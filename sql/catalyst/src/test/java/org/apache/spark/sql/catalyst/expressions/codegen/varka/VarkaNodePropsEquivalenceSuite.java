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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.Analysis.WordExpr;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.Analysis.WordOwner;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaNodeProps.Ring;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaNodeProps.Validity;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The live per-node switches against {@link VarkaNodeProps}, over every distinct node of 4000
 * drawn shapes and hand-built variants of every mode, lane, sign and level: while a switch and
 * the descriptor both exist, they must give the same answer for every node, which is what lets
 * the switch read the descriptor instead. A disagreement is a wrong {@code props()} in the
 * record it names, not a reason to change the pass.
 *
 * <p>The suite lives only as long as the switches do (`VARKA-489.md` 5): each check goes with
 * the switch it compares, since a frozen copy of a switch is a per-node list a new node would
 * have to keep, the cost the descriptor removes.
 */
@VarkaSuite
public final class VarkaNodePropsEquivalenceSuite {

  private static final int SHAPES = 4000;

  /** Adds the node {@code make} builds, or nothing where its constructor refuses the operands. */
  private static void tryAdd(Set<VarkaVectorIR> into, Supplier<VarkaVectorIR> make) {
    try {
      VarkaVariants.walk(make.get(), into::add);
    } catch (IllegalArgumentException | IllegalStateException refused) {
      // An operand combination the IR does not admit: not a node, so nothing to compare.
    }
  }

  /** Every record over every lane, mode, operator, sign and level its constructor admits. */
  static Set<VarkaVectorIR> handBuilt() {
    var nodes = new LinkedHashSet<VarkaVectorIR>();
    for (LaneType lane : LaneType.values()) {
      var col = new ColumnRef(0, lane);
      var lit = new LiteralSlot(0, lane);
      var cmp = new Compare(CompareOp.LT, col, lit);
      tryAdd(nodes, () -> col);
      tryAdd(nodes, () -> lit);
      tryAdd(nodes, () -> new NullValue(lane));
      tryAdd(nodes, () -> new BoolColumn(1, lane));
      for (Truth t : Truth.values()) {
        tryAdd(nodes, () -> new CondConst(t, lane));
      }
      for (IntOp op : IntOp.values()) {
        for (Overflow mode : Overflow.values()) {
          tryAdd(nodes, () -> new IntArith(op, mode, col, col));
          tryAdd(nodes, () -> new IntArith(op, mode, col, lit));
        }
      }
      for (Overflow mode : Overflow.values()) {
        tryAdd(nodes, () -> new IntNeg(mode, col, false));
        tryAdd(nodes, () -> new IntNeg(mode, col, true));
        tryAdd(nodes, () -> new GuardedRange(col, -100, 100, mode));
      }
      for (long divisor : new long[] {3, -3, 7, -10}) {
        tryAdd(nodes, () -> new ConstDivide(col, divisor));
      }
      for (BitOpKind op : BitOpKind.values()) {
        tryAdd(nodes, () -> new BitOp(op, col, lit));
      }
      tryAdd(nodes, () -> new BitCount(col));
      for (MathFn fn : MathFn.values()) {
        tryAdd(nodes, () -> new MathUnary(fn, col));
      }
      for (RoundMode mode : RoundMode.values()) {
        tryAdd(nodes, () -> new RoundToIntegral(mode, col));
        tryAdd(nodes, () -> new RoundToIntegral(mode, new IntArith(IntOp.DIV, Overflow.WRAP,
            col, lit)));
      }
      for (CompareOp op : CompareOp.values()) {
        tryAdd(nodes, () -> new Compare(op, col, lit));
        tryAdd(nodes, () -> new BoolCompare(op, cmp, cmp));
      }
      tryAdd(nodes, () -> new NullSafeEqual(col, lit));
      tryAdd(nodes, () -> new Greatest(col, lit));
      tryAdd(nodes, () -> new Least(col, lit));
      tryAdd(nodes, () -> new IfElse(cmp, col, lit));
      tryAdd(nodes, () -> new CondValue(cmp, LaneType.INT));
      tryAdd(nodes, () -> new BoolOutput(cmp));
      tryAdd(nodes, () -> new IsNotNull(col));
      tryAdd(nodes, () -> new IsNotNull(cmp));
      tryAdd(nodes, () -> new And(cmp, cmp));
      tryAdd(nodes, () -> new Or(cmp, cmp));
      tryAdd(nodes, () -> new Not(cmp));
      tryAdd(nodes, () -> new CondIf(cmp, cmp, cmp));
      tryAdd(nodes, () -> new BoolNullSafeEq(cmp, cmp));
      tryAdd(nodes, () -> new InRanges(col, List.of(1, 5)));
      for (LaneType target : LaneType.values()) {
        tryAdd(nodes, () -> new CrossLane(cmp, target));
        tryAdd(nodes, () -> new WidenLane(col, target));
        tryAdd(nodes, () -> new WidenLane(new IntArith(IntOp.ADD, Overflow.WRAP, col, col),
            target));
        tryAdd(nodes, () -> new NarrowLane(col, target));
        tryAdd(nodes, () -> new ConvertLane(col, target));
        for (Overflow mode : Overflow.values()) {
          for (long[] bounds : new long[][] {
              {VarkaChrono.MONTH_ARITH_MIN_MONTHS, VarkaChrono.MONTH_ARITH_MAX_MONTHS},
              {VarkaChrono.MONTH_ARITH_MIN_MONTHS - 1, VarkaChrono.MONTH_ARITH_MAX_MONTHS},
              {VarkaChrono.MONTH_ARITH_MIN_MONTHS, VarkaChrono.MONTH_ARITH_MAX_MONTHS + 1},
              {Integer.MIN_VALUE, Integer.MAX_VALUE}}) {
            tryAdd(nodes, () -> new NarrowLane(new GuardedRange(col, bounds[0], bounds[1], mode),
                target));
          }
        }
      }
    }
    var days = new ColumnRef(0);
    var n = new LiteralSlot(0);
    tryAdd(nodes, () -> new DecimalColumn(0, LaneType.LONG, 10));
    tryAdd(nodes, () -> new WidenDecimal(new DecimalColumn(0, LaneType.LONG, 10)));
    for (Word word : Word.values()) {
      tryAdd(nodes, () -> new DecimalWord(0, word, 30));
    }
    tryAdd(nodes, () -> new GuardedDay(days));
    tryAdd(nodes, () -> new AddDays(days, n));
    tryAdd(nodes, () -> new SubDays(days, n));
    tryAdd(nodes, () -> new DateDiff(days, days));
    tryAdd(nodes, () -> new AddMonths(days, n));
    tryAdd(nodes, () -> new NextDay(days, n));
    tryAdd(nodes, () -> new MakeDate(days, n, n, true));
    tryAdd(nodes, () -> new MakeDate(days, n, n, false));
    tryAdd(nodes, () -> new DayOfWeek(days));
    tryAdd(nodes, () -> new WeekDay(days));
    tryAdd(nodes, () -> new DayOfWeekIso(days));
    tryAdd(nodes, () -> new WeekOfYear(new ThursdayOf(days)));
    tryAdd(nodes, () -> new Year(days));
    tryAdd(nodes, () -> new Month(days));
    tryAdd(nodes, () -> new DayOfMonth(days));
    tryAdd(nodes, () -> new Quarter(days));
    tryAdd(nodes, () -> new DayOfYear(days));
    tryAdd(nodes, () -> new LastDay(days));
    for (TruncLevel level : TruncLevel.values()) {
      tryAdd(nodes, () -> new TruncDate(days, level));
    }
    tryAdd(nodes, () -> new TruncDateDynamic(days, new ColumnRef(1)));
    tryAdd(nodes, () -> {
      int[] form = BoundedDivide.magic(7, 1000);
      return new BoundedDivide(days, 7, 1000, form[0], form[1]);
    });
    return nodes;
  }

