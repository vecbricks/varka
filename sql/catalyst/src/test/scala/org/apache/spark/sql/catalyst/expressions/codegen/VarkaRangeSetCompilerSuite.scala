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

package org.apache.spark.sql.catalyst.expressions.codegen

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.{And, BoundReference, EqualTo, Expression,
  GreaterThan, GreaterThanOrEqual, LessThan, LessThanOrEqual, Literal, Or}
import org.apache.spark.sql.types.{DateType, IntegerType}

/**
 * What `VarkaConditionCompiler.rangeSet` takes for a range set and what it leaves as the
 * comparisons it is written as: the bounds it produces are sorted, merged where ranges overlap
 * or touch, and normalised from strict bounds, equalities and either operand order, so that one
 * set of rows is one shape however the query wrote it. `VarkaRangeSetSuite` checks the answers
 * end to end; this checks the form.
 */
class VarkaRangeSetCompilerSuite extends SparkFunSuite {

  private val k = BoundReference(0, IntegerType, nullable = true)
  private val k2 = BoundReference(1, IntegerType, nullable = true)
  private val d = BoundReference(2, DateType, nullable = true)

  private def between(c: Expression, lo: Int, hi: Int): Expression =
    And(GreaterThanOrEqual(c, Literal(lo)), LessThanOrEqual(c, Literal(hi)))

  private def or(es: Expression*): Or = es.reduceLeft(Or(_, _)).asInstanceOf[Or]

  private def bounds(e: Or): Option[Seq[Int]] = VarkaConditionCompiler.rangeSet(e).map(_._2)

  test("ranges are sorted and merged where they overlap or touch") {
    assert(bounds(or(between(k, 90, 101), between(k, -5, 0), between(k, 1, 4),
      between(k, 95, 99), between(k, 10, 10))) === Some(Seq(-5, 4, 10, 10, 90, 101)))
  }

  test("strict bounds move by one, equalities are one-point ranges, either operand order") {
    assert(bounds(or(
      And(GreaterThan(k, Literal(0)), LessThan(k, Literal(5))),
      And(LessThanOrEqual(Literal(9), k), GreaterThanOrEqual(Literal(11), k)),
      EqualTo(k, Literal(100)),
      EqualTo(Literal(6), k))) === Some(Seq(1, 4, 6, 6, 9, 11, 100, 100)))
  }

  test("an empty range is dropped, and a disjunction of only empty ranges is not a set") {
    val empty = And(GreaterThan(k, Literal(3)), LessThan(k, Literal(4)))
    assert(bounds(or(empty, between(k, 7, 9))) === Some(Seq(7, 9)))
    assert(bounds(or(empty, empty)) === None)
  }

  test("strict bounds at the int extremes stay inside the int range") {
    assert(bounds(or(
      And(GreaterThanOrEqual(k, Literal(Int.MinValue)), LessThan(k, Literal(Int.MinValue + 1))),
      And(GreaterThan(k, Literal(Int.MaxValue - 1)), LessThanOrEqual(k, Literal(Int.MaxValue)))))
      === Some(Seq(Int.MinValue, Int.MinValue, Int.MaxValue, Int.MaxValue)))
  }

  test("a date column's ranges are a set, over date literals") {
    assert(bounds(or(
      And(GreaterThanOrEqual(d, Literal(10, DateType)), LessThanOrEqual(d, Literal(20, DateType))),
      EqualTo(d, Literal(30, DateType)))) === Some(Seq(10, 20, 30, 30)))
  }

  test("what is not a range set over one column is left as it is written") {
    // Two columns.
    assert(bounds(or(between(k, 0, 10), between(k2, 5, 20))) === None)
    // A disjunct that is not a range.
    assert(bounds(or(between(k, 0, 10), GreaterThan(k, Literal(50)))) === None)
    // Two lower bounds, no upper one.
    assert(bounds(or(between(k, 0, 10),
      And(GreaterThan(k, Literal(1)), GreaterThan(k, Literal(2))))) === None)
    // A bound of another type than the column's.
    assert(bounds(or(between(k, 0, 10),
      And(GreaterThanOrEqual(k, Literal(1L)), LessThanOrEqual(k, Literal(2L))))) === None)
    // The same range twice is two disjuncts, so a set, merged to the one range.
    assert(bounds(or(between(k, 0, 10), between(k, 0, 10))) === Some(Seq(0, 10)))
  }
}
