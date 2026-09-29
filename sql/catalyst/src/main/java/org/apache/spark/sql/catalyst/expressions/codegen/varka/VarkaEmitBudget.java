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
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.AddMonths;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Chrono;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConstDivide;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DayOfWeekIso;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.DayOfYear;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.InRanges;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntNeg;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LastDay;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LiteralSlot;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.MakeDate;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.NextDay;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ThursdayOf;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.TruncDate;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.TruncDateDynamic;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WeekOfYear;

/**
 * What an emitted loop method may carry and what each node costs against it: the group budget
 * and the fused ceiling, the per-lowering weights the calendar nodes and the divisions are
 * priced at (each calendar weight a shared civil-from-days prefix plus the node's own tail),
 * the temporary-slot counts those lowerings reserve, and {@link #weightOf}, which prices a node
 * for the grouping pass. The emitter imports these statically; the entry the compiler asks,
 * {@code VarkaLoopEmitter.fitsBudgets}, stays on the emitter.
 *
 * <p>Weight is a proxy. The quantities the JVM actually enforces - a method's bytecode length,
 * a class's constant pool, a method's parameter slots - are the limits at the top of this
 * class, and {@link #overLimits} reads an emitted class against them ({@code PLAN_TASK_87.md}).
 * One more is C1's rather than the class file's: the Vector API call sites a group method
 * carries, bounded by {@link #CALL_SITE_BUDGET} and read by {@link #groupsOverCallSites}
 * ({@code PLAN_TASK_209.md}).
 */
final class VarkaEmitBudget {

  private VarkaEmitBudget() {
  }

  // ---------------------------------------------------------------------------------------------
  // What the JVM enforces: bytes and entries, not weight.
  // ---------------------------------------------------------------------------------------------

  /**
   * The bytecode length past which HotSpot never compiles a method, at any tier. It is
   * {@code HugeMethodLimit}, a develop-only flag fixed at 8000 in a product build, applied
   * whenever {@code DontCompileHugeMethods} is on, which it is by default. A method over it is
   * not an error: it loads, verifies and runs - interpreted, with every vector operation boxed,
   * for the life of the JVM, and nothing reports that it did. This is the limit that bites
   * first. Two more sit below it and are not limits in the same sense: C1 refuses a method past
   * a count of Vector API call sites ("out of virtual registers"), which is register pressure
   * and only correlates with bytes, so such a method is interpreted until C2 compiles it - the
   * budget {@link #CALL_SITE_BUDGET} holds a wide group's methods under that count; and a loop
   * reaches C2 quickly through its backedges where a method with no loop, such as an epilogue,
   * reaches it only by invocation count. See {@code PLAN_TASK_87.md} 2.3 and 2.6.5.
   */
  static final int HUGE_METHOD_LIMIT = 8000;

  /**
   * The class-file format's cap on one method's bytecode length: a {@code u2} of code bytes. The
   * Class-File API enforces it while a class is assembled, by refusing the build, so a method
   * over it is never in a class {@link VarkaEmittedClass#measure} could read; the emitter reads
   * the refusal instead ({@link VarkaEmittedClass#refused}) and declines on it.
   */
  static final int METHOD_CODE_CAP = 65535;

  /**
   * The class-file format's cap on {@code constant_pool_count}. The count includes the unused
   * slot zero, which is why {@link VarkaEmittedClass#constantPoolCount} reports it that way. A
   * kernel of many distinct literals, divisors and magic constants approaches this from a
   * direction method length never does, which is why it is counted separately.
   */
  static final int CONSTANT_POOL_CAP = 65535;

  /** The JVM's cap on a method's parameter slots, {@code this} included. */
  static final int PARAMETER_SLOT_CAP = 255;

  /**
   * Every way an emitted class is over what the JVM enforces, one sentence each, naming the
   * method and the number; empty when the class is within every limit. A method over
   * {@link #HUGE_METHOD_LIMIT} is reported although it would load, because it would never be
   * compiled, and an emitted method that is never compiled is the failure this class is here
   * to make visible. The tools print this list; the emitter acts on the form below, measured
   * against the budget it was given.
   */
  static List<String> overLimits(VarkaEmittedClass emitted) {
    return overLimits(emitted, HUGE_METHOD_LIMIT);
  }

