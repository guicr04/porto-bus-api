package pt.porto.bus.shared;

/**
 * GTFS gives route_color as bare hex ("187EC2"); every other colour field in
 * this API comes back already '#'-prefixed. Normalise so callers never have to
 * care which endpoint a colour came from.
 */
public final class Colors {
  private Colors() {}

  public static String toHex(String v) {
    String trimmed = v == null ? "" : v.trim();
    if (trimmed.isEmpty()) return null;
    return "#" + (trimmed.startsWith("#") ? trimmed.substring(1) : trimmed);
  }
}
