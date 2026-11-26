# VARKA-279: Declines as specified behaviour

## 1. Where this came from

Row 279 of `m7/PLAN.md` (described under "Declines as specified behaviour" in its section on the
test rows), from `m7/READING.md` 11's reading of Korat (a precondition for every behaviour),
Alive2 (refinement: decline wherever Spark raises, equal answers elsewhere) and TLP (a predicate,
its negation and IS NULL partition the rows). The wave table orders it after row 138, whose
coverage-row harness it extends.

A Varka kernel may refuse a batch - set a status bit, and the batch falls back to Spark's row
engine - and the guards that refuse are where a kernel's correctness has a boundary: inside the
range a guard admits the lowering is exact, outside it the batch must go to Spark. Today that
boundary is tested by hand, guard by guard:

* **The fuzzers stay away from it.** `VarkaReferenceEvaluator` has no notion of a declined batch:
  `GuardedDay` and `GuardedRange` pass their child through, `make_date`'s year limit is "the
  kernel's business, not the oracle's", and a failing overflow has no expected value
  (`VarkaReferenceEvaluator.scala`, around lines 51, 143, 169 and 242). `VarkaKernelCheck` takes a
  boolean `declineAllowed`, so a check can say "a decline is fine here", never "a decline must
  happen here, with this bit".
* **The coverage table says nothing about domains.** `sql/varka/coverage.json` has a free-text
  `note` per row ("a column offset, under a per-batch bound on its range"), and
  `VarkaCoverageDifferentialSuite` keeps its inputs inside the guards by hand.
* **The edges are pinned per test.** `VarkaEmitterChronoSuite`'s month-count test is the clearest
  case - the bound and one past it, on both sides, in a masked lane and an epilogue lane - but no
  table drives such tests, and no coverage row is ever run at its domain's edge.

So a guard whose bound is off by one, or a guard missing from a shape, is found only if someone
wrote that shape's edge test.

## 2. The admission check, done

Run on 11 October 2026 with a throwaway probe (`VarkaGuardProbeSuite`, not committed), which
compiles every coverage row under the default options and reads the guards its kernel carries:
the `Analysis` refusals the emitter places (`VarkaLoopEmitter.refusalsForTest`) and the per-batch
input bounds the compiler asks for (`inputBounds`).

* **The guard inventory is seven, as listed.** The six `Analysis.Refusal` reasons (day producer,
  month count, `make_date`, int overflow, day range, value range) and the input bound on a
  day-interval column (`DeclineSink.bound`, status `1 << 8`). Nothing else in the emitter or the
  compiler declines a batch.
* **Which rows carry which guard.** Of the 92 rows, 15 carry a guard: int overflow 8 (`i + 1`,
  `i - 1`, `-i`, `year(d) + i`, `abs(ym)`, `ym - ymm`, `extract(YEAR FROM ym) - 1`, and `d - ym`
  with month count as well), value range 3 (the two `time_from_*` round trips and `t + INTERVAL`),
  month count 3 (`add_months(d, i)`, `d + ym`, `d - ym`), `make_date` 1 (`make_date(2021, i, 1)`)
  and the input bound 1 (`d + CAST(i AS INTERVAL DAY)`). No row carries the day-producer guard,
  which the default options leave off, or the day-range guard, which the compiler inserts only
  where a day producer feeds a calendar node; those two are reached by composed shapes, so 3.2
  drives them on IR, not through the table.
* **The notes do not say.** Only 4 of the 15 guarded rows' notes mention their guard.
* **One open question.** `date_add(d, i)`'s note says "a column offset, under a per-batch bound
  on its range", yet the row's kernel carries neither a refusal nor an input bound. Either the
  note is stale or the bound is held elsewhere; the domain work of 3.3 answers it first, and if
  Spark can raise where the kernel answers, it becomes its own row with a reproducer.
* **Each guard's edge, from the tests that pin it.** Month count both sides of both bounds in a
  masked and an epilogue lane (`VarkaEmitterChronoSuite`, around line 1403); `make_date` one past
  each year limit (`VarkaChronoSuite`, around 284); int overflow under FAIL
  (`VarkaEmitterArithmeticSuite`, around 504); value range (`VarkaEmitterLongLaneSuite`, around
  278); day range on the composed day (`VarkaEmitterChronoSuite`, around 100); day producer one
  past each narrow bound (around 1281); the input bound one past its limit end to end
  (`VarkaDifferentialSuite`, around 901). They pass in the gate, so the edge table of 3.2 starts
  from bounds known right.

