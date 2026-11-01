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

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.PartialVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaDecline;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaOutputSpec;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.TimeType;
import org.apache.spark.sql.types.YearMonthIntervalType;

/**
 * The emitter's debugging view, one command: what a projection compiles to, what the emitter
 * emits for it, and what that costs per method. Driven by {@code dev/varka_emit.sh}, which also
 * runs it under {@code -XX:CompileCommand=print} for the assembly.
 *
 * <pre>
 *   dev/varka_emit.sh "year(d)"
 *   dev/varka_emit.sh "year(d)" "month(d)" --options shareChronoPrefix=false
 *   dev/varka_emit.sh "date_add(d, 7)" --columns d:date --asm
 * </pre>
 *
 * <p>Expressions are SQL, parsed by Catalyst's parser and resolved against the columns given with
 * {@code --columns name:type,...} (default {@code d:date,d2:date,i:int,sh:short,by:byte}; {@code
 * ymm}, {@code ymy} and {@code ym} declare year-month interval columns by field) and the built-in
 * function registry, then handed to {@link VarkaExpressionCompiler} exactly as a projection would
 * be. The output is the IR each entry lowered to, the shape hash production would name the class
 * by, and for every emitted method its bytecode size, its {@code IntVector}, {@code
 * DoubleVector}, {@code convertShape} and {@code VectorMask} invocation counts (the metric the
 * {@code VarkaEmitter*Suite}s' op-count tests use, so a prediction registered from here is on the
 * suite's own scale) and its line-map entries. With {@code --rounds N} the kernel is also loaded
 * and run N times over synthetic data, which is what lets the wrapper's {@code --asm} get C2's
 * standard compilation of the loop method printed. After the table comes the class against what
 * the JVM enforces ({@code VARKA-87.md}): the constant pool count, the widest signature's
 * parameter slots, and either a line saying every method is under {@code HugeMethodLimit} or one
 * {@code OVER LIMIT} line per method that is not - a method HotSpot will load and run interpreted
 * for the life of the JVM without saying so. {@code --rows N} sets the batch length (1024 by
 * default). A length that is not a multiple of the lane count gives every batch a tail, so the
 * epilogue does vector work rather than returning at once, which is the only way a probe can see
 * what the tail costs. The time of the second half of the rounds is reported, after tiering has
 * had the first half to settle; it is one run in one fork, a probe's reading and not a
 * benchmark's. {@code --repeat N} re-emits the shape N times after the first emission and reports
 * the median time per emission: a loop for a profiler to sample - {@code dev/varka_jfr_frames.py}
 * reads a JFR recording of it by phase - and again a probe's reading, where {@code
 * VarkaEmissionBenchmark} is the number.
 *
 * <p>Options are named as in {@code VarkaEmitOption.TABLE} ({@code --options
 * cse=false,groupBudget=24}), so a new option is settable here once it has its table entry.
 *
 * <p>{@code --table} prints instead the markdown a plan's registered-op-counts section wants: one
 * row per expression, one column per option variant given with {@code --variant k=v,...} (the
 * defaults first), each cell every lane op {@code loopDense0} runs - on whichever vector type -
 * and a delta column per variant against the defaults. That is the table VARKA-53 and VARKA-54
 * built by hand; summing the types rather than reading {@code IntVector} alone is what keeps a
 * variant that moves work onto the double lane from reporting the move as a saving.
 *
 * <p>{@code --replay <file>} replays a reproduction file a kernel failure wrote (VARKA-315,
 * {@link VarkaReplay}) and exits 0 when it fails with the recorded signature.
 */
public final class VarkaEmitDump {

  // The interval columns are in the default set because the benchmark lists that cite this tool
  // as "the source of truth [that] regenerates every number here" are full of them: with them
  // absent, the documented command threw "unknown column ymy" for every chain entry and printed
  // no table, so the recipe for re-taking a stale op count did not work.
  private static final String DEFAULT_COLUMNS =
      "d:date,d2:date,i:int,sh:short,by:byte,ymm:ymm,ymy:ymy,ym:ym";
  private static final String CLASS_NAME = "org.apache.spark.sql.varka.execution.VarkaFusedDump";

