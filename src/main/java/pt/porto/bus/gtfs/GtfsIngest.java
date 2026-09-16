package pt.porto.bus.gtfs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;
import pt.porto.bus.shared.AppProperties;
import pt.porto.bus.shared.Colors;
import pt.porto.bus.shared.LisbonTime;
import pt.porto.bus.shared.UpstreamException;
import pt.porto.bus.shared.UriEncoding;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole GTFS feed into the static store. The only writer.
 *
 * <p>Everything happens inside one transaction, so a reader never observes a
 * partially-loaded feed and a failure halfway leaves yesterday's data intact.
 *
 * <p>Deliberately strict. A silently-wrong feed is worse than a failed refresh:
 * the API would keep serving plausible nonsense. The assumptions the store is
 * built on (README §2a) are asserted before anything is written.
 */
@Component
public class GtfsIngest {

  /** Placeholder stops the real feed carries, with "." for a code and name. */
  private static final Set<String> JUNK = Set.of("", ".");

  private static final int BATCH = 10_000;

  private static final List<String> TABLES =
      List.of("feed_meta", "stops", "routes", "trips", "stop_times", "shapes", "service_dates", "stop_routes");

  public record FeedSource(String url, String name) {}

  private final DataSource dataSource;
  private final AppProperties props;
  private final JsonMapper json;
  private final Clock clock;
  private final HttpClient http;
  private final ReentrantLock writer = new ReentrantLock();

  public GtfsIngest(DataSource dataSource, AppProperties props, JsonMapper json, Clock clock) {
    this.dataSource = dataSource;
    this.props = props;
    this.json = json;
    this.clock = clock;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(props.httpTimeoutMs()))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  /** "HH:MM:SS" to seconds. GTFS times legitimately exceed 24h, so this must not wrap. */
  public static Integer toSeconds(String hms) {
    var m = java.util.regex.Pattern.compile("^(\\d{1,3}):(\\d{2}):(\\d{2})$").matcher(hms == null ? "" : hms.trim());
    if (!m.find()) return null;
    return Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60 + Integer.parseInt(m.group(3));
  }

  // ---- sources -------------------------------------------------------------

  /**
   * Ask the portal's CKAN API for the newest GTFS zip. The dataset accumulates
   * one resource per publication, each with its own UUID, so sort by
   * last_modified and take the freshest rather than trusting any fixed URL.
   */
  public FeedSource resolveLatestFeed() {
    String url =
        props.gtfsPortalBase() + "/api/3/action/package_show?id=" + UriEncoding.component(props.gtfsDatasetId());
    HttpResponse<byte[]> resp = send(url, Duration.ofMillis(props.httpTimeoutMs()));
    if (resp.statusCode() < 200 || resp.statusCode() > 299) {
      throw new UpstreamException(resp.statusCode(), "GTFS portal returned " + resp.statusCode());
    }
    JsonNode body = json.readTree(resp.body());
    List<JsonNode> zips = new ArrayList<>();
    for (JsonNode r : body.path("result").path("resources")) {
      String format = r.path("format").asString("").toLowerCase();
      String resourceUrl = r.path("url").asString("");
      if (format.equals("zip") || resourceUrl.endsWith(".zip")) zips.add(r);
    }
    if (zips.isEmpty()) throw new UpstreamException(null, "GTFS portal listed no zip resources");
    zips.sort(Comparator.comparing(GtfsIngest::modifiedOf));
    JsonNode latest = zips.getLast();
    JsonNode name = latest.path("name");
    return new FeedSource(latest.path("url").asString(), name.isString() ? name.stringValue() : null);
  }

  private static String modifiedOf(JsonNode r) {
    for (String k : List.of("last_modified", "created")) {
      JsonNode v = r.path(k);
      if (!v.isMissingNode() && !v.isNull()) return v.asString("");
    }
    return "";
  }

