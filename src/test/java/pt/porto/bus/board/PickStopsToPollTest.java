package pt.porto.bus.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import pt.porto.bus.board.BoardService.Candidate;

class PickStopsToPollTest {

  static Candidate stop(String code, String name) {
    return new Candidate(code, name, null, null);
  }

  @Test
  void pollingPrefersDistinctStopNamesOverAClusterOfPlatforms() {
    // Three CORDOARIA platforms within metres would otherwise eat the whole
    // budget and leave the rest of the radius unseen.
    var nearby =
        List.of(
            stop("CORD1", "CORDOARIA"),
            stop("CORD3", "CORDOARIA"),
            stop("CORD5", "CORDOARIA"),
            stop("GGF", "GUIL. G. FERNANDES"),
            stop("CMO", "CARMO"));
    assertThat(BoardService.pickStopsToPoll(nearby, 3)).extracting(Candidate::stopCode).containsExactly("CORD1", "GGF", "CMO");
  }

  @Test
  void platformsAreDedupedByProximityEvenWhenNamedDifferently() {
    // The same place spelled two ways, ~10 m apart.
    var nearby =
        List.of(
            new Candidate("GGF", "GUIL. G. FERNANDES", 41.14724, -8.61462),
            new Candidate("GGF1", "GUILHERME GOMES FERNANDES", 41.14730, -8.61470),
            new Candidate("CMO", "CARMO", 41.147223, -8.616926));
    assertThat(BoardService.pickStopsToPoll(nearby, 2)).extracting(Candidate::stopCode).containsExactly("GGF", "CMO");
  }

  @Test
  void leftoverBudgetFallsBackToTheDuplicatePlatforms() {
    var nearby = List.of(stop("CORD1", "CORDOARIA"), stop("CORD3", "CORDOARIA"), stop("CMO", "CARMO"));
    assertThat(BoardService.pickStopsToPoll(nearby, 3)).extracting(Candidate::stopCode).containsExactly("CORD1", "CMO", "CORD3");
  }
}
