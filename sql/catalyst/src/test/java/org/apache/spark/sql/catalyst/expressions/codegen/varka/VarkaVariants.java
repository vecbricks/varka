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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DecimalColumn;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;

/**
 * Variants of a projection whose answers follow from its own (VARKA-278): the same trees emitted
 * in other company, so that what the emitter decides from a tree's surroundings - which outputs
 * share a subtree and so a slot, how the outputs group into methods, which literal is folded and
 * which column is read - meets the reference evaluator too, not only the one form a fuzzer drew.
 * The idea is equivalence modulo inputs (Le, Afshari and Su, PLDI 2014).
 *
 * <p>A variant is a list of roots and a column plan: where each of its input columns comes from,
 * an original column or a literal slot's value on every row. The suites apply the plan to the
 * batch the original passed on and compare every output of the variant with the reference
 * evaluator, so a variant's expected answer is never computed here; this class only builds
 * shapes. Every variant is well typed by construction - a rewrite goes through
 * {@link VarkaVectorIR#withChildren}, which refuses a tree that does not type - and keeps every
 * node's claim about its operands honest, since the values a node sees on the compared rows are
 * the original's: a literal moves into a column holding that same literal, columns move with
 * their data, and a replaced arm is one no compared row takes.
 *
 * <p>A rewrite keeps every subtree it does not change as the same object. {@link #addedRefusalBits}
 * reads the guards a variant adds, by position against the original, which is what a variant
 * that may decline must name.
 */
final class VarkaVariants {

  private VarkaVariants() {}

  /** The eight variants, in the order the suites run them. */
  enum Kind {
    /** The first root beside its first value child: a shared subtree, a slot. */
    BESIDE_A_CHILD("beside a child", false),
    /** The child first: output order decides grouping and slot numbering. */
    PERMUTED("permuted", false),
    /** Every root twice, the same objects: sharing by identity, where the emitter has any. */
    DUPLICATED("duplicated", false),
    /** Every root beside a structurally equal copy of fresh objects: sharing by structure. */
    COPIED("copied", false),
    /**
     * Beside another passing tree over copies of the columns: two unrelated outputs in one
     * method, and the stranger's guards in the original's.
     */
    BESIDE_A_STRANGER("beside a stranger", true),
    /** Each literal slot read from a column holding its value: the column path, no constant. */
    LITERAL_TO_COLUMN("literal to column", true),
    /** The columns in reverse order, the batch alike: input ordinals in slots and words. */
    COLUMNS_RENUMBERED("columns renumbered", false),
    /**
     * An arm no row of the batch takes, replaced by another tree: code that must not run. It may
     * decline, by a guard the replacement brings: one of its own, unqualified where the arm does
     * not mask it, or one a node gains by its new place - a producer the replacement puts under a
     * calendar node - which, the emitter keying nodes by structure, guards every use of it.
     */
    UNTAKEN_ARM("untaken arm replaced", true);

    /** The name in a failure and in the counts. */
    final String label;
    /**
     * Whether the variant may decline a batch the original answered: only where it changes what
     * the emitter knows or guards, and then only by a guard the new part brings.
     */
    final boolean mayDecline;

    Kind(String label, boolean mayDecline) {
      this.label = label;
      this.mayDecline = mayDecline;
    }
  }

  /** Where one input column of a variant comes from. */
  sealed interface Source permits FromColumn, FromLiteral {}

  /** The original's column {@code ordinal}, values and nulls. */
  record FromColumn(int ordinal) implements Source {
    FromColumn {
      if (ordinal < 0) {
        throw new IllegalArgumentException("column " + ordinal);
      }
    }
  }

  /** Literal slot {@code slot}'s value on every row, never null. */
  record FromLiteral(int slot) implements Source {
    FromLiteral {
      if (slot < 0) {
        throw new IllegalArgumentException("literal slot " + slot);
      }
    }
  }

  /** One variant: its kind, its roots and where each of its columns comes from. */
  record Variant(Kind kind, List<VarkaVectorIR> roots, List<Source> columns) {
    Variant {
      roots = List.copyOf(roots);
      columns = List.copyOf(columns);
      if (roots.isEmpty()) {
        throw new IllegalArgumentException(kind.label + ": no roots");
      }
    }

    int numInputs() {
      return columns.size();
    }
  }

  /** Which arm of a condition the rows of a batch take, as the reference evaluator decides. */
  enum Taken {
    /** No row's condition is true: the then arm is never taken (null goes to else). */
    ELSE_ONLY,
    /** Every row's condition is true: the else arm is never taken. */
    THEN_ONLY,
    /** Some rows each way. */
    BOTH
  }

