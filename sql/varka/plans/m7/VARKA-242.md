# VARKA-242: The other bounded lowerings proven

## 1. Where this came from

Row 242 of `m7/PLAN.md`, item 58 step 3 of `m10/SCOPE.md`: the bounded lowerings VARKA-240 and
VARKA-241 left - `floorMod7`'s forms, the decomposition's constants over the covered years, and the
unsigned range compare - proven as met; `sql/varka/AGENTS.md` saying that a new bounded lowering
comes with its proof file; each calendar division site's range and theorem recorded, every quoted
bound labelled sufficient or exact, and the first failing input past it pinned as a test.
`m7/READING.md` 11 names the cases: the round-up magics follow from Granlund and Montgomery's
(4.4), the round-down-plus-carry steps, `floorMod7`'s folds and the leap hash have no theorem,
`NARROW_DECOMPOSE_MAX_DAYS` sits past what the closed form proves, and `YEAR_M`'s 44858 first fails
at 44894. The unsigned compare proven is the leap hash's, the one built, not row 208's. VARKA-241's
9.4 adds the calendar's double forms, `DOUBLE_DIV` and `DOUBLE_RECIP` at each site's range, which
its prelude states.

What stands behind these today is sweeps and comments. `VarkaChronoSuite` sweeps the scalar twin
in `VarkaChrono` against `java.time` over the narrow range, the leap hash over its whole domain, and
`WEEK_M` one past its bound. Each constant's javadoc quotes a bound, some exact, some sufficient,
none saying which. `recipExact` is transcribed from `verify_double_division.py`'s probe. The
`floorMod7` forms are argued in `emitFloorMod7`'s comment.

## 2. The admission check, done

Run on 10 October 2026 on the laptop under the pinned Z3 5.1.0 and cvc5 1.4.1, with `java.smt2`
inserted as `dev/varka_prove.sh` does.

### 2.1 The division sites: milliseconds over integers

Each `ChronoDivide` site is `(v * m) >>> k` in an int lane, with one round-down carry at six of
them. Over integers, with the prelude's wrapping multiply and unsigned shift, each site's
statement - the form is `v / d` over the site's dividends - is one linear check. Five of them
together (`JULIAN_YEAR` with its carry over [0, 584399]; `ERA_NARROW` with its carry over
[0, 20161385], and wrong at 20161386; `YEAR_OF_CENTURY` over [0, 44893], and wrong at 44894) take
32 ms under Z3 and 23 ms under cvc5.

The first failing dividend of each site, scanned in Java over the whole non-negative int range:

| site | divisor | carry | dividends | first wrong | double reciprocal first wrong |
| :--- | ---: | :---: | :--- | ---: | ---: |
| `QUARTER` | 3 | | [0, 14] | 48 | none |
| `CENTURY` | 36524 | yes | [0, 146096] | 584429 | none |
| `YEAR_OF_CENTURY` | 365 | | [0, 36524] | 44894 | none |
| `MONTH` | 153 | | [0, 1827] | 4896 | none |
| `JULIAN_CENTURY` | 146097 | yes | 4x + 3 to 584387 | 2338035 | 9204111 |
| `JULIAN_YEAR` | 1461 | yes | 4x + 3 to 584399 | 1496507 | none |
| `DAY_OF_MONTH` | 2141 | | [0, 65535] | 87780 | none |
| `MONTH_START` | 5 | | [0, 1685] | 5120 | none |
| `MONTH_ARITH` | 12 | | [0, 49151] | 98304 | none |
| `YEAR_OF_ERA_400` | 400 | yes | section 3.1 | 102401 | none |
| `YEAR_OF_ERA_100` | 100 | yes | [0, 399] | 102401 | none |
| `WEEK` | 7 | | [0, 365] | 685 | none |
| `ERA_NARROW` | 146097 | yes | [0, 20161385] | 20161386 | 146097 |

The true division through doubles, `DOUBLE_DIV`, is exact over every non-negative int at every
site. Three things the table shows that the comments do not:

* **The Julian sites need their shape.** Over the plain interval the reciprocal at
  `JULIAN_CENTURY` first fails at 146097, which is 1 mod 4; its dividends are `4 * dayOfEra + 3`,
  where it first fails at 9204111, past the site's range. So `recipExact = true` is true only of
  the shape, and the proof has to state it.
* **`MONTH_ARITH_MAX_MONTHS` is sufficient, not exact.** It is derived from `v * M < 2^31`, but the
  shift is unsigned, so the multiply may run to 2^32: the magic is exact to 98303, twice the 49151
  the guard admits.
* **The inverse direction's `/ 400` is exact to biased year 102400**, a hundred years short of the
  leap hash's 102499. No caller reaches either (section 3.1).

### 2.2 `floorMod7`: the encoding decides cvc5, and the logic does too

The shipped form - two 15-bit folds, `+3` where negative, the exact magic by 7 - stated directly
over the prelude: Z3 proves it in 2850 ms; cvc5 runs past 300 s, and past 200 s again with the
sign split. Over bit-vectors both solvers run past 300 s.

What decides it is a lemma per step. A fold `(x & (2^k - 1)) + (x >>> k)` is stated by the digits
of `x`'s unsigned reading - `u = 2^k * a + b`, `0 <= b < 2^k` - and a lemma shows the prelude's
mask and shift give `b` and `a`; another shows a fold keeps the residue mod 7 and bounds the sum.
Every step is then linear arithmetic with a few small variables. With that, a second finding:
**cvc5's verdicts depend on the declared logic.** The same eighteen checks under
`(set-logic QF_NIA)` leave six unknown after 60 s; under `(set-logic ALL)` every one holds in
66 ms (Z3: 34 ms under either). The mask lemma for negative `x` alone, under `QF_NIA`, ran past
30 s with every non-linear option tried, and holds at once under `QF_LIA` or `ALL`. Z3 rejects
`QF_LIA` for the prelude, whose shifts multiply by an `ite`. So `floor_mod7.smt2` declares `ALL`.

| form | checks | Z3 | cvc5 |
| :--- | ---: | ---: | ---: |
| shipped, magic step stated as a quotient | 18 | 34 ms | 66 ms |
| `DIGIT_SUM`, six folds as one check | 2 | 55 ms | unknown at 10 s |
| `DIGIT_SUM`, one residue lemma per fold | 6 | 16 ms | 15 ms |

The per-fold bounds: 163838, 32770, 574, 77, 15, 8. The second is one below the 32771 the comment
quotes, so that bound is sufficient.

### 2.3 The leap hash over bit-vectors

`((y * LEAP_HASH_M) & LEAP_HASH_MASK) <= LEAP_HASH_MAX`, unsigned, against the Gregorian rule by
`bvurem`: exact over biased years [0, 102499], wrong at 102500, and a signed compare wrong inside
the range. 445 ms under Z3 and 532 ms under cvc5 as `QF_BV`; under `ALL` with the prelude inserted,
246 ms and 558 ms, so the file needs no change to `dev/varka_prove.sh`. A typo in the multiplier
during the spike failed the first check, as it should.

### 2.4 The double forms over integers

VARKA-241's `jdouble.rne` per binade states both forms at the int lane: `DOUBLE_DIV` is
`D2I(I2D(v) / d)`, `DOUBLE_RECIP` is `D2I(I2D(v) * R)` with `R` the double nearest `1 / d`, written
as `M_R * 2^-k`. For four sites and both forms, 139 checks: 69 ms under Z3, 836 ms under cvc5, and
the reciprocal at `ERA_NARROW` refuted, as `recipExact = false` says.

What the check would have rejected: a site whose statement no solver decides in seconds, which
would have moved it to the nightly or out of this row.

## 3. The design

### 3.1 The sites' table, and `chrono_divide.smt2`

