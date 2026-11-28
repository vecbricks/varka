# Map of Varka

A lookup table: where things live, the path of a query, what each large class owns, and where a
decline reason comes from. Checked against the tree on 28 November 2026; the commands behind
the counts are given, so rerun them when in doubt. Path prefixes used below:

    CG/  = sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/
    CGT/ = sql/catalyst/src/test/{java,scala}/org/apache/spark/sql/catalyst/expressions/codegen/
    EX/  = sql/core/src/main/{java,scala}/org/apache/spark/sql/execution/
    EXT/ = sql/core/src/test/{java,scala}/org/apache/spark/sql/execution/
    EN/  = sql/varka/engine/src/main/java/org/apache/spark/sql/varka/

## 1. Where things live

### Main sources

- `CG/Varka*.java` (24 files), `DeclineSink`, `CompiledVarka*`, `PartialVarkaProjection`: the
  compiler from Catalyst expressions to the IR; section 3.
- `CG/varka/` (82 files): the IR, the emitter, the lowerings, the options, the shape cache.
- `EX/Varka*.scala` (6 files): the plan rule and the exec nodes (Scala, Spark's surface).
- `EX/Varka*.java` (16 files): the per-task evaluators and what they are built from.
- `EX/varka/` (9 files): the kernel runner, the fallback accounting, the batch ledger.
- `EN/` (9 files): the engine module, `sql/varka/engine/` (own `pom.xml`, no parent pom):
  `vector/VarkaVectorSupport` (helpers a kernel calls) and the hand-written reference kernels.
- `sql/catalyst/src/main/scala/org/apache/spark/sql/internal/SQLConf.scala`: the
  `spark.sql.codegen.varka.*` configurations.

### Tests, by kind

- `CGT/varka/` (149 Java, 79 Scala files): the emitter and IR suites: IR built by hand, emitted
  and compared with `VarkaReferenceEvaluator`. Among them `VarkaIrFuzzSuite` (random IR),
  `VarkaCoverageSuite` (every row of `coverage.json`), `VarkaEmittedBytesSuite` and
  `VarkaWidthAuditSuite` (pins), and the option matrix's test side (`VarkaMatrix`,
  `VarkaMatrixRuns`, `VarkaTestReach`; its data is in `sql/varka/matrix/`).
- `CGT/Varka*Suite.*` (9 files): the compiler suites (Catalyst expression in, IR or decline out).
- `EXT/Varka*.scala` (62 files): end-to-end suites over a Spark session, Varka on against off;
  `VarkaSparkFuzzSuite` is random SQL, with reproducers in `sql/varka/fuzz/spark/`.
- `EXT/Varka*.java` (23 files): the expression harness (`VarkaExpressionHarness`,
  `VarkaSuiteCheck`) that reruns Spark's expression suites under Varka.
- `sql/core/src/test/scala/org/apache/spark/sql/VarkaSQLQueryTestSuite.scala`: Spark's SQL golden
  files under Varka; its lists are in `sql/varka/spark_suites/`.
- `sql/varka/engine/src/test/`, `sql/varka/bench/src/test/`: JUnit tests of those two modules.
- Which base class, which tag, how to run one: `sql/varka/skills/testing-and-debugging.md`.

### Generated files and what regenerates each

Never edit these by hand. `dev/varka_regen_generated.sh --all` runs the first group in the
right order (its header says why); `python3 dev/varka_generated_merge.py --list` prints it.

- `sql/varka/coverage.json`, the generated blocks of `docs/sql-varka.md`: `VarkaCoverageSuite`.
- `sql/varka/emitted_bytes.json`: `VarkaEmittedBytesSuite`.
- `sql/varka/width_audit.json`: `VarkaWidthAuditSuite`, only on the host the file names.
- `sql/varka/types.tsv`: `VarkaTypesSuite`. `sql/varka/fuzz/types.tsv`: `VarkaFuzzTypesSuite`.
- `sql/varka/plans/m8/casts_fused.tsv`: `VarkaCastMatrixSuite`. `casts_suites.tsv`: sql/core's
  `VarkaCastWithAnsiOffSuite`, `VarkaCastWithAnsiOnSuite` and `VarkaTryCastSuite`.
