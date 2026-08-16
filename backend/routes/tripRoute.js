const express = require("express");
const authController = require("../controllers/authController");
const tripController = require("../controllers/tripController");
const vehicleController = require("../controllers/vehicleController");
const markerController = require("../controllers/markerController");
const waypointController = require("../controllers/waypointController");
const alertController = require("../controllers/alertController");
const messageController = require("../controllers/messageController");
const mediaController = require("../controllers/mediaController");
const {
  loadTrip,
  requireParticipant,
  requireTripRole,
  requireOwner,
} = require("../middleware/tripAuth");

const router = express.Router();

// Everything below needs an identity — device, email or Google.
router.use(authController.protect);

// ── Joining ──────────────────────────────────────────────────────
// Preview first: tapping a shared link should show "Goa Ride · Rohit ·
// 4 members" before asking anyone to commit.
router.post("/preview", tripController.previewTrip);
router.post("/join", tripController.joinTrip);

// ── Trips ────────────────────────────────────────────────────────
router
  .route("/")
  .get(tripController.getMyTrips)
  .post(tripController.createTrip);

router
  .route("/:tripId")
  .get(loadTrip, requireParticipant, tripController.getTrip)
  .patch(
    loadTrip,
    requireParticipant,
    requireTripRole("HOST", "CO_HOST"),
    tripController.updateTrip
  );

router.patch(
  "/:tripId/status",
  loadTrip,
  requireParticipant,
  requireTripRole("HOST", "CO_HOST"),
  tripController.updateStatus
);

router.post("/:tripId/leave", loadTrip, requireParticipant, tripController.leaveTrip);

// ── Lobby ────────────────────────────────────────────────────────
router.get("/:tripId/lobby", loadTrip, requireParticipant, tripController.getLobby);
router.post("/:tripId/ready", loadTrip, requireParticipant, tripController.setReady);

router.post(
  "/:tripId/transfer-host",
  loadTrip,
  requireParticipant,
  requireOwner,
  tripController.transferHost
);

router.post(
  "/:tripId/invite/rotate",
  loadTrip,
  requireParticipant,
  requireTripRole("HOST", "CO_HOST"),
  tripController.rotateInvite
);

// ── Approval queue ───────────────────────────────────────────────
router.get(
  "/:tripId/requests",
  loadTrip,
  requireParticipant,
  requireTripRole("HOST", "CO_HOST"),
  tripController.getJoinRequests
);

router.patch(
  "/:tripId/requests/:participantId",
  loadTrip,
  requireParticipant,
  requireTripRole("HOST", "CO_HOST"),
  tripController.decideJoinRequest
);

// ── Roster ───────────────────────────────────────────────────────
router
  .route("/:tripId/participants/:participantId")
  .patch(
    loadTrip,
    requireParticipant,
    requireTripRole("HOST", "CO_HOST"),
    tripController.updateParticipant
  )
  .delete(
    loadTrip,
    requireParticipant,
    requireTripRole("HOST", "CO_HOST"),
    tripController.removeParticipant
  );

// ── Vehicles ─────────────────────────────────────────────────────
router
  .route("/:tripId/vehicles")
  .get(loadTrip, requireParticipant, vehicleController.listVehicles)
  .post(loadTrip, requireParticipant, vehicleController.createVehicle);

router
  .route("/:tripId/vehicles/:vehicleId")
  .patch(loadTrip, requireParticipant, vehicleController.updateVehicle)
  .delete(
    loadTrip,
    requireParticipant,
    requireTripRole("HOST", "CO_HOST"),
    vehicleController.deleteVehicle
  );

router.post(
  "/:tripId/vehicles/:vehicleId/board",
  loadTrip,
  requireParticipant,
  vehicleController.boardVehicle
);

// ── Markers ──────────────────────────────────────────────────────
router
  .route("/:tripId/markers")
  .get(loadTrip, requireParticipant, markerController.listMarkers)
  .post(loadTrip, requireParticipant, markerController.createMarker);

router
  .route("/:tripId/markers/:markerId")
  .patch(loadTrip, requireParticipant, markerController.updateMarker)
  .delete(loadTrip, requireParticipant, markerController.deleteMarker);

// Resuming the drive. Distinct from delete: a cleared stop stays in history
// and feeds the trip recap.
router.post(
  "/:tripId/markers/:markerId/clear",
  loadTrip,
  requireParticipant,
  markerController.clearMarker
);

