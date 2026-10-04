# Changelog

One section per milestone, newest first, written at each milestone's close by its closing task
(`CONTRIBUTING.md`, "Finding work"). Each names what the milestone set out to do, what shipped,
the plan that records it row by row, and the posts that published it. Tags `varka-m<n>` mark the
commit that closed each milestone from 6 on.

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
