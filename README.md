# Porto Bus API

A personal wrapper API around Porto's **STCP** (Sociedade de Transportes Colectivos
do Porto) bus system. Built in **Node.js + Express (plain JavaScript)**, with
type-only `.d.ts` domain files as the shared data contract.

It sits on two data sources: the **official static GTFS feed** and **STCP's own
public JSON API** (the one their website calls for live arrivals).

This README doubles as the project's knowledge base.

---

## 1. Why Node + JS (and where Python might come back)
The frontend and future services are JavaScript, so the backend is too: one
language, one toolchain, copy-paste-able fetch calls, shared types. Right now this
is "just a clean API to consume," which is a thin proxy over STCP's JSON — Node does
that as well as anything.

Types live in `types/*.d.ts` (declaration-only, no runtime cost). JS files reference
them through JSDoc, so VS Code type-checks the code with **no build step**. A future
JS/TS frontend can import the same `.d.ts` as its contract.

> If heavy schedule/delay **data analysis** ever shows up, that's Python's turf —
> spin it up as a *separate* service that consumes the GTFS feed. Split by job, not
> by force.

## 2. How STCP data works

### A) Official static data — GTFS (schedules, stops, routes)
Porto's open-data portal publishes STCP's timetable data in **GTFS** format.
- Portal: https://opendata.porto.digital  (dataset: `horarios-paragens-e-rotas-em-formato-gtfs-stcp`)

> **The feed URL is not stable.** The portal publishes a *new resource with a new
> UUID* every few days (55+ and counting, most named `gtfs_feed.zip`), and older
> ones eventually 404. So we don't hardcode a URL: `gtfs.js` asks the portal's
> CKAN API (`/api/3/action/package_show`) for the dataset, sorts the zip resources
> by `last_modified`, and downloads the newest. Set `GTFS_URL` only to pin a
> specific feed or point at a local copy. Verified: a pinned URL had already gone
> 404, auto-resolution picked up "GTFS STCP 18-07-2026 Mais Recente".

We ingest the **whole feed** — stops, routes, trips, stop_times, shapes and
calendar_dates — into a local SQLite store (§2a). Stable and legal; scheduled
data, not live positions.

### B) Live data — STCP's public JSON API
The modern stcp.pt website calls a clean REST-ish JSON API (no auth). Captured from
its own network traffic:

| Endpoint                                         | Returns                                   |
|--------------------------------------------------|-------------------------------------------|
| `GET /api/stops/{code}/realtime`                 | live arrivals board                       |
| `GET /api/stops/{code}/routes`                   | routes (lines) serving the stop           |
| `GET /api/stops/{code}/services?date=YYYY-MM-DD` | which service_ids run that day            |
| `GET /api/stops/{code}/schedule?route_id=&service_id=&direction_id=` | timetable for route+service+direction |

Base URL: `https://stcp.pt/api`

The site also has a **line-centric** API under `/api/route/{line}`:

| Endpoint                                              | Returns                                  |
|-------------------------------------------------------|------------------------------------------|
| `GET /api/route/{line}/stops/direction?direction_id=` | full ordered stop list for a direction   |
| `GET /api/route/{line}/shape?direction_id=`           | map polyline (`lat/lng/sequence` points) |
| `GET /api/route/{line}/services?date=`                | service_ids running that day (string array) |
| `GET /api/route/{line}/schedule?service_id=&direction_id=` | full timetable grid (trips x timepoint stops) |

Two quirks handled in the parsers: the line `services` payload is a plain string
array (the stop version returns objects), and the schedule's `selected_stops` use
camelCase keys (`stopId`, `stopLat`) while `stops/direction` uses snake_case — both
are normalised to the same `DirectionStop` shape.

Timetable is a two-step lookup: **services** gives the valid `service_id`s (GTFS-style
calendar keys like `DOM|FERIADO:FLUXO 3.1 20260718` = Sunday/holiday flow) — use
`active_service_id` / `is_active_today` — then **schedule** takes a `route_id` +
`service_id` + `direction_id`.

> Gotcha handled for you: `service_id` is matched exactly server-side, so spaces are
> sent as `%20` (via `encodeURIComponent`), never `+` (which `URLSearchParams` would
> produce and which would break the lookup).

Three more quirks confirmed against the live API (July 2026):

- **`/stops/{code}/routes` returns two lists.** `display_routes` (no direction info)
  and `dropdown_routes` (carries `direction_id`, `direction_name`, `trip_headsign`).
  We expose them as `routes` and `directions`. A route appears in `dropdown_routes`
  **once per stop**, with the direction that actually serves that stop — a stop is on
  one side of the street — so auto-detecting direction by route id is unambiguous.
- **The day flags in `services` are always `0`.** `monday`…`sunday` come back zeroed
  from upstream for every service; that's their bug, not ours. Use `is_active_today`
  (or `active_service_id` on the line endpoint) — it *is* correct.
