# The reading notes traced to the plans

*Written on 27 October 2026 at the owner's request: did anything worth having from the reading
get dropped on the way to the plans?* This file takes every concrete recommendation of fourteen
reading notes - the eleven `READING_*.md` notes of this folder, `m9/READING_LAYOUTS.md`,
`m7/READING.md` and `m7/READING_POLARS.md` - and says where it landed. Documents only: nothing was
built or run, and no recommendation was judged on its merits here.

**What counts as a landing.** A row of this plan's table (by number), a section of this plan or a
task plan (`VARKA-<n>.md`) of this folder, an item of `m9/SCOPE.md`, a lesson under
`sql/varka/skills/`, or an explicit rejection with a reason. A rejection in the note itself (its
"not worth taking" section) counts; so does this plan's section 9. For the two milestone 7 notes
a row of `m7/PLAN.md` counts too, since that plan was theirs.

**Statuses.** *landed*: the recommendation, or a stronger form of it, is in one of those places.
*partly*: the rule landed but a named part (a fixture, a citation, a correction) did not.
*rejected*: refused with a reason. *dropped*: searched for and found nowhere. Each search ran
`grep -riE` over `sql/varka/plans/m8`, `sql/varka/plans/m9/SCOPE.md`, `sql/varka/plans/m7`,
`sql/varka/skills`, `SKILLS.md`, `sql/varka/proofs` and `dev`, the reading notes left out; the
terms are quoted with each dropped item in section 2.

A note's facts (what an engine does) are not listed, only what it says to take, change or test.
The item column is the note's own section or item number.

## 1. Each note

### 1.1 `READING_ARROW_RS.md`

| item | recommendation | where it landed | status |
| :--- | :--- | :--- | :--- |
| 3.1 | Check the whole 256-bit product against `10^p` first | 2.8, the 363 bullet | landed |
| 3.2 | Rounding and `(38, 18)` multiply fixtures | row 363 done-when | landed |
| 3.2 | Cast fixtures: `1.050` to `1.1`, the all-nines carry | none | dropped |
| 3.3 | Divisor table `10^k +- 1`, `5^k +- 1`, `k` to 38 | row 363 done-when | landed |
| 3.4 | Carry-corner values as test inputs | row 362 done-when | landed |
| 3.5 | Two table forms for 359's `10^p` check | `VARKA-347.md` (64-bit load) | partly |
| 3.6 | Static "infallible" rescale test for 364 | none | dropped |
| 3.6 | Float limits (`2^53`, `2^24`, 22, 10) for 346, 366 | 2.2, 2.7, row 366 | landed |
| 2 | Result type from the node, never the session | 2.8, the 363 bullet | landed |
| 2 | Double to decimal: a guard, else shortest digits | 2.7, row 346 (own bound) | landed |
| 4 | The arithmetic kernels as such | note 4; this plan 9 | rejected |
| 4 | A sentinel, then a null pass | note 4 | rejected |
| 4 | `abs`-based `checked_mul` | note 4 | rejected |

### 1.2 `READING_DECIMAL_ENGINES.md`

| item | recommendation | where it landed | status |
| :--- | :--- | :--- | :--- |
| 2.1 | Velox's leading-zero test as a batch guard | row 362, an option arm | landed |
| 2.2 | An SMT line: `<= 38` against Velox's `< 38` | row 362 | landed |
| 2.3 | The `10^p` table compare, two sides | row 359 | landed |
| 2.4 | DuckDB's overflow-free `HALF_UP` | row 363 | landed |
| 2.5 | Strip the widening casts under a multiply | row 361 | landed |
| 2.6 | A random `BigInteger` cross-check of the kernel | row 362 done-when | landed |
| 3 | Machine-overflow style instead of `10^p` | note 3 | rejected |
| 3 | A 256-bit schoolbook or library multiply | note 3 | rejected |
| 3 | Long division by a power of ten | note 3; 2.8 | rejected |
| 3 | Rescale up with an overflow flag | note 3; row 360's guard | rejected |
| 4 | Prove the carry network, do not sample it | 2.8, "The proofs" | landed |
| 4 | Test the tie in both directions | row 363 fixtures | landed |

### 1.3 `READING_HIGHWAY.md`

