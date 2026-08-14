import { Router } from 'express';
import * as gtfs from '../clients/gtfs.js';
import * as route from '../clients/route.js';

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
    res.json(await route.routeStops(req.params.line, dir(req)));
  } catch (err) {
    next(err);
  }
});

// GET /lines/:line/shape?direction_id=  — map polyline
linesRouter.get('/:line/shape', async (req, res, next) => {
  try {
    res.json(await route.routeShape(req.params.line, dir(req)));
  } catch (err) {
    next(err);
  }
});

// GET /lines/:line/services?date=  — service_ids running that day
linesRouter.get('/:line/services', async (req, res, next) => {
  try {
    const date = typeof req.query.date === 'string' ? req.query.date : undefined;
    res.json(await route.routeServices(req.params.line, date));
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
