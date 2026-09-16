package pt.porto.bus.board;

import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pt.porto.bus.model.LocationBoard;
import pt.porto.bus.shared.ApiException;
import pt.porto.bus.shared.AppProperties;
import pt.porto.bus.shared.Geo;
import pt.porto.bus.shared.Numbers;

/**
 * What can I still catch from here, on foot? (README §4b)
 *
 * <p>Two representations of one board: JSON for an app or firmware, and
 * fixed-width text for a microcontroller with no JSON parser.
 */
@RestController
public class BoardController {

  private static final String NO_ORIGIN =
      "No origin. Pass ?lat=&lon=, or set HOME_LAT/HOME_LON in .env "
          + "(find them for an address with: make geocode ADDRESS=\"...\").";

  private static final MediaType TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);

  private final BoardService boards;
  private final AppProperties props;

  public BoardController(BoardService boards, AppProperties props) {
    this.boards = boards;
    this.props = props;
  }

  /**
   * GET /board?lat=&lon=&walk_minutes=&limit=&max_stops=&buffer=
   * &include_unreachable=&collapse=&walk_speed=&sort=
   */
  @GetMapping(value = "/board", produces = MediaType.APPLICATION_JSON_VALUE)
  public LocationBoard board(@RequestParam MultiValueMap<String, String> query) {
    BoardService.Request req = request(query);
    if (req == null) throw ApiException.badRequest(NO_ORIGIN);
    return boards.locationBoard(req);
  }

  /** The same board as text/plain, plus width=, title= and color=. */
  @GetMapping("/board.txt")
  public ResponseEntity<String> boardText(@RequestParam MultiValueMap<String, String> query) {
    BoardService.Request req = request(query);
    if (req == null) return ResponseEntity.badRequest().contentType(TEXT).body(NO_ORIGIN);

    LocationBoard board = boards.locationBoard(req);
    String title = query.getFirst("title");
    String rendered =
        BoardRenderer.render(
            board.departures(),
            Numbers.parseInt(query.getFirst("width"), 42),
            title != null ? title : props.homeLabel(),
            Numbers.parseBool(query.getFirst("color"), false));
    return ResponseEntity.ok().contentType(TEXT).body(rendered + "\n");
  }

  /** Null when there is no origin: neither ?lat=&lon= nor HOME_LAT/HOME_LON. */
  private BoardService.Request request(MultiValueMap<String, String> q) {
    Double lat = Numbers.parse(q.getFirst("lat"), props.homeLat());
    Double lon = Numbers.parse(q.getFirst("lon"), props.homeLon());
    if (lat == null || lon == null) return null;
    return new BoardService.Request(
        lat,
        lon,
        Numbers.parse(q.getFirst("walk_minutes"), 10.0),
        Numbers.parseInt(q.getFirst("max_stops"), 12),
        Numbers.parse(q.getFirst("walk_speed"), Geo.WALK_METERS_PER_MINUTE),
        new BoardAssembler.Options(
            Numbers.parseInt(q.getFirst("limit"), 10),
            Numbers.parseBool(q.getFirst("include_unreachable"), false),
            Numbers.parseBool(q.getFirst("collapse"), true),
            Numbers.parse(q.getFirst("buffer"), 0.0),
            !"eta".equals(q.getFirst("sort"))));
  }
}
