# VARKA-577: decimal remainder by a variable divisor

Row 577 of `PLAN.md`. Opened on 7 November 2026 on the owner's word, from the open question in
`VARKA-363.md` 8, point 1: the decimal `%`, `mod`, `pmod` and `try_mod` have no row that builds
them.

## 0. What exists now (admission check)

From master at `0b682025e09`, by reading `PLAN.md`, `census.tsv`, `VARKA-359.md`, `VARKA-363.md`
and `VARKA-30.md`. Nothing was dispatched and nothing was measured for this row.

1. **No decimal remainder fuses.** `census.tsv` lists `%`, `mod`, `pmod` and `try_mod` as
   accepting `dec` with `dec` absent from `fused_today`, and the three that carry a note for it
   say "a remainder by a variable divisor, which the long lane has no vector division for".
   That covers the 64-bit decimals too (precision 18 and below), not only the 128-bit pair.
2. **Three plans name three owners.** `VARKA-359.md` 6 lists the four under row 363. Row 349's
   plan suggested row 30. `VARKA-363.md` 0, item 5 finds they never cut a scale: Spark's `Remainder`
   and `Pmod` call `adjustPrecisionScale`, but the raw precision
   `min(p1 - s1, p2 - s2) + max(s1, s2)` never passes 38, so 363's `HALF_UP` division by a power
   of ten does not serve them. `VARKA-363.md` 8, point 1, asks the owner whether the general
   division they need is a milestone 8 row or `m9/SCOPE.md` scope. This row is the answer: a
   milestone 8 row.
