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

package org.apache.spark.sql.execution.columnar

import org.apache.arrow.vector.VectorSchemaRoot

import org.apache.spark.{TaskContext}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.execution.arrow.{ArrowCompressionUtils, ArrowWriter}
import org.apache.spark.sql.types._
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.util.Utils

/**
 * Iterator that converts InternalRow to ArrowCachedBatch.
 */
private class InternalRowToArrowCachedBatchIterator(
    rowIter: Iterator[InternalRow],
    schema: Seq[Attribute],
    sparkSchema: StructType,
    maxRecordsPerBatch: Long,
    maxBytesPerBatch: Long,
    timeZoneId: String,
    compressionCodecName: String,
    compressionLevel: Int) extends Iterator[ArrowCachedBatch] {

  private val compressionCodec = ArrowCompressionUtils.createCompressionCodec(
    compressionCodecName,
    compressionLevel)

  private val allocator = ArrowUtils.rootAllocator.newChildAllocator(
    s"InternalRowToArrowCachedBatchIterator-${TaskContext.get().taskAttemptId()}",
    0,
    Long.MaxValue)

  private val arrowSchema = ArrowUtils.toArrowSchema(
    sparkSchema, timeZoneId, false, false, losslessInternalTypes = true)
  private val root = VectorSchemaRoot.create(arrowSchema, allocator)
  private val arrowWriter = ArrowWriter.create(root)
  private val unloader = ArrowCompressionUtils.createVectorUnloader(root, compressionCodec)

  // Create statistics collectors for each column
  private val statsCollectors: Array[ColumnStats] = schema.map { attr =>
    ArrowCachedBatchSerializer.createColumnStats(attr.dataType)
  }.toArray

  // Register cleanup
  Option(TaskContext.get()).foreach { tc =>
    tc.addTaskCompletionListener[Unit] { _ =>
      close()
    }
  }

  override def hasNext: Boolean = rowIter.hasNext || {
    close()
    false
  }

  override def next(): ArrowCachedBatch = {
    var rowCount = 0

    // Reset statistics collectors for new batch
    var idx = 0
    while (idx < statsCollectors.length) {
      statsCollectors(idx) = ArrowCachedBatchSerializer.createColumnStats(schema(idx).dataType)
      idx += 1
    }

    Utils.tryWithSafeFinally {
      // Write rows to Arrow vectors and collect statistics incrementally, stopping when either the
      // record-count or byte limit is reached (whichever is hit first), so wide rows cannot form
      // multi-gigabyte batches that exhaust memory or overflow Arrow's 32-bit variable-width
      // offsets. A nonpositive limit means that limit is unlimited; the `<= 0` guards also keep the
      // loop from emitting empty batches forever. At least one row is always written so a single
      // oversized row still makes progress. The byte limit is measured from the actual bytes
      // already written to the Arrow vectors (arrowWriter.sizeInBytes), which is accurate for every
      // row type -- a row-size estimate would undercount large values in a GenericInternalRow (e.g.
      // a multi-megabyte string) and let the batch grow past the limit.
      def recordLimitReached: Boolean = maxRecordsPerBatch > 0 && rowCount >= maxRecordsPerBatch
      def byteLimitReached: Boolean =
        maxBytesPerBatch > 0 && arrowWriter.sizeInBytes() >= maxBytesPerBatch
      while (rowIter.hasNext && (rowCount == 0 || (!recordLimitReached && !byteLimitReached))) {
        val row = rowIter.next()
        arrowWriter.write(row)

        // Collect statistics for this row
        var i = 0
        while (i < statsCollectors.length) {
          statsCollectors(i).gatherStats(row, i)
          i += 1
        }

        rowCount += 1
      }
      arrowWriter.finish()

      // Get the Arrow RecordBatch with compression
      val recordBatch = unloader.getRecordBatch()

      Utils.tryWithSafeFinally {
        // Serialize to Arrow IPC format
        val arrowData = ArrowCachedBatchSerializer.serializeBatch(recordBatch)

        // Build statistics InternalRow from collected stats
        val stats = ArrowCachedBatchSerializer.buildStatisticsFromCollectors(
          statsCollectors, schema)

        ArrowCachedBatch(rowCount, arrowData, stats)
      } {
        recordBatch.close()
      }
    } {
      arrowWriter.reset()
    }
  }

  private def close(): Unit = {
    root.close()
    allocator.close()
  }
}
