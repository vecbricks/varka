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
import java.util.Iterator;
import java.util.NoSuchElementException;

import javax.annotation.Nullable;

import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.DecimalVector;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.execution.columnar.ArrowCachedBatchSerializer;
import org.apache.spark.sql.types.ArrayType;
import org.apache.spark.sql.types.CalendarIntervalType;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.types.DecimalType;
import org.apache.spark.sql.types.GeographyType;
import org.apache.spark.sql.types.GeometryType;
import org.apache.spark.sql.types.MapType;
import org.apache.spark.sql.types.NullType;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.types.TimestampLTZNanosType;
import org.apache.spark.sql.types.TimestampNTZNanosType;
import org.apache.spark.sql.types.UserDefinedType;
import org.apache.spark.sql.types.VariantType;
import org.apache.spark.sql.vectorized.ArrowColumnVector;
import org.apache.spark.sql.vectorized.ColumnVector;
import org.apache.spark.sql.vectorized.ColumnarArray;
import org.apache.spark.sql.vectorized.ColumnarBatch;
import org.apache.spark.sql.vectorized.ColumnarMap;
import org.apache.spark.unsafe.types.UTF8String;

/**
 * The rows of an input batch as Varka's row-by-row reads see them: the same columns, except that
 * an Arrow decimal column of precision 18 or less hands each value back as vanilla Spark's row
 * path over the Arrow cache does. Every row-by-row read of a Varka node's input batch goes
 * through {@link #rows}, by way of {@code VarkaEvaluatorBase.inputRows} (VARKA-365).
 *
 * <p>Why it matters: Spark's ANSI and TRY casts of a decimal to {@code INT}, {@code SMALLINT} and
 * {@code TINYINT} follow one of two rules, chosen by the value's backing
 * ({@code Decimal.roundToInt}): a long-backed value truncates its unscaled long and checks it
 * exactly, a {@code BigDecimal}-backed one rounds through a double, and the two part near whole
 * numbers and near the range's ends. Vanilla Spark's operators read the Arrow cache as rows and
 * meet long-backed values; a row of a columnar batch reads through {@code ArrowColumnVector},
 * whose small-decimal accessor builds a {@code BigDecimal}-backed one.
 *
 * <p>Vanilla's row path is one of two readers, chosen by the columns the scan selects
 * ({@code ArrowCachedBatchSerializer.convertCachedBatchToInternalRows}), and the view follows
 * the one vanilla would take for the batch's columns:
 *
 * <ul>
 *   <li>The cache's row reader ({@code ArrowColumnReader}), when every column is a type it
 *       reads. It writes the slot's unscaled low word into an {@code UnsafeRow} with no check,
 *       which {@code UnsafeRow.getDecimal} hands back as {@code Decimal.createUnsafe}; the view
 *       builds that value from the same word, at the offset the row reader takes
 *       ({@code ArrowCachedBatchSerializer.compactDecimalUnscaledOffset}). So a slot whose high
 *       word is not its low word's sign extension reads as its low word, and a low word with
 *       more digits than the precision reads as it is, as vanilla reads them.
 *   <li>An {@code UnsafeProjection} over the batch, when a column is one of the types the row
 *       reader leaves to it ({@link #rowReaderReads}). The projection reads through the accessor,
 *       which raises {@code NUMERIC_VALUE_OUT_OF_RANGE} on a slot outside the precision, and
 *       writes the unscaled long of the value it got; the view does the same.
 * </ul>
 *
 * <p>The view is never handed to a kernel, whose Arrow checks it would fail, nor to another
 * operator. It owns none of the input's vectors, so its close is a no-op, as is each wrapper's.
 * It is reused: {@link #rows} rebinds it, so a batch's rows must be read before the next batch's
 * rows are asked for, as every caller's one-batch-at-a-time pull does, and the iterator it
 * returns is the same object each time. When that iterator reports its rows exhausted the view
 * drops its references to the batch's vectors, so a read after it fails rather than reaching a
 * vector its owner may have freed; a batch whose rows are abandoned part way keeps them until the
 * next {@link #rows}. On the row reader's path a decimal read allocates one {@code Decimal}, as
 * {@code UnsafeRow.getDecimal} does, where the accessor allocates a {@code BigDecimal}, a Scala
 * {@code BigDecimal} and a {@code Decimal}; past its first batch the view allocates nothing per
 * batch beyond the batch's own row iterator. A batch with no such column is read as it is.
 */
