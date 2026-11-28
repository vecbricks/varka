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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitOpKind;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WideMul;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Word;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WordConst;

/**
 * The lowerings of the decimal composites to primitive nodes (`VARKA-506.md` 2.1): a pure function
 * from a composite to a graph of nodes the walk emits one by one, its masks and shift counts
 * {@link WordConst}s, so the emitter and the proof printer read one network. The emitter lowers
 * every output before it plans the kernel ({@link #lowerAll}); the shape key, the compiler, the
 * reference and the cost model see the composite.
 *
 * <p>{@link WideMul}, the one composite so far, is 32-bit limbs in the carry-free order of
 * `VARKA-361.md` 2.3, built by {@link #productWord}. With {@code M = 2^32 - 1}:
 *
 * <pre>
 *   xl = x &amp; M;  xh = x &gt;&gt;&gt; 32;  yl = y &amp; M;  yh = y &gt;&gt;&gt; 32
 *   p0 = xl * yl;  p1 = xh * yl;  p2 = xl * yh;  p3 = xh * yh
 *   t  = p1 + (p0 &gt;&gt;&gt; 32)
 *   u  = p2 + (t &amp; M)
 *   hi = p3 + (t &gt;&gt;&gt; 32) + (u &gt;&gt;&gt; 32)
 *        - (y &amp; (x &gt;&gt; 63)) - (x &amp; (y &gt;&gt; 63))
 *   lo = (u &lt;&lt; 32) | (p0 &amp; M)
 * </pre>
 *
 * No limb product or sum passes {@code 2^64}, so before the corrections {@code hi:lo} is the
 * unsigned product of the two bit patterns; each correction, {@code unsignedMultiplyHigh}'s
 * inverse, reads one operand signed, and an operand read unsigned drops its correction. Each limb
 * is a mask by a constant or a shift by 32, which C2 reads as 32 unsigned bits, so each product is
 * one {@code vpmuludq}. With {@code rightOneLimb}, {@code yh}, {@code p2}, {@code p3} and the
 * second correction are zero. {@code VarkaWideMulSuite} holds both forms, and every reading of
 * the operands, against {@code BigInteger}.
 */
final class VarkaDecimalLowering {

  private VarkaDecimalLowering() {
  }

  /** A wrong lowering a test plants (rows 504, 506): an argument, never an option. */
  enum PlantedFault {
    /**
     * The middle column summed in one word, {@code t = p1 + p2 + (p0 >>> 32)} and
     * {@code hi = p3 + (t >>> 32)}: QuestDB's {@code a0 * m1 + a1 * m0} in
     * {@code Decimal128.multiplyBy64Bit} (`READING_QUESTDB.md` 9). The sum passes {@code 2^64}
     * and loses its carry where both middle products are large, which a negative operand's
     * all-ones high limb makes likely (`results/VARKA-361-planted-carry.txt`).
     */
    MIDDLE_COLUMN_ONE_WORD
  }

  /** The low limb's mask, {@code 2^32 - 1}. */
  private static final WordConst MASK = new WordConst(0xFFFFFFFFL);

  /** The limb width, a shift count. */
  private static final WordConst HALF = new WordConst(32);

  /** The sign bit's position, the count of {@code x >> 63}. */
  private static final WordConst SIGN = new WordConst(63);

  /**
   * {@code outputs} with every composite lowered; the list itself where none holds one.
   * {@code options} are the options the kernel is emitted under; row 361's two options read them.
   * {@code faults} is empty on every production path.
   */
  static List<VarkaVectorIR> lowerAll(List<VarkaVectorIR> outputs, VarkaEmitOptions options,
      Set<PlantedFault> faults) {
    Map<VarkaVectorIR, VarkaVectorIR> memo = new HashMap<>();
    List<VarkaVectorIR> lowered = new ArrayList<>(outputs.size());
    boolean changed = false;
    for (VarkaVectorIR root : outputs) {
      VarkaVectorIR l = lowered(root, options, faults, memo);
      changed |= !l.equals(root);
      lowered.add(l);
    }
    return changed ? List.copyOf(lowered) : outputs;
  }

  private static VarkaVectorIR lowered(VarkaVectorIR node, VarkaEmitOptions options,
      Set<PlantedFault> faults, Map<VarkaVectorIR, VarkaVectorIR> memo) {
    VarkaVectorIR done = memo.get(node);
    if (done != null) {
      return done;
    }
    VarkaVectorIR[] children = VarkaVectorIR.childrenOf(node);
    boolean changed = false;
    for (int i = 0; i < children.length; i++) {
      VarkaVectorIR c = lowered(children[i], options, faults, memo);
      changed |= !c.equals(children[i]);
      children[i] = c;
    }
    VarkaVectorIR rebuilt = changed ? VarkaVectorIR.withChildren(node, children) : node;
    VarkaVectorIR out = rebuilt instanceof WideMul m ? lower(m, options, faults) : rebuilt;
    memo.put(node, out);
    return out;
  }

  /**
   * {@code n}'s word as primitive long-lane nodes, every operation wrapping, both operands read
   * signed. The two words of one product build equal limbs, products and middle sums, which the
   * walk computes once.
   */
  static VarkaVectorIR lower(WideMul n, VarkaEmitOptions options, Set<PlantedFault> faults) {
    return productWord(options, n.left(), n.right(), n.word(), n.rightOneLimb(), true, true,
        faults);
  }

