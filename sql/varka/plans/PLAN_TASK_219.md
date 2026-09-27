# Task 219: A method past 64 KB escapes the emitter instead of declining

*Scoped 27 September 2026 (milestone 6 row 219, from the night fuzz run of
26-27 September); opened 27 September 2026.*

## 1. Where this came from

The night fuzz run of 26-27 September 2026 (twenty-two JVMs, fresh seeds, the
IR fuzzer at 300000 trees per lane per run) found ten IR trees whose emitted
class holds a method the Class-File API refuses:

    java.lang.IllegalArgumentException: Code length 67426 is outside the
    allowed range in loopMasked0(long[],long[],int[],long[],long[],int[],int)int

Eight are loop methods (`loopMasked0` to `loopMasked2`, 65598 to 77885 bytes)
and two are the legacy form's `epilogueMasked`; every one is a deeply nested
`make_date` tree. Row 219 lists the seeds and iterations. The exception leaves
`VarkaLoopEmitter.emit` as an `IllegalArgumentException` rather than a
`VarkaEmitDeclined`, which breaks task 169's contract that the only exception
the emitter throws for a shape the compiler may ask for is a decline, and it
has a second consequence: `VarkaExpressionCompiler.admitBySize` reads any
failure other than a decline as a shape that fits, so the compiler admits at
plan time a kernel that cannot be built.

The shape family is old. A 35-million-iteration run on 8 September 2026 built
a 67244-byte `epilogueMasked` for a nested `make_date` tree
(`PLAN_TASK_87.md` 1); task 87 split the epilogue per group and task 168
bounded every method by bytes, and the two fuzz campaigns of 22 and 23
September found the family again, in the legacy epilogue at `groupBudget` 400
(`sql/varka/skills/testing-and-debugging.md`, "Six arms, one finding"). What
is new in the night's ten is that eight are *loop* methods under the default
budget - the form every query runs - and that the reading of task 168's
class-file caps as "out of reach of any admitted shape"
(`sql/varka/skills/emitter-and-ir.md`) is refuted below with a production
options tree.

## 2. The admission check, done

Four things had to be true for the design in section 3 to be the right one,
each checked on master `9ed9979dc5e`, 27 September 2026, on the laptop.

**2.1 Where the JVM refuses.** Task 168 measures a class after it is built
and declines on what it reads; the check that failed is the Class-File API's
own, and the question is when it runs. The reproducer replayed with full
stack traces (`-Dvarka.fuzz.seed=27090110001 -Dvarka.fuzz.only=41527`):

    at jdk.internal.classfile.impl.DirectCodeBuilder$4.writeBody(DirectCodeBuilder.java:369)
    at jdk.internal.classfile.impl.UnboundAttribute$AdHocAttribute.writeTo(UnboundAttribute.java:1099)
    at jdk.internal.classfile.impl.Util.writeAttribute(Util.java:230)
    at jdk.internal.classfile.impl.AttributeHolder.writeTo(AttributeHolder.java:71)
    at jdk.internal.classfile.impl.DirectMethodBuilder.writeTo(DirectMethodBuilder.java:146)
    at jdk.internal.classfile.impl.DirectClassBuilder.build(DirectClassBuilder.java:198)
    at java.lang.classfile.ClassFile.build(ClassFile.java:558)
    at ...VarkaLoopEmitter.build(VarkaLoopEmitter.java:329)

The refusal is thrown while the class is *assembled* - when the method's
`Code` attribute is written into the class buffer, after every method's
body has been built - not while the method's body is built. Two things
follow. There is no class to measure, so task 168's measurement never sees
the method, and no budget measured after the build can. And a catch around
each `withMethodBody` call, which would know the method by construction,
catches nothing: the only place the refusal can be caught is around
`ClassFile.build`, where the method is known only from the message.

**2.2 What the message carries.** The text is `DirectCodeBuilder`'s,
`"Code length %d is outside the allowed range in %s%s"` with the code length,
the method's name and its descriptor in display form; the JDK throws it as a
plain `IllegalArgumentException`. Both numbers the decline needs are in it,
and nothing else the emitter builds can produce that prefix. The text is not
an API. What the design does about that is in 3.1.

