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

import java.util.List;

/**
 * The operators the operator census asks about, one entry per cell of its table. It names the
 * loop that exercises an operator and the {@code VectorSupport} function that must appear among
 * C2's inlining decisions for the answer to be read at all, and it touches no vector class, so
 * the suite that forks the probe can read it without defining a species in its own JVM.
 *
 * @param name the cell's name: the lane type, a dot and the operator
 * @param family the group of operators the table lists it under
 * @param loop the probe's loop, or two loops joined by {@code +} for the two-species cases, in the
 *     order they are first made hot
 * @param operator the {@code VectorOperators} constant the loop applies; for a cell that applies
 *     none, the cell's own label, which nothing reads
 * @param anchor the {@code VectorSupport} function whose inlining decision the verdict needs
 * @param rows the plan rows whose designs use the operator
 * @param minBits the smallest preferred species, in bits, the loop is meaningful at
 */
public record VarkaOperatorCensusCatalog(String name, String family, String loop, String operator,
    String anchor, String rows, int minBits) {

  /** The entry whose name is {@code name}. */
  public static VarkaOperatorCensusCatalog named(String name) {
    return ENTRIES.stream().filter(e -> e.name().equals(name)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no census entry named " + name));
  }


  private static final String LANEWISE = "byte and short lanewise";
  private static final String WIDENING = "byte and short widening, narrowing";
  private static final String CONVERSIONS = "conversions";
  private static final String LANE_WIDTH = "lane width";
  private static final String FLOAT_COMPARES = "floating compares";
  private static final String FMA = "fused multiply-add";
  private static final String MATH = "math";
  private static final String UNSIGNED = "unsigned long compares";
  private static final String DEINTERLEAVE = "the de-interleave";
  private static final String MASKS = "masks";
  private static final String TWO_SPECIES = "two species";
  private static final String PLANTED = "planted";

  private static VarkaOperatorCensusCatalog of(String lane, String operator, String family,
      String loop, String anchor, String rows) {
    return wide(lane, operator, family, loop, anchor, rows, 0);
  }

  private static VarkaOperatorCensusCatalog wide(String lane, String operator, String family,
      String loop, String anchor, String rows, int minBits) {
    return new VarkaOperatorCensusCatalog(lane + "." + operator, family, loop, operator, anchor,
        rows, minBits);
  }

  /** Every entry, in the order the table lists them. */
  public static final List<VarkaOperatorCensusCatalog> ENTRIES = List.of(
      of("byte", "ADD", LANEWISE, "bin.byte", "binaryOp", "329, 330"),
      of("byte", "SUB", LANEWISE, "bin.byte", "binaryOp", "329, 330"),
      of("byte", "MUL", LANEWISE, "bin.byte", "binaryOp", "329, 330"),
      of("byte", "SADD", LANEWISE, "bin.byte", "binaryOp", "329, 330"),
      of("byte", "SSUB", LANEWISE, "bin.byte", "binaryOp", "329, 330"),
      of("byte", "EQ", LANEWISE, "cmp.byte", "compare", "329, 330"),
      of("byte", "LT", LANEWISE, "cmp.byte", "compare", "329, 330"),
      of("byte", "LE", LANEWISE, "cmp.byte", "compare", "329, 330"),
      of("byte", "LSHL", LANEWISE, "shift.byte", "broadcastInt", "329, 330"),
      of("byte", "LSHR", LANEWISE, "shift.byte", "broadcastInt", "329, 330"),
      of("byte", "ASHR", LANEWISE, "shift.byte", "broadcastInt", "329, 330"),
      of("short", "ADD", LANEWISE, "bin.short", "binaryOp", "329, 330"),
      of("short", "SUB", LANEWISE, "bin.short", "binaryOp", "329, 330"),
      of("short", "MUL", LANEWISE, "bin.short", "binaryOp", "329, 330"),
      of("short", "SADD", LANEWISE, "bin.short", "binaryOp", "329, 330"),
      of("short", "SSUB", LANEWISE, "bin.short", "binaryOp", "329, 330"),
      of("short", "EQ", LANEWISE, "cmp.short", "compare", "329, 330"),
      of("short", "LT", LANEWISE, "cmp.short", "compare", "329, 330"),
      of("short", "LE", LANEWISE, "cmp.short", "compare", "329, 330"),
      of("short", "LSHL", LANEWISE, "shift.short", "broadcastInt", "329, 330"),
      of("short", "LSHR", LANEWISE, "shift.short", "broadcastInt", "329, 330"),
      of("short", "ASHR", LANEWISE, "shift.short", "broadcastInt", "329, 330"),
      of("byte", "B2I", WIDENING, "conv.B2I", "convert", "329, 330"),
      of("short", "S2I", WIDENING, "conv.S2I", "convert", "329, 330"),
      of("int", "I2B", WIDENING, "conv.I2B", "convert", "329, 330"),
      of("int", "I2S", WIDENING, "conv.I2S", "convert", "329, 330"),
      of("long", "L2D", CONVERSIONS, "conv.L2D", "convert", "30, 343, 344"),
      of("double", "D2L", CONVERSIONS, "conv.D2L", "convert", "30, 343, 344"),
      of("float", "F2D", CONVERSIONS, "conv.F2D", "convert", "30, 343, 344"),
      of("double", "D2F", CONVERSIONS, "conv.D2F", "convert", "30, 343, 344"),
      of("int", "I2D", CONVERSIONS, "conv.I2D", "convert", "30, 343, 344"),
      of("long", "L2F", CONVERSIONS, "conv.L2F", "convert", "30, 343, 344"),
      of("int", "I2L", LANE_WIDTH, "conv.I2L", "convert", "28"),
      of("long", "L2I", LANE_WIDTH, "conv.L2I", "convert", "28"),
      of("int", "I2F", CONVERSIONS, "conv.I2F", "convert", "343, 344"),
      of("float", "F2I", CONVERSIONS, "conv.F2I", "convert", "343, 344"),
      of("double", "D2I", CONVERSIONS, "conv.D2I", "convert", "343, 344"),
      wide("int", "B2I_load", WIDENING, "load.b2i", "convert", "329, 330", 256),
      of("float", "EQ", FLOAT_COMPARES, "cmp.float", "compare", "341"),
      of("float", "LT", FLOAT_COMPARES, "cmp.float", "compare", "341"),
      of("float", "LE", FLOAT_COMPARES, "cmp.float", "compare", "341"),
      of("float", "IS_NAN", FLOAT_COMPARES, "test.float", "compare", "341"),
      of("float", "FMA", FMA, "fma.float", "ternaryOp", "30, 346, 366"),
      of("double", "EQ", FLOAT_COMPARES, "cmp.double", "compare", "341"),
      of("double", "LT", FLOAT_COMPARES, "cmp.double", "compare", "341"),
      of("double", "LE", FLOAT_COMPARES, "cmp.double", "compare", "341"),
      of("double", "IS_NAN", FLOAT_COMPARES, "test.double", "compare", "341"),
      of("double", "FMA", FMA, "fma.double", "ternaryOp", "30, 346, 366"),
      of("double", "EXP", MATH, "un.double", "libraryUnaryOp", "345, 356 to 358"),
      of("double", "LOG", MATH, "un.double", "libraryUnaryOp", "345, 356 to 358"),
      of("double", "LOG10", MATH, "un.double", "libraryUnaryOp", "345, 356 to 358"),
      of("double", "SIN", MATH, "un.double", "libraryUnaryOp", "345, 356 to 358"),
      of("double", "COS", MATH, "un.double", "libraryUnaryOp", "345, 356 to 358"),
      of("double", "TAN", MATH, "un.double", "libraryUnaryOp", "345, 356 to 358"),
      of("double", "ASIN", MATH, "un.double", "libraryUnaryOp", "345, 356 to 358"),
      of("double", "ACOS", MATH, "un.double", "libraryUnaryOp", "345, 356 to 358"),
      of("double", "ATAN", MATH, "un.double", "libraryUnaryOp", "345, 356 to 358"),
      of("double", "SINH", MATH, "un.double", "libraryUnaryOp", "345, 356 to 358"),
      of("double", "COSH", MATH, "un.double", "libraryUnaryOp", "345, 356 to 358"),
      of("double", "TANH", MATH, "un.double", "libraryUnaryOp", "345, 356 to 358"),
      of("double", "EXPM1", MATH, "un.double", "libraryUnaryOp", "345, 356 to 358"),
      of("double", "LOG1P", MATH, "un.double", "libraryUnaryOp", "345, 356 to 358"),
      of("double", "CBRT", MATH, "un.double", "libraryUnaryOp", "345, 356 to 358"),
      of("double", "SQRT", MATH, "un.double", "unaryOp", "345, 356 to 358"),
      of("double", "POW", MATH, "bin.double", "libraryBinaryOp", "345, 356 to 358"),
      of("double", "ATAN2", MATH, "bin.double", "libraryBinaryOp", "345, 356 to 358"),
      of("double", "HYPOT", MATH, "bin.double", "libraryBinaryOp", "345, 356 to 358"),
      of("long", "ULT", UNSIGNED, "cmp.long", "compare", "359, 360"),
      of("long", "UGT", UNSIGNED, "cmp.long", "compare", "359, 360"),
      of("long", "ULE", UNSIGNED, "cmp.long", "compare", "359, 360"),
      of("long", "UGE", UNSIGNED, "cmp.long", "compare", "359, 360"),
      of("long", "rearrange2", DEINTERLEAVE, "deint.rearrange2", "rearrangeOp", "347, 359"),
      of("long", "selectFrom", DEINTERLEAVE, "deint.selectFrom", "selectFromOp", "347, 359"),
      of("long", "compress", DEINTERLEAVE, "deint.compress", "compressExpandOp", "347, 359"),
      of("byte", "fromLong", MASKS, "mask.fromLong.byte", "fromBitsCoerced", "331, 375, 27"),
      of("byte", "toLong", MASKS, "mask.toLong.byte", "maskReductionCoerced", "331, 375, 27"),
      of("int", "fromLong", MASKS, "mask.fromLong.int", "fromBitsCoerced", "331, 375, 27"),
      of("int", "toLong", MASKS, "mask.toLong.int", "maskReductionCoerced", "331, 375, 27"),
      of("long", "fromLong", MASKS, "mask.fromLong.long", "fromBitsCoerced", "331, 375, 27"),
      of("long", "toLong", MASKS, "mask.toLong.long", "maskReductionCoerced", "331, 375, 27"),
      wide("byte", "narrow_then_full", TWO_SPECIES, "two.narrow+two.full", "binaryOp", "329", 256),
      wide("byte", "full_then_narrow", TWO_SPECIES, "two.full+two.narrow", "binaryOp", "329", 256),
      wide("byte", "both_in_one_loop", TWO_SPECIES, "two.mixed", "binaryOp", "329", 256),
      wide("byte", "widen_then_full", TWO_SPECIES, "load.b2i+two.full", "binaryOp", "329", 256),
      wide("byte", "full_then_widen", TWO_SPECIES, "two.full+load.b2i", "binaryOp", "329", 256),
      new VarkaOperatorCensusCatalog("planted.box", PLANTED, "planted.box", "",
          "load", "", 0),
      new VarkaOperatorCensusCatalog("planted.fallback", PLANTED, "bin.long", "ADD",
          "binaryOp", "", 0));
}
