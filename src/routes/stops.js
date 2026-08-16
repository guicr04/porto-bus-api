import { Router } from 'express';
import * as gtfs from '../clients/gtfs.js';
import * as stcp from '../clients/stcp.js';
import * as live from '../services/live.js';
import { stopLineDepartures } from '../services/departures.js';

export const stopsRouter = Router();

/**
 * Parse `bbox=minLon,minLat,maxLon,maxLat` (GeoJSON order, which is what MapKit
 * and every mapping client hands you). Returns null when absent, or an { error }
 * so a malformed box is a 400 rather than a silently empty map.
 * @param {unknown} raw
 */
function parseBBox(raw) {
  if (typeof raw !== 'string' || raw === '') return null;
  const parts = raw.split(',').map((v) => Number(v.trim()));
  if (parts.length !== 4 || parts.some((n) => !Number.isFinite(n))) {
    return { error: 'bbox must be four numbers: minLon,minLat,maxLon,maxLat' };
  }
  const [minLon, minLat, maxLon, maxLat] = parts;
  if (minLat > maxLat || minLon > maxLon) {
    return { error: 'bbox is inverted: expected minLon,minLat,maxLon,maxLat' };
  }
  return { minLat, minLon, maxLat, maxLon };
}

// GET /stops?q=&bbox=&limit=
//
// The limit defaults low but tops out above the network's real size. It used to
// clamp at 2000, which silently truncated Porto's 2,568 stops — a cap that looks
// like a full answer is worse than an error.
stopsRouter.get('/', async (req, res, next) => {
  try {
    const q = typeof req.query.q === 'string' ? req.query.q.trim() : null;
    const box = parseBBox(req.query.bbox);
    if (box && 'error' in box) return res.status(400).json({ detail: box.error });

    // A bbox is already a bound, and a map that draws 100 of the 178 stops in
    // view is wrong in a way the client cannot detect. So bbox requests default
    // to "everything in the box"; an explicit ?limit= still wins.
    const defaultLimit = box ? 5000 : 100;
    const limit = Math.min(Math.max(Number(req.query.limit ?? defaultLimit), 1), 5000);

    if (box) {
      const stops = await gtfs.getStopsInBBox(box, limit);
      return res.json(q ? stops.filter((s) => s.name.toLowerCase().includes(q.toLowerCase())) : stops);
    }
    if (q) return res.json(await gtfs.searchStops(q, limit));
    res.json((await gtfs.getStops()).slice(0, limit));
  } catch (err) {
    next(err);
  }
});

// GET /stops/lines?bbox=  — which lines serve each stop in a region.
//
// MUST stay declared above `/:code`, or Express routes it there as a stop whose
// code is "lines". (No such stop exists in the feed; the ordering is what keeps
// that true rather than luck.)
stopsRouter.get('/lines', async (req, res, next) => {
  try {
    const box = parseBBox(req.query.bbox);
    if (!box) return res.status(400).json({ detail: 'bbox is required: minLon,minLat,maxLon,maxLat' });
    if ('error' in box) return res.status(400).json({ detail: box.error });
    res.json(await gtfs.getStopLinesInBBox(box));
  } catch (err) {
    next(err);
  }
});

// GET /stops/:code
stopsRouter.get('/:code', async (req, res, next) => {
  try {
    const stop = await gtfs.getStop(req.params.code);
    if (!stop) return res.status(404).json({ detail: `Stop '${req.params.code}' not found` });
    res.json(stop);
  } catch (err) {
    next(err);
  }
});

// GET /stops/:code/realtime
//
// Live when STCP answers; today's timetable, tagged data_source="scheduled",
// when it doesn't. A client must render the difference (README §2a).
stopsRouter.get('/:code/realtime', async (req, res, next) => {
  try {
    res.json(await live.stopBoard(req.params.code));
  } catch (err) {
    next(err);
  }
});

// GET /stops/:code/routes
stopsRouter.get('/:code/routes', async (req, res, next) => {
  try {
    res.json(await stcp.stopRoutes(req.params.code));
  } catch (err) {
    next(err);
  }
});

// GET /stops/:code/services?date=YYYY-MM-DD
stopsRouter.get('/:code/services', async (req, res, next) => {
  try {
    const date = typeof req.query.date === 'string' ? req.query.date : undefined;
    res.json(await stcp.stopServices(req.params.code, date));
  } catch (err) {
    next(err);
  }
});

// GET /stops/:code/departures?line=&service_id=&direction_id=&window_minutes=&limit=
// Combined live + scheduled view for one line at one stop. Each departure is
// tagged source: "realtime" | "scheduled".
stopsRouter.get('/:code/departures', async (req, res, next) => {
  try {
    const line = req.query.line;
    if (typeof line !== 'string' || line === '') {
      return res.status(400).json({ detail: 'line query param is required' });
    }
    /** @type {{ serviceId?: string, directionId?: number, windowMinutes?: number, limit?: number }} */
    const opts = {};
    if (typeof req.query.service_id === 'string') opts.serviceId = req.query.service_id;
    if (req.query.direction_id !== undefined) opts.directionId = Number(req.query.direction_id);
    if (req.query.window_minutes !== undefined) opts.windowMinutes = Number(req.query.window_minutes);
    if (req.query.limit !== undefined) opts.limit = Number(req.query.limit);
    res.json(await stopLineDepartures(req.params.code, line, opts));
  } catch (err) {
    next(err);
  }
});

// GET /stops/:code/schedule?route_id=&service_id=&direction_id=
stopsRouter.get('/:code/schedule', async (req, res, next) => {
  try {
    const routeId = req.query.route_id;
    const serviceId = req.query.service_id;
    if (typeof routeId !== 'string' || typeof serviceId !== 'string') {
      return res.status(400).json({ detail: 'route_id and service_id are required' });
    }
    const directionId = Number(req.query.direction_id ?? 0);
    res.json(await stcp.stopSchedule(req.params.code, routeId, serviceId, directionId));
  } catch (err) {
    next(err);
  }
});
