# Varka Milestone 7 Plan: trust and maintainability

*Opened 2 October 2026, while milestone 6 closes. Offered four directions drawn from the scope
catalogue - coverage and a first query-level number, no case handed back to Spark's code
generator, a plugin others can install, and trust and maintainability - the owner chose the
last.*

This is a task plan, not a scope catalogue. Under the house rule (`sql/varka/AGENTS.md`), the
catalogue moved forward the same day: `SCOPE_MILESTONE_7.md` is now `SCOPE_MILESTONE_8.md`,
with its item numbers unchanged, and the coverage spine it argues for (decimal lanes, aggregate
wiring, grouped aggregation, string keys, TPC-H q6) is milestone 8's. Nothing in it is withdrawn.
The items this milestone takes are named below with the item number they have there, and the
rows that moved there from milestones 5 and 6 keep their task numbers.

*2 October 2026, before this plan merged: a review of it against the record added rows 262 to
270, gave each structural row the proof that fits it (section 5), and changed rows 138, 217,
248, 251 and 255; section 2 says why each.*

*2 October 2026, later the same day: a survey of how thirteen engines keep their answers right
(`READING_MILESTONE_7.md`) added rows 271 and 272 and refined rows 248, 255, 256, 262 and 263.*

## 1. The question, and what "done" means

Six milestones built vocabulary and then a foundation under it, and each was checked as it
went: the coverage oracle, the sweeps against `java.time`, the fuzzers against the reference
evaluator, the bytes oracle. What the record now shows is where that checking stops and where
the code has grown faster than its structure.

* **Proof stops at 32 bits.** Every int32 lowering is proven by exhaustion, thirteen and a half
  minutes of each nightly. The long lane cannot be exhausted, so its divisions rest on a Python
  model and sampled regions (`SCOPE_MILESTONE_8.md` item 58).
* **The differential tests have known gaps.** Spark's own date tests have never been run
  against Varka (row 81). No test poisons the null lanes of a masked body or checks that both
  engines throw on an ANSI row (row 138). The fuzzers compare kernels only with Varka's own
  reference evaluator, never with Spark (2.2). The shared test JVM runs a second vector species,
  which can leave later kernels boxed and made one suite fail in about one full run in three
  (item 69).
* **The code is harder to change than it should be.** A new emit option touches about nine
  places. `emitBody` is one 372-line method serving three roles. The compiler's facade is
  1346 lines of Scala, and method names are matched as string prefixes in a dozen places
  (item 74).
* **The infrastructure leans on the owner's laptop.** The CI queue stops when the laptop
  sleeps (item 76). A benchmark run rewrites every section of its file on whichever CPU the
  pool assigns (item 72).

Milestone 8 adds new kinds of operator: aggregates, decimals, strings. This milestone makes the
base they land on provable where proof is cheap, tested where it is not, and easy to change.

### 1.1 Done when

1. **Every bounded arithmetic lowering carries a machine-checked proof** over its guarded
   domain, run in CI in under a minute and stated against a specification of the SQL standard
   with Spark's departures named (tasks 240 to 245).
2. **The tests reach what they miss today**: Spark's own date tests, poisoned null lanes, the
   ANSI form comparing error classes, and the partition oracle (81, 138); random compositions
   compared with vanilla Spark itself (262), Spark's own SQL suites run with Varka on (271), and
   every small IR tree through the emitter (269); every segment a kernel maps checked against its
   buffers under test (263), and every kernel's answer the same under the interpreter, C1, C2 and
   the Vector API's intrinsics off (272). No Varka suite runs
   a second species of a lane type in the shared test JVM (246), and every emit option states
   why it exists and moves some hash in the bytes oracle, or is gone (247, 248). What the tests
   miss is measured, by branch coverage and a bounded mutation run (265).
3. **Varka's code is Java and legible**: the compiler ported with its shape cache (214 to 217,
   224) and the evaluators with it (251, 267), checked by Error Prone and NullAway (266); item
   74's refactors landed, each with the proof its row names (248 to 252); the runtime refusals
   and the operand admission each stated once (83, 86); and milestone 4's debt register swept
   (264).
