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
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.apache.spark.internal.SparkLogger;
import org.apache.spark.internal.SparkLoggerFactory;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelWarmth.State;

/**
 * Gets a newly emitted kernel compiled before it serves batches: one JVM-wide daemon thread that
 * runs each queued kernel on a copy of a real batch until HotSpot has compiled it, while the
 * shape's own batches take Spark's row path ({@link VarkaKernelWarmth}).
 *
 * <p><b>Why a kernel needs this.</b> A kernel's methods are called once per batch and loop a few
 * hundred times per call, so a new class reaches none of HotSpot's compile thresholds over a query
 * of ten batches, and until C2 compiles it every Vector API operation is a library call that
 * allocates its result - slower than Spark's own row code. Here the calls are short,
 * {@link #SLICE_ROWS} rows, so the invocation counters the thresholds read advance hundreds of
 * times faster per row than on real batches, and the compile is requested after a few thousand
 * calls rather than after hundreds of batches.
 *
 * <p><b>How it knows the compile landed.</b> By allocation, not by a count. A kernel that is not
 * compiled yet allocates a vector box per operation; a compiled one allocates almost nothing -
 * only the memory segments its driver makes for each column it reads or writes, a few hundred
 * bytes per column per call on a wide kernel, where C2 does not inline every call they are
 * passed to. The warm-up measures its own thread's allocation over a probe block of calls, and a
 * block is clean when it is within {@link #SEGMENT_BYTES_PER_COLUMN} per column per call over the
 * species-pollution check's allowance ({@link VarkaAllocationSampler}) and allocates at most a
 * quarter ({@link #COMPILED_DROP}) of what the first block did; {@link #CLEAN_PROBES} clean blocks
 * in a row are the verdict. The per-column term keeps a wide kernel's segments from reading as
 * boxing, and the drop keeps a narrow kernel's boxing from reading as segments. A count cannot
 * say any of this: crossing a threshold only queues a compile, which lands whenever a compiler
 * thread reaches it. A kernel whose first block is already clean has nothing to wait for - its
 * driver returns before any loop runs, as it does over an all-null input - and is released at
 * once.
 *
 * <p><b>What it runs on.</b> A copy of a real batch of the shape, taken on the task thread before
 * that batch is released: its kernel inputs, tiled to {@link #SNAPSHOT_ROWS} rows, so that C2
 * compiles from real values and real guard outcomes rather than a synthetic batch's. The kernel
 * has two drivers, one for batches whose inputs are all null-free and one for batches with nulls
 * (`PLAN_TASK_10.md` 2.5), and the batch that claims the warm-up says nothing about which of the
 * two the shape's later batches will need, so the calls alternate between them and the verdict
 * waits for both - unless no input of the shape is nullable, when no batch can reach the masked
 * driver and only the dense one is warmed. A call to the dense driver passes every input as
 * null-free; the values under the batch's nulls are replaced with a valid value of the same input
 * first, so that it computes on the input's own domain. A call to the masked driver passes each
 * input, in turn, with and without validity - the batch's own bits where it has nulls, all bits
 * set where it has none, and for an input that is all null its own count, so that the driver's
 * all-null shortcut is taken too - so that C2 sees every input's null test go both ways. That is
 * sound because the kernel tests a count only against zero and the length and otherwise reads
 * the validity bits.
 * A call runs {@link #SLICE_ROWS} rows plus the batch's own length modulo that, which leaves the
 * batch's remainder past the last whole lane group at every lane count that divides it: the
 * epilogues then run, or return at once, as they do on the real batches, and C2 compiles them
 * from that profile rather than one the warm-up made up.
 *
 * <p><b>What it costs.</b> One thread's CPU while a kernel warms, plus the C2 compiles the kernel
 * needs in any case before it can run fast. After {@link #SPIN_CALLS} calls, past every threshold
 * at its default, the warm-up only probes, every {@link #PACE_MILLIS} milliseconds, while the
 * compile queue works. A shape without a verdict {@link #DEADLINE_SECONDS} seconds after its
 * warm-up was queued is released, waiting included, and its tasks then run the kernel, which the
 * warm-up's calls have already profiled.
 *
 * <p><b>Who gets one.</b> Only a kernel emitted to be warmed ({@link VarkaShapeKey#warmed}), which
 * a session asks for when its warm-up is on and {@link #canWarm} says this JVM can: that name is
 * what the compiler directive keeping C1 off the warm-up's methods matches
 * ({@link VarkaKernelCompileDirective}).
 */
