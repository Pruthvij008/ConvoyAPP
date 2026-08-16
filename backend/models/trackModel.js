const mongoose = require("mongoose");
const config = require("../config/config");

// ─────────────────────────────────────────────────────────────
// Track — the breadcrumb trail, stored in BUCKETS.
//
// One document per ping would mean millions of tiny documents, an index
// larger than the data, and a route replay that has to page through
// thousands of rows. One document per vehicle per ~10-minute window turns a
// 3-hour drive into ~18 documents per vehicle, and replay into a single
// ordered query.
//
// Field names inside points are deliberately short (t, x, y, s, h). At tens
// of thousands of points per trip, "latitude" repeated as a BSON key costs
// more than the number it labels.
// ─────────────────────────────────────────────────────────────

const trackSchema = new mongoose.Schema(
  {
    tripId: { type: mongoose.Schema.ObjectId, ref: "Trip", required: true },
    vehicleId: { type: mongoose.Schema.ObjectId, ref: "Vehicle", required: true },

    bucketStartAt: { type: Date, required: true },
    bucketEndAt: Date,

    points: [
      {
        _id: false,
        t: { type: Date, required: true }, // server timestamp
        x: { type: Number, required: true }, // lng
        y: { type: Number, required: true }, // lat
        s: Number, // speed km/h
        h: Number, // heading degrees
      },
    ],

    pointCount: { type: Number, default: 0 },
    distanceM: { type: Number, default: 0 },

    // Set when the bucket stops accepting points, so the writer can find the
    // open one with an indexed query instead of sorting every bucket.
    isClosed: { type: Boolean, default: false },
  },
  { timestamps: true }
);

// Replay, in order.
trackSchema.index({ tripId: 1, vehicleId: 1, bucketStartAt: 1 });
// Finding the one open bucket to append to.
trackSchema.index({ vehicleId: 1, isClosed: 1 });
// Retention: raw trails expire, trip recap stats do not (they are
// aggregated onto Vehicle at trip end).
trackSchema.index(
  { bucketStartAt: 1 },
  { expireAfterSeconds: config.location.trackRetentionDays * 24 * 60 * 60 }
);

trackSchema.methods.isFull = function () {
  if (this.pointCount >= config.location.trackBucketMaxPoints) return true;
  const ageMin = (Date.now() - this.bucketStartAt.getTime()) / 60000;
  return ageMin >= config.location.trackBucketMaxMinutes;
};

const Track = mongoose.model("Track", trackSchema);
module.exports = Track;
