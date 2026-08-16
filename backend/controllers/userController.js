const User = require("../models/userModel");
const catchAsync = require("../utils/catchAsync");
const cloudinaryService = require("../services/cloudinary.service");
const { userFolder } = require("../utils/media");
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


// ── Profile photo ────────────────────────────────────────────────
// Entirely optional. A convoy works perfectly with initials on a coloured
// disc, so this is never required and never blocks anything — but a real
// face makes a roster of six cars far easier to read at a glance.
//
// Same two-step as every other upload: we sign a folder-scoped request, the
// phone uploads DIRECTLY to Cloudinary (bytes never touch this server), and
// then we verify the asset really exists under this user's own folder.
// Without that last step a client could PATCH any publicId onto itself and
// point at somebody else's upload.

exports.getAvatarSignature = catchAsync(async (req, res) => {
  const signature = cloudinaryService.createUploadSignature({
    userId: req.user.id,
    resourceType: "image",
  });
  res.status(200).json({ status: "success", data: signature });
});

exports.setAvatar = catchAsync(async (req, res, next) => {
  const { publicId } = req.body;
  if (!publicId) return next(new AppError("publicId is required.", 400));

  const media = await cloudinaryService.verifyAsset({
    publicId,
    expectedFolder: userFolder(req.user.id),
    resourceType: "image",
  });

  const user = await User.findById(req.user.id);

  // The previous avatar is deleted rather than orphaned. Cloudinary bills
  // on stored bytes, and a user who changes their photo ten times should
  // not leave ten images behind forever.
  const previous = user.photoMedia?.publicId;
  if (previous && previous !== publicId) {
    cloudinaryService.destroyAsset(previous, "image").catch(() => {});
  }

  user.photoMedia = { ...media, uploadedAt: new Date() };
  user.photo = media.url;
  await user.save({ validateBeforeSave: false });

  res.status(200).json({ status: "success", data: { user } });
});

exports.removeAvatar = catchAsync(async (req, res) => {
  const user = await User.findById(req.user.id);
  const previous = user.photoMedia?.publicId;
  if (previous) cloudinaryService.destroyAsset(previous, "image").catch(() => {});

  user.photoMedia = undefined;
  user.photo = "default.jpg";
  await user.save({ validateBeforeSave: false });

  res.status(200).json({ status: "success", data: { user } });
});
