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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.Murmur3HashFunction;
import org.apache.spark.sql.catalyst.expressions.XxHash64Function;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BitOpKind;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CanonicalBits;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CanonicalFloat;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.HashMix;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.HashStep;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;

/**
 * {@code hash} and {@code xxhash64} kernels (VARKA-337 2.2, 3.1, 3.2), held bit for bit to
 * Spark's {@code Murmur3HashFunction.hash} and {@code XxHash64Function.hash}, the functions the
 * two expressions evaluate per child. Each kernel is the tree the compiler builds for its
 * children, {@link #step}: a step per child over its word, from the seed's literal, skipping a
 * null word. The regions: the int and long extremes, the powers of two and their negatives, the
 * {@code FLOAT} and {@code DOUBLE} cells of {@code VarkaEmitterCanonicalFloatSuite} (both zeros,
 * NaN payloads of both signs, the four {@code DOUBLE} NaN range endpoints), then a seeded draw,
 * at five seeds, a null in every seventh row after the cells, where the answer is the seed; and a
 * fold of two children with a null in each position. Every {@code INT} and every {@code FLOAT}
 * pattern, through both hashes, behind {@code -Dvarka.sweep=true} (3.1).
 */
@VarkaSuite(ownJvm = true)
public final class VarkaEmitterHashSuite {

  /** Spark's seed of both expressions. */
  private static final int SEED = 42;

  /** The seeds the regions are hashed at: Spark's, then drawn ones. */
  private static final long[] SEEDS = {SEED, 0L, -1L, 0x5bd1e995L, Long.MIN_VALUE + 9L};

  /** Rows drawn after a lane's cells. */
  private static final int DRAWN = 4000;

  /**
   * The step the compiler builds for one child over {@code value}, from {@code seed}: the bits of
   * a floating value's canonicalisation, an int widened under XXH64, a long's two halves under
   * Murmur3, the high one by {@code shift32}.
   */
  public static VarkaVectorIR step(boolean xxh64, VarkaVectorIR seed, VarkaVectorIR value,
      LiteralSlot shift32) {
    VarkaVectorIR w = switch (value.laneType()) {
      case FLOAT, DOUBLE -> new CanonicalBits(CanonicalFloat.of(value));
      case BYTE, SHORT, INT, LONG -> value;
    };
    if (xxh64) {
      return w.laneType() == LaneType.INT
          ? new HashStep(HashMix.XXH64_INT, seed, new WidenLane(w, LaneType.LONG))
          : new HashStep(HashMix.XXH64_LONG, seed, w);
    }
    if (w.laneType() == LaneType.INT) {
      return new HashStep(HashMix.MURMUR3_INT, seed, w);
    }
    var high = new NarrowLane(new BitOp(BitOpKind.USHR, w, shift32), LaneType.INT);
    return new HashStep(HashMix.MURMUR3_HIGH,
        new HashStep(HashMix.MURMUR3_LOW, seed, new NarrowLane(w, LaneType.INT)), high);
  }

  /** The fold over one column per lane of {@code lanes}, its seed and shift count as slots. */
  private static VarkaVectorIR fold(boolean xxh64, LaneType... lanes) {
    // The seed is slot 0 of its lane's table; the shift count sits in the long table beside it.
    VarkaVectorIR h = new LiteralSlot(0, xxh64 ? LaneType.LONG : LaneType.INT);
    var shift = new LiteralSlot(xxh64 ? 1 : 0, LaneType.LONG);
    for (int c = 0; c < lanes.length; c++) {
      h = step(xxh64, h, new ColumnRef(c, lanes[c]), shift);
    }
    return h;
  }

