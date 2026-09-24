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

import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitBudget.*;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaLoopEmitter.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDivisionLowering.Divider;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaLoopEmitter.ArmStep;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddDays;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddMonths;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.And;
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
   * of its own. This is {@link #planWordRef} in symbolic form - the same aliasing rules over names
   * instead of slot numbers - and {@link #planSlots} asserts the two agree on every node it plans,
   * so the algebra {@link WordExpr} states cannot drift from the emission it describes. The bitmap
   * pass and the word-liveness rule both read this map rather than the slot numbers, because they
   * have to decide things before the slots are assigned.
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
   * equal operands collapse, which is exactly the folding {@link #andRef} does on slots, so the two
   * views name the same thing.
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
   * time and call the general validity helpers; see {@link VarkaLoopEmitter#emitLanes}.
   */
  final int lanes;
  /**
   * The lane this emission is on, and so every descriptor that names a width. One per emitted
   * class rather than one per node: a kernel's loop, its epilogue and its stores are one
   * species, which is what {@link #analyze}'s refusal below keeps true.
   *
   * <p>It is read from the first output root, and {@link #analyze}'s refusal below is what
   * makes that safe: every other node must answer the same lane, so a mixed emission is
   * rejected rather than emitted with one lane's descriptors over the other's data.
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
  /** Distinct nodes in first-visit order, with how often each is used. */
  final Map<VarkaVectorIR, Integer> useCount = new LinkedHashMap<>();
  /** The output roots, for the one node type admitted only there. */
  final Set<VarkaVectorIR> roots = new HashSet<>();
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
   * The producers that carry a runtime range guard on their own result: every {@link AddDays} /
   * {@link SubDays} whose offset is a column (not a {@link LiteralSlot} ) and which some calendar
   * node reads, directly or through further arithmetic, and every {@link AddMonths} whose month
   * count is a column - the latter wherever it sits, because that guard protects the node's own
   * magic-multiply arithmetic rather than a consumer's, so a bare {@code add_months(d, m)} with
   * no further calendar wrapper still needs it. Empty on every kernel with no such column-driven
   * producer - which is what makes the guard's default cheap: nothing is planned or emitted for
   * it then, and every such shape stays byte-identical under either setting of
   * {@link VarkaEmitOptions#guardDayProducers}.
   *
   * <p>The set is hand-picked, not derived: the compiler's {@code dayRange} and
   * {@code compileMonths} analyses bound every other producer at compile time and decline a node
   * they do not know, so a future column-driven producer fails safe as a residual entry until it
   * is taught to both sides. Filled by {@link #collectGuardedProducers()} once every root is
   * analyzed.
   */
  final Set<VarkaVectorIR> guardedProducers = new HashSet<>();

  /**
   * The nodes that guard themselves whatever their consumers ( {@code MakeDate}: a year outside
   * its limits, and in ANSI mode an invalid date, decline the batch). Unlike
   * {@link #guardedProducers} this set is not behind an option: the check is the node's
   * correctness, not a producer's insurance.
   */
  final Set<VarkaVectorIR> selfGuarding = new HashSet<>();
  /** the int arithmetic nodes whose overflow check condemns the batch through the
   *  same accumulator the producer guard uses - the FAIL ones. A NULL node checks too but
   *  disposes of the mask into its own validity word, so it is not one of these. */
  final Set<VarkaVectorIR> checkedArith = new HashSet<>();

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
   *  PLAN_TASK_70.md 3.1, which the suite holds at zero over its fixtures. */
  int declinedBitmapRoots;

  /**
   * Whether some node turns valid inputs into a null output (non-ANSI {@code MakeDate}). The
   * dense body assumes valid in, valid out - it writes no per-lane validity - so a kernel with
   * such a node is dispatched to the masked methods for every batch; a null-free input costs the
   * masked body only a constant all-true word.
   */
  boolean nullsFromValidInputs;
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
    // conditions via requireValue. A NarrowLane root is legal too, and only as a root.
    roots.add(root);
    analyze(root);
    if (height.get(root) > MAX_CHAIN_DEPTH) {
      throw new IllegalArgumentException(
          "chain deeper than MAX_CHAIN_DEPTH=" + MAX_CHAIN_DEPTH);
    }
  }

  /**
   * See {@link #guardedProducers}: a walk under each calendar node for a column-offset day
   * producer. A column-count {@link AddMonths} joins {@link #selfGuarding} instead, not
   * {@link #guardedProducers}: its check is on its own month count, which its own magic
   * multiply is exact only over, so it is the node's correctness rather than a consumer's
   * insurance - the {@link MakeDate} criterion exactly. It also has to be unconditional
   * because the compiler reads the guard as established fact: {@code dayRange} answers
   * {@code Bounded} for a column count, and composes that interval with whatever shifts it,
   * without being able to see {@link VarkaEmitOptions#guardDayProducers}. Behind the option
   * the guard would vanish while the compile-time bound stayed, which is a wrong answer
   * rather than a slower one.
   */
  /**
   * The context walk: assigns every node the arm chain its uses agree on, by the rule in
   * {@code PLAN_TASK_79.md} 3.3 - narrow only where every use sits under one and the same
   * innermost arm chain, and keep the unqualified guard for anything else.
   *
   * <p>It is its own walk rather than a stack in {@link #analyze}, because that one memoises
   * on {@code useCount} and returns on a repeated node without descending: a node used twice
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

  void collectGuardedProducers() {
    for (VarkaVectorIR node : topoOrder) {
      if (isChrono(node)) {
        collectColumnOffsetProducers(chronoChild(node), guardedProducers);
      }
      if (node instanceof AddMonths n && !(n.months() instanceof LiteralSlot)) {
        selfGuarding.add(node);
      }
      if (node instanceof MakeDate) {
        selfGuarding.add(node);
      }
    }
  }

  private static void collectColumnOffsetProducers(VarkaVectorIR node,
      Set<VarkaVectorIR> into) {
    switch (node) {
      case AddDays n when !(n.offset() instanceof LiteralSlot) -> into.add(node);
      case SubDays n when !(n.offset() instanceof LiteralSlot) -> into.add(node);
      default -> { }
    }
    for (VarkaVectorIR child : childrenOf(node)) {
      collectColumnOffsetProducers(child, into);
    }
  }

  private static void requireValue(VarkaVectorIR node, String position) {
    if (node instanceof Cond) {
      throw new IllegalArgumentException(
          "condition node " + node + " in a value position (" + position + ")");
    }
  }

  /**
   * Fills {@link #wordOwner} and {@link #pureWord} over the topological order, children
   * first, so every node sees its children's entries. The owner rules are
   * {@link #planWordRef}'s, case for case; the purity rules are the word algebra each
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
   * Decides, per output, whether the masked driver writes that root's validity bitmap in
   * one pass over whole input bitmaps (PLAN_TASK_70.md 3.1). A root qualifies when its word
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
      case DayOfWeek n -> wordOwner.get(n.days());
      case WeekDay n -> wordOwner.get(n.days());
      case DayOfWeekIso n -> wordOwner.get(n.days());
      case ThursdayOf n -> wordOwner.get(n.days());
      case GuardedDay n -> wordOwner.get(n.days());
      case GuardedRange n -> wordOwner.get(n.child());
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

  /** {@link #andRef}'s folding over owners: all-true drops out, equal operands collapse. */
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
      case Greatest n -> orExpr(pureWord.get(n.left()), pureWord.get(n.right()));
      case Least n -> orExpr(pureWord.get(n.left()), pureWord.get(n.right()));
      case DayOfWeek n -> pureWord.get(n.days());
      case WeekDay n -> pureWord.get(n.days());
      case DayOfWeekIso n -> pureWord.get(n.days());
      case ThursdayOf n -> pureWord.get(n.days());
      case GuardedDay n -> pureWord.get(n.days());
      case GuardedRange n -> pureWord.get(n.child());
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
    // One species per emitted class, enforced per node. `laneOf` reads the output roots
    // only, so this is the sole defence against a node further down disagreeing - which
    // would emit one lane's descriptors over the other's data. The IR's own constructors
    // make such a tree unbuildable; this is what catches one built another way. The one
    // node whose own lane differs from the emission's is the narrowing root, which is on
    // the int lane by value and the long lane by computation; it is held to the emission
    // lane through its child, and to the root position here: an output root only, until
    // task 28 gives the emitter a bi-lane loop, since below a root it would put a 32-bit
    // value into a computation nothing here can hold. Asked before the lane check so the
    // refusal names the cause rather than the lane mix that follows from it.
    if (node instanceof NarrowLane && !roots.contains(node)) {
      throw new IllegalArgumentException(
          "a narrowing is an output root only; found one under another node: " + node);
    }
    if (VarkaVectorIR.emissionLane(node) != lane.laneType) {
      throw new IllegalArgumentException("a " + node.laneType() + " node in a "
          + lane.laneType + " emission: " + node.getClass().getSimpleName());
    }
    Integer seen = useCount.get(node);
    if (seen != null) {
      // A repeated node: its subtree is already analyzed, only the use count grows.
      useCount.put(node, seen + 1);
      return;
    }
    useCount.put(node, 1);
    switch (node) {
      case ColumnRef c -> {
        if (c.ordinal() < 0 || c.ordinal() >= numInputs) {
          throw new IllegalArgumentException(
              "column ordinal " + c.ordinal() + " outside [0, " + numInputs + ")");
        }
        long set = 1L << c.ordinal();
        columns.put(node, set);
        height.put(node, 0);
        skipping.put(node, false);
        referencedColumns |= set;
      }
      case LiteralSlot l -> {
        if (l.index() < 0 || l.index() >= numLiterals) {
          throw new IllegalArgumentException(
              "literal slot " + l.index() + " outside [0, " + numLiterals + ")");
        }
        columns.put(node, 0L);
        height.put(node, 0);
        skipping.put(node, false);
      }
      case AddDays n -> {
        requireDayOffsetShape(n.offset(), "date_add's day offset");
        analyzeOp(node, false, n.days(), n.offset());
      }
      case SubDays n -> {
        requireDayOffsetShape(n.offset(), "date_sub's day offset");
        analyzeOp(node, false, n.days(), n.offset());
      }
      case DateDiff n -> analyzeOp(node, false, n.end(), n.start());
      case DayOfWeek n -> analyzeOp(node, false, n.days());
      case WeekDay n -> analyzeOp(node, false, n.days());
      case DayOfWeekIso n -> analyzeOp(node, false, n.days());
      case NextDay n -> {
        // A literal slot or a column (the derived weekday leaf).
        requireOffsetShape(n.offset(), "next_day's weekday");
        analyzeOp(node, false, n.days(), n.offset());
      }
      case ThursdayOf n -> analyzeOp(node, false, n.days());
      // A pass-through of its child's value with a range check beside it, so it analyses
      // exactly as any other one-date operation: same validity, own word, one child.
      case GuardedDay n -> analyzeOp(node, false, n.days());
      // The narrowing root: its child's value, its child's validity, narrowed at the store.
      case NarrowLane n -> analyzeOp(node, false, n.child());
      case GuardedRange n -> {
        // At the int lane the bounds have to be what the lane can compare against; the long
        // lane holds any bound. Refused here, where the tree is, rather than at the push.
        if (lane == Lane.INT && (n.lo() < Integer.MIN_VALUE || n.hi() > Integer.MAX_VALUE)) {
          throw new IllegalArgumentException(
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
        // The level is the evaluator's derived int32 column; a literal level is
        // the literal TruncDate node, which the compiler builds instead.
        if (!(n.level() instanceof ColumnRef)) {
          throw new IllegalArgumentException(
              "trunc's level must be a column, got " + n.level());
        }
        analyzeOp(node, false, n.days(), n.level());
      }
      case WeekOfYear n -> {
        requireThursdayChild(n.days());
        analyzeOp(node, false, n.days());
      }
      case AddMonths n -> {
        requireMonthCountShape(n.months(), "add_months' month count");
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
        // FAIL only: this set exists to allocate the condemning accumulator and to keep the
        // word the collect reads alive, and a NULL node writes neither - it narrows its own
        // word instead, and liveWords demands that word through its own arm. Including NULL
        // here parked a guardAcc and a guardTmp that nothing ever read. MUL is excluded for a
        // different reason - a checked one never reaches here at all, because the compiler
        // declines it and emitIntArith throws if it ever did - so this guard is defensive
        // rather than load-bearing; the invariant is stated in three places (here, the throw,
        // and the compiler's decline) and all three must move together if it is ever widened.
        if (n.mode() == Overflow.FAIL && n.op() != IntOp.MUL) {
          checkedArith.add(node);
        }
        if (n.mode() == Overflow.NULL) {
          nullsFromValidInputs = true;
        }
      }
      case IntNeg n -> {
        // Spark has no try_negative, so NULL never reaches here; refused rather than
        // emitted as a form nothing can produce (VarkaVectorIR.IntNeg).
        if (n.mode() == Overflow.NULL) {
          throw new IllegalArgumentException("IntNeg has no NULL mode: " + node);
        }
        if (n.mode() == Overflow.FAIL) {
          checkedArith.add(node);
        }
        analyzeOp(node, false, n.child());
      }
      // The bounded division: exact by its constructor over the bound the caller proved,
      // and like the constant division it neither overflows nor nulls a lane.
      case BoundedDivide n -> analyzeOp(node, false, n.child());
      case ConstDivide n -> {
        // A division by a non-zero constant cannot overflow or null a lane, so it carries no
        // overflow mode and joins no validity: it is its child's word exactly, the way
        // IntNeg above is. The one input the int lane cannot divide is Integer.MIN_VALUE by
        // -1, which the compiler refuses rather than emitting; see its own arm there.
        if (n.divisor() == -1) {
          throw new IllegalArgumentException("a constant division by -1 overflows at "
              + (lane == Lane.LONG ? "Long.MIN_VALUE" : "Integer.MIN_VALUE") + ": " + node);
        }
        // Where this node takes the conversion form it has no magic range-narrowed form to
        // fall back on, so a width with no double species to convert through cannot emit
        // it. Refused here rather than in the emitter, where the absence would surface as
        // a class that computes nothing.
        if (!divider.canConvert()) {
          throw new IllegalArgumentException(
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
          long widest = Math.max(Math.abs(g.lo()), Math.abs(g.hi()));
          if (widest >= n.dividendBound()) {
            throw new IllegalArgumentException("a constant division claims its dividend is "
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
        // The compiler enforces this too; re-checked here because emitCond reads the
        // child's per-input validity word, which only a column has before any value walk.
        if (!(n.child() instanceof ColumnRef)) {
          throw new IllegalArgumentException(
              "IsNotNull child must be a ColumnRef, got " + n.child());
        }
        // skips = true states the semantics - known output from a null input - though a
        // Cond only reaches a root through IfElse, which already marks skipping.
        analyzeOp(node, true, n.child());
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
      throw new IllegalArgumentException(
          "more than MAX_FUSED_NODES=" + MAX_FUSED_NODES + " distinct ops");
    }
    long set = 0L;
    int maxChildHeight = 0;
    boolean childSkips = false;
    for (VarkaVectorIR child : children) {
      // Value children of value ops and of Compare must not be conditions; the ops whose
      // condition children are legal carry them in Cond-typed record fields already.
      if (child instanceof Cond && !(node instanceof IfElse) && !(node instanceof And)
          && !(node instanceof Or) && !(node instanceof Not)) {
        requireValue(child, "child of " + node.getClass().getSimpleName());
      }
      analyze(child);
      set |= columns.get(child);
      maxChildHeight = Math.max(maxChildHeight, height.get(child));
      childSkips |= skipping.get(child);
    }
    columns.put(node, set);
    height.put(node, 1 + maxChildHeight);
    skipping.put(node, skips || childSkips);
  }

  // The offset may be a literal or a column, but it is still not an arbitrary subtree -
  // VarkaExpressionCompiler only ever emits one of these two shapes, and this check fails fast if
  // a future IR producer emits anything else. It guards NextDay's weekday, the stricter
  // requireLiteralOffset that used to cover it having no caller left; the day offset and
  // AddMonths' month count each // took a third kind and moved to requireDayOffsetShape and
  // requireMonthCountShape. {@code position} names the operand that failed, because one message
  // shared across operands is exactly what sent the IR fuzzer's first failure (#110) hunting for
  // a next_day the shape did not contain - the reason the check requireLiteralOffset replaced
  // carried the name too.
  //
  // This one stays a local instanceof pair rather than moving to a shared predicate the way
  // requireDayOffsetShape moved to isDayOffsetShape, and the difference is which of the two
  // has a compiler-side counterpart to drift from. The day offset does: `compileOffset` runs
  // an admission test over a compiled subtree, so the rule was stated twice, in two languages,
  // and the copies did drift. This operand is not admitted by a test at all - the NextDay arm
  // *constructs* a LiteralSlot or a derived column and can build nothing else - so there is no
  // second copy here, only a defence against a future producer.
  //
  // It once served AddMonths' month count too, on a reason true of only one of them: see
  // requireMonthCountShape.
  private static void requireOffsetShape(VarkaVectorIR offset, String position) {
    if (!(offset instanceof LiteralSlot) && !(offset instanceof ColumnRef)) {
      throw new IllegalArgumentException(
          position + " must be a literal slot or a column, got " + offset);
    }
  }

  /**
   * The month count of {@code AddMonths}, which accepts: a literal slot, a column, or int
   * arithmetic over those - {@code d - ym_col}, whose count is an {@link IntNeg} over the
   * interval column, and {@code CAST(i AS INTERVAL YEAR)}, whose count is that column times
   * twelve.
   *
   * <p>This position and {@code next_day} 's weekday once shared {@link #requireOffsetShape},
   * under one sentence covering both - that each "carries a runtime bound a derived value cannot
   * declare". That is true of the weekday and false here, which {@code PLAN_TASK_67.md} 2.1
   * recorded and this split acts on. A column-count {@code AddMonths} is in
   * {@link Analysis#selfGuarding} and is checked at run time against
   * {@link VarkaChrono#MONTH_ARITH_MIN_MONTHS} / {@code MAX_MONTHS} by a lanewise test on the
   * count's own <i>value</i>, which cares nothing about what produced it - so a derived count is
   * covered by exactly the guard a column count is. {@code next_day} 's weekday has no such
   * guard: its range is established by a compile-time fold or by the derived weekday leaf, and a
   * derived value there would reach {@code emitFloorMod7} unchecked.
   *
   * <p>The two therefore share no rule any more. {@code VarkaChronoCompiler.compileMonths}
   * admits exactly these three kinds; the two are meant to be read together, and an enumeration
   * test over the kinds is what would keep them together mechanically.
   */
  private static void requireMonthCountShape(VarkaVectorIR months, String position) {
    if (!isDayOffsetShape(months)) {
      throw new IllegalArgumentException(
          position + " must be a literal slot, a column or int arithmetic, got " + months);
    }
  }

  /**
   * The day offset of {@code AddDays}/{@code SubDays}, which accepts: a literal slot, a column,
   * or int arithmetic over those - {@code date_add(d, i * 7)}. Not every node, which is the point
   * of keeping a check here at all: a date-valued subtree in this position would be read as a day
   * count and produce a plausible wrong date, and {@code next_day}'s weekday next door still
   * takes the stricter {@link #requireOffsetShape}, because its range comes from a compile-time
   * fold with no runtime guard behind it - unlike {@code add_months}' month count, which takes
   * this same shape for the reason {@link #requireMonthCountShape} gives.
   * `VarkaChronoCompiler.compileOffset` admits exactly these three kinds; the two are meant
   * to be read together.
   */
  private static void requireDayOffsetShape(VarkaVectorIR offset, String position) {
    if (!isDayOffsetShape(offset)) {
      throw new IllegalArgumentException(
          position + " must be a literal slot, a column or int arithmetic, got " + offset);
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
      throw new IllegalArgumentException(
          "WeekOfYear's child must be a ThursdayOf, got " + days);
    }
  }
}

