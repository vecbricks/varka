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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMathBinding.Host;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMathBinding.Operator;

/**
 * The binding check of the math family's opt-in mode ({@code VARKA-345.md} 2.5, 3.6): a JVM run
 * with {@code -Djdk.incubator.vector.DEBUG=true} ({@link VarkaMathBindingProbe}) touches every
 * math operator at every {@code DOUBLE} species it has, and the symbols the JDK prints are held to
 * {@link VarkaMathBinding}'s: a symbol wherever the resolver names one, none where it names none.
 * It runs in the pull request's build, which is x86, and in the width audit workflow on the Arm
 * runner, so each JDK build CI uses is checked on both architectures. The DEBUG line proves the
 * symbol and not the call; the call is checked by hand with {@code dev/varka_emit.sh --asm}
 * ({@code VARKA-345.md} 2.5), once a math form exists to emit.
 */
@VarkaSuite
public final class VarkaMathBindingSuite {

  private static final long CHILD_TIMEOUT_SECONDS = 120L;

  private static final Pattern HOST =
      Pattern.compile("^HOST arch=(\\S+) os=(\\S+) avx=(\\d+) max=(\\d+) property=(.*)$");
  private static final Pattern TOUCH = Pattern.compile("^TOUCH (\\w+) (\\d+)$");
  private static final Pattern BOUND =
      Pattern.compile("^DEBUG: VectorMathLibrary: (\\w+) (\\S+) => 0x[0-9a-f]+$");

  private static final String LINUX = "Linux";

  private static Optional<String> svml(Operator op, int bits, int avx) {
    return VarkaMathBinding.symbol(op, bits, new Host("amd64", LINUX, null, avx, 512));
  }

  @Test
  @DisplayName("the resolver names the JDK's symbols, and none where the JDK binds none")
  void resolverRules() {
    assertEquals(Optional.of("__jsvml_sin8_ha_z0"), svml(Operator.SIN, 512, 3));
    assertEquals(Optional.of("__jsvml_log108_ha_z0"), svml(Operator.LOG10, 512, 3));
    assertEquals(Optional.of("__jsvml_atan24_ha_l9"), svml(Operator.ATAN2, 256, 3));
    assertEquals(Optional.of("__jsvml_expm12_ha_l9"), svml(Operator.EXPM1, 128, 2));
    assertEquals(Optional.of("__jsvml_cos2_ha_e9"), svml(Operator.COS, 128, 1));
    assertEquals(Optional.of("__jsvml_cos2_ha_ex"), svml(Operator.COS, 128, 0));
    // AVX512DQ is taken from level 3; POW has no symbol below 512 bits, and 64 bits none at all.
    assertEquals(Optional.empty(), svml(Operator.LOG, 512, 2));
    assertEquals(Optional.of("__jsvml_pow8_ha_z0"), svml(Operator.POW, 512, 3));
    assertEquals(Optional.empty(), svml(Operator.POW, 256, 3));
    assertEquals(Optional.empty(), svml(Operator.SIN, 64, 3));
    var avx2 = new Host("amd64", LINUX, null, 2, 256);
    assertEquals(Optional.empty(), VarkaMathBinding.symbol(Operator.SIN, 512, avx2));
    var neon = new Host("aarch64", LINUX, null, 0, 128);
    var sve = new Host("aarch64", LINUX, null, 0, 512);
    var rvv = new Host("riscv64", LINUX, null, 0, 256);
    assertEquals(Optional.of("sind2_u10advsimd"), VarkaMathBinding.symbol(Operator.SIN, 128, neon));
    assertEquals(Optional.of("hypotd2_u05advsimd"),
        VarkaMathBinding.symbol(Operator.HYPOT, 128, neon));
    assertEquals(Optional.of("expdx_u10sve"), VarkaMathBinding.symbol(Operator.EXP, 256, sve));
    assertEquals(Optional.of("logdx_u10rvv"), VarkaMathBinding.symbol(Operator.LOG, 128, rvv));
    assertEquals(Optional.empty(), VarkaMathBinding.symbol(Operator.TANH, 128, neon));
    assertEquals(Optional.of("u05"), VarkaMathBinding.statedTier("hypotd2_u05advsimd"));
    assertEquals(Optional.of("u10"), VarkaMathBinding.statedTier("expdx_u10sve"));
    assertEquals(Optional.empty(), VarkaMathBinding.statedTier("__jsvml_sin8_ha_z0"));
    // The property chooses, and a library the host cannot load, or "java", binds nothing.
    for (String property : List.of("java", "sleef", "unknown")) {
      var host = new Host("amd64", LINUX, property, 3, 512);
      assertEquals(VarkaMathBinding.Library.NONE, host.library(), property);
    }
    assertEquals(VarkaMathBinding.Library.SVML,
        new Host("x86_64", "Windows 11", "svml", 2, 256).library());
    assertEquals(VarkaMathBinding.Library.NONE,
        new Host("aarch64", LINUX, "svml", 0, 128).library());
    assertEquals(VarkaMathBinding.Library.NONE, new Host("s390x", LINUX, null, 0, 128).library());
    // The JDK ships SVML on Linux and Windows only, and SLEEF on Linux and macOS only.
    assertEquals(VarkaMathBinding.Library.NONE,
        new Host("x86_64", "Mac OS X", null, 2, 256).library());
    assertEquals(VarkaMathBinding.Library.SLEEF,
        new Host("aarch64", "Mac OS X", null, 0, 128).library());
    assertEquals(VarkaMathBinding.Library.NONE,
        new Host("aarch64", "Windows 11", null, 0, 128).library());
  }

