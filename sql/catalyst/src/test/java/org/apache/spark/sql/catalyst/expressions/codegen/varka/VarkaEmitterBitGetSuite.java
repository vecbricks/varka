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
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import scala.Option;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.BitwiseCount;
import org.apache.spark.sql.catalyst.expressions.BitwiseGet;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.LessThan;
import org.apache.spark.sql.catalyst.expressions.Literal$;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaDecline;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.Batch;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.CallHook;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.LongBatch;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.SparkAnswers;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;

/**
 * {@code bit_get} (and its alias {@code getbit}) at every integral target type, and
 * {@code bit_count} of a {@code BOOLEAN} (VARKA-335, row 335b), through
 * {@link VarkaExpressionCompiler} and the emitted class on both narrow arms, every answered row
 * held to {@link VarkaSparkOracle}, Spark's own {@code eval}, and to the reference.
 *
 * <p>{@code bit_get} raises {@code BIT_POSITION_RANGE} in every mode for a position outside
 * {@code [0, w)}, {@code w} the target's width. A literal position there declines at compile
 * time; a column position is guarded, and a batch declines exactly where a live position is out
 * of range - every batch Spark raises on, and also one whose out-of-range position sits on a row
 * with a null target, where Spark answers null and the row engine answers it in turn. The narrow
 * targets are exhausted against every position, both inside the range and on its edges; the int
 * target is swept in {@code VarkaEmitterBitwiseSuite}, and both wide targets meet their edge
 * values here at every position.
 *
 * <p>In its own JVM: the native arm needs a narrow species beside the int one.
 */
@VarkaSuite(ownJvm = true)
public final class VarkaEmitterBitGetSuite {

  private record Arm(String name, VarkaEmitOptions options) {}

  /** One compiled projection with what its rows are checked against. */
  private record Kernel(String name, byte[] bytes, List<VarkaVectorIR> roots, int numInputs,
      int[] lits, long[] longLits, boolean longLane, VarkaSparkOracle oracle) {}

  /** A target type: its column and its width in bits, the positions {@code bit_get} admits. */
  private record Target(String sql, DataType spark, Attribute column, int width) {}

  /** Both arms, pinned as `VarkaEmitterIntegralCastSuite` pins them, the native one first. */
  private static final List<Arm> ARMS = List.of(
      new Arm("the native arm", VarkaMatrixRuns.BASE.toBuilder().narrowLanes(true).build()),
      new Arm("the widened arm", VarkaMatrixRuns.BASE.toBuilder().narrowLanes(false).build()));

  private static final Target TINYINT = target("TINYINT", DataTypes.ByteType, "y", 8);
  private static final Target SMALLINT = target("SMALLINT", DataTypes.ShortType, "s", 16);
  private static final Target INT = target("INT", DataTypes.IntegerType, "i", 32);
  private static final Target BIGINT = target("BIGINT", DataTypes.LongType, "l", 64);

  /** The position column, an {@code INT} as Spark's implicit cast makes every position. */
  private static final Attribute POS = VarkaSqlResolve.column("p", DataTypes.IntegerType);

  /** The positions past the range that a guard must catch, beside the target's own width. */
  private static final int[] OUTSIDE = {-1, 64, 65, Integer.MIN_VALUE, Integer.MAX_VALUE};

  private static final AtomicInteger CLASS_COUNTER = new AtomicInteger();

  private static Target target(String sql, DataType spark, String column, int width) {
    return new Target(sql, spark, VarkaSqlResolve.column(column, spark), width);
  }

  /** Whether {@code reason} is a width the configuration cannot load or store a narrow lane at. */
  private static boolean widthDecline(String reason) {
    return reason.contains("has no load at") || reason.contains("has no widening load at")
        || reason.contains("needs a baked");
  }

