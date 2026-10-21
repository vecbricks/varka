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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddDays;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddMonths;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.And;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitCount;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitOpKind;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BoundedDivide;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
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
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
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
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.TruncLevel;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WeekDay;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WeekOfYear;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Year;

/**
 * The random IR grammar shared by {@code VarkaIrFuzzSuite}, which emits and runs the trees it
 * draws, and {@code VarkaRangeAnalysisSuite}, which asks the range analysis about them. One
 * generator rather than two, on the {@code VarkaSqlResolve} precedent: two grammars would drift,
 * and the property the range suite checks is only worth what the shapes it is checked over are.
 *
 * <p>{@link Shapes} draws value and condition trees over {@code numInputs} int32 columns and
 * {@code numLiterals} literal slots ({@link LongShapes}, at the end of the file, is its twin at
 * the long lane), obeying the emitter's structural rules (a day offset is a literal slot or a
 * column, {@code IsNotNull} is over a column or a condition, a calendar node sits only over a
 * subtree whose magnitude bound fits the narrowed day range with slack). {@link #boundsOf}
 * recomputes two magnitude bounds from a built tree - the node's own value and the largest value
 * any guarded day producer under it reaches - which is what decides whether a calendar node may
 * be placed over it. The bounds saturate rather than wrap in every arm that combines two.
 *
 * <p>Columns hold days within plus or minus {@link #COLUMN_BOUND} (about six thousand years
 * either side of 1970) and literals within plus or minus {@link #LITERAL_BOUND};
 * {@link #CHRONO_BOUND} is the magnitude a calendar node's subtree must stay under. The suites
 * draw the actual lane values themselves, inside these bounds, because how a row is drawn is the
 * suite's question (the fuzzer poisons null lanes, the range suite widens int-only columns to the
 * whole of int32); a value inside a symmetric bound is drawn with {@link #within} or
 * {@link #intWithin}.
 *
 * <p>Every draw is from a {@code java.util.Random}, the generator a {@code scala.util.Random}
 * wraps: a Scala caller passes its generator's {@code self}, and the draws after the shape - the
 * lane values, the null patterns - continue from where the shape left it. A port that drew one
 * number more or less, or in another order, would move every shape after it, and
 * {@code VarkaEmittedBytesSuite} would see it on the first (VARKA-320).
 */
public final class VarkaIrGrammar {

  private VarkaIrGrammar() {
  }

  public static final long COLUMN_BOUND = 2500000L;
  public static final int LITERAL_BOUND = 4000;
  /** The widest magnitude an int lane holds, the bound of a value the grammar cannot narrow. */
  public static final long INT_LANE_BOUND = 1L << 31;

  /**
   * The seed both shape corpora are drawn from: {@code VarkaIrFuzzSuite} runs shape {@code k}
   * against the reference evaluator, and {@code VarkaEmittedBytesSuite} pins the bytes shape
   * {@code k} emits. One seed here rather than one in each, so that the oracle's shapes really are
   * the fuzzer's - it pins many more of them than the fuzzer checks by default, but every shape
   * the fuzzer checks is one the oracle pins, which is what makes "the emitter produces what it
   * produced" a statement about code that was also checked for correctness. The fuzz suite's own
   * system property overrides its seed for a one-off hunt; the oracle's corpus is the committed
   * file's, so it does not follow the override.
   */
  public static final long FUZZ_SEED = 20260903L;

  // The generator's magnitude bounds saturate rather than wrap, in *every* arm that combines
  // two of them - `boundsOf` and the generator alike. Four nested multiplies of a column's own
  // bound pass 2^63, and a bound that came back negative would let `fitsUnderChrono` admit an
  // out-of-range subtree under a calendar node, at which point the kernel declines the batch,
  // correctly, and the suite fails asserting a zero status. Saturating only the arms that can
  // reach 2^63 on their own is not enough: `Long.MAX_VALUE` wraps negative the moment a parent
  // adds anything to it, so a single plain `+` one level up undoes the discipline. A saturated
  // bound is always an over-approximation, which is the safe direction here.
  static long satAdd(long a, long b) {
    try {
      return Math.addExact(a, b);
    } catch (ArithmeticException e) {
      return Long.MAX_VALUE;
    }
  }

  static long satMul(long a, long b) {
    try {
      return Math.multiplyExact(a, Math.max(1L, b));
    } catch (ArithmeticException e) {
      return Long.MAX_VALUE;
    }
  }

  /** A calendar node may sit over a subtree whose value cannot leave this magnitude. */
  public static final long CHRONO_BOUND = 5000000L;

  /** A generated node with a bound on the magnitude of the value it can take. */
  public record Gen(VarkaVectorIR node, long bound) { }

  /**
   * A built subtree's two bounds: {@code value}, the magnitude the node's own value can reach,
   * and {@code guarded}, the magnitude the guarded day producers inside it can reach.
   */
  public record Bounds(long value, long guarded) { }

  // Each child's bounds are computed once and both its fields read from them: reading a child's
  // value and its guard through two calls would walk its subtree twice at every level, 2^depth
  // walks of the deepest node.

  private static long guard(VarkaVectorIR... ns) {
    long m = 0L;
    for (VarkaVectorIR n : ns) {
      m = Math.max(m, boundsOf(n).guarded());
    }
    return m;
  }

  private static long guard(Bounds... bs) {
    long m = 0L;
    for (Bounds b : bs) {
      m = Math.max(m, b.guarded());
    }
    return m;
  }

  /** A node whose value is {@code f} of its one child's and whose guard is the child's. */
  private static Bounds over(VarkaVectorIR child, java.util.function.LongUnaryOperator f) {
    Bounds b = boundsOf(child);
    return new Bounds(f.applyAsLong(b.value()), b.guarded());
  }

  /** A node whose value is {@code f} of its two children's and whose guard is theirs. */
  private static Bounds over(VarkaVectorIR left, VarkaVectorIR right,
      java.util.function.LongBinaryOperator f) {
    Bounds l = boundsOf(left);
    Bounds r = boundsOf(right);
    return new Bounds(f.applyAsLong(l.value(), r.value()), guard(l, r));
  }

  private static Bounds shift(VarkaVectorIR days, VarkaVectorIR offset) {
    Bounds d = boundsOf(days);
    Bounds o = boundsOf(offset);
    long own = satAdd(d.value(), o.value());
    // A literal offset is folded at compile time and never guarded;
    // a column offset is exactly what collectRefusals collects.
    long guarded = offset instanceof LiteralSlot ? 0L : own;
    return new Bounds(own, Math.max(guard(d, o), guarded));
  }

