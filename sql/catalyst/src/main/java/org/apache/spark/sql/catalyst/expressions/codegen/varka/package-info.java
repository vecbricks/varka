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

/**
 * Varka's vector IR and the runtime bytecode emitter that turns it into a fused SIMD loop.
 *
 * <p><b>Where to start.</b> This package has two dozen classes and three of them are the
 * spine; the rest support those three. In the order a query meets them:
 *
 * <ol>
 *   <li>{@link org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR} - the IR
 *       itself, a sealed interface whose records are values over int32 lanes. Much smaller
 *       than Catalyst's expression tree, and carrying no literal <i>values</i>: a folded
 *       literal becomes a slot index, so one emitted class serves every literal and a plan's
 *       identity is its shape.</li>
 *   <li>{@link org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFusedKernel} - the
 *       interface an emitted class implements, and so the contract the emitter must satisfy.
 *       Read it before the emitter: it is short, and it says what is being built.</li>
 *   <li>{@link org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaLoopEmitter} -
 *       walks the IR post-order and assembles that class with the JDK 25 Class-File API,
 *       leaving intermediates on the operand stack so they stay in vector registers. The
 *       largest file here.</li>
 * </ol>
 *
 * <p>What feeds this package is
 * {@code org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler} (Scala,
 * one package up), which translates Catalyst expressions into the IR and is where an
 * unsupported expression is declined. What consumes it are the {@code Varka*Exec} nodes in
 * {@code sql/core}.
 *
 * <p><b>The rest, by role</b>, so a reader can skip it on a first pass:
 *
 * <ul>
 *   <li><i>Shape cache</i> - {@code VarkaShapeCacheImpl}, {@code VarkaShapeKey},
 *       {@code VarkaShapeEntry}, {@code VarkaShapeLookup}: one emitted class per plan shape,
 *       so emission is paid once rather than per task.</li>
 *   <li><i>Emitter support</i> - {@code VarkaEmitOptions} (the variants the emitter can be
 *       asked for), {@code VarkaChrono} (calendar constants and range bounds),
 *       {@code IntRangeOps}, {@code SelectionVectorOps}, {@code VarkaSelectionBitmap},
 *       {@code TruncLevelLeaf}, {@code WeekdayLeaf}, {@code VarkaDerivedKind}.</li>
 *   <li><i>Telemetry</i> - {@code VarkaDebugInfo} and its reader, which stamp an emitted class
 *       with its IR and plan fragment so a profiler or heap dump names the plan node; the
 *       {@code Varka*Event} classes and {@code VarkaCompilationWatch},
 *       {@code VarkaAllocationSampler}.</li>
 * </ul>
 *
 * <p><b>A property worth reading for.</b> Every stage here may decline - the emitter refuses a
 * shape it will not build, and the kernel refuses a batch whose values leave the range it
 * proved. A decline falls back to stock Spark for that batch and the query still returns the
 * right answer, so declining is a normal outcome in this code rather than an error path.
 */
package org.apache.spark.sql.catalyst.expressions.codegen.varka;
