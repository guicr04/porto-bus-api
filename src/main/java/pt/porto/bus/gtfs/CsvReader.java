package pt.porto.bus.gtfs;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A streaming RFC-4180 reader, good enough for GTFS: double-quoted fields,
 * escaped quotes ("") and commas inside quotes.
 *
 * <p>Streaming, not materialised, because of stop_times.txt: 850k rows and 50 MB
 * of text that the ingest consumes once, row by row.
 */
final class CsvReader implements AutoCloseable {

  /** One data row, read by header name. A missing column reads as "". */
  static final class Row {
    private final Map<String, Integer> index;
    private final List<String> values;

    private Row(Map<String, Integer> index, List<String> values) {
      this.index = index;
      this.values = values;
    }

    String get(String column) {
      Integer i = index.get(column);
      return i == null || i >= values.size() ? "" : values.get(i);
    }
  }

  private final Reader in;
  private final char[] buf = new char[1 << 16];
  private int pos;
  private int len;
  private Map<String, Integer> header;

  CsvReader(Reader in) {
    this.in = in;
  }

  /** The next data row, or null at the end. Blank lines are skipped. */
  Row next() {
    if (header == null) {
      List<String> names = readRow();
      if (names == null) return null;
      header = new HashMap<>();
      for (int i = 0; i < names.size(); i++) {
        String name = names.get(i);
        if (i == 0 && name.startsWith("﻿")) name = name.substring(1); // BOM
        header.putIfAbsent(name.trim(), i);
      }
    }
    while (true) {
      List<String> row = readRow();
      if (row == null) return null;
      if (row.size() == 1 && row.getFirst().isEmpty()) continue;
      return new Row(header, row);
    }
  }

  /** Every data row. For the small files only. */
  List<Row> readAll() {
    List<Row> rows = new ArrayList<>();
    for (Row r = next(); r != null; r = next()) rows.add(r);
    return rows;
  }

  private List<String> readRow() {
    if (peek() < 0) return null;
    List<String> row = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    while (true) {
      field.setLength(0);
      if (peek() == '"') {
        read(); // opening quote
        while (peek() >= 0) {
          int ch = read();
          if (ch == '"') {
            if (peek() == '"') {
              field.append('"');
              read();
              continue;
            }
            break; // closing quote
          }
          field.append((char) ch);
        }
      } else {
        int ch;
        while ((ch = peek()) >= 0 && ch != ',' && ch != '\n' && ch != '\r') {
          field.append((char) read());
        }
      }
      row.add(field.toString());

      // Whatever ended the field decides whether the row continues.
      if (peek() == ',') {
        read();
        if (peek() < 0) return row;
        continue;
      }
      while (peek() == '\r') read();
      if (peek() == '\n') read();
      return row;
    }
  }

  private int peek() {
    if (pos == len && !fill()) return -1;
    return buf[pos];
  }

  private int read() {
    if (pos == len && !fill()) return -1;
    return buf[pos++];
  }

  private boolean fill() {
    try {
      len = in.read(buf, 0, buf.length);
      pos = 0;
      if (len <= 0) {
        len = 0;
        return false;
      }
      return true;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void close() throws IOException {
    in.close();
  }
}
