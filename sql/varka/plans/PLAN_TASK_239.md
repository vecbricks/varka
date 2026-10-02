# Task 239: locals a loop method stores and never reads

*Planned 2 October 2026 (milestone 6 row 239), with its admission check run the same day on task
223's branch at `49c1aac9c20`, which is master at `8afa3fe4bd3` with task 223's change.*

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
  count takes for two uses.

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
| a shared slot, a `dup` and a store | 5,722 | 5,532 |
| an input's data segment | 4,288 | 4,114 |
| the epilogue's mask | 212 | 132 |
| **total** | **121,988** | |

Nothing else: no store the census could not attribute. Against the row's count, made before task
235 changed the long draws, the segments are the same (66,034 against 66,024), the reloads exactly
so, and the masks and slots have moved with the long corpus (212 against 145, 5,722 against
5,708). Two smaller kinds are new as kinds. An input's data segment is unread where the body reads
the column only through its validity, as `isNotNull(col)` does. The mask is unread where the
epilogue loads no column value and stores no value, as for a condition over literals or a lone
`isNotNull`.

Trimmed of all of them, the loop and epilogue bytes fall 3.37% over the corpus:

| family | loop and epilogue bytes | all trimmed | the segments alone | the reloads alone |
|---|---:|---:|---:|---:|
| fuzz, int | 4,418,073 | -1.43% | -1.26% | -0.11% |
| fuzz, long | 1,933,777 | -2.10% | -2.04% | 0 |
| wide, int | 29,490,987 | -5.01% | -1.41% | -3.55% |
| wide, long | 16,872,067 | -1.33% | -1.33% | 0 |
| size ladder | 2,602,528 | -2.10% | -1.47% | -0.64% |
| `make_date` ladder | 171,992 | -2.18% | -1.13% | -1.03% |
| cheap tails | 26,092 | -17.91% | -15.64% | -2.27% |

Every trimmed class of the corpus verifies. Trimmed, the size ladder at 100 and 400 entries and the
mixed family at 100 and 200 answer as the reference evaluator does on both bodies, at lengths 1,
17, 129 and 4096.

### 2.2 What the JIT makes of them

Allocated bytes per 4096-row batch, read from the JVM's per-thread counter over 2000 batches after
20,000 to warm up:

| kernel | body | as emitted | trimmed |
|---|---|---:|---:|
| size ladder, 400 entries | null-free | 173.5 | 83.1 |
| | every seventh row null | 32,152 | 16,088 |
| mixed family, 200 entries | null-free | 72.0 | 72.0 |
| | every seventh row null | 10,192 | 10,072 |

The row expected a dead segment's allocation to be dead to C2 as well. In the masked body it is not:
the masked ladder allocates half its bytes a batch for segments nothing reads.

### 2.3 Run time, on the laptop

The size ladder and the mixed family as `VarkaWideKernelBenchmark` runs them, a million rows in
4096-row batches, from a scratch copy of its setup. Each kernel was run as emitted, trimmed, and as
emitted again last as a control for the order of the cases. The figures are the best of at least
five iterations, in nanoseconds a row, on the AMD Ryzen AI 9 HX PRO 370 with JDK 25:

| shape | body | as emitted | trimmed | as emitted, again | segments trimmed | reloads trimmed |
|---|---|---:|---:|---:|---:|---:|
| ladder, 100 entries | null-free | 72.1 | 72.3 | 72.1 | | |
| ladder, 400 entries | null-free | 294.8 | 291.2 | 286.0 | 286.3 | 284.5 |
| ladder, 100 entries | every seventh row null | 87.8 | 76.9 | 85.1 | | |
| ladder, 400 entries | every seventh row null | 347.0 | 304.5 | 346.7 | 312.5 | 346.3 |
| mixed, 100 entries | null-free | 40.4 | 40.2 | 38.2 | | |
| mixed, 200 entries | null-free | 81.4 | 106.3, then 80.2 | 79.8 | 79.9 | 80.7 |
| mixed, 100 entries | every seventh row null | 46.3 | 42.4 | 41.6 | | |
| mixed, 200 entries | every seventh row null | 95.1 | 95.8 | 95.4 | 94.5 | 94.6 |

The masked ladder runs 12% faster trimmed at 400 entries, against a control 0.1% from the first
run. At 100 entries it runs 10 to 12% faster. Nearly all of it is the segments: with only the
segments trimmed it is 10% faster, with only the reloads trimmed not at all. Every other case is
within its control's spread.

