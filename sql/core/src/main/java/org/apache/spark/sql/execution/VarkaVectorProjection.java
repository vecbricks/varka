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
import java.util.Iterator;

import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.MutableProjection;
import org.apache.spark.sql.catalyst.expressions.MutableProjection$;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.UnsafeProjection;
import org.apache.spark.sql.catalyst.expressions.UnsafeProjection$;
import org.apache.spark.sql.catalyst.expressions.codegen.CodeGenerator$;
import org.apache.spark.sql.catalyst.types.DataTypeUtils$;
import org.apache.spark.sql.execution.vectorized.MutableColumnarRow;
import org.apache.spark.sql.execution.vectorized.WritableColumnVector;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.vectorized.ColumnarBatch;

/**
 * The row path of a Varka node whose output is columnar: each row of an input batch projected into
 * the writable column vectors of an output batch.
 *
 * <p>When every output column has a primitive Java type - the fixed-width types, and the dates,
 * timestamps, times and intervals stored as them - a mutable projection writes each entry straight
 * into its vector, through a {@link MutableColumnarRow} over the output vectors whose
 * {@code rowId} is the row being written: the pattern Spark's vectorized hash aggregation uses.
 * Any other output takes the conversion instead: each row is projected into an
 * {@code UnsafeRow}, and {@code RowToColumnConverter} copies it into the vectors. The generated
 * code writes a string or a nested value through the row's {@code update}, which
 * {@code MutableColumnarRow} rejects, and a null decimal or calendar interval through its typed
 * setter with a null value rather than through {@code setNullAt}, so only the primitive types are
 * safe to write directly. The direct write saves the {@code UnsafeRow} and the converter's pass
 * over it, about a tenth of the row path's time; see {@code VARKA-230.md} 2.
 *
 * <p>Either way the projection reads its input through {@link VarkaRowView}, which hands a small
 * decimal long-backed as vanilla Spark's row path does, and {@link VarkaInputRows}. Both
 * projections are compiled on first use, so a task that never takes the row path compiles neither.
 */
final class VarkaVectorProjection {

  private final Seq<NamedExpression> projectList;
  private final Seq<Attribute> childOutput;
  private final StructType outputSchema;
  private final boolean writesDirectly;

  // Built on first use; the class is used by one task thread, as the evaluator that owns it is.
  private VarkaInputRows inputRows;
  private MutableProjection direct;
  private UnsafeProjection unsafe;
  private RowToColumnConverter converter;
  private final VarkaRowView rowView = new VarkaRowView();

  VarkaVectorProjection(Seq<NamedExpression> projectList, Seq<Attribute> childOutput) {
    this.projectList = projectList;
    this.childOutput = childOutput;
    var attributes = new ArrayList<Attribute>();
    for (NamedExpression named : CollectionConverters.asJava(projectList)) {
      attributes.add(named.toAttribute());
    }
    this.outputSchema = DataTypeUtils$.MODULE$.fromAttributes(
        CollectionConverters.asScala(attributes).toSeq());
    boolean primitive = true;
    for (StructField field : outputSchema.fields()) {
      primitive &= CodeGenerator$.MODULE$.isPrimitiveType(field.dataType());
    }
    this.writesDirectly = primitive;
  }

  /** The projection list as the expressions Catalyst's factories take; Scala's {@code Seq} is
   * covariant and Java's view of it is not. */
  // Safe: Scala's immutable Seq is covariant, so a Seq of NamedExpression is a Seq of Expression.
  @SuppressWarnings("unchecked")
  private Seq<Expression> expressions() {
    return (Seq<Expression>) (Seq<?>) projectList;
  }

  /** The schema of the output batch. */
  StructType outputSchema() {
    return outputSchema;
  }

  /** Whether the entries are written straight into the vectors. */
  boolean writesDirectly() {
    return writesDirectly;
  }

  /**
   * Projects every row of {@code input} into {@code output}, from position 0, and returns the
   * number of rows written. {@code output} holds one freshly allocated vector per output column,
   * with room for every row of {@code input}.
   */
  int project(ColumnarBatch input, WritableColumnVector[] output) {
    if (inputRows == null) {
      inputRows = new VarkaInputRows(expressions(), childOutput);
    }
    Iterator<InternalRow> rows = rowView.of(input).rowIterator();
    int count = 0;
    if (writesDirectly) {
      if (direct == null) {
        direct = MutableProjection$.MODULE$.create(expressions(), inputRows.attributes());
      }
      var target = new MutableColumnarRow(output);
      direct.target(target);
      while (rows.hasNext()) {
        target.rowId = count;
        direct.apply(inputRows.apply(rows.next()));
        count++;
      }
    } else {
      if (unsafe == null) {
        unsafe = UnsafeProjection$.MODULE$.create(expressions(), inputRows.attributes());
        converter = VarkaRowToColumn.apply(outputSchema);
      }
      while (rows.hasNext()) {
        converter.convert(unsafe.apply(inputRows.apply(rows.next())), output);
        count++;
      }
    }
    return count;
  }
}
