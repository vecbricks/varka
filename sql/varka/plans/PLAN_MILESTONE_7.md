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
  engines throw on an ANSI row (row 138). The shared test JVM runs a second vector species,
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
2. **The differential tests reach what they miss today**: Spark's own date tests, poisoned null
   lanes, the ANSI throw-or-agree form and the partition oracle (81, 138). No Varka suite runs
   a second species of a lane type in the shared test JVM (246), and every emit option moves
   some hash in the bytes oracle, or is gone (247).
3. **The compiler is Java and legible**: the ports done (214 to 217, 224); item 74's refactors
   landed, each with `emitted_bytes.json` unchanged (248 to 252); the runtime refusals and the
   operand admission each stated once (83, 86).
4. **The infrastructure stops costing manual work**: CI reaches a verdict with no process on a
   laptop (255), and a benchmark run regenerates only its own sections (256). The slow mode a
   kernel meets after other kernels is a number (257).
5. Beside the spine: promotion continues (180), and the three posts the owner chose on
   2 October are published (258 to 260).

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

### 2.2 Differential tests and the test JVM (81, 138, 246, 247)

Row 81 is planned (`PLAN_TASK_81.md`) and suits a newcomer. Row 138's three forms all run in
`VarkaCoverageDifferentialSuite`. Task 246 comes early: until the shared JVM runs one species
per lane type, a test whose verdict is a JIT outcome can fail by suite order, and every later
task reads those suites. Task 247 is small: `validityOrFirst` is the one option that moves no
hash anywhere, so either the shape it guards joins the oracle or the option goes.

### 2.3 Structure (248 to 254, 214 to 217, 224, 83, 86, 222)

None of these moves an emitted byte, and `emitted_bytes.json` is the proof each PR cites.

The options table (248) and the method-name class (249) go first, because every later
refactor touches options or method names. The emitter splits (250, `emitBody`) and the
evaluator split (251) follow. Row 83's single refusal goes after 250, since both rewrite the
emitter's refusal paths. Row 86's single operand admission goes after the ports, so that the
admission is written once in Java and not ported twice. The ports themselves (214 to 217, 224)
are mechanical and meant for an agent, gated by the family-chain, coverage and bytes oracles as
task 175 was. Row 217 is item 74.4. Item 74.2, the size loop, is task 236's and is not repeated
here. The comments pass (252) runs last, once the structure has settled.

Task 253 states and enforces the seven-argument `run`'s scratch contract (item 68). Row 222
caches each IR node's hash. Task 254 (item 63) either pins each grouping weight to the count
its node emits alone, or retires the hand-written register so the grouping reads the class.

### 2.4 Infrastructure (255 to 257)

Task 255 moves the CI queue into a workflow on `vecbricks/varka`, as `PLAN_TASK_227.md` 3 lays
it out. It needs the owner's decision on a fine-grained token on the fork; without that it does
not start. Task 256 lets a benchmark run regenerate only the sections a PR adds or changes. Task
257 is item 62's probe: the slow mode a kernel meets after N other kernels, as a function of N,
with one arm in the nightly.

### 2.5 Promotion (180, 258 to 260)

Task 180 is the cadence carried from milestone 6. Tasks 258 and 259 are the pair of posts in
items 78 and 79, 258 first; 260, the reference post of item 80, comes between them and the next
long post, as the catalogue orders them.

### 2.6 The closing task (261)

1.1 read row by row, the scoring of each row's predictions collected, and the README's account
of how Varka is checked rewritten on what the proofs and tests now hold, quoting nothing that
does not trace to a committed file.

## 3. Task breakdown

Task numbers continue the single sequence from 240; rows that moved to the catalogue from
milestones 5 and 6 keep theirs.

