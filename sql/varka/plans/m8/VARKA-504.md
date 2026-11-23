# VARKA-504: Every check the done-when rests on proves it can fail

## 0. What exists now (admission check)

From master at `b9c587374b6`. For each check the row names, the plan read its suite or script
for a planted fault (a test that breaks the thing checked and requires the check to fail) and
for a completeness assertion (a test that the set iterated is the whole of what the code holds).
Paths are under `sql/catalyst/src/test/.../codegen/varka/` or `sql/core/src/test/.../execution/`
unless they say otherwise.

| check | planted fault today | completeness today | gap |
| :-- | :-- | :-- | :-- |
| coverage (`VarkaCoverageSuite`) | yes, for the domain rule only: "a guarded row with no domain, a domain on a row with no guard, and a domain that is not a boolean all fail the check" (`VarkaCoverageSuite.scala:977-990`) | every matched class documented (the suite's own check) | no plant for the subclass rule (a subclass row covering a superclass arm, 27 October) or for a row that stops fusing |
| census (`dev/varka_census.py --check`) | none: its two doctests are on `casts_fused` (`:810`) and `conformance_errors` (`:953`), not on the claim rules | none in reverse (row 487, `VARKA-487.md` 0.3) | a plant per claim rule, and the second-species tell that had no live case |
| cast matrix (`VarkaCastMatrixSuite`) | none | every ordered pair, every mode (`:112`), every form (`:231`) | a plant: a legality or fusion answer flipped must fail |
| conformance base (`VarkaTypeConformance`) | none | per lane, by construction | a plant per contract (load, round trip, null handling, ordering, `:190-233`) |
| differential (`VarkaCoverageDifferentialSuite`, `VarkaDifferentialSuite`) | yes, in the first: planted outcomes and a planted kernel (`VarkaCoverageDifferentialSuite.scala:233-295`); none in the second | none | a plant in `VarkaDifferentialSuite`; the cached-against-uncached fault of row 501 as a plant |
| IR fuzzer (`VarkaIrFuzzSuite`) | yes: a planted liveness bug and a planted class bug (`VarkaIrFuzzSuite.scala:857`, `:873`) | the grammar's reach lists name what is undrawn | none for this row (the grammar is row 352's) |
| Spark fuzzer (`VarkaSparkFuzzSuite`) | yes: a planted failure shrunk (`:406`), a planted kernel fault after the handover (`:534`), a condition on no entry (`:356`) | none | the NaN filter pruned by Spark's cache on both sides (SPARK-58482) as a plant |
| warm-up reach (`VarkaWarmupReachSuite`) | yes: `plantedOmission` (`VarkaWarmupReachSuite.java:81-97`) | every emitted receiver gets its calls (`:66`) | completeness per lane: the float-masks-credited-to-double fault of 27 October as a plant |
| bytes oracle (`VarkaEmittedBytesSuite`) | none | none: a row it cannot build is skipped into `coverage_rows_skipped` without failing (row 495) | a plant (one byte changed fails the pin), and row 495's skip-fails rule |
| reproduction parser (`VarkaReproductionSuite`) | row 502's plan registers four plants (`VARKA-502.md` 3, item 2) | `everyNodeTypeRoundTrips` over the sealed IR (`VarkaReproductionSuite.java:160-179`) | owned by row 502 |
| proofs runner (`dev/varka_prove.sh`) | yes: `--self-test`, "one planted failure of each kind, every one caught" (`:26`) | `VarkaDivisorProofSuite` holds the long divisors both ways (row 496) | `VarkaProofFilesSuite` checks rendering only; the int-lane divisors are open (row 496's note) |

**The upstream workarounds.** SPARK-58482 is named in `VarkaFloatingLaneSuite`,
`VarkaSparkDifferential` and `VarkaCoverageDifferentialSuite`. The JIT's messageless exception
(`OmitStackTraceInFastThrow`) is handled in `VarkaExpressionHarness`,
`VarkaFilterRaiseOrderSuite` and `VarkaMaybeThrows`. No test pins either behaviour: if Spark
stopped pruning, or the JVM kept messages, the workarounds would stay and nothing would say they
had become dead.

### What differs from the row text

- Five of the ten checks already have a planted fault (coverage in part, differential in part,
  both fuzzers, warm-up reach, proofs). The row's work is the other five and the completeness
  half.
- Row 502 (planned) owns the reproduction parser's plants, and row 495 owns the bytes oracle's
  silent skip. This row cites them rather than doing their work.
- Row 487 (planned) adds the census's reverse check. This row adds the census's plants and does
  not wait on 487.

## 1. Goal and non-goal

**Goal.** Each check that the done-when list (items 1 to 4 and 7) rests on has a committed test
that plants the fault it exists for and requires it to fail. Where it iterates a set, it has a
completeness assertion. Each upstream workaround names its upstream issue and has a test that
fails when the behaviour it works around goes away.

**Non-goal.** No change to what any check accepts on master: every check passes before and after.
No new checks beyond the plants and the completeness assertions.

## 2. Design

### 2.1 One plant per gap, committed

A plant is a committed test that builds the broken input in memory and runs the real check on
it. It never edits a committed file, and its assertion names the expected failure. One per gap of
the table:

1. **Coverage, subclass rule**: a synthetic family whose only row has a subclass node of an arm
   the compiler matches by superclass; the documentation check must name the superclass as
   undocumented.
2. **Census**: doctests in `dev/varka_census.py` that build a census line in a temporary tree
   claiming a type with no row, a `~` claim with no quoted row, and a fused type outside its
   types. Each must report. Also a doctest for the second-species tell, with a live case.
3. **Cast matrix**: a test that hands the legality comparison a `casts.tsv` with one pair's mode
   flipped, and the fusion comparison a `casts_fused.tsv` line changed. Each must fail, naming
   the pair. That needs the comparisons to take their input as an argument (**[I]**: the plan
   did not check whether they read the committed file directly).
4. **Conformance**: for each contract, an evaluator stub that answers one lane's value wrong (a
   flipped sign on a load, a dropped null, an ordering with NaN first). The contract must fail
   on it.
