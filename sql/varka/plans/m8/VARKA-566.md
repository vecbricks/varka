# VARKA-566: One function from a type to its output width

*Opened 11 November 2026 as a build sheet. The row (`PLAN.md` row 566, from the review of #1348)
had no plan file. Its text: the rule that a decimal output is stored at 16 bytes a row is written
twice, as `VarkaKernelRunner.outputWidth` over a vector and as a literal in
`VarkaEvaluatorBase.java`'s warm-up, and the old overload of `VarkaKernelWarmup.start` still
fills 8 bytes a row; sizing the warm-up's decimal outputs at 8 bytes is what the memory sanitizer
caught in #1348. Done when one function from a type to its byte width serves the allocator and
the warm-up, the old overload is test-only or gone, and a test sends a decimal output through each
path.*

## Build sheet (11 November 2026)

Follow `BUILD_SHEETS.md` (same folder). Everything below was read at origin/master `c62f492fc89`.
`<main>` is `sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka`,
`<test>` the same path under `src/test/java`, `<core>` is
`sql/core/src/main/java/org/apache/spark/sql/execution`.

**The rule, as master has it.** A `DECIMAL` output is sixteen bytes a row **at every precision**
(Arrow's decimal slot; `VarkaLaneArrow`'s `DECIMAL` entry, and the comment above the literal in
`VarkaEvaluatorBase.kernelReady`). Outputs above precision 18 are declined at plan time. A rule
"16 above 18 digits, else 8" would size a `DECIMAL(14, 6)` output at 8 bytes, the fault #1348
caught: do not write it.

**The design, decided here.** The one function lives in the type registry, `VarkaTypes` (catalyst
main), which both sql/core and catalyst can call, and it reads the registry's own `Storage`:

| Storage (`VarkaTypes.Info.storage()`) | Types | `outputWidth` returns |
|---|---|---|
| `Plain(bits)` | BYTE 1, SHORT 2, INT, DATE, YM_INTERVAL, FLOAT 4; LONG, DOUBLE, TIME, DT_INTERVAL, TIMESTAMP_NTZ, TIMESTAMP (declined, never an output) 8 | `bits / 8` |
| `DecimalSlot` | DECIMAL, every precision | `16` |
| `Bitmap` | BOOLEAN | `VarkaReproduction.KernelCall.BIT_OUTPUT` (`-1`) |

The evaluator's destinations take this width as the kernel's `dstWidth`, exactly what
`VarkaKernelRunner.outputWidth(vector)` returned (Arrow's type width, or `BIT_OUTPUT` for a
`BitVector`). The warm-up takes the same widths and allocates `Math.max(width, Long.BYTES)` bytes a
row for each destination, so its sizes do not change: sixteen for a decimal, eight for every other
output and for a filter's selection, as today. Tightening the warm-up to exact widths is out of
this row. A type with no registry id throws: no such type reaches an output, since
`VarkaLaneArrow.allocate` already returns no vector for it and the plan declines it.

### B0. Premise re-check

| Command | Expected |
|---|---|
| `grep -n 'instanceof DecimalType ? 16 : 8' <core>/VarkaEvaluatorBase.java` | one line, in `kernelReady` |
| `grep -n 'VarkaKernelRunner.outputWidth(' -r sql --include='*.java' --include='*.scala'` | two lines: `VarkaEvaluatorBase.java` (`runKernel`) and `sql/core/src/test/java/.../VarkaSameAnswerCoreContractSuite.java` |
| `grep -n 'eightBytes' <main>/VarkaKernelWarmup.java` | three lines, in the first `start` overload |
| `grep -rn 'VarkaKernelWarmup.start(' sql/catalyst/src/test sql/core/src` | four lines: `VarkaKernelWarmupProbe.java`, `VarkaKernelHistoryProbe.java`, `VarkaSanitizedWarmupSuite.scala` (the 8-byte overload) and `VarkaWarmupGate.java` (the other) |
| `grep -n 'dstWidths\[o\]' <main>/VarkaKernelWarmup.java` | two lines, both `allocRows(..., dstWidths[o])` in `Job` |
| `grep -c 'drawDecimal' <test>/VarkaKernelHistoryProbe.java` | `0` (its shapes have no decimal output) |
| `grep -n 'record DecimalSlot\|record Bitmap\|record Plain' <main>/VarkaTypes.java` | three lines |

### B1. Scope

1. **`<main>/VarkaTypes.java`**, after `idOf`:

   ```java
   /**
    * The width a kernel gives an output of {@code dataType}, read from the type's storage: its
    * bytes a row for a plain value, sixteen for a decimal's slot at every precision, and
    * {@link VarkaReproduction.KernelCall#BIT_OUTPUT} for a BOOLEAN's bitmap. The one answer the
    * evaluator's destinations and the kernel warm-up share (VARKA-566). Allocates nothing, so a
    * batch can ask it.
    */
   public static int outputWidth(DataType dataType) {
     VarkaTypeId id = idOf(dataType);
     if (id == null) {
       throw VarkaErrors.illegalArgument("no Varka output is of type " + dataType);
     }
     return switch (info(id).storage()) {
       case Storage.Plain p -> p.bits() / Byte.SIZE;
       case Storage.DecimalSlot d -> 16;
       case Storage.Bitmap b -> VarkaReproduction.KernelCall.BIT_OUTPUT;
     };
   }
   ```
2. **`<core>/VarkaEvaluatorBase.java`**:
   - `runKernel`: `kernel.dstWidth[o] = VarkaKernelRunner.outputWidth(vector);` becomes
     `kernel.dstWidth[o] = VarkaTypes.outputWidth(outputTypes[o]);` (add the import of
     `org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypes`).
   - `kernelReady`: the loop body becomes
     `outputBytes[o] = o < outputTypes.length ? VarkaTypes.outputWidth(outputTypes[o]) : Long.BYTES;`
     with the comment "Each output's width (VarkaTypes.outputWidth), which the warm-up sizes its
     destination by; a filter has no typed outputs, its one destination being the selection."
3. **`<core>/varka/VarkaKernelRunner.java`**: delete `outputWidth(BaseFixedWidthVector)` and its
   javadoc. Keep `warmupWidth`, `bytesPerRow` and `floatingOutput`.
4. **`<core>/varka/VarkaWarmupGate.java`**: the constructor's `@param outputBytes` becomes "each
   output's width, as `VarkaTypes.outputWidth` gives it, or eight for a filter's selection".
   Names stay.
5. **`<main>/VarkaKernelWarmup.java`**:
   - Delete the `start` overload that takes `int numOutputs` (the one with `eightBytes`), and the
     `java.util.Arrays` import if `grep -c 'Arrays\.'` then prints `0`.
   - The remaining `start`'s javadoc becomes the deleted overload's (its summary paragraph and its
     `@param srcWidths`, `nullable` and `kernel`), followed by "@param dstWidths each output's width, as `VarkaTypes.outputWidth` gives it; a destination
     is allocated at least eight bytes a row, so a decimal's is sixteen (VARKA-157) and a bitmap's
     or a narrow lane's is eight, and each is registered at the rows a call can reach". The old
     "As above, ..." paragraph goes.
   - Directly after the remaining `start`, add `static long destinationBytes(int width) { return Math.max(width, Long.BYTES); }` with the
     javadoc "The bytes a row the warm-up allocates for an output of {@code width}: its width, and
     at least eight, a lane's value, which a bitmap's marker of -1 also takes." In `Job`, both
     `allocRows(..., dstWidths[o])` become `allocRows(..., destinationBytes(dstWidths[o]))`.
6. **The three callers of the deleted overload** pass widths explicitly, eight for each output
   (their outputs are lane values):
   - `<test>/VarkaKernelWarmupProbe.java` and `<test>/VarkaKernelHistoryProbe.java`: build
     `int[] outWidths = new int[n]; Arrays.fill(outWidths, Long.BYTES);` (the history probe
     already has an input `widths`) with `n` the argument they
     passed (`numOutputs`, `s.roots().size()`), pass `outWidths` as `dstWidths`, and import `java.util.Arrays`.
   - `sql/catalyst/src/test/scala/.../VarkaSanitizedWarmupSuite.scala`: `1` becomes `Array(8)`.
7. **`sql/core/src/test/java/org/apache/spark/sql/execution/VarkaSameAnswerCoreContractSuite.java`**:
   `VarkaKernelRunner.outputWidth(served)` becomes `VarkaTypes.outputWidth(p.type())`; import
   `org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypes`.
8. **Tests** (B2): one assertion in `VarkaLaneArrowSuite`, one test in `VarkaWarmupEndToEndSuite`.
9. **`PLAN.md` row 566**: the row opens `**Planned** (5 November 2026, ...`; replace
   `**Planned**` with `**Done**`. **This file**: an "Outcome" section at the end.

**Out of scope:** the warm-up's exact sizing of non-decimal outputs; `warmupWidth` and
`bytesPerRow` (inputs, not outputs); `VarkaLaneArrow`; any generated file.

### B2. Tests and their oracles

1. **The allocator path**, `sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaLaneArrowSuite.scala`,
   test "every form of a type a lane writes allocates its vector at the registry's width": inside
   the loop, after `val width = ...`, add
   `assert(VarkaTypes.outputWidth(form) === (if (outputOnly.contains(id))
   VarkaReproduction.KernelCall.BIT_OUTPUT else width), form.sql)` (import `VarkaReproduction`).
   Oracle: Arrow's own `getTypeWidth` of the vector the evaluator allocates; the decimal forms of
   precision 18 or less are among the forms.
2. **The warm-up path**, `sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaWarmupEndToEndSuite.scala`,
   a test after the boolean one: "a decimal output's warm-up writes its sixteen-byte slots inside
   the sanitizer". It `assume`s `VarkaMemorySanitizer.ENABLED` as the boolean test does, records
   `VarkaMemorySanitizer.checked()`, and calls `checkWarmup("SELECT time_to_seconds(t) AS s FROM
   varka_times_big", cache)` where `cache(session)` builds `numRows` rows
   `(if (i % 7 == 3) null else java.time.LocalTime.ofSecondOfDay(i % 86400).withNano(i % 1000 *
   1000000), i)` with `session.createDataFrame(rows).toDF("t", "i")`, makes the view
   `varka_times_big`, caches it and counts it, as the boolean test's builder does, at the default batch size (four batches). Then it
   asserts `checked()` grew. Oracle: the row engine's answer (`checkWarmup` compares both runs
   with it) and the sanitizer, which fails a store past a destination's registered rows.

