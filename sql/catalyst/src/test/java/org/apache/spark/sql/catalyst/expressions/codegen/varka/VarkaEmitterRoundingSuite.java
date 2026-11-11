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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.LongToIntFunction;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.catalyst.expressions.RoundBase;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterFixtures.Output;
import org.apache.spark.sql.catalyst.util.MathUtils;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.sql.internal.SQLConf$;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;

/**
 * {@code RoundToIntegral} with mode {@code RINT} (VARKA-344): the kernel's answer is held to
 * Spark's {@code rint} bit for bit, {@code Math.rint} with the canonical NaN for every NaN
 * (SPARK-61175), over the cells that decide the 2^52 construction, every binary exponent from
 * -1074 to 1023 in both signs and a seeded draw. Every cell is on a valid lane and the nulls fall
 * among the rest, where a null lane's output is null; the construction's binary32 twin is
 * exhausted over all 2^32 patterns behind {@code -Dvarka.sweep=true}. Payload, signalling and
 * negative NaNs, doubles and widened floats, go through {@code rint}, the one function the
 * compiler lowers through the node, on the kernel and on Spark's own expression, each answering
 * the canonical NaN.
 *
 * <p>The integral rounding on the int lane through its emitted kernels against Spark's own
 * {@code Round}, {@code BRound} and {@code Truncate}: every {@code INT} through each function at
 * each scale from -1 to -10, LEGACY's value and ANSI's raise ({@code VARKA-344.md} 3.5), behind
 * {@code -Dvarka.sweep=true}, and a strided slice of the ints, the windows' ends, the ties and
 * the extremes among them, on every build; {@code TINYINT} and {@code SMALLINT} over every value
 * at every scale where the answer is not constant. Under ANSI a chunk the kernel answers holds
 * no value Spark raises on, and a chunk holding one is declined.
 */
@VarkaSuite(ownJvm = true)
public final class VarkaEmitterRoundingSuite {

  private static final VarkaVectorIR.ColumnRef X =
      new VarkaVectorIR.ColumnRef(0, VarkaVectorIR.LaneType.DOUBLE);

  private static final VarkaVectorIR RINT =
      new VarkaVectorIR.RoundToIntegral(VarkaVectorIR.RoundMode.RINT, X);

  /** How many of {@link #data}'s rows are the cells, which no null covers. */
  private static final int CELLS = cells().length;

  /** The cells of {@code VARKA-344.md} 3.1 for {@code rint}, as doubles. */
  private static double[] cells() {
    double c = 0x1.0p52;
    return new double[] {
        -0.3, -0.0, 0.0, 0.5, -0.5, 1.5, 2.5, -1.5, -2.5, 0.49999999999999994, 1.0, -1.0,
        c - 0.5, c - 1.5, -(c - 0.5), c, c + 1, -c, -(c + 1), 0x1.0p53, Double.MAX_VALUE,
        -Double.MAX_VALUE, Double.MIN_VALUE, -Double.MIN_VALUE, Double.MIN_NORMAL,
        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN,
        Double.longBitsToDouble(0xfff8000000000000L), Double.longBitsToDouble(0x7ff8000000000001L),
        Math.nextDown(c), Math.nextUp(c), Math.nextDown(0.5), Math.nextUp(0.5), 4503599627370495.5,
        2147483647.5, -2147483648.5, 9007199254740991.0};
  }

  /**
   * The cells, then each power of two {@code 2^e} for every {@code e} from -1074 (the smallest
   * subnormal) to 1023, with {@code 1.5 * 2^e} and both neighbours, in both signs, the ties below
   * {@code 2^52} and their neighbours, and a seeded draw.
   */
  private static double[] data() {
    var out = new java.util.ArrayList<Double>();
    for (double d : cells()) {
      out.add(d);
    }
    for (int e = -1074; e <= 1023; e++) {
      double p = Math.scalb(1.0, e);
      for (double v : new double[] {p, 1.5 * p, Math.nextUp(p), Math.nextDown(p)}) {
        out.add(v);
        out.add(-v);
      }
    }
    for (int k = 0; k < 64; k++) {
      double tie = Math.scalb(1.0, 52 - k) + 0.5;
      out.add(tie);
      out.add(-tie);
      out.add(Math.nextUp(tie));
      out.add(Math.nextDown(tie));
    }
    var rnd = new SplittableRandom(344);
    for (int i = 0; i < 4000; i++) {
      long bits = rnd.nextLong();
      out.add(Double.longBitsToDouble(bits));
      out.add(Math.floor(Double.longBitsToDouble(bits & 0x433fffffffffffffL)) + 0.5);
    }
    return out.stream().mapToDouble(Double::doubleValue).toArray();
  }

