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

package org.apache.spark.sql.varka.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.apache.spark.sql.varka.execution.VarkaClassLoader;

/**
 * Task 4: define-and-run integration of the DateVectorOps kernels. Catalyst owns the
 * Class-File assembly (Java 25+ baseline; see VarkaClassFileGen.assembleKernelClass), so
 * this test independently assembles a probe class (mirroring the catalyst assembler, which
 * this module cannot depend on), defines it via {@link VarkaClassLoader}, runs it against
 * native memory, and asserts the result. It also pins the kernel descriptors from the actual
 * methods via reflection, so the contract strings on the catalyst side cannot silently drift.
 *
 * <p>What is mirrored is the body: the parameter loads in argument order followed by a single
 * invokestatic to the kernel. The probe's {@code run} is static, whereas catalyst's runner
 * implements a kernel-shape interface declared in catalyst - a type this module has no way to
 * reference. That difference is in the calling convention, not in how the kernel is reached.
 */
class DateVectorOpsEmissionTest {

  private static final String DATE_VECTOR_OPS_INTERNAL =
      "org/apache/spark/sql/varka/vector/DateVectorOps";

  @Test
  void contractDescriptorsMatchReflection() throws NoSuchMethodException {
    Method addDays = DateVectorOps.class.getMethod("vectorAddDays",
        long.class, long.class, int.class, long.class, long.class, int.class, int.class);
    Method subDays = DateVectorOps.class.getMethod("vectorSubDays",
        long.class, long.class, int.class, long.class, long.class, int.class, int.class);
    Method dateDiff = DateVectorOps.class.getMethod("vectorDateDiff",
        long.class, long.class, int.class, long.class, long.class, int.class,
        long.class, long.class, int.class);
    assertEquals("(JJIJJII)V", methodDescriptor(addDays));
    assertEquals("(JJIJJII)V", methodDescriptor(subDays));
    assertEquals("(JJIJJIJJI)V", methodDescriptor(dateDiff));
  }

  @Test
  void addDaysEmissionRunsAndDisassembles() throws Exception {
    Method kernel = DateVectorOps.class.getMethod("vectorAddDays",
        long.class, long.class, int.class, long.class, long.class, int.class, int.class);
    String className = "org.apache.spark.sql.varka.vector.EmissionProbeAddDays";
    byte[] bytes = assembleProbe(className, kernel);
    Class<?> probeClass = defineAndRunAddSub(className, bytes, 3, false);
    assertNotNull(probeClass);
    assertKernelInvocation(bytes, kernel, "vectorAddDays");
  }

  @Test
  void subDaysEmissionRunsAndDisassembles() throws Exception {
    Method kernel = DateVectorOps.class.getMethod("vectorSubDays",
        long.class, long.class, int.class, long.class, long.class, int.class, int.class);
    String className = "org.apache.spark.sql.varka.vector.EmissionProbeSubDays";
    byte[] bytes = assembleProbe(className, kernel);
    Class<?> probeClass = defineAndRunAddSub(className, bytes, 3, true);
    assertNotNull(probeClass);
    assertKernelInvocation(bytes, kernel, "vectorSubDays");
  }

  @Test
  void dateDiffEmissionRunsAndDisassembles() throws Exception {
    Method kernel = DateVectorOps.class.getMethod("vectorDateDiff",
        long.class, long.class, int.class, long.class, long.class, int.class,
        long.class, long.class, int.class);
    String className = "org.apache.spark.sql.varka.vector.EmissionProbeDateDiff";
    byte[] bytes = assembleProbe(className, kernel);
    Class<?> probeClass = defineAndRunDateDiff(className, bytes);
    assertNotNull(probeClass);
    assertKernelInvocation(bytes, kernel, "vectorDateDiff");
  }

