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

import static java.util.Map.entry;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtTerm.and;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtTerm.app;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtTerm.ite;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtTerm.not;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtTerm.num;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtTerm.or;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtTerm.App;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtTerm.Flag;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtTerm.Num;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtTerm.Op;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtTerm.Sort;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtTerm.Var;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.*;

/**
 * Each IR node's meaning, stated once (VARKA-506 2.2): one exhaustive switch over the sealed IR
 * that gives a node's lane outcome - {@code (decline, valid, data)}, as {@code refinement.smt2}'s
 * header has it - from its children's, in {@code java.smt2}'s operators. A node with no template
 * is refused with its reason ({@link #REFUSED}), so a new IR record does not compile until it is
 * given one or the other. The meanings sit here, beside {@link VarkaReference}, until row 489's
 * per-node descriptor lands and they move into it (VARKA-506.md 8 point 2).
 *
 * <p>A condition's outcome is three-valued: {@code valid} is that its truth is known and
 * {@code data} that truth, read only where it is known. {@code decline} is a lane the kernel
 * refuses the batch at: a checked operation's overflow and a leaf's load check, propagated from
 * every child except an {@code IfElse} arm the lane does not take. Leaves are free variables: a
 * column {@code c<n>} with validity {@code v<n>}, and a runtime literal the table's constant, or
 * {@code l<i>} (int) and {@code m<i>} (long) when no table is given. {@link #facts} holds the
 * range each variable ranges over.
 */
final class VarkaSmtNodes {

  /** A lane's outcome: whether it declines the batch, whether it is valid, and its value. */
  record Outcome(VarkaSmtTerm decline, VarkaSmtTerm valid, VarkaSmtTerm data) { }

  /** A node the printer has no meaning for, with the reason. */
  static final class Refused extends RuntimeException {
    Refused(VarkaVectorIR node, String reason) {
      super(node.getClass().getSimpleName() + ": " + reason);
    }
  }

  private static final String CALENDAR = "a calendar node: refinement.smt2 states the calendar "
      + "by hand, which this row leaves as it is (VARKA-506.md 7)";
  private static final String DIVISION = "a division stands for its lowering's form, proven in "
      + "long_divide.smt2 or int_mulhi_divide.smt2, with an obligation on its dividends; that is "
      + "the refinement's, from 506b on (VARKA-506.md 2.5)";

  /** The node types refused whatever their operands, and why. */
  static final Map<Class<?>, String> REFUSED = Map.ofEntries(
      entry(AddDays.class, CALENDAR), entry(SubDays.class, CALENDAR),
      entry(DateDiff.class, CALENDAR), entry(DayOfWeek.class, CALENDAR),
      entry(WeekDay.class, CALENDAR), entry(DayOfWeekIso.class, CALENDAR),
      entry(NextDay.class, CALENDAR), entry(ThursdayOf.class, CALENDAR),
      entry(AddMonths.class, CALENDAR), entry(MakeDate.class, CALENDAR),
      entry(GuardedDay.class, CALENDAR), entry(Year.class, CALENDAR),
      entry(Month.class, CALENDAR), entry(DayOfMonth.class, CALENDAR),
      entry(Quarter.class, CALENDAR), entry(DayOfYear.class, CALENDAR),
      entry(LastDay.class, CALENDAR), entry(TruncDate.class, CALENDAR),
      entry(TruncDateDynamic.class, CALENDAR), entry(WeekOfYear.class, CALENDAR),
      entry(ConstDivide.class, DIVISION), entry(BoundedDivide.class, DIVISION),
      entry(BitCount.class, "a popcount has no linear integer meaning"),
      entry(MathUnary.class, "a floating function: the prelude has no double as a value"),
      entry(RoundToIntegral.class, "a rounding of the double lane: the prelude has no double"),
      entry(ConvertLane.class, "a conversion to or from a floating lane"),
      entry(CrossLane.class, "a condition moved between lanes; no decimal shape builds one"),
      entry(InRanges.class, "a range set of the int lane; no decimal shape builds one"),
      entry(BoolCompare.class, "an order of two booleans; no decimal shape builds one"),
      entry(BoolNullSafeEq.class, "a boolean <=>; no decimal shape builds one"));