  /** Whether row {@code i} of {@link #data} is null: none of the cells, every seventh after. */
  private static boolean nullAt(int i) {
    return i >= CELLS && i % 7 == 3;
  }

  @Test
  @DisplayName("rint is Spark's rint for the cells, every exponent and a draw, nulls staying "
      + "null")
  void rintMatchesSparkRint() {
    double[] values = data();
    int n = values.length;
    var loaded = VarkaEmitterFixtures.load(
        VarkaEmitterFixtures.emit(List.of(RINT), 1, 0, VarkaMatrixRuns.BASE));
    try (Arena arena = Arena.ofConfined()) {
      var col = VarkaEmitterFixtures.makeLongInput(arena, n, VarkaEmitterRoundingSuite::nullAt,
          i -> Double.doubleToRawLongBits(values[i]), true);
      var out = VarkaEmitterFixtures.makeLongOutput(arena, n);
      int status = loaded.kernel().run(new long[] {col.data().address()},
          new long[] {col.validityAddress(n)}, new int[] {col.nullCount()},
          new long[] {out.data().address()}, new long[] {out.validity().address()}, new int[0],
          new long[0], n, VarkaEmitterTestSupport.scratch(loaded.kernel(), n));
      assertEquals(0, status, "the kernel declined a batch it computes");
      for (int i = 0; i < n; i++) {
        boolean valid = (out.validity().get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) != 0;
        assertEquals(!nullAt(i), valid, "validity of row " + i);
        if (valid) {
          long want = Double.doubleToRawLongBits(MathUtils.rint(values[i]));
          long got = out.data().get(ValueLayout.JAVA_LONG, i * 8L);
          assertEquals(want, got, "rint of 0x" + Long.toHexString(
              Double.doubleToRawLongBits(values[i])) + ": want 0x" + Long.toHexString(want)
              + ", got 0x" + Long.toHexString(got));
        }
      }
    } finally {
      loaded.loader().release();
    }
  }

  /**
   * The NaNs every rounding of a double is fed: the canonical one, a payload, a signalling one,
   * both signs of each, and the all-ones patterns.
   */
  private static final long[] NANS = {0x7ff8000000000000L, 0xfff8000000000000L,
      0x7ff8000000000001L, 0xfff8000000000001L, 0x7ff0000000000001L, 0xfff0000000000001L,
      0x7ff4000000000000L, 0xfffc000000000000L, 0x7fffffffffffffffL, 0xffffffffffffffffL};

  private static final long CANONICAL_NAN = Double.doubleToRawLongBits(Double.NaN);

