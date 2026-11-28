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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import scala.collection.immutable.Seq;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.Add;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.EvalMode$;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.catalyst.expressions.GreaterThan;
import org.apache.spark.sql.catalyst.expressions.If;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.Multiply;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.expressions.NumericEvalContext;
import org.apache.spark.sql.catalyst.expressions.NumericEvalContext$;
import org.apache.spark.sql.catalyst.expressions.ShiftRight;
import org.apache.spark.sql.catalyst.expressions.Subtract;
import org.apache.spark.sql.catalyst.expressions.UnsafeProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterTestSupport;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterTestSupport.Settle;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterTestSupport.TierState;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFusedKernel;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaLoopEmitter;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMethodNames;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSqlResolve;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTierWitness;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;

/**
 * The emitter's chain-depth cap priced past 16 ({@code sql/varka/plans/m8/VARKA-620.md}).
 *
 * <p>{@code VarkaEmitBudget.chainDepthCap} caps one output's height at 16, the depth
 * {@code VARKA-9.md} 5.2 measured a chain up to and no further, or at 64 where this file's
 * readings support it: a tree of the int lane alone, and one of the long lane alone where the
 * long vector is 256 bits or wider. The decimal rows of milestone 8 build shapes 26 to 60 deep,
 * so this file measures what a deeper chain costs: the fused kernel under the cap raised to
 * {@link #RAISED_CAP} through the {@code maxChainDepth} emit option, against Spark's row path
 * and against a scalar loop of the same steps, at five depths and two lanes.
 *
 * <p><b>The shape.</b> One output over two columns {@code x} and {@code y}, a decimal-like mix
 * of operations repeated to the target height, ANSI-checked but for the multiply, which wraps
 * (see {@link #LEGACY}): {@code e + y}, {@code e * 3},
 * {@code shiftright(e, 2)} and {@code e - (CASE WHEN e > y THEN y ELSE 0 END)}, the last a
 * compare and a select on the running value, as a decimal's overflow and rounding tests are, and
 * three levels deep. A depth that the cycle of six levels does not divide ends with up to five
 * single-level steps. The Catalyst tree is built once and handed to both {@code
 * VarkaExpressionCompiler} and Spark's {@code UnsafeProjection}, so the two arms evaluate one
 * expression, and the scalar loop is that expression written out in Java. Each emit asserts that
 * the compiled output's height is the depth its label names and that the default options fuse
 * it exactly up to the tree's default cap ({@code VarkaEmitBudget.chainDepthCap}: 64 on the int
 * lane, and on the BIGINT lane 64 where the long vector is 256 bits or wider and 16 at 128
 * bits). The literal table is the same at every depth, so the broadcast hoist, a literal count
 * ({@code VarkaEmitBudget.HOIST_LITERALS}), is the same along the ladder; at every depth the
 * default cap admits, the kernel under the raised cap is checked to be byte for byte the
 * default's.
 *
 * <p><b>The values</b> keep the chain inside both lanes: {@code x} below 2^40 (BIGINT) or 2^20
 * (INT) in magnitude, {@code y} in [1, 1000], so each cycle shrinks the value by a quarter and
 * adds at most {@code y}. Every arm's output is checked against Spark's before it is timed, and
 * Spark's answers are checked to be far from constant, so a check cannot pass on a degenerate
 * column.
 *
 * <p><b>What a section records.</b> Per depth, a table of the three arms over {@link #numRows}
 * null-free rows, {@link #BUFFER_ROWS} of them driven again and again in chunks of {@link #CHUNK},
 * Spark's batch size, so every arm runs from L2; a note of every group method's
 * bytecode size, against HotSpot's 8000-byte {@code HugeMethodLimit}; and a note of the fused
 * arm's tier state, read by {@link VarkaTierWitness} after the arm is settled: whether every
 * dense loop method is C2 code. The two sections, {@code chain depth past the cap: BIGINT lane}
 * and {@code chain depth past the cap: INT lane}, and the case and note labels are stable, so
 * runs at two widths diff by label.
 *
 * <p>A dry run ({@code -Dvarka.bench.dryRun=true}) emits every kernel, prints the byte notes and
 * runs each arm once over {@link #DRY_ROWS} rows, one chunk and a ragged tail, checking every
 * answer, and settles the deepest rung of each lane on the tier witness; it times nothing.
 *
 * <pre>
 *   build/sbt "catalyst/Test/runMain org.apache.spark.sql.VarkaChainDepthBenchmark"
 *   dev/varka_bench_regen.sh catalyst VarkaChainDepthBenchmark
 * </pre>
 */
