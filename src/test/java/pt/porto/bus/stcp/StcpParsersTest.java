package pt.porto.bus.stcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The upstream quirks README §2 records, each pinned. */
class StcpParsersTest {

  static final JsonMapper JSON = JsonMapper.builder().build();

  static JsonNode json(String s) {
    return JSON.readTree(s.replace('\'', '"'));
  }

  @Test
  void realtimeFillsDefaultsForMissingFields() {
    var rt = StcpParsers.realtime(json("{'arrivals':[{'arrival_minutes':'7','delay_minutes':''}]}"), "CMO");
    assertThat(rt.stopCode()).isEqualTo("CMO");
    var a = rt.arrivals().getFirst();
    assertThat(a.line()).isEqualTo("?");
    assertThat(a.destination()).isEqualTo("?");
    assertThat(a.arrivalMinutes()).isEqualTo(7);
    assertThat(a.delayMinutes()).isNull();
    assertThat(a.tripId()).isNull();
  }

  @Test
  void stopRoutesSplitsDisplayAndDropdownLists() {
    var routes =
        StcpParsers.stopRoutes(
            json(
                "{'display_routes':[{'route_id':'300','route_short_name':'300'}],"
                    + "'dropdown_routes':[{'route_id':'300','direction_id':1,'trip_headsign':'Aliados'}]}"));
    assertThat(routes.routes()).hasSize(1);
    assertThat(routes.routes().getFirst().directionId()).isNull();
    assertThat(routes.directions().getFirst().directionId()).isEqualTo(1);
    assertThat(routes.directions().getFirst().tripHeadsign()).isEqualTo("Aliados");
  }

  @Test
  void servicesKeepZeroedDayFlagsAndTrustIsActiveToday() {
    var services =
        StcpParsers.stopServices(json("{'services':[{'service_id':'S','is_active_today':1,'monday':0}],'active_service_id':'S'}"));
    var day = services.services().getFirst();
    assertThat(day.isActiveToday()).isTrue();
    assertThat(day.days()).containsOnlyKeys("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday");
    assertThat(services.activeServiceId()).isEqualTo("S");
  }

  @Test
  void scheduleBucketsAreFlattenedAndSortedWithAfterMidnightLast() {
    var sched =
        StcpParsers.stopSchedule(
            json(
                "{'schedule':{'24':[{'departure_time':'24:10:00','headsign':'X'}],"
                    + "'08':[{'departure_time':'08:30:00'},{'departure_time':'08:05:00'}]}}"),
            "CMO");
    assertThat(sched.departures()).extracting(d -> d.departureTime()).containsExactly("08:05:00", "08:30:00", "24:10:00");
  }

  @Test
  void lineServicesAreAPlainStringArray() {
    var s = StcpParsers.routeServices(json("{'services':['A','B'],'active_service_id':'A'}"), "300");
    assertThat(s.routeId()).isEqualTo("300");
    assertThat(s.services()).containsExactly("A", "B");
  }

  @Test
  void camelCaseAndSnakeCaseStopsNormaliseToOneShape() {
    var snake =
        StcpParsers.routeDirectionStops(
            json("{'stops':[{'stop_id':'CMO','stop_name':'CARMO','stop_lat':41.1,'stop_sequence':3}]}"), "300");
    var camel =
        StcpParsers.routeSchedule(
            json("{'selected_stops':[{'stopId':'CMO','stopName':'CARMO','stopLat':'41.1','stopSequence':3}]}"), "300");
    assertThat(camel.timepointStops().getFirst()).isEqualTo(snake.stops().getFirst());
    assertThat(snake.stops().getFirst().stopCode()).as("falls back to the id").isEqualTo("CMO");
  }

  @Test
  void tripsInTheGridSortByFirstDeparture() {
    var grid =
        StcpParsers.routeSchedule(
            json(
                "{'schedule':[{'trip_id':'late','stops':[{'departure_time':'09:00:00'}]},"
                    + "{'trip_id':'early','stops':[{'departure_time':'07:00:00'}]}]}"),
            "300");
    assertThat(grid.trips()).extracting(t -> t.tripId()).containsExactly("early", "late");
  }
}
