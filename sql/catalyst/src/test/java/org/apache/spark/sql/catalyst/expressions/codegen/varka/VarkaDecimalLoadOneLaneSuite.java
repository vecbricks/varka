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

import jdk.incubator.vector.LongVector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The decimal load's tables ({@link VarkaDecimalLoadTables}) at one long lane: a JVM whose widest
 * vector is 64 bits, where the preferred species holds one long and the emitter bakes no width for
 * it ({@code Lane.emitLanes}). A lane group is then one slot, its low word alone in the first
 * vector and its high word alone in the second, so the odd-lane shuffle's one index must select
 * from the second vector and the epilogue's second mask starts at the vector's one lane - a case
 * no even lane count reaches. HotSpot sizes the preferred shape from {@code MaxVectorSize} and
 * falls back to 64 bits when a long vector does not fit it
 * ({@code VectorShape.getMaxVectorBitSize}), so the flag gives that species on every host. The
 * tests no width changes are {@link VarkaDecimalLoadSuite}'s alone.
 */
@VarkaSuite(ownJvm = true, jvmFlags = {"-XX:MaxVectorSize=8"})
public final class VarkaDecimalLoadOneLaneSuite extends VarkaDecimalLoadTables {

  private static final int[] WIDTHS = {1};

  /** Only the preferred species: a baked width would run as the Java fallback here. */
  @Override
  int[] widths() {
    return WIDTHS;
  }

  @Test
  @DisplayName("the JVM's preferred long species is one lane, so the tables run at one lane")
  void oneLongLane() {
    assertEquals(1, LongVector.SPECIES_PREFERRED.length(),
        "-XX:MaxVectorSize=8 did not give a one-lane preferred species");
  }
}
