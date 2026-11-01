# VARKA-477: The Varka JUnit extension holds parameterized, repeated and own-JVM generated tests

## 0. What exists now (admission check)

From master at `5c660201b35`, read on 1 November 2026 with `git`. Nothing was run.

1. **Dynamic tests are held already, in a shared JVM.** #1101 (row 509, 29 October 2026) added
   `VarkaSuiteExtension.interceptDynamicTest`. A `@TestFactory`'s dynamic test runs under
   `runTest`, which applies the matrix, the skip list and the watchdog, and is named by the
   display name its factory gives. A name used twice in a class fails the test. Three sql suites
   use it: `VarkaCoverageHarnessSuite`, `VarkaDifferentialHarnessSuite` and
   `VarkaTimeHarnessSuite`.
2. **What is still refused** (`VarkaSuiteExtension.checkShape`):
   - any `@TestTemplate`, which covers `@ParameterizedTest` and `@RepeatedTest`;
   - a `@TestFactory` in an `ownJvm` class, because the child JVM's results are answered by test
     name and a factory's names are known only as it runs.
3. **The row's example is an `ownJvm` class.** `VarkaEmitterNarrowArithSuite` is
   `@VarkaSuite(ownJvm = true)` and spells out its SMALLINT sweeps as 36 `@Test` methods, one per
   mode, operator and arm: 18 slices (`sliceSweep`) and 18 opt-in full sweeps (`fullSweep`). So
   the row needs the child-JVM path, not only the shared one.
4. **How names travel.**
   - The skip list and the fused-batch report key a test by its suite's simple name and its
     display name (`VarkaMatrixRuns.around`, `dev/varka_matrix_report.py`).
   - In the child JVM, `VarkaSuiteRunner` runs the class through the JUnit launcher with
     `VarkaSuiteListener`. `VarkaChildJvm.Results.of(name)` answers the parent's test by that name.
   - `VarkaSuiteRunner`'s name filter (`:166-175`) runs at discovery, on descriptors whose source
     is a `MethodSource`. A template's invocations do not exist at discovery, so a `-z` filter
     sees only the template's container (**[I]**: from JUnit's documented model, not read in
     its source).
5. **The library is there.** The root `pom.xml`'s `<dependencies>` (`:425-476`) gives every
   module `junit-jupiter`, the aggregate that includes `junit-jupiter-params`.

### What differs from the row text

- The `@TestFactory` half is done for a shared JVM (item 1). What is left is the templates, and
  every generated test in an `ownJvm` class.

## 1. Goal and non-goal

**Goal.** A `@ParameterizedTest`'s and a `@RepeatedTest`'s invocations run under the harness
as a plain `@Test` does: the matrix's tags, the skip list, the watchdog, the fused-batch report.
Each has a name the report lists and the skip list can name alone. The same holds for these and
for `@TestFactory` tests in an `ownJvm` class, answered from the child JVM by name.

**Non-goal.** `@Nested` classes stay refused. `VarkaEmitterNarrowArithSuite` is not rewritten in
this row beyond what proves the mechanism (question 2).

## 2. Design

### 2.1 Templates under the harness

`VarkaSuiteExtension` implements `interceptTestTemplateMethod`, built like
`interceptDynamicTest`:

- it finds the `@VarkaSuite` class;
- it builds the guard and calls `runTest` with the invocation's skip-list key (2.2);
- it keeps `DYNAMIC_NAMES`' rule that a key is used once per class.

`checkShape` stops refusing `@TestTemplate`. It refuses instead a template whose name cannot
make a stable key (2.2).

### 2.2 The key of an invocation

JUnit's display name for an invocation is the rendered `name` attribute alone, `[1] ...` by
default, without the method's display name (**[I]**: JUnit 5's documented default
`"[{index}] {argumentsWithNames}"`). The key is the method's display name, a space, and the
rendered invocation name: `SMALLINT sweep [LEGACY, +, widened]`. A key built from `{index}`
alone renumbers every later case when an argument is added. So `checkShape` requires a
`@ParameterizedTest` in a `@VarkaSuite` class to give a `name` that renders at least one
argument and does not use `{index}` (question 1). A `@RepeatedTest`'s invocations differ only by
index, so there the index is the key: `<display name> repetition <n>`.

### 2.3 In an own JVM