public final class VarkaKernelWarmup {

  private static final SparkLogger LOG = SparkLoggerFactory.getLogger(VarkaKernelWarmup.class);

  /** Rows of the copied batch; a shorter batch is repeated to fill them. */
  static final int SNAPSHOT_ROWS = 1024;

  /**
   * Rows per call before the batch's remainder: two lane groups at the widest int species, so
   * every loop's back edge is taken in the profile C2 reads, and short enough that a call costs
   * little at interpreted speed.
   */
  static final int SLICE_ROWS = 32;

  /** Slices start on 64-row boundaries, so a slice's validity address is word-aligned. */
  private static final int SLICE_STRIDE = 64;

  private static final int MAX_CALL_ROWS = 2 * SLICE_ROWS - 1;

  private static final int NUM_SLICES = (SNAPSHOT_ROWS - MAX_CALL_ROWS) / SLICE_STRIDE + 1;

  /** The argument sets: each slice once for the dense driver and once for the masked one. */
  private static final int NUM_CALLS = 2 * NUM_SLICES;

  /** Calls between probes while the warm-up spins. */
  private static final int BLOCK_CALLS = 64;

  /** Calls one allocation probe measures. */
  private static final int PROBE_CALLS = 16;

  /** Clean probes in a row that make the verdict. */
  static final int CLEAN_PROBES = 2;

  /**
   * What a compiled kernel may allocate per column it reads or writes, per call: its driver's
   * memory segments, which escape into the calls C2 leaves out of line on a wide kernel. Measured
   * at about 130 bytes a column on a 54-output kernel; a boxing kernel allocates thousands.
   */
  static final int SEGMENT_BYTES_PER_COLUMN = 256;

  /** How far below the first probe block a clean block must be. */
  static final int COMPILED_DROP = 4;

  /**
   * Calls after which the warm-up stops spinning: 8000 per driver, well past JDK 25's tier-4
   * invocation threshold (5000 calls) at its default scale, so both drivers' compiles have been
   * requested and only a busy compile queue stands between the kernel and its verdict.
   */
  static final int SPIN_CALLS = 16000;

  /** The pause between probes once the warm-up has stopped spinning. */
  static final int PACE_MILLIS = 5;

  /**
   * How long after its warm-up was queued a shape waits for the verdict before it is released,
   * time in the queue included. A hundred-entry kernel is some fifty methods per driver that C2
   * compiles one after another on a four-core machine's two compiler threads, which takes
   * seconds; the deadline is for a compile that never comes.
   */
  static final int DEADLINE_SECONDS = 60;

  /**
   * Warm-ups waiting for the worker at most, besides the one it runs. A shape that finds the
   * queue full hands its claim back, and a later batch of it claims the shape again.
   */
  static final int QUEUE_CAPACITY = 16;

  private static final int RECENT_OUTCOMES = 64;

  private static final ArrayBlockingQueue<Job> QUEUE = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

  // Warm-ups queued or running, for awaitIdle; incremented before a job is offered and decremented
  // after it has finished and freed its copy.
  private static final AtomicInteger PENDING = new AtomicInteger();

  private static final ArrayDeque<Outcome> OUTCOMES = new ArrayDeque<>();

  // Replaced when it has died; see ensureWorker.
  private static Thread worker;

  private VarkaKernelWarmup() {
  }

  /**
   * One finished warm-up: how it ended, what it ran, how long the job waited in the queue and
   * then ran, and the evidence - what the first probe block allocated, with the kernel surely not
   * compiled yet, against what the last one did. For the cold-start benchmark and diagnostics.
   */
  public record Outcome(String shapeHash, State state, int calls, long rows, long queuedNanos,
      long runNanos, long firstProbeBytes, long lastProbeBytes) {
  }

