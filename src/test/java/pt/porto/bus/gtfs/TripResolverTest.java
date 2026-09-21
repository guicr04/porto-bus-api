package pt.porto.bus.gtfs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pt.porto.bus.gtfs.TripResolver.Hints;
import pt.porto.bus.model.ResolvedTripStop;

/**
 * Resolving a live trip_id to a trip in the store. A join on an undocumented id
 * format, so these are mostly about the ways it is allowed to fail, and about
 * staying deterministic when the feed cannot decide for itself.
 */
class TripResolverTest {

  // A weekday that has service, and one that doesn't (the feed ends on the 15th).
  static final Instant ON_SERVICE_DAY = at("2026-08-14T09:00:00+01:00");
  static final Instant PAST_FEED_END = at("2026-08-16T09:00:00+01:00");

  @TempDir static Path dir;
  static TripResolver trips;

  static Instant at(String iso) {
    return OffsetDateTime.parse(iso).toInstant();
  }

  @BeforeAll
  static void load() {
    var s = Fixtures.store(dir, Clock.fixed(ON_SERVICE_DAY, ZoneOffset.UTC));
    s.load(Fixtures.tripsFeed());
    trips = s.trips();
  }

  @Test
  void normalizingDropsTheFeedVersionAndNothingElse() {
    assertThat(TripResolver.normalizeTripId("601_0_1|280|D3|T1|N6")).isEqualTo("601_0_1|D3|T1|N6");
    assertThat(TripResolver.normalizeTripId("601_0_1|276|D3|T1|N6")).isEqualTo("601_0_1|D3|T1|N6");
    // Not a versioned id at all: left alone rather than mangled.
    assertThat(TripResolver.normalizeTripId("T1")).isEqualTo("T1");
    assertThat(TripResolver.normalizeTripId("A|B")).isEqualTo("A|B");
  }

  @Test
  void anIdTheStoreHoldsVerbatimMatchesExactly() {
    var r = trips.resolve("601_0_1|276|D3|T1|N8", ON_SERVICE_DAY);
    assertThat(r.match()).isEqualTo("exact");
    assertThat(r.trip().tripId()).isEqualTo("601_0_1|276|D3|T1|N8");
  }

  @Test
  void aLiveIdResolvesOnceTheFeedVersionIsDropped() {
    // What STCP actually serves: a version the ingested zip has never seen.
    var r = trips.resolve("601_0_1|280|D3|T1|N8", ON_SERVICE_DAY);
    assertThat(r.match()).isEqualTo("version");
    assertThat(r.trip().tripId()).isEqualTo("601_0_1|276|D3|T1|N8");
    assertThat(r.trip().headsign()).isEqualTo("Aeroporto");
    assertThat(r.trip().directionId()).isZero();
  }

  @Test
  void theDaysServiceBreaksATieBetweenTwoFeedVersions() {
    // Both 274 and 276 normalise to the same key; only SVC_B runs on the 14th.
    var r = trips.resolve("601_0_1|280|D3|T1|N6", ON_SERVICE_DAY);
    assertThat(r.match()).isEqualTo("version");
    assertThat(r.trip().serviceId()).isEqualTo("SVC_B");
    assertThat(r.trip().tripId()).isEqualTo("601_0_1|276|D3|T1|N6");
  }

  @Test
  void withNoServiceTodayTheNewestVersionWinsAndSaysSo() {
    // Past feed_end_date no service_dates row exists at all. The answer must
    // still be deterministic, and must not pass itself off as certain.
    var r = trips.resolve("601_0_1|280|D3|T1|N6", PAST_FEED_END);
    assertThat(r.match()).isEqualTo("version_latest");
    assertThat(r.trip().tripId()).as("the reissue, not the superseded copy").isEqualTo("601_0_1|276|D3|T1|N6");
  }

  @Test
  void underscoresAreMatchedLiterallyNotAsSqlWildcards() {
    assertThat(TripResolver.versionWildcard("601_0_1|280|D3|T1|N6")).isEqualTo("601\\_0\\_1|%|D3|T1|N6");
    var r = trips.resolve("601_0_1|280|D3|T1|N6", ON_SERVICE_DAY);
    assertThat(r.trip().headsign()).isNotEqualTo("Decoy");
    assertThat(r.trip().tripId()).startsWith("601_0_1|");
  }

  @Test
  void anIdThatMatchesNothingResolvesToNothing() {
    assertThat(trips.resolve("999_9_9|280|D9|T9|N9", ON_SERVICE_DAY)).isNull();
    assertThat(trips.resolve("gibberish", ON_SERVICE_DAY)).isNull();
  }

