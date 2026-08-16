const express = require("express");
const rateLimit = require("express-rate-limit");
const alertController = require("../controllers/alertController");

const router = express.Router();

// Deliberately NOT behind authController.protect. The share token is the
// credential — the whole point is that family without the app can open it.
//
// Rate limited hard because this is the one trip endpoint reachable with no
// account at all, so it is the natural target for token guessing. The token
// is 48 hex characters, so guessing is hopeless anyway; this stops the noise.
router.get(
  "/:token",
  rateLimit({
    windowMs: 5 * 60 * 1000,
    max: 60,
    standardHeaders: true,
    legacyHeaders: false,
    message: { status: "fail", message: "Too many requests." },
  }),
  alertController.publicLiveView
);

module.exports = router;
