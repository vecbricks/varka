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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.*;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.YearMonthIntervalType$;

/**
 * A census of validity words (VARKA-70's algebra): for every value root of a corpus, what its
 * word is - one input's bitmap, the constant, a single-operator chain, a tree that mixes the
 * operators, or nothing pure at all - and what two extensions of the axioms would change:
 * the coalesce axiom (an {@code IfElse(IsNotNull(x), x, y)} denotes {@code x OR y}) and the
 * absorption laws ({@code x AND (x OR y) = x}, {@code x OR (x AND y) = x}). The admission check of
 * the milestone 5 follow-ups to VARKA-70, run from {@code dev/varka_word_census.sh}.
 *
 * <p>Three corpora: the {@code Surface} inventory's projections (the shapes the surface run
 * covers), a list of composites chosen to exercise the operator boundary, and {@code --fuzz N}
 * shapes from a copy of {@code VarkaIrFuzzSuite}'s value grammar. Every single-root shape is also
 * emitted under {@code validityByBitmap} on and off, and the mirror's verdict is checked against
 * the emitter's: a served root changes {@code loopMasked0}'s bytes, a declined one does not. The
 * mirror of the analysis below exists because the emitter's is private; it is the one thing here
 * that can drift, and the cross-check is what would show it. VARKA-74 replaces the mirror with the
 * analysis itself.
 *
 * <p>It prints what the Scala form printed byte for byte (VARKA-321): the records' text forms
 * are the Scala case classes' ({@code Chain(true,2)}, a pair as {@code (1,500)}).
 */
public final class VarkaWordCensus {

  private VarkaWordCensus() {
  }

  // --- the word algebra, mirrored, with the two extensions switchable ---------------------

  /** A pure validity word: an input's bitmap, the constant, or an AND or OR of two words. */
  sealed interface W permits In, T, A, O { }

  record In(int i) implements W {
    @Override public String toString() {
      return "In(" + i + ")";
    }
  }

  record T() implements W {
    @Override public String toString() {
      return "T";
    }
  }

  record A(W a, W b) implements W {
    @Override public String toString() {
      return "A(" + a + "," + b + ")";
    }
  }

  record O(W a, W b) implements W {
    @Override public String toString() {
      return "O(" + a + "," + b + ")";
    }
  }

  private static final T CONSTANT = new T();

  private static W andW(W a, W b, boolean absorb) {
    if (a == null || b == null) {
      return null;
    }
    if (a instanceof T) {
      return b;
    }
    if (b instanceof T) {
      return a;
    }
    if (a.equals(b)) {
      return a;
    }
    if (absorb && b instanceof O(W p, W q) && (p.equals(a) || q.equals(a))) {
      return a;
    }
    if (absorb && a instanceof O(W p, W q) && (p.equals(b) || q.equals(b))) {
      return b;
    }
    return new A(a, b);
  }

  private static W orW(W a, W b, boolean absorb) {
    if (a == null || b == null) {
      return null;
    }
    if (a instanceof T || b instanceof T) {
      return CONSTANT;
    }
    if (a.equals(b)) {
      return a;
    }
    if (absorb && b instanceof A(W p, W q) && (p.equals(a) || q.equals(a))) {
      return a;
    }
    if (absorb && a instanceof A(W p, W q) && (p.equals(b) || q.equals(b))) {
      return b;
    }
    return new O(a, b);
  }

