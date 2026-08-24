# Task 9: the IR + emitter spike

**Status: PLANNED.** See `PLAN_MILESTONE_2.md` for the milestone this task
opens. This file is the plan; on completion it is updated with what was actually
built, the measurement tables, and any deviations - the milestone 1 convention.

## 1. Why this task exists, and why first

Milestone 2 stands on one bet: that a Class-File-API-emitted vector loop can
match the hand-written kernels' speed, with HotSpot C2 intrinsifying the
emitted Vector API calls. Task 9 is the spike that settles the bet before
anything is built on it - single op, single output, no integration into the
execution path. Its contract from the milestone plan: the generated class
verifies and matches `DateVectorOps` row for row; the intrinsification gate
passes at the preferred width and at `-XX:MaxVectorSize=16`; the chain-depth
cap is measured and fixed as a number.

Build facts this plan rests on, verified: test JVMs get
`--add-modules=jdk.incubator.vector` (`SparkBuild.scala:342`) and
`--enable-native-access=ALL-UNNAMED` (`:2074`), so emitted vector code runs in
catalyst tests; catalyst already hosts `BenchmarkBase` benchmarks
(`CalendarIntervalBenchmark`) with results in `sql/catalyst/benchmarks/`.

Preconditions are met: the columnar-write split and the reactor change are both
merged into master, which closes milestone 1 entirely. The work branches from
current master. With the reactor change in, sbt builds the engine in-tree and
puts its jar on catalyst's test classpath itself - no manual install step
remains.

## 2. Deliverables

### 2.1 Engine: `VarkaVectorSupport` (promotion, no behavior change)

New `engine/src/main/java/org/apache/spark/sql/varka/vector/VarkaVectorSupport.java`
- public final class, private constructor - holding the helpers promoted from
`DateVectorOps` (which keeps using them, same package): `validityBitsAt`,
`orValidityBitsAt`, `laneMask`, `groupBytes`, `zero`, `isBitSet`, `setBit`.
Javadoc at reference-code level, like the kernels. Engine tests must pass
unchanged (imports in the bitmap tests may need the new class name). Generated
code will call these by name; the engine stays off catalyst's compile
classpath, per the milestone 1 rule.

### 2.2 Catalyst: the IR

`sql/catalyst/src/main/java/.../expressions/codegen/varka/VarkaVectorIR.java`:
a `sealed interface` with nested records - task 9 defines only what it emits:
`ColumnRef(int ordinal)`, `LiteralSlot(int index)`,
`AddDays(VarkaVectorIR node, VarkaVectorIR days)`, `SubDays(...)`. Every node
carries the lane type (an enum, only `INT` for now); the emitter rejects
anything else. `SubDays` is included solely so the chain-depth measurement can
alternate ops if C2 reassociates a pure `AddDays` chain. Task 10 adds the rest
via new `permits` entries. No Catalyst-to-IR compiler yet - tests build IR by
hand; `VarkaExpressionCompiler` is task 10.

### 2.3 Catalyst: the kernel shape

`.../codegen/varka/VarkaFusedKernel.java`:

```java
public interface VarkaFusedKernel {
  void run(long[] srcData, long[] srcValidity, int[] srcNullCount,
           long[] dstData, long[] dstValidity, int[] scalarArgs, int length);
}
```

Loaded by the parent loader; the generated class implements it (instance
method, `this` in slot 0, parameters from slot 1 - milestone 1's finding 11
lesson). Callers reuse the arrays across batches, so nothing is allocated per
call.

### 2.4 Catalyst: the emitter

`.../codegen/varka/VarkaLoopEmitter.java`, entry point
`static byte[] emit(String className, List<VarkaVectorIR> outputs,
int numInputs, int numLiterals)` - a list for forward compatibility,
`size() == 1` required in task 9.

**Descriptor table** as private static finals - the single source of truth for
every emitted call, because erasure is the milestone's named risk. The
load-bearing entries:

| call | kind | note |
| :--- | :--- | :--- |
| `MemorySegment.ofAddress(long)` | invokestatic (interface) | `(J)Ljava/lang/foreign/MemorySegment;` |
| `MemorySegment.reinterpret(long)` | invokeinterface | returns `MemorySegment` |
| `IntVector.SPECIES_PREFERRED` | getstatic | `Ljdk/incubator/vector/VectorSpecies;` |
| `VectorSpecies.length()` / `loopBound(int)` | invokeinterface | `()I` / `(I)I` |
| `IntVector.broadcast(VectorSpecies, int)` | invokestatic | returns `IntVector` |
| `VectorMask.fromLong(VectorSpecies, long)` | invokestatic | returns `VectorMask` |
| `IntVector.fromMemorySegment(species, seg, long, order, mask)` | invokestatic | returns `IntVector` |
| `IntVector.add/sub(Vector, VectorMask)` | invokevirtual | takes *erased* `Vector`, returns `IntVector` |
| `intoMemorySegment(seg, long, order, mask)` | invokevirtual | `void` |
| `VectorMask.toLong()` | invokevirtual | `()J` |
| `ByteOrder.LITTLE_ENDIAN` | getstatic | on `java/nio/ByteOrder` |
| `VarkaVectorSupport.*` | invokestatic | signatures per 2.1 |
| `MemorySegment.get/set(ValueLayout$OfInt, long)` | invokeinterface | scalar tail |

**Emitted method structure**, mirroring `vectorAddDays` shape for shape:

