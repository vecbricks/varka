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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.DayTimeIntervalType;
import org.apache.spark.sql.types.DayTimeIntervalType$;
import org.apache.spark.sql.types.DecimalType;
import org.apache.spark.sql.types.TimeType;
import org.apache.spark.sql.types.YearMonthIntervalType;
import org.apache.spark.sql.types.YearMonthIntervalType$;

/**
 * The one table of facts about the types Varka reads (VARKA-494): which lane a Spark type takes,
 * or why it takes none; its name in the milestone's census and cast matrix; its SQL spelling; how
 * many bits a row it occupies in Arrow; the forms the tests probe; the lanes its kernels reach;
 * and its conformance suite. Before it, the compiler's {@code laneOf}, the evaluator's Arrow
 * table, the test harness and the Python tools each kept their own copy of these, and nothing
 * made the copies agree.
 *
 * <p>An entry holds a fact only if some code reads it. {@code VarkaTypesSuite} writes the table to
 * {@code sql/varka/types.tsv}, which the Python tools read, and fails when the file is stale.
 *
 * <p>It is compile-time data: built once, read when a shape compiles, never on a batch.
 */
public final class VarkaTypes {

  private VarkaTypes() {
  }

  /**
   * What a kernel does with a column of a type. The set is closed, so a switch over it decides
   * every case.
   */
  public sealed interface Admission {

    /** Read as values on {@code lane}. */
    record OnLane(LaneType lane) implements Admission {
      public OnLane {
        Objects.requireNonNull(lane, "lane");
      }
    }

    /**
     * Read as values on {@code lane} in bands of precision, a decimal's (VARKA-347, VARKA-359):
     * how many words of the lane a row takes depends on its type's precision, so the admission
     * reads the {@code DecimalType} and not only the id. A precision past the last band does not
     * exist, and a negative scale is declined everywhere (the milestone plan's 2.8).
     *
     * <p>Each band names the row that builds its reader. A band whose reader is not built yet is
     * owed: its words exist in the plan but no kernel reads such a column, so
     * {@link VarkaTypes#read(DataType)} answers empty for it and the decline names the row.
     */
    record ByPrecision(LaneType lane, List<Band> bands) implements Admission {
      public ByPrecision {
        Objects.requireNonNull(lane, "lane");
        bands = List.copyOf(bands);
        if (bands.isEmpty()) {
          throw VarkaErrors.illegalArgument("a precision admission has at least one band");
        }
        for (int i = 1; i < bands.size(); i++) {
          if (bands.get(i).maxPrecision() <= bands.get(i - 1).maxPrecision()) {
            throw VarkaErrors.illegalArgument("precision bands ascend: " + bands);
          }
        }
      }

      /** The band holding {@code type}'s precision, or empty for a negative scale. */
      public Optional<Band> band(DecimalType type) {
        if (type.scale() < 0) {
          return Optional.empty();
        }
        for (Band b : bands) {
          if (type.precision() <= b.maxPrecision()) {
            return Optional.of(b);
          }
        }
        return Optional.empty();
      }

      /** Whether a kernel reads a column of {@code type} today: its band's reader is built. */
      public boolean reads(DecimalType type) {
        return band(type).filter(Admission.Band::read).isPresent();
      }

      /** The widest precision a column takes one word at: the last one-word band's. */
      public int oneWordPrecision() {
        int widest = 0;
        for (Band b : bands) {
          if (b.words() == 1) {
            widest = b.maxPrecision();
          }
        }
        return widest;
      }

      /** The widest precision a kernel reads today, or 0 when no band's reader is built. */
      public int maxReadPrecision() {
        int widest = 0;
        for (Band b : bands) {
          if (!b.read()) {
            break;
          }
          widest = b.maxPrecision();
        }
        return widest;
      }
    }

    /**
     * Precisions up to {@code maxPrecision} (above the previous band's) take {@code words} words of
     * the admission's lane a row; {@code row} builds their reader, and {@code read} says whether it
     * is built.
     */
    record Band(int maxPrecision, int words, String row, boolean read) {
      public Band {
        Objects.requireNonNull(row, "row");
        if (words < 1) {
          throw VarkaErrors.illegalArgument("a band has at least one word, not " + words);
        }
      }
    }