  @Test
  @DisplayName("rint answers the canonical NaN for payload, signalling and negative NaNs, on "
      + "the kernel and on Spark's expression")
  void roundingsAnswerTheCanonicalNaN() {
    // Rows: each NaN in x beside each NaN and 0.0 in y, so x + y, x - y and sqrt(x - y) see
    // NaNs of both operands and the x86 default NaN; then numbers, so the rows are not all NaN.
    int n = NANS.length * (NANS.length + 1) + 64;
    long[] xs = new long[n];
    long[] ys = new long[n];
    for (int i = 0; i < n; i++) {
      int a = i / (NANS.length + 1);
      int b = i % (NANS.length + 1);
      xs[i] = a < NANS.length ? NANS[a] : Double.doubleToRawLongBits(i * -0.75);
      ys[i] = a < NANS.length && b < NANS.length ? NANS[b] : Double.doubleToRawLongBits(0.0);
    }
    var x = VarkaSqlResolve.column("x", DataTypes.DoubleType);
    var y = VarkaSqlResolve.column("y", DataTypes.DoubleType);
    // rint is the only function that lowers through the node today. floor, ceil, round and
    // bround of a double decline; the part that lowers one through it adds it here, with a
    // harness for its BIGINT result or its literal scale, which this one does not read.
    for (String sql : List.of("rint(x)", "rint(x + y)", "rint(x - y)", "rint(sqrt(x - y))",
        "-rint(x)", "rint(rint(x))")) {
      var cols = CollectionConverters.asScala(List.<Attribute>of(x, y)).toSeq();
      Expression e = VarkaSqlResolve.parse(sql, cols);
      var entry = CollectionConverters.asScala(List.of(VarkaSqlResolve.named(e, "c"))).toSeq();
      Optional<CompiledVarkaProjection> plan =
          VarkaExpressionCompiler.compile(entry, cols, VarkaMatrixRuns.BASE);
      assertTrue(plan.isPresent() && plan.get().outputs().stream().anyMatch(
          VarkaEmitterRoundingSuite::holdsRint), sql + " does not lower through the node");
      Expression spark = VarkaSqlResolve.bind(e, cols);
      List<Integer> ordinals = plan.get().inputOrdinals();
      VarkaEmitterFixtures.Emitted emitted = VarkaEmitterFixtures.emit(plan.get().outputs(),
          ordinals.size(), plan.get().numLiterals(), VarkaMatrixRuns.BASE);
      var loaded = VarkaEmitterFixtures.load(emitted);
      try (Arena arena = Arena.ofConfined()) {
        int k = ordinals.size();
        long[] data = new long[k];
        long[] validity = new long[k];
        for (int c = 0; c < k; c++) {
          long[] src = ordinals.get(c) == 0 ? xs : ys;
          data[c] = VarkaEmitterFixtures.makeLongInput(arena, n, i -> false, i -> src[i], true)
              .data().address();
        }
        var out = VarkaEmitterFixtures.makeLongOutput(arena, n);
        int status = loaded.kernel().run(data, validity, new int[k],
            new long[] {out.data().address()}, new long[] {out.validity().address()},
            new int[0], new long[0], n, VarkaEmitterTestSupport.scratch(loaded.kernel(), n));
        assertEquals(0, status, sql + ": the kernel declined a batch it computes");
        var row = new GenericInternalRow(new Object[2]);
        for (int i = 0; i < n; i++) {
          row.update(0, Double.longBitsToDouble(xs[i]));
          row.update(1, Double.longBitsToDouble(ys[i]));
          long want = Double.doubleToRawLongBits((Double) spark.eval(row));
          long got = out.data().get(ValueLayout.JAVA_LONG, i * 8L);
          String ctx = sql + " over x = 0x" + Long.toHexString(xs[i]) + ", y = 0x"
              + Long.toHexString(ys[i]);
          // A negation flips the canonical NaN's sign, and passes nothing else of the operand's.
          long nan = sql.startsWith("-") ? CANONICAL_NAN ^ Long.MIN_VALUE : CANONICAL_NAN;
          if (Double.isNaN(Double.longBitsToDouble(want))) {
            assertEquals(Long.toHexString(nan), Long.toHexString(want), ctx + ": Spark's NaN");
          }
          assertEquals(Long.toHexString(want), Long.toHexString(got), ctx + ": the kernel");
        }
      } finally {
        loaded.loader().release();
      }
    }
  }

  /** Whether {@code node} is or holds a {@code RoundToIntegral}. */
  private static boolean holdsRint(VarkaVectorIR node) {
    return findRint(node) != null;
  }

  /** The first {@code RoundToIntegral} at or under {@code node}, or null. */
  private static VarkaVectorIR.RoundToIntegral findRint(VarkaVectorIR node) {
    if (node instanceof VarkaVectorIR.RoundToIntegral r) {
      return r;
    }
    for (VarkaVectorIR child : VarkaVectorIR.childrenOf(node)) {
      VarkaVectorIR.RoundToIntegral r = findRint(child);
      if (r != null) {
        return r;
      }
    }
    return null;
  }

