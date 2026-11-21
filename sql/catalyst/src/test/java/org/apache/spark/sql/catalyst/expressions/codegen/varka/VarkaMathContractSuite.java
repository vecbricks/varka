/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.catalyst.expressions.codegen.varka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMathBinding.Operator;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMathContract.Citation;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMathContract.Entry;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMathContract.MeasuredLine;

/**
 * The contract table against the measured lines ({@code VARKA-345.md} 2.5, 3.4): every committed
 * {@code sql/varka/plans/m8/results/VARKA-367-<host>.tsv} parses in the format
 * {@link MeasuredLine} fixes, and {@link VarkaMathContract#ENTRIES} has no finding against them -
 * no line above its entry's contracted value, no entry citing no line. Both are empty until row
 * 367 commits its first host, so the rules are also proved here on a planted table.
 */
@VarkaSuite
public final class VarkaMathContractSuite {

  private static final Pattern FILE = Pattern.compile("VARKA-367-(.+)\\.tsv");

  private static final String JDK = "25.0.4+1-LTS";
  private static final String CPU = "AMD EPYC 7763 64-Core Processor";

  /** Every committed measured line, each file's header checked. */
  private static List<MeasuredLine> committedLines() throws IOException {
    String home = System.getProperty("spark.test.home", System.getenv("SPARK_HOME"));
    assertTrue(home != null, "spark.test.home or SPARK_HOME is not set.");
    Path results = Path.of(home, "sql", "varka", "plans", "m8", "results");
    var lines = new ArrayList<MeasuredLine>();
    try (Stream<Path> files = Files.list(results)) {
      for (Path f : files.sorted().toList()) {
        Matcher m = FILE.matcher(f.getFileName().toString());
        if (!m.matches()) {
          continue;
        }
        List<String> text = Files.readAllLines(f, StandardCharsets.UTF_8);
        assertTrue(!text.isEmpty() && text.get(0).equals(MeasuredLine.HEADER),
            f + " does not open with the header " + MeasuredLine.HEADER);
        for (String line : text.subList(1, text.size())) {
          lines.add(MeasuredLine.parse(m.group(1), line));
        }
      }
    }
    return lines;
  }

  private static MeasuredLine line(String host, long steps) {
    String witness = steps == 0 ? "-" : "0x3ff0000000000000";
    return MeasuredLine.parse(host, String.join("\t", "sin", "SVML", "__jsvml_sin4_ha_l9", "256",
        JDK, CPU, "16777216", steps == 0 ? "0" : "130", String.valueOf(steps), witness, witness,
        witness));
  }

  private static Entry entry(int steps, Citation... lines) {
    return new Entry(Operator.SIN, "__jsvml_sin4_ha_l9", JDK, steps, List.of(lines));
  }

  @Test
  @DisplayName("every committed measured line parses, and the contract table has no finding")
  void committedContractHolds() throws IOException {
    assertEquals(List.of(), VarkaMathContract.findings(VarkaMathContract.ENTRIES,
        committedLines()));
  }

  @Test
  @DisplayName("a measured line above its entry's contracted value fails, one at or below passes")
  void lineAboveTheContractFails() {
    var cited = new Citation("epyc", CPU);
    assertEquals(List.of(), VarkaMathContract.findings(List.of(entry(1, cited)),
        List.of(line("epyc", 1), line("laptop", 0))));
    List<String> found = VarkaMathContract.findings(List.of(entry(1, cited)),
        List.of(line("epyc", 1), line("laptop", 2)));
    assertEquals(1, found.size(), found.toString());
    assertTrue(found.get(0).contains("laptop") && found.get(0).contains("measured 2 steps"),
        found.get(0));
    // A refused pair is a line no contracted value covers.
    assertEquals(1, VarkaMathContract.findings(List.of(entry(Integer.MAX_VALUE, cited)),
        List.of(line("epyc", 1), line("arm", VarkaMathBound.REFUSED))).size());
    // A line of another JDK build is another key, which this entry does not cover.
    String other = String.join("\t", "sin", "SVML", "__jsvml_sin4_ha_l9", "256", "25.0.5+1",
        CPU, "16777216", "1", "9", "0x3ff0000000000000", "0x3ff0000000000001",
        "0x3ff0000000000000");
    assertEquals(List.of(), VarkaMathContract.findings(List.of(entry(1, cited)),
        List.of(line("epyc", 1), MeasuredLine.parse("epyc", other))));
  }