    /**
     * Read as two bitmaps, a condition's true and false words, and not as a value: a value of
     * the type is {@code valuesRow}'s to build.
     */
    record AsCondition(String valuesRow) implements Admission {
      public AsCondition {
        Objects.requireNonNull(valuesRow, "valuesRow");
      }
    }

    /** Planned and not built: {@code row} builds the type's lane. */
    record Owed(String row) implements Admission {
      public Owed {
        Objects.requireNonNull(row, "row");
      }
    }

    /** Not read, by a recorded decision. */
    record Declined(String decision) implements Admission {
      public Declined {
        Objects.requireNonNull(decision, "decision");
      }
    }
  }

  /** Whether a type has the conformance suite every type that has landed must have (VARKA-484). */
  public sealed interface Conformance {

    /** The suite {@code Varka<name>ConformanceSuite} holds the type to the contract. */
    record Suite(String name) implements Conformance {
      public Suite {
        Objects.requireNonNull(name, "name");
      }
    }

    /** The type has landed and its suite {@code Varka<name>ConformanceSuite} is {@code row}'s. */
    record Owed(String name, String row) implements Conformance {
      public Owed {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(row, "row");
      }
    }

    /** The type has not landed, so no suite is due. */
    record NotLanded() implements Conformance {
    }
  }

  /**
   * How a row of a type is laid out in its Arrow vector, apart from the lane it computes on: a
   * consumer that sees a long lane must not take it to mean an eight-byte long (the owner's note
   * of 27 October 2026, `m8/VARKA-359.md` 2.3).
   */
  public sealed interface Storage {

    /** A value in its own width, {@code bits} a row: eight to 64. */
    record Plain(int bits) implements Storage {
      public Plain {
        if (bits < 8 || bits > 64 || Integer.bitCount(bits) != 1) {
          throw VarkaErrors.illegalArgument("a plain value is 8, 16, 32 or 64 bits, not " + bits);
        }
      }
    }

    /** One bit a row, a {@code BitVector}'s. */
    record Bitmap() implements Storage {
    }

    /**
     * Sixteen bytes a row, Arrow's decimal slot: the unscaled value's low word, then its high
     * word, little-endian.
     */
    record DecimalSlot() implements Storage {
    }

    /** The bits a row occupies. */
    default int bits() {
      return switch (this) {
        case Plain p -> p.bits();
        case Bitmap b -> 1;
        case DecimalSlot d -> 128;
      };
    }
  }

  /**
   * How a kernel reads a column of one {@code DataType}: the lane it computes on, the words of
   * that lane a row takes, and the storage those words come from.
   */
  public sealed interface Read {

    /** {@code words} words of {@code lane} a row, loaded from {@code storage}. */
    record Lanes(LaneType lane, int words, Storage storage) implements Read {
      public Lanes {
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(storage, "storage");
        if (words < 1) {
          throw VarkaErrors.illegalArgument("a read takes at least one word, not " + words);
        }
      }
    }
  }

