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
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import javax.management.ObjectName;

import jdk.incubator.vector.IntVector;

import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.util.DateTimeUtils$;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.util.Utils;

/**
 * The child process behind {@code dev/varka_kernel_history.sh} (VARKA-257, scope item 62): what a
 * kernel meets when it is compiled after {@code N} other kernels in the same JVM, which the
 * deopt guard, the inlining-cliff probe and the warm-up probe never see, since they fork a fresh
 * JVM per class.
 *
 * <p>The history is {@code N} kernels of the IR fuzz grammar ({@link VarkaIrGrammar}), the int
 * and the long lane in turn, each emitted under {@link VarkaEmitOptions#DEFAULTS} and warmed by
 * production's warm-up worker ({@link VarkaKernelWarmup}), one after another, its C1 exclusion
 * and its Vector API template prewarm included unless the JVM turned them off. So the history is
 * the one a production JVM runs, at one vector species: no draw carries a lanes override, since a
 * second int species in one JVM boxes every later kernel for a reason of its own
 * ({@code VARKA-209.md} 13.2). The 128-bit reading is a JVM started with
 * {@code -XX:MaxVectorSize=16}.
 *
 * <p>Then the kernel under test: the inlining-cliff probe's cheap tails, {@code year(d) + k} over
 * one date, 24 outputs by default or the 64 whose class {@code VARKA-209.md} 12.3 read slow in a
 * benchmark JVM after nine other kernels and never alone. It is warmed the same way, then run
 * over 1024-row null-free batches for a number of seconds, its rate printed every second and its
 * allocation per call over the last second. Those are the readings of the two slow modes: a loop
 * C2 never reached runs at hundreds of nanoseconds a row (the cliff probe's mode, VARKA-198,
 * where C1 failed; the warm-up keeps C1 off its classes, so here it is a compile that never
 * came), and a C2 compile that keeps a vector box per loop iteration (VARKA-295, VARKA-371,
 * VARKA-373) runs a few nanoseconds a row slower but allocates thousands of bytes a call. The
 * code list says which of its loop methods are C2 code at the end.
 *
 * <p>Every kernel's answers are compared after its warm-up, the history's and the one under test,
 * through {@link VarkaKernelChecks#runLoaded} on the instance the JVM compiled, over a batch with
 * nulls and one without, against {@link VarkaReference}: a kernel that runs fast and answers
 * wrong after a history is the finding the rate cannot show. A wrong answer is printed and the
 * process exits 1 at the end; a failure of the harness rather than the check exits 2.
 *
 * <p>Arguments: {@code <after N> <seed> <seconds> [outputs]}, the last the cheap-tail count
 * of the kernel under test (24 by default: the 64 of VARKA-209 12.3 takes 45 to 60 s to warm on
 * three loaded cores, {@code VARKA-257.md} 2). Lines the reader
 * ({@code dev/varka_kernel_history.py}) looks for:
 * <ul>
 *   <li>{@code VARKA_HISTORY_BEGIN=pid=<pid> after=<N> seed=<seed> outputs=<n> bits=<preferred
 *       int vector bits> prewarm=<on|off>};</li>
 *   <li>{@code VARKA_HISTORY_KERNEL=<position> draw=<k> lane=<int|long> outputs=<n>
 *       state=<COMPILED|RELEASED|...> millis=<warm-up> first=<bytes> last=<bytes>
 *       answers=<ok|declined|wrong>}, with {@code tiers=<loop tiers>} when released, one per
 *       history kernel, position 0 first; {@code VARKA_HISTORY_SKIPPED=<k> <why>} for a draw the
 *       emitter declined or whose shape repeated an earlier draw's;</li>
 *   <li>{@code VARKA_HISTORY_TEST=state=<state> millis=<warm-up> first=<bytes> last=<bytes>
 *       methods=<loop>:<bytes>:<sites>,...};</li>
 *   <li>{@code VARKA_HISTORY_RATE=<second> <nanoseconds a row>} once a second;</li>
 *   <li>{@code VARKA_HISTORY_ALLOC=<bytes a call>} over the last second;</li>
 *   <li>{@code VARKA_HISTORY_CODE=<loop method>:<tier of the code in use, or none>,...};</li>
 *   <li>{@code VARKA_HISTORY_WRONG=<position or test> <the check's message, first line>}, and
 *       {@code VARKA_HISTORY_ERROR=} the same for a failure that is not the check's;</li>
 *   <li>{@code VARKA_HISTORY_DONE=status=<0, 1 for a wrong answer, 2 for an error>
 *       runStatus=<the timed calls' statuses or'd> answers=<the test kernel's>
 *       templateMillis=<the prewarm's time>}.</li>
 * </ul>
 */
