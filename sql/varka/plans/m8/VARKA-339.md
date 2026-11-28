# VARKA-339: The `TIMESTAMP_NTZ` arithmetic


## A. What is left of this row, and how it is built (28 November 2026)

*NEW SECTION, written on 28 November 2026 for builders that have only this file and the
repository's `AGENTS.md` files; revised the same day (the fix round): each part has a build
sheet in the house form. Its code facts were checked on master `6dafb8bd2f6` (origin/master
`04bfdd3673d` differs by plan files only). No JVM was started for it; A.13 lists what only a
JVM settles. Sections 0 to 10 below are the row's record and are unchanged, word for word,
except that 10.2 (step 4 as built on its branch) is added from that branch, marked. Every build
sheet says "Follow `BUILD_SHEETS.md`": its rules bind each part in full. A.5.0 of
`VARKA-343.md` (the names `CG`, `T`, `S`, the output lines of a run, the bytes check) applies
to these sheets too. Revised again in fix round 2 (28 November 2026, after
review 1 and the owner's answers of that day): each changed place says "Changed in fix
round 2" or "fix round 2".*

### A.1 What this row is, and which done-when clause holds it

Row 339 of `m8/PLAN.md` is the arithmetic of `TIMESTAMP_NTZ`: `ntz - ntz`, `ntz +/- dt`,
`ntz +/- ym`, `timestampadd` and `time_bucket`. The close path (`m8/PLAN.md` 12.5, 27 November
2026) keeps the row as a gate "under item 2 alone": item 2 of 1.1, "Most of the census is
fused". So the close holds this row to `m8/census.tsv`, and to nothing else of 1.1.

| census entry | types | fused today | after part 339d2 |
| :--- | :--- | :--- | :--- |
| `ntz_minus_ntz`, `ntz_plus_dt`, `ntz_plus_ym`, `timestampadd`, `datetime_sub` | as listed | all | unchanged |
| `time_bucket` | `ntz, ym, dt` | none | `ntz, dt` fused; `ym` leaves the entry's types, marked `ym!`, its reason row 664 of `m9/PLAN.md` |

That is two pairs and one entry fused for every type it admits. Print the line before and
after with `awk -F'\t' '$1=="time_bucket"' sql/varka/plans/m8/census.tsv`.

**Merged** (`git log origin/master --format='%h %cs %s' -E --grep='VARKA-339\b'`): the plan
(#1064), step 1, the baseline class (#1229), step 2, the difference and the day-time sums
(#1244), step 3, `timestampadd` and the month shifts (#1442, 12 November 2026).

**Left:** step 4, `time_bucket` over a day-time width, and step 5, the measurement.

**Built and not merged:** step 4, on the local branch `varka-339-ntz-arith` of
`/home/max/proj/vecbricks-varka`, and on the fork as `varka-339-ntz-arith-laptop` (A.12
question 2) (tip `6476788b31a`, 20 November 2026, merge-base with master
`0b8be5a33e4`, 534 commits behind `6dafb8bd2f6`). Its record is 10.2 below. It adds no IR
node, lowering or emit option, so row 489's `props()` descriptor, which merged after it, asks
nothing of it. It is 908 counted lines (`python3 dev/varka_msg_check.py size --base
0b8be5a33e4 --head varka-339-ntz-arith`), so A.5 cuts it in two.

**How the branch meets master** (fix round: `scratch-343/merge3.py` of the planning folder, a
read-only `git merge-file` per file). The code merges clean: `VarkaTimeCompiler`,
`VarkaFamily`, `VarkaCatalystGrammar`, `VarkaCoverageSuite`, `VarkaDomains`,
`VarkaTimestampNtzSuite`, `dev/varka_census.py`; the suite is new. Every list file conflicts
(master grew each since 20 November): `VarkaProofFiles` (`LONG_DIVISORS`, master added the
divisors of rows 103 and 350 and two lists exempt from a rule), `VarkaDivisorProofSuite`
(javadoc), `sql/varka/proofs/README.md`, `VarkaAnsiForm`, the benchmark class's javadoc,
`expected_errors.tsv`, `skips.tsv` and `census_curated.txt`. Taking the branch's side of
`census_curated.txt` would revert master's `hash` and `xxhash64` lines; the sheets write each
list edit out. Every generated file conflicts and is regenerated, never merged.

### A.2 What a builder must know

- A `TIMESTAMP_NTZ` value is a count of microseconds from 1970-01-01 00:00:00 with no zone, on
  the long lane. `D` is the microseconds of a day, 86,400,000,000.
- `time_bucket(b, t[, o])` is the start of the bucket of width `b` that `t` falls in, on the
  grid anchored at the origin `o` (the epoch when absent): `o + floorDiv(t - o, b) * b`. The
  division floors, it does not truncate: a count before the origin belongs to the bucket that
  starts below it. Spark needs `b` and `o` foldable, and `b` positive.
- Where Spark raises (`DateTimeUtils.timeBucketFromTimestampDTInterval`, at UTC): on
  `t - o`, on `k * b`, on `o + k * b`, and, for `b >= D` only, also on `(k + 1) * b` and on
  `o + (k + 1) * b`, because for such a width Spark steps the index until the bucket holds
  `t`, which evaluates the next bucket's start. A kernel never raises: a batch with a lane
  that Spark raises on is declined, and Spark's row code raises on the row.
- A division on the long lane is `ConstDivide(x, d, bound)`, exact only where `|x| < bound`
  and `bound <= 2^52` (`ConstDivide.EXACT_DIVIDEND_BOUND`). `VarkaNodeCompiler.floorDivLong(x,
  D, sink)` is the floor division by a day over every long, and
  `VarkaNodeCompiler.microsOfDay(x, sink)` is `floorMod(x, D)`; both are on master and proved
  in `long_divide.smt2`.
- A tree not of the int lane alone may be at most 16 nodes deep (`VarkaEmitBudget.
  MAX_CHAIN_DEPTH`). That is why the guards below sit on the leaf `t` and not on the results.
- A divisor the compiler builds into a coverage row must be in `VarkaProofFiles.LONG_DIVISORS`
  (`VarkaDivisorProofSuite` fails otherwise, VARKA-496), which renders its checks into
  `long_divide.smt2`. So the proof part goes first. The same test also holds the other
  direction (fix round 2): every proof divisor that no compiled kernel divides by is named in
  its `PROVEN_ONLY` (on master `Set.of(-60L)`), or the test fails as "stale". So between 339d1
  and 339d2 the three new divisors are named there, and 339d2 takes them out again.

### A.3 The admission check (run each; stop if one prints something else)

The directories, as shell names every command of this file uses (paste them first):

    CG=sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen
    T=sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka
    S=sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka
    CS=sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen
    BJ=sql/core/src/test/java/org/apache/spark/sql/execution/benchmark
    BS=sql/core/src/test/scala/org/apache/spark/sql/execution/benchmark

Each command was run on `6dafb8bd2f6` and printed what is written after it.

1. No arm claims the class: `grep -c 'TimeBucket' $CG/VarkaTimeCompiler.java`: `0`.
2. The helpers exist: `grep -c 'static VarkaVectorIR floorDivLong\|static VarkaVectorIR
   microsOfDay' $CG/VarkaNodeCompiler.java`: `2`.
3. Spark's arithmetic is the one A.2 states: `grep -c 'while (boundary(MathUtils.addExact(
   adjusted, 1L)) <= tsMicros)' sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/util/
   DateTimeUtils.scala`: `1`. If it prints 0, Spark's raise points moved: stop.
4. The baseline's two sections are the row path's:
   `grep -c 'Fusion.NONE' $BJ/
   VarkaTimestampNtzArithmeticBaselineBenchmark.java`: `2` (the two `time_bucket` sections).
5. `awk -F'\t' '$1=="time_bucket"' sql/varka/plans/m8/census.tsv` prints the line with types
   `ntz,ym,dt` and an empty `fused_today`.
6. `grep -c 'static boolean isNtz' $CG/varka/VarkaTypes.java`: `1`, and its body is
   `DataTypes.TimestampNTZType.equals(dataType)`: the arm's guard admits no nanosecond type,
   though `TimeBucket` takes those on master too.
7. The APIs the branch's suite calls are on master with its signatures:
   `DateTimeUtils.timeBucketDTInterval(Long, Long, Long, ZoneId)`, `VarkaSqlResolve.column` and
   `named` (Scala object, `$S/VarkaSqlResolve.scala`), `VarkaExpressionCompiler.compile(Seq,
   Seq, VarkaEmitOptions)` and `declines(...)`, `VarkaDecline.reason()`, the seven-argument
   `VarkaLoopEmitter.emit`, `VarkaKernelChecks.runAndCompareLong(String, String, byte[], List,
   int, long[], int[], LongBatch, boolean, SparkAnswers)`, `SparkAnswers.answer(int, long[],
   boolean[])` and `sql(int)`, `Answer.Value(long)`, `Answer.Null()`, and
   `CompiledVarkaProjection`'s `outputs`, `inputOrdinals`, `literals`, `longLiterals`,
   `numLiterals()` (checked by `grep -n` in each file).

Not checked: that the merged code compiles on master. The first command of each part is the
compile.

### A.4 The design: `VarkaTimeCompiler.bucketStart`, written out

The arm, in `VarkaTimeCompiler.arm`, beside the `TimestampAdd` case:

    case TimeBucket tb when VarkaTypes.isNtz(tb.ts().dataType()) ->
        () -> ntzTimeBucket(tb, inputs, literals, sink);

`ntzTimeBucket` declines, in this order and with these texts: a width that is not a day-time
interval, `"time_bucket by a year-month width, which needs the month of the date (row 664 of
m9/PLAN.md)"` (the branch cites item 117 of `m9/SCOPE[.]md`, a grep pattern: change it, in the
code and in the test
that pins it); a width or origin that folds to null or raises, `"time_bucket's width or origin
folds to null or raises"`; `b <= 0`, `"time_bucket by a width that is not positive"`; and
`D % b != 0 && b % D != 0 && b > 2^51`, `"time_bucket by a width past 2^51 microseconds that
neither divides a day nor is whole days"`. Then `bucketStart(t, b, o, sink)` over the long
operand `t`:

    windowed = D % b != 0 && b % D != 0
    [lo, hi] = bucketGuard(b, o, windowed)                 compile-time constants
    held  = (lo, hi) is the whole long range ? t : GuardedRange(t, lo, hi)      FAIL
    diff  = o == 0 ? held : IntArith(SUB, FAIL, held, slot(o))
    r     = floorMod(diff, b), by the arm below
    kb    = b == 1 ? diff : IntArith(SUB, FAIL, diff, r)
    start = o == 0 ? kb : IntArith(ADD, FAIL, slot(o), kb)

`kb = diff - r` is `k * b`, so its checked subtraction overflows exactly where Spark's
`multiplyExact(k, b)` does. The remainder, by width:

| width | `r` | exact over |
| :--- | :--- | :--- |
| `b < D`, `b` divides `D` | `r1 = microsOfDay(diff)`; `r = r1 - ConstDivide(r1, b, D) * b`, wrapping | every long: `floorMod(x, b) = floorMod(floorMod(x, D), b)` |
| `b == D` | `microsOfDay(diff)` | every long |
| `b = n * D`, `n > 1` | `k = floorDivBounded(floorDivLong(diff, D), n, 106,751,993 + n - 1)`; `r = diff - k * b`, wrapping | every long: the true remainder is in `[0, b)` |
| any other `b <= 2^51` | `k = floorDivBounded(diff, b, 2^52)`; `r = diff - k * b`, wrapping | `t` within `2^52 - b` of `o`; the guard declines the rest |

    floorDivBounded(x, d, bound) = ConstDivide(x + ((x >> 63) & (1 - d)), d, bound)

A negative `x` moves down by `d - 1`, so that the truncating division floors.

    bucketGuard(b, o, windowed), in BigInteger:
      lo = Long.MIN_VALUE; hi = Long.MAX_VALUE
      if windowed:  w = 2^52 - b;  lo = max(lo, o - w);  hi = min(hi, o + w)
      if b >= D:    top = Long.MAX_VALUE - b
                    kmax = min(floorDiv(top, b), floorDiv(top - o, b))
                    hi = min(hi, o + (kmax + 1) * b - 1)

`kmax` is the greatest index whose next start, with and without the origin, is a long; a count
from `o + (kmax + 1) * b` up is one whose next bucket Spark cannot compute, so Spark raises on
it and the guard declines it. Edge cases: a null `t` gives null; a batch with no live row is
answered by the masked driver's shortcut; at two 64-bit lanes the form has no mask of its own
beyond the guards', which every long-lane guard already has.

**Checked in Python** (`scratch-339/check_bucket.py` of the planning folder; the script models
Spark's function and this lowering on exact integers, every `ConstDivide` asserting its
bound): `time_bucket: 630220 (width, origin, value) cells over 504 divisors of a day, 5
whole-day and 8 other widths, 9 origins: 523753 equal Spark's start, 101714 decline where
Spark raises, 4753 decline outside the window only, 0 mismatches; 600036 datetime cells from
year 1 to 9999 equal the cleared datetime`. The last clause is the check against Python's
`datetime` (proleptic Gregorian, before 1970 included): an hour, a quarter-hour and a day
bucket from the epoch equal the datetime with its lower fields cleared, which holds only
because the remainder floors. Three planted faults fail the script: a guard one count too
wide, a day remainder that truncates, a window one microsecond wider.

### A.5 The parts

Order: 339d1, then 339d2, then 339r. 339d1 goes first because 339d2's coverage rows divide by
three new divisors, which `VarkaDivisorProofSuite` requires in `LONG_DIVISORS`, and are marked
`Proved("long_divide.smt2")`.

*Changed in fix round 2 (review 1, finding 1).* `VarkaDivisorProofSuite.everyDivisorProven`
also fails on a proof divisor that no compiled kernel divides by and `PROVEN_ONLY` does not
name (master's `VarkaDivisorProofSuite.everyDivisorProven`, its `stale` set against
`PROVEN_ONLY = Set.of(-60L)`). No coverage row on master divides by `900_000_000`, `30` or
`25_200_000_000` (`grep -c "INTERVAL '30' DAY\|INTERVAL '15' MINUTE\|INTERVAL '7' HOUR"
sql/varka/coverage.json` prints `0`), so 339d1 alone would leave that test red. Of the review's
two fixes this plan takes the first: 339d1 names the three divisors in `PROVEN_ONLY`, with a
comment that says why, and 339d2 takes them out in the same commit that adds the rows that
divide by them. Why not merge the two parts: the merged part is about 820 counted lines, over
`BUILD_SHEETS.md` 8's stop at 900 by the estimate's own error, and it would put a solver run of
up to 300 s per solver and the kernel's review in one pull request; kept apart, each part is
green alone, and forgetting the revert in 339d2 fails the same test (`stale` is then `{-60}`,
`PROVEN_ONLY` four values), so the check is the test itself.

| part | title | size, counted | builder | starts from | waits on |
| :--- | :--- | ---: | :--- | :--- | :--- |
| 339d1 | `[VARKA-339] Prove time_bucket's remainders before its kernel` | 120 | Sonnet-class | master; `6476788b31a` | nothing |
| 339d2 | `[VARKA-339] Fuse time_bucket of a TIMESTAMP_NTZ by a day-time width` | 710 | Sonnet-class | master after 339d1; `6476788b31a` | 339d1 |
| 339r | `[VARKA-339] Results: TIMESTAMP_NTZ arithmetic on its kernels` | 40 | Sonnet-class | master | 339d2 |

#### Part 339d1: the bucket's proofs (build sheet, fix round)

- **Title:** `[VARKA-339] Prove time_bucket's remainders before its kernel`. **Branch:**
  `varka-339d1-bucket-proofs` from master. **Builder:** Sonnet-class. **Size:** about 120
  counted lines; `long_divide.smt2` is generated and not counted. Follow `BUILD_SHEETS.md`.

**B0, the premises.**

1. `grep -c 'timeBucket' $T/VarkaProofFiles.java`: `0`.
2. `grep -c '^      1_953_125L);$' $T/VarkaProofFiles.java`: `1`, the end of `LONG_DIVISORS`
   (the unanchored text also matches `DECIMAL_SHIFT_DIVISORS`).
3. `grep -c 'check-sat' sql/varka/proofs/long_divide.smt2`: `2479`.
4. `grep -c 'm10/SCOPE.md} item 116' $T/VarkaDivisorProofSuite.java`: `1`.
5. `git cat-file -t 6476788b31a`: `commit` (where the code comes from: A.12 question 2).
6. `grep -c 'private static final Set<Long> PROVEN_ONLY = Set.of(-60L);'
   $T/VarkaDivisorProofSuite.java`: `1` (fix round 2).

**Steps.**

1. `$T/VarkaProofFiles.java`, `LONG_DIVISORS`, by hand: the last element `1_953_125L);` becomes

           1_953_125L,
           // time_bucket's divisions (VARKA-339): a 15-minute width's remainder of the time of day,
           // 30 days' division of the day, and a seven-hour width's under its window.
           15 * DateTimeConstants.MICROS_PER_MINUTE,
           30L,
           7 * DateTimeConstants.MICROS_PER_HOUR);

   Do not add them to `DAY_SPLIT_DIVISORS` or `DECIMAL_SHIFT_DIVISORS`: the rule those lists
   exempt from (where the conversion form first fails past `2^53`) is the one the branch's
   file passed under both solvers for these three divisors, and master has not changed it.
   `DateTimeConstants` is imported there already.
