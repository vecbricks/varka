# Changelog

One section per milestone, newest first, written at each milestone's close by its closing task
(`CONTRIBUTING.md`, "Finding work"). Each names what the milestone set out to do, what shipped,
the plan that records it row by row, and the posts that published it. Tags `varka-m<n>` mark the
commit that closed each milestone from 6 on.

## Milestone 7: trust and maintainability (2 to 18 October 2026)

Tag `varka-m7`. Plan: [`sql/varka/plans/m7/PLAN.md`](sql/varka/plans/m7/PLAN.md),
115 task rows: 112 done (one, the IR hash cache, closed on its measurement), 2 moved to
milestone 8, and the close.

* **Every bounded lowering is proven.** SMT-LIB proofs under `sql/varka/proofs/`, Java's
  operators stated once and held to the JVM, run by `dev/varka_prove.sh` in the linters' job
  under Z3 and nightly under cvc5 as well: the multiply-high division exact for every divisor
  in use, both long-lane division forms with their exact dividend bounds, every calendar
  division site with its first failing dividend pinned, and the range analyses that supply the
  guards' bounds (one latent unsound rule found and fixed). A specification fragment from the
  SQL-92 text (`proofs/spec/STANDARD.md`), Spark's eleven departures as named deltas with a
  test each (`DELTAS.md`), and the lowerings restated as refinements of it in Spark mode and
  standard mode (216 checks).
* **The tests reach what they missed.** Vanilla Spark as the oracle at random, with a shrinker,
  saved reproducers, pivot-rectified filters, nested trees with casts and forced declines;
  Spark's own golden files through an Arrow cache in both arms; every IR tree to size 5 at both
  lanes; eight variants of every passing shape; kernels compared compiled by C2 and under the
  interpreter, C1, intrinsics off and C2's stress flags, with JFR naming the tier; a memory
  sanitizer on in every suite; poisoned null lanes and both validity fills; a verdict per guard
  at every bound; the reference evaluator held to Catalyst's `eval`; a hidden kernel failure
  fails its test. Measured by branch coverage of the emitter and of the classes it emits, what
  the emitter reached, and a mutation run of 1,760 mutants with every survivor answered. The
  warm-up flake's cause named - C2's compile-wide trap sums - and removed at the preferred
  species by a once-per-JVM template warm-up.
* **Varka's code is Java and legible.** The compiler, its shape cache, the evaluators, the
  reference evaluator, the fuzz infrastructure, the test helpers, the censuses and all 26
  benchmarks ported, each with the proof its row named; Error Prone and NullAway on every pull
  request; one options table, one class for method names, `emitBody` split, the size loop as
  named steps, the refusals and the operand admission each stated once, about 290 comments
  rewritten to describe the code as it is; and a family kill switch, error conditions with a
  context record, a reproduction file per kernel failure, and a troubleshooting runbook.
* **CI off the laptop.** An admission job inside the Build, master's head run after each merge,
  benchmark pairs and results files on GitHub-hosted runners, a step-time budget, section-wise
  benchmark regeneration, the gate in parallel, and the slow mode a kernel meets after other
  kernels as a number in the nightly.
* **Wrong answers found and fixed.** A declined filter batch refiltered out of Spark's conjunct
  order; a fused conjunct run before a residual conjunct Spark evaluates first; a cached date
  column past years 0001 to 9999 given wrong calendar fields; a filter raising a later row's
  error before the projection's earlier one; a `NULL`-typed column crashing the filter
  fallback; a `Long.MIN_VALUE` interval admitted by a one-day guard; and a narrowed output
  mapped at twice its width, found by the sanitizer. One disagreement waits on Spark's own
  answer upstream (SPARK-60315).
* **The close's own regression, found and fixed.** Regenerating the cold-start benchmark for
  this entry released every projection shape of 16 to 100 entries at the warm-up's deadline,
  where the file of 28 September had all 71 compiled: the Vector API template warm-up, run after
  other vector code had taken the shared templates to C2, deoptimised them, and a wide kernel
  summed that one decompile six hundred times past C2's cutoff. The warm-up now runs before any
  vector code (VARKA-389); the one-line wrappers it calls cross their first compile inside its
  paced rounds, so a four-core host no longer compiles them without a profile (VARKA-391); and CI
  retries a listed flaky suite in a fresh JVM, saying so on the run (VARKA-390).
