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
import java.lang.management.ManagementFactory;
import com.sun.management.HotSpotDiagnosticMXBean;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.management.ObjectName;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorOperators.Binary;
import jdk.incubator.vector.VectorOperators.Comparison;
import jdk.incubator.vector.VectorOperators.Unary;
import jdk.incubator.vector.VectorSpecies;

import org.apache.spark.internal.SparkLogger;
import org.apache.spark.internal.SparkLoggerFactory;

/**
 * Runs every Vector API method the emitted kernels call once, with every operator class they pass,
 * before the first kernel is warmed, so that the JDK's shared Vector API templates are profiled as
 * the many-operator methods they are rather than as the first kernel's operators (VARKA-295).
 *
 * <p><b>The problem it removes.</b> {@code IntVector.lanewise}, {@code VectorOperators.opKind}
 * and the other templates every kernel's vector operation goes through are compiled by C2 on their
 * own as soon as a first kernel has called them often enough, and that compile reads the profile
 * the calls so far left: one operator class at a call site, say, so it compiles a check of that
 * class with an uncommon trap on a miss. The next kernel that passes another operator class meets
 * the trap, the template is deoptimized, and the trap is recorded in the template's
 * {@code MethodData}, where it stays. A later C2 compile of a kernel's loop inlines those
 * templates hundreds of times, adds the recorded counts once per inlined copy, and past 100 traps
 * (or 201 decompiles) in the sum C2 stops putting uncommon traps on class-check misses and keeps
 * a real virtual call instead. A vector that flows through that call cannot be scalar-replaced,
 * and the compiled loop then allocates one {@code int[16]} per iteration for the rest of the JVM's
 * life, which the warm-up reads as a kernel that never compiled ({@code VARKA-295.md} 9.5, 9.6).
 *
 * <p><b>What this does.</b> It calls each template {@link #ROUNDS} times, interleaving every
 * operator class, species and argument form the emitter uses. The call sites inside the templates
 * then record several receiver classes, which HotSpot treats as megamorphic, and C2 does not
 * speculate on them. The calls are not only profiled: at this count the hottest templates
 * ({@code VectorOperators.opKind}, {@code ImplCache.find}, the {@code lanewise} template) pass
 * the tier-4 threshold during the warm-up and are compiled by C2 from the mixed profile
 * ({@code VARKA-295.md} 9.7). Which of the two - the profile or the early compile - matters more
 * was not separated; 100 rounds also worked in the reproducer. It runs once per JVM, on the
 * warm-up thread before its first job, so the cost is a fixed number of calls on a thread that
 * is not a query's.
 *
 * <p><b>What it does not do.</b> It computes nothing the engine uses and it cannot undo a trap a
 * template has already recorded, so vector code that ran earlier in the JVM (the query-path
 * helpers {@code IntRangeOps} and {@code SelectionVectorOps}) is not covered. It feeds the
 * templates receivers of the preferred species only. A call that throws (an unsupported shape on
 * some CPU) ends the warm-up there: the int, long and double groups run in that order, the
 * conversions last, and the rest stay cold.
 *
 * <p><b>Paced on C2's queue, and heavy enough on the wrappers.</b> HotSpot's tiered policy
 * compiles a method due for its profiling tier at tier 2 instead, C1 code with counters and no
 * profiling, whenever C2's queue is longer than {@code Tier3DelayOn} (5) times C2's compiler
 * threads at the instant the method crosses its first threshold: ten entries on a four-core
 * host. A one-line wrapper such as {@code IntVector.max(Vector)} that takes that path never
 * returns to profiling, because C1 at tier 2 marks a call as a profiling point only when its
 * target is final and the wrapper's one call is to the abstract {@code lanewise}, so its tier-2
 * compile ends with "nothing to profile" and the policy sends it straight to tier 4 with a
 * profile frozen under C2's maturity line of 1,000 calls; every kernel that inlines it
 * afterwards reads no receiver type at that call, keeps a real virtual call, and boxes one
 * vector per iteration ({@code VARKA-389.md} 9.5, {@code VARKA-391.md}). Two things decide
 * where that first crossing falls and what the queue is then. The wrappers are called
 * {@link #THIN_CALLS_PER_ROUND} times a round, so that they cross inside the rounds even when
 * C1's queue has scaled the threshold several-fold, rather than later in the kernel warm-up's
 * traffic; and before the rounds, after every round and once at the end, the warm-up reads C2's
 * queue through the diagnostic command the JVM exposes and waits until it is within
 * {@link #PACE_QUEUE_TARGET}, the shorter queue at which the policy itself resumes profiling,
 * {@link #PACE_BUDGET_MILLIS} in all at most; where the command or the options are unavailable
 * it does not wait. Either alone was not enough: paced to the policy's limit the four-core
 * suite passed by luck, nine tests of ten; paced to the resume line with one call a round it
 * released two of five three times, the wrappers crossing 200 to 300 ms after the rounds; with
 * both, the wrappers compiled at tier 3 inside the rounds with C2's queue at 0 to 2, and the
 * suite passed every test in every run. The one log line the warm-up writes says what the
 * pacing saw, so a JVM where it could not hold the queue can be told from one where it did.
 *
 * <p><b>When it runs.</b> Before any vector code of the engine runs in the JVM, not before the
 * first warmed shape: {@link #beforeVectorCode} is called by the shape cache's first build, by
 * the first kernel instance of a task, by the query-path helpers' class initialisers and by the
 * warm-up worker before its first job, and the first of those to arrive runs it on its own
 * thread, once, 200 to 600 ms on a fresh JVM. The order matters because of what the warm-up does
 * to a template that is already compiled: its rounds take branches the template's standalone
 * compile never saw, the compile is deoptimised with an {@code unstable_if} trap, and the
 * template's {@code MethodData} keeps a decompile count from then on. C2 sums that count once per
 * inlined copy when it compiles a kernel, and a wide kernel's group method inlines a template
 * hundreds of times, so one decompile is enough to pass the cutoff of 201 and leave the method's
 * class checks as virtual calls, which box - the release of every wide shape on every machine
 * that the milestone 7 close found ({@code VARKA-389.md} 8.4a). Run first, the warm-up has
 * nothing to deoptimise, and the templates' later standalone compiles are made from its mixed
 * profile, which is what VARKA-295 measured.
 */
