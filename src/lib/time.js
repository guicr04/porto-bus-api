/**
 * Time helpers. STCP clock times are Europe/Lisbon wall-clock, and can exceed
 * 24:00 for after-midnight trips (e.g. "24:35:00" == 00:35 next day).
 */

/**
 * Current wall-clock time in Europe/Lisbon, as minutes since midnight.
 * DST-safe via Intl.
 * @param {Date} [now]
 * @returns {number}
 */
export function lisbonNowMinutes(now = new Date()) {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Europe/Lisbon',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).formatToParts(now);
  const h = Number(parts.find((p) => p.type === 'hour')?.value ?? 0) % 24;
  const m = Number(parts.find((p) => p.type === 'minute')?.value ?? 0);
  return h * 60 + m;
}

/**
 * Parse "HH:MM:SS" (or "HH:MM") to minutes since midnight. Hours may be >= 24.
 * @param {string} clock
 * @returns {number | null}
 */
export function clockToMinutes(clock) {
  const m = /^(\d{1,2}):(\d{2})/.exec(clock ?? '');
  if (!m) return null;
  return Number(m[1]) * 60 + Number(m[2]);
}

/**
 * Format minutes-since-midnight to "HH:MM", wrapping hours into 0-23
 * (so 1475 -> "00:35", the after-midnight display).
 * @param {number} minutes
 * @returns {string}
 */
export function minutesToClock(minutes) {
  const h = Math.floor(minutes / 60) % 24;
  const m = ((minutes % 60) + 60) % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

/**
 * Format an ISO timestamp to "HH:MM" in Europe/Lisbon.
 * @param {string | null | undefined} iso
 * @returns {string | null}
 */
export function isoToLisbonClock(iso) {
  if (!iso) return null;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Europe/Lisbon',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(d);
}

/**
 * Today's date in Europe/Lisbon as GTFS writes it: "YYYYMMDD".
 *
 * Must be Lisbon rather than the host's timezone — the store is keyed by
 * service date, and a server running in UTC would flip days an hour early in
 * summer, quietly serving tomorrow's timetable.
 *
 * @param {Date} [now]
 * @param {number} [dayOffset] e.g. -1 for yesterday
 * @returns {string}
 */
export function lisbonDateStamp(now = new Date(), dayOffset = 0) {
  const shifted = new Date(now.getTime() + dayOffset * 86400000);
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Europe/Lisbon',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(shifted);
  return parts.replace(/-/g, '');
}
