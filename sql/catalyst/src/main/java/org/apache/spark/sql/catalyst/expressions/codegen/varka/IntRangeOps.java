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

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * A range check over an int32 Arrow column: whether every <i>live</i> value lies in a closed
 * interval. The evaluator runs it over an input column the compiler has bounded -
 * first use: a day offset that came from {@code CAST(i AS INTERVAL DAY)}, which Spark's cast
 * throws on past {@link VarkaChrono#INTERVAL_DAY_LIMIT_DAYS} - and declines the batch to the
 * row engine when the answer is no, so the row engine can raise the error the kernel cannot.
 *
 * <p>It lives here rather than beside the engine's kernels for {@link SelectionVectorOps}'s
 * reason: the engine module is a test-scope dependency, and a kernel called from Scala on the
 * batch path has to be on the compile classpath. The shape is the engine's reference shape
 * ({@code DateVectorOps}): raw addresses in, the source validity dereferenced only when
 * {@code 0 < nullCount < length}, a whole-lane-group loop to {@link VectorSpecies#loopBound}
 * and a masked epilogue in its own method. Null lanes are excluded from the check because their
 * data is undefined - a null offset must not condemn a batch - which is why the validity drives
 * the compare masks rather than only the loads. The loop exits at the first group with a lane
 * outside, so the common all-inside batch pays the whole scan and a violating batch pays less.
 */
public final class IntRangeOps {

  private static final VectorSpecies<Integer> SPECIES = IntVector.SPECIES_PREFERRED;

  static {
    // This class runs vector code on the query path without a kernel, so the shared Vector API
    // templates are warmed before its first use (VARKA-389; see VarkaVectorTemplateWarmup).
    VarkaVectorTemplateWarmup.beforeVectorCode();
  }
  private static final ByteOrder ORDER = ByteOrder.LITTLE_ENDIAN;
  private IntRangeOps() {}

  /**
   * Whether every live lane of {@code src[0 .. length)} lies in {@code [lo, hi]}.
   *
   * @param srcData address of the int32 data buffer
   * @param srcValidity address of the validity bitmap, dereferenced only when
   *                    {@code 0 < srcNullCount < length}; a null-free or all-null caller may
   *                    pass {@code 0L}
   * @param srcNullCount how many of the {@code length} rows are null
   * @param length the row count
   * @param lo the inclusive lower bound
   * @param hi the inclusive upper bound
   */
  public static boolean allWithin(long srcData, long srcValidity, int srcNullCount, int length,
      int lo, int hi) {
    if (length == 0 || srcNullCount == length) {
      return true;
    }
    MemorySegment src = VarkaSegments.map(srcData, length * 4L);
    boolean hasNulls = srcNullCount > 0;
    MemorySegment validity = hasNulls
        ? VarkaSegments.map(srcValidity, (length + 7) / 8L) : null;
    IntVector loVec = IntVector.broadcast(SPECIES, lo);
    IntVector hiVec = IntVector.broadcast(SPECIES, hi);

    long loopBound = SPECIES.loopBound(length);
    int lanes = SPECIES.length();
    long i = 0;
    for (; i < loopBound; i += lanes) {
      VectorMask<Integer> mask = hasNulls
          ? VectorMask.fromLong(SPECIES, bitsAt(validity, i, lanes))
          : VectorMask.fromLong(SPECIES, -1L);
      IntVector v = IntVector.fromMemorySegment(SPECIES, src, i * 4L, ORDER, mask);
      // Outside means below lo or above hi on a live lane; the mask on the compares keeps a
      // null lane's undefined data out of the verdict.
      VectorMask<Integer> outside = v.compare(VectorOperators.LT, loVec, mask)
          .or(v.compare(VectorOperators.GT, hiVec, mask));
      if (outside.anyTrue()) {
        return false;
      }
    }
    return i >= length || tailWithin(src, validity, hasNulls, i, length, loVec, hiVec);
  }

  /** {@link #allWithin}'s final partial lane group; its own method per the class doc. */
  private static boolean tailWithin(MemorySegment src, MemorySegment validity, boolean hasNulls,
      long i, int length, IntVector loVec, IntVector hiVec) {
    int rows = (int) (length - i);
    VectorMask<Integer> bounds = SPECIES.indexInRange((int) i, length);
    VectorMask<Integer> mask = hasNulls
        ? VectorMask.fromLong(SPECIES, bitsAt(validity, i, rows)).and(bounds)
        : bounds;
    IntVector v = IntVector.fromMemorySegment(SPECIES, src, i * 4L, ORDER, mask);
    VectorMask<Integer> outside = v.compare(VectorOperators.LT, loVec, mask)
        .or(v.compare(VectorOperators.GT, hiVec, mask));
    return !outside.anyTrue();
  }

  /**
   * The {@code rows} validity bits starting at row {@code row}, lane 0 at bit 0, reading only
   * the bytes those rows span - the same read {@link SelectionVectorOps} keeps privately, for
   * the reason its class doc gives (the layout is Arrow's and fixed, and the engine's copy is
   * not on this classpath).
   */
  private static long bitsAt(MemorySegment bitmap, long row, int rows) {
    long byteOffset = row / 8;
    int shift = (int) (row % 8);
    int spanned = (shift + rows + 7) / 8;
    long bits = 0L;
    for (int b = 0; b < spanned; b++) {
      bits |= (bitmap.get(ValueLayout.JAVA_BYTE, byteOffset + b) & 0xFFL) << (b * 8);
    }
    long low = rows >= 64 ? -1L : (1L << rows) - 1;
    return (bits >>> shift) & low;
  }
}
