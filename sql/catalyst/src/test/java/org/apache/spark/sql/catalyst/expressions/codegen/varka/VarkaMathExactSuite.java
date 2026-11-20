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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntPredicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterFixtures.Col;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterFixtures.Output;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.MathFn;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.MathUnary;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;

/**
 * The math functions' exact forms against Spark's generated code (VARKA-356, {@code VARKA-345.md}
 * 2.3): each function's kernel, a per-lane call of the method Spark's code calls, compared bit for
 * bit with that code evaluated by scalar Java on the same JVM - {@code Math.sin(c)} for
 * {@code sin}, {@code 1 / Math.cos(c)} for {@code sec} - by {@code doubleToRawLongBits}.
 *
 * <p>Three checks. The sampled differential draws {@value #SAMPLES} inputs a function (uniform bit
 * patterns, the function's own domain, {@code [-2, 2]}, magnitudes down to {@code 2^-60}, and for
 * the trigonometric functions arguments from {@code 2^20} up to {@code 1e300}, where the reduction
 * is hardest) at the preferred width. The fixed vectors - the special values and their 64
 * neighbours each way, Spark's own edges and NaN payloads of both signs, quiet and signalling -
 * run through every null pattern and several lengths at two and eight lanes. And a planted fault,
 * the other class's method, must fail the differential where the classes differ.
 *
 * <p>Every answer is compared by its raw bits, a NaN's too: a NaN passed through from the input
 * keeps its payload, and one the function makes from a number ({@code asin(2.0)}) is the bits the
 * one method computes on this JVM for both sides.
 */