public class VarkaChainDepthBenchmark extends VarkaBenchmarkBase {

  /** The depths of the ladder: the cap, then past it to the deepest decimal shape and beyond. */
  private static final List<Integer> DEPTHS = List.of(16, 24, 32, 48, 64);

  /** The cap the fused arm is emitted under: the deepest rung, so every rung is admitted. */
  private static final int RAISED_CAP = 64;

  /** Spark's {@code COLUMN_BATCH_SIZE}: the chunk every arm is driven in. */
  private static final int CHUNK = 4096;

  /** The rows of a timed run. Read at run time, never folded into a timed loop by javac. */
  private static int numRows = 1 << 20;

  /**
   * The buffer a timed run re-drives to {@link #numRows}: 16384 rows, three columns of at most
   * eight bytes, 384 KB, inside a core's L2 at both lanes ({@code VarkaLongLaneBenchmark}'s
   * in-L2 rung). One pass over a million rows would stream 24 MB at the BIGINT lane, which leaves
   * L3, so a shallow rung would price memory and a deep one compute, and a ratio against depth 16
   * would read the cache boundary instead of the depth.
   */
  private static final int BUFFER_ROWS = 16384;

  /** The rows of a dry run: one chunk and a ragged tail. */
  private static final int DRY_ROWS = CHUNK + 3;

  /** At least this many distinct answers in the first chunk, or the check reads a constant. */
  private static final int MIN_DISTINCT = 64;

  private static final int MIN_WARM_DRIVES = 8;
  private static final double STEADY_FRACTION = 0.3;
  private static final long WARM_WAIT_MILLIS = 30_000;

  private static final NumericEvalContext ANSI =
      NumericEvalContext$.MODULE$.apply(EvalMode$.MODULE$.ANSI(), false);

  /**
   * The multiply's mode. A checked BIGINT multiply whose operands do not rule out overflow
   * declines (row 104's 128-bit product), so the multiply wraps at both lanes. The values never
   * reach an overflow, so its answers are the checked ones.
   */
  private static final NumericEvalContext LEGACY =
      NumericEvalContext$.MODULE$.apply(EvalMode$.MODULE$.LEGACY(), false);

  /** One step of the chain, by the levels it adds to the height. */
  private enum Step {
    ADD(1), MUL(1), SHR(1), CMP(3);

    final int levels;

    Step(int levels) {
      this.levels = levels;
    }
  }

  /** The repeated cycle, six levels deep, and the single-level steps a remainder takes. */
  private static final List<Step> CYCLE = List.of(Step.ADD, Step.MUL, Step.SHR, Step.CMP);
  private static final List<Step> REMAINDER =
      List.of(Step.ADD, Step.MUL, Step.SHR, Step.ADD, Step.MUL);
  private static final int CYCLE_LEVELS = 6;

  /** A lane: its label, its Spark type and its byte width. */
  private record Lane(String label, DataType type, int bytes) {
    boolean isLong() {
      return bytes == 8;
    }

    Literal literal(long v) {
      return isLong() ? Literal.create(v, type) : Literal.create((int) v, type);
    }
  }

  private static final List<Lane> LANES = List.of(
      new Lane("BIGINT", DataTypes.LongType, 8), new Lane("INT", DataTypes.IntegerType, 4));

  /** One rung: its depth, its steps, its kernel and class bytes, and Spark's projection. */
  private record Rung(int depth, int cycles, List<Step> tail, CompiledVarkaProjection fused,
      VarkaFusedKernel kernel, byte[] bytes, UnsafeProjection spark) { }

