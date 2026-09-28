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

The quiet run, 26 September 2026, on the laptop at both widths
(`VarkaSharedPrefixBenchmark-jdk25-results.txt` and its 128-bit companion),
and five repeats of the wide run for the band
(`VarkaSharedPrefixBenchmark-jdk25-band.txt`). The canary passed before the
run and the load at start was below 0.5.

**The `make_date` family is stable** - the band puts its four cases in tiers 0
and 1 - so the first two predictions are scored from it.

1. **Held.** Sixty groups cost 73.1 ns a row against the default twelve
   groups' 26.8 on the wide run, 2.7 times, and 211.7 against 86.0 on the
   narrow one, 2.5 times. Recomputation on this shape is priced far above the
   band.
2. **Held, as a ceiling.** Each group past the default's twelve costs about
   1 ns a row on the wide run and 2.6 on the narrow one. Charging all of it to
   the recomputed prefix, the default's eleven repeated prefixes are at most
   about 40% of its time on the wide run and 33% on the narrow one, above the
   20% the admission asked for. It stays a ceiling rather than a measured
   share: each extra group also reloads the column and runs its own loop,
   which section 3 said an arm that splits without recomputing would separate.
   **The computed-once prefix is admitted**, and row 200 with it.
3. **Refuted as worded.** The cheap-tail family is not slow in one shape and
   fast in the others: its speed is decided per JVM run. In the five band
   runs the one-group kernel was fast every time, about 4 ns a row, while the
   six-group kernel ran fast in some runs and at up to about 250 in others,
   the widest spread any band in this project has recorded (tier 3). In the
   regeneration's single wide run all four cheap-tail kernels were slow,
   241.6 to 411.7 ns a row; in its narrow run the one-group kernel was slow,
   976.6, and the six-group kernel fast, 10.0. So the catastrophe of section 3
   is a compile outcome that varies between runs of the same class, not a
   property of grouping, and the committed cheap-tail rows of the wide file
   are a slow-mode run, not the family's typical speed.

What it means for row 209: the cliff under the byte budget is worse than a
shape the budget misses, because the same kernel can land on either side of
it. Timing cannot say which side a run landed on; the evidence has to come
from the JVM - `-XX:+PrintCompilation` and the intrinsic diagnostics per run,
as `PLAN_TASK_165.md` did for the failed-intrinsic shape - and row 209 should
start there.

## 7. Explicitly out of this task

* The computed-once prefix itself, until section 6 admits it.
* Row 209's cliff, which is its own task.

## 8. The design, 28 September 2026

*Section 6 admitted the task. This section is the design the owner asked for
before any code, with its options weighed, its predictions registered, and the
measurement that decides it.*

### 8.1 What is shared, and where it is recomputed

The civil-from-days prefix is not an IR node. It is an emission fragment
(`VarkaChronoLowering.emitChronoPrefix`) keyed by the date it decomposes
(`chronoChild`): from the day count in `t[0]` it leaves the era, the day of
year, the century, the year of century and the March month in `t[1..5]`,
about 38 `IntVector` operations, most of them magic multiplies
(`PLAN_TASK_87.md` 2.2; `CHRONO_PREFIX_WEIGHT` is 31 in the weight model). A
calendar tail - `year`, `month`, `dayofmonth`, `make_date`'s recompose,
`last_day`, `trunc` - reads what it needs from those locals, and the `trunc`
and `last_day` tails and `add_months` read `t[0]` as well.

Within one body the fragment is emitted once per lane group: `Slots` keys it
by the date (and, in the masked body, by the validity word), and a sibling
over the same date finds the locals filled (`emittedFragments`). That is the
sharing `shareChronoPrefix` gives, and it stops at the method: a lane group's
locals do not outlive the method, and the driver runs each group's loop over
the whole batch before the next group's - `loopDense0` over every lane group,
then `loopDense1`, and so on, then the epilogues in the same order - so a
prefix over `d` that outputs in twelve groups need is computed twelve times per
lane group of the batch. At sixty `make_date` outputs the default grouping is
twelve groups of five, because five outputs' methods are what fit under 8000
bytes, and no grouping can put sixty outputs in one method. The size ladder's
own entry, `greatest(add_months(d, k), date_add(d, k), last_day(d))`, shares
`d`'s prefix between `add_months` and `last_day` in every entry, so every
group of a wide rung past the first recomputes it too: at 100 entries the
emitter makes 25 groups of four, each loop method 3271 bytes with 402
`IntVector` call sites (`dev/varka_emit.sh`, 28 September 2026), 38 of them
the prefix. This is why the task moves the ladder and not only the
shared-prefix benchmark.

