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

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddDays;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.And;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DateDiff;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DayOfWeek;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Greatest;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IfElse;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Least;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Not;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Or;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.SubDays;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WeekDay;

/**
 * Emits a fused vector loop for a {@link VarkaVectorIR} DAG with the Class-File API
 * (milestone 2, tasks 9-11): a class implementing {@link VarkaFusedKernel} whose {@code run}
 * is the loop itself - loads, the op DAG on the operand stack, one store per output -
 * mirroring the hand-written {@code DateVectorOps} kernels' six-step shape, generalized. The
 * kernels remain the reference semantics for the arithmetic ops; this class exists so a whole
 * projection - predication included - runs in one pass with its intermediates in vector
 * registers.
 *
 * <p><b>Method layout</b> (task 10's twin bodies, split further in task 11): {@code run}
 * dispatches per batch on one loop-invariant test - are all referenced inputs null-free? - to
 * a dense or masked <i>driver</i>, which zeroes the output validity, takes the all-null
 * shortcut, then calls one sibling <i>loop</i> method per output group (at most
 * {@link #GROUP_BUDGET} ops each; see that constant for the measured reason) and finally the
 * sibling <i>tail</i> method. The dense side runs with no validity bookkeeping at all, which
 * task 11's invariant keeps sound: every node maps valid inputs to valid outputs (there is no
 * null-literal node), so null-free in means all-valid out. Separate methods, not one big one:
 * each gets its own C2 compilation, so no method's node and inlining budgets can starve
 * another's intrinsics.
 *
 * <p><b>Unmasked compute</b> (task 11, plan 2.4): both bodies run unmasked loads, lanewise ops
 * and stores. Inside {@code loopBound} every access is in bounds, an all-null column still has
 * an allocated data buffer, and the engine contract declares invalid destination lanes
 * undefined - so masks carry no correctness inside the loop, and task 10 measured masked ops
 * at 2.3x-2.9x slower even with an all-true mask. Truth lives in the <i>validity words</i>:
 * per lane group each referenced input contributes one long ({@code 0L} all-null, {@code -1L}
 * null-free, {@code validityBitsAt} otherwise), and each node's validity is computed from its
 * children's words by the task-11 mask algebra - AND for the null-intolerant ops, OR for
 * {@code greatest}/{@code least}, a word blend for {@code IfElse}. A {@code VectorMask} is
 * materialized only where a blend semantically needs one.
 *
 * <p><b>Conditions</b> (task 11, plan 2.6): a {@link Cond} node evaluates to a known-true and
 * a known-false word pair - three-valued logic, where an unknown lane (a null below the
 * comparison) is neither, and {@code IfElse} takes its ELSE branch there. In the dense body
 * every input lane is valid, so the pair degenerates to the comparison mask itself and the
 * connectives run in mask space. {@code IfElse} validity is
 * {@code (kT & validThen) | (~kT & validElse)}: the chosen branch's validity, lane-wise,
 * nothing ANDed globally.
 *
 * <p>{@code dayofweek}/{@code weekday} lower to a full-range mod-7 by base-8 digit sum
 * (pre-measured in PLAN_TASK_11.md: 8x the lanewise-DIV variant, which x86 scalarizes): fold
 * 15-, 6- and 3-bit chunks ({@code 2^(3k) = 1 mod 7}), correct by {@code +3} where the input
 * is negative ({@code 2^32 = 4 mod 7}), one compare-subtract fixup, then the constant offset
 * applied after the mod so it cannot overflow.
 *
 * <p>The scalar tail is a per-row topological pass over the same DAG: each distinct node's
 * value (and, in the masked body, its validity bit and a condition's kT/kF bits) lands in an
 * int local computed once per row, mirroring the vector algebra rule for rule. Shared subtrees
 * are therefore computed once per row too.
 *
 * <p>Every call the loop makes is declared once in the descriptor table below - erasure is
 * this milestone's named risk ({@code IntVector.add}, {@code compare}, {@code blend},
 * {@code max} all take the <i>erased</i> {@code Vector}), and a wrong descriptor must be found
 * by pointing at one line, not by disassembling the output.
 *
 * <p>Out-of-shape IR - unknown lane types, a condition as an output root or in a value
 * position, out-of-range ordinals or slots, non-literal day offsets, trees past
 * {@link #MAX_CHAIN_DEPTH} or {@link #MAX_FUSED_NODES} - is rejected with
 * {@link IllegalArgumentException}, which the evaluator wiring treats as "fall back".
 *
 * <p><b>Telemetry</b> (task 13): every emitted class carries a {@code SourceFile} attribute -
 * the caller-supplied name, meant to identify the operator and stage
 * ({@code Varka_Project_Stage3.java}), so a stack frame in the generated {@code run} names the
 * plan node it came from without any mapping table - and a {@link VarkaDebugInfo} custom
 * attribute holding the IR and the caller's plan fragment, so a captured class is
 * self-describing. Both are metadata the JVM ignores; neither costs anything at runtime.
 */
public final class VarkaLoopEmitter {

  /**
   * The deepest op path (root to leaf, per output) the emitter accepts, fixed by measurement
   * (VarkaEmitterParityBenchmark; details in PLAN_TASK_9.md): fused throughput declines only
   * gently with depth while sequential passes collapse linearly, so the cap bounds emitted
   * method size and register pressure by policy, well past any depth a real projection
   * produces, rather than marking a measured performance edge. Condition nodes count.
   */
  public static final int MAX_CHAIN_DEPTH = 16;

  /**
   * The most distinct op nodes one emitted kernel may hold, across all outputs after CSE
   * (task 10). Depth alone no longer bounds method size once outputs multiply, so this is the
   * total-size counterpart of {@link #MAX_CHAIN_DEPTH}: a policy bound far past any real
   * projection, kept honest by the widest-shape case in the parity benchmark. Since task 11
   * the ops are spread over loop methods of at most {@link #GROUP_BUDGET} ops each, so this
   * caps the kernel, not any one compiled method.
   */
  public static final int MAX_FUSED_NODES = 64;

  /**
   * The most op nodes one emitted <i>loop method</i> carries; outputs are partitioned into
   * sibling loop methods within this budget (task 11). Measured reason: each Vector API call
   * site expands into a large intrinsic graph, so C2's compile time grows steeply with op
   * count - the tier-4 compile of a single 64-op loop took ~10 seconds, during which the
   * loop ran the C1 version with boxed vectors at ~1% speed ({@code -XX:+PrintCompilation}
   * shows the OSR task pending; whether a run sees the cliff depends only on when that
   * compile lands relative to it). A 16-op loop method compiles promptly under every load
   * tried, so every hot loop stays at or under it by construction. Grouping is greedy over
   * the output order and counts only nodes new to the group, so outputs sharing subtrees
   * tend to land together and keep their cross-output CSE; a single output wider than the
   * budget gets its own group untouched - splitting inside an output would forfeit the
   * register residency that is the point, and single-output loops measured healthy at every
   * width tried (59 ops: 80% of peak within 400 ms, throughput proportional to op count) -
   * the slow compiles were specific to multi-output loops. Numbers in PLAN_TASK_11.md
   * section 6.
   */
  public static final int GROUP_BUDGET = 16;

  /**
   * The most input columns one emitted loop may read. A node's referenced-column set is a long
   * bitset, which fixes the representation limit at 64; real projections reference a handful.
   */
  public static final int MAX_INPUTS = 64;

  /**
   * Test hook: when set, {@link AddDays} is emitted against a deliberately wrong descriptor
   * (unerased {@code IntVector} parameter instead of {@code Vector}). The class still passes
   * bytecode verification - member resolution happens at link time - so the failure surfaces
   * on first execution as a {@link NoSuchMethodError} naming {@code IntVector.add}. The suite
   * pins that, so a future descriptor regression is diagnosable from the error alone.
   */
  static volatile boolean misdescribeAddForTesting = false;

  /**
   * Test hook: when set, the node memo is disabled and shared subtrees are recomputed at every
   * use. Results must not change - CSE is an optimization, never a semantics change - and the
   * suite pins exactly that; the parity benchmark uses it to price CSE itself.
   */
  static volatile boolean disableCseForTesting = false;

  /**
   * Test hook: when set, {@code dayofweek}/{@code weekday} lower their mod-7 through lanewise
   * {@code DIV} instead of the digit sum - the certainly-correct reference variant the parity
   * benchmark prices the shipped one against (plan 2.3).
   */
  static volatile boolean divFloorModForTesting = false;

  private VarkaLoopEmitter() {
  }

  // ---------------------------------------------------------------------------------------------
  // Descriptor table: the single source of truth for everything the emitted code calls.
  // ---------------------------------------------------------------------------------------------

  private static final ClassDesc MEMORY_SEGMENT =
      ClassDesc.of("java.lang.foreign.MemorySegment");
  private static final ClassDesc VALUE_LAYOUT =
      ClassDesc.of("java.lang.foreign.ValueLayout");
  private static final ClassDesc VALUE_LAYOUT_OF_INT =
      ClassDesc.ofDescriptor("Ljava/lang/foreign/ValueLayout$OfInt;");
  private static final ClassDesc BYTE_ORDER = ClassDesc.of("java.nio.ByteOrder");
  private static final ClassDesc INT_VECTOR = ClassDesc.of("jdk.incubator.vector.IntVector");
  private static final ClassDesc VECTOR = ClassDesc.of("jdk.incubator.vector.Vector");
  private static final ClassDesc VECTOR_MASK = ClassDesc.of("jdk.incubator.vector.VectorMask");
  private static final ClassDesc VECTOR_SPECIES =
      ClassDesc.of("jdk.incubator.vector.VectorSpecies");
  private static final ClassDesc VECTOR_OPERATORS =
      ClassDesc.of("jdk.incubator.vector.VectorOperators");
  private static final ClassDesc VO_COMPARISON =
      ClassDesc.ofDescriptor("Ljdk/incubator/vector/VectorOperators$Comparison;");
  private static final ClassDesc VO_BINARY =
      ClassDesc.ofDescriptor("Ljdk/incubator/vector/VectorOperators$Binary;");
  private static final ClassDesc MATH = ClassDesc.of("java.lang.Math");
  private static final ClassDesc SUPPORT =
      ClassDesc.of("org.apache.spark.sql.varka.vector.VarkaVectorSupport");
  private static final ClassDesc FUSED_KERNEL = ClassDesc.of(VarkaFusedKernel.class.getName());

