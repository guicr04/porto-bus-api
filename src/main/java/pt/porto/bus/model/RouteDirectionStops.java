package pt.porto.bus.model;

import java.util.List;

/**
 * @param stops the full ordered stop list for the direction
 * @param timepointStopIds the subset shown as timing columns
 */
public record RouteDirectionStops(
    String routeId, int directionId, List<DirectionStop> stops, List<String> timepointStopIds, String dataSource) {
  public RouteDirectionStops withDataSource(String source) {
    return new RouteDirectionStops(routeId, directionId, stops, timepointStopIds, source);
  }
}