final class VarkaVectorTemplateWarmup {

  private static final SparkLogger LOG =
      SparkLoggerFactory.getLogger(VarkaVectorTemplateWarmup.class);

  /**
   * A system property that turns the warm-up off when it is {@code false}, for the measurements
   * that compare the JVM with and without it. Production does not set it.
   */
  static final String PROPERTY = "varka.warmup.vectorTemplates";

  /**
   * Rounds of calls. A template reaches C1's profiling tier after 200 invocations, and C2 asks for
   * it after 5000 at the default scale; the rounds only have to cover the profiling tier with
   * every operator class mixed in, which C2 then reads whenever it compiles.
   */
  static final int ROUNDS = 300;

  /**
   * How many times a round calls each one-line wrapper the kernels call ({@code add},
   * {@code min}, {@code max}, {@code blend} and the rest). One call a round left them at 300
   * calls when the rounds ended, under the policy's first threshold once C1's queue had scaled
   * it (200 calls times a load factor the trace showed as high as 6.6), so their first compile
   * fell in the kernel warm-up's traffic, where C2's queue decided the tier
   * ({@code VARKA-389.md} 9.5). Eight calls a round put the crossing inside the paced rounds
   * and take every wrapper past the policy's "old" mark of 2,000 calls before the rounds end,
   * after which its later decisions ignore the queue and its queued task cannot go stale.
   */
  static final int THIN_CALLS_PER_ROUND = 8;

  private static final int BYTES = 4096;

  /**
   * Rounds between two looks at C2's queue: every round, since one round's burst can put the
   * queue over the limit on a small host. See the class doc, "Paced on C2's queue".
   */
  static final int PACE_ROUNDS = 1;

  // What the pacing saw in this JVM, for the one log line: C2's queue before the rounds, the
  // longest queue met during them, and the milliseconds waited in all.
  private static volatile int queueBefore = -1;
  private static volatile int queuePeak = -1;
  private static volatile long waitedMillis;

  /** The most the warm-up waits for C2's queue in all, so a JVM with a slow C2 is not held. */
  static final long PACE_BUDGET_MILLIS = 20_000;

  /**
   * The queue length C2 may have for the policy to still choose the profiling tier:
   * {@code Tier3DelayOn} times C2's compiler threads, read from the JVM's options once;
   * HotSpot's defaults, 5 and two thirds of {@code CICompilerCount}, where they cannot be read.
   * The log line reports the queue against it.
   */
  static final int PROFILING_TIER_QUEUE_LIMIT =
      Math.max(1, vmOption("Tier3DelayOn", 5) * c2Threads());

