package pt.porto.bus.gtfs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvReaderTest {

  static List<CsvReader.Row> parse(String text) {
    return new CsvReader(new StringReader(text)).readAll();
  }

  @Test
  void readsRowsByHeaderName() {
    var rows = parse("a,b\n1,2\n3,4\n");
    assertThat(rows).hasSize(2);
    assertThat(rows.get(1).get("b")).isEqualTo("4");
    assertThat(rows.get(0).get("missing")).isEmpty();
  }

  @Test
  void handlesQuotesEscapedQuotesAndCommasInsideQuotes() {
    var rows = parse("name,long\nR1,\"CORDOARIA, \"\"CENTRO\"\"\"\n");
    assertThat(rows.getFirst().get("long")).isEqualTo("CORDOARIA, \"CENTRO\"");
  }

  @Test
  void stripsTheBomAndToleratesCrlfBlankLinesAndNoTrailingNewline() {
    var rows = parse("﻿stop_id , name\r\n\r\nX1,One\r\nX2,Two");
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).get("stop_id")).isEqualTo("X1");
    assertThat(rows.get(1).get("name")).isEqualTo("Two");
  }

  @Test
  void aShortRowReadsMissingColumnsAsEmpty() {
    assertThat(parse("a,b,c\n1\n").getFirst().get("c")).isEmpty();
  }

  @Test
  void streamsAcrossBufferBoundaries() {
    var sb = new StringBuilder("id,v\n");
    for (int i = 0; i < 20_000; i++) sb.append(i).append(",\"x,").append(i).append("\"\n");
    var rows = parse(sb.toString());
    assertThat(rows).hasSize(20_000);
    assertThat(rows.get(19_999).get("v")).isEqualTo("x,19999");
  }
}
