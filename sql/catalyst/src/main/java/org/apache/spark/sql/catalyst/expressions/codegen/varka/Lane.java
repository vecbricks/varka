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

import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;

import javax.annotation.Nullable;

/**
 * Everything about an emission that names its lane's width, in one place, so that adding a
 * lane extends this enum instead of reworking the emitter.
 *
 * <p>The emitter reads a lane in about forty places - the loads and stores, the broadcast, the
 * arithmetic and comparison shapes, the blend, the species constant, the byte stride, the
 * array a scalar argument comes from - and each of those places asks a {@code Lane}. The
 * roughly one hundred and forty calendar sites do not, because a calendar lowering decomposes
 * a 32-bit epoch day and has no meaning at another width, so they require {@link #INT} instead.
 *
 * <p>Every descriptor that names the lane is derived from two facts - the vector class and the
 * scalar type - rather than written out per member, so a second member cannot disagree with
 * the first about the shape of {@code lanewise} or {@code compare}. Three of them name no lane
 * at all and are the same at every width: the two {@code intoMemorySegment} shapes, whose
 * receiver carries the lane, and {@code compare}'s erased-vector form. {@code
 * VarkaLaneTypeSuite} pins every one of them against a hand-written expectation, which is what
 * makes deriving safer than tabulating rather than merely shorter.
 *
 * <p><b>The overload trap the derivation navigates.</b> {@code IntVector} declares both
 * {@code broadcast(VectorSpecies, int)} and {@code broadcast(VectorSpecies, long)}, and
 * {@code compare}, {@code blend} and {@code lanewise} have int and long forms of their own;
 * {@code LongVector} declares only the long ones. So the scalar in these descriptors must be
 * the *lane's* type - {@code CD_int} here, {@code CD_long} at a wider lane - and neither a
 * fixed {@code CD_int}, which would name a method {@code LongVector} does not have, nor a
 * fixed {@code CD_long}, which would silently select {@code IntVector}'s other overload.
 */
// Its fields are ClassDesc and MethodTypeDesc, immutable JDK values the checker cannot tell are.
@SuppressWarnings("ImmutableEnumChecker")
enum Lane {
  /**
   * 8-bit lanes: a {@code TINYINT} column, stored a byte a row. Under
   * {@link VarkaEmitOptions#narrowLanes()} a kernel whose every value is narrow computes in this
   * lane; otherwise the lane names only how a byte column is stored and loaded, and the value is
   * widened to the int lane once at its load.
   */
  BYTE(VarkaVectorIR.LaneType.BYTE, "jdk.incubator.vector.ByteVector", Byte.SIZE,
      ConstantDescs.CD_byte),
  /** 16-bit lanes: a {@code SMALLINT} column, stored two bytes a row; see {@link #BYTE}. */
  SHORT(VarkaVectorIR.LaneType.SHORT, "jdk.incubator.vector.ShortVector", Short.SIZE,
      ConstantDescs.CD_short),
  /** 32-bit lanes: dates, ints, year-month intervals. */
  INT(VarkaVectorIR.LaneType.INT, "jdk.incubator.vector.IntVector", Integer.SIZE,
      ConstantDescs.CD_int),
  /**
   * 64-bit lanes: {@code bigint}, {@code TIME}, the timestamps and day-time intervals when
   * their tasks arrive. The emitter serves the lane-generic subset of the IR here - the
   * leaves, the arithmetic and its overflow test, the comparisons, the blend and the hull
   * ops - while the calendar lowerings stay at {@link #INT}, since they decompose a 32-bit
   * epoch day.
   */
  LONG(VarkaVectorIR.LaneType.LONG, "jdk.incubator.vector.LongVector", Long.SIZE,
      ConstantDescs.CD_long),
  /**
   * 32-bit floating lanes: a {@code FLOAT} column (VARKA-341). The emitter serves the nodes that
   * move a value's bits here - the loads and stores, the literals, the null tests and the
   * choices - and the comparisons and picks in Spark's order, built from the lane's IEEE compares
   * and its NaN test. The integer-only operations (shifts, bitwise, the bit count) have no
   * meaning at this lane, and arithmetic is a row of its own.
   */
  FLOAT(VarkaVectorIR.LaneType.FLOAT, "jdk.incubator.vector.FloatVector", Float.SIZE,
      ConstantDescs.CD_float),
  /** 64-bit floating lanes: a {@code DOUBLE} column (VARKA-341); see {@link #FLOAT}. */
  DOUBLE(VarkaVectorIR.LaneType.DOUBLE, "jdk.incubator.vector.DoubleVector", Double.SIZE,
      ConstantDescs.CD_double);

