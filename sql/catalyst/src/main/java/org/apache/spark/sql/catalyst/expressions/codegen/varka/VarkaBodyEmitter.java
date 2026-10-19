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

import static org.apache.spark.sql.catalyst.expressions.codegen.varka.Slots.planFragmentsReadingMonth;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDescriptors.*;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitBudget.*;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorWalk.*;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.Analysis.BitmapPass;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;

/**
 * The methods of the emitted class, and the lane-group step they all run.
 *
 * <p>{@link #emitDriver} emits the driver: from a table, its calls and little else, or unrolled,
 * the shared prologue - segment addresses, the per-input null state, the all-null shortcut -
 * followed by calls to its sibling loop methods, one per output group, and to the epilogues.
 * {@link #emitGroupBody} emits a loop method - that prologue and the vector loop over full lane
 * groups - or an epilogue, the loop body run once, masked, over the rows left past
 * {@code loopBound}; {@link #emitStage} a split driver's stage. Each has a dense and a
 * masked variant, and the two must agree wherever both could run. The lane-group step
 * ({@link #emitLaneGroup}) is the loads, the walk over each output's DAG, the validity write and
 * the stores; the validity side that is decided per body lives here with it - the per-input
 * words, the bitmap pass that writes a served root's validity a word at a time, the narrowed
 * store - and the status return that turns the guard accumulator into the method's result.
 */
final class VarkaBodyEmitter {

  private VarkaBodyEmitter() {
  }

  /**
   * The driver: the method the dispatcher calls, which prepares every output's validity, takes
   * the empty batch and the all-null shortcut, and calls the groups' loop methods and then their
   * epilogues, or the stages that call them (see the method layout in
   * {@link VarkaLoopEmitter#emit}).
   * Under {@code driverOutputTable} it is the table form, which reads its per-output work from a
   * plan; otherwise the unrolled form, which emits that work output by output.
   */
  static void emitDriver(CodeBuilder cb, boolean dense, ClassDesc classDesc,
      List<VarkaVectorIR> outputs, Analysis analysis, int numLiterals, List<List<Integer>> groups) {
    List<Integer> all = allOutputs(outputs);
    Slots s = Slots.plan(dense, BodyMode.DRIVER, outputs, all, analysis, numLiterals, false, -1);
    if (analysis.options.driverOutputTable()) {
      emitTableDriver(cb, dense, classDesc, outputs, analysis, groups, s);
    } else {
      emitUnrolledDriver(cb, dense, classDesc, outputs, all, analysis, groups, s);
    }
    noteDriverReached(dense, outputs, analysis);
  }

  /**
   * The mechanisms a driver just emitted reached (VARKA-280), read from the predicates both
   * driver forms emit by: the bitmap pass and its declines, the all-null shortcut, and a kernel
   * with no dense body. The stages are noted where their calls are emitted.
   */
  private static void noteDriverReached(boolean dense, List<VarkaVectorIR> outputs,
      Analysis analysis) {
    for (int o = 0; o < outputs.size(); o++) {
      if (servedByPass(analysis, dense, o)) {
        analysis.reach(VarkaEmitMechanism.BITMAP_PASS);
      }
    }
    if (!dense && analysis.declinedBitmapRoots > 0) {
      analysis.reach(VarkaEmitMechanism.BITMAP_PASS_DECLINED);
    }
    if (shortcutApplies(dense, outputs, analysis)) {
      analysis.reach(VarkaEmitMechanism.ALL_NULL_SHORTCUT);
    }
    if (analysis.nullsFromValidInputs) {
      analysis.reach(VarkaEmitMechanism.MASKED_ONLY);
    }
  }

  /**
   * The driver from a table: the empty-batch return, one call that zeroes or fills every output's
   * validity and runs the bitmap pass from a plan, the all-null shortcut from a table of columns,
   * and the calls to the groups. It maps no segment, sizes nothing, hoists no literal and reads no
   * species - the loop and epilogue methods do all of that for themselves - so its size is its
   * calls' ({@code VARKA-190.md} 10). Of the locals {@code Slots.plan} numbers for every body it
   * uses only the status the calls accumulate into, and under {@code splitDriver} not that.
   */
  private static void emitTableDriver(CodeBuilder cb, boolean dense, ClassDesc classDesc,
      List<VarkaVectorIR> outputs, Analysis analysis, List<List<Integer>> groups, Slots s) {
    emitEmptyReturn(cb, analysis);
    cb.aload(P_DST_VALIDITY);
    cb.aload(P_SRC_VALIDITY);
    cb.aload(P_NULL_COUNT);
    cb.loadConstant(tableConstant(outputPlan(analysis, dense, outputs), "output plan"));
    cb.iload(analysis.lane.pLength);
    cb.invokestatic(SUPPORT, "prepareOutputValidity", PREPARE_OUTPUT_VALIDITY);
    if (shortcutApplies(dense, outputs, analysis)) {
      Label live = cb.newLabel();
      cb.aload(P_NULL_COUNT);
      cb.loadConstant(tableConstant(shortcutColumns(analysis, outputs), "all-null shortcut"));
      cb.iload(analysis.lane.pLength);
      cb.invokestatic(SUPPORT, "everyOutputReadsAnAllNullColumn", EVERY_OUTPUT_ALL_NULL);
      cb.ifeq(live);
      cb.loadConstant(0);
      cb.ireturn();
      cb.labelBinding(live);
    }
    emitGroupCalls(cb, dense, classDesc, analysis, groups, s);
  }

  /**
   * The driver output by output: the shared prologue over every output, with each output's
   * validity zeroed - or set, where a dense batch makes every bit known - before anything can
   * return, so that an output nothing writes still reads as all-null; then, in the masked
   * driver, the bitmap pass for the outputs it serves and the all-null shortcut; then the calls.
   */
  private static void emitUnrolledDriver(CodeBuilder cb, boolean dense, ClassDesc classDesc,
      List<VarkaVectorIR> outputs, List<Integer> all, Analysis analysis,
      List<List<Integer>> groups, Slots s) {
    emitEmptyReturn(cb, analysis);
    emitSizes(cb, analysis, s);
    emitOutputSegments(cb, dense, BodyMode.DRIVER, outputs, all, analysis, s);
    emitInputState(cb, dense, analysis, s);
    // The bitmap pass (VARKA-70.md 3.1) after the null state it reads and before the shortcut,
    // since a batch the shortcut returns from must already have every served bitmap written.
    if (!dense) {
      for (int o = 0; o < outputs.size(); o++) {
        BitmapPass pass = analysis.served[o];
        if (pass != null) {
          emitBitmapPass(cb, s, o, pass, analysis.lane);
        }
      }
    }
    if (shortcutApplies(dense, outputs, analysis)) {
      Label live = cb.newLabel();
      boolean firstOutput = true;
      for (VarkaVectorIR root : outputs) {
        long set = analysis.columns.get(root);
        boolean firstColumn = true;
        for (int i = 0; i < analysis.numInputs; i++) {
          if ((set >>> i & 1L) != 0) {
            cb.iload(s.dead[i]);
            if (!firstColumn) {
              cb.ior();
            }
            firstColumn = false;
          }
        }
        if (!firstOutput) {
          cb.iand();
        }
        firstOutput = false;
      }
      cb.ifeq(live);
      cb.loadConstant(0);
      cb.ireturn();
      cb.labelBinding(live);
    }
    emitSpecies(cb, analysis, s);
    emitLiterals(cb, analysis, s);
    emitGroupCalls(cb, dense, classDesc, analysis, groups, s);
  }

  /**
   * A loop method or an epilogue: the shared prologue over the outputs the body writes, then the
   * vector loop over full lane groups, or the loop body run once, masked, over the rows past
   * {@code loopBound}; then the status return. Under the byte budget the body is one group's and
   * sets up only what that group writes and reads (VARKA-87); without it, a loop method is one
   * group's and the single epilogue every output's ({@code group} -1).
   */
  static void emitGroupBody(CodeBuilder cb, boolean dense, BodyMode mode, int group,
      List<VarkaVectorIR> outputs, Analysis analysis, int numLiterals,
      List<List<Integer>> groups) {
    boolean perGroup = analysis.options.methodByteBudget() > 0;
    List<Integer> bodyOutputs = group >= 0 ? groups.get(group) : allOutputs(outputs);
    if (perGroup && group < 0) {
      throw VarkaErrors.illegalArgument(
          "a " + mode + " body under the byte budget is one group's");
    }
    Slots s = Slots.plan(dense, mode, outputs, bodyOutputs, analysis, numLiterals, perGroup,
        group);
    emitEmptyReturn(cb, analysis);
    emitSizes(cb, analysis, s);
    emitScratch(cb, analysis, s);
    emitOutputSegments(cb, dense, mode, outputs, perGroup ? bodyOutputs : allOutputs(outputs),
        analysis, s);
    emitInputState(cb, dense, analysis, s);
    emitSpecies(cb, analysis, s);
    emitLiterals(cb, analysis, s);
    if (s.guardAcc != null) {
      // An empty mask: no lane has been found out of range yet. One per reason under
      // `exactRefusalStatus`.
      for (int acc : guardAccs(s)) {
        cb.aload(s.species);
        cb.loadConstant(0L);
        cb.invokestatic(VECTOR_MASK, "fromLong", FROM_LONG);
        cb.astore(acc);
      }
      // The min-and-max guards' accumulators start at their own bounds, so that they compare
      // outside only once a guarded lane was (VARKA-379 11).
      for (Map.Entry<Slots.Bounds, Integer> e : s.rangeAcc.entrySet()) {
        for (int k = 0; k < 2; k++) {
          cb.aload(s.species);
          analysis.lane.pushScalar(cb, k == 0 ? e.getKey().lo() : e.getKey().hi());
          cb.invokestatic(analysis.lane.vector, "broadcast", analysis.lane.broadcast);
          cb.astore(e.getValue() + k);
        }
      }
    }
    switch (mode) {
      case LOOP -> emitVectorLoop(cb, dense, outputs, bodyOutputs, analysis, s);
      case EPILOGUE -> emitEpilogue(cb, dense, outputs, bodyOutputs, analysis, s);
      case DRIVER -> throw VarkaErrors.illegalArgument("the driver is emitDriver's, not a body's");
    }
    assertWordsLive(s, mode);
    emitRangeFold(cb, analysis, s);
    emitStatusReturn(cb, s);
    if (!s.deadRefs.isEmpty()) {
      // The input words this masked body allocated and, by the liveness pass, never read.
      analysis.reach(VarkaEmitMechanism.DEAD_INPUT_WORD);
    }
  }

