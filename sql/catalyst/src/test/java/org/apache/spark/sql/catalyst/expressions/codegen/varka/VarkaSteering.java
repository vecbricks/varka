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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.IntFunction;

/**
 * Generation steered toward mechanisms the emitter has not reached yet, after QPG (VARKA-280):
 * a draw whose emission reached a set of mechanisms ({@link VarkaEmitMechanism}) no earlier
 * emission had is kept, and later steps mutate a kept draw instead of drawing afresh. The
 * mutations are ones the suites already make, so a mutant is a case the fuzzer can check: one
 * option of {@code VarkaEmitOption.TABLE} changed to another of the values the IR fuzzer draws
 * (a flag flipped, an enum moved to another constant, a count drawn from its fuzz draws), or one
 * of the draw's {@link VarkaVariants}, built as {@link VarkaVariantChecks#variants} builds them.
 *
 * <p>The steering is emission only - the mechanisms are compile-time facts - so a sequence is
 * decided before any kernel runs, and a case the fuzzer then checks is one of a sequence that a
 * seed reproduces in seconds. The unsteered arm of the A/B is the fresh draws alone.
 */
final class VarkaSteering {

  private VarkaSteering() {
  }

  /**
   * The options a mutation of any case redraws: every option the fuzzer's general draws vary
   * ({@link VarkaEmitOption#drawnInGeneral}), in a fixed order.
   */
  private static final List<VarkaEmitOption> FUZZED = VarkaEmitOption.TABLE.stream()
      .filter(VarkaEmitOption::drawnInGeneral)
      .sorted(Comparator.comparing(VarkaEmitOption::name))
      .toList();

  /**
   * The options a mutation of {@code c} redraws: {@link #FUZZED}, then, in table order, each drawn
   * option one node kind alone reads ({@link VarkaEmitOption#readOnlyBy}) whose node {@code c}'s
   * shape holds, as {@code decimalStore} for a shape with a {@code WidenDecimal}. A case whose
   * shape holds no such node gets {@code FUZZED} itself, so its mutations draw the sequence they
   * always drew; one that holds it can have that option changed too.
   */
  static List<VarkaEmitOption> mutable(VarkaFuzzCase c) {
    List<VarkaEmitOption> read = new ArrayList<>();
    for (VarkaEmitOption option : VarkaEmitOption.TABLE) {
      if (option.reason().drawn() && VarkaEmitOption.readOnlyBy(option)
          .filter(kind -> holds(c.roots(), kind)).isPresent()) {
        read.add(option);
      }
    }
    if (read.isEmpty()) {
      return FUZZED;
    }
    List<VarkaEmitOption> all = new ArrayList<>(FUZZED);
    all.addAll(read);
    return List.copyOf(all);
  }

  /** Whether a node of {@code kind} is anywhere in the trees of {@code roots}. */
  private static boolean holds(List<VarkaVectorIR> roots, Class<? extends VarkaVectorIR> kind) {
    var pending = new ArrayDeque<VarkaVectorIR>(roots);
    while (!pending.isEmpty()) {
      VarkaVectorIR node = pending.pop();
      if (kind.isInstance(node)) {
        return true;
      }
      Collections.addAll(pending, VarkaVectorIR.childrenOf(node));
    }
    return false;
  }

  /**
   * What makes a steered step keep its case: a set of mechanisms no earlier emission reached, or
   * a mechanism fewer than {@link #RARE} earlier emissions reached. Random draws reach a new set
   * at most steps, so the first keeps nearly everything; the second keeps what is rare, which is
   * closer to a coverage-guided fuzzer's favoured inputs. The A/B runs both.
   */
  enum Keep { NEW_SET, RARE_MECHANISM }

  /** Below how many earlier emissions a mechanism is rare, for {@link Keep#RARE_MECHANISM}. */
  static final int RARE = 4;

  /** The class name every steering emission builds under: nothing loads the class. */
  private static final String CLASS_NAME = "org.apache.spark.sql.varka.execution.VarkaSteered";

  /**
   * The first {@code budget} fresh draws, each emitted once into {@code trace}: the unsteered
   * arm, and the sequence the IR fuzzer's main tests run.
   */
  static List<VarkaFuzzCase> unsteered(int budget, IntFunction<VarkaFuzzCase> fresh,
      VarkaEmitTrace trace) {
    List<VarkaFuzzCase> cases = new ArrayList<>();
    for (int k = 0; k < budget; k++) {
      VarkaFuzzCase c = fresh.apply(k);
      emit(c, trace);
      cases.add(c);
    }
    return cases;
  }

