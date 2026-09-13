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
 * The zero-copy bridge from Arrow buffers to the SIMD kernels.
 *
 * <p>{@link org.apache.spark.sql.varka.memory.VarkaMorsel} maps an Arrow vector's data and
 * validity buffers onto Panama {@link java.lang.foreign.MemorySegment}s. Nothing is copied and
 * no per-row object is created: a "morsel" is just the pair of segments a kernel reads and
 * writes, plus the row count.
 *
 * <p>Two details decide most of the code that reads a morsel. The data buffer is flat int32,
 * so a lane group is a plain segment load. The validity buffer is <b>bit-packed</b>, one bit
 * per row - so it is read a {@code long} at a time and turned into a {@code VectorMask}, and a
 * byte-per-lane read of it would be a correctness bug rather than a slow path.
 */
package org.apache.spark.sql.varka.memory;
