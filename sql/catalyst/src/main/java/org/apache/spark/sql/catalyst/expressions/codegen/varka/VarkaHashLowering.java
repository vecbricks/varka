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

import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDescriptors.*;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorWalk.*;

import java.lang.classfile.CodeBuilder;
import java.lang.constant.MethodTypeDesc;
import java.util.Set;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CanonicalBits;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.HashStep;

/**
 * The lowerings of {@link HashStep} and {@link CanonicalBits} (VARKA-337 2.2). A step is Spark's
 * primitive written lane by lane, with Spark's constants: {@code Murmur3_x86_32}'s
 * {@code mixK1}, {@code mixH1} and {@code fmix} on the int lane, {@code XXH64}'s
 * {@code hashInt} and {@code hashLong} tails and {@code fmix} on the long lane. Every operation is
 * Java's int or long operator, which the Vector API's {@code mul}, {@code add}, {@code ROL},
 * {@code LSHR} and {@code XOR} are lane by lane, so a lane's answer is the primitive's. The word's
 * half of a step (Murmur3's {@code mixK1}, XXH64's product) is emitted before the seed is read,
 * since it does not depend on it: a hash of {@code n} children has {@code n} independent word
 * chains beside its one serial seed chain. {@code h * 5} is written {@code (h << 2) + h}, a shift
 * and an add against a multiply, as Spark's JIT-compiled scalar code is.
 */
final class VarkaHashLowering {

  /** {@code Murmur3_x86_32.C1} and {@code C2}, the {@code mixK1} multipliers. */
  static final int MURMUR3_C1 = 0xcc9e2d51;
  static final int MURMUR3_C2 = 0x1b873593;
  /** {@code mixH1}'s addend after {@code h * 5}. */
  static final int MURMUR3_H1_ADD = 0xe6546b64;
  /** {@code fmix}'s two multipliers. */
  static final int MURMUR3_FMIX_1 = 0x85ebca6b;
  static final int MURMUR3_FMIX_2 = 0xc2b2ae35;

  /** {@code XXH64.PRIME64_1} to {@code PRIME64_5}. */
  static final long XXH64_P1 = 0x9E3779B185EBCA87L;
  static final long XXH64_P2 = 0xC2B2AE3D27D4EB4FL;
  static final long XXH64_P3 = 0x165667B19E3779F9L;
  static final long XXH64_P4 = 0x85EBCA77C2B2AE63L;
  static final long XXH64_P5 = 0x27D4EB2F165667C5L;

  private static final MethodTypeDesc INT_VIEW = MethodTypeDesc.of(INT_VECTOR);
  private static final MethodTypeDesc LONG_VIEW = MethodTypeDesc.of(LONG_VECTOR);

  private VarkaHashLowering() {
  }

  /**
   * {@code CanonicalBits}: the floating vector viewed as the integral one of its shape, which
   * changes no bit; the word is the child's.
   */
  static void emitCanonicalBits(CodeBuilder cb, CanonicalBits n, boolean dense,
      Analysis analysis, Slots s, Set<VarkaVectorIR> computed) {
    emitValue(cb, n.child(), dense, analysis, s, computed);
    line(cb, analysis, n);
    Lane from = analysis.laneOf(n.child());
    cb.invokevirtual(from.vector, "viewAsIntegralLanes",
        from == Lane.FLOAT ? INT_VIEW : LONG_VIEW);
  }

  /**
   * {@code HashStep}: the word's half, then the seed and the rest of the step. Spark skips a null
   * child, so in the masked body, where the word can be null, the seed is kept under the step and
   * the step is blended over it by the word's validity: a null lane answers its seed. The node's
   * validity is the seed's, aliased, so nothing is stored for it here.
   */
  static void emitHashStep(CodeBuilder cb, HashStep n, boolean dense, Analysis analysis,
      Slots s, Set<VarkaVectorIR> computed) {
    Lane lane = analysis.laneOf(n);
    emitValue(cb, n.word(), dense, analysis, s, computed);
    line(cb, analysis, n);
    int skip = dense ? WORD_ALL_TRUE : s.wordRef.get(n.word());
    switch (n.mix()) {
      case MURMUR3_INT, MURMUR3_LOW, MURMUR3_HIGH -> {
        emitMixK1(cb, lane);                                     // [k]
        emitSeed(cb, n, skip, dense, analysis, s, computed);     // [(h,) k, h]
        emitMixH1(cb, lane);                                     // [h]
        if (n.mix() != VarkaVectorIR.HashMix.MURMUR3_LOW) {
          emitMurmur3Fmix(cb, lane, n.mix() == VarkaVectorIR.HashMix.MURMUR3_INT ? 4 : 8);
        }
      }
      case XXH64_INT -> {
        // (input & 0xFFFFFFFFL) * P1, then hash = seed + P5 + 4 XORed with it.
        lane.pushScalar(cb, 0xFFFFFFFFL);
        cb.invokevirtual(lane.vector, "and", lane.lanewiseVI);
        mulScalar(cb, lane, XXH64_P1);                           // [k]
        emitSeed(cb, n, skip, dense, analysis, s, computed);
        addScalar(cb, lane, XXH64_P5 + 4L);                      // [(h,) k, h']
        xorVectors(cb, lane);
        rotateLeft(cb, lane, 23);
        mulScalar(cb, lane, XXH64_P2);
        addScalar(cb, lane, XXH64_P3);
        emitXxh64Fmix(cb, lane);
      }
      case XXH64_LONG -> {
        // rotl(input * P2, 31) * P1, then hash = seed + P5 + 8 XORed with it.
        mulScalar(cb, lane, XXH64_P2);
        rotateLeft(cb, lane, 31);
        mulScalar(cb, lane, XXH64_P1);                           // [k]
        emitSeed(cb, n, skip, dense, analysis, s, computed);
        addScalar(cb, lane, XXH64_P5 + 8L);                      // [(h,) k, h']
        xorVectors(cb, lane);
        rotateLeft(cb, lane, 27);
        mulScalar(cb, lane, XXH64_P1);
        addScalar(cb, lane, XXH64_P4);
        emitXxh64Fmix(cb, lane);
      }
    }
    if (skip != WORD_ALL_TRUE) {
      // [h, step] -> [h.blend(step, valid(word))]: a null word's lane keeps its seed.
      cb.aload(s.species(lane));
      loadWord(cb, s, skip);
      emitMaskOfWord(cb, lane, analysis);
      cb.invokevirtual(lane.vector, "blend", lane.blend);
    }
  }

