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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import jdk.incubator.vector.IntVector;
import scala.Enumeration;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.EvalMode;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.LessThan$;
import org.apache.spark.sql.catalyst.expressions.Multiply$;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaPredicate;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterTestSupport;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFusedKernel;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelPlan;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaLoopEmitter;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaReference;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSqlResolve;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntNeg;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenLane;
import org.apache.spark.sql.types.ByteType;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.ShortType;

/**
 * What row 329's narrow lane costs through the real emitter, under the two arms of the emit
 * option {@code VarkaEmitOptions.narrowLanes} (not a SQL conf: no session setting reaches it, so a
 * production kernel is the widened arm): the measurement the hand-loop spike
 * ({@link VarkaNarrowLaneBenchmark}, {@code results/VARKA-329-spike.txt}) could not make.
 *
 * <p>The IR is built by hand and passed through {@link VarkaLoopEmitter#emit}, so what is timed
 * is the code the evaluator would run: the widening loads or the narrow species of the arm, the
 * validity words, the selection bitmap, the loop bounds and the epilogue. The shapes are row
 * 329's four and two unary ones, all over {@code TINYINT} columns, and the checked multiply:
 *
 * <ul>
 *   <li>(a) {@code y1 < y2} as a selection: the compare of two {@code TINYINT} columns, both
 *       operands narrow, written as a packed selection bitmap. WIDENED reads each column through
 *       the byte species of a fraction of the int species' width and compares ints; NATIVE reads
 *       and compares in the preferred byte species.</li>
 *   <li>(b) {@code (y1 + y2) < y1}: the same compare with a narrow add under it (row 330's
 *       arithmetic, measured here in its LEGACY wrapping mode on data where no byte addition
 *       overflows, the only data on which the two arms and the reference can agree before 330's
 *       deferred wrap lands - see {@link #overflowProbe}).</li>
 *   <li>(c) {@code b < i}, int-driven: a byte column widened to the int lane against an int
 *       column. The tree holds an int value, so both arms emit the int lane and the byte column
 *       is a widening load; the run pins that the two arms' class bytes are identical.</li>
 *   <li>(d) the packed boolean output of a byte compare: the same kernel as (a). A boolean
 *       output in the IR is a condition root, whose only write is the selection bitmap - one
 *       {@code long} word per 64 rows under NATIVE, several combined masks under WIDENED - so
 *       the spike's fourth shape and its first are one emitted kernel (VARKA-329.md 9.2).</li>
 *   <li>(e) {@code CASE WHEN y1 < y2 THEN y1 ELSE y2 END}: the store shape the spike lacked, a
 *       narrow value written one byte a row, under a narrow blend (NATIVE) or an int choice
 *       narrowed once at its store (WIDENED).</li>
 *   <li>(f) {@code -y1} and (g) {@code abs(y1)}, LEGACY, stored narrow: the unary arithmetic,
 *       one lanewise {@code NEG} or {@code ABS} in either arm since the review fixes of #987 and
 *       #989 (a multiply by -1, and a compare, a negation and a blend, before them).</li>
 *   <li>(h) to (k) {@code y1 * y2} and {@code s1 * s2} stored narrow, each in ANSI and in TRY
 *       ({@code try_multiply}), and (l) {@code y1 * y2 < y3} in ANSI as a selection: the checked
 *       narrow multiply, a {@code TINYINT} or {@code SMALLINT} product checked against its type's
 *       range. Its data holds no product that overflows, so every arm answers every batch.</li>
 * </ul>
 *
 * <p>Shapes (a) to (g) are IR built by hand. The checked multiply's shapes are compiled from their
 * Catalyst expressions by {@link VarkaExpressionCompiler} under each arm's options, so that they
 * time the tree the compiler builds for them, whichever that is: {@code VARKA-481.md} 6 times
 * them before and after a change to that tree.
 *
 * <p>Every arm's answer is compared with {@link VarkaReference} before it is timed, over a batch
 * laid out as the evaluator lays it out: a byte column one byte a row, an int column four, the
 * output's validity a whole word. Rows are multiples of 64, so no loop has a tail and the rungs
 * price the loop rather than the epilogue.
 *
 * <p>Harness (row 407): each arm is driven at its own turn, just before it is timed, for at least
 * eight drives and then until two consecutive drives are within 30 percent, with C2 idle after
 * each drive and every dense loop method of the kernel at level 4 in {@code Compiler.codelist}.
 * An arm that does not settle in 30 seconds says so, with the levels it had, and is timed as it
 * is.
 *
 * <p>Run it with {@code -XX:MaxVectorSize=64}, {@code 32} and {@code 16} for the 512-, 256- and
 * 128-bit widths. {@code -Dvarka.narrow.emit.arms=widened} or {@code =native} keeps one arm's
 * species out of the other's JVM (the two-species hazard of {@code VARKA-376.md}); the default
 * runs both in one JVM, which is that hazard's arrangement. At 128 bits a byte column is read at
 * four int lanes through the part-form load ({@code results/VARKA-329-partload.txt}), so both
 * arms answer every shape there too. A shape an arm declines - the kernel plan's refusal, or the
 * compiler's demotion of a checked multiply for the same reason - is printed by name, with the
 * reason, rather than timed. A {@code -XX:CompileCommand=dontinline,<generated class>::*} keeps
 * a loop method out of its driver, so that the code list has a level of its own to report.
 *
 * <pre>
 *   build/sbt "catalyst/Test/runMain org.apache.spark.sql.VarkaNarrowEmitBenchmark"
 * </pre>
 */
