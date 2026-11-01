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

package org.apache.spark.sql.catalyst.expressions.codegen.varka

import org.apache.spark.SparkFunSuite

/**
 * The probe-output reader against the lines that broke its predecessors: a marker glued to a
 * compile record on either side, which is what a compiler thread writing to the probe's stream
 * produces (`m6/PLAN.md` row 229).
 */
class VarkaProbeOutputSuite extends SparkFunSuite with VarkaMatrixTests {

  // A `PrintCompilation` record as HotSpot prints it, timestamp padded with spaces.
  private val record =
    "   2661 3860    b  3       org.apache.spark.sql.GeneratedClass$Stage1::apply (45 bytes)"

  test("a marker is found on its own line, after a compile record and before one") {
    Seq("VARKA_X_DONE", record + "VARKA_X_DONE", "VARKA_X_DONE" + record).foreach { line =>
      assert(VarkaProbeOutput.has(line, "VARKA_X_DONE"), line)
    }
    assert(!VarkaProbeOutput.has(record, "VARKA_X_DONE"))
  }

  test("a number after a prefix is read whatever the line carries around it") {
    Seq("VARKA_X_BYTES=8123", "  VARKA_X_BYTES=8123 ", record + "VARKA_X_BYTES=8123",
      "VARKA_X_BYTES=8123" + record).foreach { line =>
      assert(VarkaProbeOutput.longAfter(line, "VARKA_X_BYTES=").contains(8123L), line)
    }
    assert(VarkaProbeOutput.longAfter("VARKA_X_BYTES=", "VARKA_X_BYTES=").isEmpty)
    assert(VarkaProbeOutput.longAfter("VARKA_X_BYTES=x", "VARKA_X_BYTES=").isEmpty)
    assert(VarkaProbeOutput.longAfter(record, "VARKA_X_BYTES=").isEmpty)
    assert(VarkaProbeOutput.longAfter("VARKA_X_USE_AVX=-1", "VARKA_X_USE_AVX=").contains(-1L))
  }

  test("a name after a prefix stops at whitespace, so a glued record is left out") {
    Seq("VARKA_X_SHAPE_BEGIN cast_date", "VARKA_X_SHAPE_BEGIN cast_date" + record,
      record + "VARKA_X_SHAPE_BEGIN cast_date").foreach { line =>
      assert(VarkaProbeOutput.tokenAfter(line, "VARKA_X_SHAPE_BEGIN ").contains("cast_date"),
        line)
    }
    assert(VarkaProbeOutput.tokenAfter("VARKA_X_SHAPE_BEGIN ", "VARKA_X_SHAPE_BEGIN ").isEmpty)
    // A payload without spaces, such as a list of `name:bytes:sites`, is one token.
    assert(VarkaProbeOutput.tokenAfter("VARKA_X_METHODS=loopDense0:8000:93,loopMasked0:8100:93" +
      record, "VARKA_X_METHODS=").contains("loopDense0:8000:93,loopMasked0:8100:93"))
  }

  test("a name with spaces is read up to its end mark, whatever follows the mark") {
    val name = "greatest(add_months(d, 1), last_day(d))"
    Seq(s"VARKA_X_SHAPE_BEGIN $name VARKA_X_NAME_END",
      s"VARKA_X_SHAPE_BEGIN $name VARKA_X_NAME_END" + record,
      record + s"VARKA_X_SHAPE_BEGIN $name VARKA_X_NAME_END").foreach { line =>
      assert(VarkaProbeOutput.between(line, "VARKA_X_SHAPE_BEGIN ", " VARKA_X_NAME_END")
        .contains(name), line)
    }
    // Without the end mark the name runs to the end of the line.
    assert(VarkaProbeOutput.between(s"VARKA_X_SHAPE_BEGIN $name", "VARKA_X_SHAPE_BEGIN ",
      " VARKA_X_NAME_END").contains(name))
  }

  test("a multi-line failure folded by the audit probe reads back whole between its marks") {
    // An exception message with a line that starts like a C2 refusal, which the audit reader
    // would otherwise file under the shape being run (VARKA-538).
    val failure = "IllegalAccessException: jdk.internal.vm.vector not exported\n" +
      "** not supported: arity=1\r\tat C:\\x" + 1.toChar
    val folded = VarkaWidthAuditProbe.oneLine(failure)
    assert(!folded.exists(Character.isISOControl), folded)
    assert(folded === "IllegalAccessException: jdk.internal.vm.vector not exported\\n" +
      "** not supported: arity=1\\r\\tat C:\\\\x\\" + "u0001")
    val prefix = VarkaWidthAuditProbe.FEATURES_FAILURE_PREFIX
    val end = VarkaWidthAuditProbe.FEATURES_FAILURE_END
    Seq(prefix + folded + end, prefix + folded + end + record).foreach { line =>
      assert(VarkaProbeOutput.between(line, prefix, end).contains(folded), line)
    }
    assert(VarkaProbeOutput.between(prefix + VarkaWidthAuditProbe.oneLine("") + end + record,
      prefix, end).contains(""))
  }

  test("the audit probe's printed failure line reads back through audit()'s reader") {
    // The line exactly as VarkaWidthAuditProbe.main prints it, read by the function audit()
    // calls: a planted refusal stays inside the line, and a record glued after it is left out.
    val failure = "java.lang.IllegalAccessException: not exported\n** planted refusal"
    val printed = VarkaWidthAuditProbe.featuresFailureLine(failure)
    assert(printed.linesIterator.size === 1, printed)
    assert(!printed.trim.startsWith("** "), printed)
    val folded = "java.lang.IllegalAccessException: not exported\\n** planted refusal"
    Seq(printed, printed + record, record + printed).foreach { line =>
      assert(VarkaWidthAuditProbe.featuresFailureIn(line) === Some(Some(folded)), line)
    }
    // A read that succeeded prints nothing between the marks, which reads as success.
    val read = VarkaWidthAuditProbe.featuresFailureLine(null)
    Seq(read, read + record).foreach { line =>
      assert(VarkaWidthAuditProbe.featuresFailureIn(line) === Some(None), line)
    }
    // Without the end mark the text after the prefix may be a glued record, so the failure is
    // unknown and says so; it is neither success nor that text.
    val garbled = VarkaWidthAuditProbe.FEATURES_FAILURE_PREFIX + record
    val unknown = VarkaWidthAuditProbe.featuresFailureIn(garbled)
    assert(unknown.flatten.exists(_.startsWith(VarkaWidthAuditProbe.FEATURES_FAILURE_UNKNOWN)),
      unknown)
    assert(VarkaWidthAuditProbe.featuresFailureIn(record).isEmpty)
  }

  test("the text after a prefix keeps the payload's fields, with a glued record after them") {
    val line = record + "VARKA_X_BEGIN=probe pid=41 outputs=16 ceiling=400" + record
    val payload = VarkaProbeOutput.after(line, "VARKA_X_BEGIN=").get
    assert(payload.startsWith("probe pid=41 outputs=16 ceiling=400"), payload)
    assert(VarkaProbeOutput.after(record, "VARKA_X_BEGIN=").isEmpty)
  }
}