| item | recommendation | where it landed | status |
| :--- | :--- | :--- | :--- |
| 1 | The bound in steps, not a bit distance | 2.7.1 | landed |
| 1 | Signed zero tested apart from the bound | 2.7.1, the exact-value table | landed |
| 1 | One bound per host, not one per function | 2.7.1, row 345 | landed |
| 1 | `atan2`'s absolute tolerance as a foil for 345 | 2.7.1 (the rule only) | partly |
| 2.1 | `pow(1, NaN)`, `pow(-1, inf)`: C99 against Java | row 358, the exact table | landed |
| 2.2 | A NaN compare and a zero blend for float to int | `VARKA-343.md` cells | landed |
| 2.3 | Saturating and wrapping narrowing are two forms | `casts.tsv`, `VARKA-343.md` | landed |
| 2.4 | `BIGINT` to `FLOAT` by round-to-odd | row 343 (own form, SMT) | landed |
| 2.5 | Mask `b == 0` before an integer divide | row 30 | landed |
| 3.1 | Signed high word by two ANDs and two subtracts | row 361 | landed |
| 3.2 | An 8-bit multiply is not one AVX2 instruction | row 329 (arms measured) | landed |
| 3.3 | `intdiv` as a cross-check and edge list for 363 | none | dropped |
| 3.4 | Small-integer divide through `float` | row 30 | landed |
| 3.5 | The signed 128-bit compare is the pair's own | row 359 | landed |
| 3.6 | IFMA limbs: one line under 362's measurement | none | dropped |
| 3.7 | Data bits under null slots for 331 | 2.5, row 331 | landed |
| 4 | `Fast*` kernels, dispatch, coefficients | note 4 | rejected |

### 1.4 `READING_JDK.md`

| item | recommendation | where it landed | status |
| :--- | :--- | :--- | :--- |
| 2.1 | The 636-ulp figure has no source; search | 2.7.1, row 367 | landed |
| 2.2 | Spark calls `StrictMath` for the exp group | 2.7.1 | landed |
| 2.3 | `cbrt` is host dependent | 2.7.1 | landed |
| 2.4 | Key the binding by species width too | 2.7.1 | landed |
| 2.5 | SLEEF binds no `TANH` | row 357 | landed |
| 2.6 | DEBUG proves the symbol, not the call | row 345 | landed |
| 2.7 | Masked math runs every lane; blend first | row 345 | landed |
| 2.8 | Intel's `pow` and fdlibm's switching regions | row 367 | landed |
| 2.9 | The hyperbolics are compositions | 2.7.1, row 357 | landed |
| 3.1 | The JDK's vector test as a foil for 345 | 2.7.1 (the rule only) | partly |
| 3.2 | `floatValue`, never `(float) doubleValue()` | 2.2, row 366 | landed |
| 3.3 | Schubfach for the shortest digits | 2.7, row 346; m9 item 97 | landed |
| 3.3 | A bounded probe of the tie test's 2-digit floor | none | dropped |
| 3.4 | fdlibm read for item 98, costed without FMA | m9 items 36 and 98 | landed |
| 4 | Stub assembly, `FDBigInteger`, `scale < 0` | note 4 | rejected |

### 1.5 `READING_LIBM.md`

| item | recommendation | where it landed | status |
| :--- | :--- | :--- | :--- |
| 6.1 | A true-value column (MPFR; CORE-MATH) for 367 | row 367; this plan 9 | landed |
| 6.2 | glibc's `ULPDIFF` as a third foil | 2.7.1 (the rule only) | partly |
| 6.3 | Switching regions as fixed vectors | row 367 | landed |
| 6.4 | Item 98's shape: non-FMA arms, table, licence | m9 item 98 (FMA only) | partly |
| 5 | No source states a misrounding rate | this plan 9 | landed |
| 7 | FEXPA, gathers, ifunc, the float routines | note 7 | rejected |

### 1.6 `READING_M8_PAPERS.md`

| item | recommendation | where it landed | status |
| :--- | :--- | :--- | :--- |
| 1 | Monniaux and Pain for a `BIGINT` column divisor | row 30, 2.9 | landed |
| 1 | Emulate round-to-nearest as its own lemma | row 30, 2.9 | landed |
| 1 | Measure on AVX2 against the scalar loop | row 30 | landed |
| 1 | Test at the paper's corner cases | row 30 done-when | landed |
| 2 | Didier's truncated multiply for 363 | note 2 | rejected |
| 3 | Edamatsu's reduced radix | note 3 | rejected |

