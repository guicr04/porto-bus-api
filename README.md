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

We parse `stops.txt` (stop code, name, lat/lon) and `routes.txt` (line id + names).
Stable and legal; scheduled data, not live positions.

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
  returns the line's real colour (300 → `#417DBD`). Prefer the realtime one.

These endpoints are undocumented (no contract/SLA) — cache politely, don't hammer.

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
  types/
    domain.d.ts          # the shared type contract (Arrival, StopSchedule, ...)
  src/
    index.js             # Express app + server
    config.js            # env loading
    clients/
      stcp.js            # stop-centric API: realtime, routes, services, schedule
      route.js           # line-centric API: stops, shape, services, schedule
      gtfs.js            # download + parse GTFS zip (stops, routes)
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
      board.js           # /board and /board.txt
  scripts/
    geocode.js           # address -> coordinates, run once at setup
```

## 4. Endpoints (v0)
| Method | Path                          | Source | Description                     |
|--------|-------------------------------|--------|---------------------------------|
| GET    | `/health`                     | —      | liveness check                  |
| GET    | `/board?lat=&lon=&walk_minutes=&sort=` | live+GTFS | **what can I catch on foot from here**, by line |
| GET    | `/board.txt?width=&title=&color=` | live+GTFS | same, as fixed-width text; `color=1` greens live times |
| GET    | `/stops?q=&limit=`            | GTFS   | list/search stops               |
| GET    | `/stops/{code}`               | GTFS   | one stop's details              |
| GET    | `/stops/{code}/realtime`      | live   | next buses at a stop            |
| GET    | `/stops/{code}/departures?line=` | live+GTFS | **combined** live + scheduled for one line |
| GET    | `/stops/{code}/routes`        | live   | routes serving a stop           |
| GET    | `/stops/{code}/services?date=`| live   | service_ids running that day    |
| GET    | `/stops/{code}/schedule`      | live   | timetable (route+service+dir)   |
| GET    | `/lines`                      | GTFS   | list all lines                  |
| GET    | `/lines/{line}/stops?direction_id=`   | live | ordered stops for a direction |
| GET    | `/lines/{line}/shape?direction_id=`   | live | map polyline for a direction  |
| GET    | `/lines/{line}/services?date=`        | live | service_ids running that day  |
| GET    | `/lines/{line}/schedule?service_id=&direction_id=` | live | full timetable grid |

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

Note the two-step: rows are ranked by *arrival* first, so collapsing keeps the
soonest bus per line and `limit` keeps the next N buses — only then are they
re-sorted by line. Sorting by line up front would instead show the N
lowest-numbered lines whenever they happen to run.

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
- [ ] `/journey?to=` — the Siri "how do I get there" case (needs transfers).
- [ ] Firmware for the display itself (polls `/board.txt`).
- [ ] `limit` is currently ignored on `/lines` — honour it (or drop it from the docs).
- [ ] Persist GTFS to disk/DB, refresh weekly.
- [ ] Rate-limiting on top of the cache, to stay gentle on stcp.pt.
- [ ] Small map UI (colored by route_color) — separate JS frontend consuming this API.

## 7. Be a good citizen
The `/api` endpoints are undocumented. Keep request rates low, cache, identify via
`User-Agent`, don't hammer stcp.pt. Switch to an official API if STCP ever ships one.