- `sql/varka/plans/m8/census.tsv` and the docs' support table: `python3 dev/varka_census.py`.

Outside that script (each file's first lines name its own command):

- `sql/varka/operator_census.json`: `VarkaOperatorCensusSuite`, on the host the file names.
- `sql/varka/emit_cost_audit.json`: `VarkaEmitCostAuditSuite`. `golden_corpus.json`:
  `VarkaGoldenCorpusSuite` (sql/core). `spark_suites/served.json`: `dev/varka_golden_suite.sh`.
- `sql/varka/coverage/*.md`: `dev/varka_gen_coverage.sh`. `mutation/*.md`: `dev/varka_pit.sh`.
- `sql/varka/proofs/*.smt2`: `CGT/varka/VarkaProofFiles.java` (`sql/varka/proofs/README.md`).
- `SKILLS.md` (repository root): `dev/varka_toc.py SKILLS.md --from sql/varka/skills`.
- `sql/varka/ci/*.tsv`, `matrix/*seconds.tsv`, `matrix/ledger.tsv`: see each file's header.

### Directories under `sql/varka/`

- `baselines/`: the scalar Java baseline of the calendar speedups, with its results.
- `bench/`: the benchmarks against stock Spark, a module of its own; results in `benchmarks/`.
- `census/`: TPC-H and TPC-DS codegen census output (`dev/varka_tpc_census.sh`).
- `ci/`: step and suite time budgets, the sweeps table, the tier-split data.
- `coverage/`: JaCoCo coverage of the compiler and of the emitted classes (generated).
- `demo/`: scripts and their captured output behind public claims (method-size cliff, compiles).
- `engine/`: the engine module (see "Main sources"); JMH results in `engine/benchmarks/`.
- `fuzz/`: the fuzzers' data: expected errors, known failures, types, SQL reproducers.
- `matrix/`: the option matrix's skip list (`skips.tsv`, hand-written) and timing tables.
- `mutation/`: the mutation run's verdicts and the answers to surviving mutants.
- `papers/`: transcriptions of third-party papers; not Apache-licensed (`papers/README.md`).
- `plans/`: see "Plans".
- `proofs/`: SMT-LIB proofs of the bounded lowerings; `dev/varka_prove.sh` runs them.
- `skills/`: the lessons, one file a topic (8 files); `calibration/` the scored predictions.
- `spark_suites/`: the lists for Spark's own suites run under Varka (excluded, declines, served).
- `spikes/`: kept experiments, each with a `README.md` and its results.
- `traffic/`: GitHub traffic snapshots (`traffic/README.md`).

Files directly in `sql/varka/`: `AGENTS.md` (the rules; `CLAUDE.md` is a symlink to it),
`ADDING_AN_EXPRESSION.md`, `ADDING_A_TYPE.md` (the two recipes), `WALKTHROUGH.md` (one
expression end to end), `VISION.md` (the principles; its scope sections are historical),
`HARDWARE.md` (the machines the numbers come from), `ISSUES.md` and `Varka_MVP.md` (closed,
historical), and the generated records listed above.

### Plans

- `sql/varka/plans/m<n>/`: one folder a milestone, `m1` to `m10`. `PLAN.md` is the task table
  (`m10` has `SCOPE.md`, not yet one). A row: `grep -n '^| <n> |' sql/varka/plans/m*/PLAN.md`.
- `plans/m<n>/VARKA-<id>.md`: one task's plan and outcome. `READING_*.md`: reading notes.
- `plans/m7/results/`, `plans/m8/results/`: committed measurements and reports of those rows.
- `plans/TEMPLATE_TASK.md`, `PLAN_TASK_TEMPLATE.md`: the task plan templates.
- `plans/figures/`: the scripts and data of the posts' figures.

### Benchmarks and their results

- Kernel benchmarks: `sql/catalyst/src/test/java/org/apache/spark/sql/Varka*Benchmark.java`
  on `VarkaBenchmarkBase`; results `sql/catalyst/benchmarks/Varka*-jdk25-*.txt`.
- Query benchmarks: `sql/core/src/test/{java,scala}/org/apache/spark/sql/execution/benchmark/`;
  results `sql/core/benchmarks/Varka*-jdk25-*.txt`.
- Against stock Spark: `sql/varka/bench/`. JMH on the engine: `sql/varka/engine/src/jmh/`.
- Result files: `-results.txt` the wide run, `-128bit-results.txt` the narrow run,
  `-provenance.txt` the run's commit, JDK and CPU, `-band.txt` the per-case noise band,
  `-runner-<cpu>-` a run on a CI runner. Rules: `sql/varka/skills/benchmarking.md`.

### Tools, CI, public documents

- Tools: `dev/varka_*` (106 entries, `ls dev | grep -c '^varka'`), `dev/varka_ops/` (gate, push,
  watch a pull request), `dev/varka_canary/` (machine probes). There is no single index yet.
  Today's lists: `dev/varka_ops/INDEX.md` for `dev/varka_ops/`; the tables under "Development
  and benchmarking tools" in `docs/sql-varka.md`; and the tools named in `AGENTS.md` here.
- CI: `.github/workflows/varka-*.yml` (16 files) and Spark's `build_and_test.yml`.
- Public documents: `README.md`, `CONTRIBUTING.md`, `CHANGELOG.md` at the root;
  `docs/sql-varka.md` is the architecture guide (glossary, configuration, troubleshooting).

## 2. The path of a query, from Spark's physical plan to a running kernel

`WALKTHROUGH.md` shows this path for `hour(t)` with each tool's output, and the README's
"Reading the source" tells it as prose. For which class does which step, use this list.

At planning, on the driver:

1. `EX/VarkaColumnarRule.scala`: finds a projection or filter above a columnar source and asks
   the compiler whether any entry or conjunct fuses; if so it puts a Varka node in the plan.
2. `CG/VarkaExpressionCompiler`: the entry point and classifier. `compilePartial` marks each
   projection entry fused, forwarded or residual; `compilePredicate` does the same per conjunct.
3. `CG/VarkaNodeCompiler`: compiles one expression tree; `compileNode` asks each family of its
   `FAMILIES` list in order and runs the one that claims the node.
4. `CG/Varka*Compiler` (the families, section 3): each one's `arm` is a `switch` over Catalyst
   expression classes that returns IR nodes or declines through the `DeclineSink` (section 4).
5. `CG/varka/VarkaVectorIR`: the result, a tree of records; `VarkaKernelPlan` decides its lanes
   or refuses it, `VarkaSizeAdmission` asks the emitter whether it fits in bytes.
6. `CG/PartialVarkaProjection`, `CompiledVarkaProjection`, `CompiledVarkaPredicate`: the compiled
   plan the node keeps: IR roots, input ordinals, literal values, and the declines.
7. `EX/VarkaProjectExec.scala`, `VarkaFilterExec.scala`, `VarkaColumnarToRowExec.scala`: the
   nodes; `VarkaSessionEmit` fixes the emit options from the session's configuration.

In a task, once per partition:

8. `EX/VarkaKernelEvaluator` (projection) or `VarkaFilterEvaluator` (filter), both on
   `VarkaEvaluatorBase`: compile again with the same call and ask the shape cache for the class.
9. `CG/varka/VarkaShapeCache` and `VarkaShapeCacheImpl`: one loaded class a `VarkaShapeKey` (IR
   shape plus options); on a miss they call the emitter.
10. `CG/varka/VarkaLoopEmitter.emit`: runs `VarkaKernelPlan` and `Analysis`, groups the outputs
    into methods under `VarkaEmitBudget`, and builds the class file.
11. `CG/varka/VarkaBodyEmitter`: emits each method of the class (drivers, loop methods,
    epilogues) against the frame `Slots` plans.
12. `CG/varka/VarkaVectorWalk`: emits each IR node inside a method; hands the calendar nodes to
    `VarkaChronoLowering`, constant divisions to `VarkaDivisionLowering`, and so on.
13. `CG/VarkaGeneratedClassLoader`: defines the class; it implements `VarkaFusedKernel`.
14. `CG/varka/VarkaKernelWarmup`, `EX/varka/VarkaWarmupGate`: run the new kernel until C2 has
    compiled it; until then the shape's batches take the row path.

For each batch:

15. `EX/VarkaEvaluatorBase.serveBatch` and `EX/varka/VarkaKernelRunner`: put the Arrow buffers'
    addresses in the argument arrays and call `VarkaFusedKernel.run`.
16. The emitted `run`: picks the dense or the masked driver, which calls the loop methods and
    the epilogues; they call `EN/vector/VarkaVectorSupport` for validity words.
17. `EX/varka/VarkaFallbackAccounting`: a non-zero status or a failure is counted under its
    reason, and that batch is answered by Spark's row code (the ghost fallback).

## 3. What each class over 500 lines owns

The list, largest first (35 files on 28 November 2026):

    find sql/catalyst/src/main sql/core/src/main sql/varka/engine/src/main -type f \
      \( -name 'Varka*' -o -path '*/varka/*' \) | xargs wc -l \
      | awk '$1 > 500 && $2 != "total"' | sort -rn

### The three classes that emit (`CG/varka/`)

`VarkaLoopEmitter` builds the class, `VarkaBodyEmitter` builds a method, `VarkaVectorWalk`
builds a node. To change what one IR node emits, edit `VarkaVectorWalk` (or the lowering it
calls); to change a method's loop, loads, stores or validity write, edit `VarkaBodyEmitter`; to
change how outputs are grouped into methods, the `run` dispatch or the class file itself, edit
`VarkaLoopEmitter`. Its class comment is also the design note of the emitted class.

- `VarkaLoopEmitter` 2,040: entry `emit`; grouping under budgets, `run` dispatch, the
  class file, `fitsBudgets`. Emits no node.
- `VarkaBodyEmitter` 2,258: `emitDriver`, `emitGroupBody` (loop method or epilogue),
  `emitStage`; the lane-group step: loads, the walk, validity write, stores, status return.
- `VarkaVectorWalk` 2,789: `emitValue` (the dispatch over value nodes) and `emitCond`
  (conditions); a shared node is emitted once and reloaded; `loadWord`/`storeWord`.

### The rest of the emitter side (`CG/varka/`)

- `VarkaVectorIR` 3,712: the IR, a sealed interface of records, one per node; `LaneType`.
- `Analysis` 2,008: known before emitting: validation, node order, validity words, guards.
- `VarkaChronoLowering` 1,918: the bytecode of every calendar node.
- `Slots` 1,874: the local-variable layout of one emitted method and the planner that assigns it.
- `VarkaEmitOptions` 1,720: the record of emit options, `DEFAULTS`, the builder, `canonical()`.
- `VarkaEmitOption` 695: the table of those options (`TABLE`), read by fuzzer, matrix and suites.
- `VarkaVectorTemplateWarmup` 1,144: runs the Vector API templates once before any kernel warms.
- `VarkaKernelPlan` 1,082: a kernel's lanes, decided once from its roots, or the refusal text.
- `VarkaKernelWarmup` 979: the daemon thread that runs a new kernel until C2 has compiled it.
- `VarkaMathPort` 797: fdlibm functions written as IR nodes (the `PORT` form of a math call).
- `VarkaChrono` 759: the calendar constants and their scalar model, the emitter's one source.
- `VarkaEmitBudget` 719: the limits of an emitted method and the weight of each node.
- `VarkaTypes` 709: the table of Spark types: lane, Arrow storage, names (`types.tsv`'s source).
- `VarkaRangeAnalysis` 630: compile-time value ranges over the IR (which checks can come off).
- `VarkaShapeCacheImpl` 609: the bounded LRU of loaded kernel classes, keyed by `VarkaShapeKey`.
- `VarkaReproduction` 603: the reproduction file a kernel failure leaves, and its parser.
- `VarkaDivisionLowering` 540: the bytecode of division by a constant, and which form is taken.
- `VarkaSameAnswer` 512: the one definition of "the same answer" every comparator uses.

### The compiler side (`CG/`)

- `VarkaExpressionCompiler` 1,418: entry points (`compilePartial`, `compilePredicate`,
  `declines`), the per-entry classifier, budgets and admission. It holds no family's `switch`.
- `VarkaNodeCompiler` 1,668: `compileNode`, the `FAMILIES` chain, shared operand helpers, and
  two arms of its own: date leaves and int arithmetic.
- `VarkaConditionCompiler` 1,814: comparisons, `IN`, null tests, AND/OR/NOT; `IF`, `CASE`.
- `VarkaDecimalCompiler` 1,630: decimal values on the long lane: `value`, rescale, arithmetic.
- `VarkaChronoCompiler` 1,208: date arithmetic, field extraction, `trunc`, `next_day`, months.
- `VarkaTimeCompiler` 1,045: `bigint`, `TIME`, day-time leaves; `TIME` functions; NTZ arithmetic.
- `VarkaIntervalCompiler` 973: year-month and day-time interval arithmetic and casts.
- `VarkaConjunctOrder` 746: which fused conjuncts of a split filter would change Spark's errors.
- Under 500 lines, same form: `VarkaDecimalCastCompiler`, `VarkaRoundingCompiler`,
  `VarkaIntegralCastCompiler`, `VarkaFloatingCompiler`, `VarkaMathCompiler`,
  `VarkaHashCompiler`, `VarkaFloatingCastCompiler`.

Which file holds one expression's arm: take the row's `catalyst` class from
`sql/varka/coverage.json` and run `grep -ln '<Class>' CG/Varka*Compiler.java` with `CG/` spelled
out; the row's `switches` names the family. "Is this expression fused" is the lookup in
`coverage.json`, not a read of these files.

### Spark side and engine

- `EX/VarkaEvaluatorBase` 1,066: what evaluators share: cached kernel, `serveBatch`, fallback.
- `EX/VarkaFilterExec.scala` 672: the two filter nodes and their shared output contract.
- `EX/varka/VarkaKernelRunner` 633: one task's kernel instance and its `run` argument arrays.
- `EX/VarkaKernelEvaluator` 525: the projection's evaluator for one partition.
- `EN/vector/VarkaVectorSupport` 749: static helpers a kernel calls: addresses, validity words.
- `EN/vector/ChronoVectorOps` 721: a hand-written measurement kernel; not on the query path.

## 4. Where a decline reason comes from

Two kinds, with separate vocabularies.

**At compile time: free text.** A family calls `sink.decline("<reason>", expr)` or
`sink.note("<reason>", expr)` on the compile's `CG/DeclineSink`. There is no enum and no
catalogue: the reason is the string at the call site.

    grep -rhoE 'sink\.(decline|note)\(' sql/catalyst/src/main | wc -l     # 184 call sites
    grep -rhoE 'sink\.(decline|note)\("' sql/catalyst/src/main | wc -l    # 132 open with a literal
    grep -rlE 'sink\.(decline|note)\(' sql/catalyst/src/main | wc -l      # in 14 files

- The other sites pass a built string: `VarkaKernelPlan`'s refusal texts, `VarkaFamily.offReason`
  (a family switched off), `VarkaTypes.declineReason` (a type with no lane), a size decline.
- `DeclineSink.note` keeps only an entry's first note, the innermost one; `take` hands it out
  as a `CG/VarkaDecline(reason, expr)`, the expression in the query's column names.
- Recorded in `PartialVarkaProjection.declines()` (by entry) and `VarkaConjunctSpec.decline()`.
- A user sees it in verbose or formatted `EXPLAIN` on the Varka node, `b: residual (<reason>:
  <expr>)`, rendered by `EX/VarkaFusionReport`, and in the executor's debug log. A node the rule
  left to Spark entirely has no Varka node; `VarkaColumnarRule` logs why.
- A test reads it with `VarkaExpressionCompiler.declines(...)`, or prints it with
  `dev/varka_emit.sh "<expr>"`. Tests compare the text, so before rewording a reason, search
  the test sources and `docs/sql-varka.md` for it.

**At run time: a fixed list.** A kernel that meets a value outside a lowering's range returns a
status; `EX/varka/VarkaFallbackAccounting.REASONS` names each status bit (`day-producer`,
`int-overflow`, `decimal-slot` and so on). The batch goes to the row path and is counted in the
node's `numDeclinedBatches[<reason>]` metrics. Tests read them through
`EXT/VarkaFallbackReasons.java` and `VarkaFallbackReasonSuite`; the batch declines expected of
Spark's expression suites are in `sql/varka/spark_suites/expression_declines.tsv`.
`docs/sql-varka.md`, "Troubleshooting", is the user's path through both kinds.
