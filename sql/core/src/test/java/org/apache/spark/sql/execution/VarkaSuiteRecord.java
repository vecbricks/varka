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


package org.apache.spark.sql.execution;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.annotation.Nullable;

import org.apache.spark.sql.catalyst.expressions.AttributeReference;
import org.apache.spark.sql.catalyst.expressions.Cast;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrixRuns;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypeId;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypes;
import org.apache.spark.sql.types.DataType;

/**
 * What Spark's cast suites run under Varka record and are held to beside the answers
 * ({@code m8/VARKA-368.md} 2.6 and 2.7): each bare cast's compile verdict per form and mode in
 * {@code casts_suites.tsv}, the tests {@code expression_excluded.tsv} lists, and the batch
 * declines {@code expression_declines.tsv} expects of forms {@code casts_fused.tsv} calls fused.
 * {@link VarkaSuiteCheck} feeds it each check; the suites' trait asks it at the end of a suite.
 */
public final class VarkaSuiteRecord {

  private VarkaSuiteRecord() {}

  /** Each suite's tests registered, and those whose checks all ran to a decision. */
  private static final Map<String, int[]> TESTS = new HashMap<>();

  /** Counts a test {@code suite} registers, so {@link #finish} knows whether all of them ran. */
  public static synchronized void registerTest(String suite) {
    TESTS.computeIfAbsent(suite, s -> new int[2])[0]++;
  }

  /** Counts a test of {@code suite} whose checks ran to a decision that is not a failure. */
  static synchronized void completed(String suite) {
    TESTS.computeIfAbsent(suite, s -> new int[2])[1]++;
  }

  // ---------------------------------------------------------------------------------------------
  // The record: casts_suites.tsv
  // ---------------------------------------------------------------------------------------------

  /** The record's file, beside the matrix's. */
  static final String RECORD = "casts_suites.tsv";

  static final String RECORD_HEADER =
      "suite\tfrom\tto\tfrom_type\tto_type\tmode\tverdict\treason";

  /** The command that regenerates the record, which a failure names. */
  static final String REGEN = "VARKA_SUITES_REGEN=true build/sbt 'sql/testOnly "
      + "*VarkaCastWithAnsiOffSuite *VarkaCastWithAnsiOnSuite *VarkaTryCastSuite'";

  /** Each suite's keys - suite, from, to, both forms, mode - with their verdicts and reasons. */
  private static final Map<String, Map<List<String>, Set<String>>> KEYS = new HashMap<>();

  /** The census name of {@code type}'s family, or {@code -} outside the census. */
  static String census(DataType type) {
    @Nullable VarkaTypeId id = VarkaTypes.idOf(type);
    @Nullable String census = id == null ? null : VarkaTypes.info(id).census();
    return census == null ? "-" : census;
  }

  static String mode(Cast cast) {
    return cast.evalMode().toString().toLowerCase(Locale.ROOT);
  }

  /** Keeps a bare cast's verdict: a cast of one lifted column, whatever the check expected. */
  static synchronized void record(String suite, Expression lifted,
      @Nullable VarkaSuiteCheck.Verdict verdict, @Nullable String unresolved) {
    if (!(lifted instanceof Cast cast) || !(cast.child() instanceof AttributeReference)) {
      return;
    }
    DataType from = cast.child().dataType();
    DataType to = cast.dataType();
    List<String> key = List.of(suite, census(from), census(to), from.sql(), to.sql(), mode(cast));
    // Every fixed-value check of a form the matrix calls fused, compile-declined or not, is one
    // a listed batch decline could have met: the stale rule's denominator.
    if (MATRIX_FUSED.contains(List.of(from.sql(), to.sql(), mode(cast)))) {
      DECLINES_RAN.add(List.of(suite, from.sql(), to.sql(), mode(cast)));
    }
    Set<String> found = KEYS.computeIfAbsent(suite, s -> new TreeMap<>(VarkaSuiteRecord::byKey))
        .computeIfAbsent(key, k -> new TreeSet<>());
    if (unresolved != null) {
      found.add("unresolved\t" + VarkaSuiteCheck.clean(unresolved));
    } else if (verdict.fused()) {
      found.add("fused\t");
    } else {
      for (String r : verdict.reasons().keySet()) {
        found.add("declined\t" + r);
      }
      if (verdict.reasons().isEmpty()) {
        // A decline that names no reason is still a decline, never the empty set that reads fused.
        found.add("declined\t(no reason given)");
      }
    }
  }

  private static int byKey(List<String> a, List<String> b) {
    for (int i = 0; i < a.size(); i++) {
      int c = a.get(i).compareTo(b.get(i));
      if (c != 0) {
        return c;
      }
    }
    return 0;
  }