5. **Differential**: in `VarkaDifferentialSuite`, a planted outcome as in
   `VarkaCoverageDifferentialSuite.scala:233-260`. Plus row 501's fault, a cached plan compared
   with an uncached one, planted and required to fail.
6. **Spark fuzzer, SPARK-58482**: a NaN filter over a cached relation whose batch both sides
   prune. The fuzzer's check must notice that no batch reached the kernel (a vacuous pass) and
   fail.
7. **Warm-up reach, per lane**: a call to a `FloatVector` mask method recorded under the double
   lane; the reach must fail naming the float receiver.
8. **Bytes oracle**: one shape's recorded bytes changed in memory; the pin must fail naming the
   shape. The skip rule is row 495's.

### 2.2 Completeness assertions

Where a check iterates a set the code defines, it asserts the set is all of it: the cast matrix
over `Cast`'s legal pairs (in place), the conformance contracts over every `Lane` (an exhaustive
switch, so a new lane fails to compile), the warm-up reach over every lane's receiver classes,
and the differential suites over every coverage row. Each is one assertion beside the check,
modelled on `VarkaDivisorProofSuite`, which fails both on a missing member and on a listed member
no code builds.

### 2.3 Upstream workarounds pinned

For SPARK-58482: a test that runs Spark's cache pruning on a NaN filter, asserts the pruning
still happens (the batch count), and names the issue. If Spark fixes it, the test fails, and the
workaround and the test are retired together. For the messageless exception: a test that throws
the same exception in a hot loop and asserts the message is lost after C2 compiles it, naming the
flag. Each test's name says which workaround it guards.

### 2.4 "Fails the build instead"

A check that can pass vacuously asserts that it ran on something: a positive count of rows,
batches, pairs or calls checked, as `VarkaMemorySanitizer.checked()` does for the sanitizer. That
count goes in the check's own assertion, so a vacuous run fails.

## 3. Tests and proofs

The plants of 2.1, the assertions of 2.2 and the pins of 2.3 are the tests. Each plant is first
run against a scratch revert of the check it targets, to show that the plant fails the check and
not something else. The revert is recorded in section 10, never committed.

