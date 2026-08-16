const config = require("../config/config");

/**
 * Route geometry for a trip.
 *
 * Called ONCE per trip, server-side, cached on the Trip and broadcast to
 * every client (plan §4.7). N phones must never mean N routing calls — that
 * is the single most common way an app like this becomes expensive.
 *
 * Two providers behind one function:
 *
 *   Google Routes  — knows about live traffic, which OSRM does not and
 *                    cannot. Used via the demo key: no billing account, and
 *                    a daily limit that PAUSES rather than charges.
 *   OSRM           — free, unlimited, no key. The default and the fallback.
 *
 * The caller never learns which one answered, beyond a `provider` field kept
 * for diagnostics. That is the point: routing quality can change without a
 * single line of the app changing.
 */

/**
 * Daily budget tracking.
 *
 * Google does not publish the demo key's exact daily limit, so we keep our
 * own conservative count and stop early. Discovering their limit by being
 * cut off halfway through someone's trip is not an acceptable way to find
 * out where it is.
 *
 * In process memory rather than Redis on purpose: a restart resetting the
 * count is harmless (the budget is already far below the real ceiling), and
 * routing must not fail just because Redis is briefly unavailable.
 */
const budget = { day: null, used: 0 };

const today = () => new Date().toISOString().slice(0, 10);

const budgetRemaining = () => {
  if (budget.day !== today()) {
    budget.day = today();
    budget.used = 0;
  }
  return config.routing.dailyBudget - budget.used;
};

exports.usage = () => ({
  day: budget.day,
  used: budget.used,
  budget: config.routing.dailyBudget,
  googleUsable: config.routing.googleUsable,
});

const fetchWithTimeout = async (url, options = {}) => {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), config.routing.timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
};

/**
 * Decodes Google's encoded polyline into [lng, lat] pairs.
 *
 * Written out rather than pulled from a package because it is twenty lines
 * and the alternative is a dependency in the hot path of every trip start.
 *
 * Note the output order: [lng, lat], matching GeoJSON and the rest of this
 * codebase. Google's algorithm produces lat first, so the swap happens here,
 * once, rather than being something every caller has to remember.
 */
const decodePolyline = (encoded) => {
  const points = [];
  let index = 0;
  let lat = 0;
  let lng = 0;

  while (index < encoded.length) {
    let shift = 0;
    let result = 0;
    let byte;

    do {
      byte = encoded.charCodeAt(index++) - 63;
      result |= (byte & 0x1f) << shift;
      shift += 5;
    } while (byte >= 0x20);
    lat += result & 1 ? ~(result >> 1) : result >> 1;

    shift = 0;
    result = 0;
    do {
      byte = encoded.charCodeAt(index++) - 63;
      result |= (byte & 0x1f) << shift;
      shift += 5;
    } while (byte >= 0x20);
    lng += result & 1 ? ~(result >> 1) : result >> 1;

    points.push([lng / 1e5, lat / 1e5]);
  }

  return points;
};

/**
 * A route long enough to cross a state is thousands of points, and no phone
 * needs that to draw a line a few hundred pixels wide. Every Nth point is
 * kept, with the last always included so the line actually reaches the
 * destination rather than stopping just short of it.
 */
const simplify = (points, maxPoints = 800) => {
  if (points.length <= maxPoints) return points;
  const step = Math.ceil(points.length / maxPoints);
  const out = points.filter((_, i) => i % step === 0);
  const last = points[points.length - 1];
  if (out[out.length - 1] !== last) out.push(last);
  return out;
};

