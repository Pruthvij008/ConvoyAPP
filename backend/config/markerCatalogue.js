// ─────────────────────────────────────────────────────────────
// The built-in marker catalogue.
//
// Every marker — built-in or invented by a member — carries the same
// behaviour fields. That is the whole point: without them, "Breakdown
// escalates to a loud alert and defaults to wait-for-me" would be an `if`
// statement listing hardcoded keys, and a member's custom "Accident"
// marker would silently look urgent while behaving like a coffee break.
//
// Behaviour lives on the definition, and the definition is copied into
// Trip.markerSet and again onto each Marker document, so a trip keeps
// behaving correctly even after a definition is edited or deleted.
// ─────────────────────────────────────────────────────────────

const CATEGORIES = ["FUEL_FOOD", "REST", "TROUBLE", "SIGHTS", "ADMIN"];

// severity drives notification loudness:
//   INFO     — silent, appears on the map
//   WARN     — chime, banner
//   CRITICAL — full-screen alert, overrides silent mode, raises an Alert doc
const SEVERITIES = ["INFO", "WARN", "CRITICAL"];

const MARKER_CATALOGUE = [
  // ── Fuel & food ────────────────────────────────────────────
  { key: "fuel",       label: "Fuel",         icon: "⛽", color: "#B45309", category: "FUEL_FOOD", severity: "INFO", defaultWaitingForGroup: false, requiresNote: false, isFavourite: true  },
  { key: "food",       label: "Food",         icon: "🍽️", color: "#B45309", category: "FUEL_FOOD", severity: "INFO", defaultWaitingForGroup: true,  requiresNote: false, isFavourite: true  },
  { key: "chai",       label: "Chai / Break", icon: "☕", color: "#92400E", category: "FUEL_FOOD", severity: "INFO", defaultWaitingForGroup: true,  requiresNote: false, isFavourite: true  },

  // ── Rest ───────────────────────────────────────────────────
  { key: "toilet",     label: "Restroom",     icon: "🚻", color: "#0F766E", category: "REST",      severity: "INFO", defaultWaitingForGroup: false, requiresNote: false, isFavourite: true  },
  { key: "rest",       label: "Rest / Sleep", icon: "🛏️", color: "#0F766E", category: "REST",      severity: "INFO", defaultWaitingForGroup: true,  requiresNote: false, isFavourite: false },
  { key: "parking",    label: "Parking",      icon: "🅿️", color: "#0F766E", category: "REST",      severity: "INFO", defaultWaitingForGroup: false, requiresNote: false, isFavourite: false },

  // ── Trouble — the ones that must interrupt people ──────────
  // waitingForGroup defaults true: nobody should be left behind broken down.
  { key: "breakdown",  label: "Breakdown",    icon: "🔧", color: "#B91C1C", category: "TROUBLE",   severity: "CRITICAL", defaultWaitingForGroup: true,  requiresNote: false, isFavourite: false },
  { key: "puncture",   label: "Puncture",     icon: "🛞", color: "#B91C1C", category: "TROUBLE",   severity: "CRITICAL", defaultWaitingForGroup: true,  requiresNote: false, isFavourite: false },
  { key: "medical",    label: "Medical",      icon: "🩹", color: "#991B1B", category: "TROUBLE",   severity: "CRITICAL", defaultWaitingForGroup: true,  requiresNote: true,  isFavourite: false },
  { key: "accident",   label: "Accident",     icon: "⚠️", color: "#991B1B", category: "TROUBLE",   severity: "CRITICAL", defaultWaitingForGroup: true,  requiresNote: true,  isFavourite: false },
  { key: "traffic",    label: "Traffic",      icon: "🚧", color: "#C2410C", category: "TROUBLE",   severity: "WARN",     defaultWaitingForGroup: false, requiresNote: false, isFavourite: false },
  { key: "police",     label: "Police check", icon: "👮", color: "#C2410C", category: "TROUBLE",   severity: "WARN",     defaultWaitingForGroup: false, requiresNote: false, isFavourite: false },

  // ── Sights ─────────────────────────────────────────────────
  { key: "photo",      label: "Photo stop",   icon: "📸", color: "#7C3AED", category: "SIGHTS",    severity: "INFO", defaultWaitingForGroup: true,  requiresNote: false, isFavourite: false },
  { key: "viewpoint",  label: "Viewpoint",    icon: "🏞️", color: "#7C3AED", category: "SIGHTS",    severity: "INFO", defaultWaitingForGroup: true,  requiresNote: false, isFavourite: false },

  // ── Admin ──────────────────────────────────────────────────
  { key: "toll",       label: "Toll",         icon: "💰", color: "#475569", category: "ADMIN",     severity: "INFO", defaultWaitingForGroup: false, requiresNote: false, isFavourite: false },
  { key: "shopping",   label: "Shopping",     icon: "🛒", color: "#475569", category: "ADMIN",     severity: "INFO", defaultWaitingForGroup: true,  requiresNote: false, isFavourite: false },
  // "Other" demands a word — an unexplained stop is worse than no marker.
  { key: "other",      label: "Other",        icon: "❓", color: "#475569", category: "ADMIN",     severity: "INFO", defaultWaitingForGroup: false, requiresNote: true,  isFavourite: false },
];

// Sensible starting set for a new trip: the six a driver reaches for most,
// plus the trouble markers, which must always be available even though
// nobody expects to need them.
const DEFAULT_TRIP_MARKER_KEYS = [
  "fuel", "toilet", "chai", "food",
  "breakdown", "puncture", "traffic", "other",
];

const byKey = new Map(MARKER_CATALOGUE.map((m) => [m.key, m]));

// Build the markerSet a new trip starts with, in picker order.
const defaultMarkerSet = () =>
  DEFAULT_TRIP_MARKER_KEYS.map((key, i) => ({
    ...byKey.get(key),
    order: i,
    isCustom: false,
  }));

const findCatalogueMarker = (key) => byKey.get(key) || null;

module.exports = {
  CATEGORIES,
  SEVERITIES,
  MARKER_CATALOGUE,
  DEFAULT_TRIP_MARKER_KEYS,
  defaultMarkerSet,
  findCatalogueMarker,
};