  /**
   * A key's line: declined when any check of it declined, with every reason any gave, sorted and
   * joined; else unresolved when any check could not run; else fused.
   */
  private static String line(List<String> key, Set<String> found) {
    var declined = new LinkedHashSet<String>();
    var unresolved = new LinkedHashSet<String>();
    for (String f : found) {
      String[] parts = f.split("\t", 2);
      if (parts[0].equals("declined")) {
        declined.add(parts[1]);
      } else if (parts[0].equals("unresolved")) {
        unresolved.add(parts[1]);
      }
    }
    String verdict = !declined.isEmpty() ? "declined" : !unresolved.isEmpty() ? "unresolved"
        : "fused";
    String reason = String.join("; ", !declined.isEmpty() ? declined : unresolved);
    return String.join("\t", key) + "\t" + verdict + "\t" + reason;
  }

  /** Whether this run compares or writes the record: at the defaults, unless switched off. */
  static boolean recording() {
    return VarkaMatrixRuns.CONFIG.isEmpty()
        && !"false".equals(System.getProperty("varka.suites.record"));
  }

  private static Path plans(String file) {
    return Path.of(home(), "sql", "varka", "plans", "m8", file);
  }

  private static Path lists(String file) {
    return Path.of(home(), "sql", "varka", "spark_suites", file);
  }

  private static String home() {
    String home = System.getProperty("spark.test.home", System.getenv("SPARK_HOME"));
    if (home == null) {
      throw new IllegalStateException("spark.test.home or SPARK_HOME is not set");
    }
    return home;
  }

