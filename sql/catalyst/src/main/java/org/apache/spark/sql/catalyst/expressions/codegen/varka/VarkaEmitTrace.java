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
 */
final class VarkaEmitTrace {
  int builds;
  int byteRegroups;
  int siteSplits;
  int siteRollbacks;
  int stageSplits;
  int exactFallbacks;
  int predictFallbacks;

  /** The grouping switches dropped, the fallback {@code emitCountingBuilds} reports. */
  int fallbacks() {
    return exactFallbacks + predictFallbacks;
  }
}
