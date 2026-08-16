// ─────────────────────────────────────────────────────────────
// Cloudinary media subdocument, shared by every collection that holds a
// photo, voice note or icon.
//
// The important field is `publicId`, not `url`. A Cloudinary asset can only
// be deleted or transformed by its public_id — there is no way to do either
// from the URL alone. Store only URLs and every photo ever uploaded stays
// on the account forever, which breaks the retention policy and turns
// storage into a bill that only goes up.
//
// `publicId` is intentionally NOT schema-required: a few media values are
// external URLs we do not own (a Google account picture, the default
// avatar). Anything WE upload must have one, and the confirm endpoint is
// what enforces that — see the ownership note below.
//
// Ownership rule: the client uploads straight to Cloudinary and then tells
// us the publicId. It must never be trusted blind. The confirm step
// verifies through the Admin API that the asset exists AND sits under this
// trip's folder prefix, otherwise a client can attach another trip's photo
// to its own marker.
// ─────────────────────────────────────────────────────────────

const mediaSchema = (required = false) => ({
  // The delete/transform handle. Absent only for external URLs.
  publicId: { type: String },

  // secure_url for display.
  url: { type: String, required },

  // Cloudinary buckets audio under "video" — a voice note is resourceType
  // "video" with no dimensions. Surprising, but that is its API.
  resourceType: {
    type: String,
    enum: ["image", "video", "raw"],
    default: "image",
  },

  format: String,
  bytes: Number,
  width: Number,
  height: Number,
  durationS: Number,

  uploadedBy: { type: require("mongoose").Schema.ObjectId, ref: "Participant" },
  uploadedAt: { type: Date, default: Date.now },
});

// Folder every asset for a trip lives under. The upload signature is scoped
// to this prefix, so a signature for trip A cannot be used to write into
// trip B, and deleting a trip is a single folder delete.
const tripFolder = (tripId) => `convoy/trips/${tripId}`;

const userFolder = (userId) => `convoy/users/${userId}`;

module.exports = { mediaSchema, tripFolder, userFolder };