  /**
   * What the pacing waits for: the shorter queue at which the policy sends a method it compiled
   * without profiling back to the profiling tier, {@code Tier3DelayOff} (2) times C2's threads.
   * Below {@link #PROFILING_TIER_QUEUE_LIMIT} for the reason HotSpot's own is: a round's burst
   * lifts the queue, and a round started at the limit crosses it, by one in the runs of
   * {@code VARKA-389.md} 9.5, the failing one as the passing ones.
   */
  static final int PACE_QUEUE_TARGET = Math.max(1, vmOption("Tier3DelayOff", 2) * c2Threads());

  private static int vmOption(String name, int fallback) {
    try {
      var bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
      return Integer.parseInt(bean.getVMOption(name).getValue());
    } catch (Exception | LinkageError e) {
      return fallback;
    }
  }

  /** C2's compiler threads: all but a third of {@code CICompilerCount}, HotSpot's split. */
  private static int c2Threads() {
    int compilers = vmOption("CICompilerCount", 3);
    return Math.max(1, compilers - compilers / 3);
  }

  private static final @Nullable ObjectName DIAGNOSTIC_COMMAND = diagnosticCommand();

  private static @Nullable ObjectName diagnosticCommand() {
    try {
      return new ObjectName("com.sun.management:type=DiagnosticCommand");
    } catch (Exception e) {
      return null;
    }
  }

  private static final List<Unary> UNARY = List.of(VectorOperators.NEG, VectorOperators.ABS,
      VectorOperators.NOT);

  private static final List<Binary> BINARY = List.of(VectorOperators.ADD, VectorOperators.SUB,
      VectorOperators.MUL, VectorOperators.DIV, VectorOperators.MIN, VectorOperators.MAX,
      VectorOperators.AND, VectorOperators.OR, VectorOperators.XOR);

  private static final List<Binary> SHIFTS = List.of(VectorOperators.ASHR,
      VectorOperators.LSHR, VectorOperators.LSHL);

  private static final List<Comparison> COMPARISONS = List.of(VectorOperators.EQ,
      VectorOperators.NE, VectorOperators.LT, VectorOperators.LE, VectorOperators.GT,
      VectorOperators.GE, VectorOperators.ULT, VectorOperators.ULE, VectorOperators.UGT,
      VectorOperators.UGE);

  private static boolean done;
  // Whether ensure() has decided for this JVM - ran, switched off or failed - so that the hooks
  // on the engine's hot paths are one volatile read after the first call.
  private static volatile boolean settled;
  // What the one run took, or zero: for the worker's log line and the tests.
  private static volatile long nanos;

  // Calls to warmUp that finished, and the failure a test asks the next one to throw; see
  // resetForTest.
  private static int completed;
  private static volatile @Nullable Throwable failWith;

  // Read by no one: it keeps the JIT from discarding the calls as dead code.
  @SuppressWarnings("UnusedVariable")
  private static volatile long sink;

  private VarkaVectorTemplateWarmup() {
  }

  /**
   * Runs the warm-up if this JVM has not, and returns the nanoseconds it took, or zero when it ran
   * before, is switched off, or failed. Any failure, an {@code Error} included, is logged and the
   * engine goes on: the warm-up only improves the odds of a clean compile, and the thread that
   * calls this serves every shape in the JVM.
   */
  static synchronized long ensure() {
    if (done || "false".equals(System.getProperty(PROPERTY))) {
      settled = true;
      return 0;
    }
    done = true;
    long start = System.nanoTime();
    try {
      warmUp();
    } catch (Throwable e) {
      LOG.warn("The Vector API template warm-up failed; kernels warm without it.", e);
      settled = true;
      return 0;
    }
    long took = System.nanoTime() - start;
    nanos = took;
    settled = true;
    LOG.info("Vector API template warm-up took " + TimeUnit.NANOSECONDS.toMillis(took)
        + " ms, before the JVM's first vector code; C2's queue was " + queueBefore
        + " before the rounds and at most " + queuePeak + " during them, against the policy's"
        + " limit of " + PROFILING_TIER_QUEUE_LIMIT + " and the pacing's target of "
        + PACE_QUEUE_TARGET + "; waited " + waitedMillis + " ms for it.");
    return took;
  }

