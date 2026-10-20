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
import java.util.List;

import org.apache.spark.util.Utils;

/**
 * One kernel warm-up in a JVM of its own, for {@code VarkaKernelWarmupSuite}'s compile tests, and
 * the shape and batches that suite warms.
 *
 * <p>A warm-up's verdict is a JIT outcome: the kernel's methods compiled by C2 with every vector
 * operation intrinsified, so that a call no longer allocates. The shared test JVM cannot promise
 * that outcome to a kernel it compiles late. Once an int kernel of a second species has run there
 * - the suites that check their answers at a lanes override do - the Vector API's shared templates
 * carry two receiver classes, C2 inlines both, and every vector the warmed kernel computes is
 * boxed where the two paths merge. The kernel then never stops allocating, and the warm-up rightly
 * never calls it compiled ({@code vector-api-and-width.md}, "two species in one JVM is a box per
 * iteration"; see {@code VARKA-209.md} 13.2). A fresh JVM per case is the clean JVM the verdict
 * assumes.
 *
 * <p>{@code main(<none|some|all>)} warms {@link #shape} on a batch of 10000 dates whose nulls the
 * argument names, waits for the warm-up to finish, then measures sixteen calls of 1024 rows on a
 * batch with no nulls and on one with some, and prints:
 * <ul>
 *   <li>{@code VARKA_WARMUP_SUPPORT=sampler=<bool> canWarm=<bool>};</li>
 *   <li>where the sampler works, {@code VARKA_WARMUP_OUTCOME=queued=<bool> idle=<bool>
 *       matches=<bool> state=<state> entryState=<state> ready=<bool> first=<bytes> last=<bytes>
 *       outcome=<the outcome record>}, {@code matches} saying the outcome is this shape's;</li>
 *   <li>where the warm-up was queued, {@code VARKA_WARMUP_ALLOC=served=<none|some> bytes=<bytes>}
 *       for each of the two batches, then {@code VARKA_WARMUP_OWN_RUN=served=<none|some>
 *       bytes=<bytes>}, the same calls through the kernel's own seven-argument {@code run}, an
 *       informational reading of the chain a second kernel class would leave a caller on, then
 *       {@code VARKA_WARMUP_JIT=<state>}, the kernel class's
 *       methods with C2 code and those with none ({@link VarkaKernelWarmup#jitState(Class)}),
 *       followed by the same for {@link VarkaFusedKernel}, whose default {@code run} is the
 *       entry the measured calls take;</li>
 *   <li>{@code VARKA_WARMUP_DONE=status=0}.</li>
 * </ul>
 *
 * <p>{@code main(<none|some|all>, others)} adds a second phase for the executor's situation,
 * where one JVM warms several shapes: after the readings above it warms {@link #LATER_SHAPES}
 * other shapes, each a class of its own through the same default {@code run}, and then reads the
 * first kernel again, printing {@code VARKA_WARMUP_LATER=served=<none|some> bytes=<bytes>
 * own=<bytes>} for each batch and {@code VARKA_WARMUP_LATER_JIT=<state>} (see {@link #later}).
 * The system property {@code varka.probe.busyThreads=<n>} starts that many spinning threads
 * first, which with {@code taskset} and {@code -XX:ActiveProcessorCount} starves the compiler
 * threads the way a loaded runner does.
 */
public final class VarkaKernelWarmupProbe {

  private VarkaKernelWarmupProbe() {
  }

  public static final String SUPPORT_PREFIX = "VARKA_WARMUP_SUPPORT=";
  public static final String OUTCOME_PREFIX = "VARKA_WARMUP_OUTCOME=";
  public static final String ALLOC_PREFIX = "VARKA_WARMUP_ALLOC=";
  public static final String OWN_RUN_PREFIX = "VARKA_WARMUP_OWN_RUN=";
  public static final String JIT_PREFIX = "VARKA_WARMUP_JIT=";
  public static final String LATER_PREFIX = "VARKA_WARMUP_LATER=";
  public static final String LATER_JIT_PREFIX = "VARKA_WARMUP_LATER_JIT=";
  public static final String DONE_PREFIX = "VARKA_WARMUP_DONE=";