  private int nextId = 0;

  // ---------------------------------------------------------------------------------------
  // The shape
  // ---------------------------------------------------------------------------------------

  /** The cycles and the tail of a chain {@code depth} levels high. */
  private static int cycles(int depth) {
    return depth / CYCLE_LEVELS;
  }

  private static List<Step> tail(int depth) {
    return REMAINDER.subList(0, depth % CYCLE_LEVELS);
  }

  /** The Catalyst tree of the chain over {@code x} and {@code y}, shared subtrees and all. */
  private static Expression chain(Lane lane, int depth, Attribute x, Attribute y) {
    var steps = new ArrayList<Step>();
    for (int c = 0; c < cycles(depth); c++) {
      steps.addAll(CYCLE);
    }
    steps.addAll(tail(depth));
    require(steps.stream().mapToInt(s -> s.levels).sum() == depth,
        "the steps of depth " + depth + " add up to another height");
    Expression e = x;
    for (Step s : steps) {
      e = switch (s) {
        case ADD -> new Add(e, y, ANSI);
        case MUL -> new Multiply(e, lane.literal(3), LEGACY);
        case SHR -> new ShiftRight(e, Literal.create(2, DataTypes.IntegerType));
        case CMP -> new Subtract(e, new If(new GreaterThan(e, y), y, lane.literal(0)), ANSI);
      };
    }
    return e;
  }

  /** The height of an IR node, as the emitter's analysis counts it: a leaf is 0. */
  private static int height(VarkaVectorIR node, Map<VarkaVectorIR, Integer> memo) {
    Integer known = memo.get(node);
    if (known != null) {
      return known;
    }
    int h = 0;
    for (VarkaVectorIR child : VarkaVectorIR.childrenOf(node)) {
      h = Math.max(h, 1 + height(child, memo));
    }
    memo.put(node, h);
    return h;
  }

  /** Compiles, checks and emits one rung, and builds Spark's projection of the same tree. */
  private Rung rung(Lane lane, int depth, VarkaGeneratedClassLoader loader) {
    Attribute x = VarkaSqlResolve.column("x", lane.type());
    Attribute y = VarkaSqlResolve.column("y", lane.type());
    Seq<Attribute> columns = seq(List.of(x, y));
    Expression tree = chain(lane, depth, x, y);
    Seq<NamedExpression> project = seq(List.of(VarkaSqlResolve.named(tree, "out")));
    String label = lane.label() + " depth " + depth;
    VarkaEmitOptions raised = VarkaEmitOptions.DEFAULTS.withMaxChainDepth(RAISED_CAP);
    CompiledVarkaProjection fused = VarkaExpressionCompiler.compile(project, columns, raised)
        .orElseThrow(() -> requirementFailed(label + " did not fuse under the raised cap: "
            + VarkaExpressionCompiler.declines(project, columns, raised)));
    require(fused.outputs().size() == 1 && fused.derivedInputs().isEmpty(),
        label + " compiled to " + fused.outputs().size() + " outputs and derived inputs "
            + fused.derivedInputs());
    int h = height(fused.outputs().get(0), new HashMap<>());
    require(h == depth, label + " compiled to height " + h);
    // The default cap is the tree's own: 64 on the int lane, and on the BIGINT lane 64 where
    // the long vector is 256 bits or wider and 16 at 128 bits.
    int defaultCap = VarkaEmitterTestSupport.chainDepthCap(VarkaEmitOptions.DEFAULTS,
        fused.outputs().get(0));
    boolean defaultFuses = VarkaExpressionCompiler.compile(project, columns,
        VarkaEmitOptions.DEFAULTS).isPresent();
    require(defaultFuses == depth <= defaultCap,
        label + (defaultFuses ? " fused" : " did not fuse") + " under the default cap");
    int id = nextId++;
    claimCaseId(id);
    String name = "org.apache.spark.sql.varka.execution.VarkaChainDepthBench" + id;
    var outputs = new ArrayList<VarkaVectorIR>(fused.outputs());
    int inputs = fused.inputOrdinals().size();
    byte[] bytes = VarkaLoopEmitter.emit(name, outputs, inputs, fused.numLiterals(), null, null,
        raised);
    if (depth <= defaultCap) {
      byte[] atDefault = VarkaLoopEmitter.emit(name, outputs, inputs, fused.numLiterals(), null,
          null, VarkaEmitOptions.DEFAULTS);
      require(Arrays.equals(bytes, atDefault),
          label + ": the raised cap emitted other bytes than the default");
    }
    VarkaFusedKernel kernel = defineKernel(loader, name, bytes);
    UnsafeProjection spark = UnsafeProjection.create(seq(List.of(tree)), columns);
    return new Rung(depth, cycles(depth), tail(depth), fused, kernel, bytes, spark);
  }

