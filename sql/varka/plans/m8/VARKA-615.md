# VARKA-615: Let the width audit compile a coverage row on a host that refuses it

## 1. Where this came from

Milestone 8's row 615, added 14 November 2026 from `VARKA-607.md`'s correction of that day. Row
607 (#1529) added a test that drives the width audit's own entry point,
`VarkaWidthAuditShapes.coverageRows`, and asserts that the coverage table's `d18 IN` row reaches
it as an `InSet` and compiles to a `Shape`. The weekly sweeps run 43577422049 failed its
"AVX2 at 128 bits: catalyst 0/3" job on that test: on an x86 host whose 64-bit species have two
lanes or fewer, the audit declines the row's condition for the two-lane refusal (VARKA-503)
before its shape can matter. The correction made the test accept exactly that decline, and
recorded what it gave up: on such a host the test no longer checks that the audit compiles the
row, which would need `coverageRows` to take a `VarkaAdmission`, as `VarkaWarmupReach` does
with `NO_HOST_REFUSAL`.

## 2. The admission check, done

Read at origin/master `e5d70012bdc` (20 November 2026). The problem holds.

1. `coverageRows(coverageJson: File)` takes no admission. Its projection branch compiles under
   `hostAdmission`, and `predicateRow` does too (`explainAndCompilePredicate(..., hostAdmission)`);
   `hostAdmission` is `new VarkaAdmission(Set.of(), twoLaneMasksFallBack(VarkaHostIsa.host()))`,
   a private method, so no caller can ask for another.
2. The test, "the coverage table's d18 IN row reaches the audit as an InSet"
   (`VarkaWidthAuditSuite`), matches `Shape` or `Declined(_, TWO_LANE_CONDITION_REASON)`, the
   correction as committed by #1529.
3. `VarkaWarmupReach.coverageShapes` compiles a predicate row under
   `VarkaAdmission.NO_HOST_REFUSAL`, which is `new VarkaAdmission(Set.of(), false)`: the host's
   admission with the two-lane refusal off. On a host of four 64-bit lanes or more the two
   admissions are equal, which is why the laptop and the 256-bit runners never saw the decline.
4. The other callers of the audit's rows take `all(coverageJson)`: `VarkaWidthAuditProbe` (the
   forked probe that writes the census) and `VarkaMaskOfWordSuite`. Both must keep the host's
   admission, since the census records what this host refuses (`width_audit.json`).
5. No sweep has run since: `varka-sweeps.yml`'s last run is 43577422049 (14 November 2026).

## 3. The design

### 3.1 An admission parameter

`coverageRows(coverageJson: File, admission: VarkaAdmission)` and
`predicateRow(sql, resolved, admission)` take the admission; the one-argument
`coverageRows(coverageJson)` keeps its signature and passes `hostAdmission`, so `all`, the probe
and `VarkaMaskOfWordSuite` are unchanged and the committed census cannot move. `hostAdmission`
stays private.

The test asks twice:

* under `VarkaAdmission.NO_HOST_REFUSAL`, the row is a `Shape` on every host, which is the
  claim #1529 meant: the audit's path folds the `In` and compiles it;
* under the host's admission (the one-argument form), the existing match stays: a `Shape`, or on
  a two-lane host exactly the two-lane decline, so the host's answer is still pinned.

Alternatives considered: (a) make `hostAdmission` package-visible and let the test build
`NO_HOST_REFUSAL` itself through a second entry point; rejected, two ways in to one reader.
(b) Run the test's first half only where the host refuses; rejected, the point is one assertion
that holds on every host. Neither is a design variant worth building both of: the change is a
parameter.

This is test code only (`sql/catalyst/src/test`); `VARKA-607.md`'s correction called it a
production-API change, which it is not, and section 9 records that correction.

### 3.2 What is deliberately unchanged

* `width_audit.json` and every census the probe writes: the probe calls `all`, which keeps the
  host's admission.
* `constructions`: not admission-dependent.
* The compiler and `VarkaAdmission`.

### 3.3 Registered op counts

None.

## 4. Files

| file | what |
|---|---|
| `VarkaWidthAuditShapes.scala` | the admission parameter on `coverageRows` and `predicateRow` |
| `VarkaWidthAuditSuite.scala` | the d18 `IN` test, under `NO_HOST_REFUSAL` and the host's |

## 5. Tests, and what each is for

1. "the coverage table's d18 IN row reaches the audit as an InSet", extended: a `Shape` under
   `NO_HOST_REFUSAL`, and the host's answer as now. Run on the laptop at the defaults and under
   `-XX:UseAVX=2 -XX:MaxVectorSize=16`, the sweep's flags, where the host's half must take the
   decline branch and the new half a `Shape`; that second run is the check that the test now
   asserts compilation on a two-lane host.
2. The audit's own tests unchanged; `git status` after both runs shows no committed file
   changed, `width_audit.json` among them.

## 6. The measurement

None beyond the two runs of test 1, recorded in section 11 with their commands. The flags reach
the test JVM and the suite's forked probes through `JDK_JAVA_OPTIONS`, as `sql/varka/AGENTS.md`
says for the width audit (a `--jvm-arg` does not reach the probe JVM):

    SPARK_LOCAL_IP=127.0.0.1 JDK_JAVA_OPTIONS='-XX:UseAVX=2 -XX:MaxVectorSize=16' \
      build/sbt 'catalyst/testOnly *VarkaWidthAuditSuite -- -z "d18 IN row"'

and the defaults' run is the same command without `JDK_JAVA_OPTIONS`. The suite takes
474 s in the matrix (`suite-times.tsv`), almost all in its five forked probes' `beforeAll`, so
the runs use `-z` on the one test, which still pays that `beforeAll`.

### 6.1 Predictions, registered before the run

1. Under `-XX:MaxVectorSize=16` with `UseAVX=2`, the host's half declines with the two-lane
   reason and the `NO_HOST_REFUSAL` half is a `Shape`; at the defaults both are `Shape`s.
2. The test's added time is one more compile of one row, under 1 s, with no ratio above 1
   applied: the calibration table's compile-only tests ran far under their estimates (VARKA-328
   at 0.03, VARKA-376's census cells at 0.04, a loop of one operator compiling in well under a
   second), and this test reuses the suite's session. The `-z` run is the suite's `beforeAll`,
   several minutes, unchanged.

## 7. Risks

1. A caller that should keep the host's terms is moved to `NO_HOST_REFUSAL`: only the test
   passes the new argument, and `git grep "coverageRows("` in the pull request shows it.
2. `NO_HOST_REFUSAL` declines the row for another reason on some host: the test then fails on
   that host with the reason, which is a real finding about the row.

## 8. Sequencing

One pull request of about 40 counted lines: the parameter, the test, this file's outcome.
Independent of every other open row.

## 9. Corrections to the record

`VARKA-607.md`'s correction of 14 November 2026 calls the parameter "a production-API change
this CI fix does not make". `VarkaWidthAuditShapes` is under `sql/catalyst/src/test`; the change
is to test code. Recorded here rather than edited there, per "Plans are records".

## 10. Done when

`coverageRows` takes a `VarkaAdmission` the way `VarkaWarmupReach` does with `NO_HOST_REFUSAL`,
row 607's test asserts a `Shape` under it on every host (shown on the laptop at the defaults and
under the sweep's AVX2-at-128 flags), the host's answer stays asserted beside it, and the audit's
committed files are unchanged.

## 11. Outcome

*Filled in when the work lands: the runs with their commands, section 6.1's predictions scored
one by one, and what moved that this plan did not list.*
