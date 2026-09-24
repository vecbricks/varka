# Varka Milestone 6 Plan: the compiler's foundation

*Opened 23 September 2026, when milestone 5 closed. The owner's brief: "focus on
promotion of Varka in social media and in parallel build foundation of the
compiler. How about to solve the problem of 64k + improve the compiler itself +
test/benchmarks infra", and, for the ending: "we will write a blogpost about the
issue that vanilla Spark cannot solve but Varka has fixed. This is huge win over
Spark and other native boosters."*

This is a task plan, not a scope catalogue. `SCOPE_MILESTONE_7.md` remains what
it is - the coverage survey, its fifty items and the TPC-DS/TPC-H census - and
the coverage spine it argues for (decimal lanes, aggregate wiring, grouped
aggregation, string keys, a first end-to-end TPC-H q6 number) **moves to
milestone 7 unchanged**. Nothing in that survey is withdrawn or reordered; it
simply is not this milestone's work. The items this milestone does take are
named below with the item number they come from, so every citation resolves.

## 1. The question, and what "done" means

Milestones 4 and 5 both ended in a public message about *speed*: the date family
at 10x, then the `TIME` type at 31.8x on a full-width machine. Both messages are
ratios, and a ratio invites the reader to argue about the baseline.

This milestone ends in a different kind of claim, and it is the owner's: **a
thing vanilla Spark cannot do, that Varka does.** Not faster - possible at all.
The candidate, established on the day the milestone opened, is the one the
project already has a reproducer for.

### 1.1 The claim, and the evidence for it in Spark's own source

