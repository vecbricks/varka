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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.util.List;
import java.util.TreeMap;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;

/**
 * {@code uncountedMainLoop} ({@code VARKA-383.md} 9.5): the main lane-group loop steps by the lane
 * count times {@link VarkaLoopStride#one}, a value C2 cannot fold, so that it is not compiled as a
 * counted loop and never nested over its {@code long} range checks. What the arm changes is held
 * here: only the main loops read the field, the defaults do not, and every answer is the
 * reference's at every long-lane width, at lengths around a lane group, under each null pattern
 * and through both bodies, for a long-lane kernel, an overflow-guarded one and one that widens
 * an int column, whose loop stops a long lane group early. What it does to C2 is the history
 * reading's: {@code dev/varka_kernel_history.sh} with
 * {@code --jvm -Dvarka.matrix.config=uncountedMainLoop=true}.
 */
@VarkaSuite(ownJvm = true)
public final class VarkaUncountedMainLoopSuite {

  private static final ColumnRef L0 = new ColumnRef(0, LaneType.LONG);
  private static final ColumnRef L1 = new ColumnRef(1, LaneType.LONG);
  private static final ColumnRef I1 = new ColumnRef(1, LaneType.INT);
  private static final VarkaEmitOptions COUNTED =
      VarkaMatrixRuns.BASE.withUncountedMainLoop(false);
  private static final VarkaEmitOptions UNCOUNTED = COUNTED.withUncountedMainLoop(true);
  private static final int[] LONG_LANES = {2, 4, 8};
  private static final int[] LENGTHS = {1, 7, 8, 9, 17, 129, 1000};
  private static final List<IntPredicate> PATTERNS = List.of(
      i -> false, i -> i % 5 == 0, i -> i % 2 == 1, i -> true);

  /** The shapes: long arithmetic, try_subtract (row 605's unsettled row), a mixed kernel. */
  private static final List<VarkaVectorIR> ROOTS = List.of(
      new IntArith(IntOp.ADD, Overflow.WRAP, L0, L1),
      new IntArith(IntOp.SUB, Overflow.NULL, L0, L1),
      new IntArith(IntOp.SUB, Overflow.WRAP, L0, new WidenLane(I1)));

  /** How many times each method of {@code bytes} reads {@link VarkaLoopStride#one}. */
  private static TreeMap<String, Integer> strideReads(byte[] bytes) {
    var reads = new TreeMap<String, Integer>();
    String owner = VarkaLoopStride.class.getName().replace('.', '/');
    for (MethodModel method : ClassFile.of().parse(bytes).methods()) {
      if (method.code().isEmpty()) {
        continue;
      }
      int n = 0;
      for (CodeElement element : method.code().get()) {
        if (element instanceof FieldInstruction field && field.opcode() == Opcode.GETSTATIC
            && field.owner().asInternalName().equals(owner)) {
          n++;
        }
      }
      reads.put(method.methodName().stringValue(), n);
    }
    return reads;
  }

  private static VarkaKernelChecks.LongBatch batch(int length, int pattern, int seed) {
    var nulls = new boolean[2][length];
    var values = new long[2][length];
    IntPredicate p = PATTERNS.get(pattern);
    for (int c = 0; c < 2; c++) {
      for (int i = 0; i < length; i++) {
        nulls[c][i] = p.test(i + c);
        // Wide values for the overflow guard's rows and small ones for the int column's.
        long v = (i * 2654435761L + seed * 40503L + c) ^ ((long) i << 40);
        values[c][i] = c == 1 && i % 3 == 0 ? (int) v : v;
      }
    }
    return new VarkaKernelChecks.LongBatch(length, nulls, values, length > 1 && pattern == 0);
  }

  @Test
  @DisplayName("under uncountedMainLoop only the loop methods read VarkaLoopStride.one, and the "
      + "defaults read it nowhere")
  void onlyTheMainLoopsReadTheStride() {
    for (VarkaVectorIR root : ROOTS) {
      for (int lanes : LONG_LANES) {
        var counted = VarkaEmitterFixtures.emit(List.of(root), 2, 0,
            COUNTED.withLanesOverride(lanes));
        var uncounted = VarkaEmitterFixtures.emit(List.of(root), 2, 0,
            UNCOUNTED.withLanesOverride(lanes));
        String context = root + " at " + lanes + " lanes";
        for (var e : strideReads(counted.bytes()).entrySet()) {
          assertEquals(0, e.getValue(), context + ", defaults, " + e.getKey());
        }
        int loops = 0;
        for (var e : strideReads(uncounted.bytes()).entrySet()) {
          boolean loop = e.getKey().startsWith("loop");
          loops += loop ? 1 : 0;
          if (loop) {
            assertTrue(e.getValue() >= 1, context + ", arm, " + e.getKey() + " reads none");
          } else {
            assertEquals(0, e.getValue(), context + ", arm, " + e.getKey());
          }
        }
        assertTrue(loops >= 1, context + ": no loop method");
      }
    }
  }

  @Test
  @DisplayName("every answer under uncountedMainLoop is the reference's, at every long width, "
      + "length and null pattern, through both bodies")
  void answersAreTheReferences() {
    for (VarkaVectorIR root : ROOTS) {
      for (int lanes : LONG_LANES) {
        var emitted = VarkaEmitterFixtures.emit(List.of(root), 2, 0,
            UNCOUNTED.withLanesOverride(lanes));
        for (int length : LENGTHS) {
          for (int pattern = 0; pattern < PATTERNS.size(); pattern++) {
            String context = root + " uncounted at " + lanes + " lanes, length " + length
                + ", pattern " + pattern;
            assertTrue(VarkaKernelChecks.runAndCompareMixed(context, emitted.className(),
                emitted.bytes(), List.of(root), 2, new int[0], new long[0],
                batch(length, pattern, lanes * 31 + length), false, null), context);
          }
        }
      }
    }
  }
}