  private VarkaEmitDump() {
  }

  /** The command line, parsed. */
  private record Args(List<String> exprs, String columns, String options, int rounds, int repeat,
      int rows, int nulls, boolean table, List<String> variants, String replay) {

    static Args parse(String[] args) {
      var exprs = new ArrayList<String>();
      var variants = new ArrayList<String>();
      String columns = DEFAULT_COLUMNS;
      String options = "";
      int rounds = 0;
      int repeat = 0;
      int rows = 1024;
      int nulls = 0;
      boolean table = false;
      String replay = null;
      for (int i = 0; i < args.length; i++) {
        switch (args[i]) {
          case "--columns" -> columns = args[++i];
          case "--options" -> options = args[++i];
          case "--rounds" -> rounds = Integer.parseInt(args[++i]);
          case "--repeat" -> repeat = Integer.parseInt(args[++i]);
          case "--rows" -> rows = Integer.parseInt(args[++i]);
          case "--nulls" -> nulls = Integer.parseInt(args[++i]);
          case "--table" -> table = true;
          case "--variant" -> variants.add(args[++i]);
          case "--replay" -> replay = args[++i];
          default -> exprs.add(args[i]);
        }
      }
      return new Args(exprs, columns, options, rounds, repeat, rows, nulls, table, variants,
          replay);
    }
  }

  public static void main(String[] argv) throws IOException {
    var args = Args.parse(argv);
    if (args.replay() != null) {
      replay(Path.of(args.replay()));
      return;
    }
    if (args.exprs().isEmpty()) {
      System.err.println("usage: VarkaEmitDump <sql expression>... "
          + "[--columns d:date,i:int] [--options cse=false,...] [--rounds N] [--rows N] "
          + "[--nulls N] [--repeat N] | --replay <reproduction file>");
      System.exit(2);
    }
    List<Attribute> childOutput = parseColumns(args.columns());
    var options = applyOptions(VarkaEmitOptions.DEFAULTS, args.options());
    var named = new ArrayList<NamedExpression>();
    for (var e : args.exprs()) {
      named.add(VarkaSqlResolve.named(VarkaSqlResolve.parse(e, seq(childOutput)), "c"));
    }
    if (args.table()) {
      printTable(args.exprs(), named, childOutput, options, args.variants());
      return;
    }
    Optional<PartialVarkaProjection> compiled =
        VarkaExpressionCompiler.compilePartial(seq(named), seq(childOutput), options);
    if (compiled.isEmpty()) {
      report("nothing fused: every entry declined or no column is referenced");
      VarkaExpressionCompiler.declines(seq(named), seq(childOutput), options).forEach(
          (position, decline) -> report(String.format("entry %2d  %-40s  %s", position,
              args.exprs().get(position), decline)));
      System.exit(1);
      return;
    }
    dump(args, compiled.get(), childOutput, options);
  }

  /**
   * {@code --replay}: a reproduction file (VARKA-315) replayed to its failure, the report printed,
   * and the exit status 0 when the replay failed with the file's signature and 1 otherwise.
   */
  private static void replay(Path file) throws IOException {
    var replay = VarkaReplay.of(VarkaReproduction.parse(Files.readString(file)));
    replay.report().forEach(VarkaEmitDump::report);
    System.exit(replay.reproduced() ? 0 : 1);
  }

