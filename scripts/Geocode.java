/*
 * Turn an address into coordinates, once, so you can paste them into .env.
 *
 *   java scripts/Geocode.java "Rua de Santa Catarina 100, Porto"
 *   make geocode ADDRESS="Rua de Santa Catarina 100, Porto"
 *
 * Deliberately a setup script and not a runtime endpoint. The board is polled
 * every few seconds by a device on your desk; geocoding on each poll would send a
 * third party your address over and over for an answer that never changes, and
 * would breach Nominatim's usage policy (max ~1 request/second, and they ask that
 * you cache results). Resolve it once, store the numbers, stop calling out.
 *
 * A single-file program on purpose: `java` runs it straight from source, with no
 * build and nothing but the JDK.
 */
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

void main(String[] args) throws Exception {
  String address = String.join(" ", args).trim();
  if (address.isEmpty()) {
    System.err.println("Usage: java scripts/Geocode.java \"<address>\"");
    System.exit(1);
  }

  String url =
      "https://nominatim.openstreetmap.org/search?format=json&limit=5&"
          // Bias towards Portugal: a bare street name matches half of Europe otherwise.
          + "countrycodes=pt&q="
          + URLEncoder.encode(address, StandardCharsets.UTF_8).replace("+", "%20");
  String userAgent = System.getenv().getOrDefault("USER_AGENT", "porto-bus-api/0.2 (personal project)");

  HttpResponse<String> resp =
      HttpClient.newHttpClient()
          .send(
              HttpRequest.newBuilder(URI.create(url)).header("User-Agent", userAgent).header("Accept", "application/json").build(),
              HttpResponse.BodyHandlers.ofString());
  if (resp.statusCode() != 200) {
    System.err.println("Geocoder returned " + resp.statusCode());
    System.exit(1);
  }

  record Match(String name, double lat, double lon) {}
  // Nominatim's results are flat objects; enough structure for a regex, not
  // worth a JSON library for a script run once.
  List<Match> matches = new ArrayList<>();
  Pattern obj = Pattern.compile("\\{[^{}]*?\"lat\":\"([^\"]+)\",\"lon\":\"([^\"]+)\".*?\"display_name\":\"((?:[^\"\\\\]|\\\\.)*)\"");
  Matcher m = obj.matcher(resp.body());
  while (m.find()) {
    matches.add(new Match(m.group(3).replace("\\\"", "\""), Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))));
  }

  if (matches.isEmpty()) {
    System.err.println("No match for \"" + address + "\". Try adding the city or postcode.");
    System.exit(1);
  }

  System.out.println("\nMatches for \"" + address + "\":\n");
  for (int i = 0; i < matches.size(); i++) {
    Match x = matches.get(i);
    // Locale.ROOT: a Portuguese locale would print "41,146457", which .env cannot read.
    System.out.printf(Locale.ROOT, "  %d. %s%n     lat=%.6f  lon=%.6f%n%n", i + 1, x.name(), x.lat(), x.lon());
  }
  Match best = matches.getFirst();
  System.out.println("Add the one you want to .env, e.g.:\n");
  System.out.printf(Locale.ROOT, "  HOME_LAT=%.6f%n  HOME_LON=%.6f%n  HOME_LABEL=HOME%n%n", best.lat(), best.lon());
  System.out.println("Data © OpenStreetMap contributors (ODbL), via Nominatim.\n");
}
