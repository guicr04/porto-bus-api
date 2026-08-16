import { Router } from 'express';
import { resolvedTripStops } from '../db/trips.js';
import { hasData } from '../db/index.js';

export const tripsRouter = Router();

/**
 * GET /trips/:tripId/stops?line=&headsign=&stop=&eta_minutes=
 *
 * The ordered stops of one live bus's journey, with the scheduled time at each.
 * Store-only: there is no upstream equivalent, because STCP's live API is
 * stop-centric and never answers "where does this vehicle go next".
 *
 * The query params are not filters — they are the fallback identity to use when
 * the id join misses (README §2a). They come straight off the live board row the
 * user tapped, so passing them costs the client nothing and is the difference
 * between a degraded screen and an empty one.
 */
function handle(req, res, tripId) {
  if (!hasData()) {
    return res.status(503).json({ detail: 'The static store has not been ingested yet' });
  }

  const etaRaw = req.query.eta_minutes;
  const result = resolvedTripStops(tripId, {
    line: typeof req.query.line === 'string' ? req.query.line : undefined,
    headsign: typeof req.query.headsign === 'string' ? req.query.headsign : undefined,
    stopCode: typeof req.query.stop === 'string' ? req.query.stop : undefined,
    etaMinutes: etaRaw === undefined ? undefined : Number(etaRaw),
  });

  if (!result) {
    // A miss here is genuinely "we cannot identify this bus", and the client
    // has a defined answer for that: show the line's stops without times.
    // Saying so as a 404 is what lets it tell that apart from a server fault.
    const named = tripId === null ? 'that departure' : `trip '${tripId}'`;
    return res.status(404).json({ detail: `Could not resolve ${named} in the static feed` });
  }
  res.json(result);
}

/**
 * GET /trips/stops?line=&stop=&eta_minutes=&headsign=
 *
 * The same answer for a departure that has no id to ask by. Scheduled rows on
 * `/stops/{code}/departures` are the case that needs it: upstream's timetable
 * is times and headsigns only, so there is nothing to put in the path — but
 * line + stop + departure time identify the trip perfectly well, and that is
 * already the documented fallback rung (`match: "pattern"`).
 *
 * No route conflict with `/:tripId/stops` below: that one needs two path
 * segments after `/trips`, this needs one.
 */
tripsRouter.get('/stops', (req, res, next) => {
  try {
    handle(req, res, null);
  } catch (err) {
    next(err);
  }
});

tripsRouter.get('/:tripId/stops', (req, res, next) => {
  try {
    handle(req, res, req.params.tripId);
  } catch (err) {
    next(err);
  }
});
