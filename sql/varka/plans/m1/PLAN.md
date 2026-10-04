# Varka Milestone 1 (MVP) Implementation Plan

This is the implementation plan for the Varka MVP (Date Arithmetic Over
ArrowColumnarBatch), the project's first milestone. It supersedes the sketch in
`Varka_MVP.md` where the two disagree; `VISION.md` remains the architectural
source of truth. All eight tasks are complete; the work that follows them is
planned in `m2/PLAN.md`.

Per-task detail lives in separate files:

- `VARKA-1.md` - standalone engine module + `VarkaMorsel` (completed).
- `VARKA-2.md` - `DateVectorOps` SIMD kernels (in progress).
- `VARKA-3.md` - `VarkaClassLoader` + per-task lifecycle (completed).
- `VARKA-4.md` - Catalyst hooks (`ClassFileCodegenSupport`) (completed).
- `VARKA-5.md` - Class assembly + Ghost fallback (`JavaClassFileEngine`)
  (completed).
- `VARKA-6.md` - Execution-path integration (`VarkaColumnarToRowExec`)
  (completed).
- `VARKA-7.md` - Differential + perf testing (completed).
- `VARKA-8.md` - Config-driven activation + comprehensive docs
  (completed).

## 1. Corrections to the design docs (ground truth in this repo)

This plan is grounded in the actual `vecbricks-varka` tree (Spark
5.0.0-SNAPSHOT, branch `master`, JDK 25 installed). The two design docs
contain details that do not match this codebase; they are corrected here:

| Doc says | Reality | Action |
| :--- | :--- | :--- |
| `ArrowColumnarBatch` / `ArrowVector` | `ColumnarBatch` / `ArrowColumnVector` (Java, `sql/catalyst/src/main/java/org/apache/spark/sql/vectorized/`) | Use `ArrowColumnVector.getValueVector()` (ArrowColumnVector.java:47) to reach the Arrow `ValueVector`. |
| Spark `DateType` -> Arrow type | `DateDayVector` (DATE32, int32 days since epoch) | `VarkaMorsel` targets `DateDayVector`. |
| `VectorMask.fromMemorySegment(SPECIES, validity, i, JAVA_BYTE)` | Arrow validity buffers are **bit-packed (1 bit/row)**; a byte-per-lane read is a correctness bug | Load a `long` from the validity segment and build the mask with `VectorMask.fromLong(SPECIES, bits)` (verified present in JDK 25). |
| "64-byte alignment guaranteed by Arrow's allocator" | Not a guarantee | Treat alignment as a **diagnostic** in tests, never an assertion. Vector-API masked loads/stores work unaligned. |
| `CodeGenerator.doCompile` / `currentContext` | `doCompile` was lifted into the pluggable `CodeCompiler` backend (CodeCompiler.scala:63); no `currentContext` ThreadLocal exists | The Ghost-fallback hook lives in `CodeCompiler`/`CodeGenerator.compile` (CodeGenerator.scala:1567). |
| Repo builds at Java 25 | Repo builds at **Java 17** (`--release 17`, enforcer pins bytecode version) | The engine is a **standalone module outside the Spark reactor**, built on JDK 25. **Decision (review of VARKA-4): bump the repo baseline to Java 25** (`pom.xml` `java.version=25`) so catalyst can use `java.lang.classfile`; the engine stays standalone by design (native-access test flags). |
| `spark.sql.varka.enabled` (testing section) vs `spark.sql.codegen.varka.enabled` | Inconsistent | Standardize on `spark.sql.codegen.varka.*`. |
| `arrow-vector` version | `19.0.0` (pom.xml:240) | Engine depends on `org.apache.arrow:arrow-vector:19.0.0`. |

## 2. MVP architecture overview

```
sql/varka/
  VISION.md                      (unchanged)
  Varka_MVP.md                   (unchanged)
  plans/
    m1/PLAN.md          <- this file (high-level)
    m2/PLAN.md          <- milestone 2 (task plan)
    m3/PLAN.md          <- milestone 3 (task plan; scope catalogue in its section 9)
    VARKA-1.md               <- VARKA-1 detail (completed)
    VARKA-2.md               <- VARKA-2 detail
    VARKA-3.md               <- VARKA-3 detail (completed)
    VARKA-4.md               <- VARKA-4 detail (implemented, PR #5)
  engine/                        <- Java 25 module (VARKA-1 to VARKA-3). A Spark reactor module, but
                                    with its own pom rather than spark-parent (by design:
                                    needs incubator-vector and native-access test flags).
    pom.xml                      (--release 25, --add-modules jdk.incubator.vector)
    src/main/java/org/apache/spark/sql/varka/
      memory/VarkaMorsel.java    (VARKA-1)
      vector/DateVectorOps.java  (VARKA-2)
      execution/VarkaClassLoader.java  (VARKA-3)
    src/test/java/...            (VARKA-1 to VARKA-3 unit tests)
  catalyst/                      <- VARKA-4 additions are additive source in the existing
                                    sql/catalyst module; Class-File assembly lives here on the
                                    Java 25 baseline (no new module)
  spark/                         <- FUTURE Spark-side integration module (VARKA-6+);
                                    strategy TBD (see open decision below)
```

