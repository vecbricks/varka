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
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import javax.management.ObjectName;

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
 * boxing, and the drop keeps a narrow kernel's boxing from reading as segments.
 *
 * <p>The probe's calls are long - the whole snapshot, {@link #PROBE_ROWS} rows - where the spin's
 * are short, because boxing grows with the rows a call runs and segments with the calls. A
 * kernel's methods compile one at a time, and on a busy machine a light group's loop method can
 * land a compile or two after the heavy one; over short calls that loop's interpreted boxing, a
 * few hundred bytes a call, fits under the per-column allowance, and the verdict would call the
 * kernel compiled while one of its loops still boxes about twenty bytes a row on a real batch.
 * Over a long call the same loop is thousands of bytes over the per-row allowance, so the verdict
 * waits for every loop method the probe reaches ({@code VARKA-221.md} 2). A count cannot
 * say any of this: crossing a threshold only queues a compile, which lands whenever a compiler
 * thread reaches it. A kernel whose first block is already clean has nothing to wait for - its
 * driver returns before any loop runs, as it does over an all-null input - and is released at
 * once.
 *
 * <p><b>What it runs on.</b> A copy of a real batch of the shape, taken on the task thread before
 * that batch is released: its kernel inputs, tiled to {@link #SNAPSHOT_ROWS} rows, so that C2
 * compiles from real values and real guard outcomes rather than a synthetic batch's. The kernel
 * has two drivers, one for batches whose inputs are all null-free and one for batches with nulls
 * (`VARKA-10.md` 2.5), and the batch that claims the warm-up says nothing about which of the
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
 * compile queue works. A shape without a verdict {@link #deadlineSeconds()} seconds after its
 * warm-up was queued is released, waiting included, and its tasks then run the kernel, which the
 * warm-up's calls have already profiled.
 *
 * <p><b>What it cannot reach.</b> A kernel emitted for a lane count other than the JVM's preferred
 * one ({@code VarkaEmitOptions.lanesOverride}) is warmed like any other and never earns a verdict:
 * the template warm-up compiles the shared Vector API templates for the preferred species only, so
 * every call of such a kernel boxes, and the warm-up ends at its deadline with the shape released.
 * Production leaves the override at zero; it is set by the suites and the option matrix.
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

  /**
   * The width of an input that stores one bit a row, a {@code BitVector}'s own type width: its
   * data is a bitmap in the layout of a validity bitmap, so the copy tiles it bit by bit and a
   * slice starts at {@code start / 8} bytes, as a validity bitmap's does.
   */
  public static final int BIT_WIDTH = 0;

  /** Slices start on 64-row boundaries, so a slice's validity address is word-aligned. */
  private static final int SLICE_STRIDE = 64;

  private static final int MAX_CALL_ROWS = 2 * SLICE_ROWS - 1;

  private static final int NUM_SLICES = (SNAPSHOT_ROWS - MAX_CALL_ROWS) / SLICE_STRIDE + 1;

  /** The argument sets: each slice once for the dense driver and once for the masked one. */
  private static final int NUM_CALLS = 2 * NUM_SLICES;

  /** Calls between probes while the warm-up spins. */
  private static final int BLOCK_CALLS = 64;

  /**
   * Calls one allocation probe measures: two per driver, each over the whole snapshot. Long
   * calls, so that a loop method still interpreted boxes enough to be seen (see the class doc).
   */
  private static final int PROBE_CALLS = 4;

  /** Rows of each probe call: the whole snapshot. */
  static final int PROBE_ROWS = SNAPSHOT_ROWS;

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
   * Whether a probe block of {@code allocated} bytes shows the kernel compiled, given the
   * {@code allowance} for a block that size and the {@code first} block's bytes: within the
   * allowance and at most a quarter ({@link #COMPILED_DROP}) of the first. A magnitude below the
   * first is not enough on its own - a loop method still interpreted allocates tens of kilobytes
   * a call, the size of a compiled kernel's residue in a busy JVM (VARKA-221, VARKA-295).
   */
  static boolean blockClean(long allocated, long allowance, long first) {
    return allocated <= allowance
        && allocated <= Long.MAX_VALUE / COMPILED_DROP
        && allocated * COMPILED_DROP <= first;
  }

  /**
   * A system property that replaces {@link #DEADLINE_SECONDS}: the tests whose subject is the
   * verdict set it high, because on a loaded runner a loop method's C2 compile can wait in the
   * queue longer than a minute (VARKA-295) and a release at the deadline is then the policy
   * working, not a verdict to assert on. Production does not set it.
   */
  static final String DEADLINE_PROPERTY = "varka.warmup.deadlineSeconds";

  /** The deadline in force: {@link #DEADLINE_PROPERTY} if it is a positive integer. */
  static int deadlineSeconds() {
    try {
      int configured = Integer.parseInt(System.getProperty(DEADLINE_PROPERTY, ""));
      return configured > 0 ? configured : DEADLINE_SECONDS;
    } catch (NumberFormatException e) {
      return DEADLINE_SECONDS;
    }
  }

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

  // Memory violations that ended a warm-up, for awaitIdle to hand to the caller; the worker thread
  // has no task to fail, and a violation is never a fallback (VarkaMemoryViolation).
  private static final ConcurrentLinkedQueue<VarkaMemoryViolation> VIOLATIONS =
      new ConcurrentLinkedQueue<>();

  private static final ArrayDeque<Outcome> OUTCOMES = new ArrayDeque<>();

  // Replaced when it has died; see ensureWorker.
  private static Thread worker;

  /** Nanoseconds the one-time Vector API template warm-up took, or zero if it has not run. */
  public static long templateWarmupNanos() {
    return VarkaVectorTemplateWarmup.nanos();
  }

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
   * @param srcWidths the bytes per row of each input's data, four or eight, or {@link #BIT_WIDTH}
   *        for a boolean column's bitmap.
   * @param nullable whether any input of the shape is nullable, so that a batch can reach the
   *        kernel's masked driver and the warm-up must compile it too.
   * @param kernel an instance of the shape's class for the warm-up's own use; kernels keep no
   *        state between calls, so it only has to be a different object from the tasks' ones.
   * @param dstWidths each output's width, as {@link VarkaTypes#outputWidth} gives it;
   *        {@link #destinationBytes} turns it into the bytes a row the warm-up allocates.
   */
  public static boolean start(VarkaKernelWarmth warmth, String shapeHash, VarkaFusedKernel kernel,
      boolean longLane, long[] srcData, long[] srcValidity, int[] srcNullCount, int[] srcWidths,
      boolean nullable, int length, int[] dstWidths, int[] scalarArgs, long[] longArgs) {
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
        srcWidths, nullable, length, dstWidths, scalarArgs, longArgs);
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
   * The bytes a row the warm-up allocates for an output of {@code width}: its width, and at least
   * eight, a lane's value, which a bitmap's marker of -1 also takes.
   */
  static long destinationBytes(int width) {
    return Math.max(width, Long.BYTES);
  }

  /**
   * Waits until no warm-up is queued or running, or the timeout passes, and says which. For
   * benchmarks and tests that need the JVM quiet, or a shape's verdict, before they go on.
   *
   * <p>Throws a {@link VarkaMemoryViolation} a warm-up ended with since the last call (the
   * sanitizer found the warm-up's kernel mapping memory outside its job's buffers): a violation is
   * not a kernel failure and never a fallback, and the warm-up thread has no task to fail, so the
   * caller that waits for the warm-ups is the one that fails. Every pending violation is taken at
   * once, the first thrown and the rest attached to it as suppressed exceptions, so none reaches a
   * later, unrelated call. A JVM that never waits keeps them all, as a violation is a defect the
   * suites abort on and a production run has the sanitizer off.
   */
  public static boolean awaitIdle(long timeoutMillis) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (PENDING.get() > 0) {
      if (System.nanoTime() - deadline > 0) {
        throwViolation();
        return false;
      }
      Thread.sleep(5);
    }
    throwViolation();
    return true;
  }

  private static void throwViolation() {
    VarkaMemoryViolation first = VIOLATIONS.poll();
    if (first == null) {
      return;
    }
    for (VarkaMemoryViolation next = VIOLATIONS.poll(); next != null; next = VIOLATIONS.poll()) {
      first.addSuppressed(next);
    }
    throw first;
  }

  /**
   * Methods queued for C2 plus those its threads are compiling, or -1 where the compile queue
   * cannot be read: the load the template warm-up paces itself against, for benchmarks and tests
   * that wait for the compilation their timing depends on to finish before they measure. A load
   * that cannot be read counts as not idle, so a JVM that does not serve the diagnostic command
   * shows as a wait that ran out and not as a quiet compiler.
   */
  public static int c2Load() {
    String text = VarkaVectorTemplateWarmup.compilerQueueText();
    return text == null ? -1
        : VarkaVectorTemplateWarmup.c2QueueLength(text)
            + VarkaVectorTemplateWarmup.c2Compiling(text);
  }

  // What the JIT was doing when the last warm-up was released at its deadline; see jitState.
  private static volatile String lastReleaseJitState = "";

  /**
   * The JIT's state when the most recent warm-up ran out of its {@link #deadlineSeconds()}, or
   * empty if none has: the compile queue, the compilers' total time and the code cache's use.
   * A release at the deadline means C2 never finished the kernel, and this is what says whether
   * the queue was backed up behind other methods, the compilers were off, or the cache was full
   * (VARKA-295).
   */
  public static String lastReleaseJitState() {
    return lastReleaseJitState;
  }

  static String jitState() {
    return jitState(null);
  }

  /**
   * {@link #jitState()}, and for {@code kernelClass} which of its methods have compiled code and at
   * which tier ({@code Compiler.codelist}) and which have none. That is what tells a loop method
   * the compiler never took from a compiled kernel that still allocates (VARKA-295).
   */
  static String jitState(Class<?> kernelClass) {
    var state = new StringBuilder("JIT: ");
    var compilation = ManagementFactory.getCompilationMXBean();
    if (compilation != null && compilation.isCompilationTimeMonitoringSupported()) {
      state.append("compile time ").append(compilation.getTotalCompilationTime()).append(" ms; ");
    }
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      // With the code cache unsegmented (under 240 MB) there is one pool, "CodeCache".
      if (pool.getName().startsWith("CodeHeap") || pool.getName().equals("CodeCache")) {
        state.append(pool.getName()).append(' ').append(pool.getUsage().getUsed() >> 20)
            .append('/').append(pool.getUsage().getMax() >> 20).append(" MB; ");
      }
    }
    String queue = VarkaVectorTemplateWarmup.compilerQueueText();
    if (queue == null) {
      state.append("compile queue unavailable; ");
    } else {
      // Limit 0, the one-argument split's own: trailing empty lines are dropped.
      String[] lines = queue.split("\\R", 0);
      state.append("compile queue of ").append(lines.length).append(" lines: ");
      for (int i = 0; i < Math.min(lines.length, 12); i++) {
        state.append(lines[i].strip()).append(" | ");
      }
    }
    if (kernelClass != null) {
      appendKernelCode(state, kernelClass);
    }
    return state.toString();
  }

  /** Kernel code entries the state lists at most; a wide kernel has dozens of methods. */
  private static final int MAX_CODE_ENTRIES = 40;

  /**
   * Appends the kernel class's compiled code, one {@code id tier state name} per entry from
   * {@code Compiler.codelist} (on JDK 25 state 0 is in use and 1 not entrant), then the declared
   * methods with no code in use, matched by name and descriptor so that two overloads of
   * {@code run} are told apart. Three limits: the list names classes, not loaders, so a shape
   * emitted again under a new loader while the old one is alive shows both classes' code; it does
   * not mark an OSR compile, which a call still enters interpreted; and it prints the whole code
   * cache, which this pays once per shape that has already waited out its deadline.
   */
  private static void appendKernelCode(StringBuilder state, Class<?> kernelClass) {
    try {
      Object list = ManagementFactory.getPlatformMBeanServer().invoke(
          new ObjectName("com.sun.management:type=DiagnosticCommand"), "compilerCodelist",
          new Object[] {null}, new String[] {String[].class.getName()});
      String prefix = kernelClass.getName() + ".";
      var inUse = new TreeSet<String>();
      var entries = new StringBuilder();
      int listed = 0;
      for (String line : String.valueOf(list).split("\\R", 0)) {
        // "<id> <tier> <state> <class>.<method><descriptor> [<addresses>]"
        String[] fields = line.strip().split(" ", 5);
        if (fields.length < 4 || !fields[3].startsWith(prefix)) {
          continue;
        }
        String method = fields[3].substring(prefix.length());
        if ("0".equals(fields[2])) {
          inUse.add(method);
        }
        if (listed++ < MAX_CODE_ENTRIES) {
          entries.append(fields[0]).append(" L").append(fields[1]).append(" s")
              .append(fields[2]).append(' ').append(method, 0, method.indexOf('('))
              .append(", ");
        }
      }
      var noCode = new TreeSet<String>();
      for (var m : kernelClass.getDeclaredMethods()) {
        String descriptor = MethodType.methodType(m.getReturnType(), m.getParameterTypes())
            .toMethodDescriptorString();
        if (!inUse.contains(m.getName() + descriptor)) {
          // Named with its argument count: the descriptors of a kernel's methods are long.
          noCode.add(m.getName() + "(" + m.getParameterCount() + ")");
        }
      }
      state.append("kernel code (").append(listed).append("): ").append(entries)
          .append("no code in use: ").append(noCode);
    } catch (Exception | LinkageError e) {
      state.append("kernel code unavailable: ").append(e);
    }
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

  /**
   * Runs the Vector API template warm-up if nothing has yet, for the worker's first start, and
   * never throws: this runs outside the job loop's guard, and a worker that dies here is
   * restarted by the next claim to die the same way. That includes a failure to initialize the
   * warm-up class itself. In a JVM where a kernel or a query-path helper ran first, the warm-up
   * has already run on that thread ({@link VarkaVectorTemplateWarmup}, "When it runs").
   */
  static void templateWarmup() {
    try {
      VarkaVectorTemplateWarmup.beforeVectorCode();
    } catch (Throwable t) {
      LOG.warn("The Vector API template warm-up could not run; kernels warm without it.", t);
    }
  }

  private static void work() {
    // Once per JVM, normally already done by the first kernel build or instance (VARKA-389);
    // here for a JVM whose first vector code is a warm-up job (VARKA-295).
    templateWarmup();
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
        String kernelName = VarkaShapeCacheImpl.sourceFileFor(job.shapeHash);
        if (t instanceof VarkaMemoryViolation violation) {
          VIOLATIONS.add(violation);
          LOG.warn("Varka kernel warm-up of " + kernelName
              + " ended on a memory violation, which the next awaitIdle throws.", t);
        } else {
          LOG.warn("Varka kernel warm-up of " + kernelName
              + " failed; the shape serves batches without one.", t);
        }
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
    /**
     * The memory the calls map, kept only under the memory sanitizer, whose window {@link #run}
     * registers it in; empty, and never touched again, when the sanitizer is off.
     */
    private final List<MemorySegment> owned = new ArrayList<>();
    /** The scratch a kernel with a materialized prefix takes (VARKA-198), or 0 for one without. */
    private final long scratch;
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
    // The probe's long calls: the whole snapshot from its first row, once per driver, into
    // destinations of the snapshot's length.
    private final long[][] probeData;
    private final long[][] probeValidity;
    private final int[][] probeNullCount;
    private final long[] probeDstData;
    private final long[] probeDstValidity;
    private final int[] scalarArgs;
    private final long[] longArgs;
    private final int columns;

    Job(VarkaKernelWarmth warmth, String shapeHash, VarkaFusedKernel kernel, boolean longLane,
        long[] batchData, long[] batchValidity, int[] batchNullCount, int[] widths,
        boolean nullable, int length, int[] dstWidths, int[] scalarArgs, long[] longArgs) {
      int numOutputs = dstWidths.length;
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
          boolean bits = widths[i] == BIT_WIDTH;
          MemorySegment data = bits
              ? alloc(validityBytes(SNAPSHOT_ROWS + SLICE_STRIDE), wordBytes(SNAPSHOT_ROWS),
                  SLICE_STRIDE)
              : allocRows(SNAPSHOT_ROWS, widths[i]);
          MemorySegment validity = allocValidity(SNAPSHOT_ROWS);
          if (batchNullCount[i] == 0) {
            tileValues(batchData[i], widths[i], length, data);
            validity.fill((byte) -1);
          } else if (partlyNull(batchNullCount[i], length)) {
            tileValues(batchData[i], widths[i], length, data);
            tileValidity(batchValidity[i], length, validity);
            // A bit under a null row is a boolean like any other, so a bitmap keeps what it has.
            if (!bits) {
              fillNullRows(data, widths[i], validity);
            }
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
            srcData[v][i] = dataBase[i] + (widths[i] == BIT_WIDTH ? start / 8L
                : (long) start * widths[i]);
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
          dstData[o] = allocRows(MAX_CALL_ROWS, destinationBytes(dstWidths[o])).address();
          dstValidity[o] = allocValidity(MAX_CALL_ROWS).address();
        }
        // The probe's argument sets: the dense driver's with every count at zero, the masked
        // driver's with each input's validity when the shape is nullable. A masked count is one
        // whatever the input holds - an all-null input's zeroed bits included - because the
        // count's only other effect is the driver's all-null shortcut, which returns before any
        // loop runs, and a probe that runs no masked loop cannot see one still interpreted.
        this.probeData = new long[2][numInputs];
        this.probeValidity = new long[2][numInputs];
        this.probeNullCount = new int[2][numInputs];
        for (int d = 0; d < 2; d++) {
          for (int i = 0; i < numInputs; i++) {
            probeData[d][i] = dataBase[i];
            if (nullable && d == 1) {
              probeValidity[d][i] = validityBase[i];
              probeNullCount[d][i] = 1;
            }
          }
        }
        this.probeDstData = new long[numOutputs];
        this.probeDstValidity = new long[numOutputs];
        for (int o = 0; o < numOutputs; o++) {
          probeDstData[o] = allocRows(PROBE_ROWS, destinationBytes(dstWidths[o])).address();
          probeDstValidity[o] = allocValidity(PROBE_ROWS).address();
        }
        // Sized for the longest call, the probe's; a warm-up call passes the same region.
        int perRow = kernel.scratchBytesPerRow();
        this.scratch = perRow == 0 ? 0L
            : allocRows(PROBE_ROWS, perRow).address();
      } catch (Throwable t) {
        arena.close();
        throw t;
      }
    }

    /**
     * A validity bitmap of {@code rows} bits: the whole words a batch's bitmap registers, in an
     * allocation with one spare word that is not registered.
     */
    private MemorySegment allocValidity(int rows) {
      return alloc(validityBytes(rows), wordBytes(rows), SLICE_STRIDE);
    }

    /** {@code rows} rows of {@code width} bytes, registered without the padding rows. */
    private MemorySegment allocRows(int rows, long width) {
      return alloc((rows + SLICE_STRIDE) * width, rows * width, SLICE_STRIDE);
    }

    /**
     * Allocates {@code bytes} from the job's arena. When the sanitizer is on, the segment's first
     * {@code extent} bytes are what the job registers: the rows a call can reach, without the
     * {@link #SLICE_STRIDE} rows of padding that keep the allocation aligned, so that an over-read
     * of a few rows is a violation as it is on a batch's exact buffers.
     */
    private MemorySegment alloc(long bytes, long extent, long alignment) {
      MemorySegment segment = arena.allocate(bytes, alignment);
      if (VarkaMemorySanitizer.ENABLED) {
        owned.add(segment.asSlice(0, extent));
      }
      return segment;
    }

    /**
     * Runs the warm-up to its verdict; see the class doc. Under the memory sanitizer the calls
     * run in its window with the job's memory registered, as a batch's calls do: the check at
     * every mapping reads the window, and a warm-up whose thread had none would hand C2 a profile
     * in which it is always null, code that deoptimizes at the first batch served in a window.
     * The window is opened once for the whole warm-up, so with the sanitizer off the cost is two
     * returns on a {@code static final}, and no per-call work either way.
     */
    void run() {
      VarkaMemorySanitizer.begin();
      try {
        if (VarkaMemorySanitizer.ENABLED) {
          for (int i = 0; i < owned.size(); i++) {
            MemorySegment segment = owned.get(i);
            VarkaMemorySanitizer.register("warm-up memory", i, segment.address(),
                segment.byteSize());
          }
        }
        spin();
      } finally {
        VarkaMemorySanitizer.end();
      }
    }

    private void spin() {
      long started = System.nanoTime();
      int deadlineSeconds = deadlineSeconds();
      long deadline = queuedAt + TimeUnit.SECONDS.toNanos(deadlineSeconds);
      VarkaKernelWarmupEvent event = new VarkaKernelWarmupEvent();
      event.begin();
      int calls = 0;
      long rowsRun = 0;
      int clean = 0;
      long firstProbeBytes = -1;
      long lastProbeBytes = -1;
      State outcome = State.RELEASED;
      String why = null;
      VarkaMemoryViolation violation = null;
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
            probeRows += probe(k);
            calls++;
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
          } else if (!blockClean(allocated, allowance, firstProbeBytes)) {
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
            lastReleaseJitState = jitState(kernel.getClass());
            why = "no compile after " + deadlineSeconds + " seconds; " + lastReleaseJitState;
            break;
          }
        }
      } catch (VarkaMemoryViolation v) {
        // Not a failure of the kernel to fall back from: it goes to the worker loop, which logs it
        // and keeps it for awaitIdle, once the outcome below is recorded.
        warmth.release();
        violation = v;
        why = "a memory violation";
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
      if (violation != null) {
        throw violation;
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

    /** One long probe call; even {@code k} runs the dense driver's arguments, odd the masked. */
    private int probe(int k) {
      int d = k % 2;
      if (longLane) {
        kernel.run(probeData[d], probeValidity[d], probeNullCount[d], probeDstData,
            probeDstValidity, scalarArgs, longArgs, PROBE_ROWS, scratch);
      } else {
        kernel.run(probeData[d], probeValidity[d], probeNullCount[d], probeDstData,
            probeDstValidity, scalarArgs, PROBE_ROWS, scratch);
      }
      return PROBE_ROWS;
    }

    private int call(int n) {
      int v = n % NUM_CALLS;
      if (longLane) {
        kernel.run(srcData[v], srcValidity[v], srcNullCount[v], dstData, dstValidity, scalarArgs,
            longArgs, rows, scratch);
      } else {
        kernel.run(srcData[v], srcValidity[v], srcNullCount[v], dstData, dstValidity, scalarArgs,
            rows, scratch);
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

  /** Whole 64-bit words covering {@code rows} bits: what a batch's bitmap registers. */
  private static long wordBytes(int rows) {
    return (rows + 63L) / 64 * 8;
  }

  /** {@link #wordBytes} plus one spare word, which a job allocates but does not register. */
  private static long validityBytes(int rows) {
    return wordBytes(rows) + 8;
  }

  /**
   * {@link #tileData} for a value input, {@link #tileValidity} for a bitmap: a boolean column's
   * data holds {@code (length + 7) / 8} bytes, and a batch of other than a multiple of eight rows
   * repeats at a bit offset.
   */
  private static void tileValues(long address, int width, int length, MemorySegment dst) {
    if (width != BIT_WIDTH) {
      tileData(address, width, length, dst);
    } else if (address != 0L) {
      tileValidity(address, length, dst);
    }
  }

  /** Fills {@code dst}'s first {@link #SNAPSHOT_ROWS} rows with the batch's, repeated. */
  static void tileData(long address, int width, int length, MemorySegment dst) {
    if (address == 0L) {
      return;
    }
    MemorySegment src = VarkaSegments.map(address, (long) length * width);
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
    MemorySegment src = VarkaSegments.map(address, (length + 7) / 8);
    for (int r = 0; r < SNAPSHOT_ROWS; r++) {
      int s = r % length;
      if (((src.get(ValueLayout.JAVA_BYTE, s >>> 3) >>> (s & 7)) & 1) != 0) {
        long b = (long) r >>> 3;
        byte bits = dst.get(ValueLayout.JAVA_BYTE, b);
        dst.set(ValueLayout.JAVA_BYTE, b, (byte) (bits | (1 << (r & 7))));
      }
    }
  }
}