// ── The trip's curated marker set ────────────────────────────────
// Any member may add a marker (that's the point of custom markers), but
// reordering and favourites shape everyone's picker, so those are host-only.
router.post(
  "/:tripId/marker-set",
  loadTrip,
  requireParticipant,
  markerController.addToMarkerSet
);

router
  .route("/:tripId/marker-set/:key")
  .patch(
    loadTrip,
    requireParticipant,
    requireTripRole("HOST", "CO_HOST"),
    markerController.updateMarkerSetEntry
  )
  .delete(
    loadTrip,
    requireParticipant,
    requireTripRole("HOST", "CO_HOST"),
    markerController.removeFromMarkerSet
  );

// ── Waypoints ────────────────────────────────────────────────────
router
  .route("/:tripId/waypoints")
  .get(loadTrip, requireParticipant, waypointController.listWaypoints)
  .post(loadTrip, requireParticipant, waypointController.createWaypoint);

router.patch(
  "/:tripId/waypoints/reorder",
  loadTrip,
  requireParticipant,
  requireTripRole("HOST", "CO_HOST"),
  waypointController.reorderWaypoints
);

router
  .route("/:tripId/waypoints/:waypointId")
  .patch(loadTrip, requireParticipant, waypointController.updateWaypoint)
  .delete(loadTrip, requireParticipant, waypointController.deleteWaypoint);

router.post(
  "/:tripId/waypoints/:waypointId/vote",
  loadTrip,
  requireParticipant,
  waypointController.voteWaypoint
);

router.post(
  "/:tripId/waypoints/:waypointId/arrive",
  loadTrip,
  requireParticipant,
  waypointController.arriveAtWaypoint
);

// ── Alerts ───────────────────────────────────────────────────────
router.get("/:tripId/alerts", loadTrip, requireParticipant, alertController.listAlerts);

router.post(
  "/:tripId/alerts/:alertId/ack",
  loadTrip,
  requireParticipant,
  alertController.acknowledgeAlert
);

router.post(
  "/:tripId/alerts/:alertId/resolve",
  loadTrip,
  requireParticipant,
  alertController.resolveAlert
);

// SOS bypasses the sweeper entirely — the countdown already happened on the
// device, so this fires immediately.
router.post("/:tripId/sos", loadTrip, requireParticipant, alertController.raiseSos);

// ── Public live-share link ───────────────────────────────────────
router
  .route("/:tripId/share")
  .post(loadTrip, requireParticipant, alertController.createShareLink)
  .delete(
    loadTrip,
    requireParticipant,
    requireTripRole("HOST", "CO_HOST"),
    alertController.revokeShareLink
  );

// ── Chat ─────────────────────────────────────────────────────────
// The socket is the path clients normally use; these exist so a phone with
// a dropped socket can still send and read, which in a car is often.
router
  .route("/:tripId/messages")
  .get(loadTrip, requireParticipant, messageController.listMessages)
  .post(loadTrip, requireParticipant, messageController.sendMessage);

router.post(
  "/:tripId/messages/read",
  loadTrip,
  requireParticipant,
  messageController.markRead
);

// ── Directions from a member's current position ──────────────────
// POST rather than GET because it carries the caller's live coordinates,
// and a position does not belong in a URL that ends up in access logs.
router.post(
  "/:tripId/route",
  loadTrip,
  requireParticipant,
  tripController.getMyRoute
);

// ── Going to help someone ────────────────────────────────────────
// Any member may peel off to help — needing permission to rescue a friend
// with a puncture would be absurd.
const helpController = require("../controllers/helpController");
router
  .route("/:tripId/help")
  .get(loadTrip, requireParticipant, helpController.listHelpers)
  .post(loadTrip, requireParticipant, helpController.startHelping)
  .delete(loadTrip, requireParticipant, helpController.stopHelping);

// ── Media ────────────────────────────────────────────────────────
router.post(
  "/:tripId/media/signature",
  loadTrip,
  requireParticipant,
  mediaController.getUploadSignature
);

// The security boundary: a publicId from a client is a claim until this
// verifies it exists and lives under this trip's folder.
router.post(
  "/:tripId/media/confirm",
  loadTrip,
  requireParticipant,
  mediaController.confirmUpload
);

module.exports = router;