  private static List<String> read(Path file) {
    try {
      return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * At the end of {@code suite}: its lines of the record written, under
   * {@code VARKA_SUITES_REGEN=true}, or else compared with the committed file, and its stale
   * expected declines; null when all holds, else what does not. Both only at the defaults and
   * when every test the suite registered ran to its decision, since a run of some of its tests
   * reaches some of its forms.
   */
  public static synchronized @Nullable String finish(String suite) {
    System.out.println("VarkaSuiteRecord: " + VarkaSuiteCheck.summary(suite));
    int[] tests = TESTS.getOrDefault(suite, new int[2]);
    // A test SparkFunSuite's testRetry repeats reaches its decision more than once.
    boolean complete = tests[0] > 0 && tests[1] >= tests[0];
    var problems = new ArrayList<String>();
    if (!complete || !recording()) {
      System.out.println("VarkaSuiteRecord: " + suite + ": the record is not compared, "
          + (recording() ? tests[1] + " of " + tests[0] + " tests reached their decision"
              : "the run is not at the defaults"));
      KEYS.remove(suite);
      // A regeneration that wrote nothing must not pass, or the job's diff would find nothing.
      return recording() && "true".equals(System.getenv("VARKA_SUITES_REGEN"))
          ? suite + ": the record was not written, " + tests[1] + " of " + tests[0]
              + " tests reached their decision" : null;
    }
    for (List<String> entry : DECLINES_LISTED) {
      if (entry.get(0).equals(suite) && !DECLINES_SEEN.contains(entry)
          && DECLINES_RAN.contains(entry.subList(0, 4))) {
        problems.add("expression_declines.tsv lists " + entry + ", whose fixed-value checks ran "
            + "and declined no batch: a stale line, to be removed");
      }
    }
    compareRecord(suite, problems);
    KEYS.remove(suite);
    return problems.isEmpty() ? null : String.join("\n", problems);
  }

  /** {@code suite}'s lines of the record, written under regeneration or else compared. */
  private static void compareRecord(String suite, List<String> problems) {
    var mine = new ArrayList<String>();
    KEYS.getOrDefault(suite, Map.of()).forEach((k, v) -> mine.add(line(k, v)));
    Path file = plans(RECORD);
    var others = new ArrayList<String>();
    var committed = new ArrayList<String>();
    for (String l : read(file)) {
      if (l.equals(RECORD_HEADER) || l.isEmpty()) {
        continue;
      }
      (l.startsWith(suite + "\t") ? committed : others).add(l);
    }
    if ("true".equals(System.getenv("VARKA_SUITES_REGEN"))) {
      var all = new ArrayList<String>(others);
      all.addAll(mine);
      all.sort(null);
      all.add(0, RECORD_HEADER);
      try {
        Files.write(file, all, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    } else if (!sorted(committed).equals(sorted(mine))) {
      var moved = new ArrayList<String>();
      for (String l : mine) {
        if (!committed.contains(l)) {
          moved.add("+ " + l);
        }
      }
      for (String l : committed) {
        if (!mine.contains(l)) {
          moved.add("- " + l);
        }
      }
      problems.add(RECORD + "'s lines of " + suite + " moved:\n  "
          + String.join("\n  ", moved) + "\nRegenerate it with\n  " + REGEN);
    }
  }

  /** A sorted copy: a text merge may leave the committed lines out of order, which is no move. */
  private static List<String> sorted(List<String> lines) {
    var out = new ArrayList<String>(lines);
    out.sort(null);
    return out;
  }

  // ---------------------------------------------------------------------------------------------
  // The lists: expression_excluded.tsv and expression_declines.tsv
  // ---------------------------------------------------------------------------------------------

  /** The tests whose findings are not the kernel's, by suite and test: kind and reason. */
  private static final Map<List<String>, String[]> EXCLUDED = excluded();

  private static Map<List<String>, String[]> excluded() {
    var out = new HashMap<List<String>, String[]>();
    for (String l : read(lists("expression_excluded.tsv"))) {
      if (l.isBlank() || l.startsWith("#") || l.startsWith("test\t")) {
        continue;
      }
      String[] f = fields(l, 4, "expression_excluded.tsv");
      if (!Set.of(VarkaSuiteCheck.Kind.READ_PATH.label, VarkaSuiteCheck.Kind.LIFT.label,
          VarkaSuiteCheck.Kind.SPARK.label).contains(f[2])) {
        throw new IllegalStateException("expression_excluded.tsv: no kind " + f[2] + ": " + l);
      }
      out.put(List.of(f[1], f[0]), new String[] {f[2], f[3]});
    }
    return out;
  }

  /**
   * The expected declines: suite, from form, to form, mode and kind, for a form
   * {@code casts_fused.tsv} calls fused that is meant to decline in the suites.
   */
  private static final Set<List<String>> DECLINES_LISTED = declinesListed();

  /** The listed batch declines a fixed-value check met this run. */
  private static final Set<List<String>> DECLINES_SEEN = new HashSet<>();

  /** The forms of the listed batch declines whose fixed-value checks ran this run. */
  private static final Set<List<String>> DECLINES_RAN = new HashSet<>();

  private static Set<List<String>> declinesListed() {
    var out = new HashSet<List<String>>();
    for (String l : read(lists("expression_declines.tsv"))) {
      if (l.isBlank() || l.startsWith("#") || l.startsWith("suite\t")) {
        continue;
      }
      String[] f = fields(l, 6, "expression_declines.tsv");
      if (!f[4].equals("batch")) {
        throw new IllegalStateException("expression_declines.tsv: the subclasses check the batch "
            + "kind only, and a compile decline is the census's: " + l);
      }
      out.add(List.of(f[0], f[1], f[2], f[3], f[4]));
    }
    return out;
  }

  /** {@code line}'s tab-separated fields, of which {@code file} has {@code n} with a reason. */
  private static String[] fields(String line, int n, String file) {
    String[] f = line.split("\t", -1);
    if (f.length != n || f[n - 1].isBlank()) {
      throw new IllegalStateException(file + ": a line needs " + n + " fields, the last its "
          + "reason: " + line);
    }
    return f;
  }

  /** {@code casts_fused.tsv}'s fused forms: from form, to form and mode. */
  private static final Set<List<String>> MATRIX_FUSED = matrixFused();

  private static Set<List<String>> matrixFused() {
    var out = new HashSet<List<String>>();
    String[] modes = {"legacy", "ansi", "try"};
    for (String l : read(plans("casts_fused.tsv"))) {
      String[] f = l.split("\t", -1);
      if (f.length < 7 || f[0].equals("from")) {
        continue;
      }
      for (int m = 0; m < 3; m++) {
        if (f[4 + m].equals("fused")) {
          out.add(List.of(f[2], f[3], modes[m]));
        }
      }
    }
    return out;
  }

  /**
   * A fixed-value check of a bare cast whose form {@code casts_fused.tsv} calls fused, in which a
   * batch declined although Spark answers every row: a finding, unless
   * {@code expression_declines.tsv} lists it.
   */
  static synchronized void batchDecline(String suite, Cast cast, VarkaExpressionHarness.Outcome o,
      List<VarkaSuiteCheck.Finding> findings, String context) {
    String from = cast.child().dataType().sql();
    String to = cast.dataType().sql();
    List<String> form = List.of(from, to, mode(cast));
    if (!MATRIX_FUSED.contains(form)) {
      return;
    }
    List<String> entry = List.of(suite, from, to, mode(cast), "batch");
    if (o.declined == 0 || o.sparkRaised > 0) {
      return;
    }
    if (DECLINES_LISTED.contains(entry)) {
      DECLINES_SEEN.add(entry);
      return;
    }
    findings.add(new VarkaSuiteCheck.Finding(VarkaSuiteCheck.Kind.VARKA, context
        + ": a batch declined on rows Spark answers, in a form casts_fused.tsv calls fused ("
        + o.batches + "); if the decline is meant, list it in expression_declines.tsv with its "
        + "reason: " + String.join("\t", entry)));
  }

  /** The kind and reason {@code expression_excluded.tsv} lists {@code test} under, or null. */
  static synchronized @Nullable String[] excluded(String suite, String test) {
    return EXCLUDED.get(List.of(suite, test));
  }
}
