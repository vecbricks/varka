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

package org.apache.spark.sql.execution;

import java.io.Serializable;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import scala.Option;

import org.apache.spark.internal.SparkLogger;
import org.apache.spark.internal.SparkLoggerFactory;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaErrors;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFamily;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaHostIsa;
import org.apache.spark.sql.internal.SQLConf;

/**
 * The emit settings a session chooses, resolved from its {@code SQLConf} once, when the columnar
 * rule builds an exec node - the node holds them, so a plan executed again after the conf changed
 * still emits what it was planned with - and carried to the factories and evaluators that emit
 * its kernels (VARKA-308). Each emit setting overrides the base options or, unset, leaves them
 * (the families switched off are not an emit setting and are passed beside the options instead):
 * a session that says nothing must not undo a variant a suite drives through the test hook, and a
 * session that sets a value, {@code false} included, must get it. The
 * executors cannot read the session's configuration, so the settings travel with the plan, and a
 * kernel's options are derived from them in one place, {@code VarkaColumnarToRowExec.emitOptions},
 * which the compiler asks at planning and the evaluator asks on the executor: the two must agree,
 * or the plan and the task could classify one projection differently.
 *
 * @param useAVX the {@code -XX:UseAVX} level a lowering is chosen for
 *        ({@code spark.sql.codegen.varka.emit.useAVX}), {@link VarkaEmitOptions#USE_AVX_UNKNOWN}
 *        to leave the base options' level. Never {@link #USE_AVX_FROM_HOST}: that request is
 *        resolved by {@link #of} on the driver, so the record the plan carries to the executors
 *        names the level the driver's host chose (VARKA-538)
 * @param exactRefusalStatus whether a method of several refusal reasons gives each its own mask
 *        ({@code spark.sql.codegen.varka.emit.exactRefusalStatus}, VARKA-304), null to leave the
 *        base options' setting
 * @param familiesOff the expression families the session has switched off
 *        ({@code spark.sql.codegen.varka.family.<name>.enabled} set to false, VARKA-318). Not an
 *        emit option - it changes which entries the compiler admits, never a kernel's bytes - so
 *        it is not laid over the options; it travels here because the compile at planning and the
 *        evaluator's compile in a task must decline alike, and every caller of the compiler passes
 *        it beside {@code VarkaColumnarToRowExec.emitOptions}.
 */
public record VarkaSessionEmit(
    int useAVX, @Nullable Boolean exactRefusalStatus, Set<VarkaFamily> familiesOff)
    implements Serializable {

  private static final long serialVersionUID = 2L;

  private static final SparkLogger LOG = SparkLoggerFactory.getLogger(VarkaSessionEmit.class);

  /**
   * The conf's value asking for the level to be resolved from the driver's JVM
   * ({@link VarkaHostIsa}): {@link #FALLBACK_LEVEL} where its 64-bit floating conversions fall
   * back to Java, the base options' level otherwise.
   */
  public static final int USE_AVX_FROM_HOST = -2;

  /**
   * The level a host whose conversions fall back resolves to: the one {@code VarkaMatrixRuns}
   * folds every fallback host into, so the shape key renders {@code |2|} as a session at level 2
   * does, and {@code convertsFallBack()} holds.
   */
  static final int FALLBACK_LEVEL = 2;

  /** Whether a resolution has met an unreadable feature list; it is logged once per JVM. */
  private static final AtomicBoolean UNREADABLE_LOGGED = new AtomicBoolean();

  /** The host a test plants in place of this JVM's, or null for the JVM's own. */
  @Nullable private static volatile VarkaHostIsa.Reading hostForTesting;

  /** A session that sets nothing: what a suite that builds an evaluator directly passes. */
  public static final VarkaSessionEmit DEFAULTS =
      new VarkaSessionEmit(VarkaEmitOptions.USE_AVX_UNKNOWN, null);

  public VarkaSessionEmit {
    // SQLConf checks the same on the conf; a record built another way is held to it too.
    if (useAVX < VarkaEmitOptions.USE_AVX_UNKNOWN) {
      throw VarkaErrors.illegalArgument("useAVX is a level from 0 or -1, got " + useAVX);
    }
    familiesOff = Set.copyOf(familiesOff);
  }

  /** The emit settings alone, with every family on. */
  public VarkaSessionEmit(int useAVX, @Nullable Boolean exactRefusalStatus) {
    this(useAVX, exactRefusalStatus, Set.of());
  }

  /** The session's settings, read from {@code conf}: the one place they are bound. */
  public static VarkaSessionEmit of(SQLConf conf) {
    VarkaHostIsa.Reading planted = hostForTesting;
    return of(conf, planted != null ? planted : VarkaHostIsa.host());
  }

  /** The same, with the host the level {@link #USE_AVX_FROM_HOST} resolves from given. */
  static VarkaSessionEmit of(SQLConf conf, VarkaHostIsa.Reading host) {
    Option<Object> exact = conf.varkaEmitExactRefusalStatus();
    var off = EnumSet.noneOf(VarkaFamily.class);
    for (VarkaFamily family : VarkaFamily.values()) {
      if (!conf.varkaFamilyEnabled(family)) {
        off.add(family);
      }
    }
    int level = conf.varkaEmitUseAVX();
    return new VarkaSessionEmit(level == USE_AVX_FROM_HOST ? resolve(host) : level,
        exact.isDefined() ? (Boolean) exact.get() : null, off);
  }

  /**
   * The level a session that asks for the host's gets: {@link #FALLBACK_LEVEL} where the host's
   * conversions fall back, {@link VarkaEmitOptions#USE_AVX_UNKNOWN} otherwise. A feature list
   * that could not be read leaves the conversion form, as before this setting existed, and says
   * so once in the driver's log, since nothing else would show that the host was never read.
   */
  static int resolve(VarkaHostIsa.Reading host) {
    if (!host.readable() && UNREADABLE_LOGGED.compareAndSet(false, true)) {
      LOG.warn("Varka could not read this JVM's CPU features (" + host.failure() + "), so a "
          + "64-bit division keeps the conversion form, which falls back to Java on a CPU "
          + "without AVX512DQ; the JVM needs "
          + "--add-exports=java.base/jdk.internal.vm.vector=ALL-UNNAMED");
    }
    return host.conversionsFallBack() ? FALLBACK_LEVEL : VarkaEmitOptions.USE_AVX_UNKNOWN;
  }

  /** Plants the host {@link #of(SQLConf)} resolves from; null restores this JVM's own. */
  static void setHostForTesting(@Nullable VarkaHostIsa.Reading host) {
    hostForTesting = host;
  }

  /** {@code base} with the session's settings laid over it, or {@code base} when none is set. */
  public VarkaEmitOptions apply(VarkaEmitOptions base) {
    if (useAVX == VarkaEmitOptions.USE_AVX_UNKNOWN && exactRefusalStatus == null) {
      return base;
    }
    var options = base.toBuilder();
    if (useAVX != VarkaEmitOptions.USE_AVX_UNKNOWN) {
      options.useAVX(useAVX);
    }
    if (exactRefusalStatus != null) {
      options.exactRefusalStatus(exactRefusalStatus);
    }
    return options.build();
  }
}
