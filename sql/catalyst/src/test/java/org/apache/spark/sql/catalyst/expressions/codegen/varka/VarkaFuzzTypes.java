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
import java.util.Locale;
import java.util.Random;
import java.util.function.Function;
import java.util.function.Predicate;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypes.Admission;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddDays;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddMonths;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BoolColumn;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Chrono;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DecimalColumn;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;

/**
 * What the two fuzzers know of each type, in one place (VARKA-352): which seeded sequence of the
 * IR fuzzer draws its trees and which node of them reads a column as the type, how a column of
 * it is filled with values, and which columns of it the Spark-level fuzzer's fixture holds. A
 * type row changes the descriptor in its first pull request, a site from {@link Owed} to
 * {@link Present}, and no fuzzer keeps a list of types of its own.
 *
 * <p>{@link #of} is a switch over {@link VarkaTypeId} with no {@code default}, so a type added to
 * the registry does not compile until it has an entry here. Each site of an entry says one of
 * three things, and the set is closed: the fuzzer draws the type there ({@link Present}), a named
 * row will make it ({@link Owed}), or it never will, with the reason ({@link NotApplicable}).
 * {@code sql/varka/fuzz/types.tsv} is the table of those answers, written by
 * {@code VarkaFuzzTypesSuite}; {@code dev/varka_new_lane.py} reads it to say which lane has no
 * fuzzer leaf yet.
 *
 * <p>Nothing here draws by itself. The IR fuzzer's int, long and mixed column draws ask a
 * {@link ValueSource} for each cell, and a new sequence names its seed and draw function in an
 * {@link IrLeaf}; the cast and decimal sequences fill their columns with draws of their own,
 * which their {@link IrLeaf#cells} names. The integral and boolean conformance suites read their
 * values from here ({@code VarkaTypeConformance}), so a type's edges have one home.
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
   * How the IR fuzzer draws trees over columns of a type. Shape {@code k} of the seeded sequence
   * {@code seed} names is {@code roots} over {@code VarkaIrGrammar.shapeRandom(seed, k)}, and a
   * tree reads a column as the type where a node of it passes {@code reads}, the form
   * {@code form} spells. {@code typed} is false for a type whose lane's grammar has no node of
   * the type's own: its form is the lane's column, which is all a lane-generic grammar draws of
   * it. {@code cells} names what fills the sequence's columns: the value sources of the types
   * it lists, or a draw of the sequence's own.
   */
  public record IrLeaf(String form, boolean typed, Predicate<VarkaVectorIR> reads, long seed,
      Function<Random, List<VarkaVectorIR>> roots, String cells) {

    /** Whether {@code node} or a node under it reads a column as the type. */
    public boolean readIn(VarkaVectorIR node) {
      if (reads.test(node)) {
        return true;
      }
      for (VarkaVectorIR child : VarkaVectorIR.childrenOf(node)) {
        if (readIn(child)) {
          return true;
        }
      }
      return false;
    }
  }

  /** What a value source's uniform arm draws: an integer, a float's or a double's bits, a bit. */
  public enum Kind { INTEGRAL, FLOAT, DOUBLE, BOOLEAN }

  /**
   * How a column of a type is filled, as lane bits (the low bits of a {@code long} for the
   * narrower lanes). {@code edges} are the type's own special values; those of an integral or
   * boolean source order as numbers ({@link #numeric}), so a draw inside a bound clamps one to
   * it, and a floating type's edges are bit patterns, drawn as they are.
   *
   * <p>A draw has two arms. The uniform one is a value of the type within plus or minus the
   * bound, from one {@code nextLong} of the case's own stream: for an integral source exactly
   * {@link VarkaIrGrammar#within}, the draw every fuzzer made before this class existed. The edge
   * arm replaces the cell with one of {@code edges} one time in {@code edgeOneIn}, the coin and
   * the edge both drawn from a second stream, so that a fuzzer which turns the arm on changes the
   * cells it replaces and nothing it draws afterwards.
   */
  public record ValueSource(long[] edges, Kind kind) {

    /** The edge share of the committed draws: none, so that every cell is the uniform draw. */
    public static final int NO_EDGES = 0;

    /** Whether the edges order as numbers and are clamped into a draw's bound. */
    public boolean numeric() {
      return kind == Kind.INTEGRAL || kind == Kind.BOOLEAN;
    }

    /**
     * The cell of a column whose values the grammar keeps within plus or minus {@code bound}.
     * {@code main} advances exactly as {@code VarkaIrGrammar.within(main, bound)} advances it, with
     * or without edges, whatever the kind; {@code own} is touched only when {@code edgeOneIn} is
     * positive.
     */
    public long draw(Random main, Random own, long bound, int edgeOneIn) {
      long uniform = uniform(main, bound);
      if (edgeOneIn <= 0 || own.nextInt(edgeOneIn) != 0) {
        return uniform;
      }
      long edge = edges[own.nextInt(edges.length)];
      return numeric() ? Math.max(-bound, Math.min(bound, edge)) : edge;
    }

    /** The uniform arm; a boolean is the high bit, a {@code Random}'s low bits being weakest. */
    private long uniform(Random main, long bound) {
      return switch (kind) {
        case INTEGRAL -> VarkaIrGrammar.within(main, bound);
        case FLOAT -> Integer.toUnsignedLong(
            Float.floatToRawIntBits((float) (unit(main.nextLong()) * bound)));
        case DOUBLE -> Double.doubleToRawLongBits(unit(main.nextLong()) * bound);
        case BOOLEAN -> main.nextLong() >>> 63;
      };
    }

    /** A double in {@code [-1, 1)} from the high 53 bits of {@code bits}. */
    private static double unit(long bits) {
      return (bits >> 11) * 0x1.0p-52;
    }
  }

  /** A column of the Spark-level fuzzer's fixture: its name, SQL type, and where it comes from. */
  public record Column(String name, String sql, boolean derived) {
  }

  /**
   * The columns of a type in {@code VarkaSparkFuzz}'s fixture, the view's output columns:
   * {@code derived} when the view computes the column from the {@code VALUES} columns, not when
   * it reads one by its name ({@code CAST(t AS TIME(6)) AS t} reads {@code t}).
   * {@code VarkaSparkFuzzSuite} holds each to the view's schema.
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

  // The int corpus, which VarkaEmittedBytesSuite pins: its columns hold days, and the last of
  // two or more the month count `add_months` reads (VarkaIrFuzzSuite.drawInt).
  private static final String INT_CELLS = "values:DATE,YM_INTERVAL";

  private static IrLeaf intCorpus(String form, Predicate<VarkaVectorIR> reads) {
    return new IrLeaf(form, true, reads, VarkaIrGrammar.FUZZ_SEED,
        rnd -> VarkaIrGrammar.drawShape(rnd).roots(), INT_CELLS);
  }

  private static boolean column(VarkaVectorIR node, LaneType lane) {
    return node instanceof ColumnRef c && c.lane() == lane;
  }

  private static boolean arithOver(VarkaVectorIR node, LaneType lane) {
    return node instanceof IntArith a && (column(a.left(), lane) || column(a.right(), lane));
  }

  // A calendar field's first child is the days it reads; TruncDateDynamic's second is its level.
  private static final IrLeaf INT_LANE =
      intCorpus("intArith(col)", n -> arithOver(n, LaneType.INT));
  private static final IrLeaf DATE_LEAF = intCorpus("days(col)", n ->
      (n instanceof Chrono && column(VarkaVectorIR.childrenOf(n)[0], LaneType.INT))
          || (n instanceof AddDays a && column(a.days(), LaneType.INT)));
  private static final IrLeaf MONTHS_LEAF = intCorpus("months(col)",
      n -> n instanceof AddMonths a && column(a.months(), LaneType.INT));

  // The long corpus, the second committed one. Its grammar is the lane's integer arithmetic and
  // has no node of TIMESTAMP_NTZ, TIME or DT_INTERVAL: they share the lane's column untyped.
  private static IrLeaf longCorpus(String form, boolean typed, Predicate<VarkaVectorIR> reads) {
    return new IrLeaf(form, typed, reads, VarkaIrGrammar.LONG_FUZZ_SEED,
        rnd -> VarkaIrGrammar.drawLongShape(rnd).roots(), "values:LONG");
  }

  private static final IrLeaf LONG_LANE =
      longCorpus("intArith(col:long)", true, n -> arithOver(n, LaneType.LONG));
  private static final IrLeaf LONG_COLUMN =
      longCorpus("col:long", false, n -> column(n, LaneType.LONG));

  // The narrow sequences (VARKA-352 2.2), fuzz_byte and fuzz_short: the long lane's grammar over
  // the narrow lane's leaves, every cell its type's value source's.
  private static IrLeaf narrowSequence(LaneType lane, long seed) {
    String name = lane.name().toLowerCase(Locale.ROOT);
    return new IrLeaf("intArith(col:" + name + ")", true, n -> arithOver(n, lane), seed,
        rnd -> VarkaIrGrammar.drawNarrowLaneShape(rnd, lane).roots(), "values:" + lane.name());
  }

  // The cast sequence (VARKA-332): a TINYINT, SMALLINT, INT, BIGINT and BOOLEAN column, every cell
  // VarkaIrGrammar.castValue's, which draws the lanes' ends and the narrower lanes' around them.
  private static IrLeaf castSequence(String form, Predicate<VarkaVectorIR> reads) {
    return new IrLeaf(form, true, reads, VarkaIrGrammar.CAST_FUZZ_SEED,
        rnd -> List.of(VarkaIrGrammar.drawCastShape(rnd)), "castValue");
  }

  // The decimal values' sequence (VARKA-348), every cell drawn by its column's precision there.
  private static final IrLeaf DECIMAL_LEAF = new IrLeaf("dec", true,
      n -> n instanceof DecimalColumn, VarkaIrGrammar.DECIMAL_VALUE_FUZZ_SEED,
      rnd -> VarkaIrGrammar.drawDecimalValueShape(rnd).roots(), "decimal draw");

  private static ValueSource integral(long... edges) {
    return new ValueSource(edges, Kind.INTEGRAL);
  }

  /** An integral lane's range ends, their neighbours inside it, and -1, 0 and 1. */
  private static ValueSource integral(LaneType lane) {
    VarkaTypes.IntegralRange r = VarkaTypes.integralRange(lane);
    return integral(r.min(), r.min() + 1, -1, 0, 1, r.max() - 1, r.max());
  }

  private static final ValueSource INT_VALUES = integral(LaneType.INT);
  private static final ValueSource LONG_VALUES = integral(LaneType.LONG);

  /**
   * Days since the epoch: Spark's first and last day, the Gregorian switch and a leap day. The
   * int corpus's bound of 2.5 million days clamps the last day to it.
   */
  private static final ValueSource DATE_VALUES = integral(-719162, -141427, -1, 0, 1, 19782,
      2932896);
  private static final ValueSource NTZ_VALUES = integral(-62135596800000000L, -1, 0, 1,
      86400000000L, 253402300799999999L);
  /** Nanoseconds of the day, each a {@code TIME(6)} value: whole microseconds. */
  private static final ValueSource TIME_VALUES = integral(0, 1000, 43200000000000L,
      86399999999000L);
  private static final ValueSource YM_VALUES = integral(Integer.MIN_VALUE, -12, -1, 0, 1, 12,
      Integer.MAX_VALUE);
  private static final ValueSource DT_VALUES = integral(Long.MIN_VALUE, -86400000000L, -1, 0, 1,
      86400000000L, Long.MAX_VALUE);
  private static final ValueSource BYTE_VALUES = integral(LaneType.BYTE);
  private static final ValueSource SHORT_VALUES = integral(LaneType.SHORT);
  private static final ValueSource BOOLEAN_VALUES = new ValueSource(new long[] {0, 1},
      Kind.BOOLEAN);

  private static long fb(float v) {
    return Integer.toUnsignedLong(Float.floatToRawIntBits(v));
  }

  private static long db(double v) {
    return Double.doubleToRawLongBits(v);
  }

  /**
   * The special values of the floating lanes as bit patterns: both zeros, the canonical NaN and
   * the same NaN with its sign bit set, a NaN with a payload (signalling), both infinities, the
   * largest finite value, the smallest normal and the smallest subnormal.
   */
  private static final ValueSource FLOAT_VALUES = new ValueSource(new long[] {
      fb(0.0f), fb(-0.0f), fb(Float.NaN), 0xFFC00000L, 0x7F800001L, fb(Float.POSITIVE_INFINITY),
      fb(Float.NEGATIVE_INFINITY), fb(Float.MAX_VALUE), fb(Float.MIN_NORMAL), fb(Float.MIN_VALUE)},
      Kind.FLOAT);
  private static final ValueSource DOUBLE_VALUES = new ValueSource(new long[] {
      db(0.0), db(-0.0), db(Double.NaN), 0xFFF8000000000000L, 0x7FF0000000000001L,
      db(Double.POSITIVE_INFINITY), db(Double.NEGATIVE_INFINITY), db(Double.MAX_VALUE),
      db(Double.MIN_NORMAL), db(Double.MIN_VALUE)}, Kind.DOUBLE);

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

  /** The registry's decision for a declined type, which no fuzzer draws. */
  private static <T> Site<T> declined(VarkaTypeId id) {
    if (VarkaTypes.info(id).admission() instanceof Admission.Declined d) {
      return new NotApplicable<>("declined: " + d.decision());
    }
    throw new IllegalStateException(id + " is not declined in the registry");
  }

  /** The entry of {@code id}. Adding a type to {@link VarkaTypeId} fails to compile here. */
  public static FuzzType of(VarkaTypeId id) {
    return switch (id) {
      case BOOLEAN -> entry(id,
          new Present<>(castSequence("bool", n -> n instanceof BoolColumn)),
          new Owed<>("352"), new Present<>(BOOLEAN_VALUES));
      case BYTE -> entry(id,
          new Present<>(narrowSequence(LaneType.BYTE, VarkaIrGrammar.BYTE_FUZZ_SEED)),
          new Owed<>("352"), new Present<>(BYTE_VALUES));
      case SHORT -> entry(id,
          new Present<>(narrowSequence(LaneType.SHORT, VarkaIrGrammar.SHORT_FUZZ_SEED)),
          new Owed<>("352"), new Present<>(SHORT_VALUES));
      case INT -> entry(id, new Present<>(INT_LANE), columns(col("i", "INT")),
          new Present<>(INT_VALUES));
      case LONG -> entry(id, new Present<>(LONG_LANE),
          columns(col("l", "BIGINT"), col("l2", "BIGINT")), new Present<>(LONG_VALUES));
      case FLOAT -> entry(id, new Owed<>("341"),
          columns(derived("f", "FLOAT"), derived("f2", "FLOAT")), new Present<>(FLOAT_VALUES));
      case DOUBLE -> entry(id, new Owed<>("341"),
          columns(derived("x", "DOUBLE"), derived("x2", "DOUBLE")),
          new Present<>(DOUBLE_VALUES));
      case DECIMAL -> entry(id, new Present<>(DECIMAL_LEAF), new Owed<>("347"),
          new Owed<>("347"));
      case DATE -> entry(id, new Present<>(DATE_LEAF),
          columns(col("d", "DATE"), col("d2", "DATE")), new Present<>(DATE_VALUES));
      case TIMESTAMP_NTZ -> entry(id, new Present<>(LONG_COLUMN),
          columns(derived("ntz", "TIMESTAMP_NTZ"), derived("ntz2", "TIMESTAMP_NTZ")),
          new Present<>(NTZ_VALUES));
      case TIME -> entry(id, new Present<>(LONG_COLUMN),
          columns(col("t", "TIME(6)"), col("t2", "TIME(6)")), new Present<>(TIME_VALUES));
      case YM_INTERVAL -> entry(id, new Present<>(MONTHS_LEAF),
          columns(derived("ymm", "INTERVAL MONTH"), derived("ymy", "INTERVAL YEAR"),
              derived("ym", "INTERVAL YEAR TO MONTH")),
          new Present<>(YM_VALUES));
      case DT_INTERVAL -> entry(id, new Present<>(LONG_COLUMN),
          columns(col("dt", "INTERVAL DAY TO SECOND"), col("dt2", "INTERVAL DAY TO SECOND")),
          new Present<>(DT_VALUES));
      case TIMESTAMP -> entry(id, declined(id), declined(id), declined(id));
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