  private static final ClassDesc LONG_ARRAY = ConstantDescs.CD_long.arrayType();
  private static final ClassDesc INT_ARRAY = ConstantDescs.CD_int.arrayType();

  /** {@code void run(long[], long[], int[], long[], long[], int[], int)}. */
  private static final MethodTypeDesc RUN = MethodTypeDesc.of(ConstantDescs.CD_void,
      LONG_ARRAY, LONG_ARRAY, INT_ARRAY, LONG_ARRAY, LONG_ARRAY, INT_ARRAY,
      ConstantDescs.CD_int);
  private static final MethodTypeDesc INIT = MethodTypeDesc.of(ConstantDescs.CD_void);

  /** {@code MemorySegment VarkaVectorSupport.ofAddress(long, long)}. */
  private static final MethodTypeDesc OF_ADDRESS =
      MethodTypeDesc.of(MEMORY_SEGMENT, ConstantDescs.CD_long, ConstantDescs.CD_long);
  /** {@code void VarkaVectorSupport.zero(MemorySegment)}. */
  private static final MethodTypeDesc ZERO =
      MethodTypeDesc.of(ConstantDescs.CD_void, MEMORY_SEGMENT);
  /** {@code long VarkaVectorSupport.validityBitsAt(MemorySegment, long, int)}. */
  private static final MethodTypeDesc VALIDITY_BITS_AT = MethodTypeDesc.of(
      ConstantDescs.CD_long, MEMORY_SEGMENT, ConstantDescs.CD_long, ConstantDescs.CD_int);
  /** {@code void VarkaVectorSupport.orValidityBitsAt(MemorySegment, long, long, int)}. */
  private static final MethodTypeDesc OR_VALIDITY_BITS_AT = MethodTypeDesc.of(
      ConstantDescs.CD_void, MEMORY_SEGMENT, ConstantDescs.CD_long, ConstantDescs.CD_long,
      ConstantDescs.CD_int);
  /** {@code boolean VarkaVectorSupport.isBitSet(MemorySegment, int)}. */
  private static final MethodTypeDesc IS_BIT_SET =
      MethodTypeDesc.of(ConstantDescs.CD_boolean, MEMORY_SEGMENT, ConstantDescs.CD_int);
  /** {@code void VarkaVectorSupport.setBit(MemorySegment, int)}. */
  private static final MethodTypeDesc SET_BIT =
      MethodTypeDesc.of(ConstantDescs.CD_void, MEMORY_SEGMENT, ConstantDescs.CD_int);

