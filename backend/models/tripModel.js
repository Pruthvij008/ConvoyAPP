const mongoose = require("mongoose");
const config = require("../config/config");
const { pointSchema } = require("../utils/geo");
const { mediaSchema } = require("../utils/media");
const { CATEGORIES, SEVERITIES, defaultMarkerSet } = require("../config/markerCatalogue");

// ─────────────────────────────────────────────────────────────
// Trip — the aggregate root. Everything else scopes to a trip.
//
// Note what is NOT here: no participantIds[], no vehicleIds[], no
// markers[]. Those are child collections holding tripId (plan §12.2).
// Two reasons: they are unbounded, and embedding them would make every
// phone in the convoy contend on this single document.
//
// What IS embedded is bounded, host-written, and always read with the
// trip: settings, markerSet, routeCache.
// ─────────────────────────────────────────────────────────────

// A curated marker, copied INTO the trip rather than referenced. This is a
// deliberate snapshot (plan §12.3): editing or deleting a personal custom
// marker later must not retroactively change a finished trip's history.
//
// It carries BEHAVIOUR, not just appearance. A member's custom "Tyre change"
// marker can be CRITICAL and default to wait-for-me, and it then behaves
// exactly like a built-in one — no hardcoded key list anywhere.
const tripMarkerSchema = new mongoose.Schema(
  {
    key: { type: String, required: true },
    label: { type: String, required: true, trim: true },
    icon: String,
    color: String,
    iconMedia: mediaSchema(false), // uploaded icon, beyond emoji

    // ── Picker layout ────────────────────────────────────────
    category: { type: String, enum: CATEGORIES, default: "ADMIN" },
    order: { type: Number, default: 0 },
    // The 3-4 big buttons a driver can hit without looking. Capped on save.
    isFavourite: { type: Boolean, default: false },

    // ── Behaviour ────────────────────────────────────────────
    // Drives notification loudness. CRITICAL also raises an Alert document.
    severity: { type: String, enum: SEVERITIES, default: "INFO" },
    // Whether picking this marker tells the convoy to pull over and wait.
    // Breakdown: true. Toilet: false. The driver can still override.
    defaultWaitingForGroup: { type: Boolean, default: false },
    // "Other" and "Medical" demand a word — an unexplained stop is worse
    // than no marker at all.
    requiresNote: { type: Boolean, default: false },

    isCustom: { type: Boolean, default: false },
    // Who added it to this trip's set — for the "who put this here" question.
    addedBy: { type: mongoose.Schema.ObjectId, ref: "User" },
  },
  { _id: false }
);

