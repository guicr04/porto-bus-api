package pt.porto.bus.model;

import java.util.List;

/** @param departures flattened out of upstream's hour-keyed buckets, sorted by time */
public record StopSchedule(String stopCode, String routeId, int directionId, String serviceId, List<Departure> departures) {}
