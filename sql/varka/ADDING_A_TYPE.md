# Adding a type to Varka

Milestone 8 adds seven types: three have landed in part - `TINYINT`, `SMALLINT` and
`TIMESTAMP_NTZ`, the last on the long lane with its comparisons, choice and casts with `DATE` and
its arithmetic and fields still to come (rows 339 and 340) - and three have not started (`DOUBLE`,
`FLOAT` and `DECIMAL`); `BOOLEAN` has its conditions and the condition-to-value bridge,
`CondValue` (row 460), but no compiler arm yet reads a boolean as a value. This page is the path a
type takes through the engine, in the order the code runs, with the file named for each step and a
command that says whether the step is done.

Read [the architecture guide](../../docs/sql-varka.md) first, at least its glossary - *lane*,
*word*, *morsel* and *epilogue* mean something specific here - and skim
[adding an expression](ADDING_AN_EXPRESSION.md), because most of a type's expressions arrive by
that path once the type has a lane.

## The shape of the change

The two types added under this milestone touched these files; the counts are from their diffs.

| Case | Production | Tests | Docs, generated |
| :--- | ---: | ---: | ---: |
| `BOOLEAN` conditions (`5d9beefb8c0`) | 17 | 20 | 10 |
| `TINYINT` and `SMALLINT` lanes (`33996c315d9`, the one before the coverage work) | 14 | 13 | still landing |

The list is long because a type is not one feature: it is a lane in the IR, leaves that read it,
a rendering in the emitter, a way in and out of Arrow, and rows in the census and the coverage
table. The compiler lists the sites that are exhaustive switches; `dev/varka_new_lane.py --check
<LANE>` lists the ones that are not (`plans/m8/VARKA-474.md`), and the steps below are the order to
take them in.

## Where to read first

In this order, because each file names the next:

1. `VarkaTypes.java` and `VarkaTypeId.java` - the type registry, the one place a type is
   registered: its id, the lane it is read on or why it is read on none, its census name and SQL
   spelling, its storage width, the forms the tests probe, the lanes its kernels reach and its
   conformance suite. `VarkaTypesSuite` writes it to `sql/varka/types.tsv`, which the Python
   tools read.
2. `VarkaVectorIR.java` - the `LaneType` member and the leaves that read a column of the type.
3. `Lane.java` - the runtime facts of a lane: its Vector API class and species constants, its
   width in bits and bytes, its conversions. `Lane.of` maps a `LaneType` to its entry, a switch
   with no `default`.
4. `VarkaNodeCompiler.java` - where a leaf is built, and the decline a column of a type no lane
   reads takes, with the registry's reason.
5. `Analysis.java` - which nodes are admitted, on which lane their inputs are read, and the
   guards a node arms.
6. `VarkaBodyEmitter.java`, `VarkaLoopEmitter.java`, `VarkaVectorWalk.java` - the emitted code,
   per node kind. The body emitter is the dense body, the loop emitter the groups, and the walk
   the node dispatch they share.
7. `VarkaLaneArrow.java` - the Arrow vector each type is read from and written to, one switch over
   `VarkaTypeId`, which `isArrowBacked` (`VarkaEvaluatorBase.java`, where an Arrow column becomes a
   kernel input) and `allocateVector` (`VarkaKernelEvaluator.java`, where an output goes back to
   Arrow) both read.
8. `VarkaReference.java` - the scalar semantics the fuzzers compare against.
9. `VarkaIrGrammar.java` - what the IR fuzzer can draw.
10. `VarkaCoverageSuite.scala` - the rows that make the type count as fused, and the two files
   they generate; `VarkaCoverageRows.scala` reads the table back for the fuzzers.

## The path

**1. Decide the lane and the representation, before writing anything.** A type either joins an
existing lane (`TIMESTAMP_NTZ` is a 64-bit count, `DATE` is already on the int lane) or needs a
new one (`BOOLEAN`'s bitmap, `DECIMAL`'s 128-bit pair). A type that fits an existing lane may
still need its own *stored* width: `TINYINT` is computed on the int lane and stored a byte a row.
Write the answer down in the row before the code: it decides every later step. A type milestone
8 plans already has a registry entry whose admission is `Owed` with its row; the work ends by
turning that into `OnLane`.
Check: read `VarkaTypes`, `VarkaVectorIR.LaneType` and `Lane` and say which entry the type uses,
or which two numbers (width in bits, bytes a row) its new entry carries.

**2. Add the lane's facts.** A new `LaneType` member, its `Lane` entry - the vector class name, the
species constants, the stride, the conversions - and the matching arms in every `switch` over
`LaneType` and `VarkaTypeId`, `Lane.of` and `VarkaLaneArrow.of` among them. The switches have no
`default`, so the compiler lists the sites.
Check: `build/sbt catalyst/compile` - it fails at each missing case, and passes when they are all
there.

