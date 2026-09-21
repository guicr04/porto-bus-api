package pt.porto.bus.model;

/** @param ok false when the live call for this stop failed — an empty board vs a broken one */
public record PolledStop(String stopCode, String name, long distanceMeters, int walkMinutes, boolean ok) {}
