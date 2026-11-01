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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Random nested SQL expressions over the Spark-level fuzzer's view (VARKA-298), the Catalyst-side
 * twin of {@link VarkaIrGrammar}: where that grammar draws Varka IR for the emitter, this one draws
 * SQL text for the two Spark sessions of {@code VarkaSparkFuzzSuite}, which the analyzer resolves
 * into Catalyst trees.
 *
 * <p>The coverage table's rows are fixed expressions over columns. This grammar composes the same
 * families over typed operands - {@code date_add(<date>, <int>)}, {@code <ym> + <ym>},
 * {@code greatest(<bigint>, <int>)} - to a bounded depth, and it puts operands of
 * <i>neighbouring</i> types side by side on purpose: an INT beside a BIGINT, a TINYINT or SMALLINT
 * literal where an INT goes, the three year-month units ({@code INTERVAL MONTH}, {@code YEAR},
 * {@code YEAR TO MONTH}) and the day-time fields ({@code DAY}, {@code HOUR}, {@code HOUR TO
 * SECOND}, {@code DAY TO SECOND}) against each other, a DATE compared with a date string. The text
 * writes none of the casts those meetings need; the analyzer inserts them, so the trees Varka's
 * compiler receives carry {@code Cast} nodes the coverage rows mostly do not.
 *
 * <p>Every tree is well typed under ANSI on and off by construction: a position takes exactly the
 * type its production names, and a neighbour is only placed where both modes' type coercion widens
 * it ({@code VARKA-298.md} 2 records the pairs checked). A position whose argument must be a
 * literal of a form - a {@code trunc} format, a {@code time_trunc} unit - is part of the
 * production's template, not a child, so no shrink or draw can put an expression there.
 *
 * <p>The column names and types are the view's ({@code VarkaSparkFuzz.fixtureSql}): {@code d},
 * {@code d2} DATE; {@code i} INT; {@code l}, {@code l2} BIGINT; {@code t}, {@code t2} TIME(6);
 * {@code dt}, {@code dt2} INTERVAL DAY TO SECOND; {@code ymm}, {@code ymy}, {@code ym} the three
 * year-month units; {@code ntz}, {@code ntz2} TIMESTAMP_NTZ.
 */
public final class VarkaCatalystGrammar {

  private VarkaCatalystGrammar() {
  }

  /** A position's type, as far as the grammar distinguishes them. */
  public enum Type {
    DATE, INT, BIGINT, TIME,
    /** A day-time interval of any fields. */
    DAY_TIME,
    /** A year-month interval of any unit. */
    YEAR_MONTH,
    /** A TIMESTAMP_NTZ. */
    NTZ,
    BOOLEAN
  }

  /**
   * A drawn expression: its type, its template with {@code $0}, {@code $1}, {@code $2} where its
   * children's text goes, and the children. A leaf has no children and its template is its text.
   */
  public record Node(Type type, String template, List<Node> children) {
    public Node {
      children = List.copyOf(children);
      for (int k = 0; k < 3; k++) {
        if (template.contains("$" + k) != (k < children.size())) {
          throw new IllegalArgumentException(
              "template " + template + " does not take " + children.size() + " children");
        }
      }
    }

    public boolean isLeaf() {
      return children.isEmpty();
    }

    /** The SQL text. */
    public String sql() {
      String out = template;
      for (int k = 0; k < children.size(); k++) {
        out = out.replace("$" + k, children.get(k).sql());
      }
      return out;
    }

    public Node withChildren(List<Node> updated) {
      return new Node(type, template, updated);
    }

    @Override
    public String toString() {
      return sql();
    }
  }

  /** A production: the type it yields, its template and the types of its children. */
  private record Production(Type type, String template, List<Type> operands, int weight) {
    Production {
      operands = List.copyOf(operands);
    }
  }

