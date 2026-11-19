# VARKA-545: A disagreement rerun on Spark with codegen off, and labelled

## 1. Where this came from

Milestone 8's row 545, added 1 November 2026 from `READING_SQLANCER.md` (section 2, point 2, and
proposed action 4 in its section 7). The Spark-level differential holds Varka to Spark's default
session, which runs whole-stage codegen. Spark has a second engine, the interpreted evaluator,
and the two do not always agree: row 386 exists because codegen's filter stopped at a NULL conjunct
where the interpreted filter raised, and row 541 because the ghost fallback's `Predicate.create`
evaluated a common subexpression earlier than codegen would (SPARK-60392 is the codegen-off half of
the same mechanism). A disagreement the fuzzer reports says nothing about which kind it is, so each
triage starts by finding out by hand. The reading's proposal is SQLancer's DQP oracle applied to
the reference: rerun the disagreeing case on Spark with whole-stage codegen and the codegen factory
off (`spark.sql.codegen.wholeStage`, `spark.sql.codegen.factoryMode`), and say which of Spark's two
answers Varka matches.

## 2. The admission check, done

Read on `origin/master` at `e5d70012bdc`, 20 November 2026.

- **Nothing reruns a disagreement on interpreted Spark.** `git log --grep=VARKA-545` finds no
  commit and no pull request has the id in its title. No file of the Spark-level differential
  (`VarkaSparkDifferential.scala`, `VarkaSparkFuzz.scala`, `VarkaSparkFuzzSuite.scala`,
  `VarkaSparkReproducerSuite.scala`) names `WHOLESTAGE_CODEGEN_ENABLED`, `CODEGEN_FACTORY_MODE` or
  the interpreted path. The reference is always `spark` under its defaults (`checkOnce`, which
  compares `outcome(spark, query)` with `outcomeAndResidual(varkaSpark, query)`).
- **The nearest thing is a different check.** Row 509's `VarkaExpressionHarness` compares Spark's
  interpreted and generated projection of one expression and reports where Spark's two disagree.
  It is below the session and labels nothing about Varka; the row's label is for the fuzzer's
  query-level disagreements. Nothing is reused from it except the idea.
- **The class still occurs.** Row 541 is Done (#1528), and row 386's reproducer
  (`sql/varka/fuzz/spark/varka-386-20261017-22897.sql`) became a `regression` once the upstream fix
  synced, but the upstream fix kept a legacy flag,
  `spark.sql.legacy.filterShortCircuitOnNullConjunct`
  (`SQLConf.scala:1821`, default false), whose doc says that when it is true codegen drops a row at
  the first NULL conjunct while "the interpreted evaluation" goes on to the later conjuncts. With
  that flag on the reference session only, the 386 reproducer is again a case where Spark's
  codegen and interpreter differ and Varka matches the interpreter, the test the done-when asks
  for. This was not run here (a sql/core test build is not a planning check); 6.1 registers it.
- **A planted Varka-only fault exists.** `VarkaSparkFuzzSuite.withPlantedRollback` sets
  `misdescribeRollback` 3, which changes the kernel's answers and nothing in Spark.

So the problem the row describes holds: a disagreement carries no label today.

## 3. The design

### 3.1 The rerun

One function in `VarkaSparkDifferential`, `labelOf(recheck: () => Option[String]): Label`, run
`withoutCounting` so the census does not move. `recheck` is the very check that found the case,
passed by the caller with everything it ran under, so the rerun checks the same kernel:

- for the random arm, `failureText` passes `kindOf(small, partitions, small.force.toSeq)` wrapped
  in `withEmitOptions(smallOptions)` (the shrunk case's pinned options, `checkIntOverflow` among
  them) under the run's `errorRerun` arm;
- for the warmed arm, the warmed check itself (`warmedDisagreement` under `withVarkaWarmup` and
  `withWarmupDeadline`), since its kinds appear only after the handover to the kernel and a
  rerun with the warm-up off would not show them;
- for the replay suite, `verdict`'s own `disagreement` call under the file's options and arm.

The two reruns:

- **A**, Varka against interpreted Spark: `recheck()` with `spark`'s conf set to
  `spark.sql.codegen.wholeStage=false` and `spark.sql.codegen.factoryMode=NO_CODEGEN` and restored
  after; `varkaSpark` keeps its settings. Every kind (values, raises, the pivot, a forced output,
  the partition check, the one-partition and per-row reruns of a raise) is decided by the same
  code. A kind from a check that reads `spark` alone (the row side's `partitionBroken`, a raise
  the list does not allow on the row side) says nothing about Varka: for those kinds A is not
  read, and the label comes from B alone (`spark-codegen` if B differs, `unrepeated` if not).
- **B**, Spark's two paths against each other, over the same views the failing check used:
  `checkOnce` records the queries it sends to `spark` (main query, the pivot's `alone`, the
  partition queries) and the view each ran over (whole, `coalesce(1)`, a row alone); B caches
  each view itself with `cacheFixture` and `uncacheFixture`, as `disagreement` does, and compares
  each recorded query's outcome under the defaults and under codegen off. B differs if any does.

| A (Varka = interpreted) | B (codegen = interpreted) | label |
|---|---|---|
| differs | agrees | `varka`: Varka's own bug |
| agrees | differs | `spark-codegen`: Spark's codegen against its interpreter, Varka as the latter |
| differs | differs | `varka, spark paths differ too` |
| agrees | agrees | `unrepeated`: the disagreement did not show again under the rerun |

The session-conf helper is `VarkaCodegenGiveUpSuite`'s private `withSessionConf`, hoisted into
`VarkaSharedSessions` beside `withBothConf`, which it generalizes, and the suite then uses it.

### 3.2 Where the label goes

- **The failure message**: `failureText` labels the shrunk case and prefixes the line, `[varka]`
  or `[spark-codegen]`, so a triage reads the class before the kind. The warmed arm's failure (not
  shrunk) gets the same prefix for its case.
