# Reading for milestone 8: what SQLancer's test oracles offer Varka's fuzzers

Read on 1 November 2026 for rows 352, 369, 386 and 541 of `m8/PLAN.md` and the lesson of row 342
(`VARKA-342.md` 5, "The integration fixes"): SQLancer is the reference implementation of the
metamorphic oracles behind Varka's partition and pivot checks. What do its oracles, its generators,
its expected errors and its reducers offer that Varka does not have? Paths are relative to
`/home/max/proj/sqlancer/src/sqlancer/` at commit `1d797ee5363` (21 October 2026), MIT, cloned
for reading only; `docs/` is the repository's. Varka's side was read at this worktree's base,
`5c660201b35`. **[V]** means the author read the code; **[I]** means inferred. Nothing was built
or run, and no code is copied. `m7/READING.md` 254-274 (PQS, NoREC, TLP and QPG from the papers),
`m5/PLAN.md` 3058-3069 (TLP needs no second engine) and `m9/SCOPE.md` 4528 (TLP's aggregate,
GROUP BY, DISTINCT and HAVING oracles) are not repeated.

## 1. What SQLancer is

* **A Java fuzzer with one generator per DBMS and a set of oracles shared across them** [V]:
  TLP (`common/oracle/TLPWhereOracle.java`), NoREC (`NoRECOracle.java`), PQS
  (`PivotedQuerySynthesisBase.java`), EET, the equivalent-expression transformation of Jiang and
  Su, OSDI 2024 (`EETOracle.java`, `EETTransformer.java`), CERT on cardinality estimates
  (`CERTOracle.java`), DQE across SELECT, UPDATE and DELETE (`DQEBase.java`) and CODDTest's
  constant folding (`CODDTestBase.java`, built for SQLite only in
  `sqlite3/oracle/SQLite3CODDTestOracle.java`); DQP, the same query under each optimizer setting,
  for MariaDB only (`mariadb/oracle/MariaDBDQPOracle.java`). QPG steers generation
  (`ProviderAdapter.java`).
* **Its Spark target is thin** [V]. Added on 7 December 2025 and touched six times since, by an
  error-list fix and refactors; `TLPWhere` is the only oracle (`spark/SparkOptions.java:23-36`),
  `INSERT` the only action (`spark/SparkProvider.java:30-31`), over the Thrift server's JDBC driver,
  and every session sets `spark.sql.ansi.enabled=false` so that the generator's casts such as
  BOOLEAN to DATE pass (`SparkProvider.java:111-114`). Its expected errors are substrings that
  include `AnalysisException`, `Overflow` and `Divide by zero` (`spark/SparkErrors.java:13-40`). So
  SQLancer's own Spark run never compares an ANSI raise, and skips any query that fails analysis.
* **Every oracle discards a test on an expected error** [V]. `ComparatorHelper` turns an expected
  error into `IgnoreMeException` (`ComparatorHelper.java:57-58`, `:73-79`), as NoREC does on a
  count of -1 (`NoRECOracle.java:148-150`) and PQS on a null result
  (`PivotedQuerySynthesisBase.java:66-70`).
  An error is a reason to stop looking, never a value to compare.

## 2. What the oracles find that the two-engine differential cannot (question 1)

The differential holds Varka, its kernels and its fallback alike, to Spark's default session, so a
bug the kernel and the fallback share still shows as a disagreement **[I]**. It is blind in four
places, and SQLancer's oracles reach two of them.

1. **What both sessions share.** The parser, the analyzer, the optimizer and the cache serve both
   sessions, so a bug there gives both the same wrong answer. The pivot check, which is PQS, has
   already found one: the cache's statistics leave NaN out (SPARK-58482), so the rectified filter
   loses the pivot row on both engines (`VarkaSparkDifferential.scala:44-50`) **[V]**. TLP and
   NoREC find the same class. These are facts about Spark, to be counted and reported upstream,
   as 352's 2.4 item 4 already rules.
