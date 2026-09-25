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

import java.lang.management.ManagementFactory;

import com.sun.management.HotSpotDiagnosticMXBean;

/**
 * The byte-affecting emit inputs that are not the shape: how wide a loop method may be, whether
 * common subexpressions are shared, which of the three mod-7 lowerings to emit, and one pure
 * fault injector. Everything here changes the bytes {@link VarkaLoopEmitter#emit} produces for a
 * given {@link VarkaShapeKey}, so it is part of that key rather than beside it.
 *
 * <p>This record replaces five {@code private static volatile} hook fields
 * on the emitter, an {@code AtomicLong} write generation, five package-private setters, two
 * package-private queries, a re-export shim in the catalyst test jar, a reflection-based
 * completeness test, and three reads in the shape cache: a JVM-wide gate that refused every
 * lookup while any hook was set, a snapshot of the generation before the emit walk and a re-check
 * after it. That machinery existed because the hooks were global mutable state the key could not
 * see. Options travel as a value on the call instead, so the three races it was guarding against
 * cannot be expressed:
 *
 * <ol>
 *   <li>a hook set between the cache's gate and the emit walk's snapshot - an unbounded window,
 *       since the caller may block on another task's in-flight load - was already set when the
 *       snapshot was taken, so the re-check passed and the poisoned bytes were cached under the
 *       plain key;</li>
 *   <li>the gate was JVM-wide, so while any suite held a hook every unrelated concurrent query
 *       threw instead of simply emitting uncached;</li>
 *   <li>every write bumped the generation, resets included, so one suite <i>clearing</i> its hook
 *       spuriously failed an unrelated thread's in-flight emit.</li>
 * </ol>
 *
 * <p>The record also removes an illegal state by construction. The two mod-7 reference variants
 * used to be independent booleans that could both be set at once, where the emitter silently
 * preferred one; {@link FloorMod7} makes the choice exclusive.
 *
 * <p><b>One field is reachable from SQL; the rest are test-only.</b>
 * {@link #useAVX} has a session configuration in front of it,
 * {@code spark.sql.codegen.varka.emit.useAVX}, because which division lowering is right is a
 * property of the machine and a machine can be wrong about itself. Every other field on this
 * record is set by a suite, a fuzz iteration or a benchmark arm through a test hook, and no
 * configuration reaches it. The distinction decides what the bytes oracle has to pin: the
 * emissions a user can select are pinned per value in {@code emitted_bytes.json}'s
 * {@code option_arms}, and the test-only ones are covered by the defaults, with
 * {@code VarkaEmittedBytesSuite}'s opt-in audit recording which of them move bytes at all.
 * A field that gains a configuration has to gain a pinned arm with it.
 *
 * <p><b>Two fields exist to break the emitter.</b> {@link #misdescribeAdd} and
 * {@link #misdescribeWordLiveness} feed it a wrong descriptor and an inverted liveness verdict
 * so that its own self-checks can be shown to fire; emission under the second of them raises
 * rather than producing a class, which is the point of it.
 *
 * <p><b>Defaults hash to what they always hashed.</b> {@link VarkaShapeCacheImpl#shapeHash}
 * renders these into the hash only when they differ from {@link #DEFAULTS}, so production hashes,
 * class names and telemetry are unchanged bit for bit and only the variants a suite asks for get
 * their own identity. They have to reach the hash at all because the cache's execution side table
 * is keyed on the hash alone while the map is keyed on the full key - options in one but not the
 * other would merge two variants' execution identities.
 *
 * @param groupBudget the most vector ops one emitted loop method may carry; see
 *                    {@link VarkaEmitBudget#GROUP_BUDGET} for the measured reason it is 16, and
 *                    for the retuning question the parity benchmark prices by varying it.
 * @param fusedCeiling the most vector ops one emitted loop method may carry when the outputs
 *                     in it share a civil-from-days prefix: an output joins a
 *                     group past {@link #groupBudget} only when doing so lets it skip a prefix
 *                     the group already computes, and never past this. See
 *                     {@link VarkaEmitBudget#FUSED_CEILING} for the ladder that set it.
 * @param cse whether shared subtrees are computed once and reused. Results must not change - CSE
 *            is an optimization, never a semantics change - and the emitter suite pins exactly
 *            that; the parity benchmark uses it to price CSE itself.
 * @param shareChronoPrefix whether two calendar nodes over the same date compute the
 * civil-from-days decomposition once between them rather than once each. Like {@link #cse} it is an
 * optimization and never a
 *                          semantics change, and it is pinned the same way; unlike CSE it shares
 *                          <i>inside</i> a node's emitted run rather than between whole nodes,
 *                          which is why the emitter needs a separate notion of a fragment for
 *                          it. See {@code VarkaLoopEmitter.FragmentKey}.
 * @param denseValidityOnce whether a dense batch's value outputs have their validity bits set
 *        once by the driver rather than OR-ed in per lane group by the loop. On a
 * dense batch the dispatcher has proven every referenced input null-free and the dense invariant
 * makes every value output valid on every row, so the loop's per-group call
 *        writes ones over ones. `false` reproduces the older bytes exactly and stays a
 *        reference variant the differential checks against, on {@link FloorMod7}'s precedent.
 * @param elideChronoMonth whether the civil-from-days prefix skips its March-month step in a
 *                         body where no tail reads the month. The year tail is the
 *                         one of the four fields that does not: it reads the January turn off
 *                         the day of year instead, which is the same test one step earlier in
 *                         the chain. Like {@link #cse} it is an optimization and never a
 *                         semantics change - the step it removes is dead work where it is
 *                         removed - and it is a switch only so the A/B stays re-runnable.
 * @param neriSchneiderMonth whether the month index and the day of month come out of one
 *        affine numerator (Neri-Schneider 2022) or from the magic multiply plus
 *        forward month-start this project shipped first. The two compute the same fields on
 *        two different month axes - March = 3 against March = 0 - and are differentially
 *        checked against each other, so the older one stays a live reference variant rather
 *        than dead code, on {@link FloorMod7}'s precedent.
 * @param julianMap whether the civil-from-days prefix takes the year of era through Ben Joffe's
 *        Julian map - the day of era scaled by four, one division by 146097 for the
 *        century, four added back per century, and one division by 1461 for the year, whose
 *        remainder is the day of year with the leap day right by construction - or through the
 *        century-then-year split this project shipped first, with its leap-day underflow
 *        correction. Same fields either way, differentially checked against each other, so the
 *        older form stays a live reference variant, on {@link FloorMod7}'s precedent.
 * @param shareWholeNodes whether an output that reuses whole nodes the group already computes
 *        may join it past {@link #groupBudget}, the way one reusing a civil-from-days prefix
 *        already may. {@code groupBudget} bounds the *method*, while the marginal cost
 *        it is compared against already excludes nodes the group holds - so two outputs over a
 *        shared chain are rejected for a method of 20 nodes although splitting them costs 28
 *        nodes of work. The reuse is the same kind clause 2 was written for: joining lets the
 * output skip work the group does anyway, which is less work rather than a trade. Off until a
 * measurement chooses it.
 * @param guardUnderArm whether a batch-condemning guard on a node under a {@code CASE}/{@code IF}
 *        arm is qualified by that arm's condition, so a lane the condition sends to
 *        the other arm cannot decline the batch. A vector body computes both arms, and a guard
 *        condemns rather than producing a value the blend can discard, so without this a row the
 *        query never uses declines the batch it is in. Off, the guard condemns from the untaken
 *        arm as it did before arm qualification - the reference variant the A/B prices against, on
 *        {@link FloorMod7}'s precedent. It qualifies only where every use of the node sits under
 *        one and the same arm chain; see {@code Analysis.armChain}.
 * @param guardDayProducers whether a {@code date_add}/{@code date_sub} whose offset is a column,
 *        and whose result a calendar node reads, carries a per-lane check on that result against
 *        the range the civil-from-days lowering is exact over, declining the batch to
 *        the row engine when a lane leaves it. The compiler bounds every other day producer at
 *        compile time; this is the shape it cannot, so the check is at run time and at the
 * producer rather than at each extraction (rather than at each extraction, as an earlier design
 * did). Off, such a shape keeps its previous bytes exactly and such a lane is computed wrongly
 *        rather than declined - a reference variant for the A/B that priced the guard, on
 *        {@link FloorMod7}'s precedent. This does <em>not</em> reach the month-count guard on an
 *        {@code add_months} / {@code date + INTERVAL n MONTH} column month count: that one is
 *        checked on the count itself rather than on a result, it guards the node's own magic
 *        multiply rather than a consumer's lowering, and the compiler's {@code dayRange} bounds
 * such a count on the strength of it firing - so it is unconditional, like {@code make_date}. (The
 * minus spelling declines at compile time, so no kernel exists
 *        for either setting to gate.)
 * @param validityByWord whether a destination bitmap's validity is accumulated in a register
 *        and stored a whole 64-bit word at a time, instead of read-modify-written once per lane
 *        group. The read is what goes: {@code orValidityBitsAt*} loads the group's
 *        bytes, ORs and stores them back, and at four lanes a group is half a byte, so two
 * consecutive groups rewrite the same byte and serialise on it - the regime where the helper choice
 * inverts inside. Sound only because the destination is a
 *        bitmap the evaluator allocated: {@code VarkaKernelEvaluatorSuite} pins that an Arrow
 *        validity buffer owns whole 64-bit words at every length, the driver zeroes exactly the
 *        outputs this writes, and {@code groupOutputs} gives each output one writer. Applies to
 *        a loop body at a baked lane count that divides 64; the epilogue's partial group keeps
 * the read-modify-write, once per batch. Off is the per-group form - a live reference variant on
 * {@link FloorMod7} 's precedent, and the arm
 *        the A/B that prices this measures against.
 *
 *        <p>Two other options go inert under it and say so in their own text: with no helper
 *        call there is nothing for {@link #validityByWidth} to name and nothing for
 *        {@link #validityOrFirst} to order, for the outputs it writes.
 * @param validityByWidth whether a whole lane group's validity is read and written through the
 *        helper named for the emission's lane count - {@code orValidityBitsAt16} and its
 *        siblings, each with the general form's four-arm switch already resolved - and the
 *        concrete {@code VectorSpecies} constant baked in beside it. The general pair
 *        takes the lane count as an argument, so it carries a switch the caller cannot fold; at
 *        212 bytes the writer does not inline inside a fused loop, and one refused call costs
 *        1.87 to 3.24 ns per lane group at any width. Off, the emitter reads
 * {@code SPECIES_PREFERRED} at run time and calls the general pair - a live reference variant, on
 * {@link FloorMod7} 's precedent, and the
 *        arm the A/B that priced this measures against.
 * @param validityOrFirst whether a value root's validity OR is emitted <i>before</i> its vector
 *        computation, wherever its word is already known - an input word in the masked body,
 *        the constant in the dense one - rather than after the store (see second half).
 *        Same bytes, different order; what it changes is where C2's parser meets the call.
 *        Emitted last, after the body's Vector API intrinsics, the OR helper was refused with
 *        {@code NodeCountInliningCutoff} in every arm - a develop-only limit of 18000 nodes on
 *        the <i>caller</i>, which no size of callee can satisfy - and ran as a real call in the
 *        hot loop. Emitted first, it inlines. Off reproduces the after-the-store order, the
 *        reference variant for the A/B that priced this, on {@link FloorMod7}'s precedent.
 * @param validityByBitmap whether a value root whose validity word is a pure AND/OR over input
 *        bitmaps has that bitmap written once per batch by the masked driver - a copy, an AND
 *        or an OR of whole input bitmaps, through {@code VarkaVectorSupport}'s column-taking
 *        entry points - instead of ORed in per lane group by the loop. With the write
 *        gone, a word no consumer left in the method reads is not computed either: no input
 *        word stored, no own word ANDed, and no null-state prologue for an input whose word is
 *        dead, so a masked method whose every word is dead is the dense method's bytes. What
 *        stays per group is what is not a function of input bitmaps - a {@code Cond} root's
 * selection, an {@code IfElse} 's blend, {@code make_date} 's validity test - and what still reads
 * a word: the range guards, the pick's null substitution, every condition. On, from the measurement
 * in PLAN_TASK_70.md 9: every served row
 *        faster at both widths, the four-field shape by 1.59x at AVX-512 and 1.89x at 128-bit,
 *        where it lands on its dense twin. One row reads past its dense twin by more than run
 *        noise - {@code next_day} with a weekday column at 128-bit - on loop bytecode the tests
 *        prove identical; it is an open harness question in the milestone's debt register, not
 *        a property of the lowering. Off reproduces the per-group bytes exactly and stays a
 *        reference variant the differential checks against, on {@link FloorMod7}'s precedent.
 * @param checkIntOverflow whether an int arithmetic node in Spark's ANSI or TRY evaluation
 *        mode emits its overflow check. On is the only correct setting for those
 *        modes: with it off the node emits as though it were `LEGACY`, wrapping silently,
 *        which is a wrong answer rather than a slower one. It exists so the A/B can price the
 * check against the arithmetic it protects, exactly as {@link #guardDayProducers} prices the
 * producer range guard, and the differential runs it only as that variant.
 *        A `WRAP` node is unaffected either way - it has no check to skip.
 * @param lanesOverride the lane count to emit for, or 0 to emit for the JVM's own
 *        {@code IntVector.SPECIES_PREFERRED} - which is what production always does, so
 *        {@link #DEFAULTS} renders empty and production hashes do not move. It exists because
 *        one JVM has one preferred width and {@link #validityByWidth} has an arm per width: the
 *        suite drives 2, 4, 8 and 16 lanes, and a width with no specialised sibling, from a
 *        single run. A non-zero value must be a power of two; the emitted class carries the
 *        matching species, so it computes the right answers at that width wherever it runs,
 *        slowly if the hardware is narrower.
 * @param truncDate which lowering {@code trunc(date, ...)} uses at the {@code YEAR} and
 *        {@code QUARTER} levels: {@link TruncDateForm#SUBTRACT} takes the day of year
 *        off the date ({@code d - dayofyear + start}), {@link TruncDateForm#RECOMPOSE} rebuilds
 *        the period's first day from the year and month through {@code emitDaysFromCivil}. Same
 *        dates either way, differentially checked against each other, and {@code MONTH} follows
 *        the switch too so the recomposition has a third shape to agree on; whichever is not
 *        the default stays a live reference variant, on {@link FloorMod7}'s precedent.
 * @param floorMod7 which lowering {@code dayofweek}/{@code weekday} use for their mod-7.
 * @param division which lowering the calendar prefix's constant divisions use. See
 *        {@link Division}. A width with no double species to convert through - a single int
 *        lane, whose double half would have none - ignores anything but
 *        {@link Division#MAGIC}, so this widens what may be asked for without widening what is
 *        answered.
 * @param useAVX the {@code -XX:UseAVX} level this emission targets, or
 *        {@link #USE_AVX_UNKNOWN} for no level at all, which is what it defaults to. It
 *        changes emitted bytes - the long-to-double converts the 64-bit division uses do not
 *        intrinsify under {@code -XX:UseAVX=2}, so a host at that level has a second lowering
 *        available - and it is therefore part of the shape key like every other component
 *        here.
 *
 *        <p><b>The default is deliberately not the host's own level</b>, although
 *        {@link #HOST_USE_AVX} reads it. Two reasons, and the second is the one that decides
 *        it. Nothing has yet measured that the alternative lowering is faster on such a host;
 *        task 88 step 4's A/B is what would, and until it has run, selecting a lowering from
 *        the machine changes production behaviour on a reading rather than on a number. And a
 *        default that varies by host makes everything built on it vary too: `canonical()`
 *        renders the empty string for the defaults, so the committed shape hashes and the
 *        committed emitted bytes would each describe one machine and fail on another. A fixed
 *        default keeps one shape one class name across executors, which is what the hash
 *        promises, and leaves the level an explicit request - which is what a test, a
 *        benchmark, and one day a session option, make.
 * @param misdescribeAdd emits {@code AddDays} against a deliberately wrong descriptor (an unerased
 *                       {@code IntVector} parameter instead of {@code Vector}). The class still
 *                       passes bytecode verification - member resolution happens at link time - so
 *                       the failure surfaces on first execution as a {@code NoSuchMethodError}
 *                       naming {@code IntVector.add}. The suite pins that, so a future descriptor
 *                       regression is diagnosable from the error alone.
 * @param misdescribeWordLiveness inverts the word-liveness verdict on every word: what
 *        the rule declares dead is treated as live and stored, what it declares live is treated
 *        as dead and never stored. A pure fault injector for the two halves of the emitter's own
 *        invariant - a word stored but never loaded fails at the end of the body, a word loaded
 *        but never stored fails at the load - so a test can prove both are armed rather than
 *        assume it. Meaningful only with {@link #validityByBitmap} on; off, every word is live
 *        already and the inversion has nothing to invert.
 * @param mulHiDivide whether an int-lane {@code ConstDivide} takes the multiply-high form
 *        through 64-bit lanes (task 149): each int half widened with {@code I2L}, multiplied
 *        by Granlund and Montgomery's magic, shifted, narrowed with {@code L2I}. On, which is
 *        the default and what ships; off is the conversion through double lanes it replaced,
 *        kept as the reference arm. The long lane is unaffected either way.
 * @param narrowHalfSpecies how a {@code NarrowLane} root is stored. Off, the 64-bit result is
 *        converted with {@code L2I} into the int species of the lane's own width, whose low half
 *        holds the values, and stored under an int mask of that half - one species per element
 *        type in the class, at the price of a masked store whose bounds branch C2 keeps inside
 *        the loop and does not unroll past. On, the conversion targets the int species of half
 *        the width - as many int lanes as the lane has long lanes - and the dense body stores it
 *        whole, the epilogue under its remainder mask; a second {@code IntVector} species,
 *        which {@code PLAN_TASK_28.md} 2.2 warns makes the shared templates bimorphic. Honoured
 *        only at a baked lane count, since the half of the preferred species has no named
 *        constant; at count 0 the store is the masked form either way. The A/B is
 *        {@code PLAN_TASK_156.md}'s.
 * @param methodByteBudget the bytecode length every emitted method is held to, by default
 *        {@code VarkaEmitBudget.HUGE_METHOD_LIMIT}, the length past which HotSpot never
 *        compiles a method: a group's loop and epilogue methods set up only that group's
 *        outputs and literals ({@code PLAN_TASK_87.md} 2.6.2), the epilogue is one method per
 *        group, the built class is measured and a group over the limit is split and the class
 *        built again, and a shape still over a limit when no split is left declines with the
 *        reason ({@link VarkaEmitDeclined}). {@code 0} is the form before task 87, kept as the
 *        reference the differential tests and {@code VarkaMethodSizeBenchmark}'s first arm
 *        measure against: weight groups the loop, one epilogue holds every output, nothing is
 *        measured. A small value is how a test sees a regroup or a decline on a shape of a few
 *        outputs. Fuzzed at 0 and at the default.
 * @param rangeSets whether the compiler lowers a disjunction of ranges over one int or date
 *        column to one {@link VarkaVectorIR.InRanges} node ({@code PLAN_TASK_172.md} 9.2), on by
 *        default. Off, such a disjunction compiles as the comparisons it is written as, which
 *        is the arm the split below is measured on. Read by the compiler, not by the emitter.
 * @param splitConditions whether a filter predicate that no single method can hold is split
 *        across several selection outputs rather than declined ({@code PLAN_TASK_172.md} 3.1):
 *        its conjuncts are packed into several conjunction roots, a conjunct too large alone is
 *        split into partial disjunctions when it is an {@code OR}, and the filter combines the
 *        outputs' bitmaps. Off by default until measured. Read by the compiler, not by the
 *        emitter.
 */
