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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.*;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaPredicate;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaConjunctSpec;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaRefinementFile.Refinement;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtNodes.Outcome;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtTerm.Quot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSmtTerm.Var;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.sql.internal.SQLConf$;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.types.DecimalType;

/**
 * {@code decimal_refinement.smt2} (VARKA-506 2.6): each 64-bit decimal coverage row refined
 * against Spark, neither side written per row. The kernel is {@link VarkaSmtNodes#kernel}'s
 * printing of the IR the compiler builds, declining as the emitter's {@code Analysis} says; the
 * specification is Spark's analyzed expression read by one rule per Catalyst node ({@link Spec}).
 * A refused row is listed in the file's header with the reason, and keeps another verification.
 */
final class VarkaDecimalRefinementFile {

  private VarkaDecimalRefinementFile() {
  }

  /** The decimal columns of {@code VarkaCoverageSuite}, at its fixture types. */
  private static final List<Attribute> COLUMNS = List.of(column("d9", 9, 2),
      column("d9b", 9, 2), column("d18", 18, 2), column("d18b", 18, 2), column("d9s4", 9, 4),
      column("d16s9", 16, 9), column("d8", 8, 2));

  /** The InSet row: an IN list this long reaches Varka from the optimizer as an InSet. */
  static final String IN_SET = "d18 IN (11 to 16 decimal literals)";

  /**
   * The rows of {@code VarkaCoverageSuite}'s families "DECIMAL columns" (VARKA-347, VARKA-348)
   * and "DECIMAL arithmetic" (VARKA-349), in its order; that suite fails a row of either family
   * this file does not echo, so the list cannot fall behind the table.
   */
  static final List<String> SHAPES = List.of("d9 < d9b", "d9 <= d9b", "d9 > d9b", "d9 >= d9b",
      "d9 = d9b", "d9 <> d9b", "d9 <=> d9b", "d9 = 0.42", "d9 < d18", "d18 < d18b", "d18 = d18b",
      "d18 <=> d18b", "d18 >= 123456789012.34", "d9 IS NULL", "d9 IS NOT NULL", "d18 IS NULL",
      "d18 IS NOT NULL", "d9 < d9s4", "d9 = d9s4", "d9 <=> d9s4", "d16s9 >= d9s4", "d18 > 1.5",
      "d9 < 0.425", "d9 < 10000000.0125", "-d9 < d9b", "abs(d9) >= d9b",
      "greatest(d9, d9s4) > d9b", "coalesce(d9, d9b) IS NULL", "(-d18) IS NOT NULL",
      "CASE WHEN d9 < d9b THEN d9 ELSE d9s4 END > 0.0", "d9 IN (0.42, 0.00, -1.25)",
      "d9 IN (0.425, 1.5)", IN_SET, "d9 + d9b < d18", "d9 - d8 >= d9b", "d8 * d9 <= d9b",
      "try_add(d9, d9b) > d18", "d9 * 0.5 > d9b", "d9 + 1.5 < d9b", "d9 - d9s4 > 0.00",
      "d9 + d16s9 > d9s4", "-(d9 + d9b) = d9s4", "try_multiply(d8, d9) IS NULL");

  private static Attribute column(String name, int precision, int scale) {
    return VarkaSqlResolve.column(name, DataTypes.createDecimalType(precision, scale));
  }

  /** A row the printer or the specification cannot state, with the reason. */
  private static final class Refused extends RuntimeException {
    Refused(String reason) {
      super(reason);
    }
  }

  /** An unscaled value as a long, refused past one. */
  private static long word(BigInteger u) {
    if (u.bitLength() > 63) {
      throw new Refused("a literal past a long: " + u);
    }
    return u.longValue();
  }

