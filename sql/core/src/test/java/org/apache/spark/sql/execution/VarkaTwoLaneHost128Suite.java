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
package org.apache.spark.sql.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuite;

/**
 * The two-lane refusal on the host it is for (VARKA-503): a JVM of its own started under AVX2 at
 * 128 bits, as the EPYC 7763 benchmark runner's 128-bit companion runs, where the long species has
 * two lanes. On x86 the compiler's host fact and a session's default are both true there; a JVM
 * that is not x86 ignores {@code UseAVX} and must answer false. See {@link VarkaTwoLaneHostCheck}.
 */
@VarkaSuite(ownJvm = true,
    jvmFlags = {"-XX:+IgnoreUnrecognizedVMOptions", "-XX:UseAVX=2", "-XX:MaxVectorSize=16"})
public final class VarkaTwoLaneHost128Suite {

  @Test
  @DisplayName("under AVX2 at 128 bits an x86 host declines two-lane long conditions by default")
  void declinesAt128Bits() {
    VarkaTwoLaneHostCheck.check(0, true);
  }
}