  /** {@code expr} over {@code columns} compiled and emitted at {@code options}, or the reason. */
  private static Object compile(Expression expr, List<Attribute> columns,
      VarkaEmitOptions options) {
    NamedExpression entry = VarkaSqlResolve.named(expr, "c");
    var entries = CollectionConverters.asScala(List.of(entry)).toSeq();
    var cols = CollectionConverters.asScala(columns).toSeq();
    Optional<CompiledVarkaProjection> plan =
        VarkaExpressionCompiler.compile(entries, cols, options);
    if (plan.isEmpty()) {
      return VarkaExpressionCompiler.declines(entries, cols, options).values().stream()
          .findFirst().map(VarkaDecline::reason).orElse("declined with no reason");
    }
    CompiledVarkaProjection p = plan.get();
    String name = "org.apache.spark.sql.varka.execution.VarkaBitGet"
        + CLASS_COUNTER.incrementAndGet();
    byte[] bytes = VarkaLoopEmitter.emit(name, p.outputs(), p.inputOrdinals().size(),
        p.numLiterals(), null, null, options);
    var oracle = new VarkaSparkOracle(CollectionConverters.asScala(List.of(expr)).toSeq(),
        CollectionConverters.asScala(p.inputOrdinals().stream().map(columns::get).toList())
            .toSeq());
    int[] lits = p.literals().stream().mapToInt(Integer::intValue).toArray();
    long[] longLits = p.longLiterals().stream().mapToLong(Long::longValue).toArray();
    return new Kernel(name, bytes, p.outputs(), p.inputOrdinals().size(), lits, longLits,
        p.lane(options).bits() == Long.SIZE, oracle);
  }

  /** Spark's answers for one batch, as {@link VarkaKernelChecks} reads them. */
  private static SparkAnswers answers(VarkaSparkOracle oracle) {
    return new SparkAnswers() {
      @Override
      public VarkaKernelChecks.Answer answer(int output, long[] values, boolean[] isNull) {
        VarkaSparkOracle.Answer a = oracle.answer(output, row(values, isNull));
        if (a instanceof VarkaSparkOracle.Value v) {
          return new VarkaKernelChecks.Answer.Value(v.v());
        } else if (a instanceof VarkaSparkOracle.Raised r) {
          return new VarkaKernelChecks.Answer.Raised(r.e());
        }
        return new VarkaKernelChecks.Answer.Null();
      }

      @Override
      public String sql(int output) {
        return oracle.sql(output);
      }
    };
  }

  private static scala.collection.immutable.Seq<Option<Object>> row(long[] values,
      boolean[] isNull) {
    var row = new ArrayList<Option<Object>>();
    for (int c = 0; c < values.length; c++) {
      row.add(isNull[c] ? Option.empty() : Option.apply((Object) values[c]));
    }
    return CollectionConverters.asScala(row).toList();
  }

  private static Batch intBatch(LongBatch b) {
    var data = new int[b.data().length][];
    for (int c = 0; c < data.length; c++) {
      data[c] = new int[b.length()];
      for (int i = 0; i < b.length(); i++) {
        data[c][i] = (int) b.data()[c][i];
      }
    }
    return new Batch(b.length(), b.nulls(), data, b.forceMasked());
  }

  /**
   * Runs {@code batches} on {@code k}, each compared row by row with the reference and Spark where
   * the kernel answers; {@code declines} says which batches must decline, and every other must
   * answer. A batch Spark raises on must be among the declined. Returns how many were answered.
   */
  private static int runAll(Kernel k, List<LongBatch> batches, IntPredicate declines,
      String where) {
    var loaded = VarkaEmitterFixtures.load(new VarkaEmitterFixtures.Emitted(k.name(),
        k.bytes()));
    int answered = 0;
    try {
      for (int n = 0; n < batches.size(); n++) {
        LongBatch b = batches.get(n);
        String ctx = where + " batch " + n + " length " + b.length();
        boolean decline = declines.test(n);
        int status = k.longLane()
            ? VarkaKernelChecks.runLoadedLong(ctx, loaded.kernel(), k.roots(), k.numInputs(),
                k.longLits(), k.lits(), b, decline, answers(k.oracle()), CallHook.NONE)
            : VarkaKernelChecks.runLoadedNarrow(ctx, loaded.kernel(), k.roots(), k.numInputs(),
                k.lits(), intBatch(b), decline, answers(k.oracle()), CallHook.NONE);
        assertEquals(decline, status != 0, ctx + ": the kernel "
            + (status != 0 ? "declined" : "answered") + " the batch");
        if (status == 0) {
          answered++;
        }
      }
    } finally {
      loaded.loader().release();
    }
    return answered;
  }

