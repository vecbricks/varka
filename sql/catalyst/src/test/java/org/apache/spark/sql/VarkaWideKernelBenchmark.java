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

package org.apache.spark.sql;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitDeclined;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFusedKernel;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaLoopEmitter;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMethodNames;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddDays;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddMonths;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DayOfMonth;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DayOfWeek;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DayOfYear;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Greatest;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LastDay;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.MakeDate;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Month;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Quarter;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ThursdayOf;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WeekDay;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WeekOfYear;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Year;

/**
 * Kernels wider than one driver method could once hold (VARKA-190 step 2): what a projection of
 * hundreds of outputs costs per row when it runs as one fused kernel, and what each way of getting
 * there past the driver's ceiling costs against the others.
 *
 * <p>The first section prices the table-driven driver ({@code
 * VarkaEmitOptions.driverOutputTable}) against the unrolled one on the widest projection both can
 * emit, a hundred entries of the size ladder's {@code greatest(add_months(d, k), date_add(d, k),
 * last_day(d))}, and then runs the table form alone at four hundred, which the unrolled driver
 * cannot emit. The driver's per-output work runs once per batch, so the batches are the 4096 rows
 * a columnar scan hands a kernel, over a million-row date column, null-free and with every seventh
 * row null - the dense body and the masked body with its bitmap pass. See {@code VARKA-190.md} 9.2
 * and 10.
 *
 * <p>The second section prices the exact grouping ({@code VarkaEmitOptions.exactGrouping})
 * against the greedy walk on the mixed family, where the greedy walk leaves every {@code date_add}
 * in a loop method of its own and the exact partition does not, at a hundred and two hundred
 * entries, with the four-hundred-entry ladder as the control, whose grouping the switch leaves as
 * it is. The third times one emission of the mixed family under each, for the partition's cost at
 * plan time. See {@code VARKA-200.md} 4.
 *
 * <p>A section prices the locals a loop or epilogue method stores and never reads ({@code
 * VarkaEmitOptions.elideUnreadLocals}): the size ladder at a hundred and four hundred entries and
 * the mixed family at a hundred and two hundred, with them built, elided, and built again last as
 * a control for the order of the cases. See {@code VARKA-239.md} 6.
 *
 * <p>Two sections go past the driver from a table's own ceiling, about 180 groups: eight hundred
 * and twelve hundred ladder entries, two and three hundred groups. One kernel whose driver calls
 * its groups through stages ({@code VarkaEmitOptions.splitDriver}, A') is priced against several
 * kernels run in turn over each batch ({@code VarkaEmitOptions.severalKernels}, B), split as the
 * compiler splits them - the largest prefix one kernel serves, then the rest - each reading the
 * input again and recomputing the prefix it shares. First one emission of each, with the bytes of
 * the classes, each form as the plan builds it ({@code VarkaEmitOptions.planSize}, the default)
 * and as the size loop did; then the two forms at run time, on the ladder and on sixty-four dates
 * listed by field, as many fields as the emitter needs to split them, with A' first and again
 * last. See {@code VARKA-190.md} 11 and {@code
 * VARKA-236.md} 6 and 9.3.
 *
 * <p>The last section is item 71's ({@code m10/SCOPE.md}): the planned grouping, the prediction
 * closing groups with the fit's margins, against the weights' and against the prediction without
 * the margins, on the cheap tails, the mixed family and the ladder.
 *
 * <pre>
 *   build/sbt "catalyst/Test/runMain org.apache.spark.sql.VarkaWideKernelBenchmark"
 *   SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt \
 *     "catalyst/Test/runMain org.apache.spark.sql.VarkaWideKernelBenchmark"
 * </pre>
 */
public class VarkaWideKernelBenchmark extends VarkaBenchmarkBase {

  private static final int NUM_ROWS = 1_000_000;
  private static final int CHUNK = 4096;
  private static final int WIDEST = 400;
  private static final List<Integer> PAST = List.of(800, 1200);
  private static final boolean[] NULL_ARMS = {false, true};

  /** A labelled set of emit options: one form a section compares. */
  private record Form(String label, VarkaEmitOptions options) { }

  /** A shape: its label and its roots. */
  private record Shape(String label, List<VarkaVectorIR> roots) { }

