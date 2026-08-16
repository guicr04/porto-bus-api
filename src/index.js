import express from 'express';
import { config } from './config.js';
import { stopsRouter } from './routes/stops.js';
import { linesRouter } from './routes/lines.js';
import { boardRouter } from './routes/board.js';
import { tripsRouter } from './routes/trips.js';
import { getFeedInfo } from './clients/gtfs.js';
import { hasData, isStale, isFeedExpired } from './db/index.js';
import { ingest } from './db/ingest.js';
import { breakerState } from './services/live.js';

const app = express();

/**
 * Liveness, plus which static feed is being served. `feed_expired` is the field
 * worth alerting on: the store can be freshly ingested and still hold a feed
 * whose validity window has passed, because the portal stopped republishing.
 */
app.get('/health', (_req, res) => {
  res.json({
    status: 'ok',
    gtfs: { ...getFeedInfo(), feed_expired: isFeedExpired() },
    upstream: breakerState(),
  });
});

app.use('/stops', stopsRouter);
app.use('/lines', linesRouter);
app.use('/trips', tripsRouter);
app.use('/', boardRouter);

// Central error handler.
//
// An error that already knows its status keeps it: "this stop does not exist"
// is a 404 and "STCP is down and the store can't stand in" is a 503, and
// flattening either to 502 tells the client to retry something that will never
// succeed. Anything else is an unclassified upstream failure — and upstream is
// either stcp.pt (live) or opendata.porto.digital (GTFS), so the message stays
// generic; naming the wrong one sends you debugging in the wrong direction.
app.use((err, _req, res, _next) => {
  console.error(err);
  const status = Number(err?.status);
  if (Number.isFinite(status) && status >= 400 && status < 600) {
    return res.status(status).json({ detail: err.message });
  }
  res.status(502).json({ detail: `Upstream request failed: ${err.message}` });
});

/**
 * Fill the store at boot when it is empty or past its TTL.
 *
 * A fresh clone must work with no scheduler configured, so the daily cron is an
 * optimisation rather than a dependency. An empty store blocks startup — there
 * is nothing to serve without it — but a merely *stale* one does not: yesterday's
 * timetable is far better than a server that won't come up, so that refresh runs
 * in the background and the API starts immediately.
 */
async function prepareStore() {
  const empty = !hasData();
  if (empty) {
    console.log('[gtfs] store is empty — ingesting before accepting traffic');
    await ingest({ log: (m) => console.log(`[gtfs] ${m}`) });
    console.log('[gtfs] ready');
    return;
  }
  if (isStale()) {
    console.log('[gtfs] store is stale — refreshing in the background');
    ingest({ log: (m) => console.log(`[gtfs] ${m}`) })
      .then(() => console.log('[gtfs] refresh complete'))
      .catch((err) => console.error(`[gtfs] background refresh failed: ${err.message}`));
  }
  if (isFeedExpired()) {
    console.warn(
      '[gtfs] WARNING: the ingested feed is past its validity window. ' +
        'Scheduled fallbacks will be refused until the portal publishes a current feed.',
    );
  }
}

await prepareStore();

app.listen(config.port, () => {
  console.log(`Porto Bus API listening on http://127.0.0.1:${config.port}`);
});
