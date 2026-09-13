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

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.LongAdder;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.UncheckedExecutionException;

import org.apache.spark.internal.SparkLogger;
import org.apache.spark.internal.SparkLoggerFactory;
import org.apache.spark.network.util.JavaUtils;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader;
import org.apache.spark.util.SparkStringUtils$;

/**
 * The bounded cross-task cache of loaded fused-kernel classes (task 18, {@code
 * PLAN_MILESTONE_3.md} 2.1). Task 14's diagnosis set its design: emission costs ~80 us and was
 * never the problem, but a re-defined class is a new class to HotSpot and re-pays the whole tier
 * ladder - a fixed 13-50 ms per task. Only reusing the loaded class amortises that, so this is an
 * LRU over classes each held by its own {@link VarkaGeneratedClassLoader}, released (and so
 * unloadable) on eviction rather than on task end. Metaspace is bounded by cache size instead of
 * task lifetime - a weaker guarantee than milestone 1's per-task unloading, proven the same way
 * ({@code VarkaShapeCacheSuite}, weak references against eviction).
 *
 * <p><b>Correctness before performance.</b> A wrong hit returns wrong results and the ghost
 * fallback cannot catch it - it catches failures, not silently different answers. The key is
 * therefore {@link VarkaShapeKey}, derived structurally from the same records the emitter walks,
 * never assembled by hand at a call site; the differential suites run warm as well as cold. Since
 * task 23 the key covers <i>every</i> byte-affecting emit input, {@link VarkaEmitOptions}
 * included, so there is no longer a class of emission this cache has to refuse to serve - the
 * guard stack that used to do the refusing is gone, and with it the three races inside it.
 *
 * <p><b>The loader is part of the key.</b> Each entry's generated loader parents the class loader
 * its caller passed in - in production the context class loader of the task that emitted it, which
 * {@code VarkaShapeCache} resolves (as the per-task loaders had it): the emitted bytes call the
 * engine's {@code VarkaVectorSupport}, which in the documented deployment arrives via
 * {@code --jars} and is visible only through the context loader - the loader of
 * {@link VarkaFusedKernel} (catalyst, app classpath) cannot see it. That makes the parent an input
 * to linkage, so it rides the key by identity: with one executor-wide context loader nothing
 * changes (one class per shape), and under session artifact isolation each session's loader gets
 * its own entry rather than linking through another session's - possibly closed - chain. A closed
 * session's entries are not released eagerly (nothing here observes session close); they age out
 * of the LRU, so the retained loaders are bounded by the cache capacity like everything else.
 *
 * <p><b>Naming and telemetry.</b> The class is named by its shape ({@link #classNameFor}:
 * {@code VarkaFusedProjection_<hash>}, 16 hex chars of SHA-256 over the key's canonical rendering -
 * {@code VarkaVectorIR.canonical}, hand-pinned so the hash never rides an unspecified
 * {@code Record.toString} format), {@code SourceFile} carries the same name, and the
 * {@link VarkaDebugInfo} attribute's plan fragment carries {@code shape <hash>} - the bytes
 * describe the shape, which is exactly what is shared. The map is keyed on the full structural
 * key, so a hash collision cannot cause a wrong hit; it could only give two distinct shapes one
 * <i>name</i> (their loaders keep the runtime classes distinct). The per-execution identity that
 * used to be baked into the bytes - operator, stage, the projection list - lives in a bounded side
 * table keyed by the hash, recorded (truncated) on every lookup at every capacity,
 * {@code maxEntries} = 0 included, so the diagnostics join {@link #executionsFor} keeps working
 * when sharing is off.
 *
 * <p><b>Concurrency and failure.</b> Tasks racing on one shape are serialized by the
 * single-flight gate below ({@link #loadOnce}): the winner emits once, and a cancelled or failed
 * emit fails only its own task - the co-waiters retry for themselves instead of inheriting the
 * failure into their ghost fallbacks. That is the SPARK-43300 discipline this class used to get
 * from {@code NonFateSharingCache}, reimplemented here for a reason that is a constraint on the
 * whole Java migration rather than a preference: Maven shades {@code core}, relocating
 * {@code com.google.common} to {@code org.sparkproject.guava}, so that class's constructor
 * arrives as {@code NonFateSharingCache(org.sparkproject.guava.cache.Cache)}. Scala never sees
 * it - scalac reads the unrelocated Scala pickle - but javac reads the relocated descriptor and
 * cannot pass it a catalyst-side {@code com.google.common} cache. Guava types must therefore not
 * cross the core boundary from Java at all; SPARK-44064 records the same trap upstream, and
 * {@code CodeGenerator} and {@code ProtobufUtils} both avoid it by using Guava-free overloads.
 * Neither of those overloads admits a removal listener, which this cache needs to release evicted
 * loaders, so the gate is Varka's own. Guava wraps what the callable throws
 * ({@link ExecutionException}, {@link UncheckedExecutionException}, {@link ExecutionError});
 * {@link #getOrEmit} unwraps to the cause, because the wrapper would defeat the evaluator's
 * fatal-error discipline - an {@code OutOfMemoryError} inside emit must reach the task as itself,
 * and an interrupt must cancel the task. Eviction while a task still runs the class is safe:
 * {@code release()} only drops the loader's registry, and the task's strong references keep the
 * class alive until it completes (the owner-side contract the engine's {@code VarkaClassLoader}
 * documents).
 *
 * <p>With {@code maxEntries} = 0 the same single path degenerates to the per-task class lifecycle:
 * Guava evicts each entry immediately after loading it, the removal listener releases the loader,
 * and the task's strong references carry the class to task end - the pre-task-18 unload contract,
 * with no second code path to keep in step. (Racing lookups of one shape may still share the one
 * in-flight load; that is fine - nothing is retained either way.)
 *
 * <p><b>What is deliberately not here</b> (task 23, which split this class out of the Scala
 * {@code VarkaShapeCache}): nothing that reads Spark's configuration or environment. Capacity and
 * the parent class loader arrive as plain values, so this class has one behaviour per constructed
 * instance rather than one per whichever thread happened to touch a singleton first - which is
 * what made the old lazily-sized singleton nondeterministic on an executor. {@code VarkaShapeCache}
 * is the facade that resolves both and owns the JVM-wide instance.
 */
