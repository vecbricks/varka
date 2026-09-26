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

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/**
 * JFR event for one kernel warm-up ({@link VarkaKernelWarmup}), from the moment its thread took
 * the job to its verdict, so the event's duration is how long the shape's batches waited on the
 * row path once the warm-up began. Fires only while a recording has the event enabled.
 */
@Name("org.apache.spark.sql.varka.KernelWarmup")
@Label("Varka Kernel Warm-up")
@Category("Varka")
public final class VarkaKernelWarmupEvent extends Event {

  @Label("Shape Hash")
  public String shapeHash;

  /** {@code COMPILED}, or {@code RELEASED} when the warm-up stopped without seeing the compile. */
  @Label("Outcome")
  public String outcome;

  @Label("Kernel Calls")
  public int calls;

  @Label("Rows Run")
  public long rows;
}