  private static List<Integer> allOutputs(List<VarkaVectorIR> outputs) {
    List<Integer> all = new java.util.ArrayList<>();
    for (int o = 0; o < outputs.size(); o++) {
      all.add(o);
    }
    return all;
  }

  // ---------------------------------------------------------------------------------------------
  // The prologue: the steps every unrolled driver, loop method and epilogue begins with, each
  // re-derived from the same seven parameters.
  // ---------------------------------------------------------------------------------------------

  /** {@code if (length <= 0) return 0}: nothing ran, so there is nothing to report. */
  private static void emitEmptyReturn(CodeBuilder cb, Analysis analysis) {
    Label nonEmpty = cb.newLabel();
    cb.iload(analysis.lane.pLength);
    cb.ifgt(nonEmpty);
    cb.loadConstant(0);
    cb.ireturn();
    cb.labelBinding(nonEmpty);
  }

  /**
   * The nominal sizes: {@code dataBytes = (long) length * stride} and
   * {@code validityBytes = (length + 7) / 8L}.
   */
  private static void emitSizes(CodeBuilder cb, Analysis analysis, Slots s) {
    cb.iload(analysis.lane.pLength);
    cb.i2l();
    cb.loadConstant(analysis.lane.byteStride);
    cb.lmul();
    // The fault injector of a wrong mapping (VARKA-315); at zero, the default, nothing is emitted.
    int misdescribed = analysis.options.misdescribeDataBytes();
    if (misdescribed != 0) {
      cb.loadConstant((long) misdescribed);
      cb.ladd();
    }
    cb.lstore(s.dataBytes);
    cb.iload(analysis.lane.pLength);
    cb.loadConstant(7);
    cb.iadd();
    cb.i2l();
    cb.loadConstant(8L);
    cb.ldiv();
    cb.lstore(s.validityBytes);
  }

  /**
   * A materialized prefix's scratch (VARKA-198), in a body that stores into or loads from it: one
   * segment over all of it, regions times {@code SCRATCH_VECTORS} times {@code dataBytes}, so the
   * caller's scratch is laid out by the batch's own length; region r's vector k is read and
   * written at {@code byteOffset + (r * SCRATCH_VECTORS + k) * dataBytes}.
   */
  private static void emitScratch(CodeBuilder cb, Analysis analysis, Slots s) {
    if (s.scratchSeg < 0) {
      return;
    }
    cb.lload(analysis.scratchParam());
    cb.lload(s.dataBytes);
    cb.loadConstant((long) analysis.materialized.size() * Analysis.SCRATCH_VECTORS);
    cb.lmul();
    cb.invokestatic(SUPPORT, "ofAddress", OF_ADDRESS);
    cb.astore(s.scratchSeg);
  }

  /**
   * Each output's data and validity segments, and - in the driver only - its validity zeroed
   * before any return, the emitter invariant: an output nothing writes must still read as
   * all-null. The loop and epilogue methods run after bits were written and must not. A
   * {@code Cond} root's data address is 0L by the interface contract and is not materialized;
   * zeroing its bitmap doubles as the selection invariant - an unwritten row reads as unselected.
   */
  private static void emitOutputSegments(CodeBuilder cb, boolean dense, BodyMode mode,
      List<VarkaVectorIR> outputs, List<Integer> prologueOutputs, Analysis analysis, Slots s) {
    for (int o : prologueOutputs) {
      VarkaVectorIR root = outputs.get(o);
      // An int-valued root of a kernel driven at the long lane - a narrowing root, or any int
      // root of a kernel that converts between lanes - is an int column, four bytes a row.
      if (!(root instanceof Cond) && analysis.lane == Lane.LONG
          && root.laneType() == VarkaVectorIR.LaneType.INT) {
        loadNarrowedSegment(cb, analysis, P_DST_DATA, o, s.dstSeg[o]);
      } else if (!(root instanceof Cond)) {
        loadSegment(cb, P_DST_DATA, o, s.dataBytes, s.dstSeg[o]);
      }
      // Under elideUnreadLocals a loop or epilogue body maps no validity segment for an output
      // whose validity it never writes: the driver filled it or the bitmap pass wrote it.
      if (buildsValiditySegment(analysis, dense, mode, outputs, o)) {
        // an output this emission writes a word at a time needs the segment to own the whole last
        // word, ((length + 63) / 64) * 8 bytes, where the nominal (length + 7) / 8 is short of it
        // for every length not a multiple of 64. The Arrow buffer behind it carries that at every
        // length (VarkaKernelEvaluatorSuite), and the driver's zero below then covers exactly the
        // bytes the loop stores. Every other output keeps the nominal size.
        if (wordWrites(analysis) && keepsPerGroupWrite(analysis, dense, outputs, o)) {
          cb.aload(P_DST_VALIDITY);
          cb.loadConstant(o);
          cb.laload();
          cb.iload(analysis.lane.pLength);
          cb.loadConstant(63);
          cb.iadd();
          cb.i2l();
          cb.loadConstant(64L);
          cb.ldiv();
          cb.loadConstant(3);
          cb.lshl();
          cb.invokestatic(SUPPORT, "ofAddress", OF_ADDRESS);
          cb.astore(s.dstValSeg[o]);
        } else {
          loadSegment(cb, P_DST_VALIDITY, o, s.validityBytes, s.dstValSeg[o]);
        }
      }
      // an output the bitmap pass serves is written whole after the input null state exists and
      // before the shortcut can return, and that write is what keeps this invariant for it, not
      // a zero it overwrites.
      if (mode == BodyMode.DRIVER && !servedByPass(analysis, dense, o)) {
        cb.aload(s.dstValSeg[o]);
        if (fillsValidityOnce(analysis, dense, outputs.get(o))) {
          // on a dense batch every value output is valid on every row, so the bits are known here
          // and the loop's per-lane-group OR would write ones over ones. Setting them once costs a
          // fill of the same bytes the zero would have touched.
          cb.iload(analysis.lane.pLength);
          cb.invokestatic(SUPPORT, "setValid", SET_VALID);
        } else {
          cb.invokestatic(SUPPORT, "zero", ZERO);
        }
      }
    }
  }

