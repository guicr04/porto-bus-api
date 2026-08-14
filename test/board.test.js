/**
 * Tests for the departure board: the walking model and the "can I still catch
 * it" rule, which is the whole point of the board.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { haversineMeters, walkMinutes, stopsWithinWalk } from '../src/lib/geo.js';
import { buildBoard, renderBoard, compareLines } from '../src/lib/board.js';
import { pickStopsToPoll } from '../src/services/board.js';

// CARMO and ALIADOS, two real Porto stops about 500 m apart.
const CARMO = { stop_code: 'CMO', name: 'CARMO', lat: 41.147223, lon: -8.616926 };
const ALIADOS = { stop_code: 'ALD', name: 'ALIADOS', lat: 41.14843, lon: -8.61099 };

test('haversine matches a known distance', () => {
  const d = haversineMeters(CARMO.lat, CARMO.lon, ALIADOS.lat, ALIADOS.lon);
  assert.ok(d > 400 && d < 600, `expected ~500m, got ${Math.round(d)}m`);
});

test('walking time rounds up and allows for street detours', () => {
  // 750 m straight line * 1.35 detour / 75 m per min = 13.5 -> 14
  assert.equal(walkMinutes(750), 14);
  assert.equal(walkMinutes(0), 0);
});

test('stops outside the walking budget are excluded', () => {
  const far = { stop_code: 'FAR', name: 'FAR AWAY', lat: 41.2, lon: -8.7 };
  const near = stopsWithinWalk([CARMO, ALIADOS, far], CARMO.lat, CARMO.lon, 10);

  assert.deepEqual(near.map((s) => s.stop_code), ['CMO', 'ALD']);
  assert.equal(near[0].walk_minutes, 0); // standing at it
});

test('stops with no coordinates are skipped, not treated as (0,0)', () => {
  const broken = { stop_code: 'BAD', name: 'NO COORDS', lat: null, lon: null };
  const near = stopsWithinWalk([CARMO, broken], CARMO.lat, CARMO.lon, 10);
  assert.deepEqual(near.map((s) => s.stop_code), ['CMO']);
});

test('polling prefers distinct stop names over a cluster of platforms', () => {
  // Downtown reality: three CORDOARIA platforms within metres of each other would
  // otherwise eat the whole budget and leave the rest of the radius unseen.
  const nearby = [
    { stop_code: 'CORD1', name: 'CORDOARIA' },
    { stop_code: 'CORD3', name: 'CORDOARIA' },
    { stop_code: 'CORD5', name: 'CORDOARIA' },
    { stop_code: 'GGF', name: 'GUIL. G. FERNANDES' },
    { stop_code: 'CMO', name: 'CARMO' },
  ];

  assert.deepEqual(
    pickStopsToPoll(nearby, 3).map((s) => s.stop_code),
    ['CORD1', 'GGF', 'CMO'],
  );
});

test('platforms are deduped by proximity even when named differently', () => {
  // Real feed data: the same place spelled two ways, ~10 m apart.
  const nearby = [
    { stop_code: 'GGF', name: 'GUIL. G. FERNANDES', lat: 41.14724, lon: -8.61462 },
    { stop_code: 'GGF1', name: 'GUILHERME GOMES FERNANDES', lat: 41.14730, lon: -8.61470 },
    { stop_code: 'CMO', name: 'CARMO', lat: 41.147223, lon: -8.616926 },
  ];

  assert.deepEqual(
    pickStopsToPoll(nearby, 2).map((s) => s.stop_code),
    ['GGF', 'CMO'],
  );
});

test('leftover budget falls back to the duplicate platforms', () => {
  const nearby = [
    { stop_code: 'CORD1', name: 'CORDOARIA' },
    { stop_code: 'CORD3', name: 'CORDOARIA' },
    { stop_code: 'CMO', name: 'CARMO' },
  ];

  assert.deepEqual(
    pickStopsToPoll(nearby, 3).map((s) => s.stop_code),
    ['CORD1', 'CMO', 'CORD3'],
  );
});

/** @param {object} o */
const arrival = (o) => ({
  line: '300',
  destination: 'Aliados',
  arrival_minutes: 10,
  estimated_arrival_time: null,
  scheduled_arrival_time: null,
  status: 'ON_TIME',
  delay_minutes: null,
  color: '#417DBD',
  text_color: '#FFFFFF',
  trip_id: null,
  ...o,
});

