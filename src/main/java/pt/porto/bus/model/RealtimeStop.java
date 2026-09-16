package pt.porto.bus.model;

import java.util.List;

/**
 * A stop's board.
 *
 * @param dataSource "realtime" when STCP is tracking, "scheduled" when this API
 *     fell back to the static timetable. A client must render the difference.
 */
public record RealtimeStop(
    String stopCode, String stopName, List<Arrival> arrivals, String lastUpdated, String dataSource) {}