  /** The entries, the kernel's tables and IR, the shape hash, and the per-method table. */
  private static void dump(Args args, PartialVarkaProjection partial, List<Attribute> childOutput,
      VarkaEmitOptions options) {
    for (int position = 0; position < args.exprs().size(); position++) {
      VarkaDecline decline = partial.declines().get(position);
      report(String.format("entry %2d  %-40s  %s%s", position, args.exprs().get(position),
          render(partial.specs().get(position)), decline == null ? "" : " - " + decline));
    }
    CompiledVarkaProjection fused = partial.fused();
    report("");
    report("inputs (child ordinals, in kernel order): " + join(fused.inputOrdinals()));
    report("literals (scalarArgs, in slot order):    " + join(fused.literals()));
    for (int k = 0; k < fused.outputs().size(); k++) {
      report("output " + k + " IR: " + VarkaVectorIR.canonical(fused.outputs().get(k)));
    }
    // The literal count is both arrays': a long-lane shape keeps its literals in longArgs.
    var key = new VarkaShapeKey(fused.outputs(), fused.inputOrdinals().size(),
        fused.numLiterals(), options);
    report("shape hash: " + VarkaShapeCacheImpl.shapeHash(key) + "  options: "
        + (options.isDefault() ? "(defaults)" : options.canonical()));

    byte[] bytes;
    try {
      bytes = emit(fused, options);
    } catch (VarkaEmitDeclined d) {
      report("declined by the emitter: " + d.getMessage());
      return;
    }
    if (args.repeat() > 0) {
      reportRepeat(fused, options, args.repeat());
    }
    report("");
    report(String.format("%-18s %6s %9s %10s %12s %7s %10s %8s %5s", "method", "bytes",
        "IntVector", "LongVector", "DoubleVector", "convert", "VectorMask", "validity", "lines"));
    var methods = new ArrayList<String>(VarkaEmitterTestSupport.methodNames(bytes));
    methods.remove("<init>");
    methods.sort(null);
    for (var m : methods) {
      report(String.format("%-18s %6d %9d %10d %12d %7d %10d %8d %5d", m,
          VarkaEmitterTestSupport.codeSize(bytes, m),
          VarkaEmitterTestSupport.invocationCount(bytes, m, "jdk.incubator.vector.IntVector"),
          // The long lane (VARKA-85), read beside the int one so a 64-bit body's own ops show.
          VarkaEmitterTestSupport.invocationCount(bytes, m, "jdk.incubator.vector.LongVector"),
          // The double lane, which a `division` setting other than MAGIC moves work onto:
          // without these two columns the IntVector count alone reports such a body as cheaper
          // than it is, which is the one reading this table must never give.
          VarkaEmitterTestSupport.invocationCount(bytes, m, "jdk.incubator.vector.DoubleVector"),
          // `convertShape` is declared on `Vector` itself, so it is the only thing counted here.
          VarkaEmitterTestSupport.invocationCount(bytes, m, "jdk.incubator.vector.Vector"),
          VarkaEmitterTestSupport.invocationCount(bytes, m, "jdk.incubator.vector.VectorMask"),
          // Validity work, which is the metric VARKA-70 moves: everything the method invokes on
          // VarkaVectorSupport except the segment mapping, which every body mode emits per
          // segment and which would keep the count off zero however much validity work went
          // away.
          VarkaEmitterTestSupport.invocationCount(bytes, m,
              "org.apache.spark.sql.varka.vector.VarkaVectorSupport", List.of("ofAddress")),
          VarkaEmitterTestSupport.lineNumbers(bytes, m).size()));
    }
    reportLimits(bytes, options);
    VarkaDebugInfo.read(bytes).ifPresent(info -> {
      report("");
      report("line map (line=node):");
      for (var line : info.lineMap().split("\n")) {
        report("  " + line);
      }
    });

    if (args.rounds() > 0) {
      var inputs = new ArrayList<Attribute>();
      for (int ordinal : fused.inputOrdinals()) {
        inputs.add(childOutput.get(ordinal));
      }
      runHot(bytes, fused, inputs, args.rounds(), args.nulls(), args.rows(),
          options);
    }
  }

