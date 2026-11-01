# VARKA-333: the choice and null shapes on every existing type

*Row 333 of `m8/PLAN.md`, wave 0. Planned on 16 October 2026 at `8a50f61a368`, from `m9/SCOPE.md`
items 55 and 94, the census's conditional and predicate lines and `m8/PLAN.md` 2.9.*

## 1. Where this came from

The choice expressions are the census's widest gap on the types Varka already has: `coalesce`,
`if`, `CASE WHEN`, `greatest`, `least`, `IN` and the rewritten conditionals `nullif`, `nvl2`,
`zeroifnull`, `nullifzero` and `between` admit all six existing types, and the census
(`m8/census.tsv`) shows most of them fused for `DATE` alone, some for `BIGINT`, and none for `INT`.
Two scope items name the causes: item 55 (25 September 2026) found that `if(c, 1, 0)` and
`if(c, i, i + 1)` over an int column decline, because `compileNode`'s value leaves have no int;
item 94 (13 October 2026) found that `CASE WHEN c THEN e END` fuses for none of 49 projection rows,
because the missing ELSE is a null literal. The row adds identity substitution's named cases for
`greatest` and `least` (DuckDB's sentinel bug, `m8/PLAN.md` 2.9) and asks what plan `nullif`
reaches after the `With` rewrite before it counts as one shape. It needs nothing new from the
lanes, which is why it is wave 0.

## 2. The admission check, done

What reading settled, at `8a50f61a368` (paths under `sql/catalyst/src/main/java/.../codegen/`):

1. **The value leaves.** Date column and literal: `VarkaNodeCompiler.java` 171 to 190; year-month
   interval: `VarkaIntervalCompiler.java` 96 to 103; the long lane (`BIGINT`, `TIME`, day-time
   interval): `VarkaTimeCompiler.java` 160 to 168. **`INT` has none**, on purpose: `VARKA-122.md`
   3.2 keeps an int out of `compileNode`, since the IR's lanes carry no type and an int leaf
   there would admit `date_add(d, i)`'s offset as a date. Comparisons take an int through their own
   `intLeaf` (`VarkaConditionCompiler.java` 736 to 758), and `date_add`'s offset through
   `compileOffset` (`VarkaChronoCompiler.java` 395 to 420), which avoids `compileNode` by design.
2. **Each shape's gate.**

   * `If`, `CASE` with ELSE - where: `VarkaConditionCompiler` 93 to 142, branches through
     `compileNode`; today: every type but `INT`
   * `CASE` without ELSE - where: `VarkaConditionCompiler` 94 to 97; today: declined: "dense body's
     all-valid invariant"
   * `Coalesce` - where: `VarkaConditionCompiler` 249 to 271, every operand before the last a
     `ColumnRef`; today: every type but `INT`
   * `Greatest`, `Least` - where: `VarkaNodeCompiler` 208 to 211, `foldPick`
     (`VarkaConditionCompiler` 274 to 300); today: every type but `INT`
   * `IN`, `InSet` - where: `VarkaConditionCompiler` 334 to 350 and 409 to 411, `isDateOrInterval`;
     today: `DATE` and year-month only
   * `between` - where: becomes `And(GE, LE)`, comparisons through `intLeaf`; today: should compile
     on all six; no row

   Coverage rows exist for `if` over `DATE` and `BIGINT`, `CASE` with ELSE over `DATE`, `BIGINT`
   and day-time, `coalesce(d, d2)`, `greatest` over `DATE`, `BIGINT` and `TIME`, `least` over
   `DATE` and `BIGINT`, and `IN` over `DATE`; none for `nullif`, `nvl2`, `zeroifnull`,
   `nullifzero` or `between`.
3. **What the rewritten conditionals reach** (Catalyst, `expressions/nullExpressions.scala` and
   `Between.scala`): `nullif(a, b)` becomes `With(a) { r => NullIfResult(r = b, r) }`, then
   `If(r = b, null, r)` - **an `If` with a typed null-literal branch**, the same blocker as item
   94; `nullifzero(x)` is `If(x = 0, null, x)`; `zeroifnull(x)` is `Nvl(x, 0)`, then
   `Coalesce(x, 0)`, which needs an int literal as the last operand; `nvl2(a, b, c)` is
   `If(IsNotNull(a), b, c)`; `between(x, lo, hi)` is `With(x) { r => And(r >= lo, r <= hi) }`.
   `RewriteWithExpression` (`optimizer/RewriteWithExpression.scala` 45, `canSubstitute` 270 to
   281) inlines a definition referenced once or cheap and deterministic - a column is - and
   otherwise evaluates it in a `Project` below, so the `If` reads an attribute. No Varka compiler
   mentions `With` or `CommonExpressionRef`: it relies on the optimizer removing them.
4. **A null-from-valid node has a home already.** `Analysis.nullsFromValidInputs` (378 to 383)
   marks a kernel with a node that turns valid inputs into a null output (non-ANSI `MakeDate`, and
   a `NULL`-mode `IntArith`), and such a kernel is dispatched to the masked methods for every batch.
   A null-literal branch is exactly such a node, so the "all-valid invariant" decline of item 2
   has a cure that exists, not a new mechanism.
5. **`greatest` and `least` use no sentinel**: the walk substitutes by a blend
   (`aSel = a.blend(b, ~validA)`, `varka/VarkaVectorWalk.java` 744 to 785), so DuckDB's bug cannot
   happen here, and the `MIN_VALUE` and `MAX_VALUE` cases are regression tests, not a fix.
6. **Item 55's done-when is stale on one clause**: it asks that `date_add(d, i)` over an int `i`
   still decline, and `coverage.json` now fuses `date_add(d, i)` through `compileOffset`. The row's
   own wording, "`date_add(d, i)`'s offset rule unchanged", is the current form: the offset keeps
   its own path, and no int reaches `compileNode`.

**What still needs a run, and what admits each shape.** With a catalyst test build, the emitter
tool says which shapes compile today and which decline, and why:

    dev/varka_emit.sh "if(d < d2, i, i + 1)" "coalesce(i, 0)" "greatest(i, 7)" \
      "i IN (1, 2)" "l IN (1, 2)" "t IN (t2, t)" "dt IN (dt2, dt)" \
      "CASE WHEN d < d2 THEN d END" "i BETWEEN 1 AND 9" "least(t, t2)" "least(dt, dt2)" \
      --columns d:date,d2:date,i:int,l:bigint,t:time,t2:time,dt:dt,dt2:dt

A shape that compiles needs a coverage row only; one that declines with the reason item 2 predicts
needs its arm; any other reason is a finding recorded here before code. `VarkaEmitDump` resolves
but does not run the optimizer, so the rewritten conditionals are checked in a session instead: in
a `SharedSparkSession` test (or `spark-shell`) over a table with an int column `i`,

    spark.sql("SELECT nullif(i, 3), nullif(i * 2, 3), i BETWEEN 1 AND 9 FROM t")
      .queryExecution.optimizedPlan

must show `If(i = 3, null, i)`, the second as a `Project` computing `i * 2` below an `If` over its
attribute (or inlined, if `canSubstitute` finds it cheap), and `between` inlined. The row counts
`nullif` as one shape only if both forms compile; if the `Project` form splits the kernel, that is
recorded and the coverage row names the form that fuses.

### 2.1 The run, refreshed on 1 November 2026 at `f1bd3d23b99`

Section 2 was read before the floating, `TIMESTAMP_NTZ`, `BOOLEAN`-output and 64-bit decimal work
landed, so its run was taken on current master before any code. One `dev/varka_emit.sh` call over
every shape on the six types (columns `d`, `i`, `l`, `t`, `dt`, `ym` and a second of each), and the
optimized plans through `VarkaHarnessPlans.projection`, which runs `SparkOptimizer` as a session
does (a scratch probe, not committed). What compiles today, per type in the order DATE, INT,
BIGINT, TIME, day-time, year-month:

* `if`, `CASE` with ELSE, `coalesce`, `greatest`, `least`, `nvl2`: fused on every type but `INT`,
  which declines with "non-date column of type int: i", the reason section 2.1 predicts. The long
  lane's three fuse as a second kernel (`KernelOutput`), as they did. `zeroifnull(l)` (a
  `coalesce(l, 0)`) and `positive` over `BIGINT`, day-time and year-month fuse; over `INT` both
  decline the same way.
