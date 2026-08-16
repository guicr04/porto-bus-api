/**
 * Pure merge of live (realtime) and scheduled departures for one line at one stop.
 * Live wins; scheduled fills the gaps; scheduled entries that duplicate a live one
 * (same line within a tolerance window) are dropped. Sorted purely by time.
 *
 * @typedef {import('../../types/domain').Arrival} Arrival
 * @typedef {import('../../types/domain').Departure} Departure
 * @typedef {import('../../types/domain').CombinedDeparture} CombinedDeparture
 */
import { clockToMinutes, minutesToClock, isoToLisbonClock } from './time.js';

/**
 * @param {object} args
 * @param {Arrival[]} args.realtime      live arrivals, already filtered to the line
 * @param {Departure[]} args.scheduled   scheduled departures at the stop for the line
 * @param {number} args.nowMin           minutes-since-midnight, Europe/Lisbon
 * @param {number} args.windowMinutes    dedup tolerance (scheduled within this of a live one is dropped)
 * @param {number} args.limit            max results
 * @param {string} args.line
 * @param {string | null} [args.color]
 * @param {string | null} [args.textColor]
 * @returns {CombinedDeparture[]}
 */
export function mergeDepartures({
  realtime,
  scheduled,
  nowMin,
  windowMinutes,
  limit,
  line,
  color = null,
  textColor = null,
}) {
  /** @type {(CombinedDeparture & { _min: number })[]} */
  const items = [];

  // Live arrivals (source of truth for the near term).
  for (const a of realtime) {
    const eta = a.arrival_minutes ?? null;

    // Anchor the entry on the ISO estimate when we have one, and derive both the
    // displayed clock and the sort/dedup key from that same value. Deriving the
    // clock from the estimate but the key from `nowMin + arrival_minutes` lets
    // the two drift a minute apart — enough for the dedup below to miss a match
    // and show a bus twice, and enough for the response to contradict itself.
    const clock = isoToLisbonClock(a.estimated_arrival_time);
    let min = clock === null ? null : clockToMinutes(clock);

    // A bus due after midnight reads as a small clock value (00:35 -> 35) while
    // `nowMin` is still large, which would sort it to the top. Push it into the
    // next day so it lands last, matching how the timetable treats "24:35".
    if (min !== null && min < nowMin - 60) min += 24 * 60;

    if (min === null) min = nowMin + (eta ?? 0);

    items.push({
      line: a.line,
      destination: a.destination,
      source: 'realtime',
      eta_minutes: eta,
      time: clock ?? minutesToClock(min),
      status: a.status,
      delay_minutes: a.delay_minutes,
      color: a.color ?? color,
      text_color: a.text_color ?? textColor,
      trip_id: a.trip_id ?? null,
      _min: min,
    });
  }

  // For each live bus, work out which timetable slot it belongs to.
  //
  // A late bus shows up at its *estimated* time, which can sit further from its
  // scheduled minute than any sane tolerance window: a 300 estimated 18:43 with
  // delay=5 is the 18:38 slot, five minutes off. Matching on the estimate alone
  // therefore lists the same bus twice — once live, once scheduled. So when the
  // feed gives us a delay, subtract it to recover the exact scheduled minute and
  // match on that; the tolerance window is only the fallback for when it doesn't.
  const liveSlots = items.map((i) => ({
    min: i._min,
    slot: i.delay_minutes === null ? null : Math.round(i._min - i.delay_minutes),
  }));

  // Scheduled departures: only future ones the live feed didn't already cover.
  for (const d of scheduled) {
    const schedMin = clockToMinutes(d.departure_time);
    if (schedMin === null) continue;
    const eta = schedMin - nowMin;
    if (eta < 0) continue; // already departed today
    const duplicatesLive = liveSlots.some(({ min, slot }) =>
      // ±1 on the slot match: both the ETA and the delay are rounded upstream,
      // so the recovered slot can land a minute either side of the printed time.
      slot === null ? Math.abs(min - schedMin) <= windowMinutes : Math.abs(slot - schedMin) <= 1,
    );
    if (duplicatesLive) continue;
    items.push({
      line,
      destination: d.headsign,
      source: 'scheduled',
      eta_minutes: eta,
      time: minutesToClock(schedMin),
      status: null,
      delay_minutes: null,
      color,
      text_color: textColor,
      // Only the store-backed scheduled rows know their trip; upstream's
      // timetable is times and headsigns only. Null here is normal, and the
      // client resolves those by pattern instead (README §4c).
      trip_id: d.trip_id ?? null,
      _min: schedMin,
    });
  }

  items.sort((a, b) => a._min - b._min);

  return items.slice(0, limit).map(({ _min, ...rest }) => rest);
}
