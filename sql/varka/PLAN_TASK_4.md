# Varka Task 4 - Catalyst hooks (`ClassFileCodegenSupport`)

**Status: IMPLEMENTED (PR #5), updated after the Java 25 baseline decision.**
See `PLAN_MILESTONE_1.md` for the high-level MVP plan. Task 4 is the
declarative Catalyst hook: a marker trait + emission contract that marks
`DateAdd` / `DateSub` / `DateDiff` as Varka-eligible, pins the exact
`invokestatic` stack order for the `DateVectorOps` batch kernels, and
assembles the probe class with the Class-File API in catalyst (Java 25+
baseline). Runtime routing (the `JavaClassFileEngine` assembler + ghost
fallback) is Task 5; execution interception is Task 6.

## 1. Goal

- A `ClassFileCodegenSupport` trait (catalyst, Scala) that `DateAdd` /
  `DateSub` / `DateDiff` mix in, exposing a pure-data emission spec
  (`ClassFileGenOp`) plus an eligibility rule.
- A `CodegenContext` registry (`classFileGenExpressions` /
  `isClassFileGenEligible`) - the hook Task 5's router consumes.
- Catalyst-owned Class-File assembly: `VarkaClassFileGen.assembleKernelClass`
  emits the `invokestatic` probe for each kernel, and the catalyst test
  **disassembles** it to prove the stack order.
- An engine-side (Java 25) **define-and-run integration** test that defines a
  probe of the same shape via `VarkaClassLoader`, runs it functionally, and
  cross-checks the kernel descriptors via reflection.
- **Zero-risk:** the runtime string codegen of the three expressions is
  unchanged in Task 4. Existing behavior is preserved; routing is deferred.

## 2. Investigated areas (findings)

### 2.1 The expressions (datetimeExpressions.scala)

- `DateAdd(startDate: Expression, days: Expression)` (line 524) and `DateSub`
  (line 571) - `days` is an **Expression** (typically a `Literal`), typed
  `IntegerType`/`ShortType`/`ByteType` via `inputTypes`.
- `DateDiff(endDate: Expression, startDate: Expression)` (line 3531), both
  children `DateType`.
- All three override `doGenCode` (`nullSafeCodeGen` / `defineCodeGen`) and are
  `nullIntolerant`. They are not `CodegenFallback`.

### 2.2 The interception point

- `Expression.genCode` (Expression.scala:216-238) is the public entry;
  `doGenCode` (line 280) is the string hook. VISION directs the trait to
  intercept `genCode`, register, and not generate strings on the happy path.
- `CodegenFallback` (CodegenFallback.scala:26-65) is the existing precedent of
  diverting codegen through registration (it appends `this` to
  `ctx.references`).
- There is no `doCompile`/`currentContext` in this tree; compile is
  `CodeGenerator.compile` -> `CodeCompiler.active(code)` (CodeGenerator.scala:
  1567-1614).

### 2.3 The kernels (engine, Java 25)

`DateVectorOps` (Task 2) is batch/columnar, all `void`, primitives only
(DateVectorOps.java:67-184). Argument order IS the JVM stack order:

- `vectorAddDays(long srcData, long srcValidity, int srcNullCount,
  long dstData, long dstValidity, int length, int daysOffset)` ->
  descriptor `(JJIJJII)V`.
- `vectorSubDays` -> same descriptor, `(JJIJJII)V`.
- `vectorDateDiff(long dataA, long validityA, int nullCountA,
  long dataB, long validityB, int nullCountB,
  long dstData, long dstValidity, int length)` ->
  descriptor `(JJIJJIJJI)V`.

### 2.4 Module constraint (resolved: Java 25+ baseline)

- The repo now builds at Java 25 (`pom.xml` `java.version=25`; the enforcer and
  `--release` flags follow `java.version`). Catalyst CAN use `java.lang.classfile`
  (JDK 22+), so the assembler lives in catalyst.
- The engine remains a standalone module at `--release 25`
  (`org.apache.spark.sql.varka:varka-engine:0.1.0-SNAPSHOT`) by design, not by a
  version split: its tests need `--add-modules jdk.incubator.vector` /
  `--enable-native-access` flags that are not part of the Spark build.
- **No new Spark-side module is needed for Task 4**: additive source in the
  existing `sql/catalyst` module plus the existing engine module. No engine
  dependency in catalyst; the kernel class is referenced by name
  (`ClassDesc.of`). Runtime linkage of the engine (Task 5) will be by
  name/reflection.

### 2.5 Disassembly surface (verified)

`ClassFile.of().parse` -> `ClassModel` -> method `CodeModel.elements()`
yields instruction views: `InvokeInstruction` (`owner()`, `name()`,
`typeSymbol()` -> `MethodTypeDesc`) and `LoadInstruction` (`slot()`,
`typeKind()`). This is exactly what the stack-order assertion needs.

## 3. Design

### 3.1 `ClassFileGenOp` (catalyst, pure data)

```scala
case class ClassFileGenOp(
    ownerClassName: String,     // "org.apache.spark.sql.varka.vector.DateVectorOps"
    methodName: String,         // "vectorAddDays" | "vectorSubDays" | "vectorDateDiff"
    methodDescriptor: String)   // "(JJIJJII)V" | "(JJIJJIJJI)V"
```

### 3.2 `trait ClassFileCodegenSupport` (catalyst)

```scala
trait ClassFileCodegenSupport extends Expression {
  def classFileGenOp: ClassFileGenOp
  def isClassFileGenEligible: Boolean
}
```

The trait also overrides `genCode` to register `this` into the ctx registry
and then delegate to `super.genCode(ctx)` - runtime behavior unchanged, the
registry populated for Task 5's router.

### 3.3 Patched expressions

- `DateAdd`/`DateSub`: `classFileGenOp` = the add/sub kernel spec;
  `isClassFileGenEligible` = `startDate` is a plain `Attribute` of `DateType`
  and `days` folds to an integral constant (`DateVarkaSupport.foldDaysOffset`
  via `Number.intValue()`; null or non-foldable days -> not eligible). The
  fold helper lives with the date expressions, not on the trait.
- `DateDiff`: `classFileGenOp` = the diff kernel spec; eligible when both
  children are plain `Attribute`s of `DateType`.
- Nested Varka expressions (e.g. `DateDiff(DateAdd(a, 1), b)`) are out of MVP
  eligibility (the batch kernel needs concrete column buffers).

### 3.4 `CodegenContext` registry (additive)

- `classFileGenExpressions: mutable.ArrayBuffer[ClassFileCodegenSupport]`
- `registerClassFileGenExpression(e)`
- `isClassFileGenEligible: Boolean`

### 3.5 Plan-level collector + assembler `VarkaClassFileGen` (catalyst)

- `def eligibleOps(projectList: Seq[Expression]): Seq[ClassFileGenOp]` - used by
  Task 6 (ColumnarToRowExec interception) and Task 5 (assembler input). Unit
  tested in Task 4.
- `def assembleKernelClass(className: String, op: ClassFileGenOp): Array[Byte]`
  - Class-File API assembly (Java 25+): a public class with a default
  constructor and a static `run` method that loads the kernel parameters in
  order and emits a single `invokestatic` to the kernel. Task 5 defines the
  result via the engine's `VarkaClassLoader`.

## 4. Validation - "bytecode disassembly matches expected stack order"

- **Catalyst-side** `ClassFileCodegenSupportSuite`: asserts the emission spec
  strings equal the engine's actual descriptor constants; assembles via
  `assembleKernelClass` and `ClassFile.of().parse(bytes)` to assert exactly one
  `InvokeInstruction` with the right owner/name/`typeSymbol`, preceded by the
  `LoadInstruction` sequence in the exact argument order; the eligibility
  matrix (literal vs non-literal days; byte/short/int literals; non-date
  children; DateDiff nested-child exclusion); the `CodegenContext` registry.
- **Engine-side** `DateVectorOpsEmissionTest` (Java 25): defines a probe of the
  same shape (mirrored assembler - the engine cannot depend on catalyst) via
  `VarkaClassLoader`; runs it on a small native buffer (functional check vs a
  scalar reference); and pins the kernel descriptors from the actual methods
  via reflection, so the catalyst contract strings cannot silently drift.

## 5. File layout

```
sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/expressions/codegen/
  ClassFileCodegenSupport.scala    (trait + ClassFileGenOp + VarkaClassFileGen)
  CodeGenerator.scala              (CodegenContext registry, additive)
sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/expressions/
  datetimeExpressions.scala        (patch DateAdd/DateSub/DateDiff)
sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/
  ClassFileCodegenSupportSuite.scala
sql/varka/engine/src/test/java/org/apache/spark/sql/varka/vector/
  DateVectorOpsEmissionTest.java
sql/varka/PLAN_TASK_4.md
sql/varka/PLAN_MILESTONE_1.md   (task table update)
```

No new modules: `sql/catalyst` and the engine are existing modules. The root
`pom.xml` bumps `java.version` 17 -> 25 (the Java 25 baseline decision).

## 6. Definition of done (Task 4)

- Catalyst suite green (`ClassFileCodegenSupportSuite`), no regressions in the
  existing codegen suites.
- Engine suite green (`build/mvn -f sql/varka/engine/pom.xml test`, all prior
  + new emission test).
- Stack order proven by disassembly in catalyst; the engine integration test
  cross-checks the descriptors via reflection.
- Only `sql/varka/` + additive catalyst sources + the root `java.version` bump;
  ASCII, <=100-char lines.

## 7. Explicitly deferred

- Task 5: `JavaClassFileEngine` (full `GeneratedClass` assembly), routing at
  `CodeGenerator.compile`/`CodeCompiler` (skip string generation when
  eligible), ghost Janino fallback + cache, Spark-side `VarkaClassLoader`
  wiring.
- Task 6: `ColumnarToRowExec` interception, destination buffer allocation,
  non-eligible column handling, end-to-end `SELECT DATE_ADD(...)`.
- Task 7: differential + Metaspace stress. Task 8: config flags.

## 8. Follow-ups / risks

- Nested Varka expressions and non-foldable `days` are excluded from MVP
  eligibility; they keep the existing string path.
- The registry + `genCode` registration must not change the runtime codegen
  path in Task 4 (additive only).
