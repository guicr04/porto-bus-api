package pt.porto.bus.model;

/**
 * One row of the merged live + scheduled view.
 *
 * @param source "realtime" or "scheduled" — style these differently
 * @param etaMinutes minutes from now; 0 is arriving
 * @param time "HH:MM" clock time (estimated for live, scheduled otherwise)
 * @param tripId always set on realtime rows; on scheduled ones only when they
 *     came from the store. Without one, ask `/trips/stops` with the hints.
 */
public record CombinedDeparture(
    String line,
    String destination,
    String source,
    Integer etaMinutes,
    String time,
    String status,
    Integer delayMinutes,
    String color,
    String textColor,
    String tripId) {}