  /**
   * The same shape emitted again and again: a loop a profiler can sample, with the time per
   * emission beside it. The second half's median is the settled reading, after tiering has had the
   * first half; one fork, a probe's number, not the benchmark's.
   */
  private static void reportRepeat(CompiledVarkaProjection fused, VarkaEmitOptions options,
      int repeat) {
    var times = new long[repeat];
    for (int r = 0; r < repeat; r++) {
      long t = System.nanoTime();
      emit(fused, options);
      times[r] = System.nanoTime() - t;
    }
    long[] all = times.clone();
    Arrays.sort(all);
    long[] second = Arrays.copyOfRange(times, repeat / 2, repeat);
    Arrays.sort(second);
    report(String.format("repeat %d: median %.1f ms per emission, second half's median %.1f ms",
        repeat, all[all.length / 2] / 1e6, second[second.length / 2] / 1e6));
  }

  /**
   * The class against what the JVM enforces (VARKA-87.md): weight is the emitter's proxy, and
   * these are the quantities it stands in for. A method over HugeMethodLimit is the finding this
   * exists to make visible, since the class would load and run regardless.
   */
  private static void reportLimits(byte[] bytes, VarkaEmitOptions options) {
    var measured = VarkaEmittedClass.measure(bytes);
    // The first of equal widths, as the slots are listed.
    Map.Entry<String, Integer> widest = null;
    for (var slots : measured.parameterSlots().entrySet()) {
      if (widest == null || slots.getValue() > widest.getValue()) {
        widest = slots;
      }
    }
    report(String.format("class: constant pool %d of %d entries; widest signature %s at %d of %d "
        + "parameter slots", measured.constantPoolCount(), VarkaEmitBudget.CONSTANT_POOL_CAP,
        widest.getKey(), widest.getValue(), VarkaEmitBudget.PARAMETER_SLOT_CAP));
    var over = VarkaEmitBudget.overLimits(measured);
    if (over.isEmpty()) {
      report("every method is under HugeMethodLimit (" + VarkaEmitBudget.HUGE_METHOD_LIMIT + ")");
    } else {
      over.forEach(finding -> report("OVER LIMIT: " + finding));
    }
    // C1's limit rather than the JVM's: a group method over the call-site budget belongs to a
    // narrow group of heavy outputs the emitter leaves on purpose, or to a group whose split would
    // have declined the class, or the budget was off (VARKA-209). Not an error, but worth seeing:
    // the method runs interpreted until C2 compiles it. A heavy kernel has one such method per
    // group and side, so the list is cut after the first few.
    int budget = options.callSiteBudget();
    if (budget <= 0) {
      report("the call-site budget is off");
      return;
    }
    var overSites = VarkaEmitBudget.overCallSiteBudget(measured, budget);
    if (overSites.isEmpty()) {
      report("every group method is under the call-site budget (" + budget
          + " vector call sites)");
      return;
    }
    overSites.stream().limit(4).forEach(finding -> report("PAST C1: " + finding));
    if (overSites.size() > 4) {
      report("PAST C1: and " + (overSites.size() - 4) + " more group methods over the budget");
    }
  }

  /**
   * Every lane op {@code loopDense0} runs, whichever vector type it runs it on. {@code IntVector}
   * alone was the whole answer while every lowering stayed on the int lane; a {@code division}
   * setting that routes a division through the double lane trades two int ops for seven spread
   * over three types, and counting only the first would report that as a saving.
   */
  private static int laneOps(byte[] bytes) {
    int sum = 0;
    for (var owner : List.of("IntVector", "DoubleVector", "Vector", "LongVector")) {
      sum += VarkaEmitterTestSupport.invocationCount(bytes, "loopDense0",
          "jdk.incubator.vector." + owner);
    }
    return sum;
  }

