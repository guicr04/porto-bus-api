package pt.porto.bus.shared;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything configurable, bound in application.yml from the same environment
 * variable names the project has always used (PORT, DB_PATH, GTFS_URL, ...).
 *
 * @param gtfsUrl pin a specific feed instead of resolving the newest one. The
 *     portal republishes the feed every few days as a brand-new resource with a
 *     fresh UUID, so a pinned URL always 404s eventually (README §2).
 * @param gtfsTtlSeconds daily, not weekly: the feed's own validity window is ~18
 *     days and the portal republishes every 2-3 days (README §2a).
 * @param dbPath the static store. A derived artifact, safe to delete.
 * @param homeLat default origin for the departure board. Coordinates rather than
 *     an address on purpose: the board is polled every few seconds, and geocoding
 *     each poll would hammer a third party for an answer that never changes.
 * @param realtimeTtlMs how long live arrivals are cached; 0 disables.
 * @param bootRefresh fill the store at startup when empty or stale.
 * @param scheduledRefresh re-check staleness hourly while running.
 */
@ConfigurationProperties("app")
public record AppProperties(
    String gtfsUrl,
    String gtfsPortalBase,
    String gtfsDatasetId,
    long gtfsTtlSeconds,
    String dbPath,
    String stcpApiBase,
    Double homeLat,
    Double homeLon,
    String homeLabel,
    long realtimeTtlMs,
    String userAgent,
    long httpTimeoutMs,
    boolean bootRefresh,
    boolean scheduledRefresh) {

  public AppProperties {
    gtfsUrl = blankToNull(gtfsUrl);
    gtfsPortalBase = stripTrailingSlash(gtfsPortalBase);
    stcpApiBase = stripTrailingSlash(stcpApiBase);
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  private static String stripTrailingSlash(String s) {
    return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}
