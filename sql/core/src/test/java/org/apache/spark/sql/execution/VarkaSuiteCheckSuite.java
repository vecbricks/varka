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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import scala.Option;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.Add;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.AttributeReference;
import org.apache.spark.sql.catalyst.expressions.BoundReference;
import org.apache.spark.sql.catalyst.expressions.Cast;
import org.apache.spark.sql.catalyst.expressions.EvalMode;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.NumericEvalContext$;
import org.apache.spark.sql.catalyst.expressions.Round;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrixRuns;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuite;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.unsafe.types.UTF8String;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The lift and the verdicts of {@link VarkaSuiteCheck}, the work behind Spark's cast suites run
 * under Varka ({@code m8/VARKA-368.md} 3 items 2 and 4), on expressions built here.
 */
@VarkaSuite
public final class VarkaSuiteCheckSuite {

  @AfterEach
  void matrixOptions() {
    VarkaColumnarToRowExec$.MODULE$.setEmitOptionsForTesting(VarkaMatrixRuns.BASE);
  }

  private static List<Attribute> columns(VarkaSuiteCheck.Lifted lifted) {
    return CollectionConverters.asJava(lifted.fixture().columns());
  }

  @Test
  @DisplayName("a null literal becomes a nullable column whose every row is null")
  void nullLiteralIsNullableColumn() {
    var lifted = VarkaSuiteCheck.lift(
        new Cast(Literal.create(null, DataTypes.LongType), DataTypes.IntegerType,
            Option.empty(), EvalMode.LEGACY()), InternalRow.empty());
    assertEquals(1, lifted.lifted());
    Attribute c = columns(lifted).get(0);
    assertTrue(c.nullable());
    assertEquals(VarkaSuiteCheck.LIFTED_ROWS, lifted.fixture().rows().size());
    for (InternalRow row : lifted.fixture().rows()) {
      assertTrue(row.isNullAt(0));
    }
  }

  @Test
  @DisplayName("a TRY cast's literal is lifted into a column that is not nullable")
  void tryCastIsLifted() {
    var lifted = VarkaSuiteCheck.lift(
        new Cast(Literal.create(1L << 40, DataTypes.LongType), DataTypes.IntegerType,
            Option.empty(), EvalMode.TRY()), InternalRow.empty());
    assertTrue(lifted.expression() instanceof Cast c && c.child() instanceof AttributeReference
        && c.evalMode() == EvalMode.TRY());
    assertFalse(columns(lifted).get(0).nullable());
    assertEquals(1L << 40, lifted.fixture().rows().get(3).getLong(0));
    assertEquals(3, lifted.fixture().rows().get(3).getInt(lifted.fixture().ordinal("rid")));
  }

  @Test
  @DisplayName("a literal of a type no kernel reads stays a literal, and nothing is lifted")
  void nonLaneLiteralStays() {
    var lifted = VarkaSuiteCheck.lift(
        new Cast(Literal.create(UTF8String.fromString("12"), DataTypes.StringType),
            DataTypes.IntegerType, Option.empty(), EvalMode.ANSI()), InternalRow.empty());
    assertEquals(0, lifted.lifted());
    assertTrue(((Cast) lifted.expression()).child() instanceof Literal);
  }

  @Test
  @DisplayName("a bound reference becomes a column holding the input row's value")
  void boundReferenceIsLifted() {
    var lifted = VarkaSuiteCheck.lift(
        new Cast(new BoundReference(1, DataTypes.LongType, true), DataTypes.IntegerType,
            Option.empty(), EvalMode.ANSI()), new GenericInternalRow(new Object[] {7, 42L}));
    assertEquals(1, lifted.lifted());
    assertEquals(42L, lifted.fixture().rows().get(0).getLong(0));
  }

  @Test
  @DisplayName("a lift that leaves an argument which must stay foldable is unresolved")
  void unresolvableLift() {
    var lifted = VarkaSuiteCheck.lift(
        new Round(Literal.create(1.5d, DataTypes.DoubleType),
            Literal.create(0, DataTypes.IntegerType)), InternalRow.empty());
    assertNotNull(VarkaSuiteCheck.unresolved(lifted.expression()));
    assertNull(VarkaSuiteCheck.unresolved(VarkaSuiteCheck.lift(new Cast(
        Literal.create(1L, DataTypes.LongType), DataTypes.IntegerType, Option.empty(),
        EvalMode.ANSI()), InternalRow.empty()).expression()));
  }

  @Test
  @DisplayName("a check passes where the kernel answers as Spark does")
  void fusedCheckPasses() {
    // A suite name of its own, so that the counts are this check's alone.
    String suite = "VarkaSuiteCheckSuite.fused";
    VarkaSuiteCheck.beginTest(suite, "fused");
    VarkaSuiteCheck.check(suite, new Cast(Literal.create(1L << 40, DataTypes.LongType),
        DataTypes.IntegerType, Option.empty(), EvalMode.LEGACY()), InternalRow.empty(), false,
        VarkaSuiteCheck.Expect.VALUE);
    VarkaSuiteCheck.Decision d = VarkaSuiteCheck.endTest(suite, "fused", true);
    assertEquals(VarkaSuiteCheck.Action.PASS, d.action(), d.message());
    String summary = VarkaSuiteCheck.summary(suite);
    assertTrue(summary.endsWith(" 1 fused, 1 answered by a kernel"),
        "the check did not reach a kernel: " + summary);
  }

  @Test
  @DisplayName("a kernel planted without its overflow check fails as answering a raising row")
  void plantedKernelFails() {
    // No option takes a cast's range guard away, so the planted kernel is an ANSI int add with
    // its overflow check left out (checkIntOverflow=false), through the same check.
    VarkaColumnarToRowExec$.MODULE$.setEmitOptionsForTesting(
        VarkaMatrixRuns.BASE.toBuilder().checkIntOverflow(false).build());
    SQLConf ansi = SQLConf.get().clone();
    ansi.setConfString(SQLConf.ANSI_ENABLED().key(), "true");
    Expression add = new Add(Literal.create(Integer.MAX_VALUE, DataTypes.IntegerType),
        Literal.create(1, DataTypes.IntegerType), NumericEvalContext$.MODULE$.fromSQLConf(ansi));
    VarkaSuiteCheck.beginTest("VarkaSuiteCheckSuite", "planted");
    VarkaSuiteCheck.check("VarkaSuiteCheckSuite", add, InternalRow.empty(), false,
        VarkaSuiteCheck.Expect.VALUE);
    VarkaSuiteCheck.Decision d = VarkaSuiteCheck.endTest("VarkaSuiteCheckSuite", "planted", true);
    assertEquals(VarkaSuiteCheck.Action.FAIL, d.action(), d.message());
    assertTrue(d.message().contains("[varka]")
        && d.message().contains("the kernel answered a row Spark raises"), d.message());
  }
}
