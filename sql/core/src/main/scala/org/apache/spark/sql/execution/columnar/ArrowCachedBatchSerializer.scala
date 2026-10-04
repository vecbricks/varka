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

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.channels.Channels

import scala.jdk.CollectionConverters._

import org.apache.arrow.compression.{Lz4CompressionCodec, ZstdCompressionCodec}
import org.apache.arrow.flatbuf.{RecordBatch => FlatBufRecordBatch}
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.{TypeLayout, VectorSchemaRoot}
import org.apache.arrow.vector.compression.{CompressionCodec, NoCompressionCodec}
import org.apache.arrow.vector.ipc.{ReadChannel, WriteChannel}
import org.apache.arrow.vector.ipc.message.{ArrowBodyCompression, ArrowFieldNode}
import org.apache.arrow.vector.ipc.message.{ArrowRecordBatch, MessageSerializer}
import org.apache.arrow.vector.types.pojo.Field

import org.apache.spark.{SparkException}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.expressions.codegen.UnsafeRowWriter
import org.apache.spark.sql.catalyst.types.DataTypeUtils
import org.apache.spark.sql.columnar.{CachedBatch, SimpleMetricsCachedBatchSerializer}
import org.apache.spark.sql.errors.ExecutionErrors
import org.apache.spark.sql.execution.{SparkPlan, VarkaFusedTransition}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.sql.vectorized.{ArrowColumnVector, ColumnarBatch}
import org.apache.spark.storage.StorageLevel
import org.apache.spark.unsafe.types.{TimestampNanosVal}

/**
 * A [[CachedBatchSerializer]] that uses Apache Arrow as the cache format.
 *
 * This serializer:
 *  - Supports both row-based (InternalRow) and columnar (ColumnarBatch) input
 *  - Stores each batch as an internal, schema-less encapsulated Arrow RecordBatch message with
 *    optional compression (zstd/lz4); the schema is reconstructed from the relation's attributes
 *    on read (see [[ArrowCachedBatch]])
 *  - Enables zero-copy columnar reads when output is ColumnarBatch
 *  - Uses off-heap memory via Arrow allocators during encode/decode
 *  - Collects per-column statistics for partition pruning
 *
 * Configuration options:
 *  - spark.sql.cache.serializer: Set to this class name to enable
 *  - spark.sql.execution.arrow.maxRecordsPerBatch: Max rows per cached batch
 *  - spark.sql.execution.arrow.maxBytesPerBatch: Max bytes per cached batch
 *  - spark.sql.execution.arrow.compression.codec: Compression (none/zstd/lz4)
 *  - spark.sql.execution.arrow.compression.zstd.level: zstd compression level
 *  - spark.sql.execution.arrow.cache.prefetch.enabled: Enable background prefetch of the next
 *    batch while the current one is being consumed
 *  - spark.sql.inMemoryColumnarStorage.enableVectorizedReader: Enable columnar output
 */
class ArrowCachedBatchSerializer extends SimpleMetricsCachedBatchSerializer {

  // supportsColumnarInput selects the columnar-vs-row input path; it does not gate which schemas
  // this serializer accepts. The cache framework has no per-type fallback to another serializer
  // (whatever spark.sql.cache.serializer selects handles every cached relation), so returning
  // false here only routes input through convertInternalRowToCachedBatch, which is still this
  // serializer. Type support is enforced once per partition by checkSupportedSchema below; the
  // only real precondition for columnar input is that the plan can produce columnar output, which
  // InMemoryRelation already checks via cachedPlan.supportsColumnar before calling this.
  override def supportsColumnarInput(schema: Seq[Attribute]): Boolean = true

  // The default implementation strips a topmost ColumnarToRowTransition to expose the columnar
  // plan underneath - sound for a pure transition, whose only work is the row conversion, and
  // silently WRONG for the fused Varka nodes, which carry a whole projection or filter inside
  // the transition (VARKA-21 found this: caching a view whose top was the fused filter cached
  // the unfiltered table). Those nodes exist in row/columnar pairs running identical kernels,
  // so instead of refusing the conversion this swaps the fused row node for its columnar
  // sibling: the cache still gets columnar input, and the work the transition had fused away
  // is kept. Handled through the VarkaFusedTransition trait, not a node list (the review's
  // second pass): a future fused transition node is forced by the compiler to declare its
  // sibling and is handled here automatically, instead of silently reviving the strip bug.
  override def convertToColumnarPlanIfPossible(plan: SparkPlan): SparkPlan = plan match {
    case varka: VarkaFusedTransition => varka.columnarSibling
    case other => super.convertToColumnarPlanIfPossible(other)
  }