  /**
   * Each referenced input's null state and data segment. The dense body derives no null state -
   * the dispatcher has proven the batch null-free - and neither does a body whose every reader of
   * the input's word is gone, which is what makes such a masked body the dense one's bytes. An
   * all-null input's validity address is 0L by the morsel contract, so its segment is not
   * materialized; its validity word is 0L in every group instead, which nulls everything computed
   * from it.
   *
   * <p>The unrolled driver derives all of it, and most of that is dead there: {@code hasNulls},
   * the validity segment and the data segment are read only by the lane-group step, which only a
   * loop or epilogue body runs, and {@code dead} only by the all-null shortcut. The driver is
   * planned without the liveness pass (see {@code Slots.plan}), so this residue stays; it is what
   * {@code VARKA-70.md} 9.2 prediction 3 measures.
   */
  private static void emitInputState(CodeBuilder cb, boolean dense, Analysis analysis, Slots s) {
    for (int i = 0; i < analysis.numInputs; i++) {
      if ((s.inputs >>> i & 1L) == 0) {
        continue;
      }
      if (dense || s.deadRefs.contains(s.word[i])) {
        // A column only a skipped date reads, with its word dead too, is read by nothing here.
        // Nor one the body reads only for its validity, under elideUnreadLocals (Slots.plan).
        if ((s.skippedColumns >>> i & 1L) == 0 && s.srcSeg[i] >= 0) {
          loadInputSegment(cb, analysis, s, i);
        }
        continue;
      }
      cb.aload(P_NULL_COUNT);
      cb.loadConstant(i);
      cb.iaload();
      cb.istore(s.ncTmp);
      Label notDead = cb.newLabel();
      Label stateDone = cb.newLabel();
      cb.iload(s.ncTmp);
      cb.iload(analysis.lane.pLength);
      cb.if_icmpne(notDead);
      cb.loadConstant(1);
      cb.istore(s.dead[i]);
      cb.loadConstant(0);
      cb.istore(s.hasNulls[i]);
      cb.aconst_null();
      cb.astore(s.srcValSeg[i]);
      cb.goto_(stateDone);
      cb.labelBinding(notDead);
      cb.loadConstant(0);
      cb.istore(s.dead[i]);
      Label noNulls = cb.newLabel();
      cb.iload(s.ncTmp);
      cb.ifle(noNulls);
      cb.loadConstant(1);
      cb.istore(s.hasNulls[i]);
      cb.aload(P_SRC_VALIDITY);
      cb.loadConstant(i);
      cb.laload();
      cb.lload(s.validityBytes);
      cb.invokestatic(SUPPORT, "ofAddress", OF_ADDRESS);
      cb.astore(s.srcValSeg[i]);
      cb.goto_(stateDone);
      cb.labelBinding(noNulls);
      cb.loadConstant(0);
      cb.istore(s.hasNulls[i]);
      cb.aconst_null();
      cb.astore(s.srcValSeg[i]);
      cb.labelBinding(stateDone);
      if (s.srcSeg[i] >= 0) {
        loadInputSegment(cb, analysis, s, i);
      }
    }
  }

  /**
   * Whether the driver emits the all-null shortcut - return iff every output reads at least one
   * all-null column. Only in the masked driver (the dense body has nothing null; the loop and
   * epilogue methods never run when it fires), only when every output reads a column, and only
   * for null-intolerant outputs: a null-skipping subtree ({@code greatest}, {@code IfElse}) can
   * be valid over an all-null column. A {@code Cond} root is excluded outright: an OR over one
   * all-null column and one live one still selects rows, which the zeroed bitmap the shortcut
   * leaves behind would deny. The loop needs no shortcut to be correct there.
   */
  private static boolean shortcutApplies(boolean dense, List<VarkaVectorIR> outputs,
      Analysis analysis) {
    boolean applies = !dense;
    for (VarkaVectorIR root : outputs) {
      applies &= analysis.columns.get(root) != 0L && !analysis.skipping.get(root)
          && !(root instanceof Cond);
    }
    return applies;
  }

  /**
   * The species, the lane count and the loop bound. The species is read with getstatic so it
   * stays a JIT constant - what lets C2 intrinsify the calls - and it is the concrete one this
   * emission was built for where the width-specialised validity helpers are in use, so the class
   * cannot disagree with the helper names beside it and the lane count is a bytecode constant;
   * otherwise {@code SPECIES_PREFERRED} and its {@code length()}.
   */
  private static void emitSpecies(CodeBuilder cb, Analysis analysis, Slots s) {
    cb.getstatic(analysis.lane.vector, analysis.lane.speciesField(analysis.lanes),
        VECTOR_SPECIES);
    cb.astore(s.species);
    if (s.intSpecies >= 0) {
      // The int species of the driving species' width, in a kernel that converts between lanes:
      // the width's own species, never a half-width one (`VARKA-28.md` 2.2).
      cb.getstatic(Lane.INT.vector, Lane.INT.speciesField(analysis.lanesOf(Lane.INT)),
          VECTOR_SPECIES);
      cb.astore(s.intSpecies);
    }
    if (analysis.lanes != 0) {
      cb.loadConstant(analysis.lanes);
    } else {
      cb.aload(s.species);
      cb.invokeinterface(VECTOR_SPECIES, "length", SPECIES_LENGTH);
    }
    cb.istore(s.lanes);
    cb.aload(s.species);
    cb.iload(analysis.lane.pLength);
    if (analysis.mixed()) {
      // A kernel that converts between lanes reads an int column a whole int vector at a time,
      // twice the rows a long lane group covers, so its loop stops a long lane group early: every
      // int load and store in it is then whole and unmasked, where a masked one costs two to
      // five times as much (`VARKA-28.md` 9.3), and the rest - one long lane group to just
      // under two - is the epilogue's.
      cb.iload(s.lanes);
      cb.isub();
    }
    cb.invokeinterface(VECTOR_SPECIES, "loopBound", LOOP_BOUND);
    if (analysis.mixed()) {
      // A batch shorter than a long lane group rounds down below zero: no loop, all epilogue.
      cb.iconst_0();
      cb.invokestatic(MATH, "max", MATH_INT_PAIR);
    }
    cb.istore(s.loopBound);
  }

  /** The literals the body reads, hoisted out of the loop, and broadcast where it wants vectors. */
  private static void emitLiterals(CodeBuilder cb, Analysis analysis, Slots s) {
    emitLiterals(cb, analysis.lane, s.scalarArg.length, s);
    if (s.intScalarArg != null) {
      // The int table, in a kernel that converts between lanes; read from the int array, which
      // the long lane's `run` takes beside its own.
      emitLiterals(cb, Lane.INT, s.intScalarArg.length, s);
    }
  }

  /** {@code lane}'s table: its {@code count} slots, hoisted, and broadcast where wanted. */
  private static void emitLiterals(CodeBuilder cb, Lane lane, int count, Slots s) {
    int[] scalarArg = s.scalarArgs(lane);
    int[] broadcastSlot = s.broadcastSlots(lane);
    for (int j = 0; j < count; j++) {
      if (scalarArg[j] < 0) {
        continue; // a literal no output of this body reads (per-group planning, VARKA-87)
      }
      cb.aload(lane == Lane.INT ? P_SCALAR_ARGS : lane.scalarArgsSlot());
      cb.loadConstant(j);
      lane.arrayLoad(cb);
      lane.storeScalar(cb, scalarArg[j]);
      if (broadcastSlot != null) {
        cb.aload(s.species(lane));
        lane.loadScalar(cb, scalarArg[j]);
        cb.invokestatic(lane.vector, "broadcast", lane.broadcast);
        cb.astore(broadcastSlot[j]);
      }
    }
  }

  /**
   * The driver's calls: every group's loop method, then its epilogue, or each stage in order under
   * {@code splitDriver}, returning the union of their statuses - one out-of-range lane anywhere
   * condemns the batch, which is what the caller acts on. Each epilogue keeps its own even-batch
   * return rather than the driver testing once for all of them: the calls that return at once are
   * what warm the method up on a scan whose batches mostly divide evenly (VARKA-87.md 2.6.3).
   * Under {@code splitDriver} the status stays on the operand stack; otherwise it is kept in the
   * status local between calls.
   */
  private static void emitGroupCalls(CodeBuilder cb, boolean dense, ClassDesc classDesc,
      Analysis analysis, List<List<Integer>> groups, Slots s) {
    if (analysis.stageGroups > 0) {
      analysis.reach(VarkaEmitMechanism.STAGED_DRIVER);
      cb.loadConstant(0);
      for (int k = 0; k * analysis.stageGroups < groups.size(); k++) {
        invokeCall(cb, classDesc, VarkaMethodNames.stage(dense, k), analysis);
        cb.ior();
      }
      cb.ireturn();
      return;
    }
    cb.loadConstant(0);
    cb.istore(s.status);
    for (int g = 0; g < groups.size(); g++) {
      cb.iload(s.status);
      invokeCall(cb, classDesc, VarkaMethodNames.loop(dense, g), analysis);
      cb.ior();
      cb.istore(s.status);
    }
    if (analysis.options.methodByteBudget() > 0) {
      // The last call's status is returned as it is rather than stored and reloaded: the driver
      // is the one method no regroup can shrink, so its bytes are worth keeping.
      for (int g = 0; g < groups.size(); g++) {
        cb.iload(s.status);
        invokeCall(cb, classDesc, VarkaMethodNames.epilogue(dense, g), analysis);
        cb.ior();
        if (g < groups.size() - 1) {
          cb.istore(s.status);
        }
      }
    } else {
      cb.iload(s.status);
      invokeCall(cb, classDesc, VarkaMethodNames.epilogue(dense), analysis);
      cb.ior();
    }
    cb.ireturn();
  }

  /**
   * Stage {@code k} of a split driver (see {@link VarkaEmitOptions#splitDriver}): the loop methods
   * of its run of groups and then their epilogues, returning the union of their statuses. It
   * needs no prologue - the driver has taken the empty batch, prepared every output's validity
   * and taken the all-null shortcut before calling it - and keeps the status on the operand stack,
   * so it has no locals beyond the parameters it forwards. The groups keep their order across the
   * stages, so a group that reads a prefix an earlier group materialized (VARKA-198) still runs
   * after it, loop after loop and epilogue after epilogue.
   */
  static void emitStage(CodeBuilder cb, boolean dense, int k, ClassDesc classDesc,
      Analysis analysis, List<List<Integer>> groups) {
    int from = k * analysis.stageGroups;
    int to = Math.min(groups.size(), from + analysis.stageGroups);
    cb.loadConstant(0);
    for (int g = from; g < to; g++) {
      invokeCall(cb, classDesc, VarkaMethodNames.loop(dense, g), analysis);
      cb.ior();
    }
    for (int g = from; g < to; g++) {
      invokeCall(cb, classDesc, VarkaMethodNames.epilogue(dense, g), analysis);
      cb.ior();
    }
    cb.ireturn();
  }

