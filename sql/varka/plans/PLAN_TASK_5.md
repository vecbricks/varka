# Varka Task 5 - Class assembly + Ghost fallback

**Status: DONE** (see "Implementation notes" in 3.6 for the decisions resolved
during implementation). See `PLAN_MILESTONE_1.md` for the high-level MVP
plan. Task 5 builds on the Task 4 hooks: a Class-File API engine that
assembles the full `GeneratedClass` shape, routing in the single
`CodeGenerator.compile` funnel, a lazy Janino ghost fallback, and a
catalyst-side class loader mirroring the engine's `VarkaClassLoader`.
Execution-path interception (the real batch dispatch) is Task 6.

## 1. Goal

- `JavaClassFileEngine` (catalyst, `java.lang.classfile`): assemble the full
  Janino-equivalent `GeneratedClass` shape for a Varka-eligible codegen
  context, driven by the Task 4 registry (`ctx.classFileGenExpressions`).
- Routing hook in the single `CodeGenerator.compile` funnel, driven by the
  Varka ops attached to `CodeAndComment`.
- Ghost fallback: on assembly/load failure, lazily route to the Janino
  backend; the winning path is cached under the same key so a failed
  assembly is never retried and the user job never crashes.
- Catalyst-side `VarkaGeneratedClassLoader` mirroring the engine's
  `VarkaClassLoader` contract (define + registry + `release()`); per-task
  `TaskCompletionListener` wiring is deferred to Task 6.
- Validation: compile-failure injection test hits the Janino path, results
  correct, no crash.

## 2. Investigated areas (findings)

### 2.1 The compile funnel

- `CodeGenerator.compile(CodeAndComment)` (CodeGenerator.scala:1581) is the
  single funnel: it routes to the cache `loadFunc` (1612) which calls
  `backend.compile(code)` (`CodeCompiler.active(code)`).
- `CodeCompiler.active` routes to the Janino or JDK backend; both implement
  `compile(code): (GeneratedClass, ByteCodeStats)`.
- There is **no** `doCompile`/`currentContext` ThreadLocal in this tree
  (VISION/MVP sketches are outdated); the `CodegenContext` is local to each
  generator and alive exactly where the `CodeAndComment` is constructed.

### 2.2 `CodeAndComment` call sites

Nine `CodeGenerator.compile` call sites; the MVP-relevant ones are:

- `GenerateUnsafeProjection.create` (GenerateUnsafeProjection.scala:436).
- `GenerateMutableProjection` (:149).
- `WholeStageCodegenExec.doCodeGen/doExecute` (WholeStageCodegenExec.scala:742).
- `WholeStageCodegenEvaluatorFactory` (:45).

`WholeStageCodegenExec.doExecute` already wraps compile in a try/catch that
falls back to interpreted execution when `conf.codegenFallback` is set - the
final safety net below the ghost fallback.

### 2.3 The registry (Task 4)

- `ctx.classFileGenExpressions: mutable.ArrayBuffer[ClassFileCodegenSupport]`
  (CodeGenerator.scala:156) is public, populated by
  `ClassFileCodegenSupport.genCode` registration.
- `VarkaClassFileGen.eligibleOps(projectList: Seq[Expression])` computes the
  ops. Note: `ArrayBuffer[ClassFileCodegenSupport]` is invariant, so the
  call site must widen (e.g. `ctx.classFileGenExpressions.map(e => e:
  Expression)`) or `eligibleOps` gains an overload for
  `Seq[ClassFileCodegenSupport]`.

### 2.4 `GeneratedClass` contract

- `abstract class GeneratedClass { def generate(references: Array[Any]): Any }`
  (CodeGenerator.scala:1440).
- `compile` returns a `(GeneratedClass, ByteCodeStats)`; callers invoke
  `clazz.generate(ctx.references.toArray)` (e.g.
  GenerateUnsafeProjection.scala:437). The assembled class must therefore:
  extend `GeneratedClass`, have a public no-arg constructor, and its
  `generate(Object[])` must return the evaluator instance.

### 2.5 Bytecode disassembly constraint

- Scala 2.13 hits a cyclic-reference bug when it reads the sealed Class-File
  instruction hierarchy (`UnboundRetInstruction`); disassembly assertions
  must live in a Java helper (precedent: `ClassFileGenOpVerifier`, Task 4).
  The same bug also strikes during ASSEMBLY typechecking (it fired on the
  `withField` call), so the Class-File assembly itself lives in a Java helper
  (`ClassFileAssembler.java`), not in Scala. To be safe, no Class-File type
  appears in any Java helper's method signature either (Scala loads a Java
  class's full member list and re-triggers the bug on classfile-typed
  signatures).

### 2.6 Engine linkage