2. Same file: after `truncDivDay(out);` add `timeBucket(out);`, and after the method that holds
   that call add the method `private static void timeBucket(StringBuilder out)` with its
   javadoc, word for word from `git show 6476788b31a:$T/VarkaProofFiles.java` (the 80 lines
   that begin `  /**\n   * {@code time_bucket}'s remainder`). It renders 14 checks: two for a
   15-minute width, three for each of 7, 30 and 365 days, and three for a seven-hour width,
   the last of them `sat` (one past the window the moved difference reaches `2^52`). Both hunks
   merge clean (`git diff 0b8be5a33e4 6476788b31a -- $T/VarkaProofFiles.java`, its second and
   third hunks).
3. `$T/VarkaDivisorProofSuite.java`, the class javadoc, by hand: after the line ending `{@code
   m10/SCOPE.md} item 116.` add the branch's paragraph (`git diff 0b8be5a33e4 6476788b31a --
   $T/VarkaDivisorProofSuite.java`, its 8 added lines, beginning ` * <p>One long-lane division
   is open too`), unchanged: its last sentence "A proof for any long divisor is item 116's
   too." reads against master's `m10/SCOPE.md` item 116.
   Then, same file, by hand (fix round 2, review 1 finding 1): the javadoc of `PROVEN_ONLY`
   and its line, which on master read

         /**
          * The proof's divisors that no compiled kernel divides by, each on purpose: -60 is there for
          * the sign branch a negative divisor takes and a positive one does not. A proof divisor that
          * stops being collected and is not named here fails, so the list cannot keep a divisor the
          * compiler has dropped.
          */
         private static final Set<Long> PROVEN_ONLY = Set.of(-60L);

   become

         /**
          * The proof's divisors that no compiled kernel divides by, each on purpose: -60 is there for
          * the sign branch a negative divisor takes and a positive one does not. A proof divisor that
          * stops being collected and is not named here fails, so the list cannot keep a divisor the
          * compiler has dropped. time_bucket's three divisors (VARKA-339: 15 minutes, 30 days, seven
          * hours) are proven before the kernel that divides by them; part 339d2 compiles them and
          * takes them out of this set.
          */
         private static final Set<Long> PROVEN_ONLY =
             Set.of(-60L, 900_000_000L, 30L, 25_200_000_000L);
4. `sql/varka/proofs/README.md`, the row of `long_divide.smt2`, by hand: master's text, then
   after `(VARKA-496)` insert `, \`time_bucket\`'s width apart, the query's literal, of which the
   coverage rows' are proven (VARKA-339)`. The rest of master's row stays.
5. Regenerate the proof file: `VARKA_PROOFS_REGEN=true flock "$VARKA_HEAVY_LOCK" build/sbt
   'catalyst/testOnly *VarkaProofFilesSuite'` (README, "Constants that live in the code").
6. `sql/varka/plans/m8/PLAN.md`, row 339: after `**Started** (` insert `*339d1 built <date>,
   \`VARKA-339.md\` A.5: time_bucket's three remainder forms proved in long_divide.smt2;* `.
7. `VARKA-339.md` section 10: `### 339d1 as built (<date>)`, at most 10 lines, with the two
   solver times.

**Commands, in order.**

1. `flock "$VARKA_HEAVY_LOCK" build/sbt catalyst/Test/compile`: `[success]`.
2. Step 5's regeneration; then `grep -c 'check-sat' sql/varka/proofs/long_divide.smt2` prints
   `2841` (2479, plus 116 for each new divisor as every non-exempt divisor has, plus the 14 of
   the new section; the branch added 362 to its base's 1919, the same count).
3. `dev/varka_prove.sh --solver both long_divide.smt2`: a line for each solver ending `2841
   checks <seconds> s  holds`, then `varka_prove: every proof holds under z3 cvc5`. Each
   solver's run limit is 300 s; on the branch Z3 took 118 to 209 s and cvc5 67 to 81 s on a
   shared laptop. A run past the limit answers `unknown` and fails: rerun once on an idle
   machine before reporting it.
4. `flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaDivisorProofSuite
   *VarkaProofFilesSuite'`: no test fails. If `everyDivisorProven` fails with "the proof's
   divisors no compiled kernel divides by should be exactly PROVEN_ONLY" (expected `[-60]`,
   actual four values), step 3's second edit is missing or mistyped: the set must hold exactly
   the four values written there.
5. `BUILD_SHEETS.md` 6, the commit, `python3 dev/varka_push_check.py`.

**Planted fault.** In the method's seven-hour block change `lit(-window - 1)` to `lit(-window)`:
command 3 fails on "one past the window the moved difference reaches 2^52" (now `unsat`). The
fault needs the regeneration of step 5 first; revert both.

**Done when** commands 1 to 5 print what they say. **Not here:** `VarkaTimeCompiler`, any
coverage row, the census. `PROVEN_ONLY` holds the three divisors only until 339d2 merges.

#### Part 339d2: the kernel (build sheet, fix round)

- **Title:** `[VARKA-339] Fuse time_bucket of a TIMESTAMP_NTZ by a day-time width`.
  **Branch:** `varka-339d2-time-bucket` from master after 339d1 merged. **Builder:**
  Sonnet-class. **Size:** about 710 counted lines (the suite is 374). Follow `BUILD_SHEETS.md`.
  Between 901 and 999 counted lines, report the count and go on; do not trim tests.

**B0, the premises.**

1. A.3 items 1 to 6 print what they say.
2. 339d1 is merged: `grep -c 'timeBucket(out);' $T/VarkaProofFiles.java`: `1`, and
   `grep -c 'Set.of(-60L, 900_000_000L, 30L, 25_200_000_000L);'
   $T/VarkaDivisorProofSuite.java`: `1`.
3. `ls $T/VarkaTimestampNtzBucketSuite.java` fails.
4. `grep -n '^time_bucket|' sql/varka/plans/m8/census_curated.txt` prints
   `time_bucket|IVL,ntz|ntz||semantics|339|a floor division of the micros by the bucket`.
5. `git cat-file -t 6476788b31a`: `commit`. If not, A.12 question 2 (the fork's
   `varka-339-ntz-arith-laptop`, never its `varka-339-ntz-arith`); if still not, stop: the
   suite and the session test are quoted from it.

**Steps.**

