const mongoose = require("mongoose");
const { pointSchema } = require("../utils/geo");

// ─────────────────────────────────────────────────────────────
// Waypoint — a planned or proposed stop.
//
// Kept separate from Marker despite the surface resemblance. A Marker is a
// FACT ("we stopped here, for fuel"). A Waypoint is a PROPOSAL with a
// lifecycle and a vote ("should we stop here?"). Different state machines,
// different permissions, different mutation rates — merging them would give
// one collection two disjoint halves of nullable fields.
//
// Unlike Marker, a Waypoint belongs to the TRIP, not a vehicle: it is a
// shared plan. `proposedBy` records who suggested it.
// ─────────────────────────────────────────────────────────────

const waypointSchema = new mongoose.Schema(
  {
    tripId: {
      type: mongoose.Schema.ObjectId,
      ref: "Trip",
      required: true,
    },
    proposedBy: {
      type: mongoose.Schema.ObjectId,
      ref: "Participant",
      required: true,
    },

    // Snapshot from the trip's marker set, same reasoning as Marker.
    markerKey: String,
    label: { type: String, required: true, trim: true },
    icon: String,
    color: String,

    location: pointSchema(true),
    address: String,

    // Position along the route. Gaps are fine — reordering rewrites these.
    order: { type: Number, default: 0 },

    state: {
      type: String,
      enum: ["PROPOSED", "ACCEPTED", "REJECTED", "REACHED", "SKIPPED"],
      default: "PROPOSED",
    },

    // The convoy explicitly waits for everyone here, with a live checklist
    // of who has arrived. Different from a normal stop, which people trickle
    // through.
    isRegroupPoint: { type: Boolean, default: false },

    // EMBEDDED and bounded by convoy size (plan §12.1).
    votes: [
      {
        _id: false,
        participantId: { type: mongoose.Schema.ObjectId, ref: "Participant" },
        vote: { type: String, enum: ["UP", "DOWN"] },
        at: { type: Date, default: Date.now },
      },
    ],

    // Which vehicles have reached it — vehicles, not people, consistent with
    // Marker ownership. This is what the regroup checklist reads.
    arrivals: [
      {
        _id: false,
        vehicleId: { type: mongoose.Schema.ObjectId, ref: "Vehicle" },
        arrivedAt: Date,
        departedAt: Date,
      },
    ],

    plannedArrivalAt: Date,
    reachedAt: Date,
    note: { type: String, trim: true, maxlength: 500 },
  },
  {
    timestamps: true,
    toJSON: { virtuals: true },
    toObject: { virtuals: true },
  }
);

// ── Indexes ──────────────────────────────────────────────────────
waypointSchema.index({ tripId: 1, order: 1 });
waypointSchema.index({ tripId: 1, state: 1 });
waypointSchema.index({ location: "2dsphere" });

// ── Virtuals ─────────────────────────────────────────────────────
waypointSchema.virtual("voteTally").get(function () {
  const up = this.votes.filter((v) => v.vote === "UP").length;
  return { up, down: this.votes.length - up };
});

// ── Instance methods ─────────────────────────────────────────────
// One vote per participant — changing your mind replaces the old vote
// rather than stacking a second one.
waypointSchema.methods.castVote = function (participantId, vote) {
  const existing = this.votes.find((v) =>
    v.participantId.equals(participantId)
  );
  if (existing) {
    existing.vote = vote;
    existing.at = new Date();
  } else {
    this.votes.push({ participantId, vote, at: new Date() });
  }
  return this;
};

// Idempotent: a vehicle hovering on the geofence edge must not produce a
// row per re-entry.
waypointSchema.methods.recordArrival = function (vehicleId) {
  const existing = this.arrivals.find((a) => a.vehicleId.equals(vehicleId));
  if (existing) {
    existing.departedAt = undefined; // re-entered
    return this;
  }
  this.arrivals.push({ vehicleId, arrivedAt: new Date() });
  return this;
};

// A regroup point is satisfied only when every listed vehicle has arrived.
waypointSchema.methods.allArrived = function (vehicleIds) {
  const here = new Set(this.arrivals.map((a) => String(a.vehicleId)));
  return vehicleIds.every((id) => here.has(String(id)));
};

const Waypoint = mongoose.model("Waypoint", waypointSchema);
module.exports = Waypoint;
