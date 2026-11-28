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

import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDecimalStoreTables.label;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WideMul;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Word;

/**
 * {@link WideMul} by a constant multiplier (`VARKA-360.md` 3 item 9, 11.5 part 360b1): its two
 * words from the lowered limb network against {@link BigInteger}'s product, through the reference
 * and through emitted kernels at one, two, four and eight long lanes, both bodies, every null
 * pattern the harness draws; and the network's helper under each reading of its operands. The
 * constants are {@code 10^k} for {@code k = 0..18}, which the rescale up takes, a limb of exactly
 * {@code 2^32 - 1}, {@code Long.MAX_VALUE}, whose two limbs are both large, and negative ones,
 * which only the second sign correction answers. In a JVM of its own: several species of the long
 * lane.
 */
@VarkaSuite(ownJvm = true)
public final class VarkaWideMulSuite {

  private static final int[] WIDTHS = {1, 2, 4, 8};
  private static final int[] LENGTHS = {1, 3, 37, 64};

  /** {@code 2^128}, the modulus of a product word pair read as one unsigned value. */
  private static final BigInteger TWO_128 = BigInteger.ONE.shiftLeft(128);

  /** The multipliers: the powers of ten a rescale takes, an all-ones limb and two large limbs. */
  private static List<Long> constants() {
    var out = new ArrayList<Long>();
    for (int k = 0; k <= 18; k++) {
      out.add(BigInteger.TEN.pow(k).longValueExact());
    }
    out.add(0xFFFFFFFFL);
    out.add(0xFFFFFFFFL << 20);
    out.add(Long.MAX_VALUE);
    // Negative ones, which only the second sign correction answers (row 361's column product).
    out.add(-1L);
    out.add(-12345678901L);
    out.add(Long.MIN_VALUE);
    return out;
  }

  /**
   * The operands: the decimal bounds {@code +-(10^p - 1)} at 9, 15, 17 and 18 digits, the long's
   * ends (a {@code BIGINT} under its cast), zero and one of each sign, low limbs of 0, 1,
   * {@code 2^31} and {@code 2^32 - 1} under high limbs of each sign, and random longs.
   */
  private static long[] operands(int length, long seed) {
    var fixed = new ArrayList<Long>();
    for (int p : new int[] {9, 15, 17, 18}) {
      long b = BigInteger.TEN.pow(p).longValueExact() - 1;
      fixed.add(b);
      fixed.add(-b);
    }
    for (long v : new long[] {Long.MIN_VALUE, Long.MAX_VALUE, 0, 1, -1}) {
      fixed.add(v);
    }
    for (long low : new long[] {0, 1, 1L << 31, 0xFFFFFFFFL}) {
      fixed.add((5L << 32) | low);
      fixed.add((-5L << 32) | low);
    }
    var random = new Random(seed);
    long[] out = new long[length];
    for (int i = 0; i < length; i++) {
      out[i] = i < fixed.size() ? fixed.get((int) ((i + seed) % fixed.size())) : random.nextLong();
    }
    return out;
  }