  static String render() {
    var body = new StringBuilder();
    var refused = new LinkedHashMap<String, String>();
    for (String sql : SHAPES) {
      try {
        shape(body, sql);
      } catch (Refused | VarkaSmtNodes.Refused r) {
        refused.put(sql, r.getMessage());
      }
    }
    var out = new StringBuilder(VarkaProofFiles.LICENSE);
    out.append("""
        ; The 64-bit decimal kernels refined against Spark (VARKA-506 2.6). Per coverage row of the
        ; families "DECIMAL columns" and "DECIMAL arithmetic", compiled as VarkaCoverageSuite
        ; compiles it under an ANSI and a LEGACY session: the kernel printed by VarkaSmtNodes from
        ; the IR the compiler built, its decline the emitter's guards on the lanes Analysis
        ; qualifies them by, every long in the word form with its obligations; and the
        ; specification read from Spark's analyzed expression into decimal_spec.smt2's spec.dec.*,
        ; a column's valid slot past its precision raising as Spark's reader does, a result past
        ; its type's precision raising under ANSI and null otherwise. The five checks are
        ; refinement.smt2's; a row whose two sessions print alike is checked once.
        ;
        ; Refused, each keeping the verification VarkaCoverageSuite names for it:
        """);
    refused.forEach((sql, why) -> out.append(";   ").append(sql).append(": ").append(why)
        .append('\n'));
    out.append(";\n; prelude: decimal_spec.smt2\n");
    out.append(VarkaProofFiles.rendered("ALL"));
    out.append(body);
    return out.toString();
  }

  /** The checks of one row: one set where both sessions print alike, else one per session. */
  private static void shape(StringBuilder out, String sql) {
    var bySession = new LinkedHashMap<String, List<String>>();
    for (boolean ansi : new boolean[] {true, false}) {
      bySession.put(ansi ? "ANSI" : "LEGACY", checks(sql, ansi));
    }
    List<String> ansi = bySession.get("ANSI");
    if (ansi.equals(bySession.get("LEGACY"))) {
      emit(out, sql + ", Spark mode, an ANSI and a LEGACY session alike", ansi);
    } else {
      bySession.forEach((session, lines) -> emit(out, sql + ", Spark mode, "
          + (session.equals("ANSI") ? "an" : "a") + " " + session + " session", lines));
    }
  }

  private static void emit(StringBuilder out, String name, List<String> rendered) {
    out.append(String.join("", rendered).replace(PLACEHOLDER, name));
  }

  private static final String PLACEHOLDER = "@shape@";

  /** A row's checks in one session, named by {@link #PLACEHOLDER}. */
  private static List<String> checks(String sql, boolean ansi) {
    SQLConf conf = SQLConf.get().clone();
    conf.setConfString(SQLConf.ANSI_ENABLED().key(), Boolean.toString(ansi));
    return SQLConf$.MODULE$.withExistingConf(conf, () -> {
      Expression e = expression(sql);
      CompiledVarkaProjection k = kernelOf(sql, e);
      VarkaVectorIR root = k.outputs().get(0);
      var printer = new VarkaSmtNodes(k.literals().stream().mapToInt(Integer::intValue).toArray(),
          k.longLiterals().stream().mapToLong(Long::longValue).toArray());
      Outcome o = printer.kernel(root, VarkaLoopEmitter.guardsForTest(k.outputs(),
          k.inputOrdinals().size(), k.numLiterals(), VarkaEmitOptions.DEFAULTS));
      var words = new VarkaSmtTerm.WordForm();
      Outcome p = VarkaSmtSamples.printed(o, root, words);
      var spec = new Spec(k.inputOrdinals());
      Spec.Value s = spec.of(e);
      if (s.precision() >= 0) {
        throw new Refused("not a predicate");
      }
      String raises = Spec.or(s.raise(), Spec.any(spec.reads));
      var setup = setup(printer.facts, spec.ordinals, List.of(p.decline(), p.valid(), p.data()),
          words.obligations);
      var out = new StringBuilder();
      var seen = new LinkedHashSet<String>();
      for (var ob : words.obligations) {
        if (seen.add(ob.claim().render())) {
          var lines = new ArrayList<>(setup);
          lines.add("(assert (not " + ob.claim().render() + "))");
          VarkaProofFiles.check(out, PLACEHOLDER + ": the word form's " + ob.what(), "unsat",
              lines);
        }
      }
      VarkaRefinementFile.refine(out, new Refinement(PLACEHOLDER, setup, "true", "true",
          s.valid(), raises, s.valid(), p.decline().render(), p.valid().render(),
          "(not (= " + p.data().render() + " " + s.value() + "))", List.of()));
      var names = new ArrayList<String>();
      for (int i = 0; i < k.inputOrdinals().size(); i++) {
        names.add("c" + i + " " + COLUMNS.get(k.inputOrdinals().get(i)).name());
      }
      return List.of("\n; " + sql + " (" + String.join(", ", names) + "): "
          + VarkaVectorIR.canonical(root) + "\n", out.toString());
    });
  }