### B3. Planted faults

1. `destinationBytes` returns `Long.BYTES` always: the new warm-up test fails under the sanitizer
   (a `VarkaMemoryViolation` from the warm-up, through `awaitIdle` or the verdict).
2. `outputWidth`'s `DecimalSlot` arm returns `8`: `VarkaLaneArrowSuite` fails on a decimal form,
   and the warm-up test fails as in 1.
Revert each before the next.

### B4. Commands, in order

```
flock "$VARKA_HEAVY_LOCK" build/sbt 'sql/testOnly *VarkaLaneArrowSuite'
flock "$VARKA_HEAVY_LOCK" build/sbt 'set LocalProject("sql") / Test / javaOptions += "-Dvarka.sanitizeMemory=true"' 'sql/testOnly *VarkaWarmupEndToEndSuite *VarkaKernelEvaluatorSuite *VarkaMemorySanitizerEndToEndSuite *VarkaTimeHarnessSuite'
flock "$VARKA_HEAVY_LOCK" build/sbt 'set LocalProject("catalyst") / Test / javaOptions += "-Dvarka.sanitizeMemory=true"' 'catalyst/testOnly *VarkaSanitizedWarmupSuite *VarkaKernelWarmupSuite'
flock "$VARKA_HEAVY_LOCK" build/sbt 'sql/testOnly *VarkaSameAnswerCoreContractSuite'
```

