const express = require("express");
const userController = require("../controllers/userController");
const authController = require("../controllers/authController");

const router = express.Router();

// Everything below requires a valid token.
router.use(authController.protect);

router
  .route("/me")
  .get(userController.getMe)
  .patch(userController.updateMe)
  .delete(userController.deleteMe);

module.exports = router;
