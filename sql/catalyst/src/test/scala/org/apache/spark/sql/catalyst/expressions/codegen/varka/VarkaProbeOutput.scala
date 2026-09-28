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

/**
 * Reads the markers a forked probe prints, on a stream other writers share. The probes of the
 * Varka suites run under `-XX:+PrintCompilation` or `PrintInlining` with stderr merged into
 * stdout, and HotSpot's compiler threads and log4j write to that stream without coordinating
 * with the probe's `System.out`, so a marker can share a line with a compile record, on either
 * side of it. A reader that requires the marker on a line of its own then misses it, and the
 * suite fails on a probe that did its work (`PLAN_MILESTONE_6.md` row 229). So a marker is
 * found anywhere in a line, and its value is read from what follows it: a number by its digits,
 * a name up to the next whitespace or up to the end mark the probe prints after a name that
 * holds spaces, and a payload of `key=value` fields as the rest of the line, which a glued
 * record can only lengthen. A line that carries a marker may carry a compile
 * record too, so a reader looks for both in every line rather than one or the other.
 *
 * HotSpot pads a compile record's timestamp with spaces, which is what keeps a number's digits
 * from running into a record glued after them.
 */
object VarkaProbeOutput {

  /** Whether `marker` occurs anywhere in `line`. */
  def has(line: String, marker: String): Boolean = line.contains(marker)

  /** The text after the first `prefix` in `line`, or None where the prefix does not occur. */
  def after(line: String, prefix: String): Option[String] = {
    val at = line.indexOf(prefix)
    if (at < 0) None else Some(line.substring(at + prefix.length))
  }

  /** The first whitespace-delimited token after `prefix`, or None where there is none. */
  def tokenAfter(line: String, prefix: String): Option[String] =
    after(line, prefix).map(_.trim.takeWhile(!_.isWhitespace)).filter(_.nonEmpty)

  /**
   * The text between `prefix` and `end`, trimmed, or up to the end of the line where `end` is
   * absent: for a name that may hold spaces, which the probe closes with `end` so that a record
   * glued after it is left out.
   */
  def between(line: String, prefix: String, end: String): Option[String] =
    after(line, prefix).map { rest =>
      val at = rest.indexOf(end)
      (if (at < 0) rest else rest.substring(0, at)).trim
    }

  /** The integer the text after `prefix` starts with, or None where it starts with none. */
  def longAfter(line: String, prefix: String): Option[Long] =
    after(line, prefix).flatMap(rest => leadingInt.findFirstMatchIn(rest).map(_.group(1).toLong))

  private val leadingInt = """^\s*(-?\d+)""".r
}