  /** Every group method's bytecode size, in the class's order. */
  private static String methodBytes(byte[] bytes) {
    var out = new StringJoiner(", ");
    int largest = 0;
    for (String m : VarkaEmitterTestSupport.methodNames(bytes)) {
      if (VarkaMethodNames.isGroupMethod(m)) {
        int size = VarkaEmitterTestSupport.codeSize(bytes, m);
        largest = Math.max(largest, size);
        out.add(m + " " + size);
      }
    }
    return out + "; largest " + largest + " of HugeMethodLimit 8000";
  }

  // ---------------------------------------------------------------------------------------
  // The columns and the three arms
  // ---------------------------------------------------------------------------------------

  /** A lane's columns, on and off the heap, and each arm's output. */
  private record Columns(Lane lane, int rows, long[] xs, long[] ys, MemorySegment x,
      MemorySegment y, MemorySegment dst, MemorySegment dstValidity, long[] sparkOut,
      long[] scalarOut, long[] expected) {

    static Columns of(Arena arena, Lane lane, int rows) {
      long[] xs = new long[rows];
      long[] ys = new long[rows];
      long xRange = lane.isLong() ? 1L << 40 : 1L << 20;
      MemorySegment x = arena.allocate((long) rows * lane.bytes(), 64);
      MemorySegment y = arena.allocate((long) rows * lane.bytes(), 64);
      for (int i = 0; i < rows; i++) {
        long h = (i + 1) * 0x9E3779B97F4A7C15L;
        xs[i] = Math.floorMod(h >> 7, 2 * xRange) - xRange;
        ys[i] = 1 + Math.floorMod(h >>> 41, 1000L);
        if (lane.isLong()) {
          x.set(ValueLayout.JAVA_LONG, i * 8L, xs[i]);
          y.set(ValueLayout.JAVA_LONG, i * 8L, ys[i]);
        } else {
          x.set(ValueLayout.JAVA_INT, i * 4L, (int) xs[i]);
          y.set(ValueLayout.JAVA_INT, i * 4L, (int) ys[i]);
        }
      }
      // A word-writing kernel owns the whole last word of its validity.
      MemorySegment dstValidity = arena.allocate((rows + 63) / 64 * 8L + 8, 64);
      MemorySegment dst = arena.allocate((long) rows * lane.bytes(), 64);
      return new Columns(lane, rows, xs, ys, x, y, dst, dstValidity, new long[rows],
          new long[rows], new long[rows]);
    }

    long fusedAt(int i) {
      return lane.isLong() ? dst.get(ValueLayout.JAVA_LONG, i * 8L)
          : dst.get(ValueLayout.JAVA_INT, i * 4L);
    }
  }

  /** The fused arm over every chunk; the status of every call ORed. */
  private static final class FusedDrive {
    private final VarkaFusedKernel kernel;
    private final Columns c;
    private final long[] src = new long[2];
    private final long[] srcValidity = new long[2];
    private final int[] nulls = new int[2];
    private final long[] dst = new long[1];
    private final long[] dstValidity = new long[1];
    private final int[] intLits;
    private final long[] longLits;
    private final int[] ordinals;
    private final long scratch;

