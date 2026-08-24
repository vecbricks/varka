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

package org.apache.spark.sql.catalyst.expressions.codegen;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;

/**
 * Task 4 test helper: asserts that the bytes assembled by
 * {@code VarkaClassFileGen.assembleKernelClass} contain exactly the kernel's parameter loads
 * (in argument order) followed by a single {@code invokestatic} to the expected kernel. A
 * Java helper is used because Scala 2.13 hits a cyclic-reference bug when it reads the sealed
 * instruction hierarchy of {@code java.lang.classfile}.
 *
 * <p>The {@code run} method is an instance method implementing a kernel-shape interface, so the
 * parameters start at slot 1; the first slot the loads are checked against is taken from the
 * method's own static flag rather than assumed, so this also holds if a static probe is ever
 * assembled again.
 */
public final class ClassFileGenOpVerifier {

  private ClassFileGenOpVerifier() {
  }

  public static void assertKernelInvocation(
      byte[] bytes, String ownerInternalName, String methodName, String methodDescriptor) {
    var model = ClassFile.of().parse(bytes);
    var runMethod = model.methods().stream()
        .filter(m -> m.methodName().stringValue().equals("run"))
        .findFirst().orElseThrow();
    CodeModel code = runMethod.code().orElseThrow();
    List<LoadInstruction> loads = new ArrayList<>();
    List<InvokeInstruction> invokes = new ArrayList<>();
    code.elementStream().forEach(e -> {
      if (e instanceof LoadInstruction li) {
        loads.add(li);
      } else if (e instanceof InvokeInstruction ii) {
        invokes.add(ii);
      }
    });
    MethodTypeDesc kernelDesc = MethodTypeDesc.ofDescriptor(methodDescriptor);
    List<Object[]> expectedLoads = new ArrayList<>();
    int slot = runMethod.flags().has(AccessFlag.STATIC) ? 0 : 1;
    for (var p : kernelDesc.parameterList()) {
      TypeKind kind = TypeKind.fromDescriptor(p.descriptorString().substring(0, 1));
      expectedLoads.add(new Object[]{slot, kind});
      slot += (kind == TypeKind.LONG) ? 2 : 1;
    }
    if (loads.size() != expectedLoads.size()) {
      throw new IllegalStateException(
          "expected " + expectedLoads.size() + " loads, got " + loads.size());
    }
    for (int i = 0; i < expectedLoads.size(); i++) {
      if ((Integer) expectedLoads.get(i)[0] != loads.get(i).slot()
          || !expectedLoads.get(i)[1].equals(loads.get(i).typeKind())) {
        throw new IllegalStateException("load at index " + i + " does not match kernel parameter");
      }
    }
    if (invokes.size() != 1) {
      throw new IllegalStateException("expected one invokestatic, got " + invokes.size());
    }
    InvokeInstruction invoke = invokes.get(0);
    if (invoke.opcode() != Opcode.INVOKESTATIC
        || !invoke.owner().asInternalName().equals(ownerInternalName)
        || !invoke.name().stringValue().equals(methodName)
        || !invoke.typeSymbol().equals(kernelDesc)) {
      throw new IllegalStateException("invokestatic target does not match the kernel contract");
    }
  }
}
