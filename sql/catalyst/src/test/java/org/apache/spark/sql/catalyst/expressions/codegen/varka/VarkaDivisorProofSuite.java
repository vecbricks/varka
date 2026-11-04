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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConstDivide;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.TimeType;

/**
 * Every long-lane divisor the compiler builds is one {@code long_divide.smt2} proves (VARKA-496).
 * The proof is rendered per divisor from {@link VarkaProofFiles#LONG_DIVISORS}, a list kept by
 * hand, so a compiler change that divides by a new constant would ship a division no proof
 * states and nothing would fail. This suite compiles the coverage table's rows
 * ({@code sql/varka/coverage.json}), {@code time_diff} and {@code time_trunc} at every unit the
 * compiler's table accepts, and every legal cast of the cast matrix, in each of its three
 * modes, walks each compiled kernel's tree for {@link ConstDivide} nodes on the long lane, and
 * fails on a divisor the list leaves out - as {@code VarkaEmitterYmDivideSuite} holds its sweep to
 * {@code ym_divide.smt2}.
 *
 * <p>The divisors are read from the compiled IR, not from the SQL: a {@code ConstDivide} and its
 * divisor are built by the node compilers before any emit option chooses how it lowers, so the
 * set does not depend on the options, and the defaults' compile is every compile's. So both tests
 * are tagged {@link VarkaSuite#PINS_DEFAULTS}, which the option matrix cancels under each of its
 * configurations: a run there would compile the same IR again.
 *
 * <p>The int lane is not held here. Its divisors are open: {@code ym / d} divides by the query's
 * own literal ({@code VarkaIntervalCompiler.divide}), so no corpus bounds them, and what makes
 * every one exact is Granlund and Montgomery's Theorem 5.1, which
 * {@link VarkaDivisionLowering.MulHiMagic} asserts for each divisor it is built for;
 * {@code int_mulhi_divide.smt2} proves the lanes' arithmetic for a sample of them. The calendar's
 * divisions ({@code chrono_divide.smt2}) are a closed table, {@code ChronoDivide}, which the proof
 * iterates whole. A proof of the int lane for any divisor is {@code m9/SCOPE.md} item 116.
 */
@VarkaSuite
public final class VarkaDivisorProofSuite {

  /**
   * The proof's divisors that no compiled kernel divides by, each on purpose: -60 is there for
   * the sign branch a negative divisor takes and a positive one does not. A proof divisor that
   * stops being collected and is not named here fails, so the list cannot keep a divisor the
   * compiler has dropped.
   */
  private static final Set<Long> PROVEN_ONLY = Set.of(-60L);

  /** The long-lane divisors of a corpus, each with the first source that divides by it. */
  private record Collected(Map<Long, String> divisors) {

    /** The divisors {@code proven} leaves out, each with where it was built. */
    List<String> unproven(List<Long> proven) {
      var out = new ArrayList<String>();
      divisors.forEach((d, where) -> {
        if (!proven.contains(d)) {
          out.add(d + " (" + where + ")");
        }
      });
      return out;
    }
  }

  /**
   * Adds the divisor of every long-lane {@code ConstDivide} under {@code root} to
   * {@code divisors}, keyed to {@code where} if it is the first source to build it. A division by
   * 1 is left out: {@code emitConstDivide} emits nothing for it, so there is no form to prove. A
   * division by -1 is kept, since the lowering emits a form for it as for any other divisor.
   * Trees can share subtrees, so a node is visited once.
   */
  static void collect(VarkaVectorIR root, String where, Map<Long, String> divisors) {
    Set<VarkaVectorIR> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    var pending = new ArrayList<VarkaVectorIR>(List.of(root));
    while (!pending.isEmpty()) {
      VarkaVectorIR node = pending.remove(pending.size() - 1);
      if (!seen.add(node)) {
        continue;
      }
      if (node instanceof ConstDivide n && n.child().laneType() == LaneType.LONG
          && n.divisor() != 1) {
        divisors.putIfAbsent(n.divisor(), where);
      }
      Collections.addAll(pending, VarkaVectorIR.childrenOf(node));
    }
  }

  private static void collect(
      Optional<CompiledVarkaProjection> compiled, String where, Map<Long, String> divisors) {
    compiled.ifPresent(p -> p.outputs().forEach(root -> collect(root, where, divisors)));
  }

  /** A projection entry or a predicate, compiled under the default options. */
  private static Optional<CompiledVarkaProjection> compile(
      Expression e, scala.collection.immutable.Seq<Attribute> cols, boolean predicate) {
    if (predicate) {
      return VarkaExpressionCompiler.compilePredicate(e, cols, VarkaEmitOptions.DEFAULTS,
          VarkaAdmission.NO_HOST_REFUSAL)
          .map(p -> p.fused());
    }
    var entry = CollectionConverters.asScala(List.of(VarkaSqlResolve.named(e, "c"))).toSeq();
    return VarkaExpressionCompiler.compile(entry, cols, VarkaEmitOptions.DEFAULTS);
  }

  private static String home() {
    String home = System.getProperty("spark.test.home", System.getenv("SPARK_HOME"));
    assertTrue(home != null, "spark.test.home or SPARK_HOME is not set.");
    return home;
  }

