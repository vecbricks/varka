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
import java.util.Optional;
import java.util.function.UnaryOperator;

import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.And;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaPredicate;
import org.apache.spark.sql.catalyst.expressions.codegen.PartialVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaConjunctSpec;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrixRuns;
import org.apache.spark.sql.internal.SQLConf;

/**
 * Hand-written queries held to Spark's answer through {@link VarkaExpressionHarness} (VARKA-509,
 * {@code m8/VARKA-509.md} step 3): the shapes the end-to-end differential suites ran through two
 * sessions, each a query over a table named {@code t} that the harness reads as a scan of a
 * fixture. A case is one test of those suites, under its name, so that the option matrix's skip
 * lines move with it by the suite's name alone; its parts each set a table, an ANSI mode and the
 * emit options, as the end-to-end test sets them between its queries.
 *
 * <p>A projection runs under both consumers where the compiler fuses any of it, as the row node
 * is planned only then: the columnar node's {@code project}, which computes every output, and the
 * row node's {@code projectFused}, whose fused outputs are compared. A filter runs through the
 * filter evaluator, over the conjuncts the compiler fuses, which is what the rule hands the
 * Varka node; the others are the row filter's above it. Each query then states what Varka must
 * have done beside answering as Spark does, in the end-to-end suite's terms: a Varka node
 * planned, its kernels run, every batch served, a batch declined, or no node at all; and, for
 * some, what its verbose {@code EXPLAIN} says of an entry ({@link VarkaFusionReport}).
 */
final class VarkaHarnessCases {

  private VarkaHarnessCases() {}

  /** What Varka must have done for a query, beside every answer it gave being Spark's. */
  enum Expect {
    /** Fused whole and served every batch, none declined. */
    SERVED,
    /** Fused whole, and a kernel answered some batch. */
    WHOLE,
    /** Something fused, and a kernel answered some batch: "fused, and the kernels ran". */
    RUNS,
    /** Something fused, whatever the batches did: a Varka node in the plan. */
    FUSED,
    /** Fused whole, and a kernel declined a batch by a status it returned. */
    DECLINES,
    /** Nothing fused, so the rule plans no Varka node and the row engine answers. */
    NOT_FUSED,
    /** Nothing beyond the answers: a query Spark raises on, or one the compiler may refuse. */
    ANSWERS
  }

  /**
   * One query over {@code t}, what Varka must have done, and the text each of which some line of
   * its verbose {@code EXPLAIN} must contain.
   */
  record Shape(String sql, Expect expect, List<String> explains) {
    Shape(String sql, Expect expect) {
      this(sql, expect, List.of());
    }
  }

  /**
   * The table a case's queries read: {@code schema}, SQL whose columns are the fixture's, for a
   * session to plan the same queries over, and the fixture the harness reads.
   */
  record Source(String schema, VarkaHarnessPlans.Fixture fixture) {}

  /**
   * Queries over one table under one setting: the ANSI mode, the thread's where empty, and the
   * emit options, {@code options} applied to the matrix's base.
   */
  record Part(Source source, Optional<Boolean> ansi, UnaryOperator<VarkaEmitOptions> options,
      List<Shape> shapes) {
    Part(Source source, List<Shape> shapes) {
      this(source, Optional.empty(), UnaryOperator.identity(), shapes);
    }
  }

  /** One test of an end-to-end suite: its name and its parts. */
  record Case(String name, List<Part> parts) {
    Case(String name, Source source, List<Shape> shapes) {
      this(name, List.of(new Part(source, shapes)));
    }
  }

  /** A source whose rows are its SQL's own. */
  static Source source(String sql) {
    return new Source(sql, VarkaHarnessPlans.fixture(sql));
  }

  /**
   * A source with {@code schema}'s columns and {@code rows}, each row's values in Catalyst's
   * internal form: for a table too large to write as SQL.
   */
  static Source source(String schema, List<Object[]> rows) {
    var columns = VarkaHarnessPlans.fixture(schema).columns();
    var internal = new ArrayList<InternalRow>(rows.size());
    for (Object[] row : rows) {
      internal.add(new GenericInternalRow(row));
    }
    return new Source(schema, new VarkaHarnessPlans.Fixture(columns, internal));
  }

  /** {@code c}'s queries, part by part: what disagreed, one line a finding. */
  static List<String> check(Case c) {
    var findings = new ArrayList<String>();
    for (int k = 0; k < c.parts().size(); k++) {
      Part part = c.parts().get(k);
      String label = c.parts().size() > 1 ? ", part " + (k + 1) : "";
      SQLConf conf = VarkaCoverageHarnessSuite.conf(
          part.ansi().orElse(SQLConf.get().ansiEnabled()));
      VarkaColumnarToRowExec$.MODULE$.setEmitOptionsForTesting(
          part.options().apply(VarkaMatrixRuns.BASE));
      try {
        findings.addAll(VarkaCoverageHarnessSuite.withConf(conf, () -> {
          var found = new ArrayList<String>();
          for (Shape shape : part.shapes()) {
            found.addAll(check(shape, part, label, conf));
          }
          return found;
        }));
      } finally {
        VarkaColumnarToRowExec$.MODULE$.setEmitOptionsForTesting(VarkaMatrixRuns.BASE);
      }
    }
    return findings;
  }