public class VarkaNarrowEmitBenchmark extends VarkaBenchmarkBase {

  /**
   * A rung: a row count, what its working set is for, and which opt-in property admits it. The
   * first three are in cache on the machine row 329's record was taken on; the last two are the
   * past-L3 reading for bytes, which the spike left to {@code PLAN.md} row 415.
   */
  private record Rung(int numRows, String level, int optIn) { }

  private static final int ALWAYS = 0;
  private static final int BIG_RUNGS = 1;
  private static final int PAST_L3 = 2;

  private static final List<Rung> LADDER = List.of(
      new Rung(16384, "byte pair of 32 KB", ALWAYS),
      new Rung(262144, "byte pair of 512 KB", ALWAYS),
      new Rung(1000000, "byte pair of 2 MB", ALWAYS),
      new Rung(8388608, "byte pair of 16 MB", BIG_RUNGS),
      new Rung(33554432, "byte pair of 64 MB", PAST_L3));

  private static final String BIG_RUNGS_PROPERTY = "varka.narrow.emit.bigRungs";
  private static final String PAST_L3_PROPERTY = "varka.narrow.emit.pastL3";

  /** The rungs this run times, by the opt-in properties it sets. */
  private static List<Rung> ladder() {
    int admit = Boolean.getBoolean(PAST_L3_PROPERTY) ? PAST_L3
        : Boolean.getBoolean(BIG_RUNGS_PROPERTY) ? BIG_RUNGS : ALWAYS;
    return LADDER.stream().filter(r -> r.optIn() <= admit).toList();
  }

  /** Rows a timed iteration covers, so that one is long enough for a millisecond clock. */
  private static final long ROWS_PER_ITERATION = 1L << 27;

  private static final int MIN_WARM_DRIVES = 8;
  private static final double STEADY_FRACTION = 0.3;
  private static final long WARM_WAIT_MILLIS = 30_000;
  private static final long IDLE_WAIT_MILLIS = 10_000;

  private static final String ARMS_PROPERTY = "varka.narrow.emit.arms";

  /** The columns a rung holds, by the index a shape names them with. */
  private static final int B1 = 0;
  private static final int B2 = 1;
  private static final int LOW1 = 2;
  private static final int LOW2 = 3;
  private static final int INTS = 4;
  private static final int SMALL1 = 5;
  private static final int SMALL2 = 6;
  private static final int S1 = 7;
  private static final int S2 = 8;

  private static final int[] NO_LITS = new int[0];

  // -------------------------------------------------------------------------------------------
  // The arms
  // -------------------------------------------------------------------------------------------

  /**
   * The two arms of {@code narrowLanes}: WIDENED (false) computes a narrow value in the int
   * species and narrows once at its store; NATIVE (true, the default since row 478) computes in
   * the narrow species where every value in the tree is narrow.
   */
  private enum Arm {
    WIDENED(false),
    NATIVE(true);

    private final boolean narrowLanes;

    Arm(boolean narrowLanes) {
      this.narrowLanes = narrowLanes;
    }

    String token() {
      return name().toLowerCase(Locale.ROOT);
    }

    VarkaEmitOptions options() {
      return VarkaEmitOptions.DEFAULTS.toBuilder().narrowLanes(narrowLanes).build();
    }
  }

  /** The arms {@code -Dvarka.narrow.emit.arms} names, comma separated; an unknown name refused. */
  private static Set<Arm> selectedArms() {
    String value = System.getProperty(ARMS_PROPERTY, "widened,native");
    var arms = EnumSet.noneOf(Arm.class);
    for (String token : value.split(",", -1)) {
      String name = token.strip();
      Arm arm = Arrays.stream(Arm.values()).filter(a -> a.token().equals(name)).findFirst()
          .orElseThrow(() -> new IllegalArgumentException("-D" + ARMS_PROPERTY + " names \""
              + name + "\", which is none of "
              + Arrays.stream(Arm.values()).map(Arm::token).toList()));
      if (!arms.add(arm)) {
        throw new IllegalArgumentException("-D" + ARMS_PROPERTY + " names " + name + " twice");
      }
    }
    return arms;
  }