  /** The pure word of {@code n}, or null; {@code coalesce} and {@code absorb} switch the two
   *  extensions on. */
  static W pure(VarkaVectorIR n, boolean coalesce, boolean absorb) {
    return switch (n) {
      case ColumnRef c -> new In(c.ordinal());
      case DecimalColumn c -> new In(c.ordinal());
      case LiteralSlot l -> CONSTANT;
      case AddDays x -> andW(pure(x.days(), coalesce, absorb), pure(x.offset(), coalesce, absorb),
          absorb);
      case SubDays x -> andW(pure(x.days(), coalesce, absorb), pure(x.offset(), coalesce, absorb),
          absorb);
      case NextDay x -> andW(pure(x.days(), coalesce, absorb), pure(x.offset(), coalesce, absorb),
          absorb);
      case TruncDateDynamic x -> andW(pure(x.days(), coalesce, absorb),
          pure(x.level(), coalesce, absorb), absorb);
      case AddMonths x -> andW(pure(x.days(), coalesce, absorb),
          pure(x.months(), coalesce, absorb), absorb);
      case DateDiff x -> andW(pure(x.end(), coalesce, absorb), pure(x.start(), coalesce, absorb),
          absorb);
      case BitOp x -> andW(pure(x.left(), coalesce, absorb), pure(x.right(), coalesce, absorb),
          absorb);
      case BitCount x -> pure(x.child(), coalesce, absorb);
      case Greatest x -> orW(pure(x.left(), coalesce, absorb), pure(x.right(), coalesce, absorb),
          absorb);
      case Least x -> orW(pure(x.left(), coalesce, absorb), pure(x.right(), coalesce, absorb),
          absorb);
      case DayOfWeek x -> pure(x.days(), coalesce, absorb);
      case WeekDay x -> pure(x.days(), coalesce, absorb);
      case DayOfWeekIso x -> pure(x.days(), coalesce, absorb);
      case ThursdayOf x -> pure(x.days(), coalesce, absorb);
      case Year x -> pure(x.days(), coalesce, absorb);
      case Month x -> pure(x.days(), coalesce, absorb);
      case DayOfMonth x -> pure(x.days(), coalesce, absorb);
      case Quarter x -> pure(x.days(), coalesce, absorb);
      case DayOfYear x -> pure(x.days(), coalesce, absorb);
      case LastDay x -> pure(x.days(), coalesce, absorb);
      case TruncDate x -> pure(x.days(), coalesce, absorb);
      case WeekOfYear x -> pure(x.days(), coalesce, absorb);
      case IfElse x -> coalesce && x.cond() instanceof IsNotNull nn
          && nn.child().equals(x.thenNode())
          ? orW(pure(x.thenNode(), coalesce, absorb), pure(x.elseNode(), coalesce, absorb), absorb)
          : null;
      case MakeDate _, IntArith _, IntNeg _, ConstDivide _, BoundedDivide _, Cond _,
          CondValue _, BoolOutput _, GuardedDay _, GuardedRange _, WidenLane _,
          NarrowLane _ -> null;
    };
  }

  /** What a root's word is, as the census counts it. */
  sealed interface Kind permits NoExpr, Const, Leaf, Chain, Mixed { }

  record NoExpr() implements Kind {
    @Override public String toString() {
      return "NoExpr";
    }
  }

  record Const() implements Kind {
    @Override public String toString() {
      return "Const";
    }
  }

  record Leaf() implements Kind {
    @Override public String toString() {
      return "Leaf";
    }
  }

  record Chain(boolean and, int leaves) implements Kind {
    @Override public String toString() {
      return "Chain(" + and + "," + leaves + ")";
    }
  }

  record Mixed() implements Kind {
    @Override public String toString() {
      return "Mixed";
    }
  }

  /** The inputs {@code w} reads; none for no word (null), as the Scala form's catch-all gave. */
  static Set<Integer> leaves(W w) {
    var out = new LinkedHashSet<Integer>();
    if (w != null) {
      collectLeaves(w, out);
    }
    return out;
  }

  private static void collectLeaves(W w, Set<Integer> out) {
    switch (w) {
      case In(int i) -> out.add(i);
      case A(W a, W b) -> {
        collectLeaves(a, out);
        collectLeaves(b, out);
      }
      case O(W a, W b) -> {
        collectLeaves(a, out);
        collectLeaves(b, out);
      }
      case T t -> { }
    }
  }

  /** The operators of {@code w}: true for AND, false for OR. */
  private static Set<Boolean> ops(W w) {
    var out = new LinkedHashSet<Boolean>();
    collectOps(w, out);
    return out;
  }