## 4. Predictions and costs, registered before the build

- P1: every plant passes on master, meaning the check fails on the planted input as it should.
  A plant that the check does not catch is a finding about that check. It is fixed in this row if
  small, or filed.
- P2: at least one of the eight gaps finds such a check (**[I]**: six of the 27 October checks
  passed vacuously before they were looked at).
- P3: the plants add under a minute to the catalyst and sql suites together, since each builds an
  in-memory input. That is to be read against the calibration table before the build.

## 5. Order, files and size

One pull request per module so CI's scoping holds, in this order: the Python census doctests;
then catalyst (coverage, cast matrix, conformance, reach, bytes); then sql (differential, Spark
fuzzer, upstream pins).

| File | Change |
|---|---|
| `dev/varka_census.py` | the claim-rule doctests |
| `VarkaCoverageSuite.scala` | the subclass plant |
| `VarkaCastMatrixSuite.java` | the two plants, the comparisons taking their input |
| `VarkaTypeConformance.java` and its suites | the per-contract plants |
| `VarkaWarmupReachSuite.java` | the per-lane plant and completeness |
| `VarkaEmittedBytesSuite.scala` | the pin plant |
| `VarkaDifferentialSuite.scala`, `VarkaCoverageDifferentialSuite.scala` | the plant, the cache plant |
| `VarkaSparkFuzzSuite.scala` | the vacuous-batch plant |
| `VarkaFloatingLaneSuite.scala` (or a new pin suite), `VarkaMaybeThrows.scala` | the upstream pins |
| `sql/varka/skills/testing-and-debugging.md` | the rule: a check ships with its plant |

Size: medium, as the row says. It may split into two rows at the catalyst/sql boundary if the
owner prefers smaller pull requests (question 2).

## 6. Out of this row

- The reproduction parser's plants (row 502) and the bytes oracle's silent skip (row 495).
- The census's reverse check (row 487).
- The int-lane divisor proofs that row 496 left open.
- A lint that requires every new check to carry a plant. It belongs with `dev/varka_lint.py` if
  the owner wants it.

## 7. Questions for the owner

1. **Plants as committed tests, or as a script run once?** Recommended: committed. A plant run
   once proves the check worked on that day; a committed one keeps proving it.
2. **One row or two?** Recommended: one plan, two pull requests, catalyst first. The row closes
   when both merge.

## 8. Done when