  /**
   * Ends a loop or epilogue method with its status: a constant zero where nothing is guarded,
   * and otherwise the refusal bits of the guards that fired (VARKA-304). With one mask, a
   * non-empty mask returns {@link Slots#guardStatus}, every reason the body holds; under
   * {@code exactRefusalStatus} each reason's mask contributes its own bit. The reduction is once
   * per method, not once per lane group - the accumulator is a mask OR in the loop, which is one
   * op.
   */
  private static void emitStatusReturn(CodeBuilder cb, Slots s) {
    if (s.guardAcc == null) {
      cb.loadConstant(0);
      cb.ireturn();
      return;
    }
    if (s.guardAccByReason != null) {
      // status = (anyTrue(acc_r) ? bit_r : 0) | ... over the body's reasons, in enum order.
      cb.loadConstant(0);
      for (Map.Entry<Analysis.Refusal, Integer> e : s.guardAccByReason.entrySet()) {
        Label clean = cb.newLabel();
        cb.aload(e.getValue());
        cb.invokevirtual(VECTOR_MASK, "anyTrue", ANY_TRUE);
        cb.ifeq(clean);
        cb.loadConstant(e.getKey().status);
        cb.ior();
        cb.labelBinding(clean);
      }
      cb.ireturn();
      return;
    }
    Label clean = cb.newLabel();
    cb.aload(s.guardAcc);
    cb.invokevirtual(VECTOR_MASK, "anyTrue", ANY_TRUE);
    cb.ifeq(clean);
    cb.loadConstant(s.guardStatus);
    cb.ireturn();
    cb.labelBinding(clean);
    cb.loadConstant(0);
    cb.ireturn();
  }

  /**
   * After the loop, each pair of min-and-max accumulators ({@link Slots#rangeAcc}) compared once
   * against its bounds - a min below {@code lo} or a max above {@code hi} - and ORed into the mask
   * the status return reduces: the body's one mask, or under {@code exactRefusalStatus} the
   * value-range reason's, the only reason such a guard has. Nothing is emitted in a body without
   * one. Once a method rather than once a lane group, so the guard's loop cost is the min and the
   * max alone (VARKA-379 11).
   */
  private static void emitRangeFold(CodeBuilder cb, Analysis analysis, Slots s) {
    if (s.rangeAcc.isEmpty()) {
      return;
    }
    int acc = s.guardAccByReason != null
        ? s.guardAccByReason.get(Analysis.Refusal.VALUE_RANGE) : s.guardAcc;
    for (Map.Entry<Slots.Bounds, Integer> e : s.rangeAcc.entrySet()) {
      cb.aload(e.getValue());
      cb.getstatic(VECTOR_OPERATORS, "LT", VO_COMPARISON);
      analysis.lane.pushScalar(cb, e.getKey().lo());
      cb.invokevirtual(analysis.lane.vector, "compare", analysis.lane.compareVI);
      cb.aload(e.getValue() + 1);
      cb.getstatic(VECTOR_OPERATORS, "GT", VO_COMPARISON);
      analysis.lane.pushScalar(cb, e.getKey().hi());
      cb.invokevirtual(analysis.lane.vector, "compare", analysis.lane.compareVI);
      cb.invokevirtual(VECTOR_MASK, "or", MASK_BINARY);
      cb.aload(acc);
      cb.invokevirtual(VECTOR_MASK, "or", MASK_BINARY);
      cb.astore(acc);
    }
    if (s.guardAccByReason != null) {
      analysis.reach(VarkaEmitMechanism.GUARD_EXACT_STATUS);
    }
  }

  /** The body's guard masks: the one mask, or each reason's under {@code exactRefusalStatus}. */
  private static Collection<Integer> guardAccs(Slots s) {
    return s.guardAccByReason != null ? s.guardAccByReason.values() : List.of(s.guardAcc);
  }

  private static void emitVectorLoop(CodeBuilder cb, boolean dense,
      List<VarkaVectorIR> outputs, List<Integer> outputIdx, Analysis analysis, Slots s) {
    // each validity accumulator is zeroed before the loop, not because the first group needs it -
    // it starts a word and clears the accumulator itself - but because the verifier does. The clear
    // sits behind `if ((i & 63) == 0)`, so at that branch's merge one incoming edge has assigned
    // the local and the other has not, and a merge of `long` with `top` is `top`: `VerifyError: Bad
    // local variable type` on the first `lload`. Two bytecodes once per method make the local
    // definitely assigned on every path into the loop.
    if (s.validityAcc != null) {
      for (int o : outputIdx) {
        if (s.validityAcc[o] >= 0) {
          cb.loadConstant(0L);
          cb.lstore(s.validityAcc[o]);
        }
      }
    }

    // (6) The lane-group loop: for (i = 0; i < loopBound; i += lanes).
    cb.loadConstant(0);
    cb.istore(s.iVar);
    Label loopTop = cb.newLabel();
    Label loopEnd = cb.newLabel();
    cb.labelBinding(loopTop);
    cb.iload(s.iVar);
    cb.iload(s.loopBound);
    cb.if_icmpge(loopEnd);

    emitLaneGroup(cb, dense, outputs, outputIdx, analysis, s);

    cb.iload(s.iVar);
    cb.iload(s.lanes);
    cb.iadd();
    cb.istore(s.iVar);
    cb.goto_(loopTop);
    cb.labelBinding(loopEnd);
  }

  /**
   * (7) The masked epilogue, as its own method body: the rows past
   * {@code loopBound}, done as one more iteration of the very same lane-group body rather
   * than as a second, scalar walk of the IR. Three substitutions make it so - {@code i} is
   * {@code loopBound} with no back edge, {@code lanes} becomes the remainder so every
   * validity helper is bounded by it, and {@code indexInRange} supplies the mask the loads
   * and the stores take. Nothing between a load and a store is masked, exactly as in the
   * loop.
   *
   * <p>A kernel that converts between lanes is the exception to "no back edge": its loop stops a
   * long lane group early, so its epilogue runs this body in a loop of its own, up to twice
   * ({@code emitMixedEpilogue}), the first time over a whole group.
   *
   * <p>The masked load is not an optimization here: the data segment is sized to
   * {@code length * 4}, so an unmasked load of the last partial group would run off the end
   * of the segment. Its other consequence is the invariant recorded in the class doc - lanes
   * outside the mask read {@code 0}, so no operation in the walk may trap on {@code 0}.
   *
   * <p>What this replaces: a per-row topological pass that computed every distinct node's value
   * (and, masked, its validity bit and a condition's kT/kF bits) into int locals - a complete
   * second lowering of the IR, roughly 330 lines and a second {@code switch} over every node type,
   * which every node type added would have had to extend twice and keep in agreement row for row.
   */
  private static void emitEpilogue(CodeBuilder cb, boolean dense,
      List<VarkaVectorIR> outputs, List<Integer> outputIdx, Analysis analysis, Slots s) {
    // Nothing to do when the batch divides evenly - the common case, since the default
    // COLUMN_BATCH_SIZE is 4096 and every lane count this runs at divides it.
    Label remainder = cb.newLabel();
    cb.iload(s.loopBound);
    cb.iload(analysis.lane.pLength);
    cb.if_icmplt(remainder);
    cb.loadConstant(0);
    cb.ireturn();
    cb.labelBinding(remainder);

    cb.iload(s.loopBound);
    cb.istore(s.iVar);
    if (analysis.mixed()) {
      emitMixedEpilogue(cb, dense, outputs, outputIdx, analysis, s);
      return;
    }
    // `lanes` means "how many rows this group covers" everywhere below, which for the last
    // group is the remainder - not a lane width, which is why the validity helpers switch to
    // their partial-group forms (see validityBits / orValidityBits). This one store is what
    // keeps the partial group's validity from reading or writing past the batch.
    cb.iload(analysis.lane.pLength);
    cb.iload(s.loopBound);
    cb.isub();
    cb.istore(s.lanes);
    if (s.epilogueMask != null) {
      cb.aload(s.species);
      cb.iload(s.loopBound);
      cb.iload(analysis.lane.pLength);
      cb.invokeinterface(VECTOR_SPECIES, "indexInRange", INDEX_IN_RANGE);
      cb.astore(s.epilogueMask);
    }

    emitLaneGroup(cb, dense, outputs, outputIdx, analysis, s);
  }

