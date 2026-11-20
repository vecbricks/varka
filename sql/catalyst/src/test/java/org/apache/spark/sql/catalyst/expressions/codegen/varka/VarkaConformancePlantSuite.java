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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BiFunction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The conformance contract's checks fail on a planted wrong answer (VARKA-504). Each test runs
 * one check of {@link VarkaTypeConformance} over a type whose expected answers a stub replaces
 * ({@link VarkaTypeConformance#plantedAnswer}): a value off by its low bit, a null answered as
 * a value, a comparison with NaN ordered first. The check holds the reference evaluator, and
 * through it the kernel, to those answers row by row, so it must fail naming the disagreement.
 * A check that passed here would compare nothing.
 *
 * <p>The stubs are set on instances of the type suites, whose checks are called directly. The
 * DOUBLE checks read the double lane's species, so the class runs in a JVM of its own, as the
 * DOUBLE suite does.
 */
@VarkaSuite(ownJvm = true)
public final class VarkaConformancePlantSuite {

  /** A wrong answer for a row: the stub a planted check compares the reference with. */
  private interface Fault
      extends BiFunction<long[], VarkaKernelChecks.Answer, VarkaKernelChecks.Answer> {
  }

  /** {@code suite}'s checks, each comparing the reference with {@code fault}'s answers. */
  private static VarkaTypeConformance planted(VarkaTypeConformance suite, Fault fault) {
    suite.plantedAnswer = fault;
    return suite;
  }

  /** Every value one off in its low bit: a flipped condition, a load read wrong. */
  private static final Fault VALUE = (values, spark) ->
      spark instanceof VarkaKernelChecks.Answer.Value v
          ? new VarkaKernelChecks.Answer.Value(v.v() ^ 1) : spark;

  /** Every null answered as zero: a dropped null. */
  private static final Fault DROPPED_NULL = (values, spark) ->
      spark instanceof VarkaKernelChecks.Answer.Null ? new VarkaKernelChecks.Answer.Value(0)
          : spark;

  /** A condition over a NaN answered the other way: NaN ordered below every value. */
  private static final Fault NAN_FIRST = (values, spark) -> {
    boolean nan = false;
    for (long v : values) {
      nan |= Double.isNaN(Double.longBitsToDouble(v));
    }
    return nan && spark instanceof VarkaKernelChecks.Answer.Value v && (v.v() == 0 || v.v() == 1)
        ? new VarkaKernelChecks.Answer.Value(1 - v.v()) : spark;
  };

  /** That {@code check} fails, and on the reference's disagreement with the planted answers. */
  private static void failsOnThePlant(String what, Executable check) {
    Throwable t = assertThrows(Throwable.class, check, what + " passed on a planted answer");
    String chain = "";
    for (Throwable c = t; c != null; c = c.getCause()) {
      chain += c + "\n";
    }
    assertTrue(chain.contains("the reference evaluator and Spark disagree"),
        what + " failed, but not on the planted answer:\n" + chain);
  }

  @Test
  @DisplayName("load, round trip, null handling and ordering each fail on a value answered wrong")
  void valueAnsweredWrong() {
    var t = planted(new VarkaIntConformanceSuite(), VALUE);
    failsOnThePlant("load", t::load);
    failsOnThePlant("round trip", t::roundTrip);
    failsOnThePlant("null handling", t::nullHandling);
    failsOnThePlant("ordering", t::ordering);
  }

  @Test
  @DisplayName("null handling fails on a null answered as a value")
  void nullDropped() {
    failsOnThePlant("null handling",
        planted(new VarkaIntConformanceSuite(), DROPPED_NULL)::nullHandling);
  }

  @Test
  @DisplayName("ordering over DOUBLE fails on NaN ordered below every value")
  void nanOrderedFirst() {
    failsOnThePlant("ordering", planted(new VarkaDoubleConformanceSuite(), NAN_FIRST)::ordering);
  }
}
