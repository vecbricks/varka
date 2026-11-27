# VARKA-549: Every baseline section that fuses whole is checked to run as a kernel

## 1. Where this came from

Milestone 8's row 549, added 2 November 2026 from the review of the day-time baseline on branch
`varka-103-baseline` and planned as a row in #1295. A baseline benchmark lists each section's
fusion class on the unchanged code, and `requireBaseline` in `VarkaSqlBenchmarkBase` fails the run
when the plan fuses more or less than listed. That check reads the plan only. A `WHOLE` section's
"varka on" arm is meant to time a kernel, but a Varka node can hand a batch to another path at run
time: a batch that is not Arrow, an empty one, a kernel that declines or fails. The run-time check
that existed, `VarkaSizeLadder.varkaFused`, finds a projection node only, so it throws on a filter's
plan and the filter baselines do not call it. The day-time baseline wrote its own check,
`requireKernel`, which reads every Varka node's kernel and fallback counters; the row moves that
check into `VarkaSqlBenchmarkBase` and runs it for every `WHOLE` section of every baseline, so the
decimal baseline's filter sections (#1258), against which the 128-bit decimal pair's numbers are
read, are checked as well.

## 2. The admission check, done

Read on `origin/master` at `e5d70012bdc`, 20 November 2026.

- **Not built.** `git log --grep=VARKA-549` finds no commit and no pull request has the id in its
  title; `requireKernel` exists once, as a package-private static of
  `VarkaDayTimeIntervalBaselineBenchmark` (`:226`), called by that benchmark and by its queries
  suite only. `VarkaSqlBenchmarkBase` has no run-time check.
- **Which baselines check what, today.** Every one of the twelve `Varka*BaselineBenchmark` classes
  calls `requireBaseline`. The run-time checks beside it:

| baseline | `WHOLE` sections | run-time check in the benchmark | in its queries suite |
|---|---|---|---|
| day-time interval | projections and filters | `requireKernel`, every `WHOLE` | `requireKernel` |
| BIGINT arithmetic | projections and filters | `varkaFused`, projections only | the same |
| TIMESTAMP_NTZ arithmetic | projections | `varkaFused` | none |
| TIMESTAMP_NTZ fields | projections | `varkaFused` | none |
| decimal | projections and filters (`fusedFilter`) | none | none |
| floating | projections and filters | none | none |
| TIMESTAMP_NTZ | projections and filters | none | none |
| floating cast, integral cast | projections and filters | none | none |
| floating arithmetic, rounding | projections | none | none |
| math | none listed `WHOLE` | none | none |

  So the row's problem holds and is wider than its text: seven baselines time `WHOLE` sections
  with no run-time check at all, five of them with filters, and the queries suites of ten do not
  check either. (Read from each file's `Section` list; the classes some sections take from the
  two-lane refusal are `WHOLE` at one setting.)
- **A fault to plant exists.** `VarkaColumnarToRowExec.setDeclineKernelForTesting(true)` makes
  every kernel invocation decline its batch (read by `VarkaKernelRunner` for every Varka node), so
  a section's batches go to the row path and the fallback counter moves, without touching any
  expression's range.

## 3. The design

### 3.1 One check in the base class

`VarkaSqlBenchmarkBase.requireKernel(SparkSession varka, String title, String query)`, public and
static, the day-time body moved: run the query once (`toRdd().count()`), take the plan's operators
through `operators` (subqueries and reused exchanges included), keep those with
`numVarkaBatches`, and fail unless there is at least one, they ran at least one kernel batch and
every counter of `VarkaSizeLadder.FALLBACK_METRICS` is zero. The message names the section, the
node count, the batches, the fallbacks and the query, as now.

**Where it is called.** Two alternatives:

- (a) Each benchmark calls it beside `requireBaseline`, as the day-time one does. Twelve call sites,
  and a new baseline can forget it.
- (b) `requireBaseline` calls it itself whenever the plan's measured class is `WHOLE`. One call
  site, every baseline covered by the call it already makes, a new baseline covered without
  writing anything.

