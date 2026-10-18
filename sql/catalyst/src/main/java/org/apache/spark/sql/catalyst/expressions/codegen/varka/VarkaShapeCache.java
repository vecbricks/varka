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
package org.apache.spark.sql.catalyst.expressions.codegen.varka;

import java.util.List;
import java.util.Optional;

import org.apache.spark.SparkEnv;
import org.apache.spark.internal.config.ConfigEntry;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.sql.internal.StaticSQLConf;
import org.apache.spark.util.Utils;

/**
 * The Spark-facing facade over {@link VarkaShapeCacheImpl}: everything the cache needs from
 * Spark's configuration and environment lives here, and the cache itself is plain JDK code that
 * takes those as values. Two things cross this line, and only two - the capacity and the parent
 * class loader.
 *
 * <p><b>The executor-wide instance</b> (milestone 3 open question 1, settled as per-JVM: the key
 * carries no session state the linkage does not, and Janino's codegen cache is the precedent).
 *
 * <p><b>Sizing</b> is read from the JVM's own {@code SparkConf} ({@code SparkEnv}), which is the
 * one source that is the same for every thread in the JVM and fixed for its lifetime. That is
 * what makes the capacity deterministic, and it is the task-18 debt item: the previous resolution
 * consulted {@code SQLConf.get} first, which on an executor returns a task's propagated
 * {@code ReadOnlySQLConf} inside a {@code SQLExecution} and a defaults-only fallback outside one -
 * so the lazily created singleton froze whatever the first-touching thread happened to see, and
 * two identically configured executors could size differently. {@code SQLConf.get} is still the
 * fallback when there is no {@code SparkEnv} at all, which is how a catalyst unit test gets the
 * entry's default.
 *
 * <p>The boundary that leaves, documented rather than discovered:
 * {@code spark.sql.codegen.varka.cache.maxEntries} is a static SQL conf, so a
 * {@code SparkSession.builder.config(...)} value reaches an executor only when that builder also
 * created the {@code SparkContext} - only then does it land in the {@code SparkConf} the
 * executors are launched with. On a session attached to an existing context,
 * {@code SQLConf.mergeNonStaticSQLConfigs} drops static keys, so the value never takes effect
 * anywhere, driver included. Setting it with {@code --conf} (or on the builder that creates the
 * context) is the supported way.
 *
 * <p>The instance and the watch are created on first use under a lock, once per JVM as a
 * {@code lazy val} gave; a creation that throws is retried by the next call, as a
 * {@code lazy val}'s was.
 */
public final class VarkaShapeCache {

  /**
   * The longest execution identity the side table stores; longer ones are abbreviated to exactly
   * this length, marker included. Operator, stage and the leading projection entries survive,
   * which is what the diagnostics join needs - and callers building an identity string need not
   * render more than this.
   */
  public static final int MAX_EXECUTION_IDENTITY_LENGTH =
      VarkaShapeCacheImpl.MAX_EXECUTION_IDENTITY_LENGTH;

  private VarkaShapeCache() {
  }

  // The instance and the watch are created on first use and, unlike a class initialiser, retried
  // if creating them threw: a bad capacity then surfaces as the same ordinary exception on every
  // call, which the callers' handlers treat as a failure of the cache and not of the JVM.
  private static VarkaShapeCacheImpl cache;
  private static Optional<VarkaCompilationWatch> watch;

  private static synchronized VarkaShapeCacheImpl cache() {
    if (cache == null) {
      cache = new VarkaShapeCacheImpl(maxEntries());
    }
    return cache;
  }

  /**
   * The compiled-size watch, started once per JVM and only when asked for. It hangs here rather
   * than anywhere else because this class is already the JVM-wide singleton on the emission path,
   * already reads a static conf the same way, and already owns the shape hashes the watch keys
   * on - and because a {@code RecordingStream} owns a thread, so starting one per session or per
   * query would be a bug rather than an inefficiency.
   *
   * <p>When the flag is off this stays empty, so no stream is opened, no thread is started and no
   * map is allocated: the cost of the feature to anyone who has not asked for it is a lock and a
   * null check.
   */
  private static synchronized Optional<VarkaCompilationWatch> watch() {
    if (watch == null) {
      watch = flag(StaticSQLConf.VARKA_COMPILATION_WATCH_ENABLED())
          ? Optional.of(VarkaCompilationWatch.start())
          : Optional.empty();
    }
    return watch;
  }

