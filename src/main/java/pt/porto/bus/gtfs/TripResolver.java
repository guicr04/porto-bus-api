package pt.porto.bus.gtfs;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.porto.bus.model.ResolvedTrip;
import pt.porto.bus.model.ResolvedTripStop;
import pt.porto.bus.shared.LisbonTime;

/**
 * A live trip_id to a trip in the static store, and that trip's ordered stops.
 *
 * <p>The live board names the bus with `Arrival.trip_id`; everything downstream
 * comes from stop_times. The join between the two is on an undocumented id
 * format, which is the whole difficulty (README §2a).
 */
@Repository
public class TripResolver {

  private static final int DAY_SECONDS = 86_400;

  /** A trips row. */
  public record TripRow(String tripId, String routeId, String serviceId, String headsign, Integer directionId, String shapeId) {}

  /** @param match exact, version, version_latest or pattern */
  public record Resolution(TripRow trip, String match) {}

  /** The fallback identity, taken off the live board row the rider tapped. */
  public record Hints(String line, String headsign, String stopCode, Double etaMinutes) {
    public static final Hints NONE = new Hints(null, null, null, null);
  }

  private static final String TRIP_COLUMNS = "trip_id, route_id, service_id, headsign, direction_id, shape_id";

  private static final RowMapper<TripRow> TRIP =
      (rs, i) ->
          new TripRow(
              rs.getString("trip_id"),
              rs.getString("route_id"),
              rs.getString("service_id"),
              rs.getString("headsign"),
              Rows.integer(rs, "direction_id"),
              rs.getString("shape_id"));

  private final JdbcClient jdbc;
  private final GtfsStore store;
  private final Clock clock;

  public TripResolver(JdbcClient jdbc, GtfsStore store, Clock clock) {
    this.jdbc = jdbc;
    this.store = store;
    this.clock = clock;
  }

  /**
   * Drop the feed-version field from a trip id.
   *
   * <p>Ids look like `601_0_1|280|D3|T1|N6`, and the second field is a counter
   * STCP bumps every time it republishes. Live served `280` while the ingested
   * zip held `276`, so a verbatim match resolves nothing at all.
   */
  public static String normalizeTripId(String tripId) {
    String[] parts = tripId.split("\\|", -1);
    if (parts.length < 3) return tripId;
    return parts[0] + "|" + String.join("|", Arrays.copyOfRange(parts, 2, parts.length));
  }

  /**
   * A LIKE pattern matching every version of one id. The escaping is not
   * optional: ids are full of underscores (`601_0_1`), and `_` is a
   * single-character wildcard in LIKE. Unescaped, it also matches `601X0Y1|...`.
   */
  static String versionWildcard(String tripId) {
    String[] parts = tripId.split("\\|", -1);
    if (parts.length < 3) return null;
    String rest = Arrays.stream(parts, 2, parts.length).map(TripResolver::escapeLike).collect(Collectors.joining("|"));
    return escapeLike(parts[0]) + "|%|" + rest;
  }

  private static String escapeLike(String s) {
    return s.replaceAll("([\\\\%_])", "\\\\$1");
  }

  private List<TripRow> versionSiblings(String tripId) {
    String pattern = versionWildcard(tripId);
    if (pattern == null) return List.of();
    // A LIKE scan over ~23k trips measures at ~6 ms: small enough that a
    // precomputed normalised column would be complexity bought for nothing.
    return jdbc.sql("SELECT " + TRIP_COLUMNS + " FROM trips WHERE trip_id LIKE ? ESCAPE '\\' ORDER BY trip_id")
        .param(pattern)
        .query(TRIP)
        .list();
  }

