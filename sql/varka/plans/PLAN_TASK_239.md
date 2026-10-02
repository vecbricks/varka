# Task 239: locals a loop method stores and never reads

*Planned 2 October 2026 (milestone 6 row 239). Its admission check ran the same day on task 223's
branch, which is master at `8afa3fe4bd3` with task 223's change: the timings at `49c1aac9c20`, and
the census again at `5a17a4ab18f`, after that task's review.*

## 1. Where this came from

Row 239 of `PLAN_MILESTONE_6.md`, added on 2 October 2026 on the owner's decision from task 223's
bytecode count (`PLAN_TASK_223.md` 9.3), as size control. Task 223 stopped giving a node a shared
slot it reads once, and its count of the emitted bytecode found more reference locals written and
never read, all leftovers of later work:

* step (3) of `VarkaBodyEmitter`'s prologue builds every output's validity segment in every body,
  although since task 70 the bitmap pass, or a fill on a dense batch, writes most outputs' validity;
* a group that consumes a materialized calendar prefix (task 198) reloads it whole every lane
  group, where only the month vector is loaded on demand;
* a few epilogue masks, and shared slots of dates the calendar prefix visits once, which task 223's
  count took for two uses. Task 223's review has since removed these (`PLAN_TASK_223.md` 9.4).

The row asks for the run-time cost to be measured before anything changes, since it was not known:
the segments are built once a batch, and the reloads run every lane group.

## 2. The admission check, done

Two scratch tools over the bytecode, not committed. A census parses every loop and epilogue method
the emitter builds for the cost corpus - the first 1000 fuzz shapes and the 200 wide shapes of
each lane, and the ladders - at the shipped options and sixteen int lanes. It finds each reference
local stored and never loaded, and names what the stored expression built. A transformer removes
each such store together with the expression it stored, found by walking back the stack effects,
and repeats until none is left, since a removed load can leave its segment unread. Trimmed this
way, a class can be timed against the class as emitted before the emitter changes at all.

### 2.1 The census

| what the local held | stores never read | in masked bodies |
|---|---:|---:|
| an output's validity segment | 61,746 | 55,298 |
| a calendar prefix vector reloaded from the scratch | 50,020 | 49,268 |
| an input's data segment | 4,288 | 4,114 |
| the epilogue's mask | 212 | 132 |
| **total** | **116,266** | |

Nothing else: no store the census could not attribute. Against the row's count, made before task 235
changed the long draws, the segments are nearly the same, ten more (66,034 against 66,024), the
reloads exactly so, and the masks have moved with the long corpus (212 against 145). The row's 5,708
shared slots are gone. On the census's first run, before task 223's review, they numbered 5,722. The
review made that task's count follow the walk (`PLAN_TASK_223.md` 9.4), and its new
`VarkaUnreadLocalsSuite` holds the corpus to none. Two smaller kinds are new as kinds. An input's
data segment is unread where the body reads the column only through its validity, as
`isNotNull(col)` does. The mask is unread where the epilogue loads no column value and stores no
value, as for a condition over literals or a lone `isNotNull`.

Trimmed of all of them, the loop and epilogue bytes fall 3.34% over the corpus:

| family | loop and epilogue bytes | all trimmed | the segments alone | the reloads alone |
|---|---:|---:|---:|---:|
| fuzz, int | 4,416,351 | -1.39% | -1.26% | -0.11% |
| fuzz, long | 1,933,645 | -2.09% | -2.04% | 0 |
| wide, int | 29,476,215 | -4.96% | -1.41% | -3.55% |
| wide, long | 16,871,551 | -1.33% | -1.33% | 0 |
| size ladder | 2,602,528 | -2.10% | -1.47% | -0.64% |
| `make_date` ladder | 171,968 | -2.16% | -1.13% | -1.03% |
| cheap tails | 26,092 | -17.91% | -15.64% | -2.27% |

