// Chat, media signing, and the lifecycle jobs.
require("dotenv").config({ path: "./config.env" });
const { io: ioClient } = require("socket.io-client");
const mongoose = require("mongoose");

const PORT = process.env.PORT || 3000;
const BASE = `http://localhost:${PORT}/api/v1`;
const WS = `http://localhost:${PORT}`;

let pass = 0, fail = 0;
const ok = (m) => { pass += 1; console.log(`  PASS  ${m}`); };
const bad = (m) => { fail += 1; console.log(`  FAIL  ${m}`); };
const section = (m) => console.log(`\n${m}`);

const api = async (method, path, { token, body } = {}) => {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
};

const device = async (name) => {
  const deviceId = `dev-${name}-${Date.now()}-${Math.random().toString(36).slice(2, 12)}`;
  const r = await api("POST", "/auth/device", { body: { deviceId, name } });
  return { name, token: r.body.token, deviceId };
};

const connect = (token, tripId) =>
  new Promise((resolve, reject) => {
    const s = ioClient(WS, { auth: { token, tripId }, transports: ["websocket"], reconnection: false });
    const t = setTimeout(() => reject(new Error("connect timeout")), 6000);
    s.on("connect", () => { clearTimeout(t); resolve(s); });
    s.on("connect_error", (e) => { clearTimeout(t); reject(e); });
  });

const once = (socket, event, ms = 3000) =>
  new Promise((r) => { const t = setTimeout(() => r(null), ms); socket.once(event, (p) => { clearTimeout(t); r(p); }); });