- **Two different colours for the same line.** The routes endpoints return a coarse
  family colour (`#187EC2` for essentially every city line), while the realtime board
  returns the line's real colour (300 → `#417DBD`). Prefer the realtime one where a
  live context exists — but `/lines` has no live context at all (it's a global list,
  not tied to a stop), so it exposes the coarse GTFS colour instead (`color` /
  `text_color`, from `routes.txt`'s `route_color`/`route_text_color`, normalised to
  `#RRGGBB`). That coarse colour still varies meaningfully: confirmed against the
  live feed, city lines share `#187EC2`, the **M-family is genuinely black**
  (`#000000`), and e.g. line 701 is red (`#FF0000`) — it's a family colour, not a
  useless one.

These endpoints are undocumented (no contract/SLA) — cache politely, don't hammer.

## 2a. The static store — GTFS in SQLite

GTFS used to be parsed into two in-memory `Map`s (stops + routes) on the first
request after a one-week TTL. That was fine while those were the only two files
we read. It stopped being fine for three reasons:

1. **Nothing survived a restart.** Every deploy, crash or `npm start` re-paid a
   7 MB download and a full parse on whichever request arrived first.
2. **The refresh sat on the request path.** A rider's board waited on the
   portal, with a timeout of `httpTimeoutMs * 3`.
3. **We could only afford two files.** Everything time-related therefore came
   from upstream — see the fallback ladder below for why that matters.

So the static half of the data now lives in a **SQLite file**, built by a
standalone ingest and read by everything else.

### Driver: `node:sqlite`, and the flag it needs

**Running this requires Node 22.5+ and `--experimental-sqlite`**, which is baked
into the npm scripts (`start`, `dev`, `test`, `gtfs:refresh`). Run `node
src/index.js` directly and it will fail on the import.

That was not the first choice. better-sqlite3 is the more established option and
was installed first, but its darwin-arm64 prebuild **segfaults on open** here
(Node 22.12, `NODE_MODULE_VERSION` 127), and `npm rebuild --build-from-source`
silently reuses the same prebuild rather than compiling. Depending on a native
build that has to work on a dev Mac *and* in the container, to get an API the
runtime already ships, is a bad trade. The flag is the price; it goes away on
Node 24, where `node:sqlite` is unflagged.

### Why SQLite and not Postgres

It's a file: no second container, no connection string, no ops. The data is
written once a day and read constantly, which is precisely what SQLite is best
at, and a bounding-box query over an index is all the "geospatial" the map
needs. Postgres/PostGIS becomes right the day this API runs as more than one
instance — and that migration stays cheap because **`clients/gtfs.js` keeps its
exported surface unchanged** (`getStops`, `getStop`, `getLines`, `getLine`).
Everything above it already goes through those four functions, so the storage
swap is a change to one file, twice.

### What's actually in the feed

Measured against `GTFS STCP 30-07-2026`, not assumed from the spec:

| File                | Rows      | Note                                     |
|---------------------|-----------|------------------------------------------|
| `stops.txt`         | **2,569** | more than the old `limit` clamp of 2000  |
| `stop_times.txt`    | **850,629** | the reason in-memory was never an option |
| `shapes.txt`        | 97,972    | 234 distinct shapes                      |
| `trips.txt`         | 23,488    | 5 service_ids; direction 0/1 near-even   |
| `routes.txt`        | 72        |                                          |
| `calendar_dates.txt`| 18        |                                          |
| `calendar.txt`      | **0 — header only** |                                |

Two consequences worth knowing before reading the schema:

**`calendar.txt` is empty.** STCP expresses service *entirely* through
`calendar_dates`: 5 service_ids x 18 dates, every row `exception_type=1`. There
are no weekly day-flags upstream at all — which is the real reason the `days`
field on `/stops/{code}/services` is unreliable and `is_active_today` is the
one to trust. So there is deliberately **no `calendar` table**. If a future feed
starts populating that file, the ingest fails loudly rather than silently
dropping service rules.

**The feed has a short validity window.** `feed_info.txt` on that resource gives
`feed_start_date=20260729`, `feed_end_date=20260815`, and `calendar_dates`
covers exactly those 18 days. The portal republishes every 2–3 days. A one-week
TTL against an 18-day window was cutting it fine; **the refresh is now daily**,
and the ingest records both dates so the API can say "my static data expired N
days ago" instead of quietly returning nothing.

### Schema

`src/db/schema.sql`. The decisions inside it:

- **`stop_code` is the key; there is no separate `stop_id` column.** Verified
  across all 2,569 rows of the current feed: they are identical. The API and its
  clients speak `stop_code` end to end (`/stops/CMO/realtime`), so carrying both
  would be two names for one thing. The old parser did `row.stop_code ||
  row.stop_id`, which would have *silently* switched identifier if a feed ever
  diverged; the ingest now asserts equality and fails instead.