  // -------------------------------------------------------------------------------------------
  // The shapes
  // -------------------------------------------------------------------------------------------

  /** The roots a kernel is emitted from and the literal table they read. */
  private record Tree(List<VarkaVectorIR> roots, int[] literals) {
    boolean selection() {
      return roots.get(0) instanceof Cond;
    }
  }

  /** Where a shape's tree comes from: built by hand, or compiled from a Catalyst expression. */
  private sealed interface Source permits Built, Compiled { }

  private record Built(Tree tree) implements Source { }

  /**
   * A Catalyst expression over {@code columns}, compiled as a projection entry or, for a
   * {@code predicate}, as a filter condition.
   */
  private record Compiled(Expression expr, List<Attribute> columns, boolean predicate)
      implements Source { }

  /**
   * One shape: its label, where its tree comes from, and the rung column each input ordinal reads
   * and that column's stored lane.
   */
  private record Shape(String label, Source source, int[] columns, LaneType[] inputLanes) {
    Shape(String label, List<VarkaVectorIR> roots, int[] columns, LaneType[] inputLanes) {
      this(label, new Built(new Tree(roots, NO_LITS)), columns, inputLanes);
    }
  }

  private static List<Shape> shapes() {
    var y1 = new ColumnRef(0, LaneType.BYTE);
    var y2 = new ColumnRef(1, LaneType.BYTE);
    var intColumn = new ColumnRef(1, LaneType.INT);
    var low1 = new ColumnRef(0, LaneType.BYTE);
    var low2 = new ColumnRef(1, LaneType.BYTE);
    var compare = new Compare(CompareOp.LT, y1, y2);
    return List.of(
        new Shape("a: y1 < y2, selection", List.of(compare),
            new int[] {B1, B2}, new LaneType[] {LaneType.BYTE, LaneType.BYTE}),
        // Under NATIVE the add wraps at byte width in the byte species; under WIDENED it is an
        // int add whose value the compare reads before any narrowing. The two agree on every row
        // whose byte sum is in range, which is the data this shape draws (see overflowProbe).
        new Shape("b: (y1 + y2) < y1, selection",
            List.of(new Compare(CompareOp.LT, new IntArith(IntOp.ADD, Overflow.WRAP, low1, low2),
                low1)),
            new int[] {LOW1, LOW2}, new LaneType[] {LaneType.BYTE, LaneType.BYTE}),
        new Shape("c: b < i, selection (int-driven)",
            List.of(new Compare(CompareOp.LT, new WidenLane(y1, LaneType.INT), intColumn)),
            new int[] {B1, INTS}, new LaneType[] {LaneType.BYTE, LaneType.INT}),
        new Shape("e: CASE WHEN y1 < y2 THEN y1 ELSE y2 END, TINYINT store",
            List.of(new IfElse(compare, y1, y2)),
            new int[] {B1, B2}, new LaneType[] {LaneType.BYTE, LaneType.BYTE}),
        // The negation and abs a TINYINT column's unary arithmetic is, LEGACY, stored narrow.
        new Shape("f: -y1, TINYINT store", List.of(new IntNeg(Overflow.WRAP, y1)),
            new int[] {B1}, new LaneType[] {LaneType.BYTE}),
        // abs is the negation node with its flag: one lanewise ABS. It replaced the composition
        // IfElse(y1 < 0, -y1, y1), a compare, a negation and a blend, which the run before the
        // change timed (results/VARKA-329-negabs.txt).
        new Shape("g: abs(y1), TINYINT store",
            List.of(new IntNeg(Overflow.WRAP, y1, true)),
            new int[] {B1}, new LaneType[] {LaneType.BYTE}),
        checkedMultiply("h: y1 * y2, ANSI, TINYINT store", EvalMode.ANSI(), LaneType.BYTE,
            false, SMALL1, SMALL2),
        checkedMultiply("i: try_multiply(y1, y2), TRY, TINYINT store", EvalMode.TRY(),
            LaneType.BYTE, false, SMALL1, SMALL2),
        checkedMultiply("j: s1 * s2, ANSI, SMALLINT store", EvalMode.ANSI(), LaneType.SHORT,
            false, S1, S2),
        checkedMultiply("k: try_multiply(s1, s2), TRY, SMALLINT store", EvalMode.TRY(),
            LaneType.SHORT, false, S1, S2),
        // y3 is a column of random bytes, so the compare selects about half the rows.
        checkedMultiply("l: y1 * y2 < y3, ANSI, selection", EvalMode.ANSI(), LaneType.BYTE,
            true, SMALL1, SMALL2, B1));
  }

  /** Three {@code TINYINT} columns and two {@code SMALLINT} ones, for the compiled shapes. */
  private static final List<Attribute> BYTES = List.of(column("y1", new ByteType()),
      column("y2", new ByteType()), column("y3", new ByteType()));
  private static final List<Attribute> SHORTS = List.of(column("s1", new ShortType()),
      column("s2", new ShortType()));

