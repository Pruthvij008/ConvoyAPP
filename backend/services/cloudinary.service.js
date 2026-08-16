const crypto = require("crypto");
const cloudinary = require("cloudinary").v2;
const config = require("../config/config");
const AppError = require("../utils/appError");
const { tripFolder, userFolder } = require("../utils/media");

// ─────────────────────────────────────────────────────────────
// Cloudinary — signed direct uploads.
//
// The phone uploads STRAIGHT to Cloudinary; the bytes never pass through
// this server. That matters on a patchy highway connection and keeps our
// bandwidth bill at zero. We only ever handle two small things: the
// signature going out, and the publicId coming back.
//
// The publicId coming back is a CLAIM, not a fact. verifyAsset() checks
// with Cloudinary that the asset exists AND sits under this trip's folder,
// because otherwise a client could attach another trip's photo to its own
// marker just by naming it.
// ─────────────────────────────────────────────────────────────

let configured = false;

const ensureConfigured = () => {
  if (!config.media.enabled) {
    throw new AppError(
      "Media uploads aren't configured on this server yet.",
      503
    );
  }
  if (!configured) {
    cloudinary.config({
      cloud_name: config.media.cloudName,
      api_key: config.media.apiKey,
      api_secret: config.media.apiSecret,
      secure: true,
    });
    configured = true;
  }
};

// Cloudinary's signature is a SHA-1 of the sorted params plus the secret.
// Implemented directly rather than via the SDK helper so the exact set of
// signed params is visible — anything not signed here is something a client
// could tamper with.
exports.signUploadParams = (params) => {
  const toSign = Object.keys(params)
    .filter((k) => params[k] !== undefined && params[k] !== null && params[k] !== "")
    .sort()
    .map((k) => `${k}=${params[k]}`)
    .join("&");

  return crypto
    .createHash("sha1")
    .update(toSign + config.media.apiSecret)
    .digest("hex");
};

// Everything a client needs to upload one file, and nothing more.
exports.createUploadSignature = ({ tripId, userId, resourceType = "image" }) => {
  ensureConfigured();

  const timestamp = Math.floor(Date.now() / 1000);
  // The folder is SIGNED, so a signature issued for trip A physically
  // cannot be used to write into trip B.
  const folder = tripId ? tripFolder(tripId) : userFolder(userId);

  const params = { timestamp, folder };
  const signature = exports.signUploadParams(params);

  return {
    signature,
    timestamp,
    folder,
    apiKey: config.media.apiKey,
    cloudName: config.media.cloudName,
    resourceType,
    uploadUrl: `https://api.cloudinary.com/v1_1/${config.media.cloudName}/${resourceType}/upload`,
    // Advisory for the client; the real limit is enforced on confirm, since
    // a client can ignore anything we tell it.
    maxBytes: config.media.maxBytes,
    expiresAt: new Date((timestamp + config.media.signatureTtlSec) * 1000),
  };
};

// Step 6 of the upload flow, and NOT optional. Without it a client can
// confirm any publicId it likes, including someone else's.
exports.verifyAsset = async ({ publicId, expectedFolder, resourceType = "image" }) => {
  ensureConfigured();

  if (!publicId?.startsWith(`${expectedFolder}/`)) {
    throw new AppError("That upload doesn't belong to this trip.", 403);
  }

  let asset;
  try {
    asset = await cloudinary.api.resource(publicId, { resource_type: resourceType });
  } catch (err) {
    if (err?.http_code === 404) {
      throw new AppError("That upload doesn't exist.", 404);
    }
    throw new AppError("Could not verify the upload.", 502);
  }

  if (asset.bytes > config.media.maxBytes) {
    // Delete rather than orphan it — the client already paid to upload it,
    // but we are not going to store something oversized.
    await exports.destroy(publicId, resourceType).catch(() => {});
    throw new AppError("That file is too large.", 413);
  }

  return {
    publicId: asset.public_id,
    url: asset.secure_url,
    resourceType: asset.resource_type,
    format: asset.format,
    bytes: asset.bytes,
    width: asset.width,
    height: asset.height,
    durationS: asset.duration,
  };
};

exports.destroy = async (publicId, resourceType = "image") => {
  ensureConfigured();
  return cloudinary.uploader.destroy(publicId, { resource_type: resourceType });
};

// Deleting a trip deletes its whole folder in one call — which is exactly
// why every asset is uploaded under a trip-scoped prefix.
exports.destroyTripFolder = async (tripId) => {
  ensureConfigured();
  const folder = tripFolder(tripId);
  const results = {};
  for (const type of ["image", "video", "raw"]) {
    try {
      results[type] = await cloudinary.api.delete_resources_by_prefix(folder, {
        resource_type: type,
      });
    } catch {
      /* a folder with no assets of this type is not an error */
    }
  }
  return results;
};

// Assets Cloudinary knows about that nothing in Mongo references. Happens
// when an upload succeeds and the app is killed before confirming — the
// asset is then paid for and referenced by nothing.
exports.listOrphans = async (tripId, referencedPublicIds) => {
  ensureConfigured();
  const folder = tripFolder(tripId);
  const referenced = new Set(referencedPublicIds);
  const orphans = [];

  for (const type of ["image", "video"]) {
    try {
      const { resources } = await cloudinary.api.resources({
        type: "upload",
        prefix: folder,
        resource_type: type,
        max_results: 500,
      });
      for (const r of resources) {
        if (!referenced.has(r.public_id)) {
          orphans.push({ publicId: r.public_id, resourceType: type, bytes: r.bytes });
        }
      }
    } catch {
      /* nothing of this type */
    }
  }
  return orphans;
};

exports.isEnabled = () => config.media.enabled;


/**
 * Deletes an asset.
 *
 * Used when a photo is REPLACED, not just when it is removed: Cloudinary
 * bills on stored bytes, so a user who changes their avatar ten times would
 * otherwise leave ten images on the account forever.
 *
 * Deliberately forgiving. A failed delete costs a little storage; throwing
 * would fail the user's action for something they neither asked about nor
 * can fix.
 */
exports.destroyAsset = async (publicId, resourceType = "image") => {
  if (!publicId || !exports.isEnabled()) return false;
  try {
    const result = await cloudinary.uploader.destroy(publicId, {
      resource_type: resourceType,
    });
    return result?.result === "ok";
  } catch (err) {
    console.warn(`Cloudinary delete failed for ${publicId}: ${err.message}`);
    return false;
  }
};