const tripSchema = new mongoose.Schema(
  {
    name: {
      type: String,
      required: [true, "A trip needs a name."],
      trim: true,
      maxlength: [80, "Trip name must be 80 characters or fewer."],
    },

    hostId: {
      type: mongoose.Schema.ObjectId,
      ref: "User",
      required: true,
    },

    // ── Access ───────────────────────────────────────────────────
    // Short code for reading out loud. Always present.
    joinCode: {
      type: String,
      required: true,
      unique: true,
      uppercase: true,
      trim: true,
    },

    // The link token — hashed, never stored raw (see utils/joinCode.js).
    joinTokenHash: { type: String, select: false },
    joinTokenExpiresAt: { type: Date, select: false },

    // Optional extra gate on top of the code. Hashed like a password.
    passwordHash: { type: String, select: false },

    // Read-only public live-share link, for family who are not in the app.
    // Hashed like every other token here, expiring, and revocable — and the
    // public view refuses to serve anything once the trip is over.
    shareTokenHash: { type: String, select: false },
    shareTokenExpiresAt: { type: Date, select: false },

    // ── Lifecycle ────────────────────────────────────────────────
    status: {
      type: String,
      enum: ["DRAFT", "LOBBY", "ACTIVE", "PAUSED", "ENDED", "ABANDONED"],
      default: "DRAFT",
    },

    origin: pointSchema(false),
    originAddress: String,
    destination: pointSchema(false),
    destinationAddress: String,

    // ── Settings (EMBEDDED — bounded, host-written, read with parent) ──
    settings: {
      // Even a valid link only puts you in the approval queue when this is
      // on. This is the real defence against a link forwarded to the wrong
      // group chat (plan §4.1).
      requireApproval: { type: Boolean, default: false },
      // Host closes the door once everyone is in.
      isLocked: { type: Boolean, default: false },

      // ── Gap detection ────────────────────────────────────────
      // ADAPTIVE expresses the threshold in TIME and converts it to a
      // distance using the convoy's actual rolling speed. One fixed
      // distance cannot serve both cases: 5 km is 3 minutes behind on a
      // highway and 15 minutes behind in city traffic.
      gapMode: { type: String, enum: ["ADAPTIVE", "FIXED"], default: "ADAPTIVE" },
      gapAlertMinutes: { type: Number, default: 4, min: 1, max: 60 },
      // Used when gapMode is FIXED, and as the ceiling for ADAPTIVE.
      gapAlertKm: { type: Number, default: 5, min: 0.5, max: 100 },
      // Clamps so the derived distance never becomes absurd: don't alert
      // for being one city block behind, don't stay silent across a state.
      gapMinKm: { type: Number, default: 0.8, min: 0.2, max: 10 },
      gapMaxKm: { type: Number, default: 15, min: 1, max: 200 },

      offRouteToleranceM: { type: Number, default: 500, min: 100, max: 5000 },
      stalledAfterMin: { type: Number, default: 5, min: 1, max: 60 },
      signalLostSec: { type: Number, default: 180, min: 60, max: 1800 },
      lowBatteryPct: { type: Number, default: 20, min: 5, max: 50 },
      alertsEnabled: { type: Boolean, default: true },

      sosEnabled: { type: Boolean, default: true },
      // Off by default on purpose: a false crash alert destroys trust in
      // the feature far faster than a missed one builds it (plan §3.8).
      crashDetectionEnabled: { type: Boolean, default: false },
      speedAlertKmh: { type: Number, default: null },

      allowMemberWaypoints: { type: Boolean, default: true },
      requireWaypointApproval: { type: Boolean, default: true },

      locationPrecision: {
        type: String,
        enum: ["exact", "approx"],
        default: "exact",
      },
      // Client's baseline cadence. The device still adapts around this for
      // battery (stationary → heartbeat, gap opening → tighter).
      pingIntervalSec: { type: Number, default: 15, min: 3, max: 300 },
    },

    // ── Curated marker set for this trip (EMBEDDED SNAPSHOT) ─────
    markerSet: {
      type: [tripMarkerSchema],
      default: defaultMarkerSet,
      // Keeps the in-drive picker usable: big buttons, not a grid of 30.
      validate: {
        validator: (v) => !v || v.length <= 30,
        message: "A trip can have at most 30 markers in its set.",
      },
    },

    // Recap card image.
    coverMedia: mediaSchema(false),

    // ── Route (EMBEDDED) ─────────────────────────────────────────
    // Fetched ONCE server-side and shared with every client. N members must
    // never mean N routing calls (plan §4.7).
    routeCache: {
      polyline: String, // encoded polyline, for compact client rendering
      // The same geometry as raw [lng, lat] pairs. Stored alongside the
      // encoded form so the server can do route maths (projecting a vehicle
      // onto the line for gap and off-route detection) without a decoder.
      coordinates: { type: [[Number]], default: undefined },
      distanceM: Number,
      durationS: Number,
      provider: String, // "osrm" | "ors" | "straight-line"
      fetchedAt: Date,
      // Waypoint ids in the order the route visits them, so a client can
      // tell which leg it is on without recomputing.
      waypointOrder: [{ type: mongoose.Schema.ObjectId, ref: "Waypoint" }],
    },

    // ── Denormalized counters (plan §12.3) ───────────────────────
    // So a trip list renders "5 members" without an aggregation per row.
    counts: {
      participants: { type: Number, default: 0 },
      vehicles: { type: Number, default: 0 },
      activeVehicles: { type: Number, default: 0 },
    },

    plannedStartAt: Date,
    startedAt: Date,
    endedAt: Date,

    // Drives the ABANDONED sweeper. Touched by any meaningful activity —
    // a position ping, a marker, a chat message. This is the backstop
    // against a forgotten trip broadcasting location for days (plan §4.2).
    lastActivityAt: { type: Date, default: Date.now },
  },
  {
    timestamps: true,
    toJSON: { virtuals: true },
    toObject: { virtuals: true },
  }
);

