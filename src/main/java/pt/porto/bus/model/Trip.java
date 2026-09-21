package pt.porto.bus.model;

import java.util.List;

public record Trip(String tripId, String serviceId, String tripHeadsign, int directionId, List<TripStop> stops) {}
