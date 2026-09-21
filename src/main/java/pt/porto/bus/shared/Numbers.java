package pt.porto.bus.shared;

/** Lenient query-parameter parsing, matching how the API has always read them. */
public final class Numbers {
  private Numbers() {}

  /** A finite number, or the fallback when absent, empty or unparseable. */
  public static Double parse(String raw, Double fallback) {
    if (raw == null || raw.isBlank()) return fallback;
    try {
      double v = Double.parseDouble(raw.trim());
      return Double.isFinite(v) ? v : fallback;
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  public static double parse(String raw, double fallback) {
    return parse(raw, Double.valueOf(fallback));
  }

  /** Truncated to an int, the way a JS array slice treats a fractional bound. */
  public static int parseInt(String raw, int fallback) {
    return (int) parse(raw, (double) fallback);
  }

  /** "1", "true", "yes" (any case) are true; absent or empty is the fallback. */
  public static boolean parseBool(String raw, boolean fallback) {
    if (raw == null || raw.isEmpty()) return fallback;
    String v = raw.toLowerCase();
    return v.equals("1") || v.equals("true") || v.equals("yes");
  }

  /** Whole numbers serialise as integers (4, not 4.0), so a client can decode an Int. */
  public static Number compact(double v) {
    return v == Math.rint(v) && Math.abs(v) < 1e15 ? (Number) (long) v : (Number) v;
  }
}
