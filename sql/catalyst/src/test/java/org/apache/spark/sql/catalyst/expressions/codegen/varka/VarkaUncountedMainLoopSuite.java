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

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.Batch;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelChecks.LongBatch;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;

/**
 * {@code uncountedMainLoop} ({@code VARKA-383.md} 9.5): every loop over Arrow accesses - the main
 * lane-group loop, and the epilogue loop of a kernel that converts between lanes or whose
 * narrowing load spans more than one group - steps by the lane count times
 * {@link VarkaLoopStride#one()}, a value C2 cannot fold, so that it is not compiled as a counted
 * loop and never nested over its {@code long} range checks. What the arm changes is held here:
 * every outermost loop's every step is so scaled under the arm and none is by default, and every
 * answer is the reference's at lengths around a lane group, under each null pattern and through
 * both bodies, for a long-lane kernel, an overflow-guarded one, one that widens an int column, an
 * int-lane one and one reading a byte column at two and four int lanes, whose loop covers two
 * or four groups an iteration. What it does to C2 is the history reading's:
 * {@code dev/varka_kernel_history.sh} with
 * {@code --jvm -Dvarka.matrix.config=uncountedMainLoop=true}.
 */
@VarkaSuite(ownJvm = true)
public final class VarkaUncountedMainLoopSuite {

  private static final ColumnRef L0 = new ColumnRef(0, LaneType.LONG);
  private static final ColumnRef L1 = new ColumnRef(1, LaneType.LONG);
  private static final ColumnRef I0 = new ColumnRef(0, LaneType.INT);
  private static final ColumnRef I1 = new ColumnRef(1, LaneType.INT);
  private static final ColumnRef B0 = new ColumnRef(0, LaneType.BYTE);
  private static final ColumnRef B1 = new ColumnRef(1, LaneType.BYTE);
  private static final int[] LENGTHS = {1, 7, 8, 9, 17, 129, 1000};
  private static final List<IntPredicate> PATTERNS = List.of(
      i -> false, i -> i % 5 == 0, i -> i % 2 == 1, i -> true);

  /** How a shape's answers are read: two long columns, two int ones or two byte ones. */
  private enum Columns { LONG, INT, BYTE }

  /**
   * A kernel shape, the driving lane counts it is emitted at and whether its epilogue loops: one
   * that converts between lanes, or whose narrowing load spans more than one int group.
   */
  private record Shape(VarkaVectorIR root, Columns columns, int[] lanes, boolean epilogueLoops) {}

  /**
   * Long arithmetic, try_subtract (row 605's unsettled row), a mixed kernel, int arithmetic, and
   * a byte choice, whose byte load spans four int groups at two int lanes and two at four.
   */
  private static final List<Shape> SHAPES = List.of(
      new Shape(new IntArith(IntOp.ADD, Overflow.WRAP, L0, L1), Columns.LONG,
          new int[] {2, 4, 8}, false),
      new Shape(new IntArith(IntOp.SUB, Overflow.NULL, L0, L1), Columns.LONG,
          new int[] {2, 4, 8}, false),
      new Shape(new IntArith(IntOp.SUB, Overflow.WRAP, L0, new WidenLane(I1)), Columns.LONG,
          new int[] {2, 4, 8}, true),
      new Shape(new IntArith(IntOp.ADD, Overflow.NULL, I0, I1), Columns.INT,
          new int[] {2, 4, 8}, false),
      new Shape(new IfElse(new Compare(CompareOp.GT, B0, B1), B0, B1), Columns.BYTE,
          new int[] {2, 4}, true));

  /**
   * The options a shape is emitted under at {@code lanes}. A byte shape pins the widened arm and
   * the baked widths, without which its loop computes one group an iteration and its epilogue
   * does not loop, whatever configuration the matrix sweeps the suite under.
   */
  private static VarkaEmitOptions options(Shape shape, int lanes, boolean uncounted) {
    var b = VarkaMatrixRuns.BASE.toBuilder().lanesOverride(lanes).uncountedMainLoop(uncounted);
    if (shape.columns() == Columns.BYTE) {
      b.narrowLanes(false).validityByWidth(true);
    }
    return b.build();
  }

