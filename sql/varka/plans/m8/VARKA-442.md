# VARKA-442: The ladder benchmark settles on the tier witness, and the queue parser has tests

## 0. What exists now (admission check)

From master at `5c660201b35`, read on 1 November 2026 with `git`. Nothing was run.

1. **The settling loop the row names.** `VarkaSharedPrefixBenchmark.driveToCompiledCode`
   (`VarkaSharedPrefixBenchmark.java:180-226`) drives an arm until, after a drive, C2 is idle
   (`VarkaEmitterTestSupport.awaitC2Idle`), every dense loop method reads level 4 in
   `VarkaEmitterTestSupport.loopLevels`, and two drives agree within `STEADY_FRACTION`.
   `loopLevels` (`VarkaEmitterTestSupport.java:480-520`) takes the highest in-use level from the
   code list. Its own Javadoc says what the row says: an OSR version reads as 4, and a loop C2
   inlined into its caller reads lower.
2. **The witness-based form exists, built today.** `VarkaDecimalStoreBenchmark` (#1240, row 157,
   merged 1 November 2026) settles an arm through `VarkaTierWitness` (`:560-640`):
   - the witness is started before any kernel runs, and every arm's class is watched (`:291-300`);
   - after the cheap conditions hold, it waits for the recording to deliver (`settled`, `:539-557`);
   - it reads each loop through `witness.confirmed(new Decision(...))` (`readTiers`, `:515-537`);
   - it returns a `TierState` that says why an arm did not settle;
   - the witness is closed when the section ends (`:307`).

   Its Javadoc records why the code list was not enough there: "a loop whose code was thrown away
   and that C2 then compiled only inside its caller has no version of its own, which the past-L3
   rung showed on five of six arms in a first laptop run". So the row's alternative, "the record
   says why the code list suffices", is answered against the code list by the project's own
   record.
3. **The same loop is copied twice more.** `VarkaNarrowEmitBenchmark.driveToCompiledCode`
   (`:640-675`) is the code-list form, with a fallback to the masked loops. `awaitC2Idle` alone is
   also called by `VarkaNarrowLaneBenchmark` (`:715`) and `VarkaDecimalPairSpikeBenchmark`
   (`:1349`).
4. **The two failure policies.** `awaitC2Idle` reads an unreadable queue as busy, waits out its
   bound and returns `false`, so the arm is timed as it is, with a console line.
   `loopLevels` calls `VarkaTierWitness.readCodeList`, which throws `IllegalStateException`
   ("could not read the code list"), so the same JVM fault ends the whole benchmark.
   `VarkaDecimalStoreBenchmark` lets a witness failure other than a delivery timeout propagate.
5. **`c2Compiling` has no test.** `VarkaVectorTemplateWarmup.c2Compiling` (`:498-512`) counts the
   non-blank lines of the "Current compiles:" section that do not start with `C1 `.
   `VarkaVectorTemplateWarmupSuite`'s test "the pacing reads C2's queue as the JVM prints it"
   (`:231-245`) asserts `c2QueueLength` on four fixtures, and in each of them the "Current
   compiles:" section is empty. The format, read from the local JDK 25 source:
   - `CompileBroker::print_compile_queues` (`compileBroker.cpp:549-560`) prints
     `Current compiles: ` and then `Threads::print_threads_compiling` in short form;
   - that prints each busy compiler thread's name, two spaces and the task's short line
     (`threads.cpp:1497-1518`);
   - the thread names are `"%s CompilerThread%d"` with the compiler's name, C1, C2 or JVMCI
     (`compileBroker.cpp:939`, `:1089`).

### What differs from the row text

- The row asks for the witness in "the ladder benchmark". The mechanism to reuse is already in
  `VarkaDecimalStoreBenchmark`, private to it. The work is to move it into a shared helper and to
  call it from the ladder benchmark, not to write it.
- `VarkaNarrowEmitBenchmark` has the same flaw and is not named by the row (question 1).

## 1. Goal and non-goal

**Goal.** `VarkaSharedPrefixBenchmark` settles an arm only on levels the tier witness confirms,
through one helper that `VarkaDecimalStoreBenchmark` also uses. `c2Compiling` is tested on the
format HotSpot prints. An unreadable code list and an unreadable queue lead to the same outcome:
the arm is timed as it is, and the run says why.

