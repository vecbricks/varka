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
package org.apache.spark.sql.execution;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.DurationVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.IntervalYearVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TimeNanoVector;
import org.apache.arrow.vector.TimeStampMicroVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.types.pojo.ArrowType;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypeId;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypes;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.util.ArrowUtils;

/**
 * The Arrow vector each type's columns arrive in and its outputs are written to: the evaluator's
 * one table of them (VARKA-474 2.2), read by {@code VarkaEvaluatorBase.isArrowBacked} to admit a
 * batch and by {@code VarkaKernelEvaluator.allocateVector} to build a destination.
 *
 * <p>The table is one switch over the type registry's ids ({@code VarkaTypeId}, VARKA-494) with
 * no {@code default}, so a new type does not compile until it says which vector, if any, holds it
 * here, and which Spark type a vector stores is the registry's answer rather than a second list.
 * Before it, the two functions held the list twice, the admission in a switch over vector classes
 * whose {@code default -> false} declined a lane nobody had wired on every batch, which reads as
 * a slow query rather than an error. That switch keeps a {@code default}, since Arrow's vector
 * classes are not a sealed set, but what it admits is now this table's.
 *
 * <p>Two vectors the evaluator admits belong to no lane, and stay named in {@code isArrowBacked}:
 * a boolean column's {@code BitVector}, read as two bitmaps rather than lane values, and the
 * {@code VarCharVector} a derived input arrives in. The {@code BitVector} is in the table as
 * well, as BOOLEAN's output vector; the evaluator's admission matches a {@code BitVector} before
 * it asks {@link #reads}, so a boolean column is still admitted by its own rule.
 */
final class VarkaLaneArrow {

  private VarkaLaneArrow() {
  }

  /** Builds an empty destination vector of one Arrow class for a Spark type stored in it. */
  @FunctionalInterface
  interface Allocate {
    ValueVector create(String name, DataType dataType, BufferAllocator allocator);
  }

  /**
   * The Arrow vector class a type's lane reads and writes (for BOOLEAN, which no lane reads, the
   * one its outputs are written to), the type's id, and how a destination of it is built, or null
   * for a type whose columns are read and never written.
   */
  record Vector(VarkaTypeId id, Class<? extends ValueVector> vectorClass,
      @Nullable Allocate allocate) {
  }

  /**
   * The vector a type's columns are read from and its outputs written to, or null for a type no
   * kernel stores: each is a fixed-width vector the kernels address directly. BOOLEAN's is a
   * bitmap of whole words, the vector its outputs are written to; its columns are read as
   * conditions, admitted by name in {@code isArrowBacked}.
   */
  static @Nullable Vector of(VarkaTypeId id) {
    return switch (id) {
      // A narrow column's one or two bytes a row (VARKA-329); the kernel stores into it at the
      // type's own width.
      case BYTE -> new Vector(id, TinyIntVector.class,
          (name, type, allocator) -> new TinyIntVector(name, allocator));
      case SHORT -> new Vector(id, SmallIntVector.class,
          (name, type, allocator) -> new SmallIntVector(name, allocator));
      case DATE -> new Vector(id, DateDayVector.class,
          (name, type, allocator) -> new DateDayVector(name, allocator));
      case INT -> new Vector(id, IntVector.class,
          (name, type, allocator) -> new IntVector(name, allocator));
      // A year-month interval is a count of months in an int32 buffer whatever its unit, which
      // rides on the Spark type and never on the buffer, so every unit is one vector class.
      case YM_INTERVAL -> new Vector(id, IntervalYearVector.class,
          (name, type, allocator) -> new IntervalYearVector(name, allocator));
      // The long lane's four: a bigint, a TIME(p) - nanoseconds of day at every precision - a
      // day-time interval and a TIMESTAMP_NTZ, both in microseconds, each built from the Arrow
      // field Spark's own writer builds for the type, so TimeNanoVector carries the precision in
      // its field metadata and the row path reads all four back through ArrowColumnVector. An
      // NTZ column is a TimeStampMicroVector, a class apart from the zoned type's
      // TimeStampMicroTZVector, so this entry admits no zoned column.
      case LONG -> new Vector(id, BigIntVector.class, VarkaLaneArrow::fromField);
      case TIME -> new Vector(id, TimeNanoVector.class, VarkaLaneArrow::fromField);
      case DT_INTERVAL -> new Vector(id, DurationVector.class, VarkaLaneArrow::fromField);
      case TIMESTAMP_NTZ -> new Vector(id, TimeStampMicroVector.class, VarkaLaneArrow::fromField);
      // The floating lanes (VARKA-341): IEEE 754 values in four- and eight-byte buffers, built
      // from Spark's own field as the long lane's are.
      case FLOAT -> new Vector(id, Float4Vector.class, VarkaLaneArrow::fromField);
      case DOUBLE -> new Vector(id, Float8Vector.class, VarkaLaneArrow::fromField);
      // Sixteen bytes a row at every precision, read on the long lane up to precision 18
      // (VARKA-347) once the field passes decimalFieldMatches. Not written: a decimal output is
      // row 157's store, so the entry builds no destination.
      case DECIMAL -> new Vector(id, DecimalVector.class, null);
      // Read by no lane - a boolean column is two bitmaps, admitted by name in isArrowBacked -
      // but written: a boolean output's data bitmap holds the known-true rows and its validity
      // the known ones (VARKA-27), the only vector Spark's ArrowColumnVector reads a BOOLEAN from.
      case BOOLEAN -> new Vector(id, BitVector.class,
          (name, type, allocator) -> new BitVector(name, allocator));
      // Declined by decision (VarkaTypes).
      case TIMESTAMP -> null;
    };
  }

