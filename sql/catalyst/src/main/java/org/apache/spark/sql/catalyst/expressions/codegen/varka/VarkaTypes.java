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
   * The facts about one type.
   *
   * @param census its name in the census and the cast matrix ({@code dev/varka_census.py}), or
   *     null for a type outside them
   * @param sql its SQL spelling as a cast target
   * @param storageBits the bits a row its Arrow vector holds: one for a bitmap, then eight to 128
   * @param forms the Spark types of the id the tests probe: each decimal, time precision and
   *     interval field range they read
   * @param lanes the lanes a kernel of the type computes or loads in, which the template
   *     warm-up must reach
   */
  public record Info(VarkaTypeId id, @Nullable String census, String sql, Admission admission,
      int storageBits, List<DataType> forms, List<LaneType> lanes, Conformance conformance) {
    public Info {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(sql, "sql");
      Objects.requireNonNull(admission, "admission");
      Objects.requireNonNull(conformance, "conformance");
      forms = List.copyOf(forms);
      lanes = List.copyOf(lanes);
      if (forms.isEmpty()) {
        throw VarkaErrors.illegalArgument(id + " has no form");
      }
    }

    /** The lane a column of the type is read on, if it is read as values. */
    public Optional<LaneType> lane() {
      return admission instanceof Admission.OnLane(LaneType lane)
          ? Optional.of(lane) : Optional.empty();
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
    public long bits(Object sparkValue) {
      return switch (sparkValue) {
        case Boolean b when admission instanceof Admission.AsCondition -> b ? 1L : 0L;
        case Float f when lane().orElse(null) == LaneType.FLOAT -> Float.floatToRawIntBits(f);
        case Double d when lane().orElse(null) == LaneType.DOUBLE ->
            Double.doubleToRawLongBits(d);
        case Byte b when lane().orElse(null) == LaneType.BYTE -> b;
        case Short h when lane().orElse(null) == LaneType.SHORT -> h;
        case Integer i when lane().orElse(null) == LaneType.INT -> i;
        case Long l when lane().orElse(null) == LaneType.LONG -> l;
        default -> throw VarkaErrors.illegalArgument("a " + sparkValue.getClass().getSimpleName()
            + " is not a " + sql + " value on a lane");
      };
    }

    private RuntimeException noLane() {
      return VarkaErrors.illegalArgument(sql + " is read on no lane");
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
   * The lane a column of {@code dataType} is read on, or empty for a type no kernel reads as
   * values: the question the compiler's leaves, the evaluator and the test harness ask.
   */
  public static Optional<LaneType> lane(DataType dataType) {
    return of(dataType).flatMap(id -> info(id).lane());
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
        case Admission.AsCondition c -> Optional.of(column + " read as a value, which row "
            + c.valuesRow() + " builds; it is read as a condition");
        case Admission.Owed o -> Optional.of(column + ": the " + id + " lane is row "
            + o.row() + "'s");
        case Admission.Declined d -> Optional.of(column + ": " + d.decision());
      };
    });
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
      case BOOLEAN -> new Info(id, "bool", "BOOLEAN", new Admission.AsCondition("27"), 1,
          List.of(DataTypes.BooleanType), List.of(), new Conformance.Suite("Boolean"));
      case BYTE -> new Info(id, "byte", "TINYINT", new Admission.OnLane(LaneType.BYTE), 8,
          List.of(DataTypes.ByteType), List.of(LaneType.BYTE, LaneType.INT),
          new Conformance.Suite("TinyInt"));
      case SHORT -> new Info(id, "short", "SMALLINT", new Admission.OnLane(LaneType.SHORT), 16,
          List.of(DataTypes.ShortType), List.of(LaneType.SHORT, LaneType.INT),
          new Conformance.Suite("SmallInt"));
      case INT -> new Info(id, "int", "INT", new Admission.OnLane(LaneType.INT), 32,
          List.of(DataTypes.IntegerType), List.of(LaneType.INT),
          new Conformance.Suite("Int"));
      case LONG -> new Info(id, "bigint", "BIGINT", new Admission.OnLane(LaneType.LONG), 64,
          List.of(DataTypes.LongType), List.of(LaneType.LONG),
          new Conformance.Suite("BigInt"));
      case FLOAT -> new Info(id, "float", "FLOAT", new Admission.OnLane(LaneType.FLOAT), 32,
          List.of(DataTypes.FloatType), List.of(LaneType.FLOAT),
          new Conformance.Suite("Float"));
      case DOUBLE -> new Info(id, "double", "DOUBLE", new Admission.OnLane(LaneType.DOUBLE), 64,
          List.of(DataTypes.DoubleType), List.of(LaneType.DOUBLE),
          new Conformance.Suite("Double"));
      // Every precision is sixteen bytes a row in Arrow (`ArrowUtils.toArrowType`); the tests
      // probe one form on each side of the 64-bit boundary at precision 18.
      case DECIMAL -> new Info(id, "dec", "DECIMAL(10, 2)", new Admission.Owed("347"), 128,
          List.of(DataTypes.createDecimalType(10, 2), DataTypes.createDecimalType(30, 4)),
          List.of(), new Conformance.NotLanded());
      case DATE -> new Info(id, "date", "DATE", new Admission.OnLane(LaneType.INT), 32,
          List.of(DataTypes.DateType), List.of(LaneType.INT),
          new Conformance.Suite("Date"));
      case TIME -> new Info(id, "time", "TIME", new Admission.OnLane(LaneType.LONG), 64,
          List.of(new TimeType(0), new TimeType(3), new TimeType(6)), List.of(LaneType.LONG),
          new Conformance.Suite("Time"));
      case TIMESTAMP_NTZ -> new Info(id, "ntz", "TIMESTAMP_NTZ",
          new Admission.OnLane(LaneType.LONG), 64, List.of(DataTypes.TimestampNTZType),
          List.of(LaneType.LONG), new Conformance.Suite("TimestampNtz"));
      case YM_INTERVAL -> new Info(id, "ym", "INTERVAL MONTH",
          new Admission.OnLane(LaneType.INT), 32,
          fields(YearMonthIntervalType$.MODULE$.YEAR(), YearMonthIntervalType$.MODULE$.MONTH(),
              YearMonthIntervalType::new),
          List.of(LaneType.INT), new Conformance.Suite("YearMonthInterval"));
      case DT_INTERVAL -> new Info(id, "dt", "INTERVAL DAY",
          new Admission.OnLane(LaneType.LONG), 64,
          fields(DayTimeIntervalType$.MODULE$.DAY(), DayTimeIntervalType$.MODULE$.SECOND(),
              DayTimeIntervalType::new),
          List.of(LaneType.LONG), new Conformance.Suite("DayTimeInterval"));
      // The zoned type is the same eight bytes as the others on the long lane, but its
      // differences and interval additions are computed on local date-times in the session time
      // zone, which is not lane arithmetic (`m9/SCOPE.md` item 31).
      case TIMESTAMP -> new Info(id, null, "TIMESTAMP",
          new Admission.Declined("its arithmetic is in the session time zone, which is not lane "
              + "arithmetic"),
          64, List.of(DataTypes.TimestampType), List.of(), new Conformance.NotLanded());
    };
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
