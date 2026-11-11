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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaReference.Truth;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtNodes.Outcome;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.*;

/**
 * The trees and rows {@link VarkaSmtNodes}' meanings are held to (VARKA-506 3 items 1 and 3):
 * the IR fuzzer's unsigned-compare and decimal sequences, and a hand set that reaches every node
 * the printer renders at the boundary words, each tree with its literal tables. The expected
 * answer of a row is {@link VarkaReference}'s; a decline, which the reference does not model, is
 * where a checked node's operands are present and its answer is not, or a guard's or a decimal
 * leaf's value is outside its bound: propagated as a node's template states it, and for the
 * kernel by the arm rule the emitter's {@code Analysis} reports ({@link #kernelAnswer}).
 */
final class VarkaSmtSamples {

  private VarkaSmtSamples() {
  }

  /** One tree to check, with the literal tables it reads, and its rows. */
  record Sample(String family, VarkaVectorIR root, int[] intLits, long[] longLits,
      List<Row> rows) { }

  /** A row: each column's value (an int's sign-extended, a boolean's 0 or 1) and null flag. */
  record Row(long[] values, boolean[] nulls) { }

  /** What the reference answers a row: a decline, or the validity and the value. */
  record Answer(boolean decline, boolean valid, Object data) { }

  static final long[] EDGE_LONGS = {0, 1, Long.MAX_VALUE, Long.MIN_VALUE, -1, (1L << 32) - 1,
      1L << 32, -(1L << 32)};
  static final long[] EDGE_INTS = {0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE, 7, -7};

  // -------------------------------------------------------------------------------------------
  // The trees.
  // -------------------------------------------------------------------------------------------

  /** The hand set, then {@code drawn} trees of each of the fuzzer's two sequences. */
  static List<Sample> samples(int drawn, int rowsEach) {
    var out = new ArrayList<Sample>();
    var rnd = new Random(VarkaIrGrammar.UNSIGNED_FUZZ_SEED);
    for (VarkaVectorIR root : hand()) {
      out.add(sample("hand", root, new int[] {1, 7}, HAND_LONGS, rnd, rowsEach));
    }
    for (int k = 0; k < drawn; k++) {
      Random r = VarkaIrGrammar.shapeRandom(VarkaIrGrammar.UNSIGNED_FUZZ_SEED, k);
      var shape = VarkaIrGrammar.drawUnsignedLongShape(r);
      long[] lits = new long[shape.numLiterals()];
      for (int i = 0; i < lits.length; i++) {
        lits[i] = VarkaIrGrammar.within(r, VarkaIrGrammar.LONG_LITERAL_BOUND);
      }
      for (VarkaVectorIR root : shape.roots()) {
        out.add(sample("unsigned " + k, root, new int[0], lits, r, rowsEach));
      }
      Random d = VarkaIrGrammar.shapeRandom(VarkaIrGrammar.DECIMAL_VALUE_FUZZ_SEED, k);
      var dec = VarkaIrGrammar.drawDecimalValueShape(d);
      for (VarkaVectorIR root : dec.roots()) {
        out.add(sample("decimal " + k, root, new int[0], dec.lits(), d, rowsEach));
      }
    }
    return out;
  }

  /** The literal table of the hand set: shift counts, a mask, a multiplier, a count past 63. */
  private static final long[] HAND_LONGS = {63, 32, (1L << 32) - 1, 1, 1_000_000_000L, 65};