  /**
   * The epilogue of a kernel that converts between lanes. Its loop leaves up to two long lane
   * groups (see {@code emitSpecies}), so the masked lane group runs once per group, starting at
   * {@code loopBound}. Each time, {@code lanes} is the rows that group covers, at most a long
   * lane group, and the bounds mask is rebuilt for it.
   */
  private static void emitMixedEpilogue(CodeBuilder cb, boolean dense,
      List<VarkaVectorIR> outputs, List<Integer> outputIdx, Analysis analysis, Slots s) {
    Label top = cb.newLabel();
    Label done = cb.newLabel();
    cb.labelBinding(top);
    cb.iload(s.iVar);
    cb.iload(analysis.lane.pLength);
    cb.if_icmpge(done);
    // lanes = min(length - i, the long lane count)
    cb.iload(analysis.lane.pLength);
    cb.iload(s.iVar);
    cb.isub();
    emitLaneCount(cb, analysis, s);
    cb.invokestatic(MATH, "min", MATH_INT_PAIR);
    cb.istore(s.lanes);
    if (s.epilogueMask != null) {
      cb.aload(s.species);
      cb.iload(s.iVar);
      cb.iload(analysis.lane.pLength);
      cb.invokeinterface(VECTOR_SPECIES, "indexInRange", INDEX_IN_RANGE);
      cb.astore(s.epilogueMask);
    }
    emitLaneGroup(cb, dense, outputs, outputIdx, analysis, s);
    cb.iload(s.iVar);
    emitLaneCount(cb, analysis, s);
    cb.iadd();
    cb.istore(s.iVar);
    cb.goto_(top);
    cb.labelBinding(done);
  }

  /** The driving lane's count: the baked constant, or the species' length where none is. */
  private static void emitLaneCount(CodeBuilder cb, Analysis analysis, Slots s) {
    if (analysis.lanes != 0) {
      cb.loadConstant(analysis.lanes);
    } else {
      cb.aload(s.species);
      cb.invokeinterface(VECTOR_SPECIES, "length", SPECIES_LENGTH);
    }
  }

  /**
   * The two validity helpers, named per group shape. A whole lane group spans a power-of-two
   * number of bytes and is read or written in one access; the epilogue's partial group is not
   * a lane width at all, so it takes the {@code partial} pair, which walks the bytes it spans
   * and cannot run off a nominally sized bitmap. The descriptors are identical, so the body
   * emitters differ only in the name they pass. Getting this wrong is silent, not loud: a
   * nine-row group handed to the whole-group form reads one byte and calls its ninth row null.
   *
   * <p>A whole group also names the width, through {@link #emitValidityRead} /
   * {@link #emitValidityOr}: these two return the general forms, which stay the fallback for the
   * epilogue and for any width with no specialised sibling.
   */
  private static String validityBits(Slots s) {
    return s.epilogue ? "partialValidityBitsAt" : "validityBitsAt";
  }

  private static String orValidityBits(Slots s) {
    return s.epilogue ? "orPartialValidityBitsAt" : "orValidityBitsAt";
  }

  /**
   * Whether this call site takes the width-specialised helper: a whole lane group, in an emission
   * that baked a lane count. The epilogue's partial group never does - its row count is the batch's
   * remainder rather than a width, and it runs once per batch (twice at most, in a kernel that
   * converts between lanes), so the general form's switch costs nothing worth naming a method
   * over.
   */
  private static boolean widthSpecialised(Analysis analysis, Slots s) {
    return !s.epilogue && analysis.lanes != 0;
  }

  /**
   * This lane group's validity word for the input segment and row already on the stack. Leaves
   * one long. The specialised form takes no lane count, so it needs no {@code iload} of one.
   */
  private static void emitValidityRead(CodeBuilder cb, Analysis analysis, Slots s) {
    if (widthSpecialised(analysis, s)) {
      cb.invokestatic(SUPPORT, "validityBitsAt" + analysis.lanes, VALIDITY_BITS_AT_WIDTH);
    } else {
      cb.iload(s.lanes);
      cb.invokestatic(SUPPORT, validityBits(s), VALIDITY_BITS_AT);
    }
  }

  /** ORs the word already on the stack into the destination bitmap; the write half of the pair. */
  private static void emitValidityOr(CodeBuilder cb, Analysis analysis, Slots s) {
    if (widthSpecialised(analysis, s)) {
      cb.invokestatic(SUPPORT, "orValidityBitsAt" + analysis.lanes, OR_VALIDITY_BITS_AT_WIDTH);
    } else {
      cb.iload(s.lanes);
      cb.invokestatic(SUPPORT, orValidityBits(s), OR_VALIDITY_BITS_AT);
    }
  }

  /**
   * The store of a {@link NarrowLane} root: {@code [long vector] -> []}, four bytes a row.
   *
   * <p>The 64-bit value narrows with {@code L2I} into the int species of the <i>same width</i>
   * (part 0), so the quotients land in the low half of the int lanes and the upper half is
   * zero; the store writes the low half under a mask, at half the long lane's byte offset,
   * since the destination is an int column. The mask is {@code indexInRange(0, lanes)} on the
   * int species - the low {@code lanes} lanes, which in the loop is the long species' count and
   * in the epilogue the remainder - so one form serves both bodies and C2 folds it to a
   * constant where {@code lanes} is one. In the loop of a kernel that converts between lanes the
   * store is whole instead, its upper half's zeros landing on the next group's rows, which that
   * group stores over ({@link #emitLowHalfStore}).
   *
   * <p>Two choices are deliberate. The int species is the width's own and not a half-width
   * one: a second {@code IntVector} species in the JVM makes the shared templates inline
   * bimorphically and boxes every other int kernel in the process (`VARKA-28.md` 2.2). And
   * the mask is an int mask, which C2 lowers at every width, where the long lane's masks are
   * per-lane at two lanes (VARKA-153) - so a narrowed store costs a masked int store and
   * nothing that scalarises.
   */
  private static void emitNarrowStore(CodeBuilder cb, Analysis analysis, Slots s, int o) {
    // The half-species form (`narrowHalfSpecies`): the int species with the long lane's own
    // count, half the bits, so the converted vector is exactly the group's values and the
    // dense body stores it whole. Only where the count is baked, since the half of the
    // preferred species has no named constant.
    // Never in a kernel that converts between lanes, whose other int values are the int species
    // of the long species' width: a half-width one beside it is a second int species.
    boolean half = analysis.options.narrowHalfSpecies() && analysis.lanes != 0
        && !analysis.mixed();
    if (half) {
      String halfSpecies = Lane.INT.speciesField(analysis.lanes);
      cb.getstatic(VECTOR_OPERATORS, "L2I", VO_CONVERSION);
      cb.getstatic(INT_VECTOR, halfSpecies, VECTOR_SPECIES);
      cb.loadConstant(0);
      cb.invokevirtual(VECTOR, "convertShape", CONVERT_SHAPE);
      cb.checkcast(INT_VECTOR);
      cb.aload(s.dstSeg[o]);
      cb.iload(s.iVar);
      cb.i2l();
      cb.loadConstant(4L);
      cb.lmul();
      cb.getstatic(BYTE_ORDER, "LITTLE_ENDIAN", BYTE_ORDER);
      // Whole in a loop body, under the remainder mask in an epilogue - the same split as the
      // wide store's, and in either null mode: `dense` names the validity path, not the body.
      if (!s.epilogue) {
        cb.invokevirtual(INT_VECTOR, "intoMemorySegment", Lane.INT.intoMemorySegmentDense);
      } else {
        cb.getstatic(INT_VECTOR, halfSpecies, VECTOR_SPECIES);
        cb.loadConstant(0);
        cb.iload(s.lanes);
        cb.invokeinterface(VECTOR_SPECIES, "indexInRange", INDEX_IN_RANGE);
        cb.invokevirtual(INT_VECTOR, "intoMemorySegment", Lane.INT.intoMemorySegmentMasked);
      }
      return;
    }
    // The int species of the long species' width: twice the long lane count, or the preferred
    // species where no count is baked, which the long lane's preferred species matches in bits.
    String intSpecies = Lane.INT.speciesField(analysis.lanes == 0 ? 0 : analysis.lanes * 2);
    cb.getstatic(VECTOR_OPERATORS, "L2I", VO_CONVERSION);
    cb.getstatic(INT_VECTOR, intSpecies, VECTOR_SPECIES);
    cb.loadConstant(0);
    cb.invokevirtual(VECTOR, "convertShape", CONVERT_SHAPE);
    cb.checkcast(INT_VECTOR);                                   // [ints, low half live]
    emitLowHalfStore(cb, analysis, s, o);
  }

