/**
 * Resolving a live `trip_id` to a trip in the static store.
 *
 * This is the join the app's line detail stands on, and it is a join on an
 * undocumented id format (README §2a) — so the tests here are mostly about the
 * ways it is allowed to *fail*, and about staying deterministic when the feed
 * cannot decide for itself.
 *
 * Synthesised feed, not the real 58 MB zip: the trip ids are the point, and
 * they need to be adversarial (colliding versions, underscores that are SQL
 * wildcards, an after-midnight tail) in ways Porto's feed happens not to be on
 * any given day.
 */
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import AdmZip from 'adm-zip';

const dir = mkdtempSync(join(tmpdir(), 'porto-bus-trips-'));
process.env.DB_PATH = join(dir, 'test.db');

const { ingest } = await import('../src/db/ingest.js');
const { closeDb } = await import('../src/db/index.js');
const { normalizeTripId, resolveTrip, tripStops, findTripByPattern, resolvedTripStops } =
  await import('../src/db/trips.js');

// A weekday that has service, and one that doesn't (the feed ends on the 15th).
const ON_SERVICE_DAY = new Date('2026-08-14T09:00:00+01:00');
const PAST_FEED_END = new Date('2026-08-16T09:00:00+01:00');

function makeZip() {
  const files = {
    'agency.txt': 'agency_id,agency_name\nSTCP,Test\n',
    'calendar.txt': 'service_id,monday,start_date,end_date\n',
    // Two weekday services a long way apart, so "today's service" can actually
    // separate the colliding pair, plus a Saturday nobody's tests land on.
    'calendar_dates.txt':
      'service_id,date,exception_type\n' +
      'SVC_A,20260810,1\n' +
      'SVC_B,20260814,1\n' +
      'SVC_SAT,20260815,1\n',
    'feed_info.txt':
      'feed_publisher_name,feed_version,feed_start_date,feed_end_date\n' +
      'STCP,v276,20260801,20260815\n',
    'routes.txt':
      'route_id,route_short_name,route_long_name,route_color,route_text_color,route_type,route_sort_order\n' +
      'R1,601,"ALIADOS - AEROPORTO",187EC2,FFFFFF,3,10\n' +
      'R2,602,"ALIADOS - MATOSINHOS",EC8031,FFFFFF,3,20\n',
    'stops.txt':
      'stop_lat,stop_lon,stop_id,stop_code,stop_name,zone_id\n' +
      '41.1482,-8.6108,AAA1,AAA1,AV. ALIADOS,PRT1\n' +
      '41.1500,-8.6200,BBB2,BBB2,CARMO,PRT1\n' +
      '41.1600,-8.6300,CCC3,CCC3,BOAVISTA,PRT1\n',
    'trips.txt':
      'route_id,service_id,trip_id,trip_headsign,direction_id,shape_id,block_id\n' +
      // The colliding pair: the same trip reissued under a newer feed version.
      'R1,SVC_A,601_0_1|274|D3|T1|N6,Aeroporto,0,SH1,B1\n' +
      'R1,SVC_B,601_0_1|276|D3|T1|N6,Aeroporto,0,SH1,B1\n' +
      // Unique once the version is stripped.
      'R1,SVC_B,601_0_1|276|D3|T1|N8,Aeroporto,0,SH1,B1\n' +
      // Differs from the N6 pair only where LIKE would treat `_` as a wildcard.
      'R1,SVC_B,601X0Y1|276|D3|T1|N6,Decoy,0,SH1,B1\n' +
      // Never named by id in these tests — only ever found by line + headsign.
      'R2,SVC_B,602_0_1|276|D3|T1|N2,Matosinhos,1,SH1,B2\n' +
      // Runs past midnight, filed under its own service day.
      'R1,SVC_B,601_0_1|276|D6|T9|N1,Aeroporto,0,SH1,B3\n',
    'stop_times.txt':
      'trip_id,arrival_time,departure_time,stop_id,stop_sequence,timepoint,shape_dist_traveled\n' +
      '601_0_1|274|D3|T1|N6,08:00:00,08:00:00,AAA1,1,1,0.0\n' +
      '601_0_1|274|D3|T1|N6,08:10:00,08:10:00,BBB2,2,0,1200.0\n' +
      '601_0_1|276|D3|T1|N6,08:00:00,08:00:00,AAA1,1,1,0.0\n' +
      '601_0_1|276|D3|T1|N6,08:10:00,08:10:00,BBB2,2,0,1200.0\n' +
      '601_0_1|276|D3|T1|N6,08:25:00,08:25:00,CCC3,3,1,2400.0\n' +
      '601_0_1|276|D3|T1|N8,09:00:00,09:00:00,AAA1,1,1,0.0\n' +
      '601_0_1|276|D3|T1|N8,09:12:00,09:12:00,BBB2,2,0,1200.0\n' +
      '601X0Y1|276|D3|T1|N6,07:00:00,07:00:00,AAA1,1,1,0.0\n' +
      '602_0_1|276|D3|T1|N2,08:05:00,08:05:00,AAA1,1,1,0.0\n' +
      '602_0_1|276|D3|T1|N2,08:20:00,08:20:00,BBB2,2,0,1200.0\n' +
      '601_0_1|276|D6|T9|N1,24:20:00,24:20:00,AAA1,1,1,0.0\n' +
      '601_0_1|276|D6|T9|N1,24:35:00,24:35:00,BBB2,2,0,1200.0\n',
    'shapes.txt':
      'shape_pt_lat,shape_pt_lon,shape_dist_traveled,shape_id,shape_pt_sequence\n' +
      '41.1482,-8.6108,0.0,SH1,1\n' +
      '41.1500,-8.6200,1200.0,SH1,2\n',
  };
  const zip = new AdmZip();
  for (const [name, body] of Object.entries(files)) zip.addFile(name, Buffer.from(body, 'utf8'));
  return zip.toBuffer();
}

