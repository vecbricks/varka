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

import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDescriptors.*;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitBudget.*;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaLoopEmitter.*;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorWalk.*;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.constant.ClassDesc;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.Analysis.BitmapPass;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaLoopEmitter.BodyMode;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;

/**
 * The methods of the emitted class, and the lane-group step they all run.
 *
 * <p>{@link #emitBody} emits one method in one of the three {@link BodyMode} roles. A driver is
 * the shared prologue - segment addresses, the per-input null state, the all-null shortcut -
 * followed by calls to its sibling loop methods, one per output group, and to the epilogue; a
 * loop method is that prologue and the vector loop over full lane groups; the epilogue is the
 * loop body run once, masked, over the rows left past {@code loopBound}. Each has a dense and a
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
   * One body method in one of the three roles of the method layout (see
   * {@link VarkaLoopEmitter#emit}). The dense variants run only when the dispatcher has proven
   * every referenced input null-free, so they emit no all-null shortcut and no validity words;
   * the masked variants are the general ones, and the pairs must agree wherever both could run.
   * Every method re-derives the prologue state from the same seven parameters; only the driver
   * zeroes the destination validity (the loop and epilogue methods run after bits were written
   * and must not), and the epilogue starts its single pass at {@code loopBound}.
   */
  static void emitBody(CodeBuilder cb, boolean dense, BodyMode mode, int group,
      ClassDesc classDesc, List<VarkaVectorIR> outputs, Analysis analysis, int numLiterals,
      List<List<Integer>> groups) {
    int numInputs = analysis.numInputs;
    int numOutputs = outputs.size();
    List<Integer> all = new java.util.ArrayList<>();
    for (int o = 0; o < numOutputs; o++) {
      all.add(o);
    }
    // A loop method is always one group's; the epilogue is one group's under the byte budget
    // and every output's without it (the method layout in VarkaLoopEmitter.emit); the driver
    // is every output's.
    List<Integer> bodyOutputs = group >= 0 ? groups.get(group) : all;
    // Task 87: under the byte budget a group's method sets up only what its group writes and
    // reads, so its size is the group's and not the kernel's. The driver still owns every
    // output - it zeroes each validity bitmap and runs the bitmap pass - so it keeps the
    // whole-kernel prologue whichever way the option is set.
    boolean perGroup = mode != BodyMode.DRIVER && analysis.options.methodByteBudget() > 0;
    if (perGroup && group < 0) {
      throw new IllegalArgumentException(
          "a " + mode + " body under the byte budget is one group's");
    }
    Slots s = Slots.plan(dense, mode, outputs, bodyOutputs, analysis, numLiterals, perGroup);
    List<Integer> prologueOutputs = perGroup ? bodyOutputs : all;

    // (1) if (length <= 0) return 0 - nothing ran, so there is nothing to report.
    Label nonEmpty = cb.newLabel();
    cb.iload(analysis.lane.pLength);
    cb.ifgt(nonEmpty);
    cb.loadConstant(0);
    cb.ireturn();
    cb.labelBinding(nonEmpty);

    // (2) Nominal sizes: dataBytes = (long) length * 4; validityBytes = (length + 7) / 8L.
    cb.iload(analysis.lane.pLength);
    cb.i2l();
    cb.loadConstant(analysis.lane.byteStride);
    cb.lmul();
    cb.lstore(s.dataBytes);
    cb.iload(analysis.lane.pLength);
    cb.loadConstant(7);
    cb.iadd();
    cb.i2l();
    cb.loadConstant(8L);
    cb.ldiv();
    cb.lstore(s.validityBytes);

    // (3) Per output: segments, and - in the driver only - zero(dstValidity) before any
    // return below, the emitter invariant: an output nothing writes must still read as
    // all-null. The loop and epilogue methods run after bits were written and must not. A
    // Cond root's data address is 0L by the interface contract and must not be materialized
    // (the same rule as an all-null input's validity address); zeroing its bitmap doubles
    // as the selection invariant - an unwritten row reads as unselected.
    for (int o : prologueOutputs) {
      if (!(outputs.get(o) instanceof Cond)) {
        loadSegment(cb, P_DST_DATA, o, s.dataBytes, s.dstSeg[o]);
      }
      // an output this emission writes a word at a time needs the segment to own the whole last
      // word, ((length + 63) / 64) * 8 bytes, where the nominal (length + 7) / 8 is short of it for
      // every length not a multiple of 64. The Arrow buffer behind it carries that at every length
      // (VarkaKernelEvaluatorSuite), and the driver's zero below then covers exactly the bytes the
      // loop stores. Every other output keeps the nominal size, so an emission that word-writes
      // nothing keeps its bytes.
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
      // an output the bitmap pass serves is written whole between steps (4) and (5) below - after
      // the null state it reads exists and before the shortcut can return - and that write is what
      // keeps this step's invariant for it, not a zero it overwrites.
      if (mode == BodyMode.DRIVER && !servedByPass(analysis, dense, o)) {
        cb.aload(s.dstValSeg[o]);
        if (fillsValidityOnce(analysis, dense, outputs.get(o))) {
          // on a dense batch every value output is valid on every row, so the bits are known here
          // and the loop's per-lane-group OR is writing ones over ones. Setting them once costs a
          // fill of the same bytes this zero would have touched.
          cb.iload(analysis.lane.pLength);
          cb.invokestatic(SUPPORT, "setValid", SET_VALID);
        } else {
          cb.invokestatic(SUPPORT, "zero", ZERO);
        }
      }
    }

    // (4) Per referenced input: null state (masked body only - the dispatcher has proven a
    // dense batch null-free) and the data segment. An all-null input's validity address is 0L
    // by the morsel contract, so its segment must not be materialized; its validity word is 0L
    // in every group instead, which nulls everything computed from it.
    for (int i = 0; i < numInputs; i++) {
      if (!referenced(analysis, i)) {
        continue;
      }
      // a loop or epilogue body whose every reader of this input's word is gone needs none of its
      // null state either. This is what makes such a body the dense one's bytes.
      //
      // The driver still derives all of it, and most of that is dead there: `hasNulls[i]`,
      // `srcValSeg[i]` and `srcSeg[i]` are read only inside `emitLaneGroup` and `emitValue`, which
      // only a loop or epilogue body calls, so in the masked driver they are written and never
      // read; `dead[i]` is read by the all-null shortcut alone, and is dead too on any shape that
      // emits no shortcut - a `Cond` root, a null-skipping root, an output over no column. The
      // liveness pass this task added is what could remove it, but the driver is planned with `live
      // = null` (see Slots.plan) and this is deliberately not that change: it is the residue
      // PLAN_TASK_70.md 9.2 prediction 3 measures and leaves // to the driver.
      if (dense || s.deadRefs.contains(s.word[i])) {
        loadSegment(cb, P_SRC_DATA, i, s.dataBytes, s.srcSeg[i]);
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
      loadSegment(cb, P_SRC_DATA, i, s.dataBytes, s.srcSeg[i]);
    }

    // (4b) The bitmap pass (see PLAN_TASK_70.md 3.1): for each served output, its validity written
    // whole from the input bitmaps, here and not per lane group. Between (4) and (5) on purpose -
    // the null counts it passes are read in (4), and a batch the shortcut returns from in (5) must
    // already have every served bitmap written, since nothing after (5) runs for it. The engine
    // resolves each operand's three states, so this is one call per node of the flattened
    // expression and no branch: arguments straight from the kernel's parameters.
    if (!dense && mode == BodyMode.DRIVER) {
      for (int o = 0; o < numOutputs; o++) {
        BitmapPass pass = analysis.served[o];
        if (pass != null) {
          emitBitmapPass(cb, s, o, pass, analysis.lane);
        }
      }
    }

    // (5) All-null shortcut: return iff every output reads at least one all-null column.
    // Sound only for null-intolerant outputs - a null-skipping subtree (greatest, IfElse) can
    // be valid over an all-null column - and emitted in the masked driver only (the dense
    // body has nothing null; the loop and epilogue methods never run when it fires), and
    // only when every output references a column. A Cond root is excluded outright
    // rather than reasoned about: Or(unknown, known-true) is known true, so an OR over one
    // all-null column and one live one still selects rows, which the zeroed bitmap the
    // shortcut leaves behind would deny. The loop needs no shortcut to be correct there -
    // an all-null input's word is 0L, so its side contributes no known-true bits.
    boolean shortcutApplies = !dense && mode == BodyMode.DRIVER;
    for (VarkaVectorIR root : outputs) {
      shortcutApplies &= analysis.columns.get(root) != 0L && !analysis.skipping.get(root)
          && !(root instanceof Cond);
    }
    if (shortcutApplies) {
      Label live = cb.newLabel();
      boolean firstOutput = true;
      for (VarkaVectorIR root : outputs) {
        long set = analysis.columns.get(root);
        boolean firstColumn = true;
        for (int i = 0; i < numInputs; i++) {
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

    // Species, lane count, loop bound, and the hoisted scalar arguments (LICM). The species is
    // read with getstatic so it stays a JIT constant - what lets C2 intrinsify the calls.
    //
    // Which species: the concrete one this emission was built for where the width-specialised
    // validity helpers are in use, so the class cannot disagree with the helper names beside it,
    // and the lane count is a bytecode constant rather than a call. Otherwise SPECIES_PREFERRED and
    // its length(), which is what a width with no specialised helpers does.
    cb.getstatic(analysis.lane.vector, analysis.lane.speciesField(analysis.lanes),
        VECTOR_SPECIES);
    cb.astore(s.species);
    if (analysis.lanes != 0) {
      cb.loadConstant(analysis.lanes);
    } else {
      cb.aload(s.species);
      cb.invokeinterface(VECTOR_SPECIES, "length", SPECIES_LENGTH);
    }
    cb.istore(s.lanes);
    cb.aload(s.species);
    cb.iload(analysis.lane.pLength);
    cb.invokeinterface(VECTOR_SPECIES, "loopBound", LOOP_BOUND);
    cb.istore(s.loopBound);
    for (int j = 0; j < numLiterals; j++) {
      if (s.scalarArg[j] < 0) {
        continue; // a literal no output of this group reads (per-group planning, task 87)
      }
      cb.aload(analysis.lane.scalarArgsSlot());
      cb.loadConstant(j);
      analysis.lane.arrayLoad(cb);
      analysis.lane.storeScalar(cb, s.scalarArg[j]);
      if (s.broadcastSlot != null) {
        cb.aload(s.species);
        analysis.lane.loadScalar(cb, s.scalarArg[j]);
        cb.invokestatic(analysis.lane.vector, "broadcast", analysis.lane.broadcast);
        cb.astore(s.broadcastSlot[j]);
      }
    }

    if (s.guardAcc != null) {
      // An empty mask: no lane has been found out of range yet.
      cb.aload(s.species);
      cb.loadConstant(0L);
      cb.invokestatic(VECTOR_MASK, "fromLong", FROM_LONG);
      cb.astore(s.guardAcc);
    }

    switch (mode) {
      case DRIVER -> {
        // Every callee returns a status; the batch's is their union, so one out-of-range lane
        // anywhere condemns the whole batch - which is what the caller acts on.
        cb.loadConstant(0);
        cb.istore(s.status);
        for (int g = 0; g < groups.size(); g++) {
          cb.iload(s.status);
          invokeCall(cb, classDesc, (dense ? "loopDense" : "loopMasked") + g, analysis.lane);
          cb.ior();
          cb.istore(s.status);
        }
        // The rows past loopBound belong to the sibling epilogue: one method, or one per group
        // under the byte budget. Each epilogue keeps its own even-batch return rather than the
        // driver testing once for all of them: the calls that return at once are what warm the
        // method up on a scan whose batches mostly divide evenly (PLAN_TASK_87.md 2.6.3).
        String epilogue = dense ? "epilogueDense" : "epilogueMasked";
        if (analysis.options.methodByteBudget() > 0) {
          for (int g = 0; g < groups.size(); g++) {
            cb.iload(s.status);
            invokeCall(cb, classDesc, epilogue + g, analysis.lane);
            cb.ior();
            cb.istore(s.status);
          }
          cb.iload(s.status);
        } else {
          cb.iload(s.status);
          invokeCall(cb, classDesc, epilogue, analysis.lane);
          cb.ior();
        }
        cb.ireturn();
      }
      case LOOP -> {
        emitVectorLoop(cb, dense, outputs, bodyOutputs, analysis, s);
        assertWordsLive(s, mode);
        emitStatusReturn(cb, s);
      }
      case EPILOGUE -> {
        // The loop body run once over the partial lane group past loopBound - the same shape
        // the scalar tail it replaces had - for every output, or for one group's under the
        // byte budget.
        emitEpilogue(cb, dense, outputs, bodyOutputs, analysis, s);
        assertWordsLive(s, mode);
        emitStatusReturn(cb, s);
      }
    }
  }

  /**
   * Ends a loop or epilogue method with its status: a constant zero where nothing is guarded,
   * and otherwise whether any lane the body saw fell outside the lowering's range. The
   * reduction is once per method, not once per lane group - the accumulator is a mask OR in
   * the loop, which is one op.
   */
  private static void emitStatusReturn(CodeBuilder cb, Slots s) {
    if (s.guardAcc == null) {
      cb.loadConstant(0);
      cb.ireturn();
      return;
    }
    Label clean = cb.newLabel();
    cb.aload(s.guardAcc);
    cb.invokevirtual(VECTOR_MASK, "anyTrue", ANY_TRUE);
    cb.ifeq(clean);
    cb.loadConstant(VarkaFusedKernel.STATUS_CHRONO_RANGE);
    cb.ireturn();
    cb.labelBinding(clean);
    cb.loadConstant(0);
    cb.ireturn();
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
    // `lanes` means "how many rows this group covers" everywhere below, which for the last
    // group is the remainder - not a lane width, which is why the validity helpers switch to
    // their partial-group forms (see validityBits / orValidityBits). This one store is what
    // keeps the partial group's validity from reading or writing past the batch.
    cb.iload(analysis.lane.pLength);
    cb.iload(s.loopBound);
    cb.isub();
    cb.istore(s.lanes);
    cb.aload(s.species);
    cb.iload(s.loopBound);
    cb.iload(analysis.lane.pLength);
    cb.invokeinterface(VECTOR_SPECIES, "indexInRange", INDEX_IN_RANGE);
    cb.astore(s.epilogueMask);

    emitLaneGroup(cb, dense, outputs, outputIdx, analysis, s);
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
    return s.epilogueMask != null ? "partialValidityBitsAt" : "validityBitsAt";
  }

  private static String orValidityBits(Slots s) {
    return s.epilogueMask != null ? "orPartialValidityBitsAt" : "orValidityBitsAt";
  }

  /**
   * Whether this call site takes the width-specialised helper: a whole lane group, in an emission
   * that baked a lane count. The epilogue's partial group never does - its row count is the batch's
   * remainder rather than a width, and it runs once per batch, so the general form's switch costs
   * nothing worth naming a method over.
   */
  private static boolean widthSpecialised(Analysis analysis, Slots s) {
    return s.epilogueMask == null && analysis.lanes != 0;
  }

  /**
   * This lane group's validity word for the input segment and row already on the stack. Leaves
   * one long. The specialised form takes no lane count, so the {@code iload} disappears with
   * the switch it used to feed.
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
   * One lane group: this group's validity words, then each output's vector walk and store.
   * Shared by the loop, which calls it per iteration, and the epilogue, which calls it once
   * with {@code s.epilogueMask} set - the only difference between them inside here.
   */
  /**
   * The store of a {@link NarrowLane} root: {@code [long vector] -> []}, four bytes a row.
   *
   * <p>The 64-bit value narrows with {@code L2I} into the int species of the <i>same width</i>
   * (part 0), so the quotients land in the low half of the int lanes and the upper half is
   * zero; the store writes the low half under a mask, at half the long lane's byte offset,
   * since the destination is an int column. The mask is {@code indexInRange(0, lanes)} on the
   * int species - the low {@code lanes} lanes, which in the loop is the long species' count and
   * in the epilogue the remainder - so one form serves both bodies and C2 folds it to a
   * constant where {@code lanes} is one.
   *
   * <p>Two choices are deliberate. The int species is the width's own and not a half-width
   * one: a second {@code IntVector} species in the JVM makes the shared templates inline
   * bimorphically and boxes every other int kernel in the process (`PLAN_TASK_28.md` 2.2). And
   * the mask is an int mask, which C2 lowers at every width, where the long lane's masks are
   * per-lane at two lanes (task 153) - so a narrowed store costs a masked int store and
   * nothing that scalarises.
   */
  private static void emitNarrowStore(CodeBuilder cb, Analysis analysis, Slots s, int o) {
    // The half-species form (`narrowHalfSpecies`): the int species with the long lane's own
    // count, half the bits, so the converted vector is exactly the group's values and the
    // dense body stores it whole. Only where the count is baked, since the half of the
    // preferred species has no named constant.
    boolean half = analysis.options.narrowHalfSpecies() && analysis.lanes != 0;
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
      if (s.epilogueMask == null) {
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
    cb.aload(s.dstSeg[o]);
    // The int column's offset is derived from the row index the way the lane's own offset
    // is, `(long) i * 4`, and not as `byteOffset >>> 1`: C2 folds a linear function of the
    // induction variable into the store's addressing mode and hoists its bounds check out of
    // the loop, and a shift of the wide offset is neither - it cost four scalar ops, a range
    // check and the loop's unrolling per group (`PLAN_TASK_156.md`).
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
    s.emittedFragments.clear();
    planFragmentsReadingMonth(outputs, outputIdx, dense, s);
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
      if (root instanceof NarrowLane) {
        emitNarrowStore(cb, analysis, s, o);
      } else {
        cb.aload(s.dstSeg[o]);
        cb.lload(s.byteOffset);
        cb.getstatic(BYTE_ORDER, "LITTLE_ENDIAN", BYTE_ORDER);
        if (s.epilogueMask != null) {
          cb.aload(s.epilogueMask);
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
      if ((analysis.referencedColumns >>> i & 1L) != 0 && s.word[i] == ref) {
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
      cb.aload(s.dstValSeg[output]);
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
    cb.aload(s.dstValSeg[output]);
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
   * One served output's whole-batch write (PLAN_TASK_70.md 3.1): {@code setValid} for the
   * constant, {@code copyColumnValidity} for one input, {@code and|orColumnValidity} for the
   * first two of a chain and {@code and|orColumnValidityInto} for each further one - the
   * left-leaning evaluation into the destination the engine's aliasing contract allows. Each
   * operand is the address and null count the kernel was called with, five bytes apiece.
   */
  private static void emitBitmapPass(CodeBuilder cb, Slots s, int o, BitmapPass pass, Lane lane) {
    int[] ords = pass.ordinals();
    cb.aload(s.dstValSeg[o]);
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
      cb.aload(s.dstValSeg[o]);
      emitColumnOperand(cb, ords[k]);
      cb.iload(lane.pLength);
      cb.invokestatic(SUPPORT, name + "Into", COLUMN_VALIDITY_INTO);
    }
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
   * <p>Nodes render through {@link VarkaVectorIR#canonicalShallow}, which exists for this: the key
   * used to be built from {@link Record#toString}, whose format no JDK promises, and which inlined
   * each node's whole subtree - so a shared subexpression was repeated once per parent and the key
   * grew quadratically in the sharing the emitter is built to exploit. Children are their own line
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
        throw new IllegalStateException("word slot " + ref + " is stored but never loaded in a "
            + mode + " body: a consumer the liveness inventory lists is not emitted, or the "
            + "word is dead and should not have been computed");
      }
    }
    for (int ref : s.wordUses.keySet()) {
      if (!s.wordDefs.contains(ref)) {
        throw new IllegalStateException("word slot " + ref + " is loaded but never stored in a "
            + mode + " body: a consumer the liveness inventory missed");
      }
    }
  }
}
