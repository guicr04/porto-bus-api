package pt.porto.bus.departures;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import pt.porto.bus.model.Arrival;
import pt.porto.bus.model.CombinedDeparture;
import pt.porto.bus.shared.LisbonTime;

/**
 * Pure merge of live and scheduled departures for one line at one stop. Live
 * wins; scheduled fills the gaps; a scheduled entry that duplicates a live one
 * is dropped. Sorted purely by time.
 */
public final class DepartureMerger {

  /** A timetable slot. tripId is only known when it came from the store. */
  public record Scheduled(String departureTime, String headsign, String tripId) {}

  /**
   * @param nowMin minutes since midnight, Europe/Lisbon
   * @param windowMinutes dedup tolerance, used only when a live bus has no delay value
   * @param color applied to scheduled entries so one line renders in one colour
   */
  public record Input(
      List<Arrival> realtime,
      List<Scheduled> scheduled,
      int nowMin,
      int windowMinutes,
      int limit,
      String line,
      String color,
      String textColor) {}

  private record Item(CombinedDeparture departure, int min) {}

  private DepartureMerger() {}

  public static List<CombinedDeparture> merge(Input in) {
    List<Item> items = new ArrayList<>();

    // Live arrivals: the source of truth for the near term.
    for (Arrival a : in.realtime()) {
      Integer eta = a.arrivalMinutes();

      // Anchor on the ISO estimate when there is one, and derive both the
      // displayed clock and the sort/dedup key from that same value. Deriving
      // the key from nowMin + arrival_minutes instead lets the two drift a minute
      // apart — enough to show a bus twice, and for the response to contradict
      // itself.
      String clock = LisbonTime.isoToLisbonClock(a.estimatedArrivalTime());
      Integer min = clock == null ? null : LisbonTime.clockToMinutes(clock);

      // A bus due after midnight reads as a small clock value (00:35 is 35)
      // while nowMin is still large, which would sort it to the top. Push it
      // into the next day, matching how the timetable treats "24:35".
      if (min != null && min < in.nowMin() - 60) min += 24 * 60;
      if (min == null) min = in.nowMin() + (eta == null ? 0 : eta);

      items.add(
          new Item(
              new CombinedDeparture(
                  a.line(),
                  a.destination(),
                  "realtime",
                  eta,
                  clock != null ? clock : LisbonTime.minutesToClock(min),
                  a.status(),
                  a.delayMinutes(),
                  a.color() != null ? a.color() : in.color(),
                  a.textColor() != null ? a.textColor() : in.textColor(),
                  a.tripId()),
              min));
    }

    // Which timetable slot each live bus belongs to.
    //
    // A late bus shows up at its estimated time, which can sit further from its
    // scheduled minute than any sane tolerance: a 300 estimated 18:43 with
    // delay=5 is the 18:38 slot. So when the feed gives a delay, subtract it to
    // recover the exact scheduled minute; the window is only the fallback.
    record LiveSlot(int min, Integer slot) {}
    List<LiveSlot> liveSlots =
        items.stream()
            .map(i -> new LiveSlot(i.min(), i.departure().delayMinutes() == null ? null : i.min() - i.departure().delayMinutes()))
            .toList();

    // Scheduled departures: only future ones the live feed didn't already cover.
    for (Scheduled d : in.scheduled()) {
      Integer schedMin = LisbonTime.clockToMinutes(d.departureTime());
      if (schedMin == null) continue;
      int eta = schedMin - in.nowMin();
      if (eta < 0) continue; // already departed today
      boolean duplicatesLive =
          liveSlots.stream()
              .anyMatch(
                  s ->
                      // ±1 on the slot: both ETA and delay are rounded upstream, so
                      // the recovered slot can land a minute either side.
                      s.slot() == null
                          ? Math.abs(s.min() - schedMin) <= in.windowMinutes()
                          : Math.abs(s.slot() - schedMin) <= 1);
      if (duplicatesLive) continue;
      items.add(
          new Item(
              new CombinedDeparture(
                  in.line(),
                  d.headsign(),
                  "scheduled",
                  eta,
                  LisbonTime.minutesToClock(schedMin),
                  null,
                  null,
                  in.color(),
                  in.textColor(),
                  d.tripId()),
              schedMin));
    }

    items.sort(Comparator.comparingInt(Item::min));
    return items.stream().limit(Math.max(in.limit(), 0)).map(Item::departure).toList();
  }
}