// ── Google Routes ────────────────────────────────────────────────
const viaGoogle = async (origin, destination) => {
  const response = await fetchWithTimeout(
    "https://routes.googleapis.com/directions/v2:computeRoutes",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Goog-Api-Key": config.routing.googleKey,
        // Routes API bills and responds by field mask — asking for less is
        // both cheaper and faster. We need the shape, the distance and the
        // duration, and nothing else.
        "X-Goog-FieldMask":
          "routes.polyline.encodedPolyline,routes.distanceMeters,routes.duration",
      },
      body: JSON.stringify({
        origin: { location: { latLng: { latitude: origin.lat, longitude: origin.lng } } },
        destination: {
          location: { latLng: { latitude: destination.lat, longitude: destination.lng } },
        },
        travelMode: "DRIVE",
        // The whole reason Google is here rather than OSRM.
        routingPreference: "TRAFFIC_AWARE",
        // HIGH_QUALITY, not OVERVIEW. Overview returns a deliberately
        // sparse polyline meant for a zoomed-out preview — at driving zoom
        // it renders as straight lines cutting across streets, because the
        // points simply are not there. This is the difference between a
        // line that follows the road and a line that merely ends up in the
        // right place.
        polylineQuality: "HIGH_QUALITY",
      }),
    }
  );

  if (!response.ok) {
    const body = await response.text().catch(() => "");
    const error = new Error(`Google Routes ${response.status}: ${body.slice(0, 200)}`);
    // 429 is the daily limit pausing us — expected, not a malfunction, and
    // the caller treats it differently from a genuine error.
    error.quotaExhausted = response.status === 429 || response.status === 403;
    throw error;
  }

  const json = await response.json();
  const route = json.routes && json.routes[0];
  if (!route || !route.polyline?.encodedPolyline) {
    throw new Error("Google Routes returned no route");
  }

  return {
    coordinates: simplify(decodePolyline(route.polyline.encodedPolyline)),
    distanceM: route.distanceMeters || 0,
    // Duration arrives as a string like "13085s".
    durationS: parseInt(String(route.duration || "0").replace("s", ""), 10) || 0,
    provider: "google",
    trafficAware: true,
  };
};

// ── OSRM ─────────────────────────────────────────────────────────
const viaOsrm = async (origin, destination) => {
  // OSRM takes lng,lat — the opposite of how humans say it, and the reason
  // coordinate order is called out everywhere in this codebase.
  const coords = `${origin.lng},${origin.lat};${destination.lng},${destination.lat}`;
  const url =
    `${config.routing.osrmUrl}/route/v1/driving/${coords}` +
    "?overview=full&geometries=geojson";

  const response = await fetchWithTimeout(url);
  if (!response.ok) throw new Error(`OSRM ${response.status}`);

  const json = await response.json();
  const route = json.routes && json.routes[0];
  if (!route) throw new Error("OSRM returned no route");

  return {
    coordinates: simplify(route.geometry.coordinates),
    distanceM: Math.round(route.distance || 0),
    durationS: Math.round(route.duration || 0),
    provider: "osrm",
    trafficAware: false,
  };
};

/**
 * The one function the rest of the app calls.
 *
 * Google first when it is configured and inside budget, OSRM otherwise or
 * whenever Google fails for any reason. A routing failure must never stop a
 * trip from starting — the convoy still works with no line drawn, so the
 * last resort is `null` rather than a thrown error.
 */
exports.getRoute = async (origin, destination) => {
  if (!origin || !destination) return null;

  const canUseGoogle = config.routing.googleUsable && budgetRemaining() > 0;

  if (canUseGoogle) {
    try {
      const route = await viaGoogle(origin, destination);
      budget.used += 1;
      return route;
    } catch (err) {
      if (err.quotaExhausted) {
        // Burn the rest of the budget so we stop asking for the day
        // instead of retrying into a wall on every trip.
        budget.used = config.routing.dailyBudget;
        console.warn("⚠️  Google Routes daily limit reached — using OSRM until tomorrow");
      } else {
        console.warn(`⚠️  Google Routes failed (${err.message}) — falling back to OSRM`);
      }
    }
  }

  try {
    return await viaOsrm(origin, destination);
  } catch (err) {
    // No route at all. The trip is still perfectly usable: positions,
    // stops, alerts and chat do not depend on a drawn line.
    console.warn(`⚠️  Routing unavailable: ${err.message}`);
    return null;
  }
};
