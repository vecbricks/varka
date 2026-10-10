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

// Only four Class-File API imports appear here: importing several others (CustomAttribute,
// AttributedElement, ClassElement...) makes scalac - and so every scaladoc pass over the module -
// fail with an "illegal cyclic reference" while completing the API's sealed hierarchy. Task-13
// additions use fully-qualified names inside method bodies instead, which scalac's Java parser
// never reads; see VarkaDebugInfo's class doc.
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaBodyEmitter.BodyMode;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.childrenOf;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaChronoLowering.chronoChild;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaChronoLowering.tailReadsMarchMonth;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.Lane.emitLanes;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaBodyEmitter.invokeCall;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.Analysis.referenced;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDescriptors.*;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitBudget.*;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.Analysis.BitmapPass;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;

/**
 * Emits a fused vector loop for a {@link VarkaVectorIR} DAG using the Class-File API: a class
 * implementing {@link VarkaFusedKernel} whose {@code run} <i>is</i> the loop - loads, the op
 * DAG on the operand stack, one store per output. It generalizes the six-step shape of the
 * hand-written {@code DateVectorOps} kernels, which remain the reference semantics for the
 * arithmetic; this class exists so that a whole projection, predication included, runs in one
 * pass with its intermediates in vector registers rather than in memory.
 *
 * <p>This class is the entry point and the map. {@link #emit} validates the DAG through
 * {@link Analysis}, groups the outputs under the budgets in {@link VarkaEmitBudget} and
 * assembles the class; the frame layout every method runs against is {@link Slots}; the
 * methods themselves - the drivers, the loop methods, the epilogue - are
 * {@link VarkaBodyEmitter}; the walk that emits one node's vector and validity word is
 * {@link VarkaVectorWalk}, which hands the calendar family to {@link VarkaChronoLowering} and
 * the constant divisions to {@link VarkaDivisionLowering}; the per-lane facts are {@link Lane}
 * and the method descriptors {@link VarkaDescriptors}. The notes below are the design of the
 * emitted class, in the order its code executes, and hold across those files.
 *
 * <p><b>Method layout.</b> {@code run} dispatches per batch on one loop-invariant test - are
 * all referenced inputs null-free? - to a dense or masked <i>driver</i>, which zeroes the
 * output validity, takes the all-null shortcut, then calls one sibling <i>loop</i> method per
 * output group (at most {@code GROUP_BUDGET} ops each; see that constant for the measured
 * reason) and finally the sibling <i>epilogue</i> method. The dense side runs with no validity
 * bookkeeping at all, which is sound because every node maps valid inputs to valid outputs -
 * there is no null-literal node - so null-free in means all-valid out. Separate methods rather
 * than one large one: each gets its own C2 compilation, so no method's node and inlining
 * budgets can starve another's intrinsics.
 *
 * <p><b>Unmasked compute.</b> Both bodies run unmasked loads, lanewise ops and stores. Inside
 * {@code loopBound} every access is in bounds, an all-null column still has an allocated data
 * buffer, and the engine contract declares invalid destination lanes undefined - so masks
 * carry no correctness inside the loop, and masked ops measure 2.3x-2.9x slower even with an
 * all-true mask (see {@code VARKA-10.md}). Truth lives in the <i>validity words</i>
 * instead: per lane group each referenced input contributes one long ({@code 0L} all-null,
 * {@code -1L} null-free, {@code validityBitsAt} otherwise), and each node's validity is
 * computed from its children's words by the mask algebra - AND for the null-intolerant ops, OR
 * for {@code greatest}/{@code least}, a word blend for {@code IfElse}. A {@code VectorMask} is
 * materialized only where a blend semantically needs one.
 *
 * <p><b>Conditions.</b> A {@link Cond} node evaluates to a known-true and a known-false word
 * pair - three-valued logic, where an unknown lane (a null below the comparison) is neither,
 * and {@code IfElse} takes its ELSE branch there. In the dense body every input lane is valid,
 * so the pair degenerates to the comparison mask itself and the connectives run in mask space.
 * {@code IfElse} validity is {@code (kT & validThen) | (~kT & validElse)}: the chosen branch's
 * validity, lane-wise, nothing ANDed globally.
 *
 * <p>{@code dayofweek}/{@code weekday} lower to a full-range mod-7 by base-8 digit sum, which
 * measures 8x the lanewise-DIV variant that x86 scalarizes (see {@code VARKA-11.md}): fold
 * 15-, 6- and 3-bit chunks ({@code 2^(3k) = 1 mod 7}), correct by {@code +3} where the input is
 * negative ({@code 2^32 = 4 mod 7}), one compare-subtract fixup, then the constant offset
 * applied after the mod so it cannot overflow.
 *
 * <p><b>Selection outputs.</b> A {@link Cond} may itself be an output root, and such an output
 * is a <i>selection bitmap</i> rather than a column - the root's known-true word OR-ed into
 * {@code dstValidity} exactly where a value root ORs its validity word, with the
 * {@code dstData} slot unused (callers pass {@code 0L}; the body never materializes it). The
 * bitmap's semantics are SQL's {@code WHERE}: a set bit means known true, so an unknown lane
 * reads as false - free by construction, because {@code kT} is a subset of the operands'
 * validity. This is the filter kernel: one Cond root per predicate, with no value outputs
 * beside it.
 *
 * <p><b>The epilogue, not a scalar tail.</b> The rows past {@code loopBound} are one more
 * iteration of the same lane-group body, under the mask {@code indexInRange} builds for a
 * partial group - {@code i} is {@code loopBound}, {@code lanes} becomes the remainder so every
 * validity helper stays bounded by the group, and only the loads and the stores take their
 * masked overloads. The masked load is required rather than preferred: the data segment is
 * sized to {@code length} times the lane's width in bytes, so an unmasked load of the last
 * partial group would run off its end. Lanes outside the mask are neither read nor faulted on,
 * which is what lets one masked iteration cover a partial group at all. The obvious
 * alternative - a per-row topological pass lowering every node type a second
 * time into int locals - is rejected because it is a complete second walk of the IR whose
 * every arm would have to grow with each new node type.
 *
 * <p><b>Inactive lanes read {@code 0}, so no operation in the walk may trap on {@code 0}.</b>
 * That is the invariant the epilogue rests on, and today it holds for free: the mod-7
 * lowerings divide by the constant 7, and add, sub, compare, blend, max, min and the shifts
 * are total. The first trapping operation to enter the IR - ANSI division above all - has to
 * blend a safe value into the inactive lanes or use a masked lanewise form, because the
 * epilogue computes them and only declines to store them.
 *
 * <p>Every call the loop makes is declared once, in one of two places. The calls that name a
 * lane's width - the loads and stores, the broadcast, the arithmetic, the comparisons, the
 * blend - come from the emission's {@link Lane}; the calls that do not, the mask algebra and
 * the validity helpers, are the constants below. Erasure is a live hazard in both -
 * {@code add}, {@code compare}, {@code blend} and {@code max} all take the <i>erased</i>
 * {@code Vector} - and a wrong descriptor should be found by pointing at one line rather than
 * by disassembling the output.
 *
 * <p>Out-of-shape IR - unknown lane types, a condition in a value position, out-of-range
 * ordinals or slots, a day offset that is neither a literal slot nor a column, trees past
 * {@link #MAX_CHAIN_DEPTH} or {@link #MAX_FUSED_NODES} - is rejected with
 * {@link IllegalArgumentException}, which the evaluator wiring treats as "fall back". Refusing
 * to emit is a normal outcome here, not an error path: the query still runs, on stock Spark.
 *
 * <p><b>Telemetry.</b> Every emitted class carries a {@code SourceFile} attribute - the
 * caller-supplied name, meant to identify the operator and stage
 * ({@code Varka_Project_Stage3.java}), so a stack frame in the generated {@code run} names the
 * plan node it came from without any mapping table - and a {@link VarkaDebugInfo} custom
 * attribute holding the IR and the caller's plan fragment, so a captured class is
 * self-describing. Both are metadata the JVM ignores; neither costs anything at runtime.
 */
public final class VarkaLoopEmitter {

  private VarkaLoopEmitter() {
  }

  /**
   * The lane every output root agrees on. The roots are the emission's outputs, and a class
   * holds one species: its loop, its epilogue and its stores are all that species, so two roots
   * on different lanes are two kernels rather than one. `analyze` re-checks every node below
   * them against this, which is where a mixed *tree* is caught.
   */
  private static Lane laneOf(List<VarkaVectorIR> outputs) {
    if (outputs.isEmpty()) {
      throw new IllegalArgumentException("no output chains to emit");
    }
    // The emission lane, not the root's: a NarrowLane root is a 32-bit column computed in the
    // 64-bit lane its child is on, and the loop runs at the child's species.
    Lane lane = Lane.of(VarkaVectorIR.emissionLane(outputs.get(0)));
    for (VarkaVectorIR output : outputs) {
      if (VarkaVectorIR.emissionLane(output) != lane.laneType) {
        throw new IllegalArgumentException("outputs mix lanes: " + lane.laneType + " and "
            + VarkaVectorIR.emissionLane(output));
      }
    }
    return lane;
  }

  /** {@link #emitLanes}, for the suite that checks a baked width against what the JVM has. */
  static int emitLanesForTest(VarkaEmitOptions options, Lane lane) {
    return emitLanes(options, lane);
  }

  /** The refusals {@link #analyze} declares for {@code outputs}, for the suite that checks them. */
  static Map<VarkaVectorIR, Analysis.Refusal> refusalsForTest(List<VarkaVectorIR> outputs,
      int numInputs, int numLiterals, VarkaEmitOptions options) {
    return Map.copyOf(analyze(List.copyOf(outputs), numInputs, numLiterals, options).refusals);
  }

