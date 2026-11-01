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
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions.Builder;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions.DecimalStore;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions.Division;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions.FloorMod7;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions.TruncDateForm;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenDecimal;

/**
 * One option of {@link VarkaEmitOptions} as the options table holds it: its name, why it exists,
 * its default, how to read it off a value and set it on a builder, how it reaches
 * {@link VarkaEmitOptions#canonical()}, and - for an int - the values the IR fuzzer draws and the
 * bytes suite's inventory audits.
 *
 * <p>{@link #TABLE} lists every option once, in the record's declaration order, and everything
 * that treats the options as a set reads it rather than its own list: {@code canonical()} renders
 * from it, and the bytes suite's inventory, the IR fuzzer's draws and {@code VarkaEmitDump}'s
 * parser walk it. Adding an option is the record component, its builder setter and {@code with*}
 * method, its javadoc, its value in {@link VarkaEmitOptions#DEFAULTS}, and one entry here.
 */
// Builder is VarkaEmitOptions.Builder everywhere here, the one builder an option sets;
// qualifying each of its uses would only lengthen the options table's lines.
@SuppressWarnings("BadImport")
public sealed interface VarkaEmitOption
    permits VarkaEmitOption.Flag, VarkaEmitOption.Count, VarkaEmitOption.Choice {

  /** Why an option exists: what keeps it in the record rather than deleted. */
  enum Reason {
    /**
     * An alternative form kept as the reference the shipped one is checked against: the A/B that
     * priced the choice stays re-runnable, and the differential suites compare the two.
     */
    REFERENCE,
    /**
     * An alternative kept because which form wins depends on the machine - its vector width or
     * its AVX level - so no single form can be shipped for every host.
     */
    MACHINE,
    /**
     * A value tests and benchmarks vary rather than production: a size limit, where a small value
     * shows a mechanism on a shape of a few outputs and a large one prices a retuning, or the lane
     * count a suite emits for when one JVM must cover every width.
     */
    KNOB,
    /**
     * A correctness check kept switchable so its cost can be priced. Off computes wrong answers,
     * so only the A/B and the differential run it so.
     */
    PRICED_CHECK,
    /**
     * A fault injector: it feeds the emitter a wrong input so a test can show that the emitter's
     * own self-checks fire. The fuzzer never draws one.
     */
    FAULT_INJECTOR,
    /**
     * A diagnostic: it changes what the emitted code reports about a batch - which refusal
     * reason fired - and never an answer or whether a batch is declined. Off by default, because
     * the report costs the loop something production need not pay.
     */
    DIAGNOSTIC,
    /**
     * A form some JVMs miscompile: the one it replaced, kept for the A/B that priced the
     * replacement and for the checks that need it set. The fuzzer and the option matrix never
     * draw one, since they run on hosts where it computes wrong answers.
     */
    MISCOMPILED;

    /**
     * Whether the fuzzer, its steering and the option matrix may vary an option of this reason;
     * {@link VarkaEmitOption#drawnInGeneral} asks this and whether the option is shape-specific.
     */
    public boolean drawn() {
      return this != FAULT_INJECTOR && this != MISCOMPILED;
    }
  }

  /** How an option reaches {@code canonical()}. */
  sealed interface Rendering permits Positional, FlagTag, CountTag, ChoiceTag {}

  /** Rendered by position, always: the first 26 options, which predate the tags. */
  record Positional() implements Rendering {}

  /** A flag shown as {@code |token} when its value is {@code when}, and not at all otherwise. */
  record FlagTag(String token, boolean when) implements Rendering {}

  /** A count shown as {@code |prefix<value>} when off its default, and not at all otherwise. */
  record CountTag(String prefix) implements Rendering {}

  /** A choice shown as {@code |prefix<constant>} when off its default, and not at all otherwise. */
  record ChoiceTag(String prefix) implements Rendering {}

  /** One arm of the bytes suite's inventory: its label and the change it makes to a value. */
  record Arm(String name, UnaryOperator<VarkaEmitOptions> apply) {}

  /**
   * The options under which an option has anything to do, for an option the defaults never reach
   * (VARKA-247). {@code validityOrFirst} orders the per-group validity OR, which the defaults do
   * not emit for any value root: a dense body fills validity once, and a masked one writes it by
   * the bitmap pass or computes the word inside the root. The bytes suite applies its arms over
   * the reference arms that do emit that OR - its audit and its pinned digests - and the test
   * matrix runs its non-default arm there; {@link #arms()} itself stays one option flipped from
   * the defaults.
   */
  record Subject(UnaryOperator<VarkaEmitOptions> apply) {
    /** The defaults: an option whose two values differ there. */
    static final Subject DEFAULTS = new Subject(UnaryOperator.identity());

    public Subject {
      Objects.requireNonNull(apply, "apply");
    }

    /**
     * The options this subject changes, as {@code name=value} in table order and comma-separated,
     * which {@code VarkaMatrix.parse} reads back; empty for {@link #DEFAULTS}. Derived from what
     * {@code apply} does, so it cannot describe a different base from the one it names.
     */
    public String label() {
      VarkaEmitOptions base = apply.apply(VarkaEmitOptions.DEFAULTS);
      return TABLE.stream()
          .filter(o -> !o.text(base).equals(o.text(VarkaEmitOptions.DEFAULTS)))
          .map(o -> o.name() + "=" + o.text(base))
          .collect(Collectors.joining(","));
    }
  }

  Rendering POSITIONAL = new Positional();

  String name();

  Reason reason();

  Rendering rendering();

  /**
   * The one node kind whose emission reads {@code option}, for an option no other emission reads,
   * or empty for an option of the emission in general. Such an option changes no kernel without
   * that node, so varying it over shapes that lack one tests nothing and, in the fuzzer, shifts
   * every later draw of a committed seed: {@link #drawnInGeneral} leaves it out of the general
   * draws and the option matrix, and the fuzzer's decimal sequence and the steering draw it for a
   * shape that holds the node ({@code VarkaIrFuzzSuite}'s decimal draw, {@code VarkaSteering}).
   * Only a {@link Choice} carries one so far, as its {@code readOnlyBy} component.
   */
  static Optional<Class<? extends VarkaVectorIR>> readOnlyBy(VarkaEmitOption option) {
    return switch (option) {
      case Flag _ -> Optional.empty();
      case Count _ -> Optional.empty();
      case Choice<?> choice -> choice.readOnlyBy();
    };
  }

  /**
   * Whether the fuzzer's general draws and the option matrix vary {@code option}, and the
   * steering for any case: its reason is drawn ({@link Reason#drawn()}) and it is not read by one
   * node kind alone ({@link #readOnlyBy}). A static method rather than a default one, so that
   * initializing a record never initializes the interface first (Error Prone's
   * {@code ClassInitializationDeadlock}).
   */
  static boolean drawnInGeneral(VarkaEmitOption option) {
    return option.reason().drawn() && readOnlyBy(option).isEmpty();
  }

  /** Sets this option's default on a builder. */
  void applyDefault(Builder builder);

  /** This option's value in {@code options}, as {@code canonical()} prints it. */
  String text(VarkaEmitOptions options);

  /** This option's tag in {@code options}: {@code ""} unless its rendering is a tag that shows. */
  String tag(VarkaEmitOptions options);

  /** The inventory's arms for this option, each labelled {@code name=value}. */
  List<Arm> arms();

  /** A boolean option. */
  record Flag(String name, Reason reason, boolean defaultValue, Predicate<VarkaEmitOptions> get,
      BiConsumer<Builder, Boolean> set, Rendering rendering, Subject subject)
      implements VarkaEmitOption {

    public Flag {
      if (rendering instanceof CountTag || rendering instanceof ChoiceTag) {
        throw VarkaErrors.illegalArgument(name + ": a flag renders by position or as a flag tag");
      }
      Objects.requireNonNull(subject, "subject");
    }

    /** A flag whose two values differ at the defaults. */
    public Flag(String name, Reason reason, boolean defaultValue, Predicate<VarkaEmitOptions> get,
        BiConsumer<Builder, Boolean> set, Rendering rendering) {
      this(name, reason, defaultValue, get, set, rendering, Subject.DEFAULTS);
    }

    public boolean value(VarkaEmitOptions options) {
      return get.test(options);
    }

    public VarkaEmitOptions with(VarkaEmitOptions options, boolean value) {
      var builder = options.toBuilder();
      set.accept(builder, value);
      return builder.build();
    }

    @Override
    public void applyDefault(Builder builder) {
      set.accept(builder, defaultValue);
    }

    @Override
    public String text(VarkaEmitOptions options) {
      return String.valueOf(value(options));
    }

    @Override
    public String tag(VarkaEmitOptions options) {
      return rendering instanceof FlagTag(String token, boolean when) && value(options) == when
          ? "|" + token : "";
    }

    @Override
    public List<Arm> arms() {
      return List.of(new Arm(name + "=true", o -> with(o, true)),
          new Arm(name + "=false", o -> with(o, false)));
    }
  }

  /**
   * An int option. {@code fuzzDraws} are the values the IR fuzzer draws from, and
   * {@code auditValues} the inventory's arms; either may be empty.
   */
  record Count(String name, Reason reason, int defaultValue, ToIntFunction<VarkaEmitOptions> get,
      ObjIntConsumer<Builder> set, Rendering rendering, List<Integer> fuzzDraws,
      List<Integer> auditValues) implements VarkaEmitOption {

    public Count {
      if (rendering instanceof FlagTag || rendering instanceof ChoiceTag) {
        throw VarkaErrors.illegalArgument(
            name + ": a count renders by position or as a count tag");
      }
      fuzzDraws = List.copyOf(fuzzDraws);
      auditValues = List.copyOf(auditValues);
    }

    public int value(VarkaEmitOptions options) {
      return get.applyAsInt(options);
    }

    public VarkaEmitOptions with(VarkaEmitOptions options, int value) {
      var builder = options.toBuilder();
      set.accept(builder, value);
      return builder.build();
    }

    @Override
    public void applyDefault(Builder builder) {
      set.accept(builder, defaultValue);
    }

    @Override
    public String text(VarkaEmitOptions options) {
      return String.valueOf(value(options));
    }

    @Override
    public String tag(VarkaEmitOptions options) {
      return rendering instanceof CountTag(String prefix) && value(options) != defaultValue
          ? "|" + prefix + value(options) : "";
    }

    @Override
    public List<Arm> arms() {
      var arms = new ArrayList<Arm>();
      for (int v : auditValues) {
        arms.add(new Arm(name + "=" + v, o -> with(o, v)));
      }
      return arms;
    }
  }

  /**
   * An enum option: one of {@code type}'s constants. The three that predate the tags render by
   * position; a later one renders as a {@link ChoiceTag}, so that adding it leaves every other
   * variant's rendering as it was. {@code readOnlyBy} names the node kind whose emission alone
   * reads it, if one does ({@link VarkaEmitOption#readOnlyBy}).
   */
  record Choice<E extends Enum<E>>(String name, Reason reason, Class<E> type, E defaultValue,
      Function<VarkaEmitOptions, E> get, BiConsumer<Builder, E> set, Rendering rendering,
      Optional<Class<? extends VarkaVectorIR>> readOnlyBy) implements VarkaEmitOption {

    public Choice {
      if (rendering instanceof FlagTag || rendering instanceof CountTag) {
        throw VarkaErrors.illegalArgument(
            name + ": a choice renders by position or as a choice tag");
      }
      Objects.requireNonNull(readOnlyBy, "readOnlyBy");
    }

    /** A choice rendered by position, as the three that predate the tags are. */
    public Choice(String name, Reason reason, Class<E> type, E defaultValue,
        Function<VarkaEmitOptions, E> get, BiConsumer<Builder, E> set) {
      this(name, reason, type, defaultValue, get, set, POSITIONAL, Optional.empty());
    }

    public E value(VarkaEmitOptions options) {
      return get.apply(options);
    }

    public VarkaEmitOptions with(VarkaEmitOptions options, E value) {
      var builder = options.toBuilder();
      set.accept(builder, value);
      return builder.build();
    }

    public List<E> constants() {
      return List.of(type.getEnumConstants());
    }

    /** {@code options} with the {@code index}-th constant: how a random draw picks one. */
    public VarkaEmitOptions withIndex(VarkaEmitOptions options, int index) {
      return with(options, constants().get(index));
    }

    /**
     * {@code options} with the constant named {@code constant}; an unknown name throws
     * {@link IllegalArgumentException}, naming the constants there are.
     */
    public VarkaEmitOptions withNamed(VarkaEmitOptions options, String constant) {
      for (E e : constants()) {
        if (e.name().equals(constant)) {
          return with(options, e);
        }
      }
      throw VarkaErrors.illegalArgument(
          name + " has no constant " + constant + "; it has " + constants());
    }

    @Override
    public void applyDefault(Builder builder) {
      set.accept(builder, defaultValue);
    }

    @Override
    public String text(VarkaEmitOptions options) {
      return value(options).toString();
    }

    @Override
    public String tag(VarkaEmitOptions options) {
      return rendering instanceof ChoiceTag(String prefix) && value(options) != defaultValue
          ? "|" + prefix + value(options) : "";
    }

    @Override
    public List<Arm> arms() {
      var arms = new ArrayList<Arm>();
      for (E constant : constants()) {
        arms.add(new Arm(name + "=" + constant, o -> with(o, constant)));
      }
      return arms;
    }
  }

  /** The values the fuzzer draws a budget from: small enough to bring its mechanism into view. */
  List<Integer> SMALL_BUDGETS = List.of(8, 24, 32);

  /**
   * Every option, once, in the record's declaration order - which is also the order
   * {@code canonical()} renders them in, the positional ones first. See each option's
   * {@code @param} on {@link VarkaEmitOptions} for what it does and the measurement behind its
   * default.
   *
   * <p>Production never initialises this list: {@link VarkaEmitOptions#DEFAULTS} is written out
   * and {@code canonical()} returns before reading it for the defaults, so the cost of linking
   * its method references falls on tests alone.
   */
  List<VarkaEmitOption> TABLE = List.of(
      new Count("groupBudget", Reason.KNOB, VarkaEmitBudget.GROUP_BUDGET,
          VarkaEmitOptions::groupBudget, Builder::groupBudget, POSITIONAL,
          SMALL_BUDGETS, List.of(64, 800)),
      new Count("fusedCeiling", Reason.KNOB, VarkaEmitBudget.FUSED_CEILING,
          VarkaEmitOptions::fusedCeiling, Builder::fusedCeiling, POSITIONAL,
          SMALL_BUDGETS, List.of(200, 800)),
      new Flag("cse", Reason.REFERENCE, true,
          VarkaEmitOptions::cse, Builder::cse, POSITIONAL),
      new Flag("shareChronoPrefix", Reason.REFERENCE, true,
          VarkaEmitOptions::shareChronoPrefix, Builder::shareChronoPrefix, POSITIONAL),
      new Flag("denseValidityOnce", Reason.REFERENCE, true,
          VarkaEmitOptions::denseValidityOnce, Builder::denseValidityOnce, POSITIONAL),
      new Flag("elideChronoMonth", Reason.REFERENCE, true,
          VarkaEmitOptions::elideChronoMonth, Builder::elideChronoMonth, POSITIONAL),
      new Flag("neriSchneiderMonth", Reason.REFERENCE, true,
          VarkaEmitOptions::neriSchneiderMonth, Builder::neriSchneiderMonth, POSITIONAL),
      new Flag("julianMap", Reason.REFERENCE, true,
          VarkaEmitOptions::julianMap, Builder::julianMap, POSITIONAL),
      new Flag("guardDayProducers", Reason.PRICED_CHECK, true,
          VarkaEmitOptions::guardDayProducers, Builder::guardDayProducers, POSITIONAL),
      new Flag("validityByWidth", Reason.REFERENCE, true,
          VarkaEmitOptions::validityByWidth, Builder::validityByWidth, POSITIONAL),
      new Flag("validityOrFirst", Reason.REFERENCE, true,
          VarkaEmitOptions::validityOrFirst, Builder::validityOrFirst, POSITIONAL,
          new Subject(
              o -> o.toBuilder().validityByBitmap(false).denseValidityOnce(false).build())),
      new Flag("validityByBitmap", Reason.REFERENCE, true,
          VarkaEmitOptions::validityByBitmap, Builder::validityByBitmap, POSITIONAL),
      new Flag("checkIntOverflow", Reason.PRICED_CHECK, true,
          VarkaEmitOptions::checkIntOverflow, Builder::checkIntOverflow, POSITIONAL),
      new Count("lanesOverride", Reason.KNOB, 0,
          VarkaEmitOptions::lanesOverride, Builder::lanesOverride, POSITIONAL,
          List.of(2, 4, 8, 16, 32), List.of()),
      new Choice<>("truncDate", Reason.REFERENCE, TruncDateForm.class, TruncDateForm.SUBTRACT,
          VarkaEmitOptions::truncDate, Builder::truncDate),
      new Choice<>("floorMod7", Reason.REFERENCE, FloorMod7.class, FloorMod7.MAGIC,
          VarkaEmitOptions::floorMod7, Builder::floorMod7),
      new Choice<>("division", Reason.MACHINE, Division.class, Division.MAGIC,
          VarkaEmitOptions::division, Builder::division),
      new Count("useAVX", Reason.MACHINE, VarkaEmitOptions.USE_AVX_UNKNOWN,
          VarkaEmitOptions::useAVX, Builder::useAVX, POSITIONAL,
          List.of(VarkaEmitOptions.USE_AVX_UNKNOWN, 0, 2, 3),
          List.of(VarkaEmitOptions.USE_AVX_UNKNOWN, 0, 1, 2, 3)),
      new Flag("misdescribeAdd", Reason.FAULT_INJECTOR, false,
          VarkaEmitOptions::misdescribeAdd, Builder::misdescribeAdd, POSITIONAL),
      new Flag("misdescribeWordLiveness", Reason.FAULT_INJECTOR, false,
          VarkaEmitOptions::misdescribeWordLiveness, Builder::misdescribeWordLiveness,
          POSITIONAL),
      new Flag("guardUnderArm", Reason.REFERENCE, true,
          VarkaEmitOptions::guardUnderArm, Builder::guardUnderArm, POSITIONAL),
      new Flag("shareWholeNodes", Reason.REFERENCE, true,
          VarkaEmitOptions::shareWholeNodes, Builder::shareWholeNodes, POSITIONAL),
      new Flag("validityByWord", Reason.REFERENCE, false,
          VarkaEmitOptions::validityByWord, Builder::validityByWord, POSITIONAL),
      new Flag("mulHiDivide", Reason.REFERENCE, true,
          VarkaEmitOptions::mulHiDivide, Builder::mulHiDivide, POSITIONAL),
      new Flag("narrowHalfSpecies", Reason.REFERENCE, false,
          VarkaEmitOptions::narrowHalfSpecies, Builder::narrowHalfSpecies, POSITIONAL),
      // A machine choice rather than a reference form: the arm that wins depends on the host's
      // vector width (VARKA-329.md 9.2). The native arm is the default, faster on every measured
      // all-narrow shape (9.6); the widened one is the losing arm, kept and fuzzed in the
      // matrix. Its tag keeps the default's rendering empty.
      new Flag("narrowLanes", Reason.MACHINE, true, VarkaEmitOptions::narrowLanes,
          Builder::narrowLanes, new FlagTag("widenedNarrow", false)),
      new Count("methodByteBudget", Reason.KNOB, VarkaEmitBudget.HUGE_METHOD_LIMIT,
          VarkaEmitOptions::methodByteBudget, Builder::methodByteBudget, POSITIONAL,
          List.of(0, 8000, 1000, 2000, 4000), List.of(0, 8000)),
      new Flag("rangeSets", Reason.REFERENCE, true,
          VarkaEmitOptions::rangeSets, Builder::rangeSets, new FlagTag("noRangeSets", false)),
      new Flag("splitConditions", Reason.REFERENCE, true,
          VarkaEmitOptions::splitConditions, Builder::splitConditions,
          new FlagTag("noSplitConditions", false)),
      new Flag("groupLocalSlots", Reason.REFERENCE, true,
          VarkaEmitOptions::groupLocalSlots, Builder::groupLocalSlots,
          new FlagTag("kernelWideSlots", false)),
      new Flag("materializeChronoPrefix", Reason.REFERENCE, true,
          VarkaEmitOptions::materializeChronoPrefix, Builder::materializeChronoPrefix,
          new FlagTag("recomputePrefix", false)),
      new Count("callSiteBudget", Reason.KNOB, VarkaEmitBudget.CALL_SITE_BUDGET,
          VarkaEmitOptions::callSiteBudget, Builder::callSiteBudget, new CountTag("callSites="),
          SMALL_BUDGETS, List.of(0, VarkaEmitBudget.CALL_SITE_BUDGET)),
      new Count("heavyGroupOutputs", Reason.KNOB, VarkaEmitBudget.HEAVY_GROUP_OUTPUTS,
          VarkaEmitOptions::heavyGroupOutputs, Builder::heavyGroupOutputs, new CountTag("heavy="),
          SMALL_BUDGETS, List.of(0, VarkaEmitBudget.HEAVY_GROUP_OUTPUTS)),
      new Flag("predictGrouping", Reason.REFERENCE, true,
          VarkaEmitOptions::predictGrouping, Builder::predictGrouping,
          new FlagTag("predictGrouping", true)),
      new Flag("driverOutputTable", Reason.REFERENCE, true,
          VarkaEmitOptions::driverOutputTable, Builder::driverOutputTable,
          new FlagTag("unrolledDriver", false)),
      new Flag("exactGrouping", Reason.REFERENCE, true,
          VarkaEmitOptions::exactGrouping, Builder::exactGrouping,
          new FlagTag("greedyGrouping", false)),
      new Flag("splitDriver", Reason.REFERENCE, true,
          VarkaEmitOptions::splitDriver, Builder::splitDriver, new FlagTag("wholeDriver", false)),
      new Flag("severalKernels", Reason.REFERENCE, true,
          VarkaEmitOptions::severalKernels, Builder::severalKernels,
          new FlagTag("oneKernel", false)),
      new Flag("elideUnreadLocals", Reason.REFERENCE, true,
          VarkaEmitOptions::elideUnreadLocals, Builder::elideUnreadLocals,
          new FlagTag("elideUnreadLocals", true)),
      new Flag("planSize", Reason.REFERENCE, true,
          VarkaEmitOptions::planSize, Builder::planSize, new FlagTag("planSize", true)),
      new Count("misdescribeDriverBytes", Reason.FAULT_INJECTOR, 0,
          VarkaEmitOptions::misdescribeDriverBytes, Builder::misdescribeDriverBytes,
          new CountTag("misdescribeDriverBytes="), List.of(), List.of()),
      new Count("forceResidualAt", Reason.FAULT_INJECTOR, 0,
          VarkaEmitOptions::forceResidualAt, Builder::forceResidualAt,
          new CountTag("forceResidualAt="), List.of(), List.of()),
      new Count("misdescribeRollback", Reason.FAULT_INJECTOR, 0,
          VarkaEmitOptions::misdescribeRollback, Builder::misdescribeRollback,
          new CountTag("misdescribeRollback="), List.of(), List.of()),
      new Flag("exactRefusalStatus", Reason.DIAGNOSTIC, false,
          VarkaEmitOptions::exactRefusalStatus, Builder::exactRefusalStatus,
          new FlagTag("exactRefusalStatus", true)),
      new Flag("groupBudgetUnderPrediction", Reason.REFERENCE, false,
          VarkaEmitOptions::groupBudgetUnderPrediction, Builder::groupBudgetUnderPrediction,
          new FlagTag("budgetBoundsGroups", true)),
      new Flag("dropStackMaps", Reason.FAULT_INJECTOR, false,
          VarkaEmitOptions::dropStackMaps, Builder::dropStackMaps,
          new FlagTag("dropStackMaps", true)),
      new Count("misdescribeDataBytes", Reason.FAULT_INJECTOR, 0,
          VarkaEmitOptions::misdescribeDataBytes, Builder::misdescribeDataBytes,
          new CountTag("misdescribeDataBytes="), List.of(), List.of()),
      // JDK 25 miscompiles fromLong of a mask's toLong on AVX2 (VARKA-396), so by default the
      // masked body ANDs each word with an all-ones C2 cannot fold first, behind a guard that
      // folds away on hosts with mask registers; the bare fromLong is the arm, kept for the A/B and
      // for the JDKs that fix the rule (VARKA-491). Its tag keeps the default's rendering empty.
      new Flag("fromLongMasks", Reason.MISCOMPILED, false,
          VarkaEmitOptions::fromLongMasks, Builder::fromLongMasks,
          new FlagTag("fromLongMasks", true)),
      // A machine choice: which of the decimal store's two interleaves is native depends on the
      // host (`VARKA-157.md` 2.2), so the losing arm stays behind the option. Its tag keeps the
      // default's rendering empty. Only a WidenDecimal root's store reads it.
      new Choice<>("decimalStore", Reason.MACHINE, DecimalStore.class, DecimalStore.REARRANGE,
          VarkaEmitOptions::decimalStore, Builder::decimalStore,
          new ChoiceTag("decimalStore="), Optional.of(WidenDecimal.class)));

  /** The option with this name. */
  static VarkaEmitOption named(String name) {
    for (var option : TABLE) {
      if (option.name().equals(name)) {
        return option;
      }
    }
    throw VarkaErrors.illegalArgument("no emit option " + name);
  }
}
