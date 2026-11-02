# VARKA-543: No floating-lane filter slower than the row path at two lanes on x86

## 0. What exists now (admission check)

From the plans worktree at `130280562f3` (master `5c660201b35` plus plan commits) and the branch
`varka-503-two-lane-decline`, read on 1 November 2026 with `git` and `gh api` GETs. Nothing was
dispatched and no JVM was run. `VARKA-503.md` is the plan on that branch, read with `git show`.

1. **The census already parts the two floating lanes at 128 bits.** `VARKA-341.md` 0 item 11,
   from `VARKA-376.md` 9: the `FLOAT` compares and `IS_NAN` are intrinsic and allocation-free at
   every width, since a 128-bit float species has four lanes; the `DOUBLE` ones are intrinsic at
   512 and 256 bits and the Vector API's Java fallback at 128 bits on x86, two lanes, where C2
   lowers no two-lane mask (`m7` row 374). `width_audit.json` records the 128-bit double compares
   as refusals, not failures.
2. **Nothing refuses a double filter there.** 503's `hostRefusal` refuses a condition root only
   when the plan's drive lane is `LONG` (`VarkaKernelPlan.java` on the branch, `plan.drive !=
   LaneType.LONG` returns empty). `emissionLane` drives a tree holding a `DOUBLE` node at
   `DOUBLE` (master, `VarkaKernelPlan.java:281-287`), so `x < y` over doubles fuses at two lanes
   on x86, every compare, mask join and mask-to-word call in the fallback. `VARKA-503.md` 9 left
   this to the owner, and the owner made it this row.
3. **A floating conjunct beside an integral one already declines**, on every host:
   `floatingConjunctRefusal` (`VarkaKernelPlan.java:329-338`) refuses a floating-lane conjunct in
   a filter that also reads an integral column, keeping the kernel for the integral conjuncts. So
   `x < y AND d < d2` fuses `d < d2` on the int lane and leaves `x < y` to the row filter. On a
   two-lane host the date kernel has four int lanes and is not 503's case. The row's "conjunction
   with a date conjunct" measures that split, not a mixed kernel.
4. **No fused floating measurement exists at 128 bits.** The floating baseline's 128-bit file
   (`sql/core/benchmarks/VarkaFloatingBaselineBenchmark-jdk25-runner-epyc7763-128bit-results.txt`,
   run 40401767379, 26 October 2026) predates row 341's fusion: its "varka on" arm is the row path
   in every section, and every ratio is 1.0X to 1.2X. Row 341's benchmark step is still open (its
   cell, "left: ... the benchmark").
5. **503's own measurement is on the laptop** under `-XX:UseAVX=2 -XX:MaxVectorSize=16`
   (`VARKA-503.md` 6, files under `m8/results/VARKA-503/` on the branch, not on master), taken on
   27 October 2026. Since then benchmark verdicts come from the EPYC 7763 runners.
6. **Projections that blend on a long mask won at two lanes** in 503's measurement (1.3X to 6.6X
   against the row path), and 503 left them fused. A double projection's blend may do the same.

### What differs from the row text

- `FLOAT` at 128 bits has four lanes and intrinsic compares (item 1), so the refusal is expected
  for `DOUBLE` only; the `FLOAT` sections are a control that the rule must leave fused.
- The date conjunct shape tests `floatingConjunctRefusal`'s split, which is the same at every
  width (item 3).
- The measurement runs on the EPYC 7763 at `MaxVectorSize=16` through `benchmark.yml`, not on the
  laptop, which the row text leaves open.

## 1. Goal and non-goal

**Goal.** On an x86 host at two long lanes, no fused `DOUBLE` or `FLOAT` filter is slower than
the row path: each shape is measured on the EPYC 7763 at 128 bits, and the ones that lose decline
with the two-lane reason by an extension of 503's refusal.

**Non-goal.** No change to projections unless one measures slower (then 503's rule's projection
exemption is the owner's call, question 2). No mask-free lowering (`m9/SCOPE.md` item 101). No
change at 256 or 512 bits, or on aarch64.

## 2. Design

### 2.1 The measurement first

`VarkaTwoLaneLongBenchmark` (503's, on the branch) gains a floating table, or a new
`VarkaTwoLaneFloatingBenchmark` beside it under the rule of one benchmark class per feature family
(question 1): over the floating baseline's fixture, with no nulls and with one row in ten null,

- filters: `x < y` and `x > 0.5` over `DOUBLE`, `isnan(x)` over `DOUBLE`, `f < g` and
  `isnan(f)` over `FLOAT`, `x < y AND d < d2` (the split of 0 item 3);
- projections: `CASE WHEN x < y THEN x ELSE y END`, `greatest(x, y)`, `x < y` as a value, over
  both types;
- the controls: `x + y` (no mask) and the row path.

Each Varka arm asks its plan, as 503's review made its benchmarks do (`VARKA-503.md` 10 item 13):
fused means a Varka node and no row `FilterExec`; a declined case writes a note line and keeps its
name. Run by `benchmark.yml` on the EPYC 7763 with `extra-java-options: -XX:MaxVectorSize=16`,
expected CPU pinned, on the branch before the refusal and again after it.

### 2.2 The refusal, extended where the measurement says

`hostRefusal` tests the drive lane's width instead of naming `LONG`: a condition root of a kernel
whose drive lane is 64 bits wide (`LONG` or `DOUBLE`) is refused when the session's
`declineTwoLaneConditions` is set. The host fact needs no change: a JVM's preferred double
species has as many lanes as its long one, so "x86 at two long lanes" is the same test. The
reason string names the lane (`the DOUBLE lane has two lanes on this x86 host ...`). If the
measurement shows a `DOUBLE` filter shape winning (the null-dense case may, as 503's did), the
rule still refuses it, as 503 gave up its own null-dense wins, unless the owner answers otherwise
(question 2).

The setting, its default, its pin off in `VarkaSharedSessions`, EXPLAIN and
`VarkaFusionReport.predicateLines` are 503's and unchanged.

### 2.3 The census

`VarkaWidthAuditSuite` regenerated on the laptop (`VARKA_AUDIT_REGEN=true`, then
`VARKA_COVERAGE_REGEN=true` for `docs/sql-varka.md`, in that order): the 128-bit column's `DOUBLE`
filter rows move from fused-with-fallback to declined with the reason; the `FLOAT` rows and the
256- and 512-bit columns do not move.

## 3. Tests

1. `VarkaTwoLaneConditionSuite`: a `DOUBLE` compare and `isnan` refused with the flag on, both
   fused with it off; a `FLOAT` compare admitted with the flag on.
2. `VarkaFilterExecSuite` (or a floating twin of `VarkaLongLaneSuite`'s case): `WHERE x < y` over
   doubles, refusal on, its three null patterns, against refusal off and Varka off; and `x < y AND
   d < d2` with the date conjunct fused.
3. `VarkaTwoLaneHost128Suite` (own JVM, `-XX:UseAVX=2 -XX:MaxVectorSize=16`): a double filter
   declines on the real host, a float filter fuses.
4. `VarkaWidthAuditSuite` against the regenerated census; `emitted_bytes.json` unchanged.

## 4. Predictions and costs, registered before the build

1. Before the refusal, the `DOUBLE` filters without nulls run at or below the row path at 128 bits,
   as 503's long filters did (0.5X to 0.7X on the laptop, `VARKA-503.md` 6).
2. The `FLOAT` filters beat the row path at 128 bits by 1.3x or more, their masks being intrinsic.
3. The `DOUBLE` projections beat the row path at 128 bits, as 503's long projections did, so none
   needs a refusal.
4. With nulls, the `DOUBLE` filters' ratio is higher than without (503 saw 1.1X to 1.7X for
   long), and may pass 1.0X.
5. After the refusal, every `DOUBLE` filter section times the row path and reads 1.0X within the
   1.3x rule; `emitted_bytes.json` and `coverage.json` do not move.

**Costs.** Two `benchmark.yml` runs of one class at one width, about the cost of 503's class on
the runner. Tests: four cases in suites that exist, seconds each; the host suite is one more own
JVM in the narrow step, already paid by 503's.

## 5. Order, files and size

After 503 merges.

1. The benchmark class and the before run on the EPYC (one pull request's first commit; the
   results committed).
2. The refusal's widening, the tests, the census regenerated, the after run.

| file | what |
|---|---|
| `.../codegen/varka/VarkaKernelPlan.java` | `hostRefusal` on a 64-bit drive lane; reason text |
| `sql/core/.../benchmark/VarkaTwoLaneFloatingBenchmark.java` | new (or a table of 503's class) |
| `sql/core/benchmarks/*-runner-epyc7763-128bit-*` | the before and after results |
| tests | 3's list |
| generated | `width_audit.json`, `docs/sql-varka.md` |

Size: **small**, one pull request, about one day plus two runner dispatches.

## 6. Out of this row

- Row 343's mask crossing between the kinds, which would make `x < y AND d < d2` one kernel and
  so a long- or double-driven one; that row then meets this rule.
- The mask-free lowering for two-lane masks (`m9/SCOPE.md` item 101) and run-time choice (item
  119).

## 7. Questions for the owner

1. **A new benchmark class, or a table of 503's.** *Recommended:* a new
   `VarkaTwoLaneFloatingBenchmark`, by the rule of one benchmark class per feature family; it reuses
   503's plan-asking helpers in `VarkaSqlBenchmarkBase`.
2. **A `DOUBLE` filter shape that wins with nulls.** (a) Refuse every `DOUBLE` condition root, as
   503 did for the long lane, giving up null-dense wins. (b) Refuse only the shapes that lose
   without nulls. *Recommended: (a)*: the rule is a plan-time rule that cannot see a batch's null
   density, and 503 made the same choice with the owner's word; item 119's run-time choice is
   where null density belongs.

## 8. Done when

The EPYC 7763 measurement at 128 bits is committed before and after; every `DOUBLE` and `FLOAT`
filter shape of 2.1 is either faster than the row path there or declined with the two-lane reason;
answers equal Spark's in the tests of 3; the census is regenerated with only the `DOUBLE` filter
rows of its 128-bit column moved; the PR Build and an option-matrix dispatch pass.

## 9. Open points for the lead

1. The row depends on 503, which is not in the table. 540 and 359a depend on it too.
2. Row 341's own benchmark step (its cell's "left") would measure the same filters at 256 bits;
   this row's class could serve both if 341's step is planned after it.

## 10. Outcome

(Written when the work lands.)