/** @param {object} o */
const atStop = (walk, arrivals, code = 'CMO', dataSource = 'realtime') => ({
  stop: { stop_code: code, name: code, walk_minutes: walk, distance_meters: walk * 100 },
  arrivals,
  dataSource,
});

test('a bus that arrives before you could walk there is dropped', () => {
  const rows = buildBoard({
    stops: [atStop(8, [arrival({ arrival_minutes: 3 }), arrival({ line: '500', arrival_minutes: 12 })])],
  });

  assert.deepEqual(rows.map((r) => r.line), ['500']);
  assert.equal(rows[0].leave_in_minutes, 4); // 12 - 8
});

test('include_unreachable keeps it but flags it', () => {
  const rows = buildBoard({
    stops: [atStop(8, [arrival({ arrival_minutes: 3 })])],
    includeUnreachable: true,
  });

  assert.equal(rows.length, 1);
  assert.equal(rows[0].catchable, false);
  assert.equal(rows[0].leave_in_minutes, -5);
});

test('a buffer makes the cut-off stricter', () => {
  // 10 min bus, 8 min walk: reachable with no buffer, not with 3 minutes of slack.
  const stops = [atStop(8, [arrival({ arrival_minutes: 10 })])];

  assert.equal(buildBoard({ stops }).length, 1);
  assert.equal(buildBoard({ stops, buffer: 3 }).length, 0);
});

test('the same line at two nearby stops collapses to the soonest option', () => {
  const rows = buildBoard({
    stops: [
      atStop(2, [arrival({ arrival_minutes: 20 })], 'CMO'),
      atStop(5, [arrival({ arrival_minutes: 12 })], 'ALD'),
    ],
  });

  assert.equal(rows.length, 1);
  assert.equal(rows[0].stop_code, 'ALD'); // the 12-minute one wins
});

test('collapse:false keeps every stop option', () => {
  const rows = buildBoard({
    stops: [
      atStop(2, [arrival({ arrival_minutes: 20 })], 'CMO'),
      atStop(5, [arrival({ arrival_minutes: 12 })], 'ALD'),
    ],
    collapse: false,
  });

  assert.deepEqual(rows.map((r) => r.stop_code), ['ALD', 'CMO']);
});

test('different destinations on the same line are kept apart', () => {
  const rows = buildBoard({
    stops: [
      atStop(2, [
        arrival({ arrival_minutes: 10, destination: 'Aliados' }),
        arrival({ arrival_minutes: 14, destination: 'Matosinhos' }),
      ]),
    ],
  });

  assert.equal(rows.length, 2);
});

test('rows are ordered by line', () => {
  const rows = buildBoard({
    stops: [
      atStop(1, [arrival({ line: '801', arrival_minutes: 4 })], 'S1'),
      atStop(1, [arrival({ line: '207', arrival_minutes: 15 })], 'S2'),
      atStop(1, [arrival({ line: '305', arrival_minutes: 9 })], 'S3'),
    ],
  });

  assert.deepEqual(rows.map((r) => r.line), ['207', '305', '801']);
});

test('sort=eta still orders by arrival', () => {
  const rows = buildBoard({
    stops: [
      atStop(1, [arrival({ line: '801', arrival_minutes: 4 })], 'S1'),
      atStop(1, [arrival({ line: '207', arrival_minutes: 15 })], 'S2'),
    ],
    sort: 'eta',
  });

  assert.deepEqual(rows.map((r) => r.line), ['801', '207']);
});

test('line ordering is numeric, not alphabetic', () => {
  // The bug this pins: as strings, "1M" sorts between "100" and "200",
  // and "90" after "906".
  const lines = ['906', '1M', '200', '90', '10M', 'ZC', '1'];
  const sorted = [...lines].sort(compareLines);

  assert.deepEqual(sorted, ['1', '1M', '10M', '90', '200', '906', 'ZC']);
});

