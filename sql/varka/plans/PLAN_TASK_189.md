# Task 189: A dense loop enters a C2 deoptimization cycle at twelve outputs

*Scoped 26 September 2026 (milestone 6 section 2.12, row 189); opened 26
September 2026.*

## 1. Where this came from

Task 87's benchmark met it while measuring something else. On the `make_date`
ladder's null-free arm, from twelve outputs on, the second output group's dense
loop method sometimes runs a hundred times slower than the arm with nulls over
the same kernel, and the JVM's own output says why: the method is compiled at
tier 4, made not entrant on its first execution at a `profile_predicate` trap
on the loop's back-edge, compiled again, and so on every 250 to 300
milliseconds - the method's own C2 compile time - for as long as the case runs
(`PLAN_MILESTONE_6.md` 2.12, opened 23 September 2026). In other forks of the
same JVM the same method compiles once. The band files carry it as tier 3,
unreadable from a diff: in `VarkaMethodSizeBenchmark-jdk25-128bit-band.txt`
every null-free row of the single-epilogue form from twelve outputs up spreads
6200% to 21400% over ten runs, and no row of the epilogue-per-group form does.

Why it is a task and not a note: a projection of a dozen calendar outputs is an
ordinary reporting shape, the loop method is about 3 KB, nowhere near any size
limit, and nothing reports the cycle - the query is a hundred times slower on
null-free data than on data with nulls, and only in some JVMs. It is the third
JIT cliff this milestone found that the emitter cannot see, after the
epilogue's size (task 87) and C1's register limit, and the only one that is
not about size. The lead the row carries from `PLAN_TASK_87.md` 9.5: in the
fork where the single-epilogue form sat in the cycle at 128 bits, the
per-group form - the default since task 87 - did not.

## 2. The admission check, done

**The question the check asks.** Whether the cycle still reaches a kernel the
emitter produces today. Task 87 changed every kernel's shape - each group's
methods set up only their own group, and the epilogue is one method per group
- and its band says the per-group form did not cycle where the single form
did. If no shape the default emits reaches the cycle, the fix the row plans
has nothing left to fix: the task then closes with the probe as the guard
against the cycle's return, the fork lifetime of the legacy form measured, and
the cause as a research row, since the form that cycles is a reference variant
no query runs.

**The instrument.** `dev/varka_deopt_cycle.sh` forks fresh JVMs of
`VarkaDeoptCycleProbe`: the ladder's `make_date` kernel at a given output
count, emitted in one form - `single`, the emission before task 87, or `group`,
the default - and driven over null-free 1024-row batches for eight seconds,
under `-XX:+PrintCompilation` and `-Xlog:deoptimization=debug` at a given
`MaxVectorSize`. `dev/varka_deopt_cycle.py` reads the verdict per fork from
those logs, never from a rate: a fork is in the cycle when a `loopDense`
method was compiled at tier 4 as a standard compilation three or more times and
trapped at `profile_predicate`. The rule is calibrated on the first fork
(below): a method that compiles once sees one standard compile and perhaps one
OSR; a method that traps four times at the loop head and stops sees two; only
the cycle sees a third and then one per compile time. Fresh JVMs because 2.12
found the mode decided at or near the first C2 compile and then stable for the
fork's life, so forks are the sample and iterations are not.

**Calibration, the first fork, 26 September 2026** (twelve outputs, single
form, 128 bits, eight seconds; the class the probe emits has, in that form,
`loopDense0` at 3169 bytes, `loopDense1` at 3181, `loopDense2` at 2018 and
`epilogueDense` at 6865). The fork was in the cycle: `loopDense1` compiled at
tier 4 fourteen times, thirteen of them made not entrant, seventeen
`profile_predicate maybe_recompile` traps, and a rate of 0.2 to 0.7 M rows/s
every second of the eight. The timeline is 2.12's exactly: four traps at bci
426, the loop head, within eleven milliseconds of the first standard compile,
then that version made not entrant and the next one trapping at bci 3166, the
`goto` back to the loop head, once or twice per version, a new version every
265 milliseconds. `loopDense0` and `loopDense2` each trapped four times at
their loop head, were compiled once more and then ran; only the second group's
method went on. The log formats the parser reads are this fork's.

