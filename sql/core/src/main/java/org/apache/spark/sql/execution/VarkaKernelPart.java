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
package org.apache.spark.sql.execution;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import scala.Option;
import scala.collection.immutable.Seq;

import org.apache.arrow.memory.BufferAllocator;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.execution.varka.VarkaBatchDeclined;
import org.apache.spark.sql.execution.varka.VarkaKernelFailure;
import org.apache.spark.sql.vectorized.ColumnVector;
import org.apache.spark.sql.vectorized.ColumnarBatch;

/**
 * One further kernel of a projection several kernels serve
 * ({@code VarkaEmitOptions.severalKernels}, {@code VARKA-190.md} 11): the evaluator machinery of
 * {@link VarkaEvaluatorBase} - the shape-cached runner, its warm-up, its scratch and its argument
 * arrays - for that kernel alone. It serves no batch itself: the projection's evaluator asks it
 * whether it can run and whether it is
 * ready, and runs it into the one output batch ({@code runKernel}), so every fallback is still
 * decided, counted and taken once per batch, by the projection's evaluator.
 *
 * <p>It allocates from the projection's allocator and registers no task listener of its own: the
 * projection's evaluator releases its scratch at task end, before closing that allocator, so a task
 * has one allocator however many kernels its projection has.
 */
final class VarkaKernelPart extends VarkaEvaluatorBase {

  private final CompiledVarkaProjection plan;
  private final Seq<NamedExpression> entries;
  private final Supplier<BufferAllocator> allocator;

  VarkaKernelPart(
      CompiledVarkaProjection plan,
      Seq<NamedExpression> entries,
      Seq<Attribute> childOutput,
      String operatorName,
      Option<String> classDumpDirectory,
      VarkaExecMetrics metrics,
      VarkaSessionEmit sessionEmit,
      boolean warmupEnabled,
      Supplier<BufferAllocator> allocator) {
    // It reads no input rows; the projection's evaluator does, through its own view.
    super(childOutput, operatorName, classDumpDirectory, metrics, sessionEmit, warmupEnabled,
        false);
    this.plan = plan;
    this.entries = entries;
    this.allocator = allocator;
  }

  @Override
  protected Optional<CompiledVarkaProjection> fusedPlan() {
    return Optional.ofNullable(plan);
  }

  /** The entries this kernel computes, so its side-table identity names them. */
  @Override
  protected java.util.Iterator<String> identityEntries() {
    return render(entries);
  }

  @Override
  protected BufferAllocator taskAllocator() {
    return allocator.get();
  }

  // A decline or failure this kernel raises names it, so the projection's fallback log line and
  // event name the kernel that declined rather than the first one, whichever entry point raised it.

  @Override
  boolean kernelReady(ColumnarBatch input) {
    try {
      return super.kernelReady(input);
    } catch (VarkaBatchDeclined | VarkaKernelFailure e) {
      throw blamed(e);
    }
  }

  @Override
  ColumnVector[] runKernel(
      ColumnarBatch input,
      int len,
      List<ColumnVector> owned,
      BufferAllocator allocator,
      VectorAllocator allocate) {
    try {
      return super.runKernel(input, len, owned, allocator, allocate);
    } catch (VarkaBatchDeclined | VarkaKernelFailure e) {
      throw blamed(e);
    }
  }

  /** {@code e} naming this kernel, if it names none; anything else as it is. */
  private RuntimeException blamed(RuntimeException e) {
    if (e instanceof VarkaBatchDeclined d && d.kernel == null) {
      return new VarkaBatchDeclined(d.status, kernelIdentity());
    }
    if (e instanceof VarkaKernelFailure f && f.kernel == null) {
      return new VarkaKernelFailure(f.getCause(), kernelIdentity(), f.shapeHash,
          plan == null ? List.of() : plan.families());
    }
    return e;
  }
}
