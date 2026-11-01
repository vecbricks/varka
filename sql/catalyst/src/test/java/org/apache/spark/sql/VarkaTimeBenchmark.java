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

package org.apache.spark.sql;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFusedKernel;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaLoopEmitter;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BoundedDivide;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConstDivide;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;

import static org.apache.spark.sql.catalyst.util.DateTimeConstants.NANOS_PER_DAY;
import static org.apache.spark.sql.catalyst.util.DateTimeConstants.NANOS_PER_SECOND;
import static org.apache.spark.sql.catalyst.util.DateTimeConstants.SECONDS_PER_DAY;
import static org.apache.spark.sql.catalyst.util.DateTimeConstants.SECONDS_PER_HOUR;
import static org.apache.spark.sql.catalyst.util.DateTimeConstants.SECONDS_PER_MINUTE;

/**
 * What the {@code TIME} extracts cost in the representation Spark stores - nanoseconds of day in a
 * 64-bit lane - against the split representation {@code m9/SCOPE.md} item 11 proposes: seconds of
 * day in a 32-bit lane, with the nanoseconds within the second beside it.
 *
 * <p>{@code hour}, {@code minute} and {@code second} over a {@code TIME} are constant divisions,
 * and a {@code TIME} has no calendar, so the division is the whole cost. In the stored form it is
 * a 64-bit division: three operations through the double lane on a host with the AVX-512
 * converts, fourteen in the magic-number form the emitter takes without them ({@code VARKA-88.md}
 * 9.2), and the results come out in 64-bit lanes, or narrowed to 32-bit ones at the store ({@code
 * VARKA-102.md} 8.3). In the split form the same extracts are divisions of a number under 86400 by
 * 3600 and 60, in 32-bit lanes, twice as many to a register and half the bytes to read.
 *
 * <p>Eight arms per shape, adjacent, on the same rows:
 * <ul>
 *   <li><b>nanoseconds of day, int64 lanes, conversion form</b> - the shipped lowering on this
 *     machine, {@code ConstDivide} through {@code L2D}, {@code vdivpd}, {@code D2L}, stored wide;
 *   <li><b>nanoseconds of day, int64 lanes, conversion form, narrowed store</b> - the same tree
 *     under a {@code NarrowLane} root, which is what {@code hour(t)} compiles to: the quotient
 *     narrowed with {@code L2I} and stored at four bytes a row under an int mask;
 *   <li><b>nanoseconds of day, int64 lanes, conversion form, narrowed store, half species</b> -
 *     the same root stored through the int species of half the width, whole, which is the form
 *     {@code VARKA-156.md} weighs against the masked one;
 *   <li><b>nanoseconds of day, int64 lanes, magic form</b> - the same tree emitted with {@code
 *     useAVX = 2}, the lowering every AVX2-only host in the runner census takes;
 *   <li><b>seconds of day, int32 lanes, emitted</b> - the split form's extracts as the emitter
 *     lowers an int-lane {@code ConstDivide}: the multiply-high through 64-bit lanes since
 *     VARKA-149, with the double route it replaced beside it as the reference arm;
 *   <li><b>seconds of day, int32 lanes, emitted bounded multiply</b> - the same extracts as the
 *     emitter's {@code BoundedDivide} lowers them, one multiply and one shift each, the emitted
 *     twin of the hand-written arm below ({@code VARKA-102.md} 8.4);
 *   <li><b>seconds of day, int32 lanes, hand-written magic multiply</b> - the split form as item
 *     11 imagines it: one multiply and one logical shift per division, exact over the bounded
 *     dividend, which is the lowering the calendar prefix uses and {@code ConstDivide} does not
 *     have. The constants are found by search and proven exact by exhaustion at start-up, not
 *     written down.
 * </ul>
 *
 * <p>Beside them, the price of the split itself - one 64-bit division by 10^9 and a
 * multiply-subtract, producing both halves as 64-bit outputs since the narrowing store does not
 * exist, so it is an upper bound on the conversion - and two copies as the memory floor of each
 * lane. A shape that extracts one field pays the split for one division; the three-field shape is
 * where a split done once is amortised.
 *
 * <p>A ladder rather than one row count, because the two forms read different bytes per row (8
 * against 4) and write different ones (8 against 4): at a million rows the int arm may fit a cache
 * level the long arm has left, and the ratio would price that boundary ({@code
 * sql/varka/skills/benchmarking.md}, "A benchmark that changes bytes per row must be a ladder").
 * The rungs are {@code VarkaLongLaneBenchmark}'s.
 *
 * <p>To run this benchmark:
 * <pre>
 *   1. build/sbt "catalyst/Test/runMain org.apache.spark.sql.VarkaTimeBenchmark"
 *   2. generate result:
 *        SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt
 *          "catalyst/Test/runMain org.apache.spark.sql.VarkaTimeBenchmark"
 *      Results will be written to
 *      "sql/catalyst/benchmarks/VarkaTimeBenchmark-jdk&lt;NN&gt;-results.txt".
 *   3. both widths, with provenance:
 *        dev/varka_bench_regen.sh catalyst VarkaTimeBenchmark
 * </pre>
 */
