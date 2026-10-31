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

import org.apache.spark.sql.types.DataTypes;

/**
 * `BOOLEAN` held to the conformance contract (VARKA-484): the seven checks of
 * {@link VarkaTypeConformance} over fixtures generated from the type's values.
 *
 * <p>A `BOOLEAN` column is a condition leaf (VARKA-331), a value through `CondValue`
 * (VARKA-460) and a projection's output column (VARKA-27), which its round trip stores; its casts
 * wait on VARKA-332, which the cast check names when it cancels.
 */
@VarkaSuite
public final class VarkaBooleanConformanceSuite extends VarkaTypeConformance {

  @Override
  protected ConformanceType type() {
    return new ConformanceType("bool", DataTypes.BooleanType, "BOOLEAN",
        v -> v != 0 ? "true" : "false", new long[] {0, 1}, new long[] {1, 0}, null);
  }
}