  /** A kernel emitted under one form, and the form's label. */
  private record FormKernel(String label, VarkaFusedKernel kernel) { }

  /** A kernel emitted for a shape under one form. */
  private record ShapeKernel(String shape, List<VarkaVectorIR> roots, String label,
      VarkaFusedKernel kernel) { }

  /** A width past the driver's ceiling and how the compiler splits it into kernels. */
  private record Past(int n, List<Integer> sizes) { }

  /** A shape past the ceiling: its outputs, and how a range of them is one kernel's roots. */
  private record PastShape(String label, int n, Slicer slicer) { }

  /**
   * The two forms past the ceiling at one shape: the split driver over all its inputs, and the
   * several kernels, each with its outputs and the inputs it reads.
   */
  private record PastForms(String label, int n, int inputs, VarkaFusedKernel staged,
      List<Integer> sizes, List<Integer> kernelInputs, List<VarkaFusedKernel> kernels) { }

  private static VarkaVectorIR entry(int k) {
    var col = new ColumnRef(0);
    return new Greatest(new Greatest(new AddMonths(col, new LiteralSlot(k)),
        new AddDays(col, new LiteralSlot(k))), new LastDay(col));
  }

  /** The two groupings the exact grouping's sections compare, each named for its form. */
  private static final List<Form> GROUPINGS = List.of(
      new Form("greedy grouping", VarkaEmitOptions.DEFAULTS.withExactGrouping(false)),
      new Form("exact grouping", VarkaEmitOptions.DEFAULTS.withExactGrouping(true)));

  /**
   * The mixed family's entry {@code k}: the size ladder's entry, {@code make_date(year(d),
   * month(d), k)}, the cheap tail {@code year(d) + k} and {@code date_add(d, k)}, in rotation over
   * one date. The same shapes as the catalyst suites' {@code VarkaGroupingBound.mixed}.
   */
  private static VarkaVectorIR mixedEntry(int k) {
    var col = new ColumnRef(0);
    return switch (k % 4) {
      case 0 -> entry(k);
      case 1 -> new MakeDate(new Year(col), new Month(col), new LiteralSlot(k), true);
      case 2 -> new IntArith(IntOp.ADD, Overflow.WRAP, new Year(col), new LiteralSlot(k));
      default -> new AddDays(col, new LiteralSlot(k));
    };
  }

  /** {@code year(d) + k}, the cheap tail the call-site budget was read on. */
  private static VarkaVectorIR tailEntry(int k) {
    return new IntArith(IntOp.ADD, Overflow.WRAP, new Year(new ColumnRef(0)), new LiteralSlot(k));
  }

  private int nextId = 0;

  /** Keeps the emitted bytes live. */
  private int sink = 0;

  private VarkaFusedKernel emit(List<VarkaVectorIR> roots, VarkaEmitOptions options,
      VarkaGeneratedClassLoader loader) {
    return emit(roots, options, loader, 1);
  }

  private VarkaFusedKernel emit(List<VarkaVectorIR> roots, VarkaEmitOptions options,
      VarkaGeneratedClassLoader loader, int numInputs) {
    nextId += 1;
    String name = "org.apache.spark.sql.varka.execution.VarkaWideBench" + nextId;
    return defineKernel(loader, name, VarkaLoopEmitter.emit(name, List.copyOf(roots), numInputs,
        roots.size(), null, null, options));
  }

  /**
   * The calendar fields a date is listed by, in order: each decomposes the date through the same
   * civil-from-days prefix, which one class computes once and several kernels compute again.
   */
  private static final List<Function<VarkaVectorIR, VarkaVectorIR>> FIELDS = List.of(
      Year::new, Month::new, Quarter::new, DayOfMonth::new, DayOfYear::new, LastDay::new,
      d -> new WeekOfYear(new ThursdayOf(d)), DayOfWeek::new, WeekDay::new);

  /** The emitter's limit on a kernel's inputs, and so the dates a shape can read. */
  private static final int DATES = 64;

