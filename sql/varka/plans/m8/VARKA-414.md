# VARKA-414: `ym / -1` as a checked negation, with Spark's error

## 0. What exists now (admission check)

From the plans worktree at `130280562f3` (master `5c660201b35` plus plan commits) and
apache/spark#59956, read on 1 November 2026 with `git` and `gh api` GETs. Nothing was dispatched
and no JVM was run.

1. **The arm is not in the tree.** `VARKA-89.md` 9 says the negation arm "was built and
   reverted" inside #965, a squash; no commit or branch holds it (`git log --all` finds none).
   `VarkaIntervalCompiler.divide` declines `d == -1` with "interval divided by -1 overflows at
   Int.MinValue, which the row engine raises on" (`VarkaIntervalCompiler.java` in `divide`).
2. **`-ym` already fuses as the same node.** `UnaryMinus` over a year-month interval lowers to
   `IntNeg(negationMode(x), x)` (`VarkaIntervalCompiler.java:187`, `:271`), `FAIL` unless the
   operand's range excludes `Int.MinValue` (`:299-301`). Spark raises there through
   `IntervalMathUtils.negateExact`, with `context = null` (`util/IntervalMathUtils.scala:26-46`),
   so both engines' errors for `-ym` lack the `== SQL ==` block, and agree.
3. **Spark's error for `ym / -1` on `Int.MinValue` carries the context in both of its paths.**
   Interpreted, `DivideYMInterval.nullSafeEval` raises
   `INTERVAL_ARITHMETIC_OVERFLOW.WITH_SUGGESTION` through `checkDivideOverflow(...,
   origin.context)`; generated, `doGenCode` raises `ARITHMETIC_OVERFLOW` through
   `overflowInIntegralDivideError(errCtx)`, `errCtx` being `origin.context`
   (`intervalExpressions.scala:733-745`, `:799-801`, `:803-825`). Two conditions for one input,
   which SPARK-60746 (apache/spark#59956, open on 1 November 2026) makes one.
4. **Varka's fallback evaluates Spark's expression, not the lowering.** A declined batch of a
   projection runs `UnsafeProjection.create(projectList, ...)` (`VarkaColumnarToRowExec.scala:315`)
   or the merge projection over the residual entries (`:338-355`), both over the original
   `NamedExpression`s, so a `DivideYMInterval` there generates `doGenCode`'s raise with its
   `errCtx`. Read alone, this says the declined batch should raise Spark's own error with its
   block. So the obvious cause (the fallback evaluating the negation, `VARKA-103.md` 8 point 8) is
   not what the code shows.
5. **The comparison is of the full message.** `VarkaMaybeThrows.sameError`
   (`VarkaMaybeThrows.scala:168-171`) compares condition, SQL state, parameters, message and
   exception class, the message holding the context block. Both suites drive SQL text
   (`VarkaYmDivideSuite.scala:104`, `checkFused("SELECT ...")`), so both engines' expressions come
   from a parse with an origin.
6. **A declined batch elsewhere keeps the context.** `VarkaYmDivideSuite` compares the full raised
   outcome for `(ym + ym2) / 7` over a sum that overflows and for `(ym + INTERVAL '2147483647'
   MONTH) / 7`, fused and declined, and both engines raise the same error (`VARKA-89.md` 9.1).

### What differs from the row text

- The row's evidence points at the fallback evaluating a negation (`negateExact`'s null context).
  The fallback does not evaluate the lowering (item 4), so that is a hypothesis to test, not the
  cause, and the plan lists the others below. The cause is not readable from the tree.
- SPARK-60746 changes which condition Spark's generated code raises, not whether it carries the
  context, so the row does not wait for it. It does decide which condition the oracle and the
  fallback must share once the arm fuses (question 2).

## 1. Goal and non-goal

**Goal.** Know why the reverted arm's raised error lost Spark's context block, fix it or state
its limit, and then either fuse `ym / -1` with errors identical to Spark's or keep it declined
with the reason recorded.

**Non-goal.** No change to `-ym` (item 2) or to the other divisors. No change in Spark's
interval code: SPARK-60746 is upstream's, and enters only through a sync.

## 2. Design

### 2.1 Step 1: the diagnostic (laptop, by the build agent)

A debugging run, one suite in one JVM under the shared lock, as the rule (CI verifies a pull
request, the laptop debugs) allows; the pull request's verification is CI's.