`ChronoDivide` gains what a site's proof needs and its javadoc now only quotes: the dividends the
site sees, as a maximum with a stride and residue (4 and 3 at the Julian sites, 1 and 0
elsewhere), and whether the site carries. Each is derived from the constants it follows from
(`ERA_DAYS - 1`, `4 * (ERA_DAYS - 1) + QUAD_DAY_ADD`, `NARROW_DECOMPOSE_MAX_DAYS + NARROW_BIAS`),
not typed in. For `YEAR_OF_ERA_400` the widest biased year the inverse direction can receive is
taken conservatively, `YEAR_FIELD_MAGNITUDE` plus the month arithmetic's reach plus `YEAR_BIAS`,
whether or not the range analysis bounds the decomposed year more tightly, since the proof holds
to 102400 either way. `Divider.carries` refuses a site the table marks uncarried, so the table
cannot say "carried" for a site the emitter emits without the carry and have the proof pass a
form never emitted.

`VarkaProofFiles` renders `chrono_divide.smt2`, under `QF_NIA`. Per site:

* the magic form, with its carry where the table says so, is `v / d` over the site's shape up to
  one below its first wrong dividend, and is not at the first wrong one - the generator scans for
  it, so the solver confirms the scan both ways, as VARKA-241 did for its bound;
* that maximum lies inside it, which is the site's theorem over its range;
* where the site carries, without the carry it is not `v / d` inside the range, so no site is
  marked carried for nothing;
* `DOUBLE_DIV` per quotient binade, with the check that the binades cover the range;
* `DOUBLE_RECIP` per binade where `recipExact` is true, and where it is false, a check that it is
  wrong at a dividend inside the range.

The proof covers each site's arithmetic. That the emitter emits what the scalar twin computes
rests, as today, on `VarkaChronoSuite`'s sweeps and the emitter suites; the proof does not reach
the bytecode, which item 90 would.

### 3.2 `floor_mod7.smt2`

Rendered, under `ALL`, from named constants: `emitFloorMod7`'s literals - the fold masks and
shifts, the sign fixup's 3, the magic 37450 and 18 - become package-private constants of
`VarkaChronoLowering`, and the emitter loads them. All three forms, each for every int: the
Java-operator lemmas (mask and unsigned shift give the digits, for both signs), one residue lemma
per fold with its bound, the sign fixup without wrap, and the last step - the magic quotient for
the shipped form, one masked subtract of 7 over [0, 13] for `DIGIT_SUM`, and the lanewise `/` for
`DIV`. The composition is the file's header, as VARKA-241's lemmas are.

### 3.3 `leap_hash.smt2`

Rendered from `VarkaChrono`'s `LEAP_HASH_M`, `LEAP_HASH_MASK`, `LEAP_HASH_MAX` and
`LEAP_HASH_MAX_BIASED_YEAR`, under `ALL` over bit-vectors: the three checks of 2.3. The header
says that the prelude is inserted and unused.

### 3.4 The prelude

`jint.and.low`, the int counterpart of VARKA-241's `jlong.and.low`, goes into `java.smt2`, and
`java_check.smt2` holds it to the JVM at negative `x` as well, the case cvc5 could not decide.

### 3.5 The labels, the pinned inputs, and the rule

Every bound a `VarkaChrono` or `ChronoDivide` comment quotes says "exact" or "sufficient", and a
sufficient one names where the form first fails. `VarkaChronoSuite` gains one table-driven test:
for each site, the scalar form agrees with `/` one below its first wrong dividend and disagrees at
it, the dividends literal so that a change of constant shows in the diff. `sql/varka/AGENTS.md`
says that a new bounded lowering comes with its proof file under `sql/varka/proofs/`.

### 3.6 What is deliberately unchanged

* **Every emitted byte.** The constants named in 3.2 keep their values; `emitted_bytes.json` is
  byte-identical, which is the proof of that refactor.
* **`MONTH_ARITH_MAX_MONTHS` and every other guard.** The findings of 2.1 are labelled, not acted
  on: widening a guard changes emitted bytes and what declines, a separate decision.
* **The Neri-Schneider month block** (`MONTH_NUM_M`, `DOM_M`'s affine numerator, `MONTH_START_M`):
  identities over 366 days and twelve months that `VarkaChronoSuite` checks at every value of
  their domain, which is a proof over that domain already.
* **The analyses that supply the ranges**, row 281; and item 59's specification, rows 243 to 245.
* **CI.** The three files run where the others do, under Z3 in the linters' job (or as #701's
  `--lint` lists them, if it lands first) and under both solvers in the nightly.

