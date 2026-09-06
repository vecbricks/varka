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

/**
 * The byte-affecting emit inputs that are not the shape: how wide a loop method may be, whether
 * common subexpressions are shared, which of the three mod-7 lowerings to emit, and one pure
 * fault injector. Everything here changes the bytes {@link VarkaLoopEmitter#emit} produces for a
 * given {@link VarkaShapeKey}, so it is part of that key rather than beside it.
 *
 * <p>Task 23 introduced this record to replace five {@code private static volatile} hook fields
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
 * <p><b>Defaults hash to what they always hashed.</b> {@link VarkaShapeCacheImpl#shapeHash}
 * renders these into the hash only when they differ from {@link #DEFAULTS}, so production hashes,
 * class names and telemetry are unchanged bit for bit and only the variants a suite asks for get
 * their own identity. They have to reach the hash at all because the cache's execution side table
 * is keyed on the hash alone while the map is keyed on the full key - options in one but not the
 * other would merge two variants' execution identities.
 *
 * @param groupBudget the most vector ops one emitted loop method may carry; see
 *                    {@link VarkaLoopEmitter#GROUP_BUDGET} for the measured reason it is 16, and
 *                    for the retuning question the parity benchmark prices by varying it.
 * @param fusedCeiling the most vector ops one emitted loop method may carry when the outputs
 *                     in it share a civil-from-days prefix (task 32 step B2): an output joins a
 *                     group past {@link #groupBudget} only when doing so lets it skip a prefix
 *                     the group already computes, and never past this. See
 *                     {@link VarkaLoopEmitter#FUSED_CEILING} for the ladder that set it.
 * @param cse whether shared subtrees are computed once and reused. Results must not change - CSE
 *            is an optimization, never a semantics change - and the emitter suite pins exactly
 *            that; the parity benchmark uses it to price CSE itself.
 * @param shareChronoPrefix whether two calendar nodes over the same date compute the
 *                          civil-from-days decomposition once between them rather than once each
 *                          (task 32 step B). Like {@link #cse} it is an optimization and never a
 *                          semantics change, and it is pinned the same way; unlike CSE it shares
 *                          <i>inside</i> a node's emitted run rather than between whole nodes,
 *                          which is why the emitter needs a separate notion of a fragment for
 *                          it. See {@code VarkaLoopEmitter.FragmentKey}.
 * @param denseValidityOnce whether a dense batch's value outputs have their validity bits set
 *        once by the driver rather than OR-ed in per lane group by the loop (task 45). On a
 *        dense batch the dispatcher has proven every referenced input null-free and task 11's
 *        invariant makes every value output valid on every row, so the loop's per-group call
 *        writes ones over ones. `false` reproduces the older bytes exactly and stays a
 *        reference variant the differential checks against, on {@link FloorMod7}'s precedent.
 * @param elideChronoMonth whether the civil-from-days prefix skips its March-month step in a
 *                         body where no tail reads the month (task 48). The year tail is the
 *                         one of the four fields that does not: it reads the January turn off
 *                         the day of year instead, which is the same test one step earlier in
 *                         the chain. Like {@link #cse} it is an optimization and never a
 *                         semantics change - the step it removes is dead work where it is
 *                         removed - and it is a switch only so the A/B stays re-runnable.
 * @param neriSchneiderMonth whether the month index and the day of month come out of one
 *        affine numerator (Neri-Schneider 2022, task 53) or from the magic multiply plus
 *        forward month-start this project shipped first. The two compute the same fields on
 *        two different month axes - March = 3 against March = 0 - and are differentially
 *        checked against each other, so the older one stays a live reference variant rather
 *        than dead code, on {@link FloorMod7}'s precedent.
 * @param julianMap whether the civil-from-days prefix takes the year of era through Ben Joffe's
 *        Julian map (task 54) - the day of era scaled by four, one division by 146097 for the
 *        century, four added back per century, and one division by 1461 for the year, whose
 *        remainder is the day of year with the leap day right by construction - or through the
 *        century-then-year split this project shipped first, with its leap-day underflow
 *        correction. Same fields either way, differentially checked against each other, so the
 *        older form stays a live reference variant, on {@link FloorMod7}'s precedent.
 * @param guardDayProducers whether a {@code date_add}/{@code date_sub} whose offset is a column,
 *        and whose result a calendar node reads, carries a per-lane check on that result against
 *        the range the civil-from-days lowering is exact over (task 52), declining the batch to
 *        the row engine when a lane leaves it. The compiler bounds every other day producer at
 *        compile time; this is the shape it cannot, so the check is at run time and at the
 *        producer rather than at each extraction (task 51 removed the latter). Off, a shape that
 *        existed at task 51 keeps task 51's bytes exactly and such a lane is computed wrongly
 *        rather than declined - a reference variant for the A/B that priced the guard, on
 *        {@link FloorMod7}'s precedent. This does <em>not</em> reach task 60's guard on an
 *        {@code add_months} / {@code date + INTERVAL n MONTH} column month count: that one is
 *        checked on the count itself rather than on a result, it guards the node's own magic
 *        multiply rather than a consumer's lowering, and the compiler's {@code dayRange} bounds
 *        such a count on the strength of it firing - so it is unconditional, like task 42's
 *        {@code make_date}. (The minus spelling declines at compile time, so no kernel exists
 *        for either setting to gate.)
 * @param validityByWidth whether a whole lane group's validity is read and written through the
 *        helper named for the emission's lane count - {@code orValidityBitsAt16} and its
 *        siblings, each with the general form's four-arm switch already resolved - and the
 *        concrete {@code VectorSpecies} constant baked in beside it (task 46). The general pair
 *        takes the lane count as an argument, so it carries a switch the caller cannot fold; at
 *        212 bytes the writer does not inline inside a fused loop, and one refused call costs
 *        1.87 to 3.24 ns per lane group at any width. Off, the emitter reads
 *        {@code SPECIES_PREFERRED} at run time and calls the general pair, which is task 45's
 *        bytes exactly - a live reference variant, on {@link FloorMod7}'s precedent, and the
 *        arm the A/B that priced this measures against.
 * @param validityOrFirst whether a value root's validity OR is emitted <i>before</i> its vector
 *        computation, wherever its word is already known - an input word in the masked body,
 *        the constant in the dense one - rather than after the store (task 46, second half).
 *        Same bytes, different order; what it changes is where C2's parser meets the call.
 *        Emitted last, after the body's Vector API intrinsics, the OR helper was refused with
 *        {@code NodeCountInliningCutoff} in every arm - a develop-only limit of 18000 nodes on
 *        the <i>caller</i>, which no size of callee can satisfy - and ran as a real call in the
 *        hot loop. Emitted first, it inlines. Off reproduces the after-the-store order, the
 *        reference variant for the A/B that priced this, on {@link FloorMod7}'s precedent.
 * @param lanesOverride the lane count to emit for, or 0 to emit for the JVM's own
 *        {@code IntVector.SPECIES_PREFERRED} - which is what production always does, so
 *        {@link #DEFAULTS} renders empty and production hashes do not move. It exists because
 *        one JVM has one preferred width and {@link #validityByWidth} has an arm per width: the
 *        suite drives 2, 4, 8 and 16 lanes, and a width with no specialised sibling, from a
 *        single run. A non-zero value must be a power of two; the emitted class carries the
 *        matching species, so it computes the right answers at that width wherever it runs,
 *        slowly if the hardware is narrower.
 * @param truncDate which lowering {@code trunc(date, ...)} uses at the {@code YEAR} and
 *        {@code QUARTER} levels (task 35): {@link TruncDateForm#SUBTRACT} takes the day of year
 *        off the date ({@code d - dayofyear + start}), {@link TruncDateForm#RECOMPOSE} rebuilds
 *        the period's first day from the year and month through {@code emitDaysFromCivil}. Same
 *        dates either way, differentially checked against each other, and {@code MONTH} follows
 *        the switch too so the recomposition has a third shape to agree on; whichever is not
 *        the default stays a live reference variant, on {@link FloorMod7}'s precedent.
 * @param floorMod7 which lowering {@code dayofweek}/{@code weekday} use for their mod-7.
 * @param misdescribeAdd emits {@code AddDays} against a deliberately wrong descriptor (an unerased
 *                       {@code IntVector} parameter instead of {@code Vector}). The class still
 *                       passes bytecode verification - member resolution happens at link time - so
 *                       the failure surfaces on first execution as a {@code NoSuchMethodError}
 *                       naming {@code IntVector.add}. The suite pins that, so a future descriptor
 *                       regression is diagnosable from the error alone.
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
    int lanesOverride,
    TruncDateForm truncDate,
    FloorMod7 floorMod7,
    boolean misdescribeAdd) {

  /**
   * The three mod-7 lowerings. {@link #MAGIC} is what ships: two 15-bit digit-sum folds followed
   * by an exact Granlund-Montgomery magic division (task 14's follow-up). The other two are the
   * reference variants the parity benchmark and the differential suite check it against -
   * {@link #DIGIT_SUM} is the full base-8 digit sum that shipped with task 11, and {@link #DIV} is
   * the certainly-correct lanewise divide, which scalarizes on every lane type this JVM has.
   */
  public enum FloorMod7 { MAGIC, DIV, DIGIT_SUM }

  /** The two {@code trunc(date, ...)} lowerings; see {@link #truncDate}. */
  public enum TruncDateForm { SUBTRACT, RECOMPOSE }

  /** What production always emits with; see the hashing note in the class doc. */
  public static final VarkaEmitOptions DEFAULTS =
      new VarkaEmitOptions(
          VarkaLoopEmitter.GROUP_BUDGET, VarkaLoopEmitter.FUSED_CEILING,
          true, true, true, true, true, true, true, true, true,
          0,
          TruncDateForm.SUBTRACT, FloorMod7.MAGIC, false);

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
    if (lanesOverride != 0 && (lanesOverride < 1 || Integer.bitCount(lanesOverride) != 1)) {
      throw new IllegalArgumentException(
          "lanesOverride must be 0 or a power of two: " + lanesOverride);
    }
  }

  /** {@link #DEFAULTS} with one field changed, for the suites and benchmarks that vary one. */
  public VarkaEmitOptions withGroupBudget(int budget) {
    return new VarkaEmitOptions(budget, fusedCeiling, cse, shareChronoPrefix, denseValidityOnce,
        elideChronoMonth, neriSchneiderMonth, julianMap, guardDayProducers, validityByWidth,
        validityOrFirst, lanesOverride, truncDate, floorMod7, misdescribeAdd);
  }

  public VarkaEmitOptions withFusedCeiling(int ceiling) {
    return new VarkaEmitOptions(groupBudget, ceiling, cse, shareChronoPrefix, denseValidityOnce,
        elideChronoMonth, neriSchneiderMonth, julianMap, guardDayProducers, validityByWidth,
        validityOrFirst, lanesOverride, truncDate, floorMod7, misdescribeAdd);
  }

  public VarkaEmitOptions withCse(boolean enabled) {
    return new VarkaEmitOptions(groupBudget, fusedCeiling, enabled, shareChronoPrefix,
        denseValidityOnce, elideChronoMonth, neriSchneiderMonth, julianMap, guardDayProducers,
        validityByWidth, validityOrFirst, lanesOverride, truncDate, floorMod7, misdescribeAdd);
  }

  public VarkaEmitOptions withShareChronoPrefix(boolean enabled) {
    return new VarkaEmitOptions(groupBudget, fusedCeiling, cse, enabled, denseValidityOnce,
        elideChronoMonth, neriSchneiderMonth, julianMap, guardDayProducers, validityByWidth,
        validityOrFirst, lanesOverride, truncDate, floorMod7, misdescribeAdd);
  }

  public VarkaEmitOptions withDenseValidityOnce(boolean enabled) {
    return new VarkaEmitOptions(groupBudget, fusedCeiling, cse, shareChronoPrefix, enabled,
        elideChronoMonth, neriSchneiderMonth, julianMap, guardDayProducers, validityByWidth,
        validityOrFirst, lanesOverride, truncDate, floorMod7, misdescribeAdd);
  }

  public VarkaEmitOptions withElideChronoMonth(boolean enabled) {
    return new VarkaEmitOptions(groupBudget, fusedCeiling, cse, shareChronoPrefix,
        denseValidityOnce, enabled, neriSchneiderMonth, julianMap, guardDayProducers,
        validityByWidth, validityOrFirst, lanesOverride, truncDate, floorMod7, misdescribeAdd);
  }

  public VarkaEmitOptions withNeriSchneiderMonth(boolean enabled) {
    return new VarkaEmitOptions(groupBudget, fusedCeiling, cse, shareChronoPrefix,
        denseValidityOnce, elideChronoMonth, enabled, julianMap, guardDayProducers, validityByWidth,
        validityOrFirst, lanesOverride, truncDate, floorMod7, misdescribeAdd);
  }

  public VarkaEmitOptions withJulianMap(boolean enabled) {
    return new VarkaEmitOptions(groupBudget, fusedCeiling, cse, shareChronoPrefix,
        denseValidityOnce, elideChronoMonth, neriSchneiderMonth, enabled, guardDayProducers,
        validityByWidth, validityOrFirst, lanesOverride, truncDate, floorMod7, misdescribeAdd);
  }

  public VarkaEmitOptions withGuardDayProducers(boolean enabled) {
    return new VarkaEmitOptions(groupBudget, fusedCeiling, cse, shareChronoPrefix,
        denseValidityOnce, elideChronoMonth, neriSchneiderMonth, julianMap, enabled,
        validityByWidth, validityOrFirst, lanesOverride, truncDate, floorMod7, misdescribeAdd);
  }

  public VarkaEmitOptions withValidityByWidth(boolean enabled) {
    return new VarkaEmitOptions(groupBudget, fusedCeiling, cse, shareChronoPrefix,
        denseValidityOnce, elideChronoMonth, neriSchneiderMonth, julianMap, guardDayProducers,
        enabled, validityOrFirst, lanesOverride, truncDate, floorMod7, misdescribeAdd);
  }

  public VarkaEmitOptions withValidityOrFirst(boolean enabled) {
    return new VarkaEmitOptions(groupBudget, fusedCeiling, cse, shareChronoPrefix,
        denseValidityOnce, elideChronoMonth, neriSchneiderMonth, julianMap, guardDayProducers,
        validityByWidth, enabled, lanesOverride, truncDate, floorMod7, misdescribeAdd);
  }

  public VarkaEmitOptions withLanesOverride(int lanes) {
    return new VarkaEmitOptions(groupBudget, fusedCeiling, cse, shareChronoPrefix,
        denseValidityOnce, elideChronoMonth, neriSchneiderMonth, julianMap, guardDayProducers,
        validityByWidth, validityOrFirst, lanes, truncDate, floorMod7, misdescribeAdd);
  }

  public VarkaEmitOptions withTruncDate(TruncDateForm form) {
    return new VarkaEmitOptions(groupBudget, fusedCeiling, cse, shareChronoPrefix,
        denseValidityOnce, elideChronoMonth, neriSchneiderMonth, julianMap, guardDayProducers,
        validityByWidth, validityOrFirst, lanesOverride, form, floorMod7, misdescribeAdd);
  }

  public VarkaEmitOptions withFloorMod7(FloorMod7 lowering) {
    return new VarkaEmitOptions(groupBudget, fusedCeiling, cse, shareChronoPrefix,
        denseValidityOnce, elideChronoMonth, neriSchneiderMonth, julianMap, guardDayProducers,
        validityByWidth, validityOrFirst, lanesOverride, truncDate, lowering, misdescribeAdd);
  }

  public VarkaEmitOptions withMisdescribeAdd(boolean misdescribe) {
    return new VarkaEmitOptions(groupBudget, fusedCeiling, cse, shareChronoPrefix,
        denseValidityOnce, elideChronoMonth, neriSchneiderMonth, julianMap, guardDayProducers,
        validityByWidth, validityOrFirst, lanesOverride, truncDate, floorMod7, misdescribe);
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
   * <p>"Every field" was not true until task 46: {@code truncDate} had been left out since task
   * 35, so two option values differing only in the {@code trunc} lowering rendered the same
   * string and shared one execution identity in the cache's side table, which is exactly the
   * hazard this class doc describes. {@code VarkaShapeCacheSuite} now walks the record's
   * components and fails if one of them cannot change the rendering, so the next field cannot
   * be forgotten the same way.
   */
  public String canonical() {
    if (isDefault()) {
      return "";
    }
    return "opts(" + groupBudget + '|' + fusedCeiling + '|' + cse + '|' + shareChronoPrefix
        + '|' + denseValidityOnce + '|' + elideChronoMonth + '|' + neriSchneiderMonth + '|'
        + julianMap + '|' + guardDayProducers + '|' + validityByWidth + '|' + validityOrFirst
        + '|' + lanesOverride + '|' + truncDate + '|' + floorMod7 + '|' + misdescribeAdd + ')';
  }
}