  private static void collectOps(W w, Set<Boolean> out) {
    switch (w) {
      case A(W a, W b) -> {
        collectOps(a, out);
        collectOps(b, out);
        out.add(true);
      }
      case O(W a, W b) -> {
        collectOps(a, out);
        collectOps(b, out);
        out.add(false);
      }
      case In i -> { }
      case T t -> { }
    }
  }

  /** The tree's Strahler number: the registers a lockstep evaluation needs (Ershov). */
  static int strahler(W w) {
    return switch (w) {
      case A(W a, W b) -> comb(strahler(a), strahler(b));
      case O(W a, W b) -> comb(strahler(a), strahler(b));
      case In i -> 1;
      case T t -> 1;
    };
  }

  private static int comb(int x, int y) {
    return x == y ? x + 1 : Math.max(x, y);
  }

  static Kind kind(W w) {
    if (w == null) {
      return new NoExpr();
    }
    return switch (w) {
      case T t -> new Const();
      case In i -> new Leaf();
      case A a -> chainOrMixed(w);
      case O o -> chainOrMixed(w);
    };
  }

  private static Kind chainOrMixed(W w) {
    var o = ops(w);
    return o.size() == 1 ? new Chain(o.iterator().next(), leaves(w).size()) : new Mixed();
  }

  /** Whether today's emitter serves a root of this kind (VARKA-70's rule). */
  private static boolean served(Kind k) {
    return switch (k) {
      case NoExpr n -> false;
      case Mixed m -> false;
      case Const c -> true;
      case Leaf l -> true;
      case Chain c -> true;
    };
  }

  // --- SQL to IR, as VarkaEmitDump does ------------------------------------------------

  // The interval columns are here so the corpus below can carry the date/interval shapes task
  // 67 added. They were absent while `resolve` had no analyzer pass, because without coercion
  // `d + ymm` declined and there was nothing to census.
  private static final Seq<Attribute> COLUMNS = columns();

  private static Seq<Attribute> columns() {
    byte month = YearMonthIntervalType$.MODULE$.MONTH();
    byte year = YearMonthIntervalType$.MODULE$.YEAR();
    var out = new ArrayList<Attribute>();
    for (String c : List.of("d:date", "d2:date", "d3:date", "d4:date", "i:int", "j:int",
        "ymm:ymm", "ymy:ymy", "ym:ym")) {
      String[] parts = c.split(":");
      DataType dt = switch (parts[1]) {
        case "date" -> DataTypes.DateType;
        case "ymm" -> DataTypes.createYearMonthIntervalType(month, month);
        case "ymy" -> DataTypes.createYearMonthIntervalType(year, year);
        case "ym" -> DataTypes.createYearMonthIntervalType();
        default -> DataTypes.IntegerType;
      };
      out.add(VarkaSqlResolve.column(parts[0], dt));
    }
    return CollectionConverters.asScala(out).toSeq();
  }

  private record Fused(List<VarkaVectorIR> roots, int numInputs, int numLiterals) { }

  /** The fused roots of a projection given as SQL, or empty if nothing fused. */
  private static Optional<Fused> fuse(String expr) {
    NamedExpression named = VarkaSqlResolve.named(VarkaSqlResolve.parse(expr, COLUMNS), "c");
    return VarkaExpressionCompiler.compilePartial(
        CollectionConverters.asScala(List.of(named)).toSeq(), COLUMNS)
        .map(p -> new Fused(p.fused().outputs(), p.fused().inputOrdinals().size(),
            p.fused().literals().size()));
  }