  private static Attribute column(String name, DataType type) {
    return VarkaSqlResolve.column(name, type);
  }

  /**
   * The checked multiply of two columns of {@code lane} in {@code mode}, stored, or compared below
   * a third as a selection; {@code columns} are the rung columns its inputs read.
   */
  private static Shape checkedMultiply(String label, Enumeration.Value mode, LaneType lane,
      boolean selection, int... columns) {
    List<Attribute> attrs = lane == LaneType.SHORT ? SHORTS : BYTES;
    Expression product = Multiply$.MODULE$.apply(attrs.get(0), attrs.get(1), mode);
    LaneType[] lanes = new LaneType[columns.length];
    Arrays.fill(lanes, lane);
    Expression expr = selection ? LessThan$.MODULE$.apply(product, attrs.get(2)) : product;
    return new Shape(label, new Compiled(expr, attrs.subList(0, columns.length), selection),
        columns, lanes);
  }

  /** The int-driven shape, whose two arms must emit the same class bytes. */
  private static final int INT_DRIVEN = 2;

  // -------------------------------------------------------------------------------------------
  // The data
  // -------------------------------------------------------------------------------------------

  /**
   * One rung's columns: random bytes, in-range bytes for the add, small ints, and bytes and shorts
   * whose products stay in their type's range, for the checked multiply.
   */
  private record Data(MemorySegment[] columns) { }

  private static MemorySegment randomBytes(Arena arena, int n, long seed) {
    MemorySegment seg = arena.allocate(n, 64);
    var r = new SplittableRandom(seed);
    for (long off = 0; off < n; off += 8) {
      seg.set(ValueLayout.JAVA_LONG, off, r.nextLong());
    }
    return seg;
  }

  /** Bytes in [-64, 63]: the sum of two of them never leaves the byte range. */
  private static MemorySegment inRangeBytes(Arena arena, int n, long seed) {
    MemorySegment seg = arena.allocate(n, 64);
    var r = new SplittableRandom(seed);
    for (int i = 0; i < n; i++) {
      seg.set(ValueLayout.JAVA_BYTE, i, (byte) r.nextInt(-64, 64));
    }
    return seg;
  }

  /** Bytes in [-11, 11]: the product of two of them never leaves the byte range. */
  private static MemorySegment multiplicandBytes(Arena arena, int n, long seed) {
    MemorySegment seg = arena.allocate(n, 64);
    var r = new SplittableRandom(seed);
    for (int i = 0; i < n; i++) {
      seg.set(ValueLayout.JAVA_BYTE, i, (byte) r.nextInt(-11, 12));
    }
    return seg;
  }

  /** Shorts in [-181, 181]: the product of two of them never leaves the short range. */
  private static MemorySegment multiplicandShorts(Arena arena, int n, long seed) {
    MemorySegment seg = arena.allocate(n * 2L, 64);
    var r = new SplittableRandom(seed);
    for (int i = 0; i < n; i++) {
      seg.set(ValueLayout.JAVA_SHORT, i * 2L, (short) r.nextInt(-181, 182));
    }
    return seg;
  }

  private static MemorySegment smallInts(Arena arena, int n, long seed) {
    MemorySegment seg = arena.allocate(n * 4L, 64);
    var r = new SplittableRandom(seed);
    for (int i = 0; i < n; i++) {
      seg.set(ValueLayout.JAVA_INT, i * 4L, r.nextInt(-200, 200));
    }
    return seg;
  }

  private static Data data(Arena arena, int n) {
    return new Data(new MemorySegment[] {randomBytes(arena, n, 1), randomBytes(arena, n, 2),
        inRangeBytes(arena, n, 3), inRangeBytes(arena, n, 4), smallInts(arena, n, 5),
        multiplicandBytes(arena, n, 6), multiplicandBytes(arena, n, 7),
        multiplicandShorts(arena, n, 8), multiplicandShorts(arena, n, 9)});
  }

  /** A shape's input addresses in ordinal order. */
  private static long[] sourceAddresses(Shape shape, Data d) {
    long[] out = new long[shape.columns().length];
    for (int c = 0; c < out.length; c++) {
      out[c] = d.columns()[shape.columns()[c]].address();
    }
    return out;
  }

  // -------------------------------------------------------------------------------------------
  // Emitting the kernels
  // -------------------------------------------------------------------------------------------

  /**
   * An emitted kernel, the class bytes it came from, which the arms' shapes compare, and the tree
   * it was emitted from, which the reference evaluates.
   */
  private record Emitted(VarkaFusedKernel kernel, byte[] bytes, Tree tree) { }