Design rules carried from VISION: zero string generation on the happy path;
constants passed as runtime args (never inlined into a plan hash); SIMD with
strict scalar tail; masked loads/stores so null lanes never read garbage;
ghost Janino fallback only in the Spark-side compile hook (VARKA-5).

**Correction (VARKA-6):** VARKA-6 does NOT need an in-reactor `sql/varka/spark/`
module. The Varka node + rule live as additive source in `sql/core`, and the
engine jar is wired as a test-scoped dependency on `sql/core/pom.xml` (build
order: `./build/mvn -f sql/varka/engine/pom.xml install`). Runtime deployment
of the jar is external (`--jars`).

**Correction (VARKA-8):** `VarkaColumnarRule` is auto-registered on every
`SparkSession` (`SparkSession.Builder`); it is inert while
`spark.sql.codegen.varka.enabled` is off, so enabling Varka is purely
config-driven. No new `spark.sql.codegen.varka.*` configs were added beyond
`varka.enabled` (no unused configs).

## 3. MVP task breakdown

| # | Task | Deliverable | Validation | Plan |
| :--- | :--- | :--- | :--- | :--- |
| 1 | **Standalone module + `VarkaMorsel`** | `sql/varka/engine/` Maven module; Arrow `DateDayVector` -> `MemorySegment` mapping | `VarkaMorselTest` | DONE (`VARKA-1.md`) |
| 2 | `DateVectorOps` SIMD kernels | `vectorAddDays` / `vectorSubDays` / `vectorDateDiff` (IntVector + bit-packed mask + scalar tail) | Differential unit test vs scalar reference; JMH vs scalar loop (follow-up) | `VARKA-2.md` |
| 3 | `VarkaClassLoader` + per-task lifecycle | Java loader in the engine with `release()`; registry + `findClass`; `TaskCompletionListener` wiring deferred to the Spark-side integration | Unloadability proof via weak references (1000-loader batch) | `VARKA-3.md` |
| 4 | Catalyst hooks | `ClassFileCodegenSupport` trait; `DateAdd`/`DateSub`/`DateDiff` emit `invokestatic` to `DateVectorOps` | Bytecode disassembly matches expected stack order | `VARKA-4.md` |
| 5 | Class assembly + Ghost fallback | `JavaClassFileEngine` (Class-File API); routing in `CodeGenerator.compile` (gated, inert by default); lazy Janino fallback cached under the same key | Compile-failure injection test hits Janino path, no crash | DONE (`VARKA-5.md`) |
| 6 | Execution-path integration | `VarkaColumnarRule` (`postColumnarTransitions`) rewrites `ProjectExec(projectList, ColumnarToRowExec)` -> `VarkaColumnarToRowExec` when the projection is fully Varka-eligible and `spark.sql.codegen.varka.enabled`; SIMD kernels over Arrow `DateDayVector` buffers; per-batch fallback to the Janino projection | `SELECT DATE_ADD(...)` matches Janino result; `VarkaColumnarToRowExecSuite` + `VarkaEndToEndSuite` green | DONE (`VARKA-6.md`) |
| 7 | Differential + perf testing | `VarkaDifferentialSuite` (Varka on/off `checkAnswer` equality over a query matrix), `VarkaGeneratedClassLoaderSuite` (Metaspace/unloadability), JMH kernel benchmark in the engine module, Spark `BenchmarkBase` throughput + Gen-time benchmarks | `checkAnswer` equality; `numVarkaBatches > 0` on fused plans; loader collection after `release`; throughput/Gen-time metrics | DONE (`VARKA-7.md`) |
| 8 | Config-driven activation + docs | Auto-register `VarkaColumnarRule` on every `SparkSession` (inert unless `spark.sql.codegen.varka.enabled`); `VarkaAutoRegistrationSuite`; comprehensive `docs/sql-varka.md`. Note: no unused configs - `patch.threshold`/`fallback.ghost.enabled` stay design intentions until their code paths exist | Fusion on/off purely by config; existing Varka suites green | DONE (`VARKA-8.md`) |

**Open decision (resolved for VARKA-5):** VARKA-5 keeps the VARKA-4 pattern -
additive source in the existing `sql/catalyst` module (no new module, no pom
changes). A catalyst-side `VarkaGeneratedClassLoader` mirrors the engine's
`VarkaClassLoader` contract, and the kernel is referenced only by name
(Class-File assembly; test-only stub on the catalyst test classpath). The
real `TaskCompletionListener` wiring and the engine jar on the Spark runtime
classpath are VARKA-6; whether that needs an in-reactor `sql/varka/spark/`
module (touching the root `pom.xml` module list + enforcer) stays open for
VARKA-6.
