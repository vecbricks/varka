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

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{AttributeReference, DateAdd, DateSub, GenericInternalRow, Literal, UnsafeProjection}
import org.apache.spark.sql.types.DateType

/**
 * Task 5: the Varka Class-File assembly engine and its routing through the
 * [[CodeGenerator]] compile funnel. Asserts that routing is inert by default, that an
 * eligible projection is assembled (cached) when routing is enabled, that an assembly
 * failure (or the explicit test injection) falls back to the string backend without
 * crashing, and that the assembled classes expose the full [[GeneratedClass]] shape (via
 * the Java [[ClassFileShapeVerifier]] helper) on a [[VarkaGeneratedClassLoader]].
 */
class JavaClassFileEngineSuite extends SparkFunSuite {

  import JavaClassFileEngine._

  private val startAttr = AttributeReference("d", DateType)()
  private val schema = Seq(startAttr)

  override def beforeEach(): Unit = {
    super.beforeEach()
    CodeGenerator.invalidateCodegenCache()
    routingEnabledForTesting = false
    failAssemblyForTesting = false
    corruptAssemblyForTesting = false
    assemblyAttempts.set(0)
  }

  override def afterEach(): Unit = {
    routingEnabledForTesting = false
    failAssemblyForTesting = false
    corruptAssemblyForTesting = false
    super.afterEach()
  }

  private def dateRow(days: Int): InternalRow = {
    val row = new GenericInternalRow(1)
    row.setInt(0, days)
    row
  }

  test("routing is off by default: the eligible projection goes through Janino") {
    val proj = GenerateUnsafeProjection.generate(Seq(DateAdd(startAttr, Literal(3))), schema)
    assert(!proj.getClass.getName.startsWith("org.apache.spark.sql.varka"))
    assert(assemblyAttempts.get() == 0)
    assert(proj.apply(dateRow(19244)).getInt(0) == 19247)
  }

  test("routing assembles the Varka shell and caches the result") {
    routingEnabledForTesting = true
    val proj = GenerateUnsafeProjection.generate(Seq(DateSub(startAttr, Literal(5))), schema)
    assert(proj.getClass.getName == "org.apache.spark.sql.varka.execution.VarkaProjection")
    assert(assemblyAttempts.get() == 1)
    val e = intercept[UnsupportedOperationException] {
      proj.apply(dateRow(19244))
    }
    assert(e.getMessage.contains("Varka batch execution wired in Task 6"))
    // A second compile with the same code body hits the cache: no re-assembly.
    GenerateUnsafeProjection.generate(Seq(DateSub(startAttr, Literal(5))), schema)
    assert(assemblyAttempts.get() == 1)
  }

  test("assembly failure falls back to the string backend and is cached") {
    routingEnabledForTesting = true
    failAssemblyForTesting = true
    val proj = GenerateUnsafeProjection.generate(Seq(DateAdd(startAttr, Literal(9))), schema)
    assert(!proj.getClass.getName.startsWith("org.apache.spark.sql.varka"))
    assert(assemblyAttempts.get() == 0)
    assert(proj.apply(dateRow(19244)).getInt(0) == 19253)
    // The failed attempt's Janino result is cached under the same key.
    GenerateUnsafeProjection.generate(Seq(DateAdd(startAttr, Literal(9))), schema)
    assert(assemblyAttempts.get() == 0)
  }

  test("a LinkageError during class definition falls back to the string backend") {
    routingEnabledForTesting = true
    corruptAssemblyForTesting = true
    val proj = GenerateUnsafeProjection.generate(Seq(DateSub(startAttr, Literal(5))), schema)
    assert(!proj.getClass.getName.startsWith("org.apache.spark.sql.varka"))
    // Unlike failAssemblyForTesting (which short-circuits), assembly was actually attempted
    // and the JVM rejected the corrupt bytes with a ClassFormatError (a LinkageError).
    assert(assemblyAttempts.get() == 1)
    assert(proj.apply(dateRow(19244)).getInt(0) == 19239)
    // The failed attempt's Janino result is cached under the same key.
    GenerateUnsafeProjection.generate(Seq(DateSub(startAttr, Literal(5))), schema)
    assert(assemblyAttempts.get() == 1)
  }

  test("assembleGeneratedClass exposes the full GeneratedClass shape") {
    val classes = assembleGeneratedClass("org.apache.spark.sql.varka.execution.GeneratedClass")
    assert(classes.size == 2)
    val (wrapperName, wrapperBytes) = classes.head
    val (specName, specBytes) = classes(1)
    assert(wrapperName == "org.apache.spark.sql.varka.execution.GeneratedClass")
    assert(specName == "org.apache.spark.sql.varka.execution.VarkaProjection")
    ClassFileShapeVerifier.assertGeneratedClassShape(wrapperBytes, specBytes)
  }

  test("VarkaGeneratedClassLoader defines, resolves and releases generated classes") {
    val classes = assembleGeneratedClass("org.apache.spark.sql.varka.execution.GeneratedClass")
    val loader = new VarkaGeneratedClassLoader(Thread.currentThread().getContextClassLoader)
    classes.foreach { case (name, bytes) => loader.defineGeneratedClass(name, bytes) }
    val clazz = loader.loadClass("org.apache.spark.sql.varka.execution.GeneratedClass")
    val generated = clazz.getConstructor().newInstance().asInstanceOf[GeneratedClass]
    val proj = generated.generate(Array.empty[Any]).asInstanceOf[UnsafeProjection]
    assert(proj.getClass.getName == "org.apache.spark.sql.varka.execution.VarkaProjection")
    // The kernel owner FQCN resolves via the parent loader (test stub on the classpath).
    val kernel = loader.loadClass("org.apache.spark.sql.varka.vector.DateVectorOps")
    assert(kernel.getName == "org.apache.spark.sql.varka.vector.DateVectorOps")
    loader.release()
    assert(loader.isReleased)
    intercept[IllegalStateException] {
      loader.defineGeneratedClass(
        "org.apache.spark.sql.varka.execution.GeneratedClass", classes.head._2)
    }
  }
}
