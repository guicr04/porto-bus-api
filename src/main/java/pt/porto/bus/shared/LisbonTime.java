package pt.porto.bus.shared;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time helpers. STCP clock times are Europe/Lisbon wall-clock, and can exceed
 * 24:00 for after-midnight trips ("24:35:00" is 00:35 the next day).
 *
 * <p>Everything takes an explicit {@link Instant} rather than reading the system
 * clock, so the after-midnight cases are testable.
 */
public final class LisbonTime {

  public static final ZoneId LISBON = ZoneId.of("Europe/Lisbon");

  private static final Pattern CLOCK = Pattern.compile("^(\\d{1,2}):(\\d{2})");
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd");
  private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
  private static final DateTimeFormatter ISO_MILLIS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private LisbonTime() {}

  /** Current wall-clock time in Lisbon as minutes since midnight. DST-safe. */
  public static int nowMinutes(Instant now) {
    ZonedDateTime t = now.atZone(LISBON);
    return t.getHour() * 60 + t.getMinute();
  }

  /** "HH:MM:SS" or "HH:MM" to minutes since midnight. Hours may be 24 or more. */
  public static Integer clockToMinutes(String clock) {
    if (clock == null) return null;
    Matcher m = CLOCK.matcher(clock);
    if (!m.find()) return null;
    return Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
  }

  /** Minutes since midnight to "HH:MM", wrapping hours into 0-23 (1475 is "00:35"). */
  public static String minutesToClock(int minutes) {
    int h = Math.floorDiv(minutes, 60) % 24;
    int m = Math.floorMod(minutes, 60);
    return "%02d:%02d".formatted(h, m);
  }

  /** An ISO timestamp as "HH:MM" in Lisbon, or null when it does not parse. */
  public static String isoToLisbonClock(String iso) {
    if (iso == null || iso.isEmpty()) return null;
    try {
      return OffsetDateTime.parse(iso).atZoneSameInstant(LISBON).format(HH_MM);
    } catch (DateTimeParseException ignored) {
      // fall through: no offset, which a JS Date reads as local wall-clock time
    }
    try {
      return LocalDateTime.parse(iso).format(HH_MM);
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }

  /**
   * A date in Lisbon as GTFS writes it, "YYYYMMDD".
   *
   * <p>Must be Lisbon rather than the host's zone: the store is keyed by service
   * date, and a server running in UTC would flip days an hour early in summer,
   * quietly serving tomorrow's timetable.
   *
   * @param dayOffset e.g. -1 for yesterday
   */
  public static String dateStamp(Instant now, int dayOffset) {
    return now.plusSeconds(dayOffset * 86_400L).atZone(LISBON).format(STAMP);
  }

  public static String dateStamp(Instant now) {
    return dateStamp(now, 0);
  }

  /** Today in Lisbon as "YYYY-MM-DD", the format upstream's `date` param takes. */
  public static String isoDate(Instant now) {
    return now.atZone(LISBON).toLocalDate().toString();
  }

  /** The timestamp format every `generated_at` in this API has always used. */
  public static String isoTimestamp(Instant now) {
    return ISO_MILLIS.format(now);
  }
}
