/**
 * Domain contract for the Porto Bus API.
 *
 * These are type-only declarations (no runtime code). They describe the shape of
 * STCP's data as our API exposes it, and are the single source of truth shared
 * between this backend and any frontend/service that consumes it.
 *
 * Consume from JS via JSDoc, e.g.:
 *   /** @returns {import('../../types/domain').Arrival} *\/
 * or from a future TS frontend via:
 *   import type { Arrival } from 'porto-bus-api/types/domain';
 */

// ---- stops & lines (static, from GTFS) ------------------------------------

export interface Stop {
  stop_code: string;
  name: string;
  lat: number | null;
  lon: number | null;
}

export interface Line {
  /** short name shown to riders, e.g. "500" */
  line: string;
  /** long name, e.g. "Cordoaria - Matosinhos" */
  description: string;
  /** internal GTFS route id */
  route_id: string;
  /** official GTFS route_color, e.g. "#187EC2"; most city lines share one family colour */
  color: string | null;
  text_color: string | null;
}

// ---- live arrivals: /stops/{code}/realtime --------------------------------

export type ArrivalStatus = 'ON_TIME' | 'DELAYED' | string;

export interface Arrival {
  /** route_short_name, e.g. "305" */
  line: string;
  /** trip_headsign, e.g. "Cordoaria" */
  destination: string;
  /** minutes until arrival; 0 means "Arriving" */
  arrival_minutes: number | null;
  /** ISO 8601 timestamp, e.g. "2026-07-19T16:37:10+01:00" */
  estimated_arrival_time: string | null;
  scheduled_arrival_time: string | null;
  status: ArrivalStatus | null;
  /** + late, - early */
  delay_minutes: number | null;
  /** route_color, e.g. "#417DBD" */
  color: string | null;
  text_color: string | null;
  trip_id: string | null;
}

export interface RealtimeStop {
  stop_code: string;
  stop_name: string | null;
  arrivals: Arrival[];
  last_updated: string | null;
  /** "realtime" when live, otherwise a scheduled fallback */
  data_source: string | null;
}

// ---- routes at a stop: /stops/{code}/routes -------------------------------

export interface StopRoute {
  route_id: string;
  short_name: string;
  long_name: string;
  color: string | null;
  text_color: string | null;
  route_type: number | null;
  /** only present on the per-direction ("dropdown") variant */
  direction_id: number | null;
  direction_name: string | null;
  display_name: string | null;
  trip_headsign: string | null;
}

export interface StopRoutes {
  /** one entry per line (from display_routes) */
  routes: StopRoute[];
  /** one entry per line+direction (from dropdown_routes) */
  directions: StopRoute[];
}

// ---- services at a stop: /stops/{code}/services ---------------------------

export interface ServiceDay {
  service_id: string;
  service_name: string;
  is_active_today: boolean;
  /** monday..sunday flags as returned upstream (often unreliable — prefer is_active_today) */
  days: Record<string, number>;
}

export interface StopServices {
  services: ServiceDay[];
  active_service_id: string | null;
  /** YYYYMMDD as returned upstream */
  selected_date: string | null;
  today: string | null;
}

// ---- timetable: /stops/{code}/schedule ------------------------------------

export interface Departure {
  /** "HH:MM:SS", may exceed 24h (e.g. "24:39:00") for after-midnight trips */
  departure_time: string;
  arrival_time: string;
  headsign: string;
  direction_id: number;
}

export interface StopSchedule {
  stop_code: string;
  route_id: string;
  direction_id: number;
  service_id: string;
  /** flattened out of the hour-keyed buckets and sorted by departure_time */
  departures: Departure[];
}


// ==========================================================================
// LINE-CENTRIC endpoints: /api/route/{line}/...
// ==========================================================================

// ---- /route/{line}/shape?direction_id= (map polyline) --------------------

export interface ShapePoint {
  lat: number;
  lng: number;
  sequence: number;
}

export interface RouteShape {
  route_id: string;
  direction_id: number;
  coordinates: ShapePoint[];
}

// ---- /route/{line}/services?date= ----------------------------------------
// NOTE: differs from the stop version — `services` is a plain string array.

export interface RouteServices {
  route_id: string;
  services: string[];               // service_id strings
  active_service_id: string | null;
  selected_date: string | null;     // YYYYMMDD
  today: string | null;
}

// ---- /route/{line}/stops/direction?direction_id= -------------------------

export interface DirectionStop {
  stop_id: string;
  stop_name: string;
  stop_code: string;
  zone_id: string | null;
  lat: number | null;
  lon: number | null;
  sequence: number;
  description: string | null;
}

export interface RouteDirectionStops {
  route_id: string;
  direction_id: number;
  stops: DirectionStop[];           // full ordered stop list for the direction
  timepoint_stop_ids: string[];     // subset shown as timing columns
}

// ---- /route/{line}/schedule?service_id=&direction_id= --------------------
// The full timetable grid: many trips, each hitting the timepoint stops.

export interface TripStop {
  stop_sequence: number;
  stop_id: string;
  stop_name: string;
  stop_lat: number | null;
  stop_lon: number | null;
  arrival_time: string;             // "HH:MM:SS", may exceed 24h
  departure_time: string;
}

