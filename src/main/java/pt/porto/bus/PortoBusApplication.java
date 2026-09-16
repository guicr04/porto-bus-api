package pt.porto.bus;

import java.util.Arrays;
import java.util.stream.Stream;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import pt.porto.bus.gtfs.GtfsRefresh;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PortoBusApplication {

  /** Rebuild the static store and exit, instead of serving. See README §2a. */
  static final String INGEST_FLAG = "--ingest";

  public static void main(String[] args) {
    if (Arrays.asList(args).contains(INGEST_FLAG)) {
      System.exit(ingestAndExit(args));
    }
    SpringApplication.run(PortoBusApplication.class, args);
  }

  /**
   * The standalone refresh: no web server, no boot-time refresh of its own, and
   * a non-zero exit code on failure so cron notices.
   */
  private static int ingestAndExit(String[] args) {
    // Command-line arguments, not builder defaults: application.yml's
    // ${BOOT_REFRESH:true} would outrank a default and ingest a second time.
    String[] overrides = {
      "--app.boot-refresh=false",
      "--app.scheduled-refresh=false",
      "--spring.main.banner-mode=off",
      "--logging.level.root=WARN"
    };
    String[] withOverrides = Stream.concat(Arrays.stream(args), Arrays.stream(overrides)).toArray(String[]::new);
    try (ConfigurableApplicationContext ctx =
        new SpringApplicationBuilder(PortoBusApplication.class)
            .web(WebApplicationType.NONE)
            .logStartupInfo(false)
            .run(withOverrides)) {
      return ctx.getBean(GtfsRefresh.class).runStandalone();
    }
  }
}
