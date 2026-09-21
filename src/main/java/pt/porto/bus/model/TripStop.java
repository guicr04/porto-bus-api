package pt.porto.bus.model;

public record TripStop(
    int stopSequence, String stopId, String stopName, Double stopLat, Double stopLon, String arrivalTime, String departureTime) {}
