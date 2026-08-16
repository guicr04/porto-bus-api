/**
 * Shared HTTP helper for the stcp.pt JSON API.
 * Prefixes the configured base URL, sets our User-Agent, and applies a timeout.
 */
import { config } from '../config.js';

/**
 * GET a path under the STCP API base and return parsed JSON.
 * `path` may already include a query string.
 * @param {string} path
 * @returns {Promise<any>}
 */
export async function getJson(path) {
  const url = `${config.stcpApiBase}${path}`;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), config.httpTimeoutMs);
  try {
    const resp = await fetch(url, {
      headers: { 'User-Agent': config.userAgent, Accept: 'application/json' },
      signal: controller.signal,
    });
    if (!resp.ok) {
      // The status is carried on the error, not just in the message: the GTFS
      // fallback has to tell "STCP is unwell" (fall back) from "this stop does
      // not exist" (propagate — never fabricate a board for it).
      const err = new Error(`stcp.pt returned ${resp.status} for ${path}`);
      err.status = resp.status;
      throw err;
    }
    return await resp.json();
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Encode query params with %20 for spaces (never "+"). Some STCP endpoints match
 * ids like service_id exactly; PHP accepts both but %20 is the unambiguous choice.
 * @param {Record<string, string | number>} params
 * @returns {string} a query string WITHOUT the leading "?"
 */
export function encodeQuery(params) {
  return Object.entries(params)
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
    .join('&');
}