  /**
   * The widest kernel any section scans, which sizes the scans' destinations: the widest ladder
   * past the ceiling, or the dates by every field, whichever is wider.
   */
  private static final int MAX_OUTPUTS = Math.max(
      PAST.stream().mapToInt(Integer::intValue).max().orElseThrow(), DATES * FIELDS.size());

  /**
   * Sixty-four dates, the first {@code fields} of {@link #FIELDS} of each, listed by field: every
   * {@code year}, then every {@code month}, and so on. The shape of {@code VARKA-236.md} 2.4,
   * where several kernels' cut in projection order parts the fields of a date, so the second
   * kernel decomposes every date again where the one class computes each prefix once and loads
   * it (VARKA-198).
   */
  private static List<VarkaVectorIR> datesByField(int fields) {
    return datesSlice(fields, 0, fields * DATES).roots();
  }

  /** {@code n} ladder entries numbered from literal slot 0: one kernel's roots. */
  private static List<VarkaVectorIR> ladder(int n) {
    return roots(n, VarkaWideKernelBenchmark::entry);
  }

  // ---------------------------------------------------------------------------------------
  // How the compiler splits a shape into kernels
  // ---------------------------------------------------------------------------------------

  /** One kernel's roots and the inputs it reads, numbered as the compiler numbers them. */
  private record Slice(List<VarkaVectorIR> roots, int inputs) { }

  /**
   * A shape's outputs {@code [from, to)} as one kernel's roots. The compiler numbers a kernel's
   * inputs and literals by their first use in that kernel ({@code VarkaSlotTable}), so a slice
   * is renumbered from 0 in both, and a kernel that reads only some inputs declares only those.
   */
  @FunctionalInterface
  private interface Slicer {
    Slice slice(int from, int to);
  }

  /** The ladder's entries {@code [from, to)}: one input, literal slots from 0 again. */
  private static Slice ladderSlice(int from, int to) {
    return new Slice(ladder(to - from), 1);
  }

  /**
   * Outputs {@code [from, to)} of sixty-four dates by {@code fields} fields, each date an input
   * numbered by its first use in the slice: the rest after a cut inside a field reads only the
   * dates that field has left.
   */
  private static Slice datesSlice(int fields, int from, int to) {
    require(to <= fields * DATES, "outputs past the shape");
    var slotOf = new LinkedHashMap<Integer, Integer>();
    var roots = new ArrayList<VarkaVectorIR>();
    for (int j = from; j < to; j++) {
      int date = j % DATES;
      Integer slot = slotOf.get(date);
      if (slot == null) {
        slot = slotOf.size();
        slotOf.put(date, slot);
      }
      roots.add(FIELDS.get(j / DATES).apply(new ColumnRef(slot)));
    }
    return new Slice(roots, slotOf.size());
  }

  /** What the emitter answered: a class's bytes, or a decline with its planned cut and outputs. */
  private record Asked(byte[] bytes, int plannedCut, List<Integer> outputs) {
    boolean built() {
      return bytes != null;
    }
  }

  /** One emission of {@code slice}: the bytes it builds, or how it declined. */
  private static Asked ask(Slice slice, VarkaEmitOptions options) {
    try {
      return new Asked(VarkaLoopEmitter.emit("VarkaWideBenchPlan", List.copyOf(slice.roots()),
          slice.inputs(), slice.roots().size(), null, null, options), 0, List.of());
    } catch (VarkaEmitDeclined d) {
      return new Asked(null, d.plannedCut(), d.outputs());
    }
  }

  /**
   * A decline that names outputs is one the compiler answers by demoting those outputs to the
   * row path; the shapes here decline class-wide or not at all, and a shape that does otherwise
   * would no longer be the split the section compares, so it stops the run.
   */
  private static void requireClassWide(Asked asked, String what) {
    require(asked.outputs().isEmpty(), what + " declines naming outputs " + asked.outputs()
        + ": the compiler would demote them, a split this benchmark does not model");
  }

  /** One of the compiler's kernels: its slice, and the bytes of the emission that built it. */
  private record Planned(Slice slice, byte[] bytes) { }

