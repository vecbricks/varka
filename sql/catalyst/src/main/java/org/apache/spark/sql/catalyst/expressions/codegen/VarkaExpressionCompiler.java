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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import javax.annotation.Nullable;

import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Alias;
import org.apache.spark.sql.catalyst.expressions.And;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.BindReferences;
import org.apache.spark.sql.catalyst.expressions.BitwiseCount;
import org.apache.spark.sql.catalyst.expressions.BoundReference;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.RuntimeReplaceable;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec.ForwardedOutput;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec.FusedOutput;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec.KernelOutput;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaErrors;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFamily;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaLoopEmitter;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.package$;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;

/**
 * Compiles a bound projection list to the Varka vector IR, recursing where the MVP's flat matcher
 * demanded bare attributes - {@code datediff(date_add(d, 7), d2)} compiles where milestone 1 saw
 * nothing, and so do {@code CASE WHEN}/{@code IF} (via interior comparisons and the three-valued
 * connectives), {@code greatest}/{@code least}, {@code dayofweek}/{@code weekday} and date
 * literals. The conditions also take {@code IN} over date literals (capped, see
 * {@code VarkaNodeCompiler.MAX_IN_LITERALS}) and the validity predicates {@code IS [NOT] NULL}
 * over bare columns, and the values with {@code coalesce}/{@code nvl}/{@code nvl2} (lowered onto
 * the validity condition) and the identity date cast. Used by both {@code VarkaColumnarRule} (is
 * the projection eligible?) and {@code VarkaKernelEvaluator} (what does the emitted loop
 * compute?), so eligibility cannot drift from execution: there is one compiler and the rule's
 * question is {@code compilePartial(...).isPresent()}.
 *
 * <p>Eligibility is per entry, not all or nothing: {@link #compilePartial} classifies every entry
 * as fused, forwarded (a bare column of any type, zero-copy) or residual (per-row), and the
 * projection is eligible when at least one entry fuses - a projection of forwards and residuals
 * alone gains nothing from Varka and stays on Janino untouched. {@link #compile} remains as the
 * all-entries-fused special case for callers that need exactly that.
 *
 * <p>The third entry point, {@link #compilePredicate}, is a filter condition compiled to a single
 * condition root - the selection mask the emitter writes as a bitmap - with the same per-part
 * eligibility, split on the predicate's {@code AND} spine instead of projection entries.
 *
 * <p>Literal day offsets fold through {@code DateVarkaSupport.foldDaysOffset} - the same rule the
 * MVP matched on - into slots of the runtime argument table, assigned per distinct
 * <i>value</i>: two occurrences of {@code date_add(d, 1)} must compile to equal IR records, or the
 * emitter's CSE could not see they are one computation. Slots are numbered in first-occurrence
 * order ({@link VarkaSlotTable}), so a chain's shape does not depend on what its constants are -
 * the identity milestone 3's cache will key on.
 *
 * <p>The inputs are Catalyst's own Scala trees and sequences; the results are Java. This is the
 * only eligibility rule there is. Internal to Varka: public only because the exec nodes and the
 * tests in other packages call it.
 */
public final class VarkaExpressionCompiler {

  private VarkaExpressionCompiler() {
  }

  /** The reason a round of {@link #classifyKernels} demotes an entry another kernel serves. */
  private static final String OTHER_KERNEL = "served by another kernel of this projection";

  // ---------------------------------------------------------------------------------------------
  // The entry points
  // ---------------------------------------------------------------------------------------------

  /** {@link #compile(Seq, Seq, VarkaEmitOptions)} under the default options. */
  public static Optional<CompiledVarkaProjection> compile(
      Seq<NamedExpression> projectList, Seq<Attribute> childOutput) {
    return compile(projectList, childOutput, VarkaEmitOptions.DEFAULTS);
  }

  /** The all-entries-fused special case of {@link #compilePartial}, for callers that need it. */
  public static Optional<CompiledVarkaProjection> compile(
      Seq<NamedExpression> projectList, Seq<Attribute> childOutput, VarkaEmitOptions options) {
    return compilePartial(projectList, childOutput, options)
        .filter(p -> p.specs().stream().allMatch(s -> s instanceof FusedOutput))
        .map(PartialVarkaProjection::fused);
  }

  /** {@link #compilePartial(Seq, Seq, VarkaEmitOptions)} under the default options. */
  public static Optional<PartialVarkaProjection> compilePartial(
      Seq<NamedExpression> projectList, Seq<Attribute> childOutput) {
    return compilePartial(projectList, childOutput, VarkaEmitOptions.DEFAULTS);
  }

  /**
   * Classifies every projection entry (see {@link VarkaOutputSpec}) and compiles the fused entries
   * into one sub-projection. Present exactly when at least one entry fused and the fused trees
   * reference at least one column - the emitted loop reads columns or has nothing to vectorize
   * over.
   *
   * <p>{@code options} are the emit options the kernel will be emitted with, which every caller
   * has to pass alike: they decide whether the emitter can serve the fused entries in bytes (see
   * {@link VarkaSizeAdmission}), so a caller that passed different ones could classify the same
   * projection differently from the evaluator that runs it.
   */
  public static Optional<PartialVarkaProjection> compilePartial(
      Seq<NamedExpression> projectList, Seq<Attribute> childOutput, VarkaEmitOptions options) {
    return compilePartial(projectList, childOutput, options, Set.of());
  }

  /**
   * {@link #compilePartial(Seq, Seq, VarkaEmitOptions)} with the families the session has
   * switched off (VARKA-318), which every caller has to pass alike for the reason it passes the
   * options alike: a node of a family that is off declines with the reason naming its switch.
   */
  public static Optional<PartialVarkaProjection> compilePartial(
      Seq<NamedExpression> projectList, Seq<Attribute> childOutput, VarkaEmitOptions options,
      Set<VarkaFamily> familiesOff) {
    return planning(
        () -> classifyKernels(entries(projectList), childOutput, options, familiesOff).partial(),
        () -> projectionIdentity(projectList, childOutput), options);
  }

  /** {@link #declines(Seq, Seq, VarkaEmitOptions)} under the default options. */
  public static SortedMap<Integer, VarkaDecline> declines(
      Seq<NamedExpression> projectList, Seq<Attribute> childOutput) {
    return declines(projectList, childOutput, VarkaEmitOptions.DEFAULTS);
  }