**Predictions, registered before the twenty-fork census** (twelve outputs,
both forms, 512 and 128 bits, twenty forks each, the same evening):

1. **The single form at 128 bits cycles in at least ten of twenty forks.** The
   128-bit band's single-form rows are tier 3 at every rung from twelve, which
   needs both modes in ten runs, and today's first fork cycled.
2. **The group form at 128 bits cycles in none of twenty.** The same band has no
   group-form row past 26.42%, and task 87's one fork put the two forms in
   different modes in one JVM.
3. **Neither form cycles at 512 bits.** The wide band of 24 September has no
   null-free row above tier 2 from twelve outputs in either form; the
   twelve-and-up tier-3 rows of 2.12 were the evening before, in one band of
   the single form.
4. **In every cycling fork the repeating trap is at the back-edge bci, not the
   head's,** which traps at most four times - `PerBytecodeTrapLimit` is 4 on
   this JDK - and the period is the method's C2 compile time, 200 to 350 ms.

Then the sweep the row's last paragraph asks for: the default form at 13, 14,
16, 32 and 60 outputs, ten forks each at both widths.

5. **The default form cycles in no fork at any rung or width.**

*Added the same afternoon, before that census ran, at the owner's question
whether an open pull request already removes the cycle.* Of the four open,
only #433 (task 212) touches this path, in two ways: it keeps C1 off the
kernel classes, and it has C2 compile a new kernel from the warm-up's calls of
32 rows rather than from 1024-row batches - a different branch profile for a
profiled loop predicate to read. The probe therefore takes a `path` argument:
`batches`, the census above, or `warmup`, which installs the same directive
for the probe's classes and makes twelve thousand 32-row calls before the
first batch. Run at twelve outputs, both forms, both widths, twenty forks:

6. **The warm-up path changes no verdict**: the single form at 128 bits still
   cycles in at least ten of twenty forks and the group form in none. The
   trap fires on the first execution of every version C2 compiles, whatever
   profile it compiled from, so the profile is not what decides the mode.
   Failing on the single side means #433 removes the cycle by accident and
   this task narrows to saying why; failing on the group side means #433
   brings it back, which is a finding for #433 before it merges.

**What the check would reject.** Prediction 2, 5 or the group half of 6
failing - a default-form kernel in the cycle - keeps the row as written: the
mechanism hunt of section 3 and a fix in the emitter. All of them holding
rejects the fix and keeps the guard and the lifetime measurement (3.3, 3.4).

### 2.1 The census, 26 September 2026

Twenty forks per case, twelve outputs, eight seconds each, on the laptop at a
load of about 1.2 (`target/varka-deopt-cycle/20260926-120413`, kept as
`VarkaDeoptCycleProbe-jdk25-census.txt` beside the catalyst benchmarks). Forks
in the cycle, of twenty, and the probe's rate in the last second, M rows/s:

| form | 512 bits | rate, cycling / once | 128 bits | rate, cycling / once |
| :-- | --: | :-- | --: | :-- |
| single epilogue | 19 | 2.4 to 2.5 / 163.6 | 20 | 0.7 / - |
| epilogue per group | 0 | - / 172.1 to 185.5 | 0 | - / 55.2 to 55.6 |

Every fork of both forms and both widths shows the same opening: the first two
loop methods each trap four times at `profile_predicate` on their loop's exit
test - bci 426 or 427 in the single form, 212 or 213 and 224 or 225 in the
group form - and are compiled once more, after which they run. In the single
form's cycling forks the second loop method then goes on: fourteen to sixteen
tier-4 compiles in eight seconds, all but the last made not entrant, sixteen to
eighteen traps, every one after the first four at bci 3166 or 3167 of a
3182-byte method, the `goto` back to the loop head. The group form's second
method never traps there, in forty forks. The one clean single-form fork at
512 bits compiled `loopDense1` once with no trap at all, and at 128 bits there
was none.

1. **Held.** Twenty of twenty at 128 bits.
2. **Held.** None of twenty.
3. **Refuted for the single form, held for the group form.** Nineteen of
   twenty single-form forks cycle at 512 bits. The wide band of 24 September
   read those rows as tier 2 or better over ten runs; the probe and the
   benchmark differ in what runs before the loop compiles - the benchmark
   drives every kernel over both batch lengths and both null arms before it
   times anything - which is a lead for 3.1, not an inconsistency to explain
   away here.