  /**
   * A shape with enough calendar work that its uncompiled calls allocate unmistakably, emitted
   * to be warmed, so that the C1-exclusion directive reaches its class. Its outputs fall in two
   * groups whose loop methods compile one after the other, so a verdict has to wait for both.
   */
  public static VarkaShapeKey shape() {
    var d = new VarkaVectorIR.ColumnRef(0);
    var k = new VarkaVectorIR.LiteralSlot(0);
    List<VarkaVectorIR> roots = List.of(new VarkaVectorIR.AddMonths(d, k),
        new VarkaVectorIR.LastDay(d), new VarkaVectorIR.AddDays(d, k));
    return new VarkaShapeKey(roots, 1, 1, VarkaMatrixRuns.BASE, true);
  }

  public static final int NUM_OUTPUTS = 3;

  /**
   * The cold-start benchmark's shape at sixteen entries, {@code greatest(add_months(d, k),
   * date_add(d, k), last_day(d))} with a literal slot per entry: four groups of four outputs, the
   * width whose warm-up the milestone 7 close found released on every machine (VARKA-389).
   */
  public static VarkaShapeKey wideShape() {
    var d = new VarkaVectorIR.ColumnRef(0);
    var roots = new java.util.ArrayList<VarkaVectorIR>();
    for (int i = 0; i < WIDE_OUTPUTS; i++) {
      var k = new VarkaVectorIR.LiteralSlot(i);
      roots.add(new VarkaVectorIR.Greatest(
          new VarkaVectorIR.Greatest(new VarkaVectorIR.AddMonths(d, k),
              new VarkaVectorIR.AddDays(d, k)),
          new VarkaVectorIR.LastDay(d)));
    }
    return new VarkaShapeKey(List.copyOf(roots), 1, WIDE_OUTPUTS, VarkaMatrixRuns.BASE, true);
  }

  public static final int WIDE_OUTPUTS = 16;

  /** The wide shape's literals, the month and day counts 1 to 16. */
  static int[] wideLiterals() {
    int[] literals = new int[WIDE_OUTPUTS];
    for (int i = 0; i < WIDE_OUTPUTS; i++) {
      literals[i] = i + 1;
    }
    return literals;
  }

  /** Shapes the second phase warms after the first kernel's verdict: more than two classes. */
  public static final int LATER_SHAPES = 3;

  /**
   * Another shape, a class of its own: {@code i} picks among four small calendar shapes, each
   * with one input and one literal slot and {@link #laterOutputs} outputs.
   */
  static VarkaShapeKey laterShape(int i) {
    var d = new VarkaVectorIR.ColumnRef(0);
    var k = new VarkaVectorIR.LiteralSlot(0);
    List<VarkaVectorIR> roots = switch (i) {
      case 0 -> List.of(new VarkaVectorIR.AddDays(d, k));
      case 1 -> List.of(new VarkaVectorIR.AddMonths(d, k));
      case 2 -> List.of(new VarkaVectorIR.LastDay(d), new VarkaVectorIR.AddDays(d, k));
      default -> List.of(new VarkaVectorIR.AddMonths(d, k), new VarkaVectorIR.LastDay(d));
    };
    return new VarkaShapeKey(roots, 1, 1, VarkaMatrixRuns.BASE, true);
  }

  static int laterOutputs(int i) {
    return i >= 2 ? 2 : 1;
  }

  /** Where a batch of {@link #dates} has its nulls; {@code word} is how {@link #main} is told. */
  public enum Nulls {
    NO_NULLS("no nulls", "none"),
    SOME_NULLS("some nulls", "some"),
    ALL_NULL("only nulls", "all");

    public final String label;
    public final String word;

    Nulls(String label, String word) {
      this.label = label;
      this.word = word;
    }

    @Override public String toString() {
      return label;
    }

    boolean isNull(int row) {
      return switch (this) {
        case NO_NULLS -> false;
        case SOME_NULLS -> row % 7 == 3;
        case ALL_NULL -> true;
      };
    }
  }

  /** A batch of dates: its values, its validity bitmap and its null count. */
  public record Dates(MemorySegment data, MemorySegment validity, int nullCount) { }

