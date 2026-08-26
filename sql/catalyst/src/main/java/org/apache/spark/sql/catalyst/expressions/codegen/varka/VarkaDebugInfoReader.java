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

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;

/**
 * The diagnostics helper over the task 13 telemetry: given raw bytes of an emitted fused-kernel
 * class, reads back the {@code SourceFile} name and the {@link VarkaDebugInfo} payload as plain
 * strings. This is the intended entry point for tooling and tests over captured classes; the
 * typed view stays on {@link VarkaDebugInfo#read}.
 *
 * <p>Deliberately a plain-signature Java class: Scala 2.13's typechecker hits an "illegal
 * cyclic reference" on the Class-File API's sealed hierarchy (the bug that keeps
 * {@link VarkaLoopEmitter} in Java), and that extends to {@link VarkaDebugInfo} itself, whose
 * supertype is part of that hierarchy - so any Scala caller, the evaluator's own tests
 * included, must go through signatures that never mention either.
 */
public final class VarkaDebugInfoReader {

  private VarkaDebugInfoReader() {
  }

  /** The class's {@code SourceFile} attribute value, or null when the class carries none. */
  public static String sourceFile(byte[] classBytes) {
    ClassModel model = ClassFile.of().parse(classBytes);
    return model.findAttribute(Attributes.sourceFile())
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
