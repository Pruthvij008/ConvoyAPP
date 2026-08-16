const Alert = require("../models/alertModel");
const Vehicle = require("../models/vehicleModel");
const Marker = require("../models/markerModel");
const Participant = require("../models/participantModel");
const config = require("../config/config");
const locationService = require("./location.service");
const { toPoint } = require("../utils/geo");
const { gapBetween, pickReferenceVehicle } = require("../utils/route");

// ─────────────────────────────────────────────────────────────
// Alert evaluator.
//
// Runs over one trip's whole convoy at a time, reading live positions from
// Redis rather than Mongo. Cheap enough to run every ~20s per active trip.
//
// HYSTERESIS is the load-bearing idea here. Every condition raises at one
// threshold and clears at a lower one. Without it, a car hovering either
// side of the line would open and resolve an alert every tick and everyone
// would mute the app within an hour.
// ─────────────────────────────────────────────────────────────

// An alert clears at 60% of the value that raised it. Wide enough that
// ordinary speed variation cannot cross both lines in one tick.
const HYSTERESIS = 0.6;

// ── Raise / update / resolve ─────────────────────────────────────
// One live alert per (vehicle, type), enforced by the partial unique index.
// This either creates it or refreshes the existing one — never a duplicate.
const raiseOrUpdate = async ({ trip, vehicleId, type, severity, message, payload, location }) => {
  const existing = await Alert.findOne({
    vehicleId,
    type,
    state: { $in: ["OPEN", "ACKNOWLEDGED"] },
  });

  if (existing) {
    existing.payload = { ...existing.payload, ...payload };
    existing.message = message;
    existing.lastObservedAt = new Date();
    existing.updateCount += 1;
    if (location) existing.location = location;

    // A worsening condition escalates the alert it already owns rather than
    // opening a second one — a battery falling 15% → 8% is the same problem
    // getting worse. Escalation only: a momentarily better reading must not
    // quietly downgrade something the convoy has already been warned about.
    const rank = { INFO: 0, WARN: 1, CRITICAL: 2 };
    if (rank[severity] > rank[existing.severity]) {
      existing.severity = severity;
      // Re-open it so an escalation is surfaced again even if someone had
      // already acknowledged the milder version.
      if (existing.state === "ACKNOWLEDGED") existing.state = "OPEN";
      await existing.save();
      return { alert: existing, isNew: false, escalated: true };
    }

    await existing.save();
    return { alert: existing, isNew: false };
  }

  try {
    const alert = await Alert.create({
      tripId: trip._id,
      vehicleId,
      type,
      severity,
      message,
      payload,
      location,
    });
    return { alert, isNew: true };
  } catch (err) {
    // Two sweeper ticks overlapping is benign — the index did its job.
    if (err.code === 11000) return { alert: null, isNew: false };
    throw err;
  }
};

const resolve = async (vehicleId, type, reason) => {
  const alert = await Alert.findOne({
    vehicleId,
    type,
    state: { $in: ["OPEN", "ACKNOWLEDGED"] },
  });
  if (!alert || !alert.canAutoResolve()) return null;

  alert.state = "RESOLVED";
  alert.resolvedAt = new Date();
  alert.resolvedReason = reason;
  await alert.save();
  return alert;
};

// ── Adaptive gap threshold ───────────────────────────────────────
// The convoy's median speed converts a time threshold into a distance, so
// the same setting behaves sensibly on a highway and in city traffic.
const gapThresholdMeters = (trip, vehicles) => {
  const s = trip.settings;

  if (s.gapMode === "FIXED") {
    return { meters: s.gapAlertKm * 1000, basis: "fixed" };
  }

  const speeds = vehicles
    .map((v) => v.position?.speedKmh)
    .filter((x) => typeof x === "number" && x > 5)
    .sort((a, b) => a - b);

  // Nobody moving fast enough to measure — fall back to the fixed distance
  // rather than deriving a threshold from noise.
  if (!speeds.length) {
    return { meters: s.gapAlertKm * 1000, basis: "fixed-fallback" };
  }

  const median = speeds[Math.floor(speeds.length / 2)];
  const derived = (median * 1000 / 60) * s.gapAlertMinutes; // metres
  const clamped = Math.min(
    Math.max(derived, s.gapMinKm * 1000),
    Math.min(s.gapMaxKm * 1000, s.gapAlertKm * 1000 * 3)
  );

  return { meters: clamped, basis: "adaptive", medianSpeedKmh: median };
};

const fmtKm = (m) => (m >= 1000 ? `${(m / 1000).toFixed(1)} km` : `${Math.round(m)} m`);

