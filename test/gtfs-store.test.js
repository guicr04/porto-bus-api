/**
 * The static store: ingest guarantees and the scheduled reads built on them.
 *
 * Runs against a synthesised feed rather than the real 58 MB zip — the point is
 * the contract (what is rejected, what is dropped, how after-midnight resolves),
 * not Porto's actual timetable.
 */
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import AdmZip from 'adm-zip';

// Must be set before anything imports config.js.
const dir = mkdtempSync(join(tmpdir(), 'porto-bus-store-'));
process.env.DB_PATH = join(dir, 'test.db');

const { ingest, toSeconds } = await import('../src/db/ingest.js');
const { closeDb, getFeedMeta, isFeedExpired, hasData } = await import('../src/db/index.js');
const gtfs = await import('../src/clients/gtfs.js');
const { scheduledDepartures, activeServiceIds } = await import('../src/db/schedule.js');
const { lineStops, lineShape } = await import('../src/db/lines.js');
const { isUpstreamOutage } = await import('../src/services/live.js');

/** A minimal but structurally real feed. */
function makeZip(overrides = {}) {
  const files = {
    'agency.txt': 'agency_id,agency_name\nSTCP,Test\n',
    'calendar.txt': 'service_id,monday,start_date,end_date\n',
    'calendar_dates.txt': 'service_id,date,exception_type\nSVC1,20260815,1\nSVC0,20260814,1\n',
    'feed_info.txt':
      'feed_publisher_name,feed_version,feed_start_date,feed_end_date\nSTCP,v1,20260729,20260815\n',
    'routes.txt':
      'route_id,route_short_name,route_long_name,route_color,route_text_color,route_type,route_sort_order\n' +
      'R1,500,"CORDOARIA - MATOSINHOS",187EC2,FFFFFF,3,10\n',
    'stops.txt':
      'stop_lat,stop_lon,stop_id,stop_code,stop_name,zone_id\n' +
      '41.1482,-8.6108,AAA1,AAA1,AV. ALIADOS,PRT1\n' +
      '41.1500,-8.6200,BBB2,BBB2,CARMO,PRT1\n' +
      '41.2541,-8.6537,.,.,.,MAI2\n', // the junk row the real feed carries
    'trips.txt':
      'route_id,service_id,trip_id,trip_headsign,direction_id,shape_id,block_id\n' +
      'R1,SVC1,T1,Matosinhos,0,SH1,B1\n' +
      'R1,SVC1,T2,Matosinhos,0,SH1,B1\n' +
      'R1,SVC0,T3,Matosinhos,0,SH1,B1\n',
    'stop_times.txt':
      'trip_id,arrival_time,departure_time,stop_id,stop_sequence,timepoint,shape_dist_traveled\n' +
      'T1,08:00:00,08:00:00,AAA1,1,1,0.0\n' +
      'T1,08:10:00,08:10:00,BBB2,2,0,1200.0\n' +
      'T2,09:00:00,09:00:00,AAA1,1,1,0.0\n' +
      // an after-midnight trip belonging to the PREVIOUS service day
      'T3,24:20:00,24:20:00,AAA1,1,1,0.0\n',
    'shapes.txt':
      'shape_pt_lat,shape_pt_lon,shape_dist_traveled,shape_id,shape_pt_sequence\n' +
      '41.1482,-8.6108,0.0,SH1,1\n' +
      '41.1500,-8.6200,1200.0,SH1,2\n',
    ...overrides,
  };
  const zip = new AdmZip();
  for (const [name, body] of Object.entries(files)) zip.addFile(name, Buffer.from(body, 'utf8'));
  return zip.toBuffer();
}

before(async () => {
  await ingest({ zip: makeZip(), url: 'test://feed', name: 'test feed' });
});

after(() => {
  closeDb();
  rmSync(dir, { recursive: true, force: true });
});

test('toSeconds handles after-midnight hours without wrapping', () => {
  assert.equal(toSeconds('00:00:00'), 0);
  assert.equal(toSeconds('08:30:00'), 30600);
  assert.equal(toSeconds('24:39:00'), 88740); // past midnight, must not wrap to 2340
  assert.equal(toSeconds('nonsense'), null);
});

test('ingest loads the feed and records its identity', async () => {
  assert.ok(hasData());
  const meta = getFeedMeta();
  assert.equal(meta.resource_name, 'test feed');
  assert.equal(meta.feed_start_date, '20260729');
  assert.equal(meta.feed_end_date, '20260815');
});

test('ingest drops the placeholder "." stop rather than serving it', async () => {
  const stops = await gtfs.getStops();
  assert.equal(stops.length, 2);
  assert.ok(!stops.some((s) => s.stop_code === '.'));
});

test('ingest normalises bare GTFS colours to #-prefixed', async () => {
  const [line] = await gtfs.getLines();
  assert.equal(line.color, '#187EC2');
  assert.equal(line.line, '500');
});

test('ingest refuses a feed whose stop_id and stop_code diverge', async () => {
  const zip = makeZip({
    'stops.txt':
      'stop_lat,stop_lon,stop_id,stop_code,stop_name,zone_id\n' +
      '41.1,-8.6,INTERNAL9,AAA1,AV. ALIADOS,PRT1\n',
  });
  await assert.rejects(() => ingest({ zip, url: 'test://bad' }), /stop_code/);
});

