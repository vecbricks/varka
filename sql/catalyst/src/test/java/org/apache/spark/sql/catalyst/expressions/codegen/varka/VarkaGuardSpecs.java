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

import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.*;

/**
 * Which batches a kernel must decline, may decline, or must answer, decided from the inputs and
 * the guards' bounds before the kernel runs (VARKA-279). A kernel may refuse a batch - set a
 * status bit, and Spark's row engine answers it instead - and the guards that refuse are where a
 * lowering's correctness has a boundary: inside the range a guard admits the kernel is exact,
 * outside it the batch must go to Spark. The reference evaluator has no notion of a declined
 * batch; this is its counterpart, and the suites hold a kernel's status to it.
 *
 * <p>Per row, for every node the emitter guards ({@code VarkaLoopEmitter.guardsForTest}, under the
 * options the kernel is emitted with), the guard fires when the node's operands are valid and its
 * checked value is outside the bound. A fired guard is then read by the rule {@code Analysis}
 * applies:
 *
 * <ul>
 *   <li>qualified by an arm chain (non-empty): it condemns the row only where the chain selects
 *       it, and then the row <b>must</b> be declined; elsewhere the arm masks it and the row must
 *       be answered;</li>
 *   <li>unqualified: where the row reaches the node through arms it takes, it <b>must</b> be
 *       declined; where only through arms it does not take, it <b>may</b> be declined, by that
 *       guard's bit.</li>
 * </ul>
 *
 * <p>A null is never a reason a batch must decline. One guard reads a null row all the same: an
 * int-lane {@code GuardedRange} over a bare column checks the column's stored values, null rows
 * included ({@code Analysis.guardsStoredValues}, VARKA-379 11), and the reference has no value for
 * a null row: a null row whose stored day is outside the bound <b>may</b> decline by its bit. The
 * stored day is the caller's value for the null cell: {@code 0} from the Scala face, as Spark's
 * Arrow writer leaves it, and inside the bound wherever the kernel harness filled the batch
 * ({@code VarkaKernelChecks.storedUnderNull}). A guard above a
 * FAIL-mode node that overflowed on the row <b>may</b> fire: the kernel condemns the batch for the
 * overflow and leaves the wrapped value in a lane that stays valid, which the guard above then
 * reads, where the reference evaluator has no value for the lane at all (found by the coverage
 * rows' partitions, VARKA-279 9.2). A batch's verdict joins its rows'. The bounds are the
 * constants the emitter reads ({@code VarkaChrono}'s narrow range, month-arithmetic and
 * {@code make_date} limits, the lane's own range for a checked overflow, a {@code GuardedRange}'s
 * own {@code lo} and {@code hi}), not copies, so the verdict cannot drift from the code by someone
 * editing one side. The values are
 * {@link VarkaReference}'s, which shares no lowering with the emitter.
 *
 * <p>The plan's fourth verdict, an input outside the column contract, is not computed: the IR does
 * not say which columns are dates, so the edge suite keeps every input inside the contract by
 * construction instead. {@code VarkaGuardSpec} is the Scala face of this class (VARKA-311).
 */
public final class VarkaGuardSpecs {

  private VarkaGuardSpecs() {
  }

  /**
   * The bits a batch must decline with and the further bits it may; both zero for a batch the
   * kernel must answer.
   */
  public record Verdict(int must, int may) {
    public static final Verdict ANSWER = new Verdict(0, 0);

    public Verdict or(Verdict other) {
      return new Verdict(must | other.must, may | other.may);
    }

    public boolean answers() {
      return must == 0 && may == 0;
    }

    /**
     * Whether a kernel status, read under {@code exactRefusalStatus}, agrees: every must bit and
     * nothing outside must and may; any subset of may where there is no must; 0 for an answer.
     */
    public boolean admits(int status) {
      return must != 0 ? (status & must) == must && (status & ~(must | may)) == 0
          : (status & ~may) == 0;
    }
  }

  /**
   * One end of one guard's bound moved, for the test that the edge suite cannot pass vacuously:
   * {@code lo} and {@code hi} are added to the bound the guard has.
   */
  public record Shift(Analysis.Refusal reason, long lo, long hi) { }

  /** One guarded node: the reason its guard refuses for and the arm chain that qualifies it. */
  record Guarded(VarkaVectorIR node, Analysis.Refusal reason, List<Analysis.ArmStep> chain) { }

  /**
   * A kernel's guards, read once: {@code roots} emitted under {@code options}, at the long lane
   * where {@code isLong}, and each guarded node's reason and arm chain.
   */
  public record Guards(List<VarkaVectorIR> roots, boolean isLong, List<Guarded> guards) {
    public Guards {
      roots = List.copyOf(roots);
      guards = List.copyOf(guards);
    }

