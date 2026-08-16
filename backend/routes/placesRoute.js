const express = require("express");
const authController = require("../controllers/authController");
const placesController = require("../controllers/placesController");

const router = express.Router();

// Signed in, because these calls cost us upstream quota against a shared
// free provider. Leaving them open would let anyone burn the allowance the
// whole app depends on.
router.use(authController.protect);

router.get("/search", placesController.search);
router.get("/reverse", placesController.reverse);

module.exports = router;
