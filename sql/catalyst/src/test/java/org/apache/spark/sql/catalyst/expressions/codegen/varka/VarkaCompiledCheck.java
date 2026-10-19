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
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.util.DateTimeUtils$;

/**
 * Checks a drawn kernel's answers in the code users run, not only in the interpreter (VARKA-274).
 *
 * <p>The fuzzers' check ({@link VarkaKernelChecks}) loads a kernel, runs it once and compares
 * every row with {@link VarkaReference}. A method run once is never compiled, so that verdict is
 * the interpreter's. A user's kernel serves batches as C2 code once a warm-up has compiled it, or
 * moves through the interpreter, C1 and C2 while its batches run when the warm-up is off, and on
 * the way it can be compiled at one tier, thrown away by a deoptimization, recompiled, and entered
 * in the middle of a loop by an on-stack replacement (OSR). Validating JIT compilers, Li et al.
 * (SOSP 2023) found nine in ten of their miscompilations only on such mixed histories.
 *
 * <p>So a drawn case is run over eight <b>histories</b>, each on a class of its own, since a
 * history is exactly what ran on the class before its compared batches:
 *
 * <ul>
 *   <li>{@code cold}: the drawn batch on a fresh class, the fuzzers' check as it was;</li>
 *   <li>{@code warm-same}, {@code warm-other}: warmed until every loop and epilogue method is C2
 *       code, on the drawn values or on another draw of the same columns, then the drawn batch;
 *   </li>
 *   <li>{@code quiet-remainder}, {@code quiet-null}, {@code quiet-guard}: warmed on calls that
 *       never leave a remainder past the last whole vector, never hold an all-null column, or
 *       never come near a guard's bound, then a batch that does - the case C2's code was never
 *       profiled for, which it answers by trapping back to the interpreter;</li>
 *   <li>{@code recompiled}: {@code quiet-null}'s trap, then calls until the code is C2 again, then
 *       the trapping batch again;</li>
 *   <li>{@code released}: C1 left on and no warm-up, as for a kernel run with the warm-up off: a
 *       series of large batches, during which the loops are entered by OSR at C1's tier and then
 *       at C2's.</li>
 * </ul>
 *
 * <p>Every compared batch is compared in full whatever served it. Which tier served it is a
 * second fact, read by {@link VarkaTierWitness} around each call: it says whether the history
 * happened, not whether the answer is right. The warmed histories name their class with the
 * warmed mark, so production's compiler directive keeps C1 off them as it does off a user's warmed
 * kernel ({@link VarkaKernelCompileDirective}); the others keep a name the directive does not
 * match. The warm-up is this check's own, on {@link VarkaKernelWarmup}'s call shape - 32 rows
 * plus the batch's remainder a call, the drivers alternating, from a 1,024-row copy - and it stops
 * when the code list shows every loop and epilogue method at level 4. A kernel that has not got
 * there by the deadline is <b>unsettled</b>: its batches are still compared, and it is counted,
 * not failed.
 */
public final class VarkaCompiledCheck {

  private VarkaCompiledCheck() {
  }

  /** The histories, in the order a case runs them. */
  public enum History {
    COLD("cold", false),
    WARM_SAME("warm-same", true),
    WARM_OTHER("warm-other", true),
    QUIET_REMAINDER("quiet-remainder", true),
    QUIET_NULL("quiet-null", true),
    QUIET_GUARD("quiet-guard", true),
    RECOMPILED("recompiled", true),
    RELEASED("released", false);

    public final String label;
    /** Whether the class is warmed under production's directive, C1 kept off it. */
    public final boolean warmed;

    History(String label, boolean warmed) {
      this.label = label;
      this.warmed = warmed;
    }

    /** The history called {@code label}, as {@code -Dvarka.fuzz.history} names one. */
    public static History named(String label) {
      for (History h : values()) {
        if (h.label.equals(label)) {
          return h;
        }
      }
      throw new IllegalArgumentException("no history named " + label + "; the histories are "
          + Arrays.stream(values()).map(h -> h.label).toList());
    }
  }

  /**
   * How a run is sized: the warm-up's deadline, the {@code released} history's batches and rows,
   * whether the tier witness reads each compared call (off for the A/B that prices it; the
   * recording itself stays on, since the warm-up reads it), and the histories to run alone
   * (empty for all eight).
   */
  public record Settings(long settleMillis, int releasedBatches, int releasedRows,
      boolean witnessed, Set<History> only) {

    public Settings {
      only = Set.copyOf(only);
    }

    /**
     * From {@code -Dvarka.fuzz.*}: {@code settleMillis} (default 10,000), {@code releasedBatches}
     * (200), {@code releasedRows} (10,000), {@code witness} (true) and {@code history} (all; one
     * label or several, comma separated, as {@code cold,released}).
     */
    public static Settings fromProperties() {
      String only = System.getProperty("varka.fuzz.history");
      var histories = EnumSet.noneOf(History.class);
      if (only != null) {
        for (String label : only.split(",")) {
          if (!label.isBlank()) {
            histories.add(History.named(label.strip()));
          }
        }
      }
      return new Settings(Long.getLong("varka.fuzz.settleMillis", 10_000L),
          Integer.getInteger("varka.fuzz.releasedBatches", 200),
          Integer.getInteger("varka.fuzz.releasedRows", 10_000),
          !"false".equals(System.getProperty("varka.fuzz.witness")), histories);
    }

    public Settings withSettleMillis(long millis) {
      return new Settings(millis, releasedBatches, releasedRows, witnessed, only);
    }

    public Settings withReleased(int batches, int rows) {
      return new Settings(settleMillis, batches, rows, witnessed, only);
    }

    public Settings withOnly(History history) {
      return new Settings(settleMillis, releasedBatches, releasedRows, witnessed,
          Set.of(history));
    }

    public boolean runs(History h) {
      return only.isEmpty() || only.contains(h);
    }
  }

  /**
   * What one history did on one case: why it was skipped (null when it ran), whether its warm-up
   * settled and how long that took, its wall time, and its compared calls, whose verdicts are read
   * once the witness has settled ({@link #verdicts}).
   */
  public record Run(History history, String skipped, String className, boolean warmed,
      boolean settled, long settleMillis, long millis, List<VarkaTierWitness.Call> calls,
      String unsettled, List<VarkaTierWitness.Decision> decisions) {
    public Run {
      calls = List.copyOf(calls);
      decisions = List.copyOf(decisions);
    }

    static Run skipped(History h, String why) {
      return new Run(h, why, null, false, false, 0L, 0L, List.of(), "", List.of());
    }

    /**
     * Whether every warm-up decision of the run holds with everything the recording delivered:
     * read after the witness has settled. A run whose warm-up stopped on a version the recording
     * later showed to be an on-stack replacement is settled but not confirmed.
     */
    public boolean confirmed() {
      return settled && decisions.stream().allMatch(VarkaTierWitness.get()::confirmed);
    }

    /** Each compared call's verdict, in order; empty when the witness was off. */
    public List<VarkaTierWitness.Verdict> verdicts() {
      return calls.stream().map(VarkaTierWitness.Call::verdict).toList();
    }

    /**
     * The verdicts of the calls through a body with loop and epilogue methods: a kernel whose
     * outputs read no column has none on its masked side, and a call through it has no tier to
     * show, so the counts leave it out.
     */
    public List<VarkaTierWitness.Verdict> countedVerdicts() {
      return calls.stream().filter(k -> !k.methods().isEmpty())
          .map(VarkaTierWitness.Call::verdict).toList();
    }
  }