  /** Columns of {@code data} with the rows each column's predicate names null. */
  private static LongBatch batch(long[][] data, List<IntPredicate> nulls) {
    int length = data[0].length;
    var isNull = new boolean[data.length][length];
    for (int c = 0; c < data.length; c++) {
      for (int i = 0; i < length; i++) {
        isNull[c][i] = nulls.get(c).test(i);
      }
    }
    return new LongBatch(length, isNull, data, false);
  }

  /** The values a target is read at: every one of a narrow type, the edges of a wide one. */
  private static long[] values(Target t) {
    if (t.width() <= Short.SIZE) {
      long min = -(1L << (t.width() - 1));
      var out = new long[1 << t.width()];
      for (int i = 0; i < out.length; i++) {
        out[i] = min + i;
      }
      return out;
    }
    long top = t.width() == Long.SIZE ? Long.MAX_VALUE : Integer.MAX_VALUE;
    long bottom = t.width() == Long.SIZE ? Long.MIN_VALUE : Integer.MIN_VALUE;
    boolean wide = t.width() == Long.SIZE;
    return new long[] {0, 1, -1, 2, -2, top, bottom, top - 1, bottom + 1, 0x55555555L,
        0xAAAAAAAAL - (1L << 32), 1L << 30, -(1L << 30) - 1, wide ? 1L << 40 : 7,
        wide ? 0x5555555555555555L : 13, wide ? -(1L << 40) : 99};
  }

  /**
   * Every value against every position in {@code [0, w)}: the value cycles faster than the
   * position, so the first {@code values.length * w} rows pair them all; at a narrow type that is
   * the whole of the two operands' admitted domain. Three batches: dense, with nulls in each
   * column, and a short tail.
   */
  private static List<LongBatch> inRange(Target t) {
    long[] vs = values(t);
    int length = vs.length * t.width();
    var value = new long[length];
    var pos = new long[length];
    for (int i = 0; i < length; i++) {
      value[i] = vs[i % vs.length];
      pos[i] = (i / vs.length) % t.width();
    }
    var tail = new long[][] {java.util.Arrays.copyOf(value, 37), java.util.Arrays.copyOf(pos, 37)};
    return List.of(
        batch(new long[][] {value, pos}, List.of(i -> false, i -> false)),
        batch(new long[][] {value.clone(), pos.clone()},
            List.of(i -> i % 7 == 3, i -> i % 5 == 1)),
        batch(tail, List.of(i -> i % 3 == 0, i -> false)));
  }

  /**
   * One batch per position past the range, held on row 37 of 100 two ways: on a valid row, where
   * Spark raises; and under a null target, where Spark answers null and the guard, which reads
   * the position's own validity, declines the batch to the row engine. A position under a null of
   * its own is never out of range to the guard: the harness poisons a null lane with the lane's
   * extremes, so {@link #inRange}'s batch with nulls holds such positions and must answer.
   */
  private static List<LongBatch> outside(Target t, List<String> kinds) {
    var out = new ArrayList<LongBatch>();
    var bad = new ArrayList<Integer>();
    bad.add(t.width());
    for (int p : OUTSIDE) {
      if (p < 0 || p >= t.width()) {
        bad.add(p);
      }
    }
    for (int p : bad) {
      long[] vs = values(t);
      var value = new long[100];
      var pos = new long[100];
      for (int i = 0; i < 100; i++) {
        value[i] = vs[(i * 7) % vs.length];
        pos[i] = i % t.width();
      }
      pos[37] = p;
      out.add(batch(new long[][] {value, pos}, List.of(i -> false, i -> false)));
      kinds.add("raises");
      out.add(batch(new long[][] {value.clone(), pos.clone()}, List.of(i -> i == 37, i -> false)));
      kinds.add("declines");
    }
    return out;
  }