  /**
   * The plan's reading of the drivers under {@code planSize}: the two drivers built by
   * themselves over the first grouping of {@code outputs} and measured ({@link #driverAlone}),
   * for the suite that holds them to the drivers of the built class, byte for byte.
   */
  static VarkaEmittedClass plannedDriversForTest(List<VarkaVectorIR> outputs, int numInputs,
      int numLiterals, VarkaEmitOptions options) {
    outputs = List.copyOf(outputs);
    Analysis analysis = analyze(outputs, numInputs, numLiterals, options);
    List<List<Integer>> groups = groupOutputs(outputs, options);
    analysis.planMaterialized(outputs, groups);
    return driverAlone(ClassDesc.of("org.apache.spark.sql.varka.execution.VarkaPlannedDriver"),
        outputs, analysis, numLiterals, groups);
  }

  /**
   * The analysis of {@code outputs} every emission starts from, in the one order its passes
   * run: the roots, the arm contexts, the refusals, the word algebra and the bitmap pass. What
   * depends on the grouping, the materialized prefixes, is planned by the caller once it has
   * one.
   */
  private static Analysis analyze(List<VarkaVectorIR> outputs, int numInputs, int numLiterals,
      VarkaEmitOptions options) {
    Analysis analysis = new Analysis(numInputs, numLiterals, options, laneOf(outputs));
    for (VarkaVectorIR root : outputs) {
      analysis.analyzeRoot(root);
    }
    analysis.collectArmContexts(outputs);
    analysis.collectRefusals();
    analysis.planWordAlgebra();
    analysis.planBitmapPass(outputs);
    return analysis;
  }

  /**
   * The telemetry-defaulted form of
   * {@link #emit(String, List, int, int, String, String, VarkaEmitOptions)}: the
   * {@code SourceFile} name falls back to the class's own simple name, the plan fragment to
   * empty, and the options to {@link VarkaEmitOptions#DEFAULTS}. For callers that hold no plan -
   * tests and benchmarks building IR by hand.
   */
  public static byte[] emit(
      String className, List<VarkaVectorIR> outputs, int numInputs, int numLiterals) {
    return emit(className, outputs, numInputs, numLiterals, null, null,
        VarkaEmitOptions.DEFAULTS);
  }

  /** As above, with telemetry strings and default options. */
  public static byte[] emit(
      String className, List<VarkaVectorIR> outputs, int numInputs, int numLiterals,
      String sourceFile, String planFragment) {
    return emit(className, outputs, numInputs, numLiterals, sourceFile, planFragment,
        VarkaEmitOptions.DEFAULTS);
  }

  /**
   * Assembles the fused-kernel class for the given output trees over {@code numInputs} columns
   * and {@code numLiterals} scalar-argument slots. Output {@code o} writes
   * {@code dstData[o]}/{@code dstValidity[o]}; a {@link ColumnRef} ordinal indexes the
   * {@code src*} arrays.
   *
   * <p>{@code sourceFile} becomes the class's {@code SourceFile} attribute - callers name the
   * operator and stage there so stack traces name the plan node - and {@code planFragment} is
   * carried verbatim in the {@link VarkaDebugInfo} attribute beside the IR (the telemetry note
   * in the class doc). Either may be null; see the four-argument form for the defaults. Neither
   * belongs in the shape key: each is already a function of the shape hash the cache computes.
   *
   * <p>{@code options} carries every other byte-affecting input - the group budget, CSE, the
   * mod-7 lowering, the descriptor fault injector. Unlike the two strings it <i>does</i> ride the
   * cache key, because it changes the loop rather than the labels on it; see
   * {@link VarkaEmitOptions}.
   *
   * @throws IllegalArgumentException if the IR is outside what this emitter serves - the
   *         caller is expected to fall back to the per-row projection, exactly as a kernel
   *         failure does.
   */
  public static byte[] emit(
      String className, List<VarkaVectorIR> outputs, int numInputs, int numLiterals,
      String sourceFile, String planFragment, VarkaEmitOptions options) {
    return emit(className, outputs, numInputs, numLiterals, sourceFile, planFragment, options,
        new VarkaEmitTrace());
  }

  /**
   * {@link #emit} for the suites that count how many times the class was built before the one
   * returned: {@code builds[0]} is set to that count, 1 when the first grouping was the last, and
   * {@code builds[1]}, where the array has it, to how many times a grouping switch was dropped
   * because the class it made would decline - 0 unless the fallback ran.
   */
  static byte[] emitCountingBuilds(String className, List<VarkaVectorIR> outputs, int numInputs,
      int numLiterals, VarkaEmitOptions options, int[] builds) {
    VarkaEmitTrace trace = new VarkaEmitTrace();
    try {
      return emit(className, outputs, numInputs, numLiterals, null, null, options, trace);
    } finally {
      builds[0] = trace.builds;
      if (builds.length > 1) {
        builds[1] = trace.fallbacks();
      }
    }
  }

  /**
   * {@link #emit} for the fuzzers, which add up what every emission's size control did
   * ({@link VarkaEmitTrace}) into {@code trace}, declined or not.
   */
  static byte[] emitTraced(String className, List<VarkaVectorIR> outputs, int numInputs,
      int numLiterals, VarkaEmitOptions options, VarkaEmitTrace trace) {
    return emit(className, outputs, numInputs, numLiterals, null, null, options, trace);
  }

