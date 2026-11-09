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
 * The step count between two doubles, the measure of the math family's opt-in bound
 * ({@code VARKA-345.md} 2.5): how many representable doubles a kernel's lane {@code a} lies from
 * the row engine's answer {@code r}. Every oracle that compares a value computed under
 * {@code spark.sql.codegen.varka.math.ulpContract.enabled} calls {@link #within}, and the probe
 * that measures the library against the row engine (row 367) measures with {@link #steps}.
 *
 * <p>The rules, in order: two NaNs are 0 steps apart, whatever their payloads, and a NaN against
 * a number is never within any bound; an infinity is 0 steps from the same infinity and never
 * within a bound of anything else, so {@code +inf} does not pass against
 * {@code Double.MAX_VALUE}, the double next to it; two finite values of different sign bits are
 * never within a bound, so {@code -0.0} does not pass against {@code +0.0}; and two finite values
 * of one sign are the difference of their raw bits, which counts the doubles between them because
 * the bit patterns of one sign are ordered as the values are. That difference is under
 * {@code 2^63} and cannot overflow, which a difference across the sign bit can: the
 * {@code Math.abs} of it is {@code Long.MIN_VALUE} for {@code -0.0} against {@code +0.0}
 * ({@code VARKA-345.md} item 0.10).
 */
public final class VarkaMathBound {

  /** What {@link #steps} answers for a pair no bound admits. */
  public static final long REFUSED = Long.MAX_VALUE;

  private VarkaMathBound() {
  }

  /**
   * The number of representable doubles between {@code a} and {@code r}, or {@link #REFUSED} for
   * a pair no bound admits: a NaN against a number, an infinity against anything but itself, and
   * two values of different sign bits.
   */
  public static long steps(double a, double r) {
    boolean nanA = Double.isNaN(a);
    boolean nanR = Double.isNaN(r);
    if (nanA || nanR) {
      return nanA && nanR ? 0 : REFUSED;
    }
    if (Double.isInfinite(a) || Double.isInfinite(r)) {
      return a == r ? 0 : REFUSED;
    }
    long bitsA = Double.doubleToRawLongBits(a);
    long bitsR = Double.doubleToRawLongBits(r);
    if ((bitsA ^ bitsR) < 0) {
      return REFUSED;
    }
    return Math.abs(bitsA - bitsR);
  }

  /** Whether {@code a} lies within {@code k} steps of {@code r}; {@code k} is 0 or more. */
  public static boolean within(double a, double r, int k) {
    if (k < 0) {
      throw VarkaErrors.illegalArgument("a bound is a step count of 0 or more, not " + k);
    }
    return steps(a, r) <= k;
  }
}
