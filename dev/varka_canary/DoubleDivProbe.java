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

import jdk.incubator.vector.*;

/**
 * The exact Vector API calls a double-lane division needs, settled before any bytecode is
 * emitted for it (VARKA-88, step 2). An int vector of N lanes converts into two double vectors
 * of N/2 lanes each - expanding parts 0 and 1 - is divided there, and converts back with
 * contracting parts 0 and -1, which is the half the plan had wrong in its first draft.
 *
 * The question this answers is the join: what the two contracted halves hold, and how they
 * recombine into one int vector. Run:
 *   javac --add-modules jdk.incubator.vector -d /tmp/p dev/varka_canary/DoubleDivProbe.java
 *   java --add-modules jdk.incubator.vector -cp /tmp/p DoubleDivProbe
 */
public class DoubleDivProbe {
  static final VectorSpecies<Integer> IS = IntVector.SPECIES_PREFERRED;
  static final VectorSpecies<Double> DS =
      VectorSpecies.of(double.class, VectorShape.forBitSize(IS.vectorBitSize()));

  public static void main(String[] args) {
    System.out.println("int species  " + IS + "  lanes " + IS.length()
        + "  bits " + IS.vectorBitSize());
    System.out.println("double species " + DS + "  lanes " + DS.length()
        + "  bits " + DS.vectorBitSize());

    int d = 146097;
    int[] src = new int[IS.length()];
    for (int i = 0; i < src.length; i++) {
      src[i] = i * d + (i % 3) * 7;         // multiples and non-multiples, mixed
    }
    IntVector v = IntVector.fromArray(IS, src, 0);

    // Expanding: parts 0..M-1. Contracting: parts -M+1..0.
    DoubleVector lo = (DoubleVector) v.convertShape(VectorOperators.I2D, DS, 0);
    DoubleVector hi = (DoubleVector) v.convertShape(VectorOperators.I2D, DS, 1);
    DoubleVector qlo = lo.div((double) d);
    DoubleVector qhi = hi.div((double) d);
    IntVector back0 = (IntVector) qlo.convertShape(VectorOperators.D2I, IS, 0);
    IntVector backM1 = (IntVector) qhi.convertShape(VectorOperators.D2I, IS, -1);

    int[] a = new int[IS.length()], b = new int[IS.length()];
    back0.intoArray(a, 0);
    backM1.intoArray(b, 0);
    System.out.println("part  0 -> " + java.util.Arrays.toString(a));
    System.out.println("part -1 -> " + java.util.Arrays.toString(b));

    // The join: each half filled the lanes the other left at zero, so a plain OR recombines
    // them - if that is what the parts actually do, which is what this prints.
    IntVector joined = back0.or(backM1);
    int[] got = new int[IS.length()];
    joined.intoArray(got, 0);
    int wrong = 0;
    for (int i = 0; i < src.length; i++) {
      if (got[i] != src[i] / d) {
        wrong++;
      }
    }
    System.out.println("joined  -> " + java.util.Arrays.toString(got));
    System.out.println("expected-> " + java.util.Arrays.toString(
        java.util.stream.IntStream.of(src).map(x -> x / d).toArray()));
    System.out.println(wrong == 0 ? "JOIN BY OR WORKS" : "join by or is wrong in " + wrong);
  }
}
