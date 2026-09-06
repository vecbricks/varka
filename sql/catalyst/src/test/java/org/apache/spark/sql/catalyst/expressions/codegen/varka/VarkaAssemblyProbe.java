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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.util.List;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorSpecies;

import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader;
import org.apache.spark.sql.varka.vector.ChronoVectorOps;
import org.apache.spark.sql.varka.vector.DateVectorOps;

/**
 * The child process behind {@code VarkaAssemblySuite} (task 31). It is launched in a forked JVM
 * carrying {@code -XX:+UnlockDiagnosticVMOptions -XX:CompileCommand=print,...}, runs one named
 * case hot enough for C2 to compile the case's method, and exits; the disassembly HotSpot prints
 * on the way is what the parent reads.
 *
 * <p><b>Why the driver calls a method rather than looping inside one.</b> A single call with a
 * long loop reaches C2 through on-stack replacement, and an OSR nmethod is not the compilation
 * that runs in production - {@code SKILLS.md}'s bimodality investigation had to compare standard
 * nmethods for the same reason. So every case is a short method called {@link #ROUNDS} times,
 * which produces a standard compilation the parent can find by the absence of {@code %} in
 * HotSpot's {@code Compiled method} line.
 *
 * <p><b>Every case prints its preferred vector width</b> ({@link #PREFERRED_BITS_PREFIX}) before
 * doing any work. The expected register class - {@code zmm}, {@code ymm} or {@code xmm} - is a
 * property of the host and of the flags this child was given, not of the machine the suite was
 * written on, so the parent derives it from this line rather than assuming.
 */
public final class VarkaAssemblyProbe {

  /** The line the parent parses the host's preferred vector width out of. */
  public static final String PREFERRED_BITS_PREFIX = "VARKA_PROBE_PREFERRED_BITS=";

  /**
   * The line carrying the case's steady-state allocation rate (task 55): heap bytes the probe
   * thread allocated per call of the case's method, measured over {@link #MEASURE_ROUNDS} calls
   * made after the {@link #ROUNDS} that got it compiled. A boxed vector costs a fresh object per
   * lane group and shows up here as bytes that scale with the rows per call; a per-call setup
   * object that C2 failed to scalar-replace shows up as a small constant.
   */
  public static final String ALLOC_BYTES_PER_CALL_PREFIX = "VARKA_PROBE_ALLOC_BYTES_PER_CALL=";

  /** The rows one call of the case's method processes, so the parent can normalise the above. */
  public static final String ROWS_PER_CALL_PREFIX = "VARKA_PROBE_ROWS_PER_CALL=";

  /** The line the parent uses to confirm the case ran to completion rather than dying early. */
  public static final String DONE_PREFIX = "VARKA_PROBE_DONE=";

  private static final VectorSpecies<Integer> SPECIES = IntVector.SPECIES_PREFERRED;

  /** Elements per call. Small enough that a call is cheap, large enough to be worth vectorizing. */
  private static final int LENGTH = 1024;

  /**
   * Calls per case. Well past {@code Tier4InvocationThreshold} (15000 by default) with room for a
   * loaded machine, and cheap: the whole run is a few hundred million integer ops.
   */
  private static final int ROUNDS = 200_000;

  /** Calls the allocation rate is averaged over, after the case's method has been compiled. */
  private static final int MEASURE_ROUNDS = 1000;

  private VarkaAssemblyProbe() {
  }

  /** One case, prepared: everything it needs is allocated, and {@link #run} makes {@code rounds}
   *  calls of the method under test and nothing else that allocates. */
  private interface Hot extends AutoCloseable {
    int run(int rounds);

    int rowsPerCall();

    @Override
    default void close() {
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      System.err.println("usage: VarkaAssemblyProbe <case>");
      System.exit(2);
    }
    String name = args[0];
    System.out.println(PREFERRED_BITS_PREFIX + SPECIES.vectorBitSize());

