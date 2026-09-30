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

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;

/**
 * The descriptor table of the emitted code: every class and method the fused kernel calls
 * that does not name the lane's width, as {@link ClassDesc} and {@link MethodTypeDesc}
 * constants, and the parameter slots of the kernel's {@code run} method. What does name the
 * width - the loads, stores, broadcasts, arithmetic, comparisons and blend - is derived per
 * lane by {@link Lane}, so that a second lane extends that enum rather than this table. The
 * emitter and the lane import these statically; nothing outside the package sees them.
 */
final class VarkaDescriptors {

  private VarkaDescriptors() {
  }

  // ---------------------------------------------------------------------------------------------

  static final ClassDesc MEMORY_SEGMENT =
      ClassDesc.of("java.lang.foreign.MemorySegment");
  static final ClassDesc BYTE_ORDER = ClassDesc.of("java.nio.ByteOrder");
  static final ClassDesc INT_VECTOR = ClassDesc.of("jdk.incubator.vector.IntVector");
  static final ClassDesc LONG_VECTOR = ClassDesc.of("jdk.incubator.vector.LongVector");
  static final ClassDesc VECTOR = ClassDesc.of("jdk.incubator.vector.Vector");
  static final ClassDesc VECTOR_MASK = ClassDesc.of("jdk.incubator.vector.VectorMask");
  static final ClassDesc VECTOR_SPECIES =
      ClassDesc.of("jdk.incubator.vector.VectorSpecies");
  static final ClassDesc VECTOR_OPERATORS =
      ClassDesc.of("jdk.incubator.vector.VectorOperators");
  static final ClassDesc VO_COMPARISON =
      ClassDesc.ofDescriptor("Ljdk/incubator/vector/VectorOperators$Comparison;");
  /**
   * {@code VectorOperators.Associative}, which is what {@code AND}, {@code OR} and {@code XOR}
   * are declared as - not {@code Binary}, though it extends it. A {@code getstatic} carries the
   * field's own descriptor, so reading them as {@code Binary} links cleanly and then throws
   * {@code NoSuchFieldError} the first time the kernel runs (this has been hit in practice).
   */
  static final ClassDesc VO_ASSOCIATIVE =
      ClassDesc.of("jdk.incubator.vector.VectorOperators$Associative");
  static final ClassDesc VO_BINARY =
      ClassDesc.ofDescriptor("Ljdk/incubator/vector/VectorOperators$Binary;");
  static final ClassDesc DOUBLE_VECTOR =
      ClassDesc.of("jdk.incubator.vector.DoubleVector");
  /**
   * {@code VectorOperators.Conversion}, which is what {@code I2D} and {@code D2I} are declared
   * as - read as anything else a {@code getstatic} links and then throws {@code NoSuchFieldError}
   * on first execution, the way {@link #VO_ASSOCIATIVE} above records.
   */
  static final ClassDesc VO_CONVERSION =
      ClassDesc.ofDescriptor("Ljdk/incubator/vector/VectorOperators$Conversion;");
  static final ClassDesc SUPPORT =
      ClassDesc.of("org.apache.spark.sql.varka.vector.VarkaVectorSupport");
  static final ClassDesc FUSED_KERNEL = ClassDesc.of(VarkaFusedKernel.class.getName());

  static final ClassDesc LONG_ARRAY = ConstantDescs.CD_long.arrayType();
  static final ClassDesc INT_ARRAY = ConstantDescs.CD_int.arrayType();

  static final MethodTypeDesc INIT = MethodTypeDesc.of(ConstantDescs.CD_void);