  /**
   * What only an integral lane has: the bitwise and shift shapes, the popcount, and the range a
   * negation and a range guard read. A floating lane has none of them, so the emitter asks
   * {@link #integral()} for them, which refuses a floating lane, rather than branching on the
   * lane's kind (`VARKA-475.md` 4, rule 2).
   *
   * @param lanewiseBinaryV {@code V V.lanewise(VectorOperators.Binary, Vector)} - XOR and AND
   * @param lanewiseBinaryI {@code V V.lanewise(VectorOperators.Binary, scalar)} - the shifts
   * @param lanewiseUnary {@code V V.lanewise(VectorOperators.Unary)} - the popcount
   * @param mostNegative the lane's most negative value: the one input a negate cannot represent
   * @param mostPositive the lane's largest value
   */
  record Integral(MethodTypeDesc lanewiseBinaryV, MethodTypeDesc lanewiseBinaryI,
      MethodTypeDesc lanewiseUnary, long mostNegative, long mostPositive) {}

  final VarkaVectorIR.LaneType laneType;
  /** The lane's vector class: the receiver of every load, store and lanewise call. */
  final ClassDesc vector;
  /** The lane's width in bits, which names its species constants. */
  final int bits;
  /** The scalar type a lane holds, as a literal argument and as an array element. */
  final ClassDesc scalar;
  /**
   * The lane's width in bytes, as the multiplier both address computations use: the data
   * segment is {@code length * byteStride} and a lane group starts at {@code i * byteStride}.
   * A stride rather than a shift because the emitted code multiplies - emitting a shift
   * instead would be different bytecode at the int lane for no gain.
   */
  final long byteStride;
  /** {@code V.broadcast(VectorSpecies, scalar)} (static). */
  final MethodTypeDesc broadcast;
  /** {@code V.fromMemorySegment(VectorSpecies, MemorySegment, long, ByteOrder)} (static). */
  final MethodTypeDesc fromMemorySegmentDense;
  /** The same load with a mask, which is the epilogue's only reason to differ. */
  final MethodTypeDesc fromMemorySegmentMasked;
  /** {@code void V.intoMemorySegment(MemorySegment, long, ByteOrder)}. */
  final MethodTypeDesc intoMemorySegmentDense;
  /** {@code void V.intoMemorySegment(MemorySegment, long, ByteOrder, VectorMask)}. */
  final MethodTypeDesc intoMemorySegmentMasked;
  /** {@code V V.add/sub/max/min(Vector)} - the parameter is the erased {@code Vector}. */
  final MethodTypeDesc lanewiseVV;
  /** The deliberately wrong shape behind {@link VarkaEmitOptions#misdescribeAdd()}. */
  final MethodTypeDesc lanewiseVVWrong;
  /** {@code V V.add/sub/and/mul/div(scalar)} - the broadcast-scalar convenience. */
  final MethodTypeDesc lanewiseVI;
  /** {@code V V.add/sub(scalar, VectorMask)}. */
  final MethodTypeDesc lanewiseVIMasked;
  /** The integral lane's own facts, or null at a floating lane; read through {@link #integral}. */
  private final @Nullable Integral integral;
  /** {@code VectorMask V.compare(VectorOperators.Comparison, Vector)} - erased. */
  final MethodTypeDesc compareVV;
  /** {@code VectorMask V.compare(VectorOperators.Comparison, scalar)}. */
  final MethodTypeDesc compareVI;
  /** {@code V V.blend(Vector, VectorMask)} - erased {@code Vector}. */
  final MethodTypeDesc blend;

