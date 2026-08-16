// ─────────────────────────────────────────────────────────────
// GeoJSON helpers.
//
// MongoDB stores points as [longitude, latitude] — longitude FIRST.
// This is the opposite order to how every map UI, GPS reading and human
// says it ("lat, lng"), and getting it backwards fails silently: the
// point simply lands somewhere in the ocean and distance queries return
// nothing. Every conversion between the two orders goes through the
// helpers here so the mistake can only be made in one place.
// ─────────────────────────────────────────────────────────────

// Reusable GeoJSON Point subdocument. `required` is caller-controlled
// because some points are mandatory (a marker's location) and some are
// not (a trip's destination before the host picks one).
const pointSchema = (required = false) => ({
  // No `default: "Point"`. A default here materialises `{ type: "Point" }`
  // on documents that have no location yet, and a 2dsphere index rejects
  // that with "Can't extract geo keys" — the whole insert fails. With no
  // default, an unset point stores nothing and the index simply skips it.
  type: {
    type: String,
    enum: ["Point"],
    required: required ? true : false,
  },
  coordinates: {
    type: [Number], // [lng, lat]
    required,
    // Likewise: without this, Mongoose initialises the path to [] and the
    // document is written with an empty coordinates array.
    default: undefined,
    validate: {
      // An unset optional point arrives as [] (Mongoose initialises [Number]
      // to an empty array), and [] is truthy — so the empty case must be
      // checked by length, not by falsiness.
      validator: (v) =>
        !v ||
        v.length === 0 ||
        (v.length === 2 &&
          v[0] >= -180 &&
          v[0] <= 180 &&
          v[1] >= -90 &&
          v[1] <= 90),
      // If someone passes [lat, lng] for an Indian coordinate the latitude
      // (~19) lands in the longitude slot and stays inside valid ranges, so
      // this catches only gross errors. The real defence is using toPoint().
      message:
        "Coordinates must be [longitude, latitude] within valid ranges.",
    },
  },
});

// Build a GeoJSON point from the lat/lng order humans and GPS APIs use.
const toPoint = (lat, lng) => ({
  type: "Point",
  coordinates: [lng, lat],
});

// Read a GeoJSON point back out in human order.
const toLatLng = (point) => {
  if (!point || !Array.isArray(point.coordinates)) return null;
  const [lng, lat] = point.coordinates;
  return { lat, lng };
};

// Great-circle distance in metres. Used for gap alerts, stop detection and
// the downsampler, none of which need road distance — only "how far apart".
const distanceMeters = (a, b) => {
  if (!a || !b) return null;
  const R = 6371000;
  const toRad = (d) => (d * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const s =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(s));
};

module.exports = { pointSchema, toPoint, toLatLng, distanceMeters };
