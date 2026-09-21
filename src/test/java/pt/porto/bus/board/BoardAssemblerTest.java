package pt.porto.bus.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import pt.porto.bus.board.BoardAssembler.Options;
import pt.porto.bus.board.BoardAssembler.StopBoard;
import pt.porto.bus.model.Arrival;
import pt.porto.bus.model.BoardRow;

/** The "can I still catch it" rule, which is the whole point of the board. */
class BoardAssemblerTest {

  static Arrival arrival(String line, Integer minutes) {
    return arrival(line, "Aliados", minutes);
  }

  static Arrival arrival(String line, String destination, Integer minutes) {
    return new Arrival(line, destination, minutes, null, null, "ON_TIME", null, "#417DBD", "#FFFFFF", null);
  }

  static StopBoard atStop(int walk, List<Arrival> arrivals, String code, String dataSource) {
    return new StopBoard(code, code, walk, walk * 100L, arrivals, dataSource);
  }

  static StopBoard atStop(int walk, List<Arrival> arrivals, String code) {
    return atStop(walk, arrivals, code, "realtime");
  }

  static StopBoard atStop(int walk, List<Arrival> arrivals) {
    return atStop(walk, arrivals, "CMO");
  }

  static Options opts() {
    return Options.DEFAULTS;
  }

  static List<BoardRow> build(Options o, StopBoard... stops) {
    return BoardAssembler.build(List.of(stops), o);
  }

  @Test
  void aBusThatArrivesBeforeYouCouldWalkThereIsDropped() {
    var rows = build(opts(), atStop(8, List.of(arrival("300", 3), arrival("500", 12))));
    assertThat(rows).extracting(BoardRow::line).containsExactly("500");
    assertThat(rows.getFirst().leaveInMinutes()).isEqualTo(4L); // 12 - 8
  }

  @Test
  void includeUnreachableKeepsItButFlagsIt() {
    var rows = build(new Options(10, true, true, 0, true), atStop(8, List.of(arrival("300", 3))));
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().catchable()).isFalse();
    assertThat(rows.getFirst().leaveInMinutes()).isEqualTo(-5L);
  }

  @Test
  void aBufferMakesTheCutOffStricter() {
    // 10 min bus, 8 min walk: reachable with no buffer, not with 3 minutes of slack.
    var stop = atStop(8, List.of(arrival("300", 10)));
    assertThat(build(opts(), stop)).hasSize(1);
    assertThat(build(new Options(10, false, true, 3, true), stop)).isEmpty();
  }

  @Test
  void aFractionalBufferKeepsItsFraction() {
    var rows = build(new Options(10, true, true, 1.5, true), atStop(2, List.of(arrival("300", 10))));
    assertThat(rows.getFirst().leaveInMinutes()).isEqualTo(6.5);
  }

  @Test
  void theSameLineAtTwoNearbyStopsCollapsesToTheCloserOneEvenWithALaterEta() {
    var rows = build(opts(), atStop(2, List.of(arrival("300", 20)), "CMO"), atStop(5, List.of(arrival("300", 12)), "ALD"));
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().stopCode()).isEqualTo("CMO");
  }

  @Test
  void theCloserStopWinsByWalkTimeEvenWhenTheFartherOneReadsSooner() {
    // A 701 read 13 min at a stop 9 min away and 14 min at a stop 6 min away —
    // the same physical bus a minute of tracking noise apart.
    var rows = build(opts(), atStop(9, List.of(arrival("300", 13)), "FAR"), atStop(6, List.of(arrival("300", 14)), "NEAR"));
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().stopCode()).isEqualTo("NEAR");
  }

  @Test
  void closerStopWinsUnconditionally() {
    var rows = build(opts(), atStop(2, List.of(arrival("300", 25)), "NEAR"), atStop(9, List.of(arrival("300", 11)), "FAR"));
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().stopCode()).isEqualTo("NEAR");
    assertThat(rows.getFirst().etaMinutes()).isEqualTo(25);
  }

  @Test
  void collapseFalseKeepsEveryStopOption() {
    var rows =
        build(new Options(10, false, false, 0, true), atStop(2, List.of(arrival("300", 20)), "CMO"), atStop(5, List.of(arrival("300", 12)), "ALD"));
    assertThat(rows).extracting(BoardRow::stopCode).containsExactly("ALD", "CMO");
  }

  @Test
  void differentDestinationsOnTheSameLineAreKeptApart() {
    var rows = build(opts(), atStop(2, List.of(arrival("300", "Aliados", 10), arrival("300", "Matosinhos", 14))));
    assertThat(rows).hasSize(2);
  }

  @Test
  void rowsAreOrderedByLine() {
    var rows =
        build(
            opts(),
            atStop(1, List.of(arrival("801", 4)), "S1"),
            atStop(1, List.of(arrival("207", 15)), "S2"),
            atStop(1, List.of(arrival("305", 9)), "S3"));
    assertThat(rows).extracting(BoardRow::line).containsExactly("207", "305", "801");
  }

  @Test
  void sortEtaStillOrdersByArrival() {
    var rows =
        build(new Options(10, false, true, 0, false), atStop(1, List.of(arrival("801", 4)), "S1"), atStop(1, List.of(arrival("207", 15)), "S2"));
    assertThat(rows).extracting(BoardRow::line).containsExactly("801", "207");
  }

  @Test
  void theCloserStopPerLineSurvivesThenRowsAreResortedByLine() {
    var rows =
        build(
            opts(),
            atStop(2, List.of(arrival("500", 20)), "CLOSE"),
            atStop(3, List.of(arrival("500", 6)), "FARTHER"),
            atStop(1, List.of(arrival("200", 12)), "S3"));
    assertThat(rows).extracting(r -> r.line() + "@" + r.etaMinutes()).containsExactly("200@12", "500@20");
  }

  @Test
  void limitTakesTheSoonestBusesThenOrdersThemByLine() {
    var rows = build(new Options(2, false, true, 0, true), atStop(1, List.of(arrival("100", 40), arrival("900", 2), arrival("500", 5))));
    assertThat(rows).extracting(BoardRow::line).containsExactly("500", "900");
  }

  @Test
  void arrivalsWithNoEtaAreIgnoredRatherThanSortedAsZero() {
    var rows = build(opts(), atStop(1, List.of(arrival("300", null), arrival("500", 10))));
    assertThat(rows).extracting(BoardRow::line).containsExactly("500");
  }

  @Test
  void limitCapsTheBoard() {
    var arrivals = IntStream.rangeClosed(1, 5).mapToObj(n -> arrival("L" + n, n)).toList();
    assertThat(build(new Options(3, false, true, 0, true), atStop(1, arrivals))).hasSize(3);
  }

  @Test
  void rowsAreFlaggedRealtimeWhenStcpIsTrackingTheStop() {
    var rows =
        build(opts(), atStop(1, List.of(arrival("200", 10)), "S1", "realtime"), atStop(1, List.of(arrival("300", 10)), "S2", "scheduled"));
    assertThat(rows).extracting(r -> r.line() + "=" + r.realtime()).containsExactly("200=true", "300=false");
  }

  @Test
  void aMissingDataSourceIsNotTreatedAsLive() {
    assertThat(build(opts(), atStop(1, List.of(arrival("300", 10)), "S1", null)).getFirst().realtime()).isFalse();
  }
}
