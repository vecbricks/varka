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
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFamily;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrixRuns;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSqlResolve;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuite;
import org.apache.spark.sql.types.DataTypes;

/**
 * The math family before any function fuses (VARKA-345 345a): each of Spark's 27 math functions
 * over {@code DOUBLE} columns is claimed by the family, declines naming the row of the plan that
 * fuses it, and with the family's switch off names the switch instead. The table below is
 * written out rather than read from {@code VarkaMathCompiler}, so a function the arm forgets, or
 * gives another row, fails here; and a function that starts fusing fails here too, which is the
 * prompt to move it into the coverage table.
 */
@VarkaSuite
public final class VarkaMathCompilerSuite {

  private static final Seq<Attribute> COLUMNS = CollectionConverters.asScala(List.<Attribute>of(
      VarkaSqlResolve.column("x", DataTypes.DoubleType),
      VarkaSqlResolve.column("y", DataTypes.DoubleType),
      VarkaSqlResolve.column("i", DataTypes.IntegerType))).toSeq();

  /** Each function as SQL, with the name its reason gives and the row that fuses it. */
  private static final Map<String, String> FUNCTIONS = functions();

  private static Map<String, String> functions() {
    var m = new LinkedHashMap<String, String>();
    for (String f : List.of("sin", "cos", "tan", "asin", "acos", "atan", "sec", "csc", "cot")) {
      m.put(f + "(x)", f + " is row 356's");
    }
    m.put("atan2(y, x)", "atan2 is row 356's");
    for (String f : List.of("sinh", "cosh", "tanh", "asinh", "acosh", "atanh")) {
      m.put(f + "(x)", f + " is row 357's");
    }
    for (String f : List.of("exp", "expm1", "ln", "log", "log10", "log1p", "log2", "cbrt")) {
      m.put(f + "(x)", f + " is row 358's");
    }
    m.put("log(2.0D, x)", "log is row 358's");
    m.put("pow(x, y)", "pow is row 358's");
    m.put("hypot(x, y)", "hypot is row 358's");
    // An int operand reaches the function through its cast to double, which the family claims.
    m.put("exp(i)", "exp is row 358's");
    return Collections.unmodifiableMap(m);
  }

  private static String reason(String sql, Set<VarkaFamily> off, VarkaEmitOptions options) {
    Expression e = VarkaSqlResolve.parse(sql, COLUMNS);
    Seq<NamedExpression> entries =
        CollectionConverters.asScala(List.of(VarkaSqlResolve.named(e, "o"))).toSeq();
    var declines = VarkaExpressionCompiler.declines(entries, COLUMNS, options, off);
    assertTrue(declines.containsKey(0), sql + " fused");
    return declines.get(0).reason();
  }

  @Test
  @DisplayName("every math function declines naming the row that fuses it, in either mode")
  void eachFunctionNamesItsRow() {
    assertEquals(27, FUNCTIONS.size() - 1, "the 27 functions and one int operand");
    var failures = new ArrayList<String>();
    for (var f : FUNCTIONS.entrySet()) {
      for (boolean contract : List.of(false, true)) {
        String why = reason(f.getKey(), Set.of(),
            VarkaMatrixRuns.BASE.withMathUlpContract(contract));
        if (!why.equals(f.getValue() + ": no math function fuses yet")) {
          failures.add(f.getKey() + (contract ? " (contract on)" : "") + ": " + why);
        }
      }
    }
    assertEquals(List.of(), failures);
  }

  @Test
  @DisplayName("with the math family switched off every math function names the switch")
  void switchedOffNamesTheSwitch() {
    var failures = new ArrayList<String>();
    for (String sql : FUNCTIONS.keySet()) {
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
    for (String sql : FUNCTIONS.keySet()) {
      Expression e = VarkaSqlResolve.parse(sql, COLUMNS);
      assertTrue(VarkaMathCompiler.ownerOf(e).isPresent(), sql);
    }
    for (String sql : List.of("x + y", "sqrt(x)", "abs(x)", "x", "rint(x)")) {
      Expression e = VarkaSqlResolve.parse(sql, COLUMNS);
      assertTrue(VarkaMathCompiler.ownerOf(e).isEmpty(), sql);
    }
  }
}
