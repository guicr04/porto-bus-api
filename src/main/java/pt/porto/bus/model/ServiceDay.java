package pt.porto.bus.model;

import java.util.Map;

/**
 * @param days monday..sunday flags as returned upstream — always 0, so prefer
 *     isActiveToday (README §2)
 */
public record ServiceDay(String serviceId, String serviceName, boolean isActiveToday, Map<String, Integer> days) {}