test('ingest refuses a feed that populates calendar.txt', async () => {
  const zip = makeZip({
    'calendar.txt':
      'service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n' +
      'SVC1,1,1,1,1,1,0,0,20260729,20260815\n',
  });
  await assert.rejects(() => ingest({ zip, url: 'test://bad' }), /calendar_dates/);
});

test('a rejected ingest leaves the previous store intact', async () => {
  await assert.rejects(() => ingest({ zip: makeZip({ 'calendar.txt': 'service_id\nX\n' }) }));
  const stops = await gtfs.getStops();
  assert.equal(stops.length, 2, 'the good feed should still be there');
  assert.equal(getFeedMeta().resource_name, 'test feed');
});

test('bbox reads only the stops inside the box', async () => {
  const inside = await gtfs.getStopsInBBox({ minLat: 41.14, maxLat: 41.16, minLon: -8.63, maxLon: -8.6 });
  assert.equal(inside.length, 2);
  const elsewhere = await gtfs.getStopsInBBox({ minLat: 40, maxLat: 40.5, minLon: -9, maxLon: -8.9 });
  assert.equal(elsewhere.length, 0);
});

test('scheduled departures come from the day’s active service', () => {
  assert.deepEqual(activeServiceIds('20260815'), ['SVC1']);
  const at0730 = new Date('2026-08-15T07:30:00+01:00');
  const rows = scheduledDepartures('AAA1', { now: at0730, windowMinutes: 120 });
  assert.deepEqual(
    rows.map((r) => r.clock),
    ['08:00', '09:00'],
  );
  assert.equal(rows[0].minutes, 30);
  assert.equal(rows[0].line, '500');
});

test('a trip past midnight is found under the previous service day', () => {
  // 00:10 on the 15th: the 24:20 departure belongs to the 14th's service.
  const justAfterMidnight = new Date('2026-08-15T00:10:00+01:00');
  const rows = scheduledDepartures('AAA1', { now: justAfterMidnight, windowMinutes: 60 });
  assert.equal(rows.length, 1);
  assert.equal(rows[0].clock, '24:20');
  assert.equal(rows[0].minutes, 10);
});

test('the scheduled board never claims a bus is on time', async () => {
  const { scheduledStopBoard } = await import('../src/db/schedule.js');
  const board = scheduledStopBoard('AAA1', { now: new Date('2026-08-15T07:30:00+01:00') });
  assert.equal(board.data_source, 'scheduled');
  assert.ok(board.arrivals.length > 0);
  for (const a of board.arrivals) {
    assert.equal(a.status, null, 'status implies tracking the timetable cannot provide');
    assert.equal(a.delay_minutes, null);
    assert.equal(a.estimated_arrival_time, null);
  }
});

test('line reads pick the longest trip for the direction', () => {
  const stops = lineStops('500', 0);
  assert.equal(stops.stops.length, 2, 'T1 covers more stops than T2');
  assert.deepEqual(stops.timepoint_stop_ids, ['AAA1']);
  assert.equal(lineShape('500', 0).coordinates.length, 2);
  assert.equal(lineStops('500', 1), null, 'no trips in that direction');
  assert.equal(lineShape('NOPE', 0), null);
});

test('stop -> routes is derived at ingest, deduped across trips', async () => {
  // T1 and T2 both serve AAA1 on route R1: the pair must appear once, not twice.
  const lines = await gtfs.getStopLinesInBBox({
    minLat: 41.14, maxLat: 41.16, minLon: -8.63, maxLon: -8.6,
  });
  const aliados = lines.find((s) => s.stop_code === 'AAA1');
  assert.deepEqual(aliados.lines.map((l) => l.line), ['500']);
  assert.equal(aliados.lines[0].color, '#187EC2', 'carries the colour for the badge');

  // BBB2 is served only by T1, which is also R1.
  const carmo = lines.find((s) => s.stop_code === 'BBB2');
  assert.deepEqual(carmo.lines.map((l) => l.line), ['500']);
});

test('stop -> routes covers only stops inside the box', async () => {
  const far = await gtfs.getStopLinesInBBox({
    minLat: 40, maxLat: 40.5, minLon: -9, maxLon: -8.9,
  });
  assert.deepEqual(far, []);
});

test('an expired feed disqualifies the store from standing in', () => {
  assert.equal(isFeedExpired(new Date('2026-08-15T12:00:00+01:00')), false, 'last valid day');
  assert.equal(isFeedExpired(new Date('2026-08-16T12:00:00+01:00')), true);
});

test('only upstream outages fall back; a 404 propagates', () => {
  const err = (status) => Object.assign(new Error('x'), status === undefined ? {} : { status });
  assert.equal(isUpstreamOutage(err(undefined)), true, 'timeout / connection refused');
  assert.equal(isUpstreamOutage(err(503)), true);
  assert.equal(isUpstreamOutage(err(429)), true);
  assert.equal(isUpstreamOutage(err(404)), false, 'never fabricate a board for a missing stop');
  assert.equal(isUpstreamOutage(err(400)), false);
});