4. **The infrastructure stops costing manual work**: CI reaches a verdict with no process on a
   laptop and runs master after each merge (255), and a benchmark run regenerates only its own
   sections (256). The slow mode a kernel meets after other kernels is a number (257).
5. Beside the spine: promotion continues (180), the three posts the owner chose on 2 October
   are published (258 to 260), and the milestone closes on a post of its own (270).

## 2. Design

Each row's design is in the item it comes from; this section says what the milestone adds to
it and how the rows meet.

### 2.1 Proofs (tasks 240 to 245)

Item 58 is the tooling and the proofs, in its order: SMT-LIB files under `sql/varka/proofs/`,
one per lowering with the Java it encodes named in its header, run by `dev/varka_prove.sh`,
which fails on any `sat` and on a missing solver. The int32 multiply-high bound comes first,
because task 149's sweep checks the same statement and the two are compared for one nightly
cycle: the SMT encoding is a second implementation of the lowering, so agreement between them
is worth more than either alone. Then the long-lane division forms, where no sweep reaches, with
the solver asked for the exact bound rather than handed one. Then the rest as met.

Item 59 gives the proofs a specification better than "the Java formula": the standard's
definitions for the operations the coverage table lowers, Spark's departures as named deltas
(`clamp_to_month_end`, `overflow_yields_null`, `proleptic_range`), each with a test showing
Spark doing it, and the proofs restated as refinements in Spark mode and in standard mode. It is
taken in full; task 245 is where it can be cut if 243 and 244 run long, since the proofs of 240
to 242 stand without it.

### 2.2 The tests, and how well they test (81, 138, 246, 247, 262, 263, 265, 269, 271, 272)

Row 81 is planned (`PLAN_TASK_81.md`) and suits a newcomer. Row 138's three forms all run in
`VarkaCoverageDifferentialSuite`, and its ANSI form compares the error class, the SQLSTATE and
the message parameters, as Spark's own `checkError` does, rather than only that both engines
throw: a user's error handling reads those. Task 246 comes early: until the shared JVM runs one
species per lane type, a test whose verdict is a JIT outcome can fail by suite order, and every
later task reads those suites. Task 247 is small: `validityOrFirst` is the one option that
moves no hash anywhere, so either the shape it guards joins the oracle or the option goes;
248's table makes the same question answerable for every option.

**Vanilla Spark as the oracle, at random (262).** The contract is Spark's answer, and no
randomized test holds Varka to it. Both fuzzers compare kernels with Varka's own reference
evaluator: `VarkaIrFuzzSuite` does, and `VarkaCoverageCompositionFuzzSuite`'s one answer check,
in its wide test, is `VarkaKernelCheck` against the same evaluator. Spark is the oracle only
for fixed shapes: `VarkaDifferentialSuite`'s 89 hand-chosen tests, and each row of
`VarkaCoverageDifferentialSuite` alone. Task 262 runs the composition fuzzer's random
projections and filters through a Spark session with Varka on and off, over random data with
nulls, with ANSI mode on and off, and compares the answers; a disagreement is a bug or a named
delta (244). One arm joins the nightly. Three things the other engines have come with it
(`READING_MILESTONE_7.md` 1): a failing composition is shrunk to its smallest failing subtree, as
Velox's expression runner does, where the record has only ever minimized by hand; it is saved,
and the saved reproducers replay in PR CI, as Arrow's fuzzers' do; and each fused output is
forced to decline in turn, the answer unchanged and the comparison counted only where the plan
changed, as Comet's fallback-invariance suite does.

**Spark's own suites with Varka on (271).** Comet patches a Spark checkout per Spark version so
that Spark's SQL suites run with Comet loaded, Spark's golden `.sql.out` files the oracle and
every excluded test given a reason; Gluten subclasses about 480 of Spark's suites the same way,
and its Delta gate fails when an excluded test starts passing, so the list cannot go stale
(`READING_MILESTONE_7.md` 2). The record called this the widest differential available on
16 September 2026 (`SCOPE_MILESTONE_8.md` item 24) and never made it a row. Row 81 harvests the
date family's golden inputs; row 271 runs Spark's suites with Varka on, exclusions reasoned and
self-checking.

