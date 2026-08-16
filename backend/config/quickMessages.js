// ─────────────────────────────────────────────────────────────
// Quick messages — the canned phrases that actually get used.
//
// A driver will not type. Anything that requires a keyboard while moving is
// a feature nobody uses, so the useful chat surface is a row of one-tap
// phrases covering the things people genuinely say to each other mid-convoy.
//
// Served from the API for the same reason the marker catalogue is: the app
// must never hold a list that can drift from the server's behaviour.
// ─────────────────────────────────────────────────────────────

const QUICK_MESSAGES = [
  { key: "pulling_over",   label: "Pulling over",     icon: "🅿️", severity: "INFO" },
  { key: "need_fuel",      label: "Need fuel",        icon: "⛽", severity: "INFO" },
  { key: "need_break",     label: "Need a break",     icon: "☕", severity: "INFO" },
  { key: "go_ahead",       label: "Go ahead",         icon: "👉", severity: "INFO" },
  { key: "wait_up",        label: "Wait up",          icon: "✋", severity: "WARN" },
  { key: "almost_there",   label: "Almost there",     icon: "📍", severity: "INFO" },
  { key: "on_my_way",      label: "On my way",        icon: "🚗", severity: "INFO" },
  { key: "all_good",       label: "All good",         icon: "👍", severity: "INFO" },
  { key: "where_are_you",  label: "Where are you?",   icon: "❓", severity: "INFO" },
  { key: "wrong_turn",     label: "Took a wrong turn", icon: "↩️", severity: "WARN" },
  // The one that must cut through everything else, including a silenced phone.
  { key: "emergency_stop", label: "Emergency — stop", icon: "🛑", severity: "CRITICAL" },
];

const byKey = new Map(QUICK_MESSAGES.map((q) => [q.key, q]));

const findQuickMessage = (key) => byKey.get(key) || null;

module.exports = { QUICK_MESSAGES, findQuickMessage };
