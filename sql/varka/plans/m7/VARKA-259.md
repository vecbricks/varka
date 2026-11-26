# VARKA-259: bytecode without source, the companion of row 258's post

*Row 259 of `m7/PLAN.md`, `m10/SCOPE.md` item 79. Planned and drafted on 16 October 2026 in the
shape of `VARKA-371.md`: the question, the claim, the evidence the record already holds, the
outline, what a reader can reproduce, and done-when. It is the pair of row 258 ("how Spark compiles
a query, and why it compiles it again", `VARKA-258.md`), published with it or right after, each
linking the other. The draft is `POST_BYTECODE.md`; it uses only what the record holds, and section
9.1 lists what is owed before publication.*

## 1. The question

The reader is a Spark or JVM developer who has read, or will read, row 258's post: Spark writes one
Java source string per stage, Janino compiles it on the driver and on every executor, a per-JVM
cache keyed by the source text skips the compile for a repeated string, and HotSpot then runs the
class through its tiers. The reader knows what a code generator is and may have written one. The
post answers: **what changes when a query engine's generator writes bytecode instead of Java?**

## 2. The claim, and the five-minute version

Writing the class directly with the JDK's Class-File API makes generating it cheap (about 99 us
against about 6.5 ms for a Janino compile in Varka's codegen benchmark, though not yet over the
same expressions), makes every method's size a fact read off the class before it is defined
instead of a guess from source length. It does not shorten the JIT's wait: a new class
still starts interpreted, and in the cold-start benchmark Varka without its warm-up is the slowest
arm on its first runs. The saving that matters is the class that is never rebuilt, not the class
that is built fast, and that comes from keying the class by a shape with its constants as slots,
which is a property of the IR, not of bytecode: Spark already passes strings, decimals and binaries
as references (`VARKA-258.md` 2).

The five-minute version: the size guess Spark has to make and Varka does not; the shape key with
constants as slots, the direct answer to row 258's hook, kept apart from what bytecode changes,
with SPARK-60048 (the key of the split decision, as `POST_CASE_WHEN_USERS.md` describes it) as
Spark meeting the question in a narrower place; the emission cost with its caveat; the cold-start
table; what a developer gives up.

## 3. The evidence the post cites

* **Emission against Janino**: `sql/core/benchmarks/VarkaCodegenBenchmark-jdk25-results.txt`,
  "janino: GenerateUnsafeProjection.generate" 6516903.0 ns a class (best 7 ms, average 8) against
  "varka: fused emit + define + load + instantiate" 99365.0 ns, 65.6X, on the laptop's Ryzen AI 9
  HX PRO 370 with JDK 25.0.4. The arms differ: Janino compiles a one-output `date_add` unsafe
  projection with a new literal each iteration (`VarkaCodegenBenchmark.java`, the cache defeat
  row 258 describes); Varka emits the two-output DAG-CSE kernel. Item 79 asks for both over the
  same expression list; that benchmark is owed (9.1).