  /** The coverage rows, the time units and the cast matrix, compiled, and their divisors. */
  private static Collected corpus() {
    Map<Long, String> divisors = new TreeMap<>();
    var table = VarkaCoverageRows.read(Path.of(home(), "sql", "varka", "coverage.json"));
    var rows = new ArrayList<VarkaCoverageRows.Row>();
    rows.addAll(CollectionConverters.asJava(table.projections()));
    rows.addAll(CollectionConverters.asJava(table.predicates()));
    for (VarkaCoverageRows.Row row : rows) {
      Expression e = VarkaCoverageRows.resolve(row.executable(), table.columns());
      collect(compile(e, table.columns(), row.predicate()), "coverage row " + row.executable(),
          divisors);
    }
    // Every unit time_diff and time_trunc accept, from the compiler's own table, so a unit no
    // coverage row names is still held to the proof.
    for (String unit : new TreeSet<>(VarkaExpressionCompiler.timeUnitsForTesting().keySet())) {
      for (String sql : List.of("time_diff('" + unit + "', t, t2)",
          "time_trunc('" + unit + "', t)")) {
        var compiled = compile(VarkaCoverageRows.resolve(sql, table.columns()), table.columns(),
            false);
        assertTrue(compiled.isPresent(), sql + " does not compile, so its divisor is not seen");
        collect(compiled, sql, divisors);
      }
    }
    // The decimal second of every TIME precision and time_to_seconds (VARKA-157 2.3): the
    // fraction divides by 10^(9 - p), a divisor per precision that no coverage row reaches.
    for (int p = 0; p <= 9; p++) {
      var cols = VarkaCastMatrixSuite.castColumns(new TimeType(p));
      for (String sql : List.of("extract(SECOND FROM x)", "time_to_seconds(x)")) {
        var compiled = compile(VarkaCoverageRows.resolve(sql, cols), cols, false);
        assertTrue(compiled.isPresent(), sql + " over TIME(" + p + ") does not compile, so its "
            + "divisor is not seen");
        collect(compiled, sql + " over TIME(" + p + ")", divisors);
      }
    }
    // Every legal cast between two forms, in every mode, as VarkaCastMatrixSuite probes them.
    for (DataType[] froms : VarkaCastMatrixSuite.TYPES.values()) {
      for (DataType[] tos : VarkaCastMatrixSuite.TYPES.values()) {
        for (DataType from : froms) {
          for (DataType to : tos) {
            if (from.equals(to)) {
              continue;
            }
            var legal = VarkaCastMatrixSuite.Legality.of(from, to);
            var cols = VarkaCastMatrixSuite.castColumns(from);
            for (String mode : List.of("legacy", "ansi", "try")) {
              boolean admitted = switch (mode) {
                case "legacy" -> legal.legacy();
                case "ansi" -> legal.ansi();
                default -> legal.tryCast();
              };
              if (admitted) {
                Expression e = VarkaCastMatrixSuite.castExpression(cols, to, mode);
                collect(compile(e, cols, to.equals(DataTypes.BooleanType)),
                    mode + " cast " + from.sql() + " to " + to.sql(), divisors);
              }
            }
          }
        }
      }
    }
    return new Collected(divisors);
  }

  @Test
  @Tag(VarkaSuite.PINS_DEFAULTS)
  @DisplayName("every long-lane divisor the coverage rows and the cast matrix compile is one "
      + "long_divide.smt2 proves, and every divisor it proves is compiled or named")
  void everyDivisorProven() {
    Collected collected = corpus();
    List<Long> proven = VarkaProofFiles.LONG_DIVISORS;
    List<String> unproven = collected.unproven(proven);
    assertTrue(unproven.isEmpty(), "the compiler builds a long-lane ConstDivide by a divisor "
        + "long_divide.smt2 does not prove; add it to VarkaProofFiles.LONG_DIVISORS, regenerate "
        + "the proof and run dev/varka_prove.sh:\n  " + String.join("\n  ", unproven));
    var stale = new TreeSet<Long>(proven);
    stale.removeAll(collected.divisors().keySet());
    assertEquals(new TreeSet<>(PROVEN_ONLY), stale, "the proof's divisors no compiled kernel "
        + "divides by should be exactly PROVEN_ONLY; collected: " + collected.divisors().keySet());
  }

  @Test
  @Tag(VarkaSuite.PINS_DEFAULTS)
  @DisplayName("a long-lane divisor the proof does not cover, planted among the compiled kernels, "
      + "is reported")
  void plantedDivisorReported() {
    Map<Long, String> divisors = new TreeMap<>();
    // A division by 7 on the long lane, which no compiler builds and long_divide.smt2 does not
    // state, collected through the same walk as the corpus's kernels. The map starts empty, so
    // the test shows the failure path alone and does not depend on the corpus being proven.
    var planted = new ConstDivide(new ColumnRef(0, LaneType.LONG), 7L,
        ConstDivide.EXACT_DIVIDEND_BOUND);
    collect(planted, "planted", divisors);
    assertEquals(List.of("7 (planted)"),
        new Collected(divisors).unproven(VarkaProofFiles.LONG_DIVISORS));
  }
}