  /** NaN floats with a payload, a signalling one and both signs, then the ends of the type. */
  private static final int[] FLOAT_CELLS = {0x7fc00000, 0xffc00000, 0x7fc00001, 0xffc00001,
      0x7f800001, 0xff800001, 0x7fffffff, 0xffffffff, Float.floatToRawIntBits(-0.3f),
      Float.floatToRawIntBits(2.5f), Float.floatToRawIntBits(Float.MAX_VALUE), 0x80000000};

  @Test
  @DisplayName("rint of a widened float answers the canonical NaN for payload, signalling and "
      + "negative NaN floats, and rint of a widened int skips the NaN blend")
  void rintOfAConvertedLane() {
    // The widening keeps a NaN float's sign and payload, so rint(cast(f AS DOUBLE)) must take
    // the blend; an int widens to no NaN, so rint(cast(i AS DOUBLE)) skips it.
    int n = 64;
    int[] floats = new int[n];
    int[] ints = new int[n];
    for (int i = 0; i < n; i++) {
      floats[i] = i < FLOAT_CELLS.length ? FLOAT_CELLS[i] : Float.floatToRawIntBits(i * -0.75f);
      ints[i] = i % 3 == 0 ? Integer.MIN_VALUE + i : i % 3 == 1 ? Integer.MAX_VALUE - i : -i;
    }
    for (boolean isFloat : new boolean[] {true, false}) {
      String sql = isFloat ? "rint(cast(f AS DOUBLE))" : "rint(cast(i AS DOUBLE))";
      int[] bits = isFloat ? floats : ints;
      var cols = CollectionConverters.asScala(List.<Attribute>of(VarkaSqlResolve.column(
          isFloat ? "f" : "i", isFloat ? DataTypes.FloatType : DataTypes.IntegerType))).toSeq();
      Expression e = VarkaSqlResolve.parse(sql, cols);
      var entry = CollectionConverters.asScala(List.of(VarkaSqlResolve.named(e, "c"))).toSeq();
      CompiledVarkaProjection plan =
          VarkaExpressionCompiler.compile(entry, cols, VarkaMatrixRuns.BASE).orElseThrow();
      VarkaVectorIR.RoundToIntegral node = findRint(plan.outputs().get(0));
      assertTrue(node != null && node.child() instanceof VarkaVectorIR.ConvertLane,
          sql + ": the rint's operand is not a conversion: " + plan.outputs());
      assertEquals(isFloat, VarkaRoundingLowering.mayHoldOtherNaN(node), sql + ": the blend");
      Expression spark = VarkaSqlResolve.bind(e, cols);
      var loaded = VarkaEmitterFixtures.load(VarkaEmitterFixtures.emit(plan.outputs(), 1,
          plan.numLiterals(), VarkaMatrixRuns.BASE));
      try (Arena arena = Arena.ofConfined()) {
        var col = VarkaEmitterFixtures.makeInputData(arena, n, i -> false, i -> bits[i], true);
        var out = VarkaEmitterFixtures.makeLongOutput(arena, n);
        int status = loaded.kernel().run(new long[] {col.data().address()}, new long[1],
            new int[1], new long[] {out.data().address()}, new long[] {out.validity().address()},
            new int[0], new long[0], n, VarkaEmitterTestSupport.scratch(loaded.kernel(), n));
        assertEquals(0, status, sql + ": the kernel declined a batch it computes");
        var row = new GenericInternalRow(new Object[1]);
        for (int i = 0; i < n; i++) {
          row.update(0, isFloat ? (Object) Float.intBitsToFloat(bits[i]) : (Object) bits[i]);
          long want = Double.doubleToRawLongBits((Double) spark.eval(row));
          long got = out.data().get(ValueLayout.JAVA_LONG, i * 8L);
          String ctx = sql + " over 0x" + Integer.toHexString(bits[i]);
          if (Double.isNaN(Double.longBitsToDouble(want))) {
            assertEquals(Long.toHexString(CANONICAL_NAN), Long.toHexString(want),
                ctx + ": Spark's NaN");
          }
          assertEquals(Long.toHexString(want), Long.toHexString(got), ctx + ": the kernel");
        }
      } finally {
        loaded.loader().release();
      }
    }
  }

