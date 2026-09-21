package pt.porto.bus.model;

import java.util.List;

/**
 * One bus's whole journey (README §4c).
 *
 * @param requestedTripId the id as asked for, i.e. the live one; null when asked by pattern
 * @param match how the id was matched, in descending confidence: exact, version,
 *     version_latest, pattern
 * @param feedExpired the stop order is still trustworthy; the minute-gaps come
 *     from a timetable STCP has already moved past
 */
public record ResolvedTrip(
    String tripId,
    String requestedTripId,
    String match,
    String routeId,
    String line,
    String color,
    String textColor,
    String headsign,
    Integer directionId,
    String serviceId,
    String shapeId,
    boolean feedExpired,
    List<ResolvedTripStop> stops) {}
