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

None yet. Section 10 gets the spike's figures, and the predictions are registered from them
before 577a is built.

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

## 8. Done when

- the four names fuse, or decline with a measured reason, for every decimal shape the type sweep
  lists, with Spark's answers in the three modes;
- the spike's verdict and the predictions scored are in section 10;
- the census cells for `%`, `mod`, `pmod` and `try_mod` read `dec` fused or name the measured
  decline.

## 9. Open points for the lead

1. The division form (2.3) is open until the spike.
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
rows, since one would raise under ANSI. The runner results at 256 and 128 bits are spliced into
the committed `-runner-epyc7763` files, the new sections only, with their provenance.