  private static byte[] emit(
      String className, List<VarkaVectorIR> outputs, int numInputs, int numLiterals,
      String sourceFile, String planFragment, VarkaEmitOptions options, VarkaEmitTrace trace) {
    if (outputs.isEmpty()) {
      throw new IllegalArgumentException("no output chains to emit");
    }
    // The planner and the body emitters index the outputs per method, so a caller's list that is
    // not random access - a Scala List seen through asJava, as the tools pass it - would make every
    // lookup a walk and the emission quadratic in the width for that reason alone. The shape
    // cache already hands in a copy; copying here makes it true of every caller.
    outputs = List.copyOf(outputs);
    if (numInputs < 1 || numInputs > MAX_INPUTS) {
      throw new IllegalArgumentException(
          "numInputs " + numInputs + " outside [1, " + MAX_INPUTS + "]");
    }
    if (options == null) {
      // Checked beside the others rather than left to fail as a bare NPE deep in the walk;
      // VarkaShapeKey rejects a null the same way, so this closes the other door in.
      throw new IllegalArgumentException("emit options must not be null");
    }
    Analysis analysis = analyze(outputs, numInputs, numLiterals, options);

    // Method layout, all sharing the seven-parameter shape so slots line up everywhere: `run`
    // dispatches per batch to a dense or masked *driver*; the driver zeroes the output validity,
    // takes the all-null shortcut, then calls one sibling *loop* method per output group (within
    // GROUP_BUDGET, or FUSED_CEILING where the group's outputs share a calendar prefix - see
    // groupOutputs) and finally the *epilogue*. Separate methods, not one big one: each gets its
    // own C2 compilation, so no method's node and inlining budgets can starve another's
    // intrinsics (measured 3x to 4x; see `VARKA-10.md`).
    //
    // The epilogue is one method per group beside its loop method, `epilogueDense<g>` and
    // `epilogueMasked<g>`; with methodByteBudget 0, the form before VARKA-87, it is one method
    // for every output. One method was the right shape while GROUP_BUDGET was the only bound:
    // the epilogue runs once per batch, so a hot method's C2 cost had nothing to bound there.
    // It is the wrong shape for the JVM's size limit, which reads bytes rather than heat: a
    // single epilogue carries every output's tail and crosses HugeMethodLimit at thirteen
    // make_date outputs, after which it is never compiled at all (`VARKA-87.md` 2.2).
    // Split by the loop's groups it is bounded by what bounds the loops, and the split kernel
    // measured faster on even batches too (9.5 there).
    //
    // Under the byte budget the class is measured after it is built, in the units the JVM
    // enforces (VarkaEmittedClass), and a group with a method over the budget is split in two
    // and the class built again, until every group's methods fit or the groups over budget
    // are single outputs. Weight groups first because weight is known before anything is
    // built; bytes decide because bytes are what the JVM reads. A shape still over a limit
    // when no split is left declines with the reason (VarkaEmitDeclined): a single output
    // whose method is over budget, a driver over it - the driver sets up every output and
    // gains a call per group, so no regroup shrinks it - or a class over the class-file caps.
    // The cap on one method's code is met before any measurement: the Class-File API refuses
    // such a method while the class is assembled, so the refusal is read in place of the class
    // and takes the same path, budget or not (VARKA-219).
    //
    // The same measurement reads each group method's Vector API call sites, and a group whose
    // loop or epilogue is over the call-site budget is split the same way (VARKA-209): past that
    // count C1 refuses the method, which then runs interpreted until C2 compiles it. Only a
    // group of more than HEAVY_GROUP_OUTPUTS outputs is split: a narrower group over the
    // budget is one of heavy outputs, which no split brings under C1 and which would pay a
    // method per output at steady state. The budget never costs a kernel. A group it leaves
    // over the budget is not stuck, since C2 compiles it in seconds; while a group is stuck on
    // bytes the class declines anyway, so the call sites are not read at all; and a class its
    // splits made decline - each split gives the driver a call more, so they can push it past
    // the byte budget - is built again without the budget, which makes the emission exactly
    // the one the budget-off emitter makes, decline or not. Halving at the middle output
    // settles any group in two or three builds, since the fused ceiling keeps a group's count
    // near 180 at most.
    //
    // Under `predictGrouping` the first grouping also asks the emit cost model (VarkaEmitCost)
    // whether each candidate group would measure over either budget, so the common case is built
    // once; the measurement above still decides, and a class the predicted grouping would make
    // decline is built again with the weights alone (see `VARKA-199.md`). Under
    // `exactGrouping` the first grouping is the best partition of the outputs under the same
    // rule rather than the greedy walk's; a class it would make decline is built again without
    // it, keeping the prediction, before the prediction is dropped (see `VARKA-200.md`).
    //
    // Under `planSize` the first build is planned (VARKA-236). The driver is the one method no
    // regroup shrinks and the one whose bytes are known before the class exists: from a table it
    // is its calls alone, a fixed number of bytes a group, so it is built by itself over the
    // first grouping and measured. A driver over the budget is split into stages sized off that
    // measurement in the first build, where the loop above would build the class whole, measure
    // it and build it again; without the split driver it declines before any build, naming the
    // largest prefix of the outputs one class serves, which the compiler cuts the projection at
    // in one step rather than by bisection. The prediction closes each group under the budgets
    // less the fit's margins (VarkaEmitCostTable), so a method it under-predicts still measures
    // within them. The measurement keeps the last word: a reaction to the planned build is a
    // correction, counted and named in the trace, and anything still over after it runs the loop
    // above as the last resort, unchanged (`VARKA-236.md` 3).
    ClassDesc classDesc = ClassDesc.of(className);
    String source = sourceFile != null
        ? sourceFile : className.substring(className.lastIndexOf('.') + 1) + ".java";
    VarkaDebugInfo debugInfo = new VarkaDebugInfo(
        "outputs=" + VarkaBodyEmitter.renderOutputs(outputs) + ", numInputs=" + numInputs
            + ", numLiterals=" + numLiterals,
        planFragment != null ? planFragment : "",
        VarkaBodyEmitter.renderLineMap(analysis));
    int budget = options.methodByteBudget();
    // The call-site budget in force: the option's, until a class its splits produced declines.
    int siteBudget = options.callSiteBudget();
    int narrowest = Math.max(1, options.heavyGroupOutputs());
    boolean siteSplit = false;
    Set<Integer> forcedStarts = new HashSet<>();
    // The options the grouping reads: the caller's, with the call-site budget dropped when the
    // loop drops it, and without the prediction once a class the predicted grouping produced
    // would decline (see `VARKA-199.md`).
    VarkaEmitOptions grouping = options;
    // The exact grouping's runs, priced once per grouping options: between rebuilds only the
    // forced starts change, and they cut the runs rather than change them.
    ExactRuns exactRuns = new ExactRuns();
    // Groups per stage under `splitDriver`, 0 until a build's drivers alone are over the budget.
    int stageGroups = 0;
    boolean planned = options.planSize() && budget > 0;
    // Under the plan with the prediction, each group's prediction, so a correction can say what
    // the plan expected of the method it corrects.
    TallyRecord predictions = planned && options.predictGrouping()
        ? new TallyRecord(VarkaEmitCostTable.PRICES, new ArrayList<>(), false) : null;
    // Whether the next grouping starts afresh - the first, and one a rollback or a fallback
    // makes - which is when the plan reads its driver. A regroup adds groups to a driver the plan
    // has read, so the reading is repeated only where those groups could take it over the
    // budget, by the bytes a group cost in the last reading.
    boolean afresh = true;
    int readGroups = 0;
    int readWidest = 0;
    while (true) {
      if (predictions != null) {
        predictions.tallies().clear();
      }
      List<List<Integer>> groups = groupOutputs(outputs, grouping, forcedStarts, predictions,
          exactRuns);
      // Decided per grouping, since a regroup can move a prefix across a group boundary; empty
      // unless the option is on and a prefix crosses one, and then the class takes the scratch
      // address as an eighth argument (VARKA-198).
      analysis.planMaterialized(outputs, groups);
      boolean nearBudget = readGroups > 0 && groups.size() > readGroups
          && readWidest + (long) (groups.size() - readGroups) * readWidest / readGroups > budget;
      if (planned && stageGroups == 0 && (afresh || nearBudget) && options.driverOutputTable()) {
        // The plan: the drivers built alone over this grouping, and stages or a cut read off
        // them, so no build is spent finding a driver over. `misdescribeDriverBytes` takes bytes
        // off the reading, for the test of a plan the build corrects.
        analysis.stageGroups = 0;
        VarkaEmittedClass driver = driverAlone(classDesc, outputs, analysis, numLiterals, groups);
        int widest = widestDriver(driver) - options.misdescribeDriverBytes();
        readGroups = groups.size();
        readWidest = Math.max(1, widest);
        if (widest > budget) {
          if (options.splitDriver()) {
            stageGroups = fitGroups(groups.size(), widest, budget - STAGE_MARGIN);
            trace.plannedStages++;
          } else {
            // One class serves the outputs of the first groups whose calls fit the budget; the
            // rest are the compiler's to set aside (VarkaEmitDeclined.plannedCut). No stages
            // here, so no margin for a stage's own code: the reading is the driver's, exact.
            trace.plannedDeclines++;
            throw new VarkaEmitDeclined(String.join("; ", overLimits(driver, budget))
                + "; planned before the build: " + fitGroups(groups.size(), widest, budget)
                + " of " + groups.size() + " groups fit the driver", List.of(),
                plannedCut(groups, widest, budget));
          }
        }
      }
      afresh = false;
      trace.builds++;
      // Whether this build is the plan's, whose reactions are corrections of it.
      boolean correcting = planned && trace.builds == 1;
      analysis.stageGroups = stageGroups;
      byte[] bytes;
      VarkaEmittedClass measured;
      int limit = budget;
      try {
        bytes = build(classDesc, source, debugInfo, outputs, analysis, numLiterals, groups,
            budget > 0);
        if (budget == 0) {
          return bytes;
        }
        measured = VarkaEmittedClass.measure(bytes);
      } catch (IllegalArgumentException e) {
        // The class-file cap on a method's code is the one limit no measurement of the class
        // can see: the Class-File API enforces it while the class is assembled, after every
        // body is built, so a method over it leaves no class to measure. The refusal is read
        // as the measurement of that one method and judged against the cap - whatever the
        // budget, since the cap is the JVM's and binds the legacy form as well - and the loop
        // below splits its group or declines exactly as for a measured method over a limit
        // (VARKA-219.md 3.1). The constant pool's cap is enforced the same way and read
        // here too, but no regroup shrinks a pool, so it declines class-wide at once. Any other
        // refusal of the build is not a size and is rethrown.
        Optional<VarkaEmittedClass> refusal = VarkaEmittedClass.refused(e);
        if (refusal.isEmpty()) {
          if (VarkaEmittedClass.refusedConstantPool(e)) {
            throw new VarkaEmitDeclined("the constant pool is over the cap of " + CONSTANT_POOL_CAP
                + ": the class cannot be built (" + e.getMessage() + ")", List.of());
          }
          throw e;
        }
        measured = refusal.get();
        bytes = null;
        limit = METHOD_CODE_CAP;
      }
      List<Integer> stuck = new ArrayList<>();
      SortedMap<Integer, Map.Entry<String, Integer>> overBytes = groupsOver(measured, limit);
      boolean split = halveGroups(overBytes.keySet(), groups, 1, forcedStarts, stuck);
      if (split) {
        trace.byteRegroups++;
        if (correcting) {
          noteCorrections(trace, overBytes, predictions, limit, true);
        }
      }
      // Only a built class has call-site counts: a refusal's measurement is one method's bytes.
      if (bytes != null && siteBudget > 0 && stuck.isEmpty()) {
        SortedMap<Integer, Map.Entry<String, Integer>> overSites =
            groupsOverCallSites(measured, siteBudget);
        if (halveGroups(overSites.keySet(), groups, narrowest, forcedStarts, null)) {
          siteSplit = true;
          split = true;
          trace.siteSplits++;
          if (correcting) {
            noteCorrections(trace, overSites, predictions, siteBudget, false);
          }
        }
      }
      if (split) {
        continue;
      }
      List<String> findings = overLimits(measured, limit);
      if (findings.isEmpty()) {
        // Only a built class reaches here: a refusal's one method is over the cap by
        // construction, so it always has a finding.
        return bytes;
      }
      // Under `splitDriver` a class whose only methods over the limit are its drivers - the
      // driver from a table is 20 bytes and 44 a group, so past about 180 groups - is built again
      // with the calls to its groups moved into stages. The stage size is read off the measured
      // driver, so one rebuild settles it, and a stage still over is resized off its own
      // measurement. Stages are sized for the byte budget even where the refusal of a driver
      // past the class-file cap is what found them over, so the next build fits the budget.
      if (stuck.isEmpty() && budget > 0 && options.splitDriver() && options.driverOutputTable()) {
        int next = stageSize(measured, limit, budget, findings.size(), groups.size(),
            stageGroups);
        if (next > 0) {
          stageGroups = next;
          trace.stageSplits++;
          if (correcting) {
            trace.corrections.add("the stages: " + String.join("; ", findings) + "; resized to "
                + next + " groups a stage");
          }
          continue;
        }
      }
      if (siteSplit) {
        trace.siteRollbacks++;
        siteBudget = 0;
        siteSplit = false;
        forcedStarts.clear();
        // A new grouping: its stages are sized again from its own driver.
        stageGroups = 0;
        afresh = true;
        // The prediction closes groups on call sites too, so it drops the budget with the loop.
        grouping = grouping.withCallSiteBudget(0);
        continue;
      }
      // The grouping switches close groups the greedy weights would not: the exact grouping may
      // take a group more where that saves ops, and the prediction closes groups on its budgets.
      // Every group is a call more in the driver, so either can push the driver past the byte
      // budget. Like the call-site splits above, neither may cost a kernel, so each is dropped in
      // turn - the exact grouping first, keeping the prediction's grouping, then the prediction,
      // which leaves the emission the switches-off emitter makes, decline or not. A decline that
      // names a single output over the budget is left alone: no grouping changes it.
      if (grouping.exactGrouping() && stuck.isEmpty()) {
        grouping = options.withExactGrouping(false);
        trace.exactFallbacks++;
      } else if (grouping.predictGrouping() && stuck.isEmpty()) {
        grouping = options.withPredictGrouping(false).withExactGrouping(false);
        trace.predictFallbacks++;
      } else {
        throw new VarkaEmitDeclined(String.join("; ", findings)
            + (stuck.isEmpty() ? "" : "; output" + (stuck.size() == 1 ? " " : "s ") + stuck
                + " cannot be regrouped smaller"), stuck,
            planned && stuck.isEmpty() ? plannedCutOf(measured, limit, budget, groups) : -1);
      }
      siteBudget = options.callSiteBudget();
      forcedStarts.clear();
      stageGroups = 0;
      afresh = true;
    }
  }