  /**
   * One word of {@code x * y} modulo {@code 2^128}, each operand read signed or unsigned as its
   * flag says (`VARKA-360.md` 11.4, "A word of a 64 by 64 product"). With both flags set it is
   * {@link WideMul}'s signed product; an operand read unsigned drops its correction, so a high word
   * of two unsigned operands is an unsigned word. {@code rightOneLimb} says {@code y} is in
   * {@code [0, 2^32)}, where its high limb, the two products it feeds and its correction are zero;
   * the planted fault needs two limbs and throws there.
   */
  static VarkaVectorIR productWord(VarkaEmitOptions options, VarkaVectorIR x, VarkaVectorIR y,
      Word word, boolean rightOneLimb, boolean xSigned, boolean ySigned,
      Set<PlantedFault> faults) {
    VarkaVectorIR xl = bit(BitOpKind.AND, x, MASK);
    VarkaVectorIR xh = bit(BitOpKind.USHR, x, HALF);
    VarkaVectorIR yl = bit(BitOpKind.AND, y, MASK);
    VarkaVectorIR p0 = mul(xl, yl);
    VarkaVectorIR p1 = mul(xh, yl);
    VarkaVectorIR p0High = bit(BitOpKind.USHR, p0, HALF);
    var corrections = new ArrayList<VarkaVectorIR>();
    if (xSigned) {
      // y where x is negative: the unsigned reading of x is x + 2^64 there.
      corrections.add(bit(BitOpKind.AND, y, bit(BitOpKind.SHR, x, SIGN)));
    }
    if (rightOneLimb) {
      if (!faults.isEmpty()) {
        throw VarkaErrors.illegalArgument("the planted middle column needs two limbs");
      }
      VarkaVectorIR t = add(p1, p0High);
      if (word == Word.LOW) {
        return bit(BitOpKind.OR, bit(BitOpKind.SHL, t, HALF), bit(BitOpKind.AND, p0, MASK));
      }
      VarkaVectorIR high = bit(BitOpKind.USHR, t, HALF);
      return corrections.isEmpty() ? high : sub(high, corrections.get(0));
    }
    VarkaVectorIR yh = bit(BitOpKind.USHR, y, HALF);
    VarkaVectorIR p2 = mul(xl, yh);
    VarkaVectorIR p3 = mul(xh, yh);
    if (ySigned) {
      corrections.add(bit(BitOpKind.AND, x, bit(BitOpKind.SHR, y, SIGN)));
    }
    boolean planted = faults.contains(PlantedFault.MIDDLE_COLUMN_ONE_WORD);
    VarkaVectorIR t = planted ? add(add(p1, p2), p0High) : add(p1, p0High);
    VarkaVectorIR u = planted ? t : add(p2, bit(BitOpKind.AND, t, MASK));
    if (word == Word.LOW) {
      return bit(BitOpKind.OR, bit(BitOpKind.SHL, u, HALF), bit(BitOpKind.AND, p0, MASK));
    }
    VarkaVectorIR high = add(p3, bit(BitOpKind.USHR, t, HALF));
    // The corrections are summed beside the carries, which keeps the chain one level lower
    // than subtracting them in turn: height 8 (the kernel's height budget, MAX_CHAIN_DEPTH).
    if (!corrections.isEmpty()) {
      high = sub(high, corrections.size() == 1 ? corrections.get(0)
          : add(corrections.get(0), corrections.get(1)));
    }
    return planted ? high : add(high, bit(BitOpKind.USHR, u, HALF));
  }

  /**
   * The lane calls {@code n}'s word lowers to, each operator once and its operands not counted:
   * its budget weight. It lowers under the default options and walks the lowered nodes only,
   * never asking {@code n} for its properties, since {@link WideMul#props()} calls it.
   */
  static int laneCalls(WideMul n) {
    Set<VarkaVectorIR> seen = new HashSet<>();
    seen.add(n.left());
    seen.add(n.right());
    return calls(lower(n, VarkaEmitOptions.DEFAULTS, Set.of()), seen);
  }

  private static int calls(VarkaVectorIR node, Set<VarkaVectorIR> seen) {
    if (node instanceof WordConst || !seen.add(node)) {
      return 0;
    }
    int count = 1;
    for (VarkaVectorIR child : VarkaVectorIR.childrenOf(node)) {
      count += calls(child, seen);
    }
    return count;
  }

  private static VarkaVectorIR bit(BitOpKind op, VarkaVectorIR left, VarkaVectorIR right) {
    return new BitOp(op, left, right);
  }

  private static VarkaVectorIR mul(VarkaVectorIR left, VarkaVectorIR right) {
    return new IntArith(IntOp.MUL, Overflow.WRAP, left, right);
  }

  private static VarkaVectorIR add(VarkaVectorIR left, VarkaVectorIR right) {
    return new IntArith(IntOp.ADD, Overflow.WRAP, left, right);
  }

  private static VarkaVectorIR sub(VarkaVectorIR left, VarkaVectorIR right) {
    return new IntArith(IntOp.SUB, Overflow.WRAP, left, right);
  }
}