  /**
   * The hook the engine's entry points call before their first vector code (see the class doc,
   * "When it runs"): one volatile read once the warm-up has decided, {@link #ensure} the first
   * time. It never throws - {@code ensure} absorbs the warm-up's own failures, and a failure to
   * initialize this class is caught here, so that a kernel or a helper is never lost to its
   * warm-up.
   */
  static void beforeVectorCode() {
    if (settled) {
      return;
    }
    try {
      ensure();
    } catch (Throwable t) {
      settled = true;
      LOG.warn("The Vector API template warm-up could not run; kernels warm without it.", t);
    }
  }

  /** Nanoseconds the one run of this JVM took, or zero if it has not run, is off or failed. */
  static long nanos() {
    return nanos;
  }

  /** How many times {@link #warmUp} ran to the end in this JVM; for tests. */
  static synchronized int completedRuns() {
    return completed;
  }

  /**
   * Test hook: forgets that the warm-up ran, so that {@link #ensure} runs it again, and makes the
   * next {@link #warmUp} throw {@code failure} if it is not null.
   */
  // A test hands in the one failure the next warm-up is to throw, so it is stored, not created on
  // demand; production never sets it.
  @SuppressWarnings("StaticAssignmentOfThrowable")
  static synchronized void resetForTest(@Nullable Throwable failure) {
    done = false;
    settled = false;
    nanos = 0;
    completed = 0;
    failWith = failure;
  }