  /**
   * The facts about one type.
   *
   * @param census its name in the census and the cast matrix ({@code dev/varka_census.py}), or
   *     null for a type outside them
   * @param sql its SQL spelling as a cast target
   * @param storage how a row is laid out in its Arrow vector
   * @param forms the Spark types of the id the tests probe: each decimal, time precision and
   *     interval field range they read
   * @param lanes the lanes a kernel of the type computes or loads in, which the template
   *     warm-up must reach
   */
  public record Info(VarkaTypeId id, @Nullable String census, String sql, Admission admission,
      Storage storage, List<DataType> forms, List<LaneType> lanes, Conformance conformance) {
    public Info {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(sql, "sql");
      Objects.requireNonNull(admission, "admission");
      Objects.requireNonNull(storage, "storage");
      Objects.requireNonNull(conformance, "conformance");
      forms = List.copyOf(forms);
      lanes = List.copyOf(lanes);
      if (forms.isEmpty()) {
        throw VarkaErrors.illegalArgument(id + " has no form");
      }
    }

    /** The bits a row its Arrow vector holds: one for a bitmap, then eight to 128. */
    public int storageBits() {
      return storage.bits();
    }

    /**
     * The lane a column of the type computes on, if it is read as values, whatever its storage:
     * for a decimal, the lane of every band, since a type id carries no precision. A consumer
     * that loads values asks {@link VarkaTypes#read(DataType)}, which says how many words and
     * from what storage.
     */
    public Optional<LaneType> lane() {
      return switch (admission) {
        case Admission.OnLane(LaneType lane) -> Optional.of(lane);
        case Admission.ByPrecision by -> Optional.of(by.lane());
        case Admission.AsCondition c -> Optional.empty();
        case Admission.Owed o -> Optional.empty();
        case Admission.Declined d -> Optional.empty();
      };
    }

    /**
     * The value Spark's row holds for a lane value whose bits, sign-extended to a long, are
     * {@code bits}: the integral types' boxed value at their width, a floating type's value
     * with those bits, and a condition's {@code Boolean}. The tests' oracles hand it to
     * Spark's {@code eval}, which reads a {@code DOUBLE} input as a {@code Double} and would
     * fail on the {@code Long} a width switch makes of it.
     */
    public Object sparkValue(long bits) {
      if (admission instanceof Admission.AsCondition) {
        return bits != 0L;
      }
      if (admission instanceof Admission.ByPrecision) {
        throw unscaled();
      }
      return switch (lane().orElseThrow(() -> noLane())) {
        case BYTE -> (byte) bits;
        case SHORT -> (short) bits;
        case INT -> (int) bits;
        case LONG -> bits;
        case FLOAT -> Float.intBitsToFloat((int) bits);
        case DOUBLE -> Double.longBitsToDouble(bits);
      };
    }

    /**
     * {@link #sparkValue}'s inverse: the lane bits, sign-extended to a long, of a value Spark's
     * row or {@code eval} gives for the type. A floating value's bits are its raw ones, so a
     * NaN's payload and a zero's sign survive the round trip.
     */
    public long bits(@Nullable Object sparkValue) {
      if (admission instanceof Admission.ByPrecision) {
        throw unscaled();
      }
      OptionalLong bits = laneBits(sparkValue);
      if (bits.isEmpty()) {
        String value = sparkValue == null ? "null" : "a " + sparkValue.getClass().getSimpleName();
        throw VarkaErrors.illegalArgument(value + " is not a " + sql + " value on a lane");
      }
      return bits.getAsLong();
    }

    /**
     * {@link #bits} for a value that need not be one of the type's: its lane bits, or empty for a
     * null, for a value of another class, and for every decimal, whose lane bits are an unscaled
     * value at a scale the type id does not carry. An {@code IN} list's literals are read through
     * it, so a list the lane cannot hold declines where {@link #bits} would throw.
     */
    public OptionalLong laneBits(@Nullable Object sparkValue) {
      if (sparkValue == null || admission instanceof Admission.ByPrecision) {
        return OptionalLong.empty();
      }
      LaneType lane = lane().orElse(null);
      return switch (sparkValue) {
        case Boolean b when admission instanceof Admission.AsCondition ->
            OptionalLong.of(b ? 1L : 0L);
        case Float f when lane == LaneType.FLOAT -> OptionalLong.of(Float.floatToRawIntBits(f));
        case Double d when lane == LaneType.DOUBLE ->
            OptionalLong.of(Double.doubleToRawLongBits(d));
        case Byte b when lane == LaneType.BYTE -> OptionalLong.of(b);
        case Short h when lane == LaneType.SHORT -> OptionalLong.of(h);
        case Integer i when lane == LaneType.INT -> OptionalLong.of(i);
        case Long l when lane == LaneType.LONG -> OptionalLong.of(l);
        default -> OptionalLong.empty();
      };
    }

    private RuntimeException noLane() {
      return VarkaErrors.illegalArgument(sql + " is read on no lane");
    }

    // A decimal's lane bits are its unscaled value, while Spark's value is a Decimal at a scale
    // the id does not carry, so neither direction has an answer here.
    private RuntimeException unscaled() {
      return VarkaErrors.illegalArgument(sql + "'s lane bits are an unscaled value, and Spark's "
          + "value is a Decimal at a scale the type id does not carry");
    }
  }

