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

*Added on 24 September 2026, from `READING_MILESTONE_6.md` section 3.* Two
things the tensor compilers settle for this step before it is built:

* **A third form, C: shared work computed once.** AStitch's hierarchical data
  reuse keeps a producer that several consumers share in a buffer they all read,
  rather than recomputing it in each. For Varka that is the civil-from-days
  prefix, which every group using it recomputes today (`PLAN_TASK_87.md` 3.3),
  computed once per batch into a scratch vector. It changes what B costs - B's
  price is exactly a prefix recomputed per kernel - so it is measured beside A'
  and B, and milestone row 198 measures it first on today's kernels, where it
  already applies.
* **Where B may cut.** XLA merges a producer into its consumers only when it
  fuses with all of them and the code it duplicates stays small. B's partition
  cuts only between outputs that share no subtree, and a cut through a shared
  prefix is costed as the recomputation it forces - or, under C, as the scratch
  vector it writes and reads.

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

### 9.1 Step 1: bytes decide, 24 September 2026

`MAX_FUSED_NODES` bounds only the form without a byte budget. `fitsBudgets`
takes the emit options, the compiler passes them from its admission, and
`Analysis` skips the op count under the budget; `VarkaDebugInfo` cuts each field
to fit its constant and marks the cut. `dev/varka_emit.sh` over a hundred
`greatest(add_months(d, k), date_add(d, k), last_day(d))` entries reproduces 2's
row: twenty-five groups, the largest group method 4448 bytes, `runDense` 5174,
`runMasked` 5828, 484 constant pool entries, and every entry fused.

**What the tests pin.** A hundred of these entries all fuse under the default
and fifteen under the budget-off form; two hundred fuse a suffix short of the
whole, demoted with the driver's reason at plan time. The IR form of the
hundred emits with every method under 8000 bytes and answers as the reference
evaluator. An oversized debug payload builds and ends with the mark. The three
tests that pinned the op cap now pin it on the form it still bounds, and say
what the default admits instead.

**Predictions.** 1 held: the bytes oracle is unmoved, since no oracle shape was
past 64 ops. 2 is read from `VarkaEmissionBenchmark`'s new section, one
emission per iteration, at 512 bits (the 128-bit file agrees to within 3%):

| outputs | ns per emission |
| ---: | ---: |
| 25 | 1641141.0 |
| 50 | 3876786.0 |
| 100 | 10122976.0 |
| 200 | 31042842.0 |
| 400 | 103358023.0 |

Each doubling costs 2.4 to 3.3 times as much, so emission is superlinear, as
the scratch run said. The cause 6.1 asked to be named before step 2, read from
the code rather than yet measured: every group's four methods plan their slots
over the whole kernel's topological order (`Slots.plan` walks
`analysis.topoOrder` for every body), so the planning is groups times nodes,
and both grow with the outputs. A per-group topological order would make it
linear. It is milestone row 191, added the same day at the owner's request, and
lands before step 2: how much A' costs against B, which emits several small
kernels and so never meets it, depends on it.

At the ladder's widths the cost is small against the query it serves - ten
milliseconds at a hundred entries, once per shape per JVM - so step 1 unblocks
task 171 as it stands.

### 9.2 Step 2's admission check, 30 September 2026

Tasks 191, 198, 209 and 219 changed the driver and the grouping since section 3.2 was
written, so the ceiling was measured again on master at `a94e62e5f51`, through a scratch
probe that was not committed: four families at seven widths, emitted at sixteen lanes once
at the shipped options and once with the byte budget at the class-file cap and the call-site
budget off, so the methods can be read past the point where the shipped options decline.

| family | outputs | groups | `runDense` | `runMasked` | shipped options |
|---|---:|---:|---:|---:|---|
| `greatest` entry | 100 | 25 | 5278 | 5932 | emits |
| `greatest` entry | 150 | 38 | 8268 | 9244 | declines on both drivers |
| `greatest` entry | 400 | 100 | 23742 | 26218 | declines on both drivers |
| `year(d) + k` | 100 | 1 | 4022 | 4980 | emits |
| `year(d) + k` | 150 | 1 | 6334 | 8064 | declines on `runMasked` |
| `make_date` | 100 | 17 | 4862 | 5020 | emits |
| `make_date` | 150 | 26 | 7644 | 8174 | declines on `runMasked` |
| `date_add(d, k)` | 100 | 7 | 4310 | 5268 | emits |
| `date_add(d, k)` | 400 | 25 | 19736 | 24216 | declines on both drivers |

**The ceiling is the same for every family, between 100 and 150 outputs, and it is set by the
outputs, not by the groups.** The `year(d) + k` family has one group at every width up to 300
and hits the ceiling at the same width as the `greatest` family with 38 groups. Read by
difference across the table:

* **Each output costs the driver about 41 bytes in `runDense` and 50 in `runMasked`.** Per
  output the driver loads the output's data segment, loads its validity segment and zeroes or
  fills that validity; the masked driver adds the bitmap pass's call for a served output and a
  term of the all-null shortcut. The data segment is dead in the driver: only the loop and
  epilogue methods write data, and each loads the segments it needs in its own prologue. That
  is about a third of the per-output cost, more once the segments' slots pass 255 and each
  store takes the wide form.
* **Each group costs about 54 bytes**: two calls, to its loop and to its epilogue.

