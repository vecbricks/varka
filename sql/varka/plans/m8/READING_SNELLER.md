# Reading for milestone 8: what Sneller's AVX-512 posts offer

Read on 16 October 2026 at the owner's request, starting from "64-bit Integer Division with
AVX-512" (sneller.ai/blog/avx512-int-div/, Petr Kobalicek, 30 May 2023) and the other posts under
the site's AVX-512 tag. A search of `SKILLS.md`, the plans and the lessons found no earlier mention
of Sneller. **[W]** means the claim comes from the post as a page-to-text summary, with numbers as
the summary gave them; the posts' assembly listings and charts were not seen, and no code was run.
**[I]** means inferred.

## 1. The division post

A signed 64-bit divide by a runtime divisor, 8 lanes, about 25 instructions **[W]**. It converts
the dividend to double rounding down and the divisor rounding up, forms `1.0 / b` rounding down
(the one `vdivpd`), takes `first = trunc(a * recip)`, computes the remainder `a - first * b` in
integers (`vpmullq`), repeats once on that remainder, and corrects by one where the final
remainder is at least `b`. Directed rounding keeps every estimate at or below the true quotient,
so the correction is one-sided. The post calls the argument informal. On a Zen4 machine, one
billion signed values took 370 ms against 1275 ms for scalar `idiv` (about 3.5x), and 358 ms at 16
lanes **[W]**. Constant divisors, zero divisors and unsigned or modulo forms are not built.

**Why it does not transfer as written [I].** The Vector API has no per-operation rounding mode, so
the one-sided argument is not available: round-to-nearest needs a correction in both directions
and a new error bound. There is no unsigned 64-bit conversion, only `D2L`. The embedded-rounding
conversions it uses are AVX-512, which the EPYC 7763 runners lack. And the record proves 64-bit
rules by SMT over integers, which a floating-point error bound is not (it would be an interval
lemma and sampling near the boundaries, as `m8/PLAN.md` 2.7.1 does for math).

**What the record already holds.** `skills/vector-api-and-width.md` ("A reciprocal multiply is not
a division") states the double route's bound on the dividend, `|a| < 2^52` for `trunc(a * 1/b)`
and `2^53` for `trunc(a / b)`, and `m10/SCOPE.md` names `div`, `mod` and `pmod` by a column as
having no magic. Sneller's method is the same idea carried past that bound by a second iteration
on the integer remainder, which is the part the record does not have.

## 2. The other posts

* **64-bit integers to strings** (31 March 2023): the value is split into chunks of at most 8
  decimal digits by reciprocal multiplication, then into digits by multiply-high magics (3518437209
  with shift 45 for 10000, 5243 with shift 3 for 100, 6554 for 10 in 16-bit lanes), and the length
  comes from `vplzcntq` on byte-swapped chunks **[W]**. The input is shifted right by 8 bits first
  because 10^16 has 16 trailing zero bits. It is string work and outside this milestone; the
  chunking and the shift are an idea for a later `DECIMAL`-to-string row, not for this one.
* **A SQL VM in AVX-512 assembly, branchless code, and terabytes of JSON per second**: validity as
  per-lane predicates that propagate through operators, both arms of a conditional computed under
  complementary masks, and compaction of survivors with a mask-driven compress **[W]**. Varka
  already does each of these with `VectorMask`, and none of the three posts reports a number that
  bears on a milestone 8 row.
* **Regular expressions, ILIKE, fuzzy search**: not read. They are string work for milestone 9.

## 3. What is worth taking

1. **One candidate for row 30**: a second iteration on the integer remainder to take a `BIGINT`
   column divisor past the `2^52` bound of the double route, with round-to-nearest estimates and a
   correction in both directions, measured against the scalar loop and against declining the batch.
   The check was made: the emitter has no node that divides by a column at all (`ConstDivide`
   and `BoundedDivide` only, and the census lists `div`, `%`, `mod` and `pmod` as not fused
   today), so there is no decline to remove. Row 30 builds the family, and `PLAN.md` 2.9 now
   states the column divisor it left out: the double route behind a per-batch bound, with this
   second iteration a measured option if batches leave the bound.
2. **Nothing else.** Decimal division is out of the milestone and row 363's divisors are constants,
   which a magic multiply already handles.

Not read: the posts' assembly listings and charts, and Sneller's source.
