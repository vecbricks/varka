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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.TreeSet;
import java.util.function.BinaryOperator;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import scala.Tuple2;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.And;
import org.apache.spark.sql.catalyst.expressions.BoundReference;
import org.apache.spark.sql.catalyst.expressions.CaseWhen;
import org.apache.spark.sql.catalyst.expressions.Coalesce;
import org.apache.spark.sql.catalyst.expressions.EqualTo;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.GreaterThan;
import org.apache.spark.sql.catalyst.expressions.GreaterThanOrEqual;
import org.apache.spark.sql.catalyst.expressions.If;
import org.apache.spark.sql.catalyst.expressions.In;
import org.apache.spark.sql.catalyst.expressions.InSet;
import org.apache.spark.sql.catalyst.expressions.IsNotNull;
import org.apache.spark.sql.catalyst.expressions.IsNull;
import org.apache.spark.sql.catalyst.expressions.LessThan;
import org.apache.spark.sql.catalyst.expressions.LessThanOrEqual;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.Not;
import org.apache.spark.sql.catalyst.expressions.Or;
import org.apache.spark.sql.catalyst.expressions.RuntimeReplaceable;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaErrors;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFamily;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.InRanges;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.YearMonthIntervalType;

/**
 * Two families of the compiler, each with its own switch (VARKA-318). The predicate family is the
 * three-valued conditions - comparisons, {@code IN} over date literals, the validity predicates
 * and the connectives - compiled by {@link #compileCond} for a filter's mask root and for the
 * conditions of the value-side conditionals. The condition family is those conditionals,
 * {@code IF}, {@code CASE WHEN} and {@code coalesce}, which fold onto {@code IfElse} over the
 * predicate family's conditions.
 *
 * <p>{@link #arm} is the condition family's one entry from the chain
 * {@code VarkaExpressionCompiler} dispatches through, in the form {@code VarkaIntervalCompiler}
 * set: a {@code switch} that tests and deconstructs a node and returns the lowering as a deferred
 * call, or {@code null} for a node the family does not claim; {@link #condArm} is the predicate
 * family's claim in the same form. {@code compilePredicate} reaches {@link #compileCond} and the
 * balanced {@link #andFold} directly.
 *
 * <p>The literal and input tables are the classifier's {@link VarkaSlotTable}s.
 */
final class VarkaConditionCompiler {


  private VarkaConditionCompiler() {
  }

  /**
   * The arm that claims {@code e}, or {@code null}: the conditional arms of {@code compileNode},
   * {@code IF}, {@code CASE WHEN} and {@code coalesce}, in their original order.
   */
  static VarkaFamilyArm arm(
      Expression e,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    return switch (e) {
      case If expr -> () -> ifElse(expr, inputs, literals, sink);
      // With no ELSE the missing branch is a null literal, which would break the dense body's
      // all-valid invariant (`VARKA-11.md` 2.1): decline.
      case CaseWhen c when c.elseValue().isEmpty() ->
          () -> sink.decline("CASE WHEN without an ELSE branch", c);
      // CASE WHEN with an ELSE right-folds into nested IfElse - SQL's first-match semantics is
      // exactly nested if-else. Compilation runs in query order (branches left to right, then
      // the ELSE) so input ordinals and literal slots register deterministically in reading
      // order; only the fold is right-associative.
      case CaseWhen expr -> () -> caseWhen(expr, inputs, literals, sink);
      // Coalesce right-folds onto the validity condition: `coalesce(a, b)` is
      // `IfElse(IsNotNull(a), a, b)`, whose masked validity - (kT & valid(a)) | (~kT & valid(b))
      // with kT = valid(a) - reduces to valid(a) | valid(b), exactly SQL's coalesce. Every
      // operand before the last must be a bare date column (the IsNotNull child restriction);
      // `nvl`/`ifnull` arrive here already rewritten to Coalesce by the optimizer, and `nvl2`
      // arrives as `If(IsNotNull(...), ...)` and rides the same condition node.
      case Coalesce c when c.children().nonEmpty() ->
          () -> compileCoalesce(CollectionConverters.asJava(c.children()), 0, inputs, literals,
              sink);
      default -> null;
    };
  }