  /**
   * Two bounds for a built subtree: the magnitude the node's own value can reach, and the
   * magnitude the <i>guarded day producers</i> inside it can reach. The second is not the first,
   * and treating them as one is what let this suite generate shapes that decline correctly and
   * then assert that they do not.
   *
   * <p>{@code Analysis.collectRefusals} puts VARKA-52's range guard on every
   * {@code AddDays}/{@code SubDays} with a column offset <i>anywhere</i> below a calendar node -
   * the walk descends the whole subtree and does not stop at a node that re-bases the day. So
   * {@code month(dayOfWeek(addDays(addDays(c, c), c)))} guards a producer whose value reaches
   * three times {@code COLUMN_BOUND}, even though the value {@code month} actually decomposes is
   * the {@code dayOfWeek} result and is always 1 to 7. With {@code COLUMN_BOUND} at 2.5 million
   * that producer reaches 7.5 million, past {@code NARROW_MIN_DAYS}, and the batch is declined -
   * correctly. A {@code Gen.bound} of 7 on the {@code dayOfWeek} hid it from the
   * {@code CHRONO_BOUND} check.
   *
   * <p>Six million fuzz iterations on 7 September 2026 found this in twenty jobs out of twenty,
   * and the shortest reproducer is one iteration: seed 20260907005, iteration 61379, whose
   * pattern is {@code null-free}, so it has nothing to do with poisoned null lanes.
   *
   * <p>Recomputed from the IR rather than threaded through {@link Shapes}, because threading is
   * what a new arm forgets: every arm would have to carry it, including through {@code cond},
   * and one of the twenty failures had its producer inside an {@code IfElse} condition. The
   * switch is exhaustive over the IR, and the one node the generator never builds inside a tree
   * fails loudly, so adding a node type to the IR without a rule here stops the build rather
   * than silently shrinking the suite's coverage.
   */
  public static Bounds boundsOf(VarkaVectorIR node) {
    return switch (node) {
      case ColumnRef _ -> new Bounds(COLUMN_BOUND, 0L);
      case LiteralSlot _ -> new Bounds(LITERAL_BOUND, 0L);
      case AddDays n -> shift(n.days(), n.offset());
      case SubDays n -> shift(n.days(), n.offset());
      case DateDiff n -> over(n.end(), n.start(), VarkaIrGrammar::satAdd);
      case Greatest n -> over(n.left(), n.right(), Math::max);
      case Least n -> over(n.left(), n.right(), Math::max);
      case IfElse n -> {
        Bounds arms = over(n.thenNode(), n.elseNode(), Math::max);
        yield new Bounds(arms.value(), Math.max(arms.guarded(), guard(n.cond())));
      }
      // Conditions carry no day value of their own, but their operands hold producers.
      case Compare n -> new Bounds(0L, guard(n.left(), n.right()));
      case NullSafeEqual n -> new Bounds(0L, guard(n.left(), n.right()));
      case And n -> new Bounds(0L, guard(n.left(), n.right()));
      case Or n -> new Bounds(0L, guard(n.left(), n.right()));
      case Not n -> new Bounds(0L, guard(n.child()));
      case IsNotNull n -> new Bounds(0L, guard(n.child()));
      case InRanges n -> new Bounds(0L, guard(n.child()));
      // The mod-7 family: exact for every int32 day, so the value is small whatever it is
      // over - which is precisely why the producers underneath stay visible in the guard bound.
      case DayOfWeek n -> new Bounds(7L, guard(n.days()));
      case WeekDay n -> new Bounds(6L, guard(n.days()));
      case DayOfWeekIso n -> new Bounds(7L, guard(n.days()));
      case NextDay n -> {
        Bounds days = over(n.days(), v -> satAdd(v, 8));
        yield new Bounds(days.value(), Math.max(days.guarded(), guard(n.offset())));
      }
      case ThursdayOf n -> over(n.days(), v -> satAdd(v, 3));
      // VARKA-93: a range check beside the value, which the value itself does not feel. The
      // bound is the child's, so a subtree that would leave the range still shows as one -
      // the guard reports such a batch rather than making it representable.
      case GuardedDay n -> boundsOf(n.days());
      case GuardedRange n -> boundsOf(n.child());
      case WeekOfYear n -> new Bounds(53L, guard(n.days()));
      case Year n -> new Bounds(40000L, guard(n.days()));
      case Month n -> new Bounds(12L, guard(n.days()));
      case DayOfMonth n -> new Bounds(31L, guard(n.days()));
      case Quarter n -> new Bounds(4L, guard(n.days()));
      case DayOfYear n -> new Bounds(366L, guard(n.days()));
      case AddMonths n -> over(n.days(), n.months(), (d, m) -> satAdd(d, satMul(m, 31)));
      // trunc moves a date down by up to a year, so the truncated value's magnitude can exceed
      // the child's by that much: the bound is the child's plus 366, not the child's. The
      // difference is invisible to the calendar placement, which checks with slack, and
      // decisive for the range guard's arm, which takes the bound as a guard the value must
      // not leave.
      case TruncDate n -> over(n.days(), v -> satAdd(v, 366));
      // VARKA-63: wrapping arithmetic can leave the day range entirely, which is what the
      // bound is for - a calendar node over such a subtree is refused by fitsUnderChrono.
      case IntArith n -> n.op() == IntOp.MUL
          ? over(n.left(), n.right(), VarkaIrGrammar::satMul)
          : over(n.left(), n.right(), VarkaIrGrammar::satAdd);
      case IntNeg n -> boundsOf(n.child());
      case ConstDivide n -> over(n.child(), v -> v / Math.abs(n.divisor()));
      case BoundedDivide n ->
          new Bounds((long) ((n.bound() - 1) / n.divisor()), guard(n.child()));
      // A mask or a shift reaches any int; the count is at most 64.
      case BitOp n -> new Bounds(INT_LANE_BOUND, guard(n.left(), n.right()));
      case BitCount n -> new Bounds(Long.SIZE, guard(n.child()));
      // The dynamic form moves the date down like the literal one, whatever the level; the
      // level column contributes no day magnitude of its own, only whatever guarded producer
      // might sit under it, which `guard` picks up.
      case TruncDateDynamic n -> {
        Bounds days = over(n.days(), v -> satAdd(v, 366));
        yield new Bounds(days.value(), Math.max(days.guarded(), guard(n.level())));
      }
      case LastDay n -> over(n.days(), v -> satAdd(v, 31));
      // make_date guards its own year, so its output is a date inside the column contract.
      case MakeDate n ->
          new Bounds(VarkaChrono.CONTRACT_MAX_DAYS, guard(n.year(), n.month(), n.day()));
      // The conversions: a widening keeps every value, and a narrowing keeps the low 32 bits,
      // whose magnitude is the child's where that fits an int and an int's otherwise.
      case WidenLane n -> boundsOf(n.child());
      case NarrowLane n -> {
        Bounds b = boundsOf(n.child());
        yield new Bounds(b.value() <= Integer.MAX_VALUE ? b.value() : 1L << 31, b.guarded());
      }
    };
  }

  /**
   * One drawn shape: the kernel's roots, the input and literal counts they were drawn over, and
   * the two special columns, which a caller drawing lane values has to honour - the values a
   * column holds are bounded by the arms that may read it.
   */
  public record Drawn(List<VarkaVectorIR> roots, int numInputs, int numLiterals,
      int smallOrdinal, int levelOrdinal) {
    public Drawn {
      roots = List.copyOf(roots);
    }
  }

  /**
   * The generator for shape {@code k} of the sequence, the same in every suite that draws it.
   *
   * <p>The draws live here rather than in either caller because two of them must draw the
   * <i>same</i> corpus from the same seed: {@code VarkaIrFuzzSuite} runs each shape against the
   * reference evaluator, and {@code VarkaEmittedBytesSuite} pins the bytes the same shape emits.
   * A copy of the preamble in each would let one extra {@code rnd.next*} call in one of them
   * silently split the corpus in two, and the oracle would go on pinning shapes nothing checks
   * for correctness. {@link #FUZZ_SEED} and this method are here for the same reason.
   */
  public static Random shapeRandom(long seed, int k) {
    return new Random(seed * 1000003L + k);
  }

  /**
   * A lane value uniform over {@code [-bound, bound]}, the draw every suite uses for a column or
   * literal value inside a symmetric bound.
   *
   * <p>A floor modulus rather than {@code %}: a signed remainder takes the sign of the dividend, so
   * for the half of {@code nextLong} values that are negative it lands in
   * {@code (-(2 * bound + 1), 0]} and the value below {@code -bound}, outside every guard the
   * grammar drew. Exactly one {@code nextLong} a value, whatever the bound, and no rejection loop:
   * some callers draw more after the values from the same generator (the long lane's literals come
   * before its length and null patterns, and the series draws its batches one after another), and
   * what they draw next must not depend on the bound or on the values drawn.
   */
  public static long within(Random rnd, long bound) {
    if (bound < 0 || bound > (Long.MAX_VALUE - 1) / 2) {
      throw new IllegalArgumentException("bound " + bound + " is outside [0, 2^62)");
    }
    return Math.floorMod(rnd.nextLong(), 2 * bound + 1) - bound;
  }

