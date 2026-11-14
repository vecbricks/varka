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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import scala.collection.immutable.Seq;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterTestSupport;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterTestSupport.Settle;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterTestSupport.TierState;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFusedKernel;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaLoopEmitter;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMethodNames;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSqlResolve;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTierWitness;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.types.DataTypes;

/**
 * What recomputing the civil-from-days prefix costs, per group that recomputes it, against
 * computing it once per batch (VARKA-198, {@code sql/varka/plans/m6/VARKA-198.md}).
 *
 * <p>A calendar output - {@code year(d)}, {@code month(d)}, {@code make_date(year(d), month(d),
 * k)} - decomposes its date through a prefix of about forty vector operations, and the outputs of
 * one loop method that decompose the same date share it. A kernel whose outputs are split across
 * several loop methods recomputes it in each - or, under {@code materializeChronoPrefix}, the
 * first method stores the prefix's vectors into a scratch region the caller passes and the later
 * methods load them. Each table takes the same outputs at the fused ceiling lowered step by step,
 * so the emitter splits them into more groups, and at each ceiling emits them both ways: the
 * recomputed arm and the materialized arm at the same group count, so their difference is what
 * the materialization wins at that many groups, and the recomputed arms' differences price the
 * recomputations the way the admission check did before the mechanism was built.
 *
 * <p>Two shapes. Cheap tails over one date, {@code year(d) + k}, where the prefix is most of each
 * group's work and the ceiling is widest; and the make_date ladder's top rung, 60 {@code
 * make_date(year(d), month(d), k)} outputs, the shape {@code VARKA-87.md} 3.3 counted eleven
 * repeated prefixes in. Each arm's table row names its group count, read from the emitted class,
 * so the x-axis is in the file. Null-free batches of 1024 rows, which every lane count divides:
 * the loop methods are what is measured, and the epilogue returns at once.
 *
 * <p>Two more arms at the default ceiling price the call-site budget (VARKA-209), which splits a
 * wide group whose loop method C1 refuses and leaves a narrow one: the budget off, so the cheap
 * tails are the one loop method past C1 the census measured; and the budget with its heavy-group
 * exemption off, so the make_date outputs split to one a method as the cheap tails do. The
 * default arms carry the budget as production does.
 *
 * <p>To run this benchmark:
 * <pre>
 *   dev/varka_bench_regen.sh catalyst VarkaSharedPrefixBenchmark
 * </pre>
 */
public class VarkaSharedPrefixBenchmark extends VarkaBenchmarkBase {

  private static final int NUM_ROWS = 1_000_000;
  private static final int CHUNK = 1024;

  /**
   * Drives an arm gets at least before it is timed; see {@link #driveToCompiledCode}. The bound
   * is a number of drives, not of calls: how many invocations of a loop method the JIT waits for
   * depends on C2's queue, so what ends the warm-up is the level the tier witness confirms.
   */
  private static final int MIN_WARM_DRIVES = 8;
  /** How far two drives' times may differ for the code to count as unchanged between them. */
  private static final double STEADY_FRACTION = 0.3;
  /** About the most one arm is driven, in ms (a drive and an idle wait may run past it). */
  private static final long WARM_WAIT_MILLIS = 60_000;

  /** The bounds every arm's settle runs under. */
  private static final Settle.Bounds SETTLE_BOUNDS =
      new Settle.Bounds(MIN_WARM_DRIVES, STEADY_FRACTION, WARM_WAIT_MILLIS);

  private static final Attribute D = VarkaSqlResolve.column("d", DataTypes.DateType);
  private static final Seq<Attribute> COLUMNS = seq(List.of(D));

  /** Fused ceilings from the default down, each splitting the outputs into more groups. */
  private static final List<Integer> CEILINGS = List.of(400, 200, 100, 50);

  private final Set<String> usedIds = new HashSet<>();

  /** A shape: its title and its outputs as SQL. */
  private record Shape(String title, List<String> sqls) { }

  /** An emitted kernel and the loop methods it has: its groups. */
  private record Emitted(VarkaFusedKernel kernel, int groups) { }

  /**
   * One arm of a shape: its label, whether the prefix is materialized, and its kernel; {@code
   * c2Expected} is false for the one arm whose loop method no compiler takes, which runs in the
   * interpreter (loop level 0 in the code list) however long it is driven.
   */
  private record Arm(String label, boolean materialized, int groups, VarkaFusedKernel kernel,
      long scratch, boolean c2Expected) { }

  private static CompiledVarkaProjection compile(List<String> sqls) {
    var exprs = new ArrayList<NamedExpression>();
    for (int k = 0; k < sqls.size(); k++) {
      exprs.add(VarkaSqlResolve.named(VarkaSqlResolve.parse(sqls.get(k), COLUMNS), "c" + k));
    }
    return VarkaExpressionCompiler.compile(seq(exprs), COLUMNS).orElseThrow(() ->
        new IllegalStateException("the projection did not fuse: " + sqls.get(0) + ", ..."));
  }

