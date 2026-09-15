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

import java.lang.foreign.{MemorySegment, ValueLayout}
import java.time.{Duration, LocalTime}

import org.apache.arrow.vector.{BaseFixedWidthVector, DurationVector, TimeNanoVector}

import org.apache.spark.sql.{QueryTest, Row}
import org.apache.spark.sql.classic.DataFrame
import org.apache.spark.sql.execution.columnar.InMemoryRelation
import org.apache.spark.sql.types.{DataType, DayTimeIntervalType, StructField, StructType, TimeType}
import org.apache.spark.sql.vectorized.ArrowColumnVector

/**
 * Proves that a `TIME(p)` column, and a day-time interval column, survive Varka's Arrow cache
 * path and arrive as eight-byte lanes the morsel can map (milestone 5 task 116).
 *
 * Every piece of that path exists on its own - Spark's Arrow writer and accessors, the cache
 * serializer's column stats, the evaluator's buffer mapping - and nothing composes them for
 * these two types until here. The lanes are checked by mapping the Arrow buffer with the same
 * call `VarkaKernelEvaluator.extractMorsel` uses, `MemorySegment.ofAddress(...).reinterpret`,
 * rather than through `ArrowColumnVector`'s accessor: the accessor would prove Spark's path,
 * and this suite is about Varka's. The checks run inside the cached RDD's partitions, where the
 * Arrow memory lives, and return counts, so no off-heap buffer outlives its batch.
 */
class VarkaTimeArrowCacheSuite extends QueryTest with VarkaSharedSessions {

  private val rows = 1000

  /** The three null patterns the date fixtures use: every 31st row, none, all. */
  private val patterns: Seq[(String, Int => Boolean)] = Seq(
    ("every 31st row null", i => i % 31 == 30),
    ("no nulls", _ => false),
    ("all nulls", _ => true))

  /** A time of day for row `i`, spread over the day, truncated to `p` fractional digits. */
  private def timeAt(i: Int, p: Int): LocalTime = {
    val raw = ((i.toLong * 86_400_000_000_000L) / rows + i.toLong * 1_234_567L) %
      86_400_000_000_000L
    val unit = math.pow(10, 9 - p).toLong
    LocalTime.ofNanoOfDay((raw / unit) * unit)
  }

  /** A day-time interval for row `i`, whole microseconds, both signs. */
  private def intervalAt(i: Int): Duration =
    Duration.ofNanos((i.toLong - rows / 2) * 7_001_000L * 1000L)

  private def frame(dt: DataType, value: Int => Any, isNull: Int => Boolean) = {
    val data = (0 until rows).map(i => Row(if (isNull(i)) null else value(i)))
    spark.createDataFrame(spark.sparkContext.parallelize(data, 1),
      StructType(Seq(StructField("v", dt, nullable = true))))
  }

  /**
   * The in-memory relation the cache manager holds for `df`. A Dataset memoises its
   * `queryExecution`, so after `df.collect()` its executed plan never shows the cached scan;
   * the cache manager's own lookup is the witness that the cache holds this exact plan.
   */
  private def cachedRelation(df: DataFrame): InMemoryRelation =
    spark.sharedState.cacheManager.lookupCachedData(df)
      .getOrElse(fail("the cache manager holds no entry for the frame")).cachedRepresentation