2. **Spark's own two engines.** Row 386 exists because whole-stage codegen and the interpreted
   filter answer one query differently, and row 541 because the ghost fallback's
   `Predicate.create` evaluates a common subexpression earlier than codegen would, with
   SPARK-60392 the codegen-off half of the same mechanism. NoREC's premise is that a DBMS cannot
   turn its optimizations off (`m7/READING.md` 256-257); Spark can, with
   `spark.sql.codegen.wholeStage` and `spark.sql.codegen.factoryMode` (`SQLConf.scala:2988`,
   `:3189`) **[V]**. A third run of a disagreeing case on the row engine with both off classifies
   it on the spot: Varka equal to interpreted Spark and unequal to the default is the 386 and 541
   class, in which Varka must follow the default; Varka unequal to both is Varka's own bug **[I]**.
   SQLancer's DQP oracle does this for MariaDB, the same query under each optimizer setting
   compared (`mariadb/oracle/MariaDBDQPOracle.java:66-79`) **[V]**; here it is applied to the
   reference rather than to Varka.
3. **Answers hidden behind errors.** When both engines raise, only the error class is compared,
   and the 342 lesson is the result: with `x2` zero in three of ten picks, most ANSI compositions
   that draw `x / x2` compare one error and no rows. SQLancer is blinder here (section 1, last
   point), and 352's 2.4 raise rule, which compares the parts' raises with each other, has no
   counterpart in it **[V]**. The part SQLancer does not have and Varka lacks too: after a raise
   both engines share, compare the rows that do not raise (section 3, point 3).
4. **Shapes never drawn.** Row 541's done-when asks that "the Spark fuzzer's nested draws reach the
   shape": an earlier conjunct false or NULL on a row, a later one holding a subexpression twice
   that raises there. EET is the oracle that builds such shapes on purpose. Its seven rules
   (`EETTransformer.java:215-300`) wrap an expression in an always-true or always-false predicate,
   `p OR NOT p OR p IS NULL` and `p AND NOT p AND p IS NOT NULL` (`EETTransformer.java:194-201`), or
   in a `CASE` whose dead arm is a random expression of the same type (rules 3 and 4) or a copy of
   the live one (rules 5 and 6) **[V]**. Under Spark's lazy `CASE` a dead arm that raises must not
   raise; a lane kernel evaluates every arm and masks, so a dead arm that raises is aimed at Varka's
   guard and arm-chain machinery (`VARKA-342.md` 5, "A shared guarded node") and at 541's hoisting
   **[I]**. Two traps at the Spark level **[V]**: `BooleanSimplification` folds `a AND NOT a` to
   `If(IsNull(a), null, false)` (`optimizer/expressions.scala:416-424`), which still evaluates `a`,
   and `SimplifyConditionals` folds a `CASE` or `IF` whose arms are equal to that arm (`:721`,
   `:757`), which removes rules 5 and 6 before Varka sees them. So the duplicate has to be one the
   optimizer keeps, such as `S` under two arms of an `OR` within one conjunct, or the rules have to
   be excluded on both sessions. The IR fuzzer's untaken-arm variant
   (`VarkaIrFuzzSuite.scala:80-84`) is EET at the IR level, checked against the reference evaluator;
   the Spark-level fuzzer has nothing like it.

**TLP and NoREC over fused filters and projections.** TLP has been in the fuzzer since VARKA-262
(`partitionBroken`, `VarkaSparkFuzz.scala:486-505`), and SQLancer's version is the same check
without a raise rule (`TLPWhereOracle.java:166-194`) **[V]**. One gap SQLancer shows by contrast:
the fuzzer runs its three parts at the default optimizer, so `NOT (p)` and `(p) IS NULL` arrive as
tests on the columns and Varka's negation and null tests are not reached as written.
`VarkaPartitionOracle.REWRITES` excludes the three rewriting rules
(`VarkaPartitionOracle.java:55-63`) and row 27 and row 369 use it; `VarkaSparkDifferential` and
`VarkaSparkFuzz` do not **[V]**. NoREC is 369's count arm. SQLancer's unoptimized form is
`SUM(CAST(p IS NOT NULL AND p AS INT))` over a projection
(`duckdb/gen/DuckDBExpressionGenerator.java:508-516`) **[V]**, so it evaluates `p` on every row
and holds `p` twice. Over Spark that pair also decides, on the row engine alone, whether a
filter short-circuits where a projection does not: 386's filter answers a row its projection
raises on **[I]**. 369 is safe-pool only (`VARKA-369.md` 2.4 item 7), so this
matters only when its hostile arm comes; then a raise that differs between a filter and its
projection on both engines is Spark's fact, as in 352's 2.4 item 4.

