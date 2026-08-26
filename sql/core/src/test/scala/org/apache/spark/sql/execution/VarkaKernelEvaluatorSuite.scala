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

package org.apache.spark.sql.execution

import org.apache.spark.TaskContext
import org.apache.spark.sql.QueryTest
import org.apache.spark.sql.catalyst.expressions.{Add, Alias, AttributeReference, DateAdd, Literal, NamedExpression}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDebugInfoReader
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.{DateType, IntegerType}
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.sql.vectorized.ColumnarBatch

/**
 * Unit tests for [[VarkaKernelEvaluator]]'s task-12 surface: the column-by-column batch
 * assembly (fused, forwarded, residual) and the ownership discipline it owes the vectors it
 * did not allocate. The oracle for the lifetime tests is the Arrow allocator's memory
 * accounting, as in the exec-node suites: a leak shows as memory above the expected level, a
 * double-close of a forwarded vector as memory below it (or as an Arrow reference-count
 * underflow on the input's own close), so the tests assert exact levels, not merely no crash.
 *
 * The exec-node suites cover the same paths end to end; this suite drives the evaluator
 * directly because `eq` on a forwarded vector and the precise close accounting are not
 * observable through a collected result. It also owns the task-13 telemetry round trip off
 * [[VarkaKernelEvaluator.emittedClassBytes]], the one place the emitted bytes are reachable.
 */
class VarkaKernelEvaluatorSuite extends QueryTest with SharedSparkSession {

  private val attrD = AttributeReference("d", DateType)()
  private val intAttr = AttributeReference("i", IntegerType)()
  private val childOutput = Seq(attrD, intAttr)

  // One fused entry, one forwarded, one residual - the task's canonical mixed projection.
  private val mixedList: Seq[NamedExpression] = Seq(
    Alias(DateAdd(attrD, Literal(3)), "a")(),
    intAttr,
    Alias(Add(intAttr, Literal(1)), "inc")())

  private val dates: Seq[java.lang.Integer] = Seq(0, null, -5, 20000)
  private val ints: Seq[java.lang.Integer] = Seq(10, 11, null, 13)

  private def evaluator(projectList: Seq[NamedExpression] = mixedList): VarkaKernelEvaluator =
    new VarkaKernelEvaluator(projectList, childOutput, offHeapColumnVectorEnabled = false,
      operatorName = "Test")

  /** Runs `body` inside an empty task context with a private Arrow child allocator. */
  private def withTask(body: (ColumnarBatch, () => Unit) => Unit): Unit = {
    val initial = ArrowUtils.rootAllocator.getAllocatedMemory
    val allocator = ArrowUtils.rootAllocator.newChildAllocator("varka-test", 0, Long.MaxValue)
    val context = TaskContext.empty()
    TaskContext.setTaskContext(context)
    try {
      val input = VarkaColumnarToRowExecSuite.buildBatch(
        BatchSpec("arrow", Seq(dates, ints)), childOutput, allocator)
      body(input, () => context.markTaskCompleted(None))
      input.close()
      assert(ArrowUtils.rootAllocator.getAllocatedMemory === initial,
        "the test left Arrow memory allocated")
    } finally {
      TaskContext.unset()
      allocator.close()
    }
  }

  test("a mixed projection assembles fused, forwarded and residual columns in order") {
    withTask { (input, completeTask) =>
      val kernels = evaluator()
      assert(kernels.canRun(input))
      val out = kernels.project(input)
      assert(out.numCols() === 3)
      assert(out.numRows() === dates.length)
      // The forwarded column is the input's own vector - zero copy means the same object.
      assert(out.column(1) eq input.column(1), "the forwarded column was copied")
      val actual = (0 until out.numRows()).map { r =>
        (0 until out.numCols()).map { c =>
          if (out.column(c).isNullAt(r)) null else Int.box(out.column(c).getInt(r))
        }
      }
      val expected = dates.zip(ints).map { case (d, i) =>
        Seq(
          if (d == null) null else Int.box(d + 3),
          i,
          if (i == null) null else Int.box(i + 1))
      }
      assert(actual === expected)
      kernels.release(out)
      completeTask()
    }
  }

