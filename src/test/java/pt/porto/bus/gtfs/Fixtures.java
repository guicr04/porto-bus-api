package pt.porto.bus.gtfs;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteDataSource;
import pt.porto.bus.shared.AppProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * Synthesised GTFS feeds and a store to load them into. Not the real zip: the
 * point is the contract (what is rejected, what is dropped, how after-midnight
 * resolves), and the ids need to be adversarial in ways Porto's feed happens not
 * to be on any given day.
 */
public final class Fixtures {
  private Fixtures() {}

  public static AppProperties props(Path db) {
    return new AppProperties(
        null, "http://127.0.0.1:9", "dataset", 86_400, db.toString(), "http://127.0.0.1:9/api",
        null, null, "HOME", 0, "test", 2_000, false, false);
  }

  /** A store with the schema applied, plus the pieces that read and write it. */
  public record Store(DataSource dataSource, JdbcClient jdbc, GtfsIngest ingest, GtfsStore store,
      ScheduleStore schedule, LineStore lines, TripResolver trips) {

    public Map<String, Object> load(Map<String, String> files) {
      return ingest.load(zip(files), new GtfsIngest.FeedSource("test://feed", "test feed"));
    }
  }

  public static Store store(Path dir, Clock clock) {
    Path db = dir.resolve("test.db");
    SQLiteDataSource ds = new SQLiteDataSource();
    ds.setUrl("jdbc:sqlite:" + db);
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
    JdbcClient jdbc = JdbcClient.create(ds);
    AppProperties props = props(db);
    GtfsStore store = new GtfsStore(jdbc, props, clock);
    return new Store(ds, jdbc, new GtfsIngest(ds, props, JsonMapper.builder().build(), clock), store,
        new ScheduleStore(jdbc), new LineStore(jdbc), new TripResolver(jdbc, store, clock));
  }