public final class VarkaKernelHistoryProbe {

  private VarkaKernelHistoryProbe() {
  }

  public static final String BEGIN_PREFIX = "VARKA_HISTORY_BEGIN=";
  public static final String KERNEL_PREFIX = "VARKA_HISTORY_KERNEL=";
  public static final String TEST_PREFIX = "VARKA_HISTORY_TEST=";
  public static final String RATE_PREFIX = "VARKA_HISTORY_RATE=";
  public static final String ALLOC_PREFIX = "VARKA_HISTORY_ALLOC=";
  public static final String CODE_PREFIX = "VARKA_HISTORY_CODE=";
  public static final String WRONG_PREFIX = "VARKA_HISTORY_WRONG=";
  public static final String ERROR_PREFIX = "VARKA_HISTORY_ERROR=";
  public static final String SKIPPED_PREFIX = "VARKA_HISTORY_SKIPPED=";
  public static final String DONE_PREFIX = "VARKA_HISTORY_DONE=";

  /** Plants a wrong reference answer in every comparison, to show the check compares. */
  static final String PLANT_PROPERTY = "varka.history.plantWrongAnswer";

  /**
   * The history's first draw, 0 by default: a released history kernel replays alone as N = 1
   * from its draw, which says whether its release was the history's or its shape's.
   */
  static final String FROM_PROPERTY = "varka.history.from";

  /** Rows of every batch: the warm-up's copy, the timed calls and the compared batches. */
  private static final int ROWS = 1024;

  /** Outputs of the kernel under test by default: the cliff guard's smaller case. */
  static final int DEFAULT_TEST_OUTPUTS = 24;

  /** How long one warm-up may take before the probe stops waiting: past any deadline in force. */
  private static final long AWAIT_MILLIS = 300_000L;

  /** Draws tried for one history position before the probe gives up on the grammar. */
  private static final int MAX_DRAWS_PER_KERNEL = 20;

  /**
   * The arguments: how many kernels run first, the history's seed, the timed seconds, and the
   * outputs of the kernel under test.
   */
  record Args(int after, long seed, int seconds, int outputs) {
    Args {
      if (after < 0 || seconds < 1 || outputs < 1) {
        throw new IllegalArgumentException("requirement failed: after >= 0, seconds >= 1 and "
            + "outputs >= 1, not " + after + ", " + seconds + ", " + outputs);
      }
    }

    static Args parse(String[] args) {
      if (args.length != 3 && args.length != 4) {
        throw new IllegalArgumentException("requirement failed: usage: VarkaKernelHistoryProbe "
            + "<after N> <seed> <seconds> [outputs]");
      }
      return new Args(Integer.parseInt(args[0]), Long.parseLong(args[1]),
          Integer.parseInt(args[2]),
          args.length == 4 ? Integer.parseInt(args[3]) : DEFAULT_TEST_OUTPUTS);
    }
  }

  /**
   * One kernel to warm and check: its shape, and one batch of its inputs - values inside the
   * grammar's bounds, so that no guard declines, and every fifth row null in each input.
   */
  record Subject(List<VarkaVectorIR> roots, int numInputs, boolean longLane, int[] intLits,
      long[] longLits, int[][] intData, long[][] longData) {

    int numLiterals() {
      return longLane ? longLits.length : intLits.length;
    }

    boolean isNull(int column, int row) {
      return (row + column) % 5 == 0;
    }
  }

  /** The trunc level codes TruncLevelLeaf produces, week to year. */
  private static final int WEEK = DateTimeUtils$.MODULE$.TRUNC_TO_WEEK();
  private static final int YEAR = DateTimeUtils$.MODULE$.TRUNC_TO_YEAR();