**The memory contract, checked (263).** A kernel gets raw addresses, and
`VarkaVectorSupport.ofAddress` is `MemorySegment.ofAddress(addr).reinterpret(bytes)`, sized
inside the kernel from the batch length and checked against nothing; the word-at-a-time
validity writes rely on Arrow's padding. Item 58 found the emitter's plumbing to be where the
record's bugs were, a byte-per-lane read of a bit-packed validity buffer among them. Under a
test-only flag the evaluator records each buffer's address range, and `ofAddress` fails when a
segment leaves them; the suites and the fuzzers run with the flag on, so an access past a
buffer fails by name instead of reading what lies beyond it or crashing the JVM. From the
other engines (`READING_MILESTONE_7.md` 5): a canary past each buffer, checked when the kernel
returns, as Arrow's debug memory pool writes one; a record of where each segment came from, as
Druid's poisoned buffer pool keeps; and every byte back at close, as Trino asserts per test
class. The check is against each buffer's real capacity, not its nominal size: Arrow only
recommends padding, and its Java IPC reader slices a buffer to its exact length, so the
whole-word validity writes are safe only on the buffers Varka allocates itself.

**The platform under the kernels (272).** Item 58 called HotSpot the trusted base that no proof
reaches, and JDK 25 carries a known C2 miscompilation of masked stores (JDK-8388492, item 56).
OpenJDK's own `VectorizationTestRunner` gets the interpreter's answer through WhiteBox and
compares it with C2's, and the Vector API's plain-Java fallback, which runs with
`-XX:-EnableVectorSupport`, is a second implementation of every intrinsic, sharing only the
checks made before the dispatch (`READING_MILESTONE_7.md` 8). Row 272 runs the fuzzers' kernels
under the interpreter, C1, C2 and the intrinsics off, each in a forked JVM, and compares the
answers; C2's seeded stress flags (`StressIGVN`, `StressGCM`, `StressLCM`, `RepeatCompilation`)
run on the fastdebug JDK, and are the levers row 257 lacks.

**Every small tree (269).** Random draws miss corners. Every well-typed IR tree up to a small
size goes through the emitter and is compared with the reference evaluator, under the null
patterns the fuzzers use. Item 58 called the validity and guard word algebra the one finite
piece of the emitter a model could cover; enumerating small trees covers it without a model.

**How well the tests test (265).** Nothing in the record measures it: no branch coverage, no
mutation testing. The first step is branch coverage of the emitter and the compiler under the
suites and the fuzzers, which names the arms nothing runs; the second is a bounded mutation run
(PIT) on `Slots` and `Analysis`, in an idle machine window. A mutant that survives becomes a
test, or a note that it changes no behaviour. It comes after 262, 263 and 269, so that it
measures the tests this milestone leaves.

### 2.3 Structure (248 to 254, 214 to 217, 224, 83, 86, 222, 264, 266 to 268)

Each row names its own proof. For the emitter's refactors (248, 249, 250, 83) it is
`emitted_bytes.json`, unchanged. The ports (214 to 217) change no emitted byte either, but the
bytes oracle says nothing about what the compiler admits: theirs are the coverage and
family-chain oracles, as task 175's were, and a planning-time benchmark from task 191's
emission times. The evaluator's split (251) and its port (267) are per-batch overhead on the row
boundary, so theirs is a before-and-after benchmark of it; caching the IR's hashes (222) is
compile time, measured before and after.

The options table (248) and the method-name class (249) go first, because every later refactor
touches options or method names. The table records why each option exists - an alternative kept
because the winner depends on the machine, a reference form, a fault injector such as
`misdescribeWordLiveness`, or retired - so that 247's question is answered for every option at once.
It is also the source of a configuration matrix (`READING_MILESTONE_7.md` 3): the suites run under
the options' configurations with a skip list giving a reason for each entry, as DuckDB's
`test/configs` do, and a shape marked as declining fails when it starts to fuse, as Druid's
`cannotVectorize` marker does, so neither list can go stale. The emitter splits (250, `emitBody`)
and the evaluator split (251) follow, 251 splitting `VarkaEvaluatorBase` into Java components rather
than into smaller Scala files. Row 83's single refusal goes after 250, since both rewrite the
emitter's refusal paths. Row 86's single operand admission goes after the ports, so that the
admission is written once in Java and not ported twice. The ports themselves (214 to 217, 224) are
mechanical and meant for an agent, gated as task 175 was. Row 217 is item 74.4, and takes
`VarkaShapeCache.scala` with it, the one Scala file in the compiler's own package, so that 1.1's
claim holds. Item 74.2, the size loop, is task 236's and is not repeated here. The comments pass
(252) runs last, once the structure has settled.

