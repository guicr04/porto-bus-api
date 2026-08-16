/**
 * The static store — one SQLite file holding the whole GTFS feed.
 *
 * Driver: `node:sqlite`, built into Node, rather than better-sqlite3. That was
 * not the first choice: better-sqlite3 is the more established option, but its
 * darwin-arm64 prebuild segfaults on open here (Node 22.12 / modules 127), and
 * `npm rebuild --build-from-source` silently reuses the same prebuild. Depending
 * on a native build that has to work on a dev Mac *and* in the container, to get
 * an API we already have in the runtime, is a bad trade. Cost of the swap: Node
 * 22 needs `--experimental-sqlite` (unflagged from Node 24), which lives in the
 * npm scripts.
 *
 * Opened once and reused. Only scripts/gtfs-refresh.js writes.
 */
import { DatabaseSync } from 'node:sqlite';
import { readFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { config } from '../config.js';

const here = dirname(fileURLToPath(import.meta.url));
const SCHEMA_PATH = join(here, 'schema.sql');

/** @type {DatabaseSync | null} */
let db = null;

/**
 * Open the store, creating the file and schema if absent.
 *
 * WAL matters for more than speed: it lets the daily ingest hold a write
 * transaction over ~850k rows without blocking a single reader, which is what
 * makes "replace everything in one transaction" safe to run against a live API.
 * @returns {DatabaseSync}
 */
export function getDb() {
  if (db) return db;

  mkdirSync(dirname(config.dbPath), { recursive: true });
  db = new DatabaseSync(config.dbPath);
  db.exec('PRAGMA journal_mode = WAL');
  // NORMAL rather than FULL: the store is a derived artifact. Losing the last
  // fsync to a power cut costs a re-ingest, not data.
  db.exec('PRAGMA synchronous = NORMAL');
  db.exec('PRAGMA foreign_keys = ON');
  db.exec(readFileSync(SCHEMA_PATH, 'utf8'));
  return db;
}

/** Close the store. Used by the ingest script and by tests. */
export function closeDb() {
  if (db) {
    db.close();
    db = null;
  }
}

/**
 * What feed the store was built from, or null when it has never been ingested.
 * @returns {{ resource_name: string | null, source_url: string, feed_version: string | null,
 *             feed_start_date: string | null, feed_end_date: string | null,
 *             ingested_at: string } | null}
 */
export function getFeedMeta() {
  const row = getDb().prepare('SELECT * FROM feed_meta WHERE id = 1').get();
  return row ? { ...row } : null;
}

/**
 * True when the store has no feed, or its feed is older than the TTL.
 * Drives the boot-time refresh, so a fresh clone works without a scheduler.
 * @returns {boolean}
 */
export function isStale() {
  const meta = getFeedMeta();
  if (!meta) return true;
  const ageMs = Date.now() - Date.parse(meta.ingested_at);
  return !Number.isFinite(ageMs) || ageMs > config.gtfsTtlSeconds * 1000;
}

/**
 * True when today is past the feed's own validity window — the case where
 * serving the timetable would be a guess wearing a schedule's clothes. Distinct
 * from `isStale`: a feed can be freshly ingested and still be expired, which is
 * exactly what happens when the portal stops republishing.
 * @param {Date} [now]
 * @returns {boolean}
 */
export function isFeedExpired(now = new Date()) {
  const meta = getFeedMeta();
  if (!meta?.feed_end_date) return false;
  const today =
    `${now.getFullYear()}` +
    `${String(now.getMonth() + 1).padStart(2, '0')}` +
    `${String(now.getDate()).padStart(2, '0')}`;
  return today > meta.feed_end_date;
}

/** Has the store ever been populated? @returns {boolean} */
export function hasData() {
  const row = getDb().prepare('SELECT COUNT(*) AS n FROM stops').get();
  return Number(row?.n ?? 0) > 0;
}