When whole-stage codegen produces a method that is too large, Spark abandons
codegen for the whole subtree and returns to row-at-a-time execution:

    // WholeStageCodegenExec.scala
    if (compiledCodeStats.maxMethodCodeSize > conf.hugeMethodLimit) {
      logInfo(log"Found too long generated codes and JIT optimization might not
        work: ... and the whole-stage codegen was disabled for this plan ...")
      return child.execute()
    }

It cannot do better, and the reason is in the design rather than in the code.
Spark generates **Java source** and hands it to Janino, so it does not know the
size of the bytecode until after it has compiled it. Its own configuration
documentation says so:

> `spark.sql.codegen.methodSplitThreshold` - "The threshold of source-code
> splitting in the codegen. When the number of characters in a single Java
> function (without comment) exceeds the threshold, the function will be
> automatically split to multiple smaller ones. **We cannot know how many
> bytecode will be generated, so use the code length as metric.**"
> (`SQLConf.scala`, default 1024 characters)

So the split is a heuristic in the wrong unit - characters of source standing in
for bytes of bytecode - and when the heuristic is wrong the fallback is total.

There is a second cliff, earlier and quieter. `CodeGenerator` carries
`DEFAULT_JVM_HUGE_METHOD_LIMIT = 8000`, the size past which HotSpot declines to
JIT a method at all, while `spark.sql.codegen.hugeMethodLimit` defaults to
**65535**, eight times higher. The same config doc concedes the gap: "When
running on HotSpot, it may be preferable to set the value to 8000 to match
HotSpot's implementation." In the default configuration a query can keep
whole-stage codegen and silently lose the JIT.

**And the method size is one of three cliffs, not the whole story.** Read
together they say something larger than any of them alone, which is that the
limit Spark keeps meeting is not any particular number but its own inability to
see what the JVM counts.

* **`spark.sql.codegen.maxFields`, default 100.** `isTooManyFields` deactivates
  whole-stage codegen for a schema with more than a hundred fields, nested ones
  included. This is not a guess and not a failure: a wide table simply does not
  get codegen. It is also the most common of the three in real schemas.
* **The method size, 65535**, guessed with 1024 characters of source, above.
* **The constant pool, 65536 entries.** `CodeGenerator` carries
  `MAX_JVM_CONSTANT_POOL_SIZE = 65535` and makes the same confession a second
  time, in the same words: *"The number of named constants that can exist in the
  class is limited by the Constant Pool limit, 65,536. **We cannot know how many
  constants will be inserted for a class**, so we use a threshold of 1000k bytes
  to determine when a function should be inlined to a private, inner class"* -
  `GENERATED_CLASS_SIZE_THRESHOLD = 1000000`. A second JVM limit, a second proxy
  in the wrong unit, a second number chosen because the real one is unknowable
  from source.

**Varka emits bytecode directly through the Class-File API.** It knows the exact
size of every method and the exact contents of the constant pool as it builds
them, and it reads the columns a projection names rather than the width of the
schema it sits in. All three limits are things it can count.

The sentence the post is built on, in its general form: *Spark generates source,
so it cannot measure what the JVM enforces; it guesses with proxies and gives up
when a guess fails. Varka generates bytecode and can count.*

### 1.2 What Varka has to fix first, to be allowed to say it

Varka has the same defect today, at a harder threshold, and the milestone cannot
make the claim until its own house is in order. `VarkaLoopEmitter.emit` built a
**67244-byte `epilogueMasked`** for a nested `make_date` tree and the Class-File
API refused it; it reproduces in one iteration,
`-Dvarka.fuzz.seed=2026092800 -Dvarka.fuzz.only=73411`. That is milestone 5
section 2.18, task 87, kept for a future fix at the owner's request. It is this
milestone's first task, and section 2.1 is its design.

Worse, Varka's caps make the *same category error* Spark's do, in a different
wrong unit. `GROUP_BUDGET` counts **weight**, and `MAKE_DATE_WEIGHT` is 60
against a `GROUP_BUDGET` of 16, so the weight-to-bytes ratio spans more than an
order of magnitude across the op set. A budget counted in weight cannot bound
bytes. The difference between Varka and Spark here is not that Varka got it
right; it is that Varka *can* get it right, because it can measure.

*Correction, 23 September 2026, from task 87's admission check
(`PLAN_TASK_87.md` 2): the reproducer above no longer reproduces - the fuzz
grammar has gained nodes since 8 September and a new node reshuffles the whole
sample, so the coordinate now draws a tree that emits cleanly. More importantly,
the 67KB failure is the far end of the problem, not the problem. On a product
JDK `DontCompileHugeMethods` is on and `HugeMethodLimit` is fixed at 8000. The
JVM's own `-XX:+PrintCompilation` shows a projection of sixteen `make_date`
outputs whose 9524-byte dense epilogue it never compiles at any tier, while it
compiles every loop method; by their sizes the masked epilogue crosses 8000 at
thirteen outputs and the dense one at fourteen. So Varka has had exactly the
silent default-config JIT cliff of 1.1, at an eighth of the size of the failure
this section describes.*

### 1.3 Done when

1. **No shape Varka admits can fail to emit.** Every emitted method is bounded
   in bytes by construction, and a shape the caps decline is declined at compile
   time with a reason rather than throwing and degrading silently.
2. **The size ladder is committed**, showing vanilla Spark's step against
   Varka's line, on a machine and a JDK named in the file.
3. **One realistic query** - not a synthetic 200-expression projection - where
   Spark logs "the whole-stage codegen was disabled for this plan" and Varka
   does not, with both arms measured.
4. **The census is complete** (task 188): every place vanilla Spark's codegen
   gives up is enumerated from its source, with Varka's answer to each - immune,
   solved, or declined with a reason - rather than the handful anyone happened
   to notice.
5. **The post is published**, with the comparison to native accelerators
   grounded in the record this repository already holds rather than asserted.
6. Beside the spine: the compiler is more legible than it was, the CI and
   benchmark infrastructure stops costing manual work, and promotion has run
   continuously rather than once at the end.

## 2. Design

### 2.1 The epilogue past 64KB (task 87)

*Moved here from `PLAN_MILESTONE_5.md` 2.18 and `SCOPE_MILESTONE_7.md` item 15,
text and task number unchanged; that section stays where it is and keeps the
full observation, the reproducer and the analysis of why three caps missed it.*

The short form. `MAX_CHAIN_DEPTH` (16) bounds one output's depth,
`MAX_FUSED_NODES` (64) bounds the distinct ops in the kernel, and `GROUP_BUDGET`
(16) bounds one *loop* method - the emitter partitions `loopDense<g>` and
`loopMasked<g>` accordingly. The epilogue is not partitioned: `emitBody` is
called once with `group = -1`, so every group's ops land in a single
`epilogueMasked`. The one cap meant to keep a method small is the one that does
not apply to the method that broke.

**The task.** Partition the epilogue the way the loop is partitioned, *or* give
the emitter a byte budget it checks before handing the class to the Class-File
API - and in both cases turn the failure into a decline with a reason. Section
2.2 is the argument for doing the second, generally, rather than the first,
locally; this task is where the choice is made and measured, because
partitioning adds a call per group to a body that runs once per batch and the
epilogue is the tail, so the per-batch cost lands hardest on short batches.

**Admission check.** The fuzz iteration above as a pinned emitter test,
declining with a reason instead of throwing; every shape that fits today
emitting the same bytes, against the pinned line map and the `codeSize`
assertions; `MAX_FUSED_NODES`' javadoc corrected to say which methods its
guarantee covers.

### 2.2 One budget, counted in bytes, over every emitted method (task 168)

*Planned with task 87 in `PLAN_TASK_87.md`, on the owner's decision of 23
September 2026: the epilogue is the method nobody counted, so the two are one
mechanism, and planning them apart risked 87 building what 168 replaces.*

Fixing 2.1 alone patches one method. The finding underneath it is that the
emitter has five overlapping limits - `MAX_CHAIN_DEPTH`, `MAX_FUSED_NODES`,
`GROUP_BUDGET`, the `FUSED_CEILING` escape, and now whatever 2.1 adds - each
bounding a different quantity, with nothing making them compose. The 64KB bug is
what that looks like when a method nobody counted grows.

**The task.** One budget abstraction that every emitted method passes through -
the loop methods, the epilogue, the prologue, and anything a later milestone
adds - counted in the units the JVM enforces. Each cap keeps its own decline
reason, so a refusal says which bound it hit rather than that something was too
big.

**Bytes are not the only limit, and an earlier draft of this section said they
were.** A class file has several, and 1.1's reading of Spark says why that
matters: the engine that cannot count them is the one that has to guess at each
one separately. The budget covers, at minimum, the method's bytecode size
(65535), the constant pool (65535 entries, which a class of many distinct
divisors and magic constants can approach from a direction method size does
not), and the method parameter count (255, which the emitter's own signatures
bound today only by convention). Where a limit is unreachable by construction,
this task records *why* it is unreachable rather than leaving it uncounted -
that sentence is what a later lane type will need.

What this buys beyond 2.1: a second case is cheap. Sixty-four-bit lanes widen
every node, which is why milestone 5 warned this would bite sooner, and the next
lane or output type should not need its own bug first.

**Admission check.** A shape at each cap, declining with the right reason;
`dev/varka_emit.sh --table` reporting the cost per method against each limit, so
the budget is inspectable rather than only enforced; the bytes oracle unmoved
for every shape that fits, which is what says the budget changed no emission it
admits; and, for any JVM limit the task concludes is unreachable, the argument
for that written where the next reader will find it.

### 2.2a The weight the budget counts is wrong for a division (task 148)

*Moved here from `PLAN_MILESTONE_5.md` 2.84 and `SCOPE_MILESTONE_7.md` item 39,
text and task number unchanged. It was recorded there as "an emitter budget
finding on the int lane"; it is this milestone's subject rather than a stray
note, and its own last clause says why - the ops it under-counts land "in the
epilogue, which is the one method no byte budget bounds".*

`weightOf` approximates lane operations: a checked `IntArith` is 5, and
`ConstDivide` falls through to the default **1** while its int-lane conversion
form emits **seven**. Sixteen of them fit one loop method under `GROUP_BUDGET`,
carrying about a hundred and twelve operations into it and the same again into
the epilogue. Task 88 step 3 corrected the long lane, where the magic form is
fourteen, and deliberately left the int lane alone because correcting it moves
`emitted_bytes.json` and a byte movement wants a change whose subject it is.

**This milestone is that change.** The task is now two questions rather than
one. Correct the weight to 7, regenerate the oracle and explain the movement
shape by shape - and then ask whether weight should keep bounding size at all
once 2.2's byte budget exists. If bytes bound the method, weight's remaining job
is grouping *balance*, not safety, and an under-count is a scheduling defect
rather than a correctness risk. Either answer is worth writing down, because it
decides whether `GROUP_BUDGET` keeps two jobs or one.

**Admission check.** The regenerated oracle green with the movement explained;
a statement in `weightOf`'s javadoc of what weight is for after 2.2, which is
the sentence the next reader needs.

### 2.3 No exception escapes the emitter (task 169)

`sql/varka/AGENTS.md`'s ghost-fallback contract asks that a shape Varka cannot
serve be declined with a reason. The 64KB path does not: it throws, and
`VarkaKernelEvaluator.fusedRunner` catches it by name, logs a warning, counts
`numEmissionFailures` and emits an `EMISSION_FAILURE` event. The answers are the
row engine's and correct; the costs are that the kernel is built and thrown away
once per task, and that a shape inside the documented caps degrades silently.

**The task.** Every refusal inside the emitter becomes a typed decline carrying
a reason, and a test pins the reason. The evaluator's catch-by-name stays as a
last resort but should become unreachable for size, which is the property to
assert: a fuzz campaign over the shape space produces declines and no emission
failures.

**Admission check.** `numEmissionFailures` stays at zero across a fuzz campaign
that produces declines; each decline reason is pinned by a test that names the
shape that produces it.

### 2.4 Eight thousand, not sixty-five thousand (task 170)

*Narrowed on 23 September 2026 by task 87's admission check
(`PLAN_TASK_87.md` 2.3 and 2.6.5). The choice this section sets up, 8000 against
65535, is not a choice: a method between the two is never compiled on a product
JDK, so a budget of 65535 would admit exactly the methods that run interpreted,
and task 87 takes 8000 from the JVM's own output. What is left for task 170 is
whether a lower limit earns its extra calls - C1 refuses a method from about
1900 bytes, and one that waits for C2 runs interpreted meanwhile.*

HotSpot refuses to JIT a method above `HugeMethodLimit`, 8000 bytes.
Spark's own limit defaults to 65535 and its documentation admits the gap. A
method that fits the class file but not the JIT is a silent performance cliff,
and nothing in either engine currently looks for it.

**The task.** Measure whether Varka's budget should target 8000 rather than the
class-file cap. It is a trade and it is measurable: a lower bound means more
methods and more calls per batch, a higher one risks a loop the JIT will not
compile. The A/B is the size ladder of 2.5 at both budgets, with the JIT's own
output as the instrument - `-XX:+PrintCompilation` naming the methods it
declined - rather than a timing alone.

This is also the sharpest half of the public claim: Spark's *default*
configuration does not attempt it.

### 2.5 The size ladder, and the figure (task 171)

*Absorbs milestone 4's row 44, which asked for a ladder that can see the problem
(4095 and 63 rather than only 4096) and the epilogue measured against
`HugeMethodLimit`.*

**The task.** A benchmark whose x-axis is the number of expressions in one
projection and whose y-axis is per-row time, measured on both arms: stock Spark
and Varka. Vanilla is expected to be a step function, flat until its generated
method crosses a limit and then a cliff where codegen is disabled; Varka is
expected to be a line. The rungs are chosen to straddle the thresholds, not to
be round numbers.

Both cliffs are in scope: the 8000-byte one, where the plan keeps codegen and
loses the JIT, and the 65535-byte one, where Spark logs the disable and returns
to `child.execute()`. The vanilla arm records Spark's own log line per rung, so
the claim is Spark's statement about itself rather than an inference from a
timing.

**Admission check.** The ladder committed as its own results file with its band,
per the project's rule that a new benchmark family gets its own file; the
vanilla arm's disable logged and quoted; the rung where the step happens named.

### 2.6 One realistic query (task 172)

A synthetic wide projection proves the mechanism and convinces nobody. The post
needs a shape a reader recognises: a wide table, or a deep `CASE WHEN` tree of
the kind the TPC-DS survey counted 127 of, where Spark's heuristic fails in
ordinary use.

**The task.** Find and commit one such query, with the vanilla side's disable
logged. If none can be found that is honestly realistic, that is a finding and
the post says so - the claim narrows from "queries hit this" to "shapes inside
Varka's documented caps hit this, and here is the class of them", which is still
true and still Spark cannot fix it.

**1.1's third cliff makes this easier than the risk of section 6 assumed.**
`spark.sql.codegen.maxFields` deactivates whole-stage codegen for a schema with
more than a hundred fields, which needs no deep expression tree and no
adversarial shape at all - a wide table is enough, and wide tables are ordinary.
So the realistic query is probably a wide one rather than a deep one, and the
search starts there. Varka is expected to be untouched by that cliff because it
reads the columns a projection names rather than the width of the schema around
them, and **that expectation is a prediction this task registers and scores**
rather than an assumption: a wide-schema shape where Varka also declines would
be the more interesting outcome.

**Admission check.** The query, its schema, both arms' numbers, and Spark's log
line, committed; or a written finding that the realistic case is narrower than
expected, with what was searched.

### 2.7 Improve the compiler itself (tasks 173, 174, 175)

Three items from `SCOPE_MILESTONE_7.md`, taken because the post brings readers
who will open the source, and because a foundation milestone is the right time.

* **Task 173, a disjointness test for the compiler's family chain** (item 41).
  The compiler dispatches expression families in sequence and nothing proves two
  families cannot both claim a node. A correctness invariant, and cheap.
* **Task 174, the emitter's shared constants out of the facade** (item 40), with
  javadoc position checked (item 43). Small, and it is what makes the code
  readable to someone seeing it first.
* **Task 175, port `VarkaIntervalCompiler` to Java** (item 42), the first family
  port. It says whether the Java-Catalyst direction is real or aspirational.
  **One family only**, with the friction recorded, before anything commits to
  the rest.

* **Task 184, the refactoring tools under `dev/`** (item 46). Task 159 was done
  with three scratch scripts any later refactor or port wants: a member map
  (every top-level member of a Java or Scala file with its line range, doc
  comment and callees), a call graph between named groups of members, and an
  unused-import stripper driven by scalac's own `-Wunused:imports` errors, which
  a moved file always needs because it inherits every import of its source. This
  is scheduled *before* task 175 rather than after: a plan's member list should
  be generated from the map rather than written from memory, which is how task
  159's plan came to name an `emitBoundedDivide` that never existed. Its "done
  when" and task 183's meet at `PLAN_TASK_TEMPLATE.md`, which points at the
  member map for a refactor's inventory.

Item 47, one place per node, is deliberately **not** here: its own text says it
reopens when a real second case arrives, and writing the abstraction first is
the thing the owner's constraint against abstractions for types that do not
exist yet forbids.

### 2.8 Test and benchmark infrastructure (tasks 176-179, 182)

* **Task 176, a CI queue script** (item 44). The fork runs 20 jobs in parallel,
  so builds are assigned to one pull request at a time in merge order - by hand
  today, cancelling and re-running. That is a script.
* **Task 177, a scoped CI path for oracle-proven refactors** (item 45). The
  bytes oracle proves a refactor byte-identical in one morning; CI does not know
  that and runs everything anyway.
* **Task 178, bands on demand.** Twelve of eighteen benchmark families have no
  band. Task 145 showed the cost: an apparent 23% regression that was a 30.6%
  band, and a family that could not be banded at all because
  `dev/varka_bench_repeat.sh` passed its module straight to sbt. The rule to
  write down is the one that emerged by accident - *a family gets its band the
  first time someone has to read a move in it* - plus the tooling to make that
  one command.
* **Task 182, extend Spark's own benchmarks rather than only writing our own**
  (item 8). Every committed Varka number is the fork's own, which means every
  claim is measured against a baseline this project invented. Spark ships
  benchmarks with results files in the tree; extending those makes a number
  checkable against something upstream already publishes. It is scheduled here
  rather than with the coverage milestone because the size ladder of 2.5 is the
  first thing that would use it, and because it is cheap.
* **Task 179, the fuzzer as a standing job.** Task 87 came out of a
  35-million-iteration run. The last campaign was misconfigured against the
  suite's 20-minute `failAfter`, so 47 of 48 "failures" were timeouts carrying
  no information. A correctly configured nightly campaign is how the next task
  87 is found before a user finds it.

### 2.9 Promotion, continuously (task 180)

Milestones 4 and 5 treated promotion as a terminal event. This milestone runs it
throughout, and the material already exists: milestone 5 produced four findings
that are each a post and none of which is a speed claim.

1. C2's late-inline pass gives up on `VectorSupport::loadWithMap` on some hosts
   and inlines the Java fallback in its place, so a failed intrinsic leaves **no
   call to grep for** - a short scalar body with zero calls
   (`PLAN_TASK_165.md`).
2. `PrintIntrinsics` prints the same refusals on a host that packs and a host
   that does not, so the obvious instrument cannot answer the question.
3. The per-fork JIT lottery: a case that moves 30.6% across ten runs with
   nothing changed, and how to tell that from a regression.
4. A 64-bit division is three operations with AVX-512 and fourteen without,
   which is why the lowering beat the lane count 4.7x to 2x - the opposite of
   what the date chains said.

**The task.** A cadence rather than a document: the cross-posts the milestone 5
piece never got (Show HN, r/java on the JDK 25 and Vector API angle,
r/apachespark), one post from the list above roughly every two weeks, and a
living benchmark page on `vecbricks.github.io` regenerated by the existing
workflow rather than written by hand, so each post has a permanent link and
"reproducible" is visible instead of asserted.

**Task 183, onboarding** (item 38), is the half of promotion that decides
whether attention becomes contributors or stars. It was opened on 21 September
from the first "can we contribute?" under the public post and deferred here on
the owner's decision. Three pieces, none expensive:

1. **The task tables mirrored as GitHub issues.** Issues are enabled on the
   fork. A script reads the current milestone's task table, opens one issue per
   row marked Scoped or Planned, and closes it when the row turns Done, quoting
   the outcome. The table stays the source of truth; the issues are the view
   GitHub shows. The status vocabulary is not uniform - milestone 5's table used
   Scoped, Planned, Done, DONE, Withdrawn, Moved, Partly done and landed, with
   rows carrying no marker at all - so the script's first job is to state the
   rule it applies and list the rows it cannot classify.
2. **A hardware census page and its issue template.** `dev/varka_datapath.sh`
   already prints a machine's CPU, flags, `UseAVX`, `MaxVectorSize` and the
   datapath readings. A `HARDWARE.md` seeded with the laptop and the runner
   census, plus an "add my machine" template asking for that output, is a first
   contribution that needs no build and fills the AVX2 and Arm gaps the
   committed tables have.
3. **Templates**: one for taking a task, and `PLAN_TASK_TEMPLATE.md`, which
   task 184 also writes into.

### 2.11 The cliffs, researched one at a time (tasks 185-188)

1.1 lists three places Spark's whole-stage codegen gives up, and a fourth turned
up by reading one level deeper. That is the shape of the problem: they are found
by looking, not by knowing, so each becomes a task of its own with the same two
halves - **establish what vanilla does, reproducibly, from its own output; then
solve it in Varka, or record why Varka cannot reach it.** A claim built on three
examples somebody happened to grep for is a claim a reader can extend and
embarrass; a claim built on a census is not.

The fourth, found on 23 September 2026 and the sharpest of them:
`CodeGenerator.splitExpressionsWithCurrentInputs` does not split at all inside
whole-stage codegen.

    if (INPUT_ROW == null || currentVars != null) {
      expressions.mkString("\n")
    } else {
      splitExpressions(...)
    }

Above that branch sits a one-line `TODO` comment asking for whole-stage codegen
to be supported. (The marker is described rather than quoted because this
repository's own pre-commit hook refuses one in a plan file, which is the rule
working: a marker here would read as Varka's work rather than Spark's.)

`currentVars != null` *is* the whole-stage case. So in the one place where the
method-size cliff matters most, the mitigation for it is switched off and the
generated method simply grows until the 65535 check fires and the subtree loses
codegen. The 1024-character heuristic of 1.1 is not even applied there.

* **Task 185, the schema-width cliff.** `spark.sql.codegen.maxFields`, default
  100, and `isTooManyFields` counting nested fields. Establish the row count and
  schema at which vanilla deactivates codegen, from Spark's own log; then the
  Varka side, where the expectation is immunity because a projection reads the
  columns it names rather than the width of the schema around them. That
  expectation is registered as a prediction and scored.
* **Task 186, the method-size cliff.** 65535 bytes, guessed with 1024
  characters of source - and, inside whole-stage codegen, not guessed at all.
  Establish both: the shape that trips it, and whether the no-split path above
  is why it trips so much earlier than the character threshold implies. Varka's
  answer is tasks 87, 148 and 168; this task is the vanilla half and the
  comparison.
* **Task 187, the constant-pool cliff.** 65535 entries, guessed with 1,000,000
  bytes of source (`GENERATED_CLASS_SIZE_THRESHOLD`). Establish a shape that
  reaches it - this is the limit a class of many distinct constants approaches
  from a direction method size does not, which is also why task 168 has to count
  it on the Varka side rather than assume bytes cover it.
* **Task 188, the census.** Enumerate *every* place Spark's codegen gives up, in
  the source rather than from memory: these four, the fallback path to
  interpreted evaluation, `MAX_JVM_METHOD_PARAMS_LENGTH`, whatever a
  Janino compile error does, and anything else the read finds. **Done when** the
  list is complete enough that the next person to read `CodeGenerator` and
  `WholeStageCodegenExec` end to end adds nothing to it, and each entry says
  whether Varka is immune, has solved it, or declines with a reason.

**Why these are research tasks and not assertions.** Every one of them is a
statement about another project's behaviour, published under this project's
name. The house rule that a number must trace to a committed results file
applies here to a *claim*: it traces to Spark's own log line, its own source at
a named revision, and a reproducer in this tree. Task 188 is what keeps the post
from being a list of three things I noticed.

**Admission check, for each.** A committed reproducer that makes vanilla say it
out loud - the log line, or the generated code's own size - with the Spark
revision named; the Varka arm beside it; and, where Varka is immune, the
argument for *why*, in the plan, not in a commit message.

### 2.12 A dense loop in a C2 deoptimization cycle at twelve outputs (task 189)

*Opened 23 September 2026 from task 87's benchmark, which met it while measuring
something else. Recorded here rather than absorbed there, because it is not a
method-size cliff and task 87's mechanism would not touch it.*

**The observation.** `VarkaMethodSizeBenchmark`'s null-free arm collapses from
twelve `make_date` outputs on, at both batch lengths alike - about a hundred
times slower than the arm with nulls over the same kernel - and the JVM's own
output says why. Under `-XX:+PrintCompilation` and `-Xlog:deoptimization=debug`,
the second output group's dense loop method, `loopDense1`, is compiled at tier 4
and made not entrant **eighteen times in one benchmark case**, every time with
`profile_predicate maybe_recompile` at the loop's back-edge (the `goto` back to
the loop head; the loop head itself traps four times at its own predicate and
stops, which is `PerBytecodeTrapLimit`). The timeline is a cycle with the period
of the method's own C2 compile: a compile at 3703 ms, not entrant at 3964; a
compile at 3971, not entrant at 4228; and so on every 250 to 260 milliseconds to
the end of the case. Each new version traps on first execution, so the method
runs interpreted for the whole window. `-XX:-UseProfiledLoopPredicate` removes
every one of these traps and the method compiles once. The masked loop over the
same kernel does not enter the cycle at all.

