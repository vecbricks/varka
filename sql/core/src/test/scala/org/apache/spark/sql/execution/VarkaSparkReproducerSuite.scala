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

import java.nio.file.Files

import scala.jdk.CollectionConverters._

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrixRuns
import org.apache.spark.sql.execution.VarkaSparkFuzz._

/**
 * Replays every reproducer in `sql/varka/fuzz/spark/` (VARKA-262): a file marked
 * `status: regression` must agree between Varka off and on, and one marked
 * `status: known <row>` must still disagree, so that the list cannot outlive its bugs.
 */
class VarkaSparkReproducerSuite extends VarkaSparkDifferential {

  private lazy val files: Seq[java.nio.file.Path] = {
    val dir = getWorkspaceFilePath("sql", "varka", "fuzz", "spark")
    if (!Files.isDirectory(dir)) Seq.empty
    else Files.list(dir).iterator().asScala.filter(_.toString.endsWith(".sql")).toSeq.sorted
  }

  /**
   * Why the reproducer is not what its status says, or None when it is. A check that raises is
   * compared again under the arm the reproducer was found under, which its note names when it is
   * not the default (`VarkaErrorRerun.ofNote`), whatever `-Dvarka.sparkfuzz.errorRerun` says:
   * the per-row arm's one-row batches can show a kind the default does not.
   */
  private def verdict(r: Reproducer): Option[String] = {
    val now = withOptions(r.options) {
      disagreement(r.fixture, r.select, r.where, r.ansi, partitions = true, r.pivot,
        r.force.toSeq, rerun = VarkaErrorRerun.ofNote(r.note))
    }
    if (r.status == "regression") {
      now.map(kind => s"a regression disagrees again: $kind")
    } else if (!r.status.startsWith("known ")) {
      Some(s"status is 'regression' or 'known <row>': ${r.status}")
    } else if (!now.contains(r.kind)) {
      Some(s"a known disagreement is now ${now.getOrElse("agreement")}, not ${r.kind}: " +
        "delete the file or mark it a regression")
    } else {
      None
    }
  }

  /** Runs `body` with a reproducer's `options` applied over the options the run has. */
  private def withOptions[T](options: Option[String])(body: => T): T = options match {
    case None => body
    case Some(spec) =>
      val previous = VarkaColumnarToRowExec.currentEmitOptions
      VarkaColumnarToRowExec.setEmitOptionsForTesting(VarkaMatrixRuns.parseOver(previous, spec))
      try body finally VarkaColumnarToRowExec.setEmitOptionsForTesting(previous)
  }

  test("every saved reproducer is a regression that now agrees or a known one that still differs") {
    files.foreach { file =>
      val r = parse(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8))
      assert(verdict(r).isEmpty, s"$file: ${verdict(r).getOrElse("")}")
    }
  }

  private val agreeing = Reproducer("regression", "a query that always agrees", ansi = false,
    "values differ", "d AS c0", None,
    fixtureSql(Seq(Seq.fill(2)("DATE'2024-01-31'") ++ Seq("3") ++ Seq.fill(9)("NULL"))))

  test("a regression that agrees passes, and a known one that now agrees is stale") {
    assert(verdict(agreeing).isEmpty)
    val stale = verdict(agreeing.copy(status = "known VARKA-0"))
    assert(stale.exists(_.contains("is now agreement")), stale)
    assert(verdict(agreeing.copy(status = "wishful")).exists(_.contains("status is")))
  }
}
