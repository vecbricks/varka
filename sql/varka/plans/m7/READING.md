# Reading for milestone 7: how thirteen engines keep their answers right

Read on 2 October 2026 for milestone 7, trust and maintainability (`m7/PLAN.md`), at the
owner's request: how the systems already checked out on the development machine keep their
answers correct and their code changeable, against the milestone's goals - proofs, differential
tests, fuzzing, memory safety, test adequacy, static analysis, JIT-compiled code and CI. Their
expression evaluators were compared before (`m8/SCOPE.md` items 16 to 30); this is the
other half of each codebase, its testing and maintenance machinery. Seven read-only surveys, each
over two or three systems with one rubric, and a search of this repository's record, which
found that none of the 44 papers the record has read is on testing, verification or SQL
semantics: they are on SIMD, query compilation and calendars. Paths below are relative to each
checkout, at the commits listed; a spot check of the most-cited paths and numbers confirmed them
(two counts are corrected in place).

| system | checkout under `/home/max/proj` | commit | date |
| :--- | :--- | :--- | :--- |
| Velox | `velox` | 484ef8297 | 2026-09-02 |
| Gluten | `gluten` | 90186c196 | 2026-09-16 |
| DuckDB | `duckdb` | d397c964cc | 2026-09-16 |
| DataFusion | `datafusion` | 606ae0f69 | 2026-09-16 |
| Comet | `datafusion-comet` | 8c229a703 | 2026-09-16 |
| ClickHouse | `ClickHouse` | ab12a1449 | 2026-09-02 |
| StarRocks | `starrocks` | a5dc21cf97d | 2026-09-16 |
| Trino | `trino` | 15587ef846a | 2026-09-16 |
| Druid | `apache-druid` | 5b1bcc1178 | 2026-09-16 |
| PolarDB-X | `polardbx-sql` | c17e7eca | 2025-12-05 |
| Arrow (C++; Java from the 19.0.0 source jars) | `arrow` | b274238283 | 2026-09-16 |
| spark-rapids-jni | `spark-rapids-jni` | 2574edc | 2026-09-04 |
| OpenJDK 25 (a fastdebug build) | `openjdk-build/jdk25` | 6c48f4ed7 | 2025-08-12 |
| datealgo-rs, fast-date-benchmarks, Lemire's blog code | the three directories so named | 77bf5ef, e35f46b, f1a5ce74 | 2026 |

## 1. A reference engine, at random (row 262)

**Velox** builds random expression trees from its function signature registry and checks each
against a simplified evaluation of the same input, flattened and with the junk under nulls
cleared, or against DuckDB, Presto or Spark (`velox/expression/tests/ExpressionVerifier.cpp`,
`velox/exec/fuzzer/PrestoQueryRunner.cpp`, `velox/functions/sparksql/fuzzer/SparkQueryRunner.cpp`).
Both sides throwing passes, one throwing fails, and the rows that did not throw are then
rechecked inside `try()`. A failure's input vectors and SQL are saved, `ExpressionRunner`
replays them, and `--findMinimalSubExpression` shrinks to the smallest failing subtree
(`velox/expression/tests/ExpressionRunner.cpp`). It runs on every PR with four seeds.

**Gluten** runs each query with the plugin off and on and asserts that nothing fell back
(`gluten-substrait/src/test/scala/org/apache/gluten/execution/GlutenQueryComparisonTest.scala`).
**Comet** fuzzes with its own data generator - nulls, NaN, both zeros, infinities, integer
extremes, a constant string to force a dictionary
(`spark/src/main/scala/org/apache/comet/testing/FuzzDataGenerator.scala`) - and its
`checkSparkError` requires the same exception class, error class and SQLSTATE. Its
`CometFallbackInvarianceSuite` forces one expression back to Spark at a time: the answer must not
change, and a comparison counts only if the plan did. **Trino**'s function tests run each
expression through the compiled path and the interpreter in one call (`QueryAssertions.java`),
and its verifier reruns a control query three times before trusting a mismatch
(`service/trino-verifier`). **ClickHouse**'s BuzzHouse runs one query under two values of up to
forty settings, or against a peer database, and fails when only one side errors
(`src/Client/BuzzHouse/Generator/QueryOracle.cpp`); its server runs TLP and NoREC checks on fuzzed
queries (`src/Interpreters/QueryOracleChecker.cpp`). **PolarDB-X** compares with MySQL.