### 8.2 The option space

* **S1. A scratch buffer the kernel owns.** The producing group stores the
  prefix's vectors into a per-batch buffer; later groups load them. The buffer
  is a field of the kernel instance: a kernel instance is per task
  (`VarkaShapeEntry.newKernel`; the warm-up runs an instance of its own), so
  the field is single-threaded by construction, and the `run` signature and
  every caller's arrays are unchanged. The cost is that a kernel is no longer
  stateless: it allocates its scratch on the first batch and grows it when a
  longer one comes, and holds it for the instance's life.
* **S2. A scratch buffer the caller owns**, passed as trailing entries of
  `dstData`: the class declares how many it needs and the evaluator, the
  warm-up and every harness allocate them beside the outputs. Keeps the kernel
  stateless and the "a call allocates nothing" contract; touches the twenty-four
  files that drive a kernel, and their arrays.
* **W. Grouping that keeps sharers together** (row 200): the exact partition
  in output order, and reordering for prefix affinity. It removes the
  recomputation the greedy walk causes by order - `year(d), year(d2), month(d)`
  puts `month(d)` in a third group - and nothing else: sixty sharers of one
  prefix cross eleven method boundaries under the byte budget whatever the
  order. A complement, after this task says what a boundary costs.
* **K. The kernel boundary** (task 190's C): under several kernels per
  projection the prefix would be an inter-kernel column. The same idea one
  level up, with the columns' plumbing; it is 190's to build if B is chosen
  there, and this design's plan-time analysis is what it would reuse.
* **The general form**, not taken now: any subtree read by outputs in two
  groups could be materialized the same way, one vector per shared node. The
  prefix is taken first because it is the shared thing that weighs 31 where a
  shared `year(d)` weighs one load either way; the region-per-key layout
  leaves the door open.

**S2 is chosen**, on the owner's word that stateless is preferred where it
costs no speed, and it costs none: the two are the same emitted loop code, and
differ only in who allocates the buffer and when. S1 would have been the
smaller change - the emitter alone, against the emitter plus every caller -
and its buffer is small, at most six vectors of a batch's rows, 240 KB at the
Arrow cache's ten thousand rows. But it breaks a contract the engine has kept
since milestone 1, that a kernel is a pure function of its arguments and a
call allocates nothing, and it leaves the buffer's lifetime to garbage
collection. Under S2 the caller allocates the scratch as it allocates the
output vectors, owns it for as long, and frees it with them. The scratch is a
new parameter of `run` rather than a trailing entry of `dstData`, so that
every caller is found by the compiler rather than by an index past the end of
an array at run time: two callers in production (the evaluator's runner and
the warm-up) and twenty-one harnesses and benchmarks in the test trees. If
task 190 passes a prefix between kernels, this is already the shape it needs.

### 8.3 The mechanism

Behind an emit option, `materializeChronoPrefix`, in `canonical()` so the
shape key sees it; off until 8.7's predictions are read.

**Plan time**, in `VarkaLoopEmitter.emit` after `groupOutputs` and inside the
byte-budget regroup loop, since a split can create a crossing: for each
fragment key (the date; the dense key, since the values are the same whatever
the validity word), the groups whose outputs reach a calendar node over it. A
key used by two groups or more is *materialized*: its producer is the first
such group in output order, its consumers the rest, and it gets a scratch
index. The layout is one region per materialized key of six vectors - `t[0]`
to `t[5]`, so that `add_months`, `trunc` and `last_day` tails find the day
count too - of the batch's rows, int32 whatever the tail's lane, since the
prefix is int-lane by construction (`VarkaChronoLowering`'s class doc). A
region of `length` rows is enough: the loop's unmasked accesses stay under the
loop bound, and the epilogue's masked ones check only the lanes their mask
sets, as the output stores do; the body views the region as it views an
output's buffer, a segment of `length` rows.

**The contract.** `VarkaFusedKernel.run` gains a parameter, `long scratch`,
the address of a buffer of at least `scratchBytesPerRow() * length` bytes,
and the interface gains
`scratchBytesPerRow()`, a constant the emitted class returns: zero for every
kernel with nothing to materialize, which passes `0L` and never dereferences
it. The driver of a kernel with scratch begins with one compare, and a zero
address is an `IllegalArgumentException` naming the kernel rather than a
write to address zero. Kernels stay pure functions of their arguments and a
call allocates nothing, as `VarkaFusedKernel`'s javadoc says today; the
javadoc gains the scratch's contract beside the validity addresses'.

**The callers.** The evaluator's `FusedRunner` allocates one scratch buffer
from the task's allocator on the first batch, sized to that batch's rows, and
grows it when a longer batch comes; it joins the buffers the evaluator already
keeps for a task's life and releases in its task-completion listener before
the allocator closes (the `maskBuf` discipline, `closeScratch`), so nothing
new is invented for its lifetime. The warm-up allocates its own in the arena
that already holds its probe outputs, for `MAX_CALL_ROWS`; `VarkaEmitterTestBase`
allocates for the suites in the helper the suites already drive kernels
through; each benchmark's driver allocates once beside its output buffers.
`VarkaScratch.sizeFor(kernel, rows)` is the one place the size is computed.

**The producer's bodies** - its loop method and its epilogue, dense and masked
- store `t[0..5]` to the region right after `emitChronoPrefix` leaves them,
with the body's own store form: unmasked in the loop, under the epilogue's
bounds mask in the epilogue. In the masked body the prefix is computed on
every lane already (the date is masked-loaded with zero fill); the lanes no
consumer reads hold whatever the arithmetic made of a zero, which is what they
hold today in the producer's own locals.

**The consumers' bodies** load `t[0..5]` from the region where they would have
run the prefix (`emitChronoPrefixOnce`), and do not emit the date child for it:
a child another node of the group needs is emitted by that node's own arm, as
now. Six loads replace one column load and about 38 operations. The guard that
condemns a batch on an out-of-range day lives on the date's producer and runs
where that producer is emitted, so the status is set once, by the producing
group, and the union in the driver is unchanged.

**The weights.** `GroupOps` keeps the prefixes of every group closed so far,
not only the group being built, and counts a calendar node whose prefix an
earlier group computes at its tail plus the six loads rather than at
`CHRONO_WEIGHT`, so `groupOutputs` packs more consumers per group; bytes still
decide, in the regroup. A materialized key is planned again on each regroup
iteration from the groups of that iteration, so a split that moves a producer
into a later group moves the producer with it.

**The month step.** `planFragmentsReadingMonth` decides per lane group of one
body whether a fragment's run ends with the March month, from that body's
tails alone (`elideChronoMonth`). For a materialized key the producer's run is
decided over every consumer's tails as well: a `year(d)` producer whose
consumers include `month(d)` keeps the month step it would otherwise elide, in
its loop and in its epilogue.

**Two things the design rests on, checked in the source.** First, a masked
body computes every output's DAG on every lane group: the validity words
(`0L`, the bitmap's bits or `-1L`) gate only which validity bits are written,
and the masks inside the prefix are the carries' compare masks, not a
validity word (`emitCarry`), so a prefix keyed by the date alone holds the
same values whatever word its producer's output carries, and a consumer with a
wider word finds them computed. Second, a shared node is emitted by whichever
reader reaches it first (`emitValue`'s `computed` set), so a consumer that no
longer emits the date child for the prefix leaves it to the child's next
reader in the group, if any.

**Task 209's budget** gets the same relief for free: a consumer group has about
thirty fewer vector call sites, a third of the 93 C1 compiles.

### 8.4 What is deliberately unchanged

* With the option off the emitter emits what it emits today, byte for byte:
  `emitted_bytes.json` is the oracle, as for every emitter change since task
  167.
* `shareChronoPrefix`'s sharing within a lane group stays and composes with
  this: a producer group with two tails over `d` still runs the prefix once per
  lane group and stores it once.
* The `run` signature, the callers' arrays, the reference evaluator, the
  warm-up's protocol and the shape cache: an instance's scratch is invisible
  to all of them.
* The cheap-tail cliff (row 209) and the exact grouping (row 200).

### 8.5 Files

| file | what |
|---|---|
| `VarkaEmitOptions.java` | `materializeChronoPrefix`, canonical |
| `VarkaEmitBudget.java` | the consumer's weight beside `CHRONO_PREFIX_WEIGHT` |
| `VarkaLoopEmitter.java` | the plan of materialized keys per grouping; the two fields; the helper's call at the top of the driver |
| `Analysis.java`, `Slots.java` | the key-to-region map; a slot for the region's base address per body |
| `VarkaBodyEmitter.java` | unpacking the scratch address at method entry, as the output addresses are |
| `VarkaChronoLowering.java` | the stores after the prefix, the loads in place of it |
| `VarkaScratch.java` (new) | `sizeFor`: the region's size, in one place |
| `VarkaFusedKernel.java` | the `scratch` parameter of both `run` overloads, `scratchBytesPerRow()`, and their contract in the javadoc |
| `VarkaEvaluatorBase.scala` (`FusedRunner`), `VarkaKernelWarmup.java` | the two production callers: allocate, grow, free |
| `VarkaEmitterTestBase`, the probes and benchmarks that drive a kernel | the twenty-one callers in the test trees, found by the compiler |
| `VarkaEmitterChronoSuite`, `VarkaEmitterBudgetSuite`, `VarkaEmittedBytesSuite` | 8.6 |
| `VarkaSharedPrefixBenchmark` | the materialized arms, 8.8 |
| `PLAN_MILESTONE_6.md` | rows 198, 200 and 209 |

### 8.6 Tests, and what each is for

1. **Which keys materialize** (`VarkaEmitterBudgetSuite`, from the plan alone):
   `year(d), month(d)` in one group - none; sixty `make_date` at the default -
   one key, producer group 0, eleven consumers; `year(d), year(d2), month(d)`
   - `d`'s key between groups 0 and 2, the greedy limitation now costing six
   loads rather than a prefix; a computed child, `year(date_add(d, 1))` and
   `month(date_add(d, 1))` forced into two groups - materialized, and the
   consumer emits no `date_add`.
2. **Answers equal to `VarkaReferenceEvaluator`** with the option on
   (`VarkaEmitterChronoSuite`): sixty `make_date` at the default grouping and
   at a fused ceiling of 100 (sixty groups); the ladder's `greatest` entry at
   twenty; a nullable date, so the masked bodies store and load; at lengths
   1024, 1031 and 1 (an even batch, a ragged tail, and a batch shorter than a
   lane group) at both widths, as task 87's ladder tests.
3. **The scratch's contract**: one runner at 1000 rows, then 10000, then 1,
   answers right each time (the caller's buffer grew once); a kernel with
   scratch given `0L` fails by name; a kernel without scratch given `0L` runs;
   a batch that condemns itself on an out-of-range day returns the same status
   on and off.
4. **The bytes.** Off: `emitted_bytes.json` unchanged. On: every consumer loop
   method carries at least twenty-five fewer `IntVector` call sites than its
   producer's (`VarkaEmitterTestSupport.methodNames`) - the prefix's 38 and the
   date's load gone, six loads in their place - which is the count
   `dev/varka_emit.sh` prints and task 209 reads.
5. **The fuzzers with the option on** (`VarkaIrFuzzSuite`'s option matrix): the
   IR fuzzer draws calendar nodes over shared and computed dates, so it is the
   test of every crossing this plan did not think of.

### 8.7 Predictions, registered before the build

1. **Sixty `make_date` at the default grouping**: the materialized arm is at
   least 15% faster per row than the 26.8 ns of the wide run and 10% faster
   than the 86.0 of the narrow one (section 6), against a ceiling of 40% and
   33%. Below 10% on the wide run the mechanism is not worth its state, and
   the option stays off with this file as the reason.
2. **At sixty groups** (fused ceiling 100) the materialized arm is within 1.5
   times the default's twelve-group time, where recomputation put it at 2.7:
   the remainder is the per-group fixed cost - the column reloads, the loop,
   the call - which this arm separates from the prefix, the measurement
   section 3 said was missing.
3. **The size ladder** at 100 entries improves by at least 10% at 512 bits on
   the runner and on the laptop: 24 of its 25 groups recompute the prefix, 38
   of each group's 402 call sites, about 9% of the operations, and the
   admission check found the prefix's time share well above its share of
   operations. The rungs under 54 entries, one or two groups, move within
   the band.
4. **The cheap-tail shape does not move**: one group, nothing crosses, and its
   per-run cliff is row 209's, not this option's.
5. **Group counts do not grow** anywhere in `emitted_bytes.json`'s corpus with
   the option on, and at sixty `make_date` they fall below twelve once
   consumers weigh their loads rather than a prefix.

### 8.8 The measurement

* `VarkaSharedPrefixBenchmark` gains a materialized arm at each ceiling for
  both shapes, so every table reads recomputed against computed once at the
  same group count; regenerated at both widths with a band
  (`dev/varka_bench_regen.sh catalyst VarkaSharedPrefixBenchmark`,
  `dev/varka_bench_repeat.sh`), quiet.
* `VarkaMethodSizeBenchmark` (task 87's ladder) and the size ladder
  (`VarkaSizeLadder`, task 171's) with the option on against their committed
  files, on the laptop and then on a runner, since the ladder is a headline.
* The bytes and call sites per method from `dev/varka_emit.sh` on the sixty
  `make_date` shape, committed beside the plan as the reading of 8.6's test 4.
* If prediction 1 holds, the option flips on by default in its own pull
  request, with `emitted_bytes.json` regenerated under task 167's rule, the
  fuzzers' run, and both ladders' files regenerated; rows 200 and 209 note what
  moved for them.

### 8.9 Risks

1. **Memory traffic where compute was.** Six vectors of a batch written once
   and read per consumer: 240 KB per batch at ten thousand rows, which stays in
   L2 on every host in the census. On a longer batch it does not, and the
   loads would come from L3; the ladder's batches are the Arrow cache's, so the
   measurement sees the real size.
2. **A consumer's tail reads a prefix local the producer did not keep.** Under
   `elideChronoMonth` the producer may skip the month step when no tail *of its
   group* reads it; a materialized key's month step is decided over every
   consumer's tails as well (`fragmentsReadingMonth` widened to the key's
   groups), and test 2's `make_date`, whose recompose reads the month, is the
   check.
3. **A caller sizes the scratch short.** The bytes per row live in one helper,
   and the driver cannot check a segment's length from an address; test 3's
   growing batches and the fuzzers' ragged lengths are the check, and the
   evaluator's allocation is the one path production runs.
5. **The producer gains call sites.** Six stores are six `IntVector` calls, and
   a producer group already near task 209's 93-site budget could cross it
   where its consumers fall well under. The budget's setting (row 209) counts
   the stores; a producer that would cross splits like any group over a limit.
4. **The regroup moves a producer.** A split whose new first half holds no
   consumer of a key it produced leaves the key to the next group; the plan is
   recomputed per iteration, so it cannot leave a consumer without a producer,
   and test 1's forced grouping is the pin.

### 8.10 Sequencing

1. The option, the plan of materialized keys, and test 1 - no emission yet.
2. The contract: the `run` parameter, `scratchBytesPerRow()`, every caller
   allocating (most pass `0L` until a kernel needs more), `emitted_bytes.json`
   unmoved; then the emission: stores and loads; tests 2 to 4.
3. The benchmark's arms and the quiet regeneration; predictions 1, 2 and 4
   scored.
4. The ladders on the laptop and a runner; predictions 3 and 5 scored; the
   fuzzers on.
5. The default, in its own pull request, if 1 holds; then rows 200 and 209.

## 9. The build, 28 September 2026

Steps 1 and 2 of 8.10 are built, behind `materializeChronoPrefix`, off by default. What was
built as 8.3 says is not repeated here; what differs from it is:

* **The contract is two new overloads, not a changed one.** `VarkaFusedKernel.run` keeps its
  seven- and eight-argument forms, and gains each form with `long scratch` after the length,
  as interface defaults that drop the address and call the old form. A kernel with nothing
  to materialize implements the old form as before, so its bytes are unchanged -
  `emitted_bytes.json` is unmoved with no regeneration, and a caller that never meets a
  materialized kernel needs no change. A kernel with scratch implements the new form, an old
  form that throws `UnsupportedOperationException` naming the kernel and its bytes per row,
  and `scratchBytesPerRow()`. The production callers, the evaluator and the warm-up, and the
  test harnesses that drive kernels under arbitrary options, call the new form always; the
  parity and arithmetic benchmarks keep the old form, which is right for them since their
  options never materialize.
* **There is no `VarkaScratch.sizeFor`.** The size is the kernel's own answer,
  `scratchBytesPerRow()`, computed once in `Analysis.scratchBytesPerRow()` as the regions
  times six vectors times the lane's stride; the tests share one helper,
  `VarkaEmitterTestSupport.scratch(kernel, rows)`, a thread-local buffer regrown on demand.
* **Where the region is addressed.** Not at the top of the driver: each loop and epilogue
  method that touches a region builds its six segments in its own prologue, right after the
  batch's sizes, as `scratch + (region * 6 + k) * dataBytes` of `dataBytes` each, so the
  layout follows the batch's own length and the driver, which runs no calendar node, keeps its
  frame. The segment locals are planned after every other slot of the frame, so no local of
  an unchanged emission moves. The public `run` of such a kernel refuses a zero address with
  an `IllegalArgumentException` before dispatching.
* **The consumer still visits one kind of date.** 8.3 said a consumer emits no date child for
  the prefix. In the masked body a date whose validity word is its own - `date_add(d, e)` over
  two columns, not `d` or `date_add(d, 1)`, whose words alias an input's - stores that word as
  a side effect of its visit, and the calendar tails' words alias it; so such a date is still
  visited by the masked consumer and its vector dropped (`Slots.ownWord`), where a column or
  a literal-offset date is not loaded at all. The dense body never visits it.
* **The month vector travels only where it is read.** The producer computes the month step
  when any group's tail over the date reads it, as 8.3 says, and stores `t[5]` only then; a
  consumer loads `t[5]` only when its own fragment's tails read it (`fragmentsReadingMonth`),
  so a `year`-only consumer of a `month`-reading key loads five vectors.
* **The weights, in detail.** `GroupOps` takes the closed groups' prefixes as a shared set. The
  first calendar node over a date in a group pays `CHRONO_PREFIX_LOAD_WEIGHT`, six, where an
  earlier group computes the prefix, and `CHRONO_PREFIX_WEIGHT` otherwise; a later node over
  the same date in the group saves exactly what the first paid, so clause 2 of `groupOutputs`
  still opens the wider ceiling for a group that reuses a loaded prefix, on the same ground -
  joining is strictly less work.
* **The materialization needs the sharing and the byte budget.** With `shareChronoPrefix` off
  no fragment is shared inside a group either, and with `methodByteBudget` zero the epilogue
  is one method over every output, which computes each prefix once already; both leave the
  option without effect rather than half an effect.
* **Test 1 reads the class, not the plan.** The materialized keys are read as the kernel's
  `scratchBytesPerRow()` - twenty-four per key - and as the loop methods' `IntVector` call
  sites, a consumer's at least twenty-five below its producer's; the plan-time map is package
  state of `Analysis` and has no reader outside the emitter.

**The reading of 8.8's third item**, `dev/varka_emit.sh` on the sixty `make_date` outputs
(`make_date(year(d) + k / 28, month(d), k % 28 + 1)`), `IntVector` call sites per dense loop
method:

| arm | loop methods | producer | consumers | call sites per batch |
|---|---|---|---|---|
| off | 12, five outputs each | 313 to 317 each | | 3756 |
| on | 11: five outputs, nine of six, one of one | 319 (313 and six stores) | 343 to 347 for six outputs; 70 for the last | 3485 |

A consumer's six outputs cost what five and a prefix cost before, which is the weights
working: 7% fewer call sites per batch and one call fewer, where 8.7's predictions rest on the
prefix's share of time being well above its share of operations, as the admission check found.
The masked methods read the same way, six more sites in the producer's `loopMasked0`.

The tests of 8.6 pass on the laptop: test 1 in `VarkaEmitterBudgetSuite`, tests 2 and 3 in
`VarkaEmitterChronoSuite`, test 4's byte identity in `VarkaEmittedBytesSuite`, and the fuzzers,
whose option draw covers every boolean `with*` and so this one. The evaluator's side is
`VarkaMaterializedPrefixSuite` in `sql/core`: sixty `make_date` over a nullable Arrow-cached
date, at the default grouping and one output per group, against the row engine. The
benchmark's arms (8.8) are in `VarkaSharedPrefixBenchmark`; the quiet regeneration and the
predictions' scoring follow in section 10.