  /** What {@code SPECIES_PREFERRED} answers for this lane on this JVM, read once. */
  final int preferredLanes;

  Lane(VarkaVectorIR.LaneType laneType, String vectorClass, int bits, ClassDesc scalar) {
    this.laneType = laneType;
    this.vector = ClassDesc.of(vectorClass);
    this.bits = bits;
    this.scalar = scalar;
    this.byteStride = bits / Byte.SIZE;
    this.broadcast = MethodTypeDesc.of(vector, VECTOR_SPECIES, scalar);
    this.fromMemorySegmentDense = MethodTypeDesc.of(vector, VECTOR_SPECIES, MEMORY_SEGMENT,
        ConstantDescs.CD_long, BYTE_ORDER);
    this.fromMemorySegmentMasked = MethodTypeDesc.of(vector, VECTOR_SPECIES, MEMORY_SEGMENT,
        ConstantDescs.CD_long, BYTE_ORDER, VECTOR_MASK);
    this.intoMemorySegmentDense = MethodTypeDesc.of(ConstantDescs.CD_void, MEMORY_SEGMENT,
        ConstantDescs.CD_long, BYTE_ORDER);
    this.intoMemorySegmentMasked = MethodTypeDesc.of(ConstantDescs.CD_void, MEMORY_SEGMENT,
        ConstantDescs.CD_long, BYTE_ORDER, VECTOR_MASK);
    this.lanewiseVV = MethodTypeDesc.of(vector, VECTOR);
    this.lanewiseVVWrong = MethodTypeDesc.of(vector, vector);
    this.lanewiseVI = MethodTypeDesc.of(vector, scalar);
    this.lanewiseVIMasked = MethodTypeDesc.of(vector, scalar, VECTOR_MASK);
    this.integral = switch (laneType) {
      case BYTE -> integral(vector, scalar, Byte.MIN_VALUE, Byte.MAX_VALUE);
      case SHORT -> integral(vector, scalar, Short.MIN_VALUE, Short.MAX_VALUE);
      case INT -> integral(vector, scalar, Integer.MIN_VALUE, Integer.MAX_VALUE);
      case LONG -> integral(vector, scalar, Long.MIN_VALUE, Long.MAX_VALUE);
      case FLOAT, DOUBLE -> null;
    };
    this.compareVV = MethodTypeDesc.of(VECTOR_MASK, VO_COMPARISON, VECTOR);
    this.compareVI = MethodTypeDesc.of(VECTOR_MASK, VO_COMPARISON, scalar);
    this.blend = MethodTypeDesc.of(vector, VECTOR, VECTOR_MASK);
    // The scalar arrays are the ones `VarkaFusedKernel.run` declares: an `int[]` at the 32-bit
    // and narrower lanes, whose literals arrive as ints and are narrowed by
    // {@link #narrowScalar} before they reach a byte or short descriptor, and a `long[]` at the
    // 64-bit ones. A narrow lane gets no array of its own for the reason the int lane shares one:
    // every literal the compiler folds is an int, and a byte literal is one of those. A floating
    // lane's literals are their bits in the table of their width (VARKA-341 2.3), so the choice
    // is the width's, not the scalar's: a `double` scalar takes the `long[]`.
    if (bits != Long.SIZE) {
      this.runDesc = MethodTypeDesc.of(ConstantDescs.CD_int, LONG_ARRAY, LONG_ARRAY, INT_ARRAY,
          LONG_ARRAY, LONG_ARRAY, INT_ARRAY, ConstantDescs.CD_int);
      this.pLongArgs = -1;
      this.pLength = P_SCALAR_ARGS + 1;
    } else {
      this.runDesc = MethodTypeDesc.of(ConstantDescs.CD_int, LONG_ARRAY, LONG_ARRAY, INT_ARRAY,
          LONG_ARRAY, LONG_ARRAY, INT_ARRAY, LONG_ARRAY, ConstantDescs.CD_int);
      this.pLongArgs = P_SCALAR_ARGS + 1;
      this.pLength = P_SCALAR_ARGS + 2;
    }
    this.firstLocal = this.pLength + 1;
    // The same descriptor with the scratch address after the length, for a kernel that
    // materializes a calendar prefix (VARKA-198): every slot before it is unchanged, so such a
    // kernel's bodies shift their locals by the long's two slots and nothing else, and every
    // other kernel keeps the descriptor above and its bytes.
    this.runDescScratch =
        this.runDesc.insertParameterTypes(this.runDesc.parameterCount(), ConstantDescs.CD_long);
    this.pScratch = this.pLength + 1;
    this.firstLocalScratch = this.pLength + 3;
    this.localWidth = bits == Long.SIZE ? 2 : 1;
    this.preferredLanes = switch (laneType) {
      case BYTE -> jdk.incubator.vector.ByteVector.SPECIES_PREFERRED.length();
      case SHORT -> jdk.incubator.vector.ShortVector.SPECIES_PREFERRED.length();
      case INT -> jdk.incubator.vector.IntVector.SPECIES_PREFERRED.length();
      case LONG -> jdk.incubator.vector.LongVector.SPECIES_PREFERRED.length();
      case FLOAT -> jdk.incubator.vector.FloatVector.SPECIES_PREFERRED.length();
      case DOUBLE -> jdk.incubator.vector.DoubleVector.SPECIES_PREFERRED.length();
    };
  }

