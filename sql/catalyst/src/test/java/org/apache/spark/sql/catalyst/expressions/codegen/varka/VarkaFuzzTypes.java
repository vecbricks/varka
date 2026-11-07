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
import java.util.Random;
import java.util.function.Function;
import java.util.function.IntFunction;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;

/**
 * What the two fuzzers know of each type, in one place (VARKA-352): how the IR fuzzer builds a
 * column leaf of it and which seeded sequence draws its trees, how a column of it is filled with
 * values, and which columns of it the Spark-level fuzzer's fixture holds. A type row changes the
 * descriptor in its first pull request, a site from {@link Owed} to {@link Present}, and no fuzzer
 * keeps a list of types of its own.
 *
 * <p>{@link #of} is a switch over {@link VarkaTypeId} with no {@code default}, so a type added to
 * the registry does not compile until it has an entry here. Each site of an entry says one of
 * three things, and the set is closed: the fuzzer draws the type there ({@link Present}), a named
 * row will make it ({@link Owed}), or it never will, with the reason ({@link NotApplicable}).
 * {@code sql/varka/fuzz/types.tsv} is the table of those answers, written by
 * {@code VarkaFuzzTypesSuite}; {@code dev/varka_new_lane.py} reads it to say which lane has no
 * fuzzer leaf yet.
 *
 * <p>Nothing here draws by itself. The IR fuzzer's three column draws ask a {@link ValueSource}
 * for each cell, and a new sequence names its seed and draw function in an {@link IrLeaf}.
 */
public final class VarkaFuzzTypes {

  private VarkaFuzzTypes() {
  }

  // -------------------------------------------------------------------------------------------
  // The sites
  // -------------------------------------------------------------------------------------------

  /** One answer to "does this fuzzer draw this type": drawn, owed to a row, or never. */
  public sealed interface Site<T> {
  }

  /** The fuzzer draws the type, through {@code value}. */
  public record Present<T>(T value) implements Site<T> {
  }

  /** The fuzzer does not draw the type yet; {@code row} is the plan row that makes it. */
  public record Owed<T>(String row) implements Site<T> {
  }

  /** The fuzzer never draws the type, for {@code reason}. */
  public record NotApplicable<T>(String reason) implements Site<T> {
  }

  // -------------------------------------------------------------------------------------------
  // What a site holds
  // -------------------------------------------------------------------------------------------

  /**
   * How the IR fuzzer draws trees over columns of a type: {@code leaf} builds the column leaf of
   * ordinal {@code o}, and {@code roots} draws the roots of one shape of the sequence the type's
   * trees come from, which {@code seed} names ({@code VarkaIrGrammar.shapeRandom(seed, k)} is
   * shape {@code k}'s generator). Types of one lane share the lane's committed sequence, since
   * the grammar draws the lane and not the type; a type that needs its own sequence names its
   * own seed.
   */
  public record IrLeaf(LaneType lane, IntFunction<VarkaVectorIR> leaf, long seed,
      Function<Random, List<VarkaVectorIR>> roots) {
  }

  /**
   * How a column of a type is filled, as lane bits (the low bits of a {@code long} for the
   * narrower lanes). {@code edges} are the type's own special values, and {@code numeric} says
   * they order as numbers, so a draw inside a bound can clamp one to it; a floating type's
   * edges are bit patterns and are drawn as they are.
   *
   * <p>A draw has two arms. The uniform one is {@link VarkaIrGrammar#within}, the draw every
   * fuzzer made before this class existed, from the case's own stream. The edge arm replaces the
   * cell with one of {@code edges} one time in {@code edgeOneIn}, the coin and the edge both
   * drawn from a second stream, so that a fuzzer which turns the arm on changes the cells it
   * replaces and nothing it draws afterwards.
   */
  public record ValueSource(long[] edges, boolean numeric) {

    /** The edge share of the committed draws: none, so that every cell is the uniform draw. */
    public static final int NO_EDGES = 0;

    /**
     * The cell of a column whose values the grammar keeps within plus or minus {@code bound}.
     * {@code main} advances exactly as {@code VarkaIrGrammar.within(main, bound)} advances it, with
     * or without edges; {@code own} is touched only when {@code edgeOneIn} is positive.
     */
    public long draw(Random main, Random own, long bound, int edgeOneIn) {
      long uniform = VarkaIrGrammar.within(main, bound);
      if (edgeOneIn <= 0 || own.nextInt(edgeOneIn) != 0) {
        return uniform;
      }
      long edge = edges[own.nextInt(edges.length)];
      return numeric ? Math.max(-bound, Math.min(bound, edge)) : edge;
    }
  }