  override def convertInternalRowToCachedBatch(
      input: RDD[InternalRow],
      schema: Seq[Attribute],
      storageLevel: StorageLevel,
      conf: SQLConf): RDD[CachedBatch] = {
    ArrowCachedBatchSerializer.checkSupportedSchema(schema)
    // Capture config values on driver before RDD transformation
    val sparkSchema = DataTypeUtils.fromAttributes(schema)
    val maxRecordsPerBatch = conf.arrowMaxRecordsPerBatch
    val maxBytesPerBatch = conf.arrowMaxBytesPerBatch
    val timeZoneId = conf.sessionLocalTimeZone
    val compressionCodecName = conf.arrowCompressionCodec
    val compressionLevel = conf.arrowZstdCompressionLevel

    input.mapPartitionsInternal { rowIterator =>
      new InternalRowToArrowCachedBatchIterator(
        rowIterator,
        schema,
        sparkSchema,
        maxRecordsPerBatch,
        maxBytesPerBatch,
        timeZoneId,
        compressionCodecName,
        compressionLevel)
    }
  }

  override def convertColumnarBatchToCachedBatch(
      input: RDD[ColumnarBatch],
      schema: Seq[Attribute],
      storageLevel: StorageLevel,
      conf: SQLConf): RDD[CachedBatch] = {
    ArrowCachedBatchSerializer.checkSupportedSchema(schema)
    // Capture config values on driver before RDD transformation
    val sparkSchema = DataTypeUtils.fromAttributes(schema)
    val timeZoneId = conf.sessionLocalTimeZone
    val compressionCodecName = conf.arrowCompressionCodec
    val compressionLevel = conf.arrowZstdCompressionLevel

    input.mapPartitionsInternal { batchIterator =>
      new ColumnarBatchToArrowCachedBatchIterator(
        batchIterator,
        schema,
        sparkSchema,
        timeZoneId,
        compressionCodecName,
        compressionLevel)
    }
  }

  override def supportsColumnarOutput(schema: StructType): Boolean = {
    // Always support columnar output with Arrow
    true
  }

  override def vectorTypes(attributes: Seq[Attribute], conf: SQLConf): Option[Seq[String]] = {
    Option(Seq.fill(attributes.length)(classOf[ArrowColumnVector].getName))
  }

  override def convertCachedBatchToColumnarBatch(
      input: RDD[CachedBatch],
      cacheAttributes: Seq[Attribute],
      selectedAttributes: Seq[Attribute],
      conf: SQLConf): RDD[ColumnarBatch] = {
    val cacheSchema = DataTypeUtils.fromAttributes(cacheAttributes)
    val selectedSchema = DataTypeUtils.fromAttributes(selectedAttributes)
    val columnIndices = CachedColumnIndices(cacheAttributes, selectedAttributes)
    // Capture config on driver
    val timeZoneId = conf.sessionLocalTimeZone
    val prefetchEnabled = conf.arrowCachePrefetchEnabled

    input.mapPartitionsInternal { batchIterator =>
      new ArrowCachedBatchToColumnarBatchIterator(
        batchIterator,
        cacheSchema,
        selectedSchema,
        columnIndices,
        timeZoneId,
        prefetchEnabled)
    }
  }

  override def convertCachedBatchToInternalRow(
      input: RDD[CachedBatch],
      cacheAttributes: Seq[Attribute],
      selectedAttributes: Seq[Attribute],
      conf: SQLConf): RDD[InternalRow] = {
    if (selectedAttributes.isEmpty) {
      // Empty projection (e.g. a count aggregate over the cached relation): every cached batch
      // already records its row count, so emit that many empty rows without touching the Arrow
      // payload at all -- deserializing and decompressing it would be pure waste. The emitted
      // row is a single reused 0-field UnsafeRow, matching the reuse contract of the regular
      // path.
      return input.mapPartitionsInternal { batchIterator =>
        val rowWriter = new UnsafeRowWriter(0)
        rowWriter.reset()
        val emptyRow = rowWriter.getRow
        batchIterator.flatMap(batch => Iterator.fill(batch.numRows)(emptyRow))
      }
    }
    val cacheSchema = DataTypeUtils.fromAttributes(cacheAttributes)
    val selectedSchema = DataTypeUtils.fromAttributes(selectedAttributes)
    val timeZoneId = conf.sessionLocalTimeZone

    // Check if all selected types can use the fast path.
    // Types not handled by ArrowColumnReader must use the fallback path.
    val needsFallback = selectedSchema.fields.exists { f =>
      f.dataType match {
        case _: ArrayType | _: StructType | _: MapType => true
        case CalendarIntervalType | VariantType | NullType => true
        case _: UserDefinedType[_] => true
        // Geometry/Geography are represented as an Arrow struct (srid + wkb); the fast-path
        // ArrowColumnReader does not handle them, so route them through the fallback.
        case _: GeometryType | _: GeographyType => true
        // Nanosecond timestamps write a 16-byte TimestampNanosVal payload into the UnsafeRow;
        // the fast-path typed readers only write fixed primitives, so use the fallback, which
        // reads through ArrowColumnVector.getTimestampNTZNanos/getTimestampLTZNanos.
        case _: TimestampNTZNanosType | _: TimestampLTZNanosType => true
        case _ => false
      }
    }

    if (needsFallback) {
      // Fall back to columnar-to-row conversion via ColumnarBatch for complex types.
      // Use UnsafeProjection to convert ColumnarBatchRow to UnsafeRow.
      convertCachedBatchToColumnarBatch(input, cacheAttributes, selectedAttributes, conf)
        .mapPartitionsInternal { batchIter =>
          val toUnsafe = org.apache.spark.sql.catalyst.expressions.UnsafeProjection.create(
            selectedSchema)
          batchIter.flatMap { batch =>
            val numRows = batch.numRows()
            new Iterator[InternalRow] {
              private var rowIdx = 0
              override def hasNext: Boolean = rowIdx < numRows
              override def next(): InternalRow = {
                val row = batch.getRow(rowIdx)
                rowIdx += 1
                toUnsafe(row)
              }
            }
          }
        }
    } else {
      // Only the fast path consumes the column indices; the fallback branch above delegates to
      // convertCachedBatchToColumnarBatch, which resolves them itself.
      val selectedIndices = CachedColumnIndices(cacheAttributes, selectedAttributes)
      val prefetchEnabled = conf.arrowCachePrefetchEnabled
      input.mapPartitionsInternal { batchIterator =>
        new ArrowCachedBatchToInternalRowIterator(
          batchIterator,
          cacheSchema,
          selectedSchema,
          selectedIndices,
          timeZoneId,
          prefetchEnabled)
      }
    }
  }
}

