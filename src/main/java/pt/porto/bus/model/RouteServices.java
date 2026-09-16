package pt.porto.bus.model;

import java.util.List;

/** Unlike the stop version, upstream's `services` here is a plain string array. */
public record RouteServices(
    String routeId, List<String> services, String activeServiceId, String selectedDate, String today, String dataSource) {
  public RouteServices withDataSource(String source) {
    return new RouteServices(routeId, services, activeServiceId, selectedDate, today, source);
  }
}