test('the soonest bus per line survives, then rows are re-sorted by line', () => {
  // Ordering by line must not change *which* option is kept: line 500 should
  // still resolve to its 6-minute bus, not the 20-minute one.
  const rows = buildBoard({
    stops: [
      atStop(2, [arrival({ line: '500', arrival_minutes: 20 })], 'FAR'),
      atStop(3, [arrival({ line: '500', arrival_minutes: 6 })], 'NEAR'),
      atStop(1, [arrival({ line: '200', arrival_minutes: 12 })], 'S3'),
    ],
  });

  assert.deepEqual(
    rows.map((r) => [r.line, r.eta_minutes]),
    [
      ['200', 12],
      ['500', 6],
    ],
  );
});

test('limit takes the soonest buses, then orders them by line', () => {
  // Not "the lowest-numbered lines whenever they run": the 40-minute 100 must
  // lose to sooner buses even though its number sorts first.
  const rows = buildBoard({
    stops: [
      atStop(1, [
        arrival({ line: '100', arrival_minutes: 40 }),
        arrival({ line: '900', arrival_minutes: 2 }),
        arrival({ line: '500', arrival_minutes: 5 }),
      ]),
    ],
    limit: 2,
  });

  assert.deepEqual(rows.map((r) => r.line), ['500', '900']);
});

test('arrivals with no ETA are ignored rather than sorted as zero', () => {
  const rows = buildBoard({
    stops: [atStop(1, [arrival({ arrival_minutes: null }), arrival({ line: '500' })])],
  });

  assert.deepEqual(rows.map((r) => r.line), ['500']);
});

test('limit caps the board', () => {
  const rows = buildBoard({
    stops: [
      atStop(1, [1, 2, 3, 4, 5].map((n) => arrival({ line: `L${n}`, arrival_minutes: n }))),
    ],
    limit: 3,
  });

  assert.equal(rows.length, 3);
});

test('renders fixed-width rows', () => {
  const rows = buildBoard({ stops: [atStop(3, [arrival({ arrival_minutes: 9 })])] });
  const text = renderBoard(rows, { width: 42 });

  const [first] = text.split('\n');
  assert.equal(first.length, 42);
  assert.match(first, /^300 /);
  assert.match(first, /9m$/);
  assert.ok(!/\dw/.test(first), 'walking minutes should not be rendered');
});

test('renders a readable message when nothing is reachable', () => {
  assert.match(renderBoard([]), /no departures/);
});

test('rows are flagged realtime when STCP is tracking the stop', () => {
  const rows = buildBoard({
    stops: [
      atStop(1, [arrival({ line: '200' })], 'S1', 'realtime'),
      atStop(1, [arrival({ line: '300' })], 'S2', 'scheduled'),
    ],
  });

  assert.deepEqual(
    rows.map((r) => [r.line, r.realtime]),
    [
      ['200', true],
      ['300', false],
    ],
  );
});

test('a missing data_source is not treated as live', () => {
  const rows = buildBoard({ stops: [atStop(1, [arrival()], 'S1', null)] });
  assert.equal(rows[0].realtime, false);
});

test('colour marks live times green and leaves projected ones plain', () => {
  const rows = buildBoard({
    stops: [
      atStop(1, [arrival({ line: '200', arrival_minutes: 5 })], 'S1', 'realtime'),
      atStop(1, [arrival({ line: '300', arrival_minutes: 9 })], 'S2', 'scheduled'),
    ],
  });
  const [live, projected] = renderBoard(rows, { color: true }).split('\n');

  assert.match(live, /\u001b\[32m\s*5m\u001b\[0m$/);
  assert.ok(!projected.includes('\u001b'), 'projected times must stay uncoloured');
});

test('colour is off by default, for devices that cannot strip escape codes', () => {
  const rows = buildBoard({ stops: [atStop(1, [arrival()], 'S1', 'realtime')] });
  assert.ok(!renderBoard(rows).includes('\u001b'));
});

test('escape codes do not count towards the column width', () => {
  // The whole point of the fixed-width render: a coloured board must still line up.
  const rows = buildBoard({ stops: [atStop(1, [arrival()], 'S1', 'realtime')] });
  const [row] = renderBoard(rows, { width: 42, color: true }).split('\n');

  const visible = row.replace(/\u001b\[\d+m/g, '');
  assert.equal(visible.length, 42);
});