  /** A column of the Spark-level fuzzer's fixture: its name, SQL type, and where it comes from. */
  public record Column(String name, String sql, boolean derived) {
  }

  /**
   * The columns of a type in {@code VarkaSparkFuzz}'s fixture, {@code derived} when the fixture
   * computes the column from the {@code VALUES} columns and does not hold it as one.
   */
  public record SparkColumns(List<Column> columns) {
  }

  /**
   * Everything the fuzzers know of one type. {@code tlpShapes} are the predicate templates the
   * partition check takes over the type's columns; no step has drawn one yet, so every list is
   * empty (VARKA-352 2.4).
   */
  public record FuzzType(VarkaTypeId id, Site<IrLeaf> ir, Site<SparkColumns> spark,
      Site<ValueSource> values, List<String> tlpShapes) {
  }

  // -------------------------------------------------------------------------------------------
  // The entries
  // -------------------------------------------------------------------------------------------

  /** The int lane's trees, the committed corpus {@code VarkaEmittedBytesSuite} pins. */
  private static final IrLeaf INT_LANE = new IrLeaf(LaneType.INT, ColumnRef::new,
      VarkaIrGrammar.FUZZ_SEED, rnd -> VarkaIrGrammar.drawShape(rnd).roots());

  /** The long lane's trees, the second committed corpus. */
  private static final IrLeaf LONG_LANE = new IrLeaf(LaneType.LONG,
      o -> new ColumnRef(o, LaneType.LONG), VarkaIrGrammar.LONG_FUZZ_SEED,
      rnd -> VarkaIrGrammar.drawLongShape(rnd).roots());

  private static ValueSource integral(long... edges) {
    return new ValueSource(edges, true);
  }

  private static final ValueSource INT_VALUES = integral(Integer.MIN_VALUE,
      Integer.MIN_VALUE + 1L, -1, 0, 1, Integer.MAX_VALUE - 1L, Integer.MAX_VALUE);
  private static final ValueSource LONG_VALUES = integral(Long.MIN_VALUE, Long.MIN_VALUE + 1,
      -1, 0, 1, Long.MAX_VALUE - 1, Long.MAX_VALUE);

  /** Days since the epoch: Spark's first and last day, the Gregorian switch and a leap day. */
  private static final ValueSource DATE_VALUES = integral(-719162, -141427, -1, 0, 1, 19782,
      2932896);
  private static final ValueSource NTZ_VALUES = integral(-62135596800000000L, -1, 0, 1,
      86400000000L, 253402300799999999L);
  private static final ValueSource TIME_VALUES = integral(0, 1, 43200000000000L,
      86399999999999L);
  private static final ValueSource YM_VALUES = integral(Integer.MIN_VALUE, -12, -1, 0, 1, 12,
      Integer.MAX_VALUE);
  private static final ValueSource DT_VALUES = integral(Long.MIN_VALUE, -86400000000L, -1, 0, 1,
      86400000000L, Long.MAX_VALUE);
  private static final ValueSource BYTE_VALUES = integral(-128, -127, -1, 0, 1, 126, 127);
  private static final ValueSource SHORT_VALUES = integral(-32768, -32767, -1, 0, 1, 32766,
      32767);
  private static final ValueSource BOOLEAN_VALUES = integral(0, 1);

  private static long f(float v) {
    return Integer.toUnsignedLong(Float.floatToRawIntBits(v));
  }

  private static long d(double v) {
    return Double.doubleToRawLongBits(v);
  }

  /**
   * The special values of the floating lanes as bit patterns: both zeros, the canonical NaN and
   * the same NaN with its sign bit set, a NaN with a payload (signalling), both infinities, the
   * largest finite value, the smallest normal and the smallest subnormal.
   */
  private static final ValueSource FLOAT_VALUES = new ValueSource(new long[] {
      f(0.0f), f(-0.0f), f(Float.NaN), 0xFFC00000L, 0x7F800001L, f(Float.POSITIVE_INFINITY),
      f(Float.NEGATIVE_INFINITY), f(Float.MAX_VALUE), f(Float.MIN_NORMAL), f(Float.MIN_VALUE)},
      false);
  private static final ValueSource DOUBLE_VALUES = new ValueSource(new long[] {
      d(0.0), d(-0.0), d(Double.NaN), 0xFFF8000000000000L, 0x7FF0000000000001L,
      d(Double.POSITIVE_INFINITY), d(Double.NEGATIVE_INFINITY), d(Double.MAX_VALUE),
      d(Double.MIN_NORMAL), d(Double.MIN_VALUE)}, false);

