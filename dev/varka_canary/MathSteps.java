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
 * The step count between two doubles, a copy of catalyst's {@code VarkaMathBound.steps} for the
 * canary, which runs as a single-file program with no Spark build and so cannot import it.
 * {@code VarkaMathBoundSuite} compiles this file and holds it to the catalyst method on that
 * suite's pairs and on random bit patterns, so the two cannot drift; change both or neither.
 *
 * <p>The rules, in order: two NaNs are 0 steps apart, a NaN against a number is {@link #REFUSED};
 * an infinity is 0 steps from itself and refused against anything else; two values of different
 * sign bits are refused, so {@code -0.0} against {@code +0.0} is too; and two finite values of one
 * sign are the difference of their raw bits, the count of doubles between them. A raw-bit
 * difference taken across the sign bit would overflow, which is why the sign rule comes first.
 */
public final class MathSteps {

  /** What {@link #steps} answers for a pair no bound admits. */
  public static final long REFUSED = Long.MAX_VALUE;

  private MathSteps() {
  }

  /** The number of representable doubles between {@code a} and {@code r}, or {@link #REFUSED}. */
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
}