  /**
   * As above, with {@code methodLimit} in place of {@link #HUGE_METHOD_LIMIT}: the emitter
   * measures against {@link VarkaEmitOptions#methodByteBudget}, which is that limit in
   * production and a small number in a test that wants to see a regroup or a decline on a
   * shape of a few outputs.
   */
  static List<String> overLimits(VarkaEmittedClass emitted, int methodLimit) {
    List<String> findings = new ArrayList<>();
    for (Map.Entry<String, Integer> e : emitted.codeLength().entrySet()) {
      if (e.getValue() > METHOD_CODE_CAP) {
        findings.add(e.getKey() + " is " + e.getValue() + " bytes, over the class-file cap of "
            + METHOD_CODE_CAP + ": the class cannot be built");
      } else if (e.getValue() > methodLimit) {
        findings.add(e.getKey() + " is " + e.getValue() + " bytes, over the method budget of "
            + methodLimit + (methodLimit == HUGE_METHOD_LIMIT
                ? " (HugeMethodLimit): HotSpot never compiles it" : ""));
      }
    }
    for (Map.Entry<String, Integer> e : emitted.parameterSlots().entrySet()) {
      if (e.getValue() > PARAMETER_SLOT_CAP) {
        findings.add(e.getKey() + " takes " + e.getValue() + " parameter slots, over the cap of "
            + PARAMETER_SLOT_CAP);
      }
    }
    if (emitted.constantPoolCount() > CONSTANT_POOL_CAP) {
      findings.add("the constant pool has " + emitted.constantPoolCount()
          + " entries, over the cap of " + CONSTANT_POOL_CAP);
    }
    return findings;
  }

  /**
   * The group a loop or epilogue method belongs to, read off its name ({@code loopMasked3},
   * {@code epilogueDense12}), or -1 for a method that is not a group's: the drivers, the
   * dispatcher, the constructor, and the legacy form's single epilogue.
   */
  static int groupOf(String method) {
    if (!method.startsWith("loop") && !method.startsWith("epilogue")) {
      return -1;
    }
    int i = method.length();
    while (i > 0 && Character.isDigit(method.charAt(i - 1))) {
      i--;
    }
    return i == method.length() ? -1 : Integer.parseInt(method.substring(i));
  }

  /**
   * The groups with a method over {@code methodLimit}, each with its largest such method:
   * what the emitter's regroup splits. A group's methods are its loop and its epilogue on
   * each side, so the largest of the four decides.
   */
  static SortedMap<Integer, Map.Entry<String, Integer>> groupsOver(VarkaEmittedClass emitted,
      int methodLimit) {
    return groupsOver(emitted.codeLength(), methodLimit);
  }

  /**
   * {@link #groupsOver} over any per-method measure: the groups with a method whose
   * {@code measure} is over {@code limit}, each with its largest such method. The byte budget
   * reads the code lengths through it, the call-site budget the vector call sites.
   */
  private static SortedMap<Integer, Map.Entry<String, Integer>> groupsOver(
      Map<String, Integer> measure, int limit) {
    SortedMap<Integer, Map.Entry<String, Integer>> over = new TreeMap<>();
    for (Map.Entry<String, Integer> e : measure.entrySet()) {
      int g = groupOf(e.getKey());
      if (g >= 0 && e.getValue() > limit
          && (!over.containsKey(g) || over.get(g).getValue() < e.getValue())) {
        over.put(g, e);
      }
    }
    return over;
  }

  // ---------------------------------------------------------------------------------------------
  // What C1 enforces: a group method's Vector API call sites.
  // ---------------------------------------------------------------------------------------------

