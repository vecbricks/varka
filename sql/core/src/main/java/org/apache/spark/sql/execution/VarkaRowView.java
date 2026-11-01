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

import java.nio.ByteOrder;

import javax.annotation.Nullable;

import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.DecimalVector;

import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.types.DecimalType;
import org.apache.spark.sql.vectorized.ArrowColumnVector;
import org.apache.spark.sql.vectorized.ColumnVector;
import org.apache.spark.sql.vectorized.ColumnarArray;
import org.apache.spark.sql.vectorized.ColumnarBatch;
import org.apache.spark.sql.vectorized.ColumnarMap;
import org.apache.spark.unsafe.types.UTF8String;

/**
 * The input batch as Varka's row-by-row evaluations read it: the same columns, except that an
 * Arrow decimal column of precision 18 or less hands its values long-backed, as an
 * {@code UnsafeRow} does.
 *
 * <p>Spark's ANSI and TRY casts of a decimal to {@code INT}, {@code SMALLINT} and {@code TINYINT}
 * follow one of two rules, chosen by the value's backing ({@code Decimal.roundToInt}): a
 * long-backed value truncates its unscaled long and checks it exactly, a {@code BigDecimal}-backed
 * one rounds through a double, and the two part near whole numbers and near the range's ends.
 * Vanilla Spark's operators read the Arrow cache as rows - its row reader writes the unscaled long
 * into an {@code UnsafeRow}, which reads it back long-backed - so they follow the exact rule. A row
 * of a columnar batch reads through the column vector instead, and {@code ArrowColumnVector}'s
 * small-decimal accessor builds a {@code BigDecimal}-backed value. Read through this view, a
 * residual expression and a fallback meet the value vanilla Spark's operators meet, and answer as
 * they do (VARKA-365).
 *
 * <p>A slot whose high word is not its low word's sign extension - a value outside its declared
 * precision - is read through the accessor, whole, as Varka's own decimal load declines it;
 * vanilla Spark's row reader would truncate it to its low word. A vector whose scale is not the
 * one asked for is read through the accessor too, which rescales.
 *
 * <p>The view is for {@code rowIterator()} only: never for a kernel, whose Arrow checks it would
 * fail, and never handed to another operator. It owns none of the input's vectors, so its close is
 * a no-op, as is each wrapper's. It is reused, {@link #of} rebinding it to the next batch, so a
 * batch's rows must be consumed before the next batch is viewed, as every caller's
 * one-batch-at-a-time pull does. It allocates nothing per batch once it has seen the first; a
 * decimal read allocates one {@code Decimal}, as {@code UnsafeRow.getDecimal} does, where the
 * accessor allocates a {@code BigDecimal}, a Scala {@code BigDecimal} and a {@code Decimal}. A
 * batch with no such column is returned as it is.
 */
final class VarkaRowView {

  // Built on the first batch that needs them; the view is used by one task thread, as the
  // evaluator that owns it is.
  private @Nullable ColumnVector[] columns;
  private @Nullable LongBackedDecimals[] decimals;
  private @Nullable ColumnarBatch view;

  /** The batch to read {@code input}'s rows from: this view over it, or {@code input} itself. */
  ColumnarBatch of(ColumnarBatch input) {
    int width = input.numCols();
    boolean needed = false;
    for (int i = 0; i < width && !needed; i++) {
      needed = isSmallArrowDecimal(input.column(i));
    }
    if (!needed) {
      return input;
    }
    ColumnVector[] viewColumns = columns;
    LongBackedDecimals[] wrappers = decimals;
    ColumnarBatch batch = view;
    if (viewColumns == null || wrappers == null || batch == null || viewColumns.length != width) {
      viewColumns = new ColumnVector[width];
      wrappers = new LongBackedDecimals[width];
      batch = new InertBatch(viewColumns);
      columns = viewColumns;
      decimals = wrappers;
      view = batch;
    }
    for (int i = 0; i < width; i++) {
      ColumnVector column = input.column(i);
      if (isSmallArrowDecimal(column)) {
        var arrow = (ArrowColumnVector) column;
        LongBackedDecimals wrapper = wrappers[i];
        if (wrapper == null || !wrapper.dataType().equals(column.dataType())) {
          wrapper = new LongBackedDecimals(arrow);
          wrappers[i] = wrapper;
        } else {
          wrapper.bind(arrow);
        }
        viewColumns[i] = wrapper;
      } else {
        viewColumns[i] = column;
      }
    }
    batch.setNumRows(input.numRows());
    return batch;
  }