  /**
   * Why each declining entry declined, by position - including when nothing fused, which
   * {@link #compilePartial} reports as an empty result. For the tools that explain a projection
   * rather than run it.
   */
  public static SortedMap<Integer, VarkaDecline> declines(
      Seq<NamedExpression> projectList, Seq<Attribute> childOutput, VarkaEmitOptions options) {
    return declines(projectList, childOutput, options, Set.of());
  }

  /** {@link #declines(Seq, Seq, VarkaEmitOptions)} with the families switched off. */
  public static SortedMap<Integer, VarkaDecline> declines(
      Seq<NamedExpression> projectList, Seq<Attribute> childOutput, VarkaEmitOptions options,
      Set<VarkaFamily> familiesOff) {
    return planning(() -> Collections.unmodifiableSortedMap(
        classifyKernels(entries(projectList), childOutput, options, familiesOff).declines()),
        () -> projectionIdentity(projectList, childOutput), options);
  }

  /** {@link #compilePredicate(Expression, Seq, VarkaEmitOptions)} under the default options. */
  public static Optional<CompiledVarkaPredicate> compilePredicate(
      Expression condition, Seq<Attribute> childOutput) {
    return compilePredicate(condition, childOutput, VarkaEmitOptions.DEFAULTS);
  }

  /**
   * Compiles a filter predicate conjunct by conjunct. The condition splits on its {@code AND}
   * spine - Kleene AND is associative, so the split changes nothing - and each conjunct either
   * joins the fused mask kernel or stays behind as a residual, mirroring {@link #compilePartial}'s
   * per-entry eligibility including the table rollback: a declining conjunct must not widen the
   * kernel's input set or {@code canRun}'s Arrow check. An accepted conjunct must also keep the
   * <i>recombined</i> root within the emitter's budgets - the AND fold adds a node per accepted
   * conjunct, so the budgets are mirrored against the fold, not the conjunct alone. Present
   * exactly when at least one conjunct fused and the fused tree reads at least one column; the
   * caller keeps {@code residualConjuncts} in a row filter above.
   *
   * <p>The null rule needs no glue here: at the mask root unknown is false (see the IR's
   * {@code Cond} doc), and AND-splitting preserves it - a row where any conjunct is null or false
   * has the whole conjunction null or false, and both read as unselected.
   */
  public static Optional<CompiledVarkaPredicate> compilePredicate(
      Expression condition, Seq<Attribute> childOutput, VarkaEmitOptions options) {
    return compilePredicate(condition, childOutput, options, Set.of());
  }

  /** {@link #compilePredicate(Expression, Seq, VarkaEmitOptions)} with the families off. */
  public static Optional<CompiledVarkaPredicate> compilePredicate(
      Expression condition, Seq<Attribute> childOutput, VarkaEmitOptions options,
      Set<VarkaFamily> familiesOff) {
    return explainAndCompilePredicate(condition, childOutput, options, familiesOff).compiled();
  }

  /** {@link #explainPredicate(Expression, Seq, VarkaEmitOptions)} under the default options. */
  public static List<VarkaConjunctSpec> explainPredicate(
      Expression condition, Seq<Attribute> childOutput) {
    return explainPredicate(condition, childOutput, VarkaEmitOptions.DEFAULTS);
  }

  /**
   * Every conjunct of {@code condition} with what the compiler did with it - fused, or declined
   * with its reason - including when none fuses and {@link #compilePredicate} returns empty, so
   * that a filter left wholly to Spark still says why.
   */
  public static List<VarkaConjunctSpec> explainPredicate(
      Expression condition, Seq<Attribute> childOutput, VarkaEmitOptions options) {
    return explainAndCompilePredicate(condition, childOutput, options).specs();
  }

  /**
   * Whether a kernel can read a column of {@code dataType}: one of the int lane's types (date,
   * int, year-month interval) or the long lane's (bigint, TIME, day-time interval). A test
   * harness that feeds Varka asks this rather than keeping its own list, so the two cannot drift.
   */
  public static boolean readsColumnOf(DataType dataType) {
    return VarkaNodeCompiler.laneOf(dataType).isPresent();
  }

  // ---------------------------------------------------------------------------------------------
  // The planning-time catch
  // ---------------------------------------------------------------------------------------------

  // Test-only hook run inside the catch below before every compile, which throws as a broken
  // invariant of the compiler would, so a suite can show what such a bug reaches the user as
  // (VARKA-314). Static because Spark plans on one thread and runs tasks on others; reset in a
  // finally block.
  private static volatile @Nullable Runnable failForTesting = null;

  /** Sets the test-only hook that fails every compile; see {@code failForTesting}. */
  public static void setFailForTesting(boolean fail) {
    setFailForTesting(fail ? () -> {
      throw new IllegalArgumentException("injected Varka compiler failure");
    } : null);
  }

  /**
   * Sets the test-only hook to {@code failure}, run where the compile starts, or clears it with
   * null: a suite passes a call that reaches one of Varka's own invariant sites with an argument
   * it rejects, so the site's error crosses the planning catch as a compiler bug's would.
   */
  public static void setFailForTesting(@Nullable Runnable failure) {
    failForTesting = failure;
  }

  /**
   * Sets the test-only hook that makes every node {@code family} claims throw an internal
   * error, as a bug in that family's lowering would, or clears it with null (VARKA-318): a suite
   * sees the planning error name the family's switch, and the query plan once it is set.
   */
  public static void setFailInFamilyForTesting(@Nullable VarkaFamily family) {
    VarkaNodeCompiler.setFailInFamilyForTesting(family);
  }

  /**
   * {@code compile}'s result, or the internal error a throw out of it becomes (VARKA-314). The
   * compiler runs at planning time - on the driver for {@code VarkaColumnarRule}, in a task for
   * an evaluator's own compile - and nothing above it catches: a broken invariant fails the
   * query. This catch changes what the failure says, not that it fails: Spark's
   * {@code INTERNAL_ERROR}, of the original's Java type where a carrier exists, with the
   * context record naming the entries being compiled and the options they compiled under
   * ({@link VarkaErrors#planningFailure}). The entries render only here, in the catch.
   */
  private static <T> T planning(
      Supplier<T> compile,
      Supplier<String> identity,
      VarkaEmitOptions options) {
    try {
      Runnable failure = failForTesting;
      if (failure != null) {
        failure.run();
      }
      return compile.get();
    } catch (VarkaNodeCompiler.FamilyFailure f) {
      // A throw out of a family's lowering: the site's own error, with the family on its record
      // and the switch that plans the query without it in its sentence (VARKA-318).
      throw VarkaErrors.planningFailure(f.original(), identity, options, f.family());
    } catch (RuntimeException e) {
      throw VarkaErrors.planningFailure(e, identity, options);
    }
  }

