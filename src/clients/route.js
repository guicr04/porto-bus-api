/**
 * Client for stcp.pt's LINE/ROUTE-centric JSON API.
 *
 *   /api/route/{line}/shape?direction_id=
 *   /api/route/{line}/stops/direction?direction_id=
 *   /api/route/{line}/services?date=
 *   /api/route/{line}/schedule?service_id=&direction_id=
 *
 * @typedef {import('../../types/domain').RouteShape} RouteShape
 * @typedef {import('../../types/domain').RouteServices} RouteServices
 * @typedef {import('../../types/domain').RouteDirectionStops} RouteDirectionStops
 * @typedef {import('../../types/domain').RouteSchedule} RouteSchedule
 */
import { getJson, encodeQuery } from '../lib/http.js';
import {
  parseShape,
  parseRouteServices,
  parseRouteDirectionStops,
  parseRouteSchedule,
} from '../lib/parse.js';

/** @param {string} line @param {number} [directionId] @returns {Promise<RouteShape>} */
export async function routeShape(line, directionId = 0) {
  const raw = await getJson(
    `/route/${encodeURIComponent(line)}/shape?${encodeQuery({ direction_id: directionId })}`
  );
  return parseShape(raw, line);
}

/** @param {string} line @param {number} [directionId] @returns {Promise<RouteDirectionStops>} */
export async function routeStops(line, directionId = 0) {
  const raw = await getJson(
    `/route/${encodeURIComponent(line)}/stops/direction?${encodeQuery({ direction_id: directionId })}`
  );
  return parseRouteDirectionStops(raw, line);
}

/**
 * @param {string} line
 * @param {string} [date] YYYY-MM-DD; defaults to today
 * @returns {Promise<RouteServices>}
 */
export async function routeServices(line, date) {
  const day = date ?? new Date().toISOString().slice(0, 10);
  const raw = await getJson(
    `/route/${encodeURIComponent(line)}/services?${encodeQuery({ date: day })}`
  );
  return parseRouteServices(raw, line);
}

/**
 * Full timetable grid for a line in one direction.
 * @param {string} line
 * @param {string} serviceId  e.g. "DOM|FERIADO:FLUXO 3.1 20260718"
 * @param {number} [directionId]
 * @returns {Promise<RouteSchedule>}
 */
export async function routeSchedule(line, serviceId, directionId = 0) {
  const query = encodeQuery({ service_id: serviceId, direction_id: directionId });
  const raw = await getJson(`/route/${encodeURIComponent(line)}/schedule?${query}`);
  return parseRouteSchedule(raw, line);
}
