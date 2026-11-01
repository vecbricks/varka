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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVariants.Kind;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVariants.Variant;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;

/**
 * Runs the variants of a passing case ({@link VarkaVariants}, VARKA-278) through the emitter and
 * {@link VarkaKernelChecks}, for {@code VarkaVariantSuite} over the small-tree series and
 * {@code VarkaIrFuzzSuite} over its draws. A variant becomes a case of its own, its columns built
 * from the original's by the variant's plan. Its cases are the suites' {@link VarkaFuzzCase}s, and
 * {@code VarkaVariantCheck} is its Scala face (VARKA-311, VARKA-320).
 *
 * <p>Every output of a variant is compared with the reference evaluator, row for row. A variant
 * may decline only where {@link Kind#mayDecline} says, and then the kernel is emitted again under
 * {@code exactRefusalStatus} and run on the same batch: the bits it declines with must be bits of
 * guards the variant adds ({@link VarkaVariants#addedRefusalBits}). Anywhere else a decline fails.
 */
public final class VarkaVariantChecks {

  private VarkaVariantChecks() {
  }

  /** What one variant's emission and check came to. */
  public sealed interface Outcome {
    /** Every row of every output agreed with the reference evaluator. */
    record Compared() implements Outcome { }

    /** A decline the variant may make, by the bits it was allowed: {@code status} exact. */
    record Declined(int status) implements Outcome { }

    /** No form of the emitter holds the shape: past the class-file cap. */
    record Skipped() implements Outcome { }
  }

  /**
   * How a suite emits: the roots, the column and literal counts, the options and the class name
   * to give the class; empty for a shape past the class-file cap. The series passes
   * {@link #retryWithoutBudget}; the fuzzer passes its own, so its checks on a decline's reason,
   * its count of shapes past the cap and its size trace apply to its variants as to its draws.
   */
  public interface Emit {
    Optional<byte[]> emit(List<VarkaVectorIR> roots, int numInputs, int numLiterals,
        VarkaEmitOptions options, String className);
  }

  /**
   * The series' way of emitting: a size decline under the byte budget is retried without it, as
   * the fuzzer does, and a decline without one is the class-file cap's.
   */
  public static final Emit RETRY_WITHOUT_BUDGET = (roots, numInputs, numLiterals, options,
      className) -> {
    try {
      return Optional.of(VarkaLoopEmitter.emit(className, roots, numInputs, numLiterals, null,
          null, options));
    } catch (VarkaEmitDeclined first) {
      if (options.methodByteBudget() <= 0) {
        return Optional.empty();
      }
      try {
        return Optional.of(VarkaLoopEmitter.emit(className, roots, numInputs, numLiterals, null,
            null, options.withMethodByteBudget(0)));
      } catch (VarkaEmitDeclined second) {
        return Optional.empty();
      }
    }
  };

  private static final AtomicInteger CLASS_COUNTER = new AtomicInteger(0);

  private static String className(VarkaFuzzCase c) {
    return "org.apache.spark.sql.varka.execution.VarkaVariant"
        + (c.lane().bits() == Long.SIZE ? "Long" : "") + CLASS_COUNTER.addAndGet(1);
  }

  /** One variant kind and the variant, empty where it does not apply. */
  public record KindVariant(Kind kind, Optional<Variant> variant) { }

  /**
   * The variants of {@code c}, in {@link Kind}'s order. {@code stranger} is a tree that passes
   * over {@code c}'s columns, and {@code pool} the trees an untaken arm may be replaced by,
   * scanned from {@code rotation}.
   */
  public static List<KindVariant> variants(VarkaFuzzCase c, VarkaVectorIR stranger,
      List<VarkaVectorIR> pool, int rotation) {
    return variants(c, List.of(stranger), pool, rotation, v -> true);
  }

