package pt.porto.bus.gtfs;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.porto.bus.model.DirectionStop;
import pt.porto.bus.model.RouteDirectionStops;
import pt.porto.bus.model.RouteShape;
import pt.porto.bus.model.ShapePoint;

/**
 * A line's ordered stops and polyline, from the store.
 *
 * <p>A route+direction has many trips and they don't all serve the same stops
 * (short workings, peak variants). "The" stop list is therefore the longest
 * trip's — the one covering the full line.
 */
@Repository
public class LineStore {

  private record RepresentativeTrip(String tripId, String shapeId) {}

  private record StopRow(int sequence, String stopCode, Integer timepoint, String name, Double lat, Double lon, String zoneId) {}

  private final JdbcClient jdbc;

  public LineStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** A rider-facing line name ("500") to its GTFS route_id. */
  public String resolveRouteId(String line) {
    return jdbc.sql("SELECT route_id FROM routes WHERE short_name = ?")
        .param(line)
        .query(String.class)
        .optional()
        .or(() -> jdbc.sql("SELECT route_id FROM routes WHERE route_id = ?").param(line).query(String.class).optional())
        .orElse(null);
  }

  /** The trip hitting the most stops; ties break on trip_id so the answer is stable. */
  private RepresentativeTrip representativeTrip(String routeId, int directionId) {
    return jdbc.sql(
            "SELECT t.trip_id, t.shape_id, COUNT(st.stop_sequence) AS n"
                + " FROM trips t JOIN stop_times st ON st.trip_id = t.trip_id"
                + " WHERE t.route_id = ? AND t.direction_id = ?"
                + " GROUP BY t.trip_id ORDER BY n DESC, t.trip_id LIMIT 1")
        .params(routeId, directionId)
        .query((rs, i) -> new RepresentativeTrip(rs.getString("trip_id"), rs.getString("shape_id")))
        .optional()
        .orElse(null);
  }

  /** Null when the line or direction is unknown. */
  public RouteDirectionStops lineStops(String line, int directionId) {
    String routeId = resolveRouteId(line);
    if (routeId == null) return null;
    RepresentativeTrip trip = representativeTrip(routeId, directionId);
    if (trip == null) return null;

    List<StopRow> rows =
        jdbc.sql(
                "SELECT st.stop_sequence, st.stop_code, st.timepoint, s.name, s.lat, s.lon, s.zone_id"
                    + " FROM stop_times st JOIN stops s ON s.stop_code = st.stop_code"
                    + " WHERE st.trip_id = ? ORDER BY st.stop_sequence")
            .param(trip.tripId())
            .query(
                (rs, i) ->
                    new StopRow(
                        rs.getInt("stop_sequence"),
                        rs.getString("stop_code"),
                        Rows.integer(rs, "timepoint"),
                        rs.getString("name"),
                        Rows.dbl(rs, "lat"),
                        Rows.dbl(rs, "lon"),
                        rs.getString("zone_id")))
            .list();

    return new RouteDirectionStops(
        routeId,
        directionId,
        rows.stream()
            .map(r -> new DirectionStop(r.stopCode(), r.name(), r.stopCode(), r.zoneId(), r.lat(), r.lon(), r.sequence(), null))
            .toList(),
        rows.stream().filter(r -> r.timepoint() != null && r.timepoint() == 1).map(StopRow::stopCode).toList(),
        null);
  }

  /** Null when the line, direction or shape is unknown. */
  public RouteShape lineShape(String line, int directionId) {
    String routeId = resolveRouteId(line);
    if (routeId == null) return null;
    RepresentativeTrip trip = representativeTrip(routeId, directionId);
    if (trip == null || trip.shapeId() == null) return null;

    List<ShapePoint> points =
        jdbc.sql("SELECT lat, lon, sequence FROM shapes WHERE shape_id = ? ORDER BY sequence")
            .param(trip.shapeId())
            .query((rs, i) -> new ShapePoint(rs.getDouble("lat"), rs.getDouble("lon"), rs.getInt("sequence")))
            .list();
    if (points.isEmpty()) return null;
    return new RouteShape(routeId, directionId, points, null);
  }
}
