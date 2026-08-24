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

package org.apache.spark.sql.connector.write;

import java.util.Map;

import org.apache.spark.SparkUnsupportedOperationException;
import org.apache.spark.annotation.Evolving;
import org.apache.spark.sql.connector.catalog.Table;
import org.apache.spark.sql.connector.catalog.TableCapability;
import org.apache.spark.sql.connector.metric.CustomMetric;
import org.apache.spark.sql.connector.metric.CustomTaskMetric;
import org.apache.spark.sql.connector.read.Scan;
import org.apache.spark.sql.connector.write.streaming.StreamingWrite;
import org.apache.spark.sql.vectorized.ColumnarBatch;

/**
 * A logical representation of a data source write.
 * <p>
 * This logical representation is shared between batch and streaming write. Data sources must
 * implement the corresponding methods in this interface to match what the table promises
 * to support. For example, {@link #toBatch()} must be implemented if the {@link Table} that
 * creates this {@link Write} returns {@link TableCapability#BATCH_WRITE} support in its
 * {@link Table#capabilities()}.
 *
 * @since 3.2.0
 */
@Evolving
public interface Write {

  /**
   * Returns the description associated with this write.
   */
  default String description() {
    return this.getClass().toString();
  }

  /**
   * Returns a {@link BatchWrite} to write data to batch source. By default this method throws
   * exception, data sources must overwrite this method to provide an implementation, if the
   * {@link Table} that creates this write returns {@link TableCapability#BATCH_WRITE} support in
   * its {@link Table#capabilities()}.
   */
  default BatchWrite toBatch() {
    throw new SparkUnsupportedOperationException(
      "DATA_SOURCE_BATCH_WRITE_NOT_SUPPORTED", Map.of("description", description()));
  }

  /**
   * Returns a {@link StreamingWrite} to write data to streaming source. By default this method
   * throws exception, data sources must overwrite this method to provide an implementation, if the
   * {@link Table} that creates this write returns {@link TableCapability#STREAMING_WRITE} support
   * in its {@link Table#capabilities()}.
   */
  default StreamingWrite toStreaming() {
    throw new SparkUnsupportedOperationException(
      "DATA_SOURCE_STREAMING_WRITE_NOT_SUPPORTED", Map.of("description", description()));
  }

  /**
   * Returns whether this write can accept {@link ColumnarBatch}es instead of rows.
   * <p>
   * This is the write-side counterpart of {@link Scan#columnarSupportMode()}, and like it, it is
   * a property of one write rather than of the {@link Table} - the same table may produce a
   * columnar write for one schema and a row write for another. There is therefore no
   * {@link TableCapability} for it: {@link TableCapability#BATCH_WRITE} remains the only
   * table-level declaration a batch write needs.
   * <p>
   * The columnar path is an opt-in fast path, not a requirement. Spark takes it only when the
   * plan feeding the write already produces columnar batches; it never inserts a transition to
   * manufacture them. A write that returns true must therefore still be able to write rows,
   * because a row-producing plan falls back to {@link DataWriterFactory#createWriter(int, long)}.
   * <p>
   * A write that returns true must also override
   * {@link DataWriterFactory#createColumnarWriter(int, long)} on the factory its
   * {@link BatchWrite} produces, or the write fails at task start.
   * <p>
   * The default is false, which is the row-only behaviour every existing write has.
   *
   * @since 5.0.0
   */
  default boolean supportsColumnarWrite() {
    return false;
  }

  /**
   * Returns an array of supported custom metrics with name and description.
   * By default it returns empty array.
   */
  default CustomMetric[] supportedCustomMetrics() {
    return new CustomMetric[]{};
  }

  /**
   * Returns an array of custom metrics which are collected with values at the driver side only.
   * Note that these metrics must be included in the supported custom metrics reported by
   * `supportedCustomMetrics`.
   */
  default CustomTaskMetric[] reportDriverMetrics() {
    return new CustomTaskMetric[]{};
  }

}
