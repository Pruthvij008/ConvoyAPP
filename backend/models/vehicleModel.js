const mongoose = require("mongoose");
const config = require("../config/config");
const { pointSchema } = require("../utils/geo");
const { mediaSchema } = require("../utils/media");

// ─────────────────────────────────────────────────────────────
// Vehicle — THE UNIT THAT APPEARS ON THE MAP.
//
// Four friends in one car is one dot, not four. Location is emitted by a
// single designated device per vehicle (trackerParticipantId); passengers
// see the map and drop markers but never broadcast.
//
// Modelling people as the tracked entity instead would stack overlapping
// dots and multiply the position firehose by car occupancy.
// ─────────────────────────────────────────────────────────────

const vehicleSchema = new mongoose.Schema(
  {
    tripId: {
      type: mongoose.Schema.ObjectId,
      ref: "Trip",
      required: true,
    },

    label: {
      type: String,
      required: [true, "Give the vehicle a name so the group can identify it."],
      trim: true,
      maxlength: 40,
    },
    type: {
      type: String,
      enum: ["CAR", "BIKE", "SUV", "VAN", "TRUCK", "OTHER"],
      default: "CAR",
    },
    // Map marker colour. Assigned from a distinct palette at creation so two
    // vehicles are never the same colour in one convoy.
    color: { type: String, default: "#2563eb" },
    plate: { type: String, trim: true, uppercase: true },
    capacity: { type: Number, min: 1, max: 60 },
    // Helps people find each other in a crowded dhaba car park.
    photo: mediaSchema(false),

    // Sub-convoys: a trip that deliberately splits into two groups, each
    // with its own lead, still visible to each other. Null means "the one
    // convoy". Reserved now because adding it later would mean migrating
    // live trips; nothing reads it yet.
    groupId: { type: mongoose.Schema.ObjectId, default: null },

    // Whose phone actually emits position for this vehicle.
    trackerParticipantId: {
      type: mongoose.Schema.ObjectId,
      ref: "Participant",
    },

    // ── Denormalized cache of Redis live state (plan §12.3/§12.4) ──
    // Redis is the source of truth for live position. This copy exists so
    // a cold start, a Redis eviction, or a Redis outage still renders a map
    // with last-known positions instead of a blank screen. Written on the
    // same throttle as history, not on every ping.
    lastKnown: {
      point: pointSchema(false),
      heading: { type: Number, min: 0, max: 360 },
      speedKmh: { type: Number, min: 0 },
      accuracyM: Number,
      batteryPct: { type: Number, min: 0, max: 100 },
      at: Date,
    },

    // The vehicle's active status marker, if it has one ("stopped for fuel").
    // Denormalized from the Marker collection so the map roster renders in
    // one query instead of a lookup per vehicle.
    currentStatus: {
      markerId: { type: mongoose.Schema.ObjectId, ref: "Marker" },
      markerKey: String,
      label: String,
      icon: String,
      since: Date,
      // "wait for me" vs "go ahead" — tells the convoy whether to pull over.
      waitingForGroup: { type: Boolean, default: false },
    },

    // Derived from lastKnown.at on read (see refreshConnectionState). Stored
    // so the sweeper can find silent vehicles without scanning every doc.
    connectionState: {
      type: String,
      enum: ["LIVE", "STALE", "LOST", "PAUSED", "ENDED"],
      default: "LOST",
    },

    // Running totals for the recap. Updated by the downsampler as it walks
    // the track, so trip-end stats need no full history scan.
    totalDistanceM: { type: Number, default: 0 },
    movingTimeS: { type: Number, default: 0 },
    stoppedTimeS: { type: Number, default: 0 },
    maxSpeedKmh: { type: Number, default: 0 },
  },
  {
    timestamps: true,
    toJSON: { virtuals: true },
    toObject: { virtuals: true },
  }
);

vehicleSchema.index({ tripId: 1 });
vehicleSchema.index({ "lastKnown.point": "2dsphere" });

// ── Staleness ────────────────────────────────────────────────────
// A frozen dot must never be drawn as a live one — "they stopped" and "we
// lost signal" look identical otherwise, and on a mountain road that
// difference causes real panic (plan §3.3, §4.5).
//
// Server and client share these thresholds via config so both agree on
// what "live" means.
vehicleSchema.methods.computeConnectionState = function (now = Date.now()) {
  if (["PAUSED", "ENDED"].includes(this.connectionState)) {
    return this.connectionState;
  }
  if (!this.lastKnown?.at) return "LOST";
  const ageSec = (now - this.lastKnown.at.getTime()) / 1000;
  if (ageSec <= config.location.staleAfterSec) return "LIVE";
  if (ageSec <= config.location.lostAfterSec) return "STALE";
  return "LOST";
};

// Age of the last fix, in seconds. The client renders "2m ago" from this
// rather than trusting its own clock against a server timestamp.
vehicleSchema.virtual("lastFixAgeSec").get(function () {
  if (!this.lastKnown?.at) return null;
  return Math.round((Date.now() - this.lastKnown.at.getTime()) / 1000);
});

const Vehicle = mongoose.model("Vehicle", vehicleSchema);
module.exports = Vehicle;