**3. Add the leaves.** A leaf names the column's type in the IR and carries the validity word.
A leaf that is also a condition (`BoolColumn`) carries its two words instead.
Check: `build/sbt 'catalyst/testOnly *VarkaLaneTypeSuite'`; extend it with the new member's width,
stride and lane.

**4. Teach the compiler.** The type's registry entry - its admission to `OnLane`, which is what the
compiler's leaves read, with the type's arm in `VarkaLaneArrow.of` naming its Arrow vector (step 10;
`VarkaLaneArrowSuite` fails while the two disagree), and `types.tsv` regenerated with
`VARKA_TYPES_REGEN=true build/sbt 'catalyst/testOnly *VarkaTypesSuite'` - then the leaf construction
in `VarkaNodeCompiler` and the family that claims the type's expressions (`VarkaExpressionCompiler`,
`VarkaConditionCompiler`, or a `Varka*Compiler` they chain). A type that must decline says why, with
the reason enumerating what it needs - the decline reasons are what a reader sees when a query does
not fuse.
Check: `build/sbt 'catalyst/testOnly *VarkaTypesSuite *VarkaExpressionCompilerSuite'`.

**5. Teach the analysis.** Which nodes are admitted on the new lane, the lane the inputs are read
at, and any guard the node arms (an overflow check, a range guard). A guarded node parks its
operands; a node whose result is stored at another width says so.
Check: `build/sbt 'catalyst/testOnly *VarkaRangeAnalysisSuite'` and the family's emitter suite.

**6. Emit it.** The body emitter, the loop emitter and the walk, per node kind. Follow the
exhaustive-switch rule: a new node kind is a compile error at each of them, and a silent
fallthrough is the bug the rule exists to prevent.
Check: the family's emitter suite, and the bytes oracle: regenerate
`VARKA_BYTES_REGEN=true build/sbt 'catalyst/testOnly *VarkaEmittedBytesSuite'` and see that only
the new shapes moved in `sql/varka/emitted_bytes.json`.

**7. Warm it.** The template warm-up runs once per JVM and calls, a `THIN_CALLS_PER_ROUND`
block per lane, every Vector API method a kernel of the type reaches; a method left cold is
the failure CI names. Add the type's methods to `VarkaVectorTemplateWarmup`'s block for its
lane - the loads, the stores, the compares, the operators the emitted kernels call and the
mask methods - and add a block beside the others when the lane has none.
Check: `build/sbt 'catalyst/testOnly *VarkaWarmupReachSuite'` - it fails listing every
method a kernel reaches and the warm-up calls fewer than 2,000 times, so it passes only
when the new lane is warm.

**8. Add the reference semantics.** `VarkaReference` computes the node scalar-wise; the IR fuzzer
compares the kernel against it. A node the reference does not know is a node the fuzzer cannot
check.
Check: `build/sbt 'catalyst/testOnly *VarkaIrFuzzSuite'`.

**9. Let the fuzzers draw it.** The type's entry in `VarkaFuzzTypes` (a switch over `VarkaTypeId`,
so the compiler asks for it) says per fuzzer whether the type is drawn: its IR site turns from
`Owed` to `Present` with the leaf and the seeded sequence its trees come from (`VarkaIrGrammar`),
its Spark site with the fixture's columns, and its `ValueSource` holds the values a column of it
is filled with. A type draws in a sequence of its own seed, so that no committed corpus moves,
and a new lane's sequence is pinned in `emitted_bytes.json` under a key of its own, two thousand
shapes as twenty blocks, as `fuzz_byte` and `fuzz_short` are (`VarkaEmittedBytesSuite`);
`VarkaFuzzTypesSuite` fails until `sql/varka/fuzz/types.tsv` is regenerated
(`VARKA_FUZZ_TYPES_REGEN=true build/sbt 'catalyst/testOnly *VarkaFuzzTypesSuite'`).
Check: `build/sbt 'catalyst/testOnly *VarkaFuzzTypesSuite *VarkaIrFuzzSuite'`,
`build/sbt 'sql/testOnly *VarkaSparkFuzzSuite'` and
`python3 dev/varka_bytes_moved.py origin/master --ignore option_arms`, which must print nothing:
a new coverage row moves `option_arms` and nothing else of the existing keys, and a new sequence
adds keys.

**10. Admit it in and out of Arrow.** The lane's entry in `VarkaLaneArrow.of`: each Arrow vector
class the lane's types arrive in and are written to, the Spark types stored in it, and how a
destination of it is built. `isArrowBacked` and `allocateVector` read the entry, so a lane cannot
compile without one. A vector that belongs to no lane - a boolean column's `BitVector`, a derived
input's string - is a named case in `isArrowBacked` instead. `VarkaKernelRunner` handles any input
whose stored width is not its type's (a bitmap is one bit a row, and its Arrow vector reports a type
width of zero). The sanitizer registers the rows a call can reach, not the allocation's padding.
Check: `build/sbt 'sql/testOnly *VarkaKernelEvaluatorSuite *VarkaWarmupEndToEndSuite
*VarkaMemorySanitizerEndToEndSuite'`.

