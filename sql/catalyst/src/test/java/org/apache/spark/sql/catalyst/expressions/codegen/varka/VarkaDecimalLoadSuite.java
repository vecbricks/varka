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
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DecimalColumn;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;

/**
 * The decimal load's tables ({@link VarkaDecimalLoadTables}) at two, four and eight long lanes,
 * each baked, and the decimal column's shape key, refusals and bounds, which no width changes.
 * The unbaked preferred species is run only at one long lane, which no baked width reaches, by
 * {@link VarkaDecimalLoadOneLaneSuite}. In a JVM of its own: the widths are several species of
 * the long lane, which the shared JVM refuses (VARKA-246).
 */
@VarkaSuite(ownJvm = true)
public final class VarkaDecimalLoadSuite extends VarkaDecimalLoadTables {

  private static final int[] WIDTHS = {2, 4, 8};

  @Override
  int[] widths() {
    return WIDTHS;
  }

  @Test
  @DisplayName("a decimal column renders apart from a long column of its ordinal, and by its "
      + "precision")
  void shapeKey() {
    String dec9 = VarkaVectorIR.canonical(new DecimalColumn(0, LaneType.LONG, 9));
    String dec18 = VarkaVectorIR.canonical(new DecimalColumn(0, LaneType.LONG, 18));
    String bigint = VarkaVectorIR.canonical(new ColumnRef(0, LaneType.LONG));
    assertEquals("dec:0:long:p9", dec9);
    assertNotEquals(dec9, dec18);
    assertNotEquals(dec9, bigint);
  }

  @Test
  @DisplayName("a decimal column is refused off the long lane, past precision 18, and beside a "
      + "long read of the same column")
  void refusals() {
    assertThrows(IllegalArgumentException.class,
        () -> new DecimalColumn(0, LaneType.INT, 9));
    assertThrows(IllegalArgumentException.class,
        () -> new DecimalColumn(0, LaneType.LONG, 19));
    assertThrows(IllegalArgumentException.class,
        () -> new DecimalColumn(0, LaneType.LONG, 0));
    var both = List.<VarkaVectorIR>of(new DecimalColumn(0, LaneType.LONG, 9),
        new ColumnRef(0, LaneType.LONG));
    // The check runs once after the walk and names the lowest column read at two strides, here
    // column 1 although the walk meets column 2's second read first, and whether the two reads
    // share a root or not.
    var twoColumns = List.<VarkaVectorIR>of(new ColumnRef(2, LaneType.LONG),
        new DecimalColumn(2, LaneType.LONG, 9),
        new IntArith(IntOp.ADD, Overflow.WRAP, new DecimalColumn(1, LaneType.LONG, 18),
            new ColumnRef(1, LaneType.LONG)));
    for (var roots : List.of(both, twoColumns)) {
      int inputs = roots == both ? 1 : 3;
      String column = roots == both ? "column 0 " : "column 1 ";
      try {
        VarkaEmitterFixtures.emit(roots, inputs, 0, VarkaMatrixRuns.BASE);
        fail("one column read at two strides was emitted");
      } catch (IllegalArgumentException e) {
        assertTrue(e.getMessage().contains(column + "is read both as a decimal and as a long "
            + "column"), e.getMessage());
      }
    }
  }

  /** The bound a precision admits, read back from the leaf. */
  @Test
  @DisplayName("the bound of precision p is 10^p - 1")
  void bounds() {
    var got = new ArrayList<Long>();
    for (int p : new int[] {1, 9, 18}) {
      got.add(new DecimalColumn(0, LaneType.LONG, p).bound());
    }
    assertEquals(List.of(9L, 999_999_999L, 999_999_999_999_999_999L), got);
  }
}
