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
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

  /**
   * The refusal the Class-File API throws for a method past
   * {@link VarkaEmitBudget#METHOD_CODE_CAP}, as the JDK's {@code DirectCodeBuilder} words it:
   * {@code "Code length %d is outside the allowed range in %s%s"}, with the length, the method's
   * name and its descriptor in display form. The text is the JDK's and not an API; see
   * {@link #refused}.
   */
  private static final Pattern REFUSAL =
      Pattern.compile("Code length (\\d+) is outside the allowed range in ([^(\\s]+)\\(");

  /**
   * The measurement of a class the Class-File API would not build, read from its refusal: one
   * method with the code length the refusal reports, no parameter slots, and a constant pool
   * counted as 0 because it was never measured.
   *
   * <p>The API enforces the class-file format's cap on a method's code
   * ({@link VarkaEmitBudget#METHOD_CODE_CAP}) while the class is assembled, after every method
   * body has been built, by throwing an {@code IllegalArgumentException} - so no class exists
   * for {@link #measure} to read, and the one place the method is named is the message. The
   * emitter reads it here and hands the result to the same regroup that acts on a measured
   * method over a limit ({@code PLAN_TASK_219.md} 2.1 and 3.1). Empty when {@code e} is not that
   * refusal: the text is the JDK's and not an API, so an exception that does not match it, or
   * that names a length within the cap, is left to its caller as it was before, and
   * {@code VarkaIrFuzzSuite} is what reports a JDK that changed the words.
   */
  static Optional<VarkaEmittedClass> refused(IllegalArgumentException e) {
    String message = e.getMessage();
    if (message == null) {
      return Optional.empty();
    }
    Matcher m = REFUSAL.matcher(message);
    if (!m.lookingAt()) {
      return Optional.empty();
    }
    int bytes = Integer.parseInt(m.group(1));
    if (bytes <= VarkaEmitBudget.METHOD_CODE_CAP) {
      return Optional.empty();
    }
    LinkedHashMap<String, Integer> lengths = new LinkedHashMap<>();
    lengths.put(m.group(2), bytes);
    return Optional.of(new VarkaEmittedClass(lengths, new LinkedHashMap<>(), 0));
  }

  /**
   * The refusal the Class-File API throws when a class needs more constant pool entries than
   * {@link VarkaEmitBudget#CONSTANT_POOL_CAP}, as the JDK's {@code BufWriterImpl} words it when
   * the first entry past the cap is written: {@code "65536 is not a valid index. Entry: <entry>"}.
   * The text is the JDK's and not an API; see {@link #refusedConstantPool}.
   */
  private static final Pattern POOL_REFUSAL =
      Pattern.compile("(\\d+) is not a valid index\\. Entry: ");

  /**
   * Whether {@code e} is the Class-File API refusing a class whose constant pool is over
   * {@link VarkaEmitBudget#CONSTANT_POOL_CAP}. Like the method cap it is enforced while the class
   * is assembled, so a class over it is never measured; unlike the method cap no regroup shrinks
   * a pool, so the emitter declines on it class-wide, naming no output, and the compiler demotes
   * outputs from the end until it fits ({@code PLAN_TASK_219.md} 2.5). No shape the IR builds
   * has come within a fortieth of the cap; it is read here because the refusal takes the same
   * path as the method cap's, and would otherwise escape the same way.
   */
  static boolean refusedConstantPool(IllegalArgumentException e) {
    String message = e.getMessage();
    if (message == null) {
      return false;
    }
    Matcher m = POOL_REFUSAL.matcher(message);
    return m.lookingAt() && Long.parseLong(m.group(1)) > VarkaEmitBudget.CONSTANT_POOL_CAP;
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