## 3. Steering generation, and the error-heavy draw (question 2)

1. **QPG mutates the database state, not the query** [V]: after `QPGMaxMutationInterval` queries
   with no new `EXPLAIN` plan it applies a data or schema mutator chosen by a running reward
   (`ProviderAdapter.java:112-155`, `:157-190`; `docs/QueryPlanGuidance.md:2`). Row 280 measured
   the query-side form at the IR level and did not keep it (`m7/PLAN.md` row 280). The state-side
   form at the Spark level would key on per-batch runtime verdicts (fused, declined and why),
   which depend on the data. Its value is unmeasured and 280's null result counts against it; no
   row is proposed.
2. **Values come back from a cache** [V]: one draw in three reuses a constant drawn earlier
   (`Randomly.java:17-23`, `:105-107`), so a predicate's literal matches a stored value and a filter
   selects rows. Varka's pivot rectification does this more strongly
   (`VarkaSparkDifferential.scala:199-220`).
3. **Few erroring queries, by construction or by tolerance.** SQLancer types its expressions
   (`common/gen/TypedExpressionGenerator.java`), retries a failed setup statement until one
   succeeds (`SparkProvider.java:57-63`), turns off the generation of features with known bugs
   (`postgres/gen/PostgresExpressionGenerator.java:841`, `tidb/TiDBBugs.java`), and otherwise
   accepts the error and moves on. It prints the share of statements that succeeded
   (`Main.java:867-874`) and asserts nothing **[V]**. Varka already has the better half: a safe pool
   kept inside every guard and ANSI check, and hostile rows only in the full pool, introduced
   because an all-hostile pool made 70% of cases fail on both engines
   (`VarkaSparkFuzz.scala:161-169`).
   The 342 gap is that the floating columns are derived from `l`, `l2`, `i` and `d2` by
   `pmod(..., 10)` in `fixtureRows` (`VarkaSparkFuzz.scala:109-129`), outside the pool, so the safe
   pool's promise does not hold for `x2` **[V]**. Three fixes, smallest first: (a) the safe pool
   keeps the divisor columns off the zero picks; (b) like SQLancer's ratio, but asserted: a ceiling
   on the safe pool's share of compositions that errored on both sides, beside the existing
   `forcedApplied` floor (`VarkaSparkFuzzSuite.scala:228-230`, `:246-250`); (c) after a raise both
   engines share, drop the raising rows (the row engine on each row alone, as `errorRerun=eachRow`
   already does) and compare the query over the rest, the construction of 352's 2.6 item 2 used
   for every raise **[I]**. (a) moves the seeded draws, which 352 item 12 lists.

## 4. Expected errors (question 3)

