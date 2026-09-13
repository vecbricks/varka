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
 * Hand-written Vector API kernels over Arrow buffers - the <b>reference semantics</b> of the
 * Varka engine.
 *
 * <p>These are no longer the execution path: since milestone 2 a whole projection is compiled
 * into one emitted loop by
 * {@code org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaLoopEmitter}, and these
 * per-operation kernels are what that emitter mirrors and what its output is checked against.
 * They are kept, and commented, as the readable statement of what a Varka loop does.
 *
 * <p><b>So this is the package to read first</b> if you want to understand the engine rather
 * than the compiler. {@link org.apache.spark.sql.varka.vector.DateVectorOps} shows the shape of
 * every kernel here: load a lane group from a {@code MemorySegment}, compute with
 * {@code IntVector} operations, blend by a {@code VectorMask} built from the bit-packed
 * validity buffer, store once. {@link org.apache.spark.sql.varka.vector.ChronoVectorOps} holds
 * the calendar arithmetic - civil-from-days and back - which is where the interesting
 * branch-free work is, and {@link org.apache.spark.sql.varka.vector.ChronoScalarOps} is the
 * same arithmetic one value at a time, for the tail and for testing.
 *
 * <p>{@link org.apache.spark.sql.varka.vector.VarkaVectorSupport} reports whether this JVM has
 * a usable Vector API and how wide it is.
 */
package org.apache.spark.sql.varka.vector;
