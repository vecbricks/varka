# VARKA-607: The width audit sees a coverage row's InSet

## 1. Where this came from

Row 607 of `PLAN.md`, added 11 November 2026 on the owner's word from row 567's build sheet
(#1513). Row 567 (merged as #1522) moved the two rewrites of a coverage row's `In` to the
optimizer's `InSet` in the test sources onto one helper, `VarkaSqlResolve.inSetAsClaimed`, and
filed the third copy as this row. Its text: **One copy of the `In` to `InSet` rewrite in the test
sources.** `VarkaWidthAuditShapes.asCatalystClaims` is a third rewrite with a third rule: it
matches only `Literal` elements and passes anything else through, so the decimal row
`d18 IN (...)`, whose elements arrive under a cast, reaches the width audit as an `In`. **Done
when** `asCatalystClaims` is gone and `VarkaWidthAuditShapes` calls `inSetAsClaimed`, taking its
rule of failing loudly; `grep -rn 'contains("InSet")\|equals("InSet")'` over the catalyst test
sources finds only the helper's callers' claim reads, with no rewrite beside them; a test shows
the audit's `d18 IN (...)` row arrives as an `InSet`; `VarkaMaskOfWordSuite` passes; and
`dev/varka_regen_generated.sh --all`, run on the audit's host (the laptop), leaves
`width_audit.json`, `coverage.json` and the docs blocks unchanged, or the pull request names and
explains each row that moves.

## 2. The admission check, done

Read at origin/master `230773d872d` (14 November 2026). Row 567 has landed (`#1522` puts
`inSetAsClaimed` at `VarkaSqlResolve.scala:102`), and #1478 and #1484 merged before the base, so
the row's start condition holds.

**Where and what.** `asCatalystClaims` is `VarkaWidthAuditShapes.scala:130`; its rule is

```scala
case In(value, list) if catalyst.contains("InSet") && list.forall(_.isInstanceOf[Literal]) =>
  InSet(value, list.map(_.asInstanceOf[Literal].value).toSet)
case other => other
```

Its one call is `coverageRows` at `:152`, and `coverageRows` reaches the trees through `all` at
`:264`: `VarkaWidthAuditProbe.scala:94` (the forked probe the census is read from) and
`VarkaMaskOfWordSuite.java:78`.

**Why the decimal row reaches the audit as an `In`.** `coverage.json` records 12 rows whose
`catalyst` field holds `InSet`. Eleven of them spell their elements as literals of the column's
own type, so `forall(_.isInstanceOf[Literal])` holds and the copy rewrites them. The twelfth,
`d18 IN (11 to 16 decimal literals)`, spells decimal literals whose precision is at most 10;
`resolve` runs the analyzer, which widens each literal to the column's `DECIMAL(18,2)`, so
every element is a `Cast` over a literal, not a `Literal`, and the guard fails: the table says
`InSet` and the audit audits an `In`. The row's own `note` in `coverage.json` says the optimizer
hands it "an InSet of Decimals, each read at the column's scale".

**What the audit records for it today.** `width_audit.json`: at 128 bits the row declines for the
two-lane host reason (VARKA-503), and at 256 and 512 bits it records no refusal.

**The rule that replaces it is safe for all 12 rows.** `inSetAsClaimed` rewrites an `In` whose
elements are all `foldable`, folding each with `eval`: true for the 11 literal rows, and true for
the decimal row, whose casts over literals are foldable and whose `eval` is the literal's decimal
value. The helper's third arm throws where the deleted copy passed a claimed row through, so the
one thing the change would reject is a row the table records as an `InSet` that resolves to
anything else - none exists at this master, and the probe would fail loudly on one rather than
audit it.

**The grep the row asks for.** `grep -rn 'contains("InSet")\|equals("InSet")' sql/catalyst/src/test`
prints three lines: `VarkaWarmupReach.java:348` and `VarkaEmittedBytesSuite.scala:241` (row 567's
callers reading the claim) and `VarkaWidthAuditShapes.scala:131` (this copy's rewrite). After the
change the third is gone, leaving the two claim reads and no rewrite beside either.

## 3. The design

### 3.1 The mechanism

Delete `asCatalystClaims` and its scaladoc. Add to `VarkaWidthAuditShapes`, directly after the
`columns` list it reads and before `coverageRows`:

```scala
  /**
   * A coverage row's executable spelling as the audit builds it: parsed and analyzed over the
   * table's columns.
   */
  private[varka] def parsedRow(executable: String): Expression =
    VarkaSqlResolve.resolve(CatalystSqlParser.parseExpression(executable), columns)

  /**
   * `executable` as the audit hands the row to the compiler: `VarkaSqlResolve.inSetAsClaimed`
   * folds an `In`'s elements into the `InSet` the optimizer builds where the coverage table
   * records the row as one (`VARKA-567`'s rewrite, which the test sources share). A decimal
   * row's literals arrive under the analyzer's widening cast, so the fold, which evaluates each
   * element, is what makes the audit see the form a plan builds; a row that claims an `InSet`
   * and resolves otherwise throws there rather than being audited as an `In`.
   */
  private[varka] def resolvedRow(executable: String, catalyst: Set[String]): Expression =
    VarkaSqlResolve.inSetAsClaimed(parsedRow(executable), catalyst.contains("InSet"))
```

In `coverageRows`, the two lines

```scala
      val resolved = asCatalystClaims(
        VarkaSqlResolve.resolve(CatalystSqlParser.parseExpression(executable), columns), catalyst)
```

become

```scala
      val resolved = resolvedRow(executable, catalyst)
```

and `In`, `InSet` and `Literal` leave the `catalyst.expressions` import (nothing else in the file
uses them). `VarkaWidthAuditSuite` gains one test (section 5) calling `parsedRow` and
`resolvedRow`.

### 3.2 What is deliberately unchanged

The 11 claimed rows whose elements are `Literal`s: `eval` on a literal is its value, so their
`InSet`s are the same sets the deleted copy built and their kernels do not move. The compiler,
the emitter and the IR: no main source changes, so no emitted byte changes for any form. The
bytes oracle and the reach suite: row 567 already put them on `inSetAsClaimed`, and their pinned
`emitted_bytes.json` reads the compiler's own resolution, not the audit's. `coverage.json` and
the docs blocks: `VarkaCoverageSuite` renders them and never calls `VarkaWidthAuditShapes`; the
audit's only contribution to the table is its census column, which section 6 holds fixed.

### 3.3 Registered op counts

None. No emitter change, so no `IntVector` invocation count or bytecode size moves; the
registration this row has is the census, which section 6.1 predicts and section 9 scores.

## 4. Files

| file | what |
|---|---|
| `sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaWidthAuditShapes.scala` | delete `asCatalystClaims`; add `parsedRow` and `resolvedRow`; call the helper |
| `sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaWidthAuditSuite.scala` | the test that the `d18 IN` row arrives as an `InSet` |
| `sql/varka/plans/m8/PLAN.md` | row 607 opens **Started**, "done when it merges" |
| `sql/varka/plans/m8/VARKA-607.md` | this plan and its Outcome |

## 5. Tests, and what each is for

| test | what it pins | oracle |
|---|---|---|
| `VarkaWidthAuditSuite`'s "the coverage table's d18 IN row reaches the audit as an InSet" | `parsedRow` gives an `In` whose elements are not all `Literal`s (the old rule's blind spot), and `resolvedRow` gives an `InSet` | the coverage table's own row, whose `catalyst` field records the optimizer's `InSet` |
| `VarkaMaskOfWordSuite`, all four tests | the audit's shapes still compile and scan, and the row is not lost to a refusal (`scanned > 200` and `> 200 * 30`) | its own data-flow pass and counted kernels |
| `dev/varka_regen_generated.sh --all` | `width_audit.json`, `coverage.json`, the docs blocks and the census do not move | its own regeneration and `dev/varka_census.py --check` |

No pinned fixture is expected to move. If the audit's kernel for the row does move, the census
test names the shape and its before/after lines, which is what the row's "names and explains each
row that moves" asks the pull request to carry.

## 6. The measurement

No benchmark: this row changes what the audit feeds the compiler, not what the compiler emits,
so the measurement is the audit's own census on the laptop, plus the row's grep.

```
grep -rn 'contains("InSet")\|equals("InSet")' sql/catalyst/src/test
VARKA_AUDIT_REGEN=true dev/varka_regen_generated.sh --all
git status --short
git diff --exit-code -- sql/varka/width_audit.json sql/varka/coverage.json docs/sql-varka.md
```

### 6.1 Predictions, registered before the run

1. `width_audit.json` is byte-identical. The `In` and `InSet` lowerings read the same decimal
   values (`constantValue` folds the cast; `eval` folds the literal), the literal slots keep
   canonical order, and the row's only recorded entry, the 128-bit host decline, is keyed by its
   SQL and produced by the host admission, both unchanged.
2. `coverage.json` and the docs blocks are byte-identical: nothing in `VarkaCoverageSuite` reads
   the audit's shapes, and its census column rests on 1.
3. `VarkaMaskOfWordSuite` passes with the same `> 200` coverage-row kernels scanned.
4. The grep prints two lines after the change, `VarkaWarmupReach.java:348` and
   `VarkaEmittedBytesSuite.scala:241`, and no third.
5. The counted size stays under 200 lines.

## 7. Risks

1. The `InSet` form could lower differently from the `In` form after all - a different element
   order or a value fold at another point. The census test then names the moved shape, and the
   Outcome explains the move row by row; anything unexplainable is a stop.
2. `inSetAsClaimed` throws on a claimed row that is not an `In` of foldable elements, and the
   probe would fail `beforeAll` with no shape named. The new test holds the decimal row's case;
   a second row that fails it is a stop, not a rewrite.
3. The three removed imports could hide a use outside `asCatalystClaims`; the compile of the
   catalyst test sources catches that.
4. `VarkaWidthAuditSuite` forks five probe JVMs in `beforeAll`, so the new test inherits that
   cost; it reads nothing from the probes, and the regen runs the suite anyway.

## 8. Sequencing

1. This plan, from `TEMPLATE_TASK.md`, with the admission check above. No code.
2. The implementation in one commit: delete `asCatalystClaims`, add `parsedRow`/`resolvedRow`
   and the call, add the test, open row 607 **Started** with "done when it merges", run the
   checks of section 6, and write section 9.

## 9. Outcome

Filled in with the implementation.
