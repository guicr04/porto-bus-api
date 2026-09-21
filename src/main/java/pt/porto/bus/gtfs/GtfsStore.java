package pt.porto.bus.gtfs;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.porto.bus.model.Line;
import pt.porto.bus.model.LineBadge;
import pt.porto.bus.model.Stop;
import pt.porto.bus.model.StopLines;
import pt.porto.bus.shared.AppProperties;
import pt.porto.bus.shared.BBox;
import pt.porto.bus.shared.LisbonTime;

/**
 * Stops, lines and feed identity, read from the static store.
 *
 * <p>Everything above this goes through these methods, so swapping SQLite for
 * Postgres the day this runs as more than one instance stays a change to the
 * gtfs package alone (README §2a).
 */
@Repository
public class GtfsStore {

  private static final RowMapper<Stop> STOP =
      (rs, i) -> new Stop(rs.getString("stop_code"), rs.getString("name"), Rows.dbl(rs, "lat"), Rows.dbl(rs, "lon"));

  private static final RowMapper<Line> LINE =
      (rs, i) -> {
        String shortName = rs.getString("short_name");
        String longName = rs.getString("long_name");
        String routeId = rs.getString("route_id");
        return new Line(
            shortName == null || shortName.isEmpty() ? routeId : shortName,
            longName == null ? "" : longName,
            routeId,
            rs.getString("color"),
            rs.getString("text_color"));
      };

  private final JdbcClient jdbc;
  private final AppProperties props;
  private final Clock clock;

  public GtfsStore(JdbcClient jdbc, AppProperties props, Clock clock) {
    this.jdbc = jdbc;
    this.props = props;
    this.clock = clock;
  }

  public List<Stop> stops() {
    return jdbc.sql("SELECT stop_code, name, lat, lon FROM stops ORDER BY stop_code").query(STOP).list();
  }

  public Optional<Stop> stop(String stopCode) {
    return jdbc.sql("SELECT stop_code, name, lat, lon FROM stops WHERE stop_code = ?")
        .param(stopCode)
        .query(STOP)
        .optional();
  }

  public boolean stopExists(String stopCode) {
    return jdbc.sql("SELECT 1 FROM stops WHERE stop_code = ?").param(stopCode).query().singleColumn().size() > 0;
  }

  /**
   * Stops inside a box, for the map. The reason `stops(lat, lon)` is indexed: an
   * index range scan instead of a filter over every stop in Porto.
   */
  public List<Stop> stopsInBBox(BBox box, int limit) {
    return jdbc.sql(
            "SELECT stop_code, name, lat, lon FROM stops"
                + " WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?"
                + " ORDER BY stop_code LIMIT ?")
        .params(box.minLat(), box.maxLat(), box.minLon(), box.maxLon(), limit)
        .query(STOP)
        .list();
  }

  /**
   * Which lines serve each stop inside a box — one query for the whole visible
   * region, cheap because stop_routes is precomputed at ingest.
   */
  public List<StopLines> stopLinesInBBox(BBox box) {
    Map<String, List<LineBadge>> byStop = new LinkedHashMap<>();
    jdbc.sql(
            // COALESCE, not short_name: a route with no route_short_name would be
            // a badge with nothing written on it — or, for a client whose model
            // types the line as a String, a decode failure that takes the whole
            // region's labels down.
            "SELECT sr.stop_code, COALESCE(r.short_name, r.route_id) AS line, r.color, r.text_color"
                + " FROM stop_routes sr JOIN routes r ON r.route_id = sr.route_id"
                + " WHERE sr.stop_code IN (SELECT stop_code FROM stops"
                + "   WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?)"
                + " ORDER BY sr.stop_code, r.sort_order, r.short_name")
        .params(box.minLat(), box.maxLat(), box.minLon(), box.maxLon())
        .query(
            rs -> {
              byStop
                  .computeIfAbsent(rs.getString("stop_code"), k -> new ArrayList<>())
                  .add(new LineBadge(rs.getString("line"), rs.getString("color"), rs.getString("text_color")));
            });
    return byStop.entrySet().stream().map(e -> new StopLines(e.getKey(), e.getValue())).toList();
  }

  /** Free-text search on stop name; LIKE is case-insensitive for ASCII, enough for Porto's upper-case names. */
  public List<Stop> searchStops(String query, int limit) {
    return jdbc.sql("SELECT stop_code, name, lat, lon FROM stops WHERE name LIKE ? ORDER BY stop_code LIMIT ?")
        .params("%" + query + "%", limit)
        .query(STOP)
        .list();
  }

  public List<Line> lines() {
    return jdbc.sql(
            "SELECT route_id, short_name, long_name, color, text_color FROM routes ORDER BY sort_order, short_name")
        .query(LINE)
        .list();
  }

  /** The service_ids running on a date. STCP publishes only dated exceptions, so this is the whole truth. */
  public List<String> activeServiceIds(String dateStamp) {
    return jdbc.sql("SELECT service_id FROM service_dates WHERE date = ? AND exception_type = 1")
        .param(dateStamp)
        .query(String.class)
        .list();
  }

  // ---- feed identity -------------------------------------------------------

  /** Null when the store has never been ingested. */
  public FeedMeta feedMeta() {
    return jdbc.sql(
            "SELECT resource_name, source_url, feed_version, feed_start_date, feed_end_date, ingested_at"
                + " FROM feed_meta WHERE id = 1")
        .query(
            (rs, i) ->
                new FeedMeta(
                    rs.getString("resource_name"),
                    rs.getString("source_url"),
                    rs.getString("feed_version"),
                    rs.getString("feed_start_date"),
                    rs.getString("feed_end_date"),
                    rs.getString("ingested_at")))
        .optional()
        .orElse(null);
  }

  public boolean hasData() {
    return count("stops") > 0;
  }

  /** No feed, or a feed older than the TTL. Drives the refresh. */
  public boolean isStale() {
    FeedMeta meta = feedMeta();
    if (meta == null) return true;
    try {
      Duration age = Duration.between(Instant.parse(meta.ingestedAt()), clock.instant());
      return age.getSeconds() > props.gtfsTtlSeconds();
    } catch (DateTimeParseException | NullPointerException e) {
      return true;
    }
  }

  /**
   * Today is past the feed's own validity window — where serving the timetable
   * would be a guess wearing a schedule's clothes. Distinct from staleness: a
   * feed can be freshly ingested and still expired, which is exactly what
   * happens when the portal stops republishing.
   */
  public boolean isFeedExpired(Instant now) {
    FeedMeta meta = feedMeta();
    if (meta == null || meta.feedEndDate() == null || meta.feedEndDate().isEmpty()) return false;
    return LisbonTime.dateStamp(now).compareTo(meta.feedEndDate()) > 0;
  }

  public boolean isFeedExpired() {
    return isFeedExpired(clock.instant());
  }

  /** Can the store stand in for live data right now? */
  public boolean canStandIn() {
    return hasData() && !isFeedExpired();
  }

  public long count(String table) {
    if (!table.matches("[a-z_]+")) throw new IllegalArgumentException(table);
    return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
  }
}