  /**
   * The id of {@code dataType}, or empty for a type the table does not hold. The one function
   * from a Spark type to the table; every other question goes through it.
   */
  public static Optional<VarkaTypeId> of(DataType dataType) {
    return Optional.ofNullable(idOf(dataType));
  }

  /**
   * {@link #of} without the {@code Optional}, for the evaluator, which asks once per output
   * vector it builds and allocates nothing more to ask.
   */
  public static @Nullable VarkaTypeId idOf(DataType dataType) {
    if (DataTypes.BooleanType.equals(dataType)) {
      return VarkaTypeId.BOOLEAN;
    }
    if (DataTypes.ByteType.equals(dataType)) {
      return VarkaTypeId.BYTE;
    }
    if (DataTypes.ShortType.equals(dataType)) {
      return VarkaTypeId.SHORT;
    }
    if (DataTypes.IntegerType.equals(dataType)) {
      return VarkaTypeId.INT;
    }
    if (DataTypes.LongType.equals(dataType)) {
      return VarkaTypeId.LONG;
    }
    if (DataTypes.FloatType.equals(dataType)) {
      return VarkaTypeId.FLOAT;
    }
    if (DataTypes.DoubleType.equals(dataType)) {
      return VarkaTypeId.DOUBLE;
    }
    if (dataType instanceof DecimalType) {
      return VarkaTypeId.DECIMAL;
    }
    if (DataTypes.DateType.equals(dataType)) {
      return VarkaTypeId.DATE;
    }
    if (dataType instanceof TimeType) {
      return VarkaTypeId.TIME;
    }
    if (DataTypes.TimestampNTZType.equals(dataType)) {
      return VarkaTypeId.TIMESTAMP_NTZ;
    }
    if (dataType instanceof YearMonthIntervalType) {
      return VarkaTypeId.YM_INTERVAL;
    }
    if (dataType instanceof DayTimeIntervalType) {
      return VarkaTypeId.DT_INTERVAL;
    }
    if (DataTypes.TimestampType.equals(dataType)) {
      return VarkaTypeId.TIMESTAMP;
    }
    return null;
  }

  /**
   * Whether {@code dataType} is {@code TIMESTAMP_NTZ}, the zone-free timestamp of microseconds
   * the long lane reads: the test the compiler's NTZ arms share.
   */
  public static boolean isNtz(DataType dataType) {
    return DataTypes.TimestampNTZType.equals(dataType);
  }

  /** The facts about {@code id}. */
  public static Info info(VarkaTypeId id) {
    return Objects.requireNonNull(TABLE.get(id), "every id has an entry");
  }

  /** Every entry, in the id's order. */
  public static List<Info> all() {
    return List.copyOf(TABLE.values());
  }

  /**
   * The lane a kernel reads a column of {@code dataType} on today, one value a lane in its own
   * width, or empty: the question the compiler's plain leaves, the evaluator and the test harness
   * ask. It answers only for {@link Storage.Plain} storage, so a consumer that takes a long lane
   * for an eight-byte long cannot see a decimal, whose sixteen-byte slots only its own leaf reads;
   * {@link #read(DataType)} is the question for every storage.
   */
  public static Optional<LaneType> lane(DataType dataType) {
    return read(dataType).flatMap(r -> switch (r) {
      case Read.Lanes(LaneType lane, int words, Storage storage)
          when storage instanceof Storage.Plain && words == 1 -> Optional.of(lane);
      case Read.Lanes l -> Optional.<LaneType>empty();
    });
  }