  private static ValueVector fromField(String name, DataType type, BufferAllocator allocator) {
    return ArrowUtils.toArrowField(name, type, true, null, false, Metadata.empty(), false)
        .createVector(allocator);
  }

  /** Every type's vector, in the id's order, built once: the admission reads it on every batch. */
  private static final Vector[] ALL = all();

  private static Vector[] all() {
    List<Vector> out = new ArrayList<>();
    for (VarkaTypeId id : VarkaTypeId.values()) {
      Vector v = of(id);
      if (v != null) {
        out.add(v);
      }
    }
    return out.toArray(new Vector[0]);
  }

  /**
   * Whether {@code vector} is an instance of one of the table's classes, as the type patterns
   * this replaced matched. That includes BOOLEAN's {@code BitVector}: the admission matches a
   * {@code BitVector} first by its own rule and asks this only of the other vectors. A loop over a
   * few entries, so a batch's admission allocates nothing.
   */
  static boolean reads(ValueVector vector) {
    for (Vector v : ALL) {
      if (v.vectorClass().isInstance(vector)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a column {@code vector} holds as a kernel input of the expected decimal scale reads as
   * the leaf does: anything but a {@code DecimalVector} when {@code scale} is negative, the input
   * being no decimal; otherwise a {@code DecimalVector} whose field says 128 bits and that scale.
   * Arrow Java reads any decimal field narrower than 256 bits at sixteen bytes a row, so a
   * {@code Decimal(p, s, 64)} field from another writer would be read at the wrong stride, and a
   * field at another scale is read rounded by Spark and unscaled by the leaf; both take the row
   * path. A {@code Decimal256Vector} is another class, which {@link #reads} refuses already. A
   * field read: the field and its type are the vector's own, so a batch allocates nothing here.
   */
  static boolean decimalFieldMatches(ValueVector vector, int scale) {
    if (!(vector instanceof DecimalVector)) {
      return scale < 0;
    }
    return scale >= 0
        && vector.getField().getType() instanceof ArrowType.Decimal type
        && type.getBitWidth() == 128 && type.getScale() == scale;
  }

  /**
   * An empty destination vector for an output of {@code dataType}, or null where no lane stores
   * the type - a decimal output, which is row 157's.
   */
  static @Nullable ValueVector allocate(String name, DataType dataType,
      BufferAllocator allocator) {
    VarkaTypeId id = VarkaTypes.idOf(dataType);
    for (Vector v : ALL) {
      if (v.id() == id) {
        Allocate allocate = v.allocate();
        return allocate == null ? null : allocate.create(name, dataType, allocator);
      }
    }
    return null;
  }
}
