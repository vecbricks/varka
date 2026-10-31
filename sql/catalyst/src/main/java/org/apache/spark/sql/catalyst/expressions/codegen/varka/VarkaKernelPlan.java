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

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NarrowLane;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;

/**
 * The lanes of one kernel, decided once from its roots and the emit options (VARKA-488,
 * {@code VARKA-475.md} 3.3): the lane its loop drives at, the lane each node is emitted at, each
 * input's load and the int groups one iteration computes - or why no kernel of these roots can be
 * emitted. The compiler's admission, the budget mirror, the emitter and the compiled projection
 * each worked these out for themselves, and agreed only because each was fixed to agree; the plan
 * is the one place they are decided.
 *
 * <p>A plan with a {@link #refusal()} has no lanes: asking one throws, so no consumer can emit
 * past a refusal. The refusal texts are the compiler's decline reasons, word for word.
 */
public final class VarkaKernelPlan {

  /** Why no kernel of these roots can be emitted. */
  public record Refusal(Kind kind, String reason) {}

  /** What refused a kernel. */
  public enum Kind {
    /** A narrow value the drive lane cannot lower or this width cannot widen. */
    NARROW,
    /** Two roots no one lane serves: a long root beside a narrow or int one. */
    LANE_MISMATCH,
    /** A floating conjunct of a filter whose kernel is integral, which it cannot join yet. */
    FLOATING_CONDITION
  }

  private final Optional<Refusal> refusal;
  private final LaneType drive;
  private final Lane lane;
  private final int lanes;
  private final boolean mixed;
  private final Set<VarkaVectorIR> storeNarrowings;
  private final Map<Integer, LaneType> inputLanes;
  private final int narrowGroups;

  private VarkaKernelPlan(Optional<Refusal> refusal, LaneType drive, int lanes, boolean mixed,
      Set<VarkaVectorIR> storeNarrowings, Map<Integer, LaneType> inputLanes, int narrowGroups) {
    this.refusal = refusal;
    this.drive = drive;
    this.lane = Lane.of(drive);
    this.lanes = lanes;
    this.mixed = mixed;
    this.storeNarrowings = storeNarrowings;
    this.inputLanes = inputLanes;
    this.narrowGroups = narrowGroups;
  }

  /** The plan of a kernel of {@code roots} under {@code options}. */
  public static VarkaKernelPlan of(List<VarkaVectorIR> roots, VarkaEmitOptions options) {
    if (roots.isEmpty()) {
      throw VarkaErrors.illegalArgument("no output chains to plan");
    }
    Optional<LaneType> kernel = kernelLane(roots, options);
    Optional<Refusal> narrow = narrowRefusal(roots, kernel, options);
    if (narrow.isPresent()) {
      return refused(narrow.get());
    }
    if (kernel.isEmpty()) {
      return refused(laneMismatch(roots, options));
    }
    LaneType drive = kernel.get();
    Lane lane = Lane.of(drive);
    int lanes = Lane.emitLanes(options, lane);

    // Every distinct node, and every node some other node reads: a narrowing root that nothing
    // else reads narrows at its store and stays in the long lane until then.
    Set<VarkaVectorIR> nodes = new HashSet<>();
    Set<VarkaVectorIR> operands = new HashSet<>();
    Map<Integer, LaneType> inputLanes = new HashMap<>();
    var todo = new ArrayDeque<VarkaVectorIR>(roots);
    while (!todo.isEmpty()) {
      VarkaVectorIR node = todo.pop();
      if (!nodes.add(node)) {
        continue;
      }
      // Each input leaf's lane, which sizes its data segment. A boolean column's lane is its
      // siblings', but its segment is a bitmap, which loadInputSegment sizes before it asks this.
      int ordinal = VarkaVectorIR.inputOrdinal(node);
      if (ordinal >= 0) {
        inputLanes.putIfAbsent(ordinal, node.laneType());
      }
      for (VarkaVectorIR child : VarkaVectorIR.childrenOf(node)) {
        operands.add(child);
        todo.push(child);
      }
    }
    Set<VarkaVectorIR> storeNarrowings = new HashSet<>();
    for (VarkaVectorIR root : roots) {
      if (root instanceof NarrowLane && !operands.contains(root)) {
        storeNarrowings.add(root);
      }
    }
    boolean mixed = false;
    if (lane == Lane.LONG) {
      for (VarkaVectorIR node : nodes) {
        mixed |= node.laneType() == LaneType.INT && !storeNarrowings.contains(node);
      }
    }
    return new VarkaKernelPlan(Optional.empty(), drive, lanes, mixed, Set.copyOf(storeNarrowings),
        Map.copyOf(inputLanes), narrowGroups(roots, lane, lanes));
  }