  @Test
  @DisplayName("an entry that cites no line, or a line nobody committed, fails")
  void entryMustCiteACommittedLine() {
    assertEquals(List.of("SIN on __jsvml_sin4_ha_l9, JDK " + JDK + ": cites no measured line"),
        VarkaMathContract.findings(List.of(entry(1)), List.of(line("epyc", 1))));
    List<String> found = VarkaMathContract.findings(
        List.of(entry(1, new Citation("arm", CPU))), List.of(line("epyc", 1)));
    assertEquals(1, found.size(), found.toString());
    assertTrue(found.get(0).contains("which no committed line answers"), found.get(0));
  }

  @Test
  @DisplayName("a malformed measured line is refused when it is read")
  void malformedLinesAreRefused() {
    String good = String.join("\t", "sin", "SVML", "__jsvml_sin4_ha_l9", "256", JDK, CPU,
        "16777216", "130", "1", "0x3ff0000000000000", "0x3ff0000000000001",
        "0x3ff0000000000000");
    assertEquals(1, MeasuredLine.parse("epyc", good).largestSteps());
    for (String bad : List.of(
        good.replace("\t130\t", "\t0\t"),
        good.replace("sin\t", "sine\t"),
        good.replace("SVML", "NONE"),
        good.replace("0x3ff0000000000001", "1.0000000000000002"),
        good.replace("\t1\t0x", "\t-1\t0x"),
        good + "\textra",
        good.replace("\t130\t", "\t16777217\t"),
        // Another operator's symbol, or another width's, is not this line's.
        good.replace("__jsvml_sin4", "__jsvml_cos4"),
        good.replace("\t256\t", "\t512\t"),
        good.replace("_l9", "_z0"))) {
      assertThrows(IllegalArgumentException.class, () -> MeasuredLine.parse("epyc", bad), bad);
    }
    // A binary operator's input is the pair, and no other witness holds two words.
    String pow = String.join("\t", "pow", "SVML", "__jsvml_pow8_ha_z0", "512", JDK, CPU,
        "16777216", "3", "2", "0x3ff0000000000000,0x4000000000000000", "0x3ff0000000000000",
        "0x3ff0000000000002");
    assertEquals(Operator.POW, MeasuredLine.parse("laptop", pow).function());
    assertThrows(IllegalArgumentException.class, () -> MeasuredLine.parse("laptop",
        pow.replace("\t0x3ff0000000000000\t", "\t0x3ff0000000000000,0x0\t")));
    assertThrows(IllegalArgumentException.class, () -> MeasuredLine.parse("laptop",
        pow.replace("0x3ff0000000000000,0x4000000000000000", "0x3ff0000000000000")));
    assertThrows(IllegalArgumentException.class, () -> MeasuredLine.parse("epyc",
        good.replace("0x3ff0000000000000\t0x3ff0000000000001",
            "0x3ff0000000000000,0x3ff0000000000000\t0x3ff0000000000001")));
    // A SLEEF symbol under SVML, or the reverse, is refused.
    assertThrows(IllegalArgumentException.class, () -> MeasuredLine.parse("epyc",
        good.replace("__jsvml_sin4_ha_l9", "sind2_u10advsimd")));
    // SLEEF's lane count is 128 bits' alone; above it the scalable name.
    assertThrows(IllegalArgumentException.class, () -> MeasuredLine.parse("arm", good.replace(
        "SVML\t__jsvml_sin4_ha_l9", "SLEEF\tsind4_u10sve")));
    // Lanes that differ only in a NaN's payload are 0 steps apart: a line may count them.
    String payloads = String.join("\t", "sin", "SLEEF", "sind2_u10advsimd", "128", JDK, CPU,
        "16777216", "2", "0", "0x7ff0000000000000", "0xfff8000000000000", "0x7ff8000000000000");
    assertEquals(0, MeasuredLine.parse("arm", payloads).largestSteps());
  }

  @Test
  @DisplayName("two entries of one function, symbol and JDK build fail")
  void entryKeysAreUnique() {
    var cited = new Citation("epyc", CPU);
    List<String> found = VarkaMathContract.findings(List.of(entry(1, cited), entry(4, cited)),
        List.of(line("epyc", 1)));
    assertEquals(List.of("SIN on __jsvml_sin4_ha_l9, JDK " + JDK + ": entered twice"), found);
  }
}