  /** {@code sql} resolved as {@code VarkaCoverageSuite} resolves it, or the InSet it stands for. */
  private static Expression expression(String sql) {
    var cols = CollectionConverters.asScala(COLUMNS).toSeq();
    if (!sql.equals(IN_SET)) {
      return VarkaSqlResolve.parse(sql, cols);
    }
    Set<Object> set = new LinkedHashSet<>();
    for (String v : List.of("0.42", "50000000.00", "-50000000.00", "1.01", "2.02", "3.03",
        "4.04", "5.05", "6.06", "7.07", "8.08")) {
      set.add(Decimal.apply(new BigDecimal(v)));
    }
    return new InSet(COLUMNS.get(2), CollectionConverters.asScala(set).toSet());
  }

  /** The row's one fused kernel, as {@code VarkaCoverageSuite} compiles a predicate row. */
  private static CompiledVarkaProjection kernelOf(String sql, Expression e) {
    CompiledVarkaPredicate p = VarkaExpressionCompiler.compilePredicate(e,
        CollectionConverters.asScala(COLUMNS).toSeq(), VarkaEmitOptions.DEFAULTS,
        VarkaAdmission.NO_HOST_REFUSAL).orElseThrow(() -> new Refused("it does not compile"));
    CompiledVarkaProjection k = p.fused();
    if (!p.specs().stream().allMatch(VarkaConjunctSpec::fused) || p.clauses().size() != 1
        || k.outputs().size() != 1) {
      throw new Refused("not one fused root");
    }
    if (!k.inputBounds().isEmpty() || !k.derivedInputs().isEmpty()) {
      throw new Refused("an input bound or a derived input, which the printer does not read");
    }
    return k;
  }

  /** Every column declared with its range, every quotient pinned. */
  private static List<String> setup(Map<String, VarkaSmtTerm> facts, Set<Integer> ordinals,
      List<VarkaSmtTerm> terms, List<VarkaSmtTerm.WordForm.Obligation> obligations) {
    var vars = new LinkedHashMap<String, Var>();
    var quots = new LinkedHashMap<String, Quot>();
    for (VarkaSmtTerm t : terms) {
      VarkaSmtTerm.collect(t, vars, quots);
    }
    for (var ob : obligations) {
      VarkaSmtTerm.collect(ob.claim(), vars, quots);
    }
    // The specification's columns, which a kernel that reads only validity may not name.
    var names = new LinkedHashMap<String, Boolean>();
    for (int c : ordinals) {
      names.put("c" + c, false);
      names.put("v" + c, true);
    }
    vars.forEach((n, v) -> names.put(n, v.sort() == VarkaSmtTerm.Sort.BOOL));
    var lines = new ArrayList<String>();
    for (var e : names.entrySet()) {
      String n = e.getKey();
      lines.add("(declare-const " + n + " " + (e.getValue() ? "Bool" : "Int") + ")");
      String fact = facts.containsKey(n) ? facts.get(n).render()
          : e.getValue() ? null : "(jlong.in " + n + ")";
      if (fact != null) {
        lines.add("(assert " + fact + ")");
      }
    }
    for (Quot q : quots.values()) {
      lines.add("(declare-const " + q.name() + " Int)");
      lines.add(VarkaSmtTerm.pin(q));
    }
    return lines;
  }