  /** The reference evaluator's reading of a condition over the batch a variant is for. */
  interface ArmOracle {
    Taken taken(Cond cond);
  }

  // ---------------------------------------------------------------------------------------------
  // The variants
  // ---------------------------------------------------------------------------------------------

  /** {@code [roots..., c]}, {@code c} the first root's first value child; empty for a leaf. */
  static Optional<Variant> besideAChild(List<VarkaVectorIR> roots, int numInputs) {
    return firstValueChild(roots.get(0)).map(c ->
        new Variant(Kind.BESIDE_A_CHILD, concat(roots, List.of(c)), identity(numInputs)));
  }

  /** {@code [c, roots...]}, the same child first. */
  static Optional<Variant> permuted(List<VarkaVectorIR> roots, int numInputs) {
    return firstValueChild(roots.get(0)).map(c ->
        new Variant(Kind.PERMUTED, concat(List.of(c), roots), identity(numInputs)));
  }

  /** {@code [roots..., roots...]}, the same node objects twice. */
  static Variant duplicated(List<VarkaVectorIR> roots, int numInputs) {
    return new Variant(Kind.DUPLICATED, concat(roots, roots), identity(numInputs));
  }

  /** {@code [roots..., copies...]}, each copy equal to its root and sharing no node with it. */
  static Variant copied(List<VarkaVectorIR> roots, int numInputs) {
    var copies = new ArrayList<VarkaVectorIR>();
    for (VarkaVectorIR r : roots) {
      copies.add(deepCopy(r));
    }
    return new Variant(Kind.COPIED, concat(roots, copies), identity(numInputs));
  }

  /**
   * {@code [roots..., s]}, {@code s} the stranger over copies of the columns: its column
   * {@code c} becomes {@code numInputs + c}, which holds column {@code c}'s values and nulls. So
   * the stranger shares no column read with the original, and its own guards, if it has any,
   * sit in the same kernel. The stranger must be a tree that passes over the original's columns.
   */
  static Variant besideAStranger(List<VarkaVectorIR> roots, int numInputs,
      VarkaVectorIR stranger) {
    VarkaVectorIR shifted = rewrite(stranger, n -> n instanceof ColumnRef c
        ? new ColumnRef(c.ordinal() + numInputs, c.lane()) : n);
    return new Variant(Kind.BESIDE_A_STRANGER, concat(roots, List.of(shifted)),
        concat(identity(numInputs), identity(numInputs)));
  }

  /**
   * Each literal slot the roots read moved into a new column that holds the slot's value on
   * every row, never null; empty for roots that read no literal.
   */
  static Optional<Variant> literalToColumn(List<VarkaVectorIR> roots, int numInputs) {
    var slots = new TreeSet<Integer>();
    for (VarkaVectorIR r : roots) {
      walk(r, n -> {
        if (n instanceof LiteralSlot l) {
          slots.add(l.index());
        }
      });
    }
    if (slots.isEmpty()) {
      return Optional.empty();
    }
    var ordinals = new ArrayList<>(slots);
    var columns = new ArrayList<>(identity(numInputs));
    for (int s : ordinals) {
      columns.add(new FromLiteral(s));
    }
    var moved = new ArrayList<VarkaVectorIR>();
    for (VarkaVectorIR r : roots) {
      moved.add(rewrite(r, n -> n instanceof LiteralSlot l
          ? new ColumnRef(numInputs + ordinals.indexOf(l.index()), l.lane()) : n));
    }
    return Optional.of(new Variant(Kind.LITERAL_TO_COLUMN, moved, columns));
  }

  /** The columns in reverse order, column {@code c} read as {@code numInputs - 1 - c}. */
  static Optional<Variant> columnsRenumbered(List<VarkaVectorIR> roots, int numInputs) {
    if (numInputs < 2) {
      return Optional.empty();
    }
    var columns = new ArrayList<Source>();
    for (int j = 0; j < numInputs; j++) {
      columns.add(new FromColumn(numInputs - 1 - j));
    }
    var moved = new ArrayList<VarkaVectorIR>();
    for (VarkaVectorIR r : roots) {
      moved.add(rewrite(r, n -> n instanceof ColumnRef c
          ? new ColumnRef(numInputs - 1 - c.ordinal(), c.lane()) : n));
    }
    return Optional.of(new Variant(Kind.COLUMNS_RENUMBERED, moved, columns));
  }

