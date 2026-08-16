const bcrypt = require("bcryptjs");
const Trip = require("../models/tripModel");
const Vehicle = require("../models/vehicleModel");
const Participant = require("../models/participantModel");
const AppError = require("../utils/appError");
const config = require("../config/config");
const {
  generateJoinCode,
  generateJoinToken,
  hashJoinToken,
} = require("../utils/joinCode");

// ─────────────────────────────────────────────────────────────
// Trip service — the operations that must not be re-derived per route.
//
// Everything here protects an invariant: exactly one HOST, counts that
// match reality, a trip that can never be orphaned, and a rejoin path that
// does not collide with the unique (tripId, userId) index.
// ─────────────────────────────────────────────────────────────

// Distinct enough to tell apart at a glance on a map, and distinguishable
// for the most common colour-vision deficiencies. Assigned round-robin so
// two vehicles in one convoy are never the same colour.
const VEHICLE_PALETTE = [
  "#2563EB", "#DC2626", "#16A34A", "#D97706",
  "#7C3AED", "#0891B2", "#DB2777", "#65A30D",
];

const pickVehicleColor = async (tripId) => {
  const used = await Vehicle.find({ tripId }).select("color").lean();
  const taken = new Set(used.map((v) => v.color));
  return VEHICLE_PALETTE.find((c) => !taken.has(c)) || VEHICLE_PALETTE[used.length % VEHICLE_PALETTE.length];
};

const buildJoinLink = (token) => `${config.trip.joinLinkBase}/${token}`;

// ── Create ───────────────────────────────────────────────────────
// The raw join token is returned ONCE, here. Only its hash is stored, so a
// database dump yields no working links (same approach as the OTP flow).
exports.createTrip = async (user, body) => {
  const rawToken = generateJoinToken();

  const base = {
    name: body.name,
    hostId: user._id,
    // A trip is created in order to be shared, so it opens for joining
    // immediately. DRAFT is only for planning something days ahead, and has
    // to be asked for — otherwise the host copies a link that does not work
    // yet, which is the worst possible first impression.
    status: body.asDraft ? "DRAFT" : "LOBBY",
    joinTokenHash: hashJoinToken(rawToken),
    joinTokenExpiresAt: new Date(
      Date.now() + config.trip.joinTokenExpiresInHours * 3600 * 1000
    ),
    origin: body.origin,
    originAddress: body.originAddress,
    destination: body.destination,
    destinationAddress: body.destinationAddress,
    plannedStartAt: body.plannedStartAt,
  };

  if (body.password) {
    base.passwordHash = await bcrypt.hash(body.password, 12);
  }

  // joinCode is a short random string, so collisions are possible. Retry
  // against the unique index rather than trusting a single draw.
  let trip;
  for (let attempt = 0; attempt < 5; attempt += 1) {
    try {
      trip = await Trip.create({ ...base, joinCode: generateJoinCode() });
      break;
    } catch (err) {
      const isCodeCollision =
        err.code === 11000 && JSON.stringify(err.keyPattern || {}).includes("joinCode");
      if (!isCodeCollision) throw err;
    }
  }
  if (!trip) {
    throw new AppError("Could not allocate a join code. Please try again.", 500);
  }

  // The creator is HOST and, unless they say otherwise, a driver.
  const participant = await Participant.create({
    tripId: trip._id,
    userId: user._id,
    role: "HOST",
    displayName: user.name,
    photo: user.photo,
    status: "JOINED",
    joinedVia: "CREATOR",
    isDriver: true,
    deviceId: body.deviceId,
  });

  let vehicle = null;
  if (body.vehicle) {
    vehicle = await exports.addVehicle(trip, participant, body.vehicle);
  }

  await Trip.updateOne(
    { _id: trip._id },
    { $inc: { "counts.participants": 1, "counts.vehicles": vehicle ? 1 : 0 } }
  );

  return { trip, participant, vehicle, rawToken, joinLink: buildJoinLink(rawToken) };
};