  /**
   * The tree of {@code shape} under {@code options}: the hand-built one, or the one the compiler
   * builds, empty when the compiler declines it. A compiled tree must read the shape's columns in
   * order and fuse whole, or the benchmark would time something other than its label.
   */
  private static Optional<Tree> tree(Shape shape, VarkaEmitOptions options) {
    return switch (shape.source()) {
      case Built b -> Optional.of(b.tree());
      case Compiled c -> {
        var columns = CollectionConverters.asScala(c.columns()).toSeq();
        Optional<CompiledVarkaProjection> plan;
        if (c.predicate()) {
          Optional<CompiledVarkaPredicate> p =
              VarkaExpressionCompiler.compilePredicate(c.expr(), columns, options);
          p.ifPresent(q -> require(q.residualConjuncts().isEmpty(),
              shape.label() + ": the compiler left " + q.residualConjuncts() + " to a row filter"));
          plan = p.map(CompiledVarkaPredicate::fused);
        } else {
          var entries = CollectionConverters.asScala(
              List.of(VarkaSqlResolve.named(c.expr(), "c"))).toSeq();
          plan = VarkaExpressionCompiler.compile(entries, columns, options);
        }
        yield plan.map(p -> {
          var ordinals = IntStream.range(0, c.columns().size()).boxed().toList();
          require(p.inputOrdinals().equals(ordinals), shape.label() + ": the compiled kernel"
              + " reads the columns " + p.inputOrdinals() + ", not " + ordinals);
          return new Tree(p.outputs(),
              p.literals().stream().mapToInt(Integer::intValue).toArray());
        });
      }
    };
  }

  /**
   * Emits {@code shape} under {@code arm} and loads it, or returns empty with the reason the
   * emitter declined it. The class name carries both the shape and the arm: the settle gate reads
   * a kernel's compile levels from the JVM's code list by class name alone
   * ({@link VarkaEmitterTestSupport#loopLevels}), so two classes sharing one name would have
   * their loops' levels merged, and the WIDENED arm's level 4 could stand for a NATIVE loop still
   * at C1. The int-driven shape's byte comparison emits its own copies under one neutral name
   * instead ({@link #requireIntDrivenSameBytes}).
   */
  private Optional<Emitted> emit(String name, Shape shape, Arm arm,
      VarkaGeneratedClassLoader loader) {
    var options = arm.options();
    Optional<Tree> tree = tree(shape, options);
    if (tree.isEmpty()) {
      // The compiler demotes an entry whose column the arm's lane count cannot widen, the kernel
      // plan's refusal below; a decline for any other reason is a fault of the benchmark.
      Optional<String> widen = VarkaKernelPlan.of(
          List.of(new ColumnRef(0, shape.inputLanes()[0])),
          options.toBuilder().narrowLanes(false).build())
          .refusal().map(VarkaKernelPlan.Refusal::reason);
      require(widen.isPresent(), shape.label() + ", " + arm + ": the compiler declined it, and"
          + " not because the width cannot widen a " + shape.inputLanes()[0] + " column");
      System.out.println("VarkaNarrowEmitBenchmark: " + shape.label() + ", " + arm
          + " declined by the compiler: " + widen.get());
      return Optional.empty();
    }
    Optional<String> refusal = VarkaKernelPlan.of(tree.get().roots(), options).refusal()
        .map(VarkaKernelPlan.Refusal::reason);
    if (refusal.isPresent()) {
      System.out.println("VarkaNarrowEmitBenchmark: " + shape.label() + ", " + arm
          + " declined: " + refusal.get());
      return Optional.empty();
    }
    byte[] bytes = VarkaLoopEmitter.emit(name, tree.get().roots(), shape.inputLanes().length,
        tree.get().literals().length,
        null, null, options);
    return Optional.of(new Emitted(defineKernel(loader, name, bytes), bytes, tree.get()));
  }

  /** One arm's kernel name: the shape and the arm, so that no two kernels share one. */
  private static String kernelName(int shapeId, Arm arm) {
    return "org.apache.spark.sql.varka.bench.NarrowEmit" + shapeId + arm.name().charAt(0);
  }

  /** The name both arms are emitted under when the byte-identity check compares them. */
  private static final String SAME_BYTES_NAME =
      "org.apache.spark.sql.varka.bench.NarrowEmitSame";

  // -------------------------------------------------------------------------------------------
  // Calling a kernel
  // -------------------------------------------------------------------------------------------

  /** The output buffers of one shape: data (a store shape only) and the validity or selection. */
  private record Outputs(MemorySegment data, MemorySegment bits) { }

  private static Outputs outputs(Arena arena, Tree tree, int n) {
    // A condition root writes no data, and the kernel contract gives it a null data address.
    MemorySegment data = tree.selection() ? MemorySegment.NULL
        : arena.allocate((long) n * storeBytes(tree.roots().get(0).laneType()), 64);
    return new Outputs(data, arena.allocate(((n + 63) / 64) * 8L, 64));
  }