**2.3 The family, and why it was not shared away.** The compiler shares
structurally equal subtrees and the emitter shares whole nodes and
civil-from-days prefixes, so a nested `make_date` tree whose siblings are
copies costs one copy each in a query. Measured with `VarkaEmitDump` over
`make_date(year(X), month(X), day(X))` nested to depth `n` over one date
column, `loopMasked0` in bytes:

| depth | make_date copies | defaults, budget 0 | `cse` off | `shareChronoPrefix` off | both off |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 1072 | 1069 | | |
| 2 | 4 | 1899 | 1893 | | 5769 |
| 3 | 13 | 2726 | 2717 | 4337 | 18198 |
| 4 | 40 | not run | | | 55530 |

With either option on, each level adds one copy; with both off, each level
triples the copies and the bytes (about 830 bytes a copy after the first).
`shareWholeNodes` changes nothing here, being a grouping clause. The fuzzer
draws every option, and all twelve failing IR runs of the night (the ten of
row 219 and two more before the run was stopped) drew `cse` and
`shareChronoPrefix` off - the third and fourth fields of every
`options=opts(...)` in their logs read `false`. The failing trees' other
drawn options make them heavier - 67426 bytes at depth four against 55532
for the same tree under the defaults with the sharing off - which is why depth
four crosses 65535 there and depth five is the first to cross here. So the family is one
the fuzzer reaches by turning the sharing off, and it says nothing about a
query on its own - 2.4 is what does.

**2.4 A query reaches it under production options.** Under the byte budget
the compiler no longer caps distinct ops: `fitsBudgets` applies
`MAX_FUSED_NODES` only when `methodByteBudget` is 0 (task 190), so a single
output is bounded by `MAX_CHAIN_DEPTH` and by the JVM alone. A tree of
*distinct* nested `make_date`s - `E(k, i) = make_date(year(E(k-1, 3i)),
month(E(k-1, 3i+1)), day(E(k-1, 3i+2)))` over `E(0, i) = add_months(d, i)`,
forty `make_date`s over eighty-one distinct `add_months` at depth four, ten
nodes deep - through `VarkaEmitDump`, which compiles it as the planner does
and then emits what the compiler admitted:

| tree | budget 0 | budget 8000 (production) |
| :-- | :-- | :-- |
| `E(3, 0)`, 13 `make_date`s | declined: over the op cap | declined: over the method budget |
| `E(4, 0)`, 40 `make_date`s | declined: over the op cap | **admitted, then refused: `loopDense0` is 132452 bytes** |

That is the bug in one line: under the production budget the op cap is off,
the byte budget is the only bound on one output, and the byte budget cannot
measure a class the JVM refuses to build. The compiler admits `E(4, 0)`,
EXPLAIN claims fusion, and on the executor every task builds a 132 KB method,
meets the refusal, logs it with a stack trace and falls back per batch behind
the ghost fallback. The results are right; the plan, the log and the
per-task cost are wrong.

**2.5 The other class-file caps, at the API.** Whether the same escape has
siblings, asked of the Class-File API itself with four classes built for the
purpose (`CapProbe.java`, run on JDK 25.0.4.1):

| class | outcome |
| :-- | :-- |
| one method of 65536 bytes | refused at assembly: `Code length 65537 is outside the allowed range in m()void`, thrown at `DirectCodeBuilder$4.writeBody` |
| one method of 65535 bytes | built; the cap is inclusive |
| 70000 fields with distinct names | refused at assembly: `65536 is not a valid index. Entry: f65532`, thrown at `BufWriterImpl.invalidIndex` |
| a 40000-byte body with a backward branch across it | built, 40010 bytes of code: the API widens short jumps itself |

So the constant-pool cap has the same escape - enforced while the class is
assembled, never measured, a different message - and the branch range has
none. No shape the IR builds comes near the pool cap (1384 entries at 400
four-op outputs, `emitter-and-ir.md`), so the pool refusal is closed for the
mechanism rather than for a case, and class-wide, since no regroup shrinks a
pool.

What the check would have rejected: a catch per `withMethodBody`, which 2.1
shows cannot see the refusal; and a fix confined to the fuzzer's option draws,
which 2.4 shows would leave a query's escape in place.

