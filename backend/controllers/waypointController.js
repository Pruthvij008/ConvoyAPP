const Waypoint = require("../models/waypointModel");
const Vehicle = require("../models/vehicleModel");
const Participant = require("../models/participantModel");
const catchAsync = require("../utils/catchAsync");
const AppError = require("../utils/appError");
const { toPoint } = require("../utils/geo");

const broadcast = (req, event, payload) => {
  const io = req.app.get("io");
  if (io) io.to(`trip:${req.trip._id}`).emit(event, payload);
};

exports.listWaypoints = catchAsync(async (req, res) => {
  const filter = { tripId: req.trip._id };
  if (req.query.state) filter.state = req.query.state;

  const waypoints = await Waypoint.find(filter).sort({ order: 1, createdAt: 1 });
  res.status(200).json({ status: "success", results: waypoints.length, data: { waypoints } });
});

// Propose a stop. Whether it lands as PROPOSED or straight to ACCEPTED
// depends on who is asking and how the host configured the trip.
exports.createWaypoint = catchAsync(async (req, res, next) => {
  const { label, lat, lng, address, markerKey, icon, color, type, isRegroupPoint, note, plannedArrivalAt } = req.body;

  if (!label?.trim()) return next(new AppError("Give the stop a name.", 400));
  if (typeof lat !== "number" || typeof lng !== "number") {
    return next(new AppError("Where? Send lat and lng.", 400));
  }

  const isHost = req.participant.canManageTrip();
  if (!isHost && !req.trip.settings.allowMemberWaypoints) {
    return next(new AppError("Only the host can add stops on this trip.", 403));
  }

  // A host's own stop needs nobody's approval. A member's does, unless the
  // host turned approval off.
  const state = isHost || !req.trip.settings.requireWaypointApproval ? "ACCEPTED" : "PROPOSED";

  const count = await Waypoint.countDocuments({ tripId: req.trip._id });
  if (count >= 50) return next(new AppError("This trip already has 50 stops.", 409));

  const waypoint = await Waypoint.create({
    tripId: req.trip._id,
    proposedBy: req.participant._id,
    label: label.trim(),
    markerKey,
    icon,
    color,
    location: toPoint(lat, lng),
    address,
    type: type || "CUSTOM",
    state,
    isRegroupPoint: !!isRegroupPoint,
    note: note?.trim(),
    plannedArrivalAt,
    order: count,
  });

  await req.trip.touchActivity();
  broadcast(req, "waypoint:created", { waypoint, by: req.participant.displayName });

  res.status(201).json({ status: "success", data: { waypoint } });
});

exports.updateWaypoint = catchAsync(async (req, res, next) => {
  const waypoint = await Waypoint.findOne({ _id: req.params.waypointId, tripId: req.trip._id });
  if (!waypoint) return next(new AppError("Stop not found.", 404));

  const isHost = req.participant.canManageTrip();
  const isProposer = waypoint.proposedBy.equals(req.participant._id);

  // Deciding a proposal is a host action; editing your own suggestion is not.
  if (req.body.state) {
    if (!isHost) return next(new AppError("Only the host can accept or reject stops.", 403));
    if (!["ACCEPTED", "REJECTED", "SKIPPED", "REACHED", "PROPOSED"].includes(req.body.state)) {
      return next(new AppError("Unknown state.", 400));
    }
    waypoint.state = req.body.state;
    if (req.body.state === "REACHED") waypoint.reachedAt = new Date();
  }

  if (!isHost && !isProposer) {
    return next(new AppError("That's not your stop.", 403));
  }

  ["label", "address", "note", "type", "icon", "color"].forEach((f) => {
    if (req.body[f] !== undefined) waypoint[f] = req.body[f];
  });
  if (req.body.isRegroupPoint !== undefined) waypoint.isRegroupPoint = !!req.body.isRegroupPoint;
  if (typeof req.body.lat === "number" && typeof req.body.lng === "number") {
    waypoint.location = toPoint(req.body.lat, req.body.lng);
  }

  await waypoint.save();
  broadcast(req, "waypoint:updated", { waypoint });
  res.status(200).json({ status: "success", data: { waypoint } });
});