// ── The evaluation ───────────────────────────────────────────────
exports.evaluateTrip = async (trip) => {
  if (!trip.isLive || !trip.settings.alertsEnabled) return { raised: [], resolved: [] };

  const s = trip.settings;
  const now = Date.now();
  const raised = [];
  const resolvedList = [];

  const vehicles = await locationService.getTripSnapshot(trip._id);
  if (vehicles.length < 1) return { raised, resolved: resolvedList };

  const routeCoords = trip.routeCache?.coordinates;

  // Suppression context: when the WHOLE convoy is stopped, that is traffic,
  // not a problem. Alerting everyone that everyone stopped is pure noise.
  const moving = vehicles.filter((v) => (v.position?.speedKmh || 0) > 5);
  const convoyStopped = vehicles.length > 1 && moving.length === 0;

  // Active status markers tell us a stop is explained. STALLED means "we
  // don't know why they stopped", which is only meaningful if nobody said.
  const activeStatuses = await Marker.find({
    tripId: trip._id,
    kind: "STATUS",
    state: "ACTIVE",
  }).select("vehicleId").lean();
  const explained = new Set(activeStatuses.map((m) => String(m.vehicleId)));

  const leadParticipant = await Participant.findOne({
    tripId: trip._id,
    convoyRole: "LEAD",
    status: "JOINED",
  }).select("vehicleId").lean();

  const reference = pickReferenceVehicle(vehicles, routeCoords, leadParticipant?.vehicleId);
  const gapThreshold = gapThresholdMeters(trip, vehicles);

  for (const v of vehicles) {
    const vid = v.vehicleId;
    const pos = v.position;
    const ageSec = v.lastFixAgeSec;

    // ── SIGNAL_LOST ────────────────────────────────────────────
    // Only a timer can detect this: no ping means no event to react to.
    if (ageSec == null || ageSec > s.signalLostSec) {
      const mins = ageSec == null ? null : Math.round(ageSec / 60);
      // A vehicle that has never reported isn't "lost" — it just hasn't
      // started. Only alert if we've heard from it at least once.
      if (pos) {
        const r = await raiseOrUpdate({
          trip, vehicleId: vid, type: "SIGNAL_LOST", severity: "WARN",
          message: `${v.label} hasn't reported for ${mins} min. Last seen where the pin is.`,
          payload: { silentForSec: ageSec, lastSeenAt: pos.at },
          location: toPoint(pos.lat, pos.lng),
        });
        if (r.isNew || r.escalated) raised.push(r.alert);
      }
    } else if (ageSec < s.signalLostSec * HYSTERESIS) {
      const r = await resolve(vid, "SIGNAL_LOST", "signal-returned");
      if (r) resolvedList.push(r);
    }

    if (!pos) continue;

    // ── LOW_BATTERY ────────────────────────────────────────────
    // Worth knowing before someone goes dark, not after.
    if (typeof pos.batteryPct === "number") {
      if (pos.batteryPct <= s.lowBatteryPct) {
        const r = await raiseOrUpdate({
          trip, vehicleId: vid, type: "LOW_BATTERY",
          severity: pos.batteryPct <= 10 ? "WARN" : "INFO",
          message: `${v.label} is on ${pos.batteryPct}% battery.`,
          payload: { batteryPct: pos.batteryPct },
          location: toPoint(pos.lat, pos.lng),
        });
        if (r.isNew || r.escalated) raised.push(r.alert);
      } else if (pos.batteryPct > s.lowBatteryPct + 10) {
        // +10 rather than a ratio: a phone on charge climbs slowly, and we
        // don't want it re-alerting each time it dips a percent.
        const r = await resolve(vid, "LOW_BATTERY", "charging");
        if (r) resolvedList.push(r);
      }
    }

    // ── STALLED ────────────────────────────────────────────────
    // stoppedSince is carried forward in the live position, so this is how
    // long the vehicle has ACTUALLY been stationary — not how old its last
    // fix is, which would report minutes for a car parked for an hour.
    const isStopped = (pos.speedKmh || 0) <= 5;
    const stoppedForSec = pos.stoppedSince
      ? (now - new Date(pos.stoppedSince).getTime()) / 1000
      : 0;
    if (
      isStopped &&
      !convoyStopped &&                 // everyone stopped = traffic, not a problem
      !explained.has(String(vid)) &&    // they told us why = not stalled
      v.connectionState !== "LOST" &&   // gone silent is SIGNAL_LOST, not stalled
      stoppedForSec >= s.stalledAfterMin * 60
    ) {
      const r = await raiseOrUpdate({
        trip, vehicleId: vid, type: "STALLED", severity: "WARN",
        message: `${v.label} has been stopped for ${Math.round(stoppedForSec / 60)} min without saying why.`,
        payload: { stoppedForSec },
        location: toPoint(pos.lat, pos.lng),
      });
      if (r.isNew || r.escalated) raised.push(r.alert);
    } else if (!isStopped || explained.has(String(vid))) {
      const r = await resolve(
        vid,
        "STALLED",
        explained.has(String(vid)) ? "stop-marked" : "moving-again"
      );
      if (r) resolvedList.push(r);
    }

    // ── OFF_ROUTE ──────────────────────────────────────────────
    if (routeCoords?.length >= 2) {
      const { projectOntoRoute } = require("../utils/route");
      const proj = projectOntoRoute(routeCoords, pos.lat, pos.lng);
      if (proj) {
        if (proj.offRouteM > s.offRouteToleranceM) {
          const r = await raiseOrUpdate({
            trip, vehicleId: vid, type: "OFF_ROUTE", severity: "WARN",
            message: `${v.label} is ${fmtKm(proj.offRouteM)} off the planned route.`,
            payload: { offRouteM: Math.round(proj.offRouteM) },
            location: toPoint(pos.lat, pos.lng),
          });
          if (r.isNew || r.escalated) raised.push(r.alert);
        } else if (proj.offRouteM < s.offRouteToleranceM * HYSTERESIS) {
          const r = await resolve(vid, "OFF_ROUTE", "back-on-route");
          if (r) resolvedList.push(r);
        }
      }
    }

    // ── SPEEDING ───────────────────────────────────────────────
    if (s.speedAlertKmh && typeof pos.speedKmh === "number") {
      if (pos.speedKmh > s.speedAlertKmh) {
        const r = await raiseOrUpdate({
          trip, vehicleId: vid, type: "SPEEDING", severity: "INFO",
          message: `${v.label} is doing ${Math.round(pos.speedKmh)} km/h.`,
          payload: { speedKmh: Math.round(pos.speedKmh), limit: s.speedAlertKmh },
          location: toPoint(pos.lat, pos.lng),
        });
        if (r.isNew || r.escalated) raised.push(r.alert);
      } else if (pos.speedKmh < s.speedAlertKmh * 0.9) {
        const r = await resolve(vid, "SPEEDING", "slowed-down");
        if (r) resolvedList.push(r);
      }
    }

    // ── GAP ────────────────────────────────────────────────────
    // Skipped for the reference vehicle itself, and while the whole convoy
    // is stopped (a jam spreads everyone out without anyone being lost).
    if (
      reference &&
      String(reference.vehicle.vehicleId) !== String(vid) &&
      !convoyStopped &&
      v.connectionState !== "LOST"
    ) {
      const { distanceM, method } = gapBetween(routeCoords, reference.vehicle.position, pos);

      if (distanceM > gapThreshold.meters) {
        const minsBehind = gapThreshold.medianSpeedKmh
          ? Math.round((distanceM / 1000 / gapThreshold.medianSpeedKmh) * 60)
          : null;
        const r = await raiseOrUpdate({
          trip, vehicleId: vid, type: "GAP", severity: "WARN",
          message: minsBehind
            ? `${v.label} is ${fmtKm(distanceM)} behind — about ${minsBehind} min.`
            : `${v.label} is ${fmtKm(distanceM)} behind.`,
          payload: {
            distanceM: Math.round(distanceM),
            minutesBehind: minsBehind,
            thresholdM: Math.round(gapThreshold.meters),
            // The UI can be honest about which number it is showing.
            method,
            basis: gapThreshold.basis,
            referenceVehicleId: reference.vehicle.vehicleId,
            referenceBasis: reference.basis,
          },
          location: toPoint(pos.lat, pos.lng),
        });
        if (r.isNew || r.escalated) raised.push(r.alert);
      } else if (distanceM < gapThreshold.meters * HYSTERESIS) {
        const r = await resolve(vid, "GAP", "gap-closed");
        if (r) resolvedList.push(r);
      }
    }
  }

  return { raised, resolved: resolvedList };
};