**Non-goal.** No change to the arms, their shapes or the committed results beyond what the
recording's presence moves (P2). No change to `VarkaTierWitness`'s verdict logic.

## 2. Design

### 2.1 One settling helper

`VarkaEmitterTestSupport` gains the decimal benchmark's pieces as one type, a record `Settle` with
a static `drive` method:

- inputs: the kernel class, the loop names (dense, or masked where the class has no dense side,
  as `VarkaNarrowEmitBenchmark` does), a `Runnable` drive, and the bounds (minimum drives, the
  steady fraction, the wait). Extra methods to hold to C2 can be added, which the decimal
  benchmark needs for `Buffers.widen`;
- output: the `TierState` record, settled or not with its reason, and the drives, the per-loop
  verdicts and the kernel's levels for the note.

The helper takes a witness the caller has started. Starting it is the caller's job: it must start
before the first kernel runs, or the compiles of the early drives are unknown to it and the
strict levels never count them.

`VarkaDecimalStoreBenchmark` moves onto the helper with no behaviour change. Its emitted bytes,
its arms and its messages stay the same.

### 2.2 The ladder benchmark on the helper

`VarkaSharedPrefixBenchmark.runBenchmarkSuite` starts the witness after the arms are emitted and
before the first `driveChecked`, watches every arm's class, and closes it in its `finally`.
`driveToCompiledCode` becomes a call to the helper. An arm with `c2Expected` false (the one
whose single wide loop no compiler takes) keeps its exemption: the helper settles it on drive
times alone. The console line for an arm that did not settle keeps its form and adds the reason
from `TierState`.

### 2.3 One failure policy

Both readers handle an unavailable diagnostic command the same way: the arm is not settled, and
is timed as it is, with the reason "code list unreadable" or "queue unreadable". `loopLevels`
catches the code list's `IllegalStateException` and returns an empty map, which `allLoopsC2`
already reads as not C2. Its Javadoc says so. The helper does the same for a witness that
cannot read the code list. A delivery timeout stays a recoverable "recording behind", as in the
decimal benchmark. Any other witness failure still propagates: that is a bug in the witness,
not a JVM without the command.

### 2.4 Tests of the queue parser

The warm-up suite's queue test gains fixtures in the JVM's format (item 5):

- one C1 thread busy, `C1 CompilerThread0  2071       3       java.lang.String::hashCode (49
  bytes)`, gives `c2Compiling` 0;
- one C2 thread busy, `C2 CompilerThread0  2074       4       pkg.K::loopDense0 (891 bytes)`,
  gives 1;
- both busy gives 1, and two C2 threads give 2;
- an OSR task line, `%` before the level, still counts;
- a `JVMCI CompilerThread0` line counts as the top tier's, as `c2QueueLength` treats the JVMCI
  queue;
- an empty section and an unknown text give 0.

The exact column spacing of the task line is **[I]**: taken from `CompileTask::print_impl`'s
format as read, not from a captured run. The parser does not depend on the spacing, only on the
thread name at the line's start.

## 3. Tests and proofs

1. The warm-up suite's queue test with 2.4's fixtures, red before the change for none of them
   (the parser exists), and a planted case: a parser that counts C1 lines must fail the C1
   fixture.
2. `loopLevels` returns an empty map when the code list cannot be read. This is tested through a
   seam that takes the reader, so the test needs no JVM without the command.
3. `VarkaDecimalStoreBenchmark` and `VarkaSharedPrefixBenchmark` dry runs
   (`-Dvarka.bench.dryRun=true`), which emit every kernel and time none. They check that the
   classes still build their arms.
4. The pair: `benchmark.yml` in pair mode, class `VarkaSharedPrefixBenchmark`, against the merge
   base on the runner, and the same for `VarkaDecimalStoreBenchmark`.

## 4. Predictions, registered before the build

- P1: with the witness, every arm of `VarkaSharedPrefixBenchmark` settles as it did, since in the
  one whole run every arm had its own level-4 entry (row 440). The settle takes about one JFR
  delivery longer per arm, about a second, and the benchmark's wall grows by about a second per
  arm.