  /**
   * {@code --table}: {@code loopDense0}'s lane-op count per expression, under the defaults and
   * under each {@code --variant}, with the delta - each expression emitted alone, as a one-output
   * kernel.
   */
  private static void printTable(List<String> exprs, List<NamedExpression> named,
      List<Attribute> childOutput, VarkaEmitOptions base, List<String> variants) {
    var titles = new ArrayList<String>();
    var columns = new ArrayList<VarkaEmitOptions>();
    titles.add("defaults");
    columns.add(base);
    for (var v : variants) {
      titles.add(v);
      columns.add(applyOptions(base, v));
    }
    var header = new StringBuilder("| expression | " + String.join(" | ", titles));
    variants.forEach(v -> header.append(" | delta ").append(v));
    report(header + " |");
    report("|---|" + "---|".repeat(columns.size()) + "---|".repeat(variants.size()));
    for (int e = 0; e < exprs.size(); e++) {
      String text = exprs.get(e);
      var one = seq(List.of(named.get(e)));
      var compiled = VarkaExpressionCompiler.compilePartial(one, seq(childOutput));
      if (compiled.isEmpty()) {
        VarkaDecline decline = VarkaExpressionCompiler.declines(one, seq(childOutput)).get(0);
        report("| `" + text + "` | " + (decline == null ? "declined" : "declined: " + decline)
            + " |");
        continue;
      }
      var fused = compiled.get().fused();
      var counts = new ArrayList<Optional<Integer>>();
      for (var opts : columns) {
        // The literal count is both arrays': a long-lane shape keeps its literals in longArgs,
        // which `literals` does not count.
        try {
          counts.add(Optional.of(laneOps(emit(fused, opts))));
        } catch (VarkaEmitDeclined d) {
          counts.add(Optional.empty());
        }
      }
      var row = new StringBuilder("| `" + text + "` | ");
      row.append(String.join(" | ", counts.stream()
          .map(c -> c.map(String::valueOf).orElse("declined")).toList()));
      for (var c : counts.subList(1, counts.size())) {
        Optional<Integer> defaults = counts.get(0);
        row.append(" | ").append(c.isPresent() && defaults.isPresent()
            ? String.format("%+d", c.get() - defaults.get()) : "-");
      }
      report(row + " |");
    }
  }

  private static byte[] emit(CompiledVarkaProjection fused, VarkaEmitOptions options) {
    return VarkaLoopEmitter.emit(CLASS_NAME, fused.outputs(), fused.inputOrdinals().size(),
        fused.numLiterals(), null, null, options);
  }

  /** An entry's spec as the dump has always printed it: {@code FusedOutput(0)}. */
  private static String render(VarkaOutputSpec spec) {
    return switch (spec) {
      case VarkaOutputSpec.FusedOutput f -> "FusedOutput(" + f.fusedIndex() + ")";
      case VarkaOutputSpec.KernelOutput k ->
          "KernelOutput(" + k.kernel() + ", " + k.fusedIndex() + ")";
      case VarkaOutputSpec.ForwardedOutput w -> "ForwardedOutput(" + w.childOrdinal() + ")";
      case VarkaOutputSpec.ResidualOutput r -> "ResidualOutput";
    };
  }

  private static String join(List<?> values) {
    return String.join(", ", values.stream().map(String::valueOf).toList());
  }

  private static <T> Seq<T> seq(List<T> list) {
    return CollectionConverters.asScala(list).toSeq();
  }

  private static void report(String s) {
    System.out.println(s);
  }

  private static List<Attribute> parseColumns(String spec) {
    var out = new ArrayList<Attribute>();
    for (var c : spec.split(",")) {
      String[] parts = c.trim().split(":");
      if (parts.length != 2) {
        throw new IllegalArgumentException("a column is name:type, got " + c);
      }
      out.add(VarkaSqlResolve.column(parts[0].trim(), columnType(parts[1])));
    }
    return out;
  }