- **`WITHOUT ROWID` on `stop_times` and `shapes`.** Both are large and always
  accessed by their natural composite key, so SQLite's implicit rowid is pure
  overhead at 850k and 98k rows.
- **Times stay `TEXT` "HH:MM:SS"** — they legitimately exceed 24h ("24:39:00")
  for after-midnight trips, and zero-padded lexicographic order *is*
  chronological order, so `ORDER BY departure_time` needs no special case.
  Alongside them, `departure_seconds` / `arrival_seconds` hold seconds since
  midnight so arithmetic (ETA maths, the eventual vehicle interpolation) doesn't
  parse 850k strings at query time.
- **`stops(lat, lon)` is indexed** — that index exists for one caller, the map's
  bounding-box query.
- **One junk row is dropped at ingest**: `stops.txt` contains an entry whose
  `stop_id`, `stop_code` and `stop_name` are all literally `"."`. It was being
  served to clients.
- **`stop_routes` is derived, not read.** GTFS states which lines serve a stop
  only implicitly, through `stop_times x trips`. Rediscovering that per request
  is a `DISTINCT` over 850k rows — ~200 ms for a neighbourhood — for an answer
  that changes once a day. Collapsed at ingest it is **5,220 rows**, and
  `/stops/lines?bbox=` answers a screenful in ~10 ms. That difference is what
  makes the map's line labels affordable at all; per-stop requests would not be.
