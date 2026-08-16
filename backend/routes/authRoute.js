const express = require("express");
const authController = require("../controllers/authController");

const router = express.Router();

// Runtime config for the frontend (is Google enabled? client id?).
router.get("/config", authController.authConfig);

// Username + password — the primary sign-in.
router.post("/register", authController.register);
router.post("/login-username", authController.loginWithUsername);

// Device (anonymous) auth — kept for guest joins.
router.post("/device", authController.deviceAuth);

// Local email/password flow.
router.post("/signup", authController.signup);
router.post("/verify-email", authController.verifyEmail);
router.post("/resend-otp", authController.resendOtp);
router.post("/login", authController.login);
router.get("/logout", authController.logout);

// Password reset via OTP.
router.post("/forgot-password", authController.forgotPassword);
router.post("/reset-password", authController.resetPassword);

// Google Sign-In (no-op / 400 when GOOGLE_AUTH_ENABLED=false).
router.post("/google", authController.googleAuth);

// Protected.
router.use(authController.protect);
router.patch("/update-password", authController.updatePassword);

module.exports = router;
