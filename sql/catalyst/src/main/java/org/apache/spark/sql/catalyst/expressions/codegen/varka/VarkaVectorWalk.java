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

import org.apache.spark.sql.catalyst.expressions.codegen.varka.Analysis.ArmStep;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDescriptors.*;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.Set;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddDays;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddMonths;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.And;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitCount;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BoolColumn;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BoolCompare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BoolNullSafeEq;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BoolOutput;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BoundedDivide;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CondConst;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CondIf;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CondValue;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConstDivide;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DateDiff;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DayOfMonth;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DayOfWeek;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DayOfWeekIso;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DayOfYear;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DecimalColumn;
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
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NullSafeEqual;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Or;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Quarter;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.SubDays;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ThursdayOf;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.TruncDate;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.TruncDateDynamic;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Truth;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WeekDay;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WeekOfYear;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Year;

/**
 * The walk over one output's DAG: {@link #emitValue} leaves a node's vector on the operand
 * stack and its validity word in the slot the planner gave it, {@link #emitCond} leaves a
 * condition's known-true and known-false words.
 *
 * <p>{@code emitValue} is the dispatch over the IR. The leaves, the day and int arithmetic
 * with its overflow masks, the null-skipping picks, the range guards and the {@code IfElse}
 * blend are lowered here; the calendar family goes to {@link VarkaChronoLowering} and the
 * constant divisions to {@link VarkaDivisionLowering}, which call back in for their children.
 * A node used more than once is emitted once and reloaded from its CSE slot. Also here, because
 * every consumer goes through them: {@link #line}, which attributes the next instructions to
 * the node for the telemetry, and {@link #loadWord} and {@link #storeWord}, the one path a
 * validity word is read or written through, which is what lets the body check its word
 * invariant at the end.
 */
final class VarkaVectorWalk {

  private VarkaVectorWalk() {
  }

  /**
   * Attributes the instructions emitted next to the node's own line of the notional source
   * file - its 1-based topological index. Called immediately before each node's
   * defining instruction, so a stack trace through the generated loop names the IR node that
   * threw rather than only the method; {@link VarkaDebugInfo} carries the decoding key.
   */
  static void line(CodeBuilder cb, Analysis analysis, VarkaVectorIR node) {
    Integer number = analysis.lineNumbers.get(node);
    if (number != null) {
      cb.lineNumber(number);
    }
  }

  /** The word-reference value meaning "constant all-true": a literal-only subtree has no nulls. */
  static final int WORD_ALL_TRUE = -1;

  /**
   * The word-reference value meaning "this word is dead in this body": no consumer
   * left in the method reads it, so it is neither allocated nor computed. Only an own word
   * takes this value - an input's word keeps its slot for parity with the dense body's layout
   * and is marked dead in {@link Slots#deadRefs} instead. {@code loadWord} refuses both.
   */
  static final int WORD_DEAD = -2;

  /**
   * Pushes a validity word: a long local, or the all-true constant. The one call every consumer
   * of a word goes through, which is what makes {@link Slots#wordUses} a complete count.
   */
  static void loadWord(CodeBuilder cb, Slots s, int ref) {
    if (ref == WORD_ALL_TRUE) {
      cb.loadConstant(-1L);
    } else if (ref == WORD_DEAD || s.deadRefs.contains(ref)) {
      throw VarkaErrors.illegalState("a word the liveness pass declared dead is loaded: slot "
          + ref + " - a consumer the inventory in liveWords does not list");
    } else {
      cb.lload(ref);
      s.wordUses.merge(ref, 1, Integer::sum);
    }
  }

  /** Stores the word on the stack into its slot, recording the definition. */
  static void storeWord(CodeBuilder cb, Slots s, int ref) {
    cb.lstore(ref);
    s.wordDefs.add(ref);
  }

