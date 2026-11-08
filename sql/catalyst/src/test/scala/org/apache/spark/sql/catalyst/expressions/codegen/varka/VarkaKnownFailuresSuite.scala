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

import java.util.Optional

import scala.jdk.CollectionConverters._

import org.apache.spark.SparkFunSuite

class VarkaKnownFailuresSuite extends SparkFunSuite {

  private val text =
    "# a comment\n\n" +
      "int\t20260903\t0\toutput mismatch\toutput # row # differs (want #)\tVARKA-999\n" +
      "long\t20260919\t4\tdeclined batch\tthe kernel declined the batch (status #)\tVARKA-998\n"

  test("the list parses, comments and blank lines skipped") {
    val entries = VarkaKnownFailures.parse(text).asScala
    assert(entries.map(_.iteration) == Seq(0, 4))
    assert(entries.head.signature ==
      new VarkaFailureSignature("output mismatch", "output # row # differs (want #)"))
    assert(entries(1).lane == "long" && entries(1).reason == "VARKA-998")
  }

  test("a line without a reason or with the wrong lane is refused") {
    intercept[IllegalArgumentException](VarkaKnownFailures.parse("int\t1\t2\tk\tt\t"))
    intercept[IllegalArgumentException](VarkaKnownFailures.parse("bool\t1\t2\tk\tt\tr"))
    intercept[IllegalArgumentException](VarkaKnownFailures.parse("int\t1\t2\tk\tt"))
  }

  test("a signature on the list is known and one off it is not") {
    val entries = VarkaKnownFailures.parse(text)
    assert(VarkaKnownFailures.isKnown(entries, entries.get(0).signature))
    assert(!VarkaKnownFailures.isKnown(entries, new VarkaFailureSignature("output mismatch", "x")))
  }

  test("an entry its case no longer reproduces is stale") {
    val entries = VarkaKnownFailures.parse(text)
    val replay = (e: VarkaKnownFailures.Entry) =>
      if (e.lane == "int") Optional.of(e.signature) else Optional.empty[VarkaFailureSignature]()
    assert(VarkaKnownFailures.stale(entries, replay(_)).asScala == Seq(entries.get(1)))
    // A case that fails differently on replay is stale as well, not still known.
    val other = (_: VarkaKnownFailures.Entry) => Optional.of(new VarkaFailureSignature("a", "b"))
    assert(VarkaKnownFailures.stale(entries, other(_)) == entries)
  }

  test("the committed list is well formed") {
    VarkaKnownFailures.entries
  }
}
