const Track = require("../models/trackModel");
const Vehicle = require("../models/vehicleModel");
const config = require("../config/config");
const redis = require("./redis.service");
const { distanceMeters, toPoint } = require("../utils/geo");

// ─────────────────────────────────────────────────────────────
// Location pipeline.
//
// Every ping does three cheap things: validate, write to Redis, broadcast.
// Only a ping that clears a threshold additionally touches MongoDB.
//
// Writing every ping to Mongo is the single most common way an app like
// this becomes expensive, so the gate below is the most load-bearing
// twenty lines in the backend.
// ─────────────────────────────────────────────────────────────

// Smallest turn that counts as a change of direction, accounting for the
// 0/360 wraparound — heading 355° to 5° is a 10° turn, not 350°.
const headingDelta = (a, b) => {
  if (a == null || b == null) return 0;
  const d = Math.abs(a - b) % 360;
  return d > 180 ? 360 - d : d;
};

// Should this ping become a permanent breadcrumb?
exports.shouldPersist = (last, next) => {
  if (!last) return { persist: true, reason: "first-fix", distanceM: 0 };

  const distance = distanceMeters(
    { lat: last.lat, lng: last.lng },
    { lat: next.lat, lng: next.lng }
  );
  const elapsedSec = (new Date(next.at) - new Date(last.at)) / 1000;
  const turn = headingDelta(last.heading, next.heading);

  // Distance first: a car on a straight highway generates the most pings
  // and needs the fewest points.
  if (distance >= config.location.historyMinDistanceM) {
    return { persist: true, reason: "moved", distanceM: distance };
  }
  // A sharp turn matters even over a short distance — without this, corners
  // get cut and the replayed route drives through buildings.
  if (turn >= config.location.historyMinHeadingDeltaDeg && distance > 5) {
    return { persist: true, reason: "turned", distanceM: distance };
  }
  // Time-based backstop so a stationary vehicle still leaves evidence that
  // it was parked there, rather than a gap in the trail.
  if (elapsedSec >= config.location.historyMinIntervalSec * 4) {
    return { persist: true, reason: "heartbeat", distanceM: distance };
  }

  return { persist: false, reason: "too-close", distanceM: distance };
};

// Append to the vehicle's open bucket, opening a new one when it is full.
exports.appendToTrack = async (tripId, vehicleId, point, distanceM) => {
  let bucket = await Track.findOne({ vehicleId, isClosed: false }).sort({
    bucketStartAt: -1,
  });

  if (bucket && bucket.isFull()) {
    bucket.isClosed = true;
    bucket.bucketEndAt = bucket.points.at(-1)?.t || new Date();
    await bucket.save();
    bucket = null;
  }

  if (!bucket) {
    bucket = new Track({
      tripId,
      vehicleId,
      bucketStartAt: new Date(point.at),
      points: [],
    });
  }

  bucket.points.push({
    t: point.at,
    x: point.lng,
    y: point.lat,
    s: point.speedKmh,
    h: point.heading,
  });
  bucket.pointCount = bucket.points.length;
  bucket.distanceM += distanceM || 0;
  await bucket.save();

  return bucket;
};

// The full path for one ping. Returns what the caller should broadcast.
// Below this a vehicle counts as stationary. Not zero: GPS jitter gives a
// parked car a metre or two of apparent movement.
const STOPPED_SPEED_KMH = 5;

exports.ingestPosition = async ({ trip, vehicle, participant, position }) => {
  // How long a vehicle has been stationary is NOT the same as how old its
  // last fix is — a parked car still sends a heartbeat every few minutes,
  // so fix age would say "2 minutes" after 20 minutes parked. Track the
  // moment it stopped and carry it forward, in Redis so this costs no
  // database write on the hot path.
  const previous = await redis.getVehiclePosition(trip._id, vehicle._id);
  const isStopped = (position.speedKmh ?? 0) <= STOPPED_SPEED_KMH;
  const stoppedSince = isStopped
    ? previous?.stoppedSince || new Date().toISOString()
    : null;

  // The server stamps the time. Device clocks are wrong often enough that
  // trusting them corrupts ordering and staleness (plan §4.5).
  const stamped = {
    stoppedSince,
    lat: position.lat,
    lng: position.lng,
    heading: position.heading ?? null,
    speedKmh: position.speedKmh ?? null,
    accuracyM: position.accuracyM ?? null,
    batteryPct: position.batteryPct ?? null,
    at: new Date().toISOString(),
    vehicleId: String(vehicle._id),
  };

  // 1. Hot state — every ping, no database.
  await redis.setVehiclePosition(trip._id, vehicle._id, stamped);

  // 2. Decide whether history cares.
  const last = await redis.getLastPersisted(vehicle._id);
  const decision = exports.shouldPersist(last, stamped);

  if (decision.persist) {
    await Promise.all([
      exports.appendToTrack(trip._id, vehicle._id, stamped, decision.distanceM),
      redis.setLastPersisted(vehicle._id, stamped),
      // Vehicle.lastKnown is the fallback the map reads when Redis is
      // unavailable, so it is refreshed on the same (throttled) cadence
      // rather than on every ping.
      Vehicle.updateOne(
        { _id: vehicle._id },
        {
          lastKnown: {
            point: toPoint(stamped.lat, stamped.lng),
            heading: stamped.heading,
            speedKmh: stamped.speedKmh,
            accuracyM: stamped.accuracyM,
            batteryPct: stamped.batteryPct,
            at: stamped.at,
          },
          connectionState: "LIVE",
          $inc: { totalDistanceM: Math.round(decision.distanceM || 0) },
          ...(stamped.speedKmh != null
            ? { $max: { maxSpeedKmh: stamped.speedKmh } }
            : {}),
        }
      ),
    ]);
  }

  return { position: stamped, persisted: decision.persist, reason: decision.reason };
};

// Snapshot for a phone that just connected or reconnected. Redis first;
// Mongo's lastKnown fills any gap so the map is never blank.
exports.getTripSnapshot = async (tripId) => {
  const [live, vehicles] = await Promise.all([
    redis.getTripPositions(tripId),
    Vehicle.find({ tripId }).lean(),
  ]);

  return vehicles.map((v) => {
    const hot = live[String(v._id)];
    const fallback = v.lastKnown?.point?.coordinates
      ? {
          lat: v.lastKnown.point.coordinates[1],
          lng: v.lastKnown.point.coordinates[0],
          heading: v.lastKnown.heading,
          speedKmh: v.lastKnown.speedKmh,
          batteryPct: v.lastKnown.batteryPct,
          at: v.lastKnown.at,
        }
      : null;

    const position = hot || fallback;
    const ageSec = position?.at
      ? Math.round((Date.now() - new Date(position.at).getTime()) / 1000)
      : null;

    return {
      vehicleId: v._id,
      label: v.label,
      color: v.color,
      type: v.type,
      currentStatus: v.currentStatus,
      position,
      lastFixAgeSec: ageSec,
      // Computed here so server and client never disagree about what "live"
      // means — a frozen dot must never be drawn as a live one.
      connectionState:
        ageSec == null
          ? "LOST"
          : ageSec <= config.location.staleAfterSec
          ? "LIVE"
          : ageSec <= config.location.lostAfterSec
          ? "STALE"
          : "LOST",
      source: hot ? "live" : fallback ? "cache" : "none",
    };
  });
};