  private static DataType columnType(String spec) {
    String t = spec.trim().toLowerCase(Locale.ROOT);
    return switch (t) {
      case "boolean", "bool" -> DataTypes.BooleanType;
      case "date" -> DataTypes.DateType;
      case "int", "integer" -> DataTypes.IntegerType;
      case "short", "smallint" -> DataTypes.ShortType;
      case "byte", "tinyint" -> DataTypes.ByteType;
      // VARKA-67's third type in the date lane. Spelled by field, because the field decides what
      // the emitter does with it: MONTH and YEAR are the ends a column can hold, and "ym" is the
      // YEAR TO MONTH pair make_ym_interval produces.
      case "ymm", "interval month" -> DataTypes.createYearMonthIntervalType(
          YearMonthIntervalType.MONTH(), YearMonthIntervalType.MONTH());
      case "ymy", "interval year" -> DataTypes.createYearMonthIntervalType(
          YearMonthIntervalType.YEAR(), YearMonthIntervalType.YEAR());
      case "ym", "interval year to month" -> DataTypes.createYearMonthIntervalType();
      // The long lane's three types (VARKA-29): one 64-bit column each.
      case "bigint", "long" -> DataTypes.LongType;
      case "time" -> new TimeType(TimeType.MICROS_PRECISION());
      case "dt", "interval day to second" -> DataTypes.createDayTimeIntervalType();
      default -> {
        if (t.startsWith("time(") && t.endsWith(")")) {
          yield new TimeType(Integer.parseInt(t.substring(5, t.length() - 1).trim()));
        }
        throw new IllegalArgumentException("unsupported column type " + t);
      }
    };
  }

  /** {@code k=v,k=v} onto the options, each {@code k} looked up in {@code
   *  VarkaEmitOption.TABLE}. */
  private static VarkaEmitOptions applyOptions(VarkaEmitOptions base, String spec) {
    if (spec.trim().isEmpty()) {
      return base;
    }
    var opts = base;
    for (var kv : spec.split(",")) {
      String[] parts = kv.trim().split("=", 2);
      if (parts.length != 2) {
        throw new IllegalArgumentException("an option is k=v, got " + kv);
      }
      String k = parts[0];
      String value = parts[1].trim();
      opts = switch (VarkaEmitOption.named(k)) {
        case VarkaEmitOption.Count count -> count.with(opts, Integer.parseInt(value));
        // `Boolean.valueOf` answers false for every string that is not "true", so a typo or a
        // plausible-looking `=on` would silently select the arm you did not ask for - and a
        // benchmark then measure it without saying so. An unknown constant is refused below; an
        // unknown boolean is refused the same way.
        case VarkaEmitOption.Flag flag -> switch (value.toLowerCase(Locale.ROOT)) {
          case "true" -> flag.with(opts, true);
          case "false" -> flag.with(opts, false);
          default -> throw new IllegalArgumentException(
              k + ": expected true or false, got '" + value.toLowerCase(Locale.ROOT) + "'");
        };
        case VarkaEmitOption.Choice<?> choice -> choice.withNamed(opts, value);
      };
    }
    return opts;
  }

