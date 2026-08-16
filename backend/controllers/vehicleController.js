const Trip = require("../models/tripModel");
const Vehicle = require("../models/vehicleModel");
const Participant = require("../models/participantModel");
const catchAsync = require("../utils/catchAsync");
const AppError = require("../utils/appError");
const tripService = require("../services/trip.service");

exports.listVehicles = catchAsync(async (req, res) => {
  const vehicles = await Vehicle.find({ tripId: req.trip._id });
  const participants = await Participant.find({
    tripId: req.trip._id,
    status: "JOINED",
  })
    .select("displayName vehicleId isDriver convoyRole")
    .lean();

  res.status(200).json({
    status: "success",
    results: vehicles.length,
    data: {
      vehicles: vehicles.map((v) => ({
        ...v.toObject({ virtuals: true }),
        connectionState: v.computeConnectionState(),
        occupants: participants.filter((p) => String(p.vehicleId) === String(v._id)),
      })),
    },
  });
});

exports.createVehicle = catchAsync(async (req, res, next) => {
  if (!req.body.label?.trim()) {
    return next(new AppError("Give the vehicle a name.", 400));
  }

  const vehicle = await tripService.addVehicle(req.trip, req.participant, req.body);
  await Trip.updateOne({ _id: req.trip._id }, { $inc: { "counts.vehicles": 1 } });

  res.status(201).json({ status: "success", data: { vehicle } });
});

exports.updateVehicle = catchAsync(async (req, res, next) => {
  const vehicle = await Vehicle.findOne({
    _id: req.params.vehicleId,
    tripId: req.trip._id,
  });
  if (!vehicle) return next(new AppError("Vehicle not found in this trip.", 404));

  // Anyone in the vehicle can rename it; only a host can touch someone
  // else's.
  const isOccupant = String(req.participant.vehicleId) === String(vehicle._id);
  if (!isOccupant && !req.participant.canManageTrip()) {
    return next(new AppError("That's not your vehicle.", 403));
  }

  ["label", "type", "color", "plate", "capacity"].forEach((f) => {
    if (req.body[f] !== undefined) vehicle[f] = req.body[f];
  });

  // Handing the tracker to another phone in the same car — used when the
  // driver's battery is dying.
  if (req.body.trackerParticipantId) {
    const tracker = await Participant.findOne({
      _id: req.body.trackerParticipantId,
      tripId: req.trip._id,
      vehicleId: vehicle._id,
      status: "JOINED",
    });
    if (!tracker) {
      return next(new AppError("That person isn't in this vehicle.", 400));
    }
    // Exactly one broadcasting phone per vehicle.
    await Participant.updateMany(
      { tripId: req.trip._id, vehicleId: vehicle._id },
      { isDriver: false }
    );
    tracker.isDriver = true;
    await tracker.save();
    vehicle.trackerParticipantId = tracker._id;
  }

  await vehicle.save();
  res.status(200).json({ status: "success", data: { vehicle } });
});

exports.deleteVehicle = catchAsync(async (req, res, next) => {
  const vehicle = await Vehicle.findOne({
    _id: req.params.vehicleId,
    tripId: req.trip._id,
  });
  if (!vehicle) return next(new AppError("Vehicle not found in this trip.", 404));

  const occupants = await Participant.countDocuments({
    tripId: req.trip._id,
    vehicleId: vehicle._id,
    status: "JOINED",
  });
  if (occupants > 0) {
    return next(
      new AppError(
        "Move everyone out of this vehicle before deleting it.",
        409
      )
    );
  }

  await vehicle.deleteOne();
  await Trip.updateOne({ _id: req.trip._id }, { $inc: { "counts.vehicles": -1 } });

  res.status(204).json({ status: "success", data: null });
});

// Join an existing vehicle as a passenger, or move between cars mid-trip.
exports.boardVehicle = catchAsync(async (req, res, next) => {
  const vehicle = await Vehicle.findOne({
    _id: req.params.vehicleId,
    tripId: req.trip._id,
  });
  if (!vehicle) return next(new AppError("Vehicle not found in this trip.", 404));

  const previousVehicleId = req.participant.vehicleId;

  req.participant.vehicleId = vehicle._id;
  // Boarding makes you a passenger. The car already has a tracker unless it
  // has none at all, in which case you become it.
  req.participant.isDriver = !vehicle.trackerParticipantId;
  await req.participant.save();

  if (!vehicle.trackerParticipantId) {
    vehicle.trackerParticipantId = req.participant._id;
    await vehicle.save();
  }

  // If that emptied the old vehicle of its tracker, hand it on.
  if (previousVehicleId && String(previousVehicleId) !== String(vehicle._id)) {
    const old = await Vehicle.findById(previousVehicleId);
    if (old?.trackerParticipantId?.equals(req.participant._id)) {
      const replacement = await Participant.findOne({
        tripId: req.trip._id,
        vehicleId: old._id,
        status: "JOINED",
        _id: { $ne: req.participant._id },
      }).sort({ joinedAt: 1 });

      old.trackerParticipantId = replacement?._id || null;
      await old.save();
      if (replacement) {
        replacement.isDriver = true;
        await replacement.save();
      }
    }
  }

  res.status(200).json({
    status: "success",
    data: { vehicle, participant: req.participant },
  });
});
