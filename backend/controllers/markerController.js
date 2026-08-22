const Marker = require("../models/markerModel");
const Vehicle = require("../models/vehicleModel");
const Trip = require("../models/tripModel");
const User = require("../models/userModel");
const catchAsync = require("../utils/catchAsync");
const AppError = require("../utils/appError");
const { toPoint, isValidLatLng } = require("../utils/geo");
const { MARKER_CATALOGUE, CATEGORIES, SEVERITIES } = require("../config/markerCatalogue");

// Durable first, broadcast second. A socket message is fire-and-forget — if
// the connection drops mid-emit it is simply gone, which is fine for a
// position superseded in 15 seconds and unacceptable for "I've broken down".
const broadcast = (req, event, payload) => {
  const io = req.app.get("io");
  if (io) io.to(`trip:${req.trip._id}`).emit(event, payload);
};

// ── The built-in catalogue ───────────────────────────────────────
// Served so the app never hardcodes a marker list that can drift from the
// server's.
exports.getCatalogue = (req, res) => {
  res.status(200).json({
    status: "success",
    data: { markers: MARKER_CATALOGUE, categories: CATEGORIES, severities: SEVERITIES },
  });
};

// ── Markers in a trip ────────────────────────────────────────────
exports.listMarkers = catchAsync(async (req, res) => {
  const filter = { tripId: req.trip._id };
  if (req.query.state) filter.state = req.query.state;
  if (req.query.kind) filter.kind = req.query.kind;

  const markers = await Marker.find(filter).sort({ startedAt: -1 }).limit(500);
  res.status(200).json({ status: "success", results: markers.length, data: { markers } });
});

exports.createMarker = catchAsync(async (req, res, next) => {
  const { markerKey, kind = "STATUS", lat, lng, note, waitingForGroup, autoDetected, media } = req.body;

  if (!markerKey) return next(new AppError("Which marker? markerKey is required.", 400));
  if (!["STATUS", "PLACE"].includes(kind)) {
    return next(new AppError('kind must be "STATUS" or "PLACE".', 400));
  }

  // Behaviour comes from the TRIP'S SNAPSHOT of the definition, never from
  // the live catalogue — that is what keeps an old trip behaving the way it
  // did on the day (plan §12.3).
  let base;
  try {
    base = Marker.fromTripMarker(req.trip, markerKey);
  } catch {
    return next(
      new AppError(`"${markerKey}" isn't in this trip's marker set. Add it first.`, 400)
    );
  }

  if (Marker.requiresNote(req.trip, markerKey) && !note?.trim()) {
    return next(new AppError("This marker needs a short note.", 400));
  }

  if (kind === "STATUS") {
    // Markers belong to VEHICLES, not people: four friends in one car
    // tapping "chai" is one stop. The vehicle comes from the caller's
    // participant record, never from the request body.
    if (!req.participant.vehicleId) {
      return next(new AppError("You're not in a vehicle, so you can't mark a stop.", 400));
    }

    // Changing your mind mid-stop ("actually we're eating, not just fuel")
    // replaces the active status rather than colliding with the partial
    // unique index.
    const existing = await Marker.findOne({
      vehicleId: req.participant.vehicleId,
      kind: "STATUS",
      state: "ACTIVE",
    });
    if (existing) {
      existing.state = "CLEARED";
      await existing.save(); // hook stamps endedAt + durationS
      broadcast(req, "marker:cleared", { markerId: existing._id, vehicleId: existing.vehicleId });
    }
  }

  // A status marker defaults to the vehicle's current position when the
  // client doesn't send one; a place marker must say where it is.
  let location;
  // isValidLatLng rather than a bare typeof: NaN is a number, and a NaN
  // pair reached the 2dsphere index as a Mongoose ValidationError rather
  // than as the clear 400 the client can actually act on. Out-of-range
  // values had no check here at all.
  if (lat !== undefined || lng !== undefined) {
    if (!isValidLatLng(lat, lng)) {
      return next(new AppError("Those coordinates aren't valid.", 400));
    }
    location = toPoint(lat, lng);
  } else if (kind === "STATUS") {
    const vehicle = await Vehicle.findById(req.participant.vehicleId);
    location = vehicle?.lastKnown?.point;
  }
  if (!location?.coordinates?.length) {
    return next(new AppError("Where? Send lat and lng.", 400));
  }

  const marker = await Marker.create({
    ...base,
    kind,
    vehicleId: kind === "STATUS" ? req.participant.vehicleId : undefined,
    createdBy: req.participant._id,
    location,
    note: note?.trim(),
    media: Array.isArray(media) ? media : [],
    autoDetected: !!autoDetected,
    // The definition's default, overridable by the driver.
    waitingForGroup:
      waitingForGroup !== undefined ? !!waitingForGroup : base.waitingForGroup,
  });

  // Denormalized onto the vehicle so the map roster renders in one query
  // instead of a marker lookup per vehicle.
  if (kind === "STATUS") {
    await Vehicle.updateOne(
      { _id: req.participant.vehicleId },
      {
        currentStatus: {
          markerId: marker._id,
          markerKey: marker.markerKey,
          label: marker.label,
          icon: marker.icon,
          since: marker.startedAt,
          waitingForGroup: marker.waitingForGroup,
        },
      }
    );
  }

  await req.trip.touchActivity();

  // severity travels with the event so the client knows how loudly to
  // announce it without re-deriving the rule.
  broadcast(req, "marker:created", {
    marker,
    by: req.participant.displayName,
    severity: marker.severity,
  });

  res.status(201).json({ status: "success", data: { marker } });
});