### 1.7 `READING_PYTORCH.md`

| item | recommendation | where it landed | status |
| :--- | :--- | :--- | :--- |
| 2.1 | SLEEF's tier beside each measured bound | row 345 | landed |
| 2.2 | The exponent-field `exp` as item 98's citation | none | dropped |
| 2.3 | "Rounding hides error" as a note under 358 | none | dropped |
| 2.4 | The tolerance foil, one sentence in 345 | 2.7.1 (the rule only) | partly |
| 2.5 | Narrow and 64-bit multiplies are not free | rows 329, 362, 363 measure | landed |
| 3 | Casts, vector masks, the rest of the tree | note 3 | rejected |

### 1.8 `READING_RAPIDS.md`

| item | recommendation | where it landed | status |
| :--- | :--- | :--- | :--- |
| 2.1 | Run against the fork's `Decimal.scala`; pin it | 2.8, row 363 | landed |
| 3.1 | The compatibility triples and the 38-nines edge | row 363 done-when | landed |
| 3.1 | The float-arm vectors (`9.95` at scale 1, two more) | none | dropped |
| 3.1 | The opposite-sign and scale 25 and 10 overflows | none | dropped |
| 3.2 | The legacy rule kept as a named vector | 2.8 | landed |
| 3.3 | The remainder-doubling hazard as a test | none | dropped |
| 3.4 | NaN, infinity, out-of-bound float to decimal | 2.7, `casts.tsv` | landed |
| 4 | The algorithms, the parser, the row index | note 4 | rejected |

### 1.9 `READING_SLEEF.md`

| item | recommendation | where it landed | status |
| :--- | :--- | :--- | :--- |
| 6.1 | ULP against the exact value | row 367's MPFR column | landed |
| 6.1 | The random-lane filler (lane independence) | none | dropped |
| 6.2 | A digest across ISAs with software FMA | `skills/vector-api-and-width.md` | landed |
| 6.3 | The `ad` list and C99 `pow` cases as the table seed | 2.7.1 (`pow` cases) | partly |
| 6.4 | `xfmod` as 346's second oracle | row 346 | landed |
| 6.5 | Op counts; item 98 says which `log` | m9 item 98, 2.7.1 | landed |
| 4 | The `fmod` loop cut to one pass | row 346 | landed |
| 4 | `rint` as `(d + c) - c` | row 344 | landed |
| 7 | dd towers, `getexp`, gencoef, quad | note 7 | rejected |

### 1.10 `READING_SNELLER.md`

| item | recommendation | where it landed | status |
| :--- | :--- | :--- | :--- |
| 3.1 | A second iteration for a column divisor | 2.9: superseded | rejected |
| 2 | Integer-to-string chunking for decimal to string | none | dropped |
| 2 | Predicates, both arms, compaction | note 2: Varka has them | rejected |

### 1.11 `READING_0X80.md`

| item | recommendation | where it landed | status |
| :--- | :--- | :--- | :--- |
| 1.1 | Monniaux and Pain's proof for row 30 | row 30, 2.9 | landed |
| 1.2 | `TINYINT`, `SMALLINT` divide in the `float` lane | row 30, 2.9 | landed |
| 1.3 | Split-and-add as a named failing case | `VARKA-343.md` | landed |
| 2 | Floor by a mask built from the exponent | none | dropped |
| 2 | The `2^52` add-and-subtract with its limits | 2.7, row 344 | landed |
| 2 | `pow` by repeated square roots | none | dropped |
| 2 | `vpternlog` for three-valued logic (331) | none | dropped |
| 2 | Denormals in the floating benchmarks' data | none | dropped |
| 3 | Digit count, abs-diff, popcount, strings | note 3 | rejected |

### 1.12 `m9/READING_LAYOUTS.md`

| item | recommendation | where it landed | status |
| :--- | :--- | :--- | :--- |
| 4 | Item 7: FastLanes FOR, ALP, an encoding tag | m9 item 7 (pointer only) | partly |
| 4 | Item 91: plain VBP, no bit groups, no pruning | m9 item 91 (pointer only) | partly |
| 4 | Item 90: Necula's frame for the spike | m9 item 90 | landed |
| 3 | Bit-vector terms, not a linear integer form | m9 item 90 | landed |
| 4 | Not to attempt: inferred relations, loops | m9 item 90 (pointer) | landed |

