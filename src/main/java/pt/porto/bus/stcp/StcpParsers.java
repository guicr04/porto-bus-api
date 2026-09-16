package pt.porto.bus.stcp;

import static pt.porto.bus.stcp.Js.array;
import static pt.porto.bus.stcp.Js.first;
import static pt.porto.bus.stcp.Js.intOr;
import static pt.porto.bus.stcp.Js.intOrNull;
import static pt.porto.bus.stcp.Js.numOrNull;
import static pt.porto.bus.stcp.Js.number;
import static pt.porto.bus.stcp.Js.str;
import static pt.porto.bus.stcp.Js.truthy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pt.porto.bus.model.Arrival;
import pt.porto.bus.model.Departure;
import pt.porto.bus.model.DirectionStop;
import pt.porto.bus.model.RealtimeStop;
import pt.porto.bus.model.RouteDirectionStops;
import pt.porto.bus.model.RouteSchedule;
import pt.porto.bus.model.RouteServices;
import pt.porto.bus.model.RouteShape;
import pt.porto.bus.model.ServiceDay;
import pt.porto.bus.model.ShapePoint;
import pt.porto.bus.model.StopRoute;
import pt.porto.bus.model.StopRoutes;
import pt.porto.bus.model.StopSchedule;
import pt.porto.bus.model.StopServices;
import pt.porto.bus.model.Trip;
import pt.porto.bus.model.TripStop;
import tools.jackson.databind.JsonNode;

/**
 * Raw stcp.pt payloads to domain records. The quirks handled here were found
 * against captured responses and are documented in README §2.
 */
public final class StcpParsers {

  private static final List<String> WEEKDAYS =
      List.of("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday");

  private StcpParsers() {}

  // ---- stop-centric --------------------------------------------------------

  public static Arrival arrival(JsonNode a) {
    return new Arrival(
        str(a.path("route_short_name"), "?"),
        str(a.path("trip_headsign"), "?"),
        intOrNull(a.path("arrival_minutes")),
        str(a.path("estimated_arrival_time")),
        str(a.path("scheduled_arrival_time")),
        str(a.path("status")),
        intOrNull(a.path("delay_minutes")),
        str(a.path("route_color")),
        str(a.path("route_text_color")),
        str(a.path("trip_id")));
  }

  public static RealtimeStop realtime(JsonNode raw, String fallbackCode) {
    List<Arrival> arrivals = new ArrayList<>();
    for (JsonNode a : array(raw.path("arrivals"))) arrivals.add(arrival(a));
    return new RealtimeStop(
        str(raw.path("stop_id"), fallbackCode),
        str(raw.path("stop_name")),
        arrivals,
        str(raw.path("last_updated")),
        str(raw.path("data_source")));
  }

  public static StopRoute route(JsonNode r) {
    return new StopRoute(
        str(r.path("route_id"), ""),
        str(r.path("route_short_name"), ""),
        str(r.path("route_long_name"), ""),
        str(r.path("route_color")),
        str(r.path("route_text_color")),
        intOrNull(r.path("route_type")),
        intOrNull(r.path("direction_id")),
        str(r.path("direction_name")),
        str(r.path("display_name")),
        str(r.path("trip_headsign")));
  }

  /**
   * Upstream returns two lists: display_routes (no direction info) and
   * dropdown_routes (one entry per line with the direction that serves this
   * stop). Exposed as `routes` and `directions`.
   */
  public static StopRoutes stopRoutes(JsonNode raw) {
    List<StopRoute> routes = new ArrayList<>();
    for (JsonNode r : array(raw.path("display_routes"))) routes.add(route(r));
    List<StopRoute> directions = new ArrayList<>();
    for (JsonNode r : array(raw.path("dropdown_routes"))) directions.add(route(r));
    return new StopRoutes(routes, directions);
  }

  public static StopServices stopServices(JsonNode raw) {
    List<ServiceDay> services = new ArrayList<>();
    for (JsonNode s : array(raw.path("services"))) {
      Map<String, Integer> days = new LinkedHashMap<>();
      for (String d : WEEKDAYS) days.put(d, intOr(s.path(d), 0));
      services.add(
          new ServiceDay(
              str(s.path("service_id"), ""),
              str(s.path("service_name"), ""),
              truthy(s.path("is_active_today")),
              days));
    }
    return new StopServices(
        services,
        str(raw.path("active_service_id")),
        str(raw.path("selected_date")),
        str(raw.path("today")));
  }