  @Test
  @DisplayName("a rounding takes a double child only")
  void rejectsAnIntegralChild() {
    assertThrows(IllegalArgumentException.class, () -> new VarkaVectorIR.RoundToIntegral(
        VarkaVectorIR.RoundMode.RINT, new VarkaVectorIR.ColumnRef(0)));
  }

  /** The construction of {@code VarkaRoundingLowering} at {@code float}'s precision. */
  private static float rintTwin(float x) {
    float a = Math.abs(x);
    float c = 0x1.0p23f;
    if (!(a < c)) {
      return x;
    }
    float r = (a + c) - c;
    return Float.floatToRawIntBits(x) < 0 ? Float.intBitsToFloat(
        Float.floatToRawIntBits(r) | 0x80000000) : r;
  }

  @Test
  @DisplayName("the binary32 twin of the construction is rint for all 2^32 patterns")
  void binary32TwinIsRintForEveryPattern() {
    assumeTrue(Boolean.getBoolean("varka.sweep"), "an opt-in 2^32 sweep: -Dvarka.sweep=true");
    long bad = IntStream.range(0, 1 << 16).parallel().mapToLong(hi -> {
      long failures = 0;
      for (int lo = 0; lo < (1 << 16); lo++) {
        float x = Float.intBitsToFloat((hi << 16) | lo);
        float want = (float) StrictMath.rint(x);
        float got = rintTwin(x);
        // The twin against StrictMath's rint: two Java results, a NaN held to being one.
        if (Float.isNaN(want) ? !Float.isNaN(got)
            : Float.floatToRawIntBits(want) != Float.floatToRawIntBits(got)) { // nan-compare: ok
          failures++;
        }
      }
      return failures;
    }).sum();
    if (bad != 0) {
      fail(bad + " binary32 patterns differ from rint");
    }
  }

  // -----------------------------------------------------------------------------------------
  // round, bround and truncate on the int lane.
  // -----------------------------------------------------------------------------------------

  private static final int CHUNK = 4096;
  private static final long SLICE_ROWS = 1L << 20;
  private static final List<String> FUNCTIONS = List.of("round", "bround", "truncate");

  /**
   * The ints every slice starts with: the extremes, the ends of ANSI's windows at -1, and at each
   * scale -k from -1 to -9 the ties {@code q0 * 10^k + 10^k / 2} and their neighbours on both
   * signs, for quotients of both parities near zero and near the top, where HALF_EVEN's
   * {@code r + (q0 & 1) > h} decides.
   */
  private static final int[] EDGES = edges();

  private static int[] edges() {
    var out = new LinkedHashSet<Integer>(List.of(Integer.MIN_VALUE, Integer.MAX_VALUE, 0, -1, 1,
        5, -5, 2147483644, -2147483644, 2147483645, -2147483645, 2147483646, -2147483646,
        1999999999, -1999999999, 2147483640, -2147483640));
    for (int k = 1; k <= 9; k++) {
      long d = BigInteger.TEN.pow(k).longValueExact();
      long top = Integer.MAX_VALUE / d;
      for (long q0 : new long[] {0, 1, 2, 3, top - 1, top}) {
        for (long off = -1; off <= 1; off++) {
          long v = q0 * d + d / 2 + off;
          if (v <= Integer.MAX_VALUE) {
            out.add((int) v);
            out.add((int) -v);
          }
        }
      }
    }
    return out.stream().mapToInt(Integer::intValue).toArray();
  }

  /** The slice's row {@code r}: the edges, then a stride whose every byte varies. */
  private static int slice(long r) {
    return r < EDGES.length ? EDGES[(int) r] : (int) (r * 0x9E3779B1L);
  }

  /** A rounding's plan, and the same resolved expression bound for Spark to evaluate. */
  private record Compiled(CompiledVarkaProjection plan, Expression spark) {}