  /**
   * {@link #within} for an int lane: the bound is an {@code int}, so every value in
   * {@code [-bound, bound]} fits and the narrowing is exact.
   */
  public static int intWithin(Random rnd, int bound) {
    return (int) within(rnd, bound);
  }

  /** {@code roots} without repeats, each kept where it first appears. */
  private static List<VarkaVectorIR> distinct(List<VarkaVectorIR> roots) {
    return List.copyOf(new LinkedHashSet<>(roots));
  }

  /**
   * Draw one shape from {@code rnd}: the column and literal counts, the special columns, the
   * depth and the roots, in that order, leaving {@code rnd} positioned where a caller that needs
   * lane values and null patterns picks up.
   */
  public static Drawn drawShape(Random rnd) {
    int numInputs = 1 + rnd.nextInt(3);
    int numLiterals = rnd.nextInt(3);
    // The last input, when there is more than one, holds month-count-magnitude values so a
    // *column* month count can be fuzzed; see Shapes' doc. With a single input there is no
    // ordinal to spare - that one has to stay a day column for every other arm.
    int smallOrdinal = numInputs > 1 ? numInputs - 1 : -1;
    // The second special column, and only when there are three: with two, taking one for
    // trunc levels would leave a single day column and starve every other arm.
    int levelOrdinal = numInputs > 2 ? numInputs - 2 : -1;
    var shapes = new Shapes(rnd, numInputs, numLiterals, smallOrdinal, levelOrdinal);
    int depth = 1 + rnd.nextInt(4);
    // Either a projection of value roots or one selection root: the two kinds of kernel
    // production emits, never mixed in one class.
    List<VarkaVectorIR> roots;
    if (rnd.nextInt(5) == 0) {
      roots = List.of(shapes.cond(depth));
    } else {
      int count = 1 + rnd.nextInt(3);
      var drawn = new ArrayList<VarkaVectorIR>();
      for (int i = 0; i < count; i++) {
        drawn.add(shapes.value(depth).node());
      }
      roots = distinct(drawn);
    }
    return new Drawn(roots, numInputs, numLiterals, smallOrdinal, levelOrdinal);
  }

  /**
   * A wide shape: 20 to 200 value roots over one to three columns, each root drawn by a
   * generator of its own so every root gets the whole node budget. The fuzz draw's one to three
   * roots never make a group method of more than a few thousand bytes; these reach the byte and
   * call-site budgets, which is where the cost model's predictions are asked
   * ({@code VarkaEmitCostCorpus}). A sequence of its own, so drawing it moves no committed corpus.
   */
  public static Drawn drawWideShape(Random rnd) {
    int numInputs = 1 + rnd.nextInt(3);
    int numLiterals = rnd.nextInt(3);
    int smallOrdinal = numInputs > 1 ? numInputs - 1 : -1;
    int levelOrdinal = numInputs > 2 ? numInputs - 2 : -1;
    int count = 20 + rnd.nextInt(181);
    var drawn = new ArrayList<VarkaVectorIR>();
    for (int i = 0; i < count; i++) {
      var shapes = new Shapes(rnd, numInputs, numLiterals, smallOrdinal, levelOrdinal);
      drawn.add(shapes.value(1 + rnd.nextInt(4)).node());
    }
    return new Drawn(distinct(drawn), numInputs, numLiterals, smallOrdinal, levelOrdinal);
  }

  /**
   * One iteration's shape generator; keeps a node budget so trees stay well inside the emitter's
   * {@code MAX_FUSED_NODES} and {@code MAX_CHAIN_DEPTH}.
   *
   * <p>{@code smallOrdinal} is the input column whose values {@code runOne} keeps inside
   * {@code MONTH_ARITH_MIN/MAX_MONTHS}, or -1 when this iteration has only one input. Every other
   * column holds day-magnitude values, which as a month count would trip the runtime guard on
   * every batch and decline it - leaving the status-zero assertions nothing to check. Giving one
   * ordinal a small range is what lets a <i>column</i> month count be fuzzed at all, and it is
   * the operand shape VARKA-63 will want too. Its {@code Gen} bound stays {@code COLUMN_BOUND}
   * wherever the generic leaf draws it, which over-approximates its real range in the safe
   * direction.
   *
   * <p>{@code levelOrdinal} is the same idea for VARKA-61's {@code trunc} with a format column,
   * or -1 when this iteration has fewer than three inputs. {@code TruncLevelLeaf} hands the kernel
   * {@code DateTimeUtils.parseTruncLevel}'s codes - 6 ({@code WEEK}) to 9 ({@code YEAR}) - or a
   * null lane, and nothing else, so a level column drawn at day magnitude would be a lane the
   * leaf can never produce and the node would be fuzzed outside its contract. {@code runOne}
   * draws this one from the four codes instead, which is what lets {@code TruncDateDynamic} be
   * generated at all - it was the one IR node type this suite could not reach.
   */
  public static final class Shapes {
    /** See the {@code case 21} arm: non-zero, not -1, both signs, a power of two among them. */
    static final int[] CONST_DIVIDE_DIVISORS = {2, 3, 7, 12, 100, -3, -12};

    private final Random rnd;
    private final int numInputs;
    private final int numLiterals;
    private final int smallOrdinal;
    private final int levelOrdinal;
    private int budget = 20;

    public Shapes(Random rnd, int numInputs, int numLiterals, int smallOrdinal,
        int levelOrdinal) {
      this.rnd = rnd;
      this.numInputs = numInputs;
      this.numLiterals = numLiterals;
      this.smallOrdinal = smallOrdinal;
      this.levelOrdinal = levelOrdinal;
    }

    /**
     * Whether a calendar node may sit over this subtree. Both bounds have to fit inside
     * {@code CHRONO_BOUND}: the value the node itself decomposes, and - see {@code boundsOf} -
     * the value every guarded day producer underneath reaches, because VARKA-52's guard is placed
     * on those on their own values however far below the calendar node they sit.
     */
    private boolean fitsUnderChrono(Gen a) {
      return a.bound() <= CHRONO_BOUND && boundsOf(a.node()).guarded() <= CHRONO_BOUND;
    }

    private Gen leaf() {
      if (numLiterals > 0 && rnd.nextInt(4) == 0) {
        return new Gen(new LiteralSlot(rnd.nextInt(numLiterals)), LITERAL_BOUND);
      }
      return new Gen(new ColumnRef(rnd.nextInt(numInputs)), COLUMN_BOUND);
    }

    private Gen literal() {
      if (numLiterals > 0) {
        return new Gen(new LiteralSlot(rnd.nextInt(numLiterals)), LITERAL_BOUND);
      }
      return new Gen(new ColumnRef(rnd.nextInt(numInputs)), COLUMN_BOUND);
    }

    /**
     * A checked mode only where the operands' bounds rule overflow out; drawn from the
     * generator only then.
     */
    private Overflow mode(boolean safe) {
      if (!safe) {
        return Overflow.WRAP;
      }
      return switch (rnd.nextInt(3)) {
        case 0 -> Overflow.WRAP;
        case 1 -> Overflow.FAIL;
        default -> Overflow.NULL;
      };
    }

