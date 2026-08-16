const crypto = require("crypto");
const Alert = require("../models/alertModel");
const Trip = require("../models/tripModel");
const Vehicle = require("../models/vehicleModel");
const Participant = require("../models/participantModel");
const catchAsync = require("../utils/catchAsync");
const AppError = require("../utils/appError");
const config = require("../config/config");
const alertService = require("../services/alert.service");
const locationService = require("../services/location.service");

const broadcast = (req, event, payload) => {
  const io = req.app.get("io");
  if (io) io.to(`trip:${req.trip._id}`).emit(event, payload);
};

exports.listAlerts = catchAsync(async (req, res) => {
  const filter = { tripId: req.trip._id };
  // Default to what still needs attention; ?state=ALL for the full history.
  if (req.query.state === "ALL") {
    // no filter
  } else if (req.query.state) {
    filter.state = req.query.state;
  } else {
    filter.state = { $in: ["OPEN", "ACKNOWLEDGED"] };
  }
  if (req.query.type) filter.type = req.query.type;

  const alerts = await Alert.find(filter).sort({ severity: 1, raisedAt: -1 }).limit(200);

  res.status(200).json({
    status: "success",
    results: alerts.length,
    data: {
      alerts,
      critical: alerts.filter((a) => a.severity === "CRITICAL" && a.isLive()).length,
    },
  });
});

// Acknowledging says "I've seen this", not "this is over". It quiets the
// alert for the person who tapped it while leaving the condition open —
// which matters when five people all get the same breakdown alert.
exports.acknowledgeAlert = catchAsync(async (req, res, next) => {
  const alert = await Alert.findOne({ _id: req.params.alertId, tripId: req.trip._id });
  if (!alert) return next(new AppError("Alert not found.", 404));
  if (!alert.isLive()) {
    return res.status(200).json({ status: "success", data: { alert } });
  }

  const already = alert.acknowledgedBy.some((a) =>
    a.participantId.equals(req.participant._id)
  );
  if (!already) {
    alert.acknowledgedBy.push({ participantId: req.participant._id, at: new Date() });
    if (alert.state === "OPEN") alert.state = "ACKNOWLEDGED";
    await alert.save();
  }

  broadcast(req, "alert:acknowledged", {
    alertId: alert._id,
    by: req.participant.displayName,
    count: alert.acknowledgedBy.length,
  });

  res.status(200).json({ status: "success", data: { alert } });
});

// Manual resolution. The only way SOS and CRASH ever close — a car moving
// again is not evidence that an emergency ended.
exports.resolveAlert = catchAsync(async (req, res, next) => {
  const alert = await Alert.findOne({ _id: req.params.alertId, tripId: req.trip._id });
  if (!alert) return next(new AppError("Alert not found.", 404));

  const isRaiser = alert.participantId?.equals(req.participant._id);
  const isSameVehicle =
    alert.vehicleId && String(alert.vehicleId) === String(req.participant.vehicleId);

  // A critical alert may only be cleared by the person who raised it, someone
  // in that vehicle, or a host — so a bystander cannot silence an emergency.
  if (alert.severity === "CRITICAL" && !isRaiser && !isSameVehicle && !req.participant.canManageTrip()) {
    return next(new AppError("Only the person who raised this, their vehicle, or a host can clear it.", 403));
  }

  alert.state = req.body.cancelled ? "CANCELLED" : "RESOLVED";
  alert.resolvedAt = new Date();
  alert.resolvedBy = req.participant._id;
  alert.resolvedReason = req.body.reason || "manual";
  await alert.save();

  broadcast(req, "alert:resolved", {
    alertId: alert._id,
    vehicleId: alert.vehicleId,
    type: alert.type,
    reason: alert.resolvedReason,
    by: req.participant.displayName,
  });

  res.status(200).json({ status: "success", data: { alert } });
});