* **The first query**: `sql/core/benchmarks/VarkaColdStartBenchmark-jdk25-results.txt`, rung 16
  (sixteen entries of `greatest(add_months(d, k), date_add(d, k), last_day(d))` over 100,000
  Arrow-cached rows): plan only 17 ms vanilla, 18 Varka (Varka's plan holds the emission); first
  run 110, 241 without warm-up, 140 with it; second run 53, 213, 62; once compiled 27; the warm-up's
  verdicts COMPILED after 1580 to 1873 ms. Provenance in the file beside it (commit `0914c607ff8`).
* **The size read off the class, and the cap met at assembly**: `m6/POST.md` section 2 and
  `skills/emitter-and-ir.md`, "The class-file cap on a method is met before it can be measured".
* **The stack-map generator as a verifier**, and the `NoSuchMethodError` it does not catch:
  `skills/emitter-and-ir.md`, "The Class-File API's Stack-Map Generator Is a Free Verifier".
* **Line numbers as IR node indices**: `skills/emitter-and-ir.md`, "Generated Code Can Carry Its
  Own Debug Info".
* **The shape key**: `m6/POST.md` section 2 (`date_add(d, 7), year(d)` and `date_add(d, 30),
  year(d)` share a hash) and `spark.sql.codegen.varka.cache.maxEntries` in `StaticSQLConf`.
* **Spark's side**: `spark.sql.codegen.methodSplitThreshold`'s documentation and the seven
  characters a byte of `m6/POST.md` section 1; SPARK-60048 as cited by `POST_CASE_WHEN_USERS.md`.

## 4. The outline

1. **From row 258's post.** One paragraph of its pipeline, and the question.
2. **What a string costs.** The size guess, and the cache key that holds the literals.
3. **Writing the class directly.** The size read, not estimated (with the 65,535-byte cap met at
   assembly); the assembler's stack check.
4. **What the class is keyed by.** Constants as slots, a property of the IR rather than of
   bytecode; Spark's references for strings; SPARK-60048.
5. **What emission costs.** The codegen benchmark's two arms, and why they are not yet like for
   like.
6. **What it does not change.** The cold-start table and the warm-up.
7. **What you give up.** No source to read, and the tools that replace it.
8. **Reproducing it.**

## 5. What a reader can reproduce

* `dev/varka_emit.sh "date_add(d,7)" "year(d)"`: the shape, its hash, the literals in slot order
  and every method's bytes.
* `dev/varka_bench_regen.sh core VarkaCodegenBenchmark` and the same for
  `VarkaColdStartBenchmark`, with the machine stated.
* On stock Spark, `df.explain("codegen")` for the generated Java and `maxMethodCodeSize`.

## 6. Done when

A benchmark that emits and Janino-compiles the same expression list is committed with its
provenance and quoted in the post; the draft is kept short (the owner cuts length from Varka's
public writing); every number traces under `dev/varka_quote_check.py`; and the post is published
beside row 258's, each linking the other.

## 7. Sequencing

1. This plan and the draft, and the row marked drafted.
2. The matched benchmark, on an idle machine or a GitHub runner.
3. Row 258's publication, then this post's, then 9.3 (9.2 is the matched benchmark).

## 8. Explicitly out of this task

* Varka's correctness story (the proofs, the differential), which is the closing post's (row 270).
* The size control in detail, which `m6/POST.md` told; the post links it.
* Proposing a literal-free cache key upstream: SPARK-60048 is filed, and the post cites it.

## 9. Outcome

### 9.1 The draft, 16 October 2026

`POST_BYTECODE.md` is the draft, about 1300 words on 4's outline, written from the evidence of 3
only. Owed before publication:

1. **The matched benchmark** item 79's done-when asks for: emission against Janino's compile of the
   same kernels from the same expression list, committed with provenance. Until then the draft
   quotes `VarkaCodegenBenchmark` with the difference between its arms stated, and a bracketed note
   marks the table to replace.
2. **Row 258's published link**: its post is outlined, not drafted; the draft's first link is a
   placeholder.
3. **The owner's edit.**

### 9.2 The matched benchmark, 16 October 2026

9.1's first owed item. Planned and built on 16 October 2026; the numbers come from a GitHub-hosted
runner and are recorded here when they exist.

**Where it lives.** A second section of `VarkaCodegenBenchmark`, "One expression list through
Janino and through Varka (VARKA-259)", not a class of its own. The rule that gives a new feature
family its own benchmark class is about expression families; this is the question the existing
section asks, generation time, asked again over matched inputs. The `sections` input of
`benchmark.yml` (VARKA-256) lets the runner replace this section alone and keep the first, which
the draft quotes, as it was measured on the laptop. The first section's code is unchanged; its
`benchmark.run()` became `runCases`, which runs it the same way and lets a dry run skip it. The
cost of sharing the file is that the Relative columns of the two sections are on different
machines and must not be compared with each other.

**The lists.** Five, each bound against the columns `d`, `d2` (dates) and `i` (int): 1, 4, 16 and
64 date expressions, and 16 int expressions. Entry `k` of a date list is the `k % 4`-th of
`date_add(d, c)`, `date_sub(d, c)`, `datediff(date_add(d, c), d2)` and `year(date_add(d, c))`;
of the int list, the `k % 3`-th of `i + c`, `i - c` and `-(i + c)` under the session's
arithmetic mode (not `i * c`, which Varka declines; see the local check below). Every entry has its own constant `c`, so no two share a subexpression and neither side's
common-subexpression elimination has work: the 1-entry list is the first section's Janino
projection. Both arms receive the same `List<NamedExpression>` from one builder; Spark binds it
in `GenerateUnsafeProjection.generate`, Varka in `VarkaExpressionCompiler.compile`.

**The arms**, one table per list, Relative against the first row:

| Row | What one iteration does |
|---|---|
| spark: generate + Janino compile, cold | `GenerateUnsafeProjection.generate` over a list with new constants: source text, a Janino compile, the instance |
| varka: compile + emit + define + load + instantiate, cold | `VarkaExpressionCompiler.compile`, the shape key, `getOrEmit` on a private `VarkaShapeCacheImpl` of capacity 0 (the cache disabled: every lookup hashes the shape, emits, defines in a fresh loader, and the entry is evicted as it loads), then `newKernel()` |
| spark: generate, class cache hit | the same call over one fixed list: the source text is generated again and found in Janino's cache |
| varka: compile, shape cache hit | the same compile and lookup on a private cache that holds the class, then `newKernel()` |

Cold for Janino means a source text it has not seen: each call moves every constant by a round,
and the rounds cycle through 1000 against the default cache of 100 classes
(`spark.sql.codegen.cache.maxEntries`); the constants start at 1000 so that no text is one the
first section compiled. The existing row takes its literal from the iteration index, which is -1
for the whole warm-up, so its warm-up compiles once and hits after that; the new rows take the
round from a counter instead. Varka's shape does not depend on the constants (`VarkaShapeKey`),
so moving them cannot defeat its cache; the capacity-0 cache does. The hit rows both compile the
round kept outside the cycle.

**Checked before each table, every run.** The list is printed (`sql()` of every entry), and the
run fails unless: Varka fuses every entry, one output each; Varka's shape key is the same in four
other rounds, the cycle's ends included; two new rounds raise Spark's `compilationTime` histogram
count by two and a repeated fixed list by none; the capacity-0 cache misses on every call; and
the warm cache hits on the fixed list and on a list with new constants. The last is what the
post's "never make the class again" rests on: Janino hits only on the same text.

**Checked after each table, every timed run.** Janino compiled exactly once per cold call it timed
or warmed (plus at most once for the hit list, which the cold row's new classes push out of its
cache), Varka's cold cache emitted once per cold call, its warm cache emitted nothing, and the
JVM-wide cache the compiler's admission asks built nothing. A run fails before the section starts
if Janino's cache holds as many classes as the cycle has rounds.

**What a timed call holds and does not.** Every list a timed call compiles is built before the
table (1000 rounds and the hit list), so no row times building expressions. Varka's key is the
evaluator's: default emit options, warmed as the session's warm-up setting says (on by default,
so the classes are named with the `w` mark), as the compiler's admission keys it too.

**Predictions**, before any number, for the runner (an EPYC 7763 in all but one runner-made
results file so far), guessed from the laptop's first section with no measurement of these lists:

* Janino cold grows from a fixed cost with the entries: about 8 ms for 1, 10 for 4, 20 for 16,
  60 for 64.
* Varka cold: about 0.1 ms for 1, 0.2 for 4, 0.5 for 16, 2 for 64; the ratio falls from about
  80x at 1 to about 30x at 64, since the compiler's walk and the emission grow with the entries
  and Janino's fixed cost does not.
* The hits: Spark's source generation alone at a tenth of its cold row or more (0.5 ms for 1,
  5 for 64), Varka's compile and key lookup at a tenth of its cold row or less.
