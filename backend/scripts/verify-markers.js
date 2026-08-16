// Markers and waypoints over HTTP, with a live socket listening to confirm
// every durable write is broadcast to the convoy.
require("dotenv").config({ path: "./config.env" });
const { io: ioClient } = require("socket.io-client");

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
  return { name, deviceId, token: r.body.token };
};

const connect = (token, tripId) =>
  new Promise((resolve, reject) => {
    const s = ioClient(WS, { auth: { token, tripId }, transports: ["websocket"], reconnection: false });
    const t = setTimeout(() => reject(new Error("connect timeout")), 6000);
    s.on("connect", () => { clearTimeout(t); resolve(s); });
    s.on("connect_error", (e) => { clearTimeout(t); reject(e); });
  });

const once = (socket, event, ms = 3500) =>
  new Promise((resolve) => {
    const t = setTimeout(() => resolve(null), ms);
    socket.once(event, (p) => { clearTimeout(t); resolve(p); });
  });

(async () => {
  section("0. Setup — active trip, two cars, one passenger");
  const rohit = await device("MkRohit");
  const priya = await device("MkPriya");
  const amit = await device("MkAmit");

  const created = await api("POST", "/trips", {
    token: rohit.token,
    body: { name: "__mk Pune → Mahabaleshwar", deviceId: rohit.deviceId, vehicle: { label: "Thar" } },
  });
  const trip = created.body.data.trip;
  const code = created.body.data.joinCode;

  await api("POST", "/trips/join", { token: priya.token, body: { code, deviceId: priya.deviceId, vehicle: { label: "Swift" } } });
  await api("POST", "/trips/join", { token: amit.token, body: { code, deviceId: amit.deviceId } });

  const vl = await api("GET", `/trips/${trip._id}/vehicles`, { token: amit.token });
  const thar = vl.body.data.vehicles.find((v) => v.label === "Thar");
  const swift = vl.body.data.vehicles.find((v) => v.label === "Swift");
  await api("POST", `/trips/${trip._id}/vehicles/${thar._id}/board`, { token: amit.token });
  await api("PATCH", `/trips/${trip._id}/status`, { token: rohit.token, body: { status: "ACTIVE", force: true } });
  ok("trip active");

  // Priya watches the room for broadcasts.
  const watcher = await connect(priya.token, trip._id);
  await once(watcher, "trip:snapshot");
  ok("observer socket connected");

  section("1. The built-in catalogue");
  const cat = await api("GET", "/markers/catalogue", { token: rohit.token });
  if (cat.body.data.markers.length >= 15) ok(`catalogue served (${cat.body.data.markers.length} markers)`); else bad("catalogue too small");
  const bd = cat.body.data.markers.find((m) => m.key === "breakdown");
  if (bd.severity === "CRITICAL" && bd.defaultWaitingForGroup) ok("breakdown carries CRITICAL + wait-for-group");

  section("2. Dropping a status marker");
  const evt = once(watcher, "marker:created");
  const fuel = await api("POST", `/trips/${trip._id}/markers`, {
    token: rohit.token, body: { markerKey: "fuel", kind: "STATUS", lat: 18.52, lng: 73.85 },
  });
  if (fuel.status === 201) ok("fuel stop created"); else return bad(`create failed: ${JSON.stringify(fuel.body)}`);
  const gotEvt = await evt;
  if (gotEvt?.marker?.markerKey === "fuel") ok("broadcast to the convoy over the socket"); else bad("no marker:created broadcast");
  if (gotEvt?.severity === "INFO") ok("severity travels with the event");

  const vAfter = await api("GET", `/trips/${trip._id}/vehicles`, { token: rohit.token });
  const tharNow = vAfter.body.data.vehicles.find((v) => v.label === "Thar");
  if (tharNow.currentStatus?.markerKey === "fuel") ok("vehicle.currentStatus denormalized for the roster"); else bad("currentStatus not set");

  section("3. Markers belong to the VEHICLE");
  // Amit is a passenger in the Thar. His "chai" replaces the car's status
  // rather than creating a second dot for the same stop.
  const chai = await api("POST", `/trips/${trip._id}/markers`, {
    token: amit.token, body: { markerKey: "chai", kind: "STATUS", lat: 18.52, lng: 73.85 },
  });
  if (chai.status === 201) ok("passenger can amend the car's stop reason"); else bad(`passenger blocked: ${JSON.stringify(chai.body)}`);
  const active = await api("GET", `/trips/${trip._id}/markers?kind=STATUS&state=ACTIVE`, { token: rohit.token });
  const tharActive = active.body.data.markers.filter((m) => String(m.vehicleId) === String(thar._id));
  if (tharActive.length === 1) ok("still exactly ONE active status for the car"); else bad(`${tharActive.length} active statuses`);
  if (tharActive[0].markerKey === "chai") ok("the newer reason replaced the old one");
  const cleared = await api("GET", `/trips/${trip._id}/markers?state=CLEARED`, { token: rohit.token });
  if (cleared.body.data.markers.some((m) => m.markerKey === "fuel" && m.durationS !== undefined)) ok("the replaced stop was cleared with a duration");

  section("4. Behaviour comes from the definition");
  const other = await api("POST", `/trips/${trip._id}/markers`, {
    token: priya.token, body: { markerKey: "other", kind: "STATUS", lat: 18.6, lng: 73.9 },
  });
  if (other.status === 400) ok('"Other" is refused without a note'); else bad("requiresNote not enforced");
  const otherOk = await api("POST", `/trips/${trip._id}/markers`, {
    token: priya.token, body: { markerKey: "other", kind: "STATUS", lat: 18.6, lng: 73.9, note: "goats on the road" },
  });
  if (otherOk.status === 201) ok("with a note it goes through");

  const bdWait = await api("POST", `/trips/${trip._id}/markers`, {
    token: priya.token, body: { markerKey: "breakdown", kind: "STATUS", lat: 18.61, lng: 73.91 },
  });
  if (bdWait.body.data.marker.waitingForGroup === true) ok("breakdown defaults to wait-for-group, from data not an if-statement");
  else bad("breakdown did not inherit wait-for-group");
  if (bdWait.body.data.marker.severity === "CRITICAL") ok("breakdown is CRITICAL");

  section("5. Markers not in the trip's set are refused");
  const nope = await api("POST", `/trips/${trip._id}/markers`, {
    token: rohit.token, body: { markerKey: "viewpoint", kind: "STATUS", lat: 18.5, lng: 73.8 },
  });
  if (nope.status === 400) ok("a marker outside the trip's set is refused"); else bad("accepted an uncurated marker");

  section("6. Custom markers with real behaviour");
  const setEvt = once(watcher, "markerset:changed");
  const custom = await api("POST", `/trips/${trip._id}/marker-set`, {
    token: amit.token,
    body: { key: "landslide", label: "Landslide", icon: "🪨", color: "#B91C1C", category: "TROUBLE", severity: "CRITICAL", defaultWaitingForGroup: true, requiresNote: true },
  });
  if (custom.status === 201) ok("any member can add a custom marker mid-trip"); else bad(`custom add failed: ${JSON.stringify(custom.body)}`);
  if (await setEvt) ok("marker set change broadcast to everyone");

  const useCustom = await api("POST", `/trips/${trip._id}/markers`, {
    token: rohit.token, body: { markerKey: "landslide", kind: "PLACE", lat: 18.55, lng: 73.87 },
  });
  if (useCustom.status === 400) ok("the custom marker inherits requiresNote too");
  const useCustom2 = await api("POST", `/trips/${trip._id}/markers`, {
    token: rohit.token, body: { markerKey: "landslide", kind: "PLACE", lat: 18.55, lng: 73.87, note: "road blocked, single lane" },
  });
  if (useCustom2.body.data.marker.severity === "CRITICAL") ok("custom marker behaves exactly like a built-in");

  section("7. Favourites are capped");
  for (const k of ["fuel", "toilet", "chai", "food", "breakdown"]) {
    await api("PATCH", `/trips/${trip._id}/marker-set/${k}`, { token: rohit.token, body: { isFavourite: true } });
  }
  const tripNow = await api("GET", `/trips/${trip._id}`, { token: rohit.token });
  const favs = tripNow.body.data.trip.markerSet.filter((m) => m.isFavourite);
  if (favs.length <= 4) ok(`favourites capped at 4 (got ${favs.length}) — if everything is a favourite, nothing is`);
  else bad(`${favs.length} favourites`);

  section("8. Deleting a definition does not break history");
  await api("DELETE", `/trips/${trip._id}/marker-set/landslide`, { token: rohit.token });
  const stillThere = await api("GET", `/trips/${trip._id}/markers?kind=PLACE`, { token: rohit.token });
  const ls = stillThere.body.data.markers.find((m) => m.markerKey === "landslide");
  if (ls?.label === "Landslide" && ls?.severity === "CRITICAL") ok("the dropped marker still renders — it holds its own copy");
  else bad("history broke when the definition was removed");

  section("9. Members can't be silenced by permissions they should have");
  const paxDel = await api("DELETE", `/trips/${trip._id}/marker-set/fuel`, { token: amit.token });
  if (paxDel.status === 403) ok("a member cannot remove markers from everyone's picker"); else bad("member removed a shared marker");

  section("10. Clearing a stop");
  const clearEvt = once(watcher, "marker:cleared");
  const toClear = tharActive[0];
  const cl = await api("POST", `/trips/${trip._id}/markers/${toClear._id}/clear`, { token: rohit.token });
  if (cl.status === 200 && cl.body.data.marker.state === "CLEARED") ok("stop cleared on resuming"); else bad("clear failed");
  if (await clearEvt) ok("clear broadcast to the convoy");
  const vClear = await api("GET", `/trips/${trip._id}/vehicles`, { token: rohit.token });
  const tharCleared = vClear.body.data.vehicles.find((v) => v.label === "Thar");
  if (!tharCleared.currentStatus?.markerKey) ok("vehicle.currentStatus cleared too"); else bad("stale currentStatus left behind");

  const delStatus = await api("DELETE", `/trips/${trip._id}/markers/${toClear._id}`, { token: rohit.token });
  if (delStatus.status === 400) ok("a stop cannot be deleted — it's trip history");

  section("11. Waypoints: propose, vote, accept");
  const wpEvt = once(watcher, "waypoint:created");
  const wp = await api("POST", `/trips/${trip._id}/waypoints`, {
    token: amit.token, body: { label: "Panshet viewpoint", lat: 18.38, lng: 73.6, type: "PHOTO" },
  });
  if (wp.body.data.waypoint.state === "PROPOSED") ok("a member's stop needs approval"); else bad(`state ${wp.body.data.waypoint.state}`);
  if (await wpEvt) ok("proposal broadcast");

  const hostWp = await api("POST", `/trips/${trip._id}/waypoints`, {
    token: rohit.token, body: { label: "Wai regroup", lat: 17.95, lng: 73.89, isRegroupPoint: true },
  });
  if (hostWp.body.data.waypoint.state === "ACCEPTED") ok("the host's own stop is accepted immediately");

  const wpId = wp.body.data.waypoint._id;
  await api("POST", `/trips/${trip._id}/waypoints/${wpId}/vote`, { token: rohit.token, body: { vote: "UP" } });
  await api("POST", `/trips/${trip._id}/waypoints/${wpId}/vote`, { token: priya.token, body: { vote: "UP" } });
  const changed = await api("POST", `/trips/${trip._id}/waypoints/${wpId}/vote`, { token: priya.token, body: { vote: "DOWN" } });
  if (changed.body.data.tally.up === 1 && changed.body.data.tally.down === 1) ok("changing your vote replaces it, never stacks");
  else bad(`tally ${JSON.stringify(changed.body.data.tally)}`);

  const memberAccept = await api("PATCH", `/trips/${trip._id}/waypoints/${wpId}`, { token: amit.token, body: { state: "ACCEPTED" } });
  if (memberAccept.status === 403) ok("a member cannot approve their own proposal"); else bad("member self-approved");
  const hostAccept = await api("PATCH", `/trips/${trip._id}/waypoints/${wpId}`, { token: rohit.token, body: { state: "ACCEPTED" } });
  if (hostAccept.body.data.waypoint.state === "ACCEPTED") ok("host accepted it");

  section("12. Regroup: satisfied only when every vehicle arrives");
  const regroupId = hostWp.body.data.waypoint._id;
  const a1 = await api("POST", `/trips/${trip._id}/waypoints/${regroupId}/arrive`, { token: rohit.token });
  if (!a1.body.data.everyoneHere) ok(`${a1.body.data.arrived}/${a1.body.data.total} arrived — not satisfied yet`);
  await api("POST", `/trips/${trip._id}/waypoints/${regroupId}/arrive`, { token: rohit.token });
  const dup = await api("POST", `/trips/${trip._id}/waypoints/${regroupId}/arrive`, { token: amit.token });
  if (dup.body.data.arrived === 1) ok("a passenger in an already-arrived car doesn't double-count");
  const a2 = await api("POST", `/trips/${trip._id}/waypoints/${regroupId}/arrive`, { token: priya.token });
  if (a2.body.data.everyoneHere) ok("satisfied once the last vehicle arrives"); else bad("regroup never satisfied");
  if (a2.body.data.waypoint.state === "REACHED") ok("regroup point auto-marked REACHED");

  section("13. Reordering");
  const list = await api("GET", `/trips/${trip._id}/waypoints`, { token: rohit.token });
  const ids = list.body.data.waypoints.map((w) => String(w._id)).reverse();
  const re = await api("PATCH", `/trips/${trip._id}/waypoints/reorder`, { token: rohit.token, body: { order: ids } });
  if (re.body.data.waypoints[0]._id === ids[0]) ok("bulk reorder applied in one request"); else bad("reorder failed");
  const foreign = await api("PATCH", `/trips/${trip._id}/waypoints/reorder`, {
    token: rohit.token, body: { order: ["6a7c000000000000000000aa"] },
  });
  if (foreign.status === 400) ok("reorder rejects ids from another trip");

  // Cleanup
  const mongoose = require("mongoose");
  await mongoose.connect(process.env.DATABASE);
  const Trip = require("../models/tripModel");
  const Vehicle = require("../models/vehicleModel");
  const Participant = require("../models/participantModel");
  const Marker = require("../models/markerModel");
  const Waypoint = require("../models/waypointModel");
  await Promise.all([
    Trip.deleteMany({ name: /^__mk/ }),
    Vehicle.deleteMany({ tripId: trip._id }),
    Participant.deleteMany({ tripId: trip._id }),
    Marker.deleteMany({ tripId: trip._id }),
    Waypoint.deleteMany({ tripId: trip._id }),
  ]);
  await mongoose.disconnect();
  watcher.close();

  console.log(`\n${fail === 0 ? "All" : pass} checks passed${fail ? `, ${fail} FAILED` : ""}.\n`);
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error("ERROR", e); process.exit(1); });