  /**
   * The lowering of {@code IF}: the condition, the two branches and, only then, whether they
   * share a lane.
   */
  private static Optional<VarkaVectorIR> ifElse(
      If expr,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    Optional<Cond> cond = compileCond(expr.predicate(), inputs, literals, sink);
    if (cond.isEmpty()) {
      return Optional.empty();
    }
    Optional<VarkaVectorIR> thenNode = VarkaNodeCompiler.compileNode(
        expr.trueValue(), inputs, literals, sink);
    if (thenNode.isEmpty()) {
      return Optional.empty();
    }
    Optional<VarkaVectorIR> elseNode = VarkaNodeCompiler.compileNode(
        expr.falseValue(), inputs, literals, sink);
    if (elseNode.isEmpty()) {
      return Optional.empty();
    }
    if (!sameLane(expr, sink, cond.get(), thenNode.get(), elseNode.get())) {
      return Optional.empty();
    }
    return Optional.of(new IfElse(cond.get(), thenNode.get(), elseNode.get()));
  }

  /**
   * The lowering of {@code CASE WHEN} with an ELSE. Every branch is compiled, in order, even
   * after one declines, as the entry's input and literal tables are rolled back by the caller;
   * the lanes are then checked, and the fold runs from the last branch.
   */
  private static Optional<VarkaVectorIR> caseWhen(
      CaseWhen expr,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    List<Tuple2<Expression, Expression>> branches = CollectionConverters.asJava(expr.branches());
    List<Optional<Cond>> conds = new ArrayList<>();
    List<Optional<VarkaVectorIR>> values = new ArrayList<>();
    for (Tuple2<Expression, Expression> branch : branches) {
      conds.add(compileCond(branch._1(), inputs, literals, sink));
      values.add(VarkaNodeCompiler.compileNode(branch._2(), inputs, literals, sink));
    }
    Optional<VarkaVectorIR> compiledElse =
        VarkaNodeCompiler.compileNode(expr.elseValue().get(), inputs, literals, sink);
    boolean all = compiledElse.isPresent();
    for (int i = 0; i < branches.size(); i++) {
      all &= conds.get(i).isPresent() && values.get(i).isPresent();
    }
    if (!all) {
      return Optional.empty();
    }
    List<VarkaVectorIR> nodes = new ArrayList<>();
    for (int i = 0; i < branches.size(); i++) {
      nodes.add(conds.get(i).get());
      nodes.add(values.get(i).get());
    }
    nodes.add(compiledElse.get());
    if (!sameLane(expr, sink, nodes.toArray(new VarkaVectorIR[0]))) {
      return Optional.empty();
    }
    VarkaVectorIR rest = compiledElse.get();
    for (int i = branches.size() - 1; i >= 0; i--) {
      rest = new IfElse(conds.get(i).get(), values.get(i).get(), rest);
    }
    return Optional.of(rest);
  }

  /**
   * Whether {@code nodes} share a lane, noting the mismatch against {@code whole} when they do
   * not. Every IR node whose operands may disagree - the connectives, the blend - refuses a mix in
   * its constructor, so this is asked first wherever Spark's typing does not already force the
   * agreement: {@code CASE WHEN l > 0 THEN d ELSE d2} type-checks, and its condition is on the
   * long lane while its branches are on the int one.
   */
  private static boolean sameLane(Expression whole, DeclineSink sink, VarkaVectorIR... nodes) {
    List<LaneType> lanes = new ArrayList<>();
    for (VarkaVectorIR node : nodes) {
      if (!lanes.contains(node.laneType())) {
        lanes.add(node.laneType());
      }
    }
    if (lanes.size() <= 1) {
      return true;
    }
    return notMixed(whole, sink, lanes.get(0), lanes.get(1));
  }

  /** Notes that {@code whole} mixes two lanes in one kernel, and returns false. */
  private static boolean notMixed(Expression whole, DeclineSink sink, LaneType a, LaneType b) {
    sink.note("one kernel holds one lane, and this mixes the " + a + " and " + b + " lanes",
        whole);
    return false;
  }

