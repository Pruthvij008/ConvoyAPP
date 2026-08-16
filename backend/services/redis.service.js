const Redis = require("ioredis");
const config = require("../config/config");

// ─────────────────────────────────────────────────────────────
// Redis — live vehicle state.
//
// This is the ONLY place a position is written on every ping. Mongo sees a
// position only after the downsampler decides it is worth remembering. That
// split is what keeps write cost flat as convoys grow (plan §12.4).
//
// Everything here is deliberately survivable: if Redis is down the app must
// degrade, not fail. The map falls back to Vehicle.lastKnown in Mongo, which
// is why every read below returns empty rather than throwing.
// ─────────────────────────────────────────────────────────────

let client = null;
let available = false;

const KEYS = {
  live: (tripId) => `trip:${tripId}:live`,
  presence: (tripId) => `trip:${tripId}:presence`,
  lastPersist: (vehicleId) => `vehicle:${vehicleId}:lastpersist`,
  rate: (socketId) => `rate:${socketId}`,
};

exports.connect = () => {
  if (client) return client;

  client = new Redis(config.redis.url, {
    lazyConnect: false,
    // Don't let a Redis outage stall every socket message behind a queue of
    // retries — fail fast and fall back to Mongo.
    maxRetriesPerRequest: 2,
    enableOfflineQueue: false,
    retryStrategy: (times) => Math.min(times * 500, 5000),
  });

  client.on("ready", () => {
    available = true;
    console.log("✅ Redis connected");
  });
  client.on("error", (err) => {
    if (available) console.error("⚠️  Redis error:", err.message);
    available = false;
  });
  client.on("close", () => {
    available = false;
  });

  return client;
};

exports.isAvailable = () => available;
exports.getClient = () => client;
exports.KEYS = KEYS;

// ── Live positions ───────────────────────────────────────────────
// One hash per trip, one field per vehicle, overwritten constantly. A
// position is worthless three seconds later, so nothing here is durable.
exports.setVehiclePosition = async (tripId, vehicleId, position) => {
  if (!available) return false;
  try {
    const key = KEYS.live(tripId);
    await client
      .multi()
      .hset(key, String(vehicleId), JSON.stringify(position))
      // Refreshed on every write, so a trip that goes quiet expires on its
      // own and no cleanup job is needed.
      .expire(key, config.redis.liveTtlSec)
      .exec();
    return true;
  } catch {
    return false;
  }
};

exports.getVehiclePosition = async (tripId, vehicleId) => {
  if (!available) return null;
  try {
    const raw = await client.hget(KEYS.live(tripId), String(vehicleId));
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
};

// The whole convoy in one round trip — this is the snapshot a phone gets
// the moment it joins or reconnects.
exports.getTripPositions = async (tripId) => {
  if (!available) return {};
  try {
    const raw = await client.hgetall(KEYS.live(tripId));
    const out = {};
    for (const [vehicleId, json] of Object.entries(raw || {})) {
      try {
        out[vehicleId] = JSON.parse(json);
      } catch {
        /* skip a corrupt entry rather than failing the whole snapshot */
      }
    }
    return out;
  } catch {
    return {};
  }
};

exports.removeVehiclePosition = async (tripId, vehicleId) => {
  if (!available) return;
  try {
    await client.hdel(KEYS.live(tripId), String(vehicleId));
  } catch {
    /* non-fatal */
  }
};

exports.clearTrip = async (tripId) => {
  if (!available) return;
  try {
    await client.del(KEYS.live(tripId), KEYS.presence(tripId));
  } catch {
    /* non-fatal */
  }
};

// ── Presence ─────────────────────────────────────────────────────
// Who currently has a socket open. NOT the same as who is on the trip — a
// phone in a tunnel is still very much on the trip (plan §4.5).
exports.addPresence = async (tripId, participantId) => {
  if (!available) return;
  try {
    await client
      .multi()
      .sadd(KEYS.presence(tripId), String(participantId))
      .expire(KEYS.presence(tripId), config.redis.liveTtlSec)
      .exec();
  } catch {
    /* non-fatal */
  }
};

exports.removePresence = async (tripId, participantId) => {
  if (!available) return;
  try {
    await client.srem(KEYS.presence(tripId), String(participantId));
  } catch {
    /* non-fatal */
  }
};

exports.getPresence = async (tripId) => {
  if (!available) return [];
  try {
    return await client.smembers(KEYS.presence(tripId));
  } catch {
    return [];
  }
};

// ── Downsampler bookkeeping ──────────────────────────────────────
// The last position actually persisted to Track history, used to decide
// whether the next ping has moved far enough to be worth storing.
exports.getLastPersisted = async (vehicleId) => {
  if (!available) return null;
  try {
    const raw = await client.get(KEYS.lastPersist(vehicleId));
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
};

exports.setLastPersisted = async (vehicleId, position) => {
  if (!available) return;
  try {
    await client.set(
      KEYS.lastPersist(vehicleId),
      JSON.stringify(position),
      "EX",
      config.redis.liveTtlSec
    );
  } catch {
    /* non-fatal */
  }
};

// ── Rate limiting ────────────────────────────────────────────────
// A buggy or hostile client must not be able to flood the convoy. Counts
// messages per socket in a rolling window.
exports.checkRate = async (socketId, limit, windowSec) => {
  if (!available) return true; // fail open — never block a real driver
  try {
    const key = KEYS.rate(socketId);
    const count = await client.incr(key);
    if (count === 1) await client.expire(key, windowSec);
    return count <= limit;
  } catch {
    return true;
  }
};

exports.disconnect = async () => {
  if (client) {
    await client.quit().catch(() => {});
    client = null;
    available = false;
  }
};
