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

/**
 * What one emission's size control did, counted for the suites (task 238): how many times the
 * class was built, and how many times each of the loop's reactions to a measurement ran - a group
 * halved on bytes, a group split on call sites, the call-site splits rolled back, a driver split
 * into stages, and the exact grouping or the prediction dropped because the class it made would
 * decline. The fuzzers add these up over a run so that a run which never reaches a mechanism is
 * seen rather than passing on shapes too small to test it. See {@code VarkaLoopEmitter.emit}.
 *
 * <p>Under {@code VarkaEmitOptions.planSize} the first build is the plan's, and a reaction to
 * its measurement is a <i>correction</i> of the plan (task 236): counted in the reaction's own
 * counter as well, and named in {@link #corrections} with the method, what the plan predicted
 * for it and what it measured, so the audit can list every shape the plan got wrong. A reaction
 * to a later build is the size loop as the last resort, which the counters alone record.
 */
final class VarkaEmitTrace {
  int builds;
  int byteRegroups;
  int siteSplits;
  int siteRollbacks;
  int stageSplits;
  int exactFallbacks;
  int predictFallbacks;
  /** A planned shape the emitter declined before building it, naming a cut for the compiler. */
  int plannedDeclines;
  /** A planned shape whose driver was split into stages before the first build. */
  int plannedStages;
  /** Each correction of the planned build, as "method: predicted P, measured M, limit L". */
  final java.util.List<String> corrections = new java.util.ArrayList<>();

  /**
   * Each reaction's count, under the name the cost audit's file gives it, in a fixed order: the
   * one place that names the counters, so a reaction added above is added here and nowhere else.
   */
  java.util.Map<String, Integer> reactions() {
    java.util.Map<String, Integer> named = new java.util.LinkedHashMap<>();
    named.put("byte regroups", byteRegroups);
    named.put("call-site splits", siteSplits);
    named.put("call-site rollbacks", siteRollbacks);
    named.put("stage splits", stageSplits);
    named.put("exact grouping fallbacks", exactFallbacks);
    named.put("prediction fallbacks", predictFallbacks);
    return named;
  }

  /** How many of the reactions corrected the planned build, under {@code planSize}. */
  int correctionCount() {
    return corrections.size();
  }

  /** The grouping switches dropped, the fallback {@code emitCountingBuilds} reports. */
  int fallbacks() {
    return exactFallbacks + predictFallbacks;
  }
}