* **Seven posts.** [How Spark compiles a query](https://vecbricks.github.io/how-spark-compiles-a-query/)
  and [Spark's code generator by the numbers](https://vecbricks.github.io/nine-numbers/) for
  Spark users; [The class got cheaper](https://vecbricks.github.io/the-class-got-cheaper/) and
  [Why a warmed kernel can still allocate](https://vecbricks.github.io/why-a-warmed-kernel-can-still-allocate/)
  and [Two compilers, two queues](https://vecbricks.github.io/two-compilers-two-queues/)
  for JVM developers; the cliff's sequel,
  [A large CASE WHEN, split only where it has to be](https://vecbricks.github.io/split-only-where-it-has-to-be/);
  and the milestone's own, [How a SQL engine is tested](https://vecbricks.github.io/where-the-checking-stops/),
  for testers.
* **Moved to milestone 8.** Promotion as a standing cadence, and the filter whose answer
  depends on Spark's pending upstream decision, with the nightly's nested-tree switch that
  waits on it.

## Milestone 6: the compiler's foundation (23 September to 3 October 2026)

Tag `varka-m6`. Plan: [`sql/varka/plans/m6/PLAN.md`](sql/varka/plans/m6/PLAN.md),
77 task rows: 63 done, 13 moved to milestone 7.

* **No shape Varka admits can fail to emit.** One byte budget, 8000 bytes, over every emitted
  method - the loop methods, the epilogues and the driver, which calls its groups through a
  table and, past the budget, through stages - with a shape the caps cannot serve declined at
  compile time with a reason instead of thrown. A cost model predicts each method's bytes and
  call sites before emission, an exact partition of the outputs is a dynamic program over it,
  and since VARKA-236 a kernel's size is planned before its first build: every shape of the
  audit's corpus builds once.
* **Measured against vanilla Spark.** The size ladder, with Spark's step at its method-size
  limit against Varka's line; one realistic query where Spark logs that whole-stage codegen
  was disabled and Varka does not; and a census, from Spark's own source, of every place its
  code generation gives up, with Varka's answer to each.
* **The compiler and the infrastructure.** The emitter split by family and the first family
  ported to Java; a test watchdog that names a hung test; a CI queue that gives the fork's
  twenty jobs to one pull request at a time; benchmark regeneration with provenance, bands and
  a canary; the task tables mirrored as issues.
* **Three posts.** [The 8000-byte cliff](https://vecbricks.github.io/the-8000-byte-cliff/),
  on where vanilla Spark's code generation gives up;
  [Under 8000 bytes by construction](https://vecbricks.github.io/under-8000-bytes-by-construction/),
  on how Varka's emitter keeps every method under the limit; and
  [When Spark stops compiling your query](https://vecbricks.github.io/when-spark-stops-compiling-your-query/),
  for Spark users.

## Milestone 5: 64-bit lanes and TIME (September 2026)

Plan: [`sql/varka/plans/m5/PLAN.md`](sql/varka/plans/m5/PLAN.md), re-scoped
on 15 September 2026 to one lane and the types that share it: 46 rows done, 43 moved to the
coverage catalogue, 9 withdrawn. Long lanes for `bigint`, `TimestampNTZ` and the day-time
interval; the `TIME` type, which Apache Spark was about to enable by default, with its field
extraction, arithmetic and truncation as int-lane kernels; the exact 64-bit divisions by
constant with their dividend bounds; the `-128bit` companion files that measure every kernel
at the narrow width too. Post:
[Eight rows per instruction](https://vecbricks.github.io/eight-rows-per-instruction/).

## Milestone 4: the date family, and the emitter under it (September 2026)

Plan: [`sql/varka/plans/m4/PLAN.md`](sql/varka/plans/m4/PLAN.md), re-scoped
on 4 September 2026 to the `DateType` family: 41 of 46 rows done, the rest moved to milestone 5.
Every date expression Spark has over int32 day counts, the civil-from-days decomposition shared
across outputs, ANSI integer arithmetic in int lanes, the shape cache and the coverage oracle;
closed by the measurement of every date expression on a 512-bit datapath against stock Spark.

## Milestone 3: reach (late August to early September 2026)

Plan: [`sql/varka/plans/m3/PLAN.md`](sql/varka/plans/m3/PLAN.md), six rows,
all done: the fused loop reaching more of a projection - literals as slots, nulls by validity
words, the first several-output kernels - and the benchmarking method the project kept
(`VARKA-14.md` 2.1).

## Milestone 2: generate the vector loop, not a call to it (August 2026)

Plan: [`sql/varka/plans/m2/PLAN.md`](sql/varka/plans/m2/PLAN.md), eight of
nine rows done: the Class-File API emitter that generates the vector loop over Arrow buffers
instead of calling a hand-written kernel, which every milestone since has built on.

## Milestone 1: the MVP (24 to 27 August 2026)

Plan: [`sql/varka/plans/m1/PLAN.md`](sql/varka/plans/m1/PLAN.md), eight
tasks, all done: date arithmetic over `ArrowColumnarBatch` through the Vector API, the standalone
engine module, and the first measured speed-up over Spark's row engine.
