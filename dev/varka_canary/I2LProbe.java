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
 * Does an int32 value widen into int64 lanes the same way it widens into double lanes? VARKA-88
 * settled the double round trip before any bytecode was emitted for it; VARKA-28 needs the same
 * answer for `I2L` and `L2I`, and the answer is not guessable from the javadoc alone.
 *
 * <p>One species per lane type, which is the constraint that decides the whole lowering: a
 * second int species in the same JVM turns the shared IntVector templates bimorphic and costs a
 * heap box per iteration in every other loop (SKILLS.md, "Every operator the plans rely on").
 * So the int side is SPECIES_PREFERRED and the long side is the species of the same total
 * width, and a full int vector becomes two long vectors rather than a half-empty one.
 *
 * <p>What this prints: the expanding parts 0 and 1 going in, the contracting parts 0 and -1
 * coming back, that the two contracted halves are lane-disjoint so a plain `or` rejoins them,
 * and that the round trip is exact for negative values - `I2L` sign-extends.
 *
 * Run:
 *   javac --add-modules jdk.incubator.vector -d /tmp/p dev/varka_canary/I2LProbe.java
 *   java --add-modules jdk.incubator.vector -cp /tmp/p I2LProbe
 */
public class I2LProbe {
  static final VectorSpecies<Integer> IS = IntVector.SPECIES_PREFERRED;
  static final VectorSpecies<Long> LS =
      VectorSpecies.of(long.class, VectorShape.forBitSize(IS.vectorBitSize()));

  public static void main(String[] args) {
    System.out.println("int " + IS + " lanes " + IS.length()
        + " | long " + LS + " lanes " + LS.length());
    int[] src = new int[IS.length()];
    for (int i = 0; i < src.length; i++) {
      // Both signs, so a widening that zero-extended instead of sign-extending would show.
      src[i] = (i % 2 == 0 ? 1 : -1) * (i * 1000 + 7);
    }
    IntVector v = IntVector.fromArray(IS, src, 0);

    // Expanding: parts 0 .. M-1. Contracting: parts -M+1 .. 0.
    LongVector lo = (LongVector) v.convertShape(VectorOperators.I2L, LS, 0);
    LongVector hi = (LongVector) v.convertShape(VectorOperators.I2L, LS, 1);
    long[] l0 = new long[LS.length()];
    long[] l1 = new long[LS.length()];
    lo.intoArray(l0, 0);
    hi.intoArray(l1, 0);
    System.out.println("I2L part 0  -> " + java.util.Arrays.toString(l0));
    System.out.println("I2L part 1  -> " + java.util.Arrays.toString(l1));

    IntVector back0 = (IntVector) lo.convertShape(VectorOperators.L2I, IS, 0);
    IntVector back1 = (IntVector) hi.convertShape(VectorOperators.L2I, IS, -1);
    int[] r0 = new int[IS.length()];
    int[] r1 = new int[IS.length()];
    back0.intoArray(r0, 0);
    back1.intoArray(r1, 0);
    System.out.println("L2I part  0 -> " + java.util.Arrays.toString(r0));
    System.out.println("L2I part -1 -> " + java.util.Arrays.toString(r1));

    int[] got = new int[IS.length()];
    back0.or(back1).intoArray(got, 0);
    System.out.println("joined      -> " + java.util.Arrays.toString(got));
    System.out.println("source      -> " + java.util.Arrays.toString(src));
    System.out.println(java.util.Arrays.equals(got, src)
        ? "ROUND TRIP EXACT, JOIN BY OR WORKS"
        : "MISMATCH - the parts do not compose");
  }
}