  /** {@code MemorySegment VarkaVectorSupport.ofAddress(long, long)}. */
  static final MethodTypeDesc OF_ADDRESS =
      MethodTypeDesc.of(MEMORY_SEGMENT, ConstantDescs.CD_long, ConstantDescs.CD_long);
  /** {@code void VarkaVectorSupport.zero(MemorySegment)}. */
  static final MethodTypeDesc ZERO =
      MethodTypeDesc.of(ConstantDescs.CD_void, MEMORY_SEGMENT);
  /** {@code void VarkaVectorSupport.setValid(MemorySegment, int)}. */
  static final MethodTypeDesc SET_VALID =
      MethodTypeDesc.of(ConstantDescs.CD_void, MEMORY_SEGMENT, ConstantDescs.CD_int);
  /**
   * The whole-batch bitmap pass, one call per node of a served root's word expression:
   * {@code copyColumnValidity(MemorySegment dst, long addr, int nulls, int rows)} for a single
   * input, {@code and|orColumnValidity(dst, aAddr, aNulls, bAddr, bNulls, rows)} for the first two
   * of several, {@code and|orColumnValidityInto(dst, bAddr, bNulls, rows)} for each one after. The
   * operand states - a bitmap, all ones, all zeros - are resolved inside the engine
   * (PLAN_TASK_70.md 2.3), so the driver passes what it holds and emits no branch.
   */
  static final MethodTypeDesc COPY_COLUMN_VALIDITY = MethodTypeDesc.of(
      ConstantDescs.CD_void, MEMORY_SEGMENT, ConstantDescs.CD_long, ConstantDescs.CD_int,
      ConstantDescs.CD_int);
  static final MethodTypeDesc COLUMN_VALIDITY_PAIR = MethodTypeDesc.of(
      ConstantDescs.CD_void, MEMORY_SEGMENT, ConstantDescs.CD_long, ConstantDescs.CD_int,
      ConstantDescs.CD_long, ConstantDescs.CD_int, ConstantDescs.CD_int);
  static final MethodTypeDesc COLUMN_VALIDITY_INTO = COPY_COLUMN_VALIDITY;
  /**
   * {@code void VarkaVectorSupport.prepareOutputValidity(long[], long[], int[], String, int)}: the
   * driver's per-output work read from a plan, under {@code driverOutputTable}.
   */
  static final MethodTypeDesc PREPARE_OUTPUT_VALIDITY = MethodTypeDesc.of(
      ConstantDescs.CD_void, LONG_ARRAY, LONG_ARRAY, INT_ARRAY, ConstantDescs.CD_String,
      ConstantDescs.CD_int);
  /**
   * {@code boolean VarkaVectorSupport.everyOutputReadsAnAllNullColumn(int[], String, int)}: the
   * masked driver's all-null shortcut read from a table, under {@code driverOutputTable}.
   */
  static final MethodTypeDesc EVERY_OUTPUT_ALL_NULL = MethodTypeDesc.of(
      ConstantDescs.CD_boolean, INT_ARRAY, ConstantDescs.CD_String, ConstantDescs.CD_int);
  /** {@code long VarkaVectorSupport.validityBitsAt(MemorySegment, long, int)}. */
  static final MethodTypeDesc VALIDITY_BITS_AT = MethodTypeDesc.of(
      ConstantDescs.CD_long, MEMORY_SEGMENT, ConstantDescs.CD_long, ConstantDescs.CD_int);
  /** {@code void VarkaVectorSupport.orValidityBitsAt(MemorySegment, long, long, int)}. */
  static final MethodTypeDesc OR_VALIDITY_BITS_AT = MethodTypeDesc.of(
      ConstantDescs.CD_void, MEMORY_SEGMENT, ConstantDescs.CD_long, ConstantDescs.CD_long,
      ConstantDescs.CD_int);
  /** {@code long VarkaVectorSupport.validityBitsAt<N>(MemorySegment, long)}. */
  static final MethodTypeDesc VALIDITY_BITS_AT_WIDTH = MethodTypeDesc.of(
      ConstantDescs.CD_long, MEMORY_SEGMENT, ConstantDescs.CD_long);
  /** {@code void VarkaVectorSupport.orValidityBitsAt<N>(MemorySegment, long, long)}. */
  static final MethodTypeDesc OR_VALIDITY_BITS_AT_WIDTH = MethodTypeDesc.of(
      ConstantDescs.CD_void, MEMORY_SEGMENT, ConstantDescs.CD_long, ConstantDescs.CD_long);
  /** {@code void VarkaVectorSupport.putValidityWord(MemorySegment, long, long)}. */
  static final MethodTypeDesc PUT_VALIDITY_WORD = MethodTypeDesc.of(
      ConstantDescs.CD_void, MEMORY_SEGMENT, ConstantDescs.CD_long, ConstantDescs.CD_long);

