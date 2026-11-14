# VARKA-610: The BIGINT sums' domain is their guard's own condition

## 1. Where this came from

Row 610 of `PLAN.md`, added 13 November 2026 from #1484's option matrix run 43504150236 on
MaxGekk/spark (head 031e96ac7ce), which failed two BIGINT domain tests under each of
`arrowBatchSize=1` and `arrowBatchSize=3`. The four `skips.tsv` lines those failures wrote
(354-357) carry the row until it lands. The row's text: **The domain check holds a row's domain
equal to its guards' condition, which the BIGINT sums' domain is not.** `VarkaDomains.check`
requires every batch outside a row's recorded domain to be one the guards condemn
(`VarkaDomains.java:436-440`), which holds only where the domain is the guard's own condition.
`l + l2`'s domain is the operands' input range, a sufficient condition narrower than the guard's
sum-overflow test, so a batch of outside rows that holds no overflowing row is answered, and
answered correctly; a smaller Arrow batch separates the outside rows so that such a batch
exists, which is why the two tests were red under the two configurations. **Done when** either the
guarded rows' domains become their guards' own conditions where SQL can state them, or
`VarkaDomains.check` states which direction it holds and why; and the four `skips.tsv` lines are
gone.

## 2. The admission check, done

Read at origin/master `9000fc5e911` (14 November 2026).

**The two directions the check holds.** For a guarded row, `VarkaDomains.check` cuts the edges
fixture by the row's recorded domain into inside, outside and unknown partitions and, for each
batch of each partition, reads the kernel's own verdict (`VarkaGuardSpecs.verdict`):

1. Inside or unknown, no batch may be one the verdict condemns
   (`VarkaDomains.java:430-433`): the domain may not promise an answer the guard refuses.
2. Outside, every batch must be one the verdict condemns, or one the all-null shortcut answers
   (`:436-440`): the domain may not withhold rows the kernel serves.

The two together say the domain is the guard's condition. On the fixture the second is the one
that fails under small batches.

