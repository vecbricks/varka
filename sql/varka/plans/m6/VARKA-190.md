# VARKA-190: the op cap gives way to the byte budget

## 1. Where this came from

VARKA-171's admission check, 24 September 2026. The size ladder is meant to show
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
projection". VARKA-87 made the policy unnecessary: every loop and epilogue method
is now measured in bytes, regrouped until it fits, and a shape that cannot fit
is declined with a reason - at plan time since VARKA-169. The cap was a stand-in
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
   VARKA-87's bound holds - but each driver sets up every output and calls every
   group, and grows by about 52 bytes an output. It crosses 8000 between 100
   and 150 entries of this family. VARKA-169 already declines a driver over the
   budget at plan time, demoting the last-admitted entries, so this limit is
   safe today; it is a ceiling on how wide one kernel can be, not a failure.
2. **The debug attribute.** At 800 entries the class cannot be built at all:
   `VarkaDebugInfo` carries the rendered IR and line map as constant-pool UTF-8
   entries, and the class-file format caps one at 65535 bytes ("string too
   long"). It is a metadata attribute the JVM ignores, so the answer is to
   bound what it carries, not to decline the kernel.
3. **Emission time grows faster than the outputs**: over the same run, four
   times the entries from 100 to 400 took several times more than four times as
   long to emit. It is paid once per shape per JVM (VARKA-169), and step 1 prices
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
budget, regroup and plan-time decline of VARKA-87 and VARKA-169 are what bound a
kernel. With the budget off (`methodByteBudget` 0, the reference form) the old
cap still applies, since nothing else bounds that form. `VarkaDebugInfo`
truncates what it carries to fit its attribute, marking the cut. The
widest-shape case of the parity benchmark and the emission benchmark gain rungs
at 100, 200 and 400 entries.

On the ladder's range - up to 100 entries, where vanilla keeps whole-stage
codegen - this fuses every entry of the greatest family in one kernel, which
is what VARKA-171 needs.

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

*Added on 24 September 2026, from `m6/READING.md` section 3.* Two
things the tensor compilers settle for this step before it is built:

* **A third form, C: shared work computed once.** AStitch's hierarchical data
  reuse keeps a producer that several consumers share in a buffer they all read,
  rather than recomputing it in each. For Varka that is the civil-from-days
  prefix, which every group using it recomputes today (`VARKA-87.md` 3.3),
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
* The ladder itself is VARKA-171's; this task makes the widths it needs fusable.

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
| `m6/PLAN.md` | row 190 |

## 5. Tests, and what each is for

* **A projection of a hundred greatest entries fuses every entry** under the
  default, and answers as the reference evaluator on both bodies.
* **Past the driver ceiling, the compiler demotes a suffix** with the driver's
  reason (VARKA-169's path), at the production budget, and every method of the
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
   tests and the wider benchmark rungs. Unblocks VARKA-171.
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
VARKA-171 as it stands.

### 9.2 Step 2's admission check, 30 September 2026

VARKA-191, VARKA-198, VARKA-209 and VARKA-219 changed the driver and the grouping since section 3.2 was
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
seventh row null, in 4096-row batches, and the table form alone at four hundred entries. Its
results are 10.4's.

### 10.2 Predictions, registered before the runner's run

1. **The table costs nothing measurable at run time**: at a hundred entries its time per row
   is within 3% of the unrolled driver's, on both bodies.
2. **Four hundred entries run at a cost per entry within 1.5 times the hundred's**, the
   difference being the wider class's compilation and cache footprint rather than the driver.
3. **The warm-up gap is the JIT's, not the driver's**: if the averages stay far above the bests
   on the runner, they do so for both forms alike.

### 10.3 What follows

The default flip, once 10.2 is scored (done: 10.4). Then A' and B of 3.2, for the widths past
about 180 groups and for projections wider than `MAX_INPUTS` columns - the ceilings A0 leaves.

### 10.4 The runner's measurement, and the default

`VarkaWideKernelBenchmark-jdk25-results.txt`, generated by the benchmark workflow on an AMD EPYC
9V45 runner - the machine the project's headline numbers come from - per row, best of the
iterations:

| case | unrolled driver | driver from a table |
|---|---:|---:|
| 100 entries, null-free | 52.3 ns | 50.5 ns |
| 100 entries, every seventh row null | 61.7 ns | 56.6 ns |
| 400 entries, null-free | cannot be emitted | 190.2 ns |
| 400 entries, every seventh row null | cannot be emitted | 225.1 ns |

1. **Held in substance, wrong in its band.** The table costs nothing; it is faster, by 3.4%
   null-free and by 8% with nulls, where one engine call replaces the unrolled bitmap pass. Read
   as "within 3%", the prediction is outside its band on both bodies, in the favourable
   direction.
2. **Held.** Four hundred entries cost 0.94 times the hundred's per entry null-free and 0.99
   times with nulls: the wide kernel is linear in its entries.
3. **Held.** The averages are 30 to 40 times the bests for both forms alike, with a standard
   deviation near twice the average: the JIT compiling a hundred groups' methods inside the
   two-second warm-up, not the driver. How long a wide kernel runs slowly before it is
   compiled is a question of its own for the kernel warm-up (`VarkaKernelWarmup`), not A0's.

**`driverOutputTable` is on by default**, on the owner's decision of 30 September 2026 after
these numbers. Its rendering in the shape key now marks the unrolled form (`|unrolledDriver`),
so the default key is unchanged.

**What the default moved.** `emitted_bytes.json` is regenerated: every shape's two drivers move
and no other method does, which `VarkaEmitterDriverTableSuite` pins over the fuzz corpus. In
`emit_cost_audit.json` no held-out shape declines any more, where 47 wide int-lane shapes, 36
wide long-lane shapes and the 200- and 400-entry size-ladder rungs did - every one of those
declines was the driver - and the cheap tails still build once. The price tables were refitted
on the shapes that now emit, which moved the audit's accuracy by at most 0.3 points (the
fitted model's median error at 2000 bytes and over from 2.2% to 2.3%), no conclusion of
`VARKA-199.md` 9 with it. Four wide long-lane shapes that used to decline now emit and gain
one loop method under `predictGrouping`, by the greedy close `VARKA-199.md` 9.3 describes;
the suite pins all six. Seven tests pinned mechanics of the unrolled driver - the bitmap pass's
calls, the driver's growth with the outputs, the decline and demotion it caused past the
budget, and the call-site budget's rebuild when the splits made it decline - and now read the
unrolled form, the reference arm, by name; the compiler's hundred-entry test also asserts that
two hundred entries fuse under the default.

*Correction, 30 September 2026, found by VARKA-200 (`VARKA-200.md` 8.1):* the section of
`VarkaWideKernelBenchmark` this measurement came from named `VarkaEmitOptions.DEFAULTS` the
unrolled driver, and once the table became the default above, that arm was the table as well, so
a regeneration would have compared the table with itself. The results quoted above predate the
switch and stand; the arms now name their forms.

### 10.5 The review, 30 September 2026

A code review of A0 found seven problems, none a wrong answer. All seven are addressed:

1. **The driver still grew with the columns.** It kept each input's null state and segments,
   the batch's two sizes and the species, lane count and loop bound, none of which it read once
   its per-output work was a table. The driver from a table now plans no input and emits none of
   them, so it is the empty-batch return, the plan call, the shortcut and its calls to the
   groups: 20 bytes and 44 a group, on both sides - 196, 592, 1120, 2220 and 4420 bytes dense for
   the `greatest` ladder at 16, 50, 100, 200 and 400 entries - and 1020 bytes for four hundred
   `date_add` outputs over one column or over sixty-four alike. The ceiling is 181 groups. The
   size test runs over one column and over sixty-four.
2. **The plan quoted laptop timings no results file backs.** They are removed; 10.4 quotes the
   runner's committed file.
3. **A plan table had no length check.** One `CONSTANT_Utf8` holds 65535 bytes of modified
   UTF-8, where a column ordinal of 0 takes two. A table past that now declines with the reason
   instead of failing the class build.
4. **The shortcut read its whole table on every masked batch.** It returns at the first output
   with no all-null column, which is the common case.
5. **The plan helper demanded a destination array of exactly the kernel's width.** The unrolled
   driver accepted a longer one; the helper now counts outputs from the plan and refuses only a
   shorter array.
6. **Two comments in `VarkaBodyEmitter` described the unrolled driver as the only one.** Both
   now say what the table form does.
7. **The size test used one column.** It now runs over sixty-four as well.

## 11. Step 2, A' and B: past the driver from a table

### 11.1 The ceilings A0 left

The driver from a table is 20 bytes and 44 a group (10.5), so under the 8000-byte budget it holds
about 180 groups - some 720 entries of the `greatest` ladder at four to a group, fewer for a
family whose groups hold fewer outputs. Past that the class declines on its drivers and VARKA-169's bisection demotes a suffix of the projection to the row engine. The second ceiling is
`MAX_INPUTS`: a kernel reads at most 64 columns, a bitset in a long, and an entry past them is
residual with "exceeds the emitter's fused budget". 3.2's two designs are built for these two,
each behind an option, off.

### 11.2 Built, 30 September 2026

**A': `VarkaEmitOptions.splitDriver`.** A class whose only methods over the budget are its
drivers is built again with the calls to its groups moved into stages, `stageDense<k>` and
`stageMasked<k>`, each calling the loop methods of a run of consecutive groups and then their
epilogues; the driver keeps its table call and the all-null shortcut and calls the stages in
order. The stage size is read off the measured driver - its groups scaled by the budget over its
bytes, less a margin - so one rebuild settles it, and a stage still over the budget halves it.
A stage has no prologue and keeps the status on the operand stack. The groups keep their order
across the stages, so a group that loads a prefix an earlier group materialized (VARKA-198) still
runs after it. Only the driver from a table splits: the unrolled driver grows with the outputs,
which no stage takes from it. A class whose drivers fit is the same class, byte for byte.

A' is smaller than 3.2 drew it. 3.2 partitioned the driver's per-output work as well; A0 made
that one engine call whatever the width, so only the calls are left to move.

**B: `VarkaEmitOptions.severalKernels`.** A compiler option, like `rangeSets`: no kernel's bytes
change, only how many a projection has.

* *The compiler.* `classify` now reports the entries it set aside only for the kernel's sake:
  the suffix a class-wide decline demotes, and an entry over the budgets beside the others that
  fits them alone - the column limit's case. Under the option those entries are classified again,
  with every other entry demoted, as a kernel of their own, and so on until a round fuses nothing
  or nothing is left aside. The first kernel's outputs stay `FusedOutput`; a further kernel's are
  `KernelOutput(kernel, index)`, and `PartialVarkaProjection.more` holds its plan. A projection one
  kernel serves is classified exactly as before. An entry residual for another reason - an
  unsupported expression, the other lane - keeps its reason.
* *The evaluator.* Each further kernel is a `VarkaKernelPart`, the evaluator base's machinery
  for that kernel alone: its shape-cached runner, its warm-up, its scratch. The projection's
  evaluator asks every kernel whether it can run and whether it is ready - each claims its own
  warm-up - and runs them in turn into the one output batch from the task's one allocator. A
  kernel that declines a batch sends the whole batch to the fallback, as with one kernel: the
  kernels are one projection, answered whole or not at all. The row node's merge numbers the
  kernels' columns end to end; the columnar node assembles them by spec. Verbose EXPLAIN names
  the kernel of each entry after the first.
* *What B does not cover.* A filter's predicate is still one kernel, and an entry of the other
  lane is still residual: both are the one-lane and one-mask rules, not a size.

3.2's C, a shared prefix computed once, shipped as VARKA-198's `materializeChronoPrefix`, on by
default, so in B each kernel computes the ladder's prefix once and its other groups load it.

### 11.3 Tests

`VarkaEmitterSplitDriverSuite`: three hundred one-output groups decline on the driver and under
the option emit in one rebuild with every method under the budget; a class whose drivers fit is
the same class byte for byte; under a 1000-byte budget every stage and the driver fit; the split
driver answers as the reference evaluator on both bodies, over three hundred groups and over
eight hundred ladder entries whose groups share one prefix. `VarkaExpressionCompilerSuite`: under
the option the unrolled driver's demoted suffix is a second kernel and every entry fuses, and
seventy date columns are served by two kernels, the second reading columns 64 to 69, while a
long-lane entry stays residual with its own reason. `VarkaKernelEvaluatorSuite`: two kernels'
columns, with a forwarded and a residual entry between them, assembled in order, and every vector
released. `VarkaSeveralKernelsSuite`: seventy nullable date columns and two hundred ladder
entries on the unrolled driver answer as the row engine does from two kernels, end to end. With
both options on, as 11.5 set them, `VarkaExpressionCompilerSuite` plans eight hundred ladder
entries as one kernel with stages in one emission, and with sixty-nine more date columns as two
kernels of 863 and 6 entries, the first with stages; `VarkaSeveralKernelsSuite` runs that
projection against the row engine under the default options. The IR
fuzzer draws both options like every boolean; its shapes stay far below 180 groups, so the split
driver is pinned by the suite rather than fuzzed.

### 11.4 Predictions, registered before the runner's run

`VarkaWideKernelBenchmark`'s last two sections: eight hundred and twelve hundred ladder entries,
two and three hundred groups, A' as one kernel with stages and B as the compiler splits them.

1. **A' is linear in its entries**: its time per row per entry at 800 and 1200 is within 10% of
   the 400-entry kernel's in the first section. A stage adds one call per batch per 180 groups.
2. **B costs at most 5% more per row than A'**, on both bodies. Each further kernel reads the
   date column again and computes the prefix once more, against hundreds of outputs' work; 6.1's
   factor of two predates the materialized prefix.
3. **At plan time A' costs more than B's classes alone and less than B with the compiler's
   search.** A' builds its class twice, the second time with stages; B builds each kernel once,
   but the compiler first asks the emitter for about ten halving prefixes, each built whole.
4. **The bytes are within 10% of each other**: the same group methods, with stages on one side and
   a second driver and dispatcher on the other.

### 11.5 The default: both on, 30 September 2026

The owner's decision, from four options: B alone as built, both on, B alone with its split sized
by measurement, and A' alone. **Both are on by default**, and each serves the ceiling it serves
best.

* **A' serves the driver's ceiling.** It stays one class: each batch reads the input once and
  computes a shared prefix once, and the class is planned in one emission, the second build with
  its stages sized from the first. B, as built, finds its split by the bisection VARKA-169's
  demotion uses - one emission per probe, and every prefix that fits loaded and held in the shape
  cache - so at this ceiling it plans at the cost the demotion pays today, several emissions
  where A' takes one, and its cut, in projection order, ignores what the entries share.
* **B serves what A' cannot**: a projection past `MAX_INPUTS` columns, where no single class can
  help, and a class past the class-file caps, which A' as one class keeps. With the split driver
  on, a driver over the budget no longer declines, so B's bisection runs only for those.
* **A' alone** was not enough, since it leaves the column limit to the row engine; **B alone** is
  one mechanism for every ceiling, but pays the search and the sharing at the driver's ceiling,
  which is the one wide projections meet first.

The runner's measurement is still to come, and 11.4's predictions are scored against it. If it
shows B faster per row than A' at 800 and 1200 entries, the decision is to be revisited, with B
alone and its first kernel sized from the driver's measured bytes, which grow linearly with the
groups, rather than bisected.

Off, each option is kept as the reference it replaces: the driver that declines past its
ceiling, and one kernel per projection whose set-aside entries are residual. The shape key
renders the off states (`|wholeDriver`, `|oneKernel`), so the default key is unchanged.

**What the default moved.** No committed byte: `emitted_bytes.json`, the cost audit and the price
tables are regenerated unchanged, since no shape in them reaches either ceiling. Four compiler
tests pinned VARKA-169's demotion - the op cap's overflow entry in the form without a budget, the
column limit's 33rd `datediff`, the driver's suffix under a 2000-byte budget and past the unrolled
driver's ceiling - and now read one kernel per projection, the reference, by name; the first also
asserts that under the default the column limit's entry is a second kernel's. The benchmark's
arms name their forms, since each of B's kernels is one whose driver fits.

Where the two options meet is the question the owner asked next: does using both well need an
e-graph? No. A' has nothing to choose - one class keeps every output's sharing, and its stages
are a byte count. B's efficiency is where it cuts, and it cuts in projection order, blind to
what entries share; the better cut is a clustering of entries by shared columns and subtrees,
the same problem as VARKA-72's output order, recorded there in `m8/SCOPE.md`. An e-graph
chooses among equivalent forms of an expression, which is item 11's question, not this one.

### 11.6 The runner's measurement, 30 September 2026

`VarkaWideKernelBenchmark`'s last two sections, from the benchmark workflow on an AMD EPYC 7763
runner, best of the iterations. The file's earlier sections keep the machines they were measured
on; each section names its own.

| case | split driver, A' | several kernels, B | B against A' |
|---|---:|---:|---:|
| 800 entries, null-free | 1683.6 ns | 1640.6 ns | -2.6% |
| 1200 entries, null-free | 2495.9 ns | 2453.0 ns | -1.7% |
| 800 entries, every seventh row null | 1902.2 ns | 1880.4 ns | -1.1% |
| 1200 entries, every seventh row null | 2882.2 ns | 2857.0 ns | -0.9% |

| one emission | 800 entries | 1200 entries |
|---|---:|---:|
| split driver, one class | 427 ms, 3541652 bytes | 742 ms, 5245552 bytes |
| several kernels, their classes alone | 197 ms, 3558607 bytes | 283 ms, 5342585 bytes |
| several kernels with the compiler's search | 2712 ms | 4226 ms |

1. **Holds.** A' costs 2.10 ns a row per entry at 800 entries and 2.08 at 1200, against 2.05 for
   the 400-entry ladder on the same processor model in VARKA-200's section: within 3%, linear.
2. **Holds, the other way round.** B costs not up to 5% more than A' but 0.9 to 2.6% less, on
   both bodies. The repeated input reads and the prefix computed once more per kernel cost less
   than whatever one class of two stages costs over two smaller classes.
3. **Holds.** A' plans in more time than B's classes alone and in far less than B with the
   compiler's search: 6.4 times less at 800 entries, 5.7 at 1200.
4. **Holds.** B's classes are 0.5 and 1.8% more bytes than A''s one.

**The decision is reopened.** 11.5 made both options the default and said a runner showing B
faster per row would reopen it: B is faster at both widths, by 0.9 to 2.6%. Against that, A'
plans several times faster, since B finds its split by bisection, and VARKA-236 is the planner
that would remove the search.

**Both stay on**, on the owner's decision of 30 September 2026: B's lead per row is 0.9 to 2.6%,
and A' plans five to six times faster while B's split is found by bisection. The question comes
back once VARKA-236 plans B's split without the search.

**Settled: both stay on, 3 October 2026.** VARKA-236 planned both forms and measured them on a
runner (`VarkaWideKernelBenchmark-jdk25-runner-8370c-results.txt`, `VARKA-236.md` 9.3), A'
first and again last as the control 11.6 lacked. On the ladder B is within the control's spread
at 800 entries and 4 to 8% ahead at 1200; on sixty-four dates listed by field it is 7 to 22%
behind, 15 to 20% with nulls, since its second kernel decomposes the dates again; and it plans
slower, 219 against 208 ms and 404 against 359. By the rule of `VARKA-236.md` 3.6 B alone
needed no loss on either family and no slower plan, so the division of duty of 11.5 stays: the
split driver where one class serves the outputs, several kernels where it cannot. B alone waits
for a cut that keeps sharers together (`m8/SCOPE.md` item 15).
