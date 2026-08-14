import express from 'express';
import { config } from './config.js';
import { stopsRouter } from './routes/stops.js';
import { linesRouter } from './routes/lines.js';
import { boardRouter } from './routes/board.js';

const app = express();

app.get('/health', (_req, res) => res.json({ status: 'ok' }));

app.use('/stops', stopsRouter);
app.use('/lines', linesRouter);
app.use('/', boardRouter);

// Central error handler: upstream failures become a clean 502. Upstream is
// either stcp.pt (live) or opendata.porto.digital (GTFS), so stay generic —
// naming the wrong one sends you debugging in the wrong direction.
app.use((err, _req, res, _next) => {
  console.error(err);
  res.status(502).json({ detail: `Upstream request failed: ${err.message}` });
});

app.listen(config.port, () => {
  console.log(`Porto Bus API listening on http://127.0.0.1:${config.port}`);
});
