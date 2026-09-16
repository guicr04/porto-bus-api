package pt.porto.bus.model;

import java.util.List;

/**
 * @param stopsConsidered how many stops were in range
 * @param stopsTruncated true when max_stops kept us from polling all of them
 * @param stopsPolled the subset actually polled
 */
public record LocationBoard(
    Origin origin,
    Number walkMinutes,
    String generatedAt,
    int stopsConsidered,
    boolean stopsTruncated,
    List<PolledStop> stopsPolled,
    List<BoardRow> departures) {

  public record Origin(double lat, double lon) {}
}
