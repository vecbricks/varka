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