1. These files merge clean; take each with `git diff 0b8be5a33e4 6476788b31a -- <file> | git
   apply --3way`, one at a time:
   - `$CG/VarkaTimeCompiler.java`: the imports (`java.math.BigInteger`, `TimeBucket`,
     `BitOp`, `BitOpKind`), the class javadoc's sentence, `BUCKET_WINDOW_MAX_WIDTH = 1L << 51`
     and `DAY_QUOTIENT_MAGNITUDE`, the `case TimeBucket tb` arm after the `TimestampAdd` arm,
     `foldedQuantity`'s javadoc, and the methods `ntzTimeBucket`, `bucketStart`,
     `bucketGuard`, `CountRange`, `floorDiv(BigInteger, BigInteger)`, `floorDivBounded` (A.4;
     the hunks are `git diff 0b8be5a33e4 6476788b31a -- $CG/VarkaTimeCompiler.java`).
   - `$CG/varka/VarkaFamily.java`: the `TIME` family's description ends "timestampadd of a
     TIMESTAMP_NTZ by a unit from a microsecond to a week, and time_bucket of a TIMESTAMP_NTZ
     by a day-time width".
   - `$T/VarkaCatalystGrammar.java`: three productions after `($0 + INTERVAL '1' MONTH)`:
     `time_bucket(INTERVAL '15' MINUTE, $0)`, `time_bucket(INTERVAL '1' DAY, $0,
     TIMESTAMP_NTZ'1970-01-01 06:00:00')`, `time_bucket(INTERVAL '7' HOUR, $0)`.
   - `$T/VarkaTimestampNtzBucketSuite.java` (new, the branch's file whole).
   - `$S/VarkaCoverageSuite.scala`: the three helper values `ntzEpoch`, `firstQuarterHour`,
     `bucketWindow`; the four rows of the table below at the end of the family "TIMESTAMP_NTZ
     arithmetic" (after `timestampAddMonths("YEAR", 12, "l")`); and the four `Proved` entries
     after `"CAST(ntz AS TIME(0))" -> Proved("long_divide.smt2")`.
   - `sql/core/src/test/java/org/apache/spark/sql/execution/VarkaDomains.java`: the entry
     for `time_bucket(INTERVAL '15' MINUTE, ntz)` with its reason.
   - `sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaTimestampNtzSuite.scala`:
     the class javadoc's sentence, `bucketWindow`, four fixtures (`varka_ntz_bucket_last`,
     `varka_ntz_bucket_least`, `varka_ntz_bucket_window`, `varka_ntz_bucket_past_window`),
     `bucketForms`, `sevenHours`, and the test "time_bucket runs on the kernel at the date
     guard's ends, and the seven-hour bucket at its window's; a day bucket whose next start
     leaves the long raises from the row engine, and an hour bucket of the same value answers".
   - `dev/varka_census.py`: `"time_bucket": "TimeBucket",` at the end of `VIA`.
   - (fix round 2, review 1 finding 1) `$T/VarkaDivisorProofSuite.java`, by hand: the two
     lines `private static final Set<Long> PROVEN_ONLY =` and
     `Set.of(-60L, 900_000_000L, 30L, 25_200_000_000L);` become the one line
     `private static final Set<Long> PROVEN_ONLY = Set.of(-60L);`, and in its javadoc the
     sentence that begins "time_bucket's three divisors (VARKA-339" and ends "takes them out of
     this set." is deleted, so the javadoc is master's again. The four coverage rows now divide
     by `900_000_000` (15 minutes), `30` (30 days) and `25_200_000_000` (seven hours); the day
     row divides by `NTZ_DAY_ODD`, already listed.
2. The decline text, by hand, in two places of `VarkaTimeCompiler` and one of the suite: in
   `ntzTimeBucket` the string becomes `"time_bucket by a year-month width, which needs the month
   of the date (row 664 of m9/PLAN.md)"` and its javadoc's item 117 (grep `SCOPE[.]md`)
   becomes `(row 664 of {@code m9/PLAN.md})`; in the suite's test `declines`,
   the `contains` of `SCOPE[.]md item 117` (a grep pattern) becomes `contains("row 664 of
   m9/PLAN.md")`.
3. The list files, by hand:
   1. `sql/core/src/test/java/org/apache/spark/sql/execution/VarkaAnsiForm.java`: in the list
      that ends `"CAST(CAST(d9 AS DECIMAL(9, 4)) AS BIGINT)")) {` add `,
      "time_bucket(INTERVAL '7' HOUR, ntz)"` after that last element (the false-AND list:
      each entry gets `FALSE_AND`).
   2. `$BJ/VarkaTimestampNtzArithmeticBaselineBenchmark.java`:
      the two `time_bucket` sections' `Fusion.NONE` become `Fusion.WHOLE`; in the class
      javadoc the three sentences that begin "Step 2 fuses the chain" and end "time the fused
      code." become "Every section is {@code WHOLE}: the steps of VARKA-339 fuse them all, so
      the same class and the same labels time the fused code."; the rest of master's javadoc
      (from "A {@code WHOLE} section must also run as a kernel") stays as it is.
   3. `sql/varka/fuzz/expected_errors.tsv`: the two `ARITHMETIC_OVERFLOW` lines merge clean
      from the branch (their family list gains `;TIMESTAMP_NTZ arithmetic`, and the LEGACY
      line's reason gains "; time_bucket over an NTZ value raises it in every mode where the
      difference from the origin, the bucket's offset or, under a day, its start leaves the
      long (VARKA-339)"). The `java.lang.ArithmeticException` line takes the branch's text
      (its reason gains ", and so does a time_bucket start of a day or more"); master's
      `INVALID_PARAMETER_VALUE.BIT_POSITION_RANGE` line after it stays.
   4. `sql/varka/matrix/skips.tsv`: append the branch's two `checkIntOverflow=false` lines at
      the end of the file, unchanged (`git show 6476788b31a:sql/varka/matrix/skips.tsv | grep
      'time_bucket\|VarkaTimestampNtzBucketSuite'`).
   5. `sql/varka/plans/m8/census_curated.txt`: the one line of B0 item 4 becomes

          time_bucket|ym!,dt,ntz|ntz|dt,ntz|done|339|the bucket's remainder by the time of day, the day or one bounded division; a year-month width is out of milestone 8 by the owner's answer of 27 October 2026 (VARKA-339.md 8, row 664 of m9/PLAN.md)

      and no other line moves (master's `hash` and `xxhash64` lines are not the branch's).
4. Do not take the branch's `docs/sql-varka.md`, `coverage.json`, `emitted_bytes.json`,
   `width_audit.json`, `census.tsv`, `long_divide.smt2`, `PLAN.md` or `VARKA-339.md` hunks.
5. `sql/varka/plans/m8/PLAN.md`, row 339: after `**Started** (` insert `*339d2 built <date>,
   \`VARKA-339.md\` A.5: time_bucket of a TIMESTAMP_NTZ by a day-time width fuses;* `.
6. `VARKA-339.md` section 10: `### 339d2 as built (<date>)`, at most 15 lines.

**The coverage rows** (family "TIMESTAMP_NTZ arithmetic", `$S/VarkaCoverageSuite.scala` of the
branch, its hunk at line 1177):

| row | note | domain |
| :--- | :--- | :--- |
| `time_bucket(INTERVAL '15' MINUTE, ntz)` | a width that divides a day takes the remainder through the time of day, exact over every long | `ntz >= $ntzEpoch - INTERVAL '$firstQuarterHour' DAY TO SECOND` |
| `time_bucket(INTERVAL '1' DAY, ntz, TIMESTAMP_NTZ '1970-01-01 06:00:00')` | a width of whole days through the day, an origin's start checked, the next bucket's guarded | `ntz >= CAST(date_from_unix_date(-106751991) AS TIMESTAMP_NTZ) + INTERVAL '6' HOUR AND ntz < CAST(date_from_unix_date(106751990) AS TIMESTAMP_NTZ) + INTERVAL '6' HOUR` |
| `time_bucket(INTERVAL '30' DAY, ntz)` | the day's floor division by the days | `ntz >= CAST(date_from_unix_date(-106751970) AS TIMESTAMP_NTZ) AND ntz < CAST(date_from_unix_date(106751970) AS TIMESTAMP_NTZ)` |
| `time_bucket(INTERVAL '7' HOUR, ntz)` | any other width by one division, the difference from the origin held to the window that division is exact over | `ntz - $ntzEpoch BETWEEN INTERVAL '${daySecond(-bucketWindow)}' DAY TO SECOND AND INTERVAL '${daySecond(bucketWindow)}' DAY TO SECOND` |

**The tests.**

| suite | what it holds | the failure only it catches |
| :--- | :--- | :--- |
| `VarkaTimestampNtzBucketSuite` (new, JUnit, `@VarkaSuite`) | `scalarForms`: the suite's scalar model of the three forms against Spark's `timeBucketDTInterval` over the 504 divisors of a day, 5 whole-day widths (`D`, 7, 30, 365 and 106,751,991 days) and 5 windowed ones (7 hours, 36 hours, 1,000,003, `D + 1`, `2^51`), at 5 origins (0, 1,622,548,800,000,000, -1, `MIN + 5`, `MAX - 5`), over the long's ends, the buckets' ends around 7 anchors and 120 seeded draws: equal where both answer, declining only where Spark raises or outside the window (more than 100,000 compared, more than 1,000 declined). `emittedForms`: the compiled and emitted kernels of 35 widths (every 23rd divisor, the last one, 15 minutes, an hour, the 5 whole-day and 5 windowed widths) at the 5 origins: one batch of every value Spark answers inside the window, each value Spark raises on or outside the window beside two served ones, which must decline. `declines`: a year-month width (text: row 664), a null width, a null origin ("folds to null"), `2^51 + 1` ("past 2^51"), while `2^51` and 106,751,991 days fuse, and a zoned `TIMESTAMP` declines | a remainder or guard one off (scalar), the compiler's guard too wide or too narrow (emitted), a decline lost |
| `VarkaTimestampNtzSuite` (Scala) | through a session under both ANSI settings: the four forms at the date guard's ends, the seven-hour window's ends, one past it (one batch declined), and the day bucket of the last day and the hour bucket of the least long raising from the row engine | the session path and the raised error |
| `VarkaCoverageSuite` and the harness suites | the four rows, `Proved("long_divide.smt2")` | a row that stops fusing |

**Commands, in order.**

1. `flock "$VARKA_HEAVY_LOCK" build/sbt catalyst/Test/compile sql/Test/compile`: `[success]`.
2. `flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaTimestampNtzBucketSuite'`:
   `Passed: Total 3, Failed 0, Errors 0, Passed 3` (on the branch 7.7 s).
3. `flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaExpressionCompilerSuite'`:
   no test fails.
4. `dev/varka_regen_generated.sh --all` (bare): last line begins `varka regen: done`. Then
   `grep -c '"sql" : "time_bucket(' sql/varka/coverage.json` prints `4`; the bytes check of
   `VARKA-343.md` A.5.0 prints `bytes: 0 committed shapes moved, 8 shapes added`;
   `git diff --exit-code -- sql/varka/proofs/long_divide.smt2` exits 0 (339d1 rendered it).
   Then (fix round 2: `VarkaDivisorProofSuite` reads the rows from `coverage.json`, so it runs
   only after this regeneration) `flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly
   *VarkaDivisorProofSuite'`: no test fails. If `everyDivisorProven` fails on "should be
   exactly PROVEN_ONLY" (expected the four values, actual `[-60]`), the `PROVEN_ONLY` edit of
   step 1 is missing. If a stale set names one of `900000000`, `30`, `25200000000`, the row
   that divides by it did not compile to a kernel: stop.
5. `python3 dev/varka_census.py --check`: prints nothing; then
   `awk -F'\t' '$1=="time_bucket"{print $5 "|" $6 "|" $8 "|" $10}' sql/varka/plans/m8/census.tsv`
   prints `declined ym: owner's decision, 14 October 2026|ntz,dt|ntz,dt|done`. The reason
   column's date is the script's fixed text for every `!` type (`DECLINED` in
   `dev/varka_census.py`); the note carries this decision's own date (A.12, the known limit).
6. `flock "$VARKA_HEAVY_LOCK" build/sbt 'sql/testOnly *VarkaTimestampNtzSuite
   *VarkaTimestampNtzArithmeticBaselineQueriesSuite *VarkaCoverageHarnessSuite
   *VarkaCoverageDifferentialSuite'`: no test fails.
7. `BUILD_SHEETS.md` 6, the commit, `python3 dev/varka_push_check.py` (size under 900).

**Planted faults.** (a) In `bucketGuard`, `kmax.add(BigInteger.ONE)` becomes
`kmax.add(BigInteger.TWO)` (the guard one bucket too wide): `emittedForms` fails at a
whole-day width, a value whose next start leaves the long answering. (b) In
`floorDivBounded`, return `new ConstDivide(x, divisor, bound)` (no move down): `emittedForms`
fails at a windowed width over a negative difference. `scalarForms` passes under both: it
holds the suite's own model, not the compiler's tree; record that in the outcome.

**Done when** commands 1 to 7 print what they say and the two faults fail as said.

**Not here:** a year-month width (row 664 of `m9/PLAN.md`); a zoned `TIMESTAMP` (row 668);
the nanosecond types; any change to `floorDivLong`, `microsOfDay` or `ConstDivide`.

#### Part 339r: the measurement (build sheet, fix round)

- **Title:** `[VARKA-339] Results: TIMESTAMP_NTZ arithmetic on its kernels`. **Branch:**
  `varka-339r-results` from master after 339d2 merged. **Builder:** Sonnet-class; the
  dispatches are the lead's unless the builder has `gh` rights. **Size:** results files (not
  counted) and about 40 plan lines.
