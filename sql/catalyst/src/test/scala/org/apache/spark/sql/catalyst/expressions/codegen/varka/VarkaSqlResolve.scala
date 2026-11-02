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

package org.apache.spark.sql.catalyst.expressions.codegen.varka

import org.apache.spark.sql.catalyst.analysis.{SimpleAnalyzer, UnresolvedAttribute}
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, AttributeReference,
  BindReferences, Cast, Expression, Literal, NamedExpression}
import org.apache.spark.sql.catalyst.optimizer.RewriteWithExpression
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser
import org.apache.spark.sql.catalyst.plans.logical.{LocalRelation, Project}
import org.apache.spark.sql.types.{DataType, DoubleType, FloatType, StringType}

/**
 * One resolver for the SQL-to-expression tools in this package (`VarkaEmitDump`,
 * `VarkaWordCensus`), shared so that the tools cannot resolve differently.
 *
 * Binding attributes by name and looking functions up in the registry is enough for a function
 * call and not enough for an operator: `d + ym` parses to `Add(date, yearmonthinterval)` and
 * only becomes `DateAddYMInterval` when the analyzer's type coercion rewrites it, so without
 * that pass the compiler sees an `Add` over two types it has no arm for and declines - and a
 * tool reports "declined" for an expression `Surface` times as fused, or silently drops every
 * date/interval shape from its corpus.
 */
object VarkaSqlResolve {

  /**
   * A column the tools declare, for the Java tools: `AttributeReference`'s expression id and
   * qualifier sit in a second parameter list whose defaults Java cannot reach.
   */
  def column(name: String, dataType: DataType): AttributeReference =
    AttributeReference(name, dataType)()

  /** `text` parsed by Catalyst's parser and resolved against `columns`, as [[resolve]] does. */
  def parse(text: String, columns: Seq[Attribute]): Expression =
    resolve(CatalystSqlParser.parseExpression(text), columns)

  /** `e` as a projection entry named `name`, for the Java tools, as [[column]] is. */
  def named(e: Expression, name: String): NamedExpression = Alias(e, name)()

  /**
   * `e` with its attributes bound to their ordinals in `columns`, as the compiler binds an entry,
   * for the Java tools: the implicit `AttributeSeq` the binding takes is not nameable from Java.
   */
  def bind(e: Expression, columns: Seq[Attribute]): Expression =
    BindReferences.bindReference(e, columns)

  /**
   * Bind `e` against `columns` and run the analyzer over the result, so operators get the type
   * coercion they need.
   *
   * Throws rather than casting when the analyzer rewrites the projection into something else -
   * `ExtractGenerator` replaces an aliased `explode` with the generator's output attributes,
   * `ExtractWindowExpressions` lifts a window function out - because those are not expressions
   * this emitter can take, and a caller that reports declines should report them rather than
   * die of a `ClassCastException`.
   *
   * A string literal cast to `DOUBLE` or `FLOAT` is folded to its value, as Spark's optimizer
   * folds it before Varka sees a plan: it is how SQL writes NaN and the infinities, which have
   * no literal of their own, and the compiler declines a floating cast. A cast of a `NULL`
   * literal is folded to the typed null for the same reason: `if(c, x, NULL)` arrives at Varka
   * as `If(c, x, Literal(null, t))`. And a cast to its child's own type is its child, as the
   * optimizer's `SimplifyCasts` makes it: the analyzer writes one over the datetime arithmetic
   * it rewrites (`ntz + dt` arrives as `Cast(TimestampAddInterval(ntz, dt), TIMESTAMP_NTZ)`), and
   * no plan Varka sees still holds it.
   *
   * A `With`, which `nullif` and `between` resolve to, is rewritten by the optimizer's own
   * `RewriteWithExpression`, as a session's optimizer rewrites it before Varka sees a plan: over
   * a column the common expression is inlined (`nullif(i, 3)` is `If(i = 3, NULL, i)`), and
   * over a computed one it moves to a projection of its own below, a plan of two projections,
   * so there the `With` is kept as the analyzer left it (`VARKA-333.md` 2.1).
   */
  def resolve(e: Expression, columns: Seq[Attribute]): Expression = {
    val byName = columns.map(a => a.name -> a).toMap
    val bound = e.transformUp {
      case UnresolvedAttribute(Seq(name)) =>
        byName.getOrElse(name, throw new IllegalArgumentException(
          s"unknown column $name; declare it with --columns"))
    }
    // Functions are left to the analyzer, whose registry is the builtin one: a lookup before it
    // runs would hand `hour(t + dt)` its argument still uncoerced, and the builder-backed
    // functions assert that their arguments are resolved.
    val analyzed =
      SimpleAnalyzer.execute(Project(Seq(Alias(bound, "a")()), LocalRelation(columns)))
    // Only a rewrite that stays one projection is taken: one that moves a common expression
    // into a projection below leaves the `With` in place, which the compiler declines, and a
    // domain that resolves through this method still resolves.
    val rewritten = RewriteWithExpression(analyzed) match {
      case p @ Project(Seq(_: Alias), _: LocalRelation) => p
      case _ => analyzed
    }
    rewritten match {
      case Project(Seq(a: Alias), _) => a.child.transformUp {
        case c @ Cast(Literal(_, StringType), DoubleType | FloatType, _, _) =>
          Literal.create(c.eval(), c.dataType)
        case Cast(Literal(null, _), t, _, _) => Literal(null, t)
        case Cast(child, dataType, _, _) if child.dataType == dataType => child
      }
      case other =>
        throw new IllegalArgumentException(
          s"the analyzer rewrote this into ${other.nodeName}; the emitter takes projections of "
            + "one expression, so this shape has no IR to dump")
    }
  }
}