SQLancer's list is one set per DBMS and statement kind, matched as a substring or regex of the
message anywhere in the cause chain (`common/query/ExpectedErrors.java:95-110`,
`common/query/SQLQueryAdapter.java:155-167`), with entries for known bugs gated by a flag that
names the issue (`tidb/TiDBErrors.java:47-66`). Nothing reports an entry that never fires or a
fixed bug whose flag is still on **[V]**. `expected_errors.tsv` is stricter on every axis: an error
class rather than text, per side and mode, a reason for each entry, a raise on one side still a
disagreement, and a warning for an entry a run never meets (`VarkaSparkFuzzSuite.scala:242`);
`known_failures.tsv` fails a stale entry (`VarkaKnownFailures.java:36`, `:113`). One idea is worth
taking. SQLancer gates a known bug by its shape, so its fuzzer keeps running around it. Varka
gates by draw (lane, seed, iteration), which a nightly's new seed cannot match, and for 386 the
nightly turns all nested draws off instead. The classification of section 2 point 2 is a gate by
shape: a disagreement in which Varka matches interpreted Spark can be counted against 386 and
541 rather than fail the run, so the nested draws can come back before the upstream decision. The
owner decides whether a known class may pass a nightly. The families column stays documentation
(the file's header); making it decide would also fail correct raises from nested trees, which have
no family **[I]**.

## 5. Reducing a failing case (question 4)

The two are the same in kind [V]: ddmin over statements (`StatementReducer.java:59-113`) against
ddmin over outputs, conjuncts and rows with tree moves in between (`VarkaSparkFuzz.scala:521-565`);
a reduced case must show the same failure (`common/oracle/UnexpectedErrorReproducer.java:60-72`
compares the message, Varka's `still` the kind). Reduction is off by default in SQLancer and built
for only some oracles (`docs/testCaseReduction.md:29-36`); Varka shrinks every disagreement. Two
things SQLancer has that Varka does not:

* **Constants simplified** (`transformations/SimplifyConstant.java:16-17`,
  `RoundDoubleConstant.java:9-10`). Varka's tree moves already replace a node by its type's first
  literal (`VarkaCatalystGrammar.java:347-364`), but the fixture's cells are never simplified, only
  whole rows dropped. A pass that sets each cell to its typed NULL or the safe pool's first value
  would shorten reproducers **[I]**.
* **A reducer that keeps the oracle's relation** (`TransformationReducer.java:12-21`): it disables
  EET's rewrite sites rather than editing the expression, so every candidate stays equivalent.
  Varka's analogue is the pivot. A conjunct under a pivot is not shrunk inside
  (`VarkaSparkFuzz.scala:551`), because a smaller conjunct may take another value on the pivot;
  rectifying it again after each move, as EET replays its record, would keep the invariant and let
  the move through **[I]**.

## 6. Not worth taking

Result sets compared as sets after a size check, which loses multiplicity
(`ComparatorHelper.java:108-112`); trailing zeros stripped and a 0.1% double tolerance
(`:21-37`, `:63`), against Varka's bit-for-bit answers. CERT needs cardinality estimates and
reports performance; DQE needs UPDATE and DELETE. CODDTest replaces an expression by its evaluated
constant (`SQLite3CODDTestOracle.java:127-138`), and at the Spark level `ConstantFolding` would
hand the constant to Spark's `eval` before Varka saw it, which the IR fuzzer's literals-in-columns
variant already covers from the other side. QPG's steering (section 3 point 1). TLP's aggregate
oracles stay `m9/SCOPE.md` 4528; DuckDB's form, `AVG` as `SUM` over `COUNT` across the three parts
(`duckdb/test/DuckDBQueryPartitioningAggregateTester.java:120-145`), is its reference when taken.

## 7. Proposed actions

1. **Row 352, edit to 2.4**: half the partition checks (the full pool's, say) run with
   `VarkaPartitionOracle.REWRITES` excluded on both sessions, so `NOT (p)` and `(p) IS NULL` reach
   Varka as written; a disagreement counted per setting. Small.
2. **Row 352, edit to 2.5** (or a small PR before it): the safe pool keeps `x2` and `f2` off the
   zero picks, and the fuzzer asserts a ceiling on the safe pool's both-errored share (section 3,
   (a) and (b)). Small; moves the seeded draws.
3. **Row 352, edit to 2.4 or 2.6**: a raise both engines share is followed by the comparison over
   the non-raising rows (section 3, (c)). Small to medium.
4. **New row (number for the lead), serving 386 and 541**: rerun a disagreeing case on the row
   engine with whole-stage codegen and the codegen factory off, and name in the failure and the
   reproducer whether Varka matches interpreted Spark; with the owner's word, that class counts
   against 386 and 541 instead of failing the nightly. Small to medium.
5. **New row, or 541's own work, serving 541**: an EET-style production in `VarkaCatalystGrammar`
   that puts a subexpression drawn to raise twice in a later conjunct, in forms the optimizer keeps
   (section 2 point 4), and dead `CASE` arms that raise; done when 541's reproducer shape is drawn
   at the nightly's seeds. Medium.
6. **Row 369, edit**: name SQLancer's projection form as the NoREC pair, and state that a raise
   differing between the filter and the projection on both engines is Spark's fact, for when the
   hostile arm lands. Small.
7. **m9 scope item**: the shrinker re-rectifies a conjunct against its pivot after each move, and
   simplifies the fixture's cells (section 5). Small.

This sharpens rather than contradicts. `m5/PLAN.md` 3064's "TLP needs no second engine" is true
but no longer the point: with the second engine in place, a metamorphic oracle earns its keep in
the four blind spots of section 2, and SQLancer's own Spark run, ANSI off and errors skipped, is
weaker than Varka's fuzzer at all four. Not read: the per-DBMS generators beyond those cited, the
EET oracle's reproducer, DQP beyond its MariaDB check, and the papers themselves beyond
`m7/READING.md`.
