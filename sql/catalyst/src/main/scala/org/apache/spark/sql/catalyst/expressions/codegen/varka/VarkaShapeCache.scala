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

import org.apache.spark.SparkEnv
import org.apache.spark.sql.internal.{SQLConf, StaticSQLConf}
import org.apache.spark.util.Utils

/**
 * The Spark-facing facade over [[VarkaShapeCacheImpl]]: everything the cache needs from
 * Spark's configuration and environment lives here, and the cache itself is plain JDK code that
 * takes those as values. Two things cross this line, and only two - the capacity and the parent
 * class loader.
 *
 * '''The executor-wide instance''' (milestone 3 open question 1, settled as per-JVM: the key
 * carries no session state the linkage does not, and Janino's codegen cache is the precedent).
 *
 * '''Sizing''' is read from the JVM's own `SparkConf` ([[SparkEnv]]), which is the one source that
 * is the same for every thread in the JVM and fixed for its lifetime. That is what makes the
 * capacity deterministic, and it is the task-18 debt item: the previous resolution consulted
 * `SQLConf.get` first, which on an executor returns a task's propagated `ReadOnlySQLConf` inside a
 * `SQLExecution` and a defaults-only fallback outside one - so the lazily created singleton froze
 * whatever the first-touching thread happened to see, and two identically configured executors
 * could size differently. `SQLConf.get` is still the fallback when there is no `SparkEnv` at all,
 * which is how a catalyst unit test gets the entry's default.
 *
 * The boundary that leaves, documented rather than discovered: `spark.sql.codegen.varka.cache.
 * maxEntries` is a static SQL conf, so a `SparkSession.builder.config(...)` value reaches an
 * executor only when that builder also created the `SparkContext` - only then does it land in the
 * `SparkConf` the executors are launched with. On a session attached to an existing context,
 * `SQLConf.mergeNonStaticSQLConfigs` drops static keys, so the value never takes effect anywhere,
 * driver included. Setting it with `--conf` (or on the builder that creates the context) is the
 * supported way.
 */
private[sql] object VarkaShapeCache {

  /**
   * The longest execution identity the side table stores; longer ones are abbreviated to exactly
   * this length, marker included. Operator, stage and the leading projection entries survive,
   * which is what the diagnostics join needs - and callers building an identity string need not
   * render more than this.
   */
  private[sql] val maxExecutionIdentityLength = VarkaShapeCacheImpl.MAX_EXECUTION_IDENTITY_LENGTH

  private lazy val instance = new VarkaShapeCacheImpl(configuredMaxEntries())

  private def configuredMaxEntries(): Int = {
    val entry = StaticSQLConf.VARKA_CACHE_MAX_ENTRIES
    val env = SparkEnv.get
    if (env != null) env.conf.get(entry) else SQLConf.get.getConf(entry)
  }

  /**
   * The compiled-size watch, started once per JVM and only when asked for. It hangs here
   * rather than anywhere else because this object is already the JVM-wide singleton on the
   * emission path, already reads a static conf the same way, and already owns the shape hashes
   * the watch keys on - and because a `RecordingStream` owns a thread, so starting one per
   * session or per query would be a bug rather than an inefficiency.
   *
   * `lazy val` gives the once-per-JVM guarantee. When the flag is off this stays `None`, so no
   * stream is opened, no thread is started and no map is allocated: the cost of the feature to
   * anyone who has not asked for it is the null check that reads this.
   */
  private lazy val compilationWatch: Option[VarkaCompilationWatch] =
    if (compilationWatchEnabled()) Some(VarkaCompilationWatch.start()) else None

  private def compilationWatchEnabled(): Boolean = {
    val entry = StaticSQLConf.VARKA_COMPILATION_WATCH_ENABLED
    val env = SparkEnv.get
    if (env != null) env.conf.get(entry) else SQLConf.get.getConf(entry)
  }

  /**
   * How many distinct (shape, method, tier) keys have compiled to a materially different size
   * than they did earlier in this JVM; 0 when the watch is off, which is the default.
   */
  def compilationDivergences(): Long = compilationWatch.map(_.divergenceCount()).getOrElse(0L)

  /** Whether the watch is running - off by configuration and unavailable JFR both read false. */
  private[sql] def compilationWatchRunning(): Boolean =
    compilationWatch.exists(_.isRunning())

  /** The one rendering of the shape-named class name; every caller derives it here. */
  def classNameFor(shapeHash: String): String = VarkaShapeCacheImpl.classNameFor(shapeHash)

  /** The one rendering of the shape-named `SourceFile`; every caller derives it here. */
  def sourceFileFor(shapeHash: String): String = VarkaShapeCacheImpl.sourceFileFor(shapeHash)

  /** The shape's stable name fragment; see [[VarkaShapeCacheImpl.shapeHash]]. */
  def shapeHash(key: VarkaShapeKey): String = VarkaShapeCacheImpl.shapeHash(key)

  /**
   * Resolves the shape under the caller's context class loader - the loader the emitted bytes
   * link the engine's support classes through, and so an input to the entry's identity.
   */
  def getOrEmit(key: VarkaShapeKey, execution: String): VarkaShapeLookup = {
    // The watch has to be subscribed before the kernels it watches are compiled, and
    // nothing on the hot path would otherwise touch it: `compilationDivergences` is a reporting
    // call, so making the lazy val's first force happen there would mean the watch only ever
    // started for a caller already asking what it had seen. Forcing it here costs one volatile
    // read per lookup after the first, and none of the rest when the flag is off, since a `None`
    // is what gets memoised.
    compilationWatch
    instance.getOrEmit(Utils.getContextOrSparkClassLoader, key, execution)
  }

  /**
   * Whether the emitter serves the shape, answered without defining a class; throws the
   * decline if not. The compiler's plan-time question (VARKA-237). Under Spark's testing flag the
   * built bytes are also verified, so the tests catch a class the executors could not define.
   */
  def admit(key: VarkaShapeKey): Unit =
    instance.admit(Utils.getContextOrSparkClassLoader, key, Utils.isTesting)

  def executionsFor(shapeHash: String): Seq[String] =
    instance.executionsFor(shapeHash).asScala.toSeq

  def hitCount: Long = instance.hitCount()
  def missCount: Long = instance.missCount()
  def buildCount: Long = instance.buildCount()
  def size: Long = instance.size()

  /** Test hook, mirroring `CodeGenerator.invalidateCodegenCache`. */
  private[sql] def invalidateAll(): Unit = instance.invalidateAll()
}