@VarkaSuite(ownJvm = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public final class VarkaMathExactSuite {

  /** The sampled inputs a function, about 2^24, over the draws below. */
  private static final int SAMPLES = Integer.getInteger("varka.math.samples", 1 << 24);

  /** The rows of one sampled batch. */
  private static final int BATCH = 1 << 14;

  private static final ColumnRef X = new ColumnRef(0, LaneType.DOUBLE);

  /** A function as the kernel computes it and as Spark's generated code does. */
  private record Fn(String name, VarkaVectorIR root, long[] lits, DoubleUnaryOperator spark,
      boolean trig, boolean unitDomain) {}

  private static Fn call(String name, MathFn fn, DoubleUnaryOperator spark, boolean trig,
      boolean unitDomain) {
    return new Fn(name, new MathUnary(fn, X), new long[0], spark, trig, unitDomain);
  }

  /** {@code 1 / fn(c)}, Spark's {@code sec}, {@code csc} and {@code cot}, the 1.0 a literal. */
  private static Fn reciprocal(String name, MathFn fn, DoubleUnaryOperator spark) {
    var root = new IntArith(IntOp.DIV, Overflow.WRAP, new LiteralSlot(0, LaneType.DOUBLE),
        new MathUnary(fn, X));
    return new Fn(name, root, new long[] {Double.doubleToRawLongBits(1.0)}, spark, true, false);
  }

  /** Spark's generated code for each function ({@code mathExpressions.scala}). */
  private static final List<Fn> FUNCTIONS = List.of(
      call("sin", MathFn.SIN, Math::sin, true, false),
      call("cos", MathFn.COS, Math::cos, true, false),
      call("tan", MathFn.TAN, Math::tan, true, false),
      call("asin", MathFn.ASIN, Math::asin, false, true),
      call("acos", MathFn.ACOS, Math::acos, false, true),
      call("atan", MathFn.ATAN, Math::atan, false, false),
      reciprocal("sec", MathFn.COS, c -> 1 / Math.cos(c)),
      reciprocal("csc", MathFn.SIN, c -> 1 / Math.sin(c)),
      reciprocal("cot", MathFn.TAN, c -> 1 / Math.tan(c)));

  // -------------------------------------------------------------------------------------------
  // The comparison
  // -------------------------------------------------------------------------------------------

  /**
   * The mismatch, or null where the kernel's bits {@code got} are Spark's answer for {@code x}
   * at {@link VarkaSameAnswer.Level#EXACT}, a NaN's raw bits too (the class comment says why).
   */
  private static String mismatch(Fn f, double x, long got) {
    double want = f.spark().applyAsDouble(x);
    return Double.doubleToRawLongBits(want) == got ? null
        : f.name() + "(" + hex(x) + "): want " + hex(want) + ", got "
            + hex(Double.longBitsToDouble(got));
  }

  private static String hex(double v) {
    return String.format(Locale.ROOT, "%s (0x%016x)", v, Double.doubleToRawLongBits(v));
  }

  // -------------------------------------------------------------------------------------------
  // The draws
  // -------------------------------------------------------------------------------------------

  /** Draw {@code d} of {@code f}'s sampled inputs, as raw bits. */
  private static long draw(Fn f, int d, SplittableRandom rnd) {
    return switch (d) {
      case 0 -> rnd.nextLong();
      case 1 -> Double.doubleToRawLongBits(f.unitDomain() ? rnd.nextDouble(-1.0, 1.0)
          : rnd.nextDouble(-1e6, 1e6));
      case 2 -> Double.doubleToRawLongBits(rnd.nextDouble(-2.0, 2.0));
      case 3 -> Double.doubleToRawLongBits((rnd.nextBoolean() ? 1 : -1)
          * Math.scalb(1.0 + rnd.nextDouble(), -rnd.nextInt(61)));
      // The trigonometric functions' hard reductions; the inverse ones' edges near +-1.
      case 4 -> f.trig()
          ? Double.doubleToRawLongBits((rnd.nextBoolean() ? 1 : -1)
              * Math.pow(10.0, rnd.nextDouble(Math.log10(0x1p20), 300.0)))
          : Double.doubleToRawLongBits(Math.copySign(1.0 - Math.scalb(rnd.nextDouble(),
              -rnd.nextInt(53)), rnd.nextBoolean() ? 1.0 : -1.0));
      default -> throw new IllegalArgumentException("draw " + d);
    };
  }

  private static final int DRAWS = 5;

  /**
   * The number of inputs of {@code f}'s sampled differential its kernel answers otherwise than
   * Spark's code, the first few named in {@code firstFew}.
   */
  private static long sampledMismatches(Fn f, VarkaEmitOptions options, List<String> firstFew) {
    assumeTrue(Lane.emitLanes(options, Lane.DOUBLE) != 0, "no baked lane count for the call");
    var loaded = VarkaEmitterFixtures.load(
        VarkaEmitterFixtures.emit(List.of(f.root()), 1, f.lits().length, options));
    long mismatches = 0;
    var rnd = new SplittableRandom(0x356L * 31 + f.name().hashCode());
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment in = VarkaEmitterFixtures.alloc(arena, BATCH * 8L);
      Output out = VarkaEmitterFixtures.makeLongOutput(arena, BATCH);
      var col = new Col(in, VarkaEmitterFixtures.alloc(arena, BATCH / 8), 0);
      int perDraw = Math.max(1, SAMPLES / DRAWS / BATCH);
      for (int d = 0; d < DRAWS; d++) {
        for (int b = 0; b < perDraw; b++) {
          for (int i = 0; i < BATCH; i++) {
            in.set(ValueLayout.JAVA_LONG, i * 8L, draw(f, d, rnd));
          }
          run(loaded.kernel(), f, col, out, BATCH);
          for (int i = 0; i < BATCH; i++) {
            String why = mismatch(f, in.get(ValueLayout.JAVA_DOUBLE, i * 8L),
                out.data().get(ValueLayout.JAVA_LONG, i * 8L));
            if (why != null && mismatches++ < 5) {
              firstFew.add(why);
            }
          }
        }
      }
    } finally {
      loaded.loader().release();
    }
    return mismatches;
  }

  private static void run(VarkaFusedKernel kernel, Fn f, Col col, Output out, int length) {
    int status = kernel.run(new long[] {col.data().address()},
        new long[] {col.validityAddress(length)}, new int[] {col.nullCount()},
        new long[] {out.data().address()}, new long[] {out.validity().address()}, new int[0],
        f.lits(), length, VarkaEmitterTestSupport.scratch(kernel, length));
    if (status != 0) {
      fail(f.name() + ": the kernel declined a batch, status " + status);
    }
  }

  @Test
  @DisplayName("each function's kernel answers Spark's bits on every sampled input")
  void sampledDifferential() {
    var failures = new ArrayList<String>();
    for (Fn f : FUNCTIONS) {
      var firstFew = new ArrayList<String>();
      long n = sampledMismatches(f, VarkaMatrixRuns.BASE, firstFew);
      if (n != 0) {
        failures.add(n + " mismatches, " + firstFew);
      }
    }
    assertEquals(List.of(), failures);
  }

  // -------------------------------------------------------------------------------------------
  // The fixed vectors
  // -------------------------------------------------------------------------------------------

  /**
   * The fixed vectors of every function: SLEEF's special values ({@code READING_SLEEF.md} 6.3),
   * each with its 64 neighbours each way, Spark's edges for these functions (the zeros of
   * {@code csc} and {@code cot}, {@code +-1} of {@code asin} and {@code acos}, the multiples of
   * {@code pi / 2} near which {@code tan} and {@code sec} blow up, and {@code 2^20} where the
   * reduction changes), and NaN payloads of both signs, quiet and signalling.
   */
  static long[] fixedVectors() {
    double[] edges = {0.0, -0.0, 1.0, -1.0, Double.MAX_VALUE, -Double.MAX_VALUE,
        Double.MIN_NORMAL, -Double.MIN_NORMAL, Double.MIN_VALUE, -Double.MIN_VALUE,
        Math.PI / 2, -Math.PI / 2, Math.PI, -Math.PI, 3 * Math.PI / 2, 0x1p20, -0x1p20,
        Math.PI / 4, 0x1p-28, 1e300, -1e300};
    var bits = new ArrayList<Long>();
    for (double e : edges) {
      long b = Double.doubleToRawLongBits(e);
      // A neighbour past a zero or an infinity steps into the other sign's or the NaNs'
      // patterns, which are inputs too.
      for (int k = -64; k <= 64; k++) {
        bits.add(b + k);
      }
    }
    bits.add(Double.doubleToRawLongBits(Double.POSITIVE_INFINITY));
    bits.add(Double.doubleToRawLongBits(Double.NEGATIVE_INFINITY));
    for (long nan : new long[] {0x7ff8000000000000L, 0xfff8000000000000L, 0x7ff8000000000001L,
        0xfff800000000beefL, 0x7ff0000000000001L, 0xfff0000000000001L, 0x7ff4000000000000L,
        0x7fffffffffffffffL, 0xffffffffffffffffL}) {
      bits.add(nan);
    }
    return bits.stream().mapToLong(Long::longValue).toArray();
  }

  /** The null patterns the emitter suites share: none, every fifth, every odd row, all. */
  private static final List<IntPredicate> NULL_PATTERNS = List.of(
      i -> false, i -> i % 5 == 0, i -> i % 2 == 1, i -> true);

  @Test
  @DisplayName("each function's kernel answers Spark's bits on the fixed vectors, over nulls, "
      + "lengths and both widths")
  void fixedVectorsOverNullsAndWidths() {
    long[] vectors = fixedVectors();
    assumeTrue(Lane.emitLanes(VarkaMatrixRuns.BASE, Lane.DOUBLE) != 0, "no baked lane count");
    var failures = new ArrayList<String>();
    for (Fn f : FUNCTIONS) {
      for (int lanes : new int[] {2, 8}) {
        var loaded = VarkaEmitterFixtures.load(VarkaEmitterFixtures.emit(List.of(f.root()), 1,
            f.lits().length, VarkaMatrixRuns.BASE.withLanesOverride(lanes)));
        try {
          for (int length : new int[] {1, 7, vectors.length}) {
            for (int p = 0; p < NULL_PATTERNS.size(); p++) {
              IntPredicate isNull = NULL_PATTERNS.get(p);
              try (Arena arena = Arena.ofConfined()) {
                Col col = VarkaEmitterFixtures.makeLongInput(arena, length, isNull,
                    i -> vectors[i], true);
                Output out = VarkaEmitterFixtures.makeLongOutput(arena, length);
                run(loaded.kernel(), f, col, out, length);
                for (int i = 0; i < length; i++) {
                  boolean valid = (out.validity().get(ValueLayout.JAVA_BYTE, i / 8)
                      & (1 << (i % 8))) != 0;
                  String where = f.name() + " at " + lanes + " lanes, length " + length
                      + ", pattern " + p + ", row " + i;
                  if (valid == isNull.test(i)) {
                    failures.add(where + ": validity " + valid);
                  } else if (valid) {
                    String why = mismatch(f, Double.longBitsToDouble(vectors[i]),
                        out.data().get(ValueLayout.JAVA_LONG, i * 8L));
                    if (why != null) {
                      failures.add(where + ": " + why);
                    }
                  }
                }
              }
            }
          }
        } finally {
          loaded.loader().release();
        }
      }
    }
    assertEquals(List.of(), failures.subList(0, Math.min(10, failures.size())));
  }

  // -------------------------------------------------------------------------------------------
  // The planted fault
  // -------------------------------------------------------------------------------------------

  /**
   * The differential's power, by planting the other class's method in the per-lane call
   * (row 504's rule). On x86 {@code Math.sin}, {@code cos} and {@code tan} are the host's
   * intrinsics and differ from {@code StrictMath}'s on thousands of inputs in a million
   * ({@code VARKA-356.md} item 0.3), so the differential must fail for those; {@code asin},
   * {@code acos} and {@code atan} are {@code StrictMath}'s whichever class is called, so the
   * planted call changes nothing for them. Asserted on x86 only: on another host the classes
   * may agree.
   */
  @Test
  @DisplayName("a call of the other class's method fails the differential where the classes "
      + "differ")
  void otherClassIsCaught() {
    assumeTrue(VarkaHostIsa.isX86(System.getProperty("os.arch")), "the classes differ on x86");
    VarkaMathFault.plantForTesting(VarkaMathFault.OTHER_CLASS);
    try {
      for (Fn f : FUNCTIONS) {
        long n = sampledMismatches(f, VarkaMatrixRuns.BASE, new ArrayList<>());
        assertTrue(f.trig() ? n > 0 : n == 0, f.name() + " under the planted call: " + n
            + " mismatches");
      }
    } finally {
      VarkaMathFault.plantForTesting(VarkaMathFault.NONE);
    }
  }

  // -------------------------------------------------------------------------------------------
  // No allocation a batch
  // -------------------------------------------------------------------------------------------

  /**
   * The per-lane call keeps the vector in registers: a kernel compiled by C2 allocates nothing a
   * batch, where a vector boxed around the scalar call would allocate one object a lane. The
   * kernel is warmed until a measured window stays under the sampler's allowance, a few times at
   * most.
   */
  @Test
  @Order(1)
  @DisplayName("a warmed per-lane call kernel allocates nothing a batch")
  void noAllocationPerBatch() {
    assumeTrue(VarkaAllocationSampler.supported(), "thread allocation accounting unavailable");
    // First, at the preferred species alone: a second species in the JVM boxes every vector.
    assumeTrue(VarkaMatrixRuns.BASE.lanesOverride() == 0, "a lane count other than the host's");
    Fn f = FUNCTIONS.get(0);
    var loaded = VarkaEmitterFixtures.load(
        VarkaEmitterFixtures.emit(List.of(f.root()), 1, 0, VarkaMatrixRuns.BASE));
    try (Arena arena = Arena.ofConfined()) {
      int rows = 1024;
      MemorySegment in = VarkaEmitterFixtures.alloc(arena, rows * 8L);
      for (int i = 0; i < rows; i++) {
        in.set(ValueLayout.JAVA_DOUBLE, i * 8L, i * 0.001);
      }
      Output out = VarkaEmitterFixtures.makeLongOutput(arena, rows);
      VarkaFusedKernel kernel = loaded.kernel();
      // The arguments built once, so that the window measures the kernel alone.
      long[] data = {in.address()};
      long[] validity = {0L};
      int[] nulls = {0};
      long[] outData = {out.data().address()};
      long[] outValidity = {out.validity().address()};
      int[] ints = new int[0];
      long[] longs = new long[0];
      long scratch = VarkaEmitterTestSupport.scratch(kernel, rows);
      long allocated = Long.MAX_VALUE;
      int batches = 2000;
      for (int round = 0; round < 6 && VarkaAllocationSampler.suspect(allocated,
          rows * batches); round++) {
        for (int b = 0; b < 20000; b++) {
          kernel.run(data, validity, nulls, outData, outValidity, ints, longs, rows, scratch);
        }
        long before = VarkaAllocationSampler.allocatedBytes();
        for (int b = 0; b < batches; b++) {
          kernel.run(data, validity, nulls, outData, outValidity, ints, longs, rows, scratch);
        }
        allocated = VarkaAllocationSampler.allocatedBytes() - before;
      }
      assertTrue(!VarkaAllocationSampler.suspect(allocated, rows * batches),
          allocated + " bytes over " + batches + " batches");
    } finally {
      loaded.loader().release();
    }
  }
}
