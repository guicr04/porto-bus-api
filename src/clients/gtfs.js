/**
 * GTFS client — static data, read from the local store (README §2a).
 *
 * This file used to download and parse the feed itself, keeping stops and routes
 * in two in-memory Maps behind a TTL. It no longer downloads anything: the whole
 * feed is ingested into SQLite by scripts/gtfs-refresh.js, and this is a thin
 * read layer over it.
 *
 * The exported surface is deliberately unchanged from the in-memory version, so
 * that swapping the storage again (SQLite -> Postgres, the day this runs as more
 * than one instance) stays a change to this one file.
 *
 * @typedef {import('../../types/domain').Stop} Stop
 * @typedef {import('../../types/domain').Line} Line
 */
import { getDb, getFeedMeta } from '../db/index.js';

export { toHexColor } from '../lib/color.js';

/** @param {Record<string, any>} row @returns {Stop} */
function toStop(row) {
  return {
    stop_code: row.stop_code,
    name: row.name,
    lat: row.lat ?? null,
    lon: row.lon ?? null,
  };
}

/** @param {Record<string, any>} row @returns {Line} */
function toLine(row) {
  return {
    line: row.short_name || row.route_id,
    description: row.long_name ?? '',
    route_id: row.route_id,
    color: row.color ?? null,
    text_color: row.text_color ?? null,
  };
}

/** @returns {Promise<Stop[]>} */
export async function getStops() {
  return getDb().prepare('SELECT stop_code, name, lat, lon FROM stops ORDER BY stop_code').all().map(toStop);
}

/** @param {string} stopCode @returns {Promise<Stop | null>} */
export async function getStop(stopCode) {
  const row = getDb()
    .prepare('SELECT stop_code, name, lat, lon FROM stops WHERE stop_code = ?')
    .get(stopCode);
  return row ? toStop(row) : null;
}

/**
 * Stops inside a bounding box, for the map. This is the reason `stops(lat, lon)`
 * is indexed: it turns "every stop on screen" into an index range scan instead
 * of a filter over all 2,568 rows, and means a client never has to hold the
 * whole network in memory to draw part of it.
 *
 * @param {{ minLat: number, minLon: number, maxLat: number, maxLon: number }} box
 * @param {number} [limit]
 * @returns {Promise<Stop[]>}
 */
export async function getStopsInBBox(box, limit = 2000) {
  return getDb()
    .prepare(
      'SELECT stop_code, name, lat, lon FROM stops' +
        ' WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?' +
        ' ORDER BY stop_code LIMIT ?',
    )
    .all(box.minLat, box.maxLat, box.minLon, box.maxLon, limit)
    .map(toStop);
}

/**
 * Which lines serve each stop inside a box.
 *
 * One query for the whole visible region, rather than one request per stop:
 * the map labels pins with line numbers at close zoom, and 15 round trips to
 * draw one screen would be indefensible. Cheap because `stop_routes` is
 * precomputed at ingest (README §2a).
 *
 * @param {{ minLat: number, minLon: number, maxLat: number, maxLon: number }} box
 * @returns {Promise<Array<{ stop_code: string, lines: Array<{ line: string, color: string | null, text_color: string | null }> }>>}
 */
export async function getStopLinesInBBox(box) {
  const rows = getDb()
    .prepare(
      // COALESCE, not short_name: a handful of routes carry no route_short_name,
      // and a null here would be a badge with nothing written on it — or, for a
      // client whose model says the line is a String, a decode failure that
      // takes the whole region's labels down.
      'SELECT sr.stop_code, COALESCE(r.short_name, r.route_id) AS line, r.color, r.text_color' +
        ' FROM stop_routes sr JOIN routes r ON r.route_id = sr.route_id' +
        ' WHERE sr.stop_code IN (SELECT stop_code FROM stops' +
        '   WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?)' +
        ' ORDER BY sr.stop_code, r.sort_order, r.short_name',
    )
    .all(box.minLat, box.maxLat, box.minLon, box.maxLon);

  /** @type {Map<string, any[]>} */
  const byStop = new Map();
  for (const r of rows) {
    if (!byStop.has(r.stop_code)) byStop.set(r.stop_code, []);
    byStop.get(r.stop_code).push({
      line: r.line,
      color: r.color ?? null,
      text_color: r.text_color ?? null,
    });
  }
  return [...byStop].map(([stop_code, lines]) => ({ stop_code, lines }));
}

/**
 * Free-text search on stop name. Case-insensitive for ASCII via SQLite's LIKE;
 * Porto's stop names are upper-case in the feed, so that is enough.
 * @param {string} query @param {number} [limit] @returns {Promise<Stop[]>}
 */
export async function searchStops(query, limit = 100) {
  return getDb()
    .prepare(
      'SELECT stop_code, name, lat, lon FROM stops WHERE name LIKE ? ORDER BY stop_code LIMIT ?',
    )
    .all(`%${query}%`, limit)
    .map(toStop);
}

/** @returns {Promise<Line[]>} */
export async function getLines() {
  return getDb()
    .prepare('SELECT route_id, short_name, long_name, color, text_color FROM routes ORDER BY sort_order, short_name')
    .all()
    .map(toLine);
}

/**
 * One line by its rider-facing short name ("500"), falling back to route_id.
 * @param {string} shortName @returns {Promise<Line | null>}
 */
export async function getLine(shortName) {
  const db = getDb();
  const sql = 'SELECT route_id, short_name, long_name, color, text_color FROM routes WHERE ';
  const row =
    db.prepare(`${sql}short_name = ?`).get(shortName) ?? db.prepare(`${sql}route_id = ?`).get(shortName);
  return row ? toLine(row) : null;
}

/** Which feed we're actually serving, for /health and debugging. */
export function getFeedInfo() {
  const db = getDb();
  const meta = getFeedMeta();
  const count = (t) => Number(db.prepare(`SELECT COUNT(*) AS n FROM ${t}`).get()?.n ?? 0);
  return {
    source_name: meta?.resource_name ?? null,
    source_url: meta?.source_url ?? null,
    feed_version: meta?.feed_version ?? null,
    feed_start_date: meta?.feed_start_date ?? null,
    feed_end_date: meta?.feed_end_date ?? null,
    loaded_at: meta?.ingested_at ?? null,
    stops: count('stops'),
    lines: count('routes'),
    trips: count('trips'),
    stop_times: count('stop_times'),
  };
}
