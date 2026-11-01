# VARKA-503: Decline a long-lane mask at two lanes on x86

This plan was written on 1 November 2026, after the work. The row was built on 27 and 28 October
2026 on branch `varka-503-two-lane-decline` in two commits (98af5721266, 3f1b4ffd98f) before any
plan file existed, and the branch sat unpushed until the owner decided on 1 November 2026 to add
row 503 to the table and merge it ahead of row 359a, whose plan (`VARKA-359.md` 0 item 11) cites
its rule. Sections 1 to 8 are therefore a reconstruction from the two commit messages, the row's
cell as the branch wrote it and the committed results, not a plan registered before the code. No
prediction was written down before the measurement, so none is scored here; section 6.1 says so
rather than inventing one.

## 1. Where this came from

Row 27's 128-bit baseline on the EPYC 7763 runner (branch `varka-27-baseline`, now
`sql/core/benchmarks/VarkaBooleanOutputBenchmark-jdk25-runner-epyc7763-128bit-results.txt`) put
the fused filters `(d < d2) AND (l < l2)` and `l < l2` at or below Spark's row path at 128 bits and
well above it at 256. The cause was already recorded: at two long lanes x86's C2 lowers no vector
mask (`x86.ad` refuses `VectorLoadMask`, `VectorStoreMask` and `VectorMaskCast` at `vlen == 2`,
`m7/VARKA-374.md` 2), so every long-lane compare, mask join, blend and mask-to-word call runs the
Vector API's Java fallback and allocates (`m9/SCOPE.md` item 101). The owner wants no fused shape
slower than the row path, and asked on 27 October 2026 for the long-lane mask to be declined at
two lanes on x86.

## 2. The admission check, done