    public Gen value(int depth) {
      if (depth == 0 || budget <= 1) {
        return leaf();
      }
      budget -= 1;
      int arm = rnd.nextInt(26);
      switch (arm) {
        case 0: {
          Gen a = value(depth - 1);
          Gen b = literal();
          return new Gen(new AddDays(a.node(), b.node()), satAdd(a.bound(), b.bound()));
        }
        case 1: {
          Gen a = value(depth - 1);
          Gen b = literal();
          return new Gen(new SubDays(a.node(), b.node()), satAdd(a.bound(), b.bound()));
        }
        case 2: {
          Gen a = value(depth - 1);
          Gen b = value(depth - 1);
          return new Gen(new DateDiff(a.node(), b.node()), satAdd(a.bound(), b.bound()));
        }
        case 3: {
          Gen a = value(depth - 1);
          Gen b = value(depth - 1);
          return new Gen(new Greatest(a.node(), b.node()), Math.max(a.bound(), b.bound()));
        }
        case 4: {
          Gen a = value(depth - 1);
          Gen b = value(depth - 1);
          return new Gen(new Least(a.node(), b.node()), Math.max(a.bound(), b.bound()));
        }
        case 5: {
          Cond c = cond(depth - 1);
          Gen a = value(depth - 1);
          Gen b = value(depth - 1);
          return new Gen(new IfElse(c, a.node(), b.node()), Math.max(a.bound(), b.bound()));
        }
        case 6:
          return new Gen(new DayOfWeek(value(depth - 1).node()), 7);
        case 7:
          return new Gen(new WeekDay(value(depth - 1).node()), 6);
        case 8: {
          // next_day's weekday as a literal slot (VARKA-33) or, on the other draw, a column
          // (VARKA-59's derived weekday leaf), whose values the reference reads like any int:
          // the lowering is exact for every k, so a date column serves as the weekday column.
          Gen a = value(depth - 1);
          Gen k = rnd.nextBoolean()
              ? literal()
              : new Gen(new ColumnRef(rnd.nextInt(numInputs)), COLUMN_BOUND);
          return new Gen(new NextDay(a.node(), k.node()), satAdd(a.bound(), 8));
        }
        case 14: {
          // make_date (VARKA-42) over a date's own fields: always a valid triple in range, so
          // both modes run to status 0 and the answer is the date itself; every third one
          // takes a literal day instead under the NULL form, where an invalid day is a null
          // output and the batch still runs.
          Gen a = value(depth - 1);
          if (!fitsUnderChrono(a)) {
            return a;
          }
          if (numLiterals > 0 && rnd.nextInt(3) == 0) {
            Gen k = literal();
            return new Gen(new MakeDate(new Year(a.node()), new Month(a.node()), k.node(), false),
                satAdd(a.bound(), 31));
          }
          return new Gen(new MakeDate(new Year(a.node()), new Month(a.node()),
              new DayOfMonth(a.node()), rnd.nextBoolean()), a.bound());
        }
        case 15: {
          // The Thursday of the day's week (VARKA-37): a day-typed producer within three days.
          Gen a = value(depth - 1);
          return new Gen(new ThursdayOf(a.node()), satAdd(a.bound(), 3));
        }
        case 16: {
          // weekofyear as the compiler builds it, the pair as a unit: the week tail is defined
          // over ThursdayOf only (the emitter refuses any other child), and the subtree has to
          // stay inside the narrowed range like every calendar node's.
          Gen a = value(depth - 1);
          if (!fitsUnderChrono(a)) {
            return a;
          }
          return new Gen(new WeekOfYear(new ThursdayOf(a.node())), 53);
        }
        case 17:
          return new Gen(new DayOfWeekIso(value(depth - 1).node()), 7);
        case 18: {
          // VARKA-63's int arithmetic. A checked mode is drawn only where the operands' own
          // bounds rule overflow out, which is what keeps the value comparison meaningful: the
          // kernel has to answer, not decline, and the suite asserts a zero status. Drawing
          // FAIL over unbounded operands would decline most batches and check nothing - but
          // drawing WRAP only would leave every checked
          // emission path outside the differential oracle: the guard accumulator, the FAIL
          // word's liveness, the TRY narrowing, and the slot numbering that the scratch
          // temporaries shift.
          Gen a = value(depth - 1);
          Gen b = literal();
          // `bound` is a magnitude, so the sum is safe exactly when the bounds' sum is.
          long sumBound = satAdd(a.bound(), b.bound());
          boolean safe = sumBound <= Integer.MAX_VALUE;
          return switch (rnd.nextInt(3)) {
            case 0 -> new Gen(new IntArith(IntOp.ADD, mode(safe), a.node(), b.node()), sumBound);
            case 1 -> new Gen(new IntArith(IntOp.SUB, mode(safe), a.node(), b.node()), sumBound);
            // A checked multiply has no int-lane overflow test at all, so the compiler
            // declines one and the emitter refuses one: WRAP is the only mode that exists.
            default -> new Gen(new IntArith(IntOp.MUL, Overflow.WRAP, a.node(), b.node()),
                satMul(a.bound(), b.bound()));
          };
        }
        case 19: {
          Gen a = value(depth - 1);
          // Negation overflows on exactly one value, so any bound inside the int range rules
          // it out and FAIL is safe to draw. NULL has no spelling in Spark and the emitter
          // refuses it, so it is not drawn here.
          boolean checked = a.bound() <= Integer.MAX_VALUE && rnd.nextBoolean();
          return new Gen(new IntNeg(checked ? Overflow.FAIL : Overflow.WRAP, a.node()),
              a.bound());
        }
        case 21: {
          // VARKA-89's constant division. The divisors are drawn from a fixed set rather than at
          // random: zero has no quotient and -1 overflows at Integer.MIN_VALUE, both of which
          // the node and the emitter refuse, so drawing one would make the fuzzer assert its own
          // refusal instead of the arithmetic. Both signs appear, because the lowering truncates
          // toward zero and a floor would differ only on a negative dividend with a remainder.
          Gen a = value(depth - 1);
          int d = CONST_DIVIDE_DIVISORS[rnd.nextInt(CONST_DIVIDE_DIVISORS.length)];
          return new Gen(new ConstDivide(a.node(), d), a.bound() / Math.abs((long) d));
        }
        case 23: {
          // Group C's bounded division (VARKA-102.md 8.4): exact only for a non-negative
          // dividend under its bound. The grammar tracks a subtree's magnitude and not its
          // sign, so the dividend is a calendar field, non-negative and bounded by what it is:
          // the day of the year under 367, the month under 13, the day of the month under 32.
          // A guard that fired would be a decline the reference cannot spell, as the arms
          // above say, so nothing is guarded here and nothing can fire.
          Gen a = value(depth - 1);
          if (!fitsUnderChrono(a)) {
            return a;
          }
          return switch (rnd.nextInt(3)) {
            case 0 -> new Gen(BoundedDivide.of(new DayOfYear(a.node()), 7, 367), 53);
            case 1 -> new Gen(BoundedDivide.of(new Month(a.node()), 4, 13), 4);
            default -> new Gen(BoundedDivide.of(new DayOfMonth(a.node()), 7, 32), 5);
          };
        }
        case 20: {
          // VARKA-93's range check, over a subtree that stays inside the narrowed range - the
          // same condition the calendar family below draws under, and for the same reason. A
          // guard over a subtree that leaves the range does exactly what it is for: it reports
          // the batch, the kernel declines, and the reference evaluator has no spelling for
          // that, so this test would read a correct decline as a mismatch. The guard's firing
          // is asserted where a declined batch is the expected answer, in the emitter suite.
          Gen a = value(depth - 1);
          if (!fitsUnderChrono(a)) {
            return a;
          }
          return new Gen(new GuardedDay(a.node()), a.bound());
        }
        case 22: {
          // VARKA-102's range guard, with bounds that contain the child's own bound, for the
          // reason the day guard's arm gives: a guard that fires declines the batch, and the
          // reference evaluator has no spelling for that. Its firing is asserted in the
          // emitter suite. The int lane's analysis refuses bounds an int cannot hold, so a
          // bound the grammar's saturating arithmetic has pushed past int is replaced by the
          // whole int range, which contains every value the lane can produce.
          Gen a = value(depth - 1);
          boolean wide = a.bound() > Integer.MAX_VALUE;
          long bound = wide ? Integer.MAX_VALUE : a.bound();
          long lo = wide ? Integer.MIN_VALUE : -bound;
          return new Gen(new GuardedRange(a.node(), lo, bound), a.bound());
        }
        case 24: {
          // VARKA-335's bitwise operations and shifts, the right operand a subtree or a leaf:
          // a shift count of any value, which the lanes mask to five bits as Java does. The
          // grammar tracks no sign, and a mask or a shift can reach any int, so the bound is the
          // lane's own.
          Gen a = value(depth - 1);
          Gen b = rnd.nextBoolean() ? leaf() : value(depth - 1);
          BitOpKind[] ops = BitOpKind.values();
          return new Gen(new BitOp(ops[rnd.nextInt(ops.length)], a.node(), b.node()),
              INT_LANE_BOUND);
        }
        case 25:
          // bit_count, at most 64 whatever it counts.
          return new Gen(new BitCount(value(depth - 1).node()), Long.SIZE);
        default:
          return calendar(arm, depth);
      }
    }

