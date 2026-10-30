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

package org.apache.spark.sql.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.apache.arrow.memory.ArrowBuf;
import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.catalyst.expressions.InterpretedUnsafeProjection;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.UnsafeProjection;
import org.apache.spark.sql.catalyst.expressions.UnsafeRow;
import org.apache.spark.sql.catalyst.expressions.codegen.GenerateUnsafeProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.PartialVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec.ResidualOutput;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSqlResolve;
import org.apache.spark.sql.execution.columnar.VarkaCacheBatches;
import org.apache.spark.sql.execution.varka.VarkaBatchDeclined;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DateType;
import org.apache.spark.sql.types.DayTimeIntervalType;
import org.apache.spark.sql.types.DoubleType;
import org.apache.spark.sql.types.FloatType;
import org.apache.spark.sql.types.TimeType;
import org.apache.spark.sql.types.TimestampNTZType;
import org.apache.spark.sql.types.TimestampType;
import org.apache.spark.sql.types.YearMonthIntervalType;
import org.apache.spark.sql.vectorized.ArrowColumnVector;
import org.apache.spark.sql.vectorized.ColumnarBatch;

/**
 * A Varka kernel's answers held to Spark's, with no session (VARKA-509, {@code m8/VARKA-509.md}
 * section 2). The rows are a fixture's, written into Arrow batches by the cache's own iterators
 * ({@link VarkaCacheBatches}); Varka's answer is the production evaluator's, run through
 * {@code serveBatch} as the exec nodes run it; Spark's is its own projection of the same
 * expression over the same rows, interpreted and generated.
 *
 * <p>A batch the kernel answers must hold Spark's answer on every row, and no row Spark raises
 * on. A batch it declines is answered by the row path, which is Spark's evaluation, so it is
 * Spark's by construction and only counted. Any other failure of the kernel's path - an
 * exception that is not a decline - is a finding: the exec node would hide it behind the
 * fallback, and the end-to-end suites only counted batches. A row on which Spark's two
 * projections disagree is reported as Spark's disagreement and not compared.
 */
public final class VarkaExpressionHarness {

  private VarkaExpressionHarness() {}

  /** What a check found, one line a disagreement, and how its batches went. */
  public static final class Outcome {
    public final List<String> findings = new ArrayList<>();
    public int answered;
    public int declined;
    /** For a filter, the {@code rid} of every row Spark's condition is true on, batch or not. */
    public final java.util.SortedSet<Integer> sparkTrue = new java.util.TreeSet<>();

    /** For a projection, the outputs the compiler left to the row path. */
    public int residual;

    /** Every batch the evaluator was handed, in order, with how it went. */
    public final List<Batch> batches = new ArrayList<>();

    /**
     * Whether a kernel served every batch and every output: some batch answered, none declined,
     * and no output left residual, which the row path would answer beside the kernel's.
     */
    public boolean served() {
      return answered > 0 && declined == 0 && residual == 0;
    }

    public boolean ok() {
      return findings.isEmpty();
    }
  }

  /**
   * One batch: its first row's position among the rows checked, its row count, and its status -
   * {@link #ANSWERED} for a batch the kernel answered, the status a declined batch's kernel
   * returned, or {@link #FELL_BACK} for a batch the evaluator sent to the row path without
   * running a kernel.
   */
  public record Batch(int start, int rows, int status) {
    public static final int ANSWERED = 0;
    public static final int FELL_BACK = -1;
  }

  /** Spark's answer for one row: the projected row, or the error evaluating it raised. */
  private record Expected(@Nullable InternalRow row, @Nullable Raised error) {}

  /**
   * An error Spark raised, as the checks read it: its class, its message, and how it prints,
   * which is what a finding quotes.
   */
  private record Raised(String type, @Nullable String message, String text) {
    static Raised of(Throwable e) {
      return new Raised(e.getClass().getName(), e.getMessage(), e.toString());
    }

    @Override
    public String toString() {
      return text;
    }
  }

  /** Each row projected by {@code projection}, one at a time, so an error is the row's own. */
  private static List<Expected> project(UnsafeProjection projection, List<InternalRow> rows) {
    var out = new ArrayList<Expected>(rows.size());
    for (InternalRow row : rows) {
      try {
        out.add(new Expected(projection.apply(row).copy(), null));
      } catch (RuntimeException e) {
        out.add(new Expected(null, Raised.of(e)));
      }
    }
    return out;
  }

