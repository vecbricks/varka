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

package org.apache.spark.sql.varka.bench;

import java.util.List;

/**
 * The math surface (VARKA-633): one entry per {@code DOUBLE} math function the Varka compiler
 * fuses, so the surface driver can time each against a stock Spark distribution as it times the
 * date functions. Like {@link Surface} the list is data: the row that makes a function fuse
 * adds its line here, and every entry is held to {@code --expect-fused} on the fork, so a
 * function that does not fuse cannot be timed under Varka's name.
 *
 * <p>The nine are the trigonometric functions of VARKA-356, each lane passed to the method
 * Spark's own code calls ({@code Math} for {@code sin}, {@code cos} and {@code tan},
 * {@code StrictMath} for the inverse three), the reciprocals Java's {@code /} over the call.
 *
 * <p>The table is {@code varka_doubles} ({@code DateSurfaceBenchmark.TableShape#DOUBLES}):
 * {@code x} in {@code [-10, 10)} and {@code u} in {@code (-1, 1)}, the domain of {@code asin}
 * and {@code acos}, each with every 31st row null. Both are hashes of the row id, as in
 * {@code VarkaMathBaselineBenchmark}, so every batch reaches both ends of its range and no value
 * is NaN, infinite or huge.
 */
public final class Maths {

  public static final List<Surface.Entry> ENTRIES = List.of(
      Surface.Entry.projection("sin(x)"),
      Surface.Entry.projection("cos(x)"),
      Surface.Entry.projection("tan(x)"),
      Surface.Entry.projection("asin(u)"),
      Surface.Entry.projection("acos(u)"),
      Surface.Entry.projection("atan(x)"),
      Surface.Entry.projection("sec(x)"),
      Surface.Entry.projection("csc(x)"),
      Surface.Entry.projection("cot(x)"));

  private Maths() {}
}