  /**
   * A plan with no lanes. Its lane fields hold placeholders that no caller can read: every
   * accessor of them throws while the refusal is present.
   */
  private static VarkaKernelPlan refused(Refusal refusal) {
    return new VarkaKernelPlan(Optional.of(refusal), LaneType.INT, 0, false, Set.of(), Map.of(),
        0);
  }

  // -------------------------------------------------------------------------------------------
  // The drive lane
  // -------------------------------------------------------------------------------------------

  /**
   * The lane an emission with this root drives its loop at: the long lane wherever the root's
   * tree holds a node on it, and the int lane otherwise. A tree of one lane drives at that lane.
   * A tree that holds both - a {@link NarrowLane} root over a long computation, a
   * {@link WidenLane} under a long one, or a narrowing below another node - is driven at the long
   * lane, one row a long lane per iteration, and its int-lane values occupy the low half of the
   * int species of the same width (`VARKA-28.md` 9).
   *
   * <p>A tree whose every value is narrow drives at the narrow lane under
   * {@link VarkaEmitOptions#narrowLanes()}, the default, and at the int lane with it off, which
   * widens the values once at their loads: the IR says the tree computes {@code TINYINT} values,
   * and the option says which species the emitter names for them. A tree that holds both narrow
   * lanes has no single narrow species and drives at the int lane either way.
   *
   * <p>This is one root's preference. A kernel of several roots is driven at
   * {@link #kernelLane}, which the plan's {@link #drive()} is, so that every consumer reads one
   * lane rather than a root's {@code laneType()}.
   */
  static LaneType emissionLane(VarkaVectorIR root, VarkaEmitOptions options) {
    Set<LaneType> lanes = VarkaVectorIR.lanesIn(root);
    // A floating tree holds its one lane: no node converts between a floating lane and another
    // yet, and the IR's constructors refuse a mix, so the tree drives where its values are.
    if (lanes.contains(LaneType.DOUBLE)) {
      return LaneType.DOUBLE;
    }
    if (lanes.contains(LaneType.FLOAT)) {
      return LaneType.FLOAT;
    }
    if (lanes.contains(LaneType.LONG)) {
      return LaneType.LONG;
    }
    if (lanes.contains(LaneType.INT) || !options.narrowLanes()
        || holdsCheckedNarrowMultiply(root)) {
      return LaneType.INT;
    }
    // A tree that mixes the two narrow lanes has no single narrow species, and converting
    // between them is a node the compiler does not build for this row: it runs on the int lane,
    // as every narrow tree does under WIDENED. `lanes` is never empty.
    if (lanes.contains(LaneType.SHORT) && lanes.contains(LaneType.BYTE)) {
      return LaneType.INT;
    }
    return lanes.contains(LaneType.SHORT) ? LaneType.SHORT : LaneType.BYTE;
  }

  /**
   * The lane a kernel of these roots drives its loop at, or empty when no one lane serves them
   * all: the lane every root's {@link #emissionLane} names where they agree, and the int lane
   * where they differ but none is the long lane. Every narrow tree has a lowering at the int lane
   * - the widened one, which the off arm uses for all of them - so a native narrow root beside
   * an int one, beside a root of the other narrow lane, or beside a tree holding a checked narrow
   * multiply joins them there rather than splitting the projection. A narrow or int root beside a
   * long one has no shared lane: the long lane admits no narrow value, and an int root is a long
   * kernel's only through a conversion its own tree holds; such a root takes a kernel of its own
   * (VARKA-480).
   */
  private static Optional<LaneType> kernelLane(List<VarkaVectorIR> roots,
      VarkaEmitOptions options) {
    Set<LaneType> lanes = EnumSet.noneOf(LaneType.class);
    for (VarkaVectorIR root : roots) {
      lanes.add(emissionLane(root, options));
    }
    return lanesShare(lanes);
  }

