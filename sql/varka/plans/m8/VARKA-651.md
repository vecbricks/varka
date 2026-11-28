# VARKA-651: count a fused filter's rows from its selection bitmap

*Planned on 28 November 2026. This plan stands alone: a builder needs it and the repository's
`AGENTS.md` files, nothing else. Not started: no branch holds any of it (section 3, fact F1). The
code facts were checked on master `6dafb8bd2f6`. Every section is new on this day: the row had
no plan file before.*

*Fix round 2, the same day, after review 1 (`reviews/VARKA-count-census-review-1.md` in the ops
home's `m8-row-plans`). Changed: section 1 (what the row's text leaves out), section 2 (terms
and names), section 3 (F1, F7, the new F10 to F12), 4.2 to 4.8, section 5 (a new part 1b, and
the tests of all three parts), sections 6 to 9 and section 12. Unchanged: 4.4 but for one
method, section 10. The owner's decision of 28 November 2026, that the default flips in this
milestone, changed 4.1 and added parts 3a and 3b.*

## 1. What this row is

**The row's text** (`sql/varka/plans/m8/PLAN.md`, section 3, row 651): fuse an ungrouped
`count(*)` directly over a fused filter, the partial phase only. Each batch adds the popcount of
its selection bitmap to one partial count, and Spark's final aggregate stays. Out of scope:
`count(col)`, `sum`, `avg`, grouping, `DISTINCT` and a count over a bare scan, which are item 5
of `sql/varka/plans/m10/SCOPE.md` ("The aggregate operator wiring") and stay there.

**What the row's text leaves out, and this plan adds.** "The partial phase only" describes a
plan with a shuffle between the two aggregates. Where the counted filter has one partition
Spark plans no shuffle, and its rule `CombineAdjacentAggregation`, on by default, merges the
two aggregates into one aggregate in mode `Complete` (section 3, F10 and F11). That is the plan
of a small table and of every `local[1]` benchmark, the cases a user tries first. So the node
has two modes: `Partial`, in the partial aggregate's place, and `Complete`, in the merged
aggregate's place under Spark's own result projection (4.2, 4.3, 4.5). The tests and the
benchmark leave Spark's rule at its default.

**Why.** Today a `count(*)` over a fused filter runs the filter's kernel and then turns every
selected row into an `UnsafeRow`, only for Spark's aggregate to count it. The committed laptop
file `sql/core/benchmarks/VarkaFilterBenchmark-jdk25-results.txt` reads that shape at 1.8X of the
row engine with 15% of the rows selected and at 0.8X with 85% selected: the more rows pass, the
more rows cross. The filter's kernel already knows the answer for the batch, the number of set
bits of the bitmap it wrote, so no row needs to cross.

**What it gives the rows after it.** Row 652 reruns `VarkaCountBenchmark` and commits the
figure of the milestone's post (row 355). Item 5 of `m10/SCOPE.md` can start from this node.

**What must be merged first.** Row 650, part 650a (`VarkaCountBenchmark` and its baseline):
the row's own text says the baseline is taken "before row 651 changes any code". Parts 1 and 1b
below change no measured path while the switch is off, so they may be built beside row 650;
part 2 edits the benchmark class and so needs 650a merged.