4. **Held.** The head trap stops at four in every fork; the repeating trap is
   the back edge's; and a new version of `loopDense1` is installed every 250
   to 300 milliseconds at 512 bits (the first fork's tier-4 compiles of it at
   3625, 3981, 4264, 4537 and 4786 ms) and every 265 at 128 bits (the
   calibration fork), the method's own C2 compile time. One more thing the
   forks say, for 3.1: nineteen of the twenty cycling forks at 512 bits and
   all twenty at 128 saw an OSR compile of `loopDense1` beside the standard
   ones, and the one clean fork saw none - 2.12's lead - but one cycling fork
   (the third at 512 bits) saw none either, so the OSR compile is company,
   not cause.

*The warm-up path of prediction 6 is in 2.2 and the sweep of prediction 5 in
2.3.*

So the admission check admits the task as the row wrote it only in part. The
form the default emits - every kernel a query runs since task 87 - reached
the cycle in none of eighty forks, so there is nothing in production for a
fix to fix, and section 3.2 is not built unless the sweep or the warm-up path
below says otherwise. The single form cycles almost always at both widths, so
the mechanism is reproducible on demand, which the row did not have: the hunt
of 3.1 has a fork to read whenever it wants one, and the lifetime question of
3.4 can be answered on it.

### 2.2 Task 212's path, 26 September 2026

Twenty forks per case again, twelve outputs, both forms, both widths, with
the probe on the `warmup` path (`target/varka-deopt-cycle/20260926-121907`,
appended to the census file). **Not one fork of eighty cycled, and not one of
their two hundred and forty loop methods trapped at all** - not the cycle,
and not the four head traps every fork of the `batches` path shows in both
forms. Each method compiled once, at tier 4, after the twelve thousand short
calls, and the batches then ran at the clean forks' rate: the single form at
150.6 to 167.9 M rows/s at 512 bits and 50.2 to 59.4 at 128, the group form
at 167.3 to 174.7 and 53.7 to 55.5.

6. **Refuted on the single side, held on the group side.** #433's path
   removes the cycle from the old form entirely, and the default form stays
   clean. So #433 does not bring the cycle back; it takes away the one
   reproducible way of reaching it, which for the mechanism hunt is the more
   interesting result.

The path changes two things at once, and the prediction assumed the profile
was not one of them. The next census takes them apart, on the single form at
twelve outputs, both widths, twenty forks: `c1off`, the directive and then
the batches from the first call, and `shortcalls`, the twelve thousand 32-row
calls with C1 left on.

*Registered before that census runs:*

8. **The short calls are the cure, not the directive.** `shortcalls` cycles in
   no fork and shows no `profile_predicate` trap at either width; `c1off`
   cycles as the `batches` path does, in at least ten of twenty at each width,
   with the four head traps in every fork. The reason: the trap is a
   *profiled* loop predicate, built from what the interpreter counted at the
   loop's branches, and the short calls change that count - two iterations
   per call at 512 bits and eight at 128, against 64 and 256 from the batches
   - where excluding C1 changes only when the counting starts, and for the
   two 3 KB loop methods C1 never produced code in the `batches` path either
   (its tier-3 compiles fail on virtual registers, `VarkaDeoptCycleProbe`'s
   logs say so in every fork).

If 8 holds, the loop's profiled trip count is what arms the predicate, and
3.1 gains a ladder that no assembly is needed for: the warm-up rows at 32, 64,
256 and 1024, which is the trip count at which the cycle returns.

### 2.3 The default form's other rungs, 26 September 2026

Ten forks per case of the default form, `batches` path, at 13, 14, 16, 32 and
60 outputs, both widths (`target/varka-deopt-cycle/20260926-123504`, appended
to the census file). **No fork of the hundred cycled.**