  /**
   * The arrays of one invocation, made once and reused: a fresh set per call would put several
   * allocations inside the timed loop, which at the smallest rung would outweigh the kernel.
   */
  private record Invocation(long[] srcData, long[] srcValidity, int[] nullCounts,
      long[] dstData, long[] dstValidity, int[] lits, int length) {

    static Invocation of(Shape shape, Tree tree, Data d, Outputs out, int n) {
      int inputs = shape.columns().length;
      return new Invocation(sourceAddresses(shape, d), new long[inputs], new int[inputs],
          new long[] {out.data().address()}, new long[] {out.bits().address()},
          tree.literals(), n);
    }

    int run(VarkaFusedKernel kernel, long scratch) {
      return kernel.run(srcData, srcValidity, nullCounts, dstData, dstValidity, lits, length,
          scratch);
    }
  }

  // -------------------------------------------------------------------------------------------
  // Checking a kernel against the reference
  // -------------------------------------------------------------------------------------------

  /** Bit {@code i} of a validity or selection bitmap, LSB first as Arrow lays it out. */
  private static boolean bit(MemorySegment bits, int i) {
    return (bits.get(ValueLayout.JAVA_BYTE, i / 8L) & (1 << (i % 8))) != 0;
  }

  /** The first {@code rows} bits of a bitmap as text, one character a row. */
  private static String headBits(MemorySegment bits, int rows) {
    var sb = new StringBuilder(rows);
    for (int i = 0; i < rows; i++) {
      sb.append(bit(bits, i) ? '1' : '0');
    }
    return sb.toString();
  }

  /** The bytes a row of {@code lane} takes in a column, the evaluator's layout. */
  private static int storeBytes(LaneType lane) {
    return switch (lane) {
      case BYTE -> 1;
      case SHORT -> 2;
      case INT -> 4;
      case LONG, FLOAT, DOUBLE -> throw new IllegalArgumentException("no " + lane + " column here");
    };
  }

  /** One row's value as the reference reads it: sign-extended from the column's stored lane. */
  private static int rowValue(LaneType lane, MemorySegment column, int i) {
    return switch (lane) {
      case BYTE -> column.get(ValueLayout.JAVA_BYTE, i);
      case SHORT -> column.get(ValueLayout.JAVA_SHORT, i * 2L);
      case INT -> column.get(ValueLayout.JAVA_INT, i * 4L);
      case LONG, FLOAT, DOUBLE -> throw new IllegalArgumentException("no " + lane + " column here");
    };
  }

  /**
   * Compares a whole batch's answer with {@link VarkaReference}: for a selection root every row's
   * bit against the reference's truth, for a store root every row's value and validity bit
   * against the reference's value. A disagreement fails the run: it is a fault of the measurement,
   * not a data point.
   */
  private static void verify(String context, Shape shape, Tree tree, Data d, int n,
      Outputs out) {
    VarkaVectorIR root = tree.roots().get(0);
    int numInputs = shape.inputLanes().length;
    int[] row = new int[numInputs];
    boolean[] isNull = new boolean[numInputs];
    boolean selection = tree.selection();
    for (int i = 0; i < n; i++) {
      for (int c = 0; c < numInputs; c++) {
        row[c] = rowValue(shape.inputLanes()[c], d.columns()[shape.columns()[c]], i);
      }
      boolean got = bit(out.bits(), i);
      if (selection) {
        boolean want = VarkaReference.cond((Cond) root, row, isNull, tree.literals()).isTrue();
        require(got == want, context + ": selection row " + i + " differs (want " + want
            + ", got " + got + ")");
      } else {
        OptionalInt want = VarkaReference.value(root, row, isNull, tree.literals());
        require(got == want.isPresent(), context + ": validity of row " + i + " differs");
        if (want.isPresent()) {
          int value = rowValue(root.laneType(), out.data(), i);
          require(value == want.getAsInt(), context + ": row " + i + " differs (want "
              + want.getAsInt() + ", got " + value + ")");
        }
      }
    }
  }

  // -------------------------------------------------------------------------------------------
  // Driving an arm to compiled code
  // -------------------------------------------------------------------------------------------

  /** {@code reps} calls of the kernel, failing the run on a declined batch. */
  private static void drive(Invocation inv, VarkaFusedKernel kernel, Shape shape, long scratch,
      int reps) {
    for (int r = 0; r < reps; r++) {
      // The message is built only on a decline, so the timed path allocates nothing.
      int status = inv.run(kernel, scratch);
      if (status != 0) {
        throw requirementFailed(shape.label() + " declined a batch (status " + status + ")");
      }
    }
  }