  /**
   * The groups per stage the next build of a split driver should take, or 0 where no stage size
   * helps: where a method other than a driver or a stage is over {@code limit}, or a class-wide
   * cap is ({@code findings} counts more than the methods over the limit), or a stage of one group
   * is already over. Sizes are for {@code budget}, the byte budget, whatever {@code limit} found
   * the methods over. From an unsplit driver the size is its groups scaled by the budget over the
   * driver's bytes, less a margin for the stage's own few bytes; a stage over is resized the same
   * way off the widest stage, and always shrinks.
   */
  private static int stageSize(VarkaEmittedClass measured, int limit, int budget, int findings,
      int groups, int stageGroups) {
    int over = 0;
    int widestDriver = 0;
    int widestStage = 0;
    for (Map.Entry<String, Integer> e : measured.codeLength().entrySet()) {
      if (e.getValue() <= limit) {
        continue;
      }
      over++;
      String name = e.getKey();
      if (VarkaMethodNames.isDriver(name)) {
        widestDriver = Math.max(widestDriver, e.getValue());
      } else if (VarkaMethodNames.isStage(name)) {
        widestStage = Math.max(widestStage, e.getValue());
      } else {
        return 0;
      }
    }
    if (over == 0 || over != findings || budget <= STAGE_MARGIN) {
      return 0;
    }
    if (widestStage > 0) {
      if (stageGroups <= 1) {
        return 0;
      }
      int resized = (int) ((long) stageGroups * (budget - STAGE_MARGIN) / widestStage);
      return Math.max(1, Math.min(stageGroups - 1, resized));
    }
    if (stageGroups > 0) {
      // The driver of a split class is over the limit: its stages are too many, so they must be
      // wider - which only happens past some 180 stages of 180 groups, beyond any class cap.
      return 0;
    }
    return fitGroups(groups, widestDriver, budget - STAGE_MARGIN);
  }

  /** The bytes a stage size leaves for a stage's own code beside its calls. */
  private static final int STAGE_MARGIN = 64;

  /**
   * How many of {@code groups} groups a method of {@code bytes} bytes over all of them holds
   * within {@code budget}, its bytes scaling with its groups, at least one: the arithmetic that
   * sizes a stage and cuts a kernel.
   */
  private static int fitGroups(int groups, int bytes, int budget) {
    return Math.max(1, (int) ((long) groups * budget / bytes));
  }

  /**
   * The outputs of the first groups a driver of {@code widest} bytes over {@code groups} holds
   * within {@code budget} ({@link VarkaEmitDeclined#plannedCut}): fewer than all of them, by at
   * least the last group.
   */
  private static int plannedCut(List<List<Integer>> groups, int widest, int budget) {
    int fit = Math.min(fitGroups(groups.size(), widest, budget), groups.size() - 1);
    int cut = 0;
    for (int g = 0; g < fit; g++) {
      cut += groups.get(g).size();
    }
    return cut;
  }