  /**
   * The most Vector API call sites one emitted group method - a loop method, or the epilogue
   * beside it - carries by default: the invocations of {@code IntVector} and the other vector
   * classes in its body, as {@link VarkaEmittedClass#vectorCallSites} counts them.
   *
   * <p><b>Why methods are bounded in this unit.</b> C1 compiles a method by inlining every
   * Vector API call into its own intermediate form, and it refuses a method whose form needs
   * more virtual registers than it has ("out of virtual registers in LIR generator"). The
   * refusal follows the count of vector call sites far better than the method's bytes, and it
   * is costly. A loop method C1 refuses runs interpreted, boxing every vector, for seconds
   * until C2 compiles it, and in some JVMs then enters the deoptimization cycle for good; an
   * epilogue C1 refuses has no back edge to reach C2 by, so it runs interpreted on every batch
   * that leaves a remainder until invocation counts bring C2. A method under C1's limit is
   * compiled at once and runs at speed from its first second. See {@code PLAN_TASK_209.md} 9
   * and 10.
   *
   * <p><b>What the count is not.</b> It is C1's register need by proxy. It leaves out the
   * mask, shuffle and species calls and the {@code VarkaVectorSupport} helpers, which C1 inlines
   * too, and a site's cost differs by shape: a {@code make_date} producer compiles at a count
   * the cheap tails are refused at. The value was read on the cheap tails, at the 512-bit and
   * 128-bit species, on JDK 25; a mask-heavy wide group, such as a split-condition filter's, and
   * the 256-bit species are uncalibrated ({@code SCOPE_MILESTONE_7.md} item 61).
   *
   * <p><b>What the budget does.</b> The emitter reads each group method's count off the built
   * class, beside its bytes, and a group whose loop or epilogue is over the budget is split
   * through the byte budget's regroup ({@code VarkaLoopEmitter.emit}) while it holds more than
   * {@link #HEAVY_GROUP_OUTPUTS} outputs. It never costs a kernel: a group it cannot bring
   * under stands, since a method C2 compiles in seconds still beats the row fallback, and a
   * class its splits would make decline - the driver gains a call per group - is built again
   * without them.
   *
   * <p><b>The value.</b> The last count C1 compiled, six under the first it refused
   * ({@code PLAN_TASK_209.md} 10.1). An emit option ({@link VarkaEmitOptions#callSiteBudget}, 0
   * for off), so a retune is priced rather than argued and the arm without it stays measurable;
   * {@code VarkaInliningCliffSuite} is the guard that says when a JDK moves the boundary.
   */
  public static final int CALL_SITE_BUDGET = 93;

  /**
   * The most outputs a group may hold and still keep a method over {@link #CALL_SITE_BUDGET}:
   * the call-site budget splits a group only while it holds more outputs than this.
   *
   * <p>A narrow group over the budget is a group of heavy outputs - one {@code make_date} with
   * its prefix is at the budget alone, the size ladder's entry is past it - and no split brings
   * such outputs under C1. Splitting them to one output a method would only add a call, a loop
   * and the prefix's loads per method per batch, at steady state and for good. What a narrow
   * group past C1 risks is its wait for C2, and the census found that bounded: narrow groups
   * settled within seconds and did not cycle, where wide groups of cheap outputs past C1 did.
   * So the budget bounds the wide groups and leaves the narrow ones. Six is the most outputs the
   * fused ceiling packs of the heaviest calendar nodes. See {@code PLAN_TASK_209.md} 11.2 and
   * 11.3. The option is {@link VarkaEmitOptions#heavyGroupOutputs}, where 0 and 1 alike split
   * every group over the budget down to single outputs.
   */
  public static final int HEAVY_GROUP_OUTPUTS = 6;

  /**
   * The groups with a loop or epilogue method over {@code callSiteBudget} vector call sites,
   * each with its largest such method: what the emitter's regroup splits on the call-site
   * budget. A group's epilogue carries about its loop's count, so the larger of the two
   * decides, as it does for the bytes.
   */
  static SortedMap<Integer, Map.Entry<String, Integer>> groupsOverCallSites(
      VarkaEmittedClass emitted, int callSiteBudget) {
    return groupsOver(emitted.vectorCallSites(), callSiteBudget);
  }

  /**
   * Every group method over {@code callSiteBudget}, one sentence each, naming the method and
   * the count; empty when every one is under the budget. For the tools, beside
   * {@link #overLimits}: such a method is what the emitter leaves when its group is a narrow one
   * or splitting it would have declined the class, and it is worth seeing, since C1 refuses it
   * and it runs interpreted until C2 compiles it.
   */
  static List<String> overCallSiteBudget(VarkaEmittedClass emitted, int callSiteBudget) {
    List<String> findings = new ArrayList<>();
    for (Map.Entry<String, Integer> e : emitted.vectorCallSites().entrySet()) {
      if (groupOf(e.getKey()) >= 0 && e.getValue() > callSiteBudget) {
        findings.add(e.getKey() + " carries " + e.getValue() + " vector call sites, over the "
            + "call-site budget of " + callSiteBudget + ": C1 refuses it, so it runs "
            + "interpreted until C2 compiles it");
      }
    }
    return findings;
  }

  /**
   * The deepest op path (root to leaf, per output) the emitter accepts, fixed by measurement
   * (VarkaEmitterParityBenchmark; details in PLAN_TASK_9.md): fused throughput declines only
   * gently with depth while sequential passes collapse linearly, so the cap bounds emitted
   * method size and register pressure by policy, well past any depth a real projection
   * produces, rather than marking a measured performance edge. Condition nodes count.
   */
  public static final int MAX_CHAIN_DEPTH = 16;

