package pt.porto.bus.model;

/**
 * One departure on the location board.
 *
 * @param realtime true when STCP is tracking the buses at this stop
 * @param dataSource the raw upstream label behind `realtime`
 * @param walkMinutes minutes to walk from the origin to the stop
 * @param etaMinutes minutes until the bus reaches the stop
 * @param leaveInMinutes eta minus the walk minus any buffer; negative means the
 *     walk is longer than the wait
 */
public record BoardRow(
    String line,
    String destination,
    boolean realtime,
    String dataSource,
    String stopCode,
    String stopName,
    int walkMinutes,
    long distanceMeters,
    int etaMinutes,
    Number leaveInMinutes,
    boolean catchable,
    String status,
    Integer delayMinutes,
    String color,
    String textColor) {}
