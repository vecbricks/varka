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
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.*;

/**
 * Reads {@code VarkaVectorIR.canonical} back into a tree: what a reproduction file's replay
 * (VARKA-315) rebuilds the failing kernel's shape key from.
 *
 * <p><b>Why the canonical form is enough.</b> The shape hash is SHA-256 over the outputs'
 * canonical renderings, the input and literal counts and the options, and the class cache serves
 * one class per hash, so two trees with one rendering already share one class: the rendering
 * carries everything the emitted bytes depend on. Two components are left out of it on purpose,
 * and the parser fills them by rule:
 *
 * <ul>
 *   <li>{@code ConstDivide}'s dividend bound, which changes no emitted byte (its javadoc): the
 *       int lane's is implied by the type, and a long-lane division is given
 *       {@code EXACT_DIVIDEND_BOUND}, the widest bound the node admits;</li>
 *   <li>{@code BoundedDivide}'s multiplier and shift, which {@code BoundedDivide.of} derives from
 *       the divisor and the bound the rendering carries.</li>
 * </ul>
 *
 * <p>A parse is checked by rendering it again: {@link #parse} refuses a string whose tree does not
 * render back to it exactly, so a node this parser reads wrongly is an error here rather than a
 * replay of another kernel. A node type added to the IR without a case here fails that check, and
 * the round-trip test over the every-node-type key and the IR grammar's trees fails with it.
 */
public final class VarkaCanonicalIr {

  private static final Pattern RANGE = Pattern.compile("(-?\\d+)-(-?\\d+)");

  private final List<String> tokens;
  private int at;

  private VarkaCanonicalIr(String text) {
    this.tokens = tokenize(text);
  }

  /** The tree {@code canonical} rendered as {@code text}; refuses anything else. */
  public static VarkaVectorIR parse(String text) {
    var parser = new VarkaCanonicalIr(text);
    VarkaVectorIR node = parser.node();
    if (parser.at != parser.tokens.size()) {
      throw new IllegalArgumentException("trailing text after the IR at token " + parser.at
          + " of " + text);
    }
    String again = VarkaVectorIR.canonical(node);
    if (!again.equals(text)) {
      throw new IllegalArgumentException("the IR does not render back as it was written: read "
          + text + ", rendered " + again);
    }
    return node;
  }

