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
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/**
 * JFR event timing one fused-kernel emission. A defined emission ({@link #defined}) times the
 * class define, plus the Class-File walk where the lookup builds the bytes itself - the whole miss
 * path of {@code VarkaShapeCache} minus the lookup; an admission (task 237) times the walk alone,
 * at planning, for a shape whose class a later lookup defines from those bytes. Fires
 * only while a JFR recording has the event enabled; every field names the shape, never a
 * per-execution identity, because the emitted class is shared - join back to
 * operators through {@code VarkaShapeCache.executionsFor} or the {@code VarkaCacheLookupEvent}
 * stream. {@code jdk.jfr} is a default root module, so no build or deployment flag is needed.
 */
@Name("org.apache.spark.sql.varka.KernelEmission")
@Label("Varka Kernel Emission")
@Category("Varka")
public final class VarkaEmissionEvent extends Event {

  @Label("Shape Hash")
  public String shapeHash;

  @Label("Class Name")
  public String className;

  @Label("Fused Outputs")
  public int numOutputs;

  @Label("Kernel Inputs")
  public int numInputs;

  @Label("Literal Slots")
  public int numLiterals;

  @Label("Class Bytes")
  public int byteCount;

  @Label("Defined")
  @Description("Whether the class was defined; false for a plan-time admission, which only builds")
  public boolean defined;
}
