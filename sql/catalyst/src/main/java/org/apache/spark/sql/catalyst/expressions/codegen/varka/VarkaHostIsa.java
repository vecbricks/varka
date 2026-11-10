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

import java.util.List;

import javax.annotation.Nullable;

/**
 * Whether the JVM running this code lowers the 64-bit floating conversions ({@code L2D},
 * {@code L2F}, {@code D2L}, {@code F2L}) to vector instructions, read from HotSpot's own feature
 * list (VARKA-538).
 *
 * <p>HotSpot's x86 matcher refuses those four casts where the CPU lacks AVX512DQ, a feature, and
 * every other conversion only below a level every AVX2 host passes ({@code x86.ad}, the
 * {@code VectorCast*} cases of {@code match_rule_supported_vector}). A refused cast leaves the
 * Vector API's Java implementation in the loop, which is correct and allocates. The feature list
 * is the one HotSpot keys on: {@code VectorSupport.getCPUFeatures()} returns the string the JVM
 * built after {@code -XX:UseAVX} below 3 cleared the AVX-512 bits, so a JVM started at a lower
 * level than its CPU's reads as the lower level does. The method is public in {@code java.base}'s
 * unexported {@code jdk.internal.vm.vector}, so it is called reflectively and needs
 * {@code --add-exports=java.base/jdk.internal.vm.vector=ALL-UNNAMED} at run time, which Spark's
 * module options carry; without it the read fails and the answer is "converts", the emitter's
 * answer for an unknown host.
 *
 * <p>A reading also holds the bit size of {@code LongVector.SPECIES_PREFERRED}, the species the
 * long lane's kernels run on, which honours {@code -XX:MaxVectorSize}: whether the magic form
 * beats the conversions' fallback depends on it as well as on the feature (VARKA-538 9.3).
 *
 * <p>Nothing here enters the emitter or {@link VarkaEmitOptions#DEFAULTS}: a default that varied
 * by host would make the shape hashes and the committed bytes describe one machine. The session
 * resolves its emit level from it on the driver, where a session asks for that
 * ({@code VarkaSessionEmit.of}), and the plan carries the level to the executors.
 */
public final class VarkaHostIsa {

  /** The feature HotSpot keys the four 64-bit floating conversions on. */
  static final String DQ_FEATURE = "avx512dq";

  /** The class and method that return HotSpot's feature list. */
  static final String QUERY_CLASS = "jdk.internal.vm.vector.VectorSupport";
  static final String QUERY_METHOD = "getCPUFeatures";

  /** The architectures whose HotSpot has the x86 matcher, as {@code os.arch} names them. */
  private static final List<String> X86 = List.of("amd64", "x86_64");

  /** The {@code os.arch} of a 64-bit Arm HotSpot, on Linux and on macOS alike. */
  static final String AARCH64 = "aarch64";

  private VarkaHostIsa() { }

  /**
   * What one JVM said about itself.
   *
   * @param arch {@code os.arch}
   * @param features HotSpot's feature list, names separated by {@code ", "}; empty where it was
   *        not read
   * @param failure why the list could not be read, or null where it was
   * @param longBits the bit size of {@code LongVector.SPECIES_PREFERRED} in this JVM, the width
   *        the long lane's kernels run at under {@code -XX:MaxVectorSize}; 0 where it could not
   *        be read
   */
  public record Reading(String arch, String features, @Nullable String failure, int longBits) {

    public Reading {
      if (arch == null || features == null) {
        throw VarkaErrors.illegalArgument("a host reading needs an arch and a feature string");
      }
      if (longBits < 0) {
        throw VarkaErrors.illegalArgument("a species width is 0 or more bits, got " + longBits);
      }
    }

    /** Whether the feature list was read. */
    public boolean readable() {
      return failure == null;
    }

    /** Whether this JVM runs on x86, the one architecture whose matcher refuses the converts. */
    public boolean x86() {
      return isX86(arch);
    }

