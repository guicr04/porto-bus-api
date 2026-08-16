/**
 * Resolving a *live* trip to a trip in the static store, and reading its
 * ordered stops.
 *
 * This exists for one screen — the app's line detail (app repo `DESIGN.md`
 * §11.1, Phase 2), which answers "where does this specific bus take me, and
 * when does it get there". The live board names the bus with `Arrival.trip_id`;
 * everything downstream of that comes from `stop_times`. The join between the
 * two is the whole difficulty, and it is documented in README §2a.
 *
 * @typedef {import('../../types/domain').ResolvedTrip} ResolvedTrip
 * @typedef {import('../../types/domain').ResolvedTripStop} ResolvedTripStop
 */
import { getDb, isFeedExpired } from './index.js';
import { activeServiceIds } from './schedule.js';
import { lisbonDateStamp, lisbonNowMinutes } from '../lib/time.js';

const DAY_SECONDS = 86400;

/**
 * Drop the feed-version field from a trip id.
 *
 * Ids look like `601_0_1|280|D3|T1|N6`, and the second field is a counter STCP
 * bumps every time it republishes the schedule. Live served `280` while the
 * ingested zip held `276`, so a verbatim match resolves nothing at all. Every
 * other field is stable, which is what makes this safe rather than lossy.
 *
 * @param {string} tripId
 * @returns {string} the same id with field 2 removed, e.g. `601_0_1|D3|T1|N6`
 */
export function normalizeTripId(tripId) {
  const parts = String(tripId).split('|');
  if (parts.length < 3) return String(tripId);
  parts.splice(1, 1);
  return parts.join('|');
}

/**
 * A LIKE pattern matching every version of one trip id: the id with its version
 * field replaced by a wildcard.
 *
 * The escaping is not optional — stop and trip ids are full of underscores
 * (`601_0_1`), and `_` is a single-character wildcard in SQL LIKE. Unescaped,
 * `601_0_1|%|...` also matches `60110111|...`.
 *
 * @param {string} tripId
 * @returns {string | null} null when the id has no version field to wildcard
 */
function versionWildcard(tripId) {
  const parts = String(tripId).split('|');
  if (parts.length < 3) return null;
  const esc = (s) => s.replace(/[\\%_]/g, (c) => `\\${c}`);
  return `${esc(parts[0])}|%|${parts.slice(2).map(esc).join('|')}`;
}

/**
 * Every trip in the store whose id differs from this one only by feed version.
 * @param {string} tripId
 */
function versionSiblings(tripId) {
  const pattern = versionWildcard(tripId);
  if (!pattern) return [];
  // A LIKE scan over ~23k trips, which measures at ~6 ms — small enough that a
  // precomputed normalised column would be complexity bought for nothing, and
  // it keeps this endpoint working against a store that is already ingested.
  return getDb()
    .prepare(
      "SELECT trip_id, route_id, service_id, headsign, direction_id, shape_id" +
        " FROM trips WHERE trip_id LIKE ? ESCAPE '\\' ORDER BY trip_id",
    )
    .all(pattern);
}

/** The numeric feed version inside a trip id, or -1 when it has none. */
function versionOf(tripId) {
  const n = Number(String(tripId).split('|')[1]);
  return Number.isFinite(n) ? n : -1;
}

/**
 * The service_ids that could plausibly own a bus running right now: today's,
 * plus yesterday's for the after-midnight trips GTFS files under the previous
 * service day.
 * @param {Date} [now]
 * @returns {Set<string>}
 */
function currentServiceIds(now = new Date()) {
  return new Set([
    ...activeServiceIds(lisbonDateStamp(now)),
    ...activeServiceIds(lisbonDateStamp(now, -1)),
  ]);
}