One case misread once. The trimmed mixed family at 200 entries, null-free, read 106.3 in the first
run. A rerun gave 80.2 and 79.6 for the two trimmed arms, and 79.9 to 81.5 with each kind trimmed
alone, while its own as-emitted control read 93.0. A single case jumping by 15 to 30% is this
machine's noise, not the trim. These are laptop numbers, from a 256-bit datapath, for this plan's
record; the runner measures the change (section 6).

### 2.4 What the check admits

The row, with its premise corrected. The segments are not free in the masked body: each is an object
a batch, and trimming them is the one change with a measured run-time effect. The reloads are more
than half of the bytes trimmed over the corpus, most of them in the wide int family, and each is a
Vector API call site against the budget of task 209; on the laptop they cost no time. The input
segments and the masks are small and come out with the same reasoning. The shared slots are 5,722
`dup`s and stores, about 0.03% of the bytes, and task 223 named their cause.

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
`Slots` decides it from the walk it already makes to find `skippedColumns`, the columns only a
loaded prefix's date reads.

### 3.4 The epilogue's mask, at its first reader

The mask is read by four things: a column's masked load, a value root's masked store, a guard's
condemnation and a prefix transfer, which live in three classes. Rather than restate all four as
one predicate, the epilogue builds its mask at the first of them and stores it for the rest; an
epilogue none of them reaches builds none. The epilogue is one lane group with no back edge, so
the first reader comes before the others on every path that reaches them, and the verifier rejects
the class if that ever stops being true. The slot also stands in today for "this body is an
epilogue" in four places, which read a flag of their own instead.

### 3.5 The shared slots, left and named

The 5,722 are dates two calendar nodes reach through one shared prefix, which the slot count takes
for two visits (`PLAN_TASK_223.md` 9.3). Counting them right means the count must know the
lowering's fragment sharing. A `dup` and a store nothing reads costs C2 nothing, and on the laptop
trimming them moved nothing either. The check of section 5 names them as the one kind left, with
this reason, as the row's done-when allows.

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
| `VarkaBodyEmitter.java` | steps (3) and (4) build the segments 3.1 and 3.3 keep; the epilogue's mask at its first reader |
| `Slots.java` | no local for an unread segment; the columns whose values a body reads; the prefix vectors a fragment's consumers read |
| `VarkaChronoLowering.java`, `VarkaVectorWalk.java` | the reload of 3.2; the mask's readers |
| `VarkaEmitOptions.java` | `elideUnreadLocals` |
| `VarkaUnreadLocalsSuite.scala` (new), with the census in Java beside the cost corpus | the check of section 5 |
| `emitted_bytes.json`, `VarkaEmitCostTable.java`, `VarkaEmitCostRegister.java`, `emit_cost_audit.json` | regenerated, the diff reviewed |
| `VarkaWideKernelBenchmark.scala` and its results file | a section for the two forms |
| `PLAN_MILESTONE_6.md` | row 239 |

## 5. Tests, and what each is for

* **The check**: over the cost corpus at the shipped options, no reference local of a loop or
  epilogue method is stored and never read under the switch, except the shared slots of 3.5, which
  it counts by name. With the switch off it pins today's census, so a leftover added to either form
  is seen. This is the row's first done-when.
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

1. **The check** finds no unread reference local under the switch except the shared slots, about
   5,722 of them.
2. **The bytes** of the corpus's loop and epilogue methods fall about 3.4%, each family within a
   tenth of a point of the transformer's figure in 2.1.
3. **Builds and loop methods**: the reloads were call sites, so a group can drop below the call-site
   budget it was split for, but none rises above one. No shape of the audit gains a build or a
   loop method, and some lose a call-site split.
4. **Run time**: on the runner the masked ladder runs at least 5% faster at 400 entries, where the
   laptop gave 12%. Every other case stays within its control's spread.
5. **Allocation**: the masked ladder at 400 entries allocates half what it does now a batch, as on
   the laptop.

## 7. Risks

1. **The tails' stated reads go wrong when a tail changes.** A tail that reads a vector it did not
   state fails verification at its first emission. One that states a vector it never reads leaves
   a dead load the check finds.
2. **The mask's first reader does not dominate a later one**, if a lane group ever branches around
   a reader. The verifier says so at the first emission. The fallback is the predicate 3.4 avoids.
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
