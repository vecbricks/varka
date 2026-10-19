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

import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.childrenOf;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaChronoLowering.chronoChild;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaChronoLowering.tailReadsMarchMonth;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.Lane.emitLanes;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitBudget.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDivisionLowering.Divider;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddDays;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddMonths;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.And;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitCount;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BoundedDivide;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConstDivide;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DateDiff;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DayOfMonth;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DayOfWeek;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DayOfWeekIso;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DayOfYear;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Greatest;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.GuardedDay;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.GuardedRange;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.InRanges;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntNeg;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IsNotNull;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LastDay;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Least;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.MakeDate;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Month;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NextDay;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Not;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Or;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Quarter;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.SubDays;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ThursdayOf;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.TruncDate;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.TruncDateDynamic;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WeekDay;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WeekOfYear;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Year;

/**
 * What the emitter knows about a shape before it emits a byte: the validation of every node
 * against the emission's lane and the limits, the DAG walk that orders and counts the nodes,
 * the validity-word algebra (which word each node's validity is, and which are pure functions
 * of input bitmaps), the bitmap pass that serves whole bitmaps once per batch, the arm chains
 * a guard is qualified by, and the guarded producers. The passes run in the order
 * {@code VarkaLoopEmitter.emit} calls them; the fields are what the body emitters read.
 */
final class Analysis {

  // ---------------------------------------------------------------------------------------------
  // The validity-word algebra.
  // ---------------------------------------------------------------------------------------------

  /**
   * Which word a value node's validity <i>is</i>, stated before any slot exists: one input's bitmap
   * word, the all-true constant a literal-only subtree has, or a word the node computes into a slot
   * of its own. This is {@code Slots.planWordRef} in symbolic form - the same aliasing rules over
   * names instead of slot numbers - and {@link Slots#plan} asserts the two agree on every node it
   * plans, so the algebra {@link WordExpr} states cannot drift from the emission it describes.
   * The bitmap pass and the word-liveness rule both read this map rather than the slot numbers,
   * because they have to decide things before the slots are assigned.
   */
  sealed interface WordOwner {
    record Input(int ordinal) implements WordOwner {}
    record Own(VarkaVectorIR node) implements WordOwner {}
    enum Const implements WordOwner { ALL_TRUE }
  }

  /**
   * The bitmap expression a value node's validity word denotes, where that word is a pure function
   * of the inputs' bitmaps: an input's bitmap, the all-true constant, the AND of two (the
   * null-intolerant rule) or the OR of two ({@code greatest}/{@code least}'s null-skipping rule). A
   * node whose word is computed from something other than input bitmaps - {@code IfElse}'s blend by
   * the known-true mask, {@code make_date}'s validity test, a {@code Cond} - has no expression
   * here. Folded as it is built: all-true is the identity of AND and the annihilator of OR, and
   * equal operands collapse, which is exactly the folding {@code Slots.andRef} does on slots, so
   * the two views name the same thing.
   */
  sealed interface WordExpr {
    record Input(int ordinal) implements WordExpr {}
    enum Const implements WordExpr { ALL_TRUE }
    record And(WordExpr a, WordExpr b) implements WordExpr {}
    record Or(WordExpr a, WordExpr b) implements WordExpr {}
  }

  /**
   * A served root's word expression flattened for the driver: the operator and the distinct
   * input ordinals it ranges over, in first-appearance order. An empty list is the constant
   * (the root is valid on every row, {@code setValid}); one ordinal is a copy; more is a chain
   * of the operator. {@code op} is meaningless below two ordinals.
   */
  // An array on purpose: no pass is ever compared or used as a key, so the identity equality an
  // array component gives the record is never observed.
  @SuppressWarnings("ArrayRecordComponent")
  record BitmapPass(boolean and, int[] ordinals) {
    /** Null where the expression mixes AND and OR - see {@link Analysis#planBitmapPass}. */
    static BitmapPass of(WordExpr pure) {
      java.util.LinkedHashSet<Integer> ordinals = new java.util.LinkedHashSet<>();
      Boolean and = flatten(pure, null, ordinals);
      if (and == null && !(pure instanceof WordExpr.Input || pure == WordExpr.Const.ALL_TRUE)) {
        return null;
      }
      return new BitmapPass(and != null && and,
          ordinals.stream().mapToInt(Integer::intValue).toArray());
    }

    /** Collects leaves under one operator; returns that operator, or null for a leaf alone
     *  or a mixed tree (told apart by the caller from the expression's shape). */
    private static Boolean flatten(WordExpr e, Boolean op, java.util.LinkedHashSet<Integer> out) {
      switch (e) {
        case WordExpr.Input in -> {
          out.add(in.ordinal());
          return op;
        }
        case WordExpr.Const c -> {
          return op;
        }
        case WordExpr.And a -> {
          if (op != null && !op) {
            return mixed(out);
          }
          Boolean l = flatten(a.a(), Boolean.TRUE, out);
          return l == null ? null : flatten(a.b(), Boolean.TRUE, out);
        }
        case WordExpr.Or o -> {
          if (op != null && op) {
            return mixed(out);
          }
          Boolean l = flatten(o.a(), Boolean.FALSE, out);
          return l == null ? null : flatten(o.b(), Boolean.FALSE, out);
        }
      }
    }