- The engine module (`sql/varka/engine`) is standalone, not in the Spark
  reactor; catalyst references `DateVectorOps` only by name
  (`ClassDesc.of`). Runtime resolution of the kernel class is a classpath
  concern (Task 6 deployment). Task 5 tests use a test-only stub kernel with
  the same FQCN.

## 3. Design

### 3.1 `CodeAndComment` carries the Varka ops

- Add `val classFileGenOps: Seq[ClassFileGenOp] = Nil`; excluded from
  `equals`/`hashCode` (like `comment`) since it is a function of the body.
- `CodeFormatter.stripOverlappingComments` reconstructs the `CodeAndComment`
  and must carry the ops through (it drops unknown fields otherwise).
- Generators attach the ops when building the `CodeAndComment`. In Task 5 only
  `GenerateUnsafeProjection` attaches (its generated class is the
  `UnsafeProjection` shape the stub assembles); `GenerateMutableProjection`,
  `WholeStageCodegenExec` and `WholeStageCodegenEvaluatorFactory` attach in
  Task 6 with their own class shapes. Other call sites (predicate/ordering/
  etc.) leave the list empty and keep Janino.
- Codegen-time eligibility: at plan level the start/end operands are
  `Attribute`s, but by the time the funnel runs they are `BoundReference`s.
  `DateVarkaSupport.isDateAttribute` therefore accepts either shape.

### 3.2 Routing + ghost fallback in `CodeGenerator.compile`

- Inside the cache `loadFunc`: if `code.classFileGenOps.nonEmpty` AND routing
  is enabled, try `JavaClassFileEngine.assembleAndLoad` -> define via
  `VarkaGeneratedClassLoader` -> instantiate -> return
  `(GeneratedClass, ByteCodeStats)`. On `NonFatal` -> `logWarning` and fall
  through to `backend.compile(code)` (Janino).
- Because both paths run inside `loadFunc`, the `NonFateSharingCache` caches
  whichever path won under the same key `(classLoaderRef, backend, code)`; a
  failed assembly is never retried.
- Routing is gated behind a thread-scoped `routingEnabledForTesting` (default
  false): the assembled `apply` stub throws, so the happy path must stay
  inert until Task 6 wires the real batch dispatch. Thread scoping keeps the
  test knob from leaking into suites that run concurrently in the same JVM
  (`Test / parallelExecution` is true by default). A compile-failure
  injection (`failAssemblyForTesting`) exercises the ghost fallback.
- `ByteCodeStats`: reuse `CodeCompiler.computeByteCodeStats` on the two
  assembled classes (ASM-based, no Class-File parse in Scala).

### 3.3 `JavaClassFileEngine` (catalyst, Class-File API)

- The Class-File assembly lives in `ClassFileAssembler.java` (main, see 2.5);
  `JavaClassFileEngine.assembleGeneratedClass(className): Seq[(String,
  Array[Byte])]` delegates to it. `ctx`/`ops`/`schema` parameters were not
  needed for the stub shape and were dropped from the signature; Task 6 will
  extend the engine when the dispatch body needs them.
