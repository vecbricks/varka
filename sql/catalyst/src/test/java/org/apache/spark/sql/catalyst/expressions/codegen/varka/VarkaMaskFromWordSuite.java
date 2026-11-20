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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import scala.jdk.javaapi.CollectionConverters;

/**
 * {@code maskFromWord} (VARKA-375): {@link VarkaMaskFromWord} answers {@code VectorMask.fromLong}'s
 * mask for every lane type at every vector shape, and under the option no emitted kernel calls
 * {@code fromLong}, while at the defaults none calls the construction. The answers of the kernels
 * the option emits are the coverage differential's and the fuzzer's under
 * {@code -Dvarka.matrix.config=maskFromWord=true}, as for every option of the table.
 *
 * <p>The suite runs in a JVM of its own: the first test touches every shape of every lane type,
 * which the shared test JVM's species guard refuses.
 */
@VarkaSuite(ownJvm = true)
public final class VarkaMaskFromWordSuite {

  private static final String VECTOR_MASK = "jdk/incubator/vector/VectorMask";
  private static final String MASK_FROM_WORD = Type.getInternalName(VarkaMaskFromWord.class);
  private static final AtomicInteger CLASSES = new AtomicInteger();

  private static final List<Class<?>> LANE_TYPES = List.of(byte.class, short.class, int.class,
      long.class, float.class, double.class);

  /**
   * The words every species is checked at: none and all, each single bit, the two alternating
   * patterns, a run of low bits for every lane count, and random words.
   */
  private static long[] words() {
    var out = new ArrayList<Long>(List.of(0L, -1L, 0x5555_5555_5555_5555L,
        0xAAAA_AAAA_AAAA_AAAAL));
    for (int i = 0; i < Long.SIZE; i++) {
      out.add(1L << i);
      out.add(~(1L << i));
      out.add((1L << i) - 1);
    }
    var random = new Random(375);
    for (int i = 0; i < 4_000; i++) {
      out.add(random.nextLong());
    }
    return out.stream().mapToLong(Long::longValue).toArray();
  }

  /** Each word of {@code words} whose construction differs from {@code fromLong} at {@code s}. */
  private static <E> List<String> differences(VectorSpecies<E> s, long[] words) {
    var wrong = new ArrayList<String>();
    for (long w : words) {
      VectorMask<E> built = VarkaMaskFromWord.of(s, w);
      VectorMask<E> expected = VectorMask.fromLong(s, w);
      if (!built.vectorSpecies().equals(s) || built.toLong() != expected.toLong()) {
        wrong.add(s + " word " + Long.toHexString(w) + ": " + Long.toHexString(built.toLong())
            + " where fromLong gives " + Long.toHexString(expected.toLong()));
      }
    }
    return wrong;
  }

  @Test
  @DisplayName("the construction answers fromLong's mask for every lane type at every shape")
  void answersFromLong() {
    long[] words = words();
    var wrong = new ArrayList<String>();
    int species = 0;
    for (int bits : new int[] {64, 128, 256, 512}) {
      for (Class<?> type : LANE_TYPES) {
        var s = VectorSpecies.of(type, VectorShape.forBitSize(bits));
        // The species is not a constant here, so whatever is compiled runs the Vector API's Java
        // forms: this checks the construction's arithmetic. Its intrinsic forms, at a kernel's
        // constant species, are checked by the coverage differential under the option.
        for (int round = 0; round < 3; round++) {
          wrong.addAll(differences(s, words));
        }
        species++;
      }
    }
    assertEquals(24, species);
    assertTrue(wrong.isEmpty(), "masks that differ from fromLong:\n  "
        + String.join("\n  ", wrong.subList(0, Math.min(wrong.size(), 20))));
  }

