# VARKA-369: The fuzzer's count arm

## 0. What exists now (admission check)

From master at `fa698652fd2` (30 October 2026), with row 27's steps 3 and 4 read on the local,
unpushed branch `varka-27-arm` (`d38900cb9b9`) by `git show`, and row 352's design read in its
merged plan `VARKA-352.md`, none of whose parts is built yet. sql/core test paths are under
`sql/core/src/test/scala/org/apache/spark/sql/execution/` unless named. The row's text is the
milestone plan's 2.10 (its last paragraph but one) and the owner's decision A4. The counts and the
fixture arithmetic below are in `m8/results/VARKA-369-admission.txt`, produced by a script over
`coverage.json` and integer arithmetic; no JVM was started for this plan.

1. **The Spark-level fuzzer compares twelve rows.** `VarkaSparkFuzz.drawRows` draws each case's
   data as `drawData(rnd, 12, ...)` (`VarkaSparkFuzz.scala:283`), cached as the view `fz` in two
   partitions on `local[2]` (`VarkaSparkDifferential.scala:225-226`, `:345-349`). At the default
   rows per cached batch, 10000 (`SQLConf.ARROW_EXECUTION_MAX_RECORDS_PER_BATCH`, read by
   `ArrowCachedBatchSerializer.scala:107`), a case is one or two short batches. So no case reaches
   a full vector group past the first few, a batch after another batch in the same task, or a
   tail of every length: the reach the row names.
2. **The partition check compares rows, on a quarter of the compositions.** `partitionBroken`
   (`VarkaSparkFuzz.scala:486-505`) runs the whole query and its three parts and compares the
   union of the parts' rows with the whole's, on both engines, when `it % partitionEvery == 0`
   with `partitionEvery = 4` (`VarkaSparkFuzzSuite.scala:73`). 352's 2.4 rewrites it (both pools,
   the raise rule, seven queries). Nothing anywhere compares counts.
3. **An aggregate's input never reaches a kernel.** `COUNT_IF(p)` is `Count(NullIf(p, false))`
   (`CountIf.scala:47`), and an aggregate function's input is evaluated inside
   `HashAggregateExec`; Varka's rule rewrites only `ProjectExec` and `FilterExec`
   (`VarkaColumnarRule.scala`, both stages). `VarkaDifferentialSuite` asserts it: `SELECT
   max(date_add(d, 1)) AS m, count(*) AS c` is `expectFused = false`
   (`VarkaDifferentialSuite.scala:455-456`). So `SELECT COUNT_IF(p) FROM fz` is the row engine's
   answer on either session, and a comparison of it with itself would pass vacuously. A projection
   below the aggregate does stay: `CollapseProject` merges a `Project` over an `Aggregate`, never
   an `Aggregate` over a `Project` (`Optimizer.scala:1706-1714`), so in `SELECT COUNT_IF(c) FROM
   (SELECT (p) AS c FROM fz)` the inner projection plans as a `ProjectExec` that Varka can take.
   *Read from the code, not run:* 369a's first test asserts the Varka node in that plan (2.3).