  /**
   * {@link #variants} that keeps the two variants which can grow the tree within {@code fits}
   * where it can (VARKA-323): beside a stranger takes the first of {@code strangers} whose variant
   * fits, else the first; the untaken arm is replaced as {@link VarkaVariants#untakenArm} with
   * {@code fits} chooses. The fuzzer passes whether the emitter's form without a byte budget holds
   * the variant, which a draw always satisfies and a grown variant may not.
   */
  public static List<KindVariant> variants(VarkaFuzzCase c, List<VarkaVectorIR> strangers,
      List<VarkaVectorIR> pool, int rotation, Predicate<Variant> fits) {
    var roots = c.roots();
    int n = c.numInputs();
    List<Variant> candidates = strangers.stream()
        .map(s -> VarkaVariants.besideAStranger(roots, n, s))
        .toList();
    Variant stranger = candidates.stream().filter(fits).findFirst().orElse(candidates.get(0));
    return List.of(
        new KindVariant(Kind.BESIDE_A_CHILD, VarkaVariants.besideAChild(roots, n)),
        new KindVariant(Kind.PERMUTED, VarkaVariants.permuted(roots, n)),
        new KindVariant(Kind.DUPLICATED, Optional.of(VarkaVariants.duplicated(roots, n))),
        new KindVariant(Kind.COPIED, Optional.of(VarkaVariants.copied(roots, n))),
        new KindVariant(Kind.BESIDE_A_STRANGER, Optional.of(stranger)),
        new KindVariant(Kind.LITERAL_TO_COLUMN, VarkaVariants.literalToColumn(roots, n)),
        new KindVariant(Kind.COLUMNS_RENUMBERED, VarkaVariants.columnsRenumbered(roots, n)),
        new KindVariant(Kind.UNTAKEN_ARM,
            VarkaVariants.untakenArm(roots, n, armOracle(c), pool, rotation, fits)));
  }

  /**
   * Which arm of a condition the rows of {@code c} take, by the reference evaluator: the then arm
   * where the condition is true, the else arm where it is false or null. Read over both batches
   * the check runs - {@code c}'s own nulls and its other body's
   * ({@link VarkaKernelChecks#otherBody}: no nulls, or at one row every column null) - since a
   * condition over a column's validity can turn there, and an arm untaken on one is taken on the
   * other.
   */
  public static VarkaVariants.ArmOracle armOracle(VarkaFuzzCase c) {
    return cond -> {
      var batches = new ArrayList<boolean[][]>();
      batches.add(c.nulls());
      VarkaKernelChecks.otherBody(c.numInputs(), c.length(), c.nulls(), c.forceMasked())
          .ifPresent(other -> batches.add(other.nulls()));
      boolean anyThen = false;
      boolean anyElse = false;
      long[] row = new long[c.numInputs()];
      int[] ints = new int[c.numInputs()];
      boolean[] isNull = new boolean[c.numInputs()];
      int[] intLits = new int[c.lits().length];
      for (int l = 0; l < intLits.length; l++) {
        intLits[l] = (int) c.lits()[l];
      }
      for (var nulls : batches) {
        for (int i = 0; i < c.length(); i++) {
          for (int col = 0; col < c.numInputs(); col++) {
            isNull[col] = nulls[col][i];
            row[col] = c.data()[col][i];
            ints[col] = (int) row[col];
          }
          boolean taken = taken(c.lane(), cond, row, ints, isNull, c.lits(), intLits);
          if (taken) {
            anyThen = true;
          } else {
            anyElse = true;
          }
        }
      }
      if (!anyThen) {
        return VarkaVariants.Taken.ELSE_ONLY;
      }
      return anyElse ? VarkaVariants.Taken.BOTH : VarkaVariants.Taken.THEN_ONLY;
    };
  }

  private static boolean taken(LaneType lane, Cond cond, long[] row, int[] ints, boolean[] isNull,
      long[] lits, int[] intLits) {
    // A 64-bit lane's rows and literals are longs, DOUBLE's as their bits.
    return lane.bits() == Long.SIZE ? VarkaReference.longCond(cond, row, isNull, lits).isTrue()
        : VarkaReference.cond(cond, ints, isNull, intLits).isTrue();
  }

  /** {@code c} as {@code v} sees it: {@code v}'s roots over the columns its plan builds. */
  public static VarkaFuzzCase caseOf(VarkaFuzzCase c, Variant v) {
    var sources = v.columns();
    long[][] data = new long[sources.size()][];
    boolean[][] nulls = new boolean[sources.size()][];
    for (int s = 0; s < sources.size(); s++) {
      switch (sources.get(s)) {
        case VarkaVariants.FromColumn f -> {
          data[s] = c.data()[f.ordinal()];
          nulls[s] = c.nulls()[f.ordinal()];
        }
        case VarkaVariants.FromLiteral l -> {
          data[s] = new long[c.length()];
          Arrays.fill(data[s], c.lits()[l.slot()]);
          nulls[s] = new boolean[c.length()];
        }
      }
    }
    var roots = v.roots();
    var shown = new ArrayList<String>();
    for (var root : roots) {
      shown.add(VarkaVectorIR.canonical(root));
    }
    return new VarkaFuzzCase(c.lane(), roots, sources.size(), c.lits(), c.length(), nulls, data,
        c.forceMasked(), c.options(), moved(sources, c.smallOrdinal()),
        moved(sources, c.levelOrdinal()), c.label() + " variant=" + v.kind().label + " roots=["
        + String.join(", ", shown) + "]");
  }

