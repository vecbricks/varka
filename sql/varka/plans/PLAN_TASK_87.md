# Task 87: every emitted method counted in the units the JVM enforces

*Planned 23 September 2026 together with task 168, on the owner's decision that
the two are one mechanism: task 87 was "the epilogue is the one method no budget
bounds" (`PLAN_MILESTONE_5.md` 2.18) and task 168 "one budget, counted in bytes,
over every emitted method" (`PLAN_MILESTONE_6.md` 2.2). The epilogue is just the
method nobody counted, so planning them apart risks 87 building a mechanism 168
replaces. Both rows point here.*

## 1. Where this came from

On 8 September 2026 a 35-million-iteration fuzz run built a 67244-byte
`epilogueMasked` for a nested `make_date` tree and the Class-File API refused
it, because a JVM method is capped at 65535 bytes (`PLAN_MILESTONE_5.md` 2.18).
The analysis then was that three caps each bound something other than bytes -
`MAX_CHAIN_DEPTH` one output's depth, `MAX_FUSED_NODES` the distinct ops,
`GROUP_BUDGET` one loop method's *weight* - and that the epilogue is emitted
once for every output with no cap applying to it at all.

Milestone 6 made this its first task because its closing post rests on the claim
that Spark cannot count what the JVM enforces - its own documentation says "we
cannot know how many bytecode will be generated, so use the code length as
metric" - while Varka, emitting bytecode through the Class-File API, can. Varka
cannot make that claim while it has the same defect.

## 2. The admission check, done

Four findings, each from the tools or the JVM rather than from reading the
emitter, and together they change what this task is.

### 2.1 The pinned reproducer no longer reproduces

`-Dvarka.fuzz.seed=2026092800 -Dvarka.fuzz.only=73411`, replayed on master
`c4e00a86484`:

    build/sbt "project catalyst" \
      'set Test/javaOptions += "-Dvarka.fuzz.seed=2026092800"' \
      'set Test/javaOptions += "-Dvarka.fuzz.only=73411"' \
      "testOnly *VarkaIrFuzzSuite"

