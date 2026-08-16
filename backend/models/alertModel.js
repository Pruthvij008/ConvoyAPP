const mongoose = require("mongoose");
const { pointSchema } = require("../utils/geo");

// ─────────────────────────────────────────────────────────────
// Alert — something the convoy should know about.
//
// The whole design turns on two problems that only appear in motion:
//
// 1. DUPLICATES. The sweeper runs every ~20s, and a car 6 km behind is
//    still 6 km behind on the next tick. Without the partial unique index
//    below you would raise 180 identical alerts an hour.
//
// 2. FLAPPING. A car hovering either side of the threshold would open and
//    resolve an alert every tick. Handled by hysteresis in the evaluator:
//    alerts raise at one threshold and clear at a lower one.
// ─────────────────────────────────────────────────────────────

const ALERT_TYPES = [
  "GAP",
  "OFF_ROUTE",
  "STALLED",
  "SIGNAL_LOST",
  "LOW_BATTERY",
  "SPEEDING",
  "SOS",
  "CRASH",
];

// Never auto-resolve. "The car started moving again" is not evidence that
// an emergency ended — a human has to clear these.
const MANUAL_RESOLVE_ONLY = ["SOS", "CRASH"];

const alertSchema = new mongoose.Schema(
  {
    tripId: { type: mongoose.Schema.ObjectId, ref: "Trip", required: true },
    // Alerts are about VEHICLES, consistent with markers — it is the car
    // that falls behind, not one of its passengers.
    vehicleId: { type: mongoose.Schema.ObjectId, ref: "Vehicle" },
    // Who pressed the button, for SOS. Null for anything the sweeper raised.
    participantId: { type: mongoose.Schema.ObjectId, ref: "Participant" },

    type: { type: String, enum: ALERT_TYPES, required: true },
    severity: {
      type: String,
      enum: ["INFO", "WARN", "CRITICAL"],
      default: "WARN",
    },

    // Human-readable, written once at raise time so the client never has to
    // reconstruct the sentence from raw numbers.
    message: String,

    // Type-specific detail: distanceM and minutesBehind for GAP, silence
    // duration for SIGNAL_LOST, and so on.
    payload: { type: mongoose.Schema.Types.Mixed, default: {} },

    location: pointSchema(false),

    state: {
      type: String,
      enum: ["OPEN", "ACKNOWLEDGED", "RESOLVED", "CANCELLED"],
      default: "OPEN",
    },

    // Bounded by convoy size, so safe to embed.
    acknowledgedBy: [
      {
        _id: false,
        participantId: { type: mongoose.Schema.ObjectId, ref: "Participant" },
        at: { type: Date, default: Date.now },
      },
    ],

    raisedAt: { type: Date, default: Date.now },
    resolvedAt: Date,
    resolvedBy: { type: mongoose.Schema.ObjectId, ref: "Participant" },
    // "gap-closed", "signal-returned", "stop-marked", "trip-ended", "manual"
    resolvedReason: String,

    // How many sweeper ticks have re-observed this condition. Useful for
    // "this has been going on for 20 minutes" without a second collection.
    updateCount: { type: Number, default: 0 },
    lastObservedAt: { type: Date, default: Date.now },
  },
  { timestamps: true }
);

// ── Indexes ──────────────────────────────────────────────────────
alertSchema.index({ tripId: 1, state: 1, raisedAt: -1 });

// THE de-duplication guard: at most one live alert of a given type per
// vehicle. Partial, so a vehicle can accumulate any number of RESOLVED
// alerts in its history while only ever holding one open.
alertSchema.index(
  { vehicleId: 1, type: 1 },
  {
    unique: true,
    partialFilterExpression: { state: { $in: ["OPEN", "ACKNOWLEDGED"] } },
  }
);

alertSchema.statics.ALERT_TYPES = ALERT_TYPES;
alertSchema.statics.MANUAL_RESOLVE_ONLY = MANUAL_RESOLVE_ONLY;

alertSchema.methods.isLive = function () {
  return ["OPEN", "ACKNOWLEDGED"].includes(this.state);
};

alertSchema.methods.canAutoResolve = function () {
  return !MANUAL_RESOLVE_ONLY.includes(this.type);
};

const Alert = mongoose.model("Alert", alertSchema);
module.exports = Alert;
module.exports.ALERT_TYPES = ALERT_TYPES;
module.exports.MANUAL_RESOLVE_ONLY = MANUAL_RESOLVE_ONLY;
