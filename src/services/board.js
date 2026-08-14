/**
 * Departure board for a location: every bus you can still catch from any stop
 * within a walking-time budget, ordered by arrival.
 *
 * This is the service the bedroom display polls. It deliberately does no
 * rendering — see lib/board.js for the text renderer.
 */
import * as gtfs from '../clients/gtfs.js';
import * as stcp from '../clients/stcp.js';
import { stopsWithinWalk, haversineMeters } from '../lib/geo.js';
import { buildBoard } from '../lib/board.js';

/**
 * How close two stops must be to count as platforms of the same place.
 * Opposite kerbs of a wide avenue sit around 30 m apart; genuinely different
 * stops are further.
 */
const CLUSTER_METERS = 60;

/**
 * Choose which of the in-range stops to actually poll.
 *
 * Nearest-first, but skipping stops that are just another platform of one already
 * picked, so a cluster doesn't consume the whole budget and leave the rest of the
 * radius unseen. Leftovers then fill any remaining budget.
 *
 * Proximity, not just name, decides: the feed spells the same place several ways
 * ("GUIL. G. FERNANDES" vs "GUILHERME GOMES FERNANDES", "HOSP. STO. ANTÓNIO" vs
 * "HOSP.STO. ANTÓNIO"), so name matching alone lets duplicates through.
 *
 * @param {{ stop_code: string, name: string, lat?: number, lon?: number }[]} nearby
 *   already sorted by distance from the origin
 * @param {number} maxStops
 */
export function pickStopsToPoll(nearby, maxStops) {
  const names = new Set();
  const picked = [];
  const rest = [];

  for (const s of nearby) {
    const name = s.name.trim().toUpperCase();

    const isDuplicate =
      names.has(name) ||
      picked.some(
        (p) =>
          p.lat !== undefined &&
          s.lat !== undefined &&
          haversineMeters(p.lat, p.lon, s.lat, s.lon) < CLUSTER_METERS,
      );

    if (isDuplicate) {
      rest.push(s);
    } else {
      names.add(name);
      picked.push(s);
    }
  }

  return [...picked, ...rest].slice(0, maxStops);
}

/**
 * @param {object} args
 * @param {number} args.lat
 * @param {number} args.lon
 * @param {number} [args.walkMinutes]   walking budget, default 10
 * @param {number} [args.limit]         rows, default 10
 * @param {number} [args.maxStops]      how many nearby stops to poll, default 12
 * @param {number} [args.buffer]        slack minutes before departure, default 0
 * @param {boolean} [args.includeUnreachable]
 * @param {boolean} [args.collapse]
 * @param {number} [args.metersPerMinute]
 * @param {'line' | 'eta'} [args.sort]  row order, default 'line'
 */
export async function locationBoard({
  lat,
  lon,
  walkMinutes = 10,
  limit = 10,
  maxStops = 12,
  buffer = 0,
  includeUnreachable = false,
  collapse = true,
  metersPerMinute,
  sort = 'line',
}) {
  const allStops = await gtfs.getStops();
  const nearby = stopsWithinWalk(allStops, lat, lon, walkMinutes, metersPerMinute);

  // Each stop costs one upstream call, so cap how many we poll.
  //
  // Taking the plain nearest-N is a bad cap downtown: stops cluster in platforms
  // (CORDOARIA alone appears three times within 20 m), so the nearest 6 around
  // Carmo covered a 195 m radius while the 10-minute budget reaches ~750 m. The
  // board then silently claimed a coverage it didn't have. So we keep one stop per
  // name first — different platforms of one stop mostly serve the same lines — and
  // only then fall back to filling the remaining budget with the next nearest.
  const polled = pickStopsToPoll(nearby, maxStops);

  const boards = await Promise.all(
    polled.map(async (stop) => {
      try {
        const rt = await stcp.stopRealtime(stop.stop_code);
        // data_source tells us whether STCP is tracking buses at this stop or has
        // fallen back to the timetable; the board marks tracked times differently.
        return { stop, arrivals: rt.arrivals ?? [], dataSource: rt.data_source };
      } catch (err) {
        // One flaky stop shouldn't blank the whole board.
        console.error(`realtime failed for ${stop.stop_code}: ${err.message}`);
        return { stop, arrivals: [], error: true };
      }
    }),
  );

  const departures = buildBoard({
    stops: boards,
    limit,
    includeUnreachable,
    collapse,
    buffer,
    sort,
  });

  return {
    origin: { lat, lon },
    walk_minutes: walkMinutes,
    generated_at: new Date().toISOString(),
    stops_considered: nearby.length,
    // True when max_stops kept us from polling everything in range — so a thin
    // board can be read as "we didn't look everywhere" rather than "nothing runs".
    stops_truncated: nearby.length > polled.length,
    stops_polled: polled.map((s) => ({
      stop_code: s.stop_code,
      name: s.name,
      distance_meters: s.distance_meters,
      walk_minutes: s.walk_minutes,
      // Surfaced so a blank board can be told apart from a broken upstream.
      ok: !boards.find((b) => b.stop.stop_code === s.stop_code)?.error,
    })),
    departures,
  };
}