3. **Decimal division stays out.** `PLAN.md` 8 puts decimal `/`, `try_divide` and `div` out of
   milestone 8 (owner's decision, 14 October 2026). A remainder is not named there, and this row
   does not reopen the quotient.
4. **No plan builds a division by a column.** Row 363's magic multiply is exact only for a
   constant divisor `10^k`. `VARKA-30.md` covers the integral `%` by a variable on the long lane
   in floating point. A 128-bit dividend by a 128-bit or 64-bit divisor that varies per row has
   no form in any plan. The readings that mention one (`READING_RAPIDS.md` 21, a bit-by-bit long
   division of 256 iterations; `READING_COMET.md` 35, which keeps the 128-bit division out of
   line) describe scalar code.
5. **The semantics are Spark's.** Decimal `Remainder` is the exact remainder at the larger of
   the two scales with the sign of the dividend; `Pmod` adds the divisor and takes the
   remainder again; a zero divisor is null, or raises under ANSI; `try_mod` nulls in every case.
   The result type is `(min(p1 - s1, p2 - s2) + max(s1, s2), max(s1, s2))`. The plan confirms each
   against `arithmetic.scala` and the oracle before any form is built (**[I]**: this plan did not
   run the oracle).

## 1. Goal and non-goal

**Goal.** Fuse `%`, `mod`, `pmod` and `try_mod` over decimals of precision 1 to 38, with Spark's
answers in every mode, or decline a shape with a reason that names why, measured against the row
engine: built where it wins, declined with the measurement where it loses.

**Non-goal.** Decimal `/`, `try_divide` and `div`. The interval quotients by a decimal
(`DivideYMInterval`, `DivideDTInterval`), which round through `Decimal./` and so need the
quotient this milestone leaves out; `VARKA-363.md` claims them and they stay unbuilt until a
decision on them (section 7).

## 2. Design (to be settled by a spike)

The row's first step is a spike, not a build: which division form wins is not known.

1. **Operands at one scale.** The two operands are brought to `max(s1, s2)` by a multiply by a
   power of ten (row 360's rescale up). That multiply can pass 128 bits for a 128-bit operand, so
   a shape whose rescaled operand does not fit declines by its bound.
2. **The 64-bit case first.** Both operands up to 18 digits, once rescaled, are longs: the
   remainder is row 30's long-lane remainder. This part needs no pair and could land before 359.
3. **The 128-bit case: candidate forms.** (a) A floating-point estimate of the quotient and a
   correction by exact multiply-subtract, the form row 30 uses for longs, extended over the two
   words; (b) a limb long division (Knuth's algorithm D) over 64-bit words, branch-free per lane;
   (c) a per-lane scalar call, as the per-lane `Math` calls do. The spike prices each at both
   widths and 512 bits, on the runner and the laptop, and the verdict goes in section 10.
4. **The sign rules, once.** `Remainder` takes the dividend's sign, `Pmod` adds the divisor when
   the remainder is negative, and `try_mod` and the ANSI zero divisor go through row 481's null
   and raise modes. The zero divisor is a per-lane condition, not a per-batch decline.

## 3. Tests and proofs

1. The division form's identity `a = q * b + r` with `|r| < |b|`, proved in `decimal128.smt2`
   (359's file) or a file of its own, over the words as emitted.
2. A product table against `BigDecimal.remainder` over the slot table's edges: operands of one
   word and two, equal scales and unequal, a divisor of 1, of `10^k`, of the dividend, and zero,
   every sign pair.
3. The `Pmod` and `try_mod` rules over the same table, in all three modes, and the type sweep
   of the result types over every ordered pair with precision at most 38.
4. The coverage rows and the census cells for the four names, each naming its verification.

## 4. Predictions and costs, registered before the build

Registered on 8 November 2026, before any runner reading of the spike (10.2), and scored there:

1. **E leads B.** On the EPYC 7763 at 256 bits, E's rate is at least 1.3 times B's at every rung,
   for `x % y` and for `pmod`. B is a `BigInteger` call a row, so this lead, if it holds,
   overstates E's lead over a scalar remainder in `long`s (10.2, item 3).
2. **E leads D.** On the same runner and width, E's rate is at least 1.3 times D's at every rung.

The predictions for 577a and 577b are registered from the spike's figures before either is built.

## 5. Order, files and size

1. **The spike** (2.3), with the baseline addition for `%` over decimals at 256 and 128 bits.
2. **577a, the long-lane case** (2.2), after row 30.
3. **577b, the 128-bit case** (2.3, 2.4), after 359a, 360 (rescale up) and 481.

**Size: medium to large, fixed after the spike.** It rests on 30, 359, 360, 481 and 503.

## 6. Out of this row

The decimal quotient and every decimal rounding function (351). The interval quotients by a
decimal (section 7).

## 7. Questions for the owner

1. **The interval quotients by a decimal.** `VARKA-363.md` claims them "once a division by a
   variable exists". They round through `Decimal./`, which is the decimal quotient section 1
   leaves out. Should they stay unbuilt with their decline, or should this row's division be
   widened to serve them?
2. **The 64-bit decimals.** Part 577a could land on the long lane before the pair exists. Should
   it be a separate pull request ahead of 359, or wait for the whole row?

3. **Form (c).** *Settled by the owner, 9 November 2026:* (c) is the `BigInteger` call a row; a
   scalar remainder in `long`s is added only if (c) comes close to the winner (10.2, item 3).
4. **The 128-bit column on x86** (added 10 November 2026, from 10.3). At two long lanes, E and D
   run the Java fallback and B is the fastest form, 3 to 4 times faster than E. E's fallback
   does not beat the row path's whole query by AGENTS.md's 1.3 times (indicative only). Two
   answers are open. One: the remainder declines at 128 bits on x86 under `VARKA-359.md` 2.6.
   Two: it fuses there with B, or with a scalar remainder in `long`s. 7.3's rule adds a scalar
   arm only if B comes close to the winner; it did not foresee a width at which B *is* the
   winner.
   *11 November 2026 (10.4):* a third answer is measured on the laptop, not yet on the runner: F,
   E's arithmetic with no mask, reading the slots by lane inserts, has no fallback at two lanes
   and runs at about 4 times B's rate there.
   *11 November 2026, tested (10.4, "The test of 7.4"):* that third answer does not stand as one
   577b can take. After other vector code in the JVM, inserted F boxes (1 to 10 KB a row), and
   even the cleanest form, split F over a block transposed out of line, keeps one vector array a
   lane group (12 to 16 bytes a row) in every JVM, 12 to 16 times the per-row term of the
   warm-up's allocation allowance (1 byte a row). 7.4 is back to its two answers: decline at
   two lanes, or B behind an option. A two-lane vector form returns only with a defence against
   C2's trap budget in a shared JVM, which is VARKA-295's problem and not this row's.

## 8. Done when

- the four names fuse, or decline with a measured reason, for every decimal shape the type sweep
  lists, with Spark's answers in the three modes;
- the spike's verdict and the predictions scored are in section 10;
- the census cells for `%`, `mod`, `pmod` and `try_mod` read `dec` fused or name the measured
  decline.

## 9. Open points for the lead

1. The division form (2.3) is open until the spike.
   *10 November 2026:* on the runner, E at 256 bits (10.3). Still owed: a 512-bit reading on a
   host with a 512-bit datapath (the laptop's is double-pumped), the laptop's three widths as 2.3
   asks, the aarch64 column at two long lanes, where the pair runs natively (`VARKA-359.md` 2.6),
   and the 128-bit column on x86, which is question 7.4.
   *11 November 2026:* the 128-bit column on x86 is read on the laptop with two mask-free and
   shuffle-free variants (10.4); the runners' reading of it is owed, and its dispatches are in
   10.4.
2. Whether `VARKA-359.md` 6 and row 349's plan carry the dated note that this row owns the four
   names (this change corrects `VARKA-363.md` and the census).

## 10. Outcome

### 10.1 The baseline addition (5.1): built, its reading pending the runners

*8 November 2026, on master `99675606f2b`. Nothing here is timed yet: the reading is the runners'
(the dispatches are in the pull request).*

`VarkaDecimalBaselineBenchmark` gains sixteen sections, `x % y` and `pmod(x, y)` as values at
`DECIMAL(9, 2)`, `(18, 4)`, `(38, 10)` and `(20, 2)`, each with no nulls and with one row in ten
null, appended after the existing 88 so the committed results keep their places. All are
`Fusion.NONE`, and `VarkaDecimalBaselineQueriesSuite`'s fuse test passes with that class, so item
0.1 holds on the plan as well as in the census. Both operands share a type, so no section
rescales; the queries suite also checks that no divisor is zero at the benchmark's 2,000,000
rows, since one would raise under ANSI. The runner results at 256 and 128 bits are to be spliced
into the committed `-runner-epyc7763` files, the new sections only, with their provenance.

The benchmark's classes now follow the two-lane refusal #1262 added (VARKA-503): at 128 bits its
30 filters over a 64-bit decimal are `NONE`, and the first 128-bit run failed on that before
(MaxGekk/spark run 42574807845); the fuse test checks the classes with the refusal off and on.

*10 November 2026:* the reading landed in #1475 (vecbricks/varka runs 42846426514 at 256 bits and
42846449693 at 128), spliced into both `-runner-epyc7763` files of
`VarkaDecimalBaselineBenchmark` with a `spliced:` entry in each provenance file. As 0.1 says, none
of the sixteen fuses, so the two arms mostly tie; #1475 lists the exceptions, among
them `DECIMAL(38, 10) pmod` at one row in ten null at 256 bits, where the Varka arm is slower by
more than 3%. At `DECIMAL(38, 10)` without nulls the row path costs
791.2 ns a row for `x % y` and 988.0 for `pmod` at 256 bits, and 771.3 and 973.4 at 128 bits.

### 10.2 The spike (2.3): built and checked, the reading pending the runners

*8 November 2026, on master `99675606f2b`. Nothing here is timed yet: the reading is the runners'
(the dispatches are in the pull request), and the verdict is open until it lands.*

**The spike.** `VarkaDecimalRemainderSpikeBenchmark` (catalyst, test side, no emitter), over two
`DECIMAL(38, s)` slot columns at one scale, in VARKA-359's de-interleaved layout, on the magnitudes
with the dividend's sign put back; a zero divisor sets the row's bit and stores 0, and `pmod` adds
`y` to a negative remainder where `y > 0`, which is Spark's `r < 0 ? (r + y) % y : r`. Its forms are
E for (a), D for (b) and B for (c). Three things differ from 2.3's words:

1. **(a) is three rounds, not one correction.** One double carries 53 bits of a quotient that can
   reach 126. The argument that three rounds and two subtractions are exact is at
   `bodyE`: each estimate is shrunk by `1 - 2^-49` so it never exceeds `r / b` (the
   remainder stays non-negative) and is within a relative `2^-48` below it, so a round leaves `r <
   2^-48 r + b`; after three, `r < 2b` for `b >= 2` and `r <= 2` for `b = 1`. It is a hand
   argument over the rounding errors, not an SMT proof, which 3.1 still owes for 577b. E converts
   by the `0x4330` identity and reads the estimate's exponent and mantissa from its bits, never
   `L2D` or `D2L`, which C2 refuses at four long lanes on AVX2 (`skills/the-jit.md`).
2. **(b) is Knuth's long division at base 2, not over 64-bit words.** Algorithm D's digit step
   divides two words by one, which no vector instruction set here has; over 32-bit digits its
   estimate would be E's at smaller digits with more rounds. Base 2 is the one long division
   with no divider.
3. **(c) is `BigInteger`, a per-lane call to an allocating routine,** so E's lead over B
   overstates its lead over a scalar 128-by-128 remainder in `long`s, which is not built; that
   arm is owed before 577b fixes its form, unless the owner takes (c) as read (7, question 3).
   *9 November 2026:* the owner took (c) as read (7, question 3), so that arm is built only if
   B comes close to the winner.

**Checks, before any timing.** Each loop is checked against `BigInteger` before it is timed and
again in compiled code after its drive, at every rung, and `VarkaDecimalRemainderSpikeSuite`
checks all six arms in every build, over 3.2's edges (zero, 1, `10^k`, `2^k` and `2^k +- 1`
divisors, the dividend and its negation, the extremes, exact multiples, remainders of `|y| - 1`,
every sign pair) and quotients past 106 bits. No form was found wrong. Laptop smoke runs drove
every arm to level-4 code at every rung and passed the compiled-code check; they ran beside other
builds, so their times are not a reading, are not quoted, and no `-laptop-*` file is committed.

**Level 4 is not vector code: the intrinsic check.** A Vector API call C2 does not intrinsify
stays a call to the Java fallback, which boxes its vectors, at level 4 too. An earlier smoke run
under `UseAVX=2` and `PrintInlining` showed four `binaryOp` and two `compare` calls in E's loop
failing with no later success. With `-Xbatch -XX:+PrintCompilation` beside the tree (E's loop
alone, 64 rows, `MaxVectorSize=32`) all six are in the on-stack replacement at the round loop,
whose vectors arrive boxed from the interpreter (`PrintIntrinsics`: `unbox failed`); the method's
own level-4 compiles, which later calls run, leave none of their 277 `VectorSupport` sites a call.
It is the same at `UseAVX=3`, and no `not supported` is printed at either, so it is not AVX2's:
`x86.ad` lowers a 64-bit lane `MulVL` on AVX2 through `vpmuludq` (`vmulL`; AVX-512DQ is needed
only masked) and an unsigned compare through `vcmpu`, which flips the sign bits. E needs no other
form to stay vector on AVX2.

So the benchmark compiles each loop once more in a child JVM with the run's flags under `-Xbatch`,
`PrintCompilation` and `PrintInlining`, and reads the tree of the loop's last level-4 compile that
is not an on-stack replacement: a `VectorSupport` site whose last message is not `(intrinsic)` or
`late inline succeeded` is a fallback. Such an arm is still timed, as the code that host runs, but
labelled `[Java fallback]`, with the calls listed in the results file; a refusal would kill the
128-bit and AVX2 dispatches, where a fallback is the finding. On the laptop the check passes all
six loops at 256 bits, at `UseAVX=3` and at `UseAVX=2`, and at `UseAVX=2 -XX:MaxVectorSize=16` it
finds 72 calls left in Java in E's `x % y` loop, the two-lane rearranges and masks `VARKA-359.md`
0, item 9 records.

*10 November 2026, the self-review before the pull request:* the check as first written had four
faults, each fixed. It gave the child `java.class.path`, which under the benchmark workflow's
`spark-submit` lacks the test jars, so every runner dispatch would have failed; the child's class
path now adds the context class loaders' jars. It read only `VectorSupport` sites, so a Vector API
method C2 failed to inline, a call with its vectors boxed and no site under it, passed; such a
line now counts. A `COMPILE SKIPPED` record replaced the last good compile; it is now passed over.
And a check that could not answer (no level-4 compile, or the body not inlined) labelled the arm a
fallback; it now stops the run, since the label would name a fallback nobody saw.
After the fixes, the check run over all six loops on the laptop finds no fallback at 256 bits under
`UseAVX=3` or `UseAVX=2`; at `UseAVX=2 -XX:MaxVectorSize=16` it finds 72 calls in E's `x % y` loop,
80 in E's `pmod`, 62 and 70 in D's, all `VectorSupport` sites, and none in B's. So the AVX2 128-bit
run will label four arms, not one (read on the laptop, **[I]** for the runner until it runs).

**Two findings for 577b.** (1) `pmod` can overflow its own result type: the type is
`min(p1 - s1, p2 - s2) + max(s1, s2)`, but `r + y` is bounded by `y` alone, so with `p1 - s1 <
p2 - s2`, `pmod(-1, 10^9)` over `DECIMAL(3, 0)` and `DECIMAL(10, 0)` is `999999999` in a
`DECIMAL(3, 0)`, null under LEGACY and an error under ANSI (`Pmod`'s `toPrecision`, read from
`arithmetic.scala`, **[I]**: not run). `%` cannot, since `|r| <= |x|`. (2) The 64-bit case (2.2)
is not priced here: it is row 30's long-lane remainder, and 5.1 scopes the spike to 2.3.

**The reading, to fill from the runners:** ns a row by form, rung and width, from
`VarkaDecimalRemainderSpikeBenchmark-jdk25-runner-epyc7763-results.txt` and its `-128bit`
companion (and the Arm runner's file if it is dispatched), read on `Rate(M/s)` by minimums with
AGENTS.md's 1.3x threshold, and the laptop's three widths (2.3), pinned on an idle machine, still
owed. **Verdict: provisional** until then.

*10 November 2026:* the runner's reading is in 10.3.

### 10.3 The spike's reading on the EPYC runners

*10 November 2026. Two runs of `benchmark.yml` on vecbricks/varka on the EPYC 7763: run
42883478096 at 256 bits, and run 42883499519 at 128 bits (`-XX:MaxVectorSize=16`). Each ran
with `-XX:CompileCommand=dontinline,...::loop*` and `-XX:CompileCommand=inline,...::body*`. They
are committed as `VarkaDecimalRemainderSpikeBenchmark-jdk25-runner-epyc7763-results.txt` and its
`-128bit-` companion, each with its provenance file, as the artifacts gave them. Both ran on
`4c793f02383`, #1470's head. The benchmark and the suite there are identical to master's
(`ebd4f7040c6`, #1470 merged): an empty diff over both files.*

**The numbers**, in ns a row (`Per Row(ns)`), E / D / B:

| Width | Section | in L2 | in L3 | past L3 |
|---|---|---|---|---|
| 256 bits | `x % y` | 21.2 / 109.0 / 270.1 | 21.3 / 109.1 / 248.3 | 21.4 / 109.1 / 252.5 |
| | `pmod(x, y)` | 22.1 / 107.2 / 286.1 | 22.2 / 107.7 / 287.1 | 22.3 / 107.8 / 290.5 |
| 128 bits | `x % y` | 915.2 / 9990.6 / 258.9 | 915.8 / 9926.9 / 245.6 | 916.4 / 9883.9 / 247.2 |
| | `pmod(x, y)` | 975.2 / 9932.9 / 283.6 | 978.7 / 9992.9 / 287.2 | 976.2 / 9908.0 / 288.1 |

B is a scalar `BigInteger` call per row, so it does not depend on the vector width. Its times at
the two widths are within 5% of each other at every rung, so the two runs can be compared. Each
form's time barely moves from L2 to past L3, so the working set is not what limits them.

**The intrinsic check.** At 256 bits, all six loops are intrinsified. At 128 bits the check
labels four arms `[Java fallback]`: E's `x % y` (72 calls left in Java), E's `pmod` (80), D's
`x % y` (62) and D's `pmod` (70). B's two loops are intrinsified. These are the counts the
laptop found under `UseAVX=2 -XX:MaxVectorSize=16` (10.2). The **[I]** there now holds on the
runner. The calls are `binaryOp`, `blend`, `compare`, `convert`, `rearrangeOp` and mask sites,
listed per arm in the results file.

**The predictions (4), scored on `Rate(M/s)` by minimums:**

1. **E leads B by 1.3 times at every rung at 256 bits: held.** E's rate is 11 to 13 times B's at
   all six rungs (47.1 against 3.7 M rows/s for `x % y` in L2, 44.9 against 3.4 for `pmod` past
   L3). As 10.2 item 3 warns, this lead overstates E's lead over a scalar remainder in `long`s,
   which is not built.
2. **E leads D by 1.3 times at every rung at 256 bits: held.** E's rate is about 5 times D's at
   all six rungs (47.1 against 9.2 for `x % y` in L2, 44.9 against 9.3 for `pmod` past L3).

Neither margin is close to 1.3 times, so no re-run is needed. Still **not measured**: the
laptop's three widths, 512 bits among them (2.3); the Arm runner, which was not dispatched; and
the 64-bit case (2.2), which the spike does not price.

**Against the row path** (10.1, `DECIMAL(38, 10)`, no nulls). This comparison is indicative only.
The spike is a hand loop over slot columns, and the baseline is a whole query over Arrow-cached
rows. At 256 bits, E's 21.2 to 22.3 ns a row is a small fraction of the row path's 791.2 (`%`)
and 988.0 (`pmod`). At 128 bits, E's fallback is no faster than the row path: 915.2 to 916.4
against 771.3 for `%`, and 975.2 to 978.7 against 973.4 for `pmod`. Both gaps are under 1.3
times, and the `pmod` pair is level, so this says only that E shows no win there, not that it
loses; a fused build would measure it.

**Verdict, on the runner:**
- **At 256 bits, E.** It is the fastest form by a wide margin. B is 11 to 13 times slower, which
  is not close to the winner, so under 7.3 the scalar remainder in `long`s stays unbuilt and (c)
  stays as read.
- **At 128 bits on x86, no vector form.** E and D are the allocating fallback there, and B is
  fastest, 3 to 4 times faster than E.

A 512-bit reading is still owed before 577b fixes its form there. It must come from a host
with a 512-bit datapath, since the laptop double-pumps 512-bit operations on 256-bit units.
Nothing here suggests it would overturn E, but it is not read. The aarch64 column at two long
lanes, where the pair runs natively (`VARKA-359.md` 2.6), is not read either.

**For the next step of row 577.**
- 577b builds E for the 128-bit dividend at 256 bits, and at 512 unless a 512-bit host
  overturns it, after 359a, 360 and 481, with the exactness argument (10.2 item 1) proved in
  SMT as 3.1 asks.
- At two long lanes on x86, the remainder is a projection, so `VARKA-359.md` 2.6 lets it fuse
  and measure it against the row path. Built as E, it shows no win over the row path (indicative
  only). So 577b either declines it there with this measurement, or fuses it with B or a scalar
  remainder in `long`s. That choice is the owner's (7.4).
- At two long lanes on aarch64 no form is read; 577b runs the spike on the Arm runner before
  it picks one there.
- Section 4's predictions for 577a and 577b are registered from these figures before either is
  built.
- 577a, the long-lane case, is unaffected and still waits on row 30.

### 10.4 Two ideas for two long lanes on x86 (question 7.4)

*11 November 2026, on master `749280d9e7d`. A laptop reading only: directional, see "How it was
read". The runners' reading is owed, and the dispatches are at the end of this section.*

**The cause, checked.** JDK 25's `x86.ad` refuses `VectorLoadShuffle` and `VectorRearrange` at
two lanes (lines 1726-1733), and `VectorLoadMask`, `VectorMaskCast` and `VectorStoreMask` (lines
1734-1744), so every rearrange, compare, blend and mask op of a two-lane `LongVector` is the
Java fallback. Lane-wise arithmetic, logic, shifts by a vector count, the 64-bit `mul`, the
double `div`, the bit reinterpretations, `withLane`, `lane` and an or-reduction all compile at
two lanes (a probe, and the intrinsic check below). `L2D` and `D2L` are refused at every width
without AVX-512DQ (lines 1757-1778), which E already avoids (10.2, item 1). A 128-bit gather of
longs is allowed with AVX2 (`LoadVectorGather` is refused only at 64 bits, line 1816), but the
Java side of the index-map `fromArray` checks the indices with two compares, an `or` of their
masks and an `anyTrue` (`VectorIntrinsics.checkIndex`, lines 74-86), five fallbacks a gather.

**What was built.** `VarkaDecimalRemainderSpikeBenchmark` gains eighteen loops, nine forms for
each of `x % y` and `pmod(x, y)`, beside E, D and B. Each is checked against `BigInteger` on
every row as before; the suite's table now also counts rows whose two low words have the sign
bit set.

- **Idea 1, mask-free: F.** E's arithmetic with every compare, blend and mask replaced by bit
  arithmetic on 0/1 lanes: a select is `x ^ ((x ^ y) & -c)`, the borrow of `x - y` is the top
  bit of `(~x & y) | ((~x | y) & d)` and the carry of `x + y` that of `(x & y) | ((x | y) & ~s)`
  (Hacker's Delight 2-13), `v == 0` is the top bit of `~(v | -v)`, the estimate's shift clamp is
  a sign mask of `63 - right`, and the zero divisors' word is the 0/1 lanes shifted by their
  lane index and or-reduced. Every value is E's, so E's exactness argument holds unchanged.
- **Idea 2, no de-interleave**, each with E's masks and with F: *gathered* (an index-map gather
  from the slots in a heap `long[]`, since `fromMemorySegment` has no index map; stores a lane
  at a time), *inserted* (each lane's word put in by `withLane` from a scalar load of the slot;
  stores a lane at a time), *split* (plain loads and stores over a column already in the split
  layout, all low words then all high words: the kernel alone, its transpose not timed), and
  *transposed* (Arrow's slots transposed 64 rows at a time by scalar moves into a 3 KB split
  block, the kernel over the block, and the result moved back into slots: kernel and transpose
  together, the cost a kernel reading Arrow pays).

**The intrinsic check, per loop** (the same counts for `x % y` and `pmod` unless two are given).
At 256 bits (`UseAVX=2`) and at 512 (`UseAVX=3`) every one of the 24 loops is intrinsified. At
128 bits (`UseAVX=2 -XX:MaxVectorSize=16`):

| Form | Calls left in Java | What they are |
|---|---|---|
| E | 72 / 80 | rearranges, compares, blends, mask ops |
| D | 62 / 70 | the same |
| B | 0 | (scalar) |
| gathered E | 62 / 70 | E's masks, and the gathers' index checks |
| inserted E, split E, transposed E | 42 / 50 | E's masks |
| F | 30 | the 12 rearranges, and the compares, blends and casts inside them |
| gathered F | 20 | the four gathers' index checks, five calls each |
| **inserted F, split F, transposed F** | **0** | |

So neither idea alone removes the fallback, and the two together do, except with a gather.

**How it was read.** The laptop (AMD Ryzen AI 9 HX PRO 370, Zen 5, a 256-bit datapath; OpenJDK
25.0.5.0.3) was shared with other agents' JVMs, at a load average of 3 to 29 over the runs (each
run's load is in the results file), and was not pinned, so every figure here is directional.
Only the first rung (4,096 rows, in L2) was timed (`-Dvarka.remainder.rungs=1`): the runners'
reading found each form's time flat from L2 to past L3. The tables are in
`results/VARKA-577-twolane-laptop.txt`. Two faults of the harness were found and fixed on the way,
and each changes how a reading must be taken:

1. **Arms timed in C1's code.** A loop method reaches C2 at its invocation count, some 5,000
   calls, and an arm running the fallback in level-3 code made too few calls of the rung's rows
   in the 30-second wait: the first 128-bit run timed seven arms at level 3. Each drive now first
   calls the loop 20,000 times over a 64-row table, as the intrinsic check does.
2. **A shared JVM boxes loops the check passed.** With every arm in one JVM, some loops the check
   (which compiles each loop alone) found intrinsified still allocate in their timed compile: at 128
   bits inserted F and transposed F allocated 2,096 bytes a row and ran 194 to 227 ns a row, against
   39 to 41 in a JVM of their own; at 256 bits the same two allocated 732 to 1,421 bytes a row, and
   at 512 the `pmod` of inserted and transposed E 195. Each section now notes the bytes a row each
   arm allocates in compiled code, so a boxed arm shows itself, and the readings below take an arm
   that allocated from a JVM of its own.
   *11 November 2026, one cause read (`results/VARKA-577-twolane-shared-jvm-laptop.txt`):* the
   harness's shared body methods are not needed. What is needed is other vector code in the JVM,
   working through C2's trap budget. Each time C2 inlines a method, it adds that method's
   recorded traps to the compile's total for each reason (`parse1.cpp` 470-483). Past
   `PerMethodTrapLimit` (100), `too_many_traps` (`compile.cpp` 4158-4169) stops speculating on
   that reason for the rest of the compile. An untaken branch is then parsed rather than
   trapped, and a type-profiled call becomes a real call, so the vectors that meet the merge or
   the call are boxed. F inlines some Vector API methods about 200 times each, so a few traps
   that other code left in one of them are enough. Two compiles were read:
   - In the benchmark, the three boxed compiles read (256 bits, after inserted E) crossed the
     `unstable_if` budget early. `VectorOperators$ImplCache.find`, inlined 190 times, carried 3
     traps, against 2 in a clean compile. That gave 105 to 134 decisions without the trap,
     against 32 to 40 in clean compiles. In the boxed compiles whose inlining was printed, 13
     `binaryOp` intrinsics failed and stayed calls.
   - After three plain `add` loops it was the `class_check` budget: 3,214 traps in all, from 4
     each in `opKind`, `opCode`, `ImplCache.find` and `lanewiseTemplate`, which left 148
     virtual calls.

   Inserted F boxes at two lanes too. At 128 bits it boxed after the three `add` loops in 6 of 6
   JVMs (9,712 to 10,440 bytes a row), and beside inserted E alone in 5 of 6 (1,080 or 2,192).
   At 256 bits the figures are 16 of 17 and 7 of 12, and transposed F boxed after the `add`
   loops in 3 of 3. In a JVM of its own, inserted F and transposed F never boxed (0 of 6 each,
   at 256 bits). After the `add` loops, split F and E allocated only 12 bytes a row (3 of 3 and
   2 of 3). Whether a shared run boxes varies from run to run. The trap limit is one path read,
   not all: `-XX:PerMethodTrapLimit=0` gave a lone inserted F 248 bytes a row, and `=100000`
   still left 5 of 6 boxing at about half the bytes. So **inserted and transposed F are not
   safe in a shared JVM**. One arm per JVM would only hide the fault, so the harness is
   unchanged.

**The numbers**, in ns a row (`Per Row(ns)`, from each case's best time), `x % y` / `pmod(x, y)`.
At 128 bits each arm ran in its own JVM; `pmod` ran twice there, and its column is each arm's
lower figure (the allocating arms, the fallbacks and B, moved by up to 4.2 times between the
two runs, the three intrinsified F arms by up to 1.7). At 256 and 512 bits the arms shared one
JVM, and an arm marked * allocated there and is quoted from a JVM of its own:

| Form | 128 bits | 256 bits | 512 bits (double-pumped) |
|---|---|---|---|
| E | 542.1 / 597.4 [fallback] | 18.6 / 20.1 | 10.4 / 9.4 |
| D | 5223.7 / 6648.4 [fallback] | 141.9 / 140.7 | 84.4 / 79.2 |
| B | 156.8 / 173.0 | 160.5 / 185.4 | 154.9 / 163.9 |
| gathered E | 434.7 / 551.4 [fallback] | 21.2 / 22.5 | 14.2 / 14.3 |
| inserted E | 388.5 / 476.6 [fallback] | 24.2 / 24.0 | 15.8 / 15.6 * |
| split E | 393.4 / 396.4 [fallback] | 18.1 / 18.7 | 9.8 / 10.9 |
| transposed E | 382.9 / 397.7 [fallback] | 19.0 / 20.3 | 11.1 / 10.1 * |
| F | 161.0 / 157.1 [fallback] | 21.9 / 23.3 | 10.4 / 10.9 |
| gathered F | 90.4 / 102.3 [fallback] | 24.9 / 24.8 | 14.2 / 14.0 |
| **inserted F** | **40.9 / 39.0** | 24.9 / 27.6 * | 16.3 / 15.8 |
| **split F** (kernel alone) | **37.4 / 38.3** | 19.7 / 19.5 | 10.1 / 9.2 |
| **transposed F** (with its transpose) | **39.7 / 41.4** | 19.4 / 21.2 * | 10.5 / 12.4 |

The two controls run in their own JVMs beside the starred arms read as the shared run did: at
256 bits `x % y` E 17.4 and F 19.8, at 512 bits `pmod` E 9.2 and F 9.1.

**What the numbers say.**

1. **Neither idea alone removes the fallback; together they do.** F with the rearranges, and E
   with any access, still leave 20 to 72 calls in Java. Inserted, split and transposed F leave
   none, allocate nothing in a JVM of their own (the transposed arm's 0.8 bytes a row is its 3 KB
   block a call; after other vector code see item 2 of "How it was read"), and run
   at 37 to 41 ns a row at 128 bits, against 157 to 173 for B and 90 to 6,648 for every form
   with a fallback: about 4 times B's rate and 13 to 16 times E's.
2. **The transpose costs about 2 to 3 ns a row at 128 bits** (transposed F against split F: 39.7
   against 37.4 for `x % y`, 41.4 against 38.3 for `pmod`), and at 256 and 512 bits it is within the
   runs' noise except for the shared run's 512-bit `pmod` (12.4 against 9.2). Kernel and transpose
   together cost under 1.1 times the kernel alone, so a split layout the engine would have to make
   buys almost nothing over reading Arrow's slots, and inserted F, which reads the slots with no
   transpose, is as fast as either at two lanes.
   *11 November 2026:* in a JVM of its own only. After other vector code, inserted F and the
   in-loop transposed F box, and split F with its transpose in methods of their own does not box
   but keeps 12 to 16 bytes a row ("The test of 7.4" below). The transpose's cost holds: 1.4 to
   2.3 ns a row.
3. **The gather does not help.** Its Java-side index check is a mask op, so the gathered arms
   keep a fallback at 128 bits, and at 256 and 512 bits they trail the rearranges by 1.1 to 1.5
   times. A gather also needs the column in a heap array.
4. **At 256 and 512 bits the forms are level on the laptop.** At 256 bits E and F differ by 1.1
   to 1.2 times, E ahead; at 512, E and F are within 1.2 times either way (`x % y` 10.4 and 10.4,
   `pmod` 9.4 and 10.9 shared, 9.2 and 9.1 alone). The owner's expectation, that at 512 bits
   E's native masks, `vpermq` rearranges and AVX-512DQ conversions beat the mask-free forms, is
   not shown here, and not refuted: no ratio reaches 1.3, and the laptop double-pumps 512-bit
   operations on 256-bit units, so its 512-bit timings are directional only and cannot pick the
   512-bit form. The deciding reading needs a full-width AVX-512 host (a Xeon 8573C or 6973P-C,
   or an EPYC 9V45 when one is drawn). Inserted F and gathered forms trail at 512 bits (14 to 16
   ns against 9 to 11): lane inserts and extracts scale with the lane count.

**What it implies for 7.4.** A third answer is open beside the two 7.4 names: at two long lanes
on x86 the remainder fuses as F, reading the slots by lane inserts (or the transposed block),
with no fallback, about 4 times faster than B on the laptop and, indicatively, 19 to 25 times
faster than the row path's 771 and 973 ns a row on the runner (10.1). The runners' reading below
is owed before 577b takes it, and the width decides the access: inserted F at two lanes,
E (or F) with the rearranges at four lanes and up, where inserts cost more than the rearranges
they replace. F's arithmetic is E's value for value, so E's exactness argument and the SMT proof
3.1 owes cover both.
*11 November 2026:* the answer holds only in a JVM where no other vector code has left traps
in the Vector API methods F inlines many times (10.4, item 2). A fused kernel shares
its JVM, so "fuse at two lanes as inserted F" needs a defence against C2's trap budget before
577b takes it, and the owner decides whether that is owed or the answer falls.
*11 November 2026:* tested below; the answer falls, and 7.4 is back to decline or B.

**The test of 7.4** (*11 November 2026*, `results/VARKA-577-twolane-shared-jvm-laptop.txt`, last
section; the laptop at load 0.3 to 2.2, one JVM at a time). The diagnosis above left one form
unexplained. Split F allocated only 12 bytes a row after the `add` loops, where inserted and
transposed F boxed. If the slot access was the fragile part, split F fed by a transpose in its
own method might be the form that survives. A throw-away driver (not committed) ran it after the
same three `add` loops. Its loop copies a block of slots into split low and high blocks by
scalar moves in one non-inlined method, runs `loopRemainderFS` over them, and copies the result
back in another. Each kernel cell was checked against `BigInteger` on every row after timing.
JVMs that allocated, of those run:

| Cell | 128 bits | 256 bits |
|---|---|---|
| split F alone | 0 / 6 | 0 / 6 |
| `add` loops, then split F | 12 / 12 (16 bytes a row) | 12 / 12 (12) |
| `add` loops, then split F with the transpose, blocks of 64 | 12 / 12 (16) | 12 / 12 (12) |
| the same, blocks of 1,024 | 12 / 12 (16) | 12 / 12 (12) |
| `add` loops, then inserted F (the control) | 3 / 3 (2,288 to 10,216) | 3 / 3 (1,460 to 6,700) |

Every kernel cell was exact. The medians at 128 bits were 31.2 ns a row for split F alone, 32.6
after the `add` loops, and 34.2 and 33.9 with the transpose, against 121.5 to 125.2 for B. At 256
bits they were 15.8, 16.2, 18.5 and 18.2. The transpose alone cost 1.4 to 2.3 ns a row.

What survives and what does not:

- **Survives:** split F with its transpose out of line does not box at the 1 to 10 KB scale in
  any JVM. It is exact, at about 3.6 times B's rate at two lanes, and the transpose costs about
  2 ns a row.
- **Does not survive:** allocation-free. After the `add` loops every JVM, at either block size,
  keeps 16 bytes a row at two lanes and 12 at four. That is exactly one `long[L]` a lane-group
  iteration (32 bytes for 2 rows, 48 for 4). JFR puts most of those samples at
  `LongVector.lanewiseTemplate` line 756, the `VectorSupport.binaryOp` call, in compiled
  `loopRemainderFS`. That matches the signature of `VARKA-295.md` 9.6, one `binaryOp` refused
  after a class-check miss path became a real call, but the cause was not confirmed here from a
  compile log.
- **Falls:** inserted F and the in-loop transposed F (the control boxed 3 of 3 at both widths).

So no two-lane form was found that the engine could fuse. A residual of one array an iteration
is what the warm-up's verdict releases on (`VARKA-295.md` 9.5). Its allowance is 4,096 bytes
plus 1 byte a row and a column term (`VarkaAllocationSampler`, `VarkaKernelWarmup`), and split
F's 12 to 16 bytes a row is 12 to 16 times its per-row term. Split F with no transpose keeps the
same residual, and the block size does not change it, so the slot access is not the cause. What
is left points at the shared templates' traps, as for inserted F, at a smaller dose. 7.4 is back to
decline at two lanes, or B behind an option. F stays as measured, for the case where a defence
against the shared-template traps lands.

**Can emitted kernels reach this size?** Yes, and they already have. Split F's `x % y` body has
199 Vector API call sites by `VarkaEmittedClass.vectorCallSites`' count: 189 in the arithmetic,
4 loads, 2 stores, 2 for the zero-divisor word and 2 before the loop. `pmod` has 213. Its
compile inlines `VectorOperators$ImplCache.find` about 200 times. The emitter's
`CALL_SITE_BUDGET` is 93 sites a group method, but it bounds C1's register need, not C2's trap
totals. It also splits only groups of more than `HEAVY_GROUP_OUTPUTS` (6) outputs, and a narrow
group of heavy outputs stands over it, up to `FUSED_CEILING`'s 400 ops. More to the point,
`VARKA-295.md` 9.6 found this fault in emitted loop methods, built with the call-site budget in
force (VARKA-209, 29 September; that method's own count was not read). A released `loopDense0`
passed the class-check limit with `VectorOperators.opKind` inlined 170 times,
holding 4 to 5 traps from other kernels' compiles. Its one surviving array an iteration is the
residual split F shows here. So the fragility does not appear at a size only F reaches. It
appears in kernels the emitter already builds, once other vector code has trapped in the shared
templates, and E at 256 bits shows the same 12 bytes a row after the `add` loops (2 of 3, item 2
above). Row 295's bimodal steady state (9.8) is not this fault. It followed the core set in
both arms, and no allocation was read there. Whether the 295 warm-up's template pre-warming
(#827, the preferred species only) protects a two-lane kernel is untested.

**For other two-lane pair forms** (row 596's comparison as a value, the choices, negation and
`abs` of `VARKA-359.md` 2.6). Every pair operator that is a compare, a blend or a mask op has a
mask-free form on 0/1 lanes built from the same three formulas (borrow, carry, select), and the
pair's load and store have the insert and lane-store forms, both of which compile at two lanes.
So the two-lane decline 2.6 accepts for the pair may be avoidable by an emitter that lowers the
pair's compares and selects this way at two lanes. This spike shows it for the remainder only;
for a comparison as a value, whose output is a 0/1 lane, it is **[I]**, untested.

**The runners' reading, prepared and not run.** Once a pull request exists, on vecbricks/varka,
256 and 128 bits on the EPYC 7763 (full ladder, every arm in one JVM as before; read each
section's `allocated` notes first, since a boxed arm there is the shared-JVM fault, not the
form):

    C=org.apache.spark.sql.VarkaDecimalRemainderSpikeBenchmark
    opts="-XX:CompileCommand=dontinline,$C::loop* -XX:CompileCommand=inline,$C::body*"
    gh workflow run benchmark.yml --repo vecbricks/varka --ref master -f pr=N \
      -f class='*.VarkaDecimalRemainderSpikeBenchmark' -f jdk=25 -f scala=2.13 \
      -f num-splits=1 -f create-commit=false -f generate-tpcds=false \
      -f expected-cpu="AMD EPYC 7763" -f extra-java-options="$opts"
    gh workflow run benchmark.yml --repo vecbricks/varka --ref master -f pr=N \
      -f class='*.VarkaDecimalRemainderSpikeBenchmark' -f jdk=25 -f scala=2.13 \
      -f num-splits=1 -f create-commit=false -f generate-tpcds=false \
      -f expected-cpu="AMD EPYC 7763" -f extra-java-options="-XX:MaxVectorSize=16 $opts"

The EPYC 7763 is AVX2 only, so the 512-bit reading needs another host, as above. The results
replace the two committed `-runner-epyc7763` files, whose arm set is the old six.