  /**
   * A connective's two operands on one lane: as they are where they agree, both on the long lane
   * where they do not and each has a form there ({@link #onLongLane}, VARKA-28), and otherwise
   * empty with the lane's reason noted.
   */
  private static Optional<List<Cond>> meetOnOneLane(
      Expression whole, DeclineSink sink, Cond a, Cond b) {
    if (a.laneType() != b.laneType()) {
      Optional<Cond> la = onLongLane(a);
      Optional<Cond> lb = onLongLane(b);
      if (la.isPresent() && lb.isPresent()) {
        return Optional.of(List.of(la.get(), lb.get()));
      }
    }
    return sameLane(whole, sink, a, b) ? Optional.of(List.of(a, b)) : Optional.empty();
  }

  /** {@link #sameLane(Expression, DeclineSink, VarkaVectorIR...)} for the two a connective has. */
  private static boolean sameLane(
      Expression whole, DeclineSink sink, VarkaVectorIR a, VarkaVectorIR b) {
    return a.laneType() == b.laneType() || notMixed(whole, sink, a.laneType(), b.laneType());
  }

  /**
   * {@code cond} on the long lane, or empty where it has no form there (VARKA-28): every int
   * comparison compares its operands widened, which keeps every answer since a sign extension
   * keeps every value and its order, and an int {@code IS NOT NULL} reads its column through a
   * widening, whose validity is the column's. A range set has no long-lane form. A long-lane
   * condition is itself. This is how an int conjunct joins a filter whose other conjuncts compare
   * on the long lane, as the {@code isnotnull(i)} the optimizer infers beside {@code i < l}.
   */
  static Optional<Cond> onLongLane(Cond cond) {
    if (cond.laneType() == VarkaVectorIR.LaneType.LONG) {
      return Optional.of(cond);
    }
    return switch (cond) {
      case Compare c -> Optional.of(new Compare(c.op(), new VarkaVectorIR.WidenLane(c.left()),
          new VarkaVectorIR.WidenLane(c.right())));
      case VarkaVectorIR.IsNotNull n ->
          Optional.of(new VarkaVectorIR.IsNotNull(new VarkaVectorIR.WidenLane(n.child())));
      case VarkaVectorIR.And a -> onLongLane(a.left()).flatMap(l ->
          onLongLane(a.right()).map(r -> (Cond) new VarkaVectorIR.And(l, r)));
      case VarkaVectorIR.Or o -> onLongLane(o.left()).flatMap(l ->
          onLongLane(o.right()).map(r -> (Cond) new VarkaVectorIR.Or(l, r)));
      case VarkaVectorIR.Not n -> onLongLane(n.child()).map(c -> (Cond) new VarkaVectorIR.Not(c));
      case InRanges r -> Optional.empty();
    };
  }

  /**
   * Folds the fused conjuncts back into one root, <b>balanced</b> like {@link #orFold} and for
   * the same reason: Kleene AND is associative, so the shape is a canonicalization, and a left
   * fold would grow the chain depth by one per conjunct - a WHERE of 16 fusible conjuncts would
   * trip {@code MAX_CHAIN_DEPTH} for no semantic reason, where the balanced fold stays
   * logarithmic.
   */
  static Cond andFold(List<Cond> conds) {
    return balancedFold(new ArrayList<>(conds), VarkaVectorIR.And::new);
  }

  /**
   * The disjunction of {@code conds} as a balanced tree, as {@link #andFold} is of a
   * conjunction; the partial roots of a split predicate are folded with it.
   */
  static Cond orFold(List<Cond> conds) {
    return balancedOr(new ArrayList<>(conds));
  }

  /**
   * The Coalesce right-fold from operand {@code from}. Every operand except the last compiles and
   * must be a bare date column: {@code IsNotNull} reads the per-input validity word, which only a
   * column has before value emission (the recorded milestone-3 restriction) - a computed operand
   * declines with its own reason. The {@code ColumnRef} match below is a proxy for "this operand
   * is a bare column" that is exact only because every {@code compileNode} arm producing a
   * {@code ColumnRef} today is either an actual column read or a null-intolerant identity relabel
   * ({@code unix_date}/{@code date_from_unix_date} and the identity date {@code Cast}) - a future
   * relabel that changes nullability or value would silently break this guard.
   */
  private static Optional<VarkaVectorIR> compileCoalesce(
      List<Expression> children,
      int from,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    if (from == children.size() - 1) {
      return VarkaNodeCompiler.compileNode(children.get(from), inputs, literals, sink);
    }
    Expression head = children.get(from);
    Optional<VarkaVectorIR> compiled = VarkaNodeCompiler.compileNode(head, inputs, literals, sink);
    if (compiled.isEmpty()) {
      return compiled;
    }
    if (compiled.get() instanceof ColumnRef ref) {
      Optional<VarkaVectorIR> rest = compileCoalesce(children, from + 1, inputs, literals, sink);
      if (rest.isEmpty()) {
        return rest;
      }
      return Optional.of(new IfElse(new VarkaVectorIR.IsNotNull(ref), ref, rest.get()));
    }
    return sink.decline("coalesce operand before the last is not a bare date column", head);
  }

