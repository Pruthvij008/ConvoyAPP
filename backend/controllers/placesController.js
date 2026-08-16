const catchAsync = require("../utils/catchAsync");
const AppError = require("../utils/appError");
const placesService = require("../services/places.service");

// ── Search ───────────────────────────────────────────────────────
// GET /api/v1/places/search?q=anjuna&lat=&lng=
//
// `lat`/`lng` are the searcher's own position and bias results towards
// them. Someone in Pune typing "station" wants Pune station, not the most
// globally famous one.
exports.search = catchAsync(async (req, res, next) => {
  const query = (req.query.q || "").trim();

  // Two characters is where a search stops being a wildcard. Below that
  // every provider returns noise, so we refuse rather than pass it upstream
  // and spend a request on it.
  if (query.length < 2) {
    return next(new AppError("Type at least 2 characters to search.", 400));
  }

  const lat = parseFloat(req.query.lat);
  const lng = parseFloat(req.query.lng);
  const near = Number.isFinite(lat) && Number.isFinite(lng) ? { lat, lng } : {};

  const results = await placesService.search(query, near);

  // An empty list is a legitimate answer ("no such place"), not an error.
  // The app shows "nothing found" and keeps the map usable underneath.
  res.status(200).json({
    status: "success",
    data: { results },
  });
});

// ── Reverse geocode ──────────────────────────────────────────────
// GET /api/v1/places/reverse?lat=&lng=
//
// Turns a dropped pin into a name. Without this a manually placed
// destination shows up in the trip list as a pair of decimals.
exports.reverse = catchAsync(async (req, res, next) => {
  const lat = parseFloat(req.query.lat);
  const lng = parseFloat(req.query.lng);

  if (!Number.isFinite(lat) || !Number.isFinite(lng)) {
    return next(new AppError("Valid lat and lng are required.", 400));
  }
  if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
    return next(new AppError("Coordinates are out of range.", 400));
  }

  const place = await placesService.reverse(lat, lng);

  // `null` when the provider has no name for that spot — a field, a new
  // road. The app falls back to the coordinates, which still work.
  res.status(200).json({
    status: "success",
    data: { place },
  });
});
