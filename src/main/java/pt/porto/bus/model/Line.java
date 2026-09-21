package pt.porto.bus.model;

/**
 * A line from the static store.
 *
 * @param line short name shown to riders, e.g. "500"; falls back to route_id
 * @param description long name, e.g. "Cordoaria - Matosinhos"
 * @param color official GTFS route_color, e.g. "#187EC2" — a family colour, not
 *     the line's own (that one only exists on the live board)
 */
public record Line(String line, String description, String routeId, String color, String textColor) {}
