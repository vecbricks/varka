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

package org.apache.spark.sql.connector.write

import org.apache.spark.{SparkFunSuite, SparkUnsupportedOperationException}
import org.apache.spark.sql.catalyst.InternalRow

/**
 * The defaults of the [[DataWriterFactory]] interface. A factory whose [[Write]] declares
 * `supportsColumnarWrite` but that does not override `createColumnarWriter` is a connector bug,
 * and it has to be reported as one rather than as a bare `UnsupportedOperationException`.
 */
class DataWriterFactorySuite extends SparkFunSuite {

  private object RowOnlyWriterFactory extends DataWriterFactory {
    override def createWriter(partitionId: Int, taskId: Long): DataWriter[InternalRow] = {
      throw new UnsupportedOperationException("not needed by this test")
    }
  }

  test("createColumnarWriter is not supported by default") {
    checkError(
      exception = intercept[SparkUnsupportedOperationException] {
        RowOnlyWriterFactory.createColumnarWriter(0, 0L)
      },
      condition = "DATA_SOURCE_COLUMNAR_WRITER_NOT_SUPPORTED",
      parameters = Map("class" -> RowOnlyWriterFactory.getClass.getName))
  }

  test("a write is row-only unless it says otherwise") {
    assert(!(new Write {}).supportsColumnarWrite())
  }
}
