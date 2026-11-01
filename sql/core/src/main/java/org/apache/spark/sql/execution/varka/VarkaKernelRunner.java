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


package org.apache.spark.sql.execution.varka;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;

import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.BaseFixedWidthVector;
import org.apache.arrow.vector.BitVector;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.IntRangeOps;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.TruncLevelLeaf;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaAllocationSampler;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDerivedKind;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaErrors;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFusedKernel;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelWarmth;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelWarmup;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMemorySanitizer;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMemoryViolation;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaReproduction;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSegments;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaShapeEntry;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.WeekdayLeaf;
import org.apache.spark.sql.vectorized.ArrowColumnVector;
import org.apache.spark.sql.vectorized.ColumnarBatch;

/**
 * The fused loop serving one task, plus the {@code run} argument arrays, allocated once here and
 * refilled per batch - nothing is allocated per call. The class comes from the shape cache -
 * shared across tasks and released on cache eviction, so its C2 code survives the task boundary -
 * and only the kernel instance and these arrays are the task's own.
 *
 * <p>The plan arrives as plain arrays (the caller converts the compiled plan once), so the
 * per-batch {@link #fill} and {@link #invoke} touch no Scala collection and box nothing.
 */
public final class VarkaKernelRunner {

  /**
   * The decline status the evaluator itself reports when an input lane lies outside a bound the
   * compiler recorded - bit 8, above the kernels' refusal bits ({@code VarkaFusedKernel}, bits 0
   * to 5), so a log line and the counts by reason tell them apart. Never returned by an emitted
   * kernel.
   */
  public static final int STATUS_INPUT_BOUND = 1 << 8;

  /**
   * The decline status the evaluator reports when a derived input met a value its row-engine
   * definition raises on under ANSI - an unrecognised weekday name - bit 9. The row engine
   * recomputes the batch and raises where a non-null date sits beside the name.
   */
  public static final int STATUS_DERIVED_INPUT = 1 << 9;

  /** The decline status of a batch a test declined through the hooks, bit 10. */
  public static final int STATUS_FORCED = 1 << 10;

  /**
   * Whether each batch a kernel serves is run again through its other body and the two compared
   * (VARKA-303): {@code -Dvarka.checkBothBodies=true}, which {@code dev/varka_matrix.sh} puts on
   * every test JVM. A kernel has a dense body for a batch with no nulls and a masked one for every
   * other, and a query's data decides which a batch takes, so an end-to-end suite compares one of
   * them with the row engine; this holds the other to it. Off in production.
   */
  public static final boolean CHECK_BOTH_BODIES = Boolean.getBoolean("varka.checkBothBodies");

  /**
   * A kernel input the compiler bounded: its position among the kernel's inputs and the closed
   * interval every live value must lie in.
   */
  public record Bound(int input, int lo, int hi) {}

  /** Test hooks, read per batch: a kernel failure injected, a decline forced. */
  public interface Hooks {
    boolean failKernel();

    /**
     * The status a test plants on every batch the kernel would have served, as a kernel whose
     * guards fired returns it, or 0 for none; {@link #STATUS_FORCED} where the test names no
     * reason.
     */
    int declineStatus();

    VarkaAllocationSampler.Schedule allocationSchedule();
  }

  /** The shape's warm state, shared with every task that runs the shape. */
  public final VarkaKernelWarmth warmth;
  public final String shapeHash;
  public final byte[] classBytes;
  public final LaneType lane;
  public final long[] srcData;
  public final long[] srcValidity;
  public final int[] srcNullCount;
  public final long[] dstData;
  public final long[] dstValidity;
  /**
   * Bytes per value of each output, set with {@link #dstData}: the vector's width for a value
   * output, zero for a selection bitmap, whose data slot is unused, and
   * {@link VarkaReproduction.KernelCall#BIT_OUTPUT} for a boolean output, whose data is a bitmap.
   * Read by the both-bodies check and recorded in a reproduction.
   */
  public final int[] dstWidth;
  public final int[] scalarArgs;
  public final long[] longArgs;

