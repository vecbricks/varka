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

import org.apache.spark.sql.catalyst.expressions.BasePredicate;
import org.apache.spark.sql.catalyst.expressions.CodeGeneratorWithInterpretedFallback;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.InterpretedPredicate;
import org.apache.spark.sql.catalyst.expressions.codegen.GeneratePredicate$;

/**
 * The per-row predicate behind Varka's filter nodes: what {@code Predicate.create} builds, except
 * that whether the generated code eliminates common subexpressions is the caller's argument, not
 * the session's {@code subexpressionElimination.enabled}.
 *
 * <p>The argument is what Spark's own filter does in the same place, which the driver resolves
 * ({@code VarkaFilterExecBase.fallbackEliminatesSubexpressions}). Where Spark compiles the filter,
 * its whole-stage code eliminates common subexpressions only when one of them is not cheap
 * ({@code FilterExec.eliminatesSubexpressions}); without elimination each conjunct, and each
 * operand inside one, short-circuits. {@code GeneratePredicate} with elimination on would instead
 * evaluate a cheap shared {@code arr[5]} before an {@code OR} whose first operand is TRUE, and
 * raise under ANSI on a row Spark answers (VARKA-541).
 *
 * <p>As in {@code Predicate}, a code generation that fails, or the test-only
 * {@code spark.sql.codegen.factoryMode=NO_CODEGEN}, gives the interpreted predicate, which reads
 * the session's setting itself. Its elimination evaluates a subexpression lazily, at its first
 * use, so it never evaluates one earlier than the conjuncts do, with or without the argument.
 */
final class VarkaFallbackPredicate {

  private VarkaFallbackPredicate() {
  }

  /**
   * The predicate of {@code bound}, a condition already bound to the child's output, with common
   * subexpressions eliminated when {@code eliminate} is true.
   */
  static BasePredicate create(Expression bound, boolean eliminate) {
    var factory = new CodeGeneratorWithInterpretedFallback<Expression, BasePredicate>() {
      @Override
      public BasePredicate createCodeGeneratedObject(Expression in) {
        return GeneratePredicate$.MODULE$.generate(in, eliminate);
      }

      @Override
      public BasePredicate createInterpretedObject(Expression in) {
        return new InterpretedPredicate(in);
      }
    };
    return factory.createObject(bound);
  }
}