Every trimmed class of the corpus verifies. Trimmed, the size ladder at 100 and 400 entries and the
mixed family at 100 and 200 answer as the reference evaluator does on both bodies, at lengths 1,
17, 129 and 4096.

### 2.2 Run time and allocation, on the laptop

`VarkaUnreadLocalsBenchmark`, committed with its results
(`sql/catalyst/benchmarks/VarkaUnreadLocalsBenchmark-jdk25-results.txt`, AMD Ryzen AI 9 HX PRO 370,
JDK 25), runs the size ladder and the mixed family as `VarkaWideKernelBenchmark` does, a million
rows in 4096-row batches. Each kernel runs as emitted, stripped of every kind by
`VarkaUnreadLocalsTrim`, stripped of the segments alone and of the reloads alone at the widest
size, and as emitted again last as a control for the order of the cases. The figures are the best
of at least five iterations, in nanoseconds a row:

| shape | body | as emitted | stripped | as emitted, again | segments stripped | reloads stripped |
|---|---|---:|---:|---:|---:|---:|
| ladder, 100 entries | null-free | 69.6 | 67.7 | 71.0 | | |
| ladder, 400 entries | null-free | 285.9 | 281.1 | 281.5 | 281.9 | 282.7 |
| ladder, 100 entries | every seventh row null | 84.2 | 76.1 | 77.3 | | |
| ladder, 400 entries | every seventh row null | 337.9 | 308.8 | 338.4 | 306.0 | 340.8 |
| mixed, 100 entries | null-free | 40.0 | 38.7 | 40.1 | | |
| mixed, 200 entries | null-free | 79.3 | 78.3 | 77.9 | 78.3 | 78.5 |
| mixed, 100 entries | every seventh row null | 46.9 | 47.9 | 43.4 | | |
| mixed, 200 entries | every seventh row null | 93.6 | 93.0 | 93.7 | 92.4 | 95.4 |

The masked ladder at 400 entries runs 8.6% faster stripped, against a control 0.1% from the first
run, and nearly all of it is the segments: with only the segments stripped it is 9.4% faster, with
only the reloads stripped not at all. Every other case is within its control's spread, the masked
ladder at 100 entries included (84.2 against a control of 77.3). A scratch run of the same setup
before this benchmark was committed read 12% at 400 entries; a single case on this machine moves
by up to 15 to 30% now and then, which is why the control is in every section.

What one batch allocates, from the JVM's per-thread counter over 1000 batches once the timed runs
have compiled the kernels:

| kernel | body | as emitted | stripped |
|---|---|---:|---:|
| size ladder, 400 entries | null-free | 40,084 | 24,000 |
| | every seventh row null | 52,040 | 28,000 |
| mixed family, 200 entries | null-free | 22,560 | 14,520 |
| | every seventh row null | 28,640 | 22,520 |

The row expected a dead segment's allocation to be dead to C2 as well. It is not: in a JVM that has
run many kernels the dead segments are allocated a batch in both bodies, 16 to 24 KB on the
ladder. (A scratch test of one kernel alone found the dense body's allocation nearly gone, 174
bytes, so how much escape analysis removes depends on what else the JVM has compiled.)

### 2.3 The timings and the review

The timings were first taken by a scratch copy of the benchmark's setup, and the plan quoted them
with no committed results file, against `sql/varka/AGENTS.md`'s rule that a performance claim
traces to one. The review of this pull request found that; the benchmark above replaces them.
The shared slots that task 223's review has since removed were never part of it.

### 2.4 What the check admits

The row, with its premise corrected. The segments are not free in the masked body: each is an object
a batch, and trimming them is the one change with a measured run-time effect. The reloads are more
than half of the bytes trimmed over the corpus, most of them in the wide int family, and each is a
Vector API call site against the budget of task 209; on the laptop they cost no time. The input
segments and the masks are small and come out with the same reasoning.

## 3. The design

### 3.1 An output's validity segment, where the body writes that validity