- every check named in the row has a committed plant that it fails on, or cites the row that
  owns it (502 for the parser, 495 for the bytes oracle's skip);
- the completeness assertions of 2.2 hold;
- each upstream workaround has a pin test that names its issue;
- each check that could pass vacuously asserts a positive count of what it checked.

## 9. Open points for the lead

1. The row's list of six vacuous checks from 27 October names fixes in rows 485 and 501 and in
   other reviews. The plan did not trace whether the warm-up reach's float-to-double crediting
   and the coverage subclass rule were fixed in code, or only noted. If they were fixed, 2.1
   items 1 and 7 are regression plants. If not, they are the first two findings.
2. Plant 3 assumes the cast matrix's comparisons can take an input other than the committed
   files. If they cannot, the refactor that lets them is the first commit.

## 10. Outcome

### 10.1 Part 1, built 20 November 2026 (branch `varka-504-checks-can-fail`)

From master at `6ac76f86cb3`. The census's doctests and the catalyst plants, in 5's order; the
sql plants (differential, Spark fuzzer, the upstream pins) are part 2 and not built.
*Corrected 22 November 2026:* one of the two upstream pins, the `Math.addExact` one, is built
here (below); only the SPARK-58482 pin, which needs a session, is part 2's.

- **Census** (`dev/varka_census.py`): doctests on `row()`, one planted claim per rule (a fused
  type outside the entry's types, a claim no coverage row shows, a `~` claim quoting no row, a
  type both fused and fused in part, a part-fused type outside the types, a need the fused types
  contradict, an unknown need) beside a true claim that reports nothing. `--self-test` passes.
- **Coverage** (`VarkaCoverageSuite`): the two checks became functions over their input,
  `declinedRows` and `undocumentedArms`, and a test plants a superclass arm (`BinaryOperator`
  beside an `i + 1` row) and two rows that stop fusing (a `rand(1)` value and a predicate with a
  `rand` conjunct). The fusion test now also asserts it checked more than 100 rows.
- **Cast matrix** (`VarkaCastMatrixSuite`): 9.2 held - both comparisons read the committed files
  directly. They became `legalityErrors(file)` and `fusionMoves(committed, fresh)`; one plant
  flips the first legal pair's ANSI answer, another changes one `casts_fused.tsv` answer and a
  third swaps two lines. Both tests assert they compared more pairs than there are types.
- **Conformance** (`VarkaTypeConformance`, new `VarkaConformancePlantSuite`): the answer the
  checks hold the reference to passes through `plantedAnswer`, a stub that is null (Spark's own)
  unless a test sets it; the plant suite sets it on instances of the type suites to a value off
  by its low bit (load, round trip, null handling, ordering over INT must each fail), a null
  answered as zero (null handling) and a NaN comparison answered the other way (ordering over
  DOUBLE). A first version overrode a method in anonymous subclasses; sbt's discovery then
  found the subclass and JUnit warned that it must not be anonymous, so the stub became a field.
- **Upstream pin** (new `VarkaUpstreamPinsSuite`): a hot `Math.addExact` overflow loses its
  message under `OmitStackTraceInFastThrow`, which three helpers accept; the pin fails when a
  JDK keeps it. The SPARK-58482 pin needs a session and is part 2's.
- **Warm-up reach** (`VarkaWarmupReach`, its suite): `laneErrors(receivers)` names any receiver
  class not of its lane's element type; the suite asserts none and that every `LaneType` is read
  (completeness), and plants the 27 October fault, the float lane answered with the double lane's
  receivers.
- **Bytes oracle** (`VarkaEmittedBytesSuite`): the difference walker became
  `movedBetween(committed, rendered)`; the plant changes one coverage method's hash and one fuzz
  block in the committed file in memory and requires exactly those two to be named.

**9.1 answered.** Both 27 October faults are fixed in code, so plants 1 and 7 are regression
plants: the coverage check compares exact simple class names (`getClass.getSimpleName`), and
`VarkaWarmupReach.receivers` maps each lane to its own species.

**Where the plan was wrong.** 2.1 item 2 put "the second-species tell" under the Python census.
The tell is `VarkaOperatorCensusSuite`'s (the operator census, `VARKA-347.md`), not
`dev/varka_census.py`'s, and its matcher already has a string-level plant ("the second-species
tell matches a species class and not a shared superclass"). The live case - a loop whose one
call chain meets two species, read from C2's printer in a forked JVM - is not built: it is a
JVM-forking probe cell, out of this window's cheap-checks rule, and owed to part 2 (built
there, 10.3).

**Run in this window.** One targeted run, `catalyst/testOnly *VarkaConformancePlantSuite
*VarkaUpstreamPinsSuite`: 4 of 4 passed (the plant suite in its own JVM, 6.8 s; the pin 0.05 s,
the message dropped within the first ten thousand overflows). The other plants and every
refactored check have compiled and not run.

**Predictions.** P1 holds for the four plants that ran: each check failed on its planted input,
and on the disagreement it exists for. P2 is not yet scored: no plant has found a check that
missed its fault so far, and the rest are owed. P3 is not scored: the suites' times are owed.

### Decisions taken while the owner was away (20 November 2026)

1. **Plants as committed tests** (7, question 1): the plan's recommendation, taken.
2. **Pull requests** (7, question 2, and 5): the plan's own text disagrees with itself - 5 says
   one pull request per module (three), 7 recommends two, catalyst first. Taken: the census
   doctests ride with the catalyst part, since a Python doctest adds no CI module of its own and
   both stay under the size cap; sql is part 2.
3. **The conformance plant's stub is on the expected side.** 2.1 item 4 asked for "an evaluator
   stub that answers one lane's value wrong". The contract compares the reference with Spark's
   answer per row and the kernel with the reference, so the stub replaces Spark's answer; a check
   that compared nothing passes it, which is the fault the plant exists for. Swapping the kernel
   for a wrong one would need an emitter-level plant per lane, larger than the row's medium.
4. **Plants that need no expensive input read the committed file instead.** The cast matrix's
   fusion plant and the bytes plant use the committed file as the "fresh" side, so they run in
   milliseconds instead of probing every cast or emitting every shape; the comparison they plant
   into is the same code the real tests call.
5. **The fuzzer's SPARK-58482 plant lands in the pivot check** (10.3), not in a new count of
   batches that reached the kernel as 2.1 item 6 put it: the pivot check already fails a case
   whose pivot row neither engine read, which is the vacuous pass, and a batch count would need
   a new metric through the exec nodes for the same verdict.
6. **The warm-up reach's plant targets the lane mapping, not the counting.** 2.1 item 7 put the
   plant in the reach's own path (`targets`, `underMark`). The 27 October fault lived in the
   receivers each lane maps to, so the plant is on `laneErrors` over `receivers`, a check of its
   own beside the reach; the counting path keeps its existing plant, `plantedOmission`.

### 10.2 Review of #1634 at `2e8a71502f3` (22 November 2026)

`/code-review high` found one important finding and five nits; all six are fixed.

- **Important.** The coverage plant's "a declined row names its reason" assertion could not
  fail: `String.split` drops a trailing empty string, so `"row  ->  "` split to the row alone,
  whose `last` is not empty. The assertion now takes the text after the arrow.
- The predicate plant's `rand` conjunct made the whole condition nondeterministic, which
  declines every conjunct, so a check satisfied by one fused conjunct still flagged it. The
  plant is now deterministic: `coalesce(i, 0) > 0` fuses and the month of a year-month
  interval stays residual, and the plant asserts that split before it asks the check. A
  nullable `i > 0` would not do: the order rule demotes a conjunct that can be NULL ahead of a
  residual one that can raise, and every conjunct off its list of error-free nodes can raise.
- The `Math.addExact` pin skips unless the JVM runs C2 with `OmitStackTraceInFastThrow`; on C1
  alone, the interpreter or a JVMCI compiler the message is kept and the pin has nothing to pin.
- Row 504's status and 10.1's part-2 line, corrected above with this date.
- Three comments dated the fault instead of stating it, and two `var`s did not name their type.

Checked by hand on the laptop. The plant test passed; it also asserts that a row with an empty
reason fails the reason check. With `declinedRows`' `forall(_.fused)` weakened to
`exists(_.fused)` (a scratch edit, reverted), the plant failed, naming only the `rand(1)` row.
The pin passed under the default JVM and was skipped under `-XX:TieredStopAtLevel=1`.

### 10.3 Part 2, built 20 November 2026 and run 23 November 2026 (branch `varka-504b-sql-plants-r`)

- **Differential** (`VarkaDifferentialSuite`): `checkDifferential` takes an optional
  `expectedQuery` for the row engine, and a test plants a wrong answer (`date_add(d, 8)`
  against `date_add(d, 7)`) on the fused path and on the vanilla one; both must fail on
  `checkAnswer`. The same test plants row 501's fault: the row engine's plan over the table
  with its cache dropped, against Varka's cached plan, must fail `sameCacheReads`.
- **SPARK-58482 pinned** (`VarkaFloatingLaneSuite`): a cached one-row batch of NaN, filtered by
  `NOT (x < 1.5D)`, keeps its row with the cache's pruning off and loses it with the pruning
  on. If Spark fixes the statistics, the test fails, and the pruning-off workarounds in this
  suite, `VarkaSparkDifferential` and `VarkaCoverageDifferentialSuite` retire with it.
- **Spark fuzzer, SPARK-58482** (`VarkaSparkFuzzSuite`): a cached one-row NaN view on both
  sessions, filtered by `NOT (x < 1.5D)`. With the pruning off the engines agree and the pivot
  row is selected; with it on they still agree (no difference), and the fuzzer's pivot check,
  `pivotMissing`, must report the row missing. That is the vacuous pass of 2.1 item 6, caught
  by the check the fuzzer already runs on every pivot case rather than by a new batch count.
- **Operator census, the second-species tell's live case** (`VarkaOperatorCensusProbe`,
  `VarkaOperatorCensusCatalog`, `VarkaOperatorCensusSuite`): a planted cell,
  `planted.bimorphic`, whose one `lanewise` call site meets the 128-bit and the 64-bit int
  species alternately, so C2 inlines it for both receiver classes and its printer says "callee
  changed to" the second. The suite forks it at the host's width and requires the tell, with the
  planted fallback's one-species loop as the control. It is assumed away below 256 bits, where
  one of the two would be the preferred species. It ran at 512 bits and the tell tripped.
