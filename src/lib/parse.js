/**
 * Pure mappers from raw stcp.pt payloads to our domain types.
 * These were validated against real captured responses.
 *
 * @typedef {import('../../types/domain').Arrival} Arrival
 * @typedef {import('../../types/domain').RealtimeStop} RealtimeStop
 * @typedef {import('../../types/domain').StopRoute} StopRoute
 * @typedef {import('../../types/domain').StopRoutes} StopRoutes
 * @typedef {import('../../types/domain').ServiceDay} ServiceDay
 * @typedef {import('../../types/domain').StopServices} StopServices
 * @typedef {import('../../types/domain').Departure} Departure
 * @typedef {import('../../types/domain').StopSchedule} StopSchedule
 */

const WEEKDAYS = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'];

/** @param {any} v @returns {number | null} */
function numOrNull(v) {
  return v === null || v === undefined || v === '' ? null : Number(v);
}

// ---- realtime -------------------------------------------------------------

/**
 * @param {any} a
 * @returns {Arrival}
 */
export function parseArrival(a) {
  return {
    line: String(a?.route_short_name ?? '?'),
    destination: String(a?.trip_headsign ?? '?'),
    arrival_minutes: numOrNull(a?.arrival_minutes),
    estimated_arrival_time: a?.estimated_arrival_time ?? null,
    scheduled_arrival_time: a?.scheduled_arrival_time ?? null,
    status: a?.status ?? null,
    delay_minutes: numOrNull(a?.delay_minutes),
    color: a?.route_color ?? null,
    text_color: a?.route_text_color ?? null,
    trip_id: a?.trip_id ?? null,
  };
}

/**
 * @param {any} raw
 * @param {string} fallbackCode
 * @returns {RealtimeStop}
 */
export function parseRealtime(raw, fallbackCode) {
  const arrivals = Array.isArray(raw?.arrivals) ? raw.arrivals.map(parseArrival) : [];
  return {
    stop_code: raw?.stop_id ?? fallbackCode,
    stop_name: raw?.stop_name ?? null,
    arrivals,
    last_updated: raw?.last_updated ?? null,
    data_source: raw?.data_source ?? null,
  };
}

// ---- routes ---------------------------------------------------------------

/**
 * @param {any} r
 * @returns {StopRoute}
 */
export function parseRoute(r) {
  return {
    route_id: String(r?.route_id ?? ''),
    short_name: String(r?.route_short_name ?? ''),
    long_name: String(r?.route_long_name ?? ''),
    color: r?.route_color ?? null,
    text_color: r?.route_text_color ?? null,
    route_type: numOrNull(r?.route_type),
    direction_id: r?.direction_id ?? null,
    direction_name: r?.direction_name ?? null,
    display_name: r?.display_name ?? null,
    trip_headsign: r?.trip_headsign ?? null,
  };
}

/**
 * @param {any} raw
 * @returns {StopRoutes}
 */
export function parseStopRoutes(raw) {
  const display = Array.isArray(raw?.display_routes) ? raw.display_routes : [];
  const dropdown = Array.isArray(raw?.dropdown_routes) ? raw.dropdown_routes : [];
  return {
    routes: display.map(parseRoute),
    directions: dropdown.map(parseRoute),
  };
}

// ---- services -------------------------------------------------------------

/**
 * @param {any} raw
 * @returns {StopServices}
 */
export function parseServices(raw) {
  const services = (Array.isArray(raw?.services) ? raw.services : []).map((s) => {
    /** @type {Record<string, number>} */
    const days = {};
    for (const d of WEEKDAYS) days[d] = Number(s?.[d] ?? 0);
    /** @type {ServiceDay} */
    return {
      service_id: String(s?.service_id ?? ''),
      service_name: String(s?.service_name ?? ''),
      is_active_today: Boolean(s?.is_active_today),
      days,
    };
  });
  return {
    services,
    active_service_id: raw?.active_service_id ?? null,
    selected_date: raw?.selected_date ?? null,
    today: raw?.today ?? null,
  };
}

// ---- schedule -------------------------------------------------------------

/**
 * @param {any} raw
 * @param {string} fallbackCode
 * @returns {StopSchedule}
 */
export function parseSchedule(raw, fallbackCode) {
  /** @type {Departure[]} */
  const departures = [];
  const buckets = raw?.schedule ?? {};
  for (const hour of Object.keys(buckets)) {
    const items = buckets[hour];
    if (!Array.isArray(items)) continue;
    for (const it of items) {
      departures.push({
        departure_time: String(it?.departure_time ?? ''),
        arrival_time: String(it?.arrival_time ?? ''),
        headsign: String(it?.headsign ?? ''),
        direction_id: Number(it?.direction_id ?? 0),
      });
    }
  }
  // zero-padded "HH:MM:SS" strings sort chronologically, and "24:.." naturally
  // sorts after "23:.." which is what we want for after-midnight departures.
  departures.sort((a, b) => a.departure_time.localeCompare(b.departure_time));

  return {
    stop_code: String(raw?.stop_id ?? fallbackCode),
    route_id: String(raw?.route_id ?? ''),
    direction_id: Number(raw?.direction_id ?? 0),
    service_id: String(raw?.service_id ?? ''),
    departures,
  };
}