  /**
   * Every arm of an {@code IfElse} that no row of the batch takes, as {@code oracle} reads the
   * batch, replaced by a tree from {@code pool}: of the arm's lane, not a condition, not
   * {@code NarrowLane} (a root-only node), and printing differently from the arm. The pool is
   * scanned from {@code rotation}, so successive trees meet different replacements. Empty where
   * no arm is untaken or no replacement fits. Defined for that batch only: on another the arm
   * may be taken, and the replacement may then leave a range an enclosing guard was drawn for,
   * which the guard rightly declines - so the variant is run on that batch's nulls alone.
   */
  static Optional<Variant> untakenArm(List<VarkaVectorIR> roots, int numInputs,
      ArmOracle oracle, List<VarkaVectorIR> pool, int rotation) {
    return untakenArm(roots, numInputs, oracle, pool, rotation, v -> true);
  }

  /**
   * {@link #untakenArm} whose replacements keep the variant within {@code fits} where any do: the
   * pool scanned from {@code rotation}, then from each next start once round the pool, and the
   * first variant that fits taken; where none fits, the one from {@code rotation}. A replacement
   * can be larger than the arm it replaces, and the fuzzer's {@code fits} is whether the emitter's
   * form without a byte budget still holds the tree (VARKA-323).
   */
  static Optional<Variant> untakenArm(List<VarkaVectorIR> roots, int numInputs,
      ArmOracle oracle, List<VarkaVectorIR> pool, int rotation, Predicate<Variant> fits) {
    // Which arm the batch takes depends on the condition alone, so each is read once, not once
    // per start: the oracle evaluates the condition over the whole batch.
    var taken = new IdentityHashMap<Cond, Taken>();
    ArmOracle once = cond -> taken.computeIfAbsent(cond, oracle::taken);
    Optional<Variant> first = untakenArmFrom(roots, numInputs, once, pool, rotation);
    if (first.isEmpty() || fits.test(first.get())) {
      return first;
    }
    for (int k = 1; k < pool.size(); k++) {
      Optional<Variant> other = untakenArmFrom(roots, numInputs, once, pool, rotation + k);
      if (other.isPresent() && fits.test(other.get())) {
        return other;
      }
    }
    return first;
  }

  private static Optional<Variant> untakenArmFrom(List<VarkaVectorIR> roots, int numInputs,
      ArmOracle oracle, List<VarkaVectorIR> pool, int rotation) {
    var replaced = new int[1];
    var moved = new ArrayList<VarkaVectorIR>();
    for (VarkaVectorIR r : roots) {
      moved.add(rewrite(r, n -> {
        if (!(n instanceof IfElse f)) {
          return n;
        }
        return switch (oracle.taken(f.cond())) {
          case BOTH -> n;
          case ELSE_ONLY -> replacement(f.thenNode(), pool, rotation + replaced[0])
              .<VarkaVectorIR>map(t -> {
                replaced[0]++;
                return new IfElse(f.cond(), t, f.elseNode());
              })
              .orElse(n);
          case THEN_ONLY -> replacement(f.elseNode(), pool, rotation + replaced[0])
              .<VarkaVectorIR>map(t -> {
                replaced[0]++;
                return new IfElse(f.cond(), f.thenNode(), t);
              })
              .orElse(n);
        };
      }));
    }
    if (replaced[0] == 0) {
      return Optional.empty();
    }
    return Optional.of(new Variant(Kind.UNTAKEN_ARM, moved, identity(numInputs)));
  }

  /**
   * The status bits of the guards {@code variant} adds to {@code original}: a refusal on a
   * variant node where the original's node at the same position carries no refusal or another,
   * and every refusal on a variant root past the original's roots. Positions pair root with root
   * and child with child while the two trees agree on a node's type, which they do for the two
   * variants that may decline: literal to column changes only leaves, and beside a stranger only
   * adds a root. By position and not by identity, because a rewrite rebuilds every ancestor of
   * what it changes, and an ancestor's guard the original already had is no reason the variant
   * may give. A variant that may decline must decline with these bits and no others, read under
   * {@code exactRefusalStatus}, which gives each reason its own (VARKA-304).
   */
  static int addedRefusalBits(List<VarkaVectorIR> original, int originalInputs,
      List<VarkaVectorIR> variant, int variantInputs, int numLiterals,
      VarkaEmitOptions options) {
    Map<VarkaVectorIR, Analysis.Refusal> before =
        VarkaLoopEmitter.refusalsForTest(original, originalInputs, numLiterals, options);
    Map<VarkaVectorIR, Analysis.Refusal> after =
        VarkaLoopEmitter.refusalsForTest(variant, variantInputs, numLiterals, options);
    int bits = 0;
    for (int r = 0; r < variant.size(); r++) {
      bits |= added(r < original.size() ? original.get(r) : null, variant.get(r), before, after);
    }
    return bits;
  }

