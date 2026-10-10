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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

import scala.Option;
import scala.collection.immutable.Seq;
import scala.runtime.AbstractPartialFunction;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.BoundReference;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;

/**
 * Collects why a projection entry declined, and the side tables a compile appends to, for the
 * classifier that owns one per query.
 *
 * <p>Only the first note of an entry is kept: the recursion compiles children before the node
 * that contains them, so the first reason is the innermost one, which names the construct that
 * actually cannot be lowered. {@link #take} returns it and clears the sink for the next entry.
 *
 * <p>The input bounds are the range facts the compile relied on: child ordinal {@code ordinal}
 * must lie in {@code [lo, hi]} for the entry being compiled. They follow the tables' rollback
 * discipline - {@link #boundsMark} before an entry, {@link #truncateBounds} when the entry
 * declines - and {@link #inputBounds} reports the survivors in kernel-input terms.
 *
 * <p>The long literal table lives here rather than beside the int one in every signature because
 * every compile arm already carries the sink, and because it is the second half of one table
 * rather than a second table: a kernel is single-lane, so it reads the int slots or the long
 * slots and never both. It follows the bounds' rollback discipline - a mark before an entry, a
 * truncate when the entry declines.
 *
 * <p>The recursion works on bound expressions, whose {@code BoundReference}s render as
 * {@code input[1, int, true]}; the child's attributes go back in before the text is kept, so a
 * reason reads in the query's own column names.
 *
 * <p>It also carries the one compile option the condition arms read, {@code rangeSets}, for the
 * same reason as the long table: every arm already has the sink in hand.
 */
final class DeclineSink {

  /** The longest rendering of an expression a decline keeps. */
  private static final int SHOWN_MAX = 80;

  /** A noted range fact: child ordinal {@code ordinal} lies in {@code [lo, hi]}. */
  private record Bound(int ordinal, int lo, int hi) {
  }

  private final Seq<Attribute> childOutput;
  private final boolean rangeSets;
  private final List<Bound> bounds = new ArrayList<>();
  private final LinkedHashMap<Long, Integer> longLiterals = new LinkedHashMap<>();
  private VarkaDecline first;

  DeclineSink(Seq<Attribute> childOutput, boolean rangeSets) {
    this.childOutput = childOutput;
    this.rangeSets = rangeSets;
  }

  boolean rangeSets() {
    return rangeSets;
  }

  /** Notes that child ordinal {@code ordinal} must lie in {@code [lo, hi]}. */
  void bound(int ordinal, int lo, int hi) {
    bounds.add(new Bound(ordinal, lo, hi));
  }

  int boundsMark() {
    return bounds.size();
  }

  /** Drops the bounds noted since {@code mark} - a declining entry's. */
  void truncateBounds(int mark) {
    bounds.subList(mark, bounds.size()).clear();
  }

  /** Interns {@code value} in the long lane's per-distinct-value table and wraps it as its slot. */
  LiteralSlot longSlot(long value) {
    Integer slot = longLiterals.get(value);
    if (slot == null) {
      slot = longLiterals.size();
      longLiterals.put(value, slot);
    }
    return new LiteralSlot(slot, LaneType.LONG);
  }

  int longMark() {
    return longLiterals.size();
  }

  /** Drops the long literals interned since {@code mark} - a declining entry's. */
  void truncateLong(int mark) {
    // Entries are in slot order, so the ones past the mark are the tail.
    while (longLiterals.size() > mark) {
      longLiterals.pollLastEntry();
    }
  }

  /** The long literal table in slot order, for the compiled plan. */
  List<Long> longLiteralValues() {
    return List.copyOf(longLiterals.keySet());
  }

  /** The noted bounds in kernel-input terms, given the accepted entries' input table. */
  List<VarkaInputBound> inputBounds(scala.collection.mutable.LinkedHashMap<?, ?> inputs) {
    var table = VarkaNodeCompiler.table(inputs);
    var distinct = new LinkedHashSet<VarkaInputBound>();
    for (Bound b : bounds) {
      Option<?> index = table.get(b.ordinal());
      if (index.isDefined()) {
        distinct.add(new VarkaInputBound((Integer) index.get(), b.lo(), b.hi()));
      }
    }
    return List.copyOf(distinct);
  }

  /** Notes {@code reason} against {@code expr} if nothing has been noted since the last take. */
  void note(String reason, Expression expr) {
    if (first == null) {
      Expression named = expr.transformUp(new AbstractPartialFunction<Expression, Expression>() {
        @Override
        public boolean isDefinedAt(Expression e) {
          return e instanceof BoundReference br
              && br.ordinal() >= 0 && br.ordinal() < childOutput.length();
        }

        @Override
        public Expression apply(Expression e) {
          return childOutput.apply(((BoundReference) e).ordinal());
        }
      });
      String text = named.sql();
      String shown = text.length() > SHOWN_MAX ? text.substring(0, SHOWN_MAX - 3) + "..." : text;
      first = new VarkaDecline(reason, shown);
    }
  }

  /** Notes {@code reason} against {@code e} and declines it. */
  <T> Option<T> decline(String reason, Expression e) {
    note(reason, e);
    return Option.empty();
  }

  /** The first note, cleared. */
  Option<VarkaDecline> take() {
    Option<VarkaDecline> taken = Option.apply(first);
    first = null;
    return taken;
  }
}
