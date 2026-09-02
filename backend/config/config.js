// ─────────────────────────────────────────────────────────────
// Central config. Everything the app reads from the environment
// funnels through here, so the rest of the codebase never touches
// process.env directly. To reuse this backend in a new project you
// only change values in config.env — never this file.
// ─────────────────────────────────────────────────────────────

// Coerce common truthy strings ("true", "1", "yes") to a real boolean.
const toBool = (val, fallback = false) => {
  if (val === undefined || val === null || val === "") return fallback;
  return ["true", "1", "yes", "on"].includes(String(val).toLowerCase());
};

module.exports = {
  env: process.env.NODE_ENV || "development",
  port: parseInt(process.env.PORT, 10) || 3000,
  frontendUrl: process.env.FRONTEND_URL || "http://localhost:5173",

  db: {
    // DATABASE may contain the literal <db_password> token, replaced
    // at startup with DATABASE_PASSWORD (kept for parity with hosted URIs).
    uri: process.env.DATABASE || "",
    password: process.env.DATABASE_PASSWORD || "",
  },

  jwt: {
    secret: process.env.JWT_SECRET,
    expiresIn: process.env.JWT_EXPIRESIN || "90d",
    cookieExpiresInDays: parseInt(process.env.JWT_COOKIE_EXPIRES_IN, 10) || 90,
  },

  otp: {
    // Minutes an emailed OTP stays valid (verification + password reset).
    expiresInMinutes: parseInt(process.env.OTP_EXPIRES_IN_MIN, 10) || 10,
    length: 6,
  },

  mail: {
    // Gmail transactional email (Nodemailer). Create an App Password:
    // https://myaccount.google.com/apppasswords
    gmailAddress: process.env.GMAIL_ADDRESS,
    gmailAppPassword: process.env.GMAIL_APP_PASSWORD,
    fromName: process.env.MAIL_FROM_NAME || "Auth Starter",
  },

  google: {
    // The single switch the user asked for:
    //   true  → Google Identity Services sign-in is enabled (ID token
    //           verified on the backend with google-auth-library).
    //   false → classic email/phone + password only (Google button hidden).
    enabled: toBool(process.env.GOOGLE_AUTH_ENABLED, false),
    clientId: process.env.GOOGLE_CLIENT_ID || "",
  },

  // ── Convoy-specific ────────────────────────────────────────────
  redis: {
    // Live vehicle positions live here, never in Mongo (see plan §12.4).
    url: process.env.REDIS_URL || "redis://127.0.0.1:6379",
    // How long a trip's live position hash survives without a write.
    liveTtlSec: parseInt(process.env.REDIS_LIVE_TTL_SEC, 10) || 6 * 60 * 60,
  },

  trip: {
    // Base of the shareable join link. The host copies this and drops it in
    // WhatsApp; tapping it opens the app straight into the join screen.
    // In production this is an https:// link with Android App Links so it
    // opens the app rather than a browser.
    // PUBLIC_BASE_URL is this API's own public origin, and it is the right
    // default because the API now SERVES the join page at /j. The old
    // default pointed at a front-end dev server, so every link a host
    // shared in production read "http://localhost:5173/j/..." and was dead
    // on arrival for everyone who received it.
    joinLinkBase:
      process.env.TRIP_JOIN_LINK_BASE ||
      `${process.env.PUBLIC_BASE_URL || process.env.FRONTEND_URL || "http://localhost:3000"}/j`,

    // Join code: uppercase, ambiguous characters (0/O, 1/I/L) removed so a
    // code read out over a phone call is unambiguous.
    codeAlphabet: "ACDEFGHJKMNPQRSTUVWXYZ23456789",
    codeLength: parseInt(process.env.TRIP_CODE_LENGTH, 10) || 6,
    // Join links expire so a forwarded WhatsApp message stops working.
    joinTokenExpiresInHours:
      parseInt(process.env.TRIP_JOIN_TOKEN_EXPIRES_IN_HOURS, 10) || 24,
    // A trip with no movement and no app activity for this long is
    // auto-ended by the sweeper. This is the backstop against a forgotten
    // trip broadcasting location for days (plan §2, §4.2).
    abandonAfterHours: parseInt(process.env.TRIP_ABANDON_AFTER_HOURS, 10) || 12,
    maxVehiclesPerTrip: parseInt(process.env.TRIP_MAX_VEHICLES, 10) || 25,
    // The abandon condition takes hours to develop, so this runs far less
    // often than the alert sweeper.
    sweepIntervalMin: parseInt(process.env.TRIP_SWEEP_INTERVAL_MIN, 10) || 15,
  },

  media: {
    // Cloudinary. Uploads go straight from the phone to Cloudinary — the
    // bytes never pass through this server, which matters on a patchy
    // highway connection. We only ever handle the signature going out and
    // the publicId coming back.
    // Root folder in the Cloudinary account. Everything Convoy uploads
    // lives beneath it, which keeps this app's assets separate from
    // anything else in the account and makes a full cleanup one delete.
    folder: process.env.CLOUDINARY_FOLDER || "convoy-app",
    cloudName: process.env.CLOUDINARY_CLOUD_NAME || "",
    apiKey: process.env.CLOUDINARY_API_KEY || "",
    apiSecret: process.env.CLOUDINARY_API_SECRET || "",
    get enabled() {
      return !!(this.cloudName && this.apiKey && this.apiSecret);
    },
    // Signatures are short-lived so a leaked one cannot be reused later.
    signatureTtlSec: parseInt(process.env.MEDIA_SIGNATURE_TTL_SEC, 10) || 600,
    maxBytes: parseInt(process.env.MEDIA_MAX_BYTES, 10) || 15 * 1024 * 1024,
    // Photos live as long as the trip — they are the recap's whole point.
    // Voice clips are chatter and expire fast.
    voiceRetentionDays: parseInt(process.env.VOICE_RETENTION_DAYS, 10) || 7,
  },

  routing: {
    // Google's Routes API is the only free option that knows about live
    // traffic, which is the one thing OSRM genuinely cannot do. It is used
    // through the DEMO key — no billing account, no card, and a daily limit
    // that pauses rather than charges.
    //
    // Google is explicit that the demo key is for prototyping and "not
    // designed for production use", so this is a development-time quality
    // upgrade, not something to ship. OSRM stays the default and the
    // fallback, and the app cannot tell which one answered.
    googleKey: process.env.GOOGLE_ROUTES_KEY || "",
    googleEnabled: process.env.GOOGLE_ROUTES_ENABLED === "true",
    // Our own ceiling, deliberately below Google's undisclosed daily limit.
    // Hitting ours degrades cleanly on our terms; hitting theirs means
    // discovering the limit by being cut off mid-trip.
    dailyBudget: parseInt(process.env.GOOGLE_ROUTES_DAILY_BUDGET, 10) || 200,
    osrmUrl: process.env.OSRM_URL || "https://router.project-osrm.org",
    timeoutMs: parseInt(process.env.ROUTING_TIMEOUT_MS, 10) || 8000,
    get googleUsable() {
      return !!(this.googleEnabled && this.googleKey);
    },
  },

  places: {
    // Photon and Nominatim are both free, keyless and card-less — the whole
    // reason the map stack avoids Google. Overridable by env so a
    // self-hosted instance can be pointed at later without a code change,
    // which is what the public instances' usage policies will eventually
    // require at real volume.
    // Google Places closes a real gap on NAMED BUSINESSES — OSM will find
    // a road named after a cafe before the cafe itself. Same demo key and
    // the same rules as routing: development only, daily limit, automatic
    // fall back to Photon, and the app never learns which answered.
    googleKey: process.env.GOOGLE_ROUTES_KEY || "",
    googleEnabled: process.env.GOOGLE_PLACES_ENABLED === "true",
    googleDailyBudget: parseInt(process.env.GOOGLE_PLACES_DAILY_BUDGET, 10) || 400,
    get googleUsable() {
      return !!(this.googleEnabled && this.googleKey);
    },

    photonUrl: process.env.PHOTON_URL || "https://photon.komoot.io",
    nominatimUrl: process.env.NOMINATIM_URL || "https://nominatim.openstreetmap.org",
    // Both providers' policies require an identifying User-Agent naming the
    // application and a contact route. Sending a generic one gets blocked.
    userAgent: process.env.PLACES_USER_AGENT || "ConvoyApp/1.0 (group ride tracker)",
    maxResults: parseInt(process.env.PLACES_MAX_RESULTS, 10) || 8,
    // A search box fires a request per pause in typing, so the timeout has
    // to be shorter than a user's patience, not merely shorter than a TCP
    // timeout.
    timeoutMs: parseInt(process.env.PLACES_TIMEOUT_MS, 10) || 6000,
    // Place names do not move. A long TTL is safe and keeps us well inside
    // the free providers' rate limits.
    cacheTtlMs: parseInt(process.env.PLACES_CACHE_TTL_MS, 10) || 24 * 60 * 60 * 1000,
  },

  alerts: {
    // How often the whole convoy is re-evaluated. Fast enough that a real
    // problem surfaces within half a minute, slow enough that it costs
    // almost nothing — each tick reads positions from Redis, not Mongo.
    sweepIntervalSec: parseInt(process.env.ALERT_SWEEP_INTERVAL_SEC, 10) || 20,
    // Public SOS/live-share links expire on their own, so a link sent to
    // family cannot outlive the trip it was shared for.
    shareLinkTtlHours: parseInt(process.env.SHARE_LINK_TTL_HOURS, 10) || 24,
  },

  location: {
    // A vehicle's dot is drawn solid below `staleAfterSec`, faded between
    // stale and lost, and greyed past `lostAfterSec`. The client mirrors
    // these numbers so server and app agree on what "live" means.
    staleAfterSec: parseInt(process.env.LOCATION_STALE_AFTER_SEC, 10) || 30,
    lostAfterSec: parseInt(process.env.LOCATION_LOST_AFTER_SEC, 10) || 180,
    // Downsampling thresholds — a ping is persisted to Track history only
    // when it clears one of these. Everything else stays in Redis only.
    historyMinDistanceM:
      parseInt(process.env.LOCATION_HISTORY_MIN_DISTANCE_M, 10) || 50,
    historyMinIntervalSec:
      parseInt(process.env.LOCATION_HISTORY_MIN_INTERVAL_SEC, 10) || 30,
    historyMinHeadingDeltaDeg:
      parseInt(process.env.LOCATION_HISTORY_MIN_HEADING_DELTA_DEG, 10) || 30,
    // Track bucket closes at whichever comes first (plan §11.9).
    trackBucketMaxPoints:
      parseInt(process.env.TRACK_BUCKET_MAX_POINTS, 10) || 120,
    trackBucketMaxMinutes:
      parseInt(process.env.TRACK_BUCKET_MAX_MINUTES, 10) || 10,
    // Raw breadcrumb retention. Downsampled trails are deleted after this;
    // trip recap stats survive because they are aggregated at trip end.
    trackRetentionDays: parseInt(process.env.TRACK_RETENTION_DAYS, 10) || 90,
  },
};