  /** A case's runs, one per history run, in history order. */
  public record Result(String label, LaneType lane, List<Run> runs) {
    public Result {
      runs = List.copyOf(runs);
    }

    /** The case's wall time over its compiled histories, every one but {@code cold}. */
    public long compiledMillis() {
      return runs.stream().filter(r -> r.history() != History.COLD).mapToLong(Run::millis).sum();
    }
  }

  private static final AtomicInteger CLASSES = new AtomicInteger();

  /** The warmed histories' class names: production's prefix and warmed mark, then a fuzz tag. */
  static final String WARMED_PREFIX =
      VarkaShapeCacheImpl.CLASS_NAME_PREFIX + VarkaShapeCacheImpl.WARMED_MARK + "fuzz";

  /** The other histories' class names, which the directive does not match. */
  static final String PLAIN_PREFIX = "org.apache.spark.sql.varka.execution.VarkaFusedFuzzTier";

  /**
   * Runs {@code c} over every history {@code s} selects. {@code emit} emits the case's kernel
   * under a class name; {@code replay} is the system properties that rerun the case, which a
   * failure's message carries with the history's own. Throws the first wrong answer, its message
   * starting with the case, the history and the batch.
   */
  public static Result run(VarkaFuzzCase c, Function<String, byte[]> emit, Settings s,
      String replay) {
    var runs = new ArrayList<Run>();
    for (History h : History.values()) {
      if (!s.runs(h)) {
        continue;
      }
      try {
        runs.add(new Check(c, emit, s, h).run());
      } catch (VirtualMachineError e) {
        throw e;
      } catch (RuntimeException | Error e) {
        throw new HistoryFailure(h, firstLine(e) + "\n  in history " + h.label
            + "; replay with " + replay + " -Dvarka.fuzz.history=" + h.label, e);
      }
    }
    return new Result(c.label(), c.lane(), runs);
  }

  /**
   * A history's failure: the history, and the check's own failure as the cause, which is what a
   * failure signature classifies (a generated class's linkage error stays one) and what a shrinker
   * holds the smaller cases to.
   */
  public static final class HistoryFailure extends IllegalStateException {
    private final transient History history;

    HistoryFailure(History history, String message, Throwable cause) {
      super(message, cause);
      this.history = history;
    }

    public History history() {
      return history;
    }
  }

  private static String firstLine(Throwable e) {
    return e.getMessage() == null ? e.getClass().getName() : e.getMessage();
  }

  // ---------------------------------------------------------------------------------------------
  // Columns: a batch's values and null flags, at either lane, before they become buffers.
  // ---------------------------------------------------------------------------------------------

  /** A batch as values widened to 64 bits: {@code data[c][i]}, {@code nulls[c][i]}. */
  record Cols(int length, long[][] data, boolean[][] nulls, boolean forceMasked) {

    /** {@code rows} rows taken from this batch's in turn, its null flags with them. */
    Cols cycled(int rows) {
      int n = data.length;
      var d = new long[n][rows];
      var z = new boolean[n][rows];
      for (int c = 0; c < n; c++) {
        for (int i = 0; i < rows; i++) {
          d[c][i] = data[c][i % length];
          z[c][i] = nulls[c][i % length];
        }
      }
      return new Cols(rows, d, z, forceMasked && rows > 1);
    }

    Cols withNulls(boolean[][] z, boolean forced) {
      return new Cols(length, data, z, forced);
    }

    /** Whether the kernel's masked body takes this batch: a null, or a forced null count. */
    boolean masked() {
      if (data.length == 0) {
        return false;
      }
      if (forceMasked) {
        return true;
      }
      for (boolean[] column : nulls) {
        for (boolean z : column) {
          if (z) {
            return true;
          }
        }
      }
      return false;
    }

    VarkaKernelChecks.Batch ints() {
      var d = new int[data.length][];
      for (int c = 0; c < data.length; c++) {
        d[c] = new int[length];
        for (int i = 0; i < length; i++) {
          d[c][i] = (int) data[c][i];
        }
      }
      return new VarkaKernelChecks.Batch(length, nulls, d, forceMasked);
    }

    VarkaKernelChecks.LongBatch longs() {
      return new VarkaKernelChecks.LongBatch(length, nulls, data, forceMasked);
    }
  }

  /** Every row null in no column. */
  private static boolean[][] noNulls(int columns, int rows) {
    return new boolean[columns][rows];
  }

  /**
   * Partial nulls in every column of every slice of at least five rows: row {@code i} of column
   * {@code c} is null where {@code i % 5 == c % 5}, so no slice is all-null (which would take the
   * masked driver's all-null shortcut and run no loop) and none is null-free.
   */
  private static boolean[][] everyFifth(int columns, int rows) {
    var z = new boolean[columns][rows];
    for (int c = 0; c < columns; c++) {
      for (int i = 0; i < rows; i++) {
        z[c][i] = i % 5 == c % 5;
      }
    }
    return z;
  }

  // ---------------------------------------------------------------------------------------------
  // One history on one case.
  // ---------------------------------------------------------------------------------------------

  /** Rows of the warm-up's copy: production's warm-up's own constant. */
  private static final int SNAPSHOT_ROWS = VarkaKernelWarmup.SNAPSHOT_ROWS;

  /** Rows a warm-up call runs before the batch's remainder: production's warm-up's constant. */
  private static final int SLICE_ROWS = VarkaKernelWarmup.SLICE_ROWS;

  /** Calls between two reads of the code list while warming. */
  private static final int CALLS_PER_LOOK = 256;

  /** The quiet-remainder batch's rows: odd, so it leaves a remainder at every lane count. */
  private static final int REMAINDER_ROWS = 1007;

  /** The guard edge batch's rows: a full vector lane at row 0 and the epilogue's at row 16. */
  private static final int EDGE_ROWS = 17;