public class VarkaTimeBenchmark extends VarkaBenchmarkBase {

  /** A rung of the ladder: a row count and what it is for. */
  private record Rung(int numRows, String level) { }

  /** A shape priced: its name and the fields it extracts. */
  private record Shape(String name, List<String> fields) { }

  /** One emitted arm of a shape: its label, its kernel, its lane, and whether it narrows. */
  private record Arm(String label, VarkaFusedKernel kernel, LaneType lane, boolean narrowed) { }

  /** A single-multiply division: {@code floor(s / d) == (s * m) >>> k} over its range. */
  private record Magic(int m, int k) { }

  /** The rungs, as {@code VarkaLongLaneBenchmark} chose them against this class of machine. */
  private static final List<Rung> LADDER = List.of(
      new Rung(16384, "both forms in L2"),
      new Rung(262144, "both forms in L3"),
      new Rung(1000000, "the long form leaves L3"),
      new Rung(8388608, "both forms past L3"));

  // Each kernel is named by its case id n, claimed so that a reused one fails at once. A dry run
  // (-Dvarka.bench.dryRun=true, VarkaBenchmarkBase.dryRun) emits every kernel and claims its id
  // but fills no rung and times nothing, so dev/varka_bench_ids.sh checks this file's ids in the
  // time a JVM takes to start; -Dvarka.bench.only times only the arms whose name contains it.
  private VarkaFusedKernel emit(List<VarkaVectorIR> roots, int numInputs, int numLiterals,
      VarkaGeneratedClassLoader loader, int n) {
    return emit(roots, numInputs, numLiterals, loader, n, VarkaEmitOptions.DEFAULTS);
  }

  private VarkaFusedKernel emit(List<VarkaVectorIR> roots, int numInputs, int numLiterals,
      VarkaGeneratedClassLoader loader, int n, VarkaEmitOptions options) {
    claimCaseId(n);
    String name = "org.apache.spark.sql.varka.execution.VarkaTimeBench" + n;
    return defineKernel(loader, name,
        VarkaLoopEmitter.emit(name, roots, numInputs, numLiterals, null, null, options));
  }

  // ---------------------------------------------------------------------------------------
  // The shapes, in both representations
  // ---------------------------------------------------------------------------------------

  /** The magic form of the 64-bit division, which an AVX2-only host takes without asking. */
  private static final VarkaEmitOptions MAGIC_FORM = VarkaEmitOptions.DEFAULTS.withUseAVX(2);

  /** The int lane's conversion through double lanes, the reference arm (VARKA-149). */
  private static final VarkaEmitOptions DOUBLE_ROUTE =
      VarkaEmitOptions.DEFAULTS.withMulHiDivide(false);

  /**
   * The narrowed store through the half-width int species ({@code VARKA-156.md}): honoured only
   * at a baked lane count, so the count is the host's own, which at every width this file is
   * regenerated at is what the shipped form emits for anyway.
   */
  private static final VarkaEmitOptions HALF_SPECIES = VarkaEmitOptions.DEFAULTS
      .withLanesOverride(LongVector.SPECIES_PREFERRED.length())
      .withNarrowHalfSpecies(true);

  /** The literal slots of the long form: nanoseconds per hour, per minute, per second. */
  private static final long NANOS_PER_HOUR = SECONDS_PER_HOUR * NANOS_PER_SECOND;
  private static final long NANOS_PER_MINUTE = SECONDS_PER_MINUTE * NANOS_PER_SECOND;
  private static final long[] LONG_LITS = {NANOS_PER_HOUR, NANOS_PER_MINUTE, NANOS_PER_SECOND};

