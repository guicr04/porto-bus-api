import { Router } from 'express';
import { config } from '../config.js';
import { locationBoard } from '../services/board.js';
import { renderBoard } from '../lib/board.js';

export const boardRouter = Router();

/** Read a number query param, or fall back. */
function num(v, fallback) {
  if (v === undefined || v === '') return fallback;
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

/** Read a boolean-ish query param ("1", "true", "yes"). */
function bool(v, fallback) {
  if (v === undefined || v === '') return fallback;
  return ['1', 'true', 'yes'].includes(String(v).toLowerCase());
}

/**
 * Resolve the origin: explicit lat/lon, else the configured home location.
 * @returns {{ lat: number, lon: number } | { error: string }}
 */
function resolveOrigin(req) {
  const lat = num(req.query.lat, config.homeLat);
  const lon = num(req.query.lon, config.homeLon);
  if (lat === null || lon === null || lat === undefined || lon === undefined) {
    return {
      error:
        'No origin. Pass ?lat=&lon=, or set HOME_LAT/HOME_LON in .env ' +
        '(find them for an address with: make geocode ADDRESS="...").',
    };
  }
  return { lat, lon };
}

/** Shared option parsing for both representations of the board. */
function boardOptions(req, origin) {
  return {
    lat: origin.lat,
    lon: origin.lon,
    walkMinutes: num(req.query.walk_minutes, 10),
    limit: num(req.query.limit, 10),
    maxStops: num(req.query.max_stops, 12),
    buffer: num(req.query.buffer, 0),
    includeUnreachable: bool(req.query.include_unreachable, false),
    collapse: bool(req.query.collapse, true),
    metersPerMinute: num(req.query.walk_speed, undefined),
    sort: req.query.sort === 'eta' ? 'eta' : 'line',
  };
}

// Mounted at the root rather than under a "/board" prefix, because `app.use`
// would not route "/board.txt" to a router mounted on "/board".

// GET /board — JSON. What an app or the device's firmware consumes.
boardRouter.get('/board', async (req, res, next) => {
  try {
    const origin = resolveOrigin(req);
    if ('error' in origin) return res.status(400).json({ detail: origin.error });
    res.json(await locationBoard(boardOptions(req, origin)));
  } catch (err) {
    next(err);
  }
});

// GET /board.txt — fixed-width text, ready to push straight at a display.
// Served as text/plain so `curl` and a microcontroller both get something usable
// without a JSON parser.
boardRouter.get('/board.txt', async (req, res, next) => {
  try {
    const origin = resolveOrigin(req);
    if ('error' in origin) return res.status(400).type('text/plain').send(origin.error);

    const board = await locationBoard(boardOptions(req, origin));
    const text = renderBoard(board.departures, {
      width: num(req.query.width, 42),
      title: req.query.title ?? config.homeLabel,
      // Off by default: ANSI is for a terminal, and a microcontroller driving an
      // LED panel wants the `realtime` flag from /board, not escape codes it would
      // have to strip. `make board` turns it on.
      color: bool(req.query.color, false),
    });
    res.type('text/plain').send(text + '\n');
  } catch (err) {
    next(err);
  }
});
