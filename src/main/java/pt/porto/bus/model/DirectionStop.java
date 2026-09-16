package pt.porto.bus.model;

/** A stop on a line's direction; both upstream spellings normalise to this. */
public record DirectionStop(
    String stopId, String stopName, String stopCode, String zoneId, Double lat, Double lon, int sequence, String description) {}
