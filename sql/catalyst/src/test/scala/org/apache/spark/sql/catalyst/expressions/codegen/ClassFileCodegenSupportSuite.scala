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

import java.lang.invoke.MethodType

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.{AttributeReference, BoundReference, DateAdd, DateDiff, DateSub, DateVarkaSupport, Literal}
import org.apache.spark.sql.types.{DateType, IntegerType}

/**
 * Task 4: declarative Varka Class-File codegen support for DateAdd/DateSub/DateDiff.
 * Asserts the emission contract (owner/name/descriptor), the assembled bytecode shape (via
 * the Java [[ClassFileGenOpVerifier]] helper), the eligibility matrix, and that genCode
 * registers into the [[CodegenContext]] while keeping the string path intact.
 */
class ClassFileCodegenSupportSuite extends SparkFunSuite {

  private val startAttr = AttributeReference("start", DateType)()
  private val endAttr = AttributeReference("end", DateType)()
  private val otherDateAttr = AttributeReference("other", DateType)()

  test("DateAdd emission contract") {
    val op = DateAdd(startAttr, Literal(3)).classFileGenOp
    assert(op.ownerClassName == "org.apache.spark.sql.varka.vector.DateVectorOps")
    assert(op.methodName == "vectorAddDays")
    assert(op.methodDescriptor == "(JJIJJII)V")
  }

  test("DateSub emission contract") {
    val op = DateSub(startAttr, Literal(3)).classFileGenOp
    assert(op.ownerClassName == "org.apache.spark.sql.varka.vector.DateVectorOps")
    assert(op.methodName == "vectorSubDays")
    assert(op.methodDescriptor == "(JJIJJII)V")
  }

  test("DateDiff emission contract") {
    val op = DateDiff(endAttr, startAttr).classFileGenOp
    assert(op.ownerClassName == "org.apache.spark.sql.varka.vector.DateVectorOps")
    assert(op.methodName == "vectorDateDiff")
    assert(op.methodDescriptor == "(JJIJJIJJI)V")
  }

  test("assembleKernelClass emits the invokestatic contract") {
    val ops = Seq(
      DateAdd(startAttr, Literal(3)),
      DateSub(startAttr, Literal(3)),
      DateDiff(endAttr, otherDateAttr)).map(_.classFileGenOp)
    ops.foreach { op =>
      val bytes = VarkaClassFileGen.assembleKernelClass("EmissionProbe", op)
      ClassFileGenOpVerifier.assertKernelInvocation(
        bytes, op.ownerClassName.replace('.', '/'), op.methodName, op.methodDescriptor)
    }
  }

  test("assembleKernelClass implements the kernel-shape interface of the op") {
    val expected = Seq(
      DateAdd(startAttr, Literal(3)).classFileGenOp -> classOf[VarkaUnaryKernel],
      DateSub(startAttr, Literal(3)).classFileGenOp -> classOf[VarkaUnaryKernel],
      DateDiff(endAttr, otherDateAttr).classFileGenOp -> classOf[VarkaBinaryKernel])
    val loader = new VarkaGeneratedClassLoader(Thread.currentThread().getContextClassLoader)
    try {
      expected.zipWithIndex.foreach { case ((op, iface), i) =>
        val name = s"org.apache.spark.sql.varka.gen.InterfaceProbe$i"
        val clazz = loader.defineGeneratedClass(name,
          VarkaClassFileGen.assembleKernelClass(name, op))
        assert(VarkaClassFileGen.kernelInterface(op) == iface)
        // Instantiating links the class, so this also proves the generated `run` really
        // implements the interface method rather than merely sharing its name.
        assert(iface.isInstance(clazz.getConstructor().newInstance()))
      }
    } finally {
      loader.release()
    }
  }

  test("assembleKernelClass rejects a descriptor with no kernel-shape interface") {
    val op = ClassFileGenOp(
      "org.apache.spark.sql.varka.vector.DateVectorOps", "vectorAddDays", "(JJI)V")
    val e = intercept[IllegalArgumentException] {
      VarkaClassFileGen.assembleKernelClass("org.apache.spark.sql.varka.gen.NoShape", op)
    }
    assert(e.getMessage.contains("(JJI)V"))
    assert(e.getMessage.contains(VarkaUnaryKernel.DESCRIPTOR))
  }

