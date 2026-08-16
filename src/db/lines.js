/**
 * Line-centric reads from the static store: the ordered stop list for a
 * direction, and the route polyline.
 *
 * Both are what the iOS Map tab draws, and both are static geometry — which is
 * why they are the strongest candidates for eventually being served from here
 * first rather than as a fallback (README §2a).
 *
 * A route+direction has many trips and they don't all serve the same stops
 * (short workings, peak variants). "The" stop list is therefore the *longest*
 * trip's — the one that covers the full line — rather than an arbitrary one.
 *
 * @typedef {import('../../types/domain').RouteDirectionStops} RouteDirectionStops
 * @typedef {import('../../types/domain').RouteShape} RouteShape
 */
import { getDb } from './index.js';

/**
 * Resolve a rider-facing line name ("500") to its GTFS route_id.
 * @param {string} line @returns {string | null}
 */
export function resolveRouteId(line) {
  const db = getDb();
  const row =
    db.prepare('SELECT route_id FROM routes WHERE short_name = ?').get(line) ??
    db.prepare('SELECT route_id FROM routes WHERE route_id = ?').get(line);
  return row?.route_id ?? null;
}

/**
 * The trip that best represents a route+direction: the one hitting the most
 * stops. Ties break on trip_id so the answer is stable between calls.
 * @param {string} routeId @param {number} directionId
 */
function representativeTrip(routeId, directionId) {
  return getDb()
    .prepare(
      'SELECT t.trip_id, t.shape_id, COUNT(st.stop_sequence) AS n' +
        ' FROM trips t JOIN stop_times st ON st.trip_id = t.trip_id' +
        ' WHERE t.route_id = ? AND t.direction_id = ?' +
        ' GROUP BY t.trip_id ORDER BY n DESC, t.trip_id LIMIT 1',
    )
    .get(routeId, directionId);
}

/**
 * @param {string} line @param {number} directionId
 * @returns {RouteDirectionStops | null}
 */
export function lineStops(line, directionId) {
  const routeId = resolveRouteId(line);
  if (!routeId) return null;
  const trip = representativeTrip(routeId, directionId);
  if (!trip) return null;

  const rows = getDb()
    .prepare(
      'SELECT st.stop_sequence, st.stop_code, st.timepoint, s.name, s.lat, s.lon, s.zone_id' +
        ' FROM stop_times st JOIN stops s ON s.stop_code = st.stop_code' +
        ' WHERE st.trip_id = ? ORDER BY st.stop_sequence',
    )
    .all(trip.trip_id);

  return {
    route_id: routeId,
    direction_id: directionId,
    stops: rows.map((r) => ({
      stop_id: r.stop_code,
      stop_name: r.name,
      stop_code: r.stop_code,
      zone_id: r.zone_id ?? null,
      lat: r.lat ?? null,
      lon: r.lon ?? null,
      sequence: r.stop_sequence,
      description: null,
    })),
    timepoint_stop_ids: rows.filter((r) => r.timepoint === 1).map((r) => r.stop_code),
  };
}

/**
 * @param {string} line @param {number} directionId
 * @returns {RouteShape | null}
 */
export function lineShape(line, directionId) {
  const routeId = resolveRouteId(line);
  if (!routeId) return null;
  const trip = representativeTrip(routeId, directionId);
  if (!trip?.shape_id) return null;

  const points = getDb()
    .prepare('SELECT lat, lon, sequence FROM shapes WHERE shape_id = ? ORDER BY sequence')
    .all(trip.shape_id);
  if (points.length === 0) return null;

  return {
    route_id: routeId,
    direction_id: directionId,
    coordinates: points.map((p) => ({ lat: p.lat, lng: p.lon, sequence: p.sequence })),
  };
}