That is the tightened reading, and the first one said otherwise, so both are
recorded. As first written the parser counted a fork in the cycle when one
loop method had three standard tier-4 compiles and any `profile_predicate`
trap, and by that rule half the 60-output forks cycled, five of ten at each
width. Every one of those ten was a single method with four traps, all at its
own loop head, made not entrant once and compiled twice more - the head-trap
opening every fork shows, with a third compile because sixty outputs give a
method's callers longer to warm up. None trapped at a back edge, and none was
made not entrant twice. The rule was written against twelve outputs, where
the settled method never saw a third compile, and it confused the two at
sixty. It now asks for what the cycle is and the opening is not - three or
more "made not entrant" and more than four traps - and read over every log of
2.1 to 2.3 it changes no other verdict: 19 and 20 of 20 for the single form,
0 of 20 for the group form, 0 of 80 on the warm-up path, and the one-fork
calibration still in the cycle.

5. **Held**, on the tightened rule; refuted on the first, by a misreading the
   per-fork counts make visible, which is why the parser prints them.

Two further readings, for the record, and neither is task 189's:

* The rate falls with the output count as the work grows - about 170, 165,
  128 and 66 M rows/s at 13, 14, 16 and 32 outputs at 512 bits, 55, 50, 41
  and 21 at 128 - and then drops to 0.2 and below 0.05 at sixty, in every
  fork alike. That is not a mode one fork draws and the next does not. The
  sixty-output kernel's epilogue methods are 3 to 4 KB each, their tier-3
  compiles fail with "out of virtual registers" in every fork, and in eight
  seconds of 1024-row batches the probe never saw them compiled at tier 4:
  the strand task 212 exists for, reached here by the probe's own path.
* The one-fork calibration of section 2 is still in the cycle on the new rule,
  so the instrument's positive control is unchanged.

### 2.4 The lifetime, 26 September 2026

Three forks of the single form at 128 bits, ninety seconds each, `batches`
path (`target/varka-deopt-cycle/20260926-125359`, appended to the census
file). All three entered the cycle and all three left it by themselves:

| fork | tier-4 compiles of `loopDense1` | made not entrant | traps | last compile, ms | rate before / after, M rows/s |
| --: | --: | --: | --: | --: | :-- |
| 1 | 98 (1 OSR) | 96 | 100 | 54296 | 0.6 to 0.7 / 51 to 53 |
| 2 | 98 (1 OSR) | 96 | 100 | 51282 | 0.6 to 0.7 / 53 |
| 3 | 98 (1 OSR) | 96 | 100 | 49782 | 0.7 / 53 |