  private static final String STRIDE_OWNER = VarkaLoopStride.class.getName().replace('.', '/');

  private static boolean isStrideCall(Instruction insn) {
    return insn instanceof InvokeInstruction call && call.opcode() == Opcode.INVOKESTATIC
        && call.owner().asInternalName().equals(STRIDE_OWNER);
  }

  /** What a class's loops step by: the steps checked, the stride calls, the epilogues' loops. */
  private record Steps(int checked, int strideCalls, int epilogueLoops) {}

  /**
   * Checks every outermost loop of {@code bytes} - a backward branch whose span no other one
   * holds - by its induction variable, the int its head loads first: each store to it must be
   * {@code i + lanes * VarkaLoopStride.one()} under the arm and must not read the stride by
   * default, and an {@code iinc} of it, a constant step, fails under the arm. A nested loop is
   * skipped: the range-table walk of {@code VarkaVectorWalk} steps over a static {@code int[]},
   * with no {@code long} range check for C2 to nest the loop over.
   */
  private static Steps checkSteps(byte[] bytes, boolean uncounted, String context) {
    int checked = 0;
    int strideCalls = 0;
    int epilogueLoops = 0;
    for (MethodModel method : ClassFile.of().parse(bytes).methods()) {
      if (method.code().isEmpty()) {
        continue;
      }
      String where = context + ", " + method.methodName().stringValue();
      List<CodeElement> code = method.code().get().elementList();
      var labels = new HashMap<Label, Integer>();
      for (int k = 0; k < code.size(); k++) {
        if (code.get(k) instanceof LabelTarget target) {
          labels.put(target.label(), k);
        }
        if (code.get(k) instanceof Instruction insn && isStrideCall(insn)) {
          strideCalls++;
        }
      }
      var loops = new ArrayList<int[]>();
      for (int k = 0; k < code.size(); k++) {
        if (code.get(k) instanceof BranchInstruction branch && labels.get(branch.target()) < k) {
          loops.add(new int[] {labels.get(branch.target()), k});
        }
      }
      for (int[] loop : loops) {
        boolean nested = loops.stream().anyMatch(o -> o != loop && o[0] <= loop[0]
            && loop[1] <= o[1] && (o[0] < loop[0] || loop[1] < o[1]));
        if (nested) {
          continue;
        }
        if (method.methodName().stringValue().startsWith("epilogue")) {
          epilogueLoops++;
        }
        checked += checkLoop(code, loop[0], loop[1], uncounted, where);
      }
    }
    return new Steps(checked, strideCalls, epilogueLoops);
  }

  /** The steps of one loop, checked as {@link #checkSteps} says; how many there were. */
  private static int checkLoop(List<CodeElement> code, int head, int backEdge, boolean uncounted,
      String where) {
    List<Instruction> body = new ArrayList<>();
    for (int k = head; k <= backEdge; k++) {
      if (code.get(k) instanceof Instruction insn) {
        body.add(insn);
      }
    }
    assertTrue(body.getFirst() instanceof LoadInstruction load && load.opcode() == Opcode.ILOAD,
        where + ": a loop whose head loads no int, " + body.getFirst());
    int slot = ((LoadInstruction) body.getFirst()).slot();
    int steps = 0;
    for (int k = 0; k < body.size(); k++) {
      Instruction insn = body.get(k);
      if (insn instanceof IncrementInstruction inc && inc.slot() == slot) {
        assertFalse(uncounted, where + ": a constant step under the arm, " + insn);
        steps++;
      } else if (insn instanceof StoreInstruction store && store.slot() == slot) {
        boolean scaled = k >= 3 && body.get(k - 1).opcode() == Opcode.IADD
            && body.get(k - 2).opcode() == Opcode.IMUL && isStrideCall(body.get(k - 3));
        assertEquals(uncounted, scaled, where + ": the step stored at " + k
            + (uncounted ? " does not read the stride" : " reads the stride by default"));
        steps++;
      }
    }
    assertTrue(steps >= 1, where + ": a loop whose induction variable is never stepped");
    return steps;
  }

