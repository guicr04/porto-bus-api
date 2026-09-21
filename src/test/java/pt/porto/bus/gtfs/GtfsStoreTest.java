package pt.porto.bus.gtfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pt.porto.bus.model.RouteDirectionStops;
import pt.porto.bus.shared.ApiException;
import pt.porto.bus.shared.BBox;
import pt.porto.bus.shared.UpstreamException;

/** The static store: ingest guarantees, and the scheduled reads built on them. */
class GtfsStoreTest {

  @TempDir static Path dir;
  static Fixtures.Store s;

  static Instant at(String iso) {
    return OffsetDateTime.parse(iso).toInstant();
  }

  @BeforeAll
  static void load() {
    s = Fixtures.store(dir, Clock.fixed(at("2026-08-15T12:00:00+01:00"), ZoneOffset.UTC));
    s.load(Fixtures.basicFeed());
  }

  @Test
  void toSecondsHandlesAfterMidnightHoursWithoutWrapping() {
    assertThat(GtfsIngest.toSeconds("00:00:00")).isZero();
    assertThat(GtfsIngest.toSeconds("08:30:00")).isEqualTo(30600);
    assertThat(GtfsIngest.toSeconds("24:39:00")).isEqualTo(88740); // must not wrap to 2340
    assertThat(GtfsIngest.toSeconds("nonsense")).isNull();
  }

  @Test
  void ingestLoadsTheFeedAndRecordsItsIdentity() {
    assertThat(s.store().hasData()).isTrue();
    FeedMeta meta = s.store().feedMeta();
    assertThat(meta.resourceName()).isEqualTo("test feed");
    assertThat(meta.feedStartDate()).isEqualTo("20260729");
    assertThat(meta.feedEndDate()).isEqualTo("20260815");
    assertThat(Instant.parse(meta.ingestedAt())).isNotNull();
  }

  @Test
  void ingestDropsThePlaceholderDotStopRatherThanServingIt() {
    assertThat(s.store().stops()).hasSize(2).noneMatch(st -> st.stopCode().equals("."));
  }

  @Test
  void ingestNormalisesBareGtfsColours() {
    var line = s.store().lines().getFirst();
    assertThat(line.color()).isEqualTo("#187EC2");
    assertThat(line.line()).isEqualTo("500");
  }

  @Test
  void ingestRefusesAFeedWhoseStopIdAndStopCodeDiverge() {
    var feed = Fixtures.basicFeed();
    feed.put("stops.txt", "stop_lat,stop_lon,stop_id,stop_code,stop_name,zone_id\n41.1,-8.6,INTERNAL9,AAA1,AV. ALIADOS,PRT1\n");
    assertThatThrownBy(() -> s.load(feed)).hasMessageContaining("stop_code");
  }

  @Test
  void ingestRefusesAFeedThatPopulatesCalendarTxt() {
    var feed = Fixtures.basicFeed();
    feed.put("calendar.txt",
        "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
            + "SVC1,1,1,1,1,1,0,0,20260729,20260815\n");
    assertThatThrownBy(() -> s.load(feed)).hasMessageContaining("calendar_dates");
  }

  @Test
  void ingestRefusesAFeedMissingAFile() {
    var feed = Fixtures.basicFeed();
    feed.remove("shapes.txt");
    assertThatThrownBy(() -> s.load(feed)).hasMessageContaining("feed is missing shapes.txt");
    assertThat(s.store().stops()).hasSize(2);
  }

  @Test
  void aRejectedIngestLeavesThePreviousStoreIntact() {
    var bad = Fixtures.basicFeed();
    bad.put("calendar.txt", "service_id\nX\n");
    assertThatThrownBy(() -> s.load(bad)).isInstanceOf(IllegalStateException.class);
    assertThat(s.store().stops()).as("the good feed should still be there").hasSize(2);
    assertThat(s.store().feedMeta().resourceName()).isEqualTo("test feed");
  }

  @Test
  void bboxReadsOnlyTheStopsInsideTheBox() {
    assertThat(s.store().stopsInBBox(new BBox(41.14, -8.63, 41.16, -8.6), 5000)).hasSize(2);
    assertThat(s.store().stopsInBBox(new BBox(40, -9, 40.5, -8.9), 5000)).isEmpty();
  }

  @Test
  void bboxParsingRejectsMalformedAndInvertedBoxes() {
    assertThat(BBox.parse(null)).isNull();
    assertThat(BBox.parse("-8.63,41.14,-8.6,41.16")).isEqualTo(new BBox(41.14, -8.63, 41.16, -8.6));
    assertThatThrownBy(() -> BBox.parse("1,2,3")).isInstanceOf(ApiException.class).hasMessageContaining("four numbers");
    assertThatThrownBy(() -> BBox.parse("5,5,1,1")).hasMessageContaining("inverted");
  }

  @Test
  void searchIsCaseInsensitive() {
    assertThat(s.store().searchStops("carmo", 10)).extracting(st -> st.stopCode()).containsExactly("BBB2");
  }