  /** {@code greatest} and {@code least}: every operand compiled, then folded from the left. */
  static Optional<VarkaVectorIR> foldPick(
      scala.collection.immutable.Seq<Expression> children,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink,
      BinaryOperator<VarkaVectorIR> combine) {
    // Every operand is compiled, in order, even after one declines: the tables are rolled back by
    // the caller, and a declined entry leaves them as it found them.
    List<VarkaVectorIR> compiled = new ArrayList<>();
    boolean all = true;
    for (Expression child : CollectionConverters.asJava(children)) {
      Optional<VarkaVectorIR> node = VarkaNodeCompiler.compileNode(child, inputs, literals, sink);
      if (node.isPresent()) {
        compiled.add(node.get());
      } else {
        all = false;
      }
    }
    if (!all || compiled.isEmpty()) {
      return Optional.empty();
    }
    VarkaVectorIR folded = compiled.get(0);
    for (int i = 1; i < compiled.size(); i++) {
      folded = combine.apply(folded, compiled.get(i));
    }
    return Optional.of(folded);
  }

  /**
   * The condition compiler: interior comparisons and the connectives, three-valued at run time
   * via the emitter's known-true/known-false pairs. {@code EqualNullSafe} deliberately declines -
   * its both-null-is-true case breaks the null-intolerant comparison rule and earns its own
   * algebra entry or nothing (plan section 4).
   *
   * <p>Every node {@link #condArm} claims is the predicate family's and lowers under its switch
   * (VARKA-318), wherever it is reached: a filter's conjunct, the condition of an {@code IF} or a
   * {@code CASE WHEN} branch, or an operand of a connective, since the connectives recurse
   * through here. A node it does not claim declines as unsupported whatever the switch.
   */
  static Optional<Cond> compileCond(
      Expression expr,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    // Defensive, mirroring compileNode: hand-built Nvl/Nvl2 in tests and the fusion report
    // arrive unreplaced; real queries never do.
    if (expr instanceof RuntimeReplaceable r) {
      return compileCond(r.replacement(), inputs, literals, sink);
    }
    Supplier<Optional<Cond>> arm;
    try {
      arm = condArm(expr, inputs, literals, sink);
    } catch (RuntimeException e) {
      // The claim's guards are the family's code too, as in compileNode: a throw there names it.
      throw new VarkaNodeCompiler.FamilyFailure(VarkaFamily.PREDICATE, e);
    }
    if (arm == null) {
      return sink.decline("unsupported predicate", expr);
    }
    return VarkaNodeCompiler.inFamily(VarkaFamily.PREDICATE, arm, expr, sink);
  }

