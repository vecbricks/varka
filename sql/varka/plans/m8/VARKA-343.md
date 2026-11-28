# VARKA-343: The floating casts


## A. What is left of this row, and how it is built (28 November 2026)

*NEW SECTION, written on 28 November 2026 for builders that have only this file and the
repository's `AGENTS.md` files; revised the same day (the fix round): each part now has a build
sheet in the house form. Its code facts were checked on master `6dafb8bd2f6` (origin/master
`04bfdd3673d` differs from it by plan files only). No JVM was started for it; A.13 lists what
only a JVM settles. Sections 0 to 10 below are the row's record on master, unchanged; the
records of 343b2a and 343b2b as built on their branches are added at the end, marked. Every
build sheet says "Follow `BUILD_SHEETS.md`": its rules (the exports, the lock, the linters, the
local commit and where the builder stops) bind each part in full. Revised again in fix round 2
(28 November 2026, after review 1 and the owner's answers of that day): each changed place
says "Changed in fix round 2" or "fix round 2". Reworked the same day after the owner's
decisions E1 (a), E2 (c) and E3 (a): each changed place says "rework", "reworked" or "after E2
(c)" or "after E3 (a)"; no part of this row is full strength before January 2027 (A.8).*

### A.1 What this row is, and which done-when clause holds it

Row 343 of `m8/PLAN.md` is the floating casts. The close path (`m8/PLAN.md` 12.5, 27 November
2026) holds it under item 3 of 1.1: "Every one of the 106 legal pairs of `m8/casts.tsv` is
fused under LEGACY, ANSI and TRY where the mode admits it, or declined with its reason".

**Merged** (`git log origin/master --format='%h %cs %s' -E --grep='VARKA-343\b'`): the
baseline (#1242), 343a1 (#1311), 343a2 (#1317), 343a3 (#1328), 343a3b (#1338), 343a4 (#1385),
343a5 (#1393, #1401), 343b1's proofs (#1386) and forms (#1424, #1446, #1450). The row's line
in `m8/PLAN.md` still calls 343a4, 343a5 and 343b1 "built": it is stale.

**The row's cells of `m8/casts.tsv` with no fused mode today** (`awk -F'\t' '$10 ~ /343/ &&
$9 == ""' sql/varka/plans/m8/casts.tsv | wc -l` prints `14`: these 12 and the two identities,
`float float` and `double double`, which carry no fused cell by row 328's rule):

| pairs | why unfused today | part |
| :--- | :--- | :--- |
| `float`, `double` to `byte`, `short`, `int` (6) | no conversion to an integral lane is built | 343b2a2 |
| `float`, `double` to `bigint` (2) | the same; and below level 3 `D2L` has no form yet | 343b2a2, 343b2b (credited at emit level 2, 343L's rule) |
| `bigint` to `float`, `double` (2) | merged and fused in an x86 session, but the probe compiles at the unknown level, which declines | 343L (the probe compiles at emit level 2) |
| `date` to `float`, `double` (2) | always null; a kernel that reads no column is not compiled | declined with its reason (A.12, question 2) |

`fused_today` of `casts.tsv` is derived by `dev/varka_census.py` from `casts_fused.tsv`, which
`VarkaCastMatrixSuite` writes under `VARKA_CASTS_REGEN=true`; `dev/varka_regen_generated.sh
--all` runs both. In the census the cast aliases derive from the same file, so the same parts
move `tinyint`, `smallint`, `int` and `bigint` over `float` and `double`, and `float` and
`double` over `bigint`: 10 pairs. `time_from_seconds` over `float` and `double` (2 pairs) is
343b3's.

**Built and not merged**, on local branches of `/home/max/proj/vecbricks-varka`, all older than
row 489's `props()` descriptor (24 and 25 November) and none adding an IR node:

| branch | tip | base on master | behind `6dafb8bd2f6` | holds | counted lines |
| :--- | :--- | :--- | ---: | :--- | ---: |
| `varka-343b2a-r2` | `96047835ca4`, 23 Nov | `8e631d8ed41` | 468 | 343b2a: the casts to the four integral types in three modes, the floating `GuardedRange` | 982 |
| `varka-343b2b-r2` | `163aac5ecd6`, 23 Nov | `241125b2f37`; stacked on 343b2a at `fa1031645a7` | 474 | 343b2b: `D2L` and `F2L` below level 3 | 649 over `fa1031645a7` |
| `varka-343-conversions`, `varka-343b2b-avx2`, `varka-343b2b-avx2-r` | 20, 21 Nov | | 504 to 535 | older copies of the two | superseded |

`varka-343b2b-r2` lacks the last three commits of `varka-343b2a-r2` (`e821ad8dbf9`, a merge of
master; `fc67ccb67ae`, the coverage row `try_cast(f AS SMALLINT)`; `96047835ca4`, three
comments). 343b2a1 and 343b2a2 take their code from `git diff 8e631d8ed41 varka-343b2a-r2`;
343b2b from `git diff fa1031645a7 163aac5ecd6`.

**How the diffs meet master** (fix round, 28 November: computed, not assumed). A read-only
stand-in for `git apply --3way`, `scratch-343/merge3.py` of the planning folder, runs `git
merge-file --diff3` per file with master `6dafb8bd2f6` as ours, the branch's base as base and
its tip as theirs, stacked (343b2b over the result of 343b2a). Its conflicts, each resolved by
hand in the sheets below:

| diff | file | conflict |
| :--- | :--- | :--- |
| 343b2a | `VarkaVectorIR.java` | after `GuardedRange`'s second constructor master added `props()` (row 489) and the branch added `floating`, `loValue`, `hiValue`, `bitsOf`, `valueOf`: keep both, master's first |
| 343b2a | `VarkaBodyEmitter.java` | the narrow store's comment: master's names `narrowRowsInBody` (VARKA-575); written out in 343b2a2 |
| 343b2a | `m8/PLAN.md` | the row line: never taken from the branch |
| 343b2b | `VarkaEmitBudget.java` | master's `weightOf(VarkaVectorIR)` is `node.props().weight()`: the weight goes to `ConvertLane.props()` and the options overload (343b2b) |
| 343b2b | `VarkaEmitOptions.java` | the `@param wideConversionForm` javadoc (343b2b) |
| 343b2b | `m8/PLAN.md` | as above |

Every other file merges clean. Plain `git apply --check` (no 3-way) rejects more files
(`Analysis.java`, `VarkaKernelPlan.java`, `VarkaVectorIR.java` and the two queries suites for
343b2a) because their context moved; so the sheets say `git apply --3way`, which finds the
branch's base blobs in the shared object store. A clean merge is not proven to compile: no JVM
was run. Each part's first command is the compile.

### A.2 What a builder must know

- A cast from a floating type to an integral one under LEGACY is Java's cast (JLS 5.1.3): NaN
  to 0, saturation at the int or long range; `TINYINT` and `SMALLINT` are the int answer
  wrapped (`(byte) (int) v`). Under ANSI Spark raises `CAST_OVERFLOW` unless `Math.floor(v) <=
  MAX && Math.ceil(v) >= MIN` in double arithmetic (NaN fails); TRY answers null where ANSI
  raises.
- So each cast is `ConvertLane` over a `GuardedRange` of the values that pass that test, in
  `FAIL` (ANSI: the batch declines and Spark's row code raises) or `NULL` (TRY).
- A kernel never raises. A `FAIL` guard declines the batch (status `STATUS_VALUE_RANGE`, `1 <<
  5`, of `VarkaFusedKernel`) and the evaluator answers that batch on the row path.
- An emit level is the options' `useAVX`: 3 where the host has AVX512DQ (or is aarch64), 2
  below, and unknown (`levelUnknown()`) for the defaults. `F2I` and `D2I` are one instruction
  from AVX2 up. `F2L` and `D2L` are an instruction at level 3 only; below it they need a
  software form (343b2b).
- `-0.0` casts to 0. A NaN's payload never reaches an integral answer.
- A kernel has two bodies: the dense one (no nulls, reads no validity) and the masked one. The
  test helper `VarkaKernelChecks.runEachBodyMixedStatus` returns the status of the batch's own
  body and then of "the other body": for a batch with a null row the first is the masked body
  and the second the same values with every row valid. So a batch whose bad value sits on a
  null row answers `[0, STATUS_VALUE_RANGE]`.

### A.3 The admission check

The directories, as shell names every command of this file uses (paste them first):

    CG=sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen
    T=sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka
    S=sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka
    CS=sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen
    BJ=sql/core/src/test/java/org/apache/spark/sql/execution/benchmark
    BS=sql/core/src/test/scala/org/apache/spark/sql/execution/benchmark

Each command was run on `6dafb8bd2f6` and printed what is written after it.

1. `grep -c 'is not built yet (row 343)' $CG/varka/VarkaKernelPlan.java`: `1`, the refusal of
   every conversion to an integral lane but `D2I` (`conversionRefusal`).
2. `grep -c 'static GuardedRange floating\|emitFloatingRangeMask' $CG/varka/VarkaVectorIR.java
   $CG/varka/VarkaVectorWalk.java`: `0` for each file.
3. `grep -c 'record GuardedRange(VarkaVectorIR child, long lo, long hi, Overflow mode, int
   hash)' $CG/varka/VarkaVectorIR.java`: `1`.
4. `grep -c 'VarkaEmitOptions.DEFAULTS' $T/VarkaCastMatrixSuite.java`: `2`. The cast matrix
   compiles at the unknown emit level.
5. `grep -c '"timeFromSeconds" ->' $CG/VarkaTimeCompiler.java`: `1`; its body calls
   `timeFromUnits`, which takes a long operand only.
6. `grep -c 'case FLOAT, DOUBLE -> throw new Refused(node, "a floating lane' $T/VarkaSmtNodes.java`:
   `1`. The SMT translation already refuses a floating lane in `sortOf`, which its
   `GuardedRange` arm calls before it reads a bound; no new refusal is needed there.
7. `grep -n 'c.lane() == VarkaVectorIR.LaneType.INT' $CG/varka/Analysis.java` prints the line
   in `guardsStoredValues`: the stored-value guard, the only one kept as a lane-wise min and max
   (`accumulatesRange`, `Slots.rangeAcc`), is an `INT` column's, so a floating guard never takes
   it.
8. `grep -c 'Family("Floating casts", predicates = false' $S/VarkaCoverageSuite.scala`:
   `1`; the row `CAST((f > 1.5F AND i > 3) AS INT)` ends that family on master already.

What could not be checked: that the branches' code compiles and passes on master (A.13).

### A.4 The design

#### A.4.1 The floating range guard (343b2a1)

`GuardedRange(child, lo, hi, mode)` at a floating lane keeps each bound as the lane's own bits
in `lo` and `hi` (a float's sign-extended). New: `static GuardedRange floating(child, double
lo, double hi, Overflow mode)`, `double loValue()`, `double hiValue()`. The constructor refuses
a NaN bound, bounds out of order (`!(low <= high)`), and a `FLOAT` bound that a float does not
hold exactly. The mask of failing lanes is

    NOT (v >= lo AND v <= hi)                 under IEEE compares

so a NaN lane fails. The integral form, `v < lo OR v > hi`, would let NaN through; that is why
the floating arm is its own method, `VarkaVectorWalk.emitFloatingRangeMask`. Both modes reuse
the integral guard's tails (`emitGuardCollect` for `FAIL`, which ANDs the mask with the lanes'
validity and with the epilogue's bounds mask; the word cleared by `~mask.toLong()` for `NULL`).
The complement sets lanes past the batch's rows (an epilogue's lanes past its length, the upper
half of a 32-bit mask in a 64-bit kernel); the epilogue's bounds mask and the crossing to the
driving lane drop them, and 343b2a1's test `lanesPastTheRows` holds that.

**Every reader of `lo()` and `hi()` must not take floating bits for numbers.** The readers on
master (`grep -rn '\.lo()\|\.hi()' sql/catalyst/src/main/java sql/core/src/main/java`, and the
`GuardedRange` arms of the test helpers):

| reader | at a floating lane |
| :--- | :--- |
| `VarkaRangeAnalysis` (`case GuardedRange`) | answers `UNKNOWN` (343b2a1) |
| `Analysis` (the bounds-fit check, lanes other than long) | skips the check: the constructor checked (343b2a1) |
| `Analysis.guardsStoredValues`, `accumulatesRange`, `Slots.rangeAcc` | an `INT` column's guard only (A.3 item 7); unchanged |
| `Slots` (the NULL guard's scratch and word, the word demand) | read the mode and the child only; unchanged |
| `VarkaVectorWalk.emitValue`, `emitRangeGuard` | pass `lo`, `hi` to `emitRangeMask`, whose floating arm reads `loValue`, `hiValue` (343b2a1) |
| `VarkaVectorIR` (`withChildren`, the narrowing over a month-range `FAIL` guard) | rebuild through the canonical constructor; the month test is an `INT` guard's |
| the two canonical forms (`guardTag`) | print the bits; a reader parses them back through the canonical constructor |
| `VarkaReference.nulls` (called from `value` for `FLOAT`, `longValue` for `DOUBLE`) | compares by value (343b2a1) |
| `VarkaGuardSpecs.fires` (read by `VarkaCompiledCheck`) | compares by value (343b2a2, where the first compiled floating guard appears) |
| `VarkaSmtNodes`, `VarkaSmtSamples` | refused by `sortOf` (A.3 item 6); unchanged |

#### A.4.2 The bounds (343b2a2): `VarkaTypes.castBounds(source, target)`

Computed, never typed: start at the double just inside `MAX + 1` or `MIN - 1`, rounded to the
source type, and step to the last value that passes Spark's test. The values it must return
(computed by `scratch-343/check_conversions.py` of the planning folder from Spark's predicate;
hexadecimal as `Double.toHexString` prints):

| source | target | lowest admitted | highest admitted |
| :--- | :--- | :--- | :--- |
| `DOUBLE` | `TINYINT` | `-0x1.01fffffffffffp7` | `0x1.fffffffffffffp6` |
| `DOUBLE` | `SMALLINT` | `-0x1.0001fffffffffp15` | `0x1.fffffffffffffp14` |
| `DOUBLE` | `INT` | `-0x1.00000001fffffp31` | `0x1.fffffffffffffp30` |
| `DOUBLE` | `BIGINT` | `-0x1.0p63` | `0x1.0p63` |
| `FLOAT` | `TINYINT` | `-0x1.01fffep7` | `0x1.fffffep6` |
| `FLOAT` | `SMALLINT` | `-0x1.0001fep15` | `0x1.fffffep14` |
| `FLOAT` | `INT` | `-0x1.0p31` | `0x1.fffffep30` (2147483520) |
| `FLOAT` | `BIGINT` | `-0x1.0p63` | `0x1.0p63` |

`2^63` passes ANSI and saturates to `Long.MAX_VALUE`: Spark reads `Long.MaxValue` as the double
`2^63`. The compiler: `ConvertLane(value, INT or LONG)`, the value under
`GuardedRange.floating(value, b[0], b[1], mode)` unless the mode is LEGACY (`WRAP`), and a
narrow target as `NarrowLane(converted, target)`.

#### A.4.3 `D2L` below level 3 (343b2b): the integer assembled from the bits

No mask anywhere: C2 on x86 lowers no mask at two 64-bit lanes. `b` is the double's bits;
every line is one or two lanewise long operations; `>>>` is the logical shift.

    e    = ((b >>> 52) & 0x7FF) - 1075          the power of two of the significand's last bit
    m    = (b & (2^52 - 1)) | 2^52              the significand with its implicit bit
    neg  = -(e >>> 63)                          all ones where e < 0
    left = m << (e & ~neg)
    c    = -e & neg                             the right count, 0 where e >= 0
    c    = c | (-((-c + 63) >>> 63) & 63)       raised to 63 where it passes 63
    mag  = left ^ ((left ^ (m >>> c)) & neg)
    s    = -(b >>> 63)                          all ones where the double is negative
    r    = (mag ^ s) - s
    notNaN = ((bits(+Infinity) - (b & Long.MAX_VALUE)) >>> 63) - 1
    sat  = (Long.MAX_VALUE ^ s) & notNaN        MAX, MIN, or 0 for a NaN
    big  = -((10 - e) >>> 63)                   all ones where e > 10, so |x| >= 2^63
    out  = r ^ ((r ^ sat) & big)

The Vector API reads a shift count modulo 64, and `m < 2^53`, so any right count from 53 up
must give 0: hence the raise to 63. A zero or a subnormal has `e = -1075` and gives 0.

The reference arm, under `wideConversionForm=MAGIC`, behind a guard `|x| <= 2^51` on the child
that declines the batch (NaN and the infinities with it), with `M = 0x1.8p52` (*after E3 (a),
28 November 2026: built in January 2027 by 343b2m, after its proof 343b2p; until then `D2L`
takes the assembly under every arm*):

    r = (x + M) - M                             rint(x)
    l = bits(r + M) - bits(M)                   that integer as a long
    d = bits(x - r)
    nz     = -(((d & MAX) + MAX) >>> 63)        all ones where x - r is not zero
    differ = -((d ^ bits(x)) >>> 63)            all ones where its sign is not x's
    out = l - (((-(bits(x) >>> 63)) | 1) & nz & differ)      one toward zero where r passed x

`F2L` is `F2D`, exact, then the same form. Weights, in lane calls: the assembly 38, the magic
22, as the branch counted them; 343b2b pins the assembly's to the count the emitter writes (its
sheet), and 343b2m the magic's.

**Checked in Python** (`scratch-343/check_conversions.py`, both forms in 64-bit two's
complement against JLS 5.1.3): `floating casts: 8 bound pairs equal VARKA-343.md 2.4; the
assembly equals (long) x on 1516527 doubles (every exponent, 1500000 random patterns) and
400000 floats; the truncated magic equals it on the 793927 of them inside 2^51;
time_from_seconds' one guard equals Spark's two raises on 1200016 values; 0 mismatches`.
Where Python stands in for the JVM: the integer steps (exact), and `x + M`, `x - r`, which are
IEEE binary64 round-to-nearest in both. Where it does not: A.13.

#### A.4.4 `time_from_seconds` over a floating value (343b3)

    s = x, or ConvertLane(f, DOUBLE)
    p = IntArith(MUL, WRAP, s, doubleSlot(1e9))
    g = GuardedRange.floating(p, Math.nextUp(-1.0), Math.nextDown(8.64e13), FAIL)
    n = ConvertLane(g, LONG)

Spark raises on NaN, on an infinity, and unless `0 <= (long) p < 86,400,000,000,000`, in every
mode; `(long) p` is in that range exactly when `-1 < p < 8.64e13`, so the one guard is both
raises (checked above on 1200016 values). Cells: `-1e-9` raises; its `nextUp` gives 0;
`86399.99999999999` gives 86399999999999; 86400 raises; the float below 86400 gives
86399992187500; `-0.0` gives 0.

#### A.4.5 The unknown emit level and the records (343L)

*Rewritten after the owner's decision E2 (c) of 28 November 2026.* At the unknown emit level,
the defaults' and that of a session on a host it cannot read, `VarkaKernelPlan.conversionRefusal`
declines a `BIGINT` conversion to `FLOAT` or `DOUBLE` (and, after 343b2b, a floating one to
`BIGINT`), and that stays. What changes is where the records read: `VarkaCastMatrixSuite`, the
coverage suite and every suite that writes or holds a record crediting a fused form compile at
`VarkaMatrixRuns.CREDIT`, the defaults at emit level 2, the level an x86 session without
AVX512DQ resolves to at a 256-bit species (`VarkaSessionEmit.FALLBACK_LEVEL`). A cell of
`casts_fused.tsv`, a coverage row and a census claim then mean "fused on an x86 host at that
level". Production does not change. The known limit: a session keeps the unknown level, and
declines these conversions, on a host whose level it cannot detect (another architecture, an
unread feature list, an x86 species under 256 bits without AVX512DQ) and wherever the level is
set to -1, as the test sessions set it; an explicit `spark.sql.codegen.varka.emit.useAVX=2`
lifts it (343L's sheet, its first list).

### A.5 The parts

Order: 343L, 343b2a1, 343b2a2, 343b2b, 343b3, 343r; 343t at any time; in January 2027,
343b2p then 343b2m. The reason: the guard is under the casts, the casts under the AVX2 `D2L`,
and `time_from_seconds` needs `D2L` at every named level; 343L first so that each later part
adds its `BIGINT` coverage rows once, credited at emit level 2.

*Reworked on 28 November 2026 after the owner's decisions E1 (a), E2 (c) and E3 (a):* 343L is
a Sonnet-class part that changes no production code (A.4.5); 343b2b builds the assembly alone;
the truncated magic (343b2m) and its proof (343b2p) are January 2027 parts at full strength,
not gates before then. No part of this row is full strength before January. Every sheet that
depends on 343L has the premise `grep -c 'public static final VarkaEmitOptions CREDIT ='
$T/VarkaMatrixRuns.java` printing `1`; if it prints `0`, 343L has not merged: stop and report,
do not adapt the sheet. The fix-round-2 paragraph below is kept as the record; its premise
(`softwareLongConversions()`) no longer exists in any sheet.

*Changed in fix round 2 (review 1, finding 3; the owner's answer to question 1, 28 November
2026: build 343L). Superseded in its builder and premise by the rework paragraph above; kept
as the record.* The order is fixed, and every sheet below is written for it, with ONE text:
343L merges first (full strength, built by the lead before 1 December), so 343b2a2, 343b2b,
343b3 and row 342's 342e and 342q are written for a master that has 343L. 343b2a1, 343b2p and
343t do not depend on it and may start before it merges. Each sheet that depends on it has the
premise `grep -c 'public boolean softwareLongConversions()' $CG/varka/VarkaEmitOptions.java`
printing `1`; if it prints `0`, 343L has not merged: stop and report, do not adapt the sheet.
343L's sheet ends with the exact state it leaves (its hand-off), which the later sheets are
written against. The earlier text of this paragraph ("343b2a1 and 343b2a2 do not wait on 343L:
without it their sheets hold as written") was wrong: with 343L merged, 343b2a2's counts and
its `conversionRefusal` hunk differ (review 1, F3), and there was no variant for that.

| part | title | size, counted | builder | starts from | waits on |
| :--- | :--- | ---: | :--- | :--- | :--- |
| 343L | `[VARKA-343] Credit the cast matrix and the coverage table at an x86 session's emit level` | 190 | Sonnet-class | master | nothing (Q1 answered (c), 28 November 2026) |
| 343b2a1 | `[VARKA-343] Guard a floating value's range, NaN outside` | 380 | Sonnet-class | master; code written out below and on `varka-343b2a-r2` | nothing |
| 343b2a2 | `[VARKA-343] Cast FLOAT and DOUBLE to the integral types in three modes` | 780 | Sonnet-class | master after 343b2a1 and 343L; `varka-343b2a-r2` | 343b2a1, 343L |
| 343b2b | `[VARKA-343] Convert DOUBLE and FLOAT to BIGINT on AVX2 by the integer assembled from the bits` | 560 | Sonnet-class | `varka-343b2b-r2` | 343b2a2 (so 343L) |
| 343b3 | `[VARKA-343] Fuse time_from_seconds over a floating value` | 330 | Sonnet-class | master | 343b2b |
| 343t | `[VARKA-343] Exhaust the split and the rounding to odd at half width` | 200 | Sonnet-class | master | nothing |
| 343r | `[VARKA-343] Results: the floating casts on their kernels` | 40 | Sonnet-class | master | 343b3 |
| 343b2p | `[VARKA-343] Prove the truncated magic D2L inside its window` | 150 | January 2027, full strength; not a gate before then | master | nothing |
| 343b2m | `[VARKA-343] Take D2L and F2L by the truncated magic under wideConversionForm=MAGIC` | 150 | January 2027, full strength; not a gate before then | `varka-343b2b-r2` | 343b2b, 343b2p |

The sizes of 343b2a1 and 343b2a2 are the branch's per-file counts (`git diff --numstat
8e631d8ed41 varka-343b2a-r2`) split by hunk, plus the new guard suite and a short outcome
note; the branch's own 77-line record is already in this file and is not copied again.

#### A.5.0 What every sheet of this row shares

`CG`, `T` as in A.3; `S` is
`$CS/varka`. A JUnit
class run alone prints sbt's `Passed: Total N, Failed 0, Errors 0, Passed N`; a ScalaTest
suite prints `All tests passed.`; a run of several prints both kinds, and "no test fails"
means no `FAILED`, `*** FAILED ***` or `Failed: Total` line and sbt's last line `[success]`.

**Where the code comes from** (fix round 2, review 1 findings 4 and 9). A "branch's lines N to
M" of a file is `git show <commit>:<path> | sed -n 'N,Mp'`, at the commit the sheet names. The
commits are on local branches of `/home/max/proj/vecbricks-varka` and, since 28 November 2026,
on the fork `MaxGekk/spark` (remote `fork` there) under the same names: `varka-343b2a-r2`
(`96047835ca4`), `varka-343b2b-r2` (`163aac5ecd6`), `varka-342de-r` (`688414d4e10`), and
`varka-339-ntz-arith-laptop` (`6476788b31a`; the fork's own `varka-339-ntz-arith` is another,
older history: never use it). Each sheet's B0 runs `git cat-file -t <commit>`; if it does not
print `commit`, run `git fetch fork <fork branch name>` once (the one state-changing command a
sheet allows for this), then `git cat-file -t <commit>` again; if it still fails, stop. A file
named `scratch-*/...` "of the planning folder" is a copy of `git diff` output kept beside the
plan's drafts; a builder may not have it: the git command the sheet gives beside it is the
source. The shell names `$BJ` and `$BS` are A.3's; `sql/core`'s own test directory is
`sql/core/src/test/scala/org/apache/spark/sql/execution/` (`VarkaFloatingLaneSuite.scala`).

**The bytes check**, run from the worktree root after a regeneration and before the commit (it
reads git only):

    python3 - <<'EOF'
    import json, subprocess
    old = json.loads(subprocess.run(['git', 'show', 'HEAD:sql/varka/emitted_bytes.json'],
                                    capture_output=True, text=True, check=True).stdout)
    new = json.load(open('sql/varka/emitted_bytes.json'))
    moved = [(w, s, k) for w in old['lanes'] for s in old['lanes'][w] for k in old['lanes'][w][s]
             if new['lanes'].get(w, {}).get(s, {}).get(k) != old['lanes'][w][s][k]]
    added = sum(1 for w in new['lanes'] for s in new['lanes'][w] for k in new['lanes'][w][s]
                if k not in old['lanes'].get(w, {}).get(s, {}))
    print(f'bytes: {len(moved)} committed shapes moved, {added} shapes added')
    for m in moved[:10]:
        print('moved:', m)
    EOF

`option_arms` is left out on purpose: each of its digests runs over every coverage row, so it
moves whenever a row is added.

**The results procedure** (343r, 342r and 339r; the lead dispatches unless the builder has
`gh` rights), for a class `<C>` whose files are under `<dir>` (`sql/core/benchmarks` or
`sql/catalyst/benchmarks`):

1. Open the pull request with the plan lines only (the predictions restated as "registered"),
   so that the runs have a pull request number `<n>`.
2. Dispatch at both widths:

        gh workflow run benchmark.yml -R vecbricks/varka -f pr=<n> -f jdk=25 -f class=<C> -f expected-cpu="AMD EPYC 7763"
        gh workflow run benchmark.yml -R vecbricks/varka -f pr=<n> -f jdk=25 -f class=<C> -f expected-cpu="AMD EPYC 7763" -f extra-java-options="-XX:MaxVectorSize=16"

   A run on another CPU fails at "Probe the machine's CPU and vector datapath" with `CPU
   mismatch!`; `varka-bench-cpu-retry.yml` reruns it an hour later, up to six attempts. Do not
   dispatch again before it reports "gave up: attempt 6 of 6 is the cap".
3. For each green run, into a new empty directory outside the worktree: `gh run download
   <run> -R vecbricks/varka -n benchmark-results-1-<C>-25-2.13-1 -D <tmp>`, `tar -xf
   <tmp>/benchmark-results-25-2.13.tar -C <tmp>`. Read `<C>-jdk25-provenance.txt` first: its
   `cpu:` line holds `AMD EPYC 7763`, its `inputs:` line ends `extra-java-options=<none>` or
   `...-XX:MaxVectorSize=16`. Copy the two files over `<dir>/<C>-jdk25-runner-epyc7763-results.txt`
   and `-provenance.txt` (256 bits), or `<dir>/<C>-jdk25-runner-epyc7763-128bit-results.txt` and
   `-128bit-provenance.txt` (128 bits). `git status --short <dir>` then lists exactly four
   modified files for the class.
4. Commit them, post each run's link on the pull request, and diff each against master:
   `python3 dev/varka_bench_diff.py --git master <dir>/<file> --control "spark row path"`.
   Score each prediction as right, wrong or right in part, with the numbers, in the row's
   section 10.

#### Part 343b2a1: the floating range guard (build sheet, fix round)

- **Title:** `[VARKA-343] Guard a floating value's range, NaN outside`. **Branch:**
  `varka-343b2a1-guard` from master (`git branch --list 'varka-343b2a1*'` prints nothing on
  28 November). **Builder:** Sonnet-class. **Size:** about 380 counted lines. Follow
  `BUILD_SHEETS.md`.
- **What it adds:** the floating `GuardedRange` at the IR, the emitter, the range analysis and
  the reference evaluator, with no compiler arm. No SQL fuses because of it; no generated file
  moves.

**B0, the premises** (from the worktree root; `CG` and `T` as in A.3). Each must print what is
written, or stop.

1. `grep -c 'static GuardedRange floating\|emitFloatingRangeMask' $CG/varka/VarkaVectorIR.java
   $CG/varka/VarkaVectorWalk.java`: `...VarkaVectorIR.java:0` and `...VarkaVectorWalk.java:0`.
2. `grep -c 'record GuardedRange(VarkaVectorIR child, long lo, long hi, Overflow mode, int
   hash)' $CG/varka/VarkaVectorIR.java`: `1`.
3. `grep -c 'private static void emitRangeMask(CodeBuilder cb, VarkaVectorIR node, int guardTmp,'
   $CG/varka/VarkaVectorWalk.java`: `1`.
4. `grep -c 'case GuardedRange n -> VarkaValueRange.of(n.lo(), n.hi());'
   $CG/varka/VarkaRangeAnalysis.java`: `1`.
5. `grep -c 'if (n.laneType() != VarkaVectorIR.LaneType.LONG) {' $CG/varka/Analysis.java`: `1`.
6. `grep -c 'private static boolean nulls(GuardedRange g, long v)' $T/VarkaReference.java`: `1`.
7. `ls $T/VarkaEmitterFloatingGuardSuite.java` fails (no such file).
8. `git cat-file -t 96047835ca4` prints `commit` (the branch's tip is in the object store).
   If it does not, stop: the helpers `batch`, `check` and `outward` are quoted from it.

**Steps.** Edit in this order. Every hunk below is the branch's (`git show
varka-343b2a-r2:<path>`, the lines given), written in master's names.

1. `$CG/varka/VarkaVectorIR.java`, record `GuardedRange`:
   - In the javadoc above the record, after the sentence that ends "The three-argument form is
     {@code FAIL}.", add the paragraph of the branch's lines 914 to 919 (`<p>At a floating lane
     the bounds are the lane's own bits, ...`).
   - The canonical constructor's first test becomes (branch lines 924 to 931):

         if (child.laneType().isFloating()) {
           double low = valueOf(child.laneType(), lo);
           double high = valueOf(child.laneType(), hi);
           if (Double.isNaN(low) || Double.isNaN(high) || !(low <= high)) {
             throw VarkaErrors.illegalArgument("a floating range guard takes two ordered values, "
                 + "not [" + low + ", " + high + "]");
           }
         } else if (lo > hi) {

     and the rest of the constructor stays as it is.
   - After master's `props()` method of the record (the one whose comment begins "A FAIL guard
     forwards its child's word"), add the five members of the branch's lines 952 to 1002:
     `floating(VarkaVectorIR child, double lo, double hi, Overflow mode)`, `loValue()`,
     `hiValue()`, `private static long bitsOf(LaneType lane, double v)` and `private static
     double valueOf(LaneType lane, long bits)`, word for word. Master's `props()` stays first.
     Do not take the branch's `ConvertLane` javadoc hunk (its lines 1106 to 1108): it is
     343b2a2's.
2. `$CG/varka/Analysis.java`, the `case GuardedRange n` of the bounds-fit check (the block whose
   comment begins "At a narrow or the int lane the bounds have to be"): the branch's lines 1763
   to 1765. The comment's last sentence becomes "...which would narrow a bound silently. A
   floating lane's bounds are its own values, which the node's constructor checks." and the
   test `if (n.laneType() != VarkaVectorIR.LaneType.LONG && !n.laneType().isFloating()) {`.
   Do not take the branch's two hunks above it (lines 1668 and 1681, `narrowsAnIntAtRoot`):
   they are 343b2a2's.
3. `$CG/varka/VarkaRangeAnalysis.java`: before `case GuardedRange n -> VarkaValueRange.of(n.lo(),
   n.hi());` add

         // At a floating lane the bounds are IEEE bits, which bound no integer.
         case GuardedRange n when n.laneType().isFloating() -> VarkaValueRange.UNKNOWN;

4. `$CG/varka/VarkaVectorWalk.java`: at the top of `emitRangeMask`, after `Lane lane =
   guardLane(node, analysis);`, add

         if (lane.laneType.isFloating()) {
           emitFloatingRangeMask(cb, (GuardedRange) node, guardTmp, lane);
           return;
         }

   and after `emitRangeMask` add the method of the branch's lines 1701 to 1728, word for word:

         private static void emitFloatingRangeMask(CodeBuilder cb, GuardedRange node, int guardTmp,
             Lane lane) {
           cb.dup();
           cb.astore(guardTmp);
           for (int k = 0; k < 2; k++) {
             cb.aload(guardTmp);
             cb.getstatic(VECTOR_OPERATORS, k == 0 ? "GE" : "LE", VO_COMPARISON);
             double bound = k == 0 ? node.loValue() : node.hiValue();
             if (lane.laneType == VarkaVectorIR.LaneType.FLOAT) {
               cb.loadConstant((float) bound);
             } else {
               cb.loadConstant(bound);
             }
             cb.invokevirtual(lane.vector, "compare", lane.compareVI);
           }
           cb.invokevirtual(VECTOR_MASK, "and", MASK_BINARY);
           cb.invokevirtual(VECTOR_MASK, "not", MASK_UNARY);
         }

   with its javadoc (the branch's lines 1701 to 1709). Every name it uses is on master
   (`VarkaDescriptors.MASK_UNARY`, `MASK_BINARY`, `VO_COMPARISON`, `VECTOR_OPERATORS`,
   `VECTOR_MASK`; `Lane.compareVI`, whose scalar is the lane's own type). Do not take the
   branch's `emitShapeConversion` hunks (lines 855 to 882): they are 343b2a2's. `emitRangeMask`
   is reached only with a `GuardedRange` at a floating lane in this part; 343b2b changes the
   cast when it adds a `ConvertLane` caller.
5. `$T/VarkaReference.java`: replace `nulls(GuardedRange g, long v)` and its one-line javadoc by
   the branch's lines 363 to 384: `nulls` calls a new package-private `static boolean
   outside(GuardedRange g, long v)`, which switches on `g.laneType()`: `FLOAT` reads
   `Float.intBitsToFloat((int) v)` and answers `!(f >= g.loValue() && f <= g.hiValue())`,
   `DOUBLE` the same over `Double.longBitsToDouble(v)`, and `BYTE, SHORT, INT, LONG` answer
   `v < g.lo() || v > g.hi()`. Its two callers, in `value` and `longValue`, stay as they are.
6. `$T/VarkaEmitterFloatingGuardSuite.java`, new, Java, JUnit 5, `@VarkaSuite(ownJvm = true)`,
   the license header copied from a neighbour. Its full text is in "The test" below.
7. `sql/varka/plans/m8/PLAN.md`, row 343's line: after `**Started** (` insert `*343b2a1 built
   <date>, \`VARKA-343.md\` A.5: the floating range guard, NaN outside, at the IR and the
   emitter;* `. Nothing else of the line moves.
8. `sql/varka/plans/m8/VARKA-343.md`, section 10: a subsection `### 343b2a1 as built (<date>)`
   of at most 15 lines: what was built, the suite's result, and A.7's predictions for this part
   scored.

**The test.** `VarkaEmitterFloatingGuardSuite`, six tests. Its helpers `batch` and `check` are
the branch suite's (`git show varka-343b2a-r2:$T/VarkaEmitterFloatToIntegralSuite.java`, lines
115 to 124 and 126 to 154), copied unchanged; `outward` is its lines 108 to 113.

    package org.apache.spark.sql.catalyst.expressions.codegen.varka;

    import static org.junit.jupiter.api.Assertions.assertEquals;
    import static org.junit.jupiter.api.Assertions.assertThrows;
    import static org.junit.jupiter.api.Assertions.assertTrue;

    import java.util.List;
    import java.util.Optional;
    import java.util.function.IntPredicate;
    import org.junit.jupiter.api.DisplayName;
    import org.junit.jupiter.api.Test;
    import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
    import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ConvertLane;
    import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.GuardedRange;
    import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
    import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;

    /**
     * The floating range guard (VARKA-343, part 343b2a1, `VARKA-343.md` A.4.1): a GuardedRange
     * over a FLOAT or DOUBLE value whose bounds are the lane's own values and whose mask is the
     * complement of v >= lo AND v <= hi, so that a NaN lane is outside. FAIL declines a batch
     * with a live lane outside, NULL nulls the lane. Each kernel is held to the reference at
     * every width, length, null pattern and both bodies, over a float kernel, a double kernel
     * and a float guarded in a double kernel (F2D), whose 32-bit mask has twice the drive's
     * lanes.
     */
    @VarkaSuite(ownJvm = true)
    public final class VarkaEmitterFloatingGuardSuite {

      private static final ColumnRef F = new ColumnRef(0, LaneType.FLOAT);
      private static final ColumnRef X = new ColumnRef(0, LaneType.DOUBLE);

      private static final int[] WIDE_LANES = {2, 4, 8};
      private static final int[] NARROW_LANES = {4, 8, 16};
      private static final int[] LENGTHS = {1, 2, 7, 8, 9, 16, 17, 33, 129};
      private static final List<IntPredicate> PATTERNS = List.of(
          i -> false, i -> i % 5 == 0, i -> i % 2 == 1, i -> true);

      private static final VarkaEmitOptions DQ =
          VarkaMatrixRuns.BASE.withUseAVX(VarkaEmitOptions.AVX512_LEVEL);

      /** Bounds a float holds exactly; the upper one is 2.4's FLOAT to INT bound. */
      private static final double LO = -1.5;
      private static final double HI = 0x1.fffffep30;   // 2147483520

      // outward(LaneType, double, boolean): the branch suite's lines 108 to 113.
      // batch(int, int, long[]): its lines 115 to 124.
      // check(String, VarkaVectorIR, long[], VarkaEmitOptions, boolean): its lines 126 to 154.

      private static long bitsOf(LaneType source, double v) {
        return source == LaneType.FLOAT ? Float.floatToRawIntBits((float) v)
            : Double.doubleToRawLongBits(v);
      }

      /** NaN, the infinities, both zeros, each bound and its outer neighbour, inside, past. */
      private static long[] cells(LaneType source) {
        double[] values = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0,
            -0.0, LO, HI, outward(source, LO, false), outward(source, HI, true), 1.0, -1.0, 0.5,
            1e30, -1e30, 3e9, -3e9};
        long[] bits = new long[values.length];
        for (int k = 0; k < bits.length; k++) {
          bits[k] = bitsOf(source, values[k]);
        }
        return bits;
      }

      /** The three kernels: the guard alone over F and over X, and F's guard widened by F2D. */
      private static List<VarkaVectorIR> roots(double lo, double hi, Overflow mode) {
        return List.of(GuardedRange.floating(F, lo, hi, mode),
            GuardedRange.floating(X, lo, hi, mode),
            new ConvertLane(GuardedRange.floating(F, lo, hi, mode), LaneType.DOUBLE));
      }

      private static LaneType sourceOf(VarkaVectorIR root) {
        return root instanceof ConvertLane ? LaneType.FLOAT : root.laneType();
      }

      @Test
      @DisplayName("a floating guard refuses a NaN bound, unordered bounds and a bound no float "
          + "holds")
      void guardRefusals() {
        // The branch suite's lines 255 to 265, unchanged, then:
        assertThrows(IllegalArgumentException.class,
            () -> GuardedRange.floating(new ColumnRef(0, LaneType.INT), 0.0, 1.0, Overflow.FAIL));
        var g = GuardedRange.floating(X, LO, HI, Overflow.NULL);
        assertEquals(g, new GuardedRange(X, g.lo(), g.hi(), Overflow.NULL));
      }

      @Test
      @DisplayName("NULL: a lane outside the bounds or NaN is null, the rest its own value")
      void nullGuard() {
        for (VarkaVectorIR root : roots(LO, HI, Overflow.NULL)) {
          check("NULL " + VarkaVectorIR.canonical(root), root, cells(sourceOf(root)),
              VarkaMatrixRuns.BASE, false);
        }
      }

      @Test
      @DisplayName("FAIL: a batch with a live lane outside the bounds or NaN declines, the "
          + "others answer")
      void failGuard() {
        for (VarkaVectorIR root : roots(LO, HI, Overflow.FAIL)) {
          check("FAIL " + VarkaVectorIR.canonical(root), root, cells(sourceOf(root)),
              VarkaMatrixRuns.BASE, true);
        }
      }

      @Test
      @DisplayName("FAIL: a live NaN, infinity or outer neighbour at row 2, 5 or 16 declines "
          + "both bodies, a null one the other body alone")
      void failDeclinesAtRow() {
        int range = VarkaFusedKernel.STATUS_VALUE_RANGE;
        for (VarkaVectorIR root : roots(LO, HI, Overflow.FAIL)) {
          LaneType source = sourceOf(root);
          var roots = List.of(root);
          var emitted = VarkaEmitterFixtures.emit(roots, 1, 0,
              VarkaMatrixRuns.BASE.withLanesOverride(4));
          double[] outside = {outward(source, LO, false), outward(source, HI, true), Double.NaN,
              Double.POSITIVE_INFINITY};
          for (double bad : outside) {
            for (int at : new int[] {2, 5, 16}) {
              var values = new long[1][17];
              for (int i = 0; i < 17; i++) {
                values[0][i] = bitsOf(source, i == at ? bad : (i % 2 == 0 ? LO : HI));
              }
              var nulls = new boolean[1][17];
              var batch = new VarkaKernelChecks.LongBatch(17, nulls, values, false);
              String ctx = VarkaVectorIR.canonical(root) + ", " + bad + " at row " + at;
              assertEquals(List.of(range, range), VarkaKernelChecks.runEachBodyMixedStatus(ctx,
                  emitted.className(), emitted.bytes(), roots, 1, new int[0], new long[0],
                  batch), ctx);
              nulls[0][at] = true;
              assertEquals(List.of(0, range), VarkaKernelChecks.runEachBodyMixedStatus(
                  ctx + ", null", emitted.className(), emitted.bytes(), roots, 1, new int[0],
                  new long[0], batch), ctx + ", null");
            }
          }
        }
      }

      @Test
      @DisplayName("a guard whose bounds hold no zero declines and nulls no row inside it, at "
          + "any length: the lanes past the rows are not read")
      void lanesPastTheRows() {
        for (Overflow mode : List.of(Overflow.FAIL, Overflow.NULL)) {
          for (VarkaVectorIR root : roots(1.0, 2.0, mode)) {
            LaneType source = sourceOf(root);
            long[] inside = {bitsOf(source, 1.0), bitsOf(source, 1.5), bitsOf(source, 2.0),
                bitsOf(source, outward(source, 2.0, false))};
            check(mode + " " + VarkaVectorIR.canonical(root) + " over [1, 2]", root, inside,
                VarkaMatrixRuns.BASE, false);
          }
        }
      }

      @Test
      @DisplayName("the plan admits a floating guard at every emit level")
      void levels() {
        for (VarkaEmitOptions options : List.of(VarkaEmitOptions.DEFAULTS, VarkaMatrixRuns.BASE,
            VarkaMatrixRuns.BASE.withUseAVX(2), DQ)) {
          for (Overflow mode : List.of(Overflow.FAIL, Overflow.NULL)) {
            for (VarkaVectorIR root : roots(LO, HI, mode)) {
              Optional<String> refusal = VarkaKernelPlan.refusalOf(List.of(root), options);
              assertTrue(refusal.isEmpty(), VarkaVectorIR.canonical(root) + " at "
                  + options.useAVX() + ": " + refusal);
            }
          }
        }
      }
    }

What each test catches that no other does: `guardRefusals`, a bound check dropped from the
constructor or a `FLOAT` bound rounded (then a guard moves). `nullGuard`, the integral compare
kept at a floating lane (a NaN would pass and stay non-null) and a reference that compares
bits. `failGuard`, a decline over a batch whose live rows are all inside. `failDeclinesAtRow`,
a mask that misses a lane at the start, in the middle or in the epilogue, or a masked body that
ignores validity. `lanesPastTheRows`, the complement's set lanes past the rows (the epilogue's
lanes past the length, or the upper half of the float mask in the `F2D` kernel) reaching the
decline or the word: with bounds `[1, 2]` a zero lane is outside, so a leak declines a batch
of rows that are all inside. `levels`, a plan rule that refuses the guard at some level.

**Commands, in order** (with the exports and the lock of `BUILD_SHEETS.md` 2 and 3):

1. `flock "$VARKA_HEAVY_LOCK" build/sbt catalyst/Test/compile`: ends `[success]`.
2. `flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaEmitterFloatingGuardSuite'`:
   `Passed: Total 6, Failed 0, Errors 0, Passed 6`.
3. `flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaRangeAnalysisSuite
   *VarkaIrFuzzSuite *VarkaEmitterLongLaneSuite *VarkaEmitterFloatingCastSuite'`: no test
   fails (the ScalaTest suites print `All tests passed.`; the JUnit one adds to sbt's `Passed:
   Total` line with `Failed 0, Errors 0`).
4. `flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaEmittedBytesSuite'`: passes
   (`All tests passed.`); then `git diff --exit-code -- sql/varka/emitted_bytes.json
   sql/varka/coverage.json` exits 0. No committed shape holds a floating guard, so no byte
   moves.
5. `BUILD_SHEETS.md` 6: the linters, then the commit, then `python3 dev/varka_push_check.py`,
   which prints the size (under 570, the estimate and a half) and exits 0.

**Planted faults** (after the commit; each reverted with `git checkout -- <file>`): (a) in
`emitFloatingRangeMask` delete the line `cb.invokevirtual(VECTOR_MASK, "not", MASK_UNARY);`:
command 2 fails in `nullGuard` (inside lanes nulled) and `failDeclinesAtRow` (a batch whose
bad row is null still declines in the masked body). `failGuard` may pass: an all-null batch
answers either way. (b) In
`VarkaReference.outside`, answer `v < g.lo() || v > g.hi()` for `DOUBLE` too: `nullGuard`
fails (a negative double's bits order backwards). (c) In `emitRangeMask` call the integral
compares at a floating lane (delete the `if (lane.laneType.isFloating())` block): the emitter
compares a double against `lo` and `hi` read as integers; `nullGuard` fails.

**Done when** commands 1 to 5 print what they say and the three faults fail as said.

**Not here:** any compiler arm or decline text; `conversionRefusal`; `VarkaGuardSpecs`; the IR
grammar; any coverage row or benchmark section; the `ConvertLane` javadoc. **Stop** on any
item of `BUILD_SHEETS.md` 8, and if command 4 moves a byte.

#### Part 343b2a2: the casts in three modes (build sheet, fix round)

- **Title:** `[VARKA-343] Cast FLOAT and DOUBLE to the integral types in three modes`.
  **Branch:** `varka-343b2a2-casts` from master after 343b2a1 and 343L merged. **Builder:**
  Sonnet-class. **Size:** about 780 counted lines (the suite is 402 of them). Follow
  `BUILD_SHEETS.md`. If `python3 dev/varka_push_check.py` reports between 901 and 999 counted
  lines, report the count in the outcome and go on; do not trim tests to get under 900 (fix
  round 2, review 1 finding 11). From 1000 up, stop. *Changed in fix round 2 (finding 3): the
  sheet is written for a master with 343L merged (A.5); B0 item 8 checks it. Reworked on 28
  November 2026 after E2 (c): 343L no longer touches `conversionRefusal` or
  `VarkaEmitterFloatingCastSuite`, so the branch's hunks of both apply as they are (step 1),
  and step 1d extends 343L's `fusedIn`.*
- **What it adds:** `FLOAT` and `DOUBLE` to `TINYINT`, `SMALLINT`, `INT` in LEGACY, ANSI and
  TRY at every emit level, and to `BIGINT` at level 3 (below it 343b2b); a narrow target stored
  from a double kernel; eight coverage rows; the fuzz grammar's draw; the baselines' fuse lists.

**B0, the premises.**

1. 343b2a1 is merged: `grep -c 'static GuardedRange floating' $CG/varka/VarkaVectorIR.java`
   prints `1`, and `ls $T/VarkaEmitterFloatingGuardSuite.java` lists the file.
2. `grep -c 'is not built yet (row 343)' $CG/varka/VarkaKernelPlan.java`: `1`.
3. `grep -c 'boolean wholeFits = analysis.lane != Lane.LONG' $CG/varka/VarkaBodyEmitter.java`:
   `1`.
4. `grep -c 'return lane == Lane.LONG && mixed && wholeNarrowStoreFits(stored, lanes);'
   $CG/varka/VarkaKernelPlan.java`: `1`.
5. `grep -c '"a cast from a floating type (row 343' $CG/VarkaNodeCompiler.java`: `1`, and
   `grep -rn "row 343's second part" sql/core/src/test sql/catalyst/src/test` prints one line,
   `VarkaFloatingLaneSuite.scala`'s assertion.
6. `awk -F'\t' '$10 ~ /343/ && $9 == ""' sql/varka/plans/m8/casts.tsv | wc -l`: `12` (master
   before 343L prints `14`; 343L credits `bigint` to `float` and `double` at emit level 2).
7. `git cat-file -t 96047835ca4`: `commit`. If not, A.5.0 "Where the code comes from" (the
   fork's `varka-343b2a-r2`); if still not, stop: this part's suite and baselines are quoted
   from the branch, not written out.
8. 343L is merged: `grep -c 'public static final VarkaEmitOptions CREDIT ='
   $T/VarkaMatrixRuns.java`: `1`; and it left production alone: `grep -c 'at an unknown emit
   level, which may be a host' $CG/varka/VarkaKernelPlan.java`: `1`. If either differs, stop
   (A.5).

**Steps.**

1. Apply the branch's diff for these files with `git diff 8e631d8ed41 96047835ca4 -- <files> |
   git apply --3way`, one file at a time, and check each against the list below:
   - `$CG/VarkaFloatingCastCompiler.java` (whole: the imports, the class javadoc, the
     `integralTarget` branch of `compile`, `admits`, `integralTarget`, `toIntegral`);
   - `$CG/VarkaNodeCompiler.java` (whole: `floatingDecline`'s floating-source text becomes
     `"a cast from a floating type to " + c.dataType().simpleString() + ", which no floating
     lowering serves"`);
   - `$CG/varka/VarkaKernelPlan.java` (whole: `narrowRefusal`'s double-drive test admits
     `narrowsAnIntAtRoot(root)`; the new `narrowsAnIntAtRoot`; `wholeNarrowStoreAtLong` tests
     `lane.bits == Long.SIZE`; `conversionRefusal`'s javadoc and its first arm, step 1a);
   - `$T/VarkaEmitterFloatingCastSuite.java` (whole: step 1b);
   - `$CG/varka/VarkaTypes.java` (whole: `castBounds`, `passes`, `edge`, `step`);
   - `$T/VarkaIrGrammar.java`, `$S/VarkaIrFuzzSuite.scala`,
     `$T/VarkaGuardSpecs.java`, `$S/VarkaCoverageSuite.scala`;
   - the five `sql/core` files: `$BJ/VarkaFloatingBaselineBenchmark.java`,
     `$BJ/VarkaFloatingCastBaselineBenchmark.java`,
     `sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaFloatingLaneSuite.scala`,
     `$BS/VarkaFloatingBaselineQueriesSuite.scala`,
     `$BS/VarkaFloatingCastBaselineQueriesSuite.scala`.

   If a 3-way apply leaves a conflict, stop and report it: none was found against
   `6dafb8bd2f6` (A.1's table), and 343L, under E2 (c), edits none of these files but
   `VarkaCoverageSuite.scala` (two rows and a comment in the family "Floating casts", away
   from this part's hunk, which reads only the family's last row).

   1a. `$CG/varka/VarkaKernelPlan.java`, `conversionRefusal`, as the apply leaves it
   (*reworked after E2 (c)*: the hand edit of fix round 2 is gone, since 343L keeps master's
   method): the first arm is the branch's, `if (c.target() == LaneType.LONG &&
   (options.levelUnknown() || options.convertsFallBack()))` with the text "a ... conversion to
   BIGINT below emit level 3, which may be a host's without AVX512DQ: its AVX2 form is not built
   yet (row 343)", and the second is master's `BIGINT` source arm at the unknown level. Then
   `grep -c '"a conversion from "' $CG/varka/VarkaKernelPlan.java` prints `0`, `grep -c 'is
   not built yet (row 343)' ...` prints `1` and `grep -c 'at an unknown emit level, which may
   be a host' ...` prints `1`.

   1b. `$T/VarkaEmitterFloatingCastSuite.java`, as the apply leaves it: in the test
   `hostRefusal` the two lines "A floating source converted to an integral lane waits for 343b2
   at every level." and its `assertTrue(... .isPresent())` become the branch's "A floating
   source converted to the int lane is admitted at every level, and to the long lane at level 3
   (VarkaEmitterFloatToIntegralSuite)." and `assertTrue(... .isEmpty())`; the last test of the
   file, `conversionRefusal`, is deleted by the branch's hunk. It is replaced, not dropped to
   make a run pass (review 1 finding 12): it asserted the old refusal of `F2I`, `F2L` and `D2L`
   at every level, which this part lifts; `VarkaEmitterFloatToIntegralSuite.levels` (step 3)
   asserts the new behaviour, `F2I` and `D2I` admitted at every level and `F2L`, `D2L` refused
   below level 3 with the kind `CONVERSION`. The test `unknownLevelRefusal` stays master's.

   1c. `$CG/VarkaFloatingCastCompiler.java`, the javadoc of `admits`, by hand after the apply
   (343L leaves this file alone, its hand-off item 8; the branch did not update the sentence):
   the six javadoc lines between `/**` and `*/` (on master they begin "Whether {@code c} is
   one of this family's pairs" and end "and are its child where one does.") become

         * Whether {@code c} is one of this family's pairs: an integral or boolean source to a floating
         * target, one floating type to the other, or a floating source to an integral target. A
         * {@code BIGINT} source and a {@code BIGINT} target are admitted here and refused by the
         * kernel plan at the unknown emit level, which may be an AVX2 host's (and a {@code BIGINT}
         * target below level 3 too, until part 343b2b); the level is an emit option the compiler's
         * arms do not read ({@code VarkaKernelPlan}). The identities never reach here, as the
         * optimizer removes them, and are its child where one does.

   1d. `$T/VarkaTypeConformance.java`, `CastPair.fusedIn`, by hand (*new after E2 (c)*; a gap
   of the earlier text: at a matrix run of `useAVX=3` this part's `F2L` and `D2L` fuse while
   the register, written at level 2, says declined until 343b2b). 343L's `if` (its step 5)
   becomes

         // The compiler writes the register at VarkaMatrixRuns.CREDIT, an x86 session's emit
         // level (VARKA-343, part 343L). A BIGINT cast to or from FLOAT or DOUBLE declines at
         // the unknown level, the defaults', where the kernel plan refuses it
         // (VarkaKernelPlan.conversionRefusal): there it is owed, whatever the register says.
         // A FLOAT or DOUBLE cast to BIGINT is one instruction at level 3 and above, which the
         // register, written below it, does not see.
         boolean fromLong = source.equals(DataTypes.LongType)
             && (target.equals(DataTypes.FloatType) || target.equals(DataTypes.DoubleType));
         boolean toLong = target.equals(DataTypes.LongType)
             && (source.equals(DataTypes.FloatType) || source.equals(DataTypes.DoubleType));
         if ((fromLong || toLong) && VarkaMatrixRuns.BASE.levelUnknown()) {
           return false;
         }
         if (toLong && !VarkaMatrixRuns.BASE.convertsFallBack()) {
           return true;
         }

   (343b2b changes nothing here: once it merges the register reads `fused` for the floating
   sources at level 2, and the level-3 arm stays true.)

   For `VarkaVectorIR.java`, `Analysis.java` and `VarkaVectorWalk.java` take only the hunks
   343b2a1 left: in `VarkaVectorIR` the `ConvertLane` javadoc (branch lines 1106 to 1108); in
   `Analysis` the two hunks of the narrow fit rule (branch lines 1668 to 1688: the comment and
   `case DOUBLE -> (!type.isNarrow() || node instanceof ColumnRef ||
   VarkaKernelPlan.narrowsAnIntAtRoot(node)) && type != VarkaVectorIR.LaneType.LONG;`); in
   `VarkaVectorWalk` the `emitShapeConversion` javadoc and its three new names (`F2I`, `F2L`,
   `D2L`, branch lines 855 to 883). Copy these by hand: the 3-way apply of those files would
   try to add 343b2a1's hunks a second time.
2. `$CG/varka/VarkaBodyEmitter.java`, by hand (the one conflict): the comment above the line
   of B0 item 3 becomes

         // In a kernel driven at a 64-bit lane, long or double, the group is the low half of the
         // int species, and the store is whole in the loop only where the plan says the loop
         // bound leaves room for the whole species and the species is the int group's own
         // (`wholeNarrowStoreAtLong`); a byte at two 64-bit lanes is stored a row at a time
         // before this (`narrowRowsInBody`), and a width with no baked lane count stays masked.
         // The upper half's rows are the next lane group's, which that group stores over, as
         // `emitLowHalfStore` does (VARKA-332).
         boolean wholeFits = analysis.lane.bits != Long.SIZE
             || analysis.wholeNarrowStoreAtLong(stored);

   `VarkaVectorWalk.narrowRowsInBody` already tests `analysis.lane.bits == Long.SIZE`
   (VARKA-575), so a byte at two double lanes takes the row-at-a-time store as it does at two
   long lanes; nothing else changes there.
3. `$T/VarkaEmitterFloatToIntegralSuite.java`, new: the branch's file
   (`git show 96047835ca4:$T/VarkaEmitterFloatToIntegralSuite.java`) with its test
   `guardRefusals` (lines 252 to 266, now in 343b2a1's suite) and the import of `assertThrows`
   (line 22, used by that test alone) removed. Eight tests stay: `bounds`, `legacyToInt`,
   `legacyToNarrow`, `tryNulls`, `ansiAnswers`, `ansiDeclines`, `legacyToLong`, `levels`.
4. `$CG/VarkaNodeCompiler.java`, `floatingDecline`, the `DATE` text (question 2 of A.12,
   default (a)): `"a cast of DATE to a floating type, always null (row 343)"` becomes `"a cast
   of DATE to a floating type, always null under LEGACY: a kernel that reads no column is not
   compiled (row 343, as row 332's DATE to the integral types)"`. `grep -rn 'always null (row
   343)' sql/catalyst/src/test sql/core/src/test` prints nothing on master, so no test pins
   the old text.
5. `sql/varka/plans/m8/PLAN.md`, row 343: after `**Started** (` insert `*343b2a2 built <date>,
   \`VARKA-343.md\` A.5: FLOAT and DOUBLE to TINYINT, SMALLINT and INT fuse in the three modes
   at every level, to BIGINT at level 3;* `.
6. `VARKA-343.md` section 10: `### 343b2a2 as built (<date>)`, at most 15 lines.

**The coverage rows** (branch lines 1520 to 1536 of `VarkaCoverageSuite.scala`), a family of
their own placed directly after the family "Floating casts" (that is, after the row
`CAST((f > 1.5F AND i > 3) AS INT)` and its `))),`):

| row | its note | domain |
| :--- | :--- | :--- |
| `CAST(x AS INT)` | truncated toward zero; a value past the int range declines its batch | `x BETWEEN -2.0E9 AND 2.0E9` |
| `try_cast(x AS INT)` | null past the int range and at a NaN | none |
| `CAST(f AS INT)` | the float twin | `f BETWEEN -2.0E9F AND 2.0E9F` |
| `try_cast(f AS INT)` | null at the float values past 2147483520 and below -2^31 | none |
| `try_cast(x AS SMALLINT)` | the int conversion wrapped, under the short range's guard | none |
| `try_cast(f AS SMALLINT)` | the short twin over a float, in a kernel of the int lane | none |
| `try_cast(f AS TINYINT)` | the byte twin over a float, in a kernel of the int lane | none |
| `CAST(x AS TINYINT)` | the int conversion stored as a byte from a double kernel | `x BETWEEN -100.0D AND 100.0D` |

No `BIGINT` target: the rows compile at emit level 2 (343L's `VarkaMatrixRuns.CREDIT`), where
`D2L` still declines until 343b2b, which adds the four `BIGINT` rows. The family's comment from
the branch ends "A BIGINT target is not listed: the rows compile at the defaults' unknown emit
level, where D2L declines."; make that sentence read "A BIGINT target is not listed: the rows
compile at emit level 2 (VarkaMatrixRuns.CREDIT), where D2L declines until part 343b2b."
(*reworked after E2 (c)*).

**The tests.**

| suite | what it holds | the failure only it catches |
| :--- | :--- | :--- |
| `VarkaEmitterFloatToIntegralSuite` (new, JUnit) | `bounds`: A.4.2's table and Spark's own `CastUtils.*Exact`, `FloatExactNumeric`, `DoubleExactNumeric` at each bound and its outer neighbour; `legacyToInt`, `legacyToNarrow` (`(byte) 300.7` is 44, `(byte) 1e10` is -1, `(byte) 128.0` is -128), `tryNulls` (a float guard in a long kernel at level 3), `ansiAnswers` (cells inside the bounds; over every cell a batch may decline), `ansiDeclines` (rows 2 and 16, both bodies), `legacyToLong` (level 3, beside a long column), `levels` | a bound one ulp off, a wrong wrap, a lane that should decline answering |
| `VarkaEmitterFloatingCastSuite` | the plan admits `F2I` at level 3 (its assertion turns); its old test `conversionRefusal` is replaced by `VarkaEmitterFloatToIntegralSuite.levels`, which asserts the new behaviour (step 1b), not removed to make a run pass | a stale refusal |
| `VarkaFloatingLaneSuite` (Scala) | `try_cast(f1 AS INT)` fuses end to end and answers as the row engine; the decline text of a floating cast to `STRING` | the session path |
| `VarkaFloatingCastBaselineQueriesSuite`, `VarkaFloatingBaselineQueriesSuite` | the sections' fuse lists: `CAST(w AS INT)` and `CAST(x AS INT)` `WHOLE` at every level, `try_cast(w AS BIGINT)` and `DOUBLE cast to BIGINT` at level 3 alone | a benchmark section timing the row path when it should time a kernel |
| `VarkaIrFuzzSuite` | the floating casts corpus draws `FLOAT->INT`, `DOUBLE->INT`, `FLOAT->LONG`, `DOUBLE->LONG` | a conversion the grammar never draws |
| `*ConformanceSuite` (step 1d) | `VarkaFloatConformanceSuite` and `VarkaDoubleConformanceSuite`'s casts to `BIGINT`: owed at the unknown level, fused at level 3, the register's answer at the level-2 class | a matrix run at `useAVX=3` calling the fused cast drift |

**Commands, in order.**

1. `flock "$VARKA_HEAVY_LOCK" build/sbt catalyst/Test/compile sql/Test/compile`: `[success]`
   for both.
2. `flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaEmitterFloatToIntegralSuite'`:
   `Passed: Total 8, Failed 0, Errors 0, Passed 8` (on the branch: 9 of 9 with `guardRefusals`,
   23 November).
3. `flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaEmitterFloatingGuardSuite
   *VarkaEmitterFloatingCastSuite *VarkaExpressionCompilerSuite *VarkaIrFuzzSuite
   *ConformanceSuite'`: no test fails; then once more with `-Dvarka.matrix.config=useAVX=3`
   (`'set LocalProject("catalyst") / Test / javaOptions += "-Dvarka.matrix.config=useAVX=3"'`
   before the `testOnly`) for `*VarkaFloatConformanceSuite *VarkaDoubleConformanceSuite`: no
   test fails (step 1d).
4. `dev/varka_regen_generated.sh --all` (bare): its last line begins `varka regen: done`. Then:
   `grep -c '"family" : "Floating casts to integral types"' sql/varka/coverage.json` prints
   `8`; `grep -P '^(float|double)\t(byte|short|int)\t' sql/varka/plans/m8/casts_fused.tsv`
   prints six lines, each ending `fused	fused	fused`; `awk -F'\t' '$10 ~ /343/ && $9 == ""'
   sql/varka/plans/m8/casts.tsv | wc -l` prints `6` (B0 item 6's `12` less these six; fix
   round 2: `8` was the count without 343L); and the bytes check of A.5.0 prints
   `bytes: 0 committed shapes moved, 16 shapes added` (each of the eight rows at both pinned
   widths). A count of moved shapes above 0 is a stop; another count of added shapes is
   reported in the outcome, not a stop.
5. `python3 dev/varka_census.py --check`: prints nothing and exits 0.
6. `flock "$VARKA_HEAVY_LOCK" build/sbt 'sql/testOnly *VarkaFloatingLaneSuite
   *VarkaFloatingCastBaselineQueriesSuite *VarkaFloatingBaselineQueriesSuite
   *VarkaCoverageHarnessSuite'`: no test fails.
7. `BUILD_SHEETS.md` 6, then the commit and `python3 dev/varka_push_check.py` (size under 900).

The width audit runs only on its host; on another CPU the regeneration leaves it owed and
says so in its last line. The lead runs it under `JDK_JAVA_OPTIONS=-XX:UseAVX=2` on the
runners with the option matrix.

**Planted faults.** (a) In `VarkaTypes.edge`, drop the second `while` (the outward step): the
`bounds` test fails at the `FLOAT` targets. (b) In `toIntegral`, guard with `castBounds(source,
LaneType.INT)` for every target: `tryNulls` fails at `SMALLINT` and `TINYINT`. (c) In
`VarkaBodyEmitter`, put back `analysis.lane != Lane.LONG`: `legacyToNarrow` fails at the
double source (a whole store past the group's rows), or, if it passes, record that in the
outcome: the masked store is then the only store and the fault is not visible.

**Done when** commands 1 to 7 print what they say, and the census reads the six pairs fused.

**Not here:** `D2L` or `F2L` below level 3; the weight of a conversion to the long lane; any
`BIGINT` coverage row; `time_from_seconds`; the unknown level (it keeps declining, 343L's known
limit); the `DATE` pairs beyond the decline text.

#### Part 343b2b: `D2L` and `F2L` below level 3, the assembly (build sheet, rework E2 and E3)

*Rewritten on 28 November 2026 after the owner's decisions E2 (c) and E3 (a): this part builds
the exact assembly of A.4.3 only, under every arm of `wideConversionForm`; the truncated magic
and its proof move to January 2027 (parts 343b2m and 343b2p). With 343L under E2 (c) the
unknown level keeps declining `D2L` and `F2L`, so `conversionRefusal` and `Kind.CONVERSION`
stay, and the branch's hunks of `VarkaKernelPlan`, the `levels` test and the two `sql/core`
files apply as they are. The old hold rule keyed on 343b2p's marker is gone: no bounded
lowering is built here, so house rule VARKA-242 owes no proof.*

- **Title:** `[VARKA-343] Convert DOUBLE and FLOAT to BIGINT on AVX2 by the integer assembled
  from the bits`. **Branch:** `varka-343b2b-d2l-assembly` from master after 343b2a2 merged (the
  names `varka-343b2b-avx2` and `-r` are taken). **Builder:** Sonnet-class. **Size:** about
  560 counted lines (the branch's 649 less the magic arm's code, test and twin); between 901
  and 999 is impossible here. Follow `BUILD_SHEETS.md`.
- **What it adds:** below level 3 (`convertsFallBack()`, levels 0 to 2), `D2L` by the integer
  assembled from the bits, whole-range, no mask, under `SPLIT`, `XOR_SPLIT` and `MAGIC` alike
  (the option keeps reading `L2D` alone); `F2L` as the exact `F2D`, then the same. Level 3
  keeps its one instruction (343b2a2). At the unknown level both still decline, with master's
  wording for the `BIGINT` sources ("at an unknown emit level, which may be a host's without
  AVX512DQ"). The casts to `BIGINT` get their four coverage rows, credited at level 2 (343L).

**B0, the premises.**

1. 343b2a2 is merged: `grep -c 'narrowsAnIntAtRoot' $CG/varka/VarkaKernelPlan.java` at least
   `2`, and `ls $T/VarkaEmitterFloatToIntegralSuite.java` lists the file.
2. `grep -c 'emitAssembly\|emitTruncatedMagic\|ASSEMBLY_CALLS'
   $CG/varka/VarkaConversionLowering.java`: `0`.
3. `grep -c 'conversion to BIGINT below emit level 3' $CG/varka/VarkaKernelPlan.java`: `1`
   (343b2a2's refusal of `F2L` and `D2L` wherever `levelUnknown() || convertsFallBack()`).
4. `grep -c 'return node.props().weight();' $CG/varka/VarkaEmitBudget.java`: `1`.
5. 343L is merged: `grep -c 'public static final VarkaEmitOptions CREDIT ='
   $T/VarkaMatrixRuns.java`: `1`. If not, stop (A.5).
6. `git cat-file -t 163aac5ecd6`: `commit` (if not, A.5.0 "Where the code comes from": the
   fork's `varka-343b2b-r2`).
7. `grep -c 'x86.s AVX512DQ rule too, and is' $CG/varka/VarkaKernelPlan.java`: `1` (the
   javadoc sentence of 343b2a2 that the branch's hunk rewrites).

**Steps.**

1. For each of these files, `git diff fa1031645a7 163aac5ecd6 -- <file> | git apply --3way`
   (each merges clean on master after 343b2a; computed, see A.1), then the hand edits named:
   - `$CG/varka/VarkaKernelPlan.java`, whole: the `F2L`/`D2L` arm of `conversionRefusal`
     tests `options.levelUnknown()` alone, with the text "... conversion to BIGINT at an
     unknown emit level, which may be a host's without AVX512DQ (row 343)", and its javadoc
     says the long lane's conversions take their AVX2 form below level 3 and decline at the
     unknown level. Then `grep -c 'conversion to BIGINT at an unknown emit level'
     $CG/varka/VarkaKernelPlan.java` prints `1` and B0 item 3's grep prints `0`.
   - `$CG/varka/VarkaConversionLowering.java`, then by hand, so that the magic arm is not
     built: (i) in the class javadoc keep the first new `<li>` ("`D2L`, and `F2L` after an
     exact `F2D` ...") with "under {@code SPLIT} and {@code XOR_SPLIT}" replaced by "under
     every arm of {@link WideConversionForm}, which picks {@code L2D}'s form alone", and delete
     the second (`<li>The same under {@link WideConversionForm#MAGIC}: the truncated magic,
     ...</li>`, five lines); (ii) delete the line `static final int TRUNCATED_MAGIC_CALLS =
     22;`; (iii) `takesMagic` and its javadoc stay master's: undo the branch's hunk there, so
     the method reads `return takesWideForm(analysis, n) && n.target() == LaneType.DOUBLE &&
     analysis.options.wideConversionForm() == WideConversionForm.MAGIC;` under the javadoc
     "Whether {@code n} is the bounded {@code L2D}, whose guard declines a batch past 2^51.";
     (iv) in `scratch`, `return takesMagic(analysis, n) ? 4 : 6;` becomes `return 6;`, and its
     javadoc's "the assembly's six and the truncated magic's four for a conversion to the long
     lane" becomes "the assembly's six for a conversion to the long lane"; (v) `emitToLong`
     becomes

         /** {@code [x] -> [(long) x]} for {@code D2L} or {@code F2L}: a float child is widened
          *  exactly first. */
         private static void emitToLong(CodeBuilder cb, ConvertLane n, boolean dense,
             Analysis analysis, Slots s) {
           int[] tmp = s.convertTmp.get(n);
           if (tmp == null) {
             throw VarkaErrors.illegalState("a D2L form with no scratch planned: " + n);
           }
           if (n.child().laneType() == LaneType.FLOAT) {
             VarkaVectorWalk.emitShapeConversion(cb, Lane.FLOAT, Lane.DOUBLE, analysis);
           }
           emitAssembly(cb, tmp);
         }

     (`dense` stays a parameter, unread, so that `emit`'s call is the branch's; if the
     linters flag it, drop it here and in the call); (vi) delete `emitTruncatedMagic` and its
     javadoc whole; (vii) `emitAssembly`'s javadoc: "{@link WideConversionForm#SPLIT} and {@link
     WideConversionForm#XOR_SPLIT}'s {@code D2L}" becomes "{@code D2L} under every arm". Then
     `grep -c 'emitTruncatedMagic\|TRUNCATED_MAGIC_CALLS' $CG/varka/VarkaConversionLowering.java`
     prints `0` and `grep -c 'emitAssembly' ...` prints at least `2`. `takesWideForm` is the
     branch's (it reads `convertsFallBack()`, as master's does; 343L did not touch it).
   - `$CG/varka/VarkaVectorWalk.java`: the first hunk only (the `emitShapeConversion`
     javadoc), with "the assembly or the truncated magic of {@link VarkaConversionLowering}"
     replaced by "the assembly of {@link VarkaConversionLowering}". The branch's other two
     hunks (`emitRangeMask`'s floating arm, `emitValueRangeGuard`, the new signature of
     `emitFloatingRangeMask`) serve the magic's guard alone: do not take them. `git apply`
     cannot take one hunk of a file, so copy the javadoc lines by hand from `git show
     163aac5ecd6:$CG/varka/VarkaVectorWalk.java`, lines 858 to 866.
   - `$T/VarkaEmitterFloatToIntegralSuite.java`, then by hand: (i) `avx2ToLong`: delete the
     line `boolean magic = ...`, use `long[] cells = cells(source);`, pass `false` as the last
     argument of the ANSI `check`, and its `@DisplayName` becomes `"AVX2: the assembled D2L
     and F2L are Java's cast at every cell, under every arm of wideConversionForm"`; (ii) delete
     `window` (with its javadoc) and the test `magicDeclines` whole; (iii) delete `magicTwin`
     (with its javadoc); in `twins`, the filter's body becomes `return assemblyTwin(x) != (int)
     x;` (the local `window` goes), and its javadoc reads "Every {@code every}-th float pattern
     through the assembly's twin, against Java's cast."; `twinSlices`' `@DisplayName` becomes
     `"the assembly's float-to-int twin over every 97th float is Java's cast"`. The seven new
     `CELLS`, `levels` (the branch's: `F2L` and `D2L` refused at the unknown level alone),
     `twinsWhole`, `sweepSlices`, `sweepsWhole` stay as the branch has them. Then `grep -c
     'magic\|MAGIC' $T/VarkaEmitterFloatToIntegralSuite.java` prints `1` (the `MAGIC` arm of
     `avx2Arms`, which takes the assembly too).
   - `$BJ/VarkaFloatingCastBaselineBenchmark.java` and
     `$BS/VarkaFloatingCastBaselineQueriesSuite.scala`, whole: `fusesToBigint`'s javadoc and
     `val toBigint = level != -1` (D2L fuses at the named levels).
   - `sql/varka/ci/sweeps.tsv`: the suite's line, with "and the two float-to-int twins" read
     "and the assembly's float-to-int twin".

   Not from the branch: `Analysis.java` (its one hunk names the magic's window),
   `VarkaEmitBudget.java` (step 3), `VarkaEmitOptions.java` (master's `@param
   wideConversionForm`, "the form of {@code L2D} where {@link #convertsFallBack()} holds ...
   No other shape reads it.", stays true, and the enum's javadoc stays master's). If any other
   file's apply leaves a conflict, stop and report.
2. `$CG/varka/VarkaVectorIR.java`, `ConvertLane.props()`, by hand (master's weight is here,
   since row 489): the weight and its comment become

         // One lanewise op, the conversion; a long converted to a floating lane is one
         // convertShape at level 3, and below it the default AVX2 form; a floating lane
         // converted to the long one likewise, the assembly (and F2L's exact F2D before it).
         // Another L2F form the options choose is weighed by VarkaEmitBudget.weightOf's
         // overload, which reads them.
         int weight = child.laneType() == LaneType.LONG
             ? (target == LaneType.FLOAT
                 ? VarkaConversionLowering.ODD_CALLS : VarkaConversionLowering.SPLIT_CALLS)
             : VarkaConversionLowering.toLong(this)
                 ? VarkaConversionLowering.ASSEMBLY_CALLS
                     + (child.laneType() == LaneType.FLOAT ? 1 : 0)
                 : 1;

3. `$CG/varka/VarkaEmitBudget.java`: nothing in code (no form of `D2L` depends on the options);
   add to the javadoc of `weightOf(VarkaVectorIR node, VarkaEmitOptions options,
   NarrowConversionForm narrow)`: "A floating lane converted to the long one weighs the
   assembly under every arm, in {@code ConvertLane.props()}."
4. `$T/VarkaIrGrammar.java`, the javadoc of `FloatingCastShapes`: "one to the long lane is
   admitted at level 3 alone, below which its draw declines" becomes "one to the long lane is
   admitted at every named level, and its draw declines at the unknown one".
5. `$S/VarkaEmitterBudgetSuite.scala`, a new test after "a bigint conversion to a floating lane
   weighs its AVX2 form: ...":

         test("a floating conversion to the long lane weighs its AVX2 form, the assembly, " +
             "under every arm of wideConversionForm", PinsDefaults) {
           // D2L and F2L below level 3 (VARKA-343, part 343b2b): ConvertLane.props() weighs
           // the assembly, the count of lane ops its form emits over the level-3 kernel's one
           // call, and no arm of the option moves it.
           import VarkaEmitOptions.WideConversionForm._
           def ops(source: LaneType, options: VarkaEmitOptions): Int = vectorOps(emitMulti(
             Seq(new ConvertLane(new ColumnRef(0, source), LaneType.LONG)), 1, 0, options)._2)
           def form(source: LaneType, options: VarkaEmitOptions): Int =
             ops(source, options) - ops(source, VarkaMatrix.base.withUseAVX(3)) + 1
           val avx2 = VarkaMatrix.base.withUseAVX(2)
           for (source <- Seq(LaneType.DOUBLE, LaneType.FLOAT);
               arm <- Seq(SPLIT, XOR_SPLIT, MAGIC)) {
             val options = avx2.withWideConversionForm(arm)
             val conversion = new ConvertLane(new ColumnRef(0, source), LaneType.LONG)
             val weight =
               VarkaEmitBudget.weightOf(conversion, options, options.narrowConversionForm())
             val emits = form(source, options)
             assert(weight === emits, s"$source under $arm weighs $weight and emits $emits")
           }
         }

   **The rule if it fails** (the branch counted 38 by hand, never by this count): set
   `ASSEMBLY_CALLS` to the count the assertion prints for `DOUBLE` under `XOR_SPLIT`, then
   rerun. If the `FLOAT` counts are then not one more than the `DOUBLE` ones, or the three arms
   differ, stop and report the six counts. Record the counts in the outcome.
6. The casts to `BIGINT`, by hand:
   - `$CG/VarkaFloatingCastCompiler.java`, the javadoc of `admits`: the clause "(and a
     {@code BIGINT} target below level 3 too, until part 343b2b)" (343b2a2's step 1c) is
     deleted, so that the sentence says a `BIGINT` source and a `BIGINT` target are refused by
     the kernel plan at the unknown emit level alone.
   - `sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaFloatingLaneSuite.scala`
     (343b2a2's text): the comment above `declined` that begins "// The divisors are literals"
     keeps `try_cast(x1 AS BIGINT)` in the list, and its last line "waits for emit level 3,
     which these sessions leave unknown (VARKA-343)." becomes "declines at the unknown emit
     level these sessions keep (VARKA-343 A.4.5, the known limit)."; after the
     `declined.foreach` block add

           test("SELECT try_cast(x1 AS BIGINT) fuses at emit level 2 and answers as the row " +
               "engine does") {
             // D2L by the integer assembled from the bits, an x86 session's level without
             // AVX512DQ (VARKA-343, part 343b2b); the sessions here keep the unknown one.
             withBothConf(SQLConf.VARKA_EMIT_USE_AVX.key, "2") {
               checkFused("SELECT try_cast(x1 AS BIGINT) AS v FROM $t")
             }
           }

     (`$t` is `checkFused`'s placeholder, not an interpolation; `withBothConf` is
     `VarkaSharedSessions`').
   - `$S/VarkaCoverageSuite.scala`, the family "Floating casts to integral types": its comment's
     last sentence (343b2a2's: "A BIGINT target is not listed: the rows compile at emit level 2
     (VarkaMatrixRuns.CREDIT), where D2L declines until part 343b2b.") becomes "A BIGINT target
     takes D2L's assembly at emit level 2, which the rows compile at (part 343b2b), and
     declines at an unknown level.", and after its row `CAST(x AS TINYINT)` (and its domain)
     add, each as `Row(sql, note, domain = Some(...))` where a domain is given:

     | row | note | domain |
     | :--- | :--- | :--- |
     | `CAST(x AS BIGINT)` | the integer assembled from the bits at emit level 2; a value past the long range declines its batch | `x BETWEEN -9.0E18 AND 9.0E18` |
     | `try_cast(x AS BIGINT)` | null past the long range and at a NaN; `2^63` saturates as Spark reads `Long.MaxValue` | none |
     | `CAST(f AS BIGINT)` | the float widened exactly, then the same | `f BETWEEN -9.0E18F AND 9.0E18F` |
     | `try_cast(f AS BIGINT)` | the float twin | none |

7. `sql/varka/plans/m8/PLAN.md`, row 343: after `**Started** (` insert `*343b2b built <date>,
   \`VARKA-343.md\` A.5: D2L and F2L fuse below level 3 by the integer assembled from the
   bits, under every arm; the truncated magic is January's (343b2m);* `. `VARKA-343.md` section
   10: `### 343b2b as built (<date>)`, at most 15 lines, with the counts of step 5.

**The tests.**

| suite | what it holds | the failure only it catches |
| :--- | :--- | :--- |
| `VarkaEmitterFloatToIntegralSuite` (now 13 tests) | `avx2ToLong`: at `useAVX=2` under `XOR_SPLIT`, `SPLIT` and `MAGIC`, both sources to `BIGINT` in LEGACY, TRY and ANSI over every cell, every width, length, null pattern, both bodies; `twinSlices`: the assembly's 32-bit twin (`assemblyTwin`) against Java's `(int)` over every 97th float; `sweepSlices`: every 257th chunk of `2^16` float patterns through the emitted kernels of `CAST` and `try_cast` to the four types, `BIGINT` at level 3 and under both whole-range AVX2 arms; `twinsWhole` and `sweepsWhole` the same over every pattern under `-Dvarka.sweep=true`; `levels`: `F2L` and `D2L` refused at the unknown level alone | an off-by-one shift, a lost sign, a saturation or NaN case |
| `VarkaEmitterBudgetSuite` (Scala, new test) | the weight equals the emitted count under each arm | a group over its budget unseen (the review's finding) |
| `VarkaFloatingCastBaselineQueriesSuite` | `fusesToBigint` at every named level, not at -1 | a benchmark section left on the row path |
| `VarkaFloatingLaneSuite` | `try_cast(x1 AS BIGINT)` declined at the sessions' unknown level, fused at level 2 | the session path at both levels |
| `*ConformanceSuite` | `VarkaFloatConformanceSuite`, `VarkaDoubleConformanceSuite`: the casts to `BIGINT` held to the register at the credit level's class, owed at the unknown level (343b2a2's `fusedIn`) | a register that drifted from the compiler |

**Commands, in order.**

1. `flock "$VARKA_HEAVY_LOCK" build/sbt catalyst/Test/compile sql/Test/compile`: `[success]`.
2. `flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaEmitterFloatToIntegralSuite'`:
   sbt's summary shows `Total 13`, `Failed 0`, `Errors 0`; the two opt-in tests (`twinsWhole`,
   `sweepsWhole`) are reported as skipped or canceled, since they assume the property. Any
   failed or errored test is a stop.
3. `flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaEmitterBudgetSuite
   *VarkaIrFuzzSuite *VarkaEmitterWideConversionSuite *VarkaEmitterFloatingCastSuite
   *ConformanceSuite'`: no test fails (step 5's rule first).
4. `dev/varka_regen_generated.sh --all` (bare): last line begins `varka regen: done`; the
   bytes check prints `bytes: 0 committed shapes moved, 8 shapes added` (the four rows at both
   pinned widths, each `<declined>` at the oracle's unknown level, 343L's hand-off item 3;
   another added count is reported, not a stop; a moved shape is a stop).
5. `grep -P '^(float|double)\tbigint\t' sql/varka/plans/m8/casts_fused.tsv` prints two lines
   ending `fused	fused	fused`; `awk -F'\t' '$10 ~ /343/ && $9 == ""'
   sql/varka/plans/m8/casts.tsv | wc -l` prints `4` (the two `DATE` pairs and the two
   identities); `awk -F'\t' '$1=="bigint"{print $8}' sql/varka/plans/m8/census.tsv` prints
   `bool,byte,short,int,bigint,float,double,dt`; `python3 dev/varka_census.py --check` prints
   nothing.
6. `flock "$VARKA_HEAVY_LOCK" build/sbt 'sql/testOnly *VarkaFloatingCastBaselineQueriesSuite
   *VarkaFloatingBaselineQueriesSuite *VarkaFloatingLaneSuite *VarkaCoverageHarnessSuite
   *VarkaCastWithAnsiOffSuite *VarkaCastWithAnsiOnSuite *VarkaTryCastSuite'`: no test fails.
7. Once, the whole sweeps: `flock "$VARKA_HEAVY_LOCK" build/sbt 'set LocalProject("catalyst") /
   Test / javaOptions += "-Dvarka.sweep=true"' 'catalyst/testOnly
   *VarkaEmitterFloatToIntegralSuite'`: no test fails, none skipped; put the run's time in the
   suite's line of `sql/varka/ci/sweeps.tsv` in place of "not yet timed".
8. `BUILD_SHEETS.md` 6, the commit, `python3 dev/varka_push_check.py`.

**Planted faults.** (a) `MAX_LEFT_SHIFT` of 10 becomes 11: at `e = 11` the left shift of `m`
leaves the long instead of saturating, so `0x1p63` gives `Long.MIN_VALUE`; command 2 fails in
`avx2ToLong` (the cell `0x1p63` is in `CELLS`). (b) In `emitAssembly`, the
`"sub"` of `r = (mag ^ s) - s` (the `invokevirtual(LONG_VECTOR, "sub", ...)` right after
`cb.aload(neg)`) becomes `"add"`: `-0.7` then gives -2, not 0, and `avx2ToLong` fails at that
cell. (c) `ASSEMBLY_CALLS = 37`: the budget test fails.

**Done when** commands 1 to 8 print what they say and the three faults fail as said.

**Not here:** the truncated magic (343b2m, January 2027) and its proof (343b2p, January 2027);
`time_from_seconds`; `VarkaDivisionLowering`'s use of `convertsFallBack()`; the unknown level
(343L's known limit).

#### Part 343b2p (January 2027, full strength; not a gate before then)

*The truncated magic's proof. Moved on 28 November 2026 by the owner's decisions E1 (a) and E3 (a):
this proof and the arm it proves (343b2m) wait for a full-strength builder, back in January 2027.
Neither gates the milestone 8 close: 343b2b builds the whole-range assembly, which owes no proof.
The text below is kept as written; read "343b2m" where it says "343b2b" about the magic arm.*

- **Title:** `[VARKA-343] Prove the truncated magic D2L inside its window`. **Branch:**
  `varka-343b2p-magic-proof` from master. **Builder:** full strength, January 2027: a new
  section of an SMT file in the floating-point theory that mixes `Float64` operations with a
  double's bits, under both solvers' time limits; the encoding of `bits(x)` is the design work.
  **Size:** about 150 counted lines; the `.smt2` is generated. Merges before 343b2m.
- **What it proves**, in `round_to_odd.smt2` (rendered by `VarkaProofFiles.roundToOdd()`,
  whose header lists the AVX2 forms of 2.3 already), from `VarkaConversionLowering.MAGIC`
  (`0x1.8p52`) and `MAGIC_WINDOW` (`2^51`), which master has:
  1. for every `Float64` `x` with `|x| <= 2^51`, the form of A.4.3 equals `fp.to_sbv 64 RTZ` of
     `x` (Java's `(long) x` inside the range), stated per binade of `x` as `fp_half_up.smt2`
     states its claims, zero and the subnormals included;
  2. the claim is not vacuous and the window is tight: `sat` that the form differs from the
     truncation at `x = 0x1.0000000000002p+51` (the form answers 2251799813685248, the
     truncation 2251799813685249) and at `x = -0x1.0000000000001p+51`, the first doubles past
     the window where it differs (`scratch-343/magic_first_failure.py` of the planning folder,
     last line: `truncated magic: 400016 doubles inside 2^51, 0 differ; first failures past
     the window at 0x1.0000000000002p+51 and -0x1.0000000000001p+51`). `0x1.0000000000001p+51`
     is still exact, so above zero the bound is the guard's, one double inside the form's.
- **The marker** (fix round 2, review 1 finding 8): the new section of the rendered
  `round_to_odd.smt2` opens with the comment line `; D2L's truncated magic inside |x| <= 2^51
  (VARKA-343, part 343b2p)`, written by `VarkaProofFiles` word for word, on one line. 343b2m's
  B0 item 3 greps for it to know whether this part has merged.
- **A hint for the encoding**, not a decision: the bits of a non-NaN `Float64` `v` are the one
  bit-vector `b` with `((_ to_fp 11 53) b) = v` whose sign bit is `v`'s (so the two zeros are
  told apart); `round_to_odd.smt2` defines `rto.bitsToDouble` already.
- **Commands:** `VARKA_PROOFS_REGEN=true flock "$VARKA_HEAVY_LOCK" build/sbt
  'catalyst/testOnly *VarkaProofFilesSuite'`; `dev/varka_prove.sh --solver both
  round_to_odd.smt2` ends `varka_prove: every proof holds under z3 cvc5`, each solver within
  its 300 s run limit (62 checks before; the new count in the outcome). If a solver answers
  `unknown`, split the binades further; if it cannot hold inside the limit, put the section in
  a file of its own on the `NIGHTLY_ONLY` list of `dev/varka_prove.sh`, as `fp_half_up.smt2`
  is.
- **Not here:** the assembly (whole-range, held by 343b2b's cells, sweeps and twins); any code
  under `sql/catalyst/src/main`.

#### Part 343b2m (January 2027, full strength; not a gate before then)

*The truncated magic arm of `D2L` and `F2L`. New on 28 November 2026 (the owner's decision E3
(a)): the reference arm, which 343b2b no longer builds. Its code is on the branch and stays
there; this part is the list of what to take, after 343b2b and 343b2p have merged. Not to be
started before January 2027.*

- **Title:** `[VARKA-343] Take D2L and F2L by the truncated magic under wideConversionForm=MAGIC`.
  **Builder:** full strength, January 2027: a bounded lowering whose guard sits on the child,
  a second floating-guard emission path beside 343b2a1's, and a weight that depends on the
  options. **Size:** about 150 counted lines. Waits on 343b2b and 343b2p.
- **What it adds:** below level 3, under `wideConversionForm=MAGIC`, `D2L` by A.4.3's truncated
  magic behind a guard `|x| <= 2^51` on the child that declines the batch (NaN and the
  infinities with it); `F2L` as `F2D`, then the same. Under `SPLIT` and `XOR_SPLIT` the
  assembly stays.
- **B0:** 343b2b merged (`grep -c 'emitAssembly' $CG/varka/VarkaConversionLowering.java` at
  least `2`, `grep -c 'emitTruncatedMagic' ...` `0`); `git cat-file -t 163aac5ecd6` prints
  `commit`; 343b2p merged (`grep -c "D2L's truncated magic inside |x| <= 2^51 (VARKA-343, part
  343b2p)" sql/varka/proofs/round_to_odd.smt2` at least `1`; if `0`, stop: house rule VARKA-242
  wants the proof first).
- **What to take from `git diff fa1031645a7 163aac5ecd6`**, the hunks 343b2b left out: in
  `VarkaConversionLowering` the class javadoc's second `<li>` (the magic), `TRUNCATED_MAGIC_CALLS =
  22`, the `takesMagic` hunk (`n.target() == LaneType.DOUBLE || toLong(n)`, and its javadoc),
  `scratch`'s `takesMagic(analysis, n) ? 4 : 6`, `emitToLong`'s guard block and its `magic` branch,
  `emitTruncatedMagic`; in `VarkaVectorWalk` the two hunks after `emitShapeConversion`
  (`emitRangeMask`'s floating arm passing `g.loValue()`, `g.hiValue()`; the new
  `emitValueRangeGuard`; `emitFloatingRangeMask(CodeBuilder cb, int guardTmp, Lane lane, double lo,
  double hi)`), and the javadoc's "or the truncated magic"; `Analysis`'s `VALUE_RANGE` javadoc hunk;
  `VarkaEmitOptions`' two hunks (the `@param wideConversionForm` and the enum's javadoc: "The option
  also picks {@code D2L}'s and {@code F2L}'s form ..."), merged by hand over master's text (343L
  leaves the file alone); in `VarkaEmitterFloatToIntegralSuite` the branch's `avx2ToLong` (the
  window's cells under `MAGIC`, ANSI declines expected there), `window`, `magicDeclines`,
  `magicTwin` and `twins`' window term.
- **The weight**, in `VarkaEmitBudget.weightOf`'s options overload, before its last line:

        // The truncated magic, the bounded arm of D2L and F2L, where the options pick it
        // (the review of 23 November 2026: the budget charged it the assembly's 38).
        if (node instanceof ConvertLane n && VarkaConversionLowering.toLong(n)
            && options.convertsFallBack()
            && options.wideConversionForm() == VarkaEmitOptions.WideConversionForm.MAGIC) {
          return VarkaConversionLowering.TRUNCATED_MAGIC_CALLS
              + (n.child().laneType() == VarkaVectorIR.LaneType.FLOAT ? 1 : 0);
        }

  and 343b2b's budget test then expects the magic's count under `MAGIC` (its rule: set
  `TRUNCATED_MAGIC_CALLS` to the count printed for `DOUBLE` under `MAGIC`).
- **Planted fault:** in `emitTruncatedMagic`, `emitScalar(cb, "or", 1L)` becomes
  `emitScalar(cb, "or", 0L)`: for a positive `x` the step toward zero is then 0, so `0.7` gives
  1; `avx2ToLong` fails under `MAGIC`.
- **Then** 343r's 4.4 rule (the assembly within 1.5 times the magic; the default flips only if
  the ratio passes 1.3 at 256 bits) is scored by a results part of its own, on
  `VarkaWideConversionBenchmark`'s D2L section with a third arm, `cast(x AS bigint), the
  truncated magic`.

#### Part 343L: the records compile at an x86 session's emit level (build sheet, rework E2)

*Rewritten on 28 November 2026 after the owner's decision E2 (c), which replaces the answer
(b) to question 1 of A.12: this part no longer builds the exact software forms at the unknown
emit level, and production does not change. The suites that write a generated record crediting
a fused form compile at emit level 2, the level an x86 session without AVX512DQ resolves to,
in place of the unknown level of `VarkaEmitOptions.DEFAULTS`. The earlier full-strength sheet
is gone; its hand-off is replaced by the one at the end of this sheet.*

- **Title:** `[VARKA-343] Credit the cast matrix and the coverage table at an x86 session's emit
  level`. **Branch:** `varka-343L-credit-level` from master. **Builder:** Sonnet-class.
  **Size:** about 190 counted lines plus the regenerated records (not counted). The owner's
  estimate was about 40; the grep of B0 item 6 found eight readers beyond the cast matrix and
  the coverage suite that hold the records' rows, each a few lines. Between 901 and 999 counted
  lines is impossible here; above 400, stop and report. Follow `BUILD_SHEETS.md`. Waits on
  nothing.
- **What it changes:** test code, the census script's captions, one curated header and the
  regenerated records. No file under `sql/catalyst/src/main` or `sql/core/src/main` changes.
- **What it credits:** `bigint` to `float` and `double` in `casts.tsv` (two pairs, all three
  modes) and the census's cast entries. The other six of the eight pairs E2 names are credited
  by the parts that build them, on this part's rule: `float` and `double` to `bigint` by
  343b2b; `dt_times_num` over `float` and `double` by 342e; `dt_div_num` over `float` and
  `double` by 342q.
- **The known limit, stated in the records' captions:** a session keeps the unknown level, and
  so still declines a `BIGINT` conversion to or from a floating lane, on a host it cannot read:
  an x86 host without AVX512DQ whose preferred long species is under 256 bits (the runners'
  `-XX:MaxVectorSize=16` runs among them), an x86 host whose feature list could not be read,
  and any architecture but x86 and aarch64; also wherever the session sets
  `spark.sql.codegen.varka.emit.useAVX=-1`, as the test sessions do. The remedy there is an
  explicit `spark.sql.codegen.varka.emit.useAVX=2` (or 3 on AVX512DQ). Checked on
  `6dafb8bd2f6` by reading `VarkaSessionEmit.resolve` (its body: `FALLBACK_LEVEL` only where
  `host.longBits() >= MAGIC_MIN_LONG_BITS`, 256; `AVX512_LEVEL` where the conversions are
  instructions; `USE_AVX_UNKNOWN` otherwise) and B0 item 2.

**B0, the premises.** `CG`, `T`, `S` as in A.3, and two more names:

    E=sql/core/src/test/scala/org/apache/spark/sql/execution
    EJ=sql/core/src/test/java/org/apache/spark/sql/execution
    CT=sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen

1. `grep -c 'static final int FALLBACK_USE_AVX = 2;' $T/VarkaMatrixRuns.java`: `1`; `grep -c
   'CREDIT\|credited' $T/VarkaMatrixRuns.java`: `0`.
2. `grep -c 'at an unknown emit level, which may be a host' $CG/varka/VarkaKernelPlan.java`:
   `1` (the refusal this part keeps).
3. `grep -c 'VarkaEmitOptions.DEFAULTS' $T/VarkaCastMatrixSuite.java`: `2`; `grep -c
   'VarkaEmitOptions.DEFAULTS' $S/VarkaCoverageSuite.scala`: `11`.
4. `grep -P '^bigint\t(float|double)\t' sql/varka/plans/m8/casts_fused.tsv` prints two lines
   ending `declined	declined	declined`; `grep -c 'unknown emit level'
   sql/varka/plans/m8/casts_suites.tsv`: `6`.
5. `awk -F'\t' '$10 ~ /343/ && $9 == ""' sql/varka/plans/m8/casts.tsv | wc -l`: `14`.
6. The readers, `grep -rln 'coverage.json\|VarkaCoverageRows\|casts_fused\|casts_suites'
   sql/catalyst/src/test sql/core/src/test`: 25 files on `6dafb8bd2f6`. The ones that require
   a row or a cast to fuse at the unknown level, and so are edited below:
   `VarkaCastMatrixSuite`, `VarkaCoverageSuite`, `VarkaEmittedBytesSuite`,
   `VarkaTypeConformance`, `VarkaSuiteArm` (through `VarkaSuiteCheck` and `VarkaSuiteRecord`,
   which write `casts_suites.tsv`), `VarkaCoverageHarnessSuite`, `VarkaFamilySwitchSuite`,
   `VarkaCoverageCompositionFuzzSuite`, `VarkaWidthAuditSuite`. The others read rows over
   columns the new rows do not use (`VarkaFarDaySuite`), skip a declined row
   (`VarkaDivisorProofSuite`, `VarkaMaskFromWordSuite`, `VarkaMaskOfWordSuite`,
   `VarkaWarmupReach`), or run one row per family through a session
   (`VarkaCoverageDifferentialSuite`: no row added here is a family's first). Left at the
   defaults on purpose: `VarkaSQLQueryTestSuite` and `VarkaGoldenCorpusSuite` record what
   Spark's own tests serve through their sessions, which pin the level to -1; no census input
   reads them (`grep -c 'served.json\|golden' dev/varka_census.py` prints `0`).

**Steps.**

1. `$T/VarkaMatrixRuns.java`, directly after `static final int FALLBACK_USE_AVX = 2;`:

        /**
         * The emit level the generated records that credit a fused form are written at
         * ({@code casts_fused.tsv}, {@code casts_suites.tsv}, the coverage table and the census
         * read from them), and the harnesses that hold those rows run at: the level an x86
         * session without AVX512DQ resolves to at a 256-bit species, as the runners' EPYC 7763
         * does ({@code VarkaSessionEmit.FALLBACK_LEVEL}). A record's "fused" means fused on an
         * x86 host at this level. The defaults' unknown level, which a session keeps on a host
         * it cannot read, still declines a {@code BIGINT} conversion to or from a floating lane
         * (`VARKA-343.md` A.4.5, part 343L). Production code does not read it.
         */
        public static final int CREDIT_USE_AVX = FALLBACK_USE_AVX;

        /**
         * {@code options} at {@link #CREDIT_USE_AVX} where they name no level, and unchanged
         * where they name one (a matrix configuration of {@code useAVX}).
         */
        public static VarkaEmitOptions credited(VarkaEmitOptions options) {
          return options.levelUnknown() ? options.withUseAVX(CREDIT_USE_AVX) : options;
        }

        /** The defaults at {@link #CREDIT_USE_AVX}: what the records compile under. */
        public static final VarkaEmitOptions CREDIT = credited(VarkaEmitOptions.DEFAULTS);

        /** Whether {@code reason} is the kernel plan's refusal of a conversion at the unknown level. */
        public static boolean isUnknownLevelDecline(String reason) {
          return reason.contains("at an unknown emit level");
        }

   (wrap the javadoc line of `isUnknownLevelDecline` at 100 columns). The text matches both
   refusals: master's `BIGINT` source ("a BIGINT conversion to DOUBLE at an unknown emit
   level") and, after 343b2b, a floating source to `BIGINT`.
2. `$T/VarkaCastMatrixSuite.java`, `fuses`: both `VarkaEmitOptions.DEFAULTS` become
   `VarkaMatrixRuns.CREDIT`, and in its javadoc the sentence "The defaults' options, not the
   matrix's: the file records what production fuses." becomes "The options are
   {@link VarkaMatrixRuns#CREDIT}, the defaults at the emit level of an x86 session without
   AVX512DQ, and not the matrix's: a cell of the file means fused on an x86 host at that level
   (part 343L of `VARKA-343.md`). At the defaults' unknown level a {@code BIGINT} conversion to
   or from a floating lane declines, which {@code VarkaEmitterFloatingCastSuite} holds."
3. `$S/VarkaCoverageSuite.scala`:
   - every `VarkaEmitOptions.DEFAULTS` (11: nine in code, two in the comments above `guardsOf`
     and `kernelsOf`) becomes `VarkaMatrixRuns.CREDIT`;
   - the seven calls that pass no options get `VarkaMatrixRuns.CREDIT` as their last argument:
     the `compile` and `compilePartial` of `declinedRows`, the `compilePartial`, `compile` and
     `declines` of the test "every predicate row compiles as a projection entry to a boolean
     output", the `compilePartial` of the test "every operand the compiler builds in a
     restricted position is one the position admits" and the `compile` of the test "an
     expression matched only to decline really does decline". Then `grep -c 'VarkaEmitOptions.DEFAULTS'` prints
     `0` and, after the next two bullets too, `grep -c 'VarkaMatrixRuns.CREDIT'` prints `19`;
   - the comment above `guardsOf`: "Read under `VarkaMatrixRuns.CREDIT`, the options the rest
     of this suite compiles under: the defaults at an x86 session's emit level (VARKA-343, part
     343L), and not the matrix's base: the table documents the engine as an x86 session runs
     it, and a configuration that turns a check off does not take a row's domain away.";
   - the comment above the family "Floating casts" (four lines on master): its last sentence
     "A BIGINT's cast is not listed: the rows compile at the defaults' unknown emit level, where
     the plan declines L2F and L2D." becomes "A BIGINT's cast takes the exact AVX2 forms at emit
     level 2, which the rows compile at (VarkaMatrixRuns.CREDIT, VARKA-343 part 343L), and
     declines at an unknown level.";
   - in that family, directly after the row `CAST(CAST(l AS INT) AS FLOAT)` and its `domain`
     line, before the comment `// A choice's condition of the other kind than its values`:

         Row("CAST(l AS DOUBLE)", "the long split into exact halves at emit level 2, one "
           + "convertShape at level 3; declined at an unknown level"),
         Row("CAST(l AS FLOAT)", "rounded to odd without a mask at emit level 2, one "
           + "convertShape at level 3; declined at an unknown level"),

   - `renderJson`'s `"description"`: after "Anything absent runs on Spark's row engine. " add
     `"The rows compile at emit level 2, the level an x86 session without AVX512DQ resolves " +
     "to; at an unknown level, a host the session cannot read, a BIGINT conversion to or from " +
     "a floating type declines. "`.
4. `$S/VarkaEmittedBytesSuite.scala`:
   - `coverageOf`, the `val compiled = ...` block (its `if (form == "predicate")` and the two
     compiles) becomes

         // The defaults' IR where the defaults fuse the row, so the oracle pins what production
         // emits; a row only the credit level fuses (VARKA-343, part 343L: a BIGINT conversion
         // to or from a floating lane, refused at the unknown level) takes its IR from that
         // level, and the oracle's unknown-level emissions record the plan's refusal of it.
         def compileAt(options: VarkaEmitOptions) =
           if (form == "predicate") {
             VarkaExpressionCompiler.compilePredicate(claimed, cols, options,
               VarkaAdmission.NO_HOST_REFUSAL).toScala.map(_.fused)
           } else {
             VarkaExpressionCompiler.compile(Seq(Alias(claimed, "c")()), cols, options).toScala
           }
         val compiled = compileAt(VarkaEmitOptions.DEFAULTS)
           .orElse(compileAt(VarkaMatrixRuns.CREDIT))

     (the `compiled match` after it is unchanged);
   - `armDigest` gains a last parameter `admits: Seq[VarkaVectorIR] => Boolean = _ => true`,
     and its local `line` begins `if (!pinsAt(roots, lanes) || !admits(roots)) return ""`;
   - in the test "an arm the option matrix runs in another's place emits that arm's bytes", the
     comment that begins "Level 3 keys apart from the unknown level only" and the
     `widths.foreach` after it, to the end of the test, become

         // Level 3 keys apart from the unknown level by the BIGINT conversions to and from the
         // floating lanes it admits. The oracle holds such rows since part 343L of VARKA-343,
         // which the unknown level records as refused, so the two emit the same bytes over the
         // shapes both admit, and only there.
         def bothAdmit(roots: Seq[VarkaVectorIR]): Boolean =
           Seq(VarkaMatrix.base.withUseAVX(3),
             VarkaMatrix.base.withUseAVX(VarkaEmitOptions.USE_AVX_UNKNOWN)).forall(o =>
             VarkaKernelPlan.refusalOf(roots.asJava, o).isEmpty)
         assert(coverage.exists(r => !bothAdmit(r.roots)),
           "no coverage row is refused at the unknown level alone, so the narrowing is moot")
         widths.foreach { w =>
           assert(armDigest(_.withUseAVX(3), w, admits = bothAdmit) ===
             armDigest(_.withUseAVX(VarkaEmitOptions.USE_AVX_UNKNOWN), w, admits = bothAdmit),
             s"useAVX=3 emits other bytes than the unknown level at $w lanes")
         }

5. `$T/VarkaTypeConformance.java`, `CastPair.fusedIn`: the comment and the `if` between
   `String answer = ...` and `return answer.equals("fused");` become

         // The compiler writes the register at VarkaMatrixRuns.CREDIT, an x86 session's emit
         // level (VARKA-343, part 343L). A BIGINT cast to FLOAT or DOUBLE fuses there and at
         // level 3, and declines at the unknown level, the defaults', where the kernel plan
         // refuses it (VarkaKernelPlan.conversionRefusal); there it is owed, whatever the
         // register says.
         if (source.equals(DataTypes.LongType)
             && (target.equals(DataTypes.FloatType) || target.equals(DataTypes.DoubleType))
             && VarkaMatrixRuns.BASE.levelUnknown()) {
           return false;
         }

   and the class javadoc's sentence "The one exception is the {@code BIGINT} casts to
   {@code FLOAT} and {@code DOUBLE}, which the compiler admits by the options' {@code useAVX}
   level and which this class takes from that level rather than the register" ends "... which
   this class owes at the unknown level, whatever the register, written at
   {@code VarkaMatrixRuns.CREDIT}, says". 343b2a2 extends this `if` (its step 1d).
6. `$E/VarkaSuiteArm.scala`: import `org.apache.spark.sql.catalyst.expressions.codegen.varka.
   {VarkaEmitOptions, VarkaMatrixRuns}`; before `afterAll` add

         // The verdicts this trait records and the fused checks it runs are taken at the level
         // the records are written at (VarkaMatrixRuns.CREDIT's, VARKA-343 part 343L), so that
         // casts_suites.tsv agrees with casts_fused.tsv; a configuration that names a level
         // keeps its own. Put back after the suite.
         private var emitOptionsBefore: VarkaEmitOptions = _

         override protected def beforeAll(): Unit = {
           super.beforeAll()
           emitOptionsBefore = VarkaColumnarToRowExec.currentEmitOptions
           VarkaColumnarToRowExec.setEmitOptionsForTesting(
             VarkaMatrixRuns.credited(emitOptionsBefore))
         }

   and in `afterAll`, first in its `finally` block, before `super.afterAll()`:
   `VarkaColumnarToRowExec.setEmitOptionsForTesting(emitOptionsBefore)`.
7. `$EJ/VarkaCoverageHarnessSuite.java`: each of the eight `VarkaMatrixRuns.BASE` becomes
   `VarkaMatrixRuns.credited(VarkaMatrixRuns.BASE)` (a `.with...(...)` after one stays after
   it); the `@BeforeAll` method's javadoc, if any, need not change.
8. `$CT/VarkaFamilySwitchSuite.java`: `OPTIONS = VarkaMatrixRuns.BASE;` becomes `OPTIONS =
   VarkaMatrixRuns.credited(VarkaMatrixRuns.BASE);`.
9. `$S/VarkaCoverageCompositionFuzzSuite.scala`, `unbakedAlone`: the `.exists(...)` becomes
   `.exists(d => VarkaMatrixRuns.isUnbakedWidthDecline(VarkaMatrix.base, d.reason) ||
   VarkaMatrixRuns.isUnknownLevelDecline(d.reason))`, and its comment gains the sentence "So
   does a row the unknown emit level refuses alone, a BIGINT conversion to or from a floating
   lane (VARKA-343, part 343L), wherever the configuration names no level."
10. `$S/VarkaWidthAuditSuite.scala`, the test "at the host's preferred width, every shape is
    emitted ...": `val unexplained = unexplainedDeclines(census.declined, recorded)` becomes

          // A row only an x86 session's level fuses (VARKA-343, part 343L) declines at the
          // defaults' unknown level, which the audit emits at: the known limit, not a width's.
          val unexplained = unexplainedDeclines(census.declined, recorded)
            .filterNot { case (_, reason) => VarkaMatrixRuns.isUnknownLevelDecline(reason) }

    This test runs on its host alone (A.5.0's regeneration note); the lead runs it.
11. `dev/varka_census.py`:
    - the module docstring: after "casts.tsv's fused_today is derived (VARKA-486)." add "Both
      the cast matrix and the coverage table compile at emit level 2, an x86 session's
      (VarkaMatrixRuns.CREDIT, VARKA-343 part 343L), so a fused cell or row means fused on an
      x86 host at that level; the unknown level still declines a BIGINT conversion to or from
      a floating type." (wrapped as its neighbours);
    - `support_block`: in the paragraph after `"#### By type\n"`, before "An entry is a
      function", add `"A form is *fused* where the compiler fuses it at emit level 2, the level
      an x86 session without AVX512DQ resolves to; level 3 converts in one instruction what
      level 2 converts by exact forms. A session whose level is unknown (another architecture,
      an unread CPU feature list, or a 64-bit species under 256 bits without AVX512DQ) declines
      a BIGINT conversion to or from FLOAT or DOUBLE unless it names a level"` + `" with
      `spark.sql.codegen.varka.emit.useAVX`. "` as string pieces of the same paragraph; and at
      the end of the "#### Casts by mode" paragraph, before its `\n"`, add `" A fused cell is
      fused on an x86 host at emit level 2, as above."`. Run `python3 -m doctest
      dev/varka_census.py` after: it prints nothing.
12. `sql/varka/plans/m8/census_curated.txt`, the header: after the two lines that end
    "derived from m8/casts_fused.tsv, VARKA-606)" add

        # A fused type is fused at emit level 2, an x86 session's without AVX512DQ: the cast
        # matrix and the coverage table compile there (VARKA-343 part 343L); an unknown level
        # declines a BIGINT conversion to or from FLOAT or DOUBLE.

13. `sql/varka/plans/m8/PLAN.md`, row 343: after `**Started** (` insert `*343L built <date>,
    \`VARKA-343.md\` A.5: the cast matrix, the coverage table and the cast suites' record
    compile at emit level 2, an x86 session's; BIGINT to FLOAT and DOUBLE credited, production
    unchanged;* `. `VARKA-343.md` section 10: `### 343L as built (<date>)`, at most 12 lines,
    with the counts of command 4.

**What each generated record gains** (the regeneration of command 3):

| record | before | after 343L | the later parts on the same rule |
| :--- | :--- | :--- | :--- |
| `casts_fused.tsv` | `bigint float` and `bigint double`: `declined` x 3 | `fused` x 3 each | 343b2b: `float bigint`, `double bigint` |
| `casts.tsv` | the two pairs' `fused_today` empty | `LEGACY,ANSI,TRY`, `fused_forms` `all` | 343b2b: two more pairs |
| `casts_suites.tsv` | six lines `declined`, reason "... at an unknown emit level ..." | six lines `fused`, reason empty | 343b2b: the `FLOAT`/`DOUBLE` to `BIGINT` lines |
| `census.tsv` | cast aliases `float`, `double`: fused today `bool,byte,short,int,float,double` | `bool,byte,short,int,bigint,float,double` | 343b2b: alias `bigint` gains `float,double`; 342e: `dt_times_num` gains `float,double`; 342q: `dt_div_num` likewise |
| `coverage.json`, `docs/sql-varka.md` | | the two rows, the description sentence, the support table's captions and its bigint cells | 342e, 342q, 343b2b, 343b3 add their rows |
| `emitted_bytes.json` | | `lanes`: the two rows at both widths, each `<declined>` (the oracle emits at the unknown level); `option_arms`: every digest moves (a row is added) | each later row likewise |
| `results/VARKA-612-refused.txt` | `# 26 of 547 value rows refused` | `# 26 of 549 value rows refused` | |

**The tests that pinned the old answer, and how each is turned.** None asserted a decline of a
record: production is unchanged, so the tests that assert the unknown level's decline
(`VarkaEmitterFloatingCastSuite.unknownLevelRefusal`, `VarkaFloatingLaneSuite`'s two "unknown
emit level" assertions, `VarkaMatrixRuns.emissionKey`'s three classes) stay as they are and now
pin the known limit. What pinned "the records equal the defaults" is turned in steps 2 to 10:
the cast matrix and the coverage suite (compile at `CREDIT`), the bytes oracle (DEFAULTS
first, `CREDIT` for the rest; level 3 against the unknown level over the shapes both admit),
the conformance's `fusedIn` (owed at the unknown level), the cast suites' record, the coverage
harness and the family switches (`credited(BASE)`), the composition fuzz (a row the unknown
level refuses alone is left out, counted), the width audit (that decline explained).

**Commands, in order.**

1. `flock "$VARKA_HEAVY_LOCK" build/sbt catalyst/Test/compile sql/Test/compile`: `[success]`
   for both. Then `git diff --stat origin/master -- sql/catalyst/src/main sql/core/src/main`
   prints nothing.
2. `python3 -m doctest dev/varka_census.py`: prints nothing.
3. `dev/varka_regen_generated.sh --all --full` (`--full`, since only test files changed and
   the bare `--all` may skip the JVM steps): its last line begins `varka regen: done`.
4. Then: `grep -P '^bigint\t(float|double)\t' sql/varka/plans/m8/casts_fused.tsv` prints two
   lines ending `fused	fused	fused`; `awk -F'\t' '$1=="bigint" && ($2=="float" ||
   $2=="double") {print $9}' sql/varka/plans/m8/casts.tsv` prints `LEGACY,ANSI,TRY` twice;
   `awk -F'\t' '$10 ~ /343/ && $9 == ""' sql/varka/plans/m8/casts.tsv | wc -l` prints `12`;
   `grep -c 'unknown emit level' sql/varka/plans/m8/casts_suites.tsv` prints `0`; `awk
   -F'\t' '$1=="double"{print $8}' sql/varka/plans/m8/census.tsv` prints
   `bool,byte,short,int,bigint,float,double`; `grep -c '"sql" : "CAST(l AS DOUBLE)"\|"sql" :
   "CAST(l AS FLOAT)"' sql/varka/coverage.json` prints `2`; `sed -n 4p
   sql/varka/plans/m8/results/VARKA-612-refused.txt` prints `# 26 of 549 value rows refused;
   family, row, reason:` (another count of refused rows: stop and report, since the level
   would then reach the two-lane rule, which reads no level); the bytes check of A.5.0 prints
   `bytes: 0 committed shapes moved, 4 shapes added` (a moved shape: stop).
5. `python3 dev/varka_census.py --check`: prints nothing and exits 0.
6. `flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaCastMatrixSuite
   *VarkaCoverageSuite *VarkaEmittedBytesSuite *ConformanceSuite *VarkaFamilySwitchSuite
   *VarkaCoverageCompositionFuzzSuite *VarkaDivisorProofSuite *VarkaMaskFromWordSuite
   *VarkaMaskOfWordSuite *VarkaWarmupReachSuite *VarkaEmitterFloatingCastSuite'`: no test
   fails. (`*ConformanceSuite` is the 15 suites that use `VarkaTypeConformance`: 14 by type,
   `VarkaBigIntConformanceSuite`, `VarkaFloatConformanceSuite` and
   `VarkaDoubleConformanceSuite` among them, and `VarkaConformancePlantSuite`.)
7. `flock "$VARKA_HEAVY_LOCK" build/sbt 'sql/testOnly *VarkaCastWithAnsiOffSuite
   *VarkaCastWithAnsiOnSuite *VarkaTryCastSuite *VarkaCoverageHarnessSuite
   *VarkaCoverageDifferentialSuite *VarkaFloatingLaneSuite *VarkaFloatingCastBaselineQueriesSuite'`:
   no test fails.
8. `BUILD_SHEETS.md` 6, the commit, `python3 dev/varka_push_check.py` (under 400).

**Stop rules.** A suite of command 6 or 7 that fails on a coverage row the unknown level
refuses, and is not in B0 item 6's list: stop and report the suite and its line (a reader the
grep missed). A newly fused check of a Spark expression suite (step 6 reaches every
`VarkaSuiteArm` suite, not the cast suites alone) that finds a `varka` difference: stop and
report it; it is a wrong answer at level 2, not this part's to explain away.

**Planted fault.** In step 2 keep `VarkaEmitOptions.DEFAULTS` for the projection: command 4's
`casts_fused.tsv` lines stay `declined`, and `VarkaCastMatrixSuite` passes against the
regenerated file while `--check` reports the coverage row `CAST(l AS DOUBLE)` casting a pair the
matrix calls declined. Undo it.

**Done when** commands 1 to 8 print what they say.

**Not here:** any production change (the unknown level keeps declining); `D2L`, `F2L`
(343b2a2, 343b2b); `VarkaFloatingCastCompiler.java`.

**The hand-off: the state 343L leaves, which 343b2a2, 343b2b, 343b3, 342e and 342q read.**

1. `grep -c 'public static final VarkaEmitOptions CREDIT =' $T/VarkaMatrixRuns.java` prints
   `1`; `credited(...)`, `CREDIT_USE_AVX` (2) and `isUnknownLevelDecline(...)` are beside it.
   This is the premise every later sheet checks for 343L.
2. Production is master's: `VarkaKernelPlan.conversionRefusal` keeps both arms (B0 item 2 still
   prints `1`, and `grep -c 'is not built yet (row 343)' $CG/varka/VarkaKernelPlan.java` prints
   `1`); `VarkaConversionLowering.takesWideForm` still reads `convertsFallBack()`.
3. Every record that credits a fused form compiles at `VarkaMatrixRuns.CREDIT` (level 2), and
   every harness that holds the records' rows runs at `credited(...)` of its options. A
   coverage row that fuses at level 2 and not at the unknown level is legal; the bytes oracle
   records it as `<declined>` at its unknown-level emissions (so a later part's bytes check
   counts each such row once per width under "shapes added").
4. The two rows sit in "Floating casts" after `CAST(CAST(l AS INT) AS FLOAT)` and its
   `domain` line; the family still ends with `Row("CAST((f > 1.5F AND i > 3) AS INT)", "the
   mixed AND as its one or zero"))),`, which 343b2a2's hunk reads as context. A row that only
   level 2 fuses is never a family's first row (`VarkaCoverageDifferentialSuite` runs each
   family's first row through a session at the unknown level).
5. `awk -F'\t' '$10 ~ /343/ && $9 == ""' sql/varka/plans/m8/casts.tsv | wc -l` prints `12`.
6. `VarkaTypeConformance.CastPair.fusedIn` holds the `if` of step 5, which 343b2a2 extends to
   the floating sources to `BIGINT`.
7. A session test that must see a `BIGINT` conversion to or from a floating lane fuse sets
   `withBothConf(SQLConf.VARKA_EMIT_USE_AVX.key, "2")` (`VarkaSharedSessions`): the shared
   sessions pin the level to -1.
8. `VarkaFloatingCastCompiler.java`, `VarkaEmitterFloatingCastSuite.java`,
   `VarkaFloatingLaneSuite.scala` and `VarkaFloatingCastBaselineQueriesSuite.scala` are
   master's (343b2a2's 3-way applies meet no 343L hunk there).

#### Part 343b3: `time_from_seconds` over a floating value (build sheet, fix round)

- **Title:** `[VARKA-343] Fuse time_from_seconds over a floating value`. **Branch:**
  `varka-343b3-time-seconds` from master after 343b2b merged. **Builder:** Sonnet-class.
  **Size:** about 330 counted lines. Follow `BUILD_SHEETS.md`. Nothing of it is on a branch.
  *Changed in fix round 2 (review 1, findings 3, 5 and 7): one text, for a master with 343L
  and 343b2b merged; the session test is written out; the census field is named. Reworked on
  28 November 2026 after E2 (c): the form fuses at every named level and declines at the
  unknown one, so the session test runs at level 2.*
- **What it adds:** `time_from_seconds(x)` over `DOUBLE` and `FLOAT`: A.4.4's one guard on
  the product in nanoseconds, then `D2L`, at every named emit level (343b2b); at the unknown
  level `D2L` declines, and the form with it (343L's known limit). The coverage rows compile at
  level 2 (343L), so the census credits them.

**B0, the premises.**

1. 343b2b is merged: `grep -c 'emitAssembly' $CG/varka/VarkaConversionLowering.java` at least
   `2`.
2. `grep -c '"timeFromSeconds" ->' $CG/VarkaTimeCompiler.java`: `1`, and `grep -c 'private
   static Optional<VarkaVectorIR> timeFromUnits' $CG/VarkaTimeCompiler.java`: `1`.
3. `grep -c 'timeFromFloatingSeconds' $CG/VarkaTimeCompiler.java`: `0`.
4. Spark's double overload: `grep -A5 'def timeFromSeconds(seconds: Double): Long'
   sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/util/DateTimeUtils.scala` prints
   the NaN and infinity test and `(seconds * NANOS_PER_SECOND).toLong`; `grep -A9 'private def
   withTimeConversionErrorHandling' ...DateTimeUtils.scala` prints `if (nanos < 0 || nanos >=
   NANOS_PER_DAY)`. Together they are A.4.4's three raises.
5. `grep -n '"DOUBLE time_from_seconds(s)" + density' -A1
   $BJ/VarkaFloatingCastBaselineBenchmark.java`
   prints the section with `Fusion.NONE`.
6. 343L is merged: `grep -c 'public static final VarkaEmitOptions CREDIT ='
   $T/VarkaMatrixRuns.java`: `1`; and 343b2b: `grep -c 'conversion to BIGINT at an unknown emit
   level' $CG/varka/VarkaKernelPlan.java`: `1`. If not, stop (A.5).
7. `grep -n '^time_from_seconds|' sql/varka/plans/m8/census_curated.txt` prints
   `163:time_from_seconds|NUM|time||semantics|351,343|from a decimal or a double` (a registered
   line: name, types, result, fused today, need, rows, note; its fourth field, fused today, is
   empty); `grep -c 'Family("Time arithmetic"' $S/VarkaCoverageSuite.scala`: `1`.

**Steps.**

1. `$CG/VarkaNodeCompiler.java`, `floatingDecline`'s pass list: after the floating casts'
   clause add

         // time_from_seconds over a floating value (VARKA-343, part 343b3): one guard on the
         // product in nanoseconds, then D2L.
         || (e instanceof StaticInvoke si && VarkaTimeCompiler.isTimeTarget(si)
             && "timeFromSeconds".equals(si.functionName()))

2. `$CG/VarkaTimeCompiler.java`, the case `"timeFromSeconds"`: before its `return
   timeFromUnits(...)` add

         if (VarkaFloatingCompiler.floatingLane(args.get(0).dataType()).isPresent()) {
           return timeFromFloatingSeconds(args.get(0), inputs, literals, sink);
         }

   and after `timeFromUnits` add

         /**
          * {@code time_from_seconds} over a {@code FLOAT} or {@code DOUBLE} value (VARKA-343,
          * part 343b3, `VARKA-343.md` A.4.4): Spark's {@code (long) (x * 1e9)}, raising on NaN,
          * on an infinity and where the nanoseconds leave {@code [0, NANOS_PER_DAY)}, in every
          * mode. {@code (long) p} is in that range exactly where {@code -1 < p < 8.64e13}, so one
          * floating guard on the product declines every batch Spark raises on.
          */
         private static Optional<VarkaVectorIR> timeFromFloatingSeconds(
             Expression seconds,
             VarkaSlotTable inputs,
             VarkaSlotTable literals,
             DeclineSink sink) {
           Optional<VarkaVectorIR> x = VarkaNodeCompiler.compileNode(seconds, inputs, literals,
               sink);
           if (x.isEmpty()) {
             return x;
           }
           VarkaVectorIR s = x.get();
           if (s.laneType() == LaneType.FLOAT) {
             s = new ConvertLane(s, LaneType.DOUBLE);
           } else if (s.laneType() != LaneType.DOUBLE) {
             return sink.decline("a " + seconds.dataType().simpleString() + " operand on the "
                 + s.laneType() + " lane", seconds);
           }
           VarkaVectorIR p = new IntArith(IntOp.MUL, Overflow.WRAP, s,
               VarkaNodeCompiler.doubleSlot((double) DateTimeConstants.NANOS_PER_SECOND, sink));
           VarkaVectorIR g = GuardedRange.floating(p, Math.nextUp(-1.0),
               Math.nextDown((double) DateTimeConstants.NANOS_PER_DAY), Overflow.FAIL);
           return Optional.of(new ConvertLane(g, LaneType.LONG));
         }

   adding the imports of `ConvertLane` and `LaneType` from `VarkaVectorIR` if the file lacks
   them (`GuardedRange`, `IntArith`, `IntOp`, `Overflow` are imported there already).
3. `$BJ/VarkaFloatingCastBaselineBenchmark.java`: the section `DOUBLE time_from_seconds(s)`
   takes `fusesToBigint ? Fusion.WHOLE : Fusion.NONE` (true at every named level after 343b2b,
   false at -1), and its javadoc says so.
4. `sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaFloatingLaneSuite.scala`, one
   test, at the end of the section of the interval tests (before the comment block that begins
   "What still declines, by name"), with the value above it:

         private val floatSeconds = "varka_float_seconds"

         // varka-session-lint: allow time_from_seconds' path test over its own cached fixture, and
         // the errors a declined batch raises, as the user sees them
         test("time_from_seconds over a DOUBLE and a FLOAT fuses at the edges of the day, and a " +
             "value outside it declines its batch and raises the row engine's error") {
           def cause(t: Throwable): Throwable =
             if (t.getCause == null || (t.getCause eq t)) t else cause(t.getCause)
           // D2L fuses at a named level; the shared sessions keep the unknown one (343L
           // hand-off item 7).
           withBothConf(SQLConf.VARKA_EMIT_USE_AVX.key, "2") {
             withBothConf(SQLConf.TIME_TYPE_ENABLED.key, "true") {
               // Spark admits a FLOAT argument; without that the FLOAT half has nothing to fuse.
               assert(spark.sql("SELECT time_from_seconds(CAST(1.5 AS FLOAT)) AS v").schema.nonEmpty)
               // A.4.4's cells: both zeros, the double next above -1e-9 (its product rounds to a
               // count of 0), the last double second of the day, the float below 86400 (Spark
               // answers 86399992187500 nanoseconds), and nulls.
               val rows = Seq[(java.lang.Double, java.lang.Float)]((0.0, 86399.99f), (-0.0, 0.25f),
                 (-9.999999999999999E-10, null), (86399.99999999999, 0.0f), (0.5, -0.0f),
                 (12345.6789, 1.0f), (null, null))
               for (session <- Seq(spark, varkaSpark)) {
                 session.createDataFrame(spark.sparkContext.parallelize(
                   rows.map { case (s, f) => Row(s, f) }, 1),
                   StructType(Seq(StructField("s", DoubleType), StructField("f", FloatType))))
                   .createOrReplaceTempView(floatSeconds)
                 session.catalog.cacheTable(floatSeconds)
               }
               for (ansi <- Seq(false, true)) withAnsi(ansi) {
                 val q = "SELECT time_from_seconds(s) AS a, time_from_seconds(f) AS b " +
                   s"FROM $floatSeconds"
                 withClue(s"$q, ansi $ansi") {
                   val actual = varkaSpark.sql(q)
                   assertFused(actual.queryExecution.executedPlan)
                   checkAnswer(actual, spark.sql(q))
                 }
                 // Each raises in every mode; a column operand, so the optimizer folds nothing.
                 for (e <- Seq("s - 1.0E-9D", "s + 86400.0D", "s + CAST('NaN' AS DOUBLE)",
                     "s + CAST('Infinity' AS DOUBLE)")) {
                   val raising = s"SELECT time_from_seconds($e) AS a FROM $floatSeconds " +
                     "WHERE s = 0.0D"
                   withClue(s"$raising, ansi $ansi") {
                     val actual = varkaSpark.sql(raising)
                     assertFused(actual.queryExecution.executedPlan)
                     val got = intercept[Exception](actual.collect())
                     val want = intercept[Exception](spark.sql(raising).collect())
                     assert(cause(got).getClass === cause(want).getClass)
                     assert(cause(got).getMessage === cause(want).getMessage)
                   }
                 }
                 val floatRaising = "SELECT time_from_seconds(f + 86399.75F) AS a " +
                   s"FROM $floatSeconds WHERE f = 0.25F"
                 withClue(s"$floatRaising, ansi $ansi") {
                   val actual = varkaSpark.sql(floatRaising)
                   assertFused(actual.queryExecution.executedPlan)
                   val got = intercept[Exception](actual.collect())
                   val want = intercept[Exception](spark.sql(floatRaising).collect())
                   assert(cause(got).getClass === cause(want).getClass)
                   assert(cause(got).getMessage === cause(want).getMessage)
                 }
               }
             }
           }
         }

   (*Reworked after E2 (c)*: the outer `withBothConf` of `VARKA_EMIT_USE_AVX`.) `withBothConf`,
   `withAnsi` and `assertFused` are `VarkaSharedSessions`' (in
   `sql/core/src/test/scala/org/apache/spark/sql/execution/`, on master); `Row`, `StructType`,
   `StructField`, `DoubleType`, `FloatType` and `SQLConf` are imported by the suite already.
   `s - 1.0E-9D` at `s = 0.0` is `-1e-9`,
   whose count `(long) -1.0` is -1; `f + 86399.75F` at `f = 0.25F` is `86400.0F` exactly. The
   local `cause` is the root-cause walk 342d adds as `rootCause`; it is local here so that this
   part does not depend on 342d's order. **Stop rules:** if the first assertion fails, Spark
   refuses a `FLOAT` argument: stop and report (the `FLOAT` half then has nothing to fuse); if
   a raising query answers on the Varka side, the guard lets a raising lane through: stop.
5. Coverage rows in the family "Time arithmetic" (`$S/VarkaCoverageSuite.scala`, after the row
   `time_from_micros(time_to_micros(t))` and its domain):
   `Row("time_from_seconds(x)", "the seconds times 10^9 in the double lane, one guard on the
   product for Spark's three raises, then D2L", domain = Some("x >= 0.0D AND x < 86400.0D"))`
   and `Row("time_from_seconds(f)", "the float widened exactly, then the same", domain =
   Some("f >= 0.0F AND f < 86400.0F"))`. `sql/varka/plans/m8/census_curated.txt`: the line of
   B0 item 7, its fourth field (fused today, empty) becomes `float,double`, so the line reads
   `time_from_seconds|NUM|time|float,double|semantics|351,343|from a decimal or a double`. If
   command 5's `--check` says no coverage row has a `TimeFromSeconds` node over a `float`
   operand (Spark cast the float to a double), the claim becomes `double` alone and the outcome
   records it.
6. `PLAN.md` row 343 and `VARKA-343.md` section 10, as the other parts.

**Commands.** The compile (`[success]`); `flock "$VARKA_HEAVY_LOCK" build/sbt
'catalyst/testOnly *VarkaExpressionCompilerSuite'` and `'sql/testOnly *VarkaFloatingLaneSuite
*VarkaFloatingCastBaselineQueriesSuite *VarkaCoverageHarnessSuite'`: no test fails;
`dev/varka_regen_generated.sh --all` (bare): last line begins `varka regen: done`; `grep -c
'"sql" : "time_from_seconds(' sql/varka/coverage.json` prints `2`; the bytes check: `bytes: 0
committed shapes moved, 4 shapes added`; `python3 dev/varka_census.py --check` prints nothing,
and `awk -F'\t' '$1=="time_from_seconds"{print $8}' sql/varka/plans/m8/census.tsv` prints
`float,double` (column 8 of `census.tsv` is `fused_today`); `BUILD_SHEETS.md` 6.

**Planted fault.** The guard's high end `Math.nextDown(...)` becomes the day itself
(`(double) DateTimeConstants.NANOS_PER_DAY`): the session test's `s + 86400.0D` then answers
on the Varka side, and its `intercept` fails.

**Not here:** the decimal argument (row 351); the integral arms.

#### Part 343t: the two twins 343b1 owes (build sheet, fix round)

- **Title:** `[VARKA-343] Exhaust the split and the rounding to odd at half width`. **Branch:**
  `varka-343t-twins` from master. **Builder:** Sonnet-class. **Size:** about 120 counted lines.
- **A correction to 3.2** (fix round, 28 November): 3.2 writes the second twin "unsigned 32-bit
  to `binary16` through a `binary32` intermediate". `binary16`'s largest finite value is
  65504, so every input from `2^23` up, the only ones the shift serves, rounds to infinity
  whatever the shift does: that twin tests nothing (`scratch-343/check_twins_343t.py` of the
  planning folder: with the sticky bit dropped, the `binary16` form gives a finite value on 0
  of 262144 inputs above `2^23`). The twin below rounds to `binary16`'s precision, 11 bits,
  with `binary32`'s exponent range, on the float's bits, against the same rounding computed in
  integers.

**B0.** `grep -c 'splitTwin\|roundToOddTwin' $T/VarkaEmitterWideConversionSuite.java`: `0`;
`grep -c 'VarkaEmitterWideConversionSuite' sql/varka/ci/sweeps.tsv`: `0`.

**Steps.** In `$T/VarkaEmitterWideConversionSuite.java` add, beside its other helpers:

    /** 2.3's split at half width: an int to float by 16-bit halves, as L2D by 32-bit ones. */
    static float splitTwin(int v) {
      float m = 0x1.8p23f;
      int hi = v >> 16;
      int lo = v & 0xFFFF;
      float dh = (Float.intBitsToFloat(Float.floatToRawIntBits(m) + hi) - m) * 0x1p16f;
      float dl = Float.intBitsToFloat(lo | Float.floatToRawIntBits(0x1p23f)) - 0x1p23f;
      return dh + dl;
    }

    /** f's significand rounded to 11 bits, ties to even, on its bits (binary16's precision). */
    static float round11(float f) {
      int b = Float.floatToRawIntBits(f);
      return Float.intBitsToFloat((b + 0xFFF + ((b >>> 13) & 1)) & ~0x1FFF);
    }

    /** L2F's rounding to odd at half width: an unsigned m to 11 bits through binary32. */
    static float roundToOddTwin(int m) {
      long u = Integer.toUnsignedLong(m);
      if (u < (1L << 23)) {
        return round11((float) u);
      }
      long t9 = (u >>> 9) | (((u & 0x1FF) + 0x1FF) >>> 9);
      return round11((float) t9 * 0x1p9f);
    }

    /** u rounded to 11 significant bits, ties to even, in integers. */
    static long round11Exact(long u) {
      int n = 64 - Long.numberOfLeadingZeros(u);
      if (n <= 11) {
        return u;
      }
      int shift = n - 11;
      long q = u >>> shift;
      long rem = u & ((1L << shift) - 1);
      long half = 1L << (shift - 1);
      if (rem > half || (rem == half && (q & 1) == 1)) {
        q++;
      }
      return q << shift;
    }

and two tests, `twinSlices` ("the split and the rounding to odd at half width are Java's cast
and the integer rounding over every 97th int") and `twinsWhole` (the same over every int, opt
in under `-Dvarka.sweep=true`, `assumeTrue` as `VarkaEmitterFloatToIntegralSuite.twinsWhole`
does), each a parallel `LongStream` over the inputs asserting `splitTwin(v) == (float) v` and
`roundToOddTwin(m) == (float) round11Exact(Integer.toUnsignedLong(m))`, failing with the first
bad input in hex, as `twins` of `VarkaEmitterFloatToIntegralSuite` does. Add the suite's line
to `sql/varka/ci/sweeps.tsv`: `VarkaEmitterWideConversionSuite	java	varka.sweep	class	-
varka-sweeps	the split and the rounding to odd at half width over every int; not yet timed`,
tab-separated as its neighbours.

**Checked in Python** (`scratch-343/check_twins_343t.py`, last line): `343t twins: the split
equals (float) v on 787212 ints, 0 differ; round to odd through binary32 equals the integer
rounding to 11 bits on 787212 unsigned ints, 0 differ`; with the sticky bit dropped, 8176 of
262144 inputs above `2^23` differ.

**Commands.** The compile; `flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly
*VarkaEmitterWideConversionSuite'`: no test fails, `twinsWhole` skipped; then once with
`'set LocalProject("catalyst") / Test / javaOptions += "-Dvarka.sweep=true"'` before it: no
test fails or is skipped; its time replaces "not yet timed". **Planted fault:** in
`roundToOddTwin` drop the sticky term (`t9 = u >>> 9`): `twinSlices` fails. **Not here:** any
emitted code.

#### Part 343r: the measurement (build sheet, fix round)

- **Title:** `[VARKA-343] Results: the floating casts on their kernels`. **Branch:**
  `varka-343r-results` from master after 343b3 (and 343b2b) merged. **Builder:** Sonnet-class;
  the dispatches are the lead's unless the builder has `gh` rights. **Size:** the benchmark
  section below (about 60 counted lines), results files (not counted), about 40 plan lines.
- **It measures the assembly only** (*reworked on 28 November 2026 after E3 (a)*): the
  truncated magic is not built before January 2027 (343b2m), so 4.4's rule (the assembly within
  1.5 times the magic) is not scored here; it moves to 343b2m's results.
- **First, a correction** (fix round): `VarkaWideConversionBenchmark` (catalyst,
  `sql/catalyst/src/test/java/org/apache/spark/sql/VarkaWideConversionBenchmark.java`) times
  `L2D` and `L2F` only. So this part adds a second section to it, "a double cast to bigint by
  the AVX2 assembly", with the arms `cast(x AS bigint), the assembly` (`useAVX=2`,
  `wideConversionForm=XOR_SPLIT`) and `cast(x AS bigint), a scalar loop`, over doubles of every
  exponent inside `2^51` (the window rung, kept so that 343b2m can add its arm on the same
  inputs) and of magnitudes `2^52` to `2^62`, each kernel checked against Java's `(long)`
  before it is timed, as the existing section's `check` does. Built on the existing section's
  `runRung` and `check`, with the column of doubles `(double)` of its longs.
- **Steps:** the results procedure of A.5.0 for `VarkaWideConversionBenchmark` (its files are
  under `sql/catalyst/benchmarks/`), `VarkaFloatingCastBaselineBenchmark` and
  `VarkaFloatingBaselineBenchmark` (under `sql/core/benchmarks/`), each at both widths.
- **Score**, into section 10: 4.3 (`DOUBLE` to `BIGINT` under ANSI at least three times
  faster than 39.8 ns a row at 256 bits; `time_from_seconds` at least three times) from
  `python3 dev/varka_bench_diff.py --git master <file> --control "spark row path"`; and the
  new section's assembly over the scalar loop, by minimums, recorded as a number with no rule
  attached. 4.4: "not scored, the magic arm is January's (343b2m)".
- **At 128 bits** the benchmark sessions resolve their level from the host
  (`fusesLongCasts`' javadoc), and the EPYC 7763 under `-XX:MaxVectorSize=16` resolves the
  unknown level (`VarkaSessionEmit.MAGIC_MIN_LONG_BITS`, 256): there the `BIGINT` sections and
  `time_from_seconds` time the row path by design, the known limit of 343L. **The two-lane
  rule** therefore reads `DOUBLE CAST(x AS INT), ANSI` alone: if, at 128 bits with no nulls,
  its "varka on" rate is under the row path's, record it and file a row; decline nothing here.

### A.6 Tests, proofs, fuzzing and coverage

| suite or file | part | what it holds |
| :--- | :--- | :--- |
| `VarkaEmitterFloatingGuardSuite` (new, JUnit) | 343b2a1 | the guard alone, both modes, three kernels |
| `VarkaEmitterFloatToIntegralSuite` (new, JUnit) | 343b2a2, 343b2b | the casts, the bounds against Spark, the AVX2 forms, the twins and sweeps |
| `round_to_odd.smt2` | merged | `L2F` |
| `round_to_odd.smt2`, its new `D2L` section | 343b2p, January 2027 | the truncated magic over `|x| <= 2^51`, and the first doubles past it where it fails |

Fuzzing: the IR grammar's floating casts corpus draws a conversion into an integral lane
(343b2a2, by the branch's hunk). The planner's claim that 343b2a1 draws a floating guard in
both modes "on a seed of its own" was wrong: the branch draws no guard, and this plan adds
none; the guard is held by its own suite and, through `try_cast`, by the Spark fuzzer's
coverage rows. Coverage: the rows named in A.5.

### A.7 Predictions registered before any run

Section 4's stand. Added: 343b2a1 moves no line of `emitted_bytes.json`; 343b2a2 adds the
eight rows' shapes and moves no committed one; `VarkaEmitterFloatToIntegralSuite` under 30 s
after 343b2b with the sweep slices (22 s on the branch); `VarkaEmitterFloatingGuardSuite`
under 15 s (a registered guess: it compiles 3 kernels at 3 widths per test).

### A.8 Who builds it

*Reworked on 28 November 2026 after E1 (a), E2 (c) and E3 (a).* Before January 2027 every part
is Sonnet-class: 343L, 343b2a1, 343b2a2, 343b2b, 343b3, 343t, 343r. 343L changes test code and
captions only, each edit written out; the code of the others is written out or on a branch, and
its hard parts are checked in A.4. January 2027, full strength, not gates before then: 343b2p
(the encoding of a double's bits in the floating-point theory, under both solvers' limits) and
343b2m (a bounded lowering with its guard on the child, after its proof). The earlier text, "343L
at full strength", held for the answer (b), which E2 (c) replaced.

### A.9 Risks

1. A hunk that merges may not compile on master. Held by each part's first command.
2. A reader of `lo()` or `hi()` added to master since the table of A.4.1 was taken. Held by
   343b2a1's `nullGuard` and `failGuard`, which run every reader the emitter reaches.
3. Masks at two 64-bit lanes (a `DOUBLE` guard on a 128-bit x86 host) fall back to Java
   loops. Not a wrong answer; 343r's rule reads the cost.
4. The branches lived on one laptop. Held: they are on the fork since 28 November 2026 (A.12
   question 3, A.5.0).
5. A reader of the records that requires a row to fuse at the unknown level and that 343L's
   grep missed. Held by 343L's commands 6 and 7 and its stop rule; the option matrix runs every
   suite.
6. A credit at level 2 that a level-3 host does not have. None is known (level 3 converts in
   one instruction what level 2 converts by exact forms); 343b2a2's step 1d makes the
   conformance suites say so at `useAVX=3` for the casts to `BIGINT`.

### A.10 The house rules that bind this row

From `sql/varka/AGENTS.md`: "A new bounded lowering comes with its proof file" (343b2m's
magic, after 343b2p, January 2027; nothing bounded is built before then); "Never remove, skip
or weaken a failing test" (343L points the records' readers at the credit level and deletes no
test; 343b2b deletes `magicDeclines` because the arm it tests is not built, and 343b2m brings it
back);
generated files are regenerated, never edited (`BUILD_SHEETS.md` 5); "A pull request stays
under 1000 changed lines". Do not: copy a branch's `PLAN.md` hunk; edit `coverage.json`,
`emitted_bytes.json`, `casts_fused.tsv`, `casts.tsv` or the census by hand; push or open a
pull request (`BUILD_SHEETS.md` 7).

### A.11 Outcome

To be written when the row closes.

### A.12 Questions for the owner

*Changed in fix round 2: the owner answered the questions on 28 November 2026; the plan follows
the answers.*

1. **The unknown emit level.** (a) Leave it declining: the four `BIGINT` pairs, and row 342's
   day-time forms, stay uncredited in `casts.tsv` and the census, each with the reason "fuses
   at a named emit level; the records compile at the unknown one"; then 343b2a2's B0 and
   commands and 342e, 342q and 343b3 would have their no-343L texts. (b) Take the exact forms
   there (343L). *Recommended: (b)*: the forms are exact on every host, a session already
   fuses these casts, and only the records lag. **Answered on 28 November 2026: (b), build
   343L.** So 343L is first in the order (A.5), built at full strength before 1 December, and
   every later sheet has the one text for a master with 343L merged; the no-343L variants are
   gone. **Answered on 28 November 2026, later: (c)** (decision E2): 343L does not take the
   software forms at the unknown level; the cast matrix, the coverage table and every suite that
   writes or holds a crediting record compile at emit level 2, an x86 session's, and a cell
   means "fused on an x86 host at that level" (A.4.5). (b) was replaced because it changed
   production (what the defaults fuse, the shape key, every bytes record) and so needed a
   full-strength builder before 1 December; (c) credits the same eight pairs with production
   unchanged, which a Sonnet-class builder can do.
2. **The `DATE` pairs**, always null under LEGACY. (a) Declined with the reason "always null;
   a kernel that reads no column is not compiled", the decline text in `floatingDecline`
   saying so; one design with row 332's `DATE` pairs to the integral types. (b) A null leaf
   that reads the column's word: one more part, which would also serve row 332's `DATE` pairs;
   343b2a2 would then keep the old decline text, and the two `date`-to-floating pairs would
   join the fused count. *Recommended: (a).* **Answered on 28 November 2026: (a), declined
   with that reason.** 343b2a2 rewords the decline (its step 4), and item 3's "declined with
   its reason" is met for the two pairs.
3. **Push the branches to the fork** so the parts' starting diffs survive the laptop. *Recommended:
   yes.* **Answered on 28 November 2026: done.** The four live branches are on the fork
   `MaxGekk/spark` as `varka-343b2a-r2` (`96047835ca4`), `varka-343b2b-r2` (`163aac5ecd6`),
   `varka-342de-r` (`688414d4e10`) and `varka-339-ntz-arith-laptop` (`6476788b31a`; the fork's
   `varka-339-ntz-arith` is an older, different history and is not used). Each sheet takes its
   code from the local branch, or from the fork branch of the same commit when the local one
   is gone (A.5.0, "Where the code comes from"). This row's parts read the first two.
4. **The truncated magic and its proof** (decisions E3 (a) and E1 (a), 28 November 2026): 343b2b
   builds only the exact assembly of `D2L` and `F2L` below level 3; the magic arm (343b2m) and
   its proof (343b2p) move to January 2027, when a full-strength builder is back, and gate
   nothing before then. 343r measures the assembly only.

### A.13 What only a JVM settles

| claim | command |
| :--- | :--- |
| each part's merged code compiles and its suites pass | the part's commands |
| `F2I`, `D2I` and level 3's `F2L`, `D2L` answer Java's cast on the host (C2's instruction selection) | `build/sbt 'catalyst/testOnly *VarkaEmitterFloatToIntegralSuite'` |
| float against double rounding in `castBounds` (the script rounds through `struct`) | the suite's `bounds` test against Spark's `FloatExactNumeric` |
| the assembly is vector code with no call under AVX2 | `JDK_JAVA_OPTIONS=-XX:UseAVX=2 dev/varka_emit.sh "CAST(x AS BIGINT)" --columns x:double --options useAVX=2 --asm` |
| no record reader beyond 343L's list requires a row to fuse at the unknown level; the level-3 and unknown digests agree over the shapes both admit | 343L's commands 6 and 7, then the option matrix |
| a session on an unreadable host still declines (the known limit) | `build/sbt 'sql/testOnly *VarkaFloatingLaneSuite'` (its "unknown emit level" assertions, master's) |
| the regeneration, the option matrix, the runners' results | the parts' commands, then 343r |

## 0. What exists now (admission check)

From the code at `7ddd8f6715e` (27 October 2026; Varka paths under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` unless named, Spark
paths under `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/`), from open PR #1065
(branch `varka-341-semantics`, the floating comparisons) and from row 342's draft plan (branch
`varka-342-plan`). The row's text is the milestone plan's 2.2 and 2.7, "The conversions (343)".

1. **Every floating cast declines by name, and 26 pairs are this row's.** `floatingDecline`
   answers "a cast to or from a floating type (row 343)" for every `Cast` touching a floating type
   (`VarkaNodeCompiler.java:331-352`, `:341`). `m8/casts.tsv` names 343 for 26 pairs: the five
   integral and boolean sources to `FLOAT` and `DOUBLE` (10), the two floating sources to
   `BOOLEAN`, the four integral types and the other floating type (12), the two identities, and
   `DATE` to both (LEGACY only, always null). `casts_fused.tsv` holds 24 of them, the identities
   absent by row 328's rule (2.5): the 22 others `declined` in every mode, the `DATE` pairs
   `declined` under LEGACY and `illegal` under ANSI and TRY.
2. **The integral casts are the structure to share, and their TRY arm is unbuilt.** `BIGINT` to
   `INT` is `NarrowLane` under LEGACY and `NarrowLane(GuardedRange(x, int range))` under ANSI;
   TRY declines naming row 332 (`VarkaNodeCompiler.java:692-711`). `INT` to `BIGINT` is
   `WidenLane` and a narrow source widens to the int lane at its load (`:505-528`). `GuardedRange`
   (`varka/VarkaVectorIR.java:536`) only declines a batch and holds `long` bounds; the nodes that
   null a valid lane are `MakeDate` with `failOnError` false (`:1352-1356`) and `IntArith` in
   `Overflow.NULL` (`:672`, the enum at `:415`). Row 332, the integral casts
   in three modes, has no plan yet, and `byte` and `short` to `bigint` decline today.
3. **No node converts between the kinds, and no kernel holds two lane counts across them.**
   `LaneType.isWiderThan` orders the integral lanes only (`varka/VarkaVectorIR.java:86-130`), so
   `WidenLane` (`:563`) and `NarrowLane` (`:605`) refuse a floating side. `emitShapeConversion`
   emits `I2L` and `L2I`, part 0, and throws on any other pair (`varka/VarkaVectorWalk.java:
   524-541`). `Analysis` refuses a floating node in an integral emission and the reverse
   (`varka/Analysis.java:1236-1241`); `emissionLane` drives a floating tree at its own lane and
   `lanesShare` gives a floating root a kernel of its own (`varka/VarkaKernelPlan.java:170-228`).
   #1065 leaves the same-width mixed kernel and the `VectorMask.cast` crossing owed (its
   `VARKA-341.md`, section 5.1, "Still owed").
4. **What Spark computes** (`expressions/Cast.scala`):
   - **To a floating type**: `(float) c` and `(double) c` (codegen `:2626-2700`; interpreted
     `numeric.toFloat` and `toDouble`, `:1426-1470`), with no error in any mode, so TRY is
     LEGACY's answer. JLS 5.1.2 rounds `int` to `float` and `long` to either to nearest from the
     exact value; `float` to `double` is exact. `BOOLEAN` gives `1.0` or `0.0`; `DATE` is null.
   - **To an integral type**: LEGACY is `(int) c`, `(long) c`, and `(byte) c` and `(short) c`,
     which JLS 5.1.3 defines as the `int` conversion then the wrap: NaN to 0, saturation at the
     `int` or `long` range. ANSI calls `FloatExactNumeric` and `DoubleExactNumeric`
     (`types/numerics.scala:121-185`) or `CastUtils.floatToByteExact` and its kin
     (`CastUtils.java:79-97`; `Cast.scala:2437-2476`): `floor(v) <= MAX && ceil(v) >= MIN` in
     double arithmetic, else `CAST_OVERFLOW`, NaN failing both. `MAX` for `BIGINT` is
     `Long.MaxValue.toDouble`, `2^63`, so `2^63` passes and saturates. TRY sets `ansiEnabled`
     (`:720-722`; the legacy exemption is for string sources only, `:1602-1612`) and wraps the
     cast in a catch that answers null (`:1586-1597`, `:1706-1716`).
   - **To `BOOLEAN`**: `x != 0` (`:871-874`): NaN true, `-0.0` false.
   - **`time_from_seconds`** is a `StaticInvoke` of `DateTimeUtils.timeFromSeconds`
     (`expressions/timeExpressions.scala:880`); a `FLOAT` argument resolves to the `Double`
     overload by commons-lang3's widening match (`expressions/objects/objects.scala:206-207`).
     NaN and the infinities raise, then `(long) (s * 1e9)`, then a raise unless `0 <= nanos <
     NANOS_PER_DAY` (`util/DateTimeUtils.scala:1119-1135`, `:1161-1166`), in every mode.
5. **What the JDK does** (`jdk-25-ga` source, local):
   - `VectorOperators` declares all ten conversions (`VectorOperators.java:700-732`); the Java
     fallback casts lane by lane (`AbstractVector.java:619`, `:669`), so a fallback is the JVM's
     answer, slow and allocating, never a wrong one.
   - x86 refuses `L2F`, `L2D`, `F2L` and `D2L` without AVX512DQ (`cpu/x86/x86.ad:1757-1777`).
     `F2I` and `D2I` on AVX2 convert, then fix NaN to 0 and saturate
     (`cpu/x86/c2_MacroAssembler_x86.cpp:5040-5075`, `:5186`), and a sub-word target masks and
     packs (`:5227-5249`), which is Java's `(byte) (int) x`.
   - aarch64 converts `long` to `float` lane by lane with the scalar `scvtf` on NEON and with
     SVE's `scvtf` from D to S (`cpu/aarch64/aarch64_vector.ad:4468-4495`): one rounding each.
     `D2L` is `fcvtzs`, saturating with NaN to 0 (`:4627-4640`), Java's answer.
   - The census: the laptop reads every conversion intrinsic at 512, 256 and 128 bits
     (`operator_census.json`); the EPYC 7763 reads `L2D`, `D2L` and `L2F` as fallbacks at 256 and
     128 and the rest intrinsic; the Neoverse N2 reads all intrinsic
     (`results/VARKA-376-census-epyc-7763.json`, `results/VARKA-376-census-neoverse-n2.json`).
     There is no `F2L` cell, no floating `ADD`, `SUB` or `MUL` (342's item 0.7), no
     reinterpretation and no `long` shift by a vector count.
   - The host gate exists: `VarkaEmitOptions.convertsFallBack` answers `UseAVX < 3` and reaches
     the shape key (`varka/VarkaEmitOptions.java:594-621`); the 64-bit constant division reads
     it (`varka/VarkaDivisionLowering.java:361`). The `useAVX` option sets it in a test, and a
     session sets it from the driver's JVM once row 538 feeds it the host.
6. **Two exactness traps, checked here.** `BIGINT` to `FLOAT` through `DOUBLE` rounds twice:
   `2^53 + 2^29 + 1` gives `2^53` against `2^53 + 2^30` (2.7, checked again in Python). And the
   magic number rounds where the cast truncates: `rawbits(x + 0x1.8p52) - 0x4338000000000000` is
   `(long) rint(x)`, right for 344 after its `rint` and wrong for a cast at `x = 0.7` (1 against
   0) and `x = 1.5` (2 against 1); `0.5` is no counterexample, since `rint(0.5)` is 0.
7. **The baseline covers two pairs.** `VarkaFloatingBaselineBenchmark` times `DOUBLE` to `BIGINT`
   at 39.8 ns a row and `FLOAT` to `DOUBLE` at 38.4 on Spark's row path, 256 bits, no nulls, in
   the session's default mode, ANSI (`VarkaFloatingBaselineBenchmark-jdk25-runner-epyc7763-
   results.txt` in `sql/core/benchmarks/`). Nothing times `BIGINT` to `FLOAT`, `INT` to `DOUBLE`,
   `DOUBLE` to `INT`, TRY, a boolean cast or `time_from_seconds`.
8. **What 342 waits for** (its section 0 and 2.4): `I2D` for `i / j` and the functions over an
   integral column, `F2D` for `f / g` and the functions over a `FLOAT`, `L2D` for a `BIGINT`
   division and the day-time interval, `D2I` and `D2L` for the interval rounding, a
   `GuardedRange` at a floating lane, and a kernel of two lane counts across the kinds.
9. **The boolean and date pieces are other rows'.** `CondValue` takes the int and long lanes
   (`varka/VarkaVectorIR.java:1224`); a `BOOLEAN` output is row 27's (planned); a typed null leaf,
   which an always-null `DATE` cast is, is row 333's (`VARKA-333.md`, planned).

### What differs from the row text and the milestone plan

- **NEON needs no round-to-odd** (item 0.5): C2's `L2F` on aarch64 rounds once. Round-to-odd is
  the AVX2 form only.
- **The cast truncates, the magic number rounds** (item 0.6): the AVX2 `D2L` truncates first.
- **AVX2 can be whole-range** with exact pieces: `L2D` by a split whose parts are exact, so only
  the final add rounds, and `D2L` by assembling the integer from the exponent. The row's bounded
  form stays as the option's other arm (question 1).
- **The floating `GuardedRange` and its null disposal are built here**, before 342, and are the
  node 332's TRY narrowing needs.
- **`time_from_seconds` needs 342's floating multiply**, so it is the last step (question 2).
- **No `D2B` or `D2S` operator.** 2.7 has them match LEGACY's `(byte) (int)` wrap in one
  operator; here a narrow target is `NarrowLane` over the `INT` conversion (2.1), the wrap the
  narrow lanes already emit, so no narrow-target conversion needs a census cell or a lowering.

## 1. Goal and non-goal

The 26 pairs of item 0.1 and `time_from_seconds` over a `FLOAT` or `DOUBLE` fuse with the row
engine's answers to the bit in every mode Spark allows; a lane Spark raises on declines its batch
to the row engine, and a lane TRY nulls is null. Not this row: the decimal casts (346, 350, 364,
366), string and zoned `TIMESTAMP` casts, rounding (344), arithmetic (342).

## 2. Design

### 2.1 One conversion node

`ConvertLane(child, target)`: Java's primitive cast from the child's lane to `target`, where one
side is floating - `I2F`, `I2D`, `L2F`, `L2D`, `F2I`, `F2L`, `D2I`, `D2L`, `F2D`, `D2F`. The
constructor refuses an integral pair (those are `WidenLane` and `NarrowLane`) and a narrow side: a
`TINYINT` or `SMALLINT` source is the `INT` value its load widens, and a narrow target is
`NarrowLane` over the `INT` result, which is JLS 5.1.3's wrap. The node has one meaning on every
host; its lowering is per host (2.3). A conversion between equal widths keeps the lane count; one
that widens reads part 0 of the source, as `I2L` does, and one that narrows writes the low half of
the target species, as `L2I` does.

### 2.2 The kernel of two lane counts

The rules of `VarkaKernelPlan` change from lanes to widths: a tree holding a 64-bit node (`LONG`
or `DOUBLE`) drives at 64 bits, at `LONG` if it holds one and at `DOUBLE` otherwise; a tree of
32-bit nodes only drives at `INT` if it holds one, else at `FLOAT`. A 32-bit value in a 64-bit
kernel fills the low half of its species of the drive's width, as an int value does in a long
kernel (`VARKA-28.md` 9). `Analysis`' fit rule (item 0.3) becomes the width rule, and `lanesShare`
joins roots of one width (343a3 builds the crossing within a root's tree; the join of roots of
two kinds is part 343a3b's, 3 November 2026). A mask crosses to a lane of another count through
the word, as
`emitToDrivingLane` does, and to one of the same count by `VectorMask.cast`. #1065 leaves that
crossing owed (its `VARKA-341.md` 5.1); 343a3 builds it, so it closes 341's owed item, and 341's
record names 343a3 when this lands. A narrow column in a 64-bit kernel - `CAST(y AS DOUBLE)` -
needs the narrow load at the long width that 332's `CAST(y AS BIGINT)` needs too; the first of the
two rows to land builds it.

#### 2.2.1 The root-level crossing (part 343a3b, planned 4 November 2026)

343a3 crosses a mask between the kinds inside one root's tree; what it left is the meeting of
conditions of two kinds, which every connective, boolean choice and filter makes, since `And`,
`Or`, `CondIf` and `BoolCompare` refuse operands of two lanes. Two designs meet them: one root a
kind, the filter's clauses ANDing the selection bitmaps after the kernel (`CompiledVarkaPredicate`
already ANDs a split predicate's clauses), or a crossing inside the kernel. The first leaves the
connectives and the boolean choices of one tree unserved, needs a second bitmap a batch and a
split that keeps the kinds apart, and joins cross-width roots that the projection side does not;
so the crossing is the one built.

- **The node.** `CrossLane(Cond child, LaneType lane)`: the child's three-valued answer read at
  another lane, refusing a narrow lane and the child's own. In the masked body it is free - the
  known-true and known-false words are a bit a row whatever the lane, so its words alias the
  child's, as `Not`'s swap does. In the dense body it is the child's mask as one of the other
  lane's species: by `VectorMask.cast` between lanes of one width, which have one lane count, and
  by the word, `toLong` then `fromLong`, between the widths, as `emitToDrivingLane` crosses a
  32-bit mask to a 64-bit drive (a 32-bit mask's low lanes are the group's rows; a 64-bit mask's
  word read by a 32-bit species sets only its low half). Neither pair is the one C2 miscompiles,
  which is `fromLong` of `toLong` at one lane count (VARKA-396). The reference and the range
  analysis read it as its child; the cost model gives it a feature of its own,
  `CrossLane/<from>/<to>` (`VarkaEmitCost.kindOf`), unpriced until the refit (VARKA-352).
- **Where the compiler crosses.** One meeting rule for a list of conditions, `meetAll` for the
  connectives and boolean choices and the filter's conjunct fold alike. Conditions of the
  integral kind alone meet as before (narrow to `INT`, `INT` to `LONG` by `onLongLane`), so no
  existing shape moves. Where a floating condition is among them, they meet at one lane by the
  width rule of 2.2 read over the conditions' lanes - `LONG` if one is, else `DOUBLE`, else `INT`,
  else `FLOAT` - the integral ones lifted among themselves first; each condition still off it is
  crossed, a boolean column or constant relabelled instead (its bit does not depend on the lane).
  So `frid < 0 AND CAST(i AS FLOAT) = f2 AND isnotnull(i)` is one `INT` root with the float
  conjuncts cast across, and `xrid < 0 AND CAST(i AS DOUBLE) = x2 AND isnotnull(i)` one `DOUBLE`
  root with the int null test crossed by its word. `floatingConjunctRefusal` and its reason go.
- **The projection's roots.** `lanesShare` joins roots of one width: `INT`, `FLOAT` and the narrow
  lanes at `INT`, `LONG` and `DOUBLE` at `LONG`. A 32-bit root beside a 64-bit one keeps a kernel
  of its own, as an `INT` root beside a `LONG` one does (VARKA-480): each root's tree chooses its
  own drive lane (`VarkaKernelPlan.emissionLane`), so a root with no 64-bit node never joins a
  64-bit kernel, though such a kernel stores the 32-bit roots its converting trees give from the
  low half of their species (`VarkaBodyEmitter.emitLowHalfStore`).
- **The epilogue** needs nothing new: a crossed mask is computed from loads the epilogue already
  masks, and every store and selection word is truncated to the group's rows.
- **The value choice** (*4 November 2026, the owner's choice of open point 1*): an `IfElse`
  whose condition is of the other kind than its values - `CASE WHEN f > 1.5 THEN i END`,
  `IF(i > 3, x, x2)` - crosses that condition to the values' lane with the same `CrossLane`, a
  narrow one lifted to `INT` first (`VarkaConditionCompiler.onValueKind`); nothing new in the IR.
  A `LONG` condition is of the integral kind, so over floating values it crosses too:
  `IF(l > 5, f, f2)` reads the long mask's word at the float lane. A boolean column condition
  reads no lane and is relabelled to the values' lane instead: `IF(b, x, x2)`,
  `CASE WHEN b THEN f ELSE f2 END`. Two integral kinds meet as before, so
  `CASE WHEN l > 5 THEN i END` still declines: the crossing is only between the kinds, and the
  integral lift goes to `LONG` only where the values are long.
- **Still declines:** a choice of narrow values under a floating condition; a floating condition
  over values of the other floating width, `IF(f > 1.5F, x, x2)` and `IF(x > 1.5D, f, f2)`: one
  kind, so not crossed, and Spark's typing does not widen it; any combination the
  kernel plan refuses (a narrow node under a `DOUBLE` kernel but a
  column read as a value, among them a narrow null test there); and on a two-lane x86 host a
  condition in a 64-bit kernel, by the host refusal (VARKA-503).
- **Tests:** `VarkaEmitterFloatingCastSuite` holds kernels joining conditions of two kinds - each
  pair of lanes, both widths, both the cast and the word - to the reference at three widths, the
  ten lengths, four null patterns and both bodies, and the projection kernels of an `INT` and a
  `FLOAT` root and a `LONG` and a `DOUBLE` one; the compiler suite states the meeting (one root,
  where `CrossLane` sits, the relabelled boolean column); coverage rows for the filter forms now
  fused, `CAST(CAST(l AS INT) AS FLOAT)` among them; the freed `VarkaAnsiForm.DECLINED` entries
  removed, which the strict check enforces.

*Open points for the lead* (4 November 2026): whether the value choice of the other kind
(`CASE WHEN f > 1.5 THEN i END`) belongs in this part (yes, by the owner's word: above); and
whether the projection side should join 32-bit and 64-bit roots,
which needs one drive chosen across the roots.

### 2.3 The lowering per host

| conversion | AVX512DQ (laptop) | AVX2 (EPYC 7763) | NEON and SVE (Neoverse N2) |
|---|---|---|---|
| `I2F`, `I2D`, `F2I`, `D2I`, `F2D`, `D2F` | `convertShape` | `convertShape` | `convertShape` |
| `L2D` | `convertShape` | split (below), or the bounded magic | `convertShape` |
| `L2F` | `convertShape` | round-to-odd (below) | `convertShape` |
| `D2L` | `convertShape` | assembly (below), or the truncated magic | `convertShape` |
| `F2L` | `convertShape` | `F2D`, then `D2L`'s form | `convertShape` |

The host is `convertsFallBack()` (item 0.5), already in the shape key, once row 538 feeds it the
host. The AVX2 forms live in a
`VarkaConversionLowering` beside `VarkaDivisionLowering`; `M = 0x1.8p52`, `MB` its bits:

- **`L2D`, split** (whole range): `hi = v >> 32`, `lo = v & 0xFFFFFFFF`; `dh = (bitsToDouble(hi +
  MB) - M) * 0x1p32` and `dl = bitsToDouble(lo | 0x4330000000000000) - 0x1p52`, both exact (`|hi|
  <= 2^31`, `lo < 2^32`); `d = dh + dl`, one rounding of the exact `v`. Eight lane operations,
  which is not the instruction count: AVX2 has no 64-bit arithmetic right shift, so C2 emits
  `v >> 32` (and the `>> 63` of the two forms below) as four instructions and a sign-mask load
  (`cpu/x86/x86.ad:6923-6949`); the fitted cost feature of 2.7 absorbs the difference.
- **`L2D`, bounded magic**: `bitsToDouble(v + MB) - M`, exact for `|v| < 2^51`, under a
  `GuardedRange` of that window that declines the batch outside it.
- **`L2F`, round-to-odd** (whole range, one form): `s = v >> 63`, `m = (v ^ s) - s` read unsigned
  (`Long.MIN_VALUE` gives `2^63`); where `m >>> 52 != 0`, `t = (m >>> 12) | sticky(m & 0xFFF)` and
  the scale `2^12`, else `t = m` and the scale 1; `d = (bitsToDouble(t | 0x4330000000000000) -
  0x1p52) * scale`, exact; then `D2F`, and the sign flipped where `s` is set. Below `2^52`, `d` is
  `m` and `D2F` rounds once; above, `d` is `m` rounded to odd with at least 41 bits kept, and
  `D2F` of it equals one rounding of `m`: round-to-odd at `p` bits then to nearest at `q` bits
  is one rounding to nearest when `p >= q + 2`, here 41 >= 26 (Boldo and Melquiond, IEEE TC
  2008). A seeded draw of the whole range agrees with the condition.
  *4 November 2026, read from the PDF* (`READING_ROUNDING_TO_ODD.md`; the deposit's terms permit
  no transcription, 5 step 4): **Theorem 3 (To_Odd_Even_is_Even)**, "Assuming p >= 2, k >= 2,
  and E_e >= 2 + E_w, for all x in R, round_p(odd_{p+k}(x)) = round_p(x)", the extended format
  being `(p + k, E_e)` and the working one `(p, E_w)`, `2^-E` a format's smallest positive
  number. The precision condition is `k >= 2`. Here `p = 24`; for `m` in `[2^j, 2^(j+1))`,
  `j` from 52 to 63, the 12-bit truncation with its sticky bit is rounding to odd at `j - 11`
  bits (section III-B's construction), so `k = j - 35 >= 17`; `E_e = 1074 >= 2 + 149`, and the
  values are integers far from either format's subnormals. Since `k` moves with the binade,
  3.3's proof states the instance per binade.
- **`D2L`, assembly** (whole range): from the bits, `e = ((b >>> 52) & 0x7FF) - 1075` and `m =
  (b & 0xFFFFFFFFFFFFF) | 2^52`; the magnitude is `m << e` for `0 <= e <= 10` and `m >>>
  min(-e, 63)` for `e < 0` (the Vector API takes a shift count modulo 64, so the count is clamped);
  the sign applied by `(r ^ s) - s`; `e >= 11` (`|x| >= 2^63`, the infinities, NaN) blends to
  `Long.MAX_VALUE`, `Long.MIN_VALUE` or 0. About fifteen `long` operations, `vpsllvq` and
  `vpsrlvq` being AVX2.
- **`D2L`, truncated magic**: `r = (x + M) - M`, the nearest integer, exact for `|x| < 2^51`;
  moved one toward zero where `|r| > |x|`; then `rawbits(r + M) - MB`, under a `GuardedRange` of
  `|x| < 2^51`.

The split and the assembly are the default arms and the bounded ones the reference arms of an
option `wideConversionForm` (`SPLIT`, `MAGIC`), read only where `convertsFallBack()` holds and
fuzzed on the laptop under `useAVX=2` (question 1). At the forced 128-bit species on x86 the
64-bit masks are fallbacks (`VARKA-376.md` 9), recorded in `width_audit.json` as 341 records its
compares.

Correction (VARKA-583): `L2D`'s default arm is now `XOR_SPLIT`, with the split above kept as
`SPLIT`; `L2F`'s sign and arm mask default to `roundToOddByShifts=false`, with the steps above
kept under `true`.

### 2.4 The modes and the boundary cells

To a floating type, every mode is `ConvertLane`, TRY included (item 0.4). From one:

| target | LEGACY | ANSI | TRY |
|---|---|---|---|
| `INT`, `BIGINT` | `ConvertLane` | `ConvertLane(GuardedRange(x, lo, hi, FAIL))` | in `NULL` |
| `SMALLINT`, `TINYINT` | `NarrowLane(ConvertLane(x, INT))` | the same over the guard | in `NULL` |

`GuardedRange` gains a disposal: `FAIL`, today's decline, or `NULL`, which clears the validity of
a live lane outside the bounds and marks the kernel as one that nulls valid inputs (the masked
body, as `IntArith` in `NULL` does). At a floating lane its bounds are the lane's own bits,
inclusive, compared by IEEE, so NaN is outside. The bounds are Spark's `floor` and `ceil` test
rewritten as one interval, `(MIN - 1, MAX + 1)` open, closed at the nearest value of the source
type inside it:

| target | `DOUBLE` source | `FLOAT` source |
|---|---|---|
| `INT` | `[nextUp(-2^31 - 1), nextDown(2^31)]` | `[-2^31, 2147483520]` |
| `BIGINT` | `[-2^63, 2^63]` | `[-2^63, 2^63]` |
| `SMALLINT` | `[nextUp(-32769.0), nextDown(32768.0)]` | the float neighbours of the same |
| `TINYINT` | `[nextUp(-129.0), nextDown(128.0)]` | the float neighbours of the same |

The cells, each a named test in all three modes where it differs: each bound and its outer
neighbour (`2^63` passes ANSI and saturates to `Long.MAX_VALUE`, `nextUp(2^63)` raises;
`2^31 - 0.5` gives `Int.MaxValue`, `2^31` raises; `-2^31` from a `FLOAT` passes); NaN (0, raise,
null); both infinities (saturate, raise, null); `-0.0` to 0; LEGACY's wrap: `(byte) 128.0` is
-128, `(byte) 300.7` is 44, `(byte) 1e10` is -1 (`Int.MaxValue` wrapped); the truncation, on
every host's form and both AVX2 arms: `+-0.7` to 0 and `+-1.5` to `+-1`, where a form that
rounded instead of truncating gives `+-1` and `+-2` (item 0.6). To a floating type:
`2^24 + 1` to `FLOAT`, `Int.MaxValue` to `2^31`, `2^53 + 1` to `DOUBLE`, `2^53 + 2^29 + 1` to
`FLOAT` (`2^53 + 2^30`, item 0.6), `+-Long.MAX_VALUE` and `Long.MIN_VALUE`, 4275361147 to
`FLOAT` (4275361024, 2.6's named case); `D2F` at `Float.MAX_VALUE`'s half-ulp tie (to infinity)
and below it, at `Double.MIN_VALUE` (`0.0f`, and `-0.0f` for its negation), across the float
subnormals; NaN through `F2D` and `D2F` compared by NaN-ness (2.7's rule for a computed NaN).

### 2.5 Booleans and `DATE`

- `CAST(b AS FLOAT)` and `AS DOUBLE`: `CondValue` at the floating lane, `0.0` with `1.0` added
  under the known-true mask, its validity `kT | kF`; the constructor admits the two lanes.
- `CAST(x AS BOOLEAN)`: the condition `x <> 0.0`, IEEE's not-equal, which over `0.0` is #1065's
  answer too: NaN is unequal, both zeros are equal. It fuses wherever a condition does; as an
  output value it is row 27's.
- `CAST(d AS FLOAT)` and `AS DOUBLE` under LEGACY: row 333's typed null leaf at the target lane,
  as 332's `DATE` pairs; the step waits for 333.
- The identities `FLOAT` to `FLOAT` and `DOUBLE` to `DOUBLE` compile to the child where one
  reaches the compiler; the optimizer removes them, so they have no coverage row (row 328's rule).

### 2.6 `time_from_seconds` over a floating value

    s = x, or ConvertLane(f, DOUBLE)
    p = IntArith(MUL, WRAP, s, 1e9)                       342's floating multiply
    g = GuardedRange(p, nextUp(-1.0), nextDown(8.64e13), FAIL)    every mode
    n = ConvertLane(g, LONG)

`(long) p` is in `[0, NANOS_PER_DAY)` exactly when `-1 < p < 8.64e13`, and NaN and the infinities
fall outside, so the guard is Spark's two raises in one; inside it `|p| < 2^47`, where both AVX2
`D2L` arms are exact and the bounded one never declines. Cells: `-1e-9` raises (`p = -1.0`), its
`nextUp` gives 0; `86399.99999999999` gives 86399999999999; 86400 raises; the float below 86400
gives 86399992187500; `-0.0` gives 0.

### 2.7 Compiler, reference, cost, census

- A `VarkaFloatingCastCompiler` arm in a family `FLOATING_CAST` (`varka/VarkaFamily.java`), so the
  family switch turns it off; `floatingDecline` admits a `Cast` whose pair is in this row's table,
  and a `StaticInvoke` of `timeFromSeconds` over a floating argument after step 7.
- `VarkaReference` evaluates `ConvertLane` by Java's casts over the bits and `GuardedRange` at a
  floating lane and in `NULL`.
- The range analysis answers `UNKNOWN` for a node with a floating source, as 342's do.
- Cost: a `VarkaEmitCostRegister` feature per lowering of 2.3, fitted as every feature is;
  `VarkaEmitBudget` weighs an AVX2 form by its operation count until the fit.
- The warm-up blocks gain the conversions and the AVX2 forms' operators
  (`VarkaWarmupReachSuite`).

## 3. Tests and proofs

The oracle is Spark's own `Cast` and `TimeFromSeconds`, through the reference evaluator and the
differential suites. ANSI and TRY are checked per batch: a batch declines exactly when Spark raises
on one of its live rows, and TRY's nulls are Spark's. In the sweeps the raise set is Spark's own
predicate evaluated without a throw - `Math.floor(v) <= MAX && Math.ceil(v) >= MIN`, the
`FloatExactNumeric` and `CastUtils` test - and `Cast.eval` runs on the rows it admits, since a
thrown `CAST_OVERFLOW` costs microseconds and a large share of the float patterns raise.

1. **Exhaustion at 32 bits**, opt-in under `-Dvarka.sweep=true` and in the nightly, PR CI a slice:
   19 sweeps of 2^32: every `FLOAT` bit pattern to `BOOLEAN` as a condition (1), to the four
   integral types in LEGACY, ANSI and TRY (12), to `DOUBLE` (1, one answer in every mode) and
   through `time_from_seconds` (1); every `INT` to `FLOAT` and to `DOUBLE` (2); `FLOAT` to
   `BIGINT` under LEGACY again under `useAVX=2`, once per arm of `wideConversionForm` (2). Every
   `TINYINT`, `SMALLINT` and `BOOLEAN` value to both floating types besides, which are not 2^32
   sweeps. The 19 wait, as the milestone plan's 2.10 and `VARKA-329.md` 4.4 have it, for row
   330's first timed sweep and the milestone's budget total.
2. **The reduced-width twins**, scalar Java over 2^32 inputs, each the same construction at half
   width against Java's own cast: the split as `int` to `float` by 16-bit halves; the assembly as
   `float` to `int` from the exponent; the truncated magic as `float` to `int` with `0x1.8p23`
   below `2^22`; round-to-odd as unsigned 32-bit to `binary16` through a `binary32` intermediate
   with a 9-bit shift above `2^23`, against one rounding computed in integers - 2.7's
   "exhausted through `binary16`".
3. **The SMT claim**, `sql/varka/proofs/round_to_odd.smt2` under Z3 and cvc5 by
   `dev/varka_prove.sh`: for every 64-bit `m >= 2^52`, rounding `m` to 24 bits equals rounding `t
   << 12` to 24 bits, the rounding written in bit vectors (leading-zero count, guard and sticky
   bits). And the split's exactness, `(hi << 32) + lo = v` with each part inside its exact window.
   *As built (4 November 2026, 343b1's groundwork, section 10):* `round_to_odd.smt2`, rendered by
   `VarkaProofFiles.roundToOdd`, states the claims over SMT-LIB's floating-point theory rather
   than a rounding written by hand in bit vectors, so the reference is IEEE 754's own conversion
   (`to_fp` under `RNE`, JLS 5.1.2) and no hand-written rounding can be wrong with it: the
   rounding of `t << 12` equals the rounding of `m` per binade (Theorem 3's instance), and beside
   it the whole `L2F` form of 2.3, magnitude and sign, is `(float) v`. The split is three lemmas;
   its last step, the one rounding of the exact `dh + dl`, is IEEE addition's definition, not a
   check (stated whole, Z3 passed 30 s and cvc5 took about 17 s). Each check runs after a
   `(reset)`, not between `push` and `pop`: once a file pushes, Z3 answers from its incremental
   core, where one 64-bit `fp.sub` took about 6 s and four checks passed the 10 s limit (the
   whole file about two minutes), while the same checks in fresh contexts take the file about
   5 s under each solver on the laptop, the slowest check about 2 s.
4. **The named failing case** (`READING_0X80.md` item 3): the split-and-add form, the low 31 bits
   converted and `2^31` added, gives 4275361280 for 4275361147 against 4275361024, and misrounds
   about 16.5% of a seeded draw of `[2^31, 2^32)`; the test asserts that it fails and that the
   kernel's `L2F` gives 4275361024.
5. **Sampled `DOUBLE` and `BIGINT` regions** for every pair: both zeros, three NaNs, the
   infinities, the subnormals, every bound of 2.4 and its neighbours, `+-2^31`, `+-2^51`, `+-2^52`,
   `+-2^53`, `+-2^63` and their neighbours, and a seeded draw per exponent band, on the laptop's
   forms and under `useAVX=2` in both arms; 2.4's and 2.6's cells as a table test.
6. **The mixed-width kernels** in `VarkaEmitterMixedLaneSuite`: `INT` beside `DOUBLE`, `FLOAT`
   beside `LONG`, `FLOAT` beside `DOUBLE`, a narrow column beside each, at every width, the four
   null patterns, both bodies, in a JVM of their own (row 499 (a)).
7. **Coverage rows**, a family "floating casts": every pair of item 0.1 in each legal mode over a
   column and a literal, as a value and under a filter, `try_cast` among them, and
   `time_from_seconds` over `x` and `f`; `casts_fused.tsv` regenerated from them.
8. **Conformance**: the cast rows #1065's `DOUBLE` and `FLOAT` suites cancel naming 343 run.
9. **The fuzzers**: the IR grammar draws `ConvertLane` between its floating and integral
   sequences; the Spark fuzzer's grammar casts its floating columns.
10. **Generated files** in `ADDING_AN_EXPRESSION.md`'s order, then the PR build and the option
    matrix dispatched with `wideConversionForm` and `useAVX=2`.

## 4. Predictions and costs

The measurement reruns the baselines (5, step 1) with the fuse check lifted, on the runners' EPYC
7763 at 256 and 128 bits, diffed against the committed files.

1. **No existing shape's bytes move**; the `option_arms` digests move with the new shapes.
2. **The census**: `F2L` intrinsic on the laptop and the Neoverse N2 and a fallback on the EPYC
   (the AVX512DQ rule); the double `ADD`, `SUB`, `MUL`, the reinterpretations and the `long` shifts
   by a vector count intrinsic at each width each host's census reads: 512, 256 and 128 bits on
   the laptop, 256 and 128 on the EPYC 7763, 128 on the Neoverse N2.
3. **Against the row path at 256 bits**: `FLOAT` to `DOUBLE` at least five times faster than 38.4
   ns a row; `DOUBLE` to `BIGINT` under ANSI, two compares and the assembly, at least three times
   faster than 39.8; in the new class, `INT` to `DOUBLE` at least five times, `BIGINT` to `FLOAT`
   by round-to-odd and `time_from_seconds` at least three times.
4. **The AVX2 arms**: the split within 1.3 times the bounded magic's time, the assembly within 1.5
   times the truncated magic's, by minimums. The rule: the whole-range arm is the default unless
   it is more than 1.3 times slower, since the bounded arm declines what it cannot convert.
5. **Test time**: the 19 sweeps about 2.2 minutes nightly (19 x 7 s = 133 s), at `VARKA-335.md`'s
   7 s for a unary 2^32 sweep (ratio taken as 1), which holds only with the raise set taken
   without a throw (3). With `Cast.eval` throwing on every raising pattern it would be two to
   three orders of magnitude more: 38% of the `FLOAT` patterns raise for `INT` (biased exponent
   158 and above, 98 of 256) and 48% for `TINYINT` (134 and above, 122 of 256), so at a
   microsecond a throw one `INT` sweep alone costs about 1600 s against 7; the
   four twins under 40 s; the SMT claims under 60 s (shifts and
   compares, no multiply); the coverage rows under 60 s, at `VARKA-336.md`'s ratio.

Cost: large, against the row's medium. Row 341's lane split alone was large at five days; this
row, step by step after the baseline (5): the census cells half a day; `ConvertLane` and the width
rules across the kernel plan, `Analysis`, `Slots` and the walk, with the mixed-width kernels and
the first sweeps, three days; the paper, the four AVX2 forms, the option, the twins, the SMT
claims and the failing case, three and a half; the floating `GuardedRange` and its `NULL`
disposal through the masked body, with the three modes' cells and sweeps, two; the booleans half
a day; `time_from_seconds` one; the generated files and the measurement one. About eleven days.

It lands in pull requests of under 1000 changed lines each of hand-written source and plan
text; generated files are not counted (*3 November 2026, the owner's word*: this replaces the
two parts, 343a and 343b, of 27 October). The work built for 343a on #1303 is cut into the first
five; each is taken from that branch's final tree, not by replaying its commits, and carries its
own tests and its own regenerated files. The sizes in the table include the generated files, so
they are upper bounds.

| part | what | depends on | lines, about |
|---|---|---|---|
| 343a1 | `CAST(x AS BOOLEAN)` over a `FLOAT` or `DOUBLE` (2.5): the lane's `EQ` against zero, negated, in the condition compiler; its coverage rows | master | 200 |
| 343a2 | `ConvertLane` (2.1) with its refusals, the reference's casts and the range analysis, the `convertShape` lowering, and the compiler arm for the one-count pair, `INT` to `FLOAT`; the reference cells and the refusal tests | master | 750 |
| 343a3 | the kernel of two lane counts (2.2): the width rules in the kernel plan, `Analysis`, `Slots` and the walk, the warm-up blocks, and the pairs it carries, `INT` to `DOUBLE`, `FLOAT` with `DOUBLE`, the narrow sources, a condition as a value and the mixed trees (`x + i`, `f * i`, `f + x`); the three failures #1303's last matrix found are fixed here, the `INTERNAL_ERROR` under `validityByWidth=false` as a defect, not a skip | 343a2 | 950 |
| 343a3b | the root-level crossing (2.2): roots of two kinds share one kernel (`lanesShare` joins roots of one width), so a filter's floating conjunct beside an integral one - the null test the optimizer infers on a cast's source among them - no longer declines (`floatingConjunctRefusal`), and an integral and a floating output take one kernel; the forms `VarkaAnsiForm.DECLINED` lists for it fuse, and the coverage row `CAST(CAST(l AS INT) AS FLOAT)` is added, whose domain check's filter form needs this join. Added by the owner's word of 3 November 2026, when 343a3 stayed under the cap without it. *Owed by 343a3 (10):* `CompiledVarkaProjection`'s long-literal check plans under `VarkaEmitOptions.DEFAULTS`, so a 128-bit host with `lanesOverride=4` fails `CAST(y1 AS DOUBLE) / 2.0D` with `INTERNAL_ERROR`; check under the session's options, as row 332's load needs too. *Sized 4 November 2026 (2.2.1):* a `CrossLane` node, the meeting rule and `lanesShare`'s width join, about 600 hand-written lines with the tests; one part. *Added 4 November 2026 by the owner's choice:* the value choice whose condition is of the other kind, its condition crossed to the values' lane | 343a3 | 600 |
| 343a4 | `BIGINT` to `FLOAT` and `DOUBLE` where the host has AVX512DQ: level 3 resolved from the host, the unknown level declining them, the option matrix's level-3 runs and their Spark check | 343a3b | 450 |
| 343a5 | *(built, 10)* the fuzz grammar's conversions, the 2^32 sweeps of 3.1 and the harness check of each fused cast on both lanes. *Owed by 343a3b (10):* the false-AND forms of `CAST(y1 AS DOUBLE)` and `CAST(s1 AS DOUBLE)`, which still decline: the narrow null test the optimizer infers on the source has no lowering under a double kernel, as their reason in `VarkaAnsiForm.DECLINED` names; a narrow column's null test at the long width, or the test kept on its own kernel, frees them | 343a4 | 400 |
| 343b1 | the paper and the AVX2 forms (2.3, step 4): `L2D` by the exact split, `L2F` by round-to-odd, behind `wideConversionForm`, the SMT claims and the failing case. *4 November 2026:* the groundwork - the paper's reading notes (5 step 4) and `round_to_odd.smt2` (3.3) - is built on branch `varka-343b1-proofs`, touching no kernel, emitter or compiler code; the forms, the option and the failing case are owed (section 10) | 343a5 | 900 |
| 343b2 | floating to integral in the three modes (2.4, step 5), with `GuardedRange`'s floating bounds and `NULL` disposal; `D2L`'s assembly on AVX2 | 343b1 | 900 |
| 343b3 | `time_from_seconds` (2.6, step 7), after 342's first pull request, and the measurement against the baseline (step 8) | 343b2 | 500 |

343a3 is the one nearest the cap: if it passes 1000 lines, the mixed trees and the condition as a
value move to a part of their own after it. *As built (3 November 2026, section 10):* it stayed
under the cap whole, and it also carries `TINYINT` and `SMALLINT` to `DOUBLE`, since row 332
had built the narrow load at the long width. 343a1 needs none of the others and may land first.
Only a real code dependency chains one part's matrix on another's. A part over the cap is split
again before it is pushed, never merged over it.

The `DATE` pairs follow 333 in either.

## 5. Order and files

1. **The baseline**, its own pull request on the unchanged code:
   `VarkaFloatingCastBaselineBenchmark` over `CAST(l AS FLOAT)`, `CAST(l AS DOUBLE)`,
   `CAST(i AS DOUBLE)`, `CAST(w AS INT)` in LEGACY, `CAST(x AS INT)` in ANSI,
   `try_cast(w AS BIGINT)`, `CAST(x AS FLOAT)`, `coalesce(CAST(x AS BOOLEAN), false)` under a
   filter and `time_from_seconds(s)`, with and without nulls, under the shared fuse check.
   *31 October 2026, as built:* the fuse check is the per-section one row 342's baseline built
   (`VarkaSqlBenchmarkBase.requireBaseline`, lifted by `-Dvarka.baseline.fuseCheck=false`),
   which 377's class already has, so this step adds none. The `try_cast` reads its own column
   `w`, about one row in a hundred past the long range (at one in ten the section timed mostly
   Spark's TRY path raising and catching an exception per such row) and one row in a thousand
   each NaN, `+Infinity` and `-Infinity`, and LEGACY's `CAST(w AS INT)` reads it too, so its
   saturation runs, about six rows in seven being past the int range; `time_from_seconds` reads
   its own `s`, inside the day: an ANSI cast or a `time_from_seconds` with a value out of range
   raises and ends the run, so `x`, which ANSI's `CAST(x AS INT)` reads, stays inside the int
   range. The filter's condition is
   `coalesce(CAST(x AS BOOLEAN), false)`: from the bare cast the optimizer infers
   `x IS NOT NULL`, and that conjunct alone fuses into a `VarkaFilterColumnarToRow` under
   Spark's `Filter`, so the fuse check could not see this row start fusing the cast; `coalesce`
   infers nothing. No section is listed as fusing, and none plans a Varka node (checked by the
   class's dry run, 31 October 2026). The results files at both widths come from the EPYC
   runners and are committed before it merges.
2. **The census cells** (item 0.5), on the laptop and through the census workflow on both runners.
3. **`ConvertLane` with `convertShape`** and the kernel rules (2.1, 2.2), with the same-count
   `VectorMask.cast` crossing #1065 leaves owed, built here first: the to-floating pairs and
   `FLOAT` with `DOUBLE`, tests 3.1, 3.6 and 3.7 for them.
   This step unblocks 342's `i / j`, `f / g` and the functions over a cast.
4. **The round-to-odd paper, then the AVX2 forms** (2.3) behind `wideConversionForm`, with 3.2
   to 3.5. First Boldo and Melquiond, "Emulation of a FMA and correctly-rounded sums: proved
   algorithms using rounding to odd" (IEEE Transactions on Computers 57(4), 2008), into
   `sql/varka/papers/` as its `README.md` says: from the open-access deposit by `pdftotext
   -bbox-layout`, the header naming this row, and its row in the README's contents; if the
   deposit's terms do not permit a copy, the reading notes and the citation instead. The theorem
   the `L2F` form rests on is then quoted, with its number and its precision condition, at 2.3
   and in 3.3's proof file, read from the PDF. Nothing is downloaded before this step.
   *As built (4 November 2026, 343b1's groundwork):* the HAL deposit (`inria-00080427v2`, the
   authors' manuscript) carries the HAL Authorisation v1 - read, consult and cite, and ask the
   author before reproducing - so no transcription is kept: `READING_ROUNDING_TO_ODD.md` holds the
   reading notes and the citation, and `papers/README.md` says so in a paragraph, with no row in
   its contents. The theorem is quoted at 2.3 and in `round_to_odd.smt2`'s header.
5. **Floating to integral** in the three modes (2.4), the `GuardedRange` disposal with it: 342's
   interval forms are unblocked here.
6. **The booleans** (2.5), and the `DATE` pairs once 333 lands.
7. **`time_from_seconds`** (2.6), after 342's same-lane pull request; its first test is that
   Spark analyzes and evaluates `time_from_seconds(f)` over a `FLOAT` column, since item 0.4's
   overload resolution is read from the matcher's rule, not observed.
8. **The generated files, the measurement** and its verdict.

| 342's form | what it needs from this row | step |
|---|---|---|
| `i / j`, `y / z`, `sqrt(i)`, `signum(i)`, `degrees(i)` | `I2D`, the narrow load at 64 bits | 3 |
| `l / m` and the functions over a `BIGINT` | `L2D` | 3, 4 on AVX2 |
| `f / g`, `sqrt(f)`, a `FLOAT` interval factor | `F2D` | 3 |
| `ym * x`, `ym / x` | `I2D`, `D2I`, the floating guard | 3, 5 |
| `dt * x`, `dt / x` | `L2D`, `D2L`, the guard; whole range on AVX2 under question 1 (b) | 4, 5 |

| file | what |
|---|---|
| `varka/VarkaVectorIR.java` | `ConvertLane`; `GuardedRange`'s disposal and floating bounds |
| `varka/VarkaConversionLowering.java` | the AVX2 forms of 2.3 |
| `varka/VarkaVectorWalk.java`, `Slots.java`, `Analysis.java` | conversions, widths, null disposal |
| `varka/VarkaKernelPlan.java` | the drive and sharing rules of 2.2 |
| `varka/VarkaEmitOption.java`, `VarkaEmitOptions.java` | `wideConversionForm` |
| `VarkaFloatingCastCompiler.java`, `varka/VarkaFamily.java` | the arm and its family |
| `VarkaNodeCompiler.java`, `VarkaTimeCompiler.java` | `floatingDecline`, `time_from_seconds` |
| `varka/VarkaEmitBudget.java`, `VarkaEmitCostRegister.java` | the conversion features |
| `sql/varka/proofs/round_to_odd.smt2`, `VarkaProofFiles.java` | 3.3's claims |
| `READING_ROUNDING_TO_ODD.md`, a paragraph in `papers/README.md` | step 4's paper: notes and the citation, the deposit's terms permitting no transcription (*4 November 2026*; planned as `papers/boldo-melquiond-2008-rounding-to-odd.md`) |
| tests | sweeps, twins, cells, mixed-width kernels, coverage rows, failing case, fuzz grammar |
| benchmark | the baseline class, its results files at both widths from the EPYC runners |
| generated | `coverage.json`, `docs/sql-varka.md`, `width_audit.json`, `emitted_bytes.json` |
| generated | `operator_census.json`, `census.tsv`, `casts_fused.tsv` |

## 6. Out of this row

The decimal pairs (346, 350, 364, 366); string, zoned `TIMESTAMP` and interval casts with a
floating side, which Spark does not allow for intervals; `try_eval` around a cast; 344's rounding,
which reuses the truncated magic's first two operations; the `BOOLEAN` output (row 27).

## 7. Declines that stay

A batch with a live lane Spark raises on (ANSI's range, `time_from_seconds` outside the day);
under question 1 (a), a `BIGINT` or `DOUBLE` lane past `2^51` on AVX2; the `DATE` pairs until 333;
until 343a4, every `BIGINT` source; until 343b1, `L2D` and `L2F` wherever `convertsFallBack()`
holds.

## 8. Questions for the owner

None open; both were settled with an advisor's review on 27 October 2026.

1. **The AVX2 form past `2^51`: the whole-range split and assembly are the default arms,** the
   magic-number form the option's reference arm, the default by 4's rule. The rule to keep losing
   arms behind options settles it, and the row's "the host named" clause asks for one IR on every
   host: the magic form alone would decline a lane past `2^51` on AVX2 and not on AVX-512, and
   microsecond timestamps held as `BIGINT` sit near `2^50.6` today.
2. **`time_from_seconds` follows 342's first pull request.** Its lowering (2.6) is a `DOUBLE`
   multiply by the literal `1e9`, a same-lane form of 342's first part, then this row's own `D2L`
   (343b2); so it waits for 342's first part, not all of 342. Admitting the multiply here would
   make two rows own one node.

The size, large, was confirmed by the owner on 27 October 2026, in two parts; on 3 November 2026
he set a cap of 1000 changed hand-written lines per pull request, so the row lands in the parts
of 4: eight then, nine since 343a3b was added the same day.

The baseline comes first, as its own pull request (5.1): not a question, since the house rule
settles it, as it did for rows 377, 27, 339 and 342.

## 9. Done when

Row 343's text holds, with the corrections above: every pair of item 0.1 fuses in each mode
Spark allows it, the `DATE` pairs through 333's leaf; a `FLOAT` source proven over all 2^32 bit
patterns and an `INT` source over all 2^32 values (3.1); the round-to-odd truncation stated as
3.3's bit-vector claim under both solvers and exhausted through `binary16` (3.2); the split-and-add
form kept as 3.4's named failing case; the `DOUBLE` and `BIGINT` sources over 3.5's regions and
the fuzzer, on both hosts' forms; 2.4's and 2.6's cells pass; no existing shape's bytes move; the
PR build and the option matrix pass; the measurement is committed with its predictions scored.

## 10. Outcome

### 343a3 as built (3 November 2026)

Cut from #1303's final tree, without its `BIGINT` conversions (343a4), sweeps and fuzz (343a5).

- **The width rules** (2.2): `emissionLane` drives a tree holding a `LONG` node at the long lane,
  one holding a `DOUBLE` node otherwise at the double lane, and a 32-bit tree at the int lane
  over the float one; `Analysis`' fit rule is by width. A 32-bit value of a 64-bit kernel takes
  the low half of its species of the drive's width, a value of the other kind of that width its
  whole species. The plan admits `I2F`, `I2D`, `F2D` and `D2F` (`convertShape`) and refuses a
  `BIGINT` source (343a4) and a floating one to an integral lane (343b2), `Kind.CONVERSION`.
- **The pairs**: `INT`, `TINYINT`, `SMALLINT` and `BOOLEAN` to both floating types, `FLOAT` with
  `DOUBLE`, a bigint narrowed to `INT`, and type coercion's mixed trees (`x + i`, `f * i`,
  `f + x`, `i / 2.0D`). A condition converts as `CondValue` at the int lane, a long one narrowed
  first, so none needs `L2F` or `L2D`. *Departure from 2.2:* `CAST(y AS DOUBLE)` takes row 332's
  load at the long width (#1303 declined it); any other narrow node there declines.
- **The mask crossing at one lane count** #1065 left owed (`VARKA-341.md` 5.1): a guard's mask,
  an arm's condition and the epilogue's bounds mask cross between the kinds of the drive's width
  by `VectorMask.cast`, both ways at both widths; the warm-up gains `F2D`, `D2F` and the four
  casts. *Not built:* roots of two kinds share no kernel (`lanesShare`) and a filter's floating
  conjunct beside an integral one declines: part 343a3b's (4); `VarkaAnsiForm.DECLINED` lists it.
- **Tests**: `VarkaEmitterFloatingCastSuite` holds each conversion and the two-lane kernels to
  the reference at three widths, ten lengths, four null patterns and both bodies, and a zero
  divisor's decline to its row's validity; the "Floating casts" coverage rows, a long-driven
  kernel of float and double values among them, are checked against Spark by the harness.
- **The three failures of #1303's last option matrix** (run 41572066746):
  1. *Every configuration*: a test said a narrow value in a two-lane 64-bit kernel declines; row
     332 built that load, so it fuses where a long lane count is baked, else the plan declines it.
  2. *`lanesOverride=4` and `16`*: the `FLOAT`, `DOUBLE` and `BOOLEAN` conformance suites' casts
     fuse two-width kernels whose int species is not the 32-bit suites', which the shared JVM's
     guard refused (at 16 the long lane has none; int values take the preferred, 256 bits on EPYC,
     beside `SPECIES_512`): `ownJvm` now.
  3. *`validityByWidth=false`*: an emitter test handed the emitter a plan that refuses it, which
     threw `INTERNAL_ERROR`; it now asserts the refusal. The SQL path declined cleanly all along.

  Each passed on the laptop under its configuration (`dev/varka_matrix.sh`). The six new
  `skips.tsv` lines fix none: they expect the new narrow-column rows to decline where no lane
  count is baked,
  `validityByWidth=false` and, for `DOUBLE`, `lanesOverride=16`.
- **A known limit**, shared with row 332: `CompiledVarkaProjection` checks a long literal table
  against the drive under `VarkaEmitOptions.DEFAULTS`, which refuse what the session admits at
  128 bits with `lanesOverride=4`, `CAST(y1 AS DOUBLE) / 2.0D`: planning raises `INTERNAL_ERROR`.
  No CI step pairs them (the matrix sets it only on x86 of 256+ bits); no SQL conf sets it. Owed:
  343a3b.
- **Prediction 4.1** holds: only the new rows' shapes and the `option_arms` digests move.

### 343a3b as built (4 November 2026)

- **As planned in 2.2.1:** `CrossLane(Cond, LaneType)`, free in the masked body (its words alias
  the child's) and in the dense body `VectorMask.cast` at one width, `toLong` then `fromLong`
  across the widths (`VarkaVectorWalk.emitMaskAcross`); one meeting rule,
  `VarkaConditionCompiler.acrossKinds`, for `meetAll` and the filter's conjunct fold, a boolean
  column or constant relabelled rather than crossed; `floatingConjunctRefusal` and its `Kind`
  removed; `lanesShare` joins `INT`, `FLOAT` and the narrow lanes at `INT` and `LONG` with
  `DOUBLE`. Conditions of the integral kind alone meet as before; prediction 4.1 holds - the
  bytes file gains the new rows' shapes and moves only `option_arms` digests.
- **Now fused:** a filter's floating conjunct beside an integral one, the optimizer's inferred null
  tests among them, and the boolean column beside a floating cast's test; a value choice whose
  condition is of the other kind (below), `IF(l > 5, f, f2)`'s `LONG` condition over floating values
  among them; and a boolean column, relabelled to the values' lane, choosing floating values: `IF(b,
  x, x2)`, `CASE WHEN b THEN f ELSE f2 END`. Freed from `VarkaAnsiForm.DECLINED`: the six
  boolean-column positions of `CAST(x AS BOOLEAN)` and `CAST(f AS BOOLEAN)`, and 17 of the 19
  floating casts' false-AND positions. *Still declined:* `CAST(y1 AS DOUBLE)` and `CAST(s1 AS
  DOUBLE)` there: their inferred narrow null test has no lowering under a double kernel (2.2.1).
- **Coverage:** the family "Floating casts as conditions", six rows, and the owed
  `CAST(CAST(l AS INT) AS FLOAT)`, whose false-AND form is served and declines its batch on the
  extremes row, the right side of an AND unqualified like the integral casts' (m9 item 96).
- **The owed literal check:** `CompiledVarkaProjection.requireLiteralTables` plans under the
  compile's options, called from the compiler's tables; a plan they refuse is no kernel to check.
  The compiler suite checks it under options admitting and refusing `CAST(y1 AS DOUBLE) / 2.0D`.
- **Beside it:** `VarkaFloatingCastBaselineBenchmark`'s boolean filter, `coalesce(CAST(x AS
  BOOLEAN), false)`, fuses now (its `false` relabelled to the cast's lane), so its class is `WHOLE`,
  as the class's own rule asks; the long harness hands a kernel holding a crossing the int table
  too; the cost model and the IR fuzz grammar leave `CrossLane` out until the refit (VARKA-352) and
  row 343's fuzz sequence (343a5); `VarkaCanonicalIr` reads `(cross:LANE ...)` back, which the round
  trip of every node type holds. The touched catalyst suites and the sql harness, differential,
  floating-lane, filter and baseline suites passed under the defaults, `lanesOverride=4`,
  `lanesOverride=16` and `validityByWidth=false` on the laptop (`dev/varka_matrix.sh`, 4 November
  2026); each leg's one rewritten test expects a date-guarded floating choice to fuse now.
- **Known gaps:** `VarkaCanonicalIr` still reads no boolean node (`BoolColumn`, `CondConst`,
  `BoolCompare`, `CondIf`, `BoolNullSafeEq`; `VarkaReproductionSuite.NOT_PARSED_YET`). The
  `option_arms` digests hash every shape per option arm and lane count, so their moving cannot show
  that no shape changed under a non-default option; only the defaults' bytes are compared per shape.
- **The value choice of the other kind**, by the owner's choice: its condition crossed to the
  values' lane, both directions and both widths; `VarkaEmitterFloatingCastSuite.crossingShapes`
  holds it to the reference, beside a crossing under a crossing, a range set crossed to `LONG`,
  a choice and a `CondValue` over a mixed `AND`, `NOT` and `OR` both under and over a 64-to-32
  crossing, a double null test crossed down and `IF(l > 5, f, f2)`'s long condition; nine
  coverage rows in "Floating casts" and two in its conditions' family. Its forms make
  `IF(x <=> x, d2, ...)` fuse, which the compiler suite now states.
- **Open points:** 32-bit beside 64-bit roots stay open, `m10/SCOPE.md` item 15 (the owner's word).

### 343a4 as built (6 November 2026, branch `varka-343a4-level3`)

Cut from #1303's final tree and rebuilt on master, since master had moved under the files: the
`BIGINT` to `FLOAT` and `DOUBLE` casts fuse where the emit level assures AVX512DQ.

- **The admission.** `VarkaFloatingCastCompiler.admits` takes a `BIGINT` source, and
  `VarkaKernelPlan.conversionRefusal` reads the options: `VarkaEmitOptions.convertsMayFallBack()`
  (a level below 3, and the unknown one) declines `L2F` and `L2D` by name; a floating source
  converted to an integral lane still declines at every level. The emitter lowers both through
  `convertShape`, part 0.
- **Why a second predicate.** `convertsFallBack()` picks the division's form and is optimistic at
  the unknown level, where a wrong guess costs nothing. Whether a kernel exists is the opposite
  error, a scalar Java loop inside a vector kernel, so the unknown level, the defaults' and an
  unset session's, declines.
- **The session.** `VarkaSessionEmit.resolve` maps an x86 host whose feature list was read and holds
  AVX512DQ to level 3, a host without it to level 2 as before, and everything else (aarch64, an
  unreadable list) to the unknown level; `SQLConf` and `VarkaFamily` say so. Level 3 keys apart
  from the unknown level, so such a session caches its classes apart, with the same bytes.
- **The matrix.** `VarkaMatrixRuns.emissionKey` has three classes, and `useAVX=3` runs as its own
  configuration, the only one whose answer tests reach the kernel. `VarkaTypeConformance` takes
  the `BIGINT` pairs from the level, not the register, which is written at the unknown level.
- **Tests.** `VarkaEmitterFloatingCastSuite` holds five `BIGINT` shapes (to each type, beside a
  double column and a float one, under an ANSI division) at every width, and the refusals at the
  unknown level and below 3; `VarkaDifferentialHarnessSuite` compares eighteen extremes and
  rounding cells with the row engine at level 3. No emitted-bytes, census or coverage record moves.
- **Not here:** the AVX2 forms (343b1), which make the casts fuse at every level, and the fuzz
  grammar and sweeps (343a5).

### 343b1's groundwork as built (4 November 2026)

On branch `varka-343b1-proofs`, from master; no kernel, emitter or compiler code is touched.

- **The paper** (5 step 4): its HAL deposit carries the HAL Authorisation v1, which permits
  reading and citing and not a copy, so `READING_ROUNDING_TO_ODD.md` holds the notes and the
  citation and `papers/README.md` says why; Theorem 3 is quoted at 2.3 and in the proof's header.
- **The proof** (3.3), `round_to_odd.smt2`, 45 checks, 5.0 s under Z3 and 5.2 to 5.3 s under
  cvc5 on the laptop (`dev/varka_prove.sh --solver both`, two runs), 5.3 s of the `--lint` run,
  whose whole folder took 30 s on the laptop. What it proves, with `M = 0x1.8p52` and `MB` its
  bits:
  1. *`L2D` split*: for every int `hi`, `(bitsToDouble(sext(hi) + MB) - M) * 0x1p32` is exactly
     the double of `hi * 2^32` (equal to its conversions toward both infinities, which differ
     on every inexact value); for every unsigned int `lo`, `bitsToDouble(lo | bits(0x1p52)) -
     0x1p52` is exactly the double of `lo`; and `(v >> 32) * 2^32 + (v & 0xFFFFFFFF) = v` over 66
     bits for every long. So `d = dh + dl` is one rounding of `v`, `(double) v`. Refuted: `MB + 1`
     and the low word's magic `+ 1` (the whole form against `(double) v`), and a shift of 31.
  2. *`L2D` bounded magic*: `bitsToDouble(v + MB) - M` is `(double) v` for every `|v| <= 2^51`,
     and is not at `2^51 + 1` or `-(2^51 + 1)`. The window is closed: 2.3's guard `|v| < 2^51` is
     one narrower than it needs to be at either end, which costs nothing but a decline at the
     two ends.
  3. *`L2F` round-to-odd*: `(float) v` is the rounding of `m = |v|` read unsigned, negated where
     `v < 0`, for every long, and is not with `m = v ^ s`, the `- s` dropped; below `2^52` the
     form (`d0 = bitsToDouble(m | bits(0x1p52)) - 0x1p52`, then `D2F`) is the rounding of `m`;
     on each binade `[2^j, 2^(j+1))`, `j` from 52 to 63, the form (`t = (m >>> 12) | ((m & 0xFFF) != 0 ? 1 : 0)`, `d = (bitsToDouble(t |
     bits(0x1p52)) - 0x1p52) * 0x1p12`, then `D2F`) is the rounding of `m`, and so is the rounding
     of `t << 12` to 24 bits, the theorem's instance at `k = j - 35`. Every 64-bit `m` from `2^52`
     up is covered, a superset of the magnitudes, which reach `2^63`. Refuted: no sticky bit, two
     roundings to nearest (to a double, then to a float), a shift of 11 (t reaches `2^52` on the
     top binade) and the small arm up to `2^53`. The bound is tight: rounding to odd at 26 bits
     then to nearest at 24 is one rounding on `[2^63, 2^64)`, at 25 bits it is not. A shift of
     13 still holds (k = 16 at `2^52`), the theorem's slack, kept as an `unsat` check.
- **A one-off check not committed**: the whole split form against `(double) v`, stated per
  binade of `v` (63 positive, 63 negative), was `unsat` on all 126 under both solvers, about two
  minutes each, which is past the linters' budget; it confirms the composition the file states by
  IEEE addition's definition. The whole `L2F` form per binade of signed `v` was likewise `unsat`.
- **Open points for the lead**:
  1. The constants (`M`, `0x1p52`, `0x1p32`, the shift 12, the small arm's 52 bits, the window
     `2^51`) are the plan's, held in `VarkaProofFiles`; when `VarkaConversionLowering` lands, the
     generator reads them from it, as the other proof files read theirs.
  2. 2.3 writes `sticky(m & 0xFFF)` without its lowering; the proof states it as `(m & 0xFFF) !=
     0 ? 1 : 0`, a compare and a blend. A lowering by another route (`((m & 0xFFF) + 0xFFF) >>>
     12`, say) must be shown equal to it, or the proof restated over it.
  3. 2.3's scale is proven as a blend of `d0 * 0x1p12` and `d0`, and the sign as `fp.neg` of the
     float (its sign bit flipped); a lowering that multiplies by a blended scale of `1.0` or
     `0x1p12`, or flips the bit by an `XOR` of the float's bits, is the same value but is not the
     proven form.
  4. The split's final addition is stated by IEEE semantics, not checked whole, within the
     linters' budget (the one-off above checked it).
  5. The form whose arms are the `wideConversionForm` option, the reduced-width twins (3.2) and
     the named failing case (3.4) are the rest of 343b1.


### 343a5 as built (6 November 2026, branch `varka-343a5-fuzz`)

Cut from #1303's final tree and rebuilt on 343a4 (#1385), whose files it leaves alone:

- **The fuzz grammar.** `VarkaIrGrammar.drawFloatingCastShape` draws one to three roots of one
  kernel over an int, a bigint, a float and a double column and both literal tables: a leaf, a
  `ConvertLane` into a floating lane from an integral lane or the other floating one, a wrapping
  `+`, `-` or `*`, a pick, and a choice over a comparison at the same lane or at another, joined
  by a `CrossLane` (343a3b's crossing, which #1303 predates). Its seed, `FLOATING_CAST_FUZZ_SEED`,
  is its own, so no committed corpus moves. `VarkaIrFuzzSuite` runs it against `mixedValue` at
  the drawn options, half the draws held at emit level 3, where a bigint conversion is admitted;
  a plan that refuses the draw (a bigint conversion below level 3) is counted and skipped, and the
  test fails if half the draws are skipped. A reach test asserts all six conversions, the four
  drives, a 32-bit root of a 64-bit kernel and a crossing between and within the widths.
  `ConvertLane` and `CrossLane` stay out of the older corpora, which the reach test lists.
- **The sweeps** of 3.1 that belong to the to-floating half: every `INT` to `FLOAT` and `DOUBLE`,
  every `FLOAT` pattern to `DOUBLE` and to `BOOLEAN` (the condition `NOT (f = 0.0F)` as its one or
  zero), in `VarkaEmitterFloatingCastSuite`, each row held to Java's cast bit for bit. Every
  256th chunk of the 2^32 runs on every build; the whole, under `-Dvarka.sweep=true`, is a `class`
  row of `sql/varka/ci/sweeps.tsv` for the `varka-sweeps` job. *Not timed:* only the slice ran
  here. The `TINYINT`, `SMALLINT` and `BOOLEAN` sweeps stay the coverage rows' fixtures (343a),
  the floating-to-integral and `time_from_seconds` sweeps are 343b2 and 343b3, and the two AVX2
  arms 343b1.
- **The owed false-AND forms.** `CAST(y1 AS DOUBLE)` and `CAST(s1 AS DOUBLE)` fuse now. The plan
  refused every null test over a narrow column under a double kernel, because only the long
  drive marked such a column as read for its word. The word is a bit per row at every lane and
  the double emission already loads a narrow column as the long one does, so
  `VarkaKernelPlan.narrowColumnsOnly` admits `IS NOT NULL` of a narrow column, bare or widened to
  the int lane, and nothing else narrow. `CAST(y IS NOT NULL AS DOUBLE)` fuses with them. Both
  came off `VarkaAnsiForm.DECLINED`. `VarkaReference.flatten` stood a widening in as a long
  column whatever its target; it keeps the target's lane, so a `CondValue` at the int lane over
  such a test rebuilds.
- **Tests.** `VarkaFloatingLaneSuite` plants an AVX-512 host without DQ and one with it, and
  checks the bigint cast declines at the level the first resolves to, fuses at the second's
  level 3, declines at the unknown level, and that `CAST(l AS DOUBLE) + 1.5D` compiles at level 3;
  and that a smallint's null test fuses as `DOUBLE` and `FLOAT`. 343a4 already holds the
  differential harness's level-3 cells, the conformance rows and the emitter suite's refusals.

### 343b1 as built (8 November 2026, branch `varka-343b1-forms`)

`BIGINT` to `DOUBLE` and `FLOAT` fuse at every named emit level: one `convertShape` at level 3,
below it the AVX2 forms of 2.3 in `VarkaConversionLowering`. Not built: 3.2's twins.

- **The level.** `conversionRefusal` declines a long source at the unknown level alone
  (`levelUnknown()`), where `convertsFallBack()` does not hold. So the defaults still decline, no
  coverage row can hold the casts, and aarch64, at the unknown level, declines them too.
- **`L2D`**: the split as 2.3 states it; under `wideConversionForm=MAGIC` the bounded magic behind
  a `VALUE_RANGE` refusal on the `ConvertLane` over `[-2^51, 2^51]`, comparing the long child.
- **`L2F` without a mask**, since C2 on x86 lowers none at two 64-bit lanes: the sticky bit is
  `((m & 0xFFF) + 0xFFF) >>> 12`, the arm a blend by `sel = -(((m >>> 52) + Long.MAX_VALUE) >>>
  63)`, the scale a magic number of `2^64`, the sign `v`'s sign bit set in it.
  `round_to_odd.smt2` is restated over them from the class's constants (50 checks, 7.8 s under Z3,
  6.8 s under cvc5), with the sticky bit, the signed magic and `D2F`'s sign proven too.
- **The option** is read below level 3 alone, so a `Choice` carries a `Subject`: the matrix runs
  `useAVX=2,wideConversionForm=MAGIC`.
- **Tests.** `VarkaEmitterWideConversionSuite`: both forms and arms at three widths, lengths and
  null patterns, to Java's casts at the cells (`2^52` and neighbours, the float and double ties,
  one sticky bit above, `2^53 + 2^29 + 1`, the extremes, every negation) and over 512 seeded
  values a binade of each sign; the magic's decline past `2^51`, not under a null; and 3.4's
  case, the split-and-add form misrounding 4275361147 and 16-17% of `[2^31, 2^32)`. The harness
  holds level 2 in both arms to Spark; the budget weighs the split at 10 lane calls, `L2F` at 24.
- **C2 at `-XX:UseAVX=2`** (`dev/varka_emit.sh --asm`): both forms are 256-bit `ymm` code with no
  call but the traps and no `vcvtqq2pd`; `L2F` ends in `vcvtpd2ps`. The tool's timing, not a
  benchmark: 322 ns a round of 1024 rows for the split, 218 for the magic, 542 for `L2F`; step
  8's measurement on the EPYC runners decides.

*Added 28 November 2026 from branches `varka-343b2a-r2` (`96047835ca4`) and
`varka-343b2b-r2` (`163aac5ecd6`), unchanged: the record of 343b2a and 343b2b as built
there, not merged.*

### 343b2a as built (20 November 2026, branch `varka-343-conversions`)

The first half of 343b2 (4's table), cut at the AVX2 `D2L`: the conversions from a floating lane
to an integral one in the three modes, with `GuardedRange`'s floating bounds. Row 342's
`ym * x` and `ym / x` need only this half (`D2I` and the guard); `dt * x` and `dt / x` need
`D2L` below level 3, which is 343b2b's.

- **The conversions.** `F2I` and `D2I` are one `convertShape` at every level, intrinsic from AVX2
  up (item 0.5); `F2L` and `D2L` are one `convertShape` where the level assures AVX512DQ, and
  `VarkaKernelPlan.conversionRefusal` declines them below level 3 and at the unknown level, by
  name, as it declined `L2D` between 343a4 and 343b1. `emitShapeConversion` names the four.
- **The guard.** `GuardedRange` at a floating lane holds the lane's own bits, a float's
  sign-extended, built by `GuardedRange.floating` and read by `loValue` and `hiValue`; the
  constructor refuses a NaN bound, unordered bounds, and a `FLOAT` bound a float does not hold.
  The mask is the complement of `v >= lo & v <= hi`, so NaN is outside: the integral form
  `v < lo | v > hi` lets NaN through, which is why the floating arm is its own
  (`emitFloatingRangeMask`). The range analysis answers `UNKNOWN` for it. Both modes reuse the
  integral guard's tails: `FAIL` collects through `emitGuardCollect`, crossing a 32-bit mask to a
  64-bit drive through the word, and `NULL` clears the node's word by `~mask.toLong()`. A float
  guard in a long kernel (`try_cast(f AS BIGINT)`) has twice the drive's lanes; its upper lanes
  are past the group's rows, and every reader of the word or the crossed mask reads the drive's
  lanes alone.
- **The bounds** are computed, not typed: `VarkaTypes.castBounds` starts at the double just inside
  `MAX + 1` or `MIN - 1`, rounded to the source type, and steps to the last value that passes
  Spark's own test, `Math.floor(v) <= MAX && Math.ceil(v) >= MIN` with the range read as
  doubles. They come out as 2.4's table; the test holds them to it and to Spark's own
  `FloatExactNumeric`, `DoubleExactNumeric` and `CastUtils.*Exact` at each bound and its outer
  neighbour, for both sources and all four targets.
- **The compiler.** `VarkaFloatingCastCompiler` admits a `FLOAT` or `DOUBLE` source to `TINYINT`,
  `SMALLINT`, `INT` and `BIGINT`: `ConvertLane` to the int or long lane, under ANSI and TRY over
  the guard in `FAIL` or `NULL`, and a narrow target as a `NarrowLane` of the int answer.
  `floatingDecline`'s remaining reason for a floating source names the target type.
- **A narrow target from a double.** The plan refused every narrow node but a column under a double
  drive. A root `NarrowLane` of an int value is stored from the low half of the int species in a
  double kernel as in a long one, so `VarkaKernelPlan.narrowsAnIntAtRoot` admits it and
  `Analysis`' fit rule with it; a narrowing another node reads still declines.
- **Tests.** `VarkaEmitterFloatToIntegralSuite` (JUnit, own JVM): the bounds against Spark, the
  guard's refusals, LEGACY to all four targets (NaN to 0, saturation, `(byte) 300.7` is 44,
  `+-0.7` to 0 and `+-1.5` to `+-1`), TRY's nulls (a float guard in a long kernel among them),
  ANSI's answers inside the bounds and its declines at each bound's outer neighbour, NaN and
  infinity at rows 2 and 16 in both bodies, a null outside lane declining the dense body alone;
  the level rule. 3.1's sweeps of every `FLOAT` pattern to the four targets in LEGACY and TRY
  ran here (every 256th chunk) and move to 343b2b with the AVX2 forms, to keep this part under
  the cap. The fuzz grammar draws a conversion into an integral lane. Coverage rows, family "Floating
  casts to integral types": eight, each floating type to each narrow target, no `BIGINT` target
  (the defaults' unknown level declines `D2L`). The baselines' fuse lists move: `CAST(w AS INT)` and `CAST(x AS INT)` fuse at every
  level, `try_cast(w AS BIGINT)` and `VarkaFloatingBaselineBenchmark`'s `DOUBLE` to `BIGINT`
  where the level is 3 (`fusesToBigint`).
- **Run here** (laptop, 20 November 2026): `catalyst/Test/compile` and `sql/Test/compile`, and
  one run of the new suite with the sweeps in it: 9 of 10 passed, the sweep slice included; `ansiAnswers` failed
  because the helper asserted that no batch declines over cells holding NaN. Its fix, cells
  inside the bounds and one check that lets an outside batch decline, has not been run.
- **Rebuilt** (laptop, 23 November 2026) on master as branch `varka-343b2a-r2`: the suite passed
  9 of 9 there, the fixed `ansiAnswers` included. The gate's census then found no coverage row
  casting `FLOAT` to `SMALLINT`, a pair this part fuses, and the family gained
  `try_cast(f AS SMALLINT)`.
- **Predictions, to score when the owed runs come in:** no committed shape's bytes move
  (`emitted_bytes.json` gains the new rows' shapes only), as 4.1 says; the suite adds under
  30 s to the catalyst tests (one run took 22 s on the laptop, the sweep slice included).

### Decisions taken while the owner was away (20 November 2026)

1. **343b2 is two parts.** The plan sized 343b2 at 900 lines with the AVX2 `D2L`; the floating
   guard, the three modes and their tests came to most of that alone. 343b2a is the conversions
   at the levels where they are one instruction, and 343b2b (stacked on it) the AVX2 `D2L` and
   `F2L`, the truncated magic arm and their proofs. Row 342's interval forms over `D2I` need only
   343b2a, which is why it comes first.
2. **`F2L` and `D2L` decline below level 3 in 343b2a**, by the rule 343a4 set for `L2D` before
   343b1: a scalar Java loop inside a vector kernel is the error to avoid.
3. **The bounds are derived from Spark's predicate** rather than typed from 2.4's table, and the
   table is the test: the table had no `SMALLINT` and `TINYINT` cell for a `FLOAT` source beyond
   "the float neighbours", which the derivation answers.
4. **A narrow target from `DOUBLE` fuses** by admitting the long kernel's narrowing at the root
   under a double drive, rather than declining it to a later part: the store is the same code.
5. **The branch is `varka-343-conversions`**, the brief's name, and the stacked part
   `varka-343b2b-avx2`.

### 343b2b as built (20 November 2026, branch `varka-343b2b-avx2`, stacked on 343b2a)

`D2L` and `F2L` fuse at every named emit level: one `convertShape` at level 3, below it the AVX2
forms of 2.3 in `VarkaConversionLowering`, so row 342's `dt * x` and `dt / x` have their
conversion on the runners. The unknown level still declines them, as it declines `L2D`.

- **The assembly** (`SPLIT` and `XOR_SPLIT`, the default): 2.3's construction with no mask, as
  343b1's `L2F` has none. `e = ((b >>> 52) & 0x7FF) - 1075`, `m` with its implicit bit; the
  left count `e & ~neg` and the right count `-e & neg`, `neg = -(e >>> 63)`, raised to 63 by an
  OR where it passes 63 (the Vector API reads a count modulo 64, and `m < 2^53` gives 0 from 53
  up); the two shifts blended by `neg`; the sign by `(r ^ s) - s`; and where `e > 10` the
  saturated `Long.MAX_VALUE ^ s`, cleared for a NaN, whose test is `bits(inf) - (b & MAX) < 0`.
  38 lane calls on the budget's scale, against 2.3's "about fifteen": the masks the plan's form
  would blend by are integer arithmetic here, a prediction that was wrong by the cost of having
  no mask.
- **The truncated magic** (`MAGIC`): `r = (x + M) - M`, `l = bits(r + M) - bits(M)`, and the step
  toward zero `(s | 1) & nz & differ`, from the bits of `x - r` (non-zero, sign unlike `x`'s), in
  place of 2.3's `|r| > |x|` compare. Under a guard of `|x| <= 2^51` on the child at its own lane
  (`VarkaVectorWalk.emitValueRangeGuard`, the floating mask of 343b2a), so NaN and the
  infinities decline the batch there, which the reference arm may.
- **`F2L`** is `F2D`, exact, then the same form; under `MAGIC` its guard reads the float.
- **The twins** (3.2), scalar, at half the width: the assembly as `float` to `int` from the
  exponent, and the truncated magic as `float` to `int` with `0x1.8p23` within `2^22`, each
  against Java's cast over every 97th float on every build and over every float under
  `-Dvarka.sweep=true`. A scratch run of both 64-bit forms over 20 million random doubles and
  the cells, before they were emitted, found no difference (not committed). 343b1's two twins,
  the split and round-to-odd, are still owed.
- **Tests.** `VarkaEmitterFloatToIntegralSuite` gains the three arms at level 2 over both
  sources in the three modes, the magic's declines past `2^51`, NaN and `-Infinity` in both
  bodies, and the sweeps moved from 343b2a, with `FLOAT` to `BIGINT` at level 3 and under both
  whole-range arms (3.1's "again under `useAVX=2`, once per arm"; the bounded arm cannot sweep,
  since it declines). The baselines' `fusesToBigint` now holds at every named level.
- **Owed:** the proof file a bounded lowering ships with (house rule, VARKA-242): the truncated
  magic over `|x| <= 2^51` and the assembly stated in `VarkaProofFiles`, under both solvers,
  which this window could not run; the C2 reading under `-XX:UseAVX=2` (`dev/varka_emit.sh
  --asm`) to confirm both forms are `ymm` code with no call; and 2.3's measurement on the EPYC
  runners, the assembly against the truncated magic by 4.4's rule.