## 3. The design

### 3.1 The refusal is the measurement

`VarkaLoopEmitter.emit`'s loop today builds the class, measures it, splits a
group whose method is over the budget and builds again, and declines when no
split is left. The change is one more way into that loop: when
`ClassFile.build` throws the refusal, the emitter reads the method and its
length from the message and treats them as the measurement of a class of one
method - `VarkaEmittedClass.refused(method, bytes)` - so that the same
`groupsOver` decides whether the method's group can be split and the same
`overLimits` writes the finding, which is already worded for this case
("`loopMasked0` is 67426 bytes, over the class-file cap of 65535: the class
cannot be built") and reaches the compiler as the same `VarkaEmitDeclined`
naming the same outputs.

Which method the refusal names is the first over the cap in class order.
Under a budget that is the loop method, whose group the regroup can act on;
in the legacy form (`methodByteBudget` 0) the single epilogue - one method
over every output - is assembled before the loops, so a refusal there names
it, no regroup applies, and the decline is class-wide and names no output,
as a driver's is.

Two details differ from a budget overrun. The limit the refusal is judged
against is `METHOD_CODE_CAP` whatever the budget: with `methodByteBudget` 0
the legacy form is built once and never measured, but the JVM's cap is not
the budget's and applies to that form too, so a refusal under budget 0
regroups and declines like one under a budget, and a legacy form that builds
is returned unmeasured as before. And a regroup after a refusal is a real
rescue where a budget overrun's is not: a group of several outputs whose loop
method the JVM refuses can build once split, and does (test 2 in section 5).

The constant pool's refusal (2.5) is read beside it,
`VarkaEmittedClass.refusedConstantPool`, and declines at once, class-wide and
naming no output, the way a driver over the budget does: the compiler then
demotes outputs from the end until the class fits (`PLAN_TASK_169.md` 3.1,
rule 2), which is what shrinks a pool.

Reading the messages is the one part of this that is not the emitter's own.
The design accepts it with its eyes open, and pins it: each pattern is
anchored on the JDK's exact words and reads its number, a message that does
not match is not a refusal and propagates as it does today, and a test builds
both refusals through the Class-File API itself and reads them back (test 6),
so a JDK that changes the words fails a test in minutes rather than a fuzz
run in weeks. The alternative, predicting a method's bytes before the class
is built, is row 199's and is not taken here.

`admitBySize`'s reading of a non-decline failure as a fit stays as task 169
designed it: with the refusals declines, the failures left are emitter bugs,
and a bug that reaches the executor is visible there - a warning with a stack
trace and the ghost-fallback metric - where one demoted at plan time with a
made-up reason would not be. What changes is that the admission says so: a
failure it swallows is logged once per JVM on the driver, so the next escape
of this kind is in the planning log the first time it happens and not only in
a task's.

### 3.2 What is deliberately unchanged

* The byte budget, its default and its regroup rule (tasks 87, 168, 190).
* The compiler's weight caps and the lifting of the op cap under the budget
  (task 190); a byte prediction before emission is row 199.
* The fuzzer's option draws: the trees that found this are worth keeping.
* `VarkaEvaluatorBase`'s handling of a decline on the executor (task 169).
* The measurement of a class that builds, the constant-pool count included:
  `overLimits` reads it as before, and the refusal path only adds what the
  measurement cannot reach.

### 3.3 Registered op counts

None move: the task emits no new operation and changes no method that
builds. `coverage.json` and `emitted_bytes.json` are unchanged (test 5).

## 4. Files

| file | what |
|---|---|
| `sql/varka/plans/PLAN_TASK_219.md` | this plan |
| `.../codegen/varka/VarkaLoopEmitter.java` | the refusal caught around the build, read, and fed to the regroup loop |
| `.../codegen/varka/VarkaEmittedClass.java` | `refused`, the measurement of a class the JVM would not build, and `refusedConstantPool` |
| `.../codegen/VarkaExpressionCompiler.scala` | a failure the admission swallows is logged once per JVM |
| `.../codegen/varka/VarkaEmitterTestSupport.java` | the two refusals, produced by the Class-File API for test 6 |
| `.../codegen/varka/VarkaEmitBudget.java` | the cap's doc: enforced at assembly, read from the refusal |
| `.../codegen/varka/VarkaEmitDeclined.java` | the class doc's list of declines gains the refusal |
| `.../codegen/varka/VarkaEmitterBudgetSuite.scala` | tests 1, 2 and 6 |
| `.../codegen/VarkaExpressionCompilerSuite.scala` | test 3 |
| `.../codegen/varka/VarkaIrFuzzSuite.scala` | a shape the JVM refuses in both forms is counted and skipped, not a bare failure (test 4) |
| `sql/varka/skills/emitter-and-ir.md` | the class-file caps lesson, corrected |
| `sql/varka/plans/PLAN_MILESTONE_6.md` | row 219 |

## 5. Tests, and what each is for

1. **A refused method declines like any method over a limit, budget or not**
   (`VarkaEmitterBudgetSuite`). The night's family: `make_date` nested to
   depth four with every sharing option off, forty copies, ANSI at the inner
   levels as the fuzz trees had it, at depth five. Under the production
   budget it is a `VarkaEmitDeclined` naming `loopMasked0`, a length over
   65535, the class-file cap and output 0; under budget 0 one naming the
   legacy `epilogueMasked` and no output; today both are the JDK's
   `IllegalArgumentException`. It also pins that depth four builds, so the
   test is at the smallest count that crosses
   (`testing-and-debugging.md`, "A reproducer past 64KB is sized near the
   smallest count that crosses it").
2. **A refusal regroups before it declines** (`VarkaEmitterBudgetSuite`).
   Four depth-three outputs of that family, some 18 KB each, in one group
   (`groupBudget` past their weight, the byte budget set to the cap itself so
   that the form is the per-group one): one loop method of some 72 KB is
   refused, the group is split, and the class builds with two loop methods
   each under the cap. Today it is the JDK's exception; a fix that declined on the first
   refusal without regrouping would fail it.
3. **The compiler demotes a refused output at plan time, with the reason,
   and fuses the rest** (`VarkaExpressionCompilerSuite`). `E(4, 0)` of 2.4
   beside `year(d)` under the defaults: the tree is residual with a reason
   naming `loopDense0`, its bytes and the class-file cap; `year(d)` fuses.
   Today the tree is admitted and the projection claims fusion.
4. **The fuzzer accepts the emitter's refusal in both forms**
   (`VarkaIrFuzzSuite`). A shape that declines under the budget is retried
   without it, and one that declines there too is past the JVM's cap in any
   form the emitter has: it is counted and skipped, with the count in the
   test's `info`. The ten reproducers of row 219 pass under this rule; today
   the retry's exception is a bare failure with no seed or iteration.
5. **Nothing that builds changes** - `VarkaEmittedBytesSuite` and
   `VarkaCoverageSuite` against their committed files, and every Varka suite
   in `sql/catalyst`.
6. **The refusals the emitter reads are the JDK's own, in the JDK's words**
   (`VarkaEmitterBudgetSuite`). Both refusals are produced by the Class-File
   API on classes built for the purpose - a method of 65536 bytes, a pool of
   70000 names - and read back; a method of 65535 bytes builds, and a length
   within the cap or an unrelated message is not read as a refusal. This is
   the test that catches a JDK changing the text, which nothing else would
   before the fuzzer.

Both widths: the bytes of a method do not depend on the species
(`PLAN_TASK_169.md` 2.2, one byte across three widths), so a refusal read on
one width is read on all; tests 1 to 3 run at the host's width.

## 6. The measurement

None. The task changes what happens to a shape the JVM refuses and nothing
about a shape it builds, which test 5 holds byte for byte; there is no rate
to move. The numbers of section 2 are the measurement, and the reproducers
of row 219 are rerun in section 9.

### 6.1 Predictions, registered before the run

1. All ten reproducers of row 219 decline with a message reading "over the
   class-file cap of 65535" and naming the method the night's log named, and
   under test 4's rule every one of the ten runs passes.
2. `emitted_bytes.json` and `coverage.json` are unchanged, and the Varka
   suites of `sql/catalyst` pass with the new tests added and nothing else
   moved.
3. `E(4, 0)` through the compiler is residual with the reason
   "over the emitter's method budget (loopDense0 is 132452 bytes, over the
   class-file cap of 65535: the class cannot be built)", the same length 2.4
   read.
4. Test 2's four outputs are refused once, split once, and build with two
   loop methods of about 36 KB each.

## 7. Risks

1. **The JDK's text changes.** Then the refusal is an `IllegalArgumentException`
   again, as today; test 1 fails on the next JDK, and the fuzzer's report
   names it. The pattern is in one place with the JDK class it quotes.
2. **A second method is over the cap behind the first.** The JDK reports one
   refusal per build; a stuck group declines on the first, and a split
   rebuilds and meets the next. The loop terminates as before: every pass
   either adds a forced start or throws.
3. **The regroup after a refusal changes a class that builds.** It cannot: a
   refusal means no class was built, and a class that builds takes the path
   it took before. Test 5 holds it.

## 8. Sequencing

1. This plan with the admission check done, committed first.
2. The emitter: the catch, the read, `VarkaEmittedClass.refused`, the docs;
   tests 1 and 2.
3. The compiler test (3), the fuzzer's rule (4), the reproducers rerun.
4. The lesson corrected, row 219, section 9.

## 9. Outcome

*Written 27 September 2026, when the reproducers had been rerun and every
Varka suite had passed.*

The emitter no longer lets a class-file refusal escape. A method past the
65535-byte cap, or a constant pool past its cap, is read from the Class-File
API's refusal at assembly and declined as a size - regrouped first where a
group can be split - and the compiler demotes the shape at plan time with the
cap as its reason. The reading rests on two of the JDK's messages, and a test
now produces both through the API itself. The contract of task 169 holds
again for every size the JVM enforces.

Prediction by prediction (6.1):

1. **Held.** All ten reproducers of row 219 pass: the nine with an iteration
   replayed one by one, and the tenth, seed 27090010002, at its full 300000
   trees per lane; each run reports one shape "past the class-file cap on a
   method in every form the emitter has: declined, and skipped", and none
   fails. The two epilogue cases were the fuzzer's own retry without the
   budget, as section 1 read them.
2. **Held.** Every Varka suite in `sql/catalyst` passes - 433 tests with the
   three tests of section 5 added, 434 once test 6 joined them - and
   `coverage.json` and `emitted_bytes.json` are unchanged, as their suites
   would have said otherwise.
3. **Held on the length, misnamed on the method.** `E(4, 0)` beside `year(d)`
   is residual with the reason "over the emitter's method budget (loopDense1
   is 132452 bytes, over the class-file cap of 65535: the class cannot be
   built)", the length 2.4 read to the byte; the method is `loopDense1`, not
   `loopDense0`, because the test puts the tree second and its group is the
   second, which the prediction did not think through. `year(d)` fuses.
