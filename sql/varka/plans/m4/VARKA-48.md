# VARKA-48: a `year` that does not compute the month

## 1. Where this came from, and what changed under it

`m4/PLAN.md` section 2.18. The year tail needs one bit out of the
March-based month `mp`: whether the March year has turned January, which is
`mp >= 10`. Since `mp = (5 * doy + 2) / 153`, that bit is `doy >= 306` - an
integer identity, not an approximation (section 2 proves it) - and `doy` is
already in a local when the tail runs. So a kernel computing `year` alone never
needs the month step: one compare replaces a multiply, an add, a magic multiply
and a shift. It matters because `year` alone is what TPC-H q7, q8 and q9 run,
the only calendar extraction the headline corpus asks for, and the case every
later calendar task is compared against (1823 M rows/s at AVX-512 in the
committed parity file).

Section 2.18 also said, correctly, that this task and VARKA-32 step B were in
tension: "if VARKA-32's step B lands, the prefix computes `mp` for the month,
day-of-month and quarter tails regardless, and `year` reading `doy >= 306`
instead saves one op rather than five... whichever of the two lands second
inherits the smaller half of the win." **Step B1 landed first** (`VARKA-32.md`
section 7.1). Read literally, this task is therefore worth one op: the shared
`emitChronoPrefix` ends with the `mp` step unconditionally, whether or not any
tail that follows reads it.

