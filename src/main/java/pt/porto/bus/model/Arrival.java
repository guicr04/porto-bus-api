package pt.porto.bus.model;

/**
 * One bus on a stop's board.
 *
 * @param arrivalMinutes minutes until arrival; 0 means "Arriving"
 * @param estimatedArrivalTime ISO 8601, e.g. "2026-07-19T16:37:10+01:00"; null
 *     when the row came from the timetable
 * @param status "ON_TIME", "DELAYED", ... — never invented for a scheduled row
 * @param delayMinutes positive is late, negative early
 */
public record Arrival(
    String line,
    String destination,
    Integer arrivalMinutes,
    String estimatedArrivalTime,
    String scheduledArrivalTime,
    String status,
    Integer delayMinutes,
    String color,
    String textColor,
    String tripId) {}