    FusedDrive(Rung r, Columns c) {
      this.kernel = r.kernel();
      this.c = c;
      this.intLits = r.fused().literals().stream().mapToInt(Integer::intValue).toArray();
      this.longLits = r.fused().longLiterals().stream().mapToLong(Long::longValue).toArray();
      this.ordinals = r.fused().inputOrdinals().stream().mapToInt(Integer::intValue).toArray();
      this.scratch = VarkaEmitterTestSupport.scratch(kernel, CHUNK);
    }

    int drive() {
      int status = 0;
      int w = c.lane().bytes();
      for (int off = 0; off < c.rows(); off += CHUNK) {
        int len = Math.min(CHUNK, c.rows() - off);
        for (int k = 0; k < ordinals.length; k++) {
          src[k] = (ordinals[k] == 0 ? c.x() : c.y()).address() + (long) off * w;
        }
        dst[0] = c.dst().address() + (long) off * w;
        dstValidity[0] = c.dstValidity().address() + off / 8;
        status |= c.lane().isLong()
            ? kernel.run(src, srcValidity, nulls, dst, dstValidity, intLits, longLits, len,
                scratch)
            : kernel.run(src, srcValidity, nulls, dst, dstValidity, intLits, len, scratch);
      }
      return status;
    }

    void driveChecked() {
      int status = drive();
      if (status != 0) {
        throw requirementFailed("the kernel declined a batch: status " + status);
      }
    }
  }

  /** Spark's row path: the generated projection over one row at a time. */
  private static void sparkDrive(UnsafeProjection p, Columns c) {
    var row = new GenericInternalRow(2);
    boolean isLong = c.lane().isLong();
    long[] out = c.sparkOut();
    for (int i = 0; i < c.rows(); i++) {
      if (isLong) {
        row.setLong(0, c.xs()[i]);
        row.setLong(1, c.ys()[i]);
        out[i] = p.apply(row).getLong(0);
      } else {
        row.setInt(0, (int) c.xs()[i]);
        row.setInt(1, (int) c.ys()[i]);
        InternalRow r = p.apply(row);
        out[i] = r.getInt(0);
      }
    }
  }

  /*
   * The scalar loops. The tail is a prefix of REMAINDER, so each tail length gets its own row
   * loop with its steps written out: no rung pays a per-row dispatch over its tail's steps, and
   * every rung costs the same per level. DEPTHS leave tails of 0, 2 and 4 steps only, so those
   * are the loops there are. The small helpers inline under C2.
   */

  /** The scalar loop at the BIGINT lane: the cycles, then the tail written out. */
  private static void scalarLong(long[] xs, long[] ys, long[] out, int rows, int cycles,
      int tail) {
    switch (tail) {
      case 0 -> {
        for (int i = 0; i < rows; i++) {
          long y = ys[i];
          long x = cyclesLong(xs[i], y, cycles);
          out[i] = x;
        }
      }
      case 2 -> {
        for (int i = 0; i < rows; i++) {
          long y = ys[i];
          long x = cyclesLong(xs[i], y, cycles);
          x = addLong(x, y);
          x = mulLong(x);
          out[i] = x;
        }
      }
      case 4 -> {
        for (int i = 0; i < rows; i++) {
          long y = ys[i];
          long x = cyclesLong(xs[i], y, cycles);
          x = addLong(x, y);
          x = mulLong(x);
          x = shrLong(x);
          x = addLong(x, y);
          out[i] = x;
        }
      }
      default -> throw new IllegalArgumentException("a tail of " + tail + " steps");
    }
  }

  private static long cyclesLong(long x, long y, int cycles) {
    for (int c = 0; c < cycles; c++) {
      x = mulLong(addLong(x, y));
      x = shrLong(x);
      x = Math.subtractExact(x, x > y ? y : 0L);
    }
    return x;
  }

  private static long addLong(long x, long y) {
    return Math.addExact(x, y);
  }

  private static long mulLong(long x) {
    return x * 3L;
  }

