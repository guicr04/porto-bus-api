/**
 * GTFS ingest: the whole feed -> the static store.
 *
 * The only writer. Everything happens inside one transaction, so a reader can
 * never observe a partially-loaded feed and a failure halfway through leaves
 * yesterday's data intact rather than a hole.
 *
 * Deliberately strict. A silently-wrong feed is worse than a failed refresh:
 * the API would keep serving plausible nonsense. So the assumptions this store
 * is built on (README §2a) are asserted here, and violating one aborts the
 * ingest with the old data still in place.
 */
import AdmZip from 'adm-zip';
import { config } from '../config.js';
import { parseCsv, iterCsv } from '../lib/csv.js';
import { getDb } from './index.js';
import { toHexColor } from '../lib/color.js';

/** GTFS rows we refuse to serve: placeholder stops with "." for a name/code. */
const JUNK = new Set(['', '.']);

/**
 * "HH:MM:SS" -> seconds since midnight. GTFS times legitimately exceed 24h
 * ("24:39:00") for trips that run past midnight, so this must not wrap.
 * @param {string} hms
 * @returns {number | null}
 */
export function toSeconds(hms) {
  const m = /^(\d{1,3}):(\d{2}):(\d{2})$/.exec((hms || '').trim());
  if (!m) return null;
  return Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3]);
}

/** @param {string} v @returns {number | null} */
function toFloat(v) {
  if (v === undefined || v === null || v === '') return null;
  const n = Number(v);
  return Number.isNaN(n) ? null : n;
}

/** @param {string} v @returns {number | null} */
function toInt(v) {
  const n = toFloat(v);
  return n === null ? null : Math.trunc(n);
}

/**
 * Ask the portal's CKAN API for the newest GTFS zip.
 *
 * The dataset accumulates one resource per publication (60+ and counting), each
 * with its own UUID, so we sort by last_modified and take the freshest rather
 * than trusting any fixed URL — a pinned one had already gone 404.
 *
 * @returns {Promise<{ url: string, name: string | null }>}
 */
export async function resolveLatestFeed() {
  const url =
    `${config.gtfsPortalBase}/api/3/action/package_show` +
    `?id=${encodeURIComponent(config.gtfsDatasetId)}`;

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), config.httpTimeoutMs);
  try {
    const resp = await fetch(url, {
      headers: { 'User-Agent': config.userAgent },
      signal: controller.signal,
    });
    if (!resp.ok) throw new Error(`GTFS portal returned ${resp.status}`);
    const body = await resp.json();
    const resources = (body?.result?.resources ?? [])
      .filter((r) => (r.format ?? '').toLowerCase() === 'zip' || (r.url ?? '').endsWith('.zip'))
      .sort((a, b) =>
        String(a.last_modified ?? a.created ?? '').localeCompare(
          String(b.last_modified ?? b.created ?? ''),
        ),
      );
    if (resources.length === 0) throw new Error('GTFS portal listed no zip resources');
    const latest = resources[resources.length - 1];
    return { url: latest.url, name: latest.name ?? null };
  } finally {
    clearTimeout(timer);
  }
}

/**
 * @param {string} url
 * @returns {Promise<Buffer>}
 */