Every suite passes with no test canceled (the two `assume`-gated warm-up tests run). Then the
planted faults, then `BUILD_SHEETS.md` section 6. No generated file moves: `git diff
--exit-code -- sql/varka/coverage.json sql/varka/emitted_bytes.json sql/varka/width_audit.json`
exits 0.

### B5. Done when

`grep -rn 'VarkaKernelRunner.outputWidth\|eightBytes\|instanceof DecimalType ? 16'
sql/catalyst/src/main sql/core/src/main sql/catalyst/src/test/java sql/core/src/test/java` prints
nothing; `grep -c 'VarkaTypes.outputWidth' <core>/VarkaEvaluatorBase.java` prints `2`; B4 passes;
`python3 dev/varka_push_check.py` exits 0.

### B6. Size

About 120 counted lines: the function 20, the evaluator and runner 15, the warm-up 25, the callers
15, the tests 30, the plan and outcome 20.

### B7. Stop rules

- `VarkaLaneArrowSuite`'s new assertion fails for a non-decimal form: the registry and Arrow
  disagree; report the form and stop.
- The new warm-up test does not reach the kernel (`checkWarmup` fails on `numWarmupBatches` or
  `numVarkaBatches`): `time_to_seconds` did not fuse over this view; report and stop, do not pick
  another query.
- Any suite of B4 fails that passed on master.

### B8. Decisions already taken

- The home (`VarkaTypes`), the table above, and that the warm-up keeps its sizes: decided in
  this sheet, 11 November 2026, by the lead's brief ("decide its home so both the Arrow-vector side
  and the DataType side can call it").
- The selection note's "decimal above 18 digits 16, else 8" is wrong for master and is not used.

### B9. Afterwards (the lead)

For the agent: the commit subject is `[VARKA-566] Size Varka outputs by one width function of the type`.

Review and push. Open pull requests on the same files: #1478 and #1484 (`VarkaEvaluatorBase.java`,
the families' javadoc near `emitOptions`, another function) and #1428, #1478, #1484 (`PLAN.md`):
rebase if one merged first.
