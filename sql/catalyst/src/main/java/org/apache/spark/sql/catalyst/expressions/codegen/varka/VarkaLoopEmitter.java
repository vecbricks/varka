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
import java.util.List;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddDays;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.SubDays;

/**
 * Emits a fused vector loop for a {@link VarkaVectorIR} chain with the Class-File API
 * (milestone 2, task 9): a class implementing {@link VarkaFusedKernel} whose {@code run} is the
 * loop itself - masked load, the op chain on the operand stack, masked store, validity write -
 * mirroring the hand-written {@code DateVectorOps} kernels shape for shape. The kernels remain
 * the reference semantics; this class exists so a *chain* of ops runs in one pass with its
 * intermediates in vector registers, which no fixed set of single-op kernels can do.
 *
 * <p>The emitted method follows the six-step kernel shape documented on {@code DateVectorOps}:
 * empty-batch guard, nominally sized segments, unconditional destination-validity zeroing,
 * all-null shortcut, masked lane-group loop to {@code loopBound}, scalar tail agreeing row for
 * row. Everything loop-invariant - segments, broadcasts, species, lane count - is hoisted into
 * locals in the prologue. {@code IntVector.SPECIES_PREFERRED} is read with {@code getstatic},
 * which keeps it a JIT constant; that is what lets C2 intrinsify the emitted Vector API calls,
 * and the task's parity gate exists to verify it did.
 *
 * <p>Every call the loop makes is declared once in the descriptor table below - erasure is this
 * milestone's named risk ({@code IntVector.add} takes the <i>erased</i> {@code Vector}), and a
 * wrong descriptor must be found by pointing at one line, not by disassembling the output.
 *
 * <p>Task 9 scope: one output, one input column (ordinal 0), a chain of
 * {@link AddDays}/{@link SubDays} whose offsets are {@link LiteralSlot}s. The chain depth is
 * capped at {@link #MAX_CHAIN_DEPTH}; deeper trees are rejected with
 * {@link IllegalArgumentException}, which the future evaluator wiring treats as "fall back".
 */
public final class VarkaLoopEmitter {

