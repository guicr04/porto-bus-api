package pt.porto.bus.board;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pt.porto.bus.model.Arrival;
import pt.porto.bus.model.BoardRow;
import pt.porto.bus.shared.LineOrder;
import pt.porto.bus.shared.Numbers;

/**
 * Pure assembly of a departure board from several nearby stops.
 *
 * <p>The idea that makes this useful rather than a list of buses: a departure is
 * only worth showing if you can physically reach the stop before it leaves.
 * Every arrival gets a `leave_in_minutes` — how long until you must walk out the
 * door. Negative means it's gone as far as you're concerned.
 */
public final class BoardAssembler {

  /** A polled stop's board, and how far away it is. */
  public record StopBoard(
      String stopCode, String name, int walkMinutes, long distanceMeters, List<Arrival> arrivals, String dataSource) {}

  /**
   * @param collapse one row per line+destination
   * @param buffer minutes of slack to leave
   * @param sortByLine false orders by arrival
   */
  public record Options(int limit, boolean includeUnreachable, boolean collapse, double buffer, boolean sortByLine) {
    public static final Options DEFAULTS = new Options(10, false, true, 0, true);
  }

  private static final Comparator<BoardRow> BY_ARRIVAL =
      Comparator.comparingInt(BoardRow::etaMinutes).thenComparingInt(BoardRow::walkMinutes);

  private BoardAssembler() {}

  public static List<BoardRow> build(List<StopBoard> stops, Options o) {
    List<BoardRow> rows = new ArrayList<>();

    for (StopBoard stop : stops) {
      // "realtime" when the buses are actually tracked, anything else when STCP
      // (or this API) fell back to the timetable. Carried per row so a display
      // can mark tracked times apart from projections.
      boolean isRealtime = "realtime".equals(stop.dataSource());
      for (Arrival a : stop.arrivals()) {
        if (a.arrivalMinutes() == null) continue;
        double leaveIn = a.arrivalMinutes() - stop.walkMinutes() - o.buffer();
        rows.add(
            new BoardRow(
                a.line(),
                a.destination(),
                isRealtime,
                stop.dataSource(),
                stop.stopCode(),
                stop.name(),
                stop.walkMinutes(),
                stop.distanceMeters(),
                a.arrivalMinutes(),
                Numbers.compact(leaveIn),
                leaveIn >= 0,
                a.status(),
                a.delayMinutes(),
                a.color(),
                a.textColor()));
      }
    }

    if (!o.includeUnreachable()) rows.removeIf(r -> !r.catchable());

    // By arrival first, whatever the caller asked for.
    rows.sort(BY_ARRIVAL);

    if (o.collapse()) {
      // The same line+direction often serves several stops within walking
      // distance. Show one row: the least walking, not whichever stop's tracked
      // ETA happened to read lowest. Two stops close together frequently report
      // the same physical bus a minute or two apart, so "soonest ETA" was often
      // just tracking noise deciding which stop won.
      //
      // Accepted trade-off: this reports what's coming to *your* nearest stop
      // for a line, not the earliest bus catchable from anywhere nearby.
      Map<String, BoardRow> best = new LinkedHashMap<>();
      for (BoardRow r : rows) {
        String key = r.line() + " " + r.destination();
        BoardRow current = best.get(key);
        if (current == null
            || r.walkMinutes() < current.walkMinutes()
            || (r.walkMinutes() == current.walkMinutes() && r.distanceMeters() < current.distanceMeters())
            || (r.walkMinutes() == current.walkMinutes()
                && r.distanceMeters() == current.distanceMeters()
                && r.etaMinutes() < current.etaMinutes())) {
          best.put(key, r);
        }
      }
      rows = new ArrayList<>(best.values());
      rows.sort(BY_ARRIVAL);
    }

    // Trim before the final sort: "the next N buses, listed by line", not "the N
    // lowest-numbered lines, whenever they happen to run".
    rows = new ArrayList<>(rows.subList(0, Math.min(Math.max(o.limit(), 0), rows.size())));

    if (o.sortByLine()) {
      rows.sort(Comparator.comparing(BoardRow::line, LineOrder::compare).thenComparingInt(BoardRow::etaMinutes));
    }
    return rows;
  }
}
