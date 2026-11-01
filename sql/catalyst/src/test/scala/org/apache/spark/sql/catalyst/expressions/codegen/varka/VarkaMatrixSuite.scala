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

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.jdk.CollectionConverters._

import org.scalatest.exceptions.{TestCanceledException, TestFailedException}

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrix.{Kind, Skip}

/** The option matrix's own machinery: its configurations, their parsing, and the skip list. */
class VarkaMatrixSuite extends SparkFunSuite with VarkaTestWatchdog {

  test("every configuration parses, and changes exactly the option it names") {
    val configurations = VarkaMatrix.configurations
    assert(configurations.distinct === configurations)
    for (config <- configurations.filterNot(_.startsWith(VarkaMatrix.ARROW_BATCH_SIZE))) {
      // A flag with a subject runs over it (VARKA-247), so its configuration names those options
      // too, before its own.
      val names = config.split(",").map(_.takeWhile(_ != '=')).toSet
      val options = VarkaMatrix.parse(config)
      assert(options != VarkaEmitOptions.DEFAULTS, config)
      val moved = VarkaEmitOptions.DEFAULTS.getClass.getRecordComponents.toSeq.filter { c =>
        c.getAccessor.invoke(options) != c.getAccessor.invoke(VarkaEmitOptions.DEFAULTS)
      }.map(_.getName).toSet
      assert(moved === names, config)
    }
  }

  test("the configurations cover every option the fuzzer draws") {
    val named = VarkaMatrix.configurations.filterNot(_.startsWith(VarkaMatrix.ARROW_BATCH_SIZE))
      .map(_.split(",").last.takeWhile(_ != '=')).toSet
    val expected = VarkaEmitOption.TABLE.asScala
      .filter(_.reason.drawn).map(_.name).toSet
    assert(named === expected)
    assert(VarkaMatrix.configurations.contains("lanesOverride=4"))
    assert(VarkaMatrix.configurations.contains("division=DOUBLE_DIV"))
  }

  test("an arm that emits what the defaults or another arm emits does not run") {
    // Catches: the four useAVX levels run as four configurations when they are two emissions,
    // one of them the defaults' (VarkaEmittedBytesSuite holds the bytes to that).
    val levels = VarkaMatrix.configurations.filter(_.startsWith("useAVX="))
    assert(levels === Seq("useAVX=2"))
    val configs = VarkaMatrix.configurations
    assert(configs.distinct === configs)
    assert(configs.map(VarkaMatrix.parse).map(VarkaMatrixRuns.emissionKey).distinct.size ===
      configs.filterNot(_.startsWith(VarkaMatrix.ARROW_BATCH_SIZE)).size + 1,
      "two configurations emit alike")
  }

  test("the batch-size axis changes no emit option and names its rows (VARKA-301)") {
    for (rows <- VarkaMatrix.ARROW_BATCH_SIZES) {
      val config = s"${VarkaMatrix.ARROW_BATCH_SIZE}=$rows"
      assert(VarkaMatrix.configurations.contains(config))
      assert(VarkaMatrix.parse(config) === VarkaEmitOptions.DEFAULTS, config)
    }
    intercept[IllegalArgumentException](VarkaMatrix.parse(s"${VarkaMatrix.ARROW_BATCH_SIZE}=0"))
  }

  test("a malformed configuration is refused") {
    intercept[IllegalArgumentException](VarkaMatrix.parse("cse=on"))
    intercept[IllegalArgumentException](VarkaMatrix.parse("division=FAST"))
    intercept[IllegalArgumentException](VarkaMatrix.parse("noSuchOption=1"))
    intercept[IllegalArgumentException](VarkaMatrix.parse("cse"))
    assert(VarkaMatrix.parse("") === VarkaEmitOptions.DEFAULTS)
    assert(VarkaMatrix.parse("cse=false, groupBudget=8") ===
      VarkaEmitOptions.DEFAULTS.withCse(false).withGroupBudget(8))
  }

  test("the skip list's lines parse, comments and blank lines aside") {
    val text = "# a comment\n\ncse=false\tSomeSuite\tsome test\tfails\tbecause\n"
    assert(VarkaMatrix.parseSkips(text) ===
      Seq(Skip("cse=false", "SomeSuite", "some test", Kind.FAILS, "because")))
    intercept[IllegalArgumentException](VarkaMatrix.parseSkips("cse=false\tSomeSuite\n"))
    intercept[NoSuchElementException](VarkaMatrix.parseSkips("c\ts\tt\tbreaks\tr\n"))
  }

  test("a listed test is cancelled when it fails and fails as stale when it passes") {
    val entries = Seq(Skip("cse=false", "S", "t", Kind.FAILS, "the reason"))
    def run(config: String)(body: => Unit): Unit =
      VarkaMatrix.around(entries, config, None, "S", "t")(body)

    val cancelled = intercept[TestCanceledException](run("cse=false")(throw new AssertionError))
    assert(cancelled.getMessage.contains("the reason"))
    val stale = intercept[TestFailedException](run("cse=false")(()))
    assert(stale.getMessage.contains("stale entry"))
    // Another configuration, or the defaults, leaves the test as it is.
    intercept[AssertionError](run("")(throw new AssertionError))
    run("groupBudget=8")(())
  }

  test("each test's fused batches go to the report file") {
    val report = Files.createTempFile("varka-matrix", ".tsv")
    val saved = VarkaMatrix.fusedBatches
    try {
      var batches = 10L
      VarkaMatrix.fusedBatches = () => batches
      VarkaMatrix.around(Seq.empty, "", Some(report.toString), "S", "fuses")(batches += 3)
      VarkaMatrix.fusedBatches = () => -1L
      VarkaMatrix.around(Seq.empty, "", Some(report.toString), "S", "uncounted")(())
      assert(new String(Files.readAllBytes(report), StandardCharsets.UTF_8) ===
        "S\tfuses\t3\nS\tuncounted\t-1\n")
    } finally {
      VarkaMatrix.fusedBatches = saved
      Files.delete(report)
    }
  }

  test("a C2-code test runs only under configurations that change what C2 is given") {
    assert(VarkaMatrixRuns.changesC2("lanesOverride=4"))
    assert(VarkaMatrixRuns.changesC2("useAVX=2"))
    assert(VarkaMatrixRuns.changesC2("narrowHalfSpecies=true"))
    assert(VarkaMatrixRuns.changesC2("cse=false,lanesOverride=16"))
    assert(!VarkaMatrixRuns.changesC2("cse=false"))
    assert(!VarkaMatrixRuns.changesC2(
      "denseValidityOnce=false_validityByBitmap=false_validityOrFirst=false"))
    // Every option the rule names is one the matrix's configurations set.
    VarkaMatrixRuns.C2_OPTIONS.asScala.foreach { option =>
      assert(VarkaMatrix.configurations.exists(_.startsWith(option + "=")), option)
    }
  }
}