  /**
   * The {@code Surface} projections, minus its date/interval shapes - <b>not yet</b> the whole
   * surface. The resolver bug that once kept them out is fixed and the interval columns are
   * declared above, but adding them moves every number the census publishes, and
   * {@code m5/PLAN.md} 2.x quotes a 7 September run of it; re-running and requoting belongs to the
   * task that owns the census (74/75). Until then this list is about two thirds of the surface.
   */
  private static final List<String> SURFACE = List.of(
      "date_add(d, 3)", "date_add(d, i)", "date_sub(d, 5)", "datediff(d2, d)", "unix_date(d)",
      "date_from_unix_date(unix_date(d))", "year(d)", "month(d)", "day(d)", "quarter(d)",
      "dayofyear(d)", "dayofweek(d)", "weekday(d)", "last_day(d)", "next_day(d, 'MONDAY')",
      "weekofyear(d)", "extract(DAYOFWEEK_ISO FROM d)", "extract(YEAROFWEEK FROM d)",
      "add_months(d, 3)", "add_months(d, i)", "d + INTERVAL 3 MONTH", "trunc(d, 'YEAR')",
      "trunc(d, 'MONTH')", "trunc(d, 'QUARTER')", "trunc(d, 'WEEK')", "if(d < d2, d, d2)",
      "CASE WHEN d < d2 THEN d ELSE d2 END", "coalesce(d, d2)", "greatest(d, d2)",
      "least(d, d2)", "year(date_add(d, 30))");

  /** Shapes chosen to sit on the operator boundary. */
  private static final List<String> COMPOSITES = List.of(
      "datediff(greatest(d, d2), d)",
      "datediff(greatest(d, d2), greatest(d3, d4))",
      "datediff(greatest(d, d2), least(d, d2))",
      "year(greatest(date_add(d, i), d2))",
      "date_add(greatest(d, d2), i)",
      "greatest(date_add(d, i), date_sub(d2, i))",
      "greatest(date_add(d, i), d)",
      "coalesce(d, d2, d3)",
      "year(coalesce(d, d2))",
      "datediff(coalesce(d, d2), d)",
      "date_add(coalesce(d, d2), i)",
      "greatest(coalesce(d, d2), d3)",
      "add_months(greatest(d, d2), i)",
      "datediff(date_add(d, i), date_add(d2, j))",
      "datediff(greatest(d, d2), date_add(d, i))",
      "next_day(greatest(d, d2), 'MON')");

  // --- the emitter's verdict, read off the bytes ----------------------------------------

  private static final VarkaEmitOptions OFF = VarkaEmitOptions.DEFAULTS.withValidityByBitmap(false);

  private static int maskedSize(Fused f, VarkaEmitOptions o) {
    return VarkaEmitterTestSupport.codeSize(
        VarkaLoopEmitter.emit("org.apache.spark.sql.varka.execution.VarkaFusedCensus", f.roots(),
            f.numInputs(), f.numLiterals(), null, null, o), "loopMasked0");
  }

  /** Whether the emitter serves a single root: the pass changes the masked loop's bytes. */
  private static Optional<Boolean> emitterServes(Fused f) {
    try {
      return Optional.of(maskedSize(f, VarkaEmitOptions.DEFAULTS) != maskedSize(f, OFF));
    } catch (IllegalArgumentException | IllegalStateException e) {
      return Optional.empty();
    }
  }

  // --- the fuzzer's value grammar, copied (VarkaIrFuzzSuite's Shapes is private) ----------

  /** The grammar, drawing from {@code rnd} in the Scala form's order, call for call. */
  private static final class Shapes {
    private final Random rnd;
    private final int numInputs;
    private final int numLiterals;
    private int budget = 12;

    Shapes(Random rnd, int numInputs, int numLiterals) {
      this.rnd = rnd;
      this.numInputs = numInputs;
      this.numLiterals = numLiterals;
    }

    private VarkaVectorIR leaf() {
      return new ColumnRef(rnd.nextInt(numInputs));
    }

    private VarkaVectorIR literal() {
      return numLiterals > 0 ? new LiteralSlot(rnd.nextInt(numLiterals)) : leaf();
    }