4. **Held.** Four depth-three outputs of the family, refused once as one loop
   method, are split once and build as `loopMasked0` and `loopMasked1` of
   36254 bytes each.

What moved that the plan did not list:

* **The constant pool** (2.5). Asked of the API, its cap is refused at
  assembly too, with "65536 is not a valid index. Entry: ..."; it is read
  beside the method cap and declines class-wide. The branch range, also
  asked, is not a cap: the API widens short jumps itself.
* **The legacy form's refusal names the epilogue.** With `methodByteBudget` 0
  the single epilogue is assembled before the loops and is one method over
  every output, so a refusal there names it and no output, and no regroup
  applies (3.1, test 1's budget-0 arm).
* **The admission says what it swallows.** `admitBySize` logs, once per JVM,
  a failure it admits as a fit, so the next escape of this kind reaches the
  planning log the first time it happens.
* **The sentinel** (test 6). The JDK's two messages are pinned by producing
  them, not by quoting them.

The family in numbers, for the next reader: the fuzz trees are forty copies
of a `make_date` with the sharing drawn off, 55532 bytes at depth four under
the defaults with the sharing off and past the cap at depth five (the tool
reads 165938 for the plain tree's legacy epilogue there); the query of 2.4
is forty *distinct* `make_date`s, 132452 bytes in one loop method under
production options. Neither is a projection anyone writes on purpose; the
value of the change is the contract, not the case.

What the task leaves for later:

* **A refused shape still costs one full build at plan time** - the class
  is assembled up to the method the JVM refuses - before it declines, once
  per shape per JVM (the shape cache remembers declines). Declining before
  the build needs a byte prediction, which is row 199's.
* **Where the pool cap is reachable** is not known; no shape the IR builds
  has come within a fortieth of it (`emitter-and-ir.md`). The refusal is
  closed for the mechanism, and a shape that reaches it would show as a
  class-wide decline naming the pool.
* **The fuzzer's skipped count** is a signal: a night whose `info` lines count
  many skipped shapes has found either a heavier family or a new way to the
  cap, and is worth reading.

## 10. After review, 27 September 2026

*Written after a code review of the pull request and the fork CI's first run.*

**The CI run found a regression the fix uncovered.** `VarkaCodegenGiveUpSuite`'s
G14 - Spark's 3000-field projection, with a Varka arm of 3000 `date_add`
outputs - ran for over twenty-five minutes where the job takes eighteen. The
arm had passed before this task only because the refusal escaped: a driver of
3000 outputs is past the class-file cap, the JDK's exception was read as a fit,
and the projection was "admitted" in one build with nothing checked - the
test's own comment, that every method stays under the budget by construction,
had never been true. With the refusal a class-wide decline, task 169's rule for
class-wide declines applied: demote the last-admitted output and ask again,
once per output, each ask a build of a class of thousands of methods.

`classify` now bisects on a class-wide decline. The fused entries are a
prefix in projection order, so the largest prefix the emitter admits is found
by halving - about twelve asks for 3000 outputs, each one emission through the
shape cache - and the rest are demoted with the class-wide reason. Named
declines are handled as before. G14 runs in nine seconds on the laptop; the
sixty-`make_date` test of task 169 still fuses a prefix and demotes a suffix.
This was row 220's mechanism. Three of the night's composition runs that had
hit their caps, replayed on this branch at their 4000 compositions: seed
27090210023, timed out at thirty minutes overnight, 67 seconds; 27090200001,
timed out at thirty minutes, 167 seconds; 27090190001, failed the suite's
twenty-minute cap after 1337 seconds, 62 seconds. The fuzzer draws
projections of up to three hundred entries, exactly where a driver crosses
the budget and a class-wide decline was paid once per entry. Row 220 closes
on this task's fix, with what is left of its question - emission time itself
superlinear in width - being row 191's.

**The review's findings, and what was done with each.**

1. The fuzzer's retry without the budget caught only declines, so any other
   exception there escaped with no seed or iteration - the tenth reproducer's
   shape of failure. It now fails with the context, like the first attempt.
2. The constant pool has a second refusal the probe of 2.5 did not reach:
   `SplitConstantPool` writes "Constant pool is too large N" when the pool
   itself is written with entries nothing referenced, where `BufWriterImpl`'s
   wording fires when a method writes an index past the cap. Read from the
   installed JDK's bytecode; both are matched, and the sentinel pins the
   second on its words.
3. On the refusal path each build reveals one method over the cap, so k
   over-cap groups cost k rebuilds before the measured path acts on the rest.
   Recorded, not changed: the proposed pre-split by weight is not sound,
   because weight is not bytes - this family is exactly the case where copies
   inflate bytes and not weight - and the shape is pathological and paid once
   per shape per JVM. Row 199 is the fix that would remove the builds.
4. The admission's once-per-JVM log keyed on the message, unbounded on a
   long-lived driver, and dropped the throwable. It keys on the exception's
   class and the frame that threw, and logs with the stack.
5. The admission returned early with the budget off, so under the legacy form
   a cap decline was never seen at plan time. It asks with the budget off too:
   the legacy form is built once and never measured, so the only decline it
   can give is the cap's.
6. Skipped shapes were counted into `info` with no ceiling. The fuzzer now
   fails past two plus one per thousand iterations, against the night's rate of
   one in three hundred thousand.
7. The javadoc of `refused` sent a reader to the fuzz suite for a JDK that
   changed its words; the sentinel test is the pin, and the doc names it.
8. `java.util.Optional` was written fully qualified in a file whose
   fully-qualified regime is for Class-File API types only. Imported.

