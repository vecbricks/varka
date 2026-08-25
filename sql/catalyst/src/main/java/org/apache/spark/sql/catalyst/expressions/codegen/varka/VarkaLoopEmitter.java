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

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddDays;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DateDiff;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.SubDays;

/**
 * Emits a fused vector loop for a {@link VarkaVectorIR} DAG with the Class-File API
 * (milestone 2, tasks 9 and 10): a class implementing {@link VarkaFusedKernel} whose {@code run}
 * is the loop itself - masked loads, the op DAG on the operand stack, one masked store per
 * output - mirroring the hand-written {@code DateVectorOps} kernels shape for shape. The kernels
 * remain the reference semantics; this class exists so a whole projection runs in one pass with
 * its intermediates in vector registers, which no fixed set of single-op kernels can do.
 *
 * <p>The emitted method follows the six-step kernel shape documented on {@code DateVectorOps},
 * generalized to many inputs and outputs: empty-batch guard, nominally sized segments,
 * unconditional zeroing of <i>every</i> destination validity, all-null shortcut (taken only when
 * every output reads at least one all-null column), lane-group loop to {@code loopBound}, scalar
 * tail agreeing row for row. Everything loop-invariant - segments, scalar arguments, species,
 * lane count, each input's null state - is hoisted into locals in the prologue.
 * {@code IntVector.SPECIES_PREFERRED} is read with {@code getstatic}, which keeps it a JIT
 * constant; that is what lets C2 intrinsify the emitted Vector API calls, and task 9's parity
 * gate verified it does.
 *
 * <p><b>Twin bodies</b> (task 10, plan 2.5): {@code run} is a dispatcher over two private
 * sibling methods, and one loop-invariant test selects per batch. When every referenced input
 * is null-free {@code runDense} runs - unmasked loads, ops and stores, no mask work at all -
 * measured at 2.3x to 2.9x the masked body running with an all-true mask, because a runtime
 * mask is opaque to C2 and a masked store stays a masked store even when every lane is on. Any
 * batch with nulls takes {@code runMasked}. Sibling <i>methods</i>, not two loops in one
 * method: each body gets its own C2 compilation, so one body's node and inlining budgets
 * cannot starve the other's intrinsics - measured as a 3x to 4x loss on the second-emitted
 * loop when both shared one method.
 *
 * <p>Literal <i>broadcasts</i> are hoisted into vector locals only in the regime task 9
 * measured them as a win - one output, at most {@link #MAX_CHAIN_DEPTH} literals. Any wider
 * body emits the broadcast at each use instead: a Java local alive across the loop pins one
 * vector register per literal for the whole body, and past roughly two dozen live vectors the
 * register allocator spills them - measured as a 7x collapse on a 32-literal two-output shape.
 * Emitted at the use, the broadcast is still loop-invariant (its input is a hoisted scalar
 * local), so C2 hoists it when registers allow and rematerializes it - one instruction from a
 * scalar register - when they do not; the same shape ran 3.4x faster that way and wide shapes
 * scale with their op count. Numbers in PLAN_TASK_10.md.
 *
 * <p><b>Mask algebra</b> (task 10): per lane group, each referenced input contributes one
 * validity <i>word</i> - {@code 0L} for an all-null column, {@code -1L} for a null-free one,
 * {@code validityBitsAt} otherwise. Every op this emitter serves is null-intolerant, so a node's
 * mask is the AND of the words of the columns its subtree references; the words are combined in
 * long arithmetic and {@code VectorMask.fromLong} runs once per <i>distinct referenced-column
 * set</i>, not once per node - a single-column chain builds exactly one mask, as task 9 did. An
 * all-null input therefore zeroes the masks of every node that reads it: stores write nothing,
 * validity stays zeroed, and the output correctly reads as all-null with no dedicated dead-output
 * code. Task 11's null-skipping and blending ops replace cases of this per-node rule, not a
 * global assumption.
 *
 * <p><b>DAG-CSE</b> (task 10): nodes are memoized on their structural {@code equals} while the
 * loop body is emitted. The first computation of a node used more than once lands in a local;
 * later uses - in the same output or another - are an {@code aload}. Column loads are the same
 * mechanism ({@link ColumnRef} is a node like any other), so each referenced column is loaded
 * once per lane group however many outputs read it. Single-use intermediates stay on the operand
 * stack, exactly as in task 9: a local is paid for only where sharing exists.
 *
 * <p>Every call the loop makes is declared once in the descriptor table below - erasure is this
 * milestone's named risk ({@code IntVector.add} takes the <i>erased</i> {@code Vector}), and a
 * wrong descriptor must be found by pointing at one line, not by disassembling the output.
 *
 * <p>Out-of-shape IR - unknown lane types, out-of-range ordinals or slots, non-literal day
 * offsets, trees past {@link #MAX_CHAIN_DEPTH} or {@link #MAX_FUSED_NODES} - is rejected with
 * {@link IllegalArgumentException}, which the evaluator wiring treats as "fall back".
 */
public final class VarkaLoopEmitter {

  /**
   * The deepest op path (root to leaf, per output) the emitter accepts, fixed by measurement
   * (VarkaEmitterParityBenchmark, 1M mixed-null rows; details in PLAN_TASK_9.md). Fused
   * throughput is nearly flat with depth and shows no spill cliff at either vector width:
   * 7.9 to 5.6 G rows/s from depth 1 to 16 at 16 lanes, 2.5 to 1.8 G rows/s at 4 lanes - a
   * roughly 30% decline at depth 16, from the one live broadcast register each literal adds,
   * while sequential kernel passes collapse linearly (45x slower than fused at depth 16). The
   * cap therefore bounds emitted method size and register pressure by policy, well past any
   * depth a real projection produces, rather than marking a measured performance edge.
   */
  public static final int MAX_CHAIN_DEPTH = 16;