*For Varka.* Row 262's oracle is Gluten's, Varka off against on, with Comet's error comparison.
Three things the record lacks come with it: a shrinker over IR subtrees, which the record has
never had (VARKA-234's reproducer was minimized by hand); reproducers saved and replayed in PR CI,
as Arrow commits its fuzzers'; and Comet's fallback invariance, forcing one output to decline
at a time.

## 2. Spark's own suites with the accelerator on (row 271)

**Comet** patches a Spark checkout per Spark version (`dev/diffs/3.4.3.diff` to `4.1.3.diff`)
so that Spark's own SQL suites run with Comet loaded, Spark's golden `.sql.out` files as the
oracle; 47 tests are excluded in the 4.1.3 patch, each with `IgnoreComet("reason")`, and a
patch is regenerated from a Spark checkout, never hand-edited. **Gluten** subclasses about 480 of
Spark's suites and keeps about 450 per-test excludes, mostly with a reason
(`gluten-ut/spark41/src/test/scala/org/apache/gluten/utils/velox/VeloxTestSettings.scala`); its
Delta gate fails on a new failure and also when a listed failure starts passing, so the list
cannot go stale (`.github/workflows/util/delta-spark-ut/known-failures.txt`). The record noted on
16 September 2026 that this is "the widest differential available and not yet in the record as
a row" (`m8/SCOPE.md` item 24). Row 81 harvests the date family's golden inputs only;
row 271 runs the suites.

## 3. One corpus under many configurations (rows 248, 262)

**DuckDB** reruns its whole suite under configurations such as `disable_optimizer`,
`verify_statement_prepare` and a vector size of 2 (`test/configs`, 64 files, the PR matrix's
groups in its `Makefile`), each skipped test with a reason, so the skip lists record the known
divergences. **DataFusion**'s `# configMatrix:` reruns one file over every combination of the
settings it lists and names the failing one (`datafusion/sqllogictest/src/config_matrix.rs`).
**ClickHouse** gives every test random settings, its expression JIT among them
(`compile_expressions` 0 or 1 in `tests/clickhouse-test`), and on a failure shrinks the settings
to the ones that matter. **Druid** runs every SQL test with vectorization off and forced on, and a
test marked `cannotVectorize()` fails the moment it vectorizes, so the marker cannot go stale.
**StarRocks** reruns about seventy expression tests through its JIT (`verify_with_jit` in
`be/test/exprs/exprs_test_helper.h`).

*For Varka.* Row 248's table of options is the matrix's source: the suites run under the
options' configurations with a reasoned skip list, and a shape marked as declining fails when it
starts to fuse.

## 4. Poisoned and edge inputs (row 138)

**Velox** leaves random values under null bits, junk offsets in null arrays and maps, and hands
functions dirty result buffers, then compares with a cleaned copy. **DuckDB** fills freed blocks
with `0xa5` and requires identical output with memory pre-filled with zeros and with `0xFF`
(`scripts/test_zero_initialize.py`). **Druid**'s SIMD tests use a poison row before the start, a
buffer starting at byte offset 1, and a null exactly at the SIMD chunk boundary
(`processing/src/test/java/org/apache/druid/query/aggregation/simd/SimdAggregatorTestHelpers.java`).
**Arrow** checks each kernel on the whole array, row by row as scalars, and on slices at offsets
1 to 16 and n/3 (`cpp/src/arrow/compute/kernels/test_util_internal.cc`). Row 138's poisoned null
lanes take Velox's and DuckDB's forms; Druid's chunk-boundary null is the lane-group boundary in
Varka's terms.

## 5. Memory (row 263)

**Arrow**'s C++ debug pool writes `size ^ kDebugXorSuffix` just past each allocation and checks it
on reallocation and free, and CI runs with `ARROW_DEBUG_MEMORY_POOL=trap`
(`cpp/src/arrow/memory_pool.cc`, `ci/scripts/cpp_test.sh`). **Arrow Java** bounds-checks `ArrowBuf`
by default (`BoundsChecking`, off only under `arrow.enable_unsafe_memory_access`) and has a debug
allocator that keeps a history per buffer (`-Darrow.memory.debug.allocator=true`). **Druid**'s
buffer pool, poisoned in every unit test, records where each buffer was taken and throws on the
next take after a leak (`processing/src/main/java/org/apache/druid/collections/StupidPool.java`).
**Trino** asserts every memory pool is back to zero after each test class; **Velox** fails
teardown while pools are alive (`checkUsageLeak`); **spark-rapids-jni** swaps the test JVM for
one under CUDA's compute-sanitizer. The C++ systems run ASan and UBSan on every PR, and TSan,
MSan or valgrind nightly.

**A padding fact Varka relies on.** Arrow's specification only recommends padding buffers to 64
bytes (`docs/source/format/Columnar.rst`). Arrow Java's `allocateNew` sizes a validity buffer in
whole 64-bit words (`BaseValueVector.roundUp8ForValidityBuffer`), and its allocator rounds a
request up to a power of two; but the IPC reader slices each buffer to its exact length
(`MessageSerializer`, `body.slice(offset, length)`), and a C Data Interface import sizes a bitmap
to `offset + length` bits. Varka's word-at-a-time validity writes are safe on the output buffers
it allocates itself, as today; row 263's sanitizer checks every segment against its buffer's real
capacity, so a future path that writes into a buffer Varka did not allocate fails by name.

*For Varka.* Row 263 records each buffer's range, puts Arrow's canary past it, records where each
segment came from, as Druid's pool does, and requires every byte back at close.

## 6. Static analysis and test adequacy (rows 265, 266)

**Trino**'s `.mvn/errorprone.config` sets 91 Error Prone checks to error and turns 12 off, each
with a written reason, and runs them as a job of their own on changed modules. **Druid** adds
forbidden-APIs, SpotBugs, PMD, IntelliJ inspections at error, and an OpenRewrite dry run that
fails on a pending rewrite; **Arrow Java** runs Error Prone and the Checker Framework on its
memory module. None of the thirteen runs NullAway.

**Druid** fails a pull request whose changed lines have under 50% line or branch coverage
(`.github/scripts/create-jacoco-coverage-report.sh`); **ClickHouse** fails one that drops coverage
by more than 0.3 points, and selects tests from per-test coverage; **StarRocks** requires 80% on
changed code. None of the thirteen does mutation testing.

*For Varka.* Row 266 takes Trino's file as its pattern: an error-only list, a reason for each
check turned off, its own CI job. Row 265 starts from a diff-coverage report, Druid's, before the
mutation run.

## 7. Arithmetic (rows 240 to 245)

**Velox** checks every day within 600 years either side, two million random days and every
century boundary against Hinnant's library (`velox/type/tests/FastDateTest.cpp`).
**fast-date-benchmarks** walks each algorithm's whole validity range against a day-by-day
reference, reports the first input where it diverges as a coverage of 2^32, and for 64 bits sweeps
plus and minus 2^32 and samples 2^32 random values (`tests/rangetest_fast_64.cpp`); its
`paper/fast_eaf.cpp` computes the paper's proven bound in 128-bit integers. **PolarDB-X** compares
its fast date path with the reference for every year from 0 to 19999; **Trino** checks wide
integer arithmetic against `BigInteger` for every sign combination; **ClickHouse** sweeps every
embedded time zone against cctz. Nobody proves a lowering with a solver: Lemire's code uses Z3
only to find counterexamples and synthesize tables.

*For Varka.* The proofs of rows 240 to 245 are new among these systems. Their test-side twin is
fast-date-benchmarks' pattern: pin the first failing input outside a lowering's proven range, and
sample the long lane where no sweep reaches.

## 8. Generated and JIT-compiled code (rows 272, 246, 257)

**OpenJDK**'s `VectorizationTestRunner` turns the compiler off through WhiteBox to get the
interpreter's answer, forces C2, and compares
(`test/hotspot/jtreg/compiler/vectorization/runner/VectorizationTestRunner.java`); its IR test
framework asserts which C2 nodes a compilation produced, vector nodes with their lane counts
(`test/hotspot/jtreg/compiler/lib/ir_framework/README.md`). The Vector API's tests are generated
one species per class from templates, against scalar Java (`test/jdk/jdk/incubator/vector`). C2's
stress flags - `StressIGVN`, `StressGCM`, `StressLCM` with a `StressSeed`, `RepeatCompilation` -
perturb the compiler deterministically, and the local JDK is a fastdebug build, so the debug-only
ones run here too.

The Vector API's plain-Java fallback runs in the interpreter, in C1 and wherever a C2 intrinsic
bails out, and it is a second implementation to compare the intrinsics against: off with
`-XX:+UnlockExperimentalVMOptions -XX:-EnableVectorSupport`, or per intrinsic with
`-XX:DisableIntrinsic`. It is not independent everywhere - the divide-by-zero check, shift-count
masking and the AND_NOT rewrite run in Java before the dispatch, so the two paths share them. No
test in the JDK runs the Vector API's correctness suite with the intrinsics off.

*For Varka.* Row 272 runs Varka's kernels under those configurations and compares the answers.
Item 58 called HotSpot the trusted base that no proof reaches, and JDK 25 carries a known C2
miscompilation of masked stores (JDK-8388492, item 56); comparing configurations checks the base
where it is cheap to. Seeded stress flags with `RepeatCompilation` are the levers row 257 lacks,
and one class per species is row 246's shape.

## 9. CI (rows 255, 256)

**DuckDB** benchmarks a PR against its merge base in alternating batches, compares medians, fails
at 10% slower and stops early within 3% (`scripts/regression/test_runner.py`) - Varka's A/A
controls, made a gate. **ClickHouse** reruns new tests fifty times, requires a bug fix's new test
to fail on master, reverts a PR that broke master within the hour, and compares a PR's server with
master's with a randomization test on medians (`ci/jobs/scripts/perf/compare.sh`). **Comet**'s
nightly covers every commit since the last green one and opens an issue when it fails, and a check
fails when a suite is not registered in CI. **Velox** fails a build that removes a function
signature and fuzzes changed functions harder; **Trino** requires every commit of a PR to compile
and runs its benchmarks as smoke tests.

*For Varka.* Row 256's per-section regeneration becomes a paired run of the PR against its merge
base; row 255's queue reruns a PR's new tests several times and requires a fix's new test to fail
on the base.

## 10. What none of them does

SMT proofs of arithmetic lowerings, mutation testing, bounded-exhaustive generation of expression
trees, and tests of JIT history - what a kernel meets when compiled after others. Rows 240 to 245,
265, 269 and 257 would make Varka the first among these thirteen, which is row 270's story.

## 11. The papers, read

The fifteen papers this note first listed were read in full on 3 October 2026, each by the row
it bears on. The owner downloaded them to `~/Downloads/Milestone7` (`SOURCES.md` there gives each
copy's source); mechanical transcriptions sit beside them in `markdown/`, made the way
`sql/varka/papers/README.md` describes and checked against each PDF's text layer (99.81 to 100
per cent of the characters kept). They are not copied here: these are reading notes. Pages are
the PDFs'.

**Lopes, Menendez, Nagarakatte and Regehr, "Provably Correct Peephole Optimizations with Alive",
PLDI 2015; Lopes, Lee, Hur, Liu and Regehr, "Alive2: Bounded Translation Validation for LLVM",
PLDI 2021.** A rewrite is a source and target with a precondition, proved by refinement with a
bit-vector solver; Alive found 8 wrong rewrites among 334, most making an expression defined over
fewer inputs (Alive pp. 8-9), and wide multiplies and divides took hours, worked around by
narrowing widths (p. 9). Alive2 asks first whether a precondition is always false, "because of
bugs or limitations in the encoding" (p. 8), and its authors found six soundness bugs in Z3
(p. 12). For Varka, the precondition is the guard's bound; Spark has no undefined behaviour at
this layer, so refinement becomes: decline wherever Spark raises, and equal (data, valid) pairs
elsewhere, data free under a null bit. Five things follow: the prover's verdicts are themselves
checked (`unknown`, timeouts, satisfiable preconditions, two solvers agreeing); Java's operators
are stated in a prelude, since an SMT operator with the right name can have the wrong meaning
(Alive2 p. 4); the proofs' constants come from the code; row 241's forms divide in doubles, so
it needs floating-point theory and a measured solve time; and the analyses that supply the
preconditions, which Alive trusts (pp. 2-4), are proved too (row 281).

**Granlund and Montgomery, "Division by Invariant Integers using Multiplication", PLDI 1994;
Lemire, Kaser and Kurz, "Faster Remainder by Direct Computation", Software: Practice and
Experience 2019.** Theorem 5.1 (p. 5) is exactly the condition `emitMulHiDivide` relies on: one
inequality on the multiplier, which holds for every divisor up to 2^17, where VARKA-149's sweep
covers nine. The calendar's round-up magics follow from (4.4) over their real ranges, with a
no-wrap condition the paper never needs; the round-down-plus-carry steps, `floorMod7`'s folds and
the leap hash have no theorem and need their own proofs; and `NARROW_DECOMPOSE_MAX_DAYS` sits past
what the closed form proves, so only an exact query covers it. Several quoted bounds are
sufficient rather than exact - `YEAR_M`'s 44858 first fails at 44894 - and should say which.
(7.1) (p. 7) covers neither long-lane form. Lemire's direct remainder gives `floorMod7` in five
operations instead of twelve under a range bound, and `remainderOfSixty` without a divide - speed,
for a later milestone.

**Rigger and Su, "Testing Database Engines via Pivoted Query Synthesis", OSDI 2020; "Detecting
Optimization Bugs in Database Engines via Non-Optimizing Reference Engine Construction", ESEC/FSE
2020.** NoREC compares a query the engine optimizes with one it cannot, because a DBMS cannot
turn its optimizations off (p. 3); Varka can, which makes row 262 NoREC with the switch NoREC
lacked. NoREC excludes errors "optimized away", since SQL does not say whether AND short-circuits
(p. 5); Varka's contract is stricter, vanilla Spark's order, and reading the code against that
exclusion found VARKA-273. PQS evaluates a predicate on a pivot row with an interpreter and
rectifies it to TRUE (p. 6), which gives row 262 filters that select known rows; and PQS tests
its interpreter against the engine (pp. 12-13), which is row 276 for Varka's reference evaluator.

**Rigger and Su, "Finding Bugs in Database Systems via Query Partitioning", OOPSLA 2020; Ba and
Rigger, "Testing Database Engines via Query Plan Guidance", ICSE 2023.** TLP compares rows, not
counts - counts missed 5 of 48 bugs (p. 20) - and relies on partitions optimized to different
degrees (p. 9). In Spark the partitions are optimized away before Varka sees them:
`BooleanSimplification` rewrites `NOT (a < b)` into `a >= b` and `NullDownPropagation` pushes
`IS NULL` onto the columns, so row 138's oracle runs with those rules excluded too. QPG steers
generation toward unseen query plans, finding 1.4 times SQLancer's unique bugs where the
coverage-guided SQLRight, with more code coverage, found 17 times fewer (pp. 8-9); Varka's plan
is the emitter's record of what it did, which row 280 counts.

**Zeller and Hildebrandt, "Simplifying and Isolating Failure-Inducing Input", IEEE TSE 2002 (the
version submitted); Le, Afshari and Su, "Compiler Validation via Equivalence Modulo Inputs",
PLDI 2014.** ddmin's result is 1-minimal by construction (Prop. 11, p. 5) and a single culprit
costs about 2 log2 n tests (Prop. 13); it found the one GCC option of 31 that prevents a crash in
7 tests (p. 8), which is VARKA-234's hand minimization, mechanized. An IR tree is shrunk roots
first, then level by level, each candidate kept well typed (row 277). EMI varies the program
where its input does not reach and requires the same output; 64 of its 147 bugs showed under one
configuration only (p. 8). Varka's variants are outputs permuted, a dead output added, an untaken
arm replaced (row 278); and its harnesses pre-fill output validity with 0xFF only, so a kernel
that never sets a valid bit passes on an all-valid output - row 138 adds the 0x00 fill.

**Li, Jiang, Xu and Su, "Validating JIT Compilers via Compilation Space Exploration", SOSP
2023.** Every compiled-or-interpreted choice must give the same output, and 89.6% of Artemis's
disagreements needed choices between all-interpreted and all-compiled (Table 4, p. 10); HotSpot's
one miscompilation needed C1, C2, a deoptimization and an OSR (p. 3). For Varka the finding is
about its own tests: `VarkaKernelCheck` runs a fresh class on at most 1000 rows and the test
sessions turn the warm-up off, so every fuzzer verdict comes from the interpreter, which runs the
Vector API's Java fallback. Row 274 warms each kernel to C2 and varies its batch history; row
272's arms prove which tier ran; C2's stress flags are product diagnostic flags in JDK 25, so they
run nightly.

**Petrovic and Ivankovic, "State of Mutation Testing at Google", ICSE-SEIP 2018; Banerjee, Clapp
and Sridharan, "NullAway: Practical Type-Based Null Safety for Java", ESEC/FSE 2019.** Google
mutates changed lines only, suppresses arid code, and grew usefulness from 20 to 80 per cent by
turning every "not useful" into a rule (pp. 2-6). For Varka a mutant killed only by the
emitted-bytes oracle is not a behavioural kill, and the ghost fallback lets a `sql/core` test
pass over a kernel that throws - row 275. NullAway's gain came from checking on every build at
1.15 times the compile (pp. 1, 8), staged by package with unannotated classes trusted (p. 5);
Varka's `null`s sit mostly in five classes, and a class filled across passes, as `Slots` is,
cannot use `@Initializer`.

**Boyapati, Khurshid and Marinov, "Korat: Automated Testing Based on Java Predicates", ISSTA
2002; Runciman, Naylor and Lindblad, "SmallCheck and Lazy SmallCheck", Haskell Symposium 2008.**
SmallCheck's series generate by type, and a bijection test checks a generator against the
predicate (p. 6); counts explode from tens of thousands at one depth to billions at the next
(p. 5). Varka's IR is typed locally, so series per sort generate only well-typed trees: about
1,100 at size 3, 27,000 at size 4 and 700,000 at size 5 on the int lane, seconds, forty
CPU-minutes and sixteen CPU-hours across fifteen configurations, so the bound is node count, not
depth. Korat states a precondition for every behaviour (p. 6), which for Varka is a verdict per
row - an answer, a decline that must happen, one that may, an input outside the contract (row
279). The grammar draws `date_add` offsets only as literals today, and its reach test counts node
types, not positions.

## 12. What to do with it

From the engines (sections 1 to 9), two rows and five refinements in `m7/PLAN.md`: row
271 runs Spark's own SQL suites with Varka on (section 2); row 272 runs the kernels under several
JIT configurations (section 8); and rows 262 (a shrinker, replayed reproducers, fallback
invariance), 263 (the canary, the record of where a segment came from, the capacity check), 248
(the configuration matrix with reasoned skips and stale-marker failures), 256 (a paired run
against the merge base) and 255 (new tests rerun, and a fix's test failing on the base) take what
sections 1, 3, 5 and 9 found.

From the papers (section 11), nine rows - 273, the filter-order bug NoREC's exclusion pointed at;
274, fuzzers that check C2-compiled kernels; 275, a kernel failure the ghost fallback hides fails
the test; 276, the reference evaluator held to Spark's interpreter; 277, one shrinker; 278,
answer-preserving variants; 279, declines as specified behaviour; 280, what the emitter reached;
281, proofs of the range analysis - and refinements of rows 81, 138, 240 to 245, 248, 252, 257,
262, 265, 266, 269 and 272. Work the papers point at beyond milestone 7 is `m8/SCOPE.md`
item 82.
