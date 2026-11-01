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

import javax.annotation.Nullable;

import scala.Option;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaErrors;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFamily;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelPlan;
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
 *        to leave the base options' level
 * @param exactRefusalStatus whether a method of several refusal reasons gives each its own mask
 *        ({@code spark.sql.codegen.varka.emit.exactRefusalStatus}, VARKA-304), null to leave the
 *        base options' setting
 * @param familiesOff the expression families the session has switched off
 *        ({@code spark.sql.codegen.varka.family.<name>.enabled} set to false, VARKA-318). Not an
 *        emit option - it changes which entries the compiler admits, never a kernel's bytes - so
 *        it is not laid over the options; it travels here because the compile at planning and the
 *        evaluator's compile in a task must decline alike, and every caller of the compiler passes
 *        it beside {@code VarkaColumnarToRowExec.emitOptions}.
 * @param declineTwoLaneConditions whether a filter's condition that compares at the long lane
 *        is left to the row engine, as an x86 host whose long species has two lanes needs
 *        ({@code spark.sql.codegen.varka.declineTwoLaneConditions}, VARKA-503; unset, the host's
 *        answer, {@link VarkaKernelPlan#HOST_TWO_LANE_MASKS_FALL_BACK}). Not an emit option, for
 *        the reason the families off are not: it decides which conjuncts the predicate compiler
 *        admits, never a kernel's bytes, and it travels with the plan so that the compile at
 *        planning and the filter evaluator's on an executor decline alike.
 */
public record VarkaSessionEmit(
    int useAVX, @Nullable Boolean exactRefusalStatus, Set<VarkaFamily> familiesOff,
    boolean declineTwoLaneConditions)
    implements Serializable {

  private static final long serialVersionUID = 3L;

  /**
   * A session that sets nothing, on a host that refuses nothing: what a suite that builds an
   * evaluator directly passes, so its answers do not depend on the machine it runs on.
   */
  public static final VarkaSessionEmit DEFAULTS =
      new VarkaSessionEmit(VarkaEmitOptions.USE_AVX_UNKNOWN, null);

  public VarkaSessionEmit {
    // SQLConf checks the same on the conf; a record built another way is held to it too.
    if (useAVX < VarkaEmitOptions.USE_AVX_UNKNOWN) {
      throw VarkaErrors.illegalArgument("useAVX is a level from 0 or -1, got " + useAVX);
    }
    familiesOff = Set.copyOf(familiesOff);
  }

  /** The emit settings alone, with every family on and no two-lane refusal. */
  public VarkaSessionEmit(int useAVX, @Nullable Boolean exactRefusalStatus) {
    this(useAVX, exactRefusalStatus, Set.of(), false);
  }

  /** The session's settings, read from {@code conf}: the one place they are bound. */
  public static VarkaSessionEmit of(SQLConf conf) {
    Option<Object> exact = conf.varkaEmitExactRefusalStatus();
    var off = EnumSet.noneOf(VarkaFamily.class);
    for (VarkaFamily family : VarkaFamily.values()) {
      if (!conf.varkaFamilyEnabled(family)) {
        off.add(family);
      }
    }
    Option<Object> twoLane = conf.varkaDeclineTwoLaneConditions();
    return new VarkaSessionEmit(conf.varkaEmitUseAVX(),
        exact.isDefined() ? (Boolean) exact.get() : null, off,
        twoLane.isDefined() ? (Boolean) twoLane.get()
            : VarkaKernelPlan.HOST_TWO_LANE_MASKS_FALL_BACK);
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