**The rest of the Scala (267, 268).** Varka's main code holds 17 Scala files and 6,741 lines
today, and about 3,900 lines in 13 files after rows 214 to 217. Row 267 ports the evaluators
beside 251's components: `VarkaKernelEvaluator` (453 lines), `VarkaFilterEvaluator` (361),
`VarkaVectorProjection` (86), `VarkaFusionReport` (117) and `VarkaExecMetrics` (94);
`VarkaEvaluatorBase`'s 1,079 are 251's. The exec nodes and the columnar rule stay as thin Scala
wrappers, since `SparkPlan` case classes are the interop surface the owner's Java rule exempts.
The tests are 120 Scala files (40,375 lines) against 10 Java; row 268, optional, moves
`VarkaEmitterTestBase`'s helpers to Java with the suites as thin wrappers over them, the rule
the owner set for test helpers on 30 September 2026.

**Static analysis (266).** Once the ports land, Varka's main code is nearly all Java, and
nothing checks it beyond javac and checkstyle. Row 266 runs Error Prone and NullAway on
Varka's Java packages only, not Spark's. Its first step confirms that Error Prone runs on JDK
25's javac with the incubator module. It comes early, so that the ports and the refactors are
checked as they land.

**Milestone 4's debts (264).** `sql/varka/AGENTS.md` says a swept debt is rewritten in the past
tense, and several entries of `PLAN_MILESTONE_4.md` section 9 still read as open.
`dev/varka_emit.sh` reporting a crash as an empty success looks fixed in the script, which now
runs under `set -euo pipefail` and diagnoses a non-zero run, but its entry was never rewritten;
the range analysis's loose `INT` answers, the parity harness's `next_day` row and the week fold
have no recorded outcome. Each is closed, made a row, or moved to `SCOPE_MILESTONE_8.md`.

Task 253 states and enforces the seven-argument `run`'s scratch contract (item 68). Row 222
caches each IR node's hash. Task 254 (item 63) either pins each grouping weight to the count
its node emits alone, or retires the hand-written register so the grouping reads the class.

### 2.4 Infrastructure (255 to 257)

Task 255 takes the CI queue off the laptop with no stored credential, splitting it so each
repository acts only on itself. `PLAN_TASK_227.md` 3 put the whole queue on `vecbricks/varka`,
which needs a personal access token on the fork with Actions write, kept as a secret there. The
owner declined that on 2 October 2026: any workflow on any branch of the base repository can read
a repository secret, and Actions write on the fork can dispatch workflows that push commits.
Instead:

* **The queue runs on the fork.** A workflow on `MaxGekk/spark`, on a five-minute cron and
  `workflow_dispatch`, decides as 227 lays out - open pull requests without a passed Build, in
  number order, a `ci-hold` label taking one out - and cancels and reruns the fork's own runs
  with its built-in `GITHUB_TOKEN` (`permissions: actions: write`). It reads the base
  repository's pull requests without a credential, since the repository is public.
* **The check sync runs on the base repository**, as `update_build_status.yml` does now, on a
  cron as well as on dispatch: it reads the fork's runs without a credential and writes its own
  pull requests' checks with its own `GITHUB_TOKEN` (`checks: write`).

GitHub runs a fork's scheduled workflows only after they are enabled once in its Actions tab,
and only from its default branch; both are settled in the task (section 7). The queue also runs
master's head after each merge. Master gets a full run only on Sundays
(`varka-weekly-matrix.yml`), since row 177 found that nothing runs after a merge, and with about
fifteen refactor PRs in this milestone a conflict between two green PRs could sit on master
until Sunday. The queue also reruns a PR's new tests several times, and requires a fix's new
test to fail on the PR's base, as ClickHouse's CI does (`READING_MILESTONE_7.md` 9).