- Produces the Janino-equivalent shape:
  - public wrapper class extending `GeneratedClass` with
    `generate(Object[])` returning `new VarkaProjection(references)`
    (the parameter is local slot 1: slot 0 is `this` - a slot bug here
    surfaced as a `VerifyError` in the first test run);
  - `VarkaProjection` extends `UnsafeProjection`: references field,
    constructor storing it, `initialize(int)` no-op, and `apply(InternalRow)`
    **stub** throwing `UnsupportedOperationException` ("Varka batch
    execution wired in Task 6").
- The stub is honest about Task 5 scope: the batch kernels cannot run per-row;
  real dispatch lands in Task 6's `ColumnarToRowExec` interception.
- Test hooks in the companion (`private[expressions]`):
  `@volatile var routingEnabledForTesting`, `@volatile
  failAssemblyForTesting`, and an `assemblyAttempts` counter proving a cached
  assembly is not retried.

### 3.6 Implementation notes

- `CodeGenerator.invalidateCodegenCache()` (test-visible) clears the static
  cache so the suite is deterministic even though the persistent sbt server
  JVM keeps `CodeGenerator.cache` alive across invocations.
- The fallback catches `NonFatal` **and** `LinkageError`: bad bytecode
  surfaces as `VerifyError`/`ClassFormatError` and a missing kernel as
  `NoClassDefFoundError`, all `LinkageError`s, which `NonFatal` alone would
  let escape. A byte-corruption injection (`corruptAssemblyForTesting`) makes
  the JVM reject the wrapper at definition time so the real catch path (not
  just the short-circuit flag) is tested.
- The loader resolves the `DateVectorOps` FQCN from the parent classloader:
  the test verifies `loader.loadClass("...varka.vector.DateVectorOps")`
  against the test stub.

### 3.4 `VarkaGeneratedClassLoader` (catalyst mirror)

- Extends `ClassLoader`; `defineGeneratedClass(name, bytes)` + registry +
  `findClass` + idempotent `release()` - mirrors the engine
  `VarkaClassLoader` contract. The engine loader remains for engine tests;
  the mirror is the runtime one (catalyst cannot depend on the engine).

### 3.5 Test stub kernel

- Test-only `org.apache.spark.sql.varka.vector.DateVectorOps` with the same
  FQCN on the catalyst test classpath, so any happy-path class linkage
  resolves in tests. Real engine jar on the Spark runtime classpath = Task 6.

## 4. Validation

- **`JavaClassFileEngineSuite`** (catalyst):
  - full-class shape by disassembly (Java helper): wrapper `generate` bridge,
    wrapper->evaluator wiring, references field, `apply` present;
  - `generate(references)` returns an `UnsafeProjection` instance;
  - loader define/release lifecycle (define, load, release -> cleared,
    load-after-release throws).
- **Ghost fallback injection test**: an eligible `DateAdd(startAttr,
  Literal(3))` projection through `GenerateUnsafeProjection` with
  `failAssemblyForTesting=true` -> projection works, results equal the Janino
  path, no crash; an assembly-attempt counter proves the fallback is cached
  (assembled exactly once across repeated `generate` calls).
- **Regression**: existing codegen suites green (`GeneratedProjectionSuite`,
  `GenerateUnsafeProjectionSuite`, `CodegenSubexpressionEliminationSuite`,
  `CodeCompilerSuite`); engine suite untouched.
- **Style**: ASCII, <=100-char lines, scalastyle clean.

## 5. File layout

```
sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/
  ClassFileAssembler.java           (new Java Class-File assembler, see 2.5)
sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/expressions/codegen/
  JavaClassFileEngine.scala            (new)
  VarkaGeneratedClassLoader.scala      (new)
  CodeGenerator.scala                  (CodeAndComment.classFileGenOps + loadFunc
                                        routing + invalidateCodegenCache)
  CodeFormatter.scala                  (stripOverlappingComments carries ops)
  GenerateUnsafeProjection.scala       (attach ops)
  datetimeExpressions.scala            (isDateAttribute accepts BoundReference)
sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/
  ClassFileShapeVerifier.java          (new Java disassembly helper)
sql/catalyst/src/test/java/org/apache/spark/sql/varka/vector/
  DateVectorOps.java                   (test stub, same FQCN as engine kernel)
sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/
  ClassFileCodegenSupportSuite.scala   (BoundReference eligibility tests)
  JavaClassFileEngineSuite.scala       (new)
sql/varka/PLAN_TASK_5.md               (this file)
sql/varka/PLAN_MILESTONE_1.md       (task table update)
```

## 6. Definition of done (Task 5)

- `JavaClassFileEngineSuite` green; ghost fallback injection test hits the
  Janino path with correct results and no crash; assembly not retried after
  the fallback (cached).
- Assembled class shape proven by disassembly (generate bridge + evaluator +
  references wiring + apply present).
- Existing codegen suites green; engine suite untouched.
- ASCII, <=100-char lines; only catalyst/sql-core additive source + docs.

## 7. Explicitly deferred (Task 6)

- `ColumnarToRowExec` interception when the batch is Arrow-backed and the
  projection is Varka-eligible.
- Real batch dispatch (replaces the `apply` stub), destination buffer
  allocation, Arrow buffer -> `MemorySegment` mapping.
- `TaskCompletionListener` release wiring for `VarkaGeneratedClassLoader`.
- Engine jar on the Spark runtime classpath (kernel resolution).
- End-to-end `SELECT DATE_ADD(...)` matching Janino results.

## 8. Follow-ups / risks

- The funnel routes on `classFileGenOps` attached at `CodeAndComment`
  construction, so the Janino string is still built on the happy path (cheap
  concatenation; Janino parsing/compilation is what is skipped). VISION's
  strict "zero strings" would require early routing before string build - a
  follow-up refinement to revisit when Task 6 touches the generators.
- The `apply` stub is a landmine if anything invokes it before Task 6; routing
  is gated behind `routingEnabledForTesting` (default false) so the stub is
  never produced for real queries, and the ghost test never reaches it.
- `CodeFormatter.stripOverlappingComments` and any future `CodeAndComment`
  transformation must carry `classFileGenOps` through.
- The Class-File API differs between JDK majors (e.g. `MethodModel.methodType`
  returns `Utf8Entry` in JDK 25 vs `MethodTypeDesc` in 24); the Java helpers
  pin the JDK 25 shapes and the shape verifier keeps that contract honest.