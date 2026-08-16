const Message = require("../models/messageModel");
const Trip = require("../models/tripModel");
const AppError = require("../utils/appError");
const config = require("../config/config");
const { toPoint } = require("../utils/geo");
const { findQuickMessage } = require("../config/quickMessages");

// ─────────────────────────────────────────────────────────────
// One send path, used by BOTH the socket handler and the REST endpoint.
//
// Two entry points exist deliberately: the socket is the low-latency path
// people actually use, and REST is the reliable fallback for a client whose
// socket has dropped — which, in a car, is often. Both persist before
// broadcasting, so a message is never announced and then lost.
// ─────────────────────────────────────────────────────────────

exports.sendMessage = async ({ trip, participant, payload }) => {
  const kind = payload.kind || "TEXT";

  if (!["TEXT", "QUICK", "VOICE", "PHOTO"].includes(kind)) {
    throw new AppError("Unknown message kind.", 400);
  }

  const doc = {
    tripId: trip._id,
    senderId: participant._id,
    // Snapshot, never a reference — see the model comment.
    senderName: participant.displayName,
    kind,
  };

  if (kind === "QUICK") {
    const quick = findQuickMessage(payload.quickKey);
    if (!quick) throw new AppError("Unknown quick message.", 400);
    doc.quickKey = quick.key;
    // The label is copied in so an old message still reads correctly even
    // if the catalogue is later edited.
    doc.body = quick.label;
    doc.severity = quick.severity;
  } else if (kind === "TEXT") {
    if (!payload.body?.trim()) throw new AppError("Say something.", 400);
    doc.body = payload.body.trim();
  } else if (kind === "VOICE") {
    if (!payload.media?.publicId) throw new AppError("Voice notes need an uploaded clip.", 400);
    doc.media = payload.media;
    doc.durationMs = payload.durationMs;
    // Chatter, not memories. Text and photos have no expiry at all.
    doc.expiresAt = new Date(Date.now() + config.media.voiceRetentionDays * 24 * 3600 * 1000);
  } else if (kind === "PHOTO") {
    if (!payload.media?.publicId) throw new AppError("Photos need an upload.", 400);
    doc.media = payload.media;
    doc.body = payload.body?.trim();
  }

  if (typeof payload.lat === "number" && typeof payload.lng === "number") {
    doc.location = toPoint(payload.lat, payload.lng);
  }

  const message = await Message.create(doc);

  // Chat counts as activity, so a talkative trip is never auto-abandoned.
  await Trip.updateOne({ _id: trip._id }, { lastActivityAt: new Date() });

  return message;
};

exports.markRead = async ({ trip, participant, messageIds }) => {
  if (!Array.isArray(messageIds) || !messageIds.length) return 0;

  // $addToSet on the nested field would still allow duplicates because the
  // `at` timestamp differs, so exclude anyone already present instead.
  const result = await Message.updateMany(
    {
      _id: { $in: messageIds },
      tripId: trip._id,
      "readBy.participantId": { $ne: participant._id },
    },
    { $push: { readBy: { participantId: participant._id, at: new Date() } } }
  );

  return result.modifiedCount;
};

exports.getHistory = async ({ tripId, before, limit = 50 }) => {
  const filter = { tripId };
  if (before) filter.createdAt = { $lt: new Date(before) };

  const messages = await Message.find(filter)
    .sort({ createdAt: -1 })
    .limit(Math.min(limit, 100));

  // Returned oldest-first so a client can append without reversing.
  return messages.reverse();
};