  /**
   * {@code [k] -> [k, h]}, the seed pushed beside the word's half; where the word can be null
   * ({@code skip} a word slot), {@code [k] -> [h, k, h]}, a copy of the seed kept under both for
   * the null skip's blend.
   */
  private static void emitSeed(CodeBuilder cb, HashStep n, int skip, boolean dense,
      Analysis analysis, Slots s, Set<VarkaVectorIR> computed) {
    emitValue(cb, n.seed(), dense, analysis, s, computed);
    line(cb, analysis, n);
    if (skip != WORD_ALL_TRUE) {
      cb.dup_x1();
    }
  }

  /** {@code [k] -> [mixK1(k)]}: {@code k * C1}, rotated left by 15, times {@code C2}. */
  private static void emitMixK1(CodeBuilder cb, Lane lane) {
    mulScalar(cb, lane, MURMUR3_C1);
    rotateLeft(cb, lane, 15);
    mulScalar(cb, lane, MURMUR3_C2);
  }

  /** {@code [k, h] -> [mixH1(h, k)]}: {@code h ^ k}, rotated left by 13, then {@code h*5+C}. */
  private static void emitMixH1(CodeBuilder cb, Lane lane) {
    xorVectors(cb, lane);
    rotateLeft(cb, lane, 13);
    cb.dup();
    shift(cb, lane, "LSHL", 2);
    cb.invokevirtual(lane.vector, "add", lane.lanewiseVV);       // [(h << 2) + h]
    addScalar(cb, lane, MURMUR3_H1_ADD);
  }

  /** {@code [h] -> [fmix(h, length)]}, Murmur3's finalisation. */
  private static void emitMurmur3Fmix(CodeBuilder cb, Lane lane, int length) {
    cb.getstatic(VECTOR_OPERATORS, "XOR", VO_ASSOCIATIVE);
    lane.pushScalar(cb, length);
    cb.invokevirtual(lane.vector, "lanewise", lane.integral().lanewiseBinaryI());
    xorShifted(cb, lane, 16);
    mulScalar(cb, lane, MURMUR3_FMIX_1);
    xorShifted(cb, lane, 13);
    mulScalar(cb, lane, MURMUR3_FMIX_2);
    xorShifted(cb, lane, 16);
  }

  /** {@code [h] -> [fmix(h)]}, XXH64's finalisation. */
  private static void emitXxh64Fmix(CodeBuilder cb, Lane lane) {
    xorShifted(cb, lane, 33);
    mulScalar(cb, lane, XXH64_P2);
    xorShifted(cb, lane, 29);
    mulScalar(cb, lane, XXH64_P3);
    xorShifted(cb, lane, 32);
  }

  /** {@code [h] -> [h ^ (h >>> count)]}. */
  private static void xorShifted(CodeBuilder cb, Lane lane, int count) {
    cb.dup();
    shift(cb, lane, "LSHR", count);
    xorVectors(cb, lane);
  }

  /** {@code [a, b] -> [a ^ b]}: the receiver's argument order is {@code [a, XOR, b]}. */
  private static void xorVectors(CodeBuilder cb, Lane lane) {
    cb.getstatic(VECTOR_OPERATORS, "XOR", VO_ASSOCIATIVE);
    cb.swap();
    cb.invokevirtual(lane.vector, "lanewise", lane.integral().lanewiseBinaryV());
  }

  private static void rotateLeft(CodeBuilder cb, Lane lane, int count) {
    shift(cb, lane, "ROL", count);
  }

  /** {@code [h] -> [h OP count]} for a shift or rotate operator of {@code VectorOperators}. */
  private static void shift(CodeBuilder cb, Lane lane, String op, int count) {
    cb.getstatic(VECTOR_OPERATORS, op, VO_BINARY);
    lane.pushScalar(cb, count);
    cb.invokevirtual(lane.vector, "lanewise", lane.integral().lanewiseBinaryI());
  }

  private static void mulScalar(CodeBuilder cb, Lane lane, long value) {
    lane.pushScalar(cb, value);
    cb.invokevirtual(lane.vector, "mul", lane.lanewiseVI);
  }

  private static void addScalar(CodeBuilder cb, Lane lane, long value) {
    lane.pushScalar(cb, value);
    cb.invokevirtual(lane.vector, "add", lane.lanewiseVI);
  }
}