  /**
   * The lowering {@link #compileCond} runs for {@code expr}, deferred, or {@code null} for a node
   * the predicate family does not claim: the claim of the family, in the form of the chain's
   * arms, which {@code VarkaNodeCompiler.predicates} hands to the suites that check no node is
   * claimed twice and that each switch declines exactly its nodes. The classes are disjoint, so
   * the order only matters where a guard falls through.
   */
  static @Nullable Supplier<Optional<Cond>> condArm(
      Expression expr,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    return switch (expr) {
      case LessThan n -> () -> compare(CompareOp.LT, n.left(), n.right(), inputs, literals, sink);
      case LessThanOrEqual n ->
          () -> compare(CompareOp.LE, n.left(), n.right(), inputs, literals, sink);
      case GreaterThan n ->
          () -> compare(CompareOp.GT, n.left(), n.right(), inputs, literals, sink);
      case GreaterThanOrEqual n ->
          () -> compare(CompareOp.GE, n.left(), n.right(), inputs, literals, sink);
      case EqualTo n -> () -> compare(CompareOp.EQ, n.left(), n.right(), inputs, literals, sink);
      // IN over date literals: an EQ chain joined by OR, which the mask algebra
      // makes exactly SQL's IN inside a condition - a null value leaves every comparison
      // unknown, the OR of unknowns is unknown, and an unknown condition falls to ELSE.
      case In in when isDateOrInterval(in.value().dataType()) -> () -> {
        List<OptionalInt> elements = new ArrayList<>();
        for (Expression element : CollectionConverters.asJava(in.list())) {
          elements.add(literalDays(element));
        }
        return compileInList(in.value(), elements, in, inputs, literals, sink);
      };
      case InSet inSet when isDateOrInterval(inSet.child().dataType()) -> () -> {
        // InSet's set is unordered; compileInList sorts, which is what keeps the literal
        // slots and the shape hash deterministic across runs.
        List<OptionalInt> elements = new ArrayList<>();
        for (Object element : CollectionConverters.asJava(inSet.hset())) {
          elements.add(
              element instanceof Integer days ? OptionalInt.of(days) : OptionalInt.empty());
        }
        return compileInList(inSet.child(), elements, inSet, inputs, literals, sink);
      };
      case And and -> () -> {
        Optional<Cond> left = compileCond(and.left(), inputs, literals, sink);
        if (left.isEmpty()) {
          return left;
        }
        Optional<Cond> right = compileCond(and.right(), inputs, literals, sink);
        if (right.isEmpty()) {
          return right;
        }
        return meetOnOneLane(expr, sink, left.get(), right.get())
            .map(both -> new VarkaVectorIR.And(both.get(0), both.get(1)));
      };
      case Or or -> () -> {
        // A disjunction of ranges over one int or date column - the partition-key filter a BI
        // tool writes for a set of date ranges - is one range set, whose code does not grow with
        // the ranges, instead of a tree of comparisons whose code does.
        if (sink.rangeSets()) {
          Optional<RangeSet> set = rangeSet(or);
          if (set.isPresent()) {
            return Optional.of(new InRanges(
                VarkaNodeCompiler.columnRef(set.get().column(), inputs, LaneType.INT),
                set.get().bounds()));
          }
        }
        Optional<Cond> left = compileCond(or.left(), inputs, literals, sink);
        if (left.isEmpty()) {
          return left;
        }
        Optional<Cond> right = compileCond(or.right(), inputs, literals, sink);
        if (right.isEmpty()) {
          return right;
        }
        return meetOnOneLane(expr, sink, left.get(), right.get())
            .map(both -> new VarkaVectorIR.Or(both.get(0), both.get(1)));
      };
      case Not not ->
          () -> compileCond(not.child(), inputs, literals, sink).map(v -> new VarkaVectorIR.Not(v));
      // The validity predicates: IS NOT NULL is the IR's first total condition
      // (never unknown), and IS NULL is its NOT - a slot swap in the emitter, no code.
      case IsNotNull n -> () -> compileValidity(n.child(), expr, inputs, literals, sink);
      case IsNull n -> () -> compileValidity(n.child(), expr, inputs, literals, sink)
          .map(v -> new VarkaVectorIR.Not(v));
      default -> null;
    };
  }

  private static boolean isDateOrInterval(DataType dataType) {
    return dataType.equals(DataTypes.DateType) || dataType instanceof YearMonthIntervalType;
  }

  /**
   * The int a date or year-month interval literal holds - epoch days for one, a month count for
   * the other - or empty for anything else (null included). Both are int32 lanes and an
   * {@code IN} list is type-homogeneous, so one function serves both; the type gate is on the
   * {@code In} arms, which is where the value's own type decides.
   */
  private static OptionalInt literalDays(Expression e) {
    if (e instanceof Literal l && l.value() instanceof Integer days
        && (l.dataType().equals(DataTypes.DateType)
            || l.dataType() instanceof YearMonthIntervalType)) {
      return OptionalInt.of(days);
    }
    return OptionalInt.empty();
  }

