const Trip = require("../models/tripModel");
const alertService = require("../services/alert.service");
const config = require("../config/config");

// ─────────────────────────────────────────────────────────────
// Alert sweeper.
//
// A timer is not an optimisation here, it is a requirement. SIGNAL_LOST and
// STALLED are both defined by the ABSENCE of an event — if a phone stops
// sending, there is no ping to trigger a check, so evaluation driven purely
// by incoming positions would never notice. Only a clock can.
//
// SOS and crash bypass this entirely: they are raised the instant they are
// reported, because "within 20 seconds" is not good enough for those.
// ─────────────────────────────────────────────────────────────

let timer = null;
let running = false;

const tick = async (io) => {
  // Overlap guard. A slow tick must not stack on the next one — the alert
  // table would take concurrent writes for the same conditions, and while
  // the unique index would hold, the wasted work is pointless.
  if (running) return;
  running = true;

  try {
    // Only live trips. A paused or ended trip has nothing to evaluate, and
    // its open alerts were already force-resolved on the status change.
    const trips = await Trip.find({ status: "ACTIVE" });

    for (const trip of trips) {
      try {
        const { raised, resolved } = await alertService.evaluateTrip(trip);

        // Only broadcast state CHANGES. Re-observing the same condition
        // updates the document silently — the convoy already knows.
        for (const alert of raised) {
          io?.to(`trip:${trip._id}`).emit("alert:raised", { alert });
        }
        for (const alert of resolved) {
          io?.to(`trip:${trip._id}`).emit("alert:resolved", {
            alertId: alert._id,
            vehicleId: alert.vehicleId,
            type: alert.type,
            reason: alert.resolvedReason,
          });
        }
      } catch (err) {
        // One bad trip must not stop the sweep for every other trip.
        console.error(`alert sweep failed for trip ${trip._id}:`, err.message);
      }
    }
  } catch (err) {
    console.error("alert sweeper tick failed:", err.message);
  } finally {
    running = false;
  }
};

exports.start = (io) => {
  if (timer) return timer;
  const intervalMs = config.alerts.sweepIntervalSec * 1000;

  timer = setInterval(() => tick(io), intervalMs);
  // Don't hold the process open on shutdown just for the sweeper.
  timer.unref?.();

  console.log(`🚨 Alert sweeper running every ${config.alerts.sweepIntervalSec}s`);
  return timer;
};

exports.stop = () => {
  if (timer) clearInterval(timer);
  timer = null;
};

// Exposed so tests can drive a single evaluation deterministically rather
// than waiting on wall-clock time.
exports.tick = tick;
