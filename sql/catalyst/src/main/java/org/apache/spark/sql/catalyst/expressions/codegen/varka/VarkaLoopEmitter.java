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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;

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
 * all-true mask (see {@code PLAN_TASK_10.md}). Truth lives in the <i>validity words</i>
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
 * measures 8x the lanewise-DIV variant that x86 scalarizes (see {@code PLAN_TASK_11.md}): fold
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
    if (outputs.isEmpty()) {
      throw new IllegalArgumentException("no output chains to emit");
    }
    if (numInputs < 1 || numInputs > MAX_INPUTS) {
      throw new IllegalArgumentException(
          "numInputs " + numInputs + " outside [1, " + MAX_INPUTS + "]");
    }
    if (options == null) {
      // Checked beside the others rather than left to fail as a bare NPE deep in the walk;
      // VarkaShapeKey rejects a null the same way, so this closes the other door in.
      throw new IllegalArgumentException("emit options must not be null");
    }
    Analysis analysis = new Analysis(numInputs, numLiterals, options, laneOf(outputs));
    for (VarkaVectorIR root : outputs) {
      analysis.analyzeRoot(root);
    }
    analysis.collectArmContexts(outputs);
    analysis.collectGuardedProducers();
    analysis.planWordAlgebra();
    analysis.planBitmapPass(outputs);

    // Method layout, all sharing the seven-parameter shape so slots line up everywhere: `run`
    // dispatches per batch to a dense or masked *driver*; the driver zeroes the output validity,
    // takes the all-null shortcut, then calls one sibling *loop* method per output group (within
    // GROUP_BUDGET, or FUSED_CEILING where the group's outputs share a calendar prefix - see
    // groupOutputs) and finally the *epilogue*. Separate methods, not one big one: each gets its
    // own C2 compilation, so no method's node and inlining budgets can starve another's
    // intrinsics (measured 3x to 4x; see `PLAN_TASK_10.md`).
    //
    // The epilogue is one method per group beside its loop method, `epilogueDense<g>` and
    // `epilogueMasked<g>`; with methodByteBudget 0, the form before task 87, it is one method
    // for every output. One method was the right shape while GROUP_BUDGET was the only bound:
    // the epilogue runs once per batch, so a hot method's C2 cost had nothing to bound there.
    // It is the wrong shape for the JVM's size limit, which reads bytes rather than heat: a
    // single epilogue carries every output's tail and crosses HugeMethodLimit at thirteen
    // make_date outputs, after which it is never compiled at all (`PLAN_TASK_87.md` 2.2).
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
    ClassDesc classDesc = ClassDesc.of(className);
    String source = sourceFile != null
        ? sourceFile : className.substring(className.lastIndexOf('.') + 1) + ".java";
    VarkaDebugInfo debugInfo = new VarkaDebugInfo(
        "outputs=" + VarkaBodyEmitter.renderOutputs(outputs) + ", numInputs=" + numInputs
            + ", numLiterals=" + numLiterals,
        planFragment != null ? planFragment : "",
        VarkaBodyEmitter.renderLineMap(analysis));
    int budget = options.methodByteBudget();
    Set<Integer> forcedStarts = new HashSet<>();
    while (true) {
      List<List<Integer>> groups = groupOutputs(outputs, options, forcedStarts);
      byte[] bytes = build(classDesc, source, debugInfo, outputs, analysis, numLiterals, groups,
          budget > 0);
      if (budget == 0) {
        return bytes;
      }
      VarkaEmittedClass measured = VarkaEmittedClass.measure(bytes);
      SortedMap<Integer, Map.Entry<String, Integer>> over = groupsOver(measured, budget);
      boolean split = false;
      List<Integer> stuck = new ArrayList<>();
      for (int g : over.keySet()) {
        List<Integer> group = groups.get(g);
        if (group.size() > 1) {
          forcedStarts.add(group.get(group.size() / 2));
          split = true;
        } else {
          stuck.add(group.get(0));
        }
      }
      if (split) {
        continue;
      }
      List<String> findings = overLimits(measured, budget);
      if (findings.isEmpty()) {
        return bytes;
      }
      throw new VarkaEmitDeclined(String.join("; ", findings)
          + (stuck.isEmpty() ? "" : "; output" + (stuck.size() == 1 ? " " : "s ") + stuck
              + " cannot be regrouped smaller"), stuck);
    }
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
          .withMethodBody("run", analysis.lane.runDesc, AccessFlag.PUBLIC.mask(),
              (CodeBuilder cb) -> emitDispatch(cb, classDesc, analysis));
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
    String side = dense ? "Dense" : "Masked";
    b.withMethodBody("run" + side, analysis.lane.runDesc, AccessFlag.PRIVATE.mask(),
        (CodeBuilder cb) -> VarkaBodyEmitter.emitBody(cb, dense, BodyMode.DRIVER, -1,
            classDesc, outputs, analysis, numLiterals, groups));
    if (!epiloguePerGroup) {
      b.withMethodBody("epilogue" + side, analysis.lane.runDesc, AccessFlag.PRIVATE.mask(),
          (CodeBuilder cb) -> VarkaBodyEmitter.emitBody(cb, dense, BodyMode.EPILOGUE, -1,
              classDesc, outputs, analysis, numLiterals, groups));
    }
    for (int g = 0; g < groups.size(); g++) {
      final int group = g;
      b.withMethodBody("loop" + side + g, analysis.lane.runDesc, AccessFlag.PRIVATE.mask(),
          (CodeBuilder cb) -> VarkaBodyEmitter.emitBody(cb, dense, BodyMode.LOOP, group,
              classDesc, outputs, analysis, numLiterals, groups));
      if (epiloguePerGroup) {
        b.withMethodBody("epilogue" + side + g, analysis.lane.runDesc, AccessFlag.PRIVATE.mask(),
            (CodeBuilder cb) -> VarkaBodyEmitter.emitBody(cb, dense, BodyMode.EPILOGUE, group,
                classDesc, outputs, analysis, numLiterals, groups));
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
   * winning by 1.3x - see {@code PLAN_TASK_32.md} 7.6), so it stays {@code GROUP_BUDGET} 's own
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
   */
  private static List<List<Integer>> groupOutputs(List<VarkaVectorIR> outputs,
      VarkaEmitOptions options) {
    return groupOutputs(outputs, options, Set.of());
  }

  /**
   * As above, with {@code forcedStarts}: outputs that begin a new group whatever the weights
   * say. The byte-budget regroup in {@link #emit} adds the middle output of a group whose
   * methods measured over the budget, so the split halves a group and never reorders one.
   */
  private static List<List<Integer>> groupOutputs(List<VarkaVectorIR> outputs,
      VarkaEmitOptions options, Set<Integer> forcedStarts) {
    List<List<Integer>> groups = new ArrayList<>();
    List<Integer> current = new ArrayList<>();
    GroupOps group = new GroupOps(options.shareChronoPrefix());
    for (int o = 0; o < outputs.size(); o++) {
      GroupOps withNext = group.copy();
      int marginal = withNext.add(outputs.get(o));
      // What clause 2 counts as reuse. By default only a civil-from-days prefix the group already
      // computes. Under `shareWholeNodes` any node the group already holds counts too, measured as
      // what this output would cost on its own less what it actually adds - which is the prefix
      // accounting generalised, since a reused prefix is reused nodes. The gate stays `> 0`: reuse
      // opens the wider bound, its size does not.
      int reuse = withNext.saved;
      if (options.shareWholeNodes()) {
        GroupOps alone = new GroupOps(options.shareChronoPrefix());
        reuse = alone.add(outputs.get(o)) - marginal;
      }
      boolean fits = group.ops + marginal <= options.groupBudget()
          || (reuse > 0 && group.ops + marginal <= options.fusedCeiling());
      // marginal == 0 means this output adds no node the group does not already have - it
      // is structurally the same tree - so splitting it off cannot reduce the method's op
      // count and only costs it the CSE. That matters once a node can outweigh the budget on
      // its own: after one calendar output `ops` already exceeds it, so without this test
      // `SELECT year(d) AS a, year(d) AS b` would emit the decomposition twice.
      if (!current.isEmpty() && ((marginal > 0 && !fits) || forcedStarts.contains(o))) {
        groups.add(current);
        current = new ArrayList<>();
        withNext = new GroupOps(options.shareChronoPrefix());
        withNext.add(outputs.get(o));
      }
      current.add(o);
      group = withNext;
    }
    groups.add(current);
    return groups;
  }

  /**
   * What one loop-method group costs so far, for {@link #groupOutputs}: the distinct nodes it
   * holds, the dates whose civil-from-days prefix it computes, and the op total under the
   * split {@code CHRONO_PREFIX_WEIGHT} describes - a calendar node whose prefix the group
   * already computes adds only its tail.
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
    private final Set<VarkaVectorIR> nodes;
    private final Set<VarkaVectorIR> prefixes;
    /** The group's op total. */
    int ops;
    /** How many ops the last {@link #add} skipped by reusing prefixes the group already
     * computed; zero for an output that reuses none. */
    int saved;

    GroupOps(boolean sharePrefix) {
      this(sharePrefix, new HashSet<>(), new HashSet<>(), 0);
    }

    private GroupOps(boolean sharePrefix, Set<VarkaVectorIR> nodes,
        Set<VarkaVectorIR> prefixes, int ops) {
      this.sharePrefix = sharePrefix;
      this.nodes = nodes;
      this.prefixes = prefixes;
      this.ops = ops;
    }

    GroupOps copy() {
      return new GroupOps(sharePrefix, new HashSet<>(nodes), new HashSet<>(prefixes), ops);
    }

    /** Adds the output's distinct nodes; returns how many ops were new, and leaves in
     * {@link #saved} how many the output skipped by reusing a prefix already here. */
    int add(VarkaVectorIR root) {
      saved = 0;
      int before = ops;
      walk(root);
      return ops - before;
    }

    private void walk(VarkaVectorIR node) {
      if (!nodes.add(node)) {
        return;
      }
      int weight = weightOf(node);
      if (sharePrefix && isChrono(node) && !prefixes.add(chronoChild(node))) {
        weight -= CHRONO_PREFIX_WEIGHT;
        saved += CHRONO_PREFIX_WEIGHT;
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
   * <p>This is the safety net PLAN_TASK_70.md 3.1 and 5 promise and the counter alone did not
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
    Analysis analysis = new Analysis(numInputs, numLiterals, options, laneOf(outputs));
    for (VarkaVectorIR root : outputs) {
      analysis.analyzeRoot(root);
    }
    analysis.collectArmContexts(outputs);
    analysis.collectGuardedProducers();
    analysis.planWordAlgebra();
    analysis.planBitmapPass(outputs);
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
   * null-free? - selecting {@code runDense} or {@code runMasked} (`PLAN_TASK_10.md` 2.5).
   */
  private static void emitDispatch(CodeBuilder cb, ClassDesc classDesc, Analysis analysis) {
    if (analysis.nullsFromValidInputs) {
      // No dense path for this kernel (see emit): every batch is served by the masked methods.
      invokeBody(cb, classDesc, "runMasked", analysis.lane);
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
    invokeBody(cb, classDesc, "runDense", analysis.lane);
    if (anyColumns) {
      cb.labelBinding(masked);
      invokeBody(cb, classDesc, "runMasked", analysis.lane);
    }
    // With no referenced columns the masked label is never targeted and must not be bound:
    // unreachable code has no stack frame to compute.
  }

  /**
   * {@link VarkaBodyEmitter#invokeCall} whose status becomes this method's own - a tail call in
   * effect.
   */
  private static void invokeBody(CodeBuilder cb, ClassDesc classDesc, String name, Lane lane) {
    invokeCall(cb, classDesc, name, lane);
    cb.ireturn();
  }

}
