/**
 * GTFS client.
 *
 * Downloads the official STCP GTFS zip from Porto's open-data portal and keeps a
 * parsed copy in memory (stops + routes), refreshed after a TTL. GTFS is the
 * static source of truth: stops, lines, coordinates. Stable and legal to use.
 *
 * @typedef {import('../../types/domain').Stop} Stop
 * @typedef {import('../../types/domain').Line} Line
 */
import AdmZip from 'adm-zip';
import { config } from '../config.js';
import { parseCsv } from '../lib/csv.js';

/** @type {{ loadedAt: number, sourceUrl: string | null, sourceName: string | null, stops: Map<string, Stop>, lines: Map<string, Line>, linesByShort: Map<string, Line> }} */
const cache = {
  loadedAt: 0,
  sourceUrl: null,
  sourceName: null,
  stops: new Map(),
  lines: new Map(),
  linesByShort: new Map(),
};

/** @param {string} v @returns {number | null} */
function toFloat(v) {
  if (v === undefined || v === null || v === '') return null;
  const n = Number(v);
  return Number.isNaN(n) ? null : n;
}

function isStale() {
  return Date.now() - cache.loadedAt > config.gtfsTtlSeconds * 1000;
}

async function ensureLoaded() {
  if (cache.stops.size > 0 && !isStale()) return;
  await load();
}

/**
 * Ask the portal's CKAN API for the newest GTFS zip.
 *
 * The dataset accumulates one resource per publication (55+ and counting), each
 * with its own UUID, so we sort by last_modified and take the freshest rather
 * than trusting any fixed URL.
 *
 * @returns {Promise<string>} download URL
 */
async function resolveLatestGtfsUrl() {
  const url =
    `${config.gtfsPortalBase}/api/3/action/package_show` +
    `?id=${encodeURIComponent(config.gtfsDatasetId)}`;

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), config.httpTimeoutMs);
  try {
    const resp = await fetch(url, {
      headers: { 'User-Agent': config.userAgent, Accept: 'application/json' },
      signal: controller.signal,
    });
    if (!resp.ok) throw new Error(`GTFS dataset lookup returned ${resp.status}`);
    const body = await resp.json();

    const resources = (body?.result?.resources ?? []).filter(
      (r) => ['GTFS', 'ZIP'].includes(String(r?.format ?? '').toUpperCase()) && r?.url,
    );
    if (resources.length === 0) throw new Error('GTFS dataset has no zip resources');

    resources.sort((a, b) =>
      String(a.last_modified ?? a.created ?? '').localeCompare(String(b.last_modified ?? b.created ?? '')),
    );
    const latest = resources[resources.length - 1];
    cache.sourceName = latest.name ?? null;
    return latest.url;
  } finally {
    clearTimeout(timer);
  }
}

async function load() {
  const gtfsUrl = config.gtfsUrl ?? (await resolveLatestGtfsUrl());
  cache.sourceUrl = gtfsUrl;

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), config.httpTimeoutMs * 3); // zip is bigger
  let buf;
  try {
    const resp = await fetch(gtfsUrl, {
      headers: { 'User-Agent': config.userAgent },
      signal: controller.signal,
      redirect: 'follow',
    });
    if (!resp.ok) throw new Error(`GTFS download returned ${resp.status}`);
    buf = Buffer.from(await resp.arrayBuffer());
  } finally {
    clearTimeout(timer);
  }

  const zip = new AdmZip(buf);
  parseStops(zip.readAsText('stops.txt'));
  parseRoutes(zip.readAsText('routes.txt'));
  cache.loadedAt = Date.now();
}

/** @param {string} text */
function parseStops(text) {
  const stops = new Map();
  for (const row of parseCsv(text)) {
    const code = (row.stop_code || row.stop_id || '').trim();
    if (!code) continue;
    stops.set(code, {
      stop_code: code,
      name: (row.stop_name || '').trim(),
      lat: toFloat(row.stop_lat),
      lon: toFloat(row.stop_lon),
    });
  }
  cache.stops = stops;
}

/** @param {string} text */
function parseRoutes(text) {
  const lines = new Map();
  const byShort = new Map();
  for (const row of parseCsv(text)) {
    const routeId = (row.route_id || '').trim();
    if (!routeId) continue;
    const short = (row.route_short_name || '').trim();
    /** @type {Line} */
    const line = {
      line: short || routeId,
      description: (row.route_long_name || '').trim(),
      route_id: routeId,
    };
    lines.set(routeId, line);
    if (short) byShort.set(short, line);
  }
  cache.lines = lines;
  cache.linesByShort = byShort;
}

// ---- public API -----------------------------------------------------------

/** @returns {Promise<Stop[]>} */
export async function getStops() {
  await ensureLoaded();
  return [...cache.stops.values()];
}

/** @param {string} stopCode @returns {Promise<Stop | null>} */
export async function getStop(stopCode) {
  await ensureLoaded();
  return cache.stops.get(stopCode) ?? null;
}

/** @returns {Promise<Line[]>} */
export async function getLines() {
  await ensureLoaded();
  return [...cache.lines.values()];
}

/** Which feed we're actually serving, for /health and debugging. */
export function getFeedInfo() {
  return {
    source_name: cache.sourceName,
    source_url: cache.sourceUrl,
    loaded_at: cache.loadedAt ? new Date(cache.loadedAt).toISOString() : null,
    stops: cache.stops.size,
    lines: cache.lines.size,
  };
}
