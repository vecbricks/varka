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
   run the Java fallback and B is the fastest form, 3 to 4 times faster than E. E's fallback is
   no faster than the row path's whole query (indicative only). Two answers are open. One: the
   remainder declines at 128 bits on x86 under `VARKA-359.md` 2.6. Two: it fuses there with B,
   or with a scalar remainder in `long`s. 7.3's rule adds a scalar arm only if B comes close to
   the winner; it did not foresee a width at which B *is* the winner.

## 8. Done when

- the four names fuse, or decline with a measured reason, for every decimal shape the type sweep
  lists, with Spark's answers in the three modes;
- the spike's verdict and the predictions scored are in section 10;
- the census cells for `%`, `mod`, `pmod` and `try_mod` read `dec` fused or name the measured
  decline.

## 9. Open points for the lead

1. The division form (2.3) is open until the spike.
   *10 November 2026:* on the runner, E at 256 bits (10.3). The laptop's 512-bit column is still
   owed, and the 128-bit column on x86 is question 7.4.
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
of the sixteen fuses, so the two arms tie. At `DECIMAL(38, 10)` without nulls the row path costs
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
and 988.0 (`pmod`). At 128 bits, E's fallback is not faster than the row path: 915.2 to 916.4
against 771.3 for `%`, and 975.2 to 978.7 against 973.4 for `pmod`.

**Verdict, on the runner:**
- **At 256 bits, E.** It is the fastest form by a wide margin. B is 11 to 13 times slower, which
  is not close to the winner, so under 7.3 the scalar remainder in `long`s stays unbuilt and (c)
  stays as read.
- **At 128 bits on x86, no vector form.** E and D are the allocating fallback there, and B is
  fastest, 3 to 4 times faster than E.

The laptop's 512-bit column is still owed before 577b fixes its form there. Nothing here
suggests it would overturn E, but it is not read.

**For the next step of row 577.**
- 577b builds E for the 128-bit dividend at 256 bits, and at 512 unless the laptop overturns
  it, after 359a, 360 and 481, with the exactness argument (10.2 item 1) proved in SMT as 3.1
  asks.
- At two long lanes on x86, the remainder is a projection, so `VARKA-359.md` 2.6 lets it fuse
  and measure it against the row path. Built as E, it would not beat the row path (indicative
  only). So 577b either declines it there with this measurement, or fuses it with B or a scalar
  remainder in `long`s. That choice is the owner's (7.4).
- 577a, the long-lane case, is unaffected and still waits on row 30.
