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

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.regex.Pattern;

import io.github.vecbricks.vfdlibm.VFdlibm;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorSpecies;

import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOptions;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterTestSupport;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterTestSupport.Settle;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterTestSupport.TierState;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFusedKernel;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaLoopEmitter;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMathPort;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTierWitness;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;

/**
 * What Varka's fdlibm ports are worth in speed (row 635 of {@code sql/varka/plans/m8/PLAN.md}):
 * {@code exp} and {@code expm1} three ways over the same inputs, all three bit-identical, so the
 * tables compare speed only.
 *
 * <ul>
 *   <li>{@code StrictMath, one call per row}: {@code StrictMath.exp} or {@code StrictMath.expm1}
 *       in a plain loop, the call Spark's row path makes for each row. It is the first case of
 *       every table, so the {@code Relative} column reads against it.</li>
 *   <li>{@code vfdlibm 0.2.0, one call per vector}: the library's public entry,
 *       {@code VFdlibm.exp} or {@code VFdlibm.expm1}, called directly over the same buffer. That
 *       entry computes the common lanes in vector form and finishes the rare ones (a non-finite,
 *       overflowing or underflowing lane, and for {@code exp} a subnormal result) with one
 *       {@code StrictMath} call each, so a vector holding a rare lane costs more. The library's
 *       blended arm, which computes every branch in every lane, is package-private and is not
 *       timed here: a reflective call in a timed loop would box every vector.</li>
 *   <li>{@code Varka kernel, the port inline}: {@link VarkaMathPort}'s expansion emitted as a
 *       kernel. The expansion is the library's blended arm written as IR nodes, so it costs the
 *       same whatever the lanes hold.</li>
 * </ul>
 *
 * <p><b>The kernel</b> is built as {@code VarkaMathPortSuite} builds it, without the SQL layer:
 * the port's root over one {@code DOUBLE} column, its constants in a {@link VarkaMathPort.Table},
 * emitted once by {@link VarkaLoopEmitter} under {@link VarkaEmitOptions#DEFAULTS}, the options a
 * query gets. A timed case calls only the kernel's {@code run}, over the buffer in chunks of
 * {@link #CHUNK} rows, Spark's batch size; the other two arms are one loop over the whole buffer.
 *
 * <p><b>Equal widths.</b> The kernel is emitted with no lane override, so it runs on the long
 * lane's preferred species, and the library is called on {@code DoubleVector.SPECIES_PREFERRED};
 * setup reads every species constant in the kernel's bytecode and fails unless each is as wide
 * in bits as the library's, so the 64-bit lanes match in count, and that count divides the
 * chunk. On the runners that is four doubles at 256 bits and two under
 * {@code -XX:MaxVectorSize=16}. Each section notes
 * the lane count, and the labels carry no width, so the 256-bit and 128-bit files diff by label.
 *
 * <p><b>The inputs</b>, three classes per function, each over {@link #BUFFER_ROWS} rows, 128 KB
 * in and 128 KB out, inside a core's L2, re-driven {@link #numRows} rows an iteration:
 * <ul>
 *   <li>{@code ordinary}: {@code x} uniform in {@code [-10, 10)}, the range of
 *       {@code VarkaMathBaselineBenchmark}'s {@code x}, where no lane is rare.</li>
 *   <li>{@code one row in 64 special}: the ordinary rows, each replaced by a special value
 *       with chance 1 in 64, so the special rows fall at scattered positions.</li>
 *   <li>{@code every row special}: special values only, in random order.</li>
 * </ul>
 * A special value is one of eight kinds, equally likely: a tiny {@code |x|} below
 * {@code 2^-54}, a huge one near {@code 1e300}, an overflow past {@code 709.78}, a negative past
 * {@code -56 ln 2} (to {@code -1000}), a negative whose {@code exp} is subnormal (from
 * {@code -708.4} to {@code -745.1}), NaN of several payloads, {@code +inf} and {@code -inf}.
 *
 * <p><b>Before timing</b>, each function's three arms run over each class and their raw bits are
 * compared row by row, with the kernel's validity checked all set; any difference fails the run.
 * Then the per-row and library loops are warmed over all three classes, so no table depends on
 * the order the classes run in. After a table, notes record the bytes each arm allocated in a
 * warmed pass, the least of several passes, and the kernel's tier state as
 * {@link VarkaTierWitness} read it after the settle. A vector arm that allocates is boxing its
 * vectors, and the note is how a reader of the file sees it. That is a reading, not a check that
 * fails the run, because it can be the code's own behaviour at a width rather than the harness's:
 * at two 64-bit lanes the JVM lowers no mask ({@code sql/varka/skills/the-jit.md}), and both
 * blended forms are made of masks. The file names the vfdlibm jar it ran against, and setup
 * fails unless its version is {@link #VFDLIBM_VERSION}.
 *
 * <p><b>The library's body is inlined</b>, as vfdlibm's own benchmarks run it and as Varka's
 * kernel holds its port: a timed run needs {@link #INLINE_FLAG} on the JVM and fails at setup
 * without it. The library's bodies are larger than C2 inlines by default, so without the flag
 * each call is a real call that passes its vectors as heap objects, and the vfdlibm row would
 * time that boxing. The flag names only vfdlibm's classes, so it changes no other arm.
 *
 * <p>A dry run ({@code -Dvarka.bench.dryRun=true}) emits both kernels, runs the bit check over
 * every class, settles the {@code exp} kernel once, and times nothing; it needs no flag.
 *
 * <p>To run this benchmark, on a GitHub runner (the results come back as an artifact):
 * <pre>
 *   gh workflow run benchmark.yml -f class=VarkaMathPortBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" \
 *     -f extra-java-options="-XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*"
 *   gh workflow run benchmark.yml -f class=VarkaMathPortBenchmark -f jdk=25 \
 *     -f expected-cpu="AMD EPYC 7763" -f extra-java-options="-XX:MaxVectorSize=16 \
 *     -XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*"
 * </pre>
 * and on the laptop:
 * <pre>
 *   build/sbt "project catalyst" \
 *     'set Test/javaOptions += "-XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*"' \
 *     "Test/runMain org.apache.spark.sql.VarkaMathPortBenchmark"
 * </pre>
 */