  /** History kernel {@code k}: the int lane's draw at even positions, the long lane's at odd. */
  static Subject draw(long seed, int k) {
    Random rnd = VarkaIrGrammar.shapeRandom(seed, k);
    if (k % 2 == 0) {
      var d = VarkaIrGrammar.drawShape(rnd);
      int[] lits = new int[d.numLiterals()];
      for (int i = 0; i < lits.length; i++) {
        lits[i] = VarkaIrGrammar.intWithin(rnd, VarkaIrGrammar.LITERAL_BOUND);
      }
      int[][] data = new int[d.numInputs()][ROWS];
      for (int c = 0; c < d.numInputs(); c++) {
        for (int i = 0; i < ROWS; i++) {
          if (c == d.levelOrdinal()) {
            // The codes TruncLevelLeaf produces, as the IR fuzzer draws them.
            data[c][i] = WEEK + rnd.nextInt(YEAR - WEEK + 1);
          } else {
            data[c][i] = VarkaIrGrammar.intWithin(rnd, c == d.smallOrdinal()
                ? VarkaChrono.MONTH_ARITH_MAX_MONTHS
                : Math.toIntExact(VarkaIrGrammar.COLUMN_BOUND));
          }
        }
      }
      return new Subject(d.roots(), d.numInputs(), false, lits, new long[0], data, null);
    }
    var d = VarkaIrGrammar.drawLongShape(rnd);
    long[] lits = new long[d.numLiterals()];
    for (int i = 0; i < lits.length; i++) {
      lits[i] = VarkaIrGrammar.within(rnd, VarkaIrGrammar.LONG_LITERAL_BOUND);
    }
    long[][] data = new long[d.numInputs()][ROWS];
    for (int c = 0; c < d.numInputs(); c++) {
      for (int i = 0; i < ROWS; i++) {
        data[c][i] = VarkaIrGrammar.within(rnd, VarkaIrGrammar.LONG_COLUMN_BOUND);
      }
    }
    return new Subject(d.roots(), d.numInputs(), true, new int[0], lits, null, data);
  }

  private static final Seq<Attribute> COLUMNS = CollectionConverters.asScala(
      List.<Attribute>of(VarkaSqlResolve.column("d", DataTypes.DateType))).toSeq();

  /** The kernel under test: the cliff probe's cheap tails, through the real compiler. */
  static Subject testSubject(int outputs) {
    var exprs = new ArrayList<NamedExpression>();
    for (int k = 1; k <= outputs; k++) {
      exprs.add(VarkaSqlResolve.named(VarkaSqlResolve.parse("year(d) + " + k, COLUMNS),
          "c" + k));
    }
    CompiledVarkaProjection fused = VarkaExpressionCompiler.compile(
        CollectionConverters.asScala(exprs).toSeq(), COLUMNS)
        .orElseThrow(() -> new IllegalStateException("the cheap tails did not fuse"));
    int[] lits = fused.literals().stream().mapToInt(Integer::intValue).toArray();
    int[][] data = new int[1][ROWS];
    for (int i = 0; i < ROWS; i++) {
      // The cliff probe's dates: around 1970, inside every calendar guard.
      data[0][i] = i * 7 % 20000 - 10000;
    }
    return new Subject(fused.outputs(), 1, false, lits, new long[0], data, null);
  }

  /** A queued, finished warm-up: the cache's entry, and the outcome the worker recorded. */
  record Warmed(VarkaShapeEntry entry, VarkaKernelWarmth.State state, long millis,
      long firstProbeBytes, long lastProbeBytes) { }

  /**
   * Emits {@code s} warmed through the cache, as a session with the warm-up on does; {@link #warm}
   * then queues its warm-up on its batch, every input nullable, and waits for the verdict.
   */
  static VarkaShapeEntry emit(VarkaShapeCacheImpl cache, Subject s, String label) {
    var key = new VarkaShapeKey(s.roots(), s.numInputs(), s.numLiterals(),
        VarkaEmitOptions.DEFAULTS, true);
    return cache.getOrEmit(Utils.getContextOrSparkClassLoader(), key, label).entry();
  }

