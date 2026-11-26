# VARKA-683: The range of a long column

*Planned on 26 November 2026. This plan stands alone: a builder needs it and the repository's
`AGENTS.md` files, with `m8/BUILD_SHEETS.md`, which the sheet names. Not started: milestone 9
waits for the owner's word (`m9/PLAN.md` decision 18). Its code facts were checked on master
`176db41f369`.*

## 1. What this row is

**The row's text** (`m9/PLAN.md`, row 683). The range of a long column:
`LongRangeOps.leastAndGreatest`, null rows skipped by the validity words, with a benchmark
section. Done when it equals a scalar loop over random columns, null patterns, lengths around a
vector and the long extremes at both widths, and its cost a row is recorded.

**What it builds.** One class, `LongRangeOps`, a sibling of `IntRangeOps`, with one public method:

    public static boolean leastAndGreatest(long srcData, long srcValidity, int srcNullCount,
        int length, long[] out)

It writes the least live value of a 64-bit column to `out[0]` and the greatest to `out[1]`. It
returns false when no row is live. It allocates nothing a row: a call maps one or two segments,
which stays inside the allocation sampler's allowance. Two suites and a benchmark class come
with it.

**What it gives the rows after it.** Row 682 calls it from `VarkaKernelRunner.fill`, once a
batch, for each zone node whose operand is a column: the two values choose that batch's
arguments. Row 687 skips the call where the cache holds the two values. Row 688 calls it for the
columns a computed operand reads. Row 691 reads its benchmark for the pass's share of a zoned
kernel's cost.

**What must be merged before it starts.** No row. Two documents: this file, as
`sql/varka/plans/m9/VARKA-683.md`, and the family's rows in `m9/PLAN.md`, which arrive with the
pull request of `VARKA-657.md`. And the owner's word.

**The goals it serves** (`m9/PLAN.md` 1.1, each with the item of 1.2 it moves).