  private final int[] intLits;
  private final long[] longLits;
  private final Map<VarkaVectorIR, Outcome> memo = new HashMap<>();

  /** Each leaf variable's range, by name: what a proof file asserts of it. */
  final Map<String, VarkaSmtTerm> facts = new LinkedHashMap<>();

  /** A printer over the literal tables, either of them {@code null} to leave its slots free. */
  VarkaSmtNodes(int[] intLits, long[] longLits) {
    this.intLits = intLits;
    this.longLits = longLits;
  }

  /** The outcome of {@code node}, its subtrees rendered once each. */
  Outcome outcome(VarkaVectorIR node) {
    Outcome o = memo.get(node);
    if (o == null) {
      o = template(node, this::outcome);
      memo.put(node, o);
    }
    return o;
  }

  /** The outcome of {@code node} with its children's given by {@code child}. */
  Outcome template(VarkaVectorIR node, Function<VarkaVectorIR, Outcome> child) {
    return switch (node) {
      case ColumnRef c -> column(c.ordinal(), sortOf(c, c.lane()), new Flag(false));
      case DecimalColumn c -> {
        Outcome leaf = column(c.ordinal(), Sort.LONG, new Flag(false));
        yield new Outcome(and(leaf.valid(), outside(leaf.data(), c.bound())), leaf.valid(),
            leaf.data());
      }
      // The unscaled value u of 128 bits; the low word is u mod 2^64 read signed, the high
      // floor(u / 2^64). The load declines a valid u past 10^p - 1 in magnitude.
      case DecimalWord w -> {
        var u = new Var("c" + w.ordinal(), Sort.EXACT);
        BigInteger top = BigInteger.ONE.shiftLeft(127);
        facts.put(u.name(), and(app(Op.LE, num(top.negate()), u), app(Op.LT, u, num(top))));
        var valid = new Var("v" + w.ordinal(), Sort.BOOL);
        VarkaSmtTerm data = w.word() == Word.LOW ? app(Op.JLONG_WRAP, u)
            : app(Op.DIV, u, num(VarkaSmtTerm.WORD));
        yield new Outcome(and(valid, outside(u, w.bound().value())), valid, data);
      }
      case LiteralSlot l -> new Outcome(new Flag(false), new Flag(true), literal(l));
      case NullValue n -> new Outcome(new Flag(false), new Flag(false), num(0));
      case BoolColumn b -> {
        var valid = new Var("v" + b.ordinal(), Sort.BOOL);
        yield new Outcome(new Flag(false), valid, new Var("b" + b.ordinal(), Sort.BOOL));
      }
      case CondConst c -> switch (c.value()) {
        case TRUE -> new Outcome(new Flag(false), new Flag(true), new Flag(true));
        case FALSE -> new Outcome(new Flag(false), new Flag(true), new Flag(false));
        case NULL -> new Outcome(new Flag(false), new Flag(false), new Flag(false));
      };
      case IntArith n -> arith(n, child.apply(n.left()), child.apply(n.right()));
      case IntNeg n -> negate(n, child.apply(n.child()));
      case BitOp n -> bits(n, child.apply(n.left()), child.apply(n.right()));
      case Compare n -> compare(n, child.apply(n.left()), child.apply(n.right()));
      case NullSafeEqual n -> {
        Outcome l = child.apply(n.left());
        Outcome r = child.apply(n.right());
        VarkaSmtTerm both = and(l.valid(), r.valid());
        yield new Outcome(or(l.decline(), r.decline()), new Flag(true),
            or(and(both, app(Op.EQ, l.data(), r.data())), and(not(l.valid()), not(r.valid()))));
      }
      case IsNotNull n -> {
        Outcome c = child.apply(n.child());
        yield new Outcome(c.decline(), new Flag(true), c.valid());
      }
      case Not n -> {
        Outcome c = child.apply(n.child());
        yield new Outcome(c.decline(), c.valid(), not(c.data()));
      }
      // Kleene: known where both are known, or one is known false (AND) or true (OR).
      case And n -> kleene(child.apply(n.left()), child.apply(n.right()), true);
      case Or n -> kleene(child.apply(n.left()), child.apply(n.right()), false);
      case IfElse n -> choose(child.apply(n.cond()), child.apply(n.thenNode()),
          child.apply(n.elseNode()));
      case CondIf n -> choose(child.apply(n.cond()), child.apply(n.thenCond()),
          child.apply(n.elseCond()));
      case CondValue n -> {
        Outcome c = child.apply(n.cond());
        sortOf(n, n.lane());
        yield new Outcome(c.decline(), c.valid(), ite(c.data(), num(1), num(0)));
      }
      case BoolOutput n -> child.apply(n.cond());
      // The null-skipping pick: the other operand where one is null, the larger (smaller) of two.
      case Greatest n -> pick(n, child.apply(n.left()), child.apply(n.right()), Op.GE);
      case Least n -> pick(n, child.apply(n.left()), child.apply(n.right()), Op.LE);
      case GuardedRange g -> {
        Outcome c = child.apply(g.child());
        sortOf(g, g.laneType());
        VarkaSmtTerm out = or(app(Op.LT, c.data(), num(g.lo())), app(Op.GT, c.data(), num(g.hi())));
        yield switch (g.mode()) {
          case FAIL -> new Outcome(or(c.decline(), and(c.valid(), out)), c.valid(), c.data());
          case NULL -> new Outcome(c.decline(), and(c.valid(), not(out)), c.data());
          case WRAP -> throw new Refused(g, "a range guard has no WRAP mode");
        };
      }
      case WidenLane n when n.target() == LaneType.LONG && n.child().laneType() == LaneType.INT ->
          lift(child.apply(n.child()), Op.I2L);
      case NarrowLane n when n.target() == LaneType.INT && n.child().laneType() == LaneType.LONG ->
          lift(child.apply(n.child()), Op.L2I);
      case WidenLane n -> throw new Refused(n, "only the widening of an int to a long");
      case NarrowLane n -> throw new Refused(n, "only the narrowing of a long to an int");
      // The decimal store writes its child's long unchanged (VarkaReference's rule).
      case WidenDecimal n -> child.apply(n.child());
      case AddDays _, SubDays _, DateDiff _, DayOfWeek _, WeekDay _, DayOfWeekIso _, NextDay _,
          ThursdayOf _, Chrono _, AddMonths _, MakeDate _, GuardedDay _, ConstDivide _,
          BoundedDivide _, BitCount _, MathUnary _, RoundToIntegral _, ConvertLane _, CrossLane _,
          InRanges _, BoolCompare _, BoolNullSafeEq _ -> throw refused(node);
    };
  }

