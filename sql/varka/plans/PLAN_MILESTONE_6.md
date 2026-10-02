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

*Correction, 24 September 2026: 1.1 calls the JIT cliff silent. Spark logs it at INFO, as
"Generated method too long to be JIT compiled", and then does nothing about it; the shells, at
WARN, hide the line. See `PLAN_TASK_188.md` section 5.*

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
   *Since 25 September 2026: two posts, and the milestone closes on both.* The
   first is about where vanilla Spark's code generation gives up and makes no
   Varka claim (task 210); the second is this one, about how Varka solves it
   (task 181). The owner's decision, recorded in `PLAN_TASK_210.md` 1 and
   `PLAN_TASK_181.md` 7.
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

*Done 29 September 2026 (`PLAN_TASK_148.md`): the weight is the shipped multiply-high form's
eleven operations, not the seven below, which task 149 had already made the reference arm's
count. The second question below moved to `SCOPE_MILESTONE_7.md` item 63.*

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

*Closed on 29 September 2026 by task 209 (`PLAN_TASK_209.md` 10 to 13, `PLAN_TASK_181.md` 12): the
lower limit is C1's, 93 vector call sites on JDK 25, and it earns its calls for wide groups and
not for narrow heavy ones; the emitter holds wide groups under it beside the 8000-byte budget.*

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
1.3 items 1 to 3 is true. *Since 25 September 2026 this section covers two
posts* (1.3 item 5): task 210's, about vanilla Spark, is published first and
is done when its own outline's verification holds (`PLAN_TASK_210.md` 5); the
post described above is task 181's and links it.

## 3. Task breakdown

Task numbers continue the single sequence; 87 keeps the number it was given in
milestone 4.