* Prologue, all loop-invariant per the LICM rule: `length <= 0` guard; each
  array element unpacked into a local (segments via
  `ofAddress(...).reinterpret(...)` with the nominal sizes `length * 4` and
  `(length + 7) / 8`, the kernels' bounds discipline); every output validity
  zeroed unconditionally (the milestone's emitter invariant); all-null shortcut
  return; `hasNulls` per input; one hoisted broadcast per `LiteralSlot` read
  from `scalarArgs`; species, lanes and loopBound locals. The species is read
  with `getstatic` so it stays a JIT constant.
* Vector loop, per lane group: input mask from `validityBitsAt` (all-true mask
  when null-free, as the kernels do); one `fromMemorySegment` per referenced
  column; a post-order walk of the IR leaving `IntVector`s on the operand
  stack (`AddDays` becomes `add(broadcast, mask)`); one masked store;
  `orValidityBitsAt` with `mask.toLong()`.
* Scalar tail: a second post-order walk emitting `iload`/`iadd`/`isub` - int
  wrap-around matches `DateAdd` semantics by construction - guarded by
  `isBitSet`, writing `setBit` on valid rows. Mirrors the vector body row for
  row; the differential lengths below are chosen to prove it.
* Depth cap: a `MAX_CHAIN_DEPTH` constant; deeper IR throws
  `IllegalArgumentException` (the future evaluator wiring treats that as
  fall-back). The number comes from the measurement below, with a comment
  citing it.

The Class-File API generates stack map frames itself - that, plus the
descriptor table, is the point of using it. Loading goes through the existing
`VarkaGeneratedClassLoader`, exactly as `assembleKernelClass` classes do.

### 2.5 Tests: `VarkaLoopEmitterSuite` (catalyst, `SparkFunSuite`)

Buffers from `Arena.ofConfined()`; no Arrow needed.

* `ClassFile.of().verify(bytes)` returns no errors, before any load.
* Differential vs `DateVectorOps.vectorAddDays` (the reference semantics):
  lengths `{0, 1, lanes-1, lanes, lanes+1, 63, 64, 65, 1000, 4096, 4097}`
  crossed with null patterns `{null-free, mixed, alternating, all-null}` and
  offsets `{0, 1, -1, 3, Int.MaxValue - 1}` (wrap-around included).
  Byte-for-byte on the data of valid rows and on the validity bitmap.
* Chain depth N (hand-built `AddDays`/`SubDays` nests) vs N sequential kernel
  passes over the same buffers.
* Depth over `MAX_CHAIN_DEPTH` rejected cleanly; a non-`INT` lane type
  rejected.
* The class unloads after `release()` - the weak-reference pattern from
  `VarkaGeneratedClassLoaderSuite`.
* Negative control for diagnosability: sabotage one descriptor-table entry
  (wrong erasure) in a test hook and confirm verification catches it *and the
  failure names the call* - a future descriptor regression must not surface as
  a bare `VerifyError`.

### 2.6 Benchmark: `VarkaEmitterParityBenchmark` (catalyst, `BenchmarkBase`)

Cases over 1M-row off-heap buffers, null-free and mixed-null: hand-written
`vectorAddDays` vs the generated depth-1 loop (the **parity gate**); generated
chains at depth `{1, 2, 4, 8, 16}` vs equivalent sequential kernel passes (the
fusion preview, and the data behind the depth cap). Results to
`sql/catalyst/benchmarks/VarkaEmitterParityBenchmark-jdk25-results.txt` at the
preferred width; the `-XX:MaxVectorSize=16` run's numbers are recorded in this
file's results section (one committed results file per benchmark, per repo
convention).

**Gate acceptance:** generated depth-1 best-time throughput at least 0.9x the
hand-written kernel, at both widths. On failure: stop, diagnose with
`-XX:+UnlockDiagnosticVMOptions -XX:+PrintIntrinsics` (most likely cause: the
species stopped being a JIT constant), and do not build task 10 on top.

### 2.7 Docs and decisions

* This file updated to DONE, with the measurement tables (both widths), the
  chosen `MAX_CHAIN_DEPTH` and its justification, and deviations from the plan.
* `PLAN_MILESTONE_2.md` task 9 row updated.
* The milestone's section 7 decision, recorded not coded: with the emitter
  proven, the recommendation on the unrouted `VarkaProjection` shell
  (`JavaClassFileEngine` / `ClassFileAssembler`) - keep for a milestone 3
  row-path generator, or delete in a follow-up PR - is written here with
  rationale.

## 3. Verification

```
./build/mvn -f sql/varka/engine/pom.xml test          # helpers promoted, engine green
./build/mvn -f sql/varka/engine/pom.xml test -Dvarka.jmh=true
build/sbt "catalyst/testOnly *VarkaLoopEmitterSuite *ClassFileCodegenSupportSuite \
  *VarkaGeneratedClassLoaderSuite *JavaClassFileEngineSuite"
build/sbt 'set catalyst/Test/javaOptions += "-XX:MaxVectorSize=16"' \
  "catalyst/testOnly *VarkaLoopEmitterSuite"
SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt \
  "catalyst/Test/runMain org.apache.spark.sql.VarkaEmitterParityBenchmark"
build/sbt "sql/testOnly *Varka*"        # helper promotion changed nothing downstream
build/sbt "catalyst/scalastyle" "catalyst/Test/scalastyle" && dev/lint-java
```

## 4. Explicitly out of task 9

Catalyst-to-IR compilation, any rule or evaluator wiring, DAG interning,
predication, partial eligibility, multi-output, telemetry attributes, the
`lazy val` drive-by - tasks 10 to 15. The diff should review as: one engine
refactor, four new catalyst files, one suite, one benchmark, two docs.