public record VarkaEmitOptions(
    int groupBudget,
    int fusedCeiling,
    boolean cse,
    boolean shareChronoPrefix,
    boolean denseValidityOnce,
    boolean elideChronoMonth,
    boolean neriSchneiderMonth,
    boolean julianMap,
    boolean guardDayProducers,
    boolean validityByWidth,
    boolean validityOrFirst,
    boolean validityByBitmap,
    boolean checkIntOverflow,
    int lanesOverride,
    TruncDateForm truncDate,
    FloorMod7 floorMod7,
    Division division,
    int useAVX,
    boolean misdescribeAdd,
    boolean misdescribeWordLiveness,
    boolean guardUnderArm,
    boolean shareWholeNodes,
    boolean validityByWord,
    boolean mulHiDivide,
    boolean narrowHalfSpecies,
    int methodByteBudget,
    boolean rangeSets,
    boolean splitConditions) {

  /**
   * The three mod-7 lowerings. {@link #MAGIC} is what ships: two 15-bit digit-sum folds followed
   * by an exact Granlund-Montgomery magic division. The other two are the
   * reference variants the parity benchmark and the differential suite check it against -
   * {@link #DIGIT_SUM} is the full base-8 digit sum, and {@link #DIV} is
   * the certainly-correct lanewise divide, which scalarizes on every lane type this JVM has.
   */
  public enum FloorMod7 { MAGIC, DIV, DIGIT_SUM }

  /**
   * The three lowerings of a constant division in the calendar prefix. {@link #MAGIC} is what
   * ships: the Granlund-Montgomery form {@code (v * m) >>> k}, exact over the range the emitter
   * proves the dividend stays in. The other two route the division through the double lane
   * instead, converting the int vector into two double vectors of half the lanes, dividing
   * there and converting back - seven operations against the magic form's two, but on hardware
   * whose vector divider is wider than its 32-bit multiplier the seven can win, which is the
   * A/B this option exists to run.
   *
   * <p>The two double forms differ in the divide: {@link #DOUBLE_RECIP} multiplies by the
   * precomputed reciprocal {@code fl(1/d)} and {@link #DOUBLE_DIV} issues a real divide. The
   * reciprocal is the faster of the two and the weaker: its result carries two rounding errors
   * against the divide's one, so it is exact over a smaller range of dividends. Neither is
   * universally safe, and which divisor may use which over which range is not a matter of
   * judgement - {@code sql/varka/plans/verify_double_division.py} decides it by exhaustive
   * probe, and {@link VarkaLoopEmitter} carries the deny-list it produced.
   */
  public enum Division { MAGIC, DOUBLE_RECIP, DOUBLE_DIV }

  /*
   * A note this enum cannot express, kept beside it rather than inside its javadoc, which
   * describes the calendar prefix: the 64-bit lane has a fourth lowering, the magic-number
   * identity of `VarkaLoopEmitter.emitMagicDivide`, and it is selected by {@link #useAVX}
   * rather than by this enum, because it is a property of the machine and not a variant a
   * caller chooses. The consequence is that on a host whose conversions fall back there is no
   * value of this enum that emits the conversion form at the long lane; a test or a benchmark
   * that wants it asks for a level instead, which is what `withUseAVX` is for. Task 88 step 4's
   * A/B is the reason that matters, and `PLAN_TASK_88.md` 9.2 records it.
   */

  /** The two {@code trunc(date, ...)} lowerings; see {@link #truncDate}. */
  public enum TruncDateForm { SUBTRACT, RECOMPOSE }

  /**
   * The {@link #useAVX} value meaning "this JVM has no {@code UseAVX} flag": an aarch64 or
   * other non-x86 HotSpot, or a JVM with no diagnostic bean to ask. It is a distinct value
   * rather than 0 because 0 is a real level - x86 with the AVX paths switched off - and the
   * two call for opposite assumptions: a machine that reports 0 has told us something, a
   * machine that reports nothing has not.
   */
  public static final int USE_AVX_UNKNOWN = -1;

  /**
   * What {@code -XX:UseAVX} reads on the JVM running this code, asked once.
   *
   * <p>The flag is HotSpot's own gate for its AVX code paths, which is the closest thing the
   * JVM exposes to the question the emitter actually has: whether a {@code LongVector} to
   * {@code DoubleVector} conversion becomes one instruction or a Java fallback. The two are
   * not the same question - that conversion's intrinsic wants AVX512DQ specifically, and a
   * level is not a feature list - so this describes the host rather than promising anything
   * about it. {@code dev/varka_canary/L2DProbe.java} is what settles a given machine.
   */
  public static final int HOST_USE_AVX = readUseAVX();

  /** The {@code UseAVX} level at which HotSpot enables its AVX-512 paths. */
  private static final int AVX512_LEVEL = 3;

  /**
   * Whether this level is one where the long-to-double conversions do not become instructions,
   * so a 64-bit constant division takes the magic-number form instead of converting.
   *
   * <p>It lives here rather than in the emitter because {@link #canonical()} has to ask the
   * same question: a level that changes a lowering has to reach the shape key, and one that
   * does not must not, or the common host loses the empty rendering the production hash is
   * built on. An unknown level answers false - an aarch64 machine has no evidence against its
   * conversions, and assuming the worst there would slow it down on a guess.
   */
  public boolean convertsFallBack() {
    return useAVX != USE_AVX_UNKNOWN && useAVX < AVX512_LEVEL;
  }

  private static int readUseAVX() {
    try {
      HotSpotDiagnosticMXBean bean =
          ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
      return bean == null
          ? USE_AVX_UNKNOWN
          : Integer.parseInt(bean.getVMOption("UseAVX").getValue());
    } catch (RuntimeException | LinkageError e) {
      // No such flag (aarch64), no such bean (a JVM that is not HotSpot), or a value that is
      // not a number. None of them is an error: the field then means "unknown" and every
      // lowering stays the one it would have been without this field at all.
      return USE_AVX_UNKNOWN;
    }
  }

  /** What production always emits with; see the hashing note in the class doc. */
  public static final VarkaEmitOptions DEFAULTS =
      new VarkaEmitOptions(
          VarkaEmitBudget.GROUP_BUDGET, VarkaEmitBudget.FUSED_CEILING,
          true, true, true, true, true, true, true, true, true, true, true,
          0,
          TruncDateForm.SUBTRACT, FloorMod7.MAGIC, Division.MAGIC, USE_AVX_UNKNOWN,
          false, false, true, true, false, true, false,
          VarkaEmitBudget.HUGE_METHOD_LIMIT,
          true, false);

  public VarkaEmitOptions {
    if (groupBudget < 1) {
      throw new IllegalArgumentException("groupBudget must be positive: " + groupBudget);
    }
    if (fusedCeiling < 1) {
      throw new IllegalArgumentException("fusedCeiling must be positive: " + fusedCeiling);
    }
    if (truncDate == null) {
      throw new IllegalArgumentException("truncDate must not be null");
    }
    if (floorMod7 == null) {
      throw new IllegalArgumentException("floorMod7 must not be null");
    }
    if (useAVX < USE_AVX_UNKNOWN) {
      throw new IllegalArgumentException("useAVX must be a level or " + USE_AVX_UNKNOWN
          + " for none, not " + useAVX);
    }
    if (methodByteBudget < 0) {
      throw new IllegalArgumentException("methodByteBudget must be 0 (off) or a positive byte "
          + "count: " + methodByteBudget);
    }
    if (division == null) {
      throw new IllegalArgumentException("division must not be null");
    }
    if (lanesOverride != 0 && (lanesOverride < 1 || Integer.bitCount(lanesOverride) != 1)) {
      throw new IllegalArgumentException(
          "lanesOverride must be 0 or a power of two: " + lanesOverride);
    }
  }

  /**
   * A copy of this value with any components changed: {@code toBuilder().cse(false).build()}.
   * The builder holds one field per record component and nothing else; {@link Builder#build()}
   * calls the canonical constructor, so its validation runs on every built value.
   */
  public Builder toBuilder() {
    Builder b = new Builder();
      b.groupBudget = groupBudget;
      b.fusedCeiling = fusedCeiling;
      b.cse = cse;
      b.shareChronoPrefix = shareChronoPrefix;
      b.denseValidityOnce = denseValidityOnce;
      b.elideChronoMonth = elideChronoMonth;
      b.neriSchneiderMonth = neriSchneiderMonth;
      b.julianMap = julianMap;
      b.guardDayProducers = guardDayProducers;
      b.validityByWidth = validityByWidth;
      b.validityOrFirst = validityOrFirst;
      b.validityByBitmap = validityByBitmap;
      b.checkIntOverflow = checkIntOverflow;
      b.lanesOverride = lanesOverride;
      b.truncDate = truncDate;
      b.floorMod7 = floorMod7;
      b.division = division;
      b.useAVX = useAVX;
      b.misdescribeAdd = misdescribeAdd;
      b.misdescribeWordLiveness = misdescribeWordLiveness;
      b.guardUnderArm = guardUnderArm;
      b.shareWholeNodes = shareWholeNodes;
      b.validityByWord = validityByWord;
      b.mulHiDivide = mulHiDivide;
      b.narrowHalfSpecies = narrowHalfSpecies;
      b.methodByteBudget = methodByteBudget;
      b.rangeSets = rangeSets;
      b.splitConditions = splitConditions;
    return b;
  }

  /** One field per component of {@link VarkaEmitOptions}; see {@link #toBuilder()}. */
  public static final class Builder {
    private int groupBudget;
    private int fusedCeiling;
    private boolean cse;
    private boolean shareChronoPrefix;
    private boolean denseValidityOnce;
    private boolean elideChronoMonth;
    private boolean neriSchneiderMonth;
    private boolean julianMap;
    private boolean guardDayProducers;
    private boolean validityByWidth;
    private boolean validityOrFirst;
    private boolean validityByBitmap;
    private boolean checkIntOverflow;
    private int lanesOverride;
    private TruncDateForm truncDate;
    private FloorMod7 floorMod7;
    private Division division;
    private int useAVX;
    private boolean misdescribeAdd;
    private boolean misdescribeWordLiveness;
    private boolean guardUnderArm;
    private boolean shareWholeNodes;
    private boolean validityByWord;
    private boolean mulHiDivide;
    private boolean narrowHalfSpecies;
    private int methodByteBudget;
    private boolean rangeSets;
    private boolean splitConditions;

    private Builder() {
    }

    public Builder groupBudget(int groupBudget) {
      this.groupBudget = groupBudget;
      return this;
    }

    public Builder fusedCeiling(int fusedCeiling) {
      this.fusedCeiling = fusedCeiling;
      return this;
    }

    public Builder cse(boolean cse) {
      this.cse = cse;
      return this;
    }

    public Builder shareChronoPrefix(boolean shareChronoPrefix) {
      this.shareChronoPrefix = shareChronoPrefix;
      return this;
    }

    public Builder denseValidityOnce(boolean denseValidityOnce) {
      this.denseValidityOnce = denseValidityOnce;
      return this;
    }

    public Builder elideChronoMonth(boolean elideChronoMonth) {
      this.elideChronoMonth = elideChronoMonth;
      return this;
    }

    public Builder neriSchneiderMonth(boolean neriSchneiderMonth) {
      this.neriSchneiderMonth = neriSchneiderMonth;
      return this;
    }

    public Builder julianMap(boolean julianMap) {
      this.julianMap = julianMap;
      return this;
    }

    public Builder guardDayProducers(boolean guardDayProducers) {
      this.guardDayProducers = guardDayProducers;
      return this;
    }

    public Builder validityByWidth(boolean validityByWidth) {
      this.validityByWidth = validityByWidth;
      return this;
    }

    public Builder validityOrFirst(boolean validityOrFirst) {
      this.validityOrFirst = validityOrFirst;
      return this;
    }

    public Builder validityByBitmap(boolean validityByBitmap) {
      this.validityByBitmap = validityByBitmap;
      return this;
    }

    public Builder checkIntOverflow(boolean checkIntOverflow) {
      this.checkIntOverflow = checkIntOverflow;
      return this;
    }

    public Builder lanesOverride(int lanesOverride) {
      this.lanesOverride = lanesOverride;
      return this;
    }

    public Builder truncDate(TruncDateForm truncDate) {
      this.truncDate = truncDate;
      return this;
    }

    public Builder floorMod7(FloorMod7 floorMod7) {
      this.floorMod7 = floorMod7;
      return this;
    }

    public Builder division(Division division) {
      this.division = division;
      return this;
    }

    public Builder useAVX(int useAVX) {
      this.useAVX = useAVX;
      return this;
    }

    public Builder misdescribeAdd(boolean misdescribeAdd) {
      this.misdescribeAdd = misdescribeAdd;
      return this;
    }

    public Builder misdescribeWordLiveness(boolean misdescribeWordLiveness) {
      this.misdescribeWordLiveness = misdescribeWordLiveness;
      return this;
    }

    public Builder guardUnderArm(boolean guardUnderArm) {
      this.guardUnderArm = guardUnderArm;
      return this;
    }

    public Builder shareWholeNodes(boolean shareWholeNodes) {
      this.shareWholeNodes = shareWholeNodes;
      return this;
    }

    public Builder validityByWord(boolean validityByWord) {
      this.validityByWord = validityByWord;
      return this;
    }

    public Builder mulHiDivide(boolean mulHiDivide) {
      this.mulHiDivide = mulHiDivide;
      return this;
    }

    public Builder narrowHalfSpecies(boolean narrowHalfSpecies) {
      this.narrowHalfSpecies = narrowHalfSpecies;
      return this;
    }

    public Builder methodByteBudget(int methodByteBudget) {
      this.methodByteBudget = methodByteBudget;
      return this;
    }

    public Builder rangeSets(boolean rangeSets) {
      this.rangeSets = rangeSets;
      return this;
    }

    public Builder splitConditions(boolean splitConditions) {
      this.splitConditions = splitConditions;
      return this;
    }

    public VarkaEmitOptions build() {
      return new VarkaEmitOptions(
          groupBudget, fusedCeiling, cse, shareChronoPrefix, denseValidityOnce,
          elideChronoMonth, neriSchneiderMonth, julianMap, guardDayProducers,
          validityByWidth, validityOrFirst, validityByBitmap, checkIntOverflow,
          lanesOverride, truncDate, floorMod7, division, useAVX, misdescribeAdd,
          misdescribeWordLiveness, guardUnderArm, shareWholeNodes, validityByWord,
          mulHiDivide, narrowHalfSpecies, methodByteBudget, rangeSets, splitConditions);
    }
  }

  /**
   * {@link #DEFAULTS} with one field changed, for the suites and benchmarks that vary one. Each
   * of these is {@code toBuilder().x(v).build()}, so adding a component is one field, one
   * setter and one line in {@link Builder#build()} rather than an edit to every copy method.
   */
  public VarkaEmitOptions withNarrowHalfSpecies(boolean enabled) {
    return toBuilder().narrowHalfSpecies(enabled).build();
  }

  public VarkaEmitOptions withMethodByteBudget(int bytes) {
    return toBuilder().methodByteBudget(bytes).build();
  }

  public VarkaEmitOptions withRangeSets(boolean enabled) {
    return toBuilder().rangeSets(enabled).build();
  }

  public VarkaEmitOptions withSplitConditions(boolean enabled) {
    return toBuilder().splitConditions(enabled).build();
  }

  public VarkaEmitOptions withShareWholeNodes(boolean enabled) {
    return toBuilder().shareWholeNodes(enabled).build();
  }

  public VarkaEmitOptions withValidityByWord(boolean enabled) {
    return toBuilder().validityByWord(enabled).build();
  }

  /**
   * The int-lane constant division's form: the multiply-high through 64-bit lanes that ships
   * (task 149), or, off, the conversion through double lanes it replaced - kept as the
   * reference arm the parity benchmark and the differential check it against. The long lane
   * has no wider lane to multiply into and is not affected.
   */
  public VarkaEmitOptions withMulHiDivide(boolean enabled) {
    return toBuilder().mulHiDivide(enabled).build();
  }

  public VarkaEmitOptions withGroupBudget(int budget) {
    return toBuilder().groupBudget(budget).build();
  }

  public VarkaEmitOptions withFusedCeiling(int ceiling) {
    return toBuilder().fusedCeiling(ceiling).build();
  }

  public VarkaEmitOptions withCse(boolean enabled) {
    return toBuilder().cse(enabled).build();
  }

  public VarkaEmitOptions withShareChronoPrefix(boolean enabled) {
    return toBuilder().shareChronoPrefix(enabled).build();
  }

  public VarkaEmitOptions withDenseValidityOnce(boolean enabled) {
    return toBuilder().denseValidityOnce(enabled).build();
  }

  public VarkaEmitOptions withElideChronoMonth(boolean enabled) {
    return toBuilder().elideChronoMonth(enabled).build();
  }

  public VarkaEmitOptions withNeriSchneiderMonth(boolean enabled) {
    return toBuilder().neriSchneiderMonth(enabled).build();
  }

  public VarkaEmitOptions withJulianMap(boolean enabled) {
    return toBuilder().julianMap(enabled).build();
  }

  public VarkaEmitOptions withGuardDayProducers(boolean enabled) {
    return toBuilder().guardDayProducers(enabled).build();
  }

  public VarkaEmitOptions withValidityByWidth(boolean enabled) {
    return toBuilder().validityByWidth(enabled).build();
  }

  public VarkaEmitOptions withValidityOrFirst(boolean enabled) {
    return toBuilder().validityOrFirst(enabled).build();
  }

  public VarkaEmitOptions withValidityByBitmap(boolean enabled) {
    return toBuilder().validityByBitmap(enabled).build();
  }

  /**
   * whether a batch-condemning guard under a {@code CASE}/{@code IF} arm is qualified
   * by that arm's condition, so a lane the condition sends to the other arm cannot decline the
   * batch. Off, the guard condemns from the untaken arm exactly as it did before arm qualification
   * - the reference variant for the A/B, on {@link FloorMod7} 's precedent, and the setting every
   * "unchanged bytes" assertion compares against.
   */
  public VarkaEmitOptions withGuardUnderArm(boolean enabled) {
    return toBuilder().guardUnderArm(enabled).build();
  }

  public VarkaEmitOptions withCheckIntOverflow(boolean enabled) {
    return toBuilder().checkIntOverflow(enabled).build();
  }

  public VarkaEmitOptions withLanesOverride(int lanes) {
    return toBuilder().lanesOverride(lanes).build();
  }

  public VarkaEmitOptions withTruncDate(TruncDateForm form) {
    return toBuilder().truncDate(form).build();
  }

  public VarkaEmitOptions withFloorMod7(FloorMod7 lowering) {
    return toBuilder().floorMod7(lowering).build();
  }

  public VarkaEmitOptions withDivision(Division lowering) {
    return toBuilder().division(lowering).build();
  }

  /**
   * The same options as if emitted on a host at this {@code UseAVX} level. It exists so a test
   * and the committed bytes oracle can pin a level rather than inherit the machine's, which is
   * what lets one committed file describe more than one host.
   */
  public VarkaEmitOptions withUseAVX(int level) {
    return toBuilder().useAVX(level).build();
  }

  public VarkaEmitOptions withMisdescribeAdd(boolean misdescribe) {
    return toBuilder().misdescribeAdd(misdescribe).build();
  }

  public VarkaEmitOptions withMisdescribeWordLiveness(boolean misdescribe) {
    return toBuilder().misdescribeWordLiveness(misdescribe).build();
  }

  public boolean isDefault() {
    return DEFAULTS.equals(this);
  }

  /**
   * The hand-pinned rendering that reaches the shape hash - never {@code Record.toString}, whose
   * format no JDK promises, for the same reason {@code VarkaVectorIR.canonical} exists. Empty for
   * {@link #DEFAULTS}, so a production hash is byte-identical to what it was before options
   * existed; otherwise every field, in declaration order, so two variants can never collide.
   *
   * <p>"Every field" has not always held: {@code truncDate} was once left out
   * 35, so two option values differing only in the {@code trunc} lowering rendered the same
   * string and shared one execution identity in the cache's side table, which is exactly the
   * hazard this class doc describes. {@code VarkaShapeCacheSuite} now walks the record's
   * components and fails if one of them cannot change the rendering, so the next field cannot
   * be forgotten the same way.
   *
   * <p>The two compiler options, {@code rangeSets} and {@code splitConditions}, render only when
   * they differ from their defaults, so every variant's rendering from before they existed is
   * unchanged.
   */
  public String canonical() {
    if (isDefault()) {
      return "";
    }
    return "opts(" + groupBudget + '|' + fusedCeiling + '|' + cse + '|' + shareChronoPrefix
        + '|' + denseValidityOnce + '|' + elideChronoMonth + '|' + neriSchneiderMonth + '|'
        + julianMap + '|' + guardDayProducers + '|' + validityByWidth + '|' + validityOrFirst
        + '|' + validityByBitmap + '|' + checkIntOverflow + '|' + lanesOverride + '|'
        + truncDate + '|' + floorMod7 + '|' + division + '|' + useAVX + '|'
        + misdescribeAdd + '|' + misdescribeWordLiveness + '|' + guardUnderArm + '|'
        + shareWholeNodes + '|' + validityByWord + '|' + mulHiDivide
        + '|' + narrowHalfSpecies + '|' + methodByteBudget
        + (rangeSets ? "" : "|noRangeSets") + (splitConditions ? "|splitConditions" : "") + ')';
  }
}
