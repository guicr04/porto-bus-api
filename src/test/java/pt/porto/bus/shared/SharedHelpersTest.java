package pt.porto.bus.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import pt.porto.bus.model.Stop;

class SharedHelpersTest {

  // CARMO and ALIADOS, two real Porto stops about 500 m apart.
  static final Stop CARMO = new Stop("CMO", "CARMO", 41.147223, -8.616926);
  static final Stop ALIADOS = new Stop("ALD", "ALIADOS", 41.14843, -8.61099);

  static Instant at(String iso) {
    return OffsetDateTime.parse(iso).toInstant();
  }

  @Test
  void gtfsBareHexColoursAreNormalised() {
    assertThat(Colors.toHex("187EC2")).isEqualTo("#187EC2");
    assertThat(Colors.toHex("#000000")).as("not doubled").isEqualTo("#000000");
    assertThat(Colors.toHex("")).isNull();
    assertThat(Colors.toHex(null)).isNull();
  }

  @Test
  void haversineMatchesAKnownDistance() {
    assertThat(Geo.haversineMeters(CARMO.lat(), CARMO.lon(), ALIADOS.lat(), ALIADOS.lon())).isBetween(400.0, 600.0);
  }

  @Test
  void walkingTimeRoundsUpAndAllowsForStreetDetours() {
    // 750 m * 1.35 / 75 m per min = 13.5 -> 14
    assertThat(Geo.walkMinutes(750)).isEqualTo(14);
    assertThat(Geo.walkMinutes(0)).isZero();
  }

  @Test
  void stopsOutsideTheWalkingBudgetAreExcluded() {
    var far = new Stop("FAR", "FAR AWAY", 41.2, -8.7);
    var near = Geo.stopsWithinWalk(List.of(CARMO, ALIADOS, far), CARMO.lat(), CARMO.lon(), 10, Geo.WALK_METERS_PER_MINUTE);
    assertThat(near).extracting(Geo.NearbyStop::stopCode).containsExactly("CMO", "ALD");
    assertThat(near.getFirst().walkMinutes()).as("standing at it").isZero();
  }

  @Test
  void stopsWithNoCoordinatesAreSkippedNotTreatedAsNullIsland() {
    var broken = new Stop("BAD", "NO COORDS", null, null);
    var near = Geo.stopsWithinWalk(List.of(CARMO, broken), CARMO.lat(), CARMO.lon(), 10, Geo.WALK_METERS_PER_MINUTE);
    assertThat(near).extracting(Geo.NearbyStop::stopCode).containsExactly("CMO");
  }

  @Test
  void lineOrderingIsNumericNotAlphabetic() {
    // As strings "1M" sorts between "100" and "200", and "90" after "906".
    var lines = new ArrayList<>(List.of("906", "1M", "200", "90", "10M", "ZC", "1"));
    lines.sort(LineOrder.COMPARATOR);
    assertThat(lines).containsExactly("1", "1M", "10M", "90", "200", "906", "ZC");
  }

  @Test
  void queryEncodingUsesPercent20NeverPlus() {
    // STCP matches service_id verbatim; a "+" for the space breaks the lookup.
    var params = new LinkedHashMap<String, Object>();
    params.put("service_id", "DOM|FERIADO:FLUXO 3.1 20260718");
    params.put("direction_id", 0);
    assertThat(UriEncoding.query(params)).isEqualTo("service_id=DOM%7CFERIADO%3AFLUXO%203.1%2020260718&direction_id=0");
    assertThat(UriEncoding.component("Campanhã (x)*!~'")).isEqualTo("Campanh%C3%A3%20(x)*!~'");
  }

  @Test
  void lisbonClockHelpersHandleAfterMidnight() {
    assertThat(LisbonTime.clockToMinutes("24:35:00")).isEqualTo(1475);
    assertThat(LisbonTime.clockToMinutes("nope")).isNull();
    assertThat(LisbonTime.minutesToClock(1475)).isEqualTo("00:35");
    assertThat(LisbonTime.isoToLisbonClock("2026-07-19T17:43:54Z")).isEqualTo("18:43");
    assertThat(LisbonTime.isoToLisbonClock("garbage")).isNull();
  }

  @Test
  void theServiceDateIsLisbonsNotUtcs() {
    // 23:30 UTC on 14 Aug is already the 15th in Lisbon (UTC+1 in summer).
    assertThat(LisbonTime.dateStamp(Instant.parse("2026-08-14T23:30:00Z"))).isEqualTo("20260815");
    assertThat(LisbonTime.dateStamp(Instant.parse("2026-08-14T23:30:00Z"), -1)).isEqualTo("20260814");
    assertThat(LisbonTime.nowMinutes(at("2026-08-15T00:10:00+01:00"))).isEqualTo(10);
  }

  @Test
  void timestampsKeepTheirMillisecondFormat() {
    assertThat(LisbonTime.isoTimestamp(Instant.parse("2026-08-14T23:30:00Z"))).isEqualTo("2026-08-14T23:30:00.000Z");
  }

  @Test
  void wholeNumbersSerialiseAsIntegers() {
    assertThat(Numbers.compact(4.0)).isEqualTo(4L);
    assertThat(Numbers.compact(-2.5)).isEqualTo(-2.5);
    assertThat(Numbers.parse("abc", 7.0)).isEqualTo(7.0);
    assertThat(Numbers.parseBool("YES", false)).isTrue();
    assertThat(Numbers.parseBool("", true)).isTrue();
  }
}