  /** The literal slots of the split form: seconds per hour, seconds per minute. */
  private static final int[] INT_LITS = {(int) SECONDS_PER_HOUR, (int) SECONDS_PER_MINUTE};

  private static final int[] NO_INT_LITS = new int[0];

  private static VarkaVectorIR sub(VarkaVectorIR a, VarkaVectorIR b) {
    return new IntArith(IntOp.SUB, Overflow.WRAP, a, b);
  }

  private static VarkaVectorIR mul(VarkaVectorIR a, VarkaVectorIR b) {
    return new IntArith(IntOp.MUL, Overflow.WRAP, a, b);
  }

  /**
   * The three extracts over nanoseconds of day, built the way a compiler with common
   * subexpressions would build them: each field's remainder feeds the next, so the three-field
   * shape divides three times and {@code minute} alone twice. {@code SUB(t, MUL(hour, H))} is the
   * remainder; a {@code %} node does not exist and would lower to the same thing.
   */
  private static Map<String, VarkaVectorIR> longFields() {
    var t = new ColumnRef(0, LaneType.LONG);
    // The long lane's dividend bounds, as `VarkaTimeCompiler` states them: a TIME is under a day
    // of nanoseconds, and what is left after the hours is under an hour, after the minutes a
    // minute.
    var hour = new ConstDivide(t, NANOS_PER_HOUR, NANOS_PER_DAY);
    var afterHours = sub(t, mul(hour, new LiteralSlot(0, LaneType.LONG)));
    var minute = new ConstDivide(afterHours, NANOS_PER_MINUTE, NANOS_PER_HOUR);
    var afterMinutes = sub(afterHours, mul(minute, new LiteralSlot(1, LaneType.LONG)));
    var second = new ConstDivide(afterMinutes, NANOS_PER_SECOND, NANOS_PER_MINUTE);
    return Map.of("hour", hour, "minute", minute, "second", second);
  }

  /**
   * The same three over seconds of day. {@code second} needs no division of its own here - it is
   * the remainder after the minutes - so the three-field shape divides twice.
   */
  private static Map<String, VarkaVectorIR> intFields() {
    var s = new ColumnRef(0, LaneType.INT);
    var hour = new ConstDivide(s, SECONDS_PER_HOUR);
    var afterHours = sub(s, mul(hour, new LiteralSlot(0, LaneType.INT)));
    var minute = new ConstDivide(afterHours, SECONDS_PER_MINUTE);
    var second = sub(afterHours, mul(minute, new LiteralSlot(1, LaneType.INT)));
    return Map.of("hour", hour, "minute", minute, "second", second);
  }

  /**
   * The split form's extracts as the emitter lowers a division it can bound: {@code hour}
   * from the seconds under 86400, {@code minute} from the seconds after the hours under 3600, each
   * one multiply and one shift, with the same constants the hand-written arm searches for. The
   * emitted twin of that arm ({@code VARKA-102.md} 8.4).
   */
  private static Map<String, VarkaVectorIR> boundedIntFields() {
    var s = new ColumnRef(0, LaneType.INT);
    var hour = BoundedDivide.of(s, (int) SECONDS_PER_HOUR, (int) SECONDS_PER_DAY);
    var afterHours = sub(s, mul(hour, new LiteralSlot(0, LaneType.INT)));
    var minute =
        BoundedDivide.of(afterHours, (int) SECONDS_PER_MINUTE, (int) SECONDS_PER_HOUR);
    var second = sub(afterHours, mul(minute, new LiteralSlot(1, LaneType.INT)));
    return Map.of("hour", hour, "minute", minute, "second", second);
  }

  /** The shapes priced: each field alone, and the three together, where sharing pays. */
  private static final List<Shape> SHAPES = List.of(
      new Shape("hour", List.of("hour")),
      new Shape("minute", List.of("minute")),
      new Shape("second", List.of("second")),
      new Shape("hour, minute, second", List.of("hour", "minute", "second")));

  // ---------------------------------------------------------------------------------------
  // The hand-written magic multiply, item 11's arm
  // ---------------------------------------------------------------------------------------

