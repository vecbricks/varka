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

import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDescriptors.*;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaLoopEmitter.*;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.Set;

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

  /**
   * Pushes a validity word: a long local, or the all-true constant. The one call every consumer
   * of a word goes through, which is what makes {@link Slots#wordUses} a complete count.
   */
  static void loadWord(CodeBuilder cb, Slots s, int ref) {
    if (ref == WORD_ALL_TRUE) {
      cb.loadConstant(-1L);
    } else if (ref == WORD_DEAD || s.deadRefs.contains(ref)) {
      throw new IllegalStateException("a word the liveness pass declared dead is loaded: slot "
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
      cb.aload(shared);
      return;
    }
    switch (node) {
      case ColumnRef c -> {
        line(cb, analysis, node);
        cb.aload(s.species);
        cb.aload(s.srcSeg[c.ordinal()]);
        cb.lload(s.byteOffset);
        cb.getstatic(BYTE_ORDER, "LITTLE_ENDIAN", BYTE_ORDER);
        if (s.epilogueMask != null) {
          cb.aload(s.epilogueMask);
          cb.invokestatic(analysis.lane.vector, "fromMemorySegment",
              analysis.lane.fromMemorySegmentMasked);
        } else {
          cb.invokestatic(analysis.lane.vector, "fromMemorySegment",
              analysis.lane.fromMemorySegmentDense);
        }
      }
      case LiteralSlot l -> {
        line(cb, analysis, node);
        if (s.broadcastSlot != null) {
          cb.aload(s.broadcastSlot[l.index()]);
        } else {
          cb.aload(s.species);
          analysis.lane.loadScalar(cb, s.scalarArg[l.index()]);
          cb.invokestatic(analysis.lane.vector, "broadcast", analysis.lane.broadcast);
        }
      }
      case AddDays n -> {
        analysis.lane.requireInt(n);
        // The misdescribe hook: whichever body executes first must fail naming the call.
        MethodTypeDesc desc =
            analysis.options.misdescribeAdd()
                ? analysis.lane.lanewiseVVWrong : analysis.lane.lanewiseVV;
        emitAndValidatedOp(cb, node, n.days(), n.offset(), "add", desc, dense, analysis, s,
            computed);
      }
      case SubDays n -> {
        analysis.lane.requireInt(n);
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
      case DateDiff n -> {
        analysis.lane.requireInt(n);
        emitAndValidatedOp(cb, node, n.end(), n.start(), "sub", LANEWISE_VV,
            dense, analysis, s, computed);
      }
      case DayOfWeek n -> {
        analysis.lane.requireInt(n);
        emitValue(cb, n.days(), dense, analysis, s, computed);
        line(cb, analysis, node);
        VarkaChronoLowering.emitFloorMod7(cb, node, analysis, s);
        VarkaChronoLowering.emitModOffset(cb, s, 4);
        cb.loadConstant(1);
        cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
      }
      case WeekDay n -> {
        analysis.lane.requireInt(n);
        emitValue(cb, n.days(), dense, analysis, s, computed);
        line(cb, analysis, node);
        VarkaChronoLowering.emitFloorMod7(cb, node, analysis, s);
        VarkaChronoLowering.emitModOffset(cb, s, 3);
      }
      case GuardedDay n -> {
        analysis.lane.requireInt(n);
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
      case NarrowLane n -> {
        // The value stays in the long lane here; the narrowing happens at the root's store,
        // which is the only place this node can be (see `emitNarrowStore`). The word is the
        // child's.
        emitValue(cb, n.child(), dense, analysis, s, computed);
        line(cb, analysis, node);
      }
      case GuardedRange n -> {
        // The day guard's twin at whichever lane the child is on, with the bounds the node
        // carries. The value passes through; the word is the child's.
        emitValue(cb, n.child(), dense, analysis, s, computed);
        line(cb, analysis, node);
        Integer guardTmp = s.guardTmp.get(node);
        if (guardTmp != null) {
          emitRangeGuard(cb, node, dense ? null : s.wordRef.get(n.child()), guardTmp, dense,
              analysis, s, n.lo(), n.hi());
        }
      }
      case ThursdayOf n -> {
        analysis.lane.requireInt(n);
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
        analysis.lane.requireInt(n);
        // WeekDay's tail plus one: Monday 1 to Sunday 7.
        emitValue(cb, n.days(), dense, analysis, s, computed);
        line(cb, analysis, node);
        VarkaChronoLowering.emitFloorMod7(cb, node, analysis, s);
        VarkaChronoLowering.emitModOffset(cb, s, 3);
        cb.loadConstant(1);
        cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
      }
      case NextDay n -> {
        analysis.lane.requireInt(n);
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
          cb.aload(s.species);
          cb.lload(s.kt.get(n.cond()));
          cb.invokestatic(VECTOR_MASK, "fromLong", FROM_LONG);
        }
        cb.invokevirtual(analysis.lane.vector, "blend", analysis.lane.blend);
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
      case Cond c -> throw new IllegalStateException(
          "condition node in a value position survived validation: " + c);
    }
    if (shared != null) {
      cb.dup();
      cb.astore(shared);
      computed.add(node);
    }
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
   * or a division the lane loop must not do (PLAN_TASK_11.md priced lanewise DIV at 8x) - so
   * the compiler declines a checked multiply outright and only {@code WRAP} reaches here.
   * That is wider than PLAN_TASK_63.md 3.3 assumed; see the correction there.
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
    String op = switch (n.op()) {
      case ADD -> "add";
      case SUB -> "sub";
      case MUL -> "mul";
    };
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
    if (n.op() == IntOp.MUL && n.mode() != Overflow.WRAP) {
      throw new IllegalArgumentException("a checked multiply has no int-lane overflow test: " + n);
    }
    int[] tmp = s.intArithTmp.get(n);
    if (tmp == null) {
      // WRAP, or the check switched off for the A/B: the plain lanewise op, whose word is the
      // operands' AND like every other null-intolerant binary node.
      emitAndValidatedOp(cb, n, n.left(), n.right(), op, analysis.lane.lanewiseVV, dense,
          analysis, s, computed);
      return;
    }
    int a = tmp[0];
    int b = tmp[1];
    int r = tmp[2];
    emitValue(cb, n.left(), dense, analysis, s, computed);
    emitValue(cb, n.right(), dense, analysis, s, computed);
    line(cb, analysis, n);
    cb.dup2();
    cb.invokevirtual(analysis.lane.vector, op, analysis.lane.lanewiseVV);
    cb.astore(r);
    cb.astore(b);
    cb.astore(a);
    if (!dense && s.ownWord.contains(n)) {
      emitAndWord(cb, s, s.wordRef.get(n), s.wordRef.get(n.left()), s.wordRef.get(n.right()));
    }
    // The two XORs, whose operands differ between ADD and SUB, then the AND and the sign test.
    cb.aload(a);
    cb.getstatic(VECTOR_OPERATORS, "XOR", VO_ASSOCIATIVE);
    cb.aload(n.op() == IntOp.ADD ? r : b);
    cb.invokevirtual(analysis.lane.vector, "lanewise", analysis.lane.lanewiseBinaryV);
    cb.getstatic(VECTOR_OPERATORS, "AND", VO_ASSOCIATIVE);
    cb.aload(n.op() == IntOp.ADD ? b : a);
    cb.getstatic(VECTOR_OPERATORS, "XOR", VO_ASSOCIATIVE);
    cb.aload(r);
    cb.invokevirtual(analysis.lane.vector, "lanewise", analysis.lane.lanewiseBinaryV);
    cb.invokevirtual(analysis.lane.vector, "lanewise", analysis.lane.lanewiseBinaryV);
    cb.getstatic(VECTOR_OPERATORS, "LT", VO_COMPARISON);
    analysis.lane.pushScalar(cb, 0);
    cb.invokevirtual(analysis.lane.vector, "compare", analysis.lane.compareVI);
    emitOverflowMask(cb, n.mode(), n, dense, analysis, s);
    cb.aload(r);
  }

  /**
   * {@code IntNeg}, emitted as a multiply by -1 so it needs no unary descriptor: the two agree on
   * every lane, {@link Integer#MIN_VALUE} included, where both return the input. That single value
   * is the whole of the overflow test, so the check is one compare rather than the four ops
   * {@link #emitIntArith} needs, and it reads the operand rather than the result - no scratch slot
   * at all.
   */
  private static void emitIntNeg(CodeBuilder cb, IntNeg n, boolean dense, Analysis analysis,
      Slots s, Set<VarkaVectorIR> computed) {
    emitValue(cb, n.child(), dense, analysis, s, computed);
    line(cb, analysis, n);
    boolean checked = n.mode() == Overflow.FAIL && analysis.options.checkIntOverflow();
    if (checked) {
      cb.dup();
      cb.getstatic(VECTOR_OPERATORS, "EQ", VO_COMPARISON);
      analysis.lane.pushScalar(cb, analysis.lane.mostNegative());
      cb.invokevirtual(analysis.lane.vector, "compare", analysis.lane.compareVI);
      emitOverflowMask(cb, n.mode(), n, dense, analysis, s);
    }
    analysis.lane.pushScalar(cb, -1);
    cb.invokevirtual(analysis.lane.vector, "mul", analysis.lane.lanewiseVI);
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
    // MUL is declined by the compiler and, failing that, throws inside emitIntArith itself
    // before this method would ever see it. Three refusals, three places, one invariant - a
    // future refactor that unifies the overflow dispatch across IntArith and IntNeg has to
    // keep all three in view, not assume the shape of one implies the others.
    //
    // "Refused" is a claim about this class, not about a running query: `VarkaKernelEvaluator`
    // catches an IllegalArgumentException out of `emit` as an emission failure and drops the
    // whole task to the row path, so in production a broken invariant is a warned
    // de-optimisation and not a crash. What makes these refusals load-bearing is that the
    // emitter suite asserts each of them; the throw is how the assertion has something to
    // catch, not a runtime guarantee.
    if (dense) {
      throw new IllegalArgumentException(
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
    emitValue(cb, left, dense, analysis, s, computed);
    emitValue(cb, right, dense, analysis, s, computed);
    line(cb, analysis, node);
    cb.invokevirtual(analysis.lane.vector, op, desc);
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
   * non-empty accumulator into {@code STATUS_CHRONO_RANGE}, which the evaluator answers by
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
   * node reference made those two cases indistinguishable, and reading the word slot before the arm
   * that fills it is what produced this task's VerifyError; making the caller state it keeps the
   * two facts together at the site that knows both.
   *
   * <p>This is the old per-extraction guard block, retargeted from the extraction's input to the
   * producer's output - so it runs once per distinct producer rather than once per calendar node
   * reading it, and not at all for the shapes the compiler bounds. The set is keyed on the guarded
   * node, not on the operand it checks, so two {@code AddMonths} over one count column each emit
   * their own guard over that column - redundant, not wrong, and the price of keying on the node
   * that owns the validity word the guard has to AND with. Two ANDs carry over unchanged and for
   * the same reasons: with the node's validity word in the masked body, because a null row's lanes
   * are undefined and must not condemn the batch (the node's word is the AND of every input, so a
   * null offset or a null count is covered), and with the epilogue's bounds mask, because a partial
   * group's padding lanes hold whatever the masked load left. The dense body skips the word AND:
   * every lane is valid there. A producer used more than once is emitted once per lane group under
   * CSE, guard included; with CSE off it is re-emitted per use, which repeats the guard - correct,
   * merely redundant, and not a shape production emits.
   */
  static void emitRangeGuard(CodeBuilder cb, VarkaVectorIR node, Integer word,
      int guardTmp, boolean dense, Analysis analysis, Slots s, long lo, long hi) {
    cb.dup();
    cb.astore(guardTmp);
    cb.aload(guardTmp);
    cb.getstatic(VECTOR_OPERATORS, "LT", VO_COMPARISON);
    analysis.lane.pushScalar(cb, lo);
    cb.invokevirtual(analysis.lane.vector, "compare", analysis.lane.compareVI);
    cb.aload(guardTmp);
    cb.getstatic(VECTOR_OPERATORS, "GT", VO_COMPARISON);
    analysis.lane.pushScalar(cb, hi);
    cb.invokevirtual(analysis.lane.vector, "compare", analysis.lane.compareVI);
    cb.invokevirtual(VECTOR_MASK, "or", MASK_BINARY);
    emitGuardCollect(cb, node, word, dense, analysis, s);
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
    // WORD_DEAD is deliberately not screened here beside the all-true constant. A guarded node
    // whose word the liveness pass killed is a bug in that pass, not a case to emit around: the AND
    // is what keeps a null lane from condemning the batch, so skipping it quietly would turn a
    // liveness error into spurious batch declines on nullable data. `loadWord` refuses instead, and
    // that refusal is what {@code misdescribeWordLiveness} arms in the "loaded" direction. What
    // makes the case unreachable is {@link #guardedWord}, which the liveness pass reads and which
    // contains {@link #guardScratch}, the slot planner's predicate, by construction - so the
    // planner cannot decide this node is guarded while the liveness pass decides its word is dead.
    // It was one predicate, but its two readers ask different questions; the containment is what
    // preserves the property.
    if (!dense && word != null && word != WORD_ALL_TRUE) {
      cb.aload(s.species);
      loadWord(cb, s, word);
      cb.invokestatic(VECTOR_MASK, "fromLong", FROM_LONG);
      cb.invokevirtual(VECTOR_MASK, "and", MASK_BINARY);
    }
    emitArmContext(cb, node, dense, analysis, s);
    if (s.epilogueMask != null) {
      cb.aload(s.epilogueMask);
      cb.invokevirtual(VECTOR_MASK, "and", MASK_BINARY);
    }
    cb.aload(s.guardAcc);
    cb.invokevirtual(VECTOR_MASK, "or", MASK_BINARY);
    cb.astore(s.guardAcc);
  }

  /**
   * ANDs the condemning mask on the stack with the arms {@code node} sits under, where its uses
   * agree on one chain of them. Nothing is emitted for the empty chain, which is what every shape
   * had before this task and what {@link VarkaEmitOptions#guardUnderArm} off restores.
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
        cb.invokestatic(VECTOR_MASK, "fromLong", FROM_LONG);
      }
      cb.invokevirtual(VECTOR_MASK, "and", MASK_BINARY);
    }
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
    if (dense) {
      emitValue(cb, left, dense, analysis, s, computed);
      emitValue(cb, right, dense, analysis, s, computed);
      line(cb, analysis, node);
      cb.invokevirtual(analysis.lane.vector, op, analysis.lane.lanewiseVV);
      return;
    }
    int[] tmp = s.pairTmp.get(node);
    emitValue(cb, left, dense, analysis, s, computed);
    cb.astore(tmp[0]);
    emitValue(cb, right, dense, analysis, s, computed);
    cb.astore(tmp[1]);
    line(cb, analysis, node);
    cb.aload(tmp[0]);
    cb.aload(tmp[1]);
    cb.aload(s.species);
    loadWord(cb, s, s.wordRef.get(left));
    cb.loadConstant(-1L);
    cb.lxor();
    cb.invokestatic(VECTOR_MASK, "fromLong", FROM_LONG);
    cb.invokevirtual(analysis.lane.vector, "blend", analysis.lane.blend);
    cb.aload(tmp[1]);
    cb.aload(tmp[0]);
    cb.aload(s.species);
    loadWord(cb, s, s.wordRef.get(right));
    cb.loadConstant(-1L);
    cb.lxor();
    cb.invokestatic(VECTOR_MASK, "fromLong", FROM_LONG);
    cb.invokevirtual(analysis.lane.vector, "blend", analysis.lane.blend);
    cb.invokevirtual(analysis.lane.vector, op, analysis.lane.lanewiseVV);
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
    emitValue(cb, n.child(), dense, analysis, s, computed);
    int[] tmp = s.rangeTmp.get(n);
    int value = tmp[0];
    int index = tmp[1];
    int mask = tmp[2];
    java.lang.constant.ClassDesc intArray = ConstantDescs.CD_int.arrayType();
    String table = analysis.rangeTables.get(n);
    line(cb, analysis, n);
    cb.astore(value);
    // An all-false mask to start from: no lane is below the int minimum.
    cb.aload(value);
    cb.getstatic(VECTOR_OPERATORS, "LT", VO_COMPARISON);
    analysis.lane.pushScalar(cb, Integer.MIN_VALUE);
    cb.invokevirtual(analysis.lane.vector, "compare", analysis.lane.compareVI);
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
    cb.invokevirtual(analysis.lane.vector, "compare", analysis.lane.compareVI);
    cb.aload(value);
    cb.getstatic(VECTOR_OPERATORS, "LE", VO_COMPARISON);
    cb.getstatic(analysis.owner, table, intArray);
    cb.iload(index);
    cb.loadConstant(1);
    cb.iadd();
    cb.iaload();
    cb.invokevirtual(analysis.lane.vector, "compare", analysis.lane.compareVI);
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
        emitValue(cb, n.left(), dense, analysis, s, computed);
        cb.getstatic(VECTOR_OPERATORS, n.op().name(), VO_COMPARISON);
        emitValue(cb, n.right(), dense, analysis, s, computed);
        line(cb, analysis, node);
        cb.invokevirtual(analysis.lane.vector, "compare", analysis.lane.compareVV);
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
      case IsNotNull n -> {
        line(cb, analysis, node);
        if (dense) {
          // The dense body ran because every referenced input is null-free, so the
          // predicate is constant true.
          cb.aload(s.species);
          cb.loadConstant(-1L);
          cb.invokestatic(VECTOR_MASK, "fromLong", FROM_LONG);
          cb.astore(s.condMask.get(node));
        } else {
          // kT = word(child); kF = ~word(child) - total: both masks cover every lane. The
          // ~ also inverts a word's undefined bits above `lanes`; that is safe because
          // every consumer truncates (`fromLong` reads species-length bits,
          // `orValidityBitsAt` applies its lane mask) - the same invariant IfElse's ~kT
          // already relies on.
          loadWord(cb, s, s.wordRef.get(n.child()));
          cb.lstore(s.kt.get(node));
          loadWord(cb, s, s.wordRef.get(n.child()));
          cb.loadConstant(-1L);
          cb.lxor();
          cb.lstore(s.kf.get(node));
        }
      }
    }
  }
}