  /**
   * {@code budget} steps of steered generation, each emitted once into {@code trace}, whose
   * emissions {@code reach} must be attached to: at each step, with even odds or where nothing is
   * kept yet, the next fresh draw, else a mutant of a kept case; a case is kept as {@code keep}
   * says. {@code seed} decides the coin and the mutations, so one seed gives one sequence.
   */
  static List<VarkaFuzzCase> steered(int budget, long seed, IntFunction<VarkaFuzzCase> fresh,
      VarkaEmitTrace trace, VarkaEmitReach reach, Keep keep) {
    Random rnd = new Random(seed ^ 0x53544545524cL);
    List<VarkaFuzzCase> kept = new ArrayList<>();
    List<VarkaFuzzCase> cases = new ArrayList<>();
    int nextFresh = 0;
    for (int k = 0; k < budget; k++) {
      VarkaFuzzCase c = kept.isEmpty() || rnd.nextBoolean()
          ? fresh.apply(nextFresh++)
          : mutate(kept.get(rnd.nextInt(kept.size())), rnd, k);
      int before = reach.emissions();
      emit(c, trace);
      boolean keeps = switch (keep) {
        case NEW_SET -> reach.lastReachedNewSet();
        case RARE_MECHANISM -> reach.lastReachedRare(RARE);
      };
      if (reach.emissions() > before && keeps) {
        kept.add(c);
      }
      cases.add(c);
    }
    return cases;
  }

  /**
   * One mutation of {@code c}: with even odds one option changed, else one of its variants that
   * the emitter's form without a byte budget holds, chosen at random among those that apply; the
   * option is changed where no variant applies. All eight variants are built to choose among
   * them, the arm oracle's evaluation included, which the A/B, opt-in and emission only, affords.
   */
  static VarkaFuzzCase mutate(VarkaFuzzCase c, Random rnd, int step) {
    if (rnd.nextBoolean()) {
      List<VarkaVariants.Variant> fitting = new ArrayList<>();
      List<VarkaVectorIR> strangers = new ArrayList<>();
      for (int i = 0; i < c.roots().size(); i++) {
        strangers.add(c.roots().get((step + 1 + i) % c.roots().size()));
      }
      for (VarkaVariantChecks.KindVariant kv : VarkaVariantChecks.variants(c, strangers,
          VarkaVariants.valueSubtrees(c.roots()), step, VarkaSteering::fits)) {
        kv.variant().filter(VarkaSteering::fits).ifPresent(fitting::add);
      }
      if (!fitting.isEmpty()) {
        VarkaVariants.Variant v = fitting.get(rnd.nextInt(fitting.size()));
        return VarkaVariantChecks.caseOf(c, v).withLabel(c.label() + " -> " + v.kind());
      }
    }
    List<VarkaEmitOption> mutable = mutable(c);
    VarkaEmitOption option = mutable.get(rnd.nextInt(mutable.size()));
    VarkaEmitOptions options = switch (option) {
      case VarkaEmitOption.Flag flag -> flag.with(c.options(), !flag.value(c.options()));
      case VarkaEmitOption.Choice<?> choice when choice.constants().size() < 2 -> c.options();
      case VarkaEmitOption.Choice<?> choice -> {
        // Another constant than the current one, so the mutant differs from its case.
        int current = choice.constants().indexOf(choice.value(c.options()));
        int next = rnd.nextInt(choice.constants().size() - 1);
        yield choice.withIndex(c.options(), next >= current ? next + 1 : next);
      }
      case VarkaEmitOption.Count count ->
          count.with(c.options(), count.fuzzDraws().get(rnd.nextInt(count.fuzzDraws().size())));
    };
    return c.withOptions(options).withLabel(c.label() + " -> " + option.name() + " changed");
  }

  /** Whether the emitter's form without a byte budget holds a variant, as it holds every draw. */
  private static boolean fits(VarkaVariants.Variant v) {
    return VarkaLoopEmitter.fitsBudgets(v.roots(), v.numInputs(),
        VarkaEmitOptions.DEFAULTS.withMethodByteBudget(0));
  }

  /**
   * Emits {@code c} once under its options into {@code trace}. A decline is an emission the
   * trace records, and a refusal on the op cap or the chain depth - a mutant grown past what the
   * form without a budget holds - a step that emitted nothing, as the IR fuzzer skips one; any
   * other refusal is the emitter rejecting a shape the grammar builds, and is thrown.
   */
  static void emit(VarkaFuzzCase c, VarkaEmitTrace trace) {
    try {
      VarkaLoopEmitter.emitTraced(CLASS_NAME, c.roots(), c.numInputs(), c.lits().length,
          c.options(), trace);
    } catch (VarkaEmitDeclined d) {
      // Noted by the trace as a declined emission.
    } catch (IllegalArgumentException e) {
      String message = String.valueOf(e.getMessage());
      if (!message.contains("MAX_FUSED_NODES=") && !message.contains("MAX_CHAIN_DEPTH=")) {
        throw new IllegalStateException(c.label() + ": the emitter rejected the shape", e);
      }
    }
  }
}