  /** {@code outputs} bound to {@code columns}, as Spark's projections and the store read them. */
  private static List<Expression> bound(List<Expression> outputs, Seq<Attribute> columns) {
    var bound = new ArrayList<Expression>(outputs.size());
    for (Expression e : outputs) {
      bound.add(VarkaSqlResolve.bind(e, columns));
    }
    return bound;
  }

  /**
   * Spark's answers for {@code bound} over {@code rows}, interpreted and generated, the
   * generated one's where they agree and null for a row where they do not, which is reported.
   * Under a configuration whose {@link VarkaHarnessStore} holds the defaults' answers to the
   * same expressions over the same rows under the same settings, those are the answers, and
   * Spark evaluates nothing; the defaults' run writes the answers it computed where the two
   * projections agreed on every row.
   */
  private static List<Expected> spark(String context, List<Expression> bound,
      Seq<Attribute> columns, List<InternalRow> rows, DataType[] types, Outcome outcome) {
    VarkaHarnessStore store = VarkaHarnessStore.current();
    String key = store == null ? null : VarkaHarnessStore.answerKey(bound, columns, rows);
    if (store != null) {
      byte[] stored = store.answer(key);
      if (stored != null) {
        return decodeAnswers(stored);
      }
    }
    List<Expected> out = evaluate(context, bound, rows, types, outcome);
    if (store != null && !out.contains(null)) {
      byte[] encoded = encodeAnswers(out);
      if (encoded != null) {
        store.putAnswer(key, encoded);
      }
    }
    return out;
  }

  /** {@link #spark}'s evaluation: both of Spark's projections, row by row. */
  private static List<Expected> evaluate(String context, List<Expression> bound,
      List<InternalRow> rows, DataType[] types, Outcome outcome) {
    Seq<Expression> exprs = CollectionConverters.asScala(bound).toList();
    List<Expected> interpreted = project(InterpretedUnsafeProjection.createProjection(exprs), rows);
    List<Expected> generated = project(GenerateUnsafeProjection.generate(exprs, false), rows);
    var out = new ArrayList<Expected>(rows.size());
    for (int i = 0; i < rows.size(); i++) {
      Expected a = interpreted.get(i);
      Expected b = generated.get(i);
      boolean agree = a.error() != null
          ? b.error() != null && sameError(a.error(), b.error())
          : b.error() == null && sameRow(a.row(), b.row(), types) == null;
      if (!agree) {
        outcome.findings.add(context + ", row " + i + ": Spark's interpreted and generated "
            + "projections disagree (" + describe(a, types) + " against " + describe(b, types)
            + "); the row is not compared");
        out.add(null);
      } else {
        out.add(b);
      }
    }
    return out;
  }

  /**
   * Whether two evaluations raised the same error: the same class and message, or the same class
   * where one has no message. Compiled code may throw a JVM's preallocated exception, whose
   * message HotSpot clears (C2's hot throws, {@code -XX:+OmitStackTraceInFastThrow}), so a hot
   * projection can raise the same {@code ArithmeticException} as the interpreted one without its
   * "long overflow".
   */
  private static boolean sameError(Raised a, Raised b) {
    return a.type().equals(b.type()) && (Objects.equals(a.message(), b.message())
        || a.message() == null || b.message() == null);
  }