- **The workaround sites name their pins** (the row's last clause): `checkFused` in
  `VarkaFloatingLaneSuite`, both `withFloatingPruningOff`, `VarkaMaybeThrows.sameError`,
  `VarkaExpressionHarness.sameError`, `VarkaAnsiForm.isExactLongOverflow` and the messageless
  test of `VarkaFilterRaiseOrderSuite` each point at the test that pins their upstream behaviour.
- **Positive counts (2.4)** added where a check could run over nothing: the bytes oracle's pin
  asserts more than 100 coverage rows reach it, `dev/varka_census.py --check` fails when no
  entry is in scope, and the Spark fuzzer's main test asserts that it compared and fused
  something itself (fused only for a run of more than one iteration, so a one-iteration replay
  still works). The fuzzer's counters belong to the suite, so the test reads what they gained
  during it, not their totals. `VarkaCoverageDifferentialSuite` already asserts it read at least
  57 rows.

**Each plant against a weakened check** (section 3; scratch edits, reverted, on the laptop at
512 bits, 23 November 2026). Every plant failed, and each on its own assertion:

- Wrong answer, fused: `checkDifferential`'s fused `checkAnswer` calls replaced by a
  `collect()`; the plant failed at its fused `intercept`, no failure thrown.
- Wrong answer, vanilla: the unfused `checkAnswer` alone replaced the same way; the plant
  failed at its unfused `intercept`.
- Cached against uncached: `sameCacheReads`' comparison of the two plans' cached reads made
  true; the plant failed at its cache `intercept`.
- Fuzzer pivot: `pivotMissing` made to return `None`; the plant failed, `(None, None)` for the
  expected `(None, Some("pivot row not selected by Varka"))`.
- SPARK-58482 pin: the pruned run forced to the pruning off, as if Spark had fixed it; the pin
  failed with its "SPARK-58482 looks fixed" message.
- Second-species tell: the reading's `bimorphic` forced false, and the plant failed on the
  bimorphic cell; forced true, and it failed on the control. No other census test failed with
  it forced false, as the census asserts only the tell's absence.
- Census count: `--check` over a build with every in-scope entry dropped (a Python scratch
  patch) failed with "no entry is in scope".

A first wrong-answer mutation that dropped `checkAnswer` without running the query failed on
`assertKernelsRan` instead; the plant's check that the failure says "Results do not match"
caught that it was the wrong failure, so the mutation was redone to run the query.

**What the part 2 plants cover, and what they do not.** The fuzzer plant calls the fuzzer's
`difference` and `pivotMissing` on a hand-built NaN case; it does not go through
`disagreement` and `checkOnce`, which wrap a floating case in the pruning-off workaround. So it
shows that the pivot check catches the pruned pivot, and with the pin, that the workaround is
still needed; it would not catch `checkOnce` dropping its pivot check. Driving a case through
`checkOnce` with the workaround off needs a seam in `VarkaSparkDifferential`, not built.

**Left of the plan, not built.**
- 2.2's "differential suites over every coverage row" as a completeness assertion: the
  coverage differential asserts a floor (57 rows), not that it runs every row of
  `coverage.json`; the conformance contracts' "every Lane" holds through the census's
  `conformance_errors`, which fails a landed type with no suite and no owing row.
- The plants of a changed option arm and of a skipped coverage row.

**Predictions.** P1 holds for every plant of both parts: each passed unweakened and failed on
its weakened check. P2 holds on one check: the Spark fuzzer's main test passed whatever it
compared, and its first count read the suite's totals, which other tests also raise. P3 holds:
each sql plant took under 10 s, and the census's planted fork, with its control,
is the costliest at two JVM starts.