  /**
   * The most distinct op nodes one emitted kernel may hold, across all outputs after CSE, in the
   * form without a byte budget ({@code methodByteBudget} 0). Depth alone does not bound method
   * size once outputs multiply, and that form has nothing else that does, so this is its
   * total-size bound. Under the byte budget - the default - it does not apply: every method is
   * measured in bytes and regrouped until it fits, and a kernel no regroup can fit (a driver past
   * the budget, which grows with the outputs) is declined with a reason, at plan time. The cap had
   * been a proxy for that size, and it admitted a quarter of the entries a real wide projection
   * has ({@code PLAN_TASK_190.md} 1).
   */
  public static final int MAX_FUSED_NODES = 64;

  /**
   * The most input columns one emitted loop may read. A node's referenced-column set is a long
   * bitset, which fixes the representation limit at 64; real projections reference a handful.
   */
  public static final int MAX_INPUTS = 64;

  /**
   * The most op nodes one emitted <i>loop method</i> carries. Outputs are partitioned into
   * sibling loop methods within this budget.
   *
   * <p><b>Why bound a method at all.</b> Past a count of vector call sites C1 refuses a loop
   * method and it runs interpreted until C2 lands, so a method that grows without limit has a
   * window in which it is very slow; {@link #CALL_SITE_BUDGET} now bounds that count for wide
   * groups, and this weight budget is the coarser bound that runs before anything is built.
   * Keeping methods small also keeps each one's C2 node and inlining budgets to itself, so no
   * method's size can cost another its intrinsics.
   *
   * <p><b>Why 16 rather than more.</b> Of nine shapes surveyed across budgets from 16 to 64,
   * three regroup at all and only one above 24 - and that one saves a single lane op out of
   * 38. Raising the budget therefore buys almost nothing while growing every method toward
   * C1's refusal threshold. See {@code PLAN_TASK_71.md} 10.5.
   *
   * <p><b>Grouping.</b> Greedy over the output order, counting only nodes new to the group, so
   * outputs sharing subtrees tend to land together and keep their cross-output CSE. A single
   * output wider than the budget gets its own group untouched: splitting inside one output
   * would forfeit the register residency that is the point of fusing at all.
   *
   * <p><b>Two exceptions let a method exceed this budget</b>, both because the wider method is
   * less work rather than more. An output that reuses a civil-from-days prefix the group
   * already computes may join up to {@link #FUSED_CEILING}, since skipping the prefix is a
   * saving; and under {@link VarkaEmitOptions#shareWholeNodes} an output that reuses whole
   * nodes may join too. {@code groupOutputs} implements both.
   *
   * <p><b>What this bound is not.</b> It is not a compile-time bound. C2's compile time is
   * roughly linear in op count - about 1.1 ms per op at AVX-512, measured on a ladder from 20
   * to 248 ops - so even the widest method compiles in a few hundred milliseconds. A loop that
   * appears to stall for seconds is a compile task queued behind others under load, which is a
   * scheduling property no per-method budget can bound. That measurement is one host, one JDK
   * and one shape family, so it does not license "no cliff exists": a lowering with more live
   * values per op could still spill. See {@code PLAN_TASK_43.md} 8, and {@code SKILLS.md} for
   * the three-width ladder.
   *
   * <p>The parity benchmark keeps the split and merged forms of the same shape side by side,
   * so a future retune of this constant is measured rather than argued.
   */
  public static final int GROUP_BUDGET = 16;

  /**
   * The most op nodes one emitted loop method carries when its outputs share a civil-from-days
   * prefix. {@link #GROUP_BUDGET} is the bound on a method whose outputs share nothing but whole
   * nodes; an output whose calendar prefix a group already computes joins that group past the
   * budget and up to this, because joining lets it skip emitting that prefix - the one situation
   * where a wider method is strictly less work rather than a trade (see {@code groupOutputs}). Set
   * by the ladder in {@code PLAN_TASK_32.md} section 7.6: one method kept winning through twelve
   * outputs (700 ops) at both widths, so the bound comes from compile time - an eight-output method
   * of 376 ops has every method at tier 4 within 894 ms of its first compile, the twelve-output one
   * takes 1.9 s, and the rule was one second. A method near this ceiling is past what C1
   * compiles, so it would run interpreted until C2 lands; {@link #CALL_SITE_BUDGET} splits
   * such a group after the class is measured when it is a wide one, which is why the ceiling
   * can stay a bound on the prefix sharing alone. An emit option
   * ({@link VarkaEmitOptions#fusedCeiling}) so a retune is priced rather than argued.
   */
  public static final int FUSED_CEILING = 400;