  private static Integral integral(ClassDesc vector, ClassDesc scalar, long min, long max) {
    return new Integral(MethodTypeDesc.of(vector, VO_BINARY, VECTOR),
        MethodTypeDesc.of(vector, VO_BINARY, scalar), MethodTypeDesc.of(vector, VO_UNARY), min,
        max);
  }

  /** The integral lane's facts; a floating lane has none, and asking it is a refusal. */
  Integral integral() {
    if (integral == null) {
      throw VarkaErrors.illegalState("the " + laneType + " lane is floating and has no "
          + "integral operation: no shift, bitwise operation, popcount or integer range");
    }
    return integral;
  }

  /** Whether this is one of the two narrow lanes. */
  boolean isNarrow() {
    return laneType.isNarrow();
  }

  /**
   * The lane a node's type names. A switch with no {@code default} (sql/varka/AGENTS.md), so a new
   * {@code LaneType} member does not compile until it has its entry here, the one place every
   * consumer asks (VARKA-474 2.1). Each entry is the constant whose {@link #laneType} is that
   * member, which {@code VarkaLaneTypeSuite} checks both ways.
   */
  static Lane of(VarkaVectorIR.LaneType laneType) {
    return switch (laneType) {
      case BYTE -> BYTE;
      case SHORT -> SHORT;
      case INT -> INT;
      case LONG -> LONG;
      case FLOAT -> FLOAT;
      case DOUBLE -> DOUBLE;
    };
  }

  /**
   * The descriptor every body method of an emission on this lane shares, so slots line up
   * everywhere and a driver can forward a callee's status without repacking. The int lane
   * takes the seven-parameter form {@link VarkaFusedKernel#run} declares; a wider lane takes
   * the same parameters plus its own scalar array, because a 64-bit literal does not fit in
   * the {@code int[]} the int lane reads. Two arrays rather than one widened array: widening
   * would change every int32 `run` descriptor and every {@code iaload}, and so the bytes of
   * every int-lane kernel.
   */
  final MethodTypeDesc runDesc;
  /** The {@code length} parameter's slot, which the wider lane's extra array moves along. */
  final int pLength;
  /** The wider lane's scalar array parameter, or -1 where the lane has none. */
  final int pLongArgs;
  /** The first local slot after the parameters. */
  final int firstLocal;
  /** {@link #runDesc} plus the scratch address, for a kernel with a materialized prefix. */
  final MethodTypeDesc runDescScratch;
  /** The scratch address's slot under {@link #runDescScratch}: a long, two slots. */
  final int pScratch;
  /** The first local slot after the parameters under {@link #runDescScratch}. */
  final int firstLocalScratch;
  /** JVM local slots one value of this lane's scalar type occupies: two for a long, one else. */
  final int localWidth;

