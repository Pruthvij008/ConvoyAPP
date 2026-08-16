const User = require("../models/userModel");
const catchAsync = require("../utils/catchAsync");
const AppError = require("../utils/appError");

// Return only the fields we allow a user to change about themselves.
const filterBody = (body, ...allowed) => {
  const out = {};
  Object.keys(body).forEach((key) => {
    if (allowed.includes(key)) out[key] = body[key];
  });
  return out;
};

// GET /users/me — the currently logged-in user (set by authController.protect).
exports.getMe = catchAsync(async (req, res) => {
  res.status(200).json({ status: "success", data: { user: req.user } });
});

// PATCH /users/me — update own profile (not password; use /update-password).
exports.updateMe = catchAsync(async (req, res, next) => {
  if (req.body.password || req.body.passwordConfirm) {
    return next(
      new AppError("Use /auth/update-password to change your password.", 400)
    );
  }
  // "photo" is deliberately NOT in this list. Avatars are set through the
  // media confirm endpoint, which verifies the Cloudinary asset actually
  // exists and belongs to this user — otherwise a client could PATCH an
  // arbitrary publicId onto itself and point at someone else's upload.
  const filtered = filterBody(req.body, "name", "phone");
  const user = await User.findByIdAndUpdate(req.user.id, filtered, {
    new: true,
    runValidators: true,
  });
  res.status(200).json({ status: "success", data: { user } });
});

// DELETE /users/me — soft-delete (deactivate) own account.
exports.deleteMe = catchAsync(async (req, res) => {
  await User.findByIdAndUpdate(req.user.id, { active: false });
  res.status(204).json({ status: "success", data: null });
});
