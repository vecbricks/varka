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
 * The call-site view of a two-input Varka batch kernel - {@code DateVectorOps.vectorDateDiff}
 * today. See {@link VarkaUnaryKernel} for why the generated runner implements an interface
 * instead of being reached through {@code java.lang.reflect.Method#invoke}.
 *
 * <p>All addresses are raw off-heap addresses of Arrow buffers, valid only for the duration of
 * the call. A {@code validity} address of 0 means "no nulls".
 */
public interface VarkaBinaryKernel {

  /**
   * The JVM descriptor of {@link #run}. Kernels are matched to this interface by descriptor,
   * so it must stay in step with the signature below - {@code VarkaClassFileGenSuite} pins
   * the two together.
   */
  String DESCRIPTOR = "(JJIJJIJJI)V";

  /**
   * Runs the kernel over two input vectors of the same length.
   *
   * @param leftData address of the left data buffer.
   * @param leftValidity address of the left validity bitmap, or 0 when there are no nulls.
   * @param leftNullCount number of nulls in the left rows {@code [0, length)}.
   * @param rightData address of the right data buffer.
   * @param rightValidity address of the right validity bitmap, or 0 when there are no nulls.
   * @param rightNullCount number of nulls in the right rows {@code [0, length)}.
   * @param dstData address of the destination data buffer.
   * @param dstValidity address of the destination validity bitmap.
   * @param length number of rows to process.
   */
  void run(
      long leftData,
      long leftValidity,
      int leftNullCount,
      long rightData,
      long rightValidity,
      int rightNullCount,
      long dstData,
      long dstValidity,
      int length);
}