**What it is not**, each ruled out by a run rather than an argument. Not the
other kernels in the JVM (it reproduces with the twelve-output rung alone). Not
mixed batch lengths (it reproduces at 1024-row calls only, and with a row count
that leaves no remainder call). Not the masked path having run over the same
kernel first (it reproduces with the null-free arm alone). Not the kernel's
inputs (it reproduces with the dump tool's exact data values, a real all-ones
validity bitmap and 1024-row buffers). Not the harness's `System.gc()` between
cases (`-XX:+DisableExplicitGC` changes nothing).

**It is nondeterministic across JVM forks, and that is the finding.** The band
taken the same evening - ten forks of the wide benchmark, nothing changed -
puts every null-free case from twelve outputs up in tier 3, with spreads of
3000% to 14300%: at twelve outputs the same case reads 2.4 M rows/s in one fork
and 178.2 in another, and the low reading is the cycle while the high one is a
kernel that compiled once and ran. The committed 512-bit file happened to land
in the good mode at every rung; its 128-bit companion, one fork later, landed in
the cycle at every rung from twelve on. That also settles the discrepancy with
the `--rounds` probe of `dev/varka_emit.sh`, which runs the identical kernel
over identical inputs and compiles it once: it was a fork that landed well.
Whatever provokes the cycle is decided at or near the first C2 compile of the
method - the bad forks show an **OSR compilation** of the loop right after the
four loop-head traps that the good forks do not - and is then stable for the
fork's life. This is the per-fork C2 lottery `PLAN_TASK_32.md` 11 traced to
JDK-8380195 and task 90 measured across files, with a mechanism named for the
first time: a profiled loop predicate at a back-edge, and a compile-trap cycle
behind it. What decides the mode at the first compile is the question this row
opens.