* `CASE` without ELSE: declined on all six, "CASE WHEN without an ELSE branch", as predicted;
  `TINYINT` and `SMALLINT`, which landed since (row 329), decline the same way.
* `if(c, x, null)`, `nullifzero`: the dump shows the analyzer's `CAST(NULL AS t)`, which the
  optimizer folds; in the optimized plan `if(d < d2, i, null)` is `If(d < d2, i, null)` and
  `nullifzero(i)` is `If(i = 0, null, i)`, so both need the typed null leaf, on every type.
* `nullif`, `between`: the dump shows them as `With`, because `VarkaEmitDump` (and
  `VarkaSqlResolve`, which the coverage suite resolves through) runs the analyzer only. Optimized,
  `nullif(i, 3)` and `nullif(i, i2)` are `If(i = 3, null, i)` and `If(i = i2, null, i)`, and
  `i BETWEEN 1 AND 9` is `i >= 1 AND i <= 9`, inlined. Over a computed operand, `nullif(i * 2, 3)`
  and `(i * 2) BETWEEN 1 AND 9` do not stay one projection: `RewriteWithExpression` evaluates the
  operand in a `Project` below, so the row's shape is the column form and the computed form is two
  projections, recorded here and not claimed as one shape (risk 3).
* `IN`: section 2's command used column elements (`t IN (t2, t)`), which decline on every type,
  `DATE` included, with "IN list has a null or non-literal element"; that is not the row's shape.
  Over literals, `DATE` and year-month fuse, and `INT`, `BIGINT` (both list lengths), `TIME` and
  day-time decline as "unsupported predicate", the gate of section 2.1. Row 497 (one `IN` arm for
  every lane, being built beside this row) admits all four, so this row's `IN` part is that
  branch's merge and its rows, not a gate of its own.
