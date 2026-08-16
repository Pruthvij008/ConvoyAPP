const crypto = require("crypto");
const mongoose = require("mongoose");
const validator = require("validator");
const bcrypt = require("bcryptjs");
const { mediaSchema } = require("../utils/media");
const { CATEGORIES, SEVERITIES } = require("../config/markerCatalogue");

const userSchema = new mongoose.Schema(
  {
    name: {
      type: String,
      required: [true, "Please tell us your name!"],
      trim: true,
    },
    // The primary credential. Unique and SPARSE, because device accounts
    // have no username at all and without sparse the second one would
    // collide on username: null.
    username: {
      type: String,
      unique: true,
      sparse: true,
      lowercase: true,
      trim: true,
      minlength: [3, "Username must be at least 3 characters."],
      maxlength: [20, "Username must be 20 characters or fewer."],
      validate: {
        validator: function (val) {
          // Letters, numbers and underscore only, so a username can be read
          // out loud and typed back without ambiguity.
          return !val || /^[a-z0-9_]+$/.test(val);
        },
        message: "Username can only use letters, numbers and underscores.",
      },
    },

    // Never schema-required. Accounts are username-based; an email is extra
    // contact detail people may add, and device accounts have none at all.
    // The unique index is SPARSE for the same reason as username.
    email: {
      type: String,
      unique: true,
      sparse: true,
      lowercase: true,
      trim: true,
      validate: {
        validator: function (val) {
          return !val || validator.isEmail(val);
        },
        message: "Please provide a valid email",
      },
    },
    // Optional — kept generic. Only validated when present.
    phone: {
      type: String,
      trim: true,
      validate: {
        validator: function (val) {
          return !val || /^\d{7,15}$/.test(val);
        },
        message: "Please provide a valid phone number",
      },
    },
    // Resolved display URL. Kept as a plain string because it may be an
    // external URL we do not own (a Google account picture) and therefore
    // has no Cloudinary publicId to delete.
    photo: { type: String, default: "default.jpg" },

    // The Cloudinary handle for a self-uploaded avatar. `photo` above
    // mirrors its url for display; this is what lets us DELETE the old one
    // when it is replaced, which a url alone cannot do.
    photoMedia: mediaSchema(false),

    // Set only when the user uploads their own avatar. We need the publicId
    // to delete it later; `photo` above mirrors its url for display, so
    // clients read one field and never branch.
    avatarMedia: mediaSchema(false),
    role: {
      type: String,
      enum: ["user", "admin"],
      default: "user",
    },

    // How this account authenticates.
    //   "local"  = username + password (the primary path)
    //   "google" = created via Google Sign-In (no password)
    //   "device" = anonymous device key, kept for guest joins and for
    //              device-level bans that survive a reinstall.
    authProvider: {
      type: String,
      enum: ["local", "google", "device"],
      default: "device",
    },
    googleId: { type: String, select: false },

    // Anonymous identity. Generated on the device at first launch and never
    // reused across installs — a reinstall creates a NEW user, which is why
    // trip rejoin goes through the original join link (plan §4.8).
    deviceId: { type: String, unique: true, sparse: true, select: false },

    password: {
      type: String,
      minlength: 8,
      select: false,
      // Required only for local accounts; Google accounts have no password.
      required: [
        function () {
          return this.authProvider === "local";
        },
        "Please provide a password",
      ],
    },
    passwordConfirm: {
      type: String,
      // Same conditional requirement as password.
      required: [
        function () {
          return this.authProvider === "local";
        },
        "Please confirm your password",
      ],
      validate: {
        // Only runs on CREATE and SAVE (not findOneAndUpdate).
        validator: function (el) {
          return el === this.password;
        },
        message: "Passwords are not the same!",
      },
      select: false,
    },

    // Email verification (OTP). Google and device accounts have nothing to
    // verify, so they start verified.
    isVerified: {
      type: Boolean,
      default: function () {
        return this.authProvider !== "local";
      },
    },
    emailOtp: { type: String, select: false },
    emailOtpExpires: { type: Date, select: false },

    // Password reset (OTP).
    passwordResetOtp: { type: String, select: false },
    passwordResetExpires: { type: Date, select: false },

    passwordChangedAt: Date,
    active: { type: Boolean, default: true, select: false },
    createdAt: { type: Date, default: Date.now },

    // ── Convoy ───────────────────────────────────────────────────
    // Everything below is EMBEDDED because it is small, bounded, read with
    // the user, and never queried on its own (plan §12.1).

    // Push targets. Bounded because a user realistically has 1-2 devices;
    // stale ones are pruned on registration rather than accumulating.
    pushTokens: [
      {
        _id: false,
        token: { type: String, required: true },
        platform: { type: String, enum: ["android", "ios", "web"] },
        lastSeenAt: { type: Date, default: Date.now },
      },
    ],

    // Reusable vehicle profiles so a repeat trip is not re-typed.
    savedVehicles: [
      {
        _id: false,
        label: { type: String, trim: true, required: true },
        type: {
          type: String,
          enum: ["CAR", "BIKE", "SUV", "VAN", "TRUCK", "OTHER"],
          default: "CAR",
        },
        color: String,
        plate: { type: String, trim: true, uppercase: true },
      },
    ],

    // The one place a phone number genuinely matters (plan §4.8). Only
    // collected when the user turns SOS on — never required to use the app.
    emergencyContacts: [
      {
        _id: false,
        name: { type: String, required: true, trim: true },
        phone: { type: String, required: true, trim: true },
        relation: String,
      },
    ],

    // Personal marker library. A trip takes a SNAPSHOT of the ones it uses
    // (Trip.markerSet), so editing or deleting one here never rewrites the
    // history of a past trip (plan §12.3).
    //
    // There is no shared "group library" collection yet. Instead anyone who
    // saw a custom marker on a trip can copy it into their own library —
    // which gets most of the benefit without inventing a Crew concept.
    customMarkers: [
      {
        _id: false,
        key: { type: String, required: true },
        label: { type: String, required: true, trim: true },
        icon: String,
        color: String,
        iconMedia: mediaSchema(false),
        // Same behaviour fields the built-ins carry, so a custom marker is
        // a first-class citizen rather than a cosmetic label.
        category: { type: String, enum: CATEGORIES, default: "ADMIN" },
        severity: { type: String, enum: SEVERITIES, default: "INFO" },
        defaultWaitingForGroup: { type: Boolean, default: false },
        requiresNote: { type: Boolean, default: false },
        // Where it came from, when copied out of a trip someone else set up.
        copiedFromTripId: { type: mongoose.Schema.ObjectId, ref: "Trip" },
      },
    ],

    preferences: {
      units: { type: String, enum: ["metric", "imperial"], default: "metric" },
      // Drives how aggressively the client throttles GPS. "saver" is forced
      // automatically below 20% battery regardless of this setting.
      batteryMode: {
        type: String,
        enum: ["accurate", "balanced", "saver"],
        default: "balanced",
      },
      language: { type: String, default: "en" },
      mapStyle: { type: String, default: "default" },
    },
  },
  {
    toJSON: { virtuals: true },
    toObject: { virtuals: true },
  }
);

// ── Hooks ────────────────────────────────────────────────────────
// Hash the password whenever it changes.
userSchema.pre("save", async function (next) {
  if (!this.isModified("password") || !this.password) return next();
  this.password = await bcrypt.hash(this.password, 12);
  this.passwordConfirm = undefined; // never persist the confirmation
  next();
});

// Stamp passwordChangedAt when the password changes (but not on signup).
userSchema.pre("save", function (next) {
  if (!this.isModified("password") || this.isNew) return next();
  this.passwordChangedAt = Date.now() - 1000; // small backdate for token safety
  next();
});

// Hide deactivated accounts from all find queries.
userSchema.pre(/^find/, function (next) {
  this.find({ active: { $ne: false } });
  next();
});

// ── Instance methods ─────────────────────────────────────────────
userSchema.methods.correctPassword = async function (candidate, hashed) {
  return bcrypt.compare(candidate, hashed);
};

userSchema.methods.changedPasswordAfter = function (jwtTimestamp) {
  if (this.passwordChangedAt) {
    const changed = parseInt(this.passwordChangedAt.getTime() / 1000, 10);
    return jwtTimestamp < changed;
  }
  return false;
};

const User = mongoose.model("User", userSchema);
module.exports = User;
