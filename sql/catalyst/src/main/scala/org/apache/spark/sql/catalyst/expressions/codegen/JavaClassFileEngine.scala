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

package org.apache.spark.sql.catalyst.expressions.codegen

import java.util.concurrent.atomic.AtomicInteger

import scala.util.control.NonFatal

import org.apache.spark.util.Utils

/**
 * The Varka Class-File assembly engine (Task 5). It assembles a full [[GeneratedClass]] shell
 * with the Class-File API (see [[ClassFileAssembler]]) and loads it through a
 * [[VarkaGeneratedClassLoader]].
 *
 * It is deliberately *not* wired into the [[CodeGenerator.compile]] funnel. The assembled
 * `VarkaProjection.apply` is still a stub that throws `UnsupportedOperationException`, so a
 * successful assembly would be a ghost: assembly and loading both succeed, no fallback sees a
 * failure, and the projection blows up at row-evaluation time with no way to recover. Routing
 * belongs in the change that gives `apply` a real body. That change also has to make the
 * compile cache tell an assembled entry from a Janino-compiled one, by putting the routing
 * decision in the key - see the note on [[CodeAndComment]], whose `classFileGenOps` are part
 * of the key but do not by themselves separate the two.
 *
 * The live Varka execution path does not come through here: `VarkaColumnarToRowExec` (Task 6)
 * assembles its per-op kernel dispatchers with `VarkaClassFileGen.assembleKernelClass`.
 */
private[expressions] object JavaClassFileEngine {

  /** The binary name of the assembled wrapper class. */
  private val WrapperClassName = "org.apache.spark.sql.varka.execution.GeneratedClass"

  /** The binary name of the assembled projection class. */
  private val SpecClassName = "org.apache.spark.sql.varka.execution.VarkaProjection"

  /**
   * Test injection: when true, `assembleGeneratedClass` flips the wrapper class's magic
   * number so the JVM rejects the bytes at definition time with a `ClassFormatError` (a
   * [[LinkageError]]). Exercises the load-failure path of [[assembleAndLoad]], which releases
   * the loader and rethrows.
   */
  @volatile private[expressions] var corruptAssemblyForTesting: Boolean = false

  /** Number of times `assembleGeneratedClass` has run; lets tests assert no re-assembly. */
  private[expressions] val assemblyAttempts = new AtomicInteger(0)

  /**
   * Assembles the full [[GeneratedClass]] shell as two classes: a public wrapper `className`
   * extending [[GeneratedClass]] with `generate(Object[])`, and `VarkaProjection`
   * extending [[UnsafeProjection]]. Returns each class's binary name and bytes.
   */
  def assembleGeneratedClass(className: String): Seq[(String, Array[Byte])] = {
    assemblyAttempts.incrementAndGet()
    val bytes = ClassFileAssembler.assembleGeneratedClass(className, SpecClassName)
    if (corruptAssemblyForTesting) {
      val corruptedWrapper = bytes(0).clone()
      corruptedWrapper(0) = (corruptedWrapper(0) ^ 0xff.toByte).toByte
      Seq((className, corruptedWrapper), (SpecClassName, bytes(1)))
    } else {
      Seq((className, bytes(0)), (SpecClassName, bytes(1)))
    }
  }

  /** Failures worth releasing the loader for: [[NonFatal]] plus [[LinkageError]] (bad bytecode
   * surfaces as `VerifyError`/`ClassFormatError`, a missing class as `NoClassDefFoundError`). */
  private def isCatchable(e: Throwable): Boolean = NonFatal(e) || e.isInstanceOf[LinkageError]

  /**
   * Assembles, loads and instantiates the [[GeneratedClass]] shell. On a load failure the
   * loader is released before the error is rethrown, so a rejected class definition does not
   * strand a Metaspace-holding loader.
   */
  def assembleAndLoad(): (GeneratedClass, ByteCodeStats) = {
    val classes = assembleGeneratedClass(WrapperClassName)
    val loader = new VarkaGeneratedClassLoader(Utils.getContextOrSparkClassLoader)
    try {
      classes.foreach { case (name, bytes) => loader.defineGeneratedClass(name, bytes) }
      val clazz = loader.loadClass(WrapperClassName)
      val generated = clazz.getConstructor().newInstance().asInstanceOf[GeneratedClass]
      (generated, CodeCompiler.computeByteCodeStats(classes))
    } catch {
      case e: Throwable if isCatchable(e) =>
        loader.release()
        throw e
    }
  }
}