  // ---------------------------------------------------------------------------------------------
  // What a calendar node weighs: one shared civil-from-days prefix plus the node's own tail.
  // ---------------------------------------------------------------------------------------------

  /**
   * What the civil-from-days prefix costs: the {@code IntVector} ops {@code emitChronoPrefix}
   * emits for one date - 31 with the March-month step, 29 where the month step is elided because no
   * tail in the group reads the month. The weight is a shape property, so it takes the full form.
   *
   * <p>This is the part of a calendar node's weight that a loop method pays <i>once</i>, however
   * many calendar outputs over the same date it holds (see {@code groupOutputs}); each node's
   * {@code *_TAIL_WEIGHT} below is what it pays per output. Every calendar weight is written as the
   * sum of the two, so the split {@code addOps} counts with and the total {@link #weightOf}
   * reports cannot drift apart.
   *
   * <p>How the register was taken, so the next recount does it the same way: every node was
   * emitted alone and beside {@code month(d)} in one loop method ({@code dev/varka_emit.sh
   * "month(d)" "<node>" --options groupBudget=200}), and the pair's {@code loopDense0} count
   * minus {@code month(d)}'s own (35) is the node's tail; {@code dayofmonth(d)} alone (36)
   * minus its tail (5) is the prefix. {@code VarkaEmitterBudgetSuite} pins every line of the
   * register against the emitted bytes, so a lowering change that moves a count fails there
   * rather than leaving a weight to drift.
   */
  static final int CHRONO_PREFIX_WEIGHT = 31;

  /**
   * What a calendar node weighs in place of {@link #CHRONO_PREFIX_WEIGHT} when its prefix is
   * materialized by an earlier group (task 198): the loads of the prefix's six vectors from the
   * scratch region, one operation each, where the prefix itself is thirty-one.
   */
  static final int CHRONO_PREFIX_LOAD_WEIGHT = 6;

  /**
   * The four task-26 fields' tails - {@code year} 5, {@code month} 4, {@code dayofmonth} 5,
   * {@code quarter} 7 - as one constant at the widest, since the four share
   * {@link #CHRONO_WEIGHT} and a two-op difference decides no grouping.
   */
  static final int CHRONO_FIELD_TAIL_WEIGHT = 7;

  /**
   * What {@code Year}/{@code Month}/{@code DayOfMonth}/{@code Quarter} weigh against
   * {@link #GROUP_BUDGET}: the prefix plus the field's own short tail. It exceeds the budget,
   * so a calendar output never joins a group under clause 1 of {@code groupOutputs} - it joins
   * one under clause 2, by reusing the prefix, or forms its own.
   *
   * <p>History, because the number has moved with the lowering and will again: 50 (rounded to the
   * nearest ten, when it only had to exceed the budget), then 40, and the exact 38 since the
   * shared-prefix rule made the tails bound a method against {@link #FUSED_CEILING}.
   */
  static final int CHRONO_WEIGHT = CHRONO_PREFIX_WEIGHT + CHRONO_FIELD_TAIL_WEIGHT;

  /**
   * {@code DayOfYear}'s tail: {@code emitChronoYear} (6), {@code emitLeapFlag} (4)
   * and the January-based blend, 14 in all. Its prefix elides the month step, which is why the
   * node alone emits 43 rather than 45.
   *
   * <p>This tail has been 73, then 55, then 51 as a whole-node weight, and twice out of three times
   * the leap flag was the reason: the task first shipped its own leap test (19 ops), replaced it
   * with the shared one (22), and both are gone - the helper is Huffner's perfect hash at 4 ops.
   */
  static final int DAY_OF_YEAR_TAIL_WEIGHT = 14;
  static final int DAY_OF_YEAR_WEIGHT = CHRONO_PREFIX_WEIGHT + DAY_OF_YEAR_TAIL_WEIGHT;

  /** {@code LastDay}'s tail: the month's start and the next month's, clamped, and
   * the blended length. */
  static final int LAST_DAY_TAIL_WEIGHT = 32;
  static final int LAST_DAY_WEIGHT = CHRONO_PREFIX_WEIGHT + LAST_DAY_TAIL_WEIGHT;

  /**
   * {@code AddMonths}'s tail: the month arithmetic, the day clamp and
   * {@code emitDaysFromCivil}'s recompose. By far the heaviest tail, which is what makes it
   * the node that decides how many outputs {@link #FUSED_CEILING} admits - the four fields
   * together weigh less than one of these. It used to borrow {@link #CHRONO_WEIGHT} on the
   * argument that both only had to exceed the budget; under B2 the tail is summed against the
   * ceiling, so it is counted.
   */
  static final int ADD_MONTHS_TAIL_WEIGHT = 81;
  static final int ADD_MONTHS_WEIGHT = CHRONO_PREFIX_WEIGHT + ADD_MONTHS_TAIL_WEIGHT;