    int sink = 0;
    try (Hot hot = prepare(name)) {
      sink += hot.run(ROUNDS);
      // The steady state: the method under test is compiled, and from here on the only
      // allocation on this thread is whatever its compiled body does.
      com.sun.management.ThreadMXBean threads =
          (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
      long tid = Thread.currentThread().threadId();
      long before = threads.getThreadAllocatedBytes(tid);
      sink += hot.run(MEASURE_ROUNDS);
      long after = threads.getThreadAllocatedBytes(tid);
      System.out.println(ALLOC_BYTES_PER_CALL_PREFIX + (after - before) / MEASURE_ROUNDS);
      System.out.println(ROWS_PER_CALL_PREFIX + hot.rowsPerCall());
    }
    System.out.println(DONE_PREFIX + name + " sink=" + sink);
  }

  private static Hot prepare(String name) {
    int[] a = new int[LENGTH];
    int[] b = new int[LENGTH];
    int[] o = new int[LENGTH];
    // Indexes into TABLE for the gather cases: every value in range, every lane group varied.
    int[] idx = new int[LENGTH];
    for (int i = 0; i < LENGTH; i++) {
      a[i] = i;
      b[i] = LENGTH - i;
      idx[i] = (i * 7) & (TABLE.length - 1);
    }
    return switch (name) {
      case "scalarChain" -> simple(rounds -> {
        int sink = 0;
        for (int r = 0; r < rounds; r++) {
          sink += scalarChain(b);
        }
        return sink;
      });
      case "vectorAdd" -> simple(rounds -> {
        int sink = 0;
        for (int r = 0; r < rounds; r++) {
          sink += vectorAdd(a, b, o);
        }
        return sink;
      });
      case "gatherLookup" -> simple(rounds -> {
        int sink = 0;
        for (int r = 0; r < rounds; r++) {
          sink += gatherLookup(idx, o);
        }
        return sink;
      });
      case "gatherLookupPolluted" -> simple(rounds -> {
        int sink = 0;
        for (int r = 0; r < rounds; r++) {
          sink += gatherLookup(idx, o);
          sink += gatherLookupOtherSpecies(idx, o);
        }
        return sink;
      });
      case "chronoFourFields" -> chronoFourFields();
      case "dateAddDays" -> dateAddDays();
      case "emittedYear" -> emittedProjection(
          "emittedYear", List.of(new VarkaVectorIR.Year(COLUMN_0)), 1, 0);
      case "emittedDayOfWeek" -> emittedProjection(
          "emittedDayOfWeek", List.of(new VarkaVectorIR.DayOfWeek(COLUMN_0)), 1, 0);
      // Task 46's two arms, on the body the change touches: the masked year loop, emitted
      // with the width-named validity helpers and with the general pair. Same IR, same data,
      // one option apart - so a difference in the printed instructions is the change and
      // nothing else.
      case "emittedYearMaskedByWidth" -> emittedProjection(
          "emittedYearMaskedByWidth", List.of(new VarkaVectorIR.Year(COLUMN_0)), 1, 0,
          VarkaEmitOptions.DEFAULTS, true);
      case "emittedYearMaskedGeneral" -> emittedProjection(
          "emittedYearMaskedGeneral", List.of(new VarkaVectorIR.Year(COLUMN_0)), 1, 0,
          VarkaEmitOptions.DEFAULTS.withValidityByWidth(false), true);
      case "emittedCompare" -> emittedProjection(
          "emittedCompare",
          List.of(new VarkaVectorIR.IfElse(
              new VarkaVectorIR.Compare(
                  VarkaVectorIR.CompareOp.GT, COLUMN_0, new VarkaVectorIR.LiteralSlot(0)),
              COLUMN_0,
              new VarkaVectorIR.LiteralSlot(0))),
          1, 1);
      default -> {
        System.err.println("unknown case: " + name);
        System.exit(2);
        yield null;
      }
    };
  }

  /** A case over the on-heap arrays: nothing to release, {@link #LENGTH} rows per call. */
  private static Hot simple(java.util.function.IntUnaryOperator body) {
    return new Hot() {
      @Override
      public int run(int rounds) {
        return body.applyAsInt(rounds);
      }

      @Override
      public int rowsPerCall() {
        return LENGTH;
      }
    };
  }

