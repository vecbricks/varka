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
package org.apache.spark.sql.catalyst.expressions.codegen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.apache.spark.sql.catalyst.expressions.Abs;
import org.apache.spark.sql.catalyst.expressions.Add;
import org.apache.spark.sql.catalyst.expressions.BinaryArithmetic;
import org.apache.spark.sql.catalyst.expressions.BitmapExpressionUtils;
import org.apache.spark.sql.catalyst.expressions.BitwiseAnd;
import org.apache.spark.sql.catalyst.expressions.BitwiseCount;
import org.apache.spark.sql.catalyst.expressions.BitwiseNot;
import org.apache.spark.sql.catalyst.expressions.BitwiseOr;
import org.apache.spark.sql.catalyst.expressions.BitwiseXor;
import org.apache.spark.sql.catalyst.expressions.BoundReference;
import org.apache.spark.sql.catalyst.expressions.CaseWhen;
import org.apache.spark.sql.catalyst.expressions.Cast;
import org.apache.spark.sql.catalyst.expressions.Coalesce;
import org.apache.spark.sql.catalyst.expressions.Divide;
import org.apache.spark.sql.catalyst.expressions.DivideDTInterval;
import org.apache.spark.sql.catalyst.expressions.DivideYMInterval;
import org.apache.spark.sql.catalyst.expressions.EqualNullSafe;
import org.apache.spark.sql.catalyst.expressions.EqualTo;
import org.apache.spark.sql.catalyst.expressions.EvalMode$;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.GreaterThan;
import org.apache.spark.sql.catalyst.expressions.GreaterThanOrEqual;
import org.apache.spark.sql.catalyst.expressions.Greatest;
import org.apache.spark.sql.catalyst.expressions.If;
import org.apache.spark.sql.catalyst.expressions.In;
import org.apache.spark.sql.catalyst.expressions.InSet;
import org.apache.spark.sql.catalyst.expressions.IsNaN;
import org.apache.spark.sql.catalyst.expressions.IsNotNull;
import org.apache.spark.sql.catalyst.expressions.IsNull;
import org.apache.spark.sql.catalyst.expressions.Least;
import org.apache.spark.sql.catalyst.expressions.LessThan;
import org.apache.spark.sql.catalyst.expressions.LessThanOrEqual;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.Multiply;
import org.apache.spark.sql.catalyst.expressions.MultiplyDTInterval;
import org.apache.spark.sql.catalyst.expressions.MultiplyYMInterval;
import org.apache.spark.sql.catalyst.expressions.NaNvl;
import org.apache.spark.sql.catalyst.expressions.Pmod;
import org.apache.spark.sql.catalyst.expressions.Remainder;
import org.apache.spark.sql.catalyst.expressions.RuntimeReplaceable;
import org.apache.spark.sql.catalyst.expressions.ShiftLeft;
import org.apache.spark.sql.catalyst.expressions.ShiftRight;
import org.apache.spark.sql.catalyst.expressions.ShiftRightUnsigned;
import org.apache.spark.sql.catalyst.expressions.Signum;
import org.apache.spark.sql.catalyst.expressions.Sqrt;
import org.apache.spark.sql.catalyst.expressions.Subtract;
import org.apache.spark.sql.catalyst.expressions.ToDegrees;
import org.apache.spark.sql.catalyst.expressions.ToRadians;
import org.apache.spark.sql.catalyst.expressions.UnaryMinus;
import org.apache.spark.sql.catalyst.expressions.objects.StaticInvoke;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDerivedKind;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaErrors;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFamily;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaRangeAnalysis;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaOperandPosition;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypes;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitCount;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitOpKind;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConstDivide;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.GuardedRange;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntNeg;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;
import org.apache.spark.sql.types.DataTypes;

/**
 * The recursive node compiler of the Varka expression compiler: the chain of families that
 * lowers one Catalyst expression to the vector IR, and the operand helpers the families share.
 * The classifier in {@code VarkaExpressionCompiler} decides which projection entries to compile
 * and under what budgets; this class compiles one tree.
 *
 * <p>{@link #compileNode} asks each family of {@link #FAMILIES}, in order, whether it claims the
 * node, and runs the first claimant's lowering. A claim has no side effect (a family's
 * {@code arm} tests and returns a deferred call), so the order is not what decides a node's
 * compiler: no expression is claimed by two families, every arm being gated by the expression
 * class or its data type, and {@code VarkaFamilyChainSuite} holds that as a fact over the
 * coverage table, so a family added here or a widened guard that broke it fails there rather than
 * winning silently by coming first. An empty result anywhere fails the enclosing entry, whose
 * caller rolls the tables back to their pre-entry state.
 *
 * <p>The input and literal tables are the classifier's {@link VarkaSlotTable}s: the key is a
 * child ordinal (or a literal value), the slot its dense index.
 */
final class VarkaNodeCompiler {

  /**
   * The most literals an {@code IN} list may hold and still fuse, counted after dedup. The basis,
   * recorded in {@code VARKA-20.md}: 16 is depth-safe under any fold shape
   * ({@code MAX_CHAIN_DEPTH} = 16 while the balanced chain here is {@code ceil(log2 16) + 1} = 5
   * levels), and its 31 op nodes left half the emitter's {@code MAX_FUSED_NODES} = 64 budget to
   * the rest of the projection when that cap bounded every kernel; under the byte budget it
   * bounds only the reference form, and the choice of 16 stands on the depth argument. (The
   * emitter's broadcast hoist is NOT part of the basis: its gate counts the kernel's total
   * literal slots, so a capped IN plus any other literal already re-broadcasts inline - the
   * review pass corrected an earlier claim here.) Above the cap the entry declines with a reason
   * instead of silently losing the whole kernel at emission.
   */
  static final int MAX_IN_LITERALS = 16;

  /**
   * The bits per bitmap of Spark's bitmap functions, {@code BitmapExpressionUtils.NUM_BITS}, as
   * the bitmap lowering's shift and mask divide by it: 2^15.
   */
  static final int BITMAP_SHIFT = Integer.numberOfTrailingZeros(BitmapExpressionUtils.NUM_BITS);

  static {
    if (1 << BITMAP_SHIFT != BitmapExpressionUtils.NUM_BITS) {
      throw VarkaErrors.illegalState("BitmapExpressionUtils.NUM_BITS is no longer a power of "
          + "two: " + BitmapExpressionUtils.NUM_BITS);
    }
  }

  /** Tests whether a family claims a node; the claim returns its lowering, deferred. */
  @FunctionalInterface
  interface Claim {
    VarkaFamilyArm of(
        Expression e,
        VarkaSlotTable inputs,
        VarkaSlotTable literals,
        DeclineSink sink);
  }

  /**
   * A named link of the chain, with the session switch that turns it off ({@code family}), or
   * none for the date leaves, which fuse nothing of their own (VARKA-318).
   */
  record Family(String name, @Nullable VarkaFamily family, Claim claim) {
    /** A link with no switch: the date leaves, or a suite's copy of a link. */
    Family(String name, Claim claim) {
      this(name, null, claim);
    }

    Family(VarkaFamily family, Claim claim) {
      this(family.label(), family, claim);
    }
  }