export interface Trip {
  trip_id: string;
  service_id: string;
  trip_headsign: string;
  direction_id: number;
  stops: TripStop[];
}

export interface RouteSchedule {
  route_id: string;
  service_id: string;
  direction_id: number;
  timepoint_stops: DirectionStop[]; // the column headers (from selected_stops)
  trips: Trip[];                    // sorted by first departure
}


// ==========================================================================
// COMBINED live + scheduled departures: /stops/{code}/departures?line=
// ==========================================================================

export interface CombinedDeparture {
  line: string;
  destination: string;
  /** which feed this came from — style these differently on the frontend */
  source: 'realtime' | 'scheduled';
  /** minutes from now (both sources); 0 == arriving */
  eta_minutes: number | null;
  /** "HH:MM" clock time (estimated for live, scheduled for static) */
  time: string;
  status: string | null;         // realtime only (ON_TIME / DELAYED)
  delay_minutes: number | null;  // realtime only
  color: string | null;
  text_color: string | null;
  /**
   * The trip behind this departure, when it is known. Always set on realtime
   * rows; null on scheduled ones unless they came from the store, because
   * upstream's timetable is times and headsigns only. A client that wants the
   * journey for a departure with no id asks `/trips/stops` with the hints
   * instead of `/trips/{trip_id}/stops`.
   */
  trip_id: string | null;
}

export interface StopLineDepartures {
  stop_code: string;
  line: string;
  direction_id: number | null;
  service_id: string | null;     // service used for the scheduled fallback
  generated_at: string;          // ISO timestamp of when this was built
  departures: CombinedDeparture[];
}


// ==========================================================================
// Departure board for a location: /board and /board.txt
// ==========================================================================

export interface BoardRow {
  line: string;
  destination: string;
  /**
   * True when STCP is actually tracking the buses at this stop, false when it has
   * fallen back to projecting from the timetable. Render tracked times differently
   * — the text board prints them green.
   */
  realtime: boolean;
  /** the raw upstream label behind `realtime`, e.g. "realtime" */
  data_source: string | null;
  /** which nearby stop this departs from */
  stop_code: string;
  stop_name: string;
  /** minutes to walk from the origin to that stop */
  walk_minutes: number;
  distance_meters: number;
  /** minutes until the bus reaches the stop */
  eta_minutes: number;
  /**
   * Minutes until you have to leave. eta minus the walk (minus any buffer);
   * negative means the walk is longer than the wait, so it's already unreachable.
   */
  leave_in_minutes: number;
  catchable: boolean;
  status: string | null;
  delay_minutes: number | null;
  color: string | null;
  text_color: string | null;
}

export interface PolledStop {
  stop_code: string;
  name: string;
  distance_meters: number;
  walk_minutes: number;
  /** false when the live call for this stop failed — an empty board vs a broken one */
  ok: boolean;
}

export interface LocationBoard {
  origin: { lat: number; lon: number };
  walk_minutes: number;
  generated_at: string;
  /** how many stops were in range */
  stops_considered: number;
  /** the subset actually polled (capped by max_stops) */
  stops_polled: PolledStop[];
  departures: BoardRow[];
}


// ==========================================================================
// One live bus's whole journey: /trips/{trip_id}/stops
// ==========================================================================

/**
 * A stop on a resolved trip. Extends the schedule-grid `TripStop` with the two
 * things a client doing arithmetic needs: the stop_code it speaks elsewhere,
 * and seconds-since-midnight so it never has to parse "24:35:00" itself.
 */
export interface ResolvedTripStop extends TripStop {
  stop_code: string;
  /** may exceed 86400 for the after-midnight tail of a trip */
  arrival_seconds: number;
  departure_seconds: number;
  timepoint: boolean;
}

/**
 * How the live `trip_id` was matched to a trip in the store. Descending
 * confidence — a client should be readier to hide projected times the further
 * down this list it gets. See README §2a.
 */
export type TripMatch =
  /** ids were equal: the store's feed version is what STCP is serving */
  | 'exact'
  /** equal once the feed-version field was dropped, and unambiguous */
  | 'version'
  /** version-stripped match that the day's service could not narrow; newest feed version won */
  | 'version_latest'
  /** the id missed entirely; matched on line + headsign + nearest scheduled departure */
  | 'pattern';

export interface ResolvedTrip {
  /** the store's id for this trip */
  trip_id: string;
  /** the id as asked for, i.e. the live one; null when asked for by pattern */
  requested_trip_id: string | null;
  match: TripMatch;
  route_id: string;
  /** route_short_name, e.g. "601" */
  line: string | null;
  color: string | null;
  text_color: string | null;
  headsign: string | null;
  direction_id: number | null;
  service_id: string;
  shape_id: string | null;
  /**
   * True when the ingested feed is past its validity window. The stop order is
   * still trustworthy; the minute-gaps between them are from a timetable STCP
   * has already moved past.
   */
  feed_expired: boolean;
  stops: ResolvedTripStop[];
}
