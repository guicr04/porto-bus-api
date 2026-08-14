/**
 * Tests for the live + scheduled merge. This is the one piece of real logic in
 * the project (everything else is a thin proxy), and its dedup rule is a
 * heuristic, so it's worth pinning down.
 *
 * Run with: npm test
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { mergeDepartures } from '../src/lib/combine.js';

/** @param {object} o */
const live = (o) => ({
  line: '300',
  destination: 'Aliados - H.S.João',
  arrival_minutes: null,
  estimated_arrival_time: null,
  scheduled_arrival_time: null,
  status: 'ON_TIME',
  delay_minutes: null,
  color: '#417DBD',
  text_color: '#FFFFFF',
  trip_id: null,
  ...o,
});

/** @param {string} t */
const sched = (t) => ({ departure_time: t, headsign: 'Aliados - H.S.João' });

const NOW = 17 * 60 + 50; // 17:50

test('a late live bus does not duplicate its own timetable slot', () => {
  // Estimated 18:43, running 5 late => it IS the 18:38 slot, 5 minutes away.
  // A naive ±3 window would keep both and show the same bus twice.
  const out = mergeDepartures({
    realtime: [
      live({
        arrival_minutes: 53,
        delay_minutes: 5,
        estimated_arrival_time: '2026-07-19T18:43:54+01:00',
      }),
    ],
    scheduled: [sched('18:38'), sched('19:08')],
    nowMin: NOW,
    windowMinutes: 3,
    limit: 10,
    line: '300',
  });

  assert.deepEqual(
    out.map((d) => [d.time, d.source]),
    [
      ['18:43', 'realtime'],
      ['19:08', 'scheduled'],
    ],
  );
});

test('falls back to the tolerance window when delay is unknown', () => {
  const out = mergeDepartures({
    realtime: [live({ arrival_minutes: 20, delay_minutes: null })], // 18:10
    scheduled: [sched('18:12'), sched('18:40')], // 18:12 is within ±3
    nowMin: NOW,
    windowMinutes: 3,
    limit: 10,
    line: '300',
  });

  assert.deepEqual(
    out.map((d) => [d.time, d.source]),
    [
      ['18:10', 'realtime'],
      ['18:40', 'scheduled'],
    ],
  );
});

test('an early bus (negative delay) matches its slot too', () => {
  // Estimated 18:06, running 4 early => it is the 18:10 slot.
  const out = mergeDepartures({
    realtime: [live({ arrival_minutes: 16, delay_minutes: -4 })],
    scheduled: [sched('18:10')],
    nowMin: NOW,
    windowMinutes: 3,
    limit: 10,
    line: '300',
  });

  assert.deepEqual(out.map((d) => d.source), ['realtime']);
});

test('past scheduled departures are dropped, future ones interleave by time', () => {
  const out = mergeDepartures({
    realtime: [live({ arrival_minutes: 30, delay_minutes: 0 })], // 18:20
    scheduled: [sched('17:00'), sched('17:49'), sched('18:00'), sched('18:40')],
    nowMin: NOW,
    windowMinutes: 3,
    limit: 10,
    line: '300',
  });

  assert.deepEqual(
    out.map((d) => [d.time, d.source]),
    [
      ['18:00', 'scheduled'],
      ['18:20', 'realtime'],
      ['18:40', 'scheduled'],
    ],
  );
});

test('scheduled entries inherit the live colour so one line renders consistently', () => {
  const out = mergeDepartures({
    realtime: [live({ arrival_minutes: 5, delay_minutes: 0 })],
    scheduled: [sched('18:40')],
    nowMin: NOW,
    windowMinutes: 3,
    limit: 10,
    line: '300',
    color: '#417DBD',
    textColor: '#FFFFFF',
  });

  assert.equal(new Set(out.map((d) => d.color)).size, 1);
});

test('sorts on the estimated arrival time, not on nowMin + eta', () => {
  // The two disagree here (eta implies 17:55, the estimate says 18:30). The
  // displayed clock comes from the estimate, so the ordering must too —
  // otherwise the response is sorted by one value and rendered with another.
  const out = mergeDepartures({
    realtime: [
      live({
        arrival_minutes: 5,
        delay_minutes: null,
        estimated_arrival_time: '2026-07-19T18:30:00+01:00',
      }),
    ],
    scheduled: [sched('18:00'), sched('18:45')],
    nowMin: NOW,
    windowMinutes: 3,
    limit: 10,
    line: '300',
  });

  assert.deepEqual(
    out.map((d) => [d.time, d.source]),
    [
      ['18:00', 'scheduled'],
      ['18:30', 'realtime'],
      ['18:45', 'scheduled'],
    ],
  );
});

test('a bus due after midnight sorts last instead of first', () => {
  // 00:35 is a smaller clock value than 23:20, so a naive sort would float it
  // to the top of the board rather than the bottom.
  const out = mergeDepartures({
    realtime: [
      live({
        arrival_minutes: 75,
        delay_minutes: null,
        estimated_arrival_time: '2026-07-20T00:35:00+01:00',
      }),
    ],
    scheduled: [sched('23:30')],
    nowMin: 23 * 60 + 20, // 23:20
    windowMinutes: 3,
    limit: 10,
    line: '300',
  });

  assert.deepEqual(
    out.map((d) => [d.time, d.source]),
    [
      ['23:30', 'scheduled'],
      ['00:35', 'realtime'],
    ],
  );
});

test('limit caps the result', () => {
  const out = mergeDepartures({
    realtime: [],
    scheduled: ['18:00', '18:10', '18:20', '18:30'].map(sched),
    nowMin: NOW,
    windowMinutes: 3,
    limit: 2,
    line: '300',
  });

  assert.equal(out.length, 2);
});

test('an empty live board degrades to a pure timetable view', () => {
  const out = mergeDepartures({
    realtime: [],
    scheduled: [sched('18:00'), sched('18:30')],
    nowMin: NOW,
    windowMinutes: 3,
    limit: 10,
    line: '300',
  });

  assert.deepEqual(out.map((d) => d.source), ['scheduled', 'scheduled']);
});
