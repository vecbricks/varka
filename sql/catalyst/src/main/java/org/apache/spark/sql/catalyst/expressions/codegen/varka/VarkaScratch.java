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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * The scratch a kernel with a materialized calendar prefix takes when its caller passes none:
 * the seven-argument {@code run} of such a kernel asks here for a buffer of its rows and calls
 * its own eight-argument form with it (task 198).
 *
 * <p>One buffer per thread, from the global arena, regrown to at least twice its size when a
 * longer call comes, so the replaced buffers' sum stays under the largest; never freed, which
 * is what makes it a fallback and not the contract. The callers that run kernels for a living
 * - the evaluator, from the task's Arrow allocator, and the warm-up, from its own arena - pass
 * their own scratch and never come here; the suites, the probes and the tools that drive a
 * kernel as a function of its arguments do, and are right to, since a buffer per test would be
 * the same allocation written forty times.
 */
public final class VarkaScratch {

  private static final ThreadLocal<MemorySegment> BUFFER = new ThreadLocal<>();

  /** The address of this thread's buffer, of at least {@code bytesPerRow * rows} bytes. */
  public static long forRows(int bytesPerRow, int rows) {
    long needed = (long) bytesPerRow * Math.max(rows, 1);
    MemorySegment buffer = BUFFER.get();
    if (buffer == null || buffer.byteSize() < needed) {
      long size = Math.max(needed, buffer == null ? 1L << 16 : 2 * buffer.byteSize());
      buffer = Arena.global().allocate(size, 64);
      BUFFER.set(buffer);
    }
    return buffer.address();
  }

  private VarkaScratch() {}
}