  /**
   * Waits until C2's compile queue is within {@link #PACE_QUEUE_TARGET}, polling the
   * JVM's {@code Compiler.queue} diagnostic command, for at most {@code maxMillis}; returns the
   * milliseconds waited. Where the command is unavailable or fails, returns at once. See the
   * class doc, "Paced on C2's queue".
   */
  static long awaitC2Queue(long maxMillis) {
    if (DIAGNOSTIC_COMMAND == null || maxMillis <= 0) {
      return 0;
    }
    long start = System.nanoTime();
    try {
      while (true) {
        Object reply = ManagementFactory.getPlatformMBeanServer().invoke(DIAGNOSTIC_COMMAND,
            "compilerQueue", new Object[] {null}, new String[] {String[].class.getName()});
        int length = c2QueueLength(String.valueOf(reply));
        if (length > queuePeak) {
          queuePeak = length;
        }
        if (length <= PACE_QUEUE_TARGET) {
          break;
        }
        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        if (waited >= maxMillis) {
          break;
        }
        Thread.sleep(5);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception | LinkageError e) {
      // No diagnostic command in this JVM: the rounds run unpaced, as they did before.
      return 0;
    }
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  /** C2's queue length now, or -1 where the diagnostic command is unavailable. */
  static int c2QueueLengthNow() {
    if (DIAGNOSTIC_COMMAND == null) {
      return -1;
    }
    try {
      Object reply = ManagementFactory.getPlatformMBeanServer().invoke(DIAGNOSTIC_COMMAND,
          "compilerQueue", new Object[] {null}, new String[] {String[].class.getName()});
      return c2QueueLength(String.valueOf(reply));
    } catch (Exception | LinkageError e) {
      return -1;
    }
  }

  /**
   * How many methods the {@code Compiler.queue} text lists in C2's queue: the non-blank lines
   * after "C2 compile queue:", none when it says "Empty". Unknown text reads as zero, so a format
   * this does not know never holds the warm-up.
   */
  static int c2QueueLength(String queueText) {
    boolean inC2 = false;
    int n = 0;
    for (String line : queueText.lines().toList()) {
      String t = line.strip();
      if (t.startsWith("C2 compile queue")) {
        inC2 = true;
      } else if (inC2 && t.endsWith("compile queue:")) {
        break;
      } else if (inC2 && !t.isEmpty() && !t.equals("Empty")) {
        n++;
      }
    }
    return n;
  }

  /**
   * The calls themselves, {@link #ROUNDS} rounds of them, and a checksum of the results, which
   * is the same on every call and every machine for the lane counts of one species set (a test
   * pins that the work is deterministic and computed, not discarded).
   */
  static long warmUp() {
    Throwable injected = failWith;
    if (injected != null) {
      throw VarkaShapeCacheImpl.sneakyThrow(injected);
    }
    VectorSpecies<Integer> is = IntVector.SPECIES_PREFERRED;
    VectorSpecies<Long> ls = LongVector.SPECIES_PREFERRED;
    VectorSpecies<Double> ds = DoubleVector.SPECIES_PREFERRED;
    int[] ia = new int[is.length()];
    int[] ib = new int[is.length()];
    long[] la = new long[ls.length()];
    long[] lb = new long[ls.length()];
    double[] da = new double[ds.length()];
    double[] db = new double[ds.length()];
    for (int i = 0; i < ia.length; i++) {
      ia[i] = 3 * i - 7;
      ib[i] = i + 1;
    }
    for (int i = 0; i < la.length; i++) {
      la[i] = 5L * i - 11;
      lb[i] = i + 1L;
    }
    for (int i = 0; i < da.length; i++) {
      da[i] = 0.5 * i - 1;
      db[i] = i + 1;
    }
    MemorySegment segment = Arena.ofAuto().allocate(BYTES, 64);
    long acc = 0;
    queueBefore = c2QueueLengthNow();
    long waited = awaitC2Queue(PACE_BUDGET_MILLIS);
    long paceLeft = PACE_BUDGET_MILLIS - waited;
    for (int round = 0; round < ROUNDS; round++) {
      acc += ints(is, ia, ib, segment);
      acc += longs(ls, la, lb, segment);
      acc += doubles(ds, da, db);
      acc += conversions(is, ls, ds, ia, la, da);
      if ((round + 1) % PACE_ROUNDS == 0) {
        long w = awaitC2Queue(paceLeft);
        paceLeft -= w;
        waited += w;
      }
    }
    waited += awaitC2Queue(paceLeft);
    waitedMillis = waited;
    sink = acc;
    synchronized (VarkaVectorTemplateWarmup.class) {
      completed++;
    }
    return acc;
  }

  private static long ints(VectorSpecies<Integer> s, int[] ia, int[] ib, MemorySegment seg) {
    IntVector a = IntVector.fromArray(s, ia, 0);
    IntVector b = IntVector.fromArray(s, ib, 0);
    VectorMask<Integer> m = a.compare(VectorOperators.GT, b);
    VectorMask<Integer> tail = s.indexInRange(0, s.length() / 2 + 1);
    long acc = m.toLong() + (m.anyTrue() ? 1 : 0) + m.and(tail).or(tail.not()).trueCount();
    acc += VectorMask.fromLong(s, acc).toLong();
    for (Unary op : UNARY) {
      acc += a.lanewise(op).lane(0) + a.lanewise(op, m).lane(1);
    }
    for (Binary op : BINARY) {
      acc += a.lanewise(op, b).lane(0) + a.lanewise(op, b, m).lane(1)
          + a.lanewise(op, 3).lane(2) + a.lanewise(op, 3, m).lane(3);
    }
    for (Binary op : SHIFTS) {
      acc += a.lanewise(op, 2).lane(0) + a.lanewise(op, b).lane(1);
    }
    for (Comparison op : COMPARISONS) {
      acc += a.compare(op, b).toLong() + a.compare(op, 4).toLong()
          + a.compare(op, b, m).toLong();
    }
    for (int r = 0; r < THIN_CALLS_PER_ROUND; r++) {
      acc += a.add(b).lane(0) + a.sub(b).lane(0) + a.mul(b).lane(0) + a.add(4).lane(0)
          + a.sub(4, m).lane(0) + a.add(b, m).lane(0) + a.mul(4).lane(1) + a.min(b).lane(0)
          + a.max(b).lane(0) + a.and(b).lane(0) + a.or(b).lane(0) + a.and(1).lane(1)
          + a.div(7).lane(0) + a.blend(b, m).lane(0) + a.blend(9, m).lane(0)
          + IntVector.broadcast(s, 5).lane(0) + IntVector.zero(s).lane(0);
    }
    acc += a.compress(m).lane(0);
    a.intoMemorySegment(seg, 0, ByteOrder.LITTLE_ENDIAN);
    a.intoMemorySegment(seg, 64, ByteOrder.LITTLE_ENDIAN, tail);
    acc += IntVector.fromMemorySegment(s, seg, 0, ByteOrder.LITTLE_ENDIAN).lane(0)
        + IntVector.fromMemorySegment(s, seg, 64, ByteOrder.LITTLE_ENDIAN, tail).lane(1);
    return acc;
  }

  private static long longs(VectorSpecies<Long> s, long[] la, long[] lb, MemorySegment seg) {
    LongVector a = LongVector.fromArray(s, la, 0);
    LongVector b = LongVector.fromArray(s, lb, 0);
    VectorMask<Long> m = a.compare(VectorOperators.GT, b);
    VectorMask<Long> tail = s.indexInRange(0, s.length() / 2 + 1);
    long acc = m.toLong() + (m.anyTrue() ? 1 : 0) + m.and(tail).or(tail.not()).trueCount();
    acc += VectorMask.fromLong(s, acc).toLong();
    for (Unary op : UNARY) {
      acc += a.lanewise(op).lane(0) + a.lanewise(op, m).lane(1);
    }
    for (Binary op : BINARY) {
      acc += a.lanewise(op, b).lane(0) + a.lanewise(op, b, m).lane(1)
          + a.lanewise(op, 3L).lane(0) + a.lanewise(op, 3L, m).lane(1);
    }
    for (Binary op : SHIFTS) {
      acc += a.lanewise(op, 2L).lane(0) + a.lanewise(op, b).lane(1);
    }
    for (Comparison op : COMPARISONS) {
      acc += a.compare(op, b).toLong() + a.compare(op, 4L).toLong()
          + a.compare(op, b, m).toLong();
    }
    for (int r = 0; r < THIN_CALLS_PER_ROUND; r++) {
      acc += a.add(b).lane(0) + a.sub(b).lane(0) + a.mul(b).lane(0) + a.add(4L).lane(0)
          + a.sub(4L, m).lane(0) + a.add(b, m).lane(0) + a.min(b).lane(0) + a.max(b).lane(0)
          + a.and(b).lane(0) + a.or(b).lane(0) + a.blend(b, m).lane(0) + a.blend(9L, m).lane(0)
          + LongVector.broadcast(s, 5L).lane(0) + LongVector.zero(s).lane(0);
    }
    a.intoMemorySegment(seg, 128, ByteOrder.LITTLE_ENDIAN);
    a.intoMemorySegment(seg, 256, ByteOrder.LITTLE_ENDIAN, tail);
    acc += LongVector.fromMemorySegment(s, seg, 128, ByteOrder.LITTLE_ENDIAN).lane(0)
        + LongVector.fromMemorySegment(s, seg, 256, ByteOrder.LITTLE_ENDIAN, tail).lane(1);
    return acc;
  }

  private static long doubles(VectorSpecies<Double> s, double[] da, double[] db) {
    DoubleVector a = DoubleVector.fromArray(s, da, 0);
    DoubleVector b = DoubleVector.fromArray(s, db, 0);
    VectorMask<Double> m = a.compare(VectorOperators.GT, b);
    long acc = m.toLong();
    for (int r = 0; r < THIN_CALLS_PER_ROUND; r++) {
      acc += (long) (a.add(b).lane(0) + a.sub(b).lane(0) + a.mul(b).lane(0) + a.div(b).lane(0)
          + a.lanewise(VectorOperators.NEG).lane(0) + a.lanewise(VectorOperators.MAX, b).lane(0)
          + a.lanewise(VectorOperators.ADD, 2.0, m).lane(0) + a.blend(b, m).lane(0));
    }
    for (Comparison op : List.of(VectorOperators.EQ, VectorOperators.LT, VectorOperators.GE)) {
      acc += a.compare(op, b).toLong();
    }
    return acc;
  }

  private static long conversions(VectorSpecies<Integer> is, VectorSpecies<Long> ls,
      VectorSpecies<Double> ds, int[] ia, long[] la, double[] da) {
    IntVector i = IntVector.fromArray(is, ia, 0);
    LongVector l = LongVector.fromArray(ls, la, 0);
    DoubleVector d = DoubleVector.fromArray(ds, da, 0);
    long acc = i.convertShape(VectorOperators.I2L, ls, 0).reinterpretAsLongs().lane(0)
        + l.convertShape(VectorOperators.L2I, is, 0).reinterpretAsInts().lane(0)
        + (long) i.convertShape(VectorOperators.I2D, ds, 0).reinterpretAsDoubles().lane(0)
        + d.convertShape(VectorOperators.D2I, is, 0).reinterpretAsInts().lane(0)
        + l.reinterpretAsDoubles().reinterpretAsLongs().lane(0);
    return acc;
  }
}