  // -------------------------------------------------------------------------------------------
  // The specification, read from Spark's analyzed expression.
  // -------------------------------------------------------------------------------------------

  /**
   * Spark's meaning of an analyzed decimal expression in {@code spec.dec.*} (STANDARD.md 4.1), one
   * rule per Catalyst node, nothing of Varka's read but the kernel's input order, which names the
   * columns as the printer does ({@code c<k>}, validity {@code v<k>}). A value is where it raises,
   * where it is valid, and its unscaled value at its type's scale, or a truth (precision -1). A
   * sum, difference or product is exact, a cast rescaled up or cut {@code HALF_UP}, each fitted to
   * its type: past it a raise under ANSI, else a null. A column raises on a valid slot past its
   * precision wherever it is read, since a filter's code reads every column it references first
   * ({@code FilterExec.genPredicate}); a later {@code coalesce} operand or an untaken arm raises
   * only where Spark evaluates it. Any other node, and a result cut below its exact scale, is
   * refused.
   */
  private static final class Spec {

    record Value(String raise, String valid, String value, int precision, int scale) { }

    final List<Integer> inputs;
    final Set<Integer> ordinals = new LinkedHashSet<>();
    final Set<String> reads = new LinkedHashSet<>();

    Spec(List<Integer> inputs) {
      this.inputs = inputs;
    }