    /** The guards {@code roots} carry when emitted under {@code options}. */
    public Guards(List<VarkaVectorIR> roots, int numInputs, int numLiterals,
        VarkaEmitOptions options, boolean isLong) {
      this(roots, isLong, read(roots, numInputs, numLiterals, options));
    }

    private static List<Guarded> read(List<VarkaVectorIR> roots, int numInputs, int numLiterals,
        VarkaEmitOptions options) {
      var read = new ArrayList<Guarded>();
      for (Map.Entry<VarkaVectorIR, VarkaLoopEmitter.GuardForTest> e
          : VarkaLoopEmitter.guardsForTest(roots, numInputs, numLiterals, options).entrySet()) {
        read.add(new Guarded(e.getKey(), e.getValue().reason(), e.getValue().arms()));
      }
      return read;
    }
  }

  /**
   * The verdict on a batch for the kernel {@code g}: row {@code r}'s column {@code c} holds
   * {@code values[r][c]} where {@code isNull[r][c]} is false; where it is true,
   * {@code values[r][c]} is what the column stores under the null, which only the column bound
   * reads.
   */
  public static Verdict verdict(Guards g, long[] lits, long[][] values, boolean[][] isNull,
      List<Shift> shifts) {
    return verdict(g, lits, new int[0], values, isNull, shifts);
  }

  /**
   * {@link #verdict} for a long-lane kernel that also reads the int literal table (VARKA-28):
   * {@code intLits} is that table, and the values are the reference's over both lanes.
   */
  public static Verdict verdict(Guards g, long[] lits, int[] intLits, long[][] values,
      boolean[][] isNull, List<Shift> shifts) {
    var v = Verdict.ANSWER;
    for (int r = 0; r < values.length; r++) {
      v = v.or(rowVerdict(g, Values.of(values[r], isNull[r], lits, intLits, g.isLong()),
          shifts));
    }
    return v;
  }

  /** {@link #verdict} for a kernel whose guards are read for this one call. */
  public static Verdict verdict(List<VarkaVectorIR> roots, int numInputs, long[] lits,
      VarkaEmitOptions options, boolean isLong, long[][] values, boolean[][] isNull,
      List<Shift> shifts) {
    return verdict(roots, numInputs, lits, new int[0], options, isLong, values, isNull, shifts);
  }

  /** {@link #verdict} with the int literal table of a kernel of both lanes. */
  public static Verdict verdict(List<VarkaVectorIR> roots, int numInputs, long[] lits,
      int[] intLits, VarkaEmitOptions options, boolean isLong, long[][] values,
      boolean[][] isNull, List<Shift> shifts) {
    return verdict(new Guards(roots, numInputs, lits.length + intLits.length, options, isLong),
        lits, intLits, values, isNull, shifts);
  }

  /**
   * The reference evaluator's values and conditions on one row, at one lane: the row at 64 bits,
   * and at 32 for the int lane's reference. At the long lane both tables are read, a kernel of
   * both lanes indexing each lane's own, and a node of either lane is answered
   * ({@link VarkaReference#mixedValue}).
   */
  private record Values(long[] row, boolean[] isNull, long[] lits, boolean isLong, int[] ints,
      int[] intLits) {
    static Values of(long[] row, boolean[] isNull, long[] lits, int[] mixedIntLits,
        boolean isLong) {
      int[] ints = new int[row.length];
      for (int c = 0; c < row.length; c++) {
        ints[c] = (int) row[c];
      }
      int[] intLits = isLong ? mixedIntLits : new int[lits.length];
      if (!isLong) {
        for (int l = 0; l < lits.length; l++) {
          intLits[l] = (int) lits[l];
        }
      }
      return new Values(row, isNull, lits, isLong, ints, intLits);
    }

    /** The node's value, or null where it is null. */
    BigInteger value(VarkaVectorIR node) {
      if (isLong) {
        var v = VarkaReference.mixedValue(node, row, isNull, intLits, lits);
        return v.isPresent() ? BigInteger.valueOf(v.getAsLong()) : null;
      }
      var v = VarkaReference.value(node, ints, isNull, intLits);
      return v.isPresent() ? BigInteger.valueOf(v.getAsInt()) : null;
    }

    boolean holds(Cond cond) {
      return isLong ? VarkaReference.mixedCond(cond, row, isNull, intLits, lits).isTrue()
          : VarkaReference.cond(cond, ints, isNull, intLits).isTrue();
    }
  }

