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
package org.apache.spark.sql.catalyst.expressions.codegen.varka;

/**
 * The factor the main lane-group loop's step is multiplied by under
 * {@link VarkaEmitOptions#uncountedMainLoop()}: one, read from a field C2 does not fold.
 *
 * <p>C2 compiles a loop as counted only when its induction variable steps by a constant. A
 * counted loop whose body holds {@code long} range checks - every {@code MemorySegment} access
 * of an Arrow buffer does - is nested by C2 into an outer loop and an inner {@code int} one so
 * that the checks can be eliminated, and the inner loop's predicates are then built under the
 * back edge's state, where a failed profiled predicate is limited only by the method's trap
 * count. A released kernel of the long lane after a kernel history traps there dozens of times
 * inside the warm-up's deadline ({@code VARKA-383.md} 9.2 to 9.5). Multiplying the lane count by
 * this field makes the step a load rather than a constant, so the loop is not counted and none
 * of that is built; the range checks stay in the loop.
 */
public final class VarkaLoopStride {

  private VarkaLoopStride() {}

  /** One, and never written. */
  // Not final on purpose: a final field is a constant to C2, which would count the loop again.
  // Public, since the emitted kernels live in another runtime package.
  @SuppressWarnings("NonFinalStaticField")
  public static int one = 1;
}
