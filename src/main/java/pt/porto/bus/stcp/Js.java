package pt.porto.bus.stcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Reading undocumented JSON with the same forgiveness the parsers have always
 * had: a missing field, a JSON null and a wrong type never throw, they fall back.
 * The names mirror the JavaScript idioms these parsers were validated with
 * ({@code x ?? null}, {@code Number(x)}, truthiness).
 */
final class Js {
  private static final JsonNode EMPTY_ARRAY = JsonNodeFactory.instance.arrayNode();

  private Js() {}

  /** undefined or null. */
  static boolean absent(JsonNode n) {
    return n == null || n.isMissingNode() || n.isNull();
  }

  /** The first of several spellings that is present ({@code a ?? b ?? c}). */
  static JsonNode first(JsonNode obj, String... keys) {
    for (String k : keys) {
      JsonNode v = obj.path(k);
      if (!absent(v)) return v;
    }
    return null;
  }

  /** {@code x ?? null}, as text. */
  static String str(JsonNode n) {
    if (absent(n)) return null;
    if (n.isString()) return n.stringValue();
    return n.isValueNode() ? n.asString() : n.toString();
  }

  /** {@code String(x ?? fallback)}. */
  static String str(JsonNode n, String fallback) {
    String s = str(n);
    return s == null ? fallback : s;
  }

  /** {@code Number(x)}, or null where JavaScript would produce NaN. */
  static Double number(JsonNode n) {
    if (absent(n)) return null;
    if (n.isNumber()) return n.doubleValue();
    if (n.isBoolean()) return n.booleanValue() ? 1.0 : 0.0;
    if (n.isString()) {
      String s = n.stringValue().trim();
      if (s.isEmpty()) return 0.0;
      try {
        double v = Double.parseDouble(s);
        return Double.isFinite(v) ? v : null;
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  /** numOrNull: absent or "" is null, otherwise {@code Number(x)}. */
  static Double numOrNull(JsonNode n) {
    if (absent(n) || (n.isString() && n.stringValue().isEmpty())) return null;
    return number(n);
  }

  static Integer intOrNull(JsonNode n) {
    Double d = numOrNull(n);
    return d == null ? null : (int) Math.round(d);
  }

  /** {@code Number(x ?? fallback)}. */
  static int intOr(JsonNode n, int fallback) {
    Double d = absent(n) ? null : number(n);
    return d == null ? fallback : (int) Math.round(d);
  }

  /** {@code Boolean(x)}. */
  static boolean truthy(JsonNode n) {
    if (absent(n)) return false;
    if (n.isBoolean()) return n.booleanValue();
    if (n.isNumber()) return n.doubleValue() != 0 && !Double.isNaN(n.doubleValue());
    if (n.isString()) return !n.stringValue().isEmpty();
    return true;
  }

  /** The node when it is an array, an empty one otherwise. */
  static JsonNode array(JsonNode n) {
    return n != null && n.isArray() ? n : EMPTY_ARRAY;
  }
}
