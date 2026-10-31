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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The host fact behind the session's conversion form (VARKA-538): {@link VarkaHostIsa} over
 * planted feature lists, a read that fails, and the real read on this JVM.
 */
@VarkaSuite
public final class VarkaHostIsaSuite {

  /** The laptop's list (Zen 5) and the same JVM under {@code -XX:UseAVX=2}, abridged. */
  private static final String WITH_DQ =
      "cx8, cmov, sse4.2, avx, avx2, bmi2, avx512f, avx512dq, avx512cd, avx512bw, avx512vl, fma";
  private static final String AVX2_ONLY = "cx8, cmov, sse4.2, avx, avx2, bmi2, fma, f16c";

  private static VarkaHostIsa.Reading x86(String features) {
    return new VarkaHostIsa.Reading("amd64", features, null);
  }

  @Test
  @DisplayName("an x86 list with avx512dq converts, and one without it falls back")
  void dqDecides() {
    assertFalse(x86(WITH_DQ).conversionsFallBack());
    assertTrue(x86(AVX2_ONLY).conversionsFallBack());
    assertTrue(new VarkaHostIsa.Reading("x86_64", AVX2_ONLY, null).conversionsFallBack());
    // Both of the names os.arch gives x86, and only those.
    assertTrue(VarkaHostIsa.isX86("amd64") && VarkaHostIsa.isX86("x86_64"));
    assertFalse(VarkaHostIsa.isX86("aarch64") || VarkaHostIsa.isX86(""));
  }

  @Test
  @DisplayName("an AVX-512 list without avx512dq falls back, as HotSpot's matcher refuses it")
  void avx512WithoutDq() {
    // No AMD host can make the JVM report this (UseKNLSetting is Intel-only), so it is planted.
    assertTrue(x86("avx, avx2, avx512f, avx512cd, avx512bw, avx512vl").conversionsFallBack());
  }

  @Test
  @DisplayName("avx512dq is matched as a name of the list, not as a prefix of one")
  void exactName() {
    assertTrue(x86("avx2, avx512dqx").conversionsFallBack());
    assertTrue(VarkaHostIsa.hasFeature("avx2,avx512dq", "avx512dq"));
    assertFalse(VarkaHostIsa.hasFeature("avx2, avx512dqx", "avx512dq"));
  }

  @Test
  @DisplayName("an empty list, a non-x86 arch and an unread list all answer converts")
  void unknownConverts() {
    assertFalse(x86("").conversionsFallBack());
    assertFalse(new VarkaHostIsa.Reading("aarch64", "fp, asimd, sve", null)
        .conversionsFallBack());
    assertFalse(new VarkaHostIsa.Reading("amd64", AVX2_ONLY, "no export")
        .conversionsFallBack());
  }

  @Test
  @DisplayName("a read that fails is an answer with its reason, not an error")
  void failedRead() {
    VarkaHostIsa.Reading missing =
        VarkaHostIsa.read("org.apache.spark.NoSuchVectorSupport", "getCPUFeatures");
    assertFalse(missing.readable());
    assertFalse(missing.conversionsFallBack());
    assertTrue(missing.failure().contains("ClassNotFoundException"), missing.failure());
    // An accessible class without the method.
    VarkaHostIsa.Reading noMethod = VarkaHostIsa.read("java.lang.Object", "getCPUFeatures");
    assertFalse(noMethod.readable());
    // A method that returns something other than a string.
    VarkaHostIsa.Reading notAString = VarkaHostIsa.read("java.lang.System", "nanoTime");
    assertFalse(notAString.readable());
    assertFalse(notAString.conversionsFallBack());
  }

  @Test
  @DisplayName("on x86 the real read returns HotSpot's list, through the test JVM's export")
  void realRead() {
    VarkaHostIsa.Reading host = VarkaHostIsa.host();
    assumeTrue(host.x86(),
        "the feature list is read only where the x86 matcher exists; this is " + host.arch());
    assertTrue(host.readable(), () -> "the feature query failed: " + host.failure()
        + "; the test JVM needs --add-exports=java.base/jdk.internal.vm.vector=ALL-UNNAMED");
    assertTrue(host.features().contains("avx2"), host.features());
    // HotSpot clears every AVX-512 feature below level 3, so the level and the list agree.
    if (VarkaEmitOptions.HOST_USE_AVX >= 0 && VarkaEmitOptions.HOST_USE_AVX < 3) {
      assertTrue(VarkaHostIsa.conversionsFallBack(), host.features());
    }
  }
}