The plan takes (b), keyed on the measured class: the kernel check runs for every section that
plans `WHOLE`, and the listed class is the fuse check's alone. With the fuse check on, the two
agree or the run has already failed. With it lifted (`FUSE_CHECK_PROPERTY=false`, the run that
measures a row's speedups, and so the run where a fallback would most mislead), the kernel check
still runs on every section that plans `WHOLE`, a newly fusing one included, but a section that
falls back is noted in the results file with its node, batch and fallback counts ("the varka on
arm times the row path on N of M batches") rather than failing the run, so the measuring run
completes and its reader sees which arms timed what.

**Sections that time a fallback on purpose are exempt.** The rounding baseline's `floor` and
`ceil` sections over `xw` and `xwf` exist "so that the arm's fallback is measured" before 344b
picks its default (`VarkaRoundingBaselineBenchmark` class doc): once 344b lists them `WHOLE`, their
batches decline by design. The fact is per section, so the rounding baseline's `Section` gains a
component, `boolean timesFallback`, true for the sections its four `floor` and `ceil` forms over
`xw` and `xwf` build (`:287` to `:294`); its check loop, which today
passes one `expected` for every section, passes `s.timesFallback()` to an overload
`requireBaseline(varka, title, query, expected, timesFallback)`, which skips the kernel check and
notes in the results file that the arm times the fallback. Every other baseline keeps the
four-argument form and is checked. `requireSection` (below) takes the same flag, default false,
and the rounding suite passes `s.timesFallback()`, so the suite exempts exactly the sections the
benchmark does.

The day-time
benchmark's own `requireKernel` and its call go; the three benchmarks that call `varkaFused` for
this purpose (BIGINT arithmetic, both TIMESTAMP_NTZ ones) drop those calls and
`Section.checksKernels`, since (b) covers their projections and their filters. `varkaFused` stays
for the size ladders, which read its count.

**The queries suites.** They plan each section themselves and assert
`VarkaSqlBenchmarkBase.fusion(plan)`; each gains, where that assertion is, a call of
`requireKernel` for a `WHOLE` section, replacing the day-time and BIGINT suites' calls. Six
suites wrap the assertion in a `withSettings` (BIGINT arithmetic, day-time interval, floating
arithmetic, floating cast, integral cast, rounding) and the call goes inside it. The other six
have none: decimal, floating, math, TIMESTAMP_NTZ and TIMESTAMP_NTZ arithmetic assert in their
"fuses as its class lists it" test, and TIMESTAMP_NTZ fields in "fuses only when it is listed",
each under the session settings that test already sets, and the call replaces the bare
assertion there. That is all twelve suites; the math one lists no `WHOLE` section today and gets
the call so that a future one is checked there. A shared Scala helper,
`VarkaBaselineChecks.requireSection(varka, title, query, expected, timesFallback = false)`,
doing the class assertion and the kernel check, keeps the call one line per suite.

### 3.2 What is deliberately unchanged

- The `PARTIAL` and `NULL_TESTS` classes: their arm times a kernel beside the row path by
  definition, and `requireBaseline` notes them as such.
- `VarkaBooleanOutputBenchmark` and `VarkaTwoLaneLongBenchmark`, which have sections but are not
  baselines; they can call the new static later.
- The benchmarks' timed bodies: the check runs before the timing loop, once per section.

## 4. Files

| file | what |
|---|---|
| `VarkaSqlBenchmarkBase.java` | `requireKernel`, called by `requireBaseline` on `WHOLE` |
| `VarkaDayTimeIntervalBaselineBenchmark.java` | its own `requireKernel` removed |
| `VarkaBigintArithmeticBaselineBenchmark.java` | `varkaFused` call, `checksKernels` removed |
| `VarkaTimestampNtzArithmeticBaselineBenchmark.java` | the `varkaFused` call removed |
| `VarkaTimestampNtzFieldsBaselineBenchmark.java` | the `varkaFused` call removed |
| `VarkaRoundingBaselineBenchmark.java` | `Section.timesFallback`, the five-argument call |
| `VarkaBaselineChecks.scala` (new) | `requireSection` for the queries suites |
| the twelve `Varka*BaselineQueriesSuite.scala` | `requireSection`; the planted test (decimal) |

## 5. Tests, and what each is for

- Each baseline's queries suite, the existing class test now with the kernel check: every `WHOLE`
  section of every baseline runs as a kernel over the suite's table, at both refusal settings
  where the suite already runs both. This is the done-when's first half, and it catches a section
  whose batches fall back on the suite's table (a non-Arrow cache, a guard's range).
- `VarkaDecimalBaselineQueriesSuite`, "a WHOLE section whose batches fall back fails the kernel
  check": one `WHOLE` filter section and one `WHOLE` projection section of the decimal baseline
  under `setDeclineKernelForTesting(true)`, reset in a finally block, each failing
  `requireSection` with the fallback count in the message; and the same sections passing without
  the hook. The done-when's second half; it also proves the check sees a filter node, the gap the
  row exists for.
- The dry run of each baseline benchmark at 256 and 128 bits, where `requireBaseline` now runs the
  check. No CI job runs these (`build_and_test.yml` runs no baseline benchmark); the builder runs
  them locally with `-Dvarka.bench.dryRun=true` (the flag `dev/varka_bench_ids.sh` sets), under
  `-XX:MaxVectorSize=32` and `16`, one JVM at a time, and lists the results in the PR body.

No pinned file moves.

## 6. The measurement

No performance measurement. The cost is test and dry-run time.

### 6.1 Predictions, registered before the run

1. Every `WHOLE` section of every baseline passes the check on master at both widths; none falls
   back. If one does, its arm has been timing the row path, the row's finding: the section's
   class or its table is fixed in the same pull request, and its committed results are named
   for a rerun.
2. The kernel check adds under 30 s to the twelve queries suites together: about 120 `WHOLE`
   section runs over small cached tables at VARKA-379's observed 0.08 s a query is about 10 s, at
   a ratio of 2 to 3 for runs that execute rather than plan.
3. Each benchmark's dry run grows by one execution of each `WHOLE` section over its table, under
   10% of its plan-only time.

## 7. Risks

1. **A queries suite's table is not Arrow-cached the way the benchmark's is**, so a section
   passes in the benchmark and fails in the suite with non-Arrow fallbacks. The day-time suite
   already passes `requireKernel` over `catalog.cacheTable` in the shared sessions, so the shared
   sessions' cache is Arrow; a suite that builds its table otherwise is fixed to cache the same way.
2. **A section with no rows at a density** runs no kernel batch and fails the "at least one batch"
   rule; that is the check working (an empty table times nothing), and the fix is the table.
3. **Adaptive execution** would hide nodes in stages; `operators` already reads through
   `AdaptiveSparkPlanExec` and query stages, and the benchmarks' sessions run with it off.

## 8. Sequencing

One pull request, about 250 counted lines: the base-class check and the removals in one commit,
the queries suites and the planted test in a second. Done when `build/sbt 'sql/testOnly
*BaselineQueriesSuite'` passes with the planted test among them, and the twelve local dry runs
of 5 pass.

## Questions for the owner

1. **Call the check from `requireBaseline` (b), or beside it in each benchmark (a)?**
   *Recommended: (b)*: one site, and a new baseline cannot forget it. Not mine: it makes
   `requireBaseline`, which today only plans, execute each `WHOLE` section once, a change of what
   the shared base does for every benchmark, including future non-baseline ones that reuse it.

## Done when

Every baseline benchmark's `WHOLE` sections pass `requireKernel` in its queries suite, and a
planted section whose batches fall back fails it.

## Decisions taken while the owner was away (20 November 2026)

The owner was away during the build (an implementation window that ended at 14:00 UTC), so the
questions above were decided by the plan's own recommendations and the house rules, each recorded
here with its reason.

1. **(b): `requireBaseline` makes the kernel check itself** on every section that plans `WHOLE`,
   as the plan recommends: one site, and a baseline written later is checked without writing
   anything. The cost the question names, that the shared check now executes a `WHOLE` section
   once, is the dry run's prediction 3.
2. **`requireSection` is Java, a static of `VarkaSqlBenchmarkBase`, not a new Scala object**
   `VarkaBaselineChecks`. The plan names a Scala helper, but `sql/varka/AGENTS.md` says test
   helpers are Java and new Varka code prefers Java; the base class already holds `fusion` and
   `requireKernel`, which the helper composes, so it sits beside them as two overloads (the
   five-argument one taking `timesFallback`) in place of a Scala default argument. One file fewer.
3. **The rounding queries suite gains its fuse check as a new test.** Section 3.1 lists it among
   the six suites that wrap the class assertion in a `withSettings`, but the suite had no fuse
   check at all: its class doc said it checked answers only. The plan was wrong there. The new
   test, "each section of the rounding baseline fuses as its listing says", is the other suites'
   test over the rounding listing (`fuses` to `WHOLE` or `NONE`, as the benchmark maps it), at
   both two-lane refusals, passing `timesFallback`; the table setup moved into a `withTable` the
   two tests share.
4. **The kernel check's failure names every Varka node** with its own kernel batches and
   fallbacks, not only the sums, so that a failure on the suite's single run says which node of a
   filter section handed batches back.
5. **With the fuse check lifted, a `WHOLE` section that falls back is noted, not failed**, as 3.1
   says. For the two `TIMESTAMP_NTZ` baselines this changes their documented behaviour: their
   `varkaFused` call failed such a section with the property set to `false` too. Their class
   docs now say what the shared check does; the PR body says so.
6. **The decimal baseline's two `IS NULL` filter sections over the columns with no null are
   kept and run with the in-memory scan's batch pruning off** (the owner's decision of 23
   November 2026): `VARKA-359.md`'s measurement of the lane pair names them, so they stay, and
   both arms now read every row instead of an empty scan. Their committed results time the empty
   scan, so the decimal baseline's runner results at both widths are owed a rerun, a benchmark
   dispatch the owner starts.

