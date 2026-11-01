# VARKA-509: answer checks below the session

*Written 28 October 2026, for row 509 of `m8/PLAN.md`. The profile behind the row is
`m8/results/VARKA-509-profile-local.txt`: of `VarkaCoverageDifferentialSuite`'s CPU samples,
Varka's compile and emit took 2.9%, Spark's analyzer and optimizer 26.4% and task serialization
18.2%.*

## 1. What the coverage suite checks, and where each check can live

The suite reads `sql/varka/coverage.json` and runs every row in four forms. Each needs a
different part of Spark; the harness keeps the part a check needs and drops the rest.

| check | what it needs | where it goes |
| :--- | :--- | :--- |
| a row's answer on each fixture, rows consumer | the optimized expression, the kernel, Spark's answer per row | harness (`projectFused`) |
| the same, columnar consumer (Arrow cache) | the same, through the cache builder's path | harness (`project`); one end-to-end test per consumer over a sample of rows |
| the partition oracle (`p`, `NOT p`, `p IS NULL`, with and without the three rewriting rules) | the predicate in each form, optimized and not | harness (`filterMask`); the rules run or not by the harness's own optimizer |
| the ANSI form (raise where Spark evaluates, not where it skips) | each form's expression, the extremes fixture, Spark's error | harness: a batch the kernel answers holds no row Spark raises on; a declined batch is the row path's, Spark's by construction |
| the domains (TLP partitions, decline status per batch) | the kernel's status word per batch | harness (the status the evaluator reads, not a JFR event) |
| that `VarkaColumnarRule` fuses the row, a SQL configuration reaches the emitter, the fallback's accounting, an error as the user sees it | a session | end to end, one test a path |

## 2. The harness

A trait in sql/core's tests, `VarkaExpressionDifferential`, with no `SparkSession`:

1. **The expression Spark would hand Varka.** Parse the row's SQL, resolve it with
   `SimpleAnalyzer` over the fixture's columns (`VarkaSqlResolve` does this today), then run the
   logical optimizer over the `Project` or `Filter`. The leaf under it is an opaque test node with
   the fixture's output, standing for the scan the end-to-end suite reads, not a `LocalRelation`:
   the optimizer folds a projection or a filter over a local relation into a local relation of
   results (`ConvertToLocalRelation`, `PropagateEmptyRelation`), and no expression would be left
   to compile. The optimizer is the one class that must match a session's: the catalyst
   optimizer's batches, plus the expression rules `SparkOptimizer` adds, named in the harness and
   checked (section 4).
2. **The fixture as a batch.** The fixture's `VALUES` rows, evaluated once by Catalyst into
   `InternalRow`s, written into Arrow vectors of the columns' types as the cache builder writes
   them; the same rows kept for Spark's side.
3. **Spark's answer.** Each output evaluated over the `InternalRow`s twice, by
   `InterpretedUnsafeProjection` and by `GenerateUnsafeProjection`; a row where the two disagree
   is reported as Spark's own disagreement and the row skipped, not blamed on the kernel. An
   exception is the answer for that row.
4. **Varka's answer.** The production evaluators on the batch: `VarkaKernelEvaluator.project`
   (columnar consumer) and `projectFused` (row consumer) for a projection,
   `VarkaFilterEvaluator.filterCompact` for a predicate, each through `serveBatch` as the exec
   nodes run them, under `TaskContext.setTaskContext(TaskContext.empty())`. The suite
   constructor's `VarkaSessionEmit.DEFAULTS` lays no session setting over the base options; the
   base itself is `VarkaColumnarToRowExec`'s test hook, which the harness sets to
   `VarkaMatrix.base`, so the option matrix reaches every harness test (`sql/varka/AGENTS.md`, a
   test starts from `VarkaMatrix.base`). A declined batch is the row path's, Spark's by
   construction; the status word, for the domains, is the one the kernel declined it with
   (section 6).
5. **The comparison.** Values compared as `VarkaSparkOracle` compares them (raw bits for the
   floating types); a batch the kernel declined must be one Spark's row path answers or raises
   on, as the row path would; a batch it answered must hold no row Spark raises on.

