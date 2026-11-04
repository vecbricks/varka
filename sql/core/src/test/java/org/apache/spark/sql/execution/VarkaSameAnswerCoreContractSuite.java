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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import javax.annotation.Nullable;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BaseFixedWidthVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DecimalVector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.QueryTest$;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSameAnswer.Level;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSameAnswerContractSuite;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSameAnswerContractSuite.Pair;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuite;
import org.apache.spark.sql.execution.varka.VarkaKernelRunner;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.types.DecimalType;
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.util.ArrowUtils;

/**
 * The sql/core twin of {@link VarkaSameAnswerContractSuite} (VARKA-556): the same table of
 * tricky pairs, held against the comparators only sql/core reaches. The both-bodies check is
 * {@link VarkaKernelRunner#compareBodies} itself, over one-row Arrow outputs built from Spark's
 * field for the pair's type, with the evaluator's width and floating flag, so its {@code TYPE}
 * verdict is the main code's; one null-free row is a batch the running check skips, and its
 * nullable and selection rows are {@code VarkaEvaluatorComponentsSuite}'s.
 * Spark's {@code checkAnswer} is held at {@code ANY_NAN} through its row comparison
 * ({@code QueryTest.sameRows}), the level its suites stay on (section 9.2 of
 * {@code m8/VARKA-556.md}).
 */
@VarkaSuite
public final class VarkaSameAnswerCoreContractSuite {

  /** A comparator under contract: its verdict on a pair, or null where it reads no such pair. */
  private record Registered(String name, Level level, Function<Pair, Boolean> agrees) {}

  private static final List<Registered> COMPARATORS = List.of(
      new Registered("VarkaKernelRunner.compareBodies", Level.TYPE,
          VarkaSameAnswerCoreContractSuite::bodiesAgree),
      new Registered("VarkaExpressionHarness.sameRow with no open column", Level.EXACT,
          p -> VarkaExpressionHarness.sameRow(row(p.want()), row(p.got()),
              new DataType[] {p.type()}, null) == null),
      new Registered("VarkaExpressionHarness.sameRow with unfixedNaN", Level.ROW,
          p -> VarkaExpressionHarness.sameRow(row(p.want()), row(p.got()),
              new DataType[] {p.type()},
              o -> VarkaExpressionHarness.unfixedNaN(p.expr(), VarkaSameAnswerContractSuite.ROW))
              == null),
      new Registered("checkAnswer's row comparison, QueryTest.sameRows", Level.ANY_NAN,
          p -> QueryTest$.MODULE$.sameRows(rows(p.want()), rows(p.got()), true).isEmpty()),
      // VarkaSparkFuzz.outcomeOf compares a query's rows by Row.toString. Collected rows carry a
      // decimal at its type's scale, which a pair carried at another does not, so its decimal
      // pairs are not this rule's.
      new Registered("VarkaSparkFuzz's comparison, Row.toString, decimals apart", Level.ANY_NAN,
          p -> p.type() instanceof DecimalType ? null
              : external(p.want()).toString().equals(external(p.got()).toString())));

  @Test
  @DisplayName("every sql/core comparator gives every pair of the table its level's verdict")
  void everyComparatorAtItsLevel() {
    var failures = new ArrayList<String>();
    int compared = 0;
    int bodies = 0;
    for (Registered c : COMPARATORS) {
      for (Pair p : VarkaSameAnswerContractSuite.pairs()) {
        Boolean agrees = c.agrees().apply(p);
        if (agrees == null) {
          continue;
        }
        compared++;
        bodies += c.level() == Level.TYPE ? 1 : 0;
        if (agrees != p.verdict(c.level())) {
          failures.add(c.name() + " (" + c.level() + ") " + (agrees ? "accepts" : "rejects")
              + " " + p.name() + ": want " + p.want() + ", got " + p.got());
        }
      }
    }
    assertTrue(failures.isEmpty(), String.join("\n", failures));
    int size = VarkaSameAnswerContractSuite.pairs().size();
    // The four decimals the type cannot hold are the only pairs no body writes.
    assertEquals(size - 4, bodies, "the pairs the both-bodies check read");
    assertTrue(compared > 4 * size, "the comparators read too few pairs: " + compared);
  }

