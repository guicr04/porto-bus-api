package pt.porto.bus.departures;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import pt.porto.bus.departures.DepartureMerger.Input;
import pt.porto.bus.departures.DepartureMerger.Scheduled;
import pt.porto.bus.model.Arrival;
import pt.porto.bus.model.CombinedDeparture;

/**
 * The live + scheduled merge. Its dedup rule is a heuristic, so it is worth
 * pinning down.
 */
class DepartureMergerTest {

  static final int NOW = 17 * 60 + 50; // 17:50

  static Arrival live(Integer minutes, Integer delay, String estimate) {
    return new Arrival("300", "Aliados - H.S.João", minutes, estimate, null, "ON_TIME", delay, "#417DBD", "#FFFFFF", null);
  }

  static List<Scheduled> sched(String... times) {
    return Arrays.stream(times).map(t -> new Scheduled(t, "Aliados - H.S.João", null)).toList();
  }

  static List<CombinedDeparture> merge(List<Arrival> live, List<Scheduled> sched, int now, int limit) {
    return DepartureMerger.merge(new Input(live, sched, now, 3, limit, "300", null, null));
  }

  static List<String> timeAndSource(List<CombinedDeparture> out) {
    return out.stream().map(d -> d.time() + " " + d.source()).toList();
  }

  @Test
  void aLateLiveBusDoesNotDuplicateItsOwnTimetableSlot() {
    // Estimated 18:43, running 5 late => it IS the 18:38 slot. A naive ±3 window
    // would keep both and show the same bus twice.
    var out = merge(List.of(live(53, 5, "2026-07-19T18:43:54+01:00")), sched("18:38", "19:08"), NOW, 10);
    assertThat(timeAndSource(out)).containsExactly("18:43 realtime", "19:08 scheduled");
  }

  @Test
  void fallsBackToTheToleranceWindowWhenDelayIsUnknown() {
    var out = merge(List.of(live(20, null, null)), sched("18:12", "18:40"), NOW, 10); // live is 18:10
    assertThat(timeAndSource(out)).containsExactly("18:10 realtime", "18:40 scheduled");
  }

  @Test
  void anEarlyBusMatchesItsSlotToo() {
    // Estimated 18:06, running 4 early => the 18:10 slot.
    var out = merge(List.of(live(16, -4, null)), sched("18:10"), NOW, 10);
    assertThat(out).extracting(CombinedDeparture::source).containsExactly("realtime");
  }

  @Test
  void pastScheduledDeparturesAreDroppedAndFutureOnesInterleaveByTime() {
    var out = merge(List.of(live(30, 0, null)), sched("17:00", "17:49", "18:00", "18:40"), NOW, 10);
    assertThat(timeAndSource(out)).containsExactly("18:00 scheduled", "18:20 realtime", "18:40 scheduled");
  }

  @Test
  void scheduledEntriesInheritTheLiveColour() {
    var out =
        DepartureMerger.merge(new Input(List.of(live(5, 0, null)), sched("18:40"), NOW, 3, 10, "300", "#417DBD", "#FFFFFF"));
    assertThat(out).extracting(CombinedDeparture::color).containsOnly("#417DBD");
  }

  @Test
  void sortsOnTheEstimatedArrivalTimeNotNowPlusEta() {
    // eta implies 17:55, the estimate says 18:30. The clock shown comes from the
    // estimate, so the ordering must too.
    var out = merge(List.of(live(5, null, "2026-07-19T18:30:00+01:00")), sched("18:00", "18:45"), NOW, 10);
    assertThat(timeAndSource(out)).containsExactly("18:00 scheduled", "18:30 realtime", "18:45 scheduled");
  }

  @Test
  void aBusDueAfterMidnightSortsLastInsteadOfFirst() {
    var out = merge(List.of(live(75, null, "2026-07-20T00:35:00+01:00")), sched("23:30"), 23 * 60 + 20, 10);
    assertThat(timeAndSource(out)).containsExactly("23:30 scheduled", "00:35 realtime");
  }

  @Test
  void limitCapsTheResult() {
    assertThat(merge(List.of(), sched("18:00", "18:10", "18:20", "18:30"), NOW, 2)).hasSize(2);
  }

  @Test
  void anEmptyLiveBoardDegradesToAPureTimetableView() {
    var out = merge(List.of(), sched("18:00", "18:30"), NOW, 10);
    assertThat(out).extracting(CombinedDeparture::source).containsExactly("scheduled", "scheduled");
  }

  @Test
  void storeBackedScheduledRowsKeepTheirTripId() {
    var out = merge(List.of(), List.of(new Scheduled("18:00:00", "Aliados", "300_0_1|276|D1|T1|N1")), NOW, 10);
    assertThat(out.getFirst().tripId()).isEqualTo("300_0_1|276|D1|T1|N1");
  }
}