  /**
   * A throw out of a family's lowering, carried from the innermost {@link #compileNode} that ran
   * the arm to the compiler's planning catch, which unwraps it: the family goes on the internal
   * error's record and its retry sentence names the switch (VARKA-318). Never seen outside the
   * compiler, so the exception a caller catches is the site's own, as VARKA-314 left it.
   */
  static final class FamilyFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient VarkaFamily family;

    FamilyFailure(VarkaFamily family, RuntimeException cause) {
      super(null, cause, false, false);
      this.family = family;
    }

    VarkaFamily family() {
      return family;
    }

    RuntimeException original() {
      return (RuntimeException) getCause();
    }
  }

  // Test-only: the family whose claimed nodes throw an injected internal error, as a bug in
  // its lowering would, so a suite can see the planning error name the family's switch.
  private static volatile @Nullable VarkaFamily failInFamilyForTesting = null;

  static void setFailInFamilyForTesting(@Nullable VarkaFamily family) {
    failInFamilyForTesting = family;
  }

  /**
   * The chain, in order: the date leaves, the four families, then the int arithmetic and the
   * picks, then the floating arithmetic, which claims only floating-valued nodes. The int
   * arithmetic comes after the calendar family so that the {@code Add(WeekDay, 1)} shape keeps
   * its dedicated node, and its guard says so as well.
   */
  private static final List<Family> FAMILIES = List.of(
      new Family("date leaves", VarkaNodeCompiler::leafArm),
      new Family(VarkaFamily.CALENDAR, VarkaChronoCompiler::arm),
      new Family(VarkaFamily.INTERVAL, VarkaIntervalCompiler::arm),
      new Family(VarkaFamily.TIME, VarkaTimeCompiler::arm),
      new Family(VarkaFamily.CONDITION, VarkaConditionCompiler::arm),
      new Family(VarkaFamily.INT_ARITHMETIC, VarkaNodeCompiler::arithmeticArm),
      new Family(VarkaFamily.FLOATING_ARITHMETIC, VarkaFloatingCompiler::arm));

  /**
   * The predicate family, which is not a link of the chain: {@code compileNode} never claims a
   * comparison or a connective, whose value is a mask and not a lane, and they are reached only
   * through {@code VarkaConditionCompiler.compileCond}, from a filter's conjunct or a
   * conditional's condition (VARKA-318). It is here so that the suites read its claim beside the
   * chain's: no node is claimed by two of them, so each node is under one switch.
   */
  private static final Family PREDICATES = new Family(VarkaFamily.PREDICATE,
      (e, inputs, literals, sink) -> {
        var arm = VarkaConditionCompiler.condArm(e, inputs, literals, sink);
        return arm == null ? null : () -> arm.get().map(cond -> cond);
      });

  private VarkaNodeCompiler() {
  }

  /**
   * Compiles {@code expr}. Shapes that cannot be served stay unclaimed by construction: a
   * {@code date_add} over a {@code datediff} result only type-checks through a {@code Cast},
   * which compiles to nothing here. Integer arithmetic over a {@code datediff} result is
   * admitted by the int32 lowering; a SIMD lane still cannot throw row-accurately, so an ANSI
   * overflow condemns the batch and the row engine raises it instead.
   */
  static Optional<VarkaVectorIR> compileNode(
      Expression expr,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    Optional<String> floating = floatingDecline(expr);
    if (floating.isPresent()) {
      return sink.decline(floating.get(), expr);
    }
    for (Family family : FAMILIES) {
      VarkaFamilyArm arm;
      try {
        arm = family.claim().of(expr, inputs, literals, sink);
      } catch (RuntimeException e) {
        // A claim's guards are the family's code too: a throw there names the family as well.
        throw family.family() == null || e instanceof FamilyFailure
            ? e
            : new FamilyFailure(family.family(), e);
      }
      if (arm != null) {
        return family.family() == null
            ? arm.compile()
            : inFamily(family.family(), arm::compile, expr, sink);
      }
    }
    return fallback(expr, inputs, literals, sink);
  }

  /** Whether {@code ir} is a narrow read widened to the int lane, which its load does. */
  private static boolean isNarrowLoadWidening(Object ir) {
    return ir instanceof WidenLane w && w.target() == LaneType.INT
        && (w.child() instanceof ColumnRef || w.child() instanceof LiteralSlot)
        && w.child().laneType().isNarrow();
  }

  /**
   * Runs {@code lowering}, of {@code family} and claimed for {@code expr}, under the family's
   * switch (VARKA-318): a family the session has switched off declines the node with the reason
   * naming the switch, and a family that is on lowers it and is noted as used. A read is never
   * declined by a switch and uses no family, because a switch keeps a family's operations off
   * Varka, not the columns they read: a column or a literal, which the interval and time families
   * claim for their lanes and which the 64-bit arithmetic of other families shares, and a cast of
   * one that lowers to the bare read - the relabels type coercion inserts, such as
   * {@code TIME(3)} to {@code TIME(6)} before a comparison. A throw out of the lowering leaves as
   * a {@link FamilyFailure} naming the innermost family it passed through.
   */
  static <T> Optional<T> inFamily(
      VarkaFamily family, Supplier<Optional<T>> lowering, Expression expr, DeclineSink sink) {
    boolean read = isRead(expr);
    boolean relabel = expr instanceof Cast c && isRead(c.child());
    if (!read && !relabel && sink.off(family)) {
      return sink.decline(family.offReason(), expr);
    }
    try {
      if (!read && failInFamilyForTesting == family) {
        throw VarkaErrors.illegalArgument("injected failure in Varka's " + family.label()
            + " family");
      }
      Optional<T> built = lowering.get();
      if (built.isEmpty() || read) {
        return built;
      }
      if (relabel && (built.get() instanceof ColumnRef || built.get() instanceof LiteralSlot
          || isNarrowLoadWidening(built.get()))) {
        // A relabel: the read itself, no family's operation. A TINYINT or SMALLINT read widened
        // to the int lane is one too: the widening is the column's load (VARKA-329), and no
        // instruction stands for the cast.
        return built;
      }
      if (relabel && sink.off(family)) {
        // A cast of a read that computes: the family's operation, and the family is off.
        return sink.decline(family.offReason(), expr);
      }
      sink.used(family);
      return built;
    } catch (FamilyFailure e) {
      throw e;
    } catch (RuntimeException e) {
      throw new FamilyFailure(family, e);
    }
  }

  private static boolean isRead(Expression e) {
    return e instanceof BoundReference || e instanceof Literal;
  }

  /**
   * Why {@code e} declines because it reads or makes a {@code FLOAT} or {@code DOUBLE} value
   * that no lowering serves yet, or empty. The floating lanes serve two kinds of node. Some move
   * a value's bits - a column, a literal, a null test and the choices, {@code IF},
   * {@code CASE WHEN} and {@code coalesce} - and answer the same bits whatever the value means.
   * The others order values - the comparisons, {@code <=>}, {@code greatest}, {@code least},
   * {@code IN}, {@code isnan} and {@code nanvl} - and are lowered in Spark's order, in which NaN
   * equals NaN and is greater than every other value and {@code -0.0} equals {@code 0.0}, not
   * the vector compare's or {@code max}'s. The arithmetic the floating family lowers is named
   * here too; the casts, the remainders and the interval forms by a floating factor are not. So
   * the list is of what is served, not of what is refused, and a node no one has thought of
   * declines. Both compile entries ask it before any arm claims, so no arm sees a floating
   * operand it was not written for. A {@link RuntimeReplaceable} passes, to be asked again as its
   * replacement.
   */
  static Optional<String> floatingDecline(Expression e) {
    if (!touchesFloating(e) || e instanceof BoundReference || e instanceof Literal
        || e instanceof If || e instanceof CaseWhen || e instanceof Coalesce
        || e instanceof IsNull || e instanceof IsNotNull || e instanceof RuntimeReplaceable
        // The six comparisons by name rather than as BinaryComparison, so that a comparison
        // class Spark adds later declines until someone has written its lowering.
        || e instanceof EqualTo || e instanceof EqualNullSafe || e instanceof LessThan
        || e instanceof LessThanOrEqual || e instanceof GreaterThan
        || e instanceof GreaterThanOrEqual || e instanceof Greatest || e instanceof Least
        || e instanceof In || e instanceof InSet || e instanceof IsNaN || e instanceof NaNvl
        // The floating arithmetic family's forms (VARKA-342), by name for the same reason.
        // Signum over an interval answers a double too, and stays out until its own step.
        || e instanceof Add || e instanceof Subtract || e instanceof Multiply
        || e instanceof Divide || e instanceof UnaryMinus || e instanceof Abs
        || e instanceof Sqrt || e instanceof ToDegrees || e instanceof ToRadians
        || (e instanceof Signum s && isFloating(s.child().dataType()))) {
      return Optional.empty();
    }
    String what = switch (e) {
      case Cast _ -> "a cast to or from a floating type (row 343)";
      case Remainder _, Pmod _ -> "a floating remainder (row 346)";
      // The interval products and quotients by a floating factor, and signum over an interval,
      // are row 342's step after row 343's conversions. UnaryPositive is RuntimeReplaceable, so
      // it never reaches this switch: it passes above and is asked again as its child.
      case Signum _, MultiplyYMInterval _, DivideYMInterval _, MultiplyDTInterval _,
          DivideDTInterval _, BinaryArithmetic _ ->
          "floating arithmetic (row 342)";
      default -> "an operation over floating values that no floating lowering serves";
    };
    return Optional.of(what);
  }

  /** Whether {@code e} or one of its operands is of a floating type. */
  private static boolean touchesFloating(Expression e) {
    if (isFloating(e.dataType())) {
      return true;
    }
    var children = e.children().iterator();
    while (children.hasNext()) {
      if (isFloating(children.next().dataType())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isFloating(org.apache.spark.sql.types.DataType t) {
    return DataTypes.FloatType.equals(t) || DataTypes.DoubleType.equals(t);
  }

  /** The chain, for the suite that holds no node is claimed twice. */
  static List<Family> families() {
    return FAMILIES;
  }

  /** The predicate family's claim, which the chain does not dispatch to, for the same suites. */
  static Family predicates() {
    return PREDICATES;
  }

  /** The names of the families of {@code chain} that claim {@code expr}, in chain order. */
  static List<String> claimants(
      List<Family> chain,
      Expression expr,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    var names = new ArrayList<String>();
    for (Family family : chain) {
      if (family.claim().of(expr, inputs, literals, sink) != null) {
        names.add(family.name());
      }
    }
    return names;
  }

  /**
   * The column leaves: a date column, a date literal, the identity date cast, the narrow ones
   * (a bare {@code tinyint} or {@code smallint} column and its literal, VARKA-329) and the
   * floating ones (a bare {@code float} or {@code double} column and its literal, VARKA-341).
   * First in the chain, ahead of every family.
   */
  private static VarkaFamilyArm leafArm(
      Expression e,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    return switch (e) {
      case BoundReference br when DataTypes.DateType.equals(br.dataType()) ->
          () -> Optional.of(columnRef(br, inputs));
      // A narrow column is read at its own lane: the input's data segment is one or two bytes a
      // row, and the emitter widens each loaded value to the int lane (VARKA-329). The leaf is
      // the only place a narrow lane enters the IR from a Spark type, so a kernel's lane is
      // decided by which columns it reads.
      case BoundReference br when DataTypes.ByteType.equals(br.dataType()) ->
          () -> Optional.of(columnRef(br, inputs, LaneType.BYTE));
      case BoundReference br when DataTypes.ShortType.equals(br.dataType()) ->
          () -> Optional.of(columnRef(br, inputs, LaneType.SHORT));
      // A floating column is read at its own lane (VARKA-341), and a floating literal is its
      // bits in the table of its width: a float's in the int table, a double's in the long one,
      // which the emitter reinterprets where it broadcasts the literal.
      case BoundReference br when DataTypes.FloatType.equals(br.dataType()) ->
          () -> Optional.of(columnRef(br, inputs, LaneType.FLOAT));
      case BoundReference br when DataTypes.DoubleType.equals(br.dataType()) ->
          () -> Optional.of(columnRef(br, inputs, LaneType.DOUBLE));
      case Literal l when l.value() instanceof Float f
          && DataTypes.FloatType.equals(l.dataType()) ->
          () -> Optional.of(slot(Float.floatToRawIntBits(f), LaneType.FLOAT, literals));
      case Literal l when l.value() instanceof Double d
          && DataTypes.DoubleType.equals(l.dataType()) ->
          () -> Optional.of(sink.longSlot(Double.doubleToRawLongBits(d), LaneType.DOUBLE));
      // A narrow literal's value fits an int exactly, so it takes a slot in the same
      // per-distinct-value table, on the narrow lane the comparison with a column needs.
      case Literal l when l.value() instanceof Byte b && DataTypes.ByteType.equals(l.dataType()) ->
          () -> Optional.of(slot(b.intValue(), LaneType.BYTE, literals));
      case Literal l when l.value() instanceof Short s
          && DataTypes.ShortType.equals(l.dataType()) ->
          () -> Optional.of(slot(s.intValue(), LaneType.SHORT, literals));
      // A date literal's value is already an epoch-day int, so it takes a slot in the shared
      // per-distinct-value table like a folded day offset does - what makes
      // `d < DATE'...'` and `greatest(d, DATE'...')` reachable at all. A null-valued Literal is
      // not claimed; that is a safe blind spot, not a bug, since ConstantFolding removes a null
      // date literal from any real query before it can reach here (unix_date/date_from_unix_date
      // add two more recursive paths into this same match, both equally covered by that
      // guarantee).
      case Literal l when l.value() instanceof Integer days
          && DataTypes.DateType.equals(l.dataType()) ->
          () -> Optional.of(intSlot(days, literals));
      // The identity cast: the corpus wraps date expressions in `CAST(... AS DATE)`
      // 85 times, and after optimization the wrapper is a no-op over an already-date child -
      // unwrap it. A `cast(<string literal> AS DATE)` never reaches here (constant-folded to a
      // date literal by the optimizer); a string *column* cast is a per-row parse with no
      // string lane and stays declined.
      case Cast c when DataTypes.DateType.equals(c.dataType())
          && DataTypes.DateType.equals(c.child().dataType()) ->
          () -> compileNode(c.child(), inputs, literals, sink);
      default -> null;
    };
  }

  /**
   * The int32 arithmetic over int-valued operands and the null-skipping picks, after the
   * families: the {@code Add(WeekDay, 1)} shape keeps its dedicated calendar node because the
   * calendar arms come first in the chain.
   */
  private static VarkaFamilyArm arithmeticArm(
      Expression e,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    return switch (e) {
      // Spark's greatest/least are n-ary; the null-skipping algebra is associative, so a left
      // fold into the binary IR nodes is exact.
      case Greatest g -> () -> VarkaConditionCompiler.foldPick(
          g, g.children(), inputs, literals, sink, VarkaNodeCompiler::greatest);
      case Least l -> () -> VarkaConditionCompiler.foldPick(
          l, l.children(), inputs, literals, sink, VarkaNodeCompiler::least);
      // extract(DAYOFWEEK_ISO) / date_part('DOW_ISO'): the analyzer spells them Add(WeekDay(d), 1),
      // and so does a hand-written weekday(d) + 1. One narrow arm, either operand order, and
      // nothing else: integer arithmetic over an output is out of scope for this compiler. Int32
      // arithmetic over int-valued operands - a fused field, an IntegerType column, an int
      // literal, or nested arithmetic. Placed after the `Add(WeekDay, 1)` arm of the calendar
      // family so that shape keeps its cheaper dedicated node.
      case Add a when isInt(a) && !VarkaChronoCompiler.isDayOfWeekIso(a) ->
          () -> intArith(IntOp.ADD, a.evalMode(), a.left(), a.right(), a, inputs, literals, sink);
      case Subtract a when isInt(a) ->
          () -> intArith(IntOp.SUB, a.evalMode(), a.left(), a.right(), a, inputs, literals, sink);
      case Multiply a when isInt(a) ->
          () -> intArith(IntOp.MUL, a.evalMode(), a.left(), a.right(), a, inputs, literals, sink);
      // The narrow lanes' arithmetic (VARKA-330): a `TINYINT` or `SMALLINT` operation wraps at
      // its own width, and its three modes are Spark's there rather than at 32 bits. The result
      // type is the left operand's, so both operands are of the node's narrow type after
      // analysis; a hand-built tree that pairs two lanes declines in `arithOver` instead of
      // building a node whose constructor would throw.
      case Add a when isNarrow(a) -> () -> narrowArith(
          IntOp.ADD, a.evalMode(), a.left(), a.right(), a, inputs, literals, sink);
      case Subtract a when isNarrow(a) -> () -> narrowArith(
          IntOp.SUB, a.evalMode(), a.left(), a.right(), a, inputs, literals, sink);
      case Multiply a when isNarrow(a) -> () -> narrowArith(
          IntOp.MUL, a.evalMode(), a.left(), a.right(), a, inputs, literals, sink);
      // The two casts between the int and the long lane (VARKA-28), and with them every
      // implicit promotion Catalyst's type coercion inserts where an int meets a bigint:
      // `i < l` arrives as `CAST(i AS BIGINT) < l`. Widening is a sign extension, exact in every
      // mode. Narrowing keeps the low 32 bits under LEGACY; under ANSI a value past the int range
      // raises CAST_OVERFLOW, which the kernel cannot, so a range guard of the int range declines
      // the batch and the row engine raises it on the row; TRY declines (VARKA-332).
      case Cast c when isInt(c) && DataTypes.LongType.equals(c.child().dataType()) ->
          () -> narrowing(c, inputs, literals, sink);
      case Cast c when DataTypes.LongType.equals(c.dataType()) && isInt(c.child()) ->
          () -> intOperand(c.child(), inputs, literals, sink)
              .map(x -> (VarkaVectorIR) new WidenLane(x));
      // The casts between DATE and TIMESTAMP_NTZ (VARKA-338), and with them the coercion that
      // makes `d < ntz` a comparison of `CAST(d AS TIMESTAMP_NTZ)` with the NTZ value. Neither
      // reads the session zone: Spark converts at UTC both ways. The identity cast is its child.
      case Cast c when isNtz(c) && DataTypes.DateType.equals(c.child().dataType()) ->
          () -> ntzOfDate(c, inputs, literals, sink);
      case Cast c when DataTypes.DateType.equals(c.dataType()) && isNtz(c.child()) ->
          () -> dateOfNtz(c, inputs, literals, sink);
      case Cast c when isNtz(c) && isNtz(c.child()) ->
          () -> compileNode(c.child(), inputs, literals, sink);
      // A narrow value promoted to the int lane, which is what Catalyst's coercion inserts where
      // a TINYINT or SMALLINT meets an int - `greatest(y1, y2) < i` arrives as
      // `CAST(greatest(y1, y2) AS INT) < i`, and `y1 + i` as an int add of the cast (VARKA-329).
      // The widening is a sign extension, exact in every mode, and the emitter's load already
      // reads the narrow column into the int species, so the conversion costs no instruction.
      case Cast c when isInt(c) && isNarrow(c.child()) ->
          () -> compileNode(c.child(), inputs, literals, sink)
              .map(x -> (VarkaVectorIR) new WidenLane(x, LaneType.INT));
      case UnaryMinus n when isInt(n) ->
          // Spark has no try_negative, so the mode is only ever WRAP or FAIL here. Negation
          // overflows on exactly one value, `Int.MinValue`, so any bound at all rules it out and
          // the check comes off - the same reasoning the binary arms use, on a narrower fact.
          () -> intOperand(n.child(), inputs, literals, sink).map(x -> {
            boolean checked = n.failOnError() && !boundedByIntMax(x, literals, sink);
            return (VarkaVectorIR) new IntNeg(checked ? Overflow.FAIL : Overflow.WRAP, x);
          });
      // A narrow negation is the same node at its own width (VARKA-330): `-(-128Y)` is the byte
      // that the negation cannot represent, so the check is on the lane's minimum. Spark has no
      // try_negative here either, so the mode is WRAP or FAIL; ANSI always keeps the check,
      // since a narrow column's range contains the minimum that overflows.
      case UnaryMinus n when isNarrow(n) -> () -> narrowOperand(
          n.child(), VarkaTypes.lane(n.dataType()).orElseThrow(), inputs, literals, sink)
          .map(x -> (VarkaVectorIR) new IntNeg(
              n.failOnError() ? Overflow.FAIL : Overflow.WRAP, x));
      // `abs` over a narrow lane is the negation's node with its `abs` flag: Spark's
      // `x < 0 ? negateExact(x) : x`, whose legacy form is `(byte) Math.abs(x)` - `abs(-128Y)`
      // is `-128Y` there. Its one overflowing input is the negation's, the lane's minimum, so
      // under ANSI the check is the negation's too. There is no try_abs in Spark.
      case Abs a when isNarrow(a) -> () -> narrowOperand(
          a.child(), VarkaTypes.lane(a.dataType()).orElseThrow(), inputs, literals, sink)
          .map(x -> (VarkaVectorIR) new IntNeg(
              a.failOnError() ? Overflow.FAIL : Overflow.WRAP, x, true));
      // The bitwise family (VARKA-335): one lane-wise operation at the operand's width, which
      // neither overflows nor nulls a valid lane, so the evaluation mode does not reach it.
      case BitwiseAnd b when onBitLane(b) ->
          () -> bitOp(BitOpKind.AND, b.left(), b.right(), inputs, literals, sink);
      case BitwiseOr b when onBitLane(b) ->
          () -> bitOp(BitOpKind.OR, b.left(), b.right(), inputs, literals, sink);
      case BitwiseXor b when onBitLane(b) ->
          () -> bitOp(BitOpKind.XOR, b.left(), b.right(), inputs, literals, sink);
      // `~x` is `x ^ -1`, the literal on the operand's lane.
      case BitwiseNot b when isInt(b) -> () -> intOperand(b.child(), inputs, literals, sink)
          .map(x -> new BitOp(BitOpKind.XOR, x, intSlot(-1, literals)));
      case BitwiseNot b when isLong(b) -> () -> longOperand(b.child(), inputs, literals, sink)
          .map(x -> new BitOp(BitOpKind.XOR, x, sink.longSlot(-1L)));
      case ShiftLeft s when onBitLane(s) ->
          () -> shift(BitOpKind.SHL, s.left(), s.right(), inputs, literals, sink);
      case ShiftRight s when onBitLane(s) ->
          () -> shift(BitOpKind.SHR, s.left(), s.right(), inputs, literals, sink);
      case ShiftRightUnsigned s when onBitLane(s) ->
          () -> shift(BitOpKind.USHR, s.left(), s.right(), inputs, literals, sink);
      case BitwiseCount c when isInt(c.child()) ->
          () -> intOperand(c.child(), inputs, literals, sink).map(BitCount::new);
      // The long lane's count is an int computed in the long lane, at most 64: a NarrowLane over
      // the popcount, at an output root and under another node alike.
      case BitwiseCount c when isLong(c.child()) -> () -> longOperand(c.child(), inputs, literals,
          sink).map(x -> new VarkaVectorIR.NarrowLane(new BitCount(x)));
      case StaticInvoke si when isBitmap(si) -> () -> bitmap(si, inputs, literals, sink);
      default -> null;
    };
  }

  /** Whether a bitwise or shift expression's value is on a lane: an int or a bigint. */
  private static boolean onBitLane(Expression e) {
    return isInt(e) || isLong(e);
  }

  private static boolean isLong(Expression e) {
    return DataTypes.LongType.equals(e.dataType());
  }

  /** The operand on {@code e}'s own lane: the int operand rule, or a long-lane read. */
  private static Optional<VarkaVectorIR> laneOperand(
      Expression e, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    return isLong(e) ? longOperand(e, inputs, literals, sink)
        : intOperand(e, inputs, literals, sink);
  }

  /**
   * An operand on the long lane: a {@code bigint} column or literal, or a long-valued node. One
   * that compiles onto the int lane is a width conversion, which declines with its reason.
   */
  private static Optional<VarkaVectorIR> longOperand(
      Expression e, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    Optional<VarkaVectorIR> compiled = compileNode(e, inputs, literals, sink);
    if (compiled.isPresent() && compiled.get().laneType() != LaneType.LONG) {
      return sink.decline("a bigint operand computed on the int lane", e);
    }
    return compiled;
  }

  private static Optional<VarkaVectorIR> bitOp(
      BitOpKind op,
      Expression l,
      Expression r,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    Optional<VarkaVectorIR> x = laneOperand(l, inputs, literals, sink);
    if (x.isEmpty()) {
      return x;
    }
    return laneOperand(r, inputs, literals, sink).map(y -> new BitOp(op, x.get(), y));
  }

  /**
   * A shift: the value on its lane and the count, an {@code INT}, on the same one. At the int
   * lane the count is any int operand. At the long lane an int literal count is the same count
   * as a long literal, since the shift reads only its low six bits and Java widens the int count
   * by its sign; any other int count is widened to the long lane by sign extension, which keeps
   * the low six bits Java's {@code long << int} reads, so a count of -1 or 64 or
   * {@code Integer.MIN_VALUE} is answered as the row engine answers it.
   */
  private static Optional<VarkaVectorIR> shift(
      BitOpKind op,
      Expression value,
      Expression count,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    if (isInt(value)) {
      return bitOp(op, value, count, inputs, literals, sink);
    }
    Optional<VarkaVectorIR> x = longOperand(value, inputs, literals, sink);
    if (x.isEmpty()) {
      return x;
    }
    if (count instanceof Literal l && l.value() instanceof Integer n) {
      return Optional.of(new BitOp(op, x.get(), sink.longSlot(n.longValue())));
    }
    return intOperand(count, inputs, literals, sink)
        .map(c -> new BitOp(op, x.get(), new WidenLane(c)));
  }

  /** Whether {@code si} is the replacement of {@code bitmap_bucket_number} or its sibling. */
  static boolean isBitmap(StaticInvoke si) {
    return si.staticObject() == BitmapExpressionUtils.class && si.arguments().size() == 1
        && (si.functionName().equals("bitmapBucketNumber")
            || si.functionName().equals("bitmapBitPosition"));
  }

  /**
   * {@code bitmap_bucket_number} and {@code bitmap_bit_position}, from Spark's
   * {@code BitmapExpressionUtils}: for {@code v > 0} the bucket is {@code 1 + (v - 1) / N} and the
   * position {@code (v - 1) % N}, otherwise {@code v / N} and {@code (-v) % N}, with
   * {@code N = 2^15}. Both arms divide a non-negative magnitude {@code w}: {@code v - 1} when
   * {@code v > 0} and {@code -v} otherwise, which wraps only at {@code Long.MIN_VALUE}, where
   * {@code -v} is {@code 2^63} read unsigned. So the position is {@code w & (N - 1)} on both arms,
   * and with {@code q = w >>> 15} the bucket is {@code q + 1} or {@code -q}, Java's truncating
   * quotient of a non-positive {@code v}; {@code sql/varka/proofs/bitmap.smt2} proves both over
   * every long. One blend for {@code w} and one for the bucket, no division.
   */
  private static Optional<VarkaVectorIR> bitmap(
      StaticInvoke si, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    Expression arg = si.arguments().apply(0);
    if (!isLong(arg)) {
      return sink.decline("a bitmap function over a " + arg.dataType().simpleString(), si);
    }
    return longOperand(arg, inputs, literals, sink).map(v -> {
      var positive = new Compare(CompareOp.GT, v, sink.longSlot(0L));
      VarkaVectorIR w = new IfElse(positive,
          new IntArith(IntOp.SUB, Overflow.WRAP, v, sink.longSlot(1L)),
          new IntNeg(Overflow.WRAP, v));
      if (si.functionName().equals("bitmapBitPosition")) {
        return new BitOp(BitOpKind.AND, w, sink.longSlot((1L << BITMAP_SHIFT) - 1));
      }
      VarkaVectorIR q = new BitOp(BitOpKind.USHR, w, sink.longSlot(BITMAP_SHIFT));
      return new IfElse(positive, new IntArith(IntOp.ADD, Overflow.WRAP, q, sink.longSlot(1L)),
          new IntNeg(Overflow.WRAP, q));
    });
  }

  /** {@code CAST(bigint AS INT)} in its evaluation mode; see the arm in {@link #arithmeticArm}. */
  private static Optional<VarkaVectorIR> narrowing(
      Cast c,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    Overflow mode = overflowOf(c.evalMode());
    if (mode == Overflow.NULL) {
      return sink.decline("try_cast of a bigint to int, whose null where the value does not "
          + "fit is not fused (VARKA-332)", c);
    }
    return compileNode(c.child(), inputs, literals, sink).flatMap(x -> {
      if (x.laneType() != LaneType.LONG) {
        return sink.decline("a bigint operand that is not on the long lane", c);
      }
      VarkaVectorIR guarded = mode == Overflow.FAIL
          ? new GuardedRange(x, Integer.MIN_VALUE, Integer.MAX_VALUE) : x;
      return Optional.of((VarkaVectorIR) new NarrowLane(guarded));
    });
  }

  /** Microseconds in a day: a {@code DATE}'s unit in a {@code TIMESTAMP_NTZ}'s. */
  private static final long MICROS_PER_DAY = 86_400_000_000L;

  /**
   * The last day whose midnight is a long count of microseconds, and so the bound of the days
   * {@code CAST(d AS TIMESTAMP_NTZ)} converts without overflow: {@code Long.MAX_VALUE / 86.4e9}.
   */
  private static final long LAST_NTZ_DAY = Long.MAX_VALUE / MICROS_PER_DAY;

  /**
   * {@code CAST(d AS TIMESTAMP_NTZ)}: the day widened to the long lane and multiplied by the
   * microseconds of a day, which is Spark's {@code daysToMicros(d, UTC)}. Spark computes it with
   * {@code Math.multiplyExact} in every evaluation mode, so a day past {@link #LAST_NTZ_DAY}
   * raises under LEGACY and ANSI and is null under TRY ({@code try_cast} catches the throw), and
   * so does one before {@code -LAST_NTZ_DAY}. The long lane has no checked multiply, so the day is
   * guarded instead: a batch holding a live day outside the bound goes to the row engine, which
   * raises or nulls on that row, and inside it the wrapping multiply is exact. Under TRY that is a
   * correct answer computed slower.
   */
  private static Optional<VarkaVectorIR> ntzOfDate(
      Cast c,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    return compileNode(c.child(), inputs, literals, sink).flatMap(d -> {
      if (d.laneType() != LaneType.INT) {
        return sink.decline("a date operand that is not on the int lane", c);
      }
      VarkaVectorIR day = new GuardedRange(new WidenLane(d), -LAST_NTZ_DAY, LAST_NTZ_DAY);
      return Optional.of((VarkaVectorIR) new IntArith(IntOp.MUL, Overflow.WRAP, day,
          sink.longSlot(MICROS_PER_DAY)));
    });
  }

  /**
   * {@code CAST(ntz AS DATE)}: the UTC date of the microsecond count, Spark's
   * {@code microsToDays(t, UTC)}, which is {@code floorDiv(t, 86.4e9)} ({@link #floorDivLong}).
   * Every quotient is a day in {@code [-106,751,992, 106,751,991]}, so the narrowing to the int
   * lane is exact, and the cast cannot fail in any mode.
   */
  private static Optional<VarkaVectorIR> dateOfNtz(
      Cast c,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    return compileNode(c.child(), inputs, literals, sink).flatMap(t -> {
      if (t.laneType() != LaneType.LONG) {
        return sink.decline("a timestamp_ntz operand that is not on the long lane", c);
      }
      return Optional.of((VarkaVectorIR) new NarrowLane(floorDivLong(t, MICROS_PER_DAY, sink)));
    });
  }

  /**
   * {@code Math.floorDiv(x, divisor)} over every long {@code x}, for a positive even divisor: a
   * power of two is the shift alone, and any other needs its odd part to keep the shifted dividend
   * inside {@link ConstDivide}'s exact range (`VARKA-338.md`
   * 2.3, `long_divide.smt2`). The divisor is {@code 2^k * m} with {@code m} odd: an arithmetic
   * shift right by {@code k} is the floor division by {@code 2^k} and leaves a magnitude of at
   * most {@code 2^(63 - k)}; a negative value is then moved down by {@code m - 1}, so that the
   * truncating {@code ConstDivide} by {@code m} floors; and two floor divisions by positive
   * integers are one, by their product. {@code 86,400,000,000 = 2^13 * 10,546,875}, whose shifted
   * magnitude is under {@code 2^50 + 2^24}. An odd divisor is refused: unshifted, the dividend
   * is every long, past that range.
   */
  static VarkaVectorIR floorDivLong(VarkaVectorIR x, long divisor, DeclineSink sink) {
    if (divisor <= 0 || (divisor & 1) == 1) {
      throw VarkaErrors.illegalArgument("no exact floor division of a long by " + divisor
          + ": the divisor must be positive and even");
    }
    int k = Long.numberOfTrailingZeros(divisor);
    long odd = divisor >> k;
    VarkaVectorIR y = new BitOp(BitOpKind.SHR, x, sink.longSlot(k));
    if (odd == 1) {
      return y;
    }
    long bound = (1L << (63 - k)) + odd;
    if (bound > ConstDivide.EXACT_DIVIDEND_BOUND) {
      throw VarkaErrors.illegalArgument("no exact floor division of a long by " + divisor
          + ": its odd part leaves the shifted dividend past the exact range");
    }
    VarkaVectorIR down = new BitOp(BitOpKind.AND, new BitOp(BitOpKind.SHR, y, sink.longSlot(63)),
        sink.longSlot(1 - odd));
    return new ConstDivide(new IntArith(IntOp.ADD, Overflow.WRAP, y, down), odd, bound);
  }

  private static boolean isNtz(Expression e) {
    return DataTypes.TimestampNTZType.equals(e.dataType());
  }

  private static VarkaVectorIR greatest(VarkaVectorIR left, VarkaVectorIR right) {
    return new VarkaVectorIR.Greatest(left, right);
  }

  private static VarkaVectorIR least(VarkaVectorIR left, VarkaVectorIR right) {
    return new VarkaVectorIR.Least(left, right);
  }

  private static boolean isInt(Expression e) {
    return DataTypes.IntegerType.equals(e.dataType());
  }

  /**
   * Whether {@code e}'s type is one a narrow lane carries: a {@code TINYINT} or a
   * {@code SMALLINT}. Its values are ints in a narrow lane, so its arithmetic wraps at eight or
   * sixteen bits rather than at the int lane's thirty-two.
   */
  private static boolean isNarrow(Expression e) {
    return VarkaTypes.lane(e.dataType()).filter(LaneType::isNarrow).isPresent();
  }

  /**
   * What no arm claimed: a column of another type declines by its type, a
   * {@code RuntimeReplaceable} compiles what would run, and anything else declines as
   * unsupported.
   */
  private static Optional<VarkaVectorIR> fallback(
      Expression e,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    // A column no arm read: eligible to be forwarded as a whole entry. A type the registry holds
    // and no lane reads names its type and why - the row that builds an owed one, the decision
    // for a declined one (VARKA-494); any other column, an int one in a position that takes no
    // bare int among them, keeps the general reason.
    if (e instanceof BoundReference br) {
      return sink.decline(VarkaTypes.declineReason(br.dataType())
          .orElse("non-date column of type " + br.dataType().simpleString()), br);
    }
    // Defensive: a real query never carries an unreplaced RuntimeReplaceable this far (the
    // optimizer's ReplaceExpressions runs long before physical planning), but hand-built
    // expressions in tests and the plan-side fusion report can - compile what would run.
    if (e instanceof RuntimeReplaceable r) {
      return compileNode(r.replacement(), inputs, literals, sink);
    }
    return sink.decline("unsupported expression", e);
  }

  /**
   * Spark's evaluation mode ({@code EvalMode.Value}, typed {@code Object} because scaladoc, which
   * Maven runs over the Java sources, cannot resolve a type nested in a Scala object) as the IR
   * spells it.
   */
  static Overflow overflowOf(Object mode) {
    var modes = EvalMode$.MODULE$;
    if (modes.LEGACY().equals(mode)) {
      return Overflow.WRAP;
    }
    if (modes.ANSI().equals(mode)) {
      return Overflow.FAIL;
    }
    if (modes.TRY().equals(mode)) {
      return Overflow.NULL;
    }
    throw VarkaErrors.illegalArgument("unknown evaluation mode " + mode);
  }

  /**
   * An operand of int arithmetic: an {@code IntegerType} column becomes the int column leaf, an
   * int literal a slot, and everything else goes through {@link #compileNode} - which yields the
   * fused int fields ({@code datediff}, the extractions, the ISO weekday) and nested arithmetic.
   * A {@code DateType} operand is refused here rather than silently treated as a day count:
   * {@code date + 1} is {@code DateAdd} and has its own arm.
   */
  static Optional<VarkaVectorIR> intOperand(
      Expression e,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    return intOperand(e, () -> "int arithmetic operand of type " + e.dataType().simpleString(),
        inputs, literals, sink);
  }

  /**
   * An int operand of a node that is not a day: a foldable int literal as a slot, a bare
   * {@code IntegerType} column as a column ref, and any other {@code IntegerType} expression
   * through {@link #compileNode}, which is where the fused int fields and the arithmetic arms
   * live. So {@code make_date(y + 1, m, d)} fuses, and an operand of the wrong type still
   * declines here with {@code position} in the reason rather than reaching an arm that would read
   * it as an int. The date column leaf of {@link #compileNode} is {@code DateType}-only, which is
   * why the two leaves here cannot be left to it.
   */
  static Optional<VarkaVectorIR> compileIntOperand(
      Expression e,
      String position,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    return intOperand(e, () -> position + " is not an int column or literal", inputs, literals,
        sink);
  }

  /**
   * The rule both int operand forms share (VARKA-86): the int leaf, and any other
   * {@code IntegerType} expression through {@link #compileNode}; {@code notInt} is the position's
   * own reason for an operand of another type, built only when it declines.
   */
  private static Optional<VarkaVectorIR> intOperand(
      Expression e,
      Supplier<String> notInt,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    Optional<VarkaVectorIR> leaf = intLeaf(e, inputs, literals);
    if (leaf.isPresent()) {
      return leaf;
    }
    if (!DataTypes.IntegerType.equals(e.dataType())) {
      return sink.decline(notInt.get(), e);
    }
    return compileNode(e, inputs, literals, sink);
  }

  /**
   * The int leaf, the one rule every int operand position starts from: an int literal as a slot,
   * a bare {@code IntegerType} column as an int column ref; empty for anything else. It is not
   * {@link #compileNode}'s leaf, which is {@code DateType}: a bare int has no meaning as a date
   * operand, and widening that leaf would admit {@code date_add(d, i)}'s offset as a date.
   */
  static Optional<VarkaVectorIR> intLeaf(
      Expression e, VarkaSlotTable inputs, VarkaSlotTable literals) {
    if (e instanceof Literal l && l.value() instanceof Integer v
        && DataTypes.IntegerType.equals(l.dataType())) {
      return Optional.of(intSlot(v, literals));
    }
    if (e instanceof BoundReference br && DataTypes.IntegerType.equals(br.dataType())) {
      return Optional.of(columnRef(br, inputs));
    }
    return Optional.empty();
  }

  /**
   * {@code built} where {@code position} takes it, and otherwise a decline with {@code reason}:
   * the compiler's half of the rule the emitter's {@code Analysis.requireOperand} checks, so an
   * operand the emitter would refuse never leaves the compiler as fused (VARKA-86).
   */
  static Optional<VarkaVectorIR> admitted(
      VarkaOperandPosition position, VarkaVectorIR built, Expression e, String reason,
      DeclineSink sink) {
    return position.admits(built) ? Optional.of(built) : sink.decline(reason, e);
  }

  /**
   * The literal table as {@link VarkaRangeAnalysis} reads it: slot index to value. The table is
   * keyed by value in slot order, so a slot's value is its key's position; and it is read at call
   * time, never snapshotted, because the table grows as compilation proceeds and is truncated on
   * every decline.
   */
  static IntUnaryOperator literalAt(VarkaSlotTable literals) {
    return literals::keyAt;
  }

  /**
   * Whether {@code node}'s magnitude - how large an int-valued result can be in absolute value,
   * {@link VarkaRangeAnalysis}'s {@code INT} query - is known and at most {@code Int.MaxValue}.
   * This exists so a checked operation that provably cannot overflow needs no check - which is
   * what makes {@code year(d) * 100 + month(d)} fuse under ANSI, the shape {@code VARKA-63.md} 6
   * measures. The compiler can do this and the emitter cannot: a {@code LiteralSlot} carries a
   * slot index, and the value behind it only arrives in {@code scalarArgs} at run time.
   * Conservative by construction: a no costs a check or a decline and never a wrong answer. A yes
   * removes a check, so a date column the magnitude rests on - under a {@code datediff} - gets the
   * column bound noted in {@code sink}, which the entry's compile turns into a guard in the
   * kernel (VARKA-379).
   */
  static boolean boundedByIntMax(VarkaVectorIR node, VarkaSlotTable literals, DeclineSink sink) {
    var columns = new ArrayList<Integer>();
    OptionalLong m = VarkaRangeAnalysis.magnitude(node, literalAt(literals), columns::add);
    boolean bounded = m.isPresent() && m.getAsLong() <= Integer.MAX_VALUE;
    if (bounded) {
      columns.forEach(sink::dayColumnBound);
    }
    return bounded;
  }

  /**
   * Whether the operation on operands of these bounds cannot leave the int32 range. Read through
   * the analysis's own {@code IntArith} transfer function rather than re-dispatched here: the
   * candidate node is never emitted, so building one to ask the question is free, and the two
   * answers cannot drift apart the way two copies of "MUL multiplies, else adds" once could. A
   * magnitude is non-negative by construction, so the only thing left to ask is whether it stays
   * at or under {@code Int.MaxValue} - one past it, {@code 2^31}, is the first magnitude that
   * overflows.
   */
  private static boolean cannotOverflow(
      IntOp op, VarkaVectorIR l, VarkaVectorIR r, VarkaSlotTable literals, DeclineSink sink) {
    return boundedByIntMax(new IntArith(op, Overflow.WRAP, l, r), literals, sink);
  }

  /**
   * The shared body of the three binary arithmetic arms. A checked multiply declines unless the
   * operands' bounds prove it cannot overflow: the overflow test for {@code *} needs the 64-bit
   * product or a lane division, and the emitter has neither in int lanes, so an unprovable
   * {@code ANSI} or {@code TRY} multiply stays on the row engine until milestone 5's long lanes
   * arrive ({@code VARKA-63.md} 3.4).
   */
  private static Optional<VarkaVectorIR> intArith(
      IntOp op,
      Object mode,
      Expression l,
      Expression r,
      Expression whole,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    int mark = literals.size();
    Optional<VarkaVectorIR> x = intOperand(l, inputs, literals, sink);
    if (x.isEmpty()) {
      return x;
    }
    Optional<VarkaVectorIR> y = intOperand(r, inputs, literals, sink);
    if (y.isEmpty()) {
      return y;
    }
    return arithOver(op, overflowOf(mode), x.get(), y.get(), whole, literals, mark, sink);
  }

  /**
   * {@link #intArith} for the narrow lanes (VARKA-330): the operands are the node's own narrow
   * type, so a `TINYINT` add is a byte add. Reads them through {@link #compileNode}, which is
   * where the narrow leaves (a column, a literal) and nested narrow arithmetic live; an operand
   * on another lane declines with its lane in the reason rather than reaching a constructor that
   * would throw, since a hand-built tree can pair them.
   */
  private static Optional<VarkaVectorIR> narrowArith(
      IntOp op,
      Object mode,
      Expression l,
      Expression r,
      Expression whole,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    LaneType lane = VarkaTypes.lane(whole.dataType()).orElseThrow();
    int mark = literals.size();
    Optional<VarkaVectorIR> x = narrowOperand(l, lane, inputs, literals, sink);
    if (x.isEmpty()) {
      return x;
    }
    Optional<VarkaVectorIR> y = narrowOperand(r, lane, inputs, literals, sink);
    if (y.isEmpty()) {
      return y;
    }
    return arithOver(op, overflowOf(mode), x.get(), y.get(), whole, literals, mark, sink);
  }

  /**
   * An operand of narrow arithmetic: a value on {@code lane}, or empty with the reason if the
   * expression compiles to another lane's value. A {@code TINYINT} column, its literal and a
   * nested narrow operation all come back through {@link #compileNode}, which owns those arms.
   */
  private static Optional<VarkaVectorIR> narrowOperand(
      Expression e, LaneType lane, VarkaSlotTable inputs, VarkaSlotTable literals,
      DeclineSink sink) {
    Optional<VarkaVectorIR> ir = compileNode(e, inputs, literals, sink);
    if (ir.isPresent() && ir.get().laneType() != lane) {
      return sink.decline("a narrow arithmetic operand on the " + ir.get().laneType()
          + " lane, not the " + lane + " one", e);
    }
    return ir;
  }

  /**
   * The overflow decision, shared by the int arithmetic arms and the interval ones so that the
   * bound rule and the checked-multiply refusal are stated once rather than in two places that
   * can drift. A checked operation whose operands' bounds rule out overflow needs no check at all
   * and emits as {@code WRAP} - fewer ops, and the only way a checked multiply fuses; an int lane
   * has no cheap overflow test for {@code *}, so one that keeps its check declines and
   * {@code literals} is rolled back to {@code mark} so a declining entry leaves no slot behind.
   *
   * <p>A narrow node keeps the mode it was given ({@code VARKA-329.md} 2.1, review 9.1): the
   * proof {@link #cannotOverflow} runs asks the range of the candidate node, which is on the
   * operands' narrow lane, and the range analysis does not range narrow arithmetic, so the proof
   * fails and a checked narrow operation keeps its check. Its bound is the node lane's own, and it
   * is the emitter that applies it: the narrowed arm's range compare, or the native arm's
   * saturating difference ({@code VARKA-329.md} 4.2, 4.3). Were narrow arithmetic ranged, the int32
   * proof would hold for every narrow pair and turn `127Y + 1Y` under ANSI into a silent wrap;
   * {@code VarkaEmitterNarrowArithSuite} pins that it raises. A narrow multiply never declines for
   * overflow either, its product being exact at the int lane.
   */
  static Optional<VarkaVectorIR> arithOver(
      IntOp op,
      Overflow declared,
      VarkaVectorIR x,
      VarkaVectorIR y,
      Expression whole,
      VarkaSlotTable literals,
      int mark,
      DeclineSink sink) {
    if (x.laneType() != y.laneType()) {
      literals.truncate(mark);
      return sink.decline("arithmetic operands on the " + x.laneType() + " and the "
          + y.laneType() + " lanes", whole);
    }
    boolean narrow = x.laneType().isNarrow();
    Overflow overflow = declared != Overflow.WRAP && cannotOverflow(op, x, y, literals, sink)
        ? Overflow.WRAP
        : declared;
    if (op == IntOp.MUL && overflow != Overflow.WRAP && !narrow) {
      literals.truncate(mark);
      return sink.decline("checked int multiply whose operands do not rule out overflow", whole);
    }
    return Optional.of(new IntArith(op, overflow, x, y));
  }

  /**
   * Interns {@code value} into the per-distinct-value literal table and wraps it as a
   * {@code LiteralSlot} on the int lane. Every folded constant the compiler admits is an int - a
   * day count, a month count, a date's epoch day - so this is where a literal's lane is chosen,
   * as {@link #columnRef} is for a column's; {@link #slot} is its lane-taking form.
   */
  static LiteralSlot intSlot(int value, VarkaSlotTable literals) {
    return slot(value, LaneType.INT, literals);
  }

  /**
   * {@link #intSlot} on {@code lane}: a {@code tinyint} or {@code smallint} literal, whose value
   * fits an int and so shares the value table with the int literals of the same number. The slot
   * carries the lane because a comparison's two operands must agree on one, and the narrow
   * column's leaf is on the narrow lane.
   */
  static LiteralSlot slot(int value, LaneType lane, VarkaSlotTable literals) {
    return new LiteralSlot(literals.intern(value), lane);
  }

  /** The quiet NaN as a literal of a floating lane: its bits in the table of the lane's width. */
  static LiteralSlot nanSlot(LaneType lane, VarkaSlotTable literals, DeclineSink sink) {
    return switch (lane) {
      case FLOAT -> slot(Float.floatToRawIntBits(Float.NaN), LaneType.FLOAT, literals);
      case DOUBLE -> doubleSlot(Double.NaN, sink);
      case BYTE, SHORT, INT, LONG -> throw VarkaErrors.illegalArgument(
          "no NaN on the integral lane " + lane);
    };
  }

  /** The literal slot of the double {@code v}, its raw bits on the {@code DOUBLE} lane. */
  static LiteralSlot doubleSlot(double v, DeclineSink sink) {
    return sink.longSlot(Double.doubleToRawLongBits(v), LaneType.DOUBLE);
  }

  /**
   * Interns {@code br}'s ordinal into {@code inputs} and wraps it as a {@code ColumnRef} on the
   * int lane; shared by the date, interval and long leaves and the offset's {@code IntegerType}
   * one.
   */
  static ColumnRef columnRef(BoundReference br, VarkaSlotTable inputs) {
    return columnRef(br, inputs, LaneType.INT);
  }

  /** {@link #columnRef(BoundReference, VarkaSlotTable)} on {@code lane}. */
  static ColumnRef columnRef(
      BoundReference br, VarkaSlotTable inputs, LaneType lane) {
    return new ColumnRef(inputs.intern(br.ordinal()), lane);
  }

  /**
   * {@code columnRef}'s twin for an input the evaluator derives from {@code br}: interned under
   * {@code VarkaDerivedInput.key} beside the child ordinals, so it takes the next kernel input
   * index and shares the table's rollback.
   */
  static ColumnRef derivedRef(
      BoundReference br, VarkaDerivedKind kind, VarkaSlotTable inputs) {
    return new ColumnRef(inputs.intern(VarkaDerivedInput.key(br.ordinal(), kind)), LaneType.INT);
  }
}
