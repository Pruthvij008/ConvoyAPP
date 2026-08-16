const Trip = require("../models/tripModel");
const Participant = require("../models/participantModel");
const catchAsync = require("../utils/catchAsync");
const AppError = require("../utils/appError");

// ─────────────────────────────────────────────────────────────
// Trip-scoped authorization — the per-trip equivalent of
// authController.restrictTo, which only knows about platform roles.
//
// These run on READS as well as writes. When a host removes someone, that
// person's app must stop receiving trip data immediately; hiding it in the
// UI is not a control. Authorization lives here so no route can forget it.
//
// Usage mirrors the template's middleware chain style:
//   router.get("/:tripId", protect, loadTrip, requireParticipant, handler)
//   router.patch("/:tripId", protect, loadTrip, requireParticipant,
//                requireTripRole("HOST", "CO_HOST"), handler)
// ─────────────────────────────────────────────────────────────

// Resolve :tripId and attach req.trip.
exports.loadTrip = catchAsync(async (req, res, next) => {
  const trip = await Trip.findById(req.params.tripId);
  if (!trip) return next(new AppError("Trip not found.", 404));
  req.trip = trip;
  next();
});

// Attach req.participant, or refuse. Must run after loadTrip + protect.
exports.requireParticipant = catchAsync(async (req, res, next) => {
  const participant = await Participant.findOne({
    tripId: req.trip._id,
    userId: req.user.id,
  });

  if (!participant || participant.status !== "JOINED") {
    // Deliberately the same message whether they were never in the trip,
    // left it, or were removed — a removed member should not be able to
    // probe the API to learn the trip still exists.
    return next(new AppError("You are not part of this trip.", 403));
  }

  req.participant = participant;
  next();
});

// Role gate. Mirrors restrictTo, but reads the TRIP role rather than the
// platform role — a user is HOST of one trip and MEMBER of another.
exports.requireTripRole =
  (...roles) =>
  (req, res, next) => {
    if (!req.participant) {
      return next(
        new AppError("requireTripRole used without requireParticipant.", 500)
      );
    }
    if (!roles.includes(req.participant.role)) {
      return next(
        new AppError("You do not have permission to do that in this trip.", 403)
      );
    }
    next();
  };

// Only the true HOST. A CO_HOST must not be able to delete the trip or
// demote the person who created it.
exports.requireOwner = (req, res, next) => {
  if (!req.participant?.isOwner()) {
    return next(new AppError("Only the trip host can do that.", 403));
  }
  next();
};

// Location may leave a device only while a trip is live (plan §4.2). Routes
// that accept or return position data gate on this.
exports.requireLiveTrip = (req, res, next) => {
  if (!req.trip.isLive) {
    return next(
      new AppError(
        `This trip is ${req.trip.status.toLowerCase()}, so location is not being shared.`,
        409
      )
    );
  }
  next();
};
