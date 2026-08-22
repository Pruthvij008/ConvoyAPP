const Trip = require("../models/tripModel");
const Vehicle = require("../models/vehicleModel");
const Participant = require("../models/participantModel");
const catchAsync = require("../utils/catchAsync");
const AppError = require("../utils/appError");
const tripService = require("../services/trip.service");
const alertService = require("../services/alert.service");
const routingService = require("../services/routing.service");

// Fields a host may change directly. Anything not listed — joinCode,
// joinTokenHash, counts, status, hostId — is controlled by its own endpoint
// or by the server alone.
const EDITABLE = [
  "name",
  "origin",
  "originAddress",
  "destination",
  "destinationAddress",
  "plannedStartAt",
];

const EDITABLE_SETTINGS = [
  "requireApproval",
  "isLocked",
  "gapAlertKm",
  "offRouteToleranceM",
  "stalledAfterMin",
  "sosEnabled",
  "crashDetectionEnabled",
  "speedAlertKmh",
  "allowMemberWaypoints",
  "requireWaypointApproval",
  "locationPrecision",
  "pingIntervalSec",
];

// ── Create ───────────────────────────────────────────────────────
exports.createTrip = catchAsync(async (req, res, next) => {
  if (!req.body.name?.trim()) {
    return next(new AppError("Give the trip a name.", 400));
  }

  const { trip, participant, vehicle, joinLink } = await tripService.createTrip(
    req.user,
    req.body
  );

  res.status(201).json({
    status: "success",
    data: {
      trip,
      participant,
      vehicle,
      // Shown once. The host copies this into WhatsApp; we only keep a hash,
      // so it cannot be recovered later — rotate to get a fresh one.
      joinLink,
      joinCode: trip.joinCode,
    },
  });
});

// ── Route from where I am now ────────────────────────────────────
// POST /trips/:tripId/route  { lat, lng }
//
// The trip's shared route runs origin → destination and is the journey
// everyone sees. This is a different question: "get ME from here to the
// destination", which is what a driver means by Directions and is
// necessarily per-person, since everyone is somewhere different.
//
// One call per request rather than per position update, which is what keeps
// this affordable — a route that redraws every fifteen seconds would burn a
// daily quota in minutes for a line that barely changes.
exports.getMyRoute = catchAsync(async (req, res, next) => {
  const lat = parseFloat(req.body.lat);
  const lng = parseFloat(req.body.lng);

  if (!Number.isFinite(lat) || !Number.isFinite(lng)) {
    return next(new AppError("Your current position is required.", 400));
  }

  // An explicit target wins: the caller may be routing to a friend with a
  // puncture, an SOS, or a waypoint rather than to the trip's destination.
  // Falling back to the destination keeps the common case a bare request.
  const toLat = parseFloat(req.body.toLat);
  const toLng = parseFloat(req.body.toLng);
  const hasTarget = Number.isFinite(toLat) && Number.isFinite(toLng);

  if (!hasTarget && !req.trip.destination?.coordinates?.length) {
    return next(new AppError("This trip has no destination set.", 409));
  }

  const [destLng, destLat] = hasTarget
    ? [toLng, toLat]
    : req.trip.destination.coordinates;
  const route = await routingService.getRoute(
    { lat, lng },
    { lat: destLat, lng: destLng }
  );

  if (!route) {
    return next(
      new AppError("Couldn't work out a route just now. Try again shortly.", 503)
    );
  }

  res.status(200).json({
    status: "success",
    data: {
      route: {
        coordinates: route.coordinates,
        distanceM: route.distanceM,
        durationS: route.durationS,
        provider: route.provider,
      },
    },
  });
});