  private static Class<?> defineAndRunAddSub(
      String className, byte[] bytes, int daysOffset, boolean subtract) throws Exception {
    VarkaClassLoader loader =
        new VarkaClassLoader(DateVectorOpsEmissionTest.class.getClassLoader());
    Class<?> probeClass = loader.defineGeneratedClass(className, bytes);
    int length = 8;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment src = arena.allocate(length * 4L);
      MemorySegment dst = arena.allocate(length * 4L);
      MemorySegment dstValidity = arena.allocate((length + 7) / 8L);
      for (int i = 0; i < length; i++) {
        src.set(ValueLayout.JAVA_INT, i * 4L, i);
      }
      Method run = probeClass.getMethod("run", long.class, long.class, int.class,
          long.class, long.class, int.class, int.class);
      run.invoke(null, src.address(), 0L, 0, dst.address(), dstValidity.address(),
          length, daysOffset);
      for (int i = 0; i < length; i++) {
        int expected = subtract ? i - daysOffset : i + daysOffset;
        assertEquals(expected, dst.get(ValueLayout.JAVA_INT, i * 4L));
      }
    }
    return probeClass;
  }

  private static Class<?> defineAndRunDateDiff(String className, byte[] bytes) throws Exception {
    VarkaClassLoader loader =
        new VarkaClassLoader(DateVectorOpsEmissionTest.class.getClassLoader());
    Class<?> probeClass = loader.defineGeneratedClass(className, bytes);
    int length = 8;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment a = arena.allocate(length * 4L);
      MemorySegment b = arena.allocate(length * 4L);
      MemorySegment dst = arena.allocate(length * 4L);
      MemorySegment dstValidity = arena.allocate((length + 7) / 8L);
      for (int i = 0; i < length; i++) {
        a.set(ValueLayout.JAVA_INT, i * 4L, i);
        b.set(ValueLayout.JAVA_INT, i * 4L, 2);
      }
      Method run = probeClass.getMethod("run", long.class, long.class, int.class,
          long.class, long.class, int.class, long.class, long.class, int.class);
      run.invoke(null, a.address(), 0L, 0, b.address(), 0L, 0,
          dst.address(), dstValidity.address(), length);
      for (int i = 0; i < length; i++) {
        assertEquals(i - 2, dst.get(ValueLayout.JAVA_INT, i * 4L));
      }
    }
    return probeClass;
  }

  /**
   * Builds a public class with a default constructor and a static `run` method that
   * delegates to the given kernel: loads the parameters in order, then invokestatic.
   */
  private static String methodDescriptor(Method method) {
    return MethodType.methodType(method.getReturnType(), method.getParameterTypes())
        .toMethodDescriptorString();
  }

  private static byte[] assembleProbe(String className, Method kernel) {
    ClassDesc classDesc = ClassDesc.of(className);
    MethodTypeDesc kernelDesc = MethodTypeDesc.ofDescriptor(methodDescriptor(kernel));
    return ClassFile.of().build(classDesc, b -> b
        .withFlags(AccessFlag.PUBLIC)
        .withMethodBody("<init>", MethodTypeDesc.of(ConstantDescs.CD_void),
            AccessFlag.PUBLIC.mask(),
            cb -> cb.aload(0)
                .invokespecial(ConstantDescs.CD_Object, "<init>",
                    MethodTypeDesc.of(ConstantDescs.CD_void))
                .return_())
        .withMethodBody("run", kernelDesc,
            AccessFlag.PUBLIC.mask() | AccessFlag.STATIC.mask(),
            cb -> {
              int slot = 0;
              for (ClassDesc p : kernelDesc.parameterList()) {
                TypeKind kind = TypeKind.fromDescriptor(p.descriptorString().substring(0, 1));
                if (kind == TypeKind.LONG) {
                  cb.lload(slot);
                  slot += 2;
                } else {
                  cb.iload(slot);
                  slot += 1;
                }
              }
              cb.invokestatic(ClassDesc.of(kernel.getDeclaringClass().getName()),
                  kernel.getName(), kernelDesc)
                  .return_();
            }));
  }

  /**
   * Asserts the `run` method of the assembled probe contains exactly the kernel's parameter
   * loads (in slot order) followed by a single invokestatic to the expected kernel.
   */
  private static void assertKernelInvocation(byte[] bytes, Method kernel, String methodName) {
    ClassModel model = ClassFile.of().parse(bytes);
    MethodModel runMethod = model.methods().stream()
        .filter(mm -> mm.methodName().stringValue().equals("run"))
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
    MethodTypeDesc kernelDesc = MethodTypeDesc.ofDescriptor(methodDescriptor(kernel));
    List<Object[]> expectedLoads = new ArrayList<>();
    int slot = 0;
    for (ClassDesc p : kernelDesc.parameterList()) {
      TypeKind kind = TypeKind.fromDescriptor(p.descriptorString().substring(0, 1));
      expectedLoads.add(new Object[]{slot, kind});
      slot += (kind == TypeKind.LONG) ? 2 : 1;
    }
    assertEquals(expectedLoads.size(), loads.size());
    for (int i = 0; i < expectedLoads.size(); i++) {
      assertEquals(expectedLoads.get(i)[0], loads.get(i).slot());
      assertEquals(expectedLoads.get(i)[1], loads.get(i).typeKind());
    }
    assertEquals(1, invokes.size());
    InvokeInstruction invoke = invokes.get(0);
    assertEquals(Opcode.INVOKESTATIC, invoke.opcode());
    assertEquals(DATE_VECTOR_OPS_INTERNAL, invoke.owner().asInternalName());
    assertEquals(methodName, invoke.name().stringValue());
    assertEquals(kernelDesc, invoke.typeSymbol());
  }
}