  /**
   * How a kernel reads a column of {@code dataType} today, or empty for a type no kernel reads as
   * values: one word of the type's lane over its plain storage for every type but the decimal,
   * and for a decimal the words of its precision's band over the sixteen-byte slot, where that
   * band's reader is built and the scale is not negative.
   */
  public static Optional<Read> read(DataType dataType) {
    return of(dataType).flatMap(id -> {
      Info info = info(id);
      return switch (info.admission()) {
        case Admission.OnLane(LaneType lane) ->
            Optional.of(new Read.Lanes(lane, 1, info.storage()));
        case Admission.ByPrecision by -> dataType instanceof DecimalType d
            ? by.band(d).filter(Admission.Band::read)
                .map(b -> (Read) new Read.Lanes(by.lane(), b.words(), info.storage()))
            : Optional.empty();
        case Admission.AsCondition c -> Optional.empty();
        case Admission.Owed o -> Optional.empty();
        case Admission.Declined d -> Optional.empty();
      };
    });
  }

  /**
   * The values an integral type holds, inclusive: what a cast to it keeps under {@code ANSI}, so
   * the bounds of the range guard a narrowing builds (VARKA-332), and what rows 343 and 350 read
   * for their own narrow targets.
   */
  public record IntegralRange(long min, long max) {
    public IntegralRange {
      if (min > max) {
        throw VarkaErrors.illegalArgument("an empty integral range: [" + min + ", " + max + "]");
      }
    }

    /** Whether {@code other}'s every value is one of this range's. */
    public boolean contains(IntegralRange other) {
      return min <= other.min && other.max <= max;
    }
  }

  /**
   * The range of the integral type a column on {@code lane} holds: {@code TINYINT},
   * {@code SMALLINT}, {@code INT} or {@code BIGINT}, the type's {@code MIN_VALUE} and
   * {@code MAX_VALUE}. A floating lane has none.
   */
  public static IntegralRange integralRange(LaneType lane) {
    return switch (lane) {
      case BYTE -> new IntegralRange(Byte.MIN_VALUE, Byte.MAX_VALUE);
      case SHORT -> new IntegralRange(Short.MIN_VALUE, Short.MAX_VALUE);
      case INT -> new IntegralRange(Integer.MIN_VALUE, Integer.MAX_VALUE);
      case LONG -> new IntegralRange(Long.MIN_VALUE, Long.MAX_VALUE);
      case FLOAT, DOUBLE -> throw VarkaErrors.illegalArgument("the " + lane
          + " lane holds no integral range");
    };
  }

  /**
   * Why a column of {@code dataType} is not read as values, naming the type, for a type the table
   * holds that no lane reads: the row that builds an owed one, the decision for a declined one.
   * Empty for a type a lane reads and for a type the table does not hold, whose column keeps the
   * compiler's general reason.
   */
  public static Optional<String> declineReason(DataType dataType) {
    return of(dataType).flatMap(id -> {
      String column = "a " + dataType.simpleString() + " column";
      return switch (info(id).admission()) {
        case Admission.OnLane on -> Optional.empty();
        case Admission.ByPrecision by -> {
          DecimalType d = (DecimalType) dataType;
          if (d.scale() < 0) {
            yield Optional.of(negativeScale(column));
          }
          Optional<Admission.Band> band = by.band(d).filter(b -> !b.read());
          if (band.isEmpty()) {
            yield Optional.empty();
          }
          int widest = by.maxReadPrecision();
          yield Optional.of(widest == 0
              ? column + ": no operator over a decimal is built yet, row " + band.get().row()
                  + "'s"
              : column + ": precision above " + widest + " is row " + band.get().row() + "'s");
        }
        case Admission.AsCondition c -> Optional.of(column + " read as a value, which row "
            + c.valuesRow() + " builds; it is read as a condition");
        case Admission.Owed o -> Optional.of(column + ": the " + id + " lane is row "
            + o.row() + "'s");
        case Admission.Declined d -> Optional.of(column + ": " + d.decision());
      };
    });
  }