  private static Refused refused(VarkaVectorIR node) {
    String why = REFUSED.get(node.getClass());
    if (why == null) {
      throw new IllegalStateException("no refusal reason for " + node.getClass());
    }
    return new Refused(node, why);
  }

  /** The lane's sort: the int and long lanes only. */
  private static Sort sortOf(VarkaVectorIR node, LaneType lane) {
    return switch (lane) {
      case INT -> Sort.INT;
      case LONG -> Sort.LONG;
      case BYTE, SHORT -> throw new Refused(node, "a narrow lane: its wrap is narrow_wrap.smt2's");
      case FLOAT, DOUBLE -> throw new Refused(node, "a floating lane: the prelude has no double "
          + "as a value");
    };
  }

  private Outcome column(int ordinal, Sort sort, VarkaSmtTerm decline) {
    var c = new Var("c" + ordinal, sort);
    facts.put(c.name(), app(sort == Sort.INT ? Op.JINT_IN : Op.JLONG_IN, c));
    return new Outcome(decline, new Var("v" + ordinal, Sort.BOOL), c);
  }

  private VarkaSmtTerm literal(LiteralSlot l) {
    boolean isLong = sortOf(l, l.lane()) == Sort.LONG;
    if (isLong ? longLits != null : intLits != null) {
      return num(isLong ? longLits[l.index()] : intLits[l.index()]);
    }
    var v = new Var((isLong ? "m" : "l") + l.index(), isLong ? Sort.LONG : Sort.INT);
    facts.put(v.name(), app(isLong ? Op.JLONG_IN : Op.JINT_IN, v));
    return v;
  }

