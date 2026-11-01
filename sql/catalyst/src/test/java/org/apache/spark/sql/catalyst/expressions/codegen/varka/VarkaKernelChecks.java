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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BoolColumn;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BoolOutput;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.GuardedRange;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;

/**
 * The fuzzers' row-by-row check of one emitted kernel against {@link VarkaReference}, at the int
 * lane and at the long lane, shared by the IR fuzzer's drawn, long and wide shapes, the IR series,
 * the guard edge suite, the variants and the composition fuzzer's compiled kernels (VARKA-238,
 * VARKA-289). {@code VarkaKernelCheck} is its Scala face (VARKA-311).
 *
 * <p>A check that fails throws an {@link IllegalStateException} whose message starts with the
 * batch's context and then names the mismatch - "output 2 row 7 differs (want 12, got 13)" -
 * which is the order {@code VarkaFailureSignature} classifies by. A second line carries the row's
 * inputs and the body that ran, so a failure can often be read from its message alone; it is a
 * line of its own because a signature reads only the first, and a null pattern in it would split
 * one bug into many and stop the shrinker changing the batch. The ScalaTest assertions this
 * replaced put their own comparison text first, so every such failure fell through to the
 * exception's class.
 */
public final class VarkaKernelChecks {

  private VarkaKernelChecks() {
  }

  /**
   * One batch's columns: {@code length} rows, each column's null flags ({@code nulls[c][i]}) and
   * drawn values, and whether the masked body is forced by reporting a null over a full bitmap.
   */
  public record Batch(int length, boolean[][] nulls, int[][] data, boolean forceMasked) {
    Batch withNulls(boolean[][] nulls, boolean forceMasked) {
      return new Batch(length, nulls, data, forceMasked);
    }
  }

  /** {@link Batch} at the long lane: the same, with 64-bit column values. */
  public record LongBatch(int length, boolean[][] nulls, long[][] data, boolean forceMasked) {
    LongBatch withNulls(boolean[][] nulls, boolean forceMasked) {
      return new LongBatch(length, nulls, data, forceMasked);
    }
  }

  /** The body a batch did not take: its null flags and whether the masked body is forced. */
  public record OtherBody(boolean[][] nulls, boolean forceMasked) { }

  /** Spark's answer for one output on one row (VARKA-276): a value, a null, or what it raised. */
  public sealed interface Answer {
    /** A value, a predicate's {@code true} and {@code false} as 1 and 0. */
    record Value(long v) implements Answer { }

    record Null() implements Answer { }

    /** The error Spark raised on the row, where a kernel declines the batch instead. */
    record Raised(Throwable e) implements Answer { }
  }

  /** Spark's answers for a kernel's outputs, as {@code VarkaSparkOracle} gives them. */
  public interface SparkAnswers {
    Answer answer(int output, long[] values, boolean[] isNull);

    /** Output {@code output}'s expression, for a failure message. */
    String sql(int output);
  }

  /** Answers the reference evaluator has been held to Spark on, so a suite can see it was. */
  public static final AtomicLong SPARK_COMPARISONS = new AtomicLong();

  /**
   * What every byte of an output's validity bitmap holds before the kernel runs (VARKA-138).
   * A kernel must not depend on it: the driver zeroes or fills every output's bits before
   * anything can return, and the loop ORs the rest in. Each fill catches what the other cannot.
   * Under {@link #ONES} a bit the kernel fails to clear reads as valid and shows; under
   * {@link #ZERO} a bit it fails to set reads as null and shows - and zero is what production
   * hands a kernel, since Arrow's {@code allocateNew} zeroes the validity buffer it allocates.
   */
  public enum ValidityFill {
    ZERO((byte) 0x00),
    ONES((byte) 0xFF);

    private final byte value;

    /** The fills every compared batch runs at: production's first, then the all-valid one. */
    public static final List<ValidityFill> BOTH = List.of(ZERO, ONES);

    ValidityFill(byte value) {
      this.value = value;
    }

    /** The byte every validity byte holds before the kernel runs. */
    public byte value() {
      return value;
    }

    /** What a failure's context gains, so the message says which fill the kernel failed. */
    String suffix() {
      return String.format(" (output validity pre-filled 0x%02X)", value & 0xFF);
    }
  }

  /**
   * A validity bitmap's size as Arrow Java's {@code allocateNew} gives it, in whole 64-bit words:
   * the kernels write and read a word at a time, so a buffer of the nominal
   * {@code (length + 7) / 8} bytes would be one a kernel is not entitled to, and the memory
   * sanitizer (VARKA-263) says so.
   */
  private static long wordBytes(int length) {
    return ((length + 63) / 64) * 8L;
  }

  /**
   * The output stride {@link #sanitized} reads as a {@link BoolOutput}'s: its data is a bitmap of
   * whole words, as a {@code BitVector} allocates it, not a value per row (VARKA-27).
   */
  static final long BIT_STRIDE = -1L;

  /**
   * What a {@link BoolOutput}'s data bitmap holds before the kernel runs: stale bits on every row,
   * which the driver must zero, since the kernel ORs its known-true bits in.
   */
  private static final byte STALE_DATA = (byte) 0xA5;

  /** What a kernel's {@code run} is handed besides its buffers: the scratch address. */
  interface Run {
    int apply(long scratch);
  }

  /**
   * Runs {@code run} with the scratch address the kernel takes, inside the memory sanitizer's
   * window over the buffers this harness allocated, so that every mapping the kernel makes is
   * checked against them when the sanitizer is on. {@code inStride} and {@code outStride} are a
   * value's bytes in each input and output buffer.
   * Nothing is registered, and nothing changes, when it is off. {@link VarkaCompiledCheck}'s
   * warm-up calls go through here too, so that the warm-up's profile of the sanitizer's check is
   * the one its compared calls meet.
   */
  static int sanitized(VarkaFusedKernel kernel, int length, long[] inStride,
      long[] outStride, long[] srcData, long[] srcValidity, long[] outData, long[] outValidity,
      Run run) {
    long scratch = VarkaEmitterTestSupport.scratch(kernel, length);
    VarkaMemorySanitizer.begin();
    try {
      if (VarkaMemorySanitizer.ENABLED) {
        for (int c = 0; c < srcData.length; c++) {
          VarkaMemorySanitizer.register("input data", c, srcData[c], length * inStride[c]);
          if (srcValidity[c] != 0L) {
            VarkaMemorySanitizer.register("input validity", c, srcValidity[c], wordBytes(length));
          }
        }
        for (int o = 0; o < outData.length; o++) {
          if (outData[o] != 0L) {
            VarkaMemorySanitizer.register("output data", o, outData[o],
                outStride[o] == BIT_STRIDE ? wordBytes(length) : length * outStride[o]);
          }
          VarkaMemorySanitizer.register("output validity", o, outValidity[o], wordBytes(length));
        }
        if (scratch != 0L) {
          VarkaMemorySanitizer.register("kernel scratch", 0, scratch,
              (long) kernel.scratchBytesPerRow() * length);
        }
      }
      return run.apply(scratch);
    } finally {
      VarkaMemorySanitizer.end();
    }
  }

  /** A value or a null as the Scala checks printed one, so a message reads as it did. */
  private static String shown(OptionalLong v) {
    return v.isPresent() ? "Some(" + v.getAsLong() + ")" : "None";
  }

  private static String shown(Answer a) {
    return switch (a) {
      case Answer.Value v -> "Value(" + v.v() + ")";
      case Answer.Null n -> "Null";
      case Answer.Raised r -> "Raised(" + r.e() + ")";
    };
  }

  private static String row(long[] values, boolean[] isNull) {
    var parts = new ArrayList<String>();
    for (int c = 0; c < values.length; c++) {
      parts.add(isNull[c] ? "None" : "Some(" + values[c] + ")");
    }
    return "(" + String.join(", ", parts) + ")";
  }

  /** Which of the kernel's bodies a batch ran: the masked one wherever a column reports a null. */
  private static String body(int[] nullCounts) {
    for (int n : nullCounts) {
      if (n > 0) {
        return "masked body";
      }
    }
    return "dense body";
  }

  /**
   * A failure's second line: the row's inputs and the body that ran. Built only when a check
   * fails, and kept off the first line, which is the only one a signature reads.
   */
  private static String evidence(long[] values, boolean[] isNull, int[] nullCounts) {
    return "\n  inputs " + row(values, isNull) + "; " + body(nullCounts);
  }

