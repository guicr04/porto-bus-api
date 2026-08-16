/**
 * Orchestration for the combined live + scheduled departures view of one line
 * at one stop. Fetches everything it needs in parallel, then merges.
 *
 * @typedef {import('../../types/domain').StopLineDepartures} StopLineDepartures
 */
import * as stcp from '../clients/stcp.js';
import * as live from './live.js';
import { scheduledDepartures, activeServiceIds } from '../db/schedule.js';
import { mergeDepartures } from '../lib/combine.js';
import { lisbonNowMinutes, lisbonDateStamp } from '../lib/time.js';

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
  //
  // Only the board is load-bearing: it comes through the fallback seam, so it is
  // live or timetable but never absent. The other two are decoration — direction,
  // colour, service_id — and an outage on either degrades a detail rather than
  // the answer, so they resolve to null instead of taking the request down.
  const [realtime, routes, services] = await Promise.all([
    live.stopBoard(stopCode),
    stcp.stopRoutes(stopCode).catch(rethrowUnlessOutage),
    opts.serviceId ? Promise.resolve(null) : stcp.stopServices(stopCode).catch(rethrowUnlessOutage),
  ]);

  const degraded = realtime.data_source === 'scheduled';

  // Which direction does this line run at this stop? Prefer the per-direction
  // "directions" list; fall back to the caller's override.
  const routeMeta =
    routes?.directions.find((r) => r.route_id === line) ??
    routes?.routes.find((r) => r.route_id === line) ??
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

  // The store knows today's service too, and unlike upstream it still knows it
  // during an outage.
  const serviceId =
    opts.serviceId ?? services?.active_service_id ?? activeServiceIds(lisbonDateStamp())[0] ?? null;

  // The scheduled half. Upstream while it's healthy — it can reflect short-term
  // changes the fortnightly GTFS zip doesn't — and the store once it isn't.
  let scheduled = [];
  if (degraded) {
    scheduled = scheduledDepartures(stopCode, { line, windowMinutes: 120, limit: limit * 2 }).map(
      (d) => ({
        departure_time: `${d.clock}:00`,
        arrival_time: `${d.clock}:00`,
        headsign: d.destination,
        direction_id: d.direction_id ?? directionId,
        trip_id: d.trip_id,
      }),
    );
  } else if (serviceId) {
    try {
      scheduled = (await stcp.stopSchedule(stopCode, line, serviceId, directionId)).departures;
    } catch (err) {
      if (!live.isUpstreamOutage(err)) throw err;
      scheduled = scheduledDepartures(stopCode, { line, windowMinutes: 120, limit: limit * 2 }).map(
        (d) => ({
          departure_time: `${d.clock}:00`,
          arrival_time: `${d.clock}:00`,
          headsign: d.destination,
          direction_id: d.direction_id ?? directionId,
          trip_id: d.trip_id,
        }),
      );
    }
  }

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
    // "scheduled" here means the whole view is timetable-only because STCP was
    // unreachable — distinct from an individual row's `source`, which is about
    // whether that one bus is tracked.
    data_source: degraded ? 'scheduled' : 'realtime',
    departures,
  };
}

/**
 * Swallow an upstream outage (the caller has a store-backed path), re-throw
 * anything that means the request itself was wrong.
 * @param {Error & { status?: number }} err
 */
function rethrowUnlessOutage(err) {
  if (!live.isUpstreamOutage(err)) throw err;
  return null;
}
