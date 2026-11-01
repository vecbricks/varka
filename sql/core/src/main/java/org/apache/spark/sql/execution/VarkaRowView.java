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

import javax.annotation.Nullable;

import scala.collection.immutable.Seq;

import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.DecimalVector;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.execution.columnar.ArrowCachedBatchSerializer;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.types.DecimalType;
import org.apache.spark.sql.vectorized.ArrowColumnVector;
import org.apache.spark.sql.vectorized.ColumnVector;
import org.apache.spark.sql.vectorized.ColumnarArray;
import org.apache.spark.sql.vectorized.ColumnarBatch;
import org.apache.spark.sql.vectorized.ColumnarMap;
import org.apache.spark.unsafe.types.UTF8String;

/**
 * The rows of a batch read from Spark's Arrow cache, as Varka's row-by-row reads see them: the
 * same columns, except that a decimal column of precision 18 or less hands each value back as
 * vanilla Spark's row path over the cache does. An evaluator builds one only when its node reads
 * the Arrow cache ({@code VarkaColumnarRule.readsArrowCache}); over any other columnar input its
 * rows are the batch's own, as vanilla's {@code ColumnarToRowExec} reads them. Every row-by-row
 * read of a Varka node's input batch goes through {@code VarkaEvaluatorBase.inputRows}
 * (VARKA-365).
 *
 * <p>Why it matters: Spark's ANSI and TRY casts of a decimal to {@code INT}, {@code SMALLINT} and
 * {@code TINYINT} follow one of two rules, chosen by the value's backing
 * ({@code Decimal.roundToInt}): a long-backed value truncates its unscaled long and checks it
 * exactly, a {@code BigDecimal}-backed one rounds through a double, and the two part near whole
 * numbers and near the range's ends. Vanilla Spark's operators read the Arrow cache as rows and
 * meet long-backed values; a row of a columnar batch reads through {@code ArrowColumnVector},
 * whose small-decimal accessor builds a {@code BigDecimal}-backed one.
 *
 * <p>A column the view reads takes one of two paths, chosen when the batch is bound:
 *
 * <ul>
 *   <li>The cache's row reader's, when the Arrow field is a 128-bit decimal at the scale of the
 *       column's Spark type ({@link VarkaLaneArrow#decimalFieldMatches}), as every field the
 *       cache writes is. The reader ({@code ArrowColumnReader}) writes the slot's unscaled low
 *       word into an {@code UnsafeRow} with no check, which {@code UnsafeRow.getDecimal} hands
 *       back as {@code Decimal.createUnsafe}; the view builds that value from the same word, at
 *       the offset the reader takes
 *       ({@code ArrowCachedBatchSerializer.compactDecimalUnscaledOffset}).
 *   <li>The accessor's, for a field at another scale or width: {@code ArrowColumnVector} reads
 *       the value, rescaling it, and the view makes that value long-backed in place, as
 *       {@code UnsafeRowWriter.write} and the row would. Varka's kernels decline such a batch to
 *       the row path already.
 * </ul>
 *
 * <p><b>Known limit.</b> Vanilla's cache scan reads with the row reader only when every column
 * it selects is a type the reader reads; with an array, struct, map or another type the reader
 * leaves to it ({@code needsFallback} in
 * {@code ArrowCachedBatchSerializer.convertCachedBatchToInternalRows}), it reads through an
 * {@code UnsafeProjection} over the batch, whose accessor raises
 * {@code NUMERIC_VALUE_OUT_OF_RANGE} on a slot outside the column's precision. The view always
 * takes the row reader's low word. So on such a corrupt slot - one Spark's own cache writer
 * cannot produce - in a batch whose scan also selects a nested type, vanilla raises and Varka
 * answers the low word. For every well-formed value the two readers agree.
 *
 * <p>The view is never handed to a kernel, whose Arrow checks it would fail, nor to another
 * operator. It owns none of the input's vectors, so its close is a no-op, as is each wrapper's.
 * It is reused: {@link #rows} rebinds it, so a batch's rows must be read before the next batch's
 * rows are asked for, as every caller's one-batch-at-a-time pull does. It drops its references
 * to a batch's vectors on the next {@link #rows} and at the evaluator's task completion
 * ({@link #release}), so a read after either fails rather than reaching a vector its owner may
 * have freed. On the row reader's path a decimal read allocates one {@code Decimal}, as
 * {@code UnsafeRow.getDecimal} does, where the accessor allocates a {@code BigDecimal}, a Scala
 * {@code BigDecimal} and a {@code Decimal}. The decimal columns are found once, from the
 * schema; a batch allocates nothing here beyond its own row iterator.
 */
final class VarkaRowView {

  // The byte offset of the unscaled low word in a 16-byte Arrow decimal slot, as the row reader
  // computes it.
  private static final long UNSCALED_OFFSET =
      ArrowCachedBatchSerializer.compactDecimalUnscaledOffset(ByteOrder.nativeOrder());