### 1.13 `m7/READING.md`

| item | recommendation | where it landed | status |
| :--- | :--- | :--- | :--- |
| 1 | Varka off against on, Comet's error comparison | m7 row 262 | landed |
| 1 | A shrinker over IR subtrees | m7 row 277 | landed |
| 1 | Reproducers saved and replayed | m7 row 262 | landed |
| 1 | Fallback invariance, one output at a time | m7 row 296 | landed |
| 2 | Spark's own suites with Varka on | m7 row 271 | landed |
| 3 | A matrix with reasoned skips; stale markers fail | m7 row 248 | landed |
| 4 | Poisoned nulls, the `0x00` fill, a boundary null | m7 row 138 | landed |
| 5 | Canary, segment provenance, capacity check | m7 row 263 | landed |
| 6 | Error Prone with reasons, in its own job | m7 row 266 | landed |
| 6 | Diff coverage before mutation | m7 row 265 | landed |
| 7 | Pin the first input past a proven range | m7 rows 240 to 245 | landed |
| 8 | Kernels under several JIT configurations | m7 row 272 | landed |
| 8 | Seeded stress flags; one class per species | m7 rows 257 and 246 | landed |
| 9 | A paired run against the merge base | m7 row 256 | landed |
| 9 | Rerun new tests; a fix's test fails on the base | m7 row 255 | landed |
| 11 | Checked prover verdicts, a prelude, constants | m7 rows 240 and 242 | landed |
| 11 | Prove the analyses behind the preconditions | m7 row 281 | landed |
| 11 | Say whether a bound is sufficient or exact | `m7/VARKA-242.md` | landed |
| 11 | Lemire's direct remainder for `floorMod7` | m9 item 82 | landed |
| 11 | NoREC, PQS, TLP, QPG, ddmin and EMI | m7 rows 273 to 280, 262, 138 | landed |
| 11 | Warm kernels to C2 before checking them | m7 row 274 | landed |
| 11 | Mutants the fallback hides; NullAway | m7 rows 275 and 266 | landed |
| 11 | Typed series and a verdict per row | m7 rows 269 and 279 | landed |
| 12 | Work past milestone 7 | m9 item 82 | landed |

### 1.14 `m7/READING_POLARS.md`

The note's section 11 sends the rest to `m9/SCOPE.md` item 89 (b1 to b11 below are its bullets)
and two rows to `m7/PLAN.md`. This milestone took bullet 1 (row 341) and bullet 6 without its
runtime bound (2.8).

| item | recommendation | where it landed | status |
| :--- | :--- | :--- | :--- |
| 1 | Total-order double compares, constant forms | row 341; b1 | landed |
| 1 | `min` ignores NaN, `max` propagates it | row 341; b1 | landed |
| 1 | Dense compaction for lanes not four bytes wide | b8 | landed |
| 1 | Validity from a register, 56-bit windows | b8 | landed |
| 1 | An unbounded `BIGINT` divide by a constant | row 30 (literal magic) | landed |
| 1 | Sums from `+0.0`; an ulp-tolerant sum oracle | b1 (`+0.0` only) | partly |
| 1 | Fast-`pext` deny list by CPU family (item 22) | none | dropped |
| 2 | The tail as a zero-padded copy | b8 | landed |
| 2 | Skip Arrow-java's zero-fill | b8 | landed |
| 2 | Register bitmap writes, a word-wise `countSet` | b8 | landed |
| 2 | Validate UTF-8 once per buffer | b5 | landed |
| 2 | Decide where parent validity is ANDed | none | dropped |
| 3 | Guard scoping through `AND` and `OR` | b11 | landed |
| 3 | Null contract and error capability apart | none | dropped |
| 3 | A may-be-NaN bit for a double `Range` | none | dropped |
| 3 | Never contract a multiply and an add | `VARKA-342.md` | landed |
| 3 | Do not copy the coercion tables | note 3 | rejected |
| 4 | The whole suite at a tiny batch size | m7 row 301 | landed |
| 4 | Pin the fallback; a decline-everything test | b9 | landed |
| 4 | A min-cut for item 75's spill point | b9 | landed |
| 4 | Keep the old engine as a CI variant | b10 | landed |
| 4 | Unset against zero in counters | b9 | landed |
| 4 | A budget of 0 to force the rare path | m7 row 306 | landed |
| 5 | The Parquet reader and the writer checklist | b4 | landed |
| 6 | A 16-byte slot, `LIKE` chains, code points | b5 | landed |
| 6 | Overlapping loads for a short record | b2 | landed |
| 6 | A group key of a hash and 128 bits | b3 | landed |
| 7 | Zone conversion by one threshold array | b7 | landed |
| 7 | Decimal: a runtime bound, skip proven checks | b6; 2.8 (bound refused) | landed |
| 7 | Compare across scales by a clamp | b6; rows 348 and 359 | landed |
| 7 | The decimal test grid, scales 0 to 38 | b6 (no m8 row) | partly |
| 7 | The `pmod` correction | m9 item 33 | landed |
| 7 | `add_months` by a literal, split at compile time | none | dropped |
| 7 | A fixed-shape `strptime` fast path | none | dropped |
| 8 | The variance buffer correction | m9 item 5 | landed |
| 8 | Replicated accumulators, evicting table, keys | b3 | landed |
| 8 | A hash-only probe with rollback | none | dropped |
| 9 | Comments describe the code as it is | m7 row 252; `sql/varka/AGENTS.md` | landed |
| 9 | A blanket second oracle in one pull request | b10 | landed |
| 9 | Pin the JDK patch in jobs with committed results | b10 | landed |
| 9 | Every width and architecture in some CI job | none | dropped |
| 9 | Benchmark output as a PR comment, in place | none | dropped |

