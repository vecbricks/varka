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

import java.util.Set;

/**
 * The session settings that decide which conjuncts and projection entries the compiler admits,
 * as one value every predicate entry point and the session's projection compiles take: the
 * families switched off (VARKA-318) and whether a filter's condition in a kernel driven at a
 * 64-bit lane, a column of any type read on the long or the {@code DOUBLE} lane, and a
 * projection's {@code BOOLEAN} output over a decimal column (VARKA-596) are refused on an x86
 * host whose 64-bit species have two lanes or fewer (VARKA-503, {@link VarkaKernelPlan#admit}).
 * Neither
 * is an emit option - neither changes a kernel's bytes -
 * and the compile at planning, EXPLAIN and the filter evaluator's compile in a task must admit
 * alike, so both travel with the plan in sql/core's session record, whose
 * {@code VarkaSessionEmit.admission()} every production caller passes. No predicate entry point
 * defaults it: a caller says which settings it compiles under, and a compile with no session
 * says {@link #NO_HOST_REFUSAL}. The projection's overloads that take the families alone, which
 * the catalyst suites and tools call, compile with no host refusal.
 *
 * @param familiesOff the expression families switched off
 * @param declineTwoLaneConditions whether a filter's 64-bit-lane condition, and a boolean output
 *        over a decimal column, are left to the row engine
 */
public record VarkaAdmission(Set<VarkaFamily> familiesOff, boolean declineTwoLaneConditions) {

  /**
   * Every family on and no host refusal: what a compile with no session - a catalyst suite, a
   * committed oracle ({@code coverage.json}, {@code emitted_bytes.json}, the golden corpus) -
   * compiles under, so its answers are the same on every machine.
   */
  public static final VarkaAdmission NO_HOST_REFUSAL = new VarkaAdmission(Set.of(), false);

  public VarkaAdmission {
    familiesOff = Set.copyOf(familiesOff);
  }
}
