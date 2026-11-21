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

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMathBinding.Operator;

/**
 * The child JVM of {@code VarkaMathBindingSuite}: prints the facts {@link VarkaMathBinding}
 * resolves from, then touches each math operator once at each {@code DOUBLE} species this JVM has,
 * each touch after a {@code TOUCH <operator> <bits>} line. Run with
 * {@code -Djdk.incubator.vector.DEBUG=true}, the JDK prints the symbol it binds on the line after
 * the touch that first asks for it, and nothing for an operator it leaves unbound.
 */
public final class VarkaMathBindingProbe {

  /** Where each touch's lane goes. */
  private static double sink;

  private VarkaMathBindingProbe() {
  }

  public static void main(String[] args) throws ReflectiveOperationException {
    // The widest DOUBLE shape, which SPECIES_MAX is not: its shape is the byte lane's widest,
    // narrower than the double's at UseAVX=1 or without AVX-512BW.
    int max = VectorShape.largestShapeFor(double.class).vectorBitSize();
    String property = System.getProperty(VarkaMathBinding.LIBRARY_PROPERTY);
    boolean x86 = System.getProperty("os.arch").matches("amd64|x86_64");
    if (x86 && VarkaEmitOptions.HOST_USE_AVX < 0) {
      throw new IllegalStateException("UseAVX is unreadable on this x86 host, so the library the "
          + "JDK binds cannot be named; the probe will not guess a level");
    }
    System.out.println("HOST arch=" + System.getProperty("os.arch") + " os="
        + System.getProperty("os.name").replace(' ', '_') + " avx="
        + Math.max(0, VarkaEmitOptions.HOST_USE_AVX) + " max=" + max
        + (property == null ? "" : " property=" + property));
    for (int bits = 64; bits <= max; bits *= 2) {
      VectorSpecies<Double> species =
          VectorSpecies.of(double.class, VectorShape.forBitSize(bits));
      DoubleVector x = DoubleVector.broadcast(species, 0.5);
      for (Operator op : Operator.values()) {
        System.out.println("TOUCH " + op + " " + bits);
        Object operator = VectorOperators.class.getField(op.name()).get(null);
        DoubleVector r = op.binary()
            ? x.lanewise((VectorOperators.Binary) operator, x)
            : x.lanewise((VectorOperators.Unary) operator);
        // A lane is read, so the call is not dead code to a compiler that reaches it.
        sink += r.lane(0);
      }
    }
    System.out.flush();
  }
}
