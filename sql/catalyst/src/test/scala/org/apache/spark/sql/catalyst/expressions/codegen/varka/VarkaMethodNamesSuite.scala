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

import scala.jdk.CollectionConverters._

import org.apache.spark.SparkFunSuite

/**
 * `VarkaMethodNames` builds exactly the names the emitted classes have always had - the names
 * other suites write literally when they pin a class's layout - and reads every kind back,
 * including the methods that are not a group's.
 */
class VarkaMethodNamesSuite extends SparkFunSuite with VarkaTestWatchdog {

  import VarkaMethodNames._

  test("the builders give the names the emitted classes have") {
    assert(DISPATCH === "run")
    assert(driver(true) === "runDense" && driver(false) === "runMasked")
    assert(stage(true, 0) === "stageDense0" && stage(false, 3) === "stageMasked3")
    assert(loop(true, 0) === "loopDense0" && loop(false, 12) === "loopMasked12")
    assert(epilogue(true) === "epilogueDense" && epilogue(false) === "epilogueMasked")
    assert(epilogue(true, 0) === "epilogueDense0" && epilogue(false, 12) === "epilogueMasked12")
    assert(DRIVERS.asScala === Seq("runDense", "runMasked"))
    assert(GROUP_METHOD_KINDS.asScala ===
      Seq("loopDense", "loopMasked", "epilogueDense", "epilogueMasked"))
  }

  test("the readers tell every kind of method apart") {
    val names = Seq("<init>", "run", "runDense", "runMasked", "stageDense0", "stageMasked1",
      "loopDense0", "loopMasked7", "epilogueDense", "epilogueMasked", "epilogueDense0",
      "epilogueMasked7")
    assert(names.filter(isDriver) === Seq("runDense", "runMasked"))
    assert(names.filter(isDriverOrDispatch) === Seq("run", "runDense", "runMasked"))
    assert(names.filter(isStage) === Seq("stageDense0", "stageMasked1"))
    assert(names.filter(isStage(_, false)) === Seq("stageMasked1"))
    assert(names.filter(isLoop) === Seq("loopDense0", "loopMasked7"))
    assert(names.filter(isLoop(_, true)) === Seq("loopDense0"))
    assert(names.filter(isEpilogue(_, false)) === Seq("epilogueMasked", "epilogueMasked7"))
    assert(names.filter(isGroupMethod) === Seq("loopDense0", "loopMasked7", "epilogueDense",
      "epilogueMasked", "epilogueDense0", "epilogueMasked7"))
  }

  test("a group method's group is read off its name, and every other method has none") {
    assert(groupOf("loopMasked3") === 3 && groupOf("epilogueDense12") === 12)
    for (m <- Seq("<init>", "run", "runDense", "stageDense0", "epilogueDense", "epilogueMasked")) {
      assert(groupOf(m) === -1, m)
    }
    for (dense <- Seq(true, false); g <- Seq(0, 1, 99)) {
      assert(groupOf(loop(dense, g)) === g && groupOf(epilogue(dense, g)) === g)
    }
  }
}
