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

import java.util.Locale;
import java.util.Optional;

import javax.annotation.Nullable;

/**
 * Which native routine the Vector API binds a {@code DOUBLE} math operator to on a host, as the
 * JDK's {@code jdk.incubator.vector.VectorMathLibrary} decides it ({@code VARKA-345.md} item 0.7,
 * read in JDK 25's source): the math family's opt-in mode takes the library operator only where
 * the binding is bound and has a contract entry ({@link VarkaMathContract}). The rule is restated
 * here from the JDK's behaviour, not copied, and {@code VarkaMathBindingSuite} holds it to the
 * symbols a JVM run with {@code -Djdk.incubator.vector.DEBUG=true} prints, on each host CI runs.
 *
 * <p>The library is SVML on x86, SLEEF on aarch64 and riscv64 and none elsewhere, or the one the
 * property {@code jdk.incubator.vector.VectorMathLibrary} names; a library the host cannot load
 * leaves none, and the JDK ships SVML on Linux and Windows only and SLEEF on Linux and macOS only
 * (JDK 25's {@code make/modules/jdk.incubator.vector/Lib.gmk}). A 64-bit species, and a species
 * wider than the host's widest, is never bound. SVML names {@code __jsvml_<op><lanes>_ha_<level>},
 * the level {@code z0} at a 512-bit species and otherwise {@code l9} with AVX2, {@code e9} with AVX
 * and {@code ex} below; at 512 bits {@code LOG}, {@code LOG10} and {@code POW} need AVX512DQ,
 * which the emitter's AVX level does not say, so it is taken as present at level 3 and the binding
 * check is what fails on a host with AVX-512F and no DQ; below 512 bits {@code POW} is unbound.
 * SLEEF names {@code <op>d<lanes>_<tier><suffix>}, the tier {@code u05} for {@code HYPOT} and
 * {@code u10} otherwise, the lanes {@code x} and the suffix {@code sve} for an aarch64 species
 * wider than 128 bits, {@code advsimd} at 128 and {@code rvv} with {@code x} on riscv64;
 * {@code TANH} is never bound there.
 */
public final class VarkaMathBinding {

  private VarkaMathBinding() {
  }

  /** The property that names the library, as the JDK reads it. */
  public static final String LIBRARY_PROPERTY = "jdk.incubator.vector.VectorMathLibrary";

  /** The eighteen math operators the JDK binds, by their {@code VectorOperators} names. */
  public enum Operator {
    SIN, COS, TAN, ASIN, ACOS, ATAN, EXP, LOG, LOG10, LOG1P, EXPM1, SINH, COSH, TANH, CBRT,
    ATAN2, POW, HYPOT;

    /** The name inside a library symbol, e.g. {@code log10}. */
    public String symbolName() {
      return name().toLowerCase(Locale.ROOT);
    }

    /** Whether the operator takes two operands. */
    public boolean binary() {
      return this == ATAN2 || this == POW || this == HYPOT;
    }
  }

  /** The libraries the JDK can bind. */
  public enum Library { SVML, SLEEF, NONE }

  /**
   * What decides a binding on a host: {@code os.arch}, {@code os.name}, the library property
   * ({@code null} for none), the {@code -XX:UseAVX} level (0 on a host that is not x86, where it
   * is not read) and the widest {@code DOUBLE} species in bits.
   */
  public record Host(String arch, String os, @Nullable String libraryProperty, int avxLevel,
      int maxDoubleBits) {
    public Host {
      if (avxLevel < 0 || maxDoubleBits < 64 || Integer.bitCount(maxDoubleBits) != 1) {
        throw VarkaErrors.illegalArgument("a host has an AVX level of 0 or more and a widest"
            + " species of a power of two from 64 bits: " + avxLevel + ", " + maxDoubleBits);
      }
    }

    /** The library this host binds. */
    public Library library() {
      String system = os.toLowerCase(Locale.ROOT);
      boolean linux = system.startsWith("linux");
      boolean x86 = VarkaHostIsa.isX86(arch);
      boolean sleefArch = "aarch64".equals(arch) || "riscv64".equals(arch);
      String name = libraryProperty != null ? libraryProperty : x86 ? "svml"
          : sleefArch ? "sleef" : "java";
      return switch (name) {
        case "svml" -> x86 && (linux || system.startsWith("windows"))
            ? Library.SVML : Library.NONE;
        case "sleef" -> sleefArch && (linux || system.startsWith("mac"))
            ? Library.SLEEF : Library.NONE;
        default -> Library.NONE;
      };
    }
  }

  /** The symbol {@code op} binds to at a species of {@code bits} on {@code host}, or empty. */
  public static Optional<String> symbol(Operator op, int bits, Host host) {
    if (bits <= 64 || bits > host.maxDoubleBits() || Integer.bitCount(bits) != 1) {
      return Optional.empty();
    }
    int lanes = bits / 64;
    return switch (host.library()) {
      case SVML -> svml(op, bits, lanes, host.avxLevel());
      case SLEEF -> sleef(op, bits, lanes, host.arch());
      case NONE -> Optional.empty();
    };
  }

  private static Optional<String> svml(Operator op, int bits, int lanes, int avx) {
    if (bits == 512 && (op == Operator.LOG || op == Operator.LOG10 || op == Operator.POW)
        && avx < 3) {
      return Optional.empty();
    }
    if (bits < 512 && op == Operator.POW) {
      return Optional.empty();
    }
    String level = bits == 512 ? "z0" : avx >= 2 ? "l9" : avx >= 1 ? "e9" : "ex";
    return Optional.of("__jsvml_" + op.symbolName() + lanes + "_ha_" + level);
  }

  private static Optional<String> sleef(Operator op, int bits, int lanes, String arch) {
    if (op == Operator.TANH) {
      return Optional.empty();
    }
    boolean agnostic = "riscv64".equals(arch) || bits > 128;
    String suffix = "riscv64".equals(arch) ? "rvv" : agnostic ? "sve" : "advsimd";
    String tier = op == Operator.HYPOT ? "u05" : "u10";
    return Optional.of(op.symbolName() + "d" + (agnostic ? "x" : String.valueOf(lanes)) + "_"
        + tier + suffix);
  }

  /** SLEEF's stated tier for a symbol it binds, or empty for an SVML one, which states none. */
  public static Optional<String> statedTier(String symbol) {
    if (symbol.startsWith("__jsvml_")) {
      return Optional.empty();
    }
    int u = symbol.lastIndexOf("_u");
    return u < 0 || symbol.length() < u + 5 ? Optional.empty()
        : Optional.of(symbol.substring(u + 1, u + 4));
  }
}
