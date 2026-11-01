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

package org.apache.spark.sql.catalyst.expressions.codegen.varka

import org.scalatest.{Args, Status}

import org.apache.spark.SparkFunSuite

/**
 * Opens the option matrix's reach window around a suite that emits kernels but does not run its
 * tests through [[VarkaMatrixTests]], so that what it emits is its own (VarkaTestReach). Where
 * another suite of the JVM has started the recording, a kernel emitted outside every suite's
 * window is taken as read by every test of the JVM, so this suite's emissions would make every
 * test run under the configurations that move them. Its own tests run under every
 * configuration, since the record has no line for them.
 */
trait VarkaReachWindow extends SparkFunSuite {
  override def run(testName: Option[String], args: Args): Status = {
    VarkaTestReach.suiteBegin(suiteName)
    try {
      val status = super.run(testName, args)
      status.waitUntilCompleted()
      status
    } finally VarkaTestReach.suiteEnd(suiteName)
  }
}