## 9. Outcome

### 9.1 What was built (20 November 2026, branch `varka-549-baseline-kernel-check`)

- `VarkaSqlBenchmarkBase`: `requireKernel(varka, title, query)`, public and static, the day-time
  body moved and its message widened to every node's counts; `requireBaseline` calls it on a
  section that plans `WHOLE`, failing with the fuse check on and noting the counts with it
  lifted; a five-argument `requireBaseline` and `requireSection` taking `timesFallback`; and
  `requireSection(varka, title, query, expected)` for the queries suites.
- The day-time baseline's own `requireKernel`, the BIGINT baseline's `checksKernels` and the
  `varkaFused` calls of the BIGINT and both `TIMESTAMP_NTZ` baselines are gone; their class docs
  point at the shared check.
- The rounding baseline's `Section` has `timesFallback`, true for its eight `floor` and `ceil`
  sections over `xw`, `xwf` and their null forms, passed to the five-argument `requireBaseline`.
- All twelve queries suites call `requireSection` where they asserted the class (the rounding
  suite in its new test); the decimal suite has the planted test, "a WHOLE section whose batches
  fall back fails the kernel check".

### 9.2 What the one targeted run found, and prediction 1 scored

One run, `sql/testOnly *BaselineQueriesSuite` filtered to the twelve fuse-check tests and the
planted test (13 tests, 39 s of tests): twelve passed, the planted test among them, and the
decimal suite's fuse check failed on its first `WHOLE` section of the kind, `DECIMAL(38, 10) IS
NULL filter, no nulls`: its one Varka node, a `VarkaFilterColumnarToRow`, ran 0 kernel batches and
handed 0 to another path. No batch reached it: the in-memory scan prunes a cached batch by its
statistics, and an `IS NULL` filter over a column whose batches hold no null keeps none. The
committed runner results show it, both arms of the section and of its `DECIMAL(20, 2)` twin at
the same time and the same 178 M rows a second, an empty scan, where the same filter with one row
in ten null runs at 14.7x. So these two sections have timed nothing since they were added; the
kernel check's "at least one kernel batch" rule caught them, as risk 2 foresaw.

**Prediction 1 is wrong**, then, though not in the way it guarded against: no section fell back,
but two `WHOLE` sections timed an empty scan. Fixed in the table of sections, as the prediction's
rule says. The two sections first were dropped; on the owner's word they are kept and run with
the scan's batch pruning off (decision 6), and the failure message now gives each node's input
batches too, so a node no batch reached reads apart from one whose batches went elsewhere. Owed,
a benchmark dispatch: `VarkaDecimalBaselineBenchmark` at 256 and 128 bits on the EPYC 7763, whose
committed `VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-results.txt` and its `-128bit`
companion time the two sections as an empty scan. The other eleven suites' `WHOLE` sections
all passed on the run (the decimal suite's later sections were re-run after the fix; 9.3).

### 9.3 What is left

- The twelve baseline dry runs at 256 and 128 bits of section 5 (24 runs), with prediction 3.
- The full `*BaselineQueriesSuite` run of section 8 on CI, with prediction 2.

### 9.4 A second baseline that times a fallback (25 November 2026)

After the merge with master's #1700 (VARKA-350), CI's fuse check (Build 45605120696) failed on
`DECIMAL(18, 4) CAST(x AS DECIMAL(18, 2)), no nulls`: `WHOLE`, but 0 kernel batches and 2 handed
to the row engine. Its rounding division at precision 18 and `k = 2` guards the magnitude below
`2^54` and declines the batch past it, and `x18` reaches `5 * 10^16`, past `2^54` on 64% of its
rows, so every batch declines; `VARKA-350.md` prediction 3 expects this. So the decimal
baseline's `Section` gains `timesFallback` too, true for that section at both densities, and its
runner and suite pass it, as the rounding baseline does: section 3.1's "every other baseline
keeps the four-argument form" no longer holds for the decimal baseline. The column's values are
unchanged, since the committed results time them. The other guarded fused cast, `CAST(x AS
BIGINT)` at `DECIMAL(18, 4)` (`k = 4`, bound `2^56`), stays below its bound on every row.

The flag is checked rather than trusted: the base class's `BatchCheck.ALL_FALL_BACK`, which the
decimal section takes, runs the section once and fails unless no batch ran as a kernel and at
least one was handed back (`requireFallback`), so a section that stops falling back drops its
flag and has its results regenerated. The rounding sections keep `MAY_FALL_BACK`, no check:
whether their batches decline depends on which of 344b's arms the host or an option picks, and
they plan `NONE` today. Owed: the committed decimal results, 256- and 128-bit, predate #1700 and
note no fused cast section, so their notes need a regeneration after the merge with #1700.

### 9.5 The rerun (27 November 2026)

The dispatch that 9.2 and 9.4 owed ran. The class's two runner files,
`VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-results.txt` and
`VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-128bit-results.txt`, were replaced on 27
November 2026 from `benchmark.yml` runs 46057445085 (256 bits, no `MaxVectorSize`) and 46057448792
(`-XX:MaxVectorSize=16`) of vecbricks/varka. Both ran at master `e103a4c548a`, dispatched with
`pr=1793`, so they measured `refs/pull/1793/head`, `6d1f14ae33a`, each on an AMD EPYC 7763 by its
provenance and by the job's probe. That head holds #1683 and #1700.

- The two `IS NULL` sections over a column with no null now time a scan on both arms. At 256 bits
  the `DECIMAL(38, 10)` one takes 354 ms on the row path and 21 ms as a kernel, 16.5X as the file
  prints it, and the `DECIMAL(20, 2)` one 371 and 24 ms, 15.4X. The replaced file read the two arms
  of each at one rate, 178.2 and 178.0 M rows a second and 196.6 and 197.2, the empty scan. At 128
  bits the two-lane refusal leaves both arms on the row path, 361 / 366 ms and 375 / 371 ms.
- The notes follow #1700. The 256-bit file opens with 88 notes: 86 sections that time a kernel, the
  eight fused casts among them, and the two `DECIMAL(18, 4) CAST(x AS DECIMAL(18, 2))` sections,
  noted as timing the fallback ("ran 0 kernel batches and handed 200 to another path"), 142 / 144
  ms and 140 / 145 ms. The 128-bit file opens with 16 notes.

Nothing of the row is owed after this.
