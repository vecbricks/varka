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

package org.apache.spark.sql.varka.memory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Logger;

import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.ValueVector;

/**
 * Maps the buffers of an Arrow {@link ValueVector} onto Panama {@link MemorySegment}s
 * (zero-copy, off-heap). This is the Varka "morsel": the segment pair a generated
 * SIMD kernel reads from and writes to.
 *
 * <p>Only {@link DateDayVector} is supported (Spark {@code DateType}, int32 days since
 * epoch), which covers the Date/Time MVP expressions. The data buffer is a flat int32
 * array; the validity buffer is bit-packed (1 bit per row, bit set means valid).
 *
 * <p>Segments are sized to the underlying {@link ArrowBuf} capacity rather than the
 * nominal {@code rowCount * 4} / {@code (rowCount + 7) / 8}, because the capacity is what
 * the buffer actually owns. Consumers must still bound their reads to {@code rowCount}.
 *
 * <p>That extra capacity is not a licence to over-read, and nothing depends on it: the
 * kernels re-wrap the raw addresses at the nominal sizes and address the validity bitmap
 * one lane group at a time, so they stay inside {@code (rowCount + 7) / 8} bytes even for
 * a batch whose bitmap is shorter than a single 64-bit word.
 */
public final class VarkaMorsel {

  private static final Logger log = Logger.getLogger(VarkaMorsel.class.getName());

  private static final long CACHE_LINE = 64L;

  private VarkaMorsel() {}

  /**
   * An int32 date column (days since epoch) and its bit-packed validity, mapped to
   * {@link MemorySegment}s.
   *
   * @param data     int32 days since epoch; {@code byteSize} == data buffer capacity
   *                 (&gt;= {@code rowCount * 4}).
   * @param validity bit-packed validity, 1 bit per row; {@code byteSize} == validity
   *                 buffer capacity (&gt;= {@code (rowCount + 7) / 8}); {@code null}
   *                 when the column is all-null.
   * @param rowCount number of rows in the batch.
   * @param nullCount number of null rows.
   */
  public record DateMorsel(
      MemorySegment data,
      MemorySegment validity,
      int rowCount,
      long nullCount) {

    public boolean allNull() {
      return nullCount == rowCount;
    }

    public boolean noNulls() {
      return nullCount == 0;
    }
  }

  /**
   * Maps a {@link DateDayVector} to a {@link DateMorsel}.
   *
   * @param vector the Arrow vector; must be a {@link DateDayVector}.
   * @param rowCount the number of rows to process.
   * @return the mapped morsel.
   * @throws IllegalArgumentException if {@code vector} is not a {@link DateDayVector}, if
   *         {@code rowCount} is negative, or if {@code rowCount} exceeds the vector's
   *         {@code getValueCount()}.
   */
  public static DateMorsel extractDate(ValueVector vector, int rowCount) {
    if (!(vector instanceof DateDayVector dateDayVector)) {
      throw new IllegalArgumentException(
          "Expected a DateDayVector but got " + vector.getClass().getName());
    }
    if (rowCount < 0) {
      throw new IllegalArgumentException("rowCount must be non-negative but was " + rowCount);
    }
    if (rowCount > dateDayVector.getValueCount()) {
      throw new IllegalArgumentException("rowCount " + rowCount + " exceeds vector value count "
          + dateDayVector.getValueCount());
    }
    long nullCount = dateDayVector.getNullCount();
    MemorySegment data = ofAddress(dateDayVector.getDataBuffer());
    MemorySegment validity = null;
    if (nullCount != rowCount) {
      validity = ofAddress(dateDayVector.getValidityBuffer());
    }
    return new DateMorsel(data, validity, rowCount, nullCount);
  }

  /**
   * Diagnostic: logs each buffer address and its cache-line alignment. Alignment is
   * informational only; the Vector API performs unaligned accesses safely, so nothing is
   * asserted.
   */
  public static void reportAlignment(DateMorsel m) {
    long dataAddr = m.data().address();
    log.info("data segment: address=0x" + Long.toHexString(dataAddr)
        + " bytes=" + m.data().byteSize()
        + " cacheLineAligned=" + (dataAddr % CACHE_LINE == 0));
    if (m.validity() != null) {
      long validityAddr = m.validity().address();
      log.info("validity segment: address=0x" + Long.toHexString(validityAddr)
          + " bytes=" + m.validity().byteSize()
          + " cacheLineAligned=" + (validityAddr % CACHE_LINE == 0));
    } else {
      log.info("validity segment: null (all-null column)");
    }
    if (m.noNulls()) {
      log.info("column has no nulls: all validity bits set");
    }
  }

  /** Maps an {@link ArrowBuf} to a zero-copy {@link MemorySegment} sized to its capacity. */
  private static MemorySegment ofAddress(ArrowBuf buf) {
    return MemorySegment.ofAddress(buf.memoryAddress()).reinterpret(buf.capacity());
  }

  /**
   * Convenience for Task 2 and tests: whether row {@code i} is null in a bit-packed
   * validity segment (bit set means valid).
   */
  public static boolean isNull(MemorySegment validity, int i) {
    return (validity.get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) == 0;
  }
}
