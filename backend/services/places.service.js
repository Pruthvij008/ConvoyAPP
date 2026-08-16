const config = require("../config/config");

/**
 * Place search and reverse geocoding.
 *
 * Typing "Anjuna Beach" has to produce a coordinate, otherwise the only way
 * to set a destination is dragging a pin around a map — which nobody will do.
 *
 * Everything here goes through OUR server rather than straight from the
 * phone, for three reasons that all matter:
 *
 *   1. The free providers require a real, identifying User-Agent and ban
 *      clients that do not send one. A phone app cannot be trusted to.
 *   2. Their usage policies are written per-application, not per-user. One
 *      server making N requests is compliant; N phones hammering them is not.
 *   3. Caching. A convoy of six phones searching "Lonavala" should cost one
 *      upstream request, not six.
 *
 * Provider is Photon (Komoot) — built for autocomplete, no key, no account,
 * no card — with Nominatim as the fallback since the two rarely fail together.
 */

/**
 * A tiny in-process cache.
 *
 * Deliberately not Redis: these results are small, identical for every user,
 * and cheap to recompute. A local map keeps the hot path free of a network
 * hop. Bounded so a long-running server cannot grow without limit.
 */
const cache = new Map();
const CACHE_MAX = 500;

const cacheGet = (key) => {
  const hit = cache.get(key);
  if (!hit) return null;
  if (Date.now() > hit.expiresAt) {
    cache.delete(key);
    return null;
  }
  return hit.value;
};

const cacheSet = (key, value) => {
  // Oldest-first eviction. Map preserves insertion order, so the first key
  // is the oldest — no timestamps to sort.
  if (cache.size >= CACHE_MAX) cache.delete(cache.keys().next().value);
  cache.set(key, { value, expiresAt: Date.now() + config.places.cacheTtlMs });
};

/**
 * Upstream calls get a hard timeout.
 *
 * Without one, a hung provider would hold the request open until the phone
 * gave up — and the user is sitting on a search box waiting to leave.
 */
const fetchJson = async (url) => {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), config.places.timeoutMs);
  try {
    const response = await fetch(url, {
      signal: controller.signal,
      headers: {
        // Required by both providers' usage policies. An anonymous or
        // missing User-Agent is grounds for being blocked outright.
        "User-Agent": config.places.userAgent,
        Accept: "application/json",
      },
    });
    if (!response.ok) return null;
    return await response.json();
  } catch (err) {
    return null;
  } finally {
    clearTimeout(timer);
  }
};

/**
 * Builds the one-line description shown under a result's name.
 *
 * "Anjuna Beach" alone is ambiguous; "Anjuna Beach · Bardez, Goa" is not.
 * Parts are filtered before joining so a missing district does not leave a
 * stray comma in the middle of the string.
 */
const describe = (props) =>
  [props.street, props.district, props.city, props.county, props.state, props.country]
    .filter(Boolean)
    // A place whose name IS its city ("Pune") would otherwise read "Pune · Pune".
    .filter((part, index, all) => all.indexOf(part) === index && part !== props.name)
    .slice(0, 3)
    .join(", ");

const fromPhoton = (json) =>
  (json?.features || [])
    .filter((f) => Array.isArray(f?.geometry?.coordinates))
    .map((f) => {
      const props = f.properties || {};
      // GeoJSON is [longitude, latitude]. Reversing these is the classic
      // silent failure — the pin lands in the ocean off West Africa.
      const [lng, lat] = f.geometry.coordinates;
      return {
        name: props.name || props.street || props.city || "Unnamed place",
        description: describe(props),
        lat,
        lng,
        kind: props.osm_value || props.type || null,
      };
    });

const fromNominatim = (json) =>
  (json || [])
    .filter((r) => r?.lat && r?.lon)
    .map((r) => {
      // Nominatim returns one long comma-joined address. The first part is
      // the place, the rest is context — the same shape Photon gives us.
      const parts = (r.display_name || "").split(",").map((p) => p.trim());
      return {
        name: parts[0] || "Unnamed place",
        description: parts.slice(1, 4).join(", "),
        lat: parseFloat(r.lat),
        lng: parseFloat(r.lon),
        kind: r.type || null,
      };
    });


/**
 * Google Places, used first when configured.
 *
 * The gap it closes is precision on NAMED BUSINESSES rather than raw
 * coverage. Searching "cafe goodluck pune" against OSM returns a road named
 * after the cafe, a different cafe of a similar name, and a spin-off branch
 * before the actual place. For a convoy agreeing where to meet, landing on
 * the right one matters more than having many.
 *
 * Text Search rather than Autocomplete: it returns coordinates in the same
 * call, where autocomplete returns predictions that then need a second
 * lookup per result to become a point on a map.
 *
 * Same arrangement as routing — our own conservative daily ceiling, and a
 * silent fall back to Photon when it is reached or anything fails. The app
 * cannot tell which provider answered.
 */
const googleBudget = { day: null, used: 0 };

const googleRemaining = () => {
  const today = new Date().toISOString().slice(0, 10);
  if (googleBudget.day !== today) {
    googleBudget.day = today;
    googleBudget.used = 0;
  }
  return config.places.googleDailyBudget - googleBudget.used;
};

exports.googleUsage = () => ({ ...googleBudget, budget: config.places.googleDailyBudget });