public class VarkaMathPortBenchmark extends VarkaBenchmarkBase {

  /** The vfdlibm release the labels name; setup checks the jar on the classpath is this one. */
  static final String VFDLIBM_VERSION = "0.2.0";

  /** The JVM flag that inlines vfdlibm's bodies into the library arm's loops. */
  static final String INLINE_FLAG = "-XX:CompileCommand=inline,io.github.vecbricks.vfdlibm.*::*";

  /** Spark's {@code COLUMN_BATCH_SIZE}: the chunk the kernel is driven in. */
  private static final int CHUNK = 4096;

  /** The buffer every arm re-drives: four chunks, 128 KB of doubles, inside a core's L2. */
  private static final int BUFFER_ROWS = 4 * CHUNK;

  /**
   * The rows of a timed iteration: 256 passes over the buffer, so the fastest arm's iteration is
   * several milliseconds and the results file's whole-millisecond Best Time column still has
   * digits to compare. Read at run time, never folded into a timed loop.
   */
  private static int numRows = 1 << 22;

  /** On average one row in this many is special in the sparse class. */
  private static final int SPARSE = 64;

  private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;

  private static final ByteOrder ORDER = ByteOrder.nativeOrder();

  private static final int MIN_WARM_DRIVES = 8;
  private static final double STEADY_FRACTION = 0.3;
  private static final long WARM_WAIT_MILLIS = 30_000;

  /** Passes of each per-row and library loop over each class before any table is timed. */
  private static final int WARM_ROUNDS = 200;

  /** The two functions; the per-lane and library arms switch on it outside their loops. */
  private enum Fn { EXP, EXPM1 }

  /** A class of inputs: its label, as a table's title holds it, and its rows. */
  private record Inputs(String label, double[] xs) {}

  /** A function's kernel, its long literals and its class bytes. */
  private record Port(Fn fn, VarkaFusedKernel kernel, long[] lits, byte[] bytes) {}

  private int nextId = 0;

  // ---------------------------------------------------------------------------------------
  // The inputs
  // ---------------------------------------------------------------------------------------