  /**
   * The store of an int vector whose low half holds the lane group's rows, in a kernel driven at
   * the long lane: {@code [int vector] -> []}, four bytes a row at {@code (long) i * 4}, under the
   * mask of the int species' first {@code lanes} lanes - or whole, in the loop of a kernel that
   * converts between lanes, which leaves room for it. A narrowed root's tail, and the store of
   * every int-valued root of a kernel that converts between lanes, whose int values all fill the
   * low half of their species.
   */
  private static void emitLowHalfStore(CodeBuilder cb, Analysis analysis, Slots s, int o) {
    String intSpecies = Lane.INT.speciesField(analysis.lanes == 0 ? 0 : analysis.lanes * 2);
    cb.aload(s.dstSeg[o]);
    if (analysis.mixed() && !s.epilogue) {
      // In the loop of a kernel that converts between lanes the whole int vector is stored:
      // its loop stops while a whole int vector still fits (`emitSpecies`), the upper half's
      // rows are the next lane group's, which that group stores over, and a masked store costs
      // what a masked load does.
      cb.iload(s.iVar);
      cb.i2l();
      cb.loadConstant(4L);
      cb.lmul();
      cb.getstatic(BYTE_ORDER, "LITTLE_ENDIAN", BYTE_ORDER);
      cb.invokevirtual(INT_VECTOR, "intoMemorySegment", Lane.INT.intoMemorySegmentDense);
      return;
    }
    // The int column's offset is derived from the row index the way the lane's own offset
    // is, `(long) i * 4`, and not as `byteOffset >>> 1`: C2 folds a linear function of the
    // induction variable into the store's addressing mode and hoists its bounds check out of
    // the loop, and a shift of the wide offset is neither - it cost four scalar ops, a range
    // check and the loop's unrolling per group (`VARKA-156.md`).
    cb.iload(s.iVar);
    cb.i2l();
    cb.loadConstant(4L);
    cb.lmul();                                                  // i * 4
    cb.getstatic(BYTE_ORDER, "LITTLE_ENDIAN", BYTE_ORDER);
    cb.getstatic(INT_VECTOR, intSpecies, VECTOR_SPECIES);
    cb.loadConstant(0);
    cb.iload(s.lanes);
    cb.invokeinterface(VECTOR_SPECIES, "indexInRange", INDEX_IN_RANGE);
    cb.invokevirtual(INT_VECTOR, "intoMemorySegment", Lane.INT.intoMemorySegmentMasked);
  }

  /**
   * One lane group: this group's validity words, then each output's vector walk and store.
   * Shared by the loop, which calls it per iteration, and the epilogue, which calls it once
   * with {@code s.epilogue} set (up to twice in a kernel that converts between lanes) - the only
   * difference between them inside here.
   */
  private static void emitLaneGroup(CodeBuilder cb, boolean dense,
      List<VarkaVectorIR> outputs, List<Integer> outputIdx, Analysis analysis, Slots s) {
    int numInputs = analysis.numInputs;

    // byteOffset = (long) i * 4.
    cb.iload(s.iVar);
    cb.i2l();
    cb.loadConstant(analysis.lane.byteStride);
    cb.lmul();
    cb.lstore(s.byteOffset);

    // The columns this loop method can read: the union over its own outputs' subtrees. The
    // kernel-wide referenced set would also be sound but wasteful - the word computation below
    // runs per lane group, and an input only other groups reference has no reader here.
    long groupColumns = 0L;
    for (int o : outputIdx) {
      groupColumns |= analysis.columns.get(outputs.get(o));
    }

    if (!dense) {
      // Each group-referenced input's validity word for this lane group: 0L when all-null, the
      // bitmap bits when it has nulls, -1L when null-free. All three branches leave one long.
      for (int i = 0; i < numInputs; i++) {
        if ((groupColumns >>> i & 1L) == 0 || s.deadRefs.contains(s.word[i])) {
          continue;
        }
        Label wNotDead = cb.newLabel();
        Label wNoNulls = cb.newLabel();
        Label wDone = cb.newLabel();
        cb.iload(s.dead[i]);
        cb.ifeq(wNotDead);
        cb.loadConstant(0L);
        cb.goto_(wDone);
        cb.labelBinding(wNotDead);
        cb.iload(s.hasNulls[i]);
        cb.ifeq(wNoNulls);
        cb.aload(s.srcValSeg[i]);
        cb.iload(s.iVar);
        cb.i2l();
        emitValidityRead(cb, analysis, s);
        cb.goto_(wDone);
        cb.labelBinding(wNoNulls);
        cb.loadConstant(-1L);
        cb.labelBinding(wDone);
        storeWord(cb, s, s.word[i]);
      }
    }

    // Each output of this group: the DAG post-order with intermediates on the operand stack
    // (or in a shared node's local), one unmasked store, and this lane group's validity bits -
    // the root's word (all-true when dense), which orValidityBitsAt truncates itself.
    // A Cond root writes no data at all: its output is the selection bitmap - the
    // known-true word, which is unknown-as-false by construction (kT is a subset of valid) -
    // OR-ed into dstValidity exactly where a value root ORs its validity word; the dstData
    // slot stays untouched, per the interface contract.
    Set<VarkaVectorIR> computed = new HashSet<>();
    s.emittedUnshared.clear();
    s.emittedFragments.clear();
    planFragmentsReadingMonth(outputs, outputIdx, dense, s, analysis);
    for (int o : outputIdx) {
      VarkaVectorIR root = outputs.get(o);
      if (root instanceof Cond cond) {
        emitCond(cb, cond, dense, analysis, s, computed);
        emitValidityWrite(cb, analysis, s, o, () -> {
          if (dense) {
            cb.aload(s.condMask.get(cond));
            cb.invokevirtual(VECTOR_MASK, "toLong", TO_LONG);
          } else {
            cb.lload(s.kt.get(cond));
          }
        });
        continue;
      }
      // The validity OR goes *before* the vector computation wherever its word is already known -
      // an aliased input word in the masked body, the constant in the dense one - and after it only
      // where the word is computed by the node itself (IfElse, Greatest, Least). Same bytes either
      // way; what changes is where C2's parser meets the call. Reading // the compiled loop showed
      // the OR helper a real call in every arm, refused with NodeCountInliningCutoff: the caller is
      // over C2's node budget by the time it reaches the last call in program order, after the
      // body's Vector API intrinsics have been parsed, and no size of callee changes that. Parsed
      // first, it is inlined.
      boolean validityWritten = fillsValidityOnce(analysis, dense, root)
          || servedByPass(analysis, dense, o);
      boolean wordKnownEarly = analysis.options.validityOrFirst()
          && (dense || wordKnownBeforeCompute(analysis, s, root));
      if (!validityWritten && wordKnownEarly) {
        emitRootValidityOr(cb, dense, analysis, s, o, root);
      }
      emitValue(cb, root, dense, analysis, s, computed);
      if (analysis.narrowsAtStore(root)) {
        emitNarrowStore(cb, analysis, s, o);
      } else if (analysis.laneOf(root) != analysis.lane) {
        emitLowHalfStore(cb, analysis, s, o);
      } else {
        cb.aload(s.dstSeg[o]);
        cb.lload(s.byteOffset);
        cb.getstatic(BYTE_ORDER, "LITTLE_ENDIAN", BYTE_ORDER);
        if (s.epilogue) {
          cb.aload(s.epilogueMask());
          cb.invokevirtual(analysis.lane.vector, "intoMemorySegment",
              analysis.lane.intoMemorySegmentMasked);
        } else {
          cb.invokevirtual(analysis.lane.vector, "intoMemorySegment",
              analysis.lane.intoMemorySegmentDense);
        }
      }
      if (!validityWritten && !wordKnownEarly) {
        emitRootValidityOr(cb, dense, analysis, s, o, root);
      }
    }
  }

  /**
   * Whether a masked value root's word exists before its subtree is emitted: the all-true
   * constant, or one of this lane group's input words, which the body computes first. Not
   * merely "the root computes no word of its own" - a root whose word aliases a child's
   * <i>computed</i> word ({@code Year(IfElse(...))} reads the blend's slot) is written inside
   * {@code emitValue}, and reading it earlier is a frame with no such local, which the verifier
   * rejects.
   */
  private static boolean wordKnownBeforeCompute(Analysis analysis, Slots s, VarkaVectorIR root) {
    int ref = s.wordRef.get(root);
    if (ref == WORD_ALL_TRUE) {
      return true;
    }
    for (int i = 0; i < analysis.numInputs; i++) {
      if ((s.inputs >>> i & 1L) != 0 && s.word[i] == ref) {
        return true;
      }
    }
    return false;
  }

  /** ORs a value root's validity word for this lane group into its destination bitmap. */
  private static void emitRootValidityOr(CodeBuilder cb, boolean dense, Analysis analysis,
      Slots s, int output, VarkaVectorIR root) {
    emitValidityWrite(cb, analysis, s, output, () -> {
      if (dense) {
        cb.loadConstant(-1L);
      } else {
        loadWord(cb, s, s.wordRef.get(root));
      }
    });
  }

