import { Router } from 'express';
import * as gtfs from '../clients/gtfs.js';
import * as route from '../clients/route.js';
import * as storeLines from '../db/lines.js';
import { isUpstreamOutage } from '../services/live.js';
import { activeServiceIds } from '../db/schedule.js';
import { isFeedExpired, hasData } from '../db/index.js';
import { lisbonDateStamp } from '../lib/time.js';

/**
 * Live-first with the store behind it, for the line-centric reads.
 *
 * `fromStore` returning null means "the store has nothing for this line" — a
 * 404 rather than a 502, since the upstream failure is no longer the
 * interesting fact. An expired feed disqualifies the store entirely: a
 * timetable outside its validity window is a guess, not data (README §2a).
 *
 * @template T
 * @param {() => Promise<T>} live
 * @param {() => T | null} fromStore
 * @param {string} notFound  what to say when the store has nothing for this line
 * @returns {Promise<{ value: T, source: 'realtime' | 'scheduled' }>}
 */
async function liveOrStore(live, fromStore, notFound) {
  try {
    return { value: await live(), source: 'realtime' };
  } catch (err) {
    if (!isUpstreamOutage(err)) throw err;
    if (!hasData() || isFeedExpired()) throw err;
    const value = fromStore();
    if (value === null) {
      // Report what the client can act on, not the upstream stack trace: the
      // fetch failure is incidental once we know the line doesn't exist here.
      const err404 = new Error(notFound);
      err404.status = 404;
      throw err404;
    }
    console.error(`[lines] serving from the static store: ${err.message}`);
    return { value, source: 'scheduled' };
  }
}

export const linesRouter = Router();

// GET /lines  — all lines, from the official GTFS feed
linesRouter.get('/', async (_req, res, next) => {
  try {
    res.json(await gtfs.getLines());
  } catch (err) {
    next(err);
  }
});

/** parse a 0/1 direction query param, defaulting to 0 */
function dir(req) {
  return Math.min(Math.max(Number(req.query.direction_id ?? 0), 0), 1);
}

// GET /lines/:line/stops?direction_id=  — full ordered stop list for a direction
linesRouter.get('/:line/stops', async (req, res, next) => {
  try {
    const { value, source } = await liveOrStore(
      () => route.routeStops(req.params.line, dir(req)),
      () => storeLines.lineStops(req.params.line, dir(req)),
      `No stops for line '${req.params.line}' direction ${dir(req)}`,
    );
    res.json({ ...value, data_source: source });
  } catch (err) {
    next(err);
  }
});

// GET /lines/:line/shape?direction_id=  — map polyline
linesRouter.get('/:line/shape', async (req, res, next) => {
  try {
    const { value, source } = await liveOrStore(
      () => route.routeShape(req.params.line, dir(req)),
      () => storeLines.lineShape(req.params.line, dir(req)),
      `No shape for line '${req.params.line}' direction ${dir(req)}`,
    );
    res.json({ ...value, data_source: source });
  } catch (err) {
    next(err);
  }
});

// GET /lines/:line/services?date=  — service_ids running that day
linesRouter.get('/:line/services', async (req, res, next) => {
  try {
    const date = typeof req.query.date === 'string' ? req.query.date : undefined;
    const { value, source } = await liveOrStore(
      () => route.routeServices(req.params.line, date),
      () => {
        const stamp = date ? date.replace(/-/g, '') : lisbonDateStamp();
        const services = activeServiceIds(stamp);
        const routeId = storeLines.resolveRouteId(req.params.line);
        if (!routeId) return null;
        return {
          route_id: routeId,
          services,
          active_service_id: services[0] ?? null,
          selected_date: stamp,
          today: lisbonDateStamp(),
        };
      },
      `Line '${req.params.line}' not found`,
    );
    res.json({ ...value, data_source: source });
  } catch (err) {
    next(err);
  }
});

// GET /lines/:line/schedule?service_id=&direction_id=  — full timetable grid
linesRouter.get('/:line/schedule', async (req, res, next) => {
  try {
    const serviceId = req.query.service_id;
    if (typeof serviceId !== 'string') {
      return res.status(400).json({ detail: 'service_id is required' });
    }
    res.json(await route.routeSchedule(req.params.line, serviceId, dir(req)));
  } catch (err) {
    next(err);
  }
});