  /** Spark's answer for one child's raw bits at {@code lane}, a null child keeping the seed. */
  static long spark(boolean xxh64, LaneType lane, long bits, boolean isNull, long seed) {
    if (isNull) {
      return xxh64 ? seed : (int) seed;
    }
    Object v = switch (lane) {
      case INT -> (int) bits;
      case LONG -> bits;
      case FLOAT -> Float.intBitsToFloat((int) bits);
      case DOUBLE -> Double.longBitsToDouble(bits);
      case BYTE, SHORT -> throw new IllegalArgumentException("a narrow lane: " + lane);
    };
    DataType t = switch (lane) {
      case INT -> DataTypes.IntegerType;
      case LONG -> DataTypes.LongType;
      case FLOAT -> DataTypes.FloatType;
      case DOUBLE, BYTE, SHORT -> DataTypes.DoubleType;
    };
    return xxh64 ? XxHash64Function.hash(v, t, seed, false, false)
        : (int) Murmur3HashFunction.hash(v, t, (int) seed, false, false);
  }

  /**
   * Runs the fold of {@code xxh64} over {@code columns.length} columns, column {@code c} at
   * {@code lanes[c]} null where {@code nulls[c]} holds, from {@code seed}: the output rows, an int
   * output sign-extended. Every row is valid, as a hash is never null.
   */
  private static long[] run(boolean xxh64, long seed, LaneType[] lanes, long[][] columns,
      IntPredicate[] nulls) {
    VarkaVectorIR root = fold(xxh64, lanes);
    int n = columns[0].length;
    boolean wide = xxh64 || List.of(lanes).stream().anyMatch(l -> l.bits() == Long.SIZE);
    int[] ints = xxh64 ? new int[0] : new int[] {(int) seed};
    long[] longs = xxh64 ? new long[] {seed, 32L} : new long[] {32L};
    var loaded = VarkaEmitterFixtures.load(VarkaEmitterFixtures.emit(List.of(root),
        lanes.length, 1, VarkaMatrixRuns.BASE));
    try (Arena arena = Arena.ofConfined()) {
      long[] in = new long[lanes.length];
      long[] valid = new long[lanes.length];
      int[] nullCounts = new int[lanes.length];
      for (int c = 0; c < lanes.length; c++) {
        long[] values = columns[c];
        var col = lanes[c].bits() == Long.SIZE
            ? VarkaEmitterFixtures.makeLongInput(arena, n, nulls[c], i -> values[i], true)
            : VarkaEmitterFixtures.makeInputData(arena, n, nulls[c], i -> (int) values[i], true);
        in[c] = col.data().address();
        valid[c] = col.validityAddress(n);
        nullCounts[c] = col.nullCount();
      }
      var out = xxh64 ? VarkaEmitterFixtures.makeLongOutput(arena, n)
          : VarkaEmitterFixtures.makeOutput(arena, n, -1);
      long[] dst = {out.data().address()};
      long[] dstValid = {out.validity().address()};
      long scratch = VarkaEmitterTestSupport.scratch(loaded.kernel(), n);
      int status = wide
          ? loaded.kernel().run(in, valid, nullCounts, dst, dstValid, ints, longs, n, scratch)
          : loaded.kernel().run(in, valid, nullCounts, dst, dstValid, ints, n, scratch);
      assertEquals(0, status, "the kernel declined a batch it computes");
      long[] got = new long[n];
      for (int i = 0; i < n; i++) {
        assertTrue((out.validity().get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) != 0,
            "a hash is never null, row " + i);
        got[i] = xxh64 ? out.data().get(ValueLayout.JAVA_LONG, i * 8L)
            : out.data().get(ValueLayout.JAVA_INT, i * 4L);
      }
      return got;
    } finally {
      loaded.loader().release();
    }
  }