## 2. What was dropped, and what to do with it

The dropped items, and the missing part of each partly one. Each entry: the recommendation, the
search terms that found nothing (or only an unrelated hit, named), and a proposal. A proposal
is a new row only for Varka's own work that serves this milestone's done-when list (the new
types, their casts and expressions); "row edit" means a clause in an existing row, which needs no
new number. Items 1 to 9 were added to their rows on 27 October 2026 at the owner's word; the
rest has not been added, and the owner decides.

**Fixtures and tests for this milestone's rows.**

1. Arrow-rs 3.2: the cast fixtures `1.050` to `1.1`, `-1.050` to `-1.1` and the all-nines `(5, 3)`
   to `(3, 0)` carry. Searched: `1\.050`, `all-nines`, `-1\.050`. *Row edit 364*: named tests.
2. Arrow-rs 3.6: a decimal rescale with `p_in + delta <= p_out` (up) or `p_in - delta < p_out`
   (down) cannot overflow, so the check can be skipped at compile time. Searched: `infallible`,
   `p_in`, `compile-time exemption`. *Row edit 364*: the exemption, proven by SMT like its rules.
3. Rapids 3.1: the float-to-decimal vectors (`9.95` at scale 1 gives `10.0`, and the two others of
   the note). Searched: `9\.95`, `3527\.61953125`. *Row edit 346*: named tests of the guard.
4. Rapids 3.1: the opposite-sign product overflow, `0.964808037102759 - 1` at scale 25, and the
   scale 10 add that overflows for both signs. Searched: `5e37`, `0\.964808037102759`,
   `577694938495380589068894346`. *Row edit 360 and 362*: named tests.
5. SLEEF 6.1: put the argument in one random lane and random bits in the others, so a result
   that reads a neighbour lane fails. Searched: `random lane`, `memrand`, `neighbou?r lane`. *Row
   edit 367*: a lane-independence arm of the probe.
6. SLEEF 6.3: the special-value list `ad` (NaN, both infinities, `+-DBL_MAX`, `+-DBL_MIN`,
   `+-denorm_min`, both zeros, `+-1` and their neighbours) as the exact table's seed. Searched:
   `denorm_min`, `DBL_MIN`, `list .?ad`. *Row edit 345*: seed the exact-value table with it.
7. JDK 3.3: a bounded probe that the tie test keeps two digits for subnormals. Searched:
   `two.digit`, `2-digit`, `dk = -1`. *Row edit 346*: one probe case.
8. 0x80 2: benchmark data with or without denormals on purpose. Searched: `denormal`, and
   `subnormal` near `fixture` or `benchmark`. *Row edit 377*: the floating baseline says which.
9. Polars 7: the decimal grid, scales 0, 1, 6, 18, 37 and 38 cubed, against Spark's `Decimal`.
   Searched: `0, 1, 6, 18, 37`, `37 and 38`. *Row edit 362*: a test beside its `BigInteger`
   check (item 89 bullet 6 keeps it for m9 as well).

