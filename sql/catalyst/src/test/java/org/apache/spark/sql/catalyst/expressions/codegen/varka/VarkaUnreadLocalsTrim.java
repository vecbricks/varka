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
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.PseudoInstruction;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.ArrayLoadInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * VARKA-239's admission check, read off the bytecode ({@code VARKA-239.md} 2): the reference
 * locals a loop or epilogue method stores and never reads, named by what the stored expression
 * built ({@link #census}), and a class with them removed ({@link #trim}) - each such store with
 * the expression it stored, found by walking back the stack effects, repeated until none is left,
 * since a removed load can leave the segment it read unread too. {@code VarkaUnreadLocalsBenchmark}
 * times the classes with and without them before the emitter changes; once it emits them only
 * where they are read (VARKA-239's switch), the benchmark's section in
 * {@code VarkaWideKernelBenchmark} replaces this, and so does the check {@code VarkaUnreadLocals}.
 *
 * <p>No Class-File API type appears in a signature here: the Scala suites read this class's
 * signatures, and scalac cannot load that API's sealed builders.
 */
final class VarkaUnreadLocalsTrim {

  private VarkaUnreadLocalsTrim() {}

  /** Whether a method is one the census reads: a group's loop or epilogue. */
  static boolean counted(String method) {
    return VarkaMethodNames.isLoop(method) || VarkaMethodNames.isEpilogue(method);
  }

  /** One dead store: the method kind, what the local held, and the expression's span. */
  record Dead(String method, String kind, int start, int store) {}

  /** The dead reference stores of every loop and epilogue method of a class. */
  static List<Dead> census(byte[] bytes) {
    List<Dead> all = new ArrayList<>();
    ClassModel cm = ClassFile.of().parse(bytes);
    for (MethodModel mm : cm.methods()) {
      String name = mm.methodName().stringValue();
      if (!counted(name) || mm.code().isEmpty()) {
        continue;
      }
      all.addAll(dead(name, mm.code().get().elementList()));
    }
    return all;
  }

  private static String body(String method) {
    for (String p : VarkaMethodNames.GROUP_METHOD_KINDS) {
      if (method.startsWith(p)) {
        return p;
      }
    }
    return method;
  }

  @SuppressWarnings("unchecked")
  private static List<Dead> dead(String method, List<?> raw) {
    List<CodeElement> els = (List<CodeElement>) raw;
    Set<Integer> loaded = new HashSet<>();
    Map<Integer, String> storedFrom = new HashMap<>();
    for (int i = 0; i < els.size(); i++) {
      if (els.get(i) instanceof LoadInstruction l && l.typeKind() == TypeKind.REFERENCE) {
        loaded.add(l.slot());
      }
    }
    List<Dead> out = new ArrayList<>();
    for (int i = 0; i < els.size(); i++) {
      if (!(els.get(i) instanceof StoreInstruction st) || st.typeKind() != TypeKind.REFERENCE) {
        continue;
      }
      int start = expressionStart(els, i);
      String kind = classify(els, start, i, storedFrom);
      storedFrom.putIfAbsent(st.slot(), kind);
      if (!loaded.contains(st.slot())) {
        out.add(new Dead(body(method), kind, start, i));
      }
    }
    return out;
  }

  /** The previous real instruction before {@code i}, or -1. */
  @SuppressWarnings("unchecked")
  private static int previous(List<?> raw, int i) {
    List<CodeElement> els = (List<CodeElement>) raw;
    for (int q = i - 1; q >= 0; q--) {
      if (els.get(q) instanceof Instruction) {
        return q;
      }
    }
    return -1;
  }

  /**
   * Where the expression whose value the store at {@code store} takes begins: walking back with
   * the stack effect of each instruction until exactly one value has been produced, or -1 where
   * that crosses a branch target or an instruction this walk does not model. A store after a
   * {@code dup} keeps the value on the stack for its user, so its expression is the dup alone.
   */
  @SuppressWarnings("unchecked")
  static int expressionStart(List<?> raw, int store) {
    List<CodeElement> els = (List<CodeElement>) raw;
    int prev = previous(els, store);
    if (prev >= 0 && els.get(prev) instanceof StackInstruction s && s.opcode() == Opcode.DUP) {
      return prev;
    }
    int need = 1;
    for (int q = store - 1; q >= 0; q--) {
      CodeElement e = els.get(q);
      if (e instanceof LabelTarget) {
        return -1;
      }
      if (e instanceof PseudoInstruction || !(e instanceof Instruction)) {
        continue;
      }
      int[] effect = effect((Instruction) e);
      if (effect == null) {
        return -1;
      }
      need = need - effect[1] + effect[0];
      if (need < 0) {
        return -1;
      }
      if (need == 0) {
        return q;
      }
    }
    return -1;
  }

  /** Values popped and pushed, or null for an instruction with an effect this walk avoids. */
  private static int[] effect(Object raw) {
    return switch ((Instruction) raw) {
      case LoadInstruction l -> new int[] {0, 1};
      case ConstantInstruction c -> new int[] {0, 1};
      case FieldInstruction f -> f.opcode() == Opcode.GETSTATIC ? new int[] {0, 1}
          : f.opcode() == Opcode.GETFIELD ? new int[] {1, 1} : null;
      case ArrayLoadInstruction a -> new int[] {2, 1};
      case ConvertInstruction c -> new int[] {1, 1};
      case OperatorInstruction o -> switch (o.opcode()) {
        case INEG, LNEG, FNEG, DNEG, ARRAYLENGTH -> new int[] {1, 1};
        default -> new int[] {2, 1};
      };
      case InvokeInstruction inv -> {
        int args = inv.typeSymbol().parameterCount()
            + (inv.opcode() == Opcode.INVOKESTATIC ? 0 : 1);
        boolean isVoid = inv.typeSymbol().returnType().descriptorString().equals("V");
        yield isVoid ? null : new int[] {args, 1};
      }
      default -> null;
    };
  }

  @SuppressWarnings("unchecked")
  private static String classify(List<?> raw, int start, int store,
      Map<Integer, String> storedFrom) {
    List<CodeElement> els = (List<CodeElement>) raw;
    if (start < 0) {
      return "unsliceable";
    }
    if (els.get(start) instanceof StackInstruction s && s.opcode() == Opcode.DUP) {
      return "shared slot";
    }
    int last = previous(els, store);
    if (els.get(last) instanceof InvokeInstruction inv) {
      String m = inv.name().stringValue();
      if (m.equals("ofAddress")) {
        if (els.get(start) instanceof LoadInstruction l) {
          if (l.typeKind() == TypeKind.LONG) {
            return "segment: scratch";
          }
          return switch (l.slot()) {
            case VarkaDescriptors.P_SRC_DATA -> "segment: input data";
            case VarkaDescriptors.P_SRC_VALIDITY -> "segment: input validity";
            case VarkaDescriptors.P_DST_DATA -> "segment: output data";
            case VarkaDescriptors.P_DST_VALIDITY -> "segment: output validity";
            default -> "segment: other";
          };
        }
        return "segment: other";
      }
      if (m.equals("fromMemorySegment")) {
        // The segment operand is the second instruction of the slice: species, then segment.
        int seg = -1;
        int seen = 0;
        for (int q = start; q < store; q++) {
          if (els.get(q) instanceof LoadInstruction l && l.typeKind() == TypeKind.REFERENCE) {
            if (++seen == 2) {
              seg = l.slot();
              break;
            }
          }
        }
        return "segment: scratch".equals(storedFrom.get(seg)) ? "vector: prefix reload"
            : "vector: column load";
      }
      if (m.equals("indexInRange")) {
        return "mask: indexInRange";
      }
      return "other: " + inv.owner().asInternalName() + "." + m;
    }
    return "other: " + els.get(last);
  }

  /**
   * {@code bytes} with every dead reference store of its loop and epilogue methods removed,
   * together with the expression it stored, of the kinds {@code kinds} names (a prefix match),
   * repeated until none is left, since removing a load can leave the segment it read dead too.
   */
  static byte[] trim(byte[] bytes, List<String> kinds) {
    for (int round = 0; round < 6; round++) {
      ClassModel cm = ClassFile.of().parse(bytes);
      Map<String, Set<Integer>> drop = new HashMap<>();
      for (MethodModel mm : cm.methods()) {
        String name = mm.methodName().stringValue();
        if (!counted(name) || mm.code().isEmpty()) {
          continue;
        }
        List<CodeElement> els = mm.code().get().elementList();
        Set<Integer> mine = new HashSet<>();
        for (Dead d : dead(name, els)) {
          if (d.start() < 0 || kinds.stream().noneMatch(d.kind()::startsWith)) {
            continue;
          }
          for (int q = d.start(); q <= d.store(); q++) {
            if (els.get(q) instanceof Instruction) {
              mine.add(q);
            }
          }
        }
        if (!mine.isEmpty()) {
          drop.put(name + mm.methodType().stringValue(), mine);
        }
      }
      if (drop.isEmpty()) {
        return bytes;
      }
      bytes = ClassFile.of().transformClass(cm, (clb, ce) -> {
        if (ce instanceof MethodModel mm
            && drop.containsKey(mm.methodName().stringValue() + mm.methodType().stringValue())) {
          Set<Integer> mine =
              drop.get(mm.methodName().stringValue() + mm.methodType().stringValue());
          int[] at = {0};
          clb.transformMethod(mm, MethodTransform.transformingCode((cob, coe) -> {
            int index = at[0]++;
            if (!mine.contains(index)) {
              cob.with(coe);
            }
          }));
        } else {
          clb.with(ce);
        }
      });
    }
    return bytes;
  }

  /** Counts by key, sorted, for the census's report. */
  static Map<String, Integer> tally(List<Dead> dead, boolean byBody) {
    Map<String, Integer> t = new TreeMap<>();
    for (Dead d : dead) {
      t.merge(byBody ? d.kind() + " | " + d.method() : d.kind(), 1, Integer::sum);
    }
    return t;
  }

  /** The bytes of a class's loop and epilogue methods. */
  static long countedBytes(byte[] bytes) {
    long sum = 0;
    for (Map.Entry<String, Integer> e : VarkaEmittedClass.measure(bytes).codeLength().entrySet()) {
      if (counted(e.getKey())) {
        sum += e.getValue();
      }
    }
    return sum;
  }
}