    /** The calendar family, arms 9 to 13, over a subtree that stays inside the narrowed range. */
    private Gen calendar(int arm, int depth) {
      Gen a = value(depth - 1);
      if (!fitsUnderChrono(a)) {
        return a;
      }
      switch (arm) {
        case 9:
          return new Gen(new Year(a.node()), 40000);
        case 10:
          return new Gen(new Month(a.node()), 12);
        case 11:
          return new Gen(new DayOfMonth(a.node()), 31);
        case 12:
          return new Gen(new Quarter(a.node()), 4);
        default:
          break;
      }
      int which = rnd.nextInt(4);
      if (which == 0) {
        return new Gen(new DayOfYear(a.node()), 366);
      } else if (which == 2 && (numLiterals > 0 || smallOrdinal >= 0)) {
        // The count is a literal slot (VARKA-40) or, when this iteration has a small-magnitude
        // column, that column (VARKA-60). It cannot be any other column: the rest hold
        // day-magnitude values, vastly past MONTH_ARITH_MIN/MAX_MONTHS, so the runtime guard
        // would decline every batch and the status-zero assertions would have nothing left to
        // check.
        Gen m = smallOrdinal >= 0 && (numLiterals == 0 || rnd.nextBoolean())
            ? new Gen(new ColumnRef(smallOrdinal), VarkaChrono.MONTH_ARITH_MAX_MONTHS)
            : literal();
        return new Gen(new AddMonths(a.node(), m.node()),
            satAdd(a.bound(), satMul(m.bound(), 31)));
      } else if (which == 3) {
        // trunc (VARKA-35) moves a date down by at most a year, which the bound has to carry
        // (see `boundsOf`): a range guard drawn over this node takes the bound as the range the
        // value must stay in, and a year-start below the child's own bound is a live lane the
        // guard would condemn. The level is drawn at random so all three tails are fuzzed; the
        // column-level form (VARKA-61, TruncDateDynamic) is drawn only when this iteration has
        // a level column, whose lanes hold the leaf's codes 6..9.
        if (levelOrdinal >= 0 && rnd.nextBoolean()) {
          return new Gen(new TruncDateDynamic(a.node(), new ColumnRef(levelOrdinal)),
              satAdd(a.bound(), 366));
        }
        TruncLevel[] levels = TruncLevel.values();
        return new Gen(new TruncDate(a.node(), levels[rnd.nextInt(levels.length)]),
            satAdd(a.bound(), 366));
      }
      return new Gen(new LastDay(a.node()), satAdd(a.bound(), 31));
    }

    /**
     * A range set over a drawn value: one to six sorted, disjoint, non-adjacent ranges placed
     * across the value's own bound, so that lanes land inside ranges, between them and outside
     * all of them.
     */
    public InRanges rangeSet(int depth) {
      Gen v = value(depth);
      long span = Math.max(1L, Math.min(v.bound(), Integer.MAX_VALUE / 4L));
      var bounds = new ArrayList<Integer>();
      long at = -span + draw(span);
      int ranges = 1 + rnd.nextInt(6);
      for (int r = 0; r < ranges; r++) {
        if (at > span) {
          continue;
        }
        long lo = at;
        long hi = Math.min(span, lo + draw(span / 4));
        bounds.add((int) lo);
        bounds.add((int) hi);
        at = hi + 2 + draw(span / 4);
      }
      return new InRanges(v.node(), bounds);
    }

    private long draw(long limit) {
      return (rnd.nextLong() & Long.MAX_VALUE) % (limit + 1);
    }

    public Cond cond(int depth) {
      budget -= 1;
      if (depth == 0 || budget <= 1 || rnd.nextInt(3) == 0) {
        CompareOp[] ops = CompareOp.values();
        CompareOp op = ops[rnd.nextInt(ops.length)];
        VarkaVectorIR left = value(depth).node();
        return new Compare(op, left, value(depth).node());
      }
      return switch (rnd.nextInt(5)) {
        case 0 -> {
          Cond left = cond(depth - 1);
          yield new And(left, cond(depth - 1));
        }
        case 1 -> {
          Cond left = cond(depth - 1);
          yield new Or(left, cond(depth - 1));
        }
        case 2 -> new Not(cond(depth - 1));
        case 3 -> rangeSet(depth);
        // IsNotNull reads a column's validity word, or a condition's masks: whether it is known.
        // No other value child, since a computed value's word exists only in its value walk.
        default -> rnd.nextBoolean()
            ? new IsNotNull(new ColumnRef(rnd.nextInt(numInputs)))
            : new IsNotNull(cond(depth - 1));
      };
    }
  }

  // -----------------------------------------------------------------------------------------
  // The long lane
  // -----------------------------------------------------------------------------------------

  /**
   * The seed of the long-lane corpus. It is a second sequence rather than long shapes drawn into
   * {@code FUZZ_SEED}'s, because that sequence is also the emitted-bytes oracle's committed
   * corpus: a long shape drawn into it would move every block digest in
   * {@code emitted_bytes.json}, and the oracle would stop being able to say whether the int32
   * emitter changed.
   */
  public static final long LONG_FUZZ_SEED = 20260919L;

  /**
   * Columns at the long lane hold values within plus or minus {@code LONG_COLUMN_BOUND}, the
   * magnitude of a nanosecond of day (2^46 is a little over 8.6e13), and literals within plus or
   * minus {@code LONG_LITERAL_BOUND}. The bounds exist for one node: {@code ConstDivide} is exact
   * only while its dividend stays under {@code ConstDivide.EXACT_DIVIDEND_BOUND}, a precondition
   * the caller owns and nothing enforces, so the generator places a division only over a subtree
   * whose bound fits under it - the long lane's {@code fitsUnderChrono}. A sum of two columns
   * fits; a product of two does not, and stays undivided.
   *
   * <p>A campaign can raise it to the divisions' own bound, {@code -Dvarka.fuzz.longColumnBound}
   * at 2^52 - 1, so random trees divide in the six bits between a day of nanoseconds and where
   * the lowerings stop being exact (milestone 5 row 166). The default keeps the committed fuzz
   * blocks what they are: the bound reaches the trees the grammar draws, so changing it changes
   * {@code emitted_bytes.json}'s {@code fuzz_long} blocks, and a regeneration must say so.
   */
  public static final long LONG_COLUMN_BOUND =
      Long.parseLong(System.getProperty("varka.fuzz.longColumnBound", Long.toString(1L << 46)));

