package pt.porto.bus.gtfs;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pt.porto.bus.shared.AppProperties;

/**
 * The static store: one SQLite file (README §2a).
 *
 * <p>WAL matters for more than speed: it lets the daily ingest hold a write
 * transaction over ~850k rows without blocking a single reader, which is what
 * makes "replace everything in one transaction" safe against a live API.
 * synchronous=NORMAL rather than FULL because the store is a derived artifact:
 * losing the last fsync to a power cut costs a re-ingest, not data.
 */
@Configuration(proxyBeanMethods = false)
class DataSourceConfig {

  @Bean(destroyMethod = "close")
  DataSource dataSource(AppProperties props) {
    Path db = Path.of(props.dbPath()).toAbsolutePath();
    try {
      Files.createDirectories(db.getParent());
    } catch (IOException e) {
      throw new UncheckedIOException("cannot create the store's directory " + db.getParent(), e);
    }
    HikariConfig config = new HikariConfig();
    config.setPoolName("gtfs-store");
    config.setJdbcUrl(
        "jdbc:sqlite:" + db + "?journal_mode=WAL&synchronous=NORMAL&foreign_keys=true&busy_timeout=10000");
    config.setMaximumPoolSize(8);
    return new HikariDataSource(config);
  }
}