4. **The filter side has two plumbings and the count is in only one.** Under a row consumer such
   as `COUNT(*)`, the filter is `VarkaFilterColumnarToRowExec`, which walks the batch and emits a
   row where `VarkaSelectionBitmap.isSet` (`VarkaFilterExec.scala:532-560`) and counts emitted rows
   as its `numOutputRows` (`:561`). The compacting `VarkaFilterExec` runs only under a columnar
   consumer, such as a fused projection above it, and there `filterCompact` takes the batch's
   length from `selection.count()`, which `countSet` computes a word at a time with the last word
   masked (`VarkaFilterEvaluator.java:240-242`, `:304-322`; `VarkaSelectionBitmap.java:103`, row
   27's step 2). It forwards the child's columns when every row survives and skips the copy when
   none does (`VarkaFilterEvaluator.java:309-322`), so the copy runs only on a batch of mixed
   selectivity. `VarkaDifferentialSuite.scala:482-490` reaches the compacting node by a
   `date_add` projection over a filter.
5. **The partitions are rewritten before Varka sees them.** `BooleanSimplification` turns `NOT (d
   < d2)` into `d >= d2`, `NullDownPropagation` pushes `IS NULL` onto the columns, and
   `InferFiltersFromConstraints` adds `isnotnull` conjuncts; `VarkaPartitionOracle.REWRITES`
   excludes the three, and `NULL_SAFE_REWRITES` adds `NullPropagation` for a null-safe predicate
   (`sql/core/src/test/java/org/apache/spark/sql/execution/VarkaPartitionOracle.java:55-72`).
   Row 27's differential runs its outputs under those exclusions (`VARKA-27.md` 12, departure 2).
6. **The sessions.** `VarkaSharedSessions` gives `spark` (Varka off) and `varkaSpark`, both on
   the Arrow cache serializer with the vectorized reader, the warm-up off so a kernel serves a
   shape's first batch (`VarkaSharedSessions.scala:112-126`), the cache kept across tests since
   row 501, and `sameCacheReads` to hold both plans to the same cached tables (`:426-435`). The
   option matrix's batch-size axis sets the rows per cached batch for the whole JVM to 1, 3 or 17
   (`VarkaSharedSessions.scala:68-70`; `VarkaMatrixRuns.java:79`).
7. **The cache prunes batches by their statistics, and leaves NaN out of them** (SPARK-58482).
   `VarkaSparkDifferential.withFloatingPruningOff` turns `IN_MEMORY_PARTITION_PRUNING` off on
   both sessions only where a query reads a floating column (`VarkaSparkDifferential.scala:43-52`).
   A filter over a batch the statistics drop never runs its kernel on either side, which is row
   504's first example of a check passing while testing nothing.
8. **Fault injectors already ride the options.** `VarkaEmitOptions` carries `forceResidualAt` and
   `misdescribeRollback`, zero in production, on the shape key, the defaults hashing as they always
   hashed (`VarkaEmitOptions.java:442-450`, `:77`), and `VarkaSparkFuzzSuite` plants one through
   `VarkaColumnarToRowExec.setEmitOptionsForTesting` with a seed of its own
   (`VarkaSparkFuzzSuite.scala:500-512`, the `plantedSeed` of `:80-81`).
9. **Row 27 on its branch.** Step 3 adds `BoolOutput(cond)`, which writes the condition's
   known-true bits into a `BitVector`'s data and its known bits into the validity; step 4 sends a
   `BooleanType` projection entry to `compileCond` and wraps it, so every one of the predicate
   rows fuses as `SELECT (p) AS c`. Its tests plant two faults in the emitter, the masked validity
   written as `kt` alone and the driver's zero of the data bitmap left out (`VARKA-27.md` 11 on
   the branch). Until it merges, a boolean expression at a projection's root declines
   (`VARKA-27.md` 0 item 1), and the only projection that holds a condition is a value over it,
   such as `CASE WHEN p THEN 1 ... END`.