- P2: the open recording moves no timed row outside the committed
  `VarkaSharedPrefixBenchmark-jdk25-band.txt` on the pair. The recording delivers events only for
  compiles, traps and inlining decisions, which are rare once an arm has settled (**[I]**: the
  recording's cost on timed rows was not measured by row 157).
- P3: `VarkaDecimalStoreBenchmark`'s pair is flat. Only its code moves, not its behaviour.

## 5. Order, files and size

| File | Change |
|---|---|
| `VarkaEmitterTestSupport.java` | the settling helper; `loopLevels`'s failure policy |
| `VarkaDecimalStoreBenchmark.java` | onto the helper |
| `VarkaSharedPrefixBenchmark.java` | the witness and the helper |
| `VarkaVectorTemplateWarmupSuite.scala` | the queue fixtures |
| `sql/varka/skills/the-jit.md` | one paragraph under "Which tier served a call": use the helper |

Order: the helper with the decimal benchmark moved onto it (its pair flat); the ladder benchmark;
the parser's tests and the failure policy.

Size: small, as the row says.

## 6. Out of this row

- `VarkaNarrowEmitBenchmark`: brought in by the owner's answer to question 1, and built here.
- `VarkaNarrowLaneBenchmark` and `VarkaDecimalPairSpikeBenchmark`. *Corrected 14 November 2026
  (the row's review):* this entry first said they "call `awaitC2Idle` alone and read no
  levels", which their code contradicts - each drives until its own `levelInUse` reads level 4,
  the code-list condition this row rejects. `VarkaDecimalRemainderSpikeBenchmark`, written
  after this plan, has the same loop and was not named at all. All three keep their own loops
  until a row of their own.

## 7. Questions for the owner

1. **`VarkaNarrowEmitBenchmark`'s copy.** It has the same code-list loop (item 3). Should this
   row move it onto the helper too, with its pair, or should it get a row of its own?
   Recommended: move it here. The change is the same call, and the helper's masked-loop fallback
   exists for it.
2. **The recording during timed runs.** If P2 fails, should the benchmark (a) close the witness
   before each timed run and reopen it, losing the OSR knowledge of earlier compiles, or (b) keep
   the code list and accept the row's two blind spots, with that said in the Javadoc?
   Recommended: decide after the pair. If the pair is flat the question does not arise.

## 8. Done when

- `VarkaSharedPrefixBenchmark` settles on the witness's levels through the shared helper;
- `c2Compiling` has assertions on fixtures with a C1 and a C2 thread busy;
- an unreadable code list and an unreadable queue both leave the arm timed as it is, with the
  reason printed;
- the two pairs are committed or cited by run.

## 9. Open points for the lead

1. Row 404 needs a per-case tier check for `VarkaLaneConversionBenchmark` and would use this
   helper if it lands first.

## 10. Outcome

Built as design 2.1 to 2.3, with the owner's answer to question 1 (yes: the narrow benchmark's
copy moves too). Question 2 is pending the pair; nothing about it was decided here.

- `VarkaEmitterTestSupport` gains `Settle`, a record holding the kernel class, the loop names
  (dense, or masked, chosen by the caller), the extra methods to hold to C2 (the decimal
  benchmark's `Buffers.widen`), the drive, the bounds and whether C2 is required, and
  `TierState`, its result: settled or not, the drives, the last two drives' times, C2's idle
  state, the per-loop and per-extra verdicts, the kernel's compiled levels, the reason and the
  note's failure clause. `Settle.drive` is the decimal benchmark's loop unchanged: the least
  drives, C2 idle after each, the last two drives steady, then the recording settled and the
  code list read through `VarkaTierWitness.confirmed`. The caller starts the witness before the
  first kernel call; the helper only reads it.
- `loopLevels` catches an unreadable code list and returns an empty map, which `allLoopsC2`
  reads as not C2. A reader seam (`loopLevels(class, dense, Supplier)`) makes the policy
  testable without a JVM that lacks the command.
- `VarkaDecimalStoreBenchmark` calls the helper. Its arms, emitted bytes and console messages
  are unchanged; its private `Tiers` and `TierState` are gone.
- `VarkaSharedPrefixBenchmark` emits every shape's arms first, starts the witness after the
  last emission and before the first `driveChecked`, watches every kernel class, settles each
  arm through the helper (its `c2Expected` arm exempt, settling on drive times alone), and
  closes the witness in a `finally`. Its failure line keeps its form and adds the reason from
  `TierState`.
- `VarkaNarrowEmitBenchmark`, brought in by question 1, does the same with its dense-or-masked
  loop choice; its pair is owed with the other two.
- One failure policy: an unreadable code list or queue leaves an arm not settled and timed as
  it is, with "the code list unreadable" or "the queue unreadable" in its state and its printed
  note. A delivery timeout stays the recoverable "the tier witness's recording behind"; any
  other witness failure propagates.
- `VarkaVectorTemplateWarmupSuite` gains the `c2Compiling` fixtures: a C1 thread, one and two
  C2 threads, both busy, an OSR task line, a JVMCI thread, an empty section, unknown text, and
  a planted parser that counts every thread, which the C1 fixture tells from `c2Compiling`.
- A new `VarkaEmitterTestSupportSuite` holds the `loopLevels` seam test: an unreadable code
  list keeps each declared loop at level 0 (the names survive and nothing reads as C2), and a
  readable fixture gives the loop its level.
- `sql/varka/skills/the-jit.md` gains the paragraph that sends a settling benchmark to the
  helper.

Checks and the pending pair are recorded below as they were run.

- `build/sbt catalyst/Test/compile`: exit 0 (a first pass failed one javac call in the new
  helper, `loops(tiers)` read as the record's own accessor; qualifying it with
  `VarkaEmitterTestSupport.` fixed it, and the rerun compiled the whole test tree).
- `catalyst/testOnly *VarkaEmitterTestSupportSuite *VarkaVectorTemplateWarmupSuite`: 14 tests,
  0 failed (the warm-up suite's 12 and the new suite's 2).
- `dev/lint-scala`: "Scalastyle checks passed. Scalafmt checks passed."
- `dev/lint-java`: "Checkstyle checks passed."
- `python3 dev/varka_push_check.py`: exit 0, 894 counted lines of 894 changed against
  `4c57947acd2`, under the 1000-line cap; R4 ran the pre-commit over the range, the plan lint,
  the quote check, the census and the size, and chose no suite, since no Varka main or generated
  file changed.
- The dry runs (3.3) and the pairs (3.4) were not run here: the pairs run on the benchmark
  runner against the merge base, and no benchmark was run in this task. Question 2 (the
  recording during timed runs) is decided after the ladder's pair: if P2 holds (no timed row
  moves outside the committed band) it does not arise; if it fails, the owner chooses between
  closing the witness before each timed run, which loses the OSR knowledge of earlier compiles,
  and keeping the code list with the row's two blind spots.

**The review (14 November 2026).** `/code-review high` on the branch found the settle's first
policy wrong in a way this sheet had not: `loopLevels` returned an empty map on an unreadable
reading, and all three benchmarks take the settle's loop names from its key set, so one failed
read made that arm unsettleable for the rest of the run while its note carried no reason. The
fix keeps the declared names at level 0, short-circuits a settle whose code list is unreadable to
"the code list unreadable" instead of driving out the warm bound, and `Settle.drive` now fails
loudly when the caller has not watched the kernel class or an extra class, instead of settling
an arm that never could. It also corrects the class documents that still named the code list as
the settle gate, the inverted OSR sentence in `VarkaSharedPrefixBenchmark`, and the notes that
re-read the code list instead of printing the state the settle carried; the narrow benchmark's
"loops at level 4" line now says what the witness confirmed. Three things are left with their
reasons in the pull request: the JFR recording now running through the two new benchmarks' timed
cells, which the pairs this row owes measure; the arms whose diagnostics are never served still
driving their bound before giving up when the code list was readable at the start; and the shape
of the `Settle` API itself. This sheet's section 6 was also wrong about the three spike
benchmarks that keep their own settle loops; they take a row of their own.

**The pairs (14 November 2026).** Benchmark run 43575854826 on vecbricks/varka ran all three
classes in pair mode against the merge base on an EPYC 7763, and every pair is green:
`VarkaDecimalStoreBenchmark`, `VarkaSharedPrefixBenchmark` and `VarkaNarrowEmitBenchmark`.
Section 3.4's two pairs and question 1's narrow pair are cited by run.
