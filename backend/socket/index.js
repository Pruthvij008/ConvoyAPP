const { Server } = require("socket.io");
const { promisify } = require("util");
const jwt = require("jsonwebtoken");
const config = require("../config/config");
const User = require("../models/userModel");
const Trip = require("../models/tripModel");
const Vehicle = require("../models/vehicleModel");
const Participant = require("../models/participantModel");
const redis = require("../services/redis.service");
const locationService = require("../services/location.service");
const messageService = require("../services/message.service");
const { isValidLatLng } = require("../utils/geo");

// ─────────────────────────────────────────────────────────────
// Socket layer.
//
// One room per trip. A position update is a single server-side emit that
// the library fans out to everyone else in that room — N work, not N².
//
// Nothing in a client payload is trusted. The socket carries the identity
// established during the handshake, and every message is checked against
// that rather than against what the message claims.
// ─────────────────────────────────────────────────────────────

const room = (tripId) => `trip:${tripId}`;

// A driver pinging every 15s sends ~4/minute. 60 per minute is generous
// headroom for bursts after a reconnect flush, while still stopping a
// runaway client from flooding the convoy.
const RATE_LIMIT = { max: 60, windowSec: 60 };

// Chat is far lower volume than positions, and a burst is more likely to be
// spam than a legitimate flush after reconnecting.
const CHAT_RATE_LIMIT = { max: 30, windowSec: 60 };