  private static Column col(String name, String sql) {
    return new Column(name, sql, false);
  }

  private static Column derived(String name, String sql) {
    return new Column(name, sql, true);
  }

  private static Site<SparkColumns> columns(Column... columns) {
    return new Present<>(new SparkColumns(List.of(columns)));
  }

  private static FuzzType entry(VarkaTypeId id, Site<IrLeaf> ir, Site<SparkColumns> spark,
      Site<ValueSource> values) {
    return new FuzzType(id, ir, spark, values, List.of());
  }

  /** The entry of {@code id}. Adding a type to {@link VarkaTypeId} fails to compile here. */
  public static FuzzType of(VarkaTypeId id) {
    return switch (id) {
      case BOOLEAN -> entry(id, new Owed<>("331"), new Owed<>("352"),
          new Present<>(BOOLEAN_VALUES));
      case BYTE -> entry(id, new Owed<>("352"), new Owed<>("352"), new Present<>(BYTE_VALUES));
      case SHORT -> entry(id, new Owed<>("352"), new Owed<>("352"),
          new Present<>(SHORT_VALUES));
      case INT -> entry(id, new Present<>(INT_LANE),
          columns(col("i", "INT"), col("m", "INT"), col("y", "INT"), col("mm", "INT")),
          new Present<>(INT_VALUES));
      case LONG -> entry(id, new Present<>(LONG_LANE),
          columns(col("l", "BIGINT"), col("l2", "BIGINT")), new Present<>(LONG_VALUES));
      case FLOAT -> entry(id, new Owed<>("341"),
          columns(derived("f", "FLOAT"), derived("f2", "FLOAT")), new Present<>(FLOAT_VALUES));
      case DOUBLE -> entry(id, new Owed<>("341"),
          columns(derived("x", "DOUBLE"), derived("x2", "DOUBLE")),
          new Present<>(DOUBLE_VALUES));
      case DECIMAL -> entry(id, new Owed<>("347"), new Owed<>("347"), new Owed<>("347"));
      case DATE -> entry(id, new Present<>(INT_LANE),
          columns(col("d", "DATE"), col("d2", "DATE")), new Present<>(DATE_VALUES));
      case TIMESTAMP_NTZ -> entry(id, new Present<>(LONG_LANE),
          columns(derived("ntz", "TIMESTAMP_NTZ"), derived("ntz2", "TIMESTAMP_NTZ")),
          new Present<>(NTZ_VALUES));
      case TIME -> entry(id, new Present<>(LONG_LANE),
          columns(col("t", "TIME(6)"), col("t2", "TIME(6)")), new Present<>(TIME_VALUES));
      case YM_INTERVAL -> entry(id, new Present<>(INT_LANE),
          columns(derived("ymm", "INTERVAL MONTH"), derived("ymy", "INTERVAL YEAR"),
              derived("ym", "INTERVAL YEAR TO MONTH")),
          new Present<>(YM_VALUES));
      case DT_INTERVAL -> entry(id, new Present<>(LONG_LANE),
          columns(col("dt", "INTERVAL DAY TO SECOND"), col("dt2", "INTERVAL DAY TO SECOND")),
          new Present<>(DT_VALUES));
      case TIMESTAMP -> {
        var never = "declined: its arithmetic is in the session time zone";
        yield entry(id, new NotApplicable<>(never), new NotApplicable<>(never),
            new NotApplicable<>(never));
      }
    };
  }

  /** The value source of {@code id}, for a type that has one. */
  public static ValueSource values(VarkaTypeId id) {
    return switch (of(id).values()) {
      case Present<ValueSource> p -> p.value();
      case Owed<ValueSource> _ -> throw new IllegalStateException(id + " has no values yet");
      case NotApplicable<ValueSource> n -> throw new IllegalStateException(id + ": " + n.reason());
    };
  }
}