  /**
   * How many int-vector/mask locals {@code emitChronoPrefix} leaves its results in: six
   * vectors - the biased day, era, day of era (later day of year), century, year of century
   * and the March-based month - and two masks the carries use as scratch. They are the
   * fragment's, in the sense of {@link FragmentKey}: a node that shares the prefix reads these
   * very locals instead of re-deriving them.
   */
  static final int CHRONO_PREFIX_SLOTS = 8;

  /**
   * How many int-vector/mask locals {@code emitAddMonths} needs: the
   * {@link #CHRONO_PREFIX_SLOTS} {@code emitChronoPrefix} already uses, three more to hold
   * the decomposed year/month/day, and the rest for the month arithmetic and the
   * {@code days_from_civil} recompose. What it costs in ops is {@link #ADD_MONTHS_WEIGHT}.
   */
  static final int ADD_MONTHS_TMP_COUNT = 31;

  /**
   * How many int-vector/mask locals {@code emitChronoLastDay} needs: the
   * {@link #CHRONO_PREFIX_SLOTS} {@code emitChronoPrefix} already uses, plus the reported
   * year, the day of month, the current month's start and the next one's (clamped, per
   * {@code emitMonthStart}'s own exact-range precondition), and the blended length.
   *
   * <p>This was 19 while {@code emitLeapFlag} needed five scratch locals threaded in for
   * February's own branch. It is now a perfect hash taking only the year, so those five are
   * gone along with the parameters that carried them.
   */
  static final int LAST_DAY_TMP_COUNT = 14;

  /**
   * How many int-vector/mask locals {@code emitChronoTrunc} needs, whichever level and form:
   * the {@link #CHRONO_PREFIX_SLOTS}, then the reported year, the month, the day, the
   * January-based day of year and the quarter start for the subtract form, and the eleven
   * scratch locals {@code emitDaysFromCivil} takes for the recompose form - fresh named slots
   * rather than a reuse of the prefix's scratch, which is the lesson {@code PLAN_TASK_36.md}
   * recorded after doing it the other way first. Sized for the widest case so the slot plan
   * does not depend on the option.
   */
  static final int TRUNC_DATE_TMP_COUNT = 24;

  /**
   * {@code TruncDate}'s tails, under the shipped subtract form: {@code YEAR} is the
   * day-of-year tail plus the two-op subtraction (16; its prefix elides the month step, so the
   * node alone emits 45), {@code MONTH} is the day-of-month tail with its final increment
   * removed and one subtraction added (5), {@code QUARTER} adds the month and quarter steps
   * and the four-way start select (31). The recompose form is heavier and is not the default;
   * a weight is a shape property and does not follow the option.
   */
  static final int TRUNC_YEAR_TAIL_WEIGHT = 16;
  static final int TRUNC_MONTH_TAIL_WEIGHT = 5;
  static final int TRUNC_QUARTER_TAIL_WEIGHT = 31;
  static final int TRUNC_YEAR_WEIGHT = CHRONO_PREFIX_WEIGHT + TRUNC_YEAR_TAIL_WEIGHT;
  static final int TRUNC_MONTH_WEIGHT = CHRONO_PREFIX_WEIGHT + TRUNC_MONTH_TAIL_WEIGHT;
  static final int TRUNC_QUARTER_WEIGHT = CHRONO_PREFIX_WEIGHT + TRUNC_QUARTER_TAIL_WEIGHT;

  /**
   * {@link TruncDateDynamic}'s tail: the row picks its period after the fact, so the
   * tail computes all four results - the {@code QUARTER} tail, which contains the
   * {@code YEAR}'s; the {@code MONTH}'s two ops; the week's {@code emitFloorMod7} and
   * subtract; and the three compare-and-blend pairs of the select - 60 past the prefix, 91
   * for the node alone.
   */
  static final int TRUNC_DYNAMIC_TAIL_WEIGHT = 60;
  static final int TRUNC_DYNAMIC_WEIGHT = CHRONO_PREFIX_WEIGHT + TRUNC_DYNAMIC_TAIL_WEIGHT;

