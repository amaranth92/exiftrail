import assert from "node:assert/strict";
import { normalizeRoute } from "../dist-check/route.js";

const base = new Date("2026-01-01T00:00:00Z");
const result = normalizeRoute([
  { lat: -31.95, lng: 115.86, time: new Date(+base + 1000), distanceFromPrevKm: 0, suspicious: false },
  { lat: -31.9501, lng: 115.8601, time: new Date(+base + 2000), distanceFromPrevKm: 0, suspicious: false },
  { lat: -33.86, lng: 151.2, time: new Date(+base + 3600_000), distanceFromPrevKm: 0, suspicious: false },
]);

assert.equal(result.duplicates, 1);
assert.equal(result.points.length, 2);
assert.equal(result.points[1].suspicious, true);
console.log("self-check passed");