// One vote per participant; changing your mind replaces the old vote rather
// than stacking a second one.
exports.voteWaypoint = catchAsync(async (req, res, next) => {
  const { vote } = req.body;
  if (!["UP", "DOWN"].includes(vote)) {
    return next(new AppError('vote must be "UP" or "DOWN".', 400));
  }

  const waypoint = await Waypoint.findOne({ _id: req.params.waypointId, tripId: req.trip._id });
  if (!waypoint) return next(new AppError("Stop not found.", 404));

  waypoint.castVote(req.participant._id, vote);
  await waypoint.save();

  broadcast(req, "waypoint:voted", {
    waypointId: waypoint._id,
    tally: waypoint.voteTally,
    by: req.participant.displayName,
  });

  res.status(200).json({ status: "success", data: { waypoint, tally: waypoint.voteTally } });
});

// Recorded against the VEHICLE, not the person — consistent with markers,
// and it's the car that arrives somewhere.
exports.arriveAtWaypoint = catchAsync(async (req, res, next) => {
  const waypoint = await Waypoint.findOne({ _id: req.params.waypointId, tripId: req.trip._id });
  if (!waypoint) return next(new AppError("Stop not found.", 404));
  if (!req.participant.vehicleId) {
    return next(new AppError("You're not in a vehicle.", 400));
  }

  waypoint.recordArrival(req.participant.vehicleId);

  // A regroup point is only satisfied when every vehicle in the trip has
  // arrived — that's the whole point of designating one.
  const vehicles = await Vehicle.find({ tripId: req.trip._id }).select("_id").lean();
  const vehicleIds = vehicles.map((v) => v._id);
  const everyoneHere = waypoint.allArrived(vehicleIds);

  if (everyoneHere && waypoint.state === "ACCEPTED") {
    waypoint.state = "REACHED";
    waypoint.reachedAt = new Date();
  }
  await waypoint.save();

  await req.trip.touchActivity();
  broadcast(req, "waypoint:arrived", {
    waypointId: waypoint._id,
    vehicleId: req.participant.vehicleId,
    arrived: waypoint.arrivals.length,
    total: vehicleIds.length,
    everyoneHere,
  });

  res.status(200).json({
    status: "success",
    data: { waypoint, arrived: waypoint.arrivals.length, total: vehicleIds.length, everyoneHere },
  });
});

// Bulk reorder — dragging the list around produces one request, not N.
exports.reorderWaypoints = catchAsync(async (req, res, next) => {
  const { order } = req.body; // array of waypoint ids, in the new order
  if (!Array.isArray(order) || !order.length) {
    return next(new AppError("Send an array of waypoint ids as `order`.", 400));
  }

  const owned = await Waypoint.find({ tripId: req.trip._id }).select("_id").lean();
  const ownedIds = new Set(owned.map((w) => String(w._id)));
  if (!order.every((id) => ownedIds.has(String(id)))) {
    return next(new AppError("That list contains a stop from another trip.", 400));
  }

  await Promise.all(
    order.map((id, i) => Waypoint.updateOne({ _id: id, tripId: req.trip._id }, { order: i }))
  );

  const waypoints = await Waypoint.find({ tripId: req.trip._id }).sort({ order: 1 });
  broadcast(req, "waypoint:reordered", { waypoints });
  res.status(200).json({ status: "success", data: { waypoints } });
});

exports.deleteWaypoint = catchAsync(async (req, res, next) => {
  const waypoint = await Waypoint.findOne({ _id: req.params.waypointId, tripId: req.trip._id });
  if (!waypoint) return next(new AppError("Stop not found.", 404));

  if (!waypoint.proposedBy.equals(req.participant._id) && !req.participant.canManageTrip()) {
    return next(new AppError("That's not your stop.", 403));
  }

  await waypoint.deleteOne();
  broadcast(req, "waypoint:deleted", { waypointId: waypoint._id });
  res.status(204).json({ status: "success", data: null });
});