exports.attach = (httpServer) => {
  const io = new Server(httpServer, {
    cors: { origin: true, credentials: true },
    // Ping timeout must outlast a short tunnel; anything less and every
    // underpass looks like a disconnect.
    pingTimeout: 30000,
    pingInterval: 15000,
  });

  // ── Handshake auth ───────────────────────────────────────────
  // Runs ONCE, before the connection exists. An unauthorised phone never
  // gets a socket at all, rather than getting one and being filtered later.
  io.use(async (socket, nextFn) => {
    try {
      const { token, tripId } = socket.handshake.auth || {};
      if (!token) return nextFn(new Error("Missing auth token."));
      if (!tripId) return nextFn(new Error("Missing tripId."));

      const decoded = await promisify(jwt.verify)(token, config.jwt.secret);
      const user = await User.findById(decoded.id);
      if (!user) return nextFn(new Error("Account no longer exists."));

      // Same rule as the REST middleware: membership is checked server-side
      // on connect, so removing someone cuts their feed immediately.
      const participant = await Participant.findOne({
        tripId,
        userId: user._id,
      });
      if (!participant || participant.status !== "JOINED") {
        return nextFn(new Error("You are not part of this trip."));
      }

      const trip = await Trip.findById(tripId);
      if (!trip) return nextFn(new Error("Trip not found."));

      // Everything later messages need, established once and never taken
      // from the payload.
      socket.data = {
        userId: String(user._id),
        tripId: String(trip._id),
        participantId: String(participant._id),
        vehicleId: participant.vehicleId ? String(participant.vehicleId) : null,
        isDriver: participant.isDriver,
        displayName: participant.displayName,
        role: participant.role,
      };

      nextFn();
    } catch (err) {
      nextFn(new Error(err.name === "TokenExpiredError" ? "Session expired." : "Not authorised."));
    }
  });

  // NOT async. Every listener below must be registered SYNCHRONOUSLY, before
  // the first await. Socket.IO silently drops an event that arrives with no
  // listener attached, so awaiting the snapshot first would open a window
  // where a client that acts immediately on `trip:snapshot` loses its first
  // messages — with no error anywhere to explain it.
  // ── Handler safety net ────────────────────────────────────
  // An async socket handler that rejects produces an UNHANDLED REJECTION,
  // and server.js answers those by closing the server and exiting. So a
  // single momentary Redis or Mongo hiccup — during a `disconnect`, which
  // fires every time somebody drives into a tunnel — would take the whole
  // process down and drop the entire convoy mid-journey.
  //
  // `position:update` and the chat handlers already had their own try/catch.
  // These wrappers cover the ones that did not, and make it hard for the
  // next handler added here to reintroduce the same hole.
  const guarded = (name, handler) => async (payload, ack) => {
    try {
      await handler(payload, ack);
    } catch (err) {
      console.error(`socket ${name} failed:`, err.message);
      ack?.({ ok: false, error: "Something went wrong." });
    }
  };

  // No payload and no ack, so nothing to report back — but still must not
  // be allowed to reject.
  const guardedVoid = (name, handler) => async () => {
    try {
      await handler();
    } catch (err) {
      console.error(`socket ${name} failed:`, err.message);
    }
  };

  io.on("connection", (socket) => {
    const { tripId, participantId, displayName } = socket.data;

    // Synchronous, so the socket is in the room before anything is emitted.
    socket.join(room(tripId));

    // ── Position updates — the hot path ───────────────────────
    socket.on("position:update", async (payload, ack) => {
      try {
        const withinRate = await redis.checkRate(socket.id, RATE_LIMIT.max, RATE_LIMIT.windowSec);
        if (!withinRate) return ack?.({ ok: false, error: "Slow down." });

        // isValidLatLng, not a typeof plus a bounds check: NaN is a number
        // and every comparison against it is false, so NaN slipped straight
        // through the old test and into the 2dsphere index.
        if (!isValidLatLng(payload?.lat, payload?.lng)) {
          return ack?.({ ok: false, error: "Coordinates out of range." });
        }

        // The vehicle is taken from the socket, never from the payload —
        // otherwise anyone could move anyone else's car around the map.
        if (!socket.data.vehicleId || !socket.data.isDriver) {
          return ack?.({ ok: false, error: "You are not broadcasting for a vehicle." });
        }

        const trip = await Trip.findById(socket.data.tripId);
        // Location may leave a device only while the trip is live. Enforced
        // here as well as in REST, because a client could keep a socket open
        // across a status change.
        if (!trip?.isLive) {
          return ack?.({ ok: false, error: "This trip is not active.", stop: true });
        }

        const vehicle = await Vehicle.findById(socket.data.vehicleId);
        if (!vehicle) return ack?.({ ok: false, error: "Vehicle not found." });

        const { position, persisted } = await locationService.ingestPosition({
          trip,
          vehicle,
          participant: { _id: socket.data.participantId },
          position: payload,
        });

        // To the room, not to the sender — the sender already knows.
        socket.to(room(tripId)).emit("vehicle:moved", {
          vehicleId: socket.data.vehicleId,
          ...position,
        });

        ack?.({ ok: true, at: position.at, persisted });
      } catch (err) {
        // Logged, not swallowed. A silent catch here hides exactly the kind
        // of failure that looks like "the ack never came back".
        console.error("position:update failed:", err.message);
        ack?.({ ok: false, error: "Could not record position." });
      }
    });

    // Explicit re-sync, for a client that knows it missed messages.
    socket.on("trip:resync", guarded("trip:resync", async (_payload, ack) => {
      const vehicles = await locationService.getTripSnapshot(tripId);
      const online = await redis.getPresence(tripId);
      ack?.({ ok: true, vehicles, online, serverTime: new Date().toISOString() });
    }));

    // ── Chat ──────────────────────────────────────────────────
    // Persisted BEFORE broadcasting. A socket emit is fire-and-forget, so
    // announcing first and saving second would occasionally show a message
    // that no longer exists after a refresh.
    socket.on("message:send", async (payload, ack) => {
      try {
        const withinRate = await redis.checkRate(
          `msg:${socket.id}`,
          CHAT_RATE_LIMIT.max,
          CHAT_RATE_LIMIT.windowSec
        );
        if (!withinRate) return ack?.({ ok: false, error: "Slow down." });

        const trip = await Trip.findById(socket.data.tripId);
        if (!trip || ["ENDED", "ABANDONED"].includes(trip.status)) {
          return ack?.({ ok: false, error: "This trip has finished." });
        }

        const participant = await Participant.findById(socket.data.participantId);
        if (!participant || participant.status !== "JOINED") {
          return ack?.({ ok: false, error: "You are not in this trip." });
        }

        const message = await messageService.sendMessage({ trip, participant, payload });

        // To the whole room INCLUDING the sender, unlike positions: a chat
        // client renders the server's copy so everyone sees identical
        // ordering and timestamps.
        io.to(room(tripId)).emit("message:new", { message });
        ack?.({ ok: true, message });
      } catch (err) {
        console.error("message:send failed:", err.message);
        ack?.({ ok: false, error: err.isOperational ? err.message : "Could not send." });
      }
    });

    socket.on("message:read", async (payload, ack) => {
      try {
        const trip = await Trip.findById(socket.data.tripId);
        const participant = await Participant.findById(socket.data.participantId);
        if (!trip || !participant) return ack?.({ ok: false });

        const count = await messageService.markRead({
          trip,
          participant,
          messageIds: payload?.messageIds,
        });

        socket.to(room(tripId)).emit("message:read", {
          messageIds: payload?.messageIds,
          by: socket.data.participantId,
          displayName,
        });
        ack?.({ ok: true, updated: count });
      } catch {
        ack?.({ ok: false });
      }
    });

    // Pausing sharing is visible to the group — no invisible observers.
    socket.on("sharing:set", guarded("sharing:set", async (payload, ack) => {
      const state = payload?.sharing === false ? "PAUSED" : "SHARING";
      await Participant.updateOne({ _id: participantId }, { sharingState: state });
      if (state === "PAUSED" && socket.data.vehicleId) {
        await redis.removeVehiclePosition(tripId, socket.data.vehicleId);
      }
      io.to(room(tripId)).emit("sharing:changed", { participantId, displayName, state });
      ack?.({ ok: true, state });
    }));

    socket.on("disconnect", guardedVoid("disconnect", async () => {
      await redis.removePresence(tripId, participantId);
      await Participant.updateOne(
        { _id: participantId },
        { socketId: null, lastSeenAt: new Date() }
      );
      // Deliberately NOT marking anyone offline or their vehicle LOST here.
      // A dropped socket usually means a tunnel, not a departure — staleness
      // is derived from the last position timestamp instead (plan §4.5).
      socket.to(room(tripId)).emit("presence:left", { participantId, displayName });
    }));

    // ── Bootstrap, after every listener is attached ───────────
    // A socket only ever delivers what happens AFTER it connects. Without
    // this snapshot, anyone joining late or coming out of a tunnel sees a
    // blank map until every car happens to move again.
    (async () => {
      try {
        await redis.addPresence(tripId, participantId);

        const [vehicles, online] = await Promise.all([
          locationService.getTripSnapshot(tripId),
          redis.getPresence(tripId),
        ]);

        socket.emit("trip:snapshot", {
          vehicles,
          online,
          // Clients render "2m ago" against this rather than their own clock,
          // which is wrong often enough to matter.
          serverTime: new Date().toISOString(),
        });

        socket.to(room(tripId)).emit("presence:joined", { participantId, displayName });

        await Participant.updateOne(
          { _id: participantId },
          { socketId: socket.id, lastSeenAt: new Date() }
        );
      } catch (err) {
        console.error("socket bootstrap failed:", err.message);
        socket.emit("trip:error", { message: "Could not load the trip. Try reconnecting." });
      }
    })();
  });

  return io;
};

// Called from REST controllers so a durable write (a marker, a trip ending)
// reaches every phone immediately. Durable first, broadcast second.
exports.broadcast = (io, tripId, event, payload) => {
  if (!io) return;
  io.to(room(tripId)).emit(event, payload);
};

exports.room = room;
