#!/usr/bin/env node
/**
 * Rebuild the static store from the newest published GTFS feed.
 *
 *   npm run gtfs:refresh              # newest feed from the portal
 *   GTFS_URL=... npm run gtfs:refresh # a pinned feed or a local copy
 *
 * Safe to run against a live API: the ingest replaces everything inside one
 * transaction and WAL keeps readers unblocked throughout. Intended to run daily
 * from cron — but nothing depends on cron existing, because the server runs the
 * same ingest at boot when the store is missing or stale.
 */
import { ingest } from '../src/db/ingest.js';
import { closeDb } from '../src/db/index.js';

const started = Date.now();
try {
  const counts = await ingest({ log: (m) => console.log(`[gtfs] ${m}`) });
  const seconds = ((Date.now() - started) / 1000).toFixed(1);
  console.log(`[gtfs] ingested in ${seconds}s:`);
  for (const [k, v] of Object.entries(counts)) console.log(`         ${k}: ${v}`);

  const end = counts.feed_end_date;
  if (end) {
    const today = new Date().toISOString().slice(0, 10).replace(/-/g, '');
    if (String(end) < today) {
      console.warn(
        `[gtfs] WARNING: this feed expired on ${end} (today is ${today}). ` +
          'The portal has not published a current feed; scheduled data will be refused.',
      );
    }
  }
} catch (err) {
  console.error(`[gtfs] refresh failed: ${err.message}`);
  console.error('[gtfs] the previous store is untouched.');
  process.exitCode = 1;
} finally {
  closeDb();
}