// ── Event-driven: SOS and crash ──────────────────────────────────
// These cannot wait for the next sweeper tick.
exports.raiseSos = async ({ trip, participant, vehicle, type = "SOS", lat, lng, note }) => {
  const location =
    typeof lat === "number" && typeof lng === "number"
      ? toPoint(lat, lng)
      : vehicle?.lastKnown?.point;

  // Deliberately not de-duplicated through raiseOrUpdate's silent path: if
  // an SOS is already open we refresh it, but it stays CRITICAL and open.
  const existing = await Alert.findOne({
    vehicleId: vehicle?._id,
    type,
    state: { $in: ["OPEN", "ACKNOWLEDGED"] },
  });
  if (existing) {
    existing.lastObservedAt = new Date();
    existing.updateCount += 1;
    if (location) existing.location = location;
    if (note) existing.payload = { ...existing.payload, note };
    await existing.save();
    return existing;
  }

  return Alert.create({
    tripId: trip._id,
    vehicleId: vehicle?._id,
    participantId: participant._id,
    type,
    severity: "CRITICAL",
    message:
      type === "CRASH"
        ? `Possible crash detected — ${participant.displayName}.`
        : `SOS from ${participant.displayName}.`,
    payload: { note, raisedBy: participant.displayName },
    location,
  });
};

// Trip ended: nothing may stay open, including SOS. A finished trip with a
// live emergency alert would be a permanent false signal.
exports.resolveAllForTrip = async (tripId, reason = "trip-ended") =>
  Alert.updateMany(
    { tripId, state: { $in: ["OPEN", "ACKNOWLEDGED"] } },
    { state: "RESOLVED", resolvedAt: new Date(), resolvedReason: reason }
  );

exports.HYSTERESIS = HYSTERESIS;
exports.gapThresholdMeters = gapThresholdMeters;