// ── Vehicle ──────────────────────────────────────────────────────
// Creating a vehicle makes the creator its tracker: the phone that actually
// broadcasts. Passengers join an existing vehicle instead.
exports.addVehicle = async (trip, participant, data) => {
  const count = await Vehicle.countDocuments({ tripId: trip._id });
  if (count >= config.trip.maxVehiclesPerTrip) {
    throw new AppError(
      `A trip can hold at most ${config.trip.maxVehiclesPerTrip} vehicles.`,
      409
    );
  }

  const vehicle = await Vehicle.create({
    tripId: trip._id,
    label: data.label,
    type: data.type,
    plate: data.plate,
    capacity: data.capacity,
    color: data.color || (await pickVehicleColor(trip._id)),
    trackerParticipantId: participant._id,
  });

  participant.vehicleId = vehicle._id;
  participant.isDriver = true;
  await participant.save();

  return vehicle;
};

// ── Resolve a trip from a shared link or a spoken code ────────────
exports.resolveTrip = async ({ token, code }) => {
  const select = "+joinTokenHash +joinTokenExpiresAt +passwordHash";

  if (token) {
    const trip = await Trip.findOne({ joinTokenHash: hashJoinToken(token) }).select(select);
    if (!trip) return { trip: null, via: "LINK" };
    // Links expire so a message forwarded into the wrong group months later
    // is inert. The code still works — the host controls that separately.
    if (trip.joinTokenExpiresAt && trip.joinTokenExpiresAt < new Date()) {
      throw new AppError("This invite link has expired. Ask the host for a new one.", 410);
    }
    return { trip, via: "LINK" };
  }

  if (code) {
    const trip = await Trip.findOne({ joinCode: String(code).toUpperCase().trim() }).select(select);
    return { trip, via: "CODE" };
  }

  throw new AppError("Provide an invite link or a join code.", 400);
};

// ── Join ─────────────────────────────────────────────────────────
exports.joinTrip = async ({ user, token, code, password, vehicleId, vehicle, deviceId }) => {
  const { trip, via } = await exports.resolveTrip({ token, code });

  // Same message for "wrong code" and "no such trip" so the endpoint cannot
  // be used to enumerate valid codes.
  if (!trip) throw new AppError("That invite is not valid.", 404);

  if (["ENDED", "ABANDONED"].includes(trip.status)) {
    throw new AppError("This trip has already finished.", 410);
  }
  if (trip.status === "DRAFT") {
    throw new AppError("The host hasn't opened this trip for joining yet.", 409);
  }
  if (trip.settings.isLocked) {
    throw new AppError("The host has locked this trip.", 403);
  }
  if (trip.passwordHash) {
    if (!password || !(await bcrypt.compare(password, trip.passwordHash))) {
      throw new AppError("That trip password is incorrect.", 401);
    }
  }

  // Rejoin path. The unique (tripId, userId) index means a returning member
  // CANNOT be inserted again — leaving a trip would otherwise lock you out
  // of it permanently. Update the existing row instead.
  const existing = await Participant.findOne({ tripId: trip._id, userId: user._id });

  if (existing) {
    if (["REMOVED", "BANNED"].includes(existing.status)) {
      throw new AppError("You can't rejoin this trip.", 403);
    }
    if (existing.status === "JOINED" || existing.status === "PENDING") {
      return { trip, participant: existing, rejoined: false };
    }
    // status === "LEFT"
    existing.status = trip.settings.requireApproval ? "PENDING" : "JOINED";
    existing.leftAt = undefined;
    existing.joinedAt = new Date();
    existing.lastSeenAt = new Date();
    existing.displayName = user.name; // refresh the snapshot on a fresh join
    if (deviceId) existing.deviceId = deviceId;
    await existing.save();

    if (existing.status === "JOINED") {
      await Trip.updateOne({ _id: trip._id }, { $inc: { "counts.participants": 1 } });
    }
    return { trip, participant: existing, rejoined: true };
  }

  // Device-level ban: a removed member who cleared app data returns with a
  // new userId but the same device. Imperfect (a reinstall changes it) —
  // the approval queue is the real control.
  if (deviceId) {
    const banned = await Participant.findOne({
      tripId: trip._id,
      deviceId,
      status: "BANNED",
    }).select("+deviceId");
    if (banned) throw new AppError("You can't rejoin this trip.", 403);
  }

  const participant = await Participant.create({
    tripId: trip._id,
    userId: user._id,
    role: "MEMBER",
    displayName: user.name,
    photo: user.photo,
    status: trip.settings.requireApproval ? "PENDING" : "JOINED",
    joinedVia: via,
    deviceId,
  });

  // Attach to a vehicle: ride with someone, or bring your own.
  if (vehicleId) {
    const target = await Vehicle.findOne({ _id: vehicleId, tripId: trip._id });
    if (!target) throw new AppError("That vehicle is not in this trip.", 404);
    participant.vehicleId = target._id;
    participant.isDriver = false; // passenger — does not broadcast
    await participant.save();
  } else if (vehicle) {
    await exports.addVehicle(trip, participant, vehicle);
    await Trip.updateOne({ _id: trip._id }, { $inc: { "counts.vehicles": 1 } });
  }

  if (participant.status === "JOINED") {
    await Trip.updateOne({ _id: trip._id }, { $inc: { "counts.participants": 1 } });
  }

  return { trip, participant, rejoined: false };
};

