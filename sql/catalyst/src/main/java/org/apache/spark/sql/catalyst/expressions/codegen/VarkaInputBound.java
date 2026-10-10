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
package org.apache.spark.sql.catalyst.expressions.codegen;

/**
 * A closed interval every live value of kernel input {@code inputIndex} (a position in
 * {@code inputOrdinals}) must lie in for the kernel's answer to be Spark's. The compiler records
 * one where it rewrote an expression whose row-engine form throws outside the bound - the first is
 * {@code CAST(i AS INTERVAL DAY)}, which overflows past {@code VarkaChrono.INTERVAL_DAY_LIMIT_DAYS}
 * days - and the evaluator checks it per batch before the kernel runs, declining the batch to the
 * row engine, which then raises the error, when a live lane is outside. A bound is a property of
 * the compiled plan, not of the emitted bytes: two projections with the same IR and different
 * bounds share a kernel class.
 *
 * <p>Internal to Spark SQL; public for {@code sql/core}.
 */
public record VarkaInputBound(int inputIndex, int lo, int hi) { }