  /** Write files into a zip in a temp directory. */
  public static Path zip(Map<String, String> files) {
    try {
      Path zip = Files.createTempFile("feed-", ".zip");
      try (OutputStream out = Files.newOutputStream(zip);
          ZipOutputStream z = new ZipOutputStream(out)) {
        for (var e : files.entrySet()) {
          z.putNextEntry(new ZipEntry(e.getKey()));
          z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
          z.closeEntry();
        }
      }
      return zip;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A minimal but structurally real feed: two stops, one line, an after-midnight trip. */
  public static Map<String, String> basicFeed() {
    Map<String, String> f = new LinkedHashMap<>();
    f.put("agency.txt", "agency_id,agency_name\nSTCP,Test\n");
    f.put("calendar.txt", "service_id,monday,start_date,end_date\n");
    f.put("calendar_dates.txt", "service_id,date,exception_type\nSVC1,20260815,1\nSVC0,20260814,1\n");
    f.put("feed_info.txt",
        "feed_publisher_name,feed_version,feed_start_date,feed_end_date\nSTCP,v1,20260729,20260815\n");
    f.put("routes.txt",
        "route_id,route_short_name,route_long_name,route_color,route_text_color,route_type,route_sort_order\n"
            + "R1,500,\"CORDOARIA - MATOSINHOS\",187EC2,FFFFFF,3,10\n");
    f.put("stops.txt",
        "stop_lat,stop_lon,stop_id,stop_code,stop_name,zone_id\n"
            + "41.1482,-8.6108,AAA1,AAA1,AV. ALIADOS,PRT1\n"
            + "41.1500,-8.6200,BBB2,BBB2,CARMO,PRT1\n"
            + "41.2541,-8.6537,.,.,.,MAI2\n"); // the junk row the real feed carries
    f.put("trips.txt",
        "route_id,service_id,trip_id,trip_headsign,direction_id,shape_id,block_id\n"
            + "R1,SVC1,T1,Matosinhos,0,SH1,B1\n"
            + "R1,SVC1,T2,Matosinhos,0,SH1,B1\n"
            + "R1,SVC0,T3,Matosinhos,0,SH1,B1\n");
    f.put("stop_times.txt",
        "trip_id,arrival_time,departure_time,stop_id,stop_sequence,timepoint,shape_dist_traveled\n"
            + "T1,08:00:00,08:00:00,AAA1,1,1,0.0\n"
            + "T1,08:10:00,08:10:00,BBB2,2,0,1200.0\n"
            + "T2,09:00:00,09:00:00,AAA1,1,1,0.0\n"
            // an after-midnight trip belonging to the PREVIOUS service day
            + "T3,24:20:00,24:20:00,AAA1,1,1,0.0\n");
    f.put("shapes.txt",
        "shape_pt_lat,shape_pt_lon,shape_dist_traveled,shape_id,shape_pt_sequence\n"
            + "41.1482,-8.6108,0.0,SH1,1\n"
            + "41.1500,-8.6200,1200.0,SH1,2\n");
    return f;
  }

  /**
   * Trip ids built to break a naive join: the same trip under two feed versions,
   * an id differing from another only where LIKE treats `_` as a wildcard, and a
   * trip running past midnight.
   */
  public static Map<String, String> tripsFeed() {
    Map<String, String> f = new LinkedHashMap<>();
    f.put("agency.txt", "agency_id,agency_name\nSTCP,Test\n");
    f.put("calendar.txt", "service_id,monday,start_date,end_date\n");
    // Two weekday services a long way apart, so "today's service" can separate
    // the colliding pair, plus a Saturday nobody's tests land on.
    f.put("calendar_dates.txt",
        "service_id,date,exception_type\nSVC_A,20260810,1\nSVC_B,20260814,1\nSVC_SAT,20260815,1\n");
    f.put("feed_info.txt",
        "feed_publisher_name,feed_version,feed_start_date,feed_end_date\nSTCP,v276,20260801,20260815\n");
    f.put("routes.txt",
        "route_id,route_short_name,route_long_name,route_color,route_text_color,route_type,route_sort_order\n"
            + "R1,601,\"ALIADOS - AEROPORTO\",187EC2,FFFFFF,3,10\n"
            + "R2,602,\"ALIADOS - MATOSINHOS\",EC8031,FFFFFF,3,20\n");
    f.put("stops.txt",
        "stop_lat,stop_lon,stop_id,stop_code,stop_name,zone_id\n"
            + "41.1482,-8.6108,AAA1,AAA1,AV. ALIADOS,PRT1\n"
            + "41.1500,-8.6200,BBB2,BBB2,CARMO,PRT1\n"
            + "41.1600,-8.6300,CCC3,CCC3,BOAVISTA,PRT1\n");
    f.put("trips.txt",
        "route_id,service_id,trip_id,trip_headsign,direction_id,shape_id,block_id\n"
            // The colliding pair: the same trip reissued under a newer feed version.
            + "R1,SVC_A,601_0_1|274|D3|T1|N6,Aeroporto,0,SH1,B1\n"
            + "R1,SVC_B,601_0_1|276|D3|T1|N6,Aeroporto,0,SH1,B1\n"
            // Unique once the version is stripped.
            + "R1,SVC_B,601_0_1|276|D3|T1|N8,Aeroporto,0,SH1,B1\n"
            // Differs from the N6 pair only where LIKE would treat `_` as a wildcard.
            + "R1,SVC_B,601X0Y1|276|D3|T1|N6,Decoy,0,SH1,B1\n"
            // Never named by id in these tests — only found by line + headsign.
            + "R2,SVC_B,602_0_1|276|D3|T1|N2,Matosinhos,1,SH1,B2\n"
            // Runs past midnight, filed under its own service day.
            + "R1,SVC_B,601_0_1|276|D6|T9|N1,Aeroporto,0,SH1,B3\n");
    f.put("stop_times.txt",
        "trip_id,arrival_time,departure_time,stop_id,stop_sequence,timepoint,shape_dist_traveled\n"
            + "601_0_1|274|D3|T1|N6,08:00:00,08:00:00,AAA1,1,1,0.0\n"
            + "601_0_1|274|D3|T1|N6,08:10:00,08:10:00,BBB2,2,0,1200.0\n"
            + "601_0_1|276|D3|T1|N6,08:00:00,08:00:00,AAA1,1,1,0.0\n"
            + "601_0_1|276|D3|T1|N6,08:10:00,08:10:00,BBB2,2,0,1200.0\n"
            + "601_0_1|276|D3|T1|N6,08:25:00,08:25:00,CCC3,3,1,2400.0\n"
            + "601_0_1|276|D3|T1|N8,09:00:00,09:00:00,AAA1,1,1,0.0\n"
            + "601_0_1|276|D3|T1|N8,09:12:00,09:12:00,BBB2,2,0,1200.0\n"
            + "601X0Y1|276|D3|T1|N6,07:00:00,07:00:00,AAA1,1,1,0.0\n"
            + "602_0_1|276|D3|T1|N2,08:05:00,08:05:00,AAA1,1,1,0.0\n"
            + "602_0_1|276|D3|T1|N2,08:20:00,08:20:00,BBB2,2,0,1200.0\n"
            + "601_0_1|276|D6|T9|N1,24:20:00,24:20:00,AAA1,1,1,0.0\n"
            + "601_0_1|276|D6|T9|N1,24:35:00,24:35:00,BBB2,2,0,1200.0\n");
    f.put("shapes.txt",
        "shape_pt_lat,shape_pt_lon,shape_dist_traveled,shape_id,shape_pt_sequence\n"
            + "41.1482,-8.6108,0.0,SH1,1\n"
            + "41.1500,-8.6200,1200.0,SH1,2\n");
    return f;
  }
}