// ── My trips ─────────────────────────────────────────────────────
exports.getMyTrips = catchAsync(async (req, res) => {
  const limit = Math.min(parseInt(req.query.limit, 10) || 20, 50);
  const page = Math.max(parseInt(req.query.page, 10) || 1, 1);

  const memberships = await Participant.find({
    userId: req.user.id,
    status: { $in: ["JOINED", "PENDING"] },
  })
    .select("tripId role status")
    .lean();

  const tripIds = memberships.map((m) => m.tripId);
  const roleByTrip = new Map(memberships.map((m) => [String(m.tripId), m]));

  const trips = await Trip.find({ _id: { $in: tripIds } })
    .sort({ lastActivityAt: -1 })
    .skip((page - 1) * limit)
    .limit(limit)
    .lean();

  res.status(200).json({
    status: "success",
    results: trips.length,
    data: {
      trips: trips.map((t) => ({
        ...t,
        myRole: roleByTrip.get(String(t._id))?.role,
        myStatus: roleByTrip.get(String(t._id))?.status,
      })),
    },
  });
});

// ── Detail ───────────────────────────────────────────────────────
/**
 * Rebuilds a trip's cached route when it was drawn by an older geometry
 * pipeline.
 *
 * A trip caches its line once at start and keeps it for the whole journey,
 * so a fix to how routes are simplified would otherwise reach only trips
 * that had not begun — the drive you are actually on keeps its
 * corner-cutting permanently.
 *
 * Two guards, both about not turning a routing failure into a stampede.
 * The cooldown stops a failed rebuild being retried on every poll (the app
 * polls every eight seconds, per member), and the in-flight set stops six
 * phones in one convoy each kicking off the same rebuild at once.
 */
const rebuildAttemptedAt = new Map();
const rebuildInFlight = new Set();
const REBUILD_COOLDOWN_MS = 10 * 60 * 1000;

const refreshStaleGeometry = async (trip, io) => {
  const cached = trip.routeCache;
  if (!cached?.coordinates?.length) return;
  if ((cached.geometryVersion || 0) >= routingService.GEOMETRY_VERSION) return;
  if (!trip.destination?.coordinates?.length) return;

  const key = String(trip._id);
  if (rebuildInFlight.has(key)) return;
  const last = rebuildAttemptedAt.get(key);
  if (last && Date.now() - last < REBUILD_COOLDOWN_MS) return;

  rebuildInFlight.add(key);
  rebuildAttemptedAt.set(key, Date.now());
  try {
    // Re-route from where the line already starts, not from anyone's
    // current position: this is the SHARED planned route, and silently
    // re-anchoring it to whoever happened to poll first would move the
    // whole trip's line out from under everyone else.
    const [originLng, originLat] = cached.coordinates[0];
    const [destLng, destLat] = trip.destination.coordinates;

    const route = await routingService.getRoute(
      { lat: originLat, lng: originLng },
      { lat: destLat, lng: destLng }
    );
    if (!route) return;

    trip.routeCache = {
      coordinates: route.coordinates,
      distanceM: route.distanceM,
      durationS: route.durationS,
      provider: route.provider,
      fetchedAt: new Date(),
      geometryVersion: routingService.GEOMETRY_VERSION,
    };
    await trip.save();
    if (io) io.to(`trip:${trip._id}`).emit("route:ready", { routeCache: trip.routeCache });
    console.log(`↻ Rebuilt stale route geometry for trip ${trip._id}`);
  } catch (err) {
    console.warn(`⚠️  Could not rebuild route for ${trip._id}: ${err.message}`);
  } finally {
    rebuildInFlight.delete(key);
  }
};

exports.getTrip = catchAsync(async (req, res) => {
  // Self-healing, and deliberately awaited: the caller is about to be sent
  // this trip, so handing them the corrected line now beats making them
  // wait for the next poll to see it.
  await refreshStaleGeometry(req.trip, req.app.get("io"));

  const [participants, vehicles] = await Promise.all([
    Participant.find({ tripId: req.trip._id, status: { $in: ["JOINED", "PENDING"] } }),
    Vehicle.find({ tripId: req.trip._id }),
  ]);

  res.status(200).json({
    status: "success",
    data: {
      trip: req.trip,
      me: req.participant,
      participants,
      vehicles: vehicles.map((v) => ({
        ...v.toObject({ virtuals: true }),
        // Recomputed on read so a client never sees a frozen dot labelled
        // "live" just because the stored value is stale.
        connectionState: v.computeConnectionState(),
      })),
    },
  });
});