  /** The cells of {@code lane}, then a seeded draw. */
  private static long[] data(LaneType lane) {
    var cells = new ArrayList<Long>();
    int bits = lane.bits();
    if (!lane.isFloating()) {
      for (int k = 0; k < bits; k++) {
        cells.add(bits == Long.SIZE ? 1L << k : (long) (1 << k));
        cells.add(bits == Long.SIZE ? -(1L << k) : (long) -(1 << k));
      }
      cells.addAll(List.of(0L, 1L, -1L, bits == Long.SIZE ? Long.MAX_VALUE : Integer.MAX_VALUE,
          bits == Long.SIZE ? Long.MIN_VALUE : Integer.MIN_VALUE, 0xFFFFFFFFL, 0x100000000L));
    } else if (lane == LaneType.FLOAT) {
      for (int c : VarkaEmitterCanonicalFloatSuite.FLOAT_CELLS) {
        cells.add((long) c);
      }
    } else {
      for (long c : VarkaEmitterCanonicalFloatSuite.DOUBLE_CELLS) {
        cells.add(c);
      }
    }
    var rnd = new SplittableRandom(337 + lane.ordinal());
    long[] out = new long[cells.size() + DRAWN];
    for (int i = 0; i < out.length; i++) {
      out[i] = i < cells.size() ? cells.get(i) : bits == Long.SIZE ? rnd.nextLong() : rnd.nextInt();
    }
    return out;
  }

  /** Each row at which a one-column kernel differs from Spark, over the regions at every seed. */
  private static List<String> mismatches(boolean xxh64, LaneType lane) {
    long[] values = data(lane);
    int cells = values.length - DRAWN;
    IntPredicate isNull = i -> i >= cells && i % 7 == 3;
    var bad = new ArrayList<String>();
    for (long seed : SEEDS) {
      long s = xxh64 ? seed : (int) seed;
      long[] got = run(xxh64, s, new LaneType[] {lane}, new long[][] {values},
          new IntPredicate[] {isNull});
      for (int i = 0; i < values.length; i++) {
        long want = spark(xxh64, lane, values[i], isNull.test(i), s);
        if (want != got[i]) {
          bad.add((xxh64 ? "xxhash64" : "hash") + " " + lane + " seed " + s + " of 0x"
              + Long.toHexString(values[i]) + ": want " + want + ", got " + got[i]);
        }
      }
    }
    return bad;
  }

  @Test
  @DisplayName("hash and xxhash64 of an INT column are Spark's over the extremes, the powers of "
      + "two and a draw, at five seeds, a null hashing to the seed")
  void intColumn() {
    assertEquals(List.of(), mismatches(false, LaneType.INT));
    assertEquals(List.of(), mismatches(true, LaneType.INT));
  }

  @Test
  @DisplayName("hash and xxhash64 of a BIGINT column are Spark's over the extremes, the powers "
      + "of two and their negatives and a draw, at five seeds")
  void longColumn() {
    assertEquals(List.of(), mismatches(false, LaneType.LONG));
    assertEquals(List.of(), mismatches(true, LaneType.LONG));
  }

  @Test
  @DisplayName("hash and xxhash64 of a FLOAT column are Spark's over both zeros, the NaN "
      + "payloads of both signs and a draw")
  void floatColumn() {
    assertEquals(List.of(), mismatches(false, LaneType.FLOAT));
    assertEquals(List.of(), mismatches(true, LaneType.FLOAT));
  }

  @Test
  @DisplayName("hash and xxhash64 of a DOUBLE column are Spark's over the four NaN range "
      + "endpoints, the payloads, both zeros and a draw")
  void doubleColumn() {
    assertEquals(List.of(), mismatches(false, LaneType.DOUBLE));
    assertEquals(List.of(), mismatches(true, LaneType.DOUBLE));
  }

  @Test
  @DisplayName("a fold of an INT and a BIGINT child is Spark's, a null in either position or "
      + "both leaving the running value as it is")
  void foldOfTwoWithNulls() {
    int n = DRAWN;
    var rnd = new SplittableRandom(3372);
    long[] a = rnd.ints(n).asLongStream().toArray();
    long[] b = rnd.longs(n).toArray();
    IntPredicate aNull = i -> i % 3 == 1;
    IntPredicate bNull = i -> i % 5 == 2;
    for (boolean xxh64 : new boolean[] {false, true}) {
      long[] got = run(xxh64, SEED, new LaneType[] {LaneType.INT, LaneType.LONG},
          new long[][] {a, b}, new IntPredicate[] {aNull, bNull});
      var bad = new ArrayList<String>();
      for (int i = 0; i < n; i++) {
        long h = spark(xxh64, LaneType.INT, a[i], aNull.test(i), SEED);
        h = spark(xxh64, LaneType.LONG, b[i], bNull.test(i), h);
        if (got[i] != h) {
          bad.add((xxh64 ? "xxhash64" : "hash") + " row " + i + ": want " + h + ", got "
              + got[i]);
        }
      }
      assertEquals(List.of(), bad);
    }
  }