  /**
   * {@code (M, K)} such that {@code floor(s / d) == (s * M) >>> K} for every {@code s} in {@code
   * [0, range)}, with the product read as an unsigned 32-bit number - which is what {@code
   * IntVector.mul} followed by a logical shift computes. Found by search from the widest shift
   * down and proven by exhaustion over the range, so a constant that is only nearly exact cannot
   * reach the kernel; the dividend bound is what makes a single multiply enough, exactly as in the
   * calendar prefix, and a divisor with no such pair over its range is a failure here rather than
   * a wrong row.
   */
  private static Magic magic(int d, int range) {
    for (int k = 31; k >= 1; k--) {
      long m = ((1L << k) + d - 1) / d;
      if (m <= Integer.MAX_VALUE && (range - 1L) * m < (1L << 32) && exact(d, range, m, k)) {
        return new Magic((int) m, k);
      }
    }
    throw new IllegalStateException(
        "no exact single-multiply form for / " + d + " over [0, " + range + ")");
  }

  private static boolean exact(int d, int range, long m, int k) {
    for (int s = 0; s < range; s++) {
      if (((s * m) >>> k) != s / d) {
        return false;
      }
    }
    return true;
  }

  private static final Magic HOUR_MAGIC = magic((int) SECONDS_PER_HOUR, (int) SECONDS_PER_DAY);
  private static final Magic MINUTE_MAGIC =
      magic((int) SECONDS_PER_MINUTE, (int) SECONDS_PER_HOUR);
  private static final int HOUR_M = HOUR_MAGIC.m();
  private static final int HOUR_K = HOUR_MAGIC.k();
  private static final int MINUTE_M = MINUTE_MAGIC.m();
  private static final int MINUTE_K = MINUTE_MAGIC.k();

  private static final VectorSpecies<Integer> SPECIES = IntVector.SPECIES_PREFERRED;
  private static final ByteOrder ORDER = ByteOrder.nativeOrder();

  /**
   * The split form's extracts as one multiply and one shift each: {@code hour} from the seconds,
   * {@code minute} from the seconds left after the hours (under 3600, which is what lets its
   * constant fit), {@code second} as the remainder after the minutes. Which of the three are
   * stored is the shape's choice; the arithmetic for the fields before a stored one is done either
   * way, as the emitted forms do it too.
   */
  private static void handWritten(MemorySegment src, MemorySegment[] dsts, List<String> fields,
      int numRows) {
    boolean wantHour = fields.contains("hour");
    boolean wantMinute = fields.contains("minute");
    boolean wantSecond = fields.contains("second");
    MemorySegment hourDst = wantHour ? dsts[fields.indexOf("hour")] : null;
    MemorySegment minuteDst = wantMinute ? dsts[fields.indexOf("minute")] : null;
    MemorySegment secondDst = wantSecond ? dsts[fields.indexOf("second")] : null;
    int bound = SPECIES.loopBound(numRows);
    int i = 0;
    while (i < bound) {
      IntVector s = IntVector.fromMemorySegment(SPECIES, src, i * 4L, ORDER);
      IntVector hour = s.mul(HOUR_M).lanewise(VectorOperators.LSHR, HOUR_K);
      if (wantHour) {
        hour.intoMemorySegment(hourDst, i * 4L, ORDER);
      }
      if (wantMinute || wantSecond) {
        IntVector afterHours = s.sub(hour.mul((int) SECONDS_PER_HOUR));
        IntVector minute = afterHours.mul(MINUTE_M).lanewise(VectorOperators.LSHR, MINUTE_K);
        if (wantMinute) {
          minute.intoMemorySegment(minuteDst, i * 4L, ORDER);
        }
        if (wantSecond) {
          afterHours.sub(minute.mul((int) SECONDS_PER_MINUTE))
              .intoMemorySegment(secondDst, i * 4L, ORDER);
        }
      }
      i += SPECIES.length();
    }
    while (i < numRows) {
      int s = src.get(ValueLayout.JAVA_INT, i * 4L);
      if (wantHour) {
        hourDst.set(ValueLayout.JAVA_INT, i * 4L, s / 3600);
      }
      if (wantMinute) {
        minuteDst.set(ValueLayout.JAVA_INT, i * 4L, s / 60 % 60);
      }
      if (wantSecond) {
        secondDst.set(ValueLayout.JAVA_INT, i * 4L, s % 60);
      }
      i += 1;
    }
  }

  // ---------------------------------------------------------------------------------------
  // The run
  // ---------------------------------------------------------------------------------------

  private int nextId = 0;

  private int kernelId() {
    int n = nextId;
    nextId += 1;
    return n;
  }