  /** One special value: a kind drawn evenly from the eight the class Javadoc lists. */
  private static double special(SplittableRandom rnd) {
    double sign = rnd.nextBoolean() ? 1.0 : -1.0;
    return switch (rnd.nextInt(8)) {
      case 0 -> sign * Math.scalb(1.0 + rnd.nextDouble(), -55 - rnd.nextInt(20));
      case 1 -> sign * 1e300 * (1.0 + rnd.nextDouble());
      case 2 -> rnd.nextDouble(709.79, 1000.0);
      case 3 -> rnd.nextDouble(-1000.0, -38.9);
      case 4 -> rnd.nextDouble(-745.1, -708.4);
      case 5 -> Double.longBitsToDouble(0x7ff8000000000000L | rnd.nextLong(1L << 51)
          | (rnd.nextBoolean() ? Long.MIN_VALUE : 0L));
      case 6 -> Double.POSITIVE_INFINITY;
      default -> Double.NEGATIVE_INFINITY;
    };
  }

  /** The three classes, the same for both functions. */
  private static List<Inputs> inputs() {
    var rnd = new SplittableRandom(0x633L);
    double[] ordinary = new double[BUFFER_ROWS];
    double[] sparse = new double[BUFFER_ROWS];
    double[] dense = new double[BUFFER_ROWS];
    int specials = 0;
    for (int i = 0; i < BUFFER_ROWS; i++) {
      ordinary[i] = rnd.nextDouble(-10.0, 10.0);
      boolean pick = rnd.nextInt(SPARSE) == 0;
      sparse[i] = pick ? special(rnd) : ordinary[i];
      specials += pick ? 1 : 0;
      dense[i] = special(rnd);
    }
    require(specials > BUFFER_ROWS / SPARSE / 2,
        "the sparse class drew only " + specials + " special rows");
    return List.of(new Inputs("ordinary x in [-10, 10)", ordinary),
        new Inputs("one row in " + SPARSE + " special", sparse),
        new Inputs("every row special", dense));
  }

  // ---------------------------------------------------------------------------------------
  // The three arms
  // ---------------------------------------------------------------------------------------

  /** The buffers of one class: the input and each arm's output, all off the heap. */
  private record Buffers(MemorySegment in, MemorySegment strict, MemorySegment library,
      MemorySegment varka, MemorySegment varkaValidity) {

    static Buffers of(Arena arena) {
      long bytes = BUFFER_ROWS * 8L;
      // A word-writing kernel owns the whole last word of its validity.
      return new Buffers(arena.allocate(bytes, 64), arena.allocate(bytes, 64),
          arena.allocate(bytes, 64), arena.allocate(bytes, 64),
          arena.allocate(BUFFER_ROWS / 8 + 8, 64));
    }

    void fill(double[] xs) {
      for (int i = 0; i < BUFFER_ROWS; i++) {
        in.set(ValueLayout.JAVA_DOUBLE, i * 8L, xs[i]);
      }
    }
  }

  /*
   * One loop method per function in each arm, each with a direct call, so each loop has its own
   * profile and C2 inlines the call into it; only the switch above the loops is shared by the two
   * functions, one branch a pass. A call left out of line would box the library's vectors, as
   * the allocation notes would show.
   */

  private static void strictExp(MemorySegment in, MemorySegment out) {
    for (int i = 0; i < BUFFER_ROWS; i++) {
      long at = i * 8L;
      out.set(ValueLayout.JAVA_DOUBLE, at, StrictMath.exp(in.get(ValueLayout.JAVA_DOUBLE, at)));
    }
  }

  private static void strictExpm1(MemorySegment in, MemorySegment out) {
    for (int i = 0; i < BUFFER_ROWS; i++) {
      long at = i * 8L;
      out.set(ValueLayout.JAVA_DOUBLE, at, StrictMath.expm1(in.get(ValueLayout.JAVA_DOUBLE, at)));
    }
  }

  private static void libraryExp(MemorySegment in, MemorySegment out) {
    for (int i = 0; i < BUFFER_ROWS; i += SPECIES.length()) {
      long at = i * 8L;
      VFdlibm.exp(DoubleVector.fromMemorySegment(SPECIES, in, at, ORDER))
          .intoMemorySegment(out, at, ORDER);
    }
  }

  private static void libraryExpm1(MemorySegment in, MemorySegment out) {
    for (int i = 0; i < BUFFER_ROWS; i += SPECIES.length()) {
      long at = i * 8L;
      VFdlibm.expm1(DoubleVector.fromMemorySegment(SPECIES, in, at, ORDER))
          .intoMemorySegment(out, at, ORDER);
    }
  }

