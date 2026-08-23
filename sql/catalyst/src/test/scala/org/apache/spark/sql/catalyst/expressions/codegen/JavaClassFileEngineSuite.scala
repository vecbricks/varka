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
 * Task 5: the Varka Class-File assembly engine. Asserts that the [[CodeGenerator]] compile
 * funnel never routes a Class-File-eligible unit to the engine, that [[JavaClassFileEngine]]
 * assembles and loads the full [[GeneratedClass]] shell, that the assembled `apply` is still a
 * stub - which is exactly why the funnel does not route to it - that a rejected class
 * definition surfaces as a [[LinkageError]], and that the assembled classes expose the full
 * [[GeneratedClass]] shape (via the Java [[ClassFileShapeVerifier]] helper) on a
 * [[VarkaGeneratedClassLoader]].
 */
class JavaClassFileEngineSuite extends SparkFunSuite {

  import JavaClassFileEngine._

  private val startAttr = AttributeReference("d", DateType)()
  private val schema = Seq(startAttr)

  override def beforeEach(): Unit = {
    super.beforeEach()
    CodeGenerator.invalidateCodegenCache()
    corruptAssemblyForTesting = false
    assemblyAttempts.set(0)
  }

  override def afterEach(): Unit = {
    corruptAssemblyForTesting = false
    super.afterEach()
  }

  private def dateRow(days: Int): InternalRow = {
    val row = new GenericInternalRow(1)
    row.setInt(0, days)
    row
  }

  test("the compile funnel never routes a Class-File-eligible unit to the engine") {
    val proj = GenerateUnsafeProjection.generate(Seq(DateAdd(startAttr, Literal(3))), schema)
    assert(!proj.getClass.getName.startsWith("org.apache.spark.sql.varka"))
    assert(assemblyAttempts.get() == 0)
    assert(proj.apply(dateRow(19244)).getInt(0) == 19247)
    // A second compile of the same body hits the cache, and still assembles nothing.
    GenerateUnsafeProjection.generate(Seq(DateAdd(startAttr, Literal(3))), schema)
    assert(assemblyAttempts.get() == 0)
  }

  test("assembleAndLoad returns a shell whose apply is still a stub") {
    val (generated, stats) = assembleAndLoad()
    assert(assemblyAttempts.get() == 1)
    assert(stats.maxMethodCodeSize > 0)
    val proj = generated.generate(Array.empty[Any]).asInstanceOf[UnsafeProjection]
    assert(proj.getClass.getName == "org.apache.spark.sql.varka.execution.VarkaProjection")
    // This is why the funnel does not route here: assembly, loading and construction all
    // succeed, so a fallback around them sees no failure at all, and the throw lands at
    // row-evaluation time where nothing can recover from it.
    val e = intercept[UnsupportedOperationException] {
      proj.apply(dateRow(19244))
    }
    assert(e.getMessage.contains("Varka batch execution wired in Task 6"))
  }

  test("a rejected class definition surfaces as a LinkageError") {
    corruptAssemblyForTesting = true
    intercept[LinkageError] {
      assembleAndLoad()
    }
    // Assembly was attempted; the JVM rejected the corrupt bytes with a ClassFormatError, and
    // `assembleAndLoad` released the loader before rethrowing.
    assert(assemblyAttempts.get() == 1)
  }

  test("CodeAndComment keys the compile cache on the Class-File ops, not the body alone") {
    val op = DateSub(startAttr, Literal(5)).classFileGenOp
    val plain = new CodeAndComment("body", Map.empty)
    val withOps = new CodeAndComment("body", Map.empty, Seq(op))
    assert(plain != withOps)
    assert(plain.hashCode != withOps.hashCode)
    assert(withOps == new CodeAndComment("body", Map.empty, Seq(op)))
    assert(withOps.hashCode == new CodeAndComment("body", Map.empty, Seq(op)).hashCode)
    // The comment map is debug metadata for the same source, so it stays out of the key.
    assert(plain == new CodeAndComment("body", Map("placeholder" -> "// a comment")))
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