**What this changes in 3.2.** A' and B were designed around a driver that grows with the
groups. It grows mainly with the outputs, and its per-output work is the same few calls with
a different output index each time, which is the shape of a loop rather than of unrolled code.
That admits a smaller step before either:

* **A0: the driver's per-output work as a loop over the outputs.** The driver runs one loop
  over a per-class table of each output's treatment - zero or fill its validity, run the bitmap
  pass or not - and drops the dead data segments. Its size then grows with the groups alone,
  about 54 bytes each, so the ceiling moves from about 140 outputs to about 140 groups: roughly
  560 `greatest` entries at four to a group, and more for families that pack more outputs into
  a group. No class, kernel or evaluator contract changes, and the dead segments go whatever
  else is decided.

A0 does not remove the ceiling. It moves it from outputs to groups, and past about 140 groups
A' or B is still needed, as is B for a projection wider than `MAX_INPUTS` columns. So the
proposal is to build A0 first, since it is small, is contained in the driver's prologue and
serves every design, and then to build and measure A' and B above its ceiling as 3.2 planned,
where they are needed rather than at 150 outputs.

Two things the check leaves to the design. Whether the all-null shortcut can be a loop too:
it tests, per output, whether any of its columns is all-null, which a table of each output's
column set answers. And what A0 costs at run time: the per-output work runs once per batch
either way, so a loop in place of unrolled calls is expected to cost nothing measurable, and
the step's benchmark is to say so.

## 10. Step 2, A0: the driver from a table

### 10.1 Built, 30 September 2026

`VarkaEmitOptions.driverOutputTable`, off by default. Under it the driver's per-output work is
two calls into the engine, whatever the width:

* `VarkaVectorSupport.prepareOutputValidity` reads a plan string the emitter bakes into the
  class, one step per output: zero the validity (at the nominal size, or to the last whole
  word where validity is written a word at a time), fill it on a dense batch, or run the bitmap
  pass's copy, AND or OR over the listed columns. Each step calls the same entry point the
  unrolled driver calls for that output, so the bits are the same. The plan is decided by the
  unrolled form's own predicates (`servedByPass`, `fillsValidityOnce`, `wordWrites`,
  `keepsPerGroupWrite`), so the two forms cannot disagree about an output.
* `VarkaVectorSupport.everyOutputReadsAnAllNullColumn` answers the masked driver's all-null
  shortcut from a table of each output's columns.

The driver then maps no output segment and hoists no literal: it read neither. The loop and
epilogue methods are unchanged byte for byte, which a test checks over the first 400 fuzz
shapes of each lane.

**A correction to 9.2.** It priced a group at about 54 bytes and missed a second per-output
cost. Measured on the table form, a group costs exactly its two calls, 44 bytes. The unrolled
driver also hoists every literal into a local, about seven bytes each, and never reads one; the
size ladder has a literal per entry, so this was part of what 9.2 counted as the per-output
cost. With both gone the driver is linear in the groups alone - on the `greatest` family 247,
643, 1171, 2271 and 4471 bytes dense at 16, 50, 100, 200 and 400 entries - and the ceiling is
about 180 groups, some 720 entries of that family, where 9.2 estimated 140 groups.

**Tests.** `VarkaEmitterDriverTableSuite`: the emitter's plan steps are the engine's; the driver
grows by 44 bytes a group and not with the outputs; four hundred `greatest` entries emit under
the shipped budget from a table and decline on both drivers without it; the loop and epilogue
methods are the unrolled form's byte for byte; every step the plan can hold - zero, word zero,
fill, copy, AND, OR, a three-column chain, a literal-only output, a selection - answers as the
reference evaluator on both bodies at the host's width and at sixteen lanes, with the all-null
shortcut taken; and two hundred `greatest` entries, a width the unrolled driver cannot emit,
answer too. `VarkaVectorSupportOutputPlanTest`, in the engine, runs every plan step against the
entry point it stands for over lengths either side of a byte and a word and over null-free,
all-null and mixed columns, and checks the shortcut against the unrolled AND of ORs. The IR
fuzzer draws the option like every boolean; eight fresh seeds at 20000 iterations on both
lanes and the composition fuzzer passed.

**The measurement.** `VarkaWideKernelBenchmark`, a class of its own for the wide-kernel family:
the hundred-entry ladder with the driver unrolled and from a table, null-free and with every
seventh row null, in 4096-row batches, and the table form alone at four hundred entries. A
laptop run the same day, a sanity check and not a result, read the table form's best time at
or under the unrolled form's on both bodies (72 against 74 ms null-free, 85 against 88 with
nulls); its averages were several times its bests on every case, which reads as the JIT still
compiling a hundred groups' methods inside the two-second warm-up. The committed results come
from a runner.

### 10.2 Predictions, registered before the runner's run

1. **The table costs nothing measurable at run time**: at a hundred entries its time per row
   is within 3% of the unrolled driver's, on both bodies.
2. **Four hundred entries run at a cost per entry within 1.5 times the hundred's**, the
   difference being the wider class's compilation and cache footprint rather than the driver.
3. **The warm-up gap is the JIT's, not the driver's**: if the averages stay far above the bests
   on the runner, they do so for both forms alike.

### 10.3 What follows

The default flip, once 10.2 is scored. Then A' and B of 3.2, for the widths past about 180
groups and for projections wider than `MAX_INPUTS` columns - the ceilings A0 leaves.