before(async () => {
  await ingest({ zip: makeZip(), url: 'test://feed', name: 'trips test feed' });
});

after(() => {
  closeDb();
  rmSync(dir, { recursive: true, force: true });
});

test('normalizing a trip id drops the feed version and nothing else', () => {
  assert.equal(normalizeTripId('601_0_1|280|D3|T1|N6'), '601_0_1|D3|T1|N6');
  assert.equal(normalizeTripId('601_0_1|276|D3|T1|N6'), '601_0_1|D3|T1|N6');
  // Not a versioned id at all: leave it alone rather than mangling it.
  assert.equal(normalizeTripId('T1'), 'T1');
  assert.equal(normalizeTripId('A|B'), 'A|B');
});

test('an id the store already holds verbatim matches exactly', () => {
  const r = resolveTrip('601_0_1|276|D3|T1|N8', { now: ON_SERVICE_DAY });
  assert.equal(r.match, 'exact');
  assert.equal(r.trip.trip_id, '601_0_1|276|D3|T1|N8');
});

test("a live id resolves once the feed-version field is dropped", () => {
  // What STCP actually serves: a version the ingested zip has never seen.
  const r = resolveTrip('601_0_1|280|D3|T1|N8', { now: ON_SERVICE_DAY });
  assert.equal(r.match, 'version');
  assert.equal(r.trip.trip_id, '601_0_1|276|D3|T1|N8');
  assert.equal(r.trip.headsign, 'Aeroporto');
  assert.equal(r.trip.direction_id, 0);
});

test("the day's service breaks a tie between two feed versions of one trip", () => {
  // Both 274 and 276 normalise to the same key; only SVC_B runs on the 14th.
  const r = resolveTrip('601_0_1|280|D3|T1|N6', { now: ON_SERVICE_DAY });
  assert.equal(r.match, 'version');
  assert.equal(r.trip.service_id, 'SVC_B');
  assert.equal(r.trip.trip_id, '601_0_1|276|D3|T1|N6');
});

test('with no service running today, the newest feed version wins — and says so', () => {
  // The 16th is past feed_end_date, so no service_dates row exists at all. The
  // answer must still be deterministic, and must not pass itself off as certain.
  const r = resolveTrip('601_0_1|280|D3|T1|N6', { now: PAST_FEED_END });
  assert.equal(r.match, 'version_latest');
  assert.equal(r.trip.trip_id, '601_0_1|276|D3|T1|N6', 'the reissue, not the superseded copy');
});

test('underscores in a trip id are matched literally, not as SQL wildcards', () => {
  // `601X0Y1|276|D3|T1|N6` differs from the wanted id only at the underscores.
  // Unescaped, LIKE would match it too and the tie-break could hand it back.
  const r = resolveTrip('601_0_1|280|D3|T1|N6', { now: ON_SERVICE_DAY });
  assert.notEqual(r.trip.headsign, 'Decoy');
  assert.ok(r.trip.trip_id.startsWith('601_0_1|'));
});

test('an id that matches nothing resolves to nothing', () => {
  assert.equal(resolveTrip('999_9_9|280|D9|T9|N9', { now: ON_SERVICE_DAY }), null);
  assert.equal(resolveTrip('gibberish', { now: ON_SERVICE_DAY }), null);
});

