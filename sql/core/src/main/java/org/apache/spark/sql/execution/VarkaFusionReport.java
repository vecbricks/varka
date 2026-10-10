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

import scala.Option;
import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaPredicate;
import org.apache.spark.sql.catalyst.expressions.codegen.PartialVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaConjunctSpec;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaDecline;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler$;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec.ForwardedOutput;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec.FusedOutput;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec.KernelOutput;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec.ResidualOutput;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;

/**
 * How a Varka node serves each entry of its projection, in words.
 *
 * <p>Partial eligibility means a fused node can still evaluate entries per row, and until this
 * nothing said which entries those were or why: {@code compilePartial} classified every entry and
 * dropped the reason on the floor. This renders both - the classification and, for a residual
 * entry, the decline reason the compiler recorded - for the exec nodes' verbose {@code EXPLAIN}
 * and their debug logs, which is where the question "why didn't my projection fuse?" is actually
 * asked.
 *
 * <p>Rendering is diagnostics only and never on an execution path: the plan-side overload
 * compiles the projection again (the compiler is pure and cheap, and {@code EXPLAIN} runs once),
 * while the evaluator passes the plan it already compiled.
 */
public final class VarkaFusionReport {

  private static final int PREDICATE_WIDTH = 80;

  private VarkaFusionReport() {
  }

  /** One line per projection entry, against an already compiled classification. */
  public static Seq<String> lines(
      PartialVarkaProjection partial,
      Seq<NamedExpression> projectList,
      Seq<Attribute> childOutput) {
    var out = new ArrayList<String>();
    List<VarkaOutputSpec> specs = partial.specs();
    for (int position = 0; position < specs.size(); position++) {
      String name = projectList.apply(position).name();
      out.add(name + ": " + switch (specs.get(position)) {
        case FusedOutput f -> "fused";
        case KernelOutput k -> "fused, kernel " + (k.kernel() + 1) + " of "
            + partial.kernels().size();
        case ForwardedOutput f -> "forwarded from " + childOutput.apply(f.childOrdinal()).name();
        case ResidualOutput r -> {
          VarkaDecline decline = partial.declines().get(position);
          yield "residual (" + (decline != null ? decline.toString() : "no reason recorded") + ")";
        }
      });
    }
    return CollectionConverters.asScala(out).toSeq();
  }

  /**
   * The same over a memoized classification - the exec nodes' entry point (one compilation
   * serves {@code EXPLAIN} and the driver-side residual count).
   */
  public static Seq<String> lines(
      Option<PartialVarkaProjection> partial,
      Seq<NamedExpression> projectList,
      Seq<Attribute> childOutput) {
    if (partial.isDefined()) {
      return lines(partial.get(), projectList, childOutput);
    }
    return CollectionConverters.asScala(List.of("no entry is Varka-eligible")).toSeq();
  }

  /** The same, compiling the projection first - the plan-side entry point. */
  public static Seq<String> lines(
      Seq<NamedExpression> projectList, Seq<Attribute> childOutput, VarkaEmitOptions options) {
    return lines(VarkaExpressionCompiler$.MODULE$.compilePartial(projectList, childOutput, options),
        projectList, childOutput);
  }

  /** {@link #lines(Seq, Seq, VarkaEmitOptions)} under the emitter's default options. */
  public static Seq<String> lines(Seq<NamedExpression> projectList, Seq<Attribute> childOutput) {
    return lines(projectList, childOutput, VarkaEmitOptions.DEFAULTS);
  }

  /**
   * The filter counterpart: one line per conjunct of the predicate's {@code AND} spine - fused
   * into the mask kernel, or residual with the compiler's reason. On a Varka filter node every
   * line reads "fused" by construction (the rule keeps residual conjuncts in a row
   * {@code FilterExec} above); the mixed rendering exists for logs and for reporting the original,
   * unsplit condition. A predicate too large for one method that the compiler split across
   * several selection outputs gets one more line saying so.
   */
  public static Seq<String> predicateLines(
      Expression condition, Seq<Attribute> childOutput, VarkaEmitOptions options) {
    var out = new ArrayList<String>();
    var compiler = VarkaExpressionCompiler$.MODULE$;
    for (VarkaConjunctSpec spec
        : CollectionConverters.asJava(compiler.explainPredicate(condition, childOutput, options))) {
      if (spec.fused()) {
        out.add(render(spec.conjunct()) + ": fused");
      } else {
        String why = spec.decline().map(VarkaDecline::toString).orElse("no reason recorded");
        out.add(render(spec.conjunct()) + ": residual (" + why + ")");
      }
    }
    Option<CompiledVarkaPredicate> predicate =
        compiler.compilePredicate(condition, childOutput, options);
    if (predicate.isDefined() && predicate.get().fused().outputs().size() > 1) {
      CompiledVarkaPredicate p = predicate.get();
      out.add("split across " + p.fused().outputs().size() + " selection outputs in "
          + p.clauses().size() + " clauses, each within the method budget");
    }
    return CollectionConverters.asScala(out).toSeq();
  }

  /** {@link #predicateLines(Expression, Seq, VarkaEmitOptions)} under the default options. */
  public static Seq<String> predicateLines(Expression condition, Seq<Attribute> childOutput) {
    return predicateLines(condition, childOutput, VarkaEmitOptions.DEFAULTS);
  }

  /** A conjunct in the query's own words, capped like {@code DeclineSink}'s renderings. */
  private static String render(Expression conjunct) {
    String text = conjunct.sql();
    return text.length() > PREDICATE_WIDTH ? text.substring(0, PREDICATE_WIDTH - 3) + "..." : text;
  }
}