  /**
   * How the compiler splits a shape of {@code n} outputs under {@code severalKernels} with
   * {@code planSize} on, as {@code VarkaExpressionCompiler.classify} does it: the emitter declines
   * a shape whose driver is over the budget before building it, naming the largest prefix one
   * class serves by the plan ({@code VarkaEmitDeclined.plannedCut()}); that prefix is asked again,
   * since its own grouping can still be over, and cut at most twice in all, after which the
   * largest prefix that builds is found by halving and asked once more. Then the same for the
   * rest. Each kernel keeps the bytes of the emission that built it, as the shape cache does, so
   * a kernel is built once.
   */
  private static List<Planned> plannedKernels(int n, Slicer slicer, VarkaEmitOptions options) {
    VarkaEmitOptions planned = options.withPlanSize(true);
    var kernels = new ArrayList<Planned>();
    int at = 0;
    while (at < n) {
      int size = n - at;
      int cuts = 0;
      Asked asked = ask(slicer.slice(at, at + size), planned);
      while (!asked.built()) {
        requireClassWide(asked, "outputs " + at + " to " + (at + size));
        if (asked.plannedCut() > 0 && asked.plannedCut() < size && cuts < 2) {
          size = asked.plannedCut();
          cuts++;
        } else {
          size = largestThatBuilds(slicer, at, size, planned);
        }
        asked = ask(slicer.slice(at, at + size), planned);
      }
      kernels.add(new Planned(slicer.slice(at, at + size), asked.bytes()));
      at += size;
    }
    return kernels;
  }

  /**
   * The compiler's halving over the outputs from {@code at}, below {@code over} of them: a
   * prefix declining class-wide is known not to fit, and one that builds, or declines naming
   * outputs, is the side that does, as in {@code VarkaExpressionCompiler.classify}.
   */
  private static int largestThatBuilds(Slicer slicer, int at, int over,
      VarkaEmitOptions options) {
    int lo = 0;
    int hi = over;
    while (hi - lo > 1) {
      int mid = (lo + hi) / 2;
      Asked asked = ask(slicer.slice(at, at + mid), options);
      if (!asked.built() && asked.outputs().isEmpty()) {
        hi = mid;
      } else {
        lo = mid;
      }
    }
    require(lo > 0, "not even one output builds as a class");
    return lo;
  }

  /**
   * How the compiler splits {@code n} ladder entries under {@code severalKernels} without the
   * plan, the reference arm: the largest prefix one kernel serves, found by halving, then the
   * same for the rest.
   */
  private static List<Integer> kernelSizes(int n, VarkaEmitOptions options) {
    var sizes = new ArrayList<Integer>();
    int at = 0;
    while (at < n) {
      int size = n - at;
      Asked asked = ask(ladderSlice(at, n), options);
      if (!asked.built()) {
        requireClassWide(asked, "ladder entries " + at + " to " + n);
        size = largestThatBuilds(VarkaWideKernelBenchmark::ladderSlice, at, size, options);
      }
      sizes.add(size);
      at += size;
    }
    return sizes;
  }

  /**
   * The fewest fields, from four up, for which one kernel cannot serve sixty-four dates by field
   * under {@code options}, so the compiler splits them. Asked of the emitter rather than fixed:
   * four fields, 256 outputs, split 181 + 75 when the section was written and fit one kernel once
   * the grouping began to close groups on predicted bytes (VARKA-254), which left the section
   * nothing to compare (VARKA-325). The shape grows by fields, not dates, since a kernel reads at
   * most sixty-four inputs.
   */
  private static int splittingFields(VarkaEmitOptions options) {
    for (int fields = 4; fields <= FIELDS.size(); fields++) {
      if (!ask(datesSlice(fields, 0, fields * DATES), options.withPlanSize(true)).built()) {
        return fields;
      }
    }
    throw new IllegalStateException("sixty-four dates by all " + FIELDS.size()
        + " fields fit one kernel: nothing to compare");
  }

  /** The loop methods of an emitted kernel: its groups. */
  private static int loops(VarkaFusedKernel kernel) {
    int count = 0;
    for (Method m : kernel.getClass().getDeclaredMethods()) {
      if (VarkaMethodNames.isLoop(m.getName(), true)) {
        count++;
      }
    }
    return count;
  }

  private static String nullsLabel(boolean withNulls) {
    return withNulls ? "every seventh row null" : "null-free";
  }

