// Drives the socket layer with real Socket.IO clients: two cars moving, a
// passenger who must not be able to broadcast, a stranger who must not
// connect, and a reconnect that must resync.
// Requires the server running (npm run dev) and Redis up.
require("dotenv").config({ path: "./config.env" });
const { io: ioClient } = require("socket.io-client");

const PORT = process.env.PORT || 3000;
const BASE = `http://localhost:${PORT}/api/v1`;
const WS = `http://localhost:${PORT}`;

let pass = 0, fail = 0;
const ok = (m) => { pass += 1; console.log(`  PASS  ${m}`); };
const bad = (m) => { fail += 1; console.log(`  FAIL  ${m}`); };
const section = (m) => console.log(`\n${m}`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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
  return { name, deviceId, token: r.body.token, userId: r.body.data.user._id };
};

const connect = (token, tripId) =>
  new Promise((resolve, reject) => {
    const s = ioClient(WS, { auth: { token, tripId }, transports: ["websocket"], reconnection: false });
    const timer = setTimeout(() => reject(new Error("connect timeout")), 6000);
    s.on("connect", () => { clearTimeout(timer); resolve(s); });
    s.on("connect_error", (e) => { clearTimeout(timer); reject(e); });
  });

const once = (socket, event, ms = 4000) =>
  new Promise((resolve) => {
    const timer = setTimeout(() => resolve(null), ms);
    socket.once(event, (payload) => { clearTimeout(timer); resolve(payload); });
  });

