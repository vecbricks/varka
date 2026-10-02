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
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The reference locals an emitted class's loop and epilogue methods store and never read, read
 * off the bytecode: what {@code VarkaUnreadLocalsSuite} holds the cost corpus to. A shared slot
 * (task 223) is the store after a {@code dup} that parks a node's vector for a second use, so one
 * nothing loads is a slot the body's use count gave a node visited once.
 *
 * <p>No Class-File API type appears in a signature here: the Scala suites read this class's
 * signatures, and scalac cannot load that API's sealed builders.
 */
final class VarkaUnreadLocals {

  private VarkaUnreadLocals() {}

  /**
   * Each shared slot of a loop or epilogue method of {@code bytes} that nothing in the method
   * reads, as {@code "method: local n"}.
   */
  static List<String> unreadSharedSlots(byte[] bytes) {
    return sharedSlots(bytes, false);
  }

  /** As {@link #unreadSharedSlots}, for the shared slots the method does read. */
  static List<String> readSharedSlots(byte[] bytes) {
    return sharedSlots(bytes, true);
  }

  private static List<String> sharedSlots(byte[] bytes, boolean read) {
    List<String> found = new ArrayList<>();
    ClassModel cm = ClassFile.of().parse(bytes);
    for (MethodModel mm : cm.methods()) {
      String name = mm.methodName().stringValue();
      if (!(name.startsWith("loop") || name.startsWith("epilogue")) || mm.code().isEmpty()) {
        continue;
      }
      List<CodeElement> elements = mm.code().get().elementList();
      Set<Integer> loaded = new HashSet<>();
      for (CodeElement e : elements) {
        if (e instanceof LoadInstruction load && load.typeKind() == TypeKind.REFERENCE) {
          loaded.add(load.slot());
        }
      }
      Instruction previous = null;
      for (CodeElement e : elements) {
        if (!(e instanceof Instruction instruction)) {
          continue;
        }
        if (instruction instanceof StoreInstruction store
            && store.typeKind() == TypeKind.REFERENCE
            && previous instanceof StackInstruction dup && dup.opcode() == Opcode.DUP
            && loaded.contains(store.slot()) == read) {
          found.add(name + ": local " + store.slot());
        }
        previous = instruction;
      }
    }
    return found;
  }
}
