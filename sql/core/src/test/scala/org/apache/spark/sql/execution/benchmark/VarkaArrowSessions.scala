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

package org.apache.spark.sql.execution.benchmark

import org.apache.spark.internal.config.UI.UI_ENABLED
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.codegen.CodeGenerator
import org.apache.spark.sql.execution.{VarkaColumnarRule, WholeStageCodegenExec}
import org.apache.spark.sql.execution.columnar.{ArrowCachedBatchSerializer, InMemoryTableScanExec}
import org.apache.spark.sql.internal.{SQLConf, StaticSQLConf}

/**
 * Sessions for the Varka benchmarks that compare vanilla Spark and Varka over an Arrow-cached
 * table, and the checks that the table really is Arrow-cached.
 *
 * This is a plain object on purpose. A benchmark object creates its default session when it is
 * initialised, so a helper living in one starts a SparkContext the moment another benchmark
 * calls it; the session [[createSession]] then builds joins that context, whose cache serializer
 * is Spark's default, and the Arrow setting is silently ignored. [[cache]] refuses a table that
 * is not Arrow-cached, so that cannot go unnoticed again.
 */
object VarkaArrowSessions {

  /** A local, one-core session whose cache is Arrow, with the Varka rule when asked for. */
  def createSession(appName: String, varkaEnabled: Boolean): SparkSession = {
    val builder = SparkSession.builder()
      .master("local[1]")
      .appName(appName)
      .config(UI_ENABLED.key, false)
      .config(SQLConf.SHUFFLE_PARTITIONS.key, 1)
      .config(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key, "false")
      .config(StaticSQLConf.SPARK_CACHE_SERIALIZER.key,
        classOf[ArrowCachedBatchSerializer].getName)
      .config(SQLConf.CACHE_VECTORIZED_READER_ENABLED.key, "true")
    if (varkaEnabled) {
      builder
        .config(SQLConf.VARKA_ENABLED.key, "true")
        .withExtensions(_.injectColumnar(_ => VarkaColumnarRule))
    }
    builder.getOrCreate()
  }

  /** Caches `view` and materialises it, after checking the cache is Arrow's. */
  def cache(session: SparkSession, view: String): Unit = {
    session.catalog.cacheTable(view)
    session.sql(s"select count(*) from $view").collect()
    val serializer = session.table(view).queryExecution.executedPlan.collectFirst {
      case scan: InMemoryTableScanExec => scan.relation.cacheBuilder.serializer
    }
    require(serializer.exists(_.isInstanceOf[ArrowCachedBatchSerializer]),
      s"$view is cached by ${serializer.map(_.getClass.getName).getOrElse("nothing")}, not " +
        "Arrow: the session joined a SparkContext started with another cache serializer")
  }

  /** Vanilla's largest generated method for a query, from Spark's own compile of the stage. */
  def vanillaMethodBytes(baseline: SparkSession, query: String): Int = {
    val stages = baseline.sql(query).queryExecution.executedPlan.collect {
      case w: WholeStageCodegenExec => w
    }
    require(stages.nonEmpty, s"no whole-stage codegen for the vanilla arm of: $query")
    stages.map(w => CodeGenerator.compile(w.doCodeGen()._2)._2.maxMethodCodeSize).max
  }
}