  @Test
  void aTripCarriesItsStopsInOrderWithCoordinatesAndSeconds() {
    var stops = trips.tripStops("601_0_1|276|D3|T1|N6");
    assertThat(stops).extracting(ResolvedTripStop::stopCode).containsExactly("AAA1", "BBB2", "CCC3");
    assertThat(stops).extracting(ResolvedTripStop::stopSequence).containsExactly(1, 2, 3);
    assertThat(stops.get(0).arrivalSeconds()).isEqualTo(28800);
    assertThat(stops.get(1).arrivalSeconds() - stops.get(0).arrivalSeconds())
        .as("the gap the app projects with").isEqualTo(600);
    assertThat(stops.get(0).stopLat()).isEqualTo(41.1482);
    assertThat(stops).extracting(ResolvedTripStop::timepoint).containsExactly(true, false, true);
  }

  @Test
  void anAfterMidnightTripKeepsItsSecondsPast86400() {
    var stops = trips.tripStops("601_0_1|276|D6|T9|N1");
    assertThat(stops.get(0).arrivalTime()).isEqualTo("24:20:00");
    assertThat(stops.get(0).arrivalSeconds()).isEqualTo(87600);
    assertThat(stops.get(1).arrivalSeconds() - stops.get(0).arrivalSeconds()).isEqualTo(900);
  }

  @Test
  void whenTheIdMissesLineStopAndEtaFindTheBus() {
    // 07:50 Lisbon, a 602 due at Aliados in 15 minutes -> the 08:05 departure.
    var r = trips.findByPattern("602", "AAA1", 15, "Matosinhos", at("2026-08-14T07:50:00+01:00"));
    assertThat(r.match()).isEqualTo("pattern");
    assertThat(r.trip().tripId()).isEqualTo("602_0_1|276|D3|T1|N2");
    assertThat(r.trip().directionId()).isEqualTo(1);
  }

  @Test
  void thePatternFallbackRefusesABusHoursFromTheEtaGiven() {
    // Nothing on 602 leaves Aliados near 18:00; guessing the 08:05 would be
    // worse than admitting the miss.
    assertThat(trips.findByPattern("602", "AAA1", 5, null, at("2026-08-14T18:00:00+01:00"))).isNull();
  }

  @Test
  void thePatternFallbackWillNotCrossToAnotherLine() {
    assertThat(trips.findByPattern("999", "AAA1", 15, null, at("2026-08-14T07:50:00+01:00"))).isNull();
  }

  @Test
  void theFullReadReportsTheLineTheMatchAndTheExpiredFeed() {
    var result = trips.resolvedTripStops("601_0_1|280|D3|T1|N8", Hints.NONE, ON_SERVICE_DAY);
    assertThat(result.requestedTripId()).isEqualTo("601_0_1|280|D3|T1|N8");
    assertThat(result.tripId()).isEqualTo("601_0_1|276|D3|T1|N8");
    assertThat(result.match()).isEqualTo("version");
    assertThat(result.line()).isEqualTo("601");
    assertThat(result.color()).isEqualTo("#187EC2");
    assertThat(result.headsign()).isEqualTo("Aeroporto");
    assertThat(result.stops()).hasSize(2);
    assertThat(result.feedExpired()).as("the 14th is inside the validity window").isFalse();
  }

  @Test
  void anExpiredFeedIsFlaggedNotWithheld() {
    // Stop order survives a reissue; the minute-gaps are what goes stale.
    var result = trips.resolvedTripStops("601_0_1|280|D3|T1|N8", Hints.NONE, PAST_FEED_END);
    assertThat(result.feedExpired()).isTrue();
    assertThat(result.stops()).hasSize(2);
  }

  @Test
  void aDepartureWithNoIdResolvesByPattern() {
    var now = at("2026-08-14T07:50:00+01:00");
    var found = trips.resolvedTripStops(null, new Hints("602", "Matosinhos", "AAA1", 15.0), now);
    assertThat(found.match()).isEqualTo("pattern");
    assertThat(found.tripId()).isEqualTo("602_0_1|276|D3|T1|N2");
    assertThat(found.requestedTripId()).isNull();
    assertThat(found.stops()).isNotEmpty();
    // Still no fabricating: no hints, no answer.
    assertThat(trips.resolvedTripStops(null, Hints.NONE, ON_SERVICE_DAY)).isNull();
  }

  @Test
  void theFullReadFallsThroughToThePatternMatchThenGivesUp() {
    var now = at("2026-08-14T07:50:00+01:00");
    var found = trips.resolvedTripStops("602_0_1|999|NOPE|T0|N0", new Hints("602", "Matosinhos", "AAA1", 15.0), now);
    assertThat(found.match()).isEqualTo("pattern");
    assertThat(found.tripId()).isEqualTo("602_0_1|276|D3|T1|N2");
    // Same unknown id, no hints: null, so the route can 404.
    assertThat(trips.resolvedTripStops("602_0_1|999|NOPE|T0|N0", Hints.NONE, ON_SERVICE_DAY)).isNull();
  }

  @Test
  void aNonFiniteEtaHintIsIgnored() {
    var now = at("2026-08-14T07:50:00+01:00");
    assertThat(trips.resolvedTripStops(null, new Hints("602", null, "AAA1", Double.NaN), now)).isNull();
  }
}