- G1, item 1 (three quarters of `TIMESTAMP_LTZ`'s pairs fused): only through rows 682 and 657.
  This row fuses nothing.
- G2, item 2 (no batch declined over ten years of instants in no order): only through rows 682,
  685 and 686. An exact range lets a batch get a form that covers it, with no second run.
- G7, item 10 (five times the row path where a kernel reads the zone): only through row 691.
  This row records what the pass costs a row. Every zoned kernel pays it until row 687.
- G5, item 8 (the share of fan-out pull requests the cheaper models built): not moved. This is
  no fan-out row. A cheaper model can build it as a first task (section 8).

## 2. What an agent must know

**Varka** is a vectorized expression compiler inside a fork of Apache Spark. It turns a SQL
expression into a **kernel**: a generated Java class whose loop works on a **batch** of rows,
several at a time, through the JDK's Vector API (`jdk.incubator.vector`).

**The family's idea.** A `TIMESTAMP` is a long: microseconds since the epoch in UTC. A kernel
that reads the session time zone adds the zone's offset to each instant. The offset changes a
few times a year. So the kernel gets, for each batch, a short list of change points and offsets
as arguments. Which list fits depends on the least and the greatest instant of the batch. The
evaluator finds those two values before it calls the kernel. This row builds the pass that finds
them. The pass is exact, so no batch runs twice.

**The terms.**

- *Arrow column.* A 64-bit column is a *data buffer* of eight bytes a row, little-endian, and a
  *validity bitmap* of one bit a row: bit `i % 8` of byte `i / 8` is set where row `i` holds a
  value. The *null count* is the number of rows whose bit is clear.
- *Live row.* A row that is not null. A null row still has eight bytes of storage. Those bytes
  are undefined: they can hold any long.
- *Lane, lane group, species.* A vector holds several values side by side. Each is a lane. The
  rows one vector holds are a lane group. `LongVector.SPECIES_PREFERRED` is the widest vector
  type the JVM runs: eight lanes at 512 bits, four at 256, two at 128.
- *Mask.* A `VectorMask`: one boolean a lane, which selects the lanes an operation touches.
- *The Java fallback.* Where the JIT compiler (C2) has no machine instruction for a Vector API
  call, the call runs as plain Java over heap objects. The answer is right. The call is many
  times slower, and it allocates.
- *Identity.* `Long.MAX_VALUE` leaves a `min` unchanged, and `Long.MIN_VALUE` a `max`.
- *The runner and the laptop.* The house's numbers come from GitHub runners with an AMD EPYC
  7763: AVX2, no AVX-512, four long lanes, and two under `-XX:MaxVectorSize=16`. The laptop is
  an AMD Ryzen AI 9 HX PRO 370 with AVX-512: eight long lanes.

**The names.** `<main>` is
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka`, `<test>`
the same path under `src/test/java`. Both hold the package
`org.apache.spark.sql.catalyst.expressions.codegen.varka`.

| name | this row | meaning |
| :--- | :--- | :--- |
| `LongRangeOps.leastAndGreatest(long srcData, long srcValidity, int srcNullCount, int length, long[] out)` | adds, in `<main>` (the family's shared name) | the least live value to `out[0]`, the greatest to `out[1]`; false when no row is live |
| `VarkaLongRangeOpsSuite` | adds, in `<test>` (the family's shared name) | JUnit 5, Java: the pass against a scalar loop |
| `VarkaLongRangeAllocationSuite` | adds, in `<test>` (this plan's name) | JUnit 5, Java, a JVM of its own at two lanes: the pass allocates nothing a row |
| `VarkaLongRangeBenchmark` | adds, in `<test>` (this plan's name) | the pass's cost a row |
| the six-argument `leastAndGreatest(..., boolean noMask)`, `noMask(boolean x86, int lanes)`, `NO_MASK` | adds, package-private (this plan's names) | the pass with its null-skipping loop named; the rule that picks the loop; this JVM's answer |
| `IntRangeOps.allWithin` | reads, never edits | the sibling whose layout this class follows |
| `VarkaSegments.map(long addr, long bytes)` | calls | the one way an address becomes a `MemorySegment`; the memory sanitizer checks it |
| `VarkaVectorTemplateWarmup.beforeVectorCode()` | calls | must run before a class's first vector code |
| `VarkaHostIsa.isX86(String arch)` | calls | whether `os.arch` names x86 |
| `VarkaMemorySanitizer` (`begin`, `register`, `checked`, `end`, `ENABLED`) | calls, in the suite | under `-Dvarka.sanitizeMemory=true`, checks each mapping against the registered buffers |
| `VarkaAllocationSampler` (`supported`, `allocatedBytes`, `suspect`) | calls, in tests | bytes this thread allocated; `suspect` allows 4096 bytes a call and one byte a row |
| `@VarkaSuite`, `VarkaSuite.C2_CODE` | uses | the annotation of a JUnit 5 Varka suite; the tag of a test about compiled code |
| `VarkaBenchmarkBase` (`section`, `benchmark`, `runCases`, `note`, `dryRun`, `require`) | extends | the Java base of a benchmark |

## 3. The admission check

Each fact was read on `176db41f369`. Commands run from the repository root. Facts 11 to 13 are
HotSpot's: they were read in JDK 25's source, tag `jdk-25-ga`, at
`/home/max/proj/openjdk-build/jdk25` (`<jdk>`), which is outside the repository.

| # | fact | command | prints |
| ---: | :--- | :--- | :--- |
| 1 | Nothing returns a least or a greatest value. `IntRangeOps.allWithin` tests an `int` interval and returns a boolean | `grep -n 'public static' <main>/IntRangeOps.java`; `git grep -c LongRangeOps -- sql/catalyst/src sql/core/src` | one line, `allWithin`; nothing |
| 2 | The sibling's layout: a return at once for no row or no live row; segments of `length * 4L` and `(length + 7) / 8L` bytes; a loop to `loopBound`; a mask from the validity bits | `grep -n -e 'srcNullCount == length' -e 'VarkaSegments.map' -e 'loopBound(' -e 'fromLong' <main>/IntRangeOps.java` | 7 lines |
| 3 | The caller, `VarkaKernelRunner.fill`, passes a validity address of `0L` when every row is null, and Arrow's null count | `grep -n -e 'nullCount == len ? 0L' -e 'IntRangeOps.allWithin' sql/core/src/main/java/org/apache/spark/sql/execution/varka/VarkaKernelRunner.java` | 2 lines |
| 4 | A helper that runs vector code beside a kernel warms the shared templates in its static initializer | `grep -c beforeVectorCode <main>/IntRangeOps.java <main>/SelectionVectorOps.java` | `:1` twice |
| 5 | At two 64-bit lanes x86's C2 lowers no mask, whatever the instruction set | `grep -n 'vlen == 2' <main>/VarkaKernelPlan.java`; `grep -n 'lowers no mask at all' sql/varka/skills/the-jit.md` | one line each |
| 6 | The house's census cell `long.fromLong`, a long mask from a word and a blend: the fallback at 128 bits, intrinsic at 256 and 512; the runner's cells read the same (`sql/varka/skills/vector-api-and-width.md`, "The operator census") | `python3 -c "import json;d=json.load(open('sql/varka/operator_census.json'))['widths'];print([d[w]['long.fromLong']['verdict'] for w in ('128','256','512')])"` | `['F', 'I', 'I']` |
| 7 | `LongVector.lane(k)` at a constant index is lowered at two long lanes under AVX2 | `grep -n 'A lane read, a lane insert' sql/varka/skills/vector-api-and-width.md` | one heading |
| 8 | On aarch64 C2 lowers `VectorMask.fromLong` only with SVE2's bit-permute extension | `grep -n supports_svebitperm <main>/VarkaMaskFromWord.java` | one line |
| 9 | The family's timing spike (the probe, `sql/varka/spikes/zone-offset`) priced the pass as `least.min(v)` and `greatest.max(v)` over a heap array with no nulls: 0.058, 0.232 and 0.393 ns a row at eight, four and two lanes, on the laptop | `grep -n 'the pass for least and greatest' sql/varka/spikes/zone-offset/results/forms.txt` | 3 lines |
| 10 | NullAway checks every class that `dev/varka_nullaway_pending.txt` does not list, and the list may not grow. `IntRangeOps` is on it | `grep -c IntRangeOps dev/varka_nullaway_pending.txt` | `1` |
| 11 | `Matcher::match_rule_supported_vector`: a long `MinV` or `MaxV` needs SSE4 only; a long `MinReductionV` or `MaxReductionV` needs AVX-512. So `min` and `max` are instructions on the runner, and `reduceLanes(MIN)` is the Java fallback there | `grep -n -A5 'case Op_MinReductionV' <jdk>/src/hotspot/cpu/x86/x86.ad` | the `T_LONG` test on `UseAVX < 3` |
| 12 | `C2_MacroAssembler::vpminmax`: under AVX2 a long `min` is a `vpcmpgtq` and a `vblendvpd`, with AVX-512 one `vpminsq`. Neither makes a `VectorMask` | `grep -n -B5 'vblendvpd(dst, src1, src2, dst, vlen_enc);' <jdk>/src/hotspot/cpu/x86/c2_MacroAssembler_x86.cpp` | both forms |
| 13 | `Long256Vector.lane(int)` is a switch whose arms pass a constant index, so `lane(j)` in a loop over the lanes stays intrinsic | `grep -n -A8 'public long lane(int i)' <jdk>/src/jdk.incubator.vector/share/classes/jdk/incubator/vector/Long256Vector.java` | the switch |

Two more facts place the tests and the benchmark. The input bound's check is benchmarked end to
end only: `VarkaThroughputBenchmark` (sql/core) pairs `date_add(d, i)` with
`d + CAST(i AS INTERVAL DAY)`, and no benchmark calls `IntRangeOps` alone. A JUnit suite that
needs JVM flags of its own sets `ownJvm` and `jvmFlags` on `@VarkaSuite`, as
`VarkaDecimalRemainderAllocationSuite` does. A suite run in sbt takes a JVM flag through
`set Test/javaOptions`, the spelling in `VarkaEmitterTestBase`'s class comment.

**Not checked.** No JVM ran for this plan. That each loop of section 4 compiles to vector
instructions at each width rests on the facts above, not on a compile log. The sbt summaries and
the JaCoCo line of section 5 follow the forms on record (`m7/VARKA-312.md` 9), with counts
worked out by hand. `m9/PLAN.md` on `176db41f369` has no row 683 yet.

## 4. The design

### 4.1 The contract

| case | returns | `out[0]`, `out[1]` | what is read |
| :--- | :--- | :--- | :--- |
| `length == 0` | false | `Long.MAX_VALUE`, `Long.MIN_VALUE`: the empty range | nothing; both addresses may be `0L` |
| `srcNullCount == length`: every row null | false | the empty range | nothing; both addresses may be `0L` |
| `srcNullCount == 0` | true | the least and greatest of all rows | `length * 8` bytes of data; the validity address may be `0L` |
| `0 < srcNullCount < length` | true | the least and greatest of the rows whose bit is set | the data, and `(length + 7) / 8` bytes of the bitmap |
| the same, and no row's bit is set (a wrong null count) | false | the empty range | the same |
| one row, or a length that is no multiple of the lane count | as above | as above | the rows past the last whole lane group go through the scalar tail |
| `Long.MIN_VALUE` or `Long.MAX_VALUE` live | true | it is the least or the greatest | |
| either end as the storage of a null row | as above | it is not a value | |
| every live value `Long.MAX_VALUE` | true | both `Long.MAX_VALUE` | |

- The method writes `out` on every call, and its return value is `out[0] <= out[1]`. A caller
  that drops the return value finds the empty range, never a range an earlier batch left.
- Preconditions, not checked: `length >= 0`; `srcNullCount` is the count of clear bits among the
  rows; `out` holds two elements or more.
- The span `out[1] - out[0]` can leave a long. This row returns the two ends; their reader
  handles the span.
- The method declines nothing and throws nothing of its own. No reason text belongs to it.

### 4.2 The members, and which loop skips nulls

The class follows `IntRangeOps`: `public final`, a private constructor, static members only. Its
fields and its rule, in this order, shown one a line (the file wraps them the house's way):

    private static final VectorSpecies<Long> SPECIES = LongVector.SPECIES_PREFERRED;
    static { VarkaVectorTemplateWarmup.beforeVectorCode(); }      // say: the class runs vector code
    private static final ByteOrder ORDER = ByteOrder.LITTLE_ENDIAN;
    private static final ValueLayout.OfLong WORD = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ORDER);
    private static final int LANES = SPECIES.length();
    private static final long GROUP_MASK = (1L << LANES) - 1;    // one lane group's validity bits
    private static final long[] LANE_INDEX = new long[LANES];    // a static block sets [j] = j
    static final boolean NO_MASK = noMask(VarkaHostIsa.isX86(System.getProperty("os.arch", "")), LANES);
    static boolean noMask(boolean x86, int lanes) { return !(x86 && lanes > 2); }

Null rows are skipped with a mask only where the record shows that C2 lowers a long mask from a
word and a blend: x86 above two lanes (fact 6). Everywhere else the loop uses no mask: x86 at
two lanes or fewer (fact 5), and every other architecture (fact 8).

### 4.3 The entry

The public method returns the six-argument method's answer, with `NO_MASK` as the sixth
argument. The six-argument method is package-private, with `boolean noMask` last:

1. `out[0] = Long.MAX_VALUE; out[1] = Long.MIN_VALUE;`
2. `if (length == 0 || srcNullCount == length) { return false; }`
3. `MemorySegment src = VarkaSegments.map(srcData, length * 8L);`
4. If `srcNullCount > 0`, map the bitmap,
   `MemorySegment validity = VarkaSegments.map(srcValidity, (length + 7) / 8L);`, then call
   `nullsNoMask(src, validity, length, out)` where `noMask` is true and
   `nullsMasked(src, validity, length, out)` where it is false. Otherwise call
   `dense(src, length, out)`.
5. `return out[0] <= out[1];`

The six-argument form exists for the suite and the benchmark: through it both null-skipping
loops run in every JVM, at every width. No segment is ever null, so NullAway passes the class
(fact 10). `out` is the accumulator: every loop folds into it.

### 4.4 The three loops

Each is a private static method that returns nothing. Each has this frame. The blocks below
show statements; the file indents them the house's way, two columns a level.

      LongVector least = LongVector.broadcast(SPECIES, Long.MAX_VALUE);
      LongVector greatest = LongVector.broadcast(SPECIES, Long.MIN_VALUE);
      long loopBound = SPECIES.loopBound(length);
      long i = 0;
      for (; i < loopBound; i += LANES) {
        LongVector v = LongVector.fromMemorySegment(SPECIES, src, i * 8L, ORDER);
        // the loop's own lines
      }
      fold(least, greatest, out);
      // the loop's tail

**`dense(MemorySegment src, int length, long[] out)`**: no null row; the probe's loop (fact 9).
Its tail is `for (; i < length; i++) { take(src.get(WORD, i * 8L), out); }`. Its own lines:

      least = least.min(v);
      greatest = greatest.max(v);

**`nullsMasked(MemorySegment src, MemorySegment validity, int length, long[] out)`**. The two
broadcasts get the names `noLeast` and `noGreatest`, and `least` and `greatest` start from them.
The mask comes from the group's validity bits. A null lane takes the identity of `min` in one
blend and of `max` in another. Both blends are on the loaded vector, off the chain that carries
`least` and `greatest` from group to group. The tail is one call,
`nullsTail(src, validity, i, length, out);`. Its own lines:

      VectorMask<Long> live = VectorMask.fromLong(SPECIES, groupBits(validity, i));
      least = least.min(noLeast.blend(v, live));
      greatest = greatest.max(noGreatest.blend(v, live));

**`nullsNoMask`**, with the same parameters and the same tail. The first broadcast gets the name
`noLeast`, and `least` starts from it. Before the loop there is also
`LongVector laneIndex = LongVector.fromArray(SPECIES, LANE_INDEX, 0);`. The loop makes the same
blend as bit arithmetic. The group's validity bits are broadcast to every lane. Lane `j` shifts
them right by `j`, keeps bit 0 and adds -1: the lane holds 0 where its row is live and -1, all
ones, where it is null. Its own lines:

      // 0 in a live lane, -1 in a null one: the lane's validity bit, less one.
      LongVector dead = LongVector.broadcast(SPECIES, groupBits(validity, i))
          .lanewise(VectorOperators.LSHR, laneIndex).and(1L).add(-1L);
      // x ^ ((x ^ y) & m) is y where m is all ones: Long.MAX_VALUE in a null lane.
      LongVector forLeast =
          v.lanewise(VectorOperators.XOR, v.lanewise(VectorOperators.XOR, noLeast).and(dead));
      least = least.min(forLeast);
      // Long.MAX_VALUE ^ -1 is Long.MIN_VALUE: the null lanes become the identity of max.
      greatest = greatest.max(forLeast.lanewise(VectorOperators.XOR, dead));

Every call in it is a load, a broadcast, a shift by a vector, an AND, an add, an XOR, a `min` or
a `max`. None makes or reads a `VectorMask`.

### 4.5 The fold, the tail and the validity bits

- `fold(LongVector least, LongVector greatest, long[] out)`: for `j` from 0 to `LANES - 1`,
  `out[0] = Math.min(out[0], least.lane(j));` and
  `out[1] = Math.max(out[1], greatest.lane(j));`. Never `reduceLanes`: a long `MIN` or `MAX`
  reduction is the Java fallback under AVX2 (fact 11), and `lane(j)` is lowered there (facts 7
  and 13).
- `take(long value, long[] out)`: `out[0] = Math.min(out[0], value);` and
  `out[1] = Math.max(out[1], value);`.
- `nullsTail(MemorySegment src, MemorySegment validity, long from, int length, long[] out)`: for
  `long i` from `from` to `length - 1`,
  `int bits = validity.get(ValueLayout.JAVA_BYTE, i >>> 3);` and then, if
  `((bits >>> (int) (i & 7)) & 1) != 0`, `take(src.get(WORD, i * 8L), out);`.
- `groupBits(MemorySegment validity, long row)`: the body of `IntRangeOps.bitsAt` with `rows`
  replaced by `LANES` and its last two lines by `return (bits >>> shift) & GROUP_MASK;`. It
  reads only the bytes the group's rows span, never a 64-bit word: Arrow promises no padding
  past `(length + 7) / 8` bytes. It is a third private copy of that read, as
  `SelectionVectorOps` and `IntRangeOps` each keep one.
- **A scalar tail, where the sibling has a masked epilogue.** The tail is `LANES - 1` rows at
  most, and a masked epilogue would be the Java fallback at two lanes.
- **Two lanes on x86.** `dense` and `nullsNoMask` are the only vector code that runs there.
  `nullsMasked` runs there only when a test names it.
- **AVX2 against AVX-512.** The source is the same. C2 gives `min` one instruction with AVX-512
  and two under AVX2 (fact 12), and the mask a `kmov` with AVX-512 and a `pdep` sequence under
  AVX2.
- **One lane** (`-XX:MaxVectorSize=8`): the loops are right and run as the Java fallback. No
  measured host runs it, and nothing is built for it.

### 4.6 The comments

The class comment says, in this order: what the pass returns and what it is for (the range of a
batch, which VARKA-682's caller will read; this row adds no caller, and the comment names none);
that it lives beside `IntRangeOps` for that class's reason; the shape (raw addresses, the validity
read only when `0 < srcNullCount < length`, a loop of whole lane groups, a scalar tail); that a
null lane's storage is undefined, so it takes the identity before it meets the accumulators; why
there are two null-skipping loops and which runs where (4.2); why the fold is `lane(j)` (4.5);
what a call with no live row returns (4.1). The public method's comment has the sibling's `@param`
lines, one for `out`, and a `@return`. A comment describes the code as it is: no history, and no
cost.

### 4.7 What this row does not build

- A second pair of accumulators in `dense`, or a scalar loop at two lanes. Section 7 measures
  both; a later row takes what wins.
- One validity word for each 64 rows in place of `groupBits` for each lane group
  (`sql/varka/skills/vector-api-and-width.md`, "On AVX2 a validity word made into a lane mask").
- Any caller. `VarkaKernelRunner` is row 682's. And no edit of `IntRangeOps`.

## 5. The parts

One part, one pull request.

- **Title:** `[VARKA-683] Find the least and greatest live value of a long column`
- **Branch:** `varka-683-build`, in a worktree of its own, from `origin/master`. The branch
  `varka-683` exists: it held this plan.
- **Size:** about 760 counted lines: the class 210, the suite 240, the allocation suite 100, the
  benchmark 180, this file's outcome 30.

Follow `sql/varka/plans/m8/BUILD_SHEETS.md`. In short: read this sheet first; run
`dev/varka_precommit.sh --install-hook` once; start every shell that builds or tests with

    export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
    export SPARK_LOCAL_IP=127.0.0.1
    export PATH=$JAVA_HOME/bin:$HOME/.venv-ruff/bin:$PATH
    export VARKA_HEAVY_LOCK=/home/max/varka-ops/locks/<slot>.lock

where `<slot>` is the lock slot the lead named, or `heavy` (the laptop also has JDK 26, and
`dev/varka_errorprone.sh` refuses any JDK but 25); run one sbt command at a time, each under
`flock "$VARKA_HEAVY_LOCK"`; run `dev/varka_push_check.py` bare; stop at a local commit. This
sheet differs from that file in three places, and here the sheet wins: it has four exports, with
`JAVA_HOME`; the plan lint takes `sql/varka/plans/m9/PLAN.md` as its argument; and the row to
edit is in `m9/PLAN.md`. On any other difference, stop and report.

### B0. The premises

Each command prints what the table says, or the builder stops and reports it.

| command | prints |
| :--- | :--- |
| `git show origin/master:sql/varka/plans/m9/VARKA-683.md`, its first line | `# VARKA-683: The range of a long column` |
| `grep -c 'The range of a long column. \*\*Planned\*\* (`VARKA-683.md`' sql/varka/plans/m9/PLAN.md` | `1` |
| `git grep -c LongRangeOps -- sql/catalyst/src sql/core/src` | nothing |
| `grep -c 'public static boolean allWithin(long srcData, long srcValidity, int srcNullCount, int length,' <main>/IntRangeOps.java` | `1` |
| `grep -c 'static void beforeVectorCode()' <main>/VarkaVectorTemplateWarmup.java` | `1` |
| `grep -c 'public static MemorySegment map(long addr, long bytes)' <main>/VarkaSegments.java` | `1` |
| `grep -c 'public static boolean isX86(String arch)' <main>/VarkaHostIsa.java` | `1` |
| `grep -c 'public static boolean suspect(long allocatedBytes, int rows)' <main>/VarkaAllocationSampler.java` | `1` |
| `grep -c 'public static void register(String role, int index, long address, long capacity)' <main>/VarkaMemorySanitizer.java` | `1` |
| `grep -c 'String\[\] jvmFlags() default {};' <test>/VarkaSuite.java` | `1` |
| `grep -c 'protected static void runCases(VarkaBenchmark benchmark)' sql/catalyst/src/test/java/org/apache/spark/sql/VarkaBenchmarkBase.java` | `1` |
| `grep -c '{@code IntRangeOps}, {@code SelectionVectorOps}' <main>/package-info.java` | `1` |
| the command of fact 6 | `['F', 'I', 'I']` |
| `java -version` | version `25` |

### B1. The steps, in order

1. **`<main>/LongRangeOps.java`**, new: the license header of `IntRangeOps.java`; the package;
   the imports `java.lang.foreign.MemorySegment`, `java.lang.foreign.ValueLayout` and
   `java.nio.ByteOrder`, a blank line, then `jdk.incubator.vector.LongVector`, `VectorMask`,
   `VectorOperators` and `VectorSpecies`; the class comment (4.6); the fields (4.2);
   `private LongRangeOps() {}`; then `noMask`, the two entries (4.3), the three loops (4.4) and
   the four helpers (4.5).
2. **`<main>/package-info.java`**: in the "Emitter support" item, `{@code LongRangeOps}` goes
   after `{@code IntRangeOps},`. Wrap the list again within 100 columns. Nothing else changes.
3. **`<test>/VarkaLongRangeOpsSuite.java`** and **`<test>/VarkaLongRangeAllocationSuite.java`**,
   new (B2).
4. **`<test>/VarkaLongRangeBenchmark.java`**, new (section 7).
5. **`sql/varka/plans/m9/PLAN.md`**, row 683: the cell opens with the line below, and the old
   text follows it unchanged.

       **Started** (built on branch varka-683 on <date>; left: the review, the runner's two results files and the merge).

6. **`sql/varka/plans/m9/VARKA-683.md`**, section 11: what was built, each command of B4 with
   its result, each planted fault with the test that failed, the JaCoCo line, the size count,
   and predictions 8 to 10 scored. Predictions 1 to 7 are the lead's to score.

### B2. The tests

**`VarkaLongRangeOpsSuite`** is `@VarkaSuite public final class`, in the package of
`LongRangeOps`. Every test has a `@DisplayName` sentence. Its helpers:

- `scalar(long[] values, boolean[] isNull, long[] out)`: the definition. It sets the empty
  range, folds every row that is not null with `Math.min` and `Math.max`, and returns whether
  any row was live.
- `range(long[] values, boolean[] isNull, int entry, long[] out)`: the pass over the values laid
  out as an Arrow column, the way the companion of `IntRangeOpsSuite` lays out ints. In a
  confined `Arena`, with `n` the length, the data is
  `arena.allocate(Math.max(n * 8L, 1L), 8)` and the validity
  `arena.allocate(Math.max((n + 7) / 8L, 1L), 8)`. Row `i` is written with
  `data.set(ValueLayout.JAVA_LONG, i * 8L, values[i])`; a live row sets bit `i % 8` of byte
  `i / 8`; a null row adds one to `nulls`. Every bit from `n` to the end of the last byte is set
  too: such bits are stale and are no rows. The validity address passed is `0L` when
  `nulls == 0 || nulls == n`, as the contract allows. Around the call:
  `VarkaMemorySanitizer.begin()`, `register("input data", 0, data.address(), n * 8L)`,
  `register("input validity", 0, validity.address(), (n + 7) / 8L)`, and `end()` in a `finally`.
  Where `VarkaMemorySanitizer.ENABLED`, `n > 0` and `nulls < n`, `checked()` must have grown
  across the call. `entry` 0 is the public method, 1 the six-argument one with `noMask` true, 2
  the same with false.
- `rangeClaimed(long[] values, boolean[] isNull, int claimedNulls, int entry, long[] out)`: the
  same layout and the same sanitizer calls, but the validity address is always the real one and
  the null count is `claimedNulls`, whatever the bitmap holds. `range` is this helper with the
  counted nulls and with the address `0L` where the contract allows it.
- `check(values, isNull, what)`: for each of the three entries, `out` starts as `{7L, 7L}`; the
  return value and `out` equal `scalar`'s; the message holds `what` and the entry's name.

| test, by its display name | what it does | the failure only it catches |
| :--- | :--- | :--- |
| `random columns agree with a scalar loop at every length and null pattern` | lengths 0, 1, 2, 3, 4, 5, 7, 8, 9, 15, 16, 17, 31, 32, 33, 63, 64, 65, 127, 129, 1000, 4096, 4097; nine patterns: no nulls, every other row, one row in ten, only the first, only the last, all but the first, all but the last, a random half, every row; values and the storage of null rows from `new Random(683L * 1_000_003L + length)`; `check` | a lane group or a tail row counted twice or not at all; validity bits read at the wrong shift |
| `an end of a long is a value where it is live and is not read under a null row` | lengths 1, 2, 3, 4, 5, 8, 9, 16, 17, 33, 64, 65; position first, middle, last; values `(i * 37L) % 2001 - 1000`; each end of a long live at the position; then null there; then the other end live beside it; then every live value one end, the other end under every other row; `check`, and for the last case the pair asserted by value | a null row's storage read as a value; an identity taken for "no value" |
| `no row, and every row null, give false and the empty range, and read no address` | each entry with both addresses `0L`: length 0; length 5 with null count 5; `out` from `{7L, 7L}` | a buffer read where the contract promises none |
| `a bitmap with no live row gives false, whatever the null count says` | lengths 2, 9, 17, 65, 129; every row's bit clear, the stale bits set; through `rangeClaimed` with null count 1, so that the bitmap is read; each entry | a return value taken from the null count, not from the rows; a stale bit read as a row |
| `nulls are skipped with a mask on x86 above two long lanes, and nowhere else` | `noMask(true, 1)`, `(true, 2)`, `(false, 2)`, `(false, 4)` are true; `(true, 4)`, `(true, 8)` are false; `NO_MASK` equals `noMask` of this host's `os.arch` and `LongVector.SPECIES_PREFERRED.length()` | the rule moved by one lane count |

**`VarkaLongRangeAllocationSuite`**, in the same package, holds the failure that no answer test
catches: a loop that is right and is the Java fallback. Its annotation is the one of
`VarkaDecimalRemainderAllocationSuite`:

    @VarkaSuite(ownJvm = true, jvmFlags = {"-XX:+IgnoreUnrecognizedVMOptions", "-XX:UseAVX=2",
        "-XX:MaxVectorSize=16", "-Dvarka.warmup.vectorTemplates=true"})

One test, `@Tag(VarkaSuite.C2_CODE)`, named
`the pass at two long lanes allocates within the warm-up's allowance`:

1. `assumeTrue` three times, as that sibling: `VarkaAllocationSampler.supported()`;
   `VarkaHostIsa.isX86(System.getProperty("os.arch", ""))`;
   `LongVector.SPECIES_PREFERRED.length() == 2`.
2. 4096 rows in an `Arena`; values and nulls from `new Random(683)`, a row null where
   `random.nextInt(10) == 0`; the scalar answer kept.
3. For the null-free column (null count 0, validity `0L`), then for the column with nulls: up to
   40 rounds. A round calls the public method for 250 ms, then samples 16 calls: the bytes
   allocated across each, and `VarkaAllocationSampler.suspect(bytes, 4096)`. The first round
   with no suspect ends the rounds. After 40 rounds the test fails, with the count of suspects
   and the worst bytes in its message.
4. The last call's `out` equals the scalar answer.

### B3. The planted faults

Commit the work first. Plant one fault in `LongRangeOps`, rerun command 1 of B4, see the named
test fail, `git checkout -- <file>`, then the next.

| # | the fault | must fail |
| ---: | :--- | :--- |
| 1 | `nullsTail` takes every row: the `if` removed | `random columns agree with a scalar loop ...`, and others |
| 2 | `nullsMasked`: `least.min(noGreatest.blend(v, live))` | `an end of a long is a value ...`, and `random columns ...` |
| 3 | `nullsNoMask`: `.add(-1L)` removed | `random columns agree with a scalar loop ...`, and others |
| 4 | the five-argument method passes `false` in place of `NO_MASK` | only `the pass at two long lanes allocates ...`; the other five tests pass |
| 5 | the six-argument method ends with `return true;` | only `a bitmap with no live row gives false ...` |

### B4. The commands, in order, and what each must print

1. Both suites at the host's width:

       flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaLongRangeOpsSuite *VarkaLongRangeAllocationSuite'

   prints `Passed: Total 6, Failed 0, Errors 0, Passed 6`.
2. The suite at two lanes under AVX2, the runner's narrow setting:

       flock "$VARKA_HEAVY_LOCK" build/sbt 'project catalyst' 'set Test/javaOptions ++= Seq("-XX:UseAVX=2", "-XX:MaxVectorSize=16")' 'testOnly *VarkaLongRangeOpsSuite'

   prints `Passed: Total 5, Failed 0, Errors 0, Passed 5`.
3. The same with `"-XX:MaxVectorSize=32"` in place of `"-XX:MaxVectorSize=16"`: four lanes under
   AVX2, the runner's own width. The same line.
4. The suite under the memory sanitizer:

       flock "$VARKA_HEAVY_LOCK" build/sbt 'set LocalProject("catalyst") / Test / javaOptions += "-Dvarka.sanitizeMemory=true"' 'catalyst/testOnly *VarkaLongRangeOpsSuite'

   prints `Passed: Total 5, Failed 0, Errors 0, Passed 5`.
5. The benchmark's dry run. It times nothing, so the shared laptop does not matter:

       flock "$VARKA_HEAVY_LOCK" build/sbt 'project catalyst' 'set Test/javaOptions += "-Dvarka.bench.dryRun=true"' 'Test/runMain org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaLongRangeBenchmark'

   prints two lines that start `checked: every arm's range equals the scalar loop's`, then
   `[success]`.
6. JaCoCo over the class. `S` is a new, empty directory in the builder's scratch area.

       J=$HOME/.m2/repository/org/jacoco; M=$HOME/.m2/repository/org/ow2/asm
       A=$J/org.jacoco.agent/0.8.15/org.jacoco.agent-0.8.15-runtime.jar
       flock "$VARKA_HEAVY_LOCK" build/sbt "set LocalProject(\"catalyst\") / Test / javaOptions += \"-javaagent:$A=destfile=$S/jacoco.exec,append=true,includes=org.apache.spark.sql.catalyst.expressions.codegen.varka.*\"" 'catalyst/testOnly *VarkaLongRangeOpsSuite'
       cp=$J/org.jacoco.core/0.8.15/org.jacoco.core-0.8.15.jar:sql/catalyst/target/scala-2.13/classes:common/utils/target/scala-2.13/classes
       cp=$cp:$M/asm/9.10.1/asm-9.10.1.jar:$M/asm-commons/9.10.1/asm-commons-9.10.1.jar:$M/asm-tree/9.10.1/asm-tree-9.10.1.jar
       java -cp "$cp" dev/varka_gen_coverage/VarkaEmitterCoverage.java "$S/jacoco.exec" sql/catalyst/target/scala-2.13/classes sql/catalyst/src/main/java sql/varka/coverage/reach "$S/emitter.md"
       grep '^| LongRangeOps.java ' "$S/emitter.md"

   prints `| LongRangeOps.java | 11 | 11 | 100.0% | 100.0% | 0 |`: eleven methods, each
   entered, no branch missed. `$S/emitter.md` is scratch. The committed
   `sql/varka/coverage/emitter.md` stays as it is: nothing gates it, and the next coverage run
   writes the class's row.
7. No generated file moves, since the row emits nothing. This exits 0:

       git diff --exit-code -- sql/varka/coverage.json sql/varka/emitted_bytes.json sql/varka/width_audit.json sql/varka/operator_census.json

8. The linters, each exiting 0: `dev/varka_precommit.sh`; after `git fetch -q origin master`,
   `dev/varka_errorprone.sh --master "$(git rev-parse origin/master)"` and `dev/lint-java`, each
   under `flock`; `python3 dev/varka_plan_lint.py --check --offline sql/varka/plans/m9/PLAN.md`.
9. The commit: the title above as its subject, a body of a few lines, one `Generated-by:` line.
   Then the planted faults of B3, and the commit amended with their record in section 11. Then
   `python3 dev/varka_push_check.py`, bare: it exits 0 and prints a size near 760.

### B5. Done when, stop rules, and not here

**Done when** commands 1 to 9 print what B4 says, each fault of B3 failed its test, and section
11 is filled. The builder stops at the local commit and reports the branch, the commit, each
command's result and the size.

**Stop and report** on: a premise of B0 that does not hold; a test that fails on the unchanged
design, the allocation suite above all (report the bytes a call; do not change a loop); a JaCoCo
line with a missed branch that a test of B2 should reach; a size over 900; a javac, Error Prone
or NullAway finding that the code of section 4 itself causes; anything this sheet does not
decide.

**Not here:** `IntRangeOps`, `SelectionVectorOps` and their suites; `VarkaKernelRunner` and
every other caller; `VarkaVectorTemplateWarmup`; any emit option, IR record, generated file or
`dev/varka_nullaway_pending.txt`; a results file (the lead commits them); a timed run of the
benchmark on the laptop; a push, a pull request or a workflow dispatch.

## 6. Tests, proofs, fuzzing and coverage

| suite | what it holds | the failure only it catches |
| :--- | :--- | :--- |
| `VarkaLongRangeOpsSuite` (catalyst, JUnit 5, Java) | the pass and both null-skipping loops against a scalar loop: random columns by seed, nine null patterns, 23 lengths, the ends of a long live and as the storage of null rows, no live row, the rule | a null row's storage read as a value |
| `VarkaLongRangeAllocationSuite` (catalyst, JUnit 5, Java, its own JVM) | at two lanes under AVX2 a warmed call stays within the sampler's allowance, with and without nulls | a loop that is right and is the Java fallback |
| the first suite under `-Dvarka.sanitizeMemory=true`, which the option matrix passes | every mapping inside buffers registered at `length * 8` and `(length + 7) / 8` bytes | a validity read past the bitmap's last byte |

- **Widths.** B4 runs eight, four and two lanes on the laptop. A Build runs the runner's width,
  and the scheduled sweeps run the suites under `-XX:UseAVX=2 -XX:MaxVectorSize=16`.
- **Proofs.** None: the pass has no bounded lowering. `min` and `max` are exact over every long.
- **Fuzzing.** No fuzzer reaches the pass until row 682 calls it. The suite's random columns are
  its fuzz; a failure's message names the length that seeds the replay.
- **JaCoCo.** Command 6: every branch of `LongRangeOps` reached, the line quoted in the pull
  request.
- **Mutation.** The family's mutation run is over the programs' `fill`, row 682's. Here the five
  planted faults stand in for it.

## 7. Measurement

**The class.** `VarkaLongRangeBenchmark extends VarkaBenchmarkBase`, in `<test>`, since it calls
the six-argument method. No kernel calls the pass yet, so it is timed alone, as the probe timed
it; the end-to-end pair is row 691's. One section,
`the least and greatest live value of a long column`, with two tables. A call covers 4096 rows,
the probe's batch, and an iteration is 32768 calls. The data is the probe's, in native memory:
instants of 2025 in no order, `from + (long) (random.nextDouble() * (until - from))` with
`new Random(42)`. A row is null where `random.nextInt(10) == 0`. The class comment says what it
measures and holds the two dispatch commands below.

| table | case | what runs |
| :--- | :--- | :--- |
| `no nulls, 4096 rows a call` | `a scalar loop (the control)` | `Math.min` and `Math.max` over the segment |
| | `LongRangeOps.leastAndGreatest` | the public method, null count 0 |
| | `two pairs of accumulators (this benchmark's loop)` | the dense loop of 4.4 with two loads an iteration, rows `i` and `i + LANES`, each into its own pair; one more group where one is left; the pairs meet by `min` and `max` before the fold; held in the benchmark only |
| `one row in ten null, 4096 rows a call` | `a scalar loop (the control)` | the same loop under an `if` on the row's validity bit |
| | `nulls skipped with a mask` | the six-argument method, `noMask` false |
| | `nulls skipped with no mask` | the six-argument method, `noMask` true |

- Labels carry no width, so the two files diff by label. A `note` gives the lane count and
  whether `NO_MASK` is true.
- Before a table is timed, each arm's return value and range must equal the control's
  (`require`). Then `note("checked: every arm's range equals the scalar loop's, " + table)`.
- Cases run through `runCases`, so `-Dvarka.bench.dryRun=true` checks the arms and times
  nothing. Each case folds `out[0] ^ out[1]` into a field, which a volatile field takes after
  the table.
- After a timed table, a `note` gives the bytes one call of each arm allocates, the least of
  eight calls, from `VarkaAllocationSampler.allocatedBytes()`.
- The cost a row is 1000 divided by `Rate(M/s)`. The `Per Row(ns)` column has one decimal.

**Where it runs.** The lead dispatches it on the pull request, twice, on vecbricks/varka:

    gh workflow run benchmark.yml --repo vecbricks/varka --ref master -f pr=<N> -f class=VarkaLongRangeBenchmark -f jdk=25 -f scala=2.13 -f num-splits=1 -f create-commit=false -f expected-cpu="AMD EPYC 7763"

and the same with `-f extra-java-options="-XX:MaxVectorSize=16"`. The artifacts are committed to
the pull request as
`sql/catalyst/benchmarks/VarkaLongRangeBenchmark-jdk25-runner-epyc7763-results.txt` and
`VarkaLongRangeBenchmark-jdk25-runner-epyc7763-128bit-results.txt`, each with a
`-provenance.txt` beside it in the form of `VarkaDecimalStoreBenchmark`'s. A run on another CPU
is dispatched again. The laptop adds `VarkaLongRangeBenchmark-jdk25-laptop-results.txt`, at
512 bits, when it is idle; the merge does not wait for it.

### 7.1 Predictions, registered before the run

The probe ran on the laptop. The datapath control reads 136.3 lane operations a nanosecond at
256 bits and 67.7 at 128 on the laptop
(`VarkaDecimalPairSpikeBenchmark-jdk25-laptop-256bit-provenance.txt`), and 90.1 and 45.0 on the
runner (`VarkaDecimalStoreBenchmark-jdk25-runner-epyc7763-provenance.txt`): the runner does two
thirds of the laptop's work. Predictions 1 and 2 scale the probe's costs by that ratio.

1. `LongRangeOps.leastAndGreatest`, no nulls, 256 bits: 0.35 ns a row, between 0.25 and 0.45
   (a rate between 2222 and 4000 M rows a second). The probe: 0.232.
2. The same at 128 bits: 0.59 ns a row, between 0.45 and 0.75 (1333 to 2222). The probe: 0.393.
3. With one row in ten null, the default arm of a width costs at most three times that width's
   pass with no nulls.
4. At 128 bits `nulls skipped with a mask` costs at least ten times `nulls skipped with no mask`,
   and its note reads at least 245760 bytes a call, 60 a row. Every other arm's note, at both
   widths, reads within 4096 bytes a call. The house's census reads two-lane mask fallbacks at
   68 to 220 bytes a row (`sql/varka/skills/vector-api-and-width.md`).
5. At 256 bits the two null-skipping arms are within 1.3 times of each other. A second run by
   minimums names a winner, or the default stays.
6. The scalar control with no nulls costs between 0.2 and 0.45 ns a row at both widths. So at
   128 bits the pass is not faster than a scalar loop, and at 256 bits it is within 1.3 times of
   it. The reason: under AVX2 C2's long `min` is a compare and a blend, carried from one lane
   group to the next (fact 12), where a scalar loop carries one conditional move a row.
7. `two pairs of accumulators` costs at most three quarters of the one-pair pass at each width.
8. `VarkaLongRangeOpsSuite` takes under 2 s of test time alone in a JVM, the template warm-up
   included, and under 1 s after another Varka suite. The nearest rows of the calibration table
   (`python3 dev/varka_calibration.py`): VARKA-328, a JUnit class with no session, 0.15 s
   against 5 s predicted; and the one sibling with a committed time,
   `VarkaSelectionVectorOpsSuite` at 0.1 s (`sql/varka/ci/suite-times.tsv`).
9. `VarkaLongRangeAllocationSuite` takes under 15 s in its own JVM, about 5 s: the JVM's start,
   the template warm-up and two paths that settle in a round or two. The nearest rows:
   VARKA-342, an own-JVM suite at half its estimate, and `VarkaTwoLaneHost128Suite` at 1.7 s
   (`sql/varka/matrix/suite-seconds.tsv`).
10. The pull request counts about 760 lines, under 900.

If prediction 6 or 7 holds, the lead files a row for the pass's form by width: two pairs of
accumulators, or the scalar loop at two lanes. This row does not wait for it.

## 8. Who builds it

A cheaper model builds the part from this sheet (`m9/PLAN.md` 2.7, goal G5): it is one new class
with no caller, its code is in section 4, and its oracle is the nine commands of B4. The lead
reviews it (`/code-review high`), pushes it, dispatches the two benchmark runs, commits the
results files, scores section 7.1 in section 11 and turns the row's state to `**Done**`.

## 9. Risks

1. **A null row's storage is read as a value.** Held by the suite: both ends of a long under
   null rows, in a lane group and in the tail, through all three entries.
2. **A loop is right and is not lowered.** Answer tests pass on a Java fallback at any width.
   Held by the allocation suite at two lanes under AVX2, by planted fault 4 and by prediction 4.
3. **The pass keeps an allocation after other vector code.** VARKA-604 found a long-lane loop
   that kept one `long[]` a lane group after a polluter of seven species, and the template
   warm-up removed it only where a call covered 64 rows (`m8/VARKA-604.md` 2). This pass covers
   a batch a call. What bounds it: C2 keeps a virtual call, which boxes, only where the traps it
   sums over a compile's inlined copies pass 100 (`sql/varka/skills/the-jit.md`), and these
   loops make about a dozen Vector API calls where that loop makes hundreds. No test here holds
   it: the suite that runs that polluter took 55 s in its own JVM (`m8/VARKA-604.md` 9.2). One
   reading with that polluter settles it; the owner decides who takes it. The same reading
   covers the trap cycle that a counted loop over segment accesses can enter after a JVM's
   kernel history (`the-jit.md`, "A back-edge `profile_predicate` cycle"): these loops have the
   sibling's form, with no stride trick.
4. **The pass is no faster than a scalar loop under AVX2** (prediction 6). Held by the control
   rows and by the two-pair arm, which price both ways out before any row leans on the pass.
5. **With nulls the validity work is most of the cost.** Prediction 3 bounds it; 4.7 names the
   lever.
6. **A read past a buffer.** The mappings are `length * 8` and `(length + 7) / 8` bytes, and
   `groupBits` reads bytes, never words. Held by the sanitizer run (command 4).
7. **A caller's wrong null count.** The pass trusts the bitmap and returns false when it finds
   no live row. Held by the fourth test.
8. **The plan ages.** Held by B0.
9. **The size.** Four new files come to about 760 lines. The house's stop rule at 900 holds it.

## 10. The house rules that bind this row

From `sql/varka/AGENTS.md`:

- "No allocation, streams, lambdas or boxing on a path that runs per batch or per row". The pass
  takes the caller's `out` and makes no array. No vector may reach a call C2 does not lower.
- "an address becomes a segment only through `VarkaSegments.map`". The pre-commit hook rejects a
  raw `.reinterpret(`.
- "A new catalyst suite whose helpers are Java is a JUnit 5 class under `@VarkaSuite`", each
  `@Test` "carrying its sentence as a `@DisplayName`".
- "A comment describes the code as it is." And the code carries no `TODO` or `FIXME`.
- "Explicit imports." And the JDK, not a library: no Guava, no Commons.
- "A pull request stays under 1000 changed lines of hand-written files".
- "Never kill by name pattern". "Never `git stash`."
- "Any performance claim in code comments, plans or docs traces to a committed benchmark result
  file." So no comment in the class quotes a cost.

Do not: call `reduceLanes`; make a `VectorMask` in `dense` or `nullsNoMask`; read the bitmap as
64-bit words; hold a `MemorySegment` that may be null; edit `IntRangeOps`; add an emit option, a
conf or a caller; edit a generated file; time the benchmark on the shared laptop; write a source
line over 100 columns or a non-ASCII character; add a trailer beside the one `Generated-by:`
line; push or open the pull request.

## 11. Outcome

*To be written when the row closes.*
