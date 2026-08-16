const express = require("express");
const authController = require("../controllers/authController");
const markerController = require("../controllers/markerController");

const router = express.Router();

// Trip-scoped marker routes live in tripRoute.js. These two are global:
// the built-in catalogue, and the caller's personal marker library.
router.use(authController.protect);

// Served rather than hardcoded in the app, so the client's marker list can
// never drift from the server's behaviour rules.
router.get("/catalogue", markerController.getCatalogue);

// Personal library. A marker you liked on someone else's trip can be copied
// here and reused — which is most of a "group library" without inventing a
// persistent crew concept.
router
  .route("/library")
  .get(markerController.getMyMarkers)
  .post(markerController.saveCustomMarker);

router.delete("/library/:key", markerController.deleteCustomMarker);

// Quick messages and media capability live here too — all three are
// "what can this client do", fetched once at startup.
const messageController = require("../controllers/messageController");
const mediaController = require("../controllers/mediaController");
router.get("/quick-messages", messageController.getQuickMessages);
router.get("/media-config", mediaController.getMediaConfig);

module.exports = router;