  private static Compiled compile(String sql, boolean ansi, String col, DataType type) {
    var cols = CollectionConverters.asScala(List.<Attribute>of(VarkaSqlResolve.column(col, type)))
        .toSeq();
    SQLConf conf = SQLConf.get().clone();
    conf.setConfString(SQLConf.ANSI_ENABLED().key(), Boolean.toString(ansi));
    Expression e = SQLConf$.MODULE$.withExistingConf(conf, () -> VarkaSqlResolve.parse(sql, cols));
    var entry = CollectionConverters.asScala(List.of(VarkaSqlResolve.named(e, "c"))).toSeq();
    Optional<CompiledVarkaProjection> plan =
        VarkaExpressionCompiler.compile(entry, cols, VarkaMatrixRuns.BASE);
    if (plan.isEmpty()) {
      fail(sql + " declines: " + VarkaExpressionCompiler.declines(entry, cols,
          VarkaMatrixRuns.BASE));
    }
    return new Compiled(plan.get(), VarkaSqlResolve.bind(e, cols));
  }

  /** The column, its type and the store's width in bytes, for an operand type. */
  private record Operand(String col, DataType type, int width) {
    Object box(int v) {
      return switch (width) {
        case 1 -> (byte) v;
        case 2 -> (short) v;
        default -> v;
      };
    }

    void set(MemorySegment m, int i, int v) {
      switch (width) {
        case 1 -> m.set(ValueLayout.JAVA_BYTE, i, (byte) v);
        case 2 -> m.set(ValueLayout.JAVA_SHORT, i * 2L, (short) v);
        default -> m.set(ValueLayout.JAVA_INT, i * 4L, v);
      }
    }

    int get(MemorySegment m, int i) {
      return switch (width) {
        case 1 -> m.get(ValueLayout.JAVA_BYTE, i);
        case 2 -> m.get(ValueLayout.JAVA_SHORT, i * 2L);
        default -> m.get(ValueLayout.JAVA_INT, i * 4L);
      };
    }
  }

  private static final Operand INT = new Operand("i", DataTypes.IntegerType, 4);
  private static final Operand SHORT = new Operand("s", DataTypes.ShortType, 2);
  private static final Operand BYTE = new Operand("y", DataTypes.ByteType, 1);

  /**
   * {@code fn(col, scale)} over {@code rows} values, {@code input(r)} the value of row {@code r},
   * in chunks run in parallel, each worker on a kernel of its own; the first difference fails.
   * A chunk holding a value Spark raises on must decline; it is run again with those values set
   * to 0, so every other value, a guard window's ends among them, is held to its own answer.
   */
  private static void sweep(Operand op, String fn, int scale, boolean ansi, long rows,
      LongToIntFunction input) {
    String sql = fn + "(" + op.col() + ", " + scale + ")";
    String ctx = sql + (ansi ? " under ANSI" : " under LEGACY");
    Compiled compiled = compile(sql, ansi, op.col(), op.type());
    CompiledVarkaProjection plan = compiled.plan();
    Expression expr = compiled.spark();
    // Both sides read the parsed mode, so a parse that ignored the conf would agree with itself.
    assertEquals(ansi, ((RoundBase) expr).ansiEnabled(), ctx + ": the parsed mode");
    VarkaEmitterFixtures.Emitted emitted = VarkaEmitterFixtures.emit(plan.outputs(),
        plan.inputOrdinals().size(), plan.numLiterals(), VarkaMatrixRuns.BASE);
    int[] lits = plan.literals().stream().mapToInt(Integer::intValue).toArray();
    var loaders = new ConcurrentLinkedQueue<VarkaGeneratedClassLoader>();
    var kernels = ThreadLocal.withInitial(() -> {
      var loaded = VarkaEmitterFixtures.load(emitted);
      loaders.add(loaded.loader());
      return loaded.kernel();
    });
    var failure = new AtomicReference<String>();
    long chunks = (rows + CHUNK - 1) / CHUNK;
    try {
      LongStream.range(0, chunks).parallel().forEach(k -> {
        if (failure.get() != null) {
          return;
        }
        long first = k * CHUNK;
        int n = (int) Math.min(CHUNK, rows - first);
        var row = new GenericInternalRow(new Object[1]);
        int[] want = new int[n];
        boolean[] raised = new boolean[n];
        boolean raises = false;
        for (int i = 0; i < n; i++) {
          row.update(0, op.box(input.applyAsInt(first + i)));
          try {
            want[i] = ((Number) expr.eval(row)).intValue();
          } catch (ArithmeticException e) {
            raised[i] = true;
            raises = true;
          }
        }
        var kernel = kernels.get();
        try (Arena arena = Arena.ofConfined()) {
          MemorySegment data = VarkaEmitterFixtures.alloc(arena, (long) n * op.width());
          for (int i = 0; i < n; i++) {
            op.set(data, i, input.applyAsInt(first + i));
          }
          Output out = VarkaEmitterFixtures.makeOutput(arena, n, -1L);
          IntSupplier run = () -> kernel.run(new long[] {data.address()}, new long[] {0L},
              new int[] {0}, new long[] {out.data().address()},
              new long[] {out.validity().address()}, lits, n,
              VarkaEmitterTestSupport.scratch(kernel, n));
          if (raises) {
            if (run.getAsInt() == 0) {
              failure.compareAndSet(null, ctx + ": the chunk at row " + first
                  + " holds a value Spark raises on and was answered");
              return;
            }
            for (int i = 0; i < n; i++) {
              if (raised[i]) {
                op.set(data, i, 0);
              }
            }
          }
          int status = run.getAsInt();
          if (status != 0) {
            failure.compareAndSet(null, ctx + ": status " + status + " at row " + first
                + ", where Spark answers every value");
            return;
          }
          for (int i = 0; i < n; i++) {
            int got = op.get(out.data(), i);
            if (got != want[i]) {
              failure.compareAndSet(null, ctx + ": over " + op.get(data, i)
                  + " the kernel gave " + got + " and Spark " + want[i]);
              return;
            }
          }
        }
      });
    } finally {
      loaders.forEach(VarkaGeneratedClassLoader::release);
    }
    if (failure.get() != null) {
      fail(failure.get());
    }
  }

