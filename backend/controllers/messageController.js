const catchAsync = require("../utils/catchAsync");
const AppError = require("../utils/appError");
const messageService = require("../services/message.service");
const { QUICK_MESSAGES } = require("../config/quickMessages");

const broadcast = (req, event, payload) => {
  const io = req.app.get("io");
  if (io) io.to(`trip:${req.trip._id}`).emit(event, payload);
};

// Served rather than hardcoded in the app, for the same reason as the marker
// catalogue: a client-side copy can drift from the server's behaviour.
exports.getQuickMessages = (req, res) => {
  res.status(200).json({ status: "success", data: { quickMessages: QUICK_MESSAGES } });
};

exports.listMessages = catchAsync(async (req, res) => {
  const messages = await messageService.getHistory({
    tripId: req.trip._id,
    before: req.query.before,
    limit: parseInt(req.query.limit, 10) || 50,
  });

  res.status(200).json({
    status: "success",
    results: messages.length,
    data: {
      messages,
      // Cursor for the next page, oldest message in this batch.
      nextBefore: messages.length ? messages[0].createdAt : null,
    },
  });
});

// The reliable path. The socket handler is the low-latency one people
// actually use; this exists for a client whose socket has dropped, which in
// a moving car is often.
exports.sendMessage = catchAsync(async (req, res) => {
  const message = await messageService.sendMessage({
    trip: req.trip,
    participant: req.participant,
    payload: req.body,
  });

  broadcast(req, "message:new", { message });
  res.status(201).json({ status: "success", data: { message } });
});

exports.markRead = catchAsync(async (req, res, next) => {
  const { messageIds } = req.body;
  if (!Array.isArray(messageIds) || !messageIds.length) {
    return next(new AppError("Send an array of messageIds.", 400));
  }

  const count = await messageService.markRead({
    trip: req.trip,
    participant: req.participant,
    messageIds,
  });

  // Senders want to know a critical message landed, so receipts are
  // broadcast rather than kept to the reader.
  broadcast(req, "message:read", {
    messageIds,
    by: req.participant._id,
    displayName: req.participant.displayName,
  });

  res.status(200).json({ status: "success", data: { updated: count } });
});