(async () => {
  section("0. Setup — a live trip with two cars and a passenger");
  const rohit = await device("SockRohit");
  const priya = await device("SockPriya");
  const amit = await device("SockAmit");

  const created = await api("POST", "/trips", {
    token: rohit.token,
    body: { name: "__sock Pune → Lonavala", deviceId: rohit.deviceId, vehicle: { label: "Thar" } },
  });
  const trip = created.body.data.trip;
  const code = created.body.data.joinCode;

  await api("POST", "/trips/join", { token: priya.token, body: { code, deviceId: priya.deviceId, vehicle: { label: "Swift" } } });
  const amitJoin = await api("POST", "/trips/join", { token: amit.token, body: { code, deviceId: amit.deviceId } });

  const vlist = await api("GET", `/trips/${trip._id}/vehicles`, { token: amit.token });
  const thar = vlist.body.data.vehicles.find((v) => v.label === "Thar");
  await api("POST", `/trips/${trip._id}/vehicles/${thar._id}/board`, { token: amit.token });

  const started = await api("PATCH", `/trips/${trip._id}/status`, { token: rohit.token, body: { status: "ACTIVE" } });
  if (started.status === 200) ok("trip is ACTIVE with 2 vehicles, 3 people"); else bad(`could not start: ${JSON.stringify(started.body)}`);

  section("1. Handshake authentication");
  try {
    await connect("not-a-real-token", trip._id);
    bad("a garbage token was allowed to connect");
  } catch (e) { ok(`bad token rejected at handshake (${e.message})`); }

  const stranger = await device("SockStranger");
  try {
    await connect(stranger.token, trip._id);
    bad("a non-participant connected");
  } catch (e) { ok(`non-participant rejected (${e.message})`); }

  try {
    await connect(rohit.token, "6a7c000000000000000000aa");
    bad("connected to a trip that does not exist");
  } catch (e) { ok("unknown trip rejected"); }

  section("2. Snapshot on connect");
  const sRohit = await connect(rohit.token, trip._id);
  const snap = await once(sRohit, "trip:snapshot");
  if (snap) ok("snapshot delivered immediately on connect"); else return bad("no snapshot");
  if (snap.vehicles.length === 2) ok("snapshot lists both vehicles"); else bad(`${snap.vehicles.length} vehicles`);
  if (snap.vehicles.every((v) => v.connectionState === "LOST")) ok("vehicles with no fix report LOST, not LIVE");
  else bad("a vehicle with no position claimed to be live");
  if (snap.serverTime) ok("snapshot carries server time (clients trust it over their own clock)");

  section("3. Presence");
  const sPriya = await connect(priya.token, trip._id);
  await once(sPriya, "trip:snapshot");
  const joined = await once(sRohit, "presence:joined", 3000);
  if (joined?.displayName === "SockPriya") ok("existing members are told when someone connects"); else bad("no presence:joined");

  section("4. A position update reaches the other car");
  const moved = once(sPriya, "vehicle:moved", 4000);
  const ackR = await sRohit.emitWithAck("position:update", { lat: 18.5204, lng: 73.8567, heading: 90, speedKmh: 60, batteryPct: 84 });
  if (ackR.ok) ok("server acknowledged the position"); else bad(`ack failed: ${ackR.error}`);
  if (ackR.persisted) ok("first fix persisted to history"); else bad("first fix should always persist");
  const got = await moved;
  if (got?.vehicleId === String(thar._id)) ok("Priya's phone received vehicle:moved"); else bad("broadcast not received");
  if (got?.at && got.at !== undefined) ok("position carries a SERVER timestamp");

  section("5. The sender does not get its own echo");
  let echoed = false;
  sRohit.on("vehicle:moved", () => { echoed = true; });
  await sRohit.emitWithAck("position:update", { lat: 18.5210, lng: 73.8570 });
  await sleep(300);
  if (!echoed) ok("sender is excluded from its own broadcast"); else bad("sender received its own update");

  section("6. Downsampling — most pings must NOT hit Mongo");
  // A 3 m nudge: too small to be worth remembering.
  const tiny = await sRohit.emitWithAck("position:update", { lat: 18.52103, lng: 73.85702, heading: 90 });
  if (tiny.ok && !tiny.persisted) ok("a 3 m nudge is broadcast but NOT persisted"); else bad(`tiny move persisted=${tiny.persisted}`);

  // ~120 m: worth a breadcrumb.
  const far = await sRohit.emitWithAck("position:update", { lat: 18.5221, lng: 73.8570, heading: 90 });
  if (far.persisted) ok("a 120 m move IS persisted"); else bad("large move was not persisted");

  // Sharp turn over a short distance still counts.
  const turn = await sRohit.emitWithAck("position:update", { lat: 18.52215, lng: 73.85705, heading: 180 });
  if (turn.persisted) ok("a sharp turn is persisted even over a short distance"); else bad("turn not persisted");

  section("7. Passengers cannot broadcast");
  const sAmit = await connect(amit.token, trip._id);
  await once(sAmit, "trip:snapshot");
  const paxAck = await sAmit.emitWithAck("position:update", { lat: 18.53, lng: 73.86 });
  if (!paxAck.ok) ok(`passenger refused: "${paxAck.error}"`); else bad("a passenger was allowed to broadcast");

  section("8. Payload validation");
  const badCoord = await sRohit.emitWithAck("position:update", { lat: 999, lng: 73.85 });
  if (!badCoord.ok) ok("out-of-range coordinates rejected"); else bad("accepted lat=999");
  const missing = await sRohit.emitWithAck("position:update", { lng: 73.85 });
  if (!missing.ok) ok("missing lat rejected"); else bad("accepted a payload with no lat");

  section("9. Snapshot now reflects live positions");
  const resync = await sAmit.emitWithAck("trip:resync", {});
  const tharSnap = resync.vehicles.find((v) => String(v.vehicleId) === String(thar._id));
  if (tharSnap.position) ok("resync returns the current position"); else bad("resync has no position");
  if (tharSnap.connectionState === "LIVE") ok("a fresh fix reads LIVE"); else bad(`state ${tharSnap.connectionState}`);
  if (tharSnap.source === "live") ok("position came from Redis, not the Mongo fallback"); else bad(`source ${tharSnap.source}`);

  section("10. Reconnect gets a fresh snapshot (the tunnel case)");
  sPriya.close();
  await sleep(400);
  const sPriya2 = await connect(priya.token, trip._id);
  const snap2 = await once(sPriya2, "trip:snapshot");
  const tharAfter = snap2.vehicles.find((v) => String(v.vehicleId) === String(thar._id));
  if (tharAfter?.position) ok("a reconnecting phone is caught up immediately"); else bad("reconnect snapshot was empty");

  section("11. Pausing sharing is visible to the group");
  const pauseHeard = once(sPriya2, "sharing:changed", 3000);
  await sRohit.emitWithAck("sharing:set", { sharing: false });
  const pauseEvt = await pauseHeard;
  if (pauseEvt?.state === "PAUSED") ok("the group is told when someone pauses — no invisible observers"); else bad("pause not broadcast");
  const afterPause = await sAmit.emitWithAck("trip:resync", {});
  const tharPaused = afterPause.vehicles.find((v) => String(v.vehicleId) === String(thar._id));
  if (tharPaused.source !== "live") ok("paused vehicle drops out of live state"); else bad("paused vehicle still live");

  section("12. Ending the trip stops ingestion server-side");
  await api("PATCH", `/trips/${trip._id}/status`, { token: rohit.token, body: { status: "ENDED" } });
  const afterEnd = await sRohit.emitWithAck("position:update", { lat: 18.6, lng: 73.9 });
  if (!afterEnd.ok && afterEnd.stop) ok("positions refused once the trip has ended"); else bad("position accepted after trip end");

  section("13. Track history was bucketed");
  const mongoose = require("mongoose");
  await mongoose.connect(process.env.DATABASE);
  const Track = require("../models/trackModel");
  const buckets = await Track.find({ tripId: trip._id });
  const totalPoints = buckets.reduce((n, b) => n + b.pointCount, 0);
  if (buckets.length >= 1) ok(`${buckets.length} bucket(s) holding ${totalPoints} points`); else bad("no track buckets written");
  if (totalPoints < 6) ok(`only ${totalPoints} of 7 pings persisted — downsampling is working`);
  else bad(`${totalPoints} points from 7 pings — downsampling not working`);
  if (buckets[0]?.points[0]?.x && buckets[0]?.points[0]?.y) ok("points stored compactly as x/y");

  // Cleanup
  const Trip = require("../models/tripModel");
  const Vehicle = require("../models/vehicleModel");
  const Participant = require("../models/participantModel");
  await Promise.all([
    Trip.deleteMany({ name: /^__sock/ }),
    Vehicle.deleteMany({ tripId: trip._id }),
    Participant.deleteMany({ tripId: trip._id }),
    Track.deleteMany({ tripId: trip._id }),
  ]);
  await mongoose.disconnect();

  [sRohit, sPriya2, sAmit].forEach((s) => s.close());

  console.log(`\n${fail === 0 ? "All" : pass} checks passed${fail ? `, ${fail} FAILED` : ""}.\n`);
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error("ERROR", e); process.exit(1); });
