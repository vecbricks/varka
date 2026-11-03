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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaConjunctSpec;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.DecimalType;
import org.apache.spark.sql.types.YearMonthIntervalType;

/**
 * How long {@code VarkaExpressionCompiler.explainPredicate} takes over a predicate nested
 * {@code depth} levels deep, for the chains {@code VARKA-559.md} times (run with
 * {@code build/sbt "catalyst/Test/runMain <this class> [chain...]"}). Each level's condition reads
 * the level below, so a planning walk that visits a shared node once per path costs a power of the
 * depth while one that visits it once costs the depth. A chain stops at the first depth whose
 * explain takes over three seconds; a time is the least of three explains after one warm-up.
 *
 * <p>Prints one line per chain and depth: {@code <chain> <depth> <ms> <fused|reason>}.
 */
public final class VarkaPlanningCostProbe {

  private VarkaPlanningCostProbe() {}

  /** A predicate family: its columns' type, a level around {@code X}, and the root over it. */
  private record Chain(String name, DataType type, String level, String root) {}

  private static final DataType YM = new YearMonthIntervalType((byte) 0, (byte) 1);

  private static final List<Chain> CHAINS = List.of(
      new Chain("int-coalesce", DataTypes.IntegerType, "coalesce(IF((X) > b, a, c), b)",
          "(X) > c"),
      new Chain("int-if", DataTypes.IntegerType, "IF((X) > b, a, c)", "(X) > c"),
      new Chain("int-case", DataTypes.IntegerType,
          "CASE WHEN (X) IS NULL THEN a WHEN b > c THEN b ELSE c END", "(X) IS NOT NULL"),
      new Chain("int-greatest", DataTypes.IntegerType, "greatest(IF((X) > b, a, c), b)",
          "(X) > c"),
      new Chain("int-nulltest", DataTypes.IntegerType, "IF((X) IS NOT NULL, b, c)",
          "(X) IS NULL"),
      new Chain("int-nvl2", DataTypes.IntegerType, "nvl2(X, a, b)", "(X) > c"),
      new Chain("double-signum", DataTypes.DoubleType, "signum(X)", "(X) > c"),
      new Chain("ym-abs", YM, "abs(X)", "(X) > c"),
      new Chain("ym-divide", YM, "(X) / 3", "(X) > c"),
      new Chain("dec-nulltest", new DecimalType(9, 2), "IF((X) IS NOT NULL, b, c)",
          "(X) IS NULL"),
      new Chain("dec-case", new DecimalType(9, 2),
          "CASE WHEN (X) IS NULL THEN a WHEN b > c THEN b ELSE c END", "(X) IS NOT NULL"),
      new Chain("dec-coalesce", new DecimalType(9, 2), "coalesce(IF((X) > b, a, c), b)",
          "(X) > c"));

  public static void main(String[] args) {
    List<String> only = List.of(args);
    for (Chain chain : CHAINS) {
      if (!only.isEmpty() && !only.contains(chain.name())) {
        continue;
      }
      var columns = CollectionConverters.asScala(List.<Attribute>of(
          VarkaSqlResolve.column("a", chain.type()), VarkaSqlResolve.column("b", chain.type()),
          VarkaSqlResolve.column("c", chain.type()))).toSeq();
      for (int depth = 2; depth <= 20; depth += 2) {
        String x = "a";
        for (int i = 0; i < depth; i++) {
          x = chain.level().replace("X", x);
        }
        Expression e = VarkaSqlResolve.parse(chain.root().replace("X", x), columns);
        long best = Long.MAX_VALUE;
        String verdict = "";
        for (int run = 0; run < 4; run++) {
          long start = System.nanoTime();
          List<VarkaConjunctSpec> specs = VarkaExpressionCompiler.explainPredicate(e, columns,
              VarkaMatrixRuns.BASE, VarkaAdmission.NO_HOST_REFUSAL);
          long took = System.nanoTime() - start;
          if (run > 0) {
            best = Math.min(best, took);
          }
          List<String> parts = new ArrayList<>();
          for (VarkaConjunctSpec spec : specs) {
            parts.add(spec.fused() ? "fused"
                : spec.decline().map(d -> d.reason()).orElse("declined"));
          }
          verdict = String.join("; ", parts);
          if (took > 3_000_000_000L) {
            best = Math.min(best, took);
            break;
          }
        }
        System.out.println(String.format(Locale.ROOT, "VARKA_PLAN %s %d %.2f %s", chain.name(),
            depth, best / 1e6, verdict.length() > 90 ? verdict.substring(0, 90) : verdict));
        if (best > 3_000_000_000L) {
          break;
        }
      }
    }
  }
}