  /**
   * {@link TruncDateDynamic}'s locals: the subtract-form slots of {@link #TRUNC_DATE_TMP_COUNT}
   * it reads ({@code t[0..12]}, the recompose scratch past them unused) plus one of its own for
   * the level vector, {@code t[}{@link #TRUNC_DYNAMIC_LEVEL_SLOT}{@code ]}. The two scratch
   * locals its week result's {@code emitFloorMod7} needs come from {@code dowTmp}, as for
   * {@code NextDay}, and the four results ride the operand stack.
   */
  static final int TRUNC_DYNAMIC_LEVEL_SLOT = 13;
  static final int TRUNC_DYNAMIC_TMP_COUNT = TRUNC_DYNAMIC_LEVEL_SLOT + 1;

  /**
   * What {@link VarkaVectorIR.MakeDate} weighs against {@link #GROUP_BUDGET}, counted
   * the way {@link #DAY_OF_YEAR_WEIGHT} is: the validity arithmetic (the clamp, the month length
   * with its leap flag, four compares) and {@code emitDaysFromCivil}'s recompose. Read off the
   * emitted bytes by the register in {@code VarkaEmitterBudgetSuite}, not estimated.
   */
  static final int MAKE_DATE_WEIGHT = 60;

  /**
   * {@code MakeDate}'s locals: the three inputs, the clamped month, the month length, the two
   * masks (validity, and the year in range), and {@code emitDaysFromCivil}'s eleven scratch
   * slots - fresh named slots rather than a reuse, {@code PLAN_TASK_36.md}'s lesson.
   */
  static final int MAKE_DATE_TMP_COUNT = 18;

  /**
   * What {@link VarkaVectorIR.ThursdayOf} weighs against {@link #GROUP_BUDGET},
   * counted the way {@link #NEXT_DAY_WEIGHT} is and read off the emitted bytes: the shift's
   * dense loop carries 19 {@code IntVector} calls ({@code weekday}'s 17 plus its add and
   * subtract). It is a plain node, not a calendar one: {@code WeekOfYear} decomposes the
   * shifted day, so the shift is the child its prefix is keyed on.
   */
  static final int THURSDAY_OF_WEIGHT = 19;

  /**
   * {@code WeekOfYear}'s tail: the day-of-year tail and {@code (doy - 1) / 7 + 1} by
   * {@link VarkaChrono#WEEK_M}, 16 past a prefix that elides the month step - so
   * {@code weekofyear(d)} as a whole emits 64: the shift's 19, the prefix's 29 and this.
   */
  static final int WEEK_OF_YEAR_TAIL_WEIGHT = 16;
  static final int WEEK_OF_YEAR_WEIGHT = CHRONO_PREFIX_WEIGHT + WEEK_OF_YEAR_TAIL_WEIGHT;

  /**
   * What {@link VarkaVectorIR.DayOfWeekIso} weighs against {@link #GROUP_BUDGET},
   * counted the way {@link #NEXT_DAY_WEIGHT} is: {@code WeekDay}'s mod-7 tail (17 dense-loop
   * {@code IntVector} calls under the shipped lowering, per the register in
   * {@code VarkaEmitterBudgetSuite}) plus one add.
   */
  static final int DAY_OF_WEEK_ISO_WEIGHT = 18;

  /**
   * What {@link VarkaVectorIR.NextDay} weighs against {@link #GROUP_BUDGET}, counted the same
   * way as {@link #CHRONO_WEIGHT}: its own {@code w = k - d} subtract and the final
   * {@code d + r + 1} (two ops) plus {@code emitFloorMod7}'s twelve vector ops under the
   * shipped {@link VarkaEmitOptions.FloorMod7#MAGIC} lowering - fifteen real vector ops, not
   * the flat default weight of 1 the emitter used to give it. Re-count it if the lowering
   * changes shape.
   */
  static final int NEXT_DAY_WEIGHT = 15;

  /**
   * {@link VarkaVectorIR.ConstDivide}'s weight at the long lane: the fourteen lane operations
   * {@code emitMagicDivide} spends, the larger of the lane's two lowerings - the conversion form
   * spends three. See the note at the call site in {@link #weightOf}.
   */
  static final int LONG_CONST_DIVIDE_WEIGHT = 14;

  /**
   * {@link VarkaVectorIR.ConstDivide}'s weight at the int lane: the eleven lane operations
   * {@code emitMulHiDivide} spends for a positive divisor, the larger of the lane's two
   * lowerings - the conversion through double lanes spends seven. Each int half widens,
   * multiplies, shifts and narrows, eight operations on {@code LongVector} and {@code Vector},
   * and an {@code or}, a shift and an {@code add} finish the quotient. A negative divisor adds a
   * multiply by -1, which {@link #weightOf} counts on top.
   */
  static final int INT_CONST_DIVIDE_WEIGHT = 11;