Task 256 lets a benchmark run regenerate only the sections a PR adds or changes, and runs them
paired against the PR's merge base in alternating batches with a threshold, as DuckDB's
regression runner does, so that a band becomes a gate. Task 257 is
item 62's probe: the slow mode a kernel meets after N other kernels, as a function of N, with
one arm in the nightly.

**The issue mirror, fixed with this plan.** `dev/varka_issues.py` mirrored only the
highest-numbered plan, and its `classify` compared the whole marker with "moved", so
"**Moved to milestone 7**" read as open, against its own docstring. This plan's merge would have
switched the mirror to milestone 7, leaving rows 236 and 239 unsynced and the issues of rows
182, 207, 208, 213, 218 and 231 open for good under a milestone-6 label. The script now matches
the marker by prefix and reads every plan: only the current plan opens issues, an earlier plan's
rows keep the issues they already have in step, and each issue carries its own milestone's
label. Its dry run before this plan merged would close those six issues as not planned, move the
moved rows' issues to milestone 7, keep 236 and 239 in step, and leave milestone 4's open rows,
never mirrored, alone.

### 2.5 Promotion (180, 258 to 260, 270)

Task 180 is the cadence carried from milestone 6. Tasks 258 and 259 are the pair of posts in
items 78 and 79, 258 first; 260, the reference post of item 80, comes between them and the next
long post, as the catalogue orders them.

Task 270 is the milestone's own post, for JVM and database engineers: how Varka knows its
answers are right - the proofs, the differential against Spark, the sanitizer, the measured
adequacy of its tests - and where that knowledge stops, at the emitter's plumbing, which item 58
bounds. Milestones 4, 5 and 6 each ended in a public message, and item 58 calls "every
arithmetic lowering carries a machine-checked proof over its guarded domain" publishable.

### 2.6 The closing task (261)

1.1 read row by row, the scoring of each row's predictions collected, and the README's account
of how Varka is checked rewritten on what the proofs and tests now hold, quoting nothing that
does not trace to a committed file. Task 270's post is written from the same record, after it.

## 3. Task breakdown

Task numbers continue the single sequence from 240; rows that moved to the catalogue from
milestones 5 and 6 keep theirs. Rows 262 to 270 came from the plan's own review, and 271 and
272 from the survey of other engines (the notes at its head).

