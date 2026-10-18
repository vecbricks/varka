# Varka Milestone 7 Plan: trust and maintainability

*Opened 2 October 2026, while milestone 6 closes. Offered four directions drawn from the scope
catalogue - coverage and a first query-level number, no case handed back to Spark's code
generator, a plugin others can install, and trust and maintainability - the owner chose the
last.*

This is a task plan, not a scope catalogue. Under the house rule (`sql/varka/AGENTS.md`), the
catalogue moved forward the same day: `SCOPE_MILESTONE_7.md` is now `m9/SCOPE.md`,
with its item numbers unchanged, and the coverage spine it argues for (decimal lanes, aggregate
wiring, grouped aggregation, string keys, TPC-H q6) was milestone 8's. Nothing in it is withdrawn.
The items this milestone takes are named below with the item number they have there, and the
rows that moved there from milestones 5 and 6 keep their task numbers.

*2 October 2026, before this plan merged: a review of it against the record added rows 262 to
270, gave each structural row the proof that fits it (section 5), and changed rows 138, 217,
248, 251 and 255; section 2 says why each.*

*2 October 2026, later the same day: a survey of how thirteen engines keep their answers right
(`m7/READING.md`) added rows 271 and 272 and refined rows 248, 255, 256, 262 and 263.*

*3 October 2026: the fifteen papers that survey listed, read in full (`m7/READING.md`
11), added rows 273 to 281 and refined rows 81, 138, 240 to 245, 248, 252, 257, 262, 265, 266, 269
and 272. Row 273 is a bug the reading found, reproduced the same day.*

*9 October 2026: a read of Polars (`m7/READING_POLARS.md`) added row 301 and refined row 252; the
rest of what it found is `m9/SCOPE.md` item 89.*

*15 October 2026: the reading of the warm-up flake's cause (`VARKA-295.md` 9.6, in pull request #825 until it merges) added rows 370, a
check of what a running JVM allows, and 371, a post on the cause.*

## 1. The question, and what "done" means

Six milestones built vocabulary and then a foundation under it, and each was checked as it
went: the coverage oracle, the sweeps against `java.time`, the fuzzers against the reference
evaluator, the bytes oracle. What the record now shows is where that checking stops and where
the code has grown faster than its structure.

* **Proof stops at 32 bits.** Every int32 lowering is proven by exhaustion, thirteen and a half
  minutes of each nightly. The long lane cannot be exhausted, so its divisions rest on a Python
  model and sampled regions (`m9/SCOPE.md` item 58).
* **The differential tests have known gaps.** Spark's own date tests have never been run
  against Varka (row 81). No test poisons the null lanes of a masked body or checks that both
  engines throw on an ANSI row (row 138). The fuzzers compare kernels only with Varka's own
  reference evaluator, never with Spark (2.2). The shared test JVM runs a second vector species,
  which can leave later kernels boxed and made one suite fail in about one full run in three
  (item 69).
* **The tests can pass over a wrong kernel.** The fuzzers check interpreted kernels only, a kernel
  that throws is hidden by the ghost fallback, output validity is pre-filled only with ones, and a
  filter's declined batch is refiltered in an order Spark does not use (2.2).
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
   with Spark's departures named (VARKA-240 to VARKA-245), the analyses that supply the guards' bounds
   proved as well (281).
2. **The tests reach what they miss today**: Spark's own date tests, poisoned null lanes, both
   fills of output validity, the ANSI form comparing error classes, and the partition oracle with
   and without Spark's rewrites (81, 138); random compositions compared with vanilla Spark itself
   (262), Spark's own SQL suites run with Varka on (271), and every small IR tree through the
   emitter (269), each failure shrunk to a minimal case (277) and the passing shapes varied in ways
   that must not change an answer (278); kernels checked compiled by C2 as well as interpreted
   (274), under the interpreter, C1, C2 and the Vector API's intrinsics off (272), every segment
   they map checked against its buffers (263), a kernel failure the ghost fallback hides failing
   its test (275), and every decline the one the row's verdict calls for (279); and the reference
   evaluator held to Spark's own interpreter (276). The filter-order bug the reading found is
   fixed (273). No Varka suite runs a second species of a lane type in the shared test JVM (246),
   and every emit option states why it exists and moves some hash in the bytes oracle, or is gone
   (247, 248). Every shape the tests run has both its dense and its masked body compared (284).
   What the tests miss is measured, by what the emitter reached, branch coverage of the emitter
   and of the code it emits, and a bounded mutation run (280, 285, 265). The warm-up's flake, which
   fails the module's job a few times in a hundred warm-ups, has its cause named and the part of it
   Varka can remove removed, or the part it cannot recorded with what an operator can set (295,
   370).
3. **Varka's code is Java and legible**: the compiler ported with its shape cache (214 to 217,
   224) and the evaluators with it (251, 267), checked by Error Prone and NullAway (266); item
   74's refactors landed, each with the proof its row names (248 to 252); the runtime refusals
   and the operand admission each stated once (83, 86); and milestone 4's debt register swept
   (264).
4. **The infrastructure stops costing manual work**: CI reaches a verdict with no process on a
   laptop and runs master after each merge (255), and a benchmark run regenerates only its own
   sections (256). The slow mode a kernel meets after other kernels is a number (257).
5. Beside the spine: promotion continues (180), the three posts the owner chose on 2 October
   are published (258 to 260), a short post on the JIT's trap sums follows the warm-up flake's
   fix (371), and the milestone closes on a post of its own (270).

## 2. Design

Each row's design is in the item it comes from; this section says what the milestone adds to
it and how the rows meet.

### 2.1 Proofs (VARKA-240 to VARKA-245)

Item 58 is the tooling and the proofs, in its order: SMT-LIB files under `sql/varka/proofs/`,
one per lowering with the Java it encodes named in its header, run by `dev/varka_prove.sh`,
which fails on any `sat` and on a missing solver. The int32 multiply-high bound comes first,
because VARKA-149's sweep checks the same statement and the two are compared for one nightly
cycle: the SMT encoding is a second implementation of the lowering, so agreement between them
is worth more than either alone. Then the long-lane division forms, where no sweep reaches, with
the solver asked for the exact bound rather than handed one. Then the rest as met.

Item 59 gives the proofs a specification better than "the Java formula": the standard's
definitions for the operations the coverage table lowers, Spark's departures as named deltas
(`clamp_to_month_end`, `overflow_yields_null`, `proleptic_range`), each with a test showing
Spark doing it, and the proofs restated as refinements in Spark mode and in standard mode. It is
taken in full; VARKA-245 is where it can be cut if 243 and 244 run long, since the proofs of 240
to 242 stand without it.

**What the papers add to the proofs** (`m7/READING.md` 11). The prover's verdicts are
checked in turn: `dev/varka_prove.sh` fails on `unknown` and on a timeout as well as on `sat`;
each proof file carries queries that must come back `sat` - its precondition is satisfiable, its
boundary inputs are admitted, a constant changed by one is refuted - and Z3 and cvc5, pinned, must
agree in the nightly, since Alive2's authors found six soundness bugs in Z3. Java's operators are
stated once, in a prelude (`sql/varka/proofs/java.smt2`): masked shifts, `%`, `floorMod`,
`Integer.MIN_VALUE / -1`, `L2I` and the saturating `D2I` and `D2L` are not the SMT operators of the
same names. Each proof's constants are rendered from the code (`VarkaChrono`, `signedMagic`,
`EXACT_DIVIDEND_BOUND`), and `signedMagic` asserts Granlund and Montgomery's Theorem 5.1
inequality for its divisor, which proves the multiply-high exact over all of int32 for every
divisor up to 2^17, where VARKA-149's sweep covers nine. Row 241's two forms divide in doubles, so
its proofs need floating-point theory: it is sized medium, and starts with a spike that records
the solve time against the one-minute budget, the proofs moving to the nightly if they do not fit.
Row 242 records each calendar division site's range and theorem beside it, labels every quoted
bound sufficient or exact (`YEAR_M`'s 44858 first fails at 44894), and pins the first failing
input past each bound as a test; it names the leap hash's unsigned compare, the one built, rather
than row 208's, which section 8 leaves out. Row 243 says per operation whether a division
truncates or floors, and row 245 states each refinement as five queries per mode over (data,
valid) pairs, data free under a null bit: a satisfiable precondition, a decline wherever Spark
raises, null-ness equal, values equal on valid lanes, and a decline only where Spark raises or the
input leaves the bound.

**The analyses that supply the preconditions (281).** Alive trusts its dataflow predicates
(pp. 2-4), and one of its eight bugs hinged on an overflow predicate. Varka's are
`VarkaRangeAnalysis` and `VarkaValueRange`, which decide when a calendar node fuses without a guard
and when an overflow check comes off, and `VarkaTimeCompiler`'s quotient bounds. Row 281 states an
obligation for each of their rules over symbolic interval ends and Java's wrapping arithmetic,
checks the calendar rules by exhaustion, and proves a 64-bit rule at a reduced width if it times
out, as Alive did; row 264's debt on the analysis's loose `INT` answers is paid only with these
obligations passing.

### 2.2 The tests, and how well they test (81, 138, 246, 247, 262 to 265, 269, 271 to 280, 284, 285,
289, 292, 323, 324)

Row 81 is planned (`VARKA-81.md`) and suits a newcomer. Row 138's three forms all run in
`VarkaCoverageDifferentialSuite`, and its ANSI form compares the error class, the SQLSTATE and
the message parameters, as Spark's own `checkError` does, rather than only that both engines
throw: a user's error handling reads those. VARKA-246 comes early: until the shared JVM runs one
species per lane type, a test whose verdict is a JIT outcome can fail by suite order, and every
later task reads those suites. VARKA-247 is small: `validityOrFirst` is the one option that
moves no hash anywhere, so either the shape it guards joins the oracle or the option goes;
248's table makes the same question answerable for every option.

**Vanilla Spark as the oracle, at random (262).** The contract is Spark's answer, and no
randomized test holds Varka to it. Both fuzzers compare kernels with Varka's own reference
evaluator: `VarkaIrFuzzSuite` does, and `VarkaCoverageCompositionFuzzSuite`'s one answer check,
in its wide test, is `VarkaKernelCheck` against the same evaluator. Spark is the oracle only
for fixed shapes: `VarkaDifferentialSuite`'s 89 hand-chosen tests, and each row of
`VarkaCoverageDifferentialSuite` alone. VARKA-262 runs the composition fuzzer's random
projections and filters through a Spark session with Varka on and off, over random data with
nulls, with ANSI mode on and off, and compares the answers; a disagreement is a bug or a named
delta (244). One arm joins the nightly. Three things the other engines have come with it
(`m7/READING.md` 1): a failing composition is shrunk to its smallest failing subtree, as
Velox's expression runner does, where the record has only ever minimized by hand; it is saved,
and the saved reproducers replay in PR CI, as Arrow's fuzzers' do; and each fused output is
forced to decline in turn, the answer unchanged and the comparison counted only where the plan
changed, as Comet's fallback-invariance suite does.

**Spark's own suites with Varka on (271).** Comet patches a Spark checkout per Spark version so
that Spark's SQL suites run with Comet loaded, Spark's golden `.sql.out` files the oracle and
every excluded test given a reason; Gluten subclasses about 480 of Spark's suites the same way,
and its Delta gate fails when an excluded test starts passing, so the list cannot go stale
(`m7/READING.md` 2). The record called this the widest differential available on
16 September 2026 (`m9/SCOPE.md` item 24) and never made it a row. Row 81 harvests the
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
other engines (`m7/READING.md` 5): a canary past each buffer, checked when the kernel
returns, as Arrow's debug memory pool writes one; a record of where each segment came from, as
Druid's poisoned buffer pool keeps; and every byte back at close, as Trino asserts per test
class. The check is against each buffer's real capacity, not its nominal size: Arrow only
recommends padding, and its Java IPC reader slices a buffer to its exact length, so the
whole-word validity writes are safe only on the buffers Varka allocates itself.

**What the sanitizer found (292).** *Added 7 October 2026.* Row 263's first runs under the
sanitizer passed everything but six TIME tests, and all six fail the same way: the emitter sizes
every data segment as `length * lane.byteStride`, the lane's width, and a `NarrowLane` root
(`hour`, `minute` and `second` of a TIME, whose long-lane kernel stores an int32) is stored at
`i * 4` into a segment mapped at twice that. Nothing is wrong with an answer; the kernel's
entitlement is twice its buffer, so a bad store in the upper half would raise nothing. No canary
was overwritten and no other mapping left its buffer in 958 passing tests. Row 292 maps such an
output at its own width, which moves `emitted_bytes.json` for the narrowed shapes, and so was its
own task, and row 263's last step, running the suites with the sanitizer on, waits for it. *Done
7 October 2026 (`VARKA-292.md`): the suites pass with the sanitizer on, and that step is open.*
*Row 263 is done the same day: the sanitizer is on in the suites.*

**The platform under the kernels (272).** Item 58 called HotSpot the trusted base that no proof
reaches, and JDK 25 carries a known C2 miscompilation of masked stores (JDK-8388492, item 56).
OpenJDK's own `VectorizationTestRunner` gets the interpreter's answer through WhiteBox and
compares it with C2's, and the Vector API's plain-Java fallback, which runs with
`-XX:-EnableVectorSupport`, is a second implementation of every intrinsic, sharing only the
checks made before the dispatch (`m7/READING.md` 8). Row 272 runs the fuzzers' kernels
under the interpreter, C1, C2 and the intrinsics off, each in a forked JVM, and compares the
answers. C2's seeded stress flags (`StressIGVN`, `StressGCM`, `StressLCM`, `RepeatCompilation`)
are product diagnostic flags in JDK 25, so they run nightly on the shipped JDK, only
`DeoptimizeALot` needing the fastdebug build. Each arm proves through JFR's compilation events
which tier served the compared call, an `-Xcomp` arm compiles everything first, a crashed fork keeps
its `hs_err` and replay files with its seeds, and after row 246 a deliberate second species is one
more arm. They are the levers row 257 lacks, and 257's forks compare answers as well as rates.

**Every small tree (269).** Random draws miss corners. Every well-typed IR tree up to a small
size goes through the emitter and is compared with the reference evaluator, under the null
patterns the fuzzers use. Item 58 called the validity and guard word algebra the one finite
piece of the emitter a model could cover; enumerating small trees covers it without a model.

**How well the tests test (265).** Nothing in the record measures it: no branch coverage, no
mutation testing. The first step is branch coverage of the emitter and the compiler under the
suites and the fuzzers, which names the arms nothing runs; the second is a bounded mutation run
(PIT) on `Slots` and `Analysis`, in an idle machine window. A mutant that survives becomes a
test, or a note that it changes no behaviour. It comes after 262, 263, 269 and 280, so that it
measures the tests this milestone leaves. PIT first reaches the ScalaTest suites test by test,
with `NON_VOID_METHOD_CALLS`, `REMOVE_CONDITIONALS` and the bitwise mutators beside its defaults,
and each mutant gets one of three verdicts - killed by a behavioural test, killed only by the
emitted-bytes oracle, survived - the second read as a survivor, since the bytes oracle kills
equivalent mutants too.

**Coverage of the code the emitter emits (284, 285).** Row 265 measures the emitter; nothing
measures the classes it emits. A spike on 4 October 2026 ran the Varka suites of the gate's wide
step under the JaCoCo agent: an emitted class is defined by an ordinary class loader, so the
agent instruments it like any other once `inclnolocationclasses` is on, and the suites' time did
not move (841 s against 837 s). Each class's `LineNumberTable` already maps its lines to IR
nodes, so its coverage sums per operation as well as per method. Of 13,461 classes emitted, 1,555
ran; about 730 of those ran only their masked body and about 135 only their dense one, and 3,651
of 5,596 dense loop methods were never entered. A shape is usually run once, on one null pattern,
so only one of its two bodies is ever compared, and the bytes oracle pins both bodies' bytes, not
their answers. Row 284 runs each executed shape on both kinds of batch; row 285 makes the
measurement a script whose report rows 265 and 284 read.

**The wide compositions' long lane (289).** Found 6 October 2026 by a fuzz campaign in an idle
window. `VarkaCoverageCompositionFuzzSuite`'s wide test compares a kernel row by row only on the
int lane (`checkKernel` returns before emitting anything else), so no long-lane kernel of a wide
projection is ever compared, under any seed. The test's adequacy assertion assumes 20 projections
always reach an int-lane kernel, and seed 2026100605003 draws 20 that are all long-lane: 0
kernels compared, a failure that reproduces on every run. By the campaign's end it was 4 of 162
seeds (2026100605003, ...05049, ...05103, ...05125). The same campaign's IR fuzzer, 92 JVMs and
about 9.2 million trees with no disagreement, failed once on the same kind of check:
`VarkaIrFuzzSuite`'s wide test asserts that its 10 compositions reach every size mechanism, and
seed 2026100602011 drew none that halves a group on bytes. Row 289 compares the long lane's
kernels too, against the reference evaluator with long inputs drawn within each input's bound,
and counts the comparisons per lane; and it makes the IR fuzzer's reach assertion hold by
construction too, by drawing until each mechanism is reached or by a fixed composition per
mechanism beside the random ones, rather than by the luck of 10 draws.

**What the papers found the tests can pass over** (`m7/READING.md` 11).

* **A filter's order (273).** Spark evaluates a filter's conjuncts in order and stops at the
  first false one. Varka fuses the conjuncts it compiles below a row filter that holds the rest,
  and a batch its kernel declines is refiltered with the fused conjuncts alone (the fallback
  predicate of `VarkaFilterExec` and `VarkaFilterColumnarToRowExec`), so a fused conjunct that can
  raise under ANSI runs on rows a residual conjunct before it would have stopped; the split's own
  comment considers nondeterminism and nothing else. **Reproduced** on 3 October 2026 by a test
  in `VarkaDifferentialSuite`: under ANSI, `WHERE s = 'x' AND make_date(2021, i, 1) < d` over a
  row whose `s` is not 'x' and whose month is 13 returns Spark's rows on the row engine and fails
  on Varka with `DATETIME_FIELD_OUT_OF_BOUNDS`, thrown from the filter's fallback. The fix gives
  the fallback the original condition, every conjunct in its order: the kernel only ever
  declines, so every batch with a row that would raise is filtered in Spark's order.
* **Interpreted code (274).** `VarkaKernelCheck` runs a fresh class once per batch of at most
  1,000 rows, and the test sessions turn the warm-up off, so every fuzzer verdict comes from the
  interpreter, which runs the Vector API's plain-Java fallback; the C2 vector code users run is
  checked on three shapes (`VarkaWarmupEndToEndSuite`). Row 274 warms each kernel to C2 before the
  compared batches, JFR's events proving which tier served each call, and runs about eight batch
  histories per kernel - calls before the compared batch, an OSR mid-batch, a trap after a warm-up
  that never took the guard, null or remainder branch - since 89.6% of Artemis's disagreements
  needed choices between all-interpreted and all-compiled (Table 4, p. 10).
* **The ghost fallback (275).** A kernel that throws gets a warning, a metric and a JFR event,
  and its batch reruns on the row path (`VarkaFallbackAccounting.kernelFailure`), so a `sql/core`
  test with right answers passes over a broken kernel. Row 275 fails a test on any kernel-failure
  fallback it did not expect, as Spark's own suites turn the codegen fallback off.
* **A validity bit never set (138).** The harnesses pre-fill output validity with ones, which
  catches a kernel that fails to clear a bit and not one that fails to set it; row 138 runs each
  batch with the zero fill too.
* **The partition oracle, optimized away (138).** Spark rewrites `NOT (a < b)` into `a >= b`
  (`BooleanSimplification`) and `(a < b) IS NULL` into null checks on the columns
  (`NullDownPropagation`) before Varka sees the filter, and `InferFiltersFromConstraints` adds
  `isnotnull` conjuncts that settle every null row first. Row 138 compares the three partitions on
  row ids and values with the unfiltered table, under the default optimizer and with those rules
  excluded, and its ANSI form covers each position Spark skips: after a residual conjunct, the
  right side of AND and OR, the untaken arm of CASE and IF, later COALESCE arguments.

**The reference evaluator, held to Spark (276).** `VarkaReferenceEvaluator` is the oracle of both
fuzzers and of row 269, and nothing holds it to Spark; PQS tests its interpreter against the
engine (pp. 12-13). Row 276 runs every coverage row's IR and every composed kernel three ways on
the same inputs - the kernel, the reference evaluator and Catalyst's `eval` on the Spark
expression - with no session: a kernel that disagrees with both is a Varka bug, and a reference
that disagrees with Spark is fixed or becomes a named delta (244).

**One shrinker (277).** ddmin's result is 1-minimal by construction, and a single culprit costs
about 2 log2 n tests (Zeller and Hildebrandt, Props. 11 and 13). Row 277 builds `VarkaShrinker`
in catalyst's test sources, used by rows 262, 269 and 248: an IR tree shrunk roots first and then
level by level, every candidate kept well typed by an admissibility predicate shared with row
269's enumerator; then the emit options' delta from the defaults; then the batch - its length,
null pattern and rows - under a failure signature that keeps the shrink on the same bug. Failures
are grouped by that signature, a committed list names each known one with its row or delta, and
an entry no nightly run matches fails.

**Variants that must agree (278).** EMI varies a program where its input does not reach and
requires the same output (Le, Afshari and Su). Row 278 makes about eight variants of each passing
IR shape - its outputs permuted, a dead output added that reuses a live root's date, an arm the
reference evaluator never selected replaced, a literal swapped for a column holding the same
constant - each checked against the reference evaluator.

**Declines as specified behaviour (279).** The fuzzers avoid firing guards because the reference
evaluator has no notion of a declined batch. Row 279 gives each row a verdict - an answer, a
decline that must happen, one that may (a guard not qualified by its arm, on an arm the row does
not take), an input outside the contract - and checks the kernel's status against it on both
sides of every guard bound; each guarded coverage row names its domain and runs under the domain's
predicate, its negation and IS NULL, fused with nothing declined inside and Spark's answer
outside: the test-side counterpart of row 245's guard completeness.

**What the emitter reached (280).** QPG found more bugs by steering generation toward unseen query
plans than a coverage-guided fuzzer that reached more code (pp. 8-9). Varka's plan is the
emitter's record of what it did: the canonical IR over literal slots, the `VarkaEmitTrace`
reactions, the groups and their shared or materialized prefixes, one kernel or several, the
decline reasons. Row 280 has every fuzzer and coverage suite report the distinct ones it reached
and the mechanisms it never did, which row 265 reads, then measures steering - draws that reached
something new kept and mutated - by a fixed-seed A/B, kept only if it reaches more.