  /** Queues the warm-up of {@code entry}'s kernel on {@code s}'s batch; see {@link #emit}. */
  static Warmed warm(VarkaShapeEntry entry, Subject s, String label)
      throws InterruptedException {
    if (!entry.warmth().tryClaim()) {
      throw new IllegalStateException("the shape's warm-up was already claimed: " + label);
    }
    long start = System.nanoTime();
    try (var arena = Arena.ofConfined()) {
      int n = s.numInputs();
      long[] data = new long[n];
      long[] validity = new long[n];
      int[] nullCount = new int[n];
      int[] widths = new int[n];
      for (int c = 0; c < n; c++) {
        int width = s.longLane() ? 8 : 4;
        widths[c] = width;
        MemorySegment values = arena.allocate((long) ROWS * width, 64);
        MemorySegment bits = arena.allocate(((ROWS + 63) / 64) * 8L, 64);
        for (int i = 0; i < ROWS; i++) {
          if (s.longLane()) {
            values.setAtIndex(ValueLayout.JAVA_LONG, i, s.longData()[c][i]);
          } else {
            values.setAtIndex(ValueLayout.JAVA_INT, i, s.intData()[c][i]);
          }
          if (s.isNull(c, i)) {
            nullCount[c]++;
          } else {
            int b = i / 8;
            bits.set(ValueLayout.JAVA_BYTE, b,
                (byte) (bits.get(ValueLayout.JAVA_BYTE, b) | (1 << (i % 8))));
          }
        }
        data[c] = values.address();
        validity[c] = bits.address();
      }
      var outWidths = new int[s.roots().size()];
      Arrays.fill(outWidths, Long.BYTES);
      boolean queued = VarkaKernelWarmup.start(entry.warmth(), entry.shapeHash(),
          entry.newKernel(), s.longLane(), data, validity, nullCount, widths, true, ROWS,
          outWidths, s.intLits(), s.longLits());
      if (!queued) {
        throw new IllegalStateException("the warm-up was not queued: " + label);
      }
    }
    if (!VarkaKernelWarmup.awaitIdle(AWAIT_MILLIS)) {
      throw new IllegalStateException("the warm-up did not finish in " + AWAIT_MILLIS + " ms");
    }
    long millis = (System.nanoTime() - start) / 1_000_000L;
    var outcomes = VarkaKernelWarmup.recentOutcomes();
    var outcome = outcomes.isEmpty() ? null : outcomes.get(outcomes.size() - 1);
    boolean ours = outcome != null && outcome.shapeHash().equals(entry.shapeHash());
    return new Warmed(entry, entry.warmth().state(), millis,
        ours ? outcome.firstProbeBytes() : -1L, ours ? outcome.lastProbeBytes() : -1L);
  }

  /**
   * The kernel's answers on its batch, through both bodies - the batch's nulls, then none - on
   * the instance given: {@code ok}, {@code declined} when a guard declined a body (allowed: the
   * check is of answers, and the values are drawn inside the bounds), or the failure's first line
   * prefixed with {@code wrong: } for the check's own failures, which are all
   * {@link IllegalStateException}s (a row, a validity bit or a status that differs), and with
   * {@code error: } for anything else, which is the harness's and not the kernel's.
   */
  static String answers(VarkaFusedKernel kernel, Subject s, String context) {
    int n = s.numInputs();
    boolean[][] someNulls = new boolean[n][ROWS];
    boolean[][] noNulls = new boolean[n][ROWS];
    for (int c = 0; c < n; c++) {
      for (int i = 0; i < ROWS; i++) {
        someNulls[c][i] = s.isNull(c, i);
      }
    }
    boolean declined = false;
    try {
      for (boolean[][] nulls : List.of(someNulls, noNulls)) {
        int status = s.longLane()
            ? VarkaKernelChecks.runLoadedLong(context, kernel, s.roots(), n, s.longLits(),
                new VarkaKernelChecks.LongBatch(ROWS, nulls, s.longData(), false), true,
                VarkaKernelChecks.CallHook.NONE)
            : VarkaKernelChecks.runLoaded(context, kernel, s.roots(), n, s.intLits(),
                new VarkaKernelChecks.Batch(ROWS, nulls, s.intData(), false), true,
                VarkaKernelChecks.CallHook.NONE);
        declined |= status != 0;
      }
    } catch (IllegalStateException e) {
      return "wrong: " + firstLine(e);
    } catch (VirtualMachineError e) {
      throw e;
    } catch (Throwable t) {
      return "error: " + firstLine(t);
    }
    return declined ? "declined" : "ok";
  }

  private static String firstLine(Throwable t) {
    return t.getClass().getSimpleName() + ": "
        + String.valueOf(t.getMessage()).lines().findFirst().orElse("");
  }