### 3.7 Registered op counts

None move: no emitted byte changes.

## 4. Files

| file | what |
|---|---|
| `VarkaChronoLowering.java` | `ChronoDivide`'s range, stride, residue and carry; `floorMod7`'s named constants; `Divider.carries`'s refusal is in `VarkaDivisionLowering.java` |
| `VarkaChrono.java` | each quoted bound labelled exact or sufficient |
| `VarkaProofFiles.java` | `chronoDivide()`, `floorMod7()`, `leapHash()`, and `jint.and.low`'s JVM checks |
| `sql/varka/proofs/java.smt2` | `jint.and.low` |
| `sql/varka/proofs/{chrono_divide,floor_mod7,leap_hash,java_check}.smt2` | rendered |
| `VarkaChronoSuite.scala` | the first wrong dividend of every site, pinned |
| `sql/varka/proofs/README.md`, `sql/varka/AGENTS.md`, `m7/PLAN.md`, `m10/SCOPE.md`, the testing lessons | the records |

## 5. Tests, and what each is for

* `VarkaProofFilesSuite`: the committed files are what the code renders, so a changed constant
  fails until its proof is re-rendered and re-run.
* `dev/varka_prove.sh --solver both`: every check meets its expectation under both solvers.
* The pinned-dividend test: a change to a site's constants that moves its first failure fails
  here, in the JVM, independently of the solvers.
* `VarkaChronoSuite` and the emitter suites as they are, and `emitted_bytes.json` unchanged.
* Planted faults, each in a copy, each expected to fail a check: a carry removed, a multiplier and
  a shift off by one, a fold mask one bit narrow, the sign fixup 4 for 3, a signed leap compare, a
  stride of 1 at a Julian site, and a wrong `jint.and.low`.

## 6. The measurement

The proofs' solve times under both solvers, on the laptop and from this PR's CI.

### 6.1 Predictions, registered before the run

1. **Z3 and cvc5 meet every expectation** on every check of the three new files.
2. **Each planted fault in section 5 fails a check**, and the solver-confirmed first wrong dividend
   equals the Java scan at every site.
3. **The three files add under three seconds** to a laptop run under either solver.

## 7. Risks

1. **A rendered statement that proves less than it says**, as VARKA-241's two `java_check` gaps
   did. The planted faults are the check; the stride fault in particular, since a statement over a
   wider interval would still pass for `DOUBLE_DIV`.
2. **cvc5's logic sensitivity elsewhere.** A file declared `QF_NIA` that one day meets the same
   wall: the lesson goes into the testing skills, and `ALL` is the first thing to try.
3. **The table drifting from the emitter.** `Divider.carries`' refusal closes one direction; the
   other, a carry the emitter emits that the table omits, makes the proof state a weaker form than
   the emitter's, which is still sound.

## 8. Sequencing

1. This plan.
2. The prelude's `jint.and.low` with its JVM checks; the named constants and the table's fields,
   byte-identical; the three files rendered and proven; the pinned test; the labels.
3. The records, and section 9.

## 9. Outcome, 10 October 2026

### 9.1 What was built

Three rendered files, all holding under Z3 5.1.0 and cvc5 1.4.1, and each of their checks holding
alone as well as in the file:

| file | checks | Z3 | cvc5 |
| :--- | ---: | ---: | ---: |
| `chrono_divide.smt2` | 496 | 0.27 s | 0.65 s |
| `floor_mod7.smt2` | 35 | 0.03 s | 0.06 s |
| `leap_hash.smt2` | 3 | 0.24 s | 0.93 s |