  private static int maxEntries() {
    return (Integer) conf(StaticSQLConf.VARKA_CACHE_MAX_ENTRIES());
  }

  private static boolean flag(ConfigEntry<Object> entry) {
    return (Boolean) conf(entry);
  }

  private static Object conf(ConfigEntry<Object> entry) {
    SparkEnv env = SparkEnv.get();
    return env != null ? env.conf().get(entry) : SQLConf.get().getConf(entry);
  }

  /**
   * How many distinct (shape, method, tier) keys have compiled to a materially different size
   * than they did earlier in this JVM; 0 when the watch is off, which is the default.
   */
  public static long compilationDivergences() {
    return watch().map(VarkaCompilationWatch::divergenceCount).orElse(0L);
  }

  /**
   * Whether the watch is running - off by configuration and unavailable JFR both read false.
   * Internal to Spark's SQL code and its tests; a Java class cannot say {@code private[sql]}.
   */
  public static boolean compilationWatchRunning() {
    return watch().map(VarkaCompilationWatch::isRunning).orElse(false);
  }

  /** The one rendering of the shape-named class name; every caller derives it here. */
  public static String classNameFor(String shapeHash) {
    return VarkaShapeCacheImpl.classNameFor(shapeHash);
  }

  /** The one rendering of the shape-named {@code SourceFile}; every caller derives it here. */
  public static String sourceFileFor(String shapeHash) {
    return VarkaShapeCacheImpl.sourceFileFor(shapeHash);
  }

  /** The shape's stable name fragment; see {@link VarkaShapeCacheImpl#shapeHash}. */
  public static String shapeHash(VarkaShapeKey key) {
    return VarkaShapeCacheImpl.shapeHash(key);
  }

  /**
   * Resolves the shape under the caller's context class loader - the loader the emitted bytes
   * link the engine's support classes through, and so an input to the entry's identity.
   */
  public static VarkaShapeLookup getOrEmit(VarkaShapeKey key, String execution) {
    // The watch has to be subscribed before the kernels it watches are compiled, and nothing on
    // the hot path would otherwise touch it: compilationDivergences is a reporting call, so
    // making the watch's first creation happen there would mean the watch only ever started for a
    // caller already asking what it had seen. Touching it here costs a lock and a null check
    // per lookup, and nothing else when the flag is off, since an empty Optional is what gets
    // memoised.
    watch();
    return cache().getOrEmit(Utils.getContextOrSparkClassLoader(), key, execution);
  }

  /**
   * Whether the emitter serves the shape, answered without defining a class; throws the decline
   * if not. The compiler's plan-time question (VARKA-237). Under Spark's testing flag the built
   * bytes are also verified, so the tests catch a class the executors could not define.
   */
  public static void admit(VarkaShapeKey key) {
    cache().admit(Utils.getContextOrSparkClassLoader(), key, Utils.isTesting());
  }

  public static List<String> executionsFor(String shapeHash) {
    return cache().executionsFor(shapeHash);
  }

  public static long hitCount() {
    return cache().hitCount();
  }

  public static long missCount() {
    return cache().missCount();
  }

  public static long buildCount() {
    return cache().buildCount();
  }

  public static long size() {
    return cache().size();
  }

  /**
   * Test hook, mirroring {@code CodeGenerator.invalidateCodegenCache}: the classes, the admitted
   * bytes and the remembered declines all go, so a count of builds taken after it starts from a
   * cold cache however often the JVM has compiled the shape before. Internal to Spark's SQL code
   * and its tests; a Java class cannot say {@code private[sql]}.
   */
  public static void invalidateAll() {
    cache().invalidateAll();
  }
}