  private static Verdict rowVerdict(Guards g, Values values, List<Shift> shifts) {
    var v = Verdict.ANSWER;
    Set<VarkaVectorIR> live = null;
    for (var guarded : g.guards()) {
      if (guarded.node() instanceof GuardedRange range && Analysis.guardsStoredValues(range)
          && values.value(range.child()) == null) {
        // The column bound reads the day stored under a null row, which the reference has no
        // value for: the caller's value for the null cell. Outside, the batch may decline.
        long stored = (int) values.row()[((ColumnRef) range.child()).ordinal()];
        if (outside(BigInteger.valueOf(stored), bound(range, guarded.reason(), false, shifts))) {
          v = v.or(new Verdict(0, guarded.reason().status));
        }
        continue;
      }
      boolean fired = fires(guarded.node(), guarded.reason(), values, g.isLong(), shifts);
      // Below a FAIL node that overflowed, the kernel's lane holds the wrapped value and stays
      // valid - the batch is condemned for the overflow anyway - so a guard above it reads that
      // value and may fire, where the reference has no value and fires() sees a null.
      boolean wrapped = !fired && overflowedBelow(guarded.node(), values, g.isLong());
      if (!fired && !wrapped) {
        continue;
      }
      int bit = guarded.reason().status;
      Verdict must = new Verdict(bit, 0);
      Verdict may = new Verdict(0, bit);
      if (!guarded.chain().isEmpty()) {
        boolean selected = true;
        for (var step : guarded.chain()) {
          selected &= values.holds(step.node().cond()) == step.thenBranch();
        }
        if (selected) {
          v = v.or(fired ? must : may);
        }
      } else {
        if (live == null) {
          live = reached(g.roots(), values);
        }
        v = v.or(fired && live.contains(guarded.node()) ? must : may);
      }
    }
    return v;
  }

  /**
   * Whether a FAIL-mode node below {@code node} overflows on the row - its operands valid and its
   * exact result outside the lane - so that the lanes {@code node}'s guard reads hold a wrapped
   * value the reference evaluator does not have. Through an {@code IfElse} only the taken arm
   * counts, as in {@link #mark}: the blend hands the guard above the taken arm's value.
   */
  private static boolean overflowedBelow(VarkaVectorIR node, Values values, boolean isLong) {
    VarkaVectorIR[] below = node instanceof IfElse n
        ? new VarkaVectorIR[] {n.cond(), values.holds(n.cond()) ? n.thenNode() : n.elseNode()}
        : VarkaVectorIR.childrenOf(node);
    for (var child : below) {
      if (overflows(child, values, isLong) || overflowedBelow(child, values, isLong)) {
        return true;
      }
    }
    return false;
  }

  /** Whether {@code node} is FAIL-mode arithmetic whose exact result on the row leaves the lane. */
  private static boolean overflows(VarkaVectorIR node, Values values, boolean isLong) {
    BigInteger[] lane = isLong ? pair(Long.MIN_VALUE, Long.MAX_VALUE)
        : pair(Integer.MIN_VALUE, Integer.MAX_VALUE);
    if (node instanceof IntArith n && n.mode() == Overflow.FAIL) {
      BigInteger l = values.value(n.left());
      BigInteger r = l == null ? null : values.value(n.right());
      return r != null && outside(switch (n.op()) {
        case ADD -> l.add(r);
        case SUB -> l.subtract(r);
        case MUL -> l.multiply(r);
      }, lane);
    }
    if (node instanceof IntNeg n && n.mode() == Overflow.FAIL) {
      BigInteger c = values.value(n.child());
      return c != null && outside(c.negate(), lane);
    }
    return false;
  }

  /**
   * The nodes the row computes for an output it answers: those reached from a root through arms
   * the row takes. A condition is always reached where its {@code IfElse} is; an unknown condition
   * takes the else arm, as the reference evaluator and the emitter both read it.
   */
  private static Set<VarkaVectorIR> reached(List<VarkaVectorIR> roots, Values values) {
    var seen = new HashSet<VarkaVectorIR>();
    for (var root : roots) {
      mark(root, values, seen);
    }
    return seen;
  }

  private static void mark(VarkaVectorIR node, Values values, Set<VarkaVectorIR> seen) {
    if (!seen.add(node)) {
      return;
    }
    if (node instanceof IfElse n) {
      mark(n.cond(), values, seen);
      mark(values.holds(n.cond()) ? n.thenNode() : n.elseNode(), values, seen);
    } else {
      for (var child : VarkaVectorIR.childrenOf(node)) {
        mark(child, values, seen);
      }
    }
  }

