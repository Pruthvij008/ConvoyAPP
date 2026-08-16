const mongoose = require("mongoose");

// ─────────────────────────────────────────────────────────────
// Participant — a User's membership in one Trip.
//
// THIS is where trip roles live, not on User. A person is HOST of one trip
// and MEMBER of another at the same time, so the role belongs to the
// membership, not the human (plan §10).
//
// Permission level and convoy position are SEPARATE fields on purpose. If
// LEAD/SWEEP shared an enum with HOST/MEMBER, "the host is also the lead
// vehicle" — an entirely normal situation — would be unrepresentable.
// ─────────────────────────────────────────────────────────────

const participantSchema = new mongoose.Schema(
  {
    tripId: {
      type: mongoose.Schema.ObjectId,
      ref: "Trip",
      required: true,
    },
    userId: {
      type: mongoose.Schema.ObjectId,
      ref: "User",
      required: true,
    },
    vehicleId: {
      type: mongoose.Schema.ObjectId,
      ref: "Vehicle",
    },

    // ── Permission level ─────────────────────────────────────────
    // CO_HOST exists so a dead host phone cannot orphan a trip.
    role: {
      type: String,
      enum: ["HOST", "CO_HOST", "MEMBER"],
      default: "MEMBER",
    },

    // ── Convoy position (independent of permissions) ─────────────
    // A hint to the gap/off-route algorithms, not a permission.
    convoyRole: {
      type: String,
      enum: ["LEAD", "SWEEP", null],
      default: null,
    },

    // Passengers ride along and can drop markers, but their phone does not
    // broadcast position — that is the vehicle's designated tracker.
    isDriver: { type: Boolean, default: false },

    // Sub-convoy this participant rides with. Mirrors Vehicle.groupId and is
    // reserved for the same reason: null today, no reader yet.
    groupId: { type: mongoose.Schema.ObjectId, default: null },

    // ── Denormalized identity snapshot (plan §12.3) ──────────────
    // Copied from User at join time and intentionally NEVER refreshed.
    // With no sign-in, people rename themselves freely; if this were a live
    // reference, someone renaming to "Batman" would rewrite every marker and
    // message they left in trips from months ago. The staleness is the point.
    displayName: { type: String, required: true, trim: true },
    photo: { type: String, default: "default.jpg" },

    status: {
      type: String,
      enum: ["PENDING", "JOINED", "LEFT", "REMOVED", "BANNED"],
      default: "JOINED",
    },

    // No invisible observers: pausing your sharing is visible to the group
    // as "paused" rather than silently going dark (plan §4.1).
    sharingState: {
      type: String,
      enum: ["SHARING", "PAUSED", "OFFLINE"],
      default: "OFFLINE",
    },

    // Recorded so a ban survives a reinstall. Imperfect — clearing app data
    // yields a new deviceId — but this is a friends-and-family app, and the
    // host approval queue is the real control (plan §4.8).
    deviceId: { type: String, select: false },

    joinedVia: {
      type: String,
      enum: ["LINK", "CODE", "INVITE", "CREATOR"],
      default: "LINK",
    },

    // Lobby readiness — "I'm fuelled up and in the car". Purely so the host
    // can see who they're still waiting on before hitting Start; it does not
    // block anything on its own.
    isReady: { type: Boolean, default: false },

    joinedAt: { type: Date, default: Date.now },
    leftAt: Date,
    lastSeenAt: { type: Date, default: Date.now },

    // Current socket, for targeted emits and presence. Cleared on disconnect.
    socketId: { type: String, select: false },
  },
  {
    timestamps: true,
    toJSON: { virtuals: true },
    toObject: { virtuals: true },
  }
);

// ── Indexes (plan §13) ───────────────────────────────────────────
// Makes double-join structurally impossible rather than a race condition to
// defend against in controller code. Two devices hitting join at the same
// instant get one success and one duplicate-key error.
participantSchema.index({ tripId: 1, userId: 1 }, { unique: true });
participantSchema.index({ userId: 1, status: 1 }); // "trips I'm in"
participantSchema.index({ tripId: 1, status: 1 }); // roster
participantSchema.index({ tripId: 1, vehicleId: 1 });

// ── Permissions ──────────────────────────────────────────────────
// One place that answers "may this participant do X", so no controller
// re-derives the rules.
const HOSTS = ["HOST", "CO_HOST"];

participantSchema.methods.isActive = function () {
  return ["JOINED", "PENDING"].includes(this.status);
};

participantSchema.methods.canManageTrip = function () {
  return this.status === "JOINED" && HOSTS.includes(this.role);
};

// Only the true HOST may delete a trip or change the host — a co-host must
// not be able to demote the person who created it.
participantSchema.methods.isOwner = function () {
  return this.status === "JOINED" && this.role === "HOST";
};

participantSchema.methods.canBroadcastLocation = function () {
  return (
    this.status === "JOINED" &&
    this.isDriver &&
    this.sharingState === "SHARING" &&
    !!this.vehicleId
  );
};

const Participant = mongoose.model("Participant", participantSchema);
module.exports = Participant;
