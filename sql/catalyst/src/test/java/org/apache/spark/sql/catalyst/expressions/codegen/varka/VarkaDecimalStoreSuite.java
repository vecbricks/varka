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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenDecimal;

/**
 * The decimal store's tables ({@link VarkaDecimalStoreTables}) at two, four and eight long lanes,
 * each baked, and at {@code lanesOverride=1}, the unbaked preferred species
 * ({@code Lane.emitLanes}), labelled as such ({@link VarkaDecimalStoreTables#label}); and the
 * decimal output's shape key and refusals, which no width changes. The unbaked kernel runs on
 * {@code SPECIES_PREFERRED} and the general validity helpers, not a named species and its
 * width's own, so it is a different emission from the baked width of the same count, and this
 * is its one run at more than one lane; one long lane, which no baked width reaches, is
 * {@link VarkaDecimalStoreOneLaneSuite}'s. In a JVM of its own: the widths are several species of
 * the long lane, which the shared JVM refuses (VARKA-246).
 */
@VarkaSuite(ownJvm = true)
public final class VarkaDecimalStoreSuite extends VarkaDecimalStoreTables {

  private static final int[] WIDTHS = {1, 2, 4, 8};

  @Override
  int[] widths() {
    return WIDTHS;
  }

  @Test
  @DisplayName("a decimal output renders apart from a long root of its tree, and is refused "
      + "below a root and over an int or a condition")
  void shapeKeyAndRefusals() {
    var column = new ColumnRef(0, LaneType.LONG);
    String decimal = VarkaVectorIR.canonical(new WidenDecimal(column));
    assertEquals("(wdec col:0:long)", decimal);
    assertNotEquals(VarkaVectorIR.canonical(column), decimal);
    assertThrows(IllegalArgumentException.class, () -> new WidenDecimal(new ColumnRef(0)));
    assertThrows(IllegalArgumentException.class, () -> new WidenDecimal(
        new VarkaVectorIR.Compare(VarkaVectorIR.CompareOp.LT, column, column)));
    var below = List.<VarkaVectorIR>of(new IntArith(IntOp.ADD, Overflow.WRAP,
        new WidenDecimal(column), column));
    var e = assertThrows(IllegalArgumentException.class,
        () -> VarkaEmitterFixtures.emit(below, 1, 0, VarkaMatrixRuns.BASE));
    assertTrue(e.getMessage().contains("a decimal output below a root"), e.getMessage());
  }
}
