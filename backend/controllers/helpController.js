const Vehicle = require("../models/vehicleModel");
const Participant = require("../models/participantModel");
const Message = require("../models/messageModel");
const catchAsync = require("../utils/catchAsync");
const AppError = require("../utils/appError");

/**
 * "I'm on the way."
 *
 * Someone has a puncture and a friend peels off to reach them. The point of
 * this endpoint is not the routing — that already exists — it is telling the
 * stranded car that help is actually coming, and telling the rest of the
 * convoy why one dot just left the road.
 *
 * Announced in the group chat as a SYSTEM message rather than a private
 * ping, because the whole convoy needs to know: the lead should not keep
 * driving assuming everyone is behind them.
 */

const broadcast = (req, event, payload) => {
  const io = req.app.get("io");
  if (io) io.to(`trip:${req.trip._id}`).emit(event, payload);
};

/** A system line in the trip chat. Not attributed to a person's bubble. */
const announce = async (req, body) => {
  const message = await Message.create({
    tripId: req.trip._id,
    senderId: req.participant._id,
    senderName: req.participant.displayName,
    kind: "SYSTEM",
    body,
    severity: "INFO",
  });
  broadcast(req, "message:new", { message });
  return message;
};

// ── POST /trips/:tripId/help ─────────────────────────────────────
exports.startHelping = catchAsync(async (req, res, next) => {
  const { vehicleId } = req.body;
  if (!vehicleId) return next(new AppError("Which vehicle are you going to?", 400));

  const target = await Vehicle.findOne({ _id: vehicleId, tripId: req.trip._id });
  if (!target) return next(new AppError("That vehicle isn't in this trip.", 404));

  // Going to help yourself is not a thing, and would produce a nonsense
  // chat line that the group would have to interpret.
  if (String(req.participant.vehicleId) === String(vehicleId)) {
    return next(new AppError("That's your own vehicle.", 400));
  }

  req.participant.helpingVehicleId = target._id;
  req.participant.helpingSince = new Date();
  await req.participant.save({ validateBeforeSave: false });

  await announce(req, `${req.participant.displayName} is on the way to ${target.label}.`);

  broadcast(req, "help:started", {
    participantId: req.participant._id,
    displayName: req.participant.displayName,
    vehicleId: target._id,
    vehicleLabel: target.label,
  });

  res.status(200).json({
    status: "success",
    data: { helpingVehicleId: target._id, vehicleLabel: target.label },
  });
});

// ── DELETE /trips/:tripId/help ───────────────────────────────────
// Called both when the helper arrives and when help is no longer needed —
// a puncture fixed while someone was still driving over. `reason`
// distinguishes them, because "Rohit arrived" and "Rohit is heading back to
// the route" mean very different things to the group.
exports.stopHelping = catchAsync(async (req, res) => {
  const previous = req.participant.helpingVehicleId;

  if (!previous) {
    return res.status(200).json({ status: "success", data: { helpingVehicleId: null } });
  }

  const target = await Vehicle.findById(previous).select("label");
  const label = target?.label || "them";
  const reason = req.body?.reason;

  req.participant.helpingVehicleId = null;
  req.participant.helpingSince = undefined;
  await req.participant.save({ validateBeforeSave: false });

  await announce(
    req,
    reason === "arrived"
      ? `${req.participant.displayName} has reached ${label}.`
      : `${req.participant.displayName} is no longer heading to ${label} — carrying on to the destination.`
  );

  broadcast(req, "help:ended", {
    participantId: req.participant._id,
    displayName: req.participant.displayName,
    vehicleId: previous,
    reason: reason || "cancelled",
  });

  res.status(200).json({ status: "success", data: { helpingVehicleId: null } });
});

// ── GET /trips/:tripId/help ──────────────────────────────────────
// Who is on their way to whom. The stranded driver's screen uses this to
// say "2 people coming" rather than leaving them wondering.
exports.listHelpers = catchAsync(async (req, res) => {
  const helpers = await Participant.find({
    tripId: req.trip._id,
    helpingVehicleId: { $ne: null },
    status: "JOINED",
  }).select("displayName vehicleId helpingVehicleId helpingSince");

  res.status(200).json({ status: "success", data: { helpers } });
});
