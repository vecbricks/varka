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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader;

/**
 * One cached shape: the loader that defined the class, the class itself, the bytes it was defined
 * from (kept for diagnostics - {@code VarkaDebugInfo.read} and the class dump work off them; their
 * footprint is bounded by the cache size), and the kernel's resolved no-argument constructor. The
 * cache owns the loader and releases it on eviction; a running task's strong references to the
 * class and kernel keep them alive past that, which is the whole release contract.
 *
 * <p>This record folds two concerns together. The constructor is resolved once, in
 * {@link VarkaShapeCacheImpl}'s emit, rather than by a {@code getConstructor} lookup on every
 * {@link #newKernel()} - which runs once per task, per kernel. And {@link #className()} /
 * {@link #sourceFile()} are derived from {@link #shapeHash()} instead of being stored beside it:
 * both are pure functions of the hash, and two fields that must agree with a third are two fields
 * that can disagree with it.
 *
 * <p>{@code warmth} is the one mutable component: whether the shape's kernel is compiled yet,
 * shared by every task that runs the shape so that one warm-up serves them all
 * ({@link VarkaKernelWarmth}). It lives here because it describes the loaded class - a shape
 * emitted again into a new class starts cold again, whatever its predecessor reached.
 *
 * <p>This is a record for its constructor and accessors, not for its equality. Two of its
 * components - {@code classBytes} and {@code klass} - have identity equality, so the generated
 * {@code equals} is identity-ish rather than structural. That is harmless because entries are only
 * ever compared by reference (the suites use {@code eq}/{@code ne}) and never used as map keys,
 * but a record <i>looks</i> like it has value equality, so: it does not, and nothing may start
 * relying on it having it.
 */
public record VarkaShapeEntry(
    VarkaGeneratedClassLoader loader,
    Class<?> klass,
    byte[] classBytes,
    String shapeHash,
    Constructor<?> constructor,
    VarkaKernelWarmth warmth) {

  /** The shape-named class name, derived from the hash; see {@link VarkaShapeCacheImpl}. */
  public String className() {
    return VarkaShapeCacheImpl.classNameFor(shapeHash);
  }

  /** The shape-named {@code SourceFile}, derived from the hash. */
  public String sourceFile() {
    return VarkaShapeCacheImpl.sourceFileFor(shapeHash);
  }

  /**
   * A fresh kernel instance; each task instantiates its own, only the class is shared.
   *
   * <p>A reflective failure here is rethrown as itself rather than wrapped, for the same reason
   * the cache unwraps Guava's wrappers: the evaluator's {@code isCatchable} test has to see the
   * original, or a fatal error would be counted as an ordinary kernel failure. That takes an
   * explicit unwrap, because {@link Constructor#newInstance} wraps whatever the constructor body
   * throws in an {@link InvocationTargetException} - which is itself {@code NonFatal}, so
   * rethrowing the wrapper would make every constructor failure look catchable, including the
   * ones that are not. The remaining {@link ReflectiveOperationException}s describe the class
   * rather than its execution and are rethrown as they are.
   */
  public VarkaFusedKernel newKernel() {
    try {
      return (VarkaFusedKernel) constructor.newInstance();
    } catch (InvocationTargetException e) {
      throw VarkaShapeCacheImpl.sneakyThrow(e.getCause());
    } catch (ReflectiveOperationException e) {
      throw VarkaShapeCacheImpl.sneakyThrow(e);
    }
  }
}
