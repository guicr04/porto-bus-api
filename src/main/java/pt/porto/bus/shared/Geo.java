package pt.porto.bus.shared;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import pt.porto.bus.model.Stop;

/** Distance and the walking model. Pure, so it is testable without a network. */
public final class Geo {

  private static final double EARTH_RADIUS_M = 6_371_000;

  /**
   * A comfortable pace, ~4.5 km/h. Together with {@link #DETOUR_FACTOR} this is
   * deliberately pessimistic: for a board that tells you whether you can still
   * catch a bus, over-estimating the walk is the safe direction to be wrong in.
   */
  public static final double WALK_METERS_PER_MINUTE = 75;

  /** You walk streets, not straight lines, and Porto is hilly. */
  public static final double DETOUR_FACTOR = 1.35;

  private Geo() {}

  /** A stop within walking range of an origin. */
  public record NearbyStop(String stopCode, String name, double lat, double lon, long distanceMeters, int walkMinutes) {}

  /** Straight-line distance between two points, in metres. */
  public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
    double dLat = Math.toRadians(lat2 - lat1);
    double dLon = Math.toRadians(lon2 - lon1);
    double a =
        Math.pow(Math.sin(dLat / 2), 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
    return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a));
  }

  /** Minutes to walk a straight-line distance, rounded up. */
  public static int walkMinutes(double meters, double metersPerMinute) {
    return (int) Math.ceil((meters * DETOUR_FACTOR) / metersPerMinute);
  }

  public static int walkMinutes(double meters) {
    return walkMinutes(meters, WALK_METERS_PER_MINUTE);
  }

  /**
   * Stops within a walking-time budget of an origin, nearest first. Stops with
   * no coordinates are skipped rather than treated as being at (0, 0).
   */
  public static List<NearbyStop> stopsWithinWalk(
      List<Stop> stops, double lat, double lon, double maxWalkMinutes, double metersPerMinute) {
    List<NearbyStop> out = new ArrayList<>();
    for (Stop s : stops) {
      if (s.lat() == null || s.lon() == null) continue;
      double distance = haversineMeters(lat, lon, s.lat(), s.lon());
      int walk = walkMinutes(distance, metersPerMinute);
      if (walk > maxWalkMinutes) continue;
      out.add(new NearbyStop(s.stopCode(), s.name(), s.lat(), s.lon(), Math.round(distance), walk));
    }
    out.sort(Comparator.comparingLong(NearbyStop::distanceMeters));
    return out;
  }
}
