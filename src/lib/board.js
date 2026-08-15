/**
 * Pure assembly of a departure board from several nearby stops.
 *
 * The idea that makes this useful rather than just a list of buses: a departure is
 * only worth showing if you can physically get to the stop before it leaves. Each
 * stop carries a walking time, so every arrival gets a `leave_in_minutes` — how
 * long until you must walk out the door. Negative means it's already gone as far
 * as you're concerned, even though the bus itself hasn't arrived yet.
 *
 * @typedef {import('../../types/domain').Arrival} Arrival
 * @typedef {import('../../types/domain').BoardRow} BoardRow
 */

/**
 * @param {object} args
 * @param {{ stop: { stop_code: string, name: string, walk_minutes: number, distance_meters: number }, arrivals: Arrival[], dataSource?: string | null }[]} args.stops
 * @param {number} [args.limit]           max rows, default 10
 * @param {boolean} [args.includeUnreachable]  keep buses you can no longer catch
 * @param {boolean} [args.collapse]       one row per line+destination, default true
 * @param {number} [args.buffer]          minutes of slack to leave, default 0
 * @param {'line' | 'eta'} [args.sort]    row order, default 'line'
 * @returns {BoardRow[]}
 */
export function buildBoard({
  stops,
  limit = 10,
  includeUnreachable = false,
  collapse = true,
  buffer = 0,
  sort = 'line',
}) {
  /** @type {BoardRow[]} */
  let rows = [];

  for (const { stop, arrivals, dataSource } of stops) {
    // STCP labels each board: "realtime" when the buses are actually being
    // tracked, something else when it has fallen back to the timetable. Carry it
    // per row so a display can mark tracked times (green) apart from projections.
    const isRealtime = dataSource === 'realtime';

    for (const a of arrivals) {
      if (a.arrival_minutes === null) continue;
      const leaveIn = a.arrival_minutes - stop.walk_minutes - buffer;
      rows.push({
        line: a.line,
        destination: a.destination,
        realtime: isRealtime,
        data_source: dataSource ?? null,
        stop_code: stop.stop_code,
        stop_name: stop.name,
        walk_minutes: stop.walk_minutes,
        distance_meters: stop.distance_meters,
        eta_minutes: a.arrival_minutes,
        leave_in_minutes: leaveIn,
        catchable: leaveIn >= 0,
        status: a.status,
        delay_minutes: a.delay_minutes,
        color: a.color,
        text_color: a.text_color,
      });
    }
  }

  if (!includeUnreachable) rows = rows.filter((r) => r.catchable);

  // Order by arrival first, whatever the caller asked for.
  rows.sort((a, b) => a.eta_minutes - b.eta_minutes || a.walk_minutes - b.walk_minutes);

  if (collapse) {
    // The same line+direction often serves several stops within walking
    // distance. Show one row: the option that takes the least walking, not
    // whichever stop's tracked ETA happened to read lowest. A bus a few
    // minutes further out at the nearer stop is still the better choice than
    // walking past it for a marginally sooner ping at a farther one — and two
    // stops close together on the same route frequently report the *same*
    // physical bus a minute or two apart, so "soonest ETA" was often really
    // just tracking noise deciding which stop won.
    //
    // Trade-off, accepted deliberately: this reports what's coming to *your*
    // nearest stop for a line, not "the earliest bus you could catch from
    // anywhere nearby". If a farther-but-still-reachable stop has a
    // meaningfully sooner bus on the same line+direction, that option is not
    // shown — only the nearest stop's arrival is.
    const best = new Map(); // key -> current best row
    for (const r of rows) {
      const key = `${r.line} ${r.destination}`;
      const current = best.get(key);
      if (
        !current ||
        r.walk_minutes < current.walk_minutes ||
        (r.walk_minutes === current.walk_minutes && r.distance_meters < current.distance_meters) ||
        (r.walk_minutes === current.walk_minutes &&
          r.distance_meters === current.distance_meters &&
          r.eta_minutes < current.eta_minutes)
      ) {
        best.set(key, r);
      }
    }

    rows = [...best.values()];
    // The winner per key may no longer be the soonest-inserted row, so restore
    // arrival order before the trim/sort below.
    rows.sort((a, b) => a.eta_minutes - b.eta_minutes || a.walk_minutes - b.walk_minutes);
  }

  // Trim before the final sort: "the next N buses, listed by line", rather than
  // "the N lowest-numbered lines, whenever they happen to run".
  rows = rows.slice(0, limit);

  if (sort === 'line') {
    rows.sort((a, b) => compareLines(a.line, b.line) || a.eta_minutes - b.eta_minutes);
  }

  return rows;
}

/**
 * Order line names the way a rider reads them.
 *
 * A plain string sort puts "1M" between "100" and "200". STCP mixes plain numbers
 * ("300"), numbers with a suffix ("1M", "10M") and letters ("ZC"), so compare the
 * leading number first and fall back to text.
 *
 * @param {string} a @param {string} b
 * @returns {number}
 */
export function compareLines(a, b) {
  const numOf = (s) => {
    const m = String(s).match(/^\d+/);
    return m ? Number(m[0]) : null;
  };
  const na = numOf(a);
  const nb = numOf(b);

  // Same number, different suffix: "1" before "1M".
  if (na !== null && nb !== null) return na - nb || String(a).localeCompare(String(b));

  // Numbered lines before lettered ones.
  if (na !== null) return -1;
  if (nb !== null) return 1;
  return String(a).localeCompare(String(b));
}

/**
 * Render a board as fixed-width text, the shape an LED matrix or a small display
 * wants: one row per departure, columns aligned, nothing to parse.
 *
 * Kept separate from `buildBoard` so the hardware can either take this directly or
 * take the JSON and lay it out itself.
 *
 * @param {BoardRow[]} rows
 * @param {object} [opts]
 * @param {number} [opts.width]  total character width, default 42
 * @param {string} [opts.title]
 * @param {boolean} [opts.color] colour live times green with ANSI escapes
 * @returns {string}
 */
export function renderBoard(rows, opts = {}) {
  const width = opts.width ?? 42;
  const pad = (s, n) => String(s).slice(0, n).padEnd(n);
  const padStart = (s, n) => String(s).slice(0, n).padStart(n);

  const lines = [];
  if (opts.title) {
    lines.push(pad(opts.title, width));
    lines.push('-'.repeat(width));
  }

  if (rows.length === 0) {
    lines.push(pad('no departures within reach', width));
    return lines.join('\n');
  }

  // LINE(4) DEST(rest) ETA(4). The walking time is still used to decide which
  // buses make the board at all — it just isn't shown; the destination gets the
  // space instead, which is what's actually hard to read when truncated.
  const destWidth = Math.max(8, width - 4 - 1 - 4 - 1);

  // Green marks a time STCP is actually tracking, as opposed to one projected from
  // the timetable. Colour is applied *after* padding, so the escape sequences never
  // count towards the column width — a coloured board still lines up.
  const GREEN = '\u001b[32m';
  const RESET = '\u001b[0m';
  const eta = (r) => {
    const cell = padStart(`${r.eta_minutes}m`, 4);
    return opts.color && r.realtime ? `${GREEN}${cell}${RESET}` : cell;
  };

  for (const r of rows) {
    lines.push(
      [pad(r.line, 4), pad(r.destination ?? '', destWidth), eta(r)].join(' '),
    );
  }

  return lines.join('\n');
}