  private static final class Check {
    private final VarkaFuzzCase c;
    private final Function<String, byte[]> emit;
    private final Settings s;
    private final History h;
    private final boolean isLong;
    private final int numInputs;
    private final Cols drawn;
    private final List<VarkaTierWitness.Call> calls = new ArrayList<>();
    private final VarkaTierWitness witness;
    private String className;
    private VarkaFusedKernel kernel;
    private boolean settled = true;
    private long settleMillis;
    private boolean everWarmed;
    private String unsettledLevels = "";
    private final List<VarkaTierWitness.Decision> decisions = new ArrayList<>();

    Check(VarkaFuzzCase c, Function<String, byte[]> emit, Settings s, History h) {
      this.c = c;
      this.emit = emit;
      this.s = s;
      this.h = h;
      this.isLong = c.lane() == LaneType.LONG;
      this.numInputs = c.numInputs();
      this.drawn = new Cols(c.length(), c.data(), c.nulls(), c.forceMasked());
      this.witness = s.witnessed() ? VarkaTierWitness.get() : null;
    }

    Run run() {
      if (h.warmed && !VarkaKernelCompileDirective.readyForWarmup()) {
        return Run.skipped(h, "this JVM cannot warm a kernel to C2");
      }
      Optional<Edges> edges = Optional.empty();
      if (h == History.QUIET_GUARD) {
        edges = guardEdges();
        if (edges.isEmpty()) {
          return Run.skipped(h, "no guard whose bound a column reaches");
        }
      }
      if ((h == History.QUIET_NULL || h == History.RECOMPILED) && numInputs == 0) {
        return Run.skipped(h, "no input to null");
      }
      long start = System.nanoTime();
      className = (h.warmed ? WARMED_PREFIX : PLAIN_PREFIX) + CLASSES.incrementAndGet();
      byte[] bytes = emit.apply(className);
      VarkaTierWitness.get().watch(className);
      var loader = new VarkaGeneratedClassLoader(VarkaCompiledCheck.class.getClassLoader());
      kernel = VarkaKernelChecks.load(loader, className, bytes);
      try {
        switch (h) {
          case COLD -> compareBoth("the drawn batch", drawn);
          case WARM_SAME -> {
            warm(drawn.cycled(SNAPSHOT_ROWS), ownNulls(), sliceRows(), allMethods());
            compareBoth("the drawn batch", drawn);
          }
          case WARM_OTHER -> {
            var other = otherDraw();
            warm(other, other.nulls(), sliceRows(), allMethods());
            compareBoth("the drawn batch", drawn);
          }
          case QUIET_REMAINDER -> {
            warm(drawn.cycled(SNAPSHOT_ROWS), everyFifth(numInputs, SNAPSHOT_ROWS), SLICE_ROWS,
                allMethods());
            // A remainder-free batch like the warm-up's calls first, then the one with a
            // remainder: a trap is then seen to fall in its own call's window.
            compare("a batch of " + SLICE_ROWS + " rows", drawn.cycled(SLICE_ROWS), false);
            compareBoth("a batch of " + REMAINDER_ROWS + " rows", drawn.cycled(REMAINDER_ROWS));
          }
          case QUIET_NULL -> {
            warm(drawn.cycled(SNAPSHOT_ROWS), everyFifth(numInputs, SNAPSHOT_ROWS), sliceRows(),
                allMethods());
            // First a batch shaped like the warm-up's calls, which should not trap, then the
            // one that should: a trap is then seen to fall in its own call's window.
            Cols quiet = drawn.cycled(sliceRows());
            compare("a batch like the warm-up's", quiet.withNulls(
                everyFifth(numInputs, quiet.length()), true), false);
            compareBoth("the drawn batch with column 0 all-null", firstAllNull());
          }
          case QUIET_GUARD -> {
            warm(drawn.cycled(SNAPSHOT_ROWS), everyFifth(numInputs, SNAPSHOT_ROWS), sliceRows(),
                allMethods());
            edges.get().run(this);
          }
          case RECOMPILED -> recompiled();
          case RELEASED -> released();
        }
      } finally {
        loader.release();
      }
      long millis = (System.nanoTime() - start) / 1_000_000L;
      return new Run(h, null, className, everWarmed, settled, settleMillis, millis, calls,
          unsettledLevels, decisions);
    }

    /** Rows per warm-up call: 32 and the drawn batch's remainder past them. */
    private int sliceRows() {
      return SLICE_ROWS + c.length() % SLICE_ROWS;
    }

    /** The warm-up's masked calls on the drawn null flags, where they are partial in a slice. */
    private boolean[][] ownNulls() {
      boolean[][] own = drawn.cycled(SNAPSHOT_ROWS).nulls();
      boolean[][] fallback = everyFifth(numInputs, SNAPSHOT_ROWS);
      int rows = sliceRows();
      for (int col = 0; col < numInputs; col++) {
        for (int from = 0; from + rows <= SNAPSHOT_ROWS; from += rows) {
          boolean all = true;
          for (int i = from; i < from + rows; i++) {
            all &= own[col][i];
          }
          if (all) {
            System.arraycopy(fallback[col], from, own[col], from, rows);
          }
        }
      }
      return own;
    }

    /**
     * Another draw of the case's columns, each within the bound the fuzzer draws it in, with one
     * row in three null: what {@code warm-other} warms on.
     */
    private Cols otherDraw() {
      var rnd = new Random(c.label().hashCode() * 31L + 0x57415243L);
      var d = new long[numInputs][SNAPSHOT_ROWS];
      var z = new boolean[numInputs][SNAPSHOT_ROWS];
      for (int col = 0; col < numInputs; col++) {
        for (int i = 0; i < SNAPSHOT_ROWS; i++) {
          d[col][i] = drawValue(rnd, col);
          z[col][i] = i % 5 != 4 && rnd.nextInt(3) == 0;
        }
      }
      return new Cols(SNAPSHOT_ROWS, d, z, false);
    }

    /** One value of column {@code col}, drawn as the fuzzer draws that column. */
    private long drawValue(Random rnd, int col) {
      if (isLong) {
        return VarkaIrGrammar.within(rnd, VarkaIrGrammar.LONG_COLUMN_BOUND);
      }
      if (col == c.levelOrdinal()) {
        // Exactly the codes a truncation level takes; see VarkaIrFuzzSuite's draw.
        int week = DateTimeUtils$.MODULE$.TRUNC_TO_WEEK();
        return week + rnd.nextInt(DateTimeUtils$.MODULE$.TRUNC_TO_YEAR() - week + 1);
      }
      return VarkaIrGrammar.within(rnd, columnBound(col));
    }

    /** The bound column {@code col}'s values are drawn within, plus or minus. */
    private long columnBound(int col) {
      if (isLong) {
        return VarkaIrGrammar.LONG_COLUMN_BOUND;
      }
      return col == c.smallOrdinal() ? VarkaChrono.MONTH_ARITH_MAX_MONTHS
          : VarkaIrGrammar.COLUMN_BOUND;
    }