  private static final Map<Type, List<String>> COLUMNS = new EnumMap<>(Map.of(
      Type.DATE, List.of("d", "d2"),
      Type.INT, List.of("i"),
      Type.BIGINT, List.of("l", "l2"),
      Type.TIME, List.of("t", "t2"),
      Type.DAY_TIME, List.of("dt", "dt2"),
      Type.YEAR_MONTH, List.of("ym", "ymm", "ymy"),
      Type.NTZ, List.of("ntz", "ntz2"),
      Type.BOOLEAN, List.of()));

  /**
   * The literals of each type. The INT ones include a TINYINT ({@code 3Y}) and a SMALLINT
   * ({@code 2S}) that the analyzer widens, and the interval ones a spread of units and fields;
   * a negative literal is parenthesised, since {@code - -1} without its space is a comment.
   */
  private static final Map<Type, List<String>> LITERALS = new EnumMap<>(Map.of(
      Type.DATE, List.of("DATE'2021-06-01'", "DATE'2000-02-29'"),
      Type.INT, List.of("3", "12", "(-1)", "3Y", "2S"),
      Type.BIGINT, List.of("5000000000", "(-42L)"),
      Type.TIME, List.of("TIME'09:30'", "TIME'12:34:56.789'"),
      Type.DAY_TIME, List.of("INTERVAL '3' DAY", "INTERVAL '2' HOUR", "INTERVAL '90' MINUTE",
          "INTERVAL '1:30' HOUR TO MINUTE", "INTERVAL '1 02:03:04.5' DAY TO SECOND"),
      Type.YEAR_MONTH, List.of("INTERVAL '3' MONTH", "INTERVAL '1' YEAR",
          "INTERVAL '1-2' YEAR TO MONTH"),
      Type.NTZ, List.of("TIMESTAMP_NTZ'2021-06-01 12:00:00'",
          "TIMESTAMP_NTZ'1969-12-31 18:00:00.000001'"),
      Type.BOOLEAN, List.of()));

  /** The non-boolean types, which an output and a comparison's operands draw from. */
  public static final List<Type> VALUE_TYPES = List.of(Type.DATE, Type.INT, Type.BIGINT,
      Type.TIME, Type.DAY_TIME, Type.YEAR_MONTH, Type.NTZ);

  private static final List<Production> PRODUCTIONS = productions();

  /**
   * The boolean productions a forced leaf may take: comparisons, null tests and {@code IN}, whose
   * children are drawn as leaves. A boolean has no column of its own to stop at.
   */
  private static boolean isAtom(Production p) {
    return p.type() == Type.BOOLEAN && !p.operands().contains(Type.BOOLEAN);
  }