**Notes that would change no row.**

10. Arrow-rs 3.5: hold `10^p - 1` and its negation, or `10^p` with a strict compare. Searched:
    `10\^p - 1`, `strict compare`; only `VARKA-347.md` has it, for the 64-bit load. *No action*:
    row 359's plan picks one when it is written.
11. Highway 3.3: Highway's `intdiv` as a cross-check of 363's constants, and its edge list
    (`MIN / -1`, divisor 1, `2^16`, `2^32 - 1`). Searched: `intdiv`, `MIN / -1`, `2\^32 - 1`.
    *No action*: 363 divides by the constants `5^k` only, and the proof files check constants.
12. Highway 3.6: IFMA limbs, one line under 362's measurement. Searched: `IFMA`, `MulAdd52`,
    `VPMADD52`. *No action*: no Vector API operation reaches IFMA (`READING_M8_PAPERS.md` 2).
13. The four foils for the step-count definition (Highway `atan2`, JDK 3.1, LIBM 6.2, PyTorch
    2.4). Searched: `foil`, `isWithin1Ulp`, `nearlyEqual`, `ULPDIFF`, `tolerance that`. *No
    action*: 2.7.1 already gives the reasons a multiple of `Math.ulp` fails.
14. PyTorch 2.3: a cheaper kernel is legitimate where a cast rounds the error away. Searched:
    `rounding hides`, `rounds? away`, `per function, not per use`. *No action*: the note itself
    says the contract is per function.
15. Rapids 3.3: a divisor between `2^126` and `10^38` whose remainder doubles past 128 bits.
    Searched: `remainder-doubling`, `doubling`. *No action*: only relevant if 363 divides by a
    constant that is not `5^k`.
16. 0x80 2: `floor` by a mask built from the exponent. Searched: `and-mask built from the
    exponent`, `without FPU`. *No action*: 344 uses the `2^52` construction, and the note did not
    confirm negative inputs.
17. 0x80 2: `pow` by repeated square roots. Searched: `repeated square`, `fractional exponent`.
    *No action*: it cannot give `StrictMath`'s bits, and the note rates it low.
18. 0x80 2: whether C2 fuses three-valued trees into `vpternlog`. Searched: `vpternlog`,
    `ternlog`. *No action*: one `--asm` read when 331's remaining work is measured.
19. Polars 3: a may-be-NaN bit and the NaN table for a double `Range`. Searched: `may-be-NaN`,
    `may be NaN`. *No action*: `VarkaRangeAnalysis` is int-only (2.8); the table is needed when a
    double range exists.
20. Polars 9: every width and architecture path runs in some CI job; benchmark output as a pull
    request comment updated in place. Searched: `every width and architecture`, `architecture
    path`, `in-place`, `hidden marker`. *No action*: process, not this milestone's types.

**For milestone 9's catalogue.**

21. LIBM 4 and 6.4, PyTorch 2.2: item 98's shape. The non-FMA arms of Arm's and glibc's `exp`,
    `log` and `pow` are pure `+ - *` and so one bit pattern on every host; the tail-dropped table
    form; the exponent-field `exp`; and the licence of Arm's routines, to confirm. Searched:
    `tab2`, `Dekker`, `non-FMA`, `Sollya`, `optimized-routines`, `LLVM-exception`,
    `exponent.field`, `Malossi`. *m9 item 98*: a dated note naming them, beside SLEEF's digest
    test, which `skills/vector-api-and-width.md` already records.
22. Layouts 4: item 7's body still names SIMD-BP128's 4-way layout, which FastLanes measures
    behind its interleaved one, and does not carry ALP for doubles or an encoding tag per column
    and batch. Searched: `FastLanes`, `ALP`, `encoding tag` in item 7. *m9 item 7*: a dated note.
23. Layouts 4: item 91's done-when keeps "an early stop on an all-zero running equal word", which
    BitWeaving says does not pay at 11 bits. Searched: `pruning`, `early stop` in item 91. *m9
    item 91*: a dated note that the arm to measure has no early stop.
24. Polars 1: fast `pext` assumed everywhere but AMD and Hygon families 15h to 18h. Searched:
    `Hygon`, `family 0x15`, `blacklist`. *m9 item 22*: a dated note.