/**
 * Companion object with shared utility methods for Arrow cache serialization.
 */
private object ArrowCachedBatchSerializer {

  /**
   * Fail fast, once per partition on the driver-facing entry points, if any column type cannot be
   * represented by the Arrow cache. This is the actual capability gate (supportsColumnarInput only
   * chooses the input path). Without it, an unsupported type would otherwise surface as a less
   * obvious failure deeper in schema conversion or statistics collection.
   */
  def checkSupportedSchema(schema: Seq[Attribute]): Unit = {
    schema.find(attr => !ArrowUtils.isSupportedByArrow(attr.dataType)).foreach { attr =>
      // Use the structured user-facing condition (UNSUPPORTED_DATATYPE) rather than an internal
      // error: an unsupported column type is a user-visible limitation, and the docs promise this
      // condition. It is also the same condition toArrowSchema raises, so callers see one
      // condition for the capability regardless of which layer detects it first.
      throw ExecutionErrors.unsupportedDataTypeError(attr.dataType)
    }
  }

  // scalastyle:off caselocale
  def createCompressionCodec(
      codecName: String,
      compressionLevel: Int): CompressionCodec = {
    codecName.toLowerCase match {
      case "none" => NoCompressionCodec.INSTANCE
      // The codec instance must be constructed directly so that compressionLevel is honored:
      // CompressionCodec.Factory.createCodec(codecType) ignores the level and builds a codec at
      // the default level. The level only matters on the write side; the read side looks up the
      // codec by the type recorded in the IPC message.
      case "zstd" => new ZstdCompressionCodec(compressionLevel)
      case "lz4" => new Lz4CompressionCodec()
      case other =>
        throw SparkException.internalError(
          s"Unsupported Arrow compression codec: $other. Supported values: none, zstd, lz4")
    }
  }
  // scalastyle:on caselocale

