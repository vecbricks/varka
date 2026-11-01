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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.function.Function;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddDays;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.SubDays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The option matrix's reach filter (VARKA-507, {@link VarkaReach}) runs a tree under a
 * configuration exactly when the configuration moves the tree's bytes. The planted arm is
 * {@code misdescribeAdd=true}, a fault injector that changes the call {@code date_add} is emitted
 * as and nothing else, so of two trees that differ only in the operator, the addition of days
 * runs and the subtraction is the defaults run's.
 */
@VarkaSuite
public final class VarkaReachSuite {

  private static final String PLANTED = "misdescribeAdd=true";
  private static final String CLASS = "org.apache.spark.sql.varka.execution.VarkaReachProbe";

  private static VarkaVectorIR tree(boolean add) {
    var days = new ColumnRef(0);
    var offset = new ColumnRef(1);
    return add ? new AddDays(days, offset) : new SubDays(days, offset);
  }

  private static Function<VarkaEmitOptions, byte[]> emitter(VarkaVectorIR root) {
    return options -> VarkaLoopEmitter.emit(CLASS, List.of(root), 2, 0, null, null, options);
  }

  /** The filter's verdict on one tree under {@code config}, emitted as the matrix would. */
  private static boolean ranAtDefaults(String config, VarkaVectorIR root, VarkaReach.Tally t) {
    VarkaEmitOptions options = VarkaMatrixRuns.parse(config);
    byte[] bytes = emitter(root).apply(options);
    return VarkaReach.ranAtDefaults(config, true, options, VarkaEmitOptions.DEFAULTS, bytes,
        emitter(root), t);
  }

  @Test
  @DisplayName("a planted arm that moves one tree's bytes runs that tree and not the other")
  void plantedArmRunsItsTreeOnly() {
    var tally = new VarkaReach.Tally();
    assertFalse(ranAtDefaults(PLANTED, tree(true), tally),
        PLANTED + " moved date_add's bytes, so the tree must run under it");
    assertTrue(ranAtDefaults(PLANTED, tree(false), tally),
        PLANTED + " left date_sub's bytes as the defaults', so the defaults run has run it");
    assertEquals(1, tally.moved());
    assertEquals(1, tally.skipped());
  }

  @Test
  @DisplayName("equal options are the defaults run's case without a second emission")
  void equalOptionsNeedNoEmission() {
    var tally = new VarkaReach.Tally();
    Function<VarkaEmitOptions, byte[]> refuse =
        o -> fail("emitted although the options are the defaults run's");
    assertTrue(VarkaReach.ranAtDefaults(PLANTED, true, VarkaEmitOptions.DEFAULTS,
        VarkaEmitOptions.DEFAULTS, new byte[] {1}, refuse, tally));
    assertEquals(1, tally.skipped());
  }

  @Test
  @DisplayName("the defaults run, a switched-off filter and a declined emission run every tree")
  void everythingRunsWhereTheFilterCannotSay() {
    var tally = new VarkaReach.Tally();
    VarkaVectorIR sub = tree(false);
    byte[] bytes = emitter(sub).apply(VarkaEmitOptions.DEFAULTS);
    assertFalse(VarkaReach.ranAtDefaults("", true, VarkaEmitOptions.DEFAULTS,
        VarkaEmitOptions.DEFAULTS, bytes, emitter(sub), tally), "the defaults run filtered");
    VarkaEmitOptions planted = VarkaMatrixRuns.parse(PLANTED);
    assertFalse(VarkaReach.ranAtDefaults(PLANTED, false, planted, VarkaEmitOptions.DEFAULTS,
        bytes, emitter(sub), tally), "a switched-off filter filtered");
    Function<VarkaEmitOptions, byte[]> declines = o -> {
      throw new VarkaEmitDeclined("declined under the defaults' options", List.of(0));
    };
    assertFalse(VarkaReach.ranAtDefaults(PLANTED, true, planted, VarkaEmitOptions.DEFAULTS,
        bytes, declines, tally), "a tree the defaults decline was taken as theirs");
    assertEquals(0, tally.skipped());
  }
}