  /** Whether Spark raises on a row of {@code b}. */
  private static boolean raises(Kernel k, LongBatch b) {
    var values = new long[k.numInputs()];
    var isNull = new boolean[k.numInputs()];
    for (int i = 0; i < b.length(); i++) {
      for (int c = 0; c < values.length; c++) {
        values[c] = b.data()[c][i];
        isNull[c] = b.nulls()[c][i];
      }
      if (k.oracle().answer(0, row(values, isNull)) instanceof VarkaSparkOracle.Raised) {
        return true;
      }
    }
    return false;
  }

  /** {@code bit_get} of {@code t} by the position column, on both arms. */
  private static void checkByColumn(Target t, List<String> cancelled) {
    Expression expr = new BitwiseGet(t.column(), POS);
    for (Arm arm : ARMS) {
      String where = expr.sql() + " on " + arm.name();
      Object compiled = compile(expr, List.of(t.column(), POS), arm.options());
      if (compiled instanceof String reason) {
        if (widthDecline(reason)) {
          cancelled.add(where + ": " + reason);
          continue;
        }
        fail(where + " declined: " + reason);
      }
      Kernel k = (Kernel) compiled;
      List<LongBatch> inside = inRange(t);
      assertEquals(inside.size(), runAll(k, inside, n -> false, where + ", in range"));
      var kinds = new ArrayList<String>();
      List<LongBatch> past = outside(t, kinds);
      for (int n = 0; n < past.size(); n++) {
        assertEquals(kinds.get(n).equals("raises"), raises(k, past.get(n)),
            where + ": Spark's answer on outside batch " + n + " (" + kinds.get(n) + ")");
      }
      runAll(k, past, n -> true, where + ", outside");
    }
  }

  /** {@code bit_get} of {@code t} at each literal position of {@code positions}, both arms. */
  private static void checkByLiteral(Target t, int[] positions, List<String> cancelled) {
    long[] vs = values(t);
    var nulls = List.<IntPredicate>of(i -> i % 9 == 4);
    var batches = List.of(batch(new long[][] {vs}, List.of(i -> false)),
        batch(new long[][] {vs.clone()}, nulls));
    for (int p : positions) {
      Expression expr = new BitwiseGet(t.column(), Literal$.MODULE$.apply(p));
      for (Arm arm : ARMS) {
        String where = expr.sql() + " on " + arm.name();
        Object compiled = compile(expr, List.of(t.column()), arm.options());
        if (compiled instanceof String reason) {
          if (widthDecline(reason)) {
            cancelled.add(where + ": " + reason);
            continue;
          }
          fail(where + " declined: " + reason);
        }
        assertEquals(batches.size(), runAll((Kernel) compiled, batches, n -> false, where));
      }
    }
  }

  private static int[] every(int width) {
    var out = new int[width];
    for (int p = 0; p < width; p++) {
      out[p] = p;
    }
    return out;
  }

  @Test
  @DisplayName("bit_get of every TINYINT and every SMALLINT at every position matches Spark on "
      + "both arms, and a position past the type's width declines the batch")
  public void bitGetOfEveryNarrowValue() {
    var cancelled = new ArrayList<String>();
    for (Target t : List.of(TINYINT, SMALLINT)) {
      checkByColumn(t, cancelled);
      checkByLiteral(t, every(t.width()), cancelled);
    }
    assumeTrue(cancelled.isEmpty(), "declined by width: " + cancelled);
  }

  @Test
  @DisplayName("bit_get of the INT and BIGINT edges at every position matches Spark on both arms, "
      + "and a position past the type's width declines the batch")
  public void bitGetOfTheWideEdges() {
    var cancelled = new ArrayList<String>();
    for (Target t : List.of(INT, BIGINT)) {
      checkByColumn(t, cancelled);
      checkByLiteral(t, new int[] {0, 1, t.width() / 2 - 1, t.width() / 2, t.width() - 1},
          cancelled);
    }
    assumeTrue(cancelled.isEmpty(), "declined by width: " + cancelled);
  }

