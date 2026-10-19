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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import scala.collection.immutable.Seq;
import scala.runtime.AbstractPartialFunction;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.BoundReference;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaChrono;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFamily;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaRangeAnalysis;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
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
 * declines - and {@link #inputBounds} reports the survivors in kernel-input terms. A date
 * column's bound is the exception: the entry's compile ends by turning it into a guard in the
 * kernel ({@link #guardDayColumns}), and it never reaches {@link #inputBounds}.
 *
 * <p>The long literal table lives here rather than beside the int one in every signature because
 * every compile arm already carries the sink, and because it is the second half of one table
 * rather than a second table: a kernel driven at the int lane reads only the int slots, and one
 * driven at the long lane the long slots and, where it converts between lanes, the int ones too
 * (VARKA-28), each lane's slots indexing its own table. It follows the bounds' rollback
 * discipline - a mark before an entry, a truncate when the entry declines.
 *
 * <p>The recursion works on bound expressions, whose {@code BoundReference}s render as
 * {@code input[1, int, true]}; the child's attributes go back in before the text is kept, so a
 * reason reads in the query's own column names.
 *
 * <p>It also carries the one compile option the condition arms read, {@code rangeSets}, for the
 * same reason as the long table: every arm already has the sink in hand; and, for the same
 * reason, the families the session has switched off and the families the accepted entries used
 * (VARKA-318), the latter under the bounds' rollback discipline so that a declining entry's
 * families are not reported for the kernel.
 */
final class DeclineSink {

  /** The longest rendering of an expression a decline keeps. */
  private static final int SHOWN_MAX = 80;

  /**
   * A noted range fact: the input {@code key} names lies in {@code [lo, hi]}. The key is a child
   * ordinal, or, where {@code slot} is set, the input's slot in the compile's input table - what
   * an IR {@code ColumnRef} carries, so a fact the range analysis reports needs no table to note.
   */
  private record Bound(int key, boolean slot, int lo, int hi) {
  }

  private final Seq<Attribute> childOutput;
  private final boolean rangeSets;
  private final Set<VarkaFamily> familiesOff;
  private final List<VarkaFamily> used = new ArrayList<>();
  private final List<Bound> bounds = new ArrayList<>();
  private final LinkedHashMap<Long, Integer> longLiterals = new LinkedHashMap<>();
  private VarkaDecline first;

  DeclineSink(Seq<Attribute> childOutput, boolean rangeSets) {
    this(childOutput, rangeSets, Set.of());
  }

  DeclineSink(Seq<Attribute> childOutput, boolean rangeSets, Set<VarkaFamily> familiesOff) {
    this.childOutput = childOutput;
    this.rangeSets = rangeSets;
    this.familiesOff = Set.copyOf(familiesOff);
  }

  boolean rangeSets() {
    return rangeSets;
  }

  /** Whether the session has switched {@code family} off. */
  boolean off(VarkaFamily family) {
    return familiesOff.contains(family);
  }

  /** Notes that the entry being compiled lowered a node of {@code family}. */
  void used(VarkaFamily family) {
    used.add(family);
  }

  int usedMark() {
    return used.size();
  }

  /** Drops the families noted since {@code mark} - a declining entry's. */
  void truncateUsed(int mark) {
    used.subList(mark, used.size()).clear();
  }

  /** The families the surviving entries lowered nodes of, in declaration order. */
  List<VarkaFamily> familiesUsed() {
    var distinct = EnumSet.noneOf(VarkaFamily.class);
    distinct.addAll(used);
    return List.copyOf(distinct);
  }

  /** Notes that child ordinal {@code ordinal} must lie in {@code [lo, hi]}. */
  void bound(int ordinal, int lo, int hi) {
    bounds.add(new Bound(ordinal, false, lo, hi));
  }

  /**
   * Notes that the input in slot {@code slot} of the compile's input table must lie in
   * {@code [lo, hi]}. Slots are stable for as long as the bound is kept: the table only appends,
   * and a declining entry truncates the table and these bounds back to the same marks.
   */
  void boundSlot(int slot, int lo, int hi) {
    bounds.add(new Bound(slot, true, lo, hi));
  }

  /**
   * Notes the column bound, {@code VarkaRangeAnalysis.CONTRACT}, on the date column in input slot
   * {@code slot}: the callback a compile hands the range analysis when it acts on the answer. The
   * entry's compile ends with {@link #guardDayColumns}, which moves the check into the kernel.
   */
  void dayColumnBound(int slot) {
    boundSlot(slot, VarkaChrono.CONTRACT_MIN_DAYS, VarkaChrono.CONTRACT_MAX_DAYS);
  }

  /**
   * {@code compiled}, an entry's tree whose compile noted bounds from {@code mark} on, with every
   * use of each column {@link #dayColumnBound} noted read through a guard over the column bound
   * ({@code VarkaRangeAnalysis.guardColumns}), and those bounds dropped: the kernel checks them
   * on the vectors it loads, so the evaluator does not pass over the columns before it
   * (VARKA-379). Called once per entry at its root, before the budgets are asked, rather than
   * where a bound is noted: the overflow check's site answers a question about operands its
   * callers then build the node from, and a guard placed there would not reach that node. A bound
   * the tree could not take as a guard - a column in a restricted operand position - stays for the
   * evaluator's check. The child-ordinal bounds stay too; they are not date columns.
   */
  VarkaVectorIR guardDayColumns(VarkaVectorIR compiled, int mark) {
    var slots = new LinkedHashSet<Integer>();
    for (Bound b : bounds.subList(mark, bounds.size())) {
      if (b.slot()) {
        slots.add(b.key());
      }
    }
    if (slots.isEmpty()) {
      return compiled;
    }
    var unguarded = new LinkedHashSet<Integer>();
    VarkaVectorIR guarded = VarkaRangeAnalysis.guardColumns(compiled, slots, unguarded);
    bounds.subList(mark, bounds.size())
        .removeIf(b -> b.slot() && !unguarded.contains(b.key()));
    return guarded;
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
  List<VarkaInputBound> inputBounds(VarkaSlotTable inputs) {
    var distinct = new LinkedHashSet<VarkaInputBound>();
    for (Bound b : bounds) {
      OptionalInt index = b.slot()
          ? (b.key() < inputs.size() ? OptionalInt.of(b.key()) : OptionalInt.empty())
          : inputs.slot(b.key());
      if (index.isPresent()) {
        distinct.add(new VarkaInputBound(index.getAsInt(), b.lo(), b.hi()));
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
  <T> Optional<T> decline(String reason, Expression e) {
    note(reason, e);
    return Optional.empty();
  }

  /** The first note, cleared. */
  Optional<VarkaDecline> take() {
    Optional<VarkaDecline> taken = Optional.ofNullable(first);
    first = null;
    return taken;
  }
}