  /**
   * The deepest op chain the emitter accepts, fixed by measurement
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
   * Test hook: when set, {@link AddDays} is emitted against a deliberately wrong descriptor
   * (unerased {@code IntVector} parameter instead of {@code Vector}). The class still passes
   * bytecode verification - member resolution happens at link time - so the failure surfaces on
   * first execution as a {@link NoSuchMethodError} naming {@code IntVector.add}. The suite pins
   * that, so a future descriptor regression is diagnosable from the error alone.
   */
  static volatile boolean misdescribeAddForTesting = false;

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
   * {@code sub} has the same shape.
   */
  private static final MethodTypeDesc LANEWISE_MASKED =
      MethodTypeDesc.of(INT_VECTOR, VECTOR, VECTOR_MASK);
  /** The deliberately wrong shape behind {@link #misdescribeAddForTesting}. */
  private static final MethodTypeDesc LANEWISE_MASKED_WRONG =
      MethodTypeDesc.of(INT_VECTOR, INT_VECTOR, VECTOR_MASK);
  /** {@code void IntVector.intoMemorySegment(MemorySegment, long, ByteOrder, VectorMask)}. */
  private static final MethodTypeDesc INTO_MEMORY_SEGMENT = MethodTypeDesc.of(
      ConstantDescs.CD_void, MEMORY_SEGMENT, ConstantDescs.CD_long, BYTE_ORDER, VECTOR_MASK);
  /** {@code long VectorMask.toLong()}. */
  private static final MethodTypeDesc TO_LONG = MethodTypeDesc.of(ConstantDescs.CD_long);
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
   * Assembles the fused-kernel class for the given output chains. Task 9 accepts exactly one
   * output over exactly one input column; the list parameter is the forward-compatible shape.
   *
   * @throws IllegalArgumentException if the IR is outside what this emitter serves - the caller
   *         is expected to fall back to the per-op kernels, exactly as a kernel failure does.
   */
  public static byte[] emit(
      String className, List<VarkaVectorIR> outputs, int numInputs, int numLiterals) {
    if (outputs.size() != 1) {
      throw new IllegalArgumentException(
          "task 9 emits a single output chain, got " + outputs.size());
    }
    if (numInputs != 1) {
      throw new IllegalArgumentException("task 9 emits over a single input, got " + numInputs);
    }
    VarkaVectorIR root = outputs.get(0);
    validate(root, numLiterals, 0);

    ClassDesc classDesc = ClassDesc.of(className);
    return ClassFile.of().build(classDesc, (ClassBuilder b) -> b
        .withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL)
        .withInterfaceSymbols(FUSED_KERNEL)
        .withMethodBody("<init>", INIT, AccessFlag.PUBLIC.mask(), (CodeBuilder cb) -> {
          cb.aload(0);
          cb.invokespecial(ConstantDescs.CD_Object, "<init>", INIT);
          cb.return_();
        })
        .withMethodBody("run", RUN, AccessFlag.PUBLIC.mask(),
            (CodeBuilder cb) -> emitRun(cb, root, numLiterals)));
  }

  /**
   * Rejects IR outside the task-9 shape: only {@link AddDays}/{@link SubDays} chains over
   * column 0 with literal offsets, int lanes, at most {@link #MAX_CHAIN_DEPTH} ops deep.
   */
  private static void validate(VarkaVectorIR node, int numLiterals, int depth) {
    if (node.laneType() != VarkaVectorIR.LaneType.INT) {
      throw new IllegalArgumentException("unsupported lane type " + node.laneType());
    }
    if (depth > MAX_CHAIN_DEPTH) {
      throw new IllegalArgumentException(
          "chain deeper than MAX_CHAIN_DEPTH=" + MAX_CHAIN_DEPTH);
    }
    switch (node) {
      case ColumnRef c -> {
        if (c.ordinal() != 0) {
          throw new IllegalArgumentException("task 9 reads only column 0, got " + c.ordinal());
        }
      }
      case LiteralSlot l -> {
        if (l.index() < 0 || l.index() >= numLiterals) {
          throw new IllegalArgumentException(
              "literal slot " + l.index() + " outside [0, " + numLiterals + ")");
        }
      }
      case AddDays a -> validateOp(a.days(), a.offset(), numLiterals, depth);
      case SubDays s -> validateOp(s.days(), s.offset(), numLiterals, depth);
    }
  }

  private static void validateOp(
      VarkaVectorIR days, VarkaVectorIR offset, int numLiterals, int depth) {
    if (!(offset instanceof LiteralSlot)) {
      throw new IllegalArgumentException("task 9 offsets must be literal slots, got " + offset);
    }
    validate(days, numLiterals, depth + 1);
    validate(offset, numLiterals, depth + 1);
  }

  // ---------------------------------------------------------------------------------------------
  // The emitted `run` body.
  // ---------------------------------------------------------------------------------------------

  private static void emitRun(CodeBuilder cb, VarkaVectorIR root, int numLiterals) {
    // Local slot layout, all allocated up front; longs take two slots.
    int slot = 8;
    final int dataBytes = slot;
    slot += 2;
    final int validityBytes = slot;
    slot += 2;
    final int srcSeg = slot++;
    final int dstSeg = slot++;
    final int dstValSeg = slot++;
    final int nullCount = slot++;
    final int hasNulls = slot++;
    final int srcValSeg = slot++;
    final int species = slot++;
    final int lanes = slot++;
    final int loopBound = slot++;
    final int[] scalarArg = new int[numLiterals];
    final int[] broadcast = new int[numLiterals];
    for (int j = 0; j < numLiterals; j++) {
      scalarArg[j] = slot++;
      broadcast[j] = slot++;
    }
    final int iVar = slot++;
    final int maskVar = slot++;
    final int byteOffset = slot;
    slot += 2;

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

    // srcSeg = ofAddress(srcData[0], dataBytes); dstSeg / dstValSeg likewise.
    loadSegment(cb, P_SRC_DATA, dataBytes, srcSeg);
    loadSegment(cb, P_DST_DATA, dataBytes, dstSeg);
    loadSegment(cb, P_DST_VALIDITY, validityBytes, dstValSeg);

    // (3) zero(dstValSeg) before any return below.
    cb.aload(dstValSeg);
    cb.invokestatic(SUPPORT, "zero", ZERO);

    // (4) nullCount = srcNullCount[0]; if (nullCount == length) return;
    cb.aload(P_NULL_COUNT);
    cb.loadConstant(0);
    cb.iaload();
    cb.istore(nullCount);
    Label notAllNull = cb.newLabel();
    cb.iload(nullCount);
    cb.iload(P_LENGTH);
    cb.if_icmpne(notAllNull);
    cb.return_();
    cb.labelBinding(notAllNull);

    // hasNulls = nullCount > 0; srcValSeg = hasNulls ? ofAddress(srcValidity[0], vBytes) : null.
    Label noNulls = cb.newLabel();
    Label hasNullsDone = cb.newLabel();
    cb.iload(nullCount);
    cb.ifle(noNulls);
    cb.loadConstant(1);
    cb.istore(hasNulls);
    cb.aload(P_SRC_VALIDITY);
    cb.loadConstant(0);
    cb.laload();
    cb.lload(validityBytes);
    cb.invokestatic(SUPPORT, "ofAddress", OF_ADDRESS);
    cb.astore(srcValSeg);
    cb.goto_(hasNullsDone);
    cb.labelBinding(noNulls);
    cb.loadConstant(0);
    cb.istore(hasNulls);
    cb.aconst_null();
    cb.astore(srcValSeg);
    cb.labelBinding(hasNullsDone);

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
      cb.aload(species);
      cb.iload(scalarArg[j]);
      cb.invokestatic(INT_VECTOR, "broadcast", BROADCAST);
      cb.astore(broadcast[j]);
    }

    // (5) The lane-group loop: for (i = 0; i < loopBound; i += lanes).
    cb.loadConstant(0);
    cb.istore(iVar);
    Label loopTop = cb.newLabel();
    Label loopEnd = cb.newLabel();
    cb.labelBinding(loopTop);
    cb.iload(iVar);
    cb.iload(loopBound);
    cb.if_icmpge(loopEnd);

    // mask = fromLong(species, hasNulls ? validityBitsAt(srcValSeg, i, lanes) : -1L).
    // The species is pushed first so both branches leave [species, long] for the merge.
    Label allTrue = cb.newLabel();
    Label haveBits = cb.newLabel();
    cb.aload(species);
    cb.iload(hasNulls);
    cb.ifeq(allTrue);
    cb.aload(srcValSeg);
    cb.iload(iVar);
    cb.i2l();
    cb.iload(lanes);
    cb.invokestatic(SUPPORT, "validityBitsAt", VALIDITY_BITS_AT);
    cb.goto_(haveBits);
    cb.labelBinding(allTrue);
    cb.loadConstant(-1L);
    cb.labelBinding(haveBits);
    cb.invokestatic(VECTOR_MASK, "fromLong", FROM_LONG);
    cb.astore(maskVar);

    // byteOffset = (long) i * 4.
    cb.iload(iVar);
    cb.i2l();
    cb.loadConstant(4L);
    cb.lmul();
    cb.lstore(byteOffset);

    // The chain, post-order, intermediates on the operand stack. The column load is the
    // ColumnRef leaf itself: one load per group in a single-use chain, and interning makes it
    // one load per group in general once the IR becomes a DAG (task 10).
    emitVector(cb, root, srcSeg, byteOffset, species, maskVar, broadcast);

    // Masked store, then this group's validity: dst is null exactly where the mask was off.
    cb.aload(dstSeg);
    cb.lload(byteOffset);
    cb.getstatic(BYTE_ORDER, "LITTLE_ENDIAN", BYTE_ORDER);
    cb.aload(maskVar);
    cb.invokevirtual(INT_VECTOR, "intoMemorySegment", INTO_MEMORY_SEGMENT);
    cb.aload(dstValSeg);
    cb.iload(iVar);
    cb.i2l();
    cb.aload(maskVar);
    cb.invokevirtual(VECTOR_MASK, "toLong", TO_LONG);
    cb.iload(lanes);
    cb.invokestatic(SUPPORT, "orValidityBitsAt", OR_VALIDITY_BITS_AT);

    cb.iload(iVar);
    cb.iload(lanes);
    cb.iadd();
    cb.istore(iVar);
    cb.goto_(loopTop);
    cb.labelBinding(loopEnd);

    // (6) Scalar tail: the same chain one row at a time, agreeing with the loop row for row.
    Label tailTop = cb.newLabel();
    Label tailEnd = cb.newLabel();
    Label rowValid = cb.newLabel();
    Label rowDone = cb.newLabel();
    cb.labelBinding(tailTop);
    cb.iload(iVar);
    cb.iload(P_LENGTH);
    cb.if_icmpge(tailEnd);
    cb.iload(hasNulls);
    cb.ifeq(rowValid);
    cb.aload(srcValSeg);
    cb.iload(iVar);
    cb.invokestatic(SUPPORT, "isBitSet", IS_BIT_SET);
    cb.ifeq(rowDone);
    cb.labelBinding(rowValid);
    // dstSeg.set(JAVA_INT, (long) i * 4, <scalar chain>); setBit(dstValSeg, i).
    cb.aload(dstSeg);
    cb.getstatic(VALUE_LAYOUT, "JAVA_INT", VALUE_LAYOUT_OF_INT);
    cb.iload(iVar);
    cb.i2l();
    cb.loadConstant(4L);
    cb.lmul();
    emitScalar(cb, root, srcSeg, iVar, scalarArg);
    cb.invokeinterface(MEMORY_SEGMENT, "set", SEGMENT_SET_INT);
    cb.aload(dstValSeg);
    cb.iload(iVar);
    cb.invokestatic(SUPPORT, "setBit", SET_BIT);
    cb.labelBinding(rowDone);
    cb.iinc(iVar, 1);
    cb.goto_(tailTop);
    cb.labelBinding(tailEnd);
    cb.return_();
  }

  /** {@code local = VarkaVectorSupport.ofAddress(param[0], lload(bytes))}. */
  private static void loadSegment(CodeBuilder cb, int arrayParam, int bytesSlot, int destSlot) {
    cb.aload(arrayParam);
    cb.loadConstant(0);
    cb.laload();
    cb.lload(bytesSlot);
    cb.invokestatic(SUPPORT, "ofAddress", OF_ADDRESS);
    cb.astore(destSlot);
  }

  /** Post-order walk leaving the node's {@code IntVector} on the operand stack. */
  private static void emitVector(CodeBuilder cb, VarkaVectorIR node, int srcSeg, int byteOffset,
      int species, int maskVar, int[] broadcast) {
    switch (node) {
      case ColumnRef c -> {
        cb.aload(species);
        cb.aload(srcSeg);
        cb.lload(byteOffset);
        cb.getstatic(BYTE_ORDER, "LITTLE_ENDIAN", BYTE_ORDER);
        cb.aload(maskVar);
        cb.invokestatic(INT_VECTOR, "fromMemorySegment", FROM_MEMORY_SEGMENT);
      }
      case LiteralSlot l -> cb.aload(broadcast[l.index()]);
      case AddDays a -> {
        emitVector(cb, a.days(), srcSeg, byteOffset, species, maskVar, broadcast);
        emitVector(cb, a.offset(), srcSeg, byteOffset, species, maskVar, broadcast);
        cb.aload(maskVar);
        MethodTypeDesc desc = misdescribeAddForTesting ? LANEWISE_MASKED_WRONG : LANEWISE_MASKED;
        cb.invokevirtual(INT_VECTOR, "add", desc);
      }
      case SubDays s -> {
        emitVector(cb, s.days(), srcSeg, byteOffset, species, maskVar, broadcast);
        emitVector(cb, s.offset(), srcSeg, byteOffset, species, maskVar, broadcast);
        cb.aload(maskVar);
        cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_MASKED);
      }
    }
  }

  /** The scalar-tail walk, leaving the row's {@code int} on the operand stack. */
  private static void emitScalar(
      CodeBuilder cb, VarkaVectorIR node, int srcSeg, int iVar, int[] scalarArg) {
    switch (node) {
      case ColumnRef c -> {
        cb.aload(srcSeg);
        cb.getstatic(VALUE_LAYOUT, "JAVA_INT", VALUE_LAYOUT_OF_INT);
        cb.iload(iVar);
        cb.i2l();
        cb.loadConstant(4L);
        cb.lmul();
        cb.invokeinterface(MEMORY_SEGMENT, "get", SEGMENT_GET_INT);
      }
      case LiteralSlot l -> cb.iload(scalarArg[l.index()]);
      case AddDays a -> {
        emitScalar(cb, a.days(), srcSeg, iVar, scalarArg);
        emitScalar(cb, a.offset(), srcSeg, iVar, scalarArg);
        cb.iadd();
      }
      case SubDays s -> {
        emitScalar(cb, s.days(), srcSeg, iVar, scalarArg);
        emitScalar(cb, s.offset(), srcSeg, iVar, scalarArg);
        cb.isub();
      }
    }
  }
}
