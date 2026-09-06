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

import org.apache.spark.SparkIllegalArgumentException;
import org.apache.spark.sql.catalyst.util.DateTimeUtils;
import org.apache.spark.sql.vectorized.ColumnVector;
import org.apache.spark.unsafe.types.UTF8String;

/**
 * The derived weekday leaf (task 59): maps a string column of weekday names to the int32 column
 * {@code k = dayOfWeek - 1} that {@code next_day}'s lowering reads, per batch and before the
 * kernel runs. The definition is {@code DateTimeUtils.getDayOfWeekFromString}: case-insensitive
 * under {@code Locale.ROOT}, three spellings per day, no trimming, and an
 * {@code ILLEGAL_DAY_OF_WEEK} error for anything else; {@code k} is in {@code [-1, 5]}
 * ({@code THURSDAY = 0 .. WEDNESDAY = 6}, less one).
 *
 * <p>Two parsers are kept as live variants under the same tests, and the parity benchmark
 * picks the default. {@link Parser#ROW_ENGINE} calls the definition per row, which builds a
 * {@code String}, upper-cases it and, on a miss, constructs the exception. {@link Parser#ASCII}
 * reads the {@code UTF8String}'s bytes in place, folds ASCII letters, selects the day by its
 * first two letters (which are distinct across the seven days) and verifies the rest against
 * the two-, three- or full-length spelling, returning a miss without an exception. It is exact
 * only over ASCII: {@code "\u017Funday".toUpperCase(Locale.ROOT)} is {@code SUNDAY} (U+017F,
 * long s) and {@code "fr\u0131day"} is {@code FRIDAY} (U+0131, dotless i), so any row with a
 * byte at or above {@code 0x80} is handed to the row engine's function, whatever its length.
 *
 * <p>The leaf never throws. An unrecognised name is a null lane under {@link
 * VarkaDerivedKind#WEEKDAY}; under {@link VarkaDerivedKind#WEEKDAY_ANSI} it returns {@link
 * #DECLINED} and the evaluator declines the whole batch to the row engine. The row engine is the
 * one that must raise, and not only for the error's identity: {@code NextDay.nullSafeEval}
 * never parses the name when the date beside it is null, so {@code next_day(NULL, 'xyz')} is
 * NULL under ANSI, and a pre-pass that raised on the parse alone would err where the row engine
 * does not (PLAN_TASK_59.md 2).
 *
 * <p>Lives here for {@link IntRangeOps}'s reason: called from the evaluator on the batch path,
 * so it has to be on the compile classpath.
 */
public final class WeekdayLeaf {

  /** The two parsers; both are held to the definition by {@code WeekdayLeafSuite}. */
  public enum Parser { ROW_ENGINE, ASCII }

  /** The parser the evaluator uses; chosen from the parity benchmark (PLAN_TASK_59.md 9). */
  public static final Parser DEFAULT_PARSER = Parser.ASCII;

  /** {@link #fill}'s answer when an unrecognised name met {@code failOnError}. */
  public static final int DECLINED = -1;

  /** {@link #parse}'s answer for a name the definition rejects; never a valid {@code k}. */
  public static final int UNRECOGNISED = Integer.MIN_VALUE;

  // Indexed by getDayOfWeekFromString's value: THURSDAY = 0 .. WEDNESDAY = 6.
  private static final String[] NAMES = {
    "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY"};

  private WeekdayLeaf() {}

  /**
   * Fills {@code dstData[0 .. length)} with {@code k} per row and {@code dstValidity} with the
   * lanes that hold one, returning the null count - or {@link #DECLINED} when a row's name is
   * unrecognised and {@code failOnError} is set, in which case the buffers are undefined.
   *
   * @param names the string column, read through {@code isNullAt} and {@code getUTF8String}
   * @param length the batch's row count; the column must hold at least that many rows
   * @param failOnError {@link VarkaDerivedKind#failOnError}
   * @param parser which parser to use
   * @param dstData address of an int32 buffer of {@code length} rows
   * @param dstValidity address of a bitmap of {@code (length + 7) / 8} bytes, rewritten whole
   */
  public static int fill(ColumnVector names, int length, boolean failOnError, Parser parser,
      long dstData, long dstValidity) {
    MemorySegment data = MemorySegment.ofAddress(dstData).reinterpret(length * 4L);
    MemorySegment validity = MemorySegment.ofAddress(dstValidity).reinterpret((length + 7) / 8L);
    int nulls = 0;
    int bits = 0;
    for (int i = 0; i < length; i++) {
      int k = UNRECOGNISED;
      if (!names.isNullAt(i)) {
        k = parse(names.getUTF8String(i), parser);
        if (k == UNRECOGNISED && failOnError) {
          return DECLINED;
        }
      }
      if (k == UNRECOGNISED) {
        nulls++;
        k = 0; // a null lane's data is undefined; zero keeps the buffer deterministic
      } else {
        bits |= 1 << (i & 7);
      }
      data.set(ValueLayout.JAVA_INT, i * 4L, k);
      if ((i & 7) == 7 || i == length - 1) {
        validity.set(ValueLayout.JAVA_BYTE, i >> 3, (byte) bits);
        bits = 0;
      }
    }
    return nulls;
  }

  /** {@code getDayOfWeekFromString(name) - 1}, or {@link #UNRECOGNISED}, by {@code parser}. */
  public static int parse(UTF8String name, Parser parser) {
    return parser == Parser.ASCII ? parseAscii(name) : parseRowEngine(name);
  }

  private static int parseRowEngine(UTF8String name) {
    try {
      return DateTimeUtils.getDayOfWeekFromString(name) - 1;
    } catch (SparkIllegalArgumentException e) {
      return UNRECOGNISED;
    }
  }

  private static int parseAscii(UTF8String name) {
    int n = name.numBytes();
    for (int i = 0; i < n; i++) {
      if (name.getByte(i) < 0) {
        return parseRowEngine(name);
      }
    }
    if (n < 2 || n > 9) {
      return UNRECOGNISED;
    }
    int c0 = fold(name.getByte(0));
    int c1 = fold(name.getByte(1));
    int day = switch (c0) {
      case 'T' -> c1 == 'H' ? 0 : c1 == 'U' ? 5 : -1;
      case 'F' -> c1 == 'R' ? 1 : -1;
      case 'S' -> c1 == 'A' ? 2 : c1 == 'U' ? 3 : -1;
      case 'M' -> c1 == 'O' ? 4 : -1;
      case 'W' -> c1 == 'E' ? 6 : -1;
      default -> -1;
    };
    if (day < 0) {
      return UNRECOGNISED;
    }
    String full = NAMES[day];
    if (n != 2 && n != 3 && n != full.length()) {
      return UNRECOGNISED;
    }
    for (int i = 2; i < n; i++) {
      if (fold(name.getByte(i)) != full.charAt(i)) {
        return UNRECOGNISED;
      }
    }
    return day - 1;
  }

  /** ASCII upper case, which is what {@code toUpperCase(Locale.ROOT)} does to an ASCII byte. */
  private static int fold(byte b) {
    return b >= 'a' && b <= 'z' ? b - 32 : b;
  }
}