25. Polars 3: keep the null contract and the error capability as separate node properties, each
    checked per consumer. Searched: `FunctionFlags`, `ROW_SEPARABLE`, `error capability`. *m9 item
    51*: a dated note.
26. Polars 1 and 8: an ulp-tolerant oracle for double sums, and the hash-only probe with rollback.
    Searched: `Kahan`, `ulp-tolerant`, `hash-only`, `rollback`. *m9 items 4 and 5*: dated notes.
27. Polars 2: before lists or structs, decide where parent validity is ANDed into children, and
    test the postcondition. Searched: `parent validity`, `parent-valid`. *m9*: a line in whichever
    item first scopes nested types; none does today.
28. Polars 7: `add_months` by a literal as `n = 12a + r` with one carry compare. Searched: `12a \+
    r`, `n = 12a`. *m9*: a speed item for the existing `DATE` functions, after a benchmark.
29. Polars 7 and Sneller 2: a fixed-shape `strptime` path, and digit chunking for a decimal to
    string. Searched: `strptime`, `dddd-dd-dd`, `integer.to.string`, `digits by multiply`; the
    one hit, `skills/calendar-algorithms.md`, is a date formatter note. *m9 item 3*: dated notes,
    strings being out of this milestone.

## 3. Open rows whose cause is inferred or not reproduced

Rows of this plan's table that are not **Done** and whose text says the cause is suspected,
unexplained or not yet read. Searched in each open row: `inferred`, `unproven`, `not reproduced`,
`suspect`, `unexplained`, `cause` (not `because`), `probably`, `likely`, `hypothes`; the seven
**Done in part** rows were scanned too, and none names an unproven cause. Not investigated here.

* **Row 329**: "the unexplained 128-bit bimodality of the rates". Missing: a JIT log
  (`PrintCompilation`, deoptimisation) of a slow and a fast run at 128 bits.
* **Row 383**: profile-based loop predication explains the long-lane releases on the laptop, but
  the runner's reproduction is not recorded. Missing: the runner run through the history mode of
  `benchmark.yml`, which needs the owner's go-ahead.
* **Row 404**: the reference arm swings twentyfold with unchanged kernel bytes. Missing: the
  cause read from the JIT's own output, per the row's done-when.
* **Row 406**: "suspected, at medium confidence and with no JIT log yet". Missing: the commit
  named by measurement and the JIT logs of a slow round against a fast one.
* **Row 414**: the fusion was reverted "without finding the cause"; the row narrows it to the
  negation or the origin the fallback evaluates. Missing: the reverted arm rebuilt and the
  `origin.context` the fallback carries traced.

## 4. Counts

| note | landed | partly | rejected | dropped |
| :--- | ---: | ---: | ---: | ---: |
| `READING_ARROW_RS.md` | 7 | 1 | 3 | 2 |
| `READING_DECIMAL_ENGINES.md` | 8 | 0 | 4 | 0 |
| `READING_HIGHWAY.md` | 13 | 1 | 1 | 2 |
| `READING_JDK.md` | 12 | 1 | 1 | 1 |
| `READING_LIBM.md` | 3 | 2 | 1 | 0 |
| `READING_M8_PAPERS.md` | 4 | 0 | 2 | 0 |
| `READING_PYTORCH.md` | 2 | 1 | 1 | 2 |
| `READING_RAPIDS.md` | 4 | 0 | 1 | 3 |
| `READING_SLEEF.md` | 6 | 1 | 1 | 1 |
| `READING_SNELLER.md` | 0 | 0 | 2 | 1 |
| `READING_0X80.md` | 4 | 0 | 1 | 4 |
| `m9/READING_LAYOUTS.md` | 3 | 2 | 0 | 0 |
| `m7/READING.md` | 24 | 0 | 0 | 0 |
| `m7/READING_POLARS.md` | 30 | 2 | 1 | 9 |
| all fourteen | 120 | 11 | 19 | 25 |

## 5. One scope item added

`m9/SCOPE.md` item 124, at the owner's request with this audit: the compile latency of Varka's
emitted kernels, read against Kersten, Leis and Neumann's Umbra paper (VLDB Journal 2021), which
the record had never cited, and tied to row 408's first-query timings. The highest item on master
was 123; 118 and 119 are taken by a branch in flight, so 124 is the next free number.
