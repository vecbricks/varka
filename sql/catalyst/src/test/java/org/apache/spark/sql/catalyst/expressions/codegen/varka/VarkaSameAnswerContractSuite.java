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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.Function;
import javax.annotation.Nullable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import scala.Tuple2;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.Abs;
import org.apache.spark.sql.catalyst.expressions.Add;
import org.apache.spark.sql.catalyst.expressions.BoundReference;
import org.apache.spark.sql.catalyst.expressions.CaseWhen;
import org.apache.spark.sql.catalyst.expressions.Coalesce;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.catalyst.expressions.Greatest;
import org.apache.spark.sql.catalyst.expressions.If;
import org.apache.spark.sql.catalyst.expressions.IsNotNull;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.Multiply;
import org.apache.spark.sql.catalyst.expressions.NaNvl;
import org.apache.spark.sql.catalyst.expressions.NumericEvalContext$;
import org.apache.spark.sql.catalyst.expressions.Subtract;
import org.apache.spark.sql.catalyst.expressions.UnaryMinus;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSameAnswer.Level;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntNeg;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Not;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.types.DecimalType;
import org.apache.spark.sql.types.FloatType;

/**
 * The contract of "the same answer" (VARKA-556): one table of tricky pairs of answers, each with
 * the verdict every {@link Level} gives it, and every comparator the catalyst tree can call,
 * registered with its level. Each test runs one comparator over every pair and requires its
 * level's verdict, so a comparator that drifts from the rule it names fails here, on a named
 * pair, instead of on a matrix run whose JIT tier happened to split two NaNs.
 *
 * <p>The row every pair is evaluated on holds three doubles that are NaNs of different bits,
 * {@code x}, {@code x2} and {@code x3}, and two floats that are, {@code f} and {@code f2}. A pair
 * built by {@link #otherNaN} compares Spark's answer with the other column NaN, the answer a
 * wrong pick or another tier would give; a pair built by {@link #explicit} names both answers.
 */
@VarkaSuite
public final class VarkaSameAnswerContractSuite {

  private static final long X = 0x7ff8000000000001L;
  private static final long X2 = 0xfff8000000000000L;
  private static final long X3 = 0x7ff8000000000002L;
  private static final int F = 0x7fc00001;
  private static final int F2 = 0xffc00000;

  private static final InternalRow ROW = new GenericInternalRow(new Object[] {
      Double.longBitsToDouble(X), Double.longBitsToDouble(X2), Double.longBitsToDouble(X3),
      Float.intBitsToFloat(F), Float.intBitsToFloat(F2)});
  /** The same row as the IR's reference evaluator reads it: raw bits a column, x3 a literal. */
  private static final long[] IR_ROW = {X, X2, X3, 0L, 0L};
  private static final long[] IR_LITS = {X3};

  private static final Expression x = col(0, DataTypes.DoubleType);
  private static final Expression x2 = col(1, DataTypes.DoubleType);
  private static final Expression x3 = col(2, DataTypes.DoubleType);
  private static final Expression f = col(3, DataTypes.FloatType);
  private static final Expression f2 = col(4, DataTypes.FloatType);
  private static final VarkaVectorIR X_IR = new ColumnRef(0, LaneType.DOUBLE);
  private static final VarkaVectorIR X2_IR = new ColumnRef(1, LaneType.DOUBLE);
  private static final VarkaVectorIR X3_IR = new ColumnRef(2, LaneType.DOUBLE);

  /**
   * One pair of answers, {@code want} (Spark's) and {@code got}, of an output of {@code type}
   * computed by {@code expr} (and by {@code ir} where the IR can state it) on {@link #ROW}, with
   * the verdict of each level: whether the two agree.
   */
  private record Pair(String name, DataType type, Expression expr, @Nullable VarkaVectorIR ir,
      @Nullable Object want, @Nullable Object got, boolean exact, boolean typeLevel,
      boolean row) {
    boolean verdict(Level level) {
      return switch (level) {
        case EXACT -> exact;
        case TYPE -> typeLevel;
        case ROW -> row;
        case ANY_NAN -> throw new IllegalArgumentException("no comparator here is ANY_NAN");
      };
    }
  }

  /** A comparator under contract: its verdict on a pair, or null where it reads no such pair. */
  private record Registered(String name, Level level, Function<Pair, Boolean> agrees) {}

  private static final List<Pair> TABLE = table();