export async function downloadFeed(url) {
  const controller = new AbortController();
  // The zip is ~7 MB — a longer leash than a normal API call.
  const timer = setTimeout(() => controller.abort(), config.httpTimeoutMs * 3);
  try {
    const resp = await fetch(url, {
      headers: { 'User-Agent': config.userAgent },
      signal: controller.signal,
      redirect: 'follow',
    });
    if (!resp.ok) throw new Error(`GTFS download returned ${resp.status}`);
    return Buffer.from(await resp.arrayBuffer());
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Load a feed into the store, replacing whatever was there.
 *
 * @param {object} [opts]
 * @param {Buffer} [opts.zip]        an already-downloaded feed (tests, GTFS_URL=file)
 * @param {string} [opts.url]        where to download from; defaults to the newest published
 * @param {string} [opts.name]       the portal's label for the resource
 * @param {(msg: string) => void} [opts.log]
 * @returns {Promise<Record<string, number | string | null>>} row counts + feed identity
 */
export async function ingest(opts = {}) {
  const log = opts.log ?? (() => {});

  let { url, name = null } = opts;
  let buf = opts.zip;
  if (!buf) {
    if (!url) {
      if (config.gtfsUrl) {
        url = config.gtfsUrl;
      } else {
        const latest = await resolveLatestFeed();
        url = latest.url;
        name = latest.name;
      }
    }
    log(`downloading ${name ?? url}`);
    buf = await downloadFeed(url);
    log(`downloaded ${(buf.length / 1e6).toFixed(1)} MB`);
  }

  const zip = new AdmZip(buf);
  /** @param {string} f @returns {string} */
  const read = (f) => {
    const text = zip.readAsText(f);
    if (text === null || text === undefined) throw new Error(`feed is missing ${f}`);
    return text;
  };

  // --- assumptions this store is built on, checked before anything is written -

  // calendar.txt is empty in every feed seen so far: STCP expresses service
  // purely as dated exceptions in calendar_dates.txt, which is why there is no
  // `calendar` table. If that ever changes we would silently drop every weekly
  // service rule, so stop instead.
  const calendar = parseCsv(read('calendar.txt'));
  if (calendar.length > 0) {
    throw new Error(
      `calendar.txt has ${calendar.length} rows, but this store models service ` +
        'purely from calendar_dates.txt (README §2a). Add a `calendar` table ' +
        'before ingesting this feed.',
    );
  }

  const stopRows = parseCsv(read('stops.txt'));
  const divergent = stopRows.find((r) => r.stop_id !== r.stop_code);
  if (divergent) {
    throw new Error(
      `stops.txt row stop_id="${divergent.stop_id}" has a different stop_code ` +
        `("${divergent.stop_code}"). The store keys stops by stop_code on the ` +
        'assumption they are identical (README §2a); that no longer holds.',
    );
  }

  const feedInfo = parseCsv(read('feed_info.txt'))[0] ?? {};

  // --- write ----------------------------------------------------------------

  const db = getDb();
  const counts = {};
  db.exec('BEGIN IMMEDIATE');
  try {
    for (const t of ['feed_meta', 'stops', 'routes', 'trips', 'stop_times', 'shapes', 'service_dates', 'stop_routes']) {
      db.exec(`DELETE FROM ${t}`);
    }

    const insStop = db.prepare(
      'INSERT INTO stops (stop_code, name, lat, lon, zone_id) VALUES (?, ?, ?, ?, ?)',
    );
    let stops = 0;
    let dropped = 0;
    for (const r of stopRows) {
      const code = (r.stop_code || '').trim();
      const stopName = (r.stop_name || '').trim();
      if (JUNK.has(code) || JUNK.has(stopName)) {
        dropped++;
        continue;
      }
      insStop.run(code, stopName, toFloat(r.stop_lat), toFloat(r.stop_lon), (r.zone_id || '').trim() || null);
      stops++;
    }
    counts.stops = stops;
    counts.stops_dropped = dropped;

    const insRoute = db.prepare(
      'INSERT INTO routes (route_id, short_name, long_name, color, text_color, route_type, sort_order)' +
        ' VALUES (?, ?, ?, ?, ?, ?, ?)',
    );
    let routes = 0;
    for (const r of parseCsv(read('routes.txt'))) {
      const routeId = (r.route_id || '').trim();
      if (!routeId) continue;
      insRoute.run(
        routeId,
        (r.route_short_name || '').trim() || null,
        (r.route_long_name || '').trim() || null,
        toHexColor(r.route_color),
        toHexColor(r.route_text_color),
        toInt(r.route_type),
        toInt(r.route_sort_order),
      );
      routes++;
    }
    counts.routes = routes;

    const insTrip = db.prepare(
      'INSERT INTO trips (trip_id, route_id, service_id, headsign, direction_id, shape_id, block_id)' +
        ' VALUES (?, ?, ?, ?, ?, ?, ?)',
    );
    let trips = 0;
    for (const r of iterCsv(read('trips.txt'))) {
      const tripId = (r.trip_id || '').trim();
      if (!tripId) continue;
      insTrip.run(
        tripId,
        (r.route_id || '').trim(),
        (r.service_id || '').trim(),
        (r.trip_headsign || '').trim() || null,
        toInt(r.direction_id),
        (r.shape_id || '').trim() || null,
        (r.block_id || '').trim() || null,
      );
      trips++;
    }
    counts.trips = trips;

    // The big one. Streamed rather than materialised — see iterCsv.
    const insStopTime = db.prepare(
      'INSERT INTO stop_times (trip_id, stop_sequence, stop_code, arrival_time, departure_time,' +
        ' arrival_seconds, departure_seconds, timepoint, dist_traveled)' +
        ' VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
    );
    let stopTimes = 0;
    for (const r of iterCsv(read('stop_times.txt'))) {
      const tripId = (r.trip_id || '').trim();
      const seq = toInt(r.stop_sequence);
      if (!tripId || seq === null) continue;
      const arr = (r.arrival_time || '').trim();
      const dep = (r.departure_time || '').trim();
      const arrSec = toSeconds(arr);
      const depSec = toSeconds(dep);
      if (arrSec === null || depSec === null) continue;
      insStopTime.run(
        tripId,
        seq,
        (r.stop_id || '').trim(),
        arr,
        dep,
        arrSec,
        depSec,
        toInt(r.timepoint),
        toFloat(r.shape_dist_traveled),
      );
      stopTimes++;
    }
    counts.stop_times = stopTimes;

    const insShape = db.prepare(
      'INSERT INTO shapes (shape_id, sequence, lat, lon, dist_traveled) VALUES (?, ?, ?, ?, ?)',
    );
    let shapes = 0;
    for (const r of iterCsv(read('shapes.txt'))) {
      const shapeId = (r.shape_id || '').trim();
      const seq = toInt(r.shape_pt_sequence);
      const lat = toFloat(r.shape_pt_lat);
      const lon = toFloat(r.shape_pt_lon);
      if (!shapeId || seq === null || lat === null || lon === null) continue;
      insShape.run(shapeId, seq, lat, lon, toFloat(r.shape_dist_traveled));
      shapes++;
    }
    counts.shapes = shapes;

    const insServiceDate = db.prepare(
      'INSERT INTO service_dates (service_id, date, exception_type) VALUES (?, ?, ?)',
    );
    let serviceDates = 0;
    for (const r of parseCsv(read('calendar_dates.txt'))) {
      const serviceId = (r.service_id || '').trim();
      const date = (r.date || '').trim();
      if (!serviceId || !date) continue;
      insServiceDate.run(serviceId, date, toInt(r.exception_type) ?? 1);
      serviceDates++;
    }
    counts.service_dates = serviceDates;

    // Derive stop -> routes once, now that stop_times and trips are both loaded.
    // Done in SQL rather than JS: the join never leaves SQLite, and the whole
    // collapse takes well under a second against the 850k-row table.
    db.exec(
      'INSERT OR IGNORE INTO stop_routes (stop_code, route_id)' +
        ' SELECT DISTINCT st.stop_code, t.route_id' +
        ' FROM stop_times st JOIN trips t ON t.trip_id = st.trip_id',
    );
    counts.stop_routes = Number(
      db.prepare('SELECT COUNT(*) AS n FROM stop_routes').get()?.n ?? 0,
    );

    db.prepare(
      'INSERT INTO feed_meta (id, resource_name, source_url, feed_version, feed_start_date,' +
        ' feed_end_date, ingested_at) VALUES (1, ?, ?, ?, ?, ?, ?)',
    ).run(
      name,
      url ?? '(supplied buffer)',
      (feedInfo.feed_version || '').trim() || null,
      (feedInfo.feed_start_date || '').trim() || null,
      (feedInfo.feed_end_date || '').trim() || null,
      new Date().toISOString(),
    );

    db.exec('COMMIT');
  } catch (err) {
    db.exec('ROLLBACK');
    throw err;
  }

  counts.feed_start_date = (feedInfo.feed_start_date || '').trim() || null;
  counts.feed_end_date = (feedInfo.feed_end_date || '').trim() || null;
  return counts;
}