    VarkaVectorIR value(int depth) {
      if (depth == 0 || budget <= 1) {
        return leaf();
      }
      budget--;
      int pick = rnd.nextInt(16);
      return switch (pick) {
        case 0 -> new AddDays(value(depth - 1), literal());
        case 1 -> new SubDays(value(depth - 1), literal());
        case 2 -> new DateDiff(value(depth - 1), value(depth - 1));
        case 3 -> new Greatest(value(depth - 1), value(depth - 1));
        case 4 -> new Least(value(depth - 1), value(depth - 1));
        case 5 -> new IfElse(cond(depth - 1), value(depth - 1), value(depth - 1));
        case 6 -> new DayOfWeek(value(depth - 1));
        case 7 -> new WeekDay(value(depth - 1));
        case 8 -> {
          VarkaVectorIR days = value(depth - 1);
          yield new NextDay(days, rnd.nextBoolean() ? literal() : leaf());
        }
        case 9 -> new Year(value(depth - 1));
        case 10 -> new Month(value(depth - 1));
        case 11 -> new DayOfMonth(value(depth - 1));
        case 12 -> new Quarter(value(depth - 1));
        case 13 -> new DayOfYear(value(depth - 1));
        case 14 -> {
          VarkaVectorIR days = value(depth - 1);
          yield new AddMonths(days, rnd.nextBoolean() ? literal() : leaf());
        }
        default -> new LastDay(value(depth - 1));
      };
    }

    Cond cond(int depth) {
      if (depth == 0 || budget <= 1) {
        return new IsNotNull(leaf());
      }
      budget--;
      return switch (rnd.nextInt(5)) {
        case 0 -> new And(cond(depth - 1), cond(depth - 1));
        case 1 -> new Or(cond(depth - 1), cond(depth - 1));
        case 2 -> new Not(cond(depth - 1));
        case 3 -> new IsNotNull(value(depth - 1));
        default -> new Compare(CompareOp.LT, value(depth - 1), value(depth - 1));
      };
    }
  }

  // --- the census -----------------------------------------------------------------------

  private static void report(String s) {
    System.out.println(s);
  }

  private static String sortedLeaves(W w) {
    var sorted = new TreeSet<>(leaves(w));
    var parts = new ArrayList<String>();
    for (int i : sorted) {
      parts.add(Integer.toString(i));
    }
    return String.join(",", parts);
  }

  private static void row(String label, Fused f) {
    W base = pure(f.roots().get(0), false, false);
    W ext = pure(f.roots().get(0), true, true);
    String check;
    if (f.roots().size() != 1) {
      check = "-";
    } else {
      var s = emitterServes(f);
      check = s.isEmpty() ? "not emitted"
          : s.get() == served(kind(base)) ? "agrees" : "DISAGREES";
    }
    report(String.format("%-46s %-16s %-16s ", label, kind(base), kind(ext))
        + String.format("%d  %-8s", ext == null ? 0 : strahler(ext), sortedLeaves(ext))
        + "  " + check);
  }

  /** A served root's form: its one operator, if any, and its leaves. */
  private record Form(Optional<Boolean> op, Set<Integer> leaves) { }

