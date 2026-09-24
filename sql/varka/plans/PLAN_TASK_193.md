# Task 193: how often do real queries meet the cliff?

## 1. Where this came from

Task 171's ladder shows vanilla Spark's time per row stepping up almost five
times where a projection's generated consume method passes HotSpot's
8000-byte `HugeMethodLimit`: HotSpot never compiles the method, and Spark logs
nothing. Task 192 then showed that the cliff is silent under the defaults and
that its remedies are settings a user has to know about. What the record does
not have is any evidence that queries people actually run reach it. The ladder
is synthetic, and task 87 found that Varka's own fuzzer cannot reach it either
(`PLAN_TASK_87.md` 2.6.1): its grammar draws kernels of one to three roots, and
the cliff needs many heavy outputs in one method.

The fuzzer's four 64KB failures belong to a different limit and a different
engine. Between 8 and 23 September 2026 the fuzz campaigns found four trees for
which Varka's own emitter built an `epilogueMasked` past the JVM's hard
65535-byte method cap (67244, 66102, 73013 and 79645 bytes), all nested
`make_date` trees (`sql/varka/skills/testing-and-debugging.md`). Task 87's
per-group epilogue and byte budget fixed that: replayed on master `ea3de65c2c3`
on 24 September, the three coordinates that still draw their original trees
(`seed=20260922006 only=35105`, `seed=20260922008 only=94624`, and
`seed=20260923021 only=88962` under `-XX:MaxVectorSize=32`) emit within the
budget and match the reference evaluator. The first one,
`seed=2026092800 only=73411`, now draws a different tree (`PLAN_TASK_87.md`
2.1). None of the four says anything about vanilla Spark.

The owner asked for this plan on 24 September 2026, after the review of task
172 found that Spark's own tests already bear on the question.

## 2. The admission check, done

**Spark's TPC-DS suite asserts that the cliff is not crossed, and switches a
default off to keep the assertion true.** `TPCDSQuerySuite` compiles every
whole-stage codegen stage of every TPC-DS query it runs (97 of v1.4's 103,
skipping six that v2.7 repeats; 32 of v2.7; 21 modified ones) through
`BenchmarkQueryTest.checkGeneratedCode`, and asserts that each stage's largest
method is at most
`CodeGenerator.DEFAULT_JVM_HUGE_METHOD_LIMIT`, 8000 bytes. Two things in it
are the point of this task:

* `excludeListForMethodCodeSizeCheck = Set("modified-q3")`: one query is known
  to be past the limit, under SPARK-29128 ("Split predicate code in OR
  expressions"), and is excluded rather than fixed.
* `sparkConf` sets `spark.sql.readSideCharPadding=false`, with the comment
  "Disable read-side char padding so that the generated code is less than
  8000". The setting defaults to true (Spark 3.4, SPARK-40697, 2022) and pads
  every `CHAR` column the scan reads; TPC-DS's schema declares 97 of them
  (`TPCDSSchema.scala`). So under Spark's defaults at least one TPC-DS stage
  generates a method past 8000 bytes, which HotSpot runs interpreted without
  Spark saying so. Which stages, how many and by how much, nobody has written
  down.

`TPCHQuerySuite` makes the same assertion without changing any setting, and the
test TPC-H schema has no `CHAR` columns.

So the machinery exists, and the task is to record rather than assert, under
the settings a user actually runs.

## 3. The design

### 3.1 The census

`VarkaTpcCodegenCensusSuite` in `sql/core`'s tests, on `BenchmarkQueryTest`
with `TPCDSBase` or `TPCHBase`, walks every whole-stage stage of every query,
subqueries included, exactly as `checkGeneratedCode` does, and records instead
of asserting. For each stage:

| column | what |
|---|---|
| query set | TPC-DS v1.4 (all 103, the six the suite skips included), v2.7, modified; TPC-H |
| query | its file name |
| stage | the codegen stage id |
| operators | the stage's operators, top down, by node name |
| bytes | the largest generated method, from Spark's own compile |
| band | within 8000; past 8000 and within 65535 (the silent cliff); past 65535 (Spark logs and runs the stage without whole-stage codegen) |

For every stage past 8000 bytes it also writes the generated source to a
scratch directory, so task 172 can read which operator's code made the method
big and whether it is work Varka's lanes cover.

**Four configurations**, one subclass each, as `TPCDSQueryWithStatsSuite`
subclasses `TPCDSQuerySuite`:

* **defaults**: Spark's defaults, `readSideCharPadding` on;
* **defaults with statistics**: the same with `injectStats`, which gives the
  planner table sizes, so its join choices look like a real warehouse's;
* **the test suite's setting**: `readSideCharPadding` off, which should
  reproduce what `TPCDSQuerySuite` asserts;
* **the test suite's setting with statistics**.

**It runs on demand, not in CI.** The suite is skipped unless
`VARKA_TPC_CENSUS` names the output file. As a CI assertion it would fail
whenever upstream changes codegen, which is not something this repository
decides. The committed file, `sql/varka/census/tpc_codegen.txt`, carries its
commit and configuration in a header, as a benchmark's provenance does, and is
regenerated when the question is asked again.

Plans are compiled over the empty tables `TPCBase` creates; nothing runs
at scale. The four configurations run as four JVMs in parallel, through the
exported test classpath (`sql/varka/skills/testing-and-debugging.md`), so the
census needs no quiet machine.

### 3.2 What it answers

* How many stages of standard benchmark queries cross 8000 bytes under
  Spark's defaults, and how many only under settings a user has changed. The
  post can then say how often the cliff occurs, with the number rather than
  the ladder alone.
* For each crossing stage, what is in it, so task 172 can tell a stage Varka
  could take over (a projection or filter over dates, integers, longs or TIME)
  from one it cannot (strings, joins, aggregates).

### 3.3 What is deliberately not in it

* Running the queries or timing them: a crossing stage's cost is task 172's
  measurement, on the one query it picks.
* Varka's arm: whether Varka's rule converts a TPC-DS stage over Parquet is a
  separate question, and one for task 172 on its chosen query.
* Spark's other codegen cliffs (`maxFields`, the constant pool): task 188's
  census, which starts from Spark's source rather than from queries. The
  `operators` column may point it at shapes to look at.

## 4. Files

| file | what |
|---|---|
| `sql/core/src/test/scala/.../VarkaTpcCodegenCensusSuite.scala` | the census and its four configurations |
| `sql/varka/census/tpc_codegen.txt` | the committed table, with a per-configuration summary |
| `PLAN_MILESTONE_6.md` | row 193 |

## 5. Tests, and what each is for

* **The census reproduces Spark's own assertion.** Under the test suite's
  setting, every stage but `modified-q3`'s must be within 8000 bytes, because
  `TPCDSQuerySuite` asserts exactly that and passes. If the census disagrees,
  it is measuring something else, and its other rows are not read.
* **Every query compiles.** A query that fails to plan or compile is a row
  with the error rather than a silent gap, so the count of stages is the count
  of stages.

## 6. Predictions, registered before the run

1. **Under the defaults, at least one TPC-DS stage is past 8000 bytes,** as the
   suite's comment implies, and fewer than twenty. The crossing stages are
   scans with `CHAR` padding in their projection.
2. **With padding off, only `modified-q3` is past 8000.** This is the suite's
   own assertion, restated as a check (5).
3. **No stage in any configuration is past 65535.** A stage past it would fail
   Janino's compile, which the suite would have reported.
4. **No TPC-H stage is past 8000** in any configuration: no `CHAR` columns,
   and the suite asserts it.
5. **Statistics add crossings, never remove them.** Broadcast joins put more
   operators into one stage, so the with-statistics configurations have at
   least as many stages past 8000 as those without.
6. **The crossings are string work, not Varka's lanes.** Every stage past 8000
   under the defaults is dominated by `CHAR` padding or by joins, so none is a
   stage Varka could take over today. If that holds, task 172's realistic query
   comes from its wide-table search (`PLAN_MILESTONE_6.md` 2.6), and the post
   says two separate things: the cliff is real in TPC-DS under the defaults,
   and the shapes Varka covers reach it through wide projections.

## 7. Risks

1. **Empty tables change plans.** Without statistics the planner picks
   sort-merge joins where a warehouse would broadcast. The with-statistics
   configurations are there for this, and the file says which configuration
   each row comes from.
2. **Upstream moves the numbers.** The fork tracks Spark master, so a codegen
   change upstream moves bytes. The file names its commit; the numbers are a
   census of that commit, not of every Spark release. If the post quotes it,
   it names the Spark version the fork was at.
3. **"Past 8000" is not "slow".** A crossing stage runs its largest method
   interpreted, but whether that dominates the query's time depends on how
   much data flows through it. The census says where the cliff is; task 172
   measures what it costs on one query.

## 8. Sequencing

1. This plan and row 193.
2. The suite, its check against Spark's assertion (5), and the four runs, in
   a window when the machine can be busy.
3. The file, the predictions scored in 9, and the list of crossing stages
   handed to task 172.

## 9. Outcome

### 9.1 The census, 24 September 2026

`VarkaTpcCodegenCensusSuite` and `dev/varka_tpc_census.sh`, run at `a821cc2e8d1`
on JDK 25; one file per configuration under `sql/varka/census/`. The whole run
takes about a minute.

**The answer: standard benchmark queries do not reach the cliff.** Across
all 178 queries (103 of TPC-DS v1.4, 32 of v2.7, 21 modified, 22 of TPC-H) and
all six configurations, exactly one stage is past 8000 bytes, the same one in
every configuration: `modified-q3`, the query Spark's suite already excludes.
The largest stage of any unmodified query is 4962 bytes (TPC-DS q66, a hash
aggregate); TPC-H's largest is 1340 (q19). No stage is past 65535, and every
one compiles.

**The one crossing is a filter over an integer column.** `modified-q3`
restricts `store_sales` by about fifty
`ss_sold_date_sk between a and b or ...` ranges, the "partition key filters"
its comment names, which is the shape a BI tool writes for a set of date
ranges. Its stage is 12214 bytes without statistics, where the filter shares a
stage with two broadcast joins and an aggregate, and 12167 bytes with them,
where the stage is `Project < Filter < ColumnarToRow`: a filter over a scan and
nothing else. The OR chain is inlined in `processNext`, so the scan loop
itself runs uncompiled. That is a realistic shape, and it is in Varka's
territory: an integer comparison chain in a filter. Task 172 takes it as its
first candidate.

**Char padding does not cause crossings, whatever the suite's comment says.**
Padding on changes the largest method of 67 TPC-DS queries, by at most 491
bytes (q30, from 902 to 1393), and puts no stage past 8000. The comment "so
that the generated code is less than 8000" cannot be reproduced on today's
tree.

**Spark's own check has been checking nothing.** With adaptive execution on,
Spark's default since 3.2, `executedPlan` is an `AdaptiveSparkPlanExec` leaf,
and its whole-stage stages exist only once the query runs. The walk
`BenchmarkQueryTest.checkGeneratedCode` does, `plan foreach` plus subqueries,
therefore finds no stage at all in a plan that is only compiled: the census
records that the same walk with adaptive execution on finds 0 stages in every
one of its 178 queries. So `TPCDSQuerySuite`, `TPCDSQueryWithStatsSuite`,
`TPCDSQueryANSISuite` and `TPCHQuerySuite` pass their "compiles, and within
8000 bytes" assertion without compiling anything. `modified-q3`'s exclusion and
the padding setting are both, today, guarding an assertion that never runs.
That is an upstream test bug, and whether to report it is the owner's call; the
fix would be to walk the stages with adaptive execution off, as the census
does, or through `AdaptiveSparkPlanHelper` after running.

### 9.2 The predictions, scored

1. **Failed.** Under the defaults no TPC-DS stage but `modified-q3`'s is past
   8000; char padding is not what the suite's comment implies.
2. **Held**, but the suite this restates does not check it (9.1).
3. **Held.** No stage is past 65535.
4. **Held.** TPC-H's largest stage is 1340 bytes.
5. **Held, trivially.** Statistics change the stages (1058 to 1586 in v1.4)
   but not the crossings: one with and one without.
6. **Failed, in Varka's favour.** The one crossing is not string work or a
   join but an integer filter over a scan.

### 9.3 Departures from the design

* **Adaptive execution is off in every configuration**, for the reason in 9.1;
  the plan did not foresee it. A user's query runs with it on, where the
  stages form at run time around the same exchanges, so the census reads as
  the stages a query compiles, not as the exact plan AQE would settle on.
* **One file per configuration**, not one file: each suite writes its own.
* **One sbt run, not four parallel JVMs**: compiling every plan takes about a
  minute, so there was nothing to parallelise.

### 9.4 Correction, the same day: the joins were stubs

A review of the upstream patch for the check in 9.1 (SPARK-59764, apache/spark
#59017) pointed out that the census inherits a second blind spot from the
same machinery. A broadcast hash join generates its code from the broadcast's
value, which it computes while generating code, and over the empty tables the
TPC bases create that value is `EmptyHashedRelation`. For it, `HashJoin` emits
a one-line comment in place of an inner or semi join and everything after it
in the stage. So 9.1 measured every stage with a broadcast join short.

The census now generates each stage's code from a copy in which every
broadcast exchange reads one row of non-null default values, counts the
exchanges it replaced in a new `broadcasts` column, and fails if any stage's
code still carries the empty-relation comment. Rerun at the commit its files
name:

* **The conclusion holds.** Still one stage past 8000 bytes in every
  configuration, `modified-q3`'s.
* **Many stages were short.** Of the default configuration's stages, 374 grew
  once their joins were generated in full, the most by 3567 bytes: TPC-DS q13's
  stage 6, from 1435 to 5002 bytes, which is now the largest stage of any
  unmodified query in that configuration. TPC-H's largest is now q19's stage 2,
  2533 bytes. The files are the corrected ones; the figures in 9.1 that differ
  are superseded by them.
* **One join shape is still approximate.** A one-row build side makes every
  join key unique, so a join generates its unique-key form. For a join whose
  real build side repeats keys, the loop over matches is missing; it is a
  small addition to the stage, not an order of magnitude.

The upstream suites share the blind spot, which #59017 names as a known
limitation for a follow-up.

**The figures are exact to within a few bytes, not to the byte.** Two runs at
the same commit gave the same bytes for every TPC-DS stage, but TPC-H q22's
stage 3 read 225 bytes in one and 233 in the other, and the constant pools of
about 460 stages differed by an entry or two. Generated code depends on state
that differs between runs, such as the order in which names are allocated. No
conclusion here rests on a margin that small.

The post can now say how often the cliff occurs in the standard benchmarks -
once in 178 queries, and in a shape a BI tool writes - rather than implying it
is everywhere.
