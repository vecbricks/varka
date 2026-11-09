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

import scala.jdk.CollectionConverters._

import org.apache.arrow.vector.VectorSchemaRoot

import org.apache.spark.{TaskContext}
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.execution.arrow.{ArrowCompressionUtils, ArrowWriter}
import org.apache.spark.sql.types._
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.sql.vectorized.{ArrowColumnVector, ColumnarBatch, ColumnVector}
import org.apache.spark.util.Utils

/**
 * Iterator that converts ColumnarBatch to ArrowCachedBatch.
 */
private class ColumnarBatchToArrowCachedBatchIterator(
    batchIter: Iterator[ColumnarBatch],
    schema: Seq[Attribute],
    sparkSchema: StructType,
    timeZoneId: String,
    compressionCodecName: String,
    compressionLevel: Int) extends Iterator[ArrowCachedBatch] {

  private val compressionCodec = ArrowCompressionUtils.createCompressionCodec(
    compressionCodecName,
    compressionLevel)

  private val allocator = ArrowUtils.rootAllocator.newChildAllocator(
    s"ColumnarBatchToArrowCachedBatchIterator-${TaskContext.get().taskAttemptId()}",
    0,
    Long.MaxValue)

  private val arrowSchema = ArrowUtils.toArrowSchema(
    sparkSchema, timeZoneId, false, false, losslessInternalTypes = true)

  // Register cleanup
  Option(TaskContext.get()).foreach { tc =>
    tc.addTaskCompletionListener[Unit] { _ =>
      allocator.close()
    }
  }

  override def hasNext: Boolean = batchIter.hasNext

  override def next(): ArrowCachedBatch = {
    val batch = batchIter.next()
    // Release the consumed input batch once converted. This iterator replaces the normal
    // ColumnarToRow consumer (which calls closeIfFreeable() after each batch), so without this
    // an Arrow-backed source's fresh off-heap vectors would stay live for every cached batch and
    // grow executor memory until OOM. Both conversion branches finish synchronously and copy the
    // data out (VectorUnloader / row conversion), so the input is safe to free here on success or
    // failure; closeIfFreeable() is a no-op for reusable writable/constant vectors.
    // One input ColumnarBatch maps to one cached batch: the upstream batch's row count is already
    // bounded by the source's batch-size config (e.g. spark.sql.parquet.columnarReaderBatchSize),
    // so no further record/byte splitting is needed here.
    Utils.tryWithSafeFinally {
      val rowCount = batch.numRows()

      // Check if batch is already Arrow-based for zero-copy path. The zero-copy path serializes
      // the input vectors' buffers verbatim under the cache's own schema (serializeBatch writes
      // only the record batch; the read path reconstructs the schema from cacheSchema and loads
      // the buffers positionally into it), so each input vector's field tree must be physically
      // congruent with the corresponding cache schema field. Any divergence -- a var-width or
      // list offset width disagreeing with the canonical one, view or dictionary encodings, an
      // interchange-shaped nanosecond timestamp or CalendarInterval vector where the cache
      // schema has the lossless structs (losslessInternalTypes=true), a tagged struct carrying
      // extra children, map entry children in the wrong order -- would be silently reinterpreted
      // under the canonical layout when the cached batch is read back. Incongruent input takes
      // the row-based conversion instead, which rewrites the values through ArrowWriter under
      // the cache schema.
      val declaredFields = arrowSchema.getFields
      val vectors = (0 until batch.numCols()).map(batch.column)
      val zeroCopyEligible = vectors.zipWithIndex.forall {
        case (acv: ArrowColumnVector, i) =>
          ArrowUtils.isCompatibleWithDeclaredField(
            acv.getValueVector.getField, declaredFields.get(i))
        case _ => false
      }
      if (zeroCopyEligible) {
        // Fast path: zero-copy extraction of Arrow RecordBatch
        convertArrowBatchZeroCopy(batch, rowCount, schema, vectors)
      } else {
        // Slow path: convert to Arrow via rows
        convertToArrowBatch(batch, rowCount, schema)
      }
    } {
      batch.closeIfFreeable()
    }
  }

  private def convertArrowBatchZeroCopy(
      batch: ColumnarBatch,
      rowCount: Int,
      schema: Seq[Attribute],
      vectors: Seq[ColumnVector]): ArrowCachedBatch = {
    // Zero-copy path: extract Arrow vectors directly from ArrowColumnVector. Vectors reaching
    // this path are physically congruent with the cache schema (isCompatibleWithDeclaredField),
    // so nanosecond timestamp and CalendarInterval columns are in the lossless struct shape
    // matching it; no value conversion happens here, so no overflow is possible.
    val arrowVectors = vectors.map(
      _.asInstanceOf[ArrowColumnVector].getValueVector.asInstanceOf[
        org.apache.arrow.vector.FieldVector])

    // Create a VectorSchemaRoot from the existing vectors
    val root = new VectorSchemaRoot(arrowSchema, arrowVectors.asJava, rowCount)

    Utils.tryWithSafeFinally {
      // Use VectorUnloader to create compressed RecordBatch
      val unloader = ArrowCompressionUtils.createVectorUnloader(root, compressionCodec)
      val recordBatch = unloader.getRecordBatch()

      Utils.tryWithSafeFinally {
        val arrowData = ArrowCachedBatchSerializer.serializeBatch(recordBatch)
        val stats = ArrowCachedBatchSerializer.collectStatistics(root, schema)
        ArrowCachedBatch(rowCount, arrowData, stats)
      } {
        recordBatch.close()
      }
    } {
      // Note: We don't close the root here because we don't own the vectors -- they are owned by
      // the input ColumnarBatch, whose buffers the caller frees via batch.closeIfFreeable() after
      // this method returns. That is not a use-after-free: serializeBatch/collectStatistics above
      // already copy everything this method returns (arrowData is a materialized Array[Byte],
      // stats are plain values) out of the vectors before this method returns, so nothing here
      // still references the input's buffers once the caller frees them.
    }
  }

  private def convertToArrowBatch(
      batch: ColumnarBatch,
      rowCount: Int,
      schema: Seq[Attribute]): ArrowCachedBatch = {
    // Convert columnar batch to rows, then to Arrow
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    val arrowWriter = ArrowWriter.create(root)
    val unloader = ArrowCompressionUtils.createVectorUnloader(root, compressionCodec)

    Utils.tryWithSafeFinally {
      val rowIterator = batch.rowIterator().asScala
      while (rowIterator.hasNext) {
        arrowWriter.write(rowIterator.next())
      }
      arrowWriter.finish()

      val recordBatch = unloader.getRecordBatch()
      Utils.tryWithSafeFinally {
        val arrowData = ArrowCachedBatchSerializer.serializeBatch(recordBatch)
        // Derive statistics from the built Arrow vectors rather than the input rows. The input
        // rows here are ColumnarArray/ColumnarMap/ColumnarRow views (this is the non-Arrow
        // ColumnarBatch path, e.g. vectorized nested Parquet/ORC), which do not expose a byte
        // size; collecting row-by-row would record zero bytes for every complex value and make a
        // complex-only relation report sizeInBytes=0, wrongly eligible for broadcast. Reading
        // vector.getBufferSize off the finished root accounts for the actual payload, matching the
        // zero-copy path.
        val stats = ArrowCachedBatchSerializer.collectStatistics(root, schema)
        ArrowCachedBatch(rowCount, arrowData, stats)
      } {
        recordBatch.close()
      }
    } {
      arrowWriter.reset()
      root.close()
    }
  }
}
