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

The plan takes (b). It keys on the measured class, not the listed one, so a run with the fuse
check lifted (`FUSE_CHECK_PROPERTY=false`, the run that measures a row) still checks every section
that does fuse whole and does not fail a listed `WHOLE` section that no longer fuses. The day-time
benchmark's own `requireKernel` and its call go; the three benchmarks that call `varkaFused` for
this purpose (BIGINT arithmetic, both TIMESTAMP_NTZ ones) drop those calls and
`Section.checksKernels`, since (b) covers their projections and their filters. `varkaFused` stays
for the size ladders, which read its count.

**The queries suites.** They plan each section themselves and assert
`VarkaSqlBenchmarkBase.fusion(plan)`; each gains, under its `withSettings`, a call of
`requireKernel` for a `WHOLE` section, replacing the day-time and BIGINT suites' calls. That is
all twelve suites; the math one lists no `WHOLE` section today and gets the call so that a future
one is checked there. A shared Scala helper,
`VarkaBaselineChecks.requireSection(varka, title, query, expected)`, doing the class assertion and
the kernel check, keeps the call one line per suite.

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
- The plan-only dry run of each baseline benchmark at 256 and 128 bits (the PR Build's dry runs),
  where `requireBaseline` now runs the check.

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
*BaselineQueriesSuite'` passes with the planted test among them, and the PR Build's dry runs pass.

## Questions for the owner

1. **Call the check from `requireBaseline` (b), or beside it in each benchmark (a)?**
   *Recommended: (b)*: one site, and a new baseline cannot forget it. Not mine: it makes
   `requireBaseline`, which today only plans, execute each `WHOLE` section once, a change of what
   the shared base does for every benchmark, including future non-baseline ones that reuse it.

## Done when

Every baseline benchmark's `WHOLE` sections pass `requireKernel` in its queries suite, and a
planted section whose batches fall back fails it.

## 9. Outcome

Not built yet.
