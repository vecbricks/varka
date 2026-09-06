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
 * How the evaluator derives a kernel input that no child column holds directly (task 59). A
 * derived input is an int32 column computed per batch, before the kernel runs, from a child
 * column the kernel cannot read - a string - by the row engine's own function for it, so the
 * kernel sees a plain int input and the semantics are the row engine's by construction. The
 * first kind maps {@code next_day}'s weekday name to {@code dayOfWeek - 1}, the literal task
 * 33 folds at compile time when the name is a constant. ANSI mode is part of the kind rather
 * than of the batch because Spark fixes {@code NextDay.failOnError} when the expression is
 * built, and the two modes differ in what an unrecognised name does: a null lane, or a decline
 * to the row engine, which then raises its own error ({@link WeekdayLeaf}). The third kind
 * (task 61) maps {@code trunc}'s format to its level code ({@link TruncLevelLeaf}); it has no
 * ANSI twin because Spark's {@code TruncDate} has no error path - an unrecognised format is a
 * NULL result in either mode.
 */
public enum VarkaDerivedKind {
  /** {@code getDayOfWeekFromString(name) - 1}; an unrecognised name is a null lane. */
  WEEKDAY(false),
  /** The same map; an unrecognised name declines the batch so the row engine raises. */
  WEEKDAY_ANSI(true),
  /** {@code parseTruncLevel(format)} where that is a date level; anything else is a null lane. */
  TRUNC_LEVEL(false);

  /** Whether an unrecognised value declines the batch instead of nulling the lane. */
  public final boolean failOnError;

  VarkaDerivedKind(boolean failOnError) {
    this.failOnError = failOnError;
  }
}
