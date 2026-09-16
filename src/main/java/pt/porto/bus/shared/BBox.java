package pt.porto.bus.shared;

/** A map viewport. */
public record BBox(double minLat, double minLon, double maxLat, double maxLon) {

  /**
   * Parse `minLon,minLat,maxLon,maxLat` — GeoJSON order, which is what MapKit and
   * every mapping client hands you. Null when absent; a malformed box is a 400
   * rather than a silently empty map.
   */
  public static BBox parse(String raw) {
    if (raw == null || raw.isEmpty()) return null;
    String[] parts = raw.split(",", -1);
    double[] n = new double[parts.length];
    boolean ok = parts.length == 4;
    for (int i = 0; ok && i < 4; i++) {
      Double v = Numbers.parse(parts[i], (Double) null);
      if (v == null) ok = false;
      else n[i] = v;
    }
    if (!ok) throw ApiException.badRequest("bbox must be four numbers: minLon,minLat,maxLon,maxLat");
    double minLon = n[0], minLat = n[1], maxLon = n[2], maxLat = n[3];
    if (minLat > maxLat || minLon > maxLon) {
      throw ApiException.badRequest("bbox is inverted: expected minLon,minLat,maxLon,maxLat");
    }
    return new BBox(minLat, minLon, maxLat, maxLon);
  }
}
