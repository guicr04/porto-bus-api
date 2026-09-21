package pt.porto.bus.model;

import java.util.List;

public record RouteShape(String routeId, int directionId, List<ShapePoint> coordinates, String dataSource) {
  public RouteShape withDataSource(String source) {
    return new RouteShape(routeId, directionId, coordinates, source);
  }
}