This plan does not accept the smaller half. The `mp` step is dead work exactly
when no consumer of that prefix reads `marchMonth`, and the emitter knows its
consumers at plan time - so the step becomes conditional on them. For a
`year`-only kernel that is the full five-op win section 2.18 wanted; for
`year(d), month(d)` in one method it is correctly nothing, because the month
tail needs `mp` regardless; and it generalises for free to every other tail
that does not read the month - `dayofyear` (VARKA-34, PR #64) and VARKA-35's
`trunc(d, 'YEAR')` both test `doy >= 306` themselves and never touch `mp`.

## 2. The identity, proved rather than asserted

For `doy` in `[0, 365]` (the March-based day of year the prefix leaves in
`rem` after its overshoot correction), `mp = (5 * doy + 2) / 153` exactly -
`VarkaChrono.MONTH_M`/`MONTH_K` is an exact magic over dividends up to
`5 * 365 + 2 = 1827`, which is the whole domain. Then

```
mp >= 10  <=>  (5 * doy + 2) / 153 >= 10   (integer division, non-negative)
          <=>  5 * doy + 2 >= 1530
          <=>  doy >= 305.6
          <=>  doy >= 306                    (doy is an integer)
```

and the top of the domain is safe too: `mp(365) = 1827 / 153 = 11`, so the
March-based month never exceeds 11 and `>= 10` means exactly `{10, 11}` -
January and February. The constant is `VarkaChrono.MARCH_TO_JANUARY_DAYS = 306`,
the count of days from 1 March through 31 December. VARKA-34 (PR #64) already
declares it under that name and value for `dayofyear`'s own January test;
whichever of the two lands second drops its copy.

The proof above is three lines; it is also 366 cases, and 366 cases are
cheaper to run than to trust. Commit 2 adds a test that evaluates both sides
for every `doy` in `[0, 365]` through the shipped constants - the same
"recorded as an integer identity rather than an approximation" the milestone
asked for, with the record being a green assertion rather than this paragraph.
`verify_chrono_tails.py` has been using `doy >= 306` as the January test since
VARKA-34's recipe, so the identity has also been implicitly swept over every day
of `0001..9999` three times over; that is corroboration, not the proof.

## 3. The design: a prefix that knows its consumers

### 3.1 The year tail

`emitChronoYear` takes `rem` instead of `marchMonth`, and its January mask
becomes `rem >= MARCH_TO_JANUARY_DAYS` through a new
`emitJanuaryMaskFromDayOfYear(cb, rem)`. The existing
`emitJanuaryMask(cb, marchMonth)` stays for `emitChronoMonth`, which has `mp`
in hand anyway and whose bytes this task has no reason to move - the milestone
says "in the year tail only", and that is where it stays.

Every caller of `emitChronoYear` passes `t[2]` where it passed `t[5]`: the
`Year` arm in `emitChrono`, `emitAddMonths`, `emitChronoLastDay` (PR #78) and
VARKA-34's `DayOfYear` arm (PR #64). `rem` is intact at every one of those call
sites: each calls `emitChronoYear` first thing after the prefix, before
anything of its own writes a local.

One byte moves per year tail: `306` needs `sipush` where `10` fit `bipush`.
This is why section 6's first prediction is about bytes and not ops.

### 3.2 The conditional month step

The prefix's last five instructions - `rem * 5 + 2`, the magic multiply, the
shift, the store to `marchMonth` - are emitted only when some consumer of this
prefix reads `marchMonth`. Two pieces:

**`tailReadsMarchMonth(node)`**, a switch over the sealed `Chrono` family plus
`AddMonths`, the way `chronoChild` is written, so a new member is a compile
error rather than a silent "true":

| node | reads `mp`? | why |
|---|---|---|
| `Year` | no | this task |
| `Month`, `DayOfMonth`, `Quarter` | yes | `emitChronoMonth`, `emitMonthStart` |
| `AddMonths` | yes | month and day-of-month tails |
| `LastDay` (PR #78) | yes | `emitMonthStart` twice |
| `DayOfYear` (PR #64) | no | tests `rem >= 306` itself |
| `TruncDate` (VARKA-35) | `level != YEAR` | `MONTH`/`QUARTER` need the month; `YEAR` is `d - jdoy + 1` |

**The consumer set.** `planSlots` already walks every node of a body and
computes each calendar node's `FragmentKey`; it gains a
`Set<FragmentKey> fragmentsReadingMonth` on `Slots`, adding the key whenever
`tailReadsMarchMonth(node)`. `emitChronoPrefixOnce` then passes
`emitMonth = shareChronoPrefix ? s.fragmentsReadingMonth.contains(key)
: tailReadsMarchMonth(node)` down to `emitChronoPrefix`.

Two granularity points, both deliberate:

* **Per lane group, not per node and not per query.** *(Corrected against the
  code while implementing: this bullet and risk 1 originally said "per body",
  on the belief that `Slots` is planned per body method. It is not.
  `planSlots` receives the kernel's whole output list and walks
  `analysis.topoOrder`, and it is the `group` argument threaded into
  `emitBody`/`emitLaneGroup` that narrows a loop method to its own outputs. A
  body-scoped set would therefore have kept the month step in a `year(d)` loop
  method merely because `month(d)` is another output of the same kernel - the
  elision this task exists for, lost. `VarkaLoopEmitterSuite`'s "sharing the
  prefix leaves every loop method byte for byte as it was" caught it on the
  first run.)* The set is filled by `planFragmentsReadingMonth` at the top of
  `emitLaneGroup`, over the union of that group's outputs' subtrees, right
  beside the `emittedFragments.clear()` that has exactly the same scope - and
  that pairing is what makes it sound, since what has to hold is that every
  reader of `t[5]` *in this group* is preceded by a write of it in this group.
  Under today's `GROUP_BUDGET` a loop method holds one calendar output, so
  `year(d)`'s loop method elides and `month(d)`'s keeps, while the epilogue -
  one method over every output by VARKA-24's decision - sees both and keeps. If
  step B2 (`VARKA-32.md` section 10) later puts several calendar outputs in
  one loop method, the set widens with it and nothing here changes.
* **Order-independent.** With sharing on, the *first* sibling emits the
  prefix. If that sibling is `year(d)` and `month(d)` follows, the prefix must
  still have emitted `mp` - which it does, because the decision is read from
  the plan-time set over all consumers, never from whichever node happened to
  emit first. Section 5 tests both orders for exactly this reason. With sharing
  off, two nodes with equal keys do not share locals, so the decision is per
  node; keying it on the fragment there would make `year(d)` pay for a
  `month(d)` it shares nothing with.

### 3.3 The switch, and the default

`VarkaEmitOptions.elideChronoMonth`, default **on** once section 6's
measurement is in, kept as a switch so the A/B is re-runnable and so the
parity benchmark can carry both cases side by side, the way `shareChronoPrefix`
and `FloorMod7` do. `canonical()` is unchanged for `DEFAULTS`, so no production
shape hash moves; a non-default rendering adds the field in declaration order.

Shipping the default on does not rest on the timing. It rests on the step
being provably dead work where it is elided (section 2) and on the exhaustive
sweep (section 5); the timing is recorded because the repo's rule is that a
performance claim traces to a committed benchmark file, not because the change
needs a number to justify removing instructions nobody reads.

### 3.4 The scalar twin

`VarkaChrono.fromEra`'s year line becomes
`+ (dayOfYear >= MARCH_TO_JANUARY_DAYS ? 1 : 0)` so the model keeps mirroring
the emitted arithmetic - the class's own contract is that a disagreement
between the two is an emission bug rather than an arithmetic one, and that only
holds while they compute the same thing the same way. `VarkaChronoSuite`'s
exhaustive sweep is unchanged and must stay green, which is the milestone's
first validation criterion and is exactly what the identity guarantees.

## 4. Files

| file | what |
|---|---|
| `VarkaChrono.java` | `MARCH_TO_JANUARY_DAYS` with section 2 in its javadoc (or the same constant PR #64 already has, if it lands first); `fromEra`'s year line |
| `VarkaLoopEmitter.java` | `emitChronoYear(.., rem)` and its four call sites; `emitJanuaryMaskFromDayOfYear`; `tailReadsMarchMonth`; `Slots.fragmentsReadingMonth` filled in `planSlots`; `emitChronoPrefixOnce`/`emitChronoPrefix` taking `emitMonth`; `CHRONO_PREFIX_SLOTS` unchanged - `t[5]` is still allocated, just not always written |
| `VarkaEmitOptions.java` | `elideChronoMonth`, its `with...`, `canonical()` |
| `VarkaChronoSuite.scala` | the 366-case identity test |
| `VarkaLoopEmitterSuite.scala` | section 5's tests; the ladder test's unshared boundary re-pinned |
| `VarkaEmitterParityBenchmark.scala` + committed results | section 6 |
| `VARKA-32.md` 7.1, `m4/PLAN.md` debt register | the ladder's third move |
| `VARKA-35.md` 7.5 | one line: `tailReadsMarchMonth` is an edit site for `TruncDate` |
| `docs/sql-varka.md` | requoted only if the `year` figure moves outside noise |

Not touched: `ChronoVectorOps` in `sql/varka/engine`, the hand-written
reference kernel, which computes all four fields and so needs `mp` regardless.

## 5. Tests

1. **The identity**, 366 cases through the shipped constants (section 2).
2. **Op count, not timing**: a `year`-only dense loop method emits exactly
   four fewer `IntVector` invocations with the switch on than off - the
   deterministic deliverable, counted from the emitted class rather than the
   source (`VarkaEmitterTestSupport` already reads method bodies).
3. **Order independence**: `Seq(Year(d), Month(d))` and `Seq(Month(d), Year(d))`
   under sharing both emit `mp` in the epilogue and agree with `LocalDate` on
   the boundary matrix; `Seq(Year(d))` alone does not emit it.
4. **Sharing off**: `Seq(Year(d), Month(d))` unshared elides in the year
   node's own prefix and keeps in the month's.
5. **The exhaustive sweep** (`-Dvarka.sweep=true`) over all 16,777,216 covered
   days, five fields, both sharing modes, both switch positions, at both vector
   widths. This is the gate that matters; everything above it locates a
   failure this would only report.
6. **"sharing the prefix leaves every loop method byte for byte as it was"**
   stays green as written - both sides of its comparison change identically.
7. **The ladder**: `fields(n)` is four fields per date, so under sharing every
   fragment has a `Month` consumer and keeps `mp`; the shared boundary stays at
   44 (plus one byte per year tail). Unshared, every `Year` node's own prefix
   elides, so the epilogue at `fields(5).take(19)` loses about 140 bytes and
   the "19 crosses" assertion fails. Re-measure, re-pin, retitle, and record
   the third move of that number in `VARKA-32.md` 7.1 and the debt
   register - sharing moved it, the guard's removal moved it, and this moves
   it again, each for a different reason, which is exactly why VARKA-44 should
   measure its own baseline when picked up rather than inherit one.

## 6. The measurement

### 6.0 The benchmark that already exists, and the one that does not apply

There is a `year` benchmark, and it is the one every later calendar task has
been measured against: the `year: the calendar extractions against LocalDate
(VARKA-26)` section of `VarkaEmitterParityBenchmark`, 20M rows in 4096-row
chunks, five iterations over two-second windows, whose committed results
(`sql/catalyst/benchmarks/VarkaEmitterParityBenchmark-jdk25-results.txt`, AVX-512)
are the baseline this task measures against:

| committed row | Best (ms) | Rate (M rows/s) |
|---|---|---|
| `year, null-free` | 11 | 1823.4 |
| `year, mixed nulls` | 12 | 1717.4 |
| `year+month, separate (2 loop methods), null-free` | 22 | 913.7 |
| `year+month+day, separate (3 loop methods), null-free` | 33 | 604.8 |
| `year(d1), year(d2), two dates, shared option, null-free` | 22 | 891.4 |
| `per-row LocalDate year (the path Spark uses today)` | 42 | 479.4 |

*(Every kernel row in this table turned out to be stale by one task when it was
finally re-measured - the file had not been regenerated since VARKA-32 step B1,
and VARKA-51 landed in between. Section 9.2 has the replacements and the
attribution; this table is kept as what the task was planned against.)*

Those five kernel rows are exactly the ones whose `year` loop method elides
under this task, which is why they are listed and the shared-method rows are
not. Note the granularity the file reports: `year, null-free` is 11 ms best
time, so a 4% change is under half a millisecond and below the file's
resolution - which is the concrete reason section 6.1 predicts "inside noise"
as a legitimate result and why the comparison is by minimums across
regenerations rather than by one row against another.

The 128-bit baseline is not in the committed file: VARKA-26 recorded it in
`VARKA-26.md` section 11.2 (`year` 599 M/s, mixed nulls 566, against the
row path's 480) when it chose the shipped lowering. This task records its
128-bit A/B the same way, in section 9.

What does **not** apply is the engine module's JMH harness
(`DateVectorOpsBenchmarkTest` and its milestone-4 siblings under
`sql/varka/engine`). The milestone's debt register records that it measures a
degraded JIT state - it runs with `forks = 0` in the surefire JVM after the
JUnit suites - and cannot be trusted for an A/B today. It also has no `year`
case: `ChronoVectorOps` is the four-field reference kernel and always computes
the month. The parity harness is the instrument here, and the only one.

Two adjacent cases in the parity benchmark's year section - `year, month step
elided` and `year, month step kept` - null-free and mixed-null each, at both
vector widths, five regenerations of the file on an idle machine, compared by
**minimums** (`SKILLS.md`: single-run comparisons carried a +/-15% band on
this machine and turned several apparent small wins into noise). Adjacent
cases in one `Benchmark` are the interleaving: each regeneration runs both
back to back under the same JIT and thermal state.

The committed rows that can legitimately move: `year, null-free`,
`year, mixed nulls`, the two `separate (N loop methods)` rows (their `year`
loop method elides), and `year(d1), year(d2), two dates`. Rows whose loop
method holds a month consumer - every `shared (1 loop method)` row and the
four-field cases - move by at most the one byte per year tail and should
reproduce within noise; if one does not, the elision fired where it should
not have, and that is a bug, not a bonus.

### 6.1 Predictions, registered before the run

1. A `year`-only loop body drops from 45 to 41 `IntVector` ops
   (deterministic; test 2 asserts it). **Measured: 43 to 39.** The four-op
   delta is exactly right and is what the test pins; the absolute pair was two
   low in both terms, because the prediction counted the prefix's two
   `VectorMask` invocations, which `invocationCount` does not - it counts
   invocations whose owner is `IntVector`. An accounting miss, not a
   behavioural one.
2. `year, null-free` by minimums: elided over kept at 1.00x to 1.06x at
   AVX-512. Inside noise is a permitted and expected outcome - the body is
   latency-bound on the prefix's dependent chain, and the four ops come off
   the end of it rather than out of the middle.
3. At 128-bit the two are indistinguishable by minimums.
4. The unshared `HugeMethodLimit` crossing moves from 19 outputs to 20; the
   shared one stays at 44. **Held**, measured directly: unshared, 19 outputs
   are 7953 bytes and 20 are 8386; shared, 44 still crosses. The test's title
   and its comment's three-move history were updated with it.
5. No pinned oracle moves: neither the IR rendering nor `DEFAULTS`' canonical
   form changes.
6. `dayofyear` (PR #64) inherits the same elision with no change of its own
   beyond the `emitChronoYear` call-site rename, since it already tests
   `rem >= 306`.

## 7. Risks

1. **Getting the set's scope wrong.** *(This risk fired, in the opposite
   direction to the one predicted. The original text argued that a lane group
   "is not a scope" and that the set had to be per body; the truth is the
   reverse - see the correction in section 3.2. The set is computed per lane
   group, from that group's outputs, precisely because `emittedFragments` is,
   and the two have to agree: a fragment is re-earned in each lane group, so
   what must hold is that every reader of `t[5]` in a group is preceded by a
   write of it in that group.)* The scope is the whole risk here, and the test
   that catches a wrong one is "sharing the prefix leaves every loop method
   byte for byte as it was", which compares the shared and unshared emissions
   of the same roots and so fails the moment the two disagree about which
   consumers count.
2. **A consumer that reads `mp` without saying so.** Any tail that reads
   `t[5]` must return true from `tailReadsMarchMonth`, or it reads an
   uninitialised local - which the JVM verifier rejects at class load, loudly,
   rather than silently. That is the right failure mode, and the exhaustive
   switch over the sealed family is what keeps a future tail from reaching it.
3. **The one-byte drift.** `sipush 306` against `bipush 10` adds a byte per
   year tail to every method containing one. Only the epilogue is near a
   limit, and at 44 shared outputs it gains eleven bytes on a number already
   past 8000; harmless, but it is why prediction 4 says "stays at 44" rather
   than "unchanged".
4. **Numbers moving under the task's own feet.** Commits 2 and 3 both touch
   emitted bytes; regenerate the parity file once, in commit 4.
5. **PR #64 and PR #78 in flight.** Both call `emitChronoYear`; whichever
   lands after this task updates one argument at its call site, and #64
   additionally drops its own `MARCH_TO_JANUARY_DAYS`. Neither is a conflict a
   merge would resolve wrongly - the compiler refuses the old signature.

## 8. Sequencing

Four commits, each green on its own:

1. **This plan.**
2. **The identity**: `MARCH_TO_JANUARY_DAYS` and its javadoc, `fromEra`'s year
   line, the 366-case test; `emitChronoYear` taking `rem` at all four call
   sites and `emitJanuaryMaskFromDayOfYear`. No elision yet, so the only byte
   change is the `sipush`, and the ladder does not move.
3. **The elision** behind `elideChronoMonth`: `tailReadsMarchMonth`, the
   per-body consumer set, the conditional step, tests 2 through 5 and 7, the
   ladder re-pinned, `VARKA-32.md` 7.1 and the debt register updated.
4. **The measurement**: the A/B cases, five regenerations, the results file,
   the number recorded in section 9 with the default confirmed or reversed on
   it, `docs/sql-varka.md` requoted only if `year` moved outside noise.

## 9. Outcome

### 9.1 The A/B, by minimums

Five regenerations of the parity file at each width on an idle machine, the two
sides adjacent in one `Benchmark` so each regeneration runs them back to back.
Best rate over the five, in M rows/s:

| case | month step elided | month step kept | ratio |
|---|---|---|---|
| AVX-512, `year, null-free` | 2201.9 | 2181.7 | 1.01x |
| AVX-512, `year, mixed nulls` | 2161.7 | 2145.2 | 1.01x |
| 128-bit, `year, null-free` | 746.3 | 742.7 | 1.00x |
| 128-bit, `year, mixed nulls` | 751.9 | 750.1 | 1.00x |

**Inside noise, and the sign is not stable**: in two of the five AVX-512 runs
the *kept* side won the mixed-nulls pair, and in two of the five 128-bit runs it
won as well. The file this branch finally commits, regenerated once more after
the leap-flag rewrite landed, is one of those runs - `year, mixed nulls` reads
2131.3 elided against 2167.7 kept, which is the kept side ahead by 1.7%. That is
not a contradiction of the table below; it is the table's own claim, visible in
the committed artifact. A reader who compares the two should conclude the effect
is unmeasurable here, which is what this section says. Four ops off a 43-op body at a 9 ms best time is under half a
millisecond, which is what section 6.0 said the file cannot resolve, so this is
the outcome that section predicted rather than a disappointment. The default
ships on the argument section 3.3 makes - the step is provably dead work where
it is elided, and the exhaustive sweep says the answers do not move - with the
number recorded because the repo's rule is that a performance claim traces to a
committed file, not because the change needs one.

*(Superseded in size, not in direction, by VARKA-53: once the month block moves
to Neri-Schneider's single-numerator form, a `year`-only kernel elides a two-op
numerator rather than a four-op magic-multiply step. The elision stays correct
and stays a win; see `VARKA-53.md` section 1.)*

### 9.2 What moved in the committed file, and why it is not this task

The regeneration moves the calendar rows far more than the A/B above can
account for, and the difference belongs to **VARKA-51**, not here:

| committed row | before | after |
|---|---|---|
| `year, null-free` | 1823.4 | 2166.5 |
| `year, mixed nulls` | 1717.4 | 2046.0 |
| `year+month, separate (2 loop methods)` | 913.7 | 1042.8 |
| `year+month+day, separate (3 loop methods)` | 604.8 | 674.6 |
| `year(d1), year(d2), two dates` | 891.4 | 1040.9 |
| `year+month+day+quarter, shared (1 loop method)` | 799.8 | 797.7 |
| `dayofweek, for scale` | 7665.1 | 7759.6 |
| `per-row LocalDate year` | 479.4 | 481.3 |

Three things establish the attribution rather than assert it. The scalar anchor
`per-row LocalDate year`, which no Varka change touches, reads 481.1-481.6
across all five runs against the committed 479.4, so the machine is in the same
state and the kernel rows moved for a real reason. The A/B pair inside a single
run puts this task's own contribution at 1.01x. And the file was last
regenerated at `06d96642707` (VARKA-32 step B1), after which exactly two commits
touched the emitter before this task: `71ebc645605`, VARKA-51's removal of the
per-extraction range guard - two compares, ANDed with validity and the epilogue
mask and ORed into an accumulator, on every calendar node's tail - and
`cb176a077eb`, VARKA-38's column offsets for `date_add`/`date_sub`, which no
calendar kernel runs. By elimination the movement is VARKA-51's, and its shape
fits: the guard was paid once per calendar node, so a one-field kernel gains
about a fifth and the four-field shared kernel, which pays it once for four
tails, does not move at all.

The 128-bit `year` figure moves the same way, 599 M rows/s in `VARKA-26.md`
section 11.2 to 746.3 here.

*(Update, after the fact: the `add_months` benchmark PR landed on master before
this one and regenerated the file first, so it - not this task - is where the
catch-up above actually appears in the history. This section is left as written,
because it is the record of how the discrepancy was found and attributed, and
because the argument is what makes the attribution checkable. What changed is
only which commit carries the numbers; this branch was re-merged and the file
regenerated once more on the merged code, so what it commits is a single
coherent measurement rather than half of each.)*

**VARKA-51 shipped a 19% improvement to every single-field calendar kernel and
never regenerated the parity file**, which is the process finding worth keeping
out of this section's arithmetic: a task that shrinks emitted bytecode has to
regenerate, or the next task to regenerate inherits its win and has to prove it
did not cause it.

### 9.3 The predictions, scored

1. **Miss, in the absolute only.** 43 to 39 `IntVector` ops, not 45 to 41; the
   four-op delta is exact. See section 6.1.
2. **Held.** 1.01x at AVX-512, inside noise, which the prediction admitted as a
   legitimate outcome.
3. **Held.** 1.00x at 128-bit, indistinguishable.
4. **Held.** The unshared `HugeMethodLimit` crossing moved from 19 outputs to
   20; the shared one stayed at 44.
5. **Held.** No pinned oracle moved - neither the IR renderings nor `DEFAULTS`'
   canonical form, and `VarkaShapeCacheSuite` stayed green untouched.
6. **Carried forward.** `dayofyear` (PR #64) inherits the elision through
   `tailReadsMarchMonth` returning false for it; it is still in flight, so the
   arm and its call-site rename land with whichever of the two merges second.