  /**
   * Spark's answers as the store keeps them: each row's {@code UnsafeRow} bytes, or the error's
   * class, message and text; null for answers that are not all {@code UnsafeRow}s.
   */
  private static @Nullable byte[] encodeAnswers(List<Expected> answers) {
    var bytes = new java.io.ByteArrayOutputStream();
    try (var out = new java.io.DataOutputStream(bytes)) {
      out.writeInt(answers.size());
      for (Expected e : answers) {
        if (e.error() != null) {
          out.writeByte(1);
          out.writeUTF(e.error().type());
          writeString(out, e.error().message());
          writeString(out, e.error().text());
        } else if (e.row() instanceof UnsafeRow row) {
          out.writeByte(0);
          out.writeInt(row.numFields());
          byte[] b = row.getBytes();
          out.writeInt(b.length);
          out.write(b);
        } else {
          return null;
        }
      }
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  private static List<Expected> decodeAnswers(byte[] stored) {
    try (var in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(stored))) {
      int n = in.readInt();
      var out = new ArrayList<Expected>(n);
      for (int i = 0; i < n; i++) {
        if (in.readByte() == 1) {
          String type = in.readUTF();
          String message = readString(in);
          out.add(new Expected(null, new Raised(type, message, readString(in))));
        } else {
          var row = new UnsafeRow(in.readInt());
          byte[] b = in.readNBytes(in.readInt());
          row.pointTo(b, b.length);
          out.add(new Expected(row, null));
        }
      }
      return out;
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  /** A string that may be null or longer than {@code writeUTF} takes. */
  private static void writeString(java.io.DataOutputStream out, @Nullable String s)
      throws java.io.IOException {
    if (s == null) {
      out.writeInt(-1);
    } else {
      byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      out.writeInt(b.length);
      out.write(b);
    }
  }

  private static @Nullable String readString(java.io.DataInputStream in)
      throws java.io.IOException {
    int n = in.readInt();
    return n < 0 ? null : new String(in.readNBytes(n), java.nio.charset.StandardCharsets.UTF_8);
  }

  /** The two consumers a Varka projection has, as the exec nodes call the evaluator. */
  public enum Consumer {
    /** The columnar node and the Arrow cache builder: {@code project}, every output a vector. */
    COLUMNS,
    /** The row node: {@code projectFused}, the fused outputs only, read row by row. */
    ROWS
  }

  /** {@link #projection(String, Seq, VarkaHarnessPlans.Fixture, SQLConf, Consumer)} as columns. */
  public static Outcome projection(String context, Seq<NamedExpression> projectList,
      VarkaHarnessPlans.Fixture fixture, SQLConf conf) {
    return projection(context, projectList, fixture, conf, Consumer.COLUMNS);
  }

  /**
   * Varka's projection evaluator over {@code rows}, as {@code consumer}'s exec node serves a
   * batch, against Spark's projection of the same list. The row node's {@code projectFused}
   * returns the fused outputs alone, in the kernels' column order, so for it those outputs are
   * compared, each with Spark's value of its own entry; the row node computes the others.
   */
  public static Outcome projection(String context, Seq<NamedExpression> projectList,
      VarkaHarnessPlans.Fixture fixture, SQLConf conf, Consumer consumer) {
    var outcome = new Outcome();
    Seq<Attribute> columns = fixture.columns();
    List<InternalRow> all = fixture.rows();
    List<NamedExpression> list = CollectionConverters.asJava(projectList);
    // A Scala trait over a class: Java sees NamedExpression without Expression's methods.
    var outputs = new ArrayList<Expression>();
    for (NamedExpression e : list) {
      outputs.add((Expression) e);
    }
    DataType[] types = outputs.stream().map(Expression::dataType).toArray(DataType[]::new);
    List<Expected> answers = spark(context, bound(outputs, columns), columns, all, types, outcome);
    // The outputs the evaluator's compile leaves to the row path, by the same compile.
    Optional<PartialVarkaProjection> partial = VarkaExpressionCompiler.compilePartial(
        projectList, columns, VarkaColumnarToRowExec$.MODULE$.currentEmitOptions());
    outcome.residual = partial
        .map(p -> (int) p.specs().stream().filter(x -> x instanceof ResidualOutput).count())
        .orElse(outputs.size());
    List<Expected> expected = answers;
    DataType[] compared = types;
    if (consumer == Consumer.ROWS && partial.isPresent()) {
      int[] kept = kernelColumns(partial.get());
      compared = new DataType[kept.length];
      for (int k = 0; k < kept.length; k++) {
        compared[k] = types[kept[k]];
      }
      expected = new ArrayList<>(answers.size());
      for (Expected e : answers) {
        expected.add(e == null || e.row() == null ? e : new Expected(pick(e.row(), kept, types),
            null));
      }
    }
    DataType[] outTypes = compared;
    List<Expected> want = expected;
    VarkaCacheBatches.inTask(() -> {
      var evaluator =
          new VarkaKernelEvaluator(projectList, columns, false, "VarkaExpressionHarness");
      int[] offset = {0};
      VarkaCacheBatches.each(all, columns, conf, batch -> {
        int start = offset[0];
        offset[0] += batch.numRows();
        ColumnarBatch out = serve(context, evaluator, start, batch,
            () -> consumer == Consumer.ROWS ? evaluator.projectFused(batch)
                : evaluator.project(batch), outcome);
        if (out != null) {
          try {
            for (int i = 0; i < batch.numRows(); i++) {
              compare(context, start + i, want.get(start + i), out.getRow(i), outTypes,
                  outcome);
            }
          } finally {
            evaluator.release(out);
          }
        }
      });
      return null;
    });
    return outcome;
  }

  /** The projection position of each column {@code projectFused} returns, in its order. */
  private static int[] kernelColumns(PartialVarkaProjection partial) {
    List<VarkaOutputSpec> specs = partial.specs();
    var byColumn = new java.util.TreeMap<Integer, Integer>();
    for (int position = 0; position < specs.size(); position++) {
      OptionalInt column = partial.columnIndex(specs.get(position));
      if (column.isPresent()) {
        byColumn.put(column.getAsInt(), position);
      }
    }
    return byColumn.values().stream().mapToInt(Integer::intValue).toArray();
  }

  /** {@code row}'s values at {@code positions}, as a row of their own. */
  private static InternalRow pick(InternalRow row, int[] positions, DataType[] types) {
    var values = new Object[positions.length];
    for (int k = 0; k < positions.length; k++) {
      values[k] = row.isNullAt(positions[k]) ? null : row.get(positions[k], types[positions[k]]);
    }
    return new GenericInternalRow(values);
  }

  /**
   * Varka's filter evaluator over {@code rows}, as {@code VarkaFilterExec} serves a batch, against
   * Spark's evaluation of {@code condition}: the rows the kernel keeps, by their {@code rid}, are
   * exactly those the condition is true on.
   */
  public static Outcome filter(String context, Expression condition,
      VarkaHarnessPlans.Fixture fixture, SQLConf conf) {
    var outcome = new Outcome();
    Seq<Attribute> columns = fixture.columns();
    List<InternalRow> all = fixture.rows();
    int ridOrdinal = fixture.ordinal("rid");
    DataType[] types = {condition.dataType()};
    List<Expected> expected =
        spark(context, bound(List.of(condition), columns), columns, all, types, outcome);
    for (int i = 0; i < all.size(); i++) {
      Expected e = expected.get(i);
      if (e != null && e.error() == null && !e.row().isNullAt(0) && e.row().getBoolean(0)) {
        outcome.sparkTrue.add(all.get(i).getInt(ridOrdinal));
      }
    }
    VarkaCacheBatches.inTask(() -> {
      var evaluator =
          new VarkaFilterEvaluator(condition, columns, false, "VarkaExpressionHarness");
      int[] offset = {0};
      VarkaCacheBatches.each(all, columns, conf, batch -> {
        int start = offset[0];
        offset[0] += batch.numRows();
        ColumnarBatch out = serve(context, evaluator, start, batch,
            () -> evaluator.filterCompact(batch), outcome);
        if (out != null) {
          try {
            var want = new ArrayList<Integer>();
            for (int i = 0; i < batch.numRows(); i++) {
              Expected e = expected.get(start + i);
              if (e == null) {
                continue;
              }
              if (e.error() != null) {
                outcome.findings.add(context + ", row " + (start + i) + ": the kernel answered "
                    + "a batch holding a row Spark raises on: " + e.error());
              } else if (!e.row().isNullAt(0) && e.row().getBoolean(0)) {
                want.add(all.get(start + i).getInt(ridOrdinal));
              }
            }
            var got = new ArrayList<Integer>();
            for (int i = 0; i < out.numRows(); i++) {
              got.add(out.getRow(i).getInt(ridOrdinal));
            }
            if (!got.equals(want)) {
              outcome.findings.add(context + ", rows " + start + " to "
                  + (start + batch.numRows() - 1) + ": kept rid " + got + ", Spark keeps rid "
                  + want);
            }
          } finally {
            evaluator.release(out);
          }
        }
      });
      return null;
    });
    return outcome;
  }

  /**
   * One batch through {@code serveBatch}: the kernel path's batch, or null for a batch the row
   * path took. A failure of the kernel's path other than a decline is recorded before the
   * evaluator falls back on it.
   */
  private static @Nullable ColumnarBatch serve(String context, VarkaEvaluatorBase evaluator,
      int start, ColumnarBatch batch, Supplier<ColumnarBatch> kernel, Outcome outcome) {
    long before = checksum(batch);
    try {
      return serveOnce(context, evaluator, start, batch, kernel, outcome);
    } finally {
      if (checksum(batch) != before) {
        outcome.findings.add(context + ": serving the batch of rows " + start + " to "
            + (start + batch.numRows() - 1) + " wrote to its input vectors");
      }
    }
  }

  /**
   * A CRC of every buffer of {@code batch}'s vectors, validity and values: a kernel reads its
   * input and writes only its own output, so serving a batch leaves this unchanged.
   */
  private static long checksum(ColumnarBatch batch) {
    var crc = new java.util.zip.CRC32();
    for (int c = 0; c < batch.numCols(); c++) {
      if (batch.column(c) instanceof ArrowColumnVector v) {
        for (ArrowBuf buf : v.getValueVector().getBuffers(false)) {
          crc.update(buf.nioBuffer(0, (int) buf.capacity()));
        }
      }
    }
    return crc.getValue();
  }

  private static @Nullable ColumnarBatch serveOnce(String context, VarkaEvaluatorBase evaluator,
      int start, ColumnarBatch batch, Supplier<ColumnarBatch> kernel, Outcome outcome) {
    boolean[] fell = {false};
    int[] status = {Batch.FELL_BACK};
    ColumnarBatch out = evaluator.serveBatch(batch, () -> {
      try {
        return kernel.get();
      } catch (VarkaBatchDeclined declined) {
        status[0] = declined.status;
        throw declined;
      } catch (RuntimeException | Error e) {
        // Recorded for the message; the evaluator's guard (VARKA-275) then raises its own
        // INTERNAL_ERROR out of serveBatch, so the test fails on that, loudly, as it should.
        outcome.findings.add(context + ": the kernel's path failed on a batch of "
            + batch.numRows() + " rows: " + e);
        throw e;
      }
    }, () -> {
      fell[0] = true;
      return null;
    });
    if (fell[0]) {
      outcome.declined++;
      outcome.batches.add(new Batch(start, batch.numRows(), status[0]));
      return null;
    }
    outcome.answered++;
    outcome.batches.add(new Batch(start, batch.numRows(), Batch.ANSWERED));
    return out;
  }

  private static void compare(String context, int row, @Nullable Expected expected,
      InternalRow got, DataType[] types, Outcome outcome) {
    if (expected == null) {
      return;
    }
    if (expected.error() != null) {
      outcome.findings.add(context + ", row " + row + ": the kernel answered a row Spark raises "
          + "on: " + expected.error());
      return;
    }
    String difference = sameRow(expected.row(), got, types);
    if (difference != null) {
      outcome.findings.add(context + ", row " + row + ": " + difference);
    }
  }

  /**
   * Null when {@code want} and {@code got} hold the same value in every column, else what
   * differs: floating values by their raw bits, so a NaN's payload and a zero's sign count, as
   * they do in {@code VarkaSparkOracle}.
   */
  private static @Nullable String sameRow(InternalRow want, InternalRow got, DataType[] types) {
    for (int o = 0; o < types.length; o++) {
      boolean wantNull = want.isNullAt(o);
      boolean gotNull = got.isNullAt(o);
      if (wantNull || gotNull) {
        if (wantNull != gotNull) {
          return "output " + o + ": Spark " + (wantNull ? "null" : value(want, o, types[o]))
              + ", Varka " + (gotNull ? "null" : value(got, o, types[o]));
        }
        continue;
      }
      DataType t = types[o];
      boolean same;
      if (t instanceof DoubleType) {
        same = Double.doubleToRawLongBits(want.getDouble(o))
            == Double.doubleToRawLongBits(got.getDouble(o));
      } else if (t instanceof FloatType) {
        same = Float.floatToRawIntBits(want.getFloat(o))
            == Float.floatToRawIntBits(got.getFloat(o));
      } else {
        same = Objects.equals(value(want, o, t), value(got, o, t));
      }
      if (!same) {
        return "output " + o + ": Spark " + value(want, o, t) + ", Varka " + value(got, o, t);
      }
    }
    return null;
  }

  /**
   * Column {@code o}'s value by its physical type: a columnar batch's row reads no {@code TIME}
   * through {@code get}, so the temporal types are read as the int or long they are stored as.
   */
  private static Object value(InternalRow row, int o, DataType t) {
    if (t instanceof TimeType || t instanceof DayTimeIntervalType || t instanceof TimestampType
        || t instanceof TimestampNTZType) {
      return row.getLong(o);
    }
    if (t instanceof DateType || t instanceof YearMonthIntervalType) {
      return row.getInt(o);
    }
    return row.get(o, t);
  }

  private static String describe(Expected e, DataType[] types) {
    if (e.error() != null) {
      return e.error().toString();
    }
    var parts = new ArrayList<String>();
    for (int o = 0; o < types.length; o++) {
      parts.add(e.row().isNullAt(o) ? "null" : String.valueOf(value(e.row(), o, types[o])));
    }
    return String.join(", ", parts);
  }
}