  @Test
  void scheduledDeparturesComeFromTheDaysActiveService() {
    assertThat(s.store().activeServiceIds("20260815")).containsExactly("SVC1");
    var rows = s.schedule().departures("AAA1", 120, 20, null, at("2026-08-15T07:30:00+01:00"));
    assertThat(rows).extracting(ScheduleStore.ScheduledDeparture::clock).containsExactly("08:00", "09:00");
    assertThat(rows.getFirst().minutes()).isEqualTo(30);
    assertThat(rows.getFirst().line()).isEqualTo("500");
  }

  @Test
  void aTripPastMidnightIsFoundUnderThePreviousServiceDay() {
    // 00:10 on the 15th: the 24:20 departure belongs to the 14th's service.
    var rows = s.schedule().departures("AAA1", 60, 20, null, at("2026-08-15T00:10:00+01:00"));
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().clock()).isEqualTo("24:20");
    assertThat(rows.getFirst().minutes()).isEqualTo(10);
  }

  @Test
  void theScheduledBoardNeverClaimsABusIsOnTime() {
    var board = s.schedule().stopBoard("AAA1", at("2026-08-15T07:30:00+01:00"));
    assertThat(board.dataSource()).isEqualTo("scheduled");
    assertThat(board.stopName()).isEqualTo("AV. ALIADOS");
    assertThat(board.arrivals()).isNotEmpty().allSatisfy(a -> {
      assertThat(a.status()).as("status implies tracking the timetable cannot provide").isNull();
      assertThat(a.delayMinutes()).isNull();
      assertThat(a.estimatedArrivalTime()).isNull();
    });
  }

  @Test
  void lineReadsPickTheLongestTripForTheDirection() {
    RouteDirectionStops stops = s.lines().lineStops("500", 0);
    assertThat(stops.stops()).as("T1 covers more stops than T2").hasSize(2);
    assertThat(stops.timepointStopIds()).containsExactly("AAA1");
    assertThat(s.lines().lineShape("500", 0).coordinates()).hasSize(2);
    assertThat(s.lines().lineStops("500", 1)).as("no trips in that direction").isNull();
    assertThat(s.lines().lineShape("NOPE", 0)).isNull();
  }

  @Test
  void stopToRoutesIsDerivedAtIngestAndDedupedAcrossTrips() {
    // T1 and T2 both serve AAA1 on route R1: the pair must appear once, not twice.
    var lines = s.store().stopLinesInBBox(new BBox(41.14, -8.63, 41.16, -8.6));
    var aliados = lines.stream().filter(l -> l.stopCode().equals("AAA1")).findFirst().orElseThrow();
    assertThat(aliados.lines()).extracting(l -> l.line()).containsExactly("500");
    assertThat(aliados.lines().getFirst().color()).as("carries the colour for the badge").isEqualTo("#187EC2");
    var carmo = lines.stream().filter(l -> l.stopCode().equals("BBB2")).findFirst().orElseThrow();
    assertThat(carmo.lines()).extracting(l -> l.line()).containsExactly("500");
  }

  @Test
  void stopToRoutesCoversOnlyStopsInsideTheBox() {
    assertThat(s.store().stopLinesInBBox(new BBox(40, -9, 40.5, -8.9))).isEmpty();
  }

  @Test
  void anExpiredFeedDisqualifiesTheStoreFromStandingIn() {
    assertThat(s.store().isFeedExpired(at("2026-08-15T12:00:00+01:00"))).as("last valid day").isFalse();
    assertThat(s.store().isFeedExpired(at("2026-08-16T12:00:00+01:00"))).isTrue();
  }

  @Test
  void expiryIsJudgedByTheLisbonDateNotUtc() {
    // 00:30 on the 16th in Lisbon is still the 15th in UTC.
    assertThat(s.store().isFeedExpired(at("2026-08-16T00:30:00+01:00"))).isTrue();
  }

  @Test
  void onlyUpstreamOutagesFallBackAndA404Propagates() {
    assertThat(UpstreamException.isOutage(new UpstreamException(null, "timeout"))).isTrue();
    assertThat(UpstreamException.isOutage(new RuntimeException("anything unclassified"))).isTrue();
    assertThat(UpstreamException.isOutage(new UpstreamException(503, "x"))).isTrue();
    assertThat(UpstreamException.isOutage(new UpstreamException(429, "x"))).isTrue();
    assertThat(UpstreamException.isOutage(new UpstreamException(404, "x")))
        .as("never fabricate a board for a missing stop").isFalse();
    assertThat(UpstreamException.isOutage(new UpstreamException(400, "x"))).isFalse();
  }

  @Test
  void ingestReportsRowCounts() {
    Map<String, Object> counts = s.load(Fixtures.basicFeed());
    assertThat(counts)
        .containsEntry("stops", 2)
        .containsEntry("stops_dropped", 1)
        .containsEntry("trips", 3)
        .containsEntry("stop_times", 4)
        .containsEntry("stop_routes", 2L)
        .containsEntry("feed_end_date", "20260815");
  }
}
