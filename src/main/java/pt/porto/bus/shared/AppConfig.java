package pt.porto.bus.shared;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.UrlHandlerFilter;

@Configuration(proxyBeanMethods = false)
public class AppConfig {

  /** Injected everywhere "now" matters, so after-midnight and expiry are testable. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * For fanning out upstream calls (the board polls a dozen stops at once). One
   * virtual thread per call: they spend their whole life waiting on the network.
   */
  @Bean(destroyMethod = "close")
  ExecutorService fanOutExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  /** Express matched `/stops/` the same as `/stops`; keep that true. */
  @Bean
  UrlHandlerFilter trailingSlashFilter() {
    return UrlHandlerFilter.trailingSlashHandler("/**").wrapRequest().build();
  }
}