  /** The bound {@code reason}'s guard on {@code node} admits, inclusive, with any shift applied. */
  private static BigInteger[] bound(VarkaVectorIR node, Analysis.Refusal reason, boolean isLong,
      List<Shift> shifts) {
    BigInteger[] b = switch (reason) {
      case DAY_PRODUCER, DAY_RANGE ->
          pair(VarkaChrono.NARROW_MIN_DAYS, VarkaChrono.NARROW_MAX_DAYS);
      case MONTH_COUNT ->
          pair(VarkaChrono.MONTH_ARITH_MIN_MONTHS, VarkaChrono.MONTH_ARITH_MAX_MONTHS);
      case MAKE_DATE -> pair(VarkaChrono.MAKE_DATE_MIN_YEAR, VarkaChrono.MAKE_DATE_MAX_YEAR);
      case INT_OVERFLOW -> isLong ? pair(Long.MIN_VALUE, Long.MAX_VALUE)
          : pair(Integer.MIN_VALUE, Integer.MAX_VALUE);
      case VALUE_RANGE -> {
        if (!(node instanceof GuardedRange g)) {
          throw new IllegalArgumentException("a value range on " + node);
        }
        yield pair(g.lo(), g.hi());
      }
    };
    BigInteger lo = b[0];
    BigInteger hi = b[1];
    for (var s : shifts) {
      if (s.reason() == reason) {
        lo = lo.add(BigInteger.valueOf(s.lo()));
        hi = hi.add(BigInteger.valueOf(s.hi()));
      }
    }
    return new BigInteger[] {lo, hi};
  }

  private static BigInteger[] pair(long lo, long hi) {
    return new BigInteger[] {BigInteger.valueOf(lo), BigInteger.valueOf(hi)};
  }

  /**
   * Whether {@code reason}'s guard on {@code node} fires on the row: its operands valid, and its
   * checked value - the producer's result, the month count, the year (and under ANSI the date),
   * the exact sum or negation, the guarded child - outside the bound.
   */
  private static boolean fires(VarkaVectorIR node, Analysis.Refusal reason, Values values,
      boolean isLong, List<Shift> shifts) {
    BigInteger[] b = bound(node, reason, isLong, shifts);
    return switch (node) {
      case AddDays n -> outside(sum(values, n.days(), n.offset(), false), b);
      case SubDays n -> outside(sum(values, n.days(), n.offset(), true), b);
      case AddMonths n -> values.value(n.days()) != null && outside(values.value(n.months()), b);
      case MakeDate n -> {
        BigInteger y = values.value(n.year());
        if (y == null) {
          yield false;
        }
        BigInteger m = values.value(n.month());
        if (m == null) {
          yield false;
        }
        BigInteger d = values.value(n.day());
        yield d != null && (outside(y, b) || (n.failOnError() && !isDate(y, m, d)));
      }
      case IntArith n -> {
        BigInteger l = values.value(n.left());
        if (l == null) {
          yield false;
        }
        BigInteger r = values.value(n.right());
        yield r != null && outside(switch (n.op()) {
          case ADD -> l.add(r);
          case SUB -> l.subtract(r);
          case MUL -> l.multiply(r);
        }, b);
      }
      case IntNeg n -> {
        BigInteger c = values.value(n.child());
        yield c != null && outside(c.negate(), b);
      }
      case GuardedDay n -> outside(values.value(n.days()), b);
      case GuardedRange n -> outside(values.value(n.child()), b);
      // No guard is placed on any other node; reaching one means the emitter placed a guard this
      // class does not know, which is the case to add.
      case ColumnRef _, LiteralSlot _, DateDiff _, IfElse _, Greatest _, Least _, DayOfWeek _,
          WeekDay _, DayOfWeekIso _, NextDay _, ThursdayOf _, Chrono _, ConstDivide _,
          BoundedDivide _, Cond _, WidenLane _, NarrowLane _ ->
          throw new IllegalArgumentException("no guard spec for " + reason + " on " + node);
    };
  }

  /** The two operands' sum, or difference where {@code subtract}; null where either is null. */
  private static BigInteger sum(Values values, VarkaVectorIR left, VarkaVectorIR right,
      boolean subtract) {
    BigInteger l = values.value(left);
    if (l == null) {
      return null;
    }
    BigInteger r = values.value(right);
    if (r == null) {
      return null;
    }
    return subtract ? l.subtract(r) : l.add(r);
  }

  /** Whether {@code v} is outside the bound {@code b}; a null is never outside. */
  private static boolean outside(BigInteger v, BigInteger[] b) {
    return v != null && (v.compareTo(b[0]) < 0 || v.compareTo(b[1]) > 0);
  }

  /** Whether {@code (y, m, d)} names a day of the calendar, as {@code LocalDate.of} decides it. */
  private static boolean isDate(BigInteger y, BigInteger m, BigInteger d) {
    if (y.bitLength() > 31 || m.bitLength() > 31 || d.bitLength() > 31) {
      return false;
    }
    try {
      LocalDate.of(y.intValue(), m.intValue(), d.intValue());
      return true;
    } catch (DateTimeException e) {
      return false;
    }
  }
}