  /** {@link #addedRefusalBits} under one pair of nodes; {@code o} null where none pairs. */
  private static int added(VarkaVectorIR o, VarkaVectorIR v,
      Map<VarkaVectorIR, Analysis.Refusal> before, Map<VarkaVectorIR, Analysis.Refusal> after) {
    int bits = 0;
    Analysis.Refusal mine = after.get(v);
    if (mine != null && (o == null || before.get(o) != mine)) {
      bits |= mine.status;
    }
    VarkaVectorIR[] vs = VarkaVectorIR.childrenOf(v);
    VarkaVectorIR[] os = o != null && o.getClass() == v.getClass()
        ? VarkaVectorIR.childrenOf(o) : new VarkaVectorIR[0];
    for (int i = 0; i < vs.length; i++) {
      bits |= added(i < os.length ? os[i] : null, vs[i], before, after);
    }
    return bits;
  }

  // ---------------------------------------------------------------------------------------------
  // Rewriting
  // ---------------------------------------------------------------------------------------------

  /** The original columns, in order. */
  static List<Source> identity(int numInputs) {
    var columns = new ArrayList<Source>(numInputs);
    for (int c = 0; c < numInputs; c++) {
      columns.add(new FromColumn(c));
    }
    return columns;
  }

  /**
   * {@code node} rewritten bottom up: each node's children first, the node rebuilt only where a
   * child changed, then {@code at} applied to it, returning it unchanged or its replacement. A
   * subtree nothing changes keeps its identity.
   */
  static VarkaVectorIR rewrite(VarkaVectorIR node, UnaryOperator<VarkaVectorIR> at) {
    VarkaVectorIR[] children = VarkaVectorIR.childrenOf(node);
    boolean changed = false;
    for (int i = 0; i < children.length; i++) {
      VarkaVectorIR c = rewrite(children[i], at);
      changed |= c != children[i];
      children[i] = c;
    }
    return at.apply(changed ? VarkaVectorIR.withChildren(node, children) : node);
  }

  /** A tree equal to {@code node} with no node object in common, the leaves included. */
  static VarkaVectorIR deepCopy(VarkaVectorIR node) {
    return switch (node) {
      // withChildren hands a leaf back as it is, so the leaves are built here.
      case ColumnRef c -> new ColumnRef(c.ordinal(), c.lane());
      case DecimalColumn d -> new DecimalColumn(d.ordinal(), d.lane(), d.precision());
      case LiteralSlot l -> new LiteralSlot(l.index(), l.lane());
      default -> {
        VarkaVectorIR[] children = VarkaVectorIR.childrenOf(node);
        for (int i = 0; i < children.length; i++) {
          children[i] = deepCopy(children[i]);
        }
        yield VarkaVectorIR.withChildren(node, children);
      }
    };
  }

  /** Visits every node under {@code node}, itself first. */
  static void walk(VarkaVectorIR node, Consumer<VarkaVectorIR> visit) {
    visit.accept(node);
    for (VarkaVectorIR c : VarkaVectorIR.childrenOf(node)) {
      walk(c, visit);
    }
  }

  /** The distinct value subtrees under {@code roots}, in walk order: a replacement pool. */
  static List<VarkaVectorIR> valueSubtrees(List<VarkaVectorIR> roots) {
    var seen = new LinkedHashMap<String, VarkaVectorIR>();
    for (VarkaVectorIR r : roots) {
      walk(r, n -> {
        if (!(n instanceof Cond) && !(n instanceof NarrowLane)) {
          seen.putIfAbsent(VarkaVectorIR.canonical(n), n);
        }
      });
    }
    return List.copyOf(seen.values());
  }

  private static Optional<VarkaVectorIR> firstValueChild(VarkaVectorIR root) {
    for (VarkaVectorIR c : VarkaVectorIR.childrenOf(root)) {
      if (!(c instanceof Cond)) {
        return Optional.of(c);
      }
    }
    return Optional.empty();
  }

  private static Optional<VarkaVectorIR> replacement(VarkaVectorIR arm, List<VarkaVectorIR> pool,
      int rotation) {
    String text = VarkaVectorIR.canonical(arm);
    for (int k = 0; k < pool.size(); k++) {
      VarkaVectorIR t = pool.get(Math.floorMod(rotation + k, pool.size()));
      if (!(t instanceof Cond) && !(t instanceof NarrowLane) && t.laneType() == arm.laneType()
          && !VarkaVectorIR.canonical(t).equals(text)) {
        return Optional.of(t);
      }
    }
    return Optional.empty();
  }

  private static <T> List<T> concat(List<? extends T> a, List<? extends T> b) {
    var out = new ArrayList<T>(a.size() + b.size());
    out.addAll(a);
    out.addAll(b);
    return out;
  }
}
