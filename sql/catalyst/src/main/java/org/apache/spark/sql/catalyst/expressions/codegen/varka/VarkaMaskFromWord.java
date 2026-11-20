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

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * {@code VectorMask.fromLong} built from operators that need no mask-from-bits instruction
 * (VARKA-375): lane {@code i} of the mask is set where bit {@code i} of the word is, and the bits
 * at and above the species' lane count are ignored, as {@code fromLong} does. Emitted kernels make
 * the same construction under {@link VarkaEmitOptions#maskFromWord()}, emitted operator by operator
 * from this class's tables ({@code VarkaVectorWalk.emitFromLong}), and call a method here only for
 * an int species past 32 lanes; the template warm-up and the tests call it.
 *
 * <p>Why it exists: JDK 25's C2 lowers {@code fromLong} (the {@code VectorLongToMask} node) on
 * aarch64 only where the core has SVE2's bit-permute extension ({@code aarch64_vector.ad}, the
 * {@code supports_svebitperm} check), so on a NEON-only or SVE1 core it is the Vector API's Java
 * fallback, which builds a {@code boolean[]} per call. The construction here is a broadcast of the
 * word, an {@code AND} with a constant vector holding each lane's bit, and a compare with zero.
 * A lane narrower than the lane count needs bits from more than one of its own elements: there the
 * word is broadcast as longs, viewed at the lane's width, and each lane picks the element that
 * holds its bit with {@code selectFrom} by a constant index vector. Floating lanes take the mask of
 * the integral species of their width and cast it, which keeps the lane count.
 *
 * <p>Each method is small and reads its species once, so where C2 inlines it at a constant
 * species it folds the branches on the lane count; C2 does not force-inline it, so a wide kernel
 * past its inlining limits keeps the call, which is why the kernels emit the broadcast form
 * themselves. The constant vectors are loads
 * from the tables below; nothing writes them. A species of at most 64 lanes is served, as many as
 * a word describes and more than any species up to 512 bits has; a wider one throws from the
 * tables' loads.
 *
 * <p>Where the construction is itself a fallback: the byte and short forms past one element's
 * lanes need a rearrange, which x86 C2 lowers for bytes only with AVX-512 VBMI at 512 bits and for
 * shorts only with AVX512BW; and any mask of two 64-bit lanes on x86, which C2 does not lower at
 * all (m7 row 374). {@code fromLong} is the default for those hosts.
 */
public final class VarkaMaskFromWord {

  private VarkaMaskFromWord() {}

  /** The most lanes a word can describe. */
  private static final int WORD_BITS = Long.SIZE;

  /*
   * Lane i's bit at each integral lane, and below lane i's element at the narrow lanes, public
   * because emitted kernels load them (they live in another runtime package); nothing may write
   * them, which VarkaMaskFromWordSuite checks.
   */
  public static final long[] LONG_BITS = new long[WORD_BITS];
  public static final int[] INT_BITS = new int[WORD_BITS];
  public static final short[] SHORT_BITS = new short[WORD_BITS];
  public static final byte[] BYTE_BITS = new byte[WORD_BITS];
  /** Lane {@code i}'s element of the word viewed at the lane's width: {@code i / width}. */
  private static final int[] INT_ELEMENT = new int[WORD_BITS];
  public static final short[] SHORT_ELEMENT = new short[WORD_BITS];
  public static final byte[] BYTE_ELEMENT = new byte[WORD_BITS];

  static {
    for (int i = 0; i < WORD_BITS; i++) {
      LONG_BITS[i] = 1L << i;
      INT_BITS[i] = 1 << (i % Integer.SIZE);
      SHORT_BITS[i] = (short) (1 << (i % Short.SIZE));
      BYTE_BITS[i] = (byte) (1 << (i % Byte.SIZE));
      INT_ELEMENT[i] = i / Integer.SIZE;
      SHORT_ELEMENT[i] = (short) (i / Short.SIZE);
      BYTE_ELEMENT[i] = (byte) (i / Byte.SIZE);
    }
  }

