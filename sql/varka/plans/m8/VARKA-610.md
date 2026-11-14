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

**The fixture's rows and the partitions, at #1484's run.** `VarkaDomains.edgesSql` then set
`l2` to 1 on every row and overrode `l` on 13: the byte and short bounds and one past each (8
rows, all inside `+/-2^62`), `Long.MIN_VALUE`, `Long.MIN_VALUE + 1`, `Long.MAX_VALUE - 1` and
`Long.MAX_VALUE` (the bigint family's 4), and one `timestampadd` row whose quantity is
`Long.MAX_VALUE`. So the old domain's outside partition was 5 rows, `MIN, MIN+1, MAX-1, MAX`
and the `timestampadd` row's `MAX`, while only the two `MAX` rows overflow `l + l2` and only
the `MIN` row overflows `l - l2`. At `arrowBatchSize=3` the fixture's order puts
`[MIN, MIN+1, MAX-1]` in one batch, which `l + l2` answers, and `[MAX, MAX]` in another, which
`l - l2` answers. At `arrowBatchSize=1` every outside row is its own batch: for `l + l2` the
three rows that do not overflow answer and the two `MAX` rows decline, and for `l - l2` only
the `MIN` row declines while the other four answer. Under the default batch size the whole
outside partition is one batch, which holds an overflowing row, so the check passed on
batching luck.

**The fixture now speaks the domain's other operand.** The fixture adds `l2`'s null beside a
live `l` and an `l2`-driven overflow each way (section 3.2). With a constant `l2` of 1, a
domain written `if(l IS NULL, NULL, try_add(l, l2) IS NOT NULL)`, which drops the `l2 IS NULL`
term, passed every configuration; it now puts the live-`l`, null-`l2` row outside the domain,
where the kernel answers it, and the `l2`-driven overflow rows must decline on that operand's
value alone.

**SQL can state the guard, and the table already does it for the NTZ rows.** `ntzDomain`
(`VarkaCoverageSuite.scala:221-230`) renders `if(<operand> IS NULL [OR ...], NULL, try_<op>(...)
IS NOT NULL)`, and its doc states the identity: Spark's `try_` form of the row has a value
exactly where the row does not raise. Nine call sites use it, for sixteen `TIMESTAMP_NTZ` rows
(the arithmetic family's five, its eight `timestampadd` rows and the three constructions), and
under every configuration where the overflow check runs those rows pass the check.
`skips.tsv` records all 29 guarded-row domain tests failing under `checkIntOverflow=false`
(`:185-187` and `:202-204` are the six `TIMESTAMP_NTZ` `try_` rows), because that configuration
switches the overflow check off by design: the kernel wraps where the guard would fire, the
verdict never condemns, and every domain's outside partition is answered. Those failures say
nothing about a domain narrower than its guard. The two BIGINT sums predate the idiom.
`try_add(l, l2)` is null exactly where the checked add's
guard fires, and the emitter's ANSI overflow test is that same signed-64-bit condition, so
`if(l IS NULL OR l2 IS NULL, NULL, try_add(l, l2) IS NOT NULL)` is the guard's own condition,
unknown exactly where an operand is null. Under it the outside partition holds the two `MAX`
rows and the `l2`-driven overflow for `l + l2`, and the `MIN` row and its own `l2`-driven
overflow for `l - l2`, every batch of which holds a row the guard condemns at any batch size;
the unknown partition holds the all-null row, which the all-null shortcut answers, and the new
live-`l`, null-`l2` row, which the kernel answers with a null.

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
record the guard, not a sufficient condition. Where the overflow check runs, the matrix records
no other row failing the outside direction, so there the two sums are the whole gap; under
`checkIntOverflow=false` it records all 29 guarded rows failing it, for the reason above, and
that configuration cannot single out a narrow domain. Direction (a) of the row is admissible and
is the one built.

## 3. The design

### 3.1 The mechanism

`VarkaCoverageSuite.scala` and the edges fixture in `VarkaDomains.java`; no main source,
compiler or emitter change.

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

Its nine call sites take the new name (sixteen NTZ rows, one of them through
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
that need the relaxation can state their guard in SQL, as sixteen `TIMESTAMP_NTZ` rows already do
(section 2).

### 3.2 What is deliberately unchanged

`VarkaDomains.check`: both directions stay, and with the domains exact they hold for every
guarded row; its javadoc already states the equality. `VarkaDomains.edgesSql`: it holds `l` at
the long's ends and one in from each, and gains `l2`'s null and its own overflows beside a live
`l`, so the sums' `l2 IS NULL` term and an `l2`-driven overflow are exercised rather than
answered by the fixture's constant 1. `VarkaDomains.TOTAL`: neither sum's outside partition is
empty. The emitter, the compiler and the IR: no main source changes. The other guarded rows:
their domains are their guards' conditions or bounds the evaluator declines outside with
`INPUT_BOUND`, which the check's outside direction accepts, with one exception (section 3.3),
`CAST(dt AS BIGINT)`, whose domain stated a narrower sufficient condition; the matrix records
no failure for it only because no fixture row lay between the two bounds.

### 3.3 The day-time cast's domain, made exact too

`CAST(dt AS BIGINT)` divides the interval's microseconds by 10^6 under a `VALUE_RANGE` guard
that admits `|micros| <= 2^52 - 1`, 52124 days 23:53:47.370495. Its recorded domain was
`dt > INTERVAL '-50000' DAY AND dt < INTERVAL '50000' DAY`, a narrower sufficient condition,
the same defect as the sums' and invisible only because the fixture held no interval between
50000 days and the guard's bound. SQL can state the bound exactly in a `DAY TO SECOND` literal,
so the row's domain now reads

```
dt BETWEEN INTERVAL '-52124 23:53:47.370495' DAY TO SECOND
       AND INTERVAL '52124 23:53:47.370495' DAY TO SECOND
```

rendered from `VarkaVectorIR.ConstDivide.EXACT_DIVIDEND_BOUND` so a moved bound regenerates the
table, and `VarkaDomains.edgesSql` holds those two bounds and one microsecond past each side.
The `2^52` bound of the exact-division lowering is the guard's own.

### 3.4 Registered op counts

None. No emitter change: no `IntVector` invocation count, bytecode size or census cell moves.

## 4. Files

| file | what |
|---|---|
| `sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaCoverageSuite.scala` | `ntzDomain` becomes `tryDomain`; the two BIGINT sums name their guards' conditions, and `CAST(dt AS BIGINT)` names its exact microsecond bound |
| `sql/core/src/test/java/org/apache/spark/sql/execution/VarkaDomains.java` | `l2`'s null and own-value overflows for the sums, and the day-time cast's exact bound and one microsecond past each side |
| `sql/varka/matrix/skips.tsv` | the four `arrowBatchSize=1` and `=3` lines deleted |
| `sql/varka/coverage.json` | regenerated: the three `domain` fields |
| `docs/sql-varka.md` | regenerated: the three rows' domain column |
| `sql/varka/skills/testing-and-debugging.md` | the lesson on a domain being the guard's own condition |
| `sql/varka/plans/m8/PLAN.md` | row 610 opens **Started**, done when it merges |
| `sql/varka/plans/m8/VARKA-610.md` | this plan and its Outcome |

## 5. Tests, and what each is for

| test | what it pins | oracle |
|---|---|---|
| `VarkaCoverageHarnessSuite`'s two dynamic tests, "Bigint arithmetic: l + l2 (l - l2) is answered inside its domain and where it is unknown, and declined outside with the verdict's status, in a projection and a filter" | outside holds the `MAX` rows and an `l2`-driven overflow (`l + l2`), and the `MIN` row and its own (`l - l2`); every outside batch is condemned at any batch size; the unknown partition holds the all-null row's shortcut and the live-`l`, null-`l2` row | `VarkaDomains.check`'s partitions and `VarkaGuardSpecs.verdict` |
| `VarkaCoverageHarnessSuite`'s dynamic test for `CAST(dt AS BIGINT)` | inside the `+/- (2^52 - 1)`-microsecond bound the kernel answers; one microsecond past each side it declines; the unknown partition is the all-null row's shortcut | the same |
| the same suite under the defaults, `arrowBatchSize=1` and `arrowBatchSize=3` | no other row moves, and the two move from fail to pass | the matrix report over `skips.tsv` |
| `VarkaHarnessParitySuite` | the rows still plan as the harness's checks plan them over the grown edges fixture | its own plan comparison |
| `VarkaCoverageSuite`'s "every row whose kernel can decline a batch names its domain, and no other row does" | the new domains resolve to a boolean over the table's columns and their rows still carry `INT_OVERFLOW` | `domainFindings` |
| `dev/varka_regen_generated.sh --all` | `coverage.json` and the docs blocks move in the three rows only | its own regeneration |

No pinned fixture besides `coverage.json` and `docs/sql-varka.md` is expected to move.

## 6. The measurement

No benchmark: the change records what the guard already is. The measurement is the option
matrix on the laptop over the one affected suite, both configurations, and the checks:

```
VARKA_HEAVY_LOCK=/home/max/varka-ops/locks/heavy12.lock dev/varka_regen_generated.sh --all
flock -w 1800 /home/max/varka-ops/locks/heavy12.lock \
  build/sbt catalyst/Test/compile sql/Test/compile
flock -w 1800 /home/max/varka-ops/locks/heavy12.lock dev/varka_matrix.sh --module sql \
  --suites VarkaCoverageHarnessSuite --config arrowBatchSize=1 --config arrowBatchSize=3 -j 1
flock -w 1800 /home/max/varka-ops/locks/heavy12.lock build/sbt \
  'sql/testOnly *VarkaHarnessParitySuite'
python3 dev/varka_plan_lint.py --check --offline
python3 dev/varka_quote_check.py
python3 dev/varka_census.py --check
flock -w 1800 /home/max/varka-ops/locks/heavy12.lock dev/lint-scala
flock -w 1800 /home/max/varka-ops/locks/heavy12.lock dev/lint-java
python3 dev/varka_push_check.py
```

The regen runs first: `VarkaCoverageHarnessSuite` reads the regenerated `coverage.json` at class
initialisation, so a matrix before it tests the old domains. The regen and `dev/varka_push_check.py`
run bare with `VARKA_HEAVY_LOCK` set rather than under an outer `flock`: each takes that lock
itself, and an outer holder deadlocks it. The sbt run is the parity suite alone; the matrix
already runs `VarkaCoverageHarnessSuite` under the defaults and both configurations. #1484's
verification of these skips (its logs in `/home/max/varka-ops/logs/`) ran the same suite one
configuration at a time and read `defaults 2569` passes and `arrowBatchSize=<n> 2567` passes
with 2 canceled, the two canceled being the two skips.

### 6.1 Predictions, registered before the run

1. Each of the three configurations reports 2569 passed and none canceled: the two moved tests
   pass and no other test moves. The 2 canceled in #1484's record were exactly the two skipped
   tests - the suite has no test that cancels itself - so with the skips gone they cannot recur.
2. `coverage.json` moves in exactly the three `domain` fields and `docs/sql-varka.md` in exactly
   the three rows (two in Bigint arithmetic, one in Integral casts); no other generated file
   moves.
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
2. One implementation commit: `ntzDomain` becomes `tryDomain`, the two sums and `CAST(dt AS
   BIGINT)` name their guards' conditions, `VarkaDomains.edgesSql` gains the `l2` and day-time
   boundary rows, the four skip lines are deleted, `dev/varka_regen_generated.sh --all`
   regenerates `coverage.json` and the docs blocks, the checks of section 6 run, and section 9
   is written.

## 9. Outcome

Built 14 November 2026 on branch `varka-610`, from origin/master `9000fc5e911`.

**What was built.** The row's first direction. `ntzDomain` is `tryDomain`, with a doc that names
both of its users, and its nine call sites take the new name. The two BIGINT sums name their
guards' own conditions, `if(l IS NULL OR l2 IS NULL, NULL, try_add(l, l2) IS NOT NULL)` and the
`try_subtract` twin, so the domain is unknown exactly where an operand is null and true exactly
where the guard does not fire. The Bigint arithmetic family comment says why, and the four
`arrowBatchSize=1` and `=3` skip lines are deleted. `VarkaDomains.check` is unchanged: both of
its directions hold now that the two domains are exact.

**The review's finding, and the direction taken.** The review of 14 November 2026 found the
exactness claim incomplete: `CAST(dt AS BIGINT)` recorded
`dt > INTERVAL '-50000' DAY AND dt < INTERVAL '50000' DAY`, the same kind of sufficient
condition narrower than its guard, which admits `|micros| <= 2^52 - 1`. The guard's bound is an
exact microsecond count, and Spark parses a `DAY TO SECOND` literal with microseconds (the
fixture already held `INTERVAL '106751991 04:00:54.775807' DAY TO SECOND`), so SQL can state it
exactly and direction (a) of the row was taken for this row too, with no PLAN row owed. The
domain is now the exact interval bound, rendered from `VarkaVectorIR.ConstDivide.EXACT_DIVIDEND_BOUND`,
and the edges fixture holds the bound and one microsecond past each side. It also gains the
live-`l`, null-`l2` row, which a domain missing the `l2 IS NULL` term puts outside and so fails,
and an `l2`-driven overflow for each sum, so the guard's second operand is exercised.

Two failures on the way there are worth recording. The first run aborted at
`VarkaCoverageHarnessSuite`'s class initialisation: the fixture rendered the new null `l2` as
`CAST('NULL' AS BIGINT)`, which ANSI mode rejects, so `row()` now renders a bigint through a
helper that emits `CAST(NULL AS BIGINT)` for a null. The next run failed the day-time domain
test with 76 answered batches outside the domain, because the lower bound had been rendered
positive too, so `BETWEEN` admitted only the exact upper value; with the lower bound negated the
check passed.

**The measurement.** The option matrix over `VarkaCoverageHarnessSuite`, the defaults and both
configurations, run on the laptop under the heavy lock:

```
configuration                    exit  secs    ok fail canc abort unfused atdef
defaults                            0    63  2569    0    0     0       0     0
arrowBatchSize=1                    0    69  2569    0    0     0       0     0
arrowBatchSize=3                    0    66  2569    0    0     0       0     0
```

The two domain tests the skips named pass under both configurations, and every other test ran and
passed: nothing declined differently and nothing was canceled. #1484's record of the same suite
read 2567 passed with 2 canceled under each configuration; the 2 canceled were exactly the two
skips, so with them gone all 2569 run and pass and no test cancels. The regen ran first, as
section 6 now orders it: the suite reads `coverage.json` at class initialisation, so a matrix
before it would test the old domains.

**Checks.**

* `build/sbt catalyst/Test/compile sql/Test/compile`: success, 228 s.
* `dev/varka_regen_generated.sh --all`: exit 0. VarkaTypesSuite 11 s, VarkaFuzzTypesSuite 5 s,
  VarkaCastMatrixSuite 8 s, VarkaCoverageSuite 12 passed with 1 canceled (16 s),
  VarkaWidthAuditSuite 7 passed (658 s), VarkaEmittedBytesSuite 6 passed with 2 canceled (132 s),
  the second VarkaCoverageSuite 12 passed with 1 canceled (19 s), and the census passed.
  `git status` after it lists `sql/varka/coverage.json` and `docs/sql-varka.md`, each changed in
  exactly the three rows against `9000fc5e911` (two from the branch's first commit, the day-time
  cast from this one), and no other generated file.
* `build/sbt 'sql/testOnly *VarkaHarnessParitySuite'`: 8 passed, 0 failed, 99 s.
* `python3 dev/varka_plan_lint.py --check --offline`: 0 findings.
* `python3 dev/varka_quote_check.py`: self-test 6 of 6, 0 orphans.
* `python3 dev/varka_census.py --check`: passed.
* `dev/lint-scala`: Scalastyle and Scalafmt passed.
* `dev/lint-java`: checkstyle passed.
* `python3 dev/varka_push_check.py`: passed, exit 0, with 467 counted lines of 479 changed
  against `9000fc5e911`, under the 1000-line cap; it also ran the regenerated files' five
  catalyst suites, exit 0 with 35 passed and 3 canceled by their own opt-ins.

**Predictions, scored.** 1 held: registered as 2569 passed and none canceled under each
configuration, and all three printed exactly that. The 2 canceled in #1484's record were the two
skips, which are gone. 2 held: no generated file moved but `coverage.json` and
`docs/sql-varka.md`, and each in exactly the three rows. 3 held: `VarkaHarnessParitySuite`
passes over the grown fixture. 4 held: `dev/varka_census.py --check` passed. 5 was over: the
plan registered under 300 counted lines and `dev/varka_push_check.py` counts 467 of 479 changed
against `9000fc5e911`; the overage is this plan's nine sections and the row's record, and the
change stays well under the 1000-line cap.

**Left owed.** Nothing. Where the overflow check runs, the matrix recorded the two sums as the
only guarded rows failing the outside direction, and both now name their guards.
`CAST(dt AS BIGINT)` was the one other row whose domain was a sufficient condition; section 3.3
makes it exact and puts its boundary in the fixture. Under `checkIntOverflow=false` all 29
guarded-row domain tests fail because the check is off by design, which says nothing about a
row's domain, and the six `TIMESTAMP_NTZ` `try_` rows among them are no exception.