**The fuzzers, sharpened (262, 81, 269).** Row 262 also draws `NOT p` and `p IS NULL` beside each
predicate and runs each projection under the three partitions of a drawn predicate; builds filters
that select known rows, evaluating each conjunct on pivot rows and negating the false ones, as PQS
rectifies; draws nested Catalyst trees with neighbouring types so the analyzer inserts casts
(`VarkaCatalystGrammar`, beside `VarkaIrGrammar`); and uses row 277's shrinker. Row 81 runs each
harvested statement as a filter too, `WHERE e = v` and its negation. Row 269 generates by type,
with series per sort checked both ways against `Analysis` up to size 3; runs size 3 in PR CI,
size 4 nightly and size 5 in an idle window (about 1,100, 27,000 and 700,000 int-lane trees);
enumerates two or three outputs over one shared subtree, which VARKA-234's shape needed; counts its
reach per position, since the grammar draws `date_add` offsets only as literals today; and runs
under row 248's word and guard configurations.

### 2.3 Structure (248 to 254, 214 to 217, 224, 83, 86, 222, 264, 266 to 268, 290, 291, 310 to 312,
320 to 322, 325)

Each row names its own proof. For the emitter's refactors (248, 249, 250, 83) it is
`emitted_bytes.json`, unchanged. The ports (214 to 217) change no emitted byte either, but the
bytes oracle says nothing about what the compiler admits: theirs are the coverage and
family-chain oracles, as VARKA-175's were, and a planning-time benchmark from VARKA-191's
emission times. The evaluator's split (251) and its port (267) are per-batch overhead on the row
boundary, so theirs is a before-and-after benchmark of it; caching the IR's hashes (222) is
compile time, measured before and after.

The options table (248) and the method-name class (249) go first, because every later refactor
touches options or method names. The table records why each option exists - an alternative kept
because the winner depends on the machine, a reference form, a fault injector such as
`misdescribeWordLiveness`, or retired - so that 247's question is answered for every option at once.
It is also the source of a configuration matrix (`m7/READING.md` 3): the suites run under
the options' configurations with a skip list giving a reason for each entry, as DuckDB's
`test/configs` do, and a shape marked as declining fails when it starts to fuse, as Druid's
`cannotVectorize` marker does, so neither list can go stale. A matrix failure is shrunk to its
minimal delta from the defaults (row 277), and its skip entry names that delta, which the stale
check reruns. The emitter splits (250, `emitBody`)
and the evaluator split (251) follow, 251 splitting `VarkaEvaluatorBase` into Java components rather
than into smaller Scala files. Row 83's single refusal goes after 250, since both rewrite the
emitter's refusal paths. Row 86's single operand admission goes after the ports, so that the
admission is written once in Java and not ported twice. The ports themselves (214 to 217, 224) are
mechanical and meant for an agent, gated as VARKA-175 was. Row 217 is item 74.4, and takes
`VarkaShapeCache.scala` with it, the one Scala file in the compiler's own package, so that 1.1's
claim holds. Item 74.2, the size loop, is VARKA-236's and is not repeated here. *Corrected 6
October 2026:* VARKA-236 put a plan in front of the loop and kept the loop itself, unchanged, as
the last resort (`VARKA-236.md` 3.2), so item 74.2 had no owner; row 290 is it, after row 83, which
rewrites the declines the loop ends in. The comments pass
(252) runs last, once the structure has settled, and corrects what the paper reading found wrong:
`VarkaEmitterDivisionSuite` says Java's `/` throws at `Integer.MIN_VALUE / -1`, where it wraps
(JLS 15.17.2), and `emitConstDivide`'s javadoc calls the long-lane division exact over the whole
lane, where it is exact under `EXACT_DIVIDEND_BOUND`.

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
checked as they land. From the NullAway paper: the checker runs as a javac pass of its own
(`dev/varka_errorprone.sh`), touching no Spark build file, in a CI job that fails on a planted
null dereference; NullAway is staged by class, a committed list of unannotated classes that may
only shrink, a reason on every suppression, and runtime non-null checks where Scala calls into
Java. The prediction registered is about 125 to 145 annotations, at the paper's rate.

**Milestone 4's debts (264).** `sql/varka/AGENTS.md` says a swept debt is rewritten in the past
tense, and several entries of `m4/PLAN.md` section 9 still read as open.
`dev/varka_emit.sh` reporting a crash as an empty success looks fixed in the script, which now
runs under `set -euo pipefail` and diagnoses a non-zero run, but its entry was never rewritten;
the range analysis's loose `INT` answers, the parity harness's `next_day` row and the week fold
have no recorded outcome. Each is closed, made a row, or moved to `m9/SCOPE.md`.

VARKA-253 states and enforces the seven-argument `run`'s scratch contract (item 68). Row 222
caches each IR node's hash. VARKA-254 (item 63) either pins each grouping weight to the count
its node emits alone, or retires the hand-written register so the grouping reads the class.

**The IR's storage, measured (291).** Item 85 of `m9/SCOPE.md` asks whether the IR's nodes should
live as rows of flat arrays, in memory the client supplies, and the owner asked on 6 October 2026
whether that could come before milestone 8. It is a measurement before it is a decision, and the
ports of rows 214 to 217 are the work it could change: they build the IR with about seventy `new`
expressions, most of them its records, so a representation change under them would be written
twice, as row 86's admission would have been. Row 291 compares four layouts on the real IR's graphs, with the real
IR compiled as value records as a fifth arm, in a spike beside the project; its plan registers its
predictions and its gate before a number is taken. Until its verdict the ports could build nodes
through factory methods, which would cost them nothing and leave the representation free to
change; that is the owner's decision and not yet taken, and rows 214 to 216 stand as written. *Row 291 is done (7 October 2026, `VARKA-291.md` 9.8): the layout is chosen, the factory-method decision is recommended and still the owner's.*

**The tests' helpers to Java (310 to 312).** Added by the owner on 12 October 2026. Catalyst's
Varka tests are 80 Scala files to 15 Java, and the rule ports a helper only when a task touches
it. The largest helpers left, `VarkaKernelCheck`, `VarkaVariantCheck` and `VarkaGuardSpec`, are
Scala for one reason: they drive `VarkaReferenceEvaluator`, a Scala object. Row 310 ports that
evaluator, 311 the three helpers over it, and 312 lets a suite itself be JUnit, which the
matrix's skip list, the `PinsDefaults` cancellation and the watchdog do not yet allow, since all
are ScalaTest mixins. Only after 312 does the rule for new suites change.

**The rest of the test code to Java (320 to 322).** Added by the owner on 12 October 2026, from a
count of the Scala left: about 15,600 lines of test code outside the suites, and 1,615 in
`sql/core`'s main tree, where the exec nodes and the columnar rule stay Scala as Spark's interop
surface. Three groups are worth a row each. The fuzz infrastructure (320), which the checks no
longer hold to Scala after 311, with the strongest proof a port can have: the grammar's draws feed
the bytes oracle, so a faithful port leaves `emitted_bytes.json` byte-identical. The censuses and
probes (321), standalone and mechanical. And the benchmarks (322), the largest group: the twelve
that run kernels directly on row 224's adapter, and the fourteen query benchmarks on a second
adapter for `SqlBasedBenchmark`, each proved by running against its Scala form through the pair
runner, read by best time as well as median. The ScalaTest harness
(`VarkaMatrix`, `VarkaOwnJvm`, the watchdog, `VarkaSharedSessions`) is 312's subject, and
`VarkaEmitterTestBase` shrinks as the suites over it move.

### 2.4 Infrastructure (255 to 257, 283, 286, 287, 313 to 319, 370)

VARKA-255 takes the CI queue off the laptop with no stored credential, splitting it so each
repository acts only on itself. `VARKA-227.md` 3 put the whole queue on `vecbricks/varka`,
which needs a personal access token on the fork with Actions write, kept as a secret there. The
owner declined that on 2 October 2026: any workflow on any branch of the base repository can read
a repository secret, and Actions write on the fork can dispatch workflows that push commits.
The design below was the first answer. On 13 October 2026 the owner accepted `VARKA-255.md` 3.1
and 3.2 in its place: GitHub's cron ran hours late in both repositories, so the queue became an
admission job inside the Build and the check sync starts on each push. The two bullets below are
kept as the record of what was replaced. The first design was:

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
test to fail on the PR's base, as ClickHouse's CI does (`m7/READING.md` 9).

VARKA-256 lets a benchmark run regenerate only the sections a PR adds or changes, and runs them
paired against the PR's merge base in alternating batches with a threshold, as DuckDB's
regression runner does, so that a band becomes a gate. VARKA-257 is
item 62's probe: the slow mode a kernel meets after N other kernels, as a function of N, with
one arm in the nightly. VARKA-283, found by VARKA-236's gate run on 3 October 2026, makes the
exhaustive division sweep of VARKA-149 fit inside the watchdog of row 226 or carry a cap of its
own, and makes a fork the watchdog halts fail its sbt step instead of hanging it; until then the
gate's and the nightly's sweep steps cannot pass.

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


**The gate's speed, and its narrow width (286, 287).** Added 5 October 2026 at the owner's
direction to use the laptop's cores: the gate ran its steps one after another and each module's
suites in one sbt test JVM, so a 24-core machine sat at a load of about 3 for 35 minutes. Row 286
runs them in parallel lanes over split JVMs, 11 minutes, and found on the way that the narrow
step had never been narrow: `JAVA_OPTS` never reached sbt's forked test JVMs. Row 287 applies the
same split to CI's four-core `varka-scoped` jobs, where it is worth less and must be measured on
the runners.
**Errors and troubleshooting (313 to 319).** Added by the owner on 12 October 2026, from a review
of what Varka tells its users and developers when something goes wrong, and of what the engines
the project has read do about errors. The commonest case is no
error at all: an expression declined at compile time, or a batch refused at run time and answered
by Spark's row path, which nothing reports (313). The errors that do surface are free text rather
than Spark's error conditions (314), a kernel that fails leaves only what was configured to be
dumped beforehand (315), and there is no written path from a symptom to the steps that diagnose
it (316). Three practices from those engines are rows of their own: errors compared as answers
are (317, Comet), a kill switch per family named in its messages (318, StarRocks), and an
expected-errors list in the fuzzers (319, SQLancer). The developer side - fuzz failures signed by their kind, and mismatch messages that
carry the kernel's value, the row's inputs and the body that ran - is in row 311.