**The recorded domain and the guard.** `coverage.json` records both sums as
`l BETWEEN -4611686018427387904 AND 4611686018427387903 AND l2 BETWEEN ...`
(`VarkaCoverageSuite.scala:487-492`; the table's comment at `:166` calls the domain "the
predicate inside which the row's kernel answers"). The range is a sufficient condition: an
operand pair inside it has its sum in `[-2^63, 2^63 - 2]`, which the long holds. It is not the
guard's condition: a pair just past it, `l = 2^62` and `l2 = 1`, is outside the recorded domain
and its sum is the long's own `2^62 + 1`, so the kernel answers it.

**The fixture's rows and the partitions.** `VarkaDomains.edgesSql` sets `l2` to 1 on every row
and overrides `l` on 13: the byte and short bounds and one past each (8 rows, all inside
`+/-2^62`), `Long.MIN_VALUE`, `Long.MIN_VALUE + 1`, `Long.MAX_VALUE - 1` and `Long.MAX_VALUE`
(the bigint family's 4), and one `timestampadd` row whose quantity is `Long.MAX_VALUE`. So the
recorded domain's outside partition is 5 rows, `MIN, MIN+1, MAX-1, MAX` and the `timestampadd`
row's `MAX`, while only the two `MAX` rows overflow `l + l2` and only the `MIN` row overflows
`l - l2`. At `arrowBatchSize=3` the fixture's order puts `[MIN, MIN+1, MAX-1]` in one batch,
which `l + l2` answers, and `[MAX, MAX]` in another, which `l - l2` answers; at
`arrowBatchSize=1` every outside row is its own batch and each such row answers. Under the
default batch size the whole outside partition is one batch, which holds an overflowing row, so
the check passed on batching luck.

**SQL can state the guard, and the table already does it for the NTZ rows.** `ntzDomain`
(`VarkaCoverageSuite.scala:221-230`) renders `if(<operand> IS NULL [OR ...], NULL, try_<op>(...)
IS NOT NULL)`, and its doc states the identity: Spark's `try_` form of the row has a value
exactly where the row does not raise. Nine call sites use it, for sixteen `TIMESTAMP_NTZ` rows
(the arithmetic family's five, its eight `timestampadd` rows and the three constructions), and
those rows pass the check under every configuration the matrix runs. The two BIGINT sums predate
the idiom. `try_add(l, l2)` is null exactly where the checked add's
guard fires, and the emitter's ANSI overflow test is that same signed-64-bit condition, so
`if(l IS NULL OR l2 IS NULL, NULL, try_add(l, l2) IS NOT NULL)` is the guard's own condition,
unknown exactly where an operand is null. Under it the outside partition is the two `MAX` rows
for `l + l2` and the one `MIN` row for `l - l2`, every batch of which holds an overflowing row at
any batch size, and the unknown partition is the fixture's one all-null row, which the all-null
shortcut answers.

**What the check would have rejected.** The bare `try_add(l, l2) IS NOT NULL` would drop the
unknown partition (`IS NOT NULL` is never null) and the check would report "no row where the
domain is unknown"; the `if(... IS NULL, NULL, ...)` wrapper is what keeps it. A domain that
answered a batch the guard condemns would be reported by the inside direction, which the
`try_` form cannot do since it is the guard's condition by definition.

**The other direction, and why it is not taken.** `VarkaDomains.check` could instead state that
it holds only the inside direction and why, letting an outside batch be answered. That weakens
the check for every guarded row to accommodate two rows whose guard SQL can state, and the
table's own convention is the opposite: `VarkaCoverageSuite`'s `ntzDomain` doc and the
narrow-width rows' exact ranges (`CAST(y1 AS INT) + CAST(y2 AS INT) BETWEEN -128 AND 127`) both
record the guard, not a sufficient condition. The matrix records no other row failing the
outside direction, so the two sums are the whole gap. Direction (a) of the row is admissible and
is the one built.

## 3. The design

### 3.1 The mechanism

`VarkaCoverageSuite.scala` only; no main source, compiler or emitter change.

`ntzDomain` becomes `tryDomain`, same body, with a doc that names both of its users:

```scala
  /**
   * The domain of a row whose kernel answers exactly where the row does not raise: unknown where
   * an operand is null, and otherwise whether Spark's `try_` form of the row has a value, which
   * it has exactly where the guard does not fire. For an NTZ row Spark's own arithmetic is the
   * bound, since no SQL spells an NTZ value as its count; the BIGINT sums take Spark's `try_add`
   * and `try_subtract` as they are. A row whose kernel also guards one operand alone puts that
   * guard first, `guard AND domain`: the guard is false on that operand's value whatever the
   * other operand holds, null included, and three-valued AND keeps it false there.
   */
  private def tryDomain(tried: String, operands: String*): String =
    s"if(${operands.map(_ + " IS NULL").mkString(" OR ")}, NULL, $tried IS NOT NULL)"
```

Its nine call sites take the new name (twelve NTZ rows, one of them through
`timestampAddDomain`). The two BIGINT sums become

```scala
      Row("l + l2", "checked under ANSI at 64 bits; the legacy mode wraps as Spark does",
        domain = Some(tryDomain("try_add(l, l2)", "l", "l2"))),
      Row("l - l2", "checked subtraction at 64 bits",
        domain = Some(tryDomain("try_subtract(l, l2)", "l", "l2"))),
```

and the family comment above them gains the reason: the sums name their guards' own conditions,
so a batch outside one holds an overflowing row however the rows are batched.

**The rejected direction, 14 November 2026.** Letting `VarkaDomains.check` hold only the inside
direction, and stating there why, was the row's other alternative. It was rejected because the
check would stop policing a domain that is too narrow for every guarded row, while the two rows
that need the relaxation can state their guard in SQL, as twelve `TIMESTAMP_NTZ` rows already do
(section 2).

### 3.2 What is deliberately unchanged

`VarkaDomains.check`: both directions stay, and with the two domains exact they hold for every
guarded row; its javadoc already states the equality. `VarkaDomains.edgesSql`: it already holds
`l` at the long's ends and one in from each, so no fixture row is owed. `VarkaDomains.TOTAL`:
neither sum's outside partition is empty. The emitter, the compiler and the IR: no main source
changes. The other guarded rows: their domains are their guards' conditions or bounds the
evaluator declines outside with `INPUT_BOUND`, which the check's outside direction accepts, and
the matrix records no failure for any of them.

### 3.3 Registered op counts

None. No emitter change: no `IntVector` invocation count, bytecode size or census cell moves.

## 4. Files

| file | what |
|---|---|
| `sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaCoverageSuite.scala` | `ntzDomain` becomes `tryDomain`; the two BIGINT sums name their guards' conditions |
| `sql/varka/matrix/skips.tsv` | the four `arrowBatchSize=1` and `=3` lines deleted |
| `sql/varka/coverage.json` | regenerated: the two `domain` fields |
| `docs/sql-varka.md` | regenerated: the two rows' domain column |
| `sql/varka/plans/m8/PLAN.md` | row 610 opens **Started**, done when it merges |
| `sql/varka/plans/m8/VARKA-610.md` | this plan and its Outcome |

## 5. Tests, and what each is for

| test | what it pins | oracle |
|---|---|---|
| `VarkaCoverageHarnessSuite`'s two dynamic tests, "Bigint arithmetic: l + l2 (l - l2) is answered inside its domain and where it is unknown, and declined outside with the verdict's status, in a projection and a filter" | outside is the 2 `MAX` rows (`l + l2`) and the 1 `MIN` row (`l - l2`); every outside batch is condemned at any batch size; the unknown partition is the all-null row's shortcut | `VarkaDomains.check`'s partitions and `VarkaGuardSpecs.verdict` |
| the same suite under the defaults, `arrowBatchSize=1` and `arrowBatchSize=3` | no other row moves, and the two move from fail to pass | the matrix report over `skips.tsv` |
| `VarkaHarnessParitySuite` | the rows still plan as the harness's checks plan them over the edges fixture | its own plan comparison |
| `VarkaCoverageSuite`'s "every row whose kernel can decline a batch names its domain, and no other row does" | the new domains resolve to a boolean over the table's columns and their rows still carry `INT_OVERFLOW` | `domainFindings` |
| `dev/varka_regen_generated.sh --all` | `coverage.json` and the docs blocks move in the two rows only | its own regeneration |

No pinned fixture besides `coverage.json` and `docs/sql-varka.md` is expected to move.

## 6. The measurement

No benchmark: the change records what the guard already is. The measurement is the option
matrix on the laptop over the one affected suite, both configurations, and the checks:

```
flock -w 1800 /home/max/varka-ops/locks/heavy12.lock \
  build/sbt catalyst/Test/compile sql/Test/compile
flock -w 1800 /home/max/varka-ops/locks/heavy12.lock dev/varka_matrix.sh --module sql \
  --suites VarkaCoverageHarnessSuite --config arrowBatchSize=1 --config arrowBatchSize=3 -j 1
flock -w 1800 /home/max/varka-ops/locks/heavy12.lock build/sbt \
  'sql/testOnly *VarkaCoverageHarnessSuite *VarkaHarnessParitySuite'
VARKA_HEAVY_LOCK=/home/max/varka-ops/locks/heavy12.lock dev/varka_regen_generated.sh --all
python3 dev/varka_plan_lint.py --check --offline
python3 dev/varka_quote_check.py
python3 dev/varka_census.py --check
flock -w 1800 /home/max/varka-ops/locks/heavy12.lock dev/lint-scala
python3 dev/varka_push_check.py
```

`dev/varka_regen_generated.sh` and `dev/varka_push_check.py` run bare: each takes the lock
itself. #1484's verification of these skips (its logs in `/home/max/varka-ops/logs/`) ran the
same suite one configuration at a time and read `defaults 2569` passes and
`arrowBatchSize=<n> 2567` passes with 2 canceled.

### 6.1 Predictions, registered before the run

1. Both configurations report 2567 passed and 2 canceled, and the defaults 2569 passed: the two
   moved tests pass, no other test moves, and the 2 canceled are the suite's own opt-ins.
2. `coverage.json` moves in exactly the two `domain` fields and `docs/sql-varka.md` in exactly
   the two rows of the Bigint arithmetic family; no other generated file moves.
3. `VarkaHarnessParitySuite` passes unchanged: it plans the rows' executables, not their
   domains.
4. `dev/varka_census.py --check` passes with no census cell moved: no guard, node or slot
   changed.
5. The counted size stays under 300 lines.

## 7. Risks

1. Spark's `try_add` and the emitter's checked add could disagree at a value the fixture holds,
   if one detected overflow and the other did not. Both are the signed-64-bit sum leaving the
   long, and the fixture holds the long's ends and one in from each; the inside direction would
   report a batch inside the domain that the guard condemns, and the outside direction the
   reverse.
2. The new domain could fail to resolve to a boolean or be unavailable as a function; the
   `VarkaCoverageSuite` domain test resolves and validates it before the harness ever runs.
3. The inside partition now holds the rows the old outside partition cut off, `MIN` beside
   `l + l2` and `MAX` beside `l - l2`; a kernel that answered them wrongly would turn a passing
   test red, which is the check doing its job, not a regression to paper over.
4. Two configurations beside the defaults cost two suite runs plus a build; the laptop's
   battery rule may delay a start, which is not a failure.

## 8. Sequencing

1. This plan, sections 1 to 8, with the admission check above, and row 610 opened **Started**
   in `PLAN.md`. No code.
2. One implementation commit: `ntzDomain` becomes `tryDomain`, the two sums name their guards'
   conditions, the four skip lines are deleted, `dev/varka_regen_generated.sh --all` regenerates
   `coverage.json` and the docs blocks, the checks of section 6 run, and section 9 is written.

## 9. Outcome

*To be written when the measurement lands, section by section as the plan's own rule asks.
Nothing above is to be rewritten to look prescient.*
