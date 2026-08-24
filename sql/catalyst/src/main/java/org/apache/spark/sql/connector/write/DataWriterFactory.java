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

import java.io.Serializable;
import java.util.Map;

import org.apache.spark.SparkUnsupportedOperationException;
import org.apache.spark.TaskContext;
import org.apache.spark.annotation.Evolving;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.vectorized.ColumnarBatch;

/**
 * A factory of {@link DataWriter} returned by
 * {@link BatchWrite#createBatchWriterFactory(PhysicalWriteInfo)}, which is responsible for
 * creating and initializing the actual data writer at executor side.
 * <p>
 * Note that, the writer factory will be serialized and sent to executors, then the data writer
 * will be created on executors and do the actual writing. So this interface must be
 * serializable and {@link DataWriter} doesn't need to be.
 *
 * @since 3.0.0
 */
@Evolving
public interface DataWriterFactory extends Serializable {

  /**
   * Returns a data writer to do the actual writing work. Note that, Spark will reuse the same data
   * object instance when sending data to the data writer, for better performance. Data writers
   * are responsible for defensive copies if necessary, e.g. copy the data before buffer it in a
   * list.
   * <p>
   * If this method fails (by throwing an exception), the corresponding Spark write task would fail
   * and get retried until hitting the maximum retry times.
   *
   * @param partitionId A unique id of the RDD partition that the returned writer will process.
   *                    Usually Spark processes many RDD partitions at the same time,
   *                    implementations should use the partition id to distinguish writers for
   *                    different partitions.
   * @param taskId The task id returned by {@link TaskContext#taskAttemptId()}. Spark may run
   *               multiple tasks for the same partition (due to speculation or task failures,
   *               for example).
   */
  DataWriter<InternalRow> createWriter(int partitionId, long taskId);

  /**
   * Returns a data writer that accepts {@link ColumnarBatch}es rather than rows.
   * <p>
   * Spark calls this instead of {@link #createWriter(int, long)} when the {@link Write} that
   * produced this factory returns true from {@link Write#supportsColumnarWrite()} and the plan
   * feeding the write already produces columnar batches. A factory whose write declares
   * columnar support must override this; the default throws.
   * <p>
   * Unlike a row writer, a columnar writer does not own the batch it is handed. Spark reuses and
   * releases batches as the query runs, so the writer must consume a batch within the
   * {@link DataWriter#write(Object)} call and must not retain it, or any of its
   * {@link org.apache.spark.sql.vectorized.ColumnVector}s, afterwards. Copy out what has to
   * outlive the call.
   *
   * @param partitionId A unique id of the RDD partition that the returned writer will process.
   * @param taskId The task id returned by {@link TaskContext#taskAttemptId()}.
   *
   * @since 5.0.0
   */
  default DataWriter<ColumnarBatch> createColumnarWriter(int partitionId, long taskId) {
    throw new SparkUnsupportedOperationException(
      "DATA_SOURCE_COLUMNAR_WRITER_NOT_SUPPORTED", Map.of("class", getClass().getName()));
  }
}