  private static final List<Registered> COMPARATORS = List.of(
      new Registered("VarkaSameAnswer.sameRow with no open column", Level.EXACT,
          p -> VarkaSameAnswer.sameRow(row(p.want()), row(p.got()), new DataType[] {p.type()},
              null) == null),
      new Registered("VarkaKernelRunner.compareBodies' row rule: equal bits, or "
          + "VarkaSameAnswer.bothNaN on a floating output", Level.TYPE,
          p -> {
            Long want = bits(p.want());
            Long got = bits(p.got());
            if (want == null || got == null) {
              return null;
            }
            int width = p.type() instanceof FloatType ? Float.BYTES : Double.BYTES;
            return want.equals(got) || VarkaSameAnswer.bothNaN(width, want, got);
          }),
      new Registered("VarkaSameAnswer.sameRow over the expression (VarkaExpressionHarness)",
          Level.ROW,
          p -> VarkaSameAnswer.sameRow(row(p.want()), row(p.got()), new DataType[] {p.type()},
              o -> VarkaSameAnswer.openNaN(p.expr(), ROW)) == null),
      new Registered("VarkaSameAnswer.sameAnswer over the IR (VarkaKernelChecks)", Level.ROW,
          p -> {
            Long want = bits(p.want());
            Long got = bits(p.got());
            if (p.ir() == null || want == null || got == null) {
              return null;
            }
            return VarkaSameAnswer.sameAnswer(p.ir(), want, got, reference(IR_ROW));
          }));

  private static List<Pair> table() {
    var t = new ArrayList<Pair>();
    // Open: an arithmetic over two NaNs of different bits, which any tier may answer with
    // either operand's payload.
    t.add(otherNaN("x + x2", add(x, x2), arith(IntOp.ADD, X_IR, X2_IR), true));
    t.add(otherNaN("x - x2", new Subtract(x, x2, ctx()), arith(IntOp.SUB, X_IR, X2_IR), true));
    t.add(otherNaN("x * x2", new Multiply(x, x2, ctx()), arith(IntOp.MUL, X_IR, X2_IR), true));
    t.add(otherNaN("x + a NaN literal of other bits",
        add(x, Literal.create(Double.longBitsToDouble(X3), DataTypes.DoubleType)),
        arith(IntOp.ADD, X_IR, new LiteralSlot(0, LaneType.DOUBLE)), true));
    t.add(otherNaN("f + f2", add(f, f2), null, true));
    t.add(otherNaN("-(x + x2)", new UnaryMinus(add(x, x2), false),
        new IntNeg(Overflow.WRAP, arith(IntOp.ADD, X_IR, X2_IR), false), true));
    t.add(otherNaN("IF(x IS NOT NULL, x + x2, 0.0)",
        new If(new IsNotNull(x), add(x, x2), Literal.create(0.0d, DataTypes.DoubleType)),
        new IfElse(new VarkaVectorIR.IsNotNull(X_IR), arith(IntOp.ADD, X_IR, X2_IR), X3_IR),
        true));
    // Fixed: a pick or a one-operand operation returns a NaN whose bits follow from its input.
    t.add(otherNaN("-x", new UnaryMinus(x, false), new IntNeg(Overflow.WRAP, X_IR, false),
        false));
    t.add(otherNaN("abs(x)", new Abs(x, false), new IntNeg(Overflow.WRAP, X_IR, true), false));
    t.add(otherNaN("coalesce(x, x2)", new Coalesce(seq(x, x2)),
        new IfElse(new VarkaVectorIR.IsNotNull(X_IR), X_IR, X2_IR), false));
    t.add(otherNaN("greatest(x, x2)", new Greatest(seq(x, x2)),
        new VarkaVectorIR.Greatest(X_IR, X2_IR), false));
    t.add(otherNaN("nanvl(x, x2)", new NaNvl(x, x2),
        new IfElse(new Not(new Compare(CompareOp.EQ, X_IR, X_IR)), X2_IR, X_IR), false));
    t.add(otherNaN("IF(x IS NOT NULL, x, x2)", new If(new IsNotNull(x), x, x2),
        new IfElse(new VarkaVectorIR.IsNotNull(X_IR), X_IR, X2_IR), false));
    t.add(otherNaN("coalesce(x3, x + x2), returning x3 beside an open sum",
        new Coalesce(seq(x3, add(x, x2))),
        new IfElse(new VarkaVectorIR.IsNotNull(X3_IR), X3_IR, arith(IntOp.ADD, X_IR, X2_IR)),
        false));
    t.add(otherNaN("CASE WHEN x IS NOT NULL THEN x3 ELSE x + x2 END",
        new CaseWhen(CollectionConverters.asScala(List.of(
            new Tuple2<Expression, Expression>(new IsNotNull(x), x3))).toSeq(),
            scala.Option.apply(add(x, x2))),
        new IfElse(new VarkaVectorIR.IsNotNull(X_IR), X3_IR, arith(IntOp.ADD, X_IR, X2_IR)),
        false));
    t.add(otherNaN("greatest(f, f2)", new Greatest(seq(f, f2)), null, false));
    // Pairs no level excuses, over an open expression to show that even it does not.
    Expression open = add(x, x2);
    VarkaVectorIR openIr = arith(IntOp.ADD, X_IR, X2_IR);
    double nan = Double.longBitsToDouble(X);
    t.add(explicit("the same NaN on both sides", open, openIr, nan, nan, true, true, true));
    t.add(explicit("a NaN against a number", open, openIr, nan, 1.0d, false, false, false));
    t.add(explicit("a number against a NaN", open, openIr, 1.0d, nan, false, false, false));
    t.add(explicit("+0.0 against -0.0", open, openIr, 0.0d, -0.0d, false, false, false));
    t.add(explicit("FLOAT +0.0 against -0.0", add(f, f2), null, 0.0f, -0.0f, false, false,
        false));
    t.add(explicit("null against null", open, null, null, null, true, true, true));
    t.add(explicit("null against a value", open, null, null, 1.0d, false, false, false));
    // Decimals: the unscaled value at the output's scale, the same at every level.
    DecimalType d10 = DataTypes.createDecimalType(10, 2);
    DecimalType d38 = DataTypes.createDecimalType(38, 4);
    t.add(decimal("DECIMAL(10,2) 1.50 against 1.5 carried at scale 1", d10, "1.50", "1.5", true));
    t.add(decimal("DECIMAL(10,2) 1.50 against the unscaled 150 stored at scale 4", d10, "1.50",
        "0.0150", false));
    t.add(decimal("DECIMAL(10,2) 1.23 against 1.234, which the type cannot hold", d10, "1.23",
        "1.234", false));
    t.add(decimal("DECIMAL(10,2) 12345678.90 against 123456789.0, past the precision", d10,
        "12345678.90", "123456789.0", false));
    String wide = "12345678901234567890123456789012.3456";
    t.add(decimal("DECIMAL(38,4) against the same value carried at scale 6", d38, wide,
        wide + "00", true));
    t.add(decimal("DECIMAL(38,4) against its unscaled value stored at scale 6", d38, wide,
        new BigDecimal(new BigDecimal(wide).unscaledValue(), 6).toPlainString(), false));
    return List.copyOf(t);
  }

