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

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{BoundReference, CaseWhen, EqualTo, Literal,
  Multiply}
import org.apache.spark.sql.catalyst.expressions.codegen.GenerateUnsafeProjection
import org.apache.spark.sql.types.LongType

/**
 * The child process behind [[VarkaSplitInliningSuite]]. Launched in a forked JVM under
 * `-Xbatch` and `PrintInlining` for Spark's generated classes, it compiles one vanilla-Spark
 * projection of a `CASE WHEN` of `n` branches over a long column - `WHEN v = k THEN v * k` -
 * the way Spark evaluates a projection outside a whole-stage codegen stage, and runs it over
 * rows whose value visits every branch. Spark splits the branches into methods of
 * `spark.sql.codegen.methodSplitThreshold` characters and calls them one after another, from the
 * projection's `apply` or, when they are many, from a method that groups the calls; what HotSpot
 * prints between the two markers says which of those calls C2 inlined into their caller and why
 * it refused the others.
 *
 * `-Xbatch` finishes each compile before the call that earned it continues, so by the end marker
 * every compile of the generated class has been printed.
 */
object VarkaSplitInliningProbe {

  val BEGIN = "VARKA_SPLIT_INLINING_BEGIN"
  val DONE = "VARKA_SPLIT_INLINING_DONE"

  /** The generated projection's class, as HotSpot names it in the inlining log. */
  val PROJECTION_CLASS = "org.apache.spark.sql.catalyst.expressions.GeneratedClass$" +
    "SpecificUnsafeProjection"

  private val calls = 20000000

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "usage: VarkaSplitInliningProbe <branches>")
    val n = args(0).toInt
    val v = BoundReference(0, LongType, nullable = false)
    val caseWhen = CaseWhen(
      (1 to n).map(k => (EqualTo(v, Literal(k.toLong)), Multiply(v, Literal(k.toLong)))),
      Literal(0L))
    val projection = GenerateUnsafeProjection.generate(Seq(caseWhen))
    val rows = (0 to n).map(k => InternalRow(k.toLong)).toArray
    // scalastyle:off println
    println(BEGIN)
    var sum = 0L
    var i = 0
    while (i < calls) {
      sum += projection(rows(i % rows.length)).getLong(0)
      i += 1
    }
    println(s"$DONE sum=$sum")
    // scalastyle:on println
  }
}
