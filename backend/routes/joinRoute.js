const express = require("express");
const rateLimit = require("express-rate-limit");
const tripService = require("../services/trip.service");
const config = require("../config/config");

// ─────────────────────────────────────────────────────────────
// The shareable join link.
//
// The host drops this into WhatsApp, so it has to be an ordinary https URL:
// a `convoy://` link is not clickable in most messengers, and several strip
// it from the message entirely. But an https URL cannot open the app either
// unless Android has VERIFIED it, which needs a domain we control.
//
// So this page is the bridge. It is a real https link that renders properly
// in a chat, and the moment it opens it hands off to the app's own scheme.
// Anyone without the app sees the trip name and the join code instead of a
// dead page — which is the case that used to be worst, because the link
// pointed at localhost:5173 and simply failed to load.
// ─────────────────────────────────────────────────────────────

const router = express.Router();

const escapeHtml = (s) =>
  String(s ?? "").replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  })[c]);

const page = ({ token, tripName, joinCode, expired }) => {
  const deepLink = `convoy://join/${encodeURIComponent(token)}`;
  const title = expired
    ? "This invite has expired"
    : tripName
      ? `Join ${escapeHtml(tripName)}`
      : "Join this convoy";

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${title} · Convoy</title>
<style>
  :root { color-scheme: light dark; }
  body {
    margin: 0; min-height: 100vh; display: grid; place-items: center;
    font: 16px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    background: #0E1514; color: #E8EFEC; padding: 24px;
  }
  .card { max-width: 380px; width: 100%; text-align: center; }
  .mark { font-size: 54px; }
  h1 { font-size: 24px; margin: 18px 0 6px; font-weight: 600; }
  p { color: #9BAAA5; margin: 8px 0; }
  .code {
    display: inline-block; margin: 18px 0 6px; padding: 12px 22px;
    font: 600 26px/1 ui-monospace, SFMono-Regular, Menlo, monospace;
    letter-spacing: 4px; background: #16211F; border: 1px solid #2A3735;
    border-radius: 14px; color: #E8EFEC;
  }
  a.btn {
    display: block; margin-top: 22px; padding: 16px; border-radius: 16px;
    background: #21D0A8; color: #04221E; font-weight: 600;
    text-decoration: none;
  }
  small { display: block; margin-top: 20px; color: #6C7B77; font-size: 12.5px; }
</style>
</head>
<body>
  <div class="card">
    <div class="mark">🛣️</div>
    <h1>${title}</h1>
    ${expired
      ? `<p>Ask the host to send a new link.</p>`
      : `<p>Opening Convoy…</p>
         ${joinCode ? `<div class="code">${escapeHtml(joinCode)}</div>
         <p>Or enter this code in the app.</p>` : ""}
         <a class="btn" href="${deepLink}">Open in Convoy</a>`}
    <small>Convoy keeps a group of cars together on a road trip.</small>
  </div>
  ${expired ? "" : `<script>
    // Attempted immediately, but only once: firing it repeatedly makes
    // Android show the "open with" chooser over and over if the app is
    // not installed. The button above is the deliberate second chance.
    setTimeout(function () { window.location.href = ${JSON.stringify(deepLink)}; }, 250);
  </script>`}
</body>
</html>`;
};

router.get(
  "/:token",
  rateLimit({
    windowMs: 5 * 60 * 1000,
    max: 120,
    standardHeaders: true,
    legacyHeaders: false,
    message: { status: "fail", message: "Too many requests." },
  }),
  async (req, res) => {
    const { token } = req.params;

    // Deliberately forgiving. This is a page a person opens, not an API —
    // a lookup failure should still render something they can act on,
    // because the deep link works whether or not we resolved the name.
    let tripName = null;
    let joinCode = null;
    let expired = false;

    try {
      const { trip } = await tripService.resolveTrip({ token });
      if (trip) {
        tripName = trip.name;
        joinCode = trip.joinCode;
      }
    } catch (err) {
      // resolveTrip throws 410 for an expired link, which is the one case
      // worth saying out loud rather than sending someone into the app.
      if (err.statusCode === 410) expired = true;
    }

    res
      .status(expired ? 410 : 200)
      .type("html")
      .send(page({ token, tripName, joinCode, expired }));
  }
);

module.exports = router;