**Why it matters beyond the benchmark.** A projection of a dozen calendar
outputs is an ordinary reporting shape, the loop method is 3.2KB - nowhere near
any size limit - and nothing reports the cycle: the query is simply a hundred
times slower on null-free data than on data with nulls. It is the third JIT
cliff this milestone has found that the emitter cannot see, after the epilogue's
size and C1's register limit, and the only one of the three that is not about
size at all. `PerMethodTrapLimit` is 100 and `PerMethodRecompilationCutoff` 400
on this JDK, so on a long-running query the cycle presumably ends after some
tens of seconds of compile time; whether it does, and what the kernel runs at
afterwards, is part of the question.

**How.** Reproduce it in a forked probe under `-XX:+PrintCompilation` and
`-Xlog:deoptimization=debug`, the way `VarkaAssemblySuite` forks its probes, run
enough forks to see both modes, and assert the cycle from the JVM's words rather
than a rate. Then find what differs at the first compile between a good fork and
a bad one - the OSR compile is the lead, and `-XX:+PrintIdeal` or the ideal
graph says what the back-edge predicate is a predicate *on*. The fixes
available, in order of preference: a loop shape the predicate does not misjudge,
if the shape is the cause; a documented `-XX:-UseProfiledLoopPredicate` for
Varka executors, if it is the JIT's; and, failing both, a decline for the shape
with a reason. **Done when** the probe passes on the twelve-output shape in
every fork of a run of twenty, the method compiled once and no
`profile_predicate` trap, and `VarkaMethodSizeBenchmark`'s null-free cases leave
tier 3 in a regenerated band. Size: medium, and the investigation is the larger
half.