// ── Preview (pre-join) ───────────────────────────────────────────
// What you see after tapping a shared link, BEFORE committing to join.
// Deliberately returns no location and no roster — just enough to know you
// have the right trip.
exports.previewTrip = catchAsync(async (req, res, next) => {
  const { trip } = await tripService.resolveTrip({
    token: req.body.token,
    code: req.body.code,
  });

  // DRAFT is previewable on purpose: someone who taps a link early should
  // see "Pune → Goa, not open yet" rather than a dead end. Joining is what
  // the status gates, not looking.
  if (!trip || ["ENDED", "ABANDONED"].includes(trip.status)) {
    return next(new AppError("That invite is not valid.", 404));
  }

  const host = await Participant.findOne({ tripId: trip._id, role: "HOST" })
    .select("displayName photo")
    .lean();

  res.status(200).json({
    status: "success",
    data: {
      trip: {
        _id: trip._id,
        name: trip.name,
        status: trip.status,
        destinationAddress: trip.destinationAddress,
        plannedStartAt: trip.plannedStartAt,
        memberCount: trip.counts.participants,
        vehicleCount: trip.counts.vehicles,
        requiresApproval: trip.settings.requireApproval,
        requiresPassword: !!trip.passwordHash,
        isLocked: trip.settings.isLocked,
        hostName: host?.displayName,
      },
    },
  });
});

// ── Join ─────────────────────────────────────────────────────────
exports.joinTrip = catchAsync(async (req, res) => {
  const { trip, participant, rejoined } = await tripService.joinTrip({
    user: req.user,
    token: req.body.token,
    code: req.body.code,
    password: req.body.password,
    vehicleId: req.body.vehicleId,
    vehicle: req.body.vehicle,
    deviceId: req.body.deviceId,
  });

  await trip.touchActivity();

  res.status(rejoined ? 200 : 201).json({
    status: "success",
    message:
      participant.status === "PENDING"
        ? "Request sent. The host needs to let you in."
        : "You're in.",
    data: { tripId: trip._id, participant },
  });
});

exports.leaveTrip = catchAsync(async (req, res) => {
  const { newHost } = await tripService.leaveTrip(req.trip, req.participant);
  res.status(200).json({
    status: "success",
    message: "You've left the trip.",
    data: { newHostId: newHost?._id || null },
  });
});

// ── Lobby readiness ──────────────────────────────────────────────
// "I'm in the car, fuelled, let's go." Advisory only — it tells the host
// who they're still waiting on, and never blocks the start.
exports.setReady = catchAsync(async (req, res) => {
  req.participant.isReady = req.body.ready !== false;
  req.participant.lastSeenAt = new Date();
  await req.participant.save();

  const [total, ready] = await Promise.all([
    Participant.countDocuments({ tripId: req.trip._id, status: "JOINED" }),
    Participant.countDocuments({ tripId: req.trip._id, status: "JOINED", isReady: true }),
  ]);

  res.status(200).json({
    status: "success",
    data: { participant: req.participant, ready, total },
  });
});