  /** The parameter holding this lane's scalar arguments. */
  int scalarArgsSlot() {
    return pLongArgs >= 0 ? pLongArgs : P_SCALAR_ARGS;
  }

  /**
   * Reads one element of this lane's scalar array, with the array reference and the index
   * already on the stack.
   */
  void arrayLoad(CodeBuilder cb) {
    if (localWidth == 2) {
      cb.laload();
    } else {
      cb.iaload();
    }
  }

  /** Stores the scalar on the stack into a local, and reads one back. */
  void storeScalar(CodeBuilder cb, int slot) {
    if (localWidth == 2) {
      cb.lstore(slot);
    } else {
      cb.istore(slot);
    }
  }

  void loadScalar(CodeBuilder cb, int slot) {
    if (localWidth == 2) {
      cb.lload(slot);
    } else {
      cb.iload(slot);
    }
  }

  /**
   * Pushes a constant of this lane's scalar type. The value is given as a {@code long} because
   * every constant the emitter pushes fits one; what differs is the type it must have on the
   * stack, and a sentinel like the most negative value differs in *magnitude* between lanes,
   * so callers pass the lane's own rather than a fixed one.
   */
  void pushScalar(CodeBuilder cb, long value) {
    integral();
    if (localWidth == 2) {
      cb.loadConstant(value);
      return;
    }
    cb.loadConstant((int) value);
    narrowScalar(cb);
  }

  /**
   * Narrows the int on the stack to this lane's scalar type, for a byte or short descriptor's
   * scalar argument. A scalar argument arrives from the {@code int[]} the int lane also reads,
   * so a narrow lane narrows it where it is pushed; the int and long lanes leave the stack as
   * it is, which is what keeps every existing kernel's bytes unchanged.
   */
  void narrowScalar(CodeBuilder cb) {
    integral();
    if (laneType == VarkaVectorIR.LaneType.BYTE) {
      cb.i2b();
    } else if (laneType == VarkaVectorIR.LaneType.SHORT) {
      cb.i2s();
    }
  }

  /**
   * Turns a literal read from this lane's scalar table into the lane's scalar type, for a
   * broadcast. The tables hold ints and longs, so an integral lane narrows as
   * {@link #narrowScalar} does and a floating lane reinterprets the bits: a {@code DOUBLE}
   * literal is its bits in the {@code long[]} table and a {@code FLOAT} one its bits in the
   * {@code int[]} one (VARKA-341 2.3), which keeps every lane on the two existing tables.
   */
  void literalScalar(CodeBuilder cb) {
    switch (laneType) {
      case BYTE, SHORT, INT, LONG -> narrowScalar(cb);
      case FLOAT -> cb.invokestatic(ConstantDescs.CD_Float, "intBitsToFloat",
          MethodTypeDesc.of(ConstantDescs.CD_float, ConstantDescs.CD_int));
      case DOUBLE -> cb.invokestatic(ConstantDescs.CD_Double, "longBitsToDouble",
          MethodTypeDesc.of(ConstantDescs.CD_double, ConstantDescs.CD_long));
    }
  }

  /** The most negative value of this lane: the one input a negate cannot represent. */
  long mostNegative() {
    return integral().mostNegative();
  }

  /** The largest value of this lane; {@link #mostNegative} is the other end. */
  long mostPositive() {
    return integral().mostPositive();
  }

  /**
   * Whether the Vector API names a species constant for this many of this lane's elements.
   * It declares {@code SPECIES_64} through {@code SPECIES_512} and nothing else, so the
   * question is whether the product is one of those four widths - which is 2 to 16 lanes at
   * 32 bits and 1 to 8 at 64. Derived from the width rather than tabulated per member, so
   * that {@link #speciesField} and this cannot disagree about which names exist: a third
   * lane given a copied list would pass here and then emit a {@code getstatic} for a field
   * no vector class has.
   */
  boolean hasSpecies(int lanes) {
    int width = lanes * bits;
    return width >= 64 && width <= 512 && Integer.bitCount(width) == 1;
  }

