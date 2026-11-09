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

package org.apache.spark.sql.execution.benchmark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.DoubleBinaryOperator;

import org.apache.spark.network.util.JavaUtils;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

/**
 * Which of Spark's math functions answer differently on another host (VARKA-587)? Not a timing:
 * the class is a benchmark so that {@code .github/workflows/benchmark.yml} can run it on a pinned
 * runner, and its results file is what it found there.
 *
 * <p>Spark evaluates {@code sin}, {@code cos}, {@code tan}, {@code sec}, {@code csc}, {@code cot},
 * {@code tanh} and {@code cbrt} through {@code java.lang.Math}, whose methods HotSpot may replace
 * by a platform stub, and {@code exp}, {@code ln}, {@code log10} and {@code pow} through {@code
 * StrictMath}, fdlibm in Java on every host ({@code mathExpressions.scala}). The class runs one
 * query of all of them over a Parquet table of {@link #ROWS} rows, drawn with a fixed seed the
 * four ways {@code dev/varka_canary/MathHostProbe.java} draws, and holds each answer to the same
 * composition computed with {@code StrictMath}, by raw bits. It does so three ways: whole-stage
 * code generation, generated projections without it, and interpreted expressions. Since
 * StrictMath is the same on every host, a function with no differing row answers the same here
 * as anywhere, and two hosts' files compare by the digest of each answer column.
 *
 * <p>Two more sections run {@code SELECT f(<literal>)} for literals where the laptop's x86 stub
 * differs from fdlibm, which Spark folds while it plans the query, and {@code reflect} into
 * {@code StrictMath} over the table, which gives fdlibm's answer on any host.
 *
 * <pre>
 *   SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt \
 *     "sql/Test/runMain org.apache.spark.sql.execution.benchmark.SparkMathHostBenchmark"
 * </pre>
 */
public class SparkMathHostBenchmark extends VarkaSqlBenchmarkBase {

  static final int PER_DRAW = 1 << 16;
  static final int ROWS = 4 * PER_DRAW;
  static final long SEED = 587;

  /** A Spark function as the query writes it, and the same composition over StrictMath. */
  record Fn(String sql, DoubleBinaryOperator strict) {}

  static final List<Fn> FNS = List.of(
      new Fn("sin(x)", (x, y) -> StrictMath.sin(x)),
      new Fn("cos(x)", (x, y) -> StrictMath.cos(x)),
      new Fn("tan(x)", (x, y) -> StrictMath.tan(x)),
      new Fn("sec(x)", (x, y) -> 1 / StrictMath.cos(x)),
      new Fn("csc(x)", (x, y) -> 1 / StrictMath.sin(x)),
      new Fn("cot(x)", (x, y) -> 1 / StrictMath.tan(x)),
      new Fn("tanh(x)", (x, y) -> StrictMath.tanh(x)),
      new Fn("cbrt(x)", (x, y) -> StrictMath.cbrt(x)),
      new Fn("asin(x)", (x, y) -> StrictMath.asin(x)),
      new Fn("atan2(x, y)", (x, y) -> StrictMath.atan2(x + 0.0, y + 0.0)),
      new Fn("exp(x)", (x, y) -> StrictMath.exp(x)),
      new Fn("ln(x)", (x, y) -> x <= 0.0 ? Double.NaN : StrictMath.log(x)),
      new Fn("log10(x)", (x, y) -> x <= 0.0 ? Double.NaN : StrictMath.log10(x)),
      new Fn("pow(x, y)", (x, y) -> StrictMath.pow(x, y)));

  /** The literals of the last section: where the laptop's Math differs from StrictMath. */
  static final List<Fn> LITERALS = List.of(
      new Fn("sin(0.48)", (x, y) -> StrictMath.sin(0.48)),
      new Fn("cos(0.1)", (x, y) -> StrictMath.cos(0.1)),
      new Fn("tan(0.08)", (x, y) -> StrictMath.tan(0.08)),
      new Fn("tanh(0.02)", (x, y) -> StrictMath.tanh(0.02)),
      new Fn("cbrt(0.22)", (x, y) -> StrictMath.cbrt(0.22)),
      new Fn("exp(0.5)", (x, y) -> StrictMath.exp(0.5)),
      new Fn("ln(0.5)", (x, y) -> StrictMath.log(0.5)));