// What the host stares at before hitting Start.
exports.getLobby = catchAsync(async (req, res) => {
  const [participants, vehicles, pendingCount] = await Promise.all([
    Participant.find({ tripId: req.trip._id, status: "JOINED" })
      .select("displayName photo isReady isDriver vehicleId role convoyRole")
      .lean(),
    Vehicle.find({ tripId: req.trip._id }).select("label type color").lean(),
    Participant.countDocuments({ tripId: req.trip._id, status: "PENDING" }),
  ]);

  const notReady = participants.filter((p) => !p.isReady);
  const unassigned = participants.filter((p) => !p.vehicleId);

  res.status(200).json({
    status: "success",
    data: {
      status: req.trip.status,
      participants,
      vehicles,
      pendingRequests: pendingCount,
      readyCount: participants.length - notReady.length,
      total: participants.length,
      // Everything standing between the host and a clean start.
      blockers: {
        noVehicles: vehicles.length === 0,
        unassigned: unassigned.map((p) => p.displayName),
        notReady: notReady.map((p) => p.displayName),
      },
      canStart: vehicles.length > 0 && unassigned.length === 0,
    },
  });
});

// ── Update ───────────────────────────────────────────────────────
exports.updateTrip = catchAsync(async (req, res) => {
  EDITABLE.forEach((f) => {
    if (req.body[f] !== undefined) req.trip[f] = req.body[f];
  });

  if (req.body.settings) {
    EDITABLE_SETTINGS.forEach((f) => {
      if (req.body.settings[f] !== undefined) req.trip.settings[f] = req.body.settings[f];
    });
  }

  if (req.body.markerSet) req.trip.markerSet = req.body.markerSet;

  req.trip.lastActivityAt = new Date();
  await req.trip.save();

  res.status(200).json({ status: "success", data: { trip: req.trip } });
});

