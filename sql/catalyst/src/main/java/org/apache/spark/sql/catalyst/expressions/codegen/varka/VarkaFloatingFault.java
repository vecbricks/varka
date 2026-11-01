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
 * The faults a test plants in the floating arithmetic to prove that the checks over it can fail
 * (milestone 8 row 504): each is a plausible wrong lowering that agrees with Spark on most
 * inputs. {@link #NONE} in production; a test plants one around a compile or an emission and
 * clears it after, and emits the kernel itself rather than through the class cache, so that no
 * faulty class outlives the test.
 */
public enum VarkaFloatingFault {
  /** No fault: the lowering as built. */
  NONE,
  /**
   * A checked division that tests its divisor for {@code 0.0} alone, as a test of the bits
   * {@code 0} would: {@code -0.0} divides to an infinity where Spark nulls or raises.
   */
  DIVISOR_POSITIVE_ZERO_ONLY,
  /**
   * Negation at a floating lane built as {@code 0.0 - x}, which answers {@code +0.0} for
   * {@code 0.0} where Spark's {@code -x} answers {@code -0.0}.
   */
  NEGATION_AS_SUBTRACTION,
  /**
   * {@code degrees} multiplying by the constant rounded to nine digits rather than by
   * {@code Math.toDegrees(1.0)}'s bits, which differs from Spark's answer in the last bits.
   */
  DEGREES_ROUNDED;

  private static volatile VarkaFloatingFault planted = NONE;

  /** The fault planted now; {@link #NONE} unless a test planted one. */
  public static VarkaFloatingFault planted() {
    return planted;
  }

  /** Plants {@code fault} until the next call; {@link #NONE} clears it. */
  public static void plantForTesting(VarkaFloatingFault fault) {
    planted = fault;
  }
}
