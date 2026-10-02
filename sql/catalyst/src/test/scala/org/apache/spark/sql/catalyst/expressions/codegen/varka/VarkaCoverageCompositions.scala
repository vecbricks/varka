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
import scala.util.Random

import org.apache.spark.sql.catalyst.expressions.Alias
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler

/**
 * Projections of twenty to two hundred entries drawn from the coverage table's projection rows,
 * through the compiler: entries of every family the compiler admits, over the table's columns,
 * with the sharing a real projection has rather than a ladder's or a random tree's. The compiler
 * fuses one lane and declines the rest; the kernel it makes is the shape. The grouping bound
 * (`VarkaGroupingBoundSuite`) and the cost audit's count of builds (`VarkaEmitCostAuditSuite`)
 * read the same sixty.
 */
object VarkaCoverageCompositions {

  /** The sixty shapes, the fused entries of each compiled under `options`. */
  def draw(table: VarkaCoverageRows.Table, options: VarkaEmitOptions)
      : Seq[VarkaEmitCostCorpus.Shape] = {
    val rnd = new Random(20261001L)
    (0 until 60).flatMap { i =>
      val picked = Seq.fill(20 + rnd.nextInt(181))(
        table.projections(rnd.nextInt(table.projections.size)))
      val list = picked.zipWithIndex.map { case (row, k) =>
        Alias(VarkaCoverageRows.resolve(row.executable, table.columns), s"c$k")()
      }
      VarkaExpressionCompiler.compilePartial(list, table.columns, options).map { partial =>
        val fused = partial.fused
        new VarkaEmitCostCorpus.Shape("coverage compositions", i, fused.outputs.asJava,
          fused.inputOrdinals.size, math.max(fused.literals.size, fused.longLiterals.size))
      }
    }
  }
}
