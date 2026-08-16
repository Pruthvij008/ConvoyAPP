const Trip = require("../models/tripModel");
const Participant = require("../models/participantModel");
const Vehicle = require("../models/vehicleModel");
const alertService = require("../services/alert.service");
const redis = require("../services/redis.service");
const config = require("../config/config");

// ─────────────────────────────────────────────────────────────
// Trip lifecycle jobs.
//
// The abandon sweeper is a privacy control, not housekeeping. Without it a
// host who forgets to end a trip keeps broadcasting their location for
// days. Every other guarantee in the app assumes location cannot outlive a
// trip; this is what makes that true when nobody remembers to press Stop.
//
// Runs far less often than the alert sweeper — the condition it looks for
// takes hours to develop.
// ─────────────────────────────────────────────────────────────

let timer = null;
let running = false;

const abandonStaleTrips = async (io) => {
  const stale = await Trip.findAbandonable();
  const ended = [];

  for (const trip of stale) {
    trip.status = "ABANDONED";
    trip.endedAt = new Date();
    await trip.save();

    // Exactly what a manual end does — sharing off for everyone, every
    // alert closed, live state dropped. Enforced server-side so it does not
    // depend on any client still being awake to hear about it.
    await Promise.all([
      Participant.updateMany({ tripId: trip._id }, { sharingState: "OFFLINE" }),
      Vehicle.updateMany({ tripId: trip._id }, { connectionState: "ENDED" }),
      alertService.resolveAllForTrip(trip._id, "trip-abandoned"),
      redis.clearTrip(trip._id),
    ]);

    io?.to(`trip:${trip._id}`).emit("trip:ended", {
      tripId: trip._id,
      status: "ABANDONED",
      reason: `No activity for ${config.trip.abandonAfterHours} hours.`,
    });

    ended.push(trip._id);
  }

  return ended;
};

// Trip.counts is denormalized so a trip list renders without an aggregation
// per row. It is $inc'd alongside every membership change, but an $inc can
// drift if a request dies between two writes. Cosmetic when wrong, so it is
// reconciled here rather than guarded with a transaction.
const reconcileCounts = async () => {
  const trips = await Trip.find({
    status: { $in: ["DRAFT", "LOBBY", "ACTIVE", "PAUSED"] },
  }).select("counts");

  let fixed = 0;

  for (const trip of trips) {
    const [participants, vehicles] = await Promise.all([
      Participant.countDocuments({ tripId: trip._id, status: "JOINED" }),
      Vehicle.countDocuments({ tripId: trip._id }),
    ]);

    if (trip.counts.participants !== participants || trip.counts.vehicles !== vehicles) {
      await Trip.updateOne(
        { _id: trip._id },
        { "counts.participants": participants, "counts.vehicles": vehicles }
      );
      fixed += 1;
    }
  }

  return fixed;
};

const tick = async (io) => {
  if (running) return;
  running = true;

  try {
    const abandoned = await abandonStaleTrips(io);
    if (abandoned.length) {
      console.log(`🧹 Auto-ended ${abandoned.length} abandoned trip(s)`);
    }

    const fixed = await reconcileCounts();
    if (fixed) console.log(`🧮 Reconciled counts on ${fixed} trip(s)`);
  } catch (err) {
    console.error("trip sweeper failed:", err.message);
  } finally {
    running = false;
  }
};

exports.start = (io) => {
  if (timer) return timer;
  const intervalMs = config.trip.sweepIntervalMin * 60 * 1000;

  timer = setInterval(() => tick(io), intervalMs);
  timer.unref?.();

  console.log(`🧹 Trip sweeper running every ${config.trip.sweepIntervalMin} min`);
  return timer;
};

exports.stop = () => {
  if (timer) clearInterval(timer);
  timer = null;
};

exports.tick = tick;
exports.abandonStaleTrips = abandonStaleTrips;
exports.reconcileCounts = reconcileCounts;