* Wall clock: five tables of four cases at the `timed()` settings, at least 4 s a case, about
  two minutes for the section beside the runner's build.

**The runner's run.** Dispatched on the fork, with only this section taken from the run and the
first kept as committed (`dev/varka_bench_sections.py restore` appends a section the committed file
lacks):

```
gh workflow run benchmark.yml -R MaxGekk/spark --ref varka-259-bench \
  -f class=VarkaCodegenBenchmark -f jdk=25 -f generate-tpcds=false -f create-commit=false \
  -f extra-java-options="--add-modules jdk.incubator.vector" \
  -f sections="One expression list through Janino and through Varka (VARKA-259)"
```

The CPU is not pinned: the post compares rows within this section, which share a machine. The
run's provenance sidecar will describe the whole file as runner-made, so its `cpu:` line is
annotated, when the file is committed, to say that the first section is the laptop's.

**The local check, 16 October 2026** (laptop, cores 3 to 5 shared with another agent, so no
timing is read from it):

* `-Dvarka.bench.dryRun=true` prints the five lists and passes every check above. Varka's classes,
  warmed names: 1 output from 1 input with 1 literal slot, 3345 bytes; 4 outputs, 2 inputs, 4
  slots, 6133 bytes; 16 outputs, 16 slots, 16435 bytes; 64 outputs, 64 slots, 58404 bytes; the
  int list 16 outputs from 1 input, 16417 bytes.
* The first int list did not compile: Varka declines a checked `i * c` whose operands do not rule
  out overflow ("checked int multiply whose operands do not rule out overflow", entries 2, 5, 8,
  11 and 14), so the third int form became `-(i + c)`. The check caught it because it refuses a
  list Varka does not fuse whole and prints the declines by position; a benchmark that timed a
  partial kernel against a whole Janino projection would have compared nothing.
* An untimed full run (no results file) ran every case of both sections to the end and passed
  the checks after each table.