// ── Indexes (plan §13) ───────────────────────────────────────────
tripSchema.index({ status: 1, lastActivityAt: 1 }); // abandon sweeper
tripSchema.index({ hostId: 1, createdAt: -1 }); // "my trips"
tripSchema.index({ destination: "2dsphere" });

// ── Hooks ────────────────────────────────────────────────────────
// The favourites row is the 3-4 buttons a driver hits without looking. If
// everything is a favourite, nothing is — so the cap is enforced here
// rather than trusted to the client.
const MAX_FAVOURITES = 4;

tripSchema.pre("save", function (next) {
  if (!this.isModified("markerSet") || !this.markerSet?.length) return next();

  // Keys must be unique — two markers with the same key would make a
  // Marker document ambiguous about which definition it snapshotted.
  const seen = new Set();
  for (const m of this.markerSet) {
    if (seen.has(m.key)) {
      return next(
        new Error(`Duplicate marker key "${m.key}" in this trip's marker set.`)
      );
    }
    seen.add(m.key);
  }

  const favourites = this.markerSet.filter((m) => m.isFavourite);
  if (favourites.length > MAX_FAVOURITES) {
    // Keep the lowest-ordered ones rather than rejecting the save — the
    // host is curating, not doing something wrong.
    favourites
      .sort((a, b) => a.order - b.order)
      .slice(MAX_FAVOURITES)
      .forEach((m) => {
        m.isFavourite = false;
      });
  }
  next();
});

// ── Virtuals ─────────────────────────────────────────────────────
// Location may leave a device ONLY while the trip is live. Every read and
// socket path checks this rather than re-deriving the rule (plan §4.2).
tripSchema.virtual("isLive").get(function () {
  return this.status === "ACTIVE";
});

tripSchema.virtual("isJoinable").get(function () {
  return (
    ["LOBBY", "ACTIVE"].includes(this.status) && !this.settings?.isLocked
  );
});

// ── Instance methods ─────────────────────────────────────────────
// Which status transitions are legal. Centralised so no controller can
// invent its own (e.g. reviving an ENDED trip).
const ALLOWED_TRANSITIONS = {
  DRAFT: ["LOBBY", "ENDED"],
  LOBBY: ["ACTIVE", "ENDED", "ABANDONED"],
  ACTIVE: ["PAUSED", "ENDED", "ABANDONED"],
  PAUSED: ["ACTIVE", "ENDED", "ABANDONED"],
  ENDED: [],
  ABANDONED: [],
};

tripSchema.methods.canTransitionTo = function (next) {
  return (ALLOWED_TRANSITIONS[this.status] || []).includes(next);
};

tripSchema.methods.touchActivity = function () {
  this.lastActivityAt = new Date();
  return this.updateOne({ lastActivityAt: this.lastActivityAt });
};

// ── Statics ──────────────────────────────────────────────────────
// Trips the sweeper should auto-end. Uses the compound index above.
tripSchema.statics.findAbandonable = function () {
  const cutoff = new Date(
    Date.now() - config.trip.abandonAfterHours * 60 * 60 * 1000
  );
  return this.find({
    status: { $in: ["LOBBY", "ACTIVE", "PAUSED"] },
    lastActivityAt: { $lt: cutoff },
  });
};

const Trip = mongoose.model("Trip", tripSchema);
module.exports = Trip;