  private static VarkaSmtTerm outside(VarkaSmtTerm u, Object bound) {
    BigInteger b = new BigInteger(bound.toString());
    return or(app(Op.GT, u, num(b)), app(Op.LT, u, num(b.negate())));
  }

  /** The lane's overflow modes over one exact result: WRAP, FAIL's decline and NULL's null. */
  private static Outcome checked(Overflow mode, Sort sort, VarkaSmtTerm decline,
      VarkaSmtTerm valid, VarkaSmtTerm data, VarkaSmtTerm exact) {
    VarkaSmtTerm fits = app(sort == Sort.INT ? Op.JINT_IN : Op.JLONG_IN, exact);
    return switch (mode) {
      case WRAP -> new Outcome(decline, valid, data);
      case FAIL -> new Outcome(or(decline, and(valid, not(fits))), valid, data);
      case NULL -> new Outcome(decline, and(valid, fits), data);
    };
  }

  private static Outcome arith(IntArith n, Outcome l, Outcome r) {
    Sort sort = sortOf(n, n.laneType());
    boolean isInt = sort == Sort.INT;
    if (n.op() == IntOp.MUL && !(l.data() instanceof Num) && !(r.data() instanceof Num)) {
      throw new Refused(n, "a product of two unknowns: the free-product rule is 506c's "
          + "(VARKA-506.md 2.4)");
    }
    if (n.op() == IntOp.MUL && !isInt && n.mode() == Overflow.FAIL) {
      throw new Refused(n, "the long lane has no checked multiply");
    }
    Op java = switch (n.op()) {
      case ADD -> isInt ? Op.JINT_ADD : Op.JLONG_ADD;
      case SUB -> isInt ? Op.JINT_SUB : Op.JLONG_SUB;
      case MUL -> isInt ? Op.JINT_MUL : Op.JLONG_MUL;
      case DIV -> throw new Refused(n, "an integral lane has no division");
    };
    Op exact = switch (n.op()) {
      case ADD -> Op.ADD;
      case SUB -> Op.SUB;
      case MUL, DIV -> Op.MUL;
    };
    return checked(n.mode(), sort, or(l.decline(), r.decline()), and(l.valid(), r.valid()),
        app(java, l.data(), r.data()), app(exact, l.data(), r.data()));
  }

  private static Outcome negate(IntNeg n, Outcome c) {
    Sort sort = sortOf(n, n.laneType());
    VarkaSmtTerm x = c.data();
    VarkaSmtTerm neg = app(sort == Sort.INT ? Op.JINT_NEG : Op.JLONG_NEG, x);
    VarkaSmtTerm negative = app(Op.LT, x, num(0));
    return checked(n.mode(), sort, c.decline(), c.valid(), n.abs() ? ite(negative, neg, x) : neg,
        n.abs() ? ite(negative, app(Op.NEG, x), x) : app(Op.NEG, x));
  }

