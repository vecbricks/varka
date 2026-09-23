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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What an emitted class measures in the units the JVM enforces, read back from its bytes.
 *
 * <p>The emitter bounds its methods by node weight, and weight is not what the JVM counts. A
 * method's code length decides whether it is compiled at all
 * ({@link VarkaEmitBudget#HUGE_METHOD_LIMIT}), a class's constant pool has a hard count, and a
 * method's parameters have a hard slot count.
 * This reads all three from the class the emitter built, so a budget can be checked against the
 * quantity it is a budget <i>of</i>, and so a tool can print it beside the op counts. See
 * {@code PLAN_TASK_87.md} section 2.
 *
 * <p>Every field is a plain Java type: Scala's typechecker cannot complete the Class-File API's
 * types, so nothing from that API may appear in a signature Scala reaches.
 *
 * @param codeLength each method's {@code Code} attribute length in bytes, by method name, in
 *                   class-file order; a method without code is absent
 * @param parameterSlots each method's parameter slots as the JVM counts them - one per
 *                       parameter, two for a {@code long} or {@code double}, plus one for
 *                       {@code this} on an instance method - by method name
 * @param constantPoolCount the class's {@code constant_pool_count}, which includes the unused
 *                          slot zero, and is the number the class-file format caps
 */
record VarkaEmittedClass(
    LinkedHashMap<String, Integer> codeLength,
    LinkedHashMap<String, Integer> parameterSlots,
    int constantPoolCount) {

  /** Reads the three measures from a class file's bytes. */
  static VarkaEmittedClass measure(byte[] classBytes) {
    java.lang.classfile.ClassModel model = java.lang.classfile.ClassFile.of().parse(classBytes);
    LinkedHashMap<String, Integer> lengths = new LinkedHashMap<>();
    LinkedHashMap<String, Integer> slots = new LinkedHashMap<>();
    for (java.lang.classfile.MethodModel method : model.methods()) {
      String name = method.methodName().stringValue();
      method.code().ifPresent(code -> lengths.put(name,
          ((java.lang.classfile.attribute.CodeAttribute) code).codeLength()));
      int count = method.flags().has(java.lang.reflect.AccessFlag.STATIC) ? 0 : 1;
      for (java.lang.constant.ClassDesc p : method.methodTypeSymbol().parameterList()) {
        String d = p.descriptorString();
        count += (d.equals("J") || d.equals("D")) ? 2 : 1;
      }
      slots.put(name, count);
    }
    return new VarkaEmittedClass(lengths, slots, model.constantPool().size());
  }

  /** The largest code length in the class, and the method that has it. */
  Map.Entry<String, Integer> largestMethod() {
    Map.Entry<String, Integer> largest = null;
    for (Map.Entry<String, Integer> e : codeLength.entrySet()) {
      if (largest == null || e.getValue() > largest.getValue()) {
        largest = e;
      }
    }
    return largest;
  }
}