  /**
   * The negative half of the self-test: a body that cannot vectorize, whatever C2 does.
   *
   * <p>The recurrence is the point. {@code acc} is read and written every iteration and the
   * update is not one of the reduction forms SuperWord recognises, so no auto-vectorizer can
   * turn this into packed arithmetic. That is deliberately a *structural* guarantee rather than
   * {@code -XX:-UseSuperWord}: a flag would hide it if this loop turned out to be vectorizable
   * after all, and the whole value of this case is that the detector reports "no packed add" on
   * a body that genuinely has none.
   */
  public static int scalarChain(int[] b) {
    int acc = 1;
    for (int i = 0; i < b.length; i++) {
      acc = acc * 31 + b[i];
    }
    return acc;
  }

  /**
   * The positive half: an explicit {@link IntVector} loop, which is what the emitter produces.
   * Auto-vectorization of a plain {@code o[i] = a[i] + b[i]} would also do, but this exercises
   * the Vector API intrinsic path the real kernels depend on, so a failure here means the same
   * thing a failure over a Varka kernel would.
   */
  public static int vectorAdd(int[] a, int[] b, int[] o) {
    int i = 0;
    int bound = SPECIES.loopBound(a.length);
    for (; i < bound; i += SPECIES.length()) {
      IntVector va = IntVector.fromArray(SPECIES, a, i);
      IntVector vb = IntVector.fromArray(SPECIES, b, i);
      va.add(vb).intoArray(o, i);
    }
    for (; i < a.length; i++) {
      o[i] = a[i] + b[i];
    }
    return o[0];
  }

  // --- The allocation self-test (task 55) --------------------------------------------------------

  /**
   * A second {@code IntVector} species, different from the preferred one at every width the suite
   * runs at: 128 bits where the preferred species is wider, 64 bits under
   * {@code -XX:MaxVectorSize=16}, where the preferred species is itself 128 bits.
   */
  private static final VectorSpecies<Integer> OTHER_SPECIES =
      SPECIES.vectorBitSize() > 128 ? IntVector.SPECIES_128 : IntVector.SPECIES_64;

  /** A constant table, indexed by the low six bits of each lane. */
  private static final int[] TABLE = new int[64];

  static {
    for (int i = 0; i < TABLE.length; i++) {
      TABLE[i] = 7 * i + 1;
    }
  }

  /**
   * The shape the allocation assertion is calibrated on: an index-map gather out of a small
   * table, one {@code vpgatherdd} and its index check when it compiles cleanly. Its index vector
   * flows through the shared {@code IntVector} templates, and those templates are what a second
   * species turns bimorphic.
   *
   * <p>The plain {@link #vectorAdd} would not do for this, and neither would a {@code selectFrom}
   * lookup: measured while building this task (`PLAN_TASK_55.md` 3), a bare add stays clean in
   * a polluted JVM, a {@code selectFrom} lookup boxes only if the second species ran hot
   * <em>first</em> and stays clean when the two are interleaved, and the gather boxes under both
   * orders at both widths. The positive case has to be a shape that boxes whenever the templates
   * are polluted, or the self-test proves nothing.
   */
  public static int gatherLookup(int[] idx, int[] o) {
    int i = 0;
    int bound = SPECIES.loopBound(idx.length);
    for (; i < bound; i += SPECIES.length()) {
      IntVector.fromArray(SPECIES, TABLE, 0, idx, i).intoArray(o, i);
    }
    return o[0];
  }

  /**
   * The same loop over {@link #OTHER_SPECIES}. Never asserted on itself: it exists so that the
   * {@code gatherLookupPolluted} case has pushed a second species through the same templates by
   * the time C2 compiles {@link #gatherLookup}, which is the one condition under which that
   * method's body acquires a heap allocation per lane group.
   */
  public static int gatherLookupOtherSpecies(int[] idx, int[] o) {
    int i = 0;
    int bound = OTHER_SPECIES.loopBound(idx.length);
    for (; i < bound; i += OTHER_SPECIES.length()) {
      IntVector.fromArray(OTHER_SPECIES, TABLE, 0, idx, i).intoArray(o, i);
    }
    return o[0];
  }