public final class VarkaShapeCacheImpl {

  private static final SparkLogger LOG =
      SparkLoggerFactory.getLogger(VarkaShapeCacheImpl.class);

  /**
   * The longest execution identity the side table stores; longer ones are abbreviated to exactly
   * this length, marker included. Operator, stage and the leading projection entries survive,
   * which is what the diagnostics join needs - and callers building an identity string need not
   * render more than this.
   */
  public static final int MAX_EXECUTION_IDENTITY_LENGTH = 256;

  /** How many per-execution identities the side table keeps per shape, oldest evicted first. */
  private static final int MAX_EXECUTIONS_PER_SHAPE = 8;

  /**
   * The full cache key: the shape, plus the identity of the class loader the entry's generated
   * loader parents. The parent loader is an input to how the class links (it resolves the engine's
   * support classes), so two contexts with different loaders must not share an entry - under
   * executor-side session artifact isolation each session has its own context loader, and a class
   * linked through session A's chain must not serve session B. In the documented {@code --jars}
   * deployment the executor has one context loader, so this key degenerates to the shape alone.
   * {@code ClassLoader} has identity equality, which is exactly the sharing rule wanted.
   */
  private record LoaderShapeKey(ClassLoader parent, VarkaShapeKey shape) {
  }

  private final int maxEntries;

  private final LongAdder hits = new LongAdder();
  private final LongAdder misses = new LongAdder();

  private final Cache<LoaderShapeKey, VarkaShapeEntry> cache;

  // The single-flight gate: one in-flight load per key, so only one task emits a given shape and
  // a failed emit is not shared. Entries live only for the duration of a load, so the map is
  // empty whenever nothing is being emitted - there is no per-key lock object to reclaim.
  private final ConcurrentHashMap<LoaderShapeKey, CompletableFuture<VarkaShapeEntry>> inFlight =
      new ConcurrentHashMap<>();

  // The side table: shape hash -> the most recent execution identities that used the shape.
  // Bounded three times over - entries here, identities per entry, characters per identity -
  // because it is diagnostics, not bookkeeping: it answers "which operators ran
  // VarkaFusedProjection_<hash>?" for a name seen in a profile, a class dump or a JFR event,
  // without that identity riding the shared bytes.
  // Floored at 64 shapes so the diagnostics join works at every capacity: with maxEntries = 0
  // the classes still carry only their shape name, and a table sized off the (zero) class
  // capacity would evict live shapes' identities mid-query. 64 shapes of 8 truncated
  // identities is at most a few hundred KB.
  private final Cache<String, LinkedHashSet<String>> executions;

