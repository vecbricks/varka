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
   * Toggles {@link VarkaLoopEmitter#disableCseForTesting} for callers outside the emitter's
   * package - the parity benchmark prices CSE by emitting the same trees with the memo off.
   */
  public static void setDisableCse(boolean disable) {
    VarkaLoopEmitter.disableCseForTesting = disable;
  }

  /**
   * Toggles {@link VarkaLoopEmitter#divFloorModForTesting} for callers outside the emitter's
   * package - the parity benchmark prices the shipped digit-sum mod-7 against the lanewise-DIV
   * reference variant (task 11, plan 2.3).
   */
  public static void setDivFloorMod(boolean div) {
    VarkaLoopEmitter.divFloorModForTesting = div;
  }
}
