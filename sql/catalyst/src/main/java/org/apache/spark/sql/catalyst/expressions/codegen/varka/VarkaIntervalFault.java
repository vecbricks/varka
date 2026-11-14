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
 * The faults a test plants in the day-time interval lowerings to prove that the checks over them
 * can fail (milestone 8 row 504, `VARKA-103.md` 3.7), as {@link VarkaFloatingFault} does for the
 * floating ones. {@link #NONE} in production; a test plants one around a compile and clears it
 * after, and emits the kernel itself rather than through the class cache.
 */
public enum VarkaIntervalFault {
  /** No fault: the lowering as built. */
  NONE,
  /**
   * {@code extract(DAY FROM dt)} as the floor of the magnitude with the sign restored, the
   * magnitude {@code (x ^ (x >> 63)) - (x >> 63)} by an arithmetic shift: it wraps at
   * {@code Long.MIN_VALUE}, where the day count comes out positive.
   */
  DAY_BY_MAGNITUDE;

  private static volatile VarkaIntervalFault planted = NONE;

  /** The fault planted now; {@link #NONE} unless a test planted one. */
  public static VarkaIntervalFault planted() {
    return planted;
  }

  /** Plants {@code fault} until the next call; {@link #NONE} clears it. */
  public static void plantForTesting(VarkaIntervalFault fault) {
    planted = fault;
  }
}