  /** The two shapes, by title. */
  private static List<Shape> shapes() {
    var tails = new ArrayList<String>();
    for (int k = 1; k <= 64; k++) {
      tails.add("year(d) + " + k);
    }
    var makeDates = new ArrayList<String>();
    for (int k = 1; k <= 60; k++) {
      int day = (k - 1) % 28 + 1;
      int yearOffset = (k - 1) / 28;
      String year = yearOffset == 0 ? "year(d)" : "year(d) + " + yearOffset;
      makeDates.add("make_date(" + year + ", month(d), " + day + ")");
    }
    return List.of(
        new Shape("64 cheap tails over one date: year(d) + k", tails),
        new Shape("60 make_date(year(d), month(d), k) outputs", makeDates));
  }

  private Emitted emit(CompiledVarkaProjection fused, VarkaGeneratedClassLoader loader,
      String id, VarkaEmitOptions options) {
    require(usedIds.add(id), "case id " + id + " is already in use by another emit in this "
        + "benchmark");
    String name = "org.apache.spark.sql.varka.execution.VarkaSharedPrefixBench" + id;
    var roots = new ArrayList<VarkaVectorIR>(fused.outputs());
    byte[] bytes = VarkaLoopEmitter.emit(name, roots, fused.inputOrdinals().size(),
        fused.numLiterals(), null, null, options);
    int groups = (int) VarkaEmitterTestSupport.methodNames(bytes).stream()
        .filter(e -> VarkaMethodNames.isLoop(e, true)).count();
    return new Emitted(defineKernel(loader, name, bytes), groups);
  }

  private static int drive(VarkaFusedKernel kernel, long scratch, MemorySegment data,
      long[] dst, long[] dstValidity, int[] literals) {
    int status = 0;
    int done = 0;
    while (done < NUM_ROWS) {
      int len = Math.min(CHUNK, NUM_ROWS - done);
      status |= kernel.run(new long[] {data.address()}, new long[] {0L}, new int[] {0}, dst,
          dstValidity, literals, len, scratch);
      done += len;
    }
    return status;
  }

  /** One drive of {@code arm} over the whole column, which must not decline a batch. */
  private static void driveChecked(String title, Arm arm, MemorySegment data, long[] dst,
      long[] dstValidity, int[] literals) {
    require(drive(arm.kernel(), arm.scratch(), data, dst, dstValidity, literals) == 0,
        title + " at " + arm.label() + ", materialized " + arm.materialized()
            + ", declined a batch");
  }

  /**
   * Drives {@code arm} over the whole column until the timed run will measure C2's code, through
   * {@link VarkaEmitterTestSupport.Settle}: the tier witness confirms every dense loop method of
   * its kernel, C2 has nothing queued or compiling, and the last two drives took the same time.
   * Neither fact is enough alone. An arm left in C1 code takes the same time every drive, and one
   * drive is 977 calls of each loop method, short of the invocations after which HotSpot asks for
   * C2 (the point is scaled by C2's queue length at that moment), so an arm driven once and left
   * stood in C1 code for as long as 37 seconds, into its timed run, at 65 to 349 ns/row against
   * 2.7. And code that has just reached level 4 can still be replaced while its drive is timed.
   *
   * <p>The witness tells a loop C2 only for an on-stack-replacement version and sees a loop C2
   * inlined into its caller, which the code list alone cannot. An arm whose C2 compile fails or
   * is refused is exempt from the level condition ({@code c2Expected}) and settles on drive
   * times alone, as the call-site budget off arm's single loop method does. An arm that does not
   * settle within {@link #WARM_WAIT_MILLIS} returns its state, which the caller prints.
   */
  private static TierState driveToCompiledCode(String title, Arm arm, MemorySegment data,
      long[] dst, long[] dstValidity, int[] literals) {
    Class<?> kernelClass = arm.kernel().getClass();
    var settle = new Settle(
        kernelClass,
        VarkaEmitterTestSupport.loopLevels(kernelClass).keySet(),
        Map.of(),
        () -> driveChecked(title, arm, data, dst, dstValidity, literals),
        SETTLE_BOUNDS,
        arm.c2Expected());
    return Settle.drive(settle);
  }

