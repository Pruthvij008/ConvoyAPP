// Route geometry — does the line we send the phones follow the actual road?
//
// This exists because "the route draws as straight lines across the map"
// has now had TWO separate causes, and neither was visible from the code:
//
//   1. Google was asked for polylineQuality OVERVIEW, a deliberately sparse
//      geometry meant for a zoomed-out preview.
//   2. The simplifier kept every Nth point. On a long trip that meant one
//      point every few hundred metres, which draws a chord straight across
//      every bend.
//
// Both are shape bugs, and the only honest test of a shape bug is to measure
// how far the simplified line strays from the geometry it came from. No
// network and no database — this is pure geometry.
require("dotenv").config({ path: "./config.env" });

const routing = require("../services/routing.service");

let pass = 0, fail = 0;
const ok = (m) => { pass += 1; console.log(`  PASS  ${m}`); };
const bad = (m) => { fail += 1; console.log(`  FAIL  ${m}`); };
const section = (m) => console.log(`\n${m}`);

const M_PER_DEGREE = 111_320;

const perpendicularM = (p, a, b) => {
  const kx = Math.cos((a[1] * Math.PI) / 180) * M_PER_DEGREE;
  const ky = M_PER_DEGREE;
  const px = (p[0] - a[0]) * kx, py = (p[1] - a[1]) * ky;
  const bx = (b[0] - a[0]) * kx, by = (b[1] - a[1]) * ky;
  const lengthSq = bx * bx + by * by;
  if (lengthSq === 0) return Math.hypot(px, py);
  const t = Math.max(0, Math.min(1, (px * bx + py * by) / lengthSq));
  return Math.hypot(px - t * bx, py - t * by);
};

// The number that matters: for every point on the REAL road, how far is it
// from the line we actually draw? This is "how far does the drawn route cut
// across the countryside", in metres.
const worstDeviationM = (original, simplified) => {
  let worst = 0;
  for (const p of original) {
    let nearest = Infinity;
    for (let i = 1; i < simplified.length; i += 1) {
      const d = perpendicularM(p, simplified[i - 1], simplified[i]);
      if (d < nearest) nearest = d;
      if (nearest === 0) break;
    }
    if (nearest > worst) worst = nearest;
  }
  return worst;
};

// A long trip with a twisty section in the middle — the shape that broke.
// Sampled every 20 m, which is roughly what Google's HIGH_QUALITY polyline
// returns.
const longRouteWithGhat = () => {
  const points = [];
  const count = 25_000; // ~500 km
  for (let i = 0; i < count; i += 1) {
    const t = i / count;
    let lat = 19.0 - t * 3.4;
    let lng = 72.9 - t * 0.9;
    if (t > 0.35 && t < 0.40) {
      const u = (t - 0.35) / 0.05;
      lng += Math.sin(u * Math.PI * 120) * 0.0025;
      lat += Math.cos(u * Math.PI * 120) * 0.0004;
    }
    points.push([lng, lat]);
  }
  return points;
};

const shortCityRoute = () => {
  const points = [];
  for (let i = 0; i < 600; i += 1) {
    const t = i / 600;
    points.push([72.87 + t * 0.05 + Math.sin(t * 40) * 0.0004, 19.07 + t * 0.03]);
  }
  return points;
};

(async () => {
  section("1. A long route keeps its shape");
  const long = longRouteWithGhat();
  const simplifiedLong = routing.__simplify(long);
  const deviation = worstDeviationM(long, simplifiedLong);

  console.log(`        ${long.length} points in, ${simplifiedLong.length} out, ` +
    `worst deviation ${deviation.toFixed(1)} m`);

  // 25 m is about the width of a dual carriageway plus its verges. Beyond
  // that the line is visibly off the road rather than merely smoothed.
  if (deviation <= 25) ok(`long route stays within 25 m of the road (${deviation.toFixed(1)} m)`);
  else bad(`long route strays ${deviation.toFixed(0)} m from the road — bends are being cut`);

  if (simplifiedLong.length <= 1500) ok(`stays under the point cap (${simplifiedLong.length})`);
  else bad(`too many points for a phone to draw: ${simplifiedLong.length}`);

  section("2. Endpoints survive");
  const first = simplifiedLong[0];
  const last = simplifiedLong[simplifiedLong.length - 1];
  if (first[0] === long[0][0] && first[1] === long[0][1]) ok("route still starts at the origin");
  else bad("origin was dropped");
  if (last[0] === long[long.length - 1][0] && last[1] === long[long.length - 1][1]) {
    ok("route still reaches the destination");
  } else {
    bad("destination was dropped — the line stops short");
  }

  section("3. A short route is left essentially alone");
  const short = shortCityRoute();
  const simplifiedShort = routing.__simplify(short);
  const shortDeviation = worstDeviationM(short, simplifiedShort);
  if (shortDeviation <= 10) ok(`short route stays within 10 m (${shortDeviation.toFixed(1)} m)`);
  else bad(`short route strays ${shortDeviation.toFixed(1)} m`);

  section("4. Degenerate input does not throw");
  const edgeCases = [
    ["empty", []],
    ["one point", [[73, 18]]],
    ["two points", [[73, 18], [73.1, 18.1]]],
    ["null", null],
    // A stationary GPS sample repeats a coordinate, which is a zero-length
    // segment and a division by zero if it is not guarded.
    ["repeated points", [[73, 18], [73, 18], [73, 18], [73.5, 18.5]]],
  ];
  for (const [name, input] of edgeCases) {
    try {
      routing.__simplify(input);
      ok(`${name} handled`);
    } catch (e) {
      bad(`${name} threw: ${e.message}`);
    }
  }

  section("5. Google is asked for the dense polyline");
  // Guarding the first cause the same way as the second: a future edit that
  // reinstates OVERVIEW should fail here rather than on someone's drive.
  const source = require("fs").readFileSync(
    require("path").join(__dirname, "..", "services", "routing.service.js"), "utf8"
  );
  if (/polylineQuality:\s*"HIGH_QUALITY"/.test(source)) {
    ok("Google Routes request asks for HIGH_QUALITY");
  } else {
    bad("Google Routes is not asking for HIGH_QUALITY — routes will be sparse");
  }
  if (/overview=full/.test(source)) ok("OSRM request asks for the full geometry");
  else bad("OSRM is not asking for overview=full");

  console.log(`\n${fail === 0 ? "All" : pass} checks passed${fail ? `, ${fail} FAILED` : ""}.\n`);
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error("ERROR", e); process.exit(1); });
