# Varka Task 1 - Standalone module + `VarkaMorsel`

**Status: COMPLETED** (merged via PR #2). See also `PLAN_MILESTONE_1.md`
for the high-level MVP plan.

## 1. Goal

A self-contained Java 25 module that maps an Arrow `DateDayVector`'s data
buffer and bit-packed validity buffer into `java.lang.foreign.MemorySegment`s,
verified by unit tests against Arrow's own accessors. No Spark, no SIMD ops,
no codegen in this task.

## 2. Files

```
sql/varka/engine/pom.xml
sql/varka/engine/.gitignore                          (target/)
sql/varka/engine/src/main/java/org/apache/spark/sql/varka/memory/VarkaMorsel.java
sql/varka/engine/src/test/java/org/apache/spark/sql/varka/memory/VarkaMorselTest.java
```

## 3. `pom.xml` (key config)

- Standalone project (NO Spark parent - the parent pins Java 17 and enforces
  bytecode version). `groupId=org.apache.spark.varka`,
  `artifactId=varka-engine`.
- `maven.compiler.release=25`; compiler args include
  `--add-modules jdk.incubator.vector` (needed from Task 2 on).
- Deps: `org.apache.arrow:arrow-vector:19.0.0`,
  `org.apache.arrow:arrow-memory-netty:19.0.0` (netty allocation manager for
  `RootAllocator`; add `arrow-memory-unsafe` if the manager fails to resolve),
  test scope `org.junit.jupiter:junit-jupiter` (5.10+).
- `maven-surefire-plugin` 3.2.5. The test JVM `argLine` requires:
  `--add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED
  --sun-misc-unsafe-memory-access=allow -Dio.netty.tryReflectionSetAccessible=true
  --add-opens=...` (Spark's Netty options). Without the Netty flags, Arrow's
  `RootAllocator` fails to initialize
  (`EmptyByteBuf.memoryAddress() -> UnsupportedOperationException`).
- Build with the repo wrapper on JDK 25:
  `build/mvn -f sql/varka/engine/pom.xml test` (first run downloads Maven).

## 4. `VarkaMorsel.java` - public API

```java
package org.apache.spark.sql.varka.memory;

public final class VarkaMorsel {
  private VarkaMorsel() {}

  /** int32 days-since-epoch column + bit-packed validity, mapped to MemorySegments. */
  public record DateMorsel(
      MemorySegment data,      // int32 days; byteSize = dataBuf.capacity() (>= rowCount*4)
      MemorySegment validity,  // bit-packed, 1 bit/row; byteSize = validityBuf.capacity()
                               //   (>= (rowCount+7)/8); null when the vector is all-null
      int rowCount,
      long nullCount) {
    public boolean allNull() { return nullCount == rowCount; }
    public boolean noNulls() { return nullCount == 0; }
  }

  public static DateMorsel extractDate(ValueVector vector, int rowCount);
  public static void reportAlignment(DateMorsel m);  // diagnostic only
  public static boolean isNull(MemorySegment validity, int i);
}
```

`extractDate` steps:

1. Validate `vector instanceof DateDayVector`; `rowCount >= 0`;
   `rowCount <= vector.getValueCount()` (else `IllegalArgumentException`).
2. If `vector.getNullCount() == rowCount` -> `validity = null`, skip validity
   mapping.
3. `ArrowBuf dataBuf = vector.getDataBuffer();` ->
   `data = MemorySegment.ofAddress(dataBuf.memoryAddress()).reinterpret(dataBuf.capacity());`
4. `ArrowBuf validityBuf = vector.getValidityBuffer();` ->
   `validity = MemorySegment.ofAddress(validityBuf.memoryAddress()).reinterpret(validityBuf.capacity());`
5. Return record. Segments are sized to **buffer capacity**, not
   `rowCount*4`/`(rowCount+7)/8` - this is what makes the Task 2 masked reads
   safe at the tail (see pitfalls).

## 5. Pitfalls baked into the design

- **Validity is bit-packed.** Task 2 reads a `long` at validity byte offset
  `i/8` (chunk start `i` is always a multiple of 8) and builds the mask via
  `VectorMask.fromLong(SPECIES, bits)`; `bits` bit *j* corresponds to row
  *i+j*. The doc's byte-per-lane mask is wrong and must not be reproduced.
- **Tail long read can exceed the nominal `(rowCount+7)/8` bytes** (e.g.
  `rowCount=8` -> 1 byte; a `JAVA_LONG` read at offset 0 needs 8). Sizing
  segments to buffer `capacity()` covers the common case, but Task 2 must
  still guard `i/8 + 8 <= validity.byteSize()` before the vector chunk and
  push the remainder to the scalar tail.
- **Zero-capacity buffers**: all-null or 0-row vectors may return capacity-0
  buffers; `reinterpret(0)` is valid, and `nullCount`/`rowCount` fields let
  Task 2 short-circuit.
- **Alignment is diagnostic, not contractual.** `reportAlignment` logs each
  buffer address and its 64-byte alignment; nothing asserts it.
- **Native access**: `MemorySegment.ofAddress` is a restricted method - the
  `--enable-native-access=ALL-UNNAMED` argLine and executor JVM flag (VISION
  section 10) are mandatory.
- **Endianness**: reads use `ByteOrder.LITTLE_ENDIAN` (Arrow's in-memory
  layout) in Task 2, not native order.

## 6. `VarkaMorselTest.java` - test matrix

All cases allocate a `DateDayVector` under an Arrow `RootAllocator`, populate
it, then **assert the segment read-back against Arrow's own `vector.get(i)` /
`vector.isNull(i)` as the oracle**:

1. `N=1000`, alternating valid/null -> data ints and validity bits match;
   `nullCount` matches `vector.getNullCount()`.
2. No-null vector -> `nullCount == 0`, `noNulls()`, all validity bits set.
3. All-null vector -> `validity == null`, `allNull()`.
4. Empty vector (`rowCount=0`) -> both segments valid objects, zero reads.
5. Boundary rows: `N = 1, 7, 8, 9` (validity byte boundaries), `N = 13, 17`
   (non-multiple-of-8 tail), `N = 64, 100, 1000`.
6. `data.byteSize() >= 4*rowCount` and `validity.byteSize() >= (rowCount+7)/8`.
7. `rowCount > vector.getValueCount()` -> `IllegalArgumentException`.
8. `reportAlignment` prints addresses/alignment (log-only, not asserted).

## 7. Definition of done (Task 1)

- Module compiles at `--release 25` with both JVM flags; all `VarkaMorselTest`
  cases pass via `build/mvn -f sql/varka/engine/pom.xml test`.
- No changes anywhere in the Spark reactor (root `pom.xml` untouched; Spark
  still builds at Java 17).
- Alignment diagnostics visible in test output.

## 8. Out of scope for Task 1

`DateVectorOps` SIMD kernels, JMH, `VarkaClassLoader`, Catalyst hooks,
`CodeCompiler` integration, Ghost fallback, `SQLConf` flags - Tasks 2+.

## 9. Findings (post-implementation)

- Arrow's netty allocation manager produced buffers with addresses that were
  **not** 64-byte aligned (e.g. `...010`, `...f10`) in the test run - confirms
  the plan's correction that 64-byte alignment is not guaranteed and must stay
  a diagnostic.
- Arrow's `RootAllocator` needs the full Netty JVM module flags in the test
  JVM (see section 3) or initialization fails with
  `UnsupportedOperationException` from `io.netty.buffer.EmptyByteBuf`.
- Test vectors must be `close()`d before the `RootAllocator.close()` or Arrow
  reports a memory leak.
