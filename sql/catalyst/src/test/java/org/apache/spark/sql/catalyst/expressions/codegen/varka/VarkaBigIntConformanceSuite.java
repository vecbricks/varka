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
 * `BIGINT` held to the conformance contract (VARKA-484): the seven checks of
 * {@link VarkaTypeConformance} over fixtures generated from the type's values, at the long lane.
 *
 * <p>In its own JVM, as the long-lane emitter suites are: under the option matrix's lane-pinning
 * configurations its kernels use an int species of a size the int-lane suites do not, and a
 * shared JVM refuses a second one ({@link VarkaSpeciesGuard}).
 */
@VarkaSuite(ownJvm = true)
public final class VarkaBigIntConformanceSuite extends VarkaTypeConformance {

  @Override
  protected ConformanceType type() {
    return integral("bigint", DataTypes.LongType, "BIGINT", "L", Long.MIN_VALUE,
        Long.MAX_VALUE);
  }
}
