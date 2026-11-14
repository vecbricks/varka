# VARKA-567: One rewrite of a coverage row into an InSet, for the test sources

*Opened 11 November 2026 as a build sheet. The row (`PLAN.md` row 567, from #1349's Build) had no
plan file. Its text: the rewrite of an `In` row into an `InSet` row is written twice in the test
sources, in `VarkaWarmupReach.java` and in the bytes oracle of `VarkaEmittedBytesSuite.scala`;
only one had learned that a decimal row's elements arrive under a cast, so the reach suite threw
a `ClassCastException` on the new decimal rows. Done when one helper in `VarkaSqlResolve.scala`
makes the rewrite, folding each element with `eval`, and both suites call it.*

## Build sheet (11 November 2026)

Follow `BUILD_SHEETS.md` (same folder). Everything below was read at origin/master `c62f492fc89`.
`<scala>` is `sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka`
and `<java>` the same path under `src/test/java`.

**What is stale in the row.** The Java copy already folds each element with `eval(null)` (the
`ClassCastException` was fixed in #1349). What still differs: the Java copy has no `foldable`
check and passes a row that claims `InSet` but is not an `In` through silently, where the Scala
copy fails. **Decided here: the Scala behaviour wins** - a row that claims `InSet` and does not
resolve to an `In` of foldable elements fails loudly.

**A third copy, out of this row.** `VarkaWidthAuditShapes.asCatalystClaims` (`<scala>`) is a
third rewrite with a third behaviour: it matches only `Literal` elements and passes anything
else through, so the decimal row `d18 IN (11 to 16 decimal literals)` reaches the width audit as
an `In`. Changing it could move `width_audit.json`, and #1478 and #1484 both edit that function's
file. It stays as it is; the agent reports it, and the lead files it.

### B0. Premise re-check

| Command | Expected |
|---|---|
| `grep -n 'def optimizeIn\|def inSetAsClaimed' <scala>/VarkaSqlResolve.scala` | one line, `def optimizeIn` |
| `grep -n 'private def asCatalystClaims' <scala>/VarkaEmittedBytesSuite.scala <scala>/VarkaWidthAuditShapes.scala` | one line in each file |
| `grep -n 'inSet && expr instanceof In in' <java>/VarkaWarmupReach.java` | one line, in `coverageShapes` |
| `grep -c '"InSet"' sql/varka/coverage.json` | `13` (twelve rows and the admitted-classes list) |

### B1. Scope

1. **`<scala>/VarkaSqlResolve.scala`**, after `optimizeIn`, a public method (Java calls it as a
   static, as it calls `parse`):

   ```scala
   /**
    * `e` as the optimizer hands a coverage row to the compiler when the table records it as an
    * `InSet` (`claimsInSet`): `OptimizeIn`'s rewrite, each element folded with `eval` first, as
    * `ConstantFolding` folds the cast coercion puts over a decimal literal of another type. A row
    * that claims an `InSet` and is not an `In` of foldable elements throws, since the rewrite is
    * all that stands between it and the compiler's `In` arm; any other row is returned as it is.
    */
   def inSetAsClaimed(e: Expression, claimsInSet: Boolean): Expression = e match {
     case In(value, list) if claimsInSet && list.forall(_.foldable) =>
       InSet(value, list.map(_.eval()).toSet)
     case other if claimsInSet =>
       throw new IllegalArgumentException("a row the table records as an InSet resolved to " +
         s"${other.getClass.getSimpleName}: the executable spelling and the catalyst field " +
         "disagree")
     case other => other
   }
   ```

   Add `In` and `InSet` to the file's `catalyst.expressions` import.
2. **`<scala>/VarkaEmittedBytesSuite.scala`**: delete `asCatalystClaims` and its scaladoc; in
   `coverage`, `parsed.map(asCatalystClaims(_, catalyst))` becomes
   `parsed.map(VarkaSqlResolve.inSetAsClaimed(_, catalyst.contains("InSet")))`, and the comment
   above `parsed` names `inSetAsClaimed` instead of `asCatalystClaims`. Drop `In` and `InSet`
   from the import if nothing else uses them (`dev/lint-scala` reports an unused import as a
   warning only; remove it anyway).
3. **`<java>/VarkaWarmupReach.java`**, `coverageShapes`: replace the `if (inSet && expr
   instanceof In in) { ... }` block with `expr = VarkaSqlResolve.inSetAsClaimed(expr, inSet);`.
   Remove the imports of `In` and `InSet` (nothing else uses them); keep `HashSet`, which other
   methods use. The method's javadoc sentence about the `InSet` stays true.
4. **`<java>/VarkaDecimalInSuite.java`**, two tests after `inSetPastTheThreshold`. Each builds
   its columns once (methods `inSetRewriteIsTheOptimizers` and `inSetClaimIsRefused`), `var cols = seq(List.of(VarkaSqlResolve.column("i", DataTypes.IntegerType),
   VarkaSqlResolve.column("d18", new DecimalType(18, 2))))` (the file's own `seq` helper), and
   parses each string once with `VarkaSqlResolve.parse(text, cols)`; import `assertSame`,
   `assertThrows` and `DataTypes`.
   - `@DisplayName("the coverage table's InSet rewrite is the optimizer's, for an int and a
     decimal row")`: for each of two coverage rows' `executable` spellings, pasted as
     string literals - the rows whose `sql` is `i IN (11 to 16 int literals)` and
     `d18 IN (11 to 16 decimal literals)`, which
     `python3 -c "import json; [print(r['executable']) for r in json.load(open('sql/varka/coverage.json'))['expressions'] if r['sql'].startswith(('i IN (11', 'd18 IN (11'))]"`
     prints - parse with `VarkaSqlResolve.parse`, then assert
     `assertEquals(VarkaSqlResolve.optimizeIn(parsed, cols), VarkaSqlResolve.inSetAsClaimed(parsed,
     true))`, and `assertSame(parsed, VarkaSqlResolve.inSetAsClaimed(parsed, false))`.
   - `@DisplayName("a row that claims an InSet and resolves to anything else is refused")`:
     `inSetAsClaimed(parse("d18 > 0.42"), true)` and
     `inSetAsClaimed(parse("i IN (1, i)"), true)` each throw (`assertThrows(IllegalArgumentException.class, ...)`), and
     `assertTrue(e.getMessage().contains(...))` holds "resolved to GreaterThan" for the first and
     "resolved to In:" for the second.
5. **`PLAN.md` row 567**: replace the row's opening `**Planned**` with `**Done**`.
6. **This file**: an "Outcome" section at the end, as `BUILD_SHEETS.md` section 7 says.

**Out of scope:** `VarkaWidthAuditShapes.scala` (above); `optimizeIn` (it applies the threshold
and unwraps casts, which the coverage rows must not get: it is the test's oracle, not the
helper); any generated file; any main source.

### B2. Tests and their oracles

| Test | Asserts | Oracle |
|---|---|---|
| the two new `VarkaDecimalInSuite` tests | the helper builds the optimizer's `InSet`, and refuses the rest | Spark's own `ConstantFolding` and `OptimizeIn`, through `optimizeIn` |
| `VarkaEmittedBytesSuite` | the coverage rows emit the bytes they did | the committed `emitted_bytes.json` |
| `VarkaWarmupReachSuite` | the reach suite still compiles every coverage row | its own committed expectations |

### B3. Planted faults

1. In `inSetAsClaimed` build `InSet(value, list.toSet)` (no `eval`): the first new test fails on
   the decimal row (and on the int row: the set holds expressions, not values).
2. Delete the `case other if claimsInSet` arm: the second new test fails ("Expected
   java.lang.IllegalArgumentException to be thrown, but nothing was thrown").
Revert each before the next.

### B4. Commands, in order

```
flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaDecimalInSuite'
flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaEmittedBytesSuite *VarkaWarmupReachSuite'
git diff --exit-code -- sql/varka/emitted_bytes.json sql/varka/width_audit.json sql/varka/coverage.json
```

All pass, and the `git diff` exits 0. Then the planted faults, then `BUILD_SHEETS.md` section 6.

### B5. Done when

`grep -c 'asCatalystClaims' <scala>/VarkaEmittedBytesSuite.scala` prints `0`;
`grep -c 'inSetAsClaimed' <java>/VarkaWarmupReach.java <scala>/VarkaEmittedBytesSuite.scala`
prints `:1` and `:2` (the call, and the comment above `parsed`); the commands of B4 pass; `python3 dev/varka_push_check.py` exits 0.

### B6. Size

About 90 counted lines: the helper 18, the two callers 15 (mostly removed), the tests 40, the plan
and outcome 20.

### B7. Stop rules

- The first new test fails at the `assertEquals` on either row: the helper does not make the
  optimizer's form; report both expressions' `sql()` and stop.
- `VarkaEmittedBytesSuite` or `VarkaWarmupReachSuite` fails, or `emitted_bytes.json` differs.
- The refusal now fires in `VarkaWarmupReachSuite` on a coverage row (a row the Java copy let
  through): report the row's SQL and stop.

### B8. Decisions already taken

- The Scala copy's behaviour (fail loudly) wins over the Java copy's (pass through): the lead,
  11 November 2026, in the brief for this sheet.
- The width audit's copy is out of this row, as above.
- Row 495 also edits `VarkaEmittedBytesSuite.coverage`. This row goes first; 495's sheet says how
  to rebase onto it.

### B9. Afterwards (the lead)

For the agent: the commit subject is `[VARKA-567] Rewrite a coverage row into an InSet in one place`.

Review, push, and file the third copy (`VarkaWidthAuditShapes.asCatalystClaims`) as a row. Open
pull requests on the same files: #1436 (`VarkaSqlResolve.scala`, a new `replaced` method near
`optimizeIn`), #1428 (`VarkaEmittedBytesSuite.scala`, other functions), and #1428, #1478, #1484
(`PLAN.md`, other rows): rebase if one merged first.

## Outcome

Built on branch `varka-567-inset`, 13 November 2026, from origin/master `6f5786d7a4e`.

**What was built.** `VarkaSqlResolve.inSetAsClaimed` makes the one rewrite of a coverage row
into an `InSet` for the test sources: for a row the table records as an `InSet` it builds
`OptimizeIn`'s form, folding each element with `eval` first, and it throws an
`IllegalArgumentException` when such a row resolves to anything but an `In` of foldable
elements; any other row comes back as it is. `VarkaEmittedBytesSuite.coverage` and
`VarkaWarmupReach.coverageShapes` both call it, so the two copies of the rewrite are gone. The
bytes oracle already refused a row that claims `InSet` and resolves otherwise; the reach suite,
whose copy passed such a row through, now refuses it too, which is B8's decision. The third
copy, `VarkaWidthAuditShapes.asCatalystClaims`, keeps its own rule as B1 says and is filed as
row 607; only its comment changed, since it named the bytes oracle's rule, which moved here.

The two tests in `VarkaDecimalInSuite`: `inSetRewriteIsTheOptimizers` parses the two coverage
rows' `executable` spellings, the int row and the decimal row of eleven literals, and asserts
the helper's `InSet` equals `optimizeIn`'s for each, and that a row not claiming `InSet` is
returned as the same object; `inSetClaimIsRefused` asserts the refusal for `d18 > 0.42`, with
"resolved to GreaterThan" in the message, and for `i IN (1, i)`, with "resolved to In rather
than an In of foldable elements" (the wording the review's first round put in place of the
sheet's, which said "resolved to In" for a row that is an `In`).

**Results.** `catalyst/testOnly *VarkaDecimalInSuite` passed: 9 tests, 0 failed, 2.855 s of test
time. `catalyst/testOnly *VarkaEmittedBytesSuite *VarkaWarmupReachSuite` passed: 9 passed, 2
canceled by their own opt-in `assume` (the option and pairs audits, which cancel without their
environment variables), 0 failed; the committed-bytes pin took 58.6 s and
`VarkaWarmupReachSuite`'s three tests 45.0 s. `emitted_bytes.json`, `width_audit.json` and
`coverage.json` are unchanged (`git diff --exit-code` exits 0).

**Planted faults.** Both failed the test the sheet names, and each was reverted with
`git checkout --` before the next. `InSet(value, list.toSet)`, the rewrite without the `eval`
fold, failed `inSetRewriteIsTheOptimizers` on the int row first: `expected: <i#2 INSET 1, 10,
11, 2, 3, 4, 5, 6, 7, 8, 9> but was: <InSet@19b4d857>`, the set holding expressions rather than
values. Deleting the `case other if claimsInSet` arm failed `inSetClaimIsRefused` with `Expected
java.lang.IllegalArgumentException to be thrown, but nothing was thrown.`

**Predictions and differences from the sheet.** The sheet registered no numeric prediction: its
checks are the B4 commands and B5's greps, all of which pass. Two pull requests the sheet lists
as open, #1436 and #1478, had merged before the branch's base and are in `6f5786d7a4e`; their
changes to `VarkaSqlResolve.scala` (the `replaced` method) and the decimal comparison rows did
not conflict with this one, and B0's premise greps match at that base. The sheet's committed line
count, about 90, came out at 128: the two tests are longer than the estimate, mostly the two
rows' `executable` spellings and the two refusal assertions.

**The review.** One `/code-review high` pass found six points. Fixed: the refusal message now
says a claimed row resolved to X "rather than an In of foldable elements", where it said
"resolved to In" for a row that is an `In` whose elements are not foldable, and the test that
pins it follows; the width audit's comment no longer names the bytes oracle's deleted rule; and
the helper's scaladoc says the rewrite is hand-applied, so the cast-unwrapping rule that runs
before `OptimizeIn` in production is not applied, and names the one kind of row that would tell
the difference. Left, with the reason:

- The helper does not run that cast-unwrapping rule, so a row whose column and literals differ
  only by a widening cast would build the cast form where the optimizer builds the unwrapped one.
  No row in the table has that shape, and running the rule needs the columns at both call sites,
  which the helper deliberately does not take.
- `coverage.json`'s `catalyst` set lists every class of a row's tree, while the helper accepts a
  root `In` only. A future row whose `InSet` sits under an `And` would be refused by this suite
  as well as by the bytes oracle, which refused it before this change. No such row exists, and
  rewriting nested `In`s would change what the bytes oracle pins.
- `inSetRewriteIsTheOptimizers` pastes the two rows' spellings instead of reading
  `sql/varka/coverage.json`, so it would not follow either row if the table changed. It is the
  two-row test B2 specifies; reading the twelve claimed rows from the table is a change to that
  section, not to this one.
