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
 * The guard verdicts' Scala face: [[VarkaGuardSpecs]], the Java class that decides which batches
 * a kernel must decline, may decline or must answer (VARKA-279, VARKA-311), over the Scala rows
 * and sequences its suite builds. Nothing is decided here.
 */
object VarkaGuardSpec {

  type Verdict = VarkaGuardSpecs.Verdict

  def Verdict(must: Int, may: Int): Verdict = new VarkaGuardSpecs.Verdict(must, may)

  val Answer: Verdict = VarkaGuardSpecs.Verdict.ANSWER

  type Shift = VarkaGuardSpecs.Shift

  def Shift(reason: Analysis.Refusal, lo: Long, hi: Long): Shift =
    new VarkaGuardSpecs.Shift(reason, lo, hi)

  /** A kernel's guards, read once, as [[VarkaGuardSpecs.Guards]]. */
  final class Guards(val roots: Seq[VarkaVectorIR], numInputs: Int, numLiterals: Int,
      options: VarkaEmitOptions, val long: Boolean) {
    private[varka] val toJava =
      new VarkaGuardSpecs.Guards(roots.asJava, numInputs, numLiterals, options, long)
  }

  /** The verdict on a batch of `rows` (each a value or None per column) for the kernel `g`. */
  def verdict(g: Guards, lits: Array[Long], rows: Seq[Seq[Option[Long]]],
      shifts: Seq[Shift]): Verdict = verdict(g, lits, Array.empty[Int], rows, shifts)

  /** [[verdict]] for a long-lane kernel that also reads its int literal table (VARKA-28). */
  def verdict(g: Guards, lits: Array[Long], intLits: Array[Int], rows: Seq[Seq[Option[Long]]],
      shifts: Seq[Shift]): Verdict = {
    val (values, isNull) = columns(rows)
    VarkaGuardSpecs.verdict(g.toJava, lits, intLits, values, isNull, shifts.asJava)
  }

  /** [[verdict]] for a kernel whose guards are read for this one call. */
  def verdict(roots: Seq[VarkaVectorIR], numInputs: Int, lits: Array[Long],
      options: VarkaEmitOptions, long: Boolean, rows: Seq[Seq[Option[Long]]],
      shifts: Seq[Shift] = Nil): Verdict =
    verdict(new Guards(roots, numInputs, lits.length, options, long), lits, rows, shifts)

  private def columns(rows: Seq[Seq[Option[Long]]]): (Array[Array[Long]], Array[Array[Boolean]]) =
    (rows.map(_.map(_.getOrElse(0L)).toArray).toArray, rows.map(_.map(_.isEmpty).toArray).toArray)
}