  private static List<VarkaVectorIR> pick(List<String> fields, Map<String, VarkaVectorIR> by) {
    var out = new ArrayList<VarkaVectorIR>();
    for (String f : fields) {
      out.add(by.get(f));
    }
    return out;
  }

  private static List<VarkaVectorIR> narrowed(List<VarkaVectorIR> roots) {
    var out = new ArrayList<VarkaVectorIR>();
    for (VarkaVectorIR r : roots) {
      out.add(new NarrowLane(r));
    }
    return out;
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    var loader = new VarkaGeneratedClassLoader(getClass().getClassLoader());
    try {
      // Every kernel once, before the ladder: a kernel does not depend on the row count, and
      // one id per kernel keeps the emitted class names stable across rungs.
      var longK = longFields();
      var intK = intFields();
      var boundedK = boundedIntFields();
      var emitted = new ArrayList<List<Arm>>();
      for (Shape shape : SHAPES) {
        var longRoots = pick(shape.fields(), longK);
        var intRoots = pick(shape.fields(), intK);
        var boundedRoots = pick(shape.fields(), boundedK);
        var arms = new ArrayList<Arm>();
        arms.add(new Arm("nanoseconds of day, int64 lanes, conversion form (shipped)",
            emit(longRoots, 1, LONG_LITS.length, loader, kernelId()), LaneType.LONG, false));
        arms.add(new Arm(
            "nanoseconds of day, int64 lanes, conversion form, narrowed store (shipped)",
            emit(narrowed(longRoots), 1, LONG_LITS.length, loader, kernelId()),
            LaneType.LONG, true));
        arms.add(new Arm(
            "nanoseconds of day, int64 lanes, conversion form, narrowed store, half species",
            emit(narrowed(longRoots), 1, LONG_LITS.length, loader, kernelId(), HALF_SPECIES),
            LaneType.LONG, true));
        arms.add(new Arm("nanoseconds of day, int64 lanes, magic form (the AVX2 lowering)",
            emit(longRoots, 1, LONG_LITS.length, loader, kernelId(), MAGIC_FORM),
            LaneType.LONG, false));
        arms.add(new Arm(
            "seconds of day, int32 lanes, emitted (shipped: multiply-high, VARKA-149)",
            emit(intRoots, 1, INT_LITS.length, loader, kernelId()), LaneType.INT, false));
        arms.add(new Arm("seconds of day, int32 lanes, emitted (the double route it replaced)",
            emit(intRoots, 1, INT_LITS.length, loader, kernelId(), DOUBLE_ROUTE), LaneType.INT,
            false));
        arms.add(new Arm("seconds of day, int32 lanes, emitted bounded multiply",
            emit(boundedRoots, 1, INT_LITS.length, loader, kernelId()), LaneType.INT, false));
        emitted.add(arms);
      }
      var t = new ColumnRef(0, LaneType.LONG);
      var seconds = new ConstDivide(t, NANOS_PER_SECOND, NANOS_PER_DAY);
      VarkaFusedKernel split = emit(List.of(seconds,
          sub(t, mul(seconds, new LiteralSlot(2, LaneType.LONG)))),
          1, LONG_LITS.length, loader, kernelId());
      VarkaFusedKernel copyLong = emit(List.of(t), 1, 0, loader, kernelId());
      VarkaFusedKernel copyInt =
          emit(List.of(new ColumnRef(0, LaneType.INT)), 1, 0, loader, kernelId());
      reportCaseIds();
      if (dryRun()) {
        // The ids are all a dry run is for; the rungs would fill and check up to 8.4M rows.
        return;
      }
      for (Rung rung : LADDER) {
        runRung(rung.numRows(), rung.level(), emitted, split, copyLong, copyInt);
      }
    } finally {
      loader.release();
    }
  }