  test("the kernel-shape interfaces match their DESCRIPTOR constants") {
    Seq(
      classOf[VarkaUnaryKernel] -> VarkaUnaryKernel.DESCRIPTOR,
      classOf[VarkaBinaryKernel] -> VarkaBinaryKernel.DESCRIPTOR).foreach { case (iface, desc) =>
      val run = iface.getMethods.filter(_.getName == "run")
      assert(run.length == 1, s"$iface must declare exactly one `run`")
      val actual = MethodType
        .methodType(run.head.getReturnType, run.head.getParameterTypes)
        .toMethodDescriptorString
      assert(actual == desc, s"$iface.DESCRIPTOR is stale")
    }
  }

  test("foldDaysOffset folds integral literals") {
    assert(DateVarkaSupport.foldDaysOffset(Literal(3)) == Some(3))
    assert(DateVarkaSupport.foldDaysOffset(Literal(3: Short)) == Some(3))
    assert(DateVarkaSupport.foldDaysOffset(Literal(3.toByte)) == Some(3))
    assert(DateVarkaSupport.foldDaysOffset(Literal(-7)) == Some(-7))
    assert(DateVarkaSupport.foldDaysOffset(Literal(null, IntegerType)).isEmpty)
  }

  test("DateAdd/DateSub eligibility requires a plain date attribute and foldable days") {
    assert(DateAdd(startAttr, Literal(3)).isClassFileGenEligible)
    assert(DateSub(startAttr, Literal(3)).isClassFileGenEligible)
    assert(!DateAdd(startAttr, AttributeReference("d", IntegerType)()).isClassFileGenEligible)
    assert(!DateAdd(startAttr, Literal(null, IntegerType)).isClassFileGenEligible)
    assert(!DateAdd(Literal(19244, DateType), Literal(3)).isClassFileGenEligible)
    assert(!DateSub(startAttr, AttributeReference("d", IntegerType)()).isClassFileGenEligible)
  }

  test("DateDiff eligibility requires two plain date attributes") {
    assert(DateDiff(endAttr, startAttr).isClassFileGenEligible)
    assert(!DateDiff(endAttr, Literal(19244, DateType)).isClassFileGenEligible)
    assert(!DateDiff(Literal(19244, DateType), startAttr).isClassFileGenEligible)
  }

  test("eligibility accepts bound date column references at codegen time") {
    assert(DateAdd(BoundReference(0, DateType, nullable = true), Literal(3))
      .isClassFileGenEligible)
    assert(DateSub(BoundReference(0, DateType, nullable = true), Literal(3))
      .isClassFileGenEligible)
    assert(DateDiff(
      BoundReference(0, DateType, nullable = true),
      BoundReference(1, DateType, nullable = true)).isClassFileGenEligible)
    assert(!DateAdd(BoundReference(0, IntegerType, nullable = true), Literal(3))
      .isClassFileGenEligible)
  }

  test("VarkaClassFileGen.eligibleOps collects eligible ops in order") {
    val ineligible = AttributeReference("d", IntegerType)()
    val projectList = Seq(
      DateAdd(startAttr, Literal(3)),
      Literal(1),
      DateDiff(endAttr, otherDateAttr),
      DateSub(startAttr, Literal(1)),
      DateAdd(startAttr, ineligible))
    val ops = VarkaClassFileGen.eligibleOps(projectList)
    assert(ops.map(_.methodName) == Seq("vectorAddDays", "vectorDateDiff", "vectorSubDays"))
    assert(ops.forall(_.ownerClassName == "org.apache.spark.sql.varka.vector.DateVectorOps"))
  }

  test("genCode registers into the CodegenContext and keeps the string path") {
    val ctx = new CodegenContext
    val add = DateAdd(BoundReference(0, DateType, nullable = true), Literal(3))
    val code = add.genCode(ctx)
    assert(ctx.isClassFileGenEligible)
    assert(ctx.classFileGenExpressions.toSeq == Seq(add))
    assert(code.code.toString.nonEmpty)
    assert(code.code.toString.contains("+ 3"))
  }

  test("non-Varka expressions do not register") {
    val ctx = new CodegenContext
    Literal(1).genCode(ctx)
    assert(!ctx.isClassFileGenEligible)
    assert(ctx.classFileGenExpressions.isEmpty)
  }
}