  /** A projection as the context record names it at planning: its entries and their input. */
  private static String projectionIdentity(
      Seq<NamedExpression> projectList, Seq<Attribute> childOutput) {
    return "projection " + projectList.mkString("[", ", ", "]") + " over "
        + childOutput.mkString("[", ", ", "]");
  }

  // ---------------------------------------------------------------------------------------------
  // The projection
  // ---------------------------------------------------------------------------------------------

  /**
   * One classification: the partial projection, present when anything fused; why each declining
   * entry declined, by position; and the entries set aside only for the kernel's sake - demoted by
   * a class-wide decline, or over the budgets beside the others but within them alone - which
   * another kernel of the projection may serve.
   */
  private record Classified(
      Optional<PartialVarkaProjection> partial,
      SortedMap<Integer, VarkaDecline> declines,
      Set<Integer> aside) {
  }

  /**
   * {@link #classify}, and under {@code VarkaEmitOptions.severalKernels} the entries it set aside
   * only for the kernel's sake - the suffix a class-wide decline demoted, an entry that fits alone
   * but not beside the rest - classified again as a kernel of their own, round after round, until
   * a round fuses nothing or nothing is left aside. Each round is {@link #classify} over the whole
   * projection with every other entry demoted, so it sees the same entries in the same order and
   * the kernels are deterministic in the projection alone. See {@code VARKA-190.md} 11.
   */
  private static Classified classifyKernels(
      List<NamedExpression> entries, Seq<Attribute> childOutput, VarkaEmitOptions options,
      Set<VarkaFamily> off) {
    Classified first = classify(entries, childOutput, options, off, Map.of());
    if (!options.severalKernels() || first.partial().isEmpty()) {
      return first;
    }
    PartialVarkaProjection kernel = first.partial().get();
    List<VarkaOutputSpec> specs = new ArrayList<>(kernel.specs());
    SortedMap<Integer, VarkaDecline> declines = new TreeMap<>(first.declines());
    List<CompiledVarkaProjection> more = new ArrayList<>();
    Set<Integer> aside = first.aside();
    boolean progress = true;
    while (!aside.isEmpty() && progress) {
      Map<Integer, String> others = new HashMap<>();
      for (int at = 0; at < entries.size(); at++) {
        if (!aside.contains(at)) {
          others.put(at, OTHER_KERNEL);
        }
      }
      Classified next = classify(entries, childOutput, options, off, others);
      // The entries this round fused, by projection position, to their index in its kernel.
      Map<Integer, Integer> fused = new LinkedHashMap<>();
      next.partial().ifPresent(p -> {
        for (int at = 0; at < p.specs().size(); at++) {
          if (p.specs().get(at) instanceof FusedOutput f) {
            fused.put(at, f.fusedIndex());
          }
        }
      });
      progress = !fused.isEmpty();
      if (progress) {
        more.add(next.partial().get().fused());
        for (Map.Entry<Integer, Integer> f : fused.entrySet()) {
          specs.set(f.getKey(), new KernelOutput(more.size(), f.getValue()));
          declines.remove(f.getKey());
        }
      }
      // Every entry this round classified and did not fuse carries this round's reason, the
      // latest thing that kept it out, including when the round fused nothing and ends the loop.
      for (int at : aside) {
        VarkaDecline why = next.declines().get(at);
        if (!fused.containsKey(at) && why != null) {
          declines.put(at, why);
        }
      }
      aside = next.aside();
    }
    return new Classified(
        Optional.of(new PartialVarkaProjection(specs, kernel.fused(), declines, more)),
        declines, Set.of());
  }

  /**
   * One classification and the size admission's answer on its kernel: empty when the emitter
   * serves it, or when nothing fused and there is nothing to ask.
   */
  private record Asked(Classified once, Optional<VarkaSizeAdmission.Decline> decline) {
  }

  /**
   * The per-entry classification, then the size admission: the entries the weight caps admit are
   * asked of the emitter as the one kernel they make, and an entry the emitter declines in bytes
   * is classified again as residual, with the emitter's reason, until the kernel it leaves is one
   * the emitter serves. Each round demotes at least one entry, so it ends.
   *
   * <p>A decline that names outputs demotes those. A class-wide one - a driver over a limit, a
   * class over the class-file caps - names none, and what shrinks such a class is fewer outputs;
   * the driver grows with their number. Demoting one output per round would ask for a class of
   * nearly the same size once per output, which on a projection of thousands of entries is
   * thousands of builds of a class of thousands of methods. So a class-wide decline bisects
   * instead: the fused entries are a prefix in projection order, the largest prefix the emitter
   * admits is found by halving, and the rest are demoted with the class-wide reason - a
   * logarithmic number of asks, each one emission through the shape cache.
   */
  private static Classified classify(
      List<NamedExpression> entries,
      Seq<Attribute> childOutput,
      VarkaEmitOptions options,
      Set<VarkaFamily> off,
      Map<Integer, String> initial) {
    // A nondeterministic entry declines the whole projection, as a nondeterministic conjunct
    // declines a predicate. A Varka node evaluates its residual entries row by row, on its row
    // path and beside its kernel, through projections of its own; vanilla's `Project` draws a
    // seeded `rand()` from one generator per partition, initialized with the partition's index,
    // and two paths would each need one. Declined, the projection runs as vanilla's.
    if (!entries.stream().allMatch(e -> ((Expression) e).deterministic())) {
      String why = "the projection has a nondeterministic entry, whose values would depend on "
          + "the path each batch took";
      DeclineSink sink = new DeclineSink(childOutput, options.rangeSets());
      UnaryOperator<Expression> bind = binder(childOutput);
      SortedMap<Integer, VarkaDecline> declines = new TreeMap<>();
      for (int at = 0; at < entries.size(); at++) {
        sink.note(why, bind.apply((Expression) entries.get(at)));
        int position = at;
        sink.take().ifPresent(d -> declines.put(position, d));
      }
      return new Classified(Optional.empty(), declines, Set.of());
    }
    Map<Integer, String> demoted = new HashMap<>(initial);
    // The entries a class-wide decline demoted: they fit, only not in this kernel.
    Set<Integer> bisected = new HashSet<>();
    // The cuts the plan has made for this kernel, each one emission that builds nothing; after
    // two the bisection has the last word (VARKA-236.md 3.4).
    int plannedCuts = 0;
    while (true) {
      Asked asked = ask(entries, childOutput, demoted, options, off);
      if (asked.decline().isEmpty()) {
        Classified once = asked.once();
        Set<Integer> aside = new HashSet<>(bisected);
        aside.addAll(once.aside());
        return new Classified(once.partial(), once.declines(), aside);
      }
      List<Integer> at = positions(asked.once().partial().get());
      VarkaSizeAdmission.Decline decline = asked.decline().get();
      int cut = decline.plannedCut();
      if (!decline.outputs().isEmpty()) {
        for (int output : decline.outputs()) {
          demoted.put(at.get(output), decline.reason());
        }
      } else if (cut > 0 && cut < at.size() && plannedCuts < 2) {
        // Under `planSize` the decline says how many of the fused entries one class serves, read
        // off the driver over the grouping the emitter formed: the rest are set aside in one
        // step. The next ask plans the prefix on its own grouping, so a prefix whose own driver
        // is still over cuts once more, and after that the bisection decides.
        plannedCuts++;
        for (int position : at.subList(cut, at.size())) {
          demoted.put(position, decline.reason());
          bisected.add(position);
        }
      } else {
        // `hi` entries are known not to fit as a class; `lo` entries are known to fit, or to
        // decline naming outputs, which the next round handles. Nothing fused fits trivially.
        int lo = 0;
        int hi = at.size();
        String reasonAtHi = decline.reason();
        while (hi - lo > 1) {
          int mid = (lo + hi) / 2;
          Map<Integer, String> probe = new HashMap<>(demoted);
          for (int position : at.subList(mid, at.size())) {
            probe.put(position, reasonAtHi);
          }
          Optional<VarkaSizeAdmission.Decline> answer =
              ask(entries, childOutput, probe, options, off).decline();
          if (answer.isPresent() && answer.get().outputs().isEmpty()) {
            hi = mid;
            reasonAtHi = answer.get().reason();
          } else {
            lo = mid;
          }
        }
        for (int position : at.subList(lo, at.size())) {
          demoted.put(position, reasonAtHi);
          bisected.add(position);
        }
      }
    }
  }