test('a trip carries its stops in order, with coordinates and seconds', () => {
  const stops = tripStops('601_0_1|276|D3|T1|N6');
  assert.deepEqual(stops.map((s) => s.stop_code), ['AAA1', 'BBB2', 'CCC3']);
  assert.deepEqual(stops.map((s) => s.stop_sequence), [1, 2, 3]);
  assert.equal(stops[0].arrival_seconds, 28800);
  assert.equal(stops[1].arrival_seconds - stops[0].arrival_seconds, 600, 'the gap the app projects with');
  assert.equal(stops[0].stop_lat, 41.1482);
  assert.deepEqual(stops.map((s) => s.timepoint), [true, false, true]);
});

test('an after-midnight trip keeps its seconds past 86400 rather than wrapping', () => {
  const stops = tripStops('601_0_1|276|D6|T9|N1');
  assert.equal(stops[0].arrival_time, '24:20:00');
  assert.equal(stops[0].arrival_seconds, 87600);
  assert.equal(stops[1].arrival_seconds - stops[0].arrival_seconds, 900);
});

test('when the id misses entirely, line + stop + ETA finds the bus', () => {
  // 07:50 Lisbon, a 602 due at Aliados in 15 minutes -> the 08:05 departure.
  const r = findTripByPattern({
    line: '602',
    stopCode: 'AAA1',
    etaMinutes: 15,
    headsign: 'Matosinhos',
    now: new Date('2026-08-14T07:50:00+01:00'),
  });
  assert.equal(r.match, 'pattern');
  assert.equal(r.trip.trip_id, '602_0_1|276|D3|T1|N2');
  assert.equal(r.trip.direction_id, 1);
});

test('the pattern fallback refuses a bus that is hours from the ETA given', () => {
  // Nothing on line 602 leaves Aliados near 18:00; guessing the 08:05 would be
  // worse than admitting the miss.
  const r = findTripByPattern({
    line: '602',
    stopCode: 'AAA1',
    etaMinutes: 5,
    now: new Date('2026-08-14T18:00:00+01:00'),
  });
  assert.equal(r, null);
});

test('the pattern fallback will not cross to another line', () => {
  const r = findTripByPattern({
    line: '999',
    stopCode: 'AAA1',
    etaMinutes: 15,
    now: new Date('2026-08-14T07:50:00+01:00'),
  });
  assert.equal(r, null);
});

test('the full read reports the line, the match and the expired feed', () => {
  const result = resolvedTripStops('601_0_1|280|D3|T1|N8', { now: ON_SERVICE_DAY });
  assert.equal(result.requested_trip_id, '601_0_1|280|D3|T1|N8');
  assert.equal(result.trip_id, '601_0_1|276|D3|T1|N8');
  assert.equal(result.match, 'version');
  assert.equal(result.line, '601');
  assert.equal(result.color, '#187EC2');
  assert.equal(result.headsign, 'Aeroporto');
  assert.equal(result.stops.length, 2);
  assert.equal(result.feed_expired, false, 'the 14th is inside the validity window');
});

test('an expired feed is flagged, not withheld', () => {
  // Stop *order* survives a feed reissue; the minute-gaps are what goes stale.
  // Refusing here would cost the client the sequence it can still trust.
  const result = resolvedTripStops('601_0_1|280|D3|T1|N8', { now: PAST_FEED_END });
  assert.equal(result.feed_expired, true);
  assert.equal(result.stops.length, 2);
});

test('a departure with no id at all resolves by pattern', () => {
  // The scheduled half of /departures comes from upstream, which serves times
  // and headsigns only — so there is no id to ask by, and null must mean "use
  // the hints" rather than being stringified into a query that matches nothing.
  const found = resolvedTripStops(null, {
    line: '602',
    stopCode: 'AAA1',
    etaMinutes: 15,
    headsign: 'Matosinhos',
    now: new Date('2026-08-14T07:50:00+01:00'),
  });
  assert.equal(found.match, 'pattern');
  assert.equal(found.trip_id, '602_0_1|276|D3|T1|N2');
  assert.equal(found.requested_trip_id, null);
  assert.ok(found.stops.length > 0);

  // Still no fabricating: no hints, no answer.
  assert.equal(resolvedTripStops(null, { now: ON_SERVICE_DAY }), null);
});

test('the full read falls through to the pattern match, then gives up', () => {
  const hints = {
    line: '602',
    stopCode: 'AAA1',
    etaMinutes: 15,
    headsign: 'Matosinhos',
    now: new Date('2026-08-14T07:50:00+01:00'),
  };
  const found = resolvedTripStops('602_0_1|999|NOPE|T0|N0', hints);
  assert.equal(found.match, 'pattern');
  assert.equal(found.trip_id, '602_0_1|276|D3|T1|N2');

  // Same unknown id, no hints to fall back on: null, so the route can 404 and
  // the client can show the line's stops without times.
  assert.equal(resolvedTripStops('602_0_1|999|NOPE|T0|N0', { now: ON_SERVICE_DAY }), null);
});