  private final VarkaShapeEntry entry;
  private final VarkaFusedKernel kernel;
  /** Bytes of scratch per row the kernel's {@code run} takes; zero for most kernels. */
  private final int scratchBytesPerRow;
  private final int[] inputOrdinals;
  private final VarkaDerivedKind[] derived;
  private final Bound[] bounds;
  private final VarkaKernelScratch scratch;
  private final VarkaFallbackAccounting accounting;
  private final Hooks hooks;

  /**
   * @param derived each kernel input's derivation, or null for a column read as it is
   * @param bounds the kernel inputs the compiler bounded
   */
  public VarkaKernelRunner(VarkaShapeEntry entry, LaneType lane, int[] inputOrdinals,
      VarkaDerivedKind[] derived, Bound[] bounds, int numOutputs, int[] scalarArgs,
      long[] longArgs, VarkaKernelScratch scratch, VarkaFallbackAccounting accounting,
      Hooks hooks) {
    this.entry = entry;
    this.kernel = entry.newKernel();
    this.scratchBytesPerRow = kernel.scratchBytesPerRow();
    this.warmth = entry.warmth();
    this.shapeHash = entry.shapeHash();
    this.classBytes = entry.classBytes();
    this.lane = lane;
    this.inputOrdinals = inputOrdinals;
    this.derived = derived;
    this.bounds = bounds;
    this.srcData = new long[inputOrdinals.length];
    this.srcValidity = new long[inputOrdinals.length];
    this.srcNullCount = new int[inputOrdinals.length];
    this.dstData = new long[numOutputs];
    this.dstValidity = new long[numOutputs];
    this.dstWidth = new int[numOutputs];
    this.scalarArgs = scalarArgs;
    this.longArgs = longArgs;
    this.scratch = scratch;
    this.accounting = accounting;
    this.hooks = hooks;
  }

  /** Another instance of the shape's class, which no task runs: the warm-up's own. */
  public VarkaFusedKernel newKernel() {
    return entry.newKernel();
  }

  /**
   * The width the warm-up reads for this fixed-width vector: its type width, or
   * {@link VarkaKernelWarmup#BIT_WIDTH} for a {@code BitVector}, whose bitmap it reads as one bit
   * a row. The two widths are one place's answer to "how wide is this input", {@link
   * #bytesPerRow} being the other's, so neither is read from {@code getTypeWidth} alone.
   */
  public static int warmupWidth(BaseFixedWidthVector v) {
    return v instanceof BitVector ? VarkaKernelWarmup.BIT_WIDTH : v.getTypeWidth();
  }

  /**
   * The width a reproduction records for this fixed-width vector: its type width, or 1 for a
   * {@code BitVector}, which reports 0 (it stores one bit a row) and would size the replay's copy
   * of its data at nothing. One byte a row over-states the bitmap eight times, which is safe for a
   * replay's allocation and for the batch cap; the data itself is the bitmap's own
   * {@code (len + 7) / 8} bytes. The warm-up does not use this: it reads 0 as one bit a row
   * ({@code VarkaKernelWarmup.BIT_WIDTH}).
   */
  public static int bytesPerRow(BaseFixedWidthVector v) {
    return v instanceof BitVector ? 1 : v.getTypeWidth();
  }

