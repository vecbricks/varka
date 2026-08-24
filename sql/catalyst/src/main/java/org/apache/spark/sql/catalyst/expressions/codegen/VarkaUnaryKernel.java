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

package org.apache.spark.sql.catalyst.expressions.codegen;

/**
 * The call-site view of a one-input Varka batch kernel that takes a scalar {@code int}
 * argument - {@code DateVectorOps.vectorAddDays} and {@code vectorSubDays} today.
 *
 * <p>The generated runner class assembled by {@code VarkaClassFileGen.assembleKernelClass}
 * implements this interface, and its {@code run} body is a single {@code invokestatic} to the
 * kernel with the arguments already on the stack as primitives. Calling through the interface
 * rather than {@code java.lang.reflect.Method#invoke} is the point: reflection would box every
 * argument and allocate an {@code Object[]} per batch, which is exactly what the generated
 * dispatcher exists to avoid.
 *
 * <p>All addresses are raw off-heap addresses of Arrow buffers, valid only for the duration of
 * the call. A {@code validity} address of 0 means "no nulls"; see the kernel javadoc in the
 * engine module for the full null contract.
 */
public interface VarkaUnaryKernel {

  /**
   * The JVM descriptor of {@link #run}. Kernels are matched to this interface by descriptor,
   * so it must stay in step with the signature below - {@code VarkaClassFileGenSuite} pins
   * the two together.
   */
  String DESCRIPTOR = "(JJIJJII)V";

  /**
   * Runs the kernel over one input vector.
   *
   * @param srcData address of the source data buffer.
   * @param srcValidity address of the source validity bitmap, or 0 when there are no nulls.
   * @param srcNullCount number of nulls in the source rows {@code [0, length)}.
   * @param dstData address of the destination data buffer.
   * @param dstValidity address of the destination validity bitmap.
   * @param length number of rows to process.
   * @param arg the kernel's scalar argument, e.g. the folded day offset of {@code date_add}.
   */
  void run(
      long srcData,
      long srcValidity,
      int srcNullCount,
      long dstData,
      long dstValidity,
      int length,
      int arg);
}
