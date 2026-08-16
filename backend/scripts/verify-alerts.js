// The alert engine, driven deterministically: positions are pushed over
// sockets, then a single sweeper tick is invoked directly rather than
// waiting on wall-clock time.
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
  const Alert = require("../models/alertModel");
  const Marker = require("../models/markerModel");
  const alertService = require("../services/alert.service");
  const redis = require("../services/redis.service");
  redis.connect();
  await new Promise((r) => setTimeout(r, 600));

  section("0. Setup — two cars on a known route");
  const rohit = await device("AlRohit");
  const priya = await device("AlPriya");

  const created = await api("POST", "/trips", {
    token: rohit.token,
    body: { name: "__al Pune → Lonavala", deviceId: rohit.deviceId, vehicle: { label: "Thar" } },
  });
  const tripId = created.body.data.trip._id;
  const code = created.body.data.joinCode;
  await api("POST", "/trips/join", { token: priya.token, body: { code, deviceId: priya.deviceId, vehicle: { label: "Swift" } } });
  await api("PATCH", `/trips/${tripId}/status`, { token: rohit.token, body: { status: "ACTIVE" } });

  // A straight 20 km west→east line, so expected distances are checkable.
  await Trip.updateOne({ _id: tripId }, {
    "routeCache.coordinates": [[73.70, 18.52], [73.90, 18.52]],
    "routeCache.distanceM": 21000,
  });

  const vehicles = await Vehicle.find({ tripId });
  const thar = vehicles.find((v) => v.label === "Thar");
  const swift = vehicles.find((v) => v.label === "Swift");
  ok("trip active with a 20 km route");

  const watcher = await connect(rohit.token, tripId);
  await once(watcher, "trip:snapshot");
  const sPriya = await connect(priya.token, tripId);
  await once(sPriya, "trip:snapshot");

  const push = async (socket, lat, lng, extra = {}) =>
    socket.emitWithAck("position:update", { lat, lng, speedKmh: 60, heading: 90, ...extra });

  const sweep = async () => {
    const trip = await Trip.findById(tripId);
    return alertService.evaluateTrip(trip);
  };
  const liveAlerts = (type) =>
    Alert.find({ tripId, type, state: { $in: ["OPEN", "ACKNOWLEDGED"] } });

  section("1. No alerts when the convoy is together");
  await push(watcher, 18.52, 73.80);
  await push(sPriya, 18.52, 73.799);
  await sweep();
  const quiet = await Alert.find({ tripId, state: "OPEN" });
  if (quiet.length === 0) ok("a tight convoy raises nothing"); else bad(`${quiet.length} spurious alerts: ${quiet.map(a=>a.type)}`);

  section("2. GAP — adaptive threshold from convoy speed");
  const tripNow = await Trip.findById(tripId);
  const snapForCalc = [{ position: { speedKmh: 60 } }, { position: { speedKmh: 60 } }];
  const th = alertService.gapThresholdMeters(tripNow, snapForCalc);
  // 60 km/h for 4 minutes = 4 km
  if (th.basis === "adaptive" && Math.abs(th.meters - 4000) < 200) ok(`at 60 km/h the 4-min threshold becomes ${(th.meters/1000).toFixed(1)} km`);
  else bad(`threshold ${JSON.stringify(th)}`);

  const thSlow = alertService.gapThresholdMeters(tripNow, [{ position: { speedKmh: 15 } }]);
  if (thSlow.meters < th.meters) ok(`in traffic (15 km/h) it tightens to ${(thSlow.meters/1000).toFixed(1)} km — one fixed number could not do both`);
  else bad("threshold did not adapt downward");

  section("3. GAP raises, then clears with hysteresis");
  // Rohit pushes ahead ~8 km along the route; Priya stays put.
  await push(watcher, 18.52, 73.876);
  await push(sPriya, 18.52, 73.80);
  const r1 = await sweep();
  const gaps = await liveAlerts("GAP");
  if (gaps.length === 1) ok("gap alert raised for the trailing car"); else bad(`${gaps.length} gap alerts`);
  if (String(gaps[0]?.vehicleId) === String(swift._id)) ok("raised against the car that fell behind, not the leader");
  if (gaps[0]?.payload?.method === "route") ok("measured ALONG the route, not straight-line");
  if (gaps[0]?.payload?.minutesBehind) ok(`message says "${gaps[0].message}"`);

  // Re-sweeping the same condition must not create a second alert.
  await sweep();
  await sweep();
  const stillOne = await liveAlerts("GAP");
  if (stillOne.length === 1) ok("three sweeps, still ONE alert — de-duplicated by the partial unique index");
  else bad(`${stillOne.length} duplicate alerts`);
  if (stillOne[0].updateCount >= 2) ok(`re-observed ${stillOne[0].updateCount}x without spamming the convoy`);

  // Just inside the threshold — must NOT clear yet (this is the flapping guard).
  await push(sPriya, 18.52, 73.8405); // ~3.8 km behind, threshold 4 km
  await sweep();
  const stillOpen = await liveAlerts("GAP");
  if (stillOpen.length === 1) ok("closing to just under the threshold does NOT clear it — no flapping");
  else bad("alert cleared too eagerly; it will flap");

  // Well inside (60% of threshold) — now it clears.
  await push(sPriya, 18.52, 73.8735);
  await sweep();
  const closed = await liveAlerts("GAP");
  if (closed.length === 0) ok("clears only once genuinely back with the group");
  else bad("gap never cleared");
  const resolvedGap = await Alert.findOne({ tripId, type: "GAP", state: "RESOLVED" });
  if (resolvedGap?.resolvedReason === "gap-closed") ok('resolved with reason "gap-closed"');

  section("4. OFF_ROUTE");
  await push(sPriya, 18.60, 73.8735); // ~9 km north of the line
  await sweep();
  const off = await liveAlerts("OFF_ROUTE");
  if (off.length === 1) ok(`off-route raised: "${off[0].message}"`); else bad(`${off.length} off-route alerts`);
  await push(sPriya, 18.5215, 73.8735);
  await sweep();
  if ((await liveAlerts("OFF_ROUTE")).length === 0) ok("clears on returning to the route"); else bad("off-route never cleared");

  section("5. STALLED — and why marking a stop silences it");
  const stallLat = 18.52, stallLng = 73.8735;
  await push(watcher, stallLat, 73.876, { speedKmh: 0 });
  await push(sPriya, stallLat, stallLng, { speedKmh: 0 });
  // Both stopped = traffic, not a problem.
  await sweep();
  if ((await liveAlerts("STALLED")).length === 0) ok("whole convoy stopped = traffic jam, no alert (context suppression)");
  else bad("alerted on a traffic jam");

  // Now only Priya is stopped, and long enough to count.
  await push(watcher, 18.52, 73.878, { speedKmh: 70 });
  await Trip.updateOne({ _id: tripId }, { "settings.stalledAfterMin": 1 });
  // Stopped 4 minutes ago but still reporting — exactly the case fix-age
  // would get wrong. The fix is fresh; the vehicle has not moved.
  const stalePos = {
    lat: stallLat, lng: stallLng, speedKmh: 0,
    stoppedSince: new Date(Date.now() - 4 * 60 * 1000).toISOString(),
    at: new Date().toISOString(),
    vehicleId: String(swift._id),
  };
  await redis.setVehiclePosition(tripId, swift._id, stalePos);
  await sweep();
  const stalled = await liveAlerts("STALLED");
  if (stalled.length === 1) ok(`stalled raised: "${stalled[0].message}"`); else bad(`${stalled.length} stalled alerts`);
  if (stalled[0]?.payload?.stoppedForSec > 200) ok("duration is real stationary time, not last-fix age");

  // Marking a reason must clear it — this is the link to the marker system.
  const trip2 = await Trip.findById(tripId);
  const priyaP = await Participant.findOne({ tripId, vehicleId: swift._id });
  await Marker.create({
    ...Marker.fromTripMarker(trip2, "fuel", {}),
    kind: "STATUS", vehicleId: swift._id, createdBy: priyaP._id,
    location: { type: "Point", coordinates: [stallLng, stallLat] },
  });
  await redis.setVehiclePosition(tripId, swift._id, stalePos);
  await sweep();
  const afterMark = await liveAlerts("STALLED");
  if (afterMark.length === 0) ok("marking a fuel stop clears STALLED — the alert means 'nobody said why'");
  else bad("stalled persisted after a reason was marked");
  const resolvedStall = await Alert.findOne({ tripId, type: "STALLED", state: "RESOLVED" });
  if (resolvedStall?.resolvedReason === "stop-marked") ok('resolved with reason "stop-marked"');

  section("6. SIGNAL_LOST — the one only a timer can find");
  await Marker.updateMany({ tripId, vehicleId: swift._id }, { state: "CLEARED" });
  const oldPos = { lat: stallLat, lng: stallLng, speedKmh: 0, at: new Date(Date.now() - 10 * 60 * 1000).toISOString(), vehicleId: String(swift._id) };
  await redis.setVehiclePosition(tripId, swift._id, oldPos);
  await sweep();
  const lost = await liveAlerts("SIGNAL_LOST");
  if (lost.length === 1) ok(`signal lost raised: "${lost[0].message}"`); else bad(`${lost.length} signal alerts`);
  if (lost[0]?.location?.coordinates) ok("carries the LAST KNOWN position so people can go and look");

  await push(sPriya, stallLat, stallLng, { speedKmh: 40 });
  await sweep();
  if ((await liveAlerts("SIGNAL_LOST")).length === 0) ok("clears the moment they report again");

  section("7. LOW_BATTERY");
  // Two tiers: a heads-up at the threshold, an urgent one when it's nearly gone.
  await push(sPriya, 18.5205, 73.874, { batteryPct: 15 });
  await sweep();
  const bat = await liveAlerts("LOW_BATTERY");
  if (bat.length === 1 && bat[0].severity === "INFO") ok("15% is a quiet heads-up (INFO)");
  else bad(`expected 1 INFO alert, got ${bat.length} (${bat[0]?.severity})`);

  await push(sPriya, 18.5206, 73.8741, { batteryPct: 8 });
  await sweep();
  const bat2 = await liveAlerts("LOW_BATTERY");
  if (bat2.length === 1 && bat2[0].severity === "WARN") ok("8% escalates the SAME alert to WARN, not a second one");
  else bad(`expected 1 WARN alert, got ${bat2.length} (${bat2[0]?.severity})`);
  await push(sPriya, 18.5205, 73.8745, { batteryPct: 45 });
  await sweep();
  if ((await liveAlerts("LOW_BATTERY")).length === 0) ok("clears once charging");

  section("8. SOS — immediate, and never auto-resolving");
  const sosEvt = once(watcher, "alert:sos");
  const sos = await api("POST", `/trips/${tripId}/sos`, {
    token: priya.token, body: { lat: 18.5205, lng: 73.8745, note: "tyre blown, need help" },
  });
  if (sos.status === 201) ok("SOS accepted immediately — no waiting for a sweep"); else bad(`sos failed: ${JSON.stringify(sos.body)}`);
  if (sos.body.data.alert.severity === "CRITICAL") ok("raised as CRITICAL");
  const sosBroadcast = await sosEvt;
  if (sosBroadcast?.alert) ok("broadcast to the convoy over the socket");
  if (sos.body.data.shareUrl) ok("a share link is minted automatically — the person in trouble shouldn't have to find it");

  // The convoy moving on must NOT clear an emergency.
  await push(sPriya, 18.5205, 73.8748, { speedKmh: 80, batteryPct: 90 });
  await push(watcher, 18.5205, 73.8749, { speedKmh: 80 });
  await sweep();
  const sosStill = await liveAlerts("SOS");
  if (sosStill.length === 1) ok("driving on does NOT auto-resolve an SOS");
  else bad("SOS was auto-resolved — unacceptable");

  section("9. Acknowledging vs resolving");
  const sosId = sosStill[0]._id;
  await api("POST", `/trips/${tripId}/alerts/${sosId}/ack`, { token: rohit.token });
  const acked = await Alert.findById(sosId);
  if (acked.state === "ACKNOWLEDGED" && acked.acknowledgedBy.length === 1) ok("acknowledging says 'seen', not 'over' — condition stays live");
  else bad(`state ${acked.state}`);

  const stranger = await device("AlStranger");
  await api("POST", "/trips/join", { token: stranger.token, body: { code } });
  const strangerResolve = await api("POST", `/trips/${tripId}/alerts/${sosId}/resolve`, { token: stranger.token });
  if (strangerResolve.status === 403) ok("a bystander cannot silence someone else's emergency");
  else bad(`bystander got ${strangerResolve.status}`);

  const properResolve = await api("POST", `/trips/${tripId}/alerts/${sosId}/resolve`, {
    token: priya.token, body: { reason: "sorted, spare fitted" },
  });
  if (properResolve.status === 200) ok("the person who raised it can clear it");

  section("10. Public live-share link");
  const shareUrl = sos.body.data.shareUrl;
  const shareToken = shareUrl.split("/").pop();
  const pub = await fetch(`${BASE}/live/${shareToken}`);
  const pubBody = await pub.json();
  if (pub.status === 200) ok("family can open the link with no account"); else bad(`public view ${pub.status}`);
  if (pubBody.data.vehicles?.length === 2) ok("shows where the cars are");
  if (pubBody.data.vehicles[0].label && !pubBody.data.participants) ok("shows vehicles, but no roster or personal data");
  const badToken = await fetch(`${BASE}/live/deadbeef`);
  if (badToken.status === 404) ok("a wrong token gets nothing");

  section("11. Ending the trip closes everything");
  await push(sPriya, 18.60, 73.8748, { batteryPct: 8 }); // off-route + low battery
  await sweep();
  const beforeEnd = await Alert.countDocuments({ tripId, state: { $in: ["OPEN", "ACKNOWLEDGED"] } });
  if (beforeEnd > 0) ok(`${beforeEnd} alert(s) open before the trip ends`);

  await api("PATCH", `/trips/${tripId}/status`, { token: rohit.token, body: { status: "ENDED" } });
  const afterEnd = await Alert.countDocuments({ tripId, state: { $in: ["OPEN", "ACKNOWLEDGED"] } });
  if (afterEnd === 0) ok("every alert force-resolved on trip end — no permanent false signals");
  else bad(`${afterEnd} alerts still open after the trip ended`);

  const pubAfter = await fetch(`${BASE}/live/${shareToken}`);
  if (pubAfter.status === 410) ok("the share link dies with the trip — location cannot outlive it");
  else bad(`share link still served ${pubAfter.status} after trip end`);

  const endedSweep = await sweep();
  if (endedSweep.raised.length === 0) ok("the sweeper ignores non-active trips");

  // Cleanup
  await Promise.all([
    Trip.deleteMany({ name: /^__al/ }),
    Vehicle.deleteMany({ tripId }),
    Participant.deleteMany({ tripId }),
    Alert.deleteMany({ tripId }),
    Marker.deleteMany({ tripId }),
  ]);
  await redis.clearTrip(tripId);
  await redis.disconnect();
  await mongoose.disconnect();
  [watcher, sPriya].forEach((s) => s.close());

  console.log(`\n${fail === 0 ? "All" : pass} checks passed${fail ? `, ${fail} FAILED` : ""}.\n`);
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error("ERROR", e); process.exit(1); });