  @Override
  protected SparkSession getSparkSession() {
    SparkSession session = super.getSparkSession();
    session.conf().set(SQLConf.VARKA_ENABLED().key(), "false");
    return session;
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    SparkSession spark = spark();
    double[] xs = new double[ROWS];
    double[] ys = new double[ROWS];
    SplittableRandom r = new SplittableRandom(SEED);
    List<Row> rows = new ArrayList<>(ROWS);
    for (int i = 0; i < ROWS; i++) {
      xs[i] = draw(i / PER_DRAW, r);
      ys[i] = draw(i / PER_DRAW, r);
      rows.add(RowFactory.create(i, xs[i], ys[i]));
    }
    StructType schema = new StructType()
        .add("id", DataTypes.IntegerType, false)
        .add("x", DataTypes.DoubleType, false)
        .add("y", DataTypes.DoubleType, false);
    Path dir;
    try {
      dir = Files.createTempDirectory("varka-math-host");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    try {
      String table = dir.resolve("t").toString();
      spark.createDataFrame(rows, schema).coalesce(1).write().parquet(table);
      spark.read().parquet(table).createOrReplaceTempView("t");
      section("Spark's math functions against StrictMath, by raw bits", () -> {
        note("host: os.arch=" + System.getProperty("os.arch") + " java.runtime.version="
            + System.getProperty("java.runtime.version") + " spark=" + spark.version());
        note("rows: " + ROWS + ", " + PER_DRAW + " a draw (bit patterns, [-750,750], [-2,2],"
            + " near zero), seed " + SEED + "; a null answer counts as NaN");
        for (String mode : new String[] {"whole-stage", "generated", "interpreted"}) {
          compare(spark, mode, xs, ys);
        }
      });
      section("SELECT f(literal), folded while the query is planned", () -> {
        for (Fn lit : LITERALS) {
          double answer = spark.sql("SELECT " + lit.sql()).collectAsList().get(0).getDouble(0);
          double strict = lit.strict().applyAsDouble(0, 0);
          note(String.format("%-12s %-22s StrictMath %-22s %s", lit.sql(), answer, strict,
              // nan-compare: ok, the raw bits are what the probe reports
              Double.doubleToRawLongBits(answer) == Double.doubleToRawLongBits(strict)
                  ? "same" : "differs"));
        }
      });
      section("reflect into StrictMath, a way to the same answer on every host", () -> {
        for (String f : new String[] {"sin", "cos", "tan", "tanh", "cbrt"}) {
          String q = "SELECT id, CAST(reflect('java.lang.StrictMath', '" + f
              + "', x) AS DOUBLE) FROM t";
          long differ = 0;
          long nans = 0;
          for (Row row : spark.sql(q).collectAsList()) {
            int id = row.getInt(0);
            long a = Double.doubleToRawLongBits(row.getDouble(1));
            long want = Double.doubleToRawLongBits(strict(f, xs[id]));
            if (a != want) {
              if (Double.isNaN(Double.longBitsToDouble(a))) {
                nans++;
              } else {
                differ++;
              }
            }
          }
          note(String.format("  CAST(reflect('java.lang.StrictMath', '%s', x) AS DOUBLE)"
              + " rows differing from StrictMath=%d nanPayload=%d", f, differ, nans));
        }
      });
    } finally {
      JavaUtils.deleteQuietly(dir.toFile());
    }
  }

  static double strict(String f, double x) {
    return switch (f) {
      case "sin" -> StrictMath.sin(x);
      case "cos" -> StrictMath.cos(x);
      case "tan" -> StrictMath.tan(x);
      case "tanh" -> StrictMath.tanh(x);
      case "cbrt" -> StrictMath.cbrt(x);
      default -> throw new IllegalArgumentException(f);
    };
  }

  /** One input of draw {@code d}, drawn as {@code MathHostProbe} draws it. */
  static double draw(int d, SplittableRandom r) {
    return switch (d) {
      case 0 -> Double.longBitsToDouble(r.nextLong());
      case 1 -> (r.nextDouble() * 2 - 1) * 750;
      case 2 -> (r.nextDouble() * 2 - 1) * 2;
      default -> (r.nextDouble() - 0.5) * Math.scalb(1.0, -r.nextInt(60));
    };
  }

  private void compare(SparkSession spark, String mode, double[] xs, double[] ys) {
    String wholeStage = SQLConf.WHOLESTAGE_CODEGEN_ENABLED().key();
    String factory = SQLConf.CODEGEN_FACTORY_MODE().key();
    spark.conf().set(wholeStage, mode.equals("whole-stage") ? "true" : "false");
    spark.conf().set(factory, mode.equals("interpreted") ? "NO_CODEGEN" : "FALLBACK");
    StringBuilder select = new StringBuilder("SELECT id");
    for (Fn f : FNS) {
      select.append(", ").append(f.sql());
    }
    List<Row> answers = spark.sql(select + " FROM t").collectAsList();
    spark.conf().unset(wholeStage);
    spark.conf().unset(factory);
    long[][] bits = new long[FNS.size()][ROWS];
    for (Row row : answers) {
      int id = row.getInt(0);
      for (int k = 0; k < FNS.size(); k++) {
        double v = row.isNullAt(k + 1) ? Double.NaN : row.getDouble(k + 1);
        bits[k][id] = Double.doubleToRawLongBits(v);
      }
    }
    note("");
    note(mode + ":");
    for (int k = 0; k < FNS.size(); k++) {
      Fn f = FNS.get(k);
      long differ = 0;
      long nans = 0;
      long maxUlp = 0;
      for (int i = 0; i < ROWS; i++) {
        long s = Double.doubleToRawLongBits(f.strict().applyAsDouble(xs[i], ys[i]));
        long a = bits[k][i];
        if (a == s) {
          continue;
        }
        // nan-compare: ok, a NaN of another payload is counted apart from a value difference
        if (Double.isNaN(Double.longBitsToDouble(a)) && Double.isNaN(Double.longBitsToDouble(s))) {
          nans++;
        } else {
          differ++;
          maxUlp = Math.max(maxUlp, Math.abs(ordered(a) - ordered(s)));
        }
      }
      note(String.format("  %-12s rows differing from StrictMath=%d maxUlp=%d nanPayload=%d"
          + " digest=%s", f.sql(), differ, maxUlp, nans, digest(bits[k])));
    }
  }

  /** The bits mapped to a long that orders as the doubles do, so a difference counts ULPs. */
  static long ordered(long bits) {
    return bits ^ ((bits >> 63) & Long.MAX_VALUE);
  }

  /** FNV-1a over the result bits, a byte at a time, as {@code MathHostProbe} digests. */
  static String digest(long[] bits) {
    long h = 0xcbf29ce484222325L;
    for (long b : bits) {
      for (int k = 0; k < 8; k++) {
        h ^= (b >>> (8 * k)) & 0xff;
        h *= 0x100000001b3L;
      }
    }
    return String.format("%016x", h);
  }
}