    private static Boolean mixed(java.util.LinkedHashSet<Integer> out) {
      out.clear();
      return null;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Validation and DAG analysis.
  // ---------------------------------------------------------------------------------------------

  /**
   * One walk over the output trees, before any bytecode exists: validates every node, counts
   * uses on structural equality (the DAG view of trees the caller may have built
   * independently), computes per node the referenced-column bitset and its height, collects a
   * post-order (children-first) topological order - the line map's numbering, and the
   * schedule planSlots' validity aliasing depends on - and marks the null-skipping subtrees
   * the all-null shortcut must not reason about.
   */

  final int numInputs;
  final int numLiterals;
  /** The emission's options, carried here because Analysis already reaches every body. */
  final VarkaEmitOptions options;
  /**
   * The lane count baked into this emission, or 0 to read {@code SPECIES_PREFERRED} at run
   * time and call the general validity helpers; see {@link Lane#emitLanes}.
   */
  final int lanes;
  /**
   * The lane this emission drives its loop at ({@link VarkaVectorIR#emissionLane}): the species of
   * its loop bound, its lane count, its validity helpers and its {@code run} descriptor. One per
   * emitted class. Every node of a single-lane emission is on it, which {@link #analyze}'s refusal
   * below keeps true; a kernel that converts between lanes is driven at the long lane, and its
   * int-lane nodes are emitted at the int species of the same width ({@link #laneOf}).
   *
   * <p>It is read from the first output root, and {@link #analyze}'s refusal below is what
   * makes that safe: no node of an int-lane emission may be on the long lane, so a mixed tree is
   * rejected there rather than emitted with one lane's descriptors over the other's data.
   */
  final Lane lane;

  /**
   * The static table each {@link InRanges} node reads its bounds from, by the field name the
   * class declares it under: filled by {@code analyze}, declared and initialised by the class
   * builder, read by the emission.
   */
  final Map<InRanges, String> rangeTables = new LinkedHashMap<>();

  /** The class being built, which the range tables are fields of; set by the class builder. */
  java.lang.constant.ClassDesc owner;
  /**
   * How this emission divides by the calendar prefix's constants; see {@link Divider}. Derived
   * once here rather than at each of the fifteen division sites, and passed down to the prefix
   * helpers that do not otherwise need an {@code Analysis}.
   */
  final Divider divider;
  /**
   * The distinct nodes {@link #analyze} has visited, its memo. How often a node is used decides
   * nothing kernel-wide: a node's shared slot is decided per body, on what that body visits
   * ({@code Slots.bodyUses}, VARKA-223).
   */
  final Set<VarkaVectorIR> analyzed = new HashSet<>();
  /**
   * {@code Slots.bodyUses} per group body, keyed by the body's outputs and whether it is dense:
   * a group's loop and epilogue methods visit the same nodes, so the count is made once for both.
   * Cleared with each grouping's materialized prefixes, which the count reads.
   */
  final Map<List<Object>, Map<VarkaVectorIR, Integer>> bodyUses = new HashMap<>();
  /** The output roots: a narrowing root that nothing else reads narrows at its store. */
  final Set<VarkaVectorIR> roots = new HashSet<>();
  /** Every node some other node reads, which tells a narrowing root read elsewhere apart. */
  private final Set<VarkaVectorIR> operands = new HashSet<>();
  /** Whether the kernel converts between lanes, decided once the roots are analyzed. */
  private Boolean mixed;
  /** Set once every root is analyzed, the first pass after which {@link #mixed} may be asked. */
  private boolean rootsAnalyzed;
  /** Each referenced input's lane, which a kernel that converts between lanes maps it at. */
  private final Map<Integer, VarkaVectorIR.LaneType> inputLanes = new HashMap<>();
  /** Per distinct node, the bitset of input ordinals its subtree references. */
  final Map<VarkaVectorIR, Long> columns = new HashMap<>();
  /** Distinct nodes, children strictly before parents - the line map's numbering and
   * planSlots' schedule: a word reference planned here always sees concrete child
   * references, which the validity aliasing depends on. */
  final List<VarkaVectorIR> topoOrder = new ArrayList<>();
  /**
   * Each distinct node's 1-based position in {@link #topoOrder}, which is the line number
   * the emitted {@code LineNumberTable} attributes its instructions to. The
   * mapping from those lines back to nodes is recorded in the class's
   * {@link VarkaDebugInfo}, so a stack frame or profile sample naming
   * {@code Varka_Project_Stage3.java:7} resolves to an IR node without a live session.
   */
  final Map<VarkaVectorIR, Integer> lineNumbers = new HashMap<>();
  /** Whether the subtree holds a null-skipping node (IfElse, Greatest, Least). */
  final Map<VarkaVectorIR, Boolean> skipping = new HashMap<>();
  /**
   * Why a node refuses a batch at run time. A refusing node computes a mask of the lanes its
   * lowering cannot answer and folds it into the body's accumulator through
   * {@code VarkaVectorWalk.emitGuardCollect}, and a non-empty accumulator sends the batch to the
   * row engine with the reason's {@link #status} bit in the method's status.
   *
   * <p>A reason carries the two things the slot planner needs from it. {@code parksValue}: whether
   * its guard stores the value it tests in {@link Slots#guardTmp} while the value stays on the
   * operand stack for the parent. A reason that does not park reads its value from locals it
   * already owns, and giving it a scratch local would reserve one nothing loads and shift every
   * later local. And its {@link Family}, which says in which bodies a parking node takes its
   * scratch local.
   */
  enum Refusal {
    /**
     * A column-offset {@link AddDays} / {@link SubDays} some calendar node reads, directly or
     * through further arithmetic: its own result, against the day range the calendar lowering is
     * exact over. Insurance for a consumer, so behind {@link VarkaEmitOptions#guardDayProducers}.
     * The set is hand-picked, not derived: the compiler's {@code dayRange} and
     * {@code compileMonths} bound every other producer at compile time and decline a node they do
     * not know, so a future column-driven producer fails safe until it is taught to both sides.
     */
    DAY_PRODUCER(Family.PRODUCER, true, VarkaFusedKernel.STATUS_DAY_PRODUCER),
    /**
     * A column-count {@link AddMonths}: its month count, against the range its magic multiply is
     * exact over, wherever it sits - a bare {@code add_months(d, m)} needs it too. The node's own
     * correctness, so never behind an option (see {@link Analysis#collectRefusals}).
     */
    MONTH_COUNT(Family.SELF, true, VarkaFusedKernel.STATUS_MONTH_COUNT),
    /**
     * {@link MakeDate}: a year outside its limits, and in ANSI mode an invalid date. The node's own
     * correctness. It guards out of {@link Slots#makeDateTmp}, so it parks nothing.
     */
    MAKE_DATE(Family.SELF, false, VarkaFusedKernel.STATUS_MAKE_DATE),
    /**
     * A FAIL-mode {@link IntArith} (not {@code MUL}) or {@link IntNeg}: a lane that overflows,
     * behind {@link VarkaEmitOptions#checkIntOverflow}. The arithmetic parks its operands and
     * result in {@link Slots#intArithTmp}, and {@code emitIntNeg} reads its operand back with
     * {@code dup}, so it parks nothing here. A NULL-mode node checks too, but narrows its own word
     * instead of refusing the batch, so it has no reason.
     */
    INT_OVERFLOW(Family.CHECKED, false, VarkaFusedKernel.STATUS_INT_OVERFLOW),
    /**
     * {@link GuardedDay}: the day range, re-armed by the compiler. Unconditional, because the
     * compiler admits the expression on the strength of this check ({@code VARKA-93.md} 3.4).
     */
    DAY_RANGE(Family.REARMED, true, VarkaFusedKernel.STATUS_DAY_RANGE),
    /**
     * {@link GuardedRange}: the day guard's twin at either lane, with the bounds it carries - a
     * value the row engine throws on, or a date column outside the column bound (VARKA-379).
     */
    VALUE_RANGE(Family.REARMED, true, VarkaFusedKernel.STATUS_VALUE_RANGE);

    final Family family;
    final boolean parksValue;
    /**
     * The status bit a method returns when this reason's guard fires. Stated per reason rather
     * than derived from the ordinal, so reordering the enum moves no emitted constant.
     */
    final int status;

    Refusal(Family family, boolean parksValue, int status) {
      this.family = family;
      this.parksValue = parksValue;
      this.status = status;
    }

    /**
     * Which bodies give a parking node its scratch local. A {@link #REARMED} node takes one in
     * every body; a node of another family takes one in a body that emits some refusing node of
     * the same family, and in no driver. The rule is per family rather than per node because a
     * body's slot loop can visit nodes it does not emit - the whole kernel's, where frames are
     * not group-local ({@code groupLocalSlots} off, or the byte budget off) - and those arms'
     * bytes are what they are because the planner allocated by family before this enum existed.
     * Allocating per node there moves them; VARKA-83's review measured it.
     */
    enum Family { PRODUCER, SELF, CHECKED, REARMED }
  }

  /**
   * Every node that refuses a batch under these options, with its reason. A node is here exactly
   * when its guard is emitted, so no reader applies an option again: the slot planner allocates
   * the accumulator for a body that emits one of these, a scratch local for one whose reason
   * {@link Refusal#parksValue parks its value}, and keeps the validity word alive of every one,
   * since the collect ANDs the mask with it. Empty on a kernel with nothing to guard, which keeps
   * its slots - and so its bytes - what they would be without the guards. Filled by
   * {@link #collectRefusals()} once every root is analyzed.
   */
  final Map<VarkaVectorIR, Refusal> refusals = new HashMap<>();

  /**
   * Whether a guard sits under an {@link IsNotNull} over a condition. A dense body answers such
   * a test constant true without lowering the condition, so it would serve a batch whose values
   * the masked body's guard declines; a guard's verdict on a batch must not depend on which body
   * runs, so the kernel is dispatched to the masked methods for every batch, as for
   * {@link #nullsFromValidInputs}. Set by {@link #collectRefusals()}.
   */
  boolean guardUnderNullTest;

  /** The {@link IsNotNull} nodes over a condition, in topological order. */
  final List<IsNotNull> nullTestsOverCond = new ArrayList<>();

  /**
   * Whether the kernel has no dense body: {@link #nullsFromValidInputs}, {@link
   * #guardUnderNullTest}, or a calendar prefix {@link #materialized} for a date some calendar node
   * under an {@link IsNotNull} over a condition reads. A dense body does not lower that condition,
   * so a group that reaches the date only there would never store the prefix a later group loads.
   * Read at emission, since the materialized prefixes are planned per grouping.
   */
  boolean maskedOnly() {
    return nullsFromValidInputs || guardUnderNullTest || prefixUnderNullTest();
  }

  private boolean prefixUnderNullTest() {
    if (materialized.isEmpty() || nullTestsOverCond.isEmpty()) {
      return false;
    }
    Set<VarkaVectorIR> under = new HashSet<>();
    for (IsNotNull n : nullTestsOverCond) {
      collectReached(n.child(), under);
    }
    for (VarkaVectorIR node : under) {
      if (isChrono(node) && materialized.containsKey(chronoChild(node))) {
        return true;
      }
    }
    return false;
  }

  /**
   * for each node, the chain of {@code IfElse} arms every one of its uses sits under, innermost
   * last - or the empty list where its uses do not agree on one, which includes a use outside any
   * arm and a use in a condition. A guard on a node with a non-empty chain condemns the batch
   * only on the lanes that chain selects; a guard on a node with the empty chain condemns as it
   * always has.
   *
   * <p>Empty rather than absent is the unqualified answer, and the two are different: absent
   * means the walk has not reached the node. {@link #collectArmContexts} fills it once every
   * root is analyzed, and only {@link #armChainOf} reads it.
   */
  final Map<VarkaVectorIR, List<ArmStep>> armChain = new HashMap<>();

  /**
   * per value node, which word its validity is - see {@link WordOwner}. Filled by
   * {@link #planWordAlgebra()} once every root is analyzed; {@code Cond} nodes have no entry.
   */
  final Map<VarkaVectorIR, WordOwner> wordOwner = new HashMap<>();
  /**
   * per value node whose word is a pure function of the inputs' bitmaps, the expression it
   * denotes - see {@link WordExpr}. A value node absent here has a computed word ({@code IfElse},
   * {@code MakeDate}, or anything over one of those).
   */
  final Map<VarkaVectorIR, WordExpr> pureWord = new HashMap<>();

  /**
   * per output position, the whole-batch bitmap expression the masked driver writes for that
   * root, or null where the root keeps its per-group write - a {@code Cond}, a root whose word
   * is computed, a root whose expression mixes AND and OR, or every root when
   * {@link VarkaEmitOptions#validityByBitmap} is off. Filled by {@link #planBitmapPass}.
   */
  BitmapPass[] served;
  /** How many value roots the pass declined for a mixed AND/OR tree - the safety net in
   *  VARKA-70.md 3.1, which the suite holds at zero over its fixtures. */
  int declinedBitmapRoots;

  /**
   * Whether some node turns valid inputs into a null output (non-ANSI {@code MakeDate}). The
   * dense body assumes valid in, valid out - it writes no per-lane validity - so a kernel with
   * such a node is dispatched to the masked methods for every batch; a null-free input costs the
   * masked body only a constant all-true word.
   */
  boolean nullsFromValidInputs;

  /**
   * The dates whose civil-from-days prefix outputs in two or more loop-method groups decompose,
   * under {@link VarkaEmitOptions#materializeChronoPrefix} (VARKA-198). The first such group
   * computes the prefix once per lane group and stores its vectors into a scratch region the
   * caller passes to {@code run}; the later groups load them in place of the prefix. Keyed by
   * the date, the dense fragment key's child: the values are the same whatever validity word a
   * consumer's output carries, since a masked body computes every lane group and the masks
   * inside the prefix are the carries' own. Set per grouping by {@code VarkaLoopEmitter.emit},
   * because a regroup can move a crossing; empty for every other emission, whose bytes and
   * descriptors are then unchanged.
   */
  Map<VarkaVectorIR, Materialized> materialized = Map.of();

  /**
   * How many consecutive groups each sub-driver ("stage") calls under
   * {@link VarkaEmitOptions#splitDriver}, or 0 where the driver calls every group itself. Set per
   * build by {@code VarkaLoopEmitter.emit}, from the measured driver of a build before, and 0 for
   * every emission whose drivers fit, whose bytes are then unchanged (`VARKA-190.md` 11).
   */
  int stageGroups = 0;

  /**
   * One materialized prefix: the group that computes and stores it, its region in the scratch,
   * whether any group's tail reads the March month (so the producer keeps that step where
   * {@code elideChronoMonth} would drop it), and every group that touches the region.
   */
  record Materialized(int producer, int region, boolean needsMonth, Set<Integer> groups) {}

  /** The vectors one region holds: the prefix's {@code t[0..5]}, the day count included. */
  static final int SCRATCH_VECTORS = 6;

  private final Map<VarkaVectorIR, Integer> height = new HashMap<>();
  /** The union of every node's columns: unreferenced inputs get no locals and no state. */
  long referencedColumns = 0L;
  private int opNodes = 0;

  Analysis(int numInputs, int numLiterals, VarkaEmitOptions options, Lane lane) {
    this.numInputs = numInputs;
    this.numLiterals = numLiterals;
    this.options = options;
    this.lane = lane;
    this.lanes = emitLanes(options, lane);
    this.divider = Divider.of(this);
  }

  void analyzeRoot(VarkaVectorIR root) {
    // A Cond root is legal: it emits this output's selection bitmap into dstValidity, with the
    // dstData slot unused (see the class doc). Value positions below a root still reject
    // conditions via requireValue.
    roots.add(root);
    analyze(root);
    if (height.get(root) > MAX_CHAIN_DEPTH) {
      throw VarkaErrors.illegalArgument(
          "chain deeper than MAX_CHAIN_DEPTH=" + MAX_CHAIN_DEPTH);
    }
  }

  /**
   * The context walk: assigns every node the arm chain its uses agree on, by the rule in
   * {@code VARKA-79.md} 3.3 - narrow only where every use sits under one and the same
   * innermost arm chain, and keep the unqualified guard for anything else.
   *
   * <p>It is its own walk rather than a stack in {@link #analyze}, because that one memoises
   * on {@code analyzed} and returns on a repeated node without descending: a node used twice
   * would have its subtree's context recorded from the first use only. Here a node whose
   * recorded chain disagrees with the chain it is reached by is demoted to the empty list and
   * the demotion is pushed down its subtree, so a shared subtree under two different arms
   * ends unqualified all the way down. That terminates because a node's state only ever moves
   * unvisited -&gt; a chain -&gt; empty, so each is rewritten at most twice.
   *
   * <p>A condition's subtree is entered with the empty chain, never the enclosing one: an
   * {@code IfElse} computes its condition on every lane it is itself computed on, so a guard
   * inside a condition must stay unqualified. That is 3.2's rule and it falls out here rather
   * than being checked at the guard.
   */
  void collectArmContexts(List<VarkaVectorIR> outputs) {
    rootsAnalyzed = true;
    for (VarkaVectorIR root : outputs) {
      assignArmChain(root, List.of());
    }
  }

  private void assignArmChain(VarkaVectorIR node, List<ArmStep> chain) {
    List<ArmStep> existing = armChain.get(node);
    if (existing != null) {
      if (existing.isEmpty() || existing.equals(chain)) {
        return;
      }
      chain = List.of();
    }
    armChain.put(node, chain);
    if (node instanceof IfElse n) {
      assignArmChain(n.cond(), List.of());
      assignArmChain(n.thenNode(), extend(chain, n, true));
      assignArmChain(n.elseNode(), extend(chain, n, false));
    } else {
      for (VarkaVectorIR child : childrenOf(node)) {
        assignArmChain(child, chain);
      }
    }
  }

  private static List<ArmStep> extend(List<ArmStep> chain, IfElse node, boolean thenBranch) {
    if (chain.isEmpty()) {
      return List.of(new ArmStep(node, thenBranch));
    }
    List<ArmStep> longer = new ArrayList<>(chain);
    longer.add(new ArmStep(node, thenBranch));
    return List.copyOf(longer);
  }

  /**
   * The arm chain to qualify {@code node}'s guard by: empty where the guard stays
   * unqualified. Behind {@link VarkaEmitOptions#guardUnderArm} so the change is measurable
   * against its own absence, the way every other guard decision here is.
   */
  List<ArmStep> armChainOf(VarkaVectorIR node) {
    if (!options.guardUnderArm()) {
      return List.of();
    }
    List<ArmStep> chain = armChain.get(node);
    return chain == null ? List.of() : chain;
  }

  /**
   * Whether {@code node}'s guard checks a column's stored values, null rows included: the column
   * bound, the int-lane {@link GuardedRange} over a bare {@link ColumnRef} with the bounds
   * {@link VarkaChrono#CONTRACT_MIN_DAYS} and {@link VarkaChrono#CONTRACT_MAX_DAYS} that
   * {@code VarkaRangeAnalysis.guardColumns} wraps a date column in (VARKA-379). Every other guard
   * ANDs its condemning mask with the lanes' validity, so that a null lane's undefined value cannot
   * condemn the batch; this one does not, because turning the validity word into a lane mask costs
   * a cheap kernel more than its own work on AVX2, and a null row a Spark writer filled holds day
   * 0, inside the bound. A far day stored under a null declines the batch to the row engine, which
   * answers it: slower, never wrong ({@code VARKA-379.md} 11.3). Told by its bounds as well as its
   * shape, so that a range guard over a column with any other bounds - the IR grammar draws them,
   * over null lanes the harness poisons - keeps the qualified form, as does the long lane, where
   * the {@code TIME} family's guards sit and AVX2 has no 64-bit min or max for
   * {@link Slots#rangeAcc}.
   */
  static boolean guardsStoredValues(VarkaVectorIR node) {
    return node instanceof GuardedRange g && g.child() instanceof ColumnRef c
        && c.lane() == VarkaVectorIR.LaneType.INT
        && g.lo() == VarkaChrono.CONTRACT_MIN_DAYS && g.hi() == VarkaChrono.CONTRACT_MAX_DAYS;
  }

  /**
   * Whether {@code node}'s guard is kept as a lane-wise min and max in a loop body
   * ({@link Slots#rangeAcc}) rather than as two compares a lane group: a stored-value guard
   * ({@link #guardsStoredValues}) in a loop method, where no arm qualifies it. The epilogue, one
   * pass, keeps the compares and the bounds mask's AND; a guard under arms keeps them so its mask
   * can be ANDed with the arm's condition, which a min cannot be without a blend; and so does an
   * int-lane guard of a kernel driven at the long lane.
   */
  boolean accumulatesRange(VarkaVectorIR node, boolean loop) {
    // Not at the int lane of a kernel driven at the long one: the accumulators are the driving
    // lane's vectors, and an int vector's upper lanes, which are not the group's rows, would
    // reach the min.
    return loop && guardsStoredValues(node) && armChainOf(node).isEmpty()
        && laneOf(node) == lane;
  }

  /**
   * Fills {@link #refusals} over the topological order, one exhaustive switch deciding every kind,
   * so that a new node type has to say whether it refuses. A column-count {@link AddMonths} is a
   * {@link Refusal#MONTH_COUNT}, not a {@link Refusal#DAY_PRODUCER}: its check is on its own month
   * count, which its own magic multiply is exact only over, so it is the node's correctness rather
   * than a consumer's insurance - the {@link MakeDate} criterion exactly. It also has to be
   * unconditional because the compiler reads the guard as established fact: {@code dayRange}
   * answers {@code Bounded} for a column count, and composes that interval with whatever shifts
   * it, without being able to see {@link VarkaEmitOptions#guardDayProducers}. Behind the option
   * the guard would vanish while the compile-time bound stayed, which is a wrong answer rather
   * than a slower one.
   */
  void collectRefusals() {
    // The nodes some calendar node reads, directly or through further arithmetic: where a
    // column-offset day producer is one a consumer relies on. Each node is walked once.
    Set<VarkaVectorIR> underChrono = new HashSet<>();
    if (options.guardDayProducers()) {
      for (VarkaVectorIR node : topoOrder) {
        if (isChrono(node)) {
          collectReached(chronoChild(node), underChrono);
        }
      }
    }
    for (VarkaVectorIR node : topoOrder) {
      switch (node) {
        case AddDays n -> {
          if (underChrono.contains(node) && !(n.offset() instanceof LiteralSlot)) {
            refuse(node, Refusal.DAY_PRODUCER);
          }
        }
        case SubDays n -> {
          if (underChrono.contains(node) && !(n.offset() instanceof LiteralSlot)) {
            refuse(node, Refusal.DAY_PRODUCER);
          }
        }
        case AddMonths n -> {
          if (!(n.months() instanceof LiteralSlot)) {
            refuse(node, Refusal.MONTH_COUNT);
          }
        }
        case MakeDate n -> refuse(node, Refusal.MAKE_DATE);
        // MUL is excluded for a different reason than NULL: a checked one never reaches here at
        // all, because the compiler declines it and emitIntArith throws if it ever did - so this
        // guard is defensive rather than load-bearing; the invariant is stated in three places
        // (here, the throw, and the compiler's decline) and all three must move together if it
        // is ever widened.
        case IntArith n -> {
          if (options.checkIntOverflow() && n.mode() == Overflow.FAIL && n.op() != IntOp.MUL) {
            refuse(node, Refusal.INT_OVERFLOW);
          }
        }
        case IntNeg n -> {
          if (options.checkIntOverflow() && n.mode() == Overflow.FAIL) {
            refuse(node, Refusal.INT_OVERFLOW);
          }
        }
        case GuardedDay n -> refuse(node, Refusal.DAY_RANGE);
        case GuardedRange n -> refuse(node, Refusal.VALUE_RANGE);
        // Everything else computes every lane it is given. Written out rather than defaulted.
        case ColumnRef n -> { }
        case LiteralSlot n -> { }
        case WidenLane n -> { }
        case NarrowLane n -> { }
        case NextDay n -> { }
        case TruncDateDynamic n -> { }
        case DateDiff n -> { }
        case Greatest n -> { }
        case Least n -> { }
        case IfElse n -> { }
        case DayOfWeek n -> { }
        case WeekDay n -> { }
        case DayOfWeekIso n -> { }
        case ThursdayOf n -> { }
        case Year n -> { }
        case Month n -> { }
        case DayOfMonth n -> { }
        case Quarter n -> { }
        case DayOfYear n -> { }
        case LastDay n -> { }
        case TruncDate n -> { }
        case WeekOfYear n -> { }
        case ConstDivide n -> { }
        case BoundedDivide n -> { }
        case BitOp n -> { }
        case BitCount n -> { }
        case Compare n -> { }
        case And n -> { }
        case Or n -> { }
        case Not n -> { }
        case IsNotNull n -> { }
        case InRanges n -> { }
      }
    }
    if (!refusals.isEmpty() && !nullTestsOverCond.isEmpty()) {
      Set<VarkaVectorIR> under = new HashSet<>();
      for (IsNotNull n : nullTestsOverCond) {
        collectReached(n.child(), under);
      }
      for (VarkaVectorIR node : under) {
        if (refusals.containsKey(node)) {
          guardUnderNullTest = true;
        }
      }
    }
  }

  /** Adds {@code node} and everything under it to {@code into}, each node once. */
  private static void collectReached(VarkaVectorIR node, Set<VarkaVectorIR> into) {
    if (into.add(node)) {
      for (VarkaVectorIR child : childrenOf(node)) {
        collectReached(child, into);
      }
    }
  }

  /** Records {@code node}'s reason, refusing a node that two rules would give two reasons. */
  private void refuse(VarkaVectorIR node, Refusal reason) {
    Refusal had = refusals.put(node, reason);
    if (had != null && had != reason) {
      throw VarkaErrors.illegalState(node + " refuses for both " + had + " and " + reason);
    }
  }

  private static void requireValue(VarkaVectorIR node, String position) {
    if (node instanceof Cond) {
      throw VarkaErrors.illegalArgument(
          "condition node " + node + " in a value position (" + position + ")");
    }
  }

  /**
   * Fills {@link #wordOwner} and {@link #pureWord} over the topological order, children
   * first, so every node sees its children's entries. The owner rules are
   * {@code Slots.planWordRef}'s, case for case; the purity rules are the word algebra each
   * emission arm implements - AND for the null-intolerant nodes, OR for the null-skipping
   * pick, an alias for every unary node, and nothing for a node that computes its word.
   */
  void planWordAlgebra() {
    for (VarkaVectorIR node : topoOrder) {
      if (node instanceof Cond) {
        continue;
      }
      wordOwner.put(node, ownerOf(node));
      WordExpr pure = pureOf(node);
      if (pure != null) {
        pureWord.put(node, pure);
      }
    }
  }

  /**
   * Decides which prefixes {@code groups} materialize: those that two or more groups decompose,
   * under the option, the fragment sharing it builds on and the byte budget whose per-group
   * methods it needs. The producer is the first group in order, since the driver runs the loop
   * methods in group order and the epilogues after them in the same order, so a later group's
   * loads always follow the producer's stores over the same rows. A date only one group
   * decomposes is left as it is: within a group the fragment sharing already computes it once.
   */
  void planMaterialized(List<VarkaVectorIR> outputs, List<List<Integer>> groups) {
    bodyUses.clear();
    materialized = Map.of();
    // Not in a kernel that converts between lanes: the scratch is laid out by the driving lane's
    // stride, and the prefix's vectors are the int species' (`VARKA-28.md` 9).
    if (!options.materializeChronoPrefix() || !options.shareChronoPrefix()
        || options.methodByteBudget() <= 0 || groups.size() < 2 || mixed()) {
      return;
    }
    Map<VarkaVectorIR, Set<Integer>> touching = new LinkedHashMap<>();
    Map<VarkaVectorIR, Boolean> month = new HashMap<>();
    for (int g = 0; g < groups.size(); g++) {
      Set<VarkaVectorIR> seen = new HashSet<>();
      for (int o : groups.get(g)) {
        collectPrefixes(outputs.get(o), g, seen, touching, month);
      }
    }
    Map<VarkaVectorIR, Materialized> planned = new LinkedHashMap<>();
    int region = 0;
    for (Map.Entry<VarkaVectorIR, Set<Integer>> e : touching.entrySet()) {
      if (e.getValue().size() < 2) {
        continue;
      }
      int producer = java.util.Collections.min(e.getValue());
      planned.put(e.getKey(), new Materialized(producer, region++, month.get(e.getKey()),
          Set.copyOf(e.getValue())));
    }
    if (!planned.isEmpty()) {
      materialized = planned;
    }
  }

  private void collectPrefixes(VarkaVectorIR node, int group, Set<VarkaVectorIR> seen,
      Map<VarkaVectorIR, Set<Integer>> touching, Map<VarkaVectorIR, Boolean> month) {
    if (!seen.add(node)) {
      return;
    }
    if (isChrono(node)) {
      VarkaVectorIR date = chronoChild(node);
      touching.computeIfAbsent(date, k -> new java.util.TreeSet<>()).add(group);
      month.merge(date, !options.elideChronoMonth() || tailReadsMarchMonth(node),
          Boolean::logicalOr);
    }
    for (VarkaVectorIR child : childrenOf(node)) {
      collectPrefixes(child, group, seen, touching, month);
    }
  }

  boolean hasScratch() {
    return !materialized.isEmpty();
  }

  /** What {@code VarkaFusedKernel.scratchBytesPerRow()} returns for this emission. */
  int scratchBytesPerRow() {
    return materialized.size() * SCRATCH_VECTORS * (int) lane.byteStride;
  }

  int scratchParam() {
    return lane.pScratch;
  }

  /** The first local after the parameters: two further along when the scratch address is one. */
  int firstLocal() {
    return hasScratch() ? lane.firstLocalScratch : lane.firstLocal;
  }

  /** The descriptor every body method of this emission shares; see {@link Lane#runDesc}. */
  java.lang.constant.MethodTypeDesc bodyDesc() {
    return hasScratch() ? lane.runDescScratch : lane.runDesc;
  }

  // ---------------------------------------------------------------------------------------------
  // The lanes of a kernel that converts between them (VARKA-28).
  // ---------------------------------------------------------------------------------------------

  /**
   * Whether this kernel holds values of both lanes: driven at the long lane, with an int-lane
   * node that is emitted as an int vector - every int-lane node but a narrowing root that nothing
   * else reads, whose value stays in the long lane until its store. False for every single-lane
   * kernel and for a kernel whose only int-lane nodes are narrowing roots, such as the
   * {@code TIME} extracts'.
   * Asked once the roots are analyzed, and answered from then on.
   */
  boolean mixed() {
    if (mixed == null) {
      if (!rootsAnalyzed) {
        throw VarkaErrors.illegalState("the lanes are asked before every root is analyzed");
      }
      boolean any = false;
      if (lane == Lane.LONG) {
        for (VarkaVectorIR node : topoOrder) {
          any |= node.laneType() == VarkaVectorIR.LaneType.INT && !narrowsAtStore(node);
        }
      }
      mixed = any;
    }
    return mixed;
  }

  /**
   * Whether {@code node} is a narrowing that stays in the long lane until its root's store: an
   * output root that no other node reads. Anywhere else a narrowing converts where it is, since
   * its consumer computes in the int lane.
   */
  boolean narrowsAtStore(VarkaVectorIR node) {
    return node instanceof NarrowLane && roots.contains(node) && !operands.contains(node);
  }

  /**
   * The lane {@code node}'s value is emitted in, and so the descriptors and the species its
   * instructions name: the emission's own lane for every node of a single-lane kernel, and in a
   * kernel that converts between lanes the node's own, a narrowing at its store excepted.
   */
  Lane laneOf(VarkaVectorIR node) {
    if (!mixed() || narrowsAtStore(node)) {
      return lane;
    }
    return Lane.of(node.laneType());
  }

  /** The lane input {@code ordinal} is read at, which sizes its data segment. */
  Lane inputLane(int ordinal) {
    VarkaVectorIR.LaneType laneType = inputLanes.get(ordinal);
    return laneType == null ? lane : Lane.of(laneType);
  }

  /**
   * The lane count baked for {@code of}'s species, or 0 where none is: {@link #lanes} for the
   * driving lane, and for the int lane of a kernel driven at the long one the int species of the
   * same width, twice the long lanes.
   */
  int lanesOf(Lane of) {
    if (of == lane) {
      return lanes;
    }
    return lanes == 0 ? 0 : lanes * 2;
  }

  /**
   * How many literal slots {@code of}'s table has: {@link #numLiterals} in a single-lane kernel,
   * whose one table is its lane's, and in a kernel that converts between lanes one past the
   * highest slot its nodes read in that table, since each lane indexes its own.
   */
  int literalsOf(Lane of) {
    if (!mixed()) {
      return of == lane ? numLiterals : 0;
    }
    int count = 0;
    for (VarkaVectorIR node : topoOrder) {
      if (node instanceof LiteralSlot l && l.lane() == of.laneType) {
        count = Math.max(count, l.index() + 1);
      }
    }
    return count;
  }

  /**
   * Decides, per output, whether the masked driver writes that root's validity bitmap in
   * one pass over whole input bitmaps (VARKA-70.md 3.1). A root qualifies when its word
   * is a pure expression over input bitmaps that flattens to one operator: AND and OR are
   * each associative and commutative over bitmaps, so a tree of either collapses to a
   * left-leaning chain the engine's two-then-{@code Into} entry points evaluate into the
   * destination with no scratch. A tree that mixes the two - {@code datediff(greatest(d, d2),
   * greatest(d3, d4))} - needs a second live intermediate, and is declined rather than
   * served: it keeps its per-group write and its word, which is today's path and always
   * right. {@code Cond} roots are not values and are never served.
   */
  void planBitmapPass(List<VarkaVectorIR> outputs) {
    served = new BitmapPass[outputs.size()];
    if (!options.validityByBitmap()) {
      return;
    }
    for (int o = 0; o < outputs.size(); o++) {
      VarkaVectorIR root = outputs.get(o);
      if (root instanceof Cond) {
        continue;
      }
      WordExpr pure = pureWord.get(root);
      if (pure == null) {
        continue;
      }
      BitmapPass pass = BitmapPass.of(pure);
      if (pass == null) {
        declinedBitmapRoots++;
      }
      served[o] = pass;
    }
  }

  private WordOwner ownerOf(VarkaVectorIR node) {
    return switch (node) {
      case ColumnRef c -> new WordOwner.Input(c.ordinal());
      case LiteralSlot l -> WordOwner.Const.ALL_TRUE;
      case AddDays n -> andOwner(node, n.days(), n.offset());
      case SubDays n -> andOwner(node, n.days(), n.offset());
      case NextDay n -> andOwner(node, n.days(), n.offset());
      case TruncDateDynamic n -> andOwner(node, n.days(), n.level());
      case AddMonths n -> andOwner(node, n.days(), n.months());
      case DateDiff n -> andOwner(node, n.end(), n.start());
      case IntArith n -> n.mode() == Overflow.NULL
          ? new WordOwner.Own(node)
          : andOwner(node, n.left(), n.right());
      case IntNeg n -> wordOwner.get(n.child());
      case ConstDivide n -> wordOwner.get(n.child());
      case BoundedDivide n -> wordOwner.get(n.child());
      case BitOp n -> andOwner(node, n.left(), n.right());
      case BitCount n -> wordOwner.get(n.child());
      case DayOfWeek n -> wordOwner.get(n.days());
      case WeekDay n -> wordOwner.get(n.days());
      case DayOfWeekIso n -> wordOwner.get(n.days());
      case ThursdayOf n -> wordOwner.get(n.days());
      case GuardedDay n -> wordOwner.get(n.days());
      case GuardedRange n -> wordOwner.get(n.child());
      case WidenLane n -> wordOwner.get(n.child());
      case NarrowLane n -> wordOwner.get(n.child());
      case Year n -> wordOwner.get(n.days());
      case Month n -> wordOwner.get(n.days());
      case DayOfMonth n -> wordOwner.get(n.days());
      case Quarter n -> wordOwner.get(n.days());
      case DayOfYear n -> wordOwner.get(n.days());
      case LastDay n -> wordOwner.get(n.days());
      case TruncDate n -> wordOwner.get(n.days());
      case WeekOfYear n -> wordOwner.get(n.days());
      // Greatest/Least (OR), IfElse (blend) and MakeDate always compute their own word.
      default -> new WordOwner.Own(node);
    };
  }

  /** {@code Slots.andRef}'s folding over owners: all-true drops out, equal operands collapse. */
  private WordOwner andOwner(VarkaVectorIR node, VarkaVectorIR a, VarkaVectorIR b) {
    WordOwner wa = wordOwner.get(a);
    WordOwner wb = wordOwner.get(b);
    if (wa == WordOwner.Const.ALL_TRUE) {
      return wb;
    }
    if (wb == WordOwner.Const.ALL_TRUE || wa.equals(wb)) {
      return wa;
    }
    return new WordOwner.Own(node);
  }

  private WordExpr pureOf(VarkaVectorIR node) {
    return switch (node) {
      case ColumnRef c -> new WordExpr.Input(c.ordinal());
      case LiteralSlot l -> WordExpr.Const.ALL_TRUE;
      case AddDays n -> andExpr(pureWord.get(n.days()), pureWord.get(n.offset()));
      case SubDays n -> andExpr(pureWord.get(n.days()), pureWord.get(n.offset()));
      case NextDay n -> andExpr(pureWord.get(n.days()), pureWord.get(n.offset()));
      case TruncDateDynamic n -> andExpr(pureWord.get(n.days()), pureWord.get(n.level()));
      case AddMonths n -> andExpr(pureWord.get(n.days()), pureWord.get(n.months()));
      case DateDiff n -> andExpr(pureWord.get(n.end()), pureWord.get(n.start()));
      // WRAP and FAIL are a pure AND of the operands' bitmaps, so the driver's pass can
      // write such a root's validity once per batch. NULL is not a function of the input
      // bitmaps at all - its overflow mask comes from the values - which is the boundary
      // IfElse and MakeDate sit on, and the null below is what keeps it off the pass.
      case IntArith n -> n.mode() == Overflow.NULL
          ? null
          : andExpr(pureWord.get(n.left()), pureWord.get(n.right()));
      case IntNeg n -> pureWord.get(n.child());
      case ConstDivide n -> pureWord.get(n.child());
      case BoundedDivide n -> pureWord.get(n.child());
      case BitOp n -> andExpr(pureWord.get(n.left()), pureWord.get(n.right()));
      case BitCount n -> pureWord.get(n.child());
      case Greatest n -> orExpr(pureWord.get(n.left()), pureWord.get(n.right()));
      case Least n -> orExpr(pureWord.get(n.left()), pureWord.get(n.right()));
      case DayOfWeek n -> pureWord.get(n.days());
      case WeekDay n -> pureWord.get(n.days());
      case DayOfWeekIso n -> pureWord.get(n.days());
      case ThursdayOf n -> pureWord.get(n.days());
      case GuardedDay n -> pureWord.get(n.days());
      case GuardedRange n -> pureWord.get(n.child());
      case WidenLane n -> pureWord.get(n.child());
      case NarrowLane n -> pureWord.get(n.child());
      case Year n -> pureWord.get(n.days());
      case Month n -> pureWord.get(n.days());
      case DayOfMonth n -> pureWord.get(n.days());
      case Quarter n -> pureWord.get(n.days());
      case DayOfYear n -> pureWord.get(n.days());
      case LastDay n -> pureWord.get(n.days());
      case TruncDate n -> pureWord.get(n.days());
      case WeekOfYear n -> pureWord.get(n.days());
      // A blend by the known-true mask, and a word ANDed with a computed validity test.
      case IfElse n -> null;
      case MakeDate n -> null;
      default -> null;
    };
  }

  private static WordExpr andExpr(WordExpr a, WordExpr b) {
    if (a == null || b == null) {
      return null;
    }
    if (a == WordExpr.Const.ALL_TRUE) {
      return b;
    }
    if (b == WordExpr.Const.ALL_TRUE || a.equals(b)) {
      return a;
    }
    return new WordExpr.And(a, b);
  }

  private static WordExpr orExpr(WordExpr a, WordExpr b) {
    if (a == null || b == null) {
      return null;
    }
    if (a == WordExpr.Const.ALL_TRUE || b == WordExpr.Const.ALL_TRUE) {
      return WordExpr.Const.ALL_TRUE;
    }
    if (a.equals(b)) {
      return a;
    }
    return new WordExpr.Or(a, b);
  }

  private void analyze(VarkaVectorIR node) {
    // The lane rule, enforced per node. `laneOf` reads the output roots only, so this is the
    // sole defence against a node further down disagreeing - which would emit one lane's
    // descriptors over the other's data. An int-lane emission holds no long-lane node; a
    // long-lane emission may hold int-lane nodes, which the IR's constructors admit only below
    // a conversion, and which `laneOf` emits at the int species. The IR's own constructors make
    // any other mix unbuildable; this is what catches one built another way.
    if (lane == Lane.INT && node.laneType() != VarkaVectorIR.LaneType.INT) {
      throw VarkaErrors.illegalArgument("a " + node.laneType() + " node in a "
          + lane.laneType + " emission: " + node.getClass().getSimpleName());
    }
    if (!analyzed.add(node)) {
      // A repeated node: its subtree is already analyzed.
      return;
    }
    for (VarkaOperandPosition.Restricted r : VarkaOperandPosition.restrictedOperands(node)) {
      requireOperand(r.position(), r.operand(), r.name());
    }
    switch (node) {
      case ColumnRef c -> {
        if (c.ordinal() < 0 || c.ordinal() >= numInputs) {
          throw VarkaErrors.illegalArgument(
              "column ordinal " + c.ordinal() + " outside [0, " + numInputs + ")");
        }
        VarkaVectorIR.LaneType had = inputLanes.putIfAbsent(c.ordinal(), c.lane());
        if (had != null && had != c.lane()) {
          throw VarkaErrors.illegalArgument("column " + c.ordinal() + " is read at both the "
              + had + " and the " + c.lane() + " lane");
        }
        long set = 1L << c.ordinal();
        columns.put(node, set);
        height.put(node, 0);
        skipping.put(node, false);
        referencedColumns |= set;
      }
      case LiteralSlot l -> {
        if (l.index() < 0 || l.index() >= numLiterals) {
          throw VarkaErrors.illegalArgument(
              "literal slot " + l.index() + " outside [0, " + numLiterals + ")");
        }
        columns.put(node, 0L);
        height.put(node, 0);
        skipping.put(node, false);
      }
      case AddDays n -> {
        analyzeOp(node, false, n.days(), n.offset());
      }
      case SubDays n -> {
        analyzeOp(node, false, n.days(), n.offset());
      }
      case DateDiff n -> analyzeOp(node, false, n.end(), n.start());
      case DayOfWeek n -> analyzeOp(node, false, n.days());
      case WeekDay n -> analyzeOp(node, false, n.days());
      case DayOfWeekIso n -> analyzeOp(node, false, n.days());
      case NextDay n -> {
        // A literal slot or a column (the derived weekday leaf).
        analyzeOp(node, false, n.days(), n.offset());
      }
      case ThursdayOf n -> analyzeOp(node, false, n.days());
      // A pass-through of its child's value with a range check beside it, so it analyses
      // exactly as any other one-date operation: same validity, own word, one child.
      case GuardedDay n -> analyzeOp(node, false, n.days());
      // The conversions: the child's value in the other lane, and the child's validity.
      case WidenLane n -> analyzeOp(node, false, n.child());
      case NarrowLane n -> analyzeOp(node, false, n.child());
      case GuardedRange n -> {
        // At the int lane the bounds have to be what the lane can compare against; the long
        // lane holds any bound. Refused here, where the tree is, rather than at the push.
        if (n.laneType() == VarkaVectorIR.LaneType.INT
            && (n.lo() < Integer.MIN_VALUE || n.hi() > Integer.MAX_VALUE)) {
          throw VarkaErrors.illegalArgument(
              "a range guard at the int lane needs int bounds: " + node);
        }
        analyzeOp(node, false, n.child());
      }
      case Year n -> analyzeOp(node, false, n.days());
      case Month n -> analyzeOp(node, false, n.days());
      case DayOfMonth n -> analyzeOp(node, false, n.days());
      case Quarter n -> analyzeOp(node, false, n.days());
      case DayOfYear n -> analyzeOp(node, false, n.days());
      case LastDay n -> analyzeOp(node, false, n.days());
      case TruncDate n -> analyzeOp(node, false, n.days());
      case TruncDateDynamic n -> {
        analyzeOp(node, false, n.days(), n.level());
      }
      case WeekOfYear n -> {
        requireThursdayChild(n.days());
        analyzeOp(node, false, n.days());
      }
      case AddMonths n -> {
        analyzeOp(node, false, n.days(), n.months());
      }
      case MakeDate n -> {
        // skips = false: a null input still nulls the output; the reverse direction (a null
        // from valid inputs) is nullsFromValidInputs, which the dispatch reads.
        analyzeOp(node, false, n.year(), n.month(), n.day());
        if (!n.failOnError()) {
          nullsFromValidInputs = true;
        }
      }
      case IntArith n -> {
        // skips = false: a null operand nulls the result, the null-intolerant rule. Under
        // NULL mode the node can also null a lane whose operands are both valid, which is
        // the other direction and is what nullsFromValidInputs states - MakeDate's arm
        // above sets it for the same reason, and the dispatcher reads it to refuse a dense
        // batch, since a dense body has no validity to clear.
        analyzeOp(node, false, n.left(), n.right());
        if (n.mode() == Overflow.NULL) {
          nullsFromValidInputs = true;
        }
      }
      case IntNeg n -> {
        // Spark has no try_negative, so NULL never reaches here; refused rather than
        // emitted as a form nothing can produce (VarkaVectorIR.IntNeg).
        if (n.mode() == Overflow.NULL) {
          throw VarkaErrors.illegalArgument("IntNeg has no NULL mode: " + node);
        }
        analyzeOp(node, false, n.child());
      }
      // The bounded division: exact by its constructor over the bound the caller proved,
      // and like the constant division it neither overflows nor nulls a lane.
      case BoundedDivide n -> analyzeOp(node, false, n.child());
      // The bitwise operations and the popcount neither overflow nor null a lane: a null
      // operand nulls the result and nothing else does.
      case BitOp n -> analyzeOp(node, false, n.left(), n.right());
      case BitCount n -> analyzeOp(node, false, n.child());
      case ConstDivide n -> {
        // A division by a non-zero constant cannot overflow or null a lane, so it carries no
        // overflow mode and joins no validity: it is its child's word exactly, the way
        // IntNeg above is. The one input the int lane cannot divide is Integer.MIN_VALUE by
        // -1, which the compiler refuses rather than emitting; see its own arm there.
        if (n.divisor() == -1) {
          throw VarkaErrors.illegalArgument("a constant division by -1 overflows at "
              + (n.laneType() == VarkaVectorIR.LaneType.LONG ? "Long.MIN_VALUE"
                  : "Integer.MIN_VALUE") + ": " + node);
        }
        // Where this node takes the conversion form it has no magic range-narrowed form to
        // fall back on, so a width with no double species to convert through cannot emit
        // it. Refused here rather than in the emitter, where the absence would surface as
        // a class that computes nothing.
        if (!divider.canConvert()) {
          throw VarkaErrors.illegalArgument(
              "a constant division needs a double species to convert through, which "
              + lane.laneType + " at " + lanes + " lanes has not: " + node);
        }
        // A caller with no structural bound discharges the dividend obligation by guarding,
        // and this is the one part of that discharge a static check can verify: the guard it
        // wrapped has to deliver the bound it then stated. The node's own constructor refuses
        // a bound a lowering cannot honour, and nothing static can know a column's values, so
        // a guard that lets through more than the claim covers is the remaining way to state a
        // bound and not get it.
        if (n.child() instanceof GuardedRange g) {
          // abs(Long.MIN_VALUE) is negative: a bound there is the widest a bound can be.
          long widest = Math.max(
              g.lo() == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(g.lo()),
              g.hi() == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(g.hi()));
          if (widest >= n.dividendBound()) {
            throw VarkaErrors.illegalArgument("a constant division claims its dividend is "
                + "under " + n.dividendBound() + " and the guard below it admits " + widest
                + ": " + VarkaVectorIR.canonical(node));
          }
        }
        analyzeOp(node, false, n.child());
      }
      case Greatest n -> analyzeOp(node, true, n.left(), n.right());
      case Least n -> analyzeOp(node, true, n.left(), n.right());
      case IfElse n -> analyzeOp(node, true, n.cond(), n.thenNode(), n.elseNode());
      case Compare n -> analyzeOp(node, false, n.left(), n.right());
      case And n -> analyzeOp(node, false, n.left(), n.right());
      case Or n -> analyzeOp(node, false, n.left(), n.right());
      case Not n -> analyzeOp(node, false, n.child());
      case InRanges n -> {
        // One static table per distinct range set, named in the order the sets are met.
        rangeTables.computeIfAbsent(n, k -> "RANGES" + rangeTables.size());
        analyzeOp(node, false, n.child());
      }
      case IsNotNull n -> {
        // The compiler enforces this too; re-checked here because emitCond reads a column
        // child's per-input validity word, which only a column has before any value walk, and
        // a condition child's masks, which the condition lowering computes first. A column read
        // through a widening is the same word: an int column's IS NOT NULL in a long-lane
        // condition (VARKA-28).
        VarkaVectorIR column = n.child() instanceof WidenLane w ? w.child() : n.child();
        if (!(column instanceof ColumnRef) && !(n.child() instanceof Cond)) {
          throw VarkaErrors.illegalArgument(
              "IsNotNull child must be a ColumnRef, a widened one or a Cond, got " + n.child());
        }
        // skips = true states the semantics - known output from a null input - though a
        // Cond only reaches a root through IfElse, which already marks skipping.
        analyzeOp(node, true, n.child());
        if (n.child() instanceof Cond) {
          nullTestsOverCond.add(n);
        }
      }
    }
    topoOrder.add(node);
    lineNumbers.put(node, topoOrder.size());
  }

  /**
   * Common op bookkeeping. Value-typed children are checked against condition nodes here;
   * condition-typed children ({@code IfElse.cond}, the connectives') are enforced by the
   * record types themselves.
   */
  private void analyzeOp(VarkaVectorIR node, boolean skips, VarkaVectorIR... children) {
    opNodes++;
    // Only the form without a byte budget is capped by op count; see MAX_FUSED_NODES.
    if (opNodes > MAX_FUSED_NODES && options.methodByteBudget() == 0) {
      throw VarkaErrors.illegalArgument(
          "more than MAX_FUSED_NODES=" + MAX_FUSED_NODES + " distinct ops");
    }
    long set = 0L;
    int maxChildHeight = 0;
    boolean childSkips = false;
    for (VarkaVectorIR child : children) {
      // Value children of value ops and of Compare must not be conditions; the ops whose
      // condition children are legal carry them in Cond-typed record fields already, except
      // IsNotNull, whose one child is a column or a condition (its arm in analyze checks which).
      if (child instanceof Cond && !(node instanceof IfElse) && !(node instanceof And)
          && !(node instanceof Or) && !(node instanceof Not) && !(node instanceof IsNotNull)) {
        requireValue(child, "child of " + node.getClass().getSimpleName());
      }
      operands.add(child);
      analyze(child);
      set |= columns.get(child);
      maxChildHeight = Math.max(maxChildHeight, height.get(child));
      childSkips |= skipping.get(child);
    }
    columns.put(node, set);
    height.put(node, 1 + maxChildHeight);
    skipping.put(node, skips || childSkips);
  }

  /**
   * Refuses {@code operand} where {@code position} does not take it: the emitter's statement of
   * the rule the compiler builds by ({@link VarkaOperandPosition}), kept because a tree from any
   * other producer - a test, a future arm - would otherwise be emitted as a plausible wrong
   * value. {@code name} says which operand failed, because one message shared across operands
   * once sent the IR fuzzer's first failure (#110) hunting for a {@code next_day} the shape did
   * not contain.
   */
  private static void requireOperand(VarkaOperandPosition position, VarkaVectorIR operand,
      String name) {
    if (operand == null || !position.admits(operand)) {
      throw VarkaErrors.illegalArgument(
          name + " must be " + position.takes() + ", got " + operand);
    }
  }

  /**
   * {@link WeekOfYear}'s lowering, {@code (dayOfYear - 1) / 7 + 1}, is the ISO week only of
   * a Thursday, so the node is defined over {@link ThursdayOf} and nothing else:
   * the compiler builds the pair, and any other tree is a bug, refused here rather than
   * emitted as a plausible wrong week.
   */
  private static void requireThursdayChild(VarkaVectorIR days) {
    if (!(days instanceof ThursdayOf)) {
      throw VarkaErrors.illegalArgument(
          "WeekOfYear's child must be a ThursdayOf, got " + days);
    }
  }

  /**
   * one step of an arm chain - an {@code IfElse} and which of its two arms. The condition it names
   * is what the guard is qualified by, taken as-is for the then arm and complemented for the else
   * arm; see {@code emitArmContext} for why the complement and not the known-false word.
   */
  record ArmStep(IfElse node, boolean thenBranch) { }

  /** Whether the kernel reads input column {@code ordinal}, from the analysis's bitset. */

  static boolean referenced(Analysis analysis, int ordinal) {
    return (analysis.referencedColumns >>> ordinal & 1L) != 0;
  }

  // ---------------------------------------------------------------------------------------------
  // What the emission reached (VARKA-280).
  // ---------------------------------------------------------------------------------------------

  /**
   * The mechanisms this emission has reached so far: those of the size loop's current pass - its
   * grouping and the bodies it builds, cleared as each pass starts - and the loop's reactions and
   * declines over every pass. Carried here for the reason {@link #options} is, since the analysis
   * already reaches every body that does one. Noted at compile time only, and read by
   * {@code VarkaLoopEmitter}'s size loop, which hands the set to the trace when the emission
   * ends; no emitted byte reads it.
   */
  final java.util.EnumSet<VarkaEmitMechanism> reached =
      java.util.EnumSet.noneOf(VarkaEmitMechanism.class);

  /** Notes that this emission reached {@code mechanism}. */
  void reach(VarkaEmitMechanism mechanism) {
    reached.add(mechanism);
  }
}
