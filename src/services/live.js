/**
 * Live-first, store-as-fallback (README §2a).
 *
 * Before the static store existed, every time-related answer in this API was an
 * upstream call — including the one called a "scheduled fallback", which was a
 * fallback against a single bus not being tracked, never against STCP being
 * down. When stcp.pt went away, every screen went blank at once.
 *
 * This module is the seam. Callers ask for live data through it and get either
 * the live answer or today's timetable, always tagged so the client can say
 * which it is showing.
 */
import * as stcp from '../clients/stcp.js';
import { scheduledStopBoard, stopExists } from '../db/schedule.js';
import { isFeedExpired, hasData } from '../db/index.js';

/**
 * Should this failure fall back to the timetable?
 *
 * A 404 means the stop genuinely does not exist upstream, and answering it with
 * a fabricated board would be worse than the error. Everything else — timeouts,
 * connection failures, 5xx, rate limiting — means "STCP is unwell", which is
 * exactly what the store is for.
 *
 * @param {Error & { status?: number }} err
 * @returns {boolean}
 */
export function isUpstreamOutage(err) {
  const status = err?.status;
  if (status === undefined) return true; // network error, timeout, abort
  if (status === 404 || status === 400) return false;
  return status >= 500 || status === 429 || status === 408;
}

// ---- circuit breaker ------------------------------------------------------
//
// Without this, every request during an outage pays the full HTTP timeout
// before falling back, and a degraded API becomes an unusably slow one. After
// enough consecutive outages we stop calling upstream at all for a cooldown,
// then let a single request through to test the water.

const BREAKER_THRESHOLD = 3;
const BREAKER_COOLDOWN_MS = 30_000;

const breaker = { failures: 0, openedAt: 0 };

/** @returns {boolean} true when we should skip the upstream call entirely */
function breakerIsOpen() {
  if (breaker.failures < BREAKER_THRESHOLD) return false;
  if (Date.now() - breaker.openedAt > BREAKER_COOLDOWN_MS) {
    // Cooldown elapsed: let one request through to probe.
    breaker.failures = BREAKER_THRESHOLD - 1;
    return false;
  }
  return true;
}

function recordFailure() {
  breaker.failures++;
  if (breaker.failures >= BREAKER_THRESHOLD) breaker.openedAt = Date.now();
}

function recordSuccess() {
  breaker.failures = 0;
}

/** Exposed for /health and tests. */
export function breakerState() {
  return {
    open: breakerIsOpen(),
    consecutive_failures: breaker.failures,
    cooldown_ms_remaining: breakerIsOpen()
      ? Math.max(0, BREAKER_COOLDOWN_MS - (Date.now() - breaker.openedAt))
      : 0,
  };
}

/** Test hook. */
export function resetBreaker() {
  breaker.failures = 0;
  breaker.openedAt = 0;
}

/**
 * Can the store stand in right now?
 *
 * An expired feed is refused rather than served: projecting a timetable that is
 * outside its own validity window onto today is a guess wearing a schedule's
 * clothes, and it would be indistinguishable to the client from a real one.
 * @returns {boolean}
 */
function storeCanServe() {
  return hasData() && !isFeedExpired();
}

/**
 * A stop's board: live when STCP answers, today's timetable when it doesn't.
 *
 * @param {string} stopCode
 * @param {object} [opts] forwarded to the scheduled board (limit, windowMinutes)
 * @returns {Promise<import('../../types/domain').RealtimeStop>}
 */
export async function stopBoard(stopCode, opts = {}) {
  if (!breakerIsOpen()) {
    try {
      const live = await stcp.stopRealtime(stopCode);
      recordSuccess();
      return live;
    } catch (err) {
      if (!isUpstreamOutage(err)) throw err;
      recordFailure();
      console.error(`[live] stcp unavailable for ${stopCode}: ${err.message}`);
    }
  }

  if (!storeCanServe()) {
    const reason = !hasData()
      ? 'the static store is empty'
      : 'the ingested GTFS feed is past its validity window';
    const err = new Error(`stcp.pt is unavailable and ${reason}`);
    err.status = 503;
    throw err;
  }
  // Don't invent a board for a stop we've never heard of.
  if (!stopExists(stopCode)) {
    const err = new Error(`Stop '${stopCode}' not found`);
    err.status = 404;
    throw err;
  }

  return scheduledStopBoard(stopCode, opts);
}