  /** The date column, its bitmap, and the destinations every scan writes. */
  private record Column(MemorySegment data, MemorySegment validity, int[] batchNulls,
      MemorySegment[] dsts, MemorySegment[] dstValidities) {

    /**
     * Every 4096-row batch of the column through {@code kernel}, null-free or with nulls; a
     * kernel over several inputs reads the one column through each of them.
     */
    void scan(VarkaFusedKernel kernel, int outputs, boolean withNulls, int inputs) {
      int[] lits = new int[outputs];
      long[] dstData = new long[outputs];
      long[] dstValidity = new long[outputs];
      for (int k = 0; k < outputs; k++) {
        lits[k] = k % 12 + 1;
        dstData[k] = dsts[k].address();
        dstValidity[k] = dstValidities[k].address();
      }
      int done = 0;
      int batch = 0;
      while (done < NUM_ROWS) {
        int n = Math.min(CHUNK, NUM_ROWS - done);
        long[] src = new long[inputs];
        Arrays.fill(src, data.address() + done * 4L);
        long[] srcValidity = new long[inputs];
        Arrays.fill(srcValidity, withNulls ? validity.address() + done / 8L : 0L);
        int[] srcNulls = new int[inputs];
        Arrays.fill(srcNulls, withNulls ? batchNulls[batch] : 0);
        int status = kernel.run(src, srcValidity, srcNulls, dstData, dstValidity, lits, n);
        if (status != 0) {
          throw requirementFailed("the kernel declined a batch: status " + status);
        }
        done += n;
        batch += 1;
      }
    }

    void scan(VarkaFusedKernel kernel, int outputs, boolean withNulls) {
      scan(kernel, outputs, withNulls, 1);
    }
  }

