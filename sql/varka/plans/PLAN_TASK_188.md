# Task 188: every place Spark's code generation gives up

*Row 188 of `PLAN_MILESTONE_6.md`, section 2.11. Compiled on 24 September 2026 by a research agent
reading the source at master `0ea8414fef5`, then reviewed. Sections 1 to 4 are the census as
drafted; its Varka column is a proposal, checked where section 5 says so. Section 5 records what
the review established, above all that the 8000-byte cliff is logged, which corrects this
milestone's plans.*

## 1. Revision

vecbricks/varka master `0ea8414fef5` ("[VARKA] Task 172, step 1: the range
filter's baseline, and the Arrow cache fixed"). The Spark code read here matches
upstream apache/spark master, including SPARK-57370's JDK compiler backend
(`CodeCompiler.scala`). The one exception is the Varka hooks, which are marked.
Janino is pinned at 3.1.12 (`pom.xml:210`). Janino's error strings below were
read with `strings` from `~/.m2/.../janino-3.1.12.jar`.

Paths are abbreviated:
`CG` = `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/expressions/codegen/CodeGenerator.scala`,
`CC` = `.../codegen/CodeCompiler.scala`,
`WSCG` = `sql/core/src/main/scala/org/apache/spark/sql/execution/WholeStageCodegenExec.scala`,
`SQLConf` = `sql/catalyst/src/main/scala/org/apache/spark/sql/internal/SQLConf.scala`,
`exec/` = `sql/core/src/main/scala/org/apache/spark/sql/execution/`,
`cat/` = `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/`,
`varka/` = `sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/`.

Log levels: Spark's default `log4j2-defaults.properties`
(`common/utils-java/src/main/resources/org/apache/spark/`) sets
`rootLogger.level = info`, so an INFO line is written by `spark-submit` by
default. An interactive shell lowers the console threshold to WARN
(`common/utils/.../internal/Logging.scala:377-383`), so INFO is hidden there.
"Plan marker" means the `*(n)` prefix that EXPLAIN puts on operators inside a
whole-stage codegen (WSCG) stage. An operator without it has lost WSCG.

## 2. Summary table

Varka column: I = immune, S = solved, D = declines, NA = not applicable (the
operator stays Spark's), ? = unsure. All Varka answers are proposals.

| # | Give-up point | Trigger (config, default) | What happens | Logged? | Varka (proposal) |
|---|---|---|---|---|---|
| G1 | WSCG switched off | `spark.sql.codegen.wholeStage`=false (default true), or `spark.sql.codegen.factoryMode`=NO_CODEGEN (default FALLBACK) | No stage anywhere. File scans also stop producing columnar output | No. Plan has no `*(n)` | I for Varka's own nodes. Parquet/ORC stop being columnar (G3) |
| G2 | Schema too wide | nested leaf fields of an operator's output or any child's output > `spark.sql.codegen.maxFields` (100) | That operator is left out of WSCG, with an InputAdapter boundary | No. Only the missing `*(n)` | I for the kernel (not in WSCG). But see G3 |
| G3 | Schema too wide at the source (coupling) | same `maxFields` test on the scan's schema; the cache uses the FULL cached-relation schema | Scan stops producing columnar output: FileSourceScan, Parquet/ORC v2, InMemoryTableScan | No | **NOT immune (proposal)**: Varka needs `child.supportsColumnar`, so a cached relation with >100 fields silently gets no Varka and no reason is recorded |
| G4 | Non-leaf CodegenFallback expression | any non-leaf `CodegenFallback` anywhere in the operator's expressions | Whole operator out of WSCG. The expression runs `eval()` | No | D per entry/conjunct: "unsupported expression" / "unsupported predicate". The residual Filter/Project stays Spark's |
| G5 | Operator not CodegenSupport | e.g. WindowExec, ObjectHashAggregateExec, WindowGroupLimitExec, SortMergeAsOfJoinExec (explicitly false) | Stage boundary. The operator runs its iterator path | No | NA |
| G6 | Aggregate conditions | ImperativeAggregate, immutable buffer type; SortAggregate confs (both default true), non-binary-stable keys | Aggregate out of WSCG | No | NA |
| G7 | Join conditions | SMJ full outer / existence confs, SHJ full-outer / build-side-outer confs (all default true); BNLJ join/build-side combinations | Join out of WSCG | No | NA |
| G8 | Generator conditions | CodegenFallback generator; `stack` with more than 50 rows | GenerateExec out of WSCG | No | NA |
| G9 | MergeRowsExec parameter length | child output's parameter length > 255 | Out of WSCG | No | NA |
| G10 | Union conditions | `...wholeStage.union.enabled` (true), `...union.maxChildren` (64), nested/partitioning-aware/multi-RDD/type mismatch | UnionExec out of WSCG | DEBUG `UnionExec codegen skipped: reason=...` | NA |
| G11 | Structural exclusions | ObjectType output; LocalTableScan/EmptyRelation/CommandResult as root; SMJ/SHJ children; columnar output; more than 2 input RDDs | Not wrapped / new stage / assertion | No | Varka nodes are themselves a WSCG boundary (not CodegenSupport) |
| G12 | No split in `splitExpressionsWithCurrentInputs` inside WSCG | `currentVars != null` (the WSCG case) | The code of all expressions is concatenated in one method. 26 call sites | No | I (does not use it) |
| G13 | No `reduceCodeSize` inside WSCG | `ctx.currentVars != null` | A large expression is not moved to its own method | No | I |
| G14 | No split of the row writer's top-level fields inside WSCG | `isTopLevel && (row == null or currentVars != null)` | ProjectExec's UnsafeRow writer stays inline | No | I (Varka's merge/residual projections run outside WSCG, where this does split) |
| G15 | The 1024-character heuristic | `spark.sql.codegen.methodSplitThreshold` (1024 characters of source) | Splits by source characters, not bytecode bytes | No | S: bytes are measured after build (`VarkaLoopEmitter.java:400-431`) |
| G16 | Consume method per operator not split out | `splitConsumeFuncByOperator` (true); parent must use all outputs; parameter length <= 255 | Parent's consume code inlined into the child's method | No | NA/I (Varka nodes are outside WSCG) |
| G17 | Subexpression split refused (WSCG) | code > threshold and a function's parameter length > 255 | CSE stays inline | INFO "Failed to split subexpression code..." (tests: internal error) | I (IR-level CSE, fixed seven-parameter methods) |
| G18 | Aggregate-function split refused | `...aggregate.splitAggregateFunc.enabled` (true); a SimpleExprValue in subexprs (silent); parameter length > 255 | Aggregate update code inline | INFO "Failed to split aggregate code..." for the parameter case only | NA |
| G19 | Expand split refused | parameter length > 255 | That switch case inline | No | NA |
| G20 | `With` common-expression method refused | uncapturable value, CSE'd node, parameter length > 255 | Definition inline | No | I (Varka does not generate `With`)/? |
| G21 | Fast hash map refused | `...aggregate.map.twolevel.enabled` (true) but unsupported key/buffer types, or `partialOnly` (true) for final mode | Only the regular hash map | INFO "...is set to true, but current version of codegened fast hashmap does not support this aggregate." | NA |
| G22 | Nested-class spill (mitigation) | class's added-function source > 1,000,000 characters (`GENERATED_CLASS_SIZE_THRESHOLD`) | New inner class. Not a give-up | No | S/I: constant pool counted (`VarkaEmitBudget.java:129-132`); literals are runtime arguments |
| G23 | Mutable-state compaction (mitigation) | > 10000 inlined primitive states; arrays of 32768 | States moved to arrays. Not a give-up | No | I |
| G24 | Compile failure (64 KB method, constant pool, any Janino/javac error) | any `CompileException`/`InternalCompilerException` | Throws. WSCG: stage runs without WSCG if `spark.sql.codegen.fallback` (true) and not testing | ERROR "Failed to compile the generated Java code." + source dump at INFO (ERROR in tests) + WARN "Whole-stage codegen disabled for plan (id=N)" | S: bytecode built directly (ClassFile API); caps checked before load; plan-time decline |
| G25 | `hugeMethodLimit` fallback | largest compiled method > `spark.sql.codegen.hugeMethodLimit` (65535) | Stage runs without WSCG. **Cannot fire at the default**: no compiled method can exceed 65535 | INFO "Found too long generated codes and JIT optimization might not work..." | S (budget is 8000, not 65535) |
| G26 | HotSpot HugeMethodLimit, 8000 bytes | a method > 8000 bytes, with `-XX:+DontCompileHugeMethods` on (default) | Method never JIT-compiled; runs interpreted | **INFO "Generated method too long to be JIT compiled: C.m is N bytes"** (SPARK-25113), per method per compile. JVM: nothing | S: `methodByteBudget` default 8000, regroup or plan-time decline |
| G27 | Constant pool | > 65535 entries | Only through G24. Spark computes `maxConstPoolSize` but never compares it with anything | EXPLAIN CODEGEN prints it with a % used | S: counted and declined (`VarkaEmitBudget.java:129`) |
| G28 | 255 parameter slots | only guarded where G9/G16-G20 check it | Unguarded overflow: ? | ? | I: fixed arity, and checked (`VarkaEmitBudget.java:123`) |
| G29 | Byte-code statistics unavailable | class-file parse fails (NonFatal) | Stats -1, so G25's check is skipped | WARN "Error calculating stats of compiled class." | NA |
| G30 | Executor-side compile has no fallback | compile fails on an executor (the driver compiled fine) | Task fails | ERROR from G24; no WSCG fallback | ? Varka re-emits on the executor, but the ghost fallback catches it |
| G31 | Compiler backend routed back to Janino | `spark.sql.codegen.compiler`=jdk but javac missing, REPL, package-object or unnameable class | Janino used instead | WARN once / INFO once | I (no source compiler) |
| G32 | Expression codegen fails outside WSCG | any exception from codegen or compile in UnsafeProjection/MutableProjection/SafeProjection/Predicate/RowOrdering `.create` | Interpreted object used instead | WARN "Expr codegen error and falling back to interpreter mode" | Varka's residual/fallback projections inherit this. Kernel failures go to the ghost fallback (WARN) |
| G33 | Callers that bypass the interpreted fallback | direct `GenerateX.generate(...)`: about 30 sites | Exception, so the query or task fails | ERROR from G24 | NA |
| G34 | No size check outside WSCG | a non-WSCG projection/predicate over 8000 bytes | Runs interpreted; `hugeMethodLimit` does not apply | INFO line of G26 only | Varka's residual projections are in this class |

Count: 34 entries. 30 are give-ups in the strict sense. G22 and G23 are
mitigations, and G29 and G31 are degradations of the machinery rather than of
generated code; they are listed so the next reader does not add them again.

## 3. The give-up points in detail

### A. Plan time: CollapseCodegenStages and operator `supportCodegen`

#### G1. Whole-stage codegen switched off globally
- Where: `WSCG:988-993`
  ```
  def apply(plan: SparkPlan): SparkPlan = {
    if (conf.wholeStageEnabled && conf.codegenFactoryMode != CodegenObjectFactoryMode.NO_CODEGEN) {
      insertWholeStageCodegen(plan)
  ```
- Trigger: `spark.sql.codegen.wholeStage` (`SQLConf:2918`, internal, default
  true), or `spark.sql.codegen.factoryMode=NO_CODEGEN` (`SQLConf:3017`,
  internal, default FALLBACK, documented as for tests only).
- What happens: no WSCG stage anywhere. Side effect: `wholeStageEnabled` is also
  required for columnar file scans (`exec/DataSourceScanExec.scala:739`,
  `exec/datasources/v2/parquet/ParquetPartitionReaderFactory.scala:76`,
  `.../orc/OrcPartitionReaderFactory.scala:69`), so turning WSCG off also turns
  the vectorised Parquet/ORC readers off.
- Logged: no. The plan has no `*(n)`.
- Scope: every operator.
- Varka: I for the Varka nodes, which do not use WSCG. The Arrow cache scan
  (`InMemoryTableScanExec.supportsColumnar`) does not test `wholeStageEnabled`,
  so the Arrow-cache path keeps Varka. Note that task 192's `wholeStage=false`
  arm therefore changes more than the projection when the source is a file scan.

#### G2. Schema too wide: `spark.sql.codegen.maxFields`
- Where: `WSCG:582-592` and `WSCG:925-934`
  ```
  def isTooManyFields(conf: SQLConf, dataType: DataType): Boolean = {
    numOfNestedFields(dataType) > conf.wholeStageMaxNumFields
  ...
  val hasTooManyInputFields =
    plan.children.exists(p => WholeStageCodegenExec.isTooManyFields(conf, p.schema))
  ```
- Trigger: the operator's output schema, or any child's output schema, has more
  than `spark.sql.codegen.maxFields` (`SQLConf:2964`, internal, default 100)
  leaf fields. `numOfNestedFields` counts struct fields recursively, map key +
  value, array element, UDT sqlType; any other type counts 1.
- What happens: this operator is not a WSCG member. Its codegen-capable
  neighbours form separate stages through InputAdapter.
- Logged: no. Only the missing `*(n)`.
- Scope: any CodegenSupport operator. It is decided per operator, so a
  101-entry projection over a narrow source loses WSCG for the ProjectExec, and
  whatever consumes it loses WSCG too (its child's schema is wide).
- Varka: I for the kernel. Varka nodes are not WSCG members, and a kernel reads
  at most `MAX_INPUTS` = 64 columns (`varka/VarkaLoopEmitter.java:213`), not the
  schema around them. For a wide *output*, `VarkaColumnarToRowExec`'s
  merge/`toRow` projections are non-WSCG `UnsafeProjection`s
  (`exec/VarkaColumnarToRowExec.scala:270-301`), which `maxFields` does not
  affect. But see G3, which is where the task-185 prediction is at risk.

#### G3. The same test gates columnar output at the source
- Where:
  - `exec/columnar/InMemoryTableScanExec.scala:96-100`
    ```
    override val supportsColumnar: Boolean = {
      conf.cacheVectorizedReaderEnabled  &&
          !WholeStageCodegenExec.isTooManyFields(conf, relation.schema) &&
          relation.cacheBuilder.serializer.supportsColumnarOutput(relation.schema)
    ```
  - `exec/DataSourceScanExec.scala:735-741` (FileSourceScanExec: "Only output
    columnar if there is WSCG to read it"), Parquet v2
    `ParquetPartitionReaderFactory.scala:75-76`, ORC v2
    `OrcPartitionReaderFactory.scala:67-72`.
- Trigger: `maxFields` applied to the scan's schema. The cache path tests
  `relation.schema`, the FULL cached relation, not the pruned attributes a query
  reads.
- What happens: the scan produces rows rather than batches.
- Logged: no.
- Scope: every columnar source above.
- Varka (proposal, important): **not immune**. `VarkaColumnarRule` rewrites
  only `if (child.supportsColumnar)` (`exec/VarkaColumnarRule.scala:67` and
  `111`; filters also `81` and `133`), and `ArrowCachedBatchSerializer`
  otherwise always says yes (`exec/columnar/ArrowCachedBatchSerializer.scala:149-152`).
  So `CACHE` a table of 101 or more columns and project one date column: the
  cache scan is not columnar, Varka does not fire, and the rule returns the
  `ProjectExec` unchanged without recording a reason (`VarkaColumnarRule.scala:70`).
  This is exactly the "wide-schema shape where Varka also declines" that
  `PLAN_MILESTONE_6.md` 2.6 calls the more interesting outcome of task 185's
  prediction. Not verified by a run.

#### G4. A non-leaf `CodegenFallback` expression
- Where: `WSCG:918-927`
  ```
  case e: LeafExpression => true
  // CodegenFallback requires the input to be an InternalRow
  case e: CodegenFallback => false
  ...
  val willFallback = plan.expressions.exists(_.exists(e => !supportCodegen(e)))
  ```
  The trait: `cat/expressions/codegen/CodegenFallback.scala:26-30`. Its
  generated code calls `((Expression) references[i]).eval(INPUT_ROW)`, which is
  why WSCG, where no input row exists, refuses it.
- Trigger: any non-leaf CodegenFallback anywhere in the operator's expression
  trees, including through `With` (see the CodegenFallback scaladoc).
  `ImperativeAggregate` is one (`cat/expressions/aggregate/interfaces.scala:285`).
  An approximate grep finds about 60 mix-in declarations in
  catalyst/core/hive main.
- What happens: the whole operator leaves WSCG. The expression is still
  evaluated through generated code that calls `eval`.
- Logged: no.
- Scope: every operator. A leaf CodegenFallback does not trigger it.
- Varka: D per entry/conjunct. Anything outside the grammar notes
  "unsupported expression" (`cat/.../codegen/VarkaExpressionCompiler.scala:818`)
  or "unsupported predicate" (`VarkaConditionCompiler.scala:216`), and stays a
  residual. The residual `FilterExec`/`UnsafeProjection` then meets G4/G32 exactly
  as vanilla does.

#### G5. Operators that are not CodegenSupport
- Where: `WSCG:925-935` (`case _ => false`) and `WSCG:940-943`, which puts an
  InputAdapter over such a plan. `exec/joins/SortMergeAsOfJoinExec.scala:69`
  says `override def supportCodegen: Boolean = false` explicitly.
- Trigger: the operator class. Seen in `exec/window` and `exec/aggregate`:
  WindowExec, WindowGroupLimitExec, ObjectHashAggregateExec,
  MergingSessionsExec, UpdatingSessionsExec. (The list is not exhaustive: see
  section 4.)
- What happens: stage boundary. The operator runs its own `doExecute`.
- Logged: no.
- Varka: NA.

#### G6. Aggregates
- Where:
  - `exec/aggregate/AggregateCodegenSupport.scala:84-89`
    ```
    val isMutableAggBuffer = aggregateBufferAttributes.forall(a => UnsafeRow.isMutable(a.dataType))
    // ImperativeAggregate are not supported right now
    isMutableAggBuffer &&
      !aggregateExpressions.exists(_.aggregateFunction.isInstanceOf[ImperativeAggregate])
    ```
  - `exec/aggregate/SortAggregateExec.scala:97-105`:
    `spark.sql.codegen.aggregate.sortAggregate.enabled` (`SQLConf:4232`, true)
    and `...sortAggregate.withKeys.enabled` (`SQLConf:4240`, true) with
    `groupingExpressions.forall(e => UnsafeRowUtils.isBinaryStable(e.dataType))`.
- What happens: the aggregate leaves WSCG.
- Logged: no.
- Varka: NA. Aggregates stay Spark's.

#### G7. Joins
- Where: `exec/joins/SortMergeJoinExec.scala:180-184` (FullOuter:
  `...join.fullOuterSortMergeJoin.enabled`, ExistenceJoin:
  `...join.existenceSortMergeJoin.enabled`, both true, `SQLConf:4267/4275`);
  `exec/joins/ShuffledHashJoinExec.scala:358-365` (FullOuter, and the outer join
  whose outer side is the build side: `SQLConf:4250/4258`, true);
  `exec/joins/BroadcastNestedLoopJoinExec.scala:429-433`
  ```
  case (_: InnerLike, _) | (LeftOuter, BuildRight) | (RightOuter, BuildLeft) |
       (LeftSemi | LeftAnti, BuildRight) | (LeftSingle, BuildRight) => true
  case _ => false
  ```
  BroadcastHashJoinExec has no override and always generates code.
- Logged: no.
- Varka: NA.

#### G8. Generators
- Where: `exec/GenerateExec.scala:136` delegates to `generator.supportCodegen`,
  which is `!isInstanceOf[CodegenFallback]` (`cat/expressions/generators.scala:81`).
  `Stack` has its own rule at `generators.scala:260`:
  `override def supportCodegen: Boolean = numRows <= 50`.
- Logged: no.
- Varka: NA.

#### G9. MergeRowsExec parameter length
- Where: `exec/datasources/v2/MergeRowsExec.scala:110-112`
  ```
  override def supportCodegen: Boolean = {
    CodeGenerator.isValidParamLength(CodeGenerator.calculateParamLength(child.output))
  ```
- Trigger: the child's output as parameters (long/double count 2, nullable +1,
  plus `this`) > 255.
- Logged: no. Varka: NA.

#### G10. Union
- Where: `exec/basicPhysicalOperators.scala:1066-1098`
  ```
  } else if (children.size > conf.getConf(SQLConf.WHOLESTAGE_UNION_MAX_CHILDREN)) {
    Some("max-children-exceeded")
  ```
  The other reasons are union-codegen-disabled, partitioning-aware,
  nested-union, multi-rdd-child, partition-index-dependent-child, columnar and
  type-mismatch. Confs: `spark.sql.codegen.wholeStage.union.enabled`
  (`SQLConf:2936`, true, version 4.2.0) and `...union.maxChildren`
  (`SQLConf:2948`, 64).
- Logged: DEBUG, `UnionExec codegen skipped: reason=<r>, numChildren=<n>`.
- Varka: NA.

#### G11. Structural exclusions
- `WSCG:960-986`: a plan whose single output is an `ObjectType` is never
  wrapped. LocalTableScanExec, EmptyRelationExec and CommandResultExec are never
  a stage root, so that collect/take can run locally on the driver.
- `WSCG:944-951`: SortMergeJoin and ShuffledHashJoin children always get their
  own stages.
- `WSCG:732-736`: `doExecuteColumnar` just calls `child.executeColumnar()`
  ("Code generation is not currently supported for columnar output"). WSCG
  never generates columnar output. `WSCG:978-981` asserts
  `!plan.supportsColumnar`.
- `WSCG:772`: `assert(rdds.size <= 2, "Up to two input RDDs can be supported")`.
- Logged: no.
- Varka: none of these apply. Varka's own nodes are a WSCG boundary as well,
  though: `VarkaColumnarToRowExec` and `VarkaFilterExec` are not CodegenSupport
  (`exec/VarkaColumnarToRowExec.scala:69`, `exec/VarkaFilterExec.scala:292`), so
  the operator above them starts a new stage.

### B. Generation time: the splitter and where it is off

#### G12. `splitExpressionsWithCurrentInputs` does not split inside WSCG
- Where: `CG:1170-1190`
  ```
  // (a to-do comment: support whole-stage codegen)
  if (INPUT_ROW == null || currentVars != null) {
    expressions.mkString("\n")
  ```
- Trigger: `currentVars != null`, which is set by `consume` at `WSCG:187`
  (`ctx.currentVars = inputVars; ctx.INPUT_ROW = null`). That is every
  expression generated for a WSCG operator's consume.
- What happens: the per-child code of every caller is concatenated into one
  method. There are 26 call sites in main: CreateNamedStruct/CreateArray
  (`complexTypeCreator.scala:172,567`), `In` (`predicates.scala:576`), CaseWhen
  (`conditionalExpressions.scala:364`), Coalesce/AtLeastNNonNulls
  (`nullExpressions.scala:142,625`), Murmur3/XxHash/HiveHash
  (`hash.scala:425,1021`), Concat/ConcatWs/Elt/FormatString
  (`stringExpressions.scala:152,220,226,239,387,2436`), Greatest/Least
  (`arithmetic.scala:1385,1480`), `collectionOperations.scala:390,803,3279`,
  `objects.scala:167,1791,1943`, Stack (`generators.scala:268`), and
  Mutable/SafeProjection (which run outside WSCG).
- Logged: no. It surfaces later as G26 (INFO) or G24 (ERROR+WARN).
- Varka: I. The emitter does not use Spark's splitter; its methods are grouped
  by weight and then measured in bytes (G15).

#### G13. `Expression.reduceCodeSize` is off inside WSCG
- Where: `cat/expressions/Expression.scala:240-243`
  ```
  // (a to-do comment: support whole-stage codegen too)
  val splitThreshold = SQLConf.get.methodSplitThreshold
  if (eval.code.length > splitThreshold && ctx.INPUT_ROW != null && ctx.currentVars == null) {
  ```
- What happens: an expression whose code is longer than 1024 characters is not
  moved into its own method under WSCG. This is the second place with the same
  to-do comment, and the plan's 2.11 quotes only the first.
- Logged: no. Varka: I.

#### G14. The row writer's top-level fields are not split inside WSCG
- Where: `cat/expressions/codegen/GenerateUnsafeProjection.scala:188-196`
  ```
  val writeFieldsCode = if (isTopLevel && (row == null || ctx.currentVars != null)) {
    // (a to-do comment: support whole-stage codegen)
    writeFields.mkString("\n")
  ```
- What happens: ProjectExec's output UnsafeRow writer, one block per output
  field, is inline in the consume method. This is the third such to-do comment. For a wide
  projection it is probably what drives the method size task 171 measured
  (`project_doConsume_0$`), alongside G12. Unconfirmed.
- Logged: no.
- Varka: I. Varka's `toRow`/merge projections are built with
  `UnsafeProjection.create` outside WSCG, where `row != null` and
  `currentVars == null`, so they do split.

#### G15. The split heuristic counts source characters
- Where: `CG:1260-1279` (`buildCodeBlocks`)
  ```
  // We can't know how many bytecode will be generated, so use the length of source code
  // as metric. A method should not go beyond 8K, otherwise it will not be JITted, ...
  if (length > splitThreshold) {
  ```
  Conf: `spark.sql.codegen.methodSplitThreshold` (`SQLConf:3093`, internal,
  default 1024 characters). The subexpression split (`CG:1479`) and the aggregate
  split (`AggregateCodegenSupport.scala:287-288`) use the same threshold, as
  does `ExpandExec` (`exec/ExpandExec.scala:227-228`).
- What happens: nothing fails. It is a proxy with no feedback, because nobody
  measures the resulting bytes against 8000 (G26 only logs).
- Varka: S. The class is built, measured in the units the JVM enforces, and
  regrouped until every group fits, or declined
  (`varka/VarkaLoopEmitter.java:400-431`, `VarkaEmitBudget.java:111-134`).

#### G16. The per-operator consume method is not split out
- Where: `WSCG:192-208`
  ```
  val paramLength = CodeGenerator.calculateParamLength(output) + (if (row != null) 1 else 0)
  val consumeFunc = if (confEnabled && requireAllOutput
      && CodeGenerator.isValidParamLength(paramLength)) {
  ```
- Trigger: `spark.sql.codegen.splitConsumeFuncByOperator` (`SQLConf:3107`, true)
  off; or the parent does not use every output (`requireAllOutput`); or more
  than 255 parameter slots (roughly 127 nullable int columns, fewer with
  long/double).
- What happens: the parent's `doConsume` is inlined into the child's method, so
  methods of adjacent operators merge.
- Logged: no.
- Varka: NA/I. Varka nodes are outside WSCG, and every emitted method has the
  same seven-parameter shape (`VarkaLoopEmitter.java` method-layout note near
  365).

#### G17. Subexpression-elimination split refused (WSCG)
- Where: `CG:1479-1544`
  ```
  val needSplit = nonSplitCode.map(_.eval.code.length).sum > SQLConf.get.methodSplitThreshold
  ...
  if (inputVarsForAllFuncs.map(calculateParamLengthFromExprValues).forall(isValidParamLength)) {
  ```
- What happens: when one common subexpression's function would take more than
  255 parameter slots, none is split. All CSE code stays inline.
- Logged: INFO "Failed to split subexpression code into small functions because
  the parameter length of at least one split function went over the JVM limit:
  255". Under `Utils.isTesting` it throws `SparkException.internalError`
  instead.
- Varka: I. CSE happens in the IR, and no parameter list grows with the
  inputs.

#### G18. Aggregate-function split refused
- Where: `exec/aggregate/AggregateCodegenSupport.scala:287-289, 325-331, 341, 368-377`
  ```
  if (exprValsInSubExprs.exists(_.isInstanceOf[SimpleExprValue])) {
    // `SimpleExprValue`s cannot be used as an input variable for split functions, so
    // we give up splitting functions if it exists in `subExprs`.
    None
  ```
- Trigger: `spark.sql.codegen.aggregate.splitAggregateFunc.enabled`
  (`SQLConf:4222`, true) off; or code <= 1024 characters; or a SimpleExprValue
  in the CSE states (silent); or one function's parameters > 255.
- Logged: INFO "Failed to split aggregate code into small functions because the
  parameter length of at least one split function went over the JVM limit: 255"
  for the parameter case only. The SimpleExprValue case is silent. Tests throw.
- Varka: NA.

#### G19. Expand switch-case split refused
- Where: `exec/ExpandExec.scala:227-243`
  ```
  val maybeSplitUpdateCode = if (CodeGenerator.isValidParamLength(paramLength)) {
  ...
  } else {
    updateCode
  ```
- Logged: no. Varka: NA.

#### G20. `With` common-expression method refused
- Where: `CG:286-298, 387-389`
  ```
  val worthAMethod = definition.containsPattern(WITH_EXPRESSION) ||
    body.length > SQLConf.get.methodSplitThreshold
  ...
  possible && isValidParamLength(calculateParamLengthFromExprValues(params)))(params)
  ```
- Trigger: a value no parameter can name, a subexpression-eliminated node, an
  input variable not yet evaluated, or parameters > 255 (`methodArgs`, `CG:324-390`).
- What happens: the definition stays inline.
- Logged: no.
- Varka: I, provided the optimizer rewrites `With` before Varka sees the plan.
  Unsure whether any `With` survives to the physical plan in a shape Varka
  compiles.

#### G21. Fast (two-level) hash map refused
- Where: `exec/aggregate/HashAggregateExec.scala:507-541, 572-575`
  ```
  logInfo(log"${MDC(CONFIG, SQLConf.ENABLE_TWOLEVEL_AGG_MAP.key)} is set to true, but" +
    log" current version of codegened fast hashmap does not support this aggregate.")
  ```
- Trigger: a key that is not binary-stable; a key or buffer that is not
  primitive/Decimal/String/CalendarInterval; a byte-array decimal buffer; or
  final mode while `...map.twolevel.partialOnly` (`SQLConf:4204`, true).
- Logged: INFO, except under testing. WARN "Two level hashmap is disabled but
  vectorized hashmap is enabled." for that configuration.
- This is a codegen feature give-up, not a codegen off switch.
- Varka: NA.

#### G22. Nested classes past 1,000,000 characters (mitigation)
- Where: `CG:1756-1760, 787-791, 807-812`
  ```
  // limit, 65,536. We cannot know how many constants will be inserted for a class, so we use a
  // threshold of 1000k bytes to determine when a function should be inlined to a private, inner
  // class.
  final val GENERATED_CLASS_SIZE_THRESHOLD = 1000000
  ```
  `classSize(className) += funcCode.length` counts only functions added through
  `addNewFunction`. Field declarations, `processNext` and the class's other code
  are not counted.
- What happens: new functions go to `NestedClassN`. More than 3 functions in an
  inner class are called through one grouping method
  (`MERGE_SPLIT_METHODS_THRESHOLD = 3`, `CG:1754`, `CG:1323`).
- Logged: no. It is not a give-up, but it is the only defence against G27.
- Varka: S/I. The constant pool is counted after build and declined over 65535
  (`varka/VarkaEmitBudget.java:129-132`). Literal values are runtime argument
  slots, not class constants (`VarkaExpressionCompiler.scala` class doc near 300),
  so the pool does not grow per literal value. Unsure: whether any shape has
  ever come close.

#### G23. Mutable-state compaction (mitigation)
- `CG:1783` `OUTER_CLASS_VARIABLES_THRESHOLD = 10000`, `CG:1788`
  `MUTABLESTATEARRAY_SIZE_LIMIT = 32768`, `addMutableState` near `CG:559-565`.
  It moves states into arrays to protect the constant pool. Not a give-up.
- Varka: I.

### C. Compile time

#### G24. Compile failure, and the WSCG fallback
- Where (Janino): `CC:424-436`
  ```
  case e: InternalCompilerException =>
    logError("Failed to compile the generated Java code.", e)
    CodeCompiler.logGeneratedCodeOnFailure(code, SQLConf.get.loggingMaxLinesForCodegen)
    throw QueryExecutionErrors.internalCompilerError(e)
  ```
  JDK backend: `CC:1323-1349` (the same ERROR text). Source dump:
  `CC:379-386`, logged at ERROR under testing and at **INFO otherwise**, up to
  `spark.sql.codegen.logging.maxLines` (1000).
  WSCG fallback: `WSCG:740-751`
  ```
  case NonFatal(_) if !Utils.isTesting && conf.codegenFallback =>
    // We should already saw the error message
    logWarning(log"Whole-stage codegen disabled for plan " +
      log"(id=${MDC(CODEGEN_STAGE_ID, codegenStageId)}):\n " +
    ...
    return child.execute()
  ```
- Trigger, from the Janino 3.1.12 jar: a method over 65535 bytes, "Code grows
  beyond 64 KB" (`org.codehaus.janino.CodeContext`); a constant pool over
  65535, "Constant pool for class ... has grown past JVM limit of 0xFFFF"
  (`org.codehaus.janino.util.ClassFile`); or any other Janino/javac error or
  Janino bug. Conf: `spark.sql.codegen.fallback` (`SQLConf:3029`, internal,
  true). The error type is `InternalCompilerException`/`CompileException` with
  message "Failed to compile: <e>" (`cat/errors/QueryExecutionErrors.scala:732-742`).
- What happens: under WSCG, the stage's subtree runs each operator's non-WSCG
  `doExecute`. Expression codegen inside those operators goes through G32. **Under
  `Utils.isTesting` there is no fallback and the query fails**, which matters for
  reproducers in this repo's test JVMs. Only `doExecute`'s compile is guarded.
  An exception thrown while *generating* (`doCodeGen`, `WSCG:738`) is not caught.
- Also: the codegen cache (`CG:1848-1863`, Guava through `NonFateSharingCache`)
  keeps successes only, so a failing unit is compiled again, and logs its ERROR
  again, every time it is requested.
- Logged: ERROR + INFO dump + WARN, as quoted.
- Varka: S. Varka does not compile source. It builds class files with the JDK
  ClassFile API (`VarkaLoopEmitter.java:440`), checks the class-file caps itself
  (`VarkaEmitBudget.java:111-134`: "over the class-file cap of 65535: the class
  cannot be built"), and declines at plan time through the shape cache
  (`VarkaExpressionCompiler.scala:516-528`, reason "over the emitter's method
  budget (...)"). A leftover emission failure on an executor is caught and logged
  (`exec/VarkaEvaluatorBase.scala:110-137`: WARN "The Varka emitter declined
  ...; falling back to the per-row path." once per JVM, or WARN "Failed to emit
  the Varka fused kernel ...") and counted in a metric. Caveat: `admitBySize`
  admits a shape on any *other* NonFatal at plan time (`VarkaExpressionCompiler.scala:527`),
  leaving it to the ghost fallback.

#### G25. `hugeMethodLimit`: the check that cannot fire at its default
- Where: `WSCG:752-763`
  ```
  if (compiledCodeStats.maxMethodCodeSize > conf.hugeMethodLimit) {
    logInfo(log"Found too long generated codes and JIT optimization might not work: " +
    ...
    return child.execute()
  ```
  Conf: `spark.sql.codegen.hugeMethodLimit` (`SQLConf:3081`, internal, default
  65535). Its doc says 8000 "may be preferable" on HotSpot.
- What happens: this runs only after a *successful* compile. A class file cannot
  hold a method of more than 65535 code bytes (JVMS 4.7.3; Janino throws first,
  G24), so with the default `maxMethodCodeSize > 65535` is never true. At
  default settings a 64 KB method takes the G24 path (ERROR + WARN "Whole-stage
  codegen disabled for plan"). The INFO "Found too long generated codes ... the
  whole-stage codegen was disabled for this plan" appears only when a user has
  lowered the limit. `PLAN_TASK_172.md` 1.2/8 and `PLAN_TASK_193.md`'s band table
  attribute "the whole-stage codegen was disabled" to the 65535 crossing. By this
  reading that is the wrong log line for the default. It needs a reproducer.
- Logged: INFO, when reachable.
- Scope: WSCG only. Nothing like it exists for non-WSCG projections (G34).
- Varka: S. The budget is the 8000 of G26, not 65535.

#### G26. HotSpot's HugeMethodLimit, 8000 bytes
- Where: `CG:1742-1744` (`DEFAULT_JVM_HUGE_METHOD_LIMIT = 8000`) and
  `CC:338-345`
  ```
  if (byteCodeSize > CodeGenerator.DEFAULT_JVM_HUGE_METHOD_LIMIT) {
    logInfo(log"Generated method too long to be JIT compiled: " +
      log"${MDC(LogKeys.CLASS_NAME, cf.getThisClassName)}." +
      log"${MDC(LogKeys.METHOD_NAME, method.getName)} is " +
      log"${MDC(LogKeys.BYTECODE_SIZE, byteCodeSize)} bytes")
  ```
  Added by SPARK-25113 (commit `3c614d0565a`, 2018-08-14). It runs for every
  class `CodeGenerator.compile` builds, WSCG or not, once per compile. The cache
  means once per JVM per distinct source, on the driver and on each executor.
- Trigger: any generated method over 8000 bytes, with HotSpot's
  `DontCompileHugeMethods` on (the default).
- What happens: the method loads and runs, and is never compiled at any tier.
  Spark does not change the plan.
- Logged: **INFO, with class, method and size**. It is written by default under
  `spark-submit` (root INFO) and in this repo's test log (`sql/core/src/test/resources/log4j2.properties`:
  file appender at INFO, `target/unit-tests.log`), and hidden from an
  interactive shell's console. The JVM says nothing unless run with
  `-XX:+PrintCompilation`. The milestone's framing that this cliff "logs nothing"
  (`PLAN_TASK_171.md` 2.2, `PLAN_TASK_192.md`, `PLAN_TASK_193.md` 1) needs
  correcting to: Spark detects it, logs it at INFO, and does not act on it.
- Varka: S. `VarkaEmitOptions.DEFAULTS` has `methodByteBudget =
  VarkaEmitBudget.HUGE_METHOD_LIMIT` = 8000 (`varka/VarkaEmitOptions.java:416-423`,
  `VarkaEmitBudget.java:77`). The emitter regroups until every group fits, and
  declines the rest at plan time with a reason (G24). Pinned by
  `VarkaHugeMethodSuite` (`PLAN_TASK_87.md`).

#### G27. The constant pool is measured and never checked
- Where: `CC:335` (`cf.getConstantPoolSize`) into
  `ByteCodeStats.maxConstPoolSize` (`CG:1734`). Its only reader is
  `exec/debug/package.scala:87-94` (EXPLAIN CODEGEN / `debugCodegen`:
  "maxConstantPoolSize:N (x% used)"). `CG:1750` `MAX_JVM_CONSTANT_POOL_SIZE =
  65535` is used only there.
- What happens: nothing until overflow, which is then G24 (Janino "has grown
  past JVM limit of 0xFFFF"). G22 is the only guard.
- Logged: only through G24.
- Varka: S (`VarkaEmitBudget.java:88, 129-132`: "the constant pool has N entries,
  over the cap of 65535"), and largely I by design (G22).

#### G28. The 255 parameter-slot limit
- Where: `CG:1747` (`MAX_JVM_METHOD_PARAMS_LENGTH = 255`) and `CG:2301-2333`
  (`calculateParamLength`, `isValidParamLength`, with a test-only override key
  `spark.sql.CodeGenerator.validParamLength`). It guards G9 and G16-G20.
- What happens when unguarded code exceeds it: unsure. Janino 3.1.12 shows no
  "too many parameters" string. A class-load-time `ClassFormatError` is
  plausible and would escape G24's catch of compile exceptions... but not WSCG's
  `NonFatal`, since loading happens inside `compile`. Needs a reproducer.
- Varka: I. The seven-parameter shape is fixed, and the slot count is checked
  anyway (`VarkaEmitBudget.java:91, 123-127`).

#### G29. When statistics fail, the size check is skipped
- Where: `CC:353-356`
  ```
  case NonFatal(e) =>
    logWarning("Error calculating stats of compiled class.", e)
    (-1, -1)
  ```
- What happens: `maxMethodCodeSize = -1`, so G25's check passes, and G26's INFO
  for that class is lost.
- Logged: WARN. Varka: NA (Varka measures its own bytes with
  `VarkaEmittedClass.measure`).

#### G30. The executor compiles again, with no fallback
- Where: `exec/WholeStageCodegenEvaluatorFactory.scala:45`
  `val (clazz, _) = CodeGenerator.compile(cleanedSource)`. It is not wrapped in
  a try.
- What happens: the driver's compile in `doExecute` decides the fallback. If an
  executor's compile fails (different class loader, a cache eviction followed by
  a failure that depends on the environment), the task fails. The fallback
  cannot be reached from there.
- Logged: G24's ERROR, on the executor.
- Varka: unsure. `VarkaEvaluatorBase.fusedRunner` catches emission failures on
  the executor and falls back per batch, so the Varka half looks safe. A
  reproducer for the vanilla half would need a driver/executor difference, which
  is hard to build honestly.

#### G31. The compiler backend is routed back to Janino
- Where: `CC:174-194, 298-318`. `spark.sql.codegen.compiler` (`SQLConf:3054`,
  internal, default `janino` or `$SPARK_CODEGEN_COMPILER`). When `jdk` is
  requested: no javac gives WARN once "... Falling back to Janino for this JVM."
  REPL context, package-object classes and unnameable classes give INFO once
  each.
- Not a codegen give-up. It is listed because it changes which compiler's
  limits (G24) apply.
- Varka: I.

### D. Expression codegen outside WSCG

#### G32. The interpreted fallback
- Where: `cat/expressions/CodeGeneratorWithInterpretedFallback.scala:39-55`
  ```
  case _ =>
    try {
      createCodeGeneratedObject(in)
    } catch {
      case NonFatal(e) =>
        logWarning("Expr codegen error and falling back to interpreter mode", e)
        createInterpretedObject(in)
  ```
  Used by `Predicate` (`predicates.scala:77`), `RowOrdering` (`ordering.scala:98`),
  `MutableProjection`/`UnsafeProjection`/`SafeProjection`
  (`Projection.scala:83,122,169`). `spark.sql.codegen.factoryMode`: CODEGEN_ONLY
  has no fallback; NO_CODEGEN always interprets and also disables WSCG (G1).
- Trigger: any NonFatal from generation or compilation, including G24's 64 KB
  and constant-pool failures and G13's no-split shapes... Outside WSCG, though,
  splitting is on, so this is rarer.
- What happens: an interpreted object for the lifetime of that operator
  instance.
- Logged: WARN with stack trace.
- Scope: non-WSCG operators, the operators under a G24 fallback, and anything
  that calls `.create`, including Varka's residual/fallback projections and
  predicates (`exec/VarkaColumnarToRowExec.scala:270-301`,
  `exec/VarkaFilterExec.scala:178, 182, 397-399`).
- Varka: the residual path inherits G32 by design. The kernel path has its own
  ghost fallback (`VarkaEvaluatorBase.scala:295-323`: WARN "The Varka SIMD
  kernels ... failed on this batch; falling back to the per-row path.", with
  cause metrics).

#### G33. Callers that bypass the fallback
- Where: about 30 direct `GenerateUnsafeProjection/GenerateOrdering/
  GenerateSafeProjection.generate(...)` calls. Examples:
  `exec/datasources/FileFormat.scala:145` (partition-column append for file
  scans), `exec/GroupedIterator.scala:76,79`, `exec/objects.scala:95,151,156,161`,
  `GenerateOrdering.scala:207,215` (`LazilyGeneratedOrdering`),
  `exec/window/WindowGroupLimitExec.scala:143`,
  `exec/datasources/v2/FileScan.scala:153`, several streaming state managers.
- What happens: a compile failure throws, and the query or task fails.
- Logged: G24's ERROR.
- Varka: NA.

#### G34. No size check outside WSCG
- Where: nothing in `CodeGeneratorWithInterpretedFallback` or the `Generate*`
  generators reads `ByteCodeStats`. The only size-driven action anywhere is
  G25, and it is WSCG-only.
- What happens: a non-WSCG projection with a method over 8000 bytes runs
  interpreted forever. There is only G26's INFO.
- Varka: the residual `UnsafeProjection`s are in this class. They are
  per-entry, split normally, and so are unlikely to cross. Unmeasured.

### E. Checked and excluded (not give-ups)
- `Nondeterministic` expressions do not disable codegen. `CodegenFallback.generate`
  registers them for `initialize(partitionIndex)` (`CodegenFallback.scala:58-68`),
  and generated nondeterministic expressions do the same through
  `addPartitionInitializationStatement`. Varka does decline a filter with any
  nondeterministic conjunct as a whole (`VarkaExpressionCompiler.scala:588`,
  `if (!condition.deterministic) return None`). **It records no reason for
  that decline**, unlike every other decline.
- Leaf `CodegenFallback` expressions are allowed in WSCG (`WSCG:919`).
- BroadcastHashJoinExec has no `supportCodegen` override.
- `spark.sql.codegen.cache.maxEntries` (`StaticSQLConf.scala:93`, default 100)
  only evicts; eviction means a recompile. Side note: its doc says caching is on
  "When nonzero", but `NonFateSharingCache.apply` sets `maximumSize` only when
  > 0 (`core/.../util/NonFateSharingCache.scala:61-66`), so 0 means an unbounded
  cache, not "off".
- `limitNotReachedCond`'s WARN "[BUG] ..." (`WSCG:413-421`) reports a bug in
  an operator and does not turn anything off.

## 4. What I could not settle, and the reproducers the next step needs

### Unsettled
1. **G3 against task 185's prediction.** From the source, a cached relation
   wider than 100 fields gets no Varka at all, silently. Not run. If true,
   `VarkaColumnarRule` should at least record why (the `child.supportsColumnar`
   miss), and the task-185 prediction is scored as wrong.
2. **G25 is dead at the default.** It follows from JVMS 4.7.3 and Janino's own
   check, but should be shown: a 64 KB stage at default settings should produce
   the G24 lines, not "Found too long generated codes".
3. **G26 is logged.** The INFO line exists in the source. Whether it appears in
   task 171's ladder runs (`target/unit-tests.log`) for `project_doConsume_0$`
   past 52-54 entries has not been checked. If it does, the plans that call the
   cliff silent need correcting.
4. **G28**: what an unguarded method with more than 255 parameter slots does
   under Janino (compile error, `ClassFormatError` at load, or verify error),
   and whether WSCG's `NonFatal` catch covers it.
5. **G14 vs G12**: which of the three no-split sites contributes most bytes to
   task 171's consume method. It can be settled from the generated source
   (`debugCodegen`).
6. **G5 is incomplete**: I did not list every physical operator without
   CodegenSupport. `grep -L CodegenSupport` over `exec/**/*Exec.scala` would
   finish the list.
7. **G20**: whether any `With` reaches physical planning in a shape where
   Varka's compiler sees it.
8. **G30**: no honest way to make the executor's compile fail while the
   driver's succeeds was found.
9. **G22/G27 on the Varka side**: whether any admitted shape has been measured
   near the constant-pool cap. The check exists; the margin is unknown.
10. **Janino message wording** in a live stack trace (the wrapping adds
    "Failed to compile: " and the location prefix). Take it from a run, not from
    `strings`.

### Reproducers to build (each should make vanilla say it out loud, at `0ea8414fef5`)
Note for all of them: `Utils.isTesting` turns off G24's fallback and turns
G17/G18's INFO into exceptions. A reproducer of the fallback must run in a
forked JVM without `spark.testing`/`SPARK_TESTING`, or assert the exception.

- R1 (G2): a 101-column projection over a narrow source. Assert that the plan
  loses `*(n)` for ProjectExec and its parent. 100 vs 101 fields, plus a nested
  struct that crosses 100 only through its leaves.
- R2 (G3, the Varka arm): `CACHE` a 101-column table with a date column and
  project `date_add(d, 1)`. Assert that `InMemoryTableScanExec.supportsColumnar`
  is false and that no Varka node is in the plan. 100 columns: Varka present.
- R3 (G24 + G25): a stage over 64 KB at default settings, for example one
  `CaseWhen` or `Concat` with enough children under WSCG (the G12 no-split path).
  Capture ERROR "Failed to compile the generated Java code.", the Janino "Code
  grows beyond 64 KB", and WARN "Whole-stage codegen disabled for plan (id=N)".
  Assert that "Found too long generated codes" is *absent*. The same query with
  `hugeMethodLimit=8000` should produce the INFO instead.
- R4 (G26): task 171's ladder at 48 and 64 entries. Capture INFO "Generated method
  too long to be JIT compiled: ...project_doConsume_0$ is N bytes" from the
  CodeGenerator logger (a `withLogAppender` on
  `org.apache.spark.sql.catalyst.expressions.codegen.CodeGenerator`), and put it
  beside `VarkaSizeLadderJitSuite`'s `PrintCompilation` evidence.
- R5 (G27): a projection with many distinct string or decimal literals, enough to
  pass 65535 pool entries without passing 64 KB per method (G22 spills methods,
  not fields). Expect Janino "has grown past JVM limit of 0xFFFF". Print
  EXPLAIN CODEGEN's "% used" on the rungs below.
- R6 (G4): `FilterExec` with a non-leaf CodegenFallback, for example a Hive UDF,
  `java_method`, or an ImperativeAggregate in an aggregate. The plan loses `*`.
  Varka arm: the decline reason "unsupported predicate".
- R7 (G12/G13/G14): the same expression under WSCG and outside it (with
  `wholeStage=false`), comparing the largest method from `debugCodegen`.
  Shows the three to-do sites in numbers.
- R8 (G16/G17/G18/G19): about 130 nullable int columns so parameter lengths pass
  255. Assert the INFO lines of G17/G18 (or the internal error under test), and
  the silent inlining of G16/G19 from the generated source.
- R9 (G32): force a compile failure outside WSCG (a `Predicate.create` over an
  expression past 64 KB with splitting off, or `CodeGenerator.validParamLength`)
  and capture WARN "Expr codegen error and falling back to interpreter mode".
- R10 (G10, G8): union of 65 children (DEBUG `reason=max-children-exceeded`);
  `stack(51, ...)`: no `*`.
- R11 (G28): a hand-built method of more than 255 slots through `ctx.addNewFunction`,
  to settle unsettled item 4.

## 5. The review, 24 September 2026

**G26 is logged, and the milestone's plans said it was not.** A `spark-submit` job on the stock
Spark 4.2.0 distribution, the ladder's query at 56 entries over generated dates, writes at the
default log level:

    INFO CodeGenerator: Generated method too long to be JIT compiled:
      org.apache.spark.sql.catalyst.expressions.GeneratedClass$GeneratedIteratorForCodegenStage1
      .project_doConsume_0$ is 9513 bytes

The line has been there since 2.4.0 (SPARK-25113, 2018), in `CodeGenerator`'s compile statistics;
on master it lives in `CodeCompiler.scala`. `spark-shell` sets the log level to WARN and does not
show it, and `sc.setLogLevel("INFO")` and `spark.log.level=INFO` did not bring it back in the shell,
which is why none of this milestone's runs saw it: the demo, the JDK checks and the ladder all ran
in a shell or a test harness. So the claim is not that the cliff is silent. It is that Spark notices
the method is past HotSpot's limit, says so once at INFO, and runs it uncompiled anyway; the
`hugeMethodLimit` fallback that could act on it defaults to a value no compiled method can reach
(G25). `PLAN_TASK_171.md`, `PLAN_TASK_172.md`, `PLAN_TASK_192.md`, `PLAN_TASK_193.md`,
`PLAN_TASK_203.md` and this milestone's section 1.1 each carry a dated note pointing here; the
demo's header is corrected.

**G3 contradicts task 185's prediction.** `InMemoryTableScanExec.supportsColumnar` applies the
`maxFields` test to the whole cached relation's schema, not to the columns a query reads, and
`VarkaColumnarRule` rewrites only over a columnar child. So a cached table of more than a hundred
columns gives Varka nothing to fuse, whatever the query reads, and nothing records why. Task 185
registered immunity; its plan must start from this.

**One Varka gap, a row of its own.** A filter with a nondeterministic conjunct is declined without
a recorded reason (`VarkaExpressionCompiler.scala` `predicateOnce`); every other decline records
one. It becomes row 206.

**What remains for the row.** The reproducers of section 4, each making vanilla say it out loud
with the Spark revision named, and the Varka column checked entry by entry against them. The
census is complete as a reading of the source; it is not yet backed by committed reproducers.

## 6. The reproducers, as they land

* **G26, 24 September 2026.** `VarkaCodegenCliffLogSuite` runs the size ladder's projection on
  vanilla Spark and captures `CodeGenerator`'s log: at 56 entries it logs "Generated method too
  long to be JIT compiled" for the consume method, with a size past 8000 bytes, and at 48 entries
  it logs no such line. It accepts INFO or WARN, since SPARK-59774 (apache/spark#59020) proposes
  raising the line to a warning that names the remedy.
* **The Varka gap of section 5, closed the same day (row 206).** A filter with a nondeterministic
  conjunct now declines every conjunct with the reason, and the fusion report shows each conjunct's
  reason even when none fuses, where it used to print one line saying none was eligible.
* **Eleven more, 25 September 2026.** `VarkaCodegenGiveUpSuite`, one test per entry, on this
  fork's Spark at the branch's base:
  * **G1**: with `spark.sql.codegen.wholeStage` off, the plan has no stage at all.
  * **G2**: a projection of 100 columns stays in its stage and one of 101 leaves it, and a single
    struct column of 101 leaves leaves it too: the count is of nested leaf fields.
  * **G4**: a filter over a deterministic non-leaf `CodegenFallback` expression - one the test
    defines, an identity with no generated code - leaves the stage and answers as the plain
    filter does; Varka declines the same conjunct with an "unsupported" reason.
  * **G8**: `stack` of 50 rows stays in the stage, of 51 leaves it.
  * **G10**: a union of 64 children stays in the stage, of 65 leaves it. Spark's DEBUG line
    naming the reason did not reach the suite's appender, so the plan is what is asserted.
  * **G17 and G18**: a common subexpression, and a `sum`, over 130 nullable int columns read from
    a shuffle, whose split functions would need 260 parameter slots. Under `spark.testing` both
    refusals are internal errors ("Failed to split subexpression code", "Failed to split aggregate
    code"), which is what is asserted; in production they are the INFO lines of section 2. The
    reproducer needs `maxFields` raised, since past 100 input fields the operator leaves the stage
    by G2 before any split is tried.
  * **G24**: a stage with one `CASE WHEN` of 3000 branches, which a stage does not split (G12),
    fails to compile past 64KB; under test the failure is thrown, and "Found too long generated
    codes" is not logged.
  * **G25**: the size ladder at 56 entries, a method past 8000 bytes, logs "Found too long
    generated codes" and falls back only with `hugeMethodLimit` set to 8000; at its default it
    says nothing.
  * **G28**, settling item 4 of section 4: Janino refuses a method past 255 parameter slots at
    compile time - 254 int parameters compile, 255 fail with "Method "f" has too many parameters
    (256)", the slots counting `this`. It is an ordinary `CompileException`, so inside a stage it
    takes G24's path and is covered by its `NonFatal` catch.
  * **G32**: outside a stage, with splitting off, a projection of 3000 entries fails to compile
    and the projection factory logs WARN "Expr codegen error and falling back to interpreter
    mode" and answers from the interpreted projection.

  Left: G3 is task 185's first step by its own plan; G27 (the constant pool), G12 to G14 in
  numbers, and the silent inlining of G16 and G19 have no reproducer yet.
