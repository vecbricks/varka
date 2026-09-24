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

import java.lang.classfile.Attribute;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Java shim over the Class-File API for the Scala test suites: everything they need to read
 * back an emitted class - verification, a method's size and invocation count, and a symbolic
 * rendering of every method body for the emitted-bytes oracle. Scala 2.13's typechecker hits an
 * "illegal cyclic reference" on the Class-File API's sealed hierarchy (the same bug that keeps
 * {@code VarkaLoopEmitter} itself in Java), so the suites go through this class rather than
 * touching the API directly.
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
   * stay under; see {@code PLAN_MILESTONE_4.md}'s task 44. Fails when the method does not
   * exist: a zero would let a test that names a method the layout no longer emits compare zero
   * with zero and pass while asserting nothing, which is how a renamed method goes unnoticed.
   */
  public static int codeSize(byte[] bytes, String methodName) {
    for (java.lang.classfile.MethodModel method : ClassFile.of().parse(bytes).methods()) {
      if (method.methodName().equalsString(methodName)) {
        return method.code()
            .map(code -> ((java.lang.classfile.attribute.CodeAttribute) code).codeLength())
            .orElse(0);
      }
    }
    throw missing(bytes, methodName);
  }

  /** The failure {@link #codeSize} and {@link #invocationCount} give for a method not there. */
  private static IllegalArgumentException missing(byte[] bytes, String methodName) {
    return new IllegalArgumentException("the class has no method " + methodName + "; it has "
        + methodNames(bytes));
  }

  /**
   * How many instructions in the named method invoke a method on {@code owner} (a binary class
   * name, e.g. {@code jdk.incubator.vector.IntVector}) - the emitted lane-op count, read off
   * the class file rather than counted in the emitter's source. It is the deterministic half
   * of an optimization's deliverable: a test can pin exactly how many lane ops a lowering
   * costs, where a timing can only say that it did not get slower. Fails when the method does
   * not exist, for {@link #codeSize}'s reason.
   */
  public static int invocationCount(byte[] bytes, String methodName, String owner) {
    return invocationCount(bytes, methodName, owner, List.of());
  }

  /**
   * {@link #invocationCount} with the named callees left out. The owner-wide count answers "how
   * many lane ops", which is the right question for {@code jdk.incubator.vector.IntVector} and
   * the wrong one for {@code VarkaVectorSupport}: {@code loadSegment} emits an
   * {@code ofAddress} for every segment the body touches, in every body mode, so a count that
   * includes it can never reach zero however much validity work is removed, and task 70's
   * registered targets of "0" would be unreachable with the tool that is supposed to read them.
   * Excluding {@code ofAddress} leaves exactly the validity work - the {@code validityBitsAt*}
   * reads, the {@code orValidityBitsAt*} and {@code orPartialValidityBitsAt*} writes, and
   * task 70's whole-batch {@code copyValidity}/{@code andValidity}/{@code orValidity}.
   *
   * <p>The match is exact, never a prefix, for {@link #invokedNames}' reason: the helpers carry
   * a lane-count suffix since task 46, and {@code orValidityBitsAt} is a prefix of
   * {@code orValidityBitsAt16}. An excluded name that the method does not invoke is not an
   * error - the exclusion list says what the metric is, not what the body contains.
   */
  public static int invocationCount(
      byte[] bytes, String methodName, String owner, List<String> excludedCallees) {
    int count = 0;
    for (java.lang.classfile.MethodModel method : ClassFile.of().parse(bytes).methods()) {
      if (!method.methodName().equalsString(methodName) || method.code().isEmpty()) {
        continue;
      }
      for (java.lang.classfile.CodeElement element : method.code().get()) {
        if (element instanceof java.lang.classfile.instruction.InvokeInstruction invoke
            && invoke.owner().asInternalName().equals(owner.replace('.', '/'))
            && !excludedCallees.contains(invoke.name().stringValue())) {
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

  /**
   * Every method's body as a canonical text, keyed by name and descriptor, for the emitted-bytes
   * oracle (task 85). One line per instruction: the opcode and its operands rendered
   * symbolically - a callee by owner, name and descriptor, a constant by its value, a branch by
   * a label numbered in order of first appearance - never by constant-pool index. So two
   * classes whose constant pools are laid out differently but whose methods do the same thing
   * render the same, and a difference in the rendering is a difference in what the method does.
   * Line-number and local-variable tables are left out: they are the emitter's IR map, not
   * behaviour. Exception ranges are kept, with their labels.
   */
  /**
   * Everything about an emitted class that is not a method body: its flags, what it extends and
   * implements, the names of its attributes, and each method's flags beside its name. The method
   * bodies are hashed one by one; without this, a refactor could drop the
   * {@code VarkaFusedKernel} interface or the telemetry attribute, or widen a loop method to
   * public, and every body would still render identically.
   */
  public static String classSummary(byte[] bytes) {
    ClassModel model = ClassFile.of().parse(bytes);
    StringBuilder sb = new StringBuilder();
    sb.append("flags ").append(model.flags().flagsMask()).append('\n');
    sb.append("super ").append(model.superclass().map(ClassEntry::asInternalName).orElse("-"))
        .append('\n');
    model.interfaces().forEach(i -> sb.append("implements ").append(i.asInternalName())
        .append('\n'));
    model.attributes().stream().map(Attribute::attributeName).map(Utf8Entry::stringValue)
        .sorted().forEach(n -> sb.append("attribute ").append(n).append('\n'));
    for (MethodModel m : model.methods()) {
      sb.append("method ").append(m.flags().flagsMask()).append(' ')
          .append(m.methodName().stringValue()).append(m.methodType().stringValue()).append('\n');
    }
    return sb.toString();
  }

  /**
   * LDC, LDC_W and LDC2_W render alike: which of the three the Class-File API picks depends on
   * where the constant lands in the pool, so rendering the opcode would move a method's hash
   * when an unrelated constant was added ahead of it. bipush, sipush and the iconst family are
   * left as they are, since those are chosen by the value rather than by the pool.
   */
  private static String ldcNormalized(Opcode opcode) {
    return switch (opcode) {
      case LDC, LDC_W, LDC2_W -> "LDC";
      default -> opcode.toString();
    };
  }

  public static LinkedHashMap<String, String> methodBodies(byte[] bytes) {
    LinkedHashMap<String, String> out = new LinkedHashMap<>();
    for (MethodModel method : ClassFile.of().parse(bytes).methods()) {
      if (method.code().isEmpty()) {
        continue;
      }
      Map<Label, Integer> labels = new IdentityHashMap<>();
      java.util.function.Function<Label, String> name =
          l -> "L" + labels.computeIfAbsent(l, k -> labels.size());
      StringBuilder sb = new StringBuilder();
      for (CodeElement element : method.code().get()) {
        switch (element) {
          case LabelTarget t -> sb.append(name.apply(t.label())).append(":\n");
          case ExceptionCatch c -> sb.append("try ").append(name.apply(c.tryStart())).append(' ')
              .append(name.apply(c.tryEnd())).append(" handler ").append(name.apply(c.handler()))
              .append(' ').append(c.catchType().map(t -> t.asInternalName()).orElse("any"))
              .append('\n');
          case InvokeInstruction i -> sb.append(i.opcode()).append(' ')
              .append(i.owner().asInternalName()).append('.').append(i.name().stringValue())
              .append(i.type().stringValue()).append('\n');
          case InvokeDynamicInstruction i -> sb.append(i.opcode()).append(' ')
              .append(i.name().stringValue()).append(i.type().stringValue()).append(' ')
              .append(i.bootstrapMethod()).append(i.bootstrapArgs()).append('\n');
          case FieldInstruction f -> sb.append(f.opcode()).append(' ')
              .append(f.owner().asInternalName()).append('.').append(f.name().stringValue())
              .append(':').append(f.type().stringValue()).append('\n');
          case ConstantInstruction c -> sb.append(ldcNormalized(c.opcode())).append(' ')
              .append(c.constantValue()).append('\n');
          case LoadInstruction l -> sb.append(l.opcode()).append(' ').append(l.slot()).append('\n');
          case StoreInstruction s -> sb.append(s.opcode()).append(' ').append(s.slot())
              .append('\n');
          case IncrementInstruction i -> sb.append(i.opcode()).append(' ').append(i.slot())
              .append(' ').append(i.constant()).append('\n');
          case BranchInstruction b -> sb.append(b.opcode()).append(' ')
              .append(name.apply(b.target())).append('\n');
          case TableSwitchInstruction t -> {
            sb.append(t.opcode()).append(' ').append(t.lowValue()).append(' ')
                .append(t.highValue()).append(" default ").append(name.apply(t.defaultTarget()));
            for (SwitchCase c : t.cases()) {
              sb.append(' ').append(c.caseValue()).append("->").append(name.apply(c.target()));
            }
            sb.append('\n');
          }
          case LookupSwitchInstruction t -> {
            sb.append(t.opcode()).append(" default ").append(name.apply(t.defaultTarget()));
            for (SwitchCase c : t.cases()) {
              sb.append(' ').append(c.caseValue()).append("->").append(name.apply(c.target()));
            }
            sb.append('\n');
          }
          case TypeCheckInstruction t -> sb.append(t.opcode()).append(' ')
              .append(t.type().asInternalName()).append('\n');
          case NewObjectInstruction n -> sb.append(n.opcode()).append(' ')
              .append(n.className().asInternalName()).append('\n');
          case NewPrimitiveArrayInstruction n -> sb.append(n.opcode()).append(' ')
              .append(n.typeKind()).append('\n');
          case NewReferenceArrayInstruction n -> sb.append(n.opcode()).append(' ')
              .append(n.componentType().asInternalName()).append('\n');
          case NewMultiArrayInstruction n -> sb.append(n.opcode()).append(' ')
              .append(n.arrayType().asInternalName()).append(' ').append(n.dimensions())
              .append('\n');
          // Operators, stack ops, conversions, array loads and stores, returns, throws, monitors,
          // nops: the opcode says everything.
          case Instruction i -> sb.append(i.opcode()).append('\n');
          // Line numbers, local variable tables, character ranges: not behaviour.
          default -> { }
        }
      }
      out.put(
          method.methodName().stringValue() + method.methodType().stringValue(), sb.toString());
    }
    return out;
  }
}
