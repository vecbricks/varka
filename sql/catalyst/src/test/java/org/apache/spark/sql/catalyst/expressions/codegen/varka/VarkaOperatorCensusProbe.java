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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.nio.ByteOrder;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/**
 * The child process behind {@code VarkaOperatorCensusSuite}: one operator of the Vector API in a
 * tight loop over arrays, at the JVM's preferred species, in a JVM that runs nothing else.
 *
 * <p>The suite forks it once per catalog entry and width with {@code -Xbatch} and a
 * {@code PrintInlining} command scoped to the {@code op_*} methods below, which a
 * {@code dontinline} command keeps out of the driver loop so that each is compiled alone. The
 * probe runs the entry's loop until C2 has compiled it, prints the markers, then counts the bytes
 * the thread allocates over a thousand calls: a loop that is intrinsic and allocation-free
 * allocates nothing, and a box is at least five bytes a row. The verdict itself is read from the
 * inlining decisions by the suite, never from here (see {@code skills/vector-api-and-width.md}).
 *
 * <p>One JVM per operator, not per family, because a second species of one lane type in a JVM
 * makes the shared templates inline bimorphically and can box every loop compiled after it. The
 * conversions use two shapes of one lane type at once, and the two-species entries do it on
 * purpose. Lines the reader looks for:
 * <ul>
 *   <li>{@code VARKA_CENSUS_BEGIN=<entry> bits=<preferred species bits>};</li>
 *   <li>{@code VARKA_CENSUS_SKIP=<reason>} when the entry is not meaningful at this width;</li>
 *   <li>{@code VARKA_CENSUS_LOOP=<loop>} before each loop's warm-up, which sets the inlining
 *       decisions that follow apart from the previous loop's;</li>
 *   <li>{@code VARKA_CENSUS_ALLOC=<entry> <bytes a row>} for the loop the entry measures;</li>
 *   <li>{@code VARKA_CENSUS_DONE=<entry>}.</li>
 * </ul>
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class VarkaOperatorCensusProbe {

  private VarkaOperatorCensusProbe() {
  }

  public static final String BEGIN_PREFIX = "VARKA_CENSUS_BEGIN=";
  public static final String SKIP_PREFIX = "VARKA_CENSUS_SKIP=";
  public static final String ALLOC_PREFIX = "VARKA_CENSUS_ALLOC=";
  public static final String LOOP_MARKER = "VARKA_CENSUS_LOOP=";
  public static final String DONE_PREFIX = "VARKA_CENSUS_DONE=";

  /**
   * The argument that makes the probe print this JVM's host fact instead of running an entry:
   * {@code VarkaHostIsa.conversionsFallBack()} and the feature list it was read from (VARKA-538).
   */
  public static final String HOST_ENTRY = "host";
  public static final String FALLS_BACK_PREFIX = "VARKA_CENSUS_FALLS_BACK=";
  public static final String USE_AVX_PREFIX = "VARKA_CENSUS_USE_AVX=";
  public static final String FEATURES_PREFIX = "VARKA_CENSUS_FEATURES=";

  /** The system property that names the {@code VectorOperators} constant a loop applies. */
  public static final String OPERATOR_PROPERTY = "varka.census.operator";

  /** The prefix of the loop methods, which the launcher's compile commands name. */
  public static final String LOOP_PREFIX = "op_";

  /** Calls that bring a loop through C1 into C2 under {@code -Xbatch}. */
  private static final int WARMUP_CALLS = 30_000;

  /** Calls whose allocation is counted; a thousand, as the assembly suite does. */
  private static final int MEASURED_CALLS = 1_000;

  private static final int ROWS = 1024;

  private static final VectorSpecies<Byte> BS = ByteVector.SPECIES_PREFERRED;
  private static final VectorSpecies<Short> SS = ShortVector.SPECIES_PREFERRED;
  private static final VectorSpecies<Integer> IS = IntVector.SPECIES_PREFERRED;
  private static final VectorSpecies<Long> LS = LongVector.SPECIES_PREFERRED;
  private static final VectorSpecies<Float> FS = FloatVector.SPECIES_PREFERRED;
  private static final VectorSpecies<Double> DS = DoubleVector.SPECIES_PREFERRED;

  /**
   * A byte species a quarter as wide as the preferred one, and never under 64 bits: the shape of a
   * widening load. It sits in a holder so that only the entries that use it define a second byte
   * species in their JVM.
   */
  private static final class Narrow {
    static final VectorSpecies<Byte> SPECIES = VectorSpecies.of(byte.class,
        VectorShape.forBitSize(Math.max(64, BS.vectorBitSize() / 4)));
  }

  /** The 64-bit byte species: a widening load that takes the int species in one or two parts. */
  private static final class Narrow64 {
    static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_64;
    static final int PARTS = parts(SPECIES);
  }

  /** The 128-bit byte species: a widening load in {@code 16 / IS.length()} parts. */
  private static final class Narrow128 {
    static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_128;
    static final int PARTS = parts(SPECIES);
  }

  /** A short species half as wide as the preferred one, never under 64 bits. */
  private static final class NarrowShort {
    static final VectorSpecies<Short> SPECIES = VectorSpecies.of(short.class,
        VectorShape.forBitSize(Math.max(64, SS.vectorBitSize() / 2)));
    static final int PARTS = parts(SPECIES);
  }

  /**
   * How many int vectors one vector of {@code narrow} widens to. A species with fewer lanes than
   * the int species is not a whole group (the catalog does not run such an entry at that width),
   * and it is refused here rather than counted as one part, which would read past its lanes.
   */
  private static int parts(VectorSpecies<?> narrow) {
    if (narrow.length() < IS.length()) {
      throw new IllegalStateException(narrow + " has fewer lanes than " + IS
          + ", so it is not a whole group of the int species");
    }
    return narrow.length() / IS.length();
  }

  /** Takes the even lanes of one vector, then of the next: the de-interleave's shuffle. */
  private static final VectorShuffle<Long> DEINTERLEAVE =
      VectorShuffle.fromOp(LS, i -> 2 * i);

  private static final long[] WORDS = new long[ROWS];

  /** Where the planted box escapes to, so that C2 cannot remove the allocation. */
  private static volatile Object SINK;
  private static final byte[] BA = new byte[ROWS];
  private static final byte[] BB = new byte[ROWS];
  private static final byte[] BC = new byte[ROWS];
  private static final byte[] BO = new byte[ROWS];
  private static final short[] SA = new short[ROWS];
  private static final short[] SB = new short[ROWS];
  private static final short[] SC = new short[ROWS];
  private static final short[] SO = new short[ROWS];
  private static final int[] IA = new int[ROWS];
  private static final int[] IB = new int[ROWS];
  private static final int[] IC = new int[ROWS];
  private static final int[] IO = new int[ROWS];
  private static final long[] LA = new long[ROWS];
  private static final long[] LB = new long[ROWS];
  private static final long[] LC = new long[ROWS];
  private static final long[] LO = new long[ROWS];
  private static final float[] FA = new float[ROWS];
  private static final float[] FB = new float[ROWS];
  private static final float[] FC = new float[ROWS];
  private static final float[] FO = new float[ROWS];
  private static final double[] DA = new double[ROWS];
  private static final double[] DB = new double[ROWS];
  private static final double[] DC = new double[ROWS];
  private static final double[] DO = new double[ROWS];

  /** The operator constant a loop applies, read once so that C2 folds it. */
  private static Object operator() {
    try {
      return VectorOperators.class.getField(System.getProperty(OPERATOR_PROPERTY)).get(null);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("no VectorOperators constant named "
          + System.getProperty(OPERATOR_PROPERTY), e);
    }
  }

  private static final class BinOp {
    static final VectorOperators.Binary OP = (VectorOperators.Binary) operator();
  }

  private static final class CmpOp {
    static final VectorOperators.Comparison OP = (VectorOperators.Comparison) operator();
  }

  private static final class TestOp {
    static final VectorOperators.Test OP = (VectorOperators.Test) operator();
  }

  private static final class UnOp {
    static final VectorOperators.Unary OP = (VectorOperators.Unary) operator();
  }

  private static final class ConvOp {
    static final VectorOperators.Conversion OP = (VectorOperators.Conversion) operator();
  }

  static void op_bin_byte() {
    for (int i = 0; i < ROWS; i += BS.length()) {
      ByteVector.fromArray(BS, BA, i).lanewise(BinOp.OP, ByteVector.fromArray(BS, BB, i))
          .intoArray(BO, i);
    }
  }

  static void op_bin_short() {
    for (int i = 0; i < ROWS; i += SS.length()) {
      ShortVector.fromArray(SS, SA, i).lanewise(BinOp.OP, ShortVector.fromArray(SS, SB, i))
          .intoArray(SO, i);
    }
  }

  static void op_un_byte() {
    for (int i = 0; i < ROWS; i += BS.length()) {
      ByteVector.fromArray(BS, BA, i).lanewise(UnOp.OP).intoArray(BO, i);
    }
  }

  static void op_un_short() {
    for (int i = 0; i < ROWS; i += SS.length()) {
      ShortVector.fromArray(SS, SA, i).lanewise(UnOp.OP).intoArray(SO, i);
    }
  }

  static void op_blend_byte() {
    for (int i = 0; i < ROWS; i += BS.length()) {
      var a = ByteVector.fromArray(BS, BA, i);
      var b = ByteVector.fromArray(BS, BB, i);
      a.blend(b, a.compare(VectorOperators.LT, b)).intoArray(BO, i);
    }
  }

  static void op_blend_short() {
    for (int i = 0; i < ROWS; i += SS.length()) {
      var a = ShortVector.fromArray(SS, SA, i);
      var b = ShortVector.fromArray(SS, SB, i);
      a.blend(b, a.compare(VectorOperators.LT, b)).intoArray(SO, i);
    }
  }

  static void op_bin_int() {
    for (int i = 0; i < ROWS; i += IS.length()) {
      IntVector.fromArray(IS, IA, i).lanewise(BinOp.OP, IntVector.fromArray(IS, IB, i))
          .intoArray(IO, i);
    }
  }

  static void op_bin_long() {
    for (int i = 0; i < ROWS; i += LS.length()) {
      LongVector.fromArray(LS, LA, i).lanewise(BinOp.OP, LongVector.fromArray(LS, LB, i))
          .intoArray(LO, i);
    }
  }

  static void op_bin_float() {
    for (int i = 0; i < ROWS; i += FS.length()) {
      FloatVector.fromArray(FS, FA, i).lanewise(BinOp.OP, FloatVector.fromArray(FS, FB, i))
          .intoArray(FO, i);
    }
  }

  static void op_bin_double() {
    for (int i = 0; i < ROWS; i += DS.length()) {
      DoubleVector.fromArray(DS, DA, i).lanewise(BinOp.OP, DoubleVector.fromArray(DS, DB, i))
          .intoArray(DO, i);
    }
  }

  static void op_shift_byte() {
    for (int i = 0; i < ROWS; i += BS.length()) {
      ByteVector.fromArray(BS, BA, i).lanewise(BinOp.OP, (byte) 3).intoArray(BO, i);
    }
  }

  static void op_shift_short() {
    for (int i = 0; i < ROWS; i += SS.length()) {
      ShortVector.fromArray(SS, SA, i).lanewise(BinOp.OP, (short) 3).intoArray(SO, i);
    }
  }

  static void op_shift_int() {
    for (int i = 0; i < ROWS; i += IS.length()) {
      IntVector.fromArray(IS, IA, i).lanewise(BinOp.OP, 3).intoArray(IO, i);
    }
  }

  static void op_shift_long() {
    for (int i = 0; i < ROWS; i += LS.length()) {
      LongVector.fromArray(LS, LA, i).lanewise(BinOp.OP, 3L).intoArray(LO, i);
    }
  }

  static void op_cmp_byte() {
    for (int i = 0; i < ROWS; i += BS.length()) {
      var a = ByteVector.fromArray(BS, BA, i);
      var b = ByteVector.fromArray(BS, BB, i);
      a.blend(b, a.compare(CmpOp.OP, b)).intoArray(BO, i);
    }
  }

  static void op_cmp_short() {
    for (int i = 0; i < ROWS; i += SS.length()) {
      var a = ShortVector.fromArray(SS, SA, i);
      var b = ShortVector.fromArray(SS, SB, i);
      a.blend(b, a.compare(CmpOp.OP, b)).intoArray(SO, i);
    }
  }

  static void op_cmp_long() {
    for (int i = 0; i < ROWS; i += LS.length()) {
      var a = LongVector.fromArray(LS, LA, i);
      var b = LongVector.fromArray(LS, LB, i);
      a.blend(b, a.compare(CmpOp.OP, b)).intoArray(LO, i);
    }
  }

  static void op_cmp_float() {
    for (int i = 0; i < ROWS; i += FS.length()) {
      var a = FloatVector.fromArray(FS, FA, i);
      var b = FloatVector.fromArray(FS, FB, i);
      a.blend(b, a.compare(CmpOp.OP, b)).intoArray(FO, i);
    }
  }

  static void op_cmp_double() {
    for (int i = 0; i < ROWS; i += DS.length()) {
      var a = DoubleVector.fromArray(DS, DA, i);
      var b = DoubleVector.fromArray(DS, DB, i);
      a.blend(b, a.compare(CmpOp.OP, b)).intoArray(DO, i);
    }
  }

  static void op_test_float() {
    for (int i = 0; i < ROWS; i += FS.length()) {
      var a = FloatVector.fromArray(FS, FA, i);
      var b = FloatVector.fromArray(FS, FB, i);
      a.blend(b, a.test(TestOp.OP)).intoArray(FO, i);
    }
  }

  static void op_fma_float() {
    for (int i = 0; i < ROWS; i += FS.length()) {
      FloatVector.fromArray(FS, FA, i).fma(FloatVector.fromArray(FS, FB, i),
          FloatVector.fromArray(FS, FC, i)).intoArray(FO, i);
    }
  }

  static void op_test_double() {
    for (int i = 0; i < ROWS; i += DS.length()) {
      var a = DoubleVector.fromArray(DS, DA, i);
      var b = DoubleVector.fromArray(DS, DB, i);
      a.blend(b, a.test(TestOp.OP)).intoArray(DO, i);
    }
  }

  static void op_fma_double() {
    for (int i = 0; i < ROWS; i += DS.length()) {
      DoubleVector.fromArray(DS, DA, i).fma(DoubleVector.fromArray(DS, DB, i),
          DoubleVector.fromArray(DS, DC, i)).intoArray(DO, i);
    }
  }

  static void op_un_double() {
    for (int i = 0; i < ROWS; i += DS.length()) {
      DoubleVector.fromArray(DS, DA, i).lanewise(UnOp.OP).intoArray(DO, i);
    }
  }

  static void op_conv_B2I() {
    for (int i = 0; i < ROWS; i += BS.length()) {
      var v = ByteVector.fromArray(BS, BA, i);
      for (int p = 0; p < 4; p++) {
        ((IntVector) v.convertShape(ConvOp.OP, IS, p))
            .intoArray(IO, i + p * (BS.length() / 4));
      }
    }
  }

  static void op_conv_S2I() {
    for (int i = 0; i < ROWS; i += SS.length()) {
      var v = ShortVector.fromArray(SS, SA, i);
      for (int p = 0; p < 2; p++) {
        ((IntVector) v.convertShape(ConvOp.OP, IS, p))
            .intoArray(IO, i + p * (SS.length() / 2));
      }
    }
  }

  static void op_conv_B2S() {
    for (int i = 0; i < ROWS; i += BS.length()) {
      var v = ByteVector.fromArray(BS, BA, i);
      for (int p = 0; p < 2; p++) {
        ((ShortVector) v.convertShape(ConvOp.OP, SS, p))
            .intoArray(SO, i + p * (BS.length() / 2));
      }
    }
  }

  static void op_conv_I2B() {
    for (int i = 0; i < ROWS; i += IS.length() * 4) {
      for (int p = 0; p < 4; p++) {
        var v = IntVector.fromArray(IS, IA, i + p * IS.length());
        ((ByteVector) v.convertShape(ConvOp.OP, BS, -p)).intoArray(BO, i);
      }
    }
  }

  static void op_conv_I2S() {
    for (int i = 0; i < ROWS; i += IS.length() * 2) {
      for (int p = 0; p < 2; p++) {
        var v = IntVector.fromArray(IS, IA, i + p * IS.length());
        ((ShortVector) v.convertShape(ConvOp.OP, SS, -p)).intoArray(SO, i);
      }
    }
  }

  static void op_conv_L2D() {
    for (int i = 0; i < ROWS; i += LS.length()) {
      var v = LongVector.fromArray(LS, LA, i);
      for (int p = 0; p < 1; p++) {
        ((DoubleVector) v.convertShape(ConvOp.OP, DS, p))
            .intoArray(DO, i + p * (LS.length() / 1));
      }
    }
  }

  static void op_conv_D2L() {
    for (int i = 0; i < ROWS; i += DS.length()) {
      var v = DoubleVector.fromArray(DS, DA, i);
      for (int p = 0; p < 1; p++) {
        ((LongVector) v.convertShape(ConvOp.OP, LS, p))
            .intoArray(LO, i + p * (DS.length() / 1));
      }
    }
  }

  static void op_conv_F2D() {
    for (int i = 0; i < ROWS; i += FS.length()) {
      var v = FloatVector.fromArray(FS, FA, i);
      for (int p = 0; p < 2; p++) {
        ((DoubleVector) v.convertShape(ConvOp.OP, DS, p))
            .intoArray(DO, i + p * (FS.length() / 2));
      }
    }
  }

  static void op_conv_D2F() {
    for (int i = 0; i < ROWS; i += DS.length() * 2) {
      for (int p = 0; p < 2; p++) {
        var v = DoubleVector.fromArray(DS, DA, i + p * DS.length());
        ((FloatVector) v.convertShape(ConvOp.OP, FS, -p)).intoArray(FO, i);
      }
    }
  }

  static void op_conv_I2D() {
    for (int i = 0; i < ROWS; i += IS.length()) {
      var v = IntVector.fromArray(IS, IA, i);
      for (int p = 0; p < 2; p++) {
        ((DoubleVector) v.convertShape(ConvOp.OP, DS, p))
            .intoArray(DO, i + p * (IS.length() / 2));
      }
    }
  }

  static void op_conv_L2F() {
    for (int i = 0; i < ROWS; i += LS.length() * 2) {
      for (int p = 0; p < 2; p++) {
        var v = LongVector.fromArray(LS, LA, i + p * LS.length());
        ((FloatVector) v.convertShape(ConvOp.OP, FS, -p)).intoArray(FO, i);
      }
    }
  }

  static void op_conv_I2L() {
    for (int i = 0; i < ROWS; i += IS.length()) {
      var v = IntVector.fromArray(IS, IA, i);
      for (int p = 0; p < 2; p++) {
        ((LongVector) v.convertShape(ConvOp.OP, LS, p))
            .intoArray(LO, i + p * (IS.length() / 2));
      }
    }
  }

  static void op_conv_L2I() {
    for (int i = 0; i < ROWS; i += LS.length() * 2) {
      for (int p = 0; p < 2; p++) {
        var v = LongVector.fromArray(LS, LA, i + p * LS.length());
        ((IntVector) v.convertShape(ConvOp.OP, IS, -p)).intoArray(IO, i);
      }
    }
  }

  static void op_conv_I2F() {
    for (int i = 0; i < ROWS; i += IS.length()) {
      var v = IntVector.fromArray(IS, IA, i);
      for (int p = 0; p < 1; p++) {
        ((FloatVector) v.convertShape(ConvOp.OP, FS, p))
            .intoArray(FO, i + p * (IS.length() / 1));
      }
    }
  }

  static void op_conv_F2I() {
    for (int i = 0; i < ROWS; i += FS.length()) {
      var v = FloatVector.fromArray(FS, FA, i);
      for (int p = 0; p < 1; p++) {
        ((IntVector) v.convertShape(ConvOp.OP, IS, p))
            .intoArray(IO, i + p * (FS.length() / 1));
      }
    }
  }

  static void op_conv_D2I() {
    for (int i = 0; i < ROWS; i += DS.length() * 2) {
      for (int p = 0; p < 2; p++) {
        var v = DoubleVector.fromArray(DS, DA, i + p * DS.length());
        ((IntVector) v.convertShape(ConvOp.OP, IS, -p)).intoArray(IO, i);
      }
    }
  }

  static void op_deint_rearrange2() {
    for (int i = 0; i < ROWS; i += LS.length()) {
      LongVector.fromArray(LS, LA, i).rearrange(DEINTERLEAVE, LongVector.fromArray(LS, LB, i))
          .intoArray(LO, i);
    }
  }

  static void op_deint_selectFrom() {
    for (int i = 0; i < ROWS; i += LS.length()) {
      LongVector.fromArray(LS, LB, i).selectFrom(LongVector.fromArray(LS, LA, i)).intoArray(LO, i);
    }
  }

  static void op_deint_compress() {
    for (int i = 0; i < ROWS; i += LS.length()) {
      var a = LongVector.fromArray(LS, LA, i);
      a.compress(a.compare(VectorOperators.LT, LongVector.fromArray(LS, LB, i))).intoArray(LO, i);
    }
  }

  /**
   * The decimal load's input: Arrow's sixteen-byte decimal slots, low word first, in native memory
   * as a kernel finds them. A vector {@code a} loaded at slot {@code i} holds
   * {@code (lo0, hi0, lo1, hi1, ...)}, half a group of rows; {@code b}, one vector further on,
   * holds the other half.
   * Each loop maps the address afresh, as a kernel maps its input once a batch, and the buffer has
   * a vector of padding past the last slot so that the offset loads, which read eight bytes past
   * their group, stay inside it. The values are in precision and their high words are the low
   * words' signs, so the checks below never fire. It sits in a holder so that only the decimal
   * entries allocate it.
   */
  private static final class Slots {
    static final long BYTES = ROWS * 16L + 64;
    static final long ADDRESS = fill(Arena.global().allocate(BYTES, 64)).address();

    /** The low words, then the high words, of the slots in two vectors. */
    static final VectorShuffle<Long> EVEN = DEINTERLEAVE;
    static final VectorShuffle<Long> ODD = VectorShuffle.fromOp(LS, i -> 2 * i + 1);

    /** The two-vector {@code selectFrom}'s index vector: 0, 2, 4, ... across both inputs. */
    static final LongVector EVEN_INDEX = LongVector.zero(LS).addIndex(2);

    /** The even lanes, where a slot's low word sits in an interleaved vector. */
    static final VectorMask<Long> EVEN_LANES = VectorMask.fromLong(LS, EVEN_BITS);

    /** The largest unscaled value of a {@code DECIMAL(18, s)}: 10^18 - 1. */
    static final long BOUND = 999_999_999_999_999_999L;

    /** The rows a vector of {@code n} longs covers in one of {@code a} and {@code b}. */
    static final int HALF = LS.length() / 2;

    private static MemorySegment fill(MemorySegment slots) {
      for (int i = 0; i < ROWS; i++) {
        long lo = i * 7L - 3000;
        slots.set(ValueLayout.JAVA_LONG, i * 16L, lo);
        slots.set(ValueLayout.JAVA_LONG, i * 16L + 8, lo >> 63);
      }
      return slots;
    }

    static MemorySegment map() {
      return VarkaSegments.map(ADDRESS, BYTES);
    }

    static LongVector load(MemorySegment slots, long offset) {
      return LongVector.fromMemorySegment(LS, slots, offset, ByteOrder.LITTLE_ENDIAN);
    }
  }

  /** Every other bit of a word: the even lanes of a mask, or a validity word spread to them. */
  private static final long EVEN_BITS = 0x5555_5555_5555_5555L;

  /** Where a check's verdict goes, so that C2 cannot remove the check. */
  private static volatile boolean DECLINED;

  /** The decimal load's low words by the two-vector {@code rearrange}, from the slots. */
  static void op_dec_rearrange() {
    var slots = Slots.map();
    for (int i = 0; i < ROWS; i += LS.length()) {
      var a = Slots.load(slots, i * 16L);
      var b = Slots.load(slots, i * 16L + LS.vectorByteSize());
      a.rearrange(Slots.EVEN, b).intoArray(LO, i);
    }
  }

  /**
   * The low words by the two-vector {@code selectFrom}: the index vector picks lanes 0, 2, 4, ...
   * of {@code a} then {@code b}.
   */
  static void op_dec_selectFrom2() {
    var slots = Slots.map();
    for (int i = 0; i < ROWS; i += LS.length()) {
      var a = Slots.load(slots, i * 16L);
      var b = Slots.load(slots, i * 16L + LS.vectorByteSize());
      Slots.EVEN_INDEX.selectFrom(a, b).intoArray(LO, i);
    }
  }

  /**
   * The low words by {@code compress} under the constant even-lane mask: each vector's low words
   * packed into its lower half, and {@code b}'s moved to the upper half by {@code unslice}.
   */
  static void op_dec_compress() {
    var slots = Slots.map();
    for (int i = 0; i < ROWS; i += LS.length()) {
      var a = Slots.load(slots, i * 16L);
      var b = Slots.load(slots, i * 16L + LS.vectorByteSize());
      a.compress(Slots.EVEN_LANES).or(b.compress(Slots.EVEN_LANES).unslice(Slots.HALF))
          .intoArray(LO, i);
    }
  }

  /**
   * The load with its check, by {@code rearrange}: the high words gathered as the low words are,
   * a slot declining when its high word is not its low word's sign or its low word is outside the
   * precision. The mismatches are gathered in a mask across the loop and tested once at its end,
   * as the kernel does.
   */
  static void op_dec_check_rearrange() {
    var slots = Slots.map();
    var bad = VectorMask.fromLong(LS, 0L);
    for (int i = 0; i < ROWS; i += LS.length()) {
      var a = Slots.load(slots, i * 16L);
      var b = Slots.load(slots, i * 16L + LS.vectorByteSize());
      var lo = a.rearrange(Slots.EVEN, b);
      var hi = a.rearrange(Slots.ODD, b);
      bad = bad.or(hi.compare(VectorOperators.NE, lo.lanewise(VectorOperators.ASHR, 63))
          .or(lo.compare(VectorOperators.GT, Slots.BOUND))
          .or(lo.compare(VectorOperators.LT, -Slots.BOUND)));
      lo.intoArray(LO, i);
    }
    DECLINED = bad.anyTrue();
  }

  /**
   * The same check in the masked body: only the rows the validity word marks valid can decline.
   * The loop repeats the dense one rather than sharing it through a helper, because the suite
   * prints inlining only for the {@code op_*} methods and reads the loop's C2 compile there, so
   * the loop must be the {@code op_*} method's own code.
   */
  static void op_dec_check_rearrange_masked() {
    var slots = Slots.map();
    var bad = VectorMask.fromLong(LS, 0L);
    for (int i = 0; i < ROWS; i += LS.length()) {
      var a = Slots.load(slots, i * 16L);
      var b = Slots.load(slots, i * 16L + LS.vectorByteSize());
      var lo = a.rearrange(Slots.EVEN, b);
      var hi = a.rearrange(Slots.ODD, b);
      var valid = VectorMask.fromLong(LS, WORDS[i >>> 6] >>> (i & 63));
      bad = bad.or(hi.compare(VectorOperators.NE, lo.lanewise(VectorOperators.ASHR, 63))
          .or(lo.compare(VectorOperators.GT, Slots.BOUND))
          .or(lo.compare(VectorOperators.LT, -Slots.BOUND)).and(valid));
      lo.intoArray(LO, i);
    }
    DECLINED = bad.anyTrue();
  }

  /** The load with its check, by {@code compress}: the high words under the odd-lane mask. */
  static void op_dec_check_compress() {
    var slots = Slots.map();
    var odd = Slots.EVEN_LANES.not();
    var bad = VectorMask.fromLong(LS, 0L);
    for (int i = 0; i < ROWS; i += LS.length()) {
      var a = Slots.load(slots, i * 16L);
      var b = Slots.load(slots, i * 16L + LS.vectorByteSize());
      var lo = a.compress(Slots.EVEN_LANES).or(b.compress(Slots.EVEN_LANES).unslice(Slots.HALF));
      var hi = a.compress(odd).or(b.compress(odd).unslice(Slots.HALF));
      bad = bad.or(hi.compare(VectorOperators.NE, lo.lanewise(VectorOperators.ASHR, 63))
          .or(lo.compare(VectorOperators.GT, Slots.BOUND))
          .or(lo.compare(VectorOperators.LT, -Slots.BOUND)));
      lo.intoArray(LO, i);
    }
    DECLINED = bad.anyTrue();
  }

  /**
   * The shuffle-free check: a second load of the same slots eight bytes on puts each high word in
   * the lane of its own low word, so an even lane compares a high word with its low word's sign
   * and no lane crosses. It takes two offset loads and two compares per output vector; the low
   * words still come from the {@code rearrange}, whose precision check stays as it is.
   */
  static void op_dec_check_offset() {
    var slots = Slots.map();
    var bad = VectorMask.fromLong(LS, 0L);
    for (int i = 0; i < ROWS; i += LS.length()) {
      long at = i * 16L;
      long next = at + LS.vectorByteSize();
      var a = Slots.load(slots, at);
      var b = Slots.load(slots, next);
      var lo = a.rearrange(Slots.EVEN, b);
      bad = bad.or(Slots.load(slots, at + 8)
          .compare(VectorOperators.NE, a.lanewise(VectorOperators.ASHR, 63))
          .or(Slots.load(slots, next + 8)
              .compare(VectorOperators.NE, b.lanewise(VectorOperators.ASHR, 63)))
          .and(Slots.EVEN_LANES)
          .or(lo.compare(VectorOperators.GT, Slots.BOUND))
          .or(lo.compare(VectorOperators.LT, -Slots.BOUND)));
      lo.intoArray(LO, i);
    }
    DECLINED = bad.anyTrue();
  }

  /**
   * The shuffle-free check in the masked body. Its compares run over the interleaved lanes, two
   * a row, so each row's validity bit is first spread to its even lane ({@code Long.expand}), once
   * for {@code a}'s rows and once for {@code b}'s; the precision check takes the word as it is.
   */
  static void op_dec_check_offset_masked() {
    var slots = Slots.map();
    var bad = VectorMask.fromLong(LS, 0L);
    for (int i = 0; i < ROWS; i += LS.length()) {
      long at = i * 16L;
      long next = at + LS.vectorByteSize();
      long word = WORDS[i >>> 6];
      var a = Slots.load(slots, at);
      var b = Slots.load(slots, next);
      var lo = a.rearrange(Slots.EVEN, b);
      var inA = VectorMask.fromLong(LS, Long.expand(word >>> (i & 63), EVEN_BITS));
      var inB = VectorMask.fromLong(LS, Long.expand(word >>> ((i + Slots.HALF) & 63), EVEN_BITS));
      var valid = VectorMask.fromLong(LS, word >>> (i & 63));
      bad = bad.or(Slots.load(slots, at + 8)
          .compare(VectorOperators.NE, a.lanewise(VectorOperators.ASHR, 63)).and(inA)
          .or(Slots.load(slots, next + 8)
              .compare(VectorOperators.NE, b.lanewise(VectorOperators.ASHR, 63)).and(inB))
          .or(lo.compare(VectorOperators.GT, Slots.BOUND)
              .or(lo.compare(VectorOperators.LT, -Slots.BOUND)).and(valid)));
      lo.intoArray(LO, i);
    }
    DECLINED = bad.anyTrue();
  }

  /**
   * The epilogue's form: both vectors loaded under {@code indexInRange} masks over the slots'
   * longs and the low words stored under one over the rows. The limit stops one row short of the
   * buffer, so the last group of every call is partial.
   */
  static void op_dec_tail() {
    var slots = Slots.map();
    int limit = ROWS - 1;
    for (int i = 0; i < ROWS; i += LS.length()) {
      var a = LongVector.fromMemorySegment(LS, slots, i * 16L, ByteOrder.LITTLE_ENDIAN,
          LS.indexInRange(2 * i, 2 * limit));
      var b = LongVector.fromMemorySegment(LS, slots, i * 16L + LS.vectorByteSize(),
          ByteOrder.LITTLE_ENDIAN, LS.indexInRange(2 * i + LS.length(), 2 * limit));
      a.rearrange(Slots.EVEN, b).intoArray(LO, i, LS.indexInRange(i, limit));
    }
  }

  /**
   * The int arm's load: two long de-interleaves, each narrowed by {@code L2I} into one half of an
   * int vector (part 0 the lower half, part -1 the upper, the other half zero), spliced by an
   * {@code or} into one int vector of twice the lanes.
   */
  static void op_dec_int_splice() {
    var slots = Slots.map();
    long vector = LS.vectorByteSize();
    for (int i = 0; i < ROWS; i += IS.length()) {
      long at = i * 16L;
      var lo0 = Slots.load(slots, at).rearrange(Slots.EVEN, Slots.load(slots, at + vector));
      var lo1 = Slots.load(slots, at + 2 * vector)
          .rearrange(Slots.EVEN, Slots.load(slots, at + 3 * vector));
      var low = (IntVector) lo0.convertShape(VectorOperators.L2I, IS, 0);
      var high = (IntVector) lo1.convertShape(VectorOperators.L2I, IS, -1);
      low.or(high).intoArray(IO, i);
    }
  }

  static void op_mask_fromLong_byte() {
    for (int i = 0; i < ROWS; i += BS.length()) {
      var a = ByteVector.fromArray(BS, BA, i);
      a.blend(ByteVector.fromArray(BS, BB, i),
          VectorMask.fromLong(BS, WORDS[i / BS.length()])).intoArray(BO, i);
    }
  }

  static void op_mask_toLong_byte() {
    for (int i = 0; i < ROWS; i += BS.length()) {
      var a = ByteVector.fromArray(BS, BA, i);
      WORDS[i / BS.length()] = a.compare(VectorOperators.LT, ByteVector.fromArray(BS, BB, i))
          .toLong();
    }
  }

  static void op_mask_fromLong_int() {
    for (int i = 0; i < ROWS; i += IS.length()) {
      var a = IntVector.fromArray(IS, IA, i);
      a.blend(IntVector.fromArray(IS, IB, i),
          VectorMask.fromLong(IS, WORDS[i / IS.length()])).intoArray(IO, i);
    }
  }

  static void op_mask_toLong_int() {
    for (int i = 0; i < ROWS; i += IS.length()) {
      var a = IntVector.fromArray(IS, IA, i);
      WORDS[i / IS.length()] = a.compare(VectorOperators.LT, IntVector.fromArray(IS, IB, i))
          .toLong();
    }
  }

  static void op_mask_fromLong_long() {
    for (int i = 0; i < ROWS; i += LS.length()) {
      var a = LongVector.fromArray(LS, LA, i);
      a.blend(LongVector.fromArray(LS, LB, i),
          VectorMask.fromLong(LS, WORDS[i / LS.length()])).intoArray(LO, i);
    }
  }

  static void op_mask_toLong_long() {
    for (int i = 0; i < ROWS; i += LS.length()) {
      var a = LongVector.fromArray(LS, LA, i);
      WORDS[i / LS.length()] = a.compare(VectorOperators.LT, LongVector.fromArray(LS, LB, i))
          .toLong();
    }
  }

  static void op_load_b2i() {
    for (int i = 0; i < ROWS; i += IS.length()) {
      var widened = ByteVector.fromArray(Narrow.SPECIES, BA, i)
          .convertShape(VectorOperators.B2I, IS, 0);
      ((IntVector) widened).intoArray(IO, i);
    }
  }

  static void op_load_b2i64() {
    loadBytesInParts(Narrow64.SPECIES, Narrow64.PARTS);
  }

  static void op_load_b2i128() {
    loadBytesInParts(Narrow128.SPECIES, Narrow128.PARTS);
  }

  /** Loads {@code species} of bytes and widens each into {@code parts} int vectors. */
  private static void loadBytesInParts(VectorSpecies<Byte> species, int parts) {
    for (int i = 0; i < ROWS; i += species.length()) {
      var v = ByteVector.fromArray(species, BA, i);
      for (int p = 0; p < parts; p++) {
        ((IntVector) v.convertShape(VectorOperators.B2I, IS, p))
            .intoArray(IO, i + p * IS.length());
      }
    }
  }

  static void op_load_s2i() {
    for (int i = 0; i < ROWS; i += NarrowShort.SPECIES.length()) {
      var v = ShortVector.fromArray(NarrowShort.SPECIES, SA, i);
      for (int p = 0; p < NarrowShort.PARTS; p++) {
        ((IntVector) v.convertShape(VectorOperators.S2I, IS, p))
            .intoArray(IO, i + p * IS.length());
      }
    }
  }

  static void op_two_fullshort() {
    for (int i = 0; i < ROWS; i += SS.length()) {
      ShortVector.fromArray(SS, SA, i).lanewise(VectorOperators.SUSUB,
          ShortVector.fromArray(SS, SB, i)).intoArray(SO, i);
    }
  }

  static void op_two_narrow() {
    for (int i = 0; i < ROWS; i += Narrow.SPECIES.length()) {
      ByteVector.fromArray(Narrow.SPECIES, BA, i).lanewise(VectorOperators.SUSUB,
          ByteVector.fromArray(Narrow.SPECIES, BB, i)).intoArray(BO, i);
    }
  }

  static void op_two_full() {
    for (int i = 0; i < ROWS; i += BS.length()) {
      ByteVector.fromArray(BS, BA, i).lanewise(VectorOperators.SUSUB,
          ByteVector.fromArray(BS, BB, i)).intoArray(BO, i);
    }
  }

  static void op_two_mixed() {
    for (int i = 0; i < ROWS; i += BS.length()) {
      var wide = ByteVector.fromArray(BS, BA, i).lanewise(VectorOperators.SUSUB,
          ByteVector.fromArray(BS, BB, i));
      var narrow = ByteVector.fromArray(Narrow.SPECIES, BC, i).lanewise(VectorOperators.SUSUB,
          ByteVector.fromArray(Narrow.SPECIES, BB, i));
      wide.intoArray(BO, i);
      narrow.intoArray(BC, i);
    }
  }

  static void op_planted_box() {
    for (int i = 0; i < ROWS; i += IS.length()) {
      SINK = IntVector.fromArray(IS, IA, i);
    }
  }

  /** Calls one loop {@code calls} times. */
  private static void call(String loop, int calls) {
    for (int k = 0; k < calls; k++) {
      switch (loop) {
        case "bin.byte" -> op_bin_byte();
        case "bin.short" -> op_bin_short();
        case "bin.int" -> op_bin_int();
        case "un.byte" -> op_un_byte();
        case "un.short" -> op_un_short();
        case "blend.byte" -> op_blend_byte();
        case "blend.short" -> op_blend_short();
        case "conv.B2S" -> op_conv_B2S();
        case "load.b2i64" -> op_load_b2i64();
        case "load.b2i128" -> op_load_b2i128();
        case "load.s2i" -> op_load_s2i();
        case "two.fullshort" -> op_two_fullshort();
        case "bin.long" -> op_bin_long();
        case "bin.float" -> op_bin_float();
        case "bin.double" -> op_bin_double();
        case "shift.byte" -> op_shift_byte();
        case "shift.short" -> op_shift_short();
        case "shift.int" -> op_shift_int();
        case "shift.long" -> op_shift_long();
        case "cmp.byte" -> op_cmp_byte();
        case "cmp.short" -> op_cmp_short();
        case "cmp.long" -> op_cmp_long();
        case "cmp.float" -> op_cmp_float();
        case "cmp.double" -> op_cmp_double();
        case "test.float" -> op_test_float();
        case "fma.float" -> op_fma_float();
        case "test.double" -> op_test_double();
        case "fma.double" -> op_fma_double();
        case "un.double" -> op_un_double();
        case "conv.B2I" -> op_conv_B2I();
        case "conv.S2I" -> op_conv_S2I();
        case "conv.I2B" -> op_conv_I2B();
        case "conv.I2S" -> op_conv_I2S();
        case "conv.L2D" -> op_conv_L2D();
        case "conv.D2L" -> op_conv_D2L();
        case "conv.F2D" -> op_conv_F2D();
        case "conv.D2F" -> op_conv_D2F();
        case "conv.I2D" -> op_conv_I2D();
        case "conv.L2F" -> op_conv_L2F();
        case "conv.I2L" -> op_conv_I2L();
        case "conv.L2I" -> op_conv_L2I();
        case "conv.I2F" -> op_conv_I2F();
        case "conv.F2I" -> op_conv_F2I();
        case "conv.D2I" -> op_conv_D2I();
        case "deint.rearrange2" -> op_deint_rearrange2();
        case "deint.selectFrom" -> op_deint_selectFrom();
        case "deint.compress" -> op_deint_compress();
        case "dec.rearrange" -> op_dec_rearrange();
        case "dec.selectFrom2" -> op_dec_selectFrom2();
        case "dec.compress" -> op_dec_compress();
        case "dec.check.rearrange" -> op_dec_check_rearrange();
        case "dec.check.rearrange.masked" -> op_dec_check_rearrange_masked();
        case "dec.check.compress" -> op_dec_check_compress();
        case "dec.check.offset" -> op_dec_check_offset();
        case "dec.check.offset.masked" -> op_dec_check_offset_masked();
        case "dec.tail" -> op_dec_tail();
        case "dec.int.splice" -> op_dec_int_splice();
        case "mask.fromLong.byte" -> op_mask_fromLong_byte();
        case "mask.toLong.byte" -> op_mask_toLong_byte();
        case "mask.fromLong.int" -> op_mask_fromLong_int();
        case "mask.toLong.int" -> op_mask_toLong_int();
        case "mask.fromLong.long" -> op_mask_fromLong_long();
        case "mask.toLong.long" -> op_mask_toLong_long();
        case "load.b2i" -> op_load_b2i();
        case "two.narrow" -> op_two_narrow();
        case "two.full" -> op_two_full();
        case "two.mixed" -> op_two_mixed();
        case "planted.box" -> op_planted_box();
        default -> throw new IllegalArgumentException("no loop named " + loop);
      }
    }
  }

  private static void fill() {
    for (int i = 0; i < ROWS; i++) {
      BA[i] = (byte) (i * 7);
      BB[i] = (byte) (i * 3 + 1);
      SA[i] = (short) (i * 7);
      SB[i] = (short) (i * 3 + 1);
      IA[i] = i * 7 - 3000;
      IB[i] = i * 3 + 1;
      LA[i] = i * 7L - 3000;
      LB[i] = (i * 5L) % LS.length();
      FA[i] = i * 0.7f;
      FB[i] = i * 0.3f + 1f;
      FC[i] = i;
      DA[i] = i * 0.0007 + 0.5;
      DB[i] = i * 0.3 + 1;
      DC[i] = i * 0.1;
      WORDS[i] = 0x5555_5555_5555_5555L;
    }
    LB[0] = 1;
  }

  public static void main(String[] args) {
    if (args[0].equals(HOST_ENTRY)) {
      System.out.println(FALLS_BACK_PREFIX + VarkaHostIsa.conversionsFallBack());
      System.out.println(USE_AVX_PREFIX + VarkaEmitOptions.HOST_USE_AVX);
      System.out.println(FEATURES_PREFIX + VarkaHostIsa.features());
      System.out.println(DONE_PREFIX + HOST_ENTRY);
      return;
    }
    var entry = VarkaOperatorCensusCatalog.named(args[0]);
    int bits = IS.vectorBitSize();
    System.out.println(BEGIN_PREFIX + entry.name() + " bits=" + bits);
    if (bits < entry.minBits()) {
      System.out.println(SKIP_PREFIX + "needs a preferred species of " + entry.minBits()
          + " bits or more");
      System.out.println(DONE_PREFIX + entry.name());
      return;
    }
    if (bits > entry.maxBits()) {
      System.out.println(SKIP_PREFIX + "needs a preferred species of " + entry.maxBits()
          + " bits or less");
      System.out.println(DONE_PREFIX + entry.name());
      return;
    }
    System.setProperty(OPERATOR_PROPERTY, entry.operator());
    fill();
    var loops = entry.loop().split("\\+");
    var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    for (String loop : loops) {
      System.out.println(LOOP_MARKER + loop);
      call(loop, WARMUP_CALLS);
    }
    double worst = 0.0;
    for (String loop : loops) {
      long before = threads.getCurrentThreadAllocatedBytes();
      call(loop, MEASURED_CALLS);
      long bytes = threads.getCurrentThreadAllocatedBytes() - before;
      worst = Math.max(worst, (double) bytes / ((double) MEASURED_CALLS * ROWS));
    }
    System.out.println(ALLOC_PREFIX + entry.name() + " " + String.format("%.3f", worst));
    System.out.println(DONE_PREFIX + entry.name());
  }
}