    /** The drawn batch with every row of column 0 null. */
    private Cols firstAllNull() {
      var z = new boolean[numInputs][];
      for (int col = 0; col < numInputs; col++) {
        z[col] = col == 0 ? filled(c.length()) : c.nulls()[col].clone();
      }
      return drawn.withNulls(z, false);
    }

    private static boolean[] filled(int rows) {
      var out = new boolean[rows];
      Arrays.fill(out, true);
      return out;
    }

    // -------------------------------------------------------------------------------------------
    // The kernel's methods.
    // -------------------------------------------------------------------------------------------

    /** The loop and epilogue methods of one side, read off the loaded class. */
    private TreeSet<String> groupMethods(boolean dense) {
      var out = new TreeSet<String>();
      for (Method m : kernel.getClass().getDeclaredMethods()) {
        String name = m.getName();
        if (VarkaMethodNames.isLoop(name, dense) || VarkaMethodNames.isEpilogue(name, dense)) {
          out.add(name);
        }
      }
      return out;
    }

    /** Both sides' loop and epilogue methods, or the dense side's for a kernel with no input. */
    private TreeSet<String> allMethods() {
      var out = groupMethods(true);
      if (numInputs > 0) {
        out.addAll(groupMethods(false));
      }
      return out;
    }

    // -------------------------------------------------------------------------------------------
    // Compared calls.
    // -------------------------------------------------------------------------------------------

    /**
     * One batch through the body it selects, compared row by row: the status, 0 once compared.
     * The witness reads the code list and opens its window around the kernel's call alone.
     */
    private int compare(String what, Cols b, boolean declineAllowed) {
      String context = c.label() + " history=" + h.label + " batch=" + what;
      VarkaKernelChecks.CallHook hook = VarkaKernelChecks.CallHook.NONE;
      if (witness != null) {
        var call = witness.call(className, groupMethods(!b.masked() || numInputs == 0));
        calls.add(call);
        hook = call;
      }
      int[] intLits = new int[c.lits().length];
      for (int k = 0; k < intLits.length; k++) {
        intLits[k] = (int) c.lits()[k];
      }
      return isLong
          ? VarkaKernelChecks.runLoadedLong(context, kernel, c.roots(), numInputs, c.lits(),
              b.longs(), declineAllowed, hook)
          : VarkaKernelChecks.runLoaded(context, kernel, c.roots(), numInputs, intLits, b.ints(),
              declineAllowed, hook);
    }

    /** {@link #compare} through the batch's own body, then through the other one. */
    private List<Integer> compareBoth(String what, Cols b, boolean declineAllowed) {
      var statuses = new ArrayList<Integer>();
      statuses.add(compare(what, b, declineAllowed));
      VarkaKernelChecks.otherBody(numInputs, b.length(), b.nulls(), b.forceMasked())
          .ifPresent(o -> statuses.add(compare(what + " (the other body)",
              b.withNulls(o.nulls(), o.forceMasked()), declineAllowed)));
      return statuses;
    }

    private void compareBoth(String what, Cols b) {
      compareBoth(what, b, false);
    }

    // -------------------------------------------------------------------------------------------
    // The warm-up.
    // -------------------------------------------------------------------------------------------

    /**
     * Calls the kernel on slices of {@code snapshot} until the code list shows every one of
     * {@code required} as C2 code in use, or the deadline passes. Each slice is called once
     * through the dense driver, with no nulls, and once through the masked one, with
     * {@code maskedNulls}' flags; null lanes hold the harness's poison. The buffers are built once,
     * and each call runs in the memory sanitizer's window, as a compared call does
     * ({@code Args.call}). Nothing is compared: what the warm-up computes is thrown away.
     */
    private void warm(Cols snapshot, boolean[][] maskedNulls, int rows, TreeSet<String> required) {
      everWarmed = true;
      VarkaTierWitness.get().touched(className);
      long start = System.nanoTime();
      long deadline = start + s.settleMillis() * 1_000_000L;
      try (var arena = Arena.ofConfined()) {
        var sets = new ArrayList<Args>();
        for (int from = 0; from + rows <= snapshot.length(); from += rows) {
          sets.add(args(arena, snapshot, noNulls(numInputs, snapshot.length()), from, rows,
              false));
          if (numInputs > 0) {
            sets.add(args(arena, snapshot, maskedNulls, from, rows, true));
          }
        }
        VarkaTierWitness.Decision last = null;
        for (long k = 0; last == null || !last.c2() && System.nanoTime() <= deadline; k++) {
          sets.get((int) (k % sets.size())).call();
          if (k % CALLS_PER_LOOK == CALLS_PER_LOOK - 1) {
            last = VarkaTierWitness.get().decideNow(className, required);
          }
        }
        decisions.add(last);
      }
      boolean ok = decisions.getLast().c2();
      if (!ok && unsettledLevels.isEmpty()) {
        unsettledLevels = VarkaTierWitness.get().levelsNow(className, required).toString();
      }
      settled &= ok;
      settleMillis += (System.nanoTime() - start) / 1_000_000L;
    }

    /**
     * One warm-up call's arguments over rows {@code from} to {@code from + rows} of
     * {@code snapshot}, with {@code nulls}' flags. For a {@code masked} call a column with no null
     * in the slice gets a forced null count over a full bitmap, as the harness forces the masked
     * body, so the masked driver runs whatever the flags.
     */
    private Args args(Arena arena, Cols snapshot, boolean[][] nulls, int from, int rows,
        boolean masked) {
      long stride = isLong ? 8L : 4L;
      long words = ((rows + 63) / 64) * 8L;
      var srcData = new long[numInputs];
      var srcValidity = new long[numInputs];
      var nullCounts = new int[numInputs];
      long boundColumns = isLong ? 0L : VarkaKernelChecks.columnBoundColumns(c.roots());
      for (int col = 0; col < numInputs; col++) {
        var d = VarkaEmitterFixtures.alloc(arena, rows * stride);
        var v = VarkaEmitterFixtures.alloc(arena, words);
        v.fill((byte) 0);
        int count = 0;
        for (int i = 0; i < rows; i++) {
          boolean z = nulls[col][from + i];
          long value = z ? poison((boundColumns >>> col & 1L) != 0, count)
              : snapshot.data()[col][from + i];
          if (isLong) {
            d.set(ValueLayout.JAVA_LONG, i * 8L, value);
          } else {
            d.set(ValueLayout.JAVA_INT, i * 4L, (int) value);
          }
          if (z) {
            count++;
          } else {
            long off = i / 8L;
            v.set(ValueLayout.JAVA_BYTE, off,
                (byte) (v.get(ValueLayout.JAVA_BYTE, off) | (1 << (i % 8))));
          }
        }
        srcData[col] = d.address();
        nullCounts[col] = count;
        srcValidity[col] = count == 0 || count == rows ? 0L : v.address();
      }
      if (masked) {
        for (int col = 0; col < numInputs; col++) {
          if (nullCounts[col] == 0) {
            var v = VarkaEmitterFixtures.alloc(arena, words);
            v.fill((byte) 0xFF);
            nullCounts[col] = 1;
            srcValidity[col] = v.address();
          }
        }
      }
      int n = c.roots().size();
      var outData = new long[n];
      var outValidity = new long[n];
      for (int o = 0; o < n; o++) {
        outData[o] = c.roots().get(o) instanceof Cond ? 0L
            : VarkaEmitterFixtures.alloc(arena, rows * stride).address();
        outValidity[o] = VarkaEmitterFixtures.alloc(arena, words).address();
      }
      int[] intLits = new int[c.lits().length];
      for (int k = 0; k < intLits.length; k++) {
        intLits[k] = (int) c.lits()[k];
      }
      return new Args(kernel, isLong, srcData, srcValidity, nullCounts, outData, outValidity,
          intLits, c.lits(), rows);
    }