  private static List<VarkaVectorIR> hand() {
    LaneType l = LaneType.LONG;
    var x = new ColumnRef(0, l);
    var y = new ColumnRef(1, l);
    var i = new ColumnRef(2, LaneType.INT);
    var b = new BoolColumn(3, LaneType.INT);
    var dec = new DecimalColumn(4, l, 9);
    var hiA = new DecimalWord(5, Word.HIGH, 38);
    var loA = new DecimalWord(5, Word.LOW, 38);
    var hiB = new DecimalWord(6, Word.HIGH, 38);
    var loB = new DecimalWord(6, Word.LOW, 38);
    var out = new ArrayList<VarkaVectorIR>();
    for (CompareOp op : CompareOp.values()) {
      out.add(new Compare(op, x, y));
    }
    // A 128-bit x < y over the words (VARKA-359), and the pair's null-safe and null tests.
    out.add(new Or(new Compare(CompareOp.LT, hiA, hiB), new And(
        new Compare(CompareOp.EQ, hiA, hiB), new Compare(CompareOp.ULT, loA, loB))));
    out.add(new And(new NullSafeEqual(hiA, hiB), new NullSafeEqual(loA, loB)));
    out.add(new Not(new IsNotNull(loA)));
    VarkaVectorIR sign = new BitOp(BitOpKind.SHR, x, new LiteralSlot(0, l));
    for (BitOpKind op : List.of(BitOpKind.SHL, BitOpKind.SHR, BitOpKind.USHR)) {
      for (int count : new int[] {0, 1, 3, 5}) {
        out.add(new BitOp(op, x, new LiteralSlot(count, l)));
      }
    }
    out.add(new BitOp(BitOpKind.AND, x, new LiteralSlot(2, l)));
    out.add(new BitOp(BitOpKind.AND, sign, y));
    out.add(new BitOp(BitOpKind.AND, y, sign));
    for (Overflow mode : Overflow.values()) {
      for (IntOp op : List.of(IntOp.ADD, IntOp.SUB)) {
        out.add(new IntArith(op, mode, x, y));
        out.add(new IntArith(op, mode, i, new LiteralSlot(1, LaneType.INT)));
      }
      out.add(new IntNeg(mode, x, false));
      out.add(new IntNeg(mode, i, true));
      out.add(new IntArith(IntOp.MUL, mode, i, new LiteralSlot(1, LaneType.INT)));
    }
    out.add(new IntArith(IntOp.MUL, Overflow.WRAP, x, new LiteralSlot(4, l)));
    out.add(new IntNeg(Overflow.WRAP, x, true));
    out.add(new GuardedRange(x, -1000, 1000, Overflow.FAIL));
    out.add(new GuardedRange(x, -1000, 1000, Overflow.NULL));
    // The date column's contract guard, which reads null rows' stored values too.
    out.add(new GuardedRange(i, VarkaChrono.CONTRACT_MIN_DAYS, VarkaChrono.CONTRACT_MAX_DAYS,
        Overflow.FAIL));
    out.add(new IfElse(new Compare(CompareOp.GT, x, y),
        new IntArith(IntOp.ADD, Overflow.FAIL, x, y), new NullValue(l)));
    out.add(new Greatest(x, y));
    out.add(new Least(x, y));
    out.add(new CondValue(new Or(new Compare(CompareOp.LT, x, y), new CondConst(
        VarkaVectorIR.Truth.NULL, l)), l));
    out.add(new BoolOutput(new And(new Compare(CompareOp.GE, i, new LiteralSlot(0,
        LaneType.INT)), new CondConst(VarkaVectorIR.Truth.TRUE, LaneType.INT))));
    out.add(new IntArith(IntOp.ADD, Overflow.WRAP, new WidenLane(i, l), x));
    out.add(new Compare(CompareOp.EQ, new NarrowLane(x, LaneType.INT), i));
    out.add(new CondIf(b, new Compare(CompareOp.LT, i, new LiteralSlot(1, LaneType.INT)),
        new CondConst(VarkaVectorIR.Truth.FALSE, LaneType.INT)));
    out.add(new WidenDecimal(new IntNeg(Overflow.WRAP, dec, true)));
    out.add(new Compare(CompareOp.LT, dec, new LiteralSlot(3, l)));
    out.add(new IfElse(new Compare(CompareOp.GT, x, y), dec, new NullValue(l)));
    return out;
  }

