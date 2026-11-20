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
 * The faults a test plants in the math functions' lowerings to prove that the checks over them
 * can fail (milestone 8 row 504, {@code VARKA-356.md} 3 test 4): each is a plausible wrong
 * lowering that agrees with Spark on most inputs. {@link #NONE} in production; a test plants one
 * around an emission and clears it after, and emits the kernel itself rather than through the
 * class cache, so that no faulty class outlives the test.
 */
public enum VarkaMathFault {
  /** No fault: the lowering as built. */
  NONE,
  /**
   * A per-lane call of the other class's method, {@code StrictMath.sin} for Spark's
   * {@code Math.sin} and {@code Math.asin} for {@code StrictMath.asin}: on x86 the first differs
   * from Spark's answer for {@code sin}, {@code cos} and {@code tan} on thousands of inputs in a
   * million, and the second never does, since {@code Math} delegates there.
   */
  OTHER_CLASS;

  private static volatile VarkaMathFault planted = NONE;

  /** The fault planted now; {@link #NONE} unless a test planted one. */
  public static VarkaMathFault planted() {
    return planted;
  }

  /** Plants {@code fault} until the next call; {@link #NONE} clears it. */
  public static void plantForTesting(VarkaMathFault fault) {
    planted = fault;
  }
}
