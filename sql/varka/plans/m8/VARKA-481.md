# VARKA-481: The checked narrow multiply through the range guard

## 0. Spark's behaviour, which the row keeps

Read on 30 October 2026 at master `ef32e007d3a`, in the fork's own sources under
`sql/catalyst/src/main/scala/org/apache/spark/sql/`. Every answer below is Spark's; Varka matches
it by declining a batch (ANSI) or nulling a lane (TRY), never by raising itself.

1. **The result type is the left operand's.** `BinaryArithmetic`'s internal type is
   `left.dataType` for every non-decimal pair (`catalyst/expressions/arithmetic.scala:218-222`,
   `dataType` at `:239`), and analysis gives both operands one type, so `TINYINT * TINYINT` is
   `TINYINT` and `SMALLINT * SMALLINT` is `SMALLINT`: nothing widens to `INT`.
2. **LEGACY wraps at the type's width**: the generated code is `(byte)(a * b)` (or `(short)`),
   `arithmetic.scala:333-335`.
3. **ANSI raises `BINARY_ARITHMETIC_OVERFLOW`, not `ARITHMETIC_OVERFLOW`.** `ByteExactNumeric.times`
   and `ShortExactNumeric.times` compute the int product and check it against the type's range
   (`types/numerics.scala:45-49`, `:81-85`), raising through
   `QueryExecutionErrors.binaryArithmeticCauseOverflowError` (`errors/QueryExecutionErrors.scala:
   672-686`): error class `BINARY_ARITHMETIC_OVERFLOW`, SQLSTATE `22003`
   (`common/utils/src/main/resources/error/error-conditions.json:444-449`), with the parameters
   `value1`, `symbol` (`*`), `value2` and `functionName` (`try_multiply`).
4. **The error has only a `Short` overload**, so both operands render through
   `toSQLValue(v, ShortType)` (`errors/QueryErrorsBase.scala:55`) as a short literal
   (`catalyst/expressions/literals.scala:670`): a `TINYINT` overflow prints `100S * 2S caused
   overflow`, with the `S` suffix. Varka never builds this text; the row engine raises it after
   the batch declines, so its parameters are Spark's by construction.
5. **TRY nulls the row.** `try_multiply` over numerics is `Multiply(l, r, EvalMode.TRY)`
   (`catalyst/expressions/TryEval.scala:268-274`); the generated code wraps the exact call in a
   `try` that sets the result null, and `eval` catches to null (`arithmetic.scala:356-396`). A null
   operand is a null result in every mode.

## 1. Where this came from

The `/code-review max` of #989 (23 October 2026, `VARKA-329.md` 9.8, a finding cut by the
review's cap): an ANSI or TRY `TINYINT`/`SMALLINT` multiply stays a narrow `IntArith`, held up by
special cases in five places, and its int-lane range compare repeats `emitRangeGuard`. The general
form is the one `CAST(bigint AS INT)` already has under ANSI,
`NarrowLane(GuardedRange(IntArith(MUL, WRAP, WidenLane(x), WidenLane(y)), MIN, MAX))`, which the
existing int-lane rule drives at the int lane. TRY needs a `NULL` mode on `GuardedRange`, which
`try_cast` of a bigint lacks too (row 332). This row comes after row 480, whose narrow-column range
and arithmetic changes touch the same code; row 480 has merged, and the line numbers below are
master's after it (`ef32e007d3a`, refreshed 30 October 2026).

## 2. The admission check, done