  public VarkaShapeCacheImpl(int maxEntries) {
    if (maxEntries < 0) {
      throw new IllegalArgumentException("maxEntries must not be negative: " + maxEntries);
    }
    this.maxEntries = maxEntries;
    Cache<LoaderShapeKey, VarkaShapeEntry> classes = CacheBuilder.newBuilder()
        .maximumSize(maxEntries)
        // Guava swallows a throwing listener; release() cannot throw, and must not be more
        // than "stop retaining": running tasks still hold the class until they complete.
        .<LoaderShapeKey, VarkaShapeEntry>removalListener(n -> n.getValue().loader().release())
        .build();
    this.cache = classes;
    this.executions = CacheBuilder.newBuilder()
        .maximumSize(Math.max((long) maxEntries * 4, 64))
        .build();
  }

  public int maxEntries() {
    return maxEntries;
  }

  /**
   * Returns the loaded class for the shape under {@code parent}, emitting and defining it if no
   * live entry holds it, and records {@code execution} (the caller's per-execution identity,
   * truncated) in the side table either way. With {@code maxEntries} = 0 every lookup emits: the
   * entry is evicted (and its loader released) as it is loaded, restoring the per-task class
   * lifecycle through this same path.
   */
  public VarkaShapeLookup getOrEmit(ClassLoader parent, VarkaShapeKey key, String execution) {
    LoaderShapeKey loaderKey = new LoaderShapeKey(parent, key);
    // A one-element array, not a local: the loading callable has to report back whether it ran,
    // and a lambda can only capture something effectively final.
    boolean[] emitted = {false};
    VarkaShapeEntry entry = loadOnce(loaderKey, emitted);
    // Bounded once, for both consumers: the side-table entry and the JFR event must carry
    // the same string or the advertised join between them fails on anything abbreviated -
    // and the event payload must not be unbounded (the evaluator's identity builder checks
    // the bound only before appending, so a single long entry can overshoot it by a lot).
    String boundedExecution =
        SparkStringUtils$.MODULE$.abbreviate(execution, MAX_EXECUTION_IDENTITY_LENGTH);
    recordExecution(entry.shapeHash(), boundedExecution);
    if (emitted[0]) {
      misses.increment();
    } else {
      hits.increment();
    }
    // Task 22: the counters' JFR twin - one event per task-level resolution, carrying the
    // per-execution identity that must not ride the shared bytes.
    VarkaCacheLookupEvent lookupEvent = new VarkaCacheLookupEvent();
    if (lookupEvent.isEnabled()) {
      lookupEvent.shapeHash = entry.shapeHash();
      lookupEvent.hit = !emitted[0];
      lookupEvent.execution = boundedExecution;
      lookupEvent.commit();
    }
    return new VarkaShapeLookup(entry, !emitted[0]);
  }

  /**
   * Returns the entry for {@code key}, emitting it at most once across racing callers.
   *
   * <p>The gate is a future per in-flight key rather than a lock per key: the winner is whoever
   * installs its future with {@code putIfAbsent}, and losers wait on that future instead of on a
   * monitor. Failure is where the discipline lives - a loser whose winner failed does <i>not</i>
   * inherit the failure (that is the fate sharing SPARK-43300 is about, and it would turn one
   * task's cancellation into unrelated tasks' ghost fallbacks); it loops and emits for itself, so
   * every caller fails only for its own reason, as if the callers had arrived one at a time.
   *
   * <p>The wait is {@link CompletableFuture#get()} rather than {@code join()} on purpose:
   * {@code join} is uninterruptible, and this class's contract is that an interrupt cancels the
   * task promptly. Waiting is bounded by one emission (~80 us) in any case.
   *
   * <p>The two exit orderings differ, and the difference matters. On success the winner
   * completes its future before unregistering it, so a caller arriving in that window is handed
   * the value instead of emitting again. On failure it unregisters <i>first</i> and completes
   * second: a loser is woken by the completion and retries at once, and if the dead future were
   * still registered its {@code putIfAbsent} would hand that same failure straight back - a spin,
   * allocating a future and filling a stack trace per pass, until the winner was rescheduled to
   * run its next statement. Unregistering first means the woken loser either installs its own
   * future or joins a live successor's. {@code remove(key, mine)} is value-conditional either
   * way: a retrying loser must never remove a successor's future.
   */
  private VarkaShapeEntry loadOnce(LoaderShapeKey key, boolean[] emitted) {
    while (true) {
      CompletableFuture<VarkaShapeEntry> mine = new CompletableFuture<>();
      CompletableFuture<VarkaShapeEntry> winner = inFlight.putIfAbsent(key, mine);
      if (winner == null) {
        VarkaShapeEntry entry;
        try {
          entry = cache.get(key, () -> {
            emitted[0] = true;
            return emit(key);
          });
        } catch (Throwable t) {
          // Unregister before completing, so a loser woken by the failure retries into an empty
          // slot rather than back into this dead future (see the ordering note above), then hand
          // this caller its own cause, unwrapped.
          Throwable cause = unwrapGuava(t);
          inFlight.remove(key, mine);
          mine.completeExceptionally(cause);
          throw sneakyThrow(cause);
        }
        mine.complete(entry);
        inFlight.remove(key, mine);
        return entry;
      }
      try {
        return winner.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw sneakyThrow(e);
      } catch (ExecutionException e) {
        // The winner failed. Do not inherit its fate: emit for ourselves on the next pass.
        emitted[0] = false;
      }
    }
  }

