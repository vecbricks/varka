# Task 185: the schema-width cliff, and the cache Varka cannot see

## 1. Where this came from

`PLAN_MILESTONE_6.md` 2.11 opened this task to establish where vanilla Spark gives up whole-stage
codegen for a wide schema, `spark.sql.codegen.maxFields` (internal, default 100), and registered
a prediction for Varka's side: immunity, because a kernel reads the columns a projection names
rather than the width of the schema around them. Section 2.6 called a wide-schema shape where
Varka also declines "the more interesting outcome".

Task 188's census found that outcome in the source (`PLAN_TASK_188.md` G2 and G3). The same
field-count test that takes an operator out of whole-stage codegen also decides whether a scan
produces columnar batches at all, and for the cache it counts the whole cached relation, not
the columns a query reads. Varka rewrites a projection or a filter only over a columnar child. So
caching a table of more than a hundred columns and projecting one date column from it gives Varka
nothing to fuse, and nothing records why.

## 2. The admission check, from the source

At master `efe3017de8a`:

* **G2, the operator.** `WholeStageCodegenExec.isTooManyFields` counts nested leaf fields; an
  operator whose output, or any child's output, has more than `maxFields` is not a member of a
  whole-stage stage (`CollapseCodegenStages`). Nothing is logged; the plan only loses its `*(n)`.
* **G3, the source.** `InMemoryTableScanExec.supportsColumnar` is
  `cacheVectorizedReaderEnabled && !isTooManyFields(conf, relation.schema) &&
  serializer.supportsColumnarOutput(relation.schema)`: the full cached schema. File scans gate
  their vectorized reader the same way (`FileSourceScanExec`, and the Parquet and ORC v2 reader
  factories), on the schema they read.
* **Varka.** `VarkaColumnarRule` rewrites a projection or a filter only `if (child.supportsColumnar)`,
  and otherwise returns the node unchanged without a reason. `ArrowCachedBatchSerializer` itself
  supports columnar output for every schema, so the only thing standing between Varka and a wide
  cache is the field count.

None of this is measured yet: it is read from the source, and the reproducers below are what
make it a claim.

## 3. The design

### 3.1 The reproducers

`VarkaSchemaWidthSuite` in `sql/core`, over Arrow-cached tables built for the test:

* **G2, vanilla.** A projection with 100 and with 101 output columns over a narrow source, and a
  struct column that crosses 100 only through its leaves: the plan's whole-stage stages, read from
  the executed plan, with the projection a member at 100 and not at 101.
* **G3, vanilla and Varka.** A cached table of 100 and of 101 columns, a date among them, and
  `SELECT date_add(d, 1) FROM t`: `InMemoryTableScanExec.supportsColumnar` true at 100 and false at
  101, and the Varka arm's plan with a Varka node at 100 and none at 101. This is the test the fix
  in 3.2 turns around.

### 3.2 The fix: Varka reads the Arrow cache as batches whatever its width

When the child of a projection or a filter Varka could fuse is an `InMemoryTableScanExec` whose
serializer is `ArrowCachedBatchSerializer`, and it does not support columnar output only because
of the field count, the rule substitutes a Varka-owned scan that reads the same cached batches as
columnar batches, of the attributes the query reads, through the serializer's own
`convertCachedBatchToColumnarBatch`. The cached data are already Arrow; the field count guards
whole-stage codegen's consumption of them, which the Varka node does not do. The substitute keeps
the scan's pruning predicates and its metrics, and changes nothing where Varka does not fire.

What stays as it is: a wide cache under another serializer, and the file scans, whose columnar
output is a vectorized reader Varka's filter could not read anyway (it needs Arrow vectors).

### 3.3 The reason, where the fix does not reach

A projection or filter Varka could have fused over a scan that is not columnar records why: the
fusion report and the fallback log say "the scan does not produce batches: its schema has more
than spark.sql.codegen.maxFields fields", or the plain reason where it is something else.

### 3.4 Upstream, the owner's call

`InMemoryTableScanExec.supportsColumnar` counts the whole cached relation, where the columns a
query reads are what its consumers see; a wide cache read through a narrow projection loses
columnar output it could have had, in vanilla Spark too. That is worth a JIRA of its own, with the
reproducer of 3.1 as its evidence; whether to file it is the owner's decision, as for the other
upstream items of row 204.

## 4. Files

| file | what |
|---|---|
| `VarkaSchemaWidthSuite.scala` | the reproducers, and the fix's tests |
| `VarkaColumnarRule.scala` | the substitution, and the recorded reason |
| a Varka columnar cache scan | reads the Arrow cache as batches of the query's attributes |
| `VarkaFusionReport.scala` | the reason in the report |
| `PLAN_MILESTONE_6.md` | row 185 |

## 5. Tests, and what each is for

* The reproducers of 3.1, which pin vanilla's behaviour at 100 and 101 fields.
* A differential over the wide cache: the Varka arm through the substitute scan returns the row
  engine's rows, nulls included, with the kernel having run.
* The substitute changes nothing below the width: at 100 columns the plan is the one it is today.
* The reason is in the report where the fix does not apply: a wide cache under the default
  serializer.

## 6. Predictions, registered before the build

1. **Vanilla at 101 columns loses whole-stage codegen for the projection**, and at 101 cached
   columns the cache scan produces rows, as the source says.
2. **Varka at 101 cached columns has no node today**, and nothing records why.
3. **With the fix, Varka fuses at 101 columns as it does at 100**, and its time per row is the same
   at both widths, since the kernel reads one column either way.

## 7. Sequencing

1. The reproducers, committed on their own, as the baseline of the change.
2. The substitute scan and the recorded reason, with their tests.
3. The predictions scored in 8, and the upstream JIRA drafted for the owner.

## 8. Outcome

### 8.1 Step 1: the baseline, 25 September 2026

`VarkaSchemaWidthSuite` caches a table of a date and `n - 1` int columns, nulls in both, under the
Arrow serializer with the vectorized reader on, and asks for `date_add(d, 1)`:

* **Prediction 1 held for the cache.** `InMemoryTableScanExec.supportsColumnar` is true at 100
  fields and false at 101. The projection half of prediction 1, G2, is already pinned by
  `VarkaCodegenGiveUpSuite` (task 188): a projection of 100 columns stays in its stage and one of
  101 leaves it.
* **Prediction 2 held.** At 100 fields the Varka session fuses the projection and its kernel runs
  over the cached Arrow batches; at 101 it has no Varka node, for a query that reads one column
  of the 101, and the rule leaves it to Spark without a reason. Both answer as the row engine
  does.

So the census's reading of the source was right and milestone 6's registered immunity was not:
today Varka shares this cliff with Spark, silently. The 101-column test is the one step 2 turns
around.
