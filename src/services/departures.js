/**
 * Orchestration for the combined live + scheduled departures view of one line
 * at one stop. Fetches everything it needs in parallel, then merges.
 *
 * @typedef {import('../../types/domain').StopLineDepartures} StopLineDepartures
 */
import * as stcp from '../clients/stcp.js';
import { mergeDepartures } from '../lib/combine.js';
import { lisbonNowMinutes } from '../lib/time.js';

/**
 * @param {string} stopCode
 * @param {string} line
 * @param {object} [opts]
 * @param {string} [opts.serviceId]      override the auto-detected service_id
 * @param {number} [opts.directionId]    override the auto-detected direction
 * @param {number} [opts.windowMinutes]  dedup tolerance (default 3)
 * @param {number} [opts.limit]          max departures (default 10)
 * @returns {Promise<StopLineDepartures>}
 */
export async function stopLineDepartures(stopCode, line, opts = {}) {
  const windowMinutes = opts.windowMinutes ?? 3;
  const limit = opts.limit ?? 10;

  // Fetch the live board, the stop's routes (for direction + colour), and the
  // day's services (for the active service_id) in parallel.
  const [realtime, routes, services] = await Promise.all([
    stcp.stopRealtime(stopCode),
    stcp.stopRoutes(stopCode),
    opts.serviceId ? Promise.resolve(null) : stcp.stopServices(stopCode),
  ]);

  // Which direction does this line run at this stop? Prefer the per-direction
  // "directions" list; fall back to the caller's override.
  const routeMeta =
    routes.directions.find((r) => r.route_id === line) ??
    routes.routes.find((r) => r.route_id === line) ??
    null;
  const directionId = opts.directionId ?? routeMeta?.direction_id ?? 0;

  // Colour: the live board carries the line's real colour (e.g. 300 -> #417DBD),
  // while the routes endpoint only returns a coarse family colour (#187EC2 for
  // every city line). Taking the routes colour would render the same line in two
  // different blues depending on whether the entry came from live or schedule,
  // so prefer the live one and fall back to routes only when the board is empty.
  const liveForLine = realtime.arrivals.filter((a) => a.line === line);
  const liveSample = liveForLine.find((a) => a.color);
  const color = liveSample?.color ?? routeMeta?.color ?? null;
  const textColor = liveSample?.text_color ?? routeMeta?.text_color ?? null;

  const serviceId = opts.serviceId ?? services?.active_service_id ?? null;

  // Scheduled fallback (only if we have a service_id to ask for).
  const scheduled = serviceId
    ? (await stcp.stopSchedule(stopCode, line, serviceId, directionId)).departures
    : [];

  const departures = mergeDepartures({
    realtime: liveForLine,
    scheduled,
    nowMin: lisbonNowMinutes(),
    windowMinutes,
    limit,
    line,
    color,
    textColor,
  });

  return {
    stop_code: stopCode,
    line,
    direction_id: directionId,
    service_id: serviceId,
    generated_at: new Date().toISOString(),
    departures,
  };
}
