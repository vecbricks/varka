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

import scala.jdk.CollectionConverters._

/**
 * The cost corpus's emitted classes held to locals that are read (`VarkaUnreadLocals`). A shared
 * slot nothing reads means the body's use count (`Slots.bodyUses`) and the walk that emits the
 * body disagree about how often a node is visited: a count too high costs a `dup` and a store, as
 * it did for dates two calendar nodes reach through one shared prefix before the count learned
 * the walk's rule (`PLAN_TASK_223.md` 9.4).
 */
class VarkaUnreadLocalsSuite extends VarkaEmitterTestBase {

  test("every shared slot of every loop and epilogue method in the cost corpus is read") {
    // The corpus of PLAN_TASK_223.md 9.1, at the shipped options: random shapes of both lanes,
    // wide shapes whose groups load materialized prefixes, and the ladders; both bodies of each.
    val options = VarkaEmitOptions.DEFAULTS.withLanesOverride(VarkaEmitCostCorpus.LANES)
    val shapes = VarkaEmitCostCorpus.fuzz(1000).asScala.toSeq ++
      VarkaEmitCostCorpus.wide().asScala ++ VarkaEmitCostCorpus.ladders().asScala
    val unread = shapes.flatMap { s =>
      val bytes = VarkaLoopEmitter.emit("org.apache.spark.sql.varka.execution.VarkaUnreadLocals",
        s.roots, s.numInputs, s.numLiterals, null, null, options)
      VarkaUnreadLocals.unreadSharedSlots(bytes).asScala.map(f => s"${s.family} ${s.index}: $f")
    }
    assert(unread.isEmpty, s"${unread.size} shared slots nothing reads, the first: " +
      unread.take(5).mkString("; "))
  }

  /** The census of every unread reference local over the corpus under `options`, by kind. */
  private def census(options: VarkaEmitOptions): Map[String, Int] = {
    val shapes = VarkaEmitCostCorpus.fuzz(1000).asScala.toSeq ++
      VarkaEmitCostCorpus.wide().asScala ++ VarkaEmitCostCorpus.ladders().asScala
    shapes.flatMap { s =>
      val bytes = VarkaLoopEmitter.emit("org.apache.spark.sql.varka.execution.VarkaUnreadLocals",
        s.roots, s.numInputs, s.numLiterals, null, null, options)
      VarkaUnreadLocalsTrim.census(bytes).asScala.map(_.kind)
    }.groupBy(identity).view.mapValues(_.size).toMap
  }

  test("under elideUnreadLocals no loop or epilogue method in the cost corpus stores a " +
      "reference local it never reads") {
    val options = VarkaEmitOptions.DEFAULTS.withLanesOverride(VarkaEmitCostCorpus.LANES)
    val on = census(options.withElideUnreadLocals(true))
    assert(on.isEmpty, s"unread reference locals under the switch: $on")
    // The form that builds everything keeps the four kinds task 239 found (PLAN_TASK_239.md 2.1)
    // and no other, so a leftover of a new kind added to the reference form is seen too.
    val off = census(options)
    assert(off.keySet.subsetOf(Set("segment: output validity", "vector: prefix reload",
      "segment: input data", "mask: indexInRange")) && off.nonEmpty,
      s"the reference form's unread reference locals: $off")
  }
}