  /**
   * The most distinct op nodes one emitted loop may hold, across all outputs after CSE
   * (task 10). Depth alone no longer bounds method size once outputs multiply, so this is the
   * total-size counterpart of {@link #MAX_CHAIN_DEPTH}: the same policy bound on bytecode size
   * and register pressure, far past any real projection, kept honest by the widest-shape case
   * in the parity benchmark.
   */
  public static final int MAX_FUSED_NODES = 64;

  /**
   * The most input columns one emitted loop may read. A node's referenced-column set is a long
   * bitset, which fixes the representation limit at 64; real projections reference a handful.
   */
  public static final int MAX_INPUTS = 64;

  /**
   * Test hook: when set, {@link AddDays} is emitted against a deliberately wrong descriptor
   * (unerased {@code IntVector} parameter instead of {@code Vector}). The class still passes
   * bytecode verification - member resolution happens at link time - so the failure surfaces on
   * first execution as a {@link NoSuchMethodError} naming {@code IntVector.add}. The suite pins
   * that, so a future descriptor regression is diagnosable from the error alone.
   */
  static volatile boolean misdescribeAddForTesting = false;

  /**
   * Test hook: when set, the node memo is disabled and shared subtrees are recomputed at every
   * use. Results must not change - CSE is an optimization, never a semantics change - and the
   * suite pins exactly that; the parity benchmark uses it to price CSE itself.
   */
  static volatile boolean disableCseForTesting = false;

  private VarkaLoopEmitter() {
  }

  // ---------------------------------------------------------------------------------------------
  // Descriptor table: the single source of truth for everything the emitted code calls.
  // ---------------------------------------------------------------------------------------------

  private static final ClassDesc MEMORY_SEGMENT =
      ClassDesc.of("java.lang.foreign.MemorySegment");
  private static final ClassDesc VALUE_LAYOUT =
      ClassDesc.of("java.lang.foreign.ValueLayout");
  private static final ClassDesc VALUE_LAYOUT_OF_INT =
      ClassDesc.ofDescriptor("Ljava/lang/foreign/ValueLayout$OfInt;");
  private static final ClassDesc BYTE_ORDER = ClassDesc.of("java.nio.ByteOrder");
  private static final ClassDesc INT_VECTOR = ClassDesc.of("jdk.incubator.vector.IntVector");
  private static final ClassDesc VECTOR = ClassDesc.of("jdk.incubator.vector.Vector");
  private static final ClassDesc VECTOR_MASK = ClassDesc.of("jdk.incubator.vector.VectorMask");
  private static final ClassDesc VECTOR_SPECIES =
      ClassDesc.of("jdk.incubator.vector.VectorSpecies");
  private static final ClassDesc SUPPORT =
      ClassDesc.of("org.apache.spark.sql.varka.vector.VarkaVectorSupport");
  private static final ClassDesc FUSED_KERNEL = ClassDesc.of(VarkaFusedKernel.class.getName());

  private static final ClassDesc LONG_ARRAY = ConstantDescs.CD_long.arrayType();
  private static final ClassDesc INT_ARRAY = ConstantDescs.CD_int.arrayType();

  /** {@code void run(long[], long[], int[], long[], long[], int[], int)}. */
  private static final MethodTypeDesc RUN = MethodTypeDesc.of(ConstantDescs.CD_void,
      LONG_ARRAY, LONG_ARRAY, INT_ARRAY, LONG_ARRAY, LONG_ARRAY, INT_ARRAY,
      ConstantDescs.CD_int);
  private static final MethodTypeDesc INIT = MethodTypeDesc.of(ConstantDescs.CD_void);

  /** {@code MemorySegment VarkaVectorSupport.ofAddress(long, long)}. */
  private static final MethodTypeDesc OF_ADDRESS =
      MethodTypeDesc.of(MEMORY_SEGMENT, ConstantDescs.CD_long, ConstantDescs.CD_long);
  /** {@code void VarkaVectorSupport.zero(MemorySegment)}. */
  private static final MethodTypeDesc ZERO =
      MethodTypeDesc.of(ConstantDescs.CD_void, MEMORY_SEGMENT);
  /** {@code long VarkaVectorSupport.validityBitsAt(MemorySegment, long, int)}. */
  private static final MethodTypeDesc VALIDITY_BITS_AT = MethodTypeDesc.of(
      ConstantDescs.CD_long, MEMORY_SEGMENT, ConstantDescs.CD_long, ConstantDescs.CD_int);
  /** {@code void VarkaVectorSupport.orValidityBitsAt(MemorySegment, long, long, int)}. */
  private static final MethodTypeDesc OR_VALIDITY_BITS_AT = MethodTypeDesc.of(
      ConstantDescs.CD_void, MEMORY_SEGMENT, ConstantDescs.CD_long, ConstantDescs.CD_long,
      ConstantDescs.CD_int);
  /** {@code boolean VarkaVectorSupport.isBitSet(MemorySegment, int)}. */
  private static final MethodTypeDesc IS_BIT_SET =
      MethodTypeDesc.of(ConstantDescs.CD_boolean, MEMORY_SEGMENT, ConstantDescs.CD_int);
  /** {@code void VarkaVectorSupport.setBit(MemorySegment, int)}. */
  private static final MethodTypeDesc SET_BIT =
      MethodTypeDesc.of(ConstantDescs.CD_void, MEMORY_SEGMENT, ConstantDescs.CD_int);