  /** The corpus: every distinct drawn node and every hand-built one. */
  private static Set<VarkaVectorIR> corpus() {
    var nodes = new LinkedHashSet<>(
        VarkaNodePropsSuite.nodesOf(VarkaNodePropsSuite.drawnShapes(SHAPES)));
    nodes.addAll(handBuilt());
    return nodes;
  }

  @Test
  @DisplayName("the descriptor answers what every property switch answers, node by node")
  void propertySwitchesAgree() {
    Set<VarkaVectorIR> nodes = corpus();
    var types = new LinkedHashSet<Class<?>>();
    for (VarkaVectorIR node : nodes) {
      String at = VarkaVectorIR.canonical(node);
      VarkaNodeProps props = node.props();
      types.add(node.getClass());
      assertEquals(VarkaEmitBudget.weightOf(node), props.weight(), "weight of " + at);
      assertEquals(VarkaEmitCost.kindOf(node), props.costKind(), "cost kind of " + at);
      for (VarkaOperandPosition p : VarkaOperandPosition.values()) {
        assertEquals(p.admits(node), props.fills().contains(p), p + " admits " + at);
      }
      assertEquals(VarkaOperandPosition.restrictedOperands(node), props.restricted(),
          "restricted operands of " + at);
      int ordinal = props.validity() instanceof Validity.Input in ? in.ordinal() : -1;
      assertEquals(VarkaVectorIR.inputOrdinal(node), ordinal, "input ordinal of " + at);
      assertEquals(VarkaKernelPlan.longNarrowReader(node), props.longNarrow(),
          "long-lane reader of " + at);
    }
    var records = new ArrayList<Class<?>>();
    VarkaNodePropsSuite.leaves(VarkaVectorIR.class, records);
    for (Class<?> r : records) {
      assertTrue(types.contains(r), "the corpus holds no " + r.getSimpleName());
    }
    System.out.println("VarkaNodePropsEquivalenceSuite: " + nodes.size()
        + " distinct nodes of " + types.size() + " record types agree");
  }

