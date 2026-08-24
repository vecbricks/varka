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

import java.lang.classfile.{ClassBuilder, ClassFile, CodeBuilder, TypeKind}
import java.lang.constant.{ClassDesc, ConstantDescs, MethodTypeDesc}
import java.lang.reflect.AccessFlag

import org.apache.spark.sql.catalyst.expressions.{Alias, Expression}

/**
 * The declarative `invokestatic` contract of a Varka batch kernel (Task 4). The argument
 * order of the DateVectorOps methods IS the JVM stack order, so the descriptor fully pins
 * the bytecode emission. Catalyst owns the Class-File assembly on the Java 25+ baseline;
 * the engine module is referenced only by name.
 *
 * @param ownerClassName the binary name of the class owning the kernel.
 * @param methodName the static method name.
 * @param methodDescriptor the JVM method descriptor, e.g. `(JJIJJII)V`.
 */
case class ClassFileGenOp(
    ownerClassName: String,
    methodName: String,
    methodDescriptor: String)

/**
 * Marker trait for expressions that can be compiled to a Varka batch-kernel call instead of
 * per-row string codegen (Task 4). Mixing expressions register themselves into the
 * [[CodegenContext]] registry via `genCode` and keep their existing string codegen path
 * unchanged; routing to the Class-File assembler is a later task.
 */
trait ClassFileCodegenSupport extends Expression {

  /** The `invokestatic` contract for the Varka batch kernel backing this expression. */
  def classFileGenOp: ClassFileGenOp

  /** Whether this expression is eligible for the Varka batch-kernel path (MVP rules). */
  def isClassFileGenEligible: Boolean

  override def genCode(ctx: CodegenContext): ExprCode = {
    ctx.registerClassFileGenExpression(this)
    super.genCode(ctx)
  }
}

/**
 * Plan-level helpers for the Varka Class-File path (Task 4). Catalyst owns the Class-File
 * assembly (Java 25+ baseline), so the kernel contract below is both declared and assembled
 * here; the engine-side integration test independently cross-checks the descriptors against
 * `DateVectorOps` via reflection.
 */
object VarkaClassFileGen {

  /**
   * The interface a generated runner implements, keyed by the descriptor of the kernel it
   * dispatches to. The descriptor is the whole of the kernel's shape - argument order IS the
   * JVM stack order - so it is also what selects the call-site view the execution path uses.
   * A new kernel shape means a new interface here.
   */
  private val kernelInterfaces: Map[String, Class[_]] = Map(
    VarkaUnaryKernel.DESCRIPTOR -> classOf[VarkaUnaryKernel],
    VarkaBinaryKernel.DESCRIPTOR -> classOf[VarkaBinaryKernel])

  /** The Varka-eligible ops of a projection's expression list, in order. */
  def eligibleOps(projectList: Seq[Expression]): Seq[ClassFileGenOp] = {
    projectList.collect {
      case Alias(e: ClassFileCodegenSupport, _) if e.isClassFileGenEligible => e.classFileGenOp
      case e: ClassFileCodegenSupport if e.isClassFileGenEligible => e.classFileGenOp
    }
  }

  /**
   * The interface that a runner assembled for this op implements, i.e. the type the execution
   * path casts the instantiated runner to. Throws if the op's descriptor is not one of the
   * known kernel shapes, rather than assembling a class that implements nothing.
   */
  def kernelInterface(op: ClassFileGenOp): Class[_] = {
    kernelInterfaces.getOrElse(op.methodDescriptor,
      throw new IllegalArgumentException(
        s"No Varka kernel interface for the descriptor ${op.methodDescriptor} of " +
          s"${op.ownerClassName}.${op.methodName}; the known kernel shapes are " +
          kernelInterfaces.keys.toSeq.sorted.mkString(", ")))
  }

  /**
   * Assembles the class bytes of a runner that invokes the op's kernel: a public class with a
   * default constructor, implementing the [[kernelInterface]] for the op's shape, whose `run`
   * method loads the kernel parameters in order and invokes them with a single `invokestatic`.
   *
   * The method is an instance method implementing the interface rather than a static one so
   * that the execution path can reach it with an ordinary interface call, keeping the
   * arguments primitive from the caller's stack all the way into the kernel. Reflection would
   * box each of them and allocate an argument array per batch, which is precisely what the
   * generated dispatcher exists to avoid. Slot 0 therefore holds `this`, and the parameters
   * start at slot 1.
   */
  def assembleKernelClass(className: String, op: ClassFileGenOp): Array[Byte] = {
    val classDesc = ClassDesc.of(className)
    val kernelDesc = MethodTypeDesc.ofDescriptor(op.methodDescriptor)
    val interfaceDesc = ClassDesc.of(kernelInterface(op).getName)
    ClassFile.of().build(classDesc, (b: ClassBuilder) => b
      .withFlags(AccessFlag.PUBLIC)
      .withInterfaceSymbols(interfaceDesc)
      .withMethodBody("<init>", MethodTypeDesc.of(ConstantDescs.CD_void),
        AccessFlag.PUBLIC.mask(),
        (cb: CodeBuilder) => {
          cb.aload(0)
          cb.invokespecial(ConstantDescs.CD_Object, "<init>",
            MethodTypeDesc.of(ConstantDescs.CD_void))
          cb.return_()
          ()
        })
      .withMethodBody("run", kernelDesc, AccessFlag.PUBLIC.mask(),
        (cb: CodeBuilder) => {
          var slot = 1
          var i = 0
          while (i < kernelDesc.parameterCount()) {
            val pDesc = kernelDesc.parameterList().get(i).descriptorString()
            val kind = TypeKind.fromDescriptor(pDesc.substring(0, 1))
            if (kind == TypeKind.LONG) {
              cb.lload(slot)
              slot += 2
            } else {
              cb.iload(slot)
              slot += 1
            }
            i += 1
          }
          cb.invokestatic(ClassDesc.of(op.ownerClassName), op.methodName, kernelDesc)
          cb.return_()
          ()
        }))
  }
}