  /** One shape ready to run: its title, its arms, and the buffers every arm of it shares. */
  private record ShapeRun(String title, List<Arm> arms, long[] dst, long[] dstValidity,
      int[] literals) { }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    var loader = new VarkaGeneratedClassLoader(getClass().getClassLoader());
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment data = arena.allocate(CHUNK * 4L, 64);
      for (int i = 0; i < CHUNK; i++) {
        data.set(ValueLayout.JAVA_INT, i * 4L, i * 7 % 20000 - 10000);
      }
      section("the civil-from-days prefix, recomputed per group or computed once", () -> {
        var shapes = shapes();
        // Every shape's arms are emitted before the first is driven, so the witness starts after
        // the last emission and watches every kernel class before it runs.
        var runs = new ArrayList<ShapeRun>();
        for (int s = 0; s < shapes.size(); s++) {
          String title = shapes.get(s).title();
          List<String> sqls = shapes.get(s).sqls();
          CompiledVarkaProjection fused = compile(sqls);
          int n = sqls.size();
          int[] literals = fused.literals().stream().mapToInt(Integer::intValue).toArray();
          long[] dst = new long[n];
          for (int k = 0; k < n; k++) {
            dst[k] = arena.allocate(CHUNK * 4L, 64).address();
          }
          long[] dstValidity = new long[n];
          for (int k = 0; k < n; k++) {
            dstValidity[k] = arena.allocate(CHUNK / 8L, 64).address();
          }
          // Per ceiling, the recomputed arm and the materialized one; the latter takes the
          // scratch its kernel asks for, sized by the batch. Then the two call-site budget arms
          // at the default ceiling, materialized: the budget off, and every group split.
          var arms = new ArrayList<Arm>();
          for (int c : CEILINGS) {
            for (boolean materialized : new boolean[] {false, true}) {
              Emitted e = emit(fused, loader, s + "_" + c + "_" + materialized,
                  VarkaEmitOptions.DEFAULTS.withFusedCeiling(c)
                      .withMaterializeChronoPrefix(materialized));
              long scratch = VarkaEmitterTestSupport.scratch(e.kernel(), CHUNK);
              arms.add(new Arm("fused ceiling " + c, materialized, e.groups(), e.kernel(),
                  scratch, true));
            }
          }
          addBudgetArm(arms, fused, loader, s, "call-site budget off",
              VarkaEmitOptions.DEFAULTS.withCallSiteBudget(0));
          addBudgetArm(arms, fused, loader, s, "every group split",
              VarkaEmitOptions.DEFAULTS.withHeavyGroupOutputs(0));
          runs.add(new ShapeRun(title, List.copyOf(arms), dst, dstValidity, literals));
        }
        // The witness records from before any kernel runs, so it knows every compile of each
        // kernel class: which are on-stack replacements, and which loops C2 inlined into a
        // caller. Each arm's class is watched before any arm of it is driven.
        var witness = VarkaTierWitness.get();
        for (ShapeRun run : runs) {
          for (Arm arm : run.arms()) {
            witness.watch(arm.kernel().getClass().getName());
          }
        }
        try {
          for (ShapeRun run : runs) {
            // Every arm is driven once before the first is timed, so that one that declines a
            // batch fails the run at its start and not minutes in.
            for (Arm arm : run.arms()) {
              driveChecked(run.title(), arm, data, run.dst(), run.dstValidity(),
                  run.literals());
            }
            var benchmark = benchmark(run.title() + " over " + NUM_ROWS + " rows", NUM_ROWS)
                .timed().build();
            for (Arm arm : run.arms()) {
              String name = arm.label() + ": " + arm.groups() + " group"
                  + (arm.groups() == 1 ? "" : "s")
                  + (arm.materialized() ? ", prefix computed once" : ", prefix per group");
              // The arm is brought to C2's code when its own turn comes, not before the first
              // arm is timed: an arm that was left for the minutes the others take was found in
              // C1 code again.
              // The body runs on this thread alone, and its first call is a warm-up run
              // (iteration -1, more than once), so a plain flag marks the first.
              boolean[] settled = new boolean[1];
              benchmark.addTimerCase(name, timer -> {
                if (!settled[0]) {
                  settled[0] = true;
                  TierState state = driveToCompiledCode(run.title(), arm, data, run.dst(),
                      run.dstValidity(), run.literals());
                  if (!state.settled()) {
                    System.out.println("VarkaSharedPrefixBenchmark: " + run.title() + " at "
                        + arm.label() + ", materialized " + arm.materialized() + " did not settle"
                        + " in " + WARM_WAIT_MILLIS + " ms (" + state.drives()
                        + " drives, the last "
                        + TimeUnit.NANOSECONDS.toMillis(state.lastDriveNanos()) + " ms after "
                        + TimeUnit.NANOSECONDS.toMillis(state.previousDriveNanos()) + " ms, C2 "
                        + (state.c2Idle() ? "idle" : "busy")
                        + (state.reason().isEmpty() ? "" : ", " + state.reason())
                        + ", loop levels "
                        + VarkaEmitterTestSupport.loopLevels(arm.kernel().getClass())
                        + "); its cell may be timed in lower-tier code");
                  }
                }
                timer.startTiming();
                drive(arm.kernel(), arm.scratch(), data, run.dst(), run.dstValidity(),
                    run.literals());
                timer.stopTiming();
              });
            }
            runCases(benchmark);
          }
        } finally {
          VarkaTierWitness.close();
        }
      });
    }
  }

  private void addBudgetArm(List<Arm> arms, CompiledVarkaProjection fused,
      VarkaGeneratedClassLoader loader, int s, String label, VarkaEmitOptions options) {
    Emitted e = emit(fused, loader, s + "_" + label, options);
    long scratch = VarkaEmitterTestSupport.scratch(e.kernel(), CHUNK);
    // A kernel of one loop method, as the first shape has with the budget off, holds all 64 cheap
    // tails in the wide method that stays in the interpreter: its level is 0 after 159 drives.
    boolean c2Expected = e.groups() > 1;
    arms.add(new Arm("fused ceiling " + CEILINGS.get(0) + ", " + label, true, e.groups(),
        e.kernel(), scratch, c2Expected));
  }
}
