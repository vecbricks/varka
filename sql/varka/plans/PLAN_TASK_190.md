# Task 190: the op cap gives way to the byte budget

## 1. Where this came from

Task 171's admission check, 24 September 2026. The size ladder is meant to show
vanilla Spark's step against Varka's line as a projection widens, and the widths
where vanilla steps are widths Varka does not fuse. Measured on an Arrow-cached
date column, for `greatest(add_months(d, k), date_add(d, k), last_day(d))` as
entry `k`: vanilla's whole-stage method crosses the 8000-byte `HugeMethodLimit`
between 48 and 64 entries and stays under `spark.sql.codegen.maxFields` (100)
until 100, while Varka fuses 15 entries at every width - the rest run per row -
because `MAX_FUSED_NODES` admits 64 distinct ops per kernel and each entry is
four. A `CASE` family fuses 21 and two-op families 32. The owner chose, on the
same day, to replace the op cap with the byte budget as a task of its own
before 171 ("Let's do option A as a new task"), and to explore several kernels
per projection beside it ("B. Several kernels per projection. I would explore
this idea too.").

`MAX_FUSED_NODES`' own javadoc calls it "a policy bound far past any real
projection". Task 87 made the policy unnecessary: every loop and epilogue method
is now measured in bytes, regrouped until it fits, and a shape that cannot fit
is declined with a reason - at plan time since task 169. The cap was a stand-in
for size, and size is now measured.

## 2. The admission check, done

With the cap raised in a scratch worktree and the byte budget set out of reach,
so that nothing regroups or declines, the greatest family above emits as
follows (bytes; `dev/varka_emit.sh` reproduces each row once step 1 lands):

| entries | groups | largest loop or epilogue method | `runDense` | `runMasked` | constant pool |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 16 | 4 | 4409 | 810 | 960 | 232 |
| 64 | 16 | 4409 | 3114 | 3552 | 376 |
| 100 | 25 | 4448 | 5174 | 5828 | 484 |
| 150 | 38 | 4481 | 8110 | 9086 | 640 |
| 200 | 50 | 4481 | 11136 | 12412 | 784 |
| 400 | 100 | 4481 | 23336 | 25812 | 1384 |

Three limits appear once the cap is gone, and only three:

1. **The driver.** Every group method stays under 4500 bytes at every width -
   task 87's bound holds - but each driver sets up every output and calls every
   group, and grows by about 52 bytes an output. It crosses 8000 between 100
   and 150 entries of this family. Task 169 already declines a driver over the
   budget at plan time, demoting the last-admitted entries, so this limit is
   safe today; it is a ceiling on how wide one kernel can be, not a failure.
2. **The debug attribute.** At 800 entries the class cannot be built at all:
   `VarkaDebugInfo` carries the rendered IR and line map as constant-pool UTF-8
   entries, and the class-file format caps one at 65535 bytes ("string too
   long"). It is a metadata attribute the JVM ignores, so the answer is to
   bound what it carries, not to decline the kernel.
3. **Emission time grows faster than the outputs**: over the same run, four
   times the entries from 100 to 400 took several times more than four times as
   long to emit. It is paid once per shape per JVM (task 169), and step 1 prices
   it with committed cases in `VarkaEmissionBenchmark` rather than this
   scratch timing.

The constant pool is not a limit at any width measured, and the parameter slots
are the fixed signature. What the check would have rejected: raising the cap to
a larger number, which keeps a proxy the byte budget has already replaced and
would leave the driver ceiling in place unexplained.

## 3. The design

### 3.1 Step 1: bytes decide, and the driver is the ceiling

`MAX_FUSED_NODES` stops being an admission cap. The compiler's `fitsBudgets` and
the emitter's analysis keep `MAX_CHAIN_DEPTH` and `MAX_INPUTS`, which bound the
tree and the column bitset rather than size, and drop the op count; the byte
budget, regroup and plan-time decline of tasks 87 and 169 are what bound a
kernel. With the budget off (`methodByteBudget` 0, the reference form) the old
cap still applies, since nothing else bounds that form. `VarkaDebugInfo`
truncates what it carries to fit its attribute, marking the cut. The
widest-shape case of the parity benchmark and the emission benchmark gain rungs
at 100, 200 and 400 entries.

On the ladder's range - up to 100 entries, where vanilla keeps whole-stage
codegen - this fuses every entry of the greatest family in one kernel, which
is what task 171 needs.

### 3.2 Step 2: past the driver ceiling, two designs, measured

Above about 150 entries one kernel's driver is over the budget. Two ways past
it, to be built behind options and measured against each other, as the owner
asked for B to be explored beside A:

* **A': the driver splits.** One class, one kernel; the driver's per-output work
  (zeroing each validity bitmap, the bitmap pass) and its calls are partitioned
  into sub-driver methods by the same byte measure as the groups. Cross-output
  sharing is unchanged; the class grows, and emission time with it.
* **B: several kernels per projection.** The compiler partitions the admitted
  entries into kernels, each within every limit, and the evaluator runs them in
  turn over the batch, each writing its own outputs. Each kernel stays small
  and emits fast, and a kernel's failure costs only its entries; the price is
  that every kernel reads the input columns again and a prefix shared across
  kernels is computed once per kernel. The evaluator's runner, the status
  union, the input bounds and the derived inputs are per kernel today and
  become per projection.

Measured on the greatest family at 200 and 400 entries, both forms, at both
widths: throughput per entry, emission time, class size. The measurement decides
whether B ships, A' ships, or both - B is also the shape a projection wider than
`MAX_INPUTS` columns would need, which A' cannot help.

### 3.3 What is deliberately unchanged

* `MAX_CHAIN_DEPTH` and `MAX_INPUTS`: they bound the tree and the column set.
* `GROUP_BUDGET` and `FUSED_CEILING`: they group by weight for C2's sake.
* The ladder itself is task 171's; this task makes the widths it needs fusable.

### 3.4 Registered op counts

None: no lowering changes. Under the defaults, `emitted_bytes.json` moves only
where a debug attribute is truncated, which no oracle shape reaches.

## 4. Files

| file | what |
|---|---|
| `VarkaLoopEmitter.java`, `Analysis.java` | the op cap applies only with the budget off |
| `VarkaDebugInfo.java` | bounded payload |
| `VarkaExpressionCompiler.scala` | `fitsBudgets` without the op count under the budget |
| `VarkaEmitterContractSuite`, `VarkaExpressionCompilerSuite`, `VarkaEmitterBudgetSuite` | section 5 |
| `VarkaEmitterParityBenchmark`, `VarkaEmissionBenchmark` | wider rungs |
| step 2: the compiler's partition, the evaluator's runners | B, behind an option |
| step 2: `VarkaBodyEmitter` | A', behind an option |
| `PLAN_MILESTONE_6.md` | row 190 |

## 5. Tests, and what each is for

* **A projection of a hundred greatest entries fuses every entry** under the
  default, and answers as the reference evaluator on both bodies.
* **Past the driver ceiling, the compiler demotes a suffix** with the driver's
  reason (task 169's path), at the production budget, and every method of the
  kernel that remains fits.
* **Under budget 0 the op cap still rejects** a shape past 64 ops - the
  contract suite's existing case, moved to the reference form.
* **A class with a debug payload past the attribute's limit is built**, and its
  payload ends with the truncation mark.
* Step 2 adds its own: B's kernels answer as one kernel; A''s sub-drivers each
  fit; both forms fuzzed through the options.

## 6. The measurement

Step 1: `VarkaEmissionBenchmark` at 100, 200 and 400 entries, the parity
benchmark's widest shape at the same rungs. Step 2: a benchmark of its own for
the two forms (the project's rule for a new family).

### 6.1 Predictions, registered before the run

1. **Step 1 moves no committed kernel's bytes**: every oracle shape is under 64
   ops, so none was capped.
2. **Emission time per entry rises with width** from 100 to 400 entries; if it
   is superlinear in the committed benchmark as in the scratch run, the cause is
   named before step 2, since B would sidestep it and A' would not.
3. **At 400 entries B's per-row cost is within a factor of two of A''s**, the
   repeated input reads being the difference; B's emission time is lower.

## 7. Risks

1. **The op cap may be load-bearing somewhere unmeasured** - an analysis pass
   quadratic in ops, a slot table sized by it. The scratch run reached 400
   entries without a failure other than the debug string; the fuzzer draws at
   the grammar's own sizes, which stay small, so the widest cases are pinned by
   the tests above rather than fuzzed.
2. **Wider kernels take longer to emit**, once per shape per JVM, on the first
   task or at planning. Prediction 2 prices it.
3. **B changes the evaluator's contract** (one kernel per projection), which
   every exec node assumes; step 2 keeps it behind an option until measured.

## 8. Sequencing

1. This plan, and row 190 Planned.
2. Step 1 - the cap gives way under the budget, the debug payload bounded, the
   tests and the wider benchmark rungs. Unblocks task 171.
3. Step 2 - A' and B behind options, their benchmark, the decision.

## 9. Outcome

<!-- Filled in when the work lands. -->