  /**
   * Compiles an IN list: dedup and sort the literal days - Kleene OR is commutative and EQ is
   * pure, so the order is free, and a canonical order keeps the literal slots and the shape hash
   * deterministic ({@code InSet} hands the values over as an unordered set) - then a
   * <b>balanced</b> pairwise fold of OR over the EQ leaves. The fold shape is part of the cap
   * arithmetic: balanced, {@code MaxInLiterals} literals are {@code ceil(log2 n) + 1} levels and
   * {@code 2n - 1} op nodes; a right-nested fold would hit the emitter's depth cap at 15. Above
   * the cap, or with any non-literal or null element, the entry declines with its reason.
   */
  private static Optional<Cond> compileInList(
      Expression value,
      List<OptionalInt> elements,
      Expression whole,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    TreeSet<Integer> days = new TreeSet<>();
    for (OptionalInt element : elements) {
      if (element.isEmpty()) {
        sink.note("IN list has a null or non-literal element", whole);
        return Optional.empty();
      }
      days.add(element.getAsInt());
    }
    if (days.isEmpty()) {
      sink.note("IN list has a null or non-literal element", whole);
      return Optional.empty();
    }
    if (days.size() > VarkaNodeCompiler.MAX_IN_LITERALS) {
      sink.note("IN list longer than the fused cap of " + VarkaNodeCompiler.MAX_IN_LITERALS, whole);
      return Optional.empty();
    }
    Optional<VarkaVectorIR> compiledValue = VarkaNodeCompiler.compileNode(
        value, inputs, literals, sink);
    if (compiledValue.isEmpty()) {
      return Optional.empty();
    }
    List<Cond> leaves = new ArrayList<>();
    for (int d : days) {
      leaves.add(new Compare(
          CompareOp.EQ, compiledValue.get(), VarkaNodeCompiler.intSlot(d, literals)));
    }
    return Optional.of(balancedOr(leaves));
  }

  /** Pairwise-reduces conditions into a balanced OR tree; the base of the cap arithmetic. */
  private static Cond balancedOr(List<Cond> level) {
    return balancedFold(level, VarkaVectorIR.Or::new);
  }

  /**
   * Pairwise-reduces conditions into a balanced tree of {@code combine} - the shared shape behind
   * {@link #balancedOr} and the predicate's {@link #andFold}.
   */
  private static Cond balancedFold(List<Cond> conds, BinaryOperator<Cond> combine) {
    if (conds.isEmpty()) {
      throw VarkaErrors.illegalArgument(
          "requirement failed: balancedFold needs at least one condition");
    }
    List<Cond> level = conds;
    while (level.size() > 1) {
      List<Cond> next = new ArrayList<>();
      for (int i = 0; i < level.size(); i += 2) {
        next.add(i + 1 < level.size() ? combine.apply(level.get(i), level.get(i + 1))
            : level.get(i));
      }
      level = next;
    }
    return level.get(0);
  }

  /**
   * Compiles the operand of a validity predicate, which must land on a bare column: the emitter
   * reads the column's per-lane-group validity word, and only a column's word is live before
   * value emission (the recorded milestone-3 restriction). As in {@code compileCoalesce} above,
   * the {@code ColumnRef} match is a proxy for "bare column" that depends on every relabel
   * expression compiling to {@code ColumnRef} staying a null-intolerant identity.
   *
   * <p>The column may be a date or an {@code IntegerType} one. Both are the same int32 lane and
   * the same validity word, and the int case is not optional: Spark's optimizer infers
   * {@code isnotnull(i)} beside any null-intolerant predicate on {@code i}, so refusing it would
   * leave a residual row filter above every fused int comparison (VARKA-122) - the kernel would do
   * the comparison and the row engine would still visit every row to check the null.
   */
  private static Optional<Cond> compileValidity(
      Expression child,
      Expression whole,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    Optional<VarkaVectorIR> compiled;
    if (child instanceof BoundReference br && br.dataType().equals(DataTypes.IntegerType)) {
      compiled = Optional.of(VarkaNodeCompiler.columnRef(br, inputs, LaneType.INT));
    } else {
      compiled = VarkaNodeCompiler.compileNode(child, inputs, literals, sink);
    }
    if (compiled.isEmpty()) {
      return Optional.empty();
    }
    if (compiled.get() instanceof ColumnRef ref) {
      return Optional.of(new VarkaVectorIR.IsNotNull(ref));
    }
    return sink.decline("validity predicate over a non-column operand", whole);
  }

  /** The column and the sorted, merged range ends of a disjunction that is a range set. */
  record RangeSet(BoundReference column, List<Integer> bounds) {
  }