  @Test
  @DisplayName("this JVM binds exactly the symbols the resolver names, at every species it has")
  void thisJvmBindsWhatTheResolverNames() throws IOException {
    Probed probed = check(List.of());
    // Where the resolver names a library the JDK binds operators, so an empty answer there would
    // mean the probe reached no binding at all rather than agreement.
    if (probed.host().library() != VarkaMathBinding.Library.NONE) {
      assertTrue(!probed.bound().isEmpty(), "no operator bound on " + probed.host());
    }
  }

  @Test
  @DisplayName("with the library property set to java this JVM binds no operator at all")
  void javaLibraryBindsNothing() throws IOException {
    Probed probed = check(List.of("-D" + VarkaMathBinding.LIBRARY_PROPERTY + "=java"));
    assertEquals(Map.of(), probed.bound());
  }

  /** The host a probe printed, and the symbols the JDK bound by {@code <operator> <bits>}. */
  private record Probed(Host host, Map<String, String> bound) {
  }

  /** Runs the probe with {@code flags} and holds what the JDK bound to the resolver. */
  private static Probed check(List<String> flags) throws IOException {
    List<String> out = fork(flags);
    Host host = null;
    var bound = new TreeMap<String, String>();
    var touched = new ArrayList<String>();
    String touch = null;
    for (String line : out) {
      Matcher h = HOST.matcher(line);
      Matcher t = TOUCH.matcher(line);
      Matcher b = BOUND.matcher(line);
      if (h.matches()) {
        host = new Host(h.group(1), h.group(2).replace('_', ' '),
            "-".equals(h.group(5)) ? null : h.group(5), Integer.parseInt(h.group(3)),
            Integer.parseInt(h.group(4)));
      } else if (t.matches()) {
        touch = t.group(1) + " " + t.group(2);
        touched.add(touch);
      } else if (b.matches()) {
        assertTrue(touch != null && touch.startsWith(b.group(1) + " "),
            "a binding printed outside its touch: " + line);
        bound.put(touch, b.group(2));
      }
    }
    assertTrue(host != null, "the probe printed no host line:\n" + String.join("\n", out));
    int widths = Integer.numberOfTrailingZeros(host.maxDoubleBits() / 32);
    assertEquals(Operator.values().length * widths, touched.size(),
        "touches, one an operator at each species from 64 bits");
    var expected = new TreeMap<String, String>();
    for (String key : touched) {
      String[] k = key.split(" ", -1);
      VarkaMathBinding.symbol(Operator.valueOf(k[0]), Integer.parseInt(k[1]), host)
          .ifPresent(s -> expected.put(key, s));
    }
    assertEquals(expected, bound, "the JDK's bindings on " + host);
    return new Probed(host, bound);
  }

  private static List<String> fork(List<String> flags) throws IOException {
    var jvmFlags = new ArrayList<String>();
    jvmFlags.add("-Djdk.incubator.vector.DEBUG=true");
    jvmFlags.addAll(flags);
    var out = new ArrayList<String>();
    // The shared launcher destroys the child on its budget and on the watchdog's interrupt.
    VarkaChildProcess.run("VarkaMathBindingSuite's binding probe", new ProcessBuilder(
        VarkaChildProcess.javaCommand(jvmFlags, VarkaMathBindingProbe.class.getName(),
            List.of())), CHILD_TIMEOUT_SECONDS, out::add).requireSuccess(true);
    return out;
  }
}