- **Line names fall back to `route_id`.** One route (`39`, "SERVIÇO ESPECIAL
  METRO") carries no `route_short_name`. A null there is a badge with nothing
  written on it — or, for a client whose model types the line as non-optional,
  a decode failure that silently takes down a whole region's labels.

### Ingest and refresh

`scripts/gtfs-refresh.js` — standalone, and the only thing that writes:

```bash
npm run gtfs:refresh          # or: make gtfs
```

- It **replaces the contents in a single transaction** (`BEGIN; DELETE; INSERT;
  COMMIT;`) rather than swapping files. SQLite gives atomicity for free and, in
  WAL mode, readers never block on the writer — so there is no window where a
  request can see a half-loaded feed. Prepared statements inside one transaction
  matter here: the same 850k inserts committed individually take minutes.
- **The server also runs it at boot** when the database is missing or older than
  `GTFS_TTL_SECONDS` (now one day). This is deliberate: a fresh clone with no
  scheduler configured must still work, and dev must not require a cron.
- **Cron is therefore pure optimisation** — a daily `npm run gtfs:refresh` keeps
  the refresh off both the boot path and the request path. Nothing breaks
  without it.
- `feed_meta` records the resource name, source URL, feed version, validity
  window and ingest timestamp. `/health` surfaces them, so "is my data stale?"
  is a check rather than a guess.

### Live `trip_id` does not join to GTFS `trip_id` verbatim

Needed by the app's line-detail screen (app repo `DESIGN.md` §11.1, Phase 2),
and non-obvious enough to be worth recording where the data lives.

The live board's `Arrival.trip_id` and the store's `trips.trip_id` share a
format but are not equal. Live serves `601_0_1|280|D3|T1|N6`; the store holds
`601_0_1|276|D3|T1|N6`. The second pipe-delimited field is a **feed-version
counter** — STCP was serving schedule `280` while the ingested zip was `276` —
and matching verbatim resolves **nothing at all**.

Strip that one field and it resolves. Measured against the live feed on
2026-08-16 across 8 busy stops: **0 of 71** arrivals matched verbatim, **71 of
71** matched normalised, each to exactly one trip, with the headsigns agreeing
every time and a usable `direction_id`. Two conditions:

- **Strip the version field** on both sides before comparing.
- **Filter to the day's active service.** 5,386 of 12,716 normalised keys
  collide. They are not scattered: every one is a `UTIL FERIAS` weekday pattern
  reissued as versions 274 / 275 / 276 of itself, so a weekday live id lands on
  a three-way tie and a Saturday one does not. Today's service breaks it.
  (That is also why the 71/71 sample above cannot be read as evidence the filter
  is unnecessary — it landed on a Saturday.)

It is a join on an undocumented id format, so it needs a fallback, not trust: on
a miss, match by line + headsign + nearest scheduled departure; on a second
miss, return the stop list without times. Never fabricate one. `/trips/{trip_id}/stops`
(§4c) implements exactly that chain and reports which rung answered.

### Live-first, with the store as the fallback

Before this, **every time-related answer in the system was an upstream call** —
including the one named "scheduled fallback". `services/departures.js` makes
four STCP calls and zero GTFS calls; the scheduled half was a fallback against
*one bus not being tracked*, never against STCP being unavailable. The practical
effect was that if STCP went down, every screen in the app went blank at once.

With trips, stop_times, shapes and service_dates in the store, that failure mode
becomes "today's timetable, clearly labelled" instead of "nothing":

| Endpoint                    | Primary | Fallback | Fidelity when degraded          |
|-----------------------------|---------|----------|---------------------------------|
| `/stops/{code}/realtime`    | STCP    | store    | scheduled times, no delay info  |
| `/stops/{code}/departures`  | STCP    | store    | the scheduled half survives alone |
| `/board`                    | STCP    | store    | walk times exact; ETAs from timetable |
| `/lines/{line}/schedule`    | STCP    | store    | near-identical — static data    |
| `/lines/{line}/stops`       | STCP    | store    | near-identical                  |
| `/lines/{line}/shape`       | STCP    | store    | geometry, **measured identical** |

**Live stays primary**, and there is now evidence for why rather than just
caution. On 15 Aug 2026 — a Saturday, and Assumption Day — the two sources
disagreed about what day it was:

```
store (calendar_dates 20260815): SABADO:Fluxo 3 20260801
STCP live:                       DOM|FERIADO:FLUXO 3 20260814
```

STCP is right: holiday service, not Saturday service. **The GTFS feed's
calendar does not encode Portuguese public holidays**, so the scheduled fallback
will serve the wrong timetable on roughly a dozen days a year. It is still far
better than a blank screen, but it is exactly why the store is the fallback and
not the source, and why a client must never present it as live.

The opposite result came back for geometry. Diffing every coordinate of
`/lines/{line}/shape` between the two sources, for lines 200/300/500 in both
directions, they are **identical** — same point count, same values to six
decimal places. So serving shapes from the store first would cost nothing in
fidelity and would save an upstream round-trip on every map pan. Left as-is for
now (live-first, deliberately), but the measurement is here when the map makes
it worth changing.

Three rules keep the degradation honest:

1. **Say so in the response.** The contract already has the vocabulary —
   `RealtimeStop.data_source`, `BoardRow.realtime`,
   `CombinedDeparture.source`. It's extended to the endpoints that lacked it, as
   optional fields, so existing clients keep decoding. A client should render
   "live data unavailable, showing today's timetable", never a blank screen.
2. **Never serve an expired feed as if it were current.** If `feed_end_date` has
   passed, the fallback flags it hard rather than projecting a stale timetable
   onto today. Same principle as refusing to draw an inferred bus position as a
   confident GPS dot.
3. **Be careful what triggers it.** Timeout, connection refused and 5xx fall
   back; a 404 (the stop genuinely doesn't exist) propagates and is never
   fabricated. A short circuit-breaker prevents paying the full upstream timeout
   on every request while STCP is down — otherwise a degraded API is merely a
   very slow one.

## 3. Project structure
```
porto-bus-api/
  package.json
  Makefile               # setup / dev / test / smoke / postman
  jsconfig.json          # turns on JSDoc type-checking in the editor
  .env.example
  postman/
    porto-bus-api.postman_collection.json
  test/
    combine.test.js      # the merge logic, incl. the dedup edge cases
    board.test.js        # walking model + "can I still catch it"
    gtfs-store.test.js   # ingest against a small fixture feed, and the store reads
    trips.test.js        # the live trip_id join and its fallbacks
  types/
    domain.d.ts          # the shared type contract (Arrival, StopSchedule, ...)
  src/
    index.js             # Express app + server
    config.js            # env loading
    clients/
      stcp.js            # stop-centric API: realtime, routes, services, schedule
      route.js           # line-centric API: stops, shape, services, schedule
      gtfs.js            # static data, read from the SQLite store (§2a)
    lib/
      http.js            # shared fetch helper + query encoder
      cache.js           # tiny TTL cache (keeps the display off stcp.pt's back)
      geo.js             # haversine + the walking model
      board.js           # pure board assembly + fixed-width renderer
      parse.js           # raw payload -> domain types (validated mappers)
      csv.js             # tiny RFC-4180 CSV parser for GTFS
      time.js            # Europe/Lisbon time helpers (after-midnight aware)
      combine.js         # pure merge of live + scheduled departures
    services/
      departures.js      # orchestrates the combined /departures view
      board.js           # nearby stops -> one board
    routes/
      stops.js           # /stops/* endpoints
      lines.js           # /lines/*
      trips.js           # /trips/{trip_id}/stops — one live bus's journey
      board.js           # /board and /board.txt
    db/
      schema.sql         # the static store's tables (§2a)
      index.js           # open the DB, set pragmas, WAL
      trips.js           # live trip_id -> a trip in the store, and its stops
  scripts/
    geocode.js           # address -> coordinates, run once at setup
    gtfs-refresh.js      # download the feed -> SQLite; daily, and at boot if stale
```

## 4. Endpoints (v0)
| Method | Path                          | Source | Description                     |
|--------|-------------------------------|--------|---------------------------------|
| GET    | `/health`                     | store  | liveness + which GTFS feed is loaded, whether it has expired, and upstream breaker state |
| GET    | `/board?lat=&lon=&walk_minutes=&sort=` | live+GTFS | **what can I catch on foot from here**, by line |
| GET    | `/board.txt?width=&title=&color=` | live+GTFS | same, as fixed-width text; `color=1` greens live times |
| GET    | `/stops?q=&bbox=&limit=`      | store  | list/search stops; `bbox=minLon,minLat,maxLon,maxLat` for the map |
| GET    | `/stops/lines?bbox=`          | store  | which lines serve each stop in a region — one query, for map labels |
| GET    | `/stops/{code}`               | store  | one stop's details              |
| GET    | `/stops/{code}/realtime`      | live+store | next buses at a stop; falls back to the timetable, tagged `data_source` |
| GET    | `/stops/{code}/departures?line=` | live+GTFS | **combined** live + scheduled for one line |
| GET    | `/stops/{code}/routes`        | live   | routes serving a stop           |
| GET    | `/stops/{code}/services?date=`| live   | service_ids running that day    |
| GET    | `/stops/{code}/schedule`      | live   | timetable (route+service+dir)   |
| GET    | `/lines`                      | store  | list all lines, incl. `color`/`text_color` |
| GET    | `/lines/{line}/stops?direction_id=`   | live+store | ordered stops for a direction |
| GET    | `/lines/{line}/shape?direction_id=`   | live+store | map polyline for a direction  |
| GET    | `/lines/{line}/services?date=`        | live+store | service_ids running that day  |
| GET    | `/lines/{line}/schedule?service_id=&direction_id=` | live | full timetable grid |
| GET    | `/trips/{trip_id}/stops` | store | **where this specific bus goes next** — the resolved trip's ordered stops and scheduled times (§4c) |
| GET    | `/trips/stops?line=&stop=&eta_minutes=` | store | the same, for a departure with no id to ask by (§4c) |

Example: `GET /stops/CMO/realtime` -> the CARMO board.

## 4a. Combined departures — "when's my bus"

`GET /stops/{code}/departures?line=305` is the merged view for one line at one stop:
it takes the live board and fills the gaps with the static timetable, so you get live
ETAs for the near term and scheduled times after that.

Every departure is tagged **`source: "realtime" | "scheduled"`** so the frontend can
render them differently (a live "3 min" pill vs a faded scheduled "16:50"). Live
entries also carry `status` and `delay_minutes`.

Query params: `line` (required), `service_id` (optional — defaults to today's active
service), `direction_id` (optional — auto-detected from the stop's routes),
`window_minutes` (dedup tolerance, default 3), `limit` (default 10).

How it merges: live wins; a scheduled departure that duplicates a live one is
dropped; past times are skipped; the rest is sorted by time.

**How duplicates are detected.** A late bus appears at its *estimated* time, which
can sit further from its scheduled minute than any sane tolerance — a real case:
line 300 estimated **18:43** with `delay_minutes: 5` is the **18:38** slot, five
minutes off. Matching on the estimate alone listed that bus twice, once live and
once scheduled. So when the feed gives a delay we subtract it to recover the exact
scheduled slot (±1, since ETA and delay are both rounded upstream) and match on
that. `window_minutes` is now only the *fallback* for arrivals with no delay value.

Colours are taken from the live board and applied to the scheduled entries too, so
one line renders in one colour regardless of which source an entry came from.

## 4c. One bus's journey — `/trips/{trip_id}/stops`

Everything else here is stop-centric, because STCP's live API is. This is the one
endpoint that answers the other question: *this* bus, the one arriving in nine
minutes — where does it go, and when does it get there? It is what the iOS app's
line-detail screen is built on (app repo `DESIGN.md` §11.1, Phase 2).

Store-only. There is no upstream equivalent to fall back to or prefer.

```
GET /trips/501_0_1%7C280%7CD3%7CT2%7CN6/stops
    ?line=501&headsign=Matosinhos%20(Praia)&stop=CMO&eta_minutes=9
```
```json
{
  "trip_id": "501_0_1|276|D3|T2|N6",
  "requested_trip_id": "501_0_1|280|D3|T2|N6",
  "match": "version",
  "route_id": "501", "line": "501", "color": "#F5D24C",
  "headsign": "Matosinhos (Praia)", "direction_id": 0,
  "service_id": "SABADO:Fluxo 3 20260801",
  "shape_id": "...", "feed_expired": true,
  "stops": [
    { "stop_sequence": 5, "stop_code": "CMO", "stop_name": "CARMO",
      "stop_lat": 41.147, "stop_lon": -8.616,
      "arrival_time": "09:07:00", "departure_time": "09:07:00",
      "arrival_seconds": 32820, "departure_seconds": 32820, "timepoint": true }
  ]
}
```

The trip id must be percent-encoded — `|` is not a legal raw path character.

**`GET /trips/stops` is the same answer without an id.** Not every departure has
one: the scheduled half of `/stops/{code}/departures` comes from upstream, which
serves times and headsigns only, so there is nothing to put in the path. Line +
stop + departure time identify the trip perfectly well, and that is already the
bottom rung of the chain below — so this route is that rung reached directly
rather than after a failed lookup. It answers with `match: "pattern"` and
`requested_trip_id: null`.

```
GET /trips/stops?line=900&stop=CMO&eta_minutes=69&headsign=Francelos
-> { "match": "pattern", "trip_id": "900_0_1|276|D2|T1|N7", "stops": [ ... 52 ] }
```

`CombinedDeparture` also gained a `trip_id`, set on every realtime row and on
scheduled rows that came from the store. A client can therefore use the exact
path when it has an id and this one when it doesn't.

**The query params are not filters.** They are the fallback identity, taken
straight off the live board row the caller tapped, and they are only consulted
when the id join misses. Passing them costs the client nothing and is the
difference between a degraded screen and an empty one.

**`match` is the confidence, and the client is expected to read it.** In
descending order: `exact` (ids equal), `version` (equal after the feed-version
field is dropped, and unambiguous), `version_latest` (version-stripped match the
day's service could not narrow — newest feed version won), `pattern` (the id
missed; matched on line + headsign + nearest scheduled departure). A miss on all
four is a **404**, not an empty list: "we cannot identify this bus" is a real and
different answer from "this bus calls nowhere", and the client's response to it
is defined — show the line's stops without times.

**Two traps, both load-bearing.**

- The normalised match is a `LIKE` with the version field wildcarded, and trip
  ids are full of underscores (`601_0_1`) — which are single-character wildcards
  in SQL `LIKE`. Unescaped, `601_0_1|%|D3|T1|N6` also matches `601X0Y1|...`. The
  escaping is tested with a decoy trip that differs only at those positions.
- Times come back as `*_seconds` alongside the clock strings because the whole
  point of this payload is arithmetic on them — a downstream ETA is
  `live ETA + (scheduled(later) − scheduled(mine))` — and doing that on
  `"24:35:00"` re-invites the after-midnight bug the store already solved once.

**Measured against the live feed** (2026-08-16, 71 arrivals across 8 stops):
0 matched verbatim, **71 of 71** matched after dropping the version field, each
to exactly one trip, with headsigns agreeing every time. A resolved read takes
~12 ms including the ~23k-row `LIKE` scan, which is why there is no precomputed
normalised column — it would be complexity bought for nothing, and it would make
the endpoint require a re-ingest before it worked.

That sample cannot exercise the collision case, because it landed on a Saturday
and only the three `UTIL FERIAS` weekday reissues collide. The service filter is
still what makes a weekday unambiguous; the tests cover it directly.

**`feed_expired` rides along rather than blocking the response.** Elsewhere an
expired feed disqualifies the store from standing in for live data, because there
it would be impersonating a measurement. Nothing is impersonated here: the caller
already holds the live ETA and wants the stop order and the gaps between them,
which barely move between reissues. Flagging it lets the client say so instead of
hiding it.

## 4b. Departure board — the desk display

`GET /board` and `GET /board.txt` answer one question: **what can I still catch
from here, on foot?**

```
$ make board
HOME
------------------------------------------
205  Campanhã                          33m
300  Aliados - H.S.João                46m
305  Cordoaria                         36m
701  Codiceira                         13m
702  Travagem                          18m
```

Columns: line, destination, minutes until the bus arrives. **Times shown in green
are ones STCP is actively tracking.**

### Green = live
Each stop's board comes back with a `data_source` field: `"realtime"` when buses are
genuinely being tracked, something else when STCP has fallen back to projecting from
the timetable. Every row carries that through as **`realtime: true | false`**, and
`/board.txt?color=1` prints the live ones green.

Colour is **off by default**. ANSI escapes are for a terminal; a microcontroller
driving an LED panel wants the boolean from `/board` and picks its own green — not
escape codes it has to strip first. `make board` turns colour on.

The escapes are applied *after* padding, so a coloured board still lines up
character-for-character. There's a test pinning that, since it's the kind of thing
that silently rots.

> Caveat worth knowing: every stop observed so far reports `data_source: "realtime"`,
> so in practice everything currently renders green. The non-live path is written
> from the field's documented meaning but has not been seen in the wild — if the
> board ever goes all-plain, that's this flag flipping, not a bug.

### Ordering
Rows are sorted **by line number**, so a given line always sits in the same place on
the display and you read it like a station board rather than re-scanning a shuffling
list. The sort is numeric, not alphabetic — as plain strings `1M` lands between
`100` and `200`, and STCP mixes `300`, `1M`, `10M` and `ZC`. Order is: leading
number ascending, suffixed variants after their bare number, lettered lines last.

`?sort=eta` gives the time-ordered board instead.

Note the two-step: rows are ranked by *arrival* first, so `limit` keeps the next
N buses — only then are they re-sorted by line. Sorting by line up front would
instead show the N lowest-numbered lines whenever they happen to run.

**Collapsing a line to several nearby stops picks the closest stop, not the
soonest bus.** Earlier behaviour kept whichever stop's raw ETA read lowest —
but two stops close together on the same route often report the *same*
physical bus a minute or two apart (a real case: line 701 read 13 min at a
stop 471 m away and 14 min at a stop 317 m away — one minute of tracking
noise), and picking by raw ETA sent riders to the farther stop for no reason.
Now the collapse compares `walk_minutes` (falling back to `distance_meters`,
then `eta_minutes`, to break exact ties) and keeps the nearer stop
unconditionally — even when a farther-but-still-reachable stop has a
meaningfully sooner bus on the same line. That trade-off is deliberate: this
reports what's coming to *your* nearest stop for a line, not the earliest bus
catchable from anywhere nearby.

### Why the walk still matters (even though it isn't shown)
A bus arriving in 3 minutes at a stop 6 minutes away is not a departure — it's
noise. So every stop within the budget carries a walking time, and every row gets a
**`leave_in_minutes`**: how long until you have to get up. Rows where that goes
negative are dropped (or kept and flagged, with `include_unreachable=true`).

The text view no longer prints the walking time — the destination gets that space,
since it's what's actually hard to read when truncated — but both `walk_minutes` and
`leave_in_minutes` are still in the JSON, and the reachability filter still runs.
That filter is what makes this different from a list of nearby buses, and it's the
thing to preserve as the project grows.

### The walking model
Straight-line distance × **1.35** (streets aren't straight; Porto is hilly) ÷ **75
m/min** (~4.5 km/h), rounded up. Deliberately pessimistic — for a board that tells
you whether you can still make it, over-estimating the walk is the safe direction to
be wrong in. Tune with `walk_speed`, or add slack with `buffer=2`.

### Setting the origin
```bash
make geocode ADDRESS="Rua de Santa Catarina 100, Porto"   # prints coordinates
# paste HOME_LAT / HOME_LON into .env
make board
```
Geocoding is a **setup script, not an endpoint**, on purpose: the board is polled
every few seconds by a device on your desk, and geocoding each poll would send a
third party your address over and over for an answer that never changes — and would
breach Nominatim's usage policy. Resolve once, store the numbers, stop calling out.

Pass `?lat=&lon=` to override per request.

### Which stops get polled
Each stop is one upstream call, so `max_stops` (default 12) caps it — but naive
nearest-N is wrong downtown. Around Carmo, 33 stops are within a 10-minute walk, yet
the nearest 6 all sat within 195 m (CORDOARIA alone appears three times, one per
platform): the board claimed a 10-minute radius while seeing about 4 minutes' worth.

So stops are deduplicated before polling — by name *and* by proximity (<60 m), since
the feed spells one place several ways (`GUIL. G. FERNANDES` vs `GUILHERME GOMES
FERNANDES`). Leftover budget then goes to the remaining platforms.
`stops_truncated` in the response tells you when `max_stops` cut things short, so a
thin board reads as "we didn't look everywhere" rather than "nothing runs".

Live arrivals are cached for `REALTIME_TTL_MS` (15s default) — an always-on display
across a dozen stops would otherwise mean hundreds of requests an hour to an
endpoint we have no agreement to use.

### Two representations
- **`/board`** — JSON, for an app or the device firmware.
- **`/board.txt`** — fixed-width `text/plain`, for a microcontroller with no JSON
  parser. `?width=42&title=BEDROOM&color=1`. Line, destination, ETA.

Rendering is separate from assembly (`lib/board.js`), so a future LED/e-ink/app
frontend can take either.

### Where this goes next
The Siri idea ("how do I get to X?") is a **journey planner**, which is a different
problem: it needs an origin *and* a destination, and routing across lines with
transfers. This board is the display half of that, and the natural next step is a
`/journey?to=` endpoint that reuses the same walking model and renders to the same
display.

Kept in this API rather than split into its own service: it needs the in-memory GTFS
stop list and the realtime client, both already here — a separate service would
duplicate them and add a network hop for no gain. It *is* cleanly separable
(`lib/geo.js`, `lib/board.js`, `services/board.js`, `routes/board.js`), so extracting
it later is a move, not a rewrite. The hardware is the separate thing.

## 5. Running it

> **Requires Node 22.5+.** The store uses `node:sqlite`, which needs
> `--experimental-sqlite` on the Node 22 line; every npm script already passes
> it. `npm start` works, `node src/index.js` does not. See §2a.
>
> The API listens on **all interfaces**, despite the startup line printing
> `127.0.0.1` — verified by reaching it on the Mac's LAN address. A phone on the
> same Wi-Fi needs no code change, just `http://<mac-lan-ip>:8000`.

```bash
cd porto-bus-api
npm install
cp .env.example .env
npm run dev      # or: npm start
npm test         # merge logic (node:test, no deps)
```

Or via `make` (run `make` on its own for the full list):

| Command | What it does |
|---------|--------------|
| `make setup`    | install dependencies and create `.env` |
| `make dev`      | run with auto-reload (implies `setup`) |
| `make start`    | run without auto-reload |
| `make stop`     | kill whatever holds the port |
| `make test`     | unit tests |
| `make smoke`    | curl every endpoint, print status codes |
| `make postman`  | run the Postman collection headlessly via newman |
| `make board`    | the departure board, as the display shows it |
| `make watch-board` | refresh it every 30s, like the real device |
| `make geocode ADDRESS="..."` | turn an address into HOME_LAT/HOME_LON |
| `make realtime` / `make departures` / `make service-id` | pretty-printed one-off calls |

Override the defaults inline: `make departures STOP=BOLH LINE=200`.
Server: http://127.0.0.1:8000  — try `/health`, then `/stops`, then `/stops/CMO/realtime`.

All 13 endpoints were verified against the live upstream on 2026-07-19 and return
the shapes documented here.

Requires Node 18+ (uses native `fetch`). If stcp.pt blocks a plain client, set a
browser-like `USER_AGENT` in `.env`.

## 5a. Postman

`postman/porto-bus-api.postman_collection.json` — import it into Postman
(*Import → File*). 23 requests across **Health**, **Stops**, **Lines** and
**Error cases**, covering every endpoint and every parameter combination, plus the
400/404/502 paths so you can tell a bug from expected behaviour.

Collection variables: `baseUrl`, `stopCode` (`CMO`), `line` (`300`), `directionId`,
`serviceId`, `today` (set automatically on each request).

**You never have to paste a `service_id` by hand.** It's an awkward value —
`DOM|FERIADO:FLUXO 3.1 20260718`, matched exactly upstream — so the *services*
requests capture today's active one into `{{serviceId}}` via a test script, and the
schedule requests read it from there. The services requests are ordered before the
schedule ones so a top-to-bottom run just works.

Requests also assert on behaviour, not just status codes: departures must be sorted,
never in the past, tagged with a `source`, rendered in a single colour, and free of
buses that duplicate their own timetable slot. Run headlessly with `make postman`
(23 requests / 76 assertions, all green as of 2026-07-19).

## 6. Roadmap
- [x] Line-centric endpoints: `/lines/{line}/stops`, `/shape`, `/services`, `/schedule` (done).
- [x] Combined live + scheduled departures (`/stops/{code}/departures?line=`), each tagged by source.
- [x] Verified every endpoint against the live API; auto-resolve the GTFS feed.
- [x] Tests for the merge logic (`test/combine.test.js`).
- [x] Nearest-stops-to-me + departure board (`/board`, `/board.txt`).
- [x] Short-TTL cache on live arrivals.
- [x] `color`/`text_color` on `/lines`, from GTFS `route_color`/`route_text_color`.
- [x] Board's line collapse picks the closest stop, not the soonest raw ETA.
- [ ] `GET /lines/{line}/vehicles` — infer vehicle positions from `trip_id` across
      a line's stops, for a live map. Needed by the iOS app's Map tab; see that
      repo's `DESIGN.md` §11.1 for the full design.
- [ ] `/journey?to=` — the Siri "how do I get there" case (needs transfers).
- [ ] Firmware for the display itself (polls `/board.txt`).
- [ ] `limit` is currently ignored on `/lines` — honour it (or drop it from the docs).
- [x] Full GTFS ingest into SQLite, refreshed daily (§2a) — replaces the
      in-memory two-file parse. 850k stop_times in ~10s.
- [x] `/stops?bbox=` for the map, and the `limit` clamp raised — it was
      truncating Porto's 2,568 stops at 2,000.
- [x] Live-first with the store as fallback, tagged by `data_source`, with a
      circuit breaker and an expired-feed guard (§2a).
- [x] `GET /trips/{trip_id}/stops` — the resolved trip's ordered stops and
      scheduled times, for the app's line-detail screen (§4c). 71/71 live
      arrivals resolve; `match` reports which rung of the fallback answered.
      Plus `GET /trips/stops` for departures with no id, and `trip_id` on
      `CombinedDeparture`.
- [ ] Consider serving `/lines/{line}/shape` from the store first — measured
      identical to upstream, and the map pans against it (§2a).
- [ ] Rate-limiting on top of the cache, to stay gentle on stcp.pt.
- [ ] Small map UI (colored by route_color) — separate JS frontend consuming this API.

## 7. Be a good citizen
The `/api` endpoints are undocumented. Keep request rates low, cache, identify via
`User-Agent`, don't hammer stcp.pt. Switch to an official API if STCP ever ships one.