- **B0:** `grep -c 'Fusion.NONE'
  $BJ/VarkaTimestampNtzArithmeticBaselineBenchmark.java`
  prints `0` (every section fuses after 339d2); `git cat-file -t 23a256d0db5` prints `commit`
  (#1229, the baseline's own results, from before any kernel).
- **Steps:** the results procedure of `VARKA-343.md` A.5.0 for
  `VarkaTimestampNtzArithmeticBaselineBenchmark` (`sql/core/benchmarks`), at both widths. Diff
  against the row path's committed files of #1229, not against master's: `python3
  dev/varka_bench_diff.py --git 23a256d0db5
  sql/core/benchmarks/VarkaTimestampNtzArithmeticBaselineBenchmark-jdk25-runner-epyc7763-results.txt
  --control "spark row path"`, and the same for the `-128bit-results.txt` file.
- **Score** 4.2 and 4.3 in section 10: the chain at least ten times the row path at 256 bits;
  the month form at least five times; `time_bucket` at 15 minutes at least twice at 256 bits
  and within 20% either way at 128.
- **The two-lane rule, a number:** if a `time_bucket` section's "varka on" rate at 128 bits
  with no nulls is under its "spark row path" rate, record it and file a row; decline nothing
  here.
- **Done when** the four files are committed from EPYC 7763 runs and each prediction carries
  its score. **Not here:** code.

### A.6 Tests, proofs, fuzzing and coverage

| suite or file | part | what it holds |
| :--- | :--- | :--- |
| `long_divide.smt2`, its `time_bucket` section | 339d1 | the three remainder forms at the coverage rows' widths, the window's bound, its refutation one past |
| `VarkaTimestampNtzBucketSuite` (new) | 339d2 | the forms against Spark, scalar and emitted |
| `VarkaTimestampNtzSuite` | 339d2 | the session path |

Fuzzing: the Spark fuzzer's grammar gains three forms (339d2); the IR fuzzer needs nothing, no
node being new. Coverage: four rows. JaCoCo and the mutation run: not applied to this row.

### A.7 Predictions registered before any run

Section 4's stand. Added: the bucket suite under 10 s at the defaults (7.7 s on the branch);
`long_divide.smt2` under Z3 at most 1.25 times master's time on the same machine (174 s
against 147 s on the branch).

### A.8 Who builds it

| part | builder | why |
| :--- | :--- | :--- |
| 339d1 | Sonnet-class | a list, one generated section, two solver runs |
| 339d2 | Sonnet-class | the arm merges as a diff and is written out in A.4; six list edits written out |
| 339r | Sonnet-class | two dispatches, four files, a diff and three scores |

No part is full strength: the one hard thing, the guard on the count and its equivalence to
Spark's raise points, is built, reviewed twice on the branch, and checked again in A.4.

### A.9 Risks

1. The merged code may not compile on master (helpers renamed since 20 November). Held by
   each part's first command; A.4 has the code to write it again by hand.
2. `long_divide.smt2` near its 300 s limit under Z3 on a loaded laptop. Held by 339d1's rule
   (rerun once on an idle machine) and A.7's prediction.
3. The branch lived on one laptop. Held: it is on the fork since 28 November 2026 (A.12
   question 2).

### A.10 The house rules that bind this row

From `sql/varka/AGENTS.md`: "A new bounded lowering comes with its proof file" (339d1 is that
file's section); generated files are regenerated, never merged; "A pull request stays under
1000 changed lines". Do not: take a branch's side of a list file whole; edit `census.tsv`,
`coverage.json` or `long_divide.smt2` by hand; build the year-month or zoned forms.

### A.11 Outcome

To be written when the row closes.

### A.12 Seams with milestone 9, and questions for the owner

Seams (do not build these here):

- Row 664 of `m9/PLAN.md`: the year-month width. Row 668: `time_bucket` over `TIMESTAMP_LTZ`,
  on this kernel. Rows 660 and 661: the nanosecond types in the census.
- Row 655 part D runs this row's baseline class again and expects 8 fused sections in the
  arithmetic class (`VARKA-655.md` 5.4, its table); after 339d2 it is 12. That table needs the
  number changed; 339r and part D then measure the same class, and part D's run may replace
  339r's files.
- Row 656 part A counts nine `isNtz` lines in `VarkaTimeCompiler.arm`; after 339d2 there are
  ten.

Questions (*changed in fix round 2, review 1 findings 4 and 10: both answered on 28 November
2026*):

1. **Who runs the measurement.** (a) This row, part 339r, before the close. (b) Row 655 part
   D of milestone 9, this row scoring from its files; under (b) 339r is dropped, and the close
   reads row 339 from part D's files, whose table must then read 12 fused sections of the
   arithmetic class, not 8 (the seam above). *Recommended: (a)*, milestone 9 not having
   started. **Answered on 28 November 2026: (a), its own part 339r before the close.**
2. **Push `varka-339-ntz-arith` to the fork** so the parts' starting diff survives the laptop.
   *Recommended: yes.* **Answered on 28 November 2026: done**, under the name
   `varka-339-ntz-arith-laptop` (`6476788b31a`). The fork's own `varka-339-ntz-arith` is
   another, older history (`d14dce887d9`): never use it. The sheets take the code from the
   local branch `varka-339-ntz-arith` at `6476788b31a`, or, if it is gone, from `git fetch fork
   varka-339-ntz-arith-laptop` (the one state-changing command a sheet allows for this); then
   `git cat-file -t 6476788b31a` prints `commit`. The earlier default here, "A.4 carries the
   code either way", was false (review 1 finding 4): A.4 carries the arm, not the 374-line
   suite or the session test, which only the branch holds.

A known limit, not a question: the census writes the reason of `time_bucket`'s `ym!` as
"owner's decision, 14 October 2026", the script's fixed text for every `!` type (`DECLINED` in
`dev/varka_census.py`), though this decision is of 27 October 2026; the entry's note carries
the right date.

### A.13 What only a JVM settles

That the emitted kernels equal Spark (`emittedForms`), the solver runs, the regeneration, the
option matrix, the runner results.

## 0. What exists now (admission check)