  /** The word of the exact product, from {@link BigInteger}. */
  private static long exact(long a, long b, Word word) {
    BigInteger p = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b));
    return word == Word.LOW ? p.longValue() : p.shiftRight(64).longValueExact();
  }

  private static WideMul mul(long c, Word word) {
    return new WideMul(new ColumnRef(0, LaneType.LONG), new LiteralSlot(0, LaneType.LONG), word,
        c >= 0 && c < 1L << 32);
  }

  /** {@code node}'s value on one row of one long column, with {@code c} in literal slot 0. */
  private static long eval(VarkaVectorIR node, long x, long c) {
    OptionalLong v = VarkaReference.longValue(node, new long[] {x}, new boolean[1], new long[] {c});
    return v.orElseThrow();
  }

  /** {@code v} as a 64-bit integer, read signed or as its unsigned bit pattern. */
  private static BigInteger read(long v, boolean signed) {
    BigInteger b = BigInteger.valueOf(v);
    return signed || v >= 0 ? b : b.add(BigInteger.ONE.shiftLeft(64));
  }

  @Test
  @DisplayName("the reference and the lowered network give BigInteger's two words")
  void loweredNetworkIsTheExactProduct() {
    long[] xs = operands(4000, 7L);
    for (long c : constants()) {
      for (Word word : Word.values()) {
        WideMul n = mul(c, word);
        VarkaVectorIR lowered = VarkaDecimalLowering.lower(n, VarkaMatrixRuns.BASE, Set.of());
        for (long x : xs) {
          long want = exact(x, c, word);
          assertEquals(want, eval(n, x, c), () -> "reference " + n + " at " + x);
          assertEquals(want, eval(lowered, x, c), () -> "lowered " + n + " at " + x + " * " + c);
        }
      }
    }
  }

  @Test
  @DisplayName("both words of a constant product fuse at every width, length and body")
  void kernelsAgreeWithTheReference() {
    long seed = 0;
    for (long c : constants()) {
      List<VarkaVectorIR> roots = List.of(mul(c, Word.LOW), mul(c, Word.HIGH));
      for (int lanes : WIDTHS) {
        var e = VarkaEmitterFixtures.emit(roots, 1, 1, at(lanes));
        assertTrue(VarkaEmitterTestSupport.verify(e.bytes()).isEmpty(), "the class verifies");
        for (int length : LENGTHS) {
          var random = new Random(++seed);
          boolean[][] nulls = {new boolean[length]};
          for (int i = 0; i < length; i++) {
            nulls[0][i] = random.nextInt(4) == 0;
          }
          var batch = new VarkaKernelChecks.LongBatch(length, nulls,
              new long[][] {operands(length, seed)}, false);
          assertTrue(VarkaKernelChecks.runAndCompareLong(label(lanes) + ", length " + length
              + ", times " + c, e.className(), e.bytes(), roots, 1, new long[] {c}, batch, false,
              null));
        }
      }
    }
  }

  @Test
  @DisplayName("a product word reads each operand signed or unsigned")
  void productWordReadsEachOperandAsItsFlagSays() {
    // Two columns, so each operand is a value the network cannot fold. The pair (hi, lo) is read
    // as one unsigned 128-bit value and held to the product modulo 2^128: with both operands
    // unsigned the high word is an unsigned word, which a signed reading would get wrong.
    // The one-limb form is held where y is in [0, 2^32), the range its flag promises.
    var x = new ColumnRef(0, LaneType.LONG);
    var y = new ColumnRef(1, LaneType.LONG);
    long[] xs = operands(160, 11L);
    var ys = new ArrayList<Long>();
    for (long v : operands(160, 13L)) {
      ys.add(v);
    }
    ys.addAll(constants());
    for (boolean oneLimb : new boolean[] {false, true}) {
      for (boolean xSigned : new boolean[] {false, true}) {
        for (boolean ySigned : new boolean[] {false, true}) {
          VarkaVectorIR lo = VarkaDecimalLowering.productWord(VarkaMatrixRuns.BASE, x, y,
              Word.LOW, oneLimb, xSigned, ySigned, Set.of());
          VarkaVectorIR hi = VarkaDecimalLowering.productWord(VarkaMatrixRuns.BASE, x, y,
              Word.HIGH, oneLimb, xSigned, ySigned, Set.of());
          for (long a : xs) {
            for (long b : ys) {
              if (oneLimb && (b >>> 32) != 0) {
                continue;
              }
              long[] row = {a, b};
              long l = VarkaReference.longValue(lo, row, new boolean[2], new long[0])
                  .orElseThrow();
              long h = VarkaReference.longValue(hi, row, new boolean[2], new long[0])
                  .orElseThrow();
              BigInteger got = read(h, false).shiftLeft(64).add(read(l, false)).mod(TWO_128);
              BigInteger want = read(a, xSigned).multiply(read(b, ySigned)).mod(TWO_128);
              String what = "x " + a + (xSigned ? " signed" : " unsigned") + ", y " + b
                  + (ySigned ? " signed" : " unsigned") + (oneLimb ? ", one limb" : "");
              assertEquals(want, got, what);
            }
          }
        }
      }
    }
  }

  private static VarkaEmitOptions at(int lanes) {
    return VarkaMatrixRuns.BASE.withLanesOverride(lanes);
  }
}