  @Test
  @DisplayName("every comparator gives every pair of the table its level's verdict")
  void everyComparatorAtItsLevel() {
    var failures = new ArrayList<String>();
    int compared = 0;
    for (Registered c : COMPARATORS) {
      for (Pair p : TABLE) {
        Boolean agrees = c.agrees().apply(p);
        if (agrees == null) {
          continue;
        }
        compared++;
        if (agrees != p.verdict(c.level())) {
          failures.add(c.name() + " (" + c.level() + ") " + (agrees ? "accepts" : "rejects")
              + " " + p.name() + ": want " + shown(p.want()) + ", got " + shown(p.got()));
        }
      }
    }
    assertTrue(failures.isEmpty(), String.join("\n", failures));
    assertTrue(compared > 3 * TABLE.size(), "the comparators read too few pairs: " + compared);
  }

  @Test
  @DisplayName("the levels nest: what EXACT accepts ROW accepts, and what ROW accepts TYPE does")
  void levelsNest() {
    for (Pair p : TABLE) {
      assertTrue(!p.exact() || p.row(), p.name());
      assertTrue(!p.row() || p.typeLevel(), p.name());
    }
  }

  @Test
  @DisplayName("the IR rule decides a pick by its condition, never by comparing a node's NaN "
      + "bits with a child's")
  void pickByConditionNotBits() {
    // coalesce(x, x + x2) returns x, a column NaN, so its payload is fixed. A rule that asked
    // which child holds the node's bits would find the sum as well whenever the tier that
    // evaluated it returned x's payload, and excuse the pick; planting that value for the sum
    // shows the rule reads the condition instead.
    VarkaVectorIR sum = arith(IntOp.ADD, X_IR, X2_IR);
    VarkaVectorIR pick = new IfElse(new VarkaVectorIR.IsNotNull(X_IR), X_IR, sum);
    VarkaSameAnswer.IrRow planted = new VarkaSameAnswer.IrRow() {
      @Override public OptionalLong value(VarkaVectorIR node) {
        return node.equals(sum) ? OptionalLong.of(X) : reference(IR_ROW).value(node);
      }

      @Override public boolean holds(VarkaVectorIR.Cond cond) {
        return reference(IR_ROW).holds(cond);
      }
    };
    assertFalse(VarkaSameAnswer.openNaN(pick, planted));
    assertTrue(VarkaSameAnswer.openNaN(sum, planted));
    assertFalse(VarkaSameAnswer.openNaN(new Coalesce(seq(x, add(x, x2))), ROW));
  }