  /**
   * Fills the source-side argument arrays from the input batch - one column per referenced input,
   * in dense kernel-input order. {@code canRun} has vouched for every column this reads: each is an
   * Arrow vector holding exactly the batch's rows, which is what makes the vector's null count the
   * batch's null count, and so what makes the all-null test sound. A column's validity address is
   * zero when every row is null; the kernels never dereference it then.
   *
   * <p>A derived input is computed here, before the kernel runs, into the task's scratch: the
   * string column goes through the row engine's own parser and the kernel reads the int32 result
   * like any other input. The leaves never throw; under ANSI an unrecognised weekday name declines
   * the batch, and the row engine - which parses a name only beside a non-null date - raises its
   * own error where one is due. The trunc level has no such route: an unrecognised format is a
   * null lane in every mode, as it is a NULL result on the row engine.
   *
   * <p>An input the compiler bounded - today a day offset that came from {@code CAST(i AS INTERVAL
   * DAY)}, which Spark's cast throws on past the bound - is checked before the kernel runs, over
   * its live lanes only. A lane outside declines the batch the same way a kernel status does: the
   * row engine recomputes it and raises the error the kernel cannot.
   */
  public void fill(ColumnarBatch input, int len) {
    for (int i = 0; i < inputOrdinals.length; i++) {
      var acv = (ArrowColumnVector) input.column(inputOrdinals[i]);
      VarkaDerivedKind kind = derived[i];
      if (kind == null) {
        var v = (BaseFixedWidthVector) acv.getValueVector();
        if (len != v.getValueCount()) {
          throw VarkaErrors.illegalArgument("requirement failed: rowCount " + len
              + " does not match the vector value count " + v.getValueCount());
        }
        int nullCount = v.getNullCount();
        srcData[i] = v.getDataBuffer().memoryAddress();
        srcValidity[i] = nullCount == len ? 0L : v.getValidityBuffer().memoryAddress();
        srcNullCount[i] = nullCount;
        if (VarkaMemorySanitizer.ENABLED) {
          VarkaMemorySanitizer.register("input data", i, v.getDataBuffer());
          if (nullCount != len) {
            VarkaMemorySanitizer.register("input validity", i, v.getValidityBuffer());
          }
        }
      } else {
        scratch.ensureDerived(i, len);
        ArrowBuf data = scratch.derivedData(i);
        ArrowBuf validity = scratch.derivedValidity(i);
        if (VarkaMemorySanitizer.ENABLED) {
          VarkaMemorySanitizer.guard("derived data", i, data, Math.max(len * 4L, 8L));
          VarkaMemorySanitizer.guard("derived validity", i, validity, ((len + 63) / 64) * 8L);
        }
        // No default: a derived kind added to the enum is a compile error here, not a silent
        // trip down the weekday path.
        int nulls = switch (kind) {
          case TRUNC_LEVEL ->
              TruncLevelLeaf.fill(acv, len, data.memoryAddress(), validity.memoryAddress());
          case WEEKDAY, WEEKDAY_ANSI ->
              WeekdayLeaf.fill(acv, len, kind.failOnError, WeekdayLeaf.DEFAULT_PARSER,
                  data.memoryAddress(), validity.memoryAddress());
        };
        if (nulls == WeekdayLeaf.DECLINED) {
          throw new VarkaBatchDeclined(STATUS_DERIVED_INPUT);
        }
        // The leaf writes (len + 7) / 8 validity bytes; the rest of the words the kernel reads is
        // zeroed so a longer earlier batch's bits cannot read as lanes past `len`. The bound is
        // what the scratch was sized to need, not its capacity: the scratch grows and is never
        // shrunk, so zeroing to capacity would memset bytes nothing reads.
        int written = (len + 7) / 8;
        long readable = ((len + 63) / 64) * 8L;
        validity.setZero(written, readable - written);
        srcData[i] = data.memoryAddress();
        srcValidity[i] = nulls == len ? 0L : validity.memoryAddress();
        srcNullCount[i] = nulls;
      }
    }
    for (Bound b : bounds) {
      int k = b.input();
      if (!IntRangeOps.allWithin(srcData[k], srcValidity[k], srcNullCount[k], len, b.lo(),
          b.hi())) {
        throw new VarkaBatchDeclined(STATUS_INPUT_BOUND);
      }
    }
  }