  /** One range over a column, inclusive at both ends. */
  private record Range(BoundReference column, long lo, long hi) {
  }

  /** One side of a range: the column, and a lower or an upper bound as an inclusive long. */
  private record Side(BoundReference column, OptionalLong lower, OptionalLong upper) {
  }

  /**
   * {@code or} as a range set, if it is one: two or more disjuncts, each a range over the same int
   * or date column with literal bounds - {@code c >= a and c <= b} in either order and either
   * operand order, {@code c = a}, or a strict bound, which moves by one - as the column and the
   * ranges' bounds, sorted by lower bound and merged where they overlap or touch. Empty for
   * anything else, which then compiles as the comparisons it is written as. A disjunct whose range
   * is empty ({@code c > a and c < a + 1}) selects nothing and is dropped; if all are, it is not a
   * set.
   */
  static Optional<RangeSet> rangeSet(Or or) {
    List<Expression> parts = new ArrayList<>();
    disjuncts(or, parts);
    List<Range> ranges = new ArrayList<>();
    for (Expression part : parts) {
      range(part).ifPresent(ranges::add);
    }
    if (parts.size() < 2 || ranges.size() != parts.size() || !oneColumn(ranges)) {
      return Optional.empty();
    }
    List<Range> nonEmpty = new ArrayList<>(ranges.size());
    for (Range r : ranges) {
      if (r.lo() <= r.hi()) {
        nonEmpty.add(r);
      }
    }
    // A stable sort by lower bound, as the merge below needs.
    nonEmpty.sort(Comparator.comparingLong(Range::lo));
    List<long[]> merged = new ArrayList<>();
    for (Range r : nonEmpty) {
      long[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
      if (last != null && r.lo() <= last[1] + 1) {
        last[1] = Math.max(last[1], r.hi());
      } else {
        merged.add(new long[] {r.lo(), r.hi()});
      }
    }
    if (merged.isEmpty()) {
      return Optional.empty();
    }
    // The strict bounds moved by one in longs; clamp back into the int range, which a moved
    // bound can only have left by stepping past an int extreme that no int value is beyond.
    List<Integer> bounds = new ArrayList<>();
    for (long[] range : merged) {
      bounds.add(clamp(range[0]));
      bounds.add(clamp(range[1]));
    }
    return Optional.of(new RangeSet(ranges.get(0).column(), List.copyOf(bounds)));
  }

  private static int clamp(long v) {
    return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
  }

  /** Whether every range is over the same (ordinal, data type) column as the first. */
  private static boolean oneColumn(List<Range> ranges) {
    BoundReference first = ranges.get(0).column();
    for (Range r : ranges) {
      if (r.column().ordinal() != first.ordinal()
          || !r.column().dataType().equals(first.dataType())) {
        return false;
      }
    }
    return true;
  }

  private static void disjuncts(Expression e, List<Expression> out) {
    if (e instanceof Or or) {
      disjuncts(or.left(), out);
      disjuncts(or.right(), out);
    } else {
      out.add(e);
    }
  }

  private static Optional<BoundReference> column(Expression e) {
    if (e instanceof BoundReference br
        && (br.dataType().equals(DataTypes.IntegerType)
            || br.dataType().equals(DataTypes.DateType))) {
      return Optional.of(br);
    }
    return Optional.empty();
  }

  private static OptionalLong bound(Expression e, BoundReference of) {
    if (e instanceof Literal l && l.value() instanceof Integer v
        && l.dataType().equals(of.dataType())) {
      return OptionalLong.of(v.longValue());
    }
    return OptionalLong.empty();
  }

  /**
   * One side of a range. Which bound it is depends on the operator and on which operand is the
   * column: {@code c >= a} is a lower bound, {@code a >= c} an upper one.
   */
  private static Optional<Side> side(Expression e) {
    return switch (e) {
      case GreaterThanOrEqual n -> oriented(n.left(), n.right(), true, false);
      case GreaterThan n -> oriented(n.left(), n.right(), true, true);
      case LessThanOrEqual n -> oriented(n.left(), n.right(), false, false);
      case LessThan n -> oriented(n.left(), n.right(), false, true);
      default -> Optional.empty();
    };
  }

  /**
   * {@code columnIsLower}: whether the operator bounds the column from below when the column is
   * its left operand.
   */
  private static Optional<Side> oriented(
      Expression l, Expression r, boolean columnIsLower, boolean strict) {
    long step = strict ? 1L : 0L;
    Optional<BoundReference> left = column(l);
    if (left.isPresent()) {
      OptionalLong v = bound(r, left.get());
      if (v.isPresent()) {
        return Optional.of(sideOf(left.get(), v.getAsLong(), columnIsLower, step));
      }
    }
    Optional<BoundReference> right = column(r);
    if (right.isPresent()) {
      OptionalLong v = bound(l, right.get());
      if (v.isPresent()) {
        return Optional.of(sideOf(right.get(), v.getAsLong(), !columnIsLower, step));
      }
    }
    return Optional.empty();
  }

  private static Side sideOf(BoundReference col, long v, boolean lower, long step) {
    return lower
        ? new Side(col, OptionalLong.of(v + step), OptionalLong.empty())
        : new Side(col, OptionalLong.empty(), OptionalLong.of(v - step));
  }

  private static Optional<Range> range(Expression e) {
    if (e instanceof EqualTo eq) {
      Optional<BoundReference> left = column(eq.left());
      if (left.isPresent() && bound(eq.right(), left.get()).isPresent()) {
        long v = bound(eq.right(), left.get()).getAsLong();
        return Optional.of(new Range(left.get(), v, v));
      }
      Optional<BoundReference> right = column(eq.right());
      if (right.isPresent() && bound(eq.left(), right.get()).isPresent()) {
        long v = bound(eq.left(), right.get()).getAsLong();
        return Optional.of(new Range(right.get(), v, v));
      }
      return Optional.empty();
    }
    if (e instanceof And and) {
      Optional<Side> l = side(and.left());
      Optional<Side> r = side(and.right());
      if (l.isEmpty() || r.isEmpty()) {
        return Optional.empty();
      }
      Side a = l.get();
      Side b = r.get();
      if (a.column().ordinal() != b.column().ordinal()
          || !a.column().dataType().equals(b.column().dataType())) {
        return Optional.empty();
      }
      OptionalLong lo = a.lower().isPresent() ? a.lower() : b.lower();
      OptionalLong hi = a.upper().isPresent() ? a.upper() : b.upper();
      if (lo.isEmpty() || hi.isEmpty() || a.lower().isPresent() == b.lower().isPresent()) {
        return Optional.empty();
      }
      return Optional.of(new Range(a.column(), lo.getAsLong(), hi.getAsLong()));
    }
    return Optional.empty();
  }

  private static Optional<Cond> compare(
      CompareOp op,
      Expression l,
      Expression r,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    Optional<VarkaVectorIR> left = operand(l, inputs, literals, sink);
    if (left.isEmpty()) {
      return Optional.empty();
    }
    Optional<VarkaVectorIR> right = operand(r, inputs, literals, sink);
    if (right.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new Compare(op, left.get(), right.get()));
  }

  /**
   * What a comparison's operands may be, beyond what {@code compileNode} yields. An int literal
   * against a fused int field - {@code weekofyear(d) = 53}, {@code month(d) = 6} - is a comparison
   * of two int lanes like any other, and the literal takes a slot the way a date literal does. An
   * {@code IntegerType} column is the same lane read from a different place, which
   * {@code intOperand} already admits for arithmetic (VARKA-63), so {@code i > 0} and
   * {@code i < i2} compare in the kernel rather than leaving a residual row filter above it. Both
   * cases are stated here rather than in {@code compileNode}, whose value leaves stay
   * {@code DateType}: a bare int has no meaning as a <i>date</i> operand, and widening that would
   * admit {@code date_add(d, i)}'s offset as a date.
   *
   * <p>There is no guard question. A comparison produces a mask, not a value, so no result can
   * leave the int range - which is why this takes one rule where VARKA-63's arithmetic needed an
   * overflow mode.
   */
  private static Optional<VarkaVectorIR> operand(
      Expression e,
      VarkaSlotTable inputs,
      VarkaSlotTable literals,
      DeclineSink sink) {
    Optional<VarkaVectorIR> leaf = VarkaNodeCompiler.intLeaf(e, inputs, literals);
    return leaf.isPresent() ? leaf : VarkaNodeCompiler.compileNode(e, inputs, literals, sink);
  }
}