  /** A sample of {@code root}: rows of edge words and random values, a fifth of the slots null. */
  private static Sample sample(String family, VarkaVectorIR root, int[] intLits,
      long[] longLits, Random rnd, int rowsEach) {
    var leaves = new TreeMap<Integer, VarkaVectorIR>();
    leaves(root, leaves);
    int width = leaves.isEmpty() ? 0 : leaves.lastKey() + 1;
    var rows = new ArrayList<Row>();
    for (int r = 0; r < rowsEach; r++) {
      long[] values = new long[width];
      boolean[] nulls = new boolean[width];
      for (var e : leaves.entrySet()) {
        values[e.getKey()] = value(e.getValue(), rnd, r);
        nulls[e.getKey()] = rnd.nextInt(5) == 0;
      }
      rows.add(new Row(values, nulls));
    }
    return new Sample(family, root, intLits, longLits, rows);
  }

  private static void leaves(VarkaVectorIR node, Map<Integer, VarkaVectorIR> out) {
    switch (node) {
      case ColumnRef c -> out.put(c.ordinal(), c);
      case DecimalColumn c -> out.put(c.ordinal(), c);
      case DecimalWord c -> out.put(c.ordinal(), c);
      case BoolColumn c -> out.put(c.ordinal(), c);
      default -> {
        for (VarkaVectorIR child : VarkaVectorIR.childrenOf(node)) {
          leaves(child, out);
        }
      }
    }
  }

  /** Row {@code r}'s value of a leaf: an edge word in turn, then random ones of its range. */
  private static long value(VarkaVectorIR leaf, Random rnd, int r) {
    boolean edge = r < EDGE_LONGS.length && rnd.nextBoolean();
    return switch (leaf) {
      case BoolColumn b -> rnd.nextInt(2);
      // The bound and one past it at both ends, where the load check declines.
      case DecimalColumn c -> edge ? new long[] {0, 1, -1, c.bound(), -c.bound(), c.bound() + 1,
          -c.bound() - 1, 1}[r] : VarkaIrGrammar.within(rnd, c.bound());
      case ColumnRef c when c.lane() == LaneType.INT ->
          edge ? EDGE_INTS[r % EDGE_INTS.length] : rnd.nextInt();
      default -> edge ? EDGE_LONGS[r] : rnd.nextLong() >> rnd.nextInt(64);
    };
  }

  // -------------------------------------------------------------------------------------------
  // The reference's answers.
  // -------------------------------------------------------------------------------------------

  /** The reference's answer for {@code node} on {@code row}. */
  static Answer answer(Sample s, VarkaVectorIR root, Row row) {
    VarkaVectorIR node = root instanceof BoolOutput b ? b.cond() : root;
    boolean decline = declines(s, node, row);
    if (node instanceof Cond c) {
      Truth t = truth(s, c, row);
      return new Answer(decline, t != Truth.UNKNOWN, t == Truth.TRUE);
    }
    Long v = value(s, node, row);
    return new Answer(decline, v != null, v == null ? null : BigInteger.valueOf(v));
  }

  private static Long value(Sample s, VarkaVectorIR node, Row row) {
    var v = VarkaReference.mixedValue(node, row.values(), row.nulls(), s.intLits(), s.longLits());
    return v.isPresent() ? v.getAsLong() : null;
  }

  private static Truth truth(Sample s, Cond c, Row row) {
    return VarkaReference.mixedCond(c, row.values(), row.nulls(), s.intLits(), s.longLits());
  }

  private static boolean present(Sample s, VarkaVectorIR node, Row row) {
    return node instanceof Cond c ? truth(s, c, row) != Truth.UNKNOWN : value(s, node, row) != null;
  }

  /** Whether a node's own template declines at this row: its children's, an untaken arm's not. */
  static boolean declines(Sample s, VarkaVectorIR node, Row row) {
    if (node instanceof IfElse(var c, var t, var e, _)) {
      return declines(s, c, row) || declines(s, truth(s, c, row) == Truth.TRUE ? t : e, row);
    }
    if (node instanceof CondIf(var c, var t, var e, _)) {
      return declines(s, c, row) || declines(s, truth(s, c, row) == Truth.TRUE ? t : e, row);
    }
    boolean below = false;
    for (VarkaVectorIR k : VarkaVectorIR.childrenOf(node)) {
      below |= declines(s, k, row);
    }
    return below || fails(s, node, row);
  }

