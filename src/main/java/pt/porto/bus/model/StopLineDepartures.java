package pt.porto.bus.model;

import java.util.List;

/**
 * @param serviceId the service used for the scheduled half
 * @param dataSource "scheduled" when the whole view is timetable-only because
 *     STCP was unreachable — distinct from each row's `source`
 */
public record StopLineDepartures(
    String stopCode,
    String line,
    Integer directionId,
    String serviceId,
    String generatedAt,
    String dataSource,
    List<CombinedDeparture> departures) {}
