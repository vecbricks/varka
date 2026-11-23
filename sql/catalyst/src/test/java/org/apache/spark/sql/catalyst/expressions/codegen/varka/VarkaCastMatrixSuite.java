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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Cast$;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.sql.internal.SQLConf$;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;

/**
 * Milestone 8's cast matrix ({@code sql/varka/plans/m8/casts.tsv}, written by
 * {@code dev/varka_census.py}) held to Spark's own legality rules (VARKA-328). The script
 * transcribes {@code Cast.canCast} and {@code Cast.canAnsiCast} by hand into its {@code legal()},
 * so a wrong transcription would stay consistent with itself; this suite asks {@code Cast} about
 * every ordered pair of the census's thirteen types instead, under each of the three modes, and
 * requires the file's columns to agree and a pair the file leaves out to be illegal in all three.
 *
 * <p>It also asks Varka's compiler which casts it fuses (VARKA-486): every legal cast between two
 * forms, in each mode, its answer recorded in {@code casts_fused.tsv}, from which the script
 * derives {@code casts.tsv}'s {@code fused_today}. Neither test asks about answers: those are the
 * coverage rows' (a fused pair needs one) and row 368's.
 */
@VarkaSuite
public final class VarkaCastMatrixSuite {

  /**
   * Each census type as a Spark type, in every form it has: a decimal's precision and scale (two
   * of them), a time's precision (three), an interval's start and end fields (all of them). A
   * pair within one type casts between two of its forms, since Spark's cast there is a real one,
   * and the file's same-type row stands for it. The fusion test reads every form, since the
   * compiler's answer depends on the form: it fuses a day-time cast whose target ends in
   * {@code SECOND} and no other.
   */
  static final Map<String, DataType[]> TYPES = new LinkedHashMap<>();

  // The type registry's census types and forms, in its order (VARKA-494).
  static {
    for (VarkaTypes.Info i : VarkaTypes.all()) {
      if (i.census() != null) {
        TYPES.put(i.census(), i.forms().toArray(new DataType[0]));
      }
    }
  }

  /** The three modes' legality of one pair, as {@code casts.tsv} writes it. */
  record Legality(boolean legacy, boolean ansi, boolean tryCast) {
    static final Legality NONE = new Legality(false, false, false);

    static Legality of(DataType from, DataType to) {
      return new Legality(Cast$.MODULE$.canCast(from, to), Cast$.MODULE$.canAnsiCast(from, to),
          Cast$.MODULE$.canTryCast(from, to));
    }
  }

  private static String home() {
    String home = System.getProperty("spark.test.home", System.getenv("SPARK_HOME"));
    assertTrue(home != null, "spark.test.home or SPARK_HOME is not set.");
    return home;
  }

  private static Map<String, Legality> committed() throws IOException {
    List<String> lines = Files.readAllLines(
        Path.of(home(), "sql", "varka", "plans", "m8", "casts.tsv"), StandardCharsets.UTF_8);
    assertTrue(lines.get(0).startsWith("from\tto\tlegacy\tansi\ttry\t"), lines.get(0));
    Map<String, Legality> out = new LinkedHashMap<>();
    for (String line : lines.subList(1, lines.size())) {
      String[] f = line.split("\t", -1);
      out.put(f[0] + ">" + f[1],
          new Legality(f[2].equals("yes"), f[3].equals("yes"), f[4].equals("yes")));
    }
    return out;
  }

  /**
   * Where {@code file}, the matrix's legality as {@code casts.tsv} records it, disagrees with
   * {@code Cast}: one line per pair and form, then one for a pair of a type outside the census.
   * Empty when they agree. Taking the file as an argument lets a test plant a flipped answer.
   */
  static List<String> legalityErrors(Map<String, Legality> file) {
    List<String> wrong = new ArrayList<>();
    for (var from : TYPES.entrySet()) {
      for (var to : TYPES.entrySet()) {
        String key = from.getKey() + ">" + to.getKey();
        Legality claimed = file.getOrDefault(key, Legality.NONE);
        // Every form of the source against every form of the target: legality depends on the
        // type family alone for these types, and a form that disagreed would show it here.
        for (DataType src : from.getValue()) {
          for (DataType dst : to.getValue()) {
            Legality actual = Legality.of(src, dst);
            if (!actual.equals(claimed)) {
              wrong.add(key + " (" + src.simpleString() + " to " + dst.simpleString()
                  + "): casts.tsv " + claimed + ", Cast " + actual);
            }
          }
        }
      }
    }
    for (String key : file.keySet()) {
      String[] p = key.split(">");
      if (!TYPES.containsKey(p[0]) || !TYPES.containsKey(p[1])) {
        wrong.add(key + ": casts.tsv names a type outside the census's thirteen");
      }
    }
    return wrong;
  }

