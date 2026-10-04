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

/**
 * Pins `sql/varka/emit_cost_audit.json`, the emit cost model's accuracy and what its grouping
 * switch does, which `VarkaEmitCostAudit` renders, with the builds of the families VARKA-236 added
 * to its count: VARKA-200's mixed and interleaved families and the wide shapes near and past the
 * driver's ceiling. Not the coverage compositions `VarkaGroupingBoundSuite` draws: their
 * draw indexes `coverage.json`, so a pinned file over them would move with every expression
 * added. Regenerate with
 * `VARKA_COST_REGEN=true build/sbt 'catalyst/testOnly *VarkaEmitCostAuditSuite'` after the price
 * tables (`VarkaEmitCostSuite`) have been regenerated and compiled.
 */
class VarkaEmitCostAuditSuite extends VarkaEmitterTestBase {

  test("sql/varka/emit_cost_audit.json is what the prices predict against what is emitted") {
    val path = getWorkspaceFilePath("sql", "varka", "emit_cost_audit.json")
    val extra = VarkaGroupingBound.interleaved().asScala.toSeq ++
      VarkaEmitCostCorpus.pastCeiling().asScala
    val rendered = VarkaEmitCostAudit.render(extra.asJava)
    if (sys.env.get("VARKA_COST_REGEN").contains("true")) {
      Files.write(path, rendered.getBytes(StandardCharsets.UTF_8))
      logInfo(s"regenerated $path")
    } else {
      assert(Files.exists(path), s"$path is missing; generate it with\n" +
        "  VARKA_COST_REGEN=true build/sbt 'catalyst/testOnly *VarkaEmitCostAuditSuite'")
      val committed = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      assert(committed === rendered, "sql/varka/emit_cost_audit.json differs from what the " +
        "prices and the emitter give now; regenerate it and requote VARKA-199.md from it")
    }
  }
}