  private static List<Production> productions() {
    var out = new ArrayList<Production>();
    final Type d = Type.DATE;
    final Type i = Type.INT;
    final Type l = Type.BIGINT;
    final Type t = Type.TIME;
    final Type x = Type.DAY_TIME;
    final Type y = Type.YEAR_MONTH;
    final Type n = Type.NTZ;
    final Type b = Type.BOOLEAN;
    // INT: the calendar fields, the day counts, the time fields, the year-month extracts, and
    // int arithmetic and choice.
    for (String f : List.of("year", "month", "day", "quarter", "dayofyear", "dayofweek",
        "weekday", "weekofyear")) {
      out.add(new Production(i, f + "($0)", List.of(d), 1));
    }
    out.add(new Production(i, "extract(YEAROFWEEK FROM $0)", List.of(d), 1));
    out.add(new Production(i, "extract(DAYOFWEEK_ISO FROM $0)", List.of(d), 1));
    out.add(new Production(i, "datediff($0, $1)", List.of(d, d), 2));
    out.add(new Production(i, "unix_date($0)", List.of(d), 1));
    out.add(new Production(i, "hour($0)", List.of(t), 1));
    out.add(new Production(i, "minute($0)", List.of(t), 1));
    out.add(new Production(i, "second($0)", List.of(t), 1));
    // EXTRACT takes only a field its interval has (no YEAR of an INTERVAL MONTH), so the operand is
    // widened to YEAR TO MONTH first, by an addition whose cast the analyzer writes.
    out.add(new Production(i, "extract(YEAR FROM ($0 + INTERVAL '0-0' YEAR TO MONTH))",
        List.of(y), 1));
    out.add(new Production(i, "extract(MONTH FROM ($0 + INTERVAL '0-0' YEAR TO MONTH))",
        List.of(y), 1));
    out.add(new Production(i, "($0 + $1)", List.of(i, i), 2));
    out.add(new Production(i, "($0 - $1)", List.of(i, i), 2));
    out.add(new Production(i, "($0 * 2)", List.of(i), 1));
    out.add(new Production(i, "(- $0)", List.of(i), 1));
    choices(out, i, i, 1);
    // BIGINT: an INT beside a BIGINT in every position that widens, then the long lane's own.
    out.add(new Production(l, "($0 + $1)", List.of(i, l), 2));
    out.add(new Production(l, "($0 - $1)", List.of(l, i), 2));
    out.add(new Production(l, "greatest($0, $1)", List.of(l, i), 2));
    out.add(new Production(l, "least($0, $1)", List.of(i, l), 2));
    out.add(new Production(l, "coalesce($0, $1)", List.of(l, i), 1));
    out.add(new Production(l, "if($0, $1, $2)", List.of(b, i, l), 1));
    out.add(new Production(l, "CASE WHEN $0 THEN $1 ELSE $2 END", List.of(b, l, i), 1));
    out.add(new Production(l, "($0 + $1)", List.of(l, l), 1));
    choices(out, l, l, 1);
    out.add(new Production(l, "time_diff('HOUR', $0, $1)", List.of(t, t), 1));
    out.add(new Production(l, "time_diff('microsecond', $0, $1)", List.of(t, t), 1));
    out.add(new Production(l, "time_to_millis($0)", List.of(t), 1));
    out.add(new Production(l, "time_to_micros($0)", List.of(t), 1));
    // DATE.
    out.add(new Production(d, "date_add($0, $1)", List.of(d, i), 3));
    out.add(new Production(d, "date_sub($0, $1)", List.of(d, i), 2));
    out.add(new Production(d, "add_months($0, $1)", List.of(d, i), 2));
    out.add(new Production(d, "($0 + $1)", List.of(d, y), 2));
    out.add(new Production(d, "($0 - $1)", List.of(d, y), 1));
    out.add(new Production(d, "($0 + CAST($1 AS INTERVAL DAY))", List.of(d, i), 1));
    out.add(new Production(d, "last_day($0)", List.of(d), 1));
    out.add(new Production(d, "next_day($0, 'MONDAY')", List.of(d), 1));
    for (String f : List.of("YEAR", "MONTH", "QUARTER", "WEEK")) {
      out.add(new Production(d, "trunc($0, '" + f + "')", List.of(d), 1));
    }
    out.add(new Production(d, "make_date($0, $1, $2)", List.of(i, i, i), 1));
    out.add(new Production(d, "date_from_unix_date($0)", List.of(i), 1));
    choices(out, d, d, 1);
    // TIME: the precisions of the literals meet TIME(6) columns in the choices.
    out.add(new Production(t, "time_trunc('MINUTE', $0)", List.of(t), 1));
    out.add(new Production(t, "time_trunc('MILLISECOND', $0)", List.of(t), 1));
    out.add(new Production(t, "time_trunc('HOUR', $0)", List.of(t), 1));
    out.add(new Production(t, "time_from_micros(time_to_micros($0))", List.of(t), 1));
    out.add(new Production(t, "time_from_millis(time_to_millis($0))", List.of(t), 1));
    // A TIME plus a day-time interval raises DATETIME_OVERFLOW in every mode once the sum leaves
    // the day, which a drawn interval does on nearly every batch; only the zero interval is added.
    out.add(new Production(t, "($0 + INTERVAL '0' SECOND)", List.of(t), 1));
    choices(out, t, t, 1);
    // DAY_TIME: a TIME difference is HOUR TO SECOND, the columns DAY TO SECOND, the literals
    // anything from DAY to MINUTE.
    out.add(new Production(x, "($0 - $1)", List.of(t, t), 3));
    out.add(new Production(x, "($0 + $1)", List.of(x, x), 1));
    // The difference of two TIMESTAMP_NTZ values, and of one and a DATE, which the analyzer casts
    // to TIMESTAMP_NTZ (VARKA-339).
    out.add(new Production(x, "($0 - $1)", List.of(n, n), 2));
    out.add(new Production(x, "($0 - $1)", List.of(n, d), 1));
    choices(out, x, x, 2);
    // YEAR_MONTH: the three units meet in every arithmetic and choice.
    out.add(new Production(y, "($0 + $1)", List.of(y, y), 3));
    out.add(new Production(y, "($0 - $1)", List.of(y, y), 3));
    out.add(new Production(y, "abs($0)", List.of(y), 1));
    out.add(new Production(y, "make_ym_interval($0, $1)", List.of(i, i), 1));
    // No `<ym> * k`: an overflowing interval multiply raises a bare ArithmeticException in every
    // mode, which reaches the fuzzer as the job's SparkException and no condition, a class the
    // expected-errors list cannot hold without excusing every task failure (VARKA-298.md 9).
    choices(out, y, y, 2);
    // TIMESTAMP_NTZ: plus and minus a day-time interval of any fields (VARKA-339), and choice.
    out.add(new Production(n, "($0 + $1)", List.of(n, x), 3));
    out.add(new Production(n, "($0 - $1)", List.of(n, x), 3));
    choices(out, n, n, 1);
    // BOOLEAN: comparisons of one type, of an INT with a BIGINT, and of a DATE with a date
    // string; null tests; IN lists that widen; and the connectives.
    for (Type v : VALUE_TYPES) {
      int w = v == d || v == y ? 3 : 2;
      for (String op : List.of("=", "<", "<=", ">", ">=")) {
        out.add(new Production(b, "($0 " + op + " $1)", List.of(v, v), w));
      }
      out.add(new Production(b, "($0 IS NULL)", List.of(v), 1));
      out.add(new Production(b, "($0 IS NOT NULL)", List.of(v), 1));
    }
    for (String op : List.of("=", "<", ">=")) {
      out.add(new Production(b, "($0 " + op + " $1)", List.of(i, l), 3));
      out.add(new Production(b, "($0 " + op + " $1)", List.of(l, i), 2));
      out.add(new Production(b, "($0 " + op + " '2021-06-01')", List.of(d), 2));
    }
    out.add(new Production(b, "($0 IN (1, 5000000000))", List.of(i), 1));
    out.add(new Production(b, "($0 IN (DATE'2021-01-01', DATE'2021-06-01'))", List.of(d), 1));
    out.add(new Production(b, "(NOT $0)", List.of(b), 3));
    out.add(new Production(b, "($0 AND $1)", List.of(b, b), 4));
    out.add(new Production(b, "($0 OR $1)", List.of(b, b), 4));
    return List.copyOf(out);
  }