// ── SOS ──────────────────────────────────────────────────────────
// The 10-second cancellable countdown happens on the device. By the time it
// reaches here the user has confirmed, so this fires immediately and loudly
// rather than waiting for the next sweeper tick.
exports.raiseSos = catchAsync(async (req, res, next) => {
  if (!req.trip.settings.sosEnabled) {
    return next(new AppError("SOS is turned off for this trip.", 409));
  }

  const type = req.body.type === "CRASH" ? "CRASH" : "SOS";
  const vehicle = req.participant.vehicleId
    ? await Vehicle.findById(req.participant.vehicleId)
    : null;

  const alert = await alertService.raiseSos({
    trip: req.trip,
    participant: req.participant,
    vehicle,
    type,
    lat: req.body.lat,
    lng: req.body.lng,
    note: req.body.note,
  });

  // A link family can open without installing anything. Minted here so the
  // person in trouble does not have to go and find it.
  const share = await exports.mintShareToken(req.trip);

  await req.trip.touchActivity();

  broadcast(req, "alert:sos", {
    alert,
    by: req.participant.displayName,
    vehicleLabel: vehicle?.label,
  });

  res.status(201).json({
    status: "success",
    data: { alert, shareUrl: share.url, expiresAt: share.expiresAt },
  });
});

// ── Public live-share link ───────────────────────────────────────
// Read-only, expiring, revocable. For family who are not in the app.
exports.mintShareToken = async (trip) => {
  const raw = crypto.randomBytes(24).toString("hex");
  trip.shareTokenHash = crypto.createHash("sha256").update(raw).digest("hex");
  trip.shareTokenExpiresAt = new Date(
    Date.now() + config.alerts.shareLinkTtlHours * 3600 * 1000
  );
  await trip.save({ validateBeforeSave: false });

  return {
    url: `${config.trip.joinLinkBase.replace(/\/j$/, "")}/live/${raw}`,
    token: raw,
    expiresAt: trip.shareTokenExpiresAt,
  };
};

exports.createShareLink = catchAsync(async (req, res) => {
  const share = await exports.mintShareToken(req.trip);
  res.status(201).json({
    status: "success",
    message: "Anyone with this link can watch the trip until it expires.",
    data: { shareUrl: share.url, expiresAt: share.expiresAt },
  });
});

exports.revokeShareLink = catchAsync(async (req, res) => {
  req.trip.shareTokenHash = undefined;
  req.trip.shareTokenExpiresAt = undefined;
  await req.trip.save({ validateBeforeSave: false });
  res.status(200).json({ status: "success", message: "Link revoked." });
});

// Unauthenticated on purpose — the token IS the credential. Returns the
// minimum needed to see where the convoy is: no roster, no chat, no
// personal data, and nothing at all once the trip ends.
exports.publicLiveView = catchAsync(async (req, res, next) => {
  const hash = crypto.createHash("sha256").update(req.params.token).digest("hex");

  const trip = await Trip.findOne({ shareTokenHash: hash }).select(
    "+shareTokenHash +shareTokenExpiresAt"
  );
  if (!trip) return next(new AppError("This link is not valid.", 404));

  if (trip.shareTokenExpiresAt && trip.shareTokenExpiresAt < new Date()) {
    return next(new AppError("This link has expired.", 410));
  }
  // Location cannot outlive the trip, and that applies to share links too.
  if (!["ACTIVE", "PAUSED"].includes(trip.status)) {
    return next(new AppError("This trip has finished.", 410));
  }

  const vehicles = await locationService.getTripSnapshot(trip._id);
  const openCritical = await Alert.find({
    tripId: trip._id,
    severity: "CRITICAL",
    state: { $in: ["OPEN", "ACKNOWLEDGED"] },
  }).select("type message location raisedAt");

  res.status(200).json({
    status: "success",
    data: {
      trip: {
        name: trip.name,
        status: trip.status,
        destinationAddress: trip.destinationAddress,
      },
      vehicles: vehicles.map((v) => ({
        label: v.label,
        color: v.color,
        position: v.position,
        connectionState: v.connectionState,
        lastFixAgeSec: v.lastFixAgeSec,
      })),
      alerts: openCritical,
      serverTime: new Date().toISOString(),
    },
  });
});