  // --- The kernels and the emitted loops --------------------------------------------------------

  private static final VarkaVectorIR COLUMN_0 = new VarkaVectorIR.ColumnRef(0);

  /**
   * The prefix every emitted case's class name carries. Production names a generated class
   * {@code VarkaFusedProjection_<shape hash>} ({@code VarkaShapeCacheImpl.classNameFor}), so the
   * probe's classes are named the same way with a readable suffix in place of the hash: the
   * suite's {@code CompileCommand} pattern then wildcards exactly where it would in production,
   * rather than exercising a pattern shape that never occurs.
   */
  private static final String EMITTED_PREFIX =
      "org.apache.spark.sql.varka.execution.VarkaFusedProjection_";

  /** Rows per call. A multiple of every supported lane count, so the dense loop does the work
   *  and the epilogue handles nothing. */
  private static final int ROWS = 1024;

  /** Calls for the off-heap cases. Lower than {@link #ROUNDS} because each call does 1024 rows
   *  of real work rather than one add, and still far past the tier-4 threshold. */
  private static final int KERNEL_ROUNDS = 50_000;

  /** Days around 2020, so the calendar lowering runs on realistic values. */
  private static void fillDays(MemorySegment data, int rows) {
    for (int i = 0; i < rows; i++) {
      data.set(ValueLayout.JAVA_INT, i * 4L, 18000 + i);
    }
  }

  /** A bitmap with every seventh row null - the parity harness's own mixed-null pattern. */
  private static MemorySegment everySeventhNull(Arena arena, int rows) {
    MemorySegment validity = arena.allocate((rows + 7) / 8L, 8);
    validity.fill((byte) 0);
    for (int i = 0; i < rows; i++) {
      if (i % 7 != 0) {
        long off = i / 8L;
        byte old = validity.get(java.lang.foreign.ValueLayout.JAVA_BYTE, off);
        validity.set(java.lang.foreign.ValueLayout.JAVA_BYTE, off, (byte) (old | (1 << (i % 8))));
      }
    }
    return validity;
  }

  private static MemorySegment allValid(Arena arena, int rows) {
    MemorySegment validity = arena.allocate((rows + 7) / 8L, 8);
    validity.fill((byte) 0xFF);
    return validity;
  }

  /** A case over off-heap buffers in an arena the {@link Hot} owns and closes. */
  private abstract static class OffHeap implements Hot {
    final Arena arena = Arena.ofConfined();

    @Override
    public int rowsPerCall() {
      return ROWS;
    }

    @Override
    public void close() {
      arena.close();
    }
  }

  /** {@code ChronoVectorOps.vectorFourFields} - the hand-written reference the emitted calendar
   *  lowering is measured against, and the first thing this suite should be able to vouch for. */
  private static Hot chronoFourFields() {
    return new OffHeap() {
      final MemorySegment src = arena.allocate(ROWS * 4L, 64);
      final MemorySegment srcValidity = allValid(arena, ROWS);
      final long[] dstData = new long[4];
      final long[] dstValidity = new long[4];

      {
        fillDays(src, ROWS);
        for (int f = 0; f < 4; f++) {
          dstData[f] = arena.allocate(ROWS * 4L, 64).address();
          dstValidity[f] = arena.allocate((ROWS + 7) / 8L, 8).address();
        }
      }

      @Override
      public int run(int rounds) {
        int status = 0;
        for (int r = 0; r < rounds; r++) {
          status |= ChronoVectorOps.vectorFourFields(
              src.address(), srcValidity.address(), 0, dstData, dstValidity, ROWS);
        }
        return status;
      }
    };
  }

