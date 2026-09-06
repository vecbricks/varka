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

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import org.apache.spark.sql.catalyst.util.DateTimeUtils;
import org.apache.spark.sql.vectorized.ColumnVector;
import org.apache.spark.unsafe.types.UTF8String;

/**
 * The derived input behind {@code trunc(date, fmt)} with a format column (task 61): the level
 * code {@code DateTimeUtils.parseTruncLevel} gives each row's format, written per batch into an
 * int32 column the kernel selects on. The lane holds the parser's own code - {@link #WEEK}
 * through {@link #YEAR}, never re-mapped - and is null wherever the parser answers anything
 * else: a null format, an unrecognised spelling, or a sub-day level, which are exactly the rows
 * Spark's {@code TruncDate} evaluates to NULL ({@code TruncInstant.evalHelper} tests the parsed
 * level against {@code MIN_LEVEL_OF_DATE_TRUNC}). There is no error path in either evaluation
 * mode, so unlike {@link WeekdayLeaf} this leaf never declines a batch and its kind has no ANSI
 * twin; the derived column's validity is the output's validity for the format's part.
 *
 * <p>One parser only, the row engine's: the value set is nine spellings under a case fold, and
 * task 59's measurement said a leaf's cost is the parse itself; an ASCII fast path would be a
 * measured change of its own, not a default.
 */
public final class TruncLevelLeaf {

  /**
   * The four date levels' codes, {@code DateTimeUtils.TRUNC_TO_WEEK} through
   * {@code TRUNC_TO_YEAR}. Restated here because those are {@code private[sql]} Scala values
   * with no static forwarder Java can reach; {@code TruncLevelLeafSuite} pins each to its
   * definition, so a renumbering upstream fails there rather than truncating to the wrong
   * period.
   */
  public static final int WEEK = 6;
  public static final int MONTH = 7;
  public static final int QUARTER = 8;
  public static final int YEAR = 9;

  /** What a null format parses to: {@code DateTimeUtils.TRUNC_INVALID}, outside the four. */
  private static final int INVALID = -1;

  private TruncLevelLeaf() {}

  /**
   * Fills {@code dstData[0 .. length)} with the level code per row and {@code dstValidity} with
   * the lanes that hold a date level, returning the null count. Never declines.
   *
   * @param formats the string column, read through {@code isNullAt} and {@code getUTF8String}
   * @param length the batch's row count; the column must hold at least that many rows
   * @param dstData address of an int32 buffer of {@code length} rows
   * @param dstValidity address of a bitmap of {@code (length + 7) / 8} bytes, rewritten whole
   */
  public static int fill(ColumnVector formats, int length, long dstData, long dstValidity) {
    MemorySegment data = MemorySegment.ofAddress(dstData).reinterpret(length * 4L);
    MemorySegment validity = MemorySegment.ofAddress(dstValidity).reinterpret((length + 7) / 8L);
    int nulls = 0;
    int bits = 0;
    for (int i = 0; i < length; i++) {
      int level = formats.isNullAt(i) ? INVALID : parse(formats.getUTF8String(i));
      if (isDateLevel(level)) {
        bits |= 1 << (i & 7);
      } else {
        nulls++;
        level = 0; // a null lane's data is undefined; zero keeps the buffer deterministic
      }
      data.set(ValueLayout.JAVA_INT, i * 4L, level);
      if ((i & 7) == 7 || i == length - 1) {
        validity.set(ValueLayout.JAVA_BYTE, i >> 3, (byte) bits);
        bits = 0;
      }
    }
    return nulls;
  }

  /** The row engine's own parse: {@code DateTimeUtils.parseTruncLevel}, whatever it answers. */
  public static int parse(UTF8String format) {
    return DateTimeUtils.parseTruncLevel(format);
  }

  /** Whether a parsed code is one the kernel truncates a date to - {@link #WEEK}..{@link #YEAR}. */
  public static boolean isDateLevel(int level) {
    return level >= WEEK && level <= YEAR;
  }
}
