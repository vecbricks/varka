# VARKA-318: A kill switch per expression family, named in its messages

## 1. Where this came from

Row 318 of `m7/PLAN.md`, added by the owner on 12 October 2026 from `m10/SCOPE.md` items 24 and 29.
Item 24 notes that Comet lets each expression be switched off on its own
(`spark.comet.expression.<Class>.enabled=false`), and that "a per-expression kill switch is the
escape hatch a user needs when one kernel is wrong in production and the rest are not". The item
also says why this is cheap here: the compiler already declines node by node, and a declined node
with a reason is exactly what a switch produces. Item 29 (`m7/READING.md` 3) supplies the form
the message should take: StarRocks's JIT fails a query with "set jit_level = 0 to disable jit and
retry". Today the only switch is `spark.sql.codegen.varka.enabled`, which turns off all of Varka.
The row is done when each family's switch is documented, a test turns each one off and sees the
family decline with its reason, and the messages name the switch. It builds on VARKA-314b
(#863), which turns Varka's invariant failures into `INTERNAL_ERROR` with a `Varka context:`
record and adds the docs section "When Varka falls back, and when it fails".

## 2. The admission check, done

The question is whether a grouping already exists that a switch can act on in one place, or
whether one would have to be invented. There are two groupings.

* **`coverage.json`'s `family` field** has nine documentation groups, for example "Day
  arithmetic", "Calendar fields", "Months and year-month intervals" and "Choice and nulls". These
  label whole rows, not nodes, and a class can sit in several groups: `Add` appears under three,
  `Cast` under four, `Year` under four. A switch keyed on them would have to decide which group
  owns each node, which means inventing a third grouping.
* **The compiler's chain, `VarkaNodeCompiler.FAMILIES`**, holds the date leaves, then calendar,
  interval, time, condition and int arithmetic. `compileNode` asks each group in order whether it
  claims a node and runs the first claimant. `VarkaFamilyChainSuite` already proves that no node
  of the coverage table is claimed twice. So one test in `compileNode` gates every node of a
  family, and each node belongs to exactly one family.

The chain is the grouping used here. It matches the row's examples: the calendar
(`VarkaChronoCompiler`), the conditionals (`VarkaConditionCompiler`) and the checked arithmetic
(the int arithmetic arm, whose ANSI forms are the checked ones). The date leaves get no switch:
they read a date column or literal, or unwrap an identity cast, and fuse nothing of their own.

The condition family needs two more gates. `VarkaConditionCompiler.compileCond` compiles a
filter's conjuncts without going through the chain (`predicateOnce`), and the class's own javadoc
calls itself "the predicate family", covering the predicates as well as `IF`, `CASE WHEN` and
`coalesce`. With the switch off, then, a filter's conjuncts also decline. The time family has
one entry outside the chain as well: `compileRoot` reaches a `TIME` `StaticInvoke` directly when
it is a root. Both entries get the same gate. A check would have rejected the chain if one node
class were claimed by two families, and the chain suite already rules that out.

The interval and time families also claim column and literal leaves of their lane types. The time
family claims every 64-bit column, `bigint` included. If those leaves were gated, switching time
off would stop `l > 5` over a `bigint` column from fusing. So reads (a `BoundReference` or a
`Literal`) are never gated: a switch covers a family's operations, not the columns they share.

## 3. The design

### 3.1 The switches, and where they travel

There are five boolean session configurations,
`spark.sql.codegen.varka.family.{calendar,interval,time,condition,intArithmetic}.enabled`, all
true by default and all declared in `SQLConf`. Version 5.0.0 follows the house convention: every
`spark.sql.codegen.varka.*` entry is 5.0.0, which is `dev/next_version_candidates.py`'s master
version, since Varka does not ship to `branch-4.x`. The binding policy is `NOT_APPLICABLE`, as on
their neighbours. On the code side they are one Java enum, `VarkaFamily`, with a label, a switch
key, the off-reason and the retry sentence. A test holds every enum key equal to a registered
`SQLConf` entry.

The switch is **not an emit option**, for three reasons. First, it changes which entries the
compiler admits and never what the emitter makes of an IR, so it has no place in the shape hash
or `canonical()`: a kernel of the remaining families is byte-identical. Second, a
`VarkaEmitOption.TABLE` entry would make "calendar off" an arm of the option matrix, and that arm
would decline half the suites' subjects by design. Third, the table's sealed Flag, Count and
Choice cases have no set-valued member. The template's guidance to put new behaviour behind a
`VarkaEmitOptions` switch is about emitter lowerings and does not apply here.

The binding still has to follow `useAVX`'s path. The planning compile and the executor's compile
(`VarkaKernelEvaluator`, `VarkaFilterEvaluator`) must classify alike, or the plan and the task
would disagree about which entries fused. So `VarkaSessionEmit`, the record the columnar rule
resolves from the `SQLConf` once and the plan carries to the evaluators, gains `familiesOff`. Each
compiler entry point gets an overload that takes the set beside the `VarkaEmitOptions`, and the
set reaches `DeclineSink`, which already carries the one compile option the arms read,
`rangeSets`. The existing overloads pass the empty set, so no suite changes.

**The decline.** In `compileNode`, a family that claims a node while its switch is off declines
the node with `offReason()`, for example "Varka's calendar family is switched off
(spark.sql.codegen.varka.family.calendar.enabled=false)". The reason is the entry's first note,
so the verbose `EXPLAIN` and the fusion report show it as they show any decline.

**The planning error.** A throw out of a family's lowering is caught once, at the innermost
`compileNode` that ran the arm, and carried to the planning catch, which adds the family to the
record. The `INTERNAL_ERROR`'s sentence then ends with `retry()`: "To plan the query with Varka's
calendar family on Spark's row engine, set spark.sql.codegen.varka.family.calendar.enabled to
false and retry." This is StarRocks's sentence. The exception type the site raised, the cause and
the record are as VARKA-314b left them.

**The context record.** `VarkaErrorContext` gains a `families` field. Its line,
`families: calendar (spark.sql.codegen.varka.family.calendar.enabled), ...`, lists the families
whose nodes the failing kernel holds (at planning, the family that threw). A kernel or emission
failure's warning therefore names the switches that would keep that kernel's work off Varka. The
compiler records the families of each compiled projection's accepted entries in
`CompiledVarkaProjection.families`, following the sink's existing mark-and-truncate rollback.

**The runbook.** In `docs/sql-varka.md`, the configuration list gains the five switches, each
with the expressions it covers. "When Varka falls back, and when it fails" gains a step: for a
family that is wrong or keeps failing, set its switch, named on the decline, the error and the
`families:` line.

### 3.2 What is deliberately unchanged

* The date leaves and every read stay unswitchable, as explained in 2.
* A switch covers the nodes its family claims. Code of one family called directly by another's
  lowering runs under the caller's switch: the calendar's interval operand
  (`VarkaIntervalCompiler.yearsToMonths`, `intervalOperand`) and `greatest`/`least` folding
  through `VarkaConditionCompiler.foldPick` under int arithmetic. The owner may overrule this.
* The emitter, the IR, every kernel's bytes and `emitted_bytes.json` do not change.
* `coverage.json`'s nine groups stay as they are. Whether the coverage table should gain a column
  naming each row's switches is a question for the owner (section 9).

### 3.3 Registered op counts

None. No kernel changes.

## 4. Files

| file | what |
|---|---|
| `SQLConf.scala` | the five switch entries and their accessors |
| `codegen/varka/VarkaFamily.java` (new) | the families, their keys and their message sentences |
| `VarkaNodeCompiler.java` | the gate and the failure catch in `compileNode`; `Family` carries its `VarkaFamily` |
| `DeclineSink.java` | the families that are off, the families used, with rollback |
| `VarkaExpressionCompiler.java` | the overloads; the time-root and predicate gates; the family on the planning catch |
| `CompiledVarkaProjection.java` | `families` |
| `varka/VarkaErrors.java`, `varka/VarkaErrorContext.java` | the retry sentence; the `families:` line |
| `VarkaSessionEmit.java` and the exec nodes, evaluators and fusion report | `familiesOff` bound once and passed beside the options; the families on the evaluator's record |
| `docs/sql-varka.md` | the switches, the runbook step |
| `VarkaFamilySwitchSuite.java` (new, catalyst) | section 5 |
| `VarkaFamilyChainSuite.scala`, `VarkaErrorContextSuite.scala` | the sink's constructor; the end-to-end switch test |

## 5. Tests, and what each is for

* **`VarkaFamilySwitchSuite`** (JUnit, `@VarkaSuite`, catalyst), compile-only:
  * for every family and every coverage row: with that family off, a row whose fused nodes include
    the family declines with `offReason()`, and every other row compiles exactly as it does with
    every switch on; every family has at least one row that declines. This would catch a family
    gated in one entry point but not another (the time root, the filter path), and a gate that
    leaks into a neighbour.
  * the enum's keys are `SQLConf`'s, so a renamed key cannot leave the messages naming a setting
    that does not exist.
  * a failure planted inside a family's lowering raises `INTERNAL_ERROR` whose message ends with
    that family's `retry()`, keeps the site's Java type, and renders `families:`.
  * a reads-only check: with the time family off, `l > 5` over a `bigint` column still fuses.
* **sql/core, `VarkaErrorContextSuite`**: `SET` each switch to false on a query over the Arrow
  cache and check that the answer equals Spark's, the verbose `EXPLAIN` shows the off-reason, and
  the query still fuses another family's entry. Then check that a planted kernel failure's warning
  carries the `families:` line naming the kernel's switch.
* No pinned fixture moves: kernels are unchanged, and the defaults turn nothing off.

## 6. The measurement

There is no benchmark, since no kernel changes. The cost is test time.

### 6.1 Predictions, registered before the run

1. The catalyst suite takes under 5 s. That is about 100 coverage rows times six configurations,
   compile-only, at a few milliseconds each, priced at the calibration table's ratio of about 2
   for new suites (VARKA-243, VARKA-244).
2. The sql/core additions take under 10 s of test time: five short queries and one planted
   failure over one cached view, against VARKA-315's 4.5 s for eight queries.
3. No suite other than the two edited ones changes its outcome.

## 7. Risks

1. A family entry point the gate misses. The per-row test over the coverage table, with both
   projection and predicate rows, would show it.
2. The planning and executor compiles disagreeing because one path drops `familiesOff`. The
   sql/core test runs through the real evaluator, where a disagreement shows as a different
   classification.
3. The failure carrier changing an exception type a caller catches. The planted-failure test
   asserts the type, and `VarkaInternalErrorSuite` runs unchanged.

## 8. Sequencing

1. This plan and the row.
2. The switches, the compiler gates and the messages, with both suites and the docs.
3. Section 9 and the row marked done, after the review.

## 9. Outcome

**What was built.** It follows section 3, with two changes the self-review made (9.2). There are
five session settings, `spark.sql.codegen.varka.family.{calendar,interval,time,condition,
intArithmetic}.enabled`, all true by default and all 5.0.0 with `NOT_APPLICABLE`. `SQLConf`
derives them from `VarkaFamily`, so each key and its coverage text is written once. They are bound
in `VarkaSessionEmit` beside the emit settings and passed beside the options at every compiler
call in `sql/core`. A family that is off declines its nodes with "Varka's <family> family is
switched off (<switch>=false)". A failure inside a family at planning ends its `INTERNAL_ERROR`
sentence with "To plan the query with Varka's <family> family on Spark's row engine, set
<switch> to false and retry.". The context record of a planning, emission or kernel failure
carries a `families:` line naming the kernel's families with their switches. The docs list the
switches, and the runbook names them in Troubleshooting's opening step, in the wrong-answer step,
in the who-reads table and in "When Varka falls back, and when it fails".

### 9.1 Tests and time

* `VarkaFamilySwitchSuite` (catalyst, JUnit) has four tests. One compiles each of the 92 coverage
  rows under each family off and checks it against the chain's claims. Its two-sided oracle found
  the two cases 9.2 describes. The suite takes 1.9 to 2.3 s, including reading the coverage
  table, against a prediction of under 5 s (6.1 #1, ratio 0.46).
* `VarkaFamilySwitchQuerySuite` (sql/core) has three tests: each switch set by `SET`, the
  condition switch on a filter, and the planning failure with its retry. Its tests take 3.7 s run
  alone and about 5 s in the gate, against a prediction of under 10 s (6.1 #2, ratio 0.37). The
  `VarkaErrorContextSuite` assertion adds nothing measurable.
* The gate (`dev/varka_matrix.sh --defaults --split 4`, 107 suites, 862 s) gave 1,371 passed and
  two failures. The first was this suite's oracle on `unix_date(d)`, fixed in 9.2. The second was
  `VarkaTierWitnessSuite` "the witness sees each tier", a `speculate_null_assert` trap in
  `VarkaMemorySanitizer.check`. It fails identically on `varka-314b`'s own gate run of 11:07 that
  day, so it predates this task (6.1 #3 holds). After the fixes, the six affected suites rerun
  together passed but for that one. `SparkConfigBindingPolicySuite` passes.

### 9.2 Where the plan was wrong

* **3.2 said** a node one family's lowering builds directly runs under the caller's switch, and
  the suite at first listed `d - ym` as the case. The review showed that a user switching the
  interval family off would still get the interval negation, so the calendar's two direct builds
  of interval nodes (`d - ym`'s negation, the years-to-months cast) now run under the interval
  switch through the same `inFamily` the chain uses. What still runs under the caller's switch is
  helper code that builds no node of its own: `arithOver`'s overflow decision, which int and
  interval arithmetic share, and the `foldPick` that `greatest`/`least` borrow.
* **2 said** that only a `BoundReference` or `Literal` is a read. The review found the relabel
  casts type coercion inserts (`TIME(3)` to `TIME(6)`, the interval unit relabels), which the
  time and interval families claim: switching time off declined `t6 < t3`. A cast of a read that
  lowers to the bare read is now a read too, and a cast of a read that computes is still the
  family's. A function that happens to lower to a bare read, `unix_date(d)`, stays its family's,
  which the gate's first run caught.

### 9.3 Review findings not taken

* The overloads without `familiesOff` stay public: they are the tests' and tools' entry points
  (every family on), and every call in `sql/core`'s main code passes the session's set, which
  section 4 lists. Folding the options and the set into one compile-context value would close the
  gap for good, at the cost of changing every compiler signature, and is not this task's.
* `docs/sql-varka.md`'s table repeated `VarkaFamily.covers()` by hand, and no suite read it. Swept
  the same day on the owner's decision (9.4): the switches' table is generated from `VarkaFamily`
  by `VarkaCoverageSuite`, which fails when the page differs.

### 9.4 The owner's decisions of 16 October 2026, and what was built

The owner read 9 and decided two changes before the pull request opens. Where 9 and 9.1 say
"five settings", there are now six.

**1. `condition` splits in two: a `predicate` switch for the predicates.** Until now `condition`
covered a filter's predicates as well as the conditionals, so a user whose comparison kernel was
wrong lost every `IF`, `CASE WHEN` and `coalesce` with it, and a user whose `CASE WHEN` was wrong
lost every filter. There is now a sixth setting, `spark.sql.codegen.varka.family.predicate.enabled`
(5.0.0, `NOT_APPLICABLE`, derived in `SQLConf` from the new enum constant like the others), and
the two cover these node classes:

| switch | node classes | where the compiler reaches them |
|---|---|---|
| `predicate` | `LessThan`, `LessThanOrEqual`, `GreaterThan`, `GreaterThanOrEqual`, `EqualTo`, `In` and `InSet` over a date or year-month interval, `IsNull`, `IsNotNull`, `And`, `Or`, `Not` | `VarkaConditionCompiler.compileCond`: a filter's conjunct, the condition of an `IF` or a `CASE WHEN` branch, and the operands of a connective |
| `condition` | `If`, `CaseWhen`, `Coalesce` | the chain (`VarkaConditionCompiler.arm`) |

*Whose switch is a comparison inside a `CASE WHEN` branch's condition?* The predicate family's,
wherever it sits. Three reasons. First, it is the rule 9.2 already settled: a node of a family
that another family's lowering reaches or builds asks its own family's switch, and only helper
code that builds no node runs under the caller's. A comparison is a node of the user's query, as
the negated interval in `d - ym` is. Second, it keeps every node class under one switch, so the
question "which switch keeps `<` off Varka" has one answer: a user whose comparison lowering is
wrong sets one switch, not two. Third, the position-dependent alternative (a comparison under
`condition` inside a conditional and under `predicate` in a filter) would make the claim depend
on the caller, which no other family does. The cost is stated in the docs: with `predicate` off,
every `IF` and `CASE WHEN` whose condition holds a comparison declines too, with the predicate
family's reason, since the comparison is the first node to decline. `coalesce` holds no
predicate node: the validity test it folds onto (`IsNotNull` over the column) is IR its lowering
builds, not a Catalyst node, so it runs under `condition` as helper code would. `nvl2(a, b, c)`
arrives as `If(IsNotNull(a), b, c)` and so holds both.

*How it keeps one claimant per node.* The predicate family is not a link of the chain:
`compileNode` never claims a comparison, whose value is a mask and not a lane, so the chain is
unchanged and no sub-family per node is needed. Instead `compileCond` is now a claim and a gate in
the chain's own form. `condArm` is a `switch` over the classes above that returns the lowering
as a deferred call, or `null` for a node the family does not claim, with the `In`/`InSet` type
guards inside the claim, so "claimed and off" and "claimed and on" cover the same nodes. A node
`condArm` does not claim (`EqualNullSafe`, a boolean column) still declines as an unsupported
predicate whatever the switch. A claimed node lowers through the same `inFamily` the chain uses
(the switch, the failure carrier, the families-used record), so a failure inside a comparison is
reported as the predicate family's even inside a `CASE WHEN`, since `inFamily` passes an inner
family's failure through. The connectives recurse through `compileCond`, so each nested node is
gated on its own. The conjunct loop in `predicateOnce` lost its `inFamily(CONDITION, ...)`
wrapper and is back to master's line, so this change leaves no edit in the `predicate()` loop
that `varka-380` reworks beyond the `off` argument that was already there.
`VarkaNodeCompiler.predicates()` hands the claim to the two suites as a `Family` beside the chain:
`VarkaFamilyChainSuite` asks it with the chain's groups and finds no node claimed by two, and
`VarkaFamilySwitchSuite`'s independent reading of a row's families walks the same claims (the
hand-added `CONDITION` for a conjunct is gone, since a conjunct's root is now claimed).

The texts follow from the enum: `predicate` sits before `condition` in `VarkaFamily`, so the
`families:` line and the coverage column list a `CASE WHEN` row as `predicate, condition`. The
decline reason, the retry sentence and the `families:` line needed no code. The query suite gains
a `predicate` case (`if(d > DATE'2000-01-01', d, DATE'2000-01-01')` declines with the predicate family's reason while
`year(d)` fuses) and its filter test now shows both sides: with `predicate` off the filter does
not fuse, with `condition` off it still does.

**2. A generated switch column in the coverage table, and a generated switch table.** Each
coverage row now names its switches: a *Switches* column in `docs/sql-varka.md`'s table (the
switch's `<name>`, `none` for a row that compiles to a bare read) and a `switches` field in
`coverage.json` (the full keys). `VarkaCoverageSuite` computes them from the compiler: the
families its kernels record (`CompiledVarkaProjection.families`, the same record a failure's
`families:` line prints), under the defaults the table documents, through a `kernelsOf` the domain
check now shares. That is the classification `VarkaFamilySwitchSuite` holds two ways, to the
claims on the row's nodes and to which rows each switch declines, so the column cannot name a
switch that would not decline its row nor miss one that would. Over the 92 rows: `calendar` 37,
`predicate` 32, `time` 13, `int arithmetic` 11, `interval` 7, `condition` 6, and one row with
none, `CAST(ymy AS INTERVAL MONTH)`, a unit relabel that lowers to the column read (9.2).

The hand-written switch rows of the Configuration table are gone. The switches have their own
table after it, between `<!-- BEGIN generated family switches -->` marks (an HTML comment inside
a Markdown table would end the table), rendered from `VarkaFamily` (key, default, `covers()`) by
`VarkaCoverageSuite` and checked by the test that checks the coverage table, regenerated by the
same `VARKA_COVERAGE_REGEN=true` command. The prose no longer counts the switches ("the five"), so
the next family changes no sentence.

#### 9.4.1 Tests and time

No cost was predicted before these runs; the times are recorded as measured, at a machine load of
8 to 10 from other worktrees' jobs.

* `VarkaFamilySwitchSuite` compiles each of the 92 rows under six families off instead of five,
  and its failure test adds the nested case: a planted failure in the comparison of
  `CASE WHEN d < d2 THEN d ELSE d2 END` names the predicate family's switch and not the
  condition family's. Its four test bodies take 0.78 s by the JUnit report; run alone in a fresh
  JVM the suite takes 12.7 s, nearly all of it the `@BeforeAll` that resolves the rows through a
  cold analyzer, which 9.1's 1.9 to 2.3 s did not include (it ran beside another suite that had
  already paid it).
* `VarkaFamilyChainSuite` asks the predicate family's claim beside the chain's and finds no node
  of the coverage table or the seams claimed twice (1.2 to 2.3 s, as before).
* `VarkaCoverageSuite`: the two tests that render the page and `coverage.json`, which now compile
  every row's kernels once more each for the switch column, take 92 ms and 165 ms, so the column
  costs at most about a quarter of a second.
* `VarkaFamilySwitchQuerySuite` has the predicate case and the two-sided filter test: 3.7 s,
  0.4 s and 0.2 s for its three tests, against 9.1's 3.7 s alone for the suite.
* Passed: `VarkaFamilySwitchSuite`, `VarkaFamilyChainSuite`, `VarkaCoverageSuite`,
  `VarkaExpressionCompilerSuite` (152 tests together, one opt-in dump cancelled as designed) and
  `VarkaEmittedBytesSuite` in catalyst, whose bytes did not move, as the refactor of
  `compileCond` into a claim emits the same IR; `VarkaFamilySwitchQuerySuite` and
  `VarkaErrorContextSuite` in sql/core (12 tests); `SparkConfigBindingPolicySuite` in sql/hive
  with the new setting. `dev/varka_precommit.sh`, scalastyle (main and test, catalyst and sql) and
  `dev/lint-java` pass. `dev/varka_errorprone.sh` fails with 14 findings, every one in a file this
  branch does not touch (`VarkaReproduction`, `VarkaVectorTemplateWarmup`, `VarkaKernelWarmup`,
  `VarkaEmitTrace`), identical at the merge base `fde86035bad`; master fixed them afterwards in
  #875, and none is in a file this change edits.

#### 9.4.2 The self-review

Read over the diff of 9.4 before the pull request, with the review's questions: does every entry
to a predicate node pass the gate, can a switch change a row it does not cover, can the column
disagree with the switches, and does anything read the shapes this changes. What it found and
what changed:

* **The nested failure was claimed, not tested.** The plan said a failure inside a comparison is
  named as the predicate family's even inside a `CASE WHEN`, because `inFamily` passes an inner
  family's failure through the outer gate. Nothing checked it: the failure test's predicate row
  is whichever row first holds the family. `failureNamesTheSwitch` now plants the failure in
  `CASE WHEN d < d2 THEN d ELSE d2 END` and requires the predicate family's retry sentence and
  no mention of the condition family's switch.
* **The query suite's predicate case did not fuse with the switch on.** The first draft used
  `if(i > 0, i, 0)`, and a comparison over an int column declines ("non-date column of type
  int"), so the "switched back on, it fuses" half failed while the decline half passed. It is
  `if(d > DATE'2000-01-01', d, DATE'2000-01-01')` now.
* **The column's empty cell said the wrong thing.** The first rendering said a row with no
  switch "reads only columns and literals"; the one such row, `CAST(ymy AS INTERVAL MONTH)`, is
  a cast that lowers to the read. Both the page and `coverage.json` now say "compiles to a bare
  column read".
* Checked and unchanged: `catalyst_classes_admitted` in `coverage.json` did not move, so the
  coverage suite's source scan still sees every predicate class after the `instanceof` chain
  became a `switch`; nothing in `sql/varka/matrix/skips.tsv` names the renamed filter test; no
  other reader of `coverage.json` asserts its key set; the reason a row declines with `predicate`
  off is the comparison's, because `caseWhen` compiles its conditions before its values and the
  sink keeps the first decline.

A `/code-review high` of the two commits then found no high-severity bug and seven smaller
points:

* **Fixed: a throw in the predicate claim lost its family.** `compileNode` turns a throw in a
  claim's guards into the family's failure; `compileCond` called `condArm` bare, and the
  `inFamily(CONDITION, ...)` this change removed from the conjunct loop used to cover it. A throw
  in the `In`/`InSet` type guards would have reached the planning error with no retry sentence.
  `compileCond` now wraps the claim as `compileNode` does, naming the predicate family.
* **Fixed: the switches' table hard-coded the default.** `renderSwitches` printed `true` for
  every switch; it reads `SQLConf.VARKA_FAMILY_SWITCHES(f).defaultValueString` now, so a family
  added with a different default renders it.
* Not taken: sharing one claim-then-gate helper between `compileNode` and `compileCond`, and making
  `VarkaFamilyArm` generic so the predicate claim needs no adapter. Both touch every family for a
  second caller; the guard wrapping is the one behaviour the two copies must share, and it is now
  the same.
* Not taken: memoizing a row's kernels in `VarkaCoverageSuite`, which compiles each row once more
  for the column in each of its two rendering tests; 9.4.1 measured the two at 0.26 s together.
* Not taken: one spelling for the switches. `coverage.json` holds the full keys, which a grep
  for a setting finds; the page prints the `<name>`, which fits a table cell, and says how the
  name becomes the key.
* Noted: `condition.enabled=false` no longer stops a filter from fusing. The key has never left
  this unmerged branch, so nobody holds the old meaning.
