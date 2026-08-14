/**
 * Tiny in-memory TTL cache.
 *
 * Exists for the departure board: an always-on display refreshing every few
 * seconds, across a dozen nearby stops, would otherwise mean hundreds of requests
 * an hour to an undocumented endpoint we have no agreement to use. Live arrivals
 * only change on the order of tens of seconds, so a short TTL costs the display
 * nothing and cuts upstream traffic by an order of magnitude.
 *
 * In-process and unbounded-by-design: the key space is stop codes, so it tops out
 * at a few thousand small entries even if every stop in Porto is queried.
 */

/** @type {Map<string, { value: any, expiresAt: number }>} */
const store = new Map();

/**
 * Get a cached value, or compute and store it.
 *
 * Concurrent callers for the same key each compute their own value the first time
 * — acceptable here (the board fans out over *distinct* stops), and it keeps this
 * free of in-flight bookkeeping.
 *
 * @template T
 * @param {string} key
 * @param {number} ttlMs  zero or less disables caching
 * @param {() => Promise<T>} compute
 * @returns {Promise<T>}
 */
export async function cached(key, ttlMs, compute) {
  if (ttlMs <= 0) return compute();

  const hit = store.get(key);
  if (hit && hit.expiresAt > Date.now()) return hit.value;

  const value = await compute();
  store.set(key, { value, expiresAt: Date.now() + ttlMs });
  return value;
}

/** Drop everything. Used by tests. */
export function clearCache() {
  store.clear();
}

/** How many live entries are held, for /health. */
export function cacheSize() {
  const now = Date.now();
  let n = 0;
  for (const entry of store.values()) if (entry.expiresAt > now) n++;
  return n;
}