* `nvl2(i2, i, i2)` is `If(IsNotNull(i2), i, i2)` and declines on the branch `i`, not the
  condition; `zeroifnull(i)` is `coalesce(i, 0)`, `nvl(i, i2)` `coalesce(i, i2)`.
* `positive(i)` is the bare column `i` after the optimizer (`UnaryPositive` is replaced), so
  `positive` never reaches Varka as a node in a plan and its census entry is a relabel, as
  `known_not_null` is.
* `date_add(d, i)` fuses through `compileOffset`, unchanged.

So every decline is one section 2 predicts, and no new reason appeared: the int branches need the
typed arm (3.1) and the null branches the typed null leaf (3.2). One correction to section 2:
the `IN` row of 2.2 named `DATE` and year-month as today's types, which holds, but the row's
command tested column lists, which no type fuses.

## 3. The design

### 3.1 Typed arms at the any-type positions (item 55)

Item 55's design, not a wider leaf: the branches of `If` and `CASE`, the operands of `Coalesce`,
and those of `Greatest` and `Least` each get a typed arm, `typedValue(e, type)`, which admits an
int column (`columnRef(br, inputs, LaneType.INT)`) or an int literal (`intSlot`) **only when the
whole expression's type is `IntegerType`**, and otherwise goes to `compileNode` as today. Int
arithmetic in a branch (`i + 1`) goes through the existing `intArith`. `compileNode` and
`compileOffset` do not change, so `date_add(d, i)` keeps its path. A whole output that reads no
column (a bare constant) is still refused, as item 55 says.

