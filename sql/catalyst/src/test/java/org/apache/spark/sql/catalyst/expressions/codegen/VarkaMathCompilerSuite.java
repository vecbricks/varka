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
package org.apache.spark.sql.catalyst.expressions.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaAdmission;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFamily;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelPlan;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrixRuns;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSqlResolve;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuite;
import org.apache.spark.sql.types.DataTypes;

/**
 * The math family's claim (VARKA-345 345a, VARKA-356): each of Spark's 27 math functions over
 * {@code DOUBLE} columns is claimed by the family; the ones built fuse, each other declines naming
 * the row of the plan that fuses it, and with the family's switch off every one names the switch
 * instead. The tables below are written out rather than read from {@code VarkaMathCompiler}, so a
 * function the arm forgets, or gives another row, fails here; and a function that starts fusing
 * fails here too, which is the prompt to move it into the fused table and the coverage table.
 */
@VarkaSuite
public final class VarkaMathCompilerSuite {

  private static final Seq<Attribute> COLUMNS = CollectionConverters.asScala(List.<Attribute>of(
      VarkaSqlResolve.column("x", DataTypes.DoubleType),
      VarkaSqlResolve.column("y", DataTypes.DoubleType),
      VarkaSqlResolve.column("i", DataTypes.IntegerType))).toSeq();

  /**
   * The functions that fuse, as SQL: row 356's one-argument functions, row 358's but {@code pow}
   * and {@code hypot}, {@code log} with a constant and with a column base, and two int operands,
   * which reach the call through their cast to double, lowered by the floating casts family.
   */
  private static final List<String> FUSED = Stream.concat(Stream.of("sin", "cos", "tan", "asin",
      "acos", "atan", "sec", "csc", "cot", "exp", "expm1", "ln", "log", "log10", "log1p", "log2",
      "cbrt").map(f -> f + "(x)"), Stream.of("log(2.0D, x)", "log(y, x)", "sin(i)", "exp(i)"))
      .toList();

  /** Each other function as SQL, with the name its reason gives and the row that fuses it. */
  private static final Map<String, String> FUNCTIONS = functions();

  private static Map<String, String> functions() {
    var m = new LinkedHashMap<String, String>();
    m.put("atan2(y, x)", "atan2 is row 356's");
    for (String f : List.of("sinh", "cosh", "tanh", "asinh", "acosh", "atanh")) {
      m.put(f + "(x)", f + " is row 357's");
    }
    m.put("pow(x, y)", "pow is row 358's");
    m.put("hypot(x, y)", "hypot is row 358's");
    return Collections.unmodifiableMap(m);
  }

  /** Every function as SQL, the fused and the declined. */
  private static List<String> allFunctions() {
    return Stream.concat(FUSED.stream(), FUNCTIONS.keySet().stream()).toList();
  }

  /** The reason {@code sql} declines with, or empty where it fuses. */
  private static Optional<String> outcome(String sql, Set<VarkaFamily> off,
      VarkaEmitOptions options) {
    Expression e = VarkaSqlResolve.parse(sql, COLUMNS);
    Seq<NamedExpression> entries =
        CollectionConverters.asScala(List.of(VarkaSqlResolve.named(e, "o"))).toSeq();
    var declines =
        VarkaExpressionCompiler.declines(entries, COLUMNS, options, new VarkaAdmission(off, false));
    return declines.containsKey(0) ? Optional.of(declines.get(0).reason()) : Optional.empty();
  }

  private static String reason(String sql, Set<VarkaFamily> off, VarkaEmitOptions options) {
    return outcome(sql, off, options).orElseThrow(() -> new AssertionError(sql + " fused"));
  }

  @Test
  @DisplayName("every math function not built declines naming the row that fuses it")
  void eachFunctionNamesItsRow() {
    assertEquals(27, FUSED.size() + FUNCTIONS.size() - 3,
        "the 27 functions, two int operands and a column base");
    var failures = new ArrayList<String>();
    for (var f : FUNCTIONS.entrySet()) {
      for (boolean contract : List.of(false, true)) {
        String why = reason(f.getKey(), Set.of(),
            VarkaMatrixRuns.BASE.withMathUlpContract(contract));
        if (!why.equals(f.getValue() + ": not fused yet")) {
          failures.add(f.getKey() + (contract ? " (contract on)" : "") + ": " + why);
        }
      }
    }
    assertEquals(List.of(), failures);
  }

  @Test
  @DisplayName("the built functions fuse, with the contract off and on")
  void builtFunctionsFuse() {
    var failures = new ArrayList<String>();
    for (String sql : FUSED) {
      for (boolean contract : List.of(false, true)) {
        VarkaEmitOptions options = VarkaMatrixRuns.BASE.withMathUlpContract(contract);
        outcome(sql, Set.of(), options)
            .filter(why -> !VarkaMatrixRuns.isUnbakedWidthDecline(options, why))
            .ifPresent(why -> failures.add(sql + (contract ? " (contract on)" : "") + ": " + why));
      }
    }
    assertEquals(List.of(), failures);
  }

  @Test
  @DisplayName("a log with a constant base at or below 0.0 declines, every row being null")
  void constantBaseAtOrBelowZeroDeclines() {
    for (String sql : List.of("log(0.0D, x)", "log(-0.0D, x)", "log(-2.0D, x)")) {
      String why = reason(sql, Set.of(), VarkaMatrixRuns.BASE);
      assertTrue(why.contains("a log with a constant base at or below 0.0"), sql + ": " + why);
    }
  }

  @Test
  @DisplayName("a per-lane call declines at a width with no baked lane count")
  void callNeedsBakedLanes() {
    String why = reason("sin(x)", Set.of(), VarkaMatrixRuns.BASE.withValidityByWidth(false));
    assertTrue(why.contains(VarkaKernelPlan.NO_BAKED_LANES_FOR_A_CALL), why);
  }

  @Test
  @DisplayName("with the math family switched off every math function names the switch")
  void switchedOffNamesTheSwitch() {
    var failures = new ArrayList<String>();
    for (String sql : allFunctions()) {
      String why = reason(sql, Set.of(VarkaFamily.MATH), VarkaMatrixRuns.BASE);
      if (!why.equals(VarkaFamily.MATH.offReason())) {
        failures.add(sql + ": " + why);
      }
    }
    assertEquals(List.of(), failures);
  }

  @Test
  @DisplayName("the family claims exactly the math classes, and nothing beside them")
  void claimsOnlyMathClasses() {
    for (String sql : allFunctions()) {
      Expression e = VarkaSqlResolve.parse(sql, COLUMNS);
      assertTrue(VarkaMathCompiler.ownerOf(e).isPresent(), sql);
    }
    for (String sql : List.of("x + y", "sqrt(x)", "abs(x)", "x", "rint(x)")) {
      Expression e = VarkaSqlResolve.parse(sql, COLUMNS);
      assertTrue(VarkaMathCompiler.ownerOf(e).isEmpty(), sql);
    }
  }
}