- **The reproducer**: `Reproducer` gains `label: Option[String]`, rendered as `-- label: <label>`
  after `kind`, parsed when present, so every file already in `sql/varka/fuzz/spark/` parses as it
  is.
- **The replay**: `VarkaSparkReproducerSuite.verdict` recomputes the label of a `known` reproducer
  that still disagrees and fails if it moved from the stored one, the same rule that keeps the list
  from outliving its bugs; a stored file without a label is not checked.

The rerun costs nothing on a passing run: it runs only for a case already failing.

### 3.3 What is deliberately unchanged

- **Gating.** A `spark-codegen` disagreement still fails the run. Whether such a case may count
  against rows 386 and 541 instead of failing the nightly is the owner's (question 1), and the
  row's done-when asks only for the label.
- **The fixed differential suites** (`VarkaDifferentialSuite`, `VarkaCoverageDifferentialSuite`)
  and the IR fuzzer, which compares with `VarkaReference`, not Spark: question 2.
- Row 352's fuzzer edits from the same reading (actions 1 to 3) are that row's.

## 4. Files

| file | what |
|---|---|
| `VarkaSparkDifferential.scala` | `labelOf`, the `Label` values |
| `VarkaSharedSessions.scala`, `VarkaCodegenGiveUpSuite.scala` | `withSessionConf` hoisted |
| `VarkaSparkFuzz.scala` | `Reproducer.label`, `render`, `parse` |
| `VarkaSparkFuzzSuite.scala` | the label in `failureText` and the warmed arm; the tests |
| `VarkaSparkReproducerSuite.scala` | the label check on `known` files; a test |

## 5. Tests, and what each is for

- `VarkaSparkFuzzSuite`, "a planted Varka-only fault is labelled Varka's": the planted-rollback
  seed's first failing composition under `withPlantedRollback`, through `failureText`; the message
  starts `[varka]` and the reproducer has `-- label: varka`. Catches a rerun that never reaches
  `varkaSpark`'s fault, or a conf left on the wrong session.
- `VarkaSparkFuzzSuite`, "a case of row 386's shape is labelled Spark's codegen against its
  interpreter": the 386 reproducer's case with the legacy short-circuit flag on `spark` only; the
  label is `spark-codegen`. Catches codegen not really off (the factory mode alone, or whole-stage
  alone, leaves part of the plan compiled).
- `VarkaSparkFuzzSuite`, "a reproducer with a label parses back to what was rendered", beside the
  three existing round-trip tests, and an old file without the line parses with `None`.
- `VarkaSparkReproducerSuite`, "a known reproducer whose label moved fails": a constructed
  `known` reproducer with a wrong stored label.
- Both session confs are restored after `labelOf`, asserted in the planted test, since a leaked
  `NO_CODEGEN` on `spark` would make every later comparison of the suite interpreted.

No pinned fixture moves; the saved reproducers are unchanged.

## 6. The measurement

None of performance: the rerun runs only on a failing case. The new tests' time is the cost.

### 6.1 Predictions, registered before the run

1. The 386 reproducer, with `spark.sql.legacy.filterShortCircuitOnNullConjunct=true` on `spark`
   only, disagrees again (Spark returns the row, Varka raises) and is labelled `spark-codegen`. If
   the flag also moves the interpreted filter, the test is rebuilt on a hand-written case of 386's
   shape and this prediction is scored wrong.
2. The planted fault's case is labelled `varka`, with B agreeing.
3. The four new tests add under 30 s to `VarkaSparkFuzzSuite` and the replay suite together:
   about 15 s estimated (the planted test's shrink is most of it, as the existing planted test's
   is), at VARKA-243's observed ratio of 2 for sql/core test time.

## 7. Risks

1. **The cached fixture under codegen off.** B caches each view itself, as `disagreement` does,
   since `disagreement` uncaches in a `finally`; a scan of the cache under `NO_CODEGEN` must read
   it, not re-cache it. The planted test's B agreeing shows the read works.
2. **A label that is wrong when the interpreted path raises a different condition text** for the
   same failure: `outcome` compares error conditions, not messages, as the existing comparison does.
3. **A third Spark path.** Adaptive execution and the ghost fallback are not Spark's two engines;
   the label speaks only of codegen against interpreted, and its text says so.

## 8. Sequencing

One pull request, about 300 counted lines: the helper hoist, `labelOf`, the reproducer field and
the wiring, then the four tests. Done when `build/sbt 'sql/testOnly *VarkaSparkFuzzSuite
*VarkaSparkReproducerSuite'` passes with the four new tests among them.

## Questions for the owner

1. **May a `spark-codegen` disagreement pass the nightly, counted against rows 386 and 541?**
   *Recommended: no, not in this row*: label only, and decide gating once a nightly has printed some
   labels. Not mine: the reading (section 4) leaves "whether a known class may pass a nightly" to
   the owner, and it changes what the nightly guarantees.
2. **Label the fixed differential suites too?** *Recommended: no.* Their failures are fixed
   queries a developer reruns at once; the label pays off on random draws. `labelOf` can be called
   from them later. Not mine: it widens the row past its done-when ("the fuzzers print the label").

## Done when

A planted Varka-only fault is labelled as Varka's, a case of row 386's shape is labelled as Spark's
codegen against its interpreter, and the fuzzers (the random and the warmed arm of the Spark-level
fuzzer) print the label in the failure message and the reproducer.

## 9. Outcome

Not built yet.
