# VARKA-499: Four test-harness traps the new types meet

## 0. What exists now (admission check)

From master at `ef32e007d3a` (30 October 2026). Test paths are under
`sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/` unless
named. The four traps were met on row 338's matrix runs (fork run 40294304006, `VARKA-338.md`
10.4) and two of them again on row 485's (#1063) and row 507's (#1091).

### (a) A kernel of two lanes in the shared test JVM

1. **The guard sees the conflict only under a lane configuration.** `VarkaSpeciesGuard.check`
   (`VarkaSpeciesGuard.java:227-253`) refuses a class that would add a second size of one vector
   class to the JVM's registry (`Registry.admit`, `:195-219`), reading the sizes from the class's
   constant pool (`sizesUsed`, `:156-182`). It is called where a test defines a kernel, in
   `VarkaKernelChecks.load` (`VarkaKernelChecks.java:1197-1199`) and
   `VarkaEmitterFixtures.load` (`VarkaEmitterFixtures.java:101-102`), and is off in a suite's own
   JVM (`OWN_JVM_PROPERTY`, `:75`).
2. **Why the defaults pass.** A kernel that holds both lanes is driven at the long lane and keeps
   its int values in the int species of the long species' width
   (`VarkaEmitterMixedLaneSuite.java:63-75`). At the defaults both are the preferred species, so
   the class names one size per vector class and the guard is silent; under `lanesOverride=4` the
   int kernels establish a narrower int species than the mixed kernel's, and under
   `narrowHalfSpecies=true` a narrowing store names the half-width int species beside the full one
   (`VarkaEmitOptions.java:281-290`). The matrix's lane configurations are `lanesOverride=4`,
   `lanesOverride=16` and `narrowHalfSpecies=true` (`VarkaMatrixRuns.java:114-117`, `:189`).
3. **What happened.** Row 338's two NTZ suites passed at the defaults and failed five tests under
   the two configurations; they and row 485's `INT`, `BIGINT`, `TIME`, `DATE` and day-time
   interval conformance suites now carry `@VarkaSuite(ownJvm = true)`. Nothing at the defaults
   tells the author a suite needs it. Only the two catalyst helpers above call the guard, so a
   sql/core session's kernels are not checked at all.

### (b) A coverage row whose literal no fixture row holds

4. **The cache prunes `In` and the comparisons with a literal, not `InSet`.** The Arrow cache's
   batch statistics drop a batch whose bounds rule a literal out
   (`sql/core/src/main/scala/org/apache/spark/sql/columnar/CachedBatchSerializer.scala:302-305`
   for `In`; `InSet` has no case). At one row a batch (`arrowBatchSize=1`) a literal no fixture row
   holds leaves no batch for the kernel.
5. **The check that met it is the matrix's "fuses nothing".** `dev/varka_matrix_report.py:139-149`
   fails a configuration where a test fused batches at the defaults and none under it; the NTZ
   rows' literals were changed to held ones (`ntzHeld`, `VarkaCoverageSuite.scala:180-187`).
6. **Since 29 October the trap has moved.** Row 509 (#1101 to #1103) moved every coverage row's
   answers below the session into `VarkaCoverageHarnessSuite` (sql/core), whose batches have no
   statistics (`VarkaCoverageHarnessSuite.java:504-509`), so its `answered > 0`
   (`:186-188`) holds for any literal. `VarkaCoverageDifferentialSuite` runs only the first row of
   each family through a session (`sampleRows`, `VarkaCoverageDifferentialSuite.scala:168-186`),
   none of which names a literal today, and skips its batch assertion when a predicate's answer is
   empty (`:124-131`). So an unheld literal is now caught nowhere and harms only a family whose
   first row has one. The fixtures are sql/core's (`VarkaCoverageDifferentialSuite.scala:395`),
   which `VarkaCoverageSuite` (catalyst) cannot read, so the row's "`VarkaCoverageSuite` checks
   each literal" crosses a module boundary.

### (c) A skip line tied to a seeded fuzz test

7. **The draws follow the table.** `VarkaSparkFuzzSuite`'s two compositions tests draw from the
   coverage rows (`draw(rows, seed, it)` at `VarkaSparkFuzzSuite.scala:161`, `drawRows` at `:221`;
   `rows` at `:64`), so a new row moves which compositions a seed gives.
8. **The line moved three times in three days.** Under `checkIntOverflow=false` a drawn overflow
   disagrees with the row engine by design. Row 338 moved the `fails` line from the 200-composition
   test to the warm-up handover test (`be047e59673`), row 485 moved it back (`959447c69df`), and
   row 507 removed it as stale (`b68f3de5459`); master has none. Each time the previous line failed
   the matrix: a `fails` line whose test passes is a stale entry (`VarkaMatrixRuns.java:430-441`).
9. **Two kinds, hard-coded in seven places.** `VarkaMatrixRuns.Kind` (`:273-298`), `expectedFailure`
   (`:353-358`), `around` (`:410-443`), the lint's skips rule (`dev/varka_lint.py:327-366`, the
   kind at `:361-362`), the report (`dev/varka_matrix_report.py:88-89`, `:144-148`) and the CI
   ledger's report line (`dev/varka_ci_ledger.py:526-532`), and `VarkaTestReach.skips`, which takes
   whether a `fails` line exists (`VarkaMatrixRuns.java:415`) to decide if a test runs at all.

### (d) The conformance cast check, cancelled as a whole

10. **One test for every cast.** `castRow` (`VarkaTypeConformance.java:251-271`) collects every
    form of the type's row of `casts.tsv` in three modes and hands them to `run` (`:429-457`),
    which checks the fused ones and then cancels the whole test when any form is owed
    (`assumeTrue`, `:456`). A type with one owed cast reads as skipped for every cast it passes:
    NTZ's casts to `DATE` ran and passed and the test reads as cancelled for the casts to `TIME`
    (`VARKA-338.md` 10.1 item 5).
11. **A test per form is not available to most of the suites.** `VarkaSuiteExtension.checkShape`
    rejects dynamic tests in an `ownJvm` class (`VarkaSuiteExtension.java:176-181`), because the
    child answers by test method, and eight of the twelve conformance suites are `ownJvm`. Plain
    `@Test` methods are what such a class can have.

### What differs from the row text

- **(b) is narrower than the row says** (item 6): only a session-run row can meet it, and the check
  cannot live in `VarkaCoverageSuite` without moving the fixtures.
- **(c)'s fix needs a third kind** (items 8 and 9): one `fails` line "naming the two tests as a
  whole" still goes stale when neither test fails.
- **(d) is a fixed set of plain tests, not a dynamic one** (item 11).

## 1. Goal and non-goal

Each of the four traps either cannot happen or fails at the defaults, in the pull request that
sets it, with a message that names the fix, instead of on the option matrix hours later. Not
this row: the same cancellation shape in the other conformance checks (choice, overflow), the
guard in sql/core's sessions, and the `VarkaIrFuzzSuite` skip lines, which are tied to a fixed
seed whose draws do not follow the coverage table.

## 2. Design

### 2.1 (a): the guard checks at the defaults what a lane configuration will refuse

At the defaults, in a JVM that is not a suite's own, `check` also fails a class whose bytes name
species of two vector classes of different element widths (an `IntVector` beside a
`LongVector`, or a narrow class beside the int one): the kernel that would add a second species
under a lane configuration. The message names the fix, `@VarkaSuite(ownJvm = true)` for a JUnit
class and `VarkaOwnJvm` for a ScalaTest one. The predicate is read from the same `sizesUsed`
map the guard has. A `PinsDefaults` test never runs under a configuration, so one that loads such
a kernel needs no annotation; the guard has no test context to tell, so step 1's census says
whether any exists, and the exemption is built only if one does.

The predicate is a claim about which classes conflict, so it is established before it is
enforced: step 1 runs the catalyst suites in the shared JVM with `-Dvarka.speciesGuard=report`
under each of the three lane configurations, and the classes the report lists must be the ones
the predicate selects at the defaults. The disagreement to expect is a kernel driven at the int
lane that widens into long lanes inside (a 64-bit overflow check, a magic-multiply division): it
names both classes, yet its long species is the size the long kernels establish, so it does not
conflict; only a long-driven kernel holding int values does. If the sets differ, the rule is the
report's set, and the predicate becomes "re-emit the kernel's IR under each lane configuration
and admit it to a scratch registry seeded with that configuration's species", which
`VarkaKernelChecks` can do, since it holds the roots and the options where it loads.

This is the row's "a lint requires the annotation", implemented at run time rather than over the
sources: no reading of a suite's source can tell which lanes its kernels hold, and its bytes at the
defaults can. The row's other option, "the harness forks for such a kernel", does not fit the
harness: a fork happens a suite at a time, before the first test (`VarkaOwnJvm`), and a kernel is
met inside a test.

### 2.2 (b): a session-run predicate row must select a row

`VarkaCoverageDifferentialSuite` fails a session-run predicate row whose answer is empty on every
fixture, naming the row and the cause ("at one row a batch the cache prunes every batch, and no
kernel runs"), beside the exemption at `:124-131`, which stays: that one is per fixture, where
an all-null fixture under a null-rejecting predicate is one pruned batch at the defaults too. A
predicate the cache cannot prune (`InSet`, a predicate on no literal) passes the same rule, since
a row it selects is a batch it cannot prune. The check runs at the defaults on the answers the
suite computes already.

### 2.3 (c): a kind for a seeded draw

A third kind beside `fails` and `declines`, `draws`: the test runs, a failure is cancelled with
the line's reason, a pass passes, and the line is never stale. It is legal only on a test that
draws from a seed, a set `VarkaMatrixRuns` names (the two `VarkaSparkFuzzSuite` tests and
`VarkaCoverageCompositionFuzzSuite`'s), which the lint enforces so that the stale rule keeps its
force everywhere else. The two by-design `checkIntOverflow=false` lines go back as `draws` lines
on both tests. The kind is a new value of the fourth field, not a sixth field, so the three
readers that split a line into five fields (`VarkaMatrixRuns.parseSkips`, `:310-323`; the lint,
`dev/varka_lint.py:342-344`; the report, `dev/varka_matrix_report.py:88`) still parse every line.
Each of the seven consumers in 0.9 learns the kind: the report counts a `draws` test as listed for
"fuses nothing" and never as stale.

### 2.4 (d): one test per cast target

`castRow` becomes one plain `@Test` per census type as a target, in `VarkaTypeConformance`, each
named for its target ("the cast to DATE, in LEGACY, ANSI and TRY") and running that pair's forms
through `run`, which is unchanged. A target with no pair from the type, its identity cast or one
`casts.tsv` lacks, is cancelled saying so. The thirteen targets are the registry's census types
(`CAST_TARGETS`, `:794-805`), so a new type is a new test with its line. An owed cast then cancels
its own test only. Plain tests rather than one test with a fuller cancellation message, because
a test is the unit row 508's test selection keys on: one cast's change then selects that cast's
test, and its verdict is its own.

## 3. Tests

Each fix with a test that fails without it:

1. **(a)** In `VarkaSpeciesGuardSuite`: a planted kernel of both lanes loaded through
   `VarkaKernelChecks.load` at the defaults, without the own-JVM property, fails naming the
   annotation; with the property it loads; a one-lane kernel loads either way. Then the gate at the
   defaults, to find any suite the predicate catches that the matrix has not.
2. **(b)** In `VarkaCoverageDifferentialSuite`, a planted family whose first row is `ntz = <a
   literal no fixture row holds>` fails the new check; with a held literal it passes.
3. **(c)** In `VarkaMatrixSuite`, through `around`'s seam: a `draws` entry whose body passes
   passes, one whose body fails is cancelled, and a `fails` entry whose body passes is still
   stale; in the lint's own tests, a `draws` line on a test outside the seeded set is refused.
   Then the matrix's `checkIntOverflow=false` configuration on the branch.
4. **(d)** Through the JUnit launcher, as `VarkaSuiteHarnessSuite.java:642-643` runs planted
   classes: a planted conformance subclass whose type has one fused and one owed cast reports the
   first test passed and the second cancelled, where today both read as one cancelled test.

## 4. Predictions

1. Step 1's report lists exactly the classes of the suites that carry `ownJvm` for this reason
   today, and none of a suite without it.
2. No session-run row fails (b)'s check on master; it guards the next family.
3. The conformance suites' test count rises by twelve per suite (one test becomes thirteen), and
   their time does not move: the same forms are compiled and run.

## 5. Order, files and size

1. (d) and (b): each about the size of its section here, a change of tens of lines with its test;
   for these two the plan and the change are nearly the same size.
2. (a): the report census first, then the predicate, its test and the gate.
3. (c): the kind across the seven consumers and its tests. **It lands with row 497 or before
   it**: 497's coverage rows move the seeded draws again, and only a `draws` line can hold the
   by-design `checkIntOverflow=false` failure wherever the draw puts it.

| file | what |
|---|---|
| `VarkaSpeciesGuard.java`, `VarkaSpeciesGuardSuite.scala` | (a) the check, its test |
| `VarkaCoverageDifferentialSuite.scala` | (b) the check, its planted test |
| `VarkaMatrixRuns.java`, `VarkaMatrixSuite.scala` | (c) the kind, the seeded set, tests |
| `dev/varka_lint.py`, `dev/varka_matrix_report.py`, `dev/varka_ci_ledger.py` | (c) the kind |
| `sql/varka/matrix/skips.tsv` | (c) the two `draws` lines |
| `VarkaTypeConformance.java`, `VarkaSuiteHarnessSuite.java` | (d) a test per target, its test |

**Size: medium**, as the row says: about two days, half of it (a)'s census and (c)'s consumers.

## 7. Out of this row, and the row text

- **Kernels in sql/core sessions.** Only the two catalyst helpers call the species guard (0.3), so
  a session's kernels are not checked; guarding them is not this row's.
- **(b)'s module.** The row text names `VarkaCoverageSuite` (catalyst) for the check, but the
  fixtures it must read are sql/core's, so the check is sql/core's (2.2). Moving the fixtures where
  catalyst can read them would be a row of its own.
- The choice and overflow checks' whole-test cancellation (the same shape as (d), met by no row
  yet), and a fixed-seed fuzzer's skip lines.

## 8. Questions for the owner

None. The choices sections 2.1 to 2.3 make where the row offers two readings, or none, are
design questions, recorded with their evidence, and reach the owner only if the lead finds one is
his.

## 9. Done when

Each of (a) to (d) has its fix and a test that fails without it, as section 3 lists: a two-lane
kernel in a shared-JVM suite fails at the defaults naming the annotation, a session-run predicate
row that selects no fixture row fails at the defaults, the two seeded fuzz tests carry `draws`
lines under `checkIntOverflow=false` that cannot go stale, and each cast of a conformance suite
passes or is cancelled on its own; the PR build passes and the option matrix is dispatched on the
branch.

## 10. Outcome

(Written when the work lands.)