  private static void strict(Fn fn, Buffers b) {
    switch (fn) {
      case EXP -> strictExp(b.in(), b.strict());
      case EXPM1 -> strictExpm1(b.in(), b.strict());
    }
  }

  private static void library(Fn fn, Buffers b) {
    switch (fn) {
      case EXP -> libraryExp(b.in(), b.library());
      case EXPM1 -> libraryExpm1(b.in(), b.library());
    }
  }

  /** The kernel over the buffer in chunks of {@link #CHUNK}; the status of every call ORed. */
  private static final class KernelDrive {
    private final VarkaFusedKernel kernel;
    private final long[] lits;
    private final Buffers b;
    private final long[] src = new long[1];
    private final long[] srcValidity = new long[1];
    private final int[] nulls = new int[1];
    private final long[] dst = new long[1];
    private final long[] dstValidity = new long[1];
    private final int[] intLits = new int[0];
    private final long scratch;

    KernelDrive(Port port, Buffers b) {
      this.kernel = port.kernel();
      this.lits = port.lits();
      this.b = b;
      this.scratch = VarkaEmitterTestSupport.scratch(kernel, CHUNK);
    }

    int drive() {
      int status = 0;
      for (int off = 0; off < BUFFER_ROWS; off += CHUNK) {
        src[0] = b.in().address() + off * 8L;
        dst[0] = b.varka().address() + off * 8L;
        dstValidity[0] = b.varkaValidity().address() + off / 8;
        status |= kernel.run(src, srcValidity, nulls, dst, dstValidity, intLits, lits, CHUNK,
            scratch);
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

  // ---------------------------------------------------------------------------------------
  // Setup and the checks
  // ---------------------------------------------------------------------------------------

  /** Emits and loads {@code fn}'s port at the defaults. */
  private Port port(Fn fn, VarkaGeneratedClassLoader loader) {
    var table = new VarkaMathPort.Table();
    var port = new VarkaMathPort(table);
    var x = new ColumnRef(0, LaneType.DOUBLE);
    VarkaVectorIR root = switch (fn) {
      case EXP -> port.exp(x);
      case EXPM1 -> port.expm1(x);
    };
    long[] lits = table.values();
    int id = nextId++;
    claimCaseId(id);
    String name = "org.apache.spark.sql.varka.execution.VarkaMathPortBench" + id;
    byte[] bytes = VarkaLoopEmitter.emit(name, List.of(root), 1, lits.length, null, null,
        VarkaEmitOptions.DEFAULTS);
    return new Port(fn, defineKernel(loader, name, bytes), lits, bytes);
  }

  /**
   * The lane count both vector arms run at, or a failure when they would differ: every species
   * constant the kernel's bytecode reads ({@code LongVector.SPECIES_256} and
   * {@code DoubleVector.SPECIES_256}, or {@code SPECIES_PREFERRED} when no width is baked, and
   * the int species {@code k} passes through) must be as wide in bits as the library's species.
   */
  private static int lanes(Port port) {
    var read = new TreeMap<String, Integer>();
    for (MethodModel m : ClassFile.of().parse(port.bytes()).methods()) {
      for (CodeElement e : m.code().map(c -> c.elementList()).orElse(List.of())) {
        if (e instanceof FieldInstruction f
            && f.owner().asInternalName().startsWith("jdk/incubator/vector/")
            && f.name().stringValue().startsWith("SPECIES_")) {
          String owner = f.owner().asInternalName().replace('/', '.');
          read.put(owner + "." + f.name().stringValue(), speciesBits(owner, f.name()
              .stringValue()));
        }
      }
    }
    require(!read.isEmpty(), "the " + port.fn() + " kernel reads no species constant");
    require(read.values().stream().allMatch(n -> n == SPECIES.vectorBitSize()), "the "
        + port.fn() + " kernel's species, by bits, " + read + " and the library's "
        + SPECIES.vectorBitSize() + " bits differ");
    require(CHUNK % SPECIES.length() == 0, SPECIES.length() + " lanes do not divide " + CHUNK);
    return SPECIES.length();
  }

  private static int speciesBits(String owner, String field) {
    try {
      return ((VectorSpecies<?>) Class.forName(owner).getField(field).get(null)).vectorBitSize();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot read " + owner + "." + field, e);
    }
  }

  /** The vfdlibm jar's file name, checked to be {@link #VFDLIBM_VERSION}'s. */
  private static String vfdlibmJar() {
    var source = VFdlibm.class.getProtectionDomain().getCodeSource();
    String path = source == null ? "" : source.getLocation().getPath();
    String jar = path.substring(path.lastIndexOf('/') + 1);
    require(Pattern.matches("vfdlibm-" + Pattern.quote(VFDLIBM_VERSION) + "\\.jar", jar),
        "VFdlibm was loaded from \"" + path + "\", not a vfdlibm-" + VFDLIBM_VERSION + ".jar");
    return jar;
  }

  /** Runs the three arms once over {@code xs} and fails on any row whose bits differ. */
  private static void checkBits(Port port, Buffers b, KernelDrive drive, Inputs inputs) {
    b.fill(inputs.xs());
    b.varka().fill((byte) 0x5A);
    b.varkaValidity().fill((byte) 0);
    strict(port.fn(), b);
    library(port.fn(), b);
    drive.driveChecked();
    String fn = port.fn().name().toLowerCase(Locale.ROOT);
    for (int i = 0; i < BUFFER_ROWS; i++) {
      long at = i * 8L;
      long want = b.strict().get(ValueLayout.JAVA_LONG, at);
      long lib = b.library().get(ValueLayout.JAVA_LONG, at);
      long varka = b.varka().get(ValueLayout.JAVA_LONG, at);
      boolean valid = (b.varkaValidity().get(ValueLayout.JAVA_BYTE, i / 8) & (1 << (i % 8))) != 0;
      if (lib != want || varka != want || !valid) {
        throw requirementFailed(String.format(Locale.ROOT,
            "%s, %s, row %d: %s(%s) is 0x%016x by StrictMath, 0x%016x by vfdlibm and 0x%016x "
                + "(valid %b) by the kernel", fn, inputs.label(), i, fn, inputs.xs()[i], want,
            lib, varka, valid));
      }
    }
  }

  /**
   * The bytes each arm allocates in a pass over {@code in}, the least of
   * {@link #ALLOCATION_PASSES}, as a line for the results file.
   */
  private static String allocation(Port port, Buffers b, KernelDrive drive, Inputs in) {
    b.fill(in.xs());
    Fn fn = port.fn();
    return "allocated in one pass, " + fn.name().toLowerCase(Locale.ROOT) + ", " + in.label()
        + ": StrictMath " + allocated(() -> strict(fn, b)) + " B, vfdlibm "
        + allocated(() -> library(fn, b)) + " B, Varka kernel "
        + allocated(drive::driveChecked) + " B";
  }

  /** The passes an allocation reading takes the least of. */
  private static final int ALLOCATION_PASSES = 8;

  /** The fewest bytes the current thread allocates in one run of {@code body}. */
  private static long allocated(Runnable body) {
    var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    long least = Long.MAX_VALUE;
    // The least of several passes: a pass that runs while C2 swaps in new code can allocate
    // once, on the thread, where the steady loop allocates nothing.
    for (int pass = 0; pass < ALLOCATION_PASSES; pass++) {
      long before = mx.getCurrentThreadAllocatedBytes();
      body.run();
      least = Math.min(least, mx.getCurrentThreadAllocatedBytes() - before);
    }
    return least;
  }

  // ---------------------------------------------------------------------------------------
  // The run
  // ---------------------------------------------------------------------------------------

  @Override
  public void runBenchmarkSuite(String[] mainArgs) {
    String jar = vfdlibmJar();
    require(dryRun() || ManagementFactory.getRuntimeMXBean().getInputArguments()
        .contains(INLINE_FLAG), "a timed run needs " + INLINE_FLAG + " on the JVM, or the "
        + "vfdlibm row times its vectors boxed across a call");
    var loader = new VarkaGeneratedClassLoader(getClass().getClassLoader());
    try (Arena arena = Arena.ofConfined()) {
      List<Port> ports = List.of(port(Fn.EXP, loader), port(Fn.EXPM1, loader));
      int lanes = lanes(ports.get(0));
      require(lanes(ports.get(1)) == lanes, "the two kernels run at different lane counts");
      reportCaseIds();
      // The witness records from before any kernel runs, so it sees every compile of each.
      var witness = VarkaTierWitness.get();
      for (Port p : ports) {
        witness.watch(p.kernel().getClass().getName());
      }
      List<Inputs> classes = inputs();
      Buffers b = Buffers.of(arena);
      if (dryRun()) {
        for (Port p : ports) {
          var drive = new KernelDrive(p, b);
          for (Inputs in : classes) {
            checkBits(p, b, drive, in);
            System.out.println("dry run, " + title(p.fn()) + ", " + in.label() + ": "
                + BUFFER_ROWS + " rows give the same bits by all three arms");
          }
        }
        var drive = new KernelDrive(ports.get(0), b);
        System.out.println("dry run, " + lanes + " lanes, " + jar + "; tier of the exp kernel: "
            + settle(drive, numRows / BUFFER_ROWS));
        return;
      }
      // Every per-row and library loop sees every class before any is timed, so a table does
      // not depend on the order the classes run in: a loop compiled on the ordinary class alone
      // would carry the rare lanes' paths as traps, and the first special lane would deoptimize
      // it. The kernel has no such path; it blends every branch.
      for (Port p : ports) {
        for (int round = 0; round < WARM_ROUNDS; round++) {
          for (Inputs in : classes) {
            b.fill(in.xs());
            strict(p.fn(), b);
            library(p.fn(), b);
          }
        }
      }
      for (Port p : ports) {
        section(title(p.fn()), () -> {
          note(lanes + " doubles a vector (" + lanes * 64 + "-bit) for the Varka kernel and "
              + "vfdlibm alike; vfdlibm from " + jar + ", its bodies inlined by " + INLINE_FLAG);
          for (Inputs in : classes) {
            runClass(p, b, in);
          }
        });
      }
    } finally {
      VarkaTierWitness.close();
      loader.release();
    }
  }

  private static String title(Fn fn) {
    String name = fn.name().toLowerCase(Locale.ROOT);
    return name + "(x): StrictMath per row, vfdlibm " + VFDLIBM_VERSION + " and Varka's port";
  }

  /** One class of one function: the bit check, the three arms timed, and the notes. */
  private void runClass(Port port, Buffers b, Inputs in) {
    Fn fn = port.fn();
    String name = fn.name().toLowerCase(Locale.ROOT);
    var drive = new KernelDrive(port, b);
    checkBits(port, b, drive, in);
    String[] tier = {"not run"};
    boolean[] settled = {false};
    int passes = numRows / BUFFER_ROWS;
    var benchmark = benchmark(name + ", " + in.label() + ", " + passes + " passes over "
        + BUFFER_ROWS + " rows", (long) passes * BUFFER_ROWS).timed().build();
    benchmark.addCase("StrictMath." + name + ", one call per row", iteration -> {
      for (int p = 0; p < passes; p++) {
        strict(fn, b);
      }
    });
    benchmark.addCase("vfdlibm " + VFDLIBM_VERSION + " VFdlibm." + name + ", one call per vector",
        iteration -> {
          for (int p = 0; p < passes; p++) {
            library(fn, b);
          }
        });
    benchmark.addTimerCase("Varka kernel, the port inline", timer -> {
      if (!settled[0]) {
        settled[0] = true;
        tier[0] = settle(drive, passes);
        // The bits are checked again on the settled code: the first check ran before C2 had it.
        checkBits(port, b, drive, in);
      }
      timer.startTiming();
      for (int p = 0; p < passes; p++) {
        drive.drive();
      }
      timer.stopTiming();
    });
    runCases(benchmark);
    note(allocation(port, b, drive, in));
    note("tier of the " + name + " kernel, " + in.label() + ": " + tier[0]);
  }

  /** Drives the kernel until the tier witness reads every dense loop as C2 code. */
  private static String settle(KernelDrive drive, int passes) {
    Class<?> kernelClass = drive.kernel.getClass();
    var s = new Settle(kernelClass, VarkaEmitterTestSupport.loopLevels(kernelClass).keySet(),
        Map.of(), () -> {
          for (int p = 0; p < passes; p++) {
            drive.driveChecked();
          }
        },
        new Settle.Bounds(MIN_WARM_DRIVES, STEADY_FRACTION, WARM_WAIT_MILLIS), true);
    TierState t = Settle.drive(s);
    return (t.settled() ? "settled" : "not settled" + t.why()) + " after " + t.drives()
        + " drives, loops C2 " + t.loopsC2() + ", kernel code " + t.kernelLevels();
  }
}
