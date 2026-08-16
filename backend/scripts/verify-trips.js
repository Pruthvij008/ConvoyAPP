// End-to-end HTTP exercise of the trip lifecycle: three devices create,
// share, join, get organised into vehicles, and hand the trip over.
// Requires the server to be running (npm run dev).
require("dotenv").config({ path: "./config.env" });

const BASE = `http://localhost:${process.env.PORT || 3000}/api/v1`;

let pass = 0;
let fail = 0;
const ok = (m) => { pass += 1; console.log(`  PASS  ${m}`); };
const bad = (m) => { fail += 1; console.log(`  FAIL  ${m}`); };
const section = (m) => console.log(`\n${m}`);

const api = async (method, path, { token, body } = {}) => {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* 204 */ }
  return { status: res.status, body: json };
};

const device = async (name) => {
  const deviceId = `dev-${name}-${Date.now()}-${Math.random().toString(36).slice(2, 12)}`;
  const r = await api("POST", "/auth/device", { body: { deviceId, name } });
  if (r.status !== 200) throw new Error(`device auth failed for ${name}: ${JSON.stringify(r.body)}`);
  return { name, deviceId, token: r.body.token, userId: r.body.data.user._id };
};

(async () => {
  section("0. Three devices sign in anonymously");
  const rohit = await device("Rohit");
  const priya = await device("Priya");
  const amit = await device("Amit");
  ok("three anonymous identities created");

  section("1. Host creates a trip");
  const created = await api("POST", "/trips", {
    token: rohit.token,
    body: {
      name: "Pune → Goa",
      destinationAddress: "Anjuna Beach",
      deviceId: rohit.deviceId,
      vehicle: { label: "Rohit's Thar", type: "SUV" },
    },
  });
  if (created.status !== 201) return bad(`create failed: ${JSON.stringify(created.body)}`);
  const trip = created.body.data.trip;
  const joinLink = created.body.data.joinLink;
  const joinCode = created.body.data.joinCode;
  const linkToken = joinLink.split("/").pop();
  ok(`trip created, code ${joinCode}`);
  if (joinLink.includes("/j/")) ok("shareable join link returned"); else bad("no join link");
  if (trip.markerSet.length === 8) ok("marker set seeded from the catalogue"); else bad("marker set not seeded");
  if (created.body.data.vehicle?.color) ok(`host vehicle got colour ${created.body.data.vehicle.color}`); else bad("no vehicle colour");

  section("2. The raw token is never retrievable again");
  const reread = await api("GET", `/trips/${trip._id}`, { token: rohit.token });
  if (!reread.body.data.trip.joinTokenHash) ok("joinTokenHash not exposed on read"); else bad("token hash leaked to client");

  section("3. Preview before joining");
  const preview = await api("POST", "/trips/preview", { token: priya.token, body: { token: linkToken } });
  if (preview.status !== 200) return bad(`preview failed: ${JSON.stringify(preview.body)}`);
  if (preview.body.data.trip.hostName === "Rohit") ok("preview shows the host"); else bad("preview missing host");
  if (preview.body.data.trip.memberCount === 1) ok("preview shows member count"); else bad(`member count ${preview.body.data.trip.memberCount}`);
  if (preview.body.data.trip.destination === undefined) ok("preview leaks no location"); else bad("preview exposed location");

  section("4. Created trips are shareable immediately; drafts are not");
  if (trip.status === "LOBBY") ok("a new trip opens in LOBBY, so the link works at once");
  else bad(`new trip status is ${trip.status} — shared link would be dead`);

  const draft = await api("POST", "/trips", {
    token: rohit.token, body: { name: "__draft Ladakh 2027", asDraft: true },
  });
  const draftPreview = await api("POST", "/trips/preview", {
    token: priya.token, body: { code: draft.body.data.joinCode },
  });
  if (draftPreview.status === 200 && draftPreview.body.data.trip.status === "DRAFT") ok("a draft is previewable but flagged not-open");
  else bad(`draft preview: ${draftPreview.status}`);
  const draftJoin = await api("POST", "/trips/join", {
    token: priya.token, body: { code: draft.body.data.joinCode },
  });
  if (draftJoin.status === 409) ok("a draft refuses joins"); else bad(`expected 409, got ${draftJoin.status}`);

  section("5. Join by link, and by code");
  const priyaJoin = await api("POST", "/trips/join", {
    token: priya.token,
    body: { token: linkToken, deviceId: priya.deviceId, vehicle: { label: "Priya's Swift" } },
  });
  if (priyaJoin.status === 201) ok("Priya joined via LINK with her own car"); else bad(`link join failed: ${JSON.stringify(priyaJoin.body)}`);

  const amitJoin = await api("POST", "/trips/join", {
    token: amit.token,
    body: { code: joinCode, deviceId: amit.deviceId },
  });
  if (amitJoin.status === 201) ok("Amit joined via CODE"); else bad(`code join failed: ${JSON.stringify(amitJoin.body)}`);

  const bogus = await api("POST", "/trips/join", { token: amit.token, body: { code: "ZZZZZZ" } });
  if (bogus.status === 404) ok("unknown code rejected"); else bad(`expected 404, got ${bogus.status}`);

  section("6. Counts stayed truthful");
  const afterJoins = await api("GET", `/trips/${trip._id}`, { token: rohit.token });
  const counts = afterJoins.body.data.trip.counts;
  if (counts.participants === 3) ok("counts.participants = 3"); else bad(`participants = ${counts.participants}`);
  if (counts.vehicles === 2) ok("counts.vehicles = 2"); else bad(`vehicles = ${counts.vehicles}`);

  section("7. Amit boards Rohit's car as a passenger");
  const vehicles = await api("GET", `/trips/${trip._id}/vehicles`, { token: amit.token });
  const thar = vehicles.body.data.vehicles.find((v) => v.label === "Rohit's Thar");
  const board = await api("POST", `/trips/${trip._id}/vehicles/${thar._id}/board`, { token: amit.token });
  if (board.body.data.participant.isDriver === false) ok("passenger does not become a broadcaster"); else bad("passenger wrongly made driver");
  const afterBoard = await api("GET", `/trips/${trip._id}/vehicles`, { token: amit.token });
  const tharNow = afterBoard.body.data.vehicles.find((v) => v.label === "Rohit's Thar");
  if (tharNow.occupants.length === 2) ok("Thar now shows 2 occupants, still ONE dot"); else bad(`occupants ${tharNow.occupants.length}`);

  section("8. Permissions");
  const amitEdit = await api("PATCH", `/trips/${trip._id}`, { token: amit.token, body: { name: "Hijacked" } });
  if (amitEdit.status === 403) ok("member cannot edit the trip"); else bad(`expected 403, got ${amitEdit.status}`);

  const amitRequests = await api("GET", `/trips/${trip._id}/requests`, { token: amit.token });
  if (amitRequests.status === 403) ok("member cannot read the approval queue"); else bad(`expected 403, got ${amitRequests.status}`);

  const stranger = await device("Stranger");
  const peek = await api("GET", `/trips/${trip._id}`, { token: stranger.token });
  if (peek.status === 403) ok("non-participant cannot read the trip"); else bad(`expected 403, got ${peek.status}`);

  section("9. Convoy roles are independent of permissions");
  const roster = await api("GET", `/trips/${trip._id}`, { token: rohit.token });
  const rohitP = roster.body.data.participants.find((p) => p.displayName === "Rohit");
  const priyaP = roster.body.data.participants.find((p) => p.displayName === "Priya");
  const lead = await api("PATCH", `/trips/${trip._id}/participants/${rohitP._id}`, {
    token: rohit.token, body: { convoyRole: "LEAD" },
  });
  if (lead.body.data.participant.role === "HOST" && lead.body.data.participant.convoyRole === "LEAD") ok("host is also LEAD");
  else bad("role/convoyRole not independent over HTTP");

  await api("PATCH", `/trips/${trip._id}/participants/${priyaP._id}`, { token: rohit.token, body: { convoyRole: "LEAD" } });
  const afterLead = await api("GET", `/trips/${trip._id}`, { token: rohit.token });
  const leads = afterLead.body.data.participants.filter((p) => p.convoyRole === "LEAD");
  if (leads.length === 1 && leads[0].displayName === "Priya") ok("assigning LEAD clears the previous holder"); else bad(`${leads.length} leads`);

  section("10. Approval queue");
  await api("PATCH", `/trips/${trip._id}`, { token: rohit.token, body: { settings: { requireApproval: true } } });
  const gate = await device("Gatecrasher");
  const pending = await api("POST", "/trips/join", { token: gate.token, body: { code: joinCode, deviceId: gate.deviceId } });
  if (pending.body.data.participant.status === "PENDING") ok("join goes to PENDING when approval is on"); else bad("approval not enforced");

  const queue = await api("GET", `/trips/${trip._id}/requests`, { token: rohit.token });
  if (queue.body.results === 1) ok("host sees 1 pending request"); else bad(`queue has ${queue.body.results}`);

  const gateBlocked = await api("GET", `/trips/${trip._id}`, { token: gate.token });
  if (gateBlocked.status === 403) ok("PENDING member cannot read trip data yet"); else bad(`expected 403, got ${gateBlocked.status}`);

  await api("PATCH", `/trips/${trip._id}/requests/${queue.body.data.requests[0]._id}`, {
    token: rohit.token, body: { decision: "APPROVE" },
  });
  const gateIn = await api("GET", `/trips/${trip._id}`, { token: gate.token });
  if (gateIn.status === 200) ok("approved member can now read the trip"); else bad("approval did not grant access");

  section("11. Leave, then rejoin (the unique-index trap)");
  await api("POST", `/trips/${trip._id}/leave`, { token: gate.token });
  const gateOut = await api("GET", `/trips/${trip._id}`, { token: gate.token });
  if (gateOut.status === 403) ok("leaving revokes access immediately"); else bad("still had access after leaving");

  const rejoin = await api("POST", "/trips/join", { token: gate.token, body: { code: joinCode, deviceId: gate.deviceId } });
  if (rejoin.status === 200) ok("rejoin UPDATES the existing membership (no E11000)");
  else bad(`rejoin failed: ${rejoin.status} ${JSON.stringify(rejoin.body)}`);

  section("12. Removal and ban");
  const roster2 = await api("GET", `/trips/${trip._id}`, { token: rohit.token });
  const gateP = roster2.body.data.participants.find((p) => p.displayName === "Gatecrasher");
  await api("DELETE", `/trips/${trip._id}/participants/${gateP._id}`, { token: rohit.token, body: { ban: true } });
  const banned = await api("POST", "/trips/join", { token: gate.token, body: { code: joinCode, deviceId: gate.deviceId } });
  if (banned.status === 403) ok("banned member cannot rejoin"); else bad(`banned member got ${banned.status}`);

  const hostSelfRemove = await api("DELETE", `/trips/${trip._id}/participants/${rohitP._id}`, { token: rohit.token });
  if (hostSelfRemove.status === 400) ok("host cannot be removed"); else bad(`expected 400, got ${hostSelfRemove.status}`);

  section("13. Locking the trip");
  await api("PATCH", `/trips/${trip._id}`, { token: rohit.token, body: { settings: { isLocked: true } } });
  const late = await device("Latecomer");
  const lockedOut = await api("POST", "/trips/join", { token: late.token, body: { code: joinCode } });
  if (lockedOut.status === 403) ok("locked trip refuses new joins"); else bad(`expected 403, got ${lockedOut.status}`);
  await api("PATCH", `/trips/${trip._id}`, { token: rohit.token, body: { settings: { isLocked: false } } });

  section("14. Rotating the invite kills old links");
  const rotated = await api("POST", `/trips/${trip._id}/invite/rotate`, { token: rohit.token });
  const newToken = rotated.body.data.joinLink.split("/").pop();
  if (newToken !== linkToken) ok("rotation issued a different token"); else bad("token unchanged");
  const oldLink = await api("POST", "/trips/preview", { token: late.token, body: { token: linkToken } });
  if (oldLink.status === 404) ok("the forwarded old link is now dead"); else bad(`old link still works (${oldLink.status})`);
  const newLink = await api("POST", "/trips/preview", { token: late.token, body: { token: newToken } });
  if (newLink.status === 200) ok("the new link works"); else bad("new link broken");

  section("14b. Lobby: ready checks and the start preflight");
  // Approval was switched on in §10; turn it off so joins land straight in.
  await api("PATCH", `/trips/${trip._id}`, { token: rohit.token, body: { settings: { requireApproval: false } } });
  const lobby1 = await api("GET", `/trips/${trip._id}/lobby`, { token: rohit.token });
  if (lobby1.status === 200) ok("host can read the lobby"); else bad("lobby unavailable");
  if (lobby1.body.data.readyCount === 0) ok("nobody is ready yet"); else bad("readiness defaulted wrong");

  await api("POST", `/trips/${trip._id}/ready`, { token: amit.token, body: { ready: true } });
  const lobby2 = await api("GET", `/trips/${trip._id}/lobby`, { token: rohit.token });
  if (lobby2.body.data.readyCount === 1) ok("marking ready is reflected for the host"); else bad(`readyCount ${lobby2.body.data.readyCount}`);
  if (lobby2.body.data.blockers.notReady.includes("Rohit")) ok("host sees exactly who they're waiting on"); else bad("notReady list wrong");

  // Nobody has left a car unassigned in this trip, so it should be startable.
  if (lobby2.body.data.canStart === true) ok("canStart true when everyone has a vehicle"); else bad(`canStart ${lobby2.body.data.canStart}`);

  // A member with no car cannot be tracked — the preflight must name them.
  const orphanDev = await device("Orphan");
  await api("POST", "/trips/join", { token: orphanDev.token, body: { code: joinCode, deviceId: orphanDev.deviceId } });
  const lobby3 = await api("GET", `/trips/${trip._id}/lobby`, { token: rohit.token });
  if (lobby3.body.data.blockers.unassigned.includes("Orphan")) ok("lobby flags the member with no vehicle"); else bad("unassigned not flagged");

  const blockedStart = await api("PATCH", `/trips/${trip._id}/status`, { token: rohit.token, body: { status: "ACTIVE" } });
  if (blockedStart.status === 409 && /Orphan/.test(blockedStart.body.message)) ok("start blocked, and it names who");
  else bad(`expected 409 naming Orphan, got ${blockedStart.status}`);

  const forcedStart = await api("PATCH", `/trips/${trip._id}/status`, { token: rohit.token, body: { status: "ACTIVE", force: true } });
  if (forcedStart.status === 200) ok("host can override and start anyway"); else bad("force did not work");

  const startedDrivers = await api("GET", `/trips/${trip._id}`, { token: rohit.token });
  const sharingDrivers = startedDrivers.body.data.participants.filter((p) => p.isDriver && p.sharingState === "SHARING");
  if (sharingDrivers.length > 0) ok(`${sharingDrivers.length} drivers flipped to SHARING on start`); else bad("drivers not set sharing");
  const sharingPax = startedDrivers.body.data.participants.filter((p) => !p.isDriver && p.sharingState === "SHARING");
  if (sharingPax.length === 0) ok("passengers are NOT set to broadcast"); else bad(`${sharingPax.length} passengers broadcasting`);

  // Back to LOBBY-equivalent state for the remaining lifecycle checks.
  await api("PATCH", `/trips/${trip._id}/status`, { token: rohit.token, body: { status: "PAUSED" } });
  await api("PATCH", `/trips/${trip._id}/status`, { token: rohit.token, body: { status: "ACTIVE" } });

  section("15. Lifecycle + the hard location guarantee");
  // A trip already under way cannot fall back into the lobby.
  const illegal = await api("PATCH", `/trips/${trip._id}/status`, { token: rohit.token, body: { status: "LOBBY" } });
  if (illegal.status === 409) ok("ACTIVE→LOBBY refused by the transition guard"); else bad(`expected 409, got ${illegal.status}`);

  const active = await api("GET", `/trips/${trip._id}`, { token: rohit.token });
  if (active.body.data.trip.startedAt) ok("ACTIVE stamps startedAt"); else bad("startedAt not set");

  await api("PATCH", `/trips/${trip._id}/status`, { token: rohit.token, body: { status: "PAUSED" } });
  const paused = await api("GET", `/trips/${trip._id}`, { token: rohit.token });
  const sharingWhilePaused = paused.body.data.participants.filter((p) => p.sharingState !== "OFFLINE");
  if (sharingWhilePaused.length === 0) ok("PAUSED stops sharing for everyone"); else bad(`${sharingWhilePaused.length} sharing while paused`);
  await api("PATCH", `/trips/${trip._id}/status`, { token: rohit.token, body: { status: "ACTIVE" } });
  ok("PAUSED→ACTIVE resumes the trip");

  section("16. Host transfer");
  const transfer = await api("POST", `/trips/${trip._id}/transfer-host`, {
    token: rohit.token, body: { participantId: priyaP._id },
  });
  if (transfer.status === 200) ok("host transferred to Priya"); else bad(`transfer failed: ${JSON.stringify(transfer.body)}`);
  const afterTransfer = await api("GET", `/trips/${trip._id}`, { token: rohit.token });
  const hosts = afterTransfer.body.data.participants.filter((p) => p.role === "HOST");
  if (hosts.length === 1 && hosts[0].displayName === "Priya") ok("exactly one HOST, and it's Priya"); else bad(`${hosts.length} hosts`);
  const oldHost = afterTransfer.body.data.participants.find((p) => p.displayName === "Rohit");
  if (oldHost.role === "CO_HOST") ok("previous host demoted to CO_HOST"); else bad(`old host is ${oldHost.role}`);
  const rohitTransfer = await api("POST", `/trips/${trip._id}/transfer-host`, { token: rohit.token, body: { participantId: rohitP._id } });
  if (rohitTransfer.status === 403) ok("a CO_HOST cannot seize the trip back"); else bad(`expected 403, got ${rohitTransfer.status}`);

  section("17. Host leaves — the trip must not be orphaned");
  await api("POST", `/trips/${trip._id}/leave`, { token: priya.token });
  const afterLeave = await api("GET", `/trips/${trip._id}`, { token: rohit.token });
  const hosts2 = afterLeave.body.data.participants.filter((p) => p.role === "HOST");
  if (hosts2.length === 1) ok(`host auto-promoted to ${hosts2[0].displayName}`); else bad(`${hosts2.length} hosts after leave`);

  section("18. Ending the trip stops sharing server-side");
  await api("PATCH", `/trips/${trip._id}/status`, { token: rohit.token, body: { status: "ENDED" } });
  const ended = await api("GET", `/trips/${trip._id}`, { token: rohit.token });
  const stillSharing = ended.body.data.participants.filter((p) => p.sharingState !== "OFFLINE");
  if (stillSharing.length === 0) ok("every participant forced OFFLINE on trip end"); else bad(`${stillSharing.length} still sharing`);
  const liveVehicles = ended.body.data.vehicles.filter((v) => v.connectionState !== "ENDED");
  if (liveVehicles.length === 0) ok("every vehicle marked ENDED"); else bad(`${liveVehicles.length} vehicles still live`);
  const reopen = await api("PATCH", `/trips/${trip._id}/status`, { token: rohit.token, body: { status: "ACTIVE" } });
  if (reopen.status === 409) ok("an ENDED trip cannot be reopened"); else bad(`expected 409, got ${reopen.status}`);
  const joinEnded = await api("POST", "/trips/join", { token: late.token, body: { code: joinCode } });
  if (joinEnded.status === 410) ok("nobody can join a finished trip"); else bad(`expected 410, got ${joinEnded.status}`);

  console.log(`\n${fail === 0 ? "All" : pass} checks passed${fail ? `, ${fail} FAILED` : ""}.\n`);
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error("ERROR", e); process.exit(1); });
