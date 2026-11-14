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

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.BoolOutput;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConvertLane;
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
    /** A conversion this part of row 343 does not lower: from BIGINT, or to an integral lane. */
    CONVERSION,
    /**
     * A condition root of a kernel driven at a 64-bit lane, {@code LONG} or {@code DOUBLE}, or a
     * boolean output over a decimal column, on an
     * x86 host whose 64-bit species have two lanes or fewer and whose masks C2 does not lower
     * ({@link #TWO_LANE_CONDITION_REASON} names the column types): a refusal
     * of the host, not of the roots, so the compiler asks for it at admission ({@link #admit})
     * and {@link #of} never makes it.
     */
    TWO_LANE_CONDITION
  }

  private final Optional<Refusal> refusal;
  private final LaneType drive;
  private final Lane lane;
  private final int lanes;
  private final boolean mixed;
  private final Set<VarkaVectorIR> storeNarrowings;
  private final Map<Integer, LaneType> inputLanes;
  private final int narrowGroups;
  private final Set<LaneType> otherLanes;

  private VarkaKernelPlan(Optional<Refusal> refusal, LaneType drive, int lanes, boolean mixed,
      Set<VarkaVectorIR> storeNarrowings, Map<Integer, LaneType> inputLanes, int narrowGroups,
      Set<LaneType> otherLanes) {
    this.refusal = refusal;
    this.drive = drive;
    this.lane = Lane.of(drive);
    this.lanes = lanes;
    this.mixed = mixed;
    this.storeNarrowings = storeNarrowings;
    this.inputLanes = inputLanes;
    this.narrowGroups = narrowGroups;
    this.otherLanes = otherLanes;
  }

  /** The plan of a kernel of {@code roots} under {@code options}. */
  public static VarkaKernelPlan of(List<VarkaVectorIR> roots, VarkaEmitOptions options) {
    if (roots.isEmpty()) {
      throw VarkaErrors.illegalArgument("no output chains to plan");
    }
    Optional<Refusal> conversion = conversionRefusal(roots, options);
    if (conversion.isPresent()) {
      return refused(conversion.get());
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
    // A kernel of two widths: driven at 64 bits with a 32-bit value that is emitted as a vector
    // (every one but a narrowing root that nothing else reads). And the lanes other than the
    // drive's that some node is emitted at, of either width: a narrow value is not one, since it
    // is widened at its load or read for its validity alone.
    boolean mixed = false;
    Set<LaneType> others = EnumSet.noneOf(LaneType.class);
    for (VarkaVectorIR node : nodes) {
      LaneType type = node.laneType();
      if (storeNarrowings.contains(node) || type == drive || type.isNarrow()) {
        continue;
      }
      others.add(type);
      mixed |= drive.bits() == Long.SIZE && type.bits() == Integer.SIZE;
    }
    if (lane.bits == Long.SIZE) {
      // A narrow value fills the int species of the 64-bit species' width, as an int one does,
      // so the kernel converts between lanes (VARKA-332, and VARKA-343 at the double lane): a
      // narrow column read as a value, and any other narrow node but a narrowing at its store.
      // A column read for its word alone is not.
      boolean narrowMixed = false;
      for (VarkaVectorIR root : roots) {
        narrowMixed |= !VarkaVectorIR.narrowValuesIn(root).isEmpty();
      }
      for (VarkaVectorIR node : nodes) {
        narrowMixed |= node.laneType().isNarrow() && !(node instanceof VarkaVectorIR.ColumnRef)
            && !storeNarrowings.contains(node);
      }
      // A narrowing to a narrow type at its store makes the kernel convert between lanes only
      // where its loop body can then store the narrow species whole: the loop bound of such a
      // kernel leaves room for one int group of the long width past the lane group, and the
      // store fits it where the lane count is baked and the narrow species of the int group's
      // width exists. A byte at two long lanes has none - it would be 32 bits - and an unbaked
      // width names the preferred species, not the int group's own, so there the store is masked
      // whatever the kernel is, and the kernel stays single-lane rather than pay the conversions
      // for nothing.
      for (VarkaVectorIR node : storeNarrowings) {
        narrowMixed |= node.laneType().isNarrow()
            && wholeNarrowStoreFits(Lane.of(node.laneType()), lanes);
      }
      // Such a kernel emits at the int species beside the drive's, as one of int values does.
      if (narrowMixed) {
        mixed = true;
        others.add(LaneType.INT);
      }
    }
    return new VarkaKernelPlan(Optional.empty(), drive, lanes, mixed, Set.copyOf(storeNarrowings),
        Map.copyOf(inputLanes), narrowGroups(roots, lane, lanes, options), Set.copyOf(others));
  }

  /**
   * Whether a narrow store of {@code stored} in a kernel at {@code longLanes} baked long lanes
   * fills exactly one int group of the long width: the lane count is baked, and the narrow
   * species of twice the long lanes exists ({@link Lane#widenLanes} names the next width up
   * where it does not).
   */
  private static boolean wholeNarrowStoreFits(Lane stored, int longLanes) {
    return longLanes != 0 && Lane.widenLanes(2 * longLanes, stored) == 2 * longLanes;
  }

  /**
   * A plan with no lanes. Its lane fields hold placeholders that no caller can read: every
   * accessor of them throws while the refusal is present.
   */
  private static VarkaKernelPlan refused(Refusal refusal) {
    return new VarkaKernelPlan(Optional.of(refusal), LaneType.INT, 0, false, Set.of(), Map.of(),
        0, Set.of());
  }

  // -------------------------------------------------------------------------------------------
  // The host's refusal
  // -------------------------------------------------------------------------------------------

  /** The reason a filter's condition records where {@link #hostRefusal} refuses it. */
  public static final String TWO_LANE_CONDITION_REASON = "a filter's condition in a kernel driven "
      + "at a 64-bit lane (BIGINT, DOUBLE, TIMESTAMP_NTZ, TIME, day-time interval, DECIMAL), on "
      + "an x86 host whose 64-bit species have two lanes or fewer: C2 lowers no mask there, and "
      + "the selection would run the Vector API's Java fallback";

  /** The reason a projection's boolean output records where {@link #hostRefusal} refuses it. */
  public static final String TWO_LANE_DECIMAL_VALUE_REASON = "a condition over a DECIMAL column "
      + "written as a BOOLEAN output, on an x86 host whose 64-bit species have two lanes or fewer: "
      + "C2 lowers no mask or two-vector rearrange there, so the decimal slots' load and check "
      + "and the compare would run the Vector API's Java fallback, slower than the row engine";

  /**
   * Whether {@code host}, read on this JVM, runs every mask of a 64-bit species as the Vector
   * API's Java fallback: {@link #twoLaneMasksFallBack(VarkaHostIsa.Reading, int)} of the reading
   * and this JVM's preferred long species. The lane count is the running JVM's own,
   * as the species the kernels are emitted at is, so a planted reading changes the architecture
   * alone; the long species stands for the {@code DOUBLE} one, whose preferred species has as
   * many lanes at every width. A host that is not x86 answers at once, before the lane count is
   * compared. Public for the session in sql/core, which resolves its default here from the
   * reading it resolves its level from, and which cannot reach the lanes itself.
   */
  public static boolean twoLaneMasksFallBack(VarkaHostIsa.Reading host) {
    return host.x86() && twoLaneMasksFallBack(host, Lane.LONG.preferredLanes);
  }

  /**
   * Whether {@code host}, with this many lanes in its preferred long species, runs every mask of
   * a 64-bit species, {@code LONG} or {@code DOUBLE}, as the Vector API's Java fallback. Public so
   * that a test in sql/core can state the answer for a lane count its own JVM does not have.
   *
   * <p>C2 on x86 refuses {@code VectorLoadMask}, {@code VectorStoreMask} and
   * {@code VectorMaskCast} at {@code vlen == 2} whatever the instruction set and whatever the
   * element type ({@code match_rule_supported_vector} in {@code x86.ad}), so at two 64-bit lanes
   * every compare to a mask, mask logic, blend and mask-to-word call is a per-lane Java loop
   * that allocates ({@code m7/VARKA-374.md} 2: the same 18-fold loss under {@code UseAVX=1},
   * {@code 2} and {@code 3} capped to 16 bytes). So the test is not the AVX level, as
   * {@link VarkaEmitOptions#convertsFallBack} is for the conversions, but "x86 at two 64-bit
   * lanes or fewer", x86 as the host reading's {@code os.arch} names it
   * ({@link VarkaHostIsa#isX86}): under {@code -XX:MaxVectorSize=8} the preferred long species
   * has one lane, and C2 has no vector of one element to lower its masks to either, while
   * aarch64's back end lowers two-lane masks, and nothing is refused there. The count is the
   * host's preferred species and never an emit option's {@code lanesOverride}, so a suite that
   * emits two long lanes on a wider host still runs them, as answer checks.
   */
  public static boolean twoLaneMasksFallBack(VarkaHostIsa.Reading host, int preferredLongLanes) {
    return host.x86() && preferredLongLanes <= 2;
  }

  /**
   * Why no kernel of {@code roots} is admitted, or empty when one is: the plan's refusal, then,
   * where {@code twoLaneMasksFallBack} says the host's 64-bit masks fall back, the host's
   * ({@link #hostRefusal}). The predicate compiler's admission and the projection's entry
   * admission ask this, with the flag the session resolved; the emitter and a compiled
   * projection ask {@link #of}, which never refuses for the host.
   */
  public static Optional<Refusal> admit(List<VarkaVectorIR> roots, VarkaEmitOptions options,
      boolean twoLaneMasksFallBack) {
    VarkaKernelPlan plan = of(roots, options);
    if (plan.refusal.isPresent()) {
      return plan.refusal;
    }
    return twoLaneMasksFallBack ? hostRefusal(roots, plan) : Optional.empty();
  }

  /**
   * The refusal of a filter's 64-bit-lane condition on a host whose 64-bit masks fall back
   * (VARKA-503): a condition root - a filter's conjunct or a selection output - of a kernel
   * driven at the long or the {@code DOUBLE} lane, whether the compare itself is on the long lane
   * ({@code l < l2}, {@code i < l}, {@code l IS NOT NULL}) or on the int half of a kernel that
   * reads a long column ({@code hour(t) = 12}, {@code CAST(l AS INT) < i}). The key is the drive
   * lane and not the lane of the condition's own mask, because a long-driven kernel makes long
   * masks of its own that no condition node names: a narrowing's range guard compares at the long
   * lane, and
   * {@code CAST(l AS INT) < i}'s dense body at two long lanes under {@code -XX:UseAVX=2} calls
   * the long compare, mask {@code or} and {@code VectorMask.fromLong} as the Java fallback
   * ({@code m8/VARKA-503.md} 10). So on such a host every conjunct that reads a long column
   * declines, Spark's inferred {@code IS NOT NULL} of a nullable long column among them, and a
   * filter over long columns alone declines whole. A kernel driven at the {@code DOUBLE} lane is
   * refused alike ({@code x < x2}, {@code x > 1.5D}, {@code x IS NOT NULL}): the refusal in
   * {@code x86.ad} does not depend on the element type, and the fused {@code DOUBLE} filters ran
   * at 0.4X to 0.7X of the row path at two lanes, with and without nulls, against 1.6X to 2.2X at
   * four ({@code m8/VARKA-503.md} 12). A {@code FLOAT} kernel has four lanes there and is
   * admitted.
   *
   * <p>The key being the drive lane, the column types refused are every type the registry reads
   * on a 64-bit lane ({@link VarkaTypes#lane}): {@code BIGINT}, {@code TIMESTAMP_NTZ},
   * {@code TIME}, the day-time intervals and a {@code DECIMAL} of up to 18 digits on the long
   * lane, {@code DOUBLE} on its own. Only {@code BIGINT} and {@code DOUBLE} were timed; the others
   * are refused by the mechanism, since their compares and null tests are the same long-lane
   * masks. The zoned {@code TIMESTAMP} is not among them only because no lane reads it yet.
   *
   * <p>Only the condition roots, and not every kernel that makes a long mask, because that is
   * where the measurement put the loss. At two long lanes under {@code -XX:UseAVX=2} the long
   * filters - {@code l < l2}, {@code l > 500000000000}, {@code i < l} and
   * {@code (d < d2) AND (l < l2)} - ran at 0.5X to 0.7X of Spark's row path on batches without
   * nulls, while every projection that blends on a long compare ({@code CASE WHEN l < l2},
   * {@code greatest(l, l2)}, {@code if(l IS NULL, l2, l)}) ran at 1.3X to 5.6X, its fallback
   * masks cheaper than the row path's per-row work ({@code m8/results/VARKA-503/}). With nulls the
   * filters won, because the masked body carries a condition as words rather than masks; the
   * refusal gives that up for the dense body's loss. A refused conjunct leaves the rest of the
   * filter to fuse: {@code d < d2} beside it stays an int kernel, and the long conjunct is the
   * row filter's.
   *
   * <p>A projection's {@code BOOLEAN} output, a condition written as a value
   * ({@link VarkaVectorIR.BoolOutput}), is refused only where its condition reads a decimal
   * column (VARKA-596), since that is where its loss was measured. Every decimal is read from
   * Arrow's sixteen-byte slots by two rearranges of two vectors of slots and a check of each slot,
   * which at two long lanes run as the fallback with the compares: under
   * {@code -XX:UseAVX=2 -XX:MaxVectorSize=16} {@code x < y} as a value ran at about 0.2X of the
   * row path at {@code DECIMAL(9, 2)} and at 0.8X at {@code DECIMAL(38, 10)} and
   * {@code DECIMAL(20, 2)}, on the EPYC 7763 as on the laptop, while {@code l < l2} over
   * {@code BIGINT}, whose load is one plain vector, ran at 1.6X and more, and {@code x < x2} over
   * {@code DOUBLE} tied ({@code m8/results/VARKA-596/}). A refused output leaves the other entries
   * to fuse.
   */
  private static Optional<Refusal> hostRefusal(List<VarkaVectorIR> roots, VarkaKernelPlan plan) {
    if (plan.drive.bits() != 64) {
      return Optional.empty();
    }
    for (VarkaVectorIR root : roots) {
      if (root instanceof VarkaVectorIR.Cond) {
        return Optional.of(new Refusal(Kind.TWO_LANE_CONDITION, TWO_LANE_CONDITION_REASON));
      }
      if (root instanceof VarkaVectorIR.BoolOutput && readsDecimal(root, new HashSet<>())) {
        return Optional.of(new Refusal(Kind.TWO_LANE_CONDITION, TWO_LANE_DECIMAL_VALUE_REASON));
      }
    }
    return Optional.empty();
  }

  /**
   * Whether {@code node}'s tree reads a decimal column, on the long lane or as the lane pair's
   * words. {@code seen} holds the nodes already walked, so a shared subtree is walked once.
   */
  private static boolean readsDecimal(VarkaVectorIR node, Set<VarkaVectorIR> seen) {
    if (node instanceof VarkaVectorIR.DecimalColumn || node instanceof VarkaVectorIR.DecimalWord) {
      return true;
    }
    if (!seen.add(node)) {
      return false;
    }
    for (VarkaVectorIR child : VarkaVectorIR.childrenOf(node)) {
      if (readsDecimal(child, seen)) {
        return true;
      }
    }
    return false;
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
    // The width rule (`VARKA-343.md` 2.2): a tree holding a 64-bit value drives at 64 bits, at
    // the long lane if it holds one and at the double lane otherwise, and a tree of 32-bit values
    // drives at the int lane if it holds one and at the float lane otherwise. The floating lanes
    // meet the others only through a ConvertLane, so a tree of one floating lane drives there.
    if (lanes.contains(LaneType.LONG)) {
      return LaneType.LONG;
    }
    if (lanes.contains(LaneType.DOUBLE)) {
      return LaneType.DOUBLE;
    }
    if (lanes.contains(LaneType.FLOAT) && !lanes.contains(LaneType.INT)) {
      return LaneType.FLOAT;
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
   * The lane roots of these emission lanes share, joining roots of one width
   * (`VARKA-343.md` 2.2.1): theirs where one; the int lane for any mix of the 32-bit and narrow
   * lanes, a {@code FLOAT} root's values filling the float species of the int one's width; the
   * long lane for a {@code LONG} root beside a {@code DOUBLE} one, likewise. A 32-bit or narrow
   * root beside a 64-bit one shares none and takes a kernel of its own (VARKA-480): each root's
   * tree chooses its own drive lane ({@link #emissionLane}), so a root with no 64-bit node never
   * joins a 64-bit kernel, though such a kernel stores the 32-bit roots its converting trees give
   * from their species' low half ({@code VarkaBodyEmitter.emitLowHalfStore}). The rule is
   * monotone - a set that shares no lane has no superset that does - which {@link #laneMismatch}
   * relies on.
   */
  private static Optional<LaneType> lanesShare(Set<LaneType> lanes) {
    if (lanes.size() <= 1) {
      return lanes.stream().findFirst();
    }
    boolean wide = lanes.contains(LaneType.LONG) || lanes.contains(LaneType.DOUBLE);
    if (!wide) {
      return Optional.of(LaneType.INT);
    }
    return lanes.stream().allMatch(l -> l.bits() == Long.SIZE)
        ? Optional.of(LaneType.LONG) : Optional.empty();
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
   * The reason a narrow value read or stored by an int-lane kernel declines at a width with no
   * baked int lane count, so the narrow species it needs has no name: {@code validityByWidth}
   * off.
   */
  public static final String NO_BAKED_INT_LANES =
      "a narrow column needs a baked int lane count to be widened";

  /**
   * The reason a narrow column loaded by a kernel driven at the long lane declines at a width
   * with no baked long lane count: {@code validityByWidth} off, or a {@code lanesOverride} with
   * no long species, such as sixteen lanes, 1024 bits. A filter's decline adds the conjunct.
   */
  public static final String NO_BAKED_LONG_LANES =
      "a narrow column needs a baked lane count to be widened to the long lane";

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
      if (drive.isFloating() && !(drive == LaneType.DOUBLE && narrowColumnsOnly(root))) {
        // A floating kernel reads a narrow value only through its int conversion, and a tree
        // holding one drives at the int lane unless it holds a 64-bit value. A double kernel
        // loads a narrow column as a long kernel does, widened into the low half of the int
        // species (VARKA-332), so CAST(y AS DOUBLE) fuses; any other narrow node there - a
        // narrowing, narrow arithmetic, a narrow store - has no lowering at the double lane.
        return Optional.of(new Refusal(Kind.NARROW,
            "a narrow value under a " + drive + " kernel has no lowering (row 343)"));
      }
      // A narrow-typed root stores through the narrow species whatever it reads: a boolean
      // column's value as a TINYINT reads no narrow column, and still stores a byte a row.
      boolean narrowStore = root.laneType().isNarrow() && !(root instanceof VarkaVectorIR.Cond)
          && !(root instanceof VarkaVectorIR.BoolOutput);
      if (!VarkaVectorIR.needsNarrowSpecies(root) && !narrowStore) {
        // A narrow column whose only use is a validity word - `y1 IS NOT NULL`, or the same
        // test lifted to a bigint kernel's lane - reads no value and needs no narrow species,
        // so it emits at the kernel's lane whichever that is: the word is one bit per row at
        // every lane. Refusing it would name a load the tree never emits.
        continue;
      }
      if (drive.bits() == Long.SIZE) {
        Optional<Refusal> atLong = longNarrowRefusal(root, options);
        if (atLong.isPresent()) {
          return atLong;
        }
        continue;
      }
      int intLanes = Lane.emitLanes(options, Lane.INT);
      if (intLanes == 0) {
        return Optional.of(new Refusal(Kind.NARROW, NO_BAKED_INT_LANES));
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

  /** Whether {@code n} tests a narrow column, bare or widened, and nothing else narrow. */
  private static boolean readsColumnWord(VarkaVectorIR.IsNotNull n) {
    VarkaVectorIR child = n.child() instanceof VarkaVectorIR.WidenLane w ? w.child() : n.child();
    return child instanceof VarkaVectorIR.ColumnRef;
  }

  /**
   * Whether every narrow node of {@code root} is a column, read as a value or for its validity
   * word alone, and the root itself is not narrow: the narrow nodes a double kernel lowers, a
   * value through the long kernel's narrow load and a null test through the word, a bit per row
   * at every lane. The test is {@code IS NOT NULL} of a narrow column, as the optimizer infers
   * it on a cast's source: the column widened to the int lane or bare, and nothing narrow below
   * it but that column.
   */
  private static boolean narrowColumnsOnly(VarkaVectorIR root) {
    if (root.laneType().isNarrow()) {
      return false;
    }
    var seen = new HashSet<VarkaVectorIR>();
    var todo = new ArrayDeque<VarkaVectorIR>(List.of(root));
    while (!todo.isEmpty()) {
      VarkaVectorIR node = todo.pop();
      if (!seen.add(node)) {
        continue;
      }
      if (node.laneType().isNarrow() && !(node instanceof VarkaVectorIR.ColumnRef)) {
        return false;
      }
      if (node instanceof VarkaVectorIR.IsNotNull n
          && VarkaVectorIR.lanesIn(n).stream().anyMatch(LaneType::isNarrow)
          && !readsColumnWord(n)) {
        return false;
      }
      todo.addAll(List.of(VarkaVectorIR.childrenOf(node)));
    }
    return true;
  }

  /**
   * Why a conversion of these roots has no kernel yet. The emitter lowers the four conversions
   * that are one {@code convertShape} on every x86 host, {@code I2F}, {@code I2D}, {@code F2D}
   * and {@code D2F}, in a kernel of one lane count or of two (`VARKA-343.md` 2.2). A
   * {@code BIGINT} source, {@code L2F} or {@code L2D}, takes the AVX2 forms of
   * {@code VarkaConversionLowering} below level 3, where x86 has no rule for either without
   * AVX512DQ; at the unknown level ({@link VarkaEmitOptions#levelUnknown()}), the defaults' and an
   * unset session's, which may be such a host, {@code convertShape} would run as the Vector API's
   * Java loop and the entry declines. A floating source converted to an
   * integral lane waits for part 343b2, at every level. A boolean whose condition compares longs
   * is not a conversion from a long: the compiler narrows its one or zero to the int lane first.
   *
   * <p>The test reads the options' {@code UseAVX} level and not the CPU: the level is part of
   * the shape key, and a plan that read the host would key one shape two ways. The feature
   * reaches it through the level: a session that asks for the host's level resolves a CPU whose
   * HotSpot feature list holds AVX512DQ to level 3 and one that lacks it, an AVX-512 one without
   * DQ included, to level 2 ({@code VarkaSessionEmit.resolve}). An aarch64 host whose list was
   * read resolves to level 3 as well, whatever features it names: its matcher lowers
   * {@code L2D} to one vector {@code scvtf} and {@code L2F} lane by lane to the scalar
   * {@code scvtf} on NEON or SVE's {@code scvtf} from D to S, each a single rounding as Java's
   * cast, so neither AVX2 form is needed there
   * ({@code VarkaHostIsa.Reading.conversionsAreInstructions}).
   */
  private static Optional<Refusal> conversionRefusal(List<VarkaVectorIR> roots,
      VarkaEmitOptions options) {
    var seen = new HashSet<VarkaVectorIR>();
    var todo = new ArrayDeque<VarkaVectorIR>(roots);
    while (!todo.isEmpty()) {
      VarkaVectorIR node = todo.pop();
      if (!seen.add(node)) {
        continue;
      }
      if (node instanceof ConvertLane c) {
        if (!c.target().isFloating()) {
          return Optional.of(new Refusal(Kind.CONVERSION, "a conversion from "
              + c.child().laneType() + " to " + c.target() + " is not built yet (row 343)"));
        }
        if (c.child().laneType() == LaneType.LONG && options.levelUnknown()) {
          return Optional.of(new Refusal(Kind.CONVERSION, "a BIGINT conversion to " + c.target()
              + " at an unknown emit level, which may be a host's without AVX512DQ (row 343)"));
        }
      }
      for (VarkaVectorIR child : VarkaVectorIR.childrenOf(node)) {
        todo.push(child);
      }
    }
    return Optional.empty();
  }

  /**
   * Why a kernel driven at the long lane cannot lower the narrow values of {@code root}
   * (`VARKA-332.md` 2.5). A long kernel emits a narrow value at the int lane, in the int species
   * of the long species' width, low half, as it emits an int one, so it lowers the narrow nodes
   * that need nothing narrow-specific there: a {@code TINYINT} or {@code SMALLINT} column - the
   * narrow load at the long width, read into the int species; an int or long value narrowed to a
   * narrow type, the shift pair's wrap at the int lane after {@code L2I} for a long one, or at a
   * root nothing else reads the narrow store; and a comparison of such values, with the
   * connectives and the boolean output over it. A value may be read by a widening, a null test,
   * a comparison or a narrowing, and a narrow-typed root may be a narrowing of a narrow value,
   * which the narrow store takes as it is.
   * Any other narrow node under a long kernel - a narrow literal, narrow arithmetic, a narrow
   * choice - has no lowering there and declines. The load reads as
   * many narrow lanes as the int species has, twice the long lanes, where the Vector API has that
   * species; a byte column at two long lanes would need a 32-bit species, so the loop body reads
   * its rows one at a time as the epilogue does ({@code VarkaVectorWalk.narrowRowsInBody},
   * VARKA-575). A width with no baked lane count declines, as before.
   */
  private static Optional<Refusal> longNarrowRefusal(VarkaVectorIR root,
      VarkaEmitOptions options) {
    if (!loweredAtLong(root)) {
      return Optional.of(new Refusal(Kind.NARROW,
          "a narrow value under a bigint kernel has no lowering"));
    }
    int longLanes = Lane.emitLanes(options, Lane.LONG);
    Set<LaneType> loads = VarkaVectorIR.narrowValuesIn(root);
    if (longLanes == 0 && !loads.isEmpty()) {
      return Optional.of(new Refusal(Kind.NARROW, NO_BAKED_LONG_LANES));
    }
    // A narrow species of twice the long lanes may not exist, as a byte's does not at two long
    // lanes: the loop body then reads the column one row at a time, as the epilogue does
    // (VarkaVectorWalk.narrowRowsInBody, VARKA-575).
    return Optional.empty();
  }

  /**
   * Whether every narrow node of {@code root} is one {@link #longNarrowRefusal} names, read by a
   * node it names: the root by {@link LongNarrowRole#atRoot}, every other narrow node by what its
   * reader takes ({@link #longNarrowReader}).
   */
  private static boolean loweredAtLong(VarkaVectorIR root) {
    if (root.laneType().isNarrow() && !longNarrowRole(root).atRoot()) {
      return false;
    }
    var seen = new HashSet<VarkaVectorIR>();
    var todo = new ArrayDeque<VarkaVectorIR>(List.of(root));
    while (!todo.isEmpty()) {
      VarkaVectorIR node = todo.pop();
      if (!seen.add(node)) {
        continue;
      }
      LongNarrowReader reader = longNarrowReader(node);
      for (VarkaVectorIR child : VarkaVectorIR.childrenOf(node)) {
        if (child.laneType().isNarrow() && !reader.takes(longNarrowRole(child))) {
          return false;
        }
        todo.push(child);
      }
    }
    return true;
  }

  /**
   * What a narrow-typed node is in a kernel driven at the long lane (`VARKA-332.md` 2.5), the one
   * classification both this plan's refusal ({@link #longNarrowRefusal}) and {@code Analysis}'
   * lane rule read: the plan asks whether each narrow node is read by a node that takes it, the
   * analysis whether a node may sit in a long emission at all.
   */
  enum LongNarrowRole {
    /** A {@code TINYINT} or {@code SMALLINT} column: the narrow load at the long width. */
    COLUMN,
    /**
     * A narrowing of an int or long value to a narrow type: the shift pair's wrap at the int lane,
     * after {@code L2I} where the child is long, or at a root the narrow store. Lowered wherever a
     * value is read.
     */
    NARROWING,
    /**
     * A narrowing of a narrow value ({@code CAST(s AS TINYINT)}): the narrow store takes it at a
     * root, and nothing reads it elsewhere.
     */
    NARROWING_AT_ROOT,
    /** A comparison of narrow values, or a connective over such comparisons. */
    COND,
    /** A boolean output over a narrow condition. */
    OUTPUT,
    /**
     * Any other node - a narrow literal, whose broadcast the long kernel's slots do not name,
     * narrow arithmetic, a narrow choice: no lowering at the long lane.
     */
    NONE;

    /** A value a widening, a null test or a comparison reads. */
    boolean isValue() {
      return this == COLUMN || this == NARROWING;
    }

    /** A narrow-typed root the long kernel stores or writes. */
    boolean atRoot() {
      return this == NARROWING || this == NARROWING_AT_ROOT || this == COND || this == OUTPUT;
    }
  }

  /** {@code node}'s {@link LongNarrowRole}, read as if {@code node} were narrow-typed. */
  static LongNarrowRole longNarrowRole(VarkaVectorIR node) {
    return switch (node) {
      case VarkaVectorIR.ColumnRef c -> LongNarrowRole.COLUMN;
      case NarrowLane n -> !n.target().isNarrow() ? LongNarrowRole.NONE
          : switch (n.child().laneType()) {
            case INT, LONG -> LongNarrowRole.NARROWING;
            case BYTE, SHORT -> LongNarrowRole.NARROWING_AT_ROOT;
            case FLOAT, DOUBLE -> LongNarrowRole.NONE;
          };
      case VarkaVectorIR.Compare c -> LongNarrowRole.COND;
      case VarkaVectorIR.NullSafeEqual e -> LongNarrowRole.COND;
      case VarkaVectorIR.And a -> LongNarrowRole.COND;
      case VarkaVectorIR.Or o -> LongNarrowRole.COND;
      case VarkaVectorIR.Not n -> LongNarrowRole.COND;
      case VarkaVectorIR.CrossLane c -> LongNarrowRole.COND;
      case VarkaVectorIR.BoolOutput b -> LongNarrowRole.OUTPUT;
      // Every other node, each named so that a new one is a compile error until it is placed.
      case VarkaVectorIR.IsNotNull _, VarkaVectorIR.InRanges _, VarkaVectorIR.BoolColumn _,
          VarkaVectorIR.CondConst _, VarkaVectorIR.BoolCompare _, VarkaVectorIR.CondIf _,
          VarkaVectorIR.BoolNullSafeEq _, VarkaVectorIR.Chrono _, VarkaVectorIR.DecimalColumn _,
          VarkaVectorIR.DecimalWord _,
          VarkaVectorIR.LiteralSlot _, VarkaVectorIR.GuardedDay _, VarkaVectorIR.GuardedRange _,
          WidenLane _, VarkaVectorIR.AddDays _, VarkaVectorIR.SubDays _,
          VarkaVectorIR.DateDiff _, IntArith _, VarkaVectorIR.IntNeg _,
          VarkaVectorIR.ConstDivide _, VarkaVectorIR.BoundedDivide _, VarkaVectorIR.BitOp _,
          VarkaVectorIR.BitCount _, VarkaVectorIR.MathUnary _, VarkaVectorIR.RoundToIntegral _,
          VarkaVectorIR.IfElse _,
          VarkaVectorIR.CondValue _, VarkaVectorIR.Greatest _, VarkaVectorIR.Least _,
          VarkaVectorIR.DayOfWeek _, VarkaVectorIR.WeekDay _, VarkaVectorIR.DayOfWeekIso _,
          VarkaVectorIR.NextDay _, VarkaVectorIR.ThursdayOf _, VarkaVectorIR.AddMonths _,
          VarkaVectorIR.MakeDate _, VarkaVectorIR.WidenDecimal _, VarkaVectorIR.NullValue _,
          ConvertLane _ ->
          LongNarrowRole.NONE;
    };
  }

  /** What a node reads of its narrow children in a kernel driven at the long lane. */
  private enum LongNarrowReader {
    /** A widening, a comparison or a narrowing: a narrow value. */
    VALUE,
    /** A connective or a boolean output: a narrow condition. */
    COND,
    /** A null test: either. */
    VALUE_OR_COND,
    /** Any other node: no narrow child. */
    NONE;

    boolean takes(LongNarrowRole child) {
      return switch (this) {
        case VALUE -> child.isValue();
        case COND -> child == LongNarrowRole.COND;
        case VALUE_OR_COND -> child.isValue() || child == LongNarrowRole.COND;
        case NONE -> false;
      };
    }
  }

  /** {@code node}'s {@link LongNarrowReader}. */
  private static LongNarrowReader longNarrowReader(VarkaVectorIR node) {
    return switch (node) {
      case WidenLane w -> LongNarrowReader.VALUE;
      case NarrowLane n -> LongNarrowReader.VALUE;
      case VarkaVectorIR.Compare c -> LongNarrowReader.VALUE;
      case VarkaVectorIR.NullSafeEqual e -> LongNarrowReader.VALUE;
      case VarkaVectorIR.IsNotNull n -> LongNarrowReader.VALUE_OR_COND;
      case VarkaVectorIR.And a -> LongNarrowReader.COND;
      case VarkaVectorIR.Or o -> LongNarrowReader.COND;
      case VarkaVectorIR.Not n -> LongNarrowReader.COND;
      case VarkaVectorIR.CrossLane c -> LongNarrowReader.COND;
      case VarkaVectorIR.BoolOutput b -> LongNarrowReader.COND;
      // Every other node, each named so that a new one is a compile error until it is placed.
      case VarkaVectorIR.InRanges _, VarkaVectorIR.BoolColumn _, VarkaVectorIR.CondConst _,
          VarkaVectorIR.BoolCompare _, VarkaVectorIR.CondIf _, VarkaVectorIR.BoolNullSafeEq _,
          VarkaVectorIR.Chrono _, VarkaVectorIR.ColumnRef _, VarkaVectorIR.DecimalColumn _,
          VarkaVectorIR.DecimalWord _,
          VarkaVectorIR.LiteralSlot _, VarkaVectorIR.GuardedDay _, VarkaVectorIR.GuardedRange _,
          VarkaVectorIR.AddDays _, VarkaVectorIR.SubDays _, VarkaVectorIR.DateDiff _,
          IntArith _, VarkaVectorIR.IntNeg _, VarkaVectorIR.ConstDivide _,
          VarkaVectorIR.BoundedDivide _, VarkaVectorIR.BitOp _, VarkaVectorIR.BitCount _,
          VarkaVectorIR.MathUnary _, VarkaVectorIR.RoundToIntegral _, VarkaVectorIR.IfElse _,
          VarkaVectorIR.CondValue _,
          VarkaVectorIR.Greatest _,
          VarkaVectorIR.Least _, VarkaVectorIR.DayOfWeek _, VarkaVectorIR.WeekDay _,
          VarkaVectorIR.DayOfWeekIso _, VarkaVectorIR.NextDay _, VarkaVectorIR.ThursdayOf _,
          VarkaVectorIR.AddMonths _, VarkaVectorIR.MakeDate _, VarkaVectorIR.WidenDecimal _,
          VarkaVectorIR.NullValue _, ConvertLane _ ->
          LongNarrowReader.NONE;
    };
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
   *
   * <p>Under {@link VarkaEmitOptions.NarrowGroupStore#COMBINED} the iteration is also sized by
   * the narrow roots the kernel stores, whose species may span more groups than any value it
   * loads - a {@code CAST(i AS TINYINT)} reads no narrow value at all but stores an eight-lane
   * species at four int lanes. The store's parts are the loop's, so the iteration must hold a
   * whole run of them; a selection root writes a bitmap and no store, so it is not one.
   */
  private static int narrowGroups(List<VarkaVectorIR> roots, Lane lane, int lanes,
      VarkaEmitOptions options) {
    if (lane != Lane.INT || lanes == 0) {
      return 1;
    }
    boolean combined =
        options.narrowGroupStore() == VarkaEmitOptions.NarrowGroupStore.COMBINED;
    int groups = 1;
    for (VarkaVectorIR root : roots) {
      for (LaneType type : VarkaVectorIR.narrowValuesIn(root)) {
        groups = Math.max(groups, Lane.widenLanes(lanes, Lane.of(type)) / lanes);
      }
      if (combined && !(root instanceof Cond) && !(root instanceof BoolOutput)
          && root.laneType().isNarrow()) {
        int species = Lane.widenLanes(lanes, Lane.of(root.laneType()));
        if (species > lanes) {
          groups = Math.max(groups, species / lanes);
        }
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

  /**
   * Whether the kernel converts between widths: driven at 64 bits and holding 32-bit values,
   * which fill the low half of their species.
   */
  boolean mixed() {
    requireEmittable();
    return mixed;
  }

  /**
   * The lanes other than the drive's that some node is emitted at, of either width: an int value
   * of a long or a double kernel, a double one of a long kernel, a float one of an int, a long or
   * a double kernel. Empty for a kernel of one lane. Each has its species and its literal table
   * beside the drive's.
   */
  Set<LaneType> otherLanes() {
    requireEmittable();
    return otherLanes;
  }

  /**
   * Whether a narrow-typed store of {@code stored} in this kernel's loop body may be whole: in a
   * kernel at the long lane, where the kernel converts between lanes - whose loop bound leaves
   * room for one int group of the long width past each lane group - and the store fills exactly
   * that group ({@link #wholeNarrowStoreFits}). The plan makes a kernel with such a narrowing at
   * its store one that converts between lanes, so this holds for every narrow store that can be
   * whole. False in a kernel at any other lane, whose store the emitter sizes against the int
   * group itself.
   */
  boolean wholeNarrowStoreAtLong(Lane stored) {
    requireEmittable();
    return lane == Lane.LONG && mixed && wholeNarrowStoreFits(stored, lanes);
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
   * excepted. A narrow column there is loaded widened into the int species, so its value is
   * emitted at the int lane (VARKA-332).
   */
  Lane laneOf(VarkaVectorIR node) {
    requireEmittable();
    if (otherLanes.isEmpty() || storeNarrowings.contains(node)) {
      return lane;
    }
    return node.laneType().isNarrow() ? Lane.INT : Lane.of(node.laneType());
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
    if (of == lane || of.bits == lane.bits) {
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
