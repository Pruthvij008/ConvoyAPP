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

// Optional profile photo. Signed, uploaded direct to Cloudinary, verified.
router.post("/me/avatar/signature", userController.getAvatarSignature);
router
  .route("/me/avatar")
  .post(userController.setAvatar)
  .delete(userController.removeAvatar);

module.exports = router;