  /**
   * The classification {@code demoted} leaves, and the size admission's answer on its kernel. The
   * classification is returned with the answer so that an admitted kernel is not classified a
   * second time to be built.
   */
  private static Asked ask(
      List<NamedExpression> entries,
      Seq<Attribute> childOutput,
      Map<Integer, String> demoted,
      VarkaEmitOptions options,
      Set<VarkaFamily> off) {
    Classified once = classifyOnce(entries, childOutput, demoted, options, off);
    return new Asked(once,
        once.partial().flatMap(partial -> VarkaSizeAdmission.admit(partial.fused(), options)));
  }

  /**
   * The projection positions of a partial's fused entries, in the order the kernel numbers them;
   * a decline's named outputs index this.
   */
  private static List<Integer> positions(PartialVarkaProjection partial) {
    List<VarkaOutputSpec> specs = partial.specs();
    Integer[] at = new Integer[partial.fused().outputs().size()];
    for (int position = 0; position < specs.size(); position++) {
      if (specs.get(position) instanceof FusedOutput f) {
        at[f.fusedIndex()] = position;
      }
    }
    return List.of(at);
  }

  private static Classified classifyOnce(
      List<NamedExpression> entries,
      Seq<Attribute> childOutput,
      Map<Integer, String> demoted,
      VarkaEmitOptions options,
      Set<VarkaFamily> off) {
    return new EntryPass(childOutput, demoted, options, off).run(entries);
  }

  /**
   * One pass over a projection's entries, with the tables they share. Both tables assign dense
   * slots in first-occurrence order, which makes the compiled shape deterministic in the
   * projection alone.
   */
  private static final class EntryPass {
    private final Seq<Attribute> childOutput;
    private final Map<Integer, String> demoted;
    private final VarkaEmitOptions options;
    private final Set<VarkaFamily> off;
    private final VarkaSlotTable inputs = new VarkaSlotTable();
    private final VarkaSlotTable literals = new VarkaSlotTable();
    private final List<VarkaVectorIR> outputs = new ArrayList<>();
    private final List<DataType> outputTypes = new ArrayList<>();
    private final DeclineSink sink;
    private final UnaryOperator<Expression> bind;
    private final SortedMap<Integer, VarkaDecline> declines = new TreeMap<>();
    // Entries over the budgets beside the others that fit them alone: another kernel's.
    private final Set<Integer> alone = new HashSet<>();

    EntryPass(Seq<Attribute> childOutput, Map<Integer, String> demoted,
        VarkaEmitOptions options, Set<VarkaFamily> off) {
      this.childOutput = childOutput;
      this.demoted = demoted;
      this.options = options;
      this.off = off;
      this.sink = new DeclineSink(childOutput, options.rangeSets(), off);
      this.bind = binder(childOutput);
    }

    Classified run(List<NamedExpression> entries) {
      List<VarkaOutputSpec> specs = new ArrayList<>(entries.size());
      for (int position = 0; position < entries.size(); position++) {
        // Another kernel's entry in a round of `classifyKernels`: neither bound nor noted, so a
        // round costs the entries it classifies rather than the whole projection.
        specs.add(OTHER_KERNEL.equals(demoted.get(position))
            ? VarkaOutputSpec.RESIDUAL
            : classify(entries.get(position), position));
      }
      if (outputs.isEmpty() || inputs.size() == 0) {
        return new Classified(Optional.empty(), declines, alone);
      }
      CompiledVarkaProjection fused = tables(inputs, literals, sink)
          .projection(outputs, outputTypes);
      return new Classified(
          Optional.of(new PartialVarkaProjection(specs, fused, declines, List.of())),
          declines, alone);
    }

