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

import java.util.{List => JList}
import java.util.concurrent.ConcurrentHashMap

import scala.jdk.OptionConverters._

import org.scalactic.source.Position
import org.scalatest.Tag

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuiteReport.{Failed, Passed,
  Skipped}

/**
 * A suite that runs its tests in a JVM of its own when it is run in a shared one (VARKA-246,
 * `m10/SCOPE.md` item 69). Mix it in last, after the suite's other traits.
 *
 * The tests of such a suite emit and run kernels at a lanes override other than the JVM's
 * preferred width, which puts a second vector species of one lane type into the JVM. That makes
 * the Vector API's shared templates inline bimorphically, so that every kernel compiled in the
 * JVM afterwards can box its vectors, and any test whose verdict is a JIT outcome fails by the
 * suites' order (`sql/varka/skills/vector-api-and-width.md`). [[VarkaSpeciesGuard]] fails any
 * suite that does it in the shared JVM; this trait is how a suite that must keeps its coverage.
 *
 * In the shared JVM the suite keeps its tests, by name and tags, but their bodies do not run
 * there: the first of them to run starts a child JVM with the parent's command-line arguments,
 * classpath and `-D` properties (the matrix configuration, the sanitizer, a fuzzer's budget and
 * seed) running this suite alone and writing a JUnit report, and each test then takes its result
 * from that report - passed, cancelled with the child's reason, or failed with the child's
 * message. So a name filter (`-z`, `-t`) selects the tests it names, the counts mean what they
 * did, and a failure is attributed to its test; what the filter cannot do is shorten the child,
 * which runs the whole suite. The child's output is copied to the parent's, so the matrix's markers
 * are where they were. `-Dvarka.ownJvm=true` runs the tests directly in the JVM at hand with the
 * guard off, which is how the child runs.
 *
 * The child, its budget and its report are [[VarkaChildJvm]]'s, which this trait runs with
 * ScalaTest's Runner (VARKA-312). The first test carries the suite's whole time against the
 * child's budget.
 */
trait VarkaOwnJvm extends SparkFunSuite {

  override protected def test(testName: String, testTags: Tag*)(testBody: => Any)
      (implicit pos: Position): Unit = {
    if (VarkaOwnJvm.inChild) {
      super.test(testName, testTags: _*)(testBody)
    } else {
      super.test(testName, testTags: _*) {
        VarkaOwnJvm.resultsOf(getClass.getName).of(testName).toScala match {
          case Some(_: Passed) =>
          case Some(s: Skipped) => cancel(s.reason)
          case Some(f: Failed) => fail(f.message)
          case None => fail(s"the child JVM reported no result for '$testName'")
        }
      }
    }
  }
}

object VarkaOwnJvm {

  /** [[VarkaChildJvm.PROPERTY]]. */
  val PROPERTY: String = VarkaChildJvm.PROPERTY

  def inChild: Boolean = VarkaChildJvm.inChild()

  // One child per suite per parent JVM; the first test to ask runs it.
  private val children = new ConcurrentHashMap[String, VarkaChildJvm.Results]()

  private def resultsOf(suite: String): VarkaChildJvm.Results =
    children.computeIfAbsent(suite, s => VarkaChildJvm.run(s,
      "org.scalatest.tools.Runner", JList.of("-oW", "-s", s), "-u"))
}