  private static List<String> tokenize(String text) {
    var out = new ArrayList<String>();
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == ' ') {
        i++;
      } else if (c == '(' || c == ')') {
        out.add(String.valueOf(c));
        i++;
      } else {
        int start = i;
        while (i < text.length() && " ()".indexOf(text.charAt(i)) < 0) {
          i++;
        }
        out.add(text.substring(start, i));
      }
    }
    return out;
  }

  private String next() {
    if (at >= tokens.size()) {
      throw new IllegalArgumentException("the IR ends early");
    }
    return tokens.get(at++);
  }

  private void expect(String token) {
    String got = next();
    if (!got.equals(token)) {
      throw new IllegalArgumentException("expected '" + token + "' at token " + (at - 1)
          + ", got '" + got + "'");
    }
  }

  private Cond cond() {
    VarkaVectorIR node = node();
    if (node instanceof Cond c) {
      return c;
    }
    throw new IllegalArgumentException("a condition was expected, got " + node);
  }

  private long number() {
    return Long.parseLong(next());
  }

  private VarkaVectorIR node() {
    String token = next();
    if (!token.equals("(")) {
      return leaf(token);
    }
    String head = next();
    String[] parts = head.split(":");
    VarkaVectorIR node = switch (parts[0]) {
      case "addDays" -> new AddDays(node(), node());
      case "subDays" -> new SubDays(node(), node());
      case "guardedDay" -> new GuardedDay(node());
      case "guardedRange" -> {
        VarkaVectorIR child = node();
        long lo = number();
        yield new GuardedRange(child, lo, number());
      }
      case "narrow" -> new NarrowLane(node());
      case "dateDiff" -> new DateDiff(node(), node());
      case "cmp" -> new Compare(CompareOp.valueOf(parts[1]), node(), node());
      case "and" -> new And(cond(), cond());
      case "or" -> new Or(cond(), cond());
      case "not" -> new Not(cond());
      case "isNotNull" -> new IsNotNull(node());
      case "inRanges" -> new InRanges(node(), ranges(next()));
      case "if" -> new IfElse(cond(), node(), node());
      case "greatest" -> new Greatest(node(), node());
      case "least" -> new Least(node(), node());
      case "dayOfWeek" -> new DayOfWeek(node());
      case "weekDay" -> new WeekDay(node());
      case "dayOfWeekIso" -> new DayOfWeekIso(node());
      case "nextDay" -> new NextDay(node(), node());
      case "thursdayOf" -> new ThursdayOf(node());
      case "year" -> new Year(node());
      case "month" -> new Month(node());
      case "dayOfMonth" -> new DayOfMonth(node());
      case "quarter" -> new Quarter(node());
      case "dayOfYear" -> new DayOfYear(node());
      case "lastDay" -> new LastDay(node());
      case "truncDate" -> new TruncDate(node(), TruncLevel.valueOf(parts[1]));
      case "truncDateDynamic" -> new TruncDateDynamic(node(), node());
      case "weekOfYear" -> new WeekOfYear(node());
      case "addMonths" -> new AddMonths(node(), node());
      case "makeDate" -> new MakeDate(node(), node(), node(), parts[1].equals("ANSI"));
      case "int" -> new IntArith(IntOp.valueOf(parts[1]), Overflow.valueOf(parts[2]), node(),
          node());
      case "neg" -> new IntNeg(Overflow.valueOf(parts[1]), node());
      case "divc" -> {
        long divisor = Long.parseLong(parts[1]);
        VarkaVectorIR child = node();
        yield child.laneType() == LaneType.INT
            ? new ConstDivide(child, divisor)
            : new ConstDivide(child, divisor, ConstDivide.EXACT_DIVIDEND_BOUND);
      }
      case "divb" -> {
        String[] pair = parts[1].split("/");
        yield BoundedDivide.of(node(), Integer.parseInt(pair[0]), Integer.parseInt(pair[1]));
      }
      case "bit" -> new BitOp(BitOpKind.valueOf(parts[1]), node(), node());
      case "bitCount" -> new BitCount(node());
      default -> throw new IllegalArgumentException("no IR node is rendered as '" + head + "'");
    };
    expect(")");
    return node;
  }

  /** {@code col:N} or {@code lit:N}, each with {@code :long} on the long lane. */
  private static VarkaVectorIR leaf(String token) {
    String[] parts = token.split(":");
    if (parts.length < 2 || parts.length > 3) {
      throw new IllegalArgumentException("not a leaf: '" + token + "'");
    }
    int index = Integer.parseInt(parts[1]);
    LaneType lane = parts.length == 3
        ? LaneType.valueOf(parts[2].toUpperCase(Locale.ROOT)) : LaneType.INT;
    return switch (parts[0]) {
      case "col" -> new ColumnRef(index, lane);
      case "lit" -> new LiteralSlot(index, lane);
      default -> throw new IllegalArgumentException("not a leaf: '" + token + "'");
    };
  }

  /** {@code lo-hi,lo-hi,...}, either bound possibly negative. */
  private static List<Integer> ranges(String text) {
    var bounds = new ArrayList<Integer>();
    for (String range : text.split(",")) {
      Matcher m = RANGE.matcher(range);
      if (!m.matches()) {
        throw new IllegalArgumentException("not a range: '" + range + "'");
      }
      bounds.add(Integer.parseInt(m.group(1)));
      bounds.add(Integer.parseInt(m.group(2)));
    }
    return bounds;
  }
}
