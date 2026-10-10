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
 * Why one entry could not be fused: the answer to "why didn't my projection fuse?". {@code reason}
 * is the vocabulary term - the same string the exec nodes' verbose {@code EXPLAIN} and debug logs
 * print - and {@code expr} names the offending expression, the innermost one that actually failed
 * rather than the whole entry.
 *
 * <p>Internal to Spark SQL; public for {@code sql/core}.
 */
public record VarkaDecline(String reason, String expr) {
  public VarkaDecline {
    java.util.Objects.requireNonNull(reason, "reason");
    java.util.Objects.requireNonNull(expr, "expr");
  }

  @Override
  public String toString() {
    return reason + ": " + expr;
  }
}