  /** {@code DateVectorOps.vectorAddDays} - the simplest kernel, and the one whose body should be
   *  packed loads, one packed add and packed stores with nothing else in it. */
  private static Hot dateAddDays() {
    return new OffHeap() {
      final MemorySegment src = arena.allocate(ROWS * 4L, 64);
      final MemorySegment srcValidity = allValid(arena, ROWS);
      final MemorySegment dst = arena.allocate(ROWS * 4L, 64);
      final MemorySegment dstValidity = arena.allocate((ROWS + 7) / 8L, 8);

      {
        fillDays(src, ROWS);
      }

      @Override
      public int run(int rounds) {
        for (int r = 0; r < rounds; r++) {
          DateVectorOps.vectorAddDays(src.address(), srcValidity.address(), 0,
              dst.address(), dstValidity.address(), ROWS, 7);
        }
        return dst.get(ValueLayout.JAVA_INT, 0);
      }
    };
  }

  /**
   * Emits one projection, loads it, and prepares to run it hot.
   *
   * <p>The driver calls {@code loopDense0} once per {@code run}, so the loop method's own
   * invocation counter trips at the same rate the kernel's does and HotSpot compiles it
   * standalone - which is what lets the assertion name a loop method rather than the whole
   * kernel, and is why {@code rounds} calls rather than one long loop. The argument arrays are
   * built once: the allocation measurement must see the kernel's own allocations and nothing the
   * driver did.
   */
  private static Hot emittedProjection(
      String suffix, List<VarkaVectorIR> outputs, int numInputs, int numLiterals) {
    return emittedProjection(suffix, outputs, numInputs, numLiterals,
        VarkaEmitOptions.DEFAULTS, false);
  }

  /**
   * As above, for a named emit variant and, optionally, the <i>masked</i> body.
   *
   * <p>Task 46 needs both: its A/B is between two emit options, and the bodies it changes are
   * the masked ones - a dense value output has had no per-lane-group validity call since task
   * 45. `masked` hands the driver a non-zero null count and a bitmap with real zeros in it, so
   * the dispatcher takes the masked path and the method the parent prints is the one that does
   * the work.
   */
  private static Hot emittedProjection(
      String suffix, List<VarkaVectorIR> outputs, int numInputs, int numLiterals,
      VarkaEmitOptions options, boolean masked) {
    String className = EMITTED_PREFIX + suffix;
    byte[] bytes = VarkaLoopEmitter.emit(
        className, outputs, numInputs, numLiterals, null, null, options);
    VarkaFusedKernel loaded;
    try {
      VarkaGeneratedClassLoader loader =
          new VarkaGeneratedClassLoader(VarkaAssemblyProbe.class.getClassLoader());
      loader.defineGeneratedClass(className, bytes);
      loaded = (VarkaFusedKernel) loader.loadClass(className).getConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("could not load the emitted kernel " + className, e);
    }
    return new OffHeap() {
      final VarkaFusedKernel kernel = loaded;
      final MemorySegment src = arena.allocate(ROWS * 4L, 64);
      final long[] srcData = {src.address()};
      final long[] srcValidity = {
          (masked ? everySeventhNull(arena, ROWS) : allValid(arena, ROWS)).address()};
      final int[] srcNullCount = {masked ? (ROWS + 6) / 7 : 0};
      final long[] dstData = new long[outputs.size()];
      final long[] dstValidity = new long[outputs.size()];
      final int[] scalarArgs = new int[numLiterals];

      {
        fillDays(src, ROWS);
        for (int i = 0; i < outputs.size(); i++) {
          dstData[i] = arena.allocate(ROWS * 4L, 64).address();
          dstValidity[i] = arena.allocate((ROWS + 7) / 8L, 8).address();
        }
        for (int i = 0; i < numLiterals; i++) {
          scalarArgs[i] = 18500;
        }
      }

      @Override
      public int run(int rounds) {
        int status = 0;
        for (int r = 0; r < rounds; r++) {
          status |= kernel.run(
              srcData, srcValidity, srcNullCount, dstData, dstValidity, scalarArgs, ROWS);
        }
        return status;
      }
    };
  }
}