  /**
   * {@code rows} dates around 2020 in memory the arena owns, with nulls as {@code nulls} says -
   * for some nulls, every seventh row - and the batch's null count.
   */
  public static Dates dates(Arena arena, int rows, Nulls nulls) {
    var data = arena.allocate(rows * 4L, 64);
    var validity = arena.allocate(((rows + 63) / 64) * 8L, 64);
    int nullCount = 0;
    for (int r = 0; r < rows; r++) {
      data.setAtIndex(ValueLayout.JAVA_INT, r, 18262 + r % 1460);
      if (nulls.isNull(r)) {
        nullCount++;
      } else {
        int b = r / 8;
        validity.set(ValueLayout.JAVA_BYTE, b,
            (byte) (validity.get(ValueLayout.JAVA_BYTE, b) | (1 << (r % 8))));
      }
    }
    return new Dates(data, validity, nullCount);
  }

  /** A queued warm-up: the cache's entry, and whether the warm-up was queued. */
  public record Warmed(VarkaShapeEntry entry, boolean queued) { }

  /**
   * Queues a warm-up of the cache's kernel for {@link #shape} on a batch of {@code rows} dates with
   * nulls as {@code nulls} says, its one input declared nullable. The claim is the caller's first,
   * so it cannot fail on a fresh cache.
   */
  public static Warmed warm(VarkaShapeCacheImpl cache, int rows, Nulls nulls) {
    return warm(cache, rows, nulls, shape(), NUM_OUTPUTS, new int[] {2});
  }

  /** As above, for {@code shape} with {@code numOutputs} outputs and {@code scalarArgs}. */
  public static Warmed warm(VarkaShapeCacheImpl cache, int rows, Nulls nulls,
      VarkaShapeKey shape, int numOutputs, int[] scalarArgs) {
    var entry = cache.getOrEmit(Utils.getContextOrSparkClassLoader(), shape, "warmup-suite")
        .entry();
    if (!entry.warmth().tryClaim()) {
      throw new IllegalArgumentException(
          "requirement failed: the shape's warm-up was already claimed");
    }
    try (var arena = Arena.ofConfined()) {
      var batch = dates(arena, rows, nulls);
      long validityAddress = batch.nullCount() == 0 ? 0L : batch.validity().address();
      boolean queued = VarkaKernelWarmup.start(entry.warmth(), entry.shapeHash(),
          entry.newKernel(), false, new long[] {batch.data().address()},
          new long[] {validityAddress}, new int[] {batch.nullCount()}, new int[] {4}, true, rows,
          numOutputs, scalarArgs, new long[0]);
      // The warm-up copies the batch before start returns, so the batch can go at once.
      return new Warmed(entry, queued);
    }
  }

  /**
   * What the kernel allocates over sixteen calls of 1024 rows with nulls as {@code nulls} says,
   * after fifty calls to settle: the evidence of which of its drivers runs compiled code.
   *
   * <p>The calls take the form with a scratch address, the entry the warm-up's calls and the
   * evaluator's take. The warm-up's probe calls reach that form's default method from code that
   * does not inline it, as these do, so both run the method's own code at whatever tier it has:
   * at C2, in a JVM with one kernel class, a compile with the whole kernel inlined; before that,
   * the kernel's own methods. The seven-argument form always enters the kernel's own {@code run},
   * a compile of its own that, with the light loops it calls, may still be queued, or never
   * requested, while the default method's C2 compile serves the verdict (VARKA-398.md 2). See
   * VARKA-398.md 7 for what a second kernel class does to that compile.
   */
  public static long allocationOfCalls(VarkaFusedKernel kernel, Nulls nulls) {
    return allocationOfCalls(kernel, nulls, false);
  }