  @Test
  @DisplayName("casts.tsv's legality agrees with Cast over every ordered pair, in every mode")
  void legality() throws IOException {
    Map<String, Legality> file = committed();
    // A file that lost its rows would agree with Cast only where no pair is legal.
    assertTrue(file.size() > TYPES.size(), "casts.tsv holds only " + file.size() + " pairs");
    List<String> wrong = legalityErrors(file);
    assertTrue(wrong.isEmpty(),
        "dev/varka_census.py's legal() disagrees with Cast:\n  " + String.join("\n  ", wrong));
  }

  @Test
  @DisplayName("a pair whose legality is flipped in one mode fails the comparison, naming the pair")
  void plantedLegality() throws IOException {
    // VARKA-504's planted fault: the first legal pair's ANSI answer flipped in memory.
    var file = new LinkedHashMap<>(committed());
    Map.Entry<String, Legality> first = file.entrySet().stream()
        .filter(e -> !e.getValue().equals(Legality.NONE)).findFirst().orElseThrow();
    Legality l = first.getValue();
    file.put(first.getKey(), new Legality(l.legacy(), !l.ansi(), l.tryCast()));
    List<String> wrong = legalityErrors(file);
    assertFalse(wrong.isEmpty(), "a flipped answer for " + first.getKey() + " passed");
    assertTrue(wrong.stream().allMatch(w -> w.startsWith(first.getKey() + " (")), wrong.toString());
  }

  // -------------------------------------------------------------------------------------------
  // Fusion: what the compiler fuses, recorded in casts_fused.tsv
  // -------------------------------------------------------------------------------------------

  /** The committed answers, beside {@code casts.tsv}. */
  private static final String FUSED_FILE = "casts_fused.tsv";

  private static final String FUSED_HEADER = "from\tto\tfrom_type\tto_type\tlegacy\tansi\ttry";

  /** The regeneration command, which a failure names. */
  private static final String REGEN =
      "VARKA_CASTS_REGEN=true build/sbt 'catalyst/testOnly *VarkaCastMatrixSuite'";

  /**
   * Whether the compiler fuses {@code CAST(x AS to)} over a column {@code x} of type {@code from}
   * in {@code mode}: as a projection, and for a {@code BOOLEAN} target as a predicate too. Fused
   * means the compiler returns a plan, so a cast lowered to the column's widening load (a narrow
   * source to {@code INT}), with no cast node in the kernel, counts. The defaults' options, not
   * the matrix's: the file records what production fuses.
   *
   * <p>A {@code BOOLEAN} target is one answer for both forms, since {@code VarkaTypeConformance}
   * holds a cast's condition form and its value twin, the {@code BOOLEAN} output column, to the
   * same line of the file. A projection root of that type compiles through the condition compiler
   * a predicate does, so the two are expected to agree; a pair on which they differ fails here
   * rather than being recorded as one of them.
   */
  private static boolean fuses(DataType from, DataType to, String mode) {
    var cols = castColumns(from);
    Expression e = castExpression(cols, to, mode);
    var entry = CollectionConverters.asScala(List.of(VarkaSqlResolve.named(e, "c"))).toSeq();
    boolean value = VarkaExpressionCompiler.compile(entry, cols, VarkaEmitOptions.DEFAULTS)
        .isPresent();
    if (to.equals(DataTypes.BooleanType)) {
      var p = VarkaExpressionCompiler.compilePredicate(e, cols, VarkaEmitOptions.DEFAULTS,
          VarkaAdmission.NO_HOST_REFUSAL);
      boolean predicate = p.isPresent() && p.get().residualConjuncts().isEmpty();
      assertTrue(predicate == value, "CAST(" + from.sql() + " AS BOOLEAN) in " + mode + " fuses "
          + (predicate ? "as a predicate only" : "as a projection only")
          + ": the two forms share one line of " + FUSED_FILE);
    }
    return value;
  }

  /** The one column {@link #castExpression} casts, {@code x} of type {@code from}. */
  static scala.collection.immutable.Seq<Attribute> castColumns(DataType from) {
    return CollectionConverters.asScala(List.<Attribute>of(VarkaSqlResolve.column("x", from)))
        .toSeq();
  }