## 3. The design

### 3.1 A verdict per row

The verdict of a kernel on one row of input, decided from the inputs and the guard bounds before
the kernel runs:

| verdict | when | what the kernel must do |
|---|---|---|
| **answer** | every guard on the row's path admits its value | status 0 for a batch of such rows, and the reference's answer |
| **must decline** | a guard on the taken path rejects its value, or Spark raises (ANSI) | the guard's exact status bit (VARKA-304's exact refusal status) |
| **may decline** | a guard rejects a value on an arm the row does not take, and the guard is not qualified by its arm | either status 0 with the reference's answer on the taken arm, or that guard's bit |
| **outside the contract** | an input outside what the compiler admits (a date outside 0001-01-01 to 9999-12-31, the column contract) | nothing checked; such rows are never generated as a test of the kernel |

A batch's verdict is the strongest of its rows': one must-decline row makes the batch
must-decline. A null lane is never a reason to decline: a guard reads only valid lanes.

The reference evaluator learns the verdict rather than the kernel's answer: a function
`VarkaGuardSpec.verdict(roots, row)` beside it, walking the tree with the same values the
reference computes and applying each guard's bound where the IR carries the guard. Its bounds are
the constants the emitter and compiler read (`VarkaChrono`'s ranges, the month-arithmetic limits,
the `make_date` years, the value ranges), not copies, so the verdict cannot drift from the code by
someone editing one side.

### 3.2 Both sides of every bound

For each guard, inputs at its bound and one past it on both sides: `lo - 1`, `lo`, `hi`, `hi + 1`,
in a full vector lane and in the epilogue lane, valid and null, through both bodies. The kernel's
status is checked against the verdict, and where the verdict is answer, the rows against the
reference. This replaces nothing: the existing hand-written edge tests stay as the readable
examples, and this table is what makes every guard have them.

### 3.3 Each guarded coverage row names its domain

A coverage row that can fire a guard gets a `domain`, a SQL predicate over its columns
(`date_add(d, i)`: `i BETWEEN <lo> AND <hi>`), carried into `coverage.json` and the docs table
beside the note. `VarkaCoverageSuite` fails a row that fires a guard and has no domain, as it
fails an undocumented expression today. Then, as TLP partitions rows, each such row runs end to
end under its domain predicate, its negation and `IS NULL`:

* under the domain: fused, and the residual and fallback metrics say nothing declined;
* outside it: Spark's answer, whether Varka declined or Spark raised the same error;
* under `IS NULL`: fused, nothing declined.

That is the test-side counterpart of row 245, which proves guard completeness; this checks it on
the code as built.

### 3.4 What is deliberately unchanged

* The guards and their bounds. A bound found wrong is its own row with a reproducer.
* `VarkaKernelCheck.runAndCompare`'s signature: the boolean stays for its callers, and the verdict
  is a new entry point beside it.
* Row 138's three test forms, which this extends rather than repeats.

### 3.5 Registered op counts

None; no emitted class changes.

## 4. Files

| file | what |
|---|---|
| `sql/catalyst/src/test/java/.../codegen/varka/VarkaGuardSpec.java` | the verdict, from the guards' own constants |
| `sql/catalyst/src/test/scala/.../codegen/varka/VarkaKernelCheck.scala` | a check against a verdict, with the expected bit |
| `sql/catalyst/src/test/scala/.../codegen/varka/VarkaGuardEdgeSuite.scala` | 3.2: every guard, both sides of its bound |
| `sql/catalyst/src/test/scala/.../codegen/varka/VarkaCoverageSuite.scala` | the `domain` field, required for a guarded row |
| `sql/core/src/test/scala/.../execution/VarkaCoverageDifferentialSuite.scala` | 3.3's three partitions per guarded row |
| `sql/varka/coverage.json`, `docs/sql-varka.md` | the generated domain column |
| `sql/varka/plans/m7/PLAN.md`, `VARKA-279.md` | the row and this plan |