  /**
   * The lane roots of these emission lanes share: theirs where one, the int lane but for long. A
   * floating root shares with no root of another lane: it would join one only through a mask
   * crossing between the kinds, which is not built (VARKA-341 2.4), so it takes a kernel of its
   * own.
   */
  private static Optional<LaneType> lanesShare(Set<LaneType> lanes) {
    if (lanes.size() <= 1) {
      return lanes.stream().findFirst();
    }
    return lanes.contains(LaneType.LONG) || lanes.stream().anyMatch(LaneType::isFloating)
        ? Optional.empty() : Optional.of(LaneType.INT);
  }

  /**
   * Whether {@code root}'s tree holds a checked narrow multiply - an {@code ANSI} or
   * {@code TRY} {@link IntOp#MUL} over {@code TINYINT} or {@code SMALLINT} operands.
   *
   * <p>Such a tree is driven at the int lane under either narrow arm. The check needs the exact
   * product, and the Vector API cannot widen a full narrow species into the next one: a
   * {@code B2S} conversion takes the source's lanes in targets of the target species' width, so a
   * 64-lane {@code ByteVector} at 512 bits has no 64-lane {@code ShortVector} to convert into,
   * and the check would have to be taken part by part with the masks recombined across species.
   * The int lane has the exact product of two narrow operands for free - a byte product is at
   * most 2^14 and a short one at most 2^30 - so one int multiply and the node lane's own range
   * compare is the check ({@code VARKA-329.md} 4.2). The native arm therefore serves the
   * wrapping multiply and takes its checked form through the widened lowering; the benchmark
   * reports what that costs.
   */
  private static boolean holdsCheckedNarrowMultiply(VarkaVectorIR root) {
    return holdsCheckedNarrowMultiply(root, new HashSet<>());
  }

  private static boolean holdsCheckedNarrowMultiply(VarkaVectorIR node, Set<VarkaVectorIR> seen) {
    if (!seen.add(node)) {
      return false;
    }
    if (node instanceof IntArith n && n.op() == IntOp.MUL && n.mode() != Overflow.WRAP
        && n.laneType().isNarrow()) {
      return true;
    }
    for (VarkaVectorIR child : VarkaVectorIR.childrenOf(node)) {
      if (holdsCheckedNarrowMultiply(child, seen)) {
        return true;
      }
    }
    return false;
  }

  // -------------------------------------------------------------------------------------------
  // The refusals
  // -------------------------------------------------------------------------------------------