  // ---------------------------------------------------------------------------------------------
  // The both-bodies check over one row of each body's output.
  // ---------------------------------------------------------------------------------------------

  /** Whether compareBodies accepts {@code want} against {@code got}; null where none is written. */
  private static @Nullable Boolean bodiesAgree(Pair p) {
    try (BufferAllocator allocator =
             ArrowUtils.rootAllocator().newChildAllocator("varka-contract", 0, Long.MAX_VALUE);
         BaseFixedWidthVector served = output(p.type(), allocator);
         BaseFixedWidthVector other = output(p.type(), allocator)) {
      if (!write(served, p.want()) || !write(other, p.got())) {
        return null;
      }
      try {
        VarkaKernelRunner.compareBodies("contract", 1, true, new long[] {0L}, new int[] {0},
            new int[] {VarkaKernelRunner.outputWidth(served)},
            new boolean[] {VarkaKernelRunner.floatingOutput(served)},
            new VarkaKernelRunner.BodyOutputs(new long[] {served.getDataBufferAddress()},
                new long[] {served.getValidityBufferAddress()}),
            new VarkaKernelRunner.BodyOutputs(new long[] {other.getDataBufferAddress()},
                new long[] {other.getValidityBufferAddress()}));
        return true;
      } catch (IllegalStateException e) {
        return false;
      }
    }
  }

  /** One row of an output of {@code type}, in the vector Spark's field for it builds. */
  private static BaseFixedWidthVector output(DataType type, BufferAllocator allocator) {
    var v = (BaseFixedWidthVector) ArrowUtils.toArrowField("o", type, true, null, false,
        Metadata.empty(), false).createVector(allocator);
    v.allocateNew(1);
    v.setValueCount(1);
    return v;
  }

  /**
   * Writes {@code value} as the kernel stores it: a floating value's raw bits, a decimal's
   * unscaled value at the type's scale, a boolean's data bit, and a null's validity bit clear
   * over whatever data it holds. False where the type cannot hold the decimal.
   */
  private static boolean write(BaseFixedWidthVector v, @Nullable Object value) {
    if (value instanceof InternalRow stale) {
      // A null over another value's bits: the data is written, the validity bit left clear.
      v.getDataBuffer().setLong(0, Double.doubleToRawLongBits(stale.getDouble(0)));
      return true;
    }
    switch (value) {
      case null -> {
        return true;
      }
      case Double d -> v.getDataBuffer().setLong(0, Double.doubleToRawLongBits(d));
      case Float f -> v.getDataBuffer().setInt(0, Float.floatToRawIntBits(f));
      case Boolean b -> ((BitVector) v).set(0, b ? 1 : 0);
      case Decimal d -> {
        var vector = (DecimalVector) v;
        BigDecimal at;
        try {
          at = d.toJavaBigDecimal().setScale(vector.getScale());
        } catch (ArithmeticException e) {
          return false;
        }
        if (at.precision() > vector.getPrecision()) {
          return false;
        }
        vector.set(0, at);
      }
      default -> throw new IllegalArgumentException("no output holds " + value);
    }
    v.setIndexDefined(0);
    return true;
  }

  // ---------------------------------------------------------------------------------------------
  // A pair's answers as each comparator reads them.
  // ---------------------------------------------------------------------------------------------

  /** A one-column row of {@code value}, or {@code value} itself where it is a row. */
  private static InternalRow row(@Nullable Object value) {
    return value instanceof InternalRow r ? r : new GenericInternalRow(new Object[] {value});
  }

  /** The collected row of {@code value}: a decimal as a Java one, a null over bits as null. */
  private static Row external(@Nullable Object value) {
    return RowFactory.create(switch (value) {
      case Decimal d -> d.toJavaBigDecimal();
      case InternalRow _ -> null;
      case null -> null;
      default -> value;
    });
  }

  private static scala.collection.immutable.Seq<Row> rows(@Nullable Object value) {
    return CollectionConverters.asScala(List.of(external(value))).toSeq();
  }
}
