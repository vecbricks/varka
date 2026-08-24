# Varka Task 8 - Config-driven activation + comprehensive docs

**Status: DONE**. See `PLAN_MILESTONE_1.md` for the high-level MVP plan.
Task 8 makes the Varka integration activation work **out of the box** (a
`SparkSession` needs only `spark.sql.codegen.varka.enabled=true` - no manual
extension injection), adds flag-toggling coverage proving the rule is inert
when disabled, and documents the whole project comprehensively.

One deliberate scope change from the original task table: **no unused
configuration.** The earlier task row named `patch.threshold` and
`fallback.ghost.enabled`; those gate class-file funnel machinery that is not
yet wired (see `PLAN_TASK_5/6`), so they are NOT added to `SQLConf`. Adding
config entries with no consumer would be misleading. Only
`spark.sql.codegen.varka.enabled` exists and is consumed.

## 1. Goal

- Register `VarkaColumnarRule` on every `SparkSession` (the rule self-gates on
  `SQLConf`), so enabling Varka is purely config-driven - no
  `SparkSessionExtensions`/`injectColumnar` needed by users.
- Prove with tests that the auto-registered rule fuses an eligible projection
  and runs the kernels when enabled, and is completely inert when off.
- Ship a single comprehensive `docs/sql-varka.md` describing the project, its
  architecture, decisions and configuration.

## 2. Decisions (recorded here)

- **Auto-registration is a core default.** `VarkaColumnarRule` is injected in
  `SparkSession.Builder.getOrCreate` (`SparkSession.scala`, after
  `applyExtensions`). Because `VarkaColumnarRule.postColumnarTransitions`
  returns the plan unchanged while `spark.sql.codegen.varka.enabled` is off,
  registering it on every session is harmless. No
  `META-INF/services/SparkSessionExtensionsProvider` / classpath-load static
  conf is used; it works out of the box.
- **No new SQLConf entries.** Only the existing `VARKA_ENABLED`
  (`internal`, v5.0.0, default `false`) is documented. `patch.threshold` and
  `fallback.ghost.enabled` stay design intentions (see `VISION.md`), to be
  introduced as configs only when their code paths exist.
- **Docs = one page.** `docs/sql-varka.md` (house style of
  `docs/sql-arrow-cache-format.md`) is the single comprehensive reference; no
  `docs/configuration.md` edits.

## 3. Deliverables

### 3.1 Auto-registration (`sql/core` main)

- `SparkSession.Builder.getOrCreate` injects `VarkaColumnarRule` (already
  visible via `import org.apache.spark.sql.execution._`). Covered by a comment
  noting the rule is inert unless enabled.

### 3.2 `VarkaAutoRegistrationSuite` (`sql/core` test)

- Base `SharedSparkSession` (Arrow cache serializer + vectorized reader), no
  custom sessions, no `injectColumnar`.
- `spark.sql.codegen.varka.enabled` defaults to `false`.
- enabled=true: `SELECT date_add(d, 3) FROM varka_dates` fuses into
  `VarkaColumnarToRowExec`, `checkAnswer` matches the row engine (null-first
  ordering), and `numVarkaBatches > 0`.
- enabled=false: the plan is untouched and the row engine still returns all
  cached rows.

### 3.3 Docs (`docs/sql-varka.md`)

Comprehensive single page: overview + MVP scope; architecture (morsels, SIMD
kernels, per-task class loader, catalyst hooks + `java.lang.classfile`, the
live columnar-to-row node and the honest status of the codegen-funnel stub);
key design decisions (Java 25 + standalone engine, Arrow-only fast path,
plan-level interception, ghost fallback/caching, extreme-offset oracle, no
unused config); module/file map; the single real config + prerequisites;
testing + benchmark results; deployment; limitations; build/test/benchmark
commands.

### 3.4 Plan/Done bookkeeping

- `PLAN_TASK_8.md` (this file).
- `PLAN_MILESTONE_1.md`: task 8 row reworded to the actual scope and marked
  DONE; the "Explicitly deferred (Task 6)" note on the full flag set and
  config-driven auto-registration resolved; the `VARKA_ENABLED` config row/
  docs references aligned (no `patch.threshold` / `fallback.ghost.enabled`).

## 4. File layout

```
sql/core/src/main/scala/org/apache/spark/sql/classic/SparkSession.scala
                          (+ injectColumnar(VarkaColumnarRule) in Builder)
sql/core/src/test/scala/org/apache/spark/sql/execution/
  VarkaAutoRegistrationSuite.scala        (new; config-driven activation)
docs/sql-varka.md                         (new; comprehensive project docs)
sql/varka/PLAN_TASK_8.md                  (this file)
sql/varka/PLAN_MILESTONE_1.md          (task 8 row -> DONE)
```

## 5. Verification

- `build/sbt "sql/testOnly *VarkaAutoRegistrationSuite"`
- Regression: `sql/testOnly *VarkaColumnarToRowExecSuite`, `*VarkaEndToEndSuite`,
  `*VarkaDifferentialSuite` (base sessions leave `varka.enabled` unset, so the
  newly-default rule changes nothing for them).
- Engine tests (unchanged), catalyst loader suite (unchanged).
- `build/sbt "catalyst/scalastyle" "sql/scalastyle"`; ASCII and `<=100`-char
  scan on changed files; docs front-matter check.

## 6. Definition of done (Task 8)

- `VarkaColumnarRule` is auto-registered and verifiably inert when
  `spark.sql.codegen.varka.enabled=false`.
- A session with the config on fuses eligible projections and reports
  `numVarkaBatches > 0` - no manual extension injection.
- No unused `spark.sql.codegen.varka.*` configs: only `varka.enabled`.
- `docs/sql-varka.md` is a comprehensive, accurate project reference.
- `PLAN_MILESTONE_1.md` task 8 marked DONE; style clean.