  /**
   * Invokes the emitted loop, marking any catchable throw as {@link VarkaKernelFailure} so the
   * exec nodes' catch can tell a genuine kernel error from a failure in the per-row machinery
   * that shares the same try (VARKA-21 review). A fatal error, and a memory violation of the
   * sanitizer, pass unmarked. A non-zero status means the kernel met a value its lowering is not
   * defined over and declined the batch: the outputs it wrote are not answers, and the batch
   * takes the caller's fallback path, signalled by a throw because that is the one path every
   * caller already routes to the fallback.
   */
  public void invoke(int len) {
    boolean sampled = accounting.sampleDue(hooks.allocationSchedule());
    long before = sampled ? VarkaAllocationSampler.allocatedBytes() : 0L;
    // Grown outside the try below, as the derived inputs' buffers are: an allocator's failure is
    // the per-batch machinery's, not the kernel's, and must not be marked as the kernel's.
    long scratchAddress = scratch.kernelScratchAddress(scratchBytesPerRow, len);
    if (VarkaMemorySanitizer.ENABLED && scratchAddress != 0L) {
      VarkaMemorySanitizer.guard("kernel scratch", 0, scratch.kernelScratchBuffer(),
          (long) scratchBytesPerRow * len);
    }
    int status;
    try {
      if (hooks.failKernel()) {
        // checkstyle.off: RegexpSinglelineJava
        throw new NoClassDefFoundError("injected Varka kernel failure");
        // checkstyle.on: RegexpSinglelineJava
      }
      // One emitted class is one lane, and each lane has its own `run`: the seven-argument form
      // reads the int literal table, the eight-argument one adds the long table. The plan's lane
      // is fixed at compile time, so this is a branch on a final field. The wrong overload would
      // not run a wrong kernel - each default throws naming the lane - but that throw would be a
      // fallback with a misleading cause.
      if (lane.bits() == Long.SIZE) {
        status = kernel.run(srcData, srcValidity, srcNullCount, dstData, dstValidity, scalarArgs,
            longArgs, len, scratchAddress);
      } else {
        status = kernel.run(srcData, srcValidity, srcNullCount, dstData, dstValidity, scalarArgs,
            len, scratchAddress);
      }
    } catch (Throwable e) {
      if (!isCatchable(e)) {
        throw e;
      }
      throw new VarkaKernelFailure(e, null, shapeHash);
    }
    // After the kernel has returned and not from a throw out of it, so that a canary does not
    // replace the failure it follows; a no-op unless the sanitizer is on.
    VarkaMemorySanitizer.verifyCanaries();
    if (sampled) {
      accounting.allocationSample(VarkaAllocationSampler.allocatedBytes() - before, len);
    }
    int planted = status != 0 ? status : hooks.declineStatus();
    if (planted != 0) {
      throw new VarkaBatchDeclined(planted);
    }
    if (CHECK_BOTH_BODIES) {
      checkOtherBody(len, scratchAddress);
    }
  }

  /**
   * The batch just served, through the body it did not take, into buffers of its own, compared
   * with what it wrote (VARKA-303). A batch with no nulls goes through the masked body forced - a
   * null count of one over a full bitmap per input - and every row must agree. A batch with nulls
   * goes through the dense body, which reads every lane as valid, and the rows where every input
   * is valid must agree; that run may decline, since a null lane holds whatever value a guard
   * then reads, and a declined run compares nothing. A disagreement, or a throw from the other
   * body, fails the task: under test, the kernel's two answers to one batch are one answer.
   */
  private void checkOtherBody(int len, long scratchAddress) {
    int inputs = srcData.length;
    int outputs = dstValidity.length;
    if (len == 0 || inputs == 0) {
      return;
    }
    boolean dense = Arrays.stream(srcNullCount).allMatch(n -> n == 0);
    if (dense && len == 1) {
      // A null count equal to the length is the all-null column by contract, so one row cannot
      // be forced down the masked body with its value kept.
      return;
    }
    long words = ((len + 63) / 64) * 8L;
    try (Arena arena = Arena.ofConfined()) {
      long[] validity = srcValidity.clone();
      int[] nulls = srcNullCount.clone();
      if (dense) {
        for (int i = 0; i < inputs; i++) {
          MemorySegment ones = arena.allocate(words, 8);
          ones.fill((byte) 0xFF);
          validity[i] = ones.address();
          nulls[i] = 1;
          VarkaMemorySanitizer.register("other body: input validity", i, ones.address(), words);
        }
      } else {
        Arrays.fill(nulls, 0);
      }
      long[] data = new long[outputs];
      long[] valid = new long[outputs];
      for (int o = 0; o < outputs; o++) {
        if (dstData[o] != 0L) {
          long bytes =
              Math.max(VarkaReproduction.KernelCall.outputDataBytes(dstWidth[o], len), 8L);
          data[o] = arena.allocate(bytes, 8).address();
          VarkaMemorySanitizer.register("other body: output data", o, data[o], bytes);
        }
        valid[o] = arena.allocate(words, 8).address();
        VarkaMemorySanitizer.register("other body: output validity", o, valid[o], words);
      }
      int status;
      try {
        status = lane.bits() == Long.SIZE
            ? kernel.run(srcData, validity, nulls, data, valid, scalarArgs, longArgs, len,
                scratchAddress)
            : kernel.run(srcData, validity, nulls, data, valid, scalarArgs, len, scratchAddress);
      } catch (Throwable e) {
        if (e instanceof VarkaMemoryViolation || !isCatchable(e)) {
          throw e;
        }
        throw VarkaErrors.illegalState(shapeHash + "'s " + (dense ? "masked" : "dense")
            + " body threw on a batch its other body served (VARKA-303)", e);
      }
      if (status != 0) {
        if (dense) {
          throw VarkaErrors.illegalState(shapeHash + "'s masked body declined a null-free batch "
              + "its dense body served, status " + status + " (VARKA-303)");
        }
        return;
      }
      compareBodies(shapeHash, len, dense, srcValidity, srcNullCount, dstWidth,
          new BodyOutputs(dstData, dstValidity), new BodyOutputs(data, valid));
    }
  }