  /**
   * The cause behind Guava's loading wrappers. It must reach the evaluator's {@code isCatchable}
   * test as itself - an {@code OutOfMemoryError} inside emit has to fail the task rather than be
   * counted as a kernel failure, and an interrupt has to cancel it - so this unwraps rather than
   * re-wraps. {@link ExecutionException} is the checked one of the three.
   */
  private static Throwable unwrapGuava(Throwable t) {
    if (t instanceof ExecutionError || t instanceof UncheckedExecutionException
        || t instanceof ExecutionException) {
      Throwable cause = t.getCause();
      return cause != null ? cause : t;
    }
    return t;
  }

  /** The recorded execution identities for a shape hash, most recent last; empty if unknown. */
  public List<String> executionsFor(String shapeHash) {
    List<String> snapshot = new ArrayList<>();
    // Inside compute, so the copy is taken under the same per-bin lock recordExecution mutates
    // the set under. The set itself is not thread-safe and is never published outside these two.
    // Returning the same instance takes Guava's "no change in weight" path, which calls
    // recordWrite - so this read bumps the entry's recency in the bounded side table. Accepted:
    // reordering eviction in a diagnostics table (no removal listener, no correctness role) is
    // the price of an atomic copy, and the alternative - getIfPresent plus an unsynchronized
    // walk of a non-thread-safe set - is the race the task-18 debt sweep removed.
    executions.asMap().computeIfPresent(shapeHash, (h, set) -> {
      snapshot.addAll(set);
      return set;
    });
    // Not List.copyOf: it rejects nulls, and a null identity is storable (SparkStringUtils
    // .abbreviate passes null through). No production caller passes one - the evaluator's
    // identity is always a StringBuilder result - but the Scala this replaced tolerated it, and
    // getOrEmit is public now, so the guard the port dropped stays dropped rather than becoming
    // an NPE in a diagnostics read.
    return Collections.unmodifiableList(snapshot);
  }

  public long hitCount() {
    return hits.sum();
  }

  public long missCount() {
    return misses.sum();
  }

  public long size() {
    return cache.size();
  }

  /** Test hook: drops every entry (releasing the loaders) and the side table. */
  public void invalidateAll() {
    cache.invalidateAll();
    executions.invalidateAll();
  }

  /**
   * Everything before the shape hash in a generated class name. Task 50 matches on it to tell a
   * Varka kernel's compilation from every other method the JVM compiles, so it is a constant
   * here rather than a literal repeated there.
   */
  public static final String CLASS_NAME_PREFIX =
      "org.apache.spark.sql.varka.execution.VarkaFusedProjection_";

  /** The one rendering of the shape-named class name; every caller derives it here. */
  public static String classNameFor(String shapeHash) {
    return CLASS_NAME_PREFIX + shapeHash;
  }

  /** The one rendering of the shape-named {@code SourceFile}; every caller derives it here. */
  public static String sourceFileFor(String shapeHash) {
    return "VarkaFusedProjection_" + shapeHash + ".java";
  }