exports.updateMarker = catchAsync(async (req, res, next) => {
  const marker = await Marker.findOne({ _id: req.params.markerId, tripId: req.trip._id });
  if (!marker) return next(new AppError("Marker not found.", 404));

  const isAuthor = marker.createdBy.equals(req.participant._id);
  // Anyone in the same vehicle can amend its status — the driver is busy
  // driving, and a passenger correcting the reason is the normal case.
  const sameVehicle =
    marker.vehicleId && String(marker.vehicleId) === String(req.participant.vehicleId);
  if (!isAuthor && !sameVehicle && !req.participant.canManageTrip()) {
    return next(new AppError("That's not your marker.", 403));
  }

  if (req.body.note !== undefined) marker.note = req.body.note?.trim();
  if (req.body.waitingForGroup !== undefined) marker.waitingForGroup = !!req.body.waitingForGroup;
  if (Array.isArray(req.body.media)) marker.media = req.body.media;
  await marker.save();

  if (marker.kind === "STATUS" && marker.state === "ACTIVE") {
    await Vehicle.updateOne(
      { _id: marker.vehicleId },
      { "currentStatus.waitingForGroup": marker.waitingForGroup }
    );
  }

  broadcast(req, "marker:updated", { marker });
  res.status(200).json({ status: "success", data: { marker } });
});

// Resuming the drive. Separate from delete on purpose: a cleared stop is
// history worth keeping, and the recap is built from it.
exports.clearMarker = catchAsync(async (req, res, next) => {
  const marker = await Marker.findOne({
    _id: req.params.markerId,
    tripId: req.trip._id,
    kind: "STATUS",
  });
  if (!marker) return next(new AppError("Status marker not found.", 404));
  if (marker.state === "CLEARED") {
    return res.status(200).json({ status: "success", data: { marker } });
  }

  const sameVehicle = String(marker.vehicleId) === String(req.participant.vehicleId);
  if (!sameVehicle && !req.participant.canManageTrip()) {
    return next(new AppError("That's not your vehicle's stop.", 403));
  }

  marker.state = "CLEARED";
  await marker.save();

  await Vehicle.updateOne({ _id: marker.vehicleId }, { $unset: { currentStatus: 1 } });
  await req.trip.touchActivity();

  broadcast(req, "marker:cleared", {
    markerId: marker._id,
    vehicleId: marker.vehicleId,
    durationS: marker.durationS,
  });

  res.status(200).json({ status: "success", data: { marker } });
});

exports.deleteMarker = catchAsync(async (req, res, next) => {
  const marker = await Marker.findOne({ _id: req.params.markerId, tripId: req.trip._id });
  if (!marker) return next(new AppError("Marker not found.", 404));

  if (marker.kind === "STATUS") {
    return next(new AppError("Clear a stop rather than deleting it — it's part of the trip's history.", 400));
  }
  if (!marker.createdBy.equals(req.participant._id) && !req.participant.canManageTrip()) {
    return next(new AppError("That's not your marker.", 403));
  }

  await marker.deleteOne();
  broadcast(req, "marker:deleted", { markerId: marker._id });
  res.status(204).json({ status: "success", data: null });
});

