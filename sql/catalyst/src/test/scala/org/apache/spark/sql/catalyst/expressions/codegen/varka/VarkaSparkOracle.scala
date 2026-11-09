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

package org.apache.spark.sql.catalyst.expressions.codegen.varka

import scala.jdk.OptionConverters._

import org.apache.spark.sql.catalyst.expressions.{Attribute, BindReferences, Expression, GenericInternalRow, RuntimeReplaceable}
import org.apache.spark.sql.types.{DataType, Decimal, DecimalType}

/**
 * Spark's own answer for each output of a compiled kernel (VARKA-276): the Catalyst expression the
 * output was compiled from, evaluated by its interpreted `eval` on the row the kernel saw, with no
 * session. [[VarkaKernelCheck]] holds [[VarkaReferenceEvaluator]] to it on every row before it
 * holds the kernel to the reference, so the oracle of both fuzzers is itself checked against
 * Spark (PQS tests its interpreter against the engine the same way, Rigger and Su, pp. 12-13).
 *
 * `outputs(o)` is output `o`'s expression and `inputs(i)` the attribute kernel input `i` reads.
 * A lane value becomes Catalyst's internal value of the input's type, which for every type a
 * kernel reads is the lane value itself: days for a date, months for a year-month interval,
 * nanoseconds for a `TIME`, microseconds for a day-time interval, the byte or short a narrow
 * lane carries sign-extended into the row's `Long` (VARKA-329), and a `BOOLEAN` column's bit as
 * 1 or 0 (VARKA-484). A floating lane carries its value's raw bits (VARKA-341), which the type
 * registry's conversion turns into the `Float` or `Double` Spark reads, and an answer of either
 * goes back to its raw bits, so a NaN's payload and a zero's sign are compared as they are.
 */
class VarkaSparkOracle(outputs: Seq[Expression], inputs: Seq[Attribute]) {

  private val bound: Seq[Expression] =
    outputs.map(e => BindReferences.bindReference(replaced(e), inputs))

  /**
   * `e` with each `RuntimeReplaceable` swapped for its replacement, as the optimizer's
   * `ReplaceExpressions` does before anything evaluates it: `extract` and its kin have no `eval`.
   */
  private def replaced(e: Expression): Expression = {
    val next = e.transformDown { case r: RuntimeReplaceable => r.replacement }
    if (next.fastEquals(e)) e else replaced(next)
  }

  private val types: Seq[DataType] = inputs.map(_.dataType)

  /**
   * Each input's bits a row as the type registry records them (VARKA-494), for a type a kernel
   * reads - on a lane, or as a condition's bitmap - and 0 for any other, which no kernel input
   * holds: the stored long is the type's internal value at that width.
   */
  private val storage: Seq[Int] = types.map { t =>
    VarkaTypes.of(t).toScala.map(VarkaTypes.info).filter { i =>
      i.lane.isPresent || i.admission.isInstanceOf[VarkaTypes.Admission.AsCondition]
    }.map(_.storageBits).getOrElse(0)
  }

  /** Each floating input's registry entry, whose conversion reads its bits; `None` otherwise. */
  private val floating: Seq[Option[VarkaTypes.Info]] = types.map { t =>
    VarkaTypes.of(t).toScala.map(VarkaTypes.info).filter(_.lane.toScala.exists(_.isFloating))
  }

  /**
   * Whether output `o`'s value on a row of lane values is a NaN whose payload is open by its
   * provenance, [[VarkaSameAnswer.openNaN]] over the expression the oracle evaluates.
   */
  def openNaN(o: Int, row: Seq[Option[Long]]): Boolean =
    VarkaSameAnswer.openNaN(bound(o), new GenericInternalRow(values(row).toArray[Any]))

  /** Spark's answer for output `o` on a row of lane values, `None` for a null input. */
  def answer(o: Int, row: Seq[Option[Long]]): VarkaSparkOracle.Answer =
    answerInternal(o, values(row))

  /**
   * Spark's answer for output `o` on a row of Catalyst's internal values, `null` for a null
   * input: for an input no lane value carries whole, such as a 128-bit decimal's `Decimal`,
   * which a harness builds from the slot's two words.
   */
  def answerInternal(o: Int, values: Seq[Any]): VarkaSparkOracle.Answer = {
    try {
      bound(o).eval(new GenericInternalRow(values.toArray[Any])) match {
        case null => VarkaSparkOracle.Null
        case b: Boolean => VarkaSparkOracle.Value(if (b) 1L else 0L)
        case b: Byte => VarkaSparkOracle.Value(b.toLong)
        case s: Short => VarkaSparkOracle.Value(s.toLong)
        case i: Int => VarkaSparkOracle.Value(i.toLong)
        case l: Long => VarkaSparkOracle.Value(l)
        case f: Float => VarkaSparkOracle.Value(java.lang.Float.floatToRawIntBits(f).toLong)
        case d: Double => VarkaSparkOracle.Value(java.lang.Double.doubleToRawLongBits(d))
        // A decimal output of precision 18 or less, read as the store writes it: the unscaled
        // long at the output's scale (VARKA-157), which the producers build at that scale.
        case d: Decimal if outputs(o).dataType.isInstanceOf[DecimalType] &&
            outputs(o).dataType.asInstanceOf[DecimalType].precision > Decimal.MAX_LONG_DIGITS =>
          // Not a raise: no kernel stores a value above 18 digits yet, so none is compared.
          throw new IllegalArgumentException(
            s"output $o is a ${outputs(o).dataType.sql}, whose two-word store is row 359's")
        case d: Decimal if outputs(o).dataType.isInstanceOf[DecimalType] =>
          val scale = outputs(o).dataType.asInstanceOf[DecimalType].scale
          VarkaSparkOracle.Value(d.toJavaBigDecimal.setScale(scale).unscaledValue.longValueExact)
        case other => throw new IllegalArgumentException(
          s"output $o evaluated to a ${other.getClass.getSimpleName}, which no kernel stores")
      }
    } catch {
      case e: ArithmeticException => VarkaSparkOracle.Raised(e)
      case e: org.apache.spark.SparkThrowable => VarkaSparkOracle.Raised(e.asInstanceOf[Throwable])
      case e: java.time.DateTimeException => VarkaSparkOracle.Raised(e)
    }
  }

  /** A row of lane values as Catalyst's values of the inputs' types. */
  private def values(row: Seq[Option[Long]]): Seq[Any] = row.indices.map { k =>
    (row(k), floating(k), storage(k)) match {
      case (None, _, _) => null
      case (Some(v), Some(info), _) => info.sparkValue(v)
      case (Some(v), None, 1) => v != 0L
      case (Some(v), None, 8) => v.toByte
      case (Some(v), None, 16) => v.toShort
      case (Some(v), None, 32) => v.toInt
      case (Some(v), None, 64) => v
      case _ => throw new IllegalArgumentException(s"no kernel input of type ${types(k)}")
    }
  }

  /** Output `o`'s expression, for a failure message. */
  def sql(o: Int): String = outputs(o).sql
}

object VarkaSparkOracle {
  sealed trait Answer
  /** A value, a predicate's `true` and `false` as 1 and 0. */
  case class Value(v: Long) extends Answer
  case object Null extends Answer
  /** The error Spark raised on the row, where a kernel declines the batch instead. */
  case class Raised(e: Throwable) extends Answer
}
