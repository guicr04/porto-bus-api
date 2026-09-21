package pt.porto.bus.model;

import java.util.List;

/**
 * @param routes one entry per line (upstream's display_routes)
 * @param directions one entry per line+direction (upstream's dropdown_routes)
 */
public record StopRoutes(List<StopRoute> routes, List<StopRoute> directions) {}
