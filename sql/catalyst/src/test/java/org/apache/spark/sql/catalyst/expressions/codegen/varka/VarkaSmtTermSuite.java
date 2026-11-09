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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongBinaryOperator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtTerm.Op;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtTerm.Sort;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtTerm.Var;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtTerm.WordForm;

/**
 * The proof printer's term tree against the JVM (VARKA-506 2.2, 2.3): each operator's
 * evaluation over {@code BigInteger} is Java's operator over the boundary operands, and the word
 * form of every term {@code smt_print_check.smt2} states a law at evaluates to the unsigned
 * reading of the prelude form, its obligations true, on the boundary words and random longs. The
 * solvers hold the same rendered text to the prelude in that file; {@code java_check.smt2} holds
 * the prelude to the JVM.
 */
@VarkaSuite
public final class VarkaSmtTermSuite {

  private static final long[] WORDS = {0, 1, -1, 7, -7, Long.MAX_VALUE, Long.MIN_VALUE,
      Long.MIN_VALUE + 1, (1L << 32) - 1, 1L << 32, -(1L << 32), (1L << 31) - 1, 1L << 31};

  private static final long[] COUNTS = {-1, 0, 1, 31, 32, 33, 63, 64, 65};

  @Test
  @DisplayName("each long and int operator of the term tree evaluates as Java's operator over "
      + "the boundary operands, shift counts past the mask included")
  void operatorsAreJavas() {
    var wrong = new ArrayList<String>();
    Map<Op, LongBinaryOperator> longs = Map.of(Op.JLONG_ADD, (a, b) -> a + b,
        Op.JLONG_SUB, (a, b) -> a - b, Op.JLONG_MUL, (a, b) -> a * b,
        Op.JLONG_ULT, (a, b) -> Long.compareUnsigned(a, b) < 0 ? 1 : 0,
        Op.JLONG_ULE, (a, b) -> Long.compareUnsigned(a, b) <= 0 ? 1 : 0,
        Op.JLONG_UGT, (a, b) -> Long.compareUnsigned(a, b) > 0 ? 1 : 0,
        Op.JLONG_UGE, (a, b) -> Long.compareUnsigned(a, b) >= 0 ? 1 : 0,
        Op.JLONG_AND_SIGN, (a, b) -> (a >> 63) & b, Op.JLONG_NEG, (a, b) -> -a);
    Map<Op, LongBinaryOperator> ints = Map.of(Op.JINT_ADD, (a, b) -> (int) a + (int) b,
        Op.JINT_SUB, (a, b) -> (int) a - (int) b, Op.JINT_MUL, (a, b) -> (int) a * (int) b,
        Op.JINT_NEG, (a, b) -> -(int) a, Op.L2I, (a, b) -> (int) a);
    for (long a : WORDS) {
      expect(wrong, Op.JINT_IN, a, 0, a == (int) a ? 1 : 0);
      expect(wrong, Op.I2L, (int) a, 0, (int) a);
      expect(wrong, Op.JLONG_IN, a, 0, 1);
      expect(wrong, Op.JLONG_WRAP, a, 0, a);
      for (long b : WORDS) {
        longs.forEach((op, f) -> expect(wrong, op, op == Op.JLONG_AND_SIGN ? a >> 63 : a, b,
            f.applyAsLong(a, b)));
        ints.forEach((op, f) -> expect(wrong, op, op == Op.L2I ? a : (int) a, (int) b,
            f.applyAsLong(a, b)));
      }
      for (long s : COUNTS) {
        expect(wrong, Op.JLONG_SHL, a, s, a << s);
        expect(wrong, Op.JLONG_SHR, a, s, a >> s);
        expect(wrong, Op.JLONG_USHR, a, s, a >>> s);
        expect(wrong, Op.JINT_SHL, (int) a, s, (int) a << s);
        expect(wrong, Op.JINT_SHR, (int) a, s, (int) a >> s);
        expect(wrong, Op.JINT_USHR, (int) a, s, (int) a >>> s);
        expect(wrong, Op.JLONG_AND_LOW, a, 1L << (s & 31), a & ((1L << (s & 31)) - 1));
      }
    }
    // Past the long range, where the range tests and the wrap are not the identity: the wrap is
    // Java's narrowing of the low 64 bits, BigInteger.longValue.
    for (BigInteger v : List.of(VarkaSmtTerm.HALF, VarkaSmtTerm.HALF.negate().subtract(
        BigInteger.ONE), VarkaSmtTerm.WORD.add(BigInteger.valueOf(5)), VarkaSmtTerm.WORD.negate(),
        VarkaSmtTerm.WORD.multiply(BigInteger.valueOf(3)).subtract(BigInteger.ONE))) {
      for (Op op : List.of(Op.JLONG_IN, Op.JINT_IN, Op.JLONG_WRAP)) {
        Object got = op.apply(List.of(v));
        Object want = op == Op.JLONG_WRAP ? BigInteger.valueOf(v.longValue()) : false;
        if (!got.equals(want)) {
          wrong.add(op.smt + " " + v + " = " + got + ", Java " + want);
        }
      }
    }
    assertTrue(wrong.isEmpty(), "operators that are not Java's: " + wrong);
  }

  private static final List<Op> UNARY =
      List.of(Op.JLONG_NEG, Op.JINT_NEG, Op.L2I, Op.I2L, Op.JINT_IN, Op.JLONG_IN, Op.JLONG_WRAP);