**Which item of the milestone's done-when list it moves** (`m8/PLAN.md` 1.1): none of the
census items; it is in the milestone "by the owner's word, since it moves no census cell" (the
row's text) and gates the close under items 7 and 8, the post, as section 12.5 of that plan
lists it: "Items 7 and 8: 355, then 650, 651 and 652, and 354."

**The row's done-when**, read against this plan: "the pi query and both `COUNT(*)` sections run
the fused count" is the benchmark's third arm (part 2, step 6); "the answers equal Spark's under
LEGACY and ANSI with and without nulls" is `VarkaFusedCountSuite` (parts 1b and 2); "a declined
filter leaves the plan as today" is the shapes tests of parts 1 and 1b and their session twins.
The owner's decision of 28 November 2026 adds: the default flips in this row (parts 3a, 3b).

## 2. What an agent must know

**The terms.**

- A *Varka node* is one of the physical plan nodes `VarkaColumnarRule` plants:
  `VarkaProjectExec`, `VarkaColumnarToRowExec`, `VarkaFilterExec` and
  `VarkaFilterColumnarToRowExec` (all in `sql/core/src/main/scala/org/apache/spark/sql/execution/`).
- The *mask kernel* is the fused loop of a filter. Its output is a *selection bitmap*: one bit a
  row, set where the predicate is known true, so a `NULL` and a false both leave the bit clear.
- The *row filter node* is `VarkaFilterColumnarToRowExec`: it runs the mask kernel over each
  Arrow batch of its columnar child and emits the selected rows as `UnsafeRow`s.
- The *partial aggregate* is the first of the two aggregate nodes Spark plans for
  `SELECT count(*)`: it emits one row per partition holding that partition's count. The *final
  aggregate* above it (above a shuffle when there are several partitions) adds them.
- The *merged aggregate* is what Spark's rule `CombineAdjacentAggregation` makes of the two
  where no shuffle separates them: one aggregate node whose function is in mode `Complete`. It
  reads the rows and emits the query's result row itself.
- An aggregate's *result attribute* is the attribute its function's value has inside the node
  (`count(1)#12L`); its *result expressions* compute the node's output from it (`count(1)#12L
  AS c#5L`, or `4 * count(1)#12L / 1024 AS pi#6`). A partial aggregate has no such step: its
  output is the function's buffer attribute.
- The *per-row fallback* is what a Varka node does with a batch its kernel does not serve: it
  evaluates Spark's own predicate row by row. Every fallback is counted under its cause.
- The *warm-up*: with `spark.sql.codegen.varka.warmup.enabled` on, a new kernel class serves no
  batch until HotSpot has compiled it; meanwhile its batches take the per-row path. That is
  counted as a warm-up batch, not as a fallback.

**The idea.** `VarkaFilterEvaluator.filterMask(batch)` returns a `VarkaSelection(mask, count)`.
Its `count` is `VarkaSelectionBitmap.countSet(mask, len)`: the set bits among the first `len`
rows, the last word masked. So the count of a batch exists already. The row adds a node that
sums it per partition and emits one row holding the sum, and two rule arms. One puts the node
in the partial aggregate's place, its row being the partial row. The other puts it in the
merged aggregate's place, its row being the count as the result attribute, with the merged
aggregate's result expressions in a `ProjectExec` above it.

**The names this row reads.**

| name | where | what it is |
| :--- | :--- | :--- |
| `VarkaColumnarRule.postColumnarTransitions` | `VarkaColumnarRule.scala` | the rule's second stage, after Spark has inserted the to-row transitions; it builds the row filter node |
| `VarkaFilterColumnarToRowExec(condition, child, narrowing, wholeCondition, sessionEmit)` | `VarkaFilterExec.scala` | the row filter node |
| `VarkaFilterExecBase.fallbackCondition`, `fallbackEliminatesSubexpressions`, `fusionLines` | `VarkaFilterExec.scala` | the per-row predicate in Spark's order, whether it eliminates common subexpressions, the `EXPLAIN` lines |
| `VarkaFilterEvaluator.filterMask`, `inputRows`, `serveBatch` | `VarkaFilterEvaluator.java`, `VarkaEvaluatorBase.java` | the mask kernel over a batch; a batch's rows; the per-batch dispatch with the cause accounting |
| `VarkaSelection(mask, count)` | `VarkaSelection.java` | one batch's bitmap and its selected-row count |
| `VarkaExecMetrics.nodeMetrics(sc, eagerFallback)`, `fromNode` | `VarkaExecMetrics.java` | the metric set every Varka node registers |
| `VarkaFallbackPredicate.create(bound, eliminates)` | `VarkaFallbackPredicate.java` | the per-row predicate |
| `BaseAggregateExec` | `sql/core/.../execution/aggregate/BaseAggregateExec.scala` | the trait of Spark's three aggregate nodes |
| `PartitioningPreservingUnaryExecNode` | `sql/core/.../execution/AliasAwareOutputExpression.scala` | how an aggregate derives its output partitioning |

**The names this row adds.**

| name | part | what it is |
| :--- | :--- | :--- |
| `SQLConf.VARKA_FUSED_COUNT_ENABLED`, key `spark.sql.codegen.varka.fusedCount.enabled`, accessor `varkaFusedCountEnabled` | 1 | the switch, internal, default `false` until part 3a makes it `true` |
| `VarkaFilterCountExec` | 1 | the new node, in the new file `VarkaFilterCountExec.scala`; its field `mode` is `Partial` or `Complete` |
| `VarkaFilterCountEvaluatorFactory` | 1 | its per-partition evaluator, same file |
| `VarkaColumnarRule.isPartialCountStar`, `countedFilter` | 1 | the two halves of the match |
| `VarkaColumnarRule.isCompleteCountStar` | 1b | the match on the merged aggregate |
| metric `numCountedRows` | 1 | the rows the node counted |
| `VarkaFilterCountExecSuite` | 1 | the node over hand-built batches and the rule over hand-built plans |
| `VarkaFusedCountSuite` | 1 (three tests), 1b (five), 2 (ten) | plans and answers against Spark's, through SQL |

## 3. The admission check

Each fact was read on master `6dafb8bd2f6` in the worktree `/home/max/proj/varka-m9-read-w`.
The commands run from the repository's root and start no JVM.

**F1. Nothing of the row is built.**

    git branch -a --list '*varka-651*' '*varka-650*' '*varka-652*'     # prints nothing
    git grep -l 'VarkaFilterCountExec\|fusedCount' -- sql docs dev ':!sql/varka/plans'   # prints nothing

The branch `varka-pi-rows` holds only the three plan rows and is merged (`git log --oneline -1
--grep='rows 650 to 652'` prints `df900510961`).

**F2. The count of a batch exists.** `VarkaFilterEvaluator.filterMask` ends in
`return new VarkaSelection(mask, VarkaSelectionBitmap.countSet(mask, len));` and `countSet`
masks the last word:

    grep -n 'countSet' sql/core/src/main/java/org/apache/spark/sql/execution/VarkaFilterEvaluator.java
    grep -n '(1L << rest) - 1' sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaSelectionBitmap.java

The first prints two lines, a comment and the `return`; the second prints one.

**F3. What Spark plans for the partial phase.** `AggUtils.planAggregateWithoutDistinct` builds
the partial aggregate with `requiredChildDistributionExpressions = None`, the function in mode
`Partial`, `initialInputBufferOffset = 0` and `resultExpressions` equal to the grouping
attributes followed by each function's `inputAggBufferAttributes`. For `count(*)` the function
is `Count(Seq(Literal(1)))` (`Count.scala`): its buffer is one attribute `count`, `LongType`, not
nullable, its initial value `Literal(0L)`. So the partial aggregate's `output` is one attribute
named `count`, a `LongType` that is not nullable, and its row is an `UnsafeRow` of one long.

    grep -n 'inputAggBufferAttributes\|requiredChildDistributionExpressions = None' sql/core/src/main/scala/org/apache/spark/sql/execution/aggregate/AggUtils.scala
    grep -n 'AttributeReference("count", LongType, nullable = false)' sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/expressions/aggregate/Count.scala

**F4. The final aggregate binds by position.** `BaseAggregateExec.inputAttributes` replaces the
last attributes of its child's output with its own function's `inputAggBufferAttributes`
(SPARK-31620). So the final aggregate reads column 0 of the partial row whatever the attribute's
id is. The node of this plan still keeps the partial aggregate's `resultExpressions` unchanged,
so the exchange between them and every plan check see the same attribute.

**F5. An empty partition gives a row.** `HashAggregateExec.doExecute`: with no grouping and no
input row it emits `outputForEmptyGroupingKeyWithoutInput()`, the initial buffer, and adds 1 to
`numOutputRows`. `SortAggregateExec` and `ObjectHashAggregateExec` do the same. So a partial
`count(*)` emits exactly one row per partition, holding 0 for an empty one.

    grep -n 'outputForEmptyGroupingKeyWithoutInput' sql/core/src/main/scala/org/apache/spark/sql/execution/aggregate/*.scala

**F6. The partial aggregate's partitioning.** `BaseAggregateExec` mixes in
`PartitioningPreservingUnaryExecNode` with `outputExpressions = resultExpressions`. A child
partitioning that is an expression over columns the output lacks is dropped; any other is kept;
with none left it is `UnknownPartitioning(n)` of the child's `n` partitions.

**F7. The order of Spark's rules.** `EnsureRequirements` runs before
`ApplyColumnarRulesAndInsertTransitions`, and `CollapseCodegenStages` after it
(`QueryExecution.preparations`); under adaptive execution the last two run on each new stage
(`AdaptiveSparkPlanExec.postStageCreationRules`), whose root is the exchange. So when
`postColumnarTransitions` runs, the exchanges are placed, no `WholeStageCodegenExec` or
`InputAdapter` is in the tree yet, and under adaptive execution the rule sees one stage at a
time: the partial aggregate and what is below it, never the final aggregate above the shuffle.
`CombineAdjacentAggregation` runs between the two, after `EnsureRequirements` and before the
columnar rules, in both lists (`QueryExecution.preparations` and
`AdaptiveSparkPlanExec.queryStagePreparationRules`). So the rule of this row sees the
aggregates as that rule left them (F10).

**F8. The plan below the partial aggregate, read from the code, not run.** For a cached table
the strategy `InMemoryScans` calls `SparkPlanner.pruneFilterProject(projectList, filters, ...)`.
For `SELECT count(*) FROM t WHERE p` the project list is empty and the filter reads columns, so
its second branch builds `ProjectExec(Nil, FilterExec(p, scan))`. `RemoveRedundantProjects` keeps
it, since its output differs from its child's. `VarkaColumnarRule` then turns the filter into
`VarkaFilterExec`, Spark wraps it in `ColumnarToRowExec`, and the post stage fuses the two into
`VarkaFilterColumnarToRowExec`. No arm absorbs the empty projection: `isForwardedNarrowing` asks
`projectList.nonEmpty`. So the shape to match is

    HashAggregate(keys=[], functions=[partial_count(1)])
    +- Project                                   (an empty project list)
       +- VarkaFilterColumnarToRowExec(p, scan)

and, over a source that plans no projection, the same without the `Project`. **Only a JVM
settles this**: part 1's admission test asserts it (section 5, part 1, step 7), and the
builder stops if it fails.

**F9. Readers of "which node is a Varka node".** These match the four node classes by name and
do not know a fifth: `VarkaSharedSessions.isVarkaNode` and
`VarkaSqlBenchmarkBase.isVarkaNode` (part 2 adds the new node to both), and tests that read a
filter's condition off the row filter node under a counted query, for one
`VarkaDecimalBaselineQueriesSuite`. With the switch off, none of them sees the new node.

    git grep -n 'VarkaFilterColumnarToRowExec' -- sql/core/src/test | wc -l      # 49

**F10. Spark merges the two aggregates where no exchange separates them.**
`CombineAdjacentAggregation` (`sql/core/.../execution/CombineAdjacentAggregation.scala`) is
gated by `spark.sql.execution.combineAdjacentAggregation`, an internal conf whose default is
`true`. For each aggregate whose functions are all `Final` it walks down from the child
(`detachAggregate`), crossing only a `GroupPartitionsExec` and a local `SortExec`; an exchange
ends the walk. Where it reaches the `Partial` aggregate of the same logical aggregate, it
returns a copy of the FINAL node with the partial node's child. What the merged node holds:

| field | value | from |
| :--- | :--- | :--- |
| `aggregateExpressions` | the partial node's, each with `mode = Complete` | `combinedAggregate`, and the `copy` calls of the other two node classes |
| `groupingExpressions` | the partial node's (empty here) | the same |
| `initialInputBufferOffset` | 0 | the same |
| `requiredChildDistributionExpressions` | the final node's: `Some(Nil)` with no grouping | `combineHashAggregates`: "Keep the final aggregate's distribution requirement" |
| `aggregateAttributes` | the final node's: each function's result attribute | not named in the `copy`, so kept |
| `resultExpressions` | the final node's: the query's result expressions over those attributes | not named in the `copy`, so kept |

    grep -c 'partialAgg.aggregateExpressions.map(_.copy(mode = Complete))' sql/core/src/main/scala/org/apache/spark/sql/execution/CombineAdjacentAggregation.scala   # 3
    grep -A8 'val COMBINE_ADJACENT_AGGREGATION_ENABLED' sql/catalyst/src/main/scala/org/apache/spark/sql/internal/SQLConf.scala | grep -c 'createWithDefault(true)'   # 1

The planner itself never plans an ungrouped `Complete` aggregate with no `DISTINCT`:
`AggUtils.planAggregateWithoutDistinct` takes its one-aggregate path only under
`bypassPartialAggregation && hasGroupingKeys`. So the merged node of F10 is the only source of
the shape this row matches in `Complete` mode.

**F11. A merged ungrouped aggregate reads exactly one partition.** Settled from the code:

1. The final aggregate of an ungrouped query has `requiredChildDistributionExpressions =
   Some(Nil)` (`AggUtils`: `Some(groupingAttributes)`, the list empty), which
   `BaseAggregateExec.requiredChildDistribution` turns into `AllTuples`.
2. `EnsureRequirements` leaves a child as it is only where its partitioning satisfies the
   distribution, and `Partitioning.satisfies0` answers `AllTuples` with `numPartitions == 1`.
   A child that declares any other count, 0 included, gets an `Exchange SinglePartition`.
3. The merge rule has no partition test of its own. It runs after `EnsureRequirements`, and an
   exchange stops its walk. So it merges exactly where step 2 placed no exchange: where the
   partial aggregate, and so its child (the partial aggregate keeps its child's partition
   count, F6), declares one partition.
4. A node that declares one partition runs one: `LocalTableScanExec` builds "a single empty
   partition to match the `SinglePartition` reported" for no rows when `useSingleTask` is set
   (by the optimizer rule `MarkSingleTaskExecution`); without it an empty relation declares
   `UnknownPartitioning(0)`, which does not satisfy `AllTuples`, so an exchange is planned and
   the rule does not merge. `CoalesceExec` builds one empty partition for a child with none.

So a `Complete` ungrouped `count(*)` sits over the counted filter only where the filter's
child has one partition, by construction. It cannot sit over several, and the rule cannot
merge over a multi-partition child. The node still asserts it (4.3), since a wrong count would
be silent.

**F12. What the merged aggregate emits.** `HashAggregateExec.doExecute` runs once a partition
of its child. With no grouping it emits ONE row for the partition, also when the partition has
no row (`outputForEmptyGroupingKeyWithoutInput()`, the initial buffer, 0 for a count). In
`Complete` mode `AggregationIterator.generateResultProjection` evaluates the function into a
row of `aggregateAttributes` and applies `UnsafeProjection.create(resultExpressions,
groupingAttributes ++ aggregateAttributes)` to it. So over its one partition the merged
aggregate emits one row: the result expressions over the count. Over a child of no partition
it would emit no row at all (`mapPartitionsWithIndex` over none), which step 2 of F11 keeps
from being planned.

**What I could not check** (no JVM was allowed): F8's plan shape, in either mode; that a
cached view built with `coalesce(1)` plans the merged aggregate of F10; that the pi predicate
`x * x + y * y < 1` over two `DOUBLE` columns fuses whole (row 650's check); how the plan reads
under adaptive execution with a `TableCacheQueryStageExec` below the filter. Section 9 gives
each its command.

## 4. The design

### 4.1 The switch

A SQL configuration, not an emit option: the rule reads it when it plans, no kernel's bytes
depend on it, and the option matrix (`dev/varka_matrix.sh`) is unchanged by it.

- Key `spark.sql.codegen.varka.fusedCount.enabled`, internal, boolean, default `false`.
- **The default is `false` through parts 1, 1b and 2, and part 3a flips it to `true`** (the
  owner's decision of 28 November 2026: "yes, flip it in this milestone"). The house rule is
  that a new behaviour's default flips last: the node lands behind the switch, is held to
  Spark's answers with the switch set, and only then becomes what a user gets. The flip changes
  the plan of every counted fused filter, and with it the committed benchmark sections of that
  shape and the tests of fact F9; part 3a lists both, and part 3b regenerates the files.

### 4.2 The plan shape that is matched

The match is on an aggregate node, in `postColumnarTransitions`, after its children were
rewritten (`transformUp` visits children first). There are two matches, one for each mode.

**The `Partial` match (part 1).** All of these must hold:

1. The switch is on.
2. The node is a `BaseAggregateExec` (so `HashAggregateExec`, `SortAggregateExec` and
   `ObjectHashAggregateExec` alike: all three emit the same partial row, fact F5).
3. `isStreaming` is false, `groupingExpressions` is empty,
   `requiredChildDistributionExpressions` is `None`, `initialInputBufferOffset` is 0.
4. `aggregateExpressions` is exactly one `AggregateExpression` whose function is
   `Count(Seq(Literal(v, _)))` with `v != null`, whose mode is `Partial`, with `isDistinct`
   false and `filter` empty.
5. `resultExpressions` is exactly one expression of `LongType`.
6. The child is a `VarkaFilterColumnarToRowExec`, or a `ProjectExec` with an empty project
   list whose child is one.

**The `Complete` match (part 1b)**, on the merged aggregate of fact F10. All of these must
hold; C1, C2 and C6 are conditions 1, 2 and 6 above:

- C3. `isStreaming` is false, `groupingExpressions` is empty, `initialInputBufferOffset` is 0,
  and `requiredChildDistributionExpressions` is `Some` of an empty list: the node asked for
  `AllTuples`, so `EnsureRequirements` gave it a child of one partition (F11).
- C4. `aggregateExpressions` is exactly one `AggregateExpression` whose function is
  `Count(Seq(Literal(v, _)))` with `v != null`, whose mode is `Complete`, with `isDistinct`
  false and `filter` empty.
- C5. `aggregateAttributes` is exactly one attribute of `LongType`, and every attribute any
  of the `resultExpressions` reads is that one.

What replaces the node is `ProjectExec(agg.resultExpressions, VarkaFilterCountExec(...,
mode = Complete))`. The count node emits the count as the aggregate's result attribute, and
Spark's own projection computes the result expressions from it, as the aggregate's result
projection did (F12): the same expressions bound to the same attribute. So an alias, `count(*)
+ 1` and `4 * count(*) / n` are all served, with no arithmetic of this row's own. The
projection is planted always, also where the result is the bare attribute: one path, and the
count node is then never the root of a plan.

**Each shape that does not match the `Partial` match, and the condition that refuses it.**

| shape | refused by |
| :--- | :--- |
| `count(col)` | 4: the child of `Count` is a column, not a literal |
| `count(NULL)` | 4: `v` is null |
| `count(DISTINCT col)` | 3: its partial aggregates group by `col` |
| `count(*) ... GROUP BY g` | 3: grouping expressions |
| `count(*) FILTER (WHERE q)` | 4: the aggregate's own `filter` is set |
| `count(*), max(i)`, a second aggregate | 4: two aggregate expressions |
| a filter the compiler declined | 6: the child is Spark's `FilterExec` |
| a filter split into fused and residual conjuncts | 6: Spark's `FilterExec` of the residual sits between |
| a count over a bare scan | 6: no filter node |
| a count over a `LIMIT` over the filter | 6: the limit sits between |
| a projection that computes below the count | 6: its project list is not empty |
| a streaming aggregate | 3 |
| the final aggregate, or a `PartialMerge` | 4: the mode |
| the switch off | 1: the plan is today's |

**Each `Complete` shape, and whether the node serves it.** "Served" means matched, with
Spark's result projection kept above the node.

| a merged (`Complete`) aggregate with | verdict | by |
| :--- | :--- | :--- |
| a grouping, `count(*) ... GROUP BY g` | refused | C3: grouping expressions |
| a second aggregate, `count(*), max(i)` | refused | C4: two aggregate expressions |
| `count(col)` | refused | C4: the child of `Count` is a column |
| `count(NULL)` | refused | C4: `v` is null |
| `count(DISTINCT col)` | refused | C3 and C4: its aggregates group by `col`, and the one that counts has `isDistinct` set |
| `count(*) FILTER (WHERE q)` | refused | C4: the merged node keeps the partial function with its `filter` |
| an alias, `count(*) AS c` | served | the alias is the result expression, in the projection above |
| a result that is not the bare count: `count(*) + 1`, `4 * count(*) / n` | served | the arithmetic is the result expression, in the projection above; it raises or answers as in the aggregate, being the same expression |
| a `HAVING` | served | a `HAVING` is never folded into the aggregate node: it is a `FilterExec` above it, and stays above the projection |
| a result expression that reads another attribute | refused | C5 (it cannot occur with no grouping; refused so that the projection above never has a missing input) |
| a required distribution that is not `AllTuples` | refused | C3 |
| a streaming aggregate | refused | C3 |
| a residual `FilterExec`, a `LIMIT` or a computing projection between | refused | C6, as in the `Partial` table |
| a count over a bare scan, or over a filter the compiler declined | refused | C6 |
| the final aggregate over this row's own `Partial` node (Spark's merge rule off, one partition) | not matched | C4: its mode is `Final`; it runs as Spark's |
| the switch off | not matched | C1 |

**`HAVING` matches in both modes, and that is right.** `SELECT count(*) AS c FROM t WHERE p
HAVING c > 1` plans the same aggregates as the query without the `HAVING`; the `HAVING` is a
filter above the final, or the merged, aggregate. The node replaces only the counting, so the
`HAVING` runs as today. In `Partial` mode it cannot be refused under adaptive execution either:
the rule sees the stage below the shuffle only (fact F7). `SELECT 4 * count(*) / n` is the
same case: its arithmetic is the final aggregate's result projection, or the projection the
`Complete` match keeps. All are tested for equal answers (part 1b, test 4; part 2, test 4).

### 4.3 The node

A new file `sql/core/src/main/scala/org/apache/spark/sql/execution/VarkaFilterCountExec.scala`,
Scala because a `SparkPlan` subclass is Spark's interop surface (`sql/varka/AGENTS.md`, "What
stays as it is"). The license header is copied from `VarkaFilterExec.scala`. The class Javadoc
says what section 2's "The idea" says, in the present tense, with no history.

    case class VarkaFilterCountExec(
        condition: Expression,
        resultExpressions: Seq[NamedExpression],
        child: SparkPlan,
        mode: AggregateMode = Partial,
        wholeCondition: Option[Expression] = None,
        sessionEmit: VarkaSessionEmit = VarkaSessionEmit.of(SQLConf.get))
        extends UnaryExecNode with PartitioningPreservingUnaryExecNode {

      require(resultExpressions.size == 1 && resultExpressions.head.dataType == LongType,
        s"a count has one BIGINT result, not $resultExpressions")
      require(mode == Partial || mode == Complete,
        s"a fused count stands for a Partial or a Complete aggregate, not $mode")

      // The count under the attribute the plan above was built against. In Partial mode that
      // is the partial aggregate's buffer attribute, which the final aggregate reads. In
      // Complete mode it is the merged aggregate's result attribute, which Spark's result
      // projection above this node reads.
      override def output: Seq[Attribute] = resultExpressions.map(_.toAttribute)

      // A Complete count is one row for the whole child, so it asks what the aggregate it
      // stands for asked: every row in one partition. A Partial count is one row a
      // partition and asks nothing.
      override def requiredChildDistribution: Seq[Distribution] =
        if (mode == Complete) AllTuples :: Nil else UnspecifiedDistribution :: Nil

      // The partial aggregate's rule for its partitioning, over the same expressions.
      override protected def outputExpressions: Seq[NamedExpression] = resultExpressions

      // The result attribute is made here, not read from the child.
      override def producedAttributes: AttributeSet = AttributeSet(output)

      // The settings are an implementation detail of the node, as on the filter nodes.
      override protected def stringArgs: Iterator[Any] =
        super.stringArgs.filterNot(_.isInstanceOf[VarkaSessionEmit])

      // The row filter node this one replaces, for what the two share: the per-row
      // fallback's condition in Spark's order, and the EXPLAIN lines.
      @transient private lazy val rowFilter = VarkaFilterColumnarToRowExec(
        condition, child, None, wholeCondition, sessionEmit)

      override lazy val metrics: Map[String, SQLMetric] =
        VarkaExecMetrics.nodeMetrics(sparkContext, true).asScala.toMap +
          ("numCountedRows" -> SQLMetrics.createMetric(sparkContext, "number of rows counted"))

      override def verboseStringWithOperatorId(): String = {
        s"""
           |$formattedNodeName
           |${ExplainUtils.generateFieldString("Input", child.output)}
           |Condition : $condition
           |${ExplainUtils.generateFieldString("Results", resultExpressions)}
           |${ExplainUtils.generateFieldString("Varka", rowFilter.fusionLines)}
           |""".stripMargin
      }

      override protected def withNewChildInternal(newChild: SparkPlan): VarkaFilterCountExec =
        copy(child = newChild)

      override protected def doExecute(): RDD[InternalRow] = {
        val batches = child.executeColumnar()
        // One row a partition is one row for the whole child only over one partition, which
        // is what the planner gives a node that requires AllTuples. A child of no partition
        // gives no row, as the aggregate this node stands for would.
        assert(mode == Partial || batches.getNumPartitions <= 1,
          s"a Complete count reads one partition, not ${batches.getNumPartitions}")
        val evaluatorFactory = new VarkaFilterCountEvaluatorFactory(
          condition,
          child.output,
          conf.varkaClassDumpDirectory,
          longMetric("numOutputRows"),
          longMetric("numInputBatches"),
          longMetric("numCountedRows"),
          VarkaExecMetrics.fromNode(metrics.getOrElse(_, null)),
          sessionEmit,
          conf.varkaWarmupEnabled,
          Some(rowFilter.fallbackCondition),
          VarkaColumnarRule.readsArrowCache(child),
          rowFilter.fallbackEliminatesSubexpressions)
        if (conf.usePartitionEvaluator) {
          batches.mapPartitionsWithEvaluator(evaluatorFactory)
        } else {
          batches.mapPartitionsWithIndexInternal { (index, partition) =>
            evaluatorFactory.createEvaluator().eval(index, partition)
          }
        }
      }
    }

The imports the two modes add to the file: `AggregateMode`, `Complete`, `Partial`
(`org.apache.spark.sql.catalyst.expressions.aggregate`) and `AllTuples`, `Distribution`,
`UnspecifiedDistribution` (`org.apache.spark.sql.catalyst.plans.physical`).

**One class, two modes, by the field `mode`.** The two modes share the whole evaluator: one
row a partition, the sum of its batches' counts. They differ in three places only, each
above: which attribute `resultExpressions` holds (the rule's choice, 4.5), the required child
distribution, and the assertion in `doExecute`. Two classes would repeat everything else. The
field's default is `Partial`, and the whole of this class, both modes, is built in part 1;
part 1b adds only the rule arm that plants `Complete`.

**The `Complete` mode, member by member.**

| member | in `Complete` mode | why |
| :--- | :--- | :--- |
| `output` | the merged aggregate's one `aggregateAttributes` entry, `count(1)#n`, `BIGINT`, not nullable | the result expressions in the projection above are written against it (F10, F12) |
| `requiredChildDistribution` | `AllTuples` | what the merged aggregate required (F11); a later pass of `EnsureRequirements` then sees the same requirement, already met |
| `outputPartitioning` | the trait's rule over `resultExpressions`: the child's `SinglePartition` kept, or `UnknownPartitioning(1)` | the merged aggregate derives its own by the same trait from the same child; the projection above is the same trait again, so what the plan above was built against is unchanged |
| `doExecute` | one row for the child's one partition, 0 where it holds no row; no row for a child of no partition; an `AssertionError` on the driver for a child of several | F12; several cannot be planned (F11), and Scala's `assert` is on in production, so a node that declares one partition and runs several stops the query instead of answering with a row a partition |

Why each member is as it is:

- **Not a `VarkaFilterExecBase`.** That trait fixes `outputPartitioning = child.outputPartitioning`
  and the filter's output; this node's output is the aggregate's, and
  `PartitioningPreservingUnaryExecNode` declares `outputPartitioning` final. It reuses the
  trait's members through `rowFilter`, a node built on the fly, as
  `VarkaFilterColumnarToRowExec.columnarSibling` builds a `VarkaFilterExec`.
- **Not a `ColumnarToRowTransition`.** A consumer that strips a transition to reach a columnar
  plan (`CachedBatchSerializer.convertToColumnarPlanIfPossible`) would drop the count. The node
  is an ordinary row node over a columnar child. It is never the root of a plan: a final
  aggregate is above it in `Partial` mode, and Spark's result projection in `Complete` mode.
- **`outputOrdering`** is the default, none, as the partial aggregate's is with no grouping.
- **`producedAttributes`** must be overridden. Without it the result attribute counts as a
  missing input (`QueryPlan.missingInput`), and the plan prints with a `!`.
- **Two members of `VarkaFilterExecBase` are widened** so that `rowFilter` can be asked:
  `protected def fallbackCondition` becomes `private[sql] def fallbackCondition`, and
  `@transient private lazy val fusionLines` becomes `@transient private[sql] lazy val fusionLines`.
  Nothing else in that file changes.
- **The name** is `VarkaFilterCountExec`, shown as `VarkaFilterCount` in `EXPLAIN` with its
  mode among its arguments: the filter nodes' naming, decided here.

### 4.4 The evaluator

In the same file. It is the count twin of `VarkaFilterToRowEvaluatorFactory`.

    private[sql] class VarkaFilterCountEvaluatorFactory(
        condition: Expression,
        childOutput: Seq[Attribute],
        classDumpDirectory: Option[String],
        numOutputRows: SQLMetric,
        numInputBatches: SQLMetric,
        numCountedRows: SQLMetric,
        varkaMetrics: VarkaExecMetrics,
        sessionEmit: VarkaSessionEmit,
        warmupEnabled: Boolean,
        wholeCondition: Option[Expression],
        readsArrowCache: Boolean,
        fallbackEliminates: Boolean)
        extends PartitionEvaluatorFactory[ColumnarBatch, InternalRow] {

      override def createEvaluator(): PartitionEvaluator[ColumnarBatch, InternalRow] =
        new PartitionCountEvaluator

      private class PartitionCountEvaluator
          extends PartitionEvaluator[ColumnarBatch, InternalRow] {

        // The kernel half is the filter's own; nothing is compacted, so the off-heap flag
        // is moot, as on the row filter node.
        private val kernels = new VarkaFilterEvaluator(
          condition, childOutput, false, "FilterCount", classDumpDirectory, varkaMetrics,
          sessionEmit, warmupEnabled, readsArrowCache)

        // Lazy: a task the kernel serves end to end never pays the Janino compile.
        private lazy val fallbackPredicate: BasePredicate = {
          val predicate = VarkaFallbackPredicate.create(BindReferences.bindReference(
            wholeCondition.getOrElse(condition), AttributeSeq(childOutput)), fallbackEliminates)
          predicate.initialize(partitionIdx)
          predicate
        }
        private var partitionIdx: Int = 0

        override def eval(
            partitionIndex: Int,
            inputs: Iterator[ColumnarBatch]*): Iterator[InternalRow] = {
          assert(inputs.length == 1)
          partitionIdx = partitionIndex
          val batches = inputs.head
          // One row for the partition whatever it holds, as an aggregate with no grouping
          // gives in either mode: an empty partition's count is 0.
          new Iterator[InternalRow] {
            private var done = false

            override def hasNext: Boolean = !done

            override def next(): InternalRow = {
              if (done) {
                throw new NoSuchElementException("next on an exhausted count iterator")
              }
              done = true
              var total = 0L
              while (batches.hasNext) {
                val input = batches.next()
                numInputBatches += 1
                total += countBatch(input)
              }
              numCountedRows += total
              numOutputRows += 1
              // One BIGINT that is never null: 8 bytes of null bits and the value.
              val row = UnsafeRow.createFromByteArray(16, 1)
              row.setLong(0, total)
              row
            }
          }
        }

        // The batch's count from whichever path answers it. The kernel path adds nothing
        // itself: a batch it fails or declines part-way is counted once, by the fallback.
        private def countBatch(input: ColumnarBatch): Long = {
          kernels.serveBatch[java.lang.Long](input,
            () => {
              val selected = kernels.filterMask(input).count.toLong
              VarkaExecMetrics.inc(varkaMetrics.varkaBatches)
              Long.box(selected)
            },
            () => Long.box(fallbackCount(input))).longValue
        }

        private def fallbackCount(input: ColumnarBatch): Long = {
          val rows = kernels.inputRows(input)
          var selected = 0L
          while (rows.hasNext) {
            if (fallbackPredicate.eval(rows.next())) {
              selected += 1
            }
          }
          selected
        }
      }
    }

The edges, each with what the code above does:

| case | what happens |
| :--- | :--- |
| a partition with no batch | the loop runs zero times; one row holding 0 |
| a batch of 0 rows | `canRun` is false for it, so the fallback counts 0; no cause is recorded (`recordRefusedBatch` asks `numRows > 0`) |
| a predicate that is `NULL` on a row | the kernel leaves the bit clear; `BasePredicate.eval` answers false: not counted, as SQL's `WHERE` |
| every row selected, or none | `count` is `len`, or 0; no special path |
| a stale bitmap tail from a longer earlier batch | `countSet` masks the last word (fact F2) |
| a batch that is not Arrow | `serveBatch` records the cause and calls the fallback |
| the kernel declines the batch (a value outside a lowering's range) | `VarkaBatchDeclined` is caught by `serveBatch`, counted by its reason, and the fallback counts the batch; under ANSI Spark's predicate raises on the row that Spark would raise on |
| the kernel fails | counted as a kernel failure; the fallback counts the batch; under test an undeclared failure fails the task (`failureFallbackForbidden`) |
| the kernel is warming | `serveBatch` counts a warm-up batch and calls the fallback |
| the emitter declined the shape | `fusedRunner` is null, `canRun` is false, every batch takes the fallback |
| the per-row predicate raises | the error leaves the task as Spark's filter's would; no row was emitted before it, since the node emits one row at the end |

`serveBatch` is a Java generic method, so its type argument is the boxed `java.lang.Long`,
written out: one box per batch. The other nodes build two closures and a
`VarkaSelection` per batch already (`VarkaBatchPath`'s Javadoc says so), and a batch is
thousands of rows; no per-row allocation is added.

### 4.5 The rule arm

In `VarkaColumnarRule.postColumnarTransitions`, new `case`s as the last arms of the
`plan.transformUp { ... }` block, after the `FilterExec` arm. No other arm matches an aggregate
node, so their place among the arms changes nothing; last keeps the diff in one spot.

**Part 1, the `Partial` arm:**

        // An ungrouped count(*) straight over a fused filter: the partial aggregate would only
        // count the rows the filter converts for it, and the filter's kernel has the count
        // already, as its selection bitmap's set bits. See `VarkaFilterCountExec`.
        case agg: BaseAggregateExec
            if SQLConf.get.varkaFusedCountEnabled && isPartialCountStar(agg) =>
          countedFilter(agg.child) match {
            case Some(filter) =>
              VarkaFilterCountExec(filter.condition, agg.resultExpressions, filter.child,
                Partial, filter.wholeCondition, filter.sessionEmit)
            case None => agg
          }

and two private methods of the object, placed after `isForwardedNarrowing`:

      /**
       * Whether `agg` is the partial phase of one ungrouped `count(*)` and nothing else: no
       * grouping, no `DISTINCT`, no `FILTER` clause, no second aggregate, not streaming. The
       * literal under `Count` is what the analyzer makes of `*`, and of `count(1)`; a
       * `count(NULL)` counts nothing and a `count(col)` skips the column's nulls, so neither is
       * the number of rows.
       */
      private def isPartialCountStar(agg: BaseAggregateExec): Boolean =
        !agg.isStreaming && agg.groupingExpressions.isEmpty &&
          agg.requiredChildDistributionExpressions.isEmpty &&
          agg.initialInputBufferOffset == 0 &&
          (agg.aggregateExpressions match {
            case Seq(AggregateExpression(
                Count(Seq(Literal(value, _))), Partial, false, None, _)) =>
              value != null && agg.resultExpressions.size == 1 &&
                agg.resultExpressions.head.dataType == LongType
            case _ => false
          })

      /**
       * The row filter node whose selected rows `child` hands an aggregate unchanged: the node
       * itself, or the node under the empty projection Spark plans between a filter and an
       * aggregate that reads no column. A projection with entries may compute, and may raise,
       * so it is not looked through.
       */
      private def countedFilter(child: SparkPlan): Option[VarkaFilterColumnarToRowExec] =
        child match {
          case filter: VarkaFilterColumnarToRowExec => Some(filter)
          case ProjectExec(Seq(), filter: VarkaFilterColumnarToRowExec) => Some(filter)
          case _ => None
        }

A row filter node with a `narrowing` matches too: the narrowing only forwards columns, which
the count does not read. The first `case` inside `isPartialCountStar` is written on two lines,
as above, so that it is under 100 columns at any indentation.

The imports the rule gains in part 1: `Literal` (catalyst expressions), `AggregateExpression`,
`Count`, `Partial` (`org.apache.spark.sql.catalyst.expressions.aggregate`), `BaseAggregateExec`
(`org.apache.spark.sql.execution.aggregate`), `LongType` (`org.apache.spark.sql.types`).

**Part 1b, the `Complete` arm**, placed after the `Partial` arm:

        // The same count where Spark merged the two aggregates into one (its rule
        // CombineAdjacentAggregation, over a child of one partition): the node emits the
        // count as the aggregate's result attribute, and the aggregate's result expressions
        // stay Spark's, in a projection above it.
        case agg: BaseAggregateExec
            if SQLConf.get.varkaFusedCountEnabled && isCompleteCountStar(agg) =>
          countedFilter(agg.child) match {
            case Some(filter) =>
              ProjectExec(agg.resultExpressions,
                VarkaFilterCountExec(filter.condition, agg.aggregateAttributes, filter.child,
                  Complete, filter.wholeCondition, filter.sessionEmit))
            case None => agg
          }

and one more private method, after `isPartialCountStar`:

      /**
       * Whether `agg` is one ungrouped `count(*)` computed whole, the node Spark's
       * `CombineAdjacentAggregation` makes of a partial and a final aggregate with no exchange
       * between them, and nothing else. It required every row in one partition, which is what
       * makes one count row the answer; its result expressions read the count alone, so they
       * can run in a projection over it.
       */
      private def isCompleteCountStar(agg: BaseAggregateExec): Boolean =
        !agg.isStreaming && agg.groupingExpressions.isEmpty &&
          agg.requiredChildDistributionExpressions.exists(_.isEmpty) &&
          agg.initialInputBufferOffset == 0 &&
          (agg.aggregateExpressions match {
            case Seq(AggregateExpression(
                Count(Seq(Literal(value, _))), Complete, false, None, _)) =>
              value != null && (agg.aggregateAttributes match {
                case Seq(count) =>
                  count.dataType == LongType && agg.resultExpressions.forall(
                    _.references.forall(_.exprId == count.exprId))
                case _ => false
              })
            case _ => false
          })

The one import part 1b adds to the rule: `Complete`, beside `Partial`. `ProjectExec` is in the
rule's own package.

The planted `ProjectExec` is safe from the rule's other arms. In the pass that plants it,
`transformUp` does not visit a node a `case` returned. On a later pass the arm `case project @
ProjectExec(projectList, child) if isVarkaEligible(...)` may find the result expressions
eligible (`4 * count / n` is arithmetic over a `BIGINT`), and then leaves the projection as
it is, because it asks `columnarChild.supportsColumnar` and the count node is not columnar.
The new arm cannot match twice either: the aggregate is gone.

### 4.6 How Spark wraps the result

- **Adaptive execution off, several partitions:** `HashAggregate(final) <- Exchange
  SinglePartition <- VarkaFilterCount <- scan`. The exchange was placed before the rule ran
  and stays valid: the node's partitioning is the partial aggregate's (fact F6).
- **One partition** (the benchmarks' `local[1]` tables, a small table): Spark plans no
  exchange, since a one-partition child satisfies `AllTuples`, and then merges the two
  aggregates (F10, F11). The plan is `Project <- VarkaFilterCount(Complete) <- scan`: the
  `Complete` match.
- **One partition with Spark's merge rule off**
  (`spark.sql.execution.combineAdjacentAggregation=false`): the two aggregates stay, with no
  exchange. The plan is `HashAggregate(final) <- VarkaFilterCount(Partial) <- scan`, the
  `Partial` match; the node's partitioning still satisfies the final aggregate's `AllTuples`.
- **Whole-stage codegen:** the node is not `CodegenSupport`, so `CollapseCodegenStages` puts an
  `InputAdapter` above it where the final aggregate, or the projection, is in its stage, as it
  does above the row filter node today.
- **Adaptive execution on, several partitions:** the rule runs on the stage under the shuffle
  and matches there. The stage still emits one row per partition, so its statistics are not
  empty and `AQEPropagateEmptyRelation` has nothing to propagate.
- **Adaptive execution on, one partition:** the merge rule is in the adaptive list too (F7), so
  the plan handed to the rule holds the merged aggregate, and the `Complete` match plants the
  projection and the node in the result stage.
- **No tests or benchmark setting.** Nothing in this row turns Spark's merge rule off to get a
  plan it can match. The one test that sets it does so to show the answer is the same either
  way (part 1b, test 3).

### 4.7 Metrics and the fallback accounting

The node registers `VarkaExecMetrics.nodeMetrics(sparkContext, true)`: its fallback is eager (the
count is taken inside the call), so `fallbackTime` is registered, as on the columnar nodes.
`numOutputRows` counts the rows the node emits, one per partition, which is what the partial
aggregate's `numOutputRows` reads; in `Complete` mode it is 1, as the merged aggregate's is.
`numCountedRows` is new and holds the count itself, the
number the row filter node's `numOutputRows` showed before. `numInputBatches`,
`numVarkaBatches`, the four fallback causes, the per-reason declines, `numWarmupBatches`,
`numVarkaRows`, `numFallbackRows` and `varkaKernelTime` come from `serveBatch` unchanged. The
partial aggregate's own metrics (`peakMemory`, `spillSize`, `aggTime`, `avgHashProbe`,
`numTasksFallBacked`) go with the node that had them: there is no hash map to measure.

### 4.8 The row's "risks to hold", answered

| risk named by the row | answer |
| :--- | :--- |
| the partial buffer's schema and attribute ids | the node keeps `agg.resultExpressions` unchanged, so `output` is the same attribute; the row is one non-null long (F3, F4) |
| an empty partition's row | one row per partition always, 0 for an empty one (F5, 4.4) |
| adaptive query execution | the match is inside one stage; the partitioning is the aggregate's; one row per partition (F6, F7, 4.6) |
| the metrics | 4.7 |
| the fallback accounting | `serveBatch`, unchanged; a batch counts once (4.4) |
| the warm-up and decline fallback | both route the batch to the per-row count through `serveBatch` (4.4) |
| (not named by the row) no partial aggregate exists over one partition | the `Complete` mode: F10 to F12, 4.2, 4.3, 4.5 |

## 5. The parts

Five parts, each one pull request, in this order; the last two are the flip (after part 2).
Part 1 is the switch, the node with both
its modes, the `Partial` rule arm and the node-level tests. Part 1b is the `Complete` rule arm
with its tests: it is what makes the node plan over one partition. Part 2 holds the whole to
Spark's answers through SQL and adds the benchmark's arm. No part changes a plan while the
switch is off, so master stays green. Part 1b is cut from part 1 by size: together they would
be about 1000 counted lines (section 8). The benchmark's third arm, and so row 652, waits on part
1b: its tables are `local[1]`.

### Part 1: `[VARKA-651] Count a fused filter's rows from its bitmap, behind a switch`

Branch `varka-651a-filter-count`, from `origin/master`. The plan pull request, if the lead
opens one, takes `varka-651` (`dev/varka_task_new.sh 651 ...` makes it).

**B0, the premises.** Each must print what is said, or stop and report.

    git grep -c 'VarkaFilterCountExec' -- sql ':!sql/varka/plans'               # prints nothing
    grep -c 'protected def fallbackCondition' sql/core/src/main/scala/org/apache/spark/sql/execution/VarkaFilterExec.scala          # 1
    grep -c '@transient private lazy val fusionLines' sql/core/src/main/scala/org/apache/spark/sql/execution/VarkaFilterExec.scala   # 1
    grep -c 'case filter @ FilterExec(condition, child) =>' sql/core/src/main/scala/org/apache/spark/sql/execution/VarkaColumnarRule.scala   # 1
    grep -c 'return new VarkaSelection(mask, VarkaSelectionBitmap.countSet(mask, len));' sql/core/src/main/java/org/apache/spark/sql/execution/VarkaFilterEvaluator.java   # 1
    grep -c 'val VARKA_WARMUP_ENABLED' sql/catalyst/src/main/scala/org/apache/spark/sql/internal/SQLConf.scala                       # 1

**The steps.**

1. `SQLConf.scala`: after the `VARKA_WARMUP_ENABLED` entry add

        val VARKA_FUSED_COUNT_ENABLED = buildConf("spark.sql.codegen.varka.fusedCount.enabled")
          .internal()
          .doc("When true, an ungrouped count(*) directly over a filter Varka fuses whole takes" +
            " each batch's count from the filter kernel's selection bitmap, in a" +
            " VarkaFilterCount node in place of the partial aggregate, and no selected row is" +
            " converted for Spark's aggregate to count. The final aggregate is Spark's. A" +
            " count(col), a DISTINCT, a grouping, a second aggregate, a filter with a residual" +
            " conjunct and a count over a bare scan plan as they do with it false.")
          .version("5.0.0")
          // Chooses which of two equal-answer plans counts the rows; nothing a view body
          // resolves to.
          .withBindingPolicy(ConfigBindingPolicy.NOT_APPLICABLE)
          .booleanConf
          .createWithDefault(false)

   and, after `def varkaWarmupEnabled`, `def varkaFusedCountEnabled: Boolean =
   getConf(VARKA_FUSED_COUNT_ENABLED)`. The version string is the neighbours'.
2. `VarkaFilterExec.scala`: the two widened modifiers of 4.3, nothing else.
3. The new file `VarkaFilterCountExec.scala`: 4.3 and 4.4.
4. `VarkaColumnarRule.scala`: 4.5, and one sentence in the object's Scaladoc after the
   paragraph on filters: an ungrouped `count(*)` over a row filter node becomes a
   `VarkaFilterCountExec` when `spark.sql.codegen.varka.fusedCount.enabled` is set.
5. `docs/sql-varka.md`: one row in the configuration table (the table whose header is
   `| Config | Default | Description |`), after the `warmup.enabled` row: the key, `false`, and
   the conf's doc sentence. The generated blocks of that file are not touched.
6. The new suite `VarkaFilterCountExecSuite.scala`, in
   `sql/core/src/test/scala/org/apache/spark/sql/execution/`,
   ScalaTest (a sql/core suite over Spark's session), `extends QueryTest with SharedSparkSession
   with VarkaTestWatchdog`, as `VarkaFilterExecSuite` does. It uses that suite's scaffolding:
   `BatchSpec("arrow" | "onheap", columns, numRows)` with values boxed as `java.lang.Integer`
   (`Int.box(1)`, `null`), `TestColumnarBatchPlan(specs, output)` (one batch per partition, one
   partition per spec), `attrD = AttributeReference("d", DateType)()`, `intAttr =
   AttributeReference("i", IntegerType)()` and the predicate `dLess10 = LessThan(attrD,
   Literal(10, DateType))`, "the date is before epoch day 10". Copy those four definitions.

   A helper builds the node and reads its rows:

        private val countAttr = AttributeReference("count", LongType, nullable = false)()
        private def counts(specs: Seq[BatchSpec]): (VarkaFilterCountExec, Seq[Long]) = {
          val node = VarkaFilterCountExec(dLess10, Seq(countAttr),
            TestColumnarBatchPlan(specs, Seq(attrD)))
          (node, node.execute().map(_.getLong(0)).collect().toSeq)
        }

   The eleven tests, each with its inputs and what it asserts (a spec below is one column of
   day numbers):

| # | test name | input | asserts |
| ---: | :--- | :--- | :--- |
| 1 | "one row a partition, counting the true rows and no NULL" | two specs: `1, null, 9, 10, 42` and `3, 4` | rows `Seq(2L, 2L)`; `numCountedRows` 4, `numOutputRows` 2, `numVarkaBatches` 2 |
| 2 | "all selected, none selected and one row" | four specs: `1, 2, 3`; `50, 60`; `5`; `77` | `Seq(3L, 0L, 1L, 0L)` |
| 3 | "a batch of no rows counts 0 and records no fallback" | one spec with an empty column | `Seq(0L)`; `numFallbackBatchesNonArrow`, `numFallbackBatchesKernel`, `numFallbackBatchesRowPath` and `numFallbackBatchesDeclined` are 0 |
| 4 | "a batch that is not Arrow is counted by the row path" | one `"onheap"` spec `1, null, 50` | `Seq(1L)`; `numFallbackBatchesNonArrow` 1, `numVarkaBatches` 0 |
| 5 | "a declined batch is counted once, by the row path" | spec `1, 2, 50` under `VarkaColumnarToRowExec.setDeclineKernelForTesting(true)`, reset to `false` in `finally` | `Seq(2L)`; `numFallbackBatchesDeclined` 1, `numVarkaBatches` 0 |
| 6 | "a failed kernel is counted once, by the row path" | the same spec under `setFailKernelForTesting(true)`, reset in `finally` | `Seq(2L)`; `numFallbackBatchesKernel` 1 |
| 7 | "65 and 129 rows count across the last word's mask" | two specs of 65 and of 129 values, the value at row `r` being `r % 20` | each count equals `values.count(_ < 10)`, computed in the test |
| 8 | "the output is the aggregate's attribute and nothing is a missing input" | the node of test 1 | `node.output === Seq(countAttr)`, `node.missingInput.isEmpty`, `node.outputPartitioning === node.child.outputPartitioning` (the test scan's `UnknownPartitioning` is kept as it is), `node.outputOrdering.isEmpty` |
| 9 | "the rule plants the node only on a partial count(*) over the row filter node" | plans built by hand, below | match and no match |
| 10 | "a Complete count is one row for a child of one partition, and asks for all tuples" | `VarkaFilterCountExec(dLess10, Seq(countAttr), TestColumnarBatchPlan(Seq(spec), Seq(attrD)), Complete)`, once with the one spec `1, null, 9, 10, 42` and once with one spec of an empty column | rows `Seq(2L)`, then `Seq(0L)`; `numOutputRows` 1 each time; `node.requiredChildDistribution === Seq(AllTuples)`; for test 1's node, whose mode is the default, `Seq(UnspecifiedDistribution)` and `node.mode === Partial` |
| 11 | "a Complete count over two partitions fails on the driver, and no other mode is built" | the `Complete` node over the two specs of test 1; and `VarkaFilterCountExec(dLess10, Seq(countAttr), scan, Final)` | `intercept[AssertionError] { node.execute() }`, its message contains `one partition, not 2`; `intercept[IllegalArgumentException]` for the `Final` one |

   Test 9 builds each plan by hand and applies
   `VarkaColumnarRule.postColumnarTransitions(plan)` under `withSQLConf(SQLConf.VARKA_ENABLED.key
   -> "true", SQLConf.VARKA_FUSED_COUNT_ENABLED.key -> "true")`. The partial aggregate is built
   as `AggUtils` builds it:

        private def partialCount(child: SparkPlan, function: Count = Count(Literal(1)),
            grouping: Seq[NamedExpression] = Nil, distinct: Boolean = false,
            filter: Option[Expression] = None): HashAggregateExec = {
          val ae = AggregateExpression(function, Partial, distinct, filter)
          HashAggregateExec(
            requiredChildDistributionExpressions = None,
            isStreaming = false,
            numShufflePartitions = None,
            groupingExpressions = grouping,
            aggregateExpressions = Seq(ae),
            aggregateAttributes = function.aggBufferAttributes,
            initialInputBufferOffset = 0,
            resultExpressions = grouping.map(_.toAttribute) ++ function.inputAggBufferAttributes,
            child = child)
        }

   With `scan = TestColumnarBatchPlan(Seq(BatchSpec("arrow", Seq(Seq(1, 50), Seq(7, 8)))),
   Seq(attrD, intAttr))` (two columns, `d` and `i`, two rows) and `rowFilter =
   VarkaFilterColumnarToRowExec(dLess10, scan)`, the cases:

| plan handed to the rule | the result |
| :--- | :--- |
| `partialCount(rowFilter)` | a `VarkaFilterCountExec` whose `child` is `scan` and whose `output` equals the aggregate's `output`; executed, its rows are `Seq(1L)` |
| `partialCount(ProjectExec(Nil, rowFilter))` | the same node |
| `partialCount(rowFilter.copy(narrowing = Some(Seq(attrD))))` | the same node |
| `partialCount(rowFilter, function = Count(intAttr))` | unchanged |
| `partialCount(rowFilter, function = Count(Literal(null, IntegerType)))` | unchanged |
| `partialCount(rowFilter, grouping = Seq(attrD))` | unchanged |
| `partialCount(rowFilter, distinct = true)` | unchanged |
| `partialCount(rowFilter, filter = Some(GreaterThan(intAttr, Literal(3))))` | unchanged |
| `partialCount(ProjectExec(Seq(Alias(Add(intAttr, Literal(1)), "j")()), rowFilter))` | unchanged |
| `partialCount(FilterExec(GreaterThan(intAttr, Literal(3)), rowFilter))` | unchanged |
| `partialCount(scan)` | unchanged |
| `partialCount(rowFilter)` with `VARKA_FUSED_COUNT_ENABLED` `"false"` | unchanged |

   "Unchanged" is asserted as `result.isInstanceOf[HashAggregateExec]` and
   `result.collectFirst { case c: VarkaFilterCountExec => c }.isEmpty`.
7. The new suite `VarkaFusedCountSuite.scala`, in the same folder,
   ScalaTest, `extends QueryTest with VarkaSharedSessions with VarkaTestWatchdog`, with two
   tests in this part and a third below; parts 1b and 2 add the rest. The trait gives three
   sessions over one context
   with an Arrow cache: `spark` (Varka off) and `varkaSpark` (Varka on, the warm-up off,
   adaptive execution off) are the two used here, and `cacheDatesBig(session, numRows, parts)`
   caches a view `varka_dates_big(d DATE, i INT)`: `d` is null on every 17th row and cycles
   over 365 days from 2020-01-01, `i` is the row number, and above one part the rows are
   repartitioned. The suite's constants and helpers:

        private val key = SQLConf.VARKA_FUSED_COUNT_ENABLED.key
        private val P = "d < DATE'2020-06-01'"
        private val Q = s"SELECT count(*) AS c FROM varka_dates_big WHERE $P"

        private def cacheBoth(numRows: Int, parts: Int = 1): Unit =
          Seq(spark, varkaSpark).foreach(cacheDatesBig(_, numRows, parts))

        /** Runs `body` with the fused count on in the Varka session. */
        private def withFusedCount[T](body: => T): T = {
          val saved = varkaSpark.conf.getOption(key)
          varkaSpark.conf.set(key, "true")
          try body finally saved match {
            case Some(v) => varkaSpark.conf.set(key, v)
            case None => varkaSpark.conf.unset(key)
          }
        }

        /** `query` on both sessions, the fused count on: equal answers, and the node or not. */
        private def counted(query: String, expectNode: Boolean): SparkPlan = withFusedCount {
          val actual = varkaSpark.sql(query)
          checkAnswer(actual, spark.sql(query).collect().toSeq)
          val plan = actual.queryExecution.executedPlan
          val node = collectFirst(plan) { case c: VarkaFilterCountExec => c }
          assert(node.isDefined === expectNode, plan.treeString)
          plan
        }

        private def metric(plan: SparkPlan, name: String): Long =
          collectFirst(plan) { case c: VarkaFilterCountExec => c.metrics(name).value }.get

        /** Whether, with the switch off, the Varka session plans `query` over the row filter node. */
        private def fusedToday(query: String): Boolean =
          collectFirst(varkaSpark.sql(query).queryExecution.executedPlan) {
            case f: VarkaFilterColumnarToRowExec => f
          }.isDefined

        /** The partitions of the cached fixture, which is what the count node emits a row for. */
        private def partitions: Int = varkaSpark.table("varka_dates_big").rdd.getNumPartitions

        /**
         * The rows of `cacheDatesBig` as a view of exactly one partition, on both sessions:
         * `coalesce(1)` declares a single partition and gives one also for no rows, so Spark
         * plans no exchange under the count and merges its two aggregates into one.
         */
        private def cacheOne(numRows: Int): Unit = Seq(spark, varkaSpark).foreach { session =>
          val rows = (0 until numRows).map { i =>
            val d = if (i % 17 == 0) null
            else java.sql.Date.valueOf(java.time.LocalDate.of(2020, 1, 1).plusDays(i % 365))
            (d, i)
          }
          session.createDataFrame(rows).toDF("d", "i").coalesce(1)
            .createOrReplaceTempView("varka_dates_big")
          session.catalog.cacheTable("varka_dates_big")
        }

        /** The mode of the count node in `plan`. */
        private def mode(plan: SparkPlan): AggregateMode =
          collectFirst(plan) { case c: VarkaFilterCountExec => c.mode }.get

   (`collectFirst` and `collect` are `AdaptiveSparkPlanHelper`'s, which the trait mixes in, so
   they read inside an adaptive plan too. `fusedToday` is for a predicate no coverage row shows
   fused: the test then asserts the node exactly where today's plan holds the row filter node.)

   **The two admission tests. Write them first and run them alone; if either fails, stop and
   report the tree it prints.** They hold fact F8 in each mode, with the switch off. The first,
   for a plan with a shuffle:

        test("with the switch off, a counted fused filter is a partial aggregate over an empty " +
            "projection over the row filter node") {
          cacheBoth(1024, parts = 4)
          assert(varkaSpark.conf.getOption(key).isEmpty)
          val plan = varkaSpark.sql(Q).queryExecution.executedPlan
          assert(collectFirst(plan) { case c: VarkaFilterCountExec => c }.isEmpty, plan.treeString)
          val partial = collectFirst(plan) {
            case a: BaseAggregateExec if a.aggregateExpressions.exists(_.mode == Partial) => a
          }.getOrElse(fail(plan.treeString))
          // The executed plan is the rule's result after CollapseCodegenStages wrapped it: the
          // aggregate and the projection are compiled together, and the row filter node, which
          // is not compiled, sits under an InputAdapter. The rule itself sees no such wrapper.
          partial.child match {
            case ProjectExec(Seq(), InputAdapter(_: VarkaFilterColumnarToRowExec)) =>
            case ProjectExec(Seq(), _: VarkaFilterColumnarToRowExec) =>
            case _ => fail("not the shape VARKA-651.md 3 (F8) plans for:\n" + plan.treeString)
          }
        }

   The second, for one partition, where Spark merges the two aggregates (F10, F11). Part 1
   plants nothing on this plan; the test holds the shape part 1b's arm is written against:

        test("with the switch off, one partition plans one Complete aggregate over the same " +
            "shape") {
          cacheOne(1024)
          assert(varkaSpark.conf.getOption(key).isEmpty)
          assert(partitions === 1)
          val plan = varkaSpark.sql(Q).queryExecution.executedPlan
          val aggregates = collect(plan) { case a: BaseAggregateExec => a }
          assert(aggregates.size === 1, plan.treeString)
          val agg = aggregates.head
          assert(agg.aggregateExpressions.map(_.mode) === Seq(Complete), plan.treeString)
          assert(agg.requiredChildDistributionExpressions === Some(Nil), plan.treeString)
          assert(agg.initialInputBufferOffset === 0 && agg.aggregateAttributes.size === 1)
          val count = agg.aggregateAttributes.head
          assert(count.dataType === LongType && !count.nullable)
          assert(agg.resultExpressions.forall(_.references.forall(_.exprId == count.exprId)))
          agg.child match {
            case ProjectExec(Seq(), InputAdapter(_: VarkaFilterColumnarToRowExec)) =>
            case ProjectExec(Seq(), _: VarkaFilterColumnarToRowExec) =>
            case _ => fail("not the shape VARKA-651.md 3 (F8, F10) plans for:\n" + plan.treeString)
          }
        }

   The third test of this part, "with the switch on, the count node answers as Spark does":
   `cacheBoth(1024, parts = 4)`, `val plan = counted(Q, expectNode = true)`, then
   `assert(mode(plan) === Partial)`, `assert(metric(plan, "numVarkaBatches") > 0)`,
   `assert(metric(plan, "numOutputRows") === partitions)` and that `metric(plan,
   "numCountedRows")` equals `spark.sql(Q).head().getLong(0)`.
8. The row's state, in `m8/PLAN.md`: the cell of row 651 opens with `**Filed** (`. Put
   `**Started** (part 1 merged as #<n>; left: part 1b, the Complete arm, and part 2, the SQL
   suite and the benchmark's arm). ` directly after `| 651 | `, before that `**Filed** (`, and
   delete nothing. Write the outcome of part 1 into this file's section 11.

**The commands, in order**, with the output that means done. Each starts a JVM and takes
minutes; export `JAVA_HOME` to a JDK 25 first.

    build/sbt sql/Test/compile                                            # ends in [success]
    build/sbt 'sql/testOnly *VarkaFusedCountSuite -- -z "with the switch off"'    # Tests: succeeded 2, failed 0
    build/sbt 'sql/testOnly *VarkaFilterCountExecSuite'                   # Tests: succeeded 11, failed 0
    build/sbt 'sql/testOnly *VarkaFusedCountSuite'                        # Tests: succeeded 3, failed 0
    build/sbt 'sql/testOnly *VarkaFilterExecSuite *VarkaColumnarToRowExecSuite'   # All tests passed.
    build/sbt 'catalyst/testOnly *SQLConfSuite' 'sql/testOnly *SQLConfSuite'      # All tests passed.
    python3 dev/varka_lint.py sql/catalyst/src/main/scala/org/apache/spark/sql/internal/SQLConf.scala   # no finding
    dev/lint-scala                                                        # passes
    python3 dev/varka_msg_check.py size                                   # under 1000

Then the gate and the closing flow of `sql/varka/ADDING_AN_EXPRESSION.md`, "Closing a row"
(`dev/varka_ops/pre-pr-slot.sh`, the review, `open-pr.sh`). The gate regenerates the generated
files; none should move, since no kernel and no coverage row changes. The configuration audit
(`SparkConfigBindingPolicySuite`, in sql/hive, run by CI) asks only that the key carries
`.withBindingPolicy`, which step 1 writes; nothing is added to any list for it.

**Done when** the fourteen tests of the two new suites pass, the two older suites pass
unchanged, and with the switch off no plan differs from master's.

**Not here.** No change to `VarkaFilterEvaluator`, `VarkaEvaluatorBase`, `VarkaSelectionBitmap`
or any kernel. No change of the default. No `count(col)`, no grouping, no count over a scan. No
edit of `VarkaSharedSessions` or of a benchmark (part 2). No emit option. No rule arm for the
merged aggregate (part 1b): the node's `Complete` mode is built and tested here, and planted
there.

### Part 1b: `[VARKA-651] Count a fused filter's rows where Spark merged the two aggregates`

Branch `varka-651b-count-complete`, from `origin/master` after part 1 has merged. Builder:
Sonnet-class.

**B0, the premises.**

    git grep -c 'mode: AggregateMode = Partial' -- sql/core/src/main                 # 1 file, count 1
    grep -c 'private def isPartialCountStar' sql/core/src/main/scala/org/apache/spark/sql/execution/VarkaColumnarRule.scala   # 1
    grep -c 'object CombineAdjacentAggregation' sql/core/src/main/scala/org/apache/spark/sql/execution/CombineAdjacentAggregation.scala   # 1
    grep -c 'in place of the partial aggregate, and no selected row is' sql/catalyst/src/main/scala/org/apache/spark/sql/internal/SQLConf.scala   # 1
    grep -c 'one partition plans one Complete aggregate' sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaFusedCountSuite.scala   # 1

**The steps.**

1. `VarkaColumnarRule.scala`: the `Complete` arm, `isCompleteCountStar` and the import of 4.5,
   "Part 1b". In the object's Scaladoc, extend part 1's sentence: the node stands for the
   partial aggregate, or under Spark's result projection for the one aggregate Spark merged the
   two into.
2. `SQLConf.scala`, the doc of `VARKA_FUSED_COUNT_ENABLED`: the two lines

            " VarkaFilterCount node in place of the partial aggregate, and no selected row is" +
            " converted for Spark's aggregate to count. The final aggregate is Spark's. A" +

   become

            " VarkaFilterCount node in place of the partial aggregate, or of the one aggregate" +
            " Spark merges the two phases into over a single partition, and no selected row is" +
            " converted for an aggregate to count. A" +

   and the row of `docs/sql-varka.md` that part 1 added takes the same sentence.
3. `VarkaFilterCountExecSuite`: a twelfth test, "the rule plants the node under the result
   projection on a Complete count(*) over the row filter node". The merged aggregate is built
   as `CombineAdjacentAggregation` leaves it (F10):

        private def completeCount(child: SparkPlan, function: Count = Count(Literal(1)),
            grouping: Seq[NamedExpression] = Nil, distinct: Boolean = false,
            filter: Option[Expression] = None,
            required: Option[Seq[Expression]] = Some(Nil),
            result: Attribute => NamedExpression = a => Alias(a, "c")()): HashAggregateExec = {
          val ae = AggregateExpression(function, Complete, distinct, filter)
          HashAggregateExec(
            requiredChildDistributionExpressions = required,
            isStreaming = false,
            numShufflePartitions = None,
            groupingExpressions = grouping,
            aggregateExpressions = Seq(ae),
            aggregateAttributes = Seq(ae.resultAttribute),
            initialInputBufferOffset = 0,
            resultExpressions = grouping.map(_.toAttribute) :+ result(ae.resultAttribute),
            child = child)
        }

   With `scan` and `rowFilter` of test 9 (one spec, so one partition; the rows of `d` are 1
   and 50, so one row passes `dLess10`), under the same two confs:

| plan handed to the rule | the result |
| :--- | :--- |
| `agg = completeCount(rowFilter)` | a `ProjectExec` whose `projectList` is `agg.resultExpressions` and whose child is a `VarkaFilterCountExec` with `mode === Complete`, `child === scan` and `output === agg.aggregateAttributes`; `result.output === agg.output`; executed (`result.execute().map(_.getLong(0)).collect()`), its rows are `Seq(1L)` |
| `completeCount(ProjectExec(Nil, rowFilter))` | the same shape, rows `Seq(1L)` |
| `completeCount(rowFilter, result = a => Alias(Add(a, Literal(1L)), "c")())` | the same shape, rows `Seq(2L)`: the arithmetic ran in the projection |
| `completeCount(rowFilter, function = Count(intAttr))` | unchanged |
| `completeCount(rowFilter, function = Count(Literal(null, IntegerType)))` | unchanged |
| `completeCount(rowFilter, grouping = Seq(attrD))` | unchanged |
| `completeCount(rowFilter, distinct = true)` | unchanged |
| `completeCount(rowFilter, filter = Some(GreaterThan(intAttr, Literal(3))))` | unchanged |
| `completeCount(rowFilter, required = None)` | unchanged |
| `completeCount(rowFilter, required = Some(Seq(attrD)))` | unchanged |
| `completeCount(rowFilter, result = _ => Alias(attrD, "c")())` | unchanged (a result that reads another attribute) |
| `completeCount(FilterExec(GreaterThan(intAttr, Literal(3)), rowFilter))` | unchanged |
| `completeCount(scan)` | unchanged |
| `completeCount(rowFilter)` with `VARKA_FUSED_COUNT_ENABLED` `"false"` | unchanged |

   "Unchanged" is asserted as in test 9.
4. `VarkaFusedCountSuite`: five more tests, and one constant, `private val combineKey =
   SQLConf.COMBINE_ADJACENT_AGGREGATION_ENABLED.key`. A second constant, `UNMATCHED`, is the
   `Seq` of the eight queries listed under part 2, step 2, "Test 5's queries": write it here,
   and part 2 reuses it. `R` is `"SELECT count(*) FROM varka_dates_big WHERE i >= 0"`.

| # | test name | what it runs | asserts beyond equal answers |
| ---: | :--- | :--- | :--- |
| 1 | "one partition plans the node in Complete mode and answers as Spark does" | `cacheOne(1024)`; `val plan = counted(Q, true)` | `mode(plan) === Complete`; `collect(plan) { case a: BaseAggregateExec => a }.isEmpty`; `numOutputRows` is 1; `numCountedRows` equals `spark.sql(Q).head().getLong(0)`; `numVarkaBatches > 0` |
| 2 | "an empty table and one row, in one partition" | `cacheOne(0)`: `counted(Q, true)`; `cacheOne(1)`: `counted(R, true)` and `counted(Q, true)` (the one row's `d` is null) | `mode` is `Complete` in each; the baseline's answers, read as `spark.sql(...).head().getLong(0)`, are 0, then 1, then 0 |
| 3 | "the answer is the same with Spark's merge rule on and off, and adaptive execution on and off" | for each fixture of `cacheOne(1024)` and `cacheBoth(1024, parts = 4)`, for `combine` and `aqe` each in `Seq("true", "false")`: `withBothConf(combineKey, combine) { withBothConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key, aqe) { counted(Q, true) } }` | the eight plans each hold the node; `mode` is `Complete` where the fixture is `cacheOne` and `combine` is `"true"`, and `Partial` in the six others; the eight answers of the Varka session are one value |
| 4 | "a HAVING, an alias, count(1) and arithmetic over the count keep the node in Complete mode" | `cacheOne(1024)`; `Q + " HAVING c > 0"`; `Q + " HAVING c < 0"`; `SELECT count(1) FROM varka_dates_big WHERE P`; `SELECT 4 * count(*) / 1024 FROM varka_dates_big WHERE P`; `SELECT count(*) + 1 FROM varka_dates_big WHERE P` | `counted(q, true)` and `mode` is `Complete` for each |
| 5 | "in one partition, what does not match plans as today" | `cacheOne(1024)`; each query of `UNMATCHED` | `counted(q, expectNode = false)` |

   In tests 1, 2 and 4 the fixture is a table of one partition, the switch is on and Spark's
   merge rule is at its default: if `counted` reports no node there, do not change the
   fixture or set a conf; stop and report the tree.
5. The surface driver's plan check, `DateSurfaceBenchmark.classifyPlan` (`sql/varka/bench`).
   It reads `EXPLAIN` text and answers `PARTIAL` where a `Filter` or a `Project` with entries
   sits above the first Varka node. In `Complete` mode Spark's result projection, `Project
   [count(1)#12L AS count(1)#5L]`, sits straight above the count node, so without this step
   the driver would report the fused count as partly fused. Beside `RESIDUAL_ABOVE` add

        private static final Pattern RESULT_PROJECT = Pattern.compile(
            "^[\\s+:|-]*(?:\\*\\(\\d+\\) )?Project \\[");

   and in `classifyPlan(String, Pattern)` replace the second loop by

        // The projection straight above a count node is the count's own result projection,
        // over the one row the node emits; no row of the filter crosses to it.
        boolean count = lines[varkaAt].contains("VarkaFilterCount");
        for (int i = 0; i < varkaAt; i++) {
          boolean result = count && i == varkaAt - 1 && RESULT_PROJECT.matcher(lines[i]).find();
          if (!result && RESIDUAL_ABOVE.matcher(lines[i]).find()) {
            return Fusion.PARTIAL;
          }
        }

   with one sentence on it in the method's Javadoc. `PlanCheckTest` gains three plans, each a
   `String.join("\n", ...)` as its `FUSED` is, and one `assertEquals` each:

        == Physical Plan ==
        *(1) Project [count(1)#12L AS count(1)#5L]
        +- VarkaFilterCount (isnotnull(d#311) AND (d#311 >= 2020-06-01)), [count(1)#12L], Complete
           +- Scan In-memory table varka_dates [d#311], [isnotnull(d#311)]

   reads `FUSED`; the same with the line `*(2) Filter (c#5L > 1)` put above the `Project` line
   reads `PARTIAL`; and

        == Physical Plan ==
        *(2) HashAggregate(keys=[], functions=[count(1)], output=[count(1)#5L])
        +- Exchange SinglePartition, ENSURE_REQUIREMENTS, [plan_id=7]
           +- VarkaFilterCount (isnotnull(d#311) AND (d#311 >= 2020-06-01)), [count#14L], Partial
              +- Scan In-memory table varka_dates [d#311], [isnotnull(d#311)]

   reads `FUSED`. Then `./build/mvn -q -f sql/varka/bench/pom.xml test` exits 0.
6. The row's state in `m8/PLAN.md`: in the `**Started** (...)` text part 1 put there, replace
   `part 1 merged as #<n>; left: part 1b, the Complete arm, and part 2` with `parts 1 and 1b
   merged as #<n> and #<m>; left: part 2`. This file's section 11.

**The commands, in order.**

    build/sbt sql/Test/compile                                            # ends in [success]
    build/sbt 'sql/testOnly *VarkaFilterCountExecSuite'                   # Tests: succeeded 12, failed 0
    build/sbt 'sql/testOnly *VarkaFusedCountSuite'                        # Tests: succeeded 8, failed 0
    SPARK_ANSI_SQL_MODE=false build/sbt 'sql/testOnly *VarkaFusedCountSuite'     # the same
    build/sbt 'sql/testOnly *VarkaFilterExecSuite *VarkaColumnarToRowExecSuite'   # All tests passed.
    build/sbt 'catalyst/testOnly *SQLConfSuite' 'sql/testOnly *SQLConfSuite'      # All tests passed.
    ./build/mvn -q -f sql/varka/bench/pom.xml test                        # exits 0
    dev/lint-scala                                                        # passes
    dev/lint-java                                                         # passes
    python3 dev/varka_msg_check.py size                                   # under 1000

Then the gate and the closing flow, as in part 1.

**Done when** the twenty tests of the two suites pass under both ANSI defaults, and with the
switch off no plan differs from master's. **Not here.** No change to the node, the evaluator
or the `Partial` arm. No conf that turns Spark's merge rule off outside test 3. No benchmark
(part 2).

### Part 2: `[VARKA-651] Hold the fused count to Spark's answers, and time its arm`

Branch `varka-651c-count-sessions`, from `origin/master` after parts 1 and 1b and row 650's
part 650a have merged.

**B0, the premises.**

    git grep -c 'class VarkaFilterCountExec' -- sql/core/src/main          # 1 file, count 1
    git grep -c 'private def isCompleteCountStar' -- sql/core/src/main     # 1 file, count 1
    git grep -c 'VARKA_FUSED_COUNT_ENABLED' -- sql/catalyst/src/main       # 1 file, count 2
    git ls-files 'sql/core/src/test/java/*/VarkaCountBenchmark.java' | wc -l    # 1
    git ls-files 'sql/core/benchmarks/VarkaCountBenchmark-*' | wc -l       # 4 or more (650a's results)
    grep -c '_: VarkaFilterExec | _: VarkaFilterColumnarToRowExec => true' sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaSharedSessions.scala   # 1

**The steps.**

1. `VarkaSharedSessions.isVarkaNode`: add `| _: VarkaFilterCountExec` to the pattern, and "five"
   for "four" in its comment. `VarkaSqlBenchmarkBase.isVarkaNode` (Java): add
   `|| p instanceof VarkaFilterCountExec` and its import.
2. `VarkaFusedCountSuite` (parts 1 and 1b made it, with eight tests and the helpers
   `cacheBoth`, `cacheOne`, `withFusedCount`, `counted`, `metric`, `mode` and `fusedToday`):
   ten more tests. The trait also gives `withAnsi(enabled)` and `withBothConf(key, value)`,
   which set a conf on `spark` and `varkaSpark` for a body. `Q` is `SELECT count(*) AS c FROM
   varka_dates_big WHERE P` and `P` is `d < DATE'2020-06-01'`. **Every test below starts with
   `cacheBoth(1024, parts = 4)`** unless its row names another fixture: four partitions put a
   shuffle under the count, so these run the `Partial` mode; part 1b's tests hold the
   `Complete` mode. No test below asserts the mode.

| # | test name | what it runs | asserts beyond equal answers |
| ---: | :--- | :--- | :--- |
| 1 | "the fused count answers as Spark does, in both ANSI modes, with nulls and without" | for `ansi` in `Seq(true, false)`: `withAnsi(ansi) { counted(Q, true); counted("SELECT count(*) FROM varka_dates_big WHERE i < 600", true) }` (`d` has nulls, `i` has none) | `numVarkaBatches > 0`; `numCountedRows` equals the answer |
| 2 | "nothing selected and everything selected" | `counted(..., true)` of `SELECT count(*) FROM varka_dates_big WHERE d < DATE'1900-01-01'` and of the same with `d > DATE'1900-01-01'` (the empty table and the one row are part 1b's test 2) | the baseline answers 0 for the first; `numCountedRows` equals the answer for each |
| 3 | "an empty partition gives its row, as the partial aggregate does" | `cacheBoth(5, parts = 8)`; `counted(Q, true)` | `partitions === 8`, and the node's `numOutputRows` equals it: five rows in eight partitions leave at least three empty; the answer is 4 |
| 4 | "a HAVING, count(1) and arithmetic over the count keep the node, above a shuffle" | `Q + " HAVING c > 0"`; `SELECT count(1) FROM varka_dates_big WHERE P`; `SELECT 4 * count(*) / 1024 FROM varka_dates_big WHERE P` | the node is planned in each |
| 5 | "what does not match plans as today, above a shuffle" | each query of `UNMATCHED`, below | no `VarkaFilterCountExec`; equal answers |
| 6 | "adaptive execution on and off" | under `withBothConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key, "true")` and then `"false"`: `counted(Q, true)` | the node is planned under both |
| 7 | "EXPLAIN names the node, its condition and its fused conjuncts" | `withFusedCount { varkaSpark.sql("EXPLAIN FORMATTED " + Q).head().getString(0) }` | the text contains `VarkaFilterCount`, `Condition :` and `Varka` |
| 8 | "a declined batch and a failed kernel are counted by the row path, and counted" | `counted(Q, true)` under `VarkaColumnarToRowExec.setDeclineKernelForTesting(true)`, then under `setFailKernelForTesting(true)`, each reset to `false` in `finally` | `numFallbackBatchesDeclined > 0`, then `numFallbackBatchesKernel > 0`; `numVarkaBatches == 0` in both |
| 9 | "while the kernel warms, the row path counts" | `varkaSpark.conf.set(SQLConf.VARKA_WARMUP_ENABLED.key, "true")`, reset to `"false"` in `finally` after `VarkaKernelWarmup.awaitIdle(150000)`; `counted("SELECT count(*) FROM varka_dates_big WHERE d < DATE'2020-03-17'", true)`, a predicate no other test uses | `numWarmupBatches + numVarkaBatches == numInputBatches` |
| 10 | "a value outside the kernel's range is counted by the row path, and an overflow raises as Spark's" | a view `varka_count_edge(d DATE, i INT)` of three rows, made on each of the two sessions by `session.sql("SELECT date_from_unix_date(v) AS d, i FROM VALUES (18700, 1), (3000000, 2), (18701, 2147483647) AS t(v, i)").createOrReplaceTempView("varka_count_edge")` and `session.catalog.cacheTable("varka_count_edge")`; `Y = "SELECT count(*) FROM varka_count_edge WHERE year(d) = 2021"`; `O = "SELECT count(*) FROM varka_count_edge WHERE i + 1 > 0"` | for `ansi` in `Seq(true, false)`: `withAnsi(ansi) { counted(Y, true) }`, and the baseline answers 2. Under `withAnsi(true)`, with the fused count on, each session's `collect()` of `O` throws, and the message of each exception or of its cause contains `ARITHMETIC_OVERFLOW`. Under `withAnsi(false)`, `counted(O, fusedToday(O))`, and the baseline answers 2 |

   Test 10's two predicates, and why each assertion is what it is. `year(d) = 2021` is the
   coverage row of that text (`sql/varka/coverage.json`: family "Predicates", form
   `predicate`, domain `unix_date(d) BETWEEN -719162 AND 2932896`), so the filter fuses and the
   node is asserted; day 3000000 is outside the domain, so the kernel declines that batch and
   the per-row path counts it. No metric is asserted there: tests 5 and 6 of
   `VarkaFilterCountExecSuite` and test 8 here hold the decline accounting with the hook. For
   `i + 1 > 0` no coverage row shows a comparison over an `INT` sum fused as a predicate (the
   row `i + 1` is a projection), so the node is asserted only where today's plan holds the row
   filter node (`fusedToday`); the answers and the error are asserted either way.

    grep -c '"sql" : "year(d) = 2021",' sql/varka/coverage.json      # 1

   Test 5's queries, the constant `UNMATCHED` of part 1b, each `counted(q, expectNode = false)`:

        SELECT count(i) FROM varka_dates_big WHERE d < DATE'2020-06-01'
        SELECT count(DISTINCT i) FROM varka_dates_big WHERE d < DATE'2020-06-01'
        SELECT d, count(*) FROM varka_dates_big WHERE d < DATE'2020-06-01' GROUP BY d
        SELECT count(*), max(i) FROM varka_dates_big WHERE d < DATE'2020-06-01'
        SELECT count(*) FILTER (WHERE i > 3) FROM varka_dates_big WHERE d < DATE'2020-06-01'
        SELECT count(*) FROM varka_dates_big
        SELECT count(*) FROM varka_dates_big WHERE pow(i, 2.0D) > 5.0D
        SELECT count(*) FROM (SELECT * FROM varka_dates_big WHERE d < DATE'2020-06-01' LIMIT 10)

   The seventh is a filter no family fuses (`pow`), so the plan keeps Spark's `FilterExec`. One
   more query is run in test 5 for its answer alone, with no assertion on its plan: `SELECT
   count(*) FROM varka_dates_big WHERE d < DATE'2020-06-01' AND pow(i, 2.0D) > 5.0D`, as
   `withFusedCount { checkAnswer(varkaSpark.sql(q), spark.sql(q).collect().toSeq) }`.

   No test asserts an exact batch count: the option matrix reruns the suite at other batch
   sizes (`VarkaMatrix.arrowBatchSize`) and under every emit option, and each assertion above
   holds under each. The partition counts are the fixture's, not the batch size's.
3. `VarkaCountBenchmark` (row 650's class): a third arm in each section, and its guard.

        private static final Map<String, String> FUSED_COUNT =
            Map.of(SQLConf.VARKA_FUSED_COUNT_ENABLED().key(), "true");

   In `runSection`, after the two arms of row 650 and inside the same `section` body:

        if (fused) {
          benchmark.addCase("varka (SIMD), fused count",
              i -> withConf(varka, FUSED_COUNT, () -> run(varka, query)));
        }

   and before the section is timed, inside row 650's `if (fused) { requireKernel(...); }`:

        withConf(varka, FUSED_COUNT, () -> {
          SparkPlan plan = varka.sql(query).queryExecution().executedPlan();
          Option<SparkPlan> node = plan.find(p -> p instanceof VarkaFilterCountExec);
          if (node.isEmpty()) {
            throw new IllegalStateException(
                "section '" + name + "' did not plan the fused count:\n" + plan.treeString());
          }
          requireKernel(varka, name, query);
        });

   So where the filter itself is not fused (the pi section at 128 bits, whose filter the
   two-lane refusal leaves to Spark; `VARKA-650.md` 4.3) there is no guard and no third arm: an
   arm named for a form must run that form. The guard's `Option<SparkPlan> node =
   plan.find(p -> ...)` is the form `VarkaFilterBenchmark.requireFused` uses (`Option` is
   `scala.Option`). The arm sets the switch and nothing else: Spark's merge rule stays at its
   default, and over the class's `local[1]` tables the count node runs in `Complete` mode
   (4.6). Say so in one sentence of the class Javadoc.
4. `sql/varka/skills/emitter-and-ir.md`, the file that holds the lesson on `columnarSibling`
   (`grep -l 'columnarSibling' sql/varka/skills/*.md` prints it): a new `##` section at its end,
   "A row node over a columnar child that is not a transition", of at most ten lines: why
   `VarkaFilterCountExec` is not a `ColumnarToRowTransition` (4.3) and why it overrides
   `producedAttributes`. Then `dev/varka_toc.py SKILLS.md --from sql/varka/skills`.
5. The row's state in `m8/PLAN.md`: replace the whole `**Started** (...). ` text the earlier
   parts put at the head of row 651's cell with `**Done** (parts 1, 1b and 2 merged as #<n>,
   #<m> and #<k>; the switch's default is false, its flip is row <r> if the owner files one). `
   and leave the `**Filed** (` text after it as it is; this file's section 11.

**The commands, in order.**

    build/sbt sql/Test/compile                                            # [success]
    build/sbt 'sql/testOnly *VarkaFusedCountSuite'                        # Tests: succeeded 18, failed 0
    SPARK_ANSI_SQL_MODE=false build/sbt 'sql/testOnly *VarkaFusedCountSuite'     # the same
    build/sbt 'sql/testOnly *VarkaFilterCountExecSuite *VarkaDifferentialSuite'  # All tests passed.
    build/sbt -batch "project sql" 'set Test/javaOptions += "-Dvarka.bench.dryRun=true"' \
      "Test/runMain org.apache.spark.sql.execution.benchmark.VarkaCountBenchmark"   # [success]
    dev/varka_toc.py --check SKILLS.md --from sql/varka/skills            # exit 0
    python3 dev/varka_msg_check.py size                                   # under 1000

The benchmark is not timed on the laptop. The dry run above builds the tables, runs every
guard, the fused count's among them, and times nothing. The timed run is row 652's dispatch.

Then the gate and the closing flow. The pull request's description states the test time the
new suite adds, read from the gate's log, against `sql/varka/ci/step-budget.tsv`.

**Done when** the suite's eighteen tests pass under both ANSI defaults, the benchmark plans the
fused count in each section its guard covers, and the option matrix run of the pull request is
green.

**Not here.** No flip of the default (part 3a). No change to a committed results file (row
652, part 3b). No edit of the tests of fact F9 beyond the two `isVarkaNode` helpers.

### Part 3a: `[VARKA-651] Count a fused filter's rows from its bitmap by default`

Branch `varka-651d-count-default`, from `origin/master` after part 2 has merged. Builder:
Sonnet-class. It may merge before or after row 652's run: the benchmark's arms keep their
names and what they time (step 3).

**B0, the premises.**

    grep -A14 'val VARKA_FUSED_COUNT_ENABLED' sql/catalyst/src/main/scala/org/apache/spark/sql/internal/SQLConf.scala | grep -c 'createWithDefault(false)'   # 1
    grep -c 'varka (SIMD), fused count' sql/core/src/test/java/org/apache/spark/sql/execution/benchmark/VarkaCountBenchmark.java   # 1
    git grep -c 'VarkaFilterCountExec' -- sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaSharedSessions.scala          # 1
    git grep -l 'VarkaFilterColumnarToRowExec' -- sql/core/src/test | wc -l     # 16: the 14 of 28 November
    # (the table of step 5 names each) plus VarkaFilterCountExecSuite and VarkaFusedCountSuite

**The steps.**

1. **The setting.** `SQLConf.scala`: `.createWithDefault(true)`, and the doc opens "When true,
   the default, an ungrouped count(*) ..." with one sentence added at its end: " Set it false
   to plan such a count as Spark's aggregate over the filter's rows." `docs/sql-varka.md`: the
   row's default reads `true` and takes the same text.
2. **The shared test helpers.** `VarkaFusedCountSuite`: the default is now on, so every place
   that relied on "unset means off" pins `"false"`. In the two admission tests replace
   `assert(varkaSpark.conf.getOption(key).isEmpty)` by wrapping the body in
   `withBothConf(key, "false") { ... }`, and name them "with the switch set false, ...". In
   `fusedToday` wrap the plan's reading in `withBothConf(key, "false") { ... }`. `withFusedCount`
   keeps setting `"true"`. Add one test, "with no setting, the count node is planned": after
   `cacheOne(1024)` and after `cacheBoth(1024, parts = 4)`, with no conf set, the executed plan
   of `Q` on `varkaSpark` holds a `VarkaFilterCountExec` and the answers of the two sessions
   are equal. The suite then runs both values of the switch, which is how the old plan stays
   covered (step 7).
3. **`VarkaCountBenchmark`.** The second arm must keep timing what row 650 measured, the row
   filter node under Spark's aggregate, and the third arm must run with no setting:

        private static final Map<String, String> ROW_COUNT =
            Map.of(SQLConf.VARKA_FUSED_COUNT_ENABLED().key(), "false");

   The second arm becomes `i -> withConf(varka, ROW_COUNT, () -> run(varka, query))`, with its
   name unchanged; the third arm becomes `i -> run(varka, query)`; the guard's
   `withConf(varka, FUSED_COUNT, ...)` wrapper goes, its body staying; `fused` is read from a
   plan made under `ROW_COUNT` (it asks whether the filter itself fuses); `FUSED_COUNT` is
   deleted. The class Javadoc says the second arm pins the setting off, to keep the before of
   the before and after.
4. **The other benchmarks' guards.** `VarkaInExpressionBenchmark` carries its own list of the
   Varka node classes and its guard (`requireFused`, line 167) checks a counted query, so with
   the default on it would report "did not fuse": add `|| p instanceof VarkaFilterCountExec`
   (and the import) to `VarkaInExpressionBenchmark.isVarkaNode`. Add the same alternative to
   the pattern of `VarkaFilterBenchmark.requireFused` for consistency only: it is called for the
   filter and stacked queries (lines 184 and 191), not for a counted one, so it would not fail
   without the edit. The benchmark base's `fusion` and `requireBaseline` also read
   `isVarkaNode` (taught the count node in part 2); no counted section exists in the decimal
   and integral-division baseline classes, so no baseline guard changes.
   The others need no edit: `VarkaLongLaneThroughputBenchmark`,
   `VarkaFilterNarrowingBenchmark`, `VarkaTwoLaneLongBenchmark` and `VarkaBooleanOutputBenchmark`
   ask `VarkaSqlBenchmarkBase.holdsVarkaNode`, which part 2 taught the count node, and
   `VarkaNarrowingBenchmark.requireShape` is called for its two projecting queries only, not
   for its counted one. No section title changes, and no class pins the setting: a benchmark
   times what a user gets. Then the dry run of `VarkaCountBenchmark` and of the five classes of
   part 3b's table, as part 2's command with the class name replaced, each ending in
   `[success]`; a guard that fails names the class to report.
5. **The tests of fact F9.** Run `build/sbt 'sql/testOnly *Varka*'` with the default on, and
   apply this table to each failure. The rule behind it: a test about the filter (its
   condition, its narrowing, its split, its metrics) pins the setting off, so that it keeps
   testing the filter; a test about a counted query's answer keeps the default and, where it
   names a node, expects the count node. Pin with
   `withBothConf(SQLConf.VARKA_FUSED_COUNT_ENABLED.key, "false") { ... }` in a suite on
   `VarkaSharedSessions`, and with `withSQLConf(... -> "false")`
   in a suite on `SharedSparkSession`. A failure in a suite the table does not name, or one
   the rule does not decide: stop and report it; change no assertion to make it pass.

| suite (mentions of the row filter node) | its counted queries | what to do |
| :--- | :--- | :--- |
| `VarkaFilterExecSuite` (20) | none through SQL: it builds the node by hand | nothing; if a test fails, pin off |
| `VarkaFilterRaiseOrderSuite` (3), `VarkaLongLaneSuite` (1), `VarkaTimeArithmeticSuite` (1), `VarkaPartialDeclineSuite` (2), `VarkaDecimalReadPathSuite` (1) | none: their queries select columns | nothing; if a test fails, pin off |
| `VarkaDecimalBaselineQueriesSuite` (4) | `SELECT count(*) AS c ...` read through its `fusion(...)` helper, which takes the filter's condition and `wholeCondition` off the row filter node | pin off (required, not optional), around the suite's helper that plans the query: the tests are about which conjuncts fuse, and lines 425 and 452 assert `fusion(count query) === Optional.empty()`, which a one-partition `Complete` plan (`Project` over the count node) would read as WHOLE once `isVarkaNode` knows the count node |
| `VarkaDifferentialSuite` (2) | the two mentions are on `SELECT d` queries; its counted queries go through `checkDifferential(..., expectFused = true)` and `isVarkaNode` | nothing: part 2 taught `isVarkaNode` the count node, and the node carries `numVarkaBatches` |
| `VarkaSharedSessions` (1) | the helper itself | done in part 2 |
| every suite that reads `isVarkaNode` or `assertKernelsRan` | counted queries here and there | nothing, for the same reason |
| `VarkaFusedCountSuite`, `VarkaFilterCountExecSuite` | all | step 2; the node suite sets the switch itself |

6. **Documents and plan text that show a counted filter's plan.** No SQL golden file names a
   Varka node (`git grep -il varka -- sql/core/src/test/resources/sql-tests` prints nothing).
   `docs/sql-varka.md`, the section that draws `-> VarkaFilterColumnarToRowExec(condition,
   child)`: add after its paragraph three sentences on the count node: an ungrouped `count(*)`
   directly over that node plans `VarkaFilterCountExec` in its place, in the partial
   aggregate's place or, over one partition, in the merged aggregate's under Spark's result
   projection; the setting that turns it off. The surface driver's plan check learned the
   count node's plan in part 1b (step 5), so nothing changes there.
7. **The option matrix.** Its configurations are emit options only: they come from
   `VarkaMatrixRuns.configurations` (through `VarkaMatrix.configurations`), and no SQL conf
   has a configuration there, this one included (4.1). So no configuration is added. The old
   plan stays covered by `VarkaFusedCountSuite`, which the matrix reruns under every
   configuration and which now runs both values of the switch (step 2), and by every test this
   part pins off (step 5).
8. The row's state in `m8/PLAN.md`: in the `**Done** (...)` text of part 2, replace `the
   switch's default is false, its flip is row <r> if the owner files one` with `the default
   flipped in part 3a, #<p>; left: part 3b, the regenerated benchmark files`, and change
   `**Done**` to `**Started**` until part 3b merges. This file's section 11.

**The commands, in order.**

    build/sbt sql/Test/compile                                            # [success]
    build/sbt 'sql/testOnly *VarkaFusedCountSuite *VarkaFilterCountExecSuite'    # Tests: succeeded 31, failed 0
    build/sbt 'sql/testOnly *Varka*'                                      # All tests passed.
    SPARK_ANSI_SQL_MODE=false build/sbt 'sql/testOnly *VarkaFusedCountSuite'     # Tests: succeeded 19, failed 0
    build/sbt 'catalyst/testOnly *SQLConfSuite' 'sql/testOnly *SQLConfSuite'      # All tests passed.
    python3 dev/varka_msg_check.py size                                   # under 1000

Then the gate, the closing flow, and the option matrix run of the pull request, green.

**Done when**, with no setting, the pi query and both `COUNT(*)` sections of
`VarkaCountBenchmark` plan the fused count (its dry run passes with the third arm unset), the
Varka suites pass with the default on, and `VarkaFusedCountSuite` passes with the switch at
both values. **Not here.** No results file (part 3b). No removal of the switch or of the row
filter node's path: the old arm stays.

### Part 3b: `[VARKA-651] Regenerate the counted sections after the default flipped`

A dispatch and a results pull request, no code. Branch `varka-651e-count-results`, from
`origin/master` after part 3a has merged. Builder: Sonnet-class.

**Which committed sections change meaning.** A section whose Varka arm times `SELECT
count(*) ... WHERE <a fused filter>` with no grouping now times the count node. Read on
master `6dafb8bd2f6` (`grep -n -i 'count(\*)'` over the benchmark sources):

| class | sections or cases whose Varka arm now times the fused count | its committed files (`sql/core/benchmarks/`) |
| :--- | :--- | :--- |
| `VarkaFilterBenchmark` | `COUNT(*) over the filter, 15% selected`; `COUNT(*) over the filter, 85% selected` | `-jdk25-results.txt` |
| `VarkaNarrowingBenchmark` | `COUNT(*) WHERE d < d2 (no read-back), 10% selected`, and the same at `70%` and `100%` | `-jdk25-results.txt`, `-jdk25-128bit-results.txt`, `-jdk25-provenance.txt` |
| `VarkaFilterNarrowingBenchmark` | case `E one column, no output (count)` | `-jdk25-results.txt`, `-jdk25-128bit-results.txt`, `-jdk25-band.txt`, `-jdk25-provenance.txt` |
| `VarkaInExpressionBenchmark` | `filter IN anchor, 5 literals (fused since VARKA-21)`; the anchors of 50, 200 and 500 literals are unfused on both arms and do not move | `-jdk25-results.txt` |
| `VarkaLongLaneThroughputBenchmark` | cases `compare int64, output none (count)` and `compare int32, output none (count)` | `-jdk25-results.txt`, `-jdk25-128bit-results.txt`, `-jdk25-256bit-results.txt`, `-jdk25-provenance.txt` |
| `VarkaCountBenchmark` | none: its second arm pins the setting off and its third is the fused count (part 3a, step 3) | row 652's files; not rerun here |
| the surface driver (`sql/varka/bench`) | the shape "filter, counted" of every filter entry, in the files of the arm with Varka on | `sql/varka/bench/benchmarks/*-varka-*-results.txt` |

Not affected: `CodegenRecompileBenchmark` (Varka is off in it); `VarkaSchemaWidthBenchmark`,
`VarkaThroughputBenchmark` and the cache warm-ups of every class (a `count(*)` with no
filter, which the node never matches).

**The steps.**

1. For each of the first five classes, read the `cpu:` line of its provenance file, or the
   CPU line at the head of its results file where it has none. On master `6dafb8bd2f6` all
   five name the laptop (`AMD Ryzen AI 9 HX PRO 370`), so step 2 is the one that applies; this
   step is for a class whose file has since become a runner file. Where the line names `AMD
   EPYC 7763`, dispatch the class on master and replace its files with the run's; add the
   128-bit run where a `-128bit` file is committed:

        gh workflow run benchmark.yml -R vecbricks/varka --ref master \
          -f class=<Class> -f expected-cpu="AMD EPYC 7763"
        gh workflow run benchmark.yml -R vecbricks/varka --ref master \
          -f class=<Class> -f expected-cpu="AMD EPYC 7763" \
          -f extra-java-options="-XX:MaxVectorSize=16"

   The whole class is rerun, not the moved sections alone: numbers compare within a run. A
   run that lands on another CPU is retried as `VARKA-650.md` 5 (part 650a, step 5) says.
2. Where the line names the laptop's CPU, the file cannot be regenerated by a builder (a
   committed file is never timed on the laptop by an agent). Dispatch the same two commands,
   and commit the run as the class's runner files, under the names
   `<Class>-jdk25-runner-epyc7763-results.txt` and `-provenance.txt` (and the `-128bit` pair),
   beside the laptop file, which stays. List each such laptop file and its moved sections in
   row 651's note, as "stale in these sections since the default flipped; the runner files
   hold them" (question 2 of section 12).
3. The surface files are not rerun here. Every surface is regenerated at the milestone's close
   with its user-facing numbers; say in row 651's note that the "filter, counted" shape of the
   committed Varka arm predates the flip until then.
4. Requote: `python3 dev/varka_bench_diff.py --git HEAD <each replaced results file>
   --requote`, and correct each document line it lists. Then `python3
   dev/varka_quote_check.py sql/varka/plans/m8/PLAN.md` (0 orphans) and `python3
   dev/varka_plan_lint.py --offline` (0 findings).
5. Row 651's state: `**Done** (parts 1, 1b, 2, 3a and 3b merged as ...; the default is true;
   regenerated: <the classes and run ids>; stale until the close: <the laptop files and the
   surfaces>)`. This file's section 11.

**Done when** each of the five classes has runner files from a run after the flip, each from
an EPYC 7763, and the row's note names what is still stale and why. **Not here:** no
code; no laptop run; no surface run; no edit of a results file by hand.

## 6. Tests, proofs, fuzzing and coverage

| suite | what it holds | the failure only it catches |
| :--- | :--- | :--- |
| `VarkaFilterCountExecSuite` (11 tests in part 1, 1 in part 1b) | the node over hand-built batches, in both modes; the rule over hand-built plans | a count wrong at a word boundary or for a batch off the kernel path; a `Complete` count over several partitions; the rule matching a shape of 4.2's two tables |
| `VarkaFusedCountSuite` (3 tests in part 1, 5 in part 1b, 10 in part 2) | plans and answers against the session with Varka off | a wrong answer through the real planner: the attribute the final aggregate or the result projection binds, an empty partition, an empty table in one partition, Spark's merge rule on and off, adaptive execution |
| `VarkaSelectionBitmapSuite` (exists) | `countSet` over stale tails | unchanged; the count's arithmetic is its |

No proof file: the row adds no lowering. No IR node, so no registry suite and no fuzzer
production. A wrong count is a wrong answer the ghost fallback cannot catch, which is why part 2
is a differential suite and why the switch ships off.

**The model check.** The accounting of 4.4 was run as a model in Python against Spark's
definition (count the rows where the predicate is true, one partial row per partition, summed):
random partitions and batches of lengths around the word boundaries, with nulls, each batch on
a random path (kernel, warm-up, declined, a kernel path that fails before the add, not Arrow),
over a reused buffer with a stale tail. Fix round 2 extended it to the `Complete` mode, over
the same generator and three result expressions (`count(*)`, `count(*) + 1`, `4 * count(*) /
1024`, the last as an exact fraction): for a child of one partition the node's row under the
projection equals the merged aggregate's row and equals what `Final` over `Partial` gives with
Spark's merge rule off; for a child of no partition neither emits a row; for a child of
several the assertion of 4.3 fires before any row. The script is
`scratch-count-r2/count_model.py` in the ops home's `m8-row-plans`; its last line reads
"count_model: 4000 random plans, every partial count equal to Spark's; Complete mode: 1316
one-partition plans equal to Spark's merged aggregate and to Final over Partial, 691 of no
partition with no row, 1993 of several partitions stopped by the assertion; 0 mismatches". It
models the logic, not the JVM code and not the planner.

## 7. Measurement

The row adds no benchmark class; it adds the third arm of `VarkaCountBenchmark` (part 2), and
row 652 runs it on an AMD EPYC 7763 at 256 and at 128 bits. The comparison is inside one file:
the arm `varka (SIMD)` (the row filter node under Spark's aggregate, today's plan) against the
arm `varka (SIMD), fused count`, with `baseline (Janino)` as the control.

**Predictions, registered before any run.** Row 652 scores them.

1. At 256 bits, in the section with 85% selected, the fused count is faster than the baseline:
   its `Relative` reads above 1.0X. (Today's plan reads 0.8X there on the laptop.) Yes or no.
2. In each section the fused count's rate is at least the rate of `varka (SIMD)`. Yes or no.
3. The fused count's rate in the 15% and the 85% sections differs by less than a quarter of the
   larger: the count no longer depends on how many rows pass. Yes or no.
4. The pi section's fused count is at least twice the baseline's rate at 256 bits. Yes or no.

No test-time prediction beyond the gate's own table: the two suites hold 30 tests over small
fixtures.

## 8. Who builds it

| part | counted lines (estimate) | builder | starts from | waits on | why |
| :--- | ---: | :--- | :--- | :--- | :--- |
| 1 | about 715: conf 20, node and evaluator 215, rule 45, the two suites 420, docs and plan 15 | Sonnet-class (since the lead's admission run of 28 November, section 8) | master | row 650 need not be merged | facts F8, F10 and F11 are read from code and not run; if an admission test fails, the match of 4.2 and 4.5 is redesigned against the tree the test prints, with nobody to ask |
| 1b | about 285: rule 40, conf doc and docs 6, the node suite's test 80, the SQL suite's five tests 105, the surface driver's plan check and its test 45, plan 9 | Sonnet-class | master | part 1 | the arm, the method and every plan and query of its tests are written out; the node's `Complete` mode exists and is tested |
| 2 | about 400: suite 300, benchmark 40, helpers 5, skills and plan 55 | Sonnet-class | master | parts 1 and 1b, and row 650's part 650a | every query, fixture and assertion is listed; the node and the rule exist |
| 3a | about 150: conf and docs 20, the suite 45, `VarkaCountBenchmark` 20, two benchmark guards 10, pinned tests 40, plan 15 | Sonnet-class | master | part 2 | each edit is named; the tests follow one rule and a table, and an undecided failure stops the builder |
| 3b | results files only, which do not count; plan text 15 | Sonnet-class | master | part 3a | dispatches, a table of classes and a rule by the file's CPU line |

The cut: parts 1 and 1b as one pull request would be about 1000 counted lines, so the
`Complete` rule arm and its tests are part 1b. The benchmark's third arm (part 2, step 3)
waits on part 1b, not only on part 1: over the `local[1]` tables only the `Complete` arm plants
the node. Row 652 waits on part 2.

**The lead's first command.** Part 1 becomes Sonnet-class once its two admission tests have
been run and have passed. On a scratch branch, paste into `VarkaDifferentialSuite` (which has
the same trait) the two tests of part 1's step 7 and the suite's `key`, `P`, `Q`, `cacheBoth`,
`cacheOne` and `partitions`, with `key` set to a string no conf has and the two
`VarkaFilterCountExec` lines removed; add the imports the compiler asks for; then run

    build/sbt 'sql/testOnly *VarkaDifferentialSuite -- -z "with the switch off"'    # Tests: succeeded 2, failed 0

That is ten minutes of the lead's time. If the first fails, the `Partial` match is redesigned;
if the second fails, the `Complete` match is, against the tree it prints.

**Run on 28 November 2026.** The lead ran both admission tests on master `b646e6bb012`, as a
scratch suite `VarkaFusedCountAdmissionSuite` with the two tests of part 1's step 7 and their
helpers (the switch's key set to a name no conf has, the two `VarkaFilterCountExec` lines left
out): `Tests: succeeded 2, failed 0`, in 242 s. So facts F8, F10 and F11 hold on the real
planner, in both modes, and part 1 is Sonnet-class. The builder still writes and runs the two
tests first, as step 7 says: a later sync of Spark could change the shapes.

## 9. Risks

1. **The planned shape differs from fact F8 or F10.** Held by part 1's two admission tests,
   written and run first.
2. **A wrong count is silent.** Held by the switch (off), by part 2's differential tests and by
   the model check.
3. **Adaptive execution wraps the cached scan in a `TableCacheQueryStageExec`.** The node's
   child is then that stage; `VarkaColumnarRule.readsArrowCache` already walks it, and the row
   filter node runs over it today. Held by part 2's test 6.
4. **A consumer strips or re-wraps the node.** It is not a transition, so nothing strips it. A
   second pass of the transition rule over a plan that holds it would put a `ColumnarToRowExec`
   under it; Spark applies that rule once to a stage (fact F7). If a later sync changes that,
   test 6 fails with `does not produce rows` errors from the child.
5. **The two widened modifiers leak.** `private[sql]` keeps both inside Spark's SQL packages.
6. **A reader of fact F9 breaks.** Not while the switch is off. Part 3a runs every sql/core
   Varka suite with the default on and applies its table; a failure the table does not decide
   stops the builder.
7. **A `Complete` count over more than one partition.** It cannot be planned (F11). If a
   node below ever declares one partition and runs several, the assertion in `doExecute` stops
   the query on the driver; no wrong row is emitted. Held by test 11 of
   `VarkaFilterCountExecSuite` and by the model check.
8. **A later rule moves the planted projection.** `ProjectExec` over the count node is plain
   Spark; `CollapseCodegenStages` compiles it over an `InputAdapter`. The rule's own
   projection arm leaves it (4.5). Held by part 1b's tests 1 and 3, the second under adaptive
   execution.

**What only a JVM settles, with the command for the lead.**

- F8 in both modes, and that F10's merge happens over the `coalesce(1)` fixture: the lead's
  first command (section 8) before part 1 is built, and `build/sbt 'sql/testOnly
  *VarkaFusedCountSuite -- -z "with the switch off"'` (part 1, step 7) in it.
- That the planted `ProjectExec` over the count node survives to the executed plan, with
  adaptive execution on and off, and that `HashAggregateExec` and the node agree on an empty
  one-partition table: `build/sbt 'sql/testOnly *VarkaFusedCountSuite -- -z "one partition"
  -z "merge rule"'` (part 1b, tests 1, 2, 3 and 5).
- That `date_from_unix_date(3000000)` caches and that `year(d) = 2021` over it answers 2 on
  both sessions: `build/sbt 'sql/testOnly *VarkaFusedCountSuite -- -z "outside the kernel"'`
  (part 2, test 10).
- That the pi predicate fuses whole at 256 bits:
  `dev/varka_emit.sh "x * x + y * y < 1" --columns x:double,y:double`; done when the entry's
  line ends `FusedOutput(0)`.
- The plan under adaptive execution: part 2's test 6.

## 10. The house rules that bind this row

Pointers, not copies:

- Answers equal vanilla Spark's bit for bit, under both ANSI settings: `sql/varka/AGENTS.md`,
  "House rules that bite here specifically", the last bullet (the ghost fallback), and the root
  `AGENTS.md`, "Build and Test" (assert both modes).
- A new behaviour ships behind a switch whose default flips last, and the losing arm stays:
  `sql/varka/skills/benchmarking.md`, "Flipping a default silently retires every A/B built on
  `DEFAULTS`".
- A Varka conf carries `.withBindingPolicy`: `dev/varka_lint.py`, rule `binding-policy`.
- `SparkPlan` subclasses and rules are Scala, sql/core suites are ScalaTest, a benchmark is
  Java: `sql/varka/AGENTS.md`, "Java code", "What stays as it is".
- No `TODO`, and a comment describes the code as it is: `sql/varka/AGENTS.md`, "Open work is
  recorded".
- A pull request stays under 1000 counted lines, is reviewed by its author first, and is
  pushed only by the scripts of `dev/varka_ops/`: `sql/varka/AGENTS.md`, "Pull requests".
- Test hooks (`setDeclineKernelForTesting`, `setFailKernelForTesting`) are static and reset in
  a `finally`: the comments above them in `VarkaColumnarToRowExec.scala`.

Do not: change the default before part 3a; touch a kernel or the evaluator base; make the node a
transition; match on the final aggregate; look through a projection that has entries; turn Spark's
`CombineAdjacentAggregation` off to make a plan match; weaken or skip a test that fails.

## 11. Outcome

To be written when the row closes.

## 12. Questions for the owner

**Answered by the owner on 28 November 2026, now decisions.**

- *Does the switch's default flip in milestone 8?* Yes: "flip it in this milestone". The node
  lands behind the switch with the default off (parts 1, 1b and 2), and the last parts of this
  row flip it (3a) and regenerate the files (3b). Section 4.1.
- *Should a `count(*)` under a `HAVING` use the fused count?* Yes, matched, with a test. The
  question as it was put follows, for the record.

**Answered on 28 November 2026, (a)**, the question as it was put:

1. **The laptop-measured files whose counted sections the flip moves** (part 3b, step 2: the
   committed files of all five classes of its table; each one's CPU line is the laptop's). A
   builder
   may not time a committed file on the laptop. Options: (a) the builder adds runner files for
   the class beside the laptop file and the row's note names the laptop file's moved sections
   as stale; (b) the lead reruns those classes on the laptop when 3a has merged; (c) the
   class pins the setting off in its counted sections, so the laptop file stays true and
   measures the old plan. Recommendation: (a), then (b) at the lead's convenience: it leaves
   no number untraceable and blocks no builder. **Answered: (a).** The same default
   leaves the surface files to the milestone's close (part 3b, step 3); say if the surfaces
   must be rerun inside this row.

**The `HAVING` question as it was put (answered: (a)).**

2. **Should a `count(*)` under a `HAVING` use the fused count?** The query: `SELECT count(*)
   AS c FROM t WHERE p HAVING c > 1`. What the rule sees: Spark plans the `HAVING` as a filter
   node above the aggregate. The aggregate under it is the very node the query without the
   `HAVING` plans, and the rule looks at an aggregate and what is below it, so it cannot tell
   the two queries apart. Under adaptive execution with a shuffle, the `HAVING` is not even in
   the stage the rule is handed. Options: (a) fused: the count comes from the filter's bitmap
   and Spark's `HAVING` filter runs above it unchanged, tested for equal answers both where it
   keeps the row and where it drops it (part 1b, test 4; part 2, test 4); (b) not fused: the
   rule would have to look above the aggregate, which it can do only where no shuffle is
   between, so the same query would be fused or not by its table's partition count.
   Recommendation: (a): the `HAVING` reads the finished count and no row of the table, so it
   cannot change what is counted. **Default applied: (a).**

Decided in the text, no longer asked: the node's name (4.3); that the fused count also
replaces the aggregate Spark merges over one partition, by a second mode of the one node and
not by turning Spark's rule off (4.2, 4.3; fix round 2, on the lead's instruction).
