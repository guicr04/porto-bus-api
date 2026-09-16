package pt.porto.bus.board;

import java.util.ArrayList;
import java.util.List;
import pt.porto.bus.model.BoardRow;

/**
 * A board as fixed-width text, the shape an LED matrix or a small display wants:
 * one row per departure, columns aligned, nothing to parse.
 */
public final class BoardRenderer {

  private static final char ESC = (char) 27;
  static final String GREEN = ESC + "[32m";
  static final String RESET = ESC + "[0m";

  private BoardRenderer() {}

  /**
   * @param title printed above a rule; null or empty for none
   * @param color colour live times green with ANSI escapes. Off by default: a
   *     microcontroller wants the `realtime` flag from /board, not escape codes
   *     it has to strip
   */
  public static String render(List<BoardRow> rows, int width, String title, boolean color) {
    List<String> lines = new ArrayList<>();
    if (title != null && !title.isEmpty()) {
      lines.add(pad(title, width));
      lines.add("-".repeat(Math.max(width, 0)));
    }

    if (rows.isEmpty()) {
      lines.add(pad("no departures within reach", width));
      return String.join("\n", lines);
    }

    // LINE(4) DEST(rest) ETA(4). The walk still decides which buses make the
    // board; it just isn't shown — the destination gets the space instead.
    int destWidth = Math.max(8, width - 4 - 1 - 4 - 1);

    for (BoardRow r : rows) {
      // Colour goes on after padding, so escape sequences never count towards
      // the column width and a coloured board still lines up.
      String eta = padStart(r.etaMinutes() + "m", 4);
      if (color && r.realtime()) eta = GREEN + eta + RESET;
      lines.add(pad(r.line(), 4) + " " + pad(r.destination() == null ? "" : r.destination(), destWidth) + " " + eta);
    }
    return String.join("\n", lines);
  }

  private static String pad(String s, int n) {
    String v = String.valueOf(s);
    if (n <= 0) return "";
    if (v.length() > n) v = v.substring(0, n);
    return v + " ".repeat(n - v.length());
  }

  private static String padStart(String s, int n) {
    String v = s.length() > n ? s.substring(0, n) : s;
    return " ".repeat(n - v.length()) + v;
  }
}