  /**
   * As above, through the kernel's own seven-argument {@code run} when {@code ownRun}: the chain
   * the warm-up does not wait for, read for information only (VARKA-398.md 3).
   */
  static long allocationOfCalls(VarkaFusedKernel kernel, Nulls nulls, boolean ownRun) {
    int rows = 1024;
    try (var arena = Arena.ofConfined()) {
      var batch = dates(arena, rows, nulls);
      long[] src = {batch.data().address()};
      long[] validity = {batch.nullCount() == 0 ? 0L : batch.validity().address()};
      int[] nullCounts = {batch.nullCount()};
      long[] dstData = new long[NUM_OUTPUTS];
      long[] dstValidity = new long[NUM_OUTPUTS];
      for (int o = 0; o < NUM_OUTPUTS; o++) {
        dstData[o] = arena.allocate(rows * 4L, 64).address();
      }
      for (int o = 0; o < NUM_OUTPUTS; o++) {
        dstValidity[o] = arena.allocate(rows / 8L + 8, 64).address();
      }
      int[] literals = {2};
      int perRow = kernel.scratchBytesPerRow();
      long scratch = perRow == 0 ? 0L : arena.allocate((long) perRow * rows, 64).address();
      for (int k = 0; k < 50; k++) {
        if (ownRun) {
          kernel.run(src, validity, nullCounts, dstData, dstValidity, literals, rows);
        } else {
          kernel.run(src, validity, nullCounts, dstData, dstValidity, literals, rows, scratch);
        }
      }
      // A plain loop: a lambda first created inside the window would count its own bootstrap,
      // tens of kilobytes, against the kernel.
      long before = VarkaAllocationSampler.allocatedBytes();
      for (int k = 0; k < 16; k++) {
        if (ownRun) {
          kernel.run(src, validity, nullCounts, dstData, dstValidity, literals, rows);
        } else {
          kernel.run(src, validity, nullCounts, dstData, dstValidity, literals, rows, scratch);
        }
      }
      return VarkaAllocationSampler.allocatedBytes() - before;
    }
  }

  /**
   * The executor's situation: {@code first} has its verdict and has been read, and the JVM now
   * warms {@link #LATER_SHAPES} other shapes, one after the other, each a class of its own
   * through the same default {@code run}. Then {@code first} is read again, as a task of its
   * shape would call it: what its calls allocate through the evaluator's scratch form and through
   * its own {@code run}, and its JIT state.
   */
  static void later(VarkaFusedKernel first) throws InterruptedException {
    for (int i = 0; i < LATER_SHAPES; i++) {
      var warmed = warm(new VarkaShapeCacheImpl(8), 10000, Nulls.SOME_NULLS, laterShape(i),
          laterOutputs(i), new int[] {2});
      if (!warmed.queued() || !VarkaKernelWarmup.awaitIdle(200000)) {
        throw new IllegalStateException("the later shape " + i + " did not warm");
      }
    }
    for (var served : List.of(Nulls.NO_NULLS, Nulls.SOME_NULLS)) {
      System.out.println(LATER_PREFIX + "served=" + served.word + " bytes="
          + allocationOfCalls(first, served) + " own=" + allocationOfCalls(first, served, true));
    }
    System.out.println(LATER_JIT_PREFIX + VarkaKernelWarmup.jitState(first.getClass()));
  }

  /**
   * Runs {@code kernel} unwarmed over {@code calls} batches of 1,024 rows, dense and masked in
   * turn, as a session with the warm-up off runs a kernel: its interpreted calls take the shared
   * Vector API templates past C2's thresholds, so that they are compiled standalone from this one
   * kernel's profile before any warm-up has run (VARKA-389's order, the cold-start benchmark's).
   * Waits two seconds after, for the compiles to land.
   */
  static void runUnwarmed(VarkaFusedKernel kernel, int numOutputs, int[] literals, int calls)
      throws InterruptedException {
    int rows = 1024;
    try (var arena = Arena.ofConfined()) {
      var dense = dates(arena, rows, Nulls.NO_NULLS);
      var masked = dates(arena, rows, Nulls.SOME_NULLS);
      long[] dstData = new long[numOutputs];
      long[] dstValidity = new long[numOutputs];
      for (int o = 0; o < numOutputs; o++) {
        dstData[o] = arena.allocate(rows * 4L, 64).address();
        dstValidity[o] = arena.allocate(rows / 8L + 8, 64).address();
      }
      for (int k = 0; k < calls; k++) {
        var batch = k % 2 == 0 ? dense : masked;
        long[] validity = {batch.nullCount() == 0 ? 0L : batch.validity().address()};
        kernel.run(new long[] {batch.data().address()}, validity,
            new int[] {batch.nullCount()}, dstData, dstValidity, literals, rows);
      }
    }
    Thread.sleep(2000);
  }