passes, four tests green. `VarkaIrFuzzSuite` fails loudly on a rejected
emission, so the iteration now draws a different tree: the grammar has gained
nodes since 8 September (`GuardedRange`, `NarrowLane`, `BoundedDivide`, the long
lane), and a new node reshuffles the whole sample
(`sql/varka/skills/emitter-and-ir.md`, "Adding an IR node moves the bytes
oracle's fuzz digests"). **A fuzz coordinate is not a durable reproducer.** This
task pins its shapes as built expressions instead.

### 2.2 The ladder: bytes grow where weight does not look

`make_date(year(d), month(d), k)` for `k` in `1..n`, through `dev/varka_emit.sh`
at the host's preferred width (512 bits):

| n | `epilogueDense` | `epilogueMasked` | `loopMasked0` | epilogue `IntVector` | loop groups |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 968 | 1087 | 1065 | 93 | 1 |
| 4 | 2498 | 2716 | 2663 | 258 | 1 |
| 8 | 4536 | 4886 | 3285 | 478 | 2 |
| 12 | 6866 | 7500 | 3409 | 698 | 3 |
| 13 | 7533 | **8206** | 3440 | 753 | 3 |
| 14 | **8202** | 8914 | 3471 | 808 | 3 |
| 16 | 9524 | 10314 | 3533 | 918 | 4 |
| 32 | 20214 | 21600 | 4029 | 1798 | 7 |
| 48 | 30890 | 32886 | 4739 | 2678 | 10 |
| 60 | 38898 | 41338 | 5357 | 3338 | 12 |

Three readings.

* **The epilogue grows linearly, about 690 bytes an output**, because every
  output lands in it. The loop methods do not, because `groupOutputs` splits
  them - five `make_date` outputs to a group under `FUSED_CEILING`, since they
  share a civil-from-days prefix.
* **The loop methods grow too, and weight cannot see it.** `loopMasked0` goes
  from 3533 to 5357 bytes between 16 and 60 outputs while its `IntVector`
  count stays at 313. Whatever grows is not an op the budget counts.
* **Sixty outputs do not reach 65535.** `MAX_FUSED_NODES` (64) stops this family
  at 62 distinct ops, first. The class-file cap is not the first limit this
  shape meets - 2.3 is.

### 2.3 The first cliff is 8000 bytes, and Varka is over it at 13 outputs

On this JDK (`25.0.4.1`, product build) `DontCompileHugeMethods` is `true` and
`HugeMethodLimit` is a develop flag, fixed at 8000 and not settable. A method
larger than that is **never compiled, by either tier**. From the JVM's own
`-XX:+PrintCompilation`, running the kernel 50000 rounds of 1024 rows:

* **4 outputs.** `epilogueDense` (2498 bytes) compiled at tier 3 and tier 4.
* **16 outputs.** All four `loopDense<g>` compiled at tiers 3 and 4.
  `epilogueDense` (9524 bytes) **does not appear at all**.
* **16 outputs, `-XX:-DontCompileHugeMethods`.** `epilogueDense` (9524 bytes)
  compiled at tier 3 and tier 4.

*Correction, 23 September 2026, from step 3: this section reads as if the
crossing were the admission check's discovery, and it was not. Task 24 made the
epilogue "one method over every output" as a deliberate decision, and tasks 44,
51, 54 and 70 each re-measured where a calendar-fields ladder crosses 8000
bytes - unshared 21 fits and 22 crosses, shared 49 - and pinned it in
`VarkaEmitterBudgetSuite`, whose comment already names the cost: "interpreted,
boxed vectors, on every batch whose length is not a lane multiple". What the
admission check added is the proof from the JVM's own output that the limit
does what the comment says, the measured cost of it, a second family crossing
at 13 and 14, and the reading that the fix had been deferred rather than
missed. The record had it; the check should have started from it.*

*Correction, 23 September 2026 (2.7): the tier-3 lines above are C1's attempts,
read from truncated log lines. Read in full, C1 refuses every method here over
about 1900 bytes - the 2498-byte epilogue and the 2455-byte loop method at four
outputs, and three of the four loop methods at sixteen - with `COMPILE SKIPPED:
out of virtual registers in LIR generator`, and each reaches tier 4 alone. What
this section rests on is unaffected: under 8000 bytes C2 compiles the
epilogue, over 8000 nothing does, and the control shows the rule is why.*

So the absence is the huge-method rule and nothing else. By 2.2's sizes the
masked epilogue crosses 8000 bytes at 13 outputs and the dense one at 14; the
JVM's output confirms the rule on the dense method at 16, and the masked
crossing is inferred from its size under that rule - the probe of section 5 pins
both methods at every rung rather than leaving the inference standing. **From
13 or 14 outputs, then, the epilogue runs interpreted for the life of the
JVM** - a JIT cliff at an eighth of the size of the failure the task was opened
on, reached by an ordinary projection, and silent: nothing counts it, logs it or
declines on it.

This is the same cliff 1.1 of the milestone plan found in Spark's default
configuration, which sets its own limit to 65535 while HotSpot stops at 8000.
Varka had it too.

### 2.4 Why the epilogue was left unbounded, and when it costs

The decision is written down beside the code (`VarkaBodyEmitter`, the `EPILOGUE`
arm): "One method for every output, not one per group: the epilogue runs a
single pass per batch, so `GROUP_BUDGET` - which exists to keep a *hot* method's
C2 compile cheap - has nothing to bound here." The reasoning is about compile
*cost*; the rule that bites is about bytecode *size*. A method that runs once
per batch still has to be compiled to be fast, and above 8000 bytes it never is.

When it costs is decided by the first lines of `emitEpilogue`: if the batch
divides evenly by the lane count it returns before any vector work - "the common
case, since the default `COLUMN_BATCH_SIZE` is 4096". Otherwise it runs **one
full lane group's body under a mask**: at 16 outputs, 918 `IntVector`
operations, interpreted, where the Vector API does not intrinsify and every
operation boxes.

So the cliff is expected to be invisible on round batches and severe on ragged
ones - and ragged batches are ordinary: the last batch of every partition, and
every batch `VarkaFilterExec` produces, since it compacts the selected rows into
batches of whatever length the predicate leaves. A projection over a Varka
filter meets a tail on nearly every batch. Section 6 is designed around exactly
this.

### 2.5 One figure in `VarkaEmitBudget`'s javadoc does not hold for this family

`GROUP_BUDGET`'s javadoc says "Past roughly 1900 bytes C1 refuses a loop method
... ('out of virtual registers in LIR')". Here C1 compiled `loopDense0` to
`loopDense2` at 3294 to 3964 bytes, at tier 3, without refusing. The refusal it
describes is a register-pressure limit, which correlates with bytes on the
family it was measured on and not on this one. It is recorded here rather than
acted on: it is one more place a byte figure stood in for something else.

*Correction, 23 September 2026, from 2.6.5 below: this section is wrong. The
tier-3 lines it read were C1's attempts; the same log, read without truncating
the line, follows each of the 3294- to 3964-byte loop methods with `COMPILE
SKIPPED: out of virtual registers in LIR generator`, while the 1536-byte one
compiles. The javadoc's figure holds for this family as well.*

**What the check would have rejected.** The premise that this task fixes a rare
67KB failure reached only by fuzzing volume. It fixes a common, silent
interpretation cliff at 13 outputs, and the rare failure is its far end.

### 2.6 The open questions, answered before any code

Reviewing this plan raised nine questions; the ones that could change the
design were answered from the tools and the JVM before a line of emitter code.
Three of the answers change 3.1, and one of them corrects 2.5.

**2.6.1 Nothing Varka ships crosses 8000 bytes today.** Every shape the bytes
oracle pins, emitted at both widths with each method's code length read through
`VarkaEmitterTestSupport.codeSize`:

| corpus | shapes | largest loop method | largest epilogue | any method over 8000 |
| :--- | ---: | ---: | ---: | ---: |
| the coverage table | 92 | 1288 | 1321 | 0 |
| the int fuzz sequence | 10000 | 5021 | 5731 | 0 |
| the long fuzz sequence | 10000 | 1486 | 1549 | 0 |

So there is no shipped shape running interpreted, and no single output the
regroup could not split. It also means **the fuzzer cannot find this cliff**:
its grammar draws kernels of one to three roots and never approaches the size
where it happens, which is why the 67KB tree took 35 million iterations. The
cliff is reached by many heavy outputs in one projection, which is a query
shape rather than a fuzz shape - task 172's realistic query is where it lives,
and task 179's standing fuzz job needs a wide mode to see it at all.

**2.6.2 Every group method sets up every output, so splitting a group cannot
shrink it.** `javap` of the 16- and 60-output classes: `loopMasked0` holds the
same five outputs and the same 313 `IntVector` calls in both, and at 60 it has
exactly 88 more `laload` and 88 more `invokestatic` (two
`VarkaVectorSupport.ofAddress` calls for each of 44 more outputs), 44 more
`iaload`/`istore` pairs (their literals), and 144 `aload_w` and 77 `astore_w`
that were not there before - past 255 local slots every load and store takes a
`wide` prefix. The source is `VarkaBodyEmitter`'s prologue,
`for (int o = 0; o < numOutputs; o++)`, run in every body mode: the driver, each
loop group and the epilogue materialize the destination segments of every
output in the kernel. That is the growth 2.2 saw with the op count fixed, and it
means a group's size has a term that scales with the whole kernel. **3.1's
regroup step would not converge** on a large kernel without first making each
group set up only what it writes.

**2.6.3 The early return has to stay in the epilogue.** At four outputs over
1024-row batches - sixteen lanes, so no batch ever has a tail - the epilogue was
compiled at tier 4 (2.3). It warmed up entirely on calls that returned before
any vector work. Moving the return into the driver, as 3.1 proposed, would make
it warm only on ragged batches, and a cached scan has one of those per
partition; the method would stay interpreted exactly where it is rarely needed
and then, when needed, be slow.

*Correction, 23 September 2026 (2.7): "warmed up" overstates what even batches
buy. A C2 compile whose profile never saw the tail branch taken turns that
branch into an uncommon trap, so the tail body is not in the compiled code: the
first ragged batch deoptimizes, runs interpreted, and the recompile that
follows includes it. The decision stands on narrower ground - one
deoptimization per shape, against an epilogue that in the other design is
never invoked often enough to be compiled at all where tails are rare - and
section 6 verifies it from the `made not entrant` lines rather than from this
paragraph.*

**2.6.4 When a kernel sees a ragged batch.** Both
`spark.sql.inMemoryColumnarStorage.batchSize` and the Arrow cache's
`spark.sql.execution.arrow.maxRecordsPerBatch` default to 10000, which divides
by every lane count from two to sixteen, so a cached scan is ragged once per
partition. After a Varka filter it is the reverse: `VarkaFilterExec` compacts
the selected rows into a fresh batch, so a Varka projection stacked on it sees a
tail on nearly every batch. `emitEpilogue`'s comment gives the default as 4096;
the number is wrong and the conclusion survives it, and the code task corrects
the comment.

**2.6.5 There are three thresholds, not one.** Read from `-XX:+PrintCompilation`
in full rather than field by field:

| method size | what the JIT does |
| :--- | :--- |
| up to about 1900 bytes | C1 compiles it, C2 later |
| about 1900 to 8000 | C1 refuses ("out of virtual registers in LIR") and it is interpreted until C2 lands |
| over 8000 | never compiled, at any tier |

The middle band is harmless for a loop method, whose backedges bring C2 in
quickly, and not for an epilogue, which has no loop and reaches C2 only by
invocation count - `Tier4InvocationThreshold` is 5000 on this JDK.

**2.6.6 Whether the cliff is worth a benchmark.** A scratch probe of the dump
tool - one run in one fork, with `--rows 1031` against `--rows 1024` - was run
for one purpose, to decide whether section 6 is worth building, and it says it
is: above 8000 bytes a ragged batch is slower than an even one for as long as
the kernel runs, and below it the difference fades once C2 has compiled the
epilogue. *Corrected on 23 September 2026 (2.7): an earlier draft of this
paragraph quoted the probe's magnitudes, and they are performance claims with no
committed file behind them, which `sql/varka/AGENTS.md` forbids. The magnitudes
are section 6's to establish; prediction 1 is scored against the benchmark and
not against this probe.*

**2.6.7 to 2.6.9.** A method's code length is readable today:
`VarkaEmitterTestSupport.codeSize` parses the class through the Class-File API,
so 3.1's measurement has a tested reader to follow. Bytecode size is the same at
every width to within a byte (2.6.1's two widths), so the cliff is
width-independent even though its cost is not. And OpenJ9, Graal and a user
running `-XX:-DontCompileHugeMethods` behave differently; that is a sentence for
`docs/sql-varka.md`, not a design input.

**What the dump tool gained.** `VarkaEmitDump` takes `--rows N` (1024 by
default) and reports the time of the second half of its rounds, which is what
2.6.6 and the ragged-batch half of 2.6.3 needed.

### 2.7 What the review of this plan corrected, 23 September 2026

A code review of the pull request found ten things, and all ten were right.
Where the old text would mislead whoever implements this, it is corrected where
it stands and says so; the list here is the record.

1. **3.3's op-count sums were wrong.** A group repeats the shared prefix, so the
   per-group epilogues sum to more than today's single one; the invariant that
   holds is per group, epilogue against loop.
2. **2.6.3's "warmed up" overstated it.** C2 compiles an untaken tail branch as
   an uncommon trap, so the first ragged batch deoptimizes. The decision stands
   on narrower ground and is verified in section 6.
3. **Section 4 still gave the driver the even-batch return** after 2.6.3 moved
   it back; corrected. Keeping it in each epilogue costs every even batch one
   call per group, and prediction 3 and risk 6 now say so.
4. **2.3 repeated 2.5's misreading** of C1's tier-3 attempts; corrected.
5. **`PLAN_MILESTONE_6.md` stated as observed what this plan infers** - the
   13-output masked epilogue - and is corrected in the same pull request.
6. **Section 6 never said whether its data had nulls**, and only a batch with
   nulls reaches the masked epilogue; it now runs both.
7. **A 7-row batch is not shorter than a lane group at 128 bits**; the test
   length is now 1.
8. **Task 170's scope conflicted with this plan's choice of 8000**;
   `PLAN_MILESTONE_6.md` 2.4 and row 170 are corrected so the two do not own
   one decision.
9. **The driver was never measured.** It sets up every output by design, so it
   grows with the kernel and a regroup cannot shrink it - 750 bytes at 16
   outputs and 2700 at 60, from `dev/varka_emit.sh` - which 3.1 step 2 and
   risk 7 now account for.
10. **2.6.6 quoted a probe's timings as performance claims** with no committed
    file behind them; they are removed and the magnitudes left to section 6.

### 2.8 What the benchmark found on its first run, 23 September 2026

Step 2 built `VarkaMethodSizeBenchmark` as section 6 specifies, and its smoke
run - one run at the host width, not a committed number - showed two things.

**The masked arm shows the cliff where the plan put it.** Ragged against even,
with nulls: flat at twelve outputs, about three times at thirteen, and three
times from there on. Masked first, at thirteen, as prediction 1 says; about
three times rather than more than an order of magnitude, as 2.6.6's probe
suggested. The committed file scores it.

**The null-free arm collapses from twelve outputs on, at both lengths, for a
reason that is not this task's.** The second group's dense loop method enters a
C2 deoptimization cycle - a new tier-4 compile installed every 250 milliseconds
and made not entrant on first execution at a `profile_predicate` trap on the
loop's back-edge - and runs interpreted for the whole measured window, a hundred
times slower. It survives every isolation this task could afford in an evening:
the rung alone, one batch length, no remainder call, the null-free arm alone,
the dump tool's exact inputs, explicit GC disabled. Turning off
`UseProfiledLoopPredicate` removes it. It is `PLAN_MILESTONE_6.md` 2.12, task
189, with the evidence and what was ruled out.

*Read again after the band, the same night: the cycle is not this harness
against that one, it is one JVM fork against another. Ten forks of the
benchmark put every null-free case from twelve outputs up in tier 3 - 2.4
against 178.2 M rows/s for the same twelve-output case - and the committed
512-bit file landed in the good mode at every rung while its 128-bit companion
landed in the cycle at every rung from twelve. `PLAN_MILESTONE_6.md` 2.12 has
the reading.*

**What it changes here.** Section 6's null-free arm above twelve outputs is tier
3 - unreadable from a diff, by the band's own verdict - until task 189 closes;
it can carry a prediction only in a fork that lands well, and a file cannot
promise which fork it was. The masked arm is tier 0 or 1 at every rung and both
lengths, and scores this task's predictions. The benchmark is committed as it
is, because a baseline that records what the JVM does to today's kernel - cycle
included - is the number both tasks improve against, and the file's header says
which rows mean what. The benchmark also gained three diagnostic knobs -
`varka.bench.rungs`, `varka.bench.chunks` and `varka.bench.nulls` - because the
isolation above needed each of them; a committed file always runs everything.

## 3. The design

### 3.1 The budget counts the method it emitted

`VarkaEmitBudget` keeps weight for what weight is good at and gains a measured
check for what it is not. The unit that decides whether a method is compiled is
the length of its code attribute, and the Class-File API knows it the moment the
method is built - so the budget reads it rather than estimating it.

1. **The epilogue is partitioned by the same groups as the loop.** The
   `EPILOGUE` arm stops emitting one method for every output and emits
   `epilogueDense<g>` and `epilogueMasked<g>` per group, with the `DRIVER` arm
   calling each after the loops, exactly as it calls `loopDense<g>`. The early
   return of 2.4 moves into the driver, so an even batch still costs one compare
   and no calls. This alone bounds the epilogue by what already bounds the
   loops, which is 2.2's third column: every loop method on the ladder is under
   5400 bytes.
2. **Every emitted method is measured against the limits.** After the class is
   built, `VarkaEmitBudget` reads each method's code length and the class's
   constant pool size and checks them against named limits: 8000 bytes per
   method (`DontCompileHugeMethods`), 65535 bytes as the class-file cap, 65535
   constant-pool entries, 255 parameter slots. A method over 8000 bytes is
   regrouped - the group split and the class emitted again - and a shape that
   still exceeds a limit after its groups are single outputs is **declined with
   a reason** naming the limit and the method, never thrown. *The driver is
   measured too (2.7 item 9): it sets up every output by design and gains a
   call for every group a regroup adds, so it cannot be regrouped smaller; a
   driver over a limit declines the shape.*
3. **Weight keeps grouping; bytes keep safety.** `GROUP_BUDGET` exists for C2's
   node and inlining budgets, which are about op count, and its argument in the
   javadoc stands. What it cannot do is bound size, and after this task it is
   not asked to. That also settles what task 148's under-count *is*: a
   grouping-balance defect, not a size risk, and 148 is done in its own row
   against that reading.

All of it is behind a `VarkaEmitOptions` switch, `methodByteBudget` (0 is
today's form, kept as the live reference under every test), and the default
flips in the last commit on 6.1's rule.

*Corrections, 23 September 2026, from 2.6. Step 1's early return does **not**
move into the driver: it stays inside each `epilogue<g>`, which is how the
epilogue warms up (2.6.3). A step comes **before** step 1: each group's methods
materialize only the destination segments and literals of the outputs that
group writes, so a group's size stops carrying a term that scales with the
whole kernel (2.6.2). Without it, step 2's regroup cannot converge; with it, the
split is what makes a method smaller. That step moves the bytes of every
multi-group kernel, so it is measured on its own before the epilogue changes.
And the warmup of 2.6.5 is a separate question from the 8000-byte limit: below
it an epilogue still waits for C2 by invocation count. Whether to fold the
masked tail into the loop method - which has backedges - or accept the warmup is
decided by 6's measurement rather than here.*

### 3.2 What is deliberately unchanged

* `MAX_CHAIN_DEPTH` and `MAX_FUSED_NODES` stay IR-level declines in `Analysis`;
  they bound the tree, not the class.
* `FUSED_CEILING` and the prefix-sharing rule in `groupOutputs` stay as they
  are; the byte check can only split a group they formed, never merge one.
* The typed decline reasons across the whole emitter are task 169's; this task
  adds the ones its limits need and asserts that none of them throws.
* Whether the method limit should be lower than 8000 is task 170's A/B. This
  task takes 8000 because it is the number the JVM's output above shows to
  matter.
* The vanilla arm and the published ladder are task 171's; 6 builds the file it
  extends.

### 3.3 Registered op counts

*Corrected on 23 September 2026 (2.7). An earlier draft said the per-group
epilogues' `IntVector` counts sum to today's single epilogue. They cannot: 2.2's
counts are a shared civil-from-days prefix of 38 operations plus 55 an output
(93, 258, 918 and `loopMasked0`'s 313 all fit), and a group repeats the prefix
its outputs share, which is the sharing risk 2 of section 7 names.*

The invariant that holds is per group: **each `epilogueMasked<g>` carries
exactly the `IntVector` count of `loopMasked<g>`**, and each `epilogueDense<g>`
that of `loopDense<g>`, since each is one lane-group body of the same outputs.
The loop methods' counts are unchanged by the switch. The total over the
epilogue methods is larger than today's single epilogue by the repeated
prefixes, and it is measured by `dev/varka_emit.sh` rather than predicted here.
Under the switch, every loop and epilogue method of every rung reads at most
8000 bytes, and the driver's size is reported beside them (3.1 step 2). Asserted
by 5.

## 4. Files

| file | what |
|---|---|
| `VarkaEmitBudget.java` | the limits, the per-method measurement, the regroup and the decline reasons |
| `VarkaBodyEmitter.java` | `EPILOGUE` per group, keeping the even-batch return inside each epilogue (2.6.3); `DRIVER` calling each epilogue |
| `VarkaLoopEmitter.java` | emitting `epilogueDense<g>` / `epilogueMasked<g>`, and re-emitting on a regroup |
| `VarkaEmitOptions.java` | `methodByteBudget`, in `canonical()` so the shape key sees it |
| `VarkaEmitterSuite` | the ladder, the op-count and size assertions, the decline |
| `VarkaEmittedBytesSuite`, `emitted_bytes.json` | unmoved under defaults; one arm for the switch (task 167's rule) |
| a new `VarkaMethodSizeBenchmark` and its results files | 6; task 171 adds the vanilla arm to it |
| `PLAN_MILESTONE_6.md` | rows 87 and 168 point here |

## 5. Tests, and what each is for

* **The ladder under the switch**, 4 to 60 outputs at 512 and 128 bits: every
  emitted method at most 8000 bytes, the `IntVector` sums of 3.3 unchanged, and
  answers equal to `VarkaReferenceEvaluator` at lengths 1024, 1031 and 1 - an
  even batch, a ragged one, and one shorter than a lane group at every width,
  including the two-lane long species at 128 bits. (An earlier draft said 7,
  which is a full lane group plus three at 128 bits, so the case of the loop
  never entered was not tested at that width.)
* **The JVM compiles every method.** A forked probe in the style of
  `VarkaAssemblySuite` runs the 16-output shape under `-XX:+PrintCompilation`
  and asserts every `loop` and `epilogue` method reaches tier 4 under the
  switch - the property this task exists for, asserted from the JVM rather than
  inferred from a size. Under the legacy form it asserts the opposite, which is
  2.3 pinned.
* **A shape at each limit declines with its reason and does not throw**,
  including one that reaches the class-file cap. That shape is found and pinned
  as an expression, not a fuzz coordinate (2.1).
* **The fuzz suite toggles every `with*` on `VarkaEmitOptions`**, so the switch
  is fuzzed the day it lands at no extra cost.
* `emitted_bytes.json` unmoved under defaults until the flip; at the flip, the
  moved rows are exactly the shapes whose epilogue exceeded 8000 bytes,
  explained shape by shape.

## 6. The measurement

`VarkaMethodSizeBenchmark`, its own file per the project's rule for a new
family. The ladder of 2.2 at 4, 8, 12, 13, 14, 16, 32 and 60 outputs, both forms
by explicit label (`single epilogue`, `epilogue per group`), at batch lengths
**1024 and 1031**, **null-free and with nulls** - a null-free batch runs only
the dense driver and its epilogue, and only a batch with nulls reaches the
masked one, so without both the masked crossing at 13 cannot appear - at 512 and
128 bits, regenerated with
`dev/varka_bench_regen.sh` and banded before anything is read. The control row
is the 4-output shape, whose epilogue compiles under both forms. **Committed as
its own pull request before the change**, so the baseline exists before the
improvement (the project's rule for a benchmark that does not exist yet).

### 6.1 Predictions, registered before the run

1. **On ragged batches the single epilogue falls off a cliff between 12 and 14
   outputs**, masked first: per-row cost at 1031 rows steps up at 13 and again
   at 14 by more than an order of magnitude against the 12-output rung, because
   a full lane group's body runs interpreted per batch.
2. **On even batches there is no cliff.** At 1024 rows the single epilogue
   returns before any vector work, so the ladder stays smooth through 14 and
   beyond and the two forms read within band of each other. If this fails, the
   early return is not doing what 2.4 says, which is a finding of its own.
3. **The per-group epilogue removes the cliff and costs nothing measurable.** At
   every rung and both lengths it is within band of the single epilogue where
   the single one compiles (4 to 12 outputs), and far faster where it does not.
   That includes the even batches, where it now makes one call per group
   instead of one in all (risk 6): the calls return at once and cost less than
   the band. *The last sentence was added by the review of 2.7, before any
   run.*
4. **The loops do not move.** Loop methods are unchanged by the switch, so on
   even batches every rung's rate is within band under both forms.

5. **Below 8000 bytes, the ragged cost is warmup only.** At 12 outputs the
   ragged penalty is visible in the first thousands of batches and gone once
   C2 compiles the epilogue; above 8000 it never goes. *Added 23 September 2026
   after 2.6.6, and so not registered before a run in the strict sense: a probe
   had already read it. It is kept apart from 1 to 4 for that reason.*

**The rule for the default:** flip when the per-group form is nowhere worse than
the single form beyond its band, at any rung, length or width - read on the
masked arm at every rung, and on the null-free arm only up to twelve outputs
until task 189 closes (2.8).

## 7. Risks

1. **Regrouping re-emits the class**, so a shape over budget costs two
   emissions. `VarkaEmissionBenchmark` prices it; the class cache means it is
   paid once per shape per JVM, but a pathological shape could regroup several
   times, which the decline bounds.
2. **Splitting the epilogue forfeits the tail's cross-output sharing** - the
   same trade the loop made, on a path that runs at most once per batch.
   Prediction 3 is where it would show.
3. **The interpreted cost may be smaller than predicted** if the tail's work is
   dominated by something other than the vector operations. Then the cliff is
   real and cheap, and the milestone's post must say so rather than lean on it.
4. **The constant pool is not reached by this family.** Its check needs a
   synthetic shape to be tested at all, and the task says so rather than
   claiming coverage it lacks.
5. **Measuring code length needs the method built.** If the Class-File API does
   not expose it without a full class build, the measurement moves after
   `ClassFile.build`, which is 3.1's step 2 as written.

6. **Keeping the even-batch return inside each epilogue costs every even batch
   one call per group** - twelve at 60 outputs - where today it costs one.
   Prediction 3 is where it would show; if it shows, the driver can guard the
   calls with one compare, at the price 2.6.3 describes.
7. **The driver grows with the kernel and cannot be regrouped.** It reads 2700
   bytes at 60 outputs, and `MAX_FUSED_NODES` keeps it far from 8000 today; a
   later raise of that cap has to re-read this line.

## 8. Sequencing

Each commit green on its own.

1. This plan, and rows 87 and 168 marked Planned.
2. `VarkaMethodSizeBenchmark`, legacy form only, committed with its band - its
   own pull request.
3. The measurement in `VarkaEmitBudget` and `dev/varka_emit.sh` reporting the
   constant pool beside bytes. No behaviour change; the oracle unmoved.
3a. Each group's methods materialize only their own outputs' segments and
   literals (2.6.2), behind the switch, with its own byte ladder: the first
   change that makes a group method smaller when it is split.
4. The per-group epilogue behind `methodByteBudget`, with 5's tests and the JIT
   probe.
5. The regroup and the declines, with the shape that reaches the class-file cap.
6. The benchmark regenerated with both forms, 6.1 scored, the default flipped
   and the oracle regenerated with its movement explained.

## 9. Outcome

### 9.1 The baseline, 23 September 2026

The legacy form's files are committed with their band and provenance
(`VarkaMethodSizeBenchmark-jdk25-results.txt`, `-128bit-results.txt`,
`-band.txt`, `-provenance.txt`: pinned, load 0.73 at start, canary clean), and
two of 6.1's predictions are about the legacy form alone, so they are scored
now. Per row, even length against ragged, from the committed files:

| outputs | masked, 512-bit | masked, 128-bit | null-free, 512-bit |
| ---: | :--- | :--- | :--- |
| 12 | 7.3 -> 7.4 ns | 23.7 -> 23.6 ns | 6.1 -> 5.6 ns |
| 13 | 8.1 -> **24.6 ns** | 26.4 -> **41.2 ns** | 6.0 -> 6.0 ns |
| 14 | 8.6 -> 26.1 ns | 26.9 -> 42.8 ns | 6.5 -> **23.0 ns** |
| 16 | 10.5 -> 30.4 ns | 32.8 -> 50.7 ns | 8.8 -> 27.1 ns |
| 60 | 41.5 -> 116.5 ns | 121.5 -> 189.5 ns | 34.8 -> 102.9 ns |

**Prediction 1: the rung held and the magnitude did not.** The masked epilogue
steps at thirteen outputs and the dense one at fourteen, exactly where 2.2's
byte sizes cross 8000 - and the null-free column scores it too, because this
fork landed in task 189's good mode. The step is about three times per row at
512 bits and about one and a half at 128, not "more than an order of
magnitude": the plan priced the interpreted epilogue as if the whole batch
paid for it, and it is one lane group's work per batch, interpreted, against
the whole batch compiled. 2.6.6's probe had already said as much.

**Prediction 2 held.** On even batches the ladder is smooth through thirteen,
fourteen and beyond on the masked arm at both widths, and on the null-free arm
in this fork; the single epilogue returns before any vector work, as 2.4 read
from the code.

**Prediction 5 is scored by the band rather than by these files**, and
differently than written: the ragged penalty below 8000 is not "warmup only",
it is absent from the committed numbers altogether, because every case ran
long enough for C2 to land; and the masked arm's bands are tier 0 and 1, so
what warmup there is sits inside them.

Predictions 3 and 4 are about the per-group form and wait for it. The 128-bit
null-free column is not shown: that fork landed in task 189's cycle at every
rung from twelve, and the band names those cases unreadable.

### 9.2 Step 3a: a group's bytes are the group's, 23 September 2026

Behind `methodByteBudget`, a loop method plans slots and sets up segments and
literals only for the outputs its group writes and the literals their trees
read; the driver and the epilogue keep the whole-kernel prologue, the one
because it zeroes every bitmap and runs the bitmap pass, the other until step 4
partitions it. From `dev/varka_emit.sh`, legacy against the switch, bytes with
the `IntVector` count beside them:

| method | 16 outputs | 60 outputs |
| :--- | :--- | :--- |
| `loopDense0` | 3294 -> 2956 (313) | 5072 -> 2956 (313) |
| `loopMasked0` | 3533 -> 3195 (313) | 5357 -> 3195 (313) |
| `loopMasked3` | 1657 -> 1210 (93) | 5597 -> 3910 (313) |
| `epilogueMasked` | 10314 -> 10314 (918) | 41338 -> 41338 (3338) |
| `runMasked` | 824 -> 824 | 2700 -> 2700 |

**The first group reads 3195 bytes at sixteen outputs and 3195 at sixty.** A
group's size is now a function of the group, which is the property step 5's
regroup needs and the property 2.6.2 found the legacy form could not give: the
same group had grown from 3533 to 5357 as outputs it never wrote were added
around it. The op counts are unchanged by construction, the epilogue and the
driver are byte-identical, and a kernel of one group is byte-identical whichever
way the switch is set. `VarkaEmitterBudgetSuite` pins all of that and runs the
sixteen-output ladder against the reference evaluator on both bodies at ragged
and even lengths under the switch.

**Two things the step found.** The suites' `forceMasked` - one reported null
over a full-set bitmap, to reach the masked body - is a lie the kernel is right
to act on at length 1: one null is then the whole batch, the input is dead and
every output null, and the first draft of this step's test read that as a bug
in the emission. It is recorded in `sql/varka/skills/testing-and-debugging.md`.
And the fuzzer draws 8, 24 or 32 for an int setter it does not know, which as
a byte budget would later mean "every method is over budget"; the setter now
has its own domain there, off or 8000.

### 9.3 Step 4: the epilogue per group, 23 September 2026

Behind the same switch the epilogue is one method per group beside its loop
method, `epilogueDense<g>` and `epilogueMasked<g>`, each planning and setting
up only its group as 3a's loop methods do, and each keeping its own even-batch
return (2.6.3). The driver calls them in turn after the loops. From
`dev/varka_emit.sh`, the `make_date` ladder under the switch, bytes with the
`IntVector` count beside them; the legacy column is 9.2's single epilogue:

| outputs | single `epilogueMasked` | `epilogueMasked<g>` under the switch | `runMasked` |
| ---: | :--- | :--- | :--- |
| 16 | 10314 (918) | 3257, 3269, 3844, 1236 (313, 313, 313, 93) | 824 -> 888 |
| 60 | 41338 (3338) | 3257 .. 3972, twelve of them (313 each) | 2700 -> 2924 |

The dense side reads the same way: 3006 to 3691 bytes a group at sixty outputs.
**Every method of the ladder now fits HugeMethodLimit at both widths through
sixty outputs**, which `VarkaEmitterBudgetSuite` asserts rung by rung, and each
`epilogue<g>` carries exactly its `loop<g>`'s `IntVector` count - the per-group
invariant 3.3 registered after the sum invariant fell. The sum is what 3.3 said
it would be: twelve epilogues at 313 are 3756 operations against the single
method's 3338, and the difference, 418, is the 38-operation civil-from-days
prefix repeated in eleven more groups. The driver gains one call per epilogue,
about twenty bytes each.

**The property the task exists for, read from the JVM.** `VarkaHugeMethodSuite`
forks a JVM under `-Xbatch -XX:+PrintCompilation`, runs the sixteen-output
ladder hot on ragged batches, and reads the tiers HotSpot prints for the
emitted class. Under the switch every loop and epilogue method reaches tier 4.
Without it the single `epilogueDense` and `epilogueMasked` never appear at any
tier while every loop method reaches tier 4 - `DontCompileHugeMethods`
refusing them silently, as 2.3 read by hand and this suite now pins.

**What this leaves for step 5.** On this family the regroup has nothing to do:
weight grouping already leaves every group's methods under 8000 bytes once
each carries only its own group. The regroup is for a group that is over the
limit on its own - a single output of great weight, or a `FUSED_CEILING` group
whose shared prefix is small beside its tails - and the decline is for a shape
that is over a limit as a single output. 6's benchmark scores predictions 3
and 4 on both forms.

### 9.4 Step 5: the regroup and the declines, 24 September 2026

`methodByteBudget` is now the limit its name says. After the class is built the
emitter measures it (`VarkaEmittedClass`), and a group with a loop or epilogue
method over the budget is split at its middle output and the class built again,
until every group's methods fit or the groups over budget are single outputs.
Weight groups first because weight is known before anything is built; bytes
decide because bytes are what the JVM reads. A shape still over a limit when no
split is left declines with `VarkaEmitDeclined`, an `IllegalArgumentException`
carrying the reason - the method, its bytes, the budget - and the outputs whose
own group cannot fit, so that a compiler can fuse the rest without them. The
driver is measured too and cannot be regrouped; a driver over the budget
declines naming no output. The class-file caps (65535 bytes of code in one
method, 65535 constant pool entries) are read from the same measurement.

**What the production limit does on this family: nothing.** At 8000 bytes the
`make_date` ladder's weight groups already fit after steps 3a and 4, so no rung
regroups and none declines, and `VarkaEmitterBudgetSuite` sees the regroup by
lowering the budget: at 1500 bytes, where a single output's methods read 1067
to 1236 and no pair fits, the sixteen-output ladder regroups from weight's four
groups to sixteen single outputs, every method under the budget,
the answers unchanged, and the emission byte-identical run to run. At 300 bytes
every output is stuck and the decline names all sixteen in order. At 2000 bytes
over sixty outputs the single-output groups fit and the driver, at 2806 bytes,
does not: the decline names `runDense` and no output.

**The shape that declines at the production limit is a single heavy output**: a
balanced `greatest` over thirty-two `add_months(d, k)`, sixty-three operations
under the sixty-four the IR admits, is one group whose masked loop method reads
25629 bytes. The legacy form emits it into methods HotSpot never compiles; the
budget declines it with `loopDense0 is 23505 bytes, over the method budget of
8000 (HugeMethodLimit): HotSpot never compiles it; ... output [0] cannot be
regrouped smaller`. Splitting inside one output is deliberately not attempted:
it would forfeit the register residency that is the point of fusing.

**The class-file caps are out of reach of any admitted shape**, in either form.
Sixty-two `add_months` outputs, the heaviest ladder the op cap admits, put the
legacy single epilogue at 49339 bytes, and under the budget no group exceeds a
few thousand. Risk 4 said as much for the constant pool; it holds for the
method cap too. The readings are pinned on a measurement built by hand, and the
test says so rather than claiming a shape covers them. The fuzz coordinate of
2.1 that once crossed 65535 is not recoverable; whatever shape it was, the
emitter now measures it before the JVM does.

**What is not in this step: the compiler.** `VarkaLoopEmitter.emit` declines;
`VarkaExpressionCompiler` admits by the weight caps alone and does not yet ask
the emitter about bytes, so a heavy single output is fused at plan time and
declines on the executor, where `VarkaKernelEvaluator` catches the
`IllegalArgumentException` as it always has and falls back per batch with an
emission failure counted. Making that a plan-time decline that EXPLAIN shows,
demoting exactly the outputs `VarkaEmitDeclined` names, is task 169's contract
(`PLAN_MILESTONE_6.md` 2.3) and is done there with the typed decline this step
provides. The fuzzer treats a size decline under the budget as an outcome and
asserts it carries a size; the dump tool prints it.

### 9.5 Step 6: both forms measured, 24 September 2026

`VarkaMethodSizeBenchmark` regenerated with both forms in one table per rung
(`-jdk25-results.txt`, `-128bit-results.txt`, `-provenance.txt`: pinned, load
0.57 at start, canary clean) and banded over ten runs. Rates in M rows/s, the
single epilogue against the epilogue per group, from the committed files:

| outputs | 512-bit masked, even | 512-bit masked, ragged | 128-bit masked, even | 128-bit masked, ragged |
| ---: | :--- | :--- | :--- | :--- |
| 4 | 441.9 -> 434.1 | 431.2 -> 442.8 | 123.7 -> 130.1 | 123.8 -> 127.7 |
| 8 | 207.3 -> 210.3 | 216.0 -> 220.9 | 63.9 -> 63.7 | 63.8 -> 63.6 |
| 12 | 134.0 -> 143.1 | 135.2 -> 142.3 | 42.6 -> 45.5 | 42.4 -> 45.5 |
| 13 | 120.9 -> 132.9 | 40.7 -> **131.7** | 38.1 -> 43.9 | 24.9 -> **44.0** |
| 14 | 116.0 -> 130.4 | 38.6 -> 127.7 | 37.3 -> 39.9 | 23.5 -> 40.0 |
| 16 | 94.4 -> 106.3 | 32.9 -> 104.9 | 31.0 -> 33.5 | 20.1 -> 33.6 |
| 32 | 46.0 -> 53.8 | 16.3 -> 53.0 | 14.7 -> 16.6 | 9.7 -> 16.6 |
| 60 | 24.1 -> 29.4 | 8.7 -> 28.9 | 8.3 -> 9.1 | 5.4 -> 9.2 |

**Prediction 3 held, and understated it.** The cliff is gone: on ragged batches
the per-group form reads within a few percent of its own even-batch rate at
every rung, where the single form had lost three quarters of its rate from
thirteen outputs at 512 bits and two fifths at 128. And it cost nothing on even
batches - it gained. From twelve outputs the per-group form is faster on even
batches too, by 7% at twelve and 22% at sixty, which no prediction had: the
loop methods are unchanged in what they compute, but each now sets up only its
group (3a), and the driver's twelve extra calls that risk 6 priced are cheaper
than the setup they replaced. At four outputs the per-group form reads 2%
lower on even masked batches at 512 bits and 5% higher at 128, both inside
those cases' tier-1 bands. The one cell that reads lower by more, four outputs
null-free ragged at 512 bits (618.8 -> 524.6), is a tier-2 case whose committed
spread is 23%.

**Prediction 4 fell, in the direction nobody minds.** The loop methods' op
counts are unchanged and their rates were predicted within band; from twelve
outputs the per-group form's even-batch rate is outside the band on the fast
side. The explanation is 3a's, not the epilogue's: a loop method that sets up
sixty segments and sixty literals before its loop is not the same compiled
method as one that sets up five, whatever its op count.

**Prediction 5 is superseded by the design.** It priced a warmup below 8000
bytes; the per-group epilogues are all below it, and the band puts the
per-group masked rows in tiers 0 and 1 at every rung but thirteen ragged (tier
2, a 10.8% spread), so whatever warmup there is sits inside the measurement.
No per-group row is tier 3: the rows the single form's deoptimization cycle
made unreadable read under the per-group form, at both lengths.

**Task 189 did not follow the per-group form into the fork.** The single form's
null-free rows at 128 bits from twelve outputs read 0.3 to 0.7 M rows/s on
ragged batches - the deoptimization cycle 2.12 of the milestone plan describes,
in this fork as in the baseline's - and the per-group form's read 40 to 56 in
the same JVM. One fork proves nothing about a bimodal effect, and the row is
recorded as a lead for task 189 rather than as a property: smaller loop methods
may change what the profiled loop predicate sees.

**The rule for the default is met** on the masked arm at every rung, length and
width, and on the null-free arm up to twelve outputs, so the default flips in
step 6b: `methodByteBudget` is `HUGE_METHOD_LIMIT` unless a caller says
otherwise, and the legacy form stays reachable at 0 as the reference the
differential tests and the benchmark's first arm keep.

### 9.6 Step 6b: the default, 24 September 2026

`methodByteBudget` defaults to `HUGE_METHOD_LIMIT`. Every kernel is now in the
form 9.2 to 9.4 built and 9.5 measured; the form before this task stays
reachable at 0, as the reference `VarkaMethodSizeBenchmark`'s first arm and the
differential tests measure against, and the fuzzer draws both.

**What moved, and why all of it.** Section 5 expected the bytes oracle's moved
rows at the flip to be exactly the shapes whose epilogue exceeded 8000 bytes.
That was written before 3a: under the default a single-group kernel's
epilogues are `epilogueDense0` and `epilogueMasked0`, a multi-group kernel's
methods carry only their group's setup and one epilogue per group, and so
every row of `emitted_bytes.json` moves, by name where not by bytes. The
oracle is regenerated and the option inventory lists both values of the
budget. The suites that name a single-group kernel's epilogue read the
suffixed names; the tests that pin the earlier form's facts - where its single
epilogue crossed 8000 bytes under prefix sharing, the legacy arms of 9.2 to
9.4's ladders, the heavy single output it emitted into an uncompilable
method - say `withMethodByteBudget(0)`, and `VarkaEmitterTestBase.epilogueSize`
measures that form on purpose. `VarkaEmitterParityBenchmark`'s task-44
section, which prices that crossing under sharing, pins both arms at 0: under
the default neither arm crosses and the section would price nothing.

**Committed benchmark files other than this task's are unchanged.** They
reflect the default at their regeneration, as every emitter change before
this one left them, and move when next regenerated; the parity file's
crossing section is the one whose meaning the flip would have changed, and
it is pinned instead.

**What the task leaves.** Task 169: `VarkaLoopEmitter.emit` declines a shape
no split can fit, and the compiler does not yet ask it, so such a shape is
fused at plan time and falls back on the executor through the evaluator's
existing catch (9.4). Task 170: whether a limit below 8000 - C1's, about
1900 - earns the calls it adds. Task 189: 9.5's lead, that the per-group form
did not enter the deoptimization cycle in the fork the single form did. Rows
87 and 168 are done.
