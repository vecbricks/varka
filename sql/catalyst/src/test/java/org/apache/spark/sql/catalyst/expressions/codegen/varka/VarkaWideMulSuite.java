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
 * pattern the harness draws. The constants are {@code 10^k} for {@code k = 0..18}, which the
 * rescale up takes, a limb of exactly {@code 2^32 - 1}, {@code Long.MAX_VALUE}, whose two limbs
 * are both large, and negative ones, which only the second sign correction answers. In a JVM of
 * its own: several species of the long lane.
 */
@VarkaSuite(ownJvm = true)
public final class VarkaWideMulSuite {

  private static final int[] WIDTHS = {1, 2, 4, 8};
  private static final int[] LENGTHS = {1, 3, 37, 64};

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

  private static VarkaEmitOptions at(int lanes) {
    return VarkaMatrixRuns.BASE.withLanesOverride(lanes);
  }
}