  /**
   * Why a kernel cannot store an output of {@code type}, or empty where the decimal store writes
   * it: the plan-time admission of a {@code WidenDecimal} root against its output's type,
   * which the compiler asks where it wraps a decimal-valued root
   * ({@code VarkaDecimalCompiler.outputRoot}), so a shape the store would write wrongly never
   * fuses. The store writes a long and its sign extension into each slot, so it takes the
   * precisions of a one-word band. A negative scale is declined everywhere, in a column's words,
   * and a wider precision in the store's own, since the value does not fit the long a slot's low
   * word holds and the two-word store is the row the wider band names.
   * The reader's row does not matter here: the store needs no operator over a decimal column.
   * The evaluator's allocator asks it again, as an assertion that no such output got through.
   */
  public static Optional<String> decimalOutputDeclineReason(DecimalType type) {
    if (!(info(VarkaTypeId.DECIMAL).admission() instanceof Admission.ByPrecision by)) {
      throw VarkaErrors.illegalState("DECIMAL's admission is not by precision");
    }
    String noun = "a " + type.simpleString() + " output";
    if (type.scale() < 0) {
      return Optional.of(negativeScale(noun));
    }
    Admission.Band band = by.band(type).orElseThrow();
    return band.words() == 1 ? Optional.empty()
        : Optional.of(noun + ": precision above " + by.oneWordPrecision() + " does not fit the "
            + "long a slot's low word holds, and its two-word store is row " + band.row() + "'s");
  }

  /** The decline of a decimal of negative scale, which no lane takes. */
  private static String negativeScale(String noun) {
    return noun + ": a negative scale is declined everywhere (the milestone plan's 2.8)";
  }

  private static final Map<VarkaTypeId, Info> TABLE = table();

  private static Map<VarkaTypeId, Info> table() {
    var table = new EnumMap<VarkaTypeId, Info>(VarkaTypeId.class);
    for (VarkaTypeId id : VarkaTypeId.values()) {
      table.put(id, entry(id));
    }
    return table;
  }