    Value of(Expression e) {
      if (e instanceof RuntimeReplaceable r) {
        return of(r.replacement());
      }
      if (e instanceof AttributeReference a && a.dataType() instanceof DecimalType t) {
        int k = inputs.indexOf(COLUMNS.indexOf(a));
        if (k < 0) {
          throw new Refused("the column " + a.name() + " is no kernel input");
        }
        ordinals.add(k);
        reads.add("(and v" + k + " (not (spec.dec.fits c" + k + " " + t.precision() + ")))");
        return new Value("false", "v" + k, "c" + k, t.precision(), t.scale());
      }
      if (e instanceof Literal l && l.dataType() instanceof DecimalType t) {
        if (l.value() == null) {
          return new Value("false", "false", "0", t.precision(), t.scale());
        }
        return new Value("false", "true", VarkaProofFiles.lit(word(((Decimal) l.value())
            .toJavaBigDecimal().setScale(t.scale()).unscaledValue())),
            t.precision(), t.scale());
      }
      if (e instanceof Cast c && c.dataType() instanceof DecimalType t) {
        Value x = decimal(c.child());
        String u = t.scale() >= x.scale()
            ? "(spec.dec.rescale " + x.value() + " " + (t.scale() - x.scale()) + ")"
            : "(spec.dec.halfup " + x.value() + " " + (x.scale() - t.scale()) + ")";
        return fit(t, x.raise(), x.valid(), u, c.evalMode() == EvalMode.ANSI());
      }
      if (e instanceof BinaryArithmetic b && b.dataType() instanceof DecimalType t
          && !(b instanceof Divide) && !(b instanceof Remainder)) {
        Value l = decimal(b.left());
        Value r = decimal(b.right());
        boolean mul = b instanceof Multiply;
        int exact = mul ? l.scale() + r.scale() : Math.max(l.scale(), r.scale());
        if (t.scale() != exact || !(mul || b instanceof Add || b instanceof Subtract)) {
          throw new Refused(t.scale() != exact ? "a " + b.prettyName() + " at " + t.sql()
              + ", cut below its exact scale " + exact + " (506c's)" : "a " + b.prettyName());
        }
        String u = mul ? "(spec.dec.mul " + l.value() + " " + r.value() + ")"
            : "(spec.dec." + (b instanceof Add ? "add " : "sub ") + l.value() + " " + l.scale()
                + " " + r.value() + " " + r.scale() + ")";
        return fit(t, or(l.raise(), r.raise()), and(l.valid(), r.valid()), u,
            b.evalMode() == EvalMode.ANSI());
      }
      if (e instanceof UnaryMinus || e instanceof Abs) {
        Value x = decimal(e.children().head());
        return new Value(x.raise(), x.valid(), "(spec.dec." + (e instanceof Abs ? "abs " : "neg ")
            + x.value() + ")", x.precision(), x.scale());
      }
      if (e instanceof BinaryComparison b && !(b instanceof EqualNullSafe)) {
        String op = switch (b.symbol()) {
          case "<", "<=", ">", ">=", "=" -> b.symbol();
          default -> throw new Refused("the comparison " + b.symbol());
        };
        Value l = decimal(b.left());
        Value r = decimal(b.right());
        return cond(or(l.raise(), r.raise()), and(l.valid(), r.valid()),
            "(" + op + " " + cmp(l, r) + " 0)");
      }
      if (e instanceof EqualNullSafe b) {
        Value l = decimal(b.left());
        Value r = decimal(b.right());
        return cond(or(l.raise(), r.raise()), "true", "(or (and " + l.valid() + " " + r.valid()
            + " (= " + cmp(l, r) + " 0)) (and (not " + l.valid() + ") (not " + r.valid() + ")))");
      }
      if (e instanceof Not n) {
        Value x = condition(n.child());
        return cond(x.raise(), x.valid(), "(not " + x.value() + ")");
      }
      if (e instanceof IsNull || e instanceof IsNotNull) {
        Value x = of(e.children().head());
        return cond(x.raise(), "true", e instanceof IsNull ? "(not " + x.valid() + ")"
            : x.valid());
      }
      if (e instanceof Greatest || e instanceof Least) {
        List<Expression> kids = CollectionConverters.asJava(e.children());
        Value acc = decimal(kids.get(0));
        String keeps = e instanceof Greatest ? ">=" : "<=";
        for (Expression kid : kids.subList(1, kids.size())) {
          Value x = decimal(kid);
          String both = and(acc.valid(), x.valid());
          acc = new Value(or(acc.raise(), x.raise()), "(or " + acc.valid() + " " + x.valid()
              + ")", "(ite " + both + " (ite (" + keeps + " " + cmp(acc, x) + " 0) " + acc.value()
              + " " + x.value() + ") (ite " + acc.valid() + " " + acc.value() + " " + x.value()
              + "))", acc.precision(), acc.scale());
          same(acc, x);
        }
        return acc;
      }
      // Spark evaluates a coalesce's operands in turn up to the first valid one, and only the
      // CASE arm a row takes: a later operand or an untaken arm raises nowhere.
      if (e instanceof Coalesce c) {
        List<Expression> kids = CollectionConverters.asJava(c.children());
        Value acc = decimal(kids.get(kids.size() - 1));
        for (int i = kids.size() - 2; i >= 0; i--) {
          Value x = decimal(kids.get(i));
          same(acc, x);
          acc = new Value(or(x.raise(), and("(not " + x.valid() + ")", acc.raise())),
              "(or " + x.valid() + " " + acc.valid() + ")", "(ite " + x.valid() + " "
              + x.value() + " " + acc.value() + ")", x.precision(), x.scale());
        }
        return acc;
      }
      if (e instanceof CaseWhen w && w.branches().size() == 1 && w.elseValue().isDefined()) {
        return choose(w.branches().head()._1(), w.branches().head()._2(), w.elseValue().get());
      }
      if (e instanceof If i) {
        return choose(i.predicate(), i.trueValue(), i.falseValue());
      }
      if (e instanceof In in) {
        Value x = decimal(in.value());
        var hits = new ArrayList<String>();
        // Spark reads no item of a null value, and none past the first match.
        String raise = "false";
        String known = "true";
        String none = "true";
        for (Expression item : CollectionConverters.asJava(in.list())) {
          Value y = decimal(item);
          raise = or(raise, and(none, y.raise()));
          known = and(known, y.valid());
          hits.add("(and " + y.valid() + " (= " + cmp(x, y) + " 0))");
          none = and(none, "(not " + hits.getLast() + ")");
        }
        String hit = hits.size() == 1 ? hits.get(0) : "(or " + String.join(" ", hits) + ")";
        return cond(or(x.raise(), and(x.valid(), raise)), "(and " + x.valid() + " (or " + hit
            + " " + known + "))", hit);
      }
      if (e instanceof InSet in) {
        Value x = decimal(in.child());
        var hits = new ArrayList<String>();
        for (Object v : CollectionConverters.asJava(in.hset())) {
          if (v == null) {
            throw new Refused("an InSet holding a null");
          }
          BigDecimal d = ((Decimal) v).toJavaBigDecimal();
          var y = new Value("false", "true", VarkaProofFiles.lit(word(d.unscaledValue())),
              d.precision(), d.scale());
          hits.add("(= " + cmp(x, y) + " 0)");
        }
        return cond(x.raise(), x.valid(), "(or " + String.join(" ", hits.stream().sorted()
            .toList()) + ")");
      }
      throw new Refused("no specification rule for " + e.prettyName());
    }