  /**
   * Post-order walk leaving the node's {@code IntVector} on the operand stack. A node used
   * more than once is computed at its first (textual) use, duplicated into its local, and
   * later uses load the local - across outputs too, since the loop body is one straight line.
   * In the masked body the node's validity word is stored as a side effect of the first visit.
   */
  static void emitValue(CodeBuilder cb, VarkaVectorIR node, boolean dense,
      Analysis analysis, Slots s, Set<VarkaVectorIR> computed) {
    Integer shared = s.sharedSlot.get(node);
    if (shared != null && computed.contains(node)) {
      analysis.reach(VarkaEmitMechanism.SHARED_LOCAL);
      cb.aload(shared);
      return;
    }
    // Under CSE a node visited twice has a shared slot, decided by the body's use count
    // (Slots.bodyUses). A second visit without one means the count ran low, and the walk would
    // quietly emit the node's subtree again; that is a planning error, refused here, as the
    // word check refuses a word the liveness pass got wrong. A literal is never shared.
    if (shared == null && analysis.options.cse() && !(node instanceof LiteralSlot)
        && !s.emittedUnshared.add(node)) {
      throw VarkaErrors.illegalState("the body visits " + VarkaVectorIR.canonical(node)
          + " twice without a shared slot: its use count (Slots.bodyUses) is too low");
    }
    // The lane this node's instructions name: the emission's own in a single-lane kernel, the
    // node's in one that converts between lanes (Analysis.laneOf).
    Lane lane = analysis.laneOf(node);
    switch (node) {
      case ColumnRef c -> {
        line(cb, analysis, node);
        // The lane the column is stored at, which is not the lane its value is computed in for
        // an int column of a long-driven kernel (low half) or a narrow column of an int-driven
        // one (widening load). Under NATIVE the two agree and the plain load below runs.
        Lane stored = analysis.inputLane(c.ordinal());
        if (stored.isNarrow() && stored != lane) {
          emitNarrowLoad(cb, c, stored, analysis, s);
          break;
        }
        if (lane != analysis.lane) {
          emitLowHalfLoad(cb, c, s);
          break;
        }
        cb.aload(s.species(lane));
        cb.aload(s.srcSeg(c.ordinal()));
        cb.lload(s.byteOffset);
        cb.getstatic(BYTE_ORDER, "LITTLE_ENDIAN", BYTE_ORDER);
        if (s.epilogue) {
          cb.aload(s.epilogueMask());
          cb.invokestatic(lane.vector, "fromMemorySegment",
              lane.fromMemorySegmentMasked);
        } else {
          cb.invokestatic(lane.vector, "fromMemorySegment",
              lane.fromMemorySegmentDense);
        }
      }
      case DecimalColumn c -> emitDecimalLoad(cb, c, dense, analysis, s);
      case LiteralSlot l -> {
        line(cb, analysis, node);
        int[] broadcast = s.broadcastSlots(lane);
        if (broadcast != null) {
          cb.aload(broadcast[l.index()]);
        } else {
          cb.aload(s.species(lane));
          lane.loadScalar(cb, s.scalarArgs(lane)[l.index()]);
          lane.literalScalar(cb);
          cb.invokestatic(lane.vector, "broadcast", lane.broadcast);
        }
      }
      case AddDays n -> {
        lane.requireInt(n);
        // The misdescribe hook: whichever body executes first must fail naming the call.
        MethodTypeDesc desc =
            analysis.options.misdescribeAdd()
                ? lane.lanewiseVVWrong : lane.lanewiseVV;
        emitAndValidatedOp(cb, node, n.days(), n.offset(), "add", desc, dense, analysis, s,
            computed);
      }
      case SubDays n -> {
        lane.requireInt(n);
        emitAndValidatedOp(cb, node, n.days(), n.offset(), "sub", LANEWISE_VV,
            dense, analysis, s, computed);
      }
      case IntArith n -> emitIntArith(cb, n, dense, analysis, s, computed);
      case IntNeg n -> emitIntNeg(cb, n, dense, analysis, s, computed);
      case ConstDivide n ->
          VarkaDivisionLowering.emitConstDivide(cb, n, dense, analysis, s, computed);
      case BoundedDivide n -> {
        // `(x * M) >>> k`: the product is under 2^32 for every dividend under the bound, so
        // the int multiply's wrap is the unsigned product the logical shift reads, and the
        // constructor proved the quotient exact there. Two lane operations, no correction.
        emitValue(cb, n.child(), dense, analysis, s, computed);
        line(cb, analysis, node);
        cb.loadConstant(n.multiplier());
        cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
        emitShift(cb, "LSHR", n.shift());
      }
      case BitOp n -> emitBitOp(cb, n, dense, analysis, s, computed);
      case BitCount n -> emitBitCount(cb, n, dense, analysis, s, computed);
      case DateDiff n -> {
        lane.requireInt(n);
        emitAndValidatedOp(cb, node, n.end(), n.start(), "sub", LANEWISE_VV,
            dense, analysis, s, computed);
      }
      case DayOfWeek n -> {
        lane.requireInt(n);
        emitValue(cb, n.days(), dense, analysis, s, computed);
        line(cb, analysis, node);
        VarkaChronoLowering.emitFloorMod7(cb, node, analysis, s);
        VarkaChronoLowering.emitModOffset(cb, s, 4);
        cb.loadConstant(1);
        cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
      }
      case WeekDay n -> {
        lane.requireInt(n);
        emitValue(cb, n.days(), dense, analysis, s, computed);
        line(cb, analysis, node);
        VarkaChronoLowering.emitFloorMod7(cb, node, analysis, s);
        VarkaChronoLowering.emitModOffset(cb, s, 3);
      }
      case GuardedDay n -> {
        lane.requireInt(n);
        // The value passes through untouched; what this node adds is two compares beside it.
        // The word is the child's, because a range check does not change validity -
        // it decides whether the batch is answered at all, not which lanes are null.
        emitValue(cb, n.days(), dense, analysis, s, computed);
        line(cb, analysis, node);
        Integer guardTmp = s.guardTmp.get(node);
        if (guardTmp != null) {
          emitRangeGuard(cb, node, dense ? null : s.wordRef.get(n.days()), guardTmp, dense,
              analysis, s, VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_MAX_DAYS);
        }
      }
      case WidenLane n -> {
        // Sign extension of the lanes that hold the group's rows into the target's species: one
        // `I2L` for the int-to-long cast, part 0. A conversion whose two sides are emitted at
        // one lane - an int-driven kernel's narrow-to-int widening, whose child the load already
        // widened - is no instruction at all. The word is the child's.
        emitValue(cb, n.child(), dense, analysis, s, computed);
        line(cb, analysis, node);
        emitShapeConversion(cb, analysis.laneOf(n.child()), analysis.laneOf(node), analysis);
      }
      case NarrowLane n -> {
        // At a root nothing else reads, the value stays in the wider lane and narrows at the
        // store (`emitNarrowStore`). Anywhere else its consumer computes at the target's lane,
        // so it narrows here: one `L2I`, part 0, the low 32 bits of each long lane into the low
        // half of the int species, whose upper half is then zero. The word is the child's.
        emitValue(cb, n.child(), dense, analysis, s, computed);
        line(cb, analysis, node);
        if (!analysis.narrowsAtStore(node)) {
          Lane target = Lane.of(n.target());
          if (target.isNarrow() && analysis.laneOf(n.child()) == Lane.INT) {
            // An int value wrapped to TINYINT or SMALLINT away from a store: it stays in the int
            // lane, reduced to the narrow width and sign-extended back by the shift pair a
            // wrapping narrow node reduces with.
            emitWrapToNarrow(cb, Lane.INT, n.target());
          } else {
            emitShapeConversion(cb, analysis.laneOf(n.child()), analysis.laneOf(node), analysis);
          }
        }
      }
      case GuardedRange n -> {
        // The day guard's twin at whichever lane the child is on, with the bounds the node
        // carries. The value passes through. A FAIL guard's word is the child's, and over a
        // column's stored values a null row's lane is checked too (Analysis.guardsStoredValues);
        // a NULL guard's word is a copy of the child's, narrowed by the check.
        emitValue(cb, n.child(), dense, analysis, s, computed);
        line(cb, analysis, node);
        Integer guardTmp = s.guardTmp.get(node);
        if (n.mode() == Overflow.NULL) {
          // The nulling guard: its own word is the child's, copied, with the lanes outside the
          // bounds cleared by emitOverflowMask's NULL arm, which narrows the node's word in place.
          if (guardTmp == null) {
            // Refused by name: without its scratch the check would be dropped silently.
            throw VarkaErrors.illegalState("a nulling range guard with no scratch planned: "
                + node);
          }
          if (!dense && s.ownWord.contains(node)) {
            loadWord(cb, s, s.wordRef.get(n.child()));
            storeWord(cb, s, s.wordRef.get(node));
          }
          emitRangeMask(cb, node, guardTmp, analysis, n.lo(), n.hi());
          emitOverflowMask(cb, Overflow.NULL, node, dense, analysis, s);
        } else if (analysis.accumulatesRange(node, !s.epilogue)) {
          Integer acc = s.rangeAcc.get(new Slots.Bounds(n.lo(), n.hi()));
          if (acc == null) {
            // Refused by name: without its accumulators the guard would be dropped silently.
            throw VarkaErrors.illegalState("a min-and-max guard with no accumulators planned: "
                + node);
          }
          emitRangeAccumulate(cb, node, acc, analysis);
        } else if (guardTmp != null) {
          Integer word = dense || Analysis.guardsStoredValues(node)
              ? null : s.wordRef.get(n.child());
          emitRangeGuard(cb, node, word, guardTmp, dense, analysis, s, n.lo(), n.hi());
        }
      }
      case ThursdayOf n -> {
        lane.requireInt(n);
        // t = d + 3 - weekday0(d), the Thursday of d's Monday-based week, on
        // NextDay's pattern: the date's second copy rides the operand stack across
        // emitFloorMod7, whose two dowTmp slots it would otherwise have to share.
        emitValue(cb, n.days(), dense, analysis, s, computed);   // [d]
        cb.dup();                                                // [d, d]
        line(cb, analysis, node);
        // [d, floorMod(d, 7)]
        VarkaChronoLowering.emitFloorMod7(cb, node, analysis, s);
        // [d, weekday0]
        VarkaChronoLowering.emitModOffset(cb, s, 3);
        cb.swap();                                               // [weekday0, d]
        cb.loadConstant(3);
        cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);        // [weekday0, d + 3]
        cb.swap();                                               // [d + 3, weekday0]
        cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);        // [d + 3 - weekday0]
      }
      case DayOfWeekIso n -> {
        lane.requireInt(n);
        // WeekDay's tail plus one: Monday 1 to Sunday 7.
        emitValue(cb, n.days(), dense, analysis, s, computed);
        line(cb, analysis, node);
        VarkaChronoLowering.emitFloorMod7(cb, node, analysis, s);
        VarkaChronoLowering.emitModOffset(cb, s, 3);
        cb.loadConstant(1);
        cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
      }
      case NextDay n -> {
        lane.requireInt(n);
        // date is needed twice - once inside w = k - d, once again for the final d + r - and
        // both children must be emitted before line() re-tags the node's own instructions
        // (matching AddDays/SubDays/DateDiff), so it rides the operand stack via dup/swap
        // rather than a dedicated local: [date] -dup-> [date, date] -offset-> [date, date, k]
        // -swap-> [date, k, date], leaving exactly k.sub(date)'s [receiver, arg] shape on top
        // with the reserved date copy underneath for the later d.add(r).
        emitValue(cb, n.days(), dense, analysis, s, computed);
        cb.dup();
        emitValue(cb, n.offset(), dense, analysis, s, computed);
        cb.swap();
        line(cb, analysis, node);
        // w = k - d, wrapping on purpose: next_day's oracle is Spark's own
        // getNextDateForDayOfWeek, which computes this in plain int arithmetic, so
        // byte-exactness with the row engine means reproducing the wrap, not avoiding it.
        cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
        VarkaChronoLowering.emitFloorMod7(cb, node, analysis, s);
        // result = d + r + 1, wrapping again.
        cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VV);
        cb.loadConstant(1);
        cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
        // A column weekday can be null on its own, so the node's word is the AND of
        // both inputs' words, stored here as AddMonths does by hand; a literal weekday is the
        // all-true word and planWordRef aliases the date's, so nothing is stored.
        if (!dense && s.ownWord.contains(node)) {
          emitAndWord(cb, s, s.wordRef.get(node), s.wordRef.get(n.days()),
              s.wordRef.get(n.offset()));
        }
      }
      case Year n -> VarkaChronoLowering.emitChrono(cb, node, dense, analysis, s, computed);
      case Month n -> VarkaChronoLowering.emitChrono(cb, node, dense, analysis, s, computed);
      case DayOfMonth n -> VarkaChronoLowering.emitChrono(cb, node, dense, analysis, s, computed);
      case Quarter n -> VarkaChronoLowering.emitChrono(cb, node, dense, analysis, s, computed);
      case DayOfYear n -> VarkaChronoLowering.emitChrono(cb, node, dense, analysis, s, computed);
      case AddMonths n -> VarkaChronoLowering.emitAddMonths(cb, n, dense, analysis, s, computed);
      case LastDay n -> VarkaChronoLowering.emitChrono(cb, node, dense, analysis, s, computed);
      case TruncDate n -> VarkaChronoLowering.emitChrono(cb, node, dense, analysis, s, computed);
      case TruncDateDynamic n -> {
        VarkaChronoLowering.emitChrono(cb, node, dense, analysis, s, computed);
        // A column level can be null on its own, so the node's word is the AND of
        // both inputs' words - NextDay's rule for its column weekday.
        if (!dense && s.ownWord.contains(node)) {
          emitAndWord(cb, s, s.wordRef.get(node), s.wordRef.get(n.days()),
              s.wordRef.get(n.level()));
        }
      }
      case MakeDate n -> VarkaChronoLowering.emitMakeDate(cb, n, dense, analysis, s, computed);
      case WeekOfYear n -> VarkaChronoLowering.emitChrono(cb, node, dense, analysis, s, computed);
      case Greatest n -> emitPick(cb, n, n.left(), n.right(), "max", dense, analysis, s,
          computed);
      case Least n -> emitPick(cb, n, n.left(), n.right(), "min", dense, analysis, s,
          computed);
      case IfElse n -> {
        emitCond(cb, n.cond(), dense, analysis, s, computed);
        emitValue(cb, n.elseNode(), dense, analysis, s, computed);
        emitValue(cb, n.thenNode(), dense, analysis, s, computed);
        line(cb, analysis, node);
        if (dense) {
          cb.aload(s.condMask.get(n.cond()));
        } else {
          cb.aload(s.species(lane));
          cb.lload(s.kt.get(n.cond()));
          emitMaskOfWord(cb, analysis);
        }
        cb.invokevirtual(lane.vector, "blend", lane.blend);
        // A narrow pick passes its arms' unreduced int values through - a blend commutes with
        // the reduction - and reduces once here where its reader is not a ring node.
        if (n.laneType().isNarrow() && analysis.wrapAt.contains(n)) {
          emitWrapToNarrow(cb, lane, n.laneType());
        }
        if (!dense && s.ownWord.contains(node)) {
          // valid = (kT & validThen) | (~kT & validElse), the chosen branch's validity.
          cb.lload(s.kt.get(n.cond()));
          loadWord(cb, s, s.wordRef.get(n.thenNode()));
          cb.land();
          cb.lload(s.kt.get(n.cond()));
          cb.loadConstant(-1L);
          cb.lxor();
          loadWord(cb, s, s.wordRef.get(n.elseNode()));
          cb.land();
          cb.lor();
          storeWord(cb, s, s.wordRef.get(node));
        }
      }
      case CondValue n -> {
        emitCond(cb, n.cond(), dense, analysis, s, computed);
        line(cb, analysis, node);
        // zero.add(1, kT): 1 where the condition is known true, 0 elsewhere. An unknown lane
        // holds 0 and is null by the word below, so only the known-false lanes read the 0.
        cb.aload(s.species(lane));
        lane.pushScalar(cb, 0);
        cb.invokestatic(lane.vector, "broadcast", lane.broadcast);
        lane.pushScalar(cb, 1);
        if (dense) {
          cb.aload(s.condMask.get(n.cond()));
        } else {
          cb.aload(s.species(lane));
          cb.lload(s.kt.get(n.cond()));
          emitMaskOfWord(cb, analysis);
        }
        cb.invokevirtual(lane.vector, "add", lane.lanewiseVIMasked);
        if (!dense && s.ownWord.contains(node)) {
          // valid = kT | kF: known either way. The dense body has no unknown lane (a condition
          // that makes one from valid inputs keeps the kernel masked-only).
          emitOr(cb, s.kt.get(n.cond()), s.kf.get(n.cond()));
          storeWord(cb, s, s.wordRef.get(node));
        }
      }
      // A root only, written by VarkaBodyEmitter.emitBoolOutput; Analysis refuses it below one.
      case BoolOutput b -> throw VarkaErrors.illegalState(
          "boolean output in a value position survived validation: " + b);
      case Cond c -> throw VarkaErrors.illegalState(
          "condition node in a value position survived validation: " + c);
    }
    if (shared != null) {
      cb.dup();
      cb.astore(shared);
      computed.add(node);
    }
  }

  /**
   * {@code lane}'s species as the constant its class declares, for a conversion's target. Not
   * the species local: {@code convertShape} intrinsifies only where C2 sees the target's lane
   * count as a constant, which it does through the static field and not through a local holding
   * it ("missing constant ... vlen_to=LoadI" under {@code PrintIntrinsics}, and the conversion
   * then runs as Java, `VARKA-28.md` 9.2).
   */
  private static void emitConstantSpecies(CodeBuilder cb, Lane lane, Analysis analysis) {
    cb.getstatic(lane.vector, lane.speciesField(analysis.lanesOf(lane)), VECTOR_SPECIES);
  }

  /**
   * An int column in a kernel driven at the long lane: the group's rows into the low lanes of the
   * int species of the same width. In a loop body the load is whole, so the upper lanes hold the
   * next group's rows, which that group reads again; in the epilogue it is under the mask of the
   * int species' first {@code lanes} lanes, the remainder, and the upper lanes read zero, as an
   * epilogue's padding lanes do. Either way only the low lanes are the group's. The offset is
   * {@code (long) i * 4}, a linear function of the induction variable that C2 folds into the
   * load's address, as {@code VarkaBodyEmitter.emitNarrowStore} derives its own.
   */
  private static void emitLowHalfLoad(CodeBuilder cb, ColumnRef c, Slots s) {
    int species = s.species(Lane.INT);
    cb.aload(species);
    cb.aload(s.srcSeg(c.ordinal()));
    cb.iload(s.iVar);
    cb.i2l();
    cb.loadConstant(Lane.INT.byteStride);
    cb.lmul();
    cb.getstatic(BYTE_ORDER, "LITTLE_ENDIAN", BYTE_ORDER);
    if (!s.epilogue) {
      // In the loop, whole: the loop stops while a whole int vector still fits
      // (`VarkaBodyEmitter.emitSpecies`), and the upper lanes are the next group's rows.
      cb.invokestatic(Lane.INT.vector, "fromMemorySegment", Lane.INT.fromMemorySegmentDense);
      return;
    }
    cb.aload(species);
    cb.loadConstant(0);
    cb.iload(s.lanes);
    cb.invokeinterface(VECTOR_SPECIES, "indexInRange", INDEX_IN_RANGE);
    cb.invokestatic(Lane.INT.vector, "fromMemorySegment", Lane.INT.fromMemorySegmentMasked);
  }

  /** The bytes of one Arrow decimal slot: the low word, then the high word. */
  static final long DECIMAL_SLOT_BYTES = 16L;

  /**
   * A {@link DecimalColumn}: the group's low words, left on the operand stack, with its slots
   * checked.
   *
   * <p>A lane group of {@code n} rows is {@code 2n} longs of slots, laid out
   * {@code (lo0, hi0, lo1, hi1, ...)}, so it is two vectors: {@code a} at the group's first slot
   * and {@code b} one vector further on, each holding half the rows. The two-vector
   * {@code rearrange} under the prologue's even-lane shuffle takes lanes {@code 0, 2, 4, ...} of
   * {@code a} and then of {@code b} - the low words, in row order - and the odd-lane shuffle the
   * high words the same way ({@code Slots#decimalShuffle}). In the epilogue both loads are masked
   * to the {@code 2 * lanes} longs of the remaining rows, so neither reads past the batch.
   *
   * <p>The check condemns a lane whose high word is not {@code lo >> 63} or whose low word is
   * outside {@code [-(10^p - 1), 10^p - 1]}: the odd shuffle, a shift, an add and two compares,
   * the second unsigned, ORed into one mask, which {@link #emitGuardCollect} qualifies by the
   * column's validity word in a masked body, so a null slot never declines, and folds into the
   * body's accumulator, tested once the body is done.
   */
  private static void emitDecimalLoad(CodeBuilder cb, DecimalColumn c, boolean dense,
      Analysis analysis, Slots s) {
    Lane lane = Lane.LONG;
    int[] halves = s.decimalTmp.get(c);
    Integer low = s.guardTmp.get(c);
    if (analysis.laneOf(c) != lane || analysis.lane != lane) {
      throw VarkaErrors.illegalState("a decimal column read in a " + analysis.lane
          + " emission: " + VarkaVectorIR.canonical(c));
    }
    if (halves == null || low == null || s.decimalShuffle == null) {
      // Refused by name: without its locals the check would be dropped silently.
      throw VarkaErrors.illegalState("a decimal column with no locals planned: "
          + VarkaVectorIR.canonical(c));
    }
    line(cb, analysis, c);
    for (int half = 0; half < 2; half++) {
      cb.aload(s.species);
      cb.aload(s.srcSeg(c.ordinal()));
      cb.iload(s.iVar);
      cb.i2l();
      cb.loadConstant(DECIMAL_SLOT_BYTES);
      cb.lmul();
      if (half == 1) {
        cb.aload(s.species);
        cb.invokeinterface(VECTOR_SPECIES, "vectorByteSize", SPECIES_LENGTH);
        cb.i2l();
        cb.ladd();
      }
      cb.getstatic(BYTE_ORDER, "LITTLE_ENDIAN", BYTE_ORDER);
      if (s.epilogue) {
        // The longs of the remaining rows' slots that this vector covers: from 0 in `a`, from
        // the vector's length in `b`, up to 2 * lanes.
        cb.aload(s.species);
        if (half == 0) {
          cb.loadConstant(0);
        } else {
          cb.aload(s.species);
          cb.invokeinterface(VECTOR_SPECIES, "length", SPECIES_LENGTH);
        }
        cb.iload(s.lanes);
        cb.iconst_1();
        cb.ishl();
        cb.invokeinterface(VECTOR_SPECIES, "indexInRange", INDEX_IN_RANGE);
        cb.invokestatic(lane.vector, "fromMemorySegment", lane.fromMemorySegmentMasked);
      } else {
        cb.invokestatic(lane.vector, "fromMemorySegment", lane.fromMemorySegmentDense);
      }
      cb.astore(halves[half]);
    }
    // lo = a.rearrange(EVEN, b), parked for the check and left on the stack for the parent.
    emitRearrange(cb, halves, s.decimalShuffle[0]);
    cb.dup();
    cb.astore(low);
    // hi.compare(NE, lo >> 63)
    emitRearrange(cb, halves, s.decimalShuffle[1]);
    cb.getstatic(VECTOR_OPERATORS, "NE", VO_COMPARISON);
    cb.aload(low);
    cb.getstatic(VECTOR_OPERATORS, "ASHR", VO_BINARY);
    cb.loadConstant((long) (Long.SIZE - 1));
    cb.invokevirtual(lane.vector, "lanewise", lane.integral().lanewiseBinaryI());
    cb.invokevirtual(lane.vector, "compare", lane.compareVV);
    // | (lo + B) UGT 2B, with B = 10^p - 1: lo outside [-B, B] in one unsigned compare. A lo
    // inside moves to [0, 2B]; one above B lands past 2B, wrapping or not, and one below -B
    // wraps to at least 2^63 + B, past 2B too. It holds for any bound that is a non-negative
    // long (decimal_slot.smt2), and 2B fits one at precision 18 or less.
    long bound = c.bound();
    cb.aload(low);
    cb.getstatic(VECTOR_OPERATORS, "ADD", VO_ASSOCIATIVE);
    cb.loadConstant(bound);
    cb.invokevirtual(lane.vector, "lanewise", lane.integral().lanewiseBinaryI());
    cb.getstatic(VECTOR_OPERATORS, "UGT", VO_COMPARISON);
    cb.loadConstant(2 * bound);
    cb.invokevirtual(lane.vector, "compare", lane.compareVI);
    cb.invokevirtual(VECTOR_MASK, "or", MASK_BINARY);
    emitGuardCollect(cb, c, dense ? null : s.wordRef.get(c), dense, analysis, s);
  }

  /** {@code a.rearrange(shuffle, b)} over the two parked halves of a decimal lane group. */
  private static void emitRearrange(CodeBuilder cb, int[] halves, int shuffle) {
    cb.aload(halves[0]);
    cb.aload(shuffle);
    cb.aload(halves[1]);
    cb.invokevirtual(LONG_VECTOR, "rearrange", REARRANGE_TWO);
  }

  /**
   * The value on the stack, of lane {@code from}, as lane {@code to}: {@code I2L} and
   * {@code L2I}, part 0, each into the target species of the width the emission bakes. The two
   * lanes the IR converts between are the int and the long one; a narrow value is widened once at
   * its load and narrowed once at its store, so a conversion node over one names the lane it is
   * already emitted at and emits nothing, and any other pair is a lowering this emitter has not
   * built.
   */
  private static void emitShapeConversion(
      CodeBuilder cb, Lane from, Lane to, Analysis analysis) {
    if (from == to) {
      return;
    }
    String op = from == Lane.INT && to == Lane.LONG ? "I2L"
        : from == Lane.LONG && to == Lane.INT ? "L2I"
        : null;
    if (op == null) {
      throw VarkaErrors.illegalArgument(
          "no conversion from the " + from + " lane to the " + to + " lane");
    }
    cb.getstatic(VECTOR_OPERATORS, op, VO_CONVERSION);
    emitConstantSpecies(cb, to, analysis);
    cb.loadConstant(0);
    cb.invokevirtual(VECTOR, "convertShape", CONVERT_SHAPE);
    cb.checkcast(to.vector);
  }

  /**
   * A narrow column in a kernel driven at the int lane: the group's rows loaded from their own
   * stride into a narrow species and sign-extended into the int species, part 0. The species is
   * the narrow one of the int species' width wherever it exists - as many narrow lanes as the
   * int species has, which is the width {@code convertShape} reads a part from. Where it does
   * not - a byte column at four int lanes would need a 32-bit species - the load reads the next
   * width up, and each of its parts is one int group: the loop computes as many groups an
   * iteration as the load has parts, every group reads the same whole load starting at the
   * iteration's first row, and its own part selects its rows ({@link Slots#rowGroup}). In a loop
   * body the load is whole, like {@link #emitLowHalfLoad}'s; in the epilogue it is under the mask
   * of the species' first {@code lanes} lanes and reads part 0. The offset is
   * {@code (long) i * stride}, a linear function of the induction variable that C2 folds into the
   * address.
   */
  private static void emitNarrowLoad(
      CodeBuilder cb, ColumnRef c, Lane stored, Analysis analysis, Slots s) {
    int groups = analysis.narrowGroups();
    // One whole iteration's rows in one species wherever the load has to span more than a
    // group: every narrow lane of the body reads the same rows and each group takes its part.
    int loadLanes =
        groups > 1 ? groups * analysis.lanesOf(Lane.INT) : analysis.widenLanes(stored);
    // A load with more parts than one int group: the loop's every group of an iteration loads
    // the same rows and takes its own part; the epilogue's one group takes part 0.
    boolean parts = groups > 1 && !s.epilogue;
    String species = stored.speciesField(loadLanes);
    cb.getstatic(stored.vector, species, VECTOR_SPECIES);
    cb.aload(s.srcSeg(c.ordinal()));
    cb.iload(s.iVar);
    if (parts) {
      // The iteration's first row, from this group's own: every part is a block of the
      // destination species' lanes, so part 0 of one load is the iteration's first group.
      cb.loadConstant(s.rowGroup * analysis.lanes);
      cb.isub();
    }
    cb.i2l();
    cb.loadConstant(stored.byteStride);
    cb.lmul();
    cb.getstatic(BYTE_ORDER, "LITTLE_ENDIAN", BYTE_ORDER);
    if (s.epilogue) {
      cb.getstatic(stored.vector, species, VECTOR_SPECIES);
      cb.loadConstant(0);
      cb.iload(s.lanes);
      cb.invokeinterface(VECTOR_SPECIES, "indexInRange", INDEX_IN_RANGE);
      cb.invokestatic(stored.vector, "fromMemorySegment", stored.fromMemorySegmentMasked);
    } else {
      cb.invokestatic(stored.vector, "fromMemorySegment", stored.fromMemorySegmentDense);
    }
    cb.getstatic(VECTOR_OPERATORS, stored == Lane.BYTE ? "B2I" : "S2I", VO_CONVERSION);
    emitConstantSpecies(cb, Lane.INT, analysis);
    cb.loadConstant(parts ? s.rowGroup : 0);
    cb.invokevirtual(VECTOR, "convertShape", CONVERT_SHAPE);
    cb.checkcast(Lane.INT.vector);
  }

  /**
   * {@code IntArith}: the lanewise op, then - in `FAIL` and `NULL` - the overflow check. The check
   * is the standard sign-based test, which needs both operands and the result, and the lanewise
   * call has consumed the operands off the stack by the time the result exists, so all three are
   * parked in {@link Slots#intArithTmp} first. `emitRangeGuard` parks its one value for the same
   * reason.
   *
   * <p>{@code ADD} overflows exactly where the operands share a sign that the result does not:
   * {@code ((a ^ r) & (b ^ r)) < 0}. {@code SUB} overflows where the operands differ in sign
   * and the result differs from the left: {@code ((a ^ b) & (a ^ r)) < 0}. Both are four
   * lanewise ops and a compare, and neither branches.
   *
   * <p>{@code MUL} has no such test in int lanes - the honest check needs the 64-bit product,
   * or a division the lane loop must not do (VARKA-11.md priced lanewise DIV at 8x) - so
   * the compiler declines a checked int multiply outright and only {@code WRAP} reaches here
   * from the int lane. That is wider than VARKA-63.md 3.3 assumed; see the correction there.
   * A checked narrow multiply does reach here, emitted at the int lane on either arm, where its
   * exact product fits and is range-checked against the narrow lane (below).
   *
   * <p>The narrow lanes check differently, because they overflow at their own width
   * ({@code VARKA-329.md} 4.2, 4.3). A checked narrow node at the int lane - the widened arm,
   * and the arm a tree holding a checked narrow multiply is driven at under either option - has
   * the exact int result of two narrow operands and tests it against the node lane's own range:
   * one compare at each end, then the mode's disposal. On the narrow lane the check is
   * `add != sadd` (`sub != ssub`), the saturating operation being the one the wrap differs from
   * exactly where the exact result leaves the range. A wrapping narrow node on the int lane
   * leaves its int result unreduced where a ring consumer can take it, and reduces it with the
   * shift pair where it cannot ({@link Analysis#wrapAt}).
   *
   * <p>Where the mask goes is what separates the two checked modes. {@code FAIL} folds it into
   * the batch's condemning accumulator through {@link #emitGuardCollect}, so the batch declines
   * and the row engine raises Spark's own error. {@code NULL} clears those lanes from the
   * node's own validity word instead, so the row is null and the batch runs on - which is why
   * `analyze` marks a `NULL` node as one that nulls valid inputs.
   *
   * <p>The {@code FAIL} route inherits {@link #emitGuardCollect}'s untaken-arm cliff, which is the
   * untaken-arm case: the mask is ANDed with the node's word and the epilogue mask but not with an
   * enclosing {@code IfElse}'s condition, and a vector body computes both arms, so a checked node
   * under a {@code CASE} arm condemns the batch from a lane the condition would have sent the other
   * way. Answers stay right - the row engine recomputes the batch - and only the fusion is lost, on
   * exactly the data the check exists for.
   */
  private static void emitIntArith(CodeBuilder cb, IntArith n, boolean dense, Analysis analysis,
      Slots s, Set<VarkaVectorIR> computed) {
    Lane lane = analysis.laneOf(n);
    String op = switch (n.op()) {
      case ADD -> "add";
      case SUB -> "sub";
      case MUL -> "mul";
    };
    boolean narrow = n.laneType().isNarrow();
    // Before the scratch-slot test, because the refusal is about the node and not about how
    // this body was configured: with `checkIntOverflow` off there is no scratch, and a checked
    // multiply would otherwise slip through as a plain wrapping one.
    //
    // Note what the argument is not. That switch *does* change meaning for the other checked
    // nodes, deliberately: with it off a `FAIL` add wraps and a `NULL` add answers the wrapped
    // number where Spark returns null, which is what its own javadoc promises and what makes
    // it a reference arm rather than a setting. `MUL` is different in kind - there is no
    // correct checked emission for it at all, so "off" cannot mean "the same node, cheaper".
    // A reader who generalises this into the `NULL` and `IntNeg` arms breaks the benchmark.
    // A narrow checked multiply is the exception that keeps the argument, but not by computing
    // in the lane: its kernel is driven at the int lane on both arms, where the product of two
    // narrow operands is exact and the range compare is sound.
    if (n.op() == IntOp.MUL && n.mode() != Overflow.WRAP && !narrow) {
      throw VarkaErrors.illegalArgument("a checked multiply has no int-lane overflow test: " + n);
    }
    int[] tmp = s.intArithTmp.get(n);
    if (tmp == null) {
      // WRAP, or the check switched off for the A/B: the plain lanewise op, whose word is the
      // operands' AND like every other null-intolerant binary node. A wrapping narrow node on
      // the int lane leaves its int result unreduced for a ring consumer, and reduces it here
      // where the first consumer that is not one reads it.
      emitAndValidatedOp(cb, n, n.left(), n.right(), op, lane.lanewiseVV, dense,
          analysis, s, computed);
      if (narrow && analysis.wrapAt.contains(n)) {
        emitWrapToNarrow(cb, lane, n.laneType());
      }
      return;
    }
    int a = tmp[0];
    int b = tmp[1];
    int r = tmp[2];
    emitValue(cb, n.left(), dense, analysis, s, computed);
    emitValue(cb, n.right(), dense, analysis, s, computed);
    line(cb, analysis, n);
    cb.dup2();
    cb.invokevirtual(lane.vector, op, lane.lanewiseVV);
    cb.astore(r);
    cb.astore(b);
    cb.astore(a);
    if (!dense && s.ownWord.contains(n)) {
      emitAndWord(cb, s, s.wordRef.get(n), s.wordRef.get(n.left()), s.wordRef.get(n.right()));
    }
    if (narrow) {
      emitNarrowOverflowMask(cb, n, lane, a, b, r, dense, analysis, s);
      cb.aload(r);
      return;
    }
    // The two XORs, whose operands differ between ADD and SUB, then the AND and the sign test.
    cb.aload(a);
    cb.getstatic(VECTOR_OPERATORS, "XOR", VO_ASSOCIATIVE);
    cb.aload(n.op() == IntOp.ADD ? r : b);
    cb.invokevirtual(lane.vector, "lanewise", lane.integral().lanewiseBinaryV());
    cb.getstatic(VECTOR_OPERATORS, "AND", VO_ASSOCIATIVE);
    cb.aload(n.op() == IntOp.ADD ? b : a);
    cb.getstatic(VECTOR_OPERATORS, "XOR", VO_ASSOCIATIVE);
    cb.aload(r);
    cb.invokevirtual(lane.vector, "lanewise", lane.integral().lanewiseBinaryV());
    cb.invokevirtual(lane.vector, "lanewise", lane.integral().lanewiseBinaryV());
    cb.getstatic(VECTOR_OPERATORS, "LT", VO_COMPARISON);
    lane.pushScalar(cb, 0);
    cb.invokevirtual(lane.vector, "compare", lane.compareVI);
    emitOverflowMask(cb, n.mode(), n, dense, analysis, s);
    cb.aload(r);
  }

  /**
   * The overflow mask of a checked narrow arithmetic node, left on the operand stack for
   * {@link #emitOverflowMask}: the operands and the result are in {@code a}, {@code b} and
   * {@code r}.
   *
   * <p>At the int lane the result is exact, so the mask is the two compares against the node
   * lane's own range. On the narrow lane it is the saturating difference - {@code add != sadd},
   * {@code sub != ssub} - one lanewise op, one compare. {@code MUL} never takes the second form:
   * its tree is driven at the int lane for exactly this reason
   * ({@code VarkaVectorIR#holdsCheckedNarrowMultiply}), so reaching there is a bug in the lane
   * decision rather than a node to lower.
   */
  private static void emitNarrowOverflowMask(CodeBuilder cb, IntArith n, Lane lane, int a, int b,
      int r, boolean dense, Analysis analysis, Slots s) {
    if (!lane.isNarrow()) {
      Lane of = Lane.of(n.laneType());
      cb.aload(r);
      cb.getstatic(VECTOR_OPERATORS, "LT", VO_COMPARISON);
      lane.pushScalar(cb, of.mostNegative());
      cb.invokevirtual(lane.vector, "compare", lane.compareVI);
      cb.aload(r);
      cb.getstatic(VECTOR_OPERATORS, "GT", VO_COMPARISON);
      lane.pushScalar(cb, of.mostPositive());
      cb.invokevirtual(lane.vector, "compare", lane.compareVI);
      cb.invokevirtual(VECTOR_MASK, "or", MASK_BINARY);
    } else {
      if (n.op() == IntOp.MUL) {
        throw VarkaErrors.illegalArgument(
            "a checked narrow multiply is emitted at the int lane: " + n);
      }
      cb.aload(a);
      cb.aload(b);
      cb.getstatic(VECTOR_OPERATORS, n.op() == IntOp.ADD ? "SADD" : "SSUB", VO_BINARY);
      cb.swap();
      cb.invokevirtual(lane.vector, "lanewise", lane.integral().lanewiseBinaryV());
      cb.aload(r);
      cb.getstatic(VECTOR_OPERATORS, "NE", VO_COMPARISON);
      cb.swap();
      cb.invokevirtual(lane.vector, "compare", lane.compareVV);
    }
    emitOverflowMask(cb, n.mode(), n, dense, analysis, s);
  }

  /**
   * Reduces the value on the stack from the lane it is emitted at to {@code type}'s width and
   * sign-extends it back: a shift left by the spare bits of that lane and an arithmetic shift
   * right by the same count. From the int lane that pair is Spark's wrap at the narrow width
   * ({@code signExtend(r mod 2^w)}, which {@code narrow_wrap.smt2} checks over every pair of
   * narrow values); the int lane's own 32-bit wraparound cannot change the answer, because
   * reducing mod 2^8 or 2^16 and then reducing mod 2^32 first agree. From the narrow lane itself
   * there is nothing to do - the lane operation already wrapped - so the pair is skipped rather
   * than emitted with a zero count.
   */
  private static void emitWrapToNarrow(CodeBuilder cb, Lane lane, VarkaVectorIR.LaneType type) {
    int shift = lane.bits - Lane.of(type).bits;
    if (shift == 0) {
      return;
    }
    cb.getstatic(VECTOR_OPERATORS, "LSHL", VO_BINARY);
    cb.loadConstant(shift);
    cb.invokevirtual(lane.vector, "lanewise", lane.integral().lanewiseBinaryI());
    cb.getstatic(VECTOR_OPERATORS, "ASHR", VO_BINARY);
    cb.loadConstant(shift);
    cb.invokevirtual(lane.vector, "lanewise", lane.integral().lanewiseBinaryI());
  }

  /**
   * {@code IntNeg}, emitted as the lanewise {@code NEG}, one subtract from zero, on every lane: a
   * multiply by -1 agrees with it on every lane, the lane's minimum ({@link Integer#MIN_VALUE},
   * {@link Long#MIN_VALUE}) included, where both return the input, but costs a multiply - eight
   * instructions on x86's byte lanes, which have no byte multiply, and an emulated sequence on an
   * AVX2 host's long lane, which has no 64-bit multiply. The overflowing value, the lane's minimum,
   * is the whole of the overflow test, so the check is one compare rather than the four ops
   * {@link #emitIntArith} needs, and it reads the operand rather than the result - no scratch slot
   * at all.
   *
   * <p>A narrow negation tests its operand against its own lane's minimum, which is the node's
   * and not the emitted lane's: under the widened arm the value is an int in the int lane, but
   * {@code -(-128Y)} is the value that overflows, not {@code -Integer.MIN_VALUE}. A wrapping
   * narrow negation on the int lane is the int negate of the exact operand, which for
   * {@code -128} is 128 rather than -128, so it reduces where a consumer cannot take the
   * unreduced value. A narrow {@code abs} is the same node with the lanewise {@code ABS}: the
   * same check on the same operand, and at the int lane a reduction of its own result wherever
   * it is read ({@link Analysis#wrapAt}).
   */
  private static void emitIntNeg(CodeBuilder cb, IntNeg n, boolean dense, Analysis analysis,
      Slots s, Set<VarkaVectorIR> computed) {
    Lane lane = analysis.laneOf(n);
    emitValue(cb, n.child(), dense, analysis, s, computed);
    line(cb, analysis, n);
    // The refusal map decides, so the emitted check and the planner's accumulator cannot disagree.
    boolean checked = analysis.refusals.get(n) == Analysis.Refusal.INT_OVERFLOW;
    if (checked) {
      cb.dup();
      cb.getstatic(VECTOR_OPERATORS, "EQ", VO_COMPARISON);
      lane.pushScalar(cb, Lane.of(n.laneType()).mostNegative());
      cb.invokevirtual(lane.vector, "compare", lane.compareVI);
      emitOverflowMask(cb, n.mode(), n, dense, analysis, s);
    }
    if (n.abs() && !n.laneType().isNarrow()) {
      throw VarkaErrors.illegalArgument("abs is a narrow lane's node only: " + n);
    }
    cb.getstatic(VECTOR_OPERATORS, n.abs() ? "ABS" : "NEG", VO_UNARY);
    cb.invokevirtual(lane.vector, "lanewise", lane.integral().lanewiseUnary());
    if (n.laneType().isNarrow() && analysis.wrapAt.contains(n)) {
      emitWrapToNarrow(cb, lane, n.laneType());
    }
  }

  /**
   * {@code BitOp}: one {@code lanewise} by the operator, the right operand a vector whether it is
   * a column or a broadcast literal, so a shift by a literal count and by a column count are one
   * emission. The Vector API's shifts mask their count to the lane's width as Java's operators
   * do. Null-intolerant, so the word is the operands' AND, as {@link #emitAndValidatedOp} stores
   * it.
   */
  private static void emitBitOp(CodeBuilder cb, BitOp n, boolean dense, Analysis analysis,
      Slots s, Set<VarkaVectorIR> computed) {
    emitValue(cb, n.left(), dense, analysis, s, computed);
    emitValue(cb, n.right(), dense, analysis, s, computed);
    line(cb, analysis, n);
    // The logical operators are declared Associative and the shifts Binary, and a getstatic
    // names the field's declared type.
    switch (n.op()) {
      case AND -> cb.getstatic(VECTOR_OPERATORS, "AND", VO_ASSOCIATIVE);
      case OR -> cb.getstatic(VECTOR_OPERATORS, "OR", VO_ASSOCIATIVE);
      case XOR -> cb.getstatic(VECTOR_OPERATORS, "XOR", VO_ASSOCIATIVE);
      case SHL -> cb.getstatic(VECTOR_OPERATORS, "LSHL", VO_BINARY);
      case SHR -> cb.getstatic(VECTOR_OPERATORS, "ASHR", VO_BINARY);
      case USHR -> cb.getstatic(VECTOR_OPERATORS, "LSHR", VO_BINARY);
    }
    // [left, right, op] -> [left, op, right], the receiver's argument order.
    cb.swap();
    Lane lane = analysis.laneOf(n);
    cb.invokevirtual(lane.vector, "lanewise", lane.integral().lanewiseBinaryV());
    if (!dense && s.ownWord.contains(n)) {
      emitAndWord(cb, s, s.wordRef.get(n), s.wordRef.get(n.left()), s.wordRef.get(n.right()));
    }
  }

  /**
   * {@code BitCount}: the popcount of the value widened to 64 bits. The long lane's is the
   * popcount itself. The int lane adds the 32 one bits a negative value gains in the widening:
   * {@code bitCount(x) + ((x >> 31) & 32)}, the operand's second copy riding the stack under the
   * popcount. The word is the operand's, since nothing here nulls a lane.
   */
  private static void emitBitCount(CodeBuilder cb, BitCount n, boolean dense, Analysis analysis,
      Slots s, Set<VarkaVectorIR> computed) {
    emitValue(cb, n.child(), dense, analysis, s, computed);
    line(cb, analysis, n);
    // The node's own lane, which in a kernel of both lanes need not be the kernel's.
    Lane lane = analysis.laneOf(n);
    if (lane == Lane.LONG) {
      cb.getstatic(VECTOR_OPERATORS, "BIT_COUNT", VO_UNARY);
      cb.invokevirtual(lane.vector, "lanewise", lane.integral().lanewiseUnary());
      return;
    }
    cb.dup();                                                       // [x, x]
    cb.getstatic(VECTOR_OPERATORS, "BIT_COUNT", VO_UNARY);
    cb.invokevirtual(lane.vector, "lanewise", lane.integral().lanewiseUnary());  // [x, pop32(x)]
    cb.swap();                                                      // [pop32(x), x]
    cb.getstatic(VECTOR_OPERATORS, "ASHR", VO_BINARY);
    cb.loadConstant(Integer.SIZE - 1);
    // [pop32(x), x >> 31]
    cb.invokevirtual(lane.vector, "lanewise", lane.integral().lanewiseBinaryI());
    cb.loadConstant(Integer.SIZE);
    cb.invokevirtual(lane.vector, "and", lane.lanewiseVI);          // [pop32(x), high]
    cb.invokevirtual(lane.vector, "add", lane.lanewiseVV);
  }

  /**
   * Consumes a {@code VectorMask} of overflowing lanes and disposes of it as the mode says:
   * {@code FAIL} condemns the batch through the shared accumulator, {@code NULL} clears those
   * lanes from the node's own validity word. Narrowing a word after it was stored is what makes
   * such a node able to null a lane whose inputs were both valid, and there is one sibling that
   * does the same thing: {@code make_date}'s non-ANSI tail in {@code emitMakeDate}, which ANDs
   * the node's word with its validity mask so an invalid date is a null rather than an error.
   * The two are deliberately not one helper. They narrow by opposite polarities - this arm by
   * the complement of its mask, {@code emitMakeDate} by the mask itself - and take the mask from
   * different places, this one off the operand stack and that one out of a local, so a shared
   * helper would have to reorder the {@code land} operands at one of the two sites. That is a
   * bytecode change to a shape whose committed benchmark numbers and pinned op counts describe
   * the bytes as they are, which is a real cost for three lines. What the pair does need is to
   * stay findable from each other, which is what this paragraph and its twin there are for.
   */
  private static void emitOverflowMask(CodeBuilder cb, Overflow mode, VarkaVectorIR node,
      boolean dense, Analysis analysis, Slots s) {
    if (mode == Overflow.FAIL) {
      // A collect the refusal map does not declare would find no accumulator planned for it;
      // refused here by name rather than met as a null slot.
      if (analysis.refusals.get(node) != Analysis.Refusal.INT_OVERFLOW) {
        throw VarkaErrors.illegalState("an overflow collect with no refusal declared: " + node);
      }
      emitGuardCollect(cb, node, dense ? null : s.wordRef.get(node), dense, analysis, s);
      return;
    }
    // NULL: word &= ~overflow. A dense body has no word to narrow, and `emit` builds none at
    // all for a kernel whose analysis set nullsFromValidInputs - which every NULL node does -
    // so reaching here dense means that invariant broke. Refused rather than papered over with
    // a pop, which would silently drop the check and answer where Spark returns null.
    //
    // The two sibling impossibilities are refused earlier, not by a throw at this same site: a
    // NULL IntNeg never reaches emission at all, because analyze() throws on it while walking
    // every root (Spark has no try_negative, so this can only mean a bug upstream); a checked
    // int MUL is declined by the compiler and, failing that, throws inside emitIntArith itself
    // before this method would ever see it. A checked narrow MUL is the exception: it is
    // driven at the int lane on either arm and arrives here through emitNarrowOverflowMask
    // (VARKA-330), so op == MUL cannot be asserted at this site; nor can the node's type, since
    // a NULL GuardedRange hands this arm the mask of its two range compares. A future refactor
    // that unifies the overflow dispatch across IntArith, IntNeg and GuardedRange has to keep
    // all of these in view, not assume the shape of one implies the others.
    //
    // "Refused" is a claim about this class, not about a running query: `VarkaKernelEvaluator`
    // catches an IllegalArgumentException out of `emit` as an emission failure and drops the
    // whole task to the row path, so in production a broken invariant is a warned
    // de-optimisation and not a crash. What makes these refusals load-bearing is that the
    // emitter suite asserts each of them; the throw is how the assertion has something to
    // catch, not a runtime guarantee.
    if (dense) {
      throw VarkaErrors.illegalArgument(
          "a NULL-mode overflow mask reached a dense body, which has no word to narrow: " + node);
    }
    // Not ANDed with the epilogue mask, unlike the FAIL arm's collect: a tail lane above the
    // row count can test as overflowing (a literal operand is broadcast to every lane while a
    // column's is zero-filled by the masked load), and clearing its validity bit is harmless
    // because no consumer reads a bit past the row count - the store is masked too. The FAIL
    // arm cannot be so relaxed: its mask leaves the lane group in the accumulator and would
    // condemn the whole batch from a row that does not exist.
    cb.invokevirtual(VECTOR_MASK, "toLong", TO_LONG);
    cb.loadConstant(-1L);
    cb.lxor();
    loadWord(cb, s, s.wordRef.get(node));
    cb.land();
    storeWord(cb, s, s.wordRef.get(node));
  }

  /** {@code lstore(own, ref(a) & ref(b))} - the null-intolerant word rule. */
  static void emitAndWord(CodeBuilder cb, Slots s, int own, int a, int b) {
    loadWord(cb, s, a);
    loadWord(cb, s, b);
    cb.land();
    storeWord(cb, s, own);
  }

  /**
   * The shape shared by {@code AddDays}, {@code SubDays} and {@code DateDiff}: two children,
   * one lanewise binary op, and - in the masked body, when the node needs its own word - the
   * null-intolerant AND-of-validity-words rule ({@link #emitAndWord}). Factored so the AND
   * cannot be dropped on one arm and not another the way it was once, silently, before a
   * dedicated test caught it.
   */
  private static void emitAndValidatedOp(CodeBuilder cb, VarkaVectorIR node, VarkaVectorIR left,
      VarkaVectorIR right, String op, MethodTypeDesc desc, boolean dense, Analysis analysis,
      Slots s, Set<VarkaVectorIR> computed) {
    Lane lane = analysis.laneOf(node);
    emitValue(cb, left, dense, analysis, s, computed);
    emitValue(cb, right, dense, analysis, s, computed);
    line(cb, analysis, node);
    cb.invokevirtual(lane.vector, op, desc);
    if (!dense && s.ownWord.contains(node)) {
      emitAndWord(cb, s, s.wordRef.get(node), s.wordRef.get(left), s.wordRef.get(right));
    }
    Integer guardTmp = s.guardTmp.get(node);
    if (guardTmp != null) {
      // The producer guard covers this node's own result, so the word that qualifies it is this
      // node's.
      emitRangeGuard(cb, node, dense ? null : s.wordRef.get(node), guardTmp, dense, analysis, s,
          VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_MAX_DAYS);
    }
  }

  /**
   * The runtime range guard on a column-driven producer's own result: lanes outside
   * {@code [lo, hi]} are ORed into {@link Slots#guardAcc}, and {@code emitStatusReturn} turns a
   * non-empty accumulator into the guard's refusal bit, which the evaluator answers by
   * recomputing the batch on the row engine. The producer guard calls this on the result of a
   * column-offset {@code AddDays} / {@code SubDays} some calendar node reads, with {@code lo} /
   * {@code hi} = {@link VarkaChrono#NARROW_MIN_DAYS} / {@link VarkaChrono#NARROW_MAX_DAYS}; task
   * 60 calls it on {@code AddMonths} ' own month count, with {@code lo} / {@code hi} =
   * {@link VarkaChrono#MONTH_ARITH_MIN_MONTHS} / {@link VarkaChrono#MONTH_ARITH_MAX_MONTHS} - two
   * compares are two compares regardless of what they bound. The guarded value stays on the operand
   * stack for the caller; it is parked in {@code guardTmp} only for the compares.
   *
   * <p>{@code word} is the validity word to AND the out-of-range mask with in a masked body, or
   * null in a dense one. The caller passes it rather than this method looking it up from a node,
   * because the value being guarded and the word that qualifies it are not always the same node's:
   * a producer guard covers a node's own result under that node's own word, while the month-count
   * guard covers an operand under the {@code AddMonths} node's word. Resolving it here from one
   * node reference would make those two cases indistinguishable, and reading the word slot before
   * the arm that fills it fails verification; making the caller state it keeps the two
   * facts together at the site that knows both.
   *
   * <p>The guard checks a producer's output rather than each extraction's input - so it runs
   * once per distinct producer rather than once per calendar node reading it, and not at all for
   * the shapes the compiler bounds. The set is keyed on the guarded node, not on the operand it
   * checks, so two {@code AddMonths} over one count column each emit their own guard over that
   * column - redundant, not wrong, and the price of keying on the node that owns the validity word
   * the guard has to AND with. Two ANDs carry over unchanged and for the same reasons: with the
   * node's validity word in the masked body, because a null row's lanes are undefined and must not
   * condemn the batch (the node's word is the AND of every input, so a null offset or a null count
   * is covered), and with the epilogue's bounds mask, because a partial group's padding lanes hold
   * whatever the masked load left. The dense body skips the word AND: every lane is valid there.
   * So does a guard over a column's stored values in every body
   * ({@link Analysis#guardsStoredValues}); in a loop it is not emitted here at all but as a min
   * and a max ({@link #emitRangeAccumulate}). A producer used more than once is emitted once per
   * lane group under CSE, guard included; with CSE off it is re-emitted per use, which repeats the
   * guard - correct, merely redundant, and not a shape production emits.
   */
  static void emitRangeGuard(CodeBuilder cb, VarkaVectorIR node, Integer word,
      int guardTmp, boolean dense, Analysis analysis, Slots s, long lo, long hi) {
    emitRangeMask(cb, node, guardTmp, analysis, lo, hi);
    emitGuardCollect(cb, node, word, dense, analysis, s);
  }

  /**
   * {@code [v] -> [v, mask]}: the lanes of the value on the stack outside {@code [lo, hi]}, two
   * compares and an OR over the value parked in {@code guardTmp}, the value left beneath the mask
   * for the caller. {@link #emitRangeGuard} folds the mask into the batch's accumulator, and a
   * {@code NULL} range guard clears those lanes from its own word instead.
   */
  private static void emitRangeMask(CodeBuilder cb, VarkaVectorIR node, int guardTmp,
      Analysis analysis, long lo, long hi) {
    Lane lane = analysis.laneOf(node);
    cb.dup();
    cb.astore(guardTmp);
    cb.aload(guardTmp);
    cb.getstatic(VECTOR_OPERATORS, "LT", VO_COMPARISON);
    lane.pushScalar(cb, lo);
    cb.invokevirtual(lane.vector, "compare", lane.compareVI);
    cb.aload(guardTmp);
    cb.getstatic(VECTOR_OPERATORS, "GT", VO_COMPARISON);
    lane.pushScalar(cb, hi);
    cb.invokevirtual(lane.vector, "compare", lane.compareVI);
    cb.invokevirtual(VECTOR_MASK, "or", MASK_BINARY);
  }

  /**
   * A column's range guard in a loop body ({@link Analysis#accumulatesRange}): the guarded vector,
   * left on the operand stack for the caller, goes into the lane-wise min at {@code acc} and the
   * lane-wise max at {@code acc + 1} ({@link Slots#rangeAcc}), two ops a lane group with no mask
   * and no branch, where {@link #emitRangeGuard} takes two compares and two ORs and a masked body
   * also turned the validity word into a mask. The bounds are compared once, after the loop
   * ({@code VarkaBodyEmitter.emitRangeFold}), so a far day anywhere in the loop still declines the
   * batch, from the same status bit (VARKA-379 11).
   */
  static void emitRangeAccumulate(CodeBuilder cb, VarkaVectorIR node, int acc,
      Analysis analysis) {
    Lane lane = analysis.laneOf(node);
    for (int k = 0; k < 2; k++) {
      cb.dup();
      cb.aload(acc + k);
      cb.swap();
      cb.invokevirtual(lane.vector, k == 0 ? "min" : "max", lane.lanewiseVV);
      cb.astore(acc + k);
    }
    analysis.reach(VarkaEmitMechanism.guard(analysis.refusals.get(node)));
    analysis.reach(VarkaEmitMechanism.GUARD_MIN_MAX);
  }

  /**
   * Consumes a {@code VectorMask} of lanes that condemn the batch and folds it into the body's
   * accumulator: ANDed with {@code word}, the lanes' validity, in a masked body (a null lane is not
   * out of range; {@code null} or the all-true constant skips the AND), ANDed with the epilogue's
   * bounds mask when there is one, ORed into {@code guardAcc}. The tail of the producer guard,
   * shared with the self-guarding nodes.
   *
   * <p>It is also ANDed with the enclosing {@code IfElse} arms' condition, where {@code node}'s
   * uses all sit under the same chain of them ({@link #emitArmContext}). A vector body computes
   * both arms and a guard condemns rather than producing a value the blend can discard, so without
   * that a lane the condition sends to the other arm declines the batch it is in. The batch fell
   * back and the answers stayed right; the fusion was what was lost.
   */
  static void emitGuardCollect(CodeBuilder cb, VarkaVectorIR node, Integer word,
      boolean dense, Analysis analysis, Slots s) {
    // In a kernel that converts between lanes, an int-lane guard's mask has the int species'
    // lanes, the low half of them the group's rows; the accumulator, the words' masks, the arms'
    // and the epilogue's are the driving lane's. Its low lanes, through a word, are that mask.
    if (analysis.laneOf(node) != analysis.lane) {
      emitToDrivingLane(cb, s);
    }
    // WORD_DEAD is deliberately not screened here beside the all-true constant. A guarded node
    // whose word the liveness pass killed is a bug in that pass, not a case to emit around: the AND
    // is what keeps a null lane from condemning the batch, so skipping it quietly would turn a
    // liveness error into spurious batch declines on nullable data. `loadWord` refuses instead, and
    // that refusal is what {@code misdescribeWordLiveness} arms in the "loaded" direction. What
    // makes the case unreachable is {@link #guardedWord}, which the liveness pass reads and which
    // contains {@link #guardScratch}, the slot planner's predicate, by construction - so the
    // planner cannot decide this node is guarded while the liveness pass decides its word is dead.
    // It was one predicate, but its two readers ask different questions; the containment is what
    // preserves the property. One guard sits outside it on purpose: the column bound
    // (Analysis.guardsStoredValues) takes a scratch slot in the epilogue and under arms while
    // demanding no word, and is safe because its callers pass a null word, so it never reaches
    // `loadWord` (VARKA-379 11).
    if (!dense && word != null && word != WORD_ALL_TRUE) {
      cb.aload(s.species);
      loadWord(cb, s, word);
      emitMaskOfWord(cb, analysis);
      cb.invokevirtual(VECTOR_MASK, "and", MASK_BINARY);
    }
    emitArmContext(cb, node, dense, analysis, s);
    if (s.epilogue) {
      cb.aload(s.epilogueMask());
      cb.invokevirtual(VECTOR_MASK, "and", MASK_BINARY);
    }
    int acc = guardAcc(node, analysis, s);
    cb.aload(acc);
    cb.invokevirtual(VECTOR_MASK, "or", MASK_BINARY);
    cb.astore(acc);
    // What this guard reached (VARKA-280): its reason, the arms that qualify it, and the masks of
    // their own the reasons of an exact status fold into.
    Analysis.Refusal reason = analysis.refusals.get(node);
    if (reason != null) {
      analysis.reach(VarkaEmitMechanism.guard(reason));
    }
    if (!analysis.armChainOf(node).isEmpty()) {
      analysis.reach(VarkaEmitMechanism.GUARD_UNDER_ARM);
    }
    if (s.guardAccByReason != null) {
      analysis.reach(VarkaEmitMechanism.GUARD_EXACT_STATUS);
    }
  }

  /**
   * The mask {@code node}'s guard folds into: the body's one mask, or under
   * {@code exactRefusalStatus} its reason's own (VARKA-304).
   */
  private static int guardAcc(VarkaVectorIR node, Analysis analysis, Slots s) {
    if (s.guardAccByReason == null) {
      return s.guardAcc;
    }
    Integer acc = s.guardAccByReason.get(analysis.refusals.get(node));
    if (acc == null) {
      // The planner gives a mask to every reason among the body's nodes, so a guard with none is
      // a node the planner did not count as this body's; refused by name, not met as a null slot.
      throw VarkaErrors.illegalState("a guard collect with no mask planned for its reason: "
          + node);
    }
    return acc;
  }

  /**
   * ANDs the condemning mask on the stack with the arms {@code node} sits under, where its uses
   * agree on one chain of them. Nothing is emitted for the empty chain, which is what
   * {@link VarkaEmitOptions#guardUnderArm} off gives every shape.
   *
   * <p>Polarity follows SQL's {@code CASE}, in which an <em>unknown</em> condition falls to
   * {@code ELSE}: the then arm's mask is the condition's known-true set and the else arm's is
   * its complement - known-false <em>plus unknown</em> - never the known-false word. Taking
   * {@code kF} there would drop the unknown-condition lanes from the else arm's guard and stop
   * it condemning a batch it must condemn.
   *
   * <p>The condition is read from the slot it already owns, per body: a word in the masked body
   * ({@code kt}, complemented by XOR), a {@code VectorMask} in the dense one ({@code condMask},
   * complemented by {@code not()}). Both are per-condition-node maps, so a nested arm reads its own
   * and cannot clobber the enclosing one, and both are set by {@code emitCond} before either arm's
   * values are emitted. Neither goes through {@link #loadWord}, so the liveness bookkeeping is
   * untouched.
   */
  private static void emitArmContext(CodeBuilder cb, VarkaVectorIR node, boolean dense,
      Analysis analysis, Slots s) {
    for (ArmStep step : analysis.armChainOf(node)) {
      VarkaVectorIR cond = step.node().cond();
      if (dense) {
        cb.aload(s.condMask.get(cond));
        if (analysis.laneOf(cond) != analysis.lane) {
          emitToDrivingLane(cb, s);
        }
        if (!step.thenBranch()) {
          cb.invokevirtual(VECTOR_MASK, "not", MASK_UNARY);
        }
      } else {
        cb.aload(s.species);
        cb.lload(s.kt.get(cond));
        if (!step.thenBranch()) {
          cb.loadConstant(-1L);
          cb.lxor();
        }
        emitMaskOfWord(cb, analysis);
      }
      cb.invokevirtual(VECTOR_MASK, "and", MASK_BINARY);
    }
  }

  /**
   * {@code [int mask] -> [mask]} at the driving lane, in a kernel that converts between lanes:
   * the int mask's low lanes, which are the lane group's rows, as a mask of the long species,
   * through the word they spell. The int mask's upper lanes are not the group's rows, and
   * {@code fromLong} reads only as many bits as the long species has lanes, so they drop out.
   * This is a {@code fromLong} of a {@code toLong} that {@link #emitMaskOfWord} does not guard:
   * C2's rule that miscompiles the pair (VARKA-396) applies only where both masks have the same
   * lane count, and the int mask has twice the long species' lanes.
   */
  private static void emitToDrivingLane(CodeBuilder cb, Slots s) {
    cb.invokevirtual(VECTOR_MASK, "toLong", TO_LONG);   // [word]
    cb.aload(s.species);                                // [word, species]
    cb.dup_x2();                                        // [species, word, species]
    cb.pop();                                           // [species, word]
    cb.invokestatic(VECTOR_MASK, "fromLong", FROM_LONG);
  }

  /**
   * {@code [species, word] -> [mask]}: a condition or validity word of the masked body as a mask of
   * the species, lane {@code i} set where bit {@code i} is, by {@code VectorMask.fromLong} of
   * {@code word & (VarkaMaskGuard.ones | VarkaMaskGuard.OPEN)}. JDK 25's C2 miscompiles
   * {@code fromLong} of a mask's {@code toLong} on x86 without AVX-512 mask registers (VARKA-396),
   * and a masked body's known-true word is that round trip once its columns have no nulls, which
   * C2 learns from the profile. On such a host {@code OPEN} is zero and the AND with a field C2
   * cannot fold keeps the pair from forming; elsewhere it is all ones and C2 folds the guard away
   * (VARKA-491). Every word-to-mask conversion of the masked body goes through here, whether or not
   * its word can carry a {@code toLong}, so each has the same bytes; words made with {@code toLong}
   * that never become a mask, such as a filter's, pay nothing. Under {@code fromLongMasks} the
   * word goes to {@code fromLong} as it is.
   */
  static void emitMaskOfWord(CodeBuilder cb, Analysis analysis) {
    if (!analysis.options.fromLongMasks()) {
      cb.getstatic(MASK_GUARD, "ones", ConstantDescs.CD_long);
      cb.getstatic(MASK_GUARD, "OPEN", ConstantDescs.CD_long);
      cb.lor();
      cb.land();
    }
    cb.invokestatic(VECTOR_MASK, "fromLong", FROM_LONG);
  }

  /**
   * The null-skipping {@code greatest}/{@code least}: in the dense body a plain lanewise
   * {@code max}/{@code min}; in the masked body each operand substitutes the other where it is
   * null - {@code aSel = a.blend(b, ~validA)} - which reduces every case (both valid, only A,
   * only B) to the plain op, and {@code valid = validA | validB}.
   */
  private static void emitPick(CodeBuilder cb, VarkaVectorIR node, VarkaVectorIR left,
      VarkaVectorIR right, String op, boolean dense, Analysis analysis, Slots s,
      Set<VarkaVectorIR> computed) {
    Lane lane = analysis.laneOf(node);
    int[] tmp = s.pairTmp.get(node);
    if (dense) {
      emitValue(cb, left, dense, analysis, s, computed);
      emitValue(cb, right, dense, analysis, s, computed);
      line(cb, analysis, node);
      emitPickOp(cb, lane, op, tmp);
      return;
    }
    emitValue(cb, left, dense, analysis, s, computed);
    cb.astore(tmp[0]);
    emitValue(cb, right, dense, analysis, s, computed);
    cb.astore(tmp[1]);
    line(cb, analysis, node);
    cb.aload(tmp[0]);
    cb.aload(tmp[1]);
    cb.aload(s.species(lane));
    loadWord(cb, s, s.wordRef.get(left));
    cb.loadConstant(-1L);
    cb.lxor();
    emitMaskOfWord(cb, analysis);
    cb.invokevirtual(lane.vector, "blend", lane.blend);
    cb.aload(tmp[1]);
    cb.aload(tmp[0]);
    cb.aload(s.species(lane));
    loadWord(cb, s, s.wordRef.get(right));
    cb.loadConstant(-1L);
    cb.lxor();
    emitMaskOfWord(cb, analysis);
    cb.invokevirtual(lane.vector, "blend", lane.blend);
    emitPickOp(cb, lane, op, tmp);
    if (s.ownWord.contains(node)) {
      loadWord(cb, s, s.wordRef.get(left));
      loadWord(cb, s, s.wordRef.get(right));
      cb.lor();
      storeWord(cb, s, s.wordRef.get(node));
    }
  }

  /** {@code [v] -> [v shifted]} by a constant, for either shift direction. */
  static void emitShift(CodeBuilder cb, String op, int bits) {
    cb.getstatic(VECTOR_OPERATORS, op, VO_BINARY);
    cb.loadConstant(bits);
    cb.invokevirtual(INT_VECTOR, "lanewise", LANEWISE_BINARY_I);
  }


  /**
   * Emits an {@link InRanges} node: a loop over the class's table of bounds that ORs, range by
   * range, the lanes with {@code lo <= v && v <= hi} into one mask. The loop is emitted once
   * whatever the number of ranges, so the method's size does not grow with them; each iteration
   * reads two bounds from the table and compares the lanes against them as scalars. In the masked
   * body a null lane is unknown, like a comparison's: known-true and known-false are the mask and
   * its complement, each ANDed with the value's validity. An epilogue's padding lanes hold
   * whatever the masked load left, which any bound compares against harmlessly.
   */
  private static void emitInRanges(CodeBuilder cb, InRanges n, boolean dense,
      Analysis analysis, Slots s, Set<VarkaVectorIR> computed) {
    Lane lane = analysis.laneOf(n);
    emitValue(cb, n.child(), dense, analysis, s, computed);
    int[] tmp = s.rangeTmp.get(n);
    int value = tmp[0];
    int index = tmp[1];
    int mask = tmp[2];
    java.lang.constant.ClassDesc intArray = ConstantDescs.CD_int.arrayType();
    String table = analysis.rangeTables.get(n);
    line(cb, analysis, n);
    cb.astore(value);
    // An all-false mask to start from: no lane is below its own lane's minimum.
    cb.aload(value);
    cb.getstatic(VECTOR_OPERATORS, "LT", VO_COMPARISON);
    lane.pushScalar(cb, lane.mostNegative());
    cb.invokevirtual(lane.vector, "compare", lane.compareVI);
    cb.astore(mask);
    cb.loadConstant(0);
    cb.istore(index);
    Label head = cb.newLabel();
    Label done = cb.newLabel();
    cb.labelBinding(head);
    cb.iload(index);
    cb.getstatic(analysis.owner, table, intArray);
    cb.arraylength();
    cb.if_icmpge(done);
    // mask = mask | (v >= table[index] & v <= table[index + 1])
    cb.aload(mask);
    cb.aload(value);
    cb.getstatic(VECTOR_OPERATORS, "GE", VO_COMPARISON);
    cb.getstatic(analysis.owner, table, intArray);
    cb.iload(index);
    cb.iaload();
    lane.narrowScalar(cb);
    cb.invokevirtual(lane.vector, "compare", lane.compareVI);
    cb.aload(value);
    cb.getstatic(VECTOR_OPERATORS, "LE", VO_COMPARISON);
    cb.getstatic(analysis.owner, table, intArray);
    cb.iload(index);
    cb.loadConstant(1);
    cb.iadd();
    cb.iaload();
    lane.narrowScalar(cb);
    cb.invokevirtual(lane.vector, "compare", lane.compareVI);
    cb.invokevirtual(VECTOR_MASK, "and", MASK_BINARY);
    cb.invokevirtual(VECTOR_MASK, "or", MASK_BINARY);
    cb.astore(mask);
    cb.iinc(index, 2);
    cb.goto_(head);
    cb.labelBinding(done);
    cb.aload(mask);
    if (dense) {
      cb.astore(s.condMask.get(n));
    } else {
      // kT = in & valid; kF = ~in & valid.
      cb.invokevirtual(VECTOR_MASK, "toLong", TO_LONG);
      cb.lstore(s.cmpTmp);
      cb.lload(s.cmpTmp);
      loadWord(cb, s, s.wordRef.get(n.child()));
      cb.land();
      cb.lstore(s.kt.get(n));
      cb.lload(s.cmpTmp);
      cb.loadConstant(-1L);
      cb.lxor();
      loadWord(cb, s, s.wordRef.get(n.child()));
      cb.land();
      cb.lstore(s.kf.get(n));
    }
  }

  /**
   * The comparison both {@link Compare} and {@link NullSafeEqual} are built on: emits the two
   * operands and leaves on the stack the lane's {@code VectorMask} of {@code left op right}. The
   * one place a lane's equality mask is made, so that a change to it reaches every equality.
   */
  private static void emitLaneCompare(CodeBuilder cb, String op, VarkaVectorIR left,
      VarkaVectorIR right, Cond node, boolean dense, Analysis analysis, Slots s,
      Set<VarkaVectorIR> computed) {
    Lane lane = analysis.laneOf(node);
    if (lane.laneType.isFloating()) {
      int[] tmp = s.pairTmp.get(node);
      emitValue(cb, left, dense, analysis, s, computed);
      cb.astore(tmp[0]);
      emitValue(cb, right, dense, analysis, s, computed);
      cb.astore(tmp[1]);
      line(cb, analysis, node);
      emitFloatingCompare(cb, lane, op, tmp[0], tmp[1]);
      return;
    }
    emitValue(cb, left, dense, analysis, s, computed);
    cb.getstatic(VECTOR_OPERATORS, op, VO_COMPARISON);
    emitValue(cb, right, dense, analysis, s, computed);
    line(cb, analysis, node);
    cb.invokevirtual(lane.vector, "compare", lane.compareVV);
  }

  /**
   * Leaves the mask of {@code l op r} in Spark's floating order, the operands in the locals
   * {@code l} and {@code r}. Spark's order is IEEE's compare with NaN placed
   * ({@code SQLOrderingUtil.compareDoubles}): NaN equals NaN and is greater than every other
   * value, and {@code -0.0} equals {@code 0.0}, which IEEE's {@code ==} already says. So each
   * operator is the IEEE compare corrected for NaN alone:
   * <ul>
   *   <li>{@code l = r} is {@code (isNaN(l) & isNaN(r)) | l == r};
   *   <li>{@code l < r} is {@code !(isNaN(l) | l >= r)}: a NaN {@code l} is less than nothing,
   *       and a NaN {@code r} with an ordinary {@code l} fails {@code l >= r}, so it is true;
   *   <li>{@code l <= r} is {@code isNaN(r) | l <= r};
   *   <li>{@code >} and {@code >=} are {@code <} and {@code <=} with the operands swapped, and
   *       {@code <>} is the negation of {@code =}.
   * </ul>
   * The comparison stays one null-intolerant node: its validity is both operands', as at every
   * lane, which composing it from null tests and connectives would not keep.
   */
  private static void emitFloatingCompare(CodeBuilder cb, Lane lane, String op, int l, int r) {
    switch (op) {
      case "EQ", "NE" -> {
        emitIsNaN(cb, lane, l);
        emitIsNaN(cb, lane, r);
        cb.invokevirtual(VECTOR_MASK, "and", MASK_BINARY);
        emitIeeeCompare(cb, lane, "EQ", l, r);
        cb.invokevirtual(VECTOR_MASK, "or", MASK_BINARY);
        if (op.equals("NE")) {
          cb.invokevirtual(VECTOR_MASK, "not", MASK_UNARY);
        }
      }
      case "LT" -> emitFloatingLess(cb, lane, l, r);
      case "GT" -> emitFloatingLess(cb, lane, r, l);
      case "LE" -> emitFloatingLessOrEqual(cb, lane, l, r);
      case "GE" -> emitFloatingLessOrEqual(cb, lane, r, l);
      default -> throw VarkaErrors.illegalArgument("no floating comparison " + op);
    }
  }

  /** {@code !(isNaN(l) | l >= r)}: Spark's {@code l < r}. */
  private static void emitFloatingLess(CodeBuilder cb, Lane lane, int l, int r) {
    emitIsNaN(cb, lane, l);
    emitIeeeCompare(cb, lane, "GE", l, r);
    cb.invokevirtual(VECTOR_MASK, "or", MASK_BINARY);
    cb.invokevirtual(VECTOR_MASK, "not", MASK_UNARY);
  }

  /** {@code isNaN(r) | l <= r}: Spark's {@code l <= r}. */
  private static void emitFloatingLessOrEqual(CodeBuilder cb, Lane lane, int l, int r) {
    emitIsNaN(cb, lane, r);
    emitIeeeCompare(cb, lane, "LE", l, r);
    cb.invokevirtual(VECTOR_MASK, "or", MASK_BINARY);
  }

  /** The IEEE compare {@code l op r} of two vector locals, NaN unordered. */
  private static void emitIeeeCompare(CodeBuilder cb, Lane lane, String op, int l, int r) {
    cb.aload(l);
    cb.getstatic(VECTOR_OPERATORS, op, VO_COMPARISON);
    cb.aload(r);
    cb.invokevirtual(lane.vector, "compare", lane.compareVV);
  }

  /** The lanes of a vector local that hold a NaN. */
  private static void emitIsNaN(CodeBuilder cb, Lane lane, int v) {
    cb.aload(v);
    cb.getstatic(VECTOR_OPERATORS, "IS_NAN", VO_TEST);
    cb.invokevirtual(lane.vector, "test", VECTOR_TEST);
  }

  /**
   * The pick of the two values on the stack: the vector {@code max} or {@code min} at an integral
   * lane, and at a floating lane Spark's order through {@link #emitFloatingPick}, which parks
   * the values in the node's two temporary locals ({@code tmp}, free again by then).
   */
  private static void emitPickOp(CodeBuilder cb, Lane lane, String op, int[] tmp) {
    if (lane.laneType.isFloating()) {
      emitFloatingPick(cb, lane, op.equals("max"), tmp[0], tmp[1]);
    } else {
      cb.invokevirtual(lane.vector, op, lane.lanewiseVV);
    }
  }

  /**
   * Replaces the two values on the stack, {@code x} under {@code y}, with Spark's
   * {@code greatest(x, y)} or {@code least(x, y)} at a floating lane: {@code y} where it is
   * greater (or less) than {@code x} in Spark's order, and {@code x} otherwise, so the first of
   * two equal operands is kept, as {@code Greatest.eval} keeps it. The vector {@code max} and
   * {@code min} answer IEEE's question instead, which differs on NaN and on the two zeros.
   */
  private static void emitFloatingPick(CodeBuilder cb, Lane lane, boolean greatest, int x,
      int y) {
    cb.astore(y);
    cb.astore(x);
    cb.aload(x);
    cb.aload(y);
    if (greatest) {
      emitFloatingLess(cb, lane, x, y);
    } else {
      emitFloatingLess(cb, lane, y, x);
    }
    cb.invokevirtual(lane.vector, "blend", lane.blend);
  }

  /** Leaves this lane group's data bits of a boolean column: one long, lane 0 in bit 0. */
  private static void loadDataBits(CodeBuilder cb, BoolColumn n, Analysis analysis, Slots s) {
    cb.aload(s.srcSeg(n.ordinal()));
    cb.iload(s.iVar);
    cb.i2l();
    VarkaBodyEmitter.emitValidityRead(cb, analysis, s);
  }

  /** {@code a & b} of two long locals. */
  private static void emitAnd(CodeBuilder cb, int a, int b) {
    cb.lload(a);
    cb.lload(b);
    cb.land();
  }

  /** {@code a | b} of two long locals. */
  static void emitOr(CodeBuilder cb, int a, int b) {
    cb.lload(a);
    cb.lload(b);
    cb.lor();
  }

  /** {@code ~x} of the long on the stack. */
  private static void emitNot(CodeBuilder cb) {
    cb.loadConstant(-1L);
    cb.lxor();
  }

  /** {@code ~x} of a long local. */
  private static void emitNot(CodeBuilder cb, int local) {
    cb.lload(local);
    emitNot(cb);
  }

  /** {@code (c & a) | (~c & b)} of three long locals. */
  private static void emitChoice(CodeBuilder cb, int c, int a, int b) {
    emitAnd(cb, c, a);
    emitNot(cb, c);
    cb.lload(b);
    cb.land();
    cb.lor();
  }

  /** A dense boolean comparison of two masks: nothing is unknown, so it is plain logic. */
  private static void emitDenseBoolCompare(CodeBuilder cb, CompareOp op, int l, int r) {
    switch (op) {
      case EQ -> {
        cb.aload(l);
        cb.aload(r);
        cb.invokevirtual(VECTOR_MASK, "xor", MASK_BINARY);
        cb.invokevirtual(VECTOR_MASK, "not", MASK_UNARY);
      }
      case LT -> {
        cb.aload(l);
        cb.invokevirtual(VECTOR_MASK, "not", MASK_UNARY);
        cb.aload(r);
        cb.invokevirtual(VECTOR_MASK, "and", MASK_BINARY);
      }
      case LE -> {
        cb.aload(l);
        cb.invokevirtual(VECTOR_MASK, "not", MASK_UNARY);
        cb.aload(r);
        cb.invokevirtual(VECTOR_MASK, "or", MASK_BINARY);
      }
      case GT -> {
        cb.aload(l);
        cb.aload(r);
        cb.invokevirtual(VECTOR_MASK, "not", MASK_UNARY);
        cb.invokevirtual(VECTOR_MASK, "and", MASK_BINARY);
      }
      case GE -> {
        cb.aload(l);
        cb.aload(r);
        cb.invokevirtual(VECTOR_MASK, "not", MASK_UNARY);
        cb.invokevirtual(VECTOR_MASK, "or", MASK_BINARY);
      }
    }
  }

  /** A masked boolean comparison: the formulas of {@link BoolCompare}, on the pairs' words. */
  private static void emitBoolCompare(CodeBuilder cb, BoolCompare n, Slots s) {
    int t1 = s.kt.get(n.left());
    int f1 = s.kf.get(n.left());
    int t2 = s.kt.get(n.right());
    int f2 = s.kf.get(n.right());
    int kt = s.kt.get(n);
    int kf = s.kf.get(n);
    if (n.op() == CompareOp.EQ) {
      emitAnd(cb, t1, t2);
      emitAnd(cb, f1, f2);
      cb.lor();
      cb.lstore(kt);
      emitAnd(cb, t1, f2);
      emitAnd(cb, f1, t2);
      cb.lor();
      cb.lstore(kf);
      return;
    }
    // k: the lanes where both sides are known; kF = k & ~kT for the four orderings.
    emitOr(cb, t1, f1);
    emitOr(cb, t2, f2);
    cb.land();
    cb.lstore(s.cmpTmp);
    switch (n.op()) {
      case LT -> emitAnd(cb, f1, t2);
      case GT -> emitAnd(cb, t1, f2);
      case LE -> {
        emitOr(cb, f1, t2);
        cb.lload(s.cmpTmp);
        cb.land();
      }
      case GE -> {
        emitOr(cb, t1, f2);
        cb.lload(s.cmpTmp);
        cb.land();
      }
      case EQ -> throw VarkaErrors.illegalState("EQ is emitted above");
    }
    cb.lstore(kt);
    cb.lload(s.cmpTmp);
    emitNot(cb, kt);
    cb.land();
    cb.lstore(kf);
  }

  /**
   * Emits a condition node: in the dense body a single {@code VectorMask} local (every input
   * lane is valid, so known-true is the comparison itself and known-false its complement); in
   * the masked body the known-true / known-false word pair of plan 2.6.
   */
  static void emitCond(CodeBuilder cb, Cond node, boolean dense, Analysis analysis,
      Slots s, Set<VarkaVectorIR> computed) {
    if (computed.contains(node)) {
      return;
    }
    computed.add(node);
    switch (node) {
      case Compare n -> {
        emitLaneCompare(cb, n.op().name(), n.left(), n.right(), node, dense, analysis, s,
            computed);
        if (dense) {
          cb.astore(s.condMask.get(node));
        } else {
          cb.invokevirtual(VECTOR_MASK, "toLong", TO_LONG);
          cb.lstore(s.cmpTmp);
          // kT = cmp & validL & validR; kF = ~cmp & validL & validR.
          cb.lload(s.cmpTmp);
          loadWord(cb, s, s.wordRef.get(n.left()));
          cb.land();
          loadWord(cb, s, s.wordRef.get(n.right()));
          cb.land();
          cb.lstore(s.kt.get(node));
          cb.lload(s.cmpTmp);
          cb.loadConstant(-1L);
          cb.lxor();
          loadWord(cb, s, s.wordRef.get(n.left()));
          cb.land();
          loadWord(cb, s, s.wordRef.get(n.right()));
          cb.land();
          cb.lstore(s.kf.get(node));
        }
      }
      case NullSafeEqual n -> {
        // The lane's equality mask, as Compare's EQ; the dense body (no null lane) is that mask.
        emitLaneCompare(cb, "EQ", n.left(), n.right(), node, dense, analysis, s, computed);
        if (dense) {
          cb.astore(s.condMask.get(node));
        } else {
          cb.invokevirtual(VECTOR_MASK, "toLong", TO_LONG);
          cb.lstore(s.cmpTmp);
          // kT = (E & vA & vB) | ~(vA | vB): both valid and equal, or both null. E on a null lane
          // is a comparison of undefined values and is masked off by the validity words.
          cb.lload(s.cmpTmp);
          loadWord(cb, s, s.wordRef.get(n.left()));
          cb.land();
          loadWord(cb, s, s.wordRef.get(n.right()));
          cb.land();
          loadWord(cb, s, s.wordRef.get(n.left()));
          loadWord(cb, s, s.wordRef.get(n.right()));
          cb.lor();
          cb.loadConstant(-1L);
          cb.lxor();
          cb.lor();
          cb.lstore(s.kt.get(node));
          // kF = ~kT: a lane is never unknown. The ~ sets the bits above the lane count, which
          // every consumer truncates, as for IsNotNull's.
          cb.lload(s.kt.get(node));
          cb.loadConstant(-1L);
          cb.lxor();
          cb.lstore(s.kf.get(node));
        }
      }
      case BoolColumn n -> {
        line(cb, analysis, node);
        // The data word of this lane group: the column's data buffer is a bitmap in the layout
        // of a validity bitmap, so it is read by the same helper, from the data segment.
        if (dense) {
          cb.aload(s.species(analysis.laneOf(node)));
          loadDataBits(cb, n, analysis, s);
          cb.invokestatic(VECTOR_MASK, "fromLong", FROM_LONG);
          cb.astore(s.condMask.get(node));
        } else {
          // kT = d & v; kF = ~d & v. The data bit under a null slot is arbitrary and v clears it.
          // The ~ sets the bits above the lane count, which every consumer truncates.
          loadDataBits(cb, n, analysis, s);
          cb.lstore(s.cmpTmp);
          cb.lload(s.cmpTmp);
          loadWord(cb, s, s.word[n.ordinal()]);
          cb.land();
          cb.lstore(s.kt.get(node));
          emitNot(cb, s.cmpTmp);
          loadWord(cb, s, s.word[n.ordinal()]);
          cb.land();
          cb.lstore(s.kf.get(node));
        }
      }
      case CondConst n -> {
        boolean isTrue = n.value() == Truth.TRUE;
        if (dense) {
          cb.aload(s.species(analysis.laneOf(node)));
          cb.loadConstant(isTrue ? -1L : 0L);
          cb.invokestatic(VECTOR_MASK, "fromLong", FROM_LONG);
          cb.astore(s.condMask.get(node));
        } else {
          cb.loadConstant(isTrue ? -1L : 0L);
          cb.lstore(s.kt.get(node));
          cb.loadConstant(n.value() == Truth.FALSE ? -1L : 0L);
          cb.lstore(s.kf.get(node));
        }
      }
      case BoolCompare n -> {
        emitCond(cb, n.left(), dense, analysis, s, computed);
        emitCond(cb, n.right(), dense, analysis, s, computed);
        line(cb, analysis, node);
        if (dense) {
          emitDenseBoolCompare(cb, n.op(), s.condMask.get(n.left()), s.condMask.get(n.right()));
          cb.astore(s.condMask.get(node));
        } else {
          emitBoolCompare(cb, n, s);
        }
      }
      case BoolNullSafeEq n -> {
        emitCond(cb, n.left(), dense, analysis, s, computed);
        emitCond(cb, n.right(), dense, analysis, s, computed);
        line(cb, analysis, node);
        if (dense) {
          emitDenseBoolCompare(cb, CompareOp.EQ, s.condMask.get(n.left()),
              s.condMask.get(n.right()));
          cb.astore(s.condMask.get(node));
        } else {
          int t1 = s.kt.get(n.left());
          int f1 = s.kf.get(n.left());
          int t2 = s.kt.get(n.right());
          int f2 = s.kf.get(n.right());
          // k = known words; kT = (t1 & t2) | (f1 & f2) | (~k1 & ~k2).
          emitAnd(cb, t1, t2);
          emitAnd(cb, f1, f2);
          cb.lor();
          emitOr(cb, t1, f1);
          emitNot(cb);
          emitOr(cb, t2, f2);
          emitNot(cb);
          cb.land();
          cb.lor();
          cb.lstore(s.kt.get(node));
          // kF = (t1 & f2) | (f1 & t2) | (k1 ^ k2): one known and the other not, or opposite.
          emitAnd(cb, t1, f2);
          emitAnd(cb, f1, t2);
          cb.lor();
          emitOr(cb, t1, f1);
          emitOr(cb, t2, f2);
          cb.lxor();
          cb.lor();
          cb.lstore(s.kf.get(node));
        }
      }
      case CondIf n -> {
        emitCond(cb, n.cond(), dense, analysis, s, computed);
        emitCond(cb, n.thenCond(), dense, analysis, s, computed);
        emitCond(cb, n.elseCond(), dense, analysis, s, computed);
        line(cb, analysis, node);
        if (dense) {
          // (c & t) | (~c & e): an unknown condition takes ELSE, and a dense body has none.
          cb.aload(s.condMask.get(n.cond()));
          cb.aload(s.condMask.get(n.thenCond()));
          cb.invokevirtual(VECTOR_MASK, "and", MASK_BINARY);
          cb.aload(s.condMask.get(n.cond()));
          cb.invokevirtual(VECTOR_MASK, "not", MASK_UNARY);
          cb.aload(s.condMask.get(n.elseCond()));
          cb.invokevirtual(VECTOR_MASK, "and", MASK_BINARY);
          cb.invokevirtual(VECTOR_MASK, "or", MASK_BINARY);
          cb.astore(s.condMask.get(node));
        } else {
          // The condition's known-true word picks THEN, everything else takes ELSE; kF likewise.
          emitChoice(cb, s.kt.get(n.cond()), s.kt.get(n.thenCond()), s.kt.get(n.elseCond()));
          cb.lstore(s.kt.get(node));
          emitChoice(cb, s.kt.get(n.cond()), s.kf.get(n.thenCond()), s.kf.get(n.elseCond()));
          cb.lstore(s.kf.get(node));
        }
      }
      case And n -> {
        emitCond(cb, n.left(), dense, analysis, s, computed);
        emitCond(cb, n.right(), dense, analysis, s, computed);
        line(cb, analysis, node);
        if (dense) {
          cb.aload(s.condMask.get(n.left()));
          cb.aload(s.condMask.get(n.right()));
          cb.invokevirtual(VECTOR_MASK, "and", MASK_BINARY);
          cb.astore(s.condMask.get(node));
        } else {
          cb.lload(s.kt.get(n.left()));
          cb.lload(s.kt.get(n.right()));
          cb.land();
          cb.lstore(s.kt.get(node));
          cb.lload(s.kf.get(n.left()));
          cb.lload(s.kf.get(n.right()));
          cb.lor();
          cb.lstore(s.kf.get(node));
        }
      }
      case Or n -> {
        emitCond(cb, n.left(), dense, analysis, s, computed);
        emitCond(cb, n.right(), dense, analysis, s, computed);
        line(cb, analysis, node);
        if (dense) {
          cb.aload(s.condMask.get(n.left()));
          cb.aload(s.condMask.get(n.right()));
          cb.invokevirtual(VECTOR_MASK, "or", MASK_BINARY);
          cb.astore(s.condMask.get(node));
        } else {
          cb.lload(s.kt.get(n.left()));
          cb.lload(s.kt.get(n.right()));
          cb.lor();
          cb.lstore(s.kt.get(node));
          cb.lload(s.kf.get(n.left()));
          cb.lload(s.kf.get(n.right()));
          cb.land();
          cb.lstore(s.kf.get(node));
        }
      }
      case Not n -> {
        emitCond(cb, n.child(), dense, analysis, s, computed);
        line(cb, analysis, node);
        if (dense) {
          cb.aload(s.condMask.get(n.child()));
          cb.invokevirtual(VECTOR_MASK, "not", MASK_UNARY);
          cb.astore(s.condMask.get(node));
        }
        // Masked: kT/kF are the child's, swapped - pure slot aliasing, planned, no code.
      }
      case InRanges n -> emitInRanges(cb, n, dense, analysis, s, computed);
      case IsNotNull n when !dense && n.overCondition() -> {
        Cond c = (Cond) n.child();
        // Over a condition: known where it is known true or known false. kT = kT(c) | kF(c),
        // kF = ~kT - total, like the column form, and with the same truncation argument for
        // the ~ (VARKA-334). A dense body takes the arm below: no lane is unknown there.
        emitCond(cb, c, false, analysis, s, computed);
        line(cb, analysis, node);
        emitOr(cb, s.kt.get(c), s.kf.get(c));
        cb.lstore(s.kt.get(node));
        emitNot(cb, s.kt.get(node));
        cb.lstore(s.kf.get(node));
      }
      case IsNotNull n -> {
        if (n.child() instanceof DecimalColumn c) {
          // The decimal leaf's load and slot check, its value dropped: Spark's IS NULL reads a
          // valid row's value and raises on a slot outside the precision (VARKA-347), so the test
          // checks the slots in both bodies, the dense one included, where every row is valid.
          emitValue(cb, c, dense, analysis, s, computed);
          cb.pop();
        }
        line(cb, analysis, node);
        if (dense) {
          // The dense body ran because every referenced input is null-free and no node makes
          // a null of valid inputs, so the predicate is constant true, over a column or a
          // condition alike.
          cb.aload(s.species(analysis.laneOf(node)));
          cb.loadConstant(-1L);
          cb.invokestatic(VECTOR_MASK, "fromLong", FROM_LONG);
          cb.astore(s.condMask.get(node));
        } else {
          // kT = word(child); kF = ~word(child) - total: both masks cover every lane. The
          // ~ also inverts a word's undefined bits above `lanes`; that is safe because
          // every consumer truncates (`fromLong` reads species-length bits,
          // `orValidityBitsAt` applies its lane mask) - the same invariant IfElse's ~kT
          // already relies on.
          int ref = n.child() instanceof BoolColumn b ? s.word[b.ordinal()]
              : s.wordRef.get(n.child());
          loadWord(cb, s, ref);
          cb.lstore(s.kt.get(node));
          loadWord(cb, s, ref);
          cb.loadConstant(-1L);
          cb.lxor();
          cb.lstore(s.kf.get(node));
        }
      }
    }
  }
}