A loop or epilogue body builds an output's validity segment only where `keepsPerGroupWrite`
holds: neither filled once by the driver on a dense batch nor written whole by the bitmap pass.
That is the predicate the lane group's write already reads, so the segment and its one writer
cannot disagree, and `Slots` plans no local for the others. The driver keeps its segments: the
unrolled driver zeroes, fills and runs the bitmap pass through them, and the driver from a table
maps none.

### 3.2 A prefix reload, of the vectors the group's tails read

Each calendar tail states which of the prefix's vectors it reads: `month` and `quarter` one,
`year` four, and so on for the others. A fragment's set is the union over the nodes that consume
it, as `fragmentsReadingMonth` unions the month today, and a consuming group loads that set and no
more. The month's on-demand load becomes one case of the rule. The producing group still stores
what it stores now, since a later group may read any of it.

### 3.3 An input's data segment, where the body reads the column's values

Step (4) maps a column's data where the body emits that column's value. A column the body reads
only through its validity word, as under `isNotNull`, keeps its null state and maps no data.
`skippedColumns` cannot tell the two apart: it holds the columns outside the emitted set, and a
column under `isNotNull` is inside it. The rule that can is task 223's use count
(`Slots.bodyUses`), which follows no edge from an `isNotNull` to its column: a column is read for
its value when the body serves it as a root or reaches it by an edge that count follows. That
walk runs today only for a loop or epilogue body under CSE, since only there do its counts decide
anything. It is split so that the edge rule runs for every loop and epilogue body, and the counts
are used only under CSE, so the two questions are answered by one rule.

### 3.4 The epilogue's mask, where the body has a reader of it

The mask is read by four things, in three classes: a column's masked load, a value root's masked
store, a guard's condemnation and a prefix transfer. Building it at its first reader would rest on
that reader dominating the rest, and it does not: `emitInRanges` loops inside the lane group, and
a reader can sit after the loop. So `Slots` decides from the body whether any of the four is
there:
* a column read for its value, the set of 3.3;
* a root that is neither a condition nor a narrowing, whose store takes the epilogue's mask (a
  narrowing builds its own);
* a guarded node, which ANDs its condemnation with the mask;
* a materialized prefix the group stores or loads.

An epilogue with none of them builds no mask. A reader the predicate misses fails verification at
the first emission, as an unassigned local, so a new reader cannot be missed quietly. The slot also
stands in today for "this body is an epilogue" in four places, which read a flag of their own
instead.

### 3.5 The shared slots, already gone

Task 223's review made that task's count ask the walk's own rule (`PLAN_TASK_223.md` 9.4), and the
suite it added, `VarkaUnreadLocalsSuite`, holds the corpus to no shared slot that nothing reads.
This task widens that suite to every kind of reference local (section 5), so no kind is left to
name.

### 3.6 Behind a switch

`VarkaEmitOptions.elideUnreadLocals`, off until measured. The form that builds everything stays as
the reference the differential suites use, so one runner run can time both forms (section 6).
Runner CPUs differ between runs, so a before and an after taken in two runs would compare two
machines. The trims win by construction rather than by a machine's measurement, so whether the
arm stays once the default is on is the owner's call, as for any structural option.

### 3.7 The alternative: a dead-store pass over the built class

The admission check's transformer could ship as the fix: one pass that removes every unread
reference local, whatever leaves it, including leftovers not yet written. It is not the design, for
three reasons:
* It reads and rewrites every class at every emission, a cost task 191 spent a task taking out of
  emission.
* It leaves the dead locals planned.
* It would hide the emitter's own bookkeeping errors instead of showing them.

The check of section 5 catches a future leftover instead, at test time and once.

### 3.8 What is deliberately unchanged

* What every node computes, and the slots of every local that is read.
* The driver's segments, and the producing group's prefix stores.
* The validity paths themselves: the bitmap pass (task 70), the fill on a dense batch
  (`denseValidityOnce`) and the per-group write.

### 3.9 Registered op counts

None move for a node's result. A consuming group's prefix loads fall from five or six vectors to
the ones its tails read, and every body sheds the segment and mask calls of 2.1.

