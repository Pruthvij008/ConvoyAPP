const { lineString, point } = require("@turf/helpers");
const nearestPointOnLine = require("@turf/nearest-point-on-line").default;
const { distanceMeters } = require("./geo");

// ─────────────────────────────────────────────────────────────
// Route maths — projecting a vehicle onto the trip's planned route.
//
// This exists because straight-line distance is WRONG on exactly the roads
// where gap alerts matter most. Two cars 2 km apart as the crow flies can
// be 15 km apart on a ghat road full of hairpins. Comparing how far each
// has travelled ALONG the route gives the honest answer.
//
// The route is stored as GeoJSON coordinates ([lng, lat] pairs) rather than
// an encoded polyline so no decoding library is needed here.
// ─────────────────────────────────────────────────────────────

// A single turf call yields both numbers we need: how far along the route
// the nearest point is, and how far off the route the vehicle is.
//   { progressM, offRouteM }  — or null when there is no usable route.
exports.projectOntoRoute = (routeCoords, lat, lng) => {
  if (!Array.isArray(routeCoords) || routeCoords.length < 2) return null;
  try {
    const snapped = nearestPointOnLine(
      lineString(routeCoords),
      point([lng, lat]),
      { units: "meters" }
    );
    return {
      progressM: snapped.properties.location,
      offRouteM: snapped.properties.dist,
    };
  } catch {
    return null;
  }
};

// Total route length, so "progress" can be reported as a percentage.
exports.routeLengthM = (routeCoords) => {
  if (!Array.isArray(routeCoords) || routeCoords.length < 2) return 0;
  let total = 0;
  for (let i = 1; i < routeCoords.length; i += 1) {
    total += distanceMeters(
      { lat: routeCoords[i - 1][1], lng: routeCoords[i - 1][0] },
      { lat: routeCoords[i][1], lng: routeCoords[i][0] }
    );
  }
  return total;
};

// How far behind `vehicle` is relative to `reference`.
//
// Returns { distanceM, method } where method is "route" when both vehicles
// projected cleanly, and "straight-line" when the trip has no route or a
// vehicle sits too far off it to project meaningfully. The method travels
// with the alert so the UI can be honest about which number it is showing.
exports.gapBetween = (routeCoords, reference, vehicle) => {
  const refProj = exports.projectOntoRoute(routeCoords, reference.lat, reference.lng);
  const vehProj = exports.projectOntoRoute(routeCoords, vehicle.lat, vehicle.lng);

  // A vehicle 5 km off the route cannot be meaningfully placed on it — its
  // projection would be an arbitrary point. Fall back rather than lie.
  const USABLE_OFFSET_M = 3000;
  if (
    refProj &&
    vehProj &&
    refProj.offRouteM < USABLE_OFFSET_M &&
    vehProj.offRouteM < USABLE_OFFSET_M
  ) {
    return {
      distanceM: Math.max(0, refProj.progressM - vehProj.progressM),
      method: "route",
    };
  }

  return {
    distanceM: distanceMeters(reference, vehicle),
    method: "straight-line",
  };
};

// Who the convoy is measured against, with a chain of fallbacks so this
// always answers something sensible:
//   1. the designated LEAD vehicle
//   2. otherwise whichever vehicle is furthest along the route
//   3. otherwise (no route) the vehicle furthest from the group's centre
//      in the direction of travel — approximated by the first mover
exports.pickReferenceVehicle = (vehicles, routeCoords, leadVehicleId) => {
  const live = vehicles.filter((v) => v.position);
  if (!live.length) return null;

  if (leadVehicleId) {
    const lead = live.find((v) => String(v.vehicleId) === String(leadVehicleId));
    if (lead) return { vehicle: lead, basis: "lead" };
  }

  if (Array.isArray(routeCoords) && routeCoords.length >= 2) {
    let best = null;
    let bestProgress = -1;
    for (const v of live) {
      const proj = exports.projectOntoRoute(routeCoords, v.position.lat, v.position.lng);
      if (proj && proj.progressM > bestProgress) {
        bestProgress = proj.progressM;
        best = v;
      }
    }
    if (best) return { vehicle: best, basis: "front-most" };
  }

  // No route and no lead: the fastest-moving vehicle is the best available
  // proxy for "the one setting the pace".
  const fastest = live.reduce((a, b) =>
    (b.position.speedKmh || 0) > (a.position.speedKmh || 0) ? b : a
  );
  return { vehicle: fastest, basis: "fastest" };
};