*Added 24 September 2026: the 128-bit band says which form the cycle lives in.*
Task 87's benchmark now measures both epilogue forms in every fork, and ten
pinned runs at 128 bits (`VarkaMethodSizeBenchmark-jdk25-128bit-band.txt`) put
every null-free row of the single-epilogue form from twelve outputs up in tier
3, with spreads of 6200% to 21400% - the cycle, in some forks and not others -
and no row of the per-group form there by more than 26.42%, an ordinary tier-3
spread with no cycle in it. Ten forks of one form entering the cycle against ten
of the other never entering it is the strongest lead the task has: what the
per-group form changed is the loop methods' prologue (each sets up only its own
group's segments and literals) and the epilogue's calls, so the first question
is which of those moves what the profiled loop predicate sees. It also means the
default since task 87 already avoids the cycle on this family, which narrows the
task to whether any shape still reaches it.

### 2.10 The closing task (task 181)

The post itself, in task 118's shape: the claim of 1.1, the figure of 2.5, the
realistic query of 2.6, and the comparison to native accelerators.

That comparison needs care and it is the one thing here that is not yet
grounded. Gluten, Comet and Photon avoid the method-size problem by leaving the
JVM - no JVM, no 64KB method - and pay for it with a coverage cliff instead:
they accelerate the operators their native library implements and fall back to
Spark for the rest, where the limit returns. That paragraph must be written from
what this repository already records - `VISION.md` and the Velox read in
`sql/varka/skills/calendar-algorithms.md` - and checked, not asserted from
memory.