  /** A batch over vectors it does not own: closing it leaves them to the input batch's owner. */
  private static final class InertBatch extends ColumnarBatch {

    InertBatch(ColumnVector[] columns) {
      super(columns);
    }

    @Override
    public void close() {
    }

    @Override
    public void closeIfFreeable() {
    }
  }

  private static boolean isSmallArrowDecimal(ColumnVector column) {
    return column instanceof ArrowColumnVector arrow
        && column.dataType() instanceof DecimalType type
        && type.precision() <= Decimal.MAX_LONG_DIGITS()
        && arrow.getValueVector() instanceof DecimalVector;
  }

  /**
   * An Arrow small-decimal column read long-backed, every other read passed to the column it
   * wraps. Arrow keeps a Decimal128 slot in the platform's byte order, as
   * {@code ArrowColumnVector} and the cache's row reader read it.
   */
  private static final class LongBackedDecimals extends ColumnVector {

    private static final int LOW_WORD =
        ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? 0 : Long.BYTES;
    private static final int HIGH_WORD = Long.BYTES - LOW_WORD;

    private ArrowColumnVector column;
    private ArrowBuf data;
    private int vectorScale;

    LongBackedDecimals(ArrowColumnVector arrow) {
      super(arrow.dataType());
      DecimalVector vector = (DecimalVector) arrow.getValueVector();
      column = arrow;
      data = vector.getDataBuffer();
      vectorScale = vector.getScale();
    }

    void bind(ArrowColumnVector arrow) {
      DecimalVector vector = (DecimalVector) arrow.getValueVector();
      column = arrow;
      data = vector.getDataBuffer();
      vectorScale = vector.getScale();
    }

    @Override
    public @Nullable Decimal getDecimal(int rowId, int precision, int scale) {
      if (column.isNullAt(rowId)) {
        return null;
      }
      long offset = (long) rowId * DecimalVector.TYPE_WIDTH;
      long unscaled = data.getLong(offset + LOW_WORD);
      if (scale != vectorScale || data.getLong(offset + HIGH_WORD) != (unscaled >> 63)) {
        return column.getDecimal(rowId, precision, scale);
      }
      return Decimal.createUnsafe(unscaled, precision, scale);
    }

    @Override
    public void close() {
    }

    @Override
    public void closeIfFreeable() {
    }

    @Override
    public boolean hasNull() {
      return column.hasNull();
    }

    @Override
    public int numNulls() {
      return column.numNulls();
    }

    @Override
    public boolean isNullAt(int rowId) {
      return column.isNullAt(rowId);
    }

    @Override
    public boolean getBoolean(int rowId) {
      return column.getBoolean(rowId);
    }

    @Override
    public byte getByte(int rowId) {
      return column.getByte(rowId);
    }

    @Override
    public short getShort(int rowId) {
      return column.getShort(rowId);
    }

    @Override
    public int getInt(int rowId) {
      return column.getInt(rowId);
    }

    @Override
    public long getLong(int rowId) {
      return column.getLong(rowId);
    }

    @Override
    public float getFloat(int rowId) {
      return column.getFloat(rowId);
    }

    @Override
    public double getDouble(int rowId) {
      return column.getDouble(rowId);
    }

    @Override
    public ColumnarArray getArray(int rowId) {
      return column.getArray(rowId);
    }

    @Override
    public ColumnarMap getMap(int ordinal) {
      return column.getMap(ordinal);
    }

    @Override
    public UTF8String getUTF8String(int rowId) {
      return column.getUTF8String(rowId);
    }

    @Override
    public byte[] getBinary(int rowId) {
      return column.getBinary(rowId);
    }

    @Override
    public ColumnVector getChild(int ordinal) {
      return column.getChild(ordinal);
    }
  }
}