  private static final String RELEASED = "a Varka row view read after its batch was released";

  // The ordinals of the schema's decimal columns of precision 18 or less, and a wrapper for
  // each; fixed for the evaluator's life. The view is used by one task thread, as the evaluator
  // that owns it is.
  private final int[] ordinals;
  private final LongBackedDecimals[] decimals;
  private ColumnVector[] columns = new ColumnVector[0];
  private ColumnarBatch view = new InertBatch(columns);

  private VarkaRowView(int[] ordinals, DecimalType[] types) {
    this.ordinals = ordinals;
    this.decimals = new LongBackedDecimals[ordinals.length];
    for (int k = 0; k < ordinals.length; k++) {
      decimals[k] = new LongBackedDecimals(types[k]);
    }
  }

  /** A view for batches of {@code schema}, or null when it has no decimal the view reads. */
  static @Nullable VarkaRowView of(Seq<Attribute> schema) {
    int width = schema.length();
    int count = 0;
    for (int i = 0; i < width; i++) {
      if (smallDecimal(schema.apply(i)) != null) {
        count++;
      }
    }
    if (count == 0) {
      return null;
    }
    int[] ordinals = new int[count];
    DecimalType[] types = new DecimalType[count];
    int k = 0;
    for (int i = 0; i < width; i++) {
      @Nullable DecimalType type = smallDecimal(schema.apply(i));
      if (type != null) {
        ordinals[k] = i;
        types[k] = type;
        k++;
      }
    }
    return new VarkaRowView(ordinals, types);
  }

  private static @Nullable DecimalType smallDecimal(Attribute attribute) {
    return attribute.dataType() instanceof DecimalType type
        && type.precision() <= Decimal.MAX_LONG_DIGITS() ? type : null;
  }

  /** The rows of {@code input}, read through this view where a column needs it. */
  Iterator<InternalRow> rows(ColumnarBatch input) {
    release();
    int width = input.numCols();
    boolean viewed = false;
    for (int k = 0; k < ordinals.length; k++) {
      int i = ordinals[k];
      if (i < width && input.column(i) instanceof ArrowColumnVector arrow
          && arrow.getValueVector() instanceof DecimalVector) {
        decimals[k].bind(arrow);
        viewed = true;
      }
    }
    if (!viewed) {
      return input.rowIterator();
    }
    if (columns.length != width) {
      columns = new ColumnVector[width];
      view = new InertBatch(columns);
    }
    for (int i = 0; i < width; i++) {
      columns[i] = input.column(i);
    }
    for (int k = 0; k < ordinals.length; k++) {
      if (decimals[k].bound()) {
        columns[ordinals[k]] = decimals[k];
      }
    }
    view.setNumRows(input.numRows());
    return view.rowIterator();
  }

  /** Drops the view's references to the last batch's vectors. */
  void release() {
    for (int i = 0; i < columns.length; i++) {
      columns[i] = null;
    }
    for (LongBackedDecimals wrapper : decimals) {
      wrapper.unbind();
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

  /**
   * An Arrow small-decimal column read as vanilla's row path reads it (see the class comment),
   * every other read passed to the column it wraps.
   */
  private static final class LongBackedDecimals extends ColumnVector {

    private @Nullable ArrowColumnVector column;
    private @Nullable ArrowBuf data;
    // The scale the row reader's path reads at: the field's, when it matches the column's type,
    // and -1 otherwise, which no read asks for.
    private int readerScale = -1;

    LongBackedDecimals(DecimalType type) {
      super(type);
    }

    void bind(ArrowColumnVector arrow) {
      var vector = (DecimalVector) arrow.getValueVector();
      int scale = ((DecimalType) type).scale();
      column = arrow;
      data = vector.getDataBuffer();
      readerScale = VarkaLaneArrow.decimalFieldMatches(vector, scale) ? scale : -1;
    }

    boolean bound() {
      return column != null;
    }

    void unbind() {
      column = null;
      data = null;
      readerScale = -1;
    }

    private ArrowColumnVector column() {
      ArrowColumnVector c = column;
      if (c == null) {
        throw new IllegalStateException(RELEASED);
      }
      return c;
    }

    @Override
    public @Nullable Decimal getDecimal(int rowId, int precision, int scale) {
      ArrowColumnVector c = column();
      if (c.isNullAt(rowId)) {
        return null;
      }
      ArrowBuf buffer = data;
      if (scale == readerScale && buffer != null) {
        long unscaled = buffer.getLong((long) rowId * DecimalVector.TYPE_WIDTH + UNSCALED_OFFSET);
        return Decimal.createUnsafe(unscaled, precision, scale);
      }
      // What UnsafeRowWriter.write stores for the accessor's value and UnsafeRow hands back.
      Decimal value = c.getDecimal(rowId, precision, scale);
      if (value == null || !value.changePrecision(precision, scale)) {
        return null;
      }
      return value.set(value.toUnscaledLong(), precision, scale);
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