// ── Host continuity ──────────────────────────────────────────────
// A trip must never be orphaned. Promote the longest-tenured CO_HOST, else
// the longest-tenured member. If nobody is left, the trip ends.
exports.promoteNewHost = async (trip, excludeParticipantId) => {
  const candidates = await Participant.find({
    tripId: trip._id,
    status: "JOINED",
    _id: { $ne: excludeParticipantId },
  }).sort({ role: 1, joinedAt: 1 }); // CO_HOST sorts before MEMBER

  const successor =
    candidates.find((p) => p.role === "CO_HOST") || candidates[0] || null;

  if (!successor) {
    trip.status = "ENDED";
    trip.endedAt = new Date();
    await trip.save();
    return null;
  }

  successor.role = "HOST";
  await successor.save();
  await Trip.updateOne({ _id: trip._id }, { hostId: successor.userId });
  return successor;
};

exports.transferHost = async (trip, currentHost, targetParticipantId) => {
  const target = await Participant.findOne({
    _id: targetParticipantId,
    tripId: trip._id,
    status: "JOINED",
  });
  if (!target) throw new AppError("That person is not in this trip.", 404);
  if (target._id.equals(currentHost._id)) {
    throw new AppError("You are already the host.", 400);
  }

  // Demote first. If the second write fails the trip has zero hosts, which
  // promoteNewHost can repair; promoting first and failing would leave TWO,
  // and nothing detects that.
  currentHost.role = "CO_HOST";
  await currentHost.save();

  target.role = "HOST";
  await target.save();
  await Trip.updateOne({ _id: trip._id }, { hostId: target.userId });

  return target;
};

// ── Leave ────────────────────────────────────────────────────────
exports.leaveTrip = async (trip, participant) => {
  participant.status = "LEFT";
  participant.leftAt = new Date();
  participant.sharingState = "OFFLINE";
  await participant.save();

  await Trip.updateOne({ _id: trip._id }, { $inc: { "counts.participants": -1 } });

  // If the driver leaves, the vehicle has nobody broadcasting. Hand it to a
  // remaining passenger rather than letting the dot silently freeze.
  if (participant.vehicleId) {
    const vehicle = await Vehicle.findById(participant.vehicleId);
    if (vehicle?.trackerParticipantId?.equals(participant._id)) {
      const replacement = await Participant.findOne({
        tripId: trip._id,
        vehicleId: vehicle._id,
        status: "JOINED",
        _id: { $ne: participant._id },
      }).sort({ joinedAt: 1 });

      if (replacement) {
        vehicle.trackerParticipantId = replacement._id;
        await vehicle.save();
        replacement.isDriver = true;
        await replacement.save();
      } else {
        vehicle.connectionState = "ENDED";
        await vehicle.save();
      }
    }
  }

  let newHost = null;
  if (participant.role === "HOST") {
    newHost = await exports.promoteNewHost(trip, participant._id);
  }
  return { newHost };
};

// ── Invite rotation ──────────────────────────────────────────────
// Kills every previously shared link at once — the fix for "the link got
// forwarded to the wrong group chat".
exports.rotateJoinToken = async (trip) => {
  const rawToken = generateJoinToken();
  trip.joinTokenHash = hashJoinToken(rawToken);
  trip.joinTokenExpiresAt = new Date(
    Date.now() + config.trip.joinTokenExpiresInHours * 3600 * 1000
  );
  await trip.save();
  return { rawToken, joinLink: buildJoinLink(rawToken) };
};

exports.buildJoinLink = buildJoinLink;
exports.VEHICLE_PALETTE = VEHICLE_PALETTE;
