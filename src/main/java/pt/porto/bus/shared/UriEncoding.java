package pt.porto.bus.shared;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Exactly JavaScript's encodeURIComponent.
 *
 * <p>STCP matches ids like service_id verbatim, and they contain spaces
 * ("DOM|FERIADO:FLUXO 3.1 20260718"). A space must go out as %20: URLEncoder
 * and form-style encoders produce "+", which breaks the lookup. Every upstream
 * URL is built here and handed to the HTTP client as a finished URI, so nothing
 * downstream gets a chance to re-encode it.
 */
public final class UriEncoding {
  private static final char[] HEX = "0123456789ABCDEF".toCharArray();

  private UriEncoding() {}

  public static String component(String value) {
    StringBuilder out = new StringBuilder();
    for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xFF;
      if (isUnreserved(c)) {
        out.append((char) c);
      } else {
        out.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
      }
    }
    return out.toString();
  }

  /** A query string without the leading "?", in the map's iteration order. */
  public static String query(Map<String, ?> params) {
    return params.entrySet().stream()
        .map(e -> component(e.getKey()) + "=" + component(String.valueOf(e.getValue())))
        .collect(Collectors.joining("&"));
  }

  private static boolean isUnreserved(int c) {
    return (c >= 'A' && c <= 'Z')
        || (c >= 'a' && c <= 'z')
        || (c >= '0' && c <= '9')
        || "-_.!~*'()".indexOf(c) >= 0;
  }
}
