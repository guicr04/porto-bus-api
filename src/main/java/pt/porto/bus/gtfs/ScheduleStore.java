package pt.porto.bus.gtfs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.porto.bus.model.Arrival;
import pt.porto.bus.model.RealtimeStop;
import pt.porto.bus.shared.LisbonTime;

/**
 * Scheduled departures from the static store — what the API serves when STCP's
 * live feed is unreachable. A timetable, not a prediction.
 *
 * <p>After-midnight is the subtle part. GTFS files a 00:35 departure belonging
 * to Tuesday's service as "24:35:00" on Tuesday, so at 00:30 on Wednesday the
 * relevant rows live under yesterday's service with times past 86400. Every
 * query here therefore looks at two service days.
 */
@Repository
public class ScheduleStore {

  private static final int DAY_SECONDS = 86_400;

  public record ScheduledDeparture(
      String line,
      String destination,
      int minutes,
      String clock,
      String color,
      String textColor,
      Integer directionId,
      String tripId) {}

  private record Row(
      String line,
      String destination,
      String departureTime,
      int departureSeconds,
      String color,
      String textColor,
      Integer directionId,
      String tripId,
      int minutes) {}

  private final JdbcClient jdbc;

  public ScheduleStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Scheduled departures from one stop within a forward window.
   *
   * @param line restrict to one route short_name, or null for every line
   */
  public List<ScheduledDeparture> departures(String stopCode, int windowMinutes, int limit, String line, Instant now) {
    int nowSeconds = LisbonTime.nowMinutes(now) * 60;
    int windowSeconds = windowMinutes * 60;

    List<Row> rows = new ArrayList<>();
    // Today's service, from now forward.
    rows.addAll(query(stopCode, LisbonTime.dateStamp(now), nowSeconds, windowSeconds, line, limit, nowSeconds));
    // Yesterday's service, for trips still running past midnight.
    int yStart = nowSeconds + DAY_SECONDS;
    rows.addAll(query(stopCode, LisbonTime.dateStamp(now, -1), yStart, windowSeconds, line, limit, yStart));

    return rows.stream()
        .sorted(Comparator.comparingInt(Row::minutes))
        .limit(Math.max(limit, 0))
        .map(
            r ->
                new ScheduledDeparture(
                    r.line(),
                    r.destination() == null ? "" : r.destination(),
                    r.minutes(),
                    r.departureTime().substring(0, Math.min(5, r.departureTime().length())),
                    r.color(),
                    r.textColor(),
                    r.directionId(),
                    r.tripId()))
        .toList();
  }

  private List<Row> query(
      String stopCode, String dateStamp, int from, int windowSeconds, String line, int limit, int origin) {
    var sql =
        new StringBuilder(
            "SELECT r.short_name AS line, t.headsign AS destination, st.departure_time,"
                + " st.departure_seconds, r.color, r.text_color, t.direction_id, t.trip_id"
                + " FROM stop_times st"
                + " JOIN trips t  ON t.trip_id = st.trip_id"
                + " JOIN routes r ON r.route_id = t.route_id"
                + " WHERE st.stop_code = ?"
                + "   AND t.service_id IN (SELECT service_id FROM service_dates"
                + "                        WHERE date = ? AND exception_type = 1)"
                + "   AND st.departure_seconds BETWEEN ? AND ?");
    List<Object> params = new ArrayList<>(List.of(stopCode, dateStamp, from, from + windowSeconds));
    if (line != null) {
      sql.append("   AND r.short_name = ?");
      params.add(line);
    }
    sql.append(" ORDER BY st.departure_seconds LIMIT ?");
    params.add(limit);

    return jdbc.sql(sql.toString())
        .params(params)
        .query(
            (rs, i) -> {
              int seconds = rs.getInt("departure_seconds");
              return new Row(
                  rs.getString("line"),
                  rs.getString("destination"),
                  rs.getString("departure_time"),
                  seconds,
                  rs.getString("color"),
                  rs.getString("text_color"),
                  Rows.integer(rs, "direction_id"),
                  rs.getString("trip_id"),
                  (int) Math.round((seconds - origin) / 60.0));
            })
        .list();
  }

  /**
   * A stop's board built purely from the timetable, shaped exactly like the live
   * one so callers need no special case beyond reading `data_source`.
   *
   * <p>`status` and `delay_minutes` stay null on purpose: a scheduled row has no
   * on-time truth, and inventing "ON_TIME" would be a lie the UI would faithfully
   * colour green.
   */
  public RealtimeStop stopBoard(String stopCode, Instant now) {
    String name =
        jdbc.sql("SELECT name FROM stops WHERE stop_code = ?").param(stopCode).query(String.class).optional().orElse(null);
    List<Arrival> arrivals =
        departures(stopCode, 90, 20, null, now).stream()
            .map(
                d ->
                    new Arrival(
                        d.line(),
                        d.destination(),
                        d.minutes(),
                        null,
                        d.clock(),
                        null,
                        null,
                        d.color(),
                        d.textColor(),
                        d.tripId()))
            .toList();
    return new RealtimeStop(stopCode, name, arrivals, null, "scheduled");
  }
}