// ==========================================================================
// LINE-CENTRIC parsers: /api/route/{line}/...
// ==========================================================================

/**
 * @typedef {import('../../types/domain').RouteShape} RouteShape
 * @typedef {import('../../types/domain').RouteServices} RouteServices
 * @typedef {import('../../types/domain').DirectionStop} DirectionStop
 * @typedef {import('../../types/domain').RouteDirectionStops} RouteDirectionStops
 * @typedef {import('../../types/domain').Trip} Trip
 * @typedef {import('../../types/domain').RouteSchedule} RouteSchedule
 */

/**
 * @param {any} raw
 * @param {string} fallbackRoute
 * @returns {RouteShape}
 */
export function parseShape(raw, fallbackRoute) {
  const coords = Array.isArray(raw?.coordinates) ? raw.coordinates : [];
  return {
    route_id: String(raw?.route_id ?? fallbackRoute),
    direction_id: Number(raw?.direction_id ?? 0),
    coordinates: coords.map((c) => ({
      lat: Number(c?.lat),
      lng: Number(c?.lng),
      sequence: Number(c?.sequence ?? 0),
    })),
  };
}

/**
 * @param {any} raw
 * @param {string} fallbackRoute
 * @returns {RouteServices}
 */
export function parseRouteServices(raw, fallbackRoute) {
  const services = Array.isArray(raw?.services) ? raw.services.map(String) : [];
  return {
    route_id: String(raw?.route_id ?? fallbackRoute),
    services,
    active_service_id: raw?.active_service_id ?? null,
    selected_date: raw?.selected_date ?? null,
    today: raw?.today ?? null,
  };
}

/**
 * Normalise a stop object that may use snake_case (stops/direction endpoint)
 * or camelCase (schedule endpoint's selected_stops).
 * @param {any} s
 * @returns {DirectionStop}
 */
function parseDirectionStop(s) {
  return {
    stop_id: String(s?.stop_id ?? s?.stopId ?? ''),
    stop_name: String(s?.stop_name ?? s?.stopName ?? ''),
    stop_code: String(s?.stop_code ?? s?.stopCode ?? s?.stop_id ?? s?.stopId ?? ''),
    zone_id: s?.zone_id ?? s?.zoneId ?? null,
    lat: numOrNull(s?.stop_lat ?? s?.stopLat),
    lon: numOrNull(s?.stop_lon ?? s?.stopLon),
    sequence: Number(s?.stop_sequence ?? s?.stopSequence ?? 0),
    description: s?.description ?? null,
  };
}

/**
 * @param {any} raw
 * @param {string} fallbackRoute
 * @returns {RouteDirectionStops}
 */
export function parseRouteDirectionStops(raw, fallbackRoute) {
  const stops = Array.isArray(raw?.stops) ? raw.stops.map(parseDirectionStop) : [];
  return {
    route_id: String(raw?.route_id ?? fallbackRoute),
    direction_id: Number(raw?.direction_id ?? 0),
    stops,
    timepoint_stop_ids: Array.isArray(raw?.timepoint_stop_ids)
      ? raw.timepoint_stop_ids.map(String)
      : [],
  };
}

/**
 * @param {any} raw
 * @param {string} fallbackRoute
 * @returns {RouteSchedule}
 */
export function parseRouteSchedule(raw, fallbackRoute) {
  const timepoints = Array.isArray(raw?.selected_stops)
    ? raw.selected_stops.map(parseDirectionStop)
    : [];

  /** @type {Trip[]} */
  const trips = (Array.isArray(raw?.schedule) ? raw.schedule : []).map((t) => ({
    trip_id: String(t?.trip_id ?? ''),
    service_id: String(t?.service_id ?? ''),
    trip_headsign: String(t?.trip_headsign ?? ''),
    direction_id: Number(t?.direction_id ?? 0),
    stops: (Array.isArray(t?.stops) ? t.stops : []).map((st) => ({
      stop_sequence: Number(st?.stop_sequence ?? 0),
      stop_id: String(st?.stop_id ?? ''),
      stop_name: String(st?.stop_name ?? ''),
      stop_lat: numOrNull(st?.stop_lat),
      stop_lon: numOrNull(st?.stop_lon),
      arrival_time: String(st?.arrival_time ?? ''),
      departure_time: String(st?.departure_time ?? ''),
    })),
  }));

  // sort trips by their first stop's departure time (zero-padded strings sort right)
  trips.sort((a, b) => {
    const da = a.stops[0]?.departure_time ?? '';
    const db = b.stops[0]?.departure_time ?? '';
    return da.localeCompare(db);
  });

  return {
    route_id: String(raw?.route_id ?? fallbackRoute),
    service_id: String(raw?.service_id ?? ''),
    direction_id: Number(raw?.direction_id ?? 0),
    timepoint_stops: timepoints,
    trips,
  };
}