  /**
   * One body's output buffers, by output index: each data address (0L for a selection bitmap,
   * which has no data) and each validity address. A pair, so that a call cannot hand one body's
   * data as the other's or a validity as data.
   */
  // Arrays on purpose: the kernel's own parameter arrays, read once by the compare and never
  // compared or used as a key, so the identity equality they give the record is never observed.
  @SuppressWarnings("ArrayRecordComponent")
  public record BodyOutputs(long[] data, long[] validity) {
    public BodyOutputs {
      if (data.length != validity.length) {
        throw VarkaErrors.illegalArgument(data.length + " data addresses and "
            + validity.length + " validity addresses");
      }
    }
  }

  /**
   * Each output's rows in {@code served} against {@code other}'s, where both are defined; see
   * above. {@code dstWidth} is both bodies' width of each output; a row of a nullable batch is
   * compared only where every input is valid. Static so that a test can plant a disagreement
   * without a kernel that makes one.
   */
  public static void compareBodies(String shapeHash, int len, boolean dense, long[] srcValidity,
      int[] srcNullCount, int[] dstWidth, BodyOutputs served, BodyOutputs other) {
    long[] dstData = served.data();
    long[] dstValidity = served.validity();
    long[] data = other.data();
    long[] valid = other.validity();
    long bitmapBytes = ((long) len + 7) / 8;
    MemorySegment[] inputValidity = new MemorySegment[srcNullCount.length];
    for (int i = 0; i < srcNullCount.length; i++) {
      if (srcNullCount[i] != 0 && srcValidity[i] != 0L) {
        inputValidity[i] = VarkaSegments.map(srcValidity[i], bitmapBytes);
      }
    }
    for (int o = 0; o < dstValidity.length; o++) {
      MemorySegment servedBits = VarkaSegments.map(dstValidity[o], bitmapBytes);
      MemorySegment otherBits = VarkaSegments.map(valid[o], bitmapBytes);
      int w = dstWidth[o];
      boolean bits = w == VarkaReproduction.KernelCall.BIT_OUTPUT;
      // A bit output's data is compared bit by bit, as its validity is.
      long dataBytes = bits ? bitmapBytes : (long) len * w;
      MemorySegment servedData =
          dstData[o] == 0L ? null : VarkaSegments.map(dstData[o], dataBytes);
      MemorySegment otherData = dstData[o] == 0L ? null : VarkaSegments.map(data[o], dataBytes);
      for (int r = 0; r < len; r++) {
        if (!dense && !allInputsValid(srcNullCount, inputValidity, r)) {
          continue;
        }
        boolean bit = bit(servedBits, r);
        boolean sameData = servedData == null || !bit || (bits
            ? bit(servedData, r) == bit(otherData, r)
            : servedData.asSlice((long) r * w, w)
                .mismatch(otherData.asSlice((long) r * w, w)) < 0);
        boolean same = bit == bit(otherBits, r) && sameData;
        if (!same) {
          throw VarkaErrors.illegalState(shapeHash + "'s dense and masked bodies disagree on "
              + "output " + o + " at row " + r + " of a " + (dense ? "null-free" : "nullable")
              + " batch of " + len + " (VARKA-303)");
        }
      }
    }
  }

  private static boolean allInputsValid(int[] srcNullCount, MemorySegment[] inputValidity,
      int r) {
    for (int i = 0; i < srcNullCount.length; i++) {
      if (srcNullCount[i] == 0) {
        continue;
      }
      if (inputValidity[i] == null || !bit(inputValidity[i], r)) {
        return false;
      }
    }
    return true;
  }

