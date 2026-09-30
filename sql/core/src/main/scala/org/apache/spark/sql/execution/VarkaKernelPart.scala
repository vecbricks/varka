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

import org.apache.arrow.memory.BufferAllocator

import org.apache.spark.sql.catalyst.expressions.{Attribute, NamedExpression}
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection

/**
 * One further kernel of a projection several kernels serve (`VarkaEmitOptions.severalKernels`,
 * `PLAN_TASK_190.md` 11): the evaluator machinery of [[VarkaEvaluatorBase]] - the shape-cached
 * runner, its warm-up, its scratch and its argument arrays - for that kernel alone. It serves no
 * batch itself: the projection's [[VarkaKernelEvaluator]] asks it whether it can run and whether
 * it is ready, and runs it into the one output batch (`runKernel`), so every fallback is still
 * decided, counted and taken once per batch, by the projection's evaluator.
 *
 * It allocates from the projection's allocator (`allocator`) and registers no task listener of its
 * own: the projection's evaluator releases its scratch at task end, before closing that
 * allocator, so a task has one allocator however many kernels its projection has.
 *
 * Scala rather than Java, which the project prefers for new code: it extends
 * [[VarkaEvaluatorBase]], a Scala class whose members it inherits and overrides, and belongs
 * with it until that class is ported.
 */
private[execution] final class VarkaKernelPart(
    plan: CompiledVarkaProjection,
    entries: Seq[NamedExpression],
    childOutput: Seq[Attribute],
    operatorName: String,
    classDumpDirectory: Option[String],
    metrics: VarkaExecMetrics,
    emitUseAVX: Int,
    warmupEnabled: Boolean,
    allocator: () => BufferAllocator)
    extends VarkaEvaluatorBase(childOutput, operatorName, classDumpDirectory, metrics, emitUseAVX,
      warmupEnabled) {

  override protected def fusedPlan: Option[CompiledVarkaProjection] = Some(plan)

  /** The entries this kernel computes, so its side-table identity names them. */
  override protected def identityEntries: Iterator[String] = entries.iterator.map(_.toString)

  override protected def taskAllocator(): BufferAllocator = allocator()
}
