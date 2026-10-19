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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaIrGrammar.Gen;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddDays;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddMonths;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.And;
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
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Year;
import org.apache.spark.sql.catalyst.util.DateTimeUtils$;

/**
 * Every well-typed IR tree up to a size, by sort (VARKA-269): the series
 * {@code VarkaSmallTreeSuite} runs through the emitter and compares with the reference evaluator,
 * where {@link VarkaIrGrammar} draws trees at random. Random draws miss corners; a series of every
 * tree of a size has none to miss.
 *
 * <p>The series is the grammar's, arm by arm: the same nodes, the same structural rules (a day
 * offset is a leaf, {@code IsNotNull} is over a column or a condition, a month count is a literal
 * or the small column, a trunc level the level column) and the same magnitude gates (a calendar
 * node over a subtree that stays inside the narrowed range, a checked mode where the bounds rule
 * overflow out, a range guard whose bounds contain its child's), through the grammar's own
 * {@code boundsOf}. So a tree here is one the grammar could draw, and the series answers what the
 * grammar's distribution never promised: whether every such tree of a size was checked.
 * {@code gated = false} drops the magnitude gates, which is how the suite asks what they keep out.
 *
 * <p>A tree's size is the number of grammar productions in it: a leaf is one - a column or a
 * literal slot, and for a condition {@code IsNotNull} over a column - and each arm is one, however
 * many IR nodes it builds. {@code make_date} over a date's fields is the grammar's one arm, though
 * its IR repeats the date three times; {@code weekofyear} is one arm over {@code ThursdayOf}.
 *
 * <p>The environment is fixed, so a size always gives the same series, in the same order: the day
 * columns and the literal slots are the leaves, and the special columns appear only where an arm
 * reads them. Where the grammar draws {@code literal()} - a literal slot, or a column when its
 * iteration has none - the series takes both, the union over the grammar's environments. Two day
 * columns rather than one, because a node over one column twice and over two columns are
 * different kernels. The order is load-bearing: the nightly runs the series in slices by index.
 *
 * <p>Every size below the one asked for is held; the size asked for is generated as it is read,
 * which is what lets the largest run at all.
 */
public final class VarkaIrSeries {

  private VarkaIrSeries() {
  }

  /** The int lane's day columns, the generic leaves with the literal slot. */
  public static final List<Integer> DAY_COLUMNS = List.of(0, 1);
  /** The column whose values stay inside the month-count range: {@code add_months}' count only. */
  public static final int SMALL_COLUMN = 2;
  /** The column whose values are trunc-level codes: {@code TruncDateDynamic}'s level only. */
  public static final int LEVEL_COLUMN = 3;
  public static final int NUM_INPUTS = 4;
  /** The literal slots, the generic leaves with the columns. */
  public static final int LITERALS = 1;

  /** The long lane's columns, all generic leaves. */
  public static final List<Integer> LONG_COLUMNS = List.of(0, 1);
  public static final int LONG_NUM_INPUTS = 2;

  /** The divisors of the grammar's {@code ConstDivide} arm, for the reasons it gives. */
  private static final int[] CONST_DIVIDE_DIVISORS = VarkaIrGrammar.Shapes.CONST_DIVIDE_DIVISORS;

  /** The long lane's divisors, {@code LongShapes}' set. */
  private static final long[] LONG_DIVISORS = VarkaIrGrammar.LongShapes.LONG_DIVISORS;

  /** A tree of the series and its size: what the suites run. */
  public record Sized(int size, VarkaVectorIR root) { }

  // ---- lazy iteration ---------------------------------------------------------------------
  // The size asked for is generated as it is read. Streams would not do: a flatMap read through
  // `iterator()` pushes each outer element's whole expansion into a buffer, which at the top
  // size is most of the series.

