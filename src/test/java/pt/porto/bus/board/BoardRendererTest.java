package pt.porto.bus.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import pt.porto.bus.board.BoardAssembler.Options;
import pt.porto.bus.model.BoardRow;

class BoardRendererTest {

  static List<BoardRow> rows(BoardAssembler.StopBoard... stops) {
    return BoardAssembler.build(List.of(stops), Options.DEFAULTS);
  }

  @Test
  void rendersFixedWidthRows() {
    var rows = rows(BoardAssemblerTest.atStop(3, List.of(BoardAssemblerTest.arrival("300", 9))));
    String first = BoardRenderer.render(rows, 42, null, false).split("\n")[0];
    assertThat(first).hasSize(42).startsWith("300 ").endsWith("9m");
    assertThat(first).as("walking minutes should not be rendered").doesNotContainPattern("\\dw");
  }

  @Test
  void rendersATitleAndRule() {
    String[] lines = BoardRenderer.render(List.of(), 20, "DESK", false).split("\n");
    assertThat(lines[0]).isEqualTo("DESK" + " ".repeat(16));
    assertThat(lines[1]).isEqualTo("-".repeat(20));
  }

  @Test
  void rendersAReadableMessageWhenNothingIsReachable() {
    assertThat(BoardRenderer.render(List.of(), 42, null, false)).contains("no departures");
  }

  @Test
  void colourMarksLiveTimesGreenAndLeavesProjectedOnesPlain() {
    var rows =
        rows(
            BoardAssemblerTest.atStop(1, List.of(BoardAssemblerTest.arrival("200", 5)), "S1", "realtime"),
            BoardAssemblerTest.atStop(1, List.of(BoardAssemblerTest.arrival("300", 9)), "S2", "scheduled"));
    String[] lines = BoardRenderer.render(rows, 42, null, true).split("\n");
    assertThat(lines[0]).endsWith(BoardRenderer.GREEN + "  5m" + BoardRenderer.RESET);
    assertThat(lines[1]).as("projected times must stay uncoloured").doesNotContain(BoardRenderer.RESET);
  }

  @Test
  void colourIsOffByDefault() {
    var rows = rows(BoardAssemblerTest.atStop(1, List.of(BoardAssemblerTest.arrival("300", 9))));
    assertThat(BoardRenderer.render(rows, 42, null, false)).doesNotContain(BoardRenderer.RESET);
  }

  @Test
  void escapeCodesDoNotCountTowardsTheColumnWidth() {
    var rows = rows(BoardAssemblerTest.atStop(1, List.of(BoardAssemblerTest.arrival("300", 9))));
    String row = BoardRenderer.render(rows, 42, null, true).split("\n")[0];
    assertThat(row.replace(BoardRenderer.GREEN, "").replace(BoardRenderer.RESET, "")).hasSize(42);
  }
}
