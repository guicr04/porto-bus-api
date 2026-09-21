package pt.porto.bus.shared;

import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Line names in the order a rider reads them.
 *
 * <p>A plain string sort puts "1M" between "100" and "200". STCP mixes plain
 * numbers ("300"), numbers with a suffix ("1M", "10M") and letters ("ZC"), so
 * compare the leading number first and fall back to text.
 */
public final class LineOrder {

  private static final Pattern LEADING_NUMBER = Pattern.compile("^\\d+");
  private static final Collator TEXT = Collator.getInstance(Locale.ROOT);

  public static final Comparator<String> COMPARATOR = LineOrder::compare;

  private LineOrder() {}

  public static int compare(String a, String b) {
    Long na = leadingNumber(a);
    Long nb = leadingNumber(b);
    // Same number, different suffix: "1" before "1M".
    if (na != null && nb != null) {
      int byNumber = Long.compare(na, nb);
      return byNumber != 0 ? byNumber : TEXT.compare(String.valueOf(a), String.valueOf(b));
    }
    // Numbered lines before lettered ones.
    if (na != null) return -1;
    if (nb != null) return 1;
    return TEXT.compare(String.valueOf(a), String.valueOf(b));
  }

  private static Long leadingNumber(String s) {
    Matcher m = LEADING_NUMBER.matcher(String.valueOf(s));
    if (!m.find()) return null;
    try {
      return Long.parseLong(m.group());
    } catch (NumberFormatException tooLong) {
      return Long.MAX_VALUE;
    }
  }
}
