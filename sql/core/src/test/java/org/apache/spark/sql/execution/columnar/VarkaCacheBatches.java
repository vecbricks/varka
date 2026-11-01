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

package org.apache.spark.sql.execution.columnar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import scala.Option;
import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.TaskContext$;
import org.apache.spark.TaskContextImpl;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.types.DataTypeUtils;
import org.apache.spark.sql.columnar.CachedBatch;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.vectorized.ColumnarBatch;

/**
 * Rows as the Arrow cache serves them, with no session (VARKA-509): the two iterators
 * {@code ArrowCachedBatchSerializer} runs in a task - the one that writes rows into cached Arrow
 * batches and the one that reads them back as columnar batches - driven over a local list. A
 * suite that hands these batches to a Varka evaluator feeds it the vectors the cache would, by
 * construction.
 *
 * <p>The reading iterator closes a batch's vectors when it moves to the next, so {@link #each}
 * hands the batches over one at a time and the consumer must be done with each before it
 * returns. Both iterators name an allocator after the running task and release it when the task
 * completes, so {@code each} runs inside {@link #inTask}, as the evaluators do.
 */
public final class VarkaCacheBatches {

  private VarkaCacheBatches() {}

  /**
   * Runs {@code body} as a task of its own: a {@code TaskContext} on this thread, completed
   * afterwards so that every listener the iterators and the evaluators registered releases what
   * it holds.
   */
  public static <T> T inTask(Supplier<T> body) {
    TaskContextImpl context = TaskContext$.MODULE$.empty();
    TaskContext$.MODULE$.setTaskContext(context);
    try {
      return body.get();
    } finally {
      try {
        context.markTaskCompleted(Option.empty());
      } finally {
        TaskContext$.MODULE$.unset();
      }
    }
  }

  /**
   * {@code rows} with {@code schema}'s columns, batch by batch, as the cache reads them back.
   * The cache's written batches - Arrow's serialized bytes, which nothing writes to once made -
   * are kept for the last few row lists written ({@link #written}), so a fixture that many
   * checks read is written once; each read deserializes fresh vectors, so no batch a consumer
   * is handed is one another consumer was handed. The rows must not change once batched.
   */
  public static void each(List<InternalRow> rows, Seq<Attribute> schema, SQLConf conf,
      Consumer<ColumnarBatch> consumer) {
    StructType sparkSchema = DataTypeUtils.fromAttributes(schema);
    List<CachedBatch> cached = written(rows, schema, sparkSchema, conf);
    var batches = new ArrowCachedBatchToColumnarBatchIterator(
        CollectionConverters.asScala(cached.iterator()),
        sparkSchema,
        sparkSchema,
        CachedColumnIndices$.MODULE$.apply(schema, schema),
        conf.sessionLocalTimeZone(),
        false);
    while (batches.hasNext()) {
      consumer.accept(batches.next());
    }
  }

  /**
   * What the cache's batches of a row list are written under: the list itself, by identity, so
   * that a lookup reads no row, its columns, and every setting the writer reads.
   */
  private record Written(List<InternalRow> rows, Seq<Attribute> schema, long maxRecords,
      long maxBytes, String timeZone, String codec, int level) {
    @Override
    public boolean equals(Object o) {
      return o instanceof Written w && w.rows == rows && w.schema.equals(schema)
          && w.maxRecords == maxRecords && w.maxBytes == maxBytes
          && w.timeZone.equals(timeZone) && w.codec.equals(codec) && w.level == level;
    }

    @Override
    public int hashCode() {
      return Objects.hash(System.identityHashCode(rows), schema, maxRecords, maxBytes,
          timeZone, codec, level);
    }
  }

  /**
   * How many row lists' batches are kept: the harness reads a few fixtures over and over, one
   * test's checks after another's, and a fixture a test makes for itself is read only by that
   * test's checks, so the oldest is dropped.
   */
  private static final int WRITTEN_LIMIT = 8;

  private static final Map<Written, List<CachedBatch>> WRITTEN =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Written, List<CachedBatch>> eldest) {
          return size() > WRITTEN_LIMIT;
        }
      };

  /** The cache's batches of {@code rows}, written by the cache's own iterator, or kept ones. */
  private static List<CachedBatch> written(List<InternalRow> rows, Seq<Attribute> schema,
      StructType sparkSchema, SQLConf conf) {
    var key = new Written(rows, schema, conf.arrowMaxRecordsPerBatch(),
        conf.arrowMaxBytesPerBatch(), conf.sessionLocalTimeZone(), conf.arrowCompressionCodec(),
        conf.arrowZstdCompressionLevel());
    synchronized (WRITTEN) {
      List<CachedBatch> kept = WRITTEN.get(key);
      if (kept != null) {
        return kept;
      }
    }
    var writer = new InternalRowToArrowCachedBatchIterator(
        CollectionConverters.asScala(rows.iterator()),
        schema,
        sparkSchema,
        key.maxRecords(),
        key.maxBytes(),
        key.timeZone(),
        key.codec(),
        key.level());
    var out = new ArrayList<CachedBatch>();
    // The writer closes its vectors and allocator once it has no row left.
    while (writer.hasNext()) {
      out.add(writer.next());
    }
    List<CachedBatch> batches = List.copyOf(out);
    synchronized (WRITTEN) {
      WRITTEN.put(key, batches);
    }
    return batches;
  }
}