  // One case per id, with no default, so a new id does not compile until its facts are written.
  private static Info entry(VarkaTypeId id) {
    return switch (id) {
      // A boolean expression is written as an output (a BitVector, VARKA-27); reading one as a
      // value, the cast to a number, is VARKA-332's.
      case BOOLEAN -> new Info(id, "bool", "BOOLEAN", new Admission.AsCondition("332"),
          new Storage.Bitmap(),
          List.of(DataTypes.BooleanType), List.of(), new Conformance.Suite("Boolean"));
      case BYTE -> new Info(id, "byte", "TINYINT", new Admission.OnLane(LaneType.BYTE),
          plain(8),
          List.of(DataTypes.ByteType), List.of(LaneType.BYTE, LaneType.INT),
          new Conformance.Suite("TinyInt"));
      case SHORT -> new Info(id, "short", "SMALLINT", new Admission.OnLane(LaneType.SHORT),
          plain(16),
          List.of(DataTypes.ShortType), List.of(LaneType.SHORT, LaneType.INT),
          new Conformance.Suite("SmallInt"));
      case INT -> new Info(id, "int", "INT", new Admission.OnLane(LaneType.INT),
          plain(32),
          List.of(DataTypes.IntegerType), List.of(LaneType.INT),
          new Conformance.Suite("Int"));
      case LONG -> new Info(id, "bigint", "BIGINT", new Admission.OnLane(LaneType.LONG),
          plain(64),
          List.of(DataTypes.LongType), List.of(LaneType.LONG),
          new Conformance.Suite("BigInt"));
      case FLOAT -> new Info(id, "float", "FLOAT", new Admission.OnLane(LaneType.FLOAT),
          plain(32),
          List.of(DataTypes.FloatType), List.of(LaneType.FLOAT),
          new Conformance.Suite("Float"));
      case DOUBLE -> new Info(id, "double", "DOUBLE", new Admission.OnLane(LaneType.DOUBLE),
          plain(64),
          List.of(DataTypes.DoubleType), List.of(LaneType.DOUBLE),
          new Conformance.Suite("Double"));
      // Every precision is sixteen bytes a row in Arrow (`ArrowUtils.toArrowType`); the tests
      // probe one form on each side of the 64-bit boundary at precision 18. Up to it a value is
      // an unscaled long, one word of the long lane (VARKA-347); above it the value is two words,
      // the low and the high, read by row 359's word leaves, whose comparisons and null tests
      // are its first operators (VarkaDecimalCompiler's pair arm). The int
      // lane, an emission choice for precision up to 9, joins the lanes when step 4 of the row
      // builds that arm: until then a decimal leaf refuses it, so no kernel loads one there.
      // The first operators over the leaf, its comparisons and null tests, are row 348's, which
      // step 5 of VARKA-347 carries (VarkaDecimalCompiler), so the reader is built and no row
      // owes it.
      // VarkaDecimalConformanceSuite holds the pair (DECIMAL(30, 4)) to the contract: the
      // comparisons, fused in part (one scale), are checked; the choices, IN and greatest wait
      // for 359b, the casts for 364, the arithmetic for 360 to 363.
      case DECIMAL -> new Info(id, "dec", "DECIMAL(10, 2)",
          new Admission.ByPrecision(LaneType.LONG, List.of(
              new Admission.Band(VarkaVectorIR.DecimalColumn.MAX_LONG_PRECISION, 1, "347", true),
              new Admission.Band(DecimalWords.MAX_PRECISION, 2, "359", true))),
          new Storage.DecimalSlot(),
          List.of(DataTypes.createDecimalType(10, 2), DataTypes.createDecimalType(30, 4)),
          // VarkaDecimalConformanceSuite holds both representations to the contract: the
          // long to 18 digits and the pair above. The forms the census marks fused in part (one
          // scale) are checked; the rest wait for their rows (VARKA-359.md 10.5).
          List.of(LaneType.LONG), new Conformance.Suite("Decimal"));
      case DATE -> new Info(id, "date", "DATE", new Admission.OnLane(LaneType.INT),
          plain(32),
          List.of(DataTypes.DateType), List.of(LaneType.INT),
          new Conformance.Suite("Date"));
      case TIME -> new Info(id, "time", "TIME", new Admission.OnLane(LaneType.LONG),
          plain(64),
          List.of(new TimeType(0), new TimeType(3), new TimeType(6)), List.of(LaneType.LONG),
          new Conformance.Suite("Time"));
      case TIMESTAMP_NTZ -> new Info(id, "ntz", "TIMESTAMP_NTZ",
          new Admission.OnLane(LaneType.LONG),
          plain(64), List.of(DataTypes.TimestampNTZType),
          List.of(LaneType.LONG), new Conformance.Suite("TimestampNtz"));
      case YM_INTERVAL -> new Info(id, "ym", "INTERVAL MONTH",
          new Admission.OnLane(LaneType.INT),
          plain(32),
          fields(YearMonthIntervalType$.MODULE$.YEAR(), YearMonthIntervalType$.MODULE$.MONTH(),
              YearMonthIntervalType::new),
          List.of(LaneType.INT), new Conformance.Suite("YearMonthInterval"));
      case DT_INTERVAL -> new Info(id, "dt", "INTERVAL DAY",
          new Admission.OnLane(LaneType.LONG),
          plain(64),
          fields(DayTimeIntervalType$.MODULE$.DAY(), DayTimeIntervalType$.MODULE$.SECOND(),
              DayTimeIntervalType::new),
          List.of(LaneType.LONG), new Conformance.Suite("DayTimeInterval"));
      // The zoned type is the same eight bytes as the others on the long lane, but its
      // differences and interval additions are computed on local date-times in the session time
      // zone, which is not lane arithmetic (`m9/SCOPE.md` item 31).
      case TIMESTAMP -> new Info(id, null, "TIMESTAMP",
          new Admission.Declined("its arithmetic is in the session time zone, which is not lane "
              + "arithmetic"),
          plain(64), List.of(DataTypes.TimestampType), List.of(), new Conformance.NotLanded());
    };
  }

  private static Storage plain(int bits) {
    return new Storage.Plain(bits);
  }

  /** An interval type in every field range from {@code first} to {@code last}. */
  private static List<DataType> fields(byte first, byte last,
      BiFunction<Byte, Byte, DataType> type) {
    var out = new ArrayList<DataType>();
    for (byte start = first; start <= last; start++) {
      for (byte end = start; end <= last; end++) {
        out.add(type.apply(start, end));
      }
    }
    return out;
  }
}