  /**
   * {@code CAST(x AS to)}, or {@code try_cast} for the {@code try} mode, over {@code cols}'s one
   * column, analysed with ANSI off for {@code legacy} and on otherwise: the cast as the matrix
   * asks the compiler about it, which {@code VarkaDivisorProofSuite} also compiles.
   */
  static Expression castExpression(
      scala.collection.immutable.Seq<Attribute> cols, DataType to, String mode) {
    String sql = (mode.equals("try") ? "try_cast" : "CAST") + "(x AS " + to.sql() + ")";
    SQLConf conf = SQLConf.get().clone();
    conf.setConfString(SQLConf.ANSI_ENABLED().key(), mode.equals("legacy") ? "false" : "true");
    return SQLConf$.MODULE$.withExistingConf(conf, () -> VarkaSqlResolve.parse(sql, cols));
  }

  /** The file's lines as the compiler answers them today: every legal cast of two forms. */
  private static List<String> probe() {
    var lines = new ArrayList<String>();
    lines.add(FUSED_HEADER);
    for (var from : TYPES.entrySet()) {
      for (var to : TYPES.entrySet()) {
        for (DataType src : from.getValue()) {
          for (DataType dst : to.getValue()) {
            Legality legal = Legality.of(src, dst);
            // An identity cast is removed by the analyzer before a cast exists.
            if (src.equals(dst) || legal.equals(Legality.NONE)) {
              continue;
            }
            lines.add(String.join("\t", from.getKey(), to.getKey(), src.sql(), dst.sql(),
                answer(legal.legacy(), src, dst, "legacy"), answer(legal.ansi(), src, dst, "ansi"),
                answer(legal.tryCast(), src, dst, "try")));
          }
        }
      }
    }
    return lines;
  }

  private static String answer(boolean legal, DataType from, DataType to, String mode) {
    return !legal ? "illegal" : fuses(from, to, mode) ? "fused" : "declined";
  }

  @Test
  @Tag(VarkaSuite.PINS_DEFAULTS)
  @DisplayName("casts_fused.tsv is what the compiler fuses, over every form of every legal cast")
  void fusion() throws IOException {
    Path file = Path.of(home(), "sql", "varka", "plans", "m8", FUSED_FILE);
    List<String> fresh = probe();
    if ("true".equals(System.getenv("VARKA_CASTS_REGEN"))) {
      Files.write(file, fresh, StandardCharsets.UTF_8);
      return;
    }
    List<String> committed = Files.exists(file)
        ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
    // Every legal cast of two forms is a line; a probe of none would match an empty file.
    assertTrue(fresh.size() > TYPES.size(), "the probe wrote only " + fresh.size() + " lines");
    List<String> moved = fusionMoves(committed, fresh);
    if (moved.isEmpty()) {
      return;
    }
    fail("the compiler's casts moved from " + FUSED_FILE + ":\n  "
        + String.join("\n  ", moved) + "\nRegenerate it, then the census, with\n  " + REGEN
        + "\n  dev/varka_census.py");
  }

  /**
   * The lines of {@code fresh}, the compiler's answers, and of {@code committed}, the file's, that
   * the other lacks, each marked with its side; empty only when the two are equal. A file that
   * differs only in order or by a repeated line gets one line saying so.
   */
  static List<String> fusionMoves(List<String> committed, List<String> fresh) {
    var moved = new ArrayList<String>();
    if (committed.equals(fresh)) {
      return moved;
    }
    for (String line : fresh) {
      if (!committed.contains(line)) {
        moved.add("now:  " + line);
      }
    }
    for (String line : committed) {
      if (!fresh.contains(line)) {
        moved.add("file: " + line);
      }
    }
    if (moved.isEmpty()) {
      moved.add("the same lines in another order, or with one repeated");
    }
    return moved;
  }

  @Test
  @DisplayName("a casts_fused.tsv line with one answer changed fails the comparison, naming it")
  void plantedFusion() throws IOException {
    // VARKA-504's planted fault: the committed file stands in for the compiler's answers, so the
    // plant needs no probe, and one line's answer is changed in memory.
    Path file = Path.of(home(), "sql", "varka", "plans", "m8", FUSED_FILE);
    List<String> committed = Files.readAllLines(file, StandardCharsets.UTF_8);
    assertTrue(fusionMoves(committed, committed).isEmpty());
    int at = 1;
    while (!committed.get(at).contains("\tfused")) {
      at++;
    }
    String line = committed.get(at);
    int k = line.indexOf("\tfused");
    String changed = line.substring(0, k) + "\tdeclined" + line.substring(k + "\tfused".length());
    var fresh = new ArrayList<>(committed);
    fresh.set(at, changed);
    assertEquals(List.of("now:  " + changed, "file: " + line), fusionMoves(committed, fresh));
    var reordered = new ArrayList<>(committed);
    Collections.swap(reordered, 1, 2);
    assertEquals(1, fusionMoves(committed, reordered).size());
  }
}