  /**
   * This lane group's validity bits for one output, however this emission writes them.
   * {@code pushBits} leaves the group's bits as a long, lane 0 in bit 0, with anything above
   * the group's own {@code lanes} bits unspecified - the read helpers deliberately leave the
   * neighbouring rows in place, and {@link VectorMask#fromLong} ignores them.
   *
   * <p>The per-group form hands segment, row and bits to {@code orValidityBitsAt*},
   * which masks and shifts them itself. The word form does that arithmetic here,
   * because the bits go into a register rather than into memory:
   *
   * <pre>
   *   if ((i &amp; 63) == 0) acc = 0;              // a new word starts at every 64th row
   *   acc |= (bits &amp; laneMask) &lt;&lt; (i &amp; 63);
   *   putValidityWord(dstValidity, i, acc);      // the whole word, no read
   * </pre>
   *
   * <p>The mask is not optional: {@code orValidityBitsAt16} gets it for free from a narrowing
   * cast to {@code short}, and dropping it here would OR a neighbouring group's bits into this
   * one. It is a constant, since the lane count is baked wherever this form is used.
   *
   * <p>The store runs on every group rather than only on the group that completes a word, which
   * is deliberate for this arm: it keeps the store count exactly what the per-group form has,
   * so what the A/B prices is the removal of the <i>read</i> and its dependency chain, not two
   * changes at once. It also means the loop needs no flush - the last group of the last word
   * has already stored it - and that the bits above {@code loopBound} in the final word are
   * zero, which is what lets the epilogue OR its partial group in afterwards.
   */
  private static void emitValidityWrite(CodeBuilder cb, Analysis analysis, Slots s, int output,
      Runnable pushBits) {
    int acc = s.validityAcc == null ? -1 : s.validityAcc[output];
    if (acc < 0) {
      cb.aload(s.dstValSeg(output));
      cb.iload(s.iVar);
      cb.i2l();
      pushBits.run();
      emitValidityOr(cb, analysis, s);
      return;
    }
    Label started = cb.newLabel();
    cb.iload(s.iVar);
    cb.loadConstant(63);
    cb.iand();
    cb.ifne(started);
    cb.loadConstant(0L);
    cb.lstore(acc);
    cb.labelBinding(started);
    cb.lload(acc);
    pushBits.run();
    // VarkaVectorSupport.laneMask, folded here: the engine module is not on this module's
    // compile path, and every width that reaches this arm is well under 64 lanes.
    cb.loadConstant((1L << analysis.lanes) - 1L);
    cb.land();
    cb.iload(s.iVar);
    cb.loadConstant(63);
    cb.iand();
    cb.lshl();
    cb.lor();
    cb.lstore(acc);
    cb.aload(s.dstValSeg(output));
    cb.iload(s.iVar);
    cb.i2l();
    cb.lload(acc);
    cb.invokestatic(SUPPORT, "putValidityWord", PUT_VALIDITY_WORD);
  }

  /**
   * Whether this output's validity is written once by the driver rather than per lane group by
   * the loop.
   *
   * <p>Three conditions, and each is load-bearing. The option, because this is a lowering change
   * and the older form stays a reference variant the differential checks against. Dense, because
   * a masked batch is exactly the case where which rows of which output are valid is what the
   * loop computes. And not a {@link Cond}, because a condition root's validity slot is the
   * <i>selection bitmap</i> - its bits mean "known true", not "valid" - so the per-group OR
   * there is real work and stays in both bodies.
   *
   * <p>Called from the driver and from {@link #emitLaneGroup} with the same arguments, so the
   * fill and the elided OR cannot disagree: one of them writing without the other is the failure
   * that would produce an all-null column or an unzeroed one.
   */
  private static boolean fillsValidityOnce(Analysis analysis, boolean dense, VarkaVectorIR root) {
    return analysis.options.denseValidityOnce() && dense && !(root instanceof Cond);
  }

  /**
   * Whether output {@code o}'s validity is written whole by the masked driver's bitmap pass,
   * so the loop and epilogue skip its per-group OR. The same one-place discipline as
   * {@link #fillsValidityOnce}, and for the same reason: the driver's write and the elided OR
   * are decided by one predicate, read from both sides, so they cannot disagree.
   */
  private static boolean servedByPass(Analysis analysis, boolean dense, int o) {
    return !dense && analysis.served[o] != null;
  }

  /**
   * Whether a body maps output {@code o}'s validity segment ({@link #emitOutputSegments}). The
   * driver always does, for its zero, fill or bitmap pass. Under
   * {@link VarkaEmitOptions#elideUnreadLocals} a loop or epilogue body does only for an output
   * whose validity it writes: one that {@link #keepsPerGroupWrite}, or a {@link Cond} root, whose
   * selection bitmap the lane group always writes. Read by {@code Slots.plan}, which plans the
   * segment's local, and by {@link #emitOutputSegments}, which builds it, so the two cannot
   * disagree; a write through a segment never built fails at the first emission in
   * {@code Slots.dstValSeg(int)}.
   */
  static boolean buildsValiditySegment(Analysis analysis, boolean dense, BodyMode mode,
      List<VarkaVectorIR> outputs, int o) {
    return !analysis.options.elideUnreadLocals() || mode == BodyMode.DRIVER
        || outputs.get(o) instanceof Cond || keepsPerGroupWrite(analysis, dense, outputs, o);
  }

  /**
   * Whether output {@code o} still writes its validity once per lane group - neither filled by the
   * driver nor written whole by the bitmap pass. This is the driver's population, and it is
   * <i>not</i> "the masked path": {@link #servedByPass} is false for every output of a dense body
   * and {@link #fillsValidityOnce} excludes a {@link Cond} root by design, so a fused filter is in
   * it on every batch, dense included.
   */
  static boolean keepsPerGroupWrite(Analysis analysis, boolean dense,
      List<VarkaVectorIR> outputs, int o) {
    return !fillsValidityOnce(analysis, dense, outputs.get(o)) && !servedByPass(analysis, dense, o);
  }

  /**
   * Whether this emission writes destination validity a 64-bit word at a time.
   *
   * <p>Three conditions. The option, because the per-group form stays a reference variant the
   * differential checks against. A baked lane count, because the accumulator's shift is
   * {@code i & 63} against a group of exactly {@code lanes} bits and a body that reads its
   * width at run time knows neither. And a lane count that is a proper divisor of 64: a group
   * runs from {@code i} to {@code i + lanes} and must not straddle two words, and at 64 lanes
   * exactly the mask {@code (1L << lanes) - 1} is zero, since Java shifts modulo 64. Both are
   * true of every width this JVM offers for int lanes; false is the safe answer for a width
   * that arrives later.
   */
  static boolean wordWrites(Analysis analysis) {
    return analysis.options.validityByWord()
        && analysis.lanes != 0 && analysis.lanes < 64 && 64 % analysis.lanes == 0;
  }

  /**
   * One served output's whole-batch write (VARKA-70.md 3.1): {@code setValid} for the
   * constant, {@code copyColumnValidity} for one input, {@code and|orColumnValidity} for the
   * first two of a chain and {@code and|orColumnValidityInto} for each further one - the
   * left-leaning evaluation into the destination the engine's aliasing contract allows. Each
   * operand is the address and null count the kernel was called with, five bytes apiece.
   */
  private static void emitBitmapPass(CodeBuilder cb, Slots s, int o, BitmapPass pass, Lane lane) {
    int[] ords = pass.ordinals();
    cb.aload(s.dstValSeg(o));
    if (ords.length == 0) {
      cb.iload(lane.pLength);
      cb.invokestatic(SUPPORT, "setValid", SET_VALID);
      return;
    }
    if (ords.length == 1) {
      emitColumnOperand(cb, ords[0]);
      cb.iload(lane.pLength);
      cb.invokestatic(SUPPORT, "copyColumnValidity", COPY_COLUMN_VALIDITY);
      return;
    }
    String name = pass.and() ? "andColumnValidity" : "orColumnValidity";
    emitColumnOperand(cb, ords[0]);
    emitColumnOperand(cb, ords[1]);
    cb.iload(lane.pLength);
    cb.invokestatic(SUPPORT, name, COLUMN_VALIDITY_PAIR);
    for (int k = 2; k < ords.length; k++) {
      cb.aload(s.dstValSeg(o));
      emitColumnOperand(cb, ords[k]);
      cb.iload(lane.pLength);
      cb.invokestatic(SUPPORT, name + "Into", COLUMN_VALIDITY_INTO);
    }
  }

  /*
   * The steps of the plan below, as VarkaVectorSupport names them. Catalyst reaches the engine
   * only by class name at run time, so they are restated here, and VarkaEmitterDriverTableSuite
   * pins the two sets equal.
   */
  static final char PLAN_ZERO = 'z';
  static final char PLAN_ZERO_WORDS = 'Z';
  static final char PLAN_FILL = 'f';
  static final char PLAN_COPY = 'c';
  static final char PLAN_AND = '&';
  static final char PLAN_OR = '|';

  /**
   * The plan {@code VarkaVectorSupport.prepareOutputValidity} reads under
   * {@code driverOutputTable}: per output, in order, the step the unrolled driver would emit for
   * it - its bitmap pass where it is served, else a fill where {@link #fillsValidityOnce}, else a
   * zero at the size {@link #emitOutputSegments} maps its segment at. Decided by the same
   * predicates as the unrolled form, so the two write the same bits.
   */
  private static String outputPlan(Analysis analysis, boolean dense, List<VarkaVectorIR> outputs) {
    StringBuilder plan = new StringBuilder();
    for (int o = 0; o < outputs.size(); o++) {
      BitmapPass pass = servedByPass(analysis, dense, o) ? analysis.served[o] : null;
      if (pass != null) {
        int[] ords = pass.ordinals();
        if (ords.length == 0) {
          plan.append(PLAN_FILL);
        } else if (ords.length == 1) {
          plan.append(PLAN_COPY).append((char) ords[0]);
        } else {
          plan.append(pass.and() ? PLAN_AND : PLAN_OR)
              .append((char) ords.length);
          for (int ord : ords) {
            plan.append((char) ord);
          }
        }
      } else if (fillsValidityOnce(analysis, dense, outputs.get(o))) {
        plan.append(PLAN_FILL);
      } else if (wordWrites(analysis) && keepsPerGroupWrite(analysis, dense, outputs, o)) {
        plan.append(PLAN_ZERO_WORDS);
      } else {
        plan.append(PLAN_ZERO);
      }
    }
    return plan.toString();
  }