### 3.2 A typed null leaf (item 94, `nullif`, `nullifzero`)

A new IR leaf, `NullValue(LaneType lane)`: a value whose validity is all false and whose vector is
zero. The compiler builds it for `CASE` without ELSE (the missing branch), and for a
`Literal(null, t)` in a branch position when `t` is one of the six types. `Analysis` sets
`nullsFromValidInputs` for any kernel holding one, so every batch takes the masked methods, whose
`IfElse` validity `(kT & vThen) | (~kT & vElse)` (`VarkaVectorWalk` 359) already gives the right
answer with `vElse` zero. The reference evaluator, the IR grammar and `VarkaCanonicalIr` gain the
leaf; `VarkaRangeAnalysis` answers the empty range.

### 3.3 `IN` on the long lane and on `INT`

`isDateOrInterval` widened to the six types for `IN` and `InSet`, with an int column through the
typed arm and the long lane through its own leaf. Whether both `IN` lowerings (the two-literal
compare chain and the eleven-literal range form) exist at the long lane is section 2's run
(`l IN (1, 2)`); if the range form is int-only, the long lane takes the compare chain up to the
cap and declines above it, with the reason, and the range form at the long lane is recorded in
section 9 for later.

### 3.4 The two pass-through entries

The census assigns two more entries to this row: `positive` (`UnaryPositive`, the identity, over
the numeric and interval types) and `known_not_null` (`KnownNotNull`, an optimizer marker whose
value is its child's). Both compile to their child, with no node; each gets its coverage rows,
and `KnownNotNull` keeps its child's validity, since the marker is a promise the kernel does not
rely on.

### 3.5 What is deliberately unchanged

* `compileNode`'s leaves and `compileOffset`: no int value leaf (`VARKA-122.md` 3.2).
* The comparison family: its `intLeaf` is reused, not changed.
* `IS NULL` over a predicate (334), null-safe equality (336), the new types' choice shapes
  (329, 331, 338, 341, 348, 359 each add theirs).
* The emitter's `greatest` and `least`: blends, no sentinel; only tests are added.

### 3.6 Registered op counts

Read with `dev/varka_emit.sh --table` after the build and asserted by the emitter suite's op-count
tests: `if(c, i, i2)` over int is expected to equal `if(c, d, d2)` over dates (the same `IfElse`
on the same lane); `CASE WHEN c THEN d END` one fewer input load than the ELSE form and the masked
path's validity blend; `IN` over `BIGINT` with two literals two `LongVector` compares and an `OR`.
Any shape whose count differs from its date twin by more than the leaf is a finding.

## 4. Files

* `.../codegen/VarkaConditionCompiler.java`: the typed arms in `ifElse`, `caseWhen`,
  `compileCoalesce`, `foldPick`; the `IN` gate; the null branch
* `.../codegen/VarkaNodeCompiler.java`: `typedValue`, the int column and literal at a value position
* `.../codegen/varka/VarkaVectorIR.java`: `NullValue`
* `.../codegen/varka/Analysis.java`, `Slots.java`, `VarkaVectorWalk.java`: `NullValue`'s analysis
  (`nullsFromValidInputs`) and emission
* `.../codegen/varka/VarkaRangeAnalysis.java`: the empty range for `NullValue`
* test: `VarkaReference.java`, `VarkaIrGrammar.java`, `VarkaCanonicalIr.java`: the leaf in the
  oracle, the fuzzer and the canonical form
* test: `VarkaCoverageSuite.scala`; `sql/varka/coverage.json`, `docs/sql-varka.md`: the rows,
  regenerated
* `sql/varka/emitted_bytes.json`: new rows only
* `sql/varka/plans/m8/census_curated.txt`, `census.tsv`: the fused types the rows prove

## 5. Tests, and what each is for

1. **A coverage row per shape and per type**, over the six types, with `VARKA-138.md`'s poisoned
   null lanes and both validity fills: the row engine is the oracle.
2. **`greatest` and `least` at `MIN_VALUE` and `MAX_VALUE` beside nulls** (`Int.MinValue`,
   `Long.MaxValue`, the first and last `DATE`, `TIME` and both interval ends): catches a sentinel
   reintroduced by any later lowering.
3. **`CASE` without ELSE on an all-valid batch** returns null wherever no WHEN holds: catches a
   kernel dispatched to the dense body despite `nullsFromValidInputs`.
4. **`date_add(d, i)` unchanged**: its bytes in the oracle do not move; catches an int leaf leaking
   into `compileOffset`'s path.
5. **`if(c, i, d)` cannot exist** (Spark's typing forbids it); the typed arm is tested instead
   with a date-typed `If` whose branch is an int column cast, which must decline.
6. **`nullif` in both plan forms** of section 2.
7. **The IR fuzzer draws `NullValue`** at every lane; the reference evaluator agrees.

No existing fixture moves; the bytes oracle gains rows.

## 6. The measurement

None of throughput is claimed by this row: the shapes are the same nodes on lanes that are already
measured. One cost is new, the masked dispatch of a kernel holding `NullValue`.

### 6.1 Predictions, registered before the run

1. **`CASE WHEN c THEN d END` costs no more per row than `CASE WHEN c THEN d ELSE d2 END` on a
   batch with nulls**, and up to the masked body's cost over the dense body's on an all-valid
   batch, the price `nullsFromValidInputs` already charges `MakeDate`; measured once in
   `VarkaEmitterParityBenchmark`'s choice section, both forms named, at both widths.
2. **The census moves by at most 71 pairs**, the existing-type pairs of this row's entries not
   fused today: `coalesce` 5, `if` 4, `ifnull` 5, `nvl` 5, `nvl2` 6, `when` 3, `nullif` 6,
   `nullifzero` 2, `zeroifnull` 2, `between` 6, `greatest` 3, `least` 4, `in` 5, `in_set` 5,
   `positive` 4 and `known_not_null` 6, less the year-month `in` and `in_set` pairs if 328's rows
   land first. No entry becomes fully fused, since every one admits a new type.
3. **The coverage differential's added test time is under 20 s** for some seventy new rows:
   estimated at 10 s (one kernel compile and a few batches a row) and doubled by the calibration
   table's most common miss, VARKA-243's ratio of about 2. The table's coverage-row lines run the
   other way (VARKA-317's came in far under), so a result well below is expected, and is scored
   either way.

## 7. Risks

1. **An int leaf reaching a date position.** The typed arm checks the whole expression's type;
   test 5 and test 4 hold it.
2. **`NullValue` in the dense body.** If any path skips `nullsFromValidInputs`, an all-valid batch
   returns a value where Spark returns null; test 3.
3. **The `With` rewrite's two forms.** The `Project` form may fuse as two kernels or as one; the
   row records which and does not claim one shape where the plan has two.
4. **`IN` at the long lane past the cap.** The decline is explicit, with its reason, and tested.

## 8. Sequencing

1. This plan; section 2's run of `dev/varka_emit.sh` and of the optimized plans, recorded here.
2. The typed arms and their coverage rows over `INT` (item 55).
3. `NullValue`, `CASE` without ELSE, `nullif` and `nullifzero` (item 94).
4. `IN` on the remaining types; `between`, `nvl2` and `zeroifnull` rows.
5. The census regenerated, the measurement of 6.1 prediction 1, the predictions scored.

Explicitly out of this row: any new type's choice shapes, `IS NULL` over a predicate (334),
`<=>` (336), and an int value leaf in `compileNode`.

## 9. Outcome

*To be written when the work lands.*