  /**
   * The tier of the code in use for each loop method of {@code kernelClass}, from
   * {@code Compiler.codelist} ({@code VarkaKernelWarmup.jitState} reads the same list): the highest
   * tier in use, {@code none} for a loop method with no code in use.
   */
  static String loopTiers(Class<?> kernelClass) {
    var tiers = new TreeMap<String, String>();
    for (var m : kernelClass.getDeclaredMethods()) {
      if (VarkaMethodNames.isLoop(m.getName())) {
        tiers.put(m.getName(), "none");
      }
    }
    try {
      Object list = ManagementFactory.getPlatformMBeanServer().invoke(
          new ObjectName("com.sun.management:type=DiagnosticCommand"), "compilerCodelist",
          new Object[] {null}, new String[] {String[].class.getName()});
      String prefix = kernelClass.getName() + ".";
      for (String line : String.valueOf(list).split("\\R")) {
        // "<id> <tier> <state> <class>.<method><descriptor> [<addresses>]"; state 0 is in use.
        String[] fields = line.strip().split(" ", 5);
        if (fields.length < 4 || !fields[3].startsWith(prefix) || !"0".equals(fields[2])) {
          continue;
        }
        String method = fields[3].substring(prefix.length(), fields[3].indexOf('('));
        String seen = tiers.get(method);
        if (seen != null && (seen.equals("none") || seen.compareTo("L" + fields[1]) < 0)) {
          tiers.put(method, "L" + fields[1]);
        }
      }
    } catch (Exception e) {
      return "unavailable:" + e.getClass().getSimpleName();
    }
    var parts = new ArrayList<String>();
    tiers.forEach((m, t) -> parts.add(m + ":" + t));
    return String.join(",", parts);
  }

  public static void main(String[] argv) throws Exception {
    var args = Args.parse(argv);
    // The check of the check (VARKA-274's planted answer): with it set, every compared kernel
    // must read wrong, so a fork that compares nothing cannot pass.
    VarkaKernelChecks.plantWrongAnswer = Boolean.getBoolean(PLANT_PROPERTY);
    boolean prewarm = !"false".equals(System.getProperty(VarkaVectorTemplateWarmup.PROPERTY));
    System.out.println(BEGIN_PREFIX + "pid=" + ProcessHandle.current().pid() + " after="
        + args.after() + " seed=" + args.seed() + " outputs=" + args.outputs() + " bits="
        + IntVector.SPECIES_PREFERRED.vectorBitSize() + " prewarm=" + (prewarm ? "on" : "off"));
    if (!VarkaKernelWarmup.canWarm()) {
      System.out.println(DONE_PREFIX + "status=3 reason=this JVM cannot warm kernels");
      System.exit(3);
    }
    var cache = new VarkaShapeCacheImpl(args.after() + 8);
    int status = 0;
    int k = Integer.getInteger(FROM_PROPERTY, 0);
    for (int position = 0; position < args.after(); position++) {
      Subject s = null;
      VarkaShapeEntry entry = null;
      // A draw the emitter declines (past a class-file cap), or one whose shape an earlier draw
      // already had - the key holds no literal values, so the cache hands back the kernel already
      // warmed - does not run; the next draw takes its position. Any other emitter failure ends
      // the fork, which the reader reports as unfinished.
      for (int tries = 0; entry == null; tries++, k++) {
        if (tries == MAX_DRAWS_PER_KERNEL) {
          throw new IllegalStateException(tries + " draws in a row did not run, at draw " + k);
        }
        s = draw(args.seed(), k);
        try {
          entry = emit(cache, s, "history-" + k);
          if (entry.warmth().state() != VarkaKernelWarmth.State.COLD) {
            System.out.println(SKIPPED_PREFIX + k + " repeated shape");
            entry = null;
          }
        } catch (VarkaEmitDeclined e) {
          System.out.println(SKIPPED_PREFIX + k + " declined: " + firstLine(e));
        }
      }
      Warmed w = warm(entry, s, "history-" + (k - 1));
      String verdict = answers(w.entry().newKernel(), s, "history kernel " + position);
      status = Math.max(status, failed(verdict, position + " draw=" + (k - 1)));
      System.out.println(KERNEL_PREFIX + position + " draw=" + (k - 1) + " lane="
          + (s.longLane() ? "long" : "int") + " outputs=" + s.roots().size() + " state="
          + w.state() + " millis=" + w.millis() + " first=" + w.firstProbeBytes() + " last="
          + w.lastProbeBytes() + " answers=" + word(verdict)
          // A released kernel's loop tiers: no C2 code is a compile that never came or a
          // deoptimization cycle, C2 code in use is a compiled kernel still allocating.
          + (w.state() == VarkaKernelWarmth.State.RELEASED
              ? " tiers=" + loopTiers(w.entry().klass()) : ""));
    }

    Subject test = testSubject(args.outputs());
    Warmed w = warm(emit(cache, test, "kernel-under-test"), test, "kernel-under-test");
    var measured = VarkaEmittedClass.measure(w.entry().classBytes());
    var methods = new ArrayList<String>();
    for (String m : VarkaEmitterTestSupport.methodNames(w.entry().classBytes())) {
      if (VarkaMethodNames.isLoop(m)) {
        methods.add(m + ":" + measured.codeLength().get(m) + ":"
            + measured.vectorCallSites().get(m));
      }
    }
    System.out.println(TEST_PREFIX + "state=" + w.state() + " millis=" + w.millis() + " first="
        + w.firstProbeBytes() + " last=" + w.lastProbeBytes() + " methods="
        + String.join(",", methods));
    var kernel = w.entry().newKernel();
    int runStatus = timed(kernel, test, args.seconds());
    System.out.println(CODE_PREFIX + loopTiers(w.entry().klass()));
    String verdict = answers(kernel, test, "kernel under test");
    status = Math.max(status, failed(verdict, "test"));
    // A timed call that declined ran no loop, so its rate and allocation say nothing.
    System.out.println(DONE_PREFIX + "status=" + status + " runStatus=" + runStatus
        + " answers=" + word(verdict) + " templateMillis="
        + VarkaKernelWarmup.templateWarmupNanos() / 1_000_000L);
    System.exit(status);
  }

