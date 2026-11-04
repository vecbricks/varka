# VARKA-556: One definition of "the same answer"

*Planned 3 November 2026 on `varka-556-557-plan`, from the both-bodies failure that pull request
row 555 (#1313, merged) fixed. 556a is built (section 10), and 556b, with 556a2
folded in (section 11).*

## 1. Where this came from

Option-matrix run 41608460861 failed `VarkaCoverageHarnessSuite`'s "Floating arithmetic:
try_add(x, x2) ..." because `VarkaKernelRunner.compareBodies` held the dense and masked bodies'
outputs byte-equal on a row of two unlike NaNs, whose sum's NaN depends on the JIT tier that ran
each body (`VARKA-555.md`, sections 1 and 2). The comparison of the same kernel
with Spark had excused exactly that payload for weeks. So two comparators of one answer disagreed
about what "the same" means, and one of them failed on the other's known case. #1313 relaxes
`compareBodies` by type; this row makes every comparator use one written rule, with a contract
table that a new comparator cannot pass while it disagrees.

## 2. The admission check, done

From master at `3767cf50799` and #1313's head, by `git grep` for `unfixedNaN`,
`doubleToRawLongBits`, `floatToRawIntBits`, `mismatch(`, `Arrays.equals`, `isNaN`, `checkAnswer`
and the fuzz oracles over `sql/catalyst` and `sql/core`, both source sets. Every place that holds
a Varka answer against another answer uses one of seven rules:

| # | comparator | context it reads | two NaNs | `-0.0` vs `+0.0` | nulls | decimals |
| ---: | :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | `compareBodies` on master (sql/core main) | output width only | bytes | differ | validity bit; data under a null unread | unscaled bytes |
| 2 | `compareBodies` after #1313 | width and a per-output floating flag | any NaN agrees on a `FLOAT`/`DOUBLE` output | differ | as 1 | as 1 |
| 3 | `VarkaKernelChecks.sameAnswer` / `unfixedNaN` (catalyst test): kernel against `VarkaReference`, and `agreeWithSpark`, reference against `VarkaSparkOracle`; inherited by `VarkaCompiledCheck`, `VarkaTypeConformance`, `VarkaIrFuzzSuite`, `VarkaCoverageCompositionFuzzSuite` and the emitter suites that pass an oracle | the IR and the reference's value of every node on the row | agree only where the row's NaN is open by provenance | differ | validity against `Optional` | unscaled long |
| 4 | `VarkaExpressionHarness.sameRow` / `unfixedNaN` (sql/core test): Spark interpreted against generated, and the kernel against Spark | the Catalyst `Expression` and the fixture row | as 3, on the expression | differ | `isNullAt` | `Decimal.equals`, which is `compare == 0` and blind to scale (`sql/api/.../Decimal.scala` 490) |
| 5 | Spark's `checkAnswer` (`RowComparisonUtils.scala` 104 to 111), in `VarkaDifferentialSuite`, `VarkaCoverageDifferentialSuite`, `VarkaLongLaneSuite`, `VarkaGoldenCorpusSuite` (through `getErrorMessageInCheckAnswer`) and most end-to-end suites | the collected `Row` | any NaN agrees | differ (raw bits otherwise) | `null` | Scala `BigDecimal`, blind to scale |
| 6 | `VarkaSparkFuzz.outcomeOf` (`VarkaSparkFuzzSuite`, the laptop nightly's Spark differential) | `Row.toString`, sorted | any NaN agrees (`"NaN"`) | differ (`"-0.0"`) | `"null"` | scale visible |
| 7 | suite-local: `VarkaFloatingLaneSuite.nanAsOne` (any NaN agrees in the first `relaxed` columns, bits elsewhere); `VarkaEmitterFloatingArithSuite.twoUnlikeNaNs` (open when the row's inputs hold two unlike NaNs, whatever the expression) and its data generator, which never pairs unlike NaNs | a column index; the inputs | per suite | differ | per suite | none |

What the table shows:

- **Rules 3 and 4 have drifted.** They state the same provenance rule, one over the IR and one over
  expressions, and the expression rule was rewritten not to compare a node's NaN bits with a
  child's ("which need not agree", its Javadoc), while the IR rule's default case still does
  (`VarkaKernelChecks.java` around 330: `c.getAsLong() == v.getAsLong()`), and the IR rule has no
  case for the picks the expression rule names (`IF`, `CASE`, `coalesce`, `nanvl`, `greatest`,
  `least`). Whether they answer a pick over two NaNs alike is unknown; the contract table decides
  it.
- **Rule 2 is looser than 3 and 4**, by #1313's own account: a body that returns the wrong NaN of
  a pick (`abs(x)`, `coalesce(x, y)` over two NaN columns) goes unseen there.
- **Rules 5 to 7 are looser still** on NaNs, and 4, 5 and 6 differ among themselves on decimal
  scale. Within one output type the scale is fixed, so the difference shows only on a writer that
  stores the wrong scale; rules 1 to 3 compare the unscaled value and see it.
- **Every rule agrees on the sign of zero** (strict) and on nulls (validity only, a null's data
  unread). Bit outputs (`BOOLEAN`) and selection bitmaps compare their data bit under the validity
  bit in rules 1 to 3.
- No main-code comparator other than `compareBodies` exists: `VarkaFilterEvaluator`'s
  `Arrays.equals` compares column positions, not answers.

## 3. The design

### 3.1 The rule's home: `VarkaSameAnswer` in catalyst main

One Java class, `sql/catalyst/src/main/java/.../codegen/varka/VarkaSameAnswer.java`: catalyst
main is the one tree that sql/core main (where `compareBodies` lives), the catalyst tests and the
sql/core tests all see, and nothing the rule needs is a test type. The IR rule takes the node's
value as a `Function<VarkaVectorIR, OptionalLong>`, so the reference evaluator, which is test
code, stays in the tests; the expression rule takes an `Expression` and an `InternalRow`, both
catalyst main. (The row level alone could instead live in the catalyst tests, which the sql/core
tests already read, `VarkaMatrix` among them, keeping a rule only tests use out of the shipped
jar; open point 4.) It holds, as a sealed set of levels a comparator names:

- `Exact`: bytes at the output's width; a decimal's unscaled value; a bit output's data bit
  under its validity bit; a null's data unread.
- `OpenNaN.Shape`: `Exact`, except that on an output whose IR **may** yield an open NaN any NaN
  agrees with any NaN of the width. "May" is a walk of the IR once per kernel: a floating
  `IntArith` whose two operands can both be NaN (neither a non-NaN literal), or a node that passes
  such a child's value on. `x + x2` may; `abs(x)`, `coalesce(x, y)`, `x + 1` may not.
- `OpenNaN.Row`: the provenance rule on one row, over the IR (rule 3) or over the expression
  (rule 4), one statement of it with two front ends, the picks handled alike.
- `AnyNaN`: Spark's `checkAnswer` rule, kept as a named level for the suites that use it (3.4).

### 3.2 The both-bodies check takes the shape level

`compareBodies` cannot take the row level: the provenance rule needs every node's value on the
row, which only the reference evaluator computes, and that is test code. It need not keep #1313's
type level either: `VarkaEvaluatorBase.newRunner` holds `compiled.outputs()`, the IR, so it sets
#1313's `dstFloating[o]` to `VarkaSameAnswer.mayOpenNaN(output o)` instead of "the Arrow vector is
floating", once per kernel, at no per-row cost. The check then stays byte-exact on `abs(x)`, a
pick and `x + 1`, where #1313 relaxes every floating output. Soundness is what the contract suite
checks: over every coverage row's IR and every fixture row, `OpenNaN.Row` open implies
`mayOpenNaN`.

### 3.3 The contract table

A JUnit 5 `@VarkaSuite` class in the catalyst tests, `VarkaSameAnswerContractSuite`, and its
sql/core twin for the comparators only sql/core sees (rule 4, the `checkAnswer` wrapper). One
table of tricky pairs, each with its verdict per level, and every comparator registered by name
with its level; each test runs one comparator over every pair and requires its level's verdict:

- two unlike NaNs from `x + x2`, `x - x2`, `x * x2`, and a column NaN plus a literal NaN (open);
- two unlike NaNs from `abs(x)`, `-x`, `coalesce(x, y)`, `greatest(x, y)`, `nanvl(x, y)` and
  `IF(c, x, y)` over NaN columns (fixed: agree only under `AnyNaN`, and under `OpenNaN.Shape`
  where the IR also holds an open arithmetic);
- the same NaN on both sides; a NaN against a number, either way round;
- `+0.0` against `-0.0` (differ everywhere), and the same at `FLOAT`;
- a `FLOAT` whose lane carries other high bits (compared at the width);
- null against null with different data under the validity bit (agree); null against a value;
- a `DECIMAL(10,2)` and a `DECIMAL(38,4)` value against the same value at another scale (differ
  under `Exact`; the writer's error);
- a `BOOLEAN` output's data bit under a clear validity bit (agree); a selection bitmap.

A new comparator either reuses `VarkaSameAnswer` or adds itself to the registry, where the table
holds it; a lint rule (3.4) finds the ones that do neither.

### 3.4 The comparators moved onto it, and what is deliberately unchanged

- Rules 1 to 4: `compareBodies` as 3.2; `VarkaKernelChecks.sameAnswer` and
  `VarkaExpressionHarness.sameRow` call `VarkaSameAnswer`, their `unfixedNaN` bodies deleted.
- Rule 7: `nanAsOne` and `twoUnlikeNaNs` become `OpenNaN.Row` calls, so a fixed-NaN column is
  held to Spark's bits where it is not now.
- Rules 5 and 6: Spark's `checkAnswer` is Spark's and stays; a Varka suite that compares floating
  answers adds the bit comparison `VarkaFloatingLaneSuite.bits` already does, through
  `VarkaSameAnswer`. Whether every `checkAnswer` call in a Varka suite must, or only the floating
  ones, is open point 2. `VarkaSparkFuzz` compares strings by design (one outcome per query,
  rerun on Spark with codegen off by row 545); it registers as `AnyNaN` with its scale caveat.
- A `dev/varka_lint.py` rule, in the pattern of row 548's: in Varka test and main files, raw-bits
  equality of two floating answers or `isNaN(a) && isNaN(b)` outside `VarkaSameAnswer` and the
  contract suites is a finding.
- Not changed: what any kernel computes, and the store `VarkaHarnessStore` keeps (Spark's answers
  by raw bits, compared after reading).

### 3.5 The lesson, in this row's pull request

A new `##` section in `sql/varka/skills/the-jit.md`, "Which NaN an operation over two NaNs
returns depends on the tier", with `SKILLS.md` regenerated by `dev/varka_toc.py`: the x86
interpreter's `dadd` and `dmul` return the right operand's payload and `dsub` and `ddiv` the
left's (`cpu/x86/templateTable_x86.cpp`, `TemplateTable::dop2`); C1 and the Vector API's Java
fallback the left's; C2 commutes a scalar add or multiply (`opto/addnode.cpp`) and orders a
non-predicated commutative vector op's inputs by node index
(`VectorNode::should_swap_inputs_to_help_global_value_numbering`, `opto/vectornode.cpp` 1088 to
1146 in jdk-25-ga); one operation over one NaN returns that NaN on every tier. So two bodies, two
engines or the same method before and after a compile can disagree on such a payload and on
nothing else; a comparator excuses it by provenance (`VarkaSameAnswer`), never by type alone; and
a test that warms neither body, as the suites do with the warm-up off, cannot see the split
(row 557).

## 4. Files

Catalyst main: `VarkaSameAnswer.java` (new). Catalyst tests: `VarkaKernelChecks.java`,
`VarkaEmitterFloatingArithSuite.java`, `VarkaSameAnswerContractSuite.java` (new). sql/core main:
`VarkaEvaluatorBase.java`, `varka/VarkaKernelRunner.java` (Javadoc). sql/core tests:
`VarkaExpressionHarness.java`, `VarkaFloatingLaneSuite.scala`, the differential suites' floating
checks, a contract twin (new). `dev/varka_lint.py`, `sql/varka/skills/the-jit.md`, `SKILLS.md`.

## 5. Tests, and what each is for

- The contract suites: each comparator's verdict on every pair, per level; the soundness check of
  3.2 over the coverage rows and their fixture rows.
- `VarkaEvaluatorComponentsSuite`: #1313's planted-bodies test, with an `abs(x)` output whose two
  unlike NaNs now fail and an `x + x2` output whose two agree.
- The lint rule's self-test: one finding planted per pattern, none on the shared class.
- No answer moves: `emitted_bytes.json` byte-identical, every existing Varka suite green with
  `-Dvarka.checkBothBodies=true`, as the gate runs them.

## 6. Size

Hand-written lines, added plus removed, counted as row 554 counts them; a moved block counts
twice. One pull request would be about 1120, over the cap, so two:

- **556a**, about 720: `VarkaSameAnswer` added (about 260, of which about 180 are the two
  `unfixedNaN` bodies and their helpers moved in) and those 180 removed from `VarkaKernelChecks`
  and `VarkaExpressionHarness`, so about 440 for the class; the catalyst contract suite (about
  200); the three Java comparators' call sites (about 30); the evaluator's shape flag (about 20);
  the lesson (about 30).
- **556b**, about 400: the suite-local rules and the differential suites' floating checks
  (about 150), the sql/core contract twin (about 100), the lint rule and its self-test (about
  120), this plan's outcome (about 30).

*Re-sized on 4 November 2026, after the lead's decisions (9) and the build of 556a (10).* With
the shape level dropped, 556a is the class, the three Java comparators, the decimal rule and the
lesson, about 900 lines with this plan's edits, and the catalyst contract suite (about 370) no
longer fits beside it, so it is a pull request of its own, 556a2, stacked on 556a (measured after
the review's fixes: 960 and 458 counted lines). With point 2
settled on `checkAnswer` left as `AnyNaN` (9), 556b is about 250: the suite-local rules, the
sql/core contract twin and the lint rule.

## 7. Risks

- **The shape walk misses an open NaN** a row then shows: the both-bodies check fails on a known
  case, as before #1313. The soundness check of 3.2 runs over the fixtures that hold such rows.
- **Moving rule 7 onto the row level makes suites fail** on fixed-NaN columns they relaxed: each
  such failure is a pick returning the wrong NaN, Varka's or the test's, and is fixed or recorded,
  not relaxed.
- **The drift between rules 3 and 4 is real** and one of them answers a pick wrongly: the contract
  table then fails on the first build, which is its purpose; the fix is in `VarkaSameAnswer`.

## 8. Sequencing

After row 555 (#1313, merged), whose flag 3.2 reuses. Independent of row 557, which proves the nightly
against #1313's tolerance; 557's planted fault (reverting the tolerance) still fails under the
shape level.

## 9. Open points for the lead

*All four settled by the lead on 4 November 2026, point 2 after its premise was checked and found
false.* The questions as first asked:

1. The shape level for the both-bodies check (3.2) or #1313's type level kept: the shape level is
   stricter and costs a walk per kernel; the type level is simpler and already merged-to-be.
2. Varka suites built on `checkAnswer`: every floating comparison through `VarkaSameAnswer`, or
   `checkAnswer` registered as the loose `AnyNaN` level and left. The first moves roughly a dozen
   suites; the second leaves a pick returning the wrong NaN unseen end to end.
3. Decimal scale: whether rule 4's scale-blind `Decimal.equals` should become `Exact`'s unscaled
   comparison at the output's scale, which would catch a writer that stores the wrong scale.
4. Where the row level lives: in catalyst main beside the exact and shape levels, one class, or
   in a catalyst test class (sql/core's tests read the catalyst test classes), so that the shipped
   jar holds only what `compareBodies`, a main-code check, needs. The plan's default is one class
   in main.

The answers:

1. **Settled: the type level stays.** `compareBodies` keeps #1313's acceptance of any NaN against
   any NaN on a `FLOAT` or `DOUBLE` output, and no walk per kernel is built. 557's trial shows
   tier-dependent payloads on rows that are not one commutative operation: `(x + x2) * x2 - x`
   failed in almost every run with the acceptance removed (`results/VARKA-557-trial.txt`), so a
   shape level would have to be the full provenance walk to keep the nightly green, and a
   commutative-only one would fail it with no defect. 3.2's shape level and its soundness check
   are not built; `VarkaSameAnswer.Level` has no shape member, and section 5's `abs(x)` bullet
   for `VarkaEvaluatorComponentsSuite` falls away with it.
2. **Settled: `checkAnswer` stays on `ANY_NAN`, though the premise does not hold.** The lead's
   first answer was to register `checkAnswer` as
   `AnyNaN` and leave the suites, provided no SQL surface shows a double's raw bits, so that a NaN
   payload is unobservable end to end. Half of that holds: Spark's hashes canonicalize, by
   `Double.doubleToLongBits` and `Float.floatToIntBits` with `-0.0` mapped to `0`, in the
   generated code (`sql/catalyst/.../expressions/hash.scala` 476 to 493, `genHashFloat` and
   `genHashDouble`, which `Murmur3Hash`, `XxHash64` and `HiveHash` share through
   `HashExpression`) and interpreted (`hash.scala` 720 to 723, `InterpretedHashFunction.hash`;
   `HiveHashFunction.hash` falls through to it, 1369); grouping, join and window keys are
   normalized by `NormalizeFloatingNumbers` (`optimizer/NormalizeFloatingNumbers.scala` 71 to 92);
   no cast takes a floating type to `BINARY`. The other half does not: `reflect`, `java_method`
   and `try_reflect` call any public static method whose parameters take the arguments' types
   (`CallMethodViaReflection.scala` 236 to 237 map `DOUBLE` to `double`; `findMethod`, 261 to 284,
   checks the name, `static`, the arity and the parameter types, and not the return type its
   Javadoc names) and return `String.valueOf` of the result (182 to 183). With the allow list
   empty, which allows every call (206 to 210; `StaticSQLConf.scala` 411 to 423), and restricted
   mode off by default (`StaticSQLConf.scala` 426 to 436),
   `reflect('java.lang.Double', 'doubleToRawLongBits', x)` returns a NaN's payload as a string.
   (Read from the code, not run.) A user-defined function sees the raw bits too. The lead then
   settled it: the `checkAnswer` suites stay on `ANY_NAN`, and the raw bits `reflect`,
   `java_method`, `try_reflect` (`CallMethodViaReflection.scala` 182 to 284, the allow list empty
   by default) and a UDF observe are the one documented exception, not a target, because vanilla
   Spark does not define that payload either: its `Add` returns whichever operand's NaN the tier
   picked (#1313), and its hash, grouping and join paths canonicalize. 556b is the small option
   (6).
3. **Settled: exact at the output's scale.** Rule 4 compares a decimal's unscaled value at the
   output's precision and scale, after normalizing each side to that type, so that a row carrying
   a `Decimal` at another internal scale does not mismatch; a side the type cannot hold (rounding
   needed, or more digits than the precision) differs. Built in 556a (section 10). *Corrected on
   review:* this catches no wrong scale, as item 5 of section 10 says.
4. **Settled: one class in catalyst main**, `VarkaSameAnswer`, as the default.

## 10. Outcome of 556a (4 November 2026, branch `varka-556-same-answer`)

**Built.** `VarkaSameAnswer` in catalyst main: the levels as an enum (`EXACT`, `TYPE`, `ROW`,
`ANY_NAN`; no shape level, 9.1), the type level's `bothNaN`, and the row rule written once over a
node of either tree, with an IR front end (`IrRow`: a node's reference value and a condition's
truth) and a Catalyst expression front end, and `sameRow`, the expression harness's row comparison,
moved in; a node whose own value is not a NaN is never open, and a subtraction or a division over
two NaNs is fixed by its left operand (items 7 and 8 below). `VarkaKernelChecks.sameAnswer`,
`VarkaExpressionHarness.sameRow` and `unfixedNaN`, and `VarkaKernelRunner.compareBodies` call it;
their own rule bodies are deleted. The lesson is `skills/the-jit.md`'s last section. The contract
suite, `VarkaSameAnswerContractSuite`, is on the stacked branch `varka-556-contract` (6). Its `TYPE`
entry restates `compareBodies`' row rule (equal bits, or `bothNaN` on a floating output), since the
catalyst tree cannot reach sql/core main; the check itself is held by
`VarkaEvaluatorComponentsSuite`'s planted-bodies tests until the sql/core twin registers it (556b).
No emitter or compiler file is touched, so no generated file moves.

**Where verdicts move**, each on purpose:

1. *The IR rule decides a pick by its condition.* The deleted IR rule had no case for `IfElse`,
   `greatest` or `least`, and opened any other node whose value had the same bits as an open
   child. So `coalesce(x, x + x2)`, which returns the column NaN `x`, was excused whenever the
   reference's evaluation of the sum happened to return `x`'s payload: the drift section 2
   predicted, real on that case. On the contract table's other picks the old and new rules agree,
   as the reference evaluator answers deterministically there. The suite plants the sum's value to
   show it (`pickByConditionNotBits`).
2. *A node of one value child passes its child's open NaN on*, as the expression rule's cast does:
   a `ConvertLane`, `WidenLane` or `NarrowLane` of an open NaN is now open, where the old rule
   required the child's lane to be the node's. Looser, and right: the conversion of an open payload
   is open.
3. *An arithmetic with one null operand* walks the NaN side where the old rule stopped. No answer
   moves: such a node is null, and a null is never compared.
4. *`agreeWithSpark` on a selection output* gets the row's references instead of an empty one; a
   condition is never floating, so no verdict moves.
5. *Rule 4's decimals* compare unscaled at the output's type (9.3): a side the type cannot hold
   differs where `Decimal.equals` compared values. *Corrected on review:* no verdict moves. Every
   row the harness compares reaches it at the type's scale (Spark's through `UnsafeProjection`,
   Varka's through the readers, which catch a wrong scale), so the rule agrees with
   `Decimal.equals` on every input it sees; 9.3's "catches a writer that stores the wrong scale"
   and 2's "rules 1 to 3 ... see it" overstate.
6. `compareBodies` is unchanged: the same bytes at the same widths, read as `JAVA_LONG` and
   `JAVA_INT` now.
7. *A node whose own value is not a NaN is never open* (review). The first build passed an open
   NaN up through a node that did not return it: `sqrt(least(x + x2, -1.0))` is `sqrt(-1.0)`,
   whose NaN is fixed, since Spark's `least` skips the NaN, yet the rule walked into the sum and
   excused a kernel that let the sum through, which the deleted IR rule rejected.
8. *A subtraction and a division over two NaNs are fixed* (review): every tier returns the left
   operand's payload (the lesson in `skills/the-jit.md`), so `x - x2` and `x / x2` are compared
   strictly and only an add, a multiply or a remainder is open. The remainder stays open: on x86
   every tier calls `SharedRuntime::drem`, which goes to `StubRoutines::fmod`
   (`sharedRuntime_x86.cpp` 98 to 100), and that stub's choice between two NaNs was not read.
9. *3.5's lesson, corrected on review:* C1 returns the left's payload except where
   `move_const_to_right` swaps a constant left operand of a commutative op; C2's scalar multiply
   is ordered in `MulNode::Ideal`, not `addnode.cpp`; the Vector API's Java fallback is plain Java
   arithmetic, so its payload is the tier's that runs it, not the left's; `MaxV` and `MinV` are on
   the vector swap list. `skills/the-jit.md` and the class Javadoc say it as corrected.


**The contract table (556a2, branch `varka-556-contract`)** holds 3.3's pairs with three changes.
`x - x2` is fixed, not open (item 8). Picks decided by their conditions are added, each one a
rule that ignored the condition gets wrong: `IF` and `CASE` taking the branch without the open
sum and with it, `coalesce` over a null column and over an arithmetic with one null operand
(item 3), `greatest` and `least` over a column NaN and an open sum, a `FLOAT` pick, a conversion
of an open NaN (item 2), and item 7's `sqrt(least(x + x2, -1.0))`, which must be rejected; a
null over stale bits (an `UnsafeRow`) and `BOOLEAN` values. Dropped from 3.3, with the reason:

- *A `FLOAT` lane carrying other high bits*: no comparator receives one. `compareBodies` reads
  four bytes, and the IR checks get both answers from the lane in one representation, so the
  pair would test one no caller hands over.
- *A bit output's data bit under a clear validity bit, and a selection bitmap*: those are compared
  as bits by `compareBodies` and by `VarkaKernelChecks`' selection check, outside
  `VarkaSameAnswer`; `VarkaEvaluatorComponentsSuite` holds both (11), not the twin.
- *The `nanvl` pair over the IR*: Varka does not lower `nanvl`, and the shape written for it was
  invented; the expression pair stays.

## 11. Outcome of 556b (4 November 2026, branch `varka-556b-twin`)

**Built**, stacked on 556a with 556a2's two commits folded in: the twin runs 556a2's table and
replaces the restatement 556a2 carries, which two pull requests would show added, then deleted.

- *The sql/core twin*, `VarkaSameAnswerCoreContractSuite`, holds the table against the
  comparators only sql/core reaches: `VarkaKernelRunner.compareBodies` at `TYPE`, over one-row
  Arrow outputs built from Spark's field for the pair's type, with the evaluator's width and
  floating flag (`VarkaKernelRunner.outputWidth` and `floatingOutput`, moved out of
  `VarkaEvaluatorBase`; public, as `VarkaKernelRunner` is in `execution.varka` and both callers
  in `execution`); `VarkaExpressionHarness.sameRow` at `EXACT` and, with `unfixedNaN`, at `ROW`;
  `checkAnswer`'s `QueryTest.sameRows` at `ANY_NAN` (9.2); and `VarkaSparkFuzz`'s `Row.toString`
  at `ANY_NAN`, decimals apart (a collected row carries its type's scale). Every pair but the four
  decimals the type cannot hold reaches `compareBodies`, two of them disagreeing at the type's
  scale (`1.50` against `1.51`, and a `DECIMAL(38,4)` apart only in its high 8 bytes). One
  null-free row is a batch the running check skips (`dense && len == 1`); a nullable batch (a null
  input's row skipped) and a selection bitmap are a two-row case in `VarkaEvaluatorComponentsSuite`
  beside its bit output under a clear validity bit, so the twin adds neither pair.
- *The suite-local rules* (rule 7) are row-level calls: `VarkaSparkOracle.openNaN` in
  `VarkaEmitterFloatingArithSuite` for `twoUnlikeNaNs`, and `VarkaSameAnswer.sameRow` over the
  row engine's optimized projection in `VarkaFloatingLaneSuite` for `nanAsOne`.
- *The lint rule*, `nan-compare` in `dev/varka_lint.py`, reads an expression across its
  continuation lines, outside `VarkaSameAnswer` and the contract suites: two answers' bits
  compared (raw or canonical; a constant operand is a sign test), `Double.compare(a, b) == 0`, or
  two un-negated NaN tests joined by `&&`, unless `openNaN` decides the pair. It catches five of
  the six comparators 556 removed; it cannot see `nanAsOne`'s one-column relaxation, a boxed
  `equals`, or a rule behind a helper of another name. Over the tree it finds six expressions, on
  no line a commit here writes; one is a comparator, `VarkaEmitterFloatingArithSuite`'s
  `nanByNess` acceptance of any two NaNs, left as it was.

**Where verdicts move** (3.4, 7): a row with two unlike NaN inputs no longer excuses every form
in `VarkaEmitterFloatingArithSuite`, nor `nanAsOne` every NaN of a column over two operands
(`x1 - x2`, `try_divide(x1, x2)`) in `VarkaFloatingLaneSuite`; only a NaN open by its provenance
agrees as any NaN. All of it passes: no Varka answer moved.

**Left for m8's math.** `VarkaSameAnswer`'s `Other` step treats a two-operand math function over
two column NaNs as fixed, but fdlibm returns `x + y` for NaN operands, an add, so its payload is
tier-dependent; when m8's math lands on these suites, those nodes are marked either-payload.

**Size and tests.** `dev/varka_msg_check.py size --base varka-556-same-answer` reads 995 counted
lines, 458 of them 556a2's. `dev/varka_matrix.sh --defaults` (the both-bodies check and the
sanitizer on) over the two contract suites, `VarkaEvaluatorComponentsSuite`,
`VarkaFloatingLaneSuite` and `VarkaEmitterFloatingArithSuite`: RESULT.
