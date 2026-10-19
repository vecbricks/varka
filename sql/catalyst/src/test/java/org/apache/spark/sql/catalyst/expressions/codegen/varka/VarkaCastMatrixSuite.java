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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.Cast$;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.DayTimeIntervalType;
import org.apache.spark.sql.types.DayTimeIntervalType$;
import org.apache.spark.sql.types.TimeType;
import org.apache.spark.sql.types.YearMonthIntervalType;
import org.apache.spark.sql.types.YearMonthIntervalType$;

/**
 * Milestone 8's cast matrix ({@code sql/varka/plans/m8/casts.tsv}, written by
 * {@code dev/varka_census.py}) held to Spark's own legality rules (VARKA-328). The script
 * transcribes {@code Cast.canCast} and {@code Cast.canAnsiCast} by hand into its {@code legal()},
 * so a wrong transcription would stay consistent with itself; this suite asks {@code Cast} about
 * every ordered pair of the census's thirteen types instead, under each of the three modes, and
 * requires the file's columns to agree and a pair the file leaves out to be illegal in all three.
 * It asks about legality only, never about answers: those are row 368's.
 */
@VarkaSuite
public final class VarkaCastMatrixSuite {

  /**
   * Each census type as a Spark type. A pair within one type casts between two of its forms
   * where it has several - a decimal's precision and scale, a time's precision, an interval's
   * fields - since Spark's cast there is a real one, and the file's same-type row stands for it.
   */
  private static final Map<String, DataType[]> TYPES = new LinkedHashMap<>();

  static {
    TYPES.put("bool", new DataType[] {DataTypes.BooleanType});
    TYPES.put("byte", new DataType[] {DataTypes.ByteType});
    TYPES.put("short", new DataType[] {DataTypes.ShortType});
    TYPES.put("int", new DataType[] {DataTypes.IntegerType});
    TYPES.put("bigint", new DataType[] {DataTypes.LongType});
    TYPES.put("float", new DataType[] {DataTypes.FloatType});
    TYPES.put("double", new DataType[] {DataTypes.DoubleType});
    TYPES.put("dec", new DataType[] {
        DataTypes.createDecimalType(10, 2), DataTypes.createDecimalType(30, 4)});
    TYPES.put("date", new DataType[] {DataTypes.DateType});
    TYPES.put("ntz", new DataType[] {DataTypes.TimestampNTZType});
    TYPES.put("time", new DataType[] {new TimeType(6), new TimeType(3)});
    TYPES.put("ym", new DataType[] {
        new YearMonthIntervalType(YearMonthIntervalType$.MODULE$.YEAR(),
            YearMonthIntervalType$.MODULE$.MONTH()),
        new YearMonthIntervalType(YearMonthIntervalType$.MODULE$.MONTH(),
            YearMonthIntervalType$.MODULE$.MONTH())});
    TYPES.put("dt", new DataType[] {
        new DayTimeIntervalType(DayTimeIntervalType$.MODULE$.DAY(),
            DayTimeIntervalType$.MODULE$.SECOND()),
        new DayTimeIntervalType(DayTimeIntervalType$.MODULE$.DAY(),
            DayTimeIntervalType$.MODULE$.DAY())});
  }

  /** The three modes' legality of one pair, as {@code casts.tsv} writes it. */
  private record Legality(boolean legacy, boolean ansi, boolean tryCast) {
    static final Legality NONE = new Legality(false, false, false);

    static Legality of(DataType from, DataType to) {
      return new Legality(Cast$.MODULE$.canCast(from, to), Cast$.MODULE$.canAnsiCast(from, to),
          Cast$.MODULE$.canTryCast(from, to));
    }
  }

  private static Map<String, Legality> committed() throws IOException {
    String home = System.getProperty("spark.test.home", System.getenv("SPARK_HOME"));
    assertTrue(home != null, "spark.test.home or SPARK_HOME is not set.");
    List<String> lines = Files.readAllLines(
        Path.of(home, "sql", "varka", "plans", "m8", "casts.tsv"), StandardCharsets.UTF_8);
    assertTrue(lines.get(0).startsWith("from\tto\tlegacy\tansi\ttry\t"), lines.get(0));
    Map<String, Legality> out = new LinkedHashMap<>();
    for (String line : lines.subList(1, lines.size())) {
      String[] f = line.split("\t", -1);
      out.put(f[0] + ">" + f[1],
          new Legality(f[2].equals("yes"), f[3].equals("yes"), f[4].equals("yes")));
    }
    return out;
  }

  @Test
  @DisplayName("casts.tsv's legality agrees with Cast over every ordered pair, in every mode")
  void legality() throws IOException {
    Map<String, Legality> file = committed();
    List<String> wrong = new ArrayList<>();
    for (var from : TYPES.entrySet()) {
      for (var to : TYPES.entrySet()) {
        String key = from.getKey() + ">" + to.getKey();
        Legality claimed = file.getOrDefault(key, Legality.NONE);
        // Every form of the source against every form of the target: legality depends on the
        // type family alone for these types, and a form that disagreed would show it here.
        for (DataType src : from.getValue()) {
          for (DataType dst : to.getValue()) {
            Legality actual = Legality.of(src, dst);
            if (!actual.equals(claimed)) {
              wrong.add(key + " (" + src.simpleString() + " to " + dst.simpleString()
                  + "): casts.tsv " + claimed + ", Cast " + actual);
            }
          }
        }
      }
    }
    assertTrue(wrong.isEmpty(),
        "dev/varka_census.py's legal() disagrees with Cast:\n  " + String.join("\n  ", wrong));
    assertTrue(file.keySet().stream().allMatch(k -> {
      String[] p = k.split(">");
      return TYPES.containsKey(p[0]) && TYPES.containsKey(p[1]);
    }), "casts.tsv names a type outside the census's thirteen: " + file.keySet());
  }
}