From the code (25 October 2026, re-checked 30 October 2026; paths under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/`):

1. **The five special cases, and nothing else depends on them.**
   - (a) `varka/VarkaKernelPlan.java:179-181` and its walk at `:226-259` (javadoc `:226-240`, the
     two methods `:241-259`): `emissionLane` forces the int lane for a narrow non-`WRAP` `MUL`,
     and `kernelLane` (`:203-210`) joins a native narrow root beside it at the int lane (989-9).
   - (b) `VarkaNodeCompiler.java:1119`: `&& !narrow` exempts it from the checked-multiply decline.
   - (c) `varka/Analysis.java:676-680`: the clause that gives a narrow multiply `INT_OVERFLOW`.
   - (d) `varka/VarkaVectorWalk.java:661-663`: `&& !narrow` on the "no int-lane overflow test"
     throw.
   - (e) `varka/VarkaVectorWalk.java:740-743`: the "emitted at the int lane" throw.
   No test pins either throw or the int-lane decision; the narrow suites assert answered or
   declined, not the status bit (`VarkaEmitterNarrowArithSuite.java`'s `answered`, `:622-626`).
   The `!narrow` at `VarkaNodeCompiler.java:1119` is the only one left in `arithOver`: the second
   one, which kept a narrow `FAIL` from being rewritten to `WRAP`, went with row 480. What protects
   narrow addition and subtraction now is the range analysis's column-only rule
   (`varka/VarkaRangeAnalysis.java:218-227`, stated in `arithOver`'s javadoc at
   `VarkaNodeCompiler.java:1091-1099`): narrow arithmetic is not ranged, so `cannotOverflow` fails
   and a checked narrow add keeps its check.
2. **The general form drives at the int lane on both arms by the existing rule.** `lanesIn` of
   `NarrowLane(GuardedRange(IntArith(..., WidenLane(x, INT), WidenLane(y, INT))))` holds `INT`, so
   `emissionLane` returns the int lane (`VarkaKernelPlan.java:179`), and the native arm never ran a
   multiply on a narrow lane anyway (its saturating difference, `VarkaVectorWalk.java:744-753`, is
   for addition and subtraction).
3. **The two compares are the same.** The multiply's (`VarkaVectorWalk.java:728-738`) and
   `emitRangeGuard` (`:1021-1035`) both compare against the narrow type's minimum and maximum and
   OR them; they differ in what consumes the mask - `emitOverflowMask` with `INT_OVERFLOW`, whose
   `NULL` arm clears the node's own word, against `emitGuardCollect` with `VALUE_RANGE`, which has
   no `NULL` disposal - and in the scratch they park in (`intArithTmp`, three slots, against
   `guardTmp`, one).
4. **`emitOverflowMask`'s `FAIL` arm is tied to `INT_OVERFLOW`.** It throws unless the node's
   registered refusal is `INT_OVERFLOW` (`VarkaVectorWalk.java:902-906`, the comment at `:902-903`
   and the check at `:904-906`): a collect the refusal map does not declare would find no
   accumulator. So a guard can hand its mask to that arm only when `Analysis` registered it as
   `INT_OVERFLOW`, which is what 3.2's emit-time lean does; an unregistered guard must collect
   through `emitGuardCollect` or emit no compare at all.
5. **Why the inner multiply is built as `WRAP` directly.** The range analysis gives a type range
   to a narrow column only and `UNKNOWN` to every other node off the int lane
   (`VarkaRangeAnalysis.java:216-236`), and `WidenLane` passes its child's range up (`:282-283`).
   Built through `arithOver`, `(y1 + y2) * y3` would therefore be an int `MUL` whose left operand,
   `WidenLane(y1 + y2)`, is `UNKNOWN`: `cannotOverflow` fails and `:1119`'s decline fires, where
   the shape fuses today. The exactness rests on the type bound on live lanes (3.1, step 2), which
   the compiler states by building `WRAP`.
6. **The call sites of `GuardedRange`'s constructor**: 8 in main code - `VarkaNodeCompiler.java:717`
   and `:750`, `VarkaTimeCompiler.java:483`, `:491` and `:564`, `VarkaChronoCompiler.java:356`, the
   column bound at `varka/VarkaRangeAnalysis.java:395`, and the rebuild in `withChildren`
   (`varka/VarkaVectorIR.java:1757`) - and 46 in tests, over 19 test files.
7. **The skip lines the option touches.** Under `checkIntOverflow=false`, ten lines of
   `sql/varka/matrix/skips.tsv` cover the checked narrow multiply alone: the coverage harness's
   `y1 * y2`, `try_multiply(y1, y2)`, `s1 * s2` and `try_multiply(s1, s2)` rows (`:40`, `:45`,
   `:48`, `:53`, `:56`, `:61`) and the narrow arithmetic suite's four `SMALLINT *` 2^24 slices,
   ANSI and TRY on both arms (`:160`, `:161`, `:166`, `:167`). Lines that mix the multiply with the
   other operators (`:148`, `:149`, `:151`, `:152`, `:154`, `:155`, `:168`, `:169`) also fail
   through it under the option.

## 3. The design

### 3.1 A `NULL` guard, and the multiply through it

1. **`GuardedRange` gains a mode** (`FAIL`, `NULL`), kept by a three-argument constructor for the
   existing call sites (2.6), rendered by `canonical` and carried by `withChildren` (a `FAIL` and a
   `NULL` kernel must not share a cache key), and split in the cost key. `withChildren` is the one
   rebuild among the eight main sites: if it called the kept three-argument constructor, a `NULL`
   guard would silently become `FAIL` on a rebuild, so it passes the mode, and the column bound at
   `VarkaRangeAnalysis.java:395` builds a fresh `FAIL` guard as before. Under `NULL` the guard
   clears its own word where the value is out of range: it owns a word (as a nulling `IntArith`
   does), sets `nullsFromValidInputs`, and has no pure word. `emitRangeGuard` hands its mask either
   to `emitGuardCollect` (`FAIL`) or to `emitOverflowMask`'s `NULL` arm. The reference evaluator
   gains the `NULL` arm; the fuzz grammar draws both modes; `narrow_range.smt2` gains the claim that
   a lane stays valid exactly when it was valid and in range. On its own this step moves no bytes.
2. **`narrowArith` builds the general form** for a `MUL` whose mode is not `WRAP`
   (`VarkaNodeCompiler.java:1045-1065`):
   `NarrowLane(GuardedRange(IntArith(MUL, WRAP, WidenLane(x, INT), WidenLane(y, INT)), MIN, MAX,
   mode), lane)`, directly rather than through `arithOver` (2.5 says why). The product of two
   narrow values is exact at the int lane (2^14 for bytes, 2^30 for shorts), so the `WRAP`
   multiply is the exact product and the guard alone decides the overflow.
3. **The special cases go**: (a) to (e) and their comments (`VarkaKernelPlan.java:226-240`,
   `VarkaVectorWalk.java:613-614, 617-619, 655-660, 715-724, 919-922`, `Analysis.java:670-675`,
   `VarkaNodeCompiler.java:1098-1099`, and in the test tree `VarkaProofFiles.java:2474-2475,
   2492`).
4. **No redundant narrowing.** An interior narrowed multiply, read by another node as in
   `(y1 * y2) + y3`, would take `emitWrapToNarrow`'s shift pair after a guard that already bounds
   it: two lanewise operations for nothing. The pair is skipped when the child is a `GuardedRange`
   within the target type's bounds.

### 3.2 The question for the owner: `checkIntOverflow=false`

Today the narrow multiply's check is an `INT_OVERFLOW` refusal, in the CHECKED family, which
`checkIntOverflow=false` turns off (`Analysis.java:325-331, 677`); the reference configuration then
computes the wrapped product. Through `GuardedRange` the check becomes `VALUE_RANGE`, in the
REARMED family, which no option turns off (`:337-341`). Two choices:

- **A. The guard stays on under `checkIntOverflow=false`** (the general form as it stands). The
  option no longer covers narrow multiplies; the ten `skips.tsv` lines of 2.7 that say "the
  overflow check is off by design" for them go false and are removed.
- **B. The option still turns it off**: a narrow multiply's guard is built only when
  `checkIntOverflow` is on, so the option keeps its meaning for every integer type; the skip lines
  stay.

B keeps one option meaning one thing across the integer types; A is simpler and is what
`CAST(bigint AS INT)` does today. *The owner chose B (25 October 2026):* the option is a priced
check (`Reason.PRICED_CHECK`), which exists so the A/B can price every ANSI and TRY int check
against the arithmetic it protects, and a cast was never in its scope; `guardDayProducers` is the
precedent for a priced option over a range guard.

*Where the condition acts, to settle before the code (the advisor's review of the choice):* the
plan first put it in `narrowArith`, at compile time, but every precedent acts at emit time on IR
that does not change - `INT_OVERFLOW` is gated in `Analysis` on an `IntArith(FAIL)` the compiler
always builds, `guardDayProducers` removes a `GuardedDay`'s check at emit, and
`VarkaArithmeticBenchmark` prices the option by emitting the same tree under `UNCHECKED_ARM`.
Emit time is the lean: `GuardedRange` carries a family beside its mode (an overflow check, not a
cast's range check, which the child's type cannot tell apart - `CAST(l + 1 AS INT)` is a
`GuardedRange` over long arithmetic), `Analysis` registers a marked guard as `INT_OVERFLOW` under
`checkIntOverflow`, and the shift pair after it is skipped only where that same refusal decides
the guard (`emitIntNeg`'s pattern, where `analysis.refusals` decides both). It keeps the option
acting in one place for every node and the status bit `INT_OVERFLOW`, which retires prediction
6.1.2. The marked guard's `FAIL` mask can then go through `emitOverflowMask`'s `FAIL` arm, whose
refusal check (2.4) it meets, and with the option off the guard is registered as nothing and emits
no compare: the value passes through, and the interior shift pair comes back to wrap it. If
`Analysis` cannot register the marked guard without special cases (c) to (e) coming back, the
condition goes to compile time instead, and section 3.1 then states that the option acts at
compile time for this node alone and what the off arm's tree is. The ten skip lines (2.7) cover
the TRY rows too (`try_multiply(y1, y2)`, `try_multiply(s1, s2)` and the TRY slices), so the `NULL`
mode leaves none stale, and the mixed lines stay as they are.

### 3.3 What is deliberately unchanged

Narrow addition and subtraction (their saturating difference on the native arm, and their check,
which the column-only range rule keeps, 2.1); `try_cast` of a bigint, which can take the new `NULL`
mode in row 332; the int and long lanes' own checked arithmetic. The int32 checked multiply of
`VARKA-104.md` (3.3, and 8 step 5) is the same tree one lane up -
`NarrowLane(GuardedRange(IntArith(MUL, WRAP, WidenLane(x, LONG), WidenLane(y, LONG)), INT_MIN,
INT_MAX, mode))`, exact because a product of two ints is below 2^62 - so this row's `NULL` mode is
also what its TRY form needs; that row builds it, this one does not.

### 3.4 Registered op counts

Per checked narrow multiply at the int lane: today two compares, an OR and the overflow mask over
three parked values; after, the same two compares and OR over one parked value, the guard's
collect or nulling, and no shift pair at an interior read. Asserted from `emitted_bytes.json`'s
diff and the emitter suite's counts once built.

### 3.5 The fusion budget and the cost model

- **Weight.** `weightOf` gives today's node 5 (`varka/VarkaEmitBudget.java:611`, a checked
  `IntArith`) and the new tree 6: `WidenLane` 1 twice, the `WRAP` int `MUL` 1, `GuardedRange` 2
  (`GUARD_WEIGHT`, `:547`) and `NarrowLane` 1. Conservative, since both widenings emit nothing in
  an int-driven kernel; not tuned here.
- **Cost feature.** Today's node counts the unpriced `IntArith/MUL/FAIL/BYTE` (or `/SHORT`, or
  `/NULL/`), absent from `varka/VarkaEmitCostTable.java`, and a tally with an unpriced feature
  predicts nothing (`varka/VarkaEmitCost.java:209-216`). The new ANSI tree counts only priced
  features - `GuardedRange/INT` (`VarkaEmitCostTable.java:137`), `IntArith/MUL/WRAP/INT` (`:159`),
  `NarrowLane` (`:207`) and `WidenLane` (`:237`) - so its group gets a prediction where it had
  none, and `predictionBoundsGroup` (`varka/VarkaLoopEmitter.java:1588-1592`) lets that prediction
  bound the group in the weight budget's place. A grouping can move. With the cost key split by
  mode (3.1, step 1), the TRY tree counts the unpriced `NULL` guard and stays weight-bounded until
  the cost table is refitted.
- **How it is measured.** The regenerated `emitted_bytes.json` is diffed row by row before it is
  committed, and any checked narrow multiply row whose kernel or method count moved is named in
  the outcome with its before and after; `VarkaEmitCostSuite` and `VarkaEmitterBudgetSuite` run on
  the branch. The pair of section 6 times the moved shapes.

### 3.6 Questions still open for the owner

1. **One arm per mode for the multiply's 2^32 sweeps** (5.1). If the two arms emit identical class
   bytes for every checked narrow multiply, which 3.1 predicts (both drive the tree at the int
   lane, and the IR no longer depends on `narrowLanes` for it), two of the four `SMALLINT *` sweeps
   repeat the other two. (a) Keep all four, as row 330's done-when reads. (b) Keep one arm per mode
   while a bytes-identity test, run on every build, fails if the arms ever diverge. Cutting a sweep
   is the owner's call under `VARKA-329.md` 4.4, so the plan asks and does not choose.

## 4. Files

| file | what |
|---|---|
| `varka/VarkaVectorIR.java` | `GuardedRange`'s mode, `canonical`, `withChildren` |
| `varka/Analysis.java`, `varka/Slots.java` | the `NULL` guard's word; special case (c) |
| `varka/VarkaVectorWalk.java` | `emitRangeGuard`'s two disposals; (d), (e); the skipped shift pair |
| `varka/VarkaKernelPlan.java` | special case (a) |
| `VarkaNodeCompiler.java` | the general form in `narrowArith`; (b) |
| `varka/VarkaEmitCost*.java` | the cost key by mode |
| `VarkaReference.java`, `VarkaIrGrammar.java`, `VarkaGuardSpecs.java` | the `NULL` mode's oracle, draws and specs |
| `VarkaProofFiles.java`, `sql/varka/proofs/narrow_range.smt2` | the `NULL` claim |
| `VarkaNarrowEmitBenchmark.java` and its results | the checked multiply's shapes (section 6) |
| `sql/varka/matrix/skips.tsv` | only under choice A |
| `sql/varka/emitted_bytes.json`, the cost table | regenerated |

## 5. Tests, and what each is for

1. The narrow suites (`VarkaEmitterNarrowArithSuite` at both arms, the TINYINT and SMALLINT
   conformance suites, the 2^24 SMALLINT slices) - the answers do not move.
2. A `NULL`-mode guard test in the emitter suite over every edge value, both widths, the oracle
   nulling where the guard fires - the new mode's own test.
3. `VarkaCoverageDifferentialSuite` - it derives the expected status from the guards, so the status
   is checked, not assumed: under 3.2's lean the marked guard is registered `INT_OVERFLOW`, the
   status bit stays `STATUS_INT_OVERFLOW`, and `VarkaGuardSpecs` reads a marked guard as that
   refusal where it reads an unmarked one as `VALUE_RANGE`.
4. The fuzz suite drawing both modes, and the option matrix (under choice B, the
   `checkIntOverflow=false` configuration still wraps, and the ten skip lines of 2.7 hold).
5. An interior narrowed multiply (`(y1 * y2) + y3`) emits no shift pair - the op count - and, with
   `checkIntOverflow` off, emits it.

### 5.1 The exhaustive proof

- **`TINYINT`: every pair.** The 2^16 pairs in one batch per operator, mode and arm, held to
  Spark's own `eval` on every row, already run on every build in `VarkaEmitterNarrowArithSuite`;
  they cover the new tree as they covered the old.
- **`SMALLINT`: every pair, opt-in.** The four 2^32 sweeps of `*` (ANSI and TRY, two arms) exist as
  opt-in tests under `-Dvarka.sweep=true`. At the floor of `results/VARKA-330-sweep.txt` - one
  2^32 sweep in 1106 s, timed on `+` in LEGACY, which checks nothing - the four take at least
  4 x 1106 s, about 74 minutes. They run once on the branch before the pull request opens, timed
  into `results/VARKA-481-sweep.txt`; the 2^24 slice of each runs on every build.
- **Where they run today.** No CI workflow runs these sweeps: no file under `.github/workflows`
  sets `varka.sweep`. They run only by hand, in the gate's sweep step (`dev/varka_gate.sh:169-172`)
  or as `results/VARKA-330-sweep.txt`'s command does, `testOnly *VarkaEmitterNarrowArithSuite`
  with `-Dvarka.sweep=true` and a `-Dvarka.sweep.filter`. The gate's step and `dev/varka_nightly.sh`
  pass `-z opt-in`, which by the nightly's own comment (`:161-162`) selects nothing in a JUnit
  suite such as this one, so this row runs them the record's way.
- **Two of the four may be duplicates.** If both arms emit identical class bytes for the checked
  multiply, the native and widened sweeps of each mode run one class twice; cutting them is
  question 1 of 3.6.

## 6. The measurement

The change is a simplification and makes no speed claim, but it changes the emitted code of every
checked narrow multiply, and nothing times one today: `VarkaNarrowEmitBenchmark` has seven shapes,
none of them an ANSI or TRY `*`. The baseline-first rule applies, for both of the row's changes to
the code: the parking slots (three parked values become one, 3.4) move every checked multiply's
body, and the skipped shift pair (3.1, step 4) moves every interior one.

1. **The baseline, its own pull request, on the unchanged code.** `VarkaNarrowEmitBenchmark` gains
   the checked multiply under both arms: `y1 * y2` and `s1 * s2` stored, each in ANSI and TRY, and
   an interior `y1 * y2 < y3` as a selection in ANSI, over data where no product overflows, so
   every arm answers. Run on a GitHub-hosted runner through `benchmark.yml` and committed under
   `results/` with its provenance. At 128 bits the widened arm declines a byte column by name, as
   it does for the class's other shapes.
2. **Then the pair** on the change's pull request, base the merge base.

### 6.1 Predictions, registered before the run

1. Every checked narrow multiply's kernel re-hashes; no answer moves.
2. *Retired (30 October 2026):* this prediction said fallback metrics would report `VALUE_RANGE`
   where they reported `INT_OVERFLOW`. Under 3.2's emit-time lean the marked guard keeps
   `INT_OVERFLOW`, so the fallback metrics do not move; the prediction comes back only if 3.2's
   compile-time fallback is taken.
3. Under choice B, `checkIntOverflow=false` still wraps a narrow multiply, and the skip lines hold.
4. The stored shapes of 6's baseline are within the runners' A/A spread of it: the work is the
   same int multiply and two compares, with one parked value where there were three; the interior
   shape is no slower, the shift pair being gone.
5. The two arms tie on every new shape at 256 bits, their class bytes being identical.

## 7. Risks

1. The range analysis's bound on a `GuardedRange` (`VarkaRangeAnalysis.java:278`) holds on live
   lanes only once a `NULL` mode exists (`VARKA-329.md` 4.2's qualification); a consumer that read
   a nulled lane's value would see the unbounded product. Test 2 runs the nulled lanes through
   consumers, and the analysis's javadoc states the qualification.
2. A literal under `WidenLane` in an int-driven kernel is a shape no test has reached; test 1's
   literal rows (`y1 * 3Y`) cover it.
3. The cost model's new prediction (3.5) can regroup a projection that holds a checked narrow
   multiply; the bytes diff names it and the pair times it.

## 8. Sequencing

1. After row 480 merges (merged): the `NULL` mode on `GuardedRange` with its oracle, grammar,
   specs and proof, moving no bytes.
2. The benchmark baseline (section 6), its own pull request, before the code that moves the bytes.
3. The general form in `narrowArith`, the special cases removed, the shift pair skipped, behind
   choice B's condition.
4. The generated files regenerated once; the four `SMALLINT *` sweeps (5.1), the gate and the
   matrix's scoped configuration; the pair.

**Ownership.** Row 481 owns the in-kernel `NULL` mode on `GuardedRange`. Rows 332 (`try_cast` of a
bigint), 343 (the floating casts' TRY nulls, whose row names the shared null disposal) and 349
(decimal arithmetic under TRY, whose row does not name it yet) depend on it and build on it
rather than adding a null disposal of their own; row 338's `TIMESTAMP_NTZ` TRY cast stays on
the fallback until it lands, and `VARKA-104.md`'s int32 checked multiply (3.3) takes the same
mode for its TRY form.

## 9. Outcome

(Written when the work lands.)