    /**
     * Whether this JVM's 64-bit floating conversions fall back to Java: an x86 HotSpot whose
     * feature list was read and lacks AVX512DQ. Anything not known answers false, as an unknown
     * level does in {@link VarkaEmitOptions#convertsFallBack()}: aarch64 has a rule for every
     * conversion, and a list that could not be read is no evidence against them.
     */
    public boolean conversionsFallBack() {
      return x86() && readable() && !features.isEmpty()
          && !hasFeature(features, DQ_FEATURE);
    }

    /**
     * Whether this JVM is known to lower {@code L2D} and {@code L2F} to vector instructions: an
     * x86 HotSpot whose feature list was read and holds AVX512DQ, or an aarch64 HotSpot whose
     * list was read. The aarch64 matcher has a rule for both with no feature predicate, NEON
     * being part of every
     * ARMv8 core: {@code L2D} is one vector {@code scvtf}, and {@code L2F} converts each lane
     * with the scalar {@code scvtf} from X to S on NEON, or with SVE's {@code scvtf} from D to
     * S, a single rounding either way, as Java's {@code (float) l} rounds
     * ({@code vcvtLtoF_neon}, {@code vcvtLtoF_sve} and {@code vcvtLtoD} in
     * {@code cpu/aarch64/aarch64_vector.ad}). So the answer there reads no feature, but the read
     * must have succeeded: a failed one is all that tells a JVM that is not HotSpot, or has no
     * C2 vector intrinsics, from one that is, and there the casts would run as the Vector API's
     * Java loop. The Neoverse N2 runner's census reads both as intrinsic at
     * its 128 bits ({@code sql/varka/plans/m8/results/VARKA-376-census-neoverse-n2.json}).
     */
    public boolean conversionsAreInstructions() {
      return readable() && (AARCH64.equals(arch) || (x86() && hasFeature(features, DQ_FEATURE)));
    }
  }

  /** Whether {@code arch}, an {@code os.arch} value, names x86, as HotSpot builds it for. */
  public static boolean isX86(String arch) {
    return X86.contains(arch);
  }

  /** Whether {@code feature} is one of the names in HotSpot's list, not a prefix of one. */
  static boolean hasFeature(String features, String feature) {
    for (String name : features.split(",", -1)) {
      if (name.strip().equals(feature)) {
        return true;
      }
    }
    return false;
  }

  /** Read once, on first use. */
  private static final class Holder {
    static final Reading HOST = read(QUERY_CLASS, QUERY_METHOD);
  }

  /** What this JVM says about itself, read on the first call. */
  public static Reading host() {
    return Holder.HOST;
  }

  /** Whether this JVM's 64-bit floating conversions fall back to Java; see {@link Reading}. */
  public static boolean conversionsFallBack() {
    return host().conversionsFallBack();
  }

  /**
   * The bit size of this JVM's preferred long species, as the emitter reads it
   * ({@code Lane.LONG}'s {@code SPECIES_PREFERRED} lane count), or 0 where the Vector API could
   * not be loaded.
   */
  static int preferredLongBits() {
    try {
      return Lane.LONG.preferredLanes * Long.SIZE;
    } catch (RuntimeException | LinkageError e) {
      return 0;
    }
  }

  /** This JVM's feature list, or empty where it could not be read. */
  public static String features() {
    return host().features();
  }

  /**
   * Calls the static, no-argument {@code className.method} for the feature list, beside this
   * JVM's preferred long species width ({@link #preferredLongBits}). Every failure is an answer,
   * not an error: a missing export (the method is inaccessible), a JVM that is not HotSpot (no
   * such class or method), a native that is not registered.
   */
  static Reading read(String className, String method) {
    String arch = System.getProperty("os.arch", "");
    int longBits = preferredLongBits();
    try {
      Object features = Class.forName(className).getMethod(method).invoke(null);
      if (features instanceof String s) {
        return new Reading(arch, s, null, longBits);
      }
      return new Reading(arch, "", className + "." + method + " returned " + features, longBits);
    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      return new Reading(arch, "", e.toString(), longBits);
    }
  }
}