  /** The verdict's first word: ok, declined, wrong or error. */
  private static String word(String verdict) {
    int colon = verdict.indexOf(':');
    return colon < 0 ? verdict : verdict.substring(0, colon);
  }

  /**
   * Prints a wrong answer or a harness error of the kernel at {@code where}, and returns the exit
   * status it asks for: 1 for a wrong answer, 2 for an error, 0 for neither.
   */
  private static int failed(String verdict, String where) {
    return switch (word(verdict)) {
      case "wrong" -> {
        System.out.println(WRONG_PREFIX + where + " " + verdict);
        yield 1;
      }
      case "error" -> {
        System.out.println(ERROR_PREFIX + where + " " + verdict);
        yield 2;
      }
      default -> 0;
    };
  }

  /**
   * The cliff probe's timed loop, statement for statement in what it reads: null-free 1024-row
   * calls for {@code seconds}, the rate printed once a second and the allocation per call of the
   * last second at the end. Returns the calls' statuses or'd, nonzero if any call declined.
   */
  private static int timed(VarkaFusedKernel kernel, Subject s, int seconds) {
    var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    int outputs = s.roots().size();
    try (var arena = Arena.ofConfined()) {
      var data = arena.allocate(ROWS * 4L, 64);
      for (int i = 0; i < ROWS; i++) {
        data.setAtIndex(ValueLayout.JAVA_INT, i, s.intData()[0][i]);
      }
      long[] dst = new long[outputs];
      long[] dstValidity = new long[outputs];
      for (int o = 0; o < outputs; o++) {
        dst[o] = arena.allocate(ROWS * 4L, 64).address();
        dstValidity[o] = arena.allocate(ROWS / 8L + 8, 64).address();
      }
      long[] src = {data.address()};
      long[] noValidity = {0L};
      int[] noNulls = {0};
      int[] literals = s.intLits();
      long start = System.nanoTime();
      long end = start + seconds * 1_000_000_000L;
      int second = 1;
      long windowStart = start;
      long windowRows = 0L;
      long windowCalls = 0L;
      long windowBytes = threads.getCurrentThreadAllocatedBytes();
      double bytesPerCall = 0.0;
      int status = 0;
      while (System.nanoTime() < end) {
        status |= kernel.run(src, noValidity, noNulls, dst, dstValidity, literals, ROWS);
        windowRows += ROWS;
        windowCalls += 1;
        long now = System.nanoTime();
        if (now - windowStart >= 1_000_000_000L) {
          long allocated = threads.getCurrentThreadAllocatedBytes();
          bytesPerCall = (double) (allocated - windowBytes) / windowCalls;
          System.out.println(RATE_PREFIX + second + " "
              + String.format("%.2f", (double) (now - windowStart) / windowRows));
          second += 1;
          windowStart = now;
          windowRows = 0;
          windowCalls = 0;
          windowBytes = allocated;
        }
      }
      System.out.println(ALLOC_PREFIX + String.format("%.0f", bytesPerCall));
      return status;
    }
  }
}