final class VarkaRowView {

  // The byte offset of the unscaled low word in a 16-byte Arrow decimal slot, as the row reader
  // computes it.
  private static final long UNSCALED_OFFSET =
      ArrowCachedBatchSerializer.compactDecimalUnscaledOffset(ByteOrder.nativeOrder());

  // Sized on the first batch that needs them; the view is used by one task thread, as the
  // evaluator that owns it is.
  private ColumnVector[] columns = new ColumnVector[0];
  private LongBackedDecimals[] decimals = new LongBackedDecimals[0];
  private ColumnarBatch view = new InertBatch(columns);
  // Whether vanilla would read the bound batch with the cache's row reader (the class comment).
  private boolean rowReader;
  private final ViewRows viewRows = new ViewRows();

  /** The rows of {@code input}, read through this view where a column needs it. */
  Iterator<InternalRow> rows(ColumnarBatch input) {
    @Nullable ColumnarBatch batch = bind(input);
    if (batch == null) {
      return input.rowIterator();
    }
    viewRows.delegate = batch.rowIterator();
    return viewRows;
  }

  /** This view bound to {@code input}, or null when no column of it needs the view. */
  private @Nullable ColumnarBatch bind(ColumnarBatch input) {
    int width = input.numCols();
    boolean viewed = false;
    boolean readerReadsAll = true;
    for (int i = 0; i < width; i++) {
      ColumnVector column = input.column(i);
      readerReadsAll &= rowReaderReads(column.dataType());
      ArrowColumnVector arrow = smallArrowDecimal(column);
      if (arrow != null && !viewed) {
        viewed = true;
        ensureWidth(width);
        for (int j = 0; j < i; j++) {
          columns[j] = input.column(j);
        }
      }
      if (!viewed) {
        continue;
      }
      if (arrow == null) {
        columns[i] = column;
        continue;
      }
      @Nullable LongBackedDecimals wrapper = decimals[i];
      if (wrapper == null || !wrapper.dataType().equals(column.dataType())) {
        wrapper = new LongBackedDecimals(arrow);
        decimals[i] = wrapper;
      } else {
        wrapper.bind(arrow);
      }
      columns[i] = wrapper;
    }
    if (!viewed) {
      return null;
    }
    rowReader = readerReadsAll;
    view.setNumRows(input.numRows());
    return view;
  }

  /** Sizes the view's arrays and batch for {@code width} columns. */
  private void ensureWidth(int width) {
    if (columns.length != width) {
      columns = new ColumnVector[width];
      decimals = new LongBackedDecimals[width];
      view = new InertBatch(columns);
    }
  }

  /** Drops the view's references to the last batch's vectors. */
  private void release() {
    for (int i = 0; i < columns.length; i++) {
      columns[i] = null;
      @Nullable LongBackedDecimals wrapper = decimals[i];
      if (wrapper != null) {
        wrapper.unbind();
      }
    }
  }

  /** The view's rows, releasing the batch once they are exhausted. */
  private final class ViewRows implements Iterator<InternalRow> {

    private @Nullable Iterator<InternalRow> delegate;

    @Override
    public boolean hasNext() {
      Iterator<InternalRow> rows = delegate;
      if (rows == null) {
        return false;
      }
      if (rows.hasNext()) {
        return true;
      }
      delegate = null;
      release();
      return false;
    }

    @Override
    public InternalRow next() {
      Iterator<InternalRow> rows = delegate;
      if (rows == null) {
        throw new NoSuchElementException("the view's rows are exhausted");
      }
      return rows.next();
    }
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

  /** {@code column} as an Arrow small-decimal column, or null when it is not one. */
  private static @Nullable ArrowColumnVector smallArrowDecimal(ColumnVector column) {
    if (column instanceof ArrowColumnVector arrow
        && column.dataType() instanceof DecimalType type
        && type.precision() <= Decimal.MAX_LONG_DIGITS()
        && arrow.getValueVector() instanceof DecimalVector) {
      return arrow;
    }
    return null;
  }