  /** Upstream buckets departures by hour; flatten and sort them. */
  public static StopSchedule stopSchedule(JsonNode raw, String fallbackCode) {
    List<Departure> departures = new ArrayList<>();
    JsonNode buckets = raw.path("schedule");
    if (buckets.isObject()) {
      for (Map.Entry<String, JsonNode> hour : buckets.properties()) {
        if (!hour.getValue().isArray()) continue;
        for (JsonNode it : hour.getValue()) {
          departures.add(
              new Departure(
                  str(it.path("departure_time"), ""),
                  str(it.path("arrival_time"), ""),
                  str(it.path("headsign"), ""),
                  intOr(it.path("direction_id"), 0)));
        }
      }
    }
    // Zero-padded "HH:MM:SS" sorts chronologically, and "24:.." lands after
    // "23:..", which is right for after-midnight departures.
    departures.sort(Comparator.comparing(Departure::departureTime));
    return new StopSchedule(
        str(raw.path("stop_id"), fallbackCode),
        str(raw.path("route_id"), ""),
        intOr(raw.path("direction_id"), 0),
        str(raw.path("service_id"), ""),
        departures);
  }

  // ---- line-centric --------------------------------------------------------

  public static RouteShape shape(JsonNode raw, String fallbackRoute) {
    List<ShapePoint> coords = new ArrayList<>();
    for (JsonNode c : array(raw.path("coordinates"))) {
      coords.add(new ShapePoint(number(c.path("lat")), number(c.path("lng")), intOr(c.path("sequence"), 0)));
    }
    return new RouteShape(str(raw.path("route_id"), fallbackRoute), intOr(raw.path("direction_id"), 0), coords, null);
  }

  /** Unlike the stop version, `services` here is a plain string array. */
  public static RouteServices routeServices(JsonNode raw, String fallbackRoute) {
    List<String> services = new ArrayList<>();
    for (JsonNode s : array(raw.path("services"))) services.add(str(s, "null"));
    return new RouteServices(
        str(raw.path("route_id"), fallbackRoute),
        services,
        str(raw.path("active_service_id")),
        str(raw.path("selected_date")),
        str(raw.path("today")),
        null);
  }

  /**
   * A stop that may use snake_case (stops/direction) or camelCase (the
   * schedule's selected_stops). Both normalise to one shape.
   */
  static DirectionStop directionStop(JsonNode s) {
    return new DirectionStop(
        str(first(s, "stop_id", "stopId"), ""),
        str(first(s, "stop_name", "stopName"), ""),
        str(first(s, "stop_code", "stopCode", "stop_id", "stopId"), ""),
        str(first(s, "zone_id", "zoneId")),
        numOrNull(first(s, "stop_lat", "stopLat")),
        numOrNull(first(s, "stop_lon", "stopLon")),
        intOr(first(s, "stop_sequence", "stopSequence"), 0),
        str(s.path("description")));
  }

  public static RouteDirectionStops routeDirectionStops(JsonNode raw, String fallbackRoute) {
    List<DirectionStop> stops = new ArrayList<>();
    for (JsonNode s : array(raw.path("stops"))) stops.add(directionStop(s));
    List<String> timepoints = new ArrayList<>();
    for (JsonNode t : array(raw.path("timepoint_stop_ids"))) timepoints.add(str(t, "null"));
    return new RouteDirectionStops(
        str(raw.path("route_id"), fallbackRoute), intOr(raw.path("direction_id"), 0), stops, timepoints, null);
  }

  public static RouteSchedule routeSchedule(JsonNode raw, String fallbackRoute) {
    List<DirectionStop> timepoints = new ArrayList<>();
    for (JsonNode s : array(raw.path("selected_stops"))) timepoints.add(directionStop(s));

    List<Trip> trips = new ArrayList<>();
    for (JsonNode t : array(raw.path("schedule"))) {
      List<TripStop> stops = new ArrayList<>();
      for (JsonNode st : array(t.path("stops"))) {
        stops.add(
            new TripStop(
                intOr(st.path("stop_sequence"), 0),
                str(st.path("stop_id"), ""),
                str(st.path("stop_name"), ""),
                numOrNull(st.path("stop_lat")),
                numOrNull(st.path("stop_lon")),
                str(st.path("arrival_time"), ""),
                str(st.path("departure_time"), "")));
      }
      trips.add(
          new Trip(
              str(t.path("trip_id"), ""),
              str(t.path("service_id"), ""),
              str(t.path("trip_headsign"), ""),
              intOr(t.path("direction_id"), 0),
              stops));
    }
    // By first departure; zero-padded strings sort right.
    trips.sort(Comparator.comparing(t -> t.stops().isEmpty() ? "" : t.stops().getFirst().departureTime()));

    return new RouteSchedule(
        str(raw.path("route_id"), fallbackRoute),
        str(raw.path("service_id"), ""),
        intOr(raw.path("direction_id"), 0),
        timepoints,
        trips);
  }
}