## 4. Files

| file | what |
|---|---|
| `VarkaBodyEmitter.java` | steps (3) and (4) build the segments 3.1 and 3.3 keep; the epilogue's mask where the body has a reader of it |
| `Slots.java` | no local for an unread segment; the columns whose values a body reads; the prefix vectors a fragment's consumers read |
| `VarkaChronoLowering.java`, `VarkaVectorWalk.java` | the reload of 3.2; the mask's readers |
| `VarkaEmitOptions.java` | `elideUnreadLocals` |
| `VarkaUnreadLocalsSuite.scala`, `VarkaUnreadLocals.java` | task 223's check of shared slots, widened to every reference local: section 5 |
| `emitted_bytes.json`, `VarkaEmitCostTable.java`, `VarkaEmitCostRegister.java`, `emit_cost_audit.json` | regenerated, the diff reviewed |
| `VarkaWideKernelBenchmark.scala` and its results file | a section for the two forms |
| `PLAN_MILESTONE_6.md` | row 239 |

## 5. Tests, and what each is for

* **The check**: over the cost corpus at the shipped options, no reference local of a loop or
  epilogue method is stored and never read under the switch. It is task 223's
  `VarkaUnreadLocalsSuite`, widened from shared slots to every kind. With the switch off it pins
  today's census, so a leftover added to either form is seen. This is the row's first done-when,
  with no kind left to name.
* **The bodies still answer**: every emitter suite, the IR fuzzer drawing the switch like every
  boolean, and the composition fuzzer. A reload short of a vector its tail reads, or a segment not
  built for an output whose validity the body writes, fails as a verifier error at the first
  emission, not as a wrong answer.

## 6. The measurement

* **The bytes**: `emitted_bytes.json`, the price tables and the cost audit regenerated under the
  new default, with the diff reviewed: bytes and call sites fall, and the audit's builds and loop
  methods say whether any grouping moved.
* **On a GitHub runner, a section of `VarkaWideKernelBenchmark`**: the size ladder at 100 and 400
  entries and the mixed family at 100 and 200, both bodies, with the switch off, on, and off again
  last as the control. Committed, which is the row's before and after.

### 6.1 Predictions, registered before the run

1. **The check** finds no unread reference local under the switch.
2. **The bytes** of the corpus's loop and epilogue methods fall about 3.3%, each family within a
   tenth of a point of the transformer's figure in 2.1.
3. **Builds and loop methods**: the reloads were call sites, so a group can drop below the call-site
   budget it was split for, but none rises above one. No shape of the audit gains a build or a
   loop method, and some lose a call-site split.
4. **Run time**: on the runner the masked ladder runs at least 5% faster at 400 entries, where the
   laptop gave 8.6%. Every other case stays within its control's spread.
5. **Allocation**: the ladder at 400 entries allocates 16 to 24 KB less a batch, as on the
   laptop.

## 7. Risks

1. **The tails' stated reads go wrong when a tail changes.** A tail that reads a vector it did not
   state fails verification at its first emission. One that states a vector it never reads leaves
   a dead load the check finds.
2. **A mask reader the predicate of 3.4 does not list.** A future reader of the epilogue's mask
   that the predicate misses reads an unassigned local, which the verifier rejects at the first
   emission, in the suites.
3. **The masked ladder's gain is the laptop's**: allocation and inlining differ across JDKs and
   processors, which is why the runner measures it and the default waits for that.
4. **The price tables move** with every method's bytes, so the cost model's fit is regenerated with
   them, as for any lowering change.

## 8. Sequencing

1. This plan, with row 239 marked Planned. It is stacked on #547, which adds the row, and is
   rebased once #547 merges.
2. The change behind `elideUnreadLocals`: the trims of 3.1 to 3.4, the check, the regenerated
   oracle, price tables and audit, and the benchmark section.
3. The runner's run, the predictions scored, the default set, and row 239 marked done.
