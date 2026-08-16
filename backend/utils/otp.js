const crypto = require("crypto");
const config = require("../config/config");

// One place to generate and hash OTPs so verification and password-reset
// flows stay consistent. We store only the HASH in the DB (never the plain
// code), the same way a password reset token would be stored.

// Generate a numeric OTP of config.otp.length digits (no leading-zero loss).
exports.generateOtp = () => {
  const min = 10 ** (config.otp.length - 1);
  const max = 10 ** config.otp.length - 1;
  return String(crypto.randomInt(min, max + 1));
};

// Deterministic hash of an OTP for storage / comparison.
exports.hashOtp = (otp) =>
  crypto.createHash("sha256").update(String(otp)).digest("hex");

// Expiry timestamp for a freshly issued OTP.
exports.otpExpiry = () =>
  Date.now() + config.otp.expiresInMinutes * 60 * 1000;