  /**
   * Caches `df`, then inside the cached RDD's own partitions: finds the Arrow vector behind
   * column 0, checks its class and value count, maps its data and validity buffers the way the
   * evaluator does, and counts lanes and validity bits that disagree with `expected` and
   * `isNull`. Returns (batches seen, value count total, wrong lanes, wrong bits, class names).
   */
  private def checkLanes(
      df: DataFrame,
      expected: Array[Long],
      nulls: Array[Boolean],
      vectorClass: Class[_ <: BaseFixedWidthVector]): (Int, Int, Int, Int, Set[String]) = {
    df.cache()
    df.count()
    val relation = cachedRelation(df)
    val attrs = relation.output
    val batches = relation.cacheBuilder.serializer.convertCachedBatchToColumnarBatch(
      relation.cacheBuilder.cachedColumnBuffers, attrs, attrs, spark.sessionState.conf)
    val wanted = vectorClass
    val results = batches.mapPartitions { it =>
      var seen = 0; var count = 0; var wrongLanes = 0; var wrongBits = 0
      val classes = scala.collection.mutable.Set.empty[String]
      var base = 0
      it.foreach { batch =>
        seen += 1
        val v = batch.column(0).asInstanceOf[ArrowColumnVector].getValueVector
        classes += v.getClass.getSimpleName
        require(wanted.isInstance(v), s"expected ${wanted.getSimpleName}, got ${v.getClass}")
        val fixed = v.asInstanceOf[BaseFixedWidthVector]
        val n = fixed.getValueCount
        require(n == batch.numRows, s"value count $n != batch rows ${batch.numRows}")
        // The evaluator's own mapping: address and capacity, nothing else.
        val dataBuf = fixed.getDataBuffer
        val data = MemorySegment.ofAddress(dataBuf.memoryAddress()).reinterpret(dataBuf.capacity())
        require(dataBuf.capacity() >= 8L * n, s"data buffer ${dataBuf.capacity()} < 8 * $n")
        val nullCount = fixed.getNullCount
        val expectedNulls = (base until base + n).count(nulls)
        if (nullCount != expectedNulls) wrongBits += 1
        val validity = if (nullCount == n) null else {
          val vb = fixed.getValidityBuffer
          MemorySegment.ofAddress(vb.memoryAddress()).reinterpret(vb.capacity())
        }
        var i = 0
        while (i < n) {
          val row = base + i
          if (!nulls(row)) {
            val lane = data.get(ValueLayout.JAVA_LONG_UNALIGNED, 8L * i)
            if (lane != expected(row)) wrongLanes += 1
          }
          if (validity != null) {
            val bit = (validity.get(ValueLayout.JAVA_BYTE, i >> 3) >> (i & 7)) & 1
            if ((bit == 1) != !nulls(row)) wrongBits += 1
          }
          i += 1
        }
        base += n
        count += n
      }
      Iterator.single((seen, count, wrongLanes, wrongBits, classes.toSet))
    }.collect()
    df.unpersist()
    results.foldLeft((0, 0, 0, 0, Set.empty[String])) { case (a, b) =>
      (a._1 + b._1, a._2 + b._2, a._3 + b._3, a._4 + b._4, a._5 ++ b._5)
    }
  }

  Seq(0, 3, 6, 9).foreach { p =>
    patterns.foreach { case (pattern, isNull) =>
      test(s"TIME($p) survives the Arrow cache and maps as eight-byte lanes ($pattern)") {
        val dt = TimeType(p)
        val df = frame(dt, i => timeAt(i, p), isNull)
        val uncached = df.collect().toSeq
        // 1. The round trip, and the precision it carries.
        df.cache()
        checkAnswer(df, uncached)
        val cachedType = cachedRelation(df).output.head.dataType
        assert(cachedType === dt, "the cached relation's type must carry the same precision")
        df.unpersist()
        // 2 to 4. The vector class, the lanes, the word.
        val expected = Array.tabulate(rows)(i => timeAt(i, p).toNanoOfDay)
        val nulls = Array.tabulate(rows)(isNull)
        val (seen, count, wrongLanes, wrongBits, classes) =
          checkLanes(df, expected, nulls, classOf[TimeNanoVector])
        assert(seen >= 1 && count === rows, s"saw $seen batches holding $count rows")
        assert(classes === Set("TimeNanoVector"), classes)
        assert(wrongLanes === 0, s"$wrongLanes lanes disagree with the nanoseconds of day")
        assert(wrongBits === 0, s"$wrongBits validity disagreements")
      }
    }
  }

  patterns.foreach { case (pattern, isNull) =>
    test(s"INTERVAL DAY TO SECOND survives the Arrow cache as Duration microseconds ($pattern)") {
      val dt = DayTimeIntervalType()
      val df = frame(dt, intervalAt, isNull)
      val uncached = df.collect().toSeq
      df.cache()
      checkAnswer(df, uncached)
      df.unpersist()
      val expected = Array.tabulate(rows)(i => intervalAt(i).toNanos / 1000L)
      val nulls = Array.tabulate(rows)(isNull)
      val (seen, count, wrongLanes, wrongBits, classes) =
        checkLanes(df, expected, nulls, classOf[DurationVector])
      assert(seen >= 1 && count === rows, s"saw $seen batches holding $count rows")
      assert(classes === Set("DurationVector"), classes)
      assert(wrongLanes === 0, s"$wrongLanes lanes disagree with the microseconds")
      assert(wrongBits === 0, s"$wrongBits validity disagreements")
    }
  }
}