| task | what it is | where it came from | size |
| ---: | :--- | :--- | :--- |
| 240 | The proof tooling, and the int32 multiply-high bound per divisor proven: `sql/varka/proofs/`, `dev/varka_prove.sh` in the linters' job, compared with task 149's sweep for one nightly cycle, then the sweep's opt-in test cites the proof | item 58 step 1 | small |
| 241 | The long-lane division forms proven at row 166's regions and at 2^52 - 1, the solver asked for the exact bound | item 58 step 2 | small |
| 242 | The other bounded lowerings proven as met: `floorMod7`'s forms, the decomposition's constants over the covered years, the unsigned range compare (row 208); `sql/varka/AGENTS.md` says a new bounded lowering comes with its proof file | item 58 step 3 | small to medium |
| 243 | The specification fragment: one definition per operation the coverage table lowers that the standard fixes, every interpretation choice recorded | item 59 step 1 | medium |
| 244 | Spark's departures from it as named deltas, each with a test showing Spark doing it; the extensions marked as Spark's or ISO 8601's | item 59 step 2 | medium |
| 245 | The proofs restated as refinements of the specification, soundness and guard completeness, in Spark mode and standard mode; `SCOPE_STANDARD_MODE.md` cites the standard-mode theorems | item 59 step 3 | medium |
| 81 | Spark's own date tests as a differential corpus. **Planned** (`PLAN_TASK_81.md`) | item 39 | small; a newcomer's |
| 138 | Three test forms: poisoned null lanes in each coverage row's masked body, both-engines-throw-or-agree for ANSI rows comparing the error class, SQLSTATE and message parameters (`checkError`), not only that both throw, and a ternary-logic partition oracle for every filter row | item 39 | medium |
| 246 | One species per lane type in the shared test JVM: the census, each second-width arm moved to a forked JVM, a guard in the emitter's test base, the full Varka run's time before and after | item 69 | small to medium |
| 247 | What `validityOrFirst` is for: the shape that tells its two values apart joins the oracle, or the option goes with the tests that set it | item 48 | small |
| 262 | Vanilla Spark as the oracle, at random: the composition fuzzer's projections and filters run with Varka on and off over random data with nulls, ANSI on and off, the answers compared; one arm in the nightly; a failure shrunk to its smallest failing subtree and saved, the saved reproducers replayed in PR CI; each fused output forced to decline in turn, the answer unchanged | the plan review (2.2), `READING_MILESTONE_7.md` 1 | medium |
| 271 | Spark's own SQL suites with Varka on, Spark's golden files the oracle: a patch per Spark version or subclassed suites, every exclusion with a reason, and the exclusion list failing when an excluded test starts passing | `READING_MILESTONE_7.md` 2, item 24 | medium |
| 263 | A sanitizer for kernel memory access: under a test-only flag the evaluator records each buffer's address range and real capacity, and `VarkaVectorSupport.ofAddress` fails on a segment outside them; a canary past each buffer, checked when the kernel returns; where each segment came from recorded; every byte back at close; the suites and fuzzers run with it on | the plan review (2.2), item 58, `READING_MILESTONE_7.md` 5 | small to medium |
| 269 | Every small tree: each well-typed IR tree up to a small size through the emitter, compared with the reference evaluator under the fuzzers' null patterns | the plan review (2.2), item 58 | medium |
| 272 | The platform under the kernels: the fuzzers' kernels run under the interpreter, C1, C2 and the Vector API's intrinsics off (`-XX:-EnableVectorSupport`), each in a forked JVM, the answers compared; C2's seeded stress flags on the fastdebug JDK | `READING_MILESTONE_7.md` 8, items 56 and 58 | medium |
| 265 | How well the tests test: branch coverage of the emitter and compiler under the suites and fuzzers, then a bounded mutation run (PIT) on `Slots` and `Analysis` in an idle window; each surviving mutant a test or an equivalence note | the plan review (2.2) | medium, measured |
| 248 | `VarkaEmitOptions` from one table of options: defaults by name, and `canonical()`, the bytes suite's inventory and the fuzzer's draws derived from it; each option's reason recorded - an alternative kept because the winner depends on the machine, a reference form, a fault injector (`misdescribeWordLiveness`), or retired; the suites run under the options' configurations with a reasoned skip list, and a shape marked as declining failing when it starts to fuse. Proof: `emitted_bytes.json` unchanged | item 74.1, `READING_MILESTONE_7.md` 3 | small to medium |
| 249 | One class owns the emitted method names, replacing the prefix matches in main and test code. Proof: `emitted_bytes.json` unchanged | item 74.6 | small |
| 250 | `emitBody` split into driver, loop and epilogue emitters sharing the prologue helpers. Proof: `emitted_bytes.json` unchanged | item 74.3 | medium |
| 251 | `VarkaEvaluatorBase` split into Java components: runner, batch ledger, scratch, warm-up, fallback accounting, dumping. Proof: a before-and-after benchmark of per-batch overhead | item 74.5 | medium |
| 252 | Comments that narrate history rewritten to explain the code as it is | item 74.7 | small, last |
| 214 | Port `VarkaTimeCompiler` to Java. Proof: the coverage and family-chain oracles, and task 191's emission times | item 81 | mechanical; an agent's |
| 215 | Port `VarkaConditionCompiler` to Java. Proof: as 214's | item 81 | mechanical; an agent's |
| 216 | Port `VarkaChronoCompiler` to Java. Proof: as 214's | item 81 | mechanical; an agent's |
| 217 | Port the facade, `VarkaExpressionCompiler`, to Java with a result type, the size admission apart from node compilation, and `VarkaShapeCache.scala` with it. Proof: as 214's | items 81 and 74.4 | medium |
| 224 | Benchmarks and tools in Java: the harness adapter and `VarkaEmitDump` | item 81 | small |
| 267 | The evaluators to Java beside 251's components: `VarkaKernelEvaluator`, `VarkaFilterEvaluator`, `VarkaVectorProjection`, `VarkaFusionReport`, `VarkaExecMetrics`; the exec nodes and the columnar rule stay thin Scala wrappers. Proof: as 251's | the plan review (2.3) | medium |
| 268 | *Optional.* `VarkaEmitterTestBase`'s helpers to Java, the suites thin wrappers over them | the plan review (2.3) | medium |
| 266 | Error Prone and NullAway on Varka's Java packages only; the first step confirms Error Prone on JDK 25's javac with the incubator module | the plan review (2.3) | small to medium |
| 264 | Milestone 4's debt register swept: each entry of `PLAN_MILESTONE_4.md` section 9 still open closed in the past tense, made a row, or moved to `SCOPE_MILESTONE_8.md` | the plan review (2.3) | small |
| 83 | One refusal, instead of four. Proof: `emitted_bytes.json` unchanged | item 39 | medium |
| 86 | One operand admission, stated once | item 39 | medium |
| 253 | The fallback scratch's contract stated in `VarkaFusedKernel`'s doc and enforced by a test | item 68 | small |
| 222 | Structural hashing of IR nodes cached rather than recomputed on every map lookup. Proof: compile time measured before and after | item 81 | small |
| 254 | The grouping weights against the emitted counts: each pinned by a test, or the register retired | item 63 | small to medium |
| 255 | The CI queue off the laptop with no stored credential: the queue on the fork with its own `GITHUB_TOKEN`, the check sync on the base repository with its own; master's head run after each merge; a PR's new tests rerun several times, and a fix's new test required to fail on the base | item 76, redesigned (2.4), `READING_MILESTONE_7.md` 9 | medium |
| 256 | Regenerate one benchmark section, not the whole file, and run it paired against the PR's merge base in alternating batches with a threshold | item 72, `READING_MILESTONE_7.md` 9 | small to medium |
| 257 | A kernel after other kernels: the slow mode's frequency as a function of N, one arm in the nightly | item 62 | medium |
| 180 | Promotion, continuously | item 81 | a cadence |
| 258 | A post for Spark users: how Spark compiles a query, and why it compiles it again | item 78 | medium |
| 259 | Its companion for Spark and JVM developers: bytecode without source | item 79 | medium |
| 260 | A reference post: Spark's code generator by the numbers | item 80 | small |
| 270 | The milestone's own post, for JVM and database engineers: how Varka knows its answers are right, and where that stops | the plan review (2.5), item 58 | medium |
| 261 | The closing task: 1.1 read row by row, and the README's account of how Varka is checked rewritten | this plan | small |

