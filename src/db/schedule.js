/**
 * Scheduled departures, read from the static store.
 *
 * This is what the API serves when STCP's live feed is unreachable. It is a
 * timetable, not a prediction: no delays, no tracking, no confidence that the
 * bus is actually running today beyond the calendar saying it should.
 *
 * After-midnight is the subtle part. GTFS expresses a 00:35 departure that
 * belongs to Tuesday's service as "24:35:00" on Tuesday, so at 00:30 on
 * Wednesday the relevant rows live under *yesterday's* service with times past
 * 86400. Every query here therefore looks at two service days.
 *
 * @typedef {import('../../types/domain').Arrival} Arrival
 * @typedef {import('../../types/domain').RealtimeStop} RealtimeStop
 */
import { getDb } from './index.js';
import { lisbonDateStamp, lisbonNowMinutes } from '../lib/time.js';

const DAY_SECONDS = 86400;

/**
 * The service_ids running on a given date. STCP publishes no weekly calendar,
 * only dated exceptions, so this is the whole truth (README §2a).
 * @param {string} dateStamp YYYYMMDD
 * @returns {string[]}
 */
export function activeServiceIds(dateStamp) {
  return getDb()
    .prepare('SELECT service_id FROM service_dates WHERE date = ? AND exception_type = 1')
    .all(dateStamp)
    .map((r) => r.service_id);
}

/**
 * Scheduled departures from one stop within a forward window.
 *
 * @param {string} stopCode
 * @param {object} [opts]
 * @param {number} [opts.windowMinutes] how far ahead to look (default 90)
 * @param {number} [opts.limit]
 * @param {string} [opts.line] restrict to one route short_name
 * @param {Date} [opts.now]
 * @returns {Array<{ line: string, destination: string, minutes: number, clock: string,
 *                   color: string | null, text_color: string | null,
 *                   direction_id: number | null, trip_id: string }>}
 */
export function scheduledDepartures(stopCode, opts = {}) {
  const { windowMinutes = 90, limit = 20, line = null, now = new Date() } = opts;
  const nowSeconds = lisbonNowMinutes(now) * 60;
  const windowSeconds = windowMinutes * 60;

  const sql =
    'SELECT r.short_name AS line, t.headsign AS destination, st.departure_time,' +
    ' st.departure_seconds, r.color, r.text_color, t.direction_id, t.trip_id' +
    ' FROM stop_times st' +
    ' JOIN trips t  ON t.trip_id = st.trip_id' +
    ' JOIN routes r ON r.route_id = t.route_id' +
    ' WHERE st.stop_code = ?' +
    '   AND t.service_id IN (SELECT service_id FROM service_dates' +
    '                        WHERE date = ? AND exception_type = 1)' +
    '   AND st.departure_seconds BETWEEN ? AND ?' +
    (line ? '   AND r.short_name = ?' : '') +
    ' ORDER BY st.departure_seconds LIMIT ?';

  const db = getDb();
  const rows = [];

  // Today's service, from now forward.
  const todayArgs = [stopCode, lisbonDateStamp(now), nowSeconds, nowSeconds + windowSeconds];
  if (line) todayArgs.push(line);
  todayArgs.push(limit);
  for (const r of db.prepare(sql).all(...todayArgs)) {
    rows.push({ ...r, minutes: Math.round((r.departure_seconds - nowSeconds) / 60) });
  }

  // Yesterday's service, for trips still running past midnight.
  const yStart = nowSeconds + DAY_SECONDS;
  const yesterdayArgs = [stopCode, lisbonDateStamp(now, -1), yStart, yStart + windowSeconds];
  if (line) yesterdayArgs.push(line);
  yesterdayArgs.push(limit);
  for (const r of db.prepare(sql).all(...yesterdayArgs)) {
    rows.push({ ...r, minutes: Math.round((r.departure_seconds - yStart) / 60) });
  }

  return rows
    .sort((a, b) => a.minutes - b.minutes)
    .slice(0, limit)
    .map((r) => ({
      line: r.line,
      destination: r.destination ?? '',
      minutes: r.minutes,
      clock: String(r.departure_time).slice(0, 5),
      color: r.color ?? null,
      text_color: r.text_color ?? null,
      direction_id: r.direction_id ?? null,
      trip_id: r.trip_id,
    }));
}

/**
 * A stop's board built purely from the timetable, shaped exactly like the live
 * one so callers need no special case beyond reading `data_source`.
 *
 * `status` and `delay_minutes` stay null on purpose: a scheduled row has no
 * on-time/delayed truth, and inventing "ON_TIME" would be a lie the UI would
 * faithfully colour green.
 *
 * @param {string} stopCode
 * @param {object} [opts]
 * @returns {RealtimeStop}
 */
export function scheduledStopBoard(stopCode, opts = {}) {
  const db = getDb();
  const stop = db.prepare('SELECT name FROM stops WHERE stop_code = ?').get(stopCode);
  const departures = scheduledDepartures(stopCode, opts);

  /** @type {Arrival[]} */
  const arrivals = departures.map((d) => ({
    line: d.line,
    destination: d.destination,
    arrival_minutes: d.minutes,
    estimated_arrival_time: null,
    scheduled_arrival_time: d.clock,
    status: null,
    delay_minutes: null,
    color: d.color,
    text_color: d.text_color,
    trip_id: d.trip_id,
  }));

  return {
    stop_code: stopCode,
    stop_name: stop?.name ?? null,
    arrivals,
    last_updated: null,
    data_source: 'scheduled',
  };
}

/** Does this stop exist in the store? Guards against fabricating a board. */
export function stopExists(stopCode) {
  return Boolean(getDb().prepare('SELECT 1 FROM stops WHERE stop_code = ?').get(stopCode));
}
