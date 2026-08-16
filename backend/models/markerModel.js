const mongoose = require("mongoose");
const { pointSchema } = require("../utils/geo");
const { mediaSchema } = require("../utils/media");
const { CATEGORIES, SEVERITIES } = require("../config/markerCatalogue");

// ─────────────────────────────────────────────────────────────
// Marker — something that happened, or something that is here.
//
// MARKERS BELONG TO VEHICLES, not people. Four friends in one car tapping
// "stopped for chai" is ONE stop, not four. `vehicleId` is the owner and
// `createdBy` records which passenger tapped it, purely for attribution.
// The partial unique index at the bottom is what actually enforces this.
//
// Two kinds share this collection because they share every field, index and
// query, and both render on the same map layer:
//   STATUS — attached to a vehicle, moves with it, clears when it drives on
//   PLACE  — pinned to a coordinate, persists for the trip
// ─────────────────────────────────────────────────────────────

const markerSchema = new mongoose.Schema(
  {
    tripId: {
      type: mongoose.Schema.ObjectId,
      ref: "Trip",
      required: true,
    },

    // The owning vehicle. Required for STATUS (it IS that vehicle's status);
    // optional for PLACE (a pin dropped on the map belongs to the trip).
    vehicleId: {
      type: mongoose.Schema.ObjectId,
      ref: "Vehicle",
      required: function () {
        return this.kind === "STATUS";
      },
    },

    // Attribution only — which passenger actually tapped the button. Never
    // used for ownership or de-duplication.
    createdBy: {
      type: mongoose.Schema.ObjectId,
      ref: "Participant",
      required: true,
    },

    kind: {
      type: String,
      enum: ["STATUS", "PLACE"],
      required: true,
    },

    // ── Snapshot of the marker definition (plan §12.3) ───────────
    // Copied at creation and never refreshed, so deleting or renaming the
    // definition later cannot break a finished trip's history.
    markerKey: { type: String, required: true },
    label: { type: String, required: true, trim: true },
    icon: String,
    color: String,
    iconMedia: mediaSchema(false),
    category: { type: String, enum: CATEGORIES, default: "ADMIN" },
    severity: { type: String, enum: SEVERITIES, default: "INFO" },

    location: pointSchema(true),

    note: { type: String, trim: true, maxlength: 500 },

    // Photos and voice notes. Bounded so the document cannot grow without
    // limit — additional photos would belong in a Message instead.
    media: {
      type: [mediaSchema(true)],
      validate: {
        validator: (v) => !v || v.length <= 10,
        message: "At most 10 attachments per marker.",
      },
    },

    state: {
      type: String,
      enum: ["ACTIVE", "CLEARED"],
      default: "ACTIVE",
    },

    // "wait for me" vs "go ahead, catch up later". Seeded from the marker
    // definition's defaultWaitingForGroup, then overridable by the driver.
    waitingForGroup: { type: Boolean, default: false },

    // True when stop detection raised it rather than a person tapping.
    autoDetected: { type: Boolean, default: false },

    startedAt: { type: Date, default: Date.now },
    endedAt: Date,
    durationS: Number,
  },
  {
    timestamps: true,
    toJSON: { virtuals: true },
    toObject: { virtuals: true },
  }
);

// ── Indexes ──────────────────────────────────────────────────────
markerSchema.index({ tripId: 1, state: 1, startedAt: -1 }); // map layer
markerSchema.index({ location: "2dsphere" });
markerSchema.index({ tripId: 1, vehicleId: 1, state: 1 });

// ONE active status per vehicle. This is what makes markers vehicle-owned
// rather than person-owned: the second passenger tapping "chai" hits a
// duplicate-key error instead of creating a second dot for the same stop.
// Partial, so it constrains only ACTIVE STATUS markers — a vehicle can have
// any number of cleared ones in its history.
markerSchema.index(
  { vehicleId: 1 },
  {
    unique: true,
    partialFilterExpression: { kind: "STATUS", state: "ACTIVE" },
  }
);

// ── Hooks ────────────────────────────────────────────────────────
markerSchema.pre("save", function (next) {
  if (this.isModified("state") && this.state === "CLEARED" && !this.endedAt) {
    this.endedAt = new Date();
    this.durationS = Math.round((this.endedAt - this.startedAt) / 1000);
  }
  next();
});

// ── Statics ──────────────────────────────────────────────────────
// Build a marker from the trip's curated set, inheriting its behaviour.
// Callers pass the trip so behaviour comes from the SNAPSHOT the trip
// holds, never from the live catalogue — that is what keeps an old trip
// behaving the way it did on the day.
markerSchema.statics.fromTripMarker = function (trip, markerKey, overrides = {}) {
  const def = trip.markerSet.find((m) => m.key === markerKey);
  if (!def) {
    throw new Error(`Marker "${markerKey}" is not in this trip's marker set.`);
  }
  return {
    tripId: trip._id,
    markerKey: def.key,
    label: def.label,
    icon: def.icon,
    color: def.color,
    iconMedia: def.iconMedia,
    category: def.category,
    severity: def.severity,
    waitingForGroup: def.defaultWaitingForGroup,
    ...overrides,
  };
};

// Does this marker key demand a note before it can be saved?
markerSchema.statics.requiresNote = function (trip, markerKey) {
  const def = trip.markerSet.find((m) => m.key === markerKey);
  return !!def?.requiresNote;
};

const Marker = mongoose.model("Marker", markerSchema);
module.exports = Marker;