  private static boolean bit(MemorySegment bitmap, int r) {
    return (bitmap.get(ValueLayout.JAVA_BYTE, r >>> 3) & (1 << (r & 7))) != 0;
  }

  /**
   * The call the kernel just failed in, for its reproduction file (VARKA-315): each input as the
   * kernel read it, its values and validity copied off the buffers {@link #fill} pointed it at,
   * with the capacity of each buffer the sanitizer registers. Called only after a failure inside
   * {@link #invoke}, when {@code fill} has completed for {@code input}, so the source arrays are
   * this batch's; it allocates, on that failure only.
   *
   * @param maxBatchBytes the most bytes of values to copy; past it, the call keeps every input's
   *                      shape and no bytes
   * @param selectionBitmaps whether the outputs are a filter's selection bitmaps
   */
  public VarkaReproduction.KernelCall capture(ColumnarBatch input, int len, long maxBatchBytes,
      boolean selectionBitmaps) {
    int n = inputOrdinals.length;
    var widths = new int[n];
    var bits = new boolean[n];
    var dataBufs = new ArrowBuf[n];
    var validityBufs = new ArrowBuf[n];
    for (int i = 0; i < n; i++) {
      if (derived[i] == null) {
        var v = (BaseFixedWidthVector)
            ((ArrowColumnVector) input.column(inputOrdinals[i])).getValueVector();
        widths[i] = bytesPerRow(v);
        bits[i] = v instanceof BitVector;
        dataBufs[i] = v.getDataBuffer();
        validityBufs[i] = v.getValidityBuffer();
      } else {
        widths[i] = 4;
        dataBufs[i] = scratch.derivedData(i);
        validityBufs[i] = scratch.derivedValidity(i);
      }
    }
    var shapes = new ArrayList<VarkaReproduction.Input>();
    for (int i = 0; i < n; i++) {
      shapes.add(new VarkaReproduction.Input(widths[i], dataBufs[i].capacity(),
          srcValidity[i] == 0L ? 0L : validityBufs[i].capacity(), srcNullCount[i],
          derived[i] != null, null, null));
    }
    long bytes = VarkaReproduction.KernelCall.bytesOf(len, shapes);
    String omitted = bytes > maxBatchBytes
        ? "the inputs' " + bytes + " bytes are over the limit of " + maxBatchBytes : null;
    var inputs = new ArrayList<VarkaReproduction.Input>();
    for (int i = 0; i < n; i++) {
      VarkaReproduction.Input shape = shapes.get(i);
      byte[] data = null;
      byte[] validity = null;
      if (omitted == null) {
        // A bit vector holds one bit a row, so its data is (len + 7) / 8 bytes.
        data = new byte[bits[i] ? (len + 7) / 8 : len * widths[i]];
        dataBufs[i].getBytes(0, data);
        if (srcValidity[i] != 0L && srcNullCount[i] != 0) {
          validity = new byte[(int) Math.min(validityBufs[i].capacity(), ((len + 63L) / 64) * 8)];
          validityBufs[i].getBytes(0, validity);
        }
      }
      inputs.add(new VarkaReproduction.Input(shape.width(), shape.dataCapacity(),
          shape.validityCapacity(), shape.nullCount(), shape.derived(), data, validity));
    }
    var outputWidths = new ArrayList<Integer>();
    for (int w : dstWidth) {
      outputWidths.add(w);
    }
    return new VarkaReproduction.KernelCall(len, scratchBytesPerRow, outputWidths, inputs,
        omitted, selectionBitmaps);
  }

  /**
   * A kernel failure worth falling back on, rather than one that has to fail the task: what
   * Scala's {@code NonFatal} admits, and a linkage error, which a kernel class that cannot link
   * raises.
   */
  public static boolean isCatchable(Throwable e) {
    // A memory violation is the sanitizer's finding, never a kernel failure: falling back on it
    // would let the row engine answer for a kernel that read outside its buffers.
    return !(e instanceof VarkaMemoryViolation)
        && (scala.util.control.NonFatal.apply(e) || e instanceof LinkageError);
  }
}