  test("release closes exactly the owned vectors and leaves forwarded ones to the input") {
    withTask { (input, completeTask) =>
      val afterInput = ArrowUtils.rootAllocator.getAllocatedMemory
      val kernels = evaluator()
      val out = kernels.project(input)
      assert(ArrowUtils.rootAllocator.getAllocatedMemory > afterInput,
        "the kernel output should have allocated Arrow memory")
      kernels.release(out)
      // Exactly back to the input's level: above would be a leaked kernel vector, below a
      // closed forwarded one.
      assert(ArrowUtils.rootAllocator.getAllocatedMemory === afterInput,
        "release must close the owned vectors and only those")
      // The forwarded vector is still alive and readable through the input.
      assert(input.column(1).getInt(0) === ints.head)
      completeTask()
    }
  }

  test("an abandoned batch is drained by the task-completion listener, forwarded ones spared") {
    withTask { (input, completeTask) =>
      val afterInput = ArrowUtils.rootAllocator.getAllocatedMemory
      val kernels = evaluator()
      kernels.project(input) // never released - a LIMIT-style early stop
      completeTask()
      // The listener closed the owned vectors and then the child allocator; a leaked vector
      // would have made the allocator's close throw, a double-closed forwarded vector would
      // show below the input's level.
      assert(ArrowUtils.rootAllocator.getAllocatedMemory === afterInput,
        "the task-completion listener must drain exactly the owned vectors")
      assert(input.column(1).getInt(0) === ints.head)
    }
  }

  test("a failed projection leaves nothing allocated, on the kernel and the residual path") {
    withTask { (input, completeTask) =>
      val afterInput = ArrowUtils.rootAllocator.getAllocatedMemory
      // Kernel failure: the fused output vectors were allocated before the kernel ran and must
      // be closed on the way out.
      val kernels = evaluator()
      VarkaColumnarToRowExec.setFailKernelForTesting(true)
      try {
        intercept[Throwable](kernels.project(input))
      } finally {
        VarkaColumnarToRowExec.setFailKernelForTesting(false)
      }
      assert(ArrowUtils.rootAllocator.getAllocatedMemory === afterInput,
        "a kernel failure must close the fused output vectors")
      // Residual failure: the residual pass runs after the kernel, so the fused vectors are
      // live when it throws and must be closed too.
      val exploding = evaluator(Seq(
        Alias(DateAdd(attrD, Literal(3)), "a")(),
        Alias(ExplodingCodegenExpression(), "boom")()))
      intercept[Throwable](exploding.project(input))
      assert(ArrowUtils.rootAllocator.getAllocatedMemory === afterInput,
        "a residual failure must close the fused output vectors")
      completeTask()
    }
  }

  test("the emitted class carries the task 13 telemetry, read back off the bytes that ran") {
    withTask { (input, completeTask) =>
      val kernels = evaluator()
      kernels.release(kernels.project(input))
      val bytes = kernels.emittedClassBytes.get
      // The custom attribute through the diagnostics reader: the fused IR, and the whole
      // projection as the plan fragment - the residual entry included, since the point is the
      // fused entries in their context.
      assert(VarkaDebugInfoReader.ir(bytes).contains("AddDays"))
      val fragment = VarkaDebugInfoReader.planFragment(bytes)
      assert(fragment.contains("date_add"))
      assert(fragment.contains("inc"))
      // The SourceFile attribute names the operator and this task's stage.
      assert(VarkaDebugInfoReader.sourceFile(bytes) ===
        s"Varka_Test_Stage${TaskContext.get().stageId()}.java")
      completeTask()
    }
  }
}