  /**
   * Drives {@code kernel} until every dense loop method of its class holds level-4 code and the
   * drive time is steady (row 407's rule); a class with no dense loop, a TRY shape's, which can
   * null a row of valid inputs, is read by its masked loops, the ones the drive runs. Bounded: an
   * arm that does not settle in 30 seconds says so, with the level each loop method had, and is
   * timed as it is.
   */
  private static void driveToCompiledCode(String label, VarkaFusedKernel kernel, Shape shape,
      Invocation inv, long scratch, int reps) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WARM_WAIT_MILLIS);
    Class<?> kernelClass = kernel.getClass();
    long previous = 0;
    int drives = 0;
    while (true) {
      long start = System.nanoTime();
      drive(inv, kernel, shape, scratch, reps);
      long took = System.nanoTime() - start;
      drives++;
      boolean idle = VarkaEmitterTestSupport.awaitC2Idle(IDLE_WAIT_MILLIS);
      var levels = VarkaEmitterTestSupport.loopLevels(kernelClass);
      if (levels.isEmpty()) {
        levels = VarkaEmitterTestSupport.loopLevels(kernelClass, false);
      }
      boolean topTier = VarkaEmitterTestSupport.allLoopsC2(levels);
      boolean steady = previous > 0 && Math.abs(took - previous) <= previous * STEADY_FRACTION;
      if (drives >= MIN_WARM_DRIVES && idle && topTier && steady) {
        System.out.println("VarkaNarrowEmitBenchmark: " + label + " at " + inv.length()
            + " rows settled after " + drives + " drives, loops at level 4");
        return;
      }
      if (System.nanoTime() > deadline) {
        System.out.println("VarkaNarrowEmitBenchmark: " + label + " at " + inv.length()
            + " rows did not settle in " + WARM_WAIT_MILLIS + " ms (" + drives + " drives, the"
            + " last " + TimeUnit.NANOSECONDS.toMicros(took) + " us after "
            + TimeUnit.NANOSECONDS.toMicros(previous) + " us, C2 " + (idle ? "idle" : "busy")
            + ", loop levels " + levels + "); it is timed as it is");
        return;
      }
      previous = took;
    }
  }

  // -------------------------------------------------------------------------------------------
  // The overflow probe: what shape (b) does not measure
  // -------------------------------------------------------------------------------------------

  /**
   * Shape (b) on the data its rungs leave out: rows whose byte sums overflow. Prints each arm's
   * selection bits beside the reference's, because the arms disagree there and the reference (an
   * int add, as {@link VarkaReference} evaluates this node) sides with WIDENED. Spark's own
   * answer is the byte-wrapped one, the deferred wrap VARKA-329.md 4.2 assigns to row 330; until
   * it lands this shape is measured on in-range data only.
   */
  private void overflowProbe(Arena arena, List<Shape> allShapes, List<Arm> arms,
      Optional<Emitted>[][] emitted) {
    int n = 64;
    MemorySegment y1 = arena.allocate(n, 64);
    MemorySegment y2 = arena.allocate(n, 64);
    y1.fill((byte) 100);
    y2.fill((byte) 100);
    var d = new Data(new MemorySegment[] {y1, y2, y1, y2, y1});
    Shape shape = allShapes.get(1);
    for (int a = 0; a < arms.size(); a++) {
      if (emitted[1][a].isEmpty()) {
        continue;
      }
      var kernel = emitted[1][a].get().kernel();
      var out = outputs(arena, emitted[1][a].get().tree(), n);
      var inv = Invocation.of(shape, emitted[1][a].get().tree(), d, out, n);
      long scratch = VarkaEmitterTestSupport.scratch(kernel, n);
      int status = inv.run(kernel, scratch);
      System.out.println("VarkaNarrowEmitBenchmark: overflow probe (100 + 100 as TINYINT, which "
          + "is " + (100 + 100 < 100) + " in int arithmetic), " + arms.get(a) + ": status "
          + status + ", the first four selection bits " + headBits(out.bits(), 4));
    }
  }

  // -------------------------------------------------------------------------------------------
  // The benchmark
  // -------------------------------------------------------------------------------------------

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    var arms = List.copyOf(selectedArms());
    var allShapes = shapes();
    var loaders = new VarkaGeneratedClassLoader[arms.size()];
    @SuppressWarnings("unchecked")
    Optional<Emitted>[][] emitted = new Optional[allShapes.size()][arms.size()];
    int bits = IntVector.SPECIES_PREFERRED.vectorBitSize();
    try {
      for (int a = 0; a < arms.size(); a++) {
        loaders[a] = new VarkaGeneratedClassLoader(getClass().getClassLoader());
        for (int s = 0; s < allShapes.size(); s++) {
          emitted[s][a] = emit(kernelName(s, arms.get(a)), allShapes.get(s), arms.get(a),
              loaders[a]);
        }
      }
      requireIntDrivenSameBytes(allShapes.get(INT_DRIVEN), arms, emitted[INT_DRIVEN]);
      for (int s = 0; s < allShapes.size(); s++) {
        if (allShapes.get(s).source() instanceof Compiled
            && Arrays.stream(emitted[s]).filter(Optional::isPresent).count() > 1) {
          boolean same = armsEmitSameBytes(allShapes.get(s), arms, emitted[s]);
          System.out.println("VarkaNarrowEmitBenchmark: " + allShapes.get(s).label()
              + ", the arms' class bytes are " + (same ? "identical" : "different"));
        }
      }
      section("emitted narrow kernels at " + bits + " bits, arms "
          + arms.stream().map(Arm::token).toList(), () -> {
            for (Rung rung : ladder()) {
              runRung(rung, allShapes, arms, emitted);
            }
            try (Arena arena = Arena.ofConfined()) {
              overflowProbe(arena, allShapes, arms, emitted);
            }
          });
    } finally {
      for (var loader : loaders) {
        if (loader != null) {
          loader.release();
        }
      }
    }
  }

  /**
   * The int-driven shape (c) expects one kernel from both arms: the tree holds an int value, so
   * the arm does not reach the narrow lane. Identical class bytes are what "one kernel" means,
   * and a difference here would mean an option changed the bytecode of a shape it should not.
   * The timed kernels are named per arm, and class bytes carry the name in the constant pool, so
   * this emits the shape once more under each arm under one neutral name and compares those.
   */
  private static void requireIntDrivenSameBytes(Shape shape, List<Arm> arms,
      Optional<Emitted>[] emitted) {
    require(armsEmitSameBytes(shape, arms, emitted), "the arms' bytes differ for "
        + shape.label() + ", whose tree holds an int value and so should not reach the narrow"
        + " lane");
  }

  /**
   * Whether every arm that emitted {@code shape} emitted the same class bytes, each emitted once
   * more under {@link #SAME_BYTES_NAME} for the comparison. The run prints it for the compiled
   * shapes, whose arms {@code VARKA-481.md} 6.1 predicts to emit one kernel.
   */
  private static boolean armsEmitSameBytes(Shape shape, List<Arm> arms,
      Optional<Emitted>[] emitted) {
    var bytes = new ArrayList<byte[]>();
    for (int a = 0; a < arms.size(); a++) {
      if (emitted[a].isPresent()) {
        Tree tree = emitted[a].get().tree();
        bytes.add(VarkaLoopEmitter.emit(SAME_BYTES_NAME, tree.roots(),
            shape.inputLanes().length, tree.literals().length, null, null,
            arms.get(a).options()));
      }
    }
    for (int k = 1; k < bytes.size(); k++) {
      if (!Arrays.equals(bytes.get(0), bytes.get(k))) {
        return false;
      }
    }
    return true;
  }

  /** One rung: fresh buffers, every shape, every arm, each checked against the reference. */
  private void runRung(Rung rung, List<Shape> allShapes, List<Arm> arms,
      Optional<Emitted>[][] emitted) {
    int n = rung.numRows();
    require(n % 64 == 0, n + " rows is not a multiple of 64");
    int reps = (int) Math.max(1, ROWS_PER_ITERATION / n);
    try (Arena arena = Arena.ofConfined()) {
      Data d = data(arena, n);
      var benchmark = benchmark(n + " rows - " + rung.level(), (long) n * reps)
          .minIterations(3).warmup(Duration.ofSeconds(1)).minTime(Duration.ofSeconds(1)).build();
      for (int s = 0; s < allShapes.size(); s++) {
        Shape shape = allShapes.get(s);
        var outs = new Outputs[arms.size()];
        var invs = new Invocation[arms.size()];
        for (int a = 0; a < arms.size(); a++) {
          if (emitted[s][a].isPresent()) {
            Tree tree = emitted[s][a].get().tree();
            outs[a] = outputs(arena, tree, n);
            invs[a] = Invocation.of(shape, tree, d, outs[a], n);
          }
        }
        for (int a = 0; a < arms.size(); a++) {
          if (emitted[s][a].isEmpty()) {
            continue;
          }
          var kernel = emitted[s][a].get().kernel();
          Tree tree = emitted[s][a].get().tree();
          long scratch = VarkaEmitterTestSupport.scratch(kernel, n);
          var inv = invs[a];
          var out = outs[a];
          String label = shape.label() + ", " + arms.get(a);
          require(inv.run(kernel, scratch) == 0, label + " declined the batch");
          verify(label + " (interpreter)", shape, tree, d, n, out);
          var settled = new AtomicBoolean();
          benchmark.addTimerCase(label, timer -> {
            if (settled.compareAndSet(false, true)) {
              driveToCompiledCode(label, kernel, shape, inv, scratch, reps);
              // The answer of the compiled code, not the interpreter's: the Vector API is plain
              // Java until C2 compiles the loop, and the reference has to meet both.
              verify(label + " (compiled)", shape, tree, d, n, out);
            }
            timer.startTiming();
            drive(inv, kernel, shape, scratch, reps);
            timer.stopTiming();
          });
        }
      }
      runCases(benchmark);
    }
  }
}
