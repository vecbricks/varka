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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Cast;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDecimalCastProofFile;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDecimalCastProofFile.Division;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrixRuns;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuite;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitOpKind;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConstDivide;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.GuardedRange;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.types.DecimalType;

/**
 * The guard decisions of {@code VarkaDecimalCastCompiler} held to Spark's own arithmetic at every
 * pair of types (`VARKA-350.md` 3 item 1): a guard is left out exactly where no value of the
 * source type can overflow the target, which Spark's {@code Decimal.changePrecision} decides over
 * the source's widest values, and wherever Spark's {@code Cast.canNullSafeCastToDecimal} says
 * the cast cannot fail. A pure function over every {@code (p, s)} into every {@code (P, S)} up to
 * 18 digits; the integral sources and the narrow targets join it with the parts that build them.
 * It also holds the divisions the compiler builds to the table {@code decimal_casts.smt2} is
 * rendered from ({@link VarkaDecimalCastProofFile#divisions()}), so the proof cannot go on stating
 * a division, a bound or a guard the compiler no longer builds.
 */
@VarkaSuite
public final class VarkaDecimalCastTableSuite {

  private static final int MAX = 18;

  private static List<DecimalType> types() {
    var out = new ArrayList<DecimalType>();
    for (int p = 1; p <= MAX; p++) {
      for (int s = 0; s <= p; s++) {
        out.add(new DecimalType(p, s));
      }
    }
    return out;
  }

  /** Whether Spark's HALF_UP precision change of {@code v} into {@code to} overflows. */
  private static boolean sparkOverflows(BigDecimal v, DecimalType to) {
    Decimal d = Decimal.apply(v);
    return !d.changePrecision(to.precision(), to.scale(), Decimal.ROUND_HALF_UP());
  }

  /** The widest value of {@code type}, {@code (10^p - 1) / 10^s}. */
  private static BigDecimal widest(DecimalType type) {
    return new BigDecimal(BigInteger.TEN.pow(type.precision()).subtract(BigInteger.ONE),
        type.scale());
  }

  @Test
  @DisplayName("a decimal into a decimal is guarded exactly where Spark's precision change of the "
      + "widest source value overflows, and never left unguarded where Spark's null-safe rule "
      + "says it can fail")
  void decimalIntoDecimal() {
    int pairs = 0;
    var failures = new ArrayList<String>();
    for (DecimalType from : types()) {
      for (DecimalType to : types()) {
        boolean guarded = VarkaDecimalCastCompiler.canOverflow(from, to);
        BigDecimal v = widest(from);
        boolean overflows = sparkOverflows(v, to) || sparkOverflows(v.negate(), to);
        if (guarded != overflows) {
          failures.add(from + " into " + to + ": guarded " + guarded + ", Spark overflows "
              + overflows);
        }
        // Spark's rule asks for more integral digits, since a rounding can carry into one
        // more; a cast that cannot round keeps them all, which is the one more the guard omits.
        boolean safe = Cast.canNullSafeCastToDecimal(from, to);
        if (safe && guarded) {
          failures.add(from + " into " + to + ": guarded where Spark's null-safe rule says it "
              + "cannot fail");
        }
        if (!guarded && !safe && !VarkaDecimalCompiler.keepsDigits(from, to)) {
          failures.add(from + " into " + to + ": unguarded where Spark's null-safe rule says "
              + "it can fail, and the cast can round");
        }
        pairs++;
      }
    }
    assertEquals(List.of(), failures);
    assertEquals(189 * 189, pairs);
  }

  /** The nodes under {@code root}, each once. */
  private static List<VarkaVectorIR> nodes(VarkaVectorIR root) {
    Set<VarkaVectorIR> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    var out = new ArrayList<VarkaVectorIR>();
    var pending = new ArrayList<VarkaVectorIR>(List.of(root));
    while (!pending.isEmpty()) {
      VarkaVectorIR node = pending.remove(pending.size() - 1);
      if (seen.add(node)) {
        out.add(node);
        Collections.addAll(pending, VarkaVectorIR.childrenOf(node));
      }
    }
    return out;
  }

  @Test
  @DisplayName("every division by a power of ten the compiler builds is the one decimal_casts.smt2 "
      + "states: its divisor, its dividend bound, its form and its guard")
  void divisionsAreTheProofs() {
    var failures = new ArrayList<String>();
    List<Attribute> none = List.of();
    for (Division want : VarkaDecimalCastProofFile.divisions()) {
      var sink = new DeclineSink(CollectionConverters.asScala(none).toSeq(),
          VarkaMatrixRuns.BASE);
      var u = new ColumnRef(0, LaneType.LONG);
      VarkaVectorIR built = want.halfUp()
          ? VarkaDecimalCastCompiler.roundedHalfUp(u, want.p(), want.k(), sink)
          : VarkaDecimalCastCompiler.truncated(u, want.p(), want.k(), sink);
      List<VarkaVectorIR> nodes = nodes(built);
      List<ConstDivide> divides = nodes.stream().filter(n -> n instanceof ConstDivide)
          .map(n -> (ConstDivide) n).toList();
      List<GuardedRange> guards = nodes.stream().filter(n -> n instanceof GuardedRange)
          .map(n -> (GuardedRange) n).toList();
      long guard = guards.isEmpty() ? VarkaDecimalCastProofFile.NO_GUARD : guards.get(0).hi();
      boolean magnitude = divides.size() == 1
          && divides.get(0).child() instanceof BitOp b && b.op() == BitOpKind.USHR;
      var got = divides.size() != 1 || guards.size() > 1 ? null : new Division(want.p(),
          want.k(), want.halfUp(), magnitude, divides.get(0).divisor(),
          divides.get(0).dividendBound(), guard);
      if (!want.equals(got) || guards.stream().anyMatch(g -> g.lo() != 0)) {
        failures.add("the proof states " + want + ", the compiler builds " + built);
      }
    }
    assertEquals(List.of(), failures);
    assertEquals(2 * 171, VarkaDecimalCastProofFile.divisions().size());
  }
}
