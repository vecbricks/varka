# Task 198: Shared work computed once, admitted by measurement first

*Opened 25 September 2026, from `PLAN_MILESTONE_6.md` 9.2's list after its
top five. Row 200, exact output grouping, says it waits for this task's
measurement, so this plan also decides whether 200 starts.*

## 1. The question

A calendar output decomposes its date through the civil-from-days prefix,
about forty vector operations, and the outputs of one loop method that
decompose the same date share it; a kernel split across several loop methods
recomputes it in each. `PLAN_TASK_87.md` 3.3 counted eleven repeated prefixes
at sixty `make_date` outputs. The row proposes AStitch's answer
(`READING_MILESTONE_6.md` section 3): compute the prefix once per batch into a
scratch buffer the later methods read. **Is recomputation expensive enough to
be worth that machinery?**

## 2. Why this starts with an admission check

**The ceiling from committed files is inside the noise.** At sixty outputs the
repeated prefix is 418 of 3756 vector operations, about 11%
(`PLAN_TASK_87.md` 9). The method-size benchmark's band puts the per-group
rows of that ladder in tiers 1 and 2, as far as 16% apart run to run
(`VarkaMethodSizeBenchmark-jdk25-band.txt`), and the scratch buffer's store and
load per lane group would take part of the 11% back. Counted in operations,
the task could not show a win on the ladder that motivates it.

**The machinery is not small.** The driver calls each group's loop method over
the whole batch, so a prefix computed once has to live in a buffer of the
batch's length, which the kernel's fixed `run` signature does not provide: its
owner, its lifetime across batches and threads, and the masked tail are all
design questions, and every emitted class would carry the answer.

So the first deliverable is a measurement that needs no new emitter code.
`VarkaSharedPrefixBenchmark` emits the same outputs with the fused ceiling
lowered from its default, so the emitter splits them into more groups and each
extra group recomputes the prefix once more; the difference between two arms
of a table prices those recomputations plus one method call per group per
batch, which bounds what a computed-once prefix can win. Two shapes: sixty
`make_date(year(d), month(d), k)` outputs, the ladder's top rung, and
sixty-four cheap tails over one date, `year(d) + k`, where the prefix is most
of each group's work. Each row names its group count, read from the emitted
class.

## 3. What the first run showed, before any measurement

The benchmark was run once on the laptop, not quiet, to check it works. Its
numbers are not committed and are not quoted here as rates; two things in it
are large enough to state as ratios and change the plan.

**Splitting `make_date` outputs costs far more than the prefix's share of
operations.** From twelve groups (the default) to thirty to sixty, the time per
row rose each time, by roughly the same amount per extra group, and at sixty
groups it was nearly three times the default's. If the eleven repeated
prefixes of the default cost what the extra groups cost here, they are well
above 11% of the kernel. The per-group cost includes more than the prefix,
though: each loop method reloads the column and runs its own loop, so the
measurement needs an arm that splits without recomputing, to separate the two.

**Sixty-four cheap tails in one group were about sixty times slower than the
same tails in six groups**, and that is not recomputation. Every method of the
one-group kernel is under 8000 bytes (`dev/varka_emit.sh`: 3763 bytes for
`loopDense0`, 225 `IntVector` call sites), so the method-size cliff is not the
cause. C2's assembly of that method is 72613 instructions with no `vpmulld`,
`vpsubd` or `vpsrld` at all, where eleven of the same outputs compile to 4407
instructions with 196 `vpmulld`: the calendar arithmetic runs as the Vector
API's scalar fallback, which is the failed-intrinsic shape `PLAN_TASK_165.md`
recorded, a method with the calls inlined away and no call left to find. The
default options produce this kernel: the fused ceiling of 400 lets a group that
reuses a prefix grow to all sixty-four outputs. It is a cliff the byte budget
does not bound, in a shape the milestone's post would call cliff-free, and it
is row 209 rather than part of this task.

## 4. Predictions, registered before the quiet run

1. **Recomputation on the `make_date` shape is priced above the band**: going
   from twelve groups to sixty costs at least twice the default's time per row
   on the laptop, as the first run suggests.
2. **The ceiling on the default grouping is at least 20%**: the eleven repeated
   prefixes plus calls of the default cost at least a fifth of its time, which
   would admit the task. Below 10% the task is withdrawn with this file as the
   reason, and row 200 with it.
3. **The one-group cheap-tail kernel stays catastrophically slow in a quiet
   run**, more than ten times the six-group kernel, because it is a compile
   outcome and not a timing effect.

## 5. Verification

* `dev/varka_bench_regen.sh catalyst VarkaSharedPrefixBenchmark` in the next
  quiet window, with a band from `dev/varka_bench_repeat.sh`, both committed.
* The group counts in the file match the emitted classes, and every arm's
  kernel completes every batch with status 0.

## 6. Outcome

*Written from the quiet run: each prediction scored, and the decision on
building the computed-once prefix and on row 200.*

## 7. Explicitly out of this task

* The computed-once prefix itself, until section 6 admits it.
* Row 209's cliff, which is its own task.