* `/code-review high` on the first commit found nine points, all about whether the rows measure
  what they say; seven were fixed in the second commit: the cycle checked against Janino's cache
  size, the timed calls themselves checked after each table (the first version checked only two
  rounds the timed rows never compile), the admission's builds checked, the key built as the
  evaluator builds it (it had dropped the warmed flag), the lists built outside the timed calls
  (which also removed a per-call copy of Spark's list), and two `var`s whose right side does not
  name the type. Not changed: both cold rows define a class per call, thousands a second for
  Varka, so metaspace collections can land in timed iterations, which the averages carry more
  than the best times; and the check before each table also runs in a dry run and under a filter
  that times none of its cases, which is what the dry run is for here.
* Measured and not anticipated in the plan: the compiler's size admission (`VarkaSizeAdmission`)
  emits through the JVM-wide cache the first time it sees a shape. The check before each table is
  that first time, so in the timed rows the admission is a lookup and the private cache's
  emission is the one build. That matches a cold production query, which builds once at the
  admission and defines at the lookup.

**The runner's numbers, 16 October 2026.** Run 38890693365 on `MaxGekk/spark` at `47998915f11`, an
AMD EPYC 7763 with Zulu JDK 25.0.4.1; `VarkaCodegenBenchmark-jdk25-results.txt` holds this section
from the run and the first section as committed (byte-identical, checked before the commit), and
the provenance sidecar's `cpu:` line says which is which. Per class, best of the run:

| List | Janino cold (ns) | Varka cold (ns) | Ratio | Spark hit (ns) | Varka hit (ns) | Varka hit against Janino cold |
|---|---|---|---|---|---|---|
| 1 date | 1471760.0 | 292781.0 | 5.0X | 60854.0 | 5240.0 | 280.9X |
| 4 date | 2602249.0 | 445489.0 | 5.8X | 267654.0 | 9017.0 | 288.6X |
| 16 date | 4190322.0 | 1198206.0 | 3.5X | 1025361.0 | 35717.0 | 117.3X |
| 64 date | 10665113.0 | 4275725.0 | 2.5X | 4056213.0 | 331014.0 | 32.2X |
| 16 int | 4517241.0 | 1153733.0 | 3.9X | 1023839.0 | 41198.0 | 109.6X |

What it says. Over the same expressions, a cold Varka class costs 2.5X to 5.8X less than a cold
Janino class, not the first section's 65.6X: that row timed the emitter alone on a different
kernel, while the matched row also runs the compiler, hashes the shape and goes through the cache.
The gap narrows with width, because Varka's cost grows with the entries about as fast as
Janino's does past four. Spark's cache hit is not free: it generates the source again on every
call, and at 16 and 64 entries that alone costs about what a cold Varka build does (1025361.0
against 1198206.0 ns, 4056213.0 against 4275725.0). Varka's shape hit is the large difference,
32.2X to 288.6X under Janino's cold compile, and it holds across constants, which Janino's does
not.

**The predictions, scored.**

* Janino cold, 8 / 10 / 20 / 60 ms: **wrong by four to six times** (about 1.5, 2.6, 4.2 and 10.7
  ms). The guess scaled the laptop's 6516903.0 ns row. A likely part of that gap, not measured
  here: the first section's Janino row takes its literal from the iteration index, which is -1
  for the whole warm-up, so its warm-up compiles once and Janino's own code reaches the five timed
  compiles barely JIT-compiled, where the new rows warm it on hundreds of compiles. The machines
  differ too, so the file cannot separate the two.
* Varka cold, 0.1 / 0.2 / 0.5 / 2 ms: **low by two to three times** (about 0.29, 0.45, 1.2 and 4.3
  ms). The guess started from the emitter-only row and left out the compiler and the cache.
* The ratio falling from 80x to 30x: **the direction right after four entries, the size wrong
  by an order of magnitude** (5.0X, 5.8X, 3.5X, 2.5X).
* Spark's hit at a tenth of its cold row or more, 0.5 ms for 1 and 5 for 64: **right at 4, 16 and
  64 entries (a tenth, a quarter, two fifths), wrong at 1** (about 4 percent, 60854.0 ns); 5 ms at
  64 was close (4056213.0 ns), 0.5 ms at 1 was eight times high.
* Varka's hit at a tenth of its cold row or less: **right**, from about 2 percent at 1 entry to
  about 8 at 64.
* Wall clock about two minutes: **not scored**; the results file does not carry it, and the job
  log was not read.

### 9.3 Published, 16 October 2026

The post went live on 16 October 2026 at https://vecbricks.github.io/the-class-got-cheaper/,
built from the merged Markdown with `dev/varka_post_page.py`. Row 258's post was not yet published,
so the page says a companion post follows; the link is added when 258 goes live, and the
repository text already carries it (#867).