  private static long shrLong(long x) {
    return x >> 2;
  }

  /** The scalar loop at the INT lane. */
  private static void scalarInt(long[] xs, long[] ys, long[] out, int rows, int cycles,
      int tail) {
    switch (tail) {
      case 0 -> {
        for (int i = 0; i < rows; i++) {
          int y = (int) ys[i];
          int x = cyclesInt((int) xs[i], y, cycles);
          out[i] = x;
        }
      }
      case 2 -> {
        for (int i = 0; i < rows; i++) {
          int y = (int) ys[i];
          int x = cyclesInt((int) xs[i], y, cycles);
          x = addInt(x, y);
          x = mulInt(x);
          out[i] = x;
        }
      }
      case 4 -> {
        for (int i = 0; i < rows; i++) {
          int y = (int) ys[i];
          int x = cyclesInt((int) xs[i], y, cycles);
          x = addInt(x, y);
          x = mulInt(x);
          x = shrInt(x);
          x = addInt(x, y);
          out[i] = x;
        }
      }
      default -> throw new IllegalArgumentException("a tail of " + tail + " steps");
    }
  }

  private static int cyclesInt(int x, int y, int cycles) {
    for (int c = 0; c < cycles; c++) {
      x = mulInt(addInt(x, y));
      x = shrInt(x);
      x = Math.subtractExact(x, x > y ? y : 0);
    }
    return x;
  }

  private static int addInt(int x, int y) {
    return Math.addExact(x, y);
  }

  private static int mulInt(int x) {
    return x * 3;
  }

  private static int shrInt(int x) {
    return x >> 2;
  }

  private static void scalarDrive(Rung r, Columns c) {
    int tail = r.tail().size();
    if (c.lane().isLong()) {
      scalarLong(c.xs(), c.ys(), c.scalarOut(), c.rows(), r.cycles(), tail);
    } else {
      scalarInt(c.xs(), c.ys(), c.scalarOut(), c.rows(), r.cycles(), tail);
    }
  }


  /**
   * Runs Spark's arm and keeps its answers, checks they are far from constant, then runs the
   * fused and the scalar arm once and checks every row of each against them.
   */
  private static void checkArms(String label, Rung r, Columns c, FusedDrive fused) {
    sparkDrive(r.spark(), c);
    System.arraycopy(c.sparkOut(), 0, c.expected(), 0, c.rows());
    var distinct = new HashSet<Long>();
    for (int i = 0; i < Math.min(CHUNK, c.rows()); i++) {
      distinct.add(c.expected()[i]);
    }
    require(distinct.size() >= MIN_DISTINCT,
        label + ": Spark's answers hold " + distinct.size() + " distinct values in a chunk");
    c.dst().fill((byte) 0x5A);
    fused.driveChecked();
    scalarDrive(r, c);
    for (int i = 0; i < c.rows(); i++) {
      long want = c.expected()[i];
      if (c.fusedAt(i) != want || c.scalarOut()[i] != want) {
        throw requirementFailed(label + ": row " + i + " (x " + c.xs()[i] + ", y " + c.ys()[i]
            + ") is " + c.fusedAt(i) + " fused and " + c.scalarOut()[i]
            + " scalar; Spark's is " + want);
      }
    }
  }