    private Value choose(Expression when, Expression then, Expression otherwise) {
      Value c = condition(when);
      Value t = decimal(then);
      Value o = decimal(otherwise);
      same(t, o);
      String takes = "(and " + c.valid() + " " + c.value() + ")";
      return new Value(or(c.raise(), or(and(takes, t.raise()), and("(not " + takes + ")",
          o.raise()))), "(ite " + takes + " " + t.valid() + " " + o.valid() + ")",
          "(ite " + takes + " " + t.value() + " " + o.value() + ")", t.precision(), t.scale());
    }

    private static void same(Value a, Value b) {
      if (a.precision() != b.precision() || a.scale() != b.scale()) {
        throw new Refused("operands of two types where Spark's coercion gives one");
      }
    }

    private Value decimal(Expression e) {
      return typed(e, true);
    }

    private Value condition(Expression e) {
      return typed(e, false);
    }

    private Value typed(Expression e, boolean isDecimal) {
      Value v = of(e);
      if ((v.precision() >= 0) != isDecimal) {
        throw new Refused("a " + (isDecimal ? "condition where a decimal" : "decimal where a "
            + "condition") + " is read");
      }
      return v;
    }

    private static Value cond(String raise, String valid, String truth) {
      return new Value(raise, valid, truth, -1, -1);
    }

    private static String cmp(Value l, Value r) {
      return "(spec.dec.cmp " + l.value() + " " + l.scale() + " " + r.value() + " " + r.scale()
          + ")";
    }

    /** {@code u} fitted to {@code t}: past its precision a raise under ANSI, else a null. */
    private static Value fit(DecimalType t, String raise, String valid, String u, boolean ansi) {
      String fits = "(spec.dec.fits " + u + " " + t.precision() + ")";
      return ansi
          ? new Value(or(raise, "(and " + valid + " (not " + fits + "))"), valid, u,
              t.precision(), t.scale())
          : new Value(raise, and(valid, fits), u, t.precision(), t.scale());
    }

    static String or(String a, String b) {
      return a.equals("false") ? b : b.equals("false") ? a : "(or " + a + " " + b + ")";
    }

    static String and(String a, String b) {
      if (a.equals("false") || b.equals("false")) {
        return "false";
      }
      return a.equals("true") ? b : b.equals("true") ? a : "(and " + a + " " + b + ")";
    }

    static String any(Set<String> terms) {
      return terms.isEmpty() ? "false" : terms.size() == 1 ? terms.iterator().next()
          : "(or " + String.join(" ", terms) + ")";
    }
  }
}