  /** {@code f} of each element of {@code outer}, flattened, each expansion made when reached. */
  private static <A, B> Iterator<B> flatMap(Iterator<A> outer,
      Function<? super A, ? extends Iterator<? extends B>> f) {
    return new Iterator<>() {
      private Iterator<? extends B> inner = Collections.emptyIterator();

      @Override
      public boolean hasNext() {
        while (!inner.hasNext()) {
          if (!outer.hasNext()) {
            return false;
          }
          inner = f.apply(outer.next());
        }
        return true;
      }

      @Override
      public B next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        return inner.next();
      }
    };
  }

  private static <A, B> Iterator<B> map(Iterator<A> it, Function<? super A, ? extends B> f) {
    return new Iterator<B>() {
      @Override
      public boolean hasNext() {
        return it.hasNext();
      }

      @Override
      public B next() {
        return f.apply(it.next());
      }
    };
  }

  /** The parts in order, each started only when the one before it is spent. */
  @SafeVarargs
  private static <A> Iterator<A> concat(Supplier<? extends Iterator<? extends A>>... parts) {
    return flatMap(Arrays.asList(parts).iterator(), Supplier::get);
  }

  /** Every split of {@code n} into {@code k} positive parts, in order. */
  private static List<int[]> splits(int n, int k) {
    var out = new ArrayList<int[]>();
    if (k == 1) {
      if (n >= 1) {
        out.add(new int[] {n});
      }
      return out;
    }
    for (int first = 1; first <= n - k + 1; first++) {
      for (int[] rest : splits(n - first, k - 1)) {
        int[] split = new int[k];
        split[0] = first;
        System.arraycopy(rest, 0, split, 1, rest.length);
        out.add(split);
      }
    }
    return out;
  }

  /**
   * The range sets {@code InRanges} takes, laid over the child's own bound as the grammar places
   * its random one: one range across the middle, and two either side of zero with a gap, so that
   * lanes land inside, between and outside.
   */
  private static List<List<Integer>> rangeSets(long bound) {
    int span = (int) Math.max(4L, Math.min(bound, Integer.MAX_VALUE / 4L));
    return List.of(List.of(-span / 2, span / 2), List.of(-span, -span / 4, span / 4, span));
  }

  /**
   * The series of one lane: value and condition trees by size, the smaller sizes held, and the
   * roots a kernel is made of.
   */
  public abstract static class Series {
    private final Map<Integer, List<Gen>> heldValues = new HashMap<>();
    private final Map<Integer, List<Cond>> heldConds = new HashMap<>();

    private static <A> List<A> drain(Iterator<A> it) {
      var out = new ArrayList<A>();
      it.forEachRemaining(out::add);
      return Collections.unmodifiableList(out);
    }

    /** Every value tree of exactly {@code n} productions, held. */
    public List<Gen> value(int n) {
      List<Gen> held = heldValues.get(n);
      if (held == null) {
        // Not computeIfAbsent: generating size n asks for the smaller sizes, which would change
        // the map inside its own update.
        held = drain(values(n));
        heldValues.put(n, held);
      }
      return held;
    }

    /** Every condition tree of exactly {@code n} productions, held. */
    public List<Cond> cond(int n) {
      List<Cond> held = heldConds.get(n);
      if (held == null) {
        held = drain(conds(n));
        heldConds.put(n, held);
      }
      return held;
    }

    /** Every value tree of exactly {@code n} productions, generated as it is read. */
    public abstract Iterator<Gen> values(int n);

    /** Every condition tree of exactly {@code n} productions, generated as it is read. */
    public abstract Iterator<Cond> conds(int n);

    /** The roots of size {@code n}: value roots, then condition roots. */
    public Iterator<VarkaVectorIR> roots(int n) {
      return concat(() -> map(values(n), Gen::node), () -> conds(n));
    }

    /** Every root up to size {@code n}, smallest first, with its size: what the suite runs. */
    public final Iterator<Sized> upTo(int n) {
      return flatMap(IntStream.rangeClosed(1, n).iterator(),
          k -> map(roots(k), root -> new Sized(k, root)));
    }

    /** The comparisons and the connectives of size {@code n}, which both lanes have. */
    protected Iterator<Cond> compareAndConnectives(int n) {
      return concat(
          () -> flatMap(splits(n - 1, 2).iterator(), s ->
              flatMap(value(s[0]).iterator(), a ->
                  flatMap(value(s[1]).iterator(), b ->
                      map(Arrays.asList(CompareOp.values()).iterator(),
                          op -> new Compare(op, a.node(), b.node()))))),
          () -> flatMap(splits(n - 1, 2).iterator(), s ->
              flatMap(cond(s[0]).iterator(), a ->
                  flatMap(cond(s[1]).iterator(), b ->
                      List.<Cond>of(new And(a, b), new Or(a, b)).iterator()))),
          () -> map(cond(n - 1).iterator(), Not::new),
          () -> map(cond(n - 1).iterator(), IsNotNull::new));
    }

    /** Every {@code IfElse} of {@code n} productions, which both lanes have. */
    protected Iterator<Gen> ifElses(int n) {
      return flatMap(splits(n - 1, 3).iterator(), s ->
          flatMap(cond(s[0]).iterator(), c ->
              flatMap(value(s[1]).iterator(), a ->
                  map(value(s[2]).iterator(), b -> new Gen(new IfElse(c, a.node(), b.node()),
                      Math.max(a.bound(), b.bound()))))));
    }
  }

  /** The int lane, {@code VarkaIrGrammar.Shapes}' arms. */
  public static final class IntSeries extends Series {

    private final boolean gated;
    private final List<Gen> leaves;

    public IntSeries() {
      this(true);
    }

    public IntSeries(boolean gated) {
      this.gated = gated;
      var out = new ArrayList<Gen>();
      for (int c : DAY_COLUMNS) {
        out.add(new Gen(new ColumnRef(c), VarkaIrGrammar.COLUMN_BOUND));
      }
      for (int j = 0; j < LITERALS; j++) {
        out.add(new Gen(new LiteralSlot(j), VarkaIrGrammar.LITERAL_BOUND));
      }
      this.leaves = List.copyOf(out);
    }

    private boolean fitsUnderChrono(Gen a) {
      return !gated || (a.bound() <= VarkaIrGrammar.CHRONO_BOUND
          && VarkaIrGrammar.boundsOf(a.node()).guarded() <= VarkaIrGrammar.CHRONO_BOUND);
    }

    /** The arms over one subtree. */
    private List<Gen> unary(Gen a) {
      var out = new ArrayList<Gen>();
      VarkaVectorIR n = a.node();
      out.add(new Gen(new DayOfWeek(n), 7));
      out.add(new Gen(new WeekDay(n), 6));
      out.add(new Gen(new ThursdayOf(n), VarkaIrGrammar.satAdd(a.bound(), 3)));
      out.add(new Gen(new DayOfWeekIso(n), 7));
      out.add(new Gen(new IntNeg(Overflow.WRAP, n), a.bound()));
      // Negation overflows on one value, which a bound inside the int range rules out.
      if (!gated || a.bound() <= Integer.MAX_VALUE) {
        out.add(new Gen(new IntNeg(Overflow.FAIL, n), a.bound()));
      }
      for (int d : CONST_DIVIDE_DIVISORS) {
        out.add(new Gen(new ConstDivide(n, d), a.bound() / Math.abs((long) d)));
      }
      // A range guard whose bounds contain the child's, the whole int range past it.
      boolean wide = a.bound() > Integer.MAX_VALUE;
      long bound = wide ? Integer.MAX_VALUE : a.bound();
      long lo = wide ? Integer.MIN_VALUE : -bound;
      out.add(new Gen(new GuardedRange(n, lo, bound), a.bound()));
      if (fitsUnderChrono(a)) {
        out.add(new Gen(new Year(n), 40000));
        out.add(new Gen(new Month(n), 12));
        out.add(new Gen(new DayOfMonth(n), 31));
        out.add(new Gen(new Quarter(n), 4));
        out.add(new Gen(new DayOfYear(n), 366));
        out.add(new Gen(new LastDay(n), VarkaIrGrammar.satAdd(a.bound(), 31)));
        out.add(new Gen(new GuardedDay(n), a.bound()));
        out.add(new Gen(new WeekOfYear(new ThursdayOf(n)), 53));
        for (boolean failOnError : new boolean[] {false, true}) {
          out.add(new Gen(new MakeDate(new Year(n), new Month(n), new DayOfMonth(n),
              failOnError), a.bound()));
        }
        out.add(new Gen(BoundedDivide.of(new DayOfYear(n), 7, 367), 53));
        out.add(new Gen(BoundedDivide.of(new Month(n), 4, 13), 4));
        out.add(new Gen(BoundedDivide.of(new DayOfMonth(n), 7, 32), 5));
        for (TruncLevel level : TruncLevel.values()) {
          out.add(new Gen(new TruncDate(n, level), VarkaIrGrammar.satAdd(a.bound(), 366)));
        }
      }
      return out;
    }

    /** The arms over a subtree and a leaf. */
    private List<Gen> withLeaf(Gen a, Gen b) {
      var out = new ArrayList<Gen>();
      long sumBound = VarkaIrGrammar.satAdd(a.bound(), b.bound());
      out.add(new Gen(new AddDays(a.node(), b.node()), sumBound));
      out.add(new Gen(new SubDays(a.node(), b.node()), sumBound));
      out.add(new Gen(new NextDay(a.node(), b.node()), VarkaIrGrammar.satAdd(a.bound(), 8)));
      // A checked mode only where the bounds rule overflow out: the kernel has to answer.
      List<Overflow> modes = !gated || sumBound <= Integer.MAX_VALUE
          ? List.of(Overflow.WRAP, Overflow.FAIL, Overflow.NULL)
          : List.of(Overflow.WRAP);
      for (Overflow mode : modes) {
        for (IntOp op : List.of(IntOp.ADD, IntOp.SUB)) {
          out.add(new Gen(new IntArith(op, mode, a.node(), b.node()), sumBound));
        }
      }
      out.add(new Gen(new IntArith(IntOp.MUL, Overflow.WRAP, a.node(), b.node()),
          VarkaIrGrammar.satMul(a.bound(), b.bound())));
      return out;
    }

    /** The arms over a subtree and a special column or a literal slot. */
    private List<Gen> withSpecial(Gen a) {
      if (!fitsUnderChrono(a)) {
        return List.of();
      }
      var out = new ArrayList<Gen>();
      var count = new Gen(new ColumnRef(SMALL_COLUMN), VarkaChrono.MONTH_ARITH_MAX_MONTHS);
      out.add(new Gen(new AddMonths(a.node(), count.node()),
          VarkaIrGrammar.satAdd(a.bound(), VarkaIrGrammar.satMul(count.bound(), 31))));
      out.add(new Gen(new TruncDateDynamic(a.node(), new ColumnRef(LEVEL_COLUMN)),
          VarkaIrGrammar.satAdd(a.bound(), 366)));
      for (int j = 0; j < LITERALS; j++) {
        var m = new Gen(new LiteralSlot(j), VarkaIrGrammar.LITERAL_BOUND);
        out.add(new Gen(new AddMonths(a.node(), m.node()),
            VarkaIrGrammar.satAdd(a.bound(), VarkaIrGrammar.satMul(m.bound(), 31))));
        // make_date's NULL form with a literal day: an invalid day is a null output.
        out.add(new Gen(new MakeDate(new Year(a.node()), new Month(a.node()), m.node(), false),
            VarkaIrGrammar.satAdd(a.bound(), 31)));
      }
      return out;
    }

    @Override
    public Iterator<Gen> values(int n) {
      if (n <= 0) {
        return Collections.emptyIterator();
      }
      if (n == 1) {
        return leaves.iterator();
      }
      return concat(
          () -> flatMap(value(n - 1).iterator(), a -> unary(a).iterator()),
          () -> flatMap(value(n - 2).iterator(), a ->
              flatMap(leaves.iterator(), b -> withLeaf(a, b).iterator())),
          () -> flatMap(value(n - 2).iterator(), a -> withSpecial(a).iterator()),
          () -> flatMap(splits(n - 1, 2).iterator(), s ->
              flatMap(value(s[0]).iterator(), a ->
                  flatMap(value(s[1]).iterator(), b -> List.of(
                      new Gen(new DateDiff(a.node(), b.node()),
                          VarkaIrGrammar.satAdd(a.bound(), b.bound())),
                      new Gen(new Greatest(a.node(), b.node()), Math.max(a.bound(), b.bound())),
                      new Gen(new Least(a.node(), b.node()), Math.max(a.bound(), b.bound())))
                      .iterator()))),
          () -> ifElses(n));
    }

    @Override
    public Iterator<Cond> conds(int n) {
      if (n <= 0) {
        return Collections.emptyIterator();
      }
      // A leaf condition: IsNotNull over a column, which reads the column's validity word. Over
      // a condition it is an arm of its own (compareAndConnectives).
      if (n == 1) {
        return map(IntStream.range(0, NUM_INPUTS).iterator(),
            c -> new IsNotNull(new ColumnRef(c)));
      }
      return concat(
          () -> compareAndConnectives(n),
          () -> flatMap(value(n - 1).iterator(), a ->
              map(rangeSets(a.bound()).iterator(), bounds -> new InRanges(a.node(), bounds))));
    }
  }

  /** The long lane, {@code VarkaIrGrammar.LongShapes}' arms, with {@code NarrowLane} at a root. */
  public static final class LongSeries extends Series {

    private final List<Gen> leaves;

    public LongSeries() {
      var out = new ArrayList<Gen>();
      for (int c : LONG_COLUMNS) {
        out.add(new Gen(new ColumnRef(c, LaneType.LONG), VarkaIrGrammar.LONG_COLUMN_BOUND));
      }
      for (int j = 0; j < LITERALS; j++) {
        out.add(new Gen(new LiteralSlot(j, LaneType.LONG), VarkaIrGrammar.LONG_LITERAL_BOUND));
      }
      this.leaves = List.copyOf(out);
    }

    private static boolean saturated(long bound) {
      return bound == Long.MAX_VALUE;
    }

    private List<Gen> unary(Gen a) {
      var out = new ArrayList<Gen>();
      out.add(new Gen(new IntNeg(Overflow.WRAP, a.node()), a.bound()));
      if (!saturated(a.bound())) {
        out.add(new Gen(new IntNeg(Overflow.FAIL, a.node()), a.bound()));
      }
      // The constant division, over a dividend the lowering is exact for.
      if (a.bound() < ConstDivide.EXACT_DIVIDEND_BOUND) {
        for (long d : LONG_DIVISORS) {
          out.add(new Gen(new ConstDivide(a.node(), d, a.bound() + 1), a.bound() / Math.abs(d)));
        }
      }
      out.add(saturated(a.bound())
          ? new Gen(new GuardedRange(a.node(), Long.MIN_VALUE, Long.MAX_VALUE), a.bound())
          : new Gen(new GuardedRange(a.node(), -a.bound(), a.bound()), a.bound()));
      return out;
    }

    /** The arms over two subtrees: add and subtract, multiply, greatest and least. */
    private List<Gen> binary(Gen a, Gen b) {
      var out = new ArrayList<Gen>();
      long sumBound = VarkaIrGrammar.satAdd(a.bound(), b.bound());
      List<Overflow> modes = saturated(sumBound)
          ? List.of(Overflow.WRAP)
          : List.of(Overflow.WRAP, Overflow.FAIL, Overflow.NULL);
      for (Overflow mode : modes) {
        for (IntOp op : List.of(IntOp.ADD, IntOp.SUB)) {
          out.add(new Gen(new IntArith(op, mode, a.node(), b.node()), sumBound));
        }
      }
      out.add(new Gen(new IntArith(IntOp.MUL, Overflow.WRAP, a.node(), b.node()),
          VarkaIrGrammar.satMul(a.bound(), b.bound())));
      out.add(new Gen(new Greatest(a.node(), b.node()), Math.max(a.bound(), b.bound())));
      out.add(new Gen(new Least(a.node(), b.node()), Math.max(a.bound(), b.bound())));
      return out;
    }

    @Override
    public Iterator<Gen> values(int n) {
      if (n <= 0) {
        return Collections.emptyIterator();
      }
      if (n == 1) {
        return leaves.iterator();
      }
      return concat(
          () -> flatMap(value(n - 1).iterator(), a -> unary(a).iterator()),
          () -> flatMap(splits(n - 1, 2).iterator(), s ->
              flatMap(value(s[0]).iterator(), a ->
                  flatMap(value(s[1]).iterator(), b -> binary(a, b).iterator()))),
          () -> ifElses(n));
    }

    @Override
    public Iterator<Cond> conds(int n) {
      if (n <= 0) {
        return Collections.emptyIterator();
      }
      if (n == 1) {
        return map(LONG_COLUMNS.iterator(),
            c -> new IsNotNull(new ColumnRef(c, LaneType.LONG)));
      }
      return compareAndConnectives(n);
    }

    /**
     * A value tree at a root is also narrowed to a 32-bit column where its bound fits an int, as
     * the compiler builds every {@code TIME} kernel's root; {@code NarrowLane} is admitted at an
     * output root alone, so it is added here and never inside a tree.
     */
    @Override
    public Iterator<VarkaVectorIR> roots(int n) {
      return concat(
          () -> flatMap(values(n), g -> g.bound() <= Integer.MAX_VALUE
              ? List.<VarkaVectorIR>of(g.node(), new NarrowLane(g.node())).iterator()
              : List.of(g.node()).iterator()),
          () -> conds(n));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The batches
  // ---------------------------------------------------------------------------------------------

  /** One batch: its name in a failure, the batch and the literal slot's value. */
  public record IntBatch(String name, VarkaKernelChecks.Batch batch, int[] lits) { }

  public record LongBatch(String name, VarkaKernelChecks.LongBatch batch, long[] lits) { }

  private static final IntPredicate NONE = i -> false;
  private static final IntPredicate ALL = i -> true;
  private static final IntPredicate EVERY_5TH = i -> i % 5 == 0;
  private static final IntPredicate ALTERNATING = i -> i % 2 == 1;

  private static boolean[][] nulls(int length, IntPredicate... patterns) {
    boolean[][] out = new boolean[patterns.length][length];
    for (int c = 0; c < patterns.length; c++) {
      for (int i = 0; i < length; i++) {
        out[c][i] = patterns[c].test(i);
      }
    }
    return out;
  }

  private static IntPredicate[] each(IntPredicate pattern, int columns) {
    IntPredicate[] out = new IntPredicate[columns];
    Arrays.fill(out, pattern);
    return out;
  }

  /**
   * The four batches the series runs on ({@code VarkaSmallTreeSuite}, {@code VarkaVariantSuite}),
   * drawn once from a fixed seed inside each column's bounds: every fifth row null at length 17,
   * no null at 64 (the check adds its forced-masked twin), a pattern per column at 33, and every
   * row null at 7.
   */
  public static synchronized List<IntBatch> intBatches() {
    if (intBatches == null) {
      intBatches = Batches.drawInt();
    }
    return intBatches;
  }

  public static synchronized List<LongBatch> longBatches() {
    if (longBatches == null) {
      longBatches = Batches.drawLong();
    }
    return longBatches;
  }

  // Each lane's batches, drawn on its first use and apart from the other's: a draw that throws -
  // the long lane's under a bad -Dvarka.fuzz.longColumnBound - fails that call alone, as a Scala
  // lazy val does, and leaves the int lane untouched, where a holder class's static initializer
  // would make both an ExceptionInInitializerError and every later use NoClassDefFoundError.
  private static List<IntBatch> intBatches;
  private static List<LongBatch> longBatches;

  /** How the batches are drawn, in this order, from these seeds. */
  private static final class Batches {

    private static List<IntBatch> drawInt() {
      var rnd = new Random(20261011L);
      int cols = NUM_INPUTS;
      return List.of(
          intBatch(rnd, "17 every-5th", 17, each(EVERY_5TH, cols)),
          intBatch(rnd, "64 null-free", 64, each(NONE, cols)),
          intBatch(rnd, "33 per-column", 33, ALTERNATING, EVERY_5TH, NONE, ALL),
          intBatch(rnd, "7 all-null", 7, each(ALL, cols)));
    }

    private static IntBatch intBatch(Random rnd, String name, int length,
        IntPredicate... patterns) {
      int[][] data = new int[NUM_INPUTS][length];
      for (int c = 0; c < NUM_INPUTS; c++) {
        for (int i = 0; i < length; i++) {
          if (c == LEVEL_COLUMN) {
            int week = DateTimeUtils$.MODULE$.TRUNC_TO_WEEK();
            data[c][i] = week + rnd.nextInt(DateTimeUtils$.MODULE$.TRUNC_TO_YEAR() - week + 1);
          } else {
            int bound = c == SMALL_COLUMN
                ? VarkaChrono.MONTH_ARITH_MAX_MONTHS
                : (int) VarkaIrGrammar.COLUMN_BOUND;
            data[c][i] = rnd.nextInt(2 * bound + 1) - bound;
          }
        }
      }
      int bound = VarkaIrGrammar.LITERAL_BOUND;
      int[] lits = new int[LITERALS];
      for (int j = 0; j < LITERALS; j++) {
        lits[j] = rnd.nextInt(2 * bound + 1) - bound;
      }
      return new IntBatch(name,
          new VarkaKernelChecks.Batch(length, nulls(length, patterns), data, false), lits);
    }

    private static List<LongBatch> drawLong() {
      var rnd = new Random(20261012L);
      int cols = LONG_NUM_INPUTS;
      return List.of(
          longBatch(rnd, "17 every-5th", 17, each(EVERY_5TH, cols)),
          longBatch(rnd, "64 null-free", 64, each(NONE, cols)),
          longBatch(rnd, "33 per-column", 33, ALTERNATING, ALL),
          longBatch(rnd, "7 all-null", 7, each(ALL, cols)));
    }

    private static LongBatch longBatch(Random rnd, String name, int length,
        IntPredicate... patterns) {
      long[][] data = new long[LONG_NUM_INPUTS][length];
      for (int c = 0; c < LONG_NUM_INPUTS; c++) {
        for (int i = 0; i < length; i++) {
          data[c][i] = VarkaIrGrammar.within(rnd, VarkaIrGrammar.LONG_COLUMN_BOUND);
        }
      }
      long[] lits = new long[LITERALS];
      for (int j = 0; j < LITERALS; j++) {
        lits[j] = VarkaIrGrammar.within(rnd, VarkaIrGrammar.LONG_LITERAL_BOUND);
      }
      return new LongBatch(name,
          new VarkaKernelChecks.LongBatch(length, nulls(length, patterns), data, false), lits);
    }
  }
}
