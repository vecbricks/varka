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

package org.apache.spark.sql.catalyst.expressions.codegen.varka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import scala.jdk.javaapi.CollectionConverters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtNodes.Outcome;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtNodes.Refused;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtSamples.Answer;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtSamples.Row;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtSamples.Sample;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtTerm.Num;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtTerm.Sort;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtTerm.Var;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtTerm.WordForm;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntNeg;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.sql.internal.SQLConf$;
import org.apache.spark.sql.types.DataTypes;

/**
 * The proof printer's meanings against the reference evaluator (VARKA-506 3 items 1 and 3):
 * every template {@link VarkaSmtNodes} states, and the word form of every long in it, evaluated
 * over {@code BigInteger} on the fuzzer's sequences and the boundary words and held to
 * {@link VarkaReference}; every IR record given a template or a refusal; and the int lane's
 * templates, at the coverage rows' own trees, equal term for term to {@code refinement.smt2}'s
 * hand-written kernels. {@code smt_nodes_check.smt2} holds the same printed text to the same
 * answers under the solvers.
 */
@VarkaSuite
public final class VarkaSmtNodesSuite {

  /** The record classes some sample rendered, which the completeness test reads. */
  private static final Set<Class<?>> RENDERED = new HashSet<>();

  @Test
  @DisplayName("every printed outcome, and the word form of every long in it, is the reference "
      + "evaluator's answer on the fuzzer's sequences and the boundary words")
  void templatesAgreeWithTheReference() {
    int rendered = 0;
    int compared = 0;
    var refusals = new TreeMap<String, Integer>();
    var wrong = new ArrayList<String>();
    for (Sample s : VarkaSmtSamples.samples(200, 12)) {
      var printer = new VarkaSmtNodes(s.intLits(), s.longLits());
      Outcome o;
      try {
        o = printer.outcome(s.root());
      } catch (Refused r) {
        refusals.merge(r.getMessage(), 1, Integer::sum);
        continue;
      }
      rendered++;
      reached(s.root());
      var words = new WordForm();
      Outcome printed = VarkaSmtSamples.printed(o, s.root(), words);
      for (Row row : s.rows()) {
        Answer want = VarkaSmtSamples.answer(s, s.root(), row);
        Map<String, Object> env = VarkaSmtSamples.env(row);
        for (var ob : words.obligations) {
          if (!Boolean.TRUE.equals(ob.claim().eval(env))) {
            wrong.add(s.family() + " " + VarkaVectorIR.canonical(s.root()) + ": " + ob.what());
          }
        }
        String got = differs(o, want, env, false, "")
            + differs(printed, want, env, VarkaSmtSamples.readsWord(s.root()), " (word form)");
        if (!got.isEmpty()) {
          wrong.add(s.family() + " " + VarkaVectorIR.canonical(s.root()) + " at "
              + java.util.Arrays.toString(row.values()) + " "
              + java.util.Arrays.toString(row.nulls()) + ":" + got);
        }
        compared++;
      }
    }
    assertTrue(wrong.isEmpty(), wrong.size() + " rows differ, the first:\n  "
        + String.join("\n  ", wrong.subList(0, Math.min(10, wrong.size()))));
    // The draws mostly render: a refused node in most of them would leave the meanings unheld.
    assertTrue(rendered >= 300, rendered + " trees rendered; refused: " + refusals);
    assertTrue(compared >= 3600, compared + " rows compared");
  }

  /** What in {@code o} differs from {@code want}, empty when nothing does. */
  private static String differs(Outcome o, Answer want, Map<String, Object> env,
      boolean reading, String form) {
    if (!o.decline().eval(env).equals(want.decline())) {
      return " decline " + o.decline().eval(env) + form;
    }
    if (want.decline()) {
      return "";
    }
    if (!o.valid().eval(env).equals(want.valid())) {
      return " valid " + o.valid().eval(env) + form;
    }
    if (!want.valid()) {
      return "";
    }
    Object data = o.data().eval(env);
    Object expected = want.data();
    if (reading) {
      BigInteger v = (BigInteger) expected;
      expected = v.mod(VarkaSmtTerm.WORD);
    }
    return data.equals(expected) ? "" : " data " + data + " for " + expected + form;
  }

  private static void reached(VarkaVectorIR node) {
    RENDERED.add(node.getClass());
    for (VarkaVectorIR child : VarkaVectorIR.childrenOf(node)) {
      reached(child);
    }
  }