// ── Lifecycle ────────────────────────────────────────────────────
exports.updateStatus = catchAsync(async (req, res, next) => {
  const next_ = req.body.status;

  if (!req.trip.canTransitionTo(next_)) {
    return next(
      new AppError(
        `A ${req.trip.status} trip can't become ${next_}.`,
        409
      )
    );
  }

  // Starting is the moment location begins flowing, so it is worth one
  // preflight rather than discovering the problem on the highway.
  const isStarting = next_ === "ACTIVE" && req.trip.status !== "PAUSED";
  if (isStarting) {
    const [vehicleCount, unassigned] = await Promise.all([
      Vehicle.countDocuments({ tripId: req.trip._id }),
      Participant.find({
        tripId: req.trip._id,
        status: "JOINED",
        vehicleId: null,
      }).select("displayName"),
    ]);

    if (vehicleCount === 0) {
      return next(
        new AppError("Add at least one vehicle before starting the trip.", 409)
      );
    }

    // Someone in no car cannot be tracked and appears nowhere on the map.
    // Nameable, fixable, and overridable — the host may genuinely be
    // starting without a friend who is running late.
    if (unassigned.length && !req.body.force) {
      const names = unassigned.map((p) => p.displayName).join(", ");
      return next(
        new AppError(
          `${names} ${unassigned.length === 1 ? "isn't" : "aren't"} in a vehicle yet. Assign ${unassigned.length === 1 ? "them" : "everyone"} a car, or start anyway with force.`,
          409
        )
      );
    }
  }

  req.trip.status = next_;
  if (next_ === "ACTIVE" && !req.trip.startedAt) req.trip.startedAt = new Date();
  if (next_ === "ENDED") req.trip.endedAt = new Date();
  req.trip.lastActivityAt = new Date();
  await req.trip.save();

  // Ending a trip must stop sharing for EVERY member, server-side. This is
  // the hard guarantee that location cannot outlive a trip (plan §4.2) —
  // it does not depend on any client behaving correctly.
  if (["ENDED", "ABANDONED", "PAUSED"].includes(next_)) {
    await Promise.all([
      Participant.updateMany({ tripId: req.trip._id }, { sharingState: "OFFLINE" }),
      Vehicle.updateMany(
        { tripId: req.trip._id },
        { connectionState: next_ === "PAUSED" ? "PAUSED" : "ENDED" }
      ),
      // Nothing may stay open, SOS included. A finished trip still showing a
      // live emergency would be a permanent false signal — and there is no
      // sweeper running on a non-active trip to ever clear it.
      alertService.resolveAllForTrip(req.trip._id, `trip-${next_.toLowerCase()}`),
    ]);
  }

  // Going live flips every driver's INTENT to share. Whether their phone is
  // actually reporting is a separate question, answered by the vehicle's
  // connectionState — intent and reality are tracked apart on purpose.
  if (next_ === "ACTIVE") {
    await Participant.updateMany(
      { tripId: req.trip._id, status: "JOINED", isDriver: true },
      { sharingState: "SHARING" }
    );
  }

  // The route is fetched ONCE here and shared with everyone (plan §4.7).
  // Doing it at start rather than at creation means it reflects traffic at
  // the moment people actually leave, which is the only moment it is true.
  //
  // Never allowed to block the start: a convoy with no drawn route still
  // does everything that matters, and a routing provider having a bad
  // minute must not be why six people cannot set off.
  // A trip already under way whose cached line was built by an older,
  // worse geometry pipeline. Rebuilding it here is what stops a fix to the
  // simplifier applying only to trips that had not started yet — otherwise
  // the journey you are actually on keeps the corner-cutting it was drawn
  // with, permanently, and the only remedy is to abandon it and start again.
  const cachedGeometryIsStale =
    !!req.trip.routeCache?.coordinates?.length &&
    (req.trip.routeCache.geometryVersion || 0) < routingService.GEOMETRY_VERSION;

  if (
    (isStarting || cachedGeometryIsStale) &&
    req.trip.destination?.coordinates?.length
  ) {
    try {
      // Nobody types where they are starting from — they are standing in
      // it. So the origin is the convoy's actual position at the moment it
      // sets off, and the stored origin (if a host ever set one) only acts
      // as a fallback.
      let originCoords = null;

      const startingVehicle = await Vehicle.findOne({
        tripId: req.trip._id,
        "lastKnown.point.coordinates": { $exists: true, $ne: [] },
      }).sort({ "lastKnown.at": -1 });

      if (startingVehicle?.lastKnown?.point?.coordinates?.length === 2) {
        originCoords = startingVehicle.lastKnown.point.coordinates;
      } else if (req.trip.origin?.coordinates?.length === 2) {
        originCoords = req.trip.origin.coordinates;
      }

      // No position from anyone yet — a route from nowhere is worse than
      // no route, so it is left for the app to request once it has a fix.
      if (!originCoords) throw new Error("no starting position yet");

      const [originLng, originLat] = originCoords;
      const [destLng, destLat] = req.trip.destination.coordinates;

      const route = await routingService.getRoute(
        { lat: originLat, lng: originLng },
        { lat: destLat, lng: destLng }
      );

      if (route) {
        req.trip.routeCache = {
          coordinates: route.coordinates,
          distanceM: route.distanceM,
          durationS: route.durationS,
          provider: route.provider,
          fetchedAt: new Date(),
          geometryVersion: routingService.GEOMETRY_VERSION,
        };
        await req.trip.save();

        // Members already in the lobby get the line without refetching.
        const io = req.app.get("io");
        if (io) {
          io.to(`trip:${req.trip._id}`).emit("route:ready", {
            routeCache: req.trip.routeCache,
          });
        }
      }
    } catch (err) {
      console.warn(`⚠️  Route fetch failed for trip ${req.trip._id}: ${err.message}`);
    }
  }

  res.status(200).json({ status: "success", data: { trip: req.trip } });
});

// ── Approval queue ───────────────────────────────────────────────
exports.getJoinRequests = catchAsync(async (req, res) => {
  const requests = await Participant.find({ tripId: req.trip._id, status: "PENDING" });
  res.status(200).json({ status: "success", results: requests.length, data: { requests } });
});