  /** The choice functions over an operand of type {@code r} and one of {@code a}, yielding r. */
  private static void choices(List<Production> out, Type r, Type a, int weight) {
    out.add(new Production(r, "if($0, $1, $2)", List.of(Type.BOOLEAN, r, a), weight));
    out.add(new Production(r, "CASE WHEN $0 THEN $1 ELSE $2 END", List.of(Type.BOOLEAN, r, a),
        weight));
    out.add(new Production(r, "coalesce($0, $1)", List.of(r, a), weight));
    out.add(new Production(r, "greatest($0, $1)", List.of(r, a), weight));
    out.add(new Production(r, "least($0, $1)", List.of(r, a), weight));
  }

  /**
   * The deepest a drawn operation goes, the root being at depth 0. A boolean due at this depth,
   * which has no column to stop at, is a comparison, a null test or an {@code IN} over leaves, so
   * a tree's leaves can sit one level deeper.
   */
  public static final int MAX_DEPTH = 3;

  /** A tree of {@code type}, its root an operation (never a bare leaf), to {@link #MAX_DEPTH}. */
  public static Node draw(Random rnd, Type type) {
    return draw(rnd, type, 0, MAX_DEPTH);
  }

  /** An output: a tree of a value type drawn uniformly. */
  public static Node drawOutput(Random rnd) {
    return draw(rnd, VALUE_TYPES.get(rnd.nextInt(VALUE_TYPES.size())));
  }