  private static void sweepInts(long rows, LongToIntFunction input) {
    for (String fn : FUNCTIONS) {
      for (int k = 1; k <= 10; k++) {
        sweep(INT, fn, -k, false, rows, input);
        sweep(INT, fn, -k, true, rows, input);
      }
    }
  }

  @Test
  @DisplayName("round, bround and truncate of an int at scales -1 to -10 match Spark over a "
      + "strided slice of the ints, in LEGACY and ANSI")
  void intSlice() {
    sweepInts(SLICE_ROWS, VarkaEmitterRoundingSuite::slice);
  }

  @Test
  @DisplayName("round, bround and truncate of an int at scales -1 to -10 match Spark over every "
      + "int, in LEGACY and ANSI (opt-in: -Dvarka.sweep=true)")
  void everyInt() {
    assumeTrue(Boolean.getBoolean("varka.sweep"), "an opt-in 2^32 sweep: -Dvarka.sweep=true");
    sweepInts(1L << 32, r -> (int) r);
  }

  @Test
  @DisplayName("round, bround and truncate of a tinyint and a smallint match Spark over every "
      + "value at every scale where the answer is not constant, in LEGACY and ANSI")
  void narrowEveryValue() {
    for (String fn : FUNCTIONS) {
      for (boolean ansi : new boolean[] {false, true}) {
        for (int k = 1; k <= 3; k++) {
          sweep(BYTE, fn, -k, ansi, 256, r -> (int) r - 128);
        }
        for (int k = 1; k <= 5; k++) {
          sweep(SHORT, fn, -k, ansi, 65536, r -> (int) r - 32768);
        }
      }
    }
  }

  @Test
  @DisplayName("bround of a tinyint at -1 under ANSI fuses inside a CASE, at the chain depth the "
      + "emitter allows")
  void narrowHalfEvenNests() {
    // The narrow operand is widened, rounded at the int lane and narrowed back, 10 deep, the
    // guard off the deepest path; the comparison's widening and compare and the CASE bring it
    // to 14, two below MAX_CHAIN_DEPTH, so the shape fuses with nodes to spare.
    compile("CASE WHEN bround(y, -1) > 0 THEN bround(y, -1) ELSE 0Y END", true, "y",
        DataTypes.ByteType);
  }
}
