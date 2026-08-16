-- The static store: Porto's GTFS feed, ingested whole.
--
-- Written only by scripts/gtfs-refresh.js, and only inside one transaction, so
-- readers never observe a half-loaded feed. See README §2a for the reasoning
-- behind the shape of this — in particular why there is no `calendar` table and
-- why `stop_code` is the key.

-- Exactly one row: which feed this database was built from. Lets /health answer
-- "is my static data stale?" as a check rather than a guess.
CREATE TABLE IF NOT EXISTS feed_meta (
  id              INTEGER PRIMARY KEY CHECK (id = 1),
  resource_name   TEXT,
  source_url      TEXT NOT NULL,
  feed_version    TEXT,
  feed_start_date TEXT,          -- YYYYMMDD, from feed_info.txt
  feed_end_date   TEXT,          -- YYYYMMDD; past this the feed is expired
  ingested_at     TEXT NOT NULL  -- ISO 8601
);

-- stop_code is the key and stop_id is deliberately absent: they are identical
-- across all 2,569 rows of the feed, and the whole API speaks stop_code. The
-- ingest asserts that equality rather than silently preferring one.
CREATE TABLE IF NOT EXISTS stops (
  stop_code TEXT PRIMARY KEY,
  name      TEXT NOT NULL,
  lat       REAL,
  lon       REAL,
  zone_id   TEXT
);
-- Exists for one caller: the map's bounding-box query.
CREATE INDEX IF NOT EXISTS stops_lat_lon ON stops (lat, lon);

CREATE TABLE IF NOT EXISTS routes (
  route_id   TEXT PRIMARY KEY,
  short_name TEXT,
  long_name  TEXT,
  color      TEXT,               -- '#'-prefixed; normalised at ingest
  text_color TEXT,
  route_type INTEGER,
  sort_order INTEGER
);
CREATE INDEX IF NOT EXISTS routes_short_name ON routes (short_name);

CREATE TABLE IF NOT EXISTS trips (
  trip_id      TEXT PRIMARY KEY,
  route_id     TEXT NOT NULL,
  service_id   TEXT NOT NULL,
  headsign     TEXT,
  direction_id INTEGER,
  shape_id     TEXT,
  block_id     TEXT
);
CREATE INDEX IF NOT EXISTS trips_route_dir ON trips (route_id, direction_id, service_id);
CREATE INDEX IF NOT EXISTS trips_shape     ON trips (shape_id);
CREATE INDEX IF NOT EXISTS trips_service   ON trips (service_id);

-- The big one (~850k rows). WITHOUT ROWID because it is always reached by its
-- natural composite key, so SQLite's implicit rowid would be pure overhead.
--
-- Times are kept as GTFS text ("HH:MM:SS", legitimately exceeding 24h for
-- after-midnight trips) because zero-padded lexicographic order is already
-- chronological order. The *_seconds columns exist so arithmetic doesn't have
-- to parse 850k strings at query time.
CREATE TABLE IF NOT EXISTS stop_times (
  trip_id          TEXT    NOT NULL,
  stop_sequence    INTEGER NOT NULL,
  stop_code        TEXT    NOT NULL,
  arrival_time     TEXT    NOT NULL,
  departure_time   TEXT    NOT NULL,
  arrival_seconds  INTEGER NOT NULL,
  departure_seconds INTEGER NOT NULL,
  timepoint        INTEGER,
  dist_traveled    REAL,
  PRIMARY KEY (trip_id, stop_sequence)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS stop_times_stop ON stop_times (stop_code, departure_seconds);

CREATE TABLE IF NOT EXISTS shapes (
  shape_id      TEXT    NOT NULL,
  sequence      INTEGER NOT NULL,
  lat           REAL    NOT NULL,
  lon           REAL    NOT NULL,
  dist_traveled REAL,
  PRIMARY KEY (shape_id, sequence)
) WITHOUT ROWID;

-- Which lines serve which stop. Derived at ingest from stop_times x trips, not
-- read from the feed: GTFS states it only implicitly, and rediscovering it per
-- request means a DISTINCT over 850k rows (~200ms for a neighbourhood) for an
-- answer that changes once a day. Precomputed it is a few thousand rows and a
-- primary-key lookup, which is what lets the map label pins with line numbers.
CREATE TABLE IF NOT EXISTS stop_routes (
  stop_code TEXT NOT NULL,
  route_id  TEXT NOT NULL,
  PRIMARY KEY (stop_code, route_id)
) WITHOUT ROWID;

-- From calendar_dates.txt alone. calendar.txt is empty in this feed: STCP
-- expresses service purely as dated exceptions, which is why there are no
-- weekly day-flags anywhere in this schema.
CREATE TABLE IF NOT EXISTS service_dates (
  service_id     TEXT    NOT NULL,
  date           TEXT    NOT NULL,  -- YYYYMMDD
  exception_type INTEGER NOT NULL,  -- 1 added, 2 removed
  PRIMARY KEY (service_id, date)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS service_dates_date ON service_dates (date);