  /**
   * What one node costs against {@link #GROUP_BUDGET} and {@link #FUSED_CEILING}. Every node has
   * weighed 1, because every node was one or two lane ops; the calendar nodes are not - each
   * expands to thirty-odd or more, since a civil-from-days decomposition is mostly division and
   * there is no vector divide. Counting them as 1 would have let four calendar outputs share a
   * method of ~180 ops when the ~10 s compile cliff was still believed in; weighing them by what
   * they emit gave each its own sibling method instead. The cliff was then measured away (272 ms at
   * 200 ops, {@code PLAN_TASK_32.md} 7.5) and step B2 lets siblings over one date share a method
   * again - deliberately, and only where the prefix is reused, which is why every calendar weight
   * is written as {@link #CHRONO_PREFIX_WEIGHT} plus a tail: {@code GroupOps} counts the prefix
   * once.
   *
   * <p>This is deliberately only about <i>grouping</i>: whether outputs share a loop method is
   * {@code groupOutputs}' question, and how many a kernel may fuse is the byte budget's, with
   * {@link #MAX_FUSED_NODES} counting nodes only where that budget is off.
   */
  static int weightOf(VarkaVectorIR node) {
    if (node instanceof ColumnRef || node instanceof LiteralSlot) {
      return 0;
    }
    if (node instanceof DayOfYear) {
      return DAY_OF_YEAR_WEIGHT;
    }
    if (node instanceof IntArith n) {
      // One lanewise op, plus the four the sign test costs where there is one.
      return n.mode() == Overflow.WRAP ? 1 : 5;
    }
    if (node instanceof IntNeg n) {
      // The multiply by -1, plus one compare where the mode checks.
      return n.mode() == Overflow.WRAP ? 1 : 2;
    }
    if (node instanceof WeekOfYear) {
      return WEEK_OF_YEAR_WEIGHT;
    }
    if (node instanceof TruncDate n) {
      return switch (n.level()) {
        case MONTH -> TRUNC_MONTH_WEIGHT;
        case YEAR -> TRUNC_YEAR_WEIGHT;
        case QUARTER -> TRUNC_QUARTER_WEIGHT;
      };
    }
    if (node instanceof TruncDateDynamic) {
      return TRUNC_DYNAMIC_WEIGHT;
    }
    if (node instanceof LastDay) {
      return LAST_DAY_WEIGHT;
    }
    if (node instanceof AddMonths) {
      return ADD_MONTHS_WEIGHT;
    }
    if (isChrono(node)) {
      return CHRONO_WEIGHT;
    }
    if (node instanceof MakeDate) {
      return MAKE_DATE_WEIGHT;
    }
    if (node instanceof ThursdayOf) {
      return THURSDAY_OF_WEIGHT;
    }
    if (node instanceof DayOfWeekIso) {
      return DAY_OF_WEEK_ISO_WEIGHT;
    }
    if (node instanceof ConstDivide n) {
      // Weight approximates lane ops, as every entry above does, counted over every vector type
      // the lowering touches. Each lane has two forms, and which one emits cannot be asked here -
      // `weightOf` runs while the groups are formed, before an `Analysis` exists - so the larger
      // is used. Over-weighing costs an extra loop method on a host that did not need one;
      // under-weighing puts more work in a method than the budget means, which the byte and
      // call-site budgets correct only when the built class measures over them.
      return n.laneType() == VarkaVectorIR.LaneType.LONG
          ? LONG_CONST_DIVIDE_WEIGHT
          : INT_CONST_DIVIDE_WEIGHT + (n.divisor() < 0 ? 1 : 0);
    }
    if (node instanceof InRanges) {
      // Emitted as one loop whatever the number of ranges: a start mask, and a body of two scalar
      // compares, an AND and an OR. Its time grows with the ranges; its code does not.
      return 6;
    }
    return node instanceof NextDay ? NEXT_DAY_WEIGHT : 1;
  }

  /** Whether {@code node} runs a civil-from-days decomposition and so needs
   * {@link #CHRONO_WEIGHT}: one of the extractions in the IR's sealed {@link Chrono} family,
   * whose membership makes weighing a new extraction total without touching this method - or
   * {@link AddMonths}, which decomposes and recomposes but is not itself an
   * extraction, so it stays outside {@link Chrono} and is checked for by hand here instead. */
  static boolean isChrono(VarkaVectorIR node) {
    return node instanceof Chrono || node instanceof AddMonths;
  }
}
