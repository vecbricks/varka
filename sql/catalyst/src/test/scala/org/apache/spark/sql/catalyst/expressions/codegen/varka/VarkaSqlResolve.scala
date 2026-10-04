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
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, Expression}
import org.apache.spark.sql.catalyst.plans.logical.{LocalRelation, Project}

/**
 * One resolver for the SQL-to-expression tools in this package, because there were two and only
 * one of them was right.
 *
 * `VarkaEmitDump` and `VarkaWordCensus` each carried a byte-identical `transformUp` that bound
 * attributes by name and looked functions up in the registry - and nothing else. That is enough
 * for a function call and not enough for an operator: `d + ym` parses to `Add(date,
 * yearmonthinterval)` and only becomes `DateAddYMInterval` when the analyzer's type coercion
 * rewrites it, so without that pass the compiler saw an `Add` over two types it has no arm for
 * and declined. The dump reported "declined" for expressions `Surface` had been timing with
 * `expectFused` for weeks, and the census worked around the same bug by hand, truncating its
 * corpus to "the `Surface` projections that resolve without the analyzer's type coercion" -
 * which silently dropped every date/interval shape VARKA-67 added.
 *
 * Fixing one copy would have left the other wrong with nothing in the tree saying so, which is
 * why this is a shared object rather than a second patch.
 */
object VarkaSqlResolve {

  /**
   * Bind `e` against `columns` and run the analyzer over the result, so operators get the type
   * coercion they need.
   *
   * Throws rather than casting when the analyzer rewrites the projection into something else -
   * `ExtractGenerator` replaces an aliased `explode` with the generator's output attributes,
   * `ExtractWindowExpressions` lifts a window function out - because those are not expressions
   * this emitter can take, and a caller that reports declines should report them rather than
   * die of a `ClassCastException`.
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
    SimpleAnalyzer.execute(Project(Seq(Alias(bound, "a")()), LocalRelation(columns))) match {
      case Project(Seq(a: Alias), _) => a.child
      case other =>
        throw new IllegalArgumentException(
          s"the analyzer rewrote this into ${other.nodeName}; the emitter takes projections of "
            + "one expression, so this shape has no IR to dump")
    }
  }
}