  @Test
  @DisplayName("a hash step refuses a word or a seed off its lane, and the bits of a floating "
      + "value are read only from its canonicalisation")
  void constructorsRefuse() {
    var i = new ColumnRef(0, LaneType.INT);
    var l = new ColumnRef(1, LaneType.LONG);
    assertThrows(IllegalArgumentException.class, () -> new HashStep(HashMix.MURMUR3_INT, i, l));
    assertThrows(IllegalArgumentException.class, () -> new HashStep(HashMix.XXH64_LONG, i, l));
    assertThrows(IllegalArgumentException.class,
        () -> new CanonicalBits(new ColumnRef(0, LaneType.FLOAT)));
  }

  /** Rows per chunk of the 2^32 sweeps. */
  private static final int CHUNK = 1 << 16;

  /** Every 32-bit pattern at {@code lane} through one hash: the count of rows off Spark's. */
  private static long sweep(boolean xxh64, LaneType lane) {
    var loaded = VarkaEmitterFixtures.load(VarkaEmitterFixtures.emit(List.of(fold(xxh64, lane)),
        1, 1, VarkaMatrixRuns.BASE));
    int[] ints = xxh64 ? new int[0] : new int[] {SEED};
    long[] longs = xxh64 ? new long[] {SEED, 32L} : new long[] {32L};
    try {
      VarkaFusedKernel kernel = loaded.kernel();
      return IntStream.range(0, (int) ((1L << 32) / CHUNK)).parallel().mapToLong(c -> {
        long failures = 0;
        try (Arena arena = Arena.ofConfined()) {
          MemorySegment in = VarkaEmitterFixtures.alloc(arena, CHUNK * 4L);
          MemorySegment out = VarkaEmitterFixtures.alloc(arena, CHUNK * (xxh64 ? 8L : 4L));
          MemorySegment validity = VarkaEmitterFixtures.alloc(arena, CHUNK / 8L);
          for (int i = 0; i < CHUNK; i++) {
            in.set(ValueLayout.JAVA_INT, i * 4L, c * CHUNK + i);
          }
          long scratch = VarkaEmitterTestSupport.scratch(kernel, CHUNK);
          long[] src = {in.address()};
          long[] dst = {out.address()};
          long[] dstValid = {validity.address()};
          int status = xxh64
              ? kernel.run(src, new long[] {0L}, new int[] {0}, dst, dstValid, ints, longs,
                  CHUNK, scratch)
              : kernel.run(src, new long[] {0L}, new int[] {0}, dst, dstValid, ints, CHUNK,
                  scratch);
          if (status != 0) {
            return CHUNK;
          }
          for (int i = 0; i < CHUNK; i++) {
            long got = xxh64 ? out.get(ValueLayout.JAVA_LONG, i * 8L)
                : out.get(ValueLayout.JAVA_INT, i * 4L);
            if (got != spark(xxh64, lane, c * CHUNK + i, false, SEED)) {
              failures++;
            }
          }
        }
        return failures;
      }).sum();
    } finally {
      loaded.loader().release();
    }
  }

  @Test
  @DisplayName("every INT and every FLOAT pattern hashes as Spark's hash and xxhash64 do")
  void every32BitPattern() {
    assumeTrue(Boolean.getBoolean("varka.sweep"), "an opt-in 2^32 sweep: -Dvarka.sweep=true");
    for (LaneType lane : new LaneType[] {LaneType.INT, LaneType.FLOAT}) {
      for (boolean xxh64 : new boolean[] {false, true}) {
        assertEquals(0L, sweep(xxh64, lane), (xxh64 ? "xxhash64" : "hash") + " over " + lane
            + ": patterns whose hash differs from Spark's");
      }
    }
  }
}
