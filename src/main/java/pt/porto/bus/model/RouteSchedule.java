package pt.porto.bus.model;

import java.util.List;

/**
 * The full timetable grid for a line in one direction.
 *
 * @param timepointStops the column headers (upstream's selected_stops)
 * @param trips sorted by first departure
 */
public record RouteSchedule(
    String routeId, String serviceId, int directionId, List<DirectionStop> timepointStops, List<Trip> trips) {}