    private VarkaOutputSpec classify(NamedExpression named, int position) {
      // Bound at Expression, not NamedExpression: a bare column entry binds to a BoundReference,
      // which is not a NamedExpression. (Every NamedExpression is an Expression; Java does not
      // see the Scala trait's superclass, hence the casts.)
      Expression bound = bind.apply((Expression) named);
      Expression e = bound instanceof Alias alias ? alias.child() : bound;
      // A bare column is compilable as a node but never emitted as an output: emitting it would
      // be a copy loop, while forwarding the input's vector is zero-copy.
      if (e instanceof BoundReference br) {
        return new ForwardedOutput(br.ordinal());
      }
      // An entry the size admission demoted in an earlier round: residual with the emitter's
      // reason, and compiled not at all, so it registers nothing in the shared tables.
      String demotedWhy = demoted.get(position);
      if (demotedWhy != null) {
        sink.note(demotedWhy, e);
        return residual(position);
      }
      // The tables are shared across entries (CSE across outputs depends on it), so a declining
      // entry must not leave the columns and literals its failing subtrees registered: they
      // would widen the kernel's input set - and `canRun`'s Arrow check - for no output. Entries
      // are appended in table order, so truncating to the pre-entry size restores the exact
      // prior state.
      Marks marks = Marks.of(inputs, literals, sink);
      // A test's forced decline (`forceResidualAt`, VARKA-296): the entry compiles as any other
      // and then takes the over-budget branch below, so the rollback it runs is the one a real
      // decline runs.
      boolean forced = options.forceResidualAt() == position + 1;
      // The date columns the entry's admission rested on are guarded in the tree now, before the
      // budgets below see it (DeclineSink.guardDayColumns).
      Optional<VarkaVectorIR> compiled = compileRoot(e, inputs, literals, sink)
          .map(ir -> sink.guardDayColumns(ir, marks.bounds()));
      if (compiled.isPresent()) {
        VarkaVectorIR ir = compiled.get();
        // One kernel is driven at one lane: its loop, its epilogue and its driving stores are that
        // lane's species. The first fused entry fixes the lane and an entry driven at the other is
        // demoted to residual with a reason that says so - checked here, before the budgets,
        // because `fitsBudgets` would refuse the mix too but only answers yes or no, and a lane
        // mismatch reported as a budget breach sends a reader hunting a chain-depth problem that
        // is not there. An entry that converts between lanes is driven at the long lane
        // (VARKA-28), so it joins a long kernel and not an int one.
        LaneType lane = VarkaVectorIR.emissionLane(ir);
        if (!outputs.isEmpty() && lane != VarkaVectorIR.emissionLane(outputs.get(0))) {
          marks.restore(inputs, literals, sink);
          sink.take();
          sink.note(laneMismatch(lane, VarkaVectorIR.emissionLane(outputs.get(0))), e);
          return residual(position);
        }
        // An accepted entry must also fit the emitter's structural budgets together with the
        // entries accepted before it. The emitter enforces the same limits, but at emission
        // time, where a breach can only become a silent per-batch fallback - no decline reason,
        // and EXPLAIN still claims fusion. So the compiler mirrors them and demotes the
        // overflowing entry to residual.
        List<VarkaVectorIR> with = new ArrayList<>(outputs);
        with.add(ir);
        if (!forced && VarkaLoopEmitter.fitsBudgets(with, inputs.size(), options)) {
          sink.take();
          outputs.add(ir);
          outputTypes.add(e.dataType());
          return new FusedOutput(outputs.size() - 1);
        }
      }
      // `misdescribeRollback` is a fault injector for the forced-decline check: zero in
      // production, where this restores the four tables exactly.
      switch (options.misdescribeRollback()) {
        case 1 -> { }
        case 3 -> inputs.truncate(Math.max(0, marks.inputs() - 1));
        default -> inputs.truncate(marks.inputs());
      }
      literals.truncate(marks.literals());
      sink.truncateLong(marks.longs());
      if (options.misdescribeRollback() != 2) {
        sink.truncateBounds(marks.bounds());
      }
      sink.truncateUsed(marks.used());
      if (compiled.isPresent()) {
        sink.take(); // an entry that compiled clean; its reason is the budget, or the force
        if (forced) {
          sink.note("forced residual (forceResidualAt=" + options.forceResidualAt() + ")", e);
        } else {
          sink.note("exceeds the emitter's fused budget", e);
          if (fitsAlone(e)) {
            alone.add(position);
          }
        }
      }
      // A declining entry always leaves a reason: every empty compile notes one.
      return residual(position);
    }

    /** Records the noted reason against {@code position}, and classifies it residual. */
    private VarkaOutputSpec residual(int position) {
      sink.take().ifPresent(d -> declines.put(position, d));
      return VarkaOutputSpec.RESIDUAL;
    }