  /**
   * Whether this JVM can warm kernels: it measures a thread's allocation, and a warm-up can end in
   * C2 code ({@link VarkaKernelCompileDirective#readyForWarmup}, which adds the directive the first
   * time it is asked). A session emits its kernels to be warmed only when this is true, and the
   * answer does not change once it has been given.
   */
  public static boolean canWarm() {
    return VarkaAllocationSampler.supported() && VarkaKernelCompileDirective.readyForWarmup();
  }

  /**
   * Whether a session with the warm-up set as {@code warmupEnabled} warms its kernels in this
   * JVM, and so emits them warmed ({@link VarkaShapeKey#warmed}). The one place that decides:
   * the evaluator's key and the planner's size admission must agree on it, or a shape is emitted
   * twice, once under each name.
   */
  public static boolean warms(boolean warmupEnabled) {
    return warmupEnabled && canWarm();
  }

  /**
   * Copies one batch's kernel inputs and queues a warm-up of {@code kernel} on the copy. Called on
   * the task thread, which must keep the batch alive until this returns; the arrays are the
   * evaluator's argument arrays, already filled for the batch, and are copied rather than kept.
   * Returns false when no warm-up was queued: having handed the claim back when the queue is
   * full, so that a later batch can claim the shape, and having released {@code warmth} when the
   * batch is empty or this JVM cannot warm at all. A throw leaves {@code warmth} to the caller.
   *
   * @param srcWidths the bytes per row of each input's data, four or eight.
   * @param nullable whether any input of the shape is nullable, so that a batch can reach the
   *        kernel's masked driver and the warm-up must compile it too.
   * @param kernel an instance of the shape's class for the warm-up's own use; kernels keep no
   *        state between calls, so it only has to be a different object from the tasks' ones.
   */
  public static boolean start(VarkaKernelWarmth warmth, String shapeHash, VarkaFusedKernel kernel,
      boolean longLane, long[] srcData, long[] srcValidity, int[] srcNullCount, int[] srcWidths,
      boolean nullable, int length, int numOutputs, int[] scalarArgs, long[] longArgs) {
    if (!canWarm() || length <= 0) {
      warmth.release();
      return false;
    }
    if (QUEUE.remainingCapacity() == 0) {
      // Checked before the copy, so that a burst of new shapes does not copy a batch per claim.
      warmth.unclaim();
      return false;
    }
    Job job = new Job(warmth, shapeHash, kernel, longLane, srcData, srcValidity, srcNullCount,
        srcWidths, nullable, length, numOutputs, scalarArgs, longArgs);
    boolean queued = false;
    try {
      ensureWorker();
      PENDING.incrementAndGet();
      queued = QUEUE.offer(job);
      if (!queued) {
        PENDING.decrementAndGet();
        warmth.unclaim();
      }
      return queued;
    } finally {
      if (!queued) {
        job.close();
      }
    }
  }

