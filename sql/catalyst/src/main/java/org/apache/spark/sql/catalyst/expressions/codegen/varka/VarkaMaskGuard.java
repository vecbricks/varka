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
 * The guard an emitted kernel ANDs into each word it hands {@code VectorMask.fromLong}
 * (VARKA-491). JDK 25's C2 miscompiles {@code fromLong(s, m.toLong())} on x86 without AVX-512
 * mask registers: it replaces the pair by a cast of the mask that keeps its 0/-1 lane encoding
 * where canonical booleans are expected, which a select then reads as false on every true lane
 * (VARKA-396). A masked body's known-true word becomes that pair once its columns have no nulls,
 * which C2 learns from the profile.
 *
 * <p>The kernel computes {@code word & (ones | OPEN)}. {@link #OPEN} is final, so C2 reads it as a
 * constant. Where the miscompile can happen it is zero, and the AND with {@link #ones} stays in
 * the compiled code: HotSpot never folds a static field that is not final, so the input of
 * {@code fromLong} is never the pair C2's rule matches. That costs one AND on the path from the
 * comparison to the blend; C2 still knows the word's range through it, so it needs no
 * zero-extension. Everywhere else {@code OPEN} is all ones, C2 folds the whole expression to
 * {@code word}, and the kernel compiles as if the guard were not there. The bytes are the same on
 * every host, so the emitted classes and their shape hash do not depend on it.
 *
 * <p>The JDKs the guard is for: OpenJDK tracks the miscompile as JDK-8378968 ("C2 VectorAVX AVX2:
 * wrong result, bad optimization in VectorLongToMaskNode::Ideal"), fixed in JDK 27; on
 * 27 October 2026 no JDK 25 update carries the fix, so every JDK 25 needs the guard
 * ({@code m10/SCOPE.md} item 114 follows it).
 *
 * <p>Nothing may write {@code ones}: a kernel that read anything but all ones would compute wrong
 * masks.
 */
public final class VarkaMaskGuard {

  private VarkaMaskGuard() {}

  /** All ones, and never written. */
  // Not final on purpose: a final field is a constant to C2, and the AND would fold away. Public,
  // since the emitted kernels live in another runtime package.
  @SuppressWarnings("NonFinalStaticField")
  public static long ones = -1L;

  /** Zero where this JVM's C2 can miscompile the round trip, all ones where it cannot. */
  public static final long OPEN = openFor(VarkaEmitOptions.HOST_USE_AVX);

  /**
   * The guard's constant for a {@code UseAVX} level. C2 types masks as vectors rather than mask
   * registers, which the rule needs, on x86 below AVX-512; and on an AVX-512 host without AVX512VL
   * or AVX512BW (Knights Landing) for species narrower than 512 bits or with byte and short lanes,
   * which a level does not show - such a host keeps the guard open. An unknown level is a JVM
   * without the flag: on aarch64 and RISC-V, C2 has {@code fromLong} only with predicate or mask
   * registers (SVE2 bitperm, RVV), so the rule cannot apply there.
   */
  static long openFor(int useAVX) {
    return useAVX != VarkaEmitOptions.USE_AVX_UNKNOWN && useAVX < 3 ? 0L : -1L;
  }
}