From the code at `a5ed9235723` (27 October 2026; Varka paths under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` unless named, Spark
paths under `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/`). The row's text is the
milestone plan's 2.6, bullet 339. What the code says about each premise is below, with the file
and line it rests on.

1. **Every form is UTC long arithmetic, with no evaluation mode.** `zoneIdForType` answers UTC
   for NTZ (`expressions/datetimeExpressions.scala:71-75`), and none of the five expressions
   carries an `evalMode`, so LEGACY and ANSI compute and raise alike:
   - `ntz - ntz` is `SubtractTimestamps` (`:4767`), `ChronoUnit.MICROS.between` of the two UTC
     wall clocks (`util/DateTimeUtils.scala:930`). `LocalDateTime.until` (JDK 25,
     `LocalDateTime.java:1678-1723`) moves the day count one towards zero before
     `multiplyExact(days, MICROS_PER_DAY)` and `addExact` of the time part, so the product never
     exceeds the true difference: the result is `Math.subtractExact(end, start)`, raising exactly
     when it does. Under `spark.sql.legacy.interval.enabled` it is a wrapping `CalendarInterval`
     instead (`:4806`).
   - `ntz + dt` is `TimestampAddInterval` (`:2560`), `timestampAddDayTime` at UTC (`:2630`,
     `DateTimeUtils.scala:310`): days, then micros, on a `ZonedDateTime` whose date range is far
     wider than a long's, then `instantToMicros`, exact over every long through its `MIN_SECONDS`
     arm (`sql/api/.../SparkDateTimeUtils.scala:117,136-145`). So it is `Math.addExact(ts, dt)`.
   - `ntz + ym` is `TimestampAddYMInterval` (`:2988`), `timestampAddMonths` at UTC (`:3017`,
     `DateTimeUtils.scala:282`): `LocalDate.plusMonths` with its day-of-month clamp, the time of
     day carried, then `instantToMicros`.
   - `timestampadd(unit, q, ntz)` is `TimestampAdd` (`:5061`), its unit a `String` field and its
     quantity `LongType` (an int column arrives widened). `DateTimeUtils.timestampAdd`
     (`:945-990`): MICROSECOND adds `q`; MILLISECOND to HOUR add `multiplyExact(q, unit)`; DAY
     takes `toIntExact(q)` days; WEEK `multiplyExact(toIntExact(q), 7)` days in int; MONTH,
     QUARTER and YEAR `toIntExact(q)` months times 1, 3 or 12 by an int `multiplyExact`. Every
     `ArithmeticException` or `DateTimeException` becomes `DATETIME_OVERFLOW` (`:988`); an
     unknown unit, NANOSECOND among them for a microsecond type, raises its own error.
   - `time_bucket(width, ntz[, origin])` is `TimeBucket` (`:5242`), width and origin foldable
     (`:5274`, `:5301`), the default origin the epoch (`:5418`). For a day-time width,
     `timeBucketFromTimestampDTInterval` (`DateTimeUtils.scala:1372-1397`): `subtractExact(ts,
     origin)`, `floorDiv` by the width, and the start `origin + multiplyExact(k, width)`,
     by `addExact` below a day and by `timestampAddDayTime` from a day up (`:1348-1358`), the
     same sum at UTC. A width of a day or more also evaluates `boundary(k + 1)`
     (`:1391`) though at UTC the linear estimate is exact, so it raises when the *next* bucket's
     start overflows. A year-month width is `timeBucketYMInterval` (`:1429`): the year and month
     of the timestamp, then `plusMonths` of the origin, twice.
2. **How the forms arrive.** `BinaryArithmeticWithDatetimeResolver.scala` (in `analysis/`) gives
   `ntz + dt` as `Cast(TimestampAddInterval(ntz, dt), TIMESTAMP_NTZ)` (`:98-99`), whose identity
   cast 338 compiles to its child (`VarkaNodeCompiler.java:433-434`); `ntz - dt` as the same cast
   over `DatetimeSub(..., TimestampAddInterval(ntz, UnaryMinus(dt)))` (`:143-146`); `ntz +/- ym`
   as `TimestampAddYMInterval`, the minus over `UnaryMinus(ym)` (`:75-76`, `:118-119`); `ntz -
   ntz` as `SubtractTimestamps` (`:147-151`), a `DATE` operand cast to NTZ first
   (`TypeCoercionHelper.scala:875-886`). `DatetimeSub` is `RuntimeReplaceable`, which
   `VarkaNodeCompiler.fallback` compiles as its replacement (`:760-762`). `UnaryMinus` over an
   ANSI interval is `negateExact` whatever its flag (`expressions/arithmetic.scala:99-100`), so
   `ntz - dt` raises at `dt = Long.MIN_VALUE` and `ntz - ym` at `Integer.MIN_VALUE`. The TRY forms
   exist only as `try_add` and `try_subtract`, which resolve to a `TryEval` (`TryEval.scala:96`)
   that no compiler arm claims.
3. **The long lane has the checked add and subtract, and nothing builds them for NTZ.** The
   column leaf and literal slot take NTZ, `dt` and `bigint` alike (`VarkaTimeCompiler.java:158-
   167`). `IntArith` in `FAIL` mode condemns the batch on an overflowing live lane, so the row
   engine raises on the row (`varka/VarkaVectorIR.java:403-415`); its sign test is the lane's
   own (`varka/VarkaVectorWalk.java:696-709`) and the long form is tested
   (`VarkaEmitterLongLaneSuite.scala:92`, `:141`) and costed (`VarkaEmitCostRegister.java:134`,
   `:162` for `IntNeg`). A checked multiply has none, at either lane (`VarkaNodeCompiler.java:
   1023-1028`); 338 used a `GuardedRange` and a wrapping multiply instead (`:647-660`). The
   interval family negates a year-month interval only (`VarkaIntervalCompiler.java:187`).
4. **The month machinery is the date family's, and is int-lane only.** `AddMonths`
   (`varka/VarkaVectorIR.java:1345`) is `DateTimeUtils.dateAddMonths` with its clamp, its count
   from `compileMonths` (`VarkaChronoCompiler.java:578`): a literal inside `MONTH_ARITH_MIN_MONTHS`
   to `MONTH_ARITH_MAX_MONTHS` (-24,576 to 24,564, `varka/VarkaChrono.java:533`), a column under a
   runtime guard, the negated interval as a checked `IntNeg`. Its day must be admitted by
   `calendarInput` (`:681`), which needs a bounded day: `GuardedDay` bounds it to
   `[NARROW_MIN_DAYS, NARROW_MAX_DAYS]` (1 March -12800 to 15 August 33134,
   `varka/VarkaChrono.java:105-109`; `varka/VarkaRangeAnalysis.java:272`), while a `NarrowLane`
   is unknown (`:286`), so `CAST(ntz AS DATE)` cannot feed it as it is (`VARKA-338.md` 0, item 7).
   The calendar lowerings require the int lane (`varka/Lane.java:391-406`); a kernel of both lanes
   runs an int node over the int species of the long species' width (`VARKA-28.md` 9), tested for
   a calendar field (`VarkaEmitterMixedLaneSuite.java:183`, `:380`) but never for `AddMonths`.
   The IR fuzzer's mixed corpus (`VarkaIrFuzzSuite.scala:430`) draws through
   `VarkaIrGrammar.MixedShapes` (`:1253`), whose int branch can draw `AddMonths` over int columns
   with a literal count, but its reach assertion (`VarkaIrFuzzSuite.scala:1317-1319`) does not
   require one.
5. **The exact floor division covers the day, not every width.** `floorDivLong`
   (`VarkaNodeCompiler.java:693`) takes a divisor `2^k * m` whose shifted dividend stays under
   `ConstDivide.EXACT_DIVIDEND_BOUND = 2^52` (`varka/VarkaVectorIR.java:783`): the day,
   `2^13 * 10,546,875`, qualifies; an hour, `2^10 * 3,515,625`, leaves `2^53` and is refused, and a
   minute or a second leaves more. So `time_bucket` is not one floor division over the long range.
6. **No sweep holds `AddMonths` to `dateAddMonths`.** `VarkaRangeRulesSuite.java:117-126` checks
   only that `dateAddMonths(d, m) - d` stays inside the range analysis's bounds, for twelve counts
   up to `NARROW_DECOMPOSE_MAX_DAYS` (`:53`), not the guard's ceiling; it never runs `AddMonths`.
   The one value check is `VarkaEmitterChronoSuite.scala:834`, 13 offsets over sampled days across
   the clamp boundaries. The opt-in whole-range sweep (`-Dvarka.sweep=true`, `:880`) covers
   `Year`, `Month`, `DayOfMonth`, `Quarter` and `DayOfYear`, not `AddMonths`.
7. **The conformance suite has no NTZ arithmetic on master; row 485's open work cancels one.** On
   master `hasArithmetic` is false for NTZ (`VarkaTypeConformance.java:120-122`), so the
   null-handling check drops its `x + y` (`:181`) and the overflow check asserts only that the
   census lists NTZ for none of `ARITHMETIC`'s eight operators, `+`, `-`, `*`, `negative`, `abs`,
   `try_add`, `try_subtract` and `try_multiply` (`:237-245`, `:650-651`). Nothing is cancelled
   naming this row. Row 485's unmerged work (`/home/max/proj/varka-485-conformance`) gives NTZ
   `PairForm("x - y", "ntz_minus_ntz", ...)` for null handling
   (`VarkaTimestampNtzConformanceSuite.java:56-57`), which the census owes here, so the check is
   cancelled naming 339 (its `VarkaTypeConformance.java:375`, `:430-456`); it removes
   `hasArithmetic`, and its overflow check keeps only forms whose census row lists the type
   (`:298`). The `+`, `-` and `*` rows never list `ntz` (`census.tsv:91-93`), so NTZ gets no
   overflow forms on either side. The census owes `ntz_minus_ntz`, `ntz_plus_dt`,
   `ntz_plus_ym`, `timestampadd`, `time_bucket` and `datetime_sub`'s NTZ form to it
   (`m8/census.tsv:604-611`, `:277`).
8. **The baseline covers three of the forms and cannot be rerun.**
   `VarkaTimestampNtzBaselineBenchmark` (`sql/core/src/test/java/.../benchmark/`) times `ntz +
   (ntz2 - ntz3) - INTERVAL '1' HOUR`, which is `ntz - ntz`, `ntz + dt` and `ntz - dt`: 271.9 ns
   a row on Spark's row path at 256 bits with no nulls (its committed results,
   `VarkaTimestampNtzBaselineBenchmark-jdk25-runner-epyc7763-results.txt` in
   `sql/core/benchmarks/`).
   Nothing times `ntz + ym`, `timestampadd` or `time_bucket`. The class throws when any section
   fuses (`:129`), and since 338 its comparison, cast and choice do, so it no longer runs as it is.

### What differs from the row text and the milestone plan

- **The `+-106,751,991` day guard is live for `timestampadd`'s DAY and WEEK, not for the month
  forms.** 2.6 states it for a calendar result day recombined with its time of day. Here the
  input day is held to `GuardedDay`'s range (item 4) and the count to `MONTH_ARITH_*`, so the
  result day is within about 12.1 million days of the epoch and its micros cannot overflow: the
  month forms recombine by a wrapping add that is exact by that structural bound, and day
  -106,751,992 is declined by the same guard, whole. The guard and checked add the row names are
  the DAY quantity's (2.2).
- **The day of an NTZ under a bound is built here, not by 340.** 340's text has it bound the
  cast's day; the month forms need it first, so it is a helper (`ntzDay`, 2.4) that 340 reuses,
  as 338 moved the floor division forward.
- **`time_bucket` is three arms, not a floor division** (item 5): exact over the whole long range
  for a width that divides a day or is a whole number of days, and a guarded window for the rest
  (2.5). A year-month width declines (question 1).
- **The conformance change is new overflow forms either way, and an un-cancelled null-handling
  pair if 485 lands first** (item 7, 3.2).

## 1. Goal and non-goal

`ntz - ntz`, `ntz +/- dt`, `ntz +/- ym`, `timestampadd` and `time_bucket` over a day-time width
fuse with the row engine's answers in LEGACY and ANSI; a batch with a lane that Spark raises on
goes to the row engine, which raises the same error on the row. Not this row: the TRY forms
(`TryEval`), a year-month `time_bucket`, the zoned `TIMESTAMP`, the date fields (340).

## 2. Design

All of it is existing IR nodes: `IntArith`, `IntNeg`, `GuardedRange`, `GuardedDay`, `WidenLane`,
`NarrowLane`, `BitOp`, `ConstDivide`, `AddMonths`. No new node, lowering or emit option, and no
emitted byte of an existing shape moves; the compiler's operand rule gains one case (2.3). `t` is
the NTZ operand, `D` 86,400,000,000.

### 2.1 The difference and the interval forms (time family)

- **`ntz - ntz`**: `IntArith(SUB, FAIL, end, start)`, a day-time interval output on the long lane
  as `d2 - d`'s is. The legacy `CalendarInterval` form declines by name, as `SubtractDates`'
  does (`VarkaChronoCompiler.java:155`).
- **`ntz + dt`**: `IntArith(ADD, FAIL, t, dt)`, `dt` a column, a literal or a nested
  `SubtractTimestamps`.
- **`ntz - dt`**: the `UnaryMinus` is absorbed, as `dateAdd` absorbs it into `SubDays`:
  `IntArith(SUB, FAIL, t, GuardedRange(dt, Long.MIN_VALUE + 1, Long.MAX_VALUE))`. The guard is
  `negateExact`'s domain and, inside it, `t - dt` overflows exactly when `t + (-dt)` does. A
  literal `Long.MIN_VALUE` interval declines at compile time.
  *30 October 2026 (step 2's review):* no literal arm is built. `ConstantFolding` folds
  `UnaryMinus` over a literal before Varka sees a plan, so `ntz - INTERVAL '1' HOUR` arrives as
  `ntz + INTERVAL '-1' HOUR`, the plain checked sum; the least literal raises at planning, and
  inside a conditional branch, which the folding leaves alone, it takes the guarded subtraction,
  whose guard sends each batch to the row engine.

### 2.2 `timestampadd` up to a week (time family)

The unit is upper-cased as Spark does; NANOSECOND and an unknown unit decline. `q` is the long
operand, `f` the unit's micros.

| unit | lowering | guard on `q` |
|---|---|---|
| MICROSECOND | `ADD FAIL(t, q)` | none |
| MILLISECOND to HOUR | `ADD FAIL(t, MUL WRAP(G(q), f))` | `+-floor(Long.MAX_VALUE / f)` |
| DAY, DAYOFYEAR | `ADD FAIL(t, MUL WRAP(G(q), D))` | `+-106,751,991` |
| WEEK | `ADD FAIL(t, MUL WRAP(G(q), 7 * D))` | `+-15,250,284` |

For the four time units the guard is exactly `multiplyExact`'s domain (no `f` is a power of two,
so it is symmetric). For DAY and WEEK it is the product's: a `q` past it either raises in Spark
(`toIntExact`, the int multiply, or the result past the long) or lands back in range from the far
end, and the batch goes to the row engine, which answers either. Every `q` that trips
`toIntExact` or WEEK's int multiply is past the guard, so the one guard covers all three. A
literal `q` is checked at compile time and folds to `q * f`.

### 2.3 The month forms (calendar family)

`ntz +/- ym`, and `timestampadd` by MONTH, QUARTER and YEAR:

    day   = ntzDay(t)                         (2.4)
    moved = AddMonths(day, months)
    out   = IntArith(ADD, WRAP, t,
              IntArith(MUL, WRAP, WidenLane(IntArith(SUB, WRAP, moved, day)), D))

`LocalDateTime.plusMonths` is the date's `plusMonths` with the time kept, so the result is `t`
moved by the change in its day. `months` is `compileMonths`' for `ym` (so `ntz - ym` keeps the
checked `IntNeg` that raises at `Integer.MIN_VALUE`); for `timestampadd` it is the quantity,
guarded to the `MONTH_ARITH_*` range over the factor (`[-24576, 24564]`, `[-8192, 8188]`,
`[-2048, 2047]` for 1, 3 and 12), then times the factor by a wrapping int multiply, which the
guard keeps exact, as the row's "month units in int" asks. A lane past it goes to the row engine.
The count position takes a column, a literal or int arithmetic at its top
(`varka/VarkaOperandPosition.java:75-95`): an int quantity arrives as `WidenLane(i)` and is read
as the int column, so `IntArith(MUL, WRAP, GuardedRange(i, lo, hi), k)` and, for MONTH, the bare
column under `AddMonths`' own count guard are shapes it takes today. A bigint quantity is
`NarrowLane(GuardedRange(q, lo, hi))`, and the position gains `NarrowLane` for it, safe for the
reason its javadoc gives a derived count: `AddMonths` guards the count's value at run time.

Both additions are exact: `|moved - day|` is at most 24,576 months, so `|out|` stays far under
`2^62` for every admitted day.

### 2.4 `ntzDay`

`GuardedDay(NarrowLane(floorDivLong(t, D)))`: 338's floor division, narrowed (exact, every
quotient fits an int) and guarded to the calendar's range, which `calendarInput` then admits. A
batch holding a timestamp before 1 March -12800 or after 15 August 33134 goes to the row engine.
One helper in `VarkaNodeCompiler`, beside `floorDivLong`, for 340.

*27 October 2026 (`VARKA-340.md` 2.1):* the helper is 340's guarded form,
`GuardedRange(NarrowLane(floorDivLong(t, D)), NARROW_MIN_DAYS + 365, NARROW_MAX_DAYS)`, not a
`GuardedDay`: the week forms and a field over `trunc` need a year of room below the floor. A
timestamp from 1 March -12800 to 28 February -12799 now goes to the row engine too. The month
forms are admitted over it as over `GuardedDay` (`addMonths` takes its day through
`calendarInput`, `VarkaChronoCompiler.java:325`); the sweep of 3.5 runs over the days it admits
and the day-guard edges of 3.4 are its bounds and one past each.

### 2.5 `time_bucket` over a day-time width (time family)

The width `b` and origin `o` must be literals (Spark makes them foldable; a null declines).
Spark's raise points, in order: `diff = ts - o`, `kb = k * b`, `o + kb`, and for `b >= D` also
`(k + 1) * b` and `o + (k + 1) * b` (0, item 1). Every arm computes the remainder
`r = floorMod(diff, b)` and then

    diff  = IntArith(SUB, FAIL, t, o)
    kb    = IntArith(SUB, FAIL, diff, r)          = k * b, raising where multiplyExact does
    start = IntArith(ADD, FAIL, o, kb)

and, for `b >= D`, `kb` and `start` each under `GuardedRange(_, Long.MIN_VALUE, Long.MAX_VALUE -
b)`, the next bucket's two overflows. The arms differ in `r`:

- **`b` divides a day** (`D = 2^13 * 3^3 * 5^8`; every width of a second, a minute, 15 minutes,
  an hour): `r1 = diff - floorDivLong(diff, D) * D` in wrapping arithmetic, exact modulo `2^64`
  (the product wraps at the lowest day, the difference does not) and in `[0, D)`, then `r = r1 -
  ConstDivide(r1, b, D) * b`, a truncating division of a non-negative value under `2^37`. Exact
  over every long, since `floorMod(x, b) = floorMod(floorMod(x, D), b)` when `b` divides `D`.
- **`b` is `n` whole days**: `q1 = floorDivLong(diff, D)`, in `[-106,751,992, 106,751,991]`, `k =
  floorDiv(q1, n)` by the sign move and a `ConstDivide` under `2^27 + n`, and `r = (q1 - k * n)
  * D + r1`. Exact over every long by the nested floors.
- **Any other width**: `diff` under `GuardedRange(_, -(2^52 - b), 2^52 - b)`, `k` by the sign
  move and one `ConstDivide`, and `r = diff - k * b`: about 142 years either side of the origin
  fuse, the rest decline. A width of this arm past `2^51` declines.

## 3. Tests and oracles

The oracle is Spark's own evaluation, through the reference evaluator and the differential
suites, as for every landed form.

1. **First, the risk**: `AddMonths` in a kernel of both lanes, in `VarkaEmitterMixedLaneSuite`, at
   the long lane's 2 and 8 lanes, the four null patterns, both bodies; and `AddMonths` added to the
   mixed corpus's reach assertion (item 0.4), so the IR fuzzer is held to drawing it.
2. **Conformance**, in `VarkaTypeConformance` and the NTZ suite (item 7). Null handling: if 485
   lands first, its `x - y` pair is un-cancelled by marking `ntz_minus_ntz` fused in the census;
   if not, 339 adds the same pair through a hook the NTZ suite fills. Overflow, either way: the
   forms come from `ARITHMETIC`'s census rows, which never list `ntz`, so the check gains a
   per-type list, NTZ's keyed on `ntz_minus_ntz`, `ntz_plus_dt` and `datetime_sub`: `x - y`, `x +
   (x - y)` and `x - (x - y)` in LEGACY and ANSI over the suite's values (the long range's ends,
   which overflow both ways), each fused and declining where Spark raises; `check` already
   matches a raised row to a declined batch in every mode (`VarkaTypeConformance.java:530-537`).
   On master the exemption's census assertion over the eight operators stays; 485 removes it.
3. **Coverage rows**, a family "TIMESTAMP_NTZ arithmetic": `ntz - ntz2`, `ntz - d`, `ntz + dt`,
   `ntz - dt`, `ntz + INTERVAL '1' HOUR`, `ntz + ym`, `ntz - ym`, `ntz + INTERVAL '1' MONTH`,
   `timestampadd` at MICROSECOND, HOUR, DAY, WEEK, MONTH, QUARTER and YEAR over an int and a
   bigint quantity, `time_bucket` at 15 minutes, one day with an origin, 30 days and 7 hours;
   each through the differential suite at every position, domains where a guard declines.
   *30 October 2026 (step 2):* two of step 2's rows are written differently. `ntz - d` is
   `ntz2 - d`: the fixtures derive `ntz` from `d`, so `ntz - d` is a time of day and its domain
   would have no row outside it. `ntz + INTERVAL '1' HOUR` is `ntz + INTERVAL '1' DAY`: the
   fixtures' NTZ values stop about four hours below the long's top (`d` inside the cast's days, a
   time of day added), so an hour overflows on no row and a day does. Each domain is Spark's own
   `try_` form of the row, unknown where an operand is null.
   *30 October 2026 (step 2's review):* that is wrong for the two rows whose kernel guards one
   operand alone, which declines a batch whose other operand is null. `ntz - dt` is
   `dt <> <least interval> AND` the form above and `ntz2 - d` the cast's day bound `AND` it, and
   the edges fixture gains a row for each: a null `ntz` beside the least interval and a null
   `ntz2` beside a day past the cast's.
4. **The edges**, in `VarkaTimestampNtzSuite`, each overflowing row in one batch of three so the
   others stay fused, LEGACY and ANSI: `Long.MAX_VALUE` plus a microsecond; the long range's ends
   subtracted; `dt = Long.MIN_VALUE` and `Integer.MIN_VALUE` months negated; `timestampadd` DAY at
   `+-106,751,991` (fused) and `+-106,751,992` (declined, the row engine's answer whether a value or
   `DATETIME_OVERFLOW`), WEEK at `+-15,250,284` and one past, YEAR at 2047, 2048, -2048 and -2049;
   the day guard at `NARROW_MIN_DAYS - 1` and `NARROW_MAX_DAYS + 1`; `time_bucket` with a day width
   at a timestamp whose bucket starts in range and whose next does not (Spark raises), and the same
   timestamp with an hour width (fused, no raise).
5. **The calendar proof, the first whole-range sweep of `AddMonths`** (item 0.6). A JUnit sweep
   beside `VarkaRangeRulesSuite`: every day `GuardedDay` admits, at a time of day drawn per day
   plus 0 and `D - 1`, for the twelve counts of its `addMonths`, the composition of 2.3 in scalar
   form against `DateTimeUtils.timestampAddMonths(t, m, UTC)` and `LocalDateTime.plusMonths`; and
   the emitted kernel, `AddMonths` and the recombination, over the same days and counts, opt-in
   under `-Dvarka.sweep=true` beside the calendar sweep at `VarkaEmitterChronoSuite.scala:880`.
6. **The `time_bucket` proofs.** In `long_divide.smt2`, over all 64-bit `x`: the divides-a-day
   remainder for each coverage width, the nested floor for whole days, and the window's dividend
   bound. In the JVM: every one of the 504 divisors of a day and `n` in 1, 7, 30, 365 and
   106,751,991 days, over the long range's ends and a seeded draw of differences, against
   `timeBucketDTInterval`, raising where it raises.
7. **The equivalences of item 0.1**, pinned at `Long.MIN_VALUE`, `Long.MAX_VALUE` and their
   neighbours: `subtractTimestamps` against `subtractExact`, `timestampAddDayTime` against
   `addExact`, the `timestampadd` guards against `multiplyExact` at the bound and one past.
8. **The fuzzers**: the Spark fuzzer's grammar gains the forms over its NTZ column.
   *30 October 2026 (step 2):* the grammar (`VarkaCatalystGrammar`) had no NTZ type, so step 2
   adds one, its columns `ntz` and `ntz2` and two literals, with `ntz - ntz`, `ntz - d`,
   `ntz + dt`, `ntz - dt` and the choices; later steps add their forms to it.
9. **Generated files** in `ADDING_AN_EXPRESSION.md`'s order: `coverage.json`, the docs tables
   (the two families' `covers` text), `width_audit.json`, `emitted_bytes.json` (new shapes only),
   `census.tsv`'s fused column.
10. **The PR build and the dispatched option matrix** over the new and changed suites.
   *31 October 2026 (step 2's matrix, run 41239405318):* three failures, none a wrong answer.
   Under `arrowBatchSize=1` the two rows of item 3's note above, a null `ntz2` beside a day past
   the cast's and a null `ntz` beside the least interval, are each a batch of their own, null on
   every row in the null column, so the masked driver's all-null shortcut returns before the
   guard runs and every output is null, Spark's answer; the verdict, per row, still said decline.
   At the defaults the same rows decline because their batch holds valid values in that column.
   `VarkaDomains` now models the shortcut (`VarkaGuardSpecs.allNullShortcut`, from the emitter's
   own column sets): such a batch must answer, outside the domain too. Under
   `checkIntOverflow=false` the five coverage rows and the grammar's NTZ forms move every draw of
   the Spark fuzzer's seeds: composition 177 wraps `try_subtract(i, 1)` at `Int.MinValue`, by
   design, so its skip line is back (VARKA-507 removed it as stale when a shift drew no overflow;
   it comes and goes with the draw), and the planted-rollback search took composition 17, whose
   overflow the configuration already breaks unplanted, so it now counts only a composition that
   agrees without the plant.
   *1 November 2026 (review of that fix):* three of its choices were replaced. The skip line came
   and went with the draw and matched only by a name embedding the seed and the iteration count,
   so it is gone again: under `checkIntOverflow=false` the Spark fuzzer reruns a disagreeing
   composition with the check forced on, everything else equal, and counts one that then agrees
   as a configuration wrap in its summary (the main test, the warmed arm and the pivot test); one
   that still disagrees is a finding, shrunk with the check on. The planted-rollback search passed
   over every composition that disagreed unplanted, even where the plant added a disagreement of
   its own; it now compares, per forced output, the planted kind with the unplanted one, and the
   warmed test runs its replay unplanted first and requires it to agree (under the same rerun
   rule). It still finds composition 86, output 1, under the defaults, `arrowBatchSize=1` and
   `checkIntOverflow=false`. And the shortcut moved into the verdict: `Guards` carries the
   column sets (`VarkaBodyEmitter.shortcutColumnSets`, the one helper both driver forms emit
   from), `verdict()` returns `Verdict.SHORTCUT` for a batch the shortcut takes, so every caller
   gets the batch's answer, and `VarkaDomains` reports a partition outside the domain that the
   shortcut answers whole, since its guard then went unexercised.
   *1 November 2026 (second review):* the configuration-wrap excuse above is gone. Two review
   rounds found it unsound - a wrong ancestor above a wrapping child was excused, under ANSI one
   overflow error masked every other wrong row, a NULL against a value from any cause passed as a
   wrap, the filter path was never probed, the checked rerun could legitimately never reach the
   kernel - and each disagreement cost a query per subtree. It is replaced by a rule with no
   excuse in it: under `checkIntOverflow=false` a composition any of whose kernels holds a node
   whose emission reads the option (`Analysis.readsCheckIntOverflow`, an int add, subtract,
   multiply or negation in `FAIL` or `NULL` mode, the test the emitter's own reads of the option
   now go through) runs with the check pinned on, every other composition under the
   configuration's own options, and every disagreement is a finding, its reproducer naming the
   options it ran under. The decision is static, from the kernels the composition's queries
   compile, and the same per-composition options serve the main test, the warmed arm, the pivot
   test, both planted tests and the reproducer replay, which pins a reproducer that names no
   options; its `checkIntOverflow=false` skip line is therefore stale and removed. At seed
   20261008 the main test pins 86 of 200 compositions and runs 114 under the configuration's own
   options (it asserts a fifth at least run unchecked), the warmed arm pins 1 of 8, and the
   planted search still finds composition 86, output 1, unpinned, under the defaults,
   `arrowBatchSize=1` and `checkIntOverflow=false`. `VarkaDomains` now counts a row as able to
   reach a guard only if its own one-row batch does, by the same verdict, so a row whose bounded
   inputs are all NULL, which the shortcut answers, no longer counts.

## 4. Predictions and costs

The measurement reruns the baseline (5, step 1) with the fuse check lifted, on the runners' EPYC
7763 at 256 and 128 bits, diffed against the committed files.

1. **No existing shape's bytes move**; the `option_arms` digests move with the new shapes.
2. **The chain** at least ten times faster than the row path at 256 bits: three loads, three
   checked ops and a store against 271.9 ns a row of `ZonedDateTime` arithmetic.
3. **The month form** at least five times faster: one `AddMonths` against `plusMonths` through
   `ZonedDateTime`. **`time_bucket`** at least twice as fast at 256 bits for the divides-a-day arm,
   whose two divisions are the conversion route; within 20% either way at 128 bits.
4. **Test time**: the calendar sweep under 10 s, `VARKA-281`'s 1.5 s at four times the work per
   day (calibration ratio 0.08 taken as 1, since `ZonedDateTime` is the slower decode); the
   differential's new rows under 20 s.

Cost: medium, as the row says. About four days after the baseline; `time_bucket` is half of it.

## 5. Order and files

1. **The baseline**, its own pull request on the unchanged code:
   `VarkaTimestampNtzArithmeticBaselineBenchmark`, the chain again, `ntz + dt` over the interval
   column, `ntz + INTERVAL '1' MONTH`, `timestampadd(HOUR, q, ntz)`, `time_bucket` at 15 minutes
   and at a day, each with and without nulls; its fuse check is the shared
   `VarkaSqlBenchmarkBase.requireBaseline` over each section's `fuses` flag, none set at step 1,
   so the after-run reuses the class: each of steps 2 to 4 sets the flag of the sections it
   fuses, and a listed section must then run fused over every batch
   (`VarkaSizeLadder.varkaFused`). `VarkaTimestampNtzArithmeticBaselineQueriesSuite` checks the
   same list at pull-request time, so a step that fuses a section it does not list fails there. The fixture's timestamps stay
   inside 2.4's `GuardedDay` range, the bound that matters: every int passes the HOUR guard.
2. **The mixed-lane `AddMonths` test** (3.1), then 2.1 with the conformance hook and its rows.
3. **`timestampadd` up to a week** (2.2), then **the month forms** (2.3, 2.4) and their sweep.
4. **`time_bucket`** (2.5) with its proofs.
5. **The generated files, the measurement** and its verdict.

| file | what |
|---|---|
| `VarkaTimeCompiler.java` | 2.1, 2.2, 2.5 |
| `VarkaChronoCompiler.java` | 2.3 |
| `VarkaNodeCompiler.java` | `ntzDay` |
| `varka/VarkaOperandPosition.java` | `NarrowLane` as a month count |
| `varka/VarkaFamily.java` | the time and calendar families' `covers` text |
| `sql/varka/proofs/long_divide.smt2`, `VarkaProofFiles.java` | the bucket claims |
| tests | conformance hook, mixed-lane case, coverage rows, edges, sweep, bucket and guard pins |
| benchmark | the baseline class, its results files at both widths |
| generated | `coverage.json`, `docs/sql-varka.md`, `width_audit.json`, `emitted_bytes.json` |
| `sql/varka/plans/m8/census.tsv` | the five forms fused for `ntz` |

## 6. Out of this row

The TRY forms through `TryEval`, whose census row already lists `ntz` for rows 330, 342 and 349
(`census.tsv:628`). Under it a `FAIL` node here turns null; a lane past a guard is not a `FAIL`
node and declines the batch instead, which is still right for TRY. Also out: a year-month
`time_bucket` (question 1); the date and time fields over NTZ (340); `CAST(i AS INTERVAL DAY)`
as an operand (332); `timestampadd` over a `DATE`, which Spark casts to the zoned type.

## 7. Declines that stay

The legacy `CalendarInterval` forms; a windowed `time_bucket` width past `2^51`, a non-literal or
null width or origin; NANOSECOND and unknown units; a literal `Long.MIN_VALUE` interval negated
(at run time, by the guard: 2.1's note of 30 October 2026);
every batch with a lane past a guard, which the row engine answers.

## 8. Questions for the owner

Answered by the owner on 27 October 2026: (a) to both - a year-month `time_bucket` declines by
name with `m10/SCOPE.md` item 117, and the windowed arm is built.

1. **A year-month `time_bucket`.** (a) Decline by name and add an `m10/SCOPE.md` item: it needs the
   year and month of the NTZ day and two `AddMonths` over the origin's day. (b) Build it here
   after 2.4. *Recommended: (a)*: 2.6 names only a day-time width, and the form is half again of
   `time_bucket`'s work for a rarer width.
2. **The windowed `time_bucket` arm** for a width that neither divides a day nor is whole days.
   (a) Build it (2.5, one guard and one division). (b) Decline those widths. *Recommended: (a)*:
   it is the plain floor division, and only timestamps over 142 years from the origin decline.
The baseline comes first, as its own pull request (5.1): not a question, since the house rule
for a change that improves something settles it, as it did for rows 377 and 27.

## 9. Done when

Row 339's text holds: each form fuses, an overflowing lane declines to the row engine's error, and
the month forms are proven against `java.time` by 3.5's whole-range sweep, scalar and emitted. And:
the conformance suite runs NTZ's own forms; the edges of 3.4 pass; the `time_bucket` claims hold
under both solvers; `dev/varka_census.py --check` passes; no existing shape's bytes move; the PR
build and the option matrix pass; the measurement is committed with its predictions scored.

## 10. Outcome

Steps 1 and 2 merged as #1229 and #1244; their findings are the dated notes in 3 above.

### 10.1 Step 3: `timestampadd` and the month forms

Built 9 November 2026 on `varka-339-step3`, from master at `264512efbd5`.

1. **`timestampadd` up to a week (2.2)** is `VarkaTimeCompiler.ntzTimestampAdd`, as the table says:
   MICROSECOND a checked add of `q`, MILLISECOND to WEEK a checked add of `q * f` under
   `GuardedRange(q, -floor(Long.MAX_VALUE / f), floor(Long.MAX_VALUE / f))`, DAYOFYEAR as DAY. A
   widened int quantity skips the guard where every int is inside it (up to an hour); a foldable one
   folds to its product, or declines past the bound. NANOSECOND, an unknown unit and a zoned
   `TIMESTAMP` decline. `VarkaTimestampNtzArithmeticSuite` holds guard and checked add to Spark's
   `timestampAdd` over the long's ends, at each unit's bound and one past, and that past the bound
   MILLISECOND to HOUR always raise (3.7).
2. **The month forms (2.3, 2.4)** are `VarkaChronoCompiler.ntzMonthShift`: the date
   `ntzDay(NarrowLane(floorDivLong(t, D)))`, admitted as a calendar node's day, moved by
   `AddMonths`, and `t + WidenLane(moved - day) * D` in wrapping arithmetic. `ntz +/- ym` takes
   `compileMonths`' count, so `ntz - ym` keeps the checked `IntNeg`; `timestampadd` by MONTH,
   QUARTER and YEAR folds a literal, reads an int column as itself (bare at a month, under
   `GuardedRange(i, lo, hi)` times 3 or 12 otherwise), and narrows a bigint under the same guard,
   `MONTH_COUNT` now taking a `NarrowLane` (2.3). Both are exact by 2.3's bound. No node, lowering
   or option is new; no existing shape's bytes move.
3. **`add_months(ntz, i)` and `CAST(ntz AS DATE) + INTERVAL n MONTH`**, which 340 left declining as
   this row's (`VARKA-340.md` 10), fuse: `calendarDay` already held the date to `ntzDay`'s range, so
   the decline alone kept them out.
4. **The sweep (3.5).** `VarkaTimestampNtzMonthSuite` holds the scalar composition to
   `timestampAddMonths` and `LocalDateTime.plusMonths` for `VarkaRangeRulesSuite`'s twelve counts,
   and the emitted kernel to `plusMonths`, declining a batch a microsecond past either end of the
   date guard. By default the scalar form walks a day in 7 and the kernel a day in 2003, beside
   every day within 400 of the guard's ends; `-Dvarka.sweep=true` walks every admitted day at its
   first and last microsecond and a drawn time, both forms. Predicted (4.4) under 10 s for the
   sweep: held at 6.0 s for the suite in a matrix JVM (scalar walk 1.0 s, kernel walk 4.4 s), after
   the kernel walk took 23.8 s at a day in 211 and moved to a day in 2003. The whole-range opt-in,
   at about 12 us a row through the reference evaluator, would take hours; not run.
5. **The mixed-lane risk (3.1)** gains its new operand kind: `AddMonths` over a narrowed long day
   by a narrowed, guarded bigint count, at both widths and every null pattern.
6. **Coverage (3.3).** Seventeen "TIMESTAMP_NTZ arithmetic" rows (`timestampadd` at MICROSECOND over
   `l`, SECOND over `i`, HOUR, DAY, WEEK, MONTH, QUARTER and YEAR over both; `ntz + ym`; `ntz - ym`;
   `ntz + INTERVAL '1' MONTH`) and `add_months(ntz, i)` in the fields family, each with its domain:
   up to a week Spark's `try_add` of `try_multiply` of the unit's interval, behind the kernel's
   guard on the quantity where it has one; for the month forms the fields' date guard and the
   count's range. An int of microseconds (under 36 minutes) overflows no fixture's NTZ value, the
   largest about four hours below the long's top, so that row is SECOND's. The edges fixture gains,
   beside the int's and the long's top, the last microsecond of the day before the last convertible
   one, where the sums leave the long. The ANSI form lists `DATETIME_OVERFLOW` by its first row, and
   the guarded rows at the right side of a false AND beside `add_months(d, i)` and `ntz + dt`, for
   the reason they are listed.
7. **The edges (3.4)**, in `VarkaTimestampNtzSuite`, LEGACY and ANSI: every unit's quantity at its
   guard's ends on the kernel, from the epoch and, for the month units, from 29 February 2024; one
   past each end in a batch of its own that the row engine answers (DAY 106,751,992 from a day
   before the epoch, YEAR 2048) or raises `DATETIME_OVERFLOW` on (MICROSECOND past the long, HOUR,
   WEEK, a month count past the int); the month forms at the date guard's ends and one past; and the
   least month count negated, which raises.
8. **The Spark fuzzer.** Its grammar gains `timestampadd` by HOUR, DAY and MONTH, `ntz +/- ym` and
   `ntz + INTERVAL '1' MONTH`. A hostile year-month interval moves an NTZ value past the long, which
   Spark raises as `Math`'s bare long overflow, no condition, reaching the fuzzer inside the job's
   failure; `VarkaSparkFuzz.outcomeOf` names it by class, as the coverage harness does
   (`VarkaAnsiForm.isExactLongOverflow`), and `expected_errors.tsv` lists it and `DATETIME_OVERFLOW`
   for the family.
9. **The baseline.** `TIMESTAMP_NTZ plus INTERVAL '1' MONTH` and `timestampadd(HOUR, q, ntz)` are
   `WHOLE` at both densities, so `VarkaTimestampNtzArithmeticBaselineQueriesSuite` holds them fused.
10. **The option matrix.** Under `checkIntOverflow=false` the checked add wraps where Spark raises,
    so the tests holding `timestampadd` by MICROSECOND, SECOND and HOUR to declining or raising take
    `ntz + dt`'s skip line; the guarded units still decline. `lanesOverride=16` and
    `validityByWidth=false` pass the coverage, differential, harness and NTZ suites with no line.
11. **No proof file.** No lowering here is exact only over a bound of its own: the guards are the
    exact domains of `multiplyExact` and the month arithmetic, the floor division is
    `long_divide.smt2`'s, and the calendar claim is the sweep's (3.5).

What differed from the plan:

- A foldable quantity, not only a literal, folds: the analyzer's widening cast of an int literal
  reaches the compiler unfolded when the optimizer has not run. Its evaluation raising declines.
- `DateVarkaSupport.foldDaysOffset`, `compileMonths`' fold, narrows a `Long` by `intValue()`, so
  `timestampadd`'s month count is built apart from `compileMonths`; only `ntz +/- ym` uses it.
- Row 579 (a calendar node over a guarded narrowed long is not in the IR fuzzer's mixed draw)
  is exactly the month forms' shape, and stays that row's.

Changed in review: the time family's `timestampadd` arm claimed the month units too, which only the
chain's order kept from the calendar arm, so it now leaves them to it and each node has one
claimant; `MONTH_COUNT` takes a `NarrowLane` only over a `GuardedRange` inside the int range, so a
truncating narrowing is refused; a bigint quantity off the long lane declines with a reason.

*Added 28 November 2026 from branch `varka-339-ntz-arith` (`6476788b31a`), unchanged:
the record of step 4 as built there, not merged. Where it cites item 117 of milestone 9's old scope,
read row 664 of `m9/PLAN.md` (A.4).*

### 10.2 Step 4: `time_bucket`

Built 20 November 2026 on `varka-339-ntz-arith`, from master at `767dcddce50`, while the owner was
away (see the decisions below). `varka-339-step3`, the older local branch, holds nothing master
lacks: its diff from its merge base is #1442's, file for file.

1. **The arm (2.5)** is `VarkaTimeCompiler.ntzTimeBucket` and `bucketStart`: the difference
   checked, the remainder `floorMod(diff, b)` in one of three forms, `kb = diff - r` checked, which
   overflows exactly where `multiplyExact(k, b)` does, and the start `o + kb` checked. A width that
   divides a day takes the time of day (`microsOfDay`, 340's) and one `ConstDivide` under `D`; a day
   is the time of day itself; `n` whole days take the day (`floorDivLong`), a floor division by `n`
   under `106,751,993 + n - 1`, and `r = diff - k * b` in wrapping arithmetic, exact because the true
   remainder is a long; any other width up to `2^51` divides the difference once under `2^52`. An
   origin of 0 drops the difference and the sum, a width of 1 the remainder. A year-month width
   declines by name with item 117 of the old m9 scope, as question 1 settled; a null width or
   origin, and
   a windowed width past `2^51`, decline. No node, lowering or option is new.
2. **Where the plan was wrong: the guards sit on the count, not on `kb` and the start.** 2.5 put
   the next bucket's two overflows under `GuardedRange(_, Long.MIN_VALUE, Long.MAX_VALUE - b)` on
   `kb` and on the start, and the window on the difference. Built so, the whole-days arm with an
   origin is 19 ops deep, past `MAX_CHAIN_DEPTH` = 16, and `time_bucket(INTERVAL '7' DAY, ntz, o)`
   declined as over the fused budget (the emitted test found it). Both are a range of `t` instead,
   constants of `o` and `b` (`bucketGuard`): the window is `t` within `2^52 - b` of the origin,
   clamped to the long, and the next start leaves the long exactly where `k` passes
   `kmax = min(floorDiv(MAX - b, b), floorDiv(MAX - b - o, b))`, which is where `t` reaches
   `o + (kmax + 1) * b`. One guard on the leaf replaces two on the root path, and the whole-days
   remainder's wrapping `diff - k * b` replaces 2.5's `(q1 - k * n) * D + r1`, four ops fewer.
3. **The proofs (3.6).** `long_divide.smt2` gains `LONG_DIVISORS`' three new divisors (15 minutes,
   30 and seven hours, each proven exact under `2^52` in both division forms, as every long divisor
   is) and a `time_bucket` section: the divides-a-day remainder at 15 minutes as two linear lemmas
   (the steps over a time of day wrap nowhere, and `floorMod(floorMod(x, D), b) = floorMod(x, b)`),
   for 7, 30 and 365 days the quotient, its dividend bound and the wrapping remainder, and at seven
   hours the window's bound, the quotient and remainder, and the bound refuted one past the window.
   As first written, four checks stated over the wrapping steps of `x` answered unknown under cvc5
   (Z3 proved them); restated over the floor quotient the first check proves, as `floorDivDay`
   states the time of day, all 2,281 checks hold under both, Z3 in 118 s to 209 s for the file on
   the shared laptop and cvc5 in 67 s to 81 s, the new section 0.2 s of it. The 504 divisors of a
   day are the JVM's (below), not the solver's.
4. **`VarkaTimestampNtzBucketSuite`**, new. The scalar form of the three arms is held to Spark's
   `timeBucketDTInterval` at UTC for every divisor of a day, the whole days 1, 7, 30, 365 and
   106,751,991 and five windowed widths (seven hours, 36 hours, 1,000,003, a day and one, `2^51`), at
   five origins (the epoch, a held value, -1 and the long's ends five in), over the long's ends, the
   ends of the buckets around the origin, the long's ends and the window's, and a seeded draw:
   equal where both answer, declining exactly where Spark raises or outside the window. The emitted
   kernels of 35 of those widths at the five origins run the same values: one batch of everything
   Spark answers inside the window must be served, and each value it raises on, or outside the
   window, declines in a batch of its own. The third test pins the declines. 7.7 s for the suite
   at the defaults with bare sbt.
5. **Coverage (3.3)**: four rows in the NTZ arithmetic family, `time_bucket` at 15 minutes, a day
   from 06:00, 30 days and seven hours, each `Proved("long_divide.smt2")` and with its domain: the
   first bucket start that is a long for 15 minutes, whose outside no fixture value reaches, so it
   is listed as a total domain; for a day from 06:00 and for 30 days the counts below the first
   bucket whose next start leaves the long (and, at 30 days, above the last whose start is a long),
   written as `date_from_unix_date` midnights; for seven hours the window. The seven-hour row joins
   the false-AND list, for the reason `ntz + dt` is on it; the first review put the day and 30-day
   rows there too, and the harness found both served at that position, their entries stale.
6. **The edges (3.4)**, in `VarkaTimestampNtzSuite`, LEGACY and ANSI: each form and an hour over
   the date guard's ends on the kernel; the last day whose midnight is a long, plus a microsecond,
   whose day bucket starts in range and whose next does not, raising from the row engine, and the
   same value's hour bucket served; the least long's hour bucket, whose start is below the long,
   raising.
7. **The baseline**: both `time_bucket` sections are `WHOLE`, so every section of
   `VarkaTimestampNtzArithmeticBaselineBenchmark` now fuses and its queries suite holds them to it.
8. **The Spark fuzzer**'s grammar gains `time_bucket` at 15 minutes, a day from 06:00 and seven
   hours; `expected_errors.tsv` adds the family to `ARITHMETIC_OVERFLOW`, which Spark raises in
   every mode where the difference, the offset `k * b` or a start under a day leaves the long
   (`MathUtils`); a start of a day or more goes through `instantToMicros`, whose bare overflow is
   the family's listed `java.lang.ArithmeticException`.
9. **The census** marks `time_bucket` done for `dt` and `ntz`, `ym!` for the declined width, and
   `dev/varka_census.py` maps the name to `TimeBucket`.
10. **The option matrix.** Under `checkIntOverflow=false` the checked subtract and add wrap where
    Spark raises, so the NTZ suite's bucket test and the bucket suite's emitted test take the
    `ntz + dt` skip line's reason. Which coverage-harness tests of the four rows need one is the
    dispatched matrix's to say, owed with the measurement.

Changed in review (`/code-review high`, a first round over the work in progress and a second over
the result): the generated files and the proof file regenerated; the fuzzer's expected error and
the false-AND list extended (by the seven-hour row only, as the harness then showed); the coverage rows marked proven; the guard's unreachable empty case an
invariant of a record rather than a decline; a null origin pinned; the NTZ suite's seven-hour form
served over its window, not the date guard's ends, which lie far outside it; the emitted test's
declining values set beside two values the kernel serves, not beside 0 and 1, which the far
origins' windows exclude; and `VarkaDivisorProofSuite` and the proofs' README saying that
`time_bucket`'s divisor is the query's literal, exact by `ConstDivide`'s bound, a proof for any
long divisor being item 116's of milestone 9's old scope file.

Predictions: 4.1, no existing shape's bytes move, held for this step: `emitted_bytes.json` gains
the four rows' shapes and only option-arm digests move. 4.4's "under 20 s" for the differential's
new rows was not timed apart from the suites; the bucket suite takes 7.7 s and the NTZ suite's new
test 1.7 s. Unpredicted: `long_divide.smt2` under Z3 takes 19% longer, 174 s against master's 147 s
run side by side on the shared laptop, for its three new divisors, which leaves less room under
`dev/varka_prove.sh`'s 300 s run limit per file.

Master at `0b8be5a33e4` is merged. The regeneration after the merge was stopped partway, by the
owner's order of 20 November 07:30 CET (compiles only from then on). Its coverage and docs steps
changed nothing; the width audit, bytes and census steps, and the suites on the merged head, are
owed with the review of the merge.

What is owed: the measurement (step 5, 4.1 to 4.3 scored there), on the runners' EPYC 7763 at 256
and 128 bits, which the coordinator dispatches; and the option matrix over the new and changed
suites.

### Decisions taken while the owner was away (20 November 2026)

1. **A new branch from master, not `varka-339-step3`.** Its diff from its merge base is #1442's,
   file for file, so nothing there is unmerged.
2. **The guards moved onto the count** (10.2 item 2) rather than raising `MAX_CHAIN_DEPTH` or
   splitting the output: the budget is the emitter's policy for every shape, and the guard on the
   leaf is the same predicate, computed once at compile time.
3. **The coverage rows' widths are the plan's; the origin is 06:00.** A day from the epoch would
   put its next-start bound past every fixture value; 06:00 brings the last day's afternoon outside
   the domain, so the row's guard is exercised both ways.
4. **The year-month decline keeps the census's `!` mark**, whose generated reason carries the
   decimal decision's date of 14 October; the note gives this one's, 27 October, rather than the
   script growing a per-entry date for one entry.
5. **Only the two skip lines the code makes certain are added**; a harness line that might not
   fail would be stale, so the rest wait for the matrix.
