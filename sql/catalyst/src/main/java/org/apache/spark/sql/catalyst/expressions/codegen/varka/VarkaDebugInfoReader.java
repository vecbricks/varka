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

/**
 * The diagnostics helper over the task 13 telemetry: given raw bytes of an emitted fused-kernel
 * class, reads back the {@code SourceFile} name and the {@link VarkaDebugInfo} payload as plain
 * strings. This is the intended entry point for tooling and tests over captured classes; the
 * typed view stays on {@link VarkaDebugInfo#read}.
 *
 * <p>Deliberately a plain-signature Java class with no {@code java.lang.classfile} import
 * (fully-qualified names in method bodies only): Scala 2.13's typechecker hits an "illegal
 * cyclic reference" completing much of the Class-File API's sealed hierarchy - the bug that
 * keeps {@link VarkaLoopEmitter} in Java and shaped {@link VarkaDebugInfo}'s structure, see
 * its class doc - so Scala callers, the evaluator's own tests included, go through signatures
 * that never mention a class-file type, and these files keep the types where scalac never
 * reads them.
 */
public final class VarkaDebugInfoReader {

  private VarkaDebugInfoReader() {
  }

  /** The class's {@code SourceFile} attribute value, or null when the class carries none. */
  public static String sourceFile(byte[] classBytes) {
    return java.lang.classfile.ClassFile.of().parse(classBytes)
        .findAttribute(java.lang.classfile.Attributes.sourceFile())
        .map(attr -> attr.sourceFile().stringValue()).orElse(null);
  }

  /** The rendered IR from the class's {@link VarkaDebugInfo}, or null when it carries none. */
  public static String ir(byte[] classBytes) {
    return VarkaDebugInfo.read(classBytes).map(VarkaDebugInfo::ir).orElse(null);
  }

  /** The plan fragment from the class's {@link VarkaDebugInfo}, or null when it carries none. */
  public static String planFragment(byte[] classBytes) {
    return VarkaDebugInfo.read(classBytes).map(VarkaDebugInfo::planFragment).orElse(null);
  }
}