10. **352's parts this row plugs into, none built.** The descriptor `VarkaFuzzTypes` with a
    `SparkColumns` site per type ("the fixture columns (names, SQL type, whether derived), a cell
    drawer each for the safe, hostile and wide pools", `VARKA-352.md` 2.1), its `tlpShapes` per
    type (2.4), the value sources and sweeps (2.5), and one Spark-level step in `varka-fuzz.yml`
    (2.7). The cell drawer writes `VALUES` text, which fits twelve rows; `ValueSource` yields lane
    bits for the IR fuzzer. Neither builds a fixture of a quarter of a million rows.
11. **The coverage table** has 337 rows, 164 of them predicates, over 24 fixture columns named in
    its `columns` map (`m8/results/VARKA-369-admission.txt`). `VarkaSparkFuzz.coverageRows`
    drops every row over a boolean or narrow column (`VarkaSparkFuzz.scala:134-139`) until 352's
    second step lifts the filter.
12. **The nightly.** `varka-fuzz.yml` runs at 02:00 UTC with jobs `fuzz` and `series`, both on
    catalyst's test classpath, and `report-failure` opens an issue for a failed scheduled run
    (`needs: [fuzz, series]`, `.github/workflows/varka-fuzz.yml:347-372`). No job compiles sql's
    tests; rows 368 (368b) and 537 plan the one that will, and 352 adds its Spark-level step to
    it ("one scheduled run", `VARKA-352.md` 5).
13. **The four harness traps** (`VARKA-499.md` 0): (a) a kernel of two lanes in the shared JVM
    under `lanesOverride=4` or `narrowHalfSpecies=true`; no sql/core Scala suite is
    `@VarkaSuite(ownJvm = true)` today, `VarkaSparkFuzzSuite` included; (b) a batch the cache
    prunes at one row a batch; (c) a skip line on a seeded test going stale, fixed by 499(c)'s
    `draws` kind on a named seeded set; (d) an own-JVM class holds no dynamic tests.
14. **The proofs reading** that the row cites has no file in this folder. Its verdicts are the
    milestone plan's section 9 (the "proofs-bits-fuzzing" reading): TLP's grouping and aggregate
    oracles were not taken, since they test operators this milestone does not fuse. This row uses
    the aggregate as an instrument over fused filters and projections, not as the operator under
    test, so it stands with that verdict. Milestone 7's reading records the cost of counts as an
    oracle: TLP's authors found that comparing counts missed 5 of 48 bugs that comparing rows found
    (`m7/READING.md`, the Rigger and Su paragraph).

### What differs from the row text and the milestone plan

- **"`COUNT_IF(p)`" has to be a projection below the aggregate** to reach a kernel (item 3): the
  arm's projection side is `SELECT COUNT_IF(c) ... FROM (SELECT (p) AS c FROM fz)` once 27 lands,
  and a value over the condition before it. `COUNT_IF(p)` written directly is the row engine's,
  and serves as the ground truth's form on `spark`.
- **The filter side is two plumbings** (item 4): the row boundary's bit walk and the compaction's
  count and copy. The arm counts both, so a disagreement names one.
- **"The three-part sum"** is read as `COUNT(*) WHERE p + COUNT(*) WHERE NOT (p) + COUNT(*) WHERE
  (p) IS NULL = COUNT(*)` on the filter side. On the projection side it holds by construction
  (each row's `c` is one of true, false and null), so there the arm compares each of the three
  counts with the filter's and with Spark's instead.
- **The pruning trap is closed for every predicate**, not only floating ones (item 7).
- **The fixture needs typed values, which 352's descriptor does not yet yield** (item 10): an open
  point for the lead (section 8, point 1).
- **Counts trade oracle strength for reach** (item 14). The arm's drill-down (2.5) gives back the
  row-level place of a disagreement, so what it finds is reported as rows, not only as a number.

## 1. Goal and non-goal

One more check in the Spark-level fuzzer's family: over a cached fixture of 264160 rows in 64
partitions, every predicate the fuzzer draws is counted through each plumbing Varka has for a
condition - the filter at the row boundary, the compacting filter, a value over the condition and,
once row 27 lands, the boolean output - and each count is held to Spark's with Varka off, and the
filter's three parts to the row count. A disagreement is localised to one plumbing, or to the
condition they share, and then to a partition, batch and lane position. It runs at a small size in
every pull request and the option matrix, and at full size nightly. Not this row: new draws or
types (352 and the type rows, read through 352's descriptor), the row-level partition check
(352's 2.4), Spark's own suites (368), hostile values and raises at scale (section 7).

## 2. Design

### 2.1 The fixture

**Geometry.** `P = 64` partitions; partition `k` holds `m_k = 4096 + k` rows, `k = 0..63`,
264160 rows in all. It is built from `sparkContext.parallelize(0 until P, P)` with
`mapPartitionsWithIndex`, each partition generating its own rows from `(seed, k, j)`, so a
partition holds exactly its rows in order and the cache cuts them into batches in that order
(`repartition` would hash them). Two batch sizes, one per nightly JVM:

| B | batches | full batch mod 64 | short batches | what it reaches |
|---|---|---|---|---|
| 4096 | 127 | 0 | 63, lengths 1 to 63 | the dense body over whole groups, then every tail |
| 4093 | 128 | 61 | 64, lengths 3 to 66 | an epilogue in every full batch at every lane count |

At every lane count from 2 to 64 the batch lengths cover every residue, and every lane position
holds at least 4035 rows (`m8/results/VARKA-369-admission.txt`). A lane count is the vector width
over the element's bits: 2 to 16 lanes at 128 bits, 4 to 32 at 256, 8 to 64 at 512, so the
residues mod 64 also cover every bitmap word's tail. Each partition crosses one batch boundary, a
full batch followed by a short one: the case where state a kernel or a bitmap keeps from the
longer batch would show past the shorter one's end, which a single batch cannot show. And with
over a hundred batches a query and hundreds of draws a night, a kernel is compiled by C2 in the
middle of a query, so batches served before and after the JIT's tier change are compared alike,
which the twelve-row cases rarely reach (the wrong answer of row 396 came from C2's code on one
runner); the job records the CPU and the vector width first (2.7).

**Small sizes.** In a pull request, `P = 65`, `m_k = 67 + k`, `B = 67`: 6435 rows in 129 batches,
the short batches 1 to 64 rows, every residue mod 64 covered. Under the matrix's batch-size axis
the arm takes the JVM's size and the same 6435 rows: at 1 and 3 rows a batch it tests the
batching edges, not the lanes, which no batch that short reaches (the admission file's table),
and says so in its info line.

**Columns.** The 24 the coverage table names, by those names, so every coverage row and nested
tree applies unchanged (item 0.11); the decimal columns of 352's 2.5 by its names (`d9_2` to
`d38_10`); and four the draws never name: `rid` (a long, `k << 20 | j`), `pk`, `pb` and `pj`
(ints: the partition, the batch index and the row's position in the batch, for the drill-down).
A type whose Spark site in 352's descriptor is `Owed` is left out, and the draws that read its
columns are skipped and counted, as `coverageRows` skips boolean and narrow rows today; a test
fails if a coverage-table column is neither in the fixture nor owed (completeness, row 504).

**Values.** Each column from its type's descriptor, safe pool only (inside every guard and ANSI
check, as `drawData`'s safe pool), with the floating specials, the decimal bounds and the narrow
edges of 352's 2.5, which raise nothing in a comparison. Nulls by the IR fuzzer's five patterns
(null-free, every fifth, alternating, all null, random; `VARKA-352.md` 0 item 6), pattern
`(k + c) mod 5` for column `c` in partition `k`, the random one at a density of 0.1 or 0.5 by
`k`. So every tail length meets every pattern, an all-null batch exists for every column (the
all-null shortcut, `VARKA-27.md` 0 item 8), and the every-fifth pattern walks a null across every
lane position. The derived columns of today's fixtures (`ntz` from `d` and `t`, the floating
picks) are drawn directly instead, from their own types' sources.

**What the cache cannot reach**, left to the suites that can: garbage data bits under a null slot
(Spark's writer clears them; the IR fuzzer and the conformance suites plant them) and a nonzero
buffer offset (every cached batch starts at row 0).

**Caching.** In both sessions, as `fzc`, with `IN_MEMORY_PARTITION_PRUNING` off on both for every
query of the arm (item 0.7), once per batch size: the fixture is built once per JVM.

### 2.2 The predicates

Draw `n` of the stream for `seed`:

- **Even draws: a composition's filter.** `VarkaSparkFuzz.draw(rows, seed, n).where` over
  `coverageRows`: the stratified coverage predicates (every predicate row once in every pass of the
  table's length), their negations, `AND` and `OR`, and on half the draws the nested trees of
  `VarkaCatalystGrammar`. The case's outputs and data are not used. A composition with no filter
  takes its first coverage predicate. As 352 lifts the boolean and narrow filter and adds the cast
  productions, they arrive here with no change.
- **Odd draws: a milestone shape.** Shape `(n / 2) mod S` of the concatenated `tlpShapes` of
  every type whose descriptor site is `Present` (352's 2.4: the NaN and `-0.0` pairs, the bare
  boolean predicates with null leaves, the narrow literals around the type's range, the decimals
  across 18 and 19 digits, `TIMESTAMP_NTZ` against `DATE`), `AND`ed or `OR`ed on every other one
  with an even draw's predicate from a stream of its own.
- **Optimizer and mode.** Draw `n` runs with `VarkaPartitionOracle.REWRITES` excluded on both
  sessions when `(n / 2) mod 2 == 0` (`NULL_SAFE_REWRITES` for a null-safe predicate), so `NOT
  (p)` and `(p) IS NULL` reach Varka as written, and at the default optimizer otherwise. ANSI is
  drawn per draw.

A predicate the optimizer folds to a constant reaches no Varka node; it is counted as unfused,
never as compared.

### 2.3 The queries, one draw

With `w` each of the three parts `p`, `NOT (p)` and `(p) IS NULL`:

- **S**, the ground truth, on `spark`: `SELECT COUNT_IF(p), COUNT_IF(NOT (p)), COUNT_IF((p) IS
  NULL), COUNT(*) FROM fzc`.
- **Fr**, the row boundary's bit walk, on `varkaSpark`: `SELECT COUNT(*) FROM fzc WHERE w`, three
  queries.
- **Fc**, the compaction's `countSet` and copy, on `varkaSpark`: `SELECT COUNT(z) FROM (SELECT
  pk + 1 AS z FROM fzc WHERE w)`, three queries.
- **Pv**, a value over the condition, on `varkaSpark`: three `COUNT_IF`s and `COUNT(*)` over
  `SELECT CASE WHEN (p) THEN 1 WHEN NOT (p) THEN 0 END AS v FROM fzc`.
- **Pb**, the boolean output, after 27, on `varkaSpark`: three `COUNT_IF`s and `COUNT(*)` over
  `SELECT (p) AS c FROM fzc`.

Pv's counts are `COUNT_IF(v = 1)`, `COUNT_IF(v = 0)` and `COUNT_IF(v IS NULL)`, Pb's `COUNT_IF(c)`,
`COUNT_IF(NOT c)` and `COUNT_IF(c IS NULL)`, each with `COUNT(*)`. Fc's `pk + 1` is the coverage
table's `i + 1` over an int column, a fused projection, so the plan is `VarkaColumnarToRowExec`
over the compacting `VarkaFilterExec` (item 0.4); a bare column there would be the forwarded
narrowing that the post stage folds back into the row-boundary node, and Fc would be a second Fr.
`pk` is never null, so `COUNT(z)` counts the rows; an int keeps Fc's projection on the int lane.
The coverage table holds no `BIGINT` addition, so `rid + 1` is not a form it shows fused. S is
one query because an aggregate's input is the row engine's on either session (item 0.3); Spark's
own filter counts are taken on one draw in eight (2.4, check 6). That is eight queries on
`varkaSpark` and one on `spark` before 27, nine after.

### 2.4 The checks

Each query's plan is read before its answer is compared:

1. **Both sides read the cache.** `sameCacheReads(..., mustRead = true)` between each Varka query
   and S (row 501's check).
2. **The Varka node is there and saw every batch.** Fr, Fc, Pv and Pb each carry their Varka node
   (for Fc, the compacting filter, which reads the cache scan, and the projection above it, which
   meets fewer or empty batches where the filter selects none), the node over the scan's
   `numInputBatches` equals the planned batch count (127, 128, 129 or the axis's), and whose
   kernel-served batches (`numVarkaBatches`) and declined or fallen-back ones add up to it. A
   query whose node is missing is counted unfused for that plumbing; one that saw fewer batches
   fails as "batches pruned or lost" (item 0.7). On the safe pool a declined batch is reported
   with its reason and counted; the run's info line gives the share of batches each plumbing
   served on a kernel.
3. **The rows.** S's `COUNT(*)` equals the planned row count.
4. **The filter's three parts.** For Fr and for Fc, the three counts sum to the row count.
5. **Each plumbing against Spark.** Fr, Fc, Pv and Pb each give S's three counts.
6. **Spark's own pruning**, one draw in eight: the three `COUNT(*) WHERE w` on `spark` with pruning
   on, against S. A difference is Spark's (SPARK-58482 where a NaN is involved), counted and named
   in the info line, never failed: the arm's own comparisons run with pruning off.
7. **Raises.** On the safe pool a raise is unexpected; if S or any Varka query raises, the draw
   fails as a harness finding naming the condition, and none of `disagreement`'s reruns is used,
   since its `coalesce(1)` would change the batches under test.

Over the run, so that the arm cannot pass while comparing nothing (row 504): at least one full
pass of the coverage table's predicate rows was drawn; Fr fused on at least half the draws, and
Fc, Pv and Pb each on at least half of those Fr fused on; and at least a quarter of the draws split
the rows into at least two nonempty parts. The first nightly sets these floors from its counts if
they are wrong, recorded in section 10. One draw in eight also counts, on S, the batches each part
selects all, none or some of, so the info line says how often the copy of item 0.4 ran.

### 2.5 The verdict and the drill-down

A failed draw's counts decide where it points:

| what fails | verdict |
|---|---|
| check 1, 2, 3 or 7 | the harness or the fixture: the comparison did not test what it says |
| Fr against S, Fc agrees | the row boundary's bit walk |
| Fc against S, Fr agrees | the compaction: `countSet`, the forwarding, or the copy |
| Fr and Fc against S, alike, the projections agree | the filter's mask: its root or `filterMask` |
| Pv or Pb against S, the filters agree | that projection's plumbing: the output's write |
| every Varka count alike, all against S | the condition all of them compile (`compileCond`) |
| anything else | more than one; every count is reported |

The filters' row reads two plumbings sharing one mask: the condition under a filter root and the
same condition under a value or boolean root are different emitted roots, so it points at the
filter root's emission or the evaluator, not at the condition's lowering. A Varka-only failure of
check 4 is reported with the same table. Then the drill-down, run only on a failure: the keys `pk,
pb, pj` of the rows the disagreeing plumbing selects in the part that differs (for a filter) or of
every row with its `c` or `v` (for a projection), collected from that plumbing's own plan and from
the same query on `spark` with pruning off, and the two sets diffed on the driver. For Fr the keys
alone keep the row-boundary node; for Fc they are collected beside `pk + 1 AS z`, so the plan
stays the compacting one rather than folding into the row boundary. The keys are bare columns,
forwarded rather than computed by a kernel, and a quarter of a million rows of four ints is a few
megabytes. The report names the first ten differing rows by partition,
batch, the batch's length, the position and the lane at each lane count, so a count's disagreement
is reported as rows, the strength item 0.14 says counts give up.

**What a failure produces.** The test fails with the verdict, the counts of every plumbing, the
first differing rows and a reproducer file under
`sql/core/target/varka-countarm/<seed>-<n>.txt`: the seed, the draw, the batch size, the vector
width, ANSI, the exclusions, the predicate and those rows, and the replay line
`-Dvarka.countarm.seed=<seed> -Dvarka.countarm.only=<n> -Dvarka.countarm.batchRows=<B>`, which
rebuilds the fixture from the seed. The file has a format of its own, a header like
`VarkaSparkFuzz.render`'s with no `VALUES` fixture, and a parse-back
test. A reviewed one is kept under `sql/varka/fuzz/count/`, replayed by the suite on each run.

### 2.6 Where the code goes

- **`VarkaCountArm.java`** (sql/core test, new): the geometry (rows, batches, the planned counts),
  the row generator over the descriptor's sources, the count records and the verdict as a sealed
  interface with an exhaustive switch over the table of 2.5, the reproducer's render and parse.
- **`VarkaCountArmSuite.scala`** (sql/core test, new): Scala because it extends
  `VarkaSparkDifferential` for the sessions, `withBothConf` and `sameCacheReads`. It holds the
  arm's test (`"the count arm agrees across plumbings (seed $seed, $n draws, B rows a batch)"`),
  the planted tests (3.1), and the reproducer replays. Its title carries the seed and the count,
  and it joins 499(c)'s seeded set.
- **The fault injectors**: test-only fields of `VarkaEmitOptions` beside `misdescribeRollback`,
  zero in production, outside `VarkaEmitOption.TABLE` so the matrix never draws one, read at the
  plant site (3.1).

Properties: `-Dvarka.countarm.seed` (the date's in the nightly), `-Dvarka.countarm.minutes` (the
draw loop stops at the first draw past it; default none), `-Dvarka.countarm.draws` (default 24),
`-Dvarka.countarm.only`, `-Dvarka.countarm.scale=full|small` (default small) and
`-Dvarka.countarm.batchRows`.

### 2.7 The nightly

A step of the scheduled sql job in `varka-fuzz.yml`, after 352's Spark-level step: two JVMs side
by side on the runner's four cores, each `local[2]`, one at `B = 4096` and one at `B = 4093`, both
at the full size and the date's seed, each under the step's cap in minutes. The 128-bit width is
taken by the date's parity in the `B = 4093` JVM (`-XX:MaxVectorSize=16`), so each pair of a batch
size and a width runs every other night. The job records the CPU and the vector width first (row
396's action), keeps the logs and the reproducers as the run's artifact, and is in
`report-failure`'s `needs`, so a failed night opens an issue naming the step. The step adds no job,
so the run's job count and the fork's budget are unchanged; it adds the cap to the job's wall time
(section 8, point 2).

## 3. Tests and proofs

### 3.1 The planted faults

Row 504's rule: each check fails on a fault planted for it. Each planted test runs the arm at its
own seed (`-Dvarka.countarm.plantedSeed`, the precedent of `VarkaSparkFuzzSuite.scala:80-81`) at
the small size with the fault on, and asserts that a draw fails within its first 24, with the
verdict and the first differing row's place named.

1. **The row boundary drops the last row of a batch.** `plantSelection = 1`: the row-boundary node
   skips index `len - 1`. Fr counts one row fewer in each batch whose last row the part selects, so
   check 5 and check 4 fail on Fr; Fc and the projections agree. Verdict: the bit walk. Place: the
   last position of a batch, at both batch sizes.
2. **`countSet` drops the partial last word.** `plantSelection = 2`: the count stops at the last
   whole word. The compaction keeps too few rows of every batch whose length is not a multiple of
   64 and that selects a row past its last whole word; Fr agrees. Verdict: the compaction. Place: a
   short batch's tail. It is the fault that proves item 0.4's split: the bit walk never reads the
   count.
3. **A value misreads a null at a lane boundary.** `plantValidity = 1`: the masked body writes a
   value root's validity bit at lane `L - 1` of each group as valid. Pv counts a null `v` as 0 or
   1 there, so its null count falls; the filters agree. Verdict: the value's plumbing. Place: a
   position `L - 1 mod L`, which the every-fifth null pattern puts at every lane.
4. **A boolean output writes its masked validity as `kt` alone** (after 27; row 27's own planted
   fault, `VARKA-27.md` 11, made a fault injector): known-false rows read as null, so Pb's false
   count falls to the rows of dense batches. Verdict: the boolean output's plumbing.
5. **A boolean output's data bitmap is not zeroed** (after 27; row 27's second planted fault). It
   shows only where an output's buffer outlives a batch; if Arrow's fresh, zeroed vector per batch
   hides it from SQL (`VARKA-27.md` 0 item 5), the test is replaced by an assertion that the arm
   cannot see it, with the reason, rather than kept passing.
6. **The shared condition is wrong.** `plantCompare = 1`: `<` lowers as `<=` in `compileCond`.
   Every Varka count moves alike. Verdict: the condition, not plumbing - the row of the table the
   other faults do not reach.
7. **The guards.** Pruning back on with a NaN predicate: check 2 fails, "batches pruned". The
   expected side's fixture uncached: check 1 fails. A draw loop that ends before one pass of the
   coverage table: the run's floor fails.

Each fault is zero in production, rides the shape key, and leaves `emitted_bytes.json` unchanged
at zero (only the `option_arms` digests may move, as with every option).

### 3.2 The rest

1. **The geometry**: `VarkaCountArm`'s planned batch lengths for both batch sizes, the small size
   and the axis's three equal the admission file's table, and the cached batch lengths read back
   from `InMemoryRelation`'s buffers equal the plan (so the fixture is what 2.1 says).
2. **The fixture's completeness**: every coverage-table column is a fixture column or owed; every
   type with a `Present` Spark site has a column; each null pattern occurs in each column.
3. **The plans**: one predicate of each lane through each plumbing has the expected Varka node
   (item 0.3's premise, read from the code here, asserted there).
4. **The reproducer** parses back to what was rendered, and a kept one replays.
5. **The option matrix**: the suite at the small size under every configuration, a by-design
   disagreement under one a `draws` line (499(c)).

## 4. The baseline, and predictions

No benchmark. Predictions, scored in section 10:

1. **The arm passes on master** at both batch sizes on the first nightly, as the differential and
   the partition oracle do over the same predicates at twelve rows; a finding, if one comes, is a
   tail or a batch boundary of a plumbing the small fixtures meet least: the compaction (item
   0.4) or, after 27, the boolean output's per-word writer at a short tail.
2. **The share fused**: Fr and Fc fuse on every draw whose predicate fuses as a filter; Pv on most
   of them, less where a condition fuses as a filter and not under a value.
3. **No pruning difference with pruning off**, and a nonzero count of Spark's own with it on over
   the floating shapes (SPARK-58482), on the first night the floating columns are drawn.
4. **Time.** Measured, not predicted: the pull request's size on 369a's pull request against the
   calibration table, and the nightly's draws per minute on its first run, which then set the
   floors of 2.4 and the cap of 2.7.

## 5. Order, files and size

1. **369a, after 352a's first step (the descriptor)**: `VarkaCountArm`, the suite with S, Fr, Fc
   and Pv, the checks and floors of 2.4, the verdict and the drill-down, the reproducer, planted
   faults 1, 2, 3, 6 and 7, the geometry and completeness tests, the small size in the Build and
   the matrix; the types are what the descriptor has `Present` that day.
2. **369b, after row 27's step 4 merges**: Pb, planted faults 4 and 5, the verdict table's
   boolean-output row live; then the arm compares kernel against kernel.
3. **369c, once the scheduled sql job exists (368b or 537, whichever lands first)**: the nightly
   step (2.7), the step in `dev/varka_nightly.sh`, the floors set from its first run.

Then each type row's first pull request turns its descriptor's Spark site `Present`, and the arm
draws its columns and shapes with no change of its own.

| file | what |
|---|---|
| `VarkaCountArm.java` (new, sql/core test) | geometry, generator, counts, verdict, reproducer |
| `VarkaCountArmSuite.scala` (new, sql/core test) | the arm, the planted tests, the replays |
| `varka/VarkaEmitOptions.java` | `plantSelection`, `plantValidity`, `plantCompare` |
| `VarkaFilterExec.scala`, `VarkaFilterEvaluator.java`, `VarkaSelectionBitmap.java` | plants |
| `varka/VarkaBodyEmitter.java`, `VarkaConditionCompiler.java` | the validity and compare plants |
| `VarkaFuzzTypes.java` (352's) | the typed value drawer, if section 8 point 1 says so |
| `.github/workflows/varka-fuzz.yml`, `dev/varka_nightly.sh` | the nightly step |
| `sql/varka/fuzz/count/` (new) | reviewed reproducers |
| `sql/varka/matrix/skips.tsv`, `VarkaMatrixRuns.java` | the seeded set, `draws` lines |

**Size: medium, as the row says.** 369a about three days, 369b about one, 369c half a day.
Test time added to the Build: the small size, 24 draws of nine queries over 129 batches, priced
on 369a's pull request.

## 6. What it unlocks in the census

Nothing directly: the census counts what fuses. It is a "how it is known" of the milestone's
done-when, beside the differential and 352's fuzzers: every predicate the fuzzers draw is checked
over every tail and batch boundary, through every plumbing.

## 7. Out of this row, and declines that stay

Out: hostile values, per-batch declines and raises at scale (section 8, point 3); a columnar
consumer of the boolean output (row 27's differential runs it, through a cached view); the
aggregate operators themselves (milestone plan section 9); new draws or types (352). Stays: a
garbage bit under a null and a nonzero offset, which the cache cannot produce (2.1).

## 8. Questions for the owner

*Settled, the lead, 30 October 2026, as each point recommends:* 1 (a), 352's drawer becomes value-
first with the text rendered from the value; 2 (a), a step of the scheduled sql job of 368b and
537 with that job's timeout raised; 3 (a), the LEGACY-only hostile fixture after 369c; 4 (b),
follow what 499(a) settles for session suites, annotating the suite in 369a if 499(a) has not
landed by then; 5 (a), the value form kept beside the boolean output as a third path.

None. Open points for the lead, each with the options and the evidence:

1. **Who bridges 352's descriptor to a typed fixture.** The arm needs a value per cell (item
   0.10). (a) 352a's first step makes `SparkColumns`' drawer value-first: it draws a typed value,
   and the `VALUES` text is that value rendered, so the twelve-row fuzzer and the count arm draw
   from one source. (b) The arm evaluates the drawer's text per cell, which at 264160 rows and
   about thirty columns is millions of literals. (c) The arm keeps a generator per type of its
   own, beside the descriptor, which the brief rules out. *Recommended: (a)*: one source, and a
   value can always be rendered where text cannot always be parsed back (a NaN payload).
2. **Where the nightly step runs.** (a) A step of the scheduled sql job of 368b and 537, the cap
   about twenty minutes, raising that job's timeout from the 60 minutes 368 plans
   (`VARKA-368.md` 4). (b) A job of its own, which pays a second sql test compile. *Recommended:
   (a)*: one compile, no new job against the fork's 40.
3. **Hostile values at scale.** (a) A second fixture, run under LEGACY only, with hostile cells in
   one batch of every third partition, so a domain guard declines whole batches between served
   ones and the row path's batches meet the kernel's in one task. (b) Leave hostile values to the
   twelve-row fuzzer's full pool. *Recommended: (a) as a later pull request after 369c*: the
   handover between a served and a declined batch in one task is a plumbing no check reaches at
   scale, but it doubles the cache and the arm's first version should be trusted first.
4. **`ownJvm` for a session suite.** The fixture holds every lane, so a draw such as `i < l`, or
   Fc's int projection over a long-lane filter, makes kernels of two lanes, the shape of 499(a). No
   sql/core Scala suite is own-JVM today, `VarkaSparkFuzzSuite` included (item 0.13). (a) Annotate
   the suite `@VarkaSuite(ownJvm = true)` from 369a. (b) Follow whatever 499(a) settles for
   session suites. *Recommended: (b), with (a) if 499(a) has not landed by 369a*.
5. **The value form after 27.** (a) Keep Pv beside Pb, a third plumbing (a condition under a value
   is `IfElse` and `CondValue`, a boolean output is `BoolOutput`). (b) Drop it when Pb lands.
   *Recommended: (a)*: one query a draw, and the two write different roots.

## 9. Done when

Row 369's text holds, with the corrections above: the arm runs nightly over the full fixture at
both batch sizes with the milestone's shapes from 352's descriptor; Fr, Fc, Pv and, after 27, Pb
are each held to Spark with Varka off and the filters' three parts to the row count, with every
batch seen by a kernel or counted declined; every planted fault of 3.1 fails the arm with its
verdict and the place of its first differing row; the run's floors hold; and the first
disagreement, if any, is a fixed finding with its reproducer kept, or a named row.

## 10. Outcome
