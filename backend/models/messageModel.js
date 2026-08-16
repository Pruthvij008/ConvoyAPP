const mongoose = require("mongoose");
const { pointSchema } = require("../utils/geo");
const { mediaSchema } = require("../utils/media");

// ─────────────────────────────────────────────────────────────
// Message — chat, one-tap phrases, and voice clips.
//
// Retention is split by kind on purpose (plan decision §4): a photo from a
// mountain pass is the recap's whole point and lives as long as the trip; a
// voice clip saying "pulling over" is chatter that is worthless an hour
// later and expensive to keep. Only VOICE gets an expiry.
// ─────────────────────────────────────────────────────────────

const messageSchema = new mongoose.Schema(
  {
    tripId: { type: mongoose.Schema.ObjectId, ref: "Trip", required: true },
    senderId: { type: mongoose.Schema.ObjectId, ref: "Participant", required: true },

    // Denormalized snapshot, same reasoning as Participant.displayName: with
    // no sign-in people rename themselves freely, and a chat log that
    // rewrites itself when someone changes their name is worse than useless.
    senderName: { type: String, required: true },

    kind: {
      type: String,
      enum: ["TEXT", "QUICK", "VOICE", "PHOTO", "SYSTEM"],
      default: "TEXT",
    },

    body: { type: String, trim: true, maxlength: 1000 },
    // For QUICK: which canned phrase. The label is denormalized into `body`
    // so an old message still reads correctly if the catalogue changes.
    quickKey: String,
    severity: { type: String, enum: ["INFO", "WARN", "CRITICAL"], default: "INFO" },

    media: mediaSchema(false),
    durationMs: Number,

    // Where it was sent from, so a "wait up" can be pinned on the map.
    location: pointSchema(false),

    // Bounded by convoy size, so safe to embed.
    readBy: [
      {
        _id: false,
        participantId: { type: mongoose.Schema.ObjectId, ref: "Participant" },
        at: { type: Date, default: Date.now },
      },
    ],

    // Set ONLY for voice clips. A TTL index ignores documents where the
    // field is absent, so text and photos are untouched by it.
    expiresAt: Date,
  },
  { timestamps: true }
);

// Chat pagination, newest first.
messageSchema.index({ tripId: 1, createdAt: -1 });
// Voice-only expiry: absent field means the document never expires.
messageSchema.index({ expiresAt: 1 }, { expireAfterSeconds: 0 });

const Message = mongoose.model("Message", messageSchema);
module.exports = Message;
