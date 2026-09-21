package pt.porto.bus.gtfs;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Nullable column reads: JDBC's primitive getters turn NULL into 0. */
final class Rows {
  private Rows() {}

  static Double dbl(ResultSet rs, String col) throws SQLException {
    double v = rs.getDouble(col);
    return rs.wasNull() ? null : v;
  }

  static Integer integer(ResultSet rs, String col) throws SQLException {
    int v = rs.getInt(col);
    return rs.wasNull() ? null : v;
  }
}