**What a running JVM allows (370).** Added 15 October 2026 from the cause of the warm-up flake
(`VARKA-295.md` 9.6, in pull request #825 until it merges): C2 puts a real virtual call in place of
an uncommon trap on the class-check miss of the Vector API's inlined templates when two sums over
a compile, trap counts against `PerMethodTrapLimit` and decompiles against
`PerMethodRecompilationCutoff`, pass their limits, and the two JVM options that raise them cannot
be set by an executor's own code. Row 370 asks the question that decides the remedy's shape: whether any mechanism of a
running JVM resets or avoids those counts. Its admission check read the HotSpot source and ran
the seconds-long experiments: none of the three limits is `manageable`, so `setVMOption`,
`jcmd VM.set_flag` and the `DiagnosticCommand` MBean refuse them, with or without the diagnostic
unlock; compiler directives can be added at run time but have no option for them; WhiteBox sets
them and clears one method's counts, but only under `-XX:+WhiteBoxAPI` and a jar on the boot class
path (`VARKA-370.md` 2). What remains is the instrumentation route and one replay. The row does
not touch 295's remedy.

### 2.5 Promotion (180, 258 to 260, 270, 288, 371)

VARKA-180 is the cadence carried from milestone 6. VARKA-258 and VARKA-259 are the pair of posts in
items 78 and 79, 258 first; 260, the reference post of item 80, comes between them and the next
long post, as the catalogue orders them.

VARKA-270 is the milestone's own post, for JVM and database engineers: how Varka knows its
answers are right - the proofs, the differential against Spark, the sanitizer, the measured
adequacy of its tests - and where that knowledge stops, at the emitter's plumbing, which item 58
bounds. Milestones 4, 5 and 6 each ended in a public message, and item 58 calls "every
arithmetic lowering carries a machine-checked proof over its guarded domain" publishable.

VARKA-288, added 5 October 2026 at the owner's request, is the sequel to "The 8000-byte cliff in
Spark SQL": apache/spark#59225 (SPARK-33301) closes the thread that post left in review, by
splitting a large `CASE WHEN` inside a stage only where its method is past the line. It waits on
that pull request's merge rather than on a wave.

VARKA-371, added 15 October 2026, is a short post for Spark and JVM developers on the cause
`VARKA-295.md` 9.6 found: a warmed Vector API kernel compiled at the top tier still allocates one
array per loop iteration in a few per cent of warm-ups, because C2's class-check gate adds up the
trap counts of every inlined copy of a shared template. It is outlined (`VARKA-371.md`) and its
draft waits for the result of the fix being tested on branch `varka-295f`, since the post's last
section is the remedy.

### 2.6 The closing task (261)

1.1 read row by row, the scoring of each row's predictions collected, and the README's account
of how Varka is checked rewritten on what the proofs and tests now hold, quoting nothing that
does not trace to a committed file. VARKA-270's post is written from the same record, after it.

Since 3 October 2026 the row also carries the close every milestone owes under `CONTRIBUTING.md`'s
rule ("Finding work"), in the shape milestone 6's VARKA-282 gave it (`VARKA-282.md` 2): every
document that names the milestone in flight corrected, the issue mirror applied, the lessons in
the skills files, the oracles regenerated on the closing commit and expected unchanged, the
milestone's entry in `CHANGELOG.md`, an annotated tag `varka-m7` on the commit that closes the
last row, and the worktrees and branches of merged pull requests removed.

## 3. Task breakdown

Task numbers continue the single sequence from 240; rows that moved to the catalogue from
milestones 5 and 6 keep theirs. Rows 262 to 270 came from the plan's own review, 271 and 272 from
the survey of other engines, and 273 to 281 from reading the papers (the notes at its head).

| task | what it is | where it came from | size |
| ---: | :--- | :--- | :--- |
| 240 | **Done** (`VARKA-240.md` 9, 9 October 2026): `sql/varka/proofs/`, Java's int and long operators stated once over SMT-LIB integers and held to the JVM, and the multiply-high division proven exact for eleven divisors under Z3 and cvc5, which agree; `dev/varka_prove.sh` checks every verdict, with a self-test, in the linters' job under Z3 and the nightly under both, beside the sweep it duplicates, which now cites it. `signedMagic` asserts Theorem 5.1, raising the book's shift for the 327,741,950 divisors whose pair misses it, the first 196611, with no emitted byte moved. Over bit-vectors the proof timed out under every solver tried; over integers it takes under a second | item 58 step 1, `m7/READING.md` 11 | small |
| 241 | **Done** (`VARKA-241.md` 9, 10 October 2026): `long_divide.smt2`, both long-lane division forms exact for every dividend under `ConstDivide.EXACT_DIVIDEND_BOUND` and every divisor in use, and the conversion form's exact bound found - one less than the first multiple of the divisor past 2^53 - over integers, since floating-point theory did not decide them (Bitwuzla eight minutes for 1000, past 25 for 10^9); doubles in `java.smt2` by their rounding, held to the JVM; 867 checks, 28 s under Z3 and 8 s under cvc5, in the linters' job | item 58 step 2, `m7/READING.md` 11 | medium |
| 242 | **Done** (`VARKA-242.md` 9, 10 October 2026): every calendar division site proven over its range with its first failing dividend pinned, and both double forms; `floorMod7`'s three forms for every int; the leap hash's unsigned compare over bit-vectors; each quoted bound labelled; the rule in `sql/varka/AGENTS.md`. | item 58 step 3, `m7/READING.md` 11 | small to medium |
| 243 | **Done** (`VARKA-243.md` 9, 14 October 2026): `sql/varka/proofs/spec/STANDARD.md`, 19 definitions quoting the SQL-92 text, each saying whether it truncates (interval fields, the time difference), floors (the date decode, the month carry, the time's modulo 24) or does not divide, with eight cross-cutting choices recorded; `VarkaStandardExamplesSuite` runs through the kernels every example Spark agrees with that a coverage row fuses. The specification fragment: one definition per operation the coverage table lowers that the standard fixes, every interpretation choice recorded; each operation says whether it truncates or floors. *Corrected by its admission check (`VARKA-243.md` 2): the repo holds none of the standard's text, so "the standard" is the SQL-92 text, the one edition that could be read, which fixes 19 of the table's 46 operations; the current edition's clause for each is owed* | item 59 step 1, `m7/READING.md` 11 | medium |
| 244 | **Done** (`VARKA-244.md` 9, 16 October 2026): `sql/varka/proofs/spec/DELTAS.md`, eleven named deltas each patching the `STANDARD.md` definitions it names - `overflow_wraps` (non-ANSI), `overflow_yields_null` (division and `make_date` only, since no operation of the fragment nulls), `clamp_to_month_end`, `proleptic_range`, `day_count_wraps`, `interval_precision_unbounded`, `interval_overflow_state`, `time_add_raises`, `time_add_day_field`, `date_add_time_field`, `no_leap_second` - and the checks that found no delta, among them 4.11, corrected: Spark's `EXTRACT(SECOND ...)` keeps the fraction and only its `second(t)` drops it; `EXTENSIONS.md` marks the 27 other coverage operations and `second(t)` as the standard's by reduction, ISO 8601's or Spark's, each defined; `VarkaStandardDeltasSuite` shows each delta on the row engine, and on the fused path where a coverage row reaches it. Spark's departures from it as named deltas, each with a test showing Spark doing it; the extensions marked as Spark's or ISO 8601's | item 59 step 2 | medium |
| 245 | **Done** (`VARKA-245.md` 9, 16 October 2026): `sql/varka/proofs/refinement.smt2`, 216 checks, each coverage row of `VARKA-245.md` 3.4 stated as five queries per mode over (data, valid) pairs against `STANDARD.md` with `DELTAS.md` in Spark mode and alone in standard mode, over the calendar's rules, its decomposition and inverse as lemmas, and every lowering site fed only what its proof covers; about three quarters of `range_calendar.smt2`'s time per solver. Standard mode fails exactly at its deltas' witnesses, `SCOPE_STANDARD_MODE.md` citing them. Found: the unchecked column contract is load-bearing (a fused `year(d)` over a stored date past year 42400 would be wrong, by the scalar twin; not run end to end), three guards decline batches Spark does not need declined, on rows whose other operand is null, and `EXTENSIONS.md`'s `datediff` wraps. The proofs restated as refinements of the specification, soundness and guard completeness, in Spark mode and standard mode; `SCOPE_STANDARD_MODE.md` cites the standard-mode theorems; five queries per mode over (data, valid) pairs | item 59 step 3, `m7/READING.md` 11 | medium |
| 281 | **Done** (`VARKA-281.md` 9, 14 October 2026): four rendered files under `sql/varka/proofs/` - `value_range.smt2`, `range_analysis.smt2`, `range_calendar.smt2`, `time_bounds.smt2`, 306 checks - prove every rule of `VarkaValueRange` and `VarkaRangeAnalysis` and `VarkaTimeCompiler`'s six bounds under Z3 and cvc5, in the witness form (a member of the interval congruent to the lane value modulo 2^32, since containment is false past int32), the products over the reals and in Fiat's two layers, `add_months` with a symbolic month count over `java.time`'s closed forms pinned to the JVM; `VarkaRangeRulesSuite` sweeps the calendar rules over the admitted range in 1.5 s; every rule's constants named in the analysis and rendered from it; one latent unsound rule, found by the branch's review and fixed - `SUB` answered the sum's interval, wrong over the asymmetric operands a `BoundedDivide` or an int-lane `GuardedRange` gives, which no compiler path composed - and the `year` answer's low end slack by 29,600 years (item 86); seven planted faults each caught. Planned (admission check, 14 October 2026: 45 obligations - 11 lattice rules, 13 arithmetic arms, 14 calendar rules, 7 bounds at the `TIME` compiler - of which two are nonlinear, the product theorem, which both solvers decide over the reals and in Fiat's two layers; the claim "the interval contains the lane value" is false past int32 and the provable one is a witness congruent to the lane value modulo 2^32; `add_months` with a symbolic month count is linear over `java.time`'s closed forms and decides in under 2 s a case once split by sign; the lint budget has room for about 14 s more on the runner). The analyses that supply the preconditions proven: an obligation for each rule of `VarkaRangeAnalysis` and `VarkaValueRange` and for `VarkaTimeCompiler`'s quotient bounds, over symbolic interval ends and Java's wrapping arithmetic | Alive, `m7/READING.md` 11 | medium |
| 81 | **Done** (`VARKA-81.md` 9, 10 October 2026): `VarkaGoldenCorpusSuite` runs the 731 statements of seven golden-file inputs, each lane-typed literal a column of an Arrow-cached fixture, as a projection and as a filter on both engines; no statement disagrees, 122 raise the same error on both, and `sql/varka/golden_corpus.json` counts what fuses per file (23 projections, 86 filters fully and 138 partly) | item 39, `m7/READING.md` 11 | small; a newcomer's |
| 138 | **Done** (`VARKA-138.md` 10 and 10.3, 13 October 2026). 138b: each of the 27 predicate rows' `WHERE p` and `WHERE NOT (p)` run on Varka with a Varka filter in the plan, on the three fixtures, under the default optimizer and with the three rewrites excluded, share no row and leave exactly Spark's `WHERE (p) IS NULL` rows, 162 cases all agreeing; under ANSI on a fixture of the types' extremes, every row alone and where Spark skips it - the untaken CASE arm, a later COALESCE argument, the right side of a false AND and of a true OR - raises the same condition, SQLSTATE and parameters on both engines or answers the same rows, and a kernel serves each skipped position or it is on a declined list of 38 with reasons; with the overflow check emitted off, three rows fail as "only Spark raises"; the suite 55% slower by minimums against at most twice predicted; the CASE reached for 61 of 65 rows and the COALESCE for 42, against 50 predicted for both; no disagreement found; one gap moved to `m9/SCOPE.md` (item 96). 138a: every batch the kernel checks compare runs at output validity pre-filled 0x00, production's, and 0xFF, and no kernel of the seven suites that use them fails at either; a planted fault, a coverage row's kernel with every validity write removed from its bytecode, fails only at 0x00 on a batch with no nulls and only at 0xFF on a batch of nulls; each of the 184 coverage kernels (92 rows at two widths) is compared on a fixed 257-row batch with a poisoned null lane in its loop and its epilogue; the per-row test 27% slower by minimums after the review's fixes (38% before them), against 25% predicted; the emitter matrices and chrono sweeps run at both fills too. Admission check: Poisoned null lanes already reach each coverage row's masked body through the per-row test of VARKA-276, by chance rather than by construction; with output validity zero-filled, which is what production's Arrow `allocateNew` gives, every kernel-check suite passes; the 486 partition queries of the 27 predicate rows agree with Spark under both optimizer settings, but `p IS NULL` reaches Varka only where Spark rewrites it onto the columns; and 204 ANSI queries agree on condition, SQLSTATE and parameters, while a CASE without ELSE and `coalesce(1, e)` never reach Varka). Three test forms: poisoned null lanes in each coverage row's masked body, both-engines-throw-or-agree for ANSI rows comparing the error class, SQLSTATE and message parameters (`checkError`), not only that both throw, and a ternary-logic partition oracle for every filter row; output validity pre-filled both ways; the partition compared on rows, with and without `BooleanSimplification`, `NullDownPropagation` and `InferFiltersFromConstraints`; the ANSI form over every position Spark skips | item 39, `m7/READING.md` 11 | medium |
| 246 | **Done** (`VARKA-246.md` 9, 9 October 2026): the guard `VarkaSpeciesGuard` found nine suites that define a second species (seven at 512 bits, two more at 256 and 128), all now in JVMs of their own through `VarkaOwnJvm`; the full run is 95 to 105 s longer. One species per lane type in the shared test JVM: the census, each second-width arm moved to a forked JVM, a guard in the emitter's test base, the full Varka run's time before and after | item 69 | small to medium |
| 247 | **Done** (`VARKA-247.md` 9, 10 October 2026): the option stays; its subject is the per-group validity OR, which the defaults never emit, so it moves nothing there and 65 of 92 coverage rows over `validityByBitmap=false, denseValidityOnce=false`; `VarkaEmitOption.Subject` names that base, the bytes oracle pins both values over it, and the audit compares there | item 48 | small |
| 262 | **Done** (`VARKA-262.md` 9, 8 October 2026): the random differential, the shrinker, the saved reproducers, the nightly arm and the partition check; rows 296 to 298 are the rest. Vanilla Spark as the oracle, at random: the composition fuzzer's projections and filters run with Varka on and off over random data with nulls, ANSI on and off, the answers compared; one arm in the nightly; a failure shrunk to its smallest failing subtree and saved, the saved reproducers replayed in PR CI; each fused output forced to decline in turn, the answer unchanged; `NOT p` and `p IS NULL` beside each predicate and projections under its partitions; filters that select known rows; nested Catalyst trees with type coercions; row 277's shrinker | the plan review (2.2), `m7/READING.md` 1, `m7/READING.md` 11 | medium |
| 271 | **Done** (`VARKA-271.md` 9 and 10, 13 October 2026; 271b: literals of a `SELECT` with no `FROM` lifted into 17 rows where the compiler fuses them as columns, served executions 1,584 to 1,761 against 2,000 predicted, the planted `date_add` fault failing the date files; 271a): every golden file through an Arrow cache in both arms, 789 passed and 1 cancelled by the exclusion list in each, the five plan-printing files compared but for their `EXPLAIN` statements, the arms identical test for test; Varka served 1,584 of 47,323 query executions and 5,843 batches in 108 tests; the served report held as a floor, since three tests move by one execution run to run. Admission check: Varka on alone serves 0 batches in Spark's golden files, an Arrow-cached copy under each relation gives it 1,423 of 32,429 query executions and 5,323 batches in 105 of 390 tests with no golden answer changed, and lifting literals raises the served executions on nine date and int files from 26 to 88. Spark's own SQL suites with Varka on, Spark's golden files the oracle: a patch per Spark version or subclassed suites, every exclusion with a reason, and the exclusion list failing when an excluded test starts passing | `m7/READING.md` 2, item 24 | medium |
| 263 | **Done** (`VARKA-263.md` 5, 7 October 2026): the sanitizer is on in every suite CI and the gate run, the runner fails a run that claims it and lacks it, the catalyst fuzzers run inside its window, and it found row 292 and nothing else. A sanitizer for kernel memory access: under a test-only flag the evaluator records each buffer's address range and real capacity, and `VarkaVectorSupport.ofAddress` fails on a segment outside them; a canary past each buffer, checked when the kernel returns; where each segment came from recorded; every byte back at close; the suites and fuzzers run with it on | the plan review (2.2), item 58, `m7/READING.md` 5 | small to medium |
| 269 | **Done** (`VARKA-269.md` 9, 11 October 2026), nothing failed to size 5 at either lane (3.76 million trees): every IR tree up to a size counted in grammar productions, mirrored arm by arm from `VarkaIrGrammar`, int and long lanes; size 3 in the gate (3,203 trees; size 4, 101,422 trees, until 11 October 2026), size 5 nightly (3.16 million) and size 6 in an idle window, split across JVMs; shared outputs, the ungated series sorted against `Analysis`, reach per position. Every small tree: each well-typed IR tree up to a small size through the emitter, compared with the reference evaluator under the fuzzers' null patterns; generated by type and checked both ways against `Analysis`; sizes 3, 4 and 5 in PR CI, nightly and an idle window; shared-output shapes; reach counted per position; under row 248's configurations | the plan review (2.2), item 58, `m7/READING.md` 11 | medium |
| 272 | **Done** (`VARKA-272.md` 9, 15 October 2026): seven arms forked by `dev/varka_platform.sh` (interpreter, C1, C2, intrinsics off, `-Xcomp`, stress flags, fastdebug), each proving its tier under rules of its own, 3,368 answers compared with no disagreement, a crash keeping three files, a `platform` nightly step and an interpreter smoke in the catalyst CI job; findings: no warm-up settles with the intrinsics off, and one epilogue's C2 compile uses 337 MB. The platform under the kernels: the fuzzers' kernels run under the interpreter, C1, C2 and the Vector API's intrinsics off (`-XX:-EnableVectorSupport`), each in a forked JVM, the answers compared; C2's seeded stress flags on the fastdebug JDK; each arm proves its tier through JFR; an `-Xcomp` arm; crash artifacts kept; the stress flags nightly, since they are product diagnostic flags | `m7/READING.md` 8, items 56 and 58, `m7/READING.md` 11 | medium |
| 273 | **Done** (#559, 3 October 2026): both Varka filter nodes carry the whole condition when residual conjuncts are split off, and a declined batch is refiltered with it; the reproducer is the regression test. **Reproduced** (3 October 2026, a test in `VarkaDifferentialSuite`). A fused conjunct that can raise runs on rows a residual conjunct before it would have stopped: a declined batch is refiltered with the fused conjuncts alone, so under ANSI `WHERE s = 'x' AND make_date(2021, i, 1) < d` fails on Varka with `DATETIME_FIELD_OUT_OF_BOUNDS` where Spark returns its rows. The fix gives the fallback the original condition, every conjunct in its order | NoREC (p. 5), `m7/READING.md` 11 | small |
| 274 | **Done**: 274a and 274b. **274a done** (`VARKA-274.md` 9, 13 October 2026): the IR fuzzer's first 20 draws a lane run over eight batch histories, each on a class of its own, every batch compared, and a JFR stream with the code cache's method list says which tier served each call; no history found a wrong answer; settled warm-ups C2 on 85% of cases, `quiet-null` trapped on 9 and 13 of 14, `quiet-remainder` on 13 and 14, `released` was served by a tier-4 OSR on 13 and 14 of 14; small kernels' loops are C2 code only inlined into their driver, so the witness reads `jdk.CompilerInlining`; the default arm takes 145 s and the witness adds 19 to 25% a case, both over the predictions. **274b done** (`VARKA-274.md` 9.7, 16 October 2026): the coverage suite's first test runs 4 rows' kernels a lane, drawn by the seed, over `warm-same`, `quiet-null` and `released` (all 92 took over the suite's 9-minute watchdog at load 30), about half a minute added to the suite; the nightly's `compiled` step (`dev/varka_compiled_fuzz.sh`, `--skip-compiled`) runs 1,000 draws a lane (the owner's choice of 16 October 2026) over eight JVMs of three cores and then every coverage row, totalled by `dev/varka_compiled_fuzz.py`, about 50 minutes extrapolated from a two-JVM trial that found no wrong answer and 3 of 66 cases unsettled, all at `profile_predicate`; the skills section is `the-jit.md`'s on the tier read; predictions 1, 6 and 9 are owed to the first nightly. Admission check: today's fuzz kernels are never compiled at any tier, 40 drawn kernels reach C2 in a median 164 ms with 3 of them caught in the `profile_predicate` deoptimization cycle, an in-process JFR stream with the code list proves per call which tier served and what trapped at no measurable cost, a quiet warm-up traps on an all-null input in 28 of 40 kernels, and an OSR mid-batch happens on 10,000-row batches with C1 on, at tier 3 at batch 96 to 101 and tier 4 at 163 to 186, and never on the warmed path). The fuzzers check the code users run: each kernel warmed to C2 before the compared batches, JFR proving which tier served each call, and about eight batch histories per kernel - calls before, an OSR mid-batch, a trap after a quiet warm-up | Artemis, `m7/READING.md` 11 | medium |
| 275 | **Done** (`VARKA-275.md` 9, 10 October 2026): under test a kernel failure, a row-machinery failure or an emission failure no test declared fails its task with the failure as its cause; the failure hooks declare one, and `setFailureFallbackExpectedForTesting` declares the others; declines, non-Arrow and warm-up batches, the emitter's over-budget decline and failures in a task being killed pass. No hidden failure; two tests that caused one without a hook now declare it. A census on 3 October 2026 ran every Varka suite in `sql/core` on master and found no hidden one: each kernel-failure, row-path and emission fallback logged came from a test that injects it, so the row adds the guard and has nothing to fix first | mutation testing, `m7/READING.md` 11 | small |
| 276 | **Done** (`VARKA-276.md` 9, 10 October 2026): `VarkaSparkOracle` gives Catalyst's `eval` of each kernel output's own expression, and `VarkaKernelCheck` holds the reference evaluator to it on every row before the kernel - in the composition fuzzer's wide test and a new test of every coverage row alone; the one disagreement was the fuzzer's `TIME(6)` draw, fixed. | PQS, NoREC, `m7/READING.md` 11 | small |
| 277 | **Done** (`VARKA-277.md` 9, 8 October 2026): the IR fuzzer's failures. One shrinker for every fuzz failure: an IR tree roots first then level by level, kept well typed; then the option delta; then the batch; failures grouped by signature, with a committed list of known ones that fails when an entry stops matching | delta debugging, `m7/READING.md` 11 | small to medium |
| 294 | **Done** (`VARKA-294.md` 9, 9 October 2026): a wide case of 214 entries with a planted emitter bug shrinks to one entry in 15 runs. The composition fuzzer's failures shrunk (`VarkaCoverageCompositionFuzzSuite`): its case is a list of picked coverage rows, the options and the batch, so the ddmin core, the signature and the known-failures list of 277 apply; the entries it picks are what ddmin removes, each one's expression what the tree moves reduce. Left by 277 | 277 | small |
| 302 | **Done** (`VARKA-302.md` 9, 10 October 2026): the IR fuzzer's test that a failure fails with the smaller case in its message plants `misdescribeWordLiveness` on draw 0, which the planted bug misses under some seeds, so it failed under 8 of the 14 seeds of the night of 10 October 2026 while every random tree passed; it takes the first failing draw, as the shrinking tests beside it do, and passes at the default seed and all fourteen (the furthest first failing draw is 11 of 200). With it, row 240's first prediction scored from #696's CI: 7 seconds, 0.8 of them solving | the night of 10 October 2026, VARKA-277 | small |
| 309 | **Done** (`VARKA-309.md` 9, 12 October 2026): the test draws from a seed of its own, the suite's default, where it first changes an answer at composition 82; at 11 of 32 seeds surveyed, today's date among them, no composition of the first 300 reaches the planted bug, so the old test failed at a third of seeds. The Spark differential's planted-rollback test ("a forced output finds a rollback that truncates one input too far", `VarkaSparkFuzzSuite`) draws from the run's seed: it searches the first 300 compositions of that seed under `misdescribeRollback` 3 and requires the first failure to be a forced-decline mismatch. At seed 1 none of the 300 failed (`None` after 8 minutes), so the test fails at a seed other than its default while no random composition disagrees, and `dev/varka_nightly.sh` runs it at each day's seed; the night of 11 October 2026 left it out to run 20 seeds. The test draws from a fixed seed of its own, as a planted check does not need the run's variety, with the furthest first failing draw over a set of seeds recorded as VARKA-302 did | the night of 11 October 2026, VARKA-302 | small |
| 278 | **Done** (`VARKA-278.md` 9, 12 October 2026): every int and long tree to size 3 in eight variants in the gate, to size 4 nightly, and the IR fuzzer's passing draws too; nothing failed. The fuzzer's first run corrected the plan: an untaken arm stays untaken only on its batch's own nulls. Variants that must agree: about eight per passing IR shape - outputs permuted, a dead output added, an untaken arm replaced, a literal swapped for a constant column - checked against the reference evaluator | EMI, `m7/READING.md` 11 | medium |
| 279 | **Done** (`VARKA-279.md` 9.1 and 9.2, 14 October 2026): a verdict per batch from the emitter's own guards and arm chains, every guard at `lo - 1`, `lo`, `hi` and `hi + 1` in a full lane and the epilogue, valid and null, through both bodies, a moved bound caught at each end (9.1); and 15 of the 92 coverage rows carry a guard and name their domain, rendered from the guards' own constants into `coverage.json` and the docs table, the coverage suite failing a guarded row without one and a domain without a guard. Each runs over a fixture at every bound and one past it, cut into the rows inside the domain, outside and unknown, as a projection and a filter: inside and unknown served with nothing declined, outside every batch declined with exactly the verdict's status, read off the JFR fallback event, the answer Spark's rows or its error. Found: the verdict stayed silent above an overflowed FAIL negation, where the kernel's lane holds the wrapped value and the month count fires too (`d - ym` at the count whose negation overflows; the verdict now allows it, and the edge suite pins the composed kernel); `date_add(d, i)`'s note promised a bound its kernel does not carry (fixed, with a test at the int extremes); and four guards no value of the column type can trip (the three time range guards and the quotient's checked subtract), their domains total | TLP, Alive2, Korat, `m7/READING.md` 11 | medium |
| 280 | **Done** (`VARKA-280.md` 9, 16 October 2026): the emitter notes 39 mechanisms (`VarkaEmitMechanism`) where it emits them, at compile time and with `emitted_bytes.json` unchanged; the IR fuzzer's int-lane, long-lane and wide tests and the coverage table report what they reached and never did, committed under `sql/varka/coverage/reach/` for row 265 (the fuzzer's tests never reach 3, its two lanes' draws alone 10, the table 20); steering is not kept - over 16 seed-lanes at 300 emissions an arm it reached more than the plain draws in 7 and fewer in 4, and its one-sided gain at 3000 is offered for the nightly. What the emitter reached: each fuzzer and coverage suite reports the emitter mechanisms it reached and never reached, read by row 265, then steering toward unseen ones measured by a fixed-seed A/B | QPG, `m7/READING.md` 11 | small to medium |
| 265 | **Done** (`VARKA-265.md` 9, 17 October 2026): branch coverage - `sql/varka/coverage/emitter.md`, the emitter 90.1% of its branches and the compiler 83.7% under every Varka suite, `Analysis` 96.2% and `Slots` 92.9%; the mutation run - `dev/varka_pit.sh`, PIT 1.30.0's official artifacts through a ScalaTest plugin written against PIT's SPI, eight shards on GitHub's runners on the owner's word (9.7) - 1,760 mutants of `Slots` and `Analysis`, 1,504 killed by a behavioural test, 117 by the bytes oracle alone, 139 survived; each of the 256 answered in `sql/varka/mutation/notes.tsv` (9.8): 37 by tests added for five gaps (refusals tested at one bound, a filter's epilogue over a loaded prefix, answers filed under `PinsDefaults`, the form without a byte budget, a conditional's arms emitted in the other order than planned), 157 equivalent, 51 structure only, 11 message only; no wrong answer found, and 87 of the bytes-only kills one `PinsDefaults` test that fails under PIT for reasons of its own (section 10). How well the tests test: branch coverage of the emitter and compiler under the suites and fuzzers, then a bounded mutation run (PIT) on `Slots` and `Analysis`, sharded on CI; each surviving mutant a test or an equivalence note; PIT through the ScalaTest suites test by test; three verdicts per mutant - killed by a behavioural test, killed only by the bytes oracle, survived | the plan review (2.2), `m7/READING.md` 11 | medium, measured |
| 284 | **Done** (`VARKA-284.md` 9, 10 October 2026): `VarkaKernelCheck` runs every compared batch through the other body too, and `checkMatrix` any body its cases miss; of 2,803 classes that ran, 2,029 now enter both drivers (733 before), 213 have one driver by construction, and the rest are named by family in `sql/varka/coverage/generated.md` - the end-to-end suites, where the data decides the body, the emitter suites' direct calls, and the IR fuzzer's throwing cases | the JaCoCo spike (2.2) | small |
| 285 | **Done** (`VARKA-285.md` 9, 10 October 2026): `dev/varka_gen_coverage.sh` runs every Varka suite under JaCoCo, the own-JVM suites included, and `sql/varka/coverage/generated.md` sums each emitted class on its own, per method kind and per IR operation: 2,803 of 14,743 classes ran, 733 entering both drivers; the missed branches in executed loops listed by operation for rows 284 and 265 | the JaCoCo spike (2.2) | small |
| 303 | **Done** (`VARKA-303.md` 9, 10 October 2026): under `-Dvarka.checkBothBodies=true`, which `dev/varka_matrix.sh` sets, `VarkaKernelRunner` runs each batch the kernel served through its other body and fails the task on a disagreement; the evaluator's classes entering both drivers went from 224 to 622, 19 left one-bodied, and every suite passed with the check on | `VARKA-284.md` 9.3 | small to medium |
| 304 | **Done** (`VARKA-304.md` 9, 11 October 2026): each reason its own bit, the default keeping one mask per body and reporting the union of its reasons (a quarter of guarded bodies hold more than one), and an emit option `exactRefusalStatus` giving each reason its own mask for exact attribution; the evaluator counts declines by reason and the JFR fallback event carries the status word. The default changes only the status constant (a regeneration with the old constant written back is byte-identical to master's), and the option moves only shapes with a method of several reasons. A status bit per refusal reason: `Analysis.Refusal` (VARKA-83) gives each reason its own bit in the kernel's status word instead of the shared `STATUS_CHRONO_RANGE`, and the evaluator counts declines by reason, so "which guard fired" becomes answerable from the metrics. Changes emitted constants, so `emitted_bytes.json` is regenerated and its diff limited to the status constants | `VARKA-83.md` 3.2 | small |
| 306 | **Done** (`VARKA-306.md` 9, 11 October 2026): `groupLocalSlots=false` and a byte budget of 0 pinned as option arms over the oracle's own shapes; the 9.1 regression, reintroduced, fails the suite naming both arms at both widths, and the failure report now names a moved arm; 21 seconds on the suite. `VarkaEmittedBytesSuite` pins the reference arms too: its option arms are the settings a session can select (`useAVX`, `validityOrFirst`), so a slot change under `groupLocalSlots=false` or a byte budget of 0 moved bytes in 23 of 64 multi-group guarded cases unseen (`VARKA-83.md` 9.1). Add digests for the reference arms over shapes with refusing nodes in some groups only. Proof: the 9.1 regression, reintroduced, fails the suite | `VARKA-83.md` 9.1 | small |
| 307 | **Done** (`VARKA-307.md` 9, 11 October 2026), the suite 72 seconds in the gate from 325 and the wide step 110 seconds shorter: the fuzz shapes drawn once, every pass hashed on a pool of threads and joined in order, and a test that emits the oracle's shapes on every thread at once against each emitted alone; the suite is the gate's critical path (325 of the slowest JVM's 791 seconds). The bytes suite in parallel: `VarkaEmittedBytesSuite` emits every shape once per pinned arm and width, eighteen full passes one after another (about six minutes in the gate), and draws the twenty thousand fuzz shapes again for each. Draw them once, and run the passes on the laptop's cores once the emitter is shown safe to call from several threads (its caches, the options record, the Class-File API). Proof: `emitted_bytes.json` unchanged, and the suite's time before and after | `VARKA-306.md` 9.1 | small |
| 308 | **Done** (`VARKA-308.md` 9, 11 October 2026): the conf, and the session's emit settings as one record bound from `SQLConf` in place of the bare `int emitUseAVX` every exec node, factory and evaluator carries; proven end to end through the JFR fallback event's status. A session conf for `exactRefusalStatus` (VARKA-304), so a production query can be rerun with exact refusal attribution: threaded through the exec nodes' evaluator factories as `useAVX` is, after row 305's pull request, which changes them | `VARKA-304.md` 3.3 | small |
| 289 | **Done** (`VARKA-289.md` 9, 6 October 2026): every wide-composition kernel compared on both lanes, 48 of 48 on the default seed where master compared 11, a declined batch drawn again nearer zero, and the IR fuzzer drawing for a mechanism its compositions missed; the five seeds pass. The wide fuzz tests' adequacy by construction: `VarkaCoverageCompositionFuzzSuite`'s wide test compares long-lane kernels row by row as it does int-lane ones and counts comparisons per lane, and `VarkaIrFuzzSuite`'s wide test reaches every size mechanism whatever its 10 draws. *Found 6 October 2026 by an idle-window fuzz campaign*: 4 of 162 composition seeds (2026100605003 first) draw wide projections that are all long-lane, so 0 kernels are compared and the adequacy assertion fails, deterministically - no long-lane kernel of a wide projection is compared under any seed; and IR seed 2026100602011 reaches no group halved on bytes. Done when the long lane is compared, those five seeds pass, and the runs report what they compared and reached | the fuzz campaign of 6 October 2026 | small |
| 248 | `VarkaEmitOptions` from one table of options. **Done** (`VARKA-248.md` 9.4, 5 October 2026): the table (#618), the option matrix on the laptop (#624), one configuration per pull request and all 45 weekly in CI (#625, #626), the first weekly run green over all 45. **Step 1 done** (`VARKA-248.md` 9.1, 4 October 2026): `VarkaEmitOption.TABLE` holds every option with its reason; `canonical()`, the inventory, the fuzzer's draws and the dump's parser read it, and the written-out defaults are checked against it; **Step 2's matrix merged** (#624, `VARKA-248.md` 9.2, 5 October 2026): all 45 configurations ran on the laptop and found no wrong answer; 79 tests tagged `PinsDefaults`, 71 skip lines; the PR job and the weekly workflow in review (3.2.8), the weekly's first run the confirmation. The row: defaults by name, and `canonical()`, the bytes suite's inventory and the fuzzer's draws derived from it; each option's reason recorded - an alternative kept because the winner depends on the machine, a reference form, a fault injector (`misdescribeWordLiveness`), or retired; the suites run under the options' configurations with a reasoned skip list, and a shape marked as declining failing when it starts to fuse. Proof: `emitted_bytes.json` unchanged; each skip entry names its minimal option delta | item 74.1, `m7/READING.md` 3, `m7/READING.md` 11 | small to medium |
| 249 | One class owns the emitted method names, replacing the prefix matches in main and test code. **Done** (`VARKA-249.md` 9, 4 October 2026): `VarkaMethodNames` builds and reads every emitted method's name, main code and tests go through it, and the bytes oracle is unchanged. Proof: `emitted_bytes.json` unchanged | item 74.6 | small |
| 250 | `emitBody` split into driver, loop and epilogue emitters sharing the prologue helpers. **Done** (`VARKA-250.md` 9.1, 5 October 2026): `emitDriver` (its table and unrolled forms) and `emitGroupBody` over shared prologue steps, byte-identical under every option arm at both widths. Proof: `emitted_bytes.json` unchanged | item 74.3 | medium |
| 290 | **Done** (`VARKA-290.md` 9, 11 October 2026): the loop is a `SizeLoop` of six named steps over one state object, its resets one method beside the changes of grouping, and the fallback order stated once in `fallBack`; every size decision over a fixed corpus of 3,312 emissions byte-identical to the base, and `emitted_bytes.json` unchanged. The size loop restructured: the private `VarkaLoopEmitter.emit`, 281 lines that build, measure and correct a class - the plan's first build, the rollback, the fallbacks in their order and the declines - with about eight pieces of mutable state, split into named steps over one state record, the fallback order stated once. Proof: `emitted_bytes.json` unchanged, and the size-control trace counts (`VarkaEmitTrace`) unchanged over the IR fuzzer's corpus. *Added 6 October 2026*: item 74.2 had no row, since VARKA-236 kept the loop as its last resort | item 74.2, the owner, 6 October 2026 | medium |
| 291 | **Done** (`VARKA-291.md` 9.8, 7 October 2026): layout C, `int[]` columns behind one owning class, is the store and A (FFM rows) the alternative; flat layouts analyze 2.8 to 7 times faster and rebuild about twice as fast warm, FFM costs nothing warm and its cold penalty is a warm-up cost, off-heap columns vectorize but run slower than A and C, and Arrow suits the wire and not the working store; whose schema, and factory methods in rows 214 to 216, are the owner's and open. The IR's storage measured: records against `int[]` columns, FFM struct rows on JDK 25, and value-record rows on the early-access JDK, with the real IR as value records as a fifth arm, on the real IR's graphs up to ten thousand nodes - build time, bytes a node, structural hash and equality, a bottom-up analysis, the cold first run - behind an agreement check, the gate being a 1.3x win on the cold first compile or a factor growing with depth on hash and equality. A spike beside the project, not in its build (`VARKA-291.md`). *Added 6 October 2026* at the owner's request, from item 85's storage question | item 85 of `m9/SCOPE.md`, the owner, 6 October 2026 | medium |
| 292 | **Done** (`VARKA-292.md` 5, 7 October 2026): a narrowed output is mapped at `length * 4`; the six TIME tests pass under the sanitizer and every Varka suite passes with it on; the oracles moved only for narrowed shapes (36 values of the bytes oracle, one price row by 5); the narrowed-store arms of `VarkaTimeBenchmark` are within 1.5% of master's. Narrowed outputs mapped at their own width: the emitter maps every data segment at `length * lane.byteStride`, so the int32 output of a `NarrowLane` root in a long-lane kernel is mapped at twice its size - found by row 263's sanitizer on 7 October 2026, 40 bytes over a buffer of 20 and 80,000 over 40,000, in six TIME tests. The stores are int32 wide and the answers are right; what is wrong is that the bounds check on that output's upper half enforces nothing. Map such an output at `length * 4`. Proof: the six tests pass under the sanitizer, `emitted_bytes.json` moves only for shapes with a narrowed root and the diff is read, and the kernels' time is unchanged on a benchmark pair | row 263's sanitizer (`VARKA-263.md` 4) | small to medium |
| 293 | **Done** (`VARKA-293.md` 9, 9 October 2026): the one-day guard on a literal interval in `timeAddInterval` is a range check, so `Long.MIN_VALUE` microseconds (whose `abs` is negative and whose multiply by 1000 wraps to zero in a lane) declines; end to end Varka now raises Spark's error where it answered | review of #663 | small |
| 295 | **Done** (16 October 2026, `VARKA-295.md` 9.7 to 9.9: the template warm-up of #827 removes the deadline releases at the preferred species, 0 in 2,000 compositions on five seeds against 442 without it at the seed that showed them; what it leaves - a 4-lane override and a 128-bit preferred species - is rows 373 and 374). Earlier: **Step 1 of 9.4 done** (`VARKA-295.md` 9.5, 15 October 2026): the warm-up fuzz arm run seven times with the JIT's own log, 171 releases at the deadline classified, none a deoptimization cycle, a C2 bailout or a policy that never asked for the compile; all are cause 4, every loop method compiled at tier 4 and in use yet one `int[16]` vector array surviving per loop iteration (the floor of 20,480 bytes a probe is 4 calls x 64 iterations x 80 bytes), the same shape compiling clean in another run, so the next step is an intrinsic and escape-analysis replay of one shape, not the CI environment. **Fix applied, cause contradicted; diagnostic extended** (`VARKA-295.md` 9.1 to 9.3): on 14 October it fired three times in the module's 70-minute JVM (`sql - other tests`), each with nothing of the kernel's queued; the last probe's bytes, compared byte for byte with local runs at the runner's width, are one interpreted light-loop call per probe, so part of a light loop runs without compiled code while the queue is empty. Not reproduced locally after the same 754 suites at either width. The release-path state now lists the kernel's compiled methods and the ones with none, and the test's failure carries the JIT's own events for the kernel (compiles, deoptimizations, refused inlining, interpreted allocation sites); the test still fails the module's job, so the next firing is read (`VARKA-295.md` 9.3). Earlier: corrected after #683's CI (`VARKA-295.md` 9.1): the 32x rule admitted a verdict while a light loop was still interpreted and is withdrawn; the likeliest cause is a loop's C2 compile queued over a minute on a loaded runner, so the tests that assert a verdict raise the deadline (`-Dvarka.warmup.deadlineSeconds`, production 60 s), and the release-path diagnostic stays to confirm it. It fired once, on 12 October: the warm-up unstarved, the kernel still mostly uncompiled after 120 s, and both compile queues empty with no C2 compile running, so the uncompiled methods were not waiting for C2 and the inferred cause does not hold for that failure; the report, which cut the queue's text, now keeps 4,000 characters a line, and what keeps the loop methods from compiling is to be read from the JIT's own events (`VARKA-295.md` 9.2). The warm-up end-to-end test that times out on the CI runner: `VarkaWarmupEndToEndSuite`'s first-query test fails about two runs in five on the fork's CI with the warm-up `RELEASED` after its sixty-second budget and never `COMPILED`, and passes on a rerun. Find whether the compile thread is starved on the shared runner, the budget is too tight, or the warm-up has a real stall; then fix the cause or give the test an honest wait. Step 2 (`VARKA-295.md` 9.6, 15 October 2026): the surviving allocation is one `int[16]` per boxed vector that C2 cannot scalar-replace, because two compile-wide sums of shared methods' trap and decompile counts (limits 100 and 201) turned the class-check miss paths into real calls, one earlier composition is enough to tip them, and `-XX:PerMethodTrapLimit=100000 -XX:PerMethodRecompilationCutoff=100000` took 0 of 1,000 warm-ups to the deadline against 10 to 39 of 500. Step 3 (`VARKA-295.md` 9.7, 15 October 2026): the first remedy works. Before its first job the warm-up thread calls every Vector API method the kernels use once per round with every operator class, 300 rounds, once per JVM (`VarkaVectorTemplateWarmup`, 220 to 350 ms on the worker at a load of 24 to 33, off the query's path), so the shared templates' standalone C2 compiles never speculate on one operator class and record no class-check trap: deadline releases in the 400-composition arm 53, 78, 33 without it and 0, 0, 0 (and 0, 0 at the shipped 300 rounds) with it; the reproducer 5 of 6 released without, 0 of 6 with. Verified at volume and across species (`VARKA-295.md` 9.8, 15 October 2026): seed 20261008 had 442 deadline releases without the warm-up and 0 with it, five more seeds 0; at 16 lanes 0 against 19 to 59, but at 4 lanes it does not help (136 to 183 either way), and steady-state throughput shows no on/off difference beyond the JVM-to-JVM spread. Found by VARKA-214's and VARKA-277's CI | VARKA-215 9 | small |
| 296 | **Done** (`VARKA-296.md` 9, 9 October 2026): each fused output of a random composition forced to decline in turn through the compiler's own decline path, behind a test option, with two checks - the projection with the entry forced equals the projection with it deleted (a decline rolls its tables back exactly, which answers alone cannot show), and the answer equals the row engine's, counted only where the executed plan's residual-entry metric rose by one - as Comet's fallback-invariance suite does | VARKA-262 3, `m7/READING.md` 1 | medium |
| 297 | **Done** (`VARKA-297.md` 9, 9 October 2026): pivot-rectified filters (every one of 1,164 returns its row, against 66% of the unrectified) and a wider data pool, no disagreement in 5,000. Filters that select known rows, as PQS rectifies them: each conjunct evaluated on pivot rows and negated where false, so the query must return the pivot; and a wider data pool than the fixtures', reaching values outside them | VARKA-262 7.3, `m7/READING.md` 11 | small to medium |
| 298 | **Done** (`VARKA-298.md` 9, 16 October 2026): `VarkaCatalystGrammar` draws nested trees of neighbouring types into half the Spark-level compositions and the shrinker reduces inside them. Its 35,000-composition census found a Varka bug, a fused conjunct hiding an error an earlier residual conjunct raises in Spark (9.2, row 380, five reproducers kept `known 380`; the nightly's `sparkfuzz` step runs without the nested trees until it is fixed), and earlier runs a harness race between two listed errors in two partitions (9.3, row 381). Nested Catalyst trees with neighbouring types, so the analyzer inserts casts (`VarkaCatalystGrammar`, beside `VarkaIrGrammar`), and the shrinker reaching inside a row's own expression | VARKA-262 3.4, `m7/PLAN.md` 2.2 | medium |
| 299 | **Done** (`VARKA-299.md` 9, 8 October 2026): `VarkaRowToColumn`. Varka's filter fallback raises `UNSUPPORTED_DATATYPE` on a `NullType` (VOID) column: `VarkaFilterEvaluatorFactory.PartitionFilterEvaluator.converter` builds a `RowToColumnConverter` over the child's schema, which has no converter for `NullType` (`VarkaFilterExec.scala:200`), so a batch the kernel declines under a filter over a relation with a `SELECT NULL` column fails where Spark answers. Reproducer: `sql/varka/fuzz/spark/void-column-fallback.sql`. Fix it, check the projection fallback and the other converter uses for the same, then turn the file into a regression. Found by VARKA-262's first long run | VARKA-262 9 | small |
| 251 | **Done** (`VARKA-251.md` 9.2, 6 October 2026): six Java components under `sql/core/.../execution/varka/` - the batch ledger, the scratch, the kernel runner, the warm-up gate, the fallback accounting, the class dump - and `VarkaEvaluatorBase` a Scala composition of them, from 1079 lines to 549; per batch the same as the unchanged evaluators over ten runs at each width, every case's range overlapping and no minimum more than 7% higher. Planned in two pull requests: the per-batch benchmark and its baseline, then the six components. `VarkaEvaluatorBase` split into Java components: runner, batch ledger, scratch, warm-up, fallback accounting, dumping. Proof: a before-and-after benchmark of per-batch overhead | item 74.5 | medium |
| 252 | **Done** (#907; `VARKA-252.md`, 17 October 2026: about 290 comments in 108 of Varka's code files and `dev/` scripts rewritten to say what the code does, with task ids kept as pointers; the two wrong comments corrected, Java's `/` wrapping at `Integer.MIN_VALUE / -1` and the long-lane constant division exact only under `EXACT_DIVIDEND_BOUND`; the sentence in `sql/varka/AGENTS.md` and a pre-commit check for `used to` and `the old` on added lines of Varka's code, with a self-test; `emitted_bytes.json` and the coverage oracles unchanged). Comments that narrate history rewritten to explain the code as it is; and the comments the paper reading found wrong; and the rule kept - a sentence in `sql/varka/CLAUDE.md` that a comment describes the code as it is, and a pre-commit check on added lines for `used to` and `the old` | item 74.7, `m7/READING.md` 11, `m7/READING_POLARS.md` 9 | small, last |
| 214 | Port `VarkaTimeCompiler` to Java. **Done** (`VARKA-214.md` 9, 8 October 2026). Proof: the coverage and family-chain oracles, and VARKA-191's emission times | item 81 | mechanical; an agent's |
| 215 | Port `VarkaConditionCompiler` to Java. **Done** (`VARKA-215.md` 9, 8 October 2026). Proof: as 214's | item 81 | mechanical; an agent's |
| 216 | Port `VarkaChronoCompiler` to Java. **Done** (`VARKA-216.md` 9, 8 October 2026). Proof: as 214's | item 81 | mechanical; an agent's |
| 217 | **Done as 217a** (`VARKA-217.md`), cut in two on 8 October 2026 at the owner's choice: the node compiler the families call (`VarkaNodeCompiler`), `DeclineSink` and `VarkaShapeCache` are Java, with no consumer changes. The classifier, the result type, the size admission apart from node compilation and the data model are row 300. Proof: as 214's | items 81 and 74.4 | medium |
| 300 | **Done as 300a** (`VARKA-300.md` 9, 10 October 2026), cut in two that day: 300a, the data model (`CompiledVarkaProjection`, the output specs, the declines, the predicate types) as Java records, the Scala classifier constructing them and the Java evaluators without converters; 300b is row 305. `coverage.json` and `emitted_bytes.json` unchanged, and a 4,436-line dump of every fusion decision over the coverage rows, under seventeen option sets, byte-identical before and after | VARKA-217 2 | medium |
| 305 | **Done** (`VARKA-305.md` 9, 11 October 2026), one pull request in two commits: the families and `DeclineSink` off `scala.Option` and the Scala tables onto a Java slot table, then the classifier to Java with its result record, the size admission in its own class and the predicate pass as a record. The facade has no Scala left; the fusion dump is byte-identical after each commit, `coverage.json` and `emitted_bytes.json` unchanged, and every compile shape is faster: the projections by more than half, after the review dropped a second classification of an admitted kernel, and the predicates by 12 to 31%. 300b: the classifier (`compilePartial`, the kernel rounds, the size admission, `compilePredicate`) to Java with a result record for the triples, a table type for the Scala `LinkedHashMap`s and their hand-written `truncate`, the size admission in its own class, and the families and `DeclineSink` off `scala.Option`. Proof: as 300a's, with `VarkaCoverageSuite`'s opt-in fusion dump identical on both sides, and `VarkaCompileBenchmark` rerun and committed | `VARKA-300.md` 2 | medium to large |
| 301 | **Done** (`VARKA-301.md` 9, 10 October 2026): `arrowBatchSize=1`, `=3` and `=17` are matrix configurations the shared sessions apply as the Arrow cache's `maxRecordsPerBatch`, a test checks the cache really splits, and the two tests that set the ignored knob set the one that works; every Varka suite at each size gives no answer that differs, and five metric assertions small batches leave at zero go to the skip list with their reasons | `m7/READING_POLARS.md` 4 | small |
| 224 | **Done** (`VARKA-224.md` 9, 11 October 2026), the Java benchmark within 5 per cent of the Scala one on every row over five interleaved rounds and the dump's output unchanged over fourteen cases: a Java adapter over Spark's benchmark harness - a base class that extends `BenchmarkBase` and needs no `main` of its own, since JDK 25's launcher calls the inherited one in a forked run - with `VarkaCompileBenchmark` ported onto it and reproducing its results against the merge base, and `VarkaEmitDump` in Java with `dev/varka_emit.sh`'s output unchanged. Benchmarks and tools in Java: the harness adapter and `VarkaEmitDump` | item 81 | small |
| 267 | **Done** (`VARKA-267.md` 9.5, 9 October 2026), in three pull requests - the leaves (the metrics, the selection, the fusion report, the vector projection), the base with the filter evaluator and the kernel part, then the projection evaluator. The evaluators to Java beside 251's components: `VarkaKernelEvaluator`, `VarkaFilterEvaluator`, `VarkaVectorProjection`, `VarkaFusionReport`, `VarkaExecMetrics`, and the Scala `VarkaEvaluatorBase` and `VarkaKernelPart` that 251 leaves; the exec nodes and the columnar rule stay thin Scala wrappers. Proof: as 251's | the plan review (2.3) | medium |
| 268 | **Done** (`VARKA-268.md` 9, 12 October 2026): `VarkaEmitterFixtures` (Java) holds the emitter suites' helpers - the emitted class, the columns, the data and the three sweeps - and `VarkaEmitterTestBase` is a thin Scala layer over it, 787 lines down to 465, with the two matrices that drive the reference evaluator; no suite changed, the 17 suites give the same 249 test outcomes, the data equal over 152,870 comparisons and the sweeps as fast. *Optional.* `VarkaEmitterTestBase`'s helpers to Java, the suites thin wrappers over them | the plan review (2.3) | medium |
| 310 | **Done** (`VARKA-310.md` 9, 12 October 2026): `VarkaReference` (Java) holds the reference semantics in four exhaustive switches over the sealed IR, and `VarkaReferenceEvaluator` is a 54-line forwarder, no caller changed; the two forms agreed on 11.5 million answers over the fuzz grammar's trees, a planted fault failed the fuzz suite, and the fuzz test runs as fast. `VarkaReferenceEvaluator` to Java: the oracle every kernel check compares against, a 335-line Scala object over `Seq[Option[Int]]` rows that 8 test files call, as a Java class with an exhaustive `switch` over the sealed `VarkaVectorIR` and rows as a values array with a nulls mask; a one-line Scala forwarder keeps the 8 callers unchanged, as 268 kept its suites. It is the one reason `VarkaKernelCheck`, `VarkaVariantCheck` and `VarkaGuardSpec` stay Scala. Proof: before the Scala body goes, both evaluators over the IR fuzz grammar's trees at every lane, null pattern and literal draw, answer for answer, recorded and not committed, as 268's data check was | the owner, 12 October 2026 | small |
| 311 | **Done** (`VARKA-311.md` 9, 12 October 2026): the three checks are Java (`VarkaKernelChecks`, `VarkaGuardSpecs`, `VarkaVariantChecks`) and their Scala objects forwarders, no suite changed; the same outputs compared before and after, to the row, every planted fault caught, and fuzz failures now classified by kind and showing what the kernel stored. `VarkaKernelCheck`, `VarkaVariantCheck` and `VarkaGuardSpec` to Java over 310's evaluator, each a thin Scala trait over the Java class so no suite changes; the helpers most kernel suites lean on, so the port that moves the most test code. Proof: as 268's - every suite that mixes them in gives the same test names and outcomes on the branch and on master | the owner, 12 October 2026 | medium |
| 312 | **Done** (`VARKA-312.md` 9, 13 October 2026): a Varka suite can be a JUnit 5 class under `@VarkaSuite`, its tests named by `@DisplayName` sentences that sbt's output, the matrix's report, the XML and the skip list show and match as ScalaTest names; the harness's logic is a Java core with a ScalaTest and a JUnit face, one listener prints ScalaTest's lines for those classes only, and `VarkaSuiteRunner` runs both kinds in a matrix JVM with one combined summary; `VarkaMethodNamesSuite` ported with its five names and outcomes unchanged at the defaults and under all 50 configurations, a planted skip entry matched by its sentence, every catalyst test outcome identical before and after, and the runner's cost 26 ms a JVM, 0.4 s with a JUnit class; `sql/varka/AGENTS.md` now says a new catalyst suite whose helpers are Java is JUnit. A Varka test suite can be JUnit 5: the matrix's skip list (`sql/varka/matrix/skips.tsv`), the `PinsDefaults` cancellation, the watchdog and the own-JVM marker reach a JUnit suite as an extension, and `dev/varka_matrix.sh`, `dev/varka_scoped_suites.sh` and the gate select and report it as they do a ScalaTest suite; then `sql/varka/AGENTS.md`'s Java rules say a new suite whose helpers are Java is written in Java. Today all four are ScalaTest mixins, which is why the rule cannot land before them. A JUnit test's name reads as a ScalaTest one does, the owner's requirement of 12 October 2026: each test carries its sentence (`@DisplayName("a reduction that fails differently is not smaller")`, not `reductionThatFailsDifferently()`), and sbt's run, the gate's logs, the matrix's reports and the skip list show and match that sentence when a test starts, passes, fails or is skipped - Spark's runner (`jupiter-interface`, `-v -a`) prints the class and method by default, so whether it can print the display name is the first thing the plan establishes. Proof: one existing small suite ported as the example, its test outcomes the same at the defaults and under the matrix, a planted skip entry cancelling its test, and its names as printed beside the ScalaTest form's | the owner, 12 October 2026 | medium |
| 313 | **Done** (`VARKA-313.md` 6.1 and 9, 16 October 2026: on runners, repeated on one CPU, the exact-status arm is 2.5% to 3.4% slower null-free and 1.3% to 1.4% faster with nulls wide, and 3.7% to 3.9% slower narrow, so by the 3% rule it is not the default: the owner's decision is that it stays a flag for the session that diagnoses; 14 October 2026: ten reason metrics that read "not reported" where a folded mask left a reason unattributed, rows and time per path, the filter's `EXPLAIN` with its residual conjuncts, a suite with planted and real declines under AQE too; the exact-status benchmark section is committed and was run once at each width, 6.1, the default unchanged. Admission check: the nodes already count batches by six causes, so item 65's "no SQL metrics" is stale and the gap is per-reason counts, rows and time per path; the status word is always recorded and only a folded decline lacks attribution; the filter's `EXPLAIN` never printed its residual conjuncts' reasons; the exact-status cost measured on runners, 6.1). Why a query did not use Varka, said where users look: per operator, SQL metrics for the batches a kernel served and the batches that fell back, by refusal reason, with rows and time through each path, and the compile-time declines in `EXPLAIN` (`m9/SCOPE.md` item 65, taken in). A refused batch names its guard only under `exactRefusalStatus`, internal and off by default (`docs/sql-varka.md`), so production learns that a batch fell back and not why: the row first measures what exact status costs on the benchmarks, and makes it the default if that is within the run's noise, or samples one batch in N if not. A reason that was not recorded shows as "not reported", never as 0 (Polars's metrics keep the two apart, `READING_POLARS.md`), the reasons survive adaptive re-planning (`m9/SCOPE.md` item 28 found Gluten losing them there), and tests assert which reason a query fell back for, as Comet's `checkSparkAnswerAndFallbackReason` and Gluten's `failOnFallback` do (`READING.md` 2). Done when the metrics show in the UI and `EXPLAIN` for a fused stage, a suite asserts them on a query with a planted per-reason decline, and the measurement is committed | the owner, 12 October 2026; item 65 | medium |
| 314 | **Done** (`VARKA-314.md` 9.3, 16 October 2026: 314b raises Spark's `INTERNAL_ERROR` from the remaining 110 invariant sites of Varka's main code, each with its own text and in the Java type it threw before - `UnsupportedOperationException` included, through Spark's carrier of it - and a scan of the main code fails on a raw one outside four parser errors and two test injections; the planning catch now puts the record around a site's error instead of passing it through without one, which the conversion would otherwise have caused; `docs/sql-varka.md` lists each fallback cause, its warning and what to do; `emitted_bytes.json` is unchanged and every Varka suite passes at the defaults. 9.1, 13 October 2026: a planning-time Varka bug now fails the query with Spark's `INTERNAL_ERROR` and a context record - the entries, the options that differ from the defaults, the cause - instead of a bare `IllegalArgumentException`; the fallback warnings and the three refusals under test carry the record with the shape hash and the failing batch, and the fallback event names the options. 9.2, 14 October 2026: the steady-state test fired once in the option matrix's run after a merge, reading a JVM whose compiler had not reached the kernel's loop and not the failure path - the kernel's bytes do not depend on the configuration drawn, and excluding the kernel classes from the JIT reproduces the read exactly - so it now takes a control before the failures and cancels with the JIT's account of the class when the control never reads clean). Planned (`VARKA-314.md`; admission check: of 121 throw sites in Varka's main code, those at planning time reach the user as raw exceptions, since the compiler runs on the driver with no catch, while code-generation and per-batch failures fall back to the row path, and the one user setting already raises `INVALID_CONF_VALUE.REQUIREMENT`; the owner decided on 13 October 2026: no new error names, every invariant `INTERNAL_ERROR` through Spark's unchecked carriers, with a context record). Varka's user-facing errors as Spark error conditions: every exception a query can surface from Varka - a decline that cannot fall back, a configuration out of range, a kernel that fails to load - raised as a `SparkThrowable` with an error condition and its parameters in Spark's error-class registry, instead of free text, and each condition listed in `docs/sql-varka.md` with what to do about it. Today Varka's main code raises no error condition. Every throw site is decided as a user error or an internal one, as Velox (`VELOX_USER_CHECK` against `VELOX_CHECK`), Trino (`ErrorType`), DuckDB (`INTERNAL`) and DataFusion (`Internal`) split them: a user error is a named condition that says what to do, a kernel or emitter bug is `SparkException.internalError` with a request to report it. The message's context is one record built only when something throws - the expression's IR, the shape key, the options that differ from the defaults - in the manner of Velox's `ExceptionContext`, so the per-batch path allocates nothing; row 315 writes the same record as its file. Done when a test reaches each condition and checks it with `checkError` | the owner, 12 October 2026 | small to medium |
| 315 | **Done** (`VARKA-315.md` 9, 14 October 2026): a kernel class that fails to verify or define, an exception inside a kernel and a sanitizer violation each write a reproduction file - the failure's signature, 314's record, the shape key, the literals, the batch as the kernel read it and the class - on by default under `java.io.tmpdir/varka-reproductions`, bounded per JVM and by a batch-byte cap; `dev/varka_emit.sh --replay` rebuilds the kernel from the file's canonical IR (a new parser, held to the shape hash) and replays it; each kind, planted through a fault injector (two new: `dropStackMaps`, `misdescribeDataBytes`), replays to its signature. Planned (`VARKA-315.md`; admission check, 14 October 2026: a class that fails to verify surfaces at the emission in production and at planning under test, where the admission verifies it and nothing records it; a sanitizer violation passes every catch unrecorded; the class dump never holds a class that failed to load; `dev/varka_emit.sh` takes SQL text only and nothing parses the canonical IR back; the emitter's plan fragment is `shape <hash>`, so the shape key and 314's record are what the file carries; only the link failure has a plant today (`misdescribeAdd`), so two fault injectors are added; the batch is the kernel's inputs as it read them, a derived input included; on by default under `java.io.tmpdir`, bounded per JVM and by a batch-byte cap). A reproduction file when a kernel fails: a generated class that fails to load or verify, a memory-sanitizer violation, or a run-time exception inside a kernel writes one self-contained file - the canonical IR, the options that differ from the defaults, the plan fragment and shape key the emitter already takes, and the failing batch where there is one - which `dev/varka_emit.sh` replays to the same failure. The file is row 314's error context written out, headed by the failure's `VarkaFailureSignature` kind so a replay is compared with it mechanically, after Velox's `persistDataAndSql` and `ExpressionRunner` (`READING.md` 4) and the JVM's `hs_err` report. Today the class dump must be configured beforehand and the rest reconstructed by hand. Done when a planted failure of each kind writes a file that replays to the same signature | the owner, 12 October 2026 | medium |
| 316 | **Done** (`VARKA-316.md` 9, 15 October 2026: `### Troubleshooting` in `docs/sql-varka.md`, a tree from three symptoms to steps and a table of who reads what; `VarkaRunbookSuite` walks each branch on a planted fault; findings: no command shrinks a user's query, a verifier failure at planning is still a bare `VerifyError`, no fault injector changes an answer). A troubleshooting runbook in `docs/sql-varka.md`: a decision tree from the symptom - a wrong answer, a slow query, a crash - to the steps: turn Varka off and compare, shrink with the fuzzers' shrinker; read row 313's metrics; dump the class, replay row 315's file, read it with `javap` and the VM flags `SKILLS.md` records. Each error condition's entry names its reader and what that reader does, after Druid's personas (user, operator, developer). Done when each branch has been walked once on a planted fault of its kind and the page says what each step showed | the owner, 12 October 2026 | small |
| 317 | **Done** (`VARKA-317.md` 9, 16 October 2026: the coverage differential's private outcome comparison is shared as `VarkaMaybeThrows` - `checkSparkAnswerMaybeThrows`, both raise the same error, condition, SQLSTATE, parameters, message and class, or both answer the same rows - and its ANSI form, domain check and ten double `intercept`s of `VarkaDifferentialSuite` and `VarkaTimeArithmeticSuite` run through it; a kernel planted with `checkIntOverflow` off answers the extremes row's `i + 1` with the wrapped `Int.MinValue` and fails it as "only Spark raises", under the defaults and under that configuration). Errors compared as answers are: a differential check that holds Varka and Spark to the same outcome on a row where Spark may raise - both raise the same error condition, or both answer the same - as Comet's `checkSparkAnswerMaybeThrows` does, the one check `m9/SCOPE.md` item 24 names as missing here. Today a kernel that answers a row Spark raises on is caught only where the Spark oracle sees that row. Done when the ANSI rows of the coverage differential run through it and a planted kernel that answers an overflowing row fails it | the owner, 12 October 2026; item 24 | small to medium |
| 318 | **Done** (`VARKA-318.md` 9, 16 October 2026: six settings `spark.sql.codegen.varka.family.<name>.enabled` for the compiler chain's calendar, interval, time, condition and int arithmetic families and for the predicate family beside the chain, split from condition on the owner's decision of 16 October 2026 so that the comparisons and connectives, in a filter or in a conditional's condition, have their own switch (`VARKA-318.md` 9.4), bound in `VarkaSessionEmit` beside the emit settings and not emit options; a family that is off declines every node it claims or builds with the reason naming its switch, a failure inside it at planning ends its `INTERNAL_ERROR` with "set <switch> to false and retry", and every failure record names its kernel's families with their switches; `VarkaFamilySwitchSuite` turns each family off over the 92 coverage rows and `VarkaFamilySwitchQuerySuite` sets each switch on a query; the coverage table and `coverage.json` name each row's switches from the compiler's record, and the docs' switch table is generated from `VarkaFamily`). A kill switch per expression family, named in the messages about it: a configuration that turns one family's fusion off (the calendar, the conditionals, the checked arithmetic...), and every decline, error condition and runbook step about that family naming it, as StarRocks's "set jit_level = 0 to disable jit and retry" does (`READING.md` 3). Today the only switch is all of Varka. Done when each family's switch is documented, a test turns each off and sees the family decline with its reason, and the messages name it | the owner, 12 October 2026; items 24, 29 | small to medium |
| 319 | **Done** (`VARKA-319.md` 9, 15 October 2026: `sql/varka/fuzz/expected_errors.tsv` lists six entries, each with side, modes, families and a reason; the Spark-level fuzzer, its forced and warmed runs and its partition check treat a raise on the list as expected and any other condition as a failing finding naming the condition; a planted `DIVIDE_BY_ZERO` fails it. Two 5,000-composition runs each met a condition the 3,000-composition census had missed, both missing entries, none a Varka bug) An expected-errors list in the differential fuzzers, after SQLancer: the conditions Spark and Varka may raise on a drawn case, listed with why, and any other error a bug. Today the composition fuzzer compares `getCondition` (VARKA-262) but has no list, so an unexpected condition and an expected one are read alike. Done when the list is committed with a reason per entry and a planted unexpected condition fails the fuzzer | the owner, 12 October 2026 | small |
| 320 | **Done** (`VARKA-320.md` 9, 12 October 2026): the grammar, the series, the shrinker, the case, the signature and the known failures are Java, 2,434 lines from 1,645; `emitted_bytes.json` byte-identical with no regeneration, and the first 6,000 drawn cases and every series tree to size 4 (a count and hash to size 5) byte-identical before and after; the composition shrinker and its case stay Scala, Catalyst interop, and no Scala forwarder is left, since a Scala object cannot share its name with a Java class in the package, so the suites call the Java classes. The fuzz infrastructure to Java: `VarkaIrGrammar` (707 lines), `VarkaIrSeries` (415), `VarkaShrinker` (306), `VarkaCompositionShrinker` (138), `VarkaFuzzCase`, `VarkaFailureSignature` and `VarkaKnownFailures`, each Scala object a forwarder while its suites are Scala. With the checks in Java (311) these are what still tie the fuzzers to Scala. The grammar must draw the same trees from the same seed, call for call, because `VarkaEmittedBytesSuite` pins the bytes of the shapes it draws: the port proves itself by `emitted_bytes.json` byte-identical with no regeneration, and by the IR fuzz suite comparing the same outputs before and after (311's row counter), the known-failures replay and the shrinker suite passing unchanged | the owner, 12 October 2026 | medium |
| 321 | **Done** (`VARKA-321.md` 9, 12 October 2026): `VarkaWordCensus`, `VarkaInliningCliffProbe` and `VarkaKernelWarmupProbe` are Java with their names, arguments and output, the census byte for byte at two seeds and the probes' verdicts the committed file's; the two codegen censuses stay Scala, since they drive Spark's ScalaTest suites and walk its plan classes, the interop surface the rules keep Scala. The censuses and probes to Java: `VarkaWordCensus` (403 lines), `VarkaCodegenGiveUpCensus` (363), `VarkaMethodSizeCensus` (254), `VarkaInliningCliffProbe` (230) and `VarkaKernelWarmupProbe` (196), standalone programs with few dependencies. Mechanical, and an agent's, as the family ports were. Each proves itself by printing what the Scala form printed on the same input - a committed report reproduced, or its output diffed against the Scala form's run the same hour | the owner, 12 October 2026 | small; an agent's |
| 322 | **Done** (#900, 17 October 2026: the last two classes, `VarkaColdStartBenchmark` and `VarkaWarmupDirectiveBenchmark`, in Java; their first runner pair failed on rows moving in both directions, the warm-up ending `COMPILED` in some JVMs and `RELEASED` in others on a 4-core runner, with the base against itself as spread, 16 rows slower and 20 faster; the cause is `m9/SCOPE.md` item 103, and the close states the cold-start headline with that spread. Until then: `VARKA-322.md`; 322a done, its tenth pair closed without a pair (`VARKA-322.md` 9, last paragraph): all eleven classes in Java under their names - the ten in #780, `VarkaEmitterParityBenchmark` in #791 - their dry-run ids and their section, benchmark and case names identical to the Scala forms', and ten pairs run on GitHub-hosted runners on 14 October 2026, at most five rounds each, both sides on one machine (`VARKA-322.md` 9): no class has a row slower by 10% by both the median and the best, `VarkaTimeBenchmark` has 2 rows faster by both and the parity class 6, the small-chunk epilogue ladder among them; the two per-row references to Spark's path that the laptop pair of the parity class had read 10% to 15% slower read -8.1%/-4.7% and +13.3%/+13.3% on an EPYC 7763 under another JDK build, so the laptop's finding does not reproduce there and the cause question stays open; the widest medians, up to 12,173%, are rows bimodal across JVM forks or under a millisecond whose bests hold within 17%, and the Java side's A/A read 16.2% by best on one emission row; `VarkaWideKernelBenchmark`'s pair cannot run, its base stops at the sixth section on every machine (`VARKA-255.md` 9.3), so it rests on identical ids and names and the first five sections matching the laptop file; 322b implemented, its pair pending (`VARKA-322.md` 9, "322b"): `VarkaSqlBenchmarkBase` in core's test tree - the default session made on first use, `withConf` restoring every value it set and unsetting one that was unset, `noop` and `note`; `addTimerCase` left for its first user in 322c - and `VarkaSizeLadderParallelBenchmark` in Java on it, its dry run's 37 lines (section, benchmarks and their settings, cases, rung notes) identical to the Scala form's, its pair owed on a GitHub-hosted runner; 322c-1 implemented, its pairs pending (`VARKA-322.md` 9, "322c-1"): `VarkaSizeLadderBenchmark`, `VarkaSizeLadderTuningBenchmark` and `VarkaSchemaWidthBenchmark` in Java with the shared `VarkaSizeLadder` helper, the schema-width benchmark the first committed user of `withConf`, their dry runs' 142 lines identical to the Scala forms' and their names, notes and method byte counts matching the committed laptop files, the tuning class's `-dontcompilehugemethods-off` file included; 322c-2 implemented, its pairs pending (`VARKA-322.md` 9, "322c-2"): the other eleven classes in Java, `VarkaArrowSessions` alone left in Scala, after a commit sharing `require` and `clearSessions` in the two bases; `addTimerCase` with `VarkaColdPathBenchmark`, `withEmitOptions` with the cold-start and range-filter benchmarks; run with every case executed once or as dry runs, their sections, benchmarks and settings, cases and notes identical to the Scala forms' but for zero durations printed as `0 days` and warm-up verdicts that vary on both sides, and matching the ten committed results files with a table; the directive benchmark has no table and so no pair; admission check: the 26 classes are eleven on `BenchmarkBase`, `VarkaCodegenBenchmark` in `sql/core` among them, and fifteen on `SqlBasedBenchmark`, not twelve and fourteen; the adapter serves the eleven with additions - on `VarkaBenchmark`, two methods for selecting cases and the `timed()` preset; on `VarkaBenchmarkBase`, the dry run, case filter and case ids, a guard that refuses either while a results file is written, `require`, `requirementFailed`, `defineKernel`, `roots` and `seq` - and Spark's `Benchmarks` runner, the path `benchmark.yml` takes, invokes the instance `main` a Java benchmark inherits; none of the fifteen uses `withSQLConf`, and the pair runner already reads best times and runs A/A). The rest of the Scala benchmarks to Java, 26 classes and 8,283 lines, in three steps on row 224's pattern (its port of `VarkaCompileBenchmark`, within 5% of the Scala one on every row). They are two kinds. Twelve extend Spark's `BenchmarkBase` and emit and run kernels directly - `VarkaCodegenBenchmark` (91) to `VarkaWideKernelBenchmark` (458) and `VarkaEmitterParityBenchmark` (2,191) - and 224's adapter, `VarkaBenchmarkBase`, already serves them. Fourteen, all in `sql/core`, extend `SqlBasedBenchmark`, a Scala trait with the shared session, `withSQLConf` and the query timings, which Java cannot mix in, and no adapter serves them yet. **322a**: the twelve on the existing adapter, the small ones first and the parity benchmark, a fifth of the whole with its hand-written reference kernels, last on its own. **322b**: a Java counterpart of `SqlBasedBenchmark` in core's test tree, proved on `VarkaSizeLadderParallelBenchmark` (115) as 224 proved the first adapter, its `withSQLConf` restoring every configuration it sets so no case leaks into the next. **322c**: the other thirteen on it, up to `VarkaThroughputBenchmark` (518). Each class keeps its name, sections and case names, so its results file diffs line for line, and proves itself with `dev/varka_bench_pair.sh`, the Scala class as the base and the Java one as the head, every case within its band or within 5% where no band is committed. Before 322a's first pair, two changes to how that is read: the pair verdict compares best times as well as medians - 224's pair read the Java side slower on all eight rows by median and equal or faster on seven by best time, its rows drawn from the same five runs - and one Java-against-Java pair at one commit measures the runner's own spread, so that a port's penalty can be told from it. 224 ported one class to prove the adapter, and the rule since ports a benchmark only when a task touches it (`sql/varka/AGENTS.md`); this row ports the rest, so no new benchmark copies a Scala one | the owner, 12 October 2026 | medium to large; in three steps |
| 323 | **Done** (`VARKA-323.md` 9, 13 October 2026): a variant that can grow a draw takes a form the emitter's form without a budget holds where one exists - a stranger from the draw's other roots, an untaken arm's replacement from the pool scanned once round - and one with no such form, declined by that form, is counted as skipped past the op cap instead of failed; the five draws of the night replay passing, three compared and two skipped, and the default seed's variant counts are unchanged. A variant stays within the op cap its draw was drawn for. *Found 13 October 2026 by VARKA-320's night run*, on master as on the branch: 5 of 80 IR fuzz chunks of 60,000 draws failed, all one way, and replaying seed 2026101307001 at iteration 47340 (`-Dvarka.fuzz.only`) fails the same in seconds on both. The grammar keeps a draw well inside `MAX_FUSED_NODES` = 64, but two of row 278's variants grow it - an untaken arm replaced by a larger value subtree of the draw (four of the five) and a stranger root added beside it (one). Under the byte budget the grown variant declines on size, as it may; `emitOrSkip` then emits it without the budget, the one form capped by op count, and the emitter's `IllegalArgumentException` ("more than MAX_FUSED_NODES=64 distinct ops") fails the test as a rejected shape. No answer was wrong, and the failure stops the chunk's remaining draws. Done when the variant builder keeps each variant within the cap - another replacement subtree or stranger where one fits, and a variant none fits counted and reported as skipped, as shapes past the class-file cap are - the five seeds replay passing, and a test pins seed 2026101307001 at iteration 47340 | VARKA-320's night run, 13 October 2026 | small |
| 324 | **Done** (`VARKA-324.md` 9, 13 October 2026): the int lane's column values, drawn by a floor modulus in both the drawn cases and the wide compositions, now put 5 of 434,669 values at `-bound` over the first 2000 draws where 217,652 were, and the reach test draws the long corpus from the long seed and still finds all 15 long node types, with `emitted_bytes.json` unchanged. The IR fuzzer's int lane draws its column values evenly, and the small-tree reach test compares the long series with the long grammar. *Found 13 October 2026 by the review of #770 (VARKA-320)*, both older than it. `VarkaIrFuzzSuite.drawInt` draws a column value as `rnd.nextLong() % (2 * bound + 1) - bound` and then clamps it to `[-bound, bound]`; a signed remainder is negative half the time, so about half of every int-lane column - day columns and the month column alike - is exactly `-bound`, where `drawLong` already uses `floorMod` and says why. `VarkaSmallTreeSuite`'s reach test draws the long lane's comparison corpus from `FUZZ_SEED`, the int lane's seed, rather than `LONG_FUZZ_SEED`, so it checks the series against long shapes neither the long fuzzer nor the bytes oracle draws. The first fix changes only the int-lane cases' column values, since it takes the same one `nextLong` a value: the shapes, the options and every later draw stay, `emitted_bytes.json`, which pins shapes, does not move, and known-failure entries that replay a seed and iteration are replayed again. Done when the int draw uses `floorMod` with a test that its values spread over the range, the reach test draws from the long seed and still finds every node type it asserts, and the known-failures replay passes | the review of #770, 13 October 2026 | small |
| 325 | **Done** (16 October 2026, with the wide-kernel pair closed without one, below; built 13 and 14 October 2026): the sixth section takes the fewest fields of sixty-four dates the emitter splits, nine today, 576 outputs split 524 + 52 over 64 and 52 inputs, and the benchmark splits a shape as the compiler does - a planned prefix asked again, the compiler's halving, each kernel's inputs numbered by first use and its bytes kept from the emission that built it; both classes' dry runs build every shape. Both results files were regenerated on an idle machine on 14 October 2026 at this branch's head, `VarkaWideKernelBenchmark`'s with every row within a few percent of the 3 October file. `VarkaEmissionBenchmark`'s pair against the merge base ran to the end: 28 shared rows, none 10% apart, the widest 2.7% by median, after three rounds a side. On 14 October 2026 the Emission pair ran again on a GitHub-hosted runner (an EPYC 7763, five rounds): 28 rows, none 10% apart by both measures, the widest 33.2% by median on the emission row the runner's own A/A moves most and 2.6% by best; and both results files were regenerated on runners (`VarkaWideKernelBenchmark` on an EPYC 7763 in 2 h 55 min, `VarkaEmissionBenchmark` on an EPYC 9V74 in 13 min) but stay as artifacts, since a results file is regenerated on the machine that measured it and the committed ones are the laptop's (`VARKA-322.md` 9, "Row 325's pair and its two runner files", with the cross-machine differences). `VarkaWideKernelBenchmark`'s pair is closed without one (`VARKA-255.md` 9.3, `VARKA-322.md` 9, last paragraph): its merge base stops at the sixth section's `require`, the bug this row fixes, so the base can never finish on any machine, and the done-when's last clause is met for `VarkaEmissionBenchmark` by its pair and for the wide-kernel class by the regeneration within a few percent and the identical ids. `VarkaWideKernelBenchmark` finishes a run again, and `VarkaEmissionBenchmark`'s results file covers all four of its sections. *Found 13 October 2026 by VARKA-322a's port runs*, on master's Scala class as on the Java port. The benchmark's sixth section compares one kernel against the shape split into several, and checks first that its shape of sixty-four dates by field, 256 outputs, does not fit one kernel; today's emitter fits it in one, so the check stops the run with "fits one kernel: nothing to compare" and the seventh section never runs. The committed results file of 3 October split the shape 181 + 75; the likeliest cause is VARKA-254's grouping change of 12 October, which grouped by predicted bytes. `VarkaEmissionBenchmark`'s committed results file predates its fourth section, added the next day. Done when the sixth section draws a shape that still splits under today's emitter, chosen by the emitter's own split rather than a fixed size so the next grouping change cannot silently empty it, both files are regenerated on an idle machine, and the port's pairs for both classes run to the end | VARKA-322a's port runs, 13 October 2026 | small |
| 326 | **Done** (`VARKA-326.md` 9, 16 October 2026): 2,234 drawn kernels over three arms, read from the JVM's own trap log by VARKA-189's rule, and every one of the 104 that cycled is a NULL-mode kernel - an `IntArith` in NULL mode or a `MakeDate` that does not fail on error, so the emitter builds no dense side - cycling on its masked loop, the fewer its roots and input columns the more often: 47 of 204 at the preferred width with the matrix base, 18 of 204 at 128 bits, 39 of 163 with drawn options, none of the 1,663 others. The cycle ends at the JVM's cap of 100 traps a method (raised, it never ends), after up to 24 s; `-XX:-UseProfiledLoopPredicate` removes it; and it depends on what ran before in the JVM, so a kernel that cycles in sequence settles alone. No guard in the nightly covers these shapes: the deopt step forks one dense `make_date` kernel per fresh JVM and reads `loopDense` only, and the compiled test prints an unsettled kernel without failing. A guard and the masked loop are candidate rows (`VARKA-326.md` 9.4). The deoptimization cycle over the fuzz grammar's shapes, censused. *Found 13 October 2026 by VARKA-274's admission check* (`VARKA-274.md` 3.7): 3 of 40 drawn kernels, each of 1 to 3 roots with only a masked driver, cycled on `profile_predicate` for 30 s on the warmed path, 97 to 110 traps each, one with C1 on as well, where the nightly's guard forks one `make_date` kernel only, so the tests do not see how often a kernel users run never settles. Done when the cycle is counted over the grammar's shapes from the JVM's own trap log, the shapes that enter it are named, and the record says whether a guard in the nightly covers them; a change to the emitted loop, if the census calls for one, is a scope item of its own | VARKA-274's admission check, 13 October 2026 | small |
| 378 | **Done** (`VARKA-378.md` 9, 16 October 2026): the nightly's `deoptnull` step runs `dev/varka_deopt_census.sh --guard`, draws 0 to 99 a lane at seed 20260903 under the matrix base, one JVM per width one after the other on three pinned cores, and counts the kernels still trapping at `profile_predicate` at the warm-up's 10 s deadline; it fails above the ratchet committed in `dev/varka_deopt_census_guard.tsv`, 8 at the preferred width and 3 at 128 bits, the most of three runs on a loaded laptop (8, 8, 2 and 2, 3, 3: the load slows the cycle, so more kernels are caught at the deadline; a quiet machine's census counts 2 and 3, so the preferred width's is to be lowered from the first quiet nights). A recorded cycling kernel planted beside a real run's logs takes the 128-bit count to 4 and fails the step, and the reader's self-test, run before each guard, plants one in six cases; raising the trap limits did not plant one (the loops trap hundreds of thousands of times yet settle, a form neither the warm-up nor this count sees). The step took 11 to 15 minutes at loads of 21 to 46, against no budget for the nightly as a whole, the size of the `sweep` or `sparkwarm` step. A nightly guard over the deoptimization cycle of the NULL-mode kernels (`VARKA-326.md` 9.4, #851): every kernel that cycled in the census holds a null-propagating `int` add or subtract or a `make_date` and so has no dense loop, the nightly's deoptimization step reads dense loops only, and the compiled fuzz test prints an unsettled kernel without failing. `dev/varka_deopt_census.sh` over a fixed draw range, one JVM per lane width (512 and 128 bits), run nightly, recording the kernels past the 10 s cut and their `profile_predicate` traps; since the class cycles today, the guard ratchets: it fails when the count rises above the committed one and the committed count falls with any fix. In this milestone because the census showed the gap in the guard that exists. **Done when** the step runs in `dev/varka_nightly.sh` with its count committed, a planted cycling kernel above the count fails it, and the step's time is stated against the nightly budget | `VARKA-326.md` 9.4 | small |
| 379 | **Done** (#874; `VARKA-379.md` 9 to 11, 17 October 2026: the bound checked inside the kernel's loop as a running minimum and maximum of the stored days; the 10-round pair on an EPYC 7763, run 39038439214, has `datediff + 1`, `year * 100 + month` and `yearofweek` within 2% by best and `weekofyear` 4.5% slower by median and 4.8% by best, a cost the owner accepted at the merge; moved to milestone 8 for an evening by #915 and back here by the merge):  the date column bound noted wherever an answer rests on it and checked per batch inside the kernel's own loop, a range guard on the column the kernel loads anyway, 24 of the 51 coverage rows over a date bounded and the rest held to declining nothing. A first build checked it in a separate pass before the kernel, which the pair measured 9 to 17% slower on four rows; the guard replaced it, and the emitted bytes of those 24 rows moved. A cached date column holding a day outside 0001-01-01 to 9999-12-31 gets wrong calendar fields from the fused kernel, served with nothing declined: the range analysis reads a bare column as that range and nothing checks it. Past 42400-02-29 or before day -5394572 `year`, `month`, `dayofmonth`, `quarter`, `weekofyear`, `last_day` and `trunc` answer wrong, and a column plus a literal with them; under ANSI a `datediff` the same bound limits loses its overflow check. A wrong answer the ghost fallback cannot catch, so done-when items 1 and 2: the bound checked per batch wherever an answer rests on it, the batch declined to the row engine past it, and every coverage row over a date column compared with Spark past it | `m7/VARKA-245.md` 9.2 item 1, reproduced 16 October 2026 | small |
| 327 | **Done** (`VARKA-327.md` 9, 14 October 2026): `VarkaSparkFuzzSuite` has a warmed arm, each composition from a cold shape with the warm-up on: the query on the row path while its kernel compiles, again after the handover until no node waits, then the kernel over other data with the same nulls, every run against vanilla Spark, and a disagreement's kind naming the side of the switch; a planted kernel fault is found after the handover and not before. It never asserts on the verdict: a release at its 10 s deadline is reported with the JIT's account, evidence for VARKA-295 (an empty C2 queue in every one so far, at 0.9% to 6.8% of warm-ups by seed). CI runs 8 compositions, about 4 s; the nightly's `sparkwarm` step 2,000, about 14 minutes, with no disagreement | VARKA-274's admission check, 13 October 2026 | small |
| 370 | **Done** (`VARKA-370.md` 9, 16 October 2026: the limits cannot be raised from inside, but the counts can be reset with no startup option. A JDK 25 JVM loads a Java agent into itself through its own `DiagnosticCommand` MBean (`JVMTI.agent_load`; a warning, and JEP 451 will refuse it by default), and redefining the Vector API classes with their own bytes gives every method empty MethodData, at the price of deoptimizing every kernel. In the warm arm with the template warm-up off, one such reset at 60 s left 1 and 0 deadline releases against 58 and 68, the flags set through WhiteBox 0 and 0, a WhiteBox clear 2 and 3; at 4 lanes the reset left 16 and 17 and the clear 1 and 3 against 29 and 30. Two corrections: the clear does reopen the per-bytecode gate, and C2 never reads `PerBytecodeTrapLimit`. No product change; the template warm-up stays the remedy). Whether a running JVM can reset or avoid the trap and decompile counts that make C2 compile the class-check misses of the Vector API's inlined templates as virtual calls instead of uncommon traps: the diagnostic commands and compiler directives (`jcmd Compiler.directives_add`, `-XX:CompileCommand` set at run time through the `Compiler.*` diagnostic commands, the `DiagnosticCommand` MBean), `WhiteBox` (`-XX:+WhiteBoxAPI`: what it can clear of a method's MethodData), and the diagnostic flags writable at run time through `HotSpotDiagnosticMXBean.setVMOption` (which of the three limits are `manageable`; `jdk.management` and HotSpot's `globals.hpp`), and whether any of them is available to an executor without a JVM option at startup. Done when each mechanism has a verdict (available, not available, needs a startup option) with the JDK source or a one-line experiment as the evidence, and the answer says whether a per-JVM or per-method reset is possible from Varka's own code or only by an operator's option. Serves point 2 of 1.1 through row 295 | the reading of `VARKA-295.md` 9.6 (pull request #825) | small |
| 266 | **Done** (`VARKA-266.md` 9, 11 October 2026), Error Prone clean under `-Werror` and NullAway checking all but 37 classes: Error Prone 2.50.0 runs on JDK 25's javac with the incubator module (69 warnings, no error, over 89 files); a `-Werror` pass in the gate's lint step, NullAway with a pending-class list the script keeps from growing (336 warnings in 45 classes at the start), suppressions with reasons, and eight classes taken off it - the exec metrics, the six JFR events and the compile facade, whose nullable fields are annotated. Error Prone and NullAway on Varka's Java packages only; the first step confirms Error Prone on JDK 25's javac with the incubator module; its own javac pass; NullAway staged by class with a list that only shrinks, reasons on suppressions, checks where Scala calls into Java | the plan review (2.3), `m7/READING.md` 11 | small to medium |
| 382 | **Done** (17 October 2026: the step "Error Prone and NullAway over Varka's Java" takes 39 to 45 s in a `spark` run (four runs from 38938923658 to 39017334406) against the predicted 2.5 minutes, and 157 s in a scoped run that touches Varka's Java (39006583049) against about 10; scoped runs that touch no Varka Java skip it; no live run has had a finding, so the job failing on one rests on 9.2's local check and on the step taking the script's exit status. Until then: `VARKA-382.md` 9, 16 October 2026: the lint job's step and its flag, the script's `--master`; the check found a third breakage, #861's `VarkaEmitTrace` field, fixed here; its CI time is scored on the first runs). Error Prone and NullAway on every pull request: `dev/varka_errorprone.sh` as a step of CI's lint job, failing it on a finding, for every Spark change and every Varka-scoped change to the Java it checks, its script or its pending list, with vecbricks/varka's master and the merge base named because the fork's `origin/master` is not Varka's. In this milestone because row 266's plan left the CI step to its own row if the gate was not enough (`VARKA-266.md` 3.6), and it was not: #809 and #827 merged with findings and master failed the check until #875. **Done when** the step runs in both scopes, a finding fails the job, and its added time is stated | `VARKA-266.md` 3.6, #875 | small |
| 264 | **Done** (`VARKA-264.md` 9, 9 October 2026): all twenty entries owned - eight closed by the task or commit that did it, seven moved to `m9/SCOPE.md` (items 15, 39 and the new 86 to 88), one kept as a decision, four already swept. Milestone 4's debt register swept: each entry of `m4/PLAN.md` section 9 still open closed in the past tense, made a row, or moved to `m9/SCOPE.md` | the plan review (2.3) | small |
| 83 | **Done** (`VARKA-83.md` 9, 10 October 2026): a refusing node declares its reason, an `Analysis.Refusal`, in one map filled once with the options applied; one slot rule and one liveness rule read it, replacing three sets, four per-body flags and two predicates; `emitted_bytes.json` unchanged | item 39 | medium |
| 86 | **Done** (`VARKA-86.md` 9, 11 October 2026), the fusion dump byte-identical: the three positions the emitter checks (day offset, month count, `next_day`'s weekday) as one enum with an exhaustive admission both sides call, the month count gated in the compiler as the day offset already is, and the compiler's three copies of the int leaf rule merged; the comparison widening and the shared day-offset predicate 2.17 asked for landed with VARKA-122 and VARKA-63. One operand admission, stated once | item 39 | medium |
| 253 | **Done** (`VARKA-253.md` 9, 10 October 2026): the forms of `run` without a scratch address are for callers outside a Spark task; inside one `VarkaScratch` refuses a kernel with scratch, so a production path cannot come to rely on the per-thread buffer; stated in `VarkaFusedKernel`'s doc and tested in `VarkaEmitterChronoSuite` | item 68 | small |
| 222 | **Closed on the measurement** (`VARKA-222.md`, 10 October 2026): IR nodes' structural `hashCode` is 2 to 5 per cent of emission at 100 to 400 outputs and `equals` under 2, since the fused budget caps every output's subtree and record equality tries identity first; interning would save at most about 2 ms of a 40 ms emission, so the owner closed the row without a change | item 81 | small |
| 254 | **Done** (`VARKA-254.md` 9, 12 October 2026): every grouping weight equals the lane calls its node emits alone, held by a register over every IR node, `weightOf` an exhaustive switch; `dayofweek` and `weekday` weighed 1 and emit 16 and 15. With the weights right the budget of 16 split a weekday shape five ways and slowed it by a third, so the prediction now bounds groups in its place by default (`groupBudgetUnderPrediction`): the weekday shape keeps two methods, a mixed one runs 14 and 25% faster, and the cost audit's corpus takes 39% fewer loop methods | item 63 | small to medium |
| 255 | **Done, with two defects for the close's debt sweep** (17 October 2026, tests 2 to 4 and 7 scored on live runs: test 2's admission releases a waiting run within one poll (job 117087221257, about 2 minutes) but a scoped push still runs `admit` and `Precompile Spark` when the precondition reports `pyspark-periodic`, which the scoped override does not reset (`build_and_test.yml` line 677; run 39008460283 waited 67 minutes for a slot and precompiled for nothing); test 3 passes, though the 12-job cap is never reached, PySpark's 6 being the one that binds; test 4's check follows its run within one poll (21 to 120 s against the predicted 4 minutes) at about 240 requests an hour, but re-sends an unchanged `in_progress` status every pass while a run is rerun (103 times in job 117303119123), and a completed check is never moved back to `in_progress` by GitHub, which answers 9.4's question; test 7 cannot be observed until a pull request carries the `fix` label, while its rerun half works (3 new tests five times in 2:35, job 117326047064). Until then: `VARKA-255.md`; 255c, 255d and 255e built as three stacked branches, to merge in that order (9.4 to 9.6): 255c queues a full-matrix Build behind another inside the Build (an `admit` job `Precompile Spark` needs, `dev/varka_ci_admit.py`, `ci-hold` honoured, `Build modules` 12 at a time on forks) and syncs the base's `Build` checks from notify's completion in a loop, both on `ubuntu-latest` since `ubuntu-slim` stops a job at 15 minutes; 255d commits the step budget `sql/varka/ci/step-budget.tsv` with `dev/varka_ci_times.py`, puts its table in each completed check, has the nightly write `.secs`, and deletes `dev/varka_ci_queue.sh`; 255e reruns a pull request's new tests five times and makes a `fix` pull request's new tests fail on the merge base (`dev/varka_new_tests.py`), fitting one pull request; tests 2 to 4 and 7 wait on live runs after the merges; 255b, the second PR, runs master's head after each merge on `vecbricks/varka`, since #806 made pull requests build their own commit and nothing else builds the merged tree: a fixed set of eleven jobs (the Varka suites, engine, bench drivers, assembly gate and docs checks, the linters and the Java 25 Maven build), one run at a time with the newest push waiting; its admission check confirmed the weekly run's empty diff from its log and found three things that would have hidden failures on master - `dev/run-tests.py` testing no module when the diff base is the head, the option step comparing master with itself, and the Java linter's exemption for Spark's sources keyed on a path segment `varka` that the base's runner workspace `/__w/varka/varka/` always has (`VARKA-255.md` 2.9), and the review found two more readers of the empty diff, the docs job and the emitted-bytes step (9.2); 255a, the first of five PRs, moves the benchmark pairs and results-file regenerations to GitHub-hosted runners: `benchmark.yml` takes `pr`, `pair`, `rounds` and `base`, probes the measuring machine first and pairs both sides on one runner, and two runner A/A pairs of `VarkaRangeFilterBenchmark` took about an hour each, one passing and one failing on a bimodal 20 ms row, so a runner pair's flagged row is read against its class's runner A/A and the threshold is an input (`VARKA-255.md` 9.1); `VarkaWideKernelBenchmark`'s pair stays on the laptop; admission check: GitHub's cron ran 4 to 10 hours late in both repositories and the laptop queue has rerun nothing since 3 October, while on 13 October the fork's slots were full for only 79 minutes, all while one of two full-matrix runs held them, and one Build at a time would have ended the day almost nine hours later - so the queue becomes an admission job inside the Build that holds only a full-matrix run behind another, the check sync hangs on the pull request's push, master's head and the benchmark pairs run on the base repository's own slots, and the step-time budget reads master's runs). The CI queue off the laptop with no stored credential: the queue on the fork with its own `GITHUB_TOKEN`, the check sync on the base repository with its own; master's head run after each merge; a PR's new tests rerun several times, and a fix's new test required to fail on the base | item 76, redesigned (2.4), `m7/READING.md` 9 | medium |
| 256 | **Done** (`VARKA-256.md` 9, 9 October 2026): `dev/varka_bench_sections.py` and `--sections` keep every other section byte for byte (tested on a real file), `dev/varka_bench_pair.sh` runs a pair in alternating rounds (identical code reads within 4.1% on every row), and the workflow takes a `sections` input. Regenerate one benchmark section, not the whole file, and run it paired against the PR's merge base in alternating batches with a threshold | item 72, `m7/READING.md` 9 | small to medium |
| 257 | **Done** (`VARKA-257.md` 9, 16 October 2026): `VarkaKernelHistoryProbe` warms N kernels of the IR fuzz grammar through production's warm-up, then the cliff probe's cheap tails at 24 outputs, and compares every kernel's answers; readings in `VarkaKernelHistory-jdk25-probe.txt`. The kernel under test was slow in 0 of 52 forks at N = 0, 16 and 64, at both widths; the history's own kernels were released still allocating, every loop at tier 4 (VARKA-295's cause 4), at a rate that grows with their position and is 0 at position 0. The template prewarm clears the int lane before position 16 (0 of 84 against 19 of 84) and leaves the long lane as it was (43 of 144 past position 16 against 46); at 128 bits the long lane releases alone (6 of 6), VARKA-374's case. 0 wrong answers in 1,140 kernels. The nightly's `history` step runs N = 16 with six forks under `--guard`, about 4 minutes. A kernel after other kernels: the slow mode's frequency as a function of N, one arm in the nightly; its forks compare answers too | item 62, `m7/READING.md` 11 | medium |
| 283 | The exhaustive division sweep outlives the watchdog, and sbt hangs on the halted fork. **Done** (`VARKA-283.md` 9, 4 October 2026): the sweep is one test per divisor with a plain comparison, 5 seconds each, and every gate and nightly step runs under `dev/varka_deadline.sh`, which stops a step past its deadline with everything it started; the gate's sweep step passes again. *Found 3 October 2026 by VARKA-236's gate run.* VARKA-149's opt-in test "the multiply-high form is exact over every int32 dividend for every divisor in use" runs nine divisors over all 2^32 dividends under a ScalaTest assert each, which is far past the ten minutes `VarkaTestWatchdog` (row 226) allows a test, so `dev/varka_gate.sh`'s sweep step and the nightly's have halted on it since the watchdog landed on 28 September 2026 - every log on record shows the test only ever cancelled by its opt-in or halted - and the proofs of VARKA-240 compare against a sweep that never completes. Worse, after the watchdog's `Runtime.halt` sbt did not report the suite aborted as row 226 intended but waited on the halted fork indefinitely (the `-batch` server printed "Unable to read from client"), so the gate hung rather than failed. Done when the sweep steps pass: the test split per divisor or given a cap of its own through `varka.test.watchdog.minutes` set by the gate's and the nightly's sweep steps, with the time measured and written beside it; and a halted fork fails the sbt step within a minute, by the watchdog exiting through a path sbt's test agent reports or the gate's steps carrying a timeout of their own | VARKA-236's gate run | small |
| 286 | The gate in parallel: after one build, the suites wide and narrow at once, each module's suites over several JVMs weighted by their last times, sbt's steps in a lane of their own, the JIT-measuring suites in a quiet phase at the end; and the narrow step really narrow, `-XX:MaxVectorSize=16` on each test JVM's command line where `JAVA_OPTS` had never reached them. **Done** (`VARKA-286.md` 9.1, 5 October 2026): 682 s from 2,074, the narrow step passing at 128 bits | the owner, 5 October 2026 | small |
| 287 | **Done** (`VARKA-287.md` 9.2, 6 October 2026): the suites run through the runner over two JVMs with their JUnit times cached, and the matrix step reuses their defaults' fused batches; the catalyst job takes 14 minutes from 22 to 25, the SQL job 22 to 24 from 22 and 33, where the runner's recording of fused batches spends what the reused defaults save. Planned in two pull requests: the step as a script with a times cache, then the runner behind it. The same split in CI: the `varka-scoped` jobs run their module's Varka suites through the runner over two or three JVMs instead of one sbt test JVM, on the four-core runners; measured on the runners against the jobs' times today | VARKA-286 | small |
| 180 | **Moved to milestone 8** (17 October 2026, `m8/PLAN.md` row 180): the promotion that happened is the four posts published on 16 and 17 October (rows 258, 259, 260 and 371) and the closing post (270); the repository records no cross-post, findings cadence or benchmark page. Still a cadence rather than a task a milestone can close, so it carries into milestone 8 as it carried into this one. Promotion, continuously | item 81 | a cadence |
| 258 | **Done** (published 17 October 2026 at https://vecbricks.github.io/how-spark-compiles-a-query/, with row 259's link; until then: `VARKA-258.md` 9, 16 October 2026: `POST_COMPILES.md`, from the counts on stock 4.2.0 under `demo/compiles/` - a new int, date or parameter value three classes in local mode, one on the driver and two on each executor, a new string none, and the default cache holding about 30 such queries in local mode, so the 150-query loop compiled all 150 again; and the times on stock 4.2.0 from a runner (9.3, AMD EPYC 9V45) - a new int 39.59 ms a query against 18.87, of it 10.96 ms Janino, a compile from 2.82 ms for a one-entry projection to 30.72 ms for 60 sums; and, on this repository's Spark (EPYC 7763), the integer passed as a reference removing the compile at no steady-state cost; prediction 3 mixed, the ratio moving with the machine; owed before publication: row 259's published link and the owner's edit). A post for Spark users: how Spark compiles a query, and why it compiles it again | item 78 | medium |
| 259 | **Done** (published 16 October 2026 at https://vecbricks.github.io/the-class-got-cheaper/; until then: `VARKA-259.md` 9.1, 16 October 2026: `POST_BYTECODE.md`, from the committed codegen and cold-start results; the benchmark of emission against Janino's compile over the same expression lists committed from a runner, `VarkaCodegenBenchmark` (9.2: 2.5X to 5.8X cold, not the emitter-only row's 65.6X); the post revised on those numbers the same day, its title and table now the matched benchmark's; owed before publication: row 258's published link and the owner's edit). Its companion for Spark and JVM developers: bytecode without source | item 79 | medium |
| 260 | **Done** (published 16 October 2026 at https://vecbricks.github.io/nine-numbers/; the fast hash map's capacity was not measured by then and the post says so, its measurement follows as an addition (`VARKA-260.md` 9.2); until then: `VARKA-260.md` 9.1, 16 October 2026: `POST_CODEGEN_NUMBERS.md`, nine numbers with their 4.2.0 defaults read from the source; owed before publication: what happens past the codegen cache's 100 entries (row 258's measurement 5) and past the fast hash map's capacity, row 258's published link and the owner's edit). A reference post: Spark's code generator by the numbers | item 80 | small |
| 371 | **Done** (published 16 October 2026 at https://vecbricks.github.io/why-a-warmed-kernel-can-still-allocate/; after #893 scoped "none" to five cores, with 2 of 1,602 warm-ups released on three; until then: `VARKA-371.md` 9.1, 16 October 2026: `POST_TRAP_SUMS.md`, written from the fix's result; the replay `sql/varka/demo/trap_sums.sh` and its run, 77 of 100 replays released with the template warm-up off against 0 of 100 with the two options and 0 of 100 stock, and row 370's verdict are in; the owner's edit is owed before publication). A short post: the JIT's per-compile trap sums, and why one vector array survives in a warmed kernel. For Spark and JVM developers: a warmed Vector API kernel compiled at the top tier still allocates one vector array per loop iteration in a few per cent of warm-ups, because C2's class-check gate adds up the trap counts of every inlined copy of a shared template; found by classifying every release from the JIT's own log, ruling out a deoptimization cycle, a bailout and a policy that never asked, naming the sums in `doCall.cpp`, and a JVM option that removes it. Serves point 5 of 1.1 | the reading of `VARKA-295.md` 9.5 and 9.6 (pull request #825) | small |
| 372 | **Done** (`VARKA-372.md` 9, 15 October 2026: the hostile years are the largest that fit and the view's year-month columns are NULL where they would overflow; compositions erroring on both sides fell from 393 to 479 of each 5,000 to 14 to 68, the full pools from 17 to 18% to under 3.2%, no disagreement in 35,000 compositions, and a new test fails on the old fixture). The Spark-level fuzzer's fixture raises on a hostile row: its interval columns `ymy` and `ym` overflow for the drawn `y = 2147483647`, so 8 to 10% of the compositions (393 to 479 of each 5,000 in the 35,000-composition census) compare an error with an error and nothing else, 17 to 18% in the full pools, and the warmed arm failed once at random on it (seed 20261008, iteration 1405: `CAST_OVERFLOW` against `INTERVAL_ARITHMETIC_OVERFLOW`, each legitimate for a different column of the same row). Make the fixture total, or run only compositions whose fixture builds, and measure the share before and after. **Done when** the share of compositions erroring on both sides falls under 6% in the full pools, the warmed arm passes seed 20261008 over iteration 1405, the census still raises every entry of the expected-errors list, and no pool loses its boundary draw | the census of `VARKA-319.md` 2, `VARKA-295.md` 9.8 | small |
| 373 | **Done, no fix** (`VARKA-373.md` 9, 16 October 2026). The 4-lane warm arm was classified from the JIT log (2 runs, 151 released classes each, all cause 4): the cause is a per-bytecode trap count of 4 at `IntVector.add` bci 5 that turns the miss path of a shared template into a virtual call, not 9.6's compile-wide sums; warming the narrower species does not help (128-bit alone 26 releases per 100 compositions, with the preferred 62, against 14 to 16 shipped and 18 to 31 off). 9.8's "256-bit preferred" was wrong: it is 512 here, so 16 lanes was the preferred arm. Production emits at the preferred species only; at a 128-bit preferred species (`-XX:MaxVectorSize=16`, the gate's) the warm-up does nothing either (41 to 43 per 100 against 45 to 46 off), by a different mechanism, undiagnosed: that is the open item. No code changed | `VARKA-295.md` 9.8 | medium |
| 374 | **Done, no fix** (`VARKA-374.md` 9, 16 October 2026). The cause at a 128-bit preferred species is named from two classified JIT logs of the warm arm at `-XX:MaxVectorSize=16` (100 and 400 compositions, 41 and 151 releases): x86 C2 lowers no 2-lane mask (`x86.ad` refuses `VectorLoadMask`, `VectorStoreMask` and `VectorMaskCast` at `vlen == 2`, before any profile is read), so every long kernel with a mask runs the Vector API's Java fallback per mask operation, allocates and is released at the deadline, warm-up or not. Every class with a 2-lane long mask in a loop compile released (66 classes, 133 of the 151 releases) and none of the 273 others; the rest is the predication cycle, 3 to 7 per 100. A standalone loop: long 2 lanes 38,852 ns a call against 2,175 for int 4 lanes, 18x, and equal at 4 lanes. The rate cannot fall under 10 per 100 without a mask-free or declined 2-lane long kernel, which is design work beyond the row. Correction: this is the x86 back end below its width, not Apple Silicon, whose aarch64 back end has 2-lane masks (`VARKA-153.md` 6); production reach is AVX-only and SSE-only x86 hosts. No code changed | `VARKA-373.md` 9 | medium |
| 380 | **Done, apart from the nightly switch, which moves to milestone 8 with row 386** (17 October 2026: the five reproducers are regressions that agree; `-Dvarka.sparkfuzz.nested=false` stays in the nightly until row 386 is settled, since its reproducer raises on Varka where Spark's default session answers. Until then: `VARKA-380.md` 9, 16 October 2026: the compiler demotes every fused conjunct that Spark, in its written or its codegen order, evaluates after a residual conjunct that can raise, or that moves a residual null test across one, and the per-row fallback evaluates the codegen order; the five reproducers are regressions that agree, and 15,000 compositions at the three seeds that met the bug found no disagreement, where the base found three; the nightly keeps `-Dvarka.sparkfuzz.nested=false` until rows 386 and 387, the nested trees' two other wrong answers found by row 381's census, are fixed, and removing it closes this row). A fused conjunct runs before a residual conjunct that Spark's optimized `Filter` evaluates first, so where that residual conjunct raises (under ANSI, or year-month interval arithmetic in any mode) Varka returns rows and Spark raises; the mirror of row 273, which ordered only the fallback. Found by VARKA-298's census, five reproducers in `sql/varka/fuzz/spark/` (`status: known 380`). In milestone 7 because it is a wrong answer, under 1.1 point 2's filter-order correctness. Decide between fusing only the conjuncts before the first residual one that can raise and evaluating the residual conjuncts first on the batch. **Done when** the five reproducers are regressions that agree, and `-Dvarka.sparkfuzz.nested=false` is gone from the `sparkfuzz` step of `dev/varka_nightly.sh`, so the nightly draws the nested trees again | VARKA-298 9.2 | medium |
| 381 | **Done** (`VARKA-381.md` 9, 17 October 2026): a check in which a query raised runs again over its rows as one partition (or, under `-Dvarka.sparkfuzz.errorRerun=eachRow`, then each row alone), so no verdict depends on which task fails first; the three-rerun absorption is gone. The census of 150,000 compositions without nested trees found no disagreement; 150,000 with them (on row 380's fix) raised every listed condition and found two deterministic Varka disagreements the race used to hide (9.2). The Spark-level differential's view has two partitions (`local[2]`), so two listed errors on rows in different partitions race and the query raises either one, on either engine and from run to run; VARKA-298 absorbs it by rerunning such a disagreement three times. In milestone 7 because it makes the fuzzer's verdict trustworthy (1.1 point 2): a comparison whose answer depends on which task fails first can neither pass nor fail honestly. Make the comparison deterministic - one partition for error cases, or the set of conditions each row raises compared instead of the first - and drop the rerun | VARKA-298 9.3 | small |
| 386 | **Moved to milestone 8** (17 October 2026, `m8/PLAN.md` row 386): Spark's answer here is under review upstream. SPARK-60315 records that codegen and the interpreted filter disagree, and apache/spark#59573 proposes that codegen raise as the interpreted path does, which would make Varka's present answer Spark's. Fixing Varka to today's default would be undone by that change, so the row waits for the upstream decision. Varka raises an error on a row whose filter Spark answers without raising: under the legacy mode, `NOT (year(d) = 2021 AND i > 0) AND (ymy <= INTERVAL '1' YEAR - ym)` over a row where the first conjunct is NULL raises `INTERVAL_ARITHMETIC_OVERFLOW` from Varka's year-month subtraction, while the row engine returns the query's rows. The opposite direction of row 380, where only Spark raises. Found by VARKA-381's census once nested trees were drawn and the two-partition race no longer hid it (seed 20261017, iteration 22897); the reproducer is `sql/varka/fuzz/spark/varka-386-20261017-22897.sql` (`status: known 386`). In milestone 7 because it is a wrong answer (1.1 point 2), and because a nightly that draws nested trees fails on it, so row 380 cannot drop `-Dvarka.sparkfuzz.nested=false` before it is fixed. Spark's own answer depends on whole-stage codegen (checked on 17 October 2026 with stock `bin/spark-sql`, `local[1]`, at apache/spark `5d17442dec4`): with it on, the default, the generated filter stops at the NULL conjunct and the query returns its row; with it off, `And` evaluates its right side after a NULL left one and the query raises `INTERVAL_ARITHMETIC_OVERFLOW`, as Varka does. The default is the oracle, as for row 380. **Done when** the reproducer is a regression that agrees | VARKA-381 9.2 | small to medium |
| 387 | **Done** (#911; `VARKA-387.md` 9, 17 October 2026: where the columnar filter's per-row fallback raises on a row of a batch, the rows that passed before it leave as a shorter batch and the error is raised when the next batch is asked for, so the operator above meets them first as in Spark; the row-out filter was already lazy per row and is unchanged; the reproducer is a regression that agrees, and `VarkaFilterRaiseOrderSuite` compares projections, `LIMIT`s and aggregates above both filter nodes with Spark at three batch sizes; the nightly's nested switch now waits on row 386 alone). Under ANSI, a query whose projection raises on one row and whose filter raises on a later row raises Spark's first row's error from the row engine (`DATETIME_FIELD_OUT_OF_BOUNDS` from `make_date(2021, i, 1)`) but the later row's from Varka (`INTERVAL_ARITHMETIC_OVERFLOW` from `ymy + ym >= ym`): Spark filters and projects row by row, and Varka's filter runs over the whole batch before the projection. Row answers follow vanilla Spark, errors included, so this is a wrong answer. Found by VARKA-381's census with nested trees (seed 20261018, iteration 47807); the reproducer is `sql/varka/fuzz/spark/varka-387-20261018-47807.sql` (`status: known 387`). In milestone 7 for the same two reasons as row 386. A raise inside a batch could decline the batch so that the row engine raises its own first error; settle against row 380's design, since both are about the order in which a fused filter's conditions meet a row. **Done when** the reproducer is a regression that agrees | VARKA-381 9.2 | small to medium |
| 384 | **Done** (`VARKA-384.md` 9, 16 October 2026): each calibration entry is a file under `sql/varka/skills/calibration/`, named after its task cell, and `dev/varka_calibration.py` prints the table, byte-equal to the fifteen rows it had; `--check`, run by the pre-commit hook, rejects a malformed file and `--add-row` turns a pasted row into one. The table is printed rather than committed, since a committed rendering conflicts as the old table did (`git merge-tree` exiting 1 on two branches that each add a row; GitHub runs no merge driver); two branches that each add an entry now merge cleanly. The cost-prediction rule in `sql/varka/AGENTS.md` names the command and the directory. The calibration table as one file per entry. `skills/testing-and-debugging.md`'s table gains a line from most tasks, so each merge conflicts with every open branch that added one, and each resolution costs a push and a CI restart (six or so per merge on 16 October 2026). In milestone 7 because it slows every remaining row's review and the close (section 10, the first CI entry). Each entry becomes a small file under one directory, the table is generated from them by the tool that already checks `SKILLS.md`, and the pre-commit check fails when the table and the files differ. **Done when** two branches that each add an entry merge without a conflict, the generated table equals today's lines, and the cost-prediction rule in `sql/varka/AGENTS.md` names the new files | section 10, opened 16 October 2026 | small |
| 389 | **Done** (`VARKA-389.md` 8 and 9, 18 October 2026; opened by the close on the owner's word, since milestone 7 did not close before it resolved). The cause: VARKA-295's Vector API template warm-up, `aa457c3797b` of 15 October, run at the warm-up worker's first job. In a JVM where vector code ran before the first warmed shape, the shared templates are already compiled standalone when the warm-up's rounds take branches they never saw; eight `unstable_if` traps reinterpret them and leave one decompile on each, a four-output group method inlines `IntVector.lanewise` six hundred times, C2 sums that decompile once per copy, crosses its cutoff of 201 and keeps 367 virtual calls, under which the vectors box; the kernel's bytecode was identical before and after, and with the warm-up off not one template trapped (8.4a, from two compilation logs). The fix, the owner's choice among five measured options: the warm-up runs before any vector code of the engine, from the shape cache's first build, a task's first kernel instance and the query-path helpers' class initialisers, with a forked check in the benchmark's order in `VarkaKernelWarmupSuite`. Measured: 71 of 71 verdicts COMPILED at both widths against 20, the steady-state rows at 2M rows 41, 105 and 186 ms against 28 September's 48, 133 and 250, the README's and the docs' cold-start row requoted. Left to the owner (9.4): a per-query cost of the warmed class at 54 to 100 entries, 40% against 28 September on the back-to-back rows and absent at 16 entries, in the unwarmed arm and at 2M rows, which predates the template warm-up; and the warm-up's thread. Originally: the warm-up's verdict is RELEASED at its 60-second deadline on every projection shape of `VarkaColdStartBenchmark` (16 to 100 entries of `greatest(add_months(d, k), date_add(d, k), last_day(d))`), on the laptop and on two GitHub runners alike, 51 of 71 verdicts each, where the file of 28 September had 71 COMPILED; the once-compiled rows read 1.3x to 2x slower than that file and the steady-state rows at 2M rows 2.5x to 4x slower, with averages that spike to seconds. First reads: every method is under HugeMethodLimit, but each of the sixteen group methods carries about 400 Vector API call sites, four times the call-site budget, left there by the heavy-group exemption; C2 is asked for them within seconds of the warm-up's start and runs out of nodes on one; every output reads its column through VARKA-379's `guardedRange`. Name the cause with its commit, fix it without a steady-state cost, regenerate the cold-start files on the laptop and a runner pair, requote the README's and the docs' cold-start row, and add the check that would have failed the pull request that did this | `VARKA-261.md` 5.5, prediction 4; `m9/SCOPE.md` item 103 | medium |
| 391 | **Done** (`VARKA-391.md` 9, 18 October 2026): the Vector API template warm-up calls every one-line wrapper the kernels use (`add`, `min`, `max`, `blend` and the rest) eight times a round instead of once, so each crosses its first compile threshold inside the rounds and passes the policy's old mark of 2,000 calls before they end, and the rounds are paced on C2's queue to the policy's resume line (`Tier3DelayOff` times C2's threads) read through the JVM's diagnostic command; the wrappers then compile at tier 3 with the queue at 0 to 2 where before they landed at tier 2 after the rounds and went to tier 4 unprofiled, and the end-to-end warm-up suite on four cores at 256-bit lanes passed every test in every run where it had released two of five in three runs of three. | `VARKA-389.md` 9.5, the four-core failure the fix's CI found; the owner's ask to fix the flake rather than live with the retry of row 390 | small to medium |
| 385 | **Done on the host runners; the container jobs to the close's debt sweep** (17 October 2026, run 39017334406: the 14 jobs on host runners restore the cache and install offline with no download, at 5 to 6 s against 12 cold; the 12 container jobs, every PySpark job, lint and docs, restore it to `/github/home/.m2` while Maven reads `/root/.m2/repository`, so their offline install fails on `maven-resources-plugin` 3.4.0 and falls back to downloading, still exposed to the miss this row removes. Until then: `VARKA-385.md` 9, 16 October 2026): the engine install restores a 35 MB local Maven repository of its own, keyed on the engine pom (which now pins every plugin the build runs) and the test mode, installs offline (`-o`) on an exact hit, retries an online transfer failure twice, and saves right after a cold install; locally the offline install succeeds with no download. Done when the first CI run's log shows what `VARKA-385.md` 9 lists. The Varka engine's Maven plugins from the cache. CI's "Install the Varka engine" step skips the Maven cache and downloads its plugins on every run, and on 16 October 2026 a transient Maven Central miss of `maven-resources-plugin` 3.4.0 failed it twice (#888, #889), each costing a rerun. In milestone 7 because every full CI run of the remaining rows takes that step (section 10, the second CI entry). Restore the cache in the jobs that install the engine, or pin the engine pom's plugin versions so a cached copy is always found. **Done when** a run whose cache is warm downloads no plugin in that step, shown by its log | section 10, opened 16 October 2026 | small |
| 270 | **Done** (published 17 October 2026 at https://vecbricks.github.io/where-the-checking-stops/, and recast for testers and republished the same day on the owner's review, `VARKA-270.md` 10.5; `VARKA-270.md` 10, pull request #914; the paragraphs on rows 379 and 387 said "a proposed change" until #874 and #911 merged the same evening, and the page was republished saying so). The milestone's own post, for JVM and database engineers: how Varka knows its answers are right, and where that stops | the plan review (2.5), item 58 | medium |
| 288 | **Done** (`VARKA-288.md` 9.3, 12 October 2026): published as "A large CASE WHEN, split only where it has to be"; the fix 4.5 times faster than the cliff post's workaround at 300 branches and that workaround within 3% of whole-stage off, C2's inlining shown from its own log. The cliff's sequel, for the readers of "The 8000-byte cliff in Spark SQL": a large CASE WHEN inside a stage split only where its method is past 8000 bytes, as apache/spark#59225 (SPARK-33301) does it; the cliff post's workaround measured against the fix, and C2's inlining under splitting always; published once the pull request merges, naming the release (`VARKA-288.md`) | the owner, 5 October 2026 | small |
| 261 | The closing task: 1.1 read row by row, and the README's account of how Varka is checked rewritten; then the close `CONTRIBUTING.md` asks of every milestone, in VARKA-282's shape - the documents, the issue mirror, the lessons, the oracles, the `CHANGELOG.md` entry, the tag `varka-m7`, the cleanup. The README's and `docs/sql-varka.md`'s cold-start headline (1.8x, 17 ms against 31 ms) is regenerated with the rest and states the spread between JVMs a runner shows, where the warm-up compiles a shape in some JVMs and gives it up in others (PR #900's pairs, `m9/SCOPE.md` item 103). The debt sweep takes, beside the register, what the last rows left (17 October 2026): the option-matrix test "inside a Spark task, the forms without a scratch address refuse a kernel with scratch", which fails under `methodByteBudget=0` and `shareChronoPrefix=false` with no `skips.tsv` line (found by #910); the noisy plan-size bisection test (#910); a scoped push running `admit` and `Precompile Spark` on `pyspark-periodic`, and the check sync re-sending an unchanged status while a run is rerun (row 255); and the container jobs' engine cache restored where Maven does not read it (row 385). Each is fixed or moved to `m9/SCOPE.md` | this plan | small |

## 4. Ordering

| wave | tasks | why they wait |
| ---: | :--- | :--- |
| 0 | 240, 246, 248, 249, 255, 256, 81, 263, 266, 273, 275, 277, 283, 286, 287, 289, 293, 295, 299, 370 | 240 is the proofs' tooling; 246 makes later suites' verdicts trustworthy; 248 and 249 are what every later refactor touches; 255 and 256 end the laptop queue and the hand splicing for every PR below; 263 and 266 check every port and refactor below as it lands; 81 needs nothing; 273 is a reproduced bug; 289 a reproduced test gap; 293 a reproduced edge in one guard; 295 a reproduced CI flake; 370 the check of what a running JVM allows, which bounds 295's remedy; 299 a reproduced crash; 275 and 277 make every later test's failure loud and small |
| 1 | 241, 243, 250, 251, 214, 215, 216, 138, 247, 222, 253, 262, 269, 271, 264, 274, 276, 284, 285, 291, 292 | after the tooling and the two cheap refactors; the three family ports run in parallel, by an agent; 262 and 269 need only the fuzzers' grammar and 277's shrinker, and 271 only Spark's suites; 264 is decisions, not code; 274, 276 and 284 sharpen the oracles every later test reads, and 285 measures what 284 closes; 292 follows 263's sanitizer, which found it, and precedes 263's last step; 291 needs only the IR's source and a quiet machine, and its verdict is wanted before the ports have built much of the compiler |
| 2 | 242, 244, 217, 224, 83, 254, 257, 267, 272, 278, 279, 280, 281, 290, 294, 296, 297, 298, 303 | 303 after 284, whose report it reads; 217 after the families it fronts; 83 after 250, which rewrites the same paths; 290 after 83, whose single refusal is where the size loop's declines end; 294 after 277, whose shrinker it reuses; 296 to 298 after 262, whose harness they extend; 244 after 243 defines what a delta departs from; 267 after 251's components; 272 after 246, so that a configuration's verdict is not a second species' boxing; 278 after 277; 279 after 138; 280 after 262; 281 after 240's tooling |
| 3 | 245, 86, 252, 268, 265, 310, 311, 312, 313, 314, 315, 316, 317, 318, 319, 320, 321, 322, 323, 324, 325 | 325 before 322a's pairs, whose wide-kernel pair cannot finish without it; 323 first, since it fails a nightly IR chunk in about sixteen and needs nothing, and 324 after 320, whose port it touches; 320 after 311, whose checks the fuzzers call, and before any fuzz suite is written in JUnit under 312; 321 and 322 whenever the machine is free, 322b before 322c, whose adapter it builds, and 322's parity benchmark last; 313 to 319 after the structure settles, 315 after 314, whose error context it writes, 316 last of them since its steps are the others'; 310 to 312 after 268, whose pattern they follow, 311 after 310, whose evaluator it calls, and 312 after 311, so that a new suite has Java helpers to call; 86 after the ports, so the admission is written once in Java; 252 once the structure has settled; 268, optional, once the ports and refactors have settled the helpers' callers; 265 after 262, 263, 269, 280 and 285, so that it measures the tests the milestone leaves |
| 3b | 384, 385 | added 16 October 2026 from section 10: two CI debts that cost every remaining row a rerun, so before the close |
| 4 | 261, 270 | 261 last by definition; 270's post written from what 261 records |

VARKA-180, VARKA-258 to VARKA-260 and VARKA-371 run across the waves; VARKA-288 waits on apache/spark#59225's
merge.

At the halfway point the milestone is reviewed against 1.1, not against this table, and the
review is recorded as section 9 with an ordering for what remains, as milestone 6's was
(`m6/PLAN.md` 9); the waves above stay as the record. That review is also where
anything rows 262, 263 and 269 find is weighed against the milestone's scope (section 6).

## 5. Verification

* Every structural PR cites the proof its row names: `emitted_bytes.json` unchanged for the
  emitter's refactors (248, 249, 250, 83); the coverage and family-chain oracles and VARKA-191's
  emission times for the ports (214 to 217); a before-and-after benchmark of per-batch overhead
  for the evaluator (251, 267); compile time before and after for 222. The Varka suites pass,
  with 263's sanitizer on.
* VARKA-285 commits the generated code's coverage report, and VARKA-284 is done when that report
  shows every executed class entering both drivers.
* VARKA-265 commits its coverage report and its mutation run's survivors, each with the test that
  now kills it or the note that it changes no behaviour.
* `dev/varka_prove.sh` fails on any `sat` and on a missing solver, and runs in the linters' job
  in under a minute.
* VARKA-246 commits the full Varka run's time before and after.
* VARKA-271's exclusion list gives a reason for every entry, and fails when an excluded test
  starts passing; VARKA-272 records which configurations each kernel ran under.
* `dev/varka_quote_check.py` at zero orphans and `dev/varka_toc.py --check` clean; the linters
  run locally before every push.

## 6. Risks

1. **Nothing here is visible to a user.** Accepted, as milestone 6 accepted its foundation.
   The posts carry promotion, and 1.1 item 1 gives the README a sentence it cannot say today.
2. **An SMT encoding can be wrong the same way the lowering is.** That is why 240 runs beside
   VARKA-149's sweep for a cycle before the sweep cites it, and why each proof file names the
   Java it encodes.
3. **The specification can sprawl.** Item 59 bounds it to the operations the coverage table
   lowers, not the semantics of queries; 245 is the row to cut if 243 and 244 run long.
4. **The refactors collide with each other and with milestone 6's last rows.** One PR per item,
   in the waves above; 236's planner owns the size loop, and 250 waits for 239's emitter
   changes to merge.
5. **An agent's port drifts from the Scala it replaces.** Each port is gated by the
   family-chain, coverage and bytes oracles, as 175 was.
6. **The new tests will find disagreements.** A differential against Spark itself (262), Spark's own
   suites (271), every small tree (269), a sanitizer (263), the kernels under several JIT
   configurations (272) and compiled by C2 (274) are built to find what the record has not - the
   reading found one before any was built (273); each finding is fixed, or becomes a named delta
   (244) or a row, and that can grow the milestone. The halfway review (section 4) is where the
   growth is weighed against 1.1.
7. **Measuring how well the tests test costs machine time.** A mutation run multiplies a
   suite's time by its mutants; 265 bounds it to `Slots` and `Analysis`, runs it in an idle
   window, and takes the cheaper branch coverage first.
8. **Checking compiled kernels costs machine time too.** Row 274's warm-ups and batch
   histories, 272's forked arms and 269's size-5 tier multiply the fuzzers' cost; each runs a small
   arm in PR CI and the rest nightly or in an idle window, and records its times.

## 7. Open questions

1. **Which solver gates the lint job?** SMT-LIB is neutral, and the nightly runs both (2.1); 240
   chooses the lint job's by install cost and solve time. Neither is on the laptop today.
   *Answered, 9 October 2026 (`VARKA-240.md` 2.6): Z3 5.1.0, installed by `pip` in four
   seconds, with cvc5 1.4.1 the nightly's second opinion. Both solve the first proof file in a third
   of a second; Bitwuzla has no integer theory, in which the proofs are stated, and is left for
   row 241's floating point.*
2. **Does 254 retune the weights or retire them?** Item 63 leaves it to the task; VARKA-236's
   planner reads the same costs, so 254 takes its answer from 236's build. *Answered in
   `VARKA-254.md` 2: retuned, since the fitted prediction judges a group and the walk needs a price
   per node; the budget they are summed against is what moves.*
3. **Where 255's queue workflow lives on the fork.** A scheduled workflow runs from the default
   branch, and the fork's `master` follows `apache/spark`'s. Carrying one extra file there is
   the simplest answer; the task weighs it against another default branch for the fork.

## 8. Explicitly out of milestone 7

* The coverage spine and everything else in `m9/SCOPE.md` not named above - decimals,
  aggregation, strings, the plugin (item 53), the configuration surface (item 64), the fallback
  metrics (item 65). Decimals up to precision 18 are milestone 8's since 14 October 2026.
* Item 47, one place per node: it waits for a second lane type or representation to design
  against.
* Item 51, a mapping type per IR node: it lands with the first operator that is not
  element-wise.
* Item 36, the math family's ULP contract: a decision for a family Varka does not lower yet.
* What `m9/SCOPE.md` item 89 collects from the Polars reading beyond rows 252 and 301: the double lane,
  strings, aggregation, decimals, time zones and the Parquet reader wait for the milestone that
  builds them (milestone 8, since 14 October 2026, for the double lane and the decimals).
* Porting the exec nodes and the columnar rule to Java: they stay thin Scala wrappers, since
  `SparkPlan` case classes are the interop surface the owner's Java rule exempts (2.3).
* Row 218 (optional JIT research), rows 207, 208 and 213 (range-set and warm-up tuning), row
  231 (the fallback's row loops), and milestone 5's row 74 (a validity-pass optimisation).
* Verifying the emitter itself, Java-level deductive tools, and a formal semantics of Spark's
  dates, for item 58's reasons.

## 9. Review at the halfway point, 13 October 2026

*Added on 13 October 2026 at the owner's request, eleven days after the milestone opened, as
section 4 provides. Section 4's waves are not rewritten; 9.3 is the ordering for what remains,
and where they differ 9.3 is current. The decisions in 9.4 are the owner's to take.*

### 9.1 The state against 1.1

The table had 53 rows when the plan merged on 2 October and has 98 today: rows 283 to 327 were
added since, 13 of them by the owner on 12 October (310 to 322, the test code to Java and the
errors rows) and the rest findings of the milestone's own tests. 63 rows read **Done** or are
closed; 271, 138, 274 and 322 are part done.

| 1.1 | State on 13 October |
| :-- | :-- |
| 1. Every bounded lowering proven, against a specification | The proofs are in: 240 (tooling, Z3 in the lint job), 241 (the long-lane divisions), 242 (the calendar's bounded lowerings), the last commit 10 October. The specification is not: 243 (the fragment), 244 (Spark's departures as named deltas), 245 (the proofs as refinements of it) and 281 (the analyses that supply the guards' bounds) have not started |
| 2. The tests reach what they miss today | The spine is through or landing. Done: 81, 246, 247, 248, 262, 263, 269, 273, 275 to 278, 284, 285, and the findings 289, 292 to 297, 299, 301, 303, 323, 324. Landing this week: 271 (271a merged, 271b's full run under way), 138 (138a merged, 138b with an agent), 274 (274a in review). Open: 272 (after 274a's tier witness), 279 (planned, waiting on 138), 280, 298, 326, 327, and 265 last by design |
| 3. Varka's code is Java and legible | Through but for two rows. Done: 214 to 217, 224, 248 to 251, 264, 266 to 268, 83, 86, 290, 291, 310 to 312, 320, 321. 322 is a third done: 322a merged (ten benchmarks), 322a-2 reviewed and waiting on its pair, 322b and 322c open. 252 (comments that narrate history) is open, last by design |
| 4. The infrastructure stops costing manual work | Half. Done: 256 (one section regenerated, paired runs), 283, 286, 287. Open: 255 (the CI queue off the laptop), 257 (the slow mode after other kernels, a number). The errors rows the owner added to this point's section on 12 October, 313 to 319, have not started; 325 is in review |
| 5. Beside the spine: promotion | 288 published on 12 October. 258 to 260 and 270 have not started, and 180 has no commit in this milestone |

### 9.2 What the review finds

**The milestone has drifted to the measurable track again, as milestone 6 did.** Point 2's rows
each end in a number and a merged test, so they kept winning the next slot; point 1's
specification rows end in a document and an argument, and nothing has moved there since the
proofs landed on 10 October. Milestone 6's review (`m6/PLAN.md` 9.1) found the same pattern for
its promotion track, and promotion has not moved here either.

**The laptop is the bottleneck, and 255 has been open since wave 0.** Three benchmark jobs wait for
an idle machine - 322a's ten Java-against-Scala pairs, 325's two results files and 322a-2's pair of
about three hours - and #780 merged before its pairs ran. The fork's CI queue sat for up to fifty
minutes on 13 October with nothing in progress, and a night load beside a gate still fails
watchdogs. 255 is the row that removes the laptop from CI's path; benchmarks on GitHub-hosted
runners, with the datapath probe checked first, are already the project's practice for CI
benchmarks.

**The tests are getting slower, and nothing tracks the total.** On 13 October alone the per-row
coverage check grew 27% (138a), the IR fuzz suite 6% with a compiled test of 145 s (274a), and
the nightly gained the golden-file step, 21 to 33 minutes per arm. Each was justified in its
plan; no plan carries a budget for the gate or the nightly as a whole.

**Reviews came after CI, and every one found something.** The six pull requests reviewed on 13
October (#785, #786, #789, #791, #792, #795) each had seven to ten findings, among them a suite
that could overwrite Spark's golden files, a fault check that was circular, a benchmark that split
shapes differently from the compiler and a tier witness that dropped traps in inlined code. Each
cost a second CI round and a second agent run.

**Parallel agents collided on shared state.** One agent's `pkill -f` pattern stopped three test
JVMs in other worktrees (now blocked by a hook, and `dev/varka_bg.sh` with the "Processes" rule in
`sql/varka/AGENTS.md`, #783); two agents edited `VarkaKernelChecks` at once (138a and 274a), and
one used `git stash`, which every worktree shares.

**Cost predictions miss on the optimistic side.** 138a predicted at most 25% on the per-row
test and measured 38%, then 27% after its review; four of 274a's six predictions missed, three of
them on cost. The plans record the misses, as they should, but the next plan's estimates do not
yet read them.

### 9.3 What comes first

| Order | Rows | Why here | Size |
| ---: | :-- | :-- | :-- |
| 1 | 271b, 138b, 274a; 325's files and 322a-2's pair in the next idle window | In flight; each closes its row or its step, and three of them close 1.1 point 2's named rows | small each |
| 2 | 255 | Takes the laptop out of CI's path and, with the benchmark pairs on GitHub-hosted runners, out of the benchmark queue; every later row's CI and every pair benefit | medium |
| 3 | 243, then 244 | 1.1 point 1 is the one done-when point with nothing in flight; 243 bounds the specification to the coverage table's operations (risk 3), and 244 names Spark's departures with a test each | medium each |
| 4 | 274b, 272, 279, 326, 327, 370, 298 | The rest of point 2's compiled-kernel and guard checks; 272 reuses 274a's witness, 279 reads 138's domains; 370 is a check of what a running JVM allows, small, with the flake's remedy; 298 last of them, the analyzer's casts at random | small to medium each |
| 5 | 313, 314, 315, 317, 318, 319, 316 | The errors rows, in the order section 4 gives them, 315 after 314 and 316 last; 317 and 319 are test strength as well | small to medium each |
| 6 | 322b, 322c | The last test code to Java; their pairs run where 255 leaves the benchmarks | medium to large |
| 7 | 281, 245 | The analyses proven and the refinement proofs, after 243 and 244 show what they cost | medium each |
| 8 | 258, 259, 260, 371 | The posts, 258 first; 260 between them and the next long post; 371 once the flake's remedy has a result | medium, small |
| 9 | 280, 257, 252, 265 | Measurement and legibility once the structure has settled; 265 last by design | small to medium each |
| 10 | 270, 261 | The milestone's post and its close | medium, small |

New rows join this table only with a reason in 1.1 to do them in this milestone; a finding that
serves no point of 1.1 is an item in `m9/SCOPE.md` (as items 93 to 95 were on 13 October).

### 9.4 Decisions for the owner

1. **245 stays or moves.** Risk 3 names it as the row to cut if 243 and 244 run long. The
   recommendation: keep it, and decide when 244 lands, from what 243 and 244 cost. *Decided by
   the owner on 13 October 2026: kept, to be decided again when 244 lands.*
2. **298 stays or moves.** A Catalyst-level grammar with casts is test strength, and the first
   reading was that 262 and 271 reach the analyzer's casts through Spark itself. They do not:
   262's fuzzer composes whole coverage rows over columns of exactly the types each row expects,
   so the analyzer inserts no cast, and in 271b's run with literals lifted, Spark's 18
   type-coercion golden files (`typeCoercion/native/*`) gave Varka no served execution, since
   their casts are between types Varka does not read. *Decided by the owner on 13 October 2026:
   kept, ordered with the rest of point 2 after 272 and 279.*
3. **The errors rows (313 to 319) stay whole.** Added by the owner on 12 October; the
   recommendation is to keep all seven, after 255 and 243. *Decided by the owner on 13 October
   2026: all seven kept, in 9.3's order, 315 after 314 and 316 last of them.*
4. **The promotion track.** 258 to 260 have not started and 180 has no commit; the recommendation
   is to outline 258 now, as milestone 6's review did for its post, so the posts stop losing the
   comparison with the next measurable row. *Decided by the owner on 13 October 2026: outlined the same day
   (`VARKA-258.md`), its measurements in 9.3's order.*

### 9.5 Practices this review changes

Proposed point by point against 9.2 and agreed by the owner on 13 October 2026.

* **Against the drift (9.2, first finding).** Until point 1 and point 5 each have a row in flight,
  every other task started comes from one of them; 243 starts as soon as 271b, 138b and 274a land,
  before 272, 279 or 298. And the state against 1.1 is written as a two-line note under this
  section after every five merged rows, not only at the halfway point, so a drift shows within
  days.
* **Against the laptop bottleneck (second finding).** 255 is the next row of work after the rows
  in flight, and the benchmark pairs move to GitHub-hosted runners through `benchmark.yml`, whose
  runner now starts the Java ports, with the datapath probe checked first, so 322a's pairs, 325's
  results files and 322a-2's pair stop waiting for an idle laptop.
* **Against the tests' growing cost (third finding).** 255 also records each gate and nightly
  step's time from CI, and a committed budget holds them; a pull request that adds more than 5% to
  a step says so, with the times before and after. The first budget is the times of 13 October, so
  138a's 27%, 274a's 6% and the golden-file step's 21 minutes are recorded rather than lost.
* **Against reviews after CI (fourth finding).** The authoring agent reviews its own pull request
  before opening it (`/code-review high` on the diff), fixes what it verifies and says what the
  review found; every agent brief ends with that, and the owner's review after CI is the second
  look (`sql/varka/AGENTS.md`, "Pull requests", #797).
* **Against agents colliding (fifth finding).** No `git stash`, no writes outside one's own
  worktree, the files an agent edits named in its first message, and tasks that edit one file run
  one after the other, each brief listing the files its agent owns (`sql/varka/AGENTS.md`,
  "Processes", #797; the pattern-kill hook and `dev/varka_bg.sh` came first, #783).
* **Against optimistic cost predictions (sixth finding).** A calibration table in
  `skills/testing-and-debugging.md` holds every scored cost prediction, predicted against measured
  with the ratio; a new plan states its cost prediction at the observed ratio unless it says why
  not (#797).
* **A finding becomes a row only through a point of 1.1**, named in the row; otherwise it is an
  item in `m9/SCOPE.md`.
* **A benchmark port merges with its pair**, or its pull request says why not and names the row
  that owes it.

## 10. Debt register

One bullet per debt: what it is, why it is a debt, and what closing it would take. Opened on 16
October 2026: milestone 7 had no register until then, and the debts it found sat in single task
plans or nowhere. Per `sql/varka/AGENTS.md`, a swept entry is rewritten in the past tense with what
the sweep found, never deleted; row 261, the close, sweeps this list. Debts already owned elsewhere
are not repeated here: the long-lane template prewarm (`m8/PLAN.md` row 383), the per-shape date
bound (`m9/SCOPE.md` item 102), the generated-class families the coverage analyser does not fold
(rows 284 and 285), and Varka's mask applied after a residual check (row 380's record).

**In code and tests**

* **`VarkaTierWitnessSuite` fails under the memory sanitizer.** "The witness sees each tier" trips
  a `speculate_null_assert` trap in `VarkaMemorySanitizer.check` under load, on master as on every
  branch that ran the gate on 16 October 2026 (`VARKA-318.md` 9.1, `VARKA-265.md` 9.1, row 380's
  outcome); it passes without the sanitizer. A suite the gate always fails hides a real failure
  of the same suite. Closing it: run the suite without the sanitizer as the JIT-measuring suites
  already do (`--no-sanitizer`), or make the witness tolerate the sanitizer's trap, with a lesson in
  `skills/the-jit.md` either way.
* **Spark's two conjunct orders are hand-ported** (row 380, `VarkaConjunctOrder`). Varka reproduces
  `FilterExec.generatePredicateCode`'s order and codegen's extra null checks by a port linked to
  Spark's code only by a comment, as Spark's own two copies are. An upstream change to either order
  would make Varka's demotion rule wrong silently. Closing it: a test that compares the port's order
  with `GeneratePredicateHelper`'s on generated conditions, or one shared function upstream.
* **The compiler entry points without the family switches stay public** (row 318, `VARKA-318.md`
  9.3). Tests and tools call them with every family on; a production caller that used one would
  ignore the user's switches. Closing it: one compile-context value carrying the options and the
  switches through every compiler signature.
* **One mutant survived both mutation passes unexplained** (row 265, its local shard check in
  #890): a removed `requireNonNull` in `Analysis.pureOf`, which the earlier sample recorded as
  killed. Swept by row 265 (`VARKA-265.md` 9.8): the mutant is equivalent, since `topoOrder` holds
  no null, and the sample's kill fits the planSize test below, which still ran in that run's
  behavioural half then; `notes.tsv` answers it.
* **`VarkaExpressionCompilerSuite`'s planSize bisection test fails under PIT for reasons of its
  own** (row 265, `VARKA-265.md` 9.8). "Under planSize the compiler cuts a projection past the
  driver's ceiling in one step" made 87 of the full run's 117 bytes-only kills, removed
  `requireNonNull` calls among them, and on a laptop rerun killed one of three alike. A
  `PinsDefaults` test that fails regardless of the mutant fills the bytes-only column with noise,
  and would turn survivors into behavioural kills were it untagged. Closing it: record the failing
  assertion (`dev/varka_pit/VarkaPitScalaTest` logs no message), then make the test independent of
  what else runs in its JVM - it reads the global `VarkaShapeCache.buildCount` - or leave it out
  of the PIT runs with that reason.
* **`catalyst/Test/checkstyle` fails on master** on upstream's `StUtilsSuite.java`, whose outer
  class
  `STUtilsSuite` does not match its file name; apache/spark's master has the same mismatch. A module
  check that always fails hides a new finding of ours in that module. Closing it: upstream's fix,
  arriving by a sync; a fork-only rename would conflict with every sync until then.

**In CI and tooling**

* **The calibration table conflicts on nearly every merge.** `skills/testing-and-debugging.md`'s
  table gains a line from most tasks, so each merge conflicts with every open branch that added one,
  and each resolution is a push and a CI restart (six or so per merge on 16 October 2026). Closing
  it: one small file per entry under a directory, with the table generated from them by the tool
  that already checks `SKILLS.md`, so two branches never edit the same lines. Owned by row 384,
  which found that a committed generated table still conflicts and keeps the entries as files
  under `skills/calibration/`, printed by `dev/varka_calibration.py` (`VARKA-384.md` 9).
* **"Install the Varka engine" downloads Maven's plugins on every run.** No job that installs the
  engine has a local Maven repository to start from, and on 16 October 2026 it failed twice on a
  transient Maven Central miss of `maven-resources-plugin` 3.4.0 (#888, #889), each costing a rerun.
  Owned by row 385, built and awaiting its first CI run: Spark's Maven cache turned out to exist in
  neither repository, so the install gets a 35 MB cache of its own, keyed on the engine pom,
  installs offline on an exact hit and retries a transfer failure (`VARKA-385.md` 9).
* **The admission queue keeps dead runs.** A run whose pull request has merged, or whose branch
  has been pushed again, still counts as ahead until it is cancelled by hand; on 16 October 2026 two
  such runs held four pull requests' full runs for hours. Closing it: `dev/varka_ci_admit.py` skips
  a run whose pull request is closed or whose head is no longer its branch's.
* **"Re-run failed jobs" on a full run skips the queue** (`VARKA-255.md` 9.4): the rerun reuses the
  first attempt's admission. Known and documented, not handled. Closing it: the admission job reruns
  with the failed jobs when any module job does, or the documentation says to use "Re-run all jobs".
* **A Java port's timing pair can approach the job's cap.** `VarkaSizeLadderBenchmark`'s pair took
  about four and a half hours of the 355-minute limit (#877). The next pair of a class that size can
  time out and lose every round. Closing it: split a long class's pair across jobs by section, or
  record a per-class round count in the pair tooling.
* **About 80 worktrees of merged branches remain on the owner's machine** (`dev/varka_worktree.sh
  list`, 16 October 2026: 83 of 105 merged). They hold disk and make `git worktree list` and the
  merge sweeps slow to read. Closing it: `dev/varka_worktree.sh gc`, with the parked ones (row 334's
  `varka-326`, the far-date reproduction) kept by name.