  private static List<String> check(Shape shape, Part part, String label, SQLConf conf) {
    VarkaHarnessPlans.Fixture fixture = part.source().fixture();
    String context = shape.sql() + label;
    VarkaEmitOptions options = VarkaColumnarToRowExec$.MODULE$.currentEmitOptions();
    var outcomes = new ArrayList<VarkaExpressionHarness.Outcome>();
    int fused;
    int residual;
    List<String> explained;
    switch (VarkaHarnessPlans.query(shape.sql(), fixture.columns())) {
      case VarkaHarnessPlans.Query.Projection p -> {
        Optional<PartialVarkaProjection> partial =
            VarkaExpressionCompiler.compilePartial(p.list(), fixture.columns(), options);
        List<VarkaOutputSpec> specs = partial.map(PartialVarkaProjection::specs)
            .orElse(List.of());
        fused = (int) specs.stream().filter(x -> x instanceof VarkaOutputSpec.FusedOutput
            || x instanceof VarkaOutputSpec.KernelOutput).count();
        residual = partial.isEmpty() ? p.list().size()
            : (int) specs.stream().filter(x -> x instanceof VarkaOutputSpec.ResidualOutput).count();
        explained = CollectionConverters.asJava(
            VarkaFusionReport.lines(partial, p.list(), fixture.columns()));
        if (fused > 0) {
          for (var consumer : VarkaExpressionHarness.Consumer.values()) {
            outcomes.add(VarkaExpressionHarness.projection(context + ", " + consumer, p.list(),
                fixture, conf, consumer));
          }
        }
      }
      case VarkaHarnessPlans.Query.Selection s -> {
        // The conjuncts the compiler leaves to the row FilterExec above the Varka node, by the
        // compile the filter evaluator makes; all of them where none compiles.
        Optional<CompiledVarkaPredicate> predicate =
            VarkaExpressionCompiler.compilePredicate(s.condition(), fixture.columns(), options);
        fused = predicate.map(x -> (int) x.specs().stream().filter(c -> c.fused()).count())
            .orElse(0);
        residual = predicate.map(x -> (int) x.specs().stream().filter(c -> !c.fused()).count())
            .orElse(1);
        explained = CollectionConverters.asJava(
            VarkaFusionReport.predicateLines(s.condition(), fixture.columns(), options));
        if (fused > 0) {
          // The rule hands the Varka filter the fused conjuncts and leaves the others to a row
          // filter above it, which reads the rows the Varka filter keeps; the evaluator compiles
          // only a condition that fuses whole.
          Expression served = s.condition();
          if (residual > 0) {
            served = predicate.get().specs().stream().filter(VarkaConjunctSpec::fused)
                .map(VarkaConjunctSpec::conjunct).reduce(And::new).orElseThrow();
          }
          outcomes.add(VarkaExpressionHarness.filter(context, served, fixture, conf));
        }
      }
      case VarkaHarnessPlans.Query.Constant k -> {
        return List.of(context + ": the optimizer folded the query to a constant answer");
      }
    }
    var findings = new ArrayList<String>();
    for (String text : shape.explains()) {
      if (explained.stream().noneMatch(line -> line.contains(text))) {
        findings.add(context + ": no EXPLAIN line contains \"" + text + "\": " + explained);
      }
    }
    String fusion = fused + " fused, " + residual + " left to the row path";
    switch (shape.expect()) {
      case NOT_FUSED -> {
        if (fused > 0) {
          findings.add(context + ": expected nothing fused: " + fusion);
        }
        return findings;
      }
      case ANSWERS -> { }
      default -> {
        if (fused == 0) {
          findings.add(context + ": expected a Varka node: " + fusion);
        }
        boolean whole = shape.expect() == Expect.SERVED || shape.expect() == Expect.WHOLE
            || shape.expect() == Expect.DECLINES;
        if (whole && residual > 0) {
          findings.add(context + ": expected the query fused whole: " + fusion);
        }
      }
    }
    for (VarkaExpressionHarness.Outcome o : outcomes) {
      findings.addAll(o.findings);
      String batches = o.answered + " answered, " + o.declined + " declined"
          + (o.declined == 0 ? "" : " (statuses " + o.batches.stream()
              .filter(b -> b.status() != VarkaExpressionHarness.Batch.ANSWERED)
              .map(b -> Integer.toHexString(b.status())).distinct()
              .collect(java.util.stream.Collectors.joining(", ")) + ")");
      boolean kernelDeclined = o.batches.stream()
          .anyMatch(b -> b.status() != VarkaExpressionHarness.Batch.ANSWERED
              && b.status() != VarkaExpressionHarness.Batch.FELL_BACK);
      String missing = switch (shape.expect()) {
        case SERVED -> o.answered > 0 && o.declined == 0 ? null : "a kernel to serve every batch";
        case WHOLE, RUNS -> o.answered > 0 ? null : "a kernel to answer some batch";
        case DECLINES -> kernelDeclined ? null : "a kernel to decline a batch";
        case FUSED, NOT_FUSED, ANSWERS -> null;
      };
      if (missing != null) {
        findings.add(context + ": expected " + missing + ": " + batches);
      }
    }
    return findings;
  }
}