**Done when** the post is published, every number in it traces to a committed
results file under `dev/varka_quote_check.py`, every claim about Spark's
behaviour traces to task 188's census with a revision named, and the claim of
1.3 items 1 to 3 is true.

## 3. Task breakdown

Task numbers continue the single sequence; 87 keeps the number it was given in
milestone 4.

| task | what it is | where it came from | size |
| ---: | :--- | :--- | :--- |
| 87 | The epilogue is the one method no budget bounds. **Done** (`PLAN_TASK_87.md`, 24 September 2026, with 168): the epilogue is one method per group beside its loop method, each group's methods set up only their group, and on the masked arm the make_date ladder, which lost about two thirds of its rate on ragged batches from 13 outputs, reads within a few percent of its even-batch rate at every rung - and faster on even batches from 12 outputs (9.5 there); the null-free arm reads within 15% at every rung | `PLAN_MILESTONE_5.md` 2.18, item 15 | medium |
| 168 | One budget, counted in bytes, over every emitted method. **Done** with 87 (`PLAN_TASK_87.md` 9.4): `methodByteBudget`, 8000 by default, is measured against every method after the class is built; a group over it is split and the class built again, and a shape no split can fit declines with `VarkaEmitDeclined` naming the method, the bytes and the outputs. The compiler's plan-time reading of that decline is task 169's | 2.2, from 87's analysis | medium |
| 148 | The weight the budget counts is wrong for a division | `PLAN_MILESTONE_5.md` 2.84, item 39 | small |
| 169 | No exception escapes the emitter. **Done** (`PLAN_TASK_169.md`, 24 September 2026): the one reachable gap was the byte budget; the compiler asks the shape cache for the kernel it admitted and demotes what the emitter declines, with the reason EXPLAIN prints, so a heavy output is residual at plan time and no task fails to emit | 2.3, the ghost-fallback contract | small |
| 170 | Eight thousand, not sixty-five thousand: the JIT cliff. *Narrowed by task 87 to whether a limit below 8000 - C1's, about 1900 - earns its extra calls* | 2.4 | small, measured |
| 171 | The size ladder, and the figure. **Laptop ladder done** (`PLAN_TASK_171.md` 9.1, 24 September 2026): vanilla steps more than five times where its consume method crosses 8000 bytes, between 52 and 54 entries, and never compiles it again (`VarkaSizeLadderJitSuite`); Varka is a line through it; the runner's run and the figure remain | 2.5, absorbing milestone 4's row 44 | medium |
| 172 | One realistic query | 2.6 | small to medium |
| 173 | A disjointness test for the compiler's family chain | item 41 | small |
| 174 | The emitter's shared constants out of the facade | items 40, 43 | small |
| 175 | Port `VarkaIntervalCompiler` to Java, one family | item 42 | medium |
| 176 | A CI queue script. **Done** (`PLAN_TASK_176.md`, 24 September 2026): `dev/varka_ci_queue.sh` holds, reruns in order, drops and reports the fork's Build runs, one at a time | item 44 | small |
| 177 | A scoped CI path for oracle-proven refactors | item 45 | small |
| 178 | Bands on demand: the rule and the tooling. *Item 49's measurements taken 24 September 2026: the arithmetic benchmark's band at both widths, which withdraws `PLAN_TASK_63.md`'s 26.1% as evidence; the rule and the tooling remain* | item 49, task 145's finding | small |
| 179 | The fuzzer as a standing job | 2.8, from 87's origin | small |
| 182 | Extend Spark's own benchmarks, not only ours | item 8 | small to medium |
| 180 | Promotion, continuously | 2.9 | continuous |
| 183 | Onboarding: task tables as issues, a hardware census, templates | item 38 | small |
| 184 | The refactoring tools under `dev/` | item 46 | small |
| 185 | The schema-width cliff: `spark.sql.codegen.maxFields` | 2.11 | small |
| 186 | The method-size cliff, and the split that is switched off | 2.11 | small |
| 187 | The constant-pool cliff | 2.11 | small |
| 188 | The census: every place Spark's codegen gives up | 2.11 | medium |
| 189 | A dense loop enters a C2 deoptimization cycle at twelve outputs. *Lead from `PLAN_TASK_87.md` 9.5: in the fork where the single-epilogue form's null-free rows sat in the cycle at 128 bits, the per-group form's did not* | 2.12, from task 87's benchmark | medium |
| 190 | The op cap gives way to the byte budget. **Step 1 done** (`PLAN_TASK_190.md` 9.1, 24 September 2026: a hundred four-op entries fuse in one kernel; the split driver and several kernels per projection remain): `MAX_FUSED_NODES` admits 15 entries of the ladder's family where vanilla steps at 48 to 64, and task 87's budget now bounds every method, so bytes decide; past the driver's ceiling at about 150 entries, a split driver and several kernels per projection are built and measured | task 171's admission check | medium |
| 191 | Emission time linear in the kernel's width. `VarkaEmissionBenchmark` prices one emission at 10122976.0 ns for a hundred four-op outputs and 103358023.0 ns for four hundred: each doubling of the outputs costs 2.4 to 3.3 times as much. The likely cause, read from the code: every group's four methods plan their slots over the whole kernel's topological order (`Slots.plan`), so planning is groups times nodes; a per-group order would make it linear. Measure the cause first, then fix it, and read the benchmark's wide section again. A second route, from `READING_MILESTONE_6.md` section 4: predict each method's bytes before emitting it (row 199), so a wide kernel emits once rather than once per regroup | `PLAN_TASK_190.md` 9.1 | small to medium |
| 192 | Can vanilla Spark tune its way off the cliff? **Planned** (`PLAN_TASK_192.md`, 24 September 2026): the ladder's vanilla arm under `spark.sql.codegen.hugeMethodLimit=8000` (an internal setting whose own doc advises it on HotSpot, default 65535), `wholeStage=false`, and `-XX:-DontCompileHugeMethods`, so the post says *cannot* only if the measurement does | task 171's ladder | small, measured |
| 193 | *Research, optional.* How often real queries meet the cliff. **Planned** (`PLAN_TASK_193.md`, 24 September 2026): Spark's own `TPCDSQuerySuite` turns `spark.sql.readSideCharPadding` off "so that the generated code is less than 8000" and excludes `modified-q3`, so under the defaults TPC-DS already crosses it; the census records every stage under four configurations, on demand. Compile every whole-stage codegen stage of Spark's TPC-DS and TPC-H query plans (the plan-stability suites carry them) and record each stage's largest generated method against 8000 bytes, with nothing run at scale. Either real queries cross it - and task 172 has its query - or the cliff belongs to wide projections, and the post says so rather than implying it is everywhere. Complements task 188's census from Spark's source with one from its benchmark queries | the task 171 discussion, 24 September 2026 | small |
| 194 | *Research, optional.* Whether the ladder is fair to stock Spark's input path. Both of task 171's arms read the fork's Arrow cache, which is not Spark's default; add vanilla arms reading Spark's default cache serializer and a Parquet file, so the ratio the post quotes is against the path a stock user runs. The cliff is in the projection and should not move; the absolute numbers and the ratio may. Can share task 192's run | the task 171 discussion | small, measured |
| 195 | *Research, optional.* What Varka costs on the first query. Every ladder number is steady state; planning plus the first row cost tens of milliseconds a query in task 171's trial, a hundred-entry kernel takes about ten milliseconds to emit once per shape (`VarkaEmissionBenchmark`), and vanilla pays Janino for its own large method. `VarkaColdStartBenchmark` at the ladder's rungs, both arms, so first-query latency is measured before the post is read by someone who runs a query once | the task 171 discussion | small, measured |
| 196 | *Research, optional.* Whether the cliff holds on every JDK Spark supports. Measured on JDK 25 only; `HugeMethodLimit` has long been 8000, so the expectation is yes. Run `VarkaSizeLadderJitSuite` once on JDK 17 and 21 in CI, so "on every supported JDK" is a measured statement | the task 171 discussion | small |
| 197 | *Research, optional.* Whether the gap survives parallelism. The ladder runs on `local[1]`; the interpreted vanilla method costs the same on every core, but Varka's kernels read and write memory at a rate all cores together may saturate. One ladder run at `local[*]` on a GitHub runner | the task 171 discussion | small, measured |
| 198 | Shared work computed once. Every group of a kernel that uses the civil-from-days prefix recomputes it - eleven extra times at sixty `make_date` outputs (`PLAN_TASK_87.md` 3.3). AStitch's hierarchical data reuse computes a shared producer once and keeps it in a buffer its consumers read. Compute the prefix once per batch into a scratch vector the later groups read, behind an option, and measure it against recomputation on the ladders of tasks 87 and 171: a lane's recomputation against a store and a load. It applies to today's kernels, and it prices task 190's option B, whose cost is exactly a prefix recomputed per kernel | `READING_MILESTONE_6.md` section 3 | medium, measured |
| 199 | Method bytes predicted before emission. The emitter builds a class, measures it and regroups, so a wide kernel is emitted once per regroup. Fit a model of each method's bytes from its node counts on the IR fuzzer's own random shapes - the Halide autoscheduler trains its cost model on random programs the same way - and let the regroup choose from the prediction, so the common case emits once; the built class stays the last word. Feeds rows 191 and 200 | `READING_MILESTONE_6.md` section 4 | small to medium |
| 200 | Output grouping chosen exactly. `groupOutputs` is greedy: it walks the outputs and closes a group when the next would pass the budget, which ignores sharing the way TENSAT's greedy extraction does. The best partition of outputs in their given order is a dynamic program, quadratic in the outputs, with the prefixes each group recomputes and the bytes per method (row 199) as its cost. After 199, and only if 198 shows that grouping moves the numbers | `READING_MILESTONE_6.md` section 3 | small to medium |
| 203 | A demo a reader can run. The ladder's vanilla query as a script of about fifteen lines for `spark-shell` on stock Spark 4.2.0, printing the consume method's bytes and the time per row, so a reader sees the step between 48 and 52 entries on their own machine; with `-XX:+PrintCompilation` it also shows the method never compiled. Checked on a GitHub runner and published with the post, which links to it rather than asking to be believed | the post discussion, 24 September 2026 | small |
| 204 | Upstream: the test bug, and the silent cliff made visible. SPARK-59764, filed 24 September 2026: `BenchmarkQueryTest.checkGeneratedCode` finds no stage under adaptive execution, so the TPC suites' size check has not run since 3.2 (`PLAN_TASK_193.md` 9.1); its patch. Then, the owner choosing which, a proposal for the cliff itself: a warning logged when a whole-stage method passes HotSpot's `HugeMethodLimit`, `spark.sql.codegen.hugeMethodLimit` defaulting to 8000 on HotSpot, or both, argued from task 192's measurements. Each its own JIRA, and the post says where they stand | the post discussion, 24 September 2026 | small to medium |
| 205 | The history of the 64KB problem, sourced. A timeline of how Spark has met the method limits - expression splitting, `hugeMethodLimit`, `maxFields`, SPARK-29128's `modified-q3`, the char padding guard, SPARK-59764 - every entry a JIRA id and a commit read from the tracker and `git log`, none from memory, so the post can open with what Spark already did about the hard limit before it turns to the silent one | the post discussion, 24 September 2026 | small |
| 181 | The closing task: the post | 2.10 | last by definition |