| task | what it is | where it came from | size |
| ---: | :--- | :--- | :--- |
| 240 | The proof tooling, and the int32 multiply-high bound per divisor proven: `sql/varka/proofs/`, `dev/varka_prove.sh` in the linters' job, compared with task 149's sweep for one nightly cycle, then the sweep's opt-in test cites the proof | item 58 step 1 | small |
| 241 | The long-lane division forms proven at row 166's regions and at 2^52 - 1, the solver asked for the exact bound | item 58 step 2 | small |
| 242 | The other bounded lowerings proven as met: `floorMod7`'s forms, the decomposition's constants over the covered years, the unsigned range compare (row 208); `sql/varka/AGENTS.md` says a new bounded lowering comes with its proof file | item 58 step 3 | small to medium |
| 243 | The specification fragment: one definition per operation the coverage table lowers that the standard fixes, every interpretation choice recorded | item 59 step 1 | medium |
| 244 | Spark's departures from it as named deltas, each with a test showing Spark doing it; the extensions marked as Spark's or ISO 8601's | item 59 step 2 | medium |
| 245 | The proofs restated as refinements of the specification, soundness and guard completeness, in Spark mode and standard mode; `SCOPE_STANDARD_MODE.md` cites the standard-mode theorems | item 59 step 3 | medium |
| 81 | Spark's own date tests as a differential corpus. **Planned** (`PLAN_TASK_81.md`) | item 39 | small; a newcomer's |
| 138 | Three test forms: poisoned null lanes in each coverage row's masked body, both-engines-throw-or-agree for ANSI rows, a ternary-logic partition oracle for every filter row | item 39 | medium |
| 246 | One species per lane type in the shared test JVM: the census, each second-width arm moved to a forked JVM, a guard in the emitter's test base, the full Varka run's time before and after | item 69 | small to medium |
| 247 | What `validityOrFirst` is for: the shape that tells its two values apart joins the oracle, or the option goes with the tests that set it | item 48 | small |
| 248 | `VarkaEmitOptions` from one table of options: defaults by name, and `canonical()`, the bytes suite's inventory and the fuzzer's draws derived from it | item 74.1 | small |
| 249 | One class owns the emitted method names, replacing the prefix matches in main and test code | item 74.6 | small |
| 250 | `emitBody` split into driver, loop and epilogue emitters sharing the prologue helpers | item 74.3 | medium |
| 251 | `VarkaEvaluatorBase` split into components: runner, batch ledger, scratch, warm-up, fallback accounting, dumping | item 74.5 | medium |
| 252 | Comments that narrate history rewritten to explain the code as it is | item 74.7 | small, last |
| 214 | Port `VarkaTimeCompiler` to Java | item 81 | mechanical; an agent's |
| 215 | Port `VarkaConditionCompiler` to Java | item 81 | mechanical; an agent's |
| 216 | Port `VarkaChronoCompiler` to Java | item 81 | mechanical; an agent's |
| 217 | Port the facade, `VarkaExpressionCompiler`, to Java with a result type, the size admission apart from node compilation | items 81 and 74.4 | medium |
| 224 | Benchmarks and tools in Java: the harness adapter and `VarkaEmitDump` | item 81 | small |
| 83 | One refusal, instead of four | item 39 | medium |
| 86 | One operand admission, stated once | item 39 | medium |
| 253 | The fallback scratch's contract stated in `VarkaFusedKernel`'s doc and enforced by a test | item 68 | small |
| 222 | Structural hashing of IR nodes cached rather than recomputed on every map lookup | item 81 | small |
| 254 | The grouping weights against the emitted counts: each pinned by a test, or the register retired | item 63 | small to medium |
| 255 | The CI queue as a workflow on the base repository. **Waits on the owner's token** | item 76 | medium |
| 256 | Regenerate one benchmark section, not the whole file | item 72 | small |
| 257 | A kernel after other kernels: the slow mode's frequency as a function of N, one arm in the nightly | item 62 | medium |
| 180 | Promotion, continuously | item 81 | a cadence |
| 258 | A post for Spark users: how Spark compiles a query, and why it compiles it again | item 78 | medium |
| 259 | Its companion for Spark and JVM developers: bytecode without source | item 79 | medium |
| 260 | A reference post: Spark's code generator by the numbers | item 80 | small |
| 261 | The closing task: 1.1 read row by row, and the README's account of how Varka is checked rewritten | this plan | small |

## 4. Ordering

| wave | tasks | why they wait |
| ---: | :--- | :--- |
| 0 | 240, 246, 248, 249, 256, 81 | 240 is the proofs' tooling; 246 makes later suites' verdicts trustworthy; 248 and 249 are what every later refactor touches; 256 ends hand splicing for every benchmark below; 81 needs nothing |
| 1 | 241, 243, 250, 251, 214, 215, 216, 138, 247, 222, 253 | after the tooling and the two cheap refactors; the three family ports run in parallel, by an agent |
| 2 | 242, 244, 217, 224, 83, 254, 257 | 217 after the families it fronts; 83 after 250, which rewrites the same paths; 244 after 243 defines what a delta departs from |
| 3 | 245, 86, 252, 255 | 86 after the ports, so the admission is written once in Java; 252 once the structure has settled; 255 when the token exists |
| 4 | 261 | last by definition |

Tasks 180 and 258 to 260 run across the waves.

## 5. Verification

* Every structural PR states that `emitted_bytes.json` is unchanged, and the Varka suites pass.
* `dev/varka_prove.sh` fails on any `sat` and on a missing solver, and runs in the linters' job
  in under a minute.
* Task 246 commits the full Varka run's time before and after.
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

## 7. Open questions

1. **Z3 or cvc5 in CI?** SMT-LIB is neutral; 240 chooses by install cost in the lint image.
   Neither is on the laptop today.
2. **Does 254 retune the weights or retire them?** Item 63 leaves it to the task; task 236's
   planner reads the same costs, so 254 takes its answer from 236's build.
3. **The token for 255** is the owner's decision.

## 8. Explicitly out of milestone 7

* The coverage spine and everything else in `SCOPE_MILESTONE_8.md` not named above - decimals,
  aggregation, strings, the plugin (item 53), the configuration surface (item 64), the fallback
  metrics (item 65).
* Item 47, one place per node: it waits for a second lane type or representation to design
  against.
* Item 51, a mapping type per IR node: it lands with the first operator that is not
  element-wise.
* Item 36, the math family's ULP contract: a decision for a family Varka does not lower yet.
* Row 218 (optional JIT research), rows 207, 208 and 213 (range-set and warm-up tuning), row
  231 (the fallback's row loops), and milestone 5's row 74 (a validity-pass optimisation).
* Verifying the emitter itself, Java-level deductive tools, and a formal semantics of Spark's
  dates, for item 58's reasons.