  /**
   * Java's shifts, and an {@code AND} in the two cases the prelude defines: a mask
   * {@code 2^k - 1} and a sign word {@code x >> 63}. Any other {@code AND}, an {@code OR} and an
   * {@code XOR} have no linear integer meaning, and are refused.
   */
  private static Outcome bits(BitOp n, Outcome l, Outcome r) {
    boolean isInt = sortOf(n, n.laneType()) == Sort.INT;
    VarkaSmtTerm decline = or(l.decline(), r.decline());
    VarkaSmtTerm valid = and(l.valid(), r.valid());
    Op op = switch (n.op()) {
      case SHL -> isInt ? Op.JINT_SHL : Op.JLONG_SHL;
      case SHR -> isInt ? Op.JINT_SHR : Op.JLONG_SHR;
      case USHR -> isInt ? Op.JINT_USHR : Op.JLONG_USHR;
      case AND -> isInt ? null : Op.JLONG_AND_LOW;
      case OR, XOR -> throw new Refused(n, "an " + n.op() + " of two unknowns has no linear "
          + "integer meaning; the disjoint OR of a lowering joins in 506c");
    };
    if (op == null) {
      throw new Refused(n, "an int-lane AND: no decimal lowering builds one");
    }
    if (op != Op.JLONG_AND_LOW) {
      return new Outcome(decline, valid, app(op, l.data(), r.data()));
    }
    if (r.data() instanceof Num(var m) && m.signum() > 0
        && m.add(BigInteger.ONE).bitCount() == 1) {
      return new Outcome(decline, valid, app(op, l.data(), num(m.add(BigInteger.ONE))));
    }
    if (isSignWord(l.data()) || isSignWord(r.data())) {
      boolean left = isSignWord(l.data());
      return new Outcome(decline, valid, app(Op.JLONG_AND_SIGN, left ? l.data() : r.data(),
          left ? r.data() : l.data()));
    }
    throw new Refused(n, "an AND that is neither a low mask nor a sign word's");
  }

  /** {@code x >> 63} on the long lane, whose lanes are 0 or -1. */
  private static boolean isSignWord(VarkaSmtTerm t) {
    return t instanceof App(var op, var args) && op == Op.JLONG_SHR
        && args.get(1) instanceof Num(var s) && s.intValue() == 63;
  }

  private static Outcome compare(Compare n, Outcome l, Outcome r) {
    boolean isInt = sortOf(n, n.laneType()) == Sort.INT;
    if (isInt && n.op().isUnsigned()) {
      throw new Refused(n, "no int-lane unsigned compare is built");
    }
    Op op = switch (n.op()) {
      case LT -> Op.LT;
      case LE -> Op.LE;
      case GT -> Op.GT;
      case GE -> Op.GE;
      case EQ -> Op.EQ;
      case ULT -> Op.JLONG_ULT;
      case ULE -> Op.JLONG_ULE;
      case UGT -> Op.JLONG_UGT;
      case UGE -> Op.JLONG_UGE;
    };
    return new Outcome(or(l.decline(), r.decline()), and(l.valid(), r.valid()),
        app(op, l.data(), r.data()));
  }

  private static Outcome kleene(Outcome l, Outcome r, boolean isAnd) {
    VarkaSmtTerm settles = isAnd
        ? or(and(l.valid(), not(l.data())), and(r.valid(), not(r.data())))
        : or(and(l.valid(), l.data()), and(r.valid(), r.data()));
    return new Outcome(or(l.decline(), r.decline()), or(and(l.valid(), r.valid()), settles),
        isAnd ? and(l.data(), r.data()) : or(l.data(), r.data()));
  }

  /** The THEN arm where the condition is known true, else the ELSE arm; each arm's declines too. */
  private static Outcome choose(Outcome c, Outcome t, Outcome e) {
    VarkaSmtTerm takes = and(c.valid(), c.data());
    return new Outcome(or(c.decline(), ite(takes, t.decline(), e.decline())),
        ite(takes, t.valid(), e.valid()), ite(takes, t.data(), e.data()));
  }

  private static Outcome pick(VarkaVectorIR n, Outcome l, Outcome r, Op keepsLeft) {
    sortOf(n, n.laneType());
    VarkaSmtTerm both = and(l.valid(), r.valid());
    return new Outcome(or(l.decline(), r.decline()), or(l.valid(), r.valid()),
        ite(both, ite(app(keepsLeft, l.data(), r.data()), l.data(), r.data()),
            ite(l.valid(), l.data(), r.data())));
  }

  private static Outcome lift(Outcome c, Op op) {
    return new Outcome(c.decline(), c.valid(), app(op, c.data()));
  }
}