  // ---------------------------------------------------------------------------------------
  // The run
  // ---------------------------------------------------------------------------------------

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    var loader = new VarkaGeneratedClassLoader(getClass().getClassLoader());
    try (Arena arena = Arena.ofConfined()) {
      var rungs = new HashMap<Lane, List<Rung>>();
      for (Lane lane : LANES) {
        var list = new ArrayList<Rung>();
        for (int depth : DEPTHS) {
          list.add(rung(lane, depth, loader));
        }
        rungs.put(lane, list);
      }
      reportCaseIds();
      // The witness records from before any kernel runs, so it sees every compile of each.
      var witness = VarkaTierWitness.get();
      for (List<Rung> list : rungs.values()) {
        for (Rung r : list) {
          witness.watch(r.kernel().getClass().getName());
        }
      }
      if (dryRun()) {
        for (Lane lane : LANES) {
          Columns c = Columns.of(arena, lane, DRY_ROWS);
          for (Rung r : rungs.get(lane)) {
            String label = lane.label() + " depth " + r.depth();
            var fused = new FusedDrive(r, c);
            checkArms(label, r, c, fused);
            System.out.println("dry run, " + label + ": " + DRY_ROWS + " rows answer as Spark "
                + "does; " + r.fused().numLiterals() + " literals; bytes " + methodBytes(r.bytes())
                + "; Spark's projection " + r.spark().getClass().getSimpleName());
            // The settle the timed run makes, once, on the deepest rung of each lane, so the
            // witness and its note are exercised without a timing. A drive is a million rows,
            // as the timed run's is: one pass of the small buffer is too little work for C2 to
            // reach the loop inside the settle's wait.
            if (r.depth() == DEPTHS.get(DEPTHS.size() - 1)) {
              System.out.println("dry run, tier at " + label + ", fused: "
                  + settle(fused, numRows / DRY_ROWS));
            }
          }
        }
        return;
      }
      for (Lane lane : LANES) {
        section("chain depth past the cap: " + lane.label() + " lane", () -> {
          Columns c = Columns.of(arena, lane, BUFFER_ROWS);
          for (Rung r : rungs.get(lane)) {
            runRung(lane, r, c);
          }
        });
      }
    } finally {
      VarkaTierWitness.close();
      loader.release();
    }
  }

  /** One depth: the check, the three arms timed, and the byte and tier notes. */
  private void runRung(Lane lane, Rung r, Columns c) {
    String label = lane.label() + " depth " + r.depth();
    var fused = new FusedDrive(r, c);
    checkArms(label, r, c, fused);
    String[] tier = {"not run"};
    boolean[] settled = {false};
    int passes = numRows / c.rows();
    var benchmark = benchmark("depth " + r.depth() + ", " + passes + " passes over "
        + c.rows() + " rows in chunks of " + CHUNK, (long) passes * c.rows()).timed().build();
    benchmark.addTimerCase("fused, cap raised to " + RAISED_CAP, timer -> {
      if (!settled[0]) {
        settled[0] = true;
        tier[0] = settle(fused, passes);
        c.dst().fill((byte) 0x5A);
        fused.driveChecked();
        for (int i = 0; i < c.rows(); i++) {
          require(c.fusedAt(i) == c.expected()[i], label + ": compiled code is wrong at " + i);
        }
      }
      timer.startTiming();
      for (int p = 0; p < passes; p++) {
        fused.drive();
      }
      timer.stopTiming();
    });
    benchmark.addCase("Spark row path (generated UnsafeProjection)",
        iteration -> {
          for (int p = 0; p < passes; p++) {
            sparkDrive(r.spark(), c);
          }
        });
    benchmark.addCase("scalar loop", iteration -> {
      for (int p = 0; p < passes; p++) {
        scalarDrive(r, c);
      }
    });
    runCases(benchmark);
    note("bytes at " + label + ": " + methodBytes(r.bytes()));
    note("tier at " + label + ", fused: " + tier[0]);
  }

  /** Drives the fused arm until the tier witness reads every dense loop as C2 code. */
  private static String settle(FusedDrive fused, int passes) {
    Class<?> kernelClass = fused.kernel.getClass();
    var s = new Settle(kernelClass, VarkaEmitterTestSupport.loopLevels(kernelClass).keySet(),
        Map.of(), () -> {
          for (int p = 0; p < passes; p++) {
            fused.driveChecked();
          }
        },
        new Settle.Bounds(MIN_WARM_DRIVES, STEADY_FRACTION, WARM_WAIT_MILLIS), true);
    TierState t = Settle.drive(s);
    return (t.settled() ? "settled" : "not settled" + t.why()) + " after " + t.drives()
        + " drives, loops C2 " + t.loopsC2() + ", kernel code " + t.kernelLevels();
  }
}