/**
 * Resolve a live `trip_id` to a trip in the store.
 *
 * The chain, in order, because each step is less certain than the one above it
 * and the caller is told which one answered:
 *
 * 1. `exact`     — the ids are equal. Only happens when the store's feed version
 *                  matches what STCP is currently serving.
 * 2. `version`   — equal once the version field is dropped, and the day's
 *                  service picks exactly one. This is the normal path.
 * 3. `version_latest` — same, but the service filter couldn't narrow it: either
 *                  the feed is expired so *no* service is active today, or the
 *                  same pattern exists under several weekday variants. The
 *                  newest feed version wins, since that is the one the current
 *                  store intends. 5,386 of 12,716 normalised keys collide this
 *                  way, all of them `UTIL FERIAS` weekday reissues.
 * 4. null        — the caller should try `findTripByPattern` before giving up.
 *
 * @param {string} tripId
 * @param {object} [opts]
 * @param {Date} [opts.now]
 * @returns {{ trip: object, match: 'exact' | 'version' | 'version_latest' } | null}
 */
export function resolveTrip(tripId, opts = {}) {
  const db = getDb();
  const exact = db
    .prepare(
      'SELECT trip_id, route_id, service_id, headsign, direction_id, shape_id' +
        ' FROM trips WHERE trip_id = ?',
    )
    .get(tripId);
  if (exact) return { trip: exact, match: 'exact' };

  const siblings = versionSiblings(tripId);
  if (siblings.length === 0) return null;
  if (siblings.length === 1) return { trip: siblings[0], match: 'version' };

  const active = currentServiceIds(opts.now);
  const inService = siblings.filter((t) => active.has(t.service_id));
  if (inService.length === 1) return { trip: inService[0], match: 'version' };

  // Ambiguous, or the day has no service at all. Prefer the newest version
  // among whatever is still in the running — deterministic, and it picks the
  // reissue rather than the superseded copy.
  const pool = inService.length > 1 ? inService : siblings;
  const newest = pool.reduce((a, b) => (versionOf(b.trip_id) > versionOf(a.trip_id) ? b : a));
  return { trip: newest, match: 'version_latest' };
}

/**
 * The fallback when the id join misses entirely: find the trip by what a rider
 * can see instead — the line, where it says it is going, and roughly when it
 * leaves a stop they named.
 *
 * Deliberately narrow. It needs a stop and a line to have any discriminating
 * power at all, and without a target time it would just return the day's first
 * departure, so all three are required.
 *
 * @param {object} q
 * @param {string} q.line       route short_name, e.g. "305"
 * @param {string} q.stopCode   a stop the bus calls at
 * @param {number} q.etaMinutes minutes from now that it is expected there
 * @param {string} [q.headsign] destination, when the board gave one
 * @param {Date} [q.now]
 * @returns {{ trip: object, match: 'pattern' } | null}
 */
export function findTripByPattern(q) {
  const now = q.now ?? new Date();
  const nowSeconds = lisbonNowMinutes(now) * 60;
  const targetSeconds = nowSeconds + q.etaMinutes * 60;
  const active = currentServiceIds(now);

  const rows = getDb()
    .prepare(
      'SELECT t.trip_id, t.route_id, t.service_id, t.headsign, t.direction_id, t.shape_id,' +
        ' st.departure_seconds' +
        ' FROM stop_times st' +
        ' JOIN trips t  ON t.trip_id = st.trip_id' +
        ' JOIN routes r ON r.route_id = t.route_id' +
        ' WHERE st.stop_code = ? AND r.short_name = ?',
    )
    .all(q.stopCode, q.line);
  if (rows.length === 0) return null;

  // Only filter by service when the store actually knows one for today — an
  // expired feed would otherwise turn every fallback into a miss.
  const scoped = active.size > 0 ? rows.filter((r) => active.has(r.service_id)) : rows;
  const pool = scoped.length > 0 ? scoped : rows;

  const wanted = q.headsign?.trim().toLowerCase();
  const byHeadsign = wanted
    ? pool.filter((r) => (r.headsign ?? '').trim().toLowerCase() === wanted)
    : [];
  const candidates = byHeadsign.length > 0 ? byHeadsign : pool;

  // An after-midnight trip is filed at >86400, so compare against both readings
  // of the target rather than declaring a 24-hour miss.
  const distance = (r) =>
    Math.min(
      Math.abs(r.departure_seconds - targetSeconds),
      Math.abs(r.departure_seconds - (targetSeconds + DAY_SECONDS)),
    );
  const best = candidates.reduce((a, b) => (distance(b) < distance(a) ? b : a));

  // Beyond half an hour out this is no longer "the bus they are looking at",
  // and a wrong trip is worse than no trip.
  if (distance(best) > 30 * 60) return null;

  const { departure_seconds: _drop, ...trip } = best;
  return { trip, match: 'pattern' };
}