## 4. Ordering

| wave | tasks | why they wait |
| ---: | :--- | :--- |
| 0 | 240, 246, 248, 249, 255, 256, 81, 263, 266 | 240 is the proofs' tooling; 246 makes later suites' verdicts trustworthy; 248 and 249 are what every later refactor touches; 255 and 256 end the laptop queue and the hand splicing for every PR below; 263 and 266 check every port and refactor below as it lands; 81 needs nothing |
| 1 | 241, 243, 250, 251, 214, 215, 216, 138, 247, 222, 253, 262, 269, 271, 264 | after the tooling and the two cheap refactors; the three family ports run in parallel, by an agent; 262 and 269 need only the fuzzers' grammar, and 271 only Spark's suites; 264 is decisions, not code |
| 2 | 242, 244, 217, 224, 83, 254, 257, 267, 272, 265 | 217 after the families it fronts; 83 after 250, which rewrites the same paths; 244 after 243 defines what a delta departs from; 267 after 251's components; 272 after 246, so that a configuration's verdict is not a second species' boxing; 265 after 262, 263 and 269, so that it measures the tests the milestone leaves |
| 3 | 245, 86, 252, 268 | 86 after the ports, so the admission is written once in Java; 252 once the structure has settled; 268, optional, once the ports and refactors have settled the helpers' callers |
| 4 | 261, 270 | 261 last by definition; 270's post written from what 261 records |

Tasks 180 and 258 to 260 run across the waves.

At the halfway point the milestone is reviewed against 1.1, not against this table, and the
review is recorded as section 9 with an ordering for what remains, as milestone 6's was
(`PLAN_MILESTONE_6.md` 9); the waves above stay as the record. That review is also where
anything rows 262, 263 and 269 find is weighed against the milestone's scope (section 6).