  /**
   * Waits until no warm-up is queued or running, or the timeout passes, and says which. For
   * benchmarks and tests that need the JVM quiet, or a shape's verdict, before they go on.
   */
  public static boolean awaitIdle(long timeoutMillis) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (PENDING.get() > 0) {
      if (System.nanoTime() - deadline > 0) {
        return false;
      }
      Thread.sleep(5);
    }
    return true;
  }

  /** The most recent warm-ups' outcomes, oldest first. */
  public static List<Outcome> recentOutcomes() {
    synchronized (OUTCOMES) {
      return List.copyOf(OUTCOMES);
    }
  }

  /**
   * Starts the worker if there is none or it has died. It is a daemon that lives as long as the
   * JVM, so it takes none of the claiming task's context: not its thread-locals, and not its
   * context class loader, which may be a session's that would otherwise never be unloaded.
   */
  private static synchronized void ensureWorker() {
    if (worker == null || !worker.isAlive()) {
      Thread t = Thread.ofPlatform().daemon().name("varka-kernel-warmup")
          .inheritInheritableThreadLocals(false).unstarted(VarkaKernelWarmup::work);
      t.setContextClassLoader(VarkaKernelWarmup.class.getClassLoader());
      t.start();
      worker = t;
    }
  }

  private static void work() {
    while (true) {
      Job job;
      try {
        job = QUEUE.take();
      } catch (InterruptedException e) {
        // Nothing interrupts this thread on purpose; keep serving the queue.
        continue;
      }
      try {
        job.run();
      } catch (Throwable t) {
        // Job.run handles the kernel's own failures; this is what escaped it, and a thread that
        // serves every shape in the JVM must outlive one job whatever that was.
        job.warmth.release();
        LOG.warn("Varka kernel warm-up of " + VarkaShapeCacheImpl.sourceFileFor(job.shapeHash)
            + " failed; the shape serves batches without one.", t);
      } finally {
        job.close();
        PENDING.decrementAndGet();
      }
    }
  }

  private static void record(Outcome outcome) {
    synchronized (OUTCOMES) {
      if (OUTCOMES.size() == RECENT_OUTCOMES) {
        OUTCOMES.removeFirst();
      }
      OUTCOMES.addLast(outcome);
    }
  }

  /** One queued warm-up: the copied batch in off-heap memory it owns, and the calls it makes. */
  static final class Job {

    final VarkaKernelWarmth warmth;
    final String shapeHash;
    private final VarkaFusedKernel kernel;
    private final boolean longLane;
    private final Arena arena;
    private final long queuedAt = System.nanoTime();

    // One set of source arguments per slice and driver, built once, so a call does nothing but
    // invoke the kernel; every call runs the same number of rows. Even sets call the dense
    // driver, odd ones the masked driver.
    private final long[][] srcData;
    private final long[][] srcValidity;
    private final int[][] srcNullCount;
    private final int rows;
    private final long[] dstData;
    private final long[] dstValidity;
    private final int[] scalarArgs;
    private final long[] longArgs;
    private final int columns;

    Job(VarkaKernelWarmth warmth, String shapeHash, VarkaFusedKernel kernel, boolean longLane,
        long[] batchData, long[] batchValidity, int[] batchNullCount, int[] widths,
        boolean nullable, int length, int numOutputs, int[] scalarArgs, long[] longArgs) {
      this.warmth = warmth;
      this.shapeHash = shapeHash;
      this.kernel = kernel;
      this.longLane = longLane;
      this.scalarArgs = scalarArgs.clone();
      this.longArgs = longArgs.clone();
      this.arena = Arena.ofShared();
      this.columns = batchData.length + numOutputs;
      try {
        int numInputs = batchData.length;
        long[] dataBase = new long[numInputs];
        long[] validityBase = new long[numInputs];
        for (int i = 0; i < numInputs; i++) {
          MemorySegment data =
              arena.allocate((long) (SNAPSHOT_ROWS + SLICE_STRIDE) * widths[i], SLICE_STRIDE);
          MemorySegment validity = arena.allocate(validityBytes(SNAPSHOT_ROWS), SLICE_STRIDE);
          if (batchNullCount[i] == 0) {
            tileData(batchData[i], widths[i], length, data);
            validity.fill((byte) -1);
          } else if (partlyNull(batchNullCount[i], length)) {
            tileData(batchData[i], widths[i], length, data);
            tileValidity(batchValidity[i], length, validity);
            fillNullRows(data, widths[i], validity);
          }
          // An input that is all null keeps zeroed values and bits: it has no valid value.
          dataBase[i] = data.address();
          validityBase[i] = validity.address();
        }
        this.rows = SLICE_ROWS + length % SLICE_ROWS;
        this.srcData = new long[NUM_CALLS][numInputs];
        this.srcValidity = new long[NUM_CALLS][numInputs];
        this.srcNullCount = new int[NUM_CALLS][numInputs];
        for (int v = 0; v < NUM_CALLS; v++) {
          int slice = v / 2;
          int start = slice * SLICE_STRIDE;
          for (int i = 0; i < numInputs; i++) {
            srcData[v][i] = dataBase[i] + (long) start * widths[i];
            // The dense driver's calls leave every count at zero. The masked driver's pass each
            // input with validity on every other slice, alternating between inputs, so that
            // every slice has one input with nulls and every input is seen both ways.
            boolean withNulls =
                nullable && v % 2 == 1 && (numInputs == 1 || (slice + i) % 2 == 0);
            if (withNulls) {
              srcValidity[v][i] = validityBase[i] + start / 8;
              boolean allNull = batchNullCount[i] >= length;
              srcNullCount[v][i] = allNull && slice % 4 < 2 ? rows : 1;
            }
          }
        }
        this.dstData = new long[numOutputs];
        this.dstValidity = new long[numOutputs];
        for (int o = 0; o < numOutputs; o++) {
          dstData[o] = arena.allocate((long) (MAX_CALL_ROWS + SLICE_STRIDE) * 8, SLICE_STRIDE)
              .address();
          dstValidity[o] = arena.allocate(validityBytes(MAX_CALL_ROWS), SLICE_STRIDE).address();
        }
      } catch (Throwable t) {
        arena.close();
        throw t;
      }
    }

    /** Runs the warm-up to its verdict; see the class doc. */
    void run() {
      long started = System.nanoTime();
      long deadline = queuedAt + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
      VarkaKernelWarmupEvent event = new VarkaKernelWarmupEvent();
      event.begin();
      int calls = 0;
      long rowsRun = 0;
      int clean = 0;
      long firstProbeBytes = -1;
      long lastProbeBytes = -1;
      State outcome = State.RELEASED;
      String why = null;
      try {
        while (true) {
          if (warmth.state() != State.WARMING) {
            why = "the shape left the cache";
            break;
          }
          if (calls < SPIN_CALLS) {
            for (int k = 0; k < BLOCK_CALLS; k++) {
              rowsRun += call(calls++);
            }
          } else {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(PACE_MILLIS));
          }
          long before = VarkaAllocationSampler.allocatedBytes();
          int probeRows = 0;
          for (int k = 0; k < PROBE_CALLS; k++) {
            probeRows += call(calls++);
          }
          long allocated = VarkaAllocationSampler.allocatedBytes() - before;
          rowsRun += probeRows;
          lastProbeBytes = allocated;
          long allowance = VarkaAllocationSampler.FIXED_ALLOWANCE_BYTES
              + VarkaAllocationSampler.BYTES_PER_ROW_ALLOWANCE * probeRows
              + (long) PROBE_CALLS * SEGMENT_BYTES_PER_COLUMN * columns;
          if (firstProbeBytes < 0) {
            // The first block runs long before any threshold, so it is the uncompiled rate.
            firstProbeBytes = allocated;
            if (allocated <= allowance) {
              warmth.release();
              why = "it allocates nothing to wait for";
              break;
            }
          } else if (allocated > allowance || allocated * COMPILED_DROP > firstProbeBytes) {
            clean = 0;
          } else if (++clean >= CLEAN_PROBES) {
            if (warmth.markCompiled()) {
              outcome = State.COMPILED;
            } else {
              why = "the shape left the cache";
            }
            break;
          }
          if (System.nanoTime() - deadline > 0) {
            warmth.release();
            why = "no compile after " + DEADLINE_SECONDS + " seconds";
            break;
          }
        }
      } catch (Throwable t) {
        warmth.release();
        LOG.warn("The Varka kernel " + VarkaShapeCacheImpl.sourceFileFor(shapeHash)
            + " failed during its warm-up; the shape serves batches without one.", t);
        if (!(t instanceof Exception) && !(t instanceof LinkageError)) {
          throw t;
        }
        why = "the kernel failed";
      }
      long finished = System.nanoTime();
      record(new Outcome(shapeHash, outcome, calls, rowsRun, started - queuedAt,
          finished - started, firstProbeBytes, lastProbeBytes));
      event.end();
      if (event.shouldCommit()) {
        event.shapeHash = shapeHash;
        event.outcome = outcome.name();
        event.calls = calls;
        event.rows = rowsRun;
        event.commit();
      }
      String kernelName = VarkaShapeCacheImpl.sourceFileFor(shapeHash);
      long millis = TimeUnit.NANOSECONDS.toMillis(finished - started);
      if (outcome == State.COMPILED) {
        LOG.info("Varka kernel " + kernelName + " is compiled after a warm-up of " + millis
            + " ms and " + calls + " calls; its batches run the kernel from now on.");
      } else {
        LOG.info("Varka kernel " + kernelName + " stopped its warm-up after " + millis + " ms and "
            + calls + " calls (" + why + "); its batches run the kernel from now on.");
      }
    }

    private int call(int n) {
      int v = n % NUM_CALLS;
      if (longLane) {
        kernel.run(srcData[v], srcValidity[v], srcNullCount[v], dstData, dstValidity, scalarArgs,
            longArgs, rows);
      } else {
        kernel.run(srcData[v], srcValidity[v], srcNullCount[v], dstData, dstValidity, scalarArgs,
            rows);
      }
      return rows;
    }

    void close() {
      arena.close();
    }
  }

  private static boolean partlyNull(int nullCount, int length) {
    return nullCount > 0 && nullCount < length;
  }

  /**
   * Gives every null row of a copied input the value of its first valid row, so that a call which
   * reads the input as null-free computes on values of the input's own domain rather than on
   * whatever the batch held under its nulls.
   */
  static void fillNullRows(MemorySegment data, int width, MemorySegment validity) {
    int first = -1;
    for (int r = 0; r < SNAPSHOT_ROWS && first < 0; r++) {
      if (valid(validity, r)) {
        first = r;
      }
    }
    if (first < 0) {
      return;
    }
    for (int r = 0; r < SNAPSHOT_ROWS; r++) {
      if (!valid(validity, r)) {
        MemorySegment.copy(data, (long) first * width, data, (long) r * width, width);
      }
    }
  }

  private static boolean valid(MemorySegment validity, int r) {
    return ((validity.get(ValueLayout.JAVA_BYTE, r >>> 3) >>> (r & 7)) & 1) != 0;
  }

  /** Whole 64-bit words covering {@code rows} bits, plus one spare word. */
  private static long validityBytes(int rows) {
    return ((rows + 63L) / 64 + 1) * 8;
  }

  /** Fills {@code dst}'s first {@link #SNAPSHOT_ROWS} rows with the batch's, repeated. */
  static void tileData(long address, int width, int length, MemorySegment dst) {
    if (address == 0L) {
      return;
    }
    MemorySegment src = MemorySegment.ofAddress(address).reinterpret((long) length * width);
    long done = 0;
    while (done < SNAPSHOT_ROWS) {
      long chunk = Math.min(length, SNAPSHOT_ROWS - done);
      MemorySegment.copy(src, 0, dst, done * width, chunk * width);
      done += chunk;
    }
  }

  /**
   * Sets bit {@code r} of {@code dst} to the batch's bit {@code r % length}, for every snapshot
   * row; {@code dst} arrives zeroed. Bit by bit, because a batch whose length is not a multiple of
   * eight repeats at a bit offset; it runs once per shape over a thousand rows.
   */
  static void tileValidity(long address, int length, MemorySegment dst) {
    MemorySegment src = MemorySegment.ofAddress(address).reinterpret((length + 7) / 8);
    for (int r = 0; r < SNAPSHOT_ROWS; r++) {
      int s = r % length;
      if (((src.get(ValueLayout.JAVA_BYTE, s >>> 3) >>> (s & 7)) & 1) != 0) {
        long b = r >>> 3;
        byte bits = dst.get(ValueLayout.JAVA_BYTE, b);
        dst.set(ValueLayout.JAVA_BYTE, b, (byte) (bits | (1 << (r & 7))));
      }
    }
  }
}