  /**
   * The reference evaluator's answer for output {@code o} on row {@code i} against Spark's
   * (VARKA-276): equal values or both null, a predicate's answers being 1, 0 or null. A difference
   * is the reference's to fix, or a named delta (row 244), before the kernel is held to it. Spark
   * raising is a row the kernel must decline the batch over, and this is called only for a batch
   * the kernel answered. The expression and the row are on the message's second line.
   */
  private static void agreeWithSpark(String context, SparkAnswers spark, int o, int i,
      long[] values, boolean[] isNull, OptionalLong reference) {
    SPARK_COMPARISONS.incrementAndGet();
    Answer answer = spark.answer(o, values, isNull);
    String where = "\n  " + spark.sql(o) + ", inputs " + row(values, isNull);
    boolean agree = switch (answer) {
      case Answer.Raised raised -> throw new IllegalStateException(context + ": Spark raises on "
          + "output " + o + " at row " + i + ", and the kernel answered the batch instead of "
          + "declining it" + where + ": " + raised.e().getMessage());
      case Answer.Value v -> reference.isPresent() && reference.getAsLong() == v.v();
      case Answer.Null n -> reference.isEmpty();
    };
    if (!agree) {
      throw new IllegalStateException(context + ": the reference evaluator and Spark disagree on "
          + "output " + o + " at row " + i + ": reference " + shown(reference) + ", Spark "
          + shown(answer) + where);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The entry points.
  // ---------------------------------------------------------------------------------------------

  /** One batch through one body: the status, 0 once compared. */
  private interface BodyRun {
    int apply(String context, boolean[][] nulls, boolean forceMasked);
  }

  /**
   * Runs one emitted int-lane kernel over the drawn columns and checks every row and validity bit
   * against {@link VarkaReference}. Null lanes are poisoned; {@code forceMasked} reports a null
   * over a full bitmap so the masked body runs. A batch that is compared is then run again through
   * the body it did not take ({@link #otherBody}), so both of the kernel's bodies meet the oracle.
   * Each body runs the kernel once per {@link ValidityFill}, and every run must agree.
   * Returns whether the rows were compared: false only where {@code declineAllowed} and the kernel
   * declined the batch, which a kernel over columns drawn without its domains may do.
   * {@code spark} is null where the suite holds no Spark answers.
   */
  public static boolean runAndCompare(String context, String className, byte[] bytes,
      List<VarkaVectorIR> roots, int numInputs, int[] lits, Batch batch, boolean declineAllowed,
      SparkAnswers spark) {
    return bodies(context, numInputs, batch.length(), batch.nulls(), batch.forceMasked(),
        (label, nulls, forced) -> runOne(label, className, bytes, roots, numInputs,
            new IntLane(batch.withNulls(nulls, forced), lits), declineAllowed, spark,
            ValidityFill.BOTH))[0] == 0;
  }

  /**
   * The batch through its own body only, at each of {@code fills}, compared as
   * {@link #runAndCompare} compares it; whether the rows were compared, as there. For a batch
   * whose subject is one body: the coverage fuzzer's poisoned batch (VARKA-138), whose nulls
   * select the masked body and whose other body, the same values with no nulls, would meet no
   * poisoned lane; and the test that each fill catches a planted fault the other misses, where a
   * batch whose rows are all null must not go on to a dense body whose rows are all valid.
   */
  public static boolean runOwnBody(String context, String className, byte[] bytes,
      List<VarkaVectorIR> roots, int numInputs, int[] lits, Batch batch, boolean declineAllowed,
      SparkAnswers spark, List<ValidityFill> fills) {
    return runOne(context, className, bytes, roots, numInputs, new IntLane(batch, lits),
        declineAllowed, spark, fills) == 0;
  }

  /**
   * How many bytes of the outputs' validity bitmaps, whole words to the last, differ from
   * {@code fill} once the kernel has run the batch at that fill. For the planted fault
   * ({@code VarkaValidityFault}), whose claim - the kernel writes no validity - must be checked
   * on what the kernel did, not on the rewrite's own idea of which calls write: a writer the
   * rewrite missed, renamed or inlined, leaves a byte changed here.
   */
  public static int validityBytesWritten(String className, byte[] bytes,
      List<VarkaVectorIR> roots, int numInputs, int[] lits, Batch batch, ValidityFill fill) {
    return validityBytesWritten(className, bytes, roots, numInputs, new IntLane(batch, lits),
        fill);
  }

  /**
   * {@link #runAndCompare} for a caller that must know why a batch declined (VARKA-278's
   * variants): the status of the first body that declined, or 0 where every body run was compared.
   * A decline fails the check unless {@code declineAllowed}, as there; the bits are each reason's
   * own only for a kernel emitted under {@code exactRefusalStatus}.
   */
  public static int runAndCompareStatus(String context, String className, byte[] bytes,
      List<VarkaVectorIR> roots, int numInputs, int[] lits, Batch batch,
      boolean declineAllowed) {
    int[] statuses = bodies(context, numInputs, batch.length(), batch.nulls(),
        batch.forceMasked(), (label, nulls, forced) -> runOne(label, className, bytes, roots,
            numInputs, new IntLane(batch.withNulls(nulls, forced), lits), declineAllowed, null,
            ValidityFill.BOTH));
    return statuses[0] != 0 ? statuses[0] : statuses[1];
  }

  /**
   * The batch through each of the kernel's bodies, its own and then {@link #otherBody}'s, whatever
   * the first returned: each body's status, its rows compared where the status is 0. For a caller
   * that holds each body to its own expectation, as the guard verdict does (VARKA-279): a status
   * from {@link #runAndCompareStatus} does not say which body returned it.
   */
  public static List<Integer> runEachBodyStatus(String context, String className, byte[] bytes,
      List<VarkaVectorIR> roots, int numInputs, int[] lits, Batch batch) {
    return each(context, numInputs, batch.length(), batch.nulls(), batch.forceMasked(),
        (label, nulls, forced) -> runOne(label, className, bytes, roots, numInputs,
            new IntLane(batch.withNulls(nulls, forced), lits), true, null, ValidityFill.BOTH));
  }

  /**
   * {@link #runAndCompare} at the long lane: 64-bit buffers, the eight-argument {@code run} with
   * {@code lits} as the long literal table, and the long-lane reference. A narrowing root stores
   * four bytes a row, so its output is read at that width and sign-extended: a value that did not
   * fit 32 bits then differs from the evaluator's 64-bit answer instead of being truncated on both
   * sides. Only the conformance entry point, {@link #runLoadedLong} with Spark's answers,
   * truncates the reference too (see {@code LongLane}); this one does not, even with
   * {@code spark} given.
   */
  public static boolean runAndCompareLong(String context, String className, byte[] bytes,
      List<VarkaVectorIR> roots, int numInputs, long[] lits, LongBatch batch,
      boolean declineAllowed, SparkAnswers spark) {
    return runAndCompareLong(context, className, bytes, roots, numInputs, lits, NO_INT_LITS,
        batch, declineAllowed, spark);
  }

  /**
   * {@link #runAndCompareLong} for a kernel compiled at the long lane that may hold both lanes:
   * {@code intLits} is its int literal table, read where it converts (VARKA-28).
   */
  public static boolean runAndCompareLong(String context, String className, byte[] bytes,
      List<VarkaVectorIR> roots, int numInputs, long[] lits, int[] intLits, LongBatch batch,
      boolean declineAllowed, SparkAnswers spark) {
    return bodies(context, numInputs, batch.length(), batch.nulls(), batch.forceMasked(),
        (label, nulls, forced) -> runOne(label, className, bytes, roots, numInputs,
            longLane(batch.withNulls(nulls, forced), lits, intLits, roots), declineAllowed,
            spark, ValidityFill.BOTH))[0] == 0;
  }

  /** {@link #runAndCompareStatus} at the long lane. */
  public static int runAndCompareLongStatus(String context, String className, byte[] bytes,
      List<VarkaVectorIR> roots, int numInputs, long[] lits, LongBatch batch,
      boolean declineAllowed) {
    int[] statuses = bodies(context, numInputs, batch.length(), batch.nulls(),
        batch.forceMasked(), (label, nulls, forced) -> runOne(label, className, bytes, roots,
            numInputs, longLane(batch.withNulls(nulls, forced), lits, NO_INT_LITS, roots),
            declineAllowed, null, ValidityFill.BOTH));
    return statuses[0] != 0 ? statuses[0] : statuses[1];
  }

  /** {@link #runEachBodyStatus} at the long lane. */
  public static List<Integer> runEachBodyLongStatus(String context, String className,
      byte[] bytes, List<VarkaVectorIR> roots, int numInputs, long[] lits, LongBatch batch) {
    return each(context, numInputs, batch.length(), batch.nulls(), batch.forceMasked(),
        (label, nulls, forced) -> runOne(label, className, bytes, roots, numInputs,
            longLane(batch.withNulls(nulls, forced), lits, NO_INT_LITS, roots), true, null,
            ValidityFill.BOTH));
  }

  /** {@link #runOwnBody} at the long lane. */
  public static boolean runOwnBodyLong(String context, String className, byte[] bytes,
      List<VarkaVectorIR> roots, int numInputs, long[] lits, LongBatch batch,
      boolean declineAllowed, SparkAnswers spark, List<ValidityFill> fills) {
    return runOwnBodyLong(context, className, bytes, roots, numInputs, lits, NO_INT_LITS, batch,
        declineAllowed, spark, fills);
  }

  /** {@link #runOwnBodyLong} with the int literal table of a kernel of both lanes. */
  public static boolean runOwnBodyLong(String context, String className, byte[] bytes,
      List<VarkaVectorIR> roots, int numInputs, long[] lits, int[] intLits, LongBatch batch,
      boolean declineAllowed, SparkAnswers spark, List<ValidityFill> fills) {
    return runOne(context, className, bytes, roots, numInputs,
        longLane(batch, lits, intLits, roots), declineAllowed, spark, fills) == 0;
  }

  /** {@link #validityBytesWritten} at the long lane. */
  public static int validityBytesWrittenLong(String className, byte[] bytes,
      List<VarkaVectorIR> roots, int numInputs, long[] lits, LongBatch batch, ValidityFill fill) {
    return validityBytesWrittenLong(className, bytes, roots, numInputs, lits, NO_INT_LITS, batch,
        fill);
  }

  /** {@link #validityBytesWrittenLong} with the int literal table of a kernel of both lanes. */
  public static int validityBytesWrittenLong(String className, byte[] bytes,
      List<VarkaVectorIR> roots, int numInputs, long[] lits, int[] intLits, LongBatch batch,
      ValidityFill fill) {
    return validityBytesWritten(className, bytes, roots, numInputs,
        longLane(batch, lits, intLits, roots), fill);
  }

  private static final int[] NO_INT_LITS = new int[0];

  /**
   * The lane a long-lane kernel's batch runs through: the long lane's own, or both lanes' where
   * the roots hold a value of each (VARKA-28) - a widening anywhere, a narrowing under another
   * node, or an int root that is not a narrowing - whose int columns, int outputs and int table
   * are the int lane's.
   */
  private static Lane longLane(LongBatch batch, long[] lits, int[] intLits,
      List<VarkaVectorIR> roots) {
    return holdsBothLanes(roots) ? new MixedLane(batch, intLits, lits, roots)
        : new LongLane(batch, lits, false);
  }

  /** Whether {@code roots} hold a value of each lane; see {@link #longLane}. */
  static boolean holdsBothLanes(List<VarkaVectorIR> roots) {
    var seen = new HashSet<VarkaVectorIR>();
    var todo = new ArrayDeque<VarkaVectorIR>();
    for (VarkaVectorIR root : roots) {
      if (!(root instanceof NarrowLane) && root.laneType() == VarkaVectorIR.LaneType.INT) {
        return true;
      }
      todo.addAll(Arrays.asList(VarkaVectorIR.childrenOf(root)));
      if (root instanceof VarkaVectorIR.WidenLane) {
        return true;
      }
    }
    while (!todo.isEmpty()) {
      VarkaVectorIR node = todo.pop();
      if (node instanceof VarkaVectorIR.WidenLane || node instanceof NarrowLane) {
        return true;
      }
      if (seen.add(node)) {
        todo.addAll(Arrays.asList(VarkaVectorIR.childrenOf(node)));
      }
    }
    return false;
  }

  /**
   * {@link #runAndCompare} for a kernel of both lanes (VARKA-28): each column at its lane's
   * width, the drawn values of an int column narrowed to it, the long lane's {@code run} with
   * {@code intLits} and {@code longLits} as its two tables, and {@link VarkaReference#mixedValue}
   * as the oracle. An int-valued output is read at four bytes a row and sign-extended.
   */
  public static boolean runAndCompareMixed(String context, String className, byte[] bytes,
      List<VarkaVectorIR> roots, int numInputs, int[] intLits, long[] longLits, LongBatch batch,
      boolean declineAllowed, SparkAnswers spark) {
    return bodies(context, numInputs, batch.length(), batch.nulls(), batch.forceMasked(),
        (label, nulls, forced) -> runOne(label, className, bytes, roots, numInputs,
            new MixedLane(batch.withNulls(nulls, forced), intLits, longLits, roots),
            declineAllowed, spark, ValidityFill.BOTH))[0] == 0;
  }

  /** {@link #runAndCompareStatus} for a kernel of both lanes. */
  public static int runAndCompareMixedStatus(String context, String className, byte[] bytes,
      List<VarkaVectorIR> roots, int numInputs, int[] intLits, long[] longLits, LongBatch batch,
      boolean declineAllowed) {
    int[] statuses = bodies(context, numInputs, batch.length(), batch.nulls(),
        batch.forceMasked(), (label, nulls, forced) -> runOne(label, className, bytes, roots,
            numInputs, new MixedLane(batch.withNulls(nulls, forced), intLits, longLits, roots),
            declineAllowed, null, ValidityFill.BOTH));
    return statuses[0] != 0 ? statuses[0] : statuses[1];
  }
  /**
   * {@link #runAndCompare} for a kernel whose columns are stored narrow (VARKA-329): each column
   * at its own lane's width - a byte one byte a row, a short two, an int four - each output at
   * its root's, through the int entry point, with {@link VarkaReference#value} as the oracle. A
   * column's lane is the one the roots read it at, so a tree of narrow leaves alone drives the
   * narrow lane and one that also reads an int column drives the int lane.
   */
  public static boolean runAndCompareNarrow(String context, String className, byte[] bytes,
      List<VarkaVectorIR> roots, int numInputs, int[] lits, Batch batch, boolean declineAllowed,
      SparkAnswers spark) {
    return bodies(context, numInputs, batch.length(), batch.nulls(), batch.forceMasked(),
        (label, nulls, forced) -> runOne(label, className, bytes, roots, numInputs,
            new StoredNarrowLane(batch.withNulls(nulls, forced), lits, roots), declineAllowed,
            spark, ValidityFill.BOTH))[0] == 0;
  }


  /** {@link #runEachBodyStatus} for a kernel of both lanes. */
  public static List<Integer> runEachBodyMixedStatus(String context, String className,
      byte[] bytes, List<VarkaVectorIR> roots, int numInputs, int[] intLits, long[] longLits,
      LongBatch batch) {
    return each(context, numInputs, batch.length(), batch.nulls(), batch.forceMasked(),
        (label, nulls, forced) -> runOne(label, className, bytes, roots, numInputs,
            new MixedLane(batch.withNulls(nulls, forced), intLits, longLits, roots), true, null,
            ValidityFill.BOTH));
  }

  /**
   * What a caller does around the one kernel call of a compared batch (VARKA-274). The tier
   * witness reads the code cache and opens its JFR event in {@code before} and closes both in
   * {@code after}, so that its window holds the kernel's call and not the buffers' setup or the
   * row-by-row comparison after it.
   */
  public interface CallHook {
    void before();

    void after(int status);

    /** A hook that does nothing, for the entry points that load a class per batch. */
    CallHook NONE = new CallHook() {
      @Override public void before() {
      }

      @Override public void after(int status) {
      }
    };
  }

  /**
   * One batch, through the body its null counts select, on a kernel the caller has loaded and
   * keeps (VARKA-274's histories, which run many batches on one class): the status, 0 once every
   * row and validity bit is compared with {@link VarkaReference}. The kernel runs once per output
   * validity fill ({@link ValidityFill#BOTH}), as every compared batch does, and the hook's window
   * holds both runs. A decline fails the check unless
   * {@code declineAllowed}. Unlike {@link #runAndCompare} it runs one body only; the caller asks
   * {@link #otherBody} for the other.
   */
  public static int runLoaded(String context, VarkaFusedKernel kernel,
      List<VarkaVectorIR> roots, int numInputs, int[] lits, Batch batch, boolean declineAllowed,
      CallHook hook) {
    return runLoaded(context, kernel, roots, numInputs, new IntLane(batch, lits), declineAllowed,
        null, ValidityFill.BOTH, hook);
  }

  /** {@link #runLoaded} at the long lane. */
  public static int runLoadedLong(String context, VarkaFusedKernel kernel,
      List<VarkaVectorIR> roots, int numInputs, long[] lits, LongBatch batch,
      boolean declineAllowed, CallHook hook) {
    return runLoaded(context, kernel, roots, numInputs, longLane(batch, lits, NO_INT_LITS, roots),
        declineAllowed, null, ValidityFill.BOTH, hook);
  }

  /**
   * {@link #runLoadedLong} for a kernel that may hold both lanes, with {@code intLits} its int
   * literal table, every row also compared with Spark's answer (the conformance contract's long
   * lane). The one long-lane entry point whose reference truncates a narrowing root to its low 32
   * bits, as the store and Spark's LEGACY {@code CAST(x AS INT)} do; every other leaves a value
   * that does not fit to show as a difference ({@code LongLane}).
   */
  public static int runLoadedLong(String context, VarkaFusedKernel kernel,
      List<VarkaVectorIR> roots, int numInputs, long[] lits, int[] intLits, LongBatch batch,
      boolean declineAllowed, SparkAnswers spark, CallHook hook) {
    return runLoaded(context, kernel, roots, numInputs,
        holdsBothLanes(roots) ? new MixedLane(batch, intLits, lits, roots)
            : new LongLane(batch, lits, true),
        declineAllowed, spark, ValidityFill.BOTH, hook);
  }

  /**
   * {@link #runLoaded} with the columns stored narrow (VARKA-329), for the sweeps that run
   * thousands of batches on one class: {@link #runAndCompareNarrow} loads the class again for
   * every batch, which a 2^32-row sweep cannot afford.
   */
  public static int runLoadedNarrow(String context, VarkaFusedKernel kernel,
      List<VarkaVectorIR> roots, int numInputs, int[] lits, Batch batch, boolean declineAllowed,
      SparkAnswers spark, CallHook hook) {
    return runLoaded(context, kernel, roots, numInputs,
        new StoredNarrowLane(batch, lits, roots), declineAllowed, spark, ValidityFill.BOTH, hook);
  }

  /**
   * A planted wrong answer, for the test that a check which compares nothing cannot pass
   * (VARKA-274's test 5): while set, output 0's reference value on row 0 is one more than the
   * evaluator says. Set only by that test, in a JVM of its own.
   */
  static volatile boolean plantWrongAnswer;

  private static OptionalLong planted(OptionalLong want, int output, int row) {
    return plantWrongAnswer && output == 0 && row == 0 && want.isPresent()
        ? OptionalLong.of(want.getAsLong() + 1) : want;
  }

  /**
   * Whether {@code message} is the word-liveness invariant's own refusal, in either direction: a
   * word stored but never loaded ({@code VarkaBodyEmitter.assertWordsLive}) or a word declared
   * dead and loaded ({@code VarkaVectorWalk.loadWord}). The tests that plant
   * {@code misdescribeWordLiveness} hold every failure they count to it, so that a failure of
   * another kind cannot stand in for the planted bug (VARKA-269.md 9).
   */
  public static boolean isWordLivenessRefusal(String message) {
    return message.contains("stored but never loaded")
        || message.contains("declared dead is loaded");
  }

  /** {@link #isWordLivenessRefusal(String)} over {@code t}'s message and its causes'. */
  public static boolean isWordLivenessRefusal(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      String message = c.getMessage();
      if (message != null && isWordLivenessRefusal(message)) {
        return true;
      }
    }
    return false;
  }

  /** {@code run} over the batch's own body and then {@link #otherBody}'s, each status in order. */
  private static List<Integer> each(String context, int numInputs, int length,
      boolean[][] nulls, boolean forceMasked, BodyRun run) {
    var statuses = new ArrayList<Integer>();
    statuses.add(run.apply(context, nulls, forceMasked));
    otherBody(numInputs, length, nulls, forceMasked).ifPresent(other ->
        statuses.add(run.apply(context + " (the other body)", other.nulls(), other.forceMasked())));
    return statuses;
  }

  /**
   * The batch through {@code run} - its own body - and, once that compared, the same values
   * through {@link #otherBody}, so both of a kernel's bodies meet the oracle: each run's status,
   * the second 0 where it did not run.
   */
  private static int[] bodies(String context, int numInputs, int length, boolean[][] nulls,
      boolean forceMasked, BodyRun run) {
    int first = run.apply(context, nulls, forceMasked);
    if (first != 0) {
      return new int[] {first, 0};
    }
    var other = otherBody(numInputs, length, nulls, forceMasked);
    int second = other.isPresent()
        ? run.apply(context + " (the other body)", other.get().nulls(), other.get().forceMasked())
        : 0;
    return new int[] {first, second};
  }

  /**
   * The same values through the body the drawn batch did not take (VARKA-284): a kernel has a
   * dense body for a batch with no nulls and a masked one for every other, and a shape run on one
   * kind of batch has only that body compared. A batch that took the masked body goes again with
   * no nulls; one that took the dense body goes again with the masked one forced - a null count
   * over a full bitmap, or for one row every column null, a forced batch of one row being all-null
   * by contract and a null in a column the kernel does not read selecting nothing. Empty for a
   * kernel that reads no column, which has no masked body to choose.
   */
  public static Optional<OtherBody> otherBody(int numInputs, int length, boolean[][] nulls,
      boolean forceMasked) {
    if (numInputs == 0 || length == 0) {
      return Optional.empty();
    }
    boolean masked = forceMasked;
    for (int c = 0; c < nulls.length && !masked; c++) {
      for (int i = 0; i < length && !masked; i++) {
        masked = nulls[c][i];
      }
    }
    if (masked) {
      return Optional.of(new OtherBody(filled(numInputs, length, false), false));
    }
    return length > 1 ? Optional.of(new OtherBody(nulls, true))
        : Optional.of(new OtherBody(filled(numInputs, length, true), false));
  }

  private static boolean[][] filled(int numInputs, int length, boolean value) {
    var out = new boolean[numInputs][length];
    for (var column : out) {
      Arrays.fill(column, value);
    }
    return out;
  }

  // ---------------------------------------------------------------------------------------------
  // One batch through one body, at either lane.
  // ---------------------------------------------------------------------------------------------

  /**
   * What differs between the two lanes in one batch's run: the value's width, its poison and the
   * sentinel an unwritten output keeps, the {@code run} overload, the reference's overload, and
   * how an output is read back. {@link #runOne} is the one harness both lanes go through.
   */
  private sealed interface Lane permits IntLane, LongLane, MixedLane, StoredNarrowLane {
    int length();

    boolean[][] nulls();

    boolean forceMasked();

    /** A value's bytes in the lane. */
    long stride();

    /** A value's bytes in column {@code c}'s buffer: the lane's, but per column where they mix. */
    default long columnStride(int c) {
      return stride();
    }

    /**
     * A value's bytes in {@code root}'s output buffer; {@code rootLane} is the root's
     * {@link VarkaVectorIR#laneType}, which the caller reads once per output rather than once a
     * row, since it walks the root's tree.
     */
    default long outputStride(VarkaVectorIR root, VarkaVectorIR.LaneType rootLane) {
      return stride();
    }

    /** Writes {@code v} at row {@code i} of column {@code c}'s buffer, at its width. */
    default void putColumn(int c, MemorySegment d, int i, long v) {
      put(d, i, v);
    }

    /** Writes {@code v} at row {@code i} of {@code root}'s output buffer, at its width. */
    default void putOutput(VarkaVectorIR root, VarkaVectorIR.LaneType rootLane, MemorySegment d,
        int i, long v) {
      put(d, i, v);
    }

    /** {@link #poison} for column {@code c}, whose lane's extremes it takes. */
    default long poisonColumn(int c, int nullOrdinal) {
      return poison(nullOrdinal);
    }

    /** {@link #sentinel} for {@code root}'s output buffer. */
    default long sentinelOutput(VarkaVectorIR root, VarkaVectorIR.LaneType rootLane) {
      return sentinel();
    }

    /** Column {@code c}'s drawn value at row {@code i}, widened. */
    long value(int c, int i);

    /** Writes {@code v} at row {@code i} of a column or output buffer at the lane's width. */
    void put(MemorySegment d, int i, long v);

    /**
     * Poison for the {@code nullOrdinal}-th null of a column (VARKA-70's harness rule): the lane's
     * extremes in turn, so each sits on both sides of every bound whatever the null pattern.
     */
    long poison(int nullOrdinal);

    /** What an output buffer holds before the kernel writes, so an unwritten lane shows. */
    long sentinel();

    int run(VarkaFusedKernel kernel, long[] srcData, long[] srcValidity, int[] nullCounts,
        long[] outData, long[] outValidity, long scratch);

    /** The reference's value of {@code root} on the row {@code values}, {@code isNull}. */
    OptionalLong reference(VarkaVectorIR root, long[] values, boolean[] isNull);

    VarkaReference.Truth reference(Cond cond, long[] values, boolean[] isNull);

    /** Output {@code o}'s stored value at row {@code i}; {@code rootLane} as for the others. */
    long got(MemorySegment out, int i, VarkaVectorIR root, VarkaVectorIR.LaneType rootLane);
  }

  /** The 32-bit lane; {@code rowInts} is its reusable row for the reference. */
  private record IntLane(Batch batch, int[] lits, int[] rowInts) implements Lane {
    IntLane(Batch batch, int[] lits) {
      this(batch, lits, new int[batch.data().length]);
    }

    @Override public int length() {
      return batch.length();
    }

    @Override public boolean[][] nulls() {
      return batch.nulls();
    }

    @Override public boolean forceMasked() {
      return batch.forceMasked();
    }

    @Override public long stride() {
      return 4L;
    }

    @Override public long value(int c, int i) {
      return batch.data()[c][i];
    }

    @Override public void put(MemorySegment d, int i, long v) {
      d.set(ValueLayout.JAVA_INT, i * 4L, (int) v);
    }

    @Override public long poison(int nullOrdinal) {
      return VarkaEmitterFixtures.poison(nullOrdinal);
    }

    @Override public long sentinel() {
      return 0xDEADBEEF;
    }

    @Override public int run(VarkaFusedKernel kernel, long[] srcData, long[] srcValidity,
        int[] nullCounts, long[] outData, long[] outValidity, long scratch) {
      return kernel.run(srcData, srcValidity, nullCounts, outData, outValidity, lits, length(),
          scratch);
    }

    @Override public OptionalLong reference(VarkaVectorIR root, long[] values, boolean[] isNull) {
      for (int c = 0; c < values.length; c++) {
        rowInts[c] = (int) values[c];
      }
      OptionalInt v = VarkaReference.value(root, rowInts, isNull, lits);
      return v.isPresent() ? OptionalLong.of(v.getAsInt()) : OptionalLong.empty();
    }

    @Override public VarkaReference.Truth reference(Cond cond, long[] values, boolean[] isNull) {
      for (int c = 0; c < values.length; c++) {
        rowInts[c] = (int) values[c];
      }
      return VarkaReference.cond(cond, rowInts, isNull, lits);
    }

    @Override public long got(MemorySegment out, int i, VarkaVectorIR root,
        VarkaVectorIR.LaneType rootLane) {
      return out.get(ValueLayout.JAVA_INT, i * 4L);
    }
  }

  /** The 64-bit lane, whose narrowing roots store four bytes a row. */
  private record LongLane(LongBatch batch, long[] lits, boolean truncateNarrow) implements Lane {
    @Override public int length() {
      return batch.length();
    }

    @Override public boolean[][] nulls() {
      return batch.nulls();
    }

    @Override public boolean forceMasked() {
      return batch.forceMasked();
    }

    @Override public long stride() {
      return 8L;
    }

    @Override public long value(int c, int i) {
      return batch.data()[c][i];
    }

    @Override public void put(MemorySegment d, int i, long v) {
      d.set(ValueLayout.JAVA_LONG, i * 8L, v);
    }

    @Override public long poison(int nullOrdinal) {
      return (nullOrdinal & 1) == 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
    }

    @Override public long sentinel() {
      return 0xDEADBEEFCAFEBABEL;
    }

    @Override public int run(VarkaFusedKernel kernel, long[] srcData, long[] srcValidity,
        int[] nullCounts, long[] outData, long[] outValidity, long scratch) {
      return kernel.run(srcData, srcValidity, nullCounts, outData, outValidity, new int[0], lits,
          length(), scratch);
    }

    @Override public OptionalLong reference(VarkaVectorIR root, long[] values, boolean[] isNull) {
      OptionalLong v = VarkaReference.longValue(root, values, isNull, lits);
      // A narrowing root stores the low 32 bits of its child (NarrowLane), which got() reads
      // back, and the reference answers the child in full. Everywhere but the conformance path
      // the two are left to differ: the IR series adds a NarrowLane root wherever its bound
      // analysis says the value fits an int, so a value that does not shows as a difference.
      // The conformance path (truncateNarrow) holds every row to Spark's answer, and there a
      // root whose value can leave the int range is a LEGACY CAST(x AS INT) of a long, which
      // keeps the low 32 bits as the store does (an ANSI one raises, and is declined).
      return truncateNarrow && root instanceof NarrowLane && v.isPresent()
          ? OptionalLong.of((int) v.getAsLong()) : v;
    }

    @Override public VarkaReference.Truth reference(Cond cond, long[] values, boolean[] isNull) {
      return VarkaReference.longCond(cond, values, isNull, lits);
    }

    @Override public long got(MemorySegment out, int i, VarkaVectorIR root,
        VarkaVectorIR.LaneType rootLane) {
      return root instanceof NarrowLane ? out.get(ValueLayout.JAVA_INT, i * 4L)
          : out.get(ValueLayout.JAVA_LONG, i * 8L);
    }
  }

  /**
   * Both lanes in one kernel (VARKA-28): each column at its own lane's width - an int column four
   * bytes a row, its values the drawn ones narrowed - each output at its root's, the long lane's
   * eight-argument {@code run} with both literal tables, and {@link VarkaReference#mixedValue} as
   * the oracle. A column's lane is the one the roots read it at; one they do not read is held at
   * eight bytes a row.
   */
  private record MixedLane(LongBatch batch, int[] intLits, long[] longLits,
      VarkaVectorIR.LaneType[] columnLanes) implements Lane {
    MixedLane(LongBatch batch, int[] intLits, long[] longLits, List<VarkaVectorIR> roots) {
      this(batch, intLits, longLits, VarkaKernelChecks.columnLanes(roots, batch.data().length));
    }

    private boolean intColumn(int c) {
      return columnLanes[c] == VarkaVectorIR.LaneType.INT;
    }

    @Override public int length() {
      return batch.length();
    }

    @Override public boolean[][] nulls() {
      return batch.nulls();
    }

    @Override public boolean forceMasked() {
      return batch.forceMasked();
    }

    @Override public long stride() {
      return 8L;
    }

    // A mixed kernel holds int and long values only (a narrow value under a long kernel is
    // refused), and the putters below lay out four or eight bytes accordingly.
    @Override public long columnStride(int c) {
      return width(intColumn(c) ? VarkaVectorIR.LaneType.INT : VarkaVectorIR.LaneType.LONG);
    }

    @Override public long outputStride(VarkaVectorIR root, VarkaVectorIR.LaneType rootLane) {
      return width(rootLane == VarkaVectorIR.LaneType.INT
          ? VarkaVectorIR.LaneType.INT : VarkaVectorIR.LaneType.LONG);
    }

    @Override public long value(int c, int i) {
      long v = batch.data()[c][i];
      return intColumn(c) ? (int) v : v;
    }

    @Override public void put(MemorySegment d, int i, long v) {
      d.set(ValueLayout.JAVA_LONG, i * 8L, v);
    }

    @Override public void putColumn(int c, MemorySegment d, int i, long v) {
      if (intColumn(c)) {
        d.set(ValueLayout.JAVA_INT, i * 4L, (int) v);
      } else {
        put(d, i, v);
      }
    }

    @Override public void putOutput(VarkaVectorIR root, VarkaVectorIR.LaneType rootLane,
        MemorySegment d, int i, long v) {
      if (outputStride(root, rootLane) == 4L) {
        d.set(ValueLayout.JAVA_INT, i * 4L, (int) v);
      } else {
        put(d, i, v);
      }
    }

    @Override public long poison(int nullOrdinal) {
      return (nullOrdinal & 1) == 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
    }

    @Override public long poisonColumn(int c, int nullOrdinal) {
      return intColumn(c) ? VarkaEmitterFixtures.poison(nullOrdinal) : poison(nullOrdinal);
    }

    @Override public long sentinel() {
      return 0xDEADBEEFCAFEBABEL;
    }

    @Override public long sentinelOutput(VarkaVectorIR root, VarkaVectorIR.LaneType rootLane) {
      return outputStride(root, rootLane) == 4L ? 0xDEADBEEF : sentinel();
    }

    @Override public int run(VarkaFusedKernel kernel, long[] srcData, long[] srcValidity,
        int[] nullCounts, long[] outData, long[] outValidity, long scratch) {
      return kernel.run(srcData, srcValidity, nullCounts, outData, outValidity, intLits,
          longLits, length(), scratch);
    }

    @Override public OptionalLong reference(VarkaVectorIR root, long[] values, boolean[] isNull) {
      return VarkaReference.mixedValue(root, values, isNull, intLits, longLits);
    }

    @Override public VarkaReference.Truth reference(Cond cond, long[] values, boolean[] isNull) {
      return VarkaReference.mixedCond(cond, values, isNull, intLits, longLits);
    }

    @Override public long got(MemorySegment out, int i, VarkaVectorIR root,
        VarkaVectorIR.LaneType rootLane) {
      return outputStride(root, rootLane) == 4L ? out.get(ValueLayout.JAVA_INT, i * 4L)
          : out.get(ValueLayout.JAVA_LONG, i * 8L);
    }
  }

  /**
   * Columns stored narrow (VARKA-329): a byte column one byte a row, a short one two, an int one
   * four, each at the lane the roots read it at, through the int entry point - the narrow lanes
   * and the int lane share it, and only the long lane has its own. The drawn values are the
   * command's ints, narrowed on the way into a narrow column and sign-extended on the way out,
   * which is what {@link VarkaReference#value} reads back from the reference's row.
   *
   * <p>A narrow lane has no value outside its domain to poison or to seed an output with, so
   * {@link #poison} and {@link #sentinel} take its extremes and a batch must draw enough rows that
   * an unwritten one is caught by a neighbour's value. Every null of a column takes a different
   * extreme, as at the wider lanes.
   *
   * <p>A column the roots read through a {@link BoolColumn} is a {@code BOOLEAN} column, stored
   * as Arrow stores one: a bit a row, set where the drawn value is not zero. Under a null its
   * data bit alternates, so half the nulls carry a set bit, as Spark's writer may leave one
   * (it clears only the validity bit).
   */
  private record StoredNarrowLane(Batch batch, int[] lits, VarkaVectorIR.LaneType[] columnLanes,
      boolean[] bitColumns, int[] rowInts) implements Lane {
    StoredNarrowLane(Batch batch, int[] lits, List<VarkaVectorIR> roots) {
      this(batch, lits, VarkaKernelChecks.columnLanes(roots, batch.data().length),
          VarkaKernelChecks.bitColumns(roots, batch.data().length),
          new int[batch.data().length]);
    }

    /** {@code v} as {@code lane} stores it, sign-extended: the value the reference reads back. */
    private static long narrow(VarkaVectorIR.LaneType lane, long v) {
      return switch (lane) {
        case BYTE -> (byte) v;
        case SHORT -> (short) v;
        // A floating lane's value is its bits at its width (VARKA-341 2.3).
        case INT, FLOAT -> (int) v;
        case LONG, DOUBLE -> v;
      };
    }

    @Override public int length() {
      return batch.length();
    }

    @Override public boolean[][] nulls() {
      return batch.nulls();
    }

    @Override public boolean forceMasked() {
      return batch.forceMasked();
    }

    @Override public long stride() {
      return 1L;
    }

    @Override public long columnStride(int c) {
      // A bit column's buffer is over-allocated at a byte a row; the kernel reads its bytes.
      return bitColumns[c] ? 1L : width(columnLanes[c]);
    }

    @Override public long outputStride(VarkaVectorIR root, VarkaVectorIR.LaneType rootLane) {
      return width(rootLane);
    }

    @Override public long value(int c, int i) {
      return bitColumns[c] ? (batch.data()[c][i] != 0 ? 1L : 0L)
          : narrow(columnLanes[c], batch.data()[c][i]);
    }

    @Override public void putColumn(int c, MemorySegment d, int i, long v) {
      if (bitColumns[c]) {
        long off = i / 8L;
        int bit = 1 << (i % 8);
        int b = d.get(ValueLayout.JAVA_BYTE, off);
        d.set(ValueLayout.JAVA_BYTE, off, (byte) (v != 0 ? b | bit : b & ~bit));
      } else {
        putAt(columnLanes[c], d, i, v);
      }
    }

    @Override public void putOutput(VarkaVectorIR root, VarkaVectorIR.LaneType rootLane,
        MemorySegment d, int i, long v) {
      putAt(rootLane, d, i, v);
    }

    @Override public void put(MemorySegment d, int i, long v) {
      putAt(VarkaVectorIR.LaneType.INT, d, i, v);
    }

    private static void putAt(VarkaVectorIR.LaneType lane, MemorySegment d, int i, long v) {
      switch (lane) {
        case BYTE -> d.set(ValueLayout.JAVA_BYTE, i, (byte) v);
        case SHORT -> d.set(ValueLayout.JAVA_SHORT, i * 2L, (short) v);
        case INT, FLOAT -> d.set(ValueLayout.JAVA_INT, i * 4L, (int) v);
        case LONG, DOUBLE -> d.set(ValueLayout.JAVA_LONG, i * 8L, v);
      }
    }

    @Override public long poison(int nullOrdinal) {
      return (nullOrdinal & 1) == 0 ? Byte.MIN_VALUE : Byte.MAX_VALUE;
    }

    @Override public long poisonColumn(int c, int nullOrdinal) {
      boolean low = (nullOrdinal & 1) == 0;
      if (bitColumns[c]) {
        return low ? 0L : 1L;
      }
      return switch (columnLanes[c]) {
        case BYTE -> low ? Byte.MIN_VALUE : Byte.MAX_VALUE;
        case SHORT -> low ? Short.MIN_VALUE : Short.MAX_VALUE;
        case INT, LONG, FLOAT, DOUBLE -> VarkaEmitterFixtures.poison(nullOrdinal);
      };
    }

    @Override public long sentinel() {
      return Byte.MIN_VALUE;
    }

    @Override public long sentinelOutput(VarkaVectorIR root, VarkaVectorIR.LaneType rootLane) {
      return switch (rootLane) {
        case BYTE -> Byte.MIN_VALUE;
        case SHORT -> Short.MIN_VALUE;
        case INT, LONG, FLOAT, DOUBLE -> 0xDEADBEEF;
      };
    }

    @Override public int run(VarkaFusedKernel kernel, long[] srcData, long[] srcValidity,
        int[] nullCounts, long[] outData, long[] outValidity, long scratch) {
      return kernel.run(srcData, srcValidity, nullCounts, outData, outValidity, lits, length(),
          scratch);
    }

    @Override public OptionalLong reference(VarkaVectorIR root, long[] values, boolean[] isNull) {
      for (int c = 0; c < values.length; c++) {
        rowInts[c] = (int) values[c];
      }
      OptionalInt v = VarkaReference.value(root, rowInts, isNull, lits);
      return v.isPresent() ? OptionalLong.of(v.getAsInt()) : OptionalLong.empty();
    }

    @Override public VarkaReference.Truth reference(Cond cond, long[] values, boolean[] isNull) {
      for (int c = 0; c < values.length; c++) {
        rowInts[c] = (int) values[c];
      }
      return VarkaReference.cond(cond, rowInts, isNull, lits);
    }

    @Override public long got(MemorySegment out, int i, VarkaVectorIR root,
        VarkaVectorIR.LaneType rootLane) {
      return switch (rootLane) {
        case BYTE -> out.get(ValueLayout.JAVA_BYTE, i);
        case SHORT -> out.get(ValueLayout.JAVA_SHORT, i * 2L);
        case INT, FLOAT -> out.get(ValueLayout.JAVA_INT, i * 4L);
        case LONG, DOUBLE -> out.get(ValueLayout.JAVA_LONG, i * 8L);
      };
    }
  }

  /** The inputs {@code roots} read through a {@link BoolColumn}: the bit-stored columns. */
  static boolean[] bitColumns(List<VarkaVectorIR> roots, int numInputs) {
    var bits = new boolean[numInputs];
    var seen = new HashSet<VarkaVectorIR>();
    var todo = new ArrayDeque<VarkaVectorIR>(roots);
    while (!todo.isEmpty()) {
      VarkaVectorIR node = todo.pop();
      if (!seen.add(node)) {
        continue;
      }
      if (node instanceof BoolColumn b && b.ordinal() < numInputs) {
        bits[b.ordinal()] = true;
      }
      for (VarkaVectorIR child : VarkaVectorIR.childrenOf(node)) {
        todo.push(child);
      }
    }
    return bits;
  }

  /**
   * Bytes of a value at {@code lane}, which is also its stride: production's own figure
   * ({@code Lane.byteStride}), so the harness lays a buffer out as the emitter addresses it rather
   * than by a table of its own (VARKA-494).
   */
  static long width(VarkaVectorIR.LaneType lane) {
    return org.apache.spark.sql.catalyst.expressions.codegen.varka.Lane.of(lane).byteStride;
  }

  /** Each of {@code numInputs} columns' lane as {@code roots} read it; the long lane if unread. */
  static VarkaVectorIR.LaneType[] columnLanes(List<VarkaVectorIR> roots, int numInputs) {
    var lanes = new VarkaVectorIR.LaneType[numInputs];
    Arrays.fill(lanes, VarkaVectorIR.LaneType.LONG);
    var seen = new HashSet<VarkaVectorIR>();
    var todo = new ArrayDeque<VarkaVectorIR>(roots);
    while (!todo.isEmpty()) {
      VarkaVectorIR node = todo.pop();
      if (!seen.add(node)) {
        continue;
      }
      if (node instanceof ColumnRef c && c.ordinal() < numInputs) {
        lanes[c.ordinal()] = c.lane();
      }
      for (VarkaVectorIR child : VarkaVectorIR.childrenOf(node)) {
        todo.push(child);
      }
    }
    return lanes;
  }

  /**
   * Loads the generated class. A failure the class's own construction throws is rethrown as it is
   * - a {@code NoSuchMethodError} from a misdescribed helper call stays one - so the failure
   * classifier names it, instead of reading the reflection that carried it.
   */
  static VarkaFusedKernel load(VarkaGeneratedClassLoader loader, String className,
      byte[] bytes) {
    VarkaSpeciesGuard.check(className, bytes);
    loader.defineGeneratedClass(className, bytes);
    try {
      return (VarkaFusedKernel) loader.loadClass(className).getConstructor().newInstance();
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Error error) {
        throw error;
      }
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * What the harness stores under the {@code nullOrdinal}-th null of a column the kernel reads
   * through the column bound: the bound's two ends in turn, not the lane's. That guard reads a
   * null row's stored day, by design (VARKA-379 11), so the lane's extremes there would decline
   * every batch with a null and leave nothing compared; production stores day 0, which a Spark
   * writer leaves under a null. The bound's ends are still the furthest a stored day can sit from
   * zero without declining, so the column's other readers meet them as they met the poison.
   */
  static long storedUnderNull(int nullOrdinal) {
    return (nullOrdinal & 1) == 0 ? VarkaChrono.CONTRACT_MIN_DAYS : VarkaChrono.CONTRACT_MAX_DAYS;
  }

  /** The inputs {@code roots} read through the column bound, as a bit set by ordinal. */
  static long columnBoundColumns(List<VarkaVectorIR> roots) {
    long columns = 0L;
    var seen = new HashSet<VarkaVectorIR>();
    var todo = new ArrayDeque<VarkaVectorIR>(roots);
    while (!todo.isEmpty()) {
      VarkaVectorIR node = todo.pop();
      if (!seen.add(node)) {
        continue;
      }
      if (node instanceof GuardedRange g && Analysis.guardsStoredValues(g)
          && g.child() instanceof ColumnRef c) {
        columns |= 1L << c.ordinal();
      }
      for (VarkaVectorIR child : VarkaVectorIR.childrenOf(node)) {
        todo.push(child);
      }
    }
    return columns;
  }

  /** A batch's input buffers as a kernel's {@code run} takes them. */
  private record Inputs(long[] data, long[] validity, int[] nullCounts) { }

  /**
   * The batch's columns in buffers of {@code arena}. Null lanes are poisoned; a column with no
   * null reports one under {@code forceMasked}, and an all-null or null-free column hands over
   * no bitmap, as the morsel contract says. A column {@code roots} read through the column bound
   * is poisoned with that bound's ends instead ({@link #storedUnderNull}).
   */
  private static Inputs inputs(Arena arena, Lane lane, int numInputs,
      List<VarkaVectorIR> roots) {
    long boundColumns = columnBoundColumns(roots);
    int length = lane.length();
    long[] srcData = new long[numInputs];
    long[] srcValidity = new long[numInputs];
    int[] nullCounts = new int[numInputs];
    for (int c = 0; c < numInputs; c++) {
      var d = VarkaEmitterFixtures.alloc(arena, length * lane.columnStride(c));
      var v = VarkaEmitterFixtures.alloc(arena, wordBytes(length));
      v.fill((byte) 0);
      int nulls = 0;
      for (int i = 0; i < length; i++) {
        if (lane.nulls()[c][i]) {
          // Poisoned, not left at the drawn value. The data is drawn inside `columnBound` and
          // `MONTH_ARITH_MAX_MONTHS`, so a null lane holding its drawn value is in range by
          // construction and can never reach a guard's condemning comparison - which is the
          // one thing the fuzzer is here to reach.
          lane.putColumn(c, d, i, (boundColumns >>> c & 1L) != 0 ? storedUnderNull(nulls)
              : lane.poisonColumn(c, nulls));
          nulls++;
        } else {
          lane.putColumn(c, d, i, lane.value(c, i));
          long off = i / 8L;
          v.set(ValueLayout.JAVA_BYTE, off,
              (byte) (v.get(ValueLayout.JAVA_BYTE, off) | (1 << (i % 8))));
        }
      }
      srcData[c] = d.address();
      nullCounts[c] = lane.forceMasked() && nulls == 0 ? 1 : nulls;
      srcValidity[c] = nullCounts[c] == 0 || nulls == length ? 0L : v.address();
    }
    return new Inputs(srcData, srcValidity, nullCounts);
  }

  /** One run's output buffers: per output its values and validity, and their addresses. */
  private record Outputs(MemorySegment[] values, MemorySegment[] bits, long[] data,
      long[] validity) { }

  /**
   * Output buffers for {@code roots} in {@code arena}: values at the lane's sentinel, so a lane
   * the kernel fails to write shows, and every validity byte, whole words to the last, at
   * {@code fill}. A {@code Cond} root's data address is 0L, as the kernel contract says; a
   * {@link BoolOutput}'s data is a bitmap of whole words holding {@link #STALE_DATA}.
   */
  private static Outputs outputs(Arena arena, Lane lane, VarkaVectorIR[] roots,
      ValidityFill fill) {
    int length = lane.length();
    int n = roots.length;
    var values = new MemorySegment[n];
    var bits = new MemorySegment[n];
    long[] data = new long[n];
    long[] validity = new long[n];
    for (int o = 0; o < n; o++) {
      if (roots[o] instanceof BoolOutput) {
        values[o] = VarkaEmitterFixtures.alloc(arena, wordBytes(length));
        values[o].fill(STALE_DATA);
      } else {
        VarkaVectorIR.LaneType rootLane = roots[o].laneType();
        values[o] =
            VarkaEmitterFixtures.alloc(arena, length * lane.outputStride(roots[o], rootLane));
        long sentinel = lane.sentinelOutput(roots[o], rootLane);
        for (int i = 0; i < length; i++) {
          lane.putOutput(roots[o], rootLane, values[o], i, sentinel);
        }
      }
      bits[o] = VarkaEmitterFixtures.alloc(arena, wordBytes(length));
      bits[o].fill(fill.value());
      data[o] = roots[o] instanceof Cond ? 0L : values[o].address();
      validity[o] = bits[o].address();
    }
    return new Outputs(values, bits, data, validity);
  }

  /** The kernel over {@code in} into {@code out}, in the sanitizer's window: its status. */
  private static int run(VarkaFusedKernel kernel, Lane lane, VarkaVectorIR[] roots, Inputs in,
      Outputs out) {
    long[] inStride = new long[in.data().length];
    for (int c = 0; c < inStride.length; c++) {
      inStride[c] = lane.columnStride(c);
    }
    long[] outStride = new long[roots.length];
    for (int o = 0; o < roots.length; o++) {
      outStride[o] = roots[o] instanceof BoolOutput
          ? BIT_STRIDE : lane.outputStride(roots[o], roots[o].laneType());
    }
    return sanitized(kernel, lane.length(), inStride, outStride, in.data(), in.validity(),
        out.data(), out.validity(), scratch -> lane.run(kernel, in.data(), in.validity(),
            in.nullCounts(), out.data(), out.validity(), scratch));
  }

  /**
   * One batch through the check, whichever body its null counts select: the kernel's status, 0
   * once the rows are compared. The kernel runs once per fill in {@code fills}, each run into
   * output buffers of its own whose validity bytes all hold that fill (VARKA-138), so a bit the
   * kernel fails to set shows at one fill and a bit it fails to clear at the other. The class is
   * loaded once and the reference evaluated once per row for all the runs; the runs must return
   * the same status, since a kernel's decline reads its inputs and never its outputs.
   *
   * <p>Past the rows, each run's last validity byte must hold zeros: {@code setValid} and
   * {@code copyValidity} in {@code VarkaVectorSupport} promise "exactly the low {@code rows}
   * bits ... and zero past them in the final byte", and the zeroing and OR paths keep the same
   * bits zero, so every fill must read the same there. The bytes after the nominal
   * {@code (length + 7) / 8} are not checked: an output the kernel writes byte-wise maps only
   * the nominal bytes ({@code VarkaBodyEmitter.emitOutputSegments}), so they are not its own.
   */
  private static int runOne(String context, String className, byte[] bytes,
      List<VarkaVectorIR> rootList, int numInputs, Lane lane, boolean declineAllowed,
      SparkAnswers spark, List<ValidityFill> fills) {
    if (!reuseLoaded) {
      var loader = new VarkaGeneratedClassLoader(VarkaKernelChecks.class.getClassLoader());
      var kernel = load(loader, className, bytes);
      KERNELS_DEFINED.incrementAndGet();
      try {
        return runLoaded(context, kernel, rootList, numInputs, lane, declineAllowed, spark,
            fills, CallHook.NONE);
      } finally {
        loader.release();
      }
    }
    return runLoaded(context, LAST_LOADED.get().kernel(className, bytes), rootList, numInputs,
        lane, declineAllowed, spark, fills, CallHook.NONE);
  }

  /**
   * Whether {@link #runOne} runs a kernel whose bytes it loaded last on this thread on that class
   * rather than defining it again. The suites check one kernel over several batches and each
   * batch through both bodies, a run apiece; defining it once a run made a tree of the series
   * eight classes, and the definitions, their verification and the unloading of their loaders
   * were a large share of the series' time (`m8/results/VARKA-suite-profile.txt`). An emitted
   * kernel holds no state between calls - its only fields are the static final range tables its
   * initialiser fills - so a run on the loaded class is the run a fresh one would make. Kept
   * switchable, {@code -Dvarka.checks.reuseLoaded=false}, so that the two can be timed and
   * compared.
   */
  static volatile boolean reuseLoaded =
      !"false".equals(System.getProperty("varka.checks.reuseLoaded"));

  /** How many kernel classes {@link #runOne} has defined in this JVM, for the reuse's test. */
  static final AtomicLong KERNELS_DEFINED = new AtomicLong();

  /**
   * The kernel {@link #runOne} loaded last on this thread, under its own loader: the suites that
   * call it from several threads each keep their own. Matched on the name and the bytes; the bytes
   * matter because a planted fault rewrites them and keeps the name. Its loader is released when
   * the thread loads another kernel, so each thread holds at most one such loader; releasing one
   * only drops its references, so the last kernel a thread ran stays loaded until then.
   */
  private static final ThreadLocal<LastLoaded> LAST_LOADED =
      ThreadLocal.withInitial(LastLoaded::new);

  private static final class LastLoaded {
    private String className;
    private byte[] bytes;
    private VarkaGeneratedClassLoader loader;
    private VarkaFusedKernel kernel;

    VarkaFusedKernel kernel(String name, byte[] code) {
      if (kernel != null && name.equals(className) && Arrays.equals(code, bytes)) {
        return kernel;
      }
      if (loader != null) {
        loader.release();
      }
      loader = null;
      kernel = null;
      var fresh = new VarkaGeneratedClassLoader(VarkaKernelChecks.class.getClassLoader());
      // A class that fails to load is not kept: the next run loads it again and fails the same.
      try {
        kernel = load(fresh, name, code);
      } catch (RuntimeException | Error e) {
        fresh.release();
        throw e;
      }
      KERNELS_DEFINED.incrementAndGet();
      loader = fresh;
      className = name;
      bytes = code.clone();
      return kernel;
    }
  }

  /**
   * {@link #runOne} on a kernel already loaded, so that a caller can run many batches on one
   * class (VARKA-274): {@code hook} is told just before the kernel's first call and just after
   * its last, one per fill, so that its window holds the kernel's calls and not the comparison.
   */
  private static int runLoaded(String context, VarkaFusedKernel kernel,
      List<VarkaVectorIR> rootList, int numInputs, Lane lane, boolean declineAllowed,
      SparkAnswers spark, List<ValidityFill> fills, CallHook hook) {
    int length = lane.length();
    var roots = rootList.toArray(new VarkaVectorIR[0]);
    try (var arena = Arena.ofConfined()) {
      var in = inputs(arena, lane, numInputs, rootList);
      int n = roots.length;
      int runs = fills.size();
      var outs = new Outputs[runs];
      int status = 0;
      for (int f = 0; f < runs; f++) {
        outs[f] = outputs(arena, lane, roots, fills.get(f));
      }
      hook.before();
      int[] statuses = new int[runs];
      for (int f = 0; f < runs; f++) {
        statuses[f] = run(kernel, lane, roots, in, outs[f]);
      }
      hook.after(statuses[runs - 1]);
      for (int f = 0; f < runs; f++) {
        if (f > 0 && statuses[f] != status) {
          throw new IllegalStateException(context + ": the kernel's status depends on the output "
              + "validity fill (" + status + fills.get(f - 1).suffix() + ", " + statuses[f]
              + fills.get(f).suffix() + ")");
        }
        status = statuses[f];
      }
      if (status != 0 && declineAllowed) {
        if (VarkaAnswerLog.enabled()) {
          VarkaAnswerLog.record(context, VarkaAnswerLog.declined(status));
        }
        return status;
      }
      if (status != 0) {
        throw new IllegalStateException(
            context + ": the kernel declined the batch (status " + status + ")");
      }
      long[] values = new long[numInputs];
      boolean[] isNull = new boolean[numInputs];
      int[] nullCounts = in.nullCounts();
      VarkaVectorIR.LaneType[] rootLanes = lanesOf(roots);
      for (int i = 0; i < length; i++) {
        for (int c = 0; c < numInputs; c++) {
          isNull[c] = lane.nulls()[c][i];
          values[c] = lane.value(c, i);
        }
        for (int o = 0; o < n; o++) {
          var root = roots[o];
          if (root instanceof BoolOutput b) {
            // Spark's three-valued answer as two bitmaps: the data bit is the known-true row and
            // the validity bit the known one, so an unknown row is null, never false.
            var truth = lane.reference(b.cond(), values, isNull);
            boolean known = truth != VarkaReference.Truth.UNKNOWN;
            if (spark != null) {
              agreeWithSpark(context, spark, o, i, values, isNull,
                  known ? OptionalLong.of(truth.isTrue() ? 1L : 0L) : OptionalLong.empty());
            }
            for (int f = 0; f < runs; f++) {
              boolean valid = bit(outs[f].bits()[o], i);
              boolean data = bit(outs[f].values()[o], i);
              if (valid != known || (known && data != truth.isTrue())) {
                throw new IllegalStateException(context + fills.get(f).suffix() + ": boolean "
                    + "output " + o + " row " + i + " differs (want " + truth + ", got "
                    + (valid ? Boolean.toString(data) : "null") + ")"
                    + evidence(values, isNull, nullCounts));
              }
            }
          } else if (root instanceof Cond c) {
            var truth = lane.reference(c, values, isNull);
            if (spark != null) {
              agreeWithSpark(context, spark, o, i, values, isNull,
                  truth == VarkaReference.Truth.UNKNOWN ? OptionalLong.empty()
                      : OptionalLong.of(truth.isTrue() ? 1L : 0L));
            }
            for (int f = 0; f < runs; f++) {
              boolean bit = bit(outs[f].bits()[o], i);
              if (bit != truth.isTrue()) {
                throw new IllegalStateException(context + fills.get(f).suffix() + ": selection row "
                    + i + " differs (want " + truth.isTrue() + ", got " + bit + ")"
                    + evidence(values, isNull, nullCounts));
              }
            }
          } else {
            OptionalLong want = planted(lane.reference(root, values, isNull), o, i);
            if (spark != null) {
              agreeWithSpark(context, spark, o, i, values, isNull, want);
            }
            for (int f = 0; f < runs; f++) {
              boolean bit = bit(outs[f].bits()[o], i);
              long got = lane.got(outs[f].values()[o], i, root, rootLanes[o]);
              // The fill's name is formatted only for a failure: this loop runs once a row.
              if (bit != want.isPresent()) {
                String at = context + fills.get(f).suffix();
                throw new IllegalStateException(at + ": validity of output " + o + " row " + i
                    + " differs (want " + shown(want) + ", got "
                    + (bit ? "Some(" + got + ")" : "None") + ")"
                    + evidence(values, isNull, nullCounts));
              }
              if (want.isPresent() && got != want.getAsLong()) {
                String at = context + fills.get(f).suffix();
                throw new IllegalStateException(at + ": output " + o + " row " + i
                    + " differs (want " + want.getAsLong() + ", got " + got + ")"
                    + evidence(values, isNull, nullCounts));
              }
            }
          }
        }
      }
      if (length % 8 != 0) {
        for (int f = 0; f < runs; f++) {
          for (int o = 0; o < n; o++) {
            int last = outs[f].bits()[o].get(ValueLayout.JAVA_BYTE, length / 8L) & 0xFF;
            int past = last >>> (length % 8);
            if (past != 0) {
              throw new IllegalStateException(context + fills.get(f).suffix() + ": validity of "
                  + "output " + o + " has bits set past row " + (length - 1) + " in its last "
                  + "byte (0x" + Integer.toHexString(last) + ")");
            }
            // A boolean output's data bitmap is zeroed by the driver and written to the batch's
            // last row, so its last byte holds zeros past it as the validity does.
            if (roots[o] instanceof BoolOutput) {
              int lastData = outs[f].values()[o].get(ValueLayout.JAVA_BYTE, length / 8L) & 0xFF;
              if (lastData >>> (length % 8) != 0) {
                throw new IllegalStateException(context + fills.get(f).suffix() + ": data of "
                    + "boolean output " + o + " has bits set past row " + (length - 1)
                    + " in its last byte (0x" + Integer.toHexString(lastData) + ")");
              }
            }
          }
        }
      }
      if (VarkaAnswerLog.enabled()) {
        VarkaAnswerLog.record(context, digest(lane, roots, outs));
      }
      return 0;
    }
  }

  /**
   * The compared answers of one batch, folded for {@link VarkaAnswerLog}: per run (one per fill),
   * per output and per row, the validity bit and the value where valid; a selection output's
   * value is its bit, and a boolean output's its data bit.
   */
  private static long digest(Lane lane, VarkaVectorIR[] roots, Outputs[] outs) {
    long h = VarkaAnswerLog.START;
    for (Outputs out : outs) {
      for (int o = 0; o < roots.length; o++) {
        boolean selection = roots[o] instanceof Cond;
        boolean bitOutput = roots[o] instanceof BoolOutput;
        VarkaVectorIR.LaneType rootLane = roots[o].laneType();
        for (int i = 0; i < lane.length(); i++) {
          boolean bit = bit(out.bits()[o], i);
          long value = !bit || selection ? 0L
              : bitOutput ? (bit(out.values()[o], i) ? 1L : 0L)
              : lane.got(out.values()[o], i, roots[o], rootLane);
          h = VarkaAnswerLog.row(h, bit, value);
        }
      }
    }
    return h;
  }

  /**
   * {@link #validityBytesWritten}'s run: the batch once at {@code fill}, and the count of the
   * outputs' validity bytes, every byte of the whole-word buffers, that no longer hold it.
   */
  private static int validityBytesWritten(String className, byte[] bytes,
      List<VarkaVectorIR> rootList, int numInputs, Lane lane, ValidityFill fill) {
    var roots = rootList.toArray(new VarkaVectorIR[0]);
    var loader = new VarkaGeneratedClassLoader(VarkaKernelChecks.class.getClassLoader());
    var kernel = load(loader, className, bytes);
    try (var arena = Arena.ofConfined()) {
      var out = outputs(arena, lane, roots, fill);
      run(kernel, lane, roots, inputs(arena, lane, numInputs, rootList), out);
      int changed = 0;
      for (var bits : out.bits()) {
        for (long b = 0; b < bits.byteSize(); b++) {
          if (bits.get(ValueLayout.JAVA_BYTE, b) != fill.value()) {
            changed++;
          }
        }
      }
      return changed;
    } finally {
      loader.release();
    }
  }

  /** Each root's {@link VarkaVectorIR#laneType}, read once for a batch's rows. */
  private static VarkaVectorIR.LaneType[] lanesOf(VarkaVectorIR[] roots) {
    var lanes = new VarkaVectorIR.LaneType[roots.length];
    for (int o = 0; o < roots.length; o++) {
      lanes[o] = roots[o].laneType();
    }
    return lanes;
  }

  /** Bit {@code i} of a validity or selection bitmap, LSB first as Arrow lays it out. */
  private static boolean bit(MemorySegment bits, int i) {
    return (bits.get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) != 0;
  }
}