  /** The numeric feed version inside a trip id, or -1 when it has none. */
  private static long versionOf(String tripId) {
    String[] parts = tripId.split("\\|", -1);
    if (parts.length < 2) return -1;
    String v = parts[1].trim();
    if (v.isEmpty()) return 0;
    try {
      return Long.parseLong(v);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /** Services that could own a bus running now: today's, plus yesterday's after-midnight tail. */
  private Set<String> currentServiceIds(Instant now) {
    Set<String> ids = new HashSet<>(store.activeServiceIds(LisbonTime.dateStamp(now)));
    ids.addAll(store.activeServiceIds(LisbonTime.dateStamp(now, -1)));
    return ids;
  }

  /**
   * Resolve a live trip_id. In order, each less certain than the last:
   *
   * <ol>
   *   <li>exact — the ids are equal; the store's version is what STCP serves.
   *   <li>version — equal once the version field is dropped, and today's service
   *       picks exactly one. The normal path.
   *   <li>version_latest — the service could not narrow it (expired feed, or the
   *       same pattern under several weekday reissues); the newest version wins.
   * </ol>
   *
   * Null means try {@link #findByPattern} before giving up.
   */
  public Resolution resolve(String tripId, Instant now) {
    var exact = jdbc.sql("SELECT " + TRIP_COLUMNS + " FROM trips WHERE trip_id = ?").param(tripId).query(TRIP).optional();
    if (exact.isPresent()) return new Resolution(exact.get(), "exact");

    List<TripRow> siblings = versionSiblings(tripId);
    if (siblings.isEmpty()) return null;
    if (siblings.size() == 1) return new Resolution(siblings.getFirst(), "version");

    Set<String> active = currentServiceIds(now);
    List<TripRow> inService = siblings.stream().filter(t -> active.contains(t.serviceId())).toList();
    if (inService.size() == 1) return new Resolution(inService.getFirst(), "version");

    // Ambiguous, or no service today. Prefer the newest version among whatever is
    // still in the running: deterministic, and it picks the reissue.
    List<TripRow> pool = inService.size() > 1 ? inService : siblings;
    TripRow newest = pool.getFirst();
    for (TripRow t : pool) if (versionOf(t.tripId()) > versionOf(newest.tripId())) newest = t;
    return new Resolution(newest, "version_latest");
  }

  /**
   * The fallback when the id join misses: find the trip by what a rider can see —
   * the line, where it says it is going, and roughly when it leaves a stop.
   * Line, stop and ETA are all required; without them there is nothing to
   * discriminate on.
   */
  public Resolution findByPattern(String line, String stopCode, double etaMinutes, String headsign, Instant now) {
    double nowSeconds = LisbonTime.nowMinutes(now) * 60;
    double target = nowSeconds + etaMinutes * 60;
    Set<String> active = currentServiceIds(now);

    record Candidate(TripRow trip, int departureSeconds) {}
    List<Candidate> rows =
        jdbc.sql(
                "SELECT t.trip_id, t.route_id, t.service_id, t.headsign, t.direction_id, t.shape_id,"
                    + " st.departure_seconds"
                    + " FROM stop_times st"
                    + " JOIN trips t  ON t.trip_id = st.trip_id"
                    + " JOIN routes r ON r.route_id = t.route_id"
                    + " WHERE st.stop_code = ? AND r.short_name = ?")
            .params(stopCode, line)
            .query((rs, i) -> new Candidate(TRIP.mapRow(rs, i), rs.getInt("departure_seconds")))
            .list();
    if (rows.isEmpty()) return null;

    // Only filter by service when the store knows one for today — an expired feed
    // would otherwise turn every fallback into a miss.
    List<Candidate> scoped = active.isEmpty() ? rows : rows.stream().filter(r -> active.contains(r.trip().serviceId())).toList();
    List<Candidate> pool = scoped.isEmpty() ? rows : scoped;

    String wanted = headsign == null ? "" : headsign.trim().toLowerCase(Locale.ROOT);
    List<Candidate> byHeadsign =
        wanted.isEmpty()
            ? List.of()
            : pool.stream()
                .filter(r -> (r.trip().headsign() == null ? "" : r.trip().headsign()).trim().toLowerCase(Locale.ROOT).equals(wanted))
                .toList();
    List<Candidate> candidates = byHeadsign.isEmpty() ? pool : byHeadsign;

    // An after-midnight trip is filed past 86400, so compare against both
    // readings of the target rather than declaring a 24-hour miss.
    java.util.function.ToDoubleFunction<Candidate> distance =
        r -> Math.min(Math.abs(r.departureSeconds() - target), Math.abs(r.departureSeconds() - (target + DAY_SECONDS)));
    Candidate best = candidates.getFirst();
    for (Candidate c : candidates) if (distance.applyAsDouble(c) < distance.applyAsDouble(best)) best = c;

    // Beyond half an hour this is no longer "the bus they are looking at", and a
    // wrong trip is worse than no trip.
    if (distance.applyAsDouble(best) > 30 * 60) return null;
    return new Resolution(best.trip(), "pattern");
  }

  /**
   * The ordered stops of one store trip, with seconds alongside the clock
   * strings: the caller's whole job is arithmetic on them, and doing it on
   * "24:35:00" re-invites the after-midnight bug the store already solved.
   */
  public List<ResolvedTripStop> tripStops(String tripId) {
    return jdbc.sql(
            "SELECT st.stop_sequence, st.stop_code, st.arrival_time, st.departure_time,"
                + " st.arrival_seconds, st.departure_seconds, st.timepoint,"
                + " s.name, s.lat, s.lon"
                + " FROM stop_times st JOIN stops s ON s.stop_code = st.stop_code"
                + " WHERE st.trip_id = ? ORDER BY st.stop_sequence")
        .param(tripId)
        .query(
            (rs, i) -> {
              Integer timepoint = Rows.integer(rs, "timepoint");
              return new ResolvedTripStop(
                  rs.getInt("stop_sequence"),
                  rs.getString("stop_code"),
                  rs.getString("stop_code"),
                  rs.getString("name"),
                  Rows.dbl(rs, "lat"),
                  Rows.dbl(rs, "lon"),
                  rs.getString("arrival_time"),
                  rs.getString("departure_time"),
                  rs.getInt("arrival_seconds"),
                  rs.getInt("departure_seconds"),
                  timepoint != null && timepoint == 1);
            })
        .list();
  }

  /**
   * The whole answer for `/trips/{trip_id}/stops`: resolve, then read the stops.
   *
   * <p>A null id is a caller that never had one (a scheduled departure), so it
   * goes straight to the pattern match. `feed_expired` rides along rather than
   * blocking: nothing is impersonated here, since the caller already holds the
   * live ETA and only wants the stop order and the gaps between them.
   *
   * @return null when the bus cannot be identified
   */
  public ResolvedTrip resolvedTripStops(String requestedTripId, Hints hints, Instant now) {
    Resolution resolved = requestedTripId == null ? null : resolve(requestedTripId, now);
    if (resolved == null
        && hints.line() != null
        && hints.stopCode() != null
        && hints.etaMinutes() != null
        && Double.isFinite(hints.etaMinutes())) {
      resolved = findByPattern(hints.line(), hints.stopCode(), hints.etaMinutes(), hints.headsign(), now);
    }
    if (resolved == null) return null;

    List<ResolvedTripStop> stops = tripStops(resolved.trip().tripId());
    if (stops.isEmpty()) return null;

    record RouteInfo(String shortName, String color, String textColor) {}
    RouteInfo route =
        jdbc.sql("SELECT short_name, color, text_color FROM routes WHERE route_id = ?")
            .param(resolved.trip().routeId())
            .query((rs, i) -> new RouteInfo(rs.getString("short_name"), rs.getString("color"), rs.getString("text_color")))
            .optional()
            .orElse(new RouteInfo(null, null, null));

    TripRow t = resolved.trip();
    return new ResolvedTrip(
        t.tripId(),
        requestedTripId,
        resolved.match(),
        t.routeId(),
        route.shortName(),
        route.color(),
        route.textColor(),
        t.headsign(),
        t.directionId(),
        t.serviceId(),
        t.shapeId(),
        store.isFeedExpired(now),
        stops);
  }

  public ResolvedTrip resolvedTripStops(String requestedTripId, Hints hints) {
    return resolvedTripStops(requestedTripId, hints, clock.instant());
  }
}
