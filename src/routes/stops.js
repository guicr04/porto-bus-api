import { Router } from 'express';
import * as gtfs from '../clients/gtfs.js';
import * as stcp from '../clients/stcp.js';
import { stopLineDepartures } from '../services/departures.js';

export const stopsRouter = Router();

// GET /stops?q=&limit=
stopsRouter.get('/', async (req, res, next) => {
  try {
    const q = typeof req.query.q === 'string' ? req.query.q.toLowerCase() : null;
    const limit = Math.min(Math.max(Number(req.query.limit ?? 100), 1), 2000);
    let stops = await gtfs.getStops();
    if (q) stops = stops.filter((s) => s.name.toLowerCase().includes(q));
    res.json(stops.slice(0, limit));
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
stopsRouter.get('/:code/realtime', async (req, res, next) => {
  try {
    res.json(await stcp.stopRealtime(req.params.code));
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