  /** The mask of {@code word} at a long species. */
  public static VectorMask<Long> ofLong(VectorSpecies<Long> s, long word) {
    return LongVector.broadcast(s, word).and(LongVector.fromArray(s, LONG_BITS, 0))
        .compare(VectorOperators.NE, 0L);
  }

  /**
   * The mask of {@code word} at an int species: the low half of the word broadcast where it holds
   * every lane's bit, as it does up to 32 lanes, which is every species of 1024 bits or fewer.
   */
  public static VectorMask<Integer> ofInt(VectorSpecies<Integer> s, long word) {
    IntVector words = s.length() <= Integer.SIZE
        ? IntVector.broadcast(s, (int) word)
        : IntVector.fromArray(s, INT_ELEMENT, 0)
            .selectFrom(LongVector.broadcast(s.withLanes(long.class), word).reinterpretAsInts());
    return words.and(IntVector.fromArray(s, INT_BITS, 0)).compare(VectorOperators.NE, 0);
  }

  /** The mask of {@code word} at a short species; a broadcast up to 16 lanes, as for ints. */
  public static VectorMask<Short> ofShort(VectorSpecies<Short> s, long word) {
    ShortVector words = s.length() <= Short.SIZE
        ? ShortVector.broadcast(s, (short) word)
        : ShortVector.fromArray(s, SHORT_ELEMENT, 0)
            .selectFrom(LongVector.broadcast(s.withLanes(long.class), word).reinterpretAsShorts());
    return words.and(ShortVector.fromArray(s, SHORT_BITS, 0))
        .compare(VectorOperators.NE, (short) 0);
  }

  /** The mask of {@code word} at a byte species; a broadcast up to 8 lanes, as for ints. */
  public static VectorMask<Byte> ofByte(VectorSpecies<Byte> s, long word) {
    ByteVector words = s.length() <= Byte.SIZE
        ? ByteVector.broadcast(s, (byte) word)
        : ByteVector.fromArray(s, BYTE_ELEMENT, 0)
            .selectFrom(LongVector.broadcast(s.withLanes(long.class), word).reinterpretAsBytes());
    return words.and(ByteVector.fromArray(s, BYTE_BITS, 0))
        .compare(VectorOperators.NE, (byte) 0);
  }

  /** The mask of {@code word} at a double species, through the long species of its shape. */
  public static VectorMask<Double> ofDouble(VectorSpecies<Double> s, long word) {
    return ofLong(s.withLanes(long.class), word).cast(s);
  }

  /** The mask of {@code word} at a float species, through the int species of its shape. */
  public static VectorMask<Float> ofFloat(VectorSpecies<Float> s, long word) {
    return ofInt(s.withLanes(int.class), word).cast(s);
  }

  /**
   * The mask of {@code word} at any species, by its element type: for the template warm-up and
   * the tests, which hold a species of a type they do not name.
   */
  @SuppressWarnings("unchecked")
  public static <E> VectorMask<E> of(VectorSpecies<E> s, long word) {
    if (s.length() > WORD_BITS) {
      // Past the tables' 64 lanes, which only SVE above 512 bits reaches: fromLong's mask, so the
      // template warm-up never throws on such a host.
      return VectorMask.fromLong(s, word);
    }
    Class<E> type = s.elementType();
    VectorSpecies<?> any = s;
    VectorMask<?> mask;
    if (type == long.class) {
      mask = ofLong((VectorSpecies<Long>) any, word);
    } else if (type == int.class) {
      mask = ofInt((VectorSpecies<Integer>) any, word);
    } else if (type == short.class) {
      mask = ofShort((VectorSpecies<Short>) any, word);
    } else if (type == byte.class) {
      mask = ofByte((VectorSpecies<Byte>) any, word);
    } else if (type == double.class) {
      mask = ofDouble((VectorSpecies<Double>) any, word);
    } else if (type == float.class) {
      mask = ofFloat((VectorSpecies<Float>) any, word);
    } else {
      throw VarkaErrors.illegalArgument("no vector lane of " + type);
    }
    return (VectorMask<E>) mask;
  }
}