| task | what it is | where it came from | size |
| ---: | :--- | :--- | :--- |
| 87 | The epilogue is the one method no budget bounds. **Done** (`PLAN_TASK_87.md`, 24 September 2026, with 168): the epilogue is one method per group beside its loop method, each group's methods set up only their group, and on the masked arm the make_date ladder, which lost about two thirds of its rate on ragged batches from 13 outputs, reads within a few percent of its even-batch rate at every rung - and faster on even batches from 12 outputs (9.5 there); the null-free arm reads within 15% at every rung | `PLAN_MILESTONE_5.md` 2.18, item 15 | medium |
| 168 | One budget, counted in bytes, over every emitted method. **Done** with 87 (`PLAN_TASK_87.md` 9.4): `methodByteBudget`, 8000 by default, is measured against every method after the class is built; a group over it is split and the class built again, and a shape no split can fit declines with `VarkaEmitDeclined` naming the method, the bytes and the outputs. The compiler's plan-time reading of that decline is task 169's | 2.2, from 87's analysis | medium |
| 148 | The weight the budget counts is wrong for a division. **Done** (`PLAN_TASK_148.md` 9, 29 September 2026): an int-lane `ConstDivide` weighs its shipped multiply-high form, 11 vector operations and 12 for a negative divisor, the larger of its two forms as the long lane's 14 is, pinned by a register test that counts every vector type; four divisions over four columns now take a loop method each. The regenerated oracle moved as predicted: no coverage row, no long-lane block, and 43 of the 10000 int-lane fuzz shapes, each with a division and several outputs, none losing a loop method. **Planned** (`PLAN_TASK_148.md`, 29 September 2026): the int lane's shipped form is task 149's multiply-high, eleven vector operations and twelve for a negative divisor, where the row counted the conversion form's seven; the weight takes the larger form, as the long lane's does, pinned by a register test that counts every vector type, and a four-column shape shows the grouping it changes. Section 2.2a's second question, what weights are for, is `SCOPE_MILESTONE_7.md` item 63's | `PLAN_MILESTONE_5.md` 2.84, item 39 | small |
| 169 | No exception escapes the emitter. **Done** (`PLAN_TASK_169.md`, 24 September 2026): the one reachable gap was the byte budget; the compiler asks the shape cache for the kernel it admitted and demotes what the emitter declines, with the reason EXPLAIN prints, so a heavy output is residual at plan time and no task fails to emit | 2.3, the ghost-fallback contract | small |
| 170 | Eight thousand, not sixty-five thousand: the JIT cliff. **Done** (29 September 2026, `PLAN_TASK_181.md` 12): answered by task 209 (`PLAN_TASK_209.md` 10 to 13) - a limit below 8000 earns its calls for wide groups, in C1's unit of 93 vector call sites rather than bytes: split under it, sixty-four cheap outputs run at 3.3 ns a row against 243 to 265 in one method, and no fork of 140 is slow against 13 of 66; groups of a few heavy outputs do not earn it and stay past C1. The size ladder's A/B of section 2.4 shows nothing, since its groups are heavy ones the budget leaves byte for byte; the post's 3.4 carries it as a footnote. *27 September 2026*: answered in part by task 209's census (`PLAN_TASK_209.md` 9.5) - a limit below 8000 earns its extra calls, since a loop method C1 refuses runs interpreted for seconds and lands in the deoptimization cycle in a third of JVMs; the limit is C1's, counted in vector call sites rather than bytes, and its value for each shape is what 209's next step measures. *Narrowed by task 87 to whether a limit below 8000 - C1's, about 1900 - earns its extra calls* | 2.4 | small, measured |
| 171 | The size ladder, and the figure. **Done** (26 September 2026: all three parts below). **Laptop ladder done** (`PLAN_TASK_171.md` 9.1, 24 September 2026): vanilla steps more than five times where its consume method crosses 8000 bytes, between 52 and 54 entries, and never compiles it again (`VarkaSizeLadderJitSuite`); Varka is a line through it; **runner ladder done** (9.2: the EPYC 9V45 shows the same step and the same line); **figure done** (9.3: `figures/svg/fig11-the-size-ladder.svg`, read from the 9V45 results file when drawn) | 2.5, absorbing milestone 4's row 44 | medium |
| 172 | One realistic query. **Done** (28 September 2026, `PLAN_TASK_172.md` 9.12): design B stays beside A, because on the first query at 200 ranges A costs three times B (420 against 142 ms on a runner) and its warm-up verdict thirty times (3.1 s against 0.1), where the two tie at steady state; `rangeSets` and `splitConditions` both on, as 9.9 configured them; the 9V45 figure is 9.10. *28 September 2026*: the 9V45 figure is committed (`PLAN_TASK_172.md` 9.10, `VarkaRangeFilterBenchmark-jdk25-runner-9v45-results.txt`): at 200 ranges Varka is 246 to 255 times vanilla, as on the 9V74, with both sides about twice as fast. **Planned** (`PLAN_TASK_172.md`, 24 September 2026): TPC-DS `modified-q3`, whose filter of 200 `ss_sold_date_sk` ranges over a scan is task 193's one crossing and compiles to a 12167-byte `processNext`; Varka declines it today, fusing at most 48 ranges in one method, so two designs are built and measured against vanilla - one condition split across methods, and a range-set lowering. **Step 1 done** (9.1): the boundary pinned by a test, and the baseline measured - vanilla steps about 135 times between 49 and 100 ranges, the scan loop itself interpreted. **Design B built** (9.2): a range set over one int or date column, emitted as one loop over a static table of bounds, so the whole 200-range filter fuses in methods under 2000 bytes; measured (9.3): 185 times vanilla at the query's 200 ranges, twice vanilla below its crossing; on a runner (9.4, an EPYC 9V74) 250 times vanilla at 200 ranges, the 9V45 figure still to come. **Design A built** (9.6): a predicate one method cannot hold is split across several selection outputs, which the filter combines, behind `splitConditions`, off until measured; **measured** (9.7): 2 to 11% faster than the range set at every rung by minimums, so the recommendation is to turn it on by default and keep range sets until task 195 prices the first query; on runners (9.8, EPYC 7763 and 9V74, no AVX-512) the two tie; **applied** (9.9): `splitConditions` on by default, range sets kept; the 9V45 figure still to come | 2.6 | medium |
| 173 | A disjointness test for the compiler's family chain. **Done** (`PLAN_TASK_173.md` 4, 25 September 2026): no node of the coverage table or the seams is claimed twice, every group claims some; the chain named as a list `compileNode` folds, `VarkaFamilyChainSuite` asserting at most one group claims any node of the coverage table and the seams, with a self-check that duplicates a family on purpose | item 41 | small |
| 174 | The emitter's shared constants out of the facade. **Done** (`PLAN_TASK_174.md`, 25 September 2026): no class under `codegen/varka` imports the facade - fifteen members and four nested types moved to the pieces that own them, the bytes oracle unchanged - and `InvalidJavadocPosition` is on for Varka's sources, which found seven misplaced doc comments, each moved onto its declaration | items 40, 43 | small |
| 175 | Port `VarkaIntervalCompiler` to Java, one family. **Done** (`PLAN_TASK_175.md` 5, 26 September 2026): the family is a Java class, the Scala file is gone, and the compiler, family-chain, coverage and emitted-bytes oracles are unchanged; the matching reads as well in Java, and every cost the port paid - no static forwarders on the facade, a default argument, the tables' erased types, `scala.Option` - is at the boundary with the Scala facade and goes when the facade is Java. **Planned** (`PLAN_TASK_175.md`, 26 September 2026): the family's arms become one Java `switch` whose cases return their body as a deferred call, which the Scala chain lifts into its partial function, so every guard is written once and asking a family whether it claims a node stays free of side effects; the coverage suite's source scan learns Java, since it is the one oracle a port changes, and `coverage.json`, `emitted_bytes.json` and the decline reasons are to come out identical. It also tests `sql/varka/CLAUDE.md`'s sentence that expression matching is a surface that forces Scala | item 42 | medium |
| 176 | A CI queue script. **Done** (`PLAN_TASK_176.md`, 24 September 2026): `dev/varka_ci_queue.sh` holds, reruns in order, drops and reports the fork's Build runs, one at a time | item 44 | small |
| 177 | A scoped CI path for oracle-proven refactors. **Done** (`PLAN_TASK_179.md` 5, 25 September 2026): item 45's precondition is checked and unmet - nothing runs on this project's master after a merge, since `build_main.yml` skips master on forks and Spark's scheduled builds are apache/spark-only - so `varka-weekly-matrix.yml` runs the full matrix on master every Sunday; the skip path is withdrawn, because the scoped job already runs the oracle beside the sql/core suites in parallel and skipping them would save job-minutes and no wall time; the oracle's verdict is printed in the scoped catalyst job's summary | item 45 | small |
| 178 | Bands on demand: the rule and the tooling. **Done** (`PLAN_TASK_178.md`, 1 October 2026): the rule is in `sql/varka/AGENTS.md` - a family gets its band the first time someone has to read a move in it - and `dev/varka_bench_repeat.sh <module> <Class> 10 --band` writes the band where the regen diff reads it; fifteen families carry one, each added when a move needed reading. *Item 49's measurements taken 24 September 2026: the arithmetic benchmark's band at both widths, which withdraws `PLAN_TASK_63.md`'s 26.1% as evidence; the rule and the tooling remain* | item 49, task 145's finding | small |
| 179 | The fuzzer as a standing job. **Done** (26 September 2026, `PLAN_TASK_179.md` 6: after three setup fixes, the run the plan provides reported for both fuzzers and both passed, the IR fuzzer's four lanes at about 730 trees a second each). **Built** (`PLAN_TASK_179.md`, 25 September 2026): `varka-fuzz.yml` runs the IR fuzzer nightly as one JVM per core with date-derived seeds, and `VarkaCoverageCompositionFuzzSuite` draws random wide projections and filters of the coverage table through the compiler, asserting fused or declined in bytes and never thrown; done when the first scheduled run has reported (section 6). *The first, on 26 September 2026, failed before the fuzzer ran: the workflow writes the classpath into `target/` before anything has created it (`PLAN_TASK_179.md` 6)* | 2.8, from 87's origin | small |
| 182 | Extend Spark's own benchmarks, not only ours | item 8 | small to medium |
| 180 | Promotion, continuously. *1 October 2026: not closable as done. The row asked for a cadence - the cross-posts the milestone 5 piece never got, a findings post every two weeks, a living benchmark page on the site - and none of it happened; the promotion that did is the two milestone posts (210, 181). It carries into milestone 7 as a continuing row, on the owner's decision* | 2.9 | continuous |
| 183 | Onboarding: task tables as issues, a hardware census, templates. **Done** (28 September 2026, `PLAN_TASK_183.md` 4: the workflow's first run, on 26 September, opened one issue per open row, 37, labelled `task` and `milestone-6`; 55 issues since, 16 of them closed as their rows turned done). **Built** (`PLAN_TASK_183.md`, 25 September 2026): the census page and both templates had landed on 21 September, before this milestone, with the walkthrough (`8d02d3ae033`); the last third is `dev/varka_issues.py`, one issue per open row of the milestone in flight, closed with the row's outcome when it turns done, run by `varka-issues.yml` on every change to a plan; done when the workflow's first run has opened them (section 4) | item 38 | small |
| 184 | The refactoring tools under `dev/`. **Done** (`PLAN_TASK_184.md`, 25 September 2026): `varka_members.py`, `varka_callgraph.py` and `varka_unused_imports.py`, documented in `docs/sql-varka.md` and pointed to from the task template; checked against the crossings and the unused imports task 174 met | item 46 | small |
| 185 | The schema-width cliff: `spark.sql.codegen.maxFields`. **Done** (26 September 2026, `PLAN_TASK_185.md` 8.6: the reproducers, the scan counting the columns a query reads under Varka and the Arrow cache, the reason logged where a user can act on one, and prediction 3 held by minimums on the repeats; the 101-field case's run-to-run spread is recorded in the band, and the upstream question of 3.4 stays with row 204, unfiled for want of a measurable cost). **Planned** (`PLAN_TASK_185.md`, 24 September 2026): reproducers at 100 and 101 fields, a Varka scan that reads the Arrow cache as batches whatever its width, and a recorded reason where that does not reach. **Baseline committed** (8.1, 25 September 2026): at 101 cached fields the scan produces rows and Varka has no node, silently, as the census read. **Fixed for the Arrow cache** (8.2): Varka reads a cache kept from columnar output by its width alone through `VarkaCacheScanExec`, reusing the scan's own columnar path; **measured** (8.3): Varka costs the same at 100 and 101 fields, 13.6 and 13.7 ns a row, and the whole-schema count costs vanilla too little to report upstream; **done** (8.4): where the fix does not reach, the rule logs why. *Task 188 found that a cached table of more than a hundred columns stops producing columnar batches whatever a query reads (`InMemoryTableScanExec.supportsColumnar`), so Varka gets nothing to fuse: the immunity this row predicts does not hold for the cache*; **Reworked** (8.5): a review found the wide scan inert under adaptive execution and hiding the scan from `observe`, the pipelined-shuffle check and EXPLAIN, so the scan now counts `maxFields` over the columns it reads under Varka and the Arrow cache, the wrapper is gone, the reasons name only actionable causes, and prediction 3 is not decidable until a re-run; repeats taken 26 September 2026 (`PLAN_TASK_185.md` 8.6): Varka fuses at 101 fields at the 100-field speed by minimums, the 101-field case unstable run to run | 2.11 | small |
| 186 | The method-size cliff, and the split that is switched off. **Done** (1 October 2026, by the census and the two posts): the shape that trips it is the size ladder's projection, 7868 bytes at 52 entries and 8254 at 54 on master, between 48 and 52 on stock 4.2.0; and the no-split path is why it trips so far below the character threshold's implication: inside a stage `splitExpressionsWithCurrentInputs` concatenates every expression's code into one method (G12), while outside one the 1024-character threshold makes methods of about 235 bytes, seven characters of source to a byte (G15). `VarkaCodegenGiveUpSuite` pins both (`PLAN_TASK_188.md` 6); `POST_MILESTONE_6_SPARK.md` 2 and `POST_MILESTONE_6.md` 1 carry them | 2.11 | small |
| 187 | The constant-pool cliff. **Done** (1 October 2026): no ordinary query reaches the pool, since Spark passes string literals through its `references` array and spills a class past a million characters into nested classes with pools of their own, so the shape that reaches it is a class built by hand - 40 methods of 900 distinct long constants fail with Janino's "0xFFFF", 20 compile, and nothing warns before the failure (G27, `PLAN_TASK_188.md` 6). On Varka's side `VarkaEmitBudget` counts the pool, and task 219 reads the Class-File API's refusal of a pool past the cap as the measurement and declines class-wide | 2.11 | small |
| 188 | The census: every place Spark's codegen gives up. **Done** (29 September 2026, `PLAN_TASK_188.md` 6): the last five provokable entries reproduced - G9 (`MergeRowsExec` leaves the stage past 255 parameter slots), G20 (a `With` does reach a stage, in a conditional branch, settling open question 7; its definition stays inline past 255 slots), G21 (a struct key gets no fast hash map), G29 (unparseable statistics report -1 and pass the size check) and G31 (the JDK backend routes a package-object unit to Janino) - so twenty-three entries have a reproducer, every one a test can reach; the rest are lists, mitigations, properties of the generated source, G30 unprovokable and G3 solved under the Arrow cache. **G15, G33 and G34 reproduced** (27 September 2026, `PLAN_TASK_188.md` 6): the three task 181's post asked for, so eighteen entries have a reproducer, and G34's Varka cell is measured - the residual projections are Spark's and cannot cross 8000 bytes under the splitter. Left: G3 with task 185; G13, G16 and G19 read from the source; G30 unprovokable; G9, G20, G21, G29 and G31 not attempted. **Census drafted** (`PLAN_TASK_188.md`, 24 September 2026): 34 entries from the source with Varka's answer to each; it found the 8000-byte cliff logged at INFO, correcting this milestone's "silent"; reproducers for G1, G2, G4, G8, G10, G12, G14, G17, G18, G24, G25, G26, G27, G28 and G32 committed (`VarkaCodegenCliffLogSuite`, `VarkaCodegenGiveUpSuite`, section 6), G28 settling an open question; G3 is task 185's first step, and G13, G16 and G19 are read from the generated source rather than asserted. **Varka's side asserted in every reproducer** (section 6, 25 September 2026, item 2 of 9.2): the fused node or the decline with its reason on the same shape, which corrected G17 from immune to not reached | 2.11 | medium |
| 189 | A dense loop enters a C2 deoptimization cycle at twelve outputs. **Done** (`PLAN_TASK_189.md` 9, 26 September 2026): no kernel a query runs reaches the cycle - the default per-group form cycled in none of 180 forks across six output counts, both widths and both paths to C2, where the legacy single-epilogue form cycles in 39 of 40 and leaves it by itself after a hundred traps - so no fix was built; the nightly now fails on any default-form fork in the cycle (`dev/varka_nightly.sh`'s `deopt` step), and `VarkaDeoptCycleSuite` holds the parser's rule against recorded logs. The mechanism hunt is left as research | 2.12, from task 87's benchmark | medium |
| 190 | The op cap gives way to the byte budget. **Done** (1 October 2026): every step built, measured on the runner and defaulted - bytes decide (`PLAN_TASK_190.md` 9.1), the driver from a table (10), the split driver and several kernels both on (11.5, 11.6); the question left open there, whether several kernels alone replace the split driver once task 236 plans a kernel's split instead of bisecting it, is task 236's. **Step 2, A' and B built** (`PLAN_TASK_190.md` 11, 30 September 2026): past the driver from a table's ceiling of about 180 groups, `splitDriver` (A') moves the driver's calls to its groups into stages it calls in turn, and `severalKernels` (B) has the compiler serve the entries one kernel sets aside - a driver past the budget, more than 64 columns - with further kernels the evaluator runs in turn over each batch. Both are on by default, on the owner's decision (11.5): the split driver serves the driver's ceiling in one class, planned in one emission with every output's sharing kept, and several kernels serve only what one class cannot, more than 64 columns or a class past the class-file caps; on the runner several kernels ran 0.9 to 2.6% faster per row and planned five to six times slower with the compiler's search, and the owner kept both on until task 236 removes that search (11.6). **Step 2, A0 done** (`PLAN_TASK_190.md` 10, 30 September 2026: the driver's per-output work is two calls reading tables baked into the class, on by default since measured, so the driver grows by 44 bytes a group and not with the outputs, the columns or the literals; on the EPYC runner it ran 3 to 8% faster, and no held-out wide shape of the cost audit declines any more, where 83 of 200 did; A' and B remain for widths past about 180 groups). **Step 1 done** (`PLAN_TASK_190.md` 9.1, 24 September 2026: a hundred four-op entries fuse in one kernel; the split driver and several kernels per projection remain): `MAX_FUSED_NODES` admits 15 entries of the ladder's family where vanilla steps at 48 to 64, and task 87's budget now bounds every method, so bytes decide; past the driver's ceiling at about 150 entries, a split driver and several kernels per projection are built and measured | task 171's admission check | medium |
| 191 | Emission time linear in the kernel's width. **Done** (`PLAN_TASK_191.md` 9, 27 September 2026): group-local frames are the default, and one emission of a four-hundred-output kernel takes 30.5 ms where it took 107, every rung faster; planning fell from 56% to 11% of an emission, and what stays superlinear is the driver, which keeps the kernel's frame by design. The admission check's probe had also paid a linked-list walk per output lookup - a Scala `List` through `asJava` - and the emitter now copies its outputs on entry. **Built** behind `groupLocalSlots`, off (`PLAN_TASK_191.md` 10, 27 September 2026): a group's loop and epilogue methods carry 174 locals at every width where they carried 726 to 10866, the class is 13% smaller at 400 outputs, and a body that guards nothing lost the two dead mask operations of an accumulator it never set; the measurement and the default flip follow on an idle machine. **Planned** (`PLAN_TASK_191.md`, 27 September 2026): the admission check found the term - every group's method plans its frame over the whole kernel, `max_locals` up to 10866 per method at 400 outputs, slot planning 72% of the emission and six-fold per doubling, the stack maps growing with the frames - so the design is group-local frames behind a switch, predicting the wide section linear and 400 outputs under 35 ms. *As found:* `VarkaEmissionBenchmark` prices one emission at 10122976.0 ns for a hundred four-op outputs and 103358023.0 ns for four hundred: each doubling of the outputs costs 2.4 to 3.3 times as much. The likely cause, read from the code: every group's four methods plan their slots over the whole kernel's topological order (`Slots.plan`), so planning is groups times nodes; a per-group order would make it linear. Measure the cause first, then fix it, and read the benchmark's wide section again. A second route, from `READING_MILESTONE_6.md` section 4: predict each method's bytes before emitting it (row 199), so a wide kernel emits once rather than once per regroup | `PLAN_TASK_190.md` 9.1 | small to medium |
| 192 | Can vanilla Spark tune its way off the cliff? **Done** (26 September 2026: the band the row still owed is committed, `PLAN_TASK_192.md` 9.6 and `VarkaSizeLadderTuningBenchmark-jdk25-band.txt`). **Laptop runs done** (`PLAN_TASK_192.md` 9.1, 24 September 2026): no longer *cannot* - `hugeMethodLimit=8000` and `wholeStage=false` turn the step into about 1.1 times the pre-cliff cost per entry, and `-XX:-DontCompileHugeMethods` removes it up to 80 entries but not at a hundred (row 201); none comes within an order of magnitude of Varka's line. The runners' runs agree (9.3: on the EPYC 9V45 tuned vanilla is 16 and 22 times slower than Varka at 54 and a hundred entries); the band remains **Corrected** (9.4): the benchmark read Spark's default cache, not Arrow; the laptop re-measured with the Arrow cache, where the tuned line costs about 1.3 times the pre-cliff cost per entry and Varka is 11 and 15 times faster than the best tuned vanilla; the runners' files re-measured too (9.5): on the EPYC 9V45 the best tuned vanilla is 16 and 19 times slower than Varka at 54 and a hundred entries | task 171's ladder | small, measured |
| 193 | *Research, optional.* How often real queries meet the cliff. **Done** (`PLAN_TASK_193.md` 9.1, 24 September 2026): of 178 TPC-DS and TPC-H queries under six configurations, one stage crosses 8000 bytes - `modified-q3`'s filter of 200 integer ranges over a scan, task 172's first candidate; char padding crosses nothing; and Spark's own `checkGeneratedCode` finds no stage under adaptive execution, so its size assertion has been checking nothing. Compile every whole-stage codegen stage of Spark's TPC-DS and TPC-H query plans (the plan-stability suites carry them) and record each stage's largest generated method against 8000 bytes, with nothing run at scale. Either real queries cross it - and task 172 has its query - or the cliff belongs to wide projections, and the post says so rather than implying it is everywhere. Complements task 188's census from Spark's source with one from its benchmark queries | the task 171 discussion, 24 September 2026 | small |
| 194 | *Research, optional.* Whether the ladder is fair to stock Spark's input path, and to its codegen. **Done** (`PLAN_TASK_194.md` 7, 29 September 2026): on a 7763 runner stock 4.2.0 over its default cache is 7% to 8% slower than the fork's vanilla below the cliff and within 5% above it, crosses between 48 and 52 entries, and stands 55 times Varka at a hundred against the fork ladder's 59; Parquet costs stock nothing the table sees; and Varka's row fallback over Parquet, which no kernel serves, is five times faster than vanilla past the cliff, since `UnsafeProjection` splits its methods and whole-stage codegen does not. The post quotes the ratio against stock and names its crossing. The question as posed: stock Spark 4.2.0 crosses 8000 bytes between 48 and 52 entries, the fork between 52 and 54 (`PLAN_TASK_192.md` 9.2), so the ladder needs a stock 4.2.0 arm as well. Both of task 171's arms read the fork's Arrow cache, which is not Spark's default; add vanilla arms reading Spark's default cache serializer and a Parquet file, so the ratio the post quotes is against the path a stock user runs. The cliff is in the projection and should not move; the absolute numbers and the ratio may. Can share task 192's run | the task 171 discussion | small, measured |
| 195 | *Research, optional.* What Varka costs on the first query. **Done** (1 October 2026): measured on the quiet laptop (`PLAN_TASK_195.md` 5) and on a four-core runner (`PLAN_TASK_181.md` 9.1), and the post's section 3 quotes the runner; of the three design questions the measurement raised, the row path while a kernel warms is task 212, done, and the other two are milestone 7's. **Planned**; **measured** on the quiet laptop 26 September 2026 (`PLAN_TASK_195.md` 5): a new shape's kernel is never compiled over a hundred thousand rows, so Varka's first query is slower than vanilla's at every rung, and the first run of the benchmark, whose offsets passed the kernel's month bound, measured vanilla twice (`PLAN_TASK_195.md`, 25 September 2026): `VarkaColdStartBenchmark` prices a new shape's first run against its second at the ladder's rungs on both arms, five predictions registered; item 3 of section 9.2. Every ladder number is steady state; planning plus the first row cost tens of milliseconds a query in task 171's trial, a hundred-entry kernel takes about ten milliseconds to emit once per shape (`VarkaEmissionBenchmark`), and vanilla pays Janino for its own large method. `VarkaColdStartBenchmark` at the ladder's rungs, both arms, so first-query latency is measured before the post is read by someone who runs a query once. The ladder's own files show a slower warmup than that: the Varka arm's average is several times its best at the wide rungs (`PLAN_TASK_171.md` 9.2) | the task 171 discussion | small, measured |
| 196 | *Research, optional.* Whether the cliff holds on every JDK Spark supports. **Done** (`PLAN_TASK_192.md` 9.2): on stock Spark 4.2.0 the consume method is never compiled past 8000 bytes on JDK 17, 21 or 25; the fork is built for Java 25 only, so the stock distribution is the one asked. Measured on JDK 25 only; `HugeMethodLimit` has long been 8000, so the expectation is yes. Run `VarkaSizeLadderJitSuite` once on JDK 17 and 21 in CI, so "on every supported JDK" is a measured statement | the task 171 discussion | small |
| 197 | *Research, optional.* Whether the gap survives parallelism. **Done** (`PLAN_TASK_197.md` 7, 29 September 2026): on a four-vCPU 7763 runner the every-core ratio past the cliff is 30 to 34 against 53 to 59 on one core - vanilla's interpreter divides by 2.9, Varka's kernels by 1.7, the two threads of a core sharing one vector unit - so the post keeps the one-core ladder and says so in a sentence; the parallel Varka arm's iteration spread is a finding left for row 231 or its own task. The ladder runs on `local[1]`; the interpreted vanilla method costs the same on every core, but Varka's kernels read and write memory at a rate all cores together may saturate. One ladder run at `local[*]` on a GitHub runner | the task 171 discussion | small, measured |
| 198 | Shared work computed once. **Done** (`PLAN_TASK_198.md` 10 to 12, 29 September 2026): `materializeChronoPrefix` measured at 33% and 44% per row on the sixty-`make_date` rung at 256 and 128 bits and made the default (#507); the size ladder 11% to 22% faster at a hundred entries on the 9V45 across four runs and 14% and 26% on the laptop's two widths; the flip's six scratch segments per region put the producer's loop into C2's deoptimization cycle on the batches path, read from the JVM's output and closed by one segment for the scratch (12). **Built** behind `materializeChronoPrefix` (`PLAN_TASK_198.md` 9, 28 September 2026). **Planned** (`PLAN_TASK_198.md` 8, 28 September 2026): the prefix's six vectors stored once per batch by the first group that computes them into a scratch buffer the caller allocates and passes to `run`, and loaded by the later groups in place of the prefix, behind `materializeChronoPrefix`; predictions registered, at least 15% on the sixty-`make_date` rung against a 40% ceiling, and the size ladder past 54 entries by 10%. **Admission check built**; **admitted** by the quiet run of 26 September 2026 (`PLAN_TASK_198.md` 6): the default's repeated prefixes are at most about 40% of its time, and the cheap-tail cliff turned out to be decided per JVM run, which row 209 now starts from (`PLAN_TASK_198.md`, 25 September 2026): the ceiling counted in operations, 11% at sixty outputs, is inside the ladder's band, so `VarkaSharedPrefixBenchmark` prices recomputation by lowering the fused ceiling on the same outputs before any scratch buffer is built; the quiet run decides this task and row 200. Every group of a kernel that uses the civil-from-days prefix recomputes it - eleven extra times at sixty `make_date` outputs (`PLAN_TASK_87.md` 3.3). AStitch's hierarchical data reuse computes a shared producer once and keeps it in a buffer its consumers read. Compute the prefix once per batch into a scratch vector the later groups read, behind an option, and measure it against recomputation on the ladders of tasks 87 and 171: a lane's recomputation against a store and a load. It applies to today's kernels, and it prices task 190's option B, whose cost is exactly a prefix recomputed per kernel | `READING_MILESTONE_6.md` section 3 | medium, measured |
| 199 | Method bytes predicted before emission. **Done** (`PLAN_TASK_199.md` 9, 30 September 2026): `VarkaEmitCost` predicts each group method's bytes and call sites from the group's features, and `sql/varka/emit_cost_audit.json` scores both models on held-out shapes. The regression, not the register, is the model to use: on methods of 2000 bytes or more its 99th-percentile error is 12.4% against the register's 69.5%, because a node priced beside only its children pays for validity code a wide group shares, so prediction 1 was wrong. Under the new `predictGrouping` switch, off by default, every shape the emitter keeps builds once, the cheap tails included, except two wide shapes that the new decline fallback rebuilds. A code review then had the test helpers moved to Java, `NarrowLane` priced (TIME kernels had gone unpredicted; the grammar's gap is row 235), and the fallback and the audit tightened (`PLAN_TASK_199.md` 9.5). Two wide shapes gain a loop method where greedy filling splits worse than the regroup's halving, which is row 200's subject. **Planned** (`PLAN_TASK_199.md`, 30 September 2026): the admission check found the regroup rebuilding almost nothing at the defaults - one build for all 20000 oracle fuzz shapes, the size ladder to 400 entries and the `make_date` ladders, two or three for the cheap tails, on the call-site budget - and a regression on node counts off by 6% at the median and biased low on the largest fuzz methods, none of which reaches the 8000-byte budget. So the task is re-scoped to the cost model tasks 200 and item 63 need: a regression and a register of emitted bytes and call sites per node, audited against the measured class on a corpus built to reach the limits, the better one driving the first grouping behind a switch. The emitter builds a class, measures it and regroups, so a wide kernel is emitted once per regroup. Fit a model of each method's bytes from its node counts on the IR fuzzer's own random shapes - the Halide autoscheduler trains its cost model on random programs the same way - and let the regroup choose from the prediction, so the common case emits once; the built class stays the last word. Feeds rows 191 and 200 | `READING_MILESTONE_6.md` section 4 | medium |
| 200 | Output grouping chosen exactly. **Done** (`PLAN_TASK_200.md` 8.2, 30 September 2026): on the runner the mixed family ran 5 to 13% faster a row under the exact grouping on both bodies, the size ladder did not move, and one emission cost less rather than more, since the class has half the loop methods to build; all four predictions held, so `exactGrouping` is on by default. **Built** (`PLAN_TASK_200.md` 8, 30 September 2026): behind `exactGrouping`, off, the first grouping is the best partition of the outputs under the greedy rule, found by a dynamic program over the starts and formed by the greedy walk with its starts forced, and the greedy partition itself wherever that is already the best; on every shape of the check it has the best partition's ops and groups, the mixed family ships 88 loop methods where greedy ships 170, and no shape declines; the runner's measurement and the default follow. **Planned** (`PLAN_TASK_200.md`, 30 September 2026): the admission check, first read as a rejection and corrected after review, finds the greedy grouping at the optimum in ops - within 0.12% of the best partition of the outputs in their order on every family, coverage compositions included - but not in methods: on outputs that mix prefix-sharers with cheap outputs sharing nothing it strands the cheap ones in loop methods of their own, 170 methods where 88 do the same work, a partition the rule already permits. So the exact partition is built behind `exactGrouping` and measured on that family; `VarkaGroupingBoundSuite` holds the greedy grouping to the best partition's ops meanwhile. Reordering the outputs stays task 72's (`SCOPE_MILESTONE_7.md` item 15, with the check's numbers), and `predictGrouping`'s default is item 71. `groupOutputs` is greedy: it walks the outputs and closes a group when the next would pass the budget, which ignores sharing the way TENSAT's greedy extraction does. The best partition of outputs in their given order is a dynamic program, quadratic in the outputs, with the prefixes each group recomputes and the bytes per method (row 199) as its cost. After 199, and only if 198 shows that grouping moves the numbers | `READING_MILESTONE_6.md` section 3 | small to medium |
| 201 | Why `-XX:-DontCompileHugeMethods` fails at a hundred entries. **Done** (`PLAN_TASK_192.md` 9.2, 24 September 2026): C1 gives up on the method at every size past the limit, and at a hundred entries C2 fails as well, "out of nodes during split", so the method stays interpreted; the flag moves the cliff rather than removing it. Under the flag vanilla's consume method is compiled and costs the same per entry as below the limit up to 80 entries, then runs at its interpreted cost at a hundred (`PLAN_TASK_192.md` 9.1). A forked probe shows C1 bailing at both sizes and C2 starting at both; the benchmark's own JVM under `-XX:+PrintCompilation` and C2's failure log are to say what happens to the 17 KB method, so the post can say where the flag stops working and why | task 192 | small |
| 202 | *Research, optional.* Varka against an interpreted vector engine. **Done** (`PLAN_TASK_202.md` 7, 29 September 2026): on two 9V45 runs over Parquet, vecruntime 0.0.3 planned its `VectorProject` at every rung and grew with no step, about 58 ns a row an entry - 2.9 times slower than stock 4.1.3 below the cliff, 1.65 times faster at a hundred entries, and 30 to 59 times slower than Varka over its cache - so all four predictions held; it converts none of the date chains. Spark's own routes around the cliff, `hugeMethodLimit=8000` and the per-expression methods of `UnsafeProjection`, run the ladder 1.7 to 3.0 times faster than it at every rung, so it beats Spark only under the defaults. Section 3.7's draft is filled (`PLAN_TASK_181.md` 13). spark-vector (github.com/spark-vector/spark-vector, a Java 25 Vector API plugin for stock Spark 4.1.3, modelled on Comet and Velox) evaluates each expression node with its own kernel into a materialised column, so it has no 8000-byte cliff, the same as Comet and Velox: any vector-at-a-time engine avoids it by construction. The post's claim is then only fair if it says what fusion adds beyond having no cliff. Add a spark-vector arm to task 171's ladder and to the date chain: its release jar on a Spark 4.1.3 distribution, as the date surface runs stock Spark 4.2.0, with its commit and version in the provenance. Every rung and entry the plugin runs as its own operator rather than falling back must be checked from its recorded fallback reasons before its number is timed. The measurement shows how much each intermediate column costs, and whether the claim should be fusion rather than the cliff | the spark-vector review, 24 September 2026 | medium, measured |
| 203 | A demo a reader can run. **Done** (`PLAN_TASK_203.md` 9.2, 24 September 2026: on runners, a 4.6 to 6.2 times step under the defaults and 1.1 to 1.3 under `hugeMethodLimit=8000`, on JDK 17, 21 and 25): one `spark-shell` script over stock Spark 4.2.0 at 44, 48, 52 and 56 entries, printing the method's bytes and the time per row under the defaults and under `hugeMethodLimit=8000`, checked by a small workflow on JDK 17, 21 and 25. The ladder's vanilla query as a script of about fifteen lines for `spark-shell` on stock Spark 4.2.0, printing the consume method's bytes and the time per row, so a reader sees the step between 48 and 52 entries on their own machine; with `-XX:+PrintCompilation` it also shows the method never compiled. Checked on a GitHub runner and published with the post, which links to it rather than asking to be believed | the post discussion, 24 September 2026 | small |
| 204 | Upstream: the test bug, and the silent cliff made visible. *State on 25 September 2026: SPARK-59764, SPARK-59765 and SPARK-59774 are merged, for 4.4.0 (SPARK-59765 also 5.0.0); the proposal for the cliff itself is still the owner's choice.* SPARK-59764, filed 24 September 2026: `BenchmarkQueryTest.checkGeneratedCode` finds no stage under adaptive execution, so the TPC suites' size check has not run since 3.2 (`PLAN_TASK_193.md` 9.1); its patch, apache/spark#59017, with a follow-up after review; and SPARK-59765, the empty-broadcast stubs the same instruments and `WholeStageCodegenSizeBenchmark` measure, with its patch, apache/spark#59018. SPARK-59774 and its patch, apache/spark#59020, filed 24 September 2026: that INFO line raised to a warning that names the remedy. Then, the owner choosing which, a proposal for the cliff itself: the INFO line Spark already logs when a method passes HotSpot's `HugeMethodLimit` (`PLAN_TASK_188.md` G26) raised to a warning, `spark.sql.codegen.hugeMethodLimit` defaulting to 8000 on HotSpot, or both, argued from task 192's measurements. Each its own JIRA, and the post says where they stand | the post discussion, 24 September 2026 | small to medium |
| 205 | The history of the 64KB problem, sourced. **Done** (`PLAN_TASK_205.md`, 24 September 2026): Spark shipped `hugeMethodLimit` at 8000 for no release - SPARK-23267 raised it to 65535 before 2.3.0, and at that default only Janino's 64KB rejection can trigger the fallback; SPARK-29128, which `modified-q3`'s exclusion cites, never landed; the TPC suites' size check ran for about 14 months, 3.0 and 3.1, and after that only in `LogicalPlanTagInSparkPlanSuite`, which disables AQE itself. A timeline of how Spark has met the method limits - expression splitting, `hugeMethodLimit`, `maxFields`, SPARK-29128's `modified-q3`, the char padding guard, SPARK-59764 - every entry a JIRA id and a commit read from the tracker and `git log`, none from memory, so the post can open with what Spark already did about the hard limit before it turns to the silent one | the post discussion, 24 September 2026 | small |
| 206 | A nondeterministic filter's decline records its reason. **Done** (`PLAN_TASK_188.md` 6, 24 September 2026): every conjunct carries the reason, and the fusion report shows each conjunct's reason when none fuses. `predicateOnce` declines a filter with a nondeterministic conjunct and returns without noting why, where every other decline records a reason the fusion report and `EXPLAIN` show (`PLAN_TASK_188.md` section 5) | task 188's census | small |
| 207 | Several accumulators in the range set's loop. The range set's kernel ORs every range into one mask, and C2 carries that mask from one iteration of the range loop to the next through the stack (`PLAN_TASK_172.md`, the correction after 9.7), so the ranges wait on each other: every four ranges a store and the load that reads it back. Two or four masks, OR-ed at the end, break the chain without changing the code's size, and the range filter benchmark says whether that closes design B's gap to design A | `PLAN_TASK_172.md`, correction after 9.7 | small, measured |
| 208 | One comparison per range. Both of task 172's designs test `lo <= v && v <= hi` as two comparisons and an AND; `(v - lo)` compared unsigned against `(hi - lo)` is one subtraction and one comparison, since a value below `lo` wraps to a large unsigned number. It applies to the range set's loop and to a range written as two comparisons over one column, which the compiler can recognise where it already recognises ranges for the range set. Measured on the range filter benchmark at both widths, with the int extremes in the differential | `PLAN_TASK_172.md`, correction after 9.7 | small, measured |
| 209 | A cliff under the byte budget: too many Vector API calls in one loop method. **Done** (`PLAN_TASK_209.md` 11 to 13, 29 September 2026): the emitter reads each loop and epilogue method's Vector API call sites off the built class beside its bytes and splits a group over 93 - C1's last compiled count on JDK 25 - through the byte budget's regroup, while the group holds more than six outputs, and never costs a kernel, since a class its splits would make decline is built again without it; a narrower group is one of heavy outputs, which no split brings under C1 and which would pay a method per output for good (the blanket budget split the `make_date` ladder to one output a method, 2.3 times slower at sixty outputs, and declined task 190's hundred-entry kernel), so it stands and runs under C2 alone as the census found such groups do, settling by second four and never cycling. Under the default the ladders and the whole emitted-bytes corpus emit byte for byte what they did; the sixty-four cheap tails split to four loop methods and read 3.3 ns a row at 256 bits against 243 to 265 in one method, 5.9 to 7.5 at 128 against 894 to 998, and the census's counts forked again under the budget are 0 of 100 forks slow at both widths against 13 of 66 in the control. In the shared-prefix benchmark's own JVM of twenty kernels, eight runs at both widths, the four-group kernel is fast in 76 instances of 80 and the one-method kernel slow in 8 of 8; the residue of about one instance in twenty is the JVM's history, not the class (`PLAN_TASK_209.md` 12.4), and is milestone 7's item 62. A review's ten findings are section 13: the epilogues and the decline fallback came from it, and no measured shape's emission moved. Left to milestone 7's item 61: C1's boundary on the runner classes and on mask-heavy groups, the heavy groups' seconds and their two modes, the warm-up's C1 exclusion under the budget. **Admission check done** (`PLAN_TASK_209.md` 9, 27 September 2026): the cliff is C1's limit, between 81 and 105 vector call sites for the cheap tails - under it every fork is fast from the first second; past it the loop runs interpreted for 2 to 6 seconds and then is fast in two forks of three and in task 189's deoptimization cycle for good in the third; at 225 sites, or 112 in each of two methods, C2 fails or cycles and no fork is fast in 23 seconds. The design is admitted in its unit, a call-site budget, with its value at C1's limit; the budget is set in its own pull request after the boundary and the ladders are measured (9.5). **Planned** (`PLAN_TASK_209.md`, 27 September 2026): an admission check first - a forked probe per run that emits the cheap-tail shape at each fused ceiling, gives every run a fast-or-slow verdict, and reads C2's refusals and intrinsic outcomes out of its compile log, with a sweep over call-site counts for the cliff's position; then, if the refusal is a budget's, a call-site budget beside the byte budget in the emitter's grouping. Sixty-four `year(d) + k` outputs fuse by default into one group whose `loopDense0` is 3763 bytes, under every limit the budget checks, and C2 compiles it to 72613 instructions with no vector multiply, subtract or shift - the calendar arithmetic runs as the Vector API's scalar fallback, about sixty times slower than the same outputs in six groups in a first, unquiet run (`PLAN_TASK_198.md` 3). The fused ceiling lets a group that reuses a prefix grow past what C2 can inline. Confirm the refusal from the JVM (`PrintInlining`, the `NodeCountInliningCutoff` of `emitter-and-ir.md`), find the call-site count where it starts, and bound a group by it; JDK 27's `DelayAfterInliningCutoff` (scope item 56) is the other lever. In this milestone because the post claims no cliff, and this is one Varka has by default 28 September, the night after (`PLAN_TASK_209.md` 10): C1 compiles 93 sites and refuses 99 (20 and 22 cheap tails), so the budget's value on this JDK is 93; a `make_date` output is 55 sites on a prefix of 38, one output a method under it; `-XX:-UseProfiledLoopPredicate` ended the cycle in 30 forks of 30 against 10 of 30 slow in the control, C1 excluded cycled in 2 of 30, the inline directive changed nothing C2 emitted, and no arm moved the seconds before C2 | `PLAN_TASK_198.md` 3 | small to medium, measured |
| 210 | The first post: where Spark's code generation gives up. **Done** (`PLAN_TASK_210.md` 9.9, 27 September 2026): published as "The 8000-byte cliff in Spark SQL" at https://vecbricks.github.io/the-8000-byte-cliff/, rendered by `dev/varka_post_page.py` from `POST_MILESTONE_6_SPARK.md` at `c6834da51cb`; the release table read from the tracker that day, SPARK-33301 still in review. **Outlined** (`PLAN_TASK_210.md`, 25 September 2026): the owner split the closing post in two, and this one is about vanilla Spark only - the JVM's four limits, how Spark guesses at them, the census, the timings, the fallbacks to interpretation and the upstream fixes in flight. Why in this milestone: it is the promotion track, which the halfway review (9.1) found untouched, and it needs none of the Varka measurements task 181 still owes; what it owes is one committed vanilla benchmark of the split-call case (`PLAN_TASK_210.md` 4) | 2.10, split from 181 | small to medium |
| 211 | The CI queue keeps a verdict across a docs-only change. **Done** (26 September 2026: in use, it kept #438's passing verdict across a docs-only push without a Build). **Built** (`PLAN_TASK_211.md`, 25 September 2026): `dev/varka_ci_queue.sh` reads GitHub's compare from the head a passed Build tested to the head a PR has now, and when the head is a descendant that differs only in plans, skills, papers, Markdown or committed benchmark results, `hold` cancels the push's run without queuing, and `run` and `status` report the PR ready; a merge of master counts as what master brought. Why now: on 25 September seven merges sent a merge of master into every open PR, most of them plans only, and each cost a two-hour Build; the owner chose this fix from three offered | task 176, the owner's choice of 25 September | small |
| 212 | A new kernel reaches the JIT on its first query. **Done** (`PLAN_TASK_212.md` 10, 26 September 2026): a new shape's batches take the row path while a background thread compiles its kernel, and C1 is kept off kernel classes, whose methods it otherwise strands on tier-2 code; the first run past the cliff is 0.73 to 0.79 times vanilla's (346 against 461 ms at 54 entries), the kernel takes over after 0.8 to 6.8 seconds, and then runs 16 times faster than vanilla at 54 entries. **Planned** (26 September 2026): task 195 found a new shape's kernel never compiled over a hundred thousand rows - ten calls of about 625 iterations, short of every first-tier threshold - so Varka's first query is slower than vanilla's. An admission check with the thresholds scaled bounds the win; then two variants built and measured against each other, one call per partition and a warmed kernel. Why now: the second post's first-query bound depends on it | task 195, the owner's request of 26 September | medium |
| 213 | The warm-up chooses which of a kernel's drivers to compile from statistics. Task 212's warm-up compiles the masked driver beside the dense one wherever a kernel input is nullable, and most sources declare every column nullable, so a shape whose batches never hold a null pays for a driver it never runs, in time to its first query. Statistics can say which drivers a shape will need: the Arrow cache's per-batch column statistics (`ArrowCachedBatchSerializer` is a `SimpleMetricsCachedBatchSerializer`, so every cached batch carries its null counts) are exact for cached data; Parquet and ORC footers, Delta and Iceberg file statistics and `ANALYZE TABLE` column statistics are exact at no nulls and all nulls and a rate in between; the operator below adds only nullability at plan time. A wrong prediction costs speed and never an answer, because the kernel chooses its driver from each batch's real null counts, so the backstop is a warm-up of the other driver started by its first batch. First measurement: how often inputs declared nullable hold no null in a batch, and what a dense-only warm-up saves at the cold-start benchmark's rungs | `PLAN_TASK_212.md` 10.7, from the review of task 212 | medium |
| 214 | Port `VarkaTimeCompiler` to Java, the second family. The smallest family left, 409 lines: the long lane, `TIME(p)` and day-time interval leaves and casts, and the TIME functions, matched as the `StaticInvoke` they rewrite into through `timeTargets`; by task 175's recipe (`PLAN_TASK_175.md` 2.3, 2.4 and 5): the arms become one Java `switch` whose cases return their lowering as a deferred call, the chain lifts it with `Function.unlift`, and the coverage suite's scan lists the file under Java; done when the family is a Java class, its Scala file is deleted, and `coverage.json`, `emitted_bytes.json` and the family-chain suite are unchanged. Mechanical, with complete oracles, so it can be handed to an agent with the plan as its instructions | `PLAN_TASK_175.md` 5 | small to medium |
| 215 | Port `VarkaConditionCompiler` to Java, the third family. 443 lines: the three-valued conditions - comparisons, `IN` over date literals, the validity predicates and the connectives - and the conditionals `IF`, `CASE WHEN` and `coalesce`. Its seam is wider than the interval family's: `compilePredicate` also calls `compileCond` and `andFold` directly, so those become Java entry points beside the arm; by task 175's recipe (`PLAN_TASK_175.md` 2.3, 2.4 and 5): the arms become one Java `switch` whose cases return their lowering as a deferred call, the chain lifts it with `Function.unlift`, and the coverage suite's scan lists the file under Java; done when the family is a Java class, its Scala file is deleted, and `coverage.json`, `emitted_bytes.json` and the family-chain suite are unchanged. Mechanical, with complete oracles, so it can be handed to an agent with the plan as its instructions | `PLAN_TASK_175.md` 5 | small to medium |
| 216 | Port `VarkaChronoCompiler` to Java, the last family. 678 lines, the calendar; it calls the interval family's `intervalOperand` and `twelve`, which are Java already; by task 175's recipe (`PLAN_TASK_175.md` 2.3, 2.4 and 5): the arms become one Java `switch` whose cases return their lowering as a deferred call, the chain lifts it with `Function.unlift`, and the coverage suite's scan lists the file under Java; done when the family is a Java class, its Scala file is deleted, and `coverage.json`, `emitted_bytes.json` and the family-chain suite are unchanged. Mechanical, with complete oracles, so it can be handed to an agent with the plan as its instructions | `PLAN_TASK_175.md` 5 | medium |
| 217 | Port the compiler's facade, `VarkaExpressionCompiler`, to Java. 1164 lines, after 214 to 216, since the dispatch stays a Scala partial function until the last family moves. With it go `DeclineSink`, the literal and input tables and the `scala.Option` signatures, and with those every workaround task 175 recorded: the module reached as `VarkaExpressionCompiler$.MODULE$`, the written-out default argument, the tables' wildcard types and cast. The families' Scala-facing signatures then become plain Java | `PLAN_TASK_175.md` 5 | large |
| 218 | *Research, optional.* Why the loop predicate cycles, and short calls cure it. *27 September 2026*: task 209's census found the default form in the cycle after all - the cheap-tail shape `year(d) + k` in one loop method at 24 to 40 outputs, the default options, recompiled three to nine times with `profile_predicate` traps in a quarter to a third of twenty forks per count, with the compiled code identical in the forks that cycle and those that do not (`PLAN_TASK_209.md` 9.1); the shape belongs in the nightly deopt guard beside the `make_date` ladder, and this row's question is live for a shape a query runs. Task 189 found the legacy single-epilogue form's second loop method trapping at `profile_predicate` on its loop's back edge on every C2 version until a hundred traps, and the short-call warm-up of task 212 keeping every fork out of it; the default form never cycles, so nothing a query runs is affected. The hunt is `PLAN_TASK_189.md` 3.1: the hoisted check read from the trapping nmethod's assembly at the deoptimization's `relative_pc`, then a good and a bad fork compared at their first standard compile. Worth doing if a future kernel shape brings the cycle back, which the nightly's deopt step would report | `PLAN_TASK_189.md` 3.1 and 9 | small to medium, measured |
| 219 | A method past 64 KB escapes the emitter instead of declining. **Done** (27 September 2026, `PLAN_TASK_219.md` 9 and 10: the refusal is read as the measurement, the four predictions held, and the review's eight findings were addressed; the one build a refused shape still costs at plan time is row 199's). **Fixed** (`PLAN_TASK_219.md`, 27 September 2026): the Class-File API enforces the 65535-byte cap on a method's code while the class is assembled, so no measurement of the class can see it and the refusal left the emitter as an `IllegalArgumentException`, which plan-time admission read as a fit; the emitter now reads the refusal as the measurement of that one method and feeds it to the byte budget's own regroup, so the shape splits or declines as any method over a limit does, budget or not; the constant pool's cap, refused the same way, declines class-wide, and a test produces both refusals through the Class-File API itself so that a JDK changing the words fails a test, not a fuzz run. Reachable by a query under production options - the op cap is off under the byte budget, and forty distinct nested `make_date`s make a 132452-byte loop method - which the compiler now demotes at plan time with the cap as its reason. The night's ten reproducers decline and the fuzzer counts and skips them. The fix uncovered the compiler demoting one output per round on a class-wide decline, which had made the 3000-output G14 arm take twenty-five minutes once its refusal no longer escaped; `classify` bisects instead (`PLAN_TASK_219.md` 10), which is very likely row 220's mechanism too. *Found by the night fuzz run of 27 September 2026: ten IR trees, each a deeply nested `make_date`, eight in loop methods (65598 to 77885 bytes) and two in the legacy `epilogueMasked`; reproducers, `VarkaIrFuzzSuite` with `-Dvarka.fuzz.seed=<seed> -Dvarka.fuzz.only=<iteration>`: 27090110001/41527, 27090080003/65887, 27090100008/42382, 27090120009/142164, 27090100010/147813 (epilogue), 27090050011/143610, 27090060012/181374, 27090060013/259035 and 27090090013/269460; the tenth, seed 27090010002 (epilogue), threw from the fuzzer's own retry without the budget, so it printed no iteration* | the night fuzz run, 27 September 2026 | small |
| 220 | Size admission can take the compiler tens of minutes. **Done** (27 September 2026, `PLAN_TASK_219.md` 10: `classify` bisects on a class-wide decline, the three composition runs that had timed out replay in 62 to 167 seconds, and the nightly fuzz has passed on every run since; emission time itself was row 191's, done). **Fixed** by task 219 (`PLAN_TASK_219.md` 10, 27 September 2026): the mechanism was `classify` demoting one output per round on a class-wide decline - a driver over the budget on a wide composition - and asking for a class of nearly the same size once per output; it bisects the fused prefix now, and three of the night's runs that hit their caps replay at 62 to 167 seconds for their 4000 compositions where they took over twenty minutes. What is left of the question, emission time superlinear in width, is row 191's. *As found:* In the same night run, 42 of the coverage composition fuzzer's runs of 4000 random projections and filters exceeded its twenty-minute cap or the runner's thirty-minute timeout, where the rest finish in one to six minutes. Thread dumps of two such JVMs show the test thread busy throughout - 1331 CPU seconds in 1457 - and moving between the compiler's classification and class building under `admitBySize` and `VarkaLoopEmitter.build`, so it is repeated work, not a deadlock. The likely shape, read from the code: `classify` demotes only the outputs a decline names and asks for the whole kernel again, and each ask can rebuild the class several times while the emitter regroups, so a wide projection over the budget pays rounds times rebuilds times an emission that task 191 already found superlinear in width. It is planning time on a real query, not only the fuzzer's. First find a composition that shows it - replay seed 27090210023 with per-composition timing - then count the rounds and builds, and bound them | the night fuzz run, 27 September 2026 | small to medium |
| 221 | The warm-up's verdict is relative, and admits a kernel that still boxes. **Done** (`PLAN_TASK_221.md`, 27 September 2026): the verdict was not only relative - a probe also had to be under an absolute allowance - and the kernel was not a bad compile but a late one: C2 compiles a kernel's methods one at a time, and on a starved machine the verdict, read from short calls, came while the light group's loop was still interpreted, its boxing under the per-column allowance. The probe now runs long calls, where an interpreted loop cannot hide; thirty starved runs pass without the retry, which is removed. Measured on a new shape's first queries (`PLAN_TASK_221.md` 10): the queries, the compiled code and the back-to-back runs within the day's noise, the verdict 7 to 20% later, and no fork in the deopt cycle on the warm-up path. *As found:* `VarkaKernelWarmup` calls a kernel compiled when a probe's allocation has fallen four-fold from the first probe's (`COMPILED_DROP`), and on the fork CI of #458 (27 September 2026, run 36296759912, the catalyst job) three of `VarkaKernelWarmupSuite`'s tests met a kernel that satisfied that rule fifty-fold - `firstProbeBytes=991936, lastProbeBytes=20032` - and still allocated 396032 bytes over the sixteen calls the tests then made, about 24 KB a call, where a compiled call allocates its memory segments alone (the tests' bound is 20480 for the sixteen); the same suite failed once the same morning inside a full local run and passed alone and on its rerun. The likely mechanism is the one `the-jit.md` records for starved runners: a C2 body compiled before the vector classes it intrinsifies were loaded is the scalar fallback, which boxes, and a relative verdict cannot tell it from a compiled one. In production that is a kernel reported warm that runs slow and allocates, silently - the class of cliff this milestone hunts. The fix is an absolute floor in the verdict, the segments plus the sampler's allowance per call, the bound the suite already uses; a warm-up that has not reached the floor within its cap says so (`STRANDED`, or a state of its own) rather than `COMPILED`, and one that recompiles after the classes load reaches it. Until it lands the three tests carry `testRetry`, to be removed when this row closes | the fork CI of #458, 27 September 2026 | small |
| 222 | Structural hashing of IR nodes on every map lookup. The IR records cache no hash, and `Slots` keys thirteen maps by node and `Analysis` eight, so every `put` and `get` hashes the node's subtree, and a subtree shared by several parents is hashed once per parent: the tree's size, not the DAG's. In task 191's 400-output profile (`VarkaEmissionProfile-jdk25-probe.txt` 2) about 8% of the samples are in the records' `hashCode` and the map work under them, at recursion depth one because the benchmark's shape is three nodes deep; the cost grows with depth, and on a maximally shared chain - `greatest(x, x)` nested d times, d + 1 nodes and 2^d hashes per lookup - it is the unfolding that CSE was meant to avoid (`SCOPE_MILESTONE_7.md` item 57). Investigate: measure the share on a deep shared shape (task 219's nested `make_date` family) with `dev/varka_jfr_frames.py`; if it is what the arithmetic says, cache one hash per node - an identity-keyed table in `Analysis`, or the hash-consing item 11's design input already asks for - and re-read the wide section of `VarkaEmissionBenchmark` | task 191's admission check, 27 September 2026 | small, measured |
| 223 | CSE's shared slots are decided by kernel-wide use counts. **Done** (`PLAN_TASK_223.md` 9, 2 October 2026): `Slots.plan` decides a shared slot on the body's own use count; over the cost corpus it removes every slot a body used once - 66,260 of 86,108 in the wide int family, 96,486 of 147,500 in the wide long one - and 0.66% and 1.33% of their loop and epilogue bytes, with no build or loop method moved; the methods of 8000 bytes and over fall from 161 to 139. The row's "a store and a reload" was one too many: a single-use slot is a `dup` and a store, never read. A finding beyond the row - segments and column loads stored and never read - is row 239. `Slots.plan` gives a node a shared slot when `analysis.useCount`, the kernel's count, is above one, so a node shared across two groups but used once inside a body still gets a slot in that body's frame, a store and a reload. Investigate: count, over the emitted-bytes corpus and the wide shape, how many shared slots of a several-group kernel are used once in their body; if that is common, decide the shared slot by the body's own use count - the body's node set is known since task 191 - and read the bytes and the op counts, a store and a load fewer per such node, on `VarkaEmitterParityBenchmark`. It moves bytes, so it takes the oracle's regeneration with the diff reviewed | `PLAN_TASK_191.md` 3.2, 27 September 2026 | small |
| 224 | Benchmarks and tools in Java: a Java adapter over Spark's harness, and `VarkaEmitDump` ported. The catalyst and core benchmarks are Scala only because they extend Spark's `BenchmarkBase` and `Benchmark`, whose `-results.txt` format `dev/varka_bench_regen.sh`, `dev/varka_bench_diff.py` and the quote check read. The harness is callable from Java - `private[spark]` is public in bytecode, and Java already compiles in those test trees - but awkward: the `Benchmark` constructor's defaults become seven arguments two of which are Scala types (`FiniteDuration`, `Option[OutputStream]`), `addCase(name, numIters)(f)` becomes a `Function1<Object, BoxedUnit>` a lambda must return `BoxedUnit.UNIT` from, `runBenchmark`'s by-name block a `Function0`, and a Java subclass writes the static `main` a Scala `object` gets for free. The cost of the Scala layer showed in task 191: `VarkaEmitDump` handed the emitter a Scala `List` through `asJava`, whose `get(i)` walks from the head, and the admission check's probe read emissions three times slower than the benchmark (`PLAN_TASK_191.md` 9). Build a Java adapter in the catalyst test tree over Spark's `Benchmark` and `BenchmarkBase` - a builder with Java defaults, `addCase(String, IntConsumer)`, a section taking a `Runnable`, and a base class that owns `main` and the output stream - so a benchmark written in Java touches no Scala type, while Spark's own code still measures and writes the results: one source for the format, which a harness of Varka's own would have to keep identical by hand. Port `VarkaEmitDump` to Java, since every plan's registered op counts and the new profiling go through it, and add to `sql/varka/AGENTS.md` that a new benchmark is written in Java on the adapter. Existing Scala benchmarks are ported when a task touches them, as the compiler families are (rows 214 to 217), and a port proves itself by reproducing its own committed results on a quiet machine. JMH stays with the engine's microbenchmarks, whose output is a different format. A harness free of Scala altogether belongs to the Java Catalyst, when the Scala runtime leaves the classpath, and upstreaming Java-friendly overloads to Spark's harness is an option, not a dependency. **Done when** the adapter exists with one benchmark on it reproducing its committed file, `VarkaEmitDump` is Java with `dev/varka_emit.sh`'s output unchanged, and the rule is in `AGENTS.md` | the owner, after task 191, 27 September 2026 | medium |
| 225 | The Linters job is a Varka pull request's critical path. **Done** (`PLAN_TASK_225.md` 5, 1 October 2026): on the runs since the merge the lint job takes 7 to 8 minutes on a change confined to Varka, inside the suites' 18, and 4 on a documents-only one, against 30 before; a change with a Python file takes 11, of which the Python linter 7. The documents-only critical path is now the base image build, a note under `SCOPE_MILESTONE_7.md` item 76. **Built** (`PLAN_TASK_225.md`, 28 September 2026): the precondition derives three flags from the change's scope, and the lint job skips MiMa, the dependency test, the Connect client's MiMa and the R linter for a change confined to Varka, the Scala and Java linters for documents, and the Python linter unless a Python file changed; the documentation job is off for a scoped change as it was for documents, and on for any file under `docs/`; done when the first scoped and documents-only runs after the merge show the times (section 5). On #456's final fork run (27 September 2026, run 36295726808) the jobs took 30 minutes for "Linters, licenses, and dependencies", 17 for the sql Varka suites, 16 for documentation generation and 11 for the catalyst suites: every Varka pull request waits on Spark's lint job about a quarter of an hour past its own tests. By step: MiMa 8m08s, the Python linter 7m33s, the Scala linter 2m55s, the R linter 2m51s, the dependency test 2m20s, the Connect client MiMa 1m48s. Row 177 scoped the module matrix for a Varka-only change and left this job, and documentation generation, running whole. Investigate which steps a change confined to Varka's files can move - MiMa checks Spark's public API, which such a change cannot touch; the R and Python linters read no Varka file unless `dev/` changed; the Scala linter and the license check do - and scope them by the same per-file test `build_and_test.yml`'s precondition already runs, keeping every step on a change that touches Spark's own files and on the weekly full-matrix run. Measure the wall time before and after on two pull requests of each kind | the fork CI of #456, 27 September 2026 | small |
| 226 | A hanging test costs a CI slot instead of failing with its name. **Done** (28 September 2026): `VarkaTestWatchdog`, mixed into every Varka suite and the three Varka test bases (the two fuzzers excepted, which are bounded by their iteration counts), runs each test beside a watchdog thread that at ten minutes (`varka.test.watchdog.minutes`) interrupts the test, and thirty seconds later, if the test still runs, writes every thread's stack under the test's name and halts the JVM; `SparkFunSuite`'s own `failAfter` had no signaler and so could only report a body after it returned, which a hung one never does; `VarkaTestWatchdogSuite` pins the three outcomes with a stubbed halt. On #456's first fork run the sql Varka suites' G14 (`VarkaCodegenGiveUpSuite`, three thousand outputs) ran past twenty-five minutes, printing only "still running", until the run was cancelled by hand; the cause was task 219's class-wide demotion loop, fixed there, and nothing bounded it. The job's own timeout is the only cap, so a regression of that kind holds the fork's single Build slot - one run at a time, row 176 - for the job's whole limit and names no test. Give the Varka suites a per-test time cap that fails the test with its name and what it was doing: SparkFunSuite's `failAfter` where a suite already wraps its loops in one (the fuzzers do), a default cap for every Varka suite otherwise, sized from the slowest test on record with a margin, and a thread dump on expiry so a hang is diagnosable from the log. Check it by reverting task 219's bisection on a branch and reading the failure | the fork CI of #456, 27 September 2026 | small |
| 227 | The CI queue leaves misleading checks and stops when it empties. **Done** (`PLAN_TASK_227.md` 5, 1 October 2026) on what it built: the sync dispatch is exercised, the runner's and by hand, each flipping a check within a minute; the wake-up path waits for the next slept-through run; the dispatcher on GitHub, which needs the owner's token, is `SCOPE_MILESTONE_7.md` item 76. **Built** (`PLAN_TASK_227.md`, 28 September 2026): `run` dispatches `update_build_status.yml` when a rerun completes, `run --while-open` idles instead of exiting while any PR is open, and a wait that finds its deadline passed asks once more before giving up, which is what a laptop that slept through a run needed on 28 September (the queue lost the day to that sleep); what remains is a dispatcher on GitHub rather than on the laptop, which needs a token of the owner's (section 3). Two frictions met on 27 September 2026. The queue holds a run by cancelling it and later reruns it; the pull request's `Build` check then shows the cancelled attempt until Spark's `update_build_status.yml` copies the rerun's result, and that job, on a fifteen-minute cron, was started by GitHub only every few hours that day - #444 and #447 read "Build: cancelled" for hours after passing. And `dev/varka_ci_queue.sh run` exits when the queue is empty, so a later `hold` waits until someone restarts the runner, which happened by hand several times that day. Make `run` dispatch the status sync (`workflow_dispatch` on `update_build_status.yml`) when a rerun completes, or write the check itself through the checks API, and give `run` a mode that stays up while any PR is open, idling with a long poll rather than exiting; keep every wait keyed on a completed status and a deadline, as the script's own rules require | the CI queue in use, 27 September 2026 | small |
| 228 | A cold shape's row path is 1.5 to 1.9 times slower than Spark's own. **Done** (`PLAN_TASK_228.md` 6 and 7, 27 September 2026): the projection's row path reads the columns it references into an `UnsafeRow` once per row when it reads some column more than once (`VarkaInputRows`), in both nodes. That takes about a third off both nodes' row path at every rung (to rows 70 to 56 ms at 16 entries, 187 to 126 at 54); to rows it is within 10% of vanilla's row-at-a-time path at seven rungs of nine and its first run within 5% of vanilla's at all nine, and below the cliff it is 1.30 to 1.44 times vanilla's whole-stage code, from 1.79 to 2.06. The JVM's own account: without the copy escape analysis left a closure per `add_months` in place, 33 MB a query to rows, eliminating six allocations in each of the projection's C2-compiled callers where it eliminates twelve on vanilla's path; with it, twelve, and vanilla's allocation per query (`VarkaColdPathProbe`, `dev/varka_c2_report.py`). Left for rows of their own: the columnar node's conversion into vectors, 5 to 31% behind vanilla's row path, and the other row loops over a batch (`PLAN_TASK_228.md` 8). *The admission check:* While a new shape's kernel warms, and whenever a batch falls back, a Varka node evaluates its projection with an `UnsafeProjection` over each batch's `ColumnarBatchRow`: `VarkaColumnarToRowExec` hands the rows on, and `VarkaProjectExec`, which the planner keeps under a consumer of batches such as the benchmarks' noop sink, writes them back into column vectors. **Admission check done** (`VarkaColdPathBenchmark`, 27 September 2026, a hundred thousand Arrow-cached rows, steady state): with no kernel the to-row node takes 70 ms at 16 entries and 187 at 54, and the columnar node 67 and 201, against 42 and 114 for vanilla with whole-stage codegen off and 39 and 412 with it on. So the gap is Varka's row path against Spark's row path, at every width, and it is not the vectors the columnar node rebuilds, since the to-row node is as slow; past the cliff both row paths still beat vanilla's uncompiled whole-stage method. Two premises of this row's first draft did not hold: the reads through `ArrowColumnVector` do not show in a JFR profile of the row path, and vanilla's whole-stage code is worth only 8 to 31% over its own row path below the cliff (39 against 42 ms at 16 entries, 90 against 118 at 52). What the profile does show is allocation that escape analysis removes on vanilla's path and not on Varka's: about an `Integer` and a closure per row from `add_months`' overflow check (`toIntExact` in `withOverflow`), concentrated in one of the projection's sixteen generated `greatest` methods. The task's first question is why: the lead is that the projection's reads through the vector, inlined into its generated methods, use up C2's inlining budget there, and the test is to copy the referenced columns into an `UnsafeRow` once per row before projecting, in both nodes, reading C2's inlining of the generated methods with and without it. Janino compiles vanilla's whole-stage class in 3 to 20 ms, so a whole-stage cold path below the cliff stays the alternative if the row path closes only to vanilla's | the owner, after task 181's first-query cost, 27 September 2026 | medium |
| 229 | `VarkaSizeLadderJitSuite` misses its probe's end marker when another writer's output runs into it. **Done** (28 September 2026): `VarkaProbeOutput` finds a marker anywhere in a line and reads its value from what follows it, the seven suites that fork a probe under `PrintCompilation` or `PrintInlining` read through it (the ladder, huge-method, width-audit, assembly, inlining-cliff and split-inlining suites), a line is read for a compile record as well as for a marker, and `VarkaProbeOutputSuite` pins a marker glued to a record on either side. The suite starts `VarkaSizeLadderJitProbe` with `-XX:+PrintCompilation` and stderr merged into stdout, and reads the child's output for `VARKA_LADDER_BYTES=` and `VARKA_LADDER_DONE`, each required on a line of its own (`startsWith` and `==` after trimming); the JVM's compiler threads and log4j write to the same stream without coordinating with `System.out`. On the fork CI of #473 (27 September 2026, run 36312813235) the probe exited 0 - so it had printed the marker, which comes before `spark.stop()` - and the test "past HugeMethodLimit the vanilla projection's consume method is never compiled at any tier" failed on "done was false", the last forty lines of the child's output all compile lines: the marker most likely shared a line with one. Find both markers anywhere in a line, the byte count by a pattern, and keep the tier parsing's tolerance of a garbled line; checked by a parser test over a marker glued to a compile line on either side | the fork CI of #473, 27 September 2026 | small |
| 230 | The columnar node's row path writes into vectors through an `UnsafeRow`. **Done** (`PLAN_TASK_230.md` 9 and 10, 27 September 2026): when every output has a primitive Java type, the row path writes each entry straight into its vector through Spark's mutable projection and a `MutableColumnarRow` (`VarkaVectorProjection`); other outputs keep the conversion. The columnar node's row path now runs level with the to-row node's, 0.97 to 1.04 times it at every rung, and within 10% of vanilla's row path under the same sink, from 5 to 31% behind (139 to 118 ms at 54 entries); C2 eliminates as many allocations in the mutable projection as in the unsafe one, and the allocation per query is unchanged. Left: a nondeterministic entry beside a fused one fails the query on the row path (row 232). **Planned** (`PLAN_TASK_230.md`, 27 September 2026): the admission check, from `VarkaColdPathProbe` under JFR, finds the `UnsafeRow` write and the converter's pass over it at about 10% of the node's time, where the 29 ms by which it trails the to-row node at 54 entries are 16% of it; the design is Spark's `GenerateMutableProjection` into a `MutableColumnarRow` over the output vectors for every output type the row has a typed setter for, today's path for the rest, and a projection generated by Varka only if that falls short. `VarkaProjectExec`, which the planner keeps under a consumer of batches, serves a new shape's batches while its kernel warms by projecting each row into an `UnsafeRow` that `RowToColumnConverter` then reads back into writable column vectors. With task 228's copy of the referenced columns it stays 5 to 31% behind vanilla's row-at-a-time path, where the to-row node comes within 10% (`PLAN_TASK_228.md` 6 and 8); the noop sink the Varka benchmarks write to, the cold-start one included, runs this node. The candidate is a projection that writes each entry straight into its output vector. First the admission check: the conversion's share of the node's time and allocation, from `VarkaColdPathProbe columnar varka` under JFR | task 228's outcome, 27 September 2026 | small to medium, measured |
| 231 | The other row loops read each column through the batch's vectors. Task 228 found that Spark's `UnsafeProjection` over a `ColumnarBatchRow` that reads a column many times leaves C2's escape analysis allocations it removes over an `UnsafeRow`, and gave the projection nodes' fallbacks a copy of the referenced columns (`VarkaInputRows`, `PLAN_TASK_228.md` 7). The same reads remain in both of `VarkaFilterExec`'s fallbacks, the kernel evaluator's residual columns, the filter evaluator's generic entries and the merge-at-row projection, which runs on the kernel path of every mixed projection, not only while a kernel is cold. Each needs a benchmark case before the copy goes in, with `dev/varka_c2_report.py` to say whether escape analysis is losing anything there: their shapes may read each column once, where the copy is not made | task 228's outcome, 27 September 2026 | medium, measured |
| 232 | A nondeterministic entry beside a fused one fails the query. **Done** (`PLAN_TASK_232.md`, 27 September 2026): a projection with any nondeterministic entry now declines whole, with the reason on each entry, as a filter with a nondeterministic conjunct already did, so it runs as vanilla's and gives vanilla's values; a differential test runs the reproducer to rows and through the noop sink, and fails without the fix. `SELECT add_months(d, 1), rand(7)` over an Arrow-cached table plans a Varka node - the compiler fuses `add_months` and leaves `rand` a residual entry - and the node's row path evaluates `rand` through a projection nothing initializes, where Spark's `ProjectExec` initializes its own with the partition index: `NullPointerException: Cannot invoke "java.util.Random.nextDouble()"`, on both nodes, with no kernel and on the default warm-up path, found by task 230's check of its risks (27 September 2026, `PLAN_TASK_230.md` 11). The merge-at-row projection on the kernel path and the kernel evaluator's residual columns evaluate residual entries the same way and are likely to fail alike. Two fixes: decline a projection with any nondeterministic entry at planning, as the compiler already declines a nondeterministic filter condition, so that vanilla's plan runs it; or initialize every projection that evaluates an entry with the partition index, which gives the row path and the kernel path a generator each, so that a row's value would depend on the path its batch took, where vanilla draws from one generator per partition. The first keeps vanilla's answers. Pinned by a test over both sinks, with and without a kernel | task 230's check, 27 September 2026 | small |
| 233 | A post for Spark users: when Spark stops compiling your query, and how you'd know. **Planned** (`PLAN_TASK_233.md`, 1 October 2026): the record has the post's list and not its numbers, so two investigations come first with their predictions registered - a census of operators outside whole-stage codegen by reason over the golden-file and TPC suites, and a Spark-style benchmark of what the silent give-ups cost, one `from_json` in a projection and a projection past `maxFields` - then the snippets on stock 4.2.0, then the draft. The census (task 188, `PLAN_TASK_188.md`) lists 34 places where Spark's code generation gives up, and 23 have a reproducer in `VarkaCodegenGiveUpSuite`; the rest are lists of operators, mitigations, properties of the generated source, and one failure no test can provoke, a compile that fails only on an executor. Neither post walks through them: task 210's covers the 8000-byte cliff and its two traps and links the census, and task 181's, for developers, shows three rows beside Varka's answers. Most of the 34 are quiet: 19 log nothing, 7 log only at INFO or DEBUG, which a default spark-shell hides, 6 log at WARN or ERROR, and 2 show only in EXPLAIN or are not yet known (`PLAN_TASK_188.md` 2). The post groups the cases by what the user sees rather than by where Spark gives up - silent, where an operator drops out of whole-stage codegen and the only sign is a missing `*(n)` in EXPLAIN; hidden in the log; logged, with a fallback; and failing the task - and each section ends with a spark-shell snippet and what to do about it: a rewrite, a setting or an upgrade. It keeps task 210's rules for this audience: census entry numbers stay out of the body, and Varka is not mentioned. The work is turning the reproducers into spark-shell snippets, writing snippets for the list entries, and checking each on a released Spark, since the census ran on the fork; a speed claim needs a committed measurement like any other. It needs no Varka result, so it does not wait on task 181. **Done when** the post is published with every snippet run on a named Spark release, and this row links it | the owner, 29 September 2026, from the post themes proposed after task 188 | medium |
| 234 | A shared calendar prefix over a guarded column fails the emitter's word check. **Done** (`PLAN_TASK_234.md` 9, 30 September 2026): the word-liveness walk defers a date whose materialized prefix the group loads and walks it only when the lowering visits it, recording those dates in `Slots.visitedMaterializedDates`, which the lowering reads; the regression shapes emit and answer, all 154 failing seeds of the night and 20 fresh ones replay clean at 200,000 iterations. After the review (9.4) a loaded date is visited only when something outside it reads its word, and the body plans slots only for the nodes it emits, which moves 100 int-lane fuzz shapes of the oracle, none of them larger. **Planned** (`PLAN_TASK_234.md`, 30 September 2026): found by the night fuzz campaign of 29 to 30 September, which failed 152 of 205 IR fuzzer runs on `assertWordsLive` ("word slot N is stored but never loaded") and none of 152 composition runs. Under task 198's `materializeChronoPrefix` a later group loads a date's prefix and does not emit the date, so a `GuardedDay` over it runs its range check in the producing group only; `Slots.liveWords` still walks the date and keeps the guard's word live, and over a bare column that word is an input's, stored by the lane group's prologue and never read. `year(gd(c1))`, `lastDay(c0)`, `month(gd(c1))` fails under every default. No wrong answer - the planner logs the failure and the executor runs the row path - and not task 148, whose division is not needed. The design: the walk defers such a date and walks it only when the lowering visits it, which is when its word is its own and live, and the lowering reads the planner's record of those dates rather than restating the rule; the IR fuzz suite names the iteration of an emitter self-check. **Done when** the regression shapes emit and answer on both bodies, every failing seed of the night replays clean at 200,000 iterations, and `emitted_bytes.json` is unchanged | the owner, 30 September 2026, from the night's fuzz campaign | small |
| 235 | Fuzz the `NarrowLane` root. *Found by the code review of #520 (task 199), 30 September 2026: the cost model's coverage test trusted the grammar's reach, and `NarrowLane` had no price.* The IR fuzzer never draws a `NarrowLane` root, so TIME's narrowing is never checked against the reference evaluator. The node is admitted at an output root only, and both generators build nodes under other nodes, so `VarkaIrFuzzSuite`'s two reach tests exclude it by name; the TIME compiler builds it at the root of every TIME kernel (`VarkaTimeCompiler`), and only the emitter suite's fixed shapes run it. `VarkaReferenceEvaluator` already evaluates it. Closing it takes a root arm in `LongShapes` - a drawn root wrapped in `NarrowLane` where its bound fits 32 bits - and the name dropped from both exclusions. The long-lane corpus then moves, so `emitted_bytes.json`'s long fuzz digests and `emit_cost_audit.json` are regenerated in the same change. | the review of #520 | small |
| 236 | Plan a kernel's size before building it. *Added 30 September 2026 on the owner's decision, from the review of size control after task 190 step 2 (#529): size control is a goal this milestone owes.* Size control reacts to measurement one fallback at a time. `VarkaLoopEmitter.emit` regroups on bytes, splits on call sites and rolls those splits back, drops the exact grouping and then the prediction, and splits the driver; the compiler bisects a class-wide decline (task 169), which is also how several kernels find their split. The reactions interact in an order that matters, one emission can build a class several times, and several kernels' split search takes several emissions where the split driver takes one (`PLAN_TASK_190.md` 11.5). The pieces for a plan now exist: the cost model predicts each group method's bytes and call sites (task 199), the exact partition is a dynamic program over them (task 200), and the driver is 20 bytes and 44 a group (`PLAN_TASK_190.md` 10.5). One planner decides the kernels, their groups and their stages from those predictions, the class is built once, and a measurement corrects a wrong prediction at most once, with today's reactions kept as the last resort. **Done when** the cost audit counts the builds of every shape of its corpus before and after, and under the defaults each builds once except where the audit names the prediction a measurement corrected; the compiler finds several kernels' split without bisecting; and `VarkaWideKernelBenchmark`'s plan-time section is measured before and after | the review of #529 | large |
| 237 | Admission probes that load no class. **Done** (30 September 2026): the compiler asks `VarkaShapeCache.admit`, which builds and measures a shape and holds its bytes without defining a class, and remembers a decline as before; the first lookup that runs the shape defines its class from those bytes and drops them, and a shape already defined under some loader is not built for the question. The held bytes are bounded at 64 MB, and a shape evicted from them is built again only when it runs. A test bisects a projection of two hundred entries on the unrolled driver: its probes define no class, and the kept kernel's first lookup defines it without a further build (`VarkaShapeCache.buildCount`). *Added 30 September 2026 on the owner's decision, from the same review.* The compiler asks whether a kernel fits through `VarkaShapeCache.getOrEmit` (`admitBySize`), and every emission there defines its class in a loader of its own, so every probe that fits is a loaded class held until the cache evicts it. A bisection keeps every probe that fits: task 169's demotion and several kernels' split search each leave several classes loaded per wide shape, each megabytes of bytecode, of which one is used. And at planning on the Spark driver, which runs no kernel, even a single admission loads a class nothing will run. A probe needs only a build and a measurement (`VarkaEmittedClass`). Its verdict is cached without its class, the bytes of the probe the plan keeps are held so that the kernel is defined from them on first use rather than built again, and only a kernel that runs is defined. **Done when** a projection that bisects leaves exactly its kept kernels defined, which a test counts in the shape cache, planning defines no class, and the kept kernel is still built once between the compiler's admission and the evaluator's first batch. Row 236 removes most bisection; this row holds wherever a probe remains | the review of #529 | small to medium |
| 238 | Fuzz past the ceilings. **Done** (30 September 2026): the IR fuzzer draws byte budgets of 1000, 2000 and 4000 beside 0 and 8000, and a new test composes `drawWideShape`'s roots into kernels of at least 250 outputs under five option variants - the split driver, the whole driver, the prediction on, call sites split with the driver whole, and a 2000-byte budget - checking every composition row by row, under the defaults where its variant declines. The emitter counts what its size control did (`VarkaEmitTrace`), and a run that reaches no instance of the regroup, a call-site split, its rollback, a stage split, either grouping switch dropped or a decline fails; the committed seed reaches all seven in ten compositions. The composition fuzzer spreads projections of 150 to 300 coverage rows over eighty copies of the table's columns, so every one is served by several kernels, and runs their int-lane kernels against the reference evaluator (`VarkaKernelCheck`, now shared with the IR fuzzer); it also counts a further kernel's entries as fused, which its property had not. *Added 30 September 2026 on the owner's decision, from the same review.* The IR fuzzer draws the byte budget as 0 or 8000, and its shapes stay far below where the size machinery engages - the driver's ceiling at about 180 groups, `MAX_INPUTS` columns, the class-file caps - so the regroup's halving, the split driver's stages, several kernels and the fallbacks between them are pinned only by hand-written tests (`PLAN_TASK_190.md` 11.3). Two draws close it. Smaller byte budgets, from about 1000 to 4000 bytes, bring every ceiling down to the widths the fuzzer already builds, with the property widened from fused-and-correct to fused-and-correct or declined with a reason. And projections composed from drawn roots, through the compiler past 180 groups and 64 columns, run against the reference evaluator. **Done when** a default fuzz run counts each mechanism it reached - the regroup, a call-site split and its rollback, a stage split, each grouping fallback, a further kernel, a decline - and every seed of the nightly reaches each of them and passes | the review of #529 | medium |
| 239 | Locals a loop method stores and never reads. *Added 2 October 2026 on the owner's decision, from task 223's bytecode count (`PLAN_TASK_223.md` 9.3); size control.* After task 223, about 116,000 reference locals in the cost corpus's loop and epilogue methods are written and never read, two leftovers of later work. 66,024 are segments: step (3) of `VarkaBodyEmitter`'s prologue builds every output's validity segment in every body, although since task 70 the bitmap pass, or a fill on a dense batch, writes most outputs' validity, so most bodies never touch it. 50,020 are calendar prefix vectors: a group that consumes a materialized prefix (task 198) reloads it whole from scratch every lane group, where only the month vector is loaded on demand and a field reads a subset - `month` and `quarter` one vector of six, `year` four. The rest are 145 epilogue masks and 5,708 shared slots of dates the calendar prefix visits once, which task 223's count takes for two uses. At about ten bytes a segment and twenty a reload they are some 3% of the corpus's loop and epilogue bytes; the run-time cost is not known - the segments are built once a batch and their allocation is dead to C2, the reloads run every lane group and their bounds checks may stay. The admission check measures it before any change: the reloads trimmed to what a group's fields read and the segments to what a body writes, `VarkaWideKernelBenchmark`'s calendar ladder and mixed families on the laptop, both ways. **Done when** a bytecode check over the cost corpus finds no reference local of a loop or epilogue method stored and never read, or names each kind it leaves and why; the benchmark's before and after are committed; and the emitted-bytes oracle, the price tables and the cost audit are regenerated with the diff reviewed | task 223's bytecode count, 2 October 2026 | medium |
| 181 | The closing task: the post. **Published** (`PLAN_TASK_181.md` 14.4, 1 October 2026) at https://vecbricks.github.io/under-8000-bytes-by-construction/, rendered from `POST_MILESTONE_6.md` at `4ab3db80cce`, site commit `d2391be`; the first post links it. **Drafted** (`PLAN_TASK_181.md` 14, 1 October 2026): `POST_MILESTONE_6.md`, restructured the same day on the owner's reading into three questions (`PLAN_TASK_181.md` 14.2: why Spark cannot just split the method; what measuring the class looks like, and what we got wrong; what it buys and costs), 3840 words, with three listings from runs, four concept drawings of the architecture (14.3) and five charts drawn from committed files, at zero orphans, and its trailer drafted in `POST_MILESTONE_6_SHORT.md`; reviewed for correctness the same day (14.1: seven errors and five inconsistencies corrected); owed before publication are the owner's review, the tickets reread on the day, the page, and the first post's link. *28 September 2026*: the size distribution is committed (`PLAN_TASK_181.md` 11, `VarkaCodegenMethodSizes-jdk25-results.txt`): of 350384 methods Spark generates over its golden-file and TPC suites one passes 8000 bytes, `modified-q3`'s aggregate at 12450. *28 September 2026*: the inlining evidence is committed (`VarkaSplitInliningSuite`, `PLAN_TASK_181.md` 10) - past a few thousand bytes of split code C2 compiles each split method but stops inlining the calls at `DesiredMethodLimit`, 90 of 101 refused at 300 branches; and the owner made every row of `PLAN_TASK_181.md` 4 owed before the post is submitted: rows 170, 172 (the 9V45 figure), 197 and 202, and the size distribution of generated methods. *27 September 2026, evening*: the committed cold-start file is regenerated with tasks 228 and 230, and the runner's run section 4 owed is committed beside it (`PLAN_TASK_181.md` 9.1): below the cliff the first query costs within about a tenth of stock's on the laptop and up to half again on a four-core runner, past it it is already faster, and the draft's figures are to be replaced from the two files. *27 September 2026*: the first-query cost is written in (`PLAN_TASK_181.md` 9) - with the warm-up on by default, Varka's first query of a new shape is slower than stock Spark's below the cliff and already faster past it, and the cost is paid once per shape; the runner's run of `VarkaColdStartBenchmark` is owed before publication. **Outlined** (`PLAN_TASK_181.md`, 25 September 2026): the claim and its five bounds, eight sections each naming its figure and its committed files, and what the draft still owes - task 195's first-query cost and task 188's Varka arm; the 9V45 figure, 197, 170 and 202 improve it but do not gate it. *Split 25 September 2026*: sections 3.2 and 3.3 moved to task 210's post (`PLAN_TASK_181.md` 7) | 2.10 | last by definition |

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

## 9. Review at the halfway point, 25 September 2026

*Added on 25 September 2026 at the owner's request, two days after the
milestone opened. Section 4's ordering is not rewritten; 9.2 is the ordering
for what remains, and where they differ 9.2 is current.*

### 9.1 The state against 1.3

| 1.3 | State on 25 September |
| :-- | :-- |
| 1. No admitted shape fails to emit | Done: 87, 168, 169 |
| 2. The size ladder committed | Done: laptop, runner and figure (171) |
| 3. One realistic query | Done in code (172, `splitConditions` on by default); the full-width 9V45 figure is still owed, 0 hits in 15 dispatches |
| 4. The census complete | 34 entries read; reproducers for 15 merged or in review; G13, G16 and G19 have none; the reproducers pin vanilla's side, and only G4's pins Varka's |
| 5. The post published | Not outlined |
| 6. Beside the spine | The CI queue (176) landed; 173, 174, 175, 177, 178, 179, 182, 183 and 184 have not started, and 180 has no commit |

The table had 23 rows when the milestone opened and has 43 today, six of them
optional research. The spine is nearly through and the research rows around it
are mostly done; the two tracks the owner set on 23 September are uneven, since
promotion has not moved at all and the compiler-legibility rows have not started.
The pattern is that the next measurable row kept winning over the row with no
number in it, and a review against 1.3 rather than against the table is what
made it visible.

**What is missing, in the order it matters for the post.**

1. The post's honest bounds: 195 (what the first query costs, since a
   12167-byte kernel is emitted and compiled per query), 197 (whether the gap
   survives parallelism) and 2.10's native-accelerator comparison. Risk 1 of
   section 6 is about exactly these, and none is started.
2. The census's Varka column is a table, not a test. `VarkaCodegenGiveUpSuite`
   makes vanilla give up in each case; the claim the post makes is Varka's
   answer, so every reproducer should assert it too: the fused node present, or
   a decline with the recorded reason.
3. Two of section 5's acceptance checks are by hand. The fuzz campaign was
   reinterpreted in `PLAN_TASK_169.md` because `VarkaIrFuzzSuite` draws IR
   directly and never goes through the compiler, and `emitted_bytes.json` is
   referenced by no workflow or script.
4. Row 170's question, whether a budget below 8000 earns its calls, is open,
   and the post has to name the threshold Varka targets.
5. Track A: 180's cadence and 183's onboarding, both with their material
   already committed.

### 9.2 What comes first

| Order | Task | Why first | Size |
| ---: | :-- | :-- | :-- |
| 1 | 181, the outline only | It decides which of 195, 197 and the 9V45 figure the post needs, before more measuring | small |
| 2 | 188, Varka's side pinned | Every reproducer asserts Varka's answer; G13, G16 and G19 get theirs | small |
| 3 | 195 | The first-query cost is the bound the post cannot publish without, and a kernel cache across queries is the product question behind it | medium, measured |
| 4 | 179 with 177 | A fuzzer through the compiler to the emitter as a standing job, asserting fuse-or-decline-with-reason and never throw; `emitted_bytes.json` checked in CI | small |
| 5 | 180 and 183 | One post from committed material (the demo, the ladder), and 183's issues opened from this table | small, owner-facing |
| 5a | 210, the vanilla Spark post | *Added 25 September 2026.* The first of the milestone's two posts, and the promotion track's largest item; it needs none of 195, 197 or the 9V45 figure, only one Spark-style benchmark of the split-call case, its inlining evidence and three cheap reproducers (`PLAN_TASK_210.md` 4), so it is drafted while 195 is measured | medium |
| 6 | 170 | The threshold answer, from the A/B task 87 left ready | small, measured |

After those, in this order: 173, 174 and 184 (legibility, cheap), 175 (the
Java port, once 184's map exists), 200 and 198 (the compiler improvements with
measured motivation), then 197 and 202. New rows join this table only with a
reason to do them in this milestone rather than in `SCOPE_MILESTONE_7.md`.

### 9.3 Lessons, recorded in the skills files

* A predicted immunity is checked at the input path before it is written
  (`testing-and-debugging.md`): 185's cache finding, the INFO line that made
  the cliff not silent, and 192's default-cache measurement were all the same
  mistake.
* A two-track milestone drifts to the measurable track, and the review that
  catches it is against the done-when list, not the table
  (`working-in-this-repo.md`).
* Grep the record before choosing an algorithm; build both designs and read
  the assembly; reread every plan paragraph against its results file before
  committing, because the quote checker catches numbers and not adjectives.
  These three are already in the skills files and in the task plans of 172
  and 192, and are listed here because the review found each of them paid for
  in this milestone.