The check was the before side of the measurement in section 6: whether every long kernel that
makes a mask loses at two lanes, or only some. It decided the rule's scope. Under
`-XX:UseAVX=2 -XX:MaxVectorSize=16` on the laptop (the runner's AVX2 at 128 bits, emulated), the
long filters lost on batches without nulls and every projection that makes a long mask still won
(section 6). So "declines every kernel with a long-lane mask", the row's title and option 1 of
item 101 as written, would have given up measured wins; the check narrowed the rule to a filter's
condition.

## 3. The design

### 3.1 The refusal, a session setting carried with the plan

`VarkaKernelPlan.admit(roots, options, twoLaneMasksFallBack)` returns the plan's own refusal, then,
where the flag is set, refuses a condition root - a filter's conjunct or a selection output - of a
kernel driven at the long lane (`Kind.TWO_LANE_CONDITION`, `TWO_LANE_CONDITION_REASON` in
EXPLAIN). The predicate compiler's admission asks it at both of its plan checks;
`VarkaKernelPlan.of`, which the emitter and a compiled projection read, never refuses for the
host. A refused conjunct
leaves the rest of the filter to fuse: in `(d < d2) AND (l < l2)` the date conjunct stays an int
kernel and the long conjunct is the row filter's.

The host fact is `VarkaKernelPlan.HOST_TWO_LANE_MASKS_FALL_BACK`: `HOST_USE_AVX` reads a level
(the JVM is x86) and `LongVector.SPECIES_PREFERRED` has two lanes (`twoLaneMasksFallBack`). It is
not `convertsFallBack()` (`UseAVX < 3`), because `x86.ad`'s refusal sits outside any AVX branch and
VARKA-374 measured the same loss at `UseAVX=3` capped to 16 bytes. aarch64 lowers two-lane masks
and never declines; an emit option's `lanesOverride` never declines, so the option matrix's
two-lane configurations on wider hosts stay answer checks.

The compiler never reads that static itself. It is the default of an internal session setting,
`spark.sql.codegen.varka.declineTwoLaneConditions`, resolved once into `VarkaSessionEmit` beside the
families off (whose model it follows: it changes which conjuncts are admitted, never a kernel's
bytes) and carried with the plan, so the compile at planning, EXPLAIN and the filter evaluator's
compile on an executor decline alike. The compiler overloads without the flag answer as a host
that refuses nothing, so `coverage.json`, `emitted_bytes.json`, the golden corpus and every catalyst
suite are the same on every machine. A first build that read the host fact inside the compiler
failed 256 tests of the gate's narrow step, the committed oracles among them, which is why it
moved to the session.

### 3.2 What is deliberately unchanged

* Projections, blends included: they won at two lanes (section 6) and still fuse. Item 101's
  mask-free lowering, and the cheaper repair the measurement suggests (the masked body for every
  batch of a long filter at two lanes), stay in `m9/SCOPE.md` item 101.
* The one-lane case (`-XX:MaxVectorSize=8`): there C2 vectorizes no long operation at all and the
  int species' two-lane masks fall back too, a whole-engine loss on a host only that flag makes;
  the gate stays at exactly two long lanes.
* A filter kernel of null tests alone, which `i < l` leaves behind once its long conjunct declines
  (section 6): no planning rule declines it; `m9/SCOPE.md` item 119 records it as the case a
  run-time choice would catch.

### 3.3 Registered op counts

None: no emitted byte changes (`VarkaEmittedBytesSuite` unchanged at both widths); the refusal
only decides which conjuncts are admitted.

## 4. Files

| file | what |
|---|---|
| `VarkaKernelPlan.java` | `admit`, `hostRefusal`, `Kind.TWO_LANE_CONDITION`, the host fact |
| `VarkaExpressionCompiler.java` | the predicate overloads that take the flag; both admission sites ask `admit` |
| `SQLConf.scala` | `spark.sql.codegen.varka.declineTwoLaneConditions`, internal, optional, `NOT_APPLICABLE` binding policy |
| `VarkaSessionEmit.java` | the fourth component, `declineTwoLaneConditions`, resolved from the conf or the host |
| `VarkaEvaluatorBase.java`, `VarkaFilterEvaluator.java`, `VarkaFusionReport.java`, `VarkaColumnarRule.scala`, `VarkaFilterExec.scala` | carry the flag from the plan to the compile and to EXPLAIN |
| `VarkaTwoLaneLongBenchmark.java` | new; the before and after under `results/VARKA-503/` |
| `VarkaLongLaneThroughputBenchmark.java` | expects its long filters declined on such a host; its 128-bit companion regenerated |
| `VarkaBooleanOutputBenchmark.java` | its filter controls expect the plan the product gives them on such a host |
| `VarkaWidthAuditShapes.scala`, `VarkaWidthAuditSuite.scala` | the audit compiles predicates on the host's terms; `width_audit.json`'s 128-bit column and `docs/sql-varka.md`'s with it |
| `VarkaSharedSessions.scala` | sets the refusal false, so the sql/core suites keep checking two-lane kernels' answers |
| `dev/varka_matrix.sh` | reads a suite's `jvmFlags` from its whole annotation, not its first line |
| `m9/SCOPE.md` | item 101's note, items 118 and 119 |

## 5. Tests, and what each is for

* `VarkaTwoLaneConditionSuite` (catalyst, JUnit): the refusal and its reason with the flag on, both
  conjuncts fused with it off, on any host; `lanesOverride` never triggers it.
* `VarkaFilterExecSuite`, "a long conjunct stays with the row filter where x86 has two long
  lanes": the EXPLAIN lines both ways.
* `VarkaTwoLaneHost128Suite`, in a JVM of its own under `-XX:UseAVX=2 -XX:MaxVectorSize=16`: the
  compiler's host fact and a session's default are true on x86, false elsewhere.
  `VarkaTwoLaneHost256Suite`, under `-XX:UseAVX=2`: both false at four long lanes. These replace
  two self-comparisons of the first commit, which checked the fact only against the expression
  that computes it.
* `VarkaLongLaneSuite`: `WHERE d < d2 AND l < l2` with the refusal on over its three null
  patterns, the long conjunct a row filter above the fused date one, against the refusal off and
  Varka off.
* `VarkaBooleanOutputQueriesSuite`: row 27's filter controls checked against both expectations by
  setting the refusal each way.
* `VarkaWidthAuditSuite`: the committed census, the 128-bit column's long filter rows declined
  with the reason and `b AND l < l2` with its long conjunct gone; the 256- and 512-bit columns
  unchanged. The branch's regeneration on 27 October 2026 declined 48 rows; regenerated again on
  the merged tree on 1 November 2026, by the same command on the same laptop, it declines 65, the
  new ones master's long-lane rows since (the `DECIMAL` rows of row 347, `TIMESTAMP_NTZ`,
  `TIME` and day-time interval comparisons). `coverage.json` did not move, and
  `docs/sql-varka.md`'s 128-bit column was regenerated after the census, as the order in
  `skills/emitter-and-ir.md` requires.

## 6. The measurement

`VarkaTwoLaneLongBenchmark`, one run per side, on the laptop with the machine shared with other
agents' JVMs at a load of about 2, under `-XX:UseAVX=2 -XX:MaxVectorSize=16` pinned to cores 0-3
and 12-15; the command is in each file's header. Before: master at fa51ebe8706 plus the class,
`results/VARKA-503/two-lane-before.txt`. After: the branch, `results/VARKA-503/two-lane-after.txt`.
Ratios are `varka on` against `spark row path (varka off)`; those under 1.3 are directions, not
numbers, by the house rule.

