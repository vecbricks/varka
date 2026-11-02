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
 * The converse of {@link VarkaTwoLaneHost128Suite}: a JVM of its own under AVX2 at its native
 * 256 bits, where the long species has four lanes and C2 lowers its masks, so neither the
 * compiler's host fact nor a session's default declines anything. The width is given, not left
 * to the default, because the gate's narrow step puts {@code -XX:MaxVectorSize=16} on every test
 * JVM and a suite's own flags come after it. See {@link VarkaTwoLaneHostCheck}.
 */
@VarkaSuite(ownJvm = true,
    jvmFlags = {"-XX:+IgnoreUnrecognizedVMOptions", "-XX:UseAVX=2", "-XX:MaxVectorSize=32"})
public final class VarkaTwoLaneHost256Suite {

  @Test
  @DisplayName("under AVX2 at 256 bits an x86 host declines no long condition by default")
  void keepsAt256Bits() {
    VarkaTwoLaneHostCheck.check(2, false);
  }
}
