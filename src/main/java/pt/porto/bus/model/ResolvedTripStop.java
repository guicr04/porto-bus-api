package pt.porto.bus.model;

/**
 * A stop on a resolved trip, with seconds since midnight so a client never
 * parses "24:35:00" itself. Seconds may exceed 86400 for the after-midnight tail.
 */
public record ResolvedTripStop(
    int stopSequence,
    String stopId,
    String stopCode,
    String stopName,
    Double stopLat,
    Double stopLon,
    String arrivalTime,
    String departureTime,
    int arrivalSeconds,
    int departureSeconds,
    boolean timepoint) {}
