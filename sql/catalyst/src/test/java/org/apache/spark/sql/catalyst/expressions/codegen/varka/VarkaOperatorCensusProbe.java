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

import java.lang.management.ManagementFactory;

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
    var entry = VarkaOperatorCensusCatalog.named(args[0]);
    int bits = IS.vectorBitSize();
    System.out.println(BEGIN_PREFIX + entry.name() + " bits=" + bits);
    if (bits < entry.minBits()) {
      System.out.println(SKIP_PREFIX + "needs a preferred species of " + entry.minBits()
          + " bits or more");
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
