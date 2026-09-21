package pt.porto.bus.model;

/** A line at a stop. The direction fields are only set on the per-direction variant. */
public record StopRoute(
    String routeId,
    String shortName,
    String longName,
    String color,
    String textColor,
    Integer routeType,
    Integer directionId,
    String directionName,
    String displayName,
    String tripHeadsign) {}