  /** The inputs and literals {@code roots} read, as the emitter's counts. */
  private static int[] counts(List<VarkaVectorIR> roots) {
    int[] c = new int[2];
    for (VarkaVectorIR root : roots) {
      VarkaVariants.walk(root, n -> {
        c[0] = Math.max(c[0], VarkaVectorIR.inputOrdinal(n) + 1);
        if (n instanceof LiteralSlot l) {
          c[1] = Math.max(c[1], l.index() + 1);
        }
      });
    }
    return c;
  }

  /** {@code Analysis.andOwner}'s fold over the operands' owners. */
  private static WordOwner andOwner(VarkaVectorIR node, Analysis a) {
    WordOwner owner = null;
    for (VarkaVectorIR c : VarkaVectorIR.childrenOf(node)) {
      WordOwner w = a.wordOwner.get(c);
      if (owner == null || owner == WordOwner.Const.ALL_TRUE) {
        owner = w;
      } else if (w != WordOwner.Const.ALL_TRUE && !owner.equals(w)) {
        return new WordOwner.Own(node);
      }
    }
    return owner;
  }

  /** The pure word {@code node}'s validity folds to over its operands' pure words. */
  private static WordExpr pureFold(VarkaVectorIR node, Analysis a) {
    WordExpr acc = null;
    boolean first = true;
    boolean or = node.props().validity() instanceof Validity.SkipsNull;
    for (VarkaVectorIR c : VarkaVectorIR.childrenOf(node)) {
      WordExpr w = a.pureWord.get(c);
      if (w == null) {
        return null;
      }
      if (first) {
        acc = w;
        first = false;
      } else if (or) {
        acc = acc == WordExpr.Const.ALL_TRUE || w == WordExpr.Const.ALL_TRUE
            ? WordExpr.Const.ALL_TRUE : acc.equals(w) ? acc : new WordExpr.Or(acc, w);
      } else {
        acc = acc == WordExpr.Const.ALL_TRUE ? w
            : w == WordExpr.Const.ALL_TRUE || acc.equals(w) ? acc : new WordExpr.And(acc, w);
      }
    }
    return acc;
  }

  @Test
  @DisplayName("the word rules and the ring rule agree with the descriptor in a real analysis")
  void analysisRulesAgree() {
    int analyzed = 0;
    int compared = 0;
    List<List<VarkaVectorIR>> shapes = VarkaNodePropsSuite.drawnShapes(SHAPES);
    for (int s = 0; s < shapes.size(); s++) {
      List<VarkaVectorIR> roots = shapes.get(s);
      int[] c = counts(roots);
      for (boolean check : new boolean[] {true, false}) {
        VarkaEmitOptions options = VarkaEmitOptions.DEFAULTS.withCheckIntOverflow(check);
        Analysis a;
        try {
          a = VarkaLoopEmitter.analyze(roots, c[0], c[1], options);
        } catch (IllegalArgumentException | IllegalStateException refused) {
          continue;
        }
        analyzed++;
        for (VarkaVectorIR node : a.topoOrder) {
          String at = "shape " + s + " checkIntOverflow=" + check + ": "
              + VarkaVectorIR.canonical(node);
          boolean ring = switch (node.props().ring()) {
            case Ring.Never _ -> false;
            case Ring.Pick _ -> node.laneType().isNarrow();
            case Ring.Arith _ -> node.laneType().isNarrow() && !a.checksIntOverflow(node);
          };
          assertEquals(a.isNarrowRing(node), ring, "ring of " + at);
          if (!a.wordOwner.containsKey(node)) {
            continue;
          }
          compared++;
          Validity v = node.props().validity();
          WordOwner owner = switch (v) {
            case Validity.Input in -> new WordOwner.Input(in.ordinal());
            case Validity.AllTrue _ -> WordOwner.Const.ALL_TRUE;
            case Validity.Strict _ -> andOwner(node, a);
            case Validity.SkipsNull _, Validity.Own _, Validity.NoWord _ ->
                new WordOwner.Own(node);
          };
          assertEquals(a.wordOwner.get(node), owner, "word owner of " + at);
          WordExpr pure = switch (v) {
            case Validity.Input in -> new WordExpr.Input(in.ordinal());
            case Validity.AllTrue _ -> WordExpr.Const.ALL_TRUE;
            case Validity.Strict _, Validity.SkipsNull _ -> pureFold(node, a);
            case Validity.Own _, Validity.NoWord _ -> null;
          };
          assertEquals(a.pureWord.get(node), pure, "pure word of " + at);
        }
      }
    }
    assertTrue(analyzed > SHAPES, "analyzed only " + analyzed + " of " + 2 * SHAPES);
    System.out.println("VarkaNodePropsEquivalenceSuite: " + analyzed + " analyses, "
        + compared + " word entries agree");
  }
}