  /**
   * Load the class and run it {@code rounds} times over synthetic columns of the kernel's lane, so
   * a {@code -XX:CompileCommand=print} on the loop method has something to print. The output count
   * is the projection's, not inferred from the loop methods: one loop method can hold several
   * outputs (VARKA-32 step B2), and a destination array sized by method count would make the
   * kernel index past it. A long-lane kernel takes 64-bit inputs holding nanoseconds of day, which
   * is inside every {@code TIME} guard and every division bound, and each output buffer has the
   * width of its Spark type - four bytes for a narrowed int, eight otherwise - so the narrowing
   * store of VARKA-102 is driven as the evaluator drives it.
   *
   * <p>{@code nulls} is how many rows of each input are null. Zero - the default - reports a
   * null-free batch, which the emitted {@code run} dispatches to the dense driver, so only the
   * dense methods are ever compiled; any positive count takes the masked path instead. Without it
   * a {@code --rounds} probe cannot see the masked body at all, which is what a {@code
   * -XX:+PrintCompilation} run of the VARKA-70 review needed.
   */
  private static void runHot(byte[] bytes, CompiledVarkaProjection fused, List<Attribute> inputs,
      int rounds, int nulls, int rows, VarkaEmitOptions options) {
    for (var a : inputs) {
      if (a.dataType().equals(DataTypes.ShortType) || a.dataType().equals(DataTypes.ByteType)) {
        report("(--rounds skipped: synthetic data is int32 only, and a short or byte column is "
            + "read)");
        return;
      }
    }
    int numInputs = fused.inputOrdinals().size();
    int outputs = fused.outputs().size();
    int[] literals = fused.literals().stream().mapToInt(Integer::intValue).toArray();
    long[] longArgs = fused.longLiterals().stream().mapToLong(Long::longValue).toArray();
    // The long entry and table at every 64-bit lane, DOUBLE's bits included.
    boolean isLong = fused.lane(options).bits() == Long.SIZE;
    long inputBytes = isLong ? 8L : 4L;
    VarkaFusedKernel kernel;
    try {
      var loader = new VarkaGeneratedClassLoader(VarkaEmitDump.class.getClassLoader());
      loader.defineGeneratedClass(CLASS_NAME, bytes);
      kernel = (VarkaFusedKernel) loader.loadClass(CLASS_NAME).getConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
    try (var arena = Arena.ofConfined()) {
      var src = new long[numInputs];
      for (int s = 0; s < numInputs; s++) {
        MemorySegment segment = arena.allocate(rows * inputBytes, 64);
        for (int r = 0; r < rows; r++) {
          if (isLong) {
            segment.set(ValueLayout.JAVA_LONG, r * 8L, (r * 1000003L * 977L) % 86400000000000L);
          } else {
            segment.set(ValueLayout.JAVA_INT, r * 4L, 18000 + r);
          }
        }
        src[s] = segment.address();
      }
      MemorySegment validity = arena.allocate((rows + 7) / 8L, 64);
      validity.fill((byte) 0xFF);
      // Every `stride`-th row null, spread over the batch so both a full lane group and the
      // epilogue's partial one see a null; the kernel reads the count, so the two must agree.
      if (nulls > 0) {
        int stride = Math.max(1, rows / nulls);
        int cleared = 0;
        for (int r = 0; r < rows && cleared < nulls; r += stride) {
          byte b = validity.get(ValueLayout.JAVA_BYTE, r / 8);
          validity.set(ValueLayout.JAVA_BYTE, r / 8, (byte) (b & ~(1 << (r % 8))));
          cleared++;
        }
      }
      var dst = new long[outputs];
      for (int k = 0; k < outputs; k++) {
        long width = fused.outputTypes().get(k).defaultSize() >= 8 ? 8L : 4L;
        dst[k] = arena.allocate(rows * width, 64).address();
      }
      var dstValidity = new long[outputs];
      for (int k = 0; k < outputs; k++) {
        dstValidity[k] = arena.allocate((rows + 7) / 8L, 64).address();
      }
      var srcValidity = new long[numInputs];
      Arrays.fill(srcValidity, validity.address());
      var nullCounts = new int[numInputs];
      Arrays.fill(nullCounts, nulls);
      long scratch = VarkaEmitterTestSupport.scratch(kernel, rows);
      // The second half of the rounds is timed, so the figure is after tiering has had the first
      // half to settle. It is a probe's reading, not a benchmark's: one run, one fork.
      int status = 0;
      long started = 0L;
      for (int round = 0; round < rounds; round++) {
        if (round == rounds / 2) {
          started = System.nanoTime();
        }
        status |= isLong
            ? kernel.run(src, srcValidity, nullCounts, dst, dstValidity, literals, longArgs, rows,
                scratch)
            : kernel.run(src, srcValidity, nullCounts, dst, dstValidity, literals, rows, scratch);
      }
      int timed = rounds - rounds / 2;
      double perRound = (double) (System.nanoTime() - started) / Math.max(1, timed);
      report("ran " + rounds + " rounds of " + rows + " rows, " + nulls + " null per input; status "
          + status + "; " + String.format("second half %.0f ns a round", perRound));
    }
  }
}
