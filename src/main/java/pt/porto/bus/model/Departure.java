package pt.porto.bus.model;

/** A timetable row. Times are "HH:MM:SS" and may exceed 24h for after-midnight trips. */
public record Departure(String departureTime, String arrivalTime, String headsign, int directionId) {}