`ChronoDivide` carries each site's range, shape and carry, derived from `VarkaChrono`'s constants;
`YEAR_OF_ERA_400`'s range comes to biased year 59647. `Divider.carries` refuses a site the table
marks uncarried. `emitFloorMod7` loads its constants from `FLOOR_MOD7_FOLDS`, `DIGIT_SUM_FOLDS`,
`FLOOR_MOD7_SIGN_FIX`, `FLOOR_MOD7_M` and `FLOOR_MOD7_K`. `java.smt2` gains `jint.and.low`, held to
the JVM at both signs, and `java_check.smt2` holds `jdouble.rne` to Java's `*` of an int and the
double nearest `1 / d`. Every bound `VarkaChrono` quotes is labelled exact or sufficient.
`VarkaChronoSuite` pins each site's first failing dividend. `sql/varka/AGENTS.md` carries the rule.

### 9.2 What the plan did not foresee

* **cvc5 needed more than `ALL`.** Section 2.2's eighteen checks held, but the file as rendered
  did not: two checks came back unknown, then after a rewrite one other, then another again. Each
  of them held alone. The `+3` lemma and the composition, written with `mod`, failed only after
  the file's earlier checks; the `DIV` form, one check over `jint.div`, failed alone too, though the
  spike had passed it - after other checks. Three cvc5 options (`--simplification=none`,
  `--arith-rewrite-equalities`, `--no-arith-brab`) each made the file hold, and each failed one of
  the earlier variants, so none went into `dev/varka_prove.sh`. What made every check hold, alone
  and in the file, was the encoding: a residue as 7 times a declared integer, never `mod`, in the
  composition; the truncated quotient named, with a lemma that `jint.div` gives it; and one
  wrapping operation per check. Hence 35 checks where section 3.2 counted fewer.
* **A comment's bound was wrong, not merely loose.** `YEAR_M`'s javadoc said 45965 "would be exact
  far further, to 1864135": in a 32-bit lane it first fails at 93440, where the multiply passes
  2^32. Corrected in the comment.
* **A site's theorem is not the decomposition's.** With `YEAR_M` raised by one (planted, 9.3), the
  divide by 365 first fails at 22994, inside its range, and `VarkaChronoSuite`'s sweep of both
  prefix forms over a whole era still passes: the step after it gives a day back when the year
  overshoots and absorbs the error. Only the site's proof and the pinned dividend saw it.

### 9.3 The planted faults

Each in a copy; every one fails, under both solvers where a solver is what catches it.

| fault | caught by |
| :--- | :--- |
| `CENTURY`'s carry removed from its check | the exactness check, `sat` |
| `YEAR_OF_CENTURY`'s multiplier raised by one in its check | the exactness check |
| `ERA_NARROW`'s shift raised by one in its check | the exactness check |
| the stride dropped from `JULIAN_CENTURY`'s reciprocal checks | two binade checks, `sat` at 146097's neighbourhood |
| a fold's mask one bit narrow in a digit lemma | the mask lemma |
| the sign fixup 4 for 3 | the `+3` lemma |
| the leap compare signed | the exactness check |
| `jint.and.low` wrong for negative `x`, in the prelude | `java_check.smt2` and three mask lemmas |
| `CENTURY` marked uncarried in the table (source) | the rendering refuses its range, and `Divider.carries` throws in the emitter suites |
| `YEAR_M` raised by one (source) | the rendering refuses the range (first fails at 22994), and the pinned dividend |
| a 14-bit fold in the shipped form (source) | the fold's residue lemma |

### 9.4 The predictions scored

1. **Holds, for the files as committed.** Z3 and cvc5 meet every expectation on every check of the
   three files. The first rendering of `floor_mod7.smt2` did not under cvc5 (9.2).
2. **Holds.** Every planted fault fails a check or the rendering (9.3), and the solver confirms each
   site's first failing dividend both ways, at the value the Java scan found.
3. **Holds.** The three files take 0.54 s under Z3 and 1.6 s under cvc5 on the laptop.

### 9.5 What this leaves

* **`MONTH_ARITH_MAX_MONTHS` could double**, to the magic's exact bound (2.1): a guard change, with
  emitted bytes and declines moving, so a decision of its own, not taken here.
* **The inverse direction's `/ 400` is exact to biased year 102400**, a hundred years short of the
  leap hash; recorded in `YEAR_CENTURY_M`'s comment. No caller reaches past 59647.
* **The emitted bytecode** stays the scalar twin's by the suites, not by these proofs: item 90.
