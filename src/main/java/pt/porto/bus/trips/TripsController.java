package pt.porto.bus.trips;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pt.porto.bus.gtfs.GtfsStore;
import pt.porto.bus.gtfs.TripResolver;
import pt.porto.bus.model.ResolvedTrip;
import pt.porto.bus.shared.ApiException;
import pt.porto.bus.shared.Numbers;

/**
 * One live bus's journey (README §4c). Store-only: STCP's live API is
 * stop-centric and never answers "where does this vehicle go next".
 *
 * <p>The query params are not filters. They are the fallback identity, taken
 * straight off the board row the rider tapped, consulted only when the id join
 * misses — the difference between a degraded screen and an empty one.
 */
@RestController
@RequestMapping("/trips")
public class TripsController {

  private final GtfsStore store;
  private final TripResolver resolver;

  public TripsController(GtfsStore store, TripResolver resolver) {
    this.store = store;
    this.resolver = resolver;
  }

  /**
   * GET /trips/stops?line=&stop=&eta_minutes=&headsign= — the same answer for a
   * departure with no id to ask by (scheduled rows from upstream carry none).
   */
  @GetMapping("/stops")
  public ResolvedTrip byPattern(
      @RequestParam(required = false) String line,
      @RequestParam(required = false) String headsign,
      @RequestParam(required = false) String stop,
      @RequestParam(name = "eta_minutes", required = false) String etaMinutes) {
    return resolve(null, line, headsign, stop, etaMinutes);
  }

  /** GET /trips/{trip_id}/stops — the id must be percent-encoded (`|` is %7C). */
  @GetMapping("/{tripId}/stops")
  public ResolvedTrip byId(
      @PathVariable String tripId,
      @RequestParam(required = false) String line,
      @RequestParam(required = false) String headsign,
      @RequestParam(required = false) String stop,
      @RequestParam(name = "eta_minutes", required = false) String etaMinutes) {
    return resolve(tripId, line, headsign, stop, etaMinutes);
  }

  private ResolvedTrip resolve(String tripId, String line, String headsign, String stop, String etaMinutes) {
    if (!store.hasData()) throw new ApiException(503, "The static store has not been ingested yet");
    Double eta = etaMinutes == null ? null : Numbers.parse(etaMinutes, Double.NaN);
    ResolvedTrip result = resolver.resolvedTripStops(tripId, new TripResolver.Hints(line, headsign, stop, eta));
    if (result == null) {
      // "We cannot identify this bus" is a real answer, different from "this bus
      // calls nowhere", and the client has a defined response to it: show the
      // line's stops without times.
      String named = tripId == null ? "that departure" : "trip '" + tripId + "'";
      throw ApiException.notFound("Could not resolve " + named + " in the static feed");
    }
    return result;
  }
}