  // ---------------------------------------------------------------------------------------------
  // Building the table.
  // ---------------------------------------------------------------------------------------------

  /**
   * Spark's answer of {@code expr} on the row against the other of the row's two NaNs: no
   * exact comparison accepts it, the type level always does, the row level where {@code open}.
   */
  private static Pair otherNaN(String name, Expression expr, @Nullable VarkaVectorIR ir,
      boolean open) {
    Object want = expr.eval(ROW);
    Object got;
    if (want instanceof Float w) {
      got = Float.intBitsToFloat(Float.floatToRawIntBits(w) == F ? F2 : F);
    } else {
      long w = Double.doubleToRawLongBits((Double) want);
      got = Double.longBitsToDouble(w == X ? X2 : X);
    }
    return new Pair(name, expr.dataType(), expr, ir, want, got, false, true, open);
  }

  private static Pair explicit(String name, Expression expr, @Nullable VarkaVectorIR ir,
      @Nullable Object want, @Nullable Object got, boolean exact, boolean typeLevel,
      boolean row) {
    return new Pair(name, expr.dataType(), expr, ir, want, got, exact, typeLevel, row);
  }

  private static Pair decimal(String name, DecimalType type, String want, String got,
      boolean agree) {
    Expression expr = Literal.create(null, type);
    return new Pair(name, type, expr, null, Decimal.apply(new BigDecimal(want)),
        Decimal.apply(new BigDecimal(got)), agree, agree, agree);
  }

  private static Expression col(int ordinal, DataType type) {
    return new BoundReference(ordinal, type, true);
  }

  private static Expression add(Expression l, Expression r) {
    return new Add(l, r, ctx());
  }

  private static org.apache.spark.sql.catalyst.expressions.NumericEvalContext ctx() {
    return NumericEvalContext$.MODULE$.fromSQLConf(SQLConf.get());
  }

  private static scala.collection.immutable.Seq<Expression> seq(Expression... children) {
    return CollectionConverters.asScala(List.of(children)).toSeq();
  }

  private static VarkaVectorIR arith(IntOp op, VarkaVectorIR l, VarkaVectorIR r) {
    return new IntArith(op, Overflow.WRAP, l, r);
  }

  /** The reference evaluator's view of {@code row}, no column null. */
  private static VarkaSameAnswer.IrRow reference(long[] row) {
    boolean[] isNull = new boolean[row.length];
    return new VarkaSameAnswer.IrRow() {
      @Override public OptionalLong value(VarkaVectorIR node) {
        return VarkaReference.longValue(node, row, isNull, IR_LITS);
      }

      @Override public boolean holds(VarkaVectorIR.Cond cond) {
        return VarkaReference.longCond(cond, row, isNull, IR_LITS).isTrue();
      }
    };
  }

  private static InternalRow row(@Nullable Object value) {
    return new GenericInternalRow(new Object[] {value});
  }

  /** A floating answer's raw bits, a FLOAT's in the low 32; null for anything else. */
  private static @Nullable Long bits(@Nullable Object v) {
    if (v instanceof Double d) {
      return Double.doubleToRawLongBits(d);
    }
    if (v instanceof Float fl) {
      return Integer.toUnsignedLong(Float.floatToRawIntBits(fl));
    }
    return null;
  }

  private static String shown(@Nullable Object v) {
    Long b = bits(v);
    return b == null ? String.valueOf(v) : v + " (0x" + Long.toHexString(b) + ")";
  }
}