  def serializeBatch(batch: ArrowRecordBatch): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    val writeChannel = new WriteChannel(Channels.newChannel(out))
    MessageSerializer.serialize(writeChannel, batch)
    out.toByteArray
  }

  /**
   * Number of Arrow buffers a field occupies in a RecordBatch body, including all of its
   * descendants, in the depth-first order `VectorLoader` consumes them. The type's own buffer
   * count comes from `TypeLayout` (validity + offset/data buffers), then each child contributes
   * its whole subtree recursively. Used to map each top-level column to its run of buffers.
   */
  private def fieldBufferCount(field: Field): Int =
    TypeLayout.getTypeBufferCount(field.getType) +
      field.getChildren.asScala.map(fieldBufferCount).sum

  /** Number of Arrow field nodes a field occupies (itself plus every descendant). */
  private def fieldNodeCount(field: Field): Int =
    1 + field.getChildren.asScala.map(fieldNodeCount).sum

  /** Number of variadic buffer counts a field contributes (one per view-type buffer, recursive). */
  private def fieldVariadicCount(field: Field): Int = {
    val own = field.getType match {
      // View types (Utf8View/BinaryView) carry a variadic-buffer count in the RecordBatch;
      // no other type does. The cache never writes view vectors today, but account for them so
      // the span arithmetic stays correct if that changes.
      case _: org.apache.arrow.vector.types.pojo.ArrowType.Utf8View |
          _: org.apache.arrow.vector.types.pojo.ArrowType.BinaryView => 1
      case _ => 0
    }
    own + field.getChildren.asScala.map(fieldVariadicCount).sum
  }

  /**
   * Read an encapsulated IPC RecordBatch message from `data`, materializing off-heap only the
   * buffers of the requested top-level columns. This is the projection-pushdown read path: the
   * message metadata (a small flatbuffer) lists every buffer's (offset, length) within the body,
   * so we copy just the byte ranges belonging to the selected columns straight out of the
   * in-memory `data` array, never touching (or allocating off-heap for) the unselected columns.
   *
   * The body is a flat, depth-first sequence of buffers in schema order, so each top-level column
   * owns a contiguous run of buffers whose span is `fieldBufferCount`; field nodes and variadic
   * counts run in the same order. The selected columns' bytes are copied into one off-heap buffer
   * (each buffer 8-byte aligned, matching Arrow's IPC body layout) and the returned batch's
   * buffers are windows into it, exactly like the standard reader slices one body buffer -- so the
   * batch has a single underlying allocation and no per-buffer bookkeeping. The returned batch
   * owns its buffers (the constructor retains each), so the caller closes it as usual.
   *
   * Compression is preserved unchanged: buffer (offset, length) spans cover the on-body bytes
   * including any per-buffer uncompressed-length prefix, so the copied windows are still compressed
   * as written; `VectorLoader.load` decompresses only the selected ones later.
   */
  def readProjectedRecordBatch(
      data: Array[Byte],
      schemaFields: Seq[Field],
      selectedIndices: Array[Int],
      allocator: BufferAllocator): ArrowRecordBatch = {
    val in = new ByteArrayInputStream(data)
    val readChannel = new ReadChannel(Channels.newChannel(in))
    // Read only the message metadata; the body bytes stay in `data` and are copied selectively.
    val metadata = MessageSerializer.readMessage(readChannel)
    require(metadata != null, "Unexpected end of input reading cached batch message")
    val batch =
      metadata.getMessage.header(new FlatBufRecordBatch()).asInstanceOf[FlatBufRecordBatch]
    // serializeBatch writes exactly [encapsulated message][body] with no end-of-stream marker, so
    // the body is the tail of `data`: it starts at data.length minus the declared body length.
    val bodyStart = data.length - metadata.getMessageBodyLength().toInt

    val compression: ArrowBodyCompression =
      if (batch.compression() == null) NoCompressionCodec.DEFAULT_BODY_COMPRESSION
      else new ArrowBodyCompression(batch.compression().codec(), batch.compression().method())

    val nodeStarts = schemaFields.scanLeft(0)(_ + fieldNodeCount(_)).toArray
    val bufferStarts = schemaFields.scanLeft(0)(_ + fieldBufferCount(_)).toArray
    val variadicStarts = schemaFields.scanLeft(0)(_ + fieldVariadicCount(_)).toArray
    val hasVariadic = batch.variadicBufferCountsLength() > 0

    // Enumerate the selected columns' nodes, buffer indices and variadic counts, in output order.
    val selectedNodes = new java.util.ArrayList[ArrowFieldNode]()
    val selectedBufferIdx = new scala.collection.mutable.ArrayBuffer[Int]()
    val selectedVariadic = new java.util.ArrayList[java.lang.Long]()
    selectedIndices.foreach { i =>
      val field = schemaFields(i)
      val nStart = nodeStarts(i)
      (nStart until nStart + fieldNodeCount(field)).foreach { j =>
        val node = batch.nodes(j)
        selectedNodes.add(new ArrowFieldNode(node.length(), node.nullCount()))
      }
      val bStart = bufferStarts(i)
      (bStart until bStart + fieldBufferCount(field)).foreach(selectedBufferIdx += _)
      if (hasVariadic) {
        val vStart = variadicStarts(i)
        (vStart until vStart + fieldVariadicCount(field)).foreach(j =>
          selectedVariadic.add(batch.variadicBufferCounts(j)))
      }
    }

    val layout = selectedBufferIdx.map { j =>
      val buf = batch.buffers(j)
      (buf.offset(), buf.length())
    }
    val alignedSizes = layout.map { case (_, len) => ((len + 7) / 8) * 8 }
    val body = allocator.buffer(math.max(alignedSizes.sum, 1))
    try {
      val selectedBuffers = new java.util.ArrayList[org.apache.arrow.memory.ArrowBuf]()
      var pos = 0L
      layout.indices.foreach { k =>
        val (srcOffset, len) = layout(k)
        if (len > 0) {
          body.setBytes(pos, data, bodyStart + srcOffset.toInt, len.toInt)
        }
        val window = body.slice(pos, len)
        window.writerIndex(len)
        selectedBuffers.add(window)
        pos += alignedSizes(k)
      }
      val recordBatch = new ArrowRecordBatch(
        batch.length().toInt,
        selectedNodes,
        selectedBuffers,
        compression,
        selectedVariadic,
        false)
      // The constructor retained each window (slice() itself does not), so the batch now holds one
      // reference per window into `body`. Drop `body`'s original allocation reference; the batch is
      // then the sole owner and the caller's recordBatch.close() frees the single allocation.
      body.close()
      recordBatch
    } catch {
      case t: Throwable =>
        body.close()
        throw t
    }
  }

  /**
   * Byte offset of the unscaled low-order word within a 16-byte Arrow Decimal128 slot, for the
   * given native byte order. Arrow Java writes decimal values in native byte order
   * (DecimalUtility.writeLongToArrowBuf / writeBigDecimalToArrowBuf): on little-endian platforms
   * the low-order word occupies the first 8 bytes and the sign-extension word follows, while on
   * big-endian platforms the order is reversed and the low-order word occupies the last 8 bytes.
   * Reading the wrong word on a big-endian JVM turns positive compact decimals into 0 and
   * negative ones into an unscaled -1.
   */
  def compactDecimalUnscaledOffset(nativeOrder: java.nio.ByteOrder): Long =
    if (nativeOrder == java.nio.ByteOrder.LITTLE_ENDIAN) 0L else 8L

  /**
   * Shut down a prefetch worker during task cleanup without leaking the root it may have produced.
   *
   * The prefetch worker deserializes the next batch into a fresh [[VectorSchemaRoot]] off-thread.
   * If task completion runs while a result is in flight (e.g. a LIMIT consumer stops early),
   * cancelling and discarding the future would drop a root that was already (or is about to be)
   * produced, and the subsequent `allocator.close()` would fail with "Memory was leaked by query".
   *
   * This stops accepting new work, waits for the worker to finish so no root is produced after we
   * stop looking, then closes any completed result. Always returns null so the caller can null out
   * its future reference. Safe to call with a null executor or future.
   */
  def drainAndClosePrefetch(
      executor: java.util.concurrent.ExecutorService,
      future: java.util.concurrent.Future[VectorSchemaRoot]): java.util.concurrent.Future[
        VectorSchemaRoot] = {
    // Drain and join the worker uninterruptibly, then close any root it produced, before the
    // caller closes the allocator. This runs from a task-completion listener, which can fire with
    // the task thread already interrupted (e.g. a killed task). If we let awaitTermination or
    // future.get observe the interrupt and bail early, the worker could still be allocating into,
    // or have already returned, a root that we then neither join nor close -- and the subsequent
    // allocator.close() would race the worker or fail with "Memory was leaked by query". So we
    // defer every interruption -- whether present on entry or delivered while blocked (throwing
    // InterruptedException clears the status, so it must be recorded here or it is lost) -- and
    // restore the flag once draining is done.
    var wasInterrupted = Thread.interrupted()
    try {
      if (executor != null) {
        executor.shutdown()
        var terminated = false
        while (!terminated) {
          try {
            terminated =
              executor.awaitTermination(Long.MaxValue, java.util.concurrent.TimeUnit.NANOSECONDS)
          } catch {
            // Record the interruption and keep waiting: we must not leave the worker running.
            case _: InterruptedException => wasInterrupted = true
          }
        }
      }
      if (future != null) {
        try {
          // The worker has terminated, so this does not block; close the root it produced.
          val root = future.get()
          if (root != null) {
            root.close()
          }
        } catch {
          // The batch was never produced (cancelled/failed); nothing to close.
          case _: java.util.concurrent.CancellationException =>
          case _: java.util.concurrent.ExecutionException =>
          case _: InterruptedException => wasInterrupted = true
        }
      }
    } finally {
      if (wasInterrupted) {
        Thread.currentThread().interrupt()
      }
    }
    null
  }

  def createColumnStats(dataType: DataType): ColumnStats = {
    dataType match {
      case BooleanType => new BooleanColumnStats
      case ByteType => new ByteColumnStats
      case ShortType => new ShortColumnStats
      case IntegerType => new IntColumnStats
      case DateType => new IntColumnStats  // Date is stored as Int
      case LongType => new LongColumnStats
      case TimestampType => new LongColumnStats  // Timestamp is stored as Long
      case TimestampNTZType => new LongColumnStats  // TimestampNTZ is stored as Long
      // Nanosecond timestamps use the TimestampNanosVal-aware collector (min/max bounds), the
      // same one the default cache serializer uses for these types.
      case _: TimestampNTZNanosType | _: TimestampLTZNanosType => new TimestampNanosColumnStats
      case FloatType => new FloatColumnStats
      case DoubleType => new DoubleColumnStats
      case st: StringType => new StringColumnStats(st)
      case BinaryType => new BinaryColumnStats
      case dt: DecimalType => new DecimalColumnStats(dt)
      case CalendarIntervalType => new IntervalColumnStats
      case _: YearMonthIntervalType => new IntColumnStats   // stored as Int
      case _: DayTimeIntervalType => new LongColumnStats  // stored as Long
      case _: TimeType => new LongColumnStats  // Time is stored as Long (nanoseconds)
      case VariantType => new VariantColumnStats
      // Geometry/Geography collect size/count without min/max bounds. Their physical value is a
      // BinaryView (not Array[Byte]), so GeoColumnStats reads it via getBinaryView rather than
      // BinaryColumnStats' getBinary, which would throw ClassCastException on a row that stores a
      // BinaryView. They are also AtomicTypes that ColumnType (used by ObjectColumnStats) does not
      // handle, so they must be matched explicitly here.
      case _: GeometryType | _: GeographyType => new GeoColumnStats
      // Unwrap UDTs to the same collector their underlying type would use. isSupportedByArrow
      // accepts a UDT whenever its sqlType is supported (including Variant/Geometry/Geography),
      // but ObjectColumnStats -> ColumnType(udt.sqlType) only unwraps one level and has no case
      // for those types, so it would throw UNSUPPORTED_DATATYPE during materialization. Recursing
      // here keeps the capability check and the statistics path in agreement.
      case udt: UserDefinedType[_] => createColumnStats(udt.sqlType)
      case _ => new ObjectColumnStats(dataType)
    }
  }

  def buildStatisticsFromCollectors(
      collectors: Array[ColumnStats],
      schema: Seq[Attribute]): InternalRow = {
    val stats = collectors.flatMap { collector =>
      val collected = collector.collectedStatistics
      // ColumnStats returns: [lowerBound, upperBound, nullCount, count, sizeInBytes]
      Seq(collected(0), collected(1), collected(2), collected(3), collected(4))
    }
    InternalRow.fromSeq(stats.toSeq)
  }

  def collectStatistics(
      root: VectorSchemaRoot,
      schema: Seq[Attribute]): InternalRow = {
    val rowCount = root.getRowCount
    val vectors = root.getFieldVectors.asScala.toSeq

    // Collect stats for each column: lowerBound, upperBound, nullCount, rowCount, sizeInBytes.
    // getNullCount reads the validity buffer with word-at-a-time bit counting instead of a
    // per-row isNull call through the vector interface; NullVector reports all rows null and the
    // struct-backed lossless types (nanos timestamps, CalendarInterval) count their struct's own
    // validity buffer, so the semantics match the per-row loop for every shape this cache
    // produces.
    val stats = schema.zip(vectors).flatMap { case (attr, vector) =>
      val nullCount = vector.getNullCount
      val sizeInBytes = vector.getBufferSize.toLong

      val (lower, upper) = attr.dataType match {
        case BooleanType => calculateMinMaxBoolean(vector, rowCount)
        case ByteType => calculateMinMaxByte(vector, rowCount)
        case ShortType => calculateMinMaxShort(vector, rowCount)
        case IntegerType => calculateMinMaxInt(vector, rowCount)
        case DateType => calculateMinMaxDate(vector, rowCount)
        case LongType => calculateMinMaxLong(vector, rowCount)
        case TimestampType => calculateMinMaxTimestamp(vector, rowCount)
        case TimestampNTZType => calculateMinMaxTimestampNTZ(vector, rowCount)
        case _: TimestampNTZNanosType | _: TimestampLTZNanosType =>
          calculateMinMaxTimestampNanos(vector, rowCount)
        case FloatType => calculateMinMaxFloat(vector, rowCount)
        case DoubleType => calculateMinMaxDouble(vector, rowCount)
        case st: StringType => calculateMinMaxString(vector, rowCount, st.collationId)
        case _: DecimalType => calculateMinMaxDecimal(vector, rowCount, attr.dataType)
        case _: YearMonthIntervalType => calculateMinMaxYearMonthInterval(vector, rowCount)
        case _: DayTimeIntervalType => calculateMinMaxDayTimeInterval(vector, rowCount)
        case _: TimeType => calculateMinMaxTime(vector, rowCount)
        case _ => (null, null) // Skip for binary, complex, and other unsupported types
      }

      Seq(lower, upper, nullCount, rowCount, sizeInBytes)
    }

    new org.apache.spark.sql.catalyst.expressions.GenericInternalRow(stats.toArray)
  }

  def calculateMinMaxBoolean(
      vector: org.apache.arrow.vector.FieldVector,
      rowCount: Int): (Any, Any) = {
    var min = true
    var max = false
    var hasValue = false

    (0 until rowCount).foreach { i =>
      if (!vector.isNull(i)) {
        val value = vector.asInstanceOf[org.apache.arrow.vector.BitVector].get(i) != 0
        if (!hasValue) {
          min = value
          max = value
          hasValue = true
        } else {
          if (value < min) min = value
          if (value > max) max = value
        }
      }
    }

    if (hasValue) (min, max) else (null, null)
  }

  def calculateMinMaxByte(
      vector: org.apache.arrow.vector.FieldVector,
      rowCount: Int): (Any, Any) = {
    var min = Byte.MaxValue
    var max = Byte.MinValue
    var hasValue = false

    (0 until rowCount).foreach { i =>
      if (!vector.isNull(i)) {
        val value = vector.asInstanceOf[org.apache.arrow.vector.TinyIntVector].get(i)
        if (!hasValue) {
          min = value
          max = value
          hasValue = true
        } else {
          if (value < min) min = value
          if (value > max) max = value
        }
      }
    }

    if (hasValue) (min, max) else (null, null)
  }

  def calculateMinMaxShort(
      vector: org.apache.arrow.vector.FieldVector,
      rowCount: Int): (Any, Any) = {
    var min = Short.MaxValue
    var max = Short.MinValue
    var hasValue = false

    (0 until rowCount).foreach { i =>
      if (!vector.isNull(i)) {
        val value = vector.asInstanceOf[org.apache.arrow.vector.SmallIntVector].get(i)
        if (!hasValue) {
          min = value
          max = value
          hasValue = true
        } else {
          if (value < min) min = value
          if (value > max) max = value
        }
      }
    }

    if (hasValue) (min, max) else (null, null)
  }

  def calculateMinMaxInt(
      vector: org.apache.arrow.vector.FieldVector,
      rowCount: Int): (Any, Any) = {
    var min = Int.MaxValue
    var max = Int.MinValue
    var hasValue = false

    (0 until rowCount).foreach { i =>
      if (!vector.isNull(i)) {
        val value = vector.asInstanceOf[org.apache.arrow.vector.IntVector].get(i)
        if (!hasValue) {
          min = value
          max = value
          hasValue = true
        } else {
          if (value < min) min = value
          if (value > max) max = value
        }
      }
    }

    if (hasValue) (min, max) else (null, null)
  }

  def calculateMinMaxDate(
      vector: org.apache.arrow.vector.FieldVector,
      rowCount: Int): (Any, Any) = {
    var min = Int.MaxValue
    var max = Int.MinValue
    var hasValue = false

    (0 until rowCount).foreach { i =>
      if (!vector.isNull(i)) {
        val value = vector.asInstanceOf[org.apache.arrow.vector.DateDayVector].get(i)
        if (!hasValue) {
          min = value
          max = value
          hasValue = true
        } else {
          if (value < min) min = value
          if (value > max) max = value
        }
      }
    }

    if (hasValue) (min, max) else (null, null)
  }

  def calculateMinMaxLong(
      vector: org.apache.arrow.vector.FieldVector,
      rowCount: Int): (Any, Any) = {
    var min = Long.MaxValue
    var max = Long.MinValue
    var hasValue = false

    (0 until rowCount).foreach { i =>
      if (!vector.isNull(i)) {
        val value = vector.asInstanceOf[org.apache.arrow.vector.BigIntVector].get(i)
        if (!hasValue) {
          min = value
          max = value
          hasValue = true
        } else {
          if (value < min) min = value
          if (value > max) max = value
        }
      }
    }

    if (hasValue) (min, max) else (null, null)
  }

  def calculateMinMaxTimestamp(
      vector: org.apache.arrow.vector.FieldVector,
      rowCount: Int): (Any, Any) = {
    var min = Long.MaxValue
    var max = Long.MinValue
    var hasValue = false

    (0 until rowCount).foreach { i =>
      if (!vector.isNull(i)) {
        val value =
          vector.asInstanceOf[org.apache.arrow.vector.TimeStampMicroTZVector].get(i)
        if (!hasValue) {
          min = value
          max = value
          hasValue = true
        } else {
          if (value < min) min = value
          if (value > max) max = value
        }
      }
    }

    if (hasValue) (min, max) else (null, null)
  }

  def calculateMinMaxTimestampNTZ(
      vector: org.apache.arrow.vector.FieldVector,
      rowCount: Int): (Any, Any) = {
    var min = Long.MaxValue
    var max = Long.MinValue
    var hasValue = false

    (0 until rowCount).foreach { i =>
      if (!vector.isNull(i)) {
        val value =
          vector.asInstanceOf[org.apache.arrow.vector.TimeStampMicroVector].get(i)
        if (!hasValue) {
          min = value
          max = value
          hasValue = true
        } else {
          if (value < min) min = value
          if (value > max) max = value
        }
      }
    }

    if (hasValue) (min, max) else (null, null)
  }

  def calculateMinMaxTimestampNanos(
      vector: org.apache.arrow.vector.FieldVector,
      rowCount: Int): (Any, Any) = {
    // The cache stores nanosecond timestamps in the lossless struct representation
    // (losslessInternalTypes = true): child 0 holds epochMicros (int64) and child 1 holds
    // nanosWithinMicro (int16) -- TimestampNanosVal's own components, no unit conversion. The
    // bounds are therefore the exact stored values, compared with TimestampNanosVal's own
    // ordering; no precision truncation is involved on either the stat or the read path, so the
    // two cannot disagree.
    val struct = vector.asInstanceOf[org.apache.arrow.vector.complex.StructVector]
    val micros =
      struct.getChild("epochMicros").asInstanceOf[org.apache.arrow.vector.BigIntVector]
    val nanos =
      struct.getChild("nanosWithinMicro").asInstanceOf[org.apache.arrow.vector.SmallIntVector]
    var min: TimestampNanosVal = null
    var max: TimestampNanosVal = null

    (0 until rowCount).foreach { i =>
      if (!vector.isNull(i)) {
        val value = TimestampNanosVal.fromParts(micros.get(i), nanos.get(i))
        if (min == null || value.compareTo(min) < 0) min = value
        if (max == null || value.compareTo(max) > 0) max = value
      }
    }

    (min, max)
  }

  def calculateMinMaxFloat(
      vector: org.apache.arrow.vector.FieldVector,
      rowCount: Int): (Any, Any) = {
    var min = Float.MaxValue
    var max = Float.MinValue
    var hasValue = false

    (0 until rowCount).foreach { i =>
      if (!vector.isNull(i)) {
        val value = vector.asInstanceOf[org.apache.arrow.vector.Float4Vector].get(i)
        // Skip NaN: IEEE 754 comparisons with NaN are always false, so NaN never
        // updates min/max in the row-based path (FloatColumnStats.gatherValueStats).
        if (!value.isNaN) {
          if (!hasValue) {
            min = value
            max = value
            hasValue = true
          } else {
            if (value < min) min = value
            if (value > max) max = value
          }
        }
      }
    }

    if (hasValue) (min, max) else (null, null)
  }

  def calculateMinMaxDouble(
      vector: org.apache.arrow.vector.FieldVector,
      rowCount: Int): (Any, Any) = {
    var min = Double.MaxValue
    var max = Double.MinValue
    var hasValue = false

    (0 until rowCount).foreach { i =>
      if (!vector.isNull(i)) {
        val value = vector.asInstanceOf[org.apache.arrow.vector.Float8Vector].get(i)
        // Skip NaN to match DoubleColumnStats.gatherValueStats.
        if (!value.isNaN) {
          if (!hasValue) {
            min = value
            max = value
            hasValue = true
          } else {
            if (value < min) min = value
            if (value > max) max = value
          }
        }
      }
    }

    if (hasValue) (min, max) else (null, null)
  }

  def calculateMinMaxString(
      vector: org.apache.arrow.vector.FieldVector,
      rowCount: Int,
      collationId: Int = StringType.collationId): (Any, Any) = {
    var min: org.apache.spark.unsafe.types.UTF8String = null
    var max: org.apache.spark.unsafe.types.UTF8String = null
    var hasValue = false

    (0 until rowCount).foreach { i =>
      if (!vector.isNull(i)) {
        val bytes = vector.asInstanceOf[org.apache.arrow.vector.VarCharVector].get(i)
        val value = org.apache.spark.unsafe.types.UTF8String.fromBytes(bytes)
        if (!hasValue) {
          min = value.clone()
          max = value.clone()
          hasValue = true
        } else {
          if (value.semanticCompare(min, collationId) < 0) min = value.clone()
          if (value.semanticCompare(max, collationId) > 0) max = value.clone()
        }
      }
    }

    if (hasValue) (min, max) else (null, null)
  }

  def calculateMinMaxDecimal(
      vector: org.apache.arrow.vector.FieldVector,
      rowCount: Int,
      dataType: org.apache.spark.sql.types.DataType): (Any, Any) = {
    val decimalType = dataType.asInstanceOf[DecimalType]
    var min: org.apache.spark.sql.types.Decimal = null
    var max: org.apache.spark.sql.types.Decimal = null
    var hasValue = false

    (0 until rowCount).foreach { i =>
      if (!vector.isNull(i)) {
        val bigDecimal = vector.asInstanceOf[
          org.apache.arrow.vector.DecimalVector].getObject(i)
        val value = org.apache.spark.sql.types.Decimal(
          bigDecimal, decimalType.precision, decimalType.scale)

        if (!hasValue) {
          min = value
          max = value
          hasValue = true
        } else {
          if (value.compareTo(min) < 0) min = value
          if (value.compareTo(max) > 0) max = value
        }
      }
    }

    if (hasValue) (min, max) else (null, null)
  }

  def calculateMinMaxYearMonthInterval(
      vector: org.apache.arrow.vector.FieldVector,
      rowCount: Int): (Any, Any) = {
    var min = Int.MaxValue
    var max = Int.MinValue
    var hasValue = false

    (0 until rowCount).foreach { i =>
      if (!vector.isNull(i)) {
        val value = vector.asInstanceOf[org.apache.arrow.vector.IntervalYearVector].get(i)
        if (!hasValue) {
          min = value
          max = value
          hasValue = true
        } else {
          if (value < min) min = value
          if (value > max) max = value
        }
      }
    }

    if (hasValue) (min, max) else (null, null)
  }

  def calculateMinMaxDayTimeInterval(
      vector: org.apache.arrow.vector.FieldVector,
      rowCount: Int): (Any, Any) = {
    var min = Long.MaxValue
    var max = Long.MinValue
    var hasValue = false

    (0 until rowCount).foreach { i =>
      if (!vector.isNull(i)) {
        val value = org.apache.arrow.vector.DurationVector.get(
          vector.asInstanceOf[org.apache.arrow.vector.DurationVector].getDataBuffer, i)
        if (!hasValue) {
          min = value
          max = value
          hasValue = true
        } else {
          if (value < min) min = value
          if (value > max) max = value
        }
      }
    }

    if (hasValue) (min, max) else (null, null)
  }

  def calculateMinMaxTime(
      vector: org.apache.arrow.vector.FieldVector,
      rowCount: Int): (Any, Any) = {
    var min = Long.MaxValue
    var max = Long.MinValue
    var hasValue = false

    (0 until rowCount).foreach { i =>
      if (!vector.isNull(i)) {
        val value = vector.asInstanceOf[org.apache.arrow.vector.TimeNanoVector].get(i)
        if (!hasValue) {
          min = value
          max = value
          hasValue = true
        } else {
          if (value < min) min = value
          if (value > max) max = value
        }
      }
    }

    if (hasValue) (min, max) else (null, null)
  }
}
