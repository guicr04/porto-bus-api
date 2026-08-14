/**
 * Distance and walking-time helpers.
 *
 * Everything here is pure so it can be tested without touching the network.
 */

/** Mean Earth radius, metres. */
const EARTH_RADIUS_M = 6_371_000;

/**
 * Straight-line ("as the crow flies") distance between two points, in metres.
 *
 * @param {number} lat1 @param {number} lon1
 * @param {number} lat2 @param {number} lon2
 * @returns {number} metres
 */
export function haversineMeters(lat1, lon1, lat2, lon2) {
  const toRad = (d) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a));
}

/**
 * Walking model.
 *
 * `WALK_METERS_PER_MINUTE` is a comfortable pace (~4.5 km/h). `DETOUR_FACTOR`
 * accounts for the fact that you walk streets, not straight lines — Porto is hilly
 * and dense, so the real path is meaningfully longer than the crow-flies distance.
 * 1.35 is the usual rule of thumb for an urban grid.
 *
 * These are deliberately pessimistic: for a board that tells you whether you can
 * still catch a bus, over-estimating the walk is the safe direction to be wrong in.
 */
export const WALK_METERS_PER_MINUTE = 75;
export const DETOUR_FACTOR = 1.35;

/**
 * Minutes to walk a straight-line distance, rounded up.
 *
 * @param {number} meters
 * @param {number} [metersPerMinute]
 * @returns {number}
 */
export function walkMinutes(meters, metersPerMinute = WALK_METERS_PER_MINUTE) {
  return Math.ceil((meters * DETOUR_FACTOR) / metersPerMinute);
}

/**
 * Stops within a walking-time budget of an origin, nearest first.
 *
 * Stops with no coordinates in the GTFS feed are skipped rather than treated as
 * being at (0, 0).
 *
 * @param {{ stop_code: string, name: string, lat: number | null, lon: number | null }[]} stops
 * @param {number} lat
 * @param {number} lon
 * @param {number} maxWalkMinutes
 * @param {number} [metersPerMinute]
 * @returns {{ stop_code: string, name: string, lat: number, lon: number, distance_meters: number, walk_minutes: number }[]}
 */
export function stopsWithinWalk(stops, lat, lon, maxWalkMinutes, metersPerMinute) {
  const out = [];
  for (const s of stops) {
    if (s.lat === null || s.lon === null) continue;
    const distance = haversineMeters(lat, lon, s.lat, s.lon);
    const walk = walkMinutes(distance, metersPerMinute);
    if (walk > maxWalkMinutes) continue;
    out.push({
      stop_code: s.stop_code,
      name: s.name,
      lat: s.lat,
      lon: s.lon,
      distance_meters: Math.round(distance),
      walk_minutes: walk,
    });
  }
  out.sort((a, b) => a.distance_meters - b.distance_meters);
  return out;
}