  /**
   * The lane count of the species a widening load of this narrow lane reads at a kernel driven
   * at {@code intLanes} int lanes, or 0 where no whole-group form exists. It is the int species'
   * own count wherever the narrow species of that width exists - a byte at eight int lanes is
   * one 64-bit load, a short at four int lanes is one too - and otherwise the next width up,
   * whose parts each become one int group: a byte at four int lanes would need a 32-bit species,
   * which the Vector API does not have, so it reads an eight-lane 64-bit load converted in two
   * parts ({@code B2I_load64} in the census, VARKA-329). A kernel whose load spans more than one
   * group computes that many groups an iteration.
   */
  static int widenLanes(int intLanes, Lane narrow) {
    if (intLanes <= 0) {
      return 0;
    }
    for (int lanes = intLanes; lanes <= 64; lanes *= 2) {
      if (narrow.hasSpecies(lanes)) {
        return lanes;
      }
    }
    return 0;
  }

  /**
   * Refuses an emission on any lane but the int one. The calendar kernels - about a hundred
   * and forty of the emitter's per-lane sites - decompose a 32-bit epoch day with constants
   * and shifts chosen for that width, so they have no meaning at another. They keep the int
   * descriptors below and call this on entry rather than reading the lane, which is what
   * makes their assumption a statement instead of a silence.
   *
   * <p>Two things already make a wider tree unreachable here - the IR's constructors refuse a
   * calendar node over a wider child, and the emitter's analysis refuses a node whose lane
   * differs from the emission's - so this is the third line of the same defence, and the one
   * that speaks for the kernels themselves.
   */
  void requireInt(VarkaVectorIR node) {
    if (this != INT) {
      throw VarkaErrors.illegalArgument("the calendar lowering needs int lanes, not "
          + laneType + ", for a " + node.getClass().getSimpleName());
    }
  }

  /**
   * The species constant for a baked lane count, or {@code SPECIES_PREFERRED} for 0: sixteen
   * int lanes is {@code SPECIES_512}, and eight long lanes is the same 512 bits.
   */
  String speciesField(int lanes) {
    return lanes == 0 ? "SPECIES_PREFERRED" : "SPECIES_" + lanes * bits;
  }

  /**
   * The lane count to bake into the emitted class, or 0 for "do not bake one" - which is what
   * {@link VarkaEmitOptions#validityByWidth} off means, and what a width the class cannot both
   * name and serve means.
   *
   * <p>A baked width needs two things that a lane count alone does not guarantee. It needs a
   * named species constant, which is a question about the width in bits: {@code SPECIES_64}
   * through {@code SPECIES_512} exist, and the shapes SVE reaches above 512 bits have no name.
   * And it needs the width-specialised validity helpers in {@link VarkaVectorSupport}, which
   * exist per lane *count*: 2, 4, 8 and 16. At the int lane the two sets coincide; at the long
   * lane they do not, because a single 64-bit lane is a species that exists and a helper that
   * does not. Anything the pair of checks rejects runs on {@code SPECIES_PREFERRED} and the
   * general helpers, which is correct at every width and no slower than the unspecialised
   * emission (VARKA-92).
   */
  static int emitLanes(VarkaEmitOptions options, Lane lane) {
    if (!options.validityByWidth()) {
      return 0;
    }
    int lanes = options.lanesOverride() != 0 ? options.lanesOverride() : lane.preferredLanes;
    // Both checks, not either: a width the class can name but not serve emits a call to a
    // validity helper that does not exist, which verifies and throws NoSuchMethodError on the
    // first masked batch. One long lane is that width, reachable with no override at all on a
    // JVM whose widest vector is 64 bits.
    return lane.hasSpecies(lanes) && hasValidityHelpers(lanes) ? lanes : 0;
  }

  /**
   * Whether {@link VarkaVectorSupport} carries a width-specialised validity pair for this many
   * lanes. A width without one is emitted against {@code SPECIES_PREFERRED} and the general
   * helpers, which is correct at any width and no slower than the unspecialised emission.
   */
  private static boolean hasValidityHelpers(int lanes) {
    return lanes == 2 || lanes == 4 || lanes == 8 || lanes == 16;
  }
}