  /**
   * The drivers of the class over {@code groups}, built by themselves and measured, for the plan
   * under {@code planSize}: the two driver methods and nothing else, so their calls name methods
   * the class does not have, which the Class-File API does not mind. From a table the driver's
   * code is its calls, so what is measured here is what the full class's driver measures, and
   * a driver past the class-file cap is read from the refusal as {@link #emit} reads one.
   */
  private static VarkaEmittedClass driverAlone(ClassDesc classDesc, List<VarkaVectorIR> outputs,
      Analysis analysis, int numLiterals, List<List<Integer>> groups) {
    boolean anyColumns = analysis.referencedColumns != 0;
    analysis.owner = classDesc;
    MethodTypeDesc desc = analysis.bodyDesc();
    try {
      byte[] bytes = ClassFile.of().build(classDesc, (ClassBuilder b) -> {
        b.withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL);
        if (!analysis.nullsFromValidInputs) {
          b.withMethodBody(VarkaMethodNames.driver(true), desc, AccessFlag.PRIVATE.mask(),
              (CodeBuilder cb) -> VarkaBodyEmitter.emitDriver(cb, true, classDesc, outputs,
                  analysis, numLiterals, groups));
        }
        if (anyColumns || analysis.nullsFromValidInputs) {
          b.withMethodBody(VarkaMethodNames.driver(false), desc, AccessFlag.PRIVATE.mask(),
              (CodeBuilder cb) -> VarkaBodyEmitter.emitDriver(cb, false, classDesc, outputs,
                  analysis, numLiterals, groups));
        }
      });
      return VarkaEmittedClass.measure(bytes);
    } catch (IllegalArgumentException e) {
      Optional<VarkaEmittedClass> refusal = VarkaEmittedClass.refused(e);
      if (refusal.isPresent()) {
        return refusal.get();
      }
      throw e;
    }
  }

  /** The bytes of the wider of a class's two drivers, 0 where it has neither. */
  private static int widestDriver(VarkaEmittedClass measured) {
    int widest = 0;
    for (String driver : VarkaMethodNames.DRIVERS) {
      Integer bytes = measured.codeLength().get(driver);
      if (bytes != null) {
        widest = Math.max(widest, bytes);
      }
    }
    return widest;
  }

  /**
   * The plan's cut for a class-wide decline of a built class on its driver
   * ({@link VarkaEmitDeclined#plannedCut}), read off the measured driver; -1 where a method other
   * than a driver is over {@code limit}, which no cut answers.
   */
  private static int plannedCutOf(VarkaEmittedClass measured, int limit, int budget,
      List<List<Integer>> groups) {
    for (Map.Entry<String, Integer> e : measured.codeLength().entrySet()) {
      if (e.getValue() > limit && !VarkaMethodNames.isDriverOrDispatch(e.getKey())) {
        return -1;
      }
    }
    int widest = widestDriver(measured);
    if (widest <= budget || groups.size() < 2) {
      return -1;
    }
    return plannedCut(groups, widest, budget);
  }

  /**
   * Names each group {@code over} names as a correction of the planned build in the trace: the
   * method, what the plan predicted for it where it predicted, and what it measured against
   * {@code limit}, in bytes or in call sites.
   */
  private static void noteCorrections(VarkaEmitTrace trace,
      SortedMap<Integer, Map.Entry<String, Integer>> over, TallyRecord predictions, int limit,
      boolean bytes) {
    for (Map.Entry<Integer, Map.Entry<String, Integer>> e : over.entrySet()) {
      String predicted = "unpredicted";
      if (predictions != null && e.getKey() < predictions.tallies().size()) {
        double[] p = predictions.tallies().get(e.getKey()).predicted();
        if (p != null) {
          predicted = "predicted " + Math.round(bytes ? VarkaEmitCost.maxBytes(p)
              : VarkaEmitCost.maxSites(p));
        }
      }
      trace.corrections.add(e.getValue().getKey() + ": " + predicted + ", measured "
          + e.getValue().getValue() + ", limit " + limit + (bytes ? " bytes" : " call sites"));
    }
  }

  /**
   * Adds a forced start at the middle output of each of the groups {@code over} names that
   * holds more than {@code narrowest} outputs, so the next grouping halves it, and says whether
   * it added any. A group it leaves whole is a single output when {@code narrowest} is 1, and
   * is then appended to {@code stuck} where that is given: the byte budget's case, whose single
   * outputs over a limit decline. The call-site budget passes no list, since a group it leaves
   * stands.
   */
  private static boolean halveGroups(Set<Integer> over, List<List<Integer>> groups, int narrowest,
      Set<Integer> forcedStarts, List<Integer> stuck) {
    boolean split = false;
    for (int g : over) {
      List<Integer> group = groups.get(g);
      if (group.size() > narrowest) {
        forcedStarts.add(group.get(group.size() / 2));
        split = true;
      } else if (stuck != null) {
        stuck.add(group.get(0));
      }
    }
    return split;
  }

  /** One build of the class over {@code groups}; see the method-layout note in {@link #emit}. */
  private static byte[] build(ClassDesc classDesc, String source, VarkaDebugInfo debugInfo,
      List<VarkaVectorIR> outputs, Analysis analysis, int numLiterals,
      List<List<Integer>> groups, boolean epiloguePerGroup) {
    boolean anyColumns = analysis.referencedColumns != 0;
    analysis.owner = classDesc;
    return ClassFile.of().build(classDesc, (ClassBuilder b) -> {
      emitRangeTables(b, classDesc, analysis);
      b.withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL)
          .withInterfaceSymbols(FUSED_KERNEL)
          .with(java.lang.classfile.attribute.SourceFileAttribute.of(source))
          .with((java.lang.classfile.ClassElement) debugElement(debugInfo))
          .withMethodBody("<init>", INIT, AccessFlag.PUBLIC.mask(), (CodeBuilder cb) -> {
            cb.aload(0);
            cb.invokespecial(ConstantDescs.CD_Object, "<init>", INIT);
            cb.return_();
          })
          .withMethodBody(VarkaMethodNames.DISPATCH, analysis.bodyDesc(), AccessFlag.PUBLIC.mask(),
              (CodeBuilder cb) -> emitDispatch(cb, classDesc, analysis));
      if (analysis.hasScratch()) {
        // The form without the address, which every caller that drives a kernel as a function
        // of its arguments reaches: it takes the thread's fallback buffer for its rows
        // (VarkaScratch) and runs the form with the address. The callers that pass their own
        // scratch never come through here. The size is the interface's question about how much
        // a caller passes per row.
        b.withMethodBody(VarkaMethodNames.DISPATCH, analysis.lane.runDesc, AccessFlag.PUBLIC.mask(),
            (CodeBuilder cb) -> {
              Lane lane = analysis.lane;
              cb.aload(0);
              cb.aload(P_SRC_DATA);
              cb.aload(P_SRC_VALIDITY);
              cb.aload(P_NULL_COUNT);
              cb.aload(P_DST_DATA);
              cb.aload(P_DST_VALIDITY);
              cb.aload(P_SCALAR_ARGS);
              if (lane.pLongArgs >= 0) {
                cb.aload(lane.pLongArgs);
              }
              cb.iload(lane.pLength);
              cb.loadConstant(analysis.scratchBytesPerRow());
              cb.iload(lane.pLength);
              cb.invokestatic(ClassDesc.of(VarkaScratch.class.getName()), "forRows",
                  MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_int,
                      ConstantDescs.CD_int));
              cb.invokevirtual(classDesc, VarkaMethodNames.DISPATCH, lane.runDescScratch);
              cb.ireturn();
            });
        b.withMethodBody("scratchBytesPerRow", MethodTypeDesc.of(ConstantDescs.CD_int),
            AccessFlag.PUBLIC.mask(), (CodeBuilder cb) -> {
              cb.loadConstant(analysis.scratchBytesPerRow());
              cb.ireturn();
            });
      }
      // A kernel that nulls a valid input (non-ANSI make_date) has no dense methods: the dense body
      // writes no per-lane validity, so the dispatch takes the masked methods for every batch, and
      // the masked body treats a null-free input as a constant word.
      if (!analysis.nullsFromValidInputs) {
        emitBodies(b, true, classDesc, outputs, analysis, numLiterals, groups, epiloguePerGroup);
      }
      if (anyColumns || analysis.nullsFromValidInputs) {
        emitBodies(b, false, classDesc, outputs, analysis, numLiterals, groups, epiloguePerGroup);
      }
    });
  }

  /**
   * Declares a {@code private static final int[]} per {@link VarkaVectorIR.InRanges} range set and
   * fills them in the class initialiser, once per class: the bounds {@code InRanges} loops over,
   * in the node's order, lower and upper bound of each range in turn. Nothing when the kernel has
   * no range set, so every other kernel's class is unchanged.
   */
  private static void emitRangeTables(ClassBuilder b, ClassDesc classDesc, Analysis analysis) {
    if (analysis.rangeTables.isEmpty()) {
      return;
    }
    ClassDesc intArray = ConstantDescs.CD_int.arrayType();
    for (String field : analysis.rangeTables.values()) {
      b.withField(field, intArray,
          AccessFlag.PRIVATE.mask() | AccessFlag.STATIC.mask() | AccessFlag.FINAL.mask());
    }
    b.withMethodBody("<clinit>", MethodTypeDesc.of(ConstantDescs.CD_void),
        AccessFlag.STATIC.mask(), (CodeBuilder cb) -> {
          for (var e : analysis.rangeTables.entrySet()) {
            List<Integer> bounds = e.getKey().bounds();
            cb.loadConstant(bounds.size());
            cb.newarray(java.lang.classfile.TypeKind.INT);
            for (int i = 0; i < bounds.size(); i++) {
              cb.dup();
              cb.loadConstant(i);
              cb.loadConstant(bounds.get(i));
              cb.iastore();
            }
            cb.putstatic(classDesc, e.getValue(), intArray);
          }
          cb.return_();
        });
  }

  /**
   * One side's methods - dense or masked: the driver, a loop method per group, and the
   * epilogue, which is one method over every output or, with {@code epiloguePerGroup}, one per
   * group. See the method-layout note in {@link #emit}.
   */
  private static void emitBodies(ClassBuilder b, boolean dense, ClassDesc classDesc,
      List<VarkaVectorIR> outputs, Analysis analysis, int numLiterals,
      List<List<Integer>> groups, boolean epiloguePerGroup) {
    MethodTypeDesc desc = analysis.bodyDesc();
    b.withMethodBody(VarkaMethodNames.driver(dense), desc, AccessFlag.PRIVATE.mask(),
        (CodeBuilder cb) -> VarkaBodyEmitter.emitDriver(cb, dense, classDesc, outputs,
            analysis, numLiterals, groups));
    for (int k = 0; analysis.stageGroups > 0 && k * analysis.stageGroups < groups.size(); k++) {
      final int stage = k;
      b.withMethodBody(VarkaMethodNames.stage(dense, k), desc, AccessFlag.PRIVATE.mask(),
          (CodeBuilder cb) -> VarkaBodyEmitter.emitStage(cb, dense, stage, classDesc, analysis,
              groups));
    }
    if (!epiloguePerGroup) {
      b.withMethodBody(VarkaMethodNames.epilogue(dense), desc, AccessFlag.PRIVATE.mask(),
          (CodeBuilder cb) -> VarkaBodyEmitter.emitGroupBody(cb, dense, BodyMode.EPILOGUE, -1,
              outputs, analysis, numLiterals, groups));
    }
    for (int g = 0; g < groups.size(); g++) {
      final int group = g;
      b.withMethodBody(VarkaMethodNames.loop(dense, g), desc, AccessFlag.PRIVATE.mask(),
          (CodeBuilder cb) -> VarkaBodyEmitter.emitGroupBody(cb, dense, BodyMode.LOOP, group,
              outputs, analysis, numLiterals, groups));
      if (epiloguePerGroup) {
        b.withMethodBody(VarkaMethodNames.epilogue(dense, g), desc, AccessFlag.PRIVATE.mask(),
            (CodeBuilder cb) -> VarkaBodyEmitter.emitGroupBody(cb, dense, BodyMode.EPILOGUE, group,
                outputs, analysis, numLiterals, groups));
      }
    }
  }

  /**
   * The write side of {@link VarkaDebugInfo}: its payload as a class element for the build
   * above. Lives here, private, beside its only call site, with the attribute subclass and
   * its write-only mapper as fully-qualified local classes in the method body - the regime
   * {@link VarkaDebugInfo}'s class doc explains (scalac cannot complete much of the
   * Class-File API, so its types stay out of every import and every non-private signature).
   * That class doc also fixes the byte format this writer and {@code read}'s mapper must
   * agree on: the writer emits the whole attribute structure, six-byte name-and-length
   * header included (the built-in mappers do the same), with the two u2 constant-pool
   * indices as the payload.
   *
   * <p>Declared to return {@code Object} - the caller casts to {@code ClassElement} inside
   * its own body - because scalac completes even a private method's signature types, and
   * {@code ClassElement} is one of the types it cannot complete.
   */
  private static Object debugElement(VarkaDebugInfo info) {
    final class Attr extends java.lang.classfile.CustomAttribute<Attr> {
      Attr(java.lang.classfile.AttributeMapper<Attr> mapper) {
        super(mapper);
      }
    }
    final class WriteMapper implements java.lang.classfile.AttributeMapper<Attr> {
      @Override
      public String name() {
        return VarkaDebugInfo.NAME;
      }

      @Override
      public Attr readAttribute(java.lang.classfile.AttributedElement enclosing,
          java.lang.classfile.ClassReader cf, int pos) {
        throw new UnsupportedOperationException(
            "write-side mapper; parsing uses VarkaDebugInfo.read()");
      }

      @Override
      public void writeAttribute(java.lang.classfile.BufWriter buf, Attr attr) {
        buf.writeIndex(buf.constantPool().utf8Entry(VarkaDebugInfo.NAME));
        buf.writeInt(6);
        buf.writeIndex(buf.constantPool().utf8Entry(info.ir()));
        buf.writeIndex(buf.constantPool().utf8Entry(info.planFragment()));
        buf.writeIndex(buf.constantPool().utf8Entry(info.lineMap()));
      }

      @Override
      public AttributeStability stability() {
        return AttributeStability.CP_REFS;
      }
    }
    return new Attr(new WriteMapper());
  }

  /**
   * Partitions the outputs into loop-method groups, greedily in output order, counting only
   * ops new to the group so shared subtrees keep their outputs together (and their
   * cross-output CSE). Two clauses admit the next output into the group being built:
   *
   * <ol> <li>its marginal ops keep the group within {@code groupBudget} (normally
   * {@code GROUP_BUDGET} ) - the ordinary rule;</li> <li>joining lets it skip a civil-from-days
   * prefix the group already computes, and the group stays within {@code fusedCeiling} (normally
   * {@code FUSED_CEILING} ). {@link GroupOps#saved} is what opens the wider bound, and it counts
   * prefix reuse only: a whole node the group already holds is not reason enough. An output that
   * skips a prefix makes the method strictly less work rather than a trade, which is a property of
   * the shape and holds whatever the register pressure of the day; whether a merely-shared subchain
   * pays is an empirical question that has already answered both ways (the merge measured a 1.4x
   * loss before the validity OR moved ahead of the vector work the same committed rows show it
   * winning by 1.3x - see {@code VARKA-32.md} 7.6), so it stays {@code GROUP_BUDGET} 's own
   * retuning question rather than riding on this clause. With
   * {@link VarkaEmitOptions#shareChronoPrefix} off no prefix is ever shared, so the clause never
   * fires and the weights count whole.</li> </ol>
   *
   * <p>An output wider than either bound on its own still forms a group: splitting inside one
   * output would forfeit the register residency that is the point. Greedy in output order is
   * a known limitation: in {@code year(d), year(d2), month(d)} the month is offered to the
   * group holding {@code year(d2)}, whose prefix it cannot reuse, so it forms a third group and
   * recomputes a prefix it would have shared had it been adjacent to {@code year(d)}. The suite
   * pins that as a limitation; reordering outputs for prefix affinity is in the milestone's
   * debt register, because the evaluator's per-output vectors and the debug line map key on
   * the projection's order.
   *
   * <p>Greedy in where it closes a group is a second limitation, which
   * {@link VarkaEmitOptions#exactGrouping} lifts: a cheap output that shares nothing can join a
   * group only under clause 1, so where the walk has just filled a group it is left with a loop
   * method of its own. Under the option the best partition of the outputs in their order
   * ({@link #bestPartitionStarts}) decides where each group starts, and the walk below closes a
   * group there and nowhere else; the rule that admits an output is the same either way.
   */
  private static List<List<Integer>> groupOutputs(List<VarkaVectorIR> outputs,
      VarkaEmitOptions options) {
    return groupOutputs(outputs, options, Set.of(), null, new ExactRuns());
  }

  /**
   * The first grouping {@link #emit} builds, before any measurement regroups it: what the cost
   * model's suites compare their predictions against, method by method.
   */
  static List<List<Integer>> groupsForTest(List<VarkaVectorIR> outputs,
      VarkaEmitOptions options) {
    return groupOutputs(List.copyOf(outputs), options);
  }

  /** As above, with {@code forcedStarts}: outputs that begin a group whatever the rule says. */
  static List<List<Integer>> groupsForTest(List<VarkaVectorIR> outputs,
      VarkaEmitOptions options, Set<Integer> forcedStarts) {
    return groupOutputs(List.copyOf(outputs), options, forcedStarts, null, new ExactRuns());
  }

  /**
   * The cost model's tally of each group of that first grouping, with its feature counts kept,
   * priced at {@code prices}: the very tallies the grouping forms, so what the suites fit and
   * score is what the switch predicts with.
   */
  static List<VarkaEmitCost.Tally> talliesForTest(List<VarkaVectorIR> outputs,
      VarkaEmitOptions options, Map<String, double[]> prices) {
    List<VarkaEmitCost.Tally> tallies = new ArrayList<>();
    groupOutputs(List.copyOf(outputs), options, Set.of(),
        new TallyRecord(prices, tallies, true), new ExactRuns());
    return tallies;
  }

  /**
   * Where {@link #talliesForTest} asks {@link #groupOutputs} to leave each group's tally, with
   * its feature counts where {@code keepCounts}; the plan under {@code planSize} asks for the
   * predictions alone.
   */
  private record TallyRecord(Map<String, double[]> prices, List<VarkaEmitCost.Tally> tallies,
      boolean keepCounts) {}

  /**
   * As above, with {@code forcedStarts}: outputs that begin a new group whatever the weights
   * say. The byte-budget regroup in {@link #emit} adds the middle output of a group whose
   * methods measured over the budget, so the split halves a group and never reorders one.
   * {@code record}, null but in the suites, collects each group's tally; {@code exactRuns}
   * keeps the exact grouping's runs between calls with the same options.
   */
  private static List<List<Integer>> groupOutputs(List<VarkaVectorIR> outputs,
      VarkaEmitOptions options, Set<Integer> forcedStarts, TallyRecord record,
      ExactRuns exactRuns) {
    Set<Integer> exactStarts = null;
    if (options.exactGrouping()) {
      // The best partition's group starts, forced on the walk below. Every group of that
      // partition is one the rule admits, so the walk closes a group at each of its starts and
      // at no other output, and so forms exactly that partition - which is checked at the end.
      // The runs are priced as the walk prices them, so the two cannot judge a step apart.
      Map<String, double[]> prices = record != null ? record.prices() : VarkaEmitCostTable.PRICES;
      exactStarts = bestPartitionStarts(outputs, options, forcedStarts,
          exactRuns.of(outputs, options, prices));
      forcedStarts = exactStarts;
    }
    List<List<Integer>> groups = new ArrayList<>();
    List<Integer> current = new ArrayList<>();
    // The prefixes the closed groups compute. Under `materializeChronoPrefix` a later group
    // loads such a prefix rather than recomputing it, and weighs it so (VARKA-198); the set is
    // shared by every GroupOps of this partition and grows as groups close.
    Set<VarkaVectorIR> earlier = new HashSet<>();
    boolean materialize = options.materializeChronoPrefix() && options.methodByteBudget() > 0;
    // Under `predictGrouping` each candidate group is also priced by the emit cost model, in the
    // units the byte and call-site budgets are read in after the build (see `VARKA-199.md`).
    boolean predict = options.predictGrouping() && options.methodByteBudget() > 0;
    Function<VarkaVectorIR.LaneType, VarkaEmitCost.Tally> newTally =
        record != null ? lane -> new VarkaEmitCost.Tally(lane, record.prices(), record.keepCounts())
            : predict ? lane -> new VarkaEmitCost.Tally(lane, VarkaEmitCostTable.PRICES, false)
            : null;
    VarkaVectorIR.LaneType lane = VarkaVectorIR.emissionLane(outputs.get(0));
    Supplier<GroupOps> newGroup = () -> new GroupOps(options.shareChronoPrefix(), materialize,
        earlier, lane, newTally == null ? null : newTally.apply(lane));
    GroupOps group = newGroup.get();
    for (int o = 0; o < outputs.size(); o++) {
      Admission step = admit(group.copy(), outputs.get(o), current.size(), options, predict,
          materialize, earlier, lane);
      GroupOps withNext = step.withNext();
      // marginal == 0 means this output adds no node the group does not already have - it
      // is structurally the same tree - so splitting it off cannot reduce the method's op
      // count and only costs it the CSE. That matters once a node can outweigh the budget on
      // its own: after one calendar output `ops` already exceeds it, so without this test
      // `SELECT year(d) AS a, year(d) AS b` would emit the decomposition twice.
      if (!current.isEmpty() && (closes(step, options) || forcedStarts.contains(o))) {
        groups.add(current);
        if (record != null) {
          record.tallies().add(group.tally);
        }
        current = new ArrayList<>();
        earlier.addAll(group.prefixes);
        withNext = newGroup.get();
        withNext.add(outputs.get(o));
      }
      current.add(o);
      group = withNext;
    }
    groups.add(current);
    if (record != null) {
      record.tallies().add(group.tally);
    }
    if (exactStarts != null && groups.size() != exactStarts.size()) {
      throw new IllegalStateException("the greedy walk formed " + groups.size() + " groups "
          + "where the best partition has " + exactStarts.size() + ": the walk and the best "
          + "partition judged a step apart");
    }
    return groups;
  }

  /**
   * The first output of each group of the best partition of {@code outputs} in their order, for
   * {@link VarkaEmitOptions#exactGrouping}: of the partitions whose every group the rule admits
   * ({@link #admit}) and in which each of {@code forcedStarts} begins a group, the one with the
   * fewest ops, then the fewest groups, then the longest first group, the longest second group
   * and so on. The last criterion is the greedy walk's own, so where the greedy partition is
   * already the best it is the one chosen, and the option changes only the shapes it improves.
   *
   * <p>{@code runs} holds the op total of every run the rule admits from each start
   * ({@link ExactRuns}); a forced start only cuts them, since a run may not cross one. The best
   * partition of the outputs from a start is the best, over its runs, of the run plus the best
   * partition of what follows it, chosen from the last start backward. See
   * {@code VARKA-200.md}.
   */
  private static Set<Integer> bestPartitionStarts(List<VarkaVectorIR> outputs,
      VarkaEmitOptions options, Set<Integer> forcedStarts, int[][] runs) {
    int n = outputs.size();
    long[] bestOps = new long[n + 1];
    int[] bestGroups = new int[n + 1];
    int[] next = new int[n + 1];
    // The first forced start after each output, which no run from it may reach.
    int cut = n;
    for (int i = n - 1; i >= 0; i--) {
      bestOps[i] = Long.MAX_VALUE;
      int longest = Math.min(runs[i].length, cut - i);
      for (int k = 0; k < longest; k++) {
        int j = i + k + 1;
        long ops = runs[i][k] + bestOps[j];
        int groups = 1 + bestGroups[j];
        // A tie goes to the longer run, which is the greedy walk's choice.
        if (ops < bestOps[i] || (ops == bestOps[i] && groups <= bestGroups[i])) {
          bestOps[i] = ops;
          bestGroups[i] = groups;
          next[i] = j;
        }
      }
      if (forcedStarts.contains(i)) {
        cut = i;
      }
    }
    Set<Integer> starts = new HashSet<>();
    for (int i = 0; i < n; i = next[i]) {
      starts.add(i);
    }
    return starts;
  }

  /**
   * The runs the exact grouping chooses from: for each start, the op total of every run of
   * outputs from it that the rule admits as one group ({@link #admit}), grown output by output
   * until the rule refuses the next. Priced once and kept while the options and the prices stay
   * the same, which they do across the regroups of one emission; only the forced starts change
   * there, and they cut runs rather than change them.
   *
   * <p>A group's ops depend on the outputs in it and on the dates the outputs before it
   * decompose - some earlier group computes each, whatever the partition before it - and on
   * nothing after it, which is what makes one price per run right in every partition. The work
   * is the outputs times the longest run. The rule's budgets bound a run's outputs that add ops;
   * an output that adds nothing joins whatever the budgets say, so a stretch of such outputs
   * lengthens every run that reaches it, and costs a step each.
   */
  private static final class ExactRuns {
    private List<VarkaVectorIR> outputs;
    private VarkaEmitOptions options;
    private Map<String, double[]> prices;
    private int[][] runs;

    int[][] of(List<VarkaVectorIR> outputs, VarkaEmitOptions options,
        Map<String, double[]> prices) {
      if (runs == null || outputs != this.outputs || !options.equals(this.options)
          || prices != this.prices) {
        this.outputs = outputs;
        this.options = options;
        this.prices = prices;
        this.runs = price(outputs, options, prices);
      }
      return runs;
    }

    private static int[][] price(List<VarkaVectorIR> outputs, VarkaEmitOptions options,
        Map<String, double[]> prices) {
      int n = outputs.size();
      boolean materialize = options.materializeChronoPrefix() && options.methodByteBudget() > 0;
      boolean predict = options.predictGrouping() && options.methodByteBudget() > 0;
      VarkaVectorIR.LaneType lane = VarkaVectorIR.emissionLane(outputs.get(0));
      // Every output before the current start, walked as one group: its prefixes are what a
      // group starting there finds computed earlier.
      GroupOps before = new GroupOps(options.shareChronoPrefix(), materialize, new HashSet<>(),
          lane, null);
      int[][] runs = new int[n][];
      int[] scratch = new int[n];
      for (int i = 0; i < n; i++) {
        Set<VarkaVectorIR> earlier = before.prefixes;
        GroupOps run = new GroupOps(options.shareChronoPrefix(), materialize, earlier, lane,
            predict ? new VarkaEmitCost.Tally(lane, prices, false) : null);
        int length = 0;
        for (int o = i; o < n; o++) {
          Admission step = admit(run, outputs.get(o), o - i, options, predict, materialize,
              earlier, lane);
          // As in the greedy walk, an output that adds nothing joins whatever the budgets say.
          if (o > i && closes(step, options)) {
            break;
          }
          scratch[length++] = run.ops;
        }
        runs[i] = Arrays.copyOf(scratch, length);
        before.add(outputs.get(i));
      }
      return runs;
    }
  }

  /**
   * One step of the grouping: the group with {@code output} added, how many ops the output
   * added, whether the weights' two clauses of {@link #groupOutputs} admit it
   * ({@code fitsWeights}), and whether the rule as a whole does ({@code fits}: the weights, and
   * under {@code predict} the cost model's prediction as well). The one place the rule is
   * written, so the greedy walk, the best partition ({@link #bestPartitionStarts}) and the test
   * that holds the one to the other ({@link #runsForTest}) decide admission alike.
   */
  private record Admission(GroupOps withNext, int marginal, boolean fitsWeights, boolean fits) {}

  /**
   * Whether the grouping closes its group before the output {@code step} added. An output that
   * adds nothing - a tree the group already holds - joins whatever the budgets say, since
   * splitting it off cannot reduce the method's ops and only costs it the CSE: with one
   * exception, under the plan. Its store is a call site, and it counts toward the group's width,
   * so a group of heavy outputs exempt from the call-site budget by its width can pass the
   * exemption on such outputs alone and be split after the build on what the prediction already
   * said (item 71 of {@code m8/SCOPE.md}, answered in {@code VARKA-236.md}): under
   * {@code planSize} the prediction judges that step too, so the group closes before it instead.
   */
  private static boolean closes(Admission step, VarkaEmitOptions options) {
    return !step.fits() && (step.marginal() > 0 || options.planSize());
  }

  /**
   * Adds {@code output} to {@code withNext} - the group so far, or a copy of it where the caller
   * keeps the group as it was, as the greedy walk does - and judges the step; see
   * {@link Admission}.
   */
  private static Admission admit(GroupOps withNext, VarkaVectorIR output, int groupSize,
      VarkaEmitOptions options, boolean predict, boolean materialize,
      Set<VarkaVectorIR> earlier, VarkaVectorIR.LaneType lane) {
    int before = withNext.ops;
    int marginal = withNext.add(output);
    if (marginal == 0) {
      // Every caller joins such an output whatever the budgets say, so it is not judged - except
      // under the plan where it takes the group past the width the call-site budget exempts and
      // the group is predicted over that budget (see `closes`). On call sites alone: its bytes
      // are a store's, and splitting it off for bytes would only re-emit the tree it shares.
      boolean fits = !predict || !options.planSize() || options.callSiteBudget() == 0
          || groupSize + 1 <= Math.max(1, options.heavyGroupOutputs())
          || withNext.predictedSitesWithin(options);
      return new Admission(withNext, 0, true, fits);
    }
    // What clause 2 counts as reuse. By default only a civil-from-days prefix the group already
    // computes. Under `shareWholeNodes` any node the group already holds counts too, measured as
    // what this output would cost on its own less what it actually adds - which is the prefix
    // accounting generalised, since a reused prefix is reused nodes. The gate stays `> 0`: reuse
    // opens the wider bound, its size does not.
    int reuse = withNext.saved;
    if (options.shareWholeNodes()) {
      GroupOps alone = new GroupOps(options.shareChronoPrefix(), materialize, earlier, lane,
          null);
      reuse = alone.add(output) - marginal;
    }
    boolean fitsWeights = before + marginal <= options.groupBudget()
        || (reuse > 0 && before + marginal <= options.fusedCeiling());
    boolean fits = fitsWeights && (!predict || withNext.predictedWithin(options, groupSize + 1));
    return new Admission(withNext, marginal, fitsWeights, fits);
  }

  /**
   * One run of outputs as {@link #runsForTest} reports it: its op total in the weights' units,
   * whether the greedy rule admitted every output of it into one group, and the cost model's
   * prediction of its widest method's bytes and Vector API call sites.
   */
  record RunForTest(int ops, boolean admitted, double bytes, double sites) {}

  /**
   * The runs of outputs that start at {@code from}, each one output longer than the last, with
   * what the grouping knows of each. {@code admitted} is decided through the same
   * {@link #admit} as the grouping under {@code options}, so a test that prices every
   * contiguous run to find the best partition, and holds the greedy grouping to it
   * ({@code VarkaGroupingBoundSuite}), searches a space the greedy partition is in. The runs
   * stop where the weights' clauses alone stop admitting outputs, so every group the grouping
   * forms with or without the prediction is among them; the first run, one output, is always
   * reported. The groups before {@code from} are taken to have decomposed every date the
   * outputs before it decompose, which is so under any partition of them.
   */
  static List<RunForTest> runsForTest(List<VarkaVectorIR> outputs, int from,
      VarkaEmitOptions options) {
    outputs = List.copyOf(outputs);
    boolean materialize = options.materializeChronoPrefix() && options.methodByteBudget() > 0;
    boolean predict = options.predictGrouping() && options.methodByteBudget() > 0;
    VarkaVectorIR.LaneType lane = VarkaVectorIR.emissionLane(outputs.get(0));
    GroupOps before = new GroupOps(options.shareChronoPrefix(), materialize, new HashSet<>(),
        lane, null);
    for (int o = 0; o < from; o++) {
      before.add(outputs.get(o));
    }
    Set<VarkaVectorIR> earlier = new HashSet<>(before.prefixes);
    GroupOps group = new GroupOps(options.shareChronoPrefix(), materialize, earlier, lane,
        new VarkaEmitCost.Tally(lane, VarkaEmitCostTable.PRICES, false));
    List<RunForTest> runs = new ArrayList<>();
    boolean admitted = true;
    for (int o = from; o < outputs.size(); o++) {
      Admission step = admit(group, outputs.get(o), o - from, options, predict, materialize,
          earlier, lane);
      // An output that adds nothing joins whatever the budgets say, as in groupOutputs.
      if (o > from && step.marginal() > 0) {
        if (!step.fitsWeights()) {
          break;
        }
        admitted &= step.fits();
      }
      group = step.withNext();
      double[] predicted = group.tally.predicted();
      runs.add(new RunForTest(group.ops, admitted,
          predicted == null ? Double.NaN : VarkaEmitCost.maxBytes(predicted),
          predicted == null ? Double.NaN : VarkaEmitCost.maxSites(predicted)));
    }
    return runs;
  }

  /**
   * What one loop-method group costs so far, for {@link #groupOutputs}: the distinct nodes it
   * holds, the dates whose civil-from-days prefix it computes, and the op total under the
   * split {@code CHRONO_PREFIX_WEIGHT} describes - a calendar node whose prefix the group
   * already computes adds only its tail. Under {@code predictGrouping} it also feeds the cost
   * model's {@link VarkaEmitCost.Tally} from the same walk, so the weights and the prediction
   * see one account of what the group shares.
   *
   * <p>The prefix is identified by the date it decomposes ({@link #chronoChild}), which is the
   * dense body's {@link FragmentKey}; the masked body's key also carries the node's validity word,
   * so a group may hold two calendar outputs whose masked bodies do not share (the column-count
   * {@code add_months} beside {@code month(d)}) while the dense body and the epilogue do. Grouping
   * on the child alone is the conservative side of that: the shape is correct either way, and a
   * share the masked body misses is a missed win, never a wrong grouping.
   */
  private static final class GroupOps {
    private final boolean sharePrefix;
    /** Whether a prefix an earlier group computes is loaded here rather than recomputed. */
    private final boolean materialize;
    /** The prefixes the earlier groups compute; shared with them, read here. */
    private final Set<VarkaVectorIR> earlier;
    /** The kernel's lane, which the tally prices a store in. */
    private final VarkaVectorIR.LaneType lane;
    private final Set<VarkaVectorIR> nodes;
    private final Set<VarkaVectorIR> prefixes;
    /** The group's op total. */
    int ops;
    /** How many ops the last {@link #add} skipped by reusing prefixes the group already
     * computed; zero for an output that reuses none. */
    int saved;
    /** The group's features for the emit cost model, or null when no prediction is asked. */
    final VarkaEmitCost.Tally tally;

    GroupOps(boolean sharePrefix, boolean materialize, Set<VarkaVectorIR> earlier,
        VarkaVectorIR.LaneType lane, VarkaEmitCost.Tally tally) {
      this(sharePrefix, materialize, earlier, lane, new HashSet<>(), new HashSet<>(), 0, tally);
    }

    private GroupOps(boolean sharePrefix, boolean materialize, Set<VarkaVectorIR> earlier,
        VarkaVectorIR.LaneType lane, Set<VarkaVectorIR> nodes, Set<VarkaVectorIR> prefixes,
        int ops, VarkaEmitCost.Tally tally) {
      this.sharePrefix = sharePrefix;
      this.materialize = materialize;
      this.earlier = earlier;
      this.lane = lane;
      this.nodes = nodes;
      this.prefixes = prefixes;
      this.ops = ops;
      this.tally = tally;
    }

    GroupOps copy() {
      return new GroupOps(sharePrefix, materialize, earlier, lane, new HashSet<>(nodes),
          new HashSet<>(prefixes), ops, tally == null ? null : tally.copy());
    }

    /**
     * Whether the cost model predicts every method of this group of {@code outputs} outputs
     * within the budgets the built class will be measured against: the byte budget always, the
     * call-site budget only for a group wider than {@code heavyGroupOutputs}, as the regroup
     * applies them, and only while it is in force - {@code options} is what {@link #emit}
     * groups with, whose call-site budget is 0 once the budget has been dropped. True when the
     * model cannot price a feature: the weights and the measurement then decide alone.
     */
    boolean predictedWithin(VarkaEmitOptions options, int outputs) {
      double[] predicted = tally.predicted();
      if (predicted == null) {
        return true;
      }
      // Under the plan the budgets are kept with the fit's margins to spare, so a method the
      // prices under-predict by as much as they did on their own groups still measures within
      // them (VARKA-236).
      double bytesMargin = options.planSize() ? VarkaEmitCostTable.BYTES_MARGIN : 0;
      if (VarkaEmitCost.maxBytes(predicted) > options.methodByteBudget() * (1 - bytesMargin)) {
        return false;
      }
      return options.callSiteBudget() == 0 || outputs <= Math.max(1, options.heavyGroupOutputs())
          || predictedSitesWithin(options);
    }

    /** Whether the cost model predicts this group's call sites within the budget, as above. */
    boolean predictedSitesWithin(VarkaEmitOptions options) {
      double[] predicted = tally.predicted();
      double sitesMargin = options.planSize() ? VarkaEmitCostTable.SITES_MARGIN : 0;
      return predicted == null
          || VarkaEmitCost.maxSites(predicted) <= options.callSiteBudget() * (1 - sitesMargin);
    }

    /**
     * What this group pays for a prefix the first time one of its nodes needs it: the loads of
     * its vectors where an earlier group materializes it, the decomposition otherwise.
     */
    private int prefixCost(VarkaVectorIR date) {
      return materialize && earlier.contains(date) ? CHRONO_PREFIX_LOAD_WEIGHT
          : CHRONO_PREFIX_WEIGHT;
    }

    /** Adds the output's distinct nodes; returns how many ops were new, and leaves in
     * {@link #saved} how many the output skipped by reusing a prefix already here. */
    int add(VarkaVectorIR root) {
      saved = 0;
      int before = ops;
      if (tally != null) {
        tally.output(root, lane);
      }
      walk(root);
      return ops - before;
    }

    private void walk(VarkaVectorIR node) {
      if (!nodes.add(node)) {
        return;
      }
      if (tally != null) {
        tally.node(node);
      }
      int weight = weightOf(node);
      if (isChrono(node)) {
        VarkaVectorIR date = chronoChild(node);
        boolean loaded = false;
        if (sharePrefix) {
          // weightOf counts the whole decomposition; the group pays it, or the loads that stand
          // in for it, once per date, and a second node over the date saves exactly that.
          loaded = materialize && earlier.contains(date);
          int cost = prefixCost(date);
          if (prefixes.add(date)) {
            weight += cost - CHRONO_PREFIX_WEIGHT;
            if (tally != null) {
              tally.prefix(loaded);
            }
          } else {
            weight -= CHRONO_PREFIX_WEIGHT;
            saved += cost;
          }
        } else if (tally != null) {
          // Unshared, every calendar node decomposes its date itself.
          tally.prefix(false);
        }
        if (tally != null && !loaded && tailReadsMarchMonth(node)) {
          tally.month(sharePrefix ? date : node);
        }
      }
      ops += weight;
      for (VarkaVectorIR child : childrenOf(node)) {
        walk(child);
      }
    }
  }

  /**
   * Whether {@code outputs} over {@code numInputs} kernel columns fit this emitter's
   * structural budgets ({@link #MAX_CHAIN_DEPTH} height per output, {@link #MAX_INPUTS} columns,
   * and {@link #MAX_FUSED_NODES} distinct ops across all outputs where {@code options} have no
   * byte budget), counted exactly as {@link Analysis} and {@link #emit} count them. The
   * compiler mirrors the budgets with this before accepting an entry: an over-budget shape that
   * reaches {@link #emit} fails there with an {@code IllegalArgumentException} the evaluator can
   * only turn into a silent per-batch fallback - no task-16 decline reason, and EXPLAIN still
   * claims fusion. Checked here instead, the offending entry is demoted to residual with a
   * recorded reason.
   *
   * <p>The lane agreement {@link #laneOf} demands is checked on the same terms and for the
   * same reason: one class holds one species, so outputs on different lanes are two kernels,
   * and a caller that learned that from an exception at {@code emit} would have learned it too
   * late to record why.
   */
  public static boolean fitsBudgets(java.util.List<VarkaVectorIR> outputs, int numInputs,
      VarkaEmitOptions options) {
    boolean capOps = options.methodByteBudget() == 0;
    if (numInputs > MAX_INPUTS || outputs.isEmpty()) {
      return false;
    }
    for (VarkaVectorIR output : outputs) {
      if (VarkaVectorIR.emissionLane(output) != VarkaVectorIR.emissionLane(outputs.get(0))) {
        return false;
      }
    }
    java.util.HashMap<VarkaVectorIR, Integer> heights = new java.util.HashMap<>();
    int[] opNodes = {0};
    for (VarkaVectorIR root : outputs) {
      if (budgetWalk(root, heights, opNodes) > MAX_CHAIN_DEPTH
          || (capOps && opNodes[0] > MAX_FUSED_NODES)) {
        return false;
      }
    }
    return true;
  }

  /**
   * How the bitmap pass classified the value roots of this shape under
   * {@code options}: {@code [served, declined]}, where a declined root is one whose word is a
   * pure expression that mixes AND and OR, which no chain of one operator can fold into the
   * destination. Every other unserved root - a computed word, a {@code Cond}, the whole shape
   * with {@link VarkaEmitOptions#validityByBitmap} off - is in neither count.
   *
   * <p>This is the safety net VARKA-70.md 3.1 and 5 promise and the counter alone did not
   * provide: with only an increment inside a private class, a regression that stopped serving
   * every root would revert the whole lowering to the per-group path and pass every test, since
   * the byte-identity tests compare the two settings (equal when nothing is served), the
   * differential compares against a reference evaluator (the per-group path is correct), and
   * the size assertions are upper bounds. The suite pins both numbers per shape instead.
   *
   * <p>Runs the analysis and nothing else - no bytes are emitted, so it is safe to call on a
   * shape whose emission would exceed a budget.
   */
  public static int[] bitmapPassCounts(List<VarkaVectorIR> outputs, int numInputs,
      int numLiterals, VarkaEmitOptions options) {
    Analysis analysis = analyze(List.copyOf(outputs), numInputs, numLiterals, options);
    int served = 0;
    for (BitmapPass pass : analysis.served) {
      if (pass != null) {
        served++;
      }
    }
    return new int[] {served, analysis.declinedBitmapRoots};
  }

  /** The height of {@code node}, memoized per distinct node like {@code Analysis.height}. */
  private static int budgetWalk(VarkaVectorIR node,
      java.util.HashMap<VarkaVectorIR, Integer> heights, int[] opNodes) {
    Integer memo = heights.get(node);
    if (memo != null) {
      return memo;
    }
    int height;
    if (node instanceof ColumnRef || node instanceof LiteralSlot) {
      height = 0;
    } else {
      opNodes[0]++;
      int maxChild = 0;
      for (VarkaVectorIR child : childrenOf(node)) {
        maxChild = Math.max(maxChild, budgetWalk(child, heights, opNodes));
      }
      height = 1 + maxChild;
    }
    heights.put(node, height);
    return height;
  }

  /**
   * The public {@code run}: one loop-invariant test per batch - are all referenced inputs
   * null-free? - selecting {@code runDense} or {@code runMasked} (`VARKA-10.md` 2.5).
   */
  private static void emitDispatch(CodeBuilder cb, ClassDesc classDesc, Analysis analysis) {
    if (analysis.hasScratch()) {
      // The bodies address the scratch without a check of their own, so a zero is refused here,
      // once per batch, instead of faulting in a loop method. An empty batch touches no scratch
      // and returns first, as the drivers return on it, so a caller may pass a zero for it.
      Label nonEmpty = cb.newLabel();
      cb.iload(analysis.lane.pLength);
      cb.ifgt(nonEmpty);
      cb.loadConstant(0);
      cb.ireturn();
      cb.labelBinding(nonEmpty);
      Label given = cb.newLabel();
      cb.lload(analysis.scratchParam());
      cb.loadConstant(0L);
      cb.lcmp();
      cb.ifne(given);
      VarkaBodyEmitter.emitThrow(cb, ClassDesc.of("java.lang.IllegalArgumentException"),
          classDesc.displayName() + " needs " + analysis.scratchBytesPerRow()
              + " bytes of scratch per row; the address passed is zero");
      cb.labelBinding(given);
    }
    if (analysis.nullsFromValidInputs) {
      // No dense path for this kernel (see emit): every batch is served by the masked methods.
      invokeBody(cb, classDesc, VarkaMethodNames.driver(false), analysis);
      return;
    }
    Label masked = cb.newLabel();
    boolean anyColumns = analysis.referencedColumns != 0;
    for (int i = 0; i < analysis.numInputs; i++) {
      if (referenced(analysis, i)) {
        cb.aload(P_NULL_COUNT);
        cb.loadConstant(i);
        cb.iaload();
        cb.ifne(masked);
      }
    }
    invokeBody(cb, classDesc, VarkaMethodNames.driver(true), analysis);
    if (anyColumns) {
      cb.labelBinding(masked);
      invokeBody(cb, classDesc, VarkaMethodNames.driver(false), analysis);
    }
    // With no referenced columns the masked label is never targeted and must not be bound:
    // unreachable code has no stack frame to compute.
  }

  /**
   * {@link VarkaBodyEmitter#invokeCall} whose status becomes this method's own - a tail call in
   * effect.
   */
  private static void invokeBody(CodeBuilder cb, ClassDesc classDesc, String name,
      Analysis analysis) {
    invokeCall(cb, classDesc, name, analysis);
    cb.ireturn();
  }

}
