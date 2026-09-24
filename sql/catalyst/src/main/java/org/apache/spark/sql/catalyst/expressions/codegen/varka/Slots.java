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

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.Analysis.WordExpr;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.Analysis.WordOwner;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaLoopEmitter.BodyMode;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaLoopEmitter.FragmentKey;
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
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntNeg;
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
 * The local-variable slots one emitted body uses, and the planner that assigns them.
 *
 * <p>A body - a loop method, the epilogue or a driver - is emitted against a fixed frame layout:
 * the segment addresses and the per-input validity words first, then the loop's own counters,
 * then one slot per node that needs one (its validity word, its condition masks, the temporaries
 * a lowering parks values in). {@link #plan} assigns them in one pass over the analysis, in the
 * order the emitters expect, and the emitters read them through the fields here rather than
 * numbering locals themselves. Two facts shape the layout. Slot numbers are part of the pinned
 * bytes: a slot allocated for a node that never reads it shifts every later local and moves the
 * oracle, so a slot is allocated on exactly the condition the emission uses it under. And a
 * validity word is planned by aliasing before allocation: a node whose word equals a child's
 * shares that child's reference, and only the words the body still reads once the bitmap pass
 * has taken over the roots' writes get a slot at all ({@link #liveWords}).
 */
final class Slots {
  /** The nominal data / validity segment sizes in bytes (long slots). */
  int dataBytes;
  int validityBytes;
  final int[] srcSeg;
  final int[] srcValSeg;
  final int[] dead;
  final int[] hasNulls;
  final int[] word;
  final int[] dstSeg;
  final int[] dstValSeg;
  int ncTmp;
  int species;
  int lanes;
  int loopBound;
  int[] scalarArg;
  int[] broadcastSlot;
  int iVar;
  int byteOffset;
  int cmpTmp;
  int maskTmp;
  /** Per distinct value node: its validity-word reference (a long slot, an input's word
   * slot, or {@code WORD_ALL_TRUE}); aliased where the algebra makes it a copy. */
  final Map<VarkaVectorIR, Integer> wordRef = new HashMap<>();
  /** The value nodes whose word is computed into their own slot (not an alias). */
  final Set<VarkaVectorIR> ownWord = new HashSet<>();
  /** Per condition node, masked body: the known-true / known-false word slots. */
  final Map<VarkaVectorIR, Integer> kt = new HashMap<>();
  final Map<VarkaVectorIR, Integer> kf = new HashMap<>();
  /** The conditions whose kt/kf are computed (Not aliases its child's, swapped). */
  final Set<VarkaVectorIR> ownCond = new HashSet<>();
  /** Per condition node, dense body: the single mask local. */
  final Map<VarkaVectorIR, Integer> condMask = new HashMap<>();
  /** Per node used more than once: the local its first vector lands in (DAG-CSE). */
  final Map<VarkaVectorIR, Integer> sharedSlot = new HashMap<>();
  /** Per Greatest/Least (masked): the two operand temporaries the substitution needs. */
  final Map<VarkaVectorIR, int[]> pairTmp = new HashMap<>();
  /** the left operand, the right operand and the result of a checked
   *  {@link IntArith}, parked so the overflow mask can read all three. */
  final Map<VarkaVectorIR, int[]> intArithTmp = new HashMap<>();
  /** The four vector locals {@code emitMagicDivide} needs; empty where it is not emitted. */
  final Map<VarkaVectorIR, int[]> constDivideTmp = new HashMap<>();
  /** Per DayOfWeek/WeekDay/NextDay: {@code emitFloorMod7}'s own original-value and fold
   * temporaries. NextDay needs no third slot for the date it reuses after the mod - its
   * emitValue arm keeps that copy on the operand stack instead (dup/swap). */
  final Map<VarkaVectorIR, int[]> dowTmp = new HashMap<>();
  /** Per calendar node: the civil-from-days temporaries, six vectors and two
   * masks - the decomposition is too long to keep on the operand stack. The first
   * {@code CHRONO_PREFIX_SLOTS} are the prefix fragment's and may be shared with a sibling
   * (see {@link #chronoPrefixTmp}); anything past them is the node's own, which is where
   * the leap-flag tail for {@code DayOfYear} keeps its two extra locals. */
  final Map<VarkaVectorIR, int[]> chronoTmp = new HashMap<>();
  /** Per prefix fragment: the {@code CHRONO_PREFIX_SLOTS} locals its run leaves its results
   * in. Two nodes with the same {@link FragmentKey} name the same locals, which is what lets
   * the second one skip the run - so this is planned even when
   * {@link VarkaEmitOptions#shareChronoPrefix} is off, it is simply never hit twice. */
  final Map<FragmentKey, int[]> chronoPrefixTmp = new HashMap<>();
  /**
   * The prefix fragments some tail of the lane group being emitted now reads the March-based
   * month out of. Filled by {@code planFragmentsReadingMonth} at the top of
   * {@code emitLaneGroup}, from that group's outputs and no others.
   *
   * <p>The lane group is the right scope precisely because {@link #emittedFragments} has it:
   * a fragment is re-earned in each lane group, so what has to be true is that every reader
   * of {@code t[5]} <i>in this group</i> is preceded by a write of it in this group, and
   * that is what this set decides. A wider scope - the body's whole output list, which is
   * every output of the kernel, since {@link #plan} walks them all - would keep the
   * month step in a {@code year(d)} loop method merely because {@code month(d)} is another
   * output emitted by a different method, which is the elision this task exists for.
   *
   * <p>Reading the set rather than the node being emitted is what makes the decision
   * order-independent under sharing: whichever sibling emits the prefix first, it emits the
   * month if any sibling in the group will read it.
   */
  final Set<FragmentKey> fragmentsReadingMonth = new HashSet<>();
  /**
   * The prefix fragments already emitted in the lane group being emitted now. Emit-time
   * state rather than plan-time, cleared at the top of {@code emitLaneGroup} for exactly the
   * reason its {@code computed} set is a fresh local there: a local's value does not survive
   * from one lane group to the next, so each one re-earns every fragment it uses. A body
   * emits one lane group, so this is also per body - which is what makes a fragment shared in
   * the epilogue independent of one shared in a loop method.
   */
  final Set<FragmentKey> emittedFragments = new HashSet<>();
  /**
   * The epilogue's bounds mask, or null in every other body role. Non-null is
   * exactly the signal that loads and stores take their masked overloads: the value is a
   * {@code VectorMask} local, live for the whole single pass.
   */
  Integer epilogueMask;
  /** The driver's status accumulator (an int slot), where its callees' returns are ORed. */
  int status;
  /**
   * The guard's accumulated out-of-range mask, or null when nothing in this body sets one.
   * Non-null is exactly the signal that the method returns something other than a constant zero.
   * The calendar extractions once set this; the guard now lives on the producers in
   * {@link Analysis#guardedProducers}, so it is non-null exactly when this body's outputs reach
   * one of those and the option is on - or reach a node in {@link Analysis#selfGuarding}, which
   * is not behind the option.
   */
  Integer guardAcc;
  /**
   * Per guarded node: the local the guarded vector is parked in while the guard compares it,
   * since that vector has to stay on the operand stack for the parent. What is guarded differs
   * by node. For {@code AddDays}/{@code SubDays} it is the node's own result, checked
   * against the range the calendar lowering is exact over. For {@code AddMonths} it
   * is the month count operand, checked against the range the magic multiply is exact over,
   * and so parked before the node's own value exists at all.
   */
  /**
   * one {@code long} accumulator per output whose validity this body writes a word at a time, or
   * {@code -1} for an output that keeps the per-lane-group read-modify-write. Null in every body
   * that does not word-write at all, which is what keeps such a body's slot numbering - and so
   * its bytes - exactly what it was before this task.
   */
  int[] validityAcc;

  final Map<VarkaVectorIR, Integer> guardTmp = new HashMap<>();

  /** {@code MakeDate}'s {@code MAKE_DATE_TMP_COUNT} locals. */
  final Map<VarkaVectorIR, int[]> makeDateTmp = new HashMap<>();

  /**
   * the word slots this body stored ( {@code storeWord} ) and how often it loaded each (
   * {@code loadWord} ). {@code assertWordsLive} reads them at the end of a loop or epilogue
   * body: every word the body defines must be read at least once, and every word it reads must be
   * one it defined. The inventory of word consumers in {@code PLAN_TASK_70.md} 2.2 is how the
   * liveness rule was designed; these counters are what keep a consumer that inventory missed
   * from being missed silently, since {@code loadWord} is the one call every consumer reads a
   * word through.
   */
  final Map<Integer, Integer> wordUses = new HashMap<>();
  final Set<Integer> wordDefs = new HashSet<>();
  /**
   * the input word slots this body never reads. Allocated all the same, so the masked layout
   * stays the dense one's and a method whose every word is dead comes out byte-identical to its
   * dense twin; never stored, and {@code loadWord} refuses them. Empty when
   * {@link VarkaEmitOptions#validityByBitmap} is off.
   */
  final Set<Integer> deadRefs = new HashSet<>();

  Slots(int numInputs, int numOutputs) {
    srcSeg = new int[numInputs];
    srcValSeg = new int[numInputs];
    dead = new int[numInputs];
    hasNulls = new int[numInputs];
    word = new int[numInputs];
    dstSeg = new int[numOutputs];
    dstValSeg = new int[numOutputs];
  }

  /** The literal slots the trees of the outputs in {@code outputIdx} read, as a set of indices. */
  static java.util.BitSet referencedLiterals(List<VarkaVectorIR> outputs, List<Integer> outputIdx,
      int numLiterals) {
    java.util.BitSet used = new java.util.BitSet(numLiterals);
    for (int o : outputIdx) {
      collectLiterals(outputs.get(o), used);
    }
    return used;
  }

  private static void collectLiterals(VarkaVectorIR node, java.util.BitSet into) {
    if (node instanceof VarkaVectorIR.LiteralSlot l) {
      into.set(l.index());
    }
    for (VarkaVectorIR child : childrenOf(node)) {
      collectLiterals(child, into);
    }
  }

  /**
   * Assigns every local slot the body needs, including the per-node word and condition slots,
   * with word aliasing: a node whose validity equals one child's (a literal offset, a unary
   * op) shares that child's reference instead of recomputing it. Per-node slots are planned
   * only for the body roles that emit them - the vector-walk slots for a loop or epilogue
   * method, neither for the driver, which runs only the shared prologue.
   */
  static Slots plan(boolean dense, BodyMode mode, List<VarkaVectorIR> outputs,
      List<Integer> outputIdx, Analysis analysis, int numLiterals) {
    return plan(dense, mode, outputs, outputIdx, analysis, numLiterals, false);
  }

  /**
   * As above; with {@code perGroup} the body gets slots only for the outputs in {@code outputIdx}
   * and the literals their trees reference, and every other output or literal slot is {@code -1}
   * so that an emission which reaches for one fails to build rather than reading a stale local.
   * A group's loop and epilogue methods plan this way (task 87): setting up every output of the
   * kernel in every group was the term that grew each group's methods with the whole kernel
   * ({@code PLAN_TASK_87.md} 2.6.2). The driver keeps planning for every output, since it
   * zeroes and serves them all.
   */
  static Slots plan(boolean dense, BodyMode mode, List<VarkaVectorIR> outputs,
      List<Integer> outputIdx, Analysis analysis, int numLiterals, boolean perGroup) {
    int numInputs = analysis.numInputs;
    Slots s = new Slots(numInputs, outputs.size());
    int slot = analysis.lane.firstLocal;
    s.dataBytes = slot;
    slot += 2;
    s.validityBytes = slot;
    slot += 2;
    java.util.BitSet planned = new java.util.BitSet(outputs.size());
    for (int o : outputIdx) {
      planned.set(o);
    }
    for (int o = 0; o < outputs.size(); o++) {
      if (perGroup && !planned.get(o)) {
        s.dstSeg[o] = -1;
        s.dstValSeg[o] = -1;
        continue;
      }
      s.dstSeg[o] = slot++;
      s.dstValSeg[o] = slot++;
    }
    for (int i = 0; i < numInputs; i++) {
      if (referenced(analysis, i)) {
        s.srcSeg[i] = slot++;
        s.srcValSeg[i] = slot++;
        s.dead[i] = slot++;
        s.hasNulls[i] = slot++;
        s.word[i] = slot;
        slot += 2;
      }
    }
    s.ncTmp = slot++;
    s.species = slot++;
    s.lanes = slot++;
    s.loopBound = slot++;
    s.scalarArg = new int[numLiterals];
    java.util.BitSet usedLiterals = perGroup
        ? referencedLiterals(outputs, outputIdx, numLiterals) : null;
    for (int j = 0; j < numLiterals; j++) {
      if (usedLiterals != null && !usedLiterals.get(j)) {
        s.scalarArg[j] = -1;
        continue;
      }
      s.scalarArg[j] = slot;
      // A long occupies two JVM locals; an int one. Stepping by the lane's own width is what
      // keeps two long literals from overlapping in the frame.
      slot += analysis.lane.localWidth;
    }
    // Broadcasts are hoisted into vector locals only where they are used - the loop methods - and
    // only in the regime where the hoist measures as a win (see `PLAN_TASK_9.md`): one output, at
    // most a chain's worth of literals. Any wider body inlines them at each use and lets C2
    // rematerialize under register pressure (PLAN_TASK_10.md).
    s.broadcastSlot = mode == BodyMode.LOOP
        && outputs.size() == 1 && numLiterals <= MAX_CHAIN_DEPTH ? new int[numLiterals] : null;
    if (s.broadcastSlot != null) {
      for (int j = 0; j < numLiterals; j++) {
        s.broadcastSlot[j] = slot++;
      }
    }
    s.iVar = slot++;
    s.byteOffset = slot;
    slot += 2;
    s.cmpTmp = slot;
    slot += 2;
    s.maskTmp = slot++;
    s.status = slot++;
    // One accumulator per body, and only in a body that emits a guarded producer: the caller acts
    // on the batch, not the lane, and a body with nothing to guard keeps the slot numbering - and
    // so the bytes - unchanged, whichever way the option is set.
    boolean producersGuarding = analysis.options.guardDayProducers() && mode != BodyMode.DRIVER
        && !analysis.guardedProducers.isEmpty()
        && outputs.stream().anyMatch(o -> reaches(o, analysis.guardedProducers));
    // A self-guarding node needs the accumulator whatever the option says.
    boolean selfGuarding = mode != BodyMode.DRIVER && !analysis.selfGuarding.isEmpty()
        && outputs.stream().anyMatch(o -> reaches(o, analysis.selfGuarding));
    // a checked int operation condemns the batch through the same accumulator, so a body holding
    // one needs it allocated whether or not anything else is guarded. The scratch slots for the
    // check itself are allocated in the node loop below; this is the accumulator they fold into,
    // and missing it is an emit-time failure rather than a wrong answer - which is how it was
    // found.
    boolean checkedArith = mode != BodyMode.DRIVER && analysis.options.checkIntOverflow()
        && outputs.stream().anyMatch(o -> reaches(o, analysis.checkedArith));
    // The re-armed range check folds into the same accumulator and is behind no option, so a body
    // holding one needs it allocated on that ground alone.
    boolean rearmed = mode != BodyMode.DRIVER
        && outputs.stream().anyMatch(o -> reachesGuardedDay(o, new HashSet<>()));
    boolean guarding = producersGuarding || selfGuarding || checkedArith || rearmed;
    if (guarding) {
      s.guardAcc = slot++;
    }
    // one accumulator per output this body writes a word at a time. Allocated after guardAcc and
    // before epilogueMask, and only in a loop body that word-writes at all, so every other emission
    // keeps the slot numbering it had - the byte-identity the option's off arm is asserted on.
    if (mode == BodyMode.LOOP && VarkaBodyEmitter.wordWrites(analysis)) {
      for (int o : outputIdx) {
        if (VarkaBodyEmitter.keepsPerGroupWrite(analysis, dense, outputs, o)) {
          if (s.validityAcc == null) {
            s.validityAcc = new int[outputs.size()];
            Arrays.fill(s.validityAcc, -1);
          }
          s.validityAcc[o] = slot;
          slot += 2;
        }
      }
    }
    // which words this body still reads, or null for "all of them" - the pass off, or a dense body,
    // which has no words. Decided before the allocation loop because it decides what the loop
    // allocates.
    Set<WordOwner> live = !dense && mode != BodyMode.DRIVER && analysis.options.validityByBitmap()
        ? liveWords(outputs, outputIdx, analysis, producersGuarding, selfGuarding, checkedArith)
        : null;
    if (live != null) {
      for (int i = 0; i < numInputs; i++) {
        if (referenced(analysis, i) && !live.contains(new WordOwner.Input(i))) {
          s.deadRefs.add(s.word[i]);
        }
      }
    }

    if (mode == BodyMode.EPILOGUE) {
      s.epilogueMask = slot++;
    }

    // The epilogue is the loop body run once over a partial lane group, so it needs exactly
    // the loop's slots - the word, condition, CSE and temporary locals - and none of its own.
    boolean vectorWalk = mode == BodyMode.LOOP || mode == BodyMode.EPILOGUE;
    boolean cse = analysis.options.cse();
    boolean shareChronoPrefix = analysis.options.shareChronoPrefix();
    for (VarkaVectorIR node : analysis.topoOrder) {
      if (vectorWalk) {
        // Vector-walk slots. Children precede parents in the topo order, so a word reference
        // computed here always sees concrete child references - the aliasing depends on it.
        if (!(node instanceof Cond)) {
          if (!dense) {
            int ref = planWordRef(node, s);
            if (ref == Integer.MIN_VALUE) {
              if (live == null || live.contains(new WordOwner.Own(node))) {
                ref = slot;
                slot += 2;
                s.ownWord.add(node);
              } else {
                // Dead: no slot, no computation, and every alias of it inherits the sentinel
                // through planWordRef - a parent of a dead word is dead, by the closure the
                // liveness pass keeps (a live word demands its operands' words).
                ref = WORD_DEAD;
              }
            }
            s.wordRef.put(node, ref);
            if (ref != WORD_DEAD) {
              assertWordAlgebraAgrees(node, ref, s, analysis);
            }
          }
          if (cse && analysis.useCount.get(node) > 1 && !(node instanceof LiteralSlot)) {
            s.sharedSlot.put(node, slot++);
          }
          if (!dense && (node instanceof Greatest || node instanceof Least)) {
            s.pairTmp.put(node, new int[] {slot++, slot++});
          }
          // The overflow check reads both operands and the result after the lanewise op has
          // consumed the operands off the stack, so all three are parked. Allocated in both bodies,
          // unlike pairTmp: a dense batch overflows exactly as a masked one does, and the check is
          // what the mode asks for, not what the null state asks for. That reads as true of both
          // modes and is true of FAIL: a NULL node has no dense body at all, because `analyze` sets
          // nullsFromValidInputs for it and `emit` then builds none, so its dense slots are planned
          // and never used. Harmless, and worth knowing before counting dense locals against a
          // method budget.
          if (analysis.options.checkIntOverflow() && node instanceof IntArith n
              && n.mode() != Overflow.WRAP) {
            s.intArithTmp.put(node, new int[] {slot++, slot++, slot++});
          }
          // The magic-number division builds its floor by hand, which needs the quotient, its
          // rounding and the mask saying where the rounding went up held at once. The
          // conversion form needs none, so the slots follow the form rather than the node, and
          // the predicate is shared with the emission so the two cannot disagree about it.
          if (node instanceof ConstDivide n
              && VarkaDivisionLowering.takesMagicDivide(analysis, n)) {
            s.constDivideTmp.put(node, new int[] {slot++, slot++, slot++, slot++});
          }
          // The multiply-high form reads its dividend three times - two halves and the sign
          // bit - so it parks the vector in one slot rather than juggling the stack.
          if (node instanceof ConstDivide n
              && VarkaDivisionLowering.takesMulHiDivide(analysis, n)) {
            s.constDivideTmp.put(node, new int[] {slot++});
          }
          if (node instanceof DayOfWeek || node instanceof WeekDay || node instanceof NextDay
              || node instanceof TruncDateDynamic || node instanceof ThursdayOf
              || node instanceof DayOfWeekIso) {
            // emitFloorMod7's own two scratch slots; NextDay's second copy of the date rides
            // the operand stack (dup/swap in its emitValue arm) rather than needing a third,
            // and TruncDateDynamic's week result reloads the date from the prefix's
            // own local.
            s.dowTmp.put(node, new int[] {slot++, slot++});
          }
          // A day producer's temporary is behind the option with the guard it serves; a
          // column-count AddMonths guards itself and takes one whatever the option says.
          // MakeDate, the other self-guarding node, guards out of makeDateTmp and takes none -
          // allocating one for it would shift every later local and move the pinned bytes.
          if (guardScratch(analysis, node, producersGuarding, selfGuarding)) {
            s.guardTmp.put(node, slot++);
          }
          if (node instanceof MakeDate) {
            int[] tmp = new int[MAKE_DATE_TMP_COUNT];
            for (int k = 0; k < MAKE_DATE_TMP_COUNT; k++) {
              tmp[k] = slot++;
            }
            s.makeDateTmp.put(node, tmp);
          }
          if (isChrono(node)) {
            // Six int-vector temporaries and two masks for a plain extraction (see emitChrono
            // for what stays live); AddMonths needs the same eight plus the rest of
            // emitAddMonths's own locals, since it decomposes and recomposes in one node;
            // DayOfYear needs one more, for the plain year its leap flag is computed
            // from - t[6] and t[7] are the prefix's carry scratch and are dead by the time its
            // tail runs, so only t[8] is genuinely extra; and LastDay needs the same
            // eight plus emitChronoLastDay's own month-length and leap-flag scratch.
            // The first eight are the prefix fragment's and are allocated once per fragment
            // when sharing is on, so siblings over one date name the same locals; the rest are
            // the node's own, because emitAddMonths/emitChronoLastDay write them and their
            // siblings must not see that. (Their one write into a shared slot is the prefix's
            // carry mask, which no field's tail reads - see emitChronoPrefixOnce.)
            int count = node instanceof AddMonths ? ADD_MONTHS_TMP_COUNT
                : node instanceof LastDay ? LAST_DAY_TMP_COUNT
                : node instanceof TruncDate ? TRUNC_DATE_TMP_COUNT
                : node instanceof TruncDateDynamic ? TRUNC_DYNAMIC_TMP_COUNT
                : node instanceof DayOfYear || node instanceof WeekOfYear ? CHRONO_PREFIX_SLOTS + 1
                : CHRONO_PREFIX_SLOTS;
            FragmentKey key = fragmentKey(node, dense, s);
            int[] prefix = shareChronoPrefix ? s.chronoPrefixTmp.get(key) : null;
            if (prefix == null) {
              prefix = new int[CHRONO_PREFIX_SLOTS];
              for (int i = 0; i < CHRONO_PREFIX_SLOTS; i++) {
                prefix[i] = slot++;
              }
              s.chronoPrefixTmp.put(key, prefix);
            }
            int[] tmp = Arrays.copyOf(prefix, count);
            for (int i = CHRONO_PREFIX_SLOTS; i < count; i++) {
              tmp[i] = slot++;
            }
            s.chronoTmp.put(node, tmp);
          }
        } else if (dense) {
          s.condMask.put(node, slot++);
        } else {
          if (node instanceof Not n) {
            // NOT swaps the pair: pure slot aliasing, no code emitted for it.
            s.kt.put(node, s.kf.get(n.child()));
            s.kf.put(node, s.kt.get(n.child()));
          } else {
            s.kt.put(node, slot);
            slot += 2;
            s.kf.put(node, slot);
            slot += 2;
            s.ownCond.add(node);
          }
        }
      }
    }
    return s;
  }

  /**
   * The validity-word reference for a value node, or {@code Integer.MIN_VALUE} when the node
   * needs its own slot (assigned in a second pass). AND-nodes over a single non-constant
   * child alias that child; literal-only subtrees are the all-true constant.
   */
  private static int planWordRef(VarkaVectorIR node, Slots s) {
    return switch (node) {
      case ColumnRef c -> s.word[c.ordinal()];
      case LiteralSlot l -> WORD_ALL_TRUE;
      case AddDays n -> andRef(s.wordRef.get(n.days()), s.wordRef.get(n.offset()));
      case SubDays n -> andRef(s.wordRef.get(n.days()), s.wordRef.get(n.offset()));
      case DayOfWeek n -> s.wordRef.get(n.days());
      case WeekDay n -> s.wordRef.get(n.days());
      case DayOfWeekIso n -> s.wordRef.get(n.days());
      case NextDay n -> andRef(s.wordRef.get(n.days()), s.wordRef.get(n.offset()));
      case ThursdayOf n -> s.wordRef.get(n.days());
      case GuardedDay n -> s.wordRef.get(n.days());
      case GuardedRange n -> s.wordRef.get(n.child());
      case NarrowLane n -> s.wordRef.get(n.child());
      case Year n -> s.wordRef.get(n.days());
      case Month n -> s.wordRef.get(n.days());
      case DayOfMonth n -> s.wordRef.get(n.days());
      case Quarter n -> s.wordRef.get(n.days());
      case DayOfYear n -> s.wordRef.get(n.days());
      case LastDay n -> s.wordRef.get(n.days());
      case TruncDate n -> s.wordRef.get(n.days());
      case TruncDateDynamic n -> andRef(s.wordRef.get(n.days()), s.wordRef.get(n.level()));
      case WeekOfYear n -> s.wordRef.get(n.days());
      case AddMonths n -> andRef(s.wordRef.get(n.days()), s.wordRef.get(n.months()));
      case DateDiff n -> andRef(s.wordRef.get(n.end()), s.wordRef.get(n.start()));
      // WRAP and FAIL alias the operands' AND like any null-intolerant node; NULL narrows that
      // AND with its overflow mask afterwards, so it needs a slot of its own to narrow.
      case IntArith n -> n.mode() == Overflow.NULL
          ? Integer.MIN_VALUE
          : andRef(s.wordRef.get(n.left()), s.wordRef.get(n.right()));
      case IntNeg n -> s.wordRef.get(n.child());
      case ConstDivide n -> s.wordRef.get(n.child());
      case BoundedDivide n -> s.wordRef.get(n.child());
      // Greatest/Least (OR) and IfElse (blend) always compute their own word.
      default -> Integer.MIN_VALUE;
    };
  }

  /**
   * the slot reference {@link #planWordRef} assigned and the symbolic owner
   * {@link Analysis#planWordAlgebra()} derived must name the same word, and the algebra
   * {@link Analysis#pureWord} states must not contradict what the emission computes.
   *
   * <p>Two checks. The mirror is exact: an owner of input {@code i} is that input's slot, the
   * constant is {@code WORD_ALL_TRUE}, and an own word is the slot of the node that owns it -
   * which may be a descendant, since every unary node and every AND over one non-constant
   * operand aliases downward. The algebra check runs only in the direction that can be wrong.
   * The two views fold differently on purpose: {@code greatest}/{@code least} always compute
   * their own word, so over two literals or over one input twice the emitter owns a slot it
   * writes with {@code -1 | -1} where the algebra says the constant or the input, and that
   * slack propagates upward through every parent that ANDs with the pick's slot. So an owner
   * that is a node's own word constrains the algebra not at all - not even by operator: two
   * operands with different owners can have equal expressions, and then a pick's {@code l | r}
   * folds to an AND, which is what the fuzzer found the first version of this check refusing -
   * while an owner that is an input or the constant must be exactly what the algebra says, and
   * a computed word can never be either. What this leaves unchecked - an arm whose
   * {@code pureOf} rule was changed to the wrong operator - produces a wrong bitmap, which
   * {@code checkMatrix}'s byte-for-byte validity comparison and the fuzzer catch at run time.
   * An {@code IllegalStateException} here means one side moved without the other, which is the
   * drift the bitmap pass cannot be allowed to inherit.
   */
  private static void assertWordAlgebraAgrees(VarkaVectorIR node, int ref, Slots s,
      Analysis analysis) {
    WordOwner owner = analysis.wordOwner.get(node);
    WordExpr pure = analysis.pureWord.get(node);
    String problem = switch (owner) {
      case WordOwner.Input in -> ref != s.word[in.ordinal()]
          ? "the owner is input " + in.ordinal() + " but the slot is " + ref
          : !(pure instanceof WordExpr.Input pi && pi.ordinal() == in.ordinal())
              ? "the owner is input " + in.ordinal() + " but the algebra says " + pure : null;
      case WordOwner.Const c -> ref != WORD_ALL_TRUE
          ? "the owner is the constant but the slot is " + ref
          : pure != WordExpr.Const.ALL_TRUE
              ? "the owner is the constant but the algebra says " + pure : null;
      case WordOwner.Own o -> {
        VarkaVectorIR n = o.node();
        Integer ownRef = s.wordRef.get(n);
        yield !s.ownWord.contains(n) || ownRef == null || ownRef != ref
            ? "the owner is " + VarkaVectorIR.canonical(n) + "'s own word but the slot is "
                + ref + " and that node's is " + ownRef
            : null;
      }
    };
    if (problem != null) {
      throw new IllegalStateException("validity-word algebra disagrees with planWordRef on "
          + VarkaVectorIR.canonical(node) + ": " + problem + " (pure=" + pure + ", owner="
          + owner + ")");
    }
  }

  private static int andRef(int a, int b) {
    if (a == WORD_DEAD || b == WORD_DEAD) {
      return WORD_DEAD;
    }
    if (a == WORD_ALL_TRUE) {
      return b;
    }
    if (b == WORD_ALL_TRUE || a == b) {
      return a;
    }
    return Integer.MIN_VALUE;
  }

  /**
   * Whether {@code node} condemns the batch from this body, and so needs its own validity word kept
   * alive for {@code emitGuardCollect} to qualify the condemning mask with. Read by
   * {@link #liveWords}: a node that collects into the accumulator without its word surviving the
   * liveness pass would fail loudly in {@code loadWord}, which is the failure this one predicate
   * exists to make impossible for the next kind added.
   */
  private static boolean guardedWord(Analysis analysis, VarkaVectorIR node,
      boolean producersGuarding, boolean selfGuarding, boolean checkedArith) {
    // Written as "everything that needs a scratch, plus the kinds that need only the word", so
    // that guardScratch being a subset of this is structural rather than two copies of two
    // clauses agreeing by inspection. A kind added to guardScratch alone would otherwise get a
    // slot and a word the liveness pass had killed.
    return guardScratch(analysis, node, producersGuarding, selfGuarding)
        || (checkedArith && analysis.checkedArith.contains(node));
  }

  /** Whether any node under {@code root} is a {@link GuardedDay}, memoised against sharing. */
  private static boolean reachesGuardedDay(VarkaVectorIR root, Set<VarkaVectorIR> seen) {
    if (!seen.add(root)) {
      return false;
    }
    if (root instanceof GuardedDay || root instanceof GuardedRange) {
      return true;
    }
    for (VarkaVectorIR child : childrenOf(root)) {
      if (reachesGuardedDay(child, seen)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether {@code node} needs {@link Slots#guardTmp}, the scratch local a guard parks its value in
   * before testing it. A subset of {@link #guardedWord}, and deliberately not the same question: a
   * guarded day producer's value is on the stack when the guard runs ({@code emitValue} stores it)
   * and {@code AddMonths} guards its count the same way, but the checked int arithmetic already
   * parks its operands and result in {@link Slots#intArithTmp}, and {@code emitIntNeg} reads its
   * operand back with {@code dup}, so neither ever loads this slot. Allocating one for them
   * reserved a local nothing read and shifted every later local in the body.
   */
  private static boolean guardScratch(Analysis analysis, VarkaVectorIR node,
      boolean producersGuarding, boolean selfGuarding) {
    // GuardedDay is unconditional - not behind either flag - because the compiler admits the
    // expression on the strength of this check (see `PLAN_TASK_93.md` 3.4). A flag that removed it
    // would leave the compile-time bound standing over a value nothing bounds, which is the
    // wrong-answer case the column-count AddMonths javadoc names.
    return node instanceof GuardedDay || node instanceof GuardedRange
        || (producersGuarding && analysis.guardedProducers.contains(node))
        || (selfGuarding && node instanceof AddMonths && analysis.selfGuarding.contains(node));
  }

  /**
   * Word liveness for one loop or epilogue body: the set of words some consumer in the body still
   * reads once the bitmap pass has taken over the served roots' writes. A word is <i>demanded</i>
   * by a consumer and then <i>propagated</i> to the operands its computation reads, to a fixpoint.
   * The consumers, from PLAN_TASK_70.md 2.2 plus the one that inventory missed - the null-skipping
   * pick's value substitution, which blends by the operands' words whether or not its own word is
   * wanted: <ul> <li>a value root the pass does not serve: its own word, for the per-group
   * write;</li> <li>a guarded producer or self-guarding {@code AddMonths}: its own word, which
   * {@code emitGuardCollect} ANDs with the condemning mask;</li> <li>{@code MakeDate}: its own
   * word, always - it stores it unconditionally and its guard reads it - and so, by propagation,
   * its three inputs';</li> <li>{@code Greatest}/{@code Least}, emitted at all: both operands'
   * words, for the value;</li> <li>every {@code Compare} and {@code IsNotNull} reached, whether
   * under an {@code IfElse} or a {@code Cond} root: its operands' words, for the known-true/false
   * pair.</li> </ul> Propagation: a demanded own word demands what its arm loads - both operands
   * for the AND family and the picks, the two branches for {@code IfElse} (its condition's leaves
   * are demanded by the walk already), the three inputs for {@code MakeDate}. Nothing is demanded
   * for a served root's own write, which is the point. {@code assertWordsLive} then checks the
   * result against what the emission actually loaded, in both directions.
   *
   * <p>With {@link VarkaEmitOptions#misdescribeWordLiveness} the verdict is inverted word by
   * word, so a test can watch each half of that invariant fail.
   */
  private static Set<WordOwner> liveWords(List<VarkaVectorIR> outputs, List<Integer> outputIdx,
      Analysis analysis, boolean producersGuarding, boolean selfGuarding,
      boolean checkedArith) {
    Set<WordOwner> live = new HashSet<>();
    java.util.ArrayDeque<VarkaVectorIR> work = new java.util.ArrayDeque<>();
    java.util.function.Consumer<WordOwner> demand = owner -> {
      if (owner instanceof WordOwner.Own own) {
        if (live.add(owner)) {
          work.add(own.node());
        }
      } else if (owner instanceof WordOwner.Input) {
        live.add(owner);
      }
    };
    // The walk: every node this body emits, roots and conditions included.
    Set<VarkaVectorIR> reached = new HashSet<>();
    java.util.ArrayDeque<VarkaVectorIR> walk = new java.util.ArrayDeque<>();
    for (int o : outputIdx) {
      VarkaVectorIR root = outputs.get(o);
      walk.add(root);
      if (!(root instanceof Cond) && analysis.served[o] == null) {
        demand.accept(analysis.wordOwner.get(root));
      }
    }
    while (!walk.isEmpty()) {
      VarkaVectorIR n = walk.poll();
      if (!reached.add(n)) {
        continue;
      }
      for (VarkaVectorIR child : childrenOf(n)) {
        walk.add(child);
      }
      // Exhaustive over the sealed IR, like `childrenOf` and `Analysis.analyze`, and for the
      // same reason: a node type added without an arm here is a word the emission loads and
      // the liveness pass killed, which `loadWord` turns into an `IllegalStateException` that
      // the evaluator can only report as a per-batch fallback. A compile error instead.
      switch (n) {
        // The consumers: nodes that read a word other than for their own root write.
        case Compare c -> {
          demand.accept(analysis.wordOwner.get(c.left()));
          demand.accept(analysis.wordOwner.get(c.right()));
        }
        case IsNotNull c -> demand.accept(analysis.wordOwner.get(c.child()));
        case Greatest g -> {
          demand.accept(analysis.wordOwner.get(g.left()));
          demand.accept(analysis.wordOwner.get(g.right()));
        }
        case Least l -> {
          demand.accept(analysis.wordOwner.get(l.left()));
          demand.accept(analysis.wordOwner.get(l.right()));
        }
        case MakeDate m -> demand.accept(new WordOwner.Own(m));
        // The range check masks the lanes the word says are null before reporting one out of
        // range, so that word has to survive the liveness pass. It is the child's: this node
        // checks a value without changing its validity, so it forwards rather than owning one.
        case GuardedDay g -> demand.accept(analysis.wordOwner.get(g.days()));
        case GuardedRange g -> demand.accept(analysis.wordOwner.get(g.child()));
        // The narrowing reads no word: its validity is its child's, and the one consumer of
        // that word is its root write, which demands it above unless the bitmap pass serves it.
        case NarrowLane g -> { }
        // The rest read no word here. A value node's own word, where it needs one, is
        // demanded by its root write, by a guard below, or by a consumer above it; a leaf
        // owns no word at all; and `IfElse`'s blend reads its branches' words through the
        // propagation loop, which is where the demand for its own word arrives.
        case ColumnRef c -> { }
        case LiteralSlot l -> { }
        case AddDays x -> { }
        case SubDays x -> { }
        case DateDiff x -> { }
        case DayOfWeek x -> { }
        case WeekDay x -> { }
        case DayOfWeekIso x -> { }
        case NextDay x -> { }
        case ThursdayOf x -> { }
        case Year x -> { }
        case Month x -> { }
        case DayOfMonth x -> { }
        case Quarter x -> { }
        case DayOfYear x -> { }
        case LastDay x -> { }
        case TruncDate x -> { }
        case TruncDateDynamic x -> { }
        case WeekOfYear x -> { }
        case AddMonths x -> { }
        // Under NULL the node stores its own word - the operands' AND with the overflowing
        // lanes cleared - so that word is demanded whether or not anything above wants it,
        // exactly as MakeDate's arm demands its own. Under WRAP and FAIL nothing is read
        // here: the word is the plain AND, demanded by a root write or by the guard.
        case IntArith x -> {
          if (x.mode() == Overflow.NULL) {
            demand.accept(new WordOwner.Own(x));
          }
        }
        case IntNeg x -> { }
        case ConstDivide x -> { }
        case BoundedDivide x -> { }
        case IfElse x -> { }
        case And x -> { }
        case Or x -> { }
        case Not x -> { }
      }
      if (guardedWord(analysis, n, producersGuarding, selfGuarding, checkedArith)) {
        demand.accept(analysis.wordOwner.get(n));
      }
    }
    // Propagation to the operands each own word's computation loads.
    while (!work.isEmpty()) {
      VarkaVectorIR n = work.poll();
      // Exhaustive for the reason the walk's switch above is. Only a node whose owner is its
      // own word ever reaches this queue, so the arms below it are unreachable rather than
      // no-ops - but they are written out, not defaulted, so that a new node type has to say
      // which it is.
      switch (n) {
        case AddDays x -> { demand.accept(analysis.wordOwner.get(x.days()));
          demand.accept(analysis.wordOwner.get(x.offset())); }
        case GuardedDay x -> demand.accept(analysis.wordOwner.get(x.days()));
        case GuardedRange x -> demand.accept(analysis.wordOwner.get(x.child()));
        case NarrowLane x -> demand.accept(analysis.wordOwner.get(x.child()));
        case SubDays x -> { demand.accept(analysis.wordOwner.get(x.days()));
          demand.accept(analysis.wordOwner.get(x.offset())); }
        case NextDay x -> { demand.accept(analysis.wordOwner.get(x.days()));
          demand.accept(analysis.wordOwner.get(x.offset())); }
        case TruncDateDynamic x -> { demand.accept(analysis.wordOwner.get(x.days()));
          demand.accept(analysis.wordOwner.get(x.level())); }
        case AddMonths x -> { demand.accept(analysis.wordOwner.get(x.days()));
          demand.accept(analysis.wordOwner.get(x.months())); }
        case DateDiff x -> { demand.accept(analysis.wordOwner.get(x.end()));
          demand.accept(analysis.wordOwner.get(x.start())); }
        case Greatest x -> { demand.accept(analysis.wordOwner.get(x.left()));
          demand.accept(analysis.wordOwner.get(x.right())); }
        case Least x -> { demand.accept(analysis.wordOwner.get(x.left()));
          demand.accept(analysis.wordOwner.get(x.right())); }
        case IfElse x -> { demand.accept(analysis.wordOwner.get(x.thenNode()));
          demand.accept(analysis.wordOwner.get(x.elseNode())); }
        case MakeDate x -> { demand.accept(analysis.wordOwner.get(x.year()));
          demand.accept(analysis.wordOwner.get(x.month()));
          demand.accept(analysis.wordOwner.get(x.day())); }
        // A leaf owns no word; every unary node aliases its child's, so its owner is that
        // child's and the child, not the alias, is what the queue holds; a `Cond` has no
        // entry in `wordOwner` at all.
        case ColumnRef x -> { }
        case LiteralSlot x -> { }
        case DayOfWeek x -> { }
        case WeekDay x -> { }
        case DayOfWeekIso x -> { }
        case ThursdayOf x -> { }
        case Year x -> { }
        case Month x -> { }
        case DayOfMonth x -> { }
        case Quarter x -> { }
        case DayOfYear x -> { }
        case LastDay x -> { }
        case TruncDate x -> { }
        case WeekOfYear x -> { }
        case IntArith x -> { demand.accept(analysis.wordOwner.get(x.left()));
          demand.accept(analysis.wordOwner.get(x.right())); }
        // IntNeg aliases its child's word (ownerOf), so it never reaches this queue - the
        // child does. Written out rather than defaulted, per this switch's own rule.
        case IntNeg x -> { }
        case ConstDivide x -> { }
        case BoundedDivide x -> { }
        case Compare x -> { }
        case And x -> { }
        case Or x -> { }
        case Not x -> { }
        case IsNotNull x -> { }
      }
    }
    if (analysis.options.misdescribeWordLiveness()) {
      Set<WordOwner> inverted = new HashSet<>();
      for (WordOwner owner : analysis.wordOwner.values()) {
        if (owner != WordOwner.Const.ALL_TRUE && !live.contains(owner)) {
          inverted.add(owner);
        }
      }
      return inverted;
    }
    return live;
  }
}