exports.decideJoinRequest = catchAsync(async (req, res, next) => {
  const { decision } = req.body; // "APPROVE" | "REJECT"
  const target = await Participant.findOne({
    _id: req.params.participantId,
    tripId: req.trip._id,
    status: "PENDING",
  });
  if (!target) return next(new AppError("No pending request for that person.", 404));

  if (decision === "APPROVE") {
    target.status = "JOINED";
    target.joinedAt = new Date();
    await target.save();
    await Trip.updateOne({ _id: req.trip._id }, { $inc: { "counts.participants": 1 } });
  } else if (decision === "REJECT") {
    target.status = "REMOVED";
    await target.save();
  } else {
    return next(new AppError('decision must be "APPROVE" or "REJECT".', 400));
  }

  res.status(200).json({ status: "success", data: { participant: target } });
});

// ── Roster management ────────────────────────────────────────────
exports.updateParticipant = catchAsync(async (req, res, next) => {
  const target = await Participant.findOne({
    _id: req.params.participantId,
    tripId: req.trip._id,
  });
  if (!target) return next(new AppError("That person is not in this trip.", 404));

  // Host role is transferred through its own endpoint so the exactly-one-
  // HOST invariant lives in one place.
  if (req.body.role === "HOST") {
    return next(new AppError("Use /transfer-host to hand over the trip.", 400));
  }
  if (target.role === "HOST" && req.body.role) {
    return next(new AppError("Transfer the trip before changing the host's role.", 400));
  }

  if (req.body.role) target.role = req.body.role;

  // LEAD and SWEEP are positions, not permissions, and only one vehicle can
  // hold each — clear the previous holder first.
  if (req.body.convoyRole !== undefined) {
    if (req.body.convoyRole) {
      await Participant.updateMany(
        { tripId: req.trip._id, convoyRole: req.body.convoyRole },
        { convoyRole: null }
      );
    }
    target.convoyRole = req.body.convoyRole || null;
  }

  if (req.body.vehicleId !== undefined) {
    if (req.body.vehicleId) {
      const v = await Vehicle.findOne({ _id: req.body.vehicleId, tripId: req.trip._id });
      if (!v) return next(new AppError("That vehicle is not in this trip.", 404));
    }
    target.vehicleId = req.body.vehicleId || null;
  }

  await target.save();
  res.status(200).json({ status: "success", data: { participant: target } });
});

exports.removeParticipant = catchAsync(async (req, res, next) => {
  const target = await Participant.findOne({
    _id: req.params.participantId,
    tripId: req.trip._id,
  }).select("+deviceId");
  if (!target) return next(new AppError("That person is not in this trip.", 404));

  if (target.role === "HOST") {
    return next(new AppError("The host can't be removed. Transfer the trip first.", 400));
  }
  if (target._id.equals(req.participant._id)) {
    return next(new AppError("Use leave to exit a trip you're in.", 400));
  }

  const wasJoined = target.status === "JOINED";
  target.status = req.body.ban ? "BANNED" : "REMOVED";
  target.sharingState = "OFFLINE";
  target.leftAt = new Date();
  await target.save();

  if (wasJoined) {
    await Trip.updateOne({ _id: req.trip._id }, { $inc: { "counts.participants": -1 } });
  }

  res.status(200).json({
    status: "success",
    message: req.body.ban ? "Removed and banned." : "Removed.",
    data: { participant: target },
  });
});

exports.transferHost = catchAsync(async (req, res) => {
  const newHost = await tripService.transferHost(
    req.trip,
    req.participant,
    req.body.participantId
  );
  res.status(200).json({
    status: "success",
    message: `${newHost.displayName} is now the host.`,
    data: { participant: newHost },
  });
});

// ── Invite link ──────────────────────────────────────────────────
exports.rotateInvite = catchAsync(async (req, res) => {
  const { joinLink } = await tripService.rotateJoinToken(req.trip);
  res.status(200).json({
    status: "success",
    message: "Old links no longer work.",
    data: { joinLink, joinCode: req.trip.joinCode },
  });
});
