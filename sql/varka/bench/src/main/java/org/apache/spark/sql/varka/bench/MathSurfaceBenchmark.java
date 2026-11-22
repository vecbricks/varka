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

import java.io.IOException;

/**
 * The math surface: {@link Maths#ENTRIES} over {@code varka_doubles}, through the same driver
 * as the date surface, so the residency guard, the fixed-share rule, the {@code EXPLAIN} check,
 * the checksums and the provenance block apply unchanged. Selected by
 * {@code dev/varka_bench_surface.sh --benchmark math}; its files are
 * {@code sql/varka/bench/benchmarks/MathSurface-<label>-results.txt} (VARKA-633).
 */
public final class MathSurfaceBenchmark {

  public static void main(String[] argv) throws IOException {
    DateSurfaceBenchmark.run(argv, Maths.ENTRIES, "math", "VarkaMathSurface",
        DateSurfaceBenchmark.TableShape.DOUBLES);
  }

  private MathSurfaceBenchmark() {}
}