/**
 * The ordered stops of one trip, with its scheduled times.
 *
 * `arrival_seconds` is carried alongside the clock strings because the caller's
 * whole job is arithmetic on these — projecting a downstream ETA is
 * `live ETA + (scheduled(later) - scheduled(mine))` — and doing that on
 * "24:35:00" strings invites exactly the after-midnight bug the store already
 * solved once.
 *
 * @param {string} tripId a trip id **in the store**, not a live one
 * @returns {ResolvedTripStop[]}
 */
export function tripStops(tripId) {
  return getDb()
    .prepare(
      'SELECT st.stop_sequence, st.stop_code, st.arrival_time, st.departure_time,' +
        ' st.arrival_seconds, st.departure_seconds, st.timepoint,' +
        ' s.name, s.lat, s.lon' +
        ' FROM stop_times st JOIN stops s ON s.stop_code = st.stop_code' +
        ' WHERE st.trip_id = ? ORDER BY st.stop_sequence',
    )
    .all(tripId)
    .map((r) => ({
      stop_sequence: r.stop_sequence,
      stop_id: r.stop_code,
      stop_code: r.stop_code,
      stop_name: r.name,
      stop_lat: r.lat ?? null,
      stop_lon: r.lon ?? null,
      arrival_time: r.arrival_time,
      departure_time: r.departure_time,
      arrival_seconds: r.arrival_seconds,
      departure_seconds: r.departure_seconds,
      timepoint: r.timepoint === 1,
    }));
}

/**
 * The whole answer for `GET /trips/{trip_id}/stops`: resolve the id, then read
 * the stops.
 *
 * `feed_expired` rides along rather than blocking the response. Elsewhere an
 * expired feed disqualifies the store from standing in for live data, because
 * there it would be *impersonating* a measurement. Here nothing is being
 * impersonated: the caller already has the live ETA and only wants the stop
 * order and the gaps between them, which barely move between feed reissues.
 * The flag is so the client can say so rather than have it hidden.
 *
 * @param {string | null} requestedTripId  null when the caller has no id to ask by
 * @param {object} [hints] fallback identity from the live board
 * @param {string} [hints.line]
 * @param {string} [hints.headsign]
 * @param {string} [hints.stopCode]
 * @param {number} [hints.etaMinutes]
 * @param {Date} [hints.now]
 * @returns {ResolvedTrip | null}
 */
export function resolvedTripStops(requestedTripId, hints = {}) {
  // A null id is not a failed lookup — it is a caller that never had one (a
  // scheduled departure), so skip straight to the pattern match rather than
  // stringifying null into a query that cannot match anything.
  let resolved = requestedTripId === null ? null : resolveTrip(requestedTripId, { now: hints.now });
  if (!resolved && hints.line && hints.stopCode && Number.isFinite(hints.etaMinutes)) {
    resolved = findTripByPattern({
      line: hints.line,
      stopCode: hints.stopCode,
      etaMinutes: /** @type {number} */ (hints.etaMinutes),
      headsign: hints.headsign,
      now: hints.now,
    });
  }
  if (!resolved) return null;

  const stops = tripStops(resolved.trip.trip_id);
  if (stops.length === 0) return null;

  const line = getDb()
    .prepare('SELECT short_name, color, text_color FROM routes WHERE route_id = ?')
    .get(resolved.trip.route_id);

  return {
    trip_id: resolved.trip.trip_id,
    requested_trip_id: requestedTripId ?? null,
    match: resolved.match,
    route_id: resolved.trip.route_id,
    line: line?.short_name ?? null,
    color: line?.color ?? null,
    text_color: line?.text_color ?? null,
    headsign: resolved.trip.headsign ?? null,
    direction_id: resolved.trip.direction_id ?? null,
    service_id: resolved.trip.service_id,
    shape_id: resolved.trip.shape_id ?? null,
    feed_expired: isFeedExpired(hints.now),
    stops,
  };
}