  @Test
  @DisplayName("bit_get by a literal position outside the type's width declines at compile time")
  public void bitGetByALiteralOutsideDeclines() {
    for (Target t : List.of(TINYINT, SMALLINT, INT, BIGINT)) {
      for (int p : new int[] {-1, t.width(), Integer.MAX_VALUE}) {
        Expression expr = new BitwiseGet(t.column(), Literal$.MODULE$.apply(p));
        Object compiled = compile(expr, List.of(t.column()), VarkaMatrixRuns.BASE);
        assertEquals("bit_get position " + p + " outside [0, " + t.width()
            + "), which Spark raises on", compiled, expr.sql());
      }
    }
  }

  @Test
  @DisplayName("bit_count of a BOOLEAN is 1 or 0, and null where the boolean is: of a column, "
      + "and of a comparison on the narrow, int and long lanes, on both arms")
  public void bitCountOfABoolean() {
    Attribute b = VarkaSqlResolve.column("b", DataTypes.BooleanType);
    Attribute y2 = VarkaSqlResolve.column("y2", DataTypes.ByteType);
    Attribute l2 = VarkaSqlResolve.column("l2", DataTypes.LongType);
    Attribute i2 = VarkaSqlResolve.column("i2", DataTypes.IntegerType);
    record Case(Expression expr, List<Attribute> columns, long[][] values) {}
    // Every length to 129 for the bare column, the harness alternating the data bit under a null;
    // each comparison over values on both sides of each other and of their equality.
    long[] ys = {-128, -1, 0, 1, 127, 5, 5, -7};
    long[] ys2 = {127, -1, 1, 0, -128, 5, 6, -8};
    long[] ls = {Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE, 1L << 40, 1L << 40, -(1L << 40)};
    long[] ls2 = {Long.MAX_VALUE, -1, 1, 0, Long.MIN_VALUE, 1L << 40, (1L << 40) + 1, 0};
    var cases = List.of(
        new Case(new BitwiseCount(new LessThan(TINYINT.column(), y2)),
            List.of(TINYINT.column(), y2), new long[][] {ys, ys2}),
        new Case(new BitwiseCount(new LessThan(INT.column(), i2)),
            List.of(INT.column(), i2), new long[][] {ys2, ys}),
        new Case(new BitwiseCount(new LessThan(BIGINT.column(), l2)),
            List.of(BIGINT.column(), l2), new long[][] {ls, ls2}));
    var cancelled = new ArrayList<String>();
    for (Arm arm : ARMS) {
      Expression bare = new BitwiseCount(b);
      Object compiled = compile(bare, List.of(b), arm.options());
      if (compiled instanceof String reason) {
        fail(bare.sql() + " on " + arm.name() + " declined: " + reason);
      }
      var batches = new ArrayList<LongBatch>();
      for (int length = 1; length <= 129; length++) {
        var bits = new long[length];
        for (int i = 0; i < length; i++) {
          bits[i] = (i * 5 + length) % 3 == 0 ? 0 : 1;
        }
        batches.add(batch(new long[][] {bits}, List.of(i -> i % 3 == 2)));
      }
      assertEquals(batches.size(), runAll((Kernel) compiled, batches, n -> false,
          bare.sql() + " on " + arm.name()));
      for (Case c : cases) {
        String where = c.expr().sql() + " on " + arm.name();
        Object k = compile(c.expr(), c.columns(), arm.options());
        if (k instanceof String reason) {
          if (widthDecline(reason)) {
            cancelled.add(where + ": " + reason);
            continue;
          }
          fail(where + " declined: " + reason);
        }
        var cmp = List.of(batch(c.values(), List.of(i -> false, i -> false)),
            batch(new long[][] {c.values()[0].clone(), c.values()[1].clone()},
                List.of(i -> i == 2, i -> i == 5)));
        assertEquals(cmp.size(), runAll((Kernel) k, cmp, n -> false, where));
        assertTrue(((Kernel) k).roots().get(0).laneType() == VarkaVectorIR.LaneType.INT,
            where + ": the count is an INT value");
      }
    }
    assumeTrue(cancelled.isEmpty(), "declined by width: " + cancelled);
  }
}