## 5. Tests, and what each is for

* **The edge suite** (3.2): a guard whose bound is off by one in either direction fails it, for
  every guard, not only those someone wrote a test for.
* **A planted wrong bound**: a test that moves one guard's bound by one through a test hook and
  requires the edge suite to fail, so the suite cannot pass vacuously.
* **The domain requirement**: a coverage row that fires a guard without a domain fails
  `VarkaCoverageSuite`; checked by a planted row.
* **The partitions** (3.3): a guard missing from a shape shows as a wrong answer outside the domain;
  a guard firing where it should not shows as a decline inside it.
* **The verdict itself**: a test that `VarkaGuardSpec` agrees with the existing hand-written edge
  tests on every case they pin.

## 6. The measurement

No benchmark. The suites' times are recorded in 9; the edge suite is small (a few inputs per
guard), the partitions are three runs per guarded row.

### 6.1 Predictions, registered before the run

1. **Every guard's edges hold.** The guards' bounds were derived and swept when they were written
   (`VarkaChronoSuite` pins the `make_date` limits and the era identity's edge).
2. **The domain requirement finds rows whose note does not say they are guarded**: at least one
   coverage row fires a guard its note does not mention, since the notes are written by hand.
3. **The may-decline verdict is rare in the coverage rows**: arm qualification covers most guards
   under a conditional; it shows in the composition fuzzer's trees, not the table's.

## 7. Risks

1. **A verdict that copies the bug.** If the verdict computed a guard's value the way the kernel
   does, a wrong lowering would agree with itself. The verdict computes values with the reference
   evaluator, which shares no lowering with the emitter; only the bound constants are shared, and
   those are what the edge suite tests.
2. **Rows outside the contract leaking in.** Edge inputs for one guard can push another input
   outside the contract; such rows are dropped by the verdict, and the suite counts them.
3. **Order with row 138.** This extends 138's harness; if 138 is not merged when this starts, 3.3
   waits for it and 3.1 and 3.2 go first.

## 8. Sequencing

1. This plan, with 2 done, and the row marked Planned.
2. The verdict and the edge suite (3.1, 3.2), with the planted wrong bound.
3. The domain column and the partitions (3.3), after row 138.
4. Section 9.

## 9. Outcome

### 9.1 The verdict and the edges (8.2), 12 October 2026

Steps 3.1 and 3.2 landed; 3.3, the coverage rows' domains and their three partitions, waits for
row 138 as 7.3 and 8 said, so the row stays Planned.

**What was built.** `VarkaGuardSpec` gives the verdict on a batch from its rows and the kernel's
own guards: `VarkaLoopEmitter.refusalsForTest` for which nodes refuse, and a new
`refusalArmsForTest` for the arm chain `Analysis.armChainOf` qualifies each by, so the verdict
applies the emitter's own rule rather than a reconstruction of it. A fired guard qualified by an
arm chain must decline where the chain selects the row and must not elsewhere; an unqualified one
must decline where the row reaches the node through arms it takes, and may where it reaches it
only through arms it does not. Its bounds are `VarkaChrono`'s constants, the lane's range and a
`GuardedRange`'s own `lo` and `hi`; its values the reference evaluator's.
`VarkaGuardEdgeSuite` holds sixteen small kernels, which carry the six guards between them, to it.

**The edges.** 248 batches: every guard at `lo - 1`, `lo`, `hi` and `hi + 1` (for `make_date`'s
date, either side of February's last day in a leap year and not, and month 12 against 13), at row 0
of 17 (a full vector) and row 16 (the epilogue), the edge row valid and, in a second batch, null in
the column that carries it, each through both bodies. 116 declined; every status agreed with the
verdict for its body under `exactRefusalStatus`, and every answered batch matched the reference
evaluator row for row. Moving each end of each guard's bound by one in either direction on the
verdict's side turned some edge against it, all 24 shifts. Under an arm no row takes, the guard
qualified by it stayed silent, and with `guardUnderArm` off it declined, which the verdict allows.
The input bound admitted `-INTERVAL_DAY_LIMIT_DAYS` to `INTERVAL_DAY_LIMIT_DAYS` and refused one
past, never on a null, and its own shifted bound was caught. The verdict agreed with the sixteen
cases the hand-written edge tests pin. The suite runs in about half a second, and the gate passed
(lint on a rerun, after one scalastyle fix in the verdict).

**Prediction scored.** 1, **held**: every guard's edges hold, at both lanes. Predictions 2 and 3
are about the coverage rows and wait for 3.3, as does 2's open question: `date_add(d, i)`'s note
still promises a bound its kernel does not carry.

**Corrections to the plan.**

* **The planted wrong bound moves the verdict's bound, not the emitter's.** The emitter reads its
  bounds as `static final` constants, which javac inlines into its bytecode, so a test hook there
  would be a change to the emitter the plan did not price. Moving the verdict's bound proves the
  same thing: the edge inputs land exactly on each bound and one past it, so an emitter off by one
  would disagree with the verdict on the same rows.
* **`VarkaGuardSpec` is Scala**, beside the reference evaluator it drives, as `VarkaKernelCheck`
  is; 4's table had it Java.
* **`VarkaKernelCheck` needed no verdict entry point.** VARKA-278 added `runAndCompareStatus`,
  which returns the status; the suite holds it to the verdict itself.
* **The fourth verdict, outside the contract, is not computed.** The IR does not say which columns
  are dates, so the edge suite keeps every input inside the contract by construction instead.
* **The input bound is tested at its check.** It has no IR node: the evaluator declines the batch
  through `IntRangeOps.allWithin` before the kernel runs, and the suite drives that.
* **2 was wrong that the defaults leave the producer guard off.** `guardDayProducers` is on by
  default; the coverage rows carry none because no row puts a column-offset producer under a
  calendar node. The suite turns both optional guards on over whatever the option matrix sets, so
  a configuration without them still tests the guard.
* **A guard's count is by guard, not by kernel**: `make_date`'s NULL form answers an invalid day
  with a null and never declines.

**Review** (`/code-review` on #735). Seven of eight findings fixed:

* **No valid edge past a bound met the masked body.** A valid edge ran the dense body, and when that
  declined the check stopped, so the masked body saw past a bound only a null edge. Each valid edge
  now runs a third way, with the masked body forced.
* **A status did not say which body returned it**, so a decline the first body must not make
  passed whenever the second body's verdict required that bit - the rule that a null is never a
  reason to decline went unchecked for the checked sums. `VarkaKernelCheck.runEachBodyStatus` runs
  both bodies whatever the first returned and returns each status; each body is held to its own
  verdict. With the third way, 372 batches, 174 declining in some body, all agreeing.
* **No kernel took an arm-qualified guard's arm.** The arm test now runs the kernel on the taken
  arm, which must decline qualified or not, and holds the kernel to what it does on the untaken
  one: silent when qualified, declining when not - which 9.1 reported but nothing asserted.
* **The input bound's planted shift moved both ends together**; each end now moves on its own.
* **One analysis per kernel**: `refusalArmsForTest` becomes `guardsForTest`, returning each guard's
  reason with its arm chain, read once per kernel (`VarkaGuardSpec.Guards`).
* **The input bound's harness** is `IntRangeOpsSuite`'s, moved to its companion object, not a copy.
* **9.1's reason the emitter's bounds cannot move was half wrong.** They are `static final` fields;
  the narrow range and the month limits are constants javac inlines, but the `make_date` years and
  the interval limit are computed when `VarkaChrono` loads. Either way a test cannot move them
  without changing the emitter, so the verdict-side shift stands.

Not taken: `VarkaGuardSpec` in Java. It drives the Scala reference evaluator, for which
`VarkaKernelCheck` stays Scala; 9.1 records the deviation.

### 9.2 The domains and their partitions (8.3), 14 October 2026

Step 3.3 landed, after row 138 closed on 13 October with the partition oracle and the extremes
fixture it builds on. The row is Done: 1.1's "every decline the one the row's verdict calls for"
holds at the guards' edges (9.1) and end to end on every guarded coverage row (here).

**The recount.** `VarkaCoverageSuite` now compiles every row and reads the guards its kernels
carry - `VarkaLoopEmitter.guardsForTest` on each kernel of `compilePartial` (or a predicate's
`fused`) under the defaults, and `CompiledVarkaProjection.inputBounds` for the evaluator's own
bound - and the count is the admission check's, guard for guard: 15 of 92. Int overflow 8 (`i + 1`,
`i - 1`, `-i`, `year(d) + i`, `abs(ym)`, `ym - ymm`, `extract(YEAR FROM ym) - 1`, and `d - ym`
with the month count as well; so counted in both groups, these are 16 guards on 15 rows), month
count 3 (`add_months(d, i)`, `d + ym`, `d - ym`), value range 3 (the two `time_from_*` round trips and `t + INTERVAL`), `make_date` 1 and the input bound 1
(`d + CAST(i AS INTERVAL DAY)`). No predicate row carries one.

**What was built.**

* **The domain column.** `Row` in `VarkaCoverageSuite` takes a `domain`, a SQL predicate over the
  row's columns, rendered from the constants the guards read - `VarkaChrono`'s interval-day limit
  and month range, the int's ends, `DateTimeConstants.NANOS_PER_DAY` for the time guards - so a
  moved bound regenerates the table rather than leaving it stale. It is carried into
  `coverage.json` (`"domain"`, beside the note) and into `docs/sql-varka.md` as a *Domain* column,
  with a paragraph above the table saying what the column means. The fifteen:
  `i BETWEEN -106751991 AND 106751991`; `i BETWEEN 1 AND 12`; `i BETWEEN -24576 AND 24564`;
  `ym BETWEEN INTERVAL '-2048-0' YEAR TO MONTH AND INTERVAL '2047-0' YEAR TO MONTH` and, for
  `d - ym`, the bounds changing sides; `ym > INTERVAL '-178956970-8' YEAR TO MONTH`;
  `CAST(ym AS BIGINT) - CAST(ymm AS BIGINT) BETWEEN -2147483648 AND 2147483647`;
  `extract(YEAR FROM ym) > -2147483648`; `i < 2147483647`; `i > -2147483648` twice;
  `i <= 2147483647 - year(d)`; `time_to_millis(t2) BETWEEN 0 AND 86399999`;
  `time_to_micros(t) BETWEEN 0 AND 86399999999`; and `time_to_micros(t) + 0` under the same bound.
* **The requirement**, both ways: a row whose kernel carries a guard and names no domain fails,
  as does a row that names one and carries no guard (a stale domain), and a domain that does not
  resolve to a boolean over the table's columns. A planted family - `i + 1` with no domain,
  `year(d)` with `true`, `i - 1` with `i` - draws exactly those three findings. The guards are
  read under `VarkaEmitOptions.DEFAULTS`, not the matrix's base: the table documents the engine
  as shipped, and a configuration that turns a check off does not take a row's domain away.
* **The partitions**, in `VarkaCoverageDifferentialSuite`. A fifth fixture,
  `varka_coverage_edges`, holds 39 rows: every guard's bound and one past it on both sides in the
  column the guard reads (`i` at the int's ends and one in, the interval-day limit, the month range
  and the months of `make_date`; `d` of year 9999 beside the `i` that takes `year(d) + i` to the
  int's top and one past; `ym` at the month range on both signs, at the count whose negation
  overflows and one in, and with `ymm` at the counts whose difference leaves the int; a time at
  each end of the day) and rows null in those columns, one group at a time and all at once. For
  each guarded row its three partitions - the domain, `NOT (domain)`, `(domain) IS NULL` - are
  cached views of their own, built by Spark once, so a kernel then reads batches wholly inside,
  wholly outside or wholly unknown; the three are checked to cut the fixture by `rid`. Each runs
  as a projection (`SELECT rid, e`) and as a filter (`WHERE (e) = <a column of e's type>`, 138's
  fourth position), on both engines, under ANSI and with exact refusal statuses on. Per query:
  the outcomes agree, rows or the same condition, SQLSTATE and parameters; the declined batches'
  statuses, read off the JFR fallback events, equal the verdict's must bits batch for batch, where
  the verdict is `VarkaGuardSpec`'s over the node's own expressions compiled against the scan's
  pruned output, the view's rows as the kernel reads them, and the evaluator's input bound
  applied first as the runner applies it; and where every batch must be answered, the kernels
  served the whole query with nothing declined.
* **The total domains.** Four guards no value of the column type can trip, so their outside
  partition is empty by construction: the three value-range guards, whose bound is the day the
  `TIME` already lies in, and the checked subtract after `extract(YEAR FROM ym)`, whose quotient is
  within +/-178956970. Their domains are written all the same, and a list with reasons polices the
  emptiness both ways.
* **The open question of 2, answered.** `date_add(d, i)`'s note promised "a per-batch bound on
  its range"; its kernel carries no refusal and no input bound, the compiler records a bound only
  for the day-interval cast, and Spark's `DateAdd` is a plain int add. The note was stale, not
  the kernel: over the edges fixture, `i` at both int extremes and `d` of year 9999 among its
  rows, the kernels serve every batch, nothing falls back, and the wrapped day equals the row
  engine's. The note now says so, and a test holds the row to it.

**What ran.** 15 guarded rows, 3 partitions, 2 forms: 90 queries on each engine, of which 82 ran
(the four total domains' outside partitions are empty and run nothing); the 22 outside a reachable
domain declined every batch - `input-bound` for the day cast, `make-date`, `month-count` for
`add_months(d, i)` and `d + ym`, `month-count|int-overflow` for `d - ym` at the count whose
negation overflows and whose wrapped value the month count then rejects, `int-overflow` for the
other six - and the 60 inside or unknown answered with every batch served; no batch's verdict left
a guard free to fire except the one below. The suite is 232 tests, 215 before. The catalyst
suites under the matrix's defaults with the memory sanitizer on, `VarkaCoverageSuite` (9) and
`VarkaGuardEdgeSuite` (6): 15 passed, the opt-in fusion dump cancelled, in 13 s. `VarkaCoverageDifferentialSuite` under
the defaults and the four configurations that could change these tests, sanitizer on, two JVMs at
a time (`dev/varka_matrix.sh --module sql --suites VarkaCoverageDifferentialSuite --config ...
-j 2`): the defaults, `guardUnderArm=false`, `arrowBatchSize=1` and `arrowBatchSize=3` 232 passed
each; `checkIntOverflow=false` 223 passed and 9 cancelled, the six new skip lines beside 138b's
three - the configuration turns the overflow check off, so outside the int rows' domains the
kernel answers the wrapped value where Spark raises, as 138b's ANSI form found. The first run of
the batch-size configurations is what found the two things above: the raise that cuts the events
to a prefix, and the wrapped lane under `d - ym`.

**Times**, this laptop's, beside other agents' test JVMs, load 4.9 to 6.1. The suite on one build,
master's file and then this one, by ScalaTest's "Run completed": 1 min 35 s for 215 tests before,
1 min 45 s for 232 after, 22:00 CEST. In the matrix's JUnit report the 17 new tests take 8.3 to
9.0 s together (median 0.5 s, the longest 1.1 s), and caching the edges fixture and its 45
partition views 7.6 s on the first session and 3.8 s on the second, inside `beforeAll`. The gate's
sql step gains about ten seconds in one of its JVMs; no nightly step runs this suite. The plan
registered no cost prediction for 3.3, so the calibration table gains no line.

The verdict's other reader, `VarkaCompiledCheck`'s guard edges (VARKA-274), reads `must` and
`admits`; the change leaves `must` as it was and only widens `admits`.

**A finding on the verdict, from the small batches.** Under the matrix's Arrow batch sizes (1, 3
and 17 rows a batch) the partitions split into several batches and the verdict is held per batch,
which the defaults' one batch per partition cannot do. That showed `d - ym` at
`INTERVAL '-178956970-8' YEAR TO MONTH`, the one count whose negation overflows: the kernel's
status named `month-count|int-overflow`, the verdict `int-overflow` alone. The negation condemns
the batch and leaves the wrapped `Int.MinValue` in a lane that stays valid; the month count reads
it and fires too. The reference evaluator has no value for an overflowed FAIL lane, so the
verdict's month-count check saw a null and stayed silent - and under the defaults the OR over the
batch's other rows, which fire the month count on their own, had hidden it. The rule 9.1 wrote was
incomplete, not the kernel: `VarkaGuardSpecs` now allows (as `may`) a guard above a FAIL node that
overflowed on the row, qualified by its arm as before, and `VarkaGuardEdgeSuite` pins the composed
kernel at the overflowing count (both bits, the verdict allowing the second), one in from it (the
count alone, a must) and an ordinary count (answered). The edge suite had no kernel composing a
FAIL node under a guard; the coverage rows have one.

**Predictions scored.** 2, **held**: of the fifteen guarded rows' notes as they stood, only the two
time rows' said the kernel is guarded (the admission check counted four, reading "a column
offset" and "further int arithmetic" as saying so); the other thirteen said nothing, and
`date_add(d, i)`, which said so, is not guarded. 3, **held, for a reason the plan did not give**:
the may-decline verdict is rare in the coverage rows - one row, `d - ym`, at one value - but not
because arm qualification covers the rest; no coverage row puts a guard under an arm at all, and
the one may bit comes from the wrapped lane above, a source the plan's table of verdicts did not
have. The arm case remains the composition fuzzer's.

**Corrections to the plan.**

* **The partitions are cached views, not `WHERE` clauses.** A verdict is per batch, so a batch has
  to lie wholly on one side; a `WHERE` over the same scan computes the guarded expression on the
  rejected lanes too (`m10/SCOPE.md` item 96), and a domain Varka cannot fuse - `CAST(ym AS
  BIGINT)` arithmetic, `2147483647 - year(d)` - would leave the whole projection to Spark. A
  partition Spark materializes once is what TLP cuts anyway.
* **The status comes off the JFR fallback event, not the metrics.** Outside most domains the row
  path raises under ANSI, the task fails, and a failed task's SQL metrics are never merged;
  the evaluator commits the event with the status first. The metrics are read where the outcome is
  rows (`kernelServed`, `numFallbackBatchesDeclined`, and the served and declined counts where
  only some batches must decline). Exact refusal statuses are set on the sessions for these tests,
  so a status is what its batch's verdict admits, batch by batch in the order they ran; and
  where the row path raised, the task stopped in the batch whose status is the last, so the
  statuses are what the batches up to it produced - a prefix the check accepts only after a raise.
* **`partitionBroken` was not reused**: `VarkaSparkFuzz.querySql` binds it to the fuzzer's table
  `fz`. The suite checks the cut with `rid` sets, as its own partition oracle does.
* **A guard may be dead.** 3.3 assumed every guard has an outside; four do not. The analysis that
  bounds the operands (row 281's business) could prove those guards away; recorded below.
* **4's table** had `VarkaKernelCheck` taking a verdict entry point and `VarkaGuardSpec` in Java;
  neither, as 9.1 said. `ADDING_AN_EXPRESSION.md` step 7 and `skills/testing-and-debugging.md`
  gained the domain, which 4 did not list.

**Candidate rows found.** Four guards no input can trip: the range guards of
`time_from_millis(time_to_millis(t2))`, `time_from_micros(time_to_micros(t))` and
`t + INTERVAL '0 00:00:00' DAY TO SECOND` (a count the type keeps inside the day), and the checked
subtract after `extract(YEAR FROM ym)` (a quotient within +/-178956970). Each costs a compare and
a mask per lane group for a decline that cannot happen; a range analysis that knew a `TIME`'s
range and a `ConstDivide`'s quotient would take them off. For the scope catalogue, or for row 281
if its analyses reach operand ranges; not widened here.

**Review** (`/code-review high` on the branch, before the pull request). Of eight findings, five
fixed: the check never held the domain itself to the partitions - a domain too wide or too narrow
agreed with the kernel because the verdict does not read the domain - so a batch inside the domain
or under `IS NULL` that the verdict condemns, and a batch outside that it answers, are findings now
(all 232 tests still pass under the defaults and the four configurations); a stale comment ("seven
guards") and the recount that did not sum (`d - ym` is in two groups: 16 guards on 15 rows); the
skills file's claim of seventeen-row batches, which was never run; and the domain's resolution now
checks input types as well. Recorded, not changed: after a raise the check stops at the last
status, so a raising batch that committed no event is not told apart from an earlier one that
raised; a wrapped lane gives `may`, never `must`; and the `may` count is reported, not asserted.