  /** {@code int VectorSpecies.length()} / {@code int VectorSpecies.loopBound(int)}. */
  private static final MethodTypeDesc SPECIES_LENGTH = MethodTypeDesc.of(ConstantDescs.CD_int);
  private static final MethodTypeDesc LOOP_BOUND =
      MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_int);
  /** {@code IntVector IntVector.broadcast(VectorSpecies, int)} (static). */
  private static final MethodTypeDesc BROADCAST =
      MethodTypeDesc.of(INT_VECTOR, VECTOR_SPECIES, ConstantDescs.CD_int);
  /** {@code VectorMask VectorMask.fromLong(VectorSpecies, long)} (static). */
  private static final MethodTypeDesc FROM_LONG =
      MethodTypeDesc.of(VECTOR_MASK, VECTOR_SPECIES, ConstantDescs.CD_long);
  /** {@code long VectorMask.toLong()}. */
  private static final MethodTypeDesc TO_LONG = MethodTypeDesc.of(ConstantDescs.CD_long);
  /**
   * {@code IntVector.fromMemorySegment(VectorSpecies, MemorySegment, long, ByteOrder)}
   * (static, unmasked - see the class doc; every load is inside {@code loopBound}).
   */
  private static final MethodTypeDesc FROM_MEMORY_SEGMENT_DENSE = MethodTypeDesc.of(INT_VECTOR,
      VECTOR_SPECIES, MEMORY_SEGMENT, ConstantDescs.CD_long, BYTE_ORDER);
  /**
   * {@code IntVector IntVector.add/sub/max/min(Vector)} - the parameter is the *erased*
   * {@code Vector}, not {@code IntVector}; the covariant return stays {@code IntVector}.
   */
  private static final MethodTypeDesc LANEWISE_VV =
      MethodTypeDesc.of(INT_VECTOR, VECTOR);
  /** The deliberately wrong shape behind {@link #misdescribeAddForTesting}. */
  private static final MethodTypeDesc LANEWISE_VV_WRONG =
      MethodTypeDesc.of(INT_VECTOR, INT_VECTOR);
  /** {@code IntVector IntVector.add/sub/and/mul/div(int)} - broadcast-scalar convenience. */
  private static final MethodTypeDesc LANEWISE_VI =
      MethodTypeDesc.of(INT_VECTOR, ConstantDescs.CD_int);
  /** {@code IntVector IntVector.add/sub(int, VectorMask)}. */
  private static final MethodTypeDesc LANEWISE_VI_MASKED =
      MethodTypeDesc.of(INT_VECTOR, ConstantDescs.CD_int, VECTOR_MASK);
  /** {@code IntVector IntVector.lanewise(VectorOperators.Binary, int)} - the shifts. */
  private static final MethodTypeDesc LANEWISE_BINARY_I =
      MethodTypeDesc.of(INT_VECTOR, VO_BINARY, ConstantDescs.CD_int);
  /** {@code VectorMask IntVector.compare(VectorOperators.Comparison, Vector)} - erased. */
  private static final MethodTypeDesc COMPARE_VV =
      MethodTypeDesc.of(VECTOR_MASK, VO_COMPARISON, VECTOR);
  /** {@code VectorMask IntVector.compare(VectorOperators.Comparison, int)}. */
  private static final MethodTypeDesc COMPARE_VI =
      MethodTypeDesc.of(VECTOR_MASK, VO_COMPARISON, ConstantDescs.CD_int);
  /** {@code IntVector IntVector.blend(Vector, VectorMask)} - erased {@code Vector}. */
  private static final MethodTypeDesc BLEND =
      MethodTypeDesc.of(INT_VECTOR, VECTOR, VECTOR_MASK);
  /** {@code VectorMask VectorMask.and/or(VectorMask)} and {@code VectorMask.not()}. */
  private static final MethodTypeDesc MASK_BINARY = MethodTypeDesc.of(VECTOR_MASK, VECTOR_MASK);
  private static final MethodTypeDesc MASK_UNARY = MethodTypeDesc.of(VECTOR_MASK);
  /** {@code void IntVector.intoMemorySegment(MemorySegment, long, ByteOrder)} - unmasked. */
  private static final MethodTypeDesc INTO_MEMORY_SEGMENT_DENSE = MethodTypeDesc.of(
      ConstantDescs.CD_void, MEMORY_SEGMENT, ConstantDescs.CD_long, BYTE_ORDER);
  /** {@code int MemorySegment.get(ValueLayout.OfInt, long)}. */
  private static final MethodTypeDesc SEGMENT_GET_INT = MethodTypeDesc.of(
      ConstantDescs.CD_int, VALUE_LAYOUT_OF_INT, ConstantDescs.CD_long);
  /** {@code void MemorySegment.set(ValueLayout.OfInt, long, int)}. */
  private static final MethodTypeDesc SEGMENT_SET_INT = MethodTypeDesc.of(ConstantDescs.CD_void,
      VALUE_LAYOUT_OF_INT, ConstantDescs.CD_long, ConstantDescs.CD_int);
  /** {@code int Math.floorMod(int, int)} / {@code int Math.max/min(int, int)} (static). */
  private static final MethodTypeDesc MATH_II_I = MethodTypeDesc.of(
      ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_int);

  // Parameter slots of `run` (instance method: `this` is slot 0, finding 11's lesson).
  private static final int P_SRC_DATA = 1;
  private static final int P_SRC_VALIDITY = 2;
  private static final int P_NULL_COUNT = 3;
  private static final int P_DST_DATA = 4;
  private static final int P_DST_VALIDITY = 5;
  private static final int P_SCALAR_ARGS = 6;
  private static final int P_LENGTH = 7;

  // The word-reference value meaning "constant all-true" (a literal-only subtree).
  private static final int WORD_ALL_TRUE = -1;

  /**
   * The telemetry-defaulted form of {@link #emit(String, List, int, int, String, String)}: the
   * {@code SourceFile} name falls back to the class's own simple name and the plan fragment to
   * empty. For callers that hold no plan - tests and benchmarks building IR by hand.
   */
  public static byte[] emit(
      String className, List<VarkaVectorIR> outputs, int numInputs, int numLiterals) {
    return emit(className, outputs, numInputs, numLiterals, null, null);
  }

  /**
   * Assembles the fused-kernel class for the given output trees over {@code numInputs} columns
   * and {@code numLiterals} scalar-argument slots. Output {@code o} writes
   * {@code dstData[o]}/{@code dstValidity[o]}; a {@link ColumnRef} ordinal indexes the
   * {@code src*} arrays.
   *
   * <p>{@code sourceFile} becomes the class's {@code SourceFile} attribute - callers name the
   * operator and stage there so stack traces name the plan node - and {@code planFragment} is
   * carried verbatim in the {@link VarkaDebugInfo} attribute beside the IR (the telemetry note
   * in the class doc). Either may be null; see the four-argument form for the defaults.
   *
   * @throws IllegalArgumentException if the IR is outside what this emitter serves - the
   *         caller is expected to fall back to the per-row projection, exactly as a kernel
   *         failure does.
   */
  public static byte[] emit(
      String className, List<VarkaVectorIR> outputs, int numInputs, int numLiterals,
      String sourceFile, String planFragment) {
    if (outputs.isEmpty()) {
      throw new IllegalArgumentException("no output chains to emit");
    }
    if (numInputs < 1 || numInputs > MAX_INPUTS) {
      throw new IllegalArgumentException(
          "numInputs " + numInputs + " outside [1, " + MAX_INPUTS + "]");
    }
    Analysis analysis = new Analysis(numInputs, numLiterals);
    for (VarkaVectorIR root : outputs) {
      analysis.analyzeRoot(root);
    }

    // Method layout, all sharing the seven-parameter shape so slots line up everywhere:
    // `run` dispatches per batch to a dense or masked *driver*; the driver zeroes the output
    // validity, takes the all-null shortcut, then calls one sibling *loop* method per output
    // group (each at most GROUP_BUDGET ops - see that constant for the measured reason) and
    // finally the sibling *tail* method. Separate methods, not one big one: each gets its own
    // C2 compilation, so no method's node and inlining budgets can starve another's
    // intrinsics (task 10 measured 3x to 4x on exactly that).
    ClassDesc classDesc = ClassDesc.of(className);
    boolean anyColumns = analysis.referencedColumns != 0;
    List<List<Integer>> groups = groupOutputs(outputs);
    String source = sourceFile != null
        ? sourceFile : className.substring(className.lastIndexOf('.') + 1) + ".java";
    VarkaDebugInfo debugInfo = new VarkaDebugInfo(
        "outputs=" + outputs + ", numInputs=" + numInputs + ", numLiterals=" + numLiterals,
        planFragment != null ? planFragment : "");
    return ClassFile.of().build(classDesc, (ClassBuilder b) -> {
      b.withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL)
          .withInterfaceSymbols(FUSED_KERNEL)
          .with(SourceFileAttribute.of(source))
          .with(debugInfo)
          .withMethodBody("<init>", INIT, AccessFlag.PUBLIC.mask(), (CodeBuilder cb) -> {
            cb.aload(0);
            cb.invokespecial(ConstantDescs.CD_Object, "<init>", INIT);
            cb.return_();
          })
          .withMethodBody("run", RUN, AccessFlag.PUBLIC.mask(),
              (CodeBuilder cb) -> emitDispatch(cb, classDesc, analysis))
          .withMethodBody("runDense", RUN, AccessFlag.PRIVATE.mask(),
              (CodeBuilder cb) -> emitBody(cb, true, BodyMode.DRIVER, -1, classDesc, outputs,
                  analysis, numLiterals, groups))
          .withMethodBody("tailDense", RUN, AccessFlag.PRIVATE.mask(),
              (CodeBuilder cb) -> emitBody(cb, true, BodyMode.TAIL, -1, classDesc, outputs,
                  analysis, numLiterals, groups));
      for (int g = 0; g < groups.size(); g++) {
        final int group = g;
        b.withMethodBody("loopDense" + g, RUN, AccessFlag.PRIVATE.mask(),
            (CodeBuilder cb) -> emitBody(cb, true, BodyMode.LOOP, group, classDesc, outputs,
                analysis, numLiterals, groups));
      }
      if (anyColumns) {
        b.withMethodBody("runMasked", RUN, AccessFlag.PRIVATE.mask(),
            (CodeBuilder cb) -> emitBody(cb, false, BodyMode.DRIVER, -1, classDesc, outputs,
                analysis, numLiterals, groups))
            .withMethodBody("tailMasked", RUN, AccessFlag.PRIVATE.mask(),
                (CodeBuilder cb) -> emitBody(cb, false, BodyMode.TAIL, -1, classDesc, outputs,
                    analysis, numLiterals, groups));
        for (int g = 0; g < groups.size(); g++) {
          final int group = g;
          b.withMethodBody("loopMasked" + g, RUN, AccessFlag.PRIVATE.mask(),
              (CodeBuilder cb) -> emitBody(cb, false, BodyMode.LOOP, group, classDesc, outputs,
                  analysis, numLiterals, groups));
        }
      }
    });
  }

  /** The three body-method roles; see the method-layout note in {@link #emit}. */
  private enum BodyMode { DRIVER, LOOP, TAIL }

  /**
   * Partitions the outputs into loop-method groups of at most {@link #GROUP_BUDGET} ops,
   * greedily in output order, counting only ops new to the group so shared subtrees keep
   * their outputs together (and their cross-output CSE). An output wider than the budget on
   * its own still forms a group: splitting inside one output would forfeit the register
   * residency that is the point.
   */
  private static List<List<Integer>> groupOutputs(List<VarkaVectorIR> outputs) {
    List<List<Integer>> groups = new ArrayList<>();
    List<Integer> current = new ArrayList<>();
    Set<VarkaVectorIR> seen = new HashSet<>();
    int ops = 0;
    for (int o = 0; o < outputs.size(); o++) {
      Set<VarkaVectorIR> withNext = new HashSet<>(seen);
      int marginal = addOps(outputs.get(o), withNext);
      if (!current.isEmpty() && ops + marginal > GROUP_BUDGET) {
        groups.add(current);
        current = new ArrayList<>();
        withNext = new HashSet<>();
        marginal = addOps(outputs.get(o), withNext);
        ops = 0;
      }
      current.add(o);
      seen = withNext;
      ops += marginal;
    }
    groups.add(current);
    return groups;
  }

  /** Adds the subtree's distinct nodes to {@code seen}; returns how many op nodes were new. */
  private static int addOps(VarkaVectorIR node, Set<VarkaVectorIR> seen) {
    if (!seen.add(node)) {
      return 0;
    }
    int count = node instanceof ColumnRef || node instanceof LiteralSlot ? 0 : 1;
    for (VarkaVectorIR child : childrenOf(node)) {
      count += addOps(child, seen);
    }
    return count;
  }

  private static VarkaVectorIR[] childrenOf(VarkaVectorIR node) {
    return switch (node) {
      case ColumnRef c -> new VarkaVectorIR[0];
      case LiteralSlot l -> new VarkaVectorIR[0];
      case AddDays n -> new VarkaVectorIR[] {n.days(), n.offset()};
      case SubDays n -> new VarkaVectorIR[] {n.days(), n.offset()};
      case DateDiff n -> new VarkaVectorIR[] {n.end(), n.start()};
      case DayOfWeek n -> new VarkaVectorIR[] {n.days()};
      case WeekDay n -> new VarkaVectorIR[] {n.days()};
      case Greatest n -> new VarkaVectorIR[] {n.left(), n.right()};
      case Least n -> new VarkaVectorIR[] {n.left(), n.right()};
      case IfElse n -> new VarkaVectorIR[] {n.cond(), n.thenNode(), n.elseNode()};
      case Compare n -> new VarkaVectorIR[] {n.left(), n.right()};
      case And n -> new VarkaVectorIR[] {n.left(), n.right()};
      case Or n -> new VarkaVectorIR[] {n.left(), n.right()};
      case Not n -> new VarkaVectorIR[] {n.child()};
    };
  }

  /**
   * The public {@code run}: one loop-invariant test per batch - are all referenced inputs
   * null-free? - selecting {@code runDense} or {@code runMasked} (plan 2.5 of task 10).
   */
  private static void emitDispatch(CodeBuilder cb, ClassDesc classDesc, Analysis analysis) {
    Label masked = cb.newLabel();
    boolean anyColumns = analysis.referencedColumns != 0;
    for (int i = 0; i < analysis.numInputs; i++) {
      if (referenced(analysis, i)) {
        cb.aload(P_NULL_COUNT);
        cb.loadConstant(i);
        cb.iaload();
        cb.ifne(masked);
      }
    }
    invokeBody(cb, classDesc, "runDense");
    if (anyColumns) {
      cb.labelBinding(masked);
      invokeBody(cb, classDesc, "runMasked");
    }
    // With no referenced columns the masked label is never targeted and must not be bound:
    // unreachable code has no stack frame to compute.
  }

  /** {@code this.<name>(srcData, ..., length)} - all seven parameters forwarded. */
  private static void invokeCall(CodeBuilder cb, ClassDesc classDesc, String name) {
    cb.aload(0);
    cb.aload(P_SRC_DATA);
    cb.aload(P_SRC_VALIDITY);
    cb.aload(P_NULL_COUNT);
    cb.aload(P_DST_DATA);
    cb.aload(P_DST_VALIDITY);
    cb.aload(P_SCALAR_ARGS);
    cb.iload(P_LENGTH);
    cb.invokespecial(classDesc, name, RUN);
  }

  /** {@link #invokeCall} followed by {@code return}. */
  private static void invokeBody(CodeBuilder cb, ClassDesc classDesc, String name) {
    invokeCall(cb, classDesc, name);
    cb.return_();
  }

  // ---------------------------------------------------------------------------------------------
  // Validation and DAG analysis.
  // ---------------------------------------------------------------------------------------------

  /**
   * One walk over the output trees, before any bytecode exists: validates every node, counts
   * uses on structural equality (the DAG view of trees the caller may have built
   * independently), computes per node the referenced-column bitset and its height, collects a
   * post-order (children-first) topological order for the scalar tail, and marks the
   * null-skipping subtrees the all-null shortcut must not reason about.
   */
  private static final class Analysis {
    final int numInputs;
    final int numLiterals;
    /** Distinct nodes in first-visit order, with how often each is used. */
    final Map<VarkaVectorIR, Integer> useCount = new LinkedHashMap<>();
    /** Per distinct node, the bitset of input ordinals its subtree references. */
    final Map<VarkaVectorIR, Long> columns = new HashMap<>();
    /** Distinct nodes, children strictly before parents - the scalar tail's schedule. */
    final List<VarkaVectorIR> topoOrder = new ArrayList<>();
    /** Whether the subtree holds a null-skipping node (IfElse, Greatest, Least). */
    final Map<VarkaVectorIR, Boolean> skipping = new HashMap<>();
    private final Map<VarkaVectorIR, Integer> height = new HashMap<>();
    /** The union of every node's columns: unreferenced inputs get no locals and no state. */
    long referencedColumns = 0L;
    private int opNodes = 0;

    Analysis(int numInputs, int numLiterals) {
      this.numInputs = numInputs;
      this.numLiterals = numLiterals;
    }

    void analyzeRoot(VarkaVectorIR root) {
      requireValue(root, "output root");
      analyze(root);
      if (height.get(root) > MAX_CHAIN_DEPTH) {
        throw new IllegalArgumentException(
            "chain deeper than MAX_CHAIN_DEPTH=" + MAX_CHAIN_DEPTH);
      }
    }

    private static void requireValue(VarkaVectorIR node, String position) {
      if (node instanceof Cond) {
        throw new IllegalArgumentException(
            "condition node " + node + " in a value position (" + position + ")");
      }
    }

    private void analyze(VarkaVectorIR node) {
      if (node.laneType() != VarkaVectorIR.LaneType.INT) {
        throw new IllegalArgumentException("unsupported lane type " + node.laneType());
      }
      Integer seen = useCount.get(node);
      if (seen != null) {
        // A repeated node: its subtree is already analyzed, only the use count grows.
        useCount.put(node, seen + 1);
        return;
      }
      useCount.put(node, 1);
      switch (node) {
        case ColumnRef c -> {
          if (c.ordinal() < 0 || c.ordinal() >= numInputs) {
            throw new IllegalArgumentException(
                "column ordinal " + c.ordinal() + " outside [0, " + numInputs + ")");
          }
          long set = 1L << c.ordinal();
          columns.put(node, set);
          height.put(node, 0);
          skipping.put(node, false);
          referencedColumns |= set;
        }
        case LiteralSlot l -> {
          if (l.index() < 0 || l.index() >= numLiterals) {
            throw new IllegalArgumentException(
                "literal slot " + l.index() + " outside [0, " + numLiterals + ")");
          }
          columns.put(node, 0L);
          height.put(node, 0);
          skipping.put(node, false);
        }
        case AddDays n -> {
          requireLiteralOffset(n.offset());
          analyzeOp(node, false, n.days(), n.offset());
        }
        case SubDays n -> {
          requireLiteralOffset(n.offset());
          analyzeOp(node, false, n.days(), n.offset());
        }
        case DateDiff n -> analyzeOp(node, false, n.end(), n.start());
        case DayOfWeek n -> analyzeOp(node, false, n.days());
        case WeekDay n -> analyzeOp(node, false, n.days());
        case Greatest n -> analyzeOp(node, true, n.left(), n.right());
        case Least n -> analyzeOp(node, true, n.left(), n.right());
        case IfElse n -> analyzeOp(node, true, n.cond(), n.thenNode(), n.elseNode());
        case Compare n -> analyzeOp(node, false, n.left(), n.right());
        case And n -> analyzeOp(node, false, n.left(), n.right());
        case Or n -> analyzeOp(node, false, n.left(), n.right());
        case Not n -> analyzeOp(node, false, n.child());
      }
      topoOrder.add(node);
    }

    /**
     * Common op bookkeeping. Value-typed children are checked against condition nodes here;
     * condition-typed children ({@code IfElse.cond}, the connectives') are enforced by the
     * record types themselves.
     */
    private void analyzeOp(VarkaVectorIR node, boolean skips, VarkaVectorIR... children) {
      opNodes++;
      if (opNodes > MAX_FUSED_NODES) {
        throw new IllegalArgumentException(
            "more than MAX_FUSED_NODES=" + MAX_FUSED_NODES + " distinct ops");
      }
      long set = 0L;
      int maxChildHeight = 0;
      boolean childSkips = false;
      for (VarkaVectorIR child : children) {
        // Value children of value ops and of Compare must not be conditions; the ops whose
        // condition children are legal carry them in Cond-typed record fields already.
        if (child instanceof Cond && !(node instanceof IfElse) && !(node instanceof And)
            && !(node instanceof Or) && !(node instanceof Not)) {
          requireValue(child, "child of " + node.getClass().getSimpleName());
        }
        analyze(child);
        set |= columns.get(child);
        maxChildHeight = Math.max(maxChildHeight, height.get(child));
        childSkips |= skipping.get(child);
      }
      columns.put(node, set);
      height.put(node, 1 + maxChildHeight);
      skipping.put(node, skips || childSkips);
    }

    private static void requireLiteralOffset(VarkaVectorIR offset) {
      if (!(offset instanceof LiteralSlot)) {
        throw new IllegalArgumentException("day offsets must be literal slots, got " + offset);
      }
    }
  }

  private static boolean referenced(Analysis analysis, int ordinal) {
    return (analysis.referencedColumns >>> ordinal & 1L) != 0;
  }

  // ---------------------------------------------------------------------------------------------
  // Slot planning.
  // ---------------------------------------------------------------------------------------------

  /** The local-variable slots one emitted body uses, threaded to the emitters. */
  private static final class Slots {
    /** The nominal data / validity segment sizes in bytes (long slots). */
    int dataBytes;
    int validityBytes;
    final int[] srcSeg;
    final int[] srcValSeg;
    final int[] dead;
    final int[] hasNulls;
    final int[] word;
    final int[] dstSeg;
    final int[] dstValSeg;
    int ncTmp;
    int species;
    int lanes;
    int loopBound;
    int[] scalarArg;
    int[] broadcastSlot;
    int iVar;
    int byteOffset;
    int cmpTmp;
    int maskTmp;
    /** Per distinct value node: its validity-word reference (a long slot, an input's word
     * slot, or {@link #WORD_ALL_TRUE}); aliased where the algebra makes it a copy. */
    final Map<VarkaVectorIR, Integer> wordRef = new HashMap<>();
    /** The value nodes whose word is computed into their own slot (not an alias). */
    final Set<VarkaVectorIR> ownWord = new HashSet<>();
    /** Per condition node, masked body: the known-true / known-false word slots. */
    final Map<VarkaVectorIR, Integer> kt = new HashMap<>();
    final Map<VarkaVectorIR, Integer> kf = new HashMap<>();
    /** The conditions whose kt/kf are computed (Not aliases its child's, swapped). */
    final Set<VarkaVectorIR> ownCond = new HashSet<>();
    /** Per condition node, dense body: the single mask local. */
    final Map<VarkaVectorIR, Integer> condMask = new HashMap<>();
    /** Per node used more than once: the local its first vector lands in (DAG-CSE). */
    final Map<VarkaVectorIR, Integer> sharedSlot = new HashMap<>();
    /** Per Greatest/Least (masked): the two operand temporaries the substitution needs. */
    final Map<VarkaVectorIR, int[]> pairTmp = new HashMap<>();
    /** Per DayOfWeek/WeekDay: the original-value and fold temporaries. */
    final Map<VarkaVectorIR, int[]> dowTmp = new HashMap<>();
    /** Scalar-tail int slots: value, validity bit, condition kt/kf bits. */
    final Map<VarkaVectorIR, Integer> tailVal = new HashMap<>();
    final Map<VarkaVectorIR, Integer> tailValid = new HashMap<>();
    final Map<VarkaVectorIR, Integer> tailKt = new HashMap<>();
    final Map<VarkaVectorIR, Integer> tailKf = new HashMap<>();

    Slots(int numInputs, int numOutputs) {
      srcSeg = new int[numInputs];
      srcValSeg = new int[numInputs];
      dead = new int[numInputs];
      hasNulls = new int[numInputs];
      word = new int[numInputs];
      dstSeg = new int[numOutputs];
      dstValSeg = new int[numOutputs];
    }
  }

  /**
   * Assigns every local slot the body needs, including the per-node word/condition/tail slots,
   * with word aliasing: a node whose validity equals one child's (a literal offset, a unary
   * op) shares that child's reference instead of recomputing it. Per-node slots are planned
   * only for the body role that emits them - the vector-walk slots for a loop method, the tail
   * slots for the tail method, neither for the driver, which runs only the shared prologue.
   */
  private static Slots planSlots(boolean dense, BodyMode mode, List<VarkaVectorIR> outputs,
      Analysis analysis, int numLiterals) {
    int numInputs = analysis.numInputs;
    Slots s = new Slots(numInputs, outputs.size());
    int slot = 8;
    s.dataBytes = slot;
    slot += 2;
    s.validityBytes = slot;
    slot += 2;
    for (int o = 0; o < outputs.size(); o++) {
      s.dstSeg[o] = slot++;
      s.dstValSeg[o] = slot++;
    }
    for (int i = 0; i < numInputs; i++) {
      if (referenced(analysis, i)) {
        s.srcSeg[i] = slot++;
        s.srcValSeg[i] = slot++;
        s.dead[i] = slot++;
        s.hasNulls[i] = slot++;
        s.word[i] = slot;
        slot += 2;
      }
    }
    s.ncTmp = slot++;
    s.species = slot++;
    s.lanes = slot++;
    s.loopBound = slot++;
    s.scalarArg = new int[numLiterals];
    for (int j = 0; j < numLiterals; j++) {
      s.scalarArg[j] = slot++;
    }
    // Broadcasts are hoisted into vector locals only where they are used - the loop methods -
    // and only in the regime task 9 measured the hoist as a win: one output, at most a chain's
    // worth of literals. Any wider body inlines them at each use and lets C2 rematerialize
    // under register pressure (PLAN_TASK_10.md).
    s.broadcastSlot = mode == BodyMode.LOOP
        && outputs.size() == 1 && numLiterals <= MAX_CHAIN_DEPTH ? new int[numLiterals] : null;
    if (s.broadcastSlot != null) {
      for (int j = 0; j < numLiterals; j++) {
        s.broadcastSlot[j] = slot++;
      }
    }
    s.iVar = slot++;
    s.byteOffset = slot;
    slot += 2;
    s.cmpTmp = slot;
    slot += 2;
    s.maskTmp = slot++;

    boolean cse = !disableCseForTesting;
    for (VarkaVectorIR node : analysis.topoOrder) {
      if (mode == BodyMode.LOOP) {
        // Vector-walk slots. Children precede parents in the topo order, so a word reference
        // computed here always sees concrete child references - the aliasing depends on it.
        if (!(node instanceof Cond)) {
          if (!dense) {
            int ref = planWordRef(node, s);
            if (ref == Integer.MIN_VALUE) {
              ref = slot;
              slot += 2;
              s.ownWord.add(node);
            }
            s.wordRef.put(node, ref);
          }
          if (cse && analysis.useCount.get(node) > 1 && !(node instanceof LiteralSlot)) {
            s.sharedSlot.put(node, slot++);
          }
          if (!dense && (node instanceof Greatest || node instanceof Least)) {
            s.pairTmp.put(node, new int[] {slot++, slot++});
          }
          if (node instanceof DayOfWeek || node instanceof WeekDay) {
            s.dowTmp.put(node, new int[] {slot++, slot++});
          }
        } else if (dense) {
          s.condMask.put(node, slot++);
        } else {
          if (node instanceof Not n) {
            // NOT swaps the pair: pure slot aliasing, no code emitted for it.
            s.kt.put(node, s.kf.get(n.child()));
            s.kf.put(node, s.kt.get(n.child()));
          } else {
            s.kt.put(node, slot);
            slot += 2;
            s.kf.put(node, slot);
            slot += 2;
            s.ownCond.add(node);
          }
        }
      } else if (mode == BodyMode.TAIL) {
        // Scalar-tail slots.
        if (node instanceof Cond) {
          s.tailKt.put(node, slot++);
          if (!dense) {
            s.tailKf.put(node, slot++);
          }
        } else if (!(node instanceof LiteralSlot)) {
          s.tailVal.put(node, slot++);
          if (!dense) {
            s.tailValid.put(node, slot++);
          }
        }
      }
    }
    return s;
  }

  /**
   * The validity-word reference for a value node, or {@code Integer.MIN_VALUE} when the node
   * needs its own slot (assigned in a second pass). AND-nodes over a single non-constant
   * child alias that child; literal-only subtrees are the all-true constant.
   */
  private static int planWordRef(VarkaVectorIR node, Slots s) {
    return switch (node) {
      case ColumnRef c -> s.word[c.ordinal()];
      case LiteralSlot l -> WORD_ALL_TRUE;
      case AddDays n -> s.wordRef.get(n.days());
      case SubDays n -> s.wordRef.get(n.days());
      case DayOfWeek n -> s.wordRef.get(n.days());
      case WeekDay n -> s.wordRef.get(n.days());
      case DateDiff n -> andRef(s.wordRef.get(n.end()), s.wordRef.get(n.start()));
      // Greatest/Least (OR) and IfElse (blend) always compute their own word.
      default -> Integer.MIN_VALUE;
    };
  }

  private static int andRef(int a, int b) {
    if (a == WORD_ALL_TRUE) {
      return b;
    }
    if (b == WORD_ALL_TRUE || a == b) {
      return a;
    }
    return Integer.MIN_VALUE;
  }

  // ---------------------------------------------------------------------------------------------
  // The emitted body methods.
  // ---------------------------------------------------------------------------------------------

  /**
   * One body method in one of the three roles of the method layout (see {@link #emit}). The
   * dense variants run only when the dispatcher has proven every referenced input null-free,
   * so they emit no all-null shortcut and no validity words; the masked variants are the
   * general ones, and the pairs must agree wherever both could run. Every method re-derives
   * the prologue state from the same seven parameters; only the driver zeroes the destination
   * validity (the loop and tail methods run after bits were written and must not), and the
   * tail starts its row loop at {@code loopBound}.
   */
  private static void emitBody(CodeBuilder cb, boolean dense, BodyMode mode, int group,
      ClassDesc classDesc, List<VarkaVectorIR> outputs, Analysis analysis, int numLiterals,
      List<List<Integer>> groups) {
    int numInputs = analysis.numInputs;
    int numOutputs = outputs.size();
    Slots s = planSlots(dense, mode, outputs, analysis, numLiterals);

    // (1) if (length <= 0) return;
    Label nonEmpty = cb.newLabel();
    cb.iload(P_LENGTH);
    cb.ifgt(nonEmpty);
    cb.return_();
    cb.labelBinding(nonEmpty);

    // (2) Nominal sizes: dataBytes = (long) length * 4; validityBytes = (length + 7) / 8L.
    cb.iload(P_LENGTH);
    cb.i2l();
    cb.loadConstant(4L);
    cb.lmul();
    cb.lstore(s.dataBytes);
    cb.iload(P_LENGTH);
    cb.loadConstant(7);
    cb.iadd();
    cb.i2l();
    cb.loadConstant(8L);
    cb.ldiv();
    cb.lstore(s.validityBytes);

    // (3) Per output: segments, and - in the driver only - zero(dstValidity) before any
    // return below, the emitter invariant: an output nothing writes must still read as
    // all-null. The loop and tail methods run after bits were written and must not.
    for (int o = 0; o < numOutputs; o++) {
      loadSegment(cb, P_DST_DATA, o, s.dataBytes, s.dstSeg[o]);
      loadSegment(cb, P_DST_VALIDITY, o, s.validityBytes, s.dstValSeg[o]);
      if (mode == BodyMode.DRIVER) {
        cb.aload(s.dstValSeg[o]);
        cb.invokestatic(SUPPORT, "zero", ZERO);
      }
    }

    // (4) Per referenced input: null state (masked body only - the dispatcher has proven a
    // dense batch null-free) and the data segment. An all-null input's validity address is 0L
    // by the morsel contract, so its segment must not be materialized; its validity word is 0L
    // in every group instead, which nulls everything computed from it.
    for (int i = 0; i < numInputs; i++) {
      if (!referenced(analysis, i)) {
        continue;
      }
      if (dense) {
        loadSegment(cb, P_SRC_DATA, i, s.dataBytes, s.srcSeg[i]);
        continue;
      }
      cb.aload(P_NULL_COUNT);
      cb.loadConstant(i);
      cb.iaload();
      cb.istore(s.ncTmp);
      Label notDead = cb.newLabel();
      Label stateDone = cb.newLabel();
      cb.iload(s.ncTmp);
      cb.iload(P_LENGTH);
      cb.if_icmpne(notDead);
      cb.loadConstant(1);
      cb.istore(s.dead[i]);
      cb.loadConstant(0);
      cb.istore(s.hasNulls[i]);
      cb.aconst_null();
      cb.astore(s.srcValSeg[i]);
      cb.goto_(stateDone);
      cb.labelBinding(notDead);
      cb.loadConstant(0);
      cb.istore(s.dead[i]);
      Label noNulls = cb.newLabel();
      cb.iload(s.ncTmp);
      cb.ifle(noNulls);
      cb.loadConstant(1);
      cb.istore(s.hasNulls[i]);
      cb.aload(P_SRC_VALIDITY);
      cb.loadConstant(i);
      cb.laload();
      cb.lload(s.validityBytes);
      cb.invokestatic(SUPPORT, "ofAddress", OF_ADDRESS);
      cb.astore(s.srcValSeg[i]);
      cb.goto_(stateDone);
      cb.labelBinding(noNulls);
      cb.loadConstant(0);
      cb.istore(s.hasNulls[i]);
      cb.aconst_null();
      cb.astore(s.srcValSeg[i]);
      cb.labelBinding(stateDone);
      loadSegment(cb, P_SRC_DATA, i, s.dataBytes, s.srcSeg[i]);
    }

    // (5) All-null shortcut: return iff every output reads at least one all-null column.
    // Sound only for null-intolerant outputs - a null-skipping subtree (greatest, IfElse) can
    // be valid over an all-null column - and emitted in the masked driver only (the dense
    // body has nothing null; the loop and tail methods are never called when it fires), and
    // only when every output references a column.
    boolean shortcutApplies = !dense && mode == BodyMode.DRIVER;
    for (VarkaVectorIR root : outputs) {
      shortcutApplies &= analysis.columns.get(root) != 0L && !analysis.skipping.get(root);
    }
    if (shortcutApplies) {
      Label live = cb.newLabel();
      boolean firstOutput = true;
      for (VarkaVectorIR root : outputs) {
        long set = analysis.columns.get(root);
        boolean firstColumn = true;
        for (int i = 0; i < numInputs; i++) {
          if ((set >>> i & 1L) != 0) {
            cb.iload(s.dead[i]);
            if (!firstColumn) {
              cb.ior();
            }
            firstColumn = false;
          }
        }
        if (!firstOutput) {
          cb.iand();
        }
        firstOutput = false;
      }
      cb.ifeq(live);
      cb.return_();
      cb.labelBinding(live);
    }

    // Species, lane count, loop bound, and the hoisted scalar arguments (LICM). The species is
    // read with getstatic so it stays a JIT constant - what lets C2 intrinsify the calls.
    cb.getstatic(INT_VECTOR, "SPECIES_PREFERRED", VECTOR_SPECIES);
    cb.astore(s.species);
    cb.aload(s.species);
    cb.invokeinterface(VECTOR_SPECIES, "length", SPECIES_LENGTH);
    cb.istore(s.lanes);
    cb.aload(s.species);
    cb.iload(P_LENGTH);
    cb.invokeinterface(VECTOR_SPECIES, "loopBound", LOOP_BOUND);
    cb.istore(s.loopBound);
    for (int j = 0; j < numLiterals; j++) {
      cb.aload(P_SCALAR_ARGS);
      cb.loadConstant(j);
      cb.iaload();
      cb.istore(s.scalarArg[j]);
      if (s.broadcastSlot != null) {
        cb.aload(s.species);
        cb.iload(s.scalarArg[j]);
        cb.invokestatic(INT_VECTOR, "broadcast", BROADCAST);
        cb.astore(s.broadcastSlot[j]);
      }
    }

    switch (mode) {
      case DRIVER -> {
        for (int g = 0; g < groups.size(); g++) {
          invokeCall(cb, classDesc, (dense ? "loopDense" : "loopMasked") + g);
        }
        // The rows past loopBound belong to the sibling tail method.
        invokeBody(cb, classDesc, dense ? "tailDense" : "tailMasked");
      }
      case LOOP -> {
        emitVectorLoop(cb, dense, outputs, groups.get(group), analysis, s);
        cb.return_();
      }
      case TAIL -> emitTailLoop(cb, dense, outputs, analysis, s);
    }
  }

  private static void emitVectorLoop(CodeBuilder cb, boolean dense,
      List<VarkaVectorIR> outputs, List<Integer> outputIdx, Analysis analysis, Slots s) {
    int numInputs = analysis.numInputs;

    // (6) The lane-group loop: for (i = 0; i < loopBound; i += lanes).
    cb.loadConstant(0);
    cb.istore(s.iVar);
    Label loopTop = cb.newLabel();
    Label loopEnd = cb.newLabel();
    cb.labelBinding(loopTop);
    cb.iload(s.iVar);
    cb.iload(s.loopBound);
    cb.if_icmpge(loopEnd);

    // byteOffset = (long) i * 4.
    cb.iload(s.iVar);
    cb.i2l();
    cb.loadConstant(4L);
    cb.lmul();
    cb.lstore(s.byteOffset);

    // The columns this loop method can read: the union over its own outputs' subtrees. The
    // kernel-wide referenced set would also be sound but wasteful - the word computation below
    // runs per lane group, and an input only other groups reference has no reader here.
    long groupColumns = 0L;
    for (int o : outputIdx) {
      groupColumns |= analysis.columns.get(outputs.get(o));
    }

    if (!dense) {
      // Each group-referenced input's validity word for this lane group: 0L when all-null, the
      // bitmap bits when it has nulls, -1L when null-free. All three branches leave one long.
      for (int i = 0; i < numInputs; i++) {
        if ((groupColumns >>> i & 1L) == 0) {
          continue;
        }
        Label wNotDead = cb.newLabel();
        Label wNoNulls = cb.newLabel();
        Label wDone = cb.newLabel();
        cb.iload(s.dead[i]);
        cb.ifeq(wNotDead);
        cb.loadConstant(0L);
        cb.goto_(wDone);
        cb.labelBinding(wNotDead);
        cb.iload(s.hasNulls[i]);
        cb.ifeq(wNoNulls);
        cb.aload(s.srcValSeg[i]);
        cb.iload(s.iVar);
        cb.i2l();
        cb.iload(s.lanes);
        cb.invokestatic(SUPPORT, "validityBitsAt", VALIDITY_BITS_AT);
        cb.goto_(wDone);
        cb.labelBinding(wNoNulls);
        cb.loadConstant(-1L);
        cb.labelBinding(wDone);
        cb.lstore(s.word[i]);
      }
    }

    // Each output of this group: the DAG post-order with intermediates on the operand stack
    // (or in a shared node's local), one unmasked store, and this lane group's validity bits -
    // the root's word (all-true when dense), which orValidityBitsAt truncates itself.
    Set<VarkaVectorIR> computed = new HashSet<>();
    for (int o : outputIdx) {
      VarkaVectorIR root = outputs.get(o);
      emitValue(cb, root, dense, analysis, s, computed);
      cb.aload(s.dstSeg[o]);
      cb.lload(s.byteOffset);
      cb.getstatic(BYTE_ORDER, "LITTLE_ENDIAN", BYTE_ORDER);
      cb.invokevirtual(INT_VECTOR, "intoMemorySegment", INTO_MEMORY_SEGMENT_DENSE);
      cb.aload(s.dstValSeg[o]);
      cb.iload(s.iVar);
      cb.i2l();
      if (dense) {
        cb.loadConstant(-1L);
      } else {
        loadWord(cb, s.wordRef.get(root));
      }
      cb.iload(s.lanes);
      cb.invokestatic(SUPPORT, "orValidityBitsAt", OR_VALIDITY_BITS_AT);
    }

    cb.iload(s.iVar);
    cb.iload(s.lanes);
    cb.iadd();
    cb.istore(s.iVar);
    cb.goto_(loopTop);
    cb.labelBinding(loopEnd);
  }

  /**
   * (7) The scalar tail, as its own method body: per row, one topological pass over the
   * distinct nodes - each value (and, masked, each validity bit and condition bit pair)
   * computed once into an int local, mirroring the vector algebra rule for rule - then one
   * guarded write per output. Starts at {@code loopBound}: everything below it belongs to the
   * loop method that called here.
   */
  private static void emitTailLoop(CodeBuilder cb, boolean dense,
      List<VarkaVectorIR> outputs, Analysis analysis, Slots s) {
    int numOutputs = outputs.size();
    cb.iload(s.loopBound);
    cb.istore(s.iVar);
    Label tailTop = cb.newLabel();
    Label tailEnd = cb.newLabel();
    cb.labelBinding(tailTop);
    cb.iload(s.iVar);
    cb.iload(P_LENGTH);
    cb.if_icmpge(tailEnd);
    for (VarkaVectorIR node : analysis.topoOrder) {
      emitTailNode(cb, node, dense, s);
    }
    for (int o = 0; o < numOutputs; o++) {
      VarkaVectorIR root = outputs.get(o);
      Label rowDone = cb.newLabel();
      if (!dense) {
        cb.iload(s.tailValid.get(root));
        cb.ifeq(rowDone);
      }
      cb.aload(s.dstSeg[o]);
      cb.getstatic(VALUE_LAYOUT, "JAVA_INT", VALUE_LAYOUT_OF_INT);
      cb.iload(s.iVar);
      cb.i2l();
      cb.loadConstant(4L);
      cb.lmul();
      tailValue(cb, root, s);
      cb.invokeinterface(MEMORY_SEGMENT, "set", SEGMENT_SET_INT);
      cb.aload(s.dstValSeg[o]);
      cb.iload(s.iVar);
      cb.invokestatic(SUPPORT, "setBit", SET_BIT);
      cb.labelBinding(rowDone);
    }
    cb.iinc(s.iVar, 1);
    cb.goto_(tailTop);
    cb.labelBinding(tailEnd);
    cb.return_();
  }

  /** {@code local = VarkaVectorSupport.ofAddress(param[index], lload(bytes))}. */
  private static void loadSegment(
      CodeBuilder cb, int arrayParam, int index, int bytesSlot, int destSlot) {
    cb.aload(arrayParam);
    cb.loadConstant(index);
    cb.laload();
    cb.lload(bytesSlot);
    cb.invokestatic(SUPPORT, "ofAddress", OF_ADDRESS);
    cb.astore(destSlot);
  }

  /** Pushes a validity word: a long local, or the all-true constant. */
  private static void loadWord(CodeBuilder cb, int ref) {
    if (ref == WORD_ALL_TRUE) {
      cb.loadConstant(-1L);
    } else {
      cb.lload(ref);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The vector walk.
  // ---------------------------------------------------------------------------------------------

  /**
   * Post-order walk leaving the node's {@code IntVector} on the operand stack. A node used
   * more than once is computed at its first (textual) use, duplicated into its local, and
   * later uses load the local - across outputs too, since the loop body is one straight line.
   * In the masked body the node's validity word is stored as a side effect of the first visit.
   */
  private static void emitValue(CodeBuilder cb, VarkaVectorIR node, boolean dense,
      Analysis analysis, Slots s, Set<VarkaVectorIR> computed) {
    Integer shared = s.sharedSlot.get(node);
    if (shared != null && computed.contains(node)) {
      cb.aload(shared);
      return;
    }
    switch (node) {
      case ColumnRef c -> {
        cb.aload(s.species);
        cb.aload(s.srcSeg[c.ordinal()]);
        cb.lload(s.byteOffset);
        cb.getstatic(BYTE_ORDER, "LITTLE_ENDIAN", BYTE_ORDER);
        cb.invokestatic(INT_VECTOR, "fromMemorySegment", FROM_MEMORY_SEGMENT_DENSE);
      }
      case LiteralSlot l -> {
        if (s.broadcastSlot != null) {
          cb.aload(s.broadcastSlot[l.index()]);
        } else {
          cb.aload(s.species);
          cb.iload(s.scalarArg[l.index()]);
          cb.invokestatic(INT_VECTOR, "broadcast", BROADCAST);
        }
      }
      case AddDays n -> {
        emitValue(cb, n.days(), dense, analysis, s, computed);
        emitValue(cb, n.offset(), dense, analysis, s, computed);
        // The misdescribe hook: whichever body executes first must fail naming the call.
        MethodTypeDesc desc = misdescribeAddForTesting ? LANEWISE_VV_WRONG : LANEWISE_VV;
        cb.invokevirtual(INT_VECTOR, "add", desc);
      }
      case SubDays n -> {
        emitValue(cb, n.days(), dense, analysis, s, computed);
        emitValue(cb, n.offset(), dense, analysis, s, computed);
        cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
      }
      case DateDiff n -> {
        emitValue(cb, n.end(), dense, analysis, s, computed);
        emitValue(cb, n.start(), dense, analysis, s, computed);
        cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
        if (!dense && s.ownWord.contains(node)) {
          emitAndWord(cb, s.wordRef.get(node),
              s.wordRef.get(n.end()), s.wordRef.get(n.start()));
        }
      }
      case DayOfWeek n -> {
        emitValue(cb, n.days(), dense, analysis, s, computed);
        emitFloorMod7(cb, node, s);
        emitModOffset(cb, s, 4);
        cb.loadConstant(1);
        cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
      }
      case WeekDay n -> {
        emitValue(cb, n.days(), dense, analysis, s, computed);
        emitFloorMod7(cb, node, s);
        emitModOffset(cb, s, 3);
      }
      case Greatest n -> emitPick(cb, n, n.left(), n.right(), "max", dense, analysis, s,
          computed);
      case Least n -> emitPick(cb, n, n.left(), n.right(), "min", dense, analysis, s,
          computed);
      case IfElse n -> {
        emitCond(cb, n.cond(), dense, analysis, s, computed);
        emitValue(cb, n.elseNode(), dense, analysis, s, computed);
        emitValue(cb, n.thenNode(), dense, analysis, s, computed);
        if (dense) {
          cb.aload(s.condMask.get(n.cond()));
        } else {
          cb.aload(s.species);
          cb.lload(s.kt.get(n.cond()));
          cb.invokestatic(VECTOR_MASK, "fromLong", FROM_LONG);
        }
        cb.invokevirtual(INT_VECTOR, "blend", BLEND);
        if (!dense) {
          // valid = (kT & validThen) | (~kT & validElse), the chosen branch's validity.
          cb.lload(s.kt.get(n.cond()));
          loadWord(cb, s.wordRef.get(n.thenNode()));
          cb.land();
          cb.lload(s.kt.get(n.cond()));
          cb.loadConstant(-1L);
          cb.lxor();
          loadWord(cb, s.wordRef.get(n.elseNode()));
          cb.land();
          cb.lor();
          cb.lstore(s.wordRef.get(node));
        }
      }
      case Cond c -> throw new IllegalStateException(
          "condition node in a value position survived validation: " + c);
    }
    if (shared != null) {
      cb.dup();
      cb.astore(shared);
      computed.add(node);
    }
  }

  /** {@code lstore(own, ref(a) & ref(b))} - the null-intolerant word rule. */
  private static void emitAndWord(CodeBuilder cb, int own, int a, int b) {
    loadWord(cb, a);
    loadWord(cb, b);
    cb.land();
    cb.lstore(own);
  }

  /**
   * The null-skipping {@code greatest}/{@code least}: in the dense body a plain lanewise
   * {@code max}/{@code min}; in the masked body each operand substitutes the other where it is
   * null - {@code aSel = a.blend(b, ~validA)} - which reduces every case (both valid, only A,
   * only B) to the plain op, and {@code valid = validA | validB}.
   */
  private static void emitPick(CodeBuilder cb, VarkaVectorIR node, VarkaVectorIR left,
      VarkaVectorIR right, String op, boolean dense, Analysis analysis, Slots s,
      Set<VarkaVectorIR> computed) {
    if (dense) {
      emitValue(cb, left, dense, analysis, s, computed);
      emitValue(cb, right, dense, analysis, s, computed);
      cb.invokevirtual(INT_VECTOR, op, LANEWISE_VV);
      return;
    }
    int[] tmp = s.pairTmp.get(node);
    emitValue(cb, left, dense, analysis, s, computed);
    cb.astore(tmp[0]);
    emitValue(cb, right, dense, analysis, s, computed);
    cb.astore(tmp[1]);
    cb.aload(tmp[0]);
    cb.aload(tmp[1]);
    cb.aload(s.species);
    loadWord(cb, s.wordRef.get(left));
    cb.loadConstant(-1L);
    cb.lxor();
    cb.invokestatic(VECTOR_MASK, "fromLong", FROM_LONG);
    cb.invokevirtual(INT_VECTOR, "blend", BLEND);
    cb.aload(tmp[1]);
    cb.aload(tmp[0]);
    cb.aload(s.species);
    loadWord(cb, s.wordRef.get(right));
    cb.loadConstant(-1L);
    cb.lxor();
    cb.invokestatic(VECTOR_MASK, "fromLong", FROM_LONG);
    cb.invokevirtual(INT_VECTOR, "blend", BLEND);
    cb.invokevirtual(INT_VECTOR, op, LANEWISE_VV);
    loadWord(cb, s.wordRef.get(left));
    loadWord(cb, s.wordRef.get(right));
    cb.lor();
    cb.lstore(s.wordRef.get(node));
  }

  /**
   * Consumes the child's {@code IntVector} on the stack and leaves {@code floorMod(v, 7)},
   * full range. The shipped variant is the base-8 digit sum (plan 2.3, pre-measured 8x the
   * DIV variant): fold 15-, 6- and 3-bit chunks ({@code 2^(3k) = 1 mod 7}), add 3 where the
   * input is negative ({@code 2^32 = 4 mod 7}; the fold saw the unsigned value), then one
   * compare-subtract fixup - its input peaks at 12, within a single subtraction. The DIV
   * variant behind {@link #divFloorModForTesting} is the tested reference.
   */
  private static void emitFloorMod7(CodeBuilder cb, VarkaVectorIR node, Slots s) {
    int[] tmp = s.dowTmp.get(node);
    int orig = tmp[0];
    int fold = tmp[1];
    cb.astore(orig);
    if (divFloorModForTesting) {
      // r = v - (v / 7) * 7; r += 7 where r < 0.
      cb.aload(orig);
      cb.aload(orig);
      cb.loadConstant(7);
      cb.invokevirtual(INT_VECTOR, "div", LANEWISE_VI);
      cb.loadConstant(7);
      cb.invokevirtual(INT_VECTOR, "mul", LANEWISE_VI);
      cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VV);
      cb.astore(fold);
      cb.aload(fold);
      cb.loadConstant(7);
      cb.aload(fold);
      cb.getstatic(VECTOR_OPERATORS, "LT", VO_COMPARISON);
      cb.loadConstant(0);
      cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
      cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI_MASKED);
      return;
    }
    // Folds: two 15-bit halves, one 6-bit, three 3-bit.
    emitFold(cb, orig, fold, 0x7FFF, 15);
    emitFold(cb, fold, fold, 0x7FFF, 15);
    emitFold(cb, fold, fold, 63, 6);
    emitFold(cb, fold, fold, 7, 3);
    emitFold(cb, fold, fold, 7, 3);
    emitFold(cb, fold, fold, 7, 3);
    // s += 3 where the original value was negative.
    cb.aload(fold);
    cb.loadConstant(3);
    cb.aload(orig);
    cb.getstatic(VECTOR_OPERATORS, "LT", VO_COMPARISON);
    cb.loadConstant(0);
    cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI_MASKED);
    // One conditional subtract lands [0, 12] in [0, 6].
    emitSubSevenWhereGe(cb, s);
  }

  /** {@code dst = src.and(mask).add(src >>> shift)}, all through locals. */
  private static void emitFold(CodeBuilder cb, int src, int dst, int mask, int shift) {
    cb.aload(src);
    cb.loadConstant(mask);
    cb.invokevirtual(INT_VECTOR, "and", LANEWISE_VI);
    cb.aload(src);
    cb.getstatic(VECTOR_OPERATORS, "LSHR", VO_BINARY);
    cb.loadConstant(shift);
    cb.invokevirtual(INT_VECTOR, "lanewise", LANEWISE_BINARY_I);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VV);
    cb.astore(dst);
  }

  /** Consumes nothing: {@code [s] -> [s - 7 where s >= 7]} via one masked subtract. */
  private static void emitSubSevenWhereGe(CodeBuilder cb, Slots s) {
    cb.dup();
    cb.getstatic(VECTOR_OPERATORS, "GE", VO_COMPARISON);
    cb.loadConstant(7);
    cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VI);
    cb.astore(s.maskTmp);
    cb.loadConstant(7);
    cb.aload(s.maskTmp);
    cb.invokevirtual(INT_VECTOR, "sub", LANEWISE_VI_MASKED);
  }

  /** {@code [r] -> [(r + k) mod 7]} for {@code r} in {@code [0, 6]}, {@code k} in 3..4. */
  private static void emitModOffset(CodeBuilder cb, Slots s, int k) {
    cb.loadConstant(k);
    cb.invokevirtual(INT_VECTOR, "add", LANEWISE_VI);
    emitSubSevenWhereGe(cb, s);
  }

  /**
   * Emits a condition node: in the dense body a single {@code VectorMask} local (every input
   * lane is valid, so known-true is the comparison itself and known-false its complement); in
   * the masked body the known-true / known-false word pair of plan 2.6.
   */
  private static void emitCond(CodeBuilder cb, Cond node, boolean dense, Analysis analysis,
      Slots s, Set<VarkaVectorIR> computed) {
    if (computed.contains(node)) {
      return;
    }
    computed.add(node);
    switch (node) {
      case Compare n -> {
        emitValue(cb, n.left(), dense, analysis, s, computed);
        cb.getstatic(VECTOR_OPERATORS, n.op().name(), VO_COMPARISON);
        emitValue(cb, n.right(), dense, analysis, s, computed);
        cb.invokevirtual(INT_VECTOR, "compare", COMPARE_VV);
        if (dense) {
          cb.astore(s.condMask.get(node));
        } else {
          cb.invokevirtual(VECTOR_MASK, "toLong", TO_LONG);
          cb.lstore(s.cmpTmp);
          // kT = cmp & validL & validR; kF = ~cmp & validL & validR.
          cb.lload(s.cmpTmp);
          loadWord(cb, s.wordRef.get(n.left()));
          cb.land();
          loadWord(cb, s.wordRef.get(n.right()));
          cb.land();
          cb.lstore(s.kt.get(node));
          cb.lload(s.cmpTmp);
          cb.loadConstant(-1L);
          cb.lxor();
          loadWord(cb, s.wordRef.get(n.left()));
          cb.land();
          loadWord(cb, s.wordRef.get(n.right()));
          cb.land();
          cb.lstore(s.kf.get(node));
        }
      }
      case And n -> {
        emitCond(cb, n.left(), dense, analysis, s, computed);
        emitCond(cb, n.right(), dense, analysis, s, computed);
        if (dense) {
          cb.aload(s.condMask.get(n.left()));
          cb.aload(s.condMask.get(n.right()));
          cb.invokevirtual(VECTOR_MASK, "and", MASK_BINARY);
          cb.astore(s.condMask.get(node));
        } else {
          cb.lload(s.kt.get(n.left()));
          cb.lload(s.kt.get(n.right()));
          cb.land();
          cb.lstore(s.kt.get(node));
          cb.lload(s.kf.get(n.left()));
          cb.lload(s.kf.get(n.right()));
          cb.lor();
          cb.lstore(s.kf.get(node));
        }
      }
      case Or n -> {
        emitCond(cb, n.left(), dense, analysis, s, computed);
        emitCond(cb, n.right(), dense, analysis, s, computed);
        if (dense) {
          cb.aload(s.condMask.get(n.left()));
          cb.aload(s.condMask.get(n.right()));
          cb.invokevirtual(VECTOR_MASK, "or", MASK_BINARY);
          cb.astore(s.condMask.get(node));
        } else {
          cb.lload(s.kt.get(n.left()));
          cb.lload(s.kt.get(n.right()));
          cb.lor();
          cb.lstore(s.kt.get(node));
          cb.lload(s.kf.get(n.left()));
          cb.lload(s.kf.get(n.right()));
          cb.land();
          cb.lstore(s.kf.get(node));
        }
      }
      case Not n -> {
        emitCond(cb, n.child(), dense, analysis, s, computed);
        if (dense) {
          cb.aload(s.condMask.get(n.child()));
          cb.invokevirtual(VECTOR_MASK, "not", MASK_UNARY);
          cb.astore(s.condMask.get(node));
        }
        // Masked: kT/kF are the child's, swapped - pure slot aliasing, planned, no code.
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The scalar tail.
  // ---------------------------------------------------------------------------------------------

  /** Pushes a node's tail value: an int local, or the scalar argument for a literal. */
  private static void tailValue(CodeBuilder cb, VarkaVectorIR node, Slots s) {
    if (node instanceof LiteralSlot l) {
      cb.iload(s.scalarArg[l.index()]);
    } else {
      cb.iload(s.tailVal.get(node));
    }
  }

  /** Pushes a node's tail validity bit; literals are always valid. */
  private static void tailValid(CodeBuilder cb, VarkaVectorIR node, Slots s) {
    if (node instanceof LiteralSlot) {
      cb.loadConstant(1);
    } else {
      cb.iload(s.tailValid.get(node));
    }
  }

  /** Emits one row's slot computations for one distinct node, in topological order. */
  private static void emitTailNode(CodeBuilder cb, VarkaVectorIR node, boolean dense, Slots s) {
    switch (node) {
      case ColumnRef c -> {
        cb.aload(s.srcSeg[c.ordinal()]);
        cb.getstatic(VALUE_LAYOUT, "JAVA_INT", VALUE_LAYOUT_OF_INT);
        cb.iload(s.iVar);
        cb.i2l();
        cb.loadConstant(4L);
        cb.lmul();
        cb.invokeinterface(MEMORY_SEGMENT, "get", SEGMENT_GET_INT);
        cb.istore(s.tailVal.get(node));
        if (!dense) {
          // valid = !dead && (!hasNulls || isBitSet).
          Label invalid = cb.newLabel();
          Label validYes = cb.newLabel();
          Label done = cb.newLabel();
          cb.iload(s.dead[c.ordinal()]);
          cb.ifne(invalid);
          cb.iload(s.hasNulls[c.ordinal()]);
          cb.ifeq(validYes);
          cb.aload(s.srcValSeg[c.ordinal()]);
          cb.iload(s.iVar);
          cb.invokestatic(SUPPORT, "isBitSet", IS_BIT_SET);
          cb.ifeq(invalid);
          cb.labelBinding(validYes);
          cb.loadConstant(1);
          cb.goto_(done);
          cb.labelBinding(invalid);
          cb.loadConstant(0);
          cb.labelBinding(done);
          cb.istore(s.tailValid.get(node));
        }
      }
      case LiteralSlot l -> {
        // Inlined at use sites.
      }
      case AddDays n -> tailBinaryArith(cb, node, n.days(), n.offset(), true, dense, s);
      case SubDays n -> tailBinaryArith(cb, node, n.days(), n.offset(), false, dense, s);
      case DateDiff n -> tailBinaryArith(cb, node, n.end(), n.start(), false, dense, s);
      case DayOfWeek n -> tailDow(cb, node, n.days(), 4, true, dense, s);
      case WeekDay n -> tailDow(cb, node, n.days(), 3, false, dense, s);
      case Greatest n -> tailPick(cb, node, n.left(), n.right(), "max", dense, s);
      case Least n -> tailPick(cb, node, n.left(), n.right(), "min", dense, s);
      case IfElse n -> {
        Label useElse = cb.newLabel();
        Label done = cb.newLabel();
        cb.iload(s.tailKt.get(n.cond()));
        cb.ifeq(useElse);
        tailValue(cb, n.thenNode(), s);
        cb.goto_(done);
        cb.labelBinding(useElse);
        tailValue(cb, n.elseNode(), s);
        cb.labelBinding(done);
        cb.istore(s.tailVal.get(node));
        if (!dense) {
          Label vElse = cb.newLabel();
          Label vDone = cb.newLabel();
          cb.iload(s.tailKt.get(n.cond()));
          cb.ifeq(vElse);
          tailValid(cb, n.thenNode(), s);
          cb.goto_(vDone);
          cb.labelBinding(vElse);
          tailValid(cb, n.elseNode(), s);
          cb.labelBinding(vDone);
          cb.istore(s.tailValid.get(node));
        }
      }
      case Compare n -> {
        // cmp as 0/1 first.
        Label isTrue = cb.newLabel();
        Label done = cb.newLabel();
        tailValue(cb, n.left(), s);
        tailValue(cb, n.right(), s);
        switch (n.op()) {
          case LT -> cb.if_icmplt(isTrue);
          case LE -> cb.if_icmple(isTrue);
          case GT -> cb.if_icmpgt(isTrue);
          case GE -> cb.if_icmpge(isTrue);
          case EQ -> cb.if_icmpeq(isTrue);
        }
        cb.loadConstant(0);
        cb.goto_(done);
        cb.labelBinding(isTrue);
        cb.loadConstant(1);
        cb.labelBinding(done);
        cb.istore(s.tailKt.get(node));
        if (!dense) {
          // known = validL & validR; kT = known & cmp; kF = known & !cmp.
          tailValid(cb, n.left(), s);
          tailValid(cb, n.right(), s);
          cb.iand();
          cb.dup();
          cb.iload(s.tailKt.get(node));
          cb.loadConstant(1);
          cb.ixor();
          cb.iand();
          cb.istore(s.tailKf.get(node));
          cb.iload(s.tailKt.get(node));
          cb.iand();
          cb.istore(s.tailKt.get(node));
        }
      }
      case And n -> {
        cb.iload(s.tailKt.get(n.left()));
        cb.iload(s.tailKt.get(n.right()));
        cb.iand();
        cb.istore(s.tailKt.get(node));
        if (!dense) {
          cb.iload(s.tailKf.get(n.left()));
          cb.iload(s.tailKf.get(n.right()));
          cb.ior();
          cb.istore(s.tailKf.get(node));
        }
      }
      case Or n -> {
        cb.iload(s.tailKt.get(n.left()));
        cb.iload(s.tailKt.get(n.right()));
        cb.ior();
        cb.istore(s.tailKt.get(node));
        if (!dense) {
          cb.iload(s.tailKf.get(n.left()));
          cb.iload(s.tailKf.get(n.right()));
          cb.iand();
          cb.istore(s.tailKf.get(node));
        }
      }
      case Not n -> {
        if (dense) {
          cb.iload(s.tailKt.get(n.child()));
          cb.loadConstant(1);
          cb.ixor();
          cb.istore(s.tailKt.get(node));
        } else {
          cb.iload(s.tailKf.get(n.child()));
          cb.istore(s.tailKt.get(node));
          cb.iload(s.tailKt.get(n.child()));
          cb.istore(s.tailKf.get(node));
        }
      }
    }
  }

  private static void tailBinaryArith(CodeBuilder cb, VarkaVectorIR node, VarkaVectorIR left,
      VarkaVectorIR right, boolean add, boolean dense, Slots s) {
    tailValue(cb, left, s);
    tailValue(cb, right, s);
    if (add) {
      cb.iadd();
    } else {
      cb.isub();
    }
    cb.istore(s.tailVal.get(node));
    if (!dense) {
      tailValid(cb, left, s);
      tailValid(cb, right, s);
      cb.iand();
      cb.istore(s.tailValid.get(node));
    }
  }

  private static void tailDow(CodeBuilder cb, VarkaVectorIR node, VarkaVectorIR child,
      int offset, boolean plusOne, boolean dense, Slots s) {
    // r = Math.floorMod(v, 7) + offset; if (r >= 7) r -= 7; then + 1 for dayofweek.
    tailValue(cb, child, s);
    cb.loadConstant(7);
    cb.invokestatic(MATH, "floorMod", MATH_II_I);
    cb.loadConstant(offset);
    cb.iadd();
    Label small = cb.newLabel();
    cb.dup();
    cb.loadConstant(7);
    cb.if_icmplt(small);
    cb.loadConstant(7);
    cb.isub();
    cb.labelBinding(small);
    if (plusOne) {
      cb.loadConstant(1);
      cb.iadd();
    }
    cb.istore(s.tailVal.get(node));
    if (!dense) {
      tailValid(cb, child, s);
      cb.istore(s.tailValid.get(node));
    }
  }

  private static void tailPick(CodeBuilder cb, VarkaVectorIR node, VarkaVectorIR left,
      VarkaVectorIR right, String op, boolean dense, Slots s) {
    if (dense) {
      tailValue(cb, left, s);
      tailValue(cb, right, s);
      cb.invokestatic(MATH, op, MATH_II_I);
      cb.istore(s.tailVal.get(node));
      return;
    }
    Label useRight = cb.newLabel();
    Label useLeft = cb.newLabel();
    Label done = cb.newLabel();
    tailValid(cb, left, s);
    cb.ifeq(useRight);
    tailValid(cb, right, s);
    cb.ifeq(useLeft);
    tailValue(cb, left, s);
    tailValue(cb, right, s);
    cb.invokestatic(MATH, op, MATH_II_I);
    cb.goto_(done);
    cb.labelBinding(useLeft);
    tailValue(cb, left, s);
    cb.goto_(done);
    cb.labelBinding(useRight);
    tailValue(cb, right, s);
    cb.labelBinding(done);
    cb.istore(s.tailVal.get(node));
    tailValid(cb, left, s);
    tailValid(cb, right, s);
    cb.ior();
    cb.istore(s.tailValid.get(node));
  }
}
