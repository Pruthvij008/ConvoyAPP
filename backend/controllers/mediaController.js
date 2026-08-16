const catchAsync = require("../utils/catchAsync");
const AppError = require("../utils/appError");
const cloudinaryService = require("../services/cloudinary.service");
const { tripFolder } = require("../utils/media");

// ─────────────────────────────────────────────────────────────
// The two-step upload the client walks through:
//
//   1. POST /signature  → we sign a folder-scoped upload
//   2. client uploads DIRECTLY to Cloudinary (we never see the bytes)
//   3. POST /confirm    → we verify the asset really exists, in OUR folder,
//                         and hand back a media object to attach
//
// Step 3 is the security boundary. The publicId a client sends is a claim.
// ─────────────────────────────────────────────────────────────

exports.getUploadSignature = catchAsync(async (req, res, next) => {
  const resourceType = req.body.resourceType || "image";
  if (!["image", "video", "raw"].includes(resourceType)) {
    return next(new AppError("resourceType must be image, video or raw.", 400));
  }
  // Cloudinary handles audio through its video pipeline, so a voice note is
  // uploaded as resource_type "video". Surprising, but that is its API.

  const signature = cloudinaryService.createUploadSignature({
    tripId: req.trip._id,
    resourceType,
  });

  res.status(200).json({ status: "success", data: signature });
});

exports.confirmUpload = catchAsync(async (req, res, next) => {
  const { publicId, resourceType = "image" } = req.body;
  if (!publicId) return next(new AppError("publicId is required.", 400));

  // Verifies existence, ownership by folder prefix, and size. Throws
  // otherwise — a client cannot talk its way past this.
  const media = await cloudinaryService.verifyAsset({
    publicId,
    expectedFolder: tripFolder(req.trip._id),
    resourceType,
  });

  res.status(201).json({
    status: "success",
    data: {
      media: {
        ...media,
        uploadedBy: req.participant._id,
        uploadedAt: new Date(),
      },
    },
  });
});

// Whether uploads are usable at all, so the client can hide the camera
// button rather than failing at the moment someone taps it.
exports.getMediaConfig = (req, res) => {
  res.status(200).json({
    status: "success",
    data: {
      enabled: cloudinaryService.isEnabled(),
      maxBytes: require("../config/config").media.maxBytes,
      voiceRetentionDays: require("../config/config").media.voiceRetentionDays,
    },
  });
};