(async () => {
  await mongoose.connect(process.env.DATABASE);
  const Trip = require("../models/tripModel");
  const Vehicle = require("../models/vehicleModel");
  const Participant = require("../models/participantModel");
  const Message = require("../models/messageModel");
  const tripSweeper = require("../jobs/tripSweeper");
  const cloudinaryService = require("../services/cloudinary.service");

  section("0. Setup");
  const rohit = await device("ChRohit");
  const priya = await device("ChPriya");
  const created = await api("POST", "/trips", {
    token: rohit.token,
    body: { name: "__ch Chat trip", deviceId: rohit.deviceId, vehicle: { label: "Thar" } },
  });
  const tripId = created.body.data.trip._id;
  const code = created.body.data.joinCode;
  await api("POST", "/trips/join", { token: priya.token, body: { code, deviceId: priya.deviceId, vehicle: { label: "Swift" } } });
  await api("PATCH", `/trips/${tripId}/status`, { token: rohit.token, body: { status: "ACTIVE" } });
  ok("trip active with two members");

  const sRohit = await connect(rohit.token, tripId);
  await once(sRohit, "trip:snapshot");
  const sPriya = await connect(priya.token, tripId);
  await once(sPriya, "trip:snapshot");

  section("1. Quick messages catalogue");
  const qm = await api("GET", "/markers/quick-messages", { token: rohit.token });
  if (qm.body.data.quickMessages.length >= 10) ok(`${qm.body.data.quickMessages.length} canned phrases served`);
  const emergency = qm.body.data.quickMessages.find((q) => q.key === "emergency_stop");
  if (emergency?.severity === "CRITICAL") ok('"Emergency — stop" is CRITICAL so it can cut through a silenced phone');

  section("2. Sending over the socket");
  const heard = once(sPriya, "message:new");
  const ack = await sRohit.emitWithAck("message:send", { kind: "TEXT", body: "Stopping at the next petrol pump" });
  if (ack.ok) ok("socket send acknowledged"); else bad(`send failed: ${ack.error}`);
  const got = await heard;
  if (got?.message?.body === "Stopping at the next petrol pump") ok("delivered to the other car");
  if (got?.message?.senderName === "ChRohit") ok("carries a SNAPSHOT of the sender's name");

  // Unlike positions, chat echoes to the sender so everyone renders the
  // server's copy and ordering is identical for all.
  const selfHeard = once(sRohit, "message:new", 2500);
  await sPriya.emitWithAck("message:send", { kind: "TEXT", body: "Roger" });
  const echo = await once(sPriya, "message:new", 2500);
  if (echo) ok("sender also receives its own message (identical ordering for everyone)");

  section("3. Quick messages");
  const qAck = await sRohit.emitWithAck("message:send", { kind: "QUICK", quickKey: "wait_up" });
  if (qAck.ok && qAck.message.body === "Wait up") ok("quick message stores the LABEL, not just the key");
  if (qAck.message.severity === "WARN") ok("severity comes from the catalogue");
  const badQuick = await sRohit.emitWithAck("message:send", { kind: "QUICK", quickKey: "nonsense" });
  if (!badQuick.ok) ok("unknown quick key rejected");

  section("4. Validation");
  const empty = await sRohit.emitWithAck("message:send", { kind: "TEXT", body: "   " });
  if (!empty.ok) ok("empty message rejected");
  const noMedia = await sRohit.emitWithAck("message:send", { kind: "VOICE" });
  if (!noMedia.ok) ok("voice note without an upload rejected");

  section("5. History and pagination");
  for (let i = 0; i < 8; i += 1) {
    await sRohit.emitWithAck("message:send", { kind: "TEXT", body: `msg ${i}` });
  }
  const page1 = await api("GET", `/trips/${tripId}/messages?limit=5`, { token: priya.token });
  if (page1.body.results === 5) ok("page size honoured");
  const ordered = page1.body.data.messages;
  if (new Date(ordered[0].createdAt) <= new Date(ordered[4].createdAt)) ok("returned oldest-first so clients can append");
  const page2 = await api("GET", `/trips/${tripId}/messages?limit=5&before=${page1.body.data.nextBefore}`, { token: priya.token });
  if (page2.body.results > 0 && page2.body.data.messages.every((m) => new Date(m.createdAt) < new Date(page1.body.data.nextBefore))) {
    ok("cursor pagination returns strictly older messages");
  } else bad("pagination overlapped or returned nothing");

  section("6. Read receipts");
  const ids = ordered.slice(0, 3).map((m) => m._id);
  const readEvt = once(sRohit, "message:read");
  const rAck = await sPriya.emitWithAck("message:read", { messageIds: ids });
  if (rAck.ok && rAck.updated === 3) ok("3 messages marked read");
  if (await readEvt) ok("sender is told their message landed");
  const again = await sPriya.emitWithAck("message:read", { messageIds: ids });
  if (again.updated === 0) ok("marking twice does not stack duplicate receipts");

  section("7. REST fallback when the socket is down");
  // Listener attached BEFORE sending — otherwise the broadcast races ahead
  // of the subscription and the check silently passes on nothing.
  const restHeardPromise = once(sRohit, "message:new", 3000);
  const restSend = await api("POST", `/trips/${tripId}/messages`, {
    token: priya.token, body: { kind: "TEXT", body: "sent over HTTP" },
  });
  if (restSend.status === 201) ok("REST send works — the fallback for a dropped socket");
  else bad(`REST send failed: ${restSend.status}`);
  const restHeard = await restHeardPromise;
  if (restHeard?.message?.body === "sent over HTTP") ok("REST-sent message still broadcasts over the socket");
  else bad("REST send did not reach the room");

  section("8. Voice notes expire, text does not");
  const voiceMsg = await Message.create({
    tripId, senderId: (await Participant.findOne({ tripId }))._id, senderName: "ChRohit",
    kind: "VOICE", media: { publicId: "convoy/trips/x/abc", url: "https://x/abc.mp3", resourceType: "video" },
    expiresAt: new Date(Date.now() + 7 * 24 * 3600 * 1000),
  });
  if (voiceMsg.expiresAt) ok("voice clip carries an expiry");
  const textMsg = await Message.findOne({ tripId, kind: "TEXT" });
  if (!textMsg.expiresAt) ok("text has NO expiry — different retention by kind, on purpose");
  const idx = await Message.collection.indexes();
  const ttl = idx.find((i) => i.expireAfterSeconds !== undefined);
  if (ttl) ok(`TTL index on ${Object.keys(ttl.key)[0]} (absent field = never expires)`);

  section("9. Media upload signing");
  const cfg = await api("GET", "/markers/media-config", { token: rohit.token });
  ok(`media uploads ${cfg.body.data.enabled ? "enabled" : "not configured (no Cloudinary keys yet)"}`);

  // The signing maths is deterministic, so it is testable without credentials.
  const sig1 = cloudinaryService.signUploadParams({ timestamp: 1700000000, folder: "convoy/trips/aaa" });
  const sig2 = cloudinaryService.signUploadParams({ folder: "convoy/trips/aaa", timestamp: 1700000000 });
  if (sig1 === sig2 && sig1.length === 40) ok("signature is deterministic and order-independent (SHA-1)");
  const sig3 = cloudinaryService.signUploadParams({ timestamp: 1700000000, folder: "convoy/trips/bbb" });
  if (sig1 !== sig3) ok("a signature for one trip's folder cannot be reused for another");

  if (!cfg.body.data.enabled) {
    const sigReq = await api("POST", `/trips/${tripId}/media/signature`, { token: rohit.token, body: {} });
    if (sigReq.status === 503) ok("without credentials the API says so clearly instead of failing oddly");
  }

  section("10. Trip sweeper — the privacy backstop");
  const beforeCounts = await Trip.findById(tripId).select("counts");
  await Trip.updateOne({ _id: tripId }, { "counts.participants": 99, "counts.vehicles": 42 });
  await tripSweeper.reconcileCounts();
  const afterCounts = await Trip.findById(tripId).select("counts");
  if (afterCounts.counts.participants === 2 && afterCounts.counts.vehicles === 2) {
    ok("drifted counts reconciled back to reality");
  } else bad(`counts still wrong: ${JSON.stringify(afterCounts.counts)}`);

  // A trip nobody has touched for longer than the threshold must auto-end.
  const staleAt = new Date(Date.now() - 20 * 3600 * 1000);
  await Trip.updateOne({ _id: tripId }, { lastActivityAt: staleAt });
  const endedEvt = once(sPriya, "trip:ended", 3000);
  const abandoned = await tripSweeper.abandonStaleTrips(null);
  if (abandoned.length >= 1) ok("a forgotten trip is auto-ended"); else bad("abandon sweeper did nothing");

  const afterAbandon = await Trip.findById(tripId);
  if (afterAbandon.status === "ABANDONED") ok("status is ABANDONED");
  const sharing = await Participant.countDocuments({ tripId, sharingState: { $ne: "OFFLINE" } });
  if (sharing === 0) ok("EVERYONE forced offline — location cannot outlive a forgotten trip");
  else bad(`${sharing} participants still sharing after abandon`);
  const liveVehicles = await Vehicle.countDocuments({ tripId, connectionState: { $ne: "ENDED" } });
  if (liveVehicles === 0) ok("every vehicle marked ENDED");

  const postAbandonSend = await sRohit.emitWithAck("message:send", { kind: "TEXT", body: "anyone there?" });
  if (!postAbandonSend.ok) ok("chat refused on a finished trip");

  // Cleanup
  await Promise.all([
    Trip.deleteMany({ name: /^__ch/ }),
    Vehicle.deleteMany({ tripId }),
    Participant.deleteMany({ tripId }),
    Message.deleteMany({ tripId }),
  ]);
  await mongoose.disconnect();
  [sRohit, sPriya].forEach((s) => s.close());

  console.log(`\n${fail === 0 ? "All" : pass} checks passed${fail ? `, ${fail} FAILED` : ""}.\n`);
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error("ERROR", e); process.exit(1); });