  /**
   * The shape's stable name fragment: 16 hex characters of SHA-256 over the key's canonical
   * rendering ({@code VarkaVectorIR.canonical}, hand-pinned - not {@code Record.toString}, whose
   * format no JDK promises). A pure function of the key: equal keys hash equal on every JVM, so
   * one shape carries one class name across executors, mixed-JDK clusters, restarts and class
   * dumps. Computed on the miss path only; a hit reads the entry's stored hash.
   *
   * <p>{@link VarkaEmitOptions#canonical()} is empty for the defaults, so a production hash is
   * byte-identical to what it was before options entered the key - which is what makes
   * the two committed hashes in {@code VarkaShapeCacheSuite} a valid oracle for that migration.
   * A non-default variant renders, and so gets its own name: the execution side table is keyed on
   * the hash alone while the map is keyed on the full key, so options that reached one but not
   * the other would merge two variants' execution identities.
   */
  public static String shapeHash(VarkaShapeKey key) {
    StringBuilder canonical = new StringBuilder();
    for (VarkaVectorIR output : key.outputs()) {
      canonical.append(VarkaVectorIR.canonical(output)).append('\n');
    }
    canonical.append(key.numInputs()).append('|').append(key.numLiterals());
    canonical.append(key.options().canonical());
    return JavaUtils.sha256Hex(canonical.toString()).substring(0, 16);
  }

  private VarkaShapeEntry emit(LoaderShapeKey loaderKey) {
    VarkaShapeKey key = loaderKey.shape();
    String hash = shapeHash(key);
    String className = classNameFor(hash);
    String sourceFile = sourceFileFor(hash);
    // Task 22: the emission event times the Class-File walk plus the define - the whole miss
    // cost minus the lookup - identified by shape only (the class is shared).
    VarkaEmissionEvent emissionEvent = new VarkaEmissionEvent();
    emissionEvent.begin();
    byte[] bytes = VarkaLoopEmitter.emit(className, key.outputs(), key.numInputs(),
        key.numLiterals(), sourceFile, "shape " + hash, key.options());
    VarkaGeneratedClassLoader loader = new VarkaGeneratedClassLoader(loaderKey.parent());
    Class<?> klass = loader.defineGeneratedClass(className, bytes);
    emissionEvent.end();
    if (emissionEvent.shouldCommit()) {
      emissionEvent.shapeHash = hash;
      emissionEvent.className = className;
      emissionEvent.numOutputs = key.outputs().size();
      emissionEvent.numInputs = key.numInputs();
      emissionEvent.numLiterals = key.numLiterals();
      emissionEvent.byteCount = bytes.length;
      emissionEvent.commit();
    }
    LOG.debug("Emitted and defined {} for shape {}", className, hash);
    // Resolved once per shape rather than once per task: newKernel runs on every FusedRunner.
    Constructor<?> constructor;
    try {
      constructor = klass.getConstructor();
    } catch (NoSuchMethodException e) {
      throw sneakyThrow(e);
    }
    return new VarkaShapeEntry(loader, klass, bytes, hash, constructor);
  }

  /**
   * Records one already-bounded identity (the caller abbreviates once, for this table and the
   * lookup event alike) against the shape hash.
   *
   * <p>One atomic remapping, which is what makes it correct: {@code compute} holds the bin lock
   * across the whole update, so the set cannot be evicted between reading it and writing to it -
   * the race the hand-rolled {@code getIfPresent}/{@code putIfAbsent} retry loop this replaced
   * existed to converge on (task 23, sweeping the task-18 debt register).
   */
  private void recordExecution(String hash, String identity) {
    executions.asMap().compute(hash, (h, existing) -> {
      LinkedHashSet<String> set = existing == null ? new LinkedHashSet<>() : existing;
      // Re-adding moves nothing in a LinkedHashSet; remove first so recency order holds.
      set.remove(identity);
      set.add(identity);
      while (set.size() > MAX_EXECUTIONS_PER_SHAPE) {
        var it = set.iterator();
        it.next();
        it.remove();
      }
      return set;
    });
  }

  /**
   * Throws {@code t} as itself, checked or not. The cache has two places that must rethrow a
   * cause rather than wrap it - Guava's wrappers around a failed emit, and a reflective failure
   * in {@link VarkaShapeEntry#newKernel()} - because the evaluator's {@code isCatchable} test
   * decides whether the task falls back or dies from the exception's own type. Wrapping would
   * turn an {@code OutOfMemoryError} or an interrupt into an ordinary kernel failure.
   */
  @SuppressWarnings("unchecked")
  static <E extends Throwable> RuntimeException sneakyThrow(Throwable t) throws E {
    throw (E) t;
  }
}
