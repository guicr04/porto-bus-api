/**
 * Client for stcp.pt's STOP-centric JSON API.
 *
 *   /api/stops/{code}/realtime
 *   /api/stops/{code}/routes
 *   /api/stops/{code}/services?date=YYYY-MM-DD
 *   /api/stops/{code}/schedule?route_id=&service_id=&direction_id=
 *
 * @typedef {import('../../types/domain').RealtimeStop} RealtimeStop
 * @typedef {import('../../types/domain').StopRoutes} StopRoutes
 * @typedef {import('../../types/domain').StopServices} StopServices
 * @typedef {import('../../types/domain').StopSchedule} StopSchedule
 */
import { getJson, encodeQuery } from '../lib/http.js';
import { config } from '../config.js';
import { cached } from '../lib/cache.js';
import {
  parseRealtime,
  parseStopRoutes,
  parseServices,
  parseSchedule,
} from '../lib/parse.js';

/**
 * Live arrivals for a stop.
 *
 * Cached for a few seconds (REALTIME_TTL_MS): the departure board polls a dozen
 * stops at a time and a desk display refreshes constantly, so without this we'd
 * generate a lot of traffic against an endpoint we have no agreement to use. The
 * TTL is short enough that nobody sees a stale minute count.
 *
 * @param {string} stopCode @returns {Promise<RealtimeStop>}
 */
export async function stopRealtime(stopCode) {
  return cached(`realtime:${stopCode}`, config.realtimeTtlMs, async () => {
    const raw = await getJson(`/stops/${encodeURIComponent(stopCode)}/realtime`);
    return parseRealtime(raw, stopCode);
  });
}

/** @param {string} stopCode @returns {Promise<StopRoutes>} */
export async function stopRoutes(stopCode) {
  const raw = await getJson(`/stops/${encodeURIComponent(stopCode)}/routes`);
  return parseStopRoutes(raw);
}

/**
 * @param {string} stopCode
 * @param {string} [date] YYYY-MM-DD; defaults to today
 * @returns {Promise<StopServices>}
 */
export async function stopServices(stopCode, date) {
  const day = date ?? new Date().toISOString().slice(0, 10);
  const raw = await getJson(
    `/stops/${encodeURIComponent(stopCode)}/services?${encodeQuery({ date: day })}`
  );
  return parseServices(raw);
}

/**
 * Timetable for one route + service + direction at a stop.
 * `serviceId` is a GTFS-style key, e.g. "DOM|FERIADO:FLUXO 3.1 20260718".
 * @param {string} stopCode
 * @param {string} routeId
 * @param {string} serviceId
 * @param {number} [directionId]
 * @returns {Promise<StopSchedule>}
 */
export async function stopSchedule(stopCode, routeId, serviceId, directionId = 0) {
  const query = encodeQuery({
    route_id: routeId,
    service_id: serviceId,
    direction_id: directionId,
  });
  const raw = await getJson(`/stops/${encodeURIComponent(stopCode)}/schedule?${query}`);
  return parseSchedule(raw, stopCode);
}