  /**
   * Why this kernel cannot be emitted over the narrow values it holds, judged at the kernel's
   * lane, or at each root's own where no lane serves them all (the lane check refuses that on
   * its own).
   *
   * <p>Under {@link VarkaEmitOptions#narrowLanes()} a kernel whose every value is narrow computes
   * in the narrow species itself, so nothing has to be converted. Otherwise a narrow column is
   * read with one widening load per iteration, and that load needs a narrow species wider than
   * or as wide as the int group: the narrow species of the int species' width where it exists,
   * and otherwise the next width up, whose parts each become one int group
   * ({@link Lane#widenLanes}). A width with no baked lane count - {@code validityByWidth} off -
   * cannot name the species at all, and the entry declines: the row engine answers it.
   */
  private static Optional<Refusal> narrowRefusal(List<VarkaVectorIR> roots,
      Optional<LaneType> kernel, VarkaEmitOptions options) {
    for (VarkaVectorIR root : roots) {
      Set<LaneType> lanes = VarkaVectorIR.lanesIn(root);
      boolean narrow = lanes.contains(LaneType.BYTE) || lanes.contains(LaneType.SHORT);
      if (!narrow) {
        continue;
      }
      LaneType drive = kernel.orElseGet(() -> emissionLane(root, options));
      if (drive.isNarrow()) {
        continue;
      }
      if (!VarkaVectorIR.needsNarrowSpecies(root)) {
        // A narrow column whose only use is a validity word - `y1 IS NOT NULL`, or the same
        // test lifted to a bigint kernel's lane - reads no value and needs no narrow species,
        // so it emits at the kernel's lane whichever that is: the word is one bit per row at
        // every lane. Refusing it would name a load the tree never emits.
        continue;
      }
      if (drive == LaneType.LONG) {
        // Analysis admits no narrow value in a long-lane emission, so the entry declines here
        // rather than failing at emit.
        return Optional.of(new Refusal(Kind.NARROW,
            "a narrow value under a bigint kernel has no lowering"));
      }
      int intLanes = Lane.emitLanes(options, Lane.INT);
      if (intLanes == 0) {
        return Optional.of(new Refusal(Kind.NARROW,
            "a narrow column needs a baked int lane count to be widened"));
      }
      for (LaneType type : lanes) {
        if (type.isNarrow() && Lane.widenLanes(intLanes, Lane.of(type)) == 0) {
          return Optional.of(new Refusal(Kind.NARROW,
              "a " + type + " column has no widening load at " + intLanes + " int lanes"));
        }
      }
    }
    return Optional.empty();
  }

  /**
   * The refusal of a filter's conjunct on a floating lane, where the filter also reads an
   * integral column: {@code x > 1.5 AND d > d2}. A filter kernel's conjuncts meet on one lane,
   * and a floating conjunct fused first would leave the date or bigint conjuncts beside it no
   * lane to meet on and send them to the row filter. So it declines, keeping the kernel for the
   * integral conjuncts, until a mask crosses between the kinds. A filter that reads only
   * floating columns fuses whole, and a choice's floating condition is no conjunct, so a
   * projection's {@code CASE WHEN x > 1.5 THEN ...} fuses too.
   */
  public static Optional<Refusal> floatingConjunctRefusal(VarkaVectorIR conjunct,
      boolean filterReadsIntegral) {
    if (filterReadsIntegral && conjunct.laneType().isFloating()) {
      return Optional.of(new Refusal(Kind.FLOATING_CONDITION, "a condition on the "
          + conjunct.laneType() + " lane in a filter that also reads integral columns: its "
          + "kernel meets on an integral lane, and a mask crossing from a floating lane is not "
          + "built"));
    }
    return Optional.empty();
  }

  /**
   * The refusal of roots no one lane serves: the first root that no lane serves beside the roots
   * before it, which is the entry the compiler is admitting when it asks, named with its own lane
   * and the lane of the kernel it would join. One walk: each root's lane is taken once.
   */
  private static Refusal laneMismatch(List<VarkaVectorIR> roots, VarkaEmitOptions options) {
    Set<LaneType> before = EnumSet.noneOf(LaneType.class);
    for (VarkaVectorIR root : roots) {
      LaneType entry = emissionLane(root, options);
      Set<LaneType> with = EnumSet.copyOf(before);
      with.add(entry);
      if (lanesShare(with).isEmpty()) {
        return new Refusal(Kind.LANE_MISMATCH,
            laneMismatchReason(entry, lanesShare(before).orElseThrow()));
      }
      before = with;
    }
    throw VarkaErrors.illegalState("no lane serves " + roots + ", and every prefix has one");
  }

  /**
   * The reason an entry of lane {@code entry} records when the kernel it would join is on
   * {@code kernel}: the plan's, and the predicate path's for a conjunct of the other lane.
   */
  public static String laneMismatchReason(LaneType entry, LaneType kernel) {
    return "the " + entry + " lane in a kernel on the " + kernel
        + " lane: one kernel holds one lane";
  }

  /**
   * The lanes roots no one lane serves would each drive at, for the emitter's refusal of a
   * caller that hands it such roots: the compiler declines them before they reach it.
   */
  static String mixedLanes(List<VarkaVectorIR> roots, VarkaEmitOptions options) {
    return "outputs mix lanes: " + roots.stream()
        .map(o -> emissionLane(o, options).toString()).distinct()
        .collect(java.util.stream.Collectors.joining(" and "));
  }