  /** The coverage rows' kernels as the compiler builds them, with their names. */
  private static List<VarkaWidthAuditShapes.Shape> shapes() {
    String home = System.getProperty("spark.test.home", System.getenv("SPARK_HOME"));
    assertTrue(home != null, "spark.test.home or SPARK_HOME is not set.");
    var out = new ArrayList<VarkaWidthAuditShapes.Shape>();
    for (var e : CollectionConverters.asJava(
        VarkaWidthAuditShapes.all(new File(home, "sql/varka/coverage.json")))) {
      if (e instanceof VarkaWidthAuditShapes.Shape s) {
        out.add(s);
      }
    }
    return out;
  }

  /** What one scan of the coverage rows' kernels found. */
  private record Scan(int kernels, int withConstruction, List<String> fromLongs,
      List<String> constructions) {}

  /**
   * The calls of {@code fromLong} and of the construction in every coverage row's kernel under
   * {@code options}, as {@code row - method}. A shape the options refuse a plan for, or make the
   * emitter decline, has no kernel to scan.
   */
  private static Scan scan(VarkaEmitOptions options) {
    int kernels = 0;
    int withConstruction = 0;
    var fromLongs = new ArrayList<String>();
    var constructions = new ArrayList<String>();
    for (var s : shapes()) {
      var roots = CollectionConverters.asJava(s.roots());
      if (VarkaKernelPlan.of(roots, options).refusal().isPresent()) {
        continue;
      }
      byte[] bytes;
      try {
        bytes = VarkaLoopEmitter.emit("org.apache.spark.sql.varka.execution.VarkaMaskFromWordScan"
            + CLASSES.incrementAndGet(), roots, s.numInputs(), s.numLiterals(), null, null,
            options);
      } catch (VarkaEmitDeclined d) {
        continue;
      }
      kernels++;
      var node = new ClassNode();
      new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
      boolean built = false;
      for (MethodNode m : node.methods) {
        for (AbstractInsnNode insn : m.instructions) {
          if (insn instanceof MethodInsnNode call) {
            if (call.owner.equals(VECTOR_MASK) && call.name.equals("fromLong")) {
              fromLongs.add(s.name() + " - " + m.name);
            } else if (call.owner.equals(MASK_FROM_WORD)) {
              constructions.add(s.name() + " - " + m.name + " " + call.name);
              built = true;
            }
          } else if (insn instanceof FieldInsnNode field && field.owner.equals(MASK_FROM_WORD)) {
            // The emitted construction loads the table of each lane's bit.
            constructions.add(s.name() + " - " + m.name + " " + field.name);
            built = true;
          }
        }
      }
      withConstruction += built ? 1 : 0;
    }
    return new Scan(kernels, withConstruction, fromLongs, constructions);
  }

  @Test
  @DisplayName("under maskFromWord no kernel calls fromLong, and the masked ones build masks")
  void optionReplacesEverySite() {
    var scan = scan(VarkaEmitOptions.DEFAULTS.toBuilder().maskFromWord(true).build());
    assertTrue(scan.kernels() > 200, "only " + scan.kernels() + " coverage rows were scanned");
    assertTrue(scan.fromLongs().isEmpty(), "fromLong under maskFromWord:\n  "
        + String.join("\n  ", scan.fromLongs()));
    // Every kernel has a masked body, and each reads at least one validity word as a mask.
    assertTrue(scan.withConstruction() > scan.kernels() / 2, "only " + scan.withConstruction()
        + " of " + scan.kernels() + " kernels call the construction");
  }

  @Test
  @Tag(VarkaSuite.PINS_DEFAULTS)
  @DisplayName("at the defaults no kernel calls the construction, and the masked ones fromLong")
  void defaultsKeepFromLong() {
    var scan = scan(VarkaEmitOptions.DEFAULTS);
    assertTrue(scan.kernels() > 200, "only " + scan.kernels() + " coverage rows were scanned");
    assertTrue(scan.constructions().isEmpty(), "the construction at the defaults:\n  "
        + String.join("\n  ", scan.constructions()));
    assertTrue(scan.fromLongs().size() > scan.kernels() / 2, "only " + scan.fromLongs().size()
        + " fromLong calls in " + scan.kernels() + " kernels");
  }
}