  private void runRung(int numRows, String level, List<List<Arm>> emitted,
      VarkaFusedKernel split, VarkaFusedKernel copyLong, VarkaFusedKernel copyInt) {
    try (Arena arena = Arena.ofConfined()) {
      // The same instants in both representations: nanoseconds of day spread over the whole
      // day, and the seconds of day each one falls in.
      MemorySegment nanos = arena.allocate(numRows * 8L, 8);
      MemorySegment secs = arena.allocate(numRows * 4L, 8);
      for (int i = 0; i < numRows; i++) {
        long t = Long.remainderUnsigned(i * 0x9E3779B97F4A7C15L, NANOS_PER_DAY);
        nanos.set(ValueLayout.JAVA_LONG, i * 8L, t);
        secs.set(ValueLayout.JAVA_INT, i * 4L, (int) (t / NANOS_PER_SECOND));
      }
      MemorySegment[] longDsts = new MemorySegment[3];
      for (int k = 0; k < 3; k++) {
        longDsts[k] = arena.allocate(numRows * 8L, 8);
      }
      MemorySegment[] intDsts = new MemorySegment[3];
      for (int k = 0; k < 3; k++) {
        intDsts[k] = arena.allocate(numRows * 4L, 8);
      }
      MemorySegment[] validities = new MemorySegment[3];
      for (int k = 0; k < 3; k++) {
        validities[k] = arena.allocate((numRows + 7) / 8L, 8);
      }

      // The hand-written arm checked against the definition once per rung, on this rung's
      // rows, before it is timed: a wrong constant would otherwise be a fast row.
      handWritten(secs, intDsts, List.of("hour", "minute", "second"), numRows);
      for (int i = 0; i < numRows; i++) {
        int s = secs.get(ValueLayout.JAVA_INT, i * 4L);
        if (!(intDsts[0].get(ValueLayout.JAVA_INT, i * 4L) == s / 3600
            && intDsts[1].get(ValueLayout.JAVA_INT, i * 4L) == s / 60 % 60
            && intDsts[2].get(ValueLayout.JAVA_INT, i * 4L) == s % 60)) {
          throw requirementFailed(
              "the hand-written magic multiply is wrong at row " + i + " (seconds " + s + ")");
        }
      }

      var buffers = new Buffers(nanos, secs, longDsts, intDsts, validities, numRows);
      var benchmark = benchmark("TIME extracts over " + numRows + " rows - " + level, numRows)
          .timed().build();
      for (int s = 0; s < SHAPES.size(); s++) {
        Shape shape = SHAPES.get(s);
        int outputs = shape.fields().size();
        for (Arm arm : emitted.get(s)) {
          benchmark.addCase(shape.name() + ": " + arm.label(),
              iteration -> buffers.run(arm.kernel(), arm.lane(), outputs, arm.narrowed()));
        }
        benchmark.addCase(
            shape.name() + ": seconds of day, int32 lanes, hand-written magic multiply",
            iteration -> handWritten(secs, intDsts, shape.fields(), numRows));
      }
      benchmark.addCase("the split itself: nanoseconds to (seconds, nanoseconds within), "
          + "int64 lanes, both halves stored wide",
          iteration -> buffers.run(split, LaneType.LONG, 2, false));
      benchmark.addCase("floor: nanoseconds of day copied, int64 lanes",
          iteration -> buffers.run(copyLong, LaneType.LONG, 1, false));
      benchmark.addCase("floor: seconds of day copied, int32 lanes",
          iteration -> buffers.run(copyInt, LaneType.INT, 1, false));
      runCases(benchmark);
    }
  }

  /** A rung's columns and destinations, and the call that runs a kernel over them. */
  private record Buffers(MemorySegment nanos, MemorySegment secs, MemorySegment[] longDsts,
      MemorySegment[] intDsts, MemorySegment[] validities, int numRows) {

    private static long[] addresses(MemorySegment[] segments, int count) {
      long[] out = new long[count];
      for (int k = 0; k < count; k++) {
        out[k] = segments[k].address();
      }
      return out;
    }

    /** {@code narrowed}: a long-lane kernel whose roots narrow at the store writes int columns. */
    void run(VarkaFusedKernel k, LaneType lane, int outputs, boolean narrowed) {
      int status;
      // The long entry and table at a 64-bit lane, as the runner picks them.
      if (lane.bits() == Long.SIZE) {
        MemorySegment[] dsts = narrowed ? intDsts : longDsts;
        status = k.run(new long[] {nanos.address()}, new long[] {0L}, new int[] {0},
            addresses(dsts, outputs), addresses(validities, outputs), NO_INT_LITS, LONG_LITS,
            numRows);
      } else {
        status = k.run(new long[] {secs.address()}, new long[] {0L}, new int[] {0},
            addresses(intDsts, outputs), addresses(validities, outputs), INT_LITS, numRows);
      }
      if (status != 0) {
        throw requirementFailed("the kernel declined a batch: status " + status);
      }
    }
  }
}
