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

import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Add;
import org.apache.spark.sql.catalyst.expressions.EquivalentExpressions;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.Greatest;
import org.apache.spark.sql.catalyst.expressions.Least;
import org.apache.spark.sql.catalyst.expressions.Multiply;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.sql.types.DoubleType;
import org.apache.spark.sql.types.FloatType;

/**
 * Whether a Varka projection or filter is left to Spark because Spark's subexpression elimination
 * would give two of its expressions one value that Varka computes twice, and the two values can
 * differ (VARKA-341).
 *
 * <p>Spark groups the expressions of one projection, or the conjuncts of one filter, by
 * {@code semanticEquals}, which compares {@code canonicalized} forms, and evaluates each group
 * of two or more always-evaluated uses once, giving every member of the group the value of the
 * first one in the list. For a projection that holds in the whole-stage projection, the
 * generated {@code UnsafeProjection} and the interpreted one alike, whenever
 * {@code spark.sql.subexpressionElimination.enabled} is on, the default; a filter eliminates
 * where {@code FilterExec.eliminatesSubexpressions} says so, or, not compiled whole-stage, under
 * the same setting. A canonical form sorts the children of {@code greatest} and {@code least}
 * and flattens and sorts a chain of {@code +} or of {@code *}. So {@code greatest(a, b)} and
 * {@code greatest(b, a)} are one group, as are {@code (a + b) + c} and {@code a + (b + c)}. On
 * {@code FLOAT} and {@code DOUBLE} values the members differ: {@code greatest(-0.0, 0.0)} is
 * {@code -0.0} and {@code greatest(0.0, -0.0)} is {@code 0.0}, two NaNs give the first one's
 * bits, and {@code (0.1 + 0.2) + 0.3} is {@code 0.6000000000000001} where
 * {@code 0.1 + (0.2 + 0.3)} is {@code 0.6}. Spark answers every member with the first member's
 * value; a Varka kernel evaluates each member as written.
 *
 * <p>Until Varka shares one value as Spark does, such an operator declines: when a group has two
 * members that are not the same expression, one of them holds a floating {@code greatest},
 * {@code least}, {@code +} or {@code *}, and one of them is in an expression the kernel
 * computes. Members that are the same expression give the same value, so sharing them is
 * harmless, and a group whose members are all left to Spark's own per-row evaluation is shared
 * there as Spark shares it.
 *
 * <p>The setting is read when the rule plans the query; a plan reused after the setting changes
 * keeps the decision it was planned with.
 */
public final class VarkaSharedOrderDecline {

  private VarkaSharedOrderDecline() {
  }

  /**
   * Why an operator over {@code expressions} is left to Spark, or empty when it keeps its
   * kernel. {@code fused} says, for each expression, whether the kernel computes it; the groups
   * are Spark's, over all of them in order. Empty when {@code eliminates}, whether Spark's
   * operator eliminates common subexpressions, is false.
   */
  public static Optional<String> reason(
      List<Expression> expressions, List<Boolean> fused, boolean eliminates) {
    if (!eliminates || !fused.contains(true)
        || expressions.stream().noneMatch(VarkaSharedOrderDecline::ordered)) {
      return Optional.empty();
    }
    SQLConf conf = SQLConf.get();
    var groups = new EquivalentExpressions(conf.subexpressionEliminationSkipForShotcutExpr());
    for (Expression e : expressions) {
      groups.addExprTree(e, groups.addExprTree$default$2());
    }
    for (Expression common : CollectionConverters.asJava(groups.getCommonSubexpressions())) {
      if (!ordered(common)) {
        continue;
      }
      var members = new ArrayList<Expression>();
      boolean inKernel = false;
      for (int i = 0; i < expressions.size(); i++) {
        int before = members.size();
        collect(expressions.get(i), common, members);
        inKernel |= fused.get(i) && members.size() > before;
      }
      if (!inKernel) {
        continue;
      }
      for (Expression member : members) {
        if (!member.equals(common)) {
          return Optional.of("holds " + common.sql() + " and " + member.sql()
              + ", which Spark's subexpression elimination evaluates once and which can differ "
              + "on floating values by their operands' order (VARKA-341)");
        }
      }
    }
    return Optional.empty();
  }

  /** Whether {@code e} holds a floating node whose answer depends on its operands' order. */
  private static boolean ordered(Expression e) {
    if ((e instanceof Greatest || e instanceof Least || e instanceof Add
        || e instanceof Multiply)
        && (e.dataType() instanceof DoubleType || e.dataType() instanceof FloatType)) {
      return true;
    }
    var children = e.children();
    for (int i = 0; i < children.size(); i++) {
      if (ordered(children.apply(i))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Adds to {@code members} each subtree of {@code e} in {@code common}'s group, outermost
   * first, without looking inside a member, as Spark replaces it whole.
   */
  private static void collect(Expression e, Expression common, List<Expression> members) {
    if (e.semanticEquals(common)) {
      members.add(e);
      return;
    }
    var children = e.children();
    for (int i = 0; i < children.size(); i++) {
      collect(children.apply(i), common, members);
    }
  }
}
