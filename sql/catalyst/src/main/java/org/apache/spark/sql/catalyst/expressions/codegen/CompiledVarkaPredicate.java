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
package org.apache.spark.sql.catalyst.expressions.codegen;

import java.util.ArrayList;
import java.util.List;

import org.apache.spark.sql.catalyst.expressions.Expression;

/**
 * A filter predicate compiled conjunct by conjunct: {@code specs} classifies every conjunct of the
 * condition's {@code AND} spine in query order, and {@code fused} describes the mask kernel. Its
 * outputs are condition roots, each a selection bitmap, and each {@code outputTypes} entry is
 * {@code BooleanType} as a description only, since a selection bitmap never allocates an output
 * vector. The split mirrors {@link PartialVarkaProjection}'s per-entry eligibility: a mixed
 * {@code WHERE} fuses what it can, and the rule keeps the residual conjuncts in a row
 * {@code FilterExec} above the Varka node.
 *
 * <p>{@code clauses} says how the outputs make the selection: a row is selected when, in every
 * clause, at least one of the clause's outputs selects it. Usually there is one output, the fused
 * conjuncts recombined into one root, and one clause holding it. A predicate that one method
 * cannot hold is split under {@code splitConditions} (see
 * {@code VarkaExpressionCompiler.compilePredicate}) into several conjunction roots, each its own
 * clause, and a disjunction too large alone into several partial roots in one clause. Kleene logic
 * allows both at the mask: a row is known true for {@code a AND b} exactly when it is known true
 * for both, and for {@code a OR b} exactly when it is known true for either.
 *
 * <p>Internal to Spark SQL; public for {@code sql/core}.
 */
public record CompiledVarkaPredicate(
    List<VarkaConjunctSpec> specs,
    CompiledVarkaProjection fused,
    List<List<Integer>> clauses) {

  public CompiledVarkaPredicate {
    specs = List.copyOf(specs);
    java.util.Objects.requireNonNull(fused, "fused");
    var copied = new ArrayList<List<Integer>>(clauses.size());
    for (List<Integer> clause : clauses) {
      copied.add(List.copyOf(clause));
    }
    clauses = List.copyOf(copied);
  }

  /** The conjuncts the mask kernel serves, in query order, unbound. */
  public List<Expression> fusedConjuncts() {
    return conjuncts(true);
  }

  /** The conjuncts left to a row filter above, in query order, unbound. */
  public List<Expression> residualConjuncts() {
    return conjuncts(false);
  }

  private List<Expression> conjuncts(boolean fusedOnes) {
    var out = new ArrayList<Expression>();
    for (VarkaConjunctSpec spec : specs) {
      if (spec.fused() == fusedOnes) {
        out.add(spec.conjunct());
      }
    }
    return List.copyOf(out);
  }
}