  @Test
  @DisplayName("every IR record has a template some sample reaches or a refusal with its reason, "
      + "never both")
  void everyRecordHasATemplateOrARefusal() {
    templatesAgreeWithTheReference();
    var missing = new ArrayList<String>();
    for (Class<?> c : records(VarkaVectorIR.class)) {
      boolean refused = VarkaSmtNodes.REFUSED.containsKey(c);
      if (refused == RENDERED.contains(c)) {
        missing.add(c.getSimpleName() + (refused ? " (refused and rendered)" : ""));
      }
    }
    assertTrue(missing.isEmpty(), "records with no template reached and no refusal: " + missing);
  }

  private static List<Class<?>> records(Class<?> sealed) {
    var out = new ArrayList<Class<?>>();
    for (Class<?> c : sealed.getPermittedSubclasses()) {
      if (c.isRecord()) {
        out.add(c);
      } else {
        out.addAll(records(c));
      }
    }
    return out;
  }

  @Test
  @DisplayName("the int lane's templates at the coverage rows' trees are refinement.smt2's "
      + "hand-written kernels, term for term, the row's operands named a and b")
  void intKernelsMatchTheHandRows() {
    Attribute i = VarkaSqlResolve.column("i", DataTypes.IntegerType);
    Attribute d = VarkaSqlResolve.column("d", DataTypes.DateType);
    Map<String, String> rows = Map.of("a + b", "i + 1", "a - b", "i - 1", "a * 2", "year(d) * 2",
        "-a", "-i");
    for (VarkaRefinementFile.IntKernel k : VarkaRefinementFile.INT_KERNELS) {
      for (boolean ansi : new boolean[] {false, true}) {
        CompiledVarkaProjection p = compile(rows.get(k.name()), List.of(i, d), ansi);
        VarkaVectorIR root = p.outputs().get(0);
        int[] lits = p.literals().stream().mapToInt(Integer::intValue).toArray();
        var printer = new VarkaSmtNodes(lits, null);
        VarkaVectorIR[] kids = VarkaVectorIR.childrenOf(root);
        Outcome o = printer.template(root, n -> operand(printer, n, n == kids[0] ? "a" : "b",
            k.name().equals("a * 2")));
        String fail = "(and " + k.valid() + " (not (jint.in " + k.exact() + ")))";
        String at = k.name() + (ansi ? " under ANSI" : " under LEGACY") + ", from "
            + VarkaVectorIR.canonical(root);
        // year(d) * 2 is checked under ANSI in the hand row, but the compiler drops the check:
        // the year's range bounds the product (VarkaCoverageSuite's note on the row), so the
        // kernel it builds is the WRAP one in both modes (VARKA-506.md 10.1).
        boolean checked = ansi && !k.name().equals("a * 2");
        Overflow mode = switch (root) {
          case IntArith a -> a.mode();
          case IntNeg a -> a.mode();
          default -> throw new IllegalStateException(at + ": not an arithmetic root");
        };
        assertEquals(checked ? Overflow.FAIL : Overflow.WRAP, mode, at);
        assertEquals(checked ? fail : "false", o.decline().render(), at);
        assertEquals(k.valid(), o.valid().render(), at);
        assertEquals(k.java(), o.data().render(), at);
      }
    }
  }

  /** An operand of the validation: a free {@code (name, v<name>)}, or the literal's constant. */
  private static Outcome operand(VarkaSmtNodes printer, VarkaVectorIR n, String name,
      boolean literalsAsConstants) {
    if (n instanceof LiteralSlot && literalsAsConstants) {
      Outcome lit = printer.outcome(n);
      assertTrue(lit.data() instanceof Num, "a literal renders as its constant");
      return lit;
    }
    return new Outcome(new VarkaSmtTerm.Flag(false), new Var("v" + name, Sort.BOOL),
        new Var(name, Sort.INT));
  }

  private static CompiledVarkaProjection compile(String sql, List<Attribute> columns,
      boolean ansi) {
    SQLConf conf = SQLConf.get().clone();
    conf.setConfString(SQLConf.ANSI_ENABLED().key(), Boolean.toString(ansi));
    var seq = CollectionConverters.asScala(columns).toSeq();
    Expression e = SQLConf$.MODULE$.withExistingConf(conf, () -> VarkaSqlResolve.parse(sql, seq));
    List<NamedExpression> out = List.of(VarkaSqlResolve.named(e, "c"));
    return VarkaExpressionCompiler.compile(CollectionConverters.asScala(out).toSeq(), seq)
        .orElseThrow(() -> new AssertionError(sql + " does not fuse"));
  }
}