    /**
     * Whether an entry over the budgets beside the others fits them by itself, counted with its
     * own columns: compiled alone into tables of its own, which the shared ones never see.
     */
    private boolean fitsAlone(Expression e) {
      VarkaSlotTable own = new VarkaSlotTable();
      DeclineSink ownSink = new DeclineSink(childOutput, options.rangeSets(), off);
      return compileRoot(e, own, new VarkaSlotTable(), ownSink)
          .map(ir -> ownSink.guardDayColumns(ir, 0))
          .map(ir -> VarkaLoopEmitter.fitsBudgets(List.of(ir), own.size(), options))
          .orElse(false);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The predicate
  // ---------------------------------------------------------------------------------------------

  /** Every conjunct with what the compiler did with it, and the predicate if any fused. */
  public record Explained(
      List<VarkaConjunctSpec> specs, Optional<CompiledVarkaPredicate> compiled) {
    public Explained {
      specs = List.copyOf(specs);
    }
  }

  /**
   * {@link #explainPredicate} and {@link #compilePredicate} from one classification, for a caller
   * that reports both, such as EXPLAIN.
   */
  public static Explained explainAndCompilePredicate(
      Expression condition, Seq<Attribute> childOutput, VarkaEmitOptions options) {
    return explainAndCompilePredicate(condition, childOutput, options, Set.of());
  }

  /** {@link #explainAndCompilePredicate(Expression, Seq, VarkaEmitOptions)} with families off. */
  public static Explained explainAndCompilePredicate(
      Expression condition, Seq<Attribute> childOutput, VarkaEmitOptions options,
      Set<VarkaFamily> familiesOff) {
    return planning(() -> predicate(condition, childOutput, options, familiesOff),
        () -> "condition " + condition + " over " + childOutput.mkString("[", ", ", "]"),
        options);
  }

  private static Explained predicate(
      Expression condition, Seq<Attribute> childOutput, VarkaEmitOptions options,
      Set<VarkaFamily> off) {
    // The split hoists fused conjuncts below the residual ones, which reorders evaluation. That
    // is sound only when every conjunct is deterministic - Spark's own predicate pushdown stops
    // at the first nondeterministic conjunct (span(_.deterministic)) for the same reason: a
    // seeded rand() must see every row, not the survivors of a hoisted predicate. One
    // nondeterministic conjunct therefore declines the whole predicate (task-21 review); the
    // rewrite must never change what the query computes.
    if (!condition.deterministic()) {
      String why = "the condition is nondeterministic: a fused conjunct would run ahead of a "
          + "nondeterministic one and change the rows it sees";
      DeclineSink sink = new DeclineSink(childOutput, options.rangeSets());
      UnaryOperator<Expression> bind = binder(childOutput);
      List<VarkaConjunctSpec> specs = new ArrayList<>();
      for (Expression conjunct : conjuncts(condition)) {
        sink.note(why, bind.apply(conjunct));
        specs.add(new VarkaConjunctSpec(conjunct, false, sink.take()));
      }
      return new Explained(specs, Optional.empty());
    }
    // The size admission of `classify`, for conjuncts. The fused conjuncts fold into one
    // condition root, which the emitter cannot split by name. Under `splitConditions` the
    // compiler splits it instead (see `split`); otherwise, or when no split fits, a decline
    // demotes the last-admitted conjunct and the rest are asked again.
    // The order rule comes first in each round (VARKA-380): a fused conjunct that Spark evaluates
    // after a residual one that can raise is demoted, so the residual filter evaluates it in
    // Spark's order. A demoted conjunct that can raise moves the line again, and so does a size
    // demotion, so the rule is asked again every round until it holds.
    List<Expression> written = conjuncts(condition);
    Map<Integer, String> demoted = new HashMap<>();
    while (true) {
      PredicatePass once = predicateOnce(condition, childOutput, demoted, options, off);
      Map<Integer, String> late = VarkaConjunctOrder.behindRaising(written,
          once.specs().stream().map(VarkaConjunctSpec::fused).toList());
      if (!late.isEmpty()) {
        demoted.putAll(late);
        continue;
      }
      Optional<VarkaSizeAdmission.Decline> decline = once.compiled().flatMap(p ->
          VarkaSizeAdmission.admit(p.fused(), options));
      if (decline.isEmpty()) {
        return new Explained(once.specs(), once.compiled());
      }
      if (options.splitConditions()) {
        Optional<CompiledVarkaPredicate> split = split(once, options);
        if (split.isPresent()) {
          return new Explained(once.specs(), split);
        }
      }
      demoted.put(lastFused(once.specs()), decline.get().reason());
    }
  }

  /** The index of the last fused conjunct. */
  private static int lastFused(List<VarkaConjunctSpec> specs) {
    int last = -1;
    for (int i = 0; i < specs.size(); i++) {
      if (specs.get(i).fused()) {
        last = i;
      }
    }
    return last;
  }

  /**
   * One pass of {@link #predicateOnce}: the conjunct specs, the fused conditions in query order,
   * the accepted conjuncts' tables, and the predicate with the fused conditions folded into one
   * root; {@link #build} lays the same tables under any other layout, which is what a split needs.
   */
  private record PredicatePass(
      List<VarkaConjunctSpec> specs,
      List<Cond> conds,
      @Nullable Tables tables,
      Optional<CompiledVarkaPredicate> compiled) {

    /** The predicate of {@code layout} over this pass's tables; see the static form. */
    CompiledVarkaPredicate build(List<List<Cond>> layout) {
      if (tables == null) {
        throw VarkaErrors.illegalState("nothing fused");
      }
      return VarkaExpressionCompiler.build(specs, tables, layout);
    }
  }

  /**
   * The predicate of {@code layout}: clauses joined by AND, each clause's roots joined by OR,
   * over the accepted conjuncts' tables.
   */
  private static CompiledVarkaPredicate build(
      List<VarkaConjunctSpec> specs, Tables tables, List<List<Cond>> layout) {
    List<VarkaVectorIR> roots = new ArrayList<>();
    List<List<Integer>> clauses = new ArrayList<>();
    for (List<Cond> clause : layout) {
      List<Integer> indices = new ArrayList<>();
      for (Cond root : clause) {
        indices.add(roots.size());
        roots.add(root);
      }
      clauses.add(indices);
    }
    List<DataType> types = Collections.nCopies(roots.size(), DataTypes.BooleanType);
    return new CompiledVarkaPredicate(specs, tables.projection(roots, types), clauses);
  }

  private static PredicatePass predicateOnce(
      Expression condition,
      Seq<Attribute> childOutput,
      Map<Integer, String> demoted,
      VarkaEmitOptions options,
      Set<VarkaFamily> off) {
    VarkaSlotTable inputs = new VarkaSlotTable();
    VarkaSlotTable literals = new VarkaSlotTable();
    DeclineSink sink = new DeclineSink(childOutput, options.rangeSets(), off);
    List<Cond> fused = new ArrayList<>();
    List<VarkaConjunctSpec> specs = new ArrayList<>();
    UnaryOperator<Expression> bind = binder(childOutput);
    List<Expression> conjuncts = conjuncts(condition);
    for (int index = 0; index < conjuncts.size(); index++) {
      Expression conjunct = conjuncts.get(index);
      Expression bound = bind.apply(conjunct);
      String demotedWhy = demoted.get(index);
      if (demotedWhy != null) {
        sink.note(demotedWhy, bound);
        specs.add(new VarkaConjunctSpec(conjunct, false, sink.take()));
        continue;
      }
      Marks marks = Marks.of(inputs, literals, sink);
      // Guarded as a projection entry is; the rebuild keeps a condition a condition.
      Optional<Cond> compiled = VarkaConditionCompiler.compileCond(bound, inputs, literals, sink)
          .map(c -> (Cond) sink.guardDayColumns(c, marks.bounds()));
      if (compiled.isPresent()) {
        Cond cond = compiled.get();
        // The lane rule of `compilePartial`, for conjuncts: the AND fold below would refuse a mix
        // by construction. Conjuncts of the two lanes meet on the long one, the int ones
        // compared widened (VARKA-28); one with no long-lane form is declined with the lane's
        // reason.
        // The fused conjuncts as this one would leave them: lifted with it where the lanes
        // differ, and committed only once the budget below takes them.
        List<Cond> before = fused;
        boolean liftedBefore = false;
        if (!fused.isEmpty() && cond.laneType() != fused.get(0).laneType()) {
          Optional<Cond> lifted = VarkaConditionCompiler.onLongLane(cond);
          List<Cond> liftedFused = new ArrayList<>();
          for (Cond f : fused) {
            VarkaConditionCompiler.onLongLane(f).ifPresent(liftedFused::add);
          }
          if (lifted.isPresent() && liftedFused.size() == fused.size()) {
            cond = lifted.get();
            before = liftedFused;
            liftedBefore = true;
          }
        }
        if (!before.isEmpty() && cond.laneType() != before.get(0).laneType()) {
          marks.restore(inputs, literals, sink);
          sink.take();
          sink.note(laneMismatch(cond.laneType(), fused.get(0).laneType()), bound);
          specs.add(new VarkaConjunctSpec(conjunct, false, sink.take()));
          continue;
        }
        List<Cond> with = new ArrayList<>(before);
        with.add(cond);
        if (VarkaLoopEmitter.fitsBudgets(
            List.of(VarkaConditionCompiler.andFold(with)), inputs.size(), options)) {
          sink.take();
          if (liftedBefore) {
            fused.clear();
            fused.addAll(before);
          }
          fused.add(cond);
          specs.add(new VarkaConjunctSpec(conjunct, true, Optional.empty()));
          continue;
        }
      }
      marks.restore(inputs, literals, sink);
      if (compiled.isPresent()) {
        sink.take();
        sink.note("exceeds the emitter's fused budget", bound);
      }
      // A declining conjunct always leaves a reason: every empty compileCond notes one.
      specs.add(new VarkaConjunctSpec(conjunct, false, sink.take()));
    }
    if (fused.isEmpty() || inputs.size() == 0) {
      return new PredicatePass(specs, List.of(), null, Optional.empty());
    }
    Tables tables = tables(inputs, literals, sink);
    CompiledVarkaPredicate whole =
        build(specs, tables, List.of(List.of(VarkaConditionCompiler.andFold(fused))));
    return new PredicatePass(specs, fused, tables, Optional.of(whole));
  }

  /**
   * Splits a fused predicate that one method cannot hold across several selection outputs
   * ({@code VARKA-172.md} 3.1), or empty when no split fits and the caller demotes a conjunct as
   * it would without the option.
   *
   * <p>Every extra output costs a pass over its columns and a bitmap, so the split looks for the
   * fewest outputs, in two steps, each a search over a count of equal contiguous pieces in query
   * order. First the fewest conjunction roots, each its own clause, that the emitter accepts,
   * except that it may still name a root holding a single conjunct that is a disjunction. Then,
   * for each root so named, the fewest partial disjunctions of it that the emitter accepts beside
   * everything else, as one clause. A root the second step cannot split - a conjunct that is not a
   * disjunction, or a disjunct too large alone - ends the split empty, as does a kernel whose
   * driver the extra outputs take past the budget.
   *
   * <p>The emitter judges the whole kernel at every step, not each piece alone: a method in a
   * kernel of several outputs is not byte for byte what it is by itself, so a piece that fits
   * alone can still be named beside the others. Each question goes through
   * {@link VarkaSizeAdmission} and so through the shape cache, and the split runs only after the
   * single root has been refused, so a predicate that fits is compiled exactly as it is without
   * the option. The search is exact for splits into equal pieces, not over every partition: the
   * exact partition is VARKA-200's dynamic program, which needs a cost cheaper to ask than an
   * emission (VARKA-199).
   */
  private static Optional<CompiledVarkaPredicate> split(
      PredicatePass pass, VarkaEmitOptions options) {
    List<Cond> conds = pass.conds();
    OptionalInt k = smallest(1, conds.size(), n -> {
      List<List<Cond>> roots = pieces(conds, n);
      return over(pass, conjunctionRoots(roots), options).stream()
          .allMatch(o -> splittable(roots.get(o)));
    });
    if (k.isEmpty()) {
      return Optional.empty();
    }
    List<List<Cond>> roots = pieces(conds, k.getAsInt());
    List<List<Cond>> layout = conjunctionRoots(roots);
    // Each clause is still one output here, so the named outputs are the clauses to split.
    for (int c : new TreeSet<>(over(pass, layout, options))) {
      List<Cond> disjuncts = disjuncts(roots.get(c).get(0));
      int start = 0;
      for (List<Cond> clause : layout.subList(0, c)) {
        start += clause.size();
      }
      List<List<Cond>> before = layout;
      int first = start;
      OptionalInt m = smallest(2, disjuncts.size(), n -> {
        Set<Integer> named = over(pass, splitClause(before, c, disjuncts, n), options);
        for (int o = first; o < first + n; o++) {
          if (named.contains(o)) {
            return false;
          }
        }
        return true;
      });
      if (m.isEmpty()) {
        return Optional.empty();
      }
      layout = splitClause(layout, c, disjuncts, m.getAsInt());
    }
    return over(pass, layout, options).isEmpty()
        ? Optional.of(pass.build(layout))
        : Optional.empty();
  }

  /** Each piece folded into one conjunction root, each root its own clause. */
  private static List<List<Cond>> conjunctionRoots(List<List<Cond>> pieces) {
    List<List<Cond>> layout = new ArrayList<>();
    for (List<Cond> piece : pieces) {
      layout.add(List.of(VarkaConditionCompiler.andFold(piece)));
    }
    return layout;
  }

  /** {@code layout} with clause {@code c} replaced by {@code n} partial disjunctions. */
  private static List<List<Cond>> splitClause(
      List<List<Cond>> layout, int c, List<Cond> disjuncts, int n) {
    List<Cond> partials = new ArrayList<>();
    for (List<Cond> piece : pieces(disjuncts, n)) {
      partials.add(VarkaConditionCompiler.orFold(piece));
    }
    List<List<Cond>> split = new ArrayList<>(layout);
    split.set(c, partials);
    return split;
  }

  /** The outputs of {@code layout} the emitter names as over the budget; empty when it fits. */
  private static Set<Integer> over(
      PredicatePass pass, List<List<Cond>> layout, VarkaEmitOptions options) {
    return VarkaSizeAdmission.admit(pass.build(layout).fused(), options)
        .<Set<Integer>>map(d -> new HashSet<>(d.outputs()))
        .orElse(Set.of());
  }

  /** {@code items} in {@code k} contiguous pieces whose sizes differ by at most one. */
  private static List<List<Cond>> pieces(List<Cond> items, int k) {
    List<List<Cond>> pieces = new ArrayList<>(k);
    for (int i = 0; i < k; i++) {
      pieces.add(items.subList(i * items.size() / k, (i + 1) * items.size() / k));
    }
    return pieces;
  }

  /** The disjuncts of {@code c}, in order: the leaves of its {@code OR} tree. */
  private static List<Cond> disjuncts(Cond c) {
    List<Cond> leaves = new ArrayList<>();
    collectDisjuncts(c, leaves);
    return leaves;
  }

  private static void collectDisjuncts(Cond c, List<Cond> leaves) {
    if (c instanceof VarkaVectorIR.Or or) {
      collectDisjuncts(or.left(), leaves);
      collectDisjuncts(or.right(), leaves);
    } else {
      leaves.add(c);
    }
  }

  /** Whether {@code root} is a single conjunct that is a disjunction, which the split can cut. */
  private static boolean splittable(List<Cond> root) {
    return root.size() == 1 && disjuncts(root.get(0)).size() > 1;
  }

  /**
   * The smallest count from {@code lo} up to {@code cap} that {@code ok} accepts: doubling until
   * one is accepted, then a binary search below it. It never asks about a count far past the
   * answer, which matters because acceptance is monotone only up to a point - past it, the extra
   * outputs take the kernel's driver over the budget and every count is refused.
   */
  private static OptionalInt smallest(int lo, int cap, IntPredicate ok) {
    int miss = lo - 1;
    int hit = lo;
    while (!ok.test(hit)) {
      if (hit >= cap) {
        return OptionalInt.empty();
      }
      miss = hit;
      hit = Math.min(hit * 2, cap);
    }
    int low = miss + 1;
    int high = hit;
    while (low < high) {
      int mid = (low + high) / 2;
      if (ok.test(mid)) {
        high = mid;
      } else {
        low = mid + 1;
      }
    }
    return OptionalInt.of(low);
  }

  // ---------------------------------------------------------------------------------------------
  // Shared by both
  // ---------------------------------------------------------------------------------------------

  /**
   * An output root: {@code VarkaNodeCompiler.compileNode} over the expression, or over its
   * replacement where it is a {@code RuntimeReplaceable}, plus {@code bit_count} of a
   * {@code bigint}, a popcount of at most 64 in the long lane narrowed to the int Spark types it
   * as. That one is admitted at an output alone for now: inside a tree it is VARKA-335's second
   * part, which row 28's narrowing makes a node's arm rather than a root's.
   */
  private static Optional<VarkaVectorIR> compileRoot(
      Expression expr, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    return switch (expr) {
      case RuntimeReplaceable r -> compileRoot(r.replacement(), inputs, literals, sink);
      // bit_count of a bigint: a count of at most 64 computed in the long lane and narrowed.
      case BitwiseCount c when DataTypes.LongType.equals(c.child().dataType()) ->
          VarkaNodeCompiler.inFamily(VarkaFamily.INT_ARITHMETIC,
              () -> VarkaNodeCompiler.bitCountAtRoot(c, inputs, literals, sink), c, sink);
      default -> VarkaNodeCompiler.compileNode(expr, inputs, literals, sink);
    };
  }

  /**
   * The accepted entries' tables, in the form a compiled projection holds them, for any outputs
   * over them: one predicate pass lays the same tables under several layouts of its roots.
   */
  private record Tables(
      List<Integer> inputOrdinals,
      List<Integer> literals,
      List<VarkaInputBound> inputBounds,
      List<VarkaDerivedInput> derivedInputs,
      List<Long> longLiterals,
      List<VarkaFamily> families) {

    CompiledVarkaProjection projection(List<VarkaVectorIR> outputs, List<DataType> outputTypes) {
      return new CompiledVarkaProjection(outputs, outputTypes, inputOrdinals, literals,
          inputBounds, derivedInputs, longLiterals, families);
    }
  }

  /**
   * {@link Tables} from the compile's own: {@code inputOrdinals} and {@code derivedInputs} from
   * the input table (a derived input is interned under a negative key beside the child ordinals,
   * see {@link VarkaDerivedInput#key}), the literal slots, and the sink's bounds, 64-bit
   * literals and the families the accepted entries used.
   */
  private static Tables tables(VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
    List<Integer> keys = inputs.keys();
    List<Integer> ordinals = new ArrayList<>(keys.size());
    List<VarkaDerivedInput> derived = new ArrayList<>();
    for (int i = 0; i < keys.size(); i++) {
      int key = keys.get(i);
      if (VarkaDerivedInput.isKey(key)) {
        ordinals.add(VarkaDerivedInput.keySourceOrdinal(key));
        derived.add(new VarkaDerivedInput(i, VarkaDerivedInput.keySourceOrdinal(key),
            VarkaDerivedInput.keyKind(key)));
      } else {
        ordinals.add(key);
      }
    }
    return new Tables(ordinals, literals.keys(), sink.inputBounds(inputs), derived,
        sink.longLiteralValues(), sink.familiesUsed());
  }

  /**
   * The sizes of a pass's four tables, and of its list of families used, before an entry or
   * conjunct: the tables are shared across the entries and append in order, so truncating to
   * these restores the state before it, and a declining entry leaves no column or literal behind
   * to widen the kernel's input set, nor a family the kernel does not hold.
   */
  private record Marks(int inputs, int literals, int longs, int bounds, int used) {

    static Marks of(VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
      return new Marks(inputs.size(), literals.size(), sink.longMark(), sink.boundsMark(),
          sink.usedMark());
    }

    void restore(VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
      inputs.truncate(this.inputs);
      literals.truncate(this.literals);
      sink.truncateLong(longs);
      sink.truncateBounds(bounds);
      sink.truncateUsed(used);
    }
  }

  /**
   * Binds an expression's attributes to their ordinals in {@code childOutput}. One per pass: the
   * attribute sequence builds its lookup by expression id on first use, so a sequence per bind
   * would build it once per entry.
   */
  private static UnaryOperator<Expression> binder(Seq<Attribute> childOutput) {
    var attributes = package$.MODULE$.AttributeSeq(childOutput);
    return expr -> BindReferences.bindReference(expr, attributes, false);
  }

  /**
   * The projection's entries as a random-access copy: the parser and the optimizer build a
   * projection list as a Scala {@code List}, where a wrapped {@code get(i)} walks from the head
   * and every pass over the entries would be quadratic in their number.
   */
  private static List<NamedExpression> entries(Seq<NamedExpression> projectList) {
    return List.copyOf(CollectionConverters.asJava(projectList));
  }

  /** The reason an entry of one lane records when the kernel is already on the other. */
  private static String laneMismatch(LaneType entry, LaneType kernel) {
    return "the " + entry + " lane in a kernel on the " + kernel
        + " lane: one kernel holds one lane";
  }

  /** The {@code AND} spine of a condition, in query order: the split the predicate works. */
  static List<Expression> conjuncts(Expression condition) {
    List<Expression> conjuncts = new ArrayList<>();
    collectConjuncts(condition, conjuncts);
    return conjuncts;
  }

  private static void collectConjuncts(Expression condition, List<Expression> conjuncts) {
    if (condition instanceof And and) {
      collectConjuncts(and.left(), conjuncts);
      collectConjuncts(and.right(), conjuncts);
    } else {
      conjuncts.add(condition);
    }
  }
}