  /**
   * The reference's answer for the kernel the emitter builds over {@code root}: {@link #answer}
   * with the decline of every node {@code guards} names, from {@code Analysis}, where its own
   * check fails on a lane some arm chain of it selects, or on any lane where it has none.
   */
  static Answer kernelAnswer(Sample s, VarkaVectorIR root, Row row,
      Map<VarkaVectorIR, VarkaLoopEmitter.GuardForTest> guards) {
    Answer a = answer(s, root, row);
    boolean decline = false;
    for (var e : guards.entrySet()) {
      boolean selected = e.getValue().arms().isEmpty();
      for (List<Analysis.ArmStep> chain : e.getValue().arms()) {
        boolean all = true;
        for (Analysis.ArmStep step : chain) {
          all &= (truth(s, step.node().cond(), row) == Truth.TRUE) == step.thenBranch();
        }
        selected |= all;
      }
      VarkaVectorIR g = e.getKey();
      // A guard over a column's stored values reads a null row's value too.
      boolean fired = Analysis.guardsStoredValues(g)
          ? outsideStored((GuardedRange) g, row) : fails(s, g, row);
      decline |= selected && fired;
    }
    return new Answer(decline, a.valid(), a.data());
  }

  private static boolean outsideStored(GuardedRange g, Row row) {
    long v = row.values()[((ColumnRef) g.child()).ordinal()];
    return v < g.lo() || v > g.hi();
  }

  /** Whether {@code node}'s own check fails at this row, its operands present. */
  private static boolean fails(Sample s, VarkaVectorIR node, Row row) {
    boolean operands = true;
    for (VarkaVectorIR k : VarkaVectorIR.childrenOf(node)) {
      operands &= present(s, k, row);
    }
    return switch (node) {
      case IntArith a when a.mode() == Overflow.FAIL -> operands && value(s, node, row) == null;
      case IntNeg a when a.mode() == Overflow.FAIL -> operands && value(s, node, row) == null;
      case GuardedRange g when g.mode() == Overflow.FAIL -> operands
          && (value(s, g.child(), row) < g.lo() || value(s, g.child(), row) > g.hi());
      case DecimalColumn c -> outside(row, c.ordinal(), BigInteger.valueOf(c.bound()));
      case DecimalWord w -> outside(row, w.ordinal(), w.bound().value());
      default -> false;
    };
  }

  private static boolean outside(Row row, int c, BigInteger bound) {
    return !row.nulls()[c] && BigInteger.valueOf(row.values()[c]).abs().compareTo(bound) > 0;
  }

  // -------------------------------------------------------------------------------------------
  // The printed outcome.
  // -------------------------------------------------------------------------------------------

  /**
   * A sample's outcome as the printer prints it: the int lane in the prelude's operators, every
   * long in the word form, a long root's data as its unsigned reading. {@code words} collects the
   * word form's obligations.
   */
  static Outcome printed(Outcome o, VarkaVectorIR root, VarkaSmtTerm.WordForm words) {
    VarkaSmtTerm data = readsWord(root) ? words.word(o.data()).u() : words.term(o.data());
    return new Outcome(words.term(o.decline()), words.term(o.valid()), data);
  }

  /** Whether a root's printed data is a long's unsigned reading rather than its value. */
  static boolean readsWord(VarkaVectorIR root) {
    return root.laneType() == LaneType.LONG && !(root instanceof Cond)
        && !(root instanceof BoolOutput);
  }

  /** The environment of a row: each leaf's value and validity, by the printer's names. */
  static Map<String, Object> env(Row row) {
    var env = new HashMap<String, Object>();
    for (int c = 0; c < row.values().length; c++) {
      env.put("c" + c, BigInteger.valueOf(row.values()[c]));
      env.put("b" + c, row.values()[c] != 0);
      env.put("v" + c, !row.nulls()[c]);
    }
    return env;
  }
}