## 5. Verification

* Every structural PR cites the proof its row names: `emitted_bytes.json` unchanged for the
  emitter's refactors (248, 249, 250, 83); the coverage and family-chain oracles and task 191's
  emission times for the ports (214 to 217); a before-and-after benchmark of per-batch overhead
  for the evaluator (251, 267); compile time before and after for 222. The Varka suites pass,
  with 263's sanitizer on.
* Task 265 commits its coverage report and its mutation run's survivors, each with the test that
  now kills it or the note that it changes no behaviour.
* `dev/varka_prove.sh` fails on any `sat` and on a missing solver, and runs in the linters' job
  in under a minute.
* Task 246 commits the full Varka run's time before and after.
* Task 271's exclusion list gives a reason for every entry, and fails when an excluded test
  starts passing; task 272 records which configurations each kernel ran under.
* `dev/varka_quote_check.py` at zero orphans and `dev/varka_toc.py --check` clean; the linters
  run locally before every push.

## 6. Risks

1. **Nothing here is visible to a user.** Accepted, as milestone 6 accepted its foundation.
   The posts carry promotion, and 1.1 item 1 gives the README a sentence it cannot say today.
2. **An SMT encoding can be wrong the same way the lowering is.** That is why 240 runs beside
   task 149's sweep for a cycle before the sweep cites it, and why each proof file names the
   Java it encodes.
3. **The specification can sprawl.** Item 59 bounds it to the operations the coverage table
   lowers, not the semantics of queries; 245 is the row to cut if 243 and 244 run long.
4. **The refactors collide with each other and with milestone 6's last rows.** One PR per item,
   in the waves above; 236's planner owns the size loop, and 250 waits for 239's emitter
   changes to merge.
5. **An agent's port drifts from the Scala it replaces.** Each port is gated by the
   family-chain, coverage and bytes oracles, as 175 was.
6. **The new tests will find disagreements.** A differential against Spark itself (262),
   Spark's own suites (271), every small tree (269), a sanitizer (263) and the kernels under
   several JIT configurations (272) are built to find what the record has not; each
   finding is fixed, or becomes a named delta (244) or a row, and that can grow the milestone.
   The halfway review (section 4) is where the growth is weighed against 1.1.
7. **Measuring how well the tests test costs machine time.** A mutation run multiplies a
   suite's time by its mutants; 265 bounds it to `Slots` and `Analysis`, runs it in an idle
   window, and takes the cheaper branch coverage first.

## 7. Open questions

1. **Z3 or cvc5 in CI?** SMT-LIB is neutral; 240 chooses by install cost in the lint image.
   Neither is on the laptop today.
2. **Does 254 retune the weights or retire them?** Item 63 leaves it to the task; task 236's
   planner reads the same costs, so 254 takes its answer from 236's build.
3. **Where 255's queue workflow lives on the fork.** A scheduled workflow runs from the default
   branch, and the fork's `master` follows `apache/spark`'s. Carrying one extra file there is
   the simplest answer; the task weighs it against another default branch for the fork.

## 8. Explicitly out of milestone 7

* The coverage spine and everything else in `SCOPE_MILESTONE_8.md` not named above - decimals,
  aggregation, strings, the plugin (item 53), the configuration surface (item 64), the fallback
  metrics (item 65).
* Item 47, one place per node: it waits for a second lane type or representation to design
  against.
* Item 51, a mapping type per IR node: it lands with the first operator that is not
  element-wise.
* Item 36, the math family's ULP contract: a decision for a family Varka does not lower yet.
* Porting the exec nodes and the columnar rule to Java: they stay thin Scala wrappers, since
  `SparkPlan` case classes are the interop surface the owner's Java rule exempts (2.3).
* Row 218 (optional JIT research), rows 207, 208 and 213 (range-set and warm-up tuning), row
  231 (the fallback's row loops), and milestone 5's row 74 (a validity-pass optimisation).
* Verifying the emitter itself, Java-level deductive tools, and a formal semantics of Spark's
  dates, for item 58's reasons.