## 4. Ordering

The spine is 87, 168, 169, 170, 171, 172, 181, in that order, because each is
the precondition of the next: the bug is fixed, then generalised, then made
loud, then aimed at the right threshold, then measured, then made realistic,
then published.

| wave | tasks | why they wait |
| ---: | :--- | :--- |
| 0 | 87, 176, 178, 179, 182 | 87 opens the milestone; the infrastructure tasks are independent of everything and pay for themselves immediately, and 182 is what lets 171's ladder be claimed against an upstream baseline |
| 1 | 168, 148, 173, 174, 184, 189 | 168 after 87, because 87's measurement decides the shape of the budget; 189 beside 168, because it distorts the dense arm of the benchmark both read; 148 rides with 168, since both move `emitted_bytes.json` and one regeneration should carry both; 173 and 174 are independent |
| 2 | 169, 177 | 169 after 168: the decline reasons are the budget's, and 177 wants the oracle's proof to be stable first |
| 3 | 170, 171, 185, 186, 187 | 170 and 171 need the budget to exist before a ladder means anything; the three cliff studies are the vanilla half of what 171 plots, and each is independent of the others |
| 3b | 188 | after 185, 186 and 187: the census is written against three worked examples rather than from a cold read |
| 4 | 172, 175, 183 | 172 after the ladder says where the cliff is; 175 after 184, whose member map is what its inventory is generated from; 183 once there is a milestone table worth mirroring, which is after wave 1 settles the rows |
| 5 | 181 | last by definition |

