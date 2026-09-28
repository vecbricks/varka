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

package org.apache.spark.sql.execution

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.{Add, Alias, AttributeReference, DateAdd, Literal,
  NamedExpression, UnsafeProjection}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTestWatchdog
import org.apache.spark.sql.catalyst.types.DataTypeUtils
import org.apache.spark.sql.execution.vectorized.{OnHeapColumnVector, WritableColumnVector}
import org.apache.spark.sql.types._
import org.apache.spark.sql.vectorized.{ColumnarBatch, ColumnVector}

/**
 * What [[VarkaVectorProjection]] writes into an output batch's vectors: the same cells and the
 * same null counts as projecting each row into an `UnsafeRow` and converting it, whichever of its
 * two paths it takes, and the direct path exactly for the outputs with a primitive Java type.
 */
class VarkaVectorProjectionSuite extends SparkFunSuite with VarkaTestWatchdog {

  private val rows = 6

  /** Every type the direct path writes, one column each. */
  private val primitives: Seq[AttributeReference] = Seq(
    BooleanType, ByteType, ShortType, IntegerType, LongType, FloatType, DoubleType, DateType,
    TimestampType, TimestampNTZType, TimeType(6), YearMonthIntervalType(),
    DayTimeIntervalType()).zipWithIndex.map { case (t, i) => AttributeReference(s"c$i", t)() }

  /** Types the direct path must not write: a string, a decimal and an array. */
  private val others: Seq[AttributeReference] = Seq(
    AttributeReference("s", StringType)(),
    AttributeReference("dec", DecimalType(20, 4))(),
    AttributeReference("arr", ArrayType(IntegerType))())

  private def vectors(schema: Seq[AttributeReference], n: Int): Array[WritableColumnVector] =
    OnHeapColumnVector.allocateColumns(n, DataTypeUtils.fromAttributes(schema))
      .map(v => v: WritableColumnVector)

  /** A batch over `schema` whose column `c` is null in the rows where `(r + c) % 3 == 0`. */
  private def batch(schema: Seq[AttributeReference]): ColumnarBatch = {
    val columns = vectors(schema, rows)
    for ((column, c) <- columns.zipWithIndex; r <- 0 until rows) {
      if ((r + c) % 3 == 0) {
        column.putNull(r)
      } else {
        val v = r * 7 - 11
        column.dataType match {
          case BooleanType => column.putBoolean(r, v % 2 == 0)
          case ByteType => column.putByte(r, v.toByte)
          case ShortType => column.putShort(r, (v * 300).toShort)
          case IntegerType | DateType | _: YearMonthIntervalType => column.putInt(r, v * 1000)
          case LongType | TimestampType | TimestampNTZType | _: DayTimeIntervalType |
               _: TimeType => column.putLong(r, v * 86400000000L + 1)
          case FloatType => column.putFloat(r, v / 3.0f)
          case DoubleType => column.putDouble(r, v / 7.0)
          case _: StringType =>
            val bytes = s"row $v".getBytes("UTF-8")
            column.putByteArray(r, bytes, 0, bytes.length)
          case d: DecimalType =>
            column.putDecimal(r, org.apache.spark.sql.types.Decimal(v * 1.5), d.precision)
          case ArrayType(IntegerType, _) =>
            column.putArray(r, column.arrayData().getElementsAppended, 2)
            column.arrayData().appendInt(v)
            column.arrayData().appendInt(-v)
        }
      }
    }
    val input = new ColumnarBatch(columns.toArray)
    input.setNumRows(rows)
    input
  }

  private def cell(v: ColumnVector, r: Int): Any = if (v.isNullAt(r)) null else v.dataType match {
    case BooleanType => v.getBoolean(r)
    case ByteType => v.getByte(r)
    case ShortType => v.getShort(r)
    case IntegerType | DateType | _: YearMonthIntervalType => v.getInt(r)
    case LongType | TimestampType | TimestampNTZType | _: DayTimeIntervalType | _: TimeType =>
      v.getLong(r)
    case FloatType => v.getFloat(r)
    case DoubleType => v.getDouble(r)
    case _: StringType => v.getUTF8String(r).toString
    case d: DecimalType => v.getDecimal(r, d.precision, d.scale)
    case ArrayType(IntegerType, _) => v.getArray(r).toIntArray.toSeq
  }

  /**
   * Projects `input` through `projection` and through an `UnsafeRow` and the converter, and
   * checks the two outputs cell by cell and column by column for their null counts.
   */
  private def assertSameAsConversion(
      projectList: Seq[NamedExpression],
      schema: Seq[AttributeReference],
      projection: VarkaVectorProjection): Unit = {
    val input = batch(schema)
    val outputSchema = projectList.map(_.toAttribute)
    val expected = vectors(outputSchema.map(a => AttributeReference(a.name, a.dataType)()), rows)
    val actual = vectors(outputSchema.map(a => AttributeReference(a.name, a.dataType)()), rows)
    try {
      val unsafe = UnsafeProjection.create(projectList, schema)
      val converter = new RowToColumnConverter(DataTypeUtils.fromAttributes(outputSchema))
      val it = input.rowIterator()
      while (it.hasNext) converter.convert(unsafe(it.next()), expected)
      assert(projection.project(input, actual) == rows)
      for (c <- outputSchema.indices) {
        val name = outputSchema(c).name
        for (r <- 0 until rows) {
          assert(cell(actual(c), r) == cell(expected(c), r), s"$name, row $r")
        }
        assert(actual(c).numNulls() == expected(c).numNulls(), s"$name's null count")
      }
    } finally {
      input.close()
      (expected ++ actual).foreach(_.close())
    }
  }

  test("every primitive output type is written straight into its vector, as the conversion " +
    "writes it") {
    val int = primitives(3)
    val date = primitives(7)
    // Each column as it is, and two computed entries; `int` read twice, so the input is copied.
    val projectList: Seq[NamedExpression] = primitives ++ Seq(
      Alias(Add(int, int), "int2")(),
      Alias(DateAdd(date, Literal(1)), "date1")())
    val projection = new VarkaVectorProjection(projectList, primitives)
    assert(projection.writesDirectly)
    assertSameAsConversion(projectList, primitives, projection)
  }

  test("a string, a decimal or an array output takes the conversion, nulls included") {
    for (column <- others) {
      val projection = new VarkaVectorProjection(Seq(column), others)
      assert(!projection.writesDirectly, s"${column.dataType} would be written directly")
      assertSameAsConversion(Seq(column), others, projection)
    }
    // One such output among primitive ones sends the whole projection to the conversion.
    val mixed = Seq(primitives(3), others.head)
    val projection = new VarkaVectorProjection(mixed, primitives ++ others)
    assert(!projection.writesDirectly)
    assertSameAsConversion(mixed, primitives ++ others, projection)
  }
}
