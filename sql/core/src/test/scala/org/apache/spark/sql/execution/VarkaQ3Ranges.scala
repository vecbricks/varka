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

import org.apache.spark.sql.catalyst.util.resourceToString

/**
 * The date ranges of TPC-DS `modified-q3`'s filter, in the query's order: 200
 * `ss_sold_date_sk between a and b` clauses joined by `or`, the partition-key filter a BI tool
 * writes for a set of date ranges. It is the one whole-stage stage of the TPC-DS and TPC-H queries
 * whose generated method is past HotSpot's 8000-byte limit (`PLAN_TASK_193.md`), and the shape
 * `PLAN_TASK_172.md` measures Varka against. Read from the query file itself, so the ranges are
 * the query's and not a copy of them.
 */
object VarkaQ3Ranges {

  lazy val ranges: Seq[(Int, Int)] = {
    val query = resourceToString("tpcds-modifiedQueries/q3.sql",
      classLoader = Thread.currentThread().getContextClassLoader)
    val found = """ss_sold_date_sk between (\d+) and (\d+)""".r.findAllMatchIn(query)
      .map(m => (m.group(1).toInt, m.group(2).toInt)).toSeq
    require(found.size == 200, s"modified-q3 has ${found.size} ranges, not 200")
    found
  }

  /** The first `n` ranges as SQL over `column`, in the query's own form. */
  def predicate(n: Int, column: String): String =
    ranges.take(n).map { case (a, b) => s"$column between $a and $b" }.mkString(" or ")

  /**
   * The first `n` ranges as SQL over `column`, each written as the two comparisons the optimizer
   * rewrites `between` into for a column.
   */
  def comparisons(n: Int, column: String): String =
    ranges.take(n).map { case (a, b) => s"($column >= $a and $column <= $b)" }.mkString(" or ")
}