  /** Threads that compete with the JIT's compiler threads for the processors they run on. */
  private static void startBusyThreads(int count) {
    for (int i = 0; i < count; i++) {
      Thread.ofPlatform().daemon().name("probe-busy-" + i).start(() -> {
        long x = 1;
        while (true) {
          x = x * 6364136223846793005L + 1442695040888963407L;
          if (x == 0) {
            System.out.print("");
          }
        }
      });
    }
  }

  public static void main(String[] args) throws InterruptedException {
    if (args.length < 1 || args.length > 2
        || (args.length == 2 && !"wide".equals(args[1]) && !"others".equals(args[1]))) {
      throw new IllegalArgumentException(
          "requirement failed: usage: VarkaKernelWarmupProbe <none|some|all> [wide|others]");
    }
    boolean wide = args.length == 2 && "wide".equals(args[1]);
    boolean others = args.length == 2 && "others".equals(args[1]);
    Nulls claimed = null;
    for (var n : Nulls.values()) {
      if (n.word.equals(args[0])) {
        claimed = n;
      }
    }
    if (claimed == null) {
      throw new IllegalArgumentException("the claiming batch has none, some or all nulls, not "
          + args[0]);
    }
    startBusyThreads(Integer.getInteger("varka.probe.busyThreads", 0));
    boolean sampler = VarkaAllocationSampler.supported();
    boolean canWarm = VarkaKernelWarmup.canWarm();
    System.out.println(SUPPORT_PREFIX + "sampler=" + sampler + " canWarm=" + canWarm);
    if (sampler) {
      Warmed warmed;
      if (wide) {
        // The benchmark's order: the shape runs unwarmed first, from a cache of its own, and a
        // new class of it is then warmed, as VarkaColdStartBenchmark does between its arms.
        var unwarmed = new VarkaShapeCacheImpl(8)
            .getOrEmit(Utils.getContextOrSparkClassLoader(), wideShape(), "warmup-suite")
            .entry();
        runUnwarmed(unwarmed.newKernel(), WIDE_OUTPUTS, wideLiterals(), 600);
        VarkaShapeCache.invalidateAll();
        warmed = warm(new VarkaShapeCacheImpl(8), 10000, claimed, wideShape(), WIDE_OUTPUTS,
            wideLiterals());
      } else {
        warmed = warm(new VarkaShapeCacheImpl(8), 10000, claimed);
      }
      var entry = warmed.entry();
      boolean queued = warmed.queued();
      boolean idle = queued && VarkaKernelWarmup.awaitIdle(200000);
      var outcomes = VarkaKernelWarmup.recentOutcomes();
      var outcome = outcomes.isEmpty() ? null : outcomes.get(outcomes.size() - 1);
      boolean matches = outcome != null && outcome.shapeHash().equals(entry.shapeHash());
      System.out.println(OUTCOME_PREFIX + "queued=" + queued + " idle=" + idle + " matches="
          + matches + " state=" + (outcome == null ? "none" : outcome.state().toString())
          + " entryState=" + entry.warmth().state() + " ready=" + entry.warmth().ready()
          + " first=" + (outcome == null ? -1L : outcome.firstProbeBytes())
          + " last=" + (outcome == null ? -1L : outcome.lastProbeBytes())
          + " outcome=" + (outcome == null ? "none" : outcome.toString()));
      if (queued && !wide) {
        var kernel = entry.newKernel();
        for (var served : List.of(Nulls.NO_NULLS, Nulls.SOME_NULLS)) {
          System.out.println(ALLOC_PREFIX + "served=" + served.word + " bytes="
              + allocationOfCalls(kernel, served));
        }
        for (var served : List.of(Nulls.NO_NULLS, Nulls.SOME_NULLS)) {
          System.out.println(OWN_RUN_PREFIX + "served=" + served.word + " bytes="
              + allocationOfCalls(kernel, served, true));
        }
        // The interface's part of its state is only its code list: the queue and the code cache
        // are the same JVM's, already in the kernel's.
        String jit = VarkaKernelWarmup.jitState(VarkaFusedKernel.class);
        System.out.println(JIT_PREFIX + VarkaKernelWarmup.jitState(kernel.getClass())
            + "; entry " + jit.substring(Math.max(0, jit.indexOf("kernel code"))));
        if (others) {
          later(kernel);
        }
      }
    }
    System.out.println(DONE_PREFIX + "status=0");
  }
}