  // -------------------------------------------------------------------------------------------
  // The lanes
  // -------------------------------------------------------------------------------------------

  /**
   * How many int lane groups one iteration computes: the widest part count any narrow value the
   * roots read loads at, so that a kernel driven at four int lanes reads a byte column as an
   * eight-lane 64-bit species and converts it in two parts. One for a kernel not driven at the
   * int lane, or with no baked lane count.
   */
  private static int narrowGroups(List<VarkaVectorIR> roots, Lane lane, int lanes) {
    if (lane != Lane.INT || lanes == 0) {
      return 1;
    }
    int groups = 1;
    for (VarkaVectorIR root : roots) {
      for (LaneType type : VarkaVectorIR.narrowValuesIn(root)) {
        groups = Math.max(groups, Lane.widenLanes(lanes, Lane.of(type)) / lanes);
      }
    }
    return groups;
  }

  /** The reason no kernel of {@code roots} can be emitted, or empty when one can. */
  static Optional<String> refusalOf(List<VarkaVectorIR> roots, VarkaEmitOptions options) {
    return of(roots, options).refusal().map(Refusal::reason);
  }

  /** The lane a kernel of {@code roots} drives at, or empty when none can be emitted. */
  static Optional<LaneType> driveOf(List<VarkaVectorIR> roots, VarkaEmitOptions options) {
    VarkaKernelPlan plan = of(roots, options);
    return plan.refusal().isPresent() ? Optional.empty() : Optional.of(plan.drive());
  }

  /** Why no kernel of these roots can be emitted, or empty when one can. */
  public Optional<Refusal> refusal() {
    return refusal;
  }

  /** The lane the kernel's loop drives at, which picks the {@code run} overload. */
  public LaneType drive() {
    requireEmittable();
    return drive;
  }

  /** {@link #drive()} as the emitter's lane: the species, descriptors and helpers it names. */
  Lane lane() {
    requireEmittable();
    return lane;
  }

  /** The lane count baked for the drive lane's species, or 0 to read it at run time. */
  int lanes() {
    requireEmittable();
    return lanes;
  }

  /** Whether the kernel converts between lanes: a long-driven kernel holding int values. */
  boolean mixed() {
    requireEmittable();
    return mixed;
  }

  /**
   * Whether {@code node} is a narrowing that stays in the long lane until its root's store: an
   * output root that no other node reads.
   */
  boolean narrowsAtStore(VarkaVectorIR node) {
    requireEmittable();
    return storeNarrowings.contains(node);
  }

  /**
   * The lane {@code node}'s value is emitted in: the drive lane for every node of a single-lane
   * kernel, and in a kernel that converts between lanes the node's own, a narrowing at its store
   * excepted.
   */
  Lane laneOf(VarkaVectorIR node) {
    requireEmittable();
    if (!mixed || storeNarrowings.contains(node)) {
      return lane;
    }
    return Lane.of(node.laneType());
  }

  /** The lane input {@code ordinal} is read at, which sizes its data segment. */
  Lane inputLane(int ordinal) {
    requireEmittable();
    LaneType laneType = inputLanes.get(ordinal);
    return laneType == null ? lane : Lane.of(laneType);
  }

  /**
   * The lane count baked for {@code of}'s species, or 0 where none is: {@link #lanes()} for the
   * drive lane, and for the int lane of a kernel driven at the long one the int species of the
   * same width, twice the long lanes.
   */
  int lanesOf(Lane of) {
    requireEmittable();
    if (of == lane) {
      return lanes;
    }
    return lanes == 0 ? 0 : lanes * 2;
  }

  /** The int groups one iteration computes; see {@link #narrowGroups(List, Lane, int)}. */
  int narrowGroups() {
    requireEmittable();
    return narrowGroups;
  }

  private void requireEmittable() {
    if (refusal.isPresent()) {
      throw VarkaErrors.illegalState("a refused kernel has no lanes: " + refusal.get().reason());
    }
  }
}
