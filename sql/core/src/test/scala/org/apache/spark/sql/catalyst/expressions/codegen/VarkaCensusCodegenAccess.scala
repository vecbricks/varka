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

package org.apache.spark.sql.catalyst.expressions.codegen

/**
 * The census's window onto two package-private parts of the code compiler, for
 * `VarkaCodegenGiveUpSuite`: the byte-code statistics every compiled class is measured by (G29),
 * and the backend a unit of generated code is compiled with (G31). Test code only; it reaches
 * nothing a reproducer could not reach through a query, only reaches it directly.
 */
object VarkaCensusCodegenAccess {

  /** The statistics of one class, as `CodeCompiler` computes them after every compile. */
  def byteCodeStats(classBytes: Array[Byte]): ByteCodeStats =
    CodeCompiler.computeByteCodeStats(Seq("census" -> classBytes))

  /** The backend `CodeCompiler` picks for `body` under the session's configuration. */
  def backendFor(body: String): CodeCompiler =
    CodeCompiler.active(new CodeAndComment(body, Map.empty))
}