  /**
   * Whether the cache's row reader reads a column of this type: the complement of the types
   * {@code ArrowCachedBatchSerializer.convertCachedBatchToInternalRows} sends to its
   * {@code UnsafeProjection} instead ({@code needsFallback} there), which this list follows.
   */
  private static boolean rowReaderReads(DataType type) {
    return !(type instanceof ArrayType
        || type instanceof StructType
        || type instanceof MapType
        || type instanceof CalendarIntervalType
        || type instanceof VariantType
        || type instanceof NullType
        || type instanceof UserDefinedType<?>
        || type instanceof GeometryType
        || type instanceof GeographyType
        || type instanceof TimestampNTZNanosType
        || type instanceof TimestampLTZNanosType);
  }

  /**
   * An Arrow small-decimal column read as vanilla's row path reads it (see the class comment),
   * every other read passed to the column it wraps.
   */
  private final class LongBackedDecimals extends ColumnVector {

    private @Nullable ArrowColumnVector column;
    private @Nullable ArrowBuf data;

    LongBackedDecimals(ArrowColumnVector arrow) {
      super(arrow.dataType());
      bind(arrow);
    }

    void bind(ArrowColumnVector arrow) {
      column = arrow;
      data = ((DecimalVector) arrow.getValueVector()).getDataBuffer();
    }

    void unbind() {
      column = null;
      data = null;
    }

    private ArrowColumnVector column() {
      ArrowColumnVector c = column;
      if (c == null) {
        throw new IllegalStateException("a Varka row view read after its batch's rows ran out");
      }
      return c;
    }

    @Override
    public @Nullable Decimal getDecimal(int rowId, int precision, int scale) {
      ArrowColumnVector c = column();
      if (c.isNullAt(rowId)) {
        return null;
      }
      if (rowReader) {
        ArrowBuf buffer = data;
        if (buffer == null) {
          throw new IllegalStateException("a Varka row view read after its batch's rows ran out");
        }
        long unscaled = buffer.getLong((long) rowId * DecimalVector.TYPE_WIDTH + UNSCALED_OFFSET);
        return Decimal.createUnsafe(unscaled, precision, scale);
      }
      // What UnsafeProjection writes (UnsafeRowWriter.write) and UnsafeRow hands back.
      Decimal value = c.getDecimal(rowId, precision, scale);
      if (value == null || !value.changePrecision(precision, scale)) {
        return null;
      }
      return Decimal.createUnsafe(value.toUnscaledLong(), precision, scale);
    }

    @Override
    public void close() {
    }

    @Override
    public void closeIfFreeable() {
    }

    @Override
    public boolean hasNull() {
      return column().hasNull();
    }

    @Override
    public int numNulls() {
      return column().numNulls();
    }

    @Override
    public boolean isNullAt(int rowId) {
      return column().isNullAt(rowId);
    }

    @Override
    public boolean getBoolean(int rowId) {
      return column().getBoolean(rowId);
    }

    @Override
    public byte getByte(int rowId) {
      return column().getByte(rowId);
    }

    @Override
    public short getShort(int rowId) {
      return column().getShort(rowId);
    }

    @Override
    public int getInt(int rowId) {
      return column().getInt(rowId);
    }

    @Override
    public long getLong(int rowId) {
      return column().getLong(rowId);
    }

    @Override
    public float getFloat(int rowId) {
      return column().getFloat(rowId);
    }

    @Override
    public double getDouble(int rowId) {
      return column().getDouble(rowId);
    }

    @Override
    public ColumnarArray getArray(int rowId) {
      return column().getArray(rowId);
    }

    @Override
    public ColumnarMap getMap(int ordinal) {
      return column().getMap(ordinal);
    }

    @Override
    public UTF8String getUTF8String(int rowId) {
      return column().getUTF8String(rowId);
    }

    @Override
    public byte[] getBinary(int rowId) {
      return column().getBinary(rowId);
    }

    @Override
    public ColumnVector getChild(int ordinal) {
      return column().getChild(ordinal);
    }
  }
}