  public static final long LONG_LITERAL_BOUND = 1L << 20;

  /** One drawn long-lane shape: the roots and the column and literal counts they read. */
  public record DrawnLong(List<VarkaVectorIR> roots, int numInputs, int numLiterals) {
    public DrawnLong {
      roots = List.copyOf(roots);
    }
  }

  /** {@link #drawShape}'s twin at the long lane, shared by the fuzzer and the bytes oracle. */
  public static DrawnLong drawLongShape(Random rnd) {
    int numInputs = 1 + rnd.nextInt(3);
    int numLiterals = rnd.nextInt(3);
    var shapes = new LongShapes(rnd, numInputs, numLiterals);
    int depth = 1 + rnd.nextInt(4);
    List<VarkaVectorIR> roots;
    if (rnd.nextInt(5) == 0) {
      roots = List.of(shapes.cond(depth));
    } else {
      int count = 1 + rnd.nextInt(3);
      var drawn = new ArrayList<VarkaVectorIR>();
      for (int i = 0; i < count; i++) {
        drawn.add(shapes.root(depth));
      }
      roots = distinct(drawn);
    }
    return new DrawnLong(roots, numInputs, numLiterals);
  }

  /** {@link #drawWideShape}'s twin at the long lane. */
  public static DrawnLong drawWideLongShape(Random rnd) {
    int numInputs = 1 + rnd.nextInt(3);
    int numLiterals = rnd.nextInt(3);
    int count = 20 + rnd.nextInt(181);
    var drawn = new ArrayList<VarkaVectorIR>();
    for (int i = 0; i < count; i++) {
      var shapes = new LongShapes(rnd, numInputs, numLiterals);
      drawn.add(shapes.root(1 + rnd.nextInt(4)));
    }
    return new DrawnLong(distinct(drawn), numInputs, numLiterals);
  }

  /**
   * The generator at the long lane, over the lane-generic subset of the IR: the leaves, the
   * arithmetic in its three modes, the negate, the constant division in both its lowerings (which
   * one emits is {@code useAVX}'s choice, which the suite draws), the range guard, the hull ops,
   * the conditional and the conditions - and, at an output root only, the narrowing
   * ({@link #root}). No calendar node, since those refuse a 64-bit child where they are built.
   * The {@code TIME} and interval expressions the compiler lowers are trees of exactly these
   * nodes - a subtraction under a division, a guarded add over a guarded multiply - so a grammar
   * over the node set covers them without knowing their names, and the fuzzer's reach test holds
   * it to the whole set.
   *
   * <p>Every subtree carries a magnitude bound, saturating as {@link Shapes}' do. It decides
   * three things: whether a division may be placed (the dividend bound), whether a checked mode
   * may be drawn (an overflow the bounds rule out is one the kernel need not decline on, so the
   * suite can assert a zero status), and what range a guard is given (one that contains the
   * child's bound, because a guard that fires declines the batch and the reference evaluator has
   * no spelling for that; the guards' firing is asserted in the emitter suite).
   */
  public static final class LongShapes {
    /**
     * The divisors the {@code TIME} and interval lowerings divide by - nanoseconds per unit,
     * micros per day - and small ones on both signs, where truncation and floor part on every
     * second or third dividend rather than once in a billion.
     */
    static final long[] LONG_DIVISORS = {2L, 3L, 7L, 60L, -60L, 1000L, 1000000L, 1000000000L,
        60000000000L, 3600000000000L, 86400000000L, -12L};

    private final Random rnd;
    private final int numInputs;
    private final int numLiterals;
    private int budget = 20;

    public LongShapes(Random rnd, int numInputs, int numLiterals) {
      this.rnd = rnd;
      this.numInputs = numInputs;
      this.numLiterals = numLiterals;
    }

    private static boolean saturated(long bound) {
      return bound == Long.MAX_VALUE;
    }

    private Gen column() {
      return new Gen(new ColumnRef(rnd.nextInt(numInputs), LaneType.LONG), LONG_COLUMN_BOUND);
    }

    private Gen leaf() {
      if (numLiterals > 0 && rnd.nextInt(4) == 0) {
        return new Gen(new LiteralSlot(rnd.nextInt(numLiterals), LaneType.LONG),
            LONG_LITERAL_BOUND);
      }
      return column();
    }

    public Gen value(int depth) {
      if (depth == 0 || budget <= 1) {
        return leaf();
      }
      budget -= 1;
      switch (rnd.nextInt(11)) {
        case 0:
        case 1: {
          // Add and subtract, over two subtrees or a subtree and a leaf. A checked mode only
          // where the bounds rule overflow out, as at the int lane: the kernel has to answer,
          // not decline, for the value comparison to check anything.
          Gen a = value(depth - 1);
          Gen b = rnd.nextBoolean() ? leaf() : value(depth - 1);
          long sumBound = satAdd(a.bound(), b.bound());
          Overflow mode = saturated(sumBound)
              ? Overflow.WRAP
              : switch (rnd.nextInt(3)) {
                case 0 -> Overflow.WRAP;
                case 1 -> Overflow.FAIL;
                default -> Overflow.NULL;
              };
          IntOp op = rnd.nextBoolean() ? IntOp.ADD : IntOp.SUB;
          return new Gen(new IntArith(op, mode, a.node(), b.node()), sumBound);
        }
        case 2: {
          // A checked multiply has no 64-bit overflow test, so WRAP is the only mode.
          Gen a = value(depth - 1);
          Gen b = value(depth - 1);
          return new Gen(new IntArith(IntOp.MUL, Overflow.WRAP, a.node(), b.node()),
              satMul(a.bound(), b.bound()));
        }
        case 3: {
          // Negation overflows on Long.MIN_VALUE alone, which any unsaturated bound rules out.
          Gen a = value(depth - 1);
          boolean checked = !saturated(a.bound()) && rnd.nextBoolean();
          return new Gen(new IntNeg(checked ? Overflow.FAIL : Overflow.WRAP, a.node()),
              a.bound());
        }
        case 4: {
          // The constant division, over a dividend the lowering is exact for. Above the bound
          // the conversion form is off by one and the magic form reads the dividend modulo
          // 2^52, and neither is a bug this suite is asking about.
          Gen a = value(depth - 1);
          if (a.bound() >= ConstDivide.EXACT_DIVIDEND_BOUND) {
            return a;
          }
          long d = LONG_DIVISORS[rnd.nextInt(LONG_DIVISORS.length)];
          // The grammar tracks this subtree's bound, and the node carries it, which is the
          // whole of VARKA-147 seen from the generator's side. Plus one, because
          // the grammar's bound is the widest magnitude a value takes and the node's is one the
          // value stays under - a guard from -bound to +bound admits bound itself.
          return new Gen(new ConstDivide(a.node(), d, a.bound() + 1), a.bound() / Math.abs(d));
        }
        case 5: {
          Gen a = value(depth - 1);
          if (saturated(a.bound())) {
            return new Gen(new GuardedRange(a.node(), Long.MIN_VALUE, Long.MAX_VALUE),
                a.bound());
          }
          return new Gen(new GuardedRange(a.node(), -a.bound(), a.bound()), a.bound());
        }
        case 6: {
          Gen a = value(depth - 1);
          Gen b = value(depth - 1);
          return new Gen(new Greatest(a.node(), b.node()), Math.max(a.bound(), b.bound()));
        }
        case 7: {
          Gen a = value(depth - 1);
          Gen b = value(depth - 1);
          return new Gen(new Least(a.node(), b.node()), Math.max(a.bound(), b.bound()));
        }
        case 9: {
          // The bitwise operations and shifts (VARKA-335), which can reach any long.
          Gen a = value(depth - 1);
          Gen b = rnd.nextBoolean() ? leaf() : value(depth - 1);
          BitOpKind[] ops = BitOpKind.values();
          return new Gen(new BitOp(ops[rnd.nextInt(ops.length)], a.node(), b.node()),
              Long.MAX_VALUE);
        }
        case 10:
          return new Gen(new BitCount(value(depth - 1).node()), Long.SIZE);
        default: {
          Cond c = cond(depth - 1);
          Gen a = value(depth - 1);
          Gen b = value(depth - 1);
          return new Gen(new IfElse(c, a.node(), b.node()), Math.max(a.bound(), b.bound()));
        }
      }
    }