  public static void main(String[] args) {
    int fuzz = 20000;
    long seed = 7L;
    int i = 0;
    while (i < args.length) {
      switch (args[i]) {
        case "--fuzz" -> {
          fuzz = Integer.parseInt(args[i + 1]);
          i += 2;
        }
        case "--seed" -> {
          seed = Long.parseLong(args[i + 1]);
          i += 2;
        }
        default -> throw new IllegalArgumentException("unknown argument " + args[i]);
      }
    }
    report(String.format("%-46s %-16s %-16s strahler leaves    emitter", "shape", "today",
        "extended"));
    report("---- Surface projections ----");
    for (String e : SURFACE) {
      fuse(e).ifPresent(f -> row(e, f));
    }
    report("---- composites ----");
    for (String e : COMPOSITES) {
      fuse(e).ifPresent(f -> row(e, f));
    }

    var rnd = new Random(seed);
    // The kinds in first-seen order; the report sorts them by count.
    Map<String, Integer> kinds = new LinkedHashMap<>();
    Map<Integer, Integer> strahlers = new TreeMap<>();
    int roots = 0;
    int mixed = 0;
    int mixedRescued = 0;
    int none = 0;
    int noneRescued = 0;
    int multiShapes = 0;
    int multiServed = 0;
    int multiDistinct = 0;
    int repeatedNonLeaf = 0;
    int subsetPairs = 0;
    int checked = 0;
    int disagreements = 0;
    for (int s = 0; s < fuzz; s++) {
      int numInputs = 1 + rnd.nextInt(3);
      int numLiterals = rnd.nextInt(3);
      var shapes = new Shapes(rnd, numInputs, numLiterals);
      int depth = 1 + rnd.nextInt(4);
      int count = 1 + rnd.nextInt(3);
      var drawn = new LinkedHashSet<VarkaVectorIR>();
      for (int k = 0; k < count; k++) {
        drawn.add(shapes.value(depth));
      }
      var rs = new ArrayList<>(drawn);
      roots += rs.size();
      var forms = new ArrayList<Optional<Form>>();
      for (var r : rs) {
        W base = pure(r, false, false);
        W abs = pure(r, false, true);
        W ext = pure(r, true, true);
        kinds.merge(kind(base).toString().replaceAll("\\(.*", ""), 1, Integer::sum);
        Kind k = kind(base);
        if (k instanceof Mixed) {
          mixed++;
          if (!(kind(abs) instanceof Mixed)) {
            mixedRescued++;
          }
        } else if (k instanceof NoExpr) {
          none++;
          if (ext != null) {
            noneRescued++;
          }
        }
        if (ext != null) {
          strahlers.merge(strahler(ext), 1, Integer::sum);
        }
        if (ext != null && !(kind(ext) instanceof Mixed)) {
          var o = ops(ext);
          forms.add(Optional.of(new Form(o.isEmpty() ? Optional.empty()
              : Optional.of(o.iterator().next()), leaves(ext))));
        } else {
          forms.add(Optional.empty());
        }
      }
      if (rs.size() == 1) {
        var served = emitterServes(new Fused(rs, numInputs, numLiterals));
        if (served.isPresent()) {
          checked++;
          if (served.get() != served(kind(pure(rs.get(0), false, false)))) {
            disagreements++;
            report("DISAGREES: " + VarkaVectorIR.canonical(rs.get(0)));
          }
        }
      } else {
        multiShapes++;
        var servedForms = new ArrayList<Form>();
        for (var f : forms) {
          f.ifPresent(servedForms::add);
        }
        multiServed += servedForms.size();
        var distinct = new ArrayList<>(new LinkedHashSet<>(servedForms));
        multiDistinct += distinct.size();
        Map<Form, Integer> groups = new HashMap<>();
        for (var f : servedForms) {
          groups.merge(f, 1, Integer::sum);
        }
        for (var g : groups.entrySet()) {
          if (g.getValue() > 1 && g.getKey().leaves().size() > 1) {
            repeatedNonLeaf++;
          }
        }
        for (var a : distinct) {
          for (var b : distinct) {
            if (!a.equals(b) && a.op().equals(b.op()) && a.op().isPresent()
                && b.leaves().containsAll(a.leaves())) {
              subsetPairs++;
            }
          }
        }
      }
    }
    report("---- fuzzer grammar: " + fuzz + " shapes, seed " + seed + " ----");
    var byCount = new ArrayList<>(kinds.entrySet());
    byCount.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
    report("roots=" + roots + " kinds=" + pairs(byCount));
    report("mixed today=" + mixed + ", of which absorption makes a chain or a leaf: "
        + mixedRescued);
    report("no expression today=" + none + ", of which the coalesce axiom gives one: "
        + noneRescued);
    report("strahler numbers of the extended pure words: "
        + pairs(new ArrayList<>(strahlers.entrySet())));
    report("multi-root shapes=" + multiShapes + " servedRoots=" + multiServed + " distinctForms="
        + multiDistinct + " repeatedNonLeafForms=" + repeatedNonLeaf + " subsetPairsSameOp="
        + subsetPairs);
    report("emitter cross-check on single-root shapes: " + checked + " checked, "
        + disagreements + " disagreements");
  }

  /** Entries as Scala prints a sequence of pairs: {@code (a,1), (b,2)}. */
  private static String pairs(List<? extends Map.Entry<?, ?>> entries) {
    var parts = new ArrayList<String>();
    for (var e : entries) {
      parts.add("(" + e.getKey() + "," + e.getValue() + ")");
    }
    return String.join(", ", parts);
  }
}
