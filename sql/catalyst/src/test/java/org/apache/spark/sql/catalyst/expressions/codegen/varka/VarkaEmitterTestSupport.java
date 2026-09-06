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

import java.lang.classfile.ClassFile;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Java shim over {@link ClassFile#verify} for the Scala test suite: Scala 2.13's typechecker
 * hits an "illegal cyclic reference" on the Class-File API's sealed hierarchy (the same bug
 * that keeps {@code VarkaLoopEmitter} itself in Java), so the suite calls the verifier through
 * this class instead of touching the API directly.
 */
public final class VarkaEmitterTestSupport {

  private VarkaEmitterTestSupport() {
  }

  /** Runs class-file verification and returns the error messages; empty means verified. */
  public static List<String> verify(byte[] bytes) {
    return ClassFile.of().verify(bytes).stream()
        .map(Throwable::getMessage)
        .collect(Collectors.toList());
  }

  /**
   * Whether the class carries an attribute with the given name, parsed <i>without</i> any
   * custom mapper - the view a third-party class-file tool gets, where an unregistered custom
   * attribute is opaque but still present under its name (the task 13 telemetry tests).
   */
  public static boolean hasAttributeNamed(byte[] bytes, String name) {
    return ClassFile.of().parse(bytes).attributes().stream()
        .anyMatch(attr -> attr.attributeName().equalsString(name));
  }

  /**
   * The named method's bytecode size - the length of its {@code Code} attribute, which is what
   * HotSpot measures against {@code HugeMethodLimit} (8000 bytes by default) when it decides
   * whether to compile the method at all. Past that limit the method is never compiled by C1 or
   * C2 and runs interpreted with boxed vectors, so this is the number a wide emitted body has to
   * stay under; see {@code PLAN_MILESTONE_4.md}'s task 44. Zero when the method does not exist.
   */
  public static int codeSize(byte[] bytes, String methodName) {
    for (java.lang.classfile.MethodModel method : ClassFile.of().parse(bytes).methods()) {
      if (method.methodName().equalsString(methodName)) {
        return method.code()
            .map(code -> ((java.lang.classfile.attribute.CodeAttribute) code).codeLength())
            .orElse(0);
      }
    }
    return 0;
  }

  /**
   * How many instructions in the named method invoke a method on {@code owner} (a binary class
   * name, e.g. {@code jdk.incubator.vector.IntVector}) - the emitted lane-op count, read off
   * the class file rather than counted in the emitter's source. It is the deterministic half
   * of an optimization's deliverable: a test can pin exactly how many lane ops a lowering
   * costs, where a timing can only say that it did not get slower. Zero when the method does
   * not exist.
   */
  public static int invocationCount(byte[] bytes, String methodName, String owner) {
    int count = 0;
    for (java.lang.classfile.MethodModel method : ClassFile.of().parse(bytes).methods()) {
      if (!method.methodName().equalsString(methodName) || method.code().isEmpty()) {
        continue;
      }
      for (java.lang.classfile.CodeElement element : method.code().get()) {
        if (element instanceof java.lang.classfile.instruction.InvokeInstruction invoke
            && invoke.owner().asInternalName().equals(owner.replace('.', '/'))) {
          count++;
        }
      }
    }
    return count;
  }

  /**
   * The line numbers the named method's {@code LineNumberTable} attributes its instructions to,
   * in ascending order and without duplicates - the task 16 mapping, read the way a debugger or
   * a stack trace reads it. Empty when the method carries no table (or does not exist).
   */
  /** Every method the class declares, in declaration order - for tools that report per method
   *  and cannot name the Class-File API's types from Scala. */
  public static List<String> methodNames(byte[] bytes) {
    List<String> names = new java.util.ArrayList<>();
    for (java.lang.classfile.MethodModel method : ClassFile.of().parse(bytes).methods()) {
      names.add(method.methodName().stringValue());
    }
    return names;
  }

  /**
   * The distinct methods the whole class invokes on {@code owner}, sorted - the callee names
   * behind {@link #invocationCount}'s count. Task 46 picks a validity helper by the emitted
   * lane count, so which name a body carries is the assertion, and a substring test would not
   * do it: {@code orValidityBitsAt} is a prefix of {@code orValidityBitsAt16}.
   */
  public static List<String> invokedNames(byte[] bytes, String owner) {
    java.util.TreeSet<String> names = new java.util.TreeSet<>();
    for (java.lang.classfile.MethodModel method : ClassFile.of().parse(bytes).methods()) {
      if (method.code().isEmpty()) {
        continue;
      }
      for (java.lang.classfile.CodeElement element : method.code().get()) {
        if (element instanceof java.lang.classfile.instruction.InvokeInstruction invoke
            && invoke.owner().asInternalName().equals(owner.replace('.', '/'))) {
          names.add(invoke.name().stringValue());
        }
      }
    }
    return new java.util.ArrayList<>(names);
  }

  /**
   * The distinct static fields the whole class reads from {@code owner}, sorted. The emitted
   * species constant is one of these, and since task 46 it is the concrete
   * {@code SPECIES_512} rather than {@code SPECIES_PREFERRED} wherever a width was baked.
   */
  public static List<String> staticFieldsRead(byte[] bytes, String owner) {
    java.util.TreeSet<String> names = new java.util.TreeSet<>();
    for (java.lang.classfile.MethodModel method : ClassFile.of().parse(bytes).methods()) {
      if (method.code().isEmpty()) {
        continue;
      }
      for (java.lang.classfile.CodeElement element : method.code().get()) {
        if (element instanceof java.lang.classfile.instruction.FieldInstruction field
            && field.opcode() == java.lang.classfile.Opcode.GETSTATIC
            && field.owner().asInternalName().equals(owner.replace('.', '/'))) {
          names.add(field.name().stringValue());
        }
      }
    }
    return new java.util.ArrayList<>(names);
  }

  public static List<Integer> lineNumbers(byte[] bytes, String methodName) {
    java.util.TreeSet<Integer> lines = new java.util.TreeSet<>();
    for (java.lang.classfile.MethodModel method : ClassFile.of().parse(bytes).methods()) {
      if (!method.methodName().equalsString(methodName)) {
        continue;
      }
      method.code()
          .flatMap(code -> code.findAttribute(java.lang.classfile.Attributes.lineNumberTable()))
          .ifPresent(table -> table.lineNumbers().forEach(info -> lines.add(info.lineNumber())));
    }
    return new java.util.ArrayList<>(lines);
  }
}
