package pt.porto.bus.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import pt.porto.bus.gtfs.Fixtures;
import pt.porto.bus.shared.ApiException;

class BoardControllerTest {

  @Test
  void withNoOriginAtAllTheBoardExplainsHowToSetOne() {
    BoardService boards = mock(BoardService.class);
    var controller = new BoardController(boards, Fixtures.props(Path.of("unused.db")));
    var query = new LinkedMultiValueMap<String, String>();

    assertThatThrownBy(() -> controller.board(query))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("HOME_LAT/HOME_LON")
        .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(400));

    var text = controller.boardText(query);
    assertThat(text.getStatusCode().value()).isEqualTo(400);
    assertThat(text.getHeaders().getContentType().toString()).startsWith("text/plain");
    assertThat(text.getBody()).startsWith("No origin.");
    verifyNoInteractions(boards);
  }
}