  /** The most bytes one {@code CONSTANT_Utf8} entry holds, in the class file's modified UTF-8. */
  private static final int UTF8_CONSTANT_CAP = 65535;

  /**
   * {@code table} as it is baked into the class, one string constant, or a decline where it is
   * past what one constant holds: a kernel wide enough for that - tens of thousands of outputs -
   * is past every other limit too, and a decline with the reason is what a caller acts on,
   * where the Class-File API's own refusal would not name it. Measured as the class file stores
   * it: a char of 1 to 127 takes one byte, 0 and 128 to 2047 two, the rest three.
   */
  static String tableConstant(String table, String what) {
    long bytes = 0;
    for (int i = 0; i < table.length(); i++) {
      char c = table.charAt(i);
      bytes += c >= 1 && c <= 0x7F ? 1 : c <= 0x7FF ? 2 : 3;
    }
    if (bytes > UTF8_CONSTANT_CAP) {
      throw new VarkaEmitDeclined("the driver's " + what + " table is " + bytes
          + " bytes, over the " + UTF8_CONSTANT_CAP + " one class-file constant holds",
          List.of());
    }
    return table;
  }

  /**
   * The table {@code VarkaVectorSupport.everyOutputReadsAnAllNullColumn} reads under
   * {@code driverOutputTable}: per output, the count of the columns it reads, then their
   * ordinals - the sets the unrolled shortcut ORs the {@code dead} flags of.
   */
  private static String shortcutColumns(Analysis analysis, List<VarkaVectorIR> outputs) {
    StringBuilder table = new StringBuilder();
    for (VarkaVectorIR root : outputs) {
      long set = analysis.columns.get(root);
      table.append((char) Long.bitCount(set));
      for (int i = 0; i < Long.SIZE; i++) {
        if ((set >>> i & 1L) != 0) {
          table.append((char) i);
        }
      }
    }
    return table.toString();
  }

  /** Pushes input {@code i}'s validity address and null count, as the kernel received them. */
  private static void emitColumnOperand(CodeBuilder cb, int i) {
    cb.aload(P_SRC_VALIDITY);
    cb.loadConstant(i);
    cb.laload();
    cb.aload(P_NULL_COUNT);
    cb.loadConstant(i);
    cb.iaload();
  }

  /**
   * The data segment of a {@link NarrowLane} root, {@code length * 4} bytes: its column is int32,
   * and {@code dataBytes}, the lane's stride a row, would map it at twice its size, so that the
   * bounds check on its upper half enforced nothing (VARKA-292, found by the memory sanitizer).
   * The size comes from the length and not from {@code dataBytes}, so that it does not assume the
   * lane's stride.
   */
  private static void loadNarrowedSegment(
      CodeBuilder cb, Analysis analysis, int arrayParam, int index, int destSlot) {
    cb.aload(arrayParam);
    cb.loadConstant(index);
    cb.laload();
    cb.iload(analysis.lane.pLength);
    cb.i2l();
    cb.loadConstant(4L);
    cb.lmul();
    cb.invokestatic(SUPPORT, "ofAddress", OF_ADDRESS);
    cb.astore(destSlot);
  }

  /**
   * Input {@code i}'s data segment: {@code dataBytes}, the driving lane's stride a row, or for an
   * int column of a kernel driven at the long lane four bytes a row, which is all its buffer has.
   */
  private static void loadInputSegment(CodeBuilder cb, Analysis analysis, Slots s, int i) {
    if (analysis.inputLane(i) != analysis.lane) {
      loadNarrowedSegment(cb, analysis, P_SRC_DATA, i, s.srcSeg[i]);
    } else {
      loadSegment(cb, P_SRC_DATA, i, s.dataBytes, s.srcSeg[i]);
    }
  }

  /** {@code local = VarkaVectorSupport.ofAddress(param[index], lload(bytes))}. */
  private static void loadSegment(
      CodeBuilder cb, int arrayParam, int index, int bytesSlot, int destSlot) {
    cb.aload(arrayParam);
    cb.loadConstant(index);
    cb.laload();
    cb.lload(bytesSlot);
    cb.invokestatic(SUPPORT, "ofAddress", OF_ADDRESS);
    cb.astore(destSlot);
  }

  /**
   * The {@code LineNumberTable}'s decoding key: one {@code <line>=<node>} entry per distinct
   * IR node, newline separated, in the topological order the line numbers index.
   * Recorded in {@link VarkaDebugInfo} so the mapping travels inside the class bytes.
   *
   * <p>Nodes render through {@link VarkaVectorIR#canonicalShallow}, which exists for this, and not
   * through {@link Record#toString}, whose format no JDK promises and which inlines each node's
   * whole subtree - a shared subexpression would be repeated once per parent and the key would
   * grow quadratically in the sharing the emitter is built to exploit. Children are their own line
   * numbers here, so the key reconstructs the DAG and each node is written once.
   */
  static String renderLineMap(Analysis analysis) {
    StringBuilder key = new StringBuilder();
    for (int i = 0; i < analysis.topoOrder.size(); i++) {
      if (i > 0) {
        key.append('\n');
      }
      VarkaVectorIR node = analysis.topoOrder.get(i);
      key.append(i + 1).append('=')
          .append(VarkaVectorIR.canonicalShallow(node, analysis.lineNumbers::get));
    }
    return key.toString();
  }

  /**
   * The whole IR as one line for {@link VarkaDebugInfo}'s summary field - the full recursive
   * {@link VarkaVectorIR#canonical} rendering per output, for the same reason the line map uses
   * the shallow one: {@code Record.toString} is not a format anything may depend on.
   */
  static String renderOutputs(List<VarkaVectorIR> outputs) {
    StringBuilder rendered = new StringBuilder("[");
    for (int i = 0; i < outputs.size(); i++) {
      if (i > 0) {
        rendered.append(", ");
      }
      rendered.append(VarkaVectorIR.canonical(outputs.get(i)));
    }
    return rendered.append(']').toString();
  }

  /**
   * The word invariant over one loop or epilogue body, checked when its lane group has been
   * emitted: every word the body stored was loaded at least once, and every word it loaded was one
   * it stored. The first half is what the liveness rule will make load-bearing - a word defined and
   * never read is per-group work the pass was meant to remove; the second is a verifier error
   * stated in the emitter's own terms. Both hold on the emitter as it stood before the pass, which
   * the whole suite and the fuzzer establish by running.
   */
  private static void assertWordsLive(Slots s, BodyMode mode) {
    for (int ref : s.wordDefs) {
      if (!s.wordUses.containsKey(ref)) {
        throw VarkaErrors.illegalState("word slot " + ref + " is stored but never loaded in a "
            + mode + " body: a consumer the liveness inventory lists is not emitted, or the "
            + "word is dead and should not have been computed");
      }
    }
    for (int ref : s.wordUses.keySet()) {
      if (!s.wordDefs.contains(ref)) {
        throw VarkaErrors.illegalState("word slot " + ref + " is loaded but never stored in a "
            + mode + " body: a consumer the liveness inventory missed");
      }
    }
  }

  /** The three body-method roles; see the method-layout note in {@link VarkaLoopEmitter#emit}. */
  enum BodyMode { DRIVER, LOOP, EPILOGUE }

  /**
   * {@code this.<name>(srcData, ..., length)} - all seven parameters forwarded, and the scratch
   * address after them in an emission that materializes a prefix.
   */
  static void invokeCall(CodeBuilder cb, ClassDesc classDesc, String name, Analysis analysis) {
    Lane lane = analysis.lane;
    cb.aload(0);
    cb.aload(P_SRC_DATA);
    cb.aload(P_SRC_VALIDITY);
    cb.aload(P_NULL_COUNT);
    cb.aload(P_DST_DATA);
    cb.aload(P_DST_VALIDITY);
    cb.aload(P_SCALAR_ARGS);
    if (lane.pLongArgs >= 0) {
      cb.aload(lane.pLongArgs);
    }
    cb.iload(lane.pLength);
    if (analysis.hasScratch()) {
      cb.lload(analysis.scratchParam());
    }
    cb.invokespecial(classDesc, name, analysis.bodyDesc());
  }

  /** {@code throw new <exception>(message)}. */
  static void emitThrow(CodeBuilder cb, ClassDesc exception, String message) {
    cb.new_(exception);
    cb.dup();
    cb.loadConstant(message);
    cb.invokespecial(exception, "<init>",
        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String));
    cb.athrow();
  }
}