Each coverage row then runs in well under a second: one parse, one analysis, one optimization,
one Varka compile (the shape cache shared across rows), one Janino compile for Spark's generated
projection, and a few batches.

## 3. The end-to-end layer that stays

A test a path rather than a test a row, in the session suites that already hold the path, each
named in `m8/results/VARKA-509-step2e-local.txt`; `VarkaCoverageDifferentialSuite` keeps the
sample rows and the ANSI errors:

- each consumer (rows, Arrow cache, filter) over a fixed sample of coverage rows, one per family;
- `VarkaColumnarRule` fusing a row and declining one it must decline;
- a SQL configuration reaching the emitter (`spark.sql.codegen.varka.*` read per session);
- the fallback's accounting (metrics, the JFR event) for a declined batch;
- an ANSI error as the user sees it, through `checkError`, for one row of each error class.

## 4. What the harness must prove about itself

- **A planted kernel fails it.** With `checkIntOverflow=false` a checked int add wraps, so the
  kernel answers `Int.MaxValue + 1` where Spark raises `ARITHMETIC_OVERFLOW`; the harness must
  report that row (the fault the end-to-end suite's ANSI form plants). `misdescribeAdd` does not
  serve: it fails the kernel rather than answering wrong.
- **Its expression is the session's.** For every coverage row, once per build in a test that
  plans but does not execute (`df.queryExecution.optimizedPlan`), the expression the harness
  optimizes equals the one in the session's optimized plan. This test keeps a session but
  executes nothing, so it costs planning only, and it fails the day the two optimizers part. It
  compiles no kernel, so row 507's record names no configuration for it and the matrix runs it
  once, at the defaults.
- **Its batch is the cache's.** By construction: the harness writes and reads its batches with
  the cache serializer's own two iterators (`VarkaCacheBatches`), so there is no second writer to
  compare.

## 5. Steps

1. The harness and section 4's three checks, on the coverage table's answer form only.
2. The coverage suite moved onto it: the partition oracle, the ANSI form and the domains; the
   old suite cut to section 3's layer. A table maps every old test to the new one that keeps its
   check, and the matrix's skip lines (`sql/varka/matrix/skips.tsv`) move with their tests.
3. The same for `VarkaDifferentialSuite` and `VarkaTimeArithmeticSuite`, whose hand-written
   shapes become harness queries (`VarkaHarnessCases`), one test of the old suite a test under
   its name; the time suite first, as `VarkaTimeHarnessSuite`, then the differential suite, as
   `VarkaDifferentialHarnessSuite`.
4. Measured: each suite at the defaults on the laptop before and after, and the next option
   matrix and Build against `m8/results/VARKA-507-run-40658422887.txt`.
   Done in `m8/results/VARKA-509-step4.txt`, with the profile after the move in
   `m8/results/VARKA-510-planning-local.txt`.

## 6. Open questions

- **The optimizer's expression rules.** Settled in step 1: `SparkOptimizer` builds over an
  in-memory `SessionCatalog` with the builtin functions, and `VarkaHarnessParitySuite` finds
  all 1,011 of the coverage table's expressions on its three fixtures equal to a session's.
- **The status word.** Settled in step 2: the harness hands `serveBatch` the kernel path, so
  the decline the kernel throws passes through the harness's own callback first, which records
  its status beside the batch's rows. That needs no JFR recording and no seam in main code, and
  ties each status to its batch, where the end-to-end suite matched a sequence of JFR events to
  the batches in order and could not see the batches after a row path that raised.
- **ANSI mode and the session's conf.** The expression's behaviour under ANSI is read from
  `SQLConf.get`; the harness sets it per test through `withSQLConf`, as catalyst's own suites do.
- **The row consumer's residual pass.** `projectFused` leaves residual entries to the row node;
  a coverage row fuses whole (`VarkaCoverageSuite` asserts it), so no residual is expected, and
  the harness fails a row that has one rather than evaluating it.