  /** {@code int VectorSpecies.length()} / {@code int VectorSpecies.loopBound(int)}. */
  static final MethodTypeDesc SPECIES_LENGTH = MethodTypeDesc.of(ConstantDescs.CD_int);
  static final MethodTypeDesc LOOP_BOUND =
      MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_int);
  /**
   * {@code VectorMask VectorSpecies.indexInRange(int, int)} - the partial lane group's mask,
   * and the whole reason the epilogue can replace a scalar walk.
   */
  static final MethodTypeDesc INDEX_IN_RANGE =
      MethodTypeDesc.of(VECTOR_MASK, ConstantDescs.CD_int, ConstantDescs.CD_int);
  /** {@code IntVector IntVector.broadcast(VectorSpecies, int)} (static). */
  static final MethodTypeDesc BROADCAST =
      MethodTypeDesc.of(INT_VECTOR, VECTOR_SPECIES, ConstantDescs.CD_int);
  /** {@code VectorMask VectorMask.fromLong(VectorSpecies, long)} (static). */
  static final MethodTypeDesc FROM_LONG =
      MethodTypeDesc.of(VECTOR_MASK, VECTOR_SPECIES, ConstantDescs.CD_long);
  /** {@code long VectorMask.toLong()}. */
  static final MethodTypeDesc TO_LONG = MethodTypeDesc.of(ConstantDescs.CD_long);
  /**
   * {@code IntVector IntVector.add/sub/max/min(Vector)} - the parameter is the *erased*
   * {@code Vector}, not {@code IntVector}; the covariant return stays {@code IntVector}.
   */
  static final MethodTypeDesc LANEWISE_VV =
      MethodTypeDesc.of(INT_VECTOR, VECTOR);
  /** The deliberately wrong shape behind {@link VarkaEmitOptions#misdescribeAdd()}. */
  static final MethodTypeDesc LANEWISE_VV_WRONG =
      MethodTypeDesc.of(INT_VECTOR, INT_VECTOR);
  /** {@code IntVector IntVector.add/sub/and/mul/div(int)} - broadcast-scalar convenience. */
  static final MethodTypeDesc LANEWISE_VI =
      MethodTypeDesc.of(INT_VECTOR, ConstantDescs.CD_int);
  /** {@code IntVector IntVector.add/sub(int, VectorMask)}. */
  static final MethodTypeDesc LANEWISE_VI_MASKED =
      MethodTypeDesc.of(INT_VECTOR, ConstantDescs.CD_int, VECTOR_MASK);
  /** {@code IntVector IntVector.lanewise(VectorOperators.Binary, int)} - the shifts. */
  static final MethodTypeDesc LANEWISE_BINARY_I =
      MethodTypeDesc.of(INT_VECTOR, VO_BINARY, ConstantDescs.CD_int);
  /**
   * {@code Vector Vector.convertShape(VectorOperators.Conversion, VectorSpecies, int)} - declared
   * on {@code Vector} and erased in both directions, so one descriptor serves the widening and
   * the narrowing halves of a double-lane division alike.
   */
  static final MethodTypeDesc CONVERT_SHAPE =
      MethodTypeDesc.of(VECTOR, VO_CONVERSION, VECTOR_SPECIES, ConstantDescs.CD_int);
  /** {@code DoubleVector DoubleVector.mul/div(double)} - broadcast-scalar convenience. */
  static final MethodTypeDesc LANEWISE_VD =
      MethodTypeDesc.of(DOUBLE_VECTOR, ConstantDescs.CD_double);

  /** {@code VectorOperators.Unary}, the operator family {@code NEG} belongs to. */
  static final ClassDesc VO_UNARY =
      ClassDesc.of("jdk.incubator.vector.VectorOperators$Unary");

  /** {@code DoubleVector Vector.reinterpretAsDoubles()}. */
  static final MethodTypeDesc REINTERPRET_D = MethodTypeDesc.of(DOUBLE_VECTOR);

  /** {@code LongVector Vector.reinterpretAsLongs()}. */
  static final MethodTypeDesc REINTERPRET_L = MethodTypeDesc.of(LONG_VECTOR);

  /** {@code VectorMask DoubleVector.compare(VectorOperators.Comparison, Vector)}. */
  static final MethodTypeDesc COMPARE_DD =
      MethodTypeDesc.of(VECTOR_MASK, VO_COMPARISON, VECTOR);

  /** {@code DoubleVector DoubleVector.lanewise(VectorOperators.Binary, double, VectorMask)}. */
  static final MethodTypeDesc LANEWISE_VD_MASKED =
      MethodTypeDesc.of(DOUBLE_VECTOR, VO_BINARY, ConstantDescs.CD_double, VECTOR_MASK);

  /** {@code LongVector LongVector.lanewise(VectorOperators.Unary, VectorMask)}. */
  static final MethodTypeDesc LANEWISE_UNARY_L_MASKED =
      MethodTypeDesc.of(LONG_VECTOR, VO_UNARY, VECTOR_MASK);

  /** {@code VectorMask VectorMask.not()}. */
  static final MethodTypeDesc MASK_NOT = MethodTypeDesc.of(VECTOR_MASK);
  /** {@code VectorMask IntVector.compare(VectorOperators.Comparison, Vector)} - erased. */
  static final MethodTypeDesc COMPARE_VV =
      MethodTypeDesc.of(VECTOR_MASK, VO_COMPARISON, VECTOR);
  /** {@code VectorMask IntVector.compare(VectorOperators.Comparison, int)}. */
  static final MethodTypeDesc COMPARE_VI =
      MethodTypeDesc.of(VECTOR_MASK, VO_COMPARISON, ConstantDescs.CD_int);
  /** {@code IntVector IntVector.blend(Vector, VectorMask)} - erased {@code Vector}. */
  static final MethodTypeDesc BLEND =
      MethodTypeDesc.of(INT_VECTOR, VECTOR, VECTOR_MASK);
  /** {@code VectorMask VectorMask.and/or(VectorMask)} and {@code VectorMask.not()}. */
  static final MethodTypeDesc MASK_BINARY = MethodTypeDesc.of(VECTOR_MASK, VECTOR_MASK);
  static final MethodTypeDesc ANY_TRUE = MethodTypeDesc.of(ConstantDescs.CD_boolean);
  static final MethodTypeDesc MASK_UNARY = MethodTypeDesc.of(VECTOR_MASK);

  // Parameter slots of `run` (instance method: `this` is slot 0, finding 11's lesson).
  static final int P_SRC_DATA = 1;
  static final int P_SRC_VALIDITY = 2;
  static final int P_NULL_COUNT = 3;
  static final int P_DST_DATA = 4;
  static final int P_DST_VALIDITY = 5;
  static final int P_SCALAR_ARGS = 6;
  // `length` has no constant here: its slot depends on the lane, because a wider lane's second
  // scalar array sits between the two. `Lane.pLength` is the one that knows.

}
