// Verifies the Trip/Participant/Vehicle models: indexes build, the
// double-join guard actually fires, and status transitions are enforced.
require("dotenv").config({ path: "./config.env" });
const mongoose = require("mongoose");

const Trip = require("../models/tripModel");
const Vehicle = require("../models/vehicleModel");
const Participant = require("../models/participantModel");
const Marker = require("../models/markerModel");
const Waypoint = require("../models/waypointModel");
const User = require("../models/userModel");
const { generateJoinCode, generateJoinToken, hashJoinToken } = require("../utils/joinCode");
const { toPoint, toLatLng, distanceMeters } = require("../utils/geo");

const ok = (m) => console.log(`  PASS  ${m}`);
const bad = (m) => { console.log(`  FAIL  ${m}`); process.exitCode = 1; };

(async () => {
  await mongoose.connect(process.env.DATABASE);
  const db = mongoose.connection.db;

  // Clean slate for the test fixtures only.
  await Promise.all([
    Trip.deleteMany({ name: /^__test/ }),
    Vehicle.deleteMany({ label: /^__test/ }),
  ]);

  console.log("\n1. Index creation");
  await Promise.all([Trip.init(), Vehicle.init(), Participant.init()]);
  for (const [name, model] of [["trips", Trip], ["vehicles", Vehicle], ["participants", Participant]]) {
    const ix = await db.collection(name).indexes();
    console.log(`  ${name}: ${ix.map((i) => i.name).join(", ")}`);
  }

  console.log("\n2. Geo helpers (the lng/lat order trap)");
  const pune = toPoint(18.5204, 73.8567);           // lat, lng in
  if (pune.coordinates[0] === 73.8567) ok("toPoint stores [lng, lat]");
  else bad("toPoint coordinate order wrong");
  const back = toLatLng(pune);
  if (back.lat === 18.5204 && back.lng === 73.8567) ok("toLatLng round-trips");
  else bad("toLatLng round-trip broken");
  const mumbai = { lat: 19.076, lng: 72.8777 };
  const d = Math.round(distanceMeters(back, mumbai) / 1000);
  if (d > 110 && d < 130) ok(`Pune→Mumbai ${d} km (expected ~120)`);
  else bad(`Pune→Mumbai ${d} km — expected ~120`);

  console.log("\n3. Join code + token");
  const code = generateJoinCode();
  if (/^[ACDEFGHJKMNPQRSTUVWXYZ23456789]{6}$/.test(code)) ok(`code "${code}" has no ambiguous chars`);
  else bad(`code "${code}" malformed`);
  const raw = generateJoinToken();
  if (hashJoinToken(raw) !== raw && hashJoinToken(raw).length === 64) ok("join token hashes, raw never stored");
  else bad("join token hashing broken");

  console.log("\n4. Trip creation + lifecycle");
  const host = await User.findOne({ authProvider: "device" });
  const trip = await Trip.create({
    name: "__test Goa Ride",
    hostId: host._id,
    joinCode: code,
    joinTokenHash: hashJoinToken(raw),
    destination: toPoint(15.2993, 74.124),
    markerSet: [{ key: "fuel", label: "Fuel", icon: "⛽" }],
  });
  if (trip.status === "DRAFT") ok("new trip starts in DRAFT");
  else bad(`new trip status ${trip.status}`);
  if (trip.settings.crashDetectionEnabled === false) ok("crash detection defaults OFF");
  else bad("crash detection should default off");
  if (trip.settings.pingIntervalSec === 15) ok("ping interval default 15s");
  else bad("ping interval default wrong");

  if (trip.canTransitionTo("LOBBY") && !trip.canTransitionTo("ACTIVE")) ok("DRAFT→LOBBY allowed, DRAFT→ACTIVE blocked");
  else bad("transition guard wrong for DRAFT");
  trip.status = "ENDED";
  if (!trip.canTransitionTo("ACTIVE")) ok("ENDED is terminal");
  else bad("ENDED must be terminal");
  trip.status = "ACTIVE";
  if (trip.isLive && trip.isJoinable) ok("ACTIVE trip isLive + isJoinable");
  else bad("ACTIVE virtuals wrong");
  trip.settings.isLocked = true;
  if (!trip.isJoinable) ok("locked trip is not joinable");
  else bad("lock not respected");
  await trip.save();

  console.log("\n5. Vehicle staleness (the frozen-dot problem)");
  const v = await Vehicle.create({ tripId: trip._id, label: "__test Swift", color: "#f00" });
  if (v.computeConnectionState() === "LOST") ok("vehicle with no fix is LOST, not LIVE");
  else bad("vehicle with no fix must be LOST");
  v.lastKnown = { point: toPoint(18.52, 73.85), at: new Date() };
  if (v.computeConnectionState() === "LIVE") ok("fresh fix → LIVE");
  else bad("fresh fix should be LIVE");
  v.lastKnown.at = new Date(Date.now() - 60 * 1000);
  if (v.computeConnectionState() === "STALE") ok("60s old fix → STALE");
  else bad(`60s old fix → ${v.computeConnectionState()}`);
  v.lastKnown.at = new Date(Date.now() - 10 * 60 * 1000);
  if (v.computeConnectionState() === "LOST") ok("10min old fix → LOST");
  else bad(`10min old fix → ${v.computeConnectionState()}`);
  await v.save();

  console.log("\n6. Participant roles + double-join guard");
  const p = await Participant.create({
    tripId: trip._id, userId: host._id, vehicleId: v._id,
    role: "HOST", displayName: host.name, isDriver: true,
    sharingState: "SHARING", joinedVia: "CREATOR",
  });
  if (p.canManageTrip() && p.isOwner()) ok("HOST can manage + owns trip");
  else bad("HOST permissions wrong");
  if (p.canBroadcastLocation()) ok("driver + SHARING can broadcast");
  else bad("driver should be able to broadcast");
  p.sharingState = "PAUSED";
  if (!p.canBroadcastLocation()) ok("paused participant cannot broadcast");
  else bad("paused must not broadcast");

  const co = new Participant({ tripId: trip._id, userId: host._id, role: "CO_HOST", displayName: "X" });
  co.status = "JOINED";
  if (co.canManageTrip() && !co.isOwner()) ok("CO_HOST manages but cannot demote host");
  else bad("CO_HOST permissions wrong");

  // Role and convoy position are independent fields.
  p.convoyRole = "LEAD";
  if (p.role === "HOST" && p.convoyRole === "LEAD") ok("host can also be LEAD (separate fields)");
  else bad("role/convoyRole not independent");
  await p.save();

  try {
    await Participant.create({ tripId: trip._id, userId: host._id, displayName: "dupe" });
    bad("double-join was ALLOWED — unique index missing");
  } catch (e) {
    if (e.code === 11000) ok("double-join blocked by unique index (E11000)");
    else bad(`unexpected error: ${e.message}`);
  }

  console.log("\n7. Abandon sweeper query");
  await Trip.updateOne({ _id: trip._id }, { status: "ACTIVE", lastActivityAt: new Date(Date.now() - 24 * 3600 * 1000) });
  const stale = await Trip.findAbandonable();
  if (stale.some((t) => t._id.equals(trip._id))) ok(`sweeper found ${stale.length} abandonable trip(s)`);
  else bad("sweeper did not find the stale trip");

  const plan = await Trip.find({ status: { $in: ["LOBBY","ACTIVE","PAUSED"] }, lastActivityAt: { $lt: new Date() } }).explain("executionStats");
  const stage = JSON.stringify(plan.queryPlanner.winningPlan);
  if (stage.includes("IXSCAN")) ok("sweeper query uses an index (no COLLSCAN)");
  else bad(`sweeper query plan: ${stage}`);

  console.log("\n8. Marker catalogue — behaviour is data, not if-statements");
  const { defaultMarkerSet, findCatalogueMarker } = require("../config/markerCatalogue");
  const breakdown = findCatalogueMarker("breakdown");
  if (breakdown.severity === "CRITICAL" && breakdown.defaultWaitingForGroup) ok("breakdown is CRITICAL + waits for group");
  else bad("breakdown behaviour wrong");
  const toilet = findCatalogueMarker("toilet");
  if (toilet.severity === "INFO" && !toilet.defaultWaitingForGroup) ok("toilet is INFO + go ahead");
  else bad("toilet behaviour wrong");
  if (findCatalogueMarker("other").requiresNote) ok('"Other" requires a note');
  else bad('"Other" should require a note');
  if (defaultMarkerSet().length === 8) ok("new trips start with 8 curated markers");
  else bad(`default set has ${defaultMarkerSet().length}`);

  console.log("\n9. Trip markerSet rules");
  const t2 = await Trip.create({ name: "__test Marker Rules", hostId: host._id, joinCode: generateJoinCode() });
  if (t2.markerSet.length === 8) ok("markerSet auto-populated on create");
  else bad("markerSet not defaulted");
  const favCount = t2.markerSet.filter((m) => m.isFavourite).length;
  if (favCount <= 4) ok(`favourites capped at 4 (got ${favCount})`);
  else bad(`favourites not capped: ${favCount}`);

  // A custom marker with real behaviour, exactly like a built-in.
  t2.markerSet.push({
    key: "custom_tyre", label: "Tyre change", icon: "🛞", color: "#B91C1C",
    category: "TROUBLE", severity: "CRITICAL", defaultWaitingForGroup: true,
    isCustom: true, addedBy: host._id, order: 99,
  });
  await t2.save();
  ok("custom marker with CRITICAL behaviour accepted");

  t2.markerSet.push({ key: "fuel", label: "Dupe", order: 100 });
  try { await t2.save(); bad("duplicate marker key was allowed"); }
  catch (e) { ok("duplicate marker key rejected"); }
  t2.markerSet.pop();

  console.log("\n10. Markers belong to VEHICLES, not people");
  await Marker.init();
  const t2veh = await Vehicle.create({ tripId: t2._id, label: "__test Thar" });
  const driver = await Participant.create({ tripId: t2._id, userId: host._id, vehicleId: t2veh._id, role: "HOST", displayName: "Rohit", isDriver: true });
  const passenger = await Participant.create({ tripId: t2._id, userId: (await User.create({ name: "Passenger", deviceId: "dev-pax-" + Date.now() + "-xxxxxxxxxx", authProvider: "device" }))._id, vehicleId: t2veh._id, role: "MEMBER", displayName: "Priya" });

  const base = Marker.fromTripMarker(t2, "custom_tyre", { kind: "STATUS", vehicleId: t2veh._id, createdBy: driver._id, location: toPoint(18.5, 73.8) });
  if (base.severity === "CRITICAL" && base.waitingForGroup === true) ok("marker inherits behaviour from the trip snapshot");
  else bad("marker did not inherit behaviour");
  await Marker.create(base);

  // The passenger in the SAME car taps the same thing.
  try {
    await Marker.create(Marker.fromTripMarker(t2, "custom_tyre", { kind: "STATUS", vehicleId: t2veh._id, createdBy: passenger._id, location: toPoint(18.5, 73.8) }));
    bad("second passenger created a DUPLICATE status for one vehicle");
  } catch (e) {
    if (e.code === 11000) ok("passenger cannot duplicate their vehicle's status (partial unique index)");
    else bad(`unexpected: ${e.message}`);
  }

  // A different vehicle is unaffected.
  const otherVeh = await Vehicle.create({ tripId: t2._id, label: "__test Swift2" });
  await Marker.create(Marker.fromTripMarker(t2, "fuel", { kind: "STATUS", vehicleId: otherVeh._id, createdBy: driver._id, location: toPoint(18.6, 73.9) }));
  ok("a different vehicle can hold its own active status");

  // Clearing frees the slot for the next stop.
  const active = await Marker.findOne({ vehicleId: t2veh._id, state: "ACTIVE" });
  active.state = "CLEARED";
  await active.save();
  if (active.durationS !== undefined && active.endedAt) ok("clearing a status stamps endedAt + durationS");
  else bad("clear did not compute duration");
  await Marker.create(Marker.fromTripMarker(t2, "chai", { kind: "STATUS", vehicleId: t2veh._id, createdBy: driver._id, location: toPoint(18.7, 73.9) }));
  ok("vehicle can start a new status once the old one is cleared");

  // PLACE markers are trip-scoped and unconstrained.
  await Marker.create(Marker.fromTripMarker(t2, "fuel", { kind: "PLACE", createdBy: driver._id, location: toPoint(18.9, 73.9) }));
  await Marker.create(Marker.fromTripMarker(t2, "fuel", { kind: "PLACE", createdBy: driver._id, location: toPoint(18.95, 73.95) }));
  ok("PLACE markers are not limited by the vehicle constraint");

  console.log("\n11. Waypoint voting + regroup");
  await Waypoint.init();
  const wp = await Waypoint.create({ tripId: t2._id, proposedBy: driver._id, label: "Lonavala chai", location: toPoint(18.75, 73.4), isRegroupPoint: true });
  wp.castVote(driver._id, "UP");
  wp.castVote(passenger._id, "UP");
  wp.castVote(passenger._id, "DOWN"); // changed mind
  if (wp.votes.length === 2 && wp.voteTally.up === 1 && wp.voteTally.down === 1) ok("one vote per participant; changing your mind replaces it");
  else bad(`vote tally wrong: ${JSON.stringify(wp.voteTally)} across ${wp.votes.length}`);

  wp.recordArrival(t2veh._id);
  wp.recordArrival(t2veh._id); // geofence flap
  if (wp.arrivals.length === 1) ok("re-entering a geofence does not duplicate an arrival");
  else bad(`arrivals duplicated: ${wp.arrivals.length}`);
  if (!wp.allArrived([t2veh._id, otherVeh._id])) ok("regroup not satisfied while one vehicle is missing");
  else bad("regroup satisfied too early");
  wp.recordArrival(otherVeh._id);
  if (wp.allArrived([t2veh._id, otherVeh._id])) ok("regroup satisfied once every vehicle arrives");
  else bad("regroup never satisfied");
  await wp.save();

  console.log("\n12. Cloudinary media");
  const { tripFolder } = require("../utils/media");
  if (tripFolder(t2._id) === `convoy/trips/${t2._id}`) ok("trip folder scopes uploads per trip");
  else bad("trip folder wrong");
  const m = await Marker.findOne({ markerKey: "chai" });
  m.media.push({ publicId: `convoy/trips/${t2._id}/abc123`, url: "https://res.cloudinary.com/x/abc123.jpg", resourceType: "image", bytes: 91234 });
  await m.save();
  const reread = await Marker.findById(m._id);
  if (reread.media[0].publicId) ok("media stores publicId (deletable), not just a url");
  else bad("publicId missing — asset would be undeletable");

  await Promise.all([
    Trip.deleteMany({ name: /^__test/ }),
    Vehicle.deleteMany({ label: /^__test/ }),
    Participant.deleteMany({ tripId: { $in: [trip._id, t2._id] } }),
    Marker.deleteMany({ tripId: t2._id }),
    Waypoint.deleteMany({ tripId: t2._id }),
    User.deleteMany({ name: "Passenger" }),
  ]);
  await mongoose.disconnect();
  console.log(process.exitCode ? "\nFAILURES ABOVE\n" : "\nAll model checks passed.\n");
})().catch((e) => { console.error("ERROR", e); process.exit(1); });