  private static void expect(List<String> wrong, Op op, long a, long b, long java) {
    List<Object> args = UNARY.contains(op)
        ? List.of(BigInteger.valueOf(a)) : List.of(BigInteger.valueOf(a), BigInteger.valueOf(b));
    Object got = op.apply(args);
    Object want = op.sort == Sort.BOOL ? (Object) (java != 0) : BigInteger.valueOf(java);
    if (!got.equals(want)) {
      wrong.add(op.smt + " " + a + " " + b + " = " + got + ", Java " + java);
    }
  }

  @Test
  @DisplayName("div and mod evaluate as SMT-LIB's, Euclidean: a = b q + r with 0 <= r < |b|, "
      + "for negative divisors and dividends too, and a zero divisor throws")
  void divisionIsEuclidean() {
    var wrong = new ArrayList<String>();
    for (BigInteger a : VarkaProofFiles.divisionOperands(false)) {
      for (BigInteger b : VarkaProofFiles.divisionOperands(true)) {
        var q = (BigInteger) Op.DIV.apply(List.of(a, b));
        var r = (BigInteger) Op.MOD.apply(List.of(a, b));
        if (!a.equals(b.multiply(q).add(r)) || r.signum() < 0 || r.compareTo(b.abs()) >= 0) {
          wrong.add("(div " + a + " " + b + ") = " + q + ", (mod " + a + " " + b + ") = " + r);
        }
      }
    }
    assertTrue(wrong.isEmpty(), "not Euclidean: " + wrong);
    // SMT-LIB's own examples' signs: the remainder is never negative.
    long[][] rows = {{-7, 2, -4, 1}, {7, -2, -3, 1}, {-7, -2, 4, 1}, {7, 2, 3, 1}};
    for (long[] row : rows) {
      List<Object> ab = List.of(BigInteger.valueOf(row[0]), BigInteger.valueOf(row[1]));
      assertEquals(BigInteger.valueOf(row[2]), Op.DIV.apply(ab), "div " + row[0] + " " + row[1]);
      assertEquals(BigInteger.valueOf(row[3]), Op.MOD.apply(ab), "mod " + row[0] + " " + row[1]);
    }
    assertThrows(ArithmeticException.class,
        () -> Op.DIV.apply(List.of(BigInteger.ONE, BigInteger.ZERO)));
  }

  @Test
  @DisplayName("a quotient by a divisor that is not a positive constant, a mask that is no 2^k "
      + "and a variable named in the quotients' prefix are refused")
  void invariantsAreEnforced() {
    var x = new Var("x", Sort.LONG);
    for (long k : new long[] {0, -5}) {
      assertThrows(IllegalArgumentException.class,
          () -> new VarkaSmtTerm.Quot(VarkaSmtTerm.QUOTIENT + "0", x, BigInteger.valueOf(k)));
    }
    assertThrows(IllegalArgumentException.class,
        () -> new VarkaSmtTerm.Quot("x", x, BigInteger.TWO));
    assertThrows(IllegalArgumentException.class,
        () -> new Var(VarkaSmtTerm.QUOTIENT + "0", Sort.LONG));
    VarkaSmtTerm mask3 = VarkaSmtTerm.app(Op.JLONG_AND_LOW, x, VarkaSmtTerm.num(3));
    assertThrows(IllegalStateException.class, () -> new WordForm().word(mask3));
    assertThrows(IllegalStateException.class,
        () -> mask3.eval(Map.of("x", BigInteger.ONE)));
    assertEquals("(- 5)", VarkaSmtTerm.num(-5).render());
  }

  @Test
  @DisplayName("the word form of every law term is the unsigned reading of its prelude form, and "
      + "its obligations hold, on the boundary words and random longs")
  void wordFormIsThePreludeForm() {
    var x = new Var("x", Sort.LONG);
    var y = new Var("y", Sort.LONG);
    var rnd = new Random(VarkaIrGrammar.UNSIGNED_FUZZ_SEED);
    var wrong = new ArrayList<String>();
    int compared = 0;
    for (VarkaSmtTerm t : VarkaProofFiles.wordLaws(x, y)) {
      var words = new WordForm();
      boolean isBool = t.sort() == Sort.BOOL;
      VarkaSmtTerm printed = isBool ? words.term(t) : words.word(t).u();
      for (int k = 0; k < 400; k++) {
        long a = k < WORDS.length * WORDS.length ? WORDS[k % WORDS.length]
            : rnd.nextLong() >> rnd.nextInt(64);
        long b = k < WORDS.length * WORDS.length ? WORDS[k / WORDS.length] : rnd.nextLong();
        Map<String, Object> env = Map.of("x", BigInteger.valueOf(a), "y", BigInteger.valueOf(b));
        Object want = isBool ? t.eval(env) : ((BigInteger) t.eval(env)).mod(VarkaSmtTerm.WORD);
        if (!printed.eval(env).equals(want)) {
          wrong.add(t.render() + " at " + a + ", " + b + ": " + printed.eval(env));
        }
        for (var ob : words.obligations) {
          if (!Boolean.TRUE.equals(ob.claim().eval(env))) {
            wrong.add(t.render() + " at " + a + ", " + b + ": " + ob.what());
          }
        }
        compared++;
      }
    }
    assertTrue(wrong.isEmpty(), wrong.size() + " differ, the first: "
        + wrong.subList(0, Math.min(5, wrong.size())));
    assertTrue(compared >= 15000, compared + " compared");
  }
}
