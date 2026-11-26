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

import scala.jdk.CollectionConverters._

import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.{BaseFixedWidthVector, BigIntVector, Decimal256Vector, DecimalVector}
import org.apache.arrow.vector.types.pojo.{ArrowType, Field, FieldType}

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaReproduction, VarkaTypeId,
  VarkaTypes}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.DecimalType

/**
 * The evaluator's Arrow table held to the type registry (VARKA-494): a type has a vector exactly
 * when a lane reads it or it is an output-only type (BOOLEAN), and every form of the type
 * allocates that vector at the registry's storage width. Without it, a type the registry puts on
 * a lane while the table has no vector for it compiles, fuses, and is declined on every batch,
 * which reads as a slow query.
 */
class VarkaLaneArrowSuite extends SparkFunSuite {

  /**
   * BOOLEAN is read as a condition (`AsCondition`), by no lane, but written to a BitVector, an
   * output vector and not a lane's (VARKA-27): its type width is 0 against one storage bit.
   */
  private val outputOnly = Set(VarkaTypeId.BOOLEAN)

  test("a type has an Arrow vector exactly when a lane reads it, or it is an output vector") {
    for (id <- VarkaTypeId.values()) {
      val info = VarkaTypes.info(id)
      val expected = info.lane().isPresent || outputOnly.contains(id)
      assert((VarkaLaneArrow.of(id) != null) === expected, id.name)
      if (outputOnly.contains(id)) {
        assert(!info.lane().isPresent, s"${id.name} is read by a lane: drop its exemption")
      }
    }
  }

  test("every form of a type a lane writes allocates its vector at the registry's width") {
    val allocator = new RootAllocator(Long.MaxValue)
    try {
      for (id <- VarkaTypeId.values(); v = VarkaLaneArrow.of(id)
          if v != null && v.allocate() != null) {
        val info = VarkaTypes.info(id)
        // Every decimal form, the lane pair's included (VARKA-359), is a sixteen-byte slot.
        for (form <- info.forms().asScala) {
          val vector = VarkaLaneArrow.allocate("x", form, allocator)
          try {
            assert(v.vectorClass().isInstance(vector), s"${form.sql}: ${vector.getClass}")
            assert(VarkaLaneArrow.reads(vector), form.sql)
            val width = vector.asInstanceOf[BaseFixedWidthVector].getTypeWidth
            assert(VarkaTypes.outputWidth(form) ===
              (if (outputOnly.contains(id)) VarkaReproduction.KernelCall.BIT_OUTPUT else width),
              form.sql)
            if (outputOnly.contains(id)) {
              // A bitmap: Arrow's type width is 0 bytes for its one storage bit a row.
              assert(width === 0 && info.storageBits() === 1, form.sql)
            } else {
              assert(width * 8 === info.storageBits(), form.sql)
            }
          } finally {
            vector.close()
          }
        }
      }
    } finally {
      allocator.close()
    }
  }

  test("a decimal output of any precision is written to a vector of Spark's own field, and a " +
      "negative scale fails the allocator's assertion") {
    val allocator = new RootAllocator(Long.MaxValue)
    try {
      for ((p, s) <- Seq((1, 0), (10, 2), (18, 6), (19, 2), (38, 10))) {
        val vector = VarkaLaneArrow.allocate("x", DecimalType(p, s), allocator)
        try {
          // The field Spark's writer builds, so ArrowColumnVector reads it with its long accessor
          // and the cache's statistics see the type's precision and scale.
          assert(vector.getField.getType === new ArrowType.Decimal(p, s, 128))
          assert(VarkaLaneArrow.decimalFieldMatches(vector, s))
          assert(vector.asInstanceOf[BaseFixedWidthVector].getTypeWidth === 16)
        } finally {
          vector.close()
        }
      }
      // The plan refuses a negative scale before it fuses (VarkaTypes'
      // decimalOutputDeclineReason); the allocator asks again, as an assertion.
      val legacy = SQLConf.LEGACY_ALLOW_NEGATIVE_SCALE_OF_DECIMAL_ENABLED.key
      SQLConf.get.setConfString(legacy, "true")
      val negative = try DecimalType(10, -2) finally SQLConf.get.unsetConf(legacy)
      val e = intercept[IllegalStateException](
        VarkaLaneArrow.allocate("x", negative, allocator))
      assert(e.getMessage.contains("negative scale"), e.getMessage)
      assert(e.getMessage.contains("the plan admitted it"), e.getMessage)
    } finally {
      allocator.close()
    }
  }

  test("a decimal column's field must say 128 bits and the scale") {
    val allocator = new RootAllocator(Long.MaxValue)
    try {
      def vector(bitWidth: Int, scale: Int): DecimalVector = new DecimalVector(
        new Field("d", FieldType.nullable(new ArrowType.Decimal(10, scale, bitWidth)), null),
        allocator)
      val ok = vector(128, 2)
      val narrow = vector(64, 2)
      val otherScale = vector(128, 3)
      val wide = new Decimal256Vector("w", allocator, 10, 2)
      val long = new BigIntVector("l", allocator)
      try {
        assert(VarkaLaneArrow.reads(ok))
        assert(VarkaLaneArrow.decimalFieldMatches(ok, 2))
        // Arrow Java reads a 64-bit decimal field at sixteen bytes a row: the class admits it,
        // and only the field check refuses it.
        assert(VarkaLaneArrow.reads(narrow))
        assert(!VarkaLaneArrow.decimalFieldMatches(narrow, 2))
        assert(!VarkaLaneArrow.decimalFieldMatches(otherScale, 2))
        assert(!VarkaLaneArrow.reads(wide))
        // A column that is no decimal: any vector but a decimal one, as before.
        assert(!VarkaLaneArrow.decimalFieldMatches(ok, -1))
        assert(VarkaLaneArrow.decimalFieldMatches(long, -1))
        assert(!VarkaLaneArrow.decimalFieldMatches(long, 2))
      } finally {
        Seq(ok, narrow, otherScale, wide, long).foreach(_.close())
      }
    } finally {
      allocator.close()
    }
  }
}