  private static int moved(List<VarkaVariants.Source> sources, int ordinal) {
    return ordinal < 0 ? -1 : sources.indexOf(new VarkaVariants.FromColumn(ordinal));
  }

  /** {@code c} itself, emitted and compared; a decline fails. */
  public static Outcome checkPlain(VarkaFuzzCase c, Emit emit) {
    String name = className(c);
    var bytes = emit.emit(c.roots(), c.numInputs(), c.lits().length, c.options(), name);
    if (bytes.isEmpty()) {
      return new Outcome.Skipped();
    }
    status(c, name, bytes.get(), false);
    return new Outcome.Compared();
  }

  /**
   * Variant {@code v} of {@code c}, emitted and compared; throws on a mismatch, on a decline the
   * variant may not make, and on one whose exact bits are not all the guards' it adds.
   */
  public static Outcome check(VarkaFuzzCase c, Variant v, Emit emit) {
    var vc = caseOf(c, v);
    var first = run(vc, vc.options(), v.kind().mayDecline, emit);
    if (first.isEmpty()) {
      return new Outcome.Skipped();
    }
    if (first.get() == 0) {
      return new Outcome.Compared();
    }
    var exactOptions = vc.options().withExactRefusalStatus(true);
    int allowed = VarkaVariants.addedRefusalBits(c.roots(), c.numInputs(), v.roots(),
        vc.numInputs(), vc.lits().length, exactOptions);
    int exact = run(vc, exactOptions, v.kind().mayDecline, emit).orElseThrow(() ->
        new IllegalStateException(vc.label() + ": declined (status " + first.get() + "), then "
            + "past the class-file cap under exactRefusalStatus"));
    if (exact == 0) {
      throw new IllegalStateException(vc.label() + ": declined (status " + first.get()
          + ") under its options and answered under exactRefusalStatus");
    }
    if ((exact & ~allowed) != 0) {
      throw new IllegalStateException(vc.label() + ": declined with status " + exact
          + "; the guards the variant adds give only " + allowed);
    }
    return new Outcome.Declined(exact);
  }

  private static Optional<Integer> run(VarkaFuzzCase vc, VarkaEmitOptions options,
      boolean mayDecline, Emit emit) {
    String name = className(vc);
    return emit.emit(vc.roots(), vc.numInputs(), vc.lits().length, options, name)
        .map(bytes -> status(vc, name, bytes, mayDecline));
  }

  /** {@code c}'s kernel run over {@code c}'s batch: the status, 0 once every row is compared. */
  private static int status(VarkaFuzzCase c, String name, byte[] bytes,
      boolean declineAllowed) {
    // The long harness at every 64-bit lane, DOUBLE's bits included.
    if (c.lane().bits() == Long.SIZE) {
      return VarkaKernelChecks.runAndCompareLongStatus(c.label(), name, bytes, c.roots(),
          c.numInputs(), c.lits(), new VarkaKernelChecks.LongBatch(c.length(), c.nulls(),
              c.data(), c.forceMasked()), declineAllowed);
    }
    int[] lits = new int[c.lits().length];
    for (int l = 0; l < lits.length; l++) {
      lits[l] = (int) c.lits()[l];
    }
    int[][] data = new int[c.data().length][];
    for (int col = 0; col < data.length; col++) {
      data[col] = new int[c.data()[col].length];
      for (int i = 0; i < data[col].length; i++) {
        data[col][i] = (int) c.data()[col][i];
      }
    }
    return VarkaKernelChecks.runAndCompareStatus(c.label(), name, bytes, c.roots(),
        c.numInputs(), lits, new VarkaKernelChecks.Batch(c.length(), c.nulls(), data,
            c.forceMasked()), declineAllowed);
  }
}