  /** {@code int VectorSpecies.length()} / {@code int VectorSpecies.loopBound(int)}. */
  private static final MethodTypeDesc SPECIES_LENGTH = MethodTypeDesc.of(ConstantDescs.CD_int);
  private static final MethodTypeDesc LOOP_BOUND =
      MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_int);
  /** {@code IntVector IntVector.broadcast(VectorSpecies, int)} (static). */
  private static final MethodTypeDesc BROADCAST =
      MethodTypeDesc.of(INT_VECTOR, VECTOR_SPECIES, ConstantDescs.CD_int);
  /** {@code VectorMask VectorMask.fromLong(VectorSpecies, long)} (static). */
  private static final MethodTypeDesc FROM_LONG =
      MethodTypeDesc.of(VECTOR_MASK, VECTOR_SPECIES, ConstantDescs.CD_long);
  /**
   * {@code IntVector IntVector.fromMemorySegment(VectorSpecies, MemorySegment, long, ByteOrder,
   * VectorMask)} (static).
   */
  private static final MethodTypeDesc FROM_MEMORY_SEGMENT = MethodTypeDesc.of(INT_VECTOR,
      VECTOR_SPECIES, MEMORY_SEGMENT, ConstantDescs.CD_long, BYTE_ORDER, VECTOR_MASK);
  /**
   * {@code IntVector IntVector.add(Vector, VectorMask)} - the parameter is the *erased*
   * {@code Vector}, not {@code IntVector}; the covariant return stays {@code IntVector}.
   * {@code sub} has the same shape and also serves {@link DateDiff} ({@code end - start}).
   */
  private static final MethodTypeDesc LANEWISE_MASKED =
      MethodTypeDesc.of(INT_VECTOR, VECTOR, VECTOR_MASK);
  /** The deliberately wrong shape behind {@link #misdescribeAddForTesting}. */
  private static final MethodTypeDesc LANEWISE_MASKED_WRONG =
      MethodTypeDesc.of(INT_VECTOR, INT_VECTOR, VECTOR_MASK);
  // The dense body's unmasked counterparts (task 10, plan 2.5): same calls, no mask operand.
  /** {@code IntVector.fromMemorySegment(VectorSpecies, MemorySegment, long, ByteOrder)}. */
  private static final MethodTypeDesc FROM_MEMORY_SEGMENT_DENSE = MethodTypeDesc.of(INT_VECTOR,
      VECTOR_SPECIES, MEMORY_SEGMENT, ConstantDescs.CD_long, BYTE_ORDER);
  /** {@code IntVector IntVector.add/sub(Vector)} - erased parameter, as in the masked shape. */
  private static final MethodTypeDesc LANEWISE_DENSE =
      MethodTypeDesc.of(INT_VECTOR, VECTOR);
  /** The dense counterpart of {@link #LANEWISE_MASKED_WRONG} for the misdescribe hook. */
  private static final MethodTypeDesc LANEWISE_DENSE_WRONG =
      MethodTypeDesc.of(INT_VECTOR, INT_VECTOR);
  /** {@code void IntVector.intoMemorySegment(MemorySegment, long, ByteOrder)}. */
  private static final MethodTypeDesc INTO_MEMORY_SEGMENT_DENSE = MethodTypeDesc.of(
      ConstantDescs.CD_void, MEMORY_SEGMENT, ConstantDescs.CD_long, BYTE_ORDER);
  /** {@code void IntVector.intoMemorySegment(MemorySegment, long, ByteOrder, VectorMask)}. */
  private static final MethodTypeDesc INTO_MEMORY_SEGMENT = MethodTypeDesc.of(
      ConstantDescs.CD_void, MEMORY_SEGMENT, ConstantDescs.CD_long, BYTE_ORDER, VECTOR_MASK);
  /** {@code int MemorySegment.get(ValueLayout.OfInt, long)}. */
  private static final MethodTypeDesc SEGMENT_GET_INT = MethodTypeDesc.of(
      ConstantDescs.CD_int, VALUE_LAYOUT_OF_INT, ConstantDescs.CD_long);
  /** {@code void MemorySegment.set(ValueLayout.OfInt, long, int)}. */
  private static final MethodTypeDesc SEGMENT_SET_INT = MethodTypeDesc.of(ConstantDescs.CD_void,
      VALUE_LAYOUT_OF_INT, ConstantDescs.CD_long, ConstantDescs.CD_int);

  // Parameter slots of `run` (instance method: `this` is slot 0, finding 11's lesson).
  private static final int P_SRC_DATA = 1;
  private static final int P_SRC_VALIDITY = 2;
  private static final int P_NULL_COUNT = 3;
  private static final int P_DST_DATA = 4;
  private static final int P_DST_VALIDITY = 5;
  private static final int P_SCALAR_ARGS = 6;
  private static final int P_LENGTH = 7;

  /**
   * Assembles the fused-kernel class for the given output trees over {@code numInputs} columns
   * and {@code numLiterals} scalar-argument slots. Output {@code o} writes
   * {@code dstData[o]}/{@code dstValidity[o]}; a {@link ColumnRef} ordinal indexes the
   * {@code src*} arrays.
   *
   * @throws IllegalArgumentException if the IR is outside what this emitter serves - the caller
   *         is expected to fall back to the per-row projection, exactly as a kernel failure
   *         does.
   */
  public static byte[] emit(
      String className, List<VarkaVectorIR> outputs, int numInputs, int numLiterals) {
    if (outputs.isEmpty()) {
      throw new IllegalArgumentException("no output chains to emit");
    }
    if (numInputs < 1 || numInputs > MAX_INPUTS) {
      throw new IllegalArgumentException(
          "numInputs " + numInputs + " outside [1, " + MAX_INPUTS + "]");
    }
    Analysis analysis = new Analysis(numInputs, numLiterals);
    for (VarkaVectorIR root : outputs) {
      analysis.analyzeRoot(root);
    }

    // The dense and masked bodies are separate private methods, not two loops in one method:
    // each gets its own C2 compilation, so the node and inlining budgets of one cannot starve
    // the other. Measured with both bodies in one `run`: the second-emitted (masked) loop lost
    // 3x to 4x on nulled batches; as sibling methods both run at full speed (PLAN_TASK_10.md).
    ClassDesc classDesc = ClassDesc.of(className);
    boolean anyColumns = analysis.referencedColumns != 0;
    return ClassFile.of().build(classDesc, (ClassBuilder b) -> {
      b.withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL)
          .withInterfaceSymbols(FUSED_KERNEL)
          .withMethodBody("<init>", INIT, AccessFlag.PUBLIC.mask(), (CodeBuilder cb) -> {
            cb.aload(0);
            cb.invokespecial(ConstantDescs.CD_Object, "<init>", INIT);
            cb.return_();
          })
          .withMethodBody("run", RUN, AccessFlag.PUBLIC.mask(),
              (CodeBuilder cb) -> emitDispatch(cb, classDesc, analysis))
          .withMethodBody("runDense", RUN, AccessFlag.PRIVATE.mask(),
              (CodeBuilder cb) -> emitBody(cb, true, outputs, analysis, numLiterals));
      if (anyColumns) {
        b.withMethodBody("runMasked", RUN, AccessFlag.PRIVATE.mask(),
            (CodeBuilder cb) -> emitBody(cb, false, outputs, analysis, numLiterals));
      }
    });
  }

  /**
   * The public {@code run}: one loop-invariant test per batch - are all referenced inputs
   * null-free? - selecting {@code runDense} or {@code runMasked} (plan 2.5). The dense body
   * measured 2.3x to 2.9x the masked body running with an all-true mask: a runtime mask is
   * opaque to C2, and a masked store stays a masked store even when every lane is on.
   */
  private static void emitDispatch(CodeBuilder cb, ClassDesc classDesc, Analysis analysis) {
    Label masked = cb.newLabel();
    boolean anyColumns = analysis.referencedColumns != 0;
    for (int i = 0; i < analysis.numInputs; i++) {
      if (referenced(analysis, i)) {
        cb.aload(P_NULL_COUNT);
        cb.loadConstant(i);
        cb.iaload();
        cb.ifne(masked);
      }
    }
    invokeBody(cb, classDesc, "runDense");
    if (anyColumns) {
      cb.labelBinding(masked);
      invokeBody(cb, classDesc, "runMasked");
    }
    // With no referenced columns the masked label is never targeted and must not be bound:
    // unreachable code has no stack frame to compute.
  }

  /** {@code return this.<name>(srcData, ..., length)} - all seven parameters forwarded. */
  private static void invokeBody(CodeBuilder cb, ClassDesc classDesc, String name) {
    cb.aload(0);
    cb.aload(P_SRC_DATA);
    cb.aload(P_SRC_VALIDITY);
    cb.aload(P_NULL_COUNT);
    cb.aload(P_DST_DATA);
    cb.aload(P_DST_VALIDITY);
    cb.aload(P_SCALAR_ARGS);
    cb.iload(P_LENGTH);
    cb.invokespecial(classDesc, name, RUN);
    cb.return_();
  }

  // ---------------------------------------------------------------------------------------------
  // Validation and DAG analysis.
  // ---------------------------------------------------------------------------------------------

  /**
   * One walk over the output trees, before any bytecode exists: validates every node, counts
   * uses on structural equality (the DAG view of trees the caller may have built independently),
   * and computes per node the referenced-column bitset its mask is the AND over, plus its
   * height for the depth cap.
   */
  private static final class Analysis {
    final int numInputs;
    final int numLiterals;
    /** Distinct nodes in first-visit order, with how often each is used. */
    final Map<VarkaVectorIR, Integer> useCount = new LinkedHashMap<>();
    /** Per distinct node, the bitset of input ordinals its subtree references. */
    final Map<VarkaVectorIR, Long> columns = new HashMap<>();
    private final Map<VarkaVectorIR, Integer> height = new HashMap<>();
    /** Distinct referenced-column sets, in first-appearance order: one mask each per group. */
    final LinkedHashSet<Long> maskSets = new LinkedHashSet<>();
    /** The union of every node's columns: unreferenced inputs get no locals and no state. */
    long referencedColumns = 0L;
    private int opNodes = 0;

    Analysis(int numInputs, int numLiterals) {
      this.numInputs = numInputs;
      this.numLiterals = numLiterals;
    }

    void analyzeRoot(VarkaVectorIR root) {
      analyze(root);
      if (height.get(root) > MAX_CHAIN_DEPTH) {
        throw new IllegalArgumentException(
            "chain deeper than MAX_CHAIN_DEPTH=" + MAX_CHAIN_DEPTH);
      }
    }

    private void analyze(VarkaVectorIR node) {
      if (node.laneType() != VarkaVectorIR.LaneType.INT) {
        throw new IllegalArgumentException("unsupported lane type " + node.laneType());
      }
      Integer seen = useCount.get(node);
      if (seen != null) {
        // A repeated node: its subtree is already analyzed, only the use count grows. The
        // children's counts do not - a shared subtree is computed once, so its inputs are
        // consumed once.
        useCount.put(node, seen + 1);
        return;
      }
      useCount.put(node, 1);
      switch (node) {
        case ColumnRef c -> {
          if (c.ordinal() < 0 || c.ordinal() >= numInputs) {
            throw new IllegalArgumentException(
                "column ordinal " + c.ordinal() + " outside [0, " + numInputs + ")");
          }
          long set = 1L << c.ordinal();
          columns.put(node, set);
          height.put(node, 0);
          referencedColumns |= set;
          maskSets.add(set);
        }
        case LiteralSlot l -> {
          if (l.index() < 0 || l.index() >= numLiterals) {
            throw new IllegalArgumentException(
                "literal slot " + l.index() + " outside [0, " + numLiterals + ")");
          }
          columns.put(node, 0L);
          height.put(node, 0);
        }
        case AddDays n -> analyzeOp(node, n.days(), n.offset(), true);
        case SubDays n -> analyzeOp(node, n.days(), n.offset(), true);
        case DateDiff n -> analyzeOp(node, n.end(), n.start(), false);
      }
    }

    private void analyzeOp(
        VarkaVectorIR node, VarkaVectorIR left, VarkaVectorIR right, boolean literalRight) {
      if (literalRight && !(right instanceof LiteralSlot)) {
        throw new IllegalArgumentException("day offsets must be literal slots, got " + right);
      }
      opNodes++;
      if (opNodes > MAX_FUSED_NODES) {
        throw new IllegalArgumentException(
            "more than MAX_FUSED_NODES=" + MAX_FUSED_NODES + " distinct ops");
      }
      analyze(left);
      analyze(right);
      long set = columns.get(left) | columns.get(right);
      columns.put(node, set);
      height.put(node, 1 + Math.max(height.get(left), height.get(right)));
      maskSets.add(set);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The emitted body methods.
  // ---------------------------------------------------------------------------------------------

  /**
   * One complete body method - prologue, lane-group loop, scalar tail. The dense variant runs
   * only when the dispatcher has proven every referenced input null-free, so it emits no
   * per-input null state, no all-null shortcut and no mask work at all; the masked variant is
   * the general one, and the two must agree row for row wherever both could run.
   */
  private static void emitBody(CodeBuilder cb, boolean dense, List<VarkaVectorIR> outputs,
      Analysis analysis, int numLiterals) {
    int numInputs = analysis.numInputs;
    int numOutputs = outputs.size();
    boolean cse = !disableCseForTesting;

    // Local slot layout, all allocated up front; longs take two slots.
    int slot = 8;
    final int dataBytes = slot;
    slot += 2;
    final int validityBytes = slot;
    slot += 2;
    final int[] dstSeg = new int[numOutputs];
    final int[] dstValSeg = new int[numOutputs];
    for (int o = 0; o < numOutputs; o++) {
      dstSeg[o] = slot++;
      dstValSeg[o] = slot++;
    }
    // Per referenced input: data segment, validity segment (or null), and the hoisted null
    // state - dead (all-null) and hasNulls as int flags. Unreferenced inputs get nothing.
    final int[] srcSeg = new int[numInputs];
    final int[] srcValSeg = new int[numInputs];
    final int[] dead = new int[numInputs];
    final int[] hasNulls = new int[numInputs];
    final int[] word = new int[numInputs];
    for (int i = 0; i < numInputs; i++) {
      if (referenced(analysis, i)) {
        srcSeg[i] = slot++;
        srcValSeg[i] = slot++;
        dead[i] = slot++;
        hasNulls[i] = slot++;
        word[i] = slot;
        slot += 2;
      }
    }
    final int ncTmp = slot++;
    final int species = slot++;
    final int lanes = slot++;
    final int loopBound = slot++;
    final int[] scalarArg = new int[numLiterals];
    for (int j = 0; j < numLiterals; j++) {
      scalarArg[j] = slot++;
    }
    // Broadcasts are hoisted into vector locals only in the regime task 9 measured them as a
    // win: one output, at most a chain's worth of literals. Any wider body inlines them at each
    // use instead and lets C2 rematerialize under register pressure. See the class doc;
    // numbers in PLAN_TASK_10.md.
    final int[] broadcastSlot =
        numOutputs == 1 && numLiterals <= MAX_CHAIN_DEPTH ? new int[numLiterals] : null;
    if (broadcastSlot != null) {
      for (int j = 0; j < numLiterals; j++) {
        broadcastSlot[j] = slot++;
      }
    }
    final int iVar = slot++;
    final int byteOffset = slot;
    slot += 2;
    // Per distinct referenced-column set: its combined validity word and its VectorMask. A
    // singleton set's word IS the input's word - no combining, no second local.
    final Map<Long, Integer> maskWordSlot = new HashMap<>();
    final Map<Long, Integer> maskVarSlot = new HashMap<>();
    for (long set : analysis.maskSets) {
      if (Long.bitCount(set) == 1) {
        maskWordSlot.put(set, word[Long.numberOfTrailingZeros(set)]);
      } else {
        maskWordSlot.put(set, slot);
        slot += 2;
      }
      maskVarSlot.put(set, slot++);
    }
    // Per node used more than once: the local its first computation lands in (DAG-CSE).
    // Literal slots are already locals (the hoisted broadcasts) and need no second one.
    final Map<VarkaVectorIR, Integer> sharedSlot = new HashMap<>();
    if (cse) {
      for (Map.Entry<VarkaVectorIR, Integer> e : analysis.useCount.entrySet()) {
        if (e.getValue() > 1 && !(e.getKey() instanceof LiteralSlot)) {
          sharedSlot.put(e.getKey(), slot++);
        }
      }
    }

    // (1) if (length <= 0) return;
    Label nonEmpty = cb.newLabel();
    cb.iload(P_LENGTH);
    cb.ifgt(nonEmpty);
    cb.return_();
    cb.labelBinding(nonEmpty);

    // (2) Nominal sizes: dataBytes = (long) length * 4; validityBytes = (length + 7) / 8L.
    cb.iload(P_LENGTH);
    cb.i2l();
    cb.loadConstant(4L);
    cb.lmul();
    cb.lstore(dataBytes);
    cb.iload(P_LENGTH);
    cb.loadConstant(7);
    cb.iadd();
    cb.i2l();
    cb.loadConstant(8L);
    cb.ldiv();
    cb.lstore(validityBytes);

    // (3) Per output: segments, and zero(dstValidity) before any return below - the emitter
    // invariant: an output nothing writes must still read as all-null.
    for (int o = 0; o < numOutputs; o++) {
      loadSegment(cb, P_DST_DATA, o, dataBytes, dstSeg[o]);
      loadSegment(cb, P_DST_VALIDITY, o, validityBytes, dstValSeg[o]);
      cb.aload(dstValSeg[o]);
      cb.invokestatic(SUPPORT, "zero", ZERO);
    }

    // (4) Per referenced input: null state (masked body only - the dispatcher has proven a
    // dense batch null-free) and the data segment. An all-null input's validity address is 0L
    // by the morsel contract, so its segment must not be materialized; its validity word is 0L
    // in every group instead, which nulls everything computed from it.
    for (int i = 0; i < numInputs; i++) {
      if (!referenced(analysis, i)) {
        continue;
      }
      if (dense) {
        loadSegment(cb, P_SRC_DATA, i, dataBytes, srcSeg[i]);
        continue;
      }
      cb.aload(P_NULL_COUNT);
      cb.loadConstant(i);
      cb.iaload();
      cb.istore(ncTmp);
      Label notDead = cb.newLabel();
      Label stateDone = cb.newLabel();
      cb.iload(ncTmp);
      cb.iload(P_LENGTH);
      cb.if_icmpne(notDead);
      cb.loadConstant(1);
      cb.istore(dead[i]);
      cb.loadConstant(0);
      cb.istore(hasNulls[i]);
      cb.aconst_null();
      cb.astore(srcValSeg[i]);
      cb.goto_(stateDone);
      cb.labelBinding(notDead);
      cb.loadConstant(0);
      cb.istore(dead[i]);
      Label noNulls = cb.newLabel();
      cb.iload(ncTmp);
      cb.ifle(noNulls);
      cb.loadConstant(1);
      cb.istore(hasNulls[i]);
      cb.aload(P_SRC_VALIDITY);
      cb.loadConstant(i);
      cb.laload();
      cb.lload(validityBytes);
      cb.invokestatic(SUPPORT, "ofAddress", OF_ADDRESS);
      cb.astore(srcValSeg[i]);
      cb.goto_(stateDone);
      cb.labelBinding(noNulls);
      cb.loadConstant(0);
      cb.istore(hasNulls[i]);
      cb.aconst_null();
      cb.astore(srcValSeg[i]);
      cb.labelBinding(stateDone);
      loadSegment(cb, P_SRC_DATA, i, dataBytes, srcSeg[i]);
    }

    // (5) All-null shortcut, generalized: return iff every output reads at least one all-null
    // column. Statically skipped in the dense body (nothing is null there) and when some
    // output references no column at all (a literal-only tree is never dead). For one output
    // over one input this is task 9's `nullCount == length`.
    boolean anyColumnFree = false;
    for (VarkaVectorIR root : outputs) {
      anyColumnFree |= analysis.columns.get(root) == 0L;
    }
    if (!dense && !anyColumnFree) {
      Label live = cb.newLabel();
      boolean firstOutput = true;
      for (VarkaVectorIR root : outputs) {
        long set = analysis.columns.get(root);
        boolean firstColumn = true;
        for (int i = 0; i < numInputs; i++) {
          if ((set >>> i & 1L) != 0) {
            cb.iload(dead[i]);
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
      cb.return_();
      cb.labelBinding(live);
    }

    // Species, lane count, loop bound, and one broadcast per literal - all hoisted (LICM).
    cb.getstatic(INT_VECTOR, "SPECIES_PREFERRED", VECTOR_SPECIES);
    cb.astore(species);
    cb.aload(species);
    cb.invokeinterface(VECTOR_SPECIES, "length", SPECIES_LENGTH);
    cb.istore(lanes);
    cb.aload(species);
    cb.iload(P_LENGTH);
    cb.invokeinterface(VECTOR_SPECIES, "loopBound", LOOP_BOUND);
    cb.istore(loopBound);
    for (int j = 0; j < numLiterals; j++) {
      cb.aload(P_SCALAR_ARGS);
      cb.loadConstant(j);
      cb.iaload();
      cb.istore(scalarArg[j]);
      if (broadcastSlot != null) {
        cb.aload(species);
        cb.iload(scalarArg[j]);
        cb.invokestatic(INT_VECTOR, "broadcast", BROADCAST);
        cb.astore(broadcastSlot[j]);
      }
    }

    // (6) The loop and tail of this body's variant.
    Slots slots = new Slots(srcSeg, srcValSeg, dead, hasNulls, word, dstSeg, dstValSeg,
        species, lanes, loopBound, scalarArg, broadcastSlot, iVar, byteOffset,
        maskWordSlot, maskVarSlot, sharedSlot);
    emitLoopAndTail(cb, dense, outputs, analysis, slots);
  }

  /** The local-variable slots one emitted {@code run} uses, threaded to the body emitters. */
  private static final class Slots {
    final int[] srcSeg;
    final int[] srcValSeg;
    final int[] dead;
    final int[] hasNulls;
    final int[] word;
    final int[] dstSeg;
    final int[] dstValSeg;
    final int species;
    final int lanes;
    final int loopBound;
    final int[] scalarArg;
    final int[] broadcastSlot;
    final int iVar;
    final int byteOffset;
    final Map<Long, Integer> maskWordSlot;
    final Map<Long, Integer> maskVarSlot;
    final Map<VarkaVectorIR, Integer> sharedSlot;

    Slots(int[] srcSeg, int[] srcValSeg, int[] dead, int[] hasNulls, int[] word,
        int[] dstSeg, int[] dstValSeg, int species, int lanes, int loopBound,
        int[] scalarArg, int[] broadcastSlot, int iVar, int byteOffset,
        Map<Long, Integer> maskWordSlot, Map<Long, Integer> maskVarSlot,
        Map<VarkaVectorIR, Integer> sharedSlot) {
      this.srcSeg = srcSeg;
      this.srcValSeg = srcValSeg;
      this.dead = dead;
      this.hasNulls = hasNulls;
      this.word = word;
      this.dstSeg = dstSeg;
      this.dstValSeg = dstValSeg;
      this.species = species;
      this.lanes = lanes;
      this.loopBound = loopBound;
      this.scalarArg = scalarArg;
      this.broadcastSlot = broadcastSlot;
      this.iVar = iVar;
      this.byteOffset = byteOffset;
      this.maskWordSlot = maskWordSlot;
      this.maskVarSlot = maskVarSlot;
      this.sharedSlot = sharedSlot;
    }
  }

  /**
   * One complete body - the lane-group loop plus the scalar tail, ending in {@code return} -
   * in one of two variants. The dense variant is entered only when every referenced input is
   * null-free: no validity words, no masks, unmasked loads, lanewise ops and stores, all-true
   * destination validity, and a tail with no per-row validity checks. The masked variant is
   * the general one, and the two must agree row for row wherever both could run.
   */
  private static void emitLoopAndTail(CodeBuilder cb, boolean dense,
      List<VarkaVectorIR> outputs, Analysis analysis, Slots s) {
    int numInputs = analysis.numInputs;
    int numOutputs = outputs.size();

    // The lane-group loop: for (i = 0; i < loopBound; i += lanes).
    cb.loadConstant(0);
    cb.istore(s.iVar);
    Label loopTop = cb.newLabel();
    Label loopEnd = cb.newLabel();
    cb.labelBinding(loopTop);
    cb.iload(s.iVar);
    cb.iload(s.loopBound);
    cb.if_icmpge(loopEnd);

    // byteOffset = (long) i * 4.
    cb.iload(s.iVar);
    cb.i2l();
    cb.loadConstant(4L);
    cb.lmul();
    cb.lstore(s.byteOffset);

    if (!dense) {
      // Each referenced input's validity word for this group: 0L when all-null, the bitmap
      // bits when it has nulls, -1L when null-free. All three branches leave one long for the
      // merge.
      for (int i = 0; i < numInputs; i++) {
        if (!referenced(analysis, i)) {
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
        cb.iload(s.lanes);
        cb.invokestatic(SUPPORT, "validityBitsAt", VALIDITY_BITS_AT);
        cb.goto_(wDone);
        cb.labelBinding(wNoNulls);
        cb.loadConstant(-1L);
        cb.labelBinding(wDone);
        cb.lstore(s.word[i]);
      }

      // One combined word and one VectorMask per distinct referenced-column set (the mask
      // algebra: AND, because every op here is null-intolerant). Singleton sets alias the
      // input's own word; the empty set is all-true.
      for (long set : analysis.maskSets) {
        int bits = Long.bitCount(set);
        if (bits == 0) {
          cb.loadConstant(-1L);
          cb.lstore(s.maskWordSlot.get(set));
        } else if (bits > 1) {
          boolean first = true;
          for (int i = 0; i < numInputs; i++) {
            if ((set >>> i & 1L) != 0) {
              cb.lload(s.word[i]);
              if (!first) {
                cb.land();
              }
              first = false;
            }
          }
          cb.lstore(s.maskWordSlot.get(set));
        }
        cb.aload(s.species);
        cb.lload(s.maskWordSlot.get(set));
        cb.invokestatic(VECTOR_MASK, "fromLong", FROM_LONG);
        cb.astore(s.maskVarSlot.get(set));
      }
    }

    // Each output: the DAG post-order with intermediates on the operand stack (or in a shared
    // node's local), one store, and this group's validity bits - the root's word (all-true
    // when dense), which orValidityBitsAt truncates to the lane count itself.
    Set<VarkaVectorIR> computed = new HashSet<>();
    for (int o = 0; o < numOutputs; o++) {
      VarkaVectorIR root = outputs.get(o);
      emitVector(cb, root, dense, analysis, s, computed);
      long rootSet = analysis.columns.get(root);
      cb.aload(s.dstSeg[o]);
      cb.lload(s.byteOffset);
      cb.getstatic(BYTE_ORDER, "LITTLE_ENDIAN", BYTE_ORDER);
      if (dense) {
        cb.invokevirtual(INT_VECTOR, "intoMemorySegment", INTO_MEMORY_SEGMENT_DENSE);
      } else {
        cb.aload(s.maskVarSlot.get(rootSet));
        cb.invokevirtual(INT_VECTOR, "intoMemorySegment", INTO_MEMORY_SEGMENT);
      }
      cb.aload(s.dstValSeg[o]);
      cb.iload(s.iVar);
      cb.i2l();
      if (dense) {
        cb.loadConstant(-1L);
      } else {
        cb.lload(s.maskWordSlot.get(rootSet));
      }
      cb.iload(s.lanes);
      cb.invokestatic(SUPPORT, "orValidityBitsAt", OR_VALIDITY_BITS_AT);
    }

    cb.iload(s.iVar);
    cb.iload(s.lanes);
    cb.iadd();
    cb.istore(s.iVar);
    cb.goto_(loopTop);
    cb.labelBinding(loopEnd);

    // Scalar tail: per row, per output - in the masked body the row is served iff every
    // column the output references is valid there (the same AND, in boolean form); the dense
    // body has no checks to make. Shared subtrees are recomputed: scalar recomputation is
    // cheaper than bookkeeping over at most one lane group of rows.
    Label tailTop = cb.newLabel();
    Label tailEnd = cb.newLabel();
    cb.labelBinding(tailTop);
    cb.iload(s.iVar);
    cb.iload(P_LENGTH);
    cb.if_icmpge(tailEnd);
    for (int o = 0; o < numOutputs; o++) {
      VarkaVectorIR root = outputs.get(o);
      long set = analysis.columns.get(root);
      Label rowDone = cb.newLabel();
      if (!dense) {
        for (int i = 0; i < numInputs; i++) {
          if ((set >>> i & 1L) != 0) {
            cb.iload(s.dead[i]);
            cb.ifne(rowDone);
            Label bitOk = cb.newLabel();
            cb.iload(s.hasNulls[i]);
            cb.ifeq(bitOk);
            cb.aload(s.srcValSeg[i]);
            cb.iload(s.iVar);
            cb.invokestatic(SUPPORT, "isBitSet", IS_BIT_SET);
            cb.ifeq(rowDone);
            cb.labelBinding(bitOk);
          }
        }
      }
      // dstSeg.set(JAVA_INT, (long) i * 4, <scalar tree>); setBit(dstValSeg, i).
      cb.aload(s.dstSeg[o]);
      cb.getstatic(VALUE_LAYOUT, "JAVA_INT", VALUE_LAYOUT_OF_INT);
      cb.iload(s.iVar);
      cb.i2l();
      cb.loadConstant(4L);
      cb.lmul();
      emitScalar(cb, root, s.srcSeg, s.iVar, s.scalarArg);
      cb.invokeinterface(MEMORY_SEGMENT, "set", SEGMENT_SET_INT);
      cb.aload(s.dstValSeg[o]);
      cb.iload(s.iVar);
      cb.invokestatic(SUPPORT, "setBit", SET_BIT);
      cb.labelBinding(rowDone);
    }
    cb.iinc(s.iVar, 1);
    cb.goto_(tailTop);
    cb.labelBinding(tailEnd);
    cb.return_();
  }

  private static boolean referenced(Analysis analysis, int ordinal) {
    return (analysis.referencedColumns >>> ordinal & 1L) != 0;
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
   * Post-order walk leaving the node's {@code IntVector} on the operand stack. A node used more
   * than once is computed at its first (textual) use, duplicated into its local, and later uses
   * load the local - across outputs too, since the loop body is one straight line.
   */
  private static void emitVector(CodeBuilder cb, VarkaVectorIR node, boolean dense,
      Analysis analysis, Slots s, Set<VarkaVectorIR> computed) {
    Integer shared = s.sharedSlot.get(node);
    if (shared != null && computed.contains(node)) {
      cb.aload(shared);
      return;
    }
    switch (node) {
      case ColumnRef c -> {
        cb.aload(s.species);
        cb.aload(s.srcSeg[c.ordinal()]);
        cb.lload(s.byteOffset);
        cb.getstatic(BYTE_ORDER, "LITTLE_ENDIAN", BYTE_ORDER);
        if (dense) {
          cb.invokestatic(INT_VECTOR, "fromMemorySegment", FROM_MEMORY_SEGMENT_DENSE);
        } else {
          cb.aload(s.maskVarSlot.get(1L << c.ordinal()));
          cb.invokestatic(INT_VECTOR, "fromMemorySegment", FROM_MEMORY_SEGMENT);
        }
      }
      case LiteralSlot l -> {
        if (s.broadcastSlot != null) {
          cb.aload(s.broadcastSlot[l.index()]);
        } else {
          cb.aload(s.species);
          cb.iload(s.scalarArg[l.index()]);
          cb.invokestatic(INT_VECTOR, "broadcast", BROADCAST);
        }
      }
      case AddDays n -> {
        emitVector(cb, n.days(), dense, analysis, s, computed);
        emitVector(cb, n.offset(), dense, analysis, s, computed);
        // The misdescribe hook covers both variants: whichever body executes first must fail
        // naming the call.
        if (dense) {
          MethodTypeDesc desc =
              misdescribeAddForTesting ? LANEWISE_DENSE_WRONG : LANEWISE_DENSE;
          cb.invokevirtual(INT_VECTOR, "add", desc);
        } else {
          cb.aload(s.maskVarSlot.get(analysis.columns.get(node)));
          MethodTypeDesc desc =
              misdescribeAddForTesting ? LANEWISE_MASKED_WRONG : LANEWISE_MASKED;
          cb.invokevirtual(INT_VECTOR, "add", desc);
        }
      }
      case SubDays n -> {
        emitVector(cb, n.days(), dense, analysis, s, computed);
        emitVector(cb, n.offset(), dense, analysis, s, computed);
        emitLanewiseSub(cb, dense, analysis, node, s);
      }
      case DateDiff n -> {
        emitVector(cb, n.end(), dense, analysis, s, computed);
        emitVector(cb, n.start(), dense, analysis, s, computed);
        emitLanewiseSub(cb, dense, analysis, node, s);
      }
    }
    if (shared != null) {
      cb.dup();
      cb.astore(shared);
      computed.add(node);
    }
  }

  /** The {@code sub} call both {@link SubDays} and {@link DateDiff} lower to. */
  private static void emitLanewiseSub(
      CodeBuilder cb, boolean dense, Analysis analysis, VarkaVectorIR node, Slots s) {
    if (dense) {
      cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_DENSE);
    } else {
      cb.aload(s.maskVarSlot.get(analysis.columns.get(node)));
      cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_MASKED);
    }
  }

  /** The scalar-tail walk, leaving the row's {@code int} on the operand stack. */
  private static void emitScalar(
      CodeBuilder cb, VarkaVectorIR node, int[] srcSeg, int iVar, int[] scalarArg) {
    switch (node) {
      case ColumnRef c -> {
        cb.aload(srcSeg[c.ordinal()]);
        cb.getstatic(VALUE_LAYOUT, "JAVA_INT", VALUE_LAYOUT_OF_INT);
        cb.iload(iVar);
        cb.i2l();
        cb.loadConstant(4L);
        cb.lmul();
        cb.invokeinterface(MEMORY_SEGMENT, "get", SEGMENT_GET_INT);
      }
      case LiteralSlot l -> cb.iload(scalarArg[l.index()]);
      case AddDays n -> {
        emitScalar(cb, n.days(), srcSeg, iVar, scalarArg);
        emitScalar(cb, n.offset(), srcSeg, iVar, scalarArg);
        cb.iadd();
      }
      case SubDays n -> {
        emitScalar(cb, n.days(), srcSeg, iVar, scalarArg);
        emitScalar(cb, n.offset(), srcSeg, iVar, scalarArg);
        cb.isub();
      }
      case DateDiff n -> {
        emitScalar(cb, n.end(), srcSeg, iVar, scalarArg);
        emitScalar(cb, n.start(), srcSeg, iVar, scalarArg);
        cb.isub();
      }
    }
  }
}