// ── Curating the trip's marker set ───────────────────────────────
exports.addToMarkerSet = catchAsync(async (req, res, next) => {
  const { key, label, icon, color, category, severity, defaultWaitingForGroup, requiresNote, iconMedia } = req.body;

  if (!key || !label) return next(new AppError("A marker needs a key and a label.", 400));
  if (req.trip.markerSet.some((m) => m.key === key)) {
    return next(new AppError(`"${key}" is already in this trip's set.`, 409));
  }
  if (req.trip.markerSet.length >= 30) {
    return next(new AppError("This trip already has 30 markers. Remove one first.", 409));
  }

  req.trip.markerSet.push({
    key, label, icon, color, iconMedia,
    category: category || "ADMIN",
    severity: severity || "INFO",
    defaultWaitingForGroup: !!defaultWaitingForGroup,
    requiresNote: !!requiresNote,
    isCustom: true,
    addedBy: req.user._id,
    order: req.trip.markerSet.length,
  });
  await req.trip.save();

  broadcast(req, "markerset:changed", { markerSet: req.trip.markerSet });
  res.status(201).json({ status: "success", data: { markerSet: req.trip.markerSet } });
});

exports.updateMarkerSetEntry = catchAsync(async (req, res, next) => {
  const entry = req.trip.markerSet.find((m) => m.key === req.params.key);
  if (!entry) return next(new AppError("That marker isn't in this trip's set.", 404));

  ["label", "icon", "color", "category", "severity", "order"].forEach((f) => {
    if (req.body[f] !== undefined) entry[f] = req.body[f];
  });
  if (req.body.isFavourite !== undefined) entry.isFavourite = !!req.body.isFavourite;
  if (req.body.defaultWaitingForGroup !== undefined) entry.defaultWaitingForGroup = !!req.body.defaultWaitingForGroup;
  if (req.body.requiresNote !== undefined) entry.requiresNote = !!req.body.requiresNote;

  // The pre-save hook enforces the 4-favourite cap here.
  await req.trip.save();

  broadcast(req, "markerset:changed", { markerSet: req.trip.markerSet });
  res.status(200).json({ status: "success", data: { markerSet: req.trip.markerSet } });
});

exports.removeFromMarkerSet = catchAsync(async (req, res, next) => {
  const before = req.trip.markerSet.length;
  req.trip.markerSet = req.trip.markerSet.filter((m) => m.key !== req.params.key);
  if (req.trip.markerSet.length === before) {
    return next(new AppError("That marker isn't in this trip's set.", 404));
  }
  await req.trip.save();

  // Markers already dropped keep their own copy of the definition, so
  // removing it here changes nothing about the trip's history.
  broadcast(req, "markerset:changed", { markerSet: req.trip.markerSet });
  res.status(200).json({ status: "success", data: { markerSet: req.trip.markerSet } });
});

// ── Personal marker library ──────────────────────────────────────
exports.getMyMarkers = catchAsync(async (req, res) => {
  const user = await User.findById(req.user.id).select("customMarkers");
  res.status(200).json({ status: "success", data: { customMarkers: user.customMarkers } });
});

exports.saveCustomMarker = catchAsync(async (req, res, next) => {
  const { key, label, icon, color, category, severity, defaultWaitingForGroup, requiresNote, iconMedia, copiedFromTripId } = req.body;
  if (!key || !label) return next(new AppError("A marker needs a key and a label.", 400));

  const user = await User.findById(req.user.id);
  if (user.customMarkers.length >= 40) {
    return next(new AppError("Your marker library is full (40). Delete one first.", 409));
  }

  const existing = user.customMarkers.find((m) => m.key === key);
  const payload = {
    key, label, icon, color, iconMedia,
    category: category || "ADMIN",
    severity: severity || "INFO",
    defaultWaitingForGroup: !!defaultWaitingForGroup,
    requiresNote: !!requiresNote,
    copiedFromTripId,
  };

  if (existing) Object.assign(existing, payload);
  else user.customMarkers.push(payload);

  await user.save({ validateBeforeSave: false });
  res.status(existing ? 200 : 201).json({
    status: "success",
    data: { customMarkers: user.customMarkers },
  });
});

exports.deleteCustomMarker = catchAsync(async (req, res, next) => {
  const user = await User.findById(req.user.id);
  const before = user.customMarkers.length;
  user.customMarkers = user.customMarkers.filter((m) => m.key !== req.params.key);
  if (user.customMarkers.length === before) {
    return next(new AppError("No such marker in your library.", 404));
  }
  await user.save({ validateBeforeSave: false });

  // Safe by design: trips snapshot their markers, so nothing that already
  // used this breaks.
  res.status(200).json({ status: "success", data: { customMarkers: user.customMarkers } });
});