7. **Held.** The traps stop at exactly one hundred in every fork, the
   `PerMethodTrapLimit` default, and the compile after the hundredth is the
   last: the method then runs at the clean rate for 128 bits (compare 55 in
   2.1's group form), and the 400 recompile cutoff is never approached. The
   cycle lasted 50 to 54 seconds of the JVM's life, inside the 40 to 60 the
   prediction gave, at a new version every 480 to 570 ms here against 265 in
   the eight-second forks, the compile queue being longer when the
   recompiles do not stop.

So the cycle is bounded, and the bound is a counter per method, not per
bytecode: a hundred traps, at about four per compile, which is why it takes
some ninety versions. Once the method has trapped that often C2 compiles it
without the trap it keeps taking, and the result is the code the clean forks
get from their first compile. That makes the cost of the cycle a fixed
minute per kernel class per JVM rather than the life of the executor.

### 2.5 Which half of task 212's path is the cure, 26 September 2026

Twenty forks per case, single form, twelve outputs, both widths
(`target/varka-deopt-cycle/20260926-130047`, appended to the census file):

| path | 512 bits | 128 bits | traps per fork | rate, M rows/s |
| :-- | --: | --: | :-- | :-- |
| `c1off`: the directive, then the batches | 20 of 20 | 20 of 20 | 23 to 26 | 2.1 to 2.5 at 512; 0.7 at 128 |
| `shortcalls`: 12000 calls of 32 rows, C1 left on | 0 of 20 | 0 of 20 | 0 | 157 to 169 at 512; 53 to 58 at 128 |

8. **Held.** The short calls are the cure and the directive is not: with C1
   kept off and the batches from the first call, every fork cycles, more
   surely than on the plain `batches` path (40 of 40 against 39 of 40); with
   the short calls and C1 left on, not one method traps, not even at its
   loop head.

The parser first printed this census as 0 of 20 for a path it called `c`: its
class-name pattern took letters only, so `c1off` lost its digit and its forks
matched no method at all. Reading the pattern with digits changes nothing in
2.1 to 2.4, whose path names have none.

So what arms the predicate is what the interpreter counted at the loop's
branches before C2 compiled it, and the count the short calls leave -
two vector iterations a call at 512 bits, eight at 128, against 64 and 256 a
1024-row batch - builds a loop C2 does not guard with the predicate that
traps. That turns 3.1 from an assembly hunt into a ladder first: the warm-up's
rows at 32, 64, 128, 256, 512 and 1024 on the single form, which finds the
trip count at which the cycle returns, and only then the trap's `relative_pc`
in the assembly, now that the question is narrow.

For production the reading is the same as 2.2's, with a reason attached: the
default form does not cycle, and the path every new kernel takes since task
212 would keep even the old form out of it, because of its short calls - so a
change to the warm-up's row count is the one place this task's finding binds
future work, and 3.3's guard is where to catch it.

## 3. The design

### 3.1 The mechanism hunt, in the order the evidence allows

Everything here is read from the JVM's output on this product JDK, whose
diagnostics are `LogCompilation`, `TraceDeoptimization`, `PrintAssembly` (with
`hsdis`) and `PrintOptoAssembly`; `PrintIdeal` and `TraceLoopPredicate` are
not in a product build, so the ideal graph is not available and the method
below does without it.

1. **What the trapping check is.** The deoptimization log prints each trap's
   `relative_pc` inside the nmethod. `-XX:CompileCommand=print` on the cycling
   method in a cycling fork gives that nmethod's assembly, and the instructions
   before the uncommon-trap stub at that offset are the hoisted check - which
   values it compares, and so which `If` of the loop body the profiled
   predicate lifted out of the loop. `LogCompilation` of the same fork gives
   the compile-time `<predicate>` and the runtime `<uncommon_trap>` elements to
   cross-check bci and reason, read with the task-77 discipline: a
   self-closing element inside `<parse>` is an insertion, an open one with a
   `<jvms>` child is a deoptimization (`sql/varka/skills/the-jit.md`).
2. **What differs between a good fork and a bad one at the first compile.** The
   same logs from a fork that compiled once, compared at the first standard
   compile of the same method: the branch profile the predicate was built from
   (LogCompilation's `<branch>` counts), whether an OSR compile preceded it,
   and the tier-3 history. 2.12's lead is the OSR compile the bad forks show
   after the four head traps.
3. **Why the per-bytecode limit does not end it.** The head's trap stops at
   four; the back-edge's ran to seventeen in eight seconds. Whether that is the
   per-bytecode counter not being kept for this reason, or a different check
   hoisted each time, decides how long a cycling fork stays in the cycle (3.4).

### 3.2 The fixes, in the order of preference the row set

1. **A loop shape the predicate does not misjudge**, if 3.1 names one: behind
   a `VarkaEmitOptions` switch with the current shape as the reference
   variant, judged by the census - twenty forks at both widths, zero in the
   cycle - and by the band, which must not move the rows the shape does not
   touch.
2. **A documented `-XX:-UseProfiledLoopPredicate`** for Varka executors, if the
   cause is the JIT's and not the shape's. The flag is JVM-wide, so its price
   on the other benchmarks is measured before it is recommended.
3. **A decline for the shape with a reason**, failing both.

Which of them, if any, is built depends on section 2: the fixes address a
kernel the default emits, and the census says whether there is one.

### 3.3 The guard

`dev/varka_deopt_cycle.sh` joins `dev/varka_nightly.sh` as a step: the default
form at twelve outputs, both widths, ten forks, failing on any fork in the
cycle. A cycle that returns with a later emitter change is then a red nightly
and not a tier-3 row nobody reads.

### 3.4 The lifetime of a cycling fork

2.12 asked what a long-running query does: `PerMethodTrapLimit` is 100 and
`PerMethodRecompilationCutoff` 400 on this JDK, so the cycle presumably ends
after some tens of seconds of compile time - either because the method's traps
pass the limit and C2 compiles it without the predicate, or because its
recompilations pass the cutoff and HotSpot stops compiling it at all, which
would leave the kernel interpreted for the JVM's life. The probe answers this
with `--seconds 90` on the single form at 128 bits: the trap and compile counts
over time, and the rate in the last seconds against the first.

*Registered 26 September 2026, after the 512-bit census (2.1) and before the
lifetime run.* The census shows the repeating trap at the `goto` back to the
loop head, sixteen to eighteen of them in eight seconds, where the four at the
loop's exit test stop at `PerBytecodeTrapLimit`. A per-bytecode limit that
does not apply at the `goto` leaves the per-method one, and HotSpot's
`too_many_traps` falls back to `PerMethodTrapLimit` (100) where a bytecode has
no profile cell of its own to count in.

7. **The cycle ends on its own between forty and sixty seconds**, when the
   method's `profile_predicate` traps reach a hundred - about two a second at
   512 bits - after which the method is compiled once more without the
   predicate and runs at the clean forks' rate for the rest of the fork; it
   does not reach `PerMethodRecompilationCutoff` (400). A cycle that runs the
   full ninety seconds refutes this and points at the hoisted check being
   remade under a different key each time.

### 3.5 What is deliberately unchanged

The emitter's grouping and byte budget (task 87), the method-size benchmark and
its bands, the JIT thresholds and the warm-up (task 212). The hand-written
`ChronoVectorOps.vectorFourFieldsNoValidity`, which task 77's census found in
a storm of its own (100 deoptimizations across 194 compiles), is reference
code and not a kernel a query runs; it is out of this task.

### 3.6 Registered op counts

None move: this task emits no new operation. A shape variant under 3.2.1, if
one is built, registers its counts in 2.1 of that step before its census.

## 4. Files

| file | what |
|---|---|
| `sql/varka/plans/PLAN_TASK_189.md` | this plan |
| `sql/catalyst/src/test/scala/.../codegen/varka/VarkaDeoptCycleProbe.scala` | the forked child: one kernel, one form, driven for a fixed time under the JVM's logs |
| `dev/varka_deopt_cycle.sh` | the census: forks per width, form and output count, logs and summary under `target/varka-deopt-cycle/` |
| `dev/varka_deopt_cycle.py` | the verdict per fork, from the compile and deoptimization logs |
| `docs/sql-varka.md` | the two tools in the table of what the compiler did |
| `dev/varka_nightly.sh` | the guard step (3.3), once the census has run |

## 5. Tests, and what each is for

The census is the test, and it runs where the fuzzer runs rather than in the
suites: twenty forks of ten seconds is not a unit test's budget. What the
suites keep is the parser: `VarkaDeoptCycleSuite` (to write with 3.3) feeds
`dev/varka_deopt_cycle.py`'s rule the first fork's log and a compiled-once
log and asserts the two verdicts, so a change to the rule or to HotSpot's log
format is caught before a nightly reads nothing and passes.

## 6. The measurement

The census of section 2 is the measurement; both widths, forks rather than
iterations, the verdict from the JVM's words with the probe's rate beside it.
No committed benchmark file moves unless a fix is built, in which case
`VarkaMethodSizeBenchmark` is regenerated with its band at both widths and the
null-free rows from twelve outputs are expected to leave tier 3.

### 6.1 Predictions, registered before the run

Section 2's five, registered there before the census, so the check and the
measurement are one run.

## 7. Risks

1. **The cycle is rarer than the band suggests at 512 bits**, so twenty forks
   see none and a rare mode goes unmeasured. The census prints every fork's
   counts, so a run that sees none says so; the sweep's rungs and both forms
   are the widening available at the same price.
2. **The rule misreads a fork.** Two standard compiles are the head-trap
   pattern and three the cycle by one fork's evidence; the parser prints the
   counts and the trap bcis so a reader can overrule it, and 2.1 reports any
   fork whose counts sit between.
3. **A loaded machine changes what the JIT does.** The census records the load
   average at start; the verdict is a compile log, not a timing, so a busy
   machine shifts the period and not the mode - but a run under load says so.

## 8. Sequencing

1. This plan with the admission check done: the probe, the two tools, their
   documentation line, and 2.1 filled.
2. The lifetime measurement (3.4) and the mechanism hunt (3.1), each a dated
   section here.
3. A fix (3.2), if the census admits one, behind its switch, with its census
   and its regenerated band.
4. The guard (3.3) in the nightly, the parser test (5), and the row closed.

## 9. Outcome

*Written 26 September 2026, when the guard, the parser test and the parser's
fix landed (section 10).*

The C2 deoptimization cycle is real, reproducible and understood in its
lifetime, and no kernel a query runs reaches it. The census of section 2 put
the legacy single-epilogue form in the cycle in 39 of 40 forks and the
default per-group form in none of 180, across six output counts, both
widths and both paths to C2; task 212's short-call warm-up keeps even the
legacy form out of it (2.2, 2.5), and a fork in the cycle leaves it by itself
after exactly a hundred traps (2.4). So section 3.2's fixes had nothing to
fix, and none was built, as section 2 said would follow.

What the task leaves behind is the guard. `dev/varka_nightly.sh` runs the
census over the default form at twelve outputs, ten forks per width, and
fails on any fork in the cycle, on any fork that did not finish, and on a
log with no fork in it; `VarkaDeoptCycleSuite` holds the parser's rule
against three recorded logs, so a change to the rule or to HotSpot's log
format fails a pull request before it can make the guard read nothing.
Writing that suite found a bug in the parser, recorded in 10.3.

Step 3.1, the mechanism hunt - which check the profiled loop predicate hoists
and why short calls cure it - is not done. The cycle reaches only a form no
query runs, so it is research, and it goes to a new row rather than keeping
this one open.

## 10. The guard, the parser test and the close, 26 September 2026

### 10.1 The guard

`dev/varka_deopt_cycle.py --fail-on-cycle` exits 1 when any fork is in the
cycle, when a fork has no DONE line, or when the logs hold no fork at all,
and prints a `verdict:` line that `dev/varka_nightly.sh` already surfaces in
its summary. `dev/varka_deopt_cycle.sh` passes the flag through and keeps the
parser's status past the `tee`. The nightly's new `deopt` step is 3.3's:
`--forms group --forks 10 --fail-on-cycle`, twelve outputs, widths 64 and 16;
`--skip-deopt` leaves it out.

Run once as the nightly runs it, on the laptop at a load of about 0.3
(`target/varka-deopt-cycle/20260926-230958`): 0 of 20 forks in the cycle,
exit 0, 243 seconds with the build warm. The positive control, the same
guard over the legacy form at two forks per width
(`target/varka-deopt-cycle/20260926-231405`): 4 of 4 in the cycle, exit 1.

### 10.2 The parser test

`VarkaDeoptCycleSuite`, seven tests over three logs recorded today at 128
bits and trimmed to the probe's and the kernel class's lines, under
`sql/catalyst/src/test/resources/varka/deopt-cycle/`: `cycle.log`, one fork of
the legacy form (`loopDense1` 14 tier-4 compiles, 13 made not entrant, 17
traps at bcis 426 and 3166); `once.log`, one fork of the default form; and
`head-traps.log`, two consecutive forks of the default form at sixty outputs.
The tests assert the verdict of each, the exit status with and without
`--fail-on-cycle`, the count across two logs, the failure on an empty log and
on a fork cut before its DONE line, and the fork boundary of 10.3.

Two mutations of the parser were run against the fixtures to confirm the
suite would notice them. The first rule of 2.3 (three standard tier-4
compiles and any trap) reads `head-traps.log` as 2 of 2 in the cycle, where
the test expects 0 of 2; the old fork boundary moves a compile between the
two forks of the same file, where the boundary test expects it to stay.

### 10.3 A finding: the fork boundary

The parser split forks at each child's last line, `VARKA_DEOPT_DONE=`. A
JVM that exits with compiles still queued prints them after that line -
tier-4 task lines marked `blocked` - and every fork of a case emits a class
of the same name, so those lines were counted in the next fork. At sixty
outputs it moved a fourth tier-4 compile of `loopDense3` from the first fork
to the second. The parser now splits at each child's first line,
`VARKA_DEOPT_METHODS=`, and the DONE line only says whether a fork finished.

What it could have changed: the verdict reads only "made not entrant" and
`profile_predicate` trap counts, and of the ten shutdown lines in every log
recorded today all ten are queued compiles, none of either kind. The raw logs
of sections 2.1 to 2.5 were not kept, so their per-fork compile counts cannot
be reread; their verdicts rest on the counts the boundary does not move in
any log that could be checked. The lesson is in `sql/varka/skills/the-jit.md`,
"A forked JVM keeps printing after its last marker line".