    public Cond cond(int depth) {
      budget -= 1;
      if (depth == 0 || budget <= 1 || rnd.nextInt(3) == 0) {
        CompareOp[] ops = CompareOp.values();
        CompareOp op = ops[rnd.nextInt(ops.length)];
        VarkaVectorIR left = value(depth).node();
        return new Compare(op, left, value(depth).node());
      }
      return switch (rnd.nextInt(4)) {
        case 0 -> {
          Cond left = cond(depth - 1);
          yield new And(left, cond(depth - 1));
        }
        case 1 -> {
          Cond left = cond(depth - 1);
          yield new Or(left, cond(depth - 1));
        }
        case 2 -> new Not(cond(depth - 1));
        default -> rnd.nextBoolean()
            ? new IsNotNull(new ColumnRef(rnd.nextInt(numInputs), LaneType.LONG))
            : new IsNotNull(cond(depth - 1));
      };
    }

    /**
     * An output root: a value tree, or - where its bound fits an int - that tree narrowed to a
     * 32-bit column, the root every {@code TIME} kernel has ({@code VarkaTimeCompiler}). The
     * narrowing is a truncation with no overflow check, so it is drawn, as the compiler builds
     * it, only over values proven to fit; and it is admitted at an output root alone, so it is
     * drawn here and never inside {@code value}. The decision draws a number only when the bound
     * fits, so a shape none of whose roots fits is the shape it was before the arm (VARKA-235).
     */
    public VarkaVectorIR root(int depth) {
      Gen v = value(depth);
      if (v.bound() <= Integer.MAX_VALUE && rnd.nextInt(3) == 0) {
        return new NarrowLane(v.node());
      }
      return v.node();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Both lanes in one kernel (VARKA-28).
  // ---------------------------------------------------------------------------------------------

  /**
   * The seed of the third corpus, trees of both lanes, which the fuzzer runs and the bytes oracle
   * pins as {@code fuzz_mixed}. A sequence of its own, so that drawing it moves neither committed
   * corpus.
   */
  public static final long MIXED_FUZZ_SEED = 20261019L;

  /**
   * One drawn shape of both lanes: the roots, the int columns {@code [0, intInputs)} and the long
   * ones {@code [intInputs, numInputs)}, and the two literal tables' counts.
   */
  public record DrawnMixed(List<VarkaVectorIR> roots, int intInputs, int numInputs,
      int intLiterals, int longLiterals) {
    public DrawnMixed {
      roots = List.copyOf(roots);
    }

    /**
     * The literal count the emitter is told, as the compiler's tables would give it: both
     * tables' where the roots read the int table, and the long table's alone where they do not,
     * since a kernel that holds no int literal reads only the long table, of that count.
     */
    public int numLiterals() {
      for (VarkaVectorIR root : roots) {
        if (readsIntLiteral(root)) {
          return intLiterals + longLiterals;
        }
      }
      return longLiterals;
    }

    private static boolean readsIntLiteral(VarkaVectorIR node) {
      if (node instanceof LiteralSlot l) {
        return l.lane() == LaneType.INT;
      }
      for (VarkaVectorIR child : VarkaVectorIR.childrenOf(node)) {
        if (readsIntLiteral(child)) {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * {@link #drawShape}'s twin over both lanes: the column and literal counts of each lane, the
   * depth and the roots, every root holding a long-lane node so that the kernel is one
   * (`VARKA-28.md` 9).
   */
  public static DrawnMixed drawMixedShape(Random rnd) {
    int intInputs = 1 + rnd.nextInt(2);
    int longInputs = 1 + rnd.nextInt(2);
    int intLiterals = rnd.nextInt(3);
    int longLiterals = rnd.nextInt(3);
    var shapes = new MixedShapes(rnd, intInputs, longInputs, intLiterals, longLiterals);
    int depth = 1 + rnd.nextInt(4);
    List<VarkaVectorIR> roots;
    if (rnd.nextInt(5) == 0) {
      roots = List.of(shapes.rootCond(depth));
    } else {
      int count = 1 + rnd.nextInt(3);
      var drawn = new ArrayList<VarkaVectorIR>();
      for (int i = 0; i < count; i++) {
        drawn.add(shapes.root(depth));
      }
      roots = distinct(drawn);
    }
    return new DrawnMixed(roots, intInputs, intInputs + longInputs, intLiterals, longLiterals);
  }

  /** {@code node} with every column's ordinal moved up by {@code by}, the rest kept. */
  static VarkaVectorIR shiftColumns(VarkaVectorIR node, int by) {
    if (node instanceof ColumnRef c) {
      return new ColumnRef(c.ordinal() + by, c.lane());
    }
    VarkaVectorIR[] children = VarkaVectorIR.childrenOf(node);
    if (children.length == 0) {
      return node;
    }
    VarkaVectorIR[] shifted = new VarkaVectorIR[children.length];
    for (int k = 0; k < children.length; k++) {
      shifted[k] = shiftColumns(children[k], by);
    }
    return VarkaVectorIR.withChildren(node, shifted);
  }

  /**
   * The generator over both lanes: the int lane's trees are {@link Shapes}' - the calendar, the
   * divisions and the range sets among them - over the int columns, the long lane's are
   * {@link LongShapes}' over the long ones, and between them the two conversions, with the
   * arithmetic, the picks and the conditional over either lane above them. A widening is exact,
   * so its bound is its child's; a narrowing is a truncation, whose bound is the child's where
   * that fits an int, and is drawn in its ANSI form - over a range guard of the int range - only
   * there, since a guard that fires declines the batch and the reference has no spelling for
   * that. A checked mode is drawn only where the bounds rule its overflow out, as in the two
   * single-lane grammars.
   */
  public static final class MixedShapes {
    private static final long INT_MAX = Integer.MAX_VALUE;

    private final Random rnd;
    private final int intInputs;
    private final int longInputs;
    private final int intLiterals;
    private final int longLiterals;
    private int budget = 20;

    public MixedShapes(Random rnd, int intInputs, int longInputs, int intLiterals,
        int longLiterals) {
      this.rnd = rnd;
      this.intInputs = intInputs;
      this.longInputs = longInputs;
      this.intLiterals = intLiterals;
      this.longLiterals = longLiterals;
    }

    private Gen intLeaf() {
      if (intLiterals > 0 && rnd.nextInt(4) == 0) {
        return new Gen(new LiteralSlot(rnd.nextInt(intLiterals)), LITERAL_BOUND);
      }
      return new Gen(new ColumnRef(rnd.nextInt(intInputs)), COLUMN_BOUND);
    }

    private Gen longLeaf() {
      if (longLiterals > 0 && rnd.nextInt(4) == 0) {
        return new Gen(new LiteralSlot(rnd.nextInt(longLiterals), LaneType.LONG),
            LONG_LITERAL_BOUND);
      }
      return new Gen(new ColumnRef(intInputs + rnd.nextInt(longInputs), LaneType.LONG),
          LONG_COLUMN_BOUND);
    }

    /** An int value whose magnitude stays within {@code bound}, saturating. */
    private static long intBound(long bound) {
      return Math.min(bound, INT_MAX + 1);
    }

    private Overflow mode(long bound, long limit) {
      if (bound > limit) {
        return Overflow.WRAP;
      }
      return switch (rnd.nextInt(3)) {
        case 0 -> Overflow.WRAP;
        case 1 -> Overflow.FAIL;
        default -> Overflow.NULL;
      };
    }

    public Gen intValue(int depth) {
      if (depth == 0 || budget <= 1) {
        return intLeaf();
      }
      budget -= 1;
      switch (rnd.nextInt(7)) {
        case 0: {
          // The LEGACY narrowing: the low 32 bits, of any value.
          Gen a = longValue(depth - 1);
          return new Gen(new NarrowLane(a.node()), intBound(a.bound()));
        }
        case 1: {
          // The ANSI narrowing, over a value that fits.
          Gen a = longValue(depth - 1);
          if (a.bound() > INT_MAX) {
            return new Gen(new NarrowLane(a.node()), INT_MAX + 1);
          }
          return new Gen(new NarrowLane(new GuardedRange(a.node(), Integer.MIN_VALUE,
              Integer.MAX_VALUE)), a.bound());
        }
        case 2: {
          // The int lane's own grammar, over the int columns.
          var inner = new Shapes(rnd, intInputs, intLiterals, -1, -1);
          Gen g = inner.value(depth - 1);
          return new Gen(g.node(), intBound(g.bound()));
        }
        case 3: {
          Gen a = intValue(depth - 1);
          Gen b = rnd.nextBoolean() ? intLeaf() : intValue(depth - 1);
          long sum = satAdd(a.bound(), b.bound());
          IntOp op = rnd.nextBoolean() ? IntOp.ADD : IntOp.SUB;
          return new Gen(new IntArith(op, mode(sum, INT_MAX - 1), a.node(), b.node()),
              intBound(sum));
        }
        case 4: {
          Gen a = intValue(depth - 1);
          Gen b = intValue(depth - 1);
          VarkaVectorIR node = rnd.nextBoolean() ? new Greatest(a.node(), b.node())
              : new Least(a.node(), b.node());
          return new Gen(node, Math.max(a.bound(), b.bound()));
        }
        case 5: {
          Cond c = intCond(depth - 1);
          Gen a = intValue(depth - 1);
          Gen b = intValue(depth - 1);
          return new Gen(new IfElse(c, a.node(), b.node()), Math.max(a.bound(), b.bound()));
        }
        default:
          return intLeaf();
      }
    }

    public Gen longValue(int depth) {
      if (depth == 0 || budget <= 1) {
        return longLeaf();
      }
      budget -= 1;
      switch (rnd.nextInt(7)) {
        case 0:
        case 1: {
          // The widening, exact over every int.
          Gen a = intValue(depth - 1);
          return new Gen(new WidenLane(a.node()), a.bound());
        }
        case 2: {
          // The long lane's own grammar, over the long columns.
          var inner = new LongShapes(rnd, longInputs, longLiterals);
          Gen g = inner.value(depth - 1);
          return new Gen(shiftColumns(g.node(), intInputs), g.bound());
        }
        case 3: {
          Gen a = longValue(depth - 1);
          Gen b = rnd.nextBoolean() ? longLeaf() : longValue(depth - 1);
          long sum = satAdd(a.bound(), b.bound());
          IntOp op = rnd.nextBoolean() ? IntOp.ADD : IntOp.SUB;
          return new Gen(new IntArith(op, mode(sum, Long.MAX_VALUE - 1), a.node(), b.node()),
              sum);
        }
        case 4: {
          Gen a = longValue(depth - 1);
          Gen b = longValue(depth - 1);
          VarkaVectorIR node = rnd.nextBoolean() ? new Greatest(a.node(), b.node())
              : new Least(a.node(), b.node());
          return new Gen(node, Math.max(a.bound(), b.bound()));
        }
        case 5: {
          Cond c = longCond(depth - 1);
          Gen a = longValue(depth - 1);
          Gen b = longValue(depth - 1);
          return new Gen(new IfElse(c, a.node(), b.node()), Math.max(a.bound(), b.bound()));
        }
        default:
          return longLeaf();
      }
    }

    public Cond intCond(int depth) {
      budget -= 1;
      if (depth == 0 || budget <= 1 || rnd.nextInt(3) == 0) {
        CompareOp[] ops = CompareOp.values();
        CompareOp op = ops[rnd.nextInt(ops.length)];
        VarkaVectorIR left = intValue(depth).node();
        return new Compare(op, left, intValue(depth).node());
      }
      return switch (rnd.nextInt(4)) {
        case 0 -> {
          Cond left = intCond(depth - 1);
          yield new And(left, intCond(depth - 1));
        }
        case 1 -> {
          Cond left = intCond(depth - 1);
          yield new Or(left, intCond(depth - 1));
        }
        case 2 -> new Not(intCond(depth - 1));
        default -> new IsNotNull(new ColumnRef(rnd.nextInt(intInputs)));
      };
    }

    public Cond longCond(int depth) {
      budget -= 1;
      if (depth == 0 || budget <= 1 || rnd.nextInt(3) == 0) {
        CompareOp[] ops = CompareOp.values();
        CompareOp op = ops[rnd.nextInt(ops.length)];
        VarkaVectorIR left = longValue(depth).node();
        return new Compare(op, left, longValue(depth).node());
      }
      return switch (rnd.nextInt(4)) {
        case 0 -> {
          Cond left = longCond(depth - 1);
          yield new And(left, longCond(depth - 1));
        }
        case 1 -> {
          Cond left = longCond(depth - 1);
          yield new Or(left, longCond(depth - 1));
        }
        case 2 -> new Not(longCond(depth - 1));
        default -> new IsNotNull(new ColumnRef(intInputs + rnd.nextInt(longInputs),
            LaneType.LONG));
      };
    }

    /**
     * An output root: a long-lane value, or an int-lane one that holds a narrowing, at the root or
     * under an add, so that every root of the kernel is driven at the long lane.
     */
    public VarkaVectorIR root(int depth) {
      if (rnd.nextBoolean()) {
        return longValue(depth).node();
      }
      Gen narrowed = intValue(0);
      Gen a = longValue(Math.max(0, depth - 1));
      VarkaVectorIR narrow = new NarrowLane(a.node());
      if (rnd.nextBoolean()) {
        return narrow;
      }
      Gen b = rnd.nextBoolean() ? narrowed : intValue(Math.max(0, depth - 1));
      return new IntArith(rnd.nextBoolean() ? IntOp.ADD : IntOp.SUB, Overflow.WRAP, narrow,
          b.node());
    }

    /** A selection root: a long-lane condition, or an int one over a narrowing. */
    public Cond rootCond(int depth) {
      if (rnd.nextBoolean()) {
        return longCond(depth);
      }
      CompareOp[] ops = CompareOp.values();
      CompareOp op = ops[rnd.nextInt(ops.length)];
      VarkaVectorIR narrow = new NarrowLane(longValue(Math.max(0, depth - 1)).node());
      return new Compare(op, narrow, intValue(Math.max(0, depth - 1)).node());
    }
  }
}
