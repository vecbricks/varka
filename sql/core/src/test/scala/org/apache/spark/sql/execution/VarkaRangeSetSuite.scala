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

import org.apache.spark.sql.{QueryTest, SparkSession}

/**
 * A disjunction of ranges over one int or date column - `c between a and b or c between d and
 * e or ...`, the partition-key filter a BI tool writes - compiles to one range set, which the
 * kernel evaluates with a loop over a table of bounds rather than as a tree of comparisons. These
 * tests run such filters on the row engine and on Varka over the same Arrow-cached data and
 * require the same answer, with the kernel having run: TPC-DS `modified-q3`'s 200 ranges, and the
 * shapes the compiler sorts, merges and normalises - ranges out of order, overlapping, touching,
 * written with strict bounds or as equalities, in either operand order - over nulls and the int
 * extremes.
 */
class VarkaRangeSetSuite extends QueryTest with VarkaSharedSessions {

  private def cacheKeys(session: SparkSession): Unit = {
    val values: Seq[Integer] = Seq[Integer](null, Int.MinValue, Int.MinValue + 1, -5, -1, 0, 1,
      4, 5, 6, 9, 10, 11, 99, 100, 101, Int.MaxValue - 1, Int.MaxValue) ++
      (0 until 3000).map(i => Integer.valueOf(2415022 + (i * 7919) % 73049)) ++
      Seq.fill[Integer](40)(null)
    session.createDataFrame(values.zipWithIndex.map { case (v, i) => (v, i) })
      .toDF("k", "i")
      .selectExpr("k", "i", "date_add(date'2000-01-01', k % 20000) AS d")
      .createOrReplaceTempView("varka_range_keys")
    session.catalog.cacheTable("varka_range_keys")
  }

  /** The query on both sessions: the same rows, and on Varka a filter kernel that ran. */
  private def check(where: String): Unit = {
    val query = s"SELECT i FROM varka_range_keys WHERE $where"
    val actual = varkaSpark.sql(query)
    val plan = actual.queryExecution.executedPlan
    assertFused(plan)
    checkAnswer(actual, spark.sql(query))
    assertKernelsRan(plan)
  }

  private def withKeys(body: => Unit): Unit = {
    cacheKeys(spark)
    cacheKeys(varkaSpark)
    try body finally {
      Seq(spark, varkaSpark).foreach(_.catalog.uncacheTable("varka_range_keys"))
    }
  }

  test("modified-q3's 200 ranges fuse and select what the row engine selects") {
    withKeys {
      check(VarkaQ3Ranges.predicate(200, "k"))
      check(VarkaQ3Ranges.predicate(49, "k"))
    }
  }

  test("ranges out of order, overlapping and touching are merged without changing the answer") {
    withKeys {
      check("k between 90 and 101 or k between -5 and 0 or k between 1 and 4 " +
        "or k between 95 and 99 or k between 10 and 10")
    }
  }

  test("strict bounds, equalities and either operand order are ranges too") {
    withKeys {
      check("(k > 0 and k < 5) or (9 <= k and 11 >= k) or k = 100 or 6 = k " +
        "or (k >= 2415100 and k <= 2415400)")
    }
  }

  test("ranges at the int extremes, where a strict bound cannot move past them") {
    withKeys {
      check(s"(k >= ${Int.MinValue} and k < ${Int.MinValue + 1}) " +
        s"or (k > ${Int.MaxValue - 1} and k <= ${Int.MaxValue}) or k between -1 and 1")
    }
  }

  test("a range set over a date column") {
    withKeys {
      check("d between date'2000-01-05' and date'2000-02-01' " +
        "or d between date'2010-01-01' and date'2010-12-31' or d = date'2030-03-03'")
    }
  }

  test("a disjunction over two columns is not a range set, and still answers correctly") {
    withKeys {
      check("k between 0 and 10 or i between 5 and 20")
    }
  }
}