  /** A conjunct: a boolean tree. */
  public static Node drawPredicate(Random rnd) {
    return draw(rnd, Type.BOOLEAN);
  }

  private static Node draw(Random rnd, Type type, int depth, int maxDepth) {
    boolean leaf = depth >= maxDepth || (depth > 0 && rnd.nextDouble() < 0.2 + 0.2 * depth);
    if (leaf && type != Type.BOOLEAN) {
      return leaf(rnd, type);
    }
    var candidates = new ArrayList<Production>();
    int total = 0;
    for (Production p : PRODUCTIONS) {
      if (p.type() == type && (!leaf || isAtom(p))) {
        candidates.add(p);
        total += p.weight();
      }
    }
    int pick = rnd.nextInt(total);
    Production chosen = candidates.get(candidates.size() - 1);
    for (Production p : candidates) {
      pick -= p.weight();
      if (pick < 0) {
        chosen = p;
        break;
      }
    }
    // An atom drawn in a leaf's place takes leaves; otherwise the children go one level down.
    int next = leaf ? maxDepth : depth + 1;
    var children = new ArrayList<Node>();
    for (Type operand : chosen.operands()) {
      children.add(draw(rnd, operand, next, maxDepth));
    }
    return new Node(type, chosen.template(), children);
  }

  /** A leaf of {@code type}: a column two times in three, else a literal. */
  private static Node leaf(Random rnd, Type type) {
    List<String> columns = COLUMNS.get(type);
    List<String> pool = rnd.nextInt(3) < 2 ? columns : LITERALS.get(type);
    return new Node(type, pool.get(rnd.nextInt(pool.size())), List.of());
  }

  /**
   * The smaller trees the shrinker tries in {@code node}'s place, all of {@code node}'s type so the
   * parent still types: each column of the type and its first literal (for a boolean, a null test
   * of the first date column), unless {@code node} is a leaf already; then each child of the same
   * type.
   */
  public static List<Node> replacements(Node node) {
    var out = new ArrayList<Node>();
    if (!node.isLeaf()) {
      if (node.type() == Type.BOOLEAN) {
        Node test = new Node(Type.BOOLEAN, "($0 IS NOT NULL)",
            List.of(new Node(Type.DATE, "d", List.of())));
        if (!test.equals(node)) {
          out.add(test);
        }
      } else {
        for (String c : COLUMNS.get(node.type())) {
          out.add(new Node(node.type(), c, List.of()));
        }
        out.add(new Node(node.type(), LITERALS.get(node.type()).get(0), List.of()));
      }
    }
    for (Node c : node.children()) {
      if (c.type() == node.type()) {
        out.add(c);
      }
    }
    return out;
  }

  /** The moves {@link VarkaShrinker#shrinkTree} makes over these trees. */
  public static final VarkaShrinker.TreeMoves<Node> MOVES = new VarkaShrinker.TreeMoves<>() {
    @Override
    public List<Node> children(Node node) {
      return node.children();
    }

    @Override
    public Node withChildren(Node node, List<Node> children) {
      return node.withChildren(children);
    }

    @Override
    public List<Node> replacements(Node node) {
      return VarkaCatalystGrammar.replacements(node);
    }
  };
}