    /**
     * The harness's poison for the {@code k}-th null of a column, at the case's lane: the column
     * bound's ends for a column the kernel reads through it ({@code bounded}), as
     * {@code VarkaKernelChecks.storedUnderNull} says why.
     */
    private long poison(boolean bounded, int k) {
      if (isLong) {
        return (k & 1) == 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
      }
      return bounded ? VarkaKernelChecks.storedUnderNull(k) : VarkaEmitterFixtures.poison(k);
    }

    // -------------------------------------------------------------------------------------------
    // recompiled and released.
    // -------------------------------------------------------------------------------------------

    /**
     * {@code quiet-null}'s warm-up and all-null batch; then, if that threw code away, calls that
     * mix all-null slices into the warm-up until the loops and epilogues, and every method whose
     * code was thrown away, are C2 code again, and the all-null batch once more. Thrown away is
     * read off the code list, over every method of the class: the all-null trap is usually in the
     * masked driver's shortcut, not in a loop. A kernel the batch did not trap has nothing to
     * recover from, and its run shows only the first batch's calls.
     */
    private void recompiled() {
      var required = allMethods();
      var snapshot = drawn.cycled(SNAPSHOT_ROWS);
      warm(snapshot, everyFifth(numInputs, SNAPSHOT_ROWS), sliceRows(), required);
      var before = VarkaTierWitness.parse(VarkaTierWitness.readCodeList(), className);
      Cols trapping = firstAllNull();
      compareBoth("the drawn batch with column 0 all-null", trapping);
      var inUse = new java.util.HashSet<Integer>();
      for (var e : VarkaTierWitness.parse(VarkaTierWitness.readCodeList(), className)) {
        if (e.inUse()) {
          inUse.add(e.compileId());
        }
      }
      boolean threw = false;
      for (var e : before) {
        if (e.inUse() && !inUse.contains(e.compileId())) {
          required.add(e.method());
          threw = true;
        }
      }
      if (threw) {
        var mixed = everyFifth(numInputs, SNAPSHOT_ROWS);
        int rows = sliceRows();
        for (int from = 0; from + rows <= SNAPSHOT_ROWS; from += 2 * rows) {
          Arrays.fill(mixed[0], from, from + rows, true);
        }
        warm(snapshot, mixed, rows, required);
        compareBoth("the drawn batch with column 0 all-null, after the recompile", trapping);
      }
    }

    /**
     * No warm-up and C1 on: {@code releasedBatches} batches of {@code releasedRows} rows, the
     * drawn rows in turn, each compared through its own body. What the JIT does on the way is the
     * history: the interpreter, an OSR compile at C1's profiling tier, C1's code, an OSR compile
     * at C2's, C2's code.
     */
    private void released() {
      Cols batch = drawn.cycled(s.releasedRows());
      for (int k = 0; k < s.releasedBatches(); k++) {
        compare("released batch " + k, batch, false);
      }
    }

    // -------------------------------------------------------------------------------------------
    // quiet-guard.
    // -------------------------------------------------------------------------------------------

    /**
     * A guard's edge reached through one column: on the drawn first row, column {@code column}
     * at {@code answer} must be answered and at {@code decline} must be declined (or, by an
     * unqualified guard under an untaken arm, may be).
     */
    private Optional<Edges> guardEdges() {
      var guards = new VarkaGuardSpecs.Guards(c.roots(), numInputs, c.lits().length, c.options(),
          isLong);
      if (guards.guards().isEmpty() || c.length() == 0) {
        return Optional.empty();
      }
      long[] template = new long[numInputs];
      for (int col = 0; col < numInputs; col++) {
        template[col] = c.data()[col][0];
      }
      if (!verdict(guards, template).answers()) {
        return Optional.empty();
      }
      for (int col = 0; col < numInputs; col++) {
        if (!isLong && col == c.levelOrdinal()) {
          continue;
        }
        long bound = columnBound(col);
        for (long dir : new long[] {1L, -1L}) {
          long inside = dir * bound;
          long[] row = template.clone();
          row[col] = inside;
          long answer;
          long decline;
          if (!verdict(guards, row).answers()) {
            // The guard's bound is inside the drawn range: bisect for the last answered value.
            long lo = template[col];
            long hi = inside;
            while (Math.abs(hi - lo) > 1) {
              long mid = lo + (hi - lo) / 2;
              row[col] = mid;
              if (verdict(guards, row).answers()) {
                lo = mid;
              } else {
                hi = mid;
              }
            }
            answer = lo;
            decline = hi;
          } else {
            // At the drawn range's end: one past it, the decline side alone leaving the range.
            row[col] = inside + dir;
            if (verdict(guards, row).answers()) {
              continue;
            }
            answer = inside;
            decline = inside + dir;
          }
          row[col] = decline;
          return Optional.of(new Edges(guards, template, col, answer, decline,
              verdict(guards, row)));
        }
      }
      return Optional.empty();
    }

    private VarkaGuardSpecs.Verdict verdict(VarkaGuardSpecs.Guards guards, long[] row) {
      return VarkaGuardSpecs.verdict(guards, c.lits(), new long[][] {row},
          new boolean[][] {new boolean[numInputs]}, List.of());
    }

    /** A batch of {@link #EDGE_ROWS} rows of {@code template}, {@code column} at {@code value}
     * on the first and last rows. */
    private Cols edgeBatch(long[] template, int column, long value) {
      var d = new long[numInputs][EDGE_ROWS];
      for (int col = 0; col < numInputs; col++) {
        Arrays.fill(d[col], template[col]);
      }
      d[column][0] = value;
      d[column][EDGE_ROWS - 1] = value;
      return new Cols(EDGE_ROWS, d, noNulls(numInputs, EDGE_ROWS), false);
    }

