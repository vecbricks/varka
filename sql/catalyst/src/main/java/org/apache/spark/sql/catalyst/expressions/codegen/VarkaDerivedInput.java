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

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDerivedKind;

/**
 * A kernel input the evaluator derives per batch rather than reads: kernel input
 * {@code inputIndex} (a position in {@code inputOrdinals}, whose entry there is
 * {@code sourceOrdinal}) is the int32 column {@code kind} computes from child column
 * {@code sourceOrdinal} - the first kind maps {@code next_day}'s weekday names to
 * {@code dayOfWeek - 1} - before the kernel runs. Like a bound, a property of the compiled plan and
 * not of the emitted bytes: the kernel sees an int input.
 *
 * <p>Internal to Spark SQL; public for {@code sql/core}.
 */
public record VarkaDerivedInput(int inputIndex, int sourceOrdinal, VarkaDerivedKind kind) {
  public VarkaDerivedInput {
    java.util.Objects.requireNonNull(kind, "kind");
  }

  private static final VarkaDerivedKind[] KINDS = VarkaDerivedKind.values();

  /**
   * The key a derived input is interned under in the compiler's input table beside the child
   * ordinals: negative, so it can collide with no ordinal, and one per (column, kind), so two
   * {@code next_day} over the same weekday column share one leaf. The table's mark-and-truncate
   * discipline rolls it back with the plain columns when its entry declines.
   */
  public static int key(int sourceOrdinal, VarkaDerivedKind kind) {
    return -1 - (sourceOrdinal * KINDS.length + kind.ordinal());
  }

  /** Whether an input-table key names a derived input rather than a child ordinal. */
  public static boolean isKey(int key) {
    return key < 0;
  }

  /** The child ordinal a derived key was made from. */
  public static int sourceOrdinal(int key) {
    return (-1 - key) / KINDS.length;
  }

  /** The kind a derived key was made from. */
  public static VarkaDerivedKind kind(int key) {
    return KINDS[(-1 - key) % KINDS.length];
  }
}