const fromGoogle = (json) =>
  (json?.places || [])
    .filter((p) => p?.location)
    .map((p) => {
      const name = p.displayName?.text || "Unnamed place";
      // Google's formattedAddress repeats the name at the front for a
      // business. Stripping it stops the list reading "Cafe Goodluck ·
      // Cafe Goodluck, Ferguson College Rd".
      let description = p.formattedAddress || "";
      if (description.toLowerCase().startsWith(name.toLowerCase())) {
        description = description.slice(name.length).replace(/^[,\s]+/, "");
      }
      return {
        name,
        description: description.split(",").slice(0, 3).join(",").trim(),
        lat: p.location.latitude,
        lng: p.location.longitude,
        kind: p.primaryType || null,
      };
    });

const viaGoogle = async (query, near) => {
  const body = {
    textQuery: query,
    maxResultCount: config.places.maxResults,
  };

  // Bias, not restriction: someone in Pune searching for a Goa beach should
  // still find it, just below the nearby matches.
  if (typeof near.lat === "number" && typeof near.lng === "number") {
    body.locationBias = {
      circle: {
        center: { latitude: near.lat, longitude: near.lng },
        radius: 50000,
      },
    };
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), config.places.timeoutMs);

  try {
    const response = await fetch("https://places.googleapis.com/v1/places:searchText", {
      method: "POST",
      signal: controller.signal,
      headers: {
        "Content-Type": "application/json",
        "X-Goog-Api-Key": config.places.googleKey,
        // Billed and returned by field mask — asking for less is cheaper
        // and faster. We need a name, an address and a point.
        "X-Goog-FieldMask":
          "places.displayName,places.formattedAddress,places.location,places.primaryType",
      },
      body: JSON.stringify(body),
    });

    if (!response.ok) {
      const text = await response.text().catch(() => "");
      const err = new Error(`Google Places ${response.status}: ${text.slice(0, 160)}`);
      err.quotaExhausted = response.status === 429 || response.status === 403;
      throw err;
    }

    return fromGoogle(await response.json());
  } finally {
    clearTimeout(timer);
  }
};

/**
 * Search for a place by name.
 *
 * `near` biases results towards the user rather than returning the most
 * globally famous match — someone in Pune typing "station" wants Pune
 * station, not Grand Central.
 */
exports.search = async (query, near = {}) => {
  const q = String(query || "").trim();
  if (q.length < 2) return [];

  const { lat, lng } = near;
  const cacheKey = `s:${q.toLowerCase()}:${lat ? lat.toFixed(1) : ""}:${lng ? lng.toFixed(1) : ""}`;
  const cached = cacheGet(cacheKey);
  if (cached) return cached;

  // Google first when it is configured and inside budget. Its results are
  // cached under their own key prefix so they are never mixed with OSM
  // results — the two providers have different terms about retention.
  if (config.places.googleUsable && googleRemaining() > 0) {
    try {
      const results = await viaGoogle(q, near);
      googleBudget.used += 1;
      if (results.length > 0) {
        cacheSet(`g:${cacheKey}`, results);
        return results;
      }
      // An empty Google answer is not a failure — fall through to OSM,
      // which sometimes knows small local places Google does not.
    } catch (err) {
      if (err.quotaExhausted) {
        googleBudget.used = config.places.googleDailyBudget;
        console.warn("⚠️  Google Places daily limit reached — using Photon until tomorrow");
      } else {
        console.warn(`⚠️  Google Places failed (${err.message}) — falling back to Photon`);
      }
    }
  }

  const photonUrl = new URL(`${config.places.photonUrl}/api`);
  photonUrl.searchParams.set("q", q);
  photonUrl.searchParams.set("limit", String(config.places.maxResults));
  if (typeof lat === "number" && typeof lng === "number") {
    photonUrl.searchParams.set("lat", String(lat));
    photonUrl.searchParams.set("lon", String(lng));
  }

  let results = fromPhoton(await fetchJson(photonUrl.toString()));

  if (results.length === 0) {
    const nominatimUrl = new URL(`${config.places.nominatimUrl}/search`);
    nominatimUrl.searchParams.set("q", q);
    nominatimUrl.searchParams.set("format", "json");
    nominatimUrl.searchParams.set("limit", String(config.places.maxResults));
    results = fromNominatim(await fetchJson(nominatimUrl.toString()));
  }

  // Only successful lookups are cached. Caching an empty result would
  // persist a provider outage long after it had recovered.
  if (results.length > 0) cacheSet(cacheKey, results);
  return results;
};

/**
 * Coordinate to a human-readable name.
 *
 * Used when someone drops the pin manually — "18.52034, 73.85673" is not a
 * destination anyone can recognise in a list of trips.
 */
exports.reverse = async (lat, lng) => {
  if (typeof lat !== "number" || typeof lng !== "number") return null;

  // Rounded to ~10 m for the cache key: panning the map a few metres should
  // not miss the cache, and no name changes at that resolution.
  const cacheKey = `r:${lat.toFixed(4)}:${lng.toFixed(4)}`;
  const cached = cacheGet(cacheKey);
  if (cached) return cached;

  const url = new URL(`${config.places.photonUrl}/reverse`);
  url.searchParams.set("lat", String(lat));
  url.searchParams.set("lon", String(lng));
  url.searchParams.set("limit", "1");

  let place = fromPhoton(await fetchJson(url.toString()))[0] || null;

  if (!place) {
    const nominatimUrl = new URL(`${config.places.nominatimUrl}/reverse`);
    nominatimUrl.searchParams.set("lat", String(lat));
    nominatimUrl.searchParams.set("lon", String(lng));
    nominatimUrl.searchParams.set("format", "json");
    const json = await fetchJson(nominatimUrl.toString());
    place = fromNominatim(json ? [json] : [])[0] || null;
  }

  if (place) cacheSet(cacheKey, place);
  return place;
};