  /** Download a feed to a temporary file. The zip is ~7 MB, so a longer leash. */
  Path download(String url) {
    try {
      Path tmp = Files.createTempFile("gtfs-", ".zip");
      if (url.startsWith("file:")) {
        Files.copy(Path.of(URI.create(url)), tmp, StandardCopyOption.REPLACE_EXISTING);
        return tmp;
      }
      HttpResponse<byte[]> resp = send(url, Duration.ofMillis(props.httpTimeoutMs() * 3));
      if (resp.statusCode() < 200 || resp.statusCode() > 299) {
        Files.deleteIfExists(tmp);
        throw new UpstreamException(resp.statusCode(), "GTFS download returned " + resp.statusCode());
      }
      Files.write(tmp, resp.body());
      return tmp;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private HttpResponse<byte[]> send(String url, Duration timeout) {
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("User-Agent", props.userAgent()).GET().build();
    try {
      return http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new UpstreamException(null, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UpstreamException(null, "interrupted", e);
    }
  }

  // ---- ingest --------------------------------------------------------------

  /** Download the configured or newest feed and load it. Waits for any ingest already running. */
  public Map<String, Object> ingest(Consumer<String> log) {
    writer.lock();
    try {
      FeedSource source =
          props.gtfsUrl() != null ? new FeedSource(props.gtfsUrl(), null) : resolveLatestFeed();
      log.accept("downloading " + (source.name() != null ? source.name() : source.url()));
      Path zip = download(source.url());
      try {
        log.accept("downloaded %.1f MB".formatted(Files.size(zip) / 1e6));
        return load(zip, source);
      } finally {
        Files.deleteIfExists(zip);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      writer.unlock();
    }
  }

  /** Like {@link #ingest(Consumer)}, but returns null at once if another ingest holds the store. */
  public Map<String, Object> ingestIfIdle(Consumer<String> log) {
    if (!writer.tryLock()) {
      log.accept("an ingest is already running; skipping");
      return null;
    }
    try {
      return ingest(log);
    } finally {
      writer.unlock();
    }
  }

  /** Load an already-downloaded feed, replacing whatever the store holds. */
  public Map<String, Object> load(Path zipPath, FeedSource source) {
    writer.lock();
    try (ZipFile zip = new ZipFile(zipPath.toFile())) {
      return load(zip, source);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      writer.unlock();
    }
  }

  private Map<String, Object> load(ZipFile zip, FeedSource source) throws IOException {
    // --- assumptions the store is built on, checked before anything is written

    // calendar.txt is empty in every feed seen so far: STCP expresses service
    // purely as dated exceptions, which is why there is no `calendar` table. If
    // that changes we would silently drop every weekly rule, so stop instead.
    List<CsvReader.Row> calendar = readAll(zip, "calendar.txt");
    if (!calendar.isEmpty()) {
      throw new IllegalStateException(
          "calendar.txt has " + calendar.size() + " rows, but this store models service purely from"
              + " calendar_dates.txt (README §2a). Add a `calendar` table before ingesting this feed.");
    }

    List<CsvReader.Row> stopRows = readAll(zip, "stops.txt");
    for (CsvReader.Row r : stopRows) {
      if (!r.get("stop_id").equals(r.get("stop_code"))) {
        throw new IllegalStateException(
            "stops.txt row stop_id=\"%s\" has a different stop_code (\"%s\"). The store keys stops by stop_code"
                    .formatted(r.get("stop_id"), r.get("stop_code"))
                + " on the assumption they are identical (README §2a); that no longer holds.");
      }
    }

    List<CsvReader.Row> feedInfoRows = readAll(zip, "feed_info.txt");
    CsvReader.Row feedInfo = feedInfoRows.isEmpty() ? null : feedInfoRows.getFirst();
    String feedVersion = feedInfo == null ? null : blankToNull(feedInfo.get("feed_version"));
    String feedStart = feedInfo == null ? null : blankToNull(feedInfo.get("feed_start_date"));
    String feedEnd = feedInfo == null ? null : blankToNull(feedInfo.get("feed_end_date"));

    // --- write

    Map<String, Object> counts = new LinkedHashMap<>();
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(false);
      try {
        try (Statement st = conn.createStatement()) {
          for (String t : TABLES) st.executeUpdate("DELETE FROM " + t);
        }

        int stops = 0;
        int dropped = 0;
        try (PreparedStatement ins =
            conn.prepareStatement("INSERT INTO stops (stop_code, name, lat, lon, zone_id) VALUES (?, ?, ?, ?, ?)")) {
          for (CsvReader.Row r : stopRows) {
            String code = r.get("stop_code").trim();
            String name = r.get("stop_name").trim();
            if (JUNK.contains(code) || JUNK.contains(name)) {
              dropped++;
              continue;
            }
            ins.setString(1, code);
            ins.setString(2, name);
            setDouble(ins, 3, toDouble(r.get("stop_lat")));
            setDouble(ins, 4, toDouble(r.get("stop_lon")));
            ins.setString(5, blankToNull(r.get("zone_id")));
            ins.addBatch();
            stops++;
          }
          ins.executeBatch();
        }
        counts.put("stops", stops);
        counts.put("stops_dropped", dropped);

        int routes = 0;
        try (PreparedStatement ins =
            conn.prepareStatement(
                "INSERT INTO routes (route_id, short_name, long_name, color, text_color, route_type, sort_order)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
          for (CsvReader.Row r : readAll(zip, "routes.txt")) {
            String routeId = r.get("route_id").trim();
            if (routeId.isEmpty()) continue;
            ins.setString(1, routeId);
            ins.setString(2, blankToNull(r.get("route_short_name")));
            ins.setString(3, blankToNull(r.get("route_long_name")));
            ins.setString(4, Colors.toHex(r.get("route_color")));
            ins.setString(5, Colors.toHex(r.get("route_text_color")));
            setInt(ins, 6, toInt(r.get("route_type")));
            setInt(ins, 7, toInt(r.get("route_sort_order")));
            ins.addBatch();
            routes++;
          }
          ins.executeBatch();
        }
        counts.put("routes", routes);

        counts.put(
            "trips",
            stream(
                zip,
                "trips.txt",
                conn,
                "INSERT INTO trips (trip_id, route_id, service_id, headsign, direction_id, shape_id, block_id)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                (r, ins) -> {
                  String tripId = r.get("trip_id").trim();
                  if (tripId.isEmpty()) return false;
                  ins.setString(1, tripId);
                  ins.setString(2, r.get("route_id").trim());
                  ins.setString(3, r.get("service_id").trim());
                  ins.setString(4, blankToNull(r.get("trip_headsign")));
                  setInt(ins, 5, toInt(r.get("direction_id")));
                  ins.setString(6, blankToNull(r.get("shape_id")));
                  ins.setString(7, blankToNull(r.get("block_id")));
                  return true;
                }));

        // The big one, streamed.
        counts.put(
            "stop_times",
            stream(
                zip,
                "stop_times.txt",
                conn,
                "INSERT INTO stop_times (trip_id, stop_sequence, stop_code, arrival_time, departure_time,"
                    + " arrival_seconds, departure_seconds, timepoint, dist_traveled)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (r, ins) -> {
                  String tripId = r.get("trip_id").trim();
                  Integer seq = toInt(r.get("stop_sequence"));
                  if (tripId.isEmpty() || seq == null) return false;
                  String arr = r.get("arrival_time").trim();
                  String dep = r.get("departure_time").trim();
                  Integer arrSec = toSeconds(arr);
                  Integer depSec = toSeconds(dep);
                  if (arrSec == null || depSec == null) return false;
                  ins.setString(1, tripId);
                  ins.setInt(2, seq);
                  ins.setString(3, r.get("stop_id").trim());
                  ins.setString(4, arr);
                  ins.setString(5, dep);
                  ins.setInt(6, arrSec);
                  ins.setInt(7, depSec);
                  setInt(ins, 8, toInt(r.get("timepoint")));
                  setDouble(ins, 9, toDouble(r.get("shape_dist_traveled")));
                  return true;
                }));

        counts.put(
            "shapes",
            stream(
                zip,
                "shapes.txt",
                conn,
                "INSERT INTO shapes (shape_id, sequence, lat, lon, dist_traveled) VALUES (?, ?, ?, ?, ?)",
                (r, ins) -> {
                  String shapeId = r.get("shape_id").trim();
                  Integer seq = toInt(r.get("shape_pt_sequence"));
                  Double lat = toDouble(r.get("shape_pt_lat"));
                  Double lon = toDouble(r.get("shape_pt_lon"));
                  if (shapeId.isEmpty() || seq == null || lat == null || lon == null) return false;
                  ins.setString(1, shapeId);
                  ins.setInt(2, seq);
                  ins.setDouble(3, lat);
                  ins.setDouble(4, lon);
                  setDouble(ins, 5, toDouble(r.get("shape_dist_traveled")));
                  return true;
                }));

        counts.put(
            "service_dates",
            stream(
                zip,
                "calendar_dates.txt",
                conn,
                "INSERT INTO service_dates (service_id, date, exception_type) VALUES (?, ?, ?)",
                (r, ins) -> {
                  String serviceId = r.get("service_id").trim();
                  String date = r.get("date").trim();
                  if (serviceId.isEmpty() || date.isEmpty()) return false;
                  Integer type = toInt(r.get("exception_type"));
                  ins.setString(1, serviceId);
                  ins.setString(2, date);
                  ins.setInt(3, type == null ? 1 : type);
                  return true;
                }));

        // Derive stop -> routes once, now that stop_times and trips are loaded.
        // The join never leaves SQLite and takes well under a second.
        try (Statement st = conn.createStatement()) {
          st.executeUpdate(
              "INSERT OR IGNORE INTO stop_routes (stop_code, route_id)"
                  + " SELECT DISTINCT st.stop_code, t.route_id"
                  + " FROM stop_times st JOIN trips t ON t.trip_id = st.trip_id");
          try (var rs = st.executeQuery("SELECT COUNT(*) FROM stop_routes")) {
            rs.next();
            counts.put("stop_routes", rs.getLong(1));
          }
        }

        try (PreparedStatement ins =
            conn.prepareStatement(
                "INSERT INTO feed_meta (id, resource_name, source_url, feed_version, feed_start_date,"
                    + " feed_end_date, ingested_at) VALUES (1, ?, ?, ?, ?, ?, ?)")) {
          ins.setString(1, source.name());
          ins.setString(2, source.url() != null ? source.url() : "(supplied file)");
          ins.setString(3, feedVersion);
          ins.setString(4, feedStart);
          ins.setString(5, feedEnd);
          ins.setString(6, LisbonTime.isoTimestamp(clock.instant()));
          ins.executeUpdate();
        }

        conn.commit();
      } catch (SQLException | RuntimeException e) {
        conn.rollback();
        throw e;
      } finally {
        conn.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("ingest failed: " + e.getMessage(), e);
    }

    counts.put("feed_start_date", feedStart);
    counts.put("feed_end_date", feedEnd);
    return counts;
  }

  // ---- helpers -------------------------------------------------------------

  @FunctionalInterface
  private interface RowBinder {
    /** Bind one row; false skips it. */
    boolean bind(CsvReader.Row row, PreparedStatement ins) throws SQLException;
  }

  private int stream(ZipFile zip, String file, Connection conn, String sql, RowBinder binder)
      throws IOException, SQLException {
    int n = 0;
    try (CsvReader csv = open(zip, file);
        PreparedStatement ins = conn.prepareStatement(sql)) {
      for (CsvReader.Row r = csv.next(); r != null; r = csv.next()) {
        if (!binder.bind(r, ins)) continue;
        ins.addBatch();
        if (++n % BATCH == 0) ins.executeBatch();
      }
      ins.executeBatch();
    }
    return n;
  }

  private static CsvReader open(ZipFile zip, String file) throws IOException {
    ZipEntry entry = zip.getEntry(file);
    if (entry == null) throw new IllegalStateException("feed is missing " + file);
    return new CsvReader(new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)));
  }

  private static List<CsvReader.Row> readAll(ZipFile zip, String file) throws IOException {
    try (CsvReader csv = open(zip, file)) {
      return csv.readAll();
    }
  }

  private static String blankToNull(String s) {
    String t = s == null ? "" : s.trim();
    return t.isEmpty() ? null : t;
  }

  static Double toDouble(String v) {
    if (v == null || v.isBlank()) return null;
    try {
      double d = Double.parseDouble(v.trim());
      return Double.isNaN(d) ? null : d;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  static Integer toInt(String v) {
    Double d = toDouble(v);
    return d == null ? null : (int) (double) d;
  }

  private static void setDouble(PreparedStatement ps, int i, Double v) throws SQLException {
    if (v == null) ps.setNull(i, Types.REAL);
    else ps.setDouble(i, v);
  }

  private static void setInt(PreparedStatement ps, int i, Integer v) throws SQLException {
    if (v == null) ps.setNull(i, Types.INTEGER);
    else ps.setInt(i, v);
  }
}