The child already runs the class with this extension under the same configuration. Its listener
must report each invocation under the key of 2.2 rather than the bare display name. The listener
reads the key from a `ReportEntry` that `runTest` publishes, or from the parent descriptor's
display name joined to the invocation's (**[I]**: which of the two `VarkaSuiteListener` can read
was not checked). The parent then answers each invocation from `Results.of(key)`, as for a plain
test. `checkShape`'s refusal of factories in `ownJvm` classes is dropped with it.

A filter by name (`-z`) that names an invocation keeps the template and lets
`interceptTestTemplateMethod` skip the invocations that do not match. They are skipped, not
cancelled, through `invocation.skip()` with no exception, so the matrix report's cancel counts do
not grow (**[I]**: how JUnit reports an intercepted invocation that is skipped was not checked;
if it reports it as passed, the filter goes through a `PostDiscoveryFilter`-like check on the
provider's arguments instead). That is what the matrix's reruns of one test need (item 4).

In an `ownJvm` class the parent JVM still runs the argument provider, or the factory's body, to
learn the invocations it answers from the child. So `checkShape` documents, and the skill says,
that a provider in a Varka suite must be cheap: a static list, never kernels emitted to name
tests. `VarkaEmitterNarrowArithSuite`'s (mode, operator, arm) list is such a list.

### 2.4 The proving suite

`VarkaSuiteHarnessSuite` gains fixture classes, as it has for the refused shapes:

- a parameterized class of three invocations, one of them listed in a test skip list as `fails`;
- a repeated class;
- an `ownJvm` parameterized class.

Its tests check that the report lines carry the three keys, and that the listed invocation alone
is cancelled while it fails and fails as stale once it passes. They also check that the `ownJvm`
class's invocations are answered from the child, and that a template using `{index}` alone is
refused by name.

## 3. Tests and proofs

1. 2.4's tests. They emit no kernel, so no vector width applies.
2. One real suite converted: `VarkaEmitterNarrowArithSuite`'s 18 slice sweeps as one
   `@ParameterizedTest` over a method source of (mode, operator, arm), and its 18 full sweeps as
   another (question 2). The converted suite under the dispatched option matrix, and in its own
   JVM in the PR Build: the report lists 36 keys, the same names as today's display names, so
   `skips.tsv` and the ledger read them unchanged.
3. A planted case: a skip line naming one invocation, with that invocation's body made to pass,
   must fail as stale (row 504's convention, plan on branch `varka-m8-triage`).

## 4. Predictions, registered before the build

- P1: the converted suite's keys equal today's 36 display names when the `name` template is
  written to render them so, so the ledger's history of those tests continues.
- P2: the PR Build's time for the suite does not move, since the work per case is the same.

## 5. Order, files and size

| File | Change |
|---|---|
| `VarkaSuiteExtension.java` | `interceptTestTemplateMethod`; the key; `checkShape` |
| `VarkaSuiteListener.java`, `VarkaChildJvm.java` | report and answer by key |
| `VarkaSuiteRunner.java` | the filter of 2.3 |
| `VarkaSuiteHarnessSuite.java` | 2.4 |
| `VarkaEmitterNarrowArithSuite.java` | the sweeps as two templates, if question 2 says so |
| `sql/varka/skills/testing-and-debugging.md` | how a Varka suite writes a parameterized test |

Order: the extension and its tests in a shared JVM; the child path; the converted suite.

Size: small, as the row says.

## 6. Questions for the owner

1. **The key rule.** Should a `@ParameterizedTest` in a Varka suite be required to render its
   arguments in its `name` (this plan), or may it keep JUnit's indexed default? Recommended:
   require it. An index-only key moves every later case's skip line when a case is inserted.
2. **Converting `VarkaEmitterNarrowArithSuite`.** In this row, as the proof the done-when asks
   for, or left as it is with 2.4's fixtures as the proof? Recommended: convert it here. The
   done-when names a parameterized suite that runs under the matrix and in its own JVM, and only
   a real one shows the child's names reach the report.

## 7. Done when

- a parameterized suite runs under the matrix and in its own JVM, with keys the report lists;
- a skip line naming one invocation skips that invocation alone, and fails as stale once it
  passes;
- a repeated test and an `ownJvm` factory run under the harness too.

## 8. Outcome