  private static LongBatch longBatch(int length, int pattern, int seed) {
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
    return new LongBatch(length, nulls, values, length > 1 && pattern == 0);
  }

  /** Two int columns, or two byte columns' values as ints, with overflowing sums among them. */
  private static Batch intBatch(Columns columns, int length, int pattern, int seed) {
    var nulls = new boolean[2][length];
    var values = new int[2][length];
    IntPredicate p = PATTERNS.get(pattern);
    for (int c = 0; c < 2; c++) {
      for (int i = 0; i < length; i++) {
        nulls[c][i] = p.test(i + c);
        int v = i * -1640531535 + seed * 40503 + c * 7919;
        values[c][i] = columns == Columns.BYTE ? (byte) v : v;
      }
    }
    return new Batch(length, nulls, values, length > 1 && pattern == 0);
  }

  @Test
  @DisplayName("under uncountedMainLoop every loop's every step reads VarkaLoopStride.one(), the "
      + "mixed and narrow epilogue loops' too, and by default none does")
  void everyLoopStepReadsTheStride() {
    for (Shape shape : SHAPES) {
      for (int lanes : shape.lanes()) {
        VarkaKernelPlan plan = VarkaKernelPlan.of(List.of(shape.root()),
            options(shape, lanes, true));
        String context = shape.root() + " at " + lanes + " lanes";
        assertEquals(shape.epilogueLoops(), plan.mixed() || plan.narrowGroups() > 1,
            context + ": whether the epilogue loops");
        for (boolean uncounted : new boolean[] {false, true}) {
          var emitted = VarkaEmitterFixtures.emit(List.of(shape.root()), 2, 0,
              options(shape, lanes, uncounted));
          String arm = context + (uncounted ? ", arm" : ", defaults");
          Steps steps = checkSteps(emitted.bytes(), uncounted, arm);
          // At least the main loop's; a guarded kernel may have one body, and so one loop.
          assertTrue(steps.checked() >= 1, arm + ": no loop step");
          // Every stride read is a checked step's: nothing else in the class reads it.
          assertEquals(uncounted ? steps.checked() : 0, steps.strideCalls(),
              arm + ": stride reads");
          assertEquals(shape.epilogueLoops(), steps.epilogueLoops() >= 1,
              arm + ": loops in the epilogues, " + steps.epilogueLoops());
        }
      }
    }
  }

  @Test
  @DisplayName("every answer under uncountedMainLoop is the reference's, at every width, length "
      + "and null pattern, through both bodies")
  void answersAreTheReferences() {
    for (Shape shape : SHAPES) {
      for (int lanes : shape.lanes()) {
        var emitted = VarkaEmitterFixtures.emit(List.of(shape.root()), 2, 0,
            options(shape, lanes, true));
        for (int length : LENGTHS) {
          for (int pattern = 0; pattern < PATTERNS.size(); pattern++) {
            String context = shape.root() + " uncounted at " + lanes + " lanes, length " + length
                + ", pattern " + pattern;
            int seed = lanes * 31 + length;
            boolean same = switch (shape.columns()) {
              case LONG -> VarkaKernelChecks.runAndCompareMixed(context, emitted.className(),
                  emitted.bytes(), List.of(shape.root()), 2, new int[0], new long[0],
                  longBatch(length, pattern, seed), false, null);
              case INT -> VarkaKernelChecks.runAndCompare(context, emitted.className(),
                  emitted.bytes(), List.of(shape.root()), 2, new int[0],
                  intBatch(shape.columns(), length, pattern, seed), false, null);
              case BYTE -> VarkaKernelChecks.runAndCompareNarrow(context, emitted.className(),
                  emitted.bytes(), List.of(shape.root()), 2, new int[0],
                  intBatch(shape.columns(), length, pattern, seed), false, null);
            };
            assertTrue(same, context);
          }
        }
      }
    }
  }
}