**11. Give it coverage rows.** A load, a comparison, a choice and an output in
`VarkaCoverageSuite.scala` - without them the type does not count as fused, whatever the emitter
does. A type with no column in the table yet needs one in five places: `VarkaCoverageSuite`'s
`columns`, which `coverage.json`'s column map is written from; the copies `VarkaEmittedBytesSuite`
and `VarkaWidthAuditShapes` keep (a row over a column the bytes suite lacks is listed in
`coverage_rows_skipped` rather than hashed, and the width audit's probe fails on it); and the
fixtures of `VarkaCoverageDifferentialSuite` and `VarkaSparkFuzz`, which may derive the column from
existing ones, as the `TIMESTAMP_NTZ` columns are derived from a date and a time. Regenerate in this order: `VARKA_COVERAGE_REGEN=true build/sbt 'catalyst/testOnly
*VarkaCoverageSuite'`, then `VARKA_AUDIT_REGEN=true build/sbt 'catalyst/testOnly
*VarkaWidthAuditSuite'`, then `VARKA_BYTES_REGEN=true build/sbt 'catalyst/testOnly
*VarkaEmittedBytesSuite'`, then `VarkaCoverageSuite` again, and check that only the new rows moved
in `coverage.json`, `docs/sql-varka.md`, `width_audit.json` and `emitted_bytes.json`.
Check: `python3 dev/varka_census.py --check`.
When master moves under the branch, merge it: the merge driver (`dev/varka_generated_merge.py`,
installed by `dev/varka_precommit.sh --install-hook`) merges these files per row and lists what
it could not compute, and `dev/varka_regen_generated.sh` regenerates exactly that, in this order.

**12. Census the operators the type needs.** New operators get a cell in
`VarkaOperatorCensusCatalog`, measured on the laptop by
`VARKA_CENSUS_REGEN=true build/sbt 'catalyst/testOnly *VarkaOperatorCensusSuite'`; the runner
columns come from the next `varka-width-audit.yml` dispatch, which needs the branch pushed.
Check: `python3 dev/varka_census.py --check` and the census suite.

**13. Price it.** `VarkaEmitCost.kindOf` names a node's cost feature; a node that falls to
`default` is priced as its class name alone, which lumps unlike emissions together. New nodes
join the awaiting-refit list (row 352) until the model is refitted.
Check: `build/sbt 'catalyst/testOnly *VarkaEmitCostSuite'`.

**14. Benchmark it.** A new feature family gets its own benchmark class beside
`VarkaLaneConversionBenchmark`, on `VarkaBenchmarkBase`, every arm driven to level-4 code before
it is timed (row 407's harness rule). Results are committed, not quoted.
Check: the benchmark's own results file, and the row's numbers against it.

**15. Run the matrix over the new emitting suites.** A new emitting suite runs under every
configuration, not only the one a PR's scoped CI run picks:
`dev/varka_matrix.sh --all --module catalyst --suites <the new suites> -j 4`, or `--module sql`
for the suites that live in `sql/core`, about 40 minutes, plus the defaults. A test whose premise
an option changes pins it, cancels under it, or gets a line in `sql/varka/matrix/skips.tsv` with
the reason.
Check: the matrix's table - every row `fail 0`, `unfused 0`, and no stale skip line.

**16. The linters, before the push.** `./dev/varka_errorprone.sh --master "$(git rev-parse
origin/master)"`, `./dev/lint-java`, `./dev/lint-scala`, `python3 dev/varka_census.py --check`,
`dev/varka_new_lane.py --check`, the 100-column and non-ASCII scans, and the pre-commit hook.
Check: their exit codes, reported in the pull request.

## What the guards do not catch

Two kinds of site exist (see `plans/m8/VARKA-474.md` 1). Exhaustive switches the compiler checks:
the IR's per-kind dispatch, `Lane.of`, the registry's entries and `VarkaLaneArrow.of` (both over
`VarkaTypeId`), and the per-lane switches of the reference evaluator and the fuzz grammar. And
sites a new lane compiles without, which `dev/varka_new_lane.py --check <LANE>` walks: per lane,
the registry's admission, the template warm-up, a fuzz-grammar leaf (read from the descriptor
`sql/varka/fuzz/types.tsv`) and a benchmark; per Spark
type on the lane, a coverage column, the width audit's shapes and the census, so a new type on an
existing lane is checked once the registry puts it on the lane. It prints each site with no case,
and a gap a later row owns as owed to that row. Sites keyed by node kind rather than by lane -
the cost model's feature names, the census catalog of operators - need no arm for a new lane, only
for its new nodes (steps 12 and 13).

Between them sit the decline sites. A column of a type the registry holds and no lane reads
declines with the type's name and the registry's reason - the row that builds an owed type, the
decision for a declined one (`plans/m8/VARKA-494.md`) - so a planned type is told apart from an
unsupported shape. A type the registry does not hold, and every shape the compilers do not claim,
falls through to a decline with the compiler's general reason.