  private static Column column(Arena arena) {
    // Days from 1970 to about 2030, and a bitmap with every seventh row null.
    MemorySegment data = arena.allocate(NUM_ROWS * 4L, 8);
    MemorySegment validity = arena.allocate((NUM_ROWS + 7) / 8L, 8);
    for (int i = 0; i < NUM_ROWS; i++) {
      data.setAtIndex(ValueLayout.JAVA_INT, i, (i * 37) % 22000);
      if (i % 7 != 0) {
        long off = i / 8L;
        validity.set(ValueLayout.JAVA_BYTE, off,
            (byte) (validity.get(ValueLayout.JAVA_BYTE, off) | (1 << (i % 8))));
      }
    }
    var batchNulls = new ArrayList<Integer>();
    for (int start = 0; start < NUM_ROWS; start += CHUNK) {
      int count = 0;
      for (int r = start; r < Math.min(start + CHUNK, NUM_ROWS); r++) {
        if (r % 7 == 0) {
          count++;
        }
      }
      batchNulls.add(count);
    }
    // Destinations for the widest kernel any section scans, the 1200 entries past the driver's
    // ceiling, which is more than WIDEST, the widest one driver from a table serves.
    MemorySegment[] dsts = new MemorySegment[MAX_OUTPUTS];
    for (int k = 0; k < MAX_OUTPUTS; k++) {
      dsts[k] = arena.allocate(CHUNK * 4L, 8);
    }
    MemorySegment[] dstValidities = new MemorySegment[MAX_OUTPUTS];
    for (int k = 0; k < MAX_OUTPUTS; k++) {
      dstValidities[k] = arena.allocate(CHUNK / 8L, 8);
    }
    return new Column(data, validity, batchNulls.stream().mapToInt(Integer::intValue).toArray(),
        dsts, dstValidities);
  }

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    var loader = new VarkaGeneratedClassLoader(getClass().getClassLoader());
    try (Arena arena = Arena.ofConfined()) {
      Column column = column(arena);

      section("the driver from a table against the driver unrolled", () -> {
        var benchmark = benchmark(NUM_ROWS + " rows in " + CHUNK + "-row batches",
            NUM_ROWS).timed().build();
        // Both forms named: the table is the default since this section's measurement.
        var unrolled = VarkaEmitOptions.DEFAULTS.withDriverOutputTable(false);
        var table = VarkaEmitOptions.DEFAULTS.withDriverOutputTable(true);
        var hundred = ladder(100);
        var forms = List.of(
            new FormKernel("unrolled driver", emit(hundred, unrolled, loader)),
            new FormKernel("driver from a table", emit(hundred, table, loader)));
        for (boolean withNulls : NULL_ARMS) {
          for (FormKernel form : forms) {
            benchmark.addCase("100 entries, " + form.label() + ", " + nullsLabel(withNulls),
                iteration -> column.scan(form.kernel(), 100, withNulls));
          }
        }
        VarkaFusedKernel wide = emit(ladder(WIDEST), table, loader);
        for (boolean withNulls : NULL_ARMS) {
          benchmark.addCase(WIDEST + " entries, driver from a table, " + nullsLabel(withNulls),
              iteration -> column.scan(wide, WIDEST, withNulls));
        }
        runCases(benchmark);
      });

      section("the exact grouping against the greedy walk", () -> {
        var benchmark = benchmark(NUM_ROWS + " rows in " + CHUNK + "-row batches",
            NUM_ROWS).timed().build();
        var shapes = List.of(
            new Shape("100 mixed entries", roots(100, VarkaWideKernelBenchmark::mixedEntry)),
            new Shape("200 mixed entries", roots(200, VarkaWideKernelBenchmark::mixedEntry)),
            new Shape(WIDEST + " ladder entries", ladder(WIDEST)));
        // Each kernel is emitted once and scanned with nulls and without.
        var kernels = new ArrayList<ShapeKernel>();
        for (Shape shape : shapes) {
          for (Form form : GROUPINGS) {
            kernels.add(new ShapeKernel(shape.label(), shape.roots(), form.label(),
                emit(shape.roots(), form.options(), loader)));
          }
        }
        for (boolean withNulls : NULL_ARMS) {
          for (ShapeKernel k : kernels) {
            benchmark.addCase(k.shape() + ", " + k.label() + " (" + loops(k.kernel())
                    + " loop methods), " + nullsLabel(withNulls),
                iteration -> column.scan(k.kernel(), k.roots().size(), withNulls));
          }
        }
        runCases(benchmark);
      });

      section("the locals nothing reads, built and elided", () -> {
        var benchmark = benchmark(NUM_ROWS + " rows in " + CHUNK + "-row batches",
            NUM_ROWS).timed().build();
        // Both forms named: the elision is the default since this section's measurement.
        var built = VarkaEmitOptions.DEFAULTS.withElideUnreadLocals(false);
        var elided = VarkaEmitOptions.DEFAULTS.withElideUnreadLocals(true);
        var shapes = List.of(
            new Shape("100 ladder entries", ladder(100)),
            new Shape(WIDEST + " ladder entries", ladder(WIDEST)),
            new Shape("100 mixed entries", roots(100, VarkaWideKernelBenchmark::mixedEntry)),
            new Shape("200 mixed entries", roots(200, VarkaWideKernelBenchmark::mixedEntry)));
        // The built form runs again last, a control for the order of the cases.
        var forms = List.of(new Form("locals built", built), new Form("locals elided", elided),
            new Form("locals built, again", built));
        for (boolean withNulls : NULL_ARMS) {
          for (Shape shape : shapes) {
            for (Form form : forms) {
              VarkaFusedKernel kernel = emit(shape.roots(), form.options(), loader);
              benchmark.addCase(shape.label() + ", " + form.label() + ", "
                      + nullsLabel(withNulls),
                  iteration -> column.scan(kernel, shape.roots().size(), withNulls));
            }
          }
        }
        runCases(benchmark);
      });

      section("emitting the mixed family: the exact grouping's cost at plan time", () -> {
        // One emission per iteration, the class built and measured but not defined.
        var benchmark = benchmark("one emission", 1).timed().build();
        var roots = List.copyOf(roots(200, VarkaWideKernelBenchmark::mixedEntry));
        for (Form form : GROUPINGS) {
          benchmark.addCase("200 mixed entries, " + form.label(), iteration -> {
            sink += VarkaLoopEmitter.emit("VarkaWideBenchEmission", roots, 1, 200, null, null,
                form.options()).length;
          });
        }
        runCases(benchmark);
        require(sink != Integer.MIN_VALUE, "");
      });

      // The two forms past the driver from a table's ceiling. Stages only where the driver is
      // over the budget, and several kernels only where one kernel cannot serve them all.
      // Both named: the split driver is the default (`VARKA-190.md` 11.5), and each of B's
      // kernels is a kernel whose driver fits, which is what the compiler's split makes. Both
      // plan their size, the plan being the default (VARKA-236); the loop that reacts to each
      // measurement in turn is the reference arm here, named `loop`, and its classes
      // are the planned ones byte for byte (`VARKA-236.md` 9.2), so only the plan-time
      // section still runs it.
      var splitDriver = VarkaEmitOptions.DEFAULTS.withSplitDriver(true);
      var oneKernel = VarkaEmitOptions.DEFAULTS.withSplitDriver(false);
      var loopSplit = splitDriver.withPlanSize(false);
      var loopOne = oneKernel.withPlanSize(false);
      var pastForms = new ArrayList<Past>();
      for (int n : PAST) {
        List<Integer> sizes = kernelSizes(n, loopOne);
        require(sizes.size() > 1, n + " ladder entries fit one kernel: nothing to compare");
        pastForms.add(new Past(n, sizes));
      }

      section("past the driver's ceiling: each form's cost at plan time", () -> {
        // One emission per iteration of every class the form needs, built and measured but not
        // defined; the label carries the classes' bytes. The planned forms are the default: the
        // split driver sizes its stages off a driver built alone, one build; several kernels'
        // cut is read off the same: an emission that builds nothing, each planned prefix asked
        // once more, and the emission that builds a kernel kept as its class, as the shape cache
        // keeps it. The loop's arms are the reference: the split driver's two builds, several
        // kernels' classes alone with their split given, and the same with the compiler's
        // search for it, which asks the emitter for halving prefixes the shape cache then holds.
        var benchmark = benchmark("one emission", 1).timed().build();
        for (Past past : pastForms) {
          int n = past.n();
          List<Integer> sizes = past.sizes();
          int stagedBytes = build(ladder(n), loopSplit).length;
          int severalBytes = 0;
          for (int k : sizes) {
            severalBytes += build(ladder(k), loopOne).length;
          }
          benchmark.addCase(n + " entries, split driver", iteration -> {
            sink += build(ladder(n), splitDriver).length;
          });
          benchmark.addCase(n + " entries, several kernels", iteration -> {
            for (Planned k : plannedKernels(n, VarkaWideKernelBenchmark::ladderSlice,
                oneKernel)) {
              sink += k.bytes().length;
            }
          });
          benchmark.addCase(n + " entries, split driver, the loop's two builds ("
              + stagedBytes + " bytes)", iteration -> {
                sink += build(ladder(n), loopSplit).length;
              });
          benchmark.addCase(n + " entries, several kernels, the loop's classes alone ("
              + severalBytes + " bytes in " + sizes.size() + " classes)", iteration -> {
                for (int k : sizes) {
                  sink += build(ladder(k), loopOne).length;
                }
              });
          benchmark.addCase(n + " entries, several kernels with the loop's compiler search",
              iteration -> {
                sink += kernelSizes(n, loopOne).size();
                for (int k : sizes) {
                  sink += build(ladder(k), loopOne).length;
                }
              });
        }
        runCases(benchmark);
        require(sink != Integer.MIN_VALUE, "");
      });

      // VARKA-190's question (VARKA-236.md 3.6): the split driver (A') against several
      // kernels (B) on the ladder, B's best case, and on sixty-four dates by field, its worst,
      // where its cut parts the fields of a date, with the fewest fields the emitter splits.
      // A' runs first and again last, a control for the case order 11.6 did not control. Both
      // forms plan their size, as they do by default.
      section("past the driver's ceiling: a split driver against several kernels, "
          + "the split driver first and again last", () -> {
            var benchmark = benchmark(NUM_ROWS + " rows in " + CHUNK + "-row batches",
                NUM_ROWS).timed().build();
            var shapes = new ArrayList<PastShape>();
            for (int n : PAST) {
              shapes.add(new PastShape(n + " ladder entries", n,
                  VarkaWideKernelBenchmark::ladderSlice));
            }
            int fields = splittingFields(oneKernel);
            shapes.add(new PastShape("sixty-four dates by " + fields + " fields, "
                + DATES * fields + " outputs", DATES * fields,
                (from, to) -> datesSlice(fields, from, to)));
            var forms = new ArrayList<PastForms>();
            for (PastShape shape : shapes) {
              List<Planned> planned = plannedKernels(shape.n(), shape.slicer(), oneKernel);
              require(planned.size() > 1, shape.label() + " fits one kernel: nothing to compare");
              Slice whole = shape.slicer().slice(0, shape.n());
              VarkaFusedKernel staged = emit(whole.roots(), splitDriver, loader, whole.inputs());
              var sizes = new ArrayList<Integer>();
              var kernelInputs = new ArrayList<Integer>();
              var kernels = new ArrayList<VarkaFusedKernel>();
              for (Planned k : planned) {
                sizes.add(k.slice().roots().size());
                kernelInputs.add(k.slice().inputs());
                kernels.add(emit(k.slice().roots(), oneKernel, loader, k.slice().inputs()));
              }
              forms.add(new PastForms(shape.label(), shape.n(), whole.inputs(), staged, sizes,
                  kernelInputs, kernels));
            }
            for (boolean withNulls : NULL_ARMS) {
              for (PastForms f : forms) {
                String nulls = nullsLabel(withNulls);
                benchmark.addCase(f.label() + ", split driver, " + nulls,
                    iteration -> column.scan(f.staged(), f.n(), withNulls, f.inputs()));
                String split = f.sizes().stream().map(String::valueOf)
                    .collect(Collectors.joining(" + "));
                benchmark.addCase(f.label() + ", several kernels (" + split + "), " + nulls,
                    iteration -> {
                      for (int k = 0; k < f.kernels().size(); k++) {
                        column.scan(f.kernels().get(k), f.sizes().get(k), withNulls,
                            f.kernelInputs().get(k));
                      }
                    });
                benchmark.addCase(f.label() + ", split driver, again, " + nulls,
                    iteration -> column.scan(f.staged(), f.n(), withNulls, f.inputs()));
              }
            }
            runCases(benchmark);
          });

      // Item 71 of m10/SCOPE.md, brought into VARKA-236 (VARKA-236.md 3.5): what the
      // predicted grouping costs at run time against the weights', and the planned grouping
      // with the fit's margins, on the families the prediction closes groups on. The weights
      // run first and again last as the control.
      section("the predicted grouping against the weights, and planned with the margins", () -> {
        var benchmark = benchmark(NUM_ROWS + " rows in " + CHUNK + "-row batches",
            NUM_ROWS).timed().build();
        // Planned is the default (VARKA-236); the weights alone and the prediction without
        // the plan's margins are its reference arms.
        var planned = VarkaEmitOptions.DEFAULTS;
        var predicted = planned.withPlanSize(false);
        var weights = predicted.withPredictGrouping(false);
        var shapes = List.of(
            new Shape("22 cheap tails", roots(22, VarkaWideKernelBenchmark::tailEntry)),
            new Shape("64 cheap tails", roots(64, VarkaWideKernelBenchmark::tailEntry)),
            new Shape("200 mixed entries", roots(200, VarkaWideKernelBenchmark::mixedEntry)),
            new Shape(WIDEST + " ladder entries", ladder(WIDEST)));
        var forms = List.of(new Form("weights", weights), new Form("predicted", predicted),
            new Form("planned", planned), new Form("weights, again", weights));
        for (boolean withNulls : NULL_ARMS) {
          for (Shape shape : shapes) {
            for (Form form : forms) {
              VarkaFusedKernel kernel = emit(shape.roots(), form.options(), loader);
              benchmark.addCase(shape.label() + ", " + form.label() + " (" + loops(kernel)
                      + " loop methods), " + nullsLabel(withNulls),
                  iteration -> column.scan(kernel, shape.roots().size(), withNulls));
            }
          }
        }
        runCases(benchmark);
      });
    }
  }

  private static byte[] build(List<VarkaVectorIR> roots, VarkaEmitOptions options) {
    return VarkaLoopEmitter.emit("VarkaWideBenchEmission", List.copyOf(roots), 1, roots.size(),
        null, null, options);
  }
}