| shape | no nulls, before | after | one in ten null, before | after |
|---|---|---|---|---|
| `l < l2` filter | 0.6X | 1.0X (row path) | 1.1X | 1.0X (row path) |
| `l > 500000000000` filter | 0.7X | 1.0X (row path) | 1.1X | 1.0X (row path) |
| `i < l` filter | 0.7X | 0.8X | 1.7X | 1.0X |
| `(d < d2) AND (l < l2)` filter | 0.5X | 1.3X | 1.4X | 1.8X |
| `l + l2`, ANSI and legacy (control, never fuses) | 1.0X | 1.0X | 1.0X | 1.0X |
| `l ^ l2` (no mask in the loop) | 4.5X | 5.6X | 7.6X | 7.3X |
| `CASE WHEN l < l2 THEN l ELSE l2 END` | 1.6X | 2.0X | 1.5X | 1.6X |
| `greatest(l, l2)` | 5.6X | 6.6X | 1.9X | 1.8X |
| `if(l IS NULL, l2, l)` | 1.3X | 1.5X | 2.3X | 2.3X |

The projections' code did not change between the two runs, so their moves (`greatest` 5.6X to
6.6X among them) are the shared machine's, and they bound how far either file's single run can be
read. `l + l2` does not fuse under either arithmetic mode (bigint addition has no compiler arm,
still true on master on 1 November 2026), so both its arms are the row path.

`VarkaLongLaneThroughputBenchmark`'s 128-bit companion
(`sql/core/benchmarks/VarkaLongLaneThroughputBenchmark-jdk25-128bit-results.txt`) was regenerated
with `--narrow-only --force` at the load its header records: its long filter rows now time the
row path; its int-lane rows, whose code did not change, moved with the machine and are not a
change. The wide file and its provenance are as they were. Row 27's committed 128-bit results
were taken before the refusal and are not regenerated.

### 6.1 Predictions, registered before the run

None was registered: the work predates this file. The row's own expectation, in its title, was
that every long-lane mask loses at two lanes; the before run showed it does not (section 2), and
the rule was narrowed to filter conditions.

## 7. Risks

1. A plan and a task that decline differently: the flag travels with the plan in
   `VarkaSessionEmit`, and `VarkaFilterExecSuite` and `VarkaLongLaneSuite` exercise both sides.
2. Oracles that depend on the machine: the compiler's flagless overloads refuse nothing, so the
   bytes oracle, the coverage table and the golden corpus are host-independent; only the width
   audit, a per-host census by design, reads the host.
3. The sql/core suites testing the row engine against itself on the narrow step:
   `VarkaSharedSessions` sets the refusal false.
4. A flagless child JVM for the 128-bit host suite: `dev/varka_matrix.sh` read a wrapped
   `jvmFlags` annotation's first line only; it now reads the annotation whole.

## 8. Sequencing

1. 98af5721266: the refusal, the setting, the benchmark and its before and after, the width audit
   and docs regenerated, the throughput benchmark's expectation and 128-bit companion, the row
   and the scope items.
2. 3f1b4ffd98f: the host suites in their own JVMs, the matrix script's annotation reader, the
   `VarkaLongLaneSuite` test, row 27's benchmark controls and their suite.
3. 1 November 2026: master merged in, this file, the row in the table.

## 9. Outcome

Built as section 3 describes. On an x86 host at two long lanes a filter's long-lane condition is
the row filter's, with its reason in EXPLAIN, and the filter's other conjuncts still fuse; every
long projection still fuses. The measured losses on batches without nulls (0.5X to 0.7X) are gone
for `l < l2`, `l > 500000000000` and `(d < d2) AND (l < l2)`; `i < l` reads 0.8X after, its kernel
the optimizer's `isnotnull(i)` alone, from one run under 1.3 (item 119). The price, chosen by the
owner, is the long filters' win on batches with nulls (1.1X to 1.7X before), which item 101's note
records the cheaper repair for.

Where the row's plan turned out wrong: the title's "decline a long-lane mask" was broader than the
measurement supports; the rule declines a condition, not a mask. The first build's host read inside
the compiler broke the committed oracles and moved to the session (3.1).

The merge with master on 1 November 2026 changed what the rule meets: a `DECIMAL` of precision up
to 18 is now read on the long lane (row 347), so its filter conditions meet this refusal by
construction, as the row anticipated; a floating conjunct beside an integral one is refused first
by master's `floatingConjunctRefusal`, and a filter over floating columns alone now fuses on the
`DOUBLE` lane, whose 128-bit species also has two lanes. Nothing here measures or refuses the
latter; it is an open point for the owner, not part of this row.

Left before the row is done: the gate's narrow step and the PR build on the merged tree, which run
on CI when the branch is pushed. The before and after files are not rerun: the merge moves no
kernel byte the benchmark times (`VarkaEmittedBytesSuite` unchanged), so only the row path's side
could have moved with master, which a single run's directions do not resolve anyway.