    /** What quiet-guard compares after its warm-up: the answer side, then the decline side. */
    private record Edges(VarkaGuardSpecs.Guards guards, long[] template, int column, long answer,
        long decline, VarkaGuardSpecs.Verdict declineVerdict) {

      void run(Check k) {
        String at = "column " + column + " at ";
        k.compareBoth(at + answer + ", inside its guard", k.edgeBatch(template, column, answer));
        String what = at + decline + ", past its guard";
        for (int status : k.compareBoth(what, k.edgeBatch(template, column, decline), true)) {
          boolean exact = k.c.options().exactRefusalStatus();
          if (declineVerdict.must() != 0 && status == 0
              || exact && !declineVerdict.admits(status)) {
            throw new IllegalStateException(k.c.label() + " history=" + k.h.label + " batch="
                + what + ": status " + status + " where the guards' verdict is "
                + declineVerdict);
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The counts a run reports.
  // ---------------------------------------------------------------------------------------------

  /**
   * What a run of many cases came to, per lane and history, read once the witness has settled:
   * the runs and skips, the warm-ups that settled and their times, how many cases' compared calls
   * were all C2 code, trapped, or were entered by an on-stack replacement, the unsettled cases,
   * and each case's time over its compiled histories.
   */
  public static final class Tally {
    private final List<Result> results = new ArrayList<>();
    private int coldOnly;

    public void add(Result r) {
      results.add(r);
    }

    /** A case run {@code cold} only: its options put a second vector species in the JVM. */
    public void addColdOnly() {
      coldOnly++;
    }

    public List<Result> results() {
      return List.copyOf(results);
    }

    /** The runs of one lane and history, skipped ones included. */
    public List<Run> runs(LaneType lane, History h) {
      var out = new ArrayList<Run>();
      for (Result r : results) {
        if (r.lane() == lane) {
          for (Run run : r.runs()) {
            if (run.history() == h) {
              out.add(run);
            }
          }
        }
      }
      return out;
    }

    /** Whether every counted call of {@code run} has a verdict labelled {@code label}. */
    public static boolean all(Run run, String label) {
      var verdicts = run.countedVerdicts();
      return !verdicts.isEmpty() && verdicts.stream().allMatch(v -> v.label().equals(label));
    }

    /** Whether some counted call of {@code run} has a verdict labelled {@code label}. */
    public static boolean any(Run run, String label) {
      return run.countedVerdicts().stream().anyMatch(v -> v.label().equals(label));
    }

    /** The first compared call of {@code run} with a verdict labelled {@code label}, or -1. */
    public static int first(Run run, String label) {
      var verdicts = run.verdicts();
      for (int k = 0; k < verdicts.size(); k++) {
        if (verdicts.get(k).label().equals(label)) {
          return k;
        }
      }
      return -1;
    }

    /**
     * What a run's counted calls came to, as the report counts cases: {@code all C2},
     * {@code trapped}, {@code osr4}, {@code osr3}, {@code all mixed} or {@code some mixed}, the
     * first that holds. Read after the witness has settled.
     */
    public static String kind(Run run) {
      return all(run, "C2") ? "all C2" : any(run, "trapped") ? "trapped"
          : any(run, "osr4") ? "osr4" : any(run, "osr3") ? "osr3"
          : all(run, "mixed") ? "all mixed" : "some mixed";
    }

    private static String percentiles(List<Long> values) {
      if (values.isEmpty()) {
        return "-";
      }
      var sorted = new ArrayList<>(values);
      sorted.sort(null);
      return "median " + sorted.get(sorted.size() / 2) + ", p90 "
          + sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.9) - 1))
          + ", max " + sorted.get(sorted.size() - 1);
    }

    /** The settle times of one lane's settled warm-ups, every warmed history's, in ms. */
    public List<Long> settleTimes(LaneType lane) {
      var out = new ArrayList<Long>();
      for (History h : History.values()) {
        for (Run run : runs(lane, h)) {
          if (run.skipped() == null && run.warmed() && run.settled()) {
            out.add(run.settleMillis());
          }
        }
      }
      return out;
    }

    /** The report, a line per lane and history and a few after them. */
    public List<String> report(boolean witnessed) {
      var lines = new ArrayList<String>();
      for (LaneType lane : List.of(LaneType.INT, LaneType.LONG)) {
        String name = lane.toString().toLowerCase(java.util.Locale.ROOT);
        var compiled = new ArrayList<Long>();
        for (Result r : results) {
          if (r.lane() == lane && r.runs().size() > 1) {
            compiled.add(r.compiledMillis());
          }
        }
        if (compiled.isEmpty() && runs(lane, History.COLD).isEmpty()) {
          continue;
        }
        lines.add(name + " lane: " + compiled.size() + " cases over the compiled histories, "
            + "ms a case " + percentiles(compiled) + "; warm-ups settled in ms "
            + percentiles(settleTimes(lane)));
        for (History h : History.values()) {
          var runs = runs(lane, h);
          if (runs.isEmpty()) {
            continue;
          }
          var ran = runs.stream().filter(r -> r.skipped() == null).toList();
          var line = new StringBuilder("  " + h.label + ": " + ran.size() + " run");
          if (ran.size() < runs.size()) {
            var why = new java.util.TreeMap<String, Integer>();
            runs.stream().filter(r -> r.skipped() != null)
                .forEach(r -> why.merge(r.skipped(), 1, Integer::sum));
            line.append(", skipped ").append(why);
          }
          if (h.warmed) {
            long settled = ran.stream().filter(Run::settled).count();
            line.append(", settled ").append(settled).append('/').append(ran.size());
            if (witnessed) {
              line.append(" (confirmed ").append(ran.stream().filter(Run::confirmed).count())
                  .append(')');
            }
          }
          line.append(", ms ").append(percentiles(ran.stream().map(Run::millis).toList()));
          if (witnessed) {
            var counts = new java.util.TreeMap<String, Integer>();
            for (Run run : ran) {
              counts.merge(kind(run), 1, Integer::sum);
            }
            line.append("; cases ").append(counts);
            if (h == History.RECOMPILED) {
              line.append("; trapped and then C2 on the last batch ").append(ran.stream()
                  .filter(r -> any(r, "trapped") && !r.countedVerdicts().isEmpty()
                      && r.countedVerdicts().getLast().label().equals("C2"))
                  .count());
            }
            if (h == History.RELEASED) {
              line.append("; first osr3 at batch ").append(percentiles(firsts(ran, "osr3")))
                  .append(", first osr4 at batch ").append(percentiles(firsts(ran, "osr4")))
                  .append(", cases with osr4 ")
                  .append(ran.stream().filter(r -> any(r, "osr4")).count());

            }
          }
          lines.add(line.toString());
        }
      }
      if (witnessed) {
        lines.add(VarkaTierWitness.get().inlinedTraps() + " trap(s) taken inside code C2 inlined "
            + "from another class, found through the compile they threw away: "
            + VarkaTierWitness.get().inlinedTrapExamples());
      }
      if (coldOnly > 0) {
        lines.add(coldOnly + " case(s) with a lanes override, run cold only after the others");
      }
      for (Result r : results) {
        for (Run run : r.runs()) {
          if (run.skipped() == null && run.warmed() && !run.settled()) {
            lines.add("unsettled: " + run.history().label + " " + r.label()
                + (witnessed ? " traps " + VarkaTierWitness.get().trapReasons(run.className())
                    + " levels " + run.unsettled() : ""));
          }
        }
      }
      return lines;
    }

    private static List<Long> firsts(List<Run> runs, String label) {
      var out = new ArrayList<Long>();
      for (Run run : runs) {
        int k = first(run, label);
        if (k >= 0) {
          out.add((long) k);
        }
      }
      return out;
    }
  }

  /**
   * The histories a run shows did not happen under the default JVM, each as a message: a
   * {@code cold} call that was not interpreted at both edges, a lane whose settled
   * {@code warm-same} or {@code warm-other} runs never once showed every call as C2 code, and a
   * lane whose {@code released} runs never showed an on-stack replacement. A history not reached
   * is a fact about the JIT on this machine and its load, never a wrong answer, so the fuzz suite
   * counts and prints these, and fails on them only when asked to
   * ({@code -Dvarka.fuzz.requireTiers=true}, for a quiet machine); the message carries the code
   * list to read it by. {@link #notReached(Tally, Tier)} is the same under another tier.
   */
  public static List<String> notReached(Tally tally) {
    return notReached(tally, Tier.C2);
  }

  // ---------------------------------------------------------------------------------------------
  // The tier a forked JVM claims (VARKA-272).
  // ---------------------------------------------------------------------------------------------

  /**
   * The tier of HotSpot a run says serves its kernels, so that a JVM forked under other flags
   * (VARKA-272's arms, {@code dev/varka_platform.sh}) is held to its own facts and not the
   * default JVM's: {@code -Dvarka.fuzz.tier}, {@link #C2} unless set. {@link VarkaPlatformFacts}
   * holds the JVM's flags to the claim before a run, and {@link #notReached(Tally, Tier)} gives
   * each tier the histories it must show.
   */
  public enum Tier {
    /** {@code -Xint}: no compiler runs, and every call is interpreted. */
    INTERPRETER("interpreter"),
    /** {@code -XX:TieredStopAtLevel=1}: C1 alone, so a kernel is interpreted until C1 takes it. */
    C1("c1"),
    /** The default JVM: the interpreter, C1 and C2 tiered, a warm-up ending in C2 code. */
    C2("c2"),
    /** {@code -Xcomp}: every method compiled before its first call, and no interpreter. */
    XCOMP("xcomp");

    public final String label;

    Tier(String label) {
      this.label = label;
    }

    /** The tier called {@code label}, as {@code -Dvarka.fuzz.tier} names one. */
    public static Tier named(String label) {
      for (Tier t : values()) {
        if (t.label.equals(label)) {
          return t;
        }
      }
      throw new IllegalArgumentException("no tier named " + label + "; the tiers are "
          + Arrays.stream(values()).map(t -> t.label).toList());
    }

    /** {@code -Dvarka.fuzz.tier}, or {@link #C2}. */
    public static Tier fromProperty() {
      String label = System.getProperty("varka.fuzz.tier");
      return label == null ? C2 : named(label);
    }
  }

  /**
   * What one history's run showed, as the tier rules read it: its verdicts (every compared call,
   * and {@code counted}, the calls through a body with loop and epilogue methods), whether its
   * warm-up settled and was confirmed, whether any method of its class was compiled at all, and
   * the code list before its first call, for a message. The seam the rules are tested through
   * with verdicts written by hand.
   */
  public record Observed(LaneType lane, History history, String className, boolean confirmed,
      List<VarkaTierWitness.Verdict> verdicts, List<VarkaTierWitness.Verdict> counted,
      boolean compiled, String codeBefore) {
    public Observed {
      verdicts = List.copyOf(verdicts);
      counted = List.copyOf(counted);
    }

    /** {@code run} as the witness saw it, its class's compiles asked of the witness. */
    static Observed of(LaneType lane, Run run) {
      var witness = VarkaTierWitness.get();
      return new Observed(lane, run.history(), run.className(), run.confirmed(), run.verdicts(),
          run.countedVerdicts(), !witness.compiles(run.className(), run.calls()).isEmpty(),
          run.calls().isEmpty() ? "[]" : run.calls().get(0).codeBefore().toString());
    }
  }

  /**
   * The histories {@code tally} shows did not happen under {@code tier}; see
   * {@link #notReached(Tally)}.
   */
  public static List<String> notReached(Tally tally, Tier tier) {
    var observed = new ArrayList<Observed>();
    for (LaneType lane : List.of(LaneType.INT, LaneType.LONG)) {
      for (Result r : tally.results()) {
        if (r.lane() != lane) {
          continue;
        }
        for (Run run : r.runs()) {
          if (run.skipped() == null) {
            observed.add(Observed.of(lane, run));
          }
        }
      }
    }
    return notReached(tier, observed);
  }

  /**
   * Each tier's rules over what the runs showed, a message per history that did not happen:
   * <ul>
   *   <li>{@code c2}: the rules of {@link #notReached(Tally)};</li>
   *   <li>{@code interpreter}: every verdict interpreted at both edges, and no method of any
   *       kernel class compiled;</li>
   *   <li>{@code c1}: {@code cold} interpreted; no call anywhere served by level-4 code (a
   *       {@code C2} verdict, an OSR at level 4, a method at level 4 at either edge); and some
   *       {@code released} call of each lane served by C1's code, an OSR at level 1 to 3 or every
   *       method at level 1 to 3 at both edges;</li>
   *   <li>{@code xcomp}: no counted verdict interpreted at both edges, since nothing is; and
   *       {@code warm-same} and {@code warm-other} all C2 in some confirmed case. No OSR rule: a
   *       method compiled before its first call is never entered by one, and a {@code cold} call
   *       is compiled code that traps on a class the compile found unloaded.</li>
   * </ul>
   */
  static List<String> notReached(Tier tier, List<Observed> observed) {
    var out = new ArrayList<String>();
    switch (tier) {
      case C2 -> {
        coldInterpreted(observed, out);
        warmUpsC2(observed, out);
        for (LaneType lane : List.of(LaneType.INT, LaneType.LONG)) {
          var released = of(observed, lane, History.RELEASED);
          if (!released.isEmpty() && released.stream().noneMatch(o -> o.verdicts().stream()
              .anyMatch(v -> v instanceof VarkaTierWitness.Osr osr && osr.level() >= 3))) {
            out.add("history not reached: released, no case of the " + lane + " lane was entered "
                + "by an on-stack replacement in " + released.get(0).verdicts().size()
                + " batches");
          }
        }
      }
      case INTERPRETER -> {
        for (Observed o : observed) {
          for (var v : o.verdicts()) {
            if (!interpreted(v)) {
              out.add("history not reached: " + o.history().label + ", a call on "
                  + o.className() + " was not interpreted under the interpreter alone: " + v);
            }
          }
          if (o.compiled()) {
            out.add("history not reached: " + o.history().label + ", a method of "
                + o.className() + " was compiled under the interpreter alone");
          }
        }
      }
      case C1 -> {
        coldInterpreted(observed, out);
        for (Observed o : observed) {
          for (var v : o.verdicts()) {
            if (level4(v)) {
              out.add("history not reached: " + o.history().label + ", a call on "
                  + o.className() + " was served by level-4 code under C1 alone: " + v);
            }
          }
        }
        for (LaneType lane : List.of(LaneType.INT, LaneType.LONG)) {
          var released = of(observed, lane, History.RELEASED);
          if (!released.isEmpty() && released.stream()
              .noneMatch(o -> o.counted().stream().anyMatch(VarkaCompiledCheck::servedByC1))) {
            out.add("history not reached: released, no call of the " + lane + " lane was served "
                + "by C1's code in " + released.get(0).verdicts().size() + " batches; first "
                + "case's verdicts " + released.get(0).verdicts());
          }
        }
      }
      case XCOMP -> {
        for (Observed o : observed) {
          for (var v : o.counted()) {
            if (interpreted(v)) {
              out.add("history not reached: " + o.history().label + ", a call on "
                  + o.className() + " was interpreted at both edges under -Xcomp: " + v);
            }
          }
        }
        warmUpsC2(observed, out);
      }
    }
    return out;
  }

  private static List<Observed> of(List<Observed> observed, LaneType lane, History h) {
    return observed.stream().filter(o -> o.lane() == lane && o.history() == h).toList();
  }

  /** The default rule on {@code cold}: every call interpreted at both edges. */
  private static void coldInterpreted(List<Observed> observed, List<String> out) {
    for (Observed o : observed) {
      if (o.history() == History.COLD) {
        for (var v : o.verdicts()) {
          if (!interpreted(v)) {
            out.add("history not reached: cold, a call on " + o.className()
                + " was not interpreted: " + v);
          }
        }
      }
    }
  }

  /**
   * The default rule on the warm-ups: some confirmed case of each lane all C2. A lane whose
   * warm-ups ran and never once settled has not reached the history either (VARKA-272: with the
   * Vector API's intrinsics off no int-lane warm-up settles, and the rule passed for want of a
   * settled case).
   */
  private static void warmUpsC2(List<Observed> observed, List<String> out) {
    for (LaneType lane : List.of(LaneType.INT, LaneType.LONG)) {
      for (History h : List.of(History.WARM_SAME, History.WARM_OTHER)) {
        var ran = of(observed, lane, h);
        var settled = ran.stream().filter(Observed::confirmed).toList();
        if (!ran.isEmpty() && settled.isEmpty()) {
          out.add("history not reached: " + h.label + ", no case of the " + lane
              + " lane settled its warm-up; first case's verdicts " + ran.get(0).verdicts());
        } else if (!settled.isEmpty() && settled.stream().noneMatch(o -> allC2(o.counted()))) {
          var sample = settled.get(0);
          out.add("history not reached: " + h.label + ", no settled case of the " + lane
              + " lane ran every compared call as C2 code; first case's verdicts "
              + sample.verdicts() + ", its code list before the first call "
              + sample.codeBefore());
        }
      }
    }
  }

  private static boolean interpreted(VarkaTierWitness.Verdict v) {
    return v instanceof VarkaTierWitness.Mixed m && m.interpreted();
  }

  private static boolean allC2(List<VarkaTierWitness.Verdict> counted) {
    return !counted.isEmpty() && counted.stream().allMatch(v -> v instanceof VarkaTierWitness.C2);
  }

  /** Whether {@code v} shows level-4 code: C2's verdict, an OSR at 4, a method at 4 at an edge. */
  private static boolean level4(VarkaTierWitness.Verdict v) {
    return switch (v) {
      case VarkaTierWitness.C2 c2 -> true;
      case VarkaTierWitness.Osr osr -> osr.level() == 4;
      case VarkaTierWitness.Mixed m -> m.before().containsValue(4) || m.after().containsValue(4);
      case VarkaTierWitness.Trapped t -> false;
    };
  }

  /** Whether C1's code served {@code v}: an OSR at level 1 to 3, or every method there at both
   * edges. */
  private static boolean servedByC1(VarkaTierWitness.Verdict v) {
    return switch (v) {
      case VarkaTierWitness.Osr osr -> osr.level() >= 1 && osr.level() <= 3;
      case VarkaTierWitness.Mixed m -> !m.after().isEmpty() && c1Levels(m.before())
          && c1Levels(m.after());
      case VarkaTierWitness.C2 c2 -> false;
      case VarkaTierWitness.Trapped t -> false;
    };
  }

  private static boolean c1Levels(Map<String, Integer> levels) {
    return levels.values().stream().allMatch(l -> l >= 1 && l <= 3);
  }

  /**
   * One warm-up call's arguments, built once with the kernel and its lane: the kernel's
   * {@code run} takes them as they are, the int lane's literals in {@code intLits}, the long
   * lane's in {@code longLits}. It is the {@link VarkaKernelChecks.Run} that {@link #call} hands
   * the sanitizer's window.
   */
  private record Args(VarkaFusedKernel kernel, boolean isLong, long[] srcData,
      long[] srcValidity, int[] nullCounts, long[] outData, long[] outValidity, int[] intLits,
      long[] longLits, int rows) implements VarkaKernelChecks.Run {

    /**
     * The kernel over these buffers, in the memory sanitizer's window as a compared call is. A
     * warm-up outside any window would teach C2 that the sanitizer's check never finds one, and
     * the first compared call, which opens one, would trap out of the warmed code at that check
     * ({@code speculate_null_assert} in {@code VarkaMemorySanitizer.check}): no warmed history
     * would be served by C2 under the sanitizer. The window also checks the warm-up's own
     * mappings. With the sanitizer off nothing is registered.
     */
    void call() {
      VarkaKernelChecks.sanitized(kernel, rows, isLong ? 8L : 4L, srcData, srcValidity, outData,
          outValidity, this);
    }

    @Override
    public int apply(long scratch) {
      return isLong
          ? kernel.run(srcData, srcValidity, nullCounts, outData, outValidity, new int[0],
              longLits, rows, scratch)
          : kernel.run(srcData, srcValidity, nullCounts, outData, outValidity, intLits, rows,
              scratch);
    }
  }
}