Task 180 runs across every wave rather than sitting in one, and task 183 is
its other half: 180 brings people to the repository and 183 gives them a rung.

## 5. Verification

The milestone's own acceptance, beyond each task's admission check:

* A fuzz campaign over the shape space produces zero emission failures and a
  decline with a reason for every shape the caps refuse.
* `emitted_bytes.json` is unmoved for every shape that emitted before, which is
  what says the budget changed no emission it admits. Where a shape's emission
  does change, the change is named in the task that caused it.
* The size ladder and the realistic query are committed results files with
  provenance and bands, and the README quotes them and nothing else.
* `dev/varka_quote_check.py` at zero orphans, `dev/varka_toc.py --check` clean,
  the linters run locally before every push.

## 6. Risks

1. **Spark's heuristic usually works.** The 64KB cliff is real and
   well-reported, but not universal, and a post implying every query hits it
   will be dismantled by the first knowledgeable reader. Task 172 exists to
   bound the claim honestly, and 1.3 item 3 makes it a precondition of
   publishing rather than a footnote. *The maxFields cliff of 1.1 is not a
   heuristic and does not have this problem, which is why 2.6 starts there.*
2. **"We split better" is not the claim.** If Varka merely raises its own
   threshold, the milestone has produced an incremental improvement and the post
   has no thesis. The claim is structural - no method-size fallback at all, by
   construction - and 2.3's zero-emission-failures property is what earns it.
3. **A byte budget may cost throughput.** More methods mean more calls per
   batch, and the epilogue runs once per batch where short batches feel it most.
   Task 87 measures this rather than assuming it, and task 170's A/B is where
   the threshold is chosen from numbers.
4. **The foundation produces no speedup number.** That is accepted, and 2.9 is
   the answer: the promotion track carries the JIT findings, which are better
   material than another ratio.
5. **The native-accelerator comparison is the easiest thing to get wrong**, and
   the most likely to be challenged. 2.10 requires it to be written from the
   repository's own record and checked.

## 7. Open questions

1. **Partition, or budget-and-decline?** 2.1 leaves the choice to its
   measurement. If partitioning the epilogue costs more per batch than the
   shapes it saves are worth, the honest answer is a decline with a reason, and
   the post's claim narrows from "Varka has no cliff" to "Varka's cliff is a
   decline with a reason rather than a silent fallback" - still something Spark
   does not do.
2. **Is 8000 reachable?** A budget that low may fragment a kernel into so many
   methods that the call overhead eats the JIT's gain. Task 170 answers it; if
   the answer is no, the post says which threshold Varka targets and why.
3. **Does the realistic query exist?** 2.6's finding may be that the shapes
   which trip Spark are narrower than the folklore. That result is publishable
   and would change the post's framing rather than cancel it.

## 8. Explicitly out of milestone 6

* **The coverage spine.** Decimal lanes, decimal arithmetic, aggregate wiring,
  grouped aggregation, string keys and a first end-to-end TPC-H q6 number stay
  in `SCOPE_MILESTONE_7.md` and move to milestone 7. The survey behind them is
  unchanged and still the best argument for what comes after this.
* **Item 47, one place per node**, for the reason 2.7 gives.
* **The Arrow-native Parquet reader**, which is the owner's work from milestone
  3 and which every benchmark here is measured without; the files say so.
* **The e-graph and IR rewriting.** A general-purpose Java e-graph belongs in
  its own repository under `github.com/vecbricks` rather than in this tree, and
  nothing in this milestone needs it.
* **Any new lane, type or expression family.** This milestone adds no coverage;
  that is what makes it a foundation milestone rather than a breadth one.