Rebuild the arm on a branch: in `divide`, `d == -1` returns `new IntNeg(negationMode(months,
...), months)` after the operand, as `UnaryMinus` does. Then run `VarkaYmDivideSuite` with one
added case, `SELECT ym / -1 FROM t` over a table holding `Int.MinValue` months, and print for
each engine the condition, the parameters, the message and the stack of the raise. The stack
answers which hypothesis holds:

- **H1, the kernel raises rather than declines.** The Varka raise's stack ends in an emitted
  kernel class or a Varka runtime helper, not in a generated `SpecificUnsafeProjection`. Then the
  `IntNeg` `FAIL` lowering in a projection raises its own error for that operand position, and
  the fix is the decline it is meant to be (or the raise given `origin.context` of the Spark
  expression that the node came from).
- **H2, the fallback evaluates an expression with no origin.** The stack ends in a generated
  projection, but the expression it evaluates is a copy made outside `CurrentOrigin.withOrigin`
  (a rewrite in Varka's planner or the residual merge). Then the fix carries the origin, and a
  test pins that every Varka rewrite keeps it.
- **H3, the two engines take different raise paths.** Spark's side raises from the interpreter
  (`INTERVAL_ARITHMETIC_OVERFLOW`) and Varka's from the generated code (`ARITHMETIC_OVERFLOW`), or
  the reverse, and the message differs by condition as well as by block (0 item 3). Then the
  difference is SPARK-60746's, and the fix is the oracle's (question 2).
- **H4, none of these.** The trace is recorded and the arm stays declined with the reason.

Each hypothesis predicts one stack; the step records the two messages and the stack in this file's
section 9 before any fix.

### 2.2 Step 2: the fix, by what step 1 found

Under H1 or H2, the fix in Varka's code, behind no option (an error's text is not a form), and the
arm fused. Under H3, the arm fused once both engines raise one condition: today that needs
the oracle to accept the pair of 0 item 3 for this input, or the sync that brings SPARK-60746
(question 2). Under H4, the decline stays and its reason string names this file.

## 3. Tests

1. `VarkaYmDivideSuite`: `ym / -1`, `ym / CAST(-1 AS SMALLINT)` and `ym / CAST(-1 AS TINYINT)`
   over a table that fits (fused, answers equal) and one with an `Int.MinValue` row (declined
   batch, the raised outcome equal to Spark's in full, `checkSparkAnswerMaybeThrows`), under ANSI
   and LEGACY (the raise is every mode's).
2. The coverage table's `ym / -1` row, naming `differential`; `VarkaCoverageDifferentialSuite`
   over it.
3. Under H2, a test that the rewrite named by step 1 keeps the origin of the expression it
   replaces.

## 4. Predictions and costs, registered before the build

1. Step 1 shows H1 or H2, not H3 (**[I]**: H3 would make every divisor's overflow differ, and
   item 6 shows a declined `/ 7` agreeing; `/ 7` never reaches `checkDivideOverflow`'s raise,
   though, so this is weak).
2. `ym / -1` fuses with identical errors, and `emitted_bytes.json` gains one row and changes none.
3. Added test time under 10 s: three queries in a suite whose cases run in seconds.

## 5. Order, files and size

1. The diagnostic, on a branch, recorded here.
2. The fix and the arm, one pull request, its PR Build and an option-matrix dispatch.

| file | what |
|---|---|
| `.../codegen/VarkaIntervalCompiler.java` | the `-1` arm |
| the file step 1 names | the fix |
| `VarkaYmDivideSuite.scala`, the coverage table | 3's tests |
| generated | `coverage.json`, `emitted_bytes.json`, `docs/sql-varka.md` |

Size: **small**, about one day with the diagnostic.

## 6. Out of this row

- SPARK-60746 itself (upstream, open), and the day-time form `dt / -1` (`DivideDTInterval`, long
  lane), which is row 103's.

## 7. Questions for the owner

1. **If H3 holds, whether to fuse before SPARK-60746 syncs.** (a) Keep `-1` declined until the
   sync brings one condition; (b) fuse now, with the oracle accepting the two conditions for this
   input. *Recommended: (a)*: (b) would hold Varka to a pair of answers vanilla gives for one
   input, and the decline costs one divisor.

## 8. Done when

The cause is recorded in section 9 with both messages and the stack; and either `ym / -1` fuses
with errors identical to Spark's in `VarkaYmDivideSuite` and the coverage differential, or it stays
declined with the reason in its decline string and in this file.

## 9. Outcome

(Step 1's messages and stack, then the fix.)
