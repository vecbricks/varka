# VARKA-281: The analyses that supply the preconditions proven

## 1. Where this came from

Row 281 of `m7/PLAN.md`, from the reading of Alive and Alive2 (`m7/READING.md` 11): Alive trusts
its dataflow predicates (pp. 2-4), and one of its eight wrong rewrites hinged on an overflow
predicate. Varka's predicates are `VarkaRangeAnalysis` and `VarkaValueRange` (VARKA-84), which
decide when a calendar node fuses without a per-lane guard and when a checked int operation
loses its overflow check, and `VarkaTimeCompiler`'s dividend bounds, which are the precondition of
the long lane's division forms. The proofs of VARKA-240, 241 and 242 take those preconditions as
hypotheses - "for every dividend under the bound", "over the site's dividends" - and VARKA-242's
3.6 left "the analyses that supply the ranges" to this row. `m7/PLAN.md` 2.1 asks for an
obligation per rule over symbolic interval ends and Java's wrapping arithmetic, the calendar rules
checked by exhaustion, and a 64-bit rule proven at a reduced width if it times out.

## 2. The admission check, done

Run on 14 October 2026 on the laptop, with VARKA-240's pinned Z3 5.1.0 and cvc5 1.4.1 installed
by `dev/varka_prove.sh --install both`, the spike files run through `dev/varka_prove.sh --solver
both` with `java.smt2` inserted as it inserts it. The spikes are in the session's scratch
directory, not committed; section 3 renders what they established.

### 2.1 The census: what the three analyses claim, and who consumes each answer

**`VarkaValueRange.Bounded`** has eleven operations: `shift`, `hull`, `add`, `sub`, `mul`, `neg`,
`divideBy`, `abs`, `magnitude`, `fitsInt` and `within`. Each claims an interval over mathematical
integers (the lattice is `long` arithmetic that saturates to `UNKNOWN` through `Math.*Exact`, so
its ends are exact or absent): for every member of the operands, the result lies in the interval
returned. Ten are linear over symbolic ends; `mul` is a product of two unknowns.

**`VarkaRangeAnalysis.range`** has 32 arms over the sealed IR. Grouped by what the arm claims:

| arms | the claim | class |
| :--- | :--- | :--- |
| `ColumnRef` under `INT`, `NarrowLane`, `Cond`, the hull nodes under `INT`, every "other kind" | `UNKNOWN` | nothing to prove |
| `ColumnRef` under `DAY` | the column lies in `[CONTRACT_MIN_DAYS, CONTRACT_MAX_DAYS]` | a premise: the project's column contract, 1 January 1 to 31 December 9999 |
| `AddDays`/`SubDays` with a column offset under `ARMED`, `GuardedDay`, `GuardedRange`, `MakeDate`'s year | what leaves the node is inside the guard's bounds | a runtime guard's promise: the emitter's `emitRangeGuard` with the same constants, held by VARKA-279's guard tests |
| `LiteralSlot` (both kinds), `AddDays`/`SubDays` with a literal, `NextDay`, `ThursdayOf`, `Greatest`/`Least`/`IfElse` under `DAY`, `DateDiff`, `IntArith` `ADD`/`SUB`, `IntNeg`, `ConstDivide`, `BoundedDivide` | the lane's value, computed by Java's int operators, is accounted for by the interval | arithmetic over Java's wrapping: linear |
| `IntArith` `MUL` | the same for a wrapped product | nonlinear: a product of two unknowns |
| `AddMonths` (literal and column count), `LastDay`, `TruncDate`, `TruncDateDynamic`, `MakeDate`'s range, `Year`, `Month`, `DayOfMonth`, `Quarter`, `DayOfYear`, `WeekOfYear`, `DayOfWeek`, `DayOfWeekIso`, `WeekDay` | a calendar fact: how far a calendar function moves a day, or the range of a field, over the days the compiler admits | the calendar: eleven of the fourteen are linear over `java.time`'s own closed forms, all fourteen are finite over the admitted range |

**`VarkaTimeCompiler`** states six bounds, every one on the long lane, where the range analysis
answers `UNKNOWN` throughout: `DAY_OF_NANOS` as the dividend bound of `subtractTimes`, `timeDiff`,
`timeTrunc`, the three field extracts and `timeToUnit` (a `TIME` is a count of nanoseconds of
day, so a time, a difference of two times and a quotient of either are under it);
`quotientBound`, `|x| < b` giving `|x / d| < ceil(b / |d|)`, for `remainderOfSixty`'s second
division; and `timeFromUnits`' `GuardedRange` to `[0, (NANOS_PER_DAY - 1) / unit]`, inside which
the wrapping multiply by the unit is exact. All linear per unit; `quotientBound` linear with the
bound symbolic.

So 45 obligations: 11 lattice, 13 arithmetic, 14 calendar, 7 at the `TIME` compiler; 2 nonlinear
(`mul` in the lattice and its use in `IntArith`), the rest linear once stated as section 2.2 says.

**The consumers, and what a too-narrow answer does.**

* `VarkaChronoCompiler.admitCalendar` and `rearm` read the `DAY` answer under `ARMED` and admit
  a calendar consumer - the nine fields, `last_day`, `trunc`, `add_months` - with no per-lane
  check when the interval lies within `[NARROW_MIN_DAYS, NARROW_DECOMPOSE_MAX_DAYS]`
  (`decomposesExactly`). An answer that misses a day its subtree can produce lets a day past
  20161385 biased reach `eraOf`, where `chrono_divide.smt2` proves the magic form wrong, and the
  kernel answers a wrong field with no error.
* `VarkaNodeCompiler.boundedByIntMax` reads the `INT` magnitude for `arithOver` (the `Add`,
  `Subtract` and `Multiply` arms and `VarkaIntervalCompiler.makeInterval`), the `UnaryMinus` arm
  and `VarkaIntervalCompiler.negationMode` (negate, `abs`, the negated month count): a checked
  operation whose operands' magnitudes rule out overflow emits as `WRAP`, and a checked multiply
  that cannot be ruled out declines. A magnitude below the truth emits a wrapping operation where
  Spark raises under ANSI or answers null under `try_`.
* `VarkaRangeAnalysis.dateDiff` reads its operands' `DAY` answers and refuses one that leaves
  int32, since the lane that produced it wrapped on the way in.
* `ConstDivide`'s constructor takes `VarkaTimeCompiler`'s bound as the long lane's precondition:
  `long_divide.smt2` proves the conversion form exact to 2^53 and the magic form to 2^52 and
  wrong past them, and `NarrowLane` stores the low 32 bits of the three field extracts, which the
  "every result is under 86400" argument makes exact. A bound below the truth is a quotient off
  by one, or a truncated store.

### 2.2 The claim that is true, and the one the suite checks

The natural statement - "the interval contains the lane's value" - is false. A literal shift can
carry a `DAY` interval past int32: over the contract `[-719162, 2932896]`, `date_add(d,
2147483647)` has the interval `[2^31 - 719163, 2^31 + 2932895]`, and for `d = 1` the lane wraps
to `-2^31`, outside it. A second shift of `-2147483647` brings the interval back to the contract,
inside int32, and the lanes back to `d`, which is right: the wrap composes. The compiler is sound
because every consumer reads an interval only when it lies within int32 (`decomposesExactly`,
`fitsInt`, a magnitude at most `Integer.MAX_VALUE`), and `VarkaRangeAnalysisSuite`'s property
test never meets the gap because the grammar's `LITERAL_BOUND` is 4000.

The statement that is true, local to each rule, and provable is the *witness* form: **for every
value of each child's interval there is a member of the node's interval congruent, modulo 2^32,
to the lane value the node computes from the children's lane values.** For a `+`, `-` or `*`
the witness is the mathematical result and the congruence is that `jint.wrap` is a ring
homomorphism; for `next_day` it is the child's witness plus one plus the residue the lanes
compute, which is in `[0, 6]` whatever the child's lane value, wrapped or not; for a hull it is
the witness of the branch taken. When the node's interval lies within int32 the witness is the
lane value itself, which is what the consumers use. So the obligations are stated in that form,
with the children's lane values as `jint.wrap` of their witnesses, and each is quantifier-free:
the witness is written out, not asked for.

### 2.3 What the solvers decide, measured

The spike files, one check each unless said otherwise, under both solvers:

| statement | Z3 | cvc5 |
| :--- | ---: | ---: |
| the ten linear lattice rules, the twelve linear arithmetic rules in witness form, `divideBy` by 12 and -12 with symbolic ends, and the `TIME` compiler's seven bounds: 22 checks, with `sat` neighbours | all, 0.09 s | all, under 1 s |
| `divideBy` with the divisor itself symbolic | `unsat`, inside the 0.09 s | unknown at 30 s |
| the four-corner product theorem over the integers, `x * y` within the corners | `unsat`, under 1 s | `unsat`, 0.21 s for the four below together |
| the same with Java's wrap, the product's congruence stated through `jint.mul` and `jint.wrap` | unknown at 20 s | `unsat` |
| the product theorem and the symmetric magnitude over the reals (`ALL`, `Real` sorts) | `unsat`, 0.05 s for three checks | `unsat`, 0.23 s |
| the wrap congruence in Fiat's two layers: the ring identity `jx * jy - mx * my = 2^32 * Q` with `jx = mx - 2^32 q1`, then `jint.wrap(v + 2^32 Q) = jint.wrap(v)`: five checks | `unsat`, 0.02 s | `unsat`, 0.03 s |
| `add_months` by a literal `k` in the compiler's range `[-24576, 24564]` over the admitted years `[-12800, 42400]`, as one check over `java.time`'s closed forms | `unsat`, 12 s | unknown under `QF_NIA`; `unsat` in 14 s under `ALL` |
| the same split by the signs of `k`, `y` and the target year, eight checks in one file | 6.7 s | 9.3 s |
| each of the eight alone | 0.01 to 1.9 s | 0.02 to 2.2 s |
| `make_date`'s range, `last_day`'s 0..30, `trunc` to the year's -365..0 over the closed forms, and `toEpochDay` pinned at five dates | `unsat`, in the 12 s file | `unsat` |

Three things decide the design. The two solvers are complementary on the nonlinear claims - cvc5
proves a wrapped product's congruence that Z3 leaves unknown, Z3 proves a symbolic divisor's
monotonicity that cvc5 leaves unknown - and the nightly needs both, so the product goes over the
reals (a stronger claim: every integer instance is a real one) with the congruence in two layers,
and the divisor is instantiated per divisor in use. The `add_months` claim as one check takes 12 s
on the laptop, past the ten seconds a check has on the runner, and cvc5 cannot decide it under
`QF_NIA`; split by sign every case is under 2 s under either solver, and the file declares `ALL`.
And the lint budget: `dev/varka_prove.sh --lint` takes 9.2 s on the laptop today (cvc5's
`long_divide.smt2` 8.1 s, Z3's five files 1.0 s) and the "Varka proofs" step 26 s on the runner
(VARKA-241 9.5) against the minute the README allows; the new files add about 7 s under Z3 on the
laptop, so about 14 s on the runner, leaving a margin of about 20 s. No file needs the nightly
alone.

### 2.4 What the paper nobody had read adds

Zohar, Irfan, Mann, Niemetz, Noetzli, Preiner, Reynolds, Barrett and Tinelli, "Bit-Precise
Reasoning via Int-Blasting", VMCAI 2022 (`~/Documents/VecMath/markdown/2022-zohar-int-blasting.md`,
read against the PDF for Fig. 1 and Table 2). It is the prelude's method, done inside cvc5:

* **The translation (Fig. 1, p. 7).** A bit-vector of width `k` is an integer in `[0, 2^k)`;
  `+`, `-` and `*` are the integer operation `mod 2^k`; one's complement is `2^k - (x + 1)`;
  unsigned division and remainder are `div` and `mod` guarded by `ite` against a zero divisor,
  since bit-vectors define that case and integers do not; a signed comparison converts with
  `uts_k(x) = 2 * (x mod 2^(k-1)) - x`; shifts multiply or divide by `pow2` of the count, which
  cvc5 eliminates by enumerating the count's `k` cases as `ite` terms (p. 15) - the prelude's
  `pow2` table, with the count masked first as the JLS says. Concatenation and extraction are
  multiplication and `div`/`mod` by powers of two. `java.smt2` is this translation written for
  Java's two widths, with signed values kept signed by wrapping into `[-2^(k-1), 2^(k-1))`
  rather than through `uts`, and with Java's truncating `/` and `%`, which the paper's unsigned
  operators do not cover.
* **Multiplication and overflow.** `(t1 * t2) mod 2^k`, and nothing more: overflow is the
  modulus, not a flag, which is exactly how `jint.mul` is defined. The result is a formula of
  non-linear integer arithmetic, and the paper says so: "int-blasting relies heavily on the
  performance of cvc5's procedure for non-linear integer arithmetic", lemma schemas by
  incremental linearization that "may show unsatisfiability quickly when useful lemmas are
  discovered" and converge slowly on satisfiable problems (p. 17). That is the asymmetry
  section 2.3 measured: cvc5 proves the wrapped product's congruence in a fifth of a second and
  Z3 does not, and the way around it for a two-solver file is the ring identity stated as a
  polynomial, which both normalise, beside the linear wrap lemma.
* **Bitwise operators** are the hard part and the paper's contribution: `&` becomes an
  uninterpreted `&_k` with range lemmas, refined lazily (CEGAR) by a sum-of-bits or a bit-by-bit
  encoding through `bsel_i(x) = (x div 2^i) mod 2`; `|` and `^` reduce to `&` by
  `x | y = x + y - (x & y)` and `x ^ y = (x | y) - (x & y)`. The prelude has no general `&`
  (`jint.and.low` and `jlong.or.disjoint` are the two cases the magic forms use), and the m8
  reading (`reading-proofs-bits-fuzzing.md`) says to keep it so: a sign correction is `ite(x < 0,
  y, 0)`, never a mask. When a real `&` is needed, the paper's `Prop` lemmas (bounds, idempotence,
  symmetry, the all-zeros and all-ones cases, Fig. 3) and the `bsel` encoding are the recipe.
* **The fragment.** Definition 2 and Proposition 1 (p. 5): a formula is *bounded* when every
  variable and every `&` term carries a `0 <= t < 2^k` range constraint, and bounded formulas are
  decidable. The prelude's `jint.in`/`jlong.in` on every declared lane value is that discipline.
  The files here lie in a smaller fragment still: `&`-free, `pow2`-free (every shift count is a
  constant), every product with a constant factor, which is linear integer arithmetic with
  `div`/`mod` by constants - Presburger - except the two product claims, which go to the reals
  and to the two-layer form.
* **What it measured** (Table 2, Fig. 4, pp. 15-18). Int-blasting loses to bit-blasting on
  SMT-LIB's `QF_BV` as a whole but solves 18 benchmarks no bit-blaster solves, 14 of them
  arithmetic-heavy; on equivalence checks of rewrite rules it wins from 128 bits without `&` and
  512 with, and even at 16 bits 78 benchmarks took it under a second where every bit-blaster took
  over ten; the lazy bit-by-bit mode is best. VARKA-240's finding that the multiply-high proof is
  instant over integers and hopeless over bit-vectors at 32 bits is one of those 78, and the m8
  rows that reach 128 and 256 bits are where the paper says the method is strongest.

### 2.5 What the check would have rejected, and what it corrected

A rule whose witness-form obligation neither solver decides in seconds would have gone to the
nightly, or to exhaustion over a bounded width as `m7/PLAN.md` 2.1 allows; none did. The row's
two allowances turn out unneeded: there is no 64-bit rule to prove at a reduced width, because the
analysis answers `UNKNOWN` on every lane wider than int32 and the `TIME` compiler's bounds are
linear and instant; and the calendar rules need no exhaustion to be *proven*, since eleven of the
fourteen are linear over `java.time`'s closed forms with the month count and the year symbolic -
exhaustion over the admitted range stays, as the independent leg the sweep is to the division
proofs. The check also found that the claim `VarkaRangeAnalysisSuite`'s property test states is
not the theorem (section 2.2); the test is right over its grammar, and the lesson goes to the
skills file.

## 3. The design

### 3.1 Four rendered files, one obligation per rule

`VarkaProofFiles` gains four renderers, each writing one file under `sql/varka/proofs/`:

* **`value_range.smt2`** (`ALL`): `VarkaValueRange.Bounded`'s eleven operations. Each rule is
  stated as a function of symbolic ends, its claim as `unsat` over members of the operands, and
  beside it a `sat` check that the rule narrowed by one at either end admits a counterexample, so
  the statement tells the rule from its neighbours. `mul`'s two claims - the four-corner theorem
  and the symmetric magnitude - are over `Real` sorts, which both solvers decide and which imply
  the integer claims. `divideBy` is stated per divisor of `VarkaProofFiles.INT_DIVISORS`, the
  int lane's divisors, with the symbolic-divisor form left out because cvc5 does not decide it.
  One check pins each stated rule to the Java lattice: the ends `Bounded` computes for a set of
  sample intervals, rendered from the code, equal the stated rule's - the `java_check` pattern,
  so a rule restated wrongly fails before any proof rests on it.
* **`range_analysis.smt2`** (`ALL`): the thirteen arithmetic arms of `VarkaRangeAnalysis` in
  the witness form of 2.2, each over symbolic ends with the children's lane values as
  `jint.wrap` of their witnesses: the literal, the literal day shift, `next_day`'s `d + 1 +
  floorMod(k - d, 7)`, the Thursday's `d + 3 - floorMod(d + 3, 7)`, the hull, `datediff` with
  its int32 hypothesis on both operands, `ADD`/`SUB`, `MUL` through the two-layer congruence and
  the real magnitude, `IntNeg` with `Integer.MIN_VALUE` in the domain, `ConstDivide` per divisor
  and `BoundedDivide` per divisor of the same list with the bound symbolic. Two `sat` checks
  record the finding of 2.2 as a theorem's edge: the naive claim fails past int32, and holds when
  the interval lies within it. As in the first file, one check pins each stated rule to
  `VarkaRangeAnalysis.range`'s own answer on a rendered sample of trees.
* **`range_calendar.smt2`** (`ALL`): the calendar rules over `java.time`'s closed forms,
  transcribed from the JDK's own source: `LocalDate.isLeapYear`, `Month.length`,
  `LocalDate.toEpochDay` with both of its sign branches, and `plusMonths`'s `floorDiv` and
  `resolvePreviousValid`. The checks: `toEpochDay` pinned to the JVM at several hundred dates the
  renderer computes with `LocalDate` (every month's first day, 28th and last day, over a set of
  years at the sign, century and era edges and the admitted range's ends); `add_months` by a
  literal in `[MONTH_ARITH_MIN_MONTHS, MONTH_ARITH_MAX_MONTHS]` over the admitted years split by
  the three signs, and the column case as a one-line consequence; `last_day`'s `[0, 30]`, the
  four `trunc` levels' `[-365, 0]`, `make_date`'s range for the years its guard admits, `year`'s
  magnitude from the admitted day range, and `month`, `day`, `quarter` and `day_of_year`; a
  `sat` neighbour for each. The weekday fields and `weekofyear` rest on the sweep of 3.3.
* **`time_bounds.smt2`** (`QF_NIA`): `VarkaTimeCompiler`'s six sites restated with the constants
  from `DateTimeConstants`, as `long_divide.smt2` restates the same compiler's divisors: under the
  `TIME` type's range `[0, NANOS_PER_DAY)`, a difference of two times neither wraps nor reaches
  `DAY_OF_NANOS`, each unit's quotient is under its field's limit and `timeTrunc`'s product does
  not wrap, `quotientBound`'s formula with the bound symbolic for each divisor it is applied to,
  `remainderOfSixty`'s subtraction exact under its bound, and the `timeFromUnits` guard's
  product exact and inside the day for each unit; with the floor in place of the ceiling, and a
  count one past each guard, refuted.

Every constant comes from the code: the calendar's from `VarkaChrono`, the divisors from
`INT_DIVISORS`, the units from `DateTimeConstants`, and the rules' own shifts and field
magnitudes from named constants `VarkaRangeAnalysis` gains (3.2). The files follow the README's
form - the header naming the Java each encodes, `(echo "...: expect ...")` before every
`(check-sat)` between `push` and `pop` - and are checked by `VarkaProofFilesSuite` as the others
are. A file declares `ALL` where it reasons over reals or where cvc5 needs it (VARKA-242 2.2).

### 3.2 The rules' constants, named

`VarkaRangeAnalysis` writes its shifts and magnitudes as literals today: `shift(1, 7)`,
`shift(-3, 3)`, `shift(0, 30)`, `shift(-365, 0)`, `28 * m` and `31 * m`, and the nine field
magnitudes. They become package-private constants of the class (a `Shift(lo, hi)` record for the
day shifts, `MONTH_DAYS_MIN` and `MONTH_DAYS_MAX`, and the field magnitudes beside
`YEAR_FIELD_MAGNITUDE`), which the arms, the renderer and the sweep read, the way VARKA-242 named
`floorMod7`'s folds for its proof. No answer changes: `VarkaRangeAnalysisSuite`'s table tests pin
every value.

### 3.3 The sweep: every calendar rule over the admitted range, against `java.time`

A JUnit 5 suite under `@VarkaSuite`, `VarkaRangeRulesSuite`, with one test per calendar rule: for
every epoch day in `[NARROW_MIN_DAYS, NARROW_DECOMPOSE_MAX_DAYS]`, the 20.2 million days the
compiler admits a calendar node for, `last_day`, the four `trunc` levels, `next_day` for each of
the seven offsets, the Thursday, and the nine fields, computed by `java.time` and
`DateTimeUtils` as the reference evaluator computes them, lie in the interval the named constants
give; and `add_months` for a sample of month counts at the guard's ends and around zero. It is
the leg independent of the closed forms, as the sweep is to the division proofs: a rule
narrowed by one fails here in the JVM whatever the solver says. Cost, stated at the calibration
table's ratio (VARKA-243's suite came in at twice its estimate): the loop is about 20 million
`LocalDate` decodes per rule over a parallel stream, which a probe of one rule will time before
the suite is written; the prediction is in 6.1.

### 3.4 What is deliberately unchanged

* **Every answer of the analysis**, and so every shape admitted or declined, every emitted byte
  and `coverage.json`: the constants are named, not moved. Tightening the `INT` answers is
  `m10/SCOPE.md` item 86.
* **The emitter's guards and their tests** (VARKA-279): the four guard promises are the
  emitter's, held there; this row checks only that the analysis answers the constants the guards
  check, which the shared `VarkaChrono` constants make true by construction.
* **`dev/varka_prove.sh`, CI and the nightly**: the four files are integers and reals under the
  pinned solvers, run where the others run; no file goes to the nightly alone, by 2.3's budget.
* **`VarkaTimeCompiler`**: its bounds are restated, as `long_divide.smt2` restates its divisors,
  not moved into the IR.
* **`VarkaRangeAnalysisSuite`**: stays ScalaTest, untouched; the new tests are the JUnit suite.

### 3.5 Registered op counts

None: nothing emitted changes.

## 4. Files

| file | what |
|---|---|
| `VarkaRangeAnalysis.java` | the shifts and field magnitudes as named constants |
| `VarkaProofFiles.java` | `valueRange()`, `rangeAnalysis()`, `rangeCalendar()`, `timeBounds()` |
| `sql/varka/proofs/{value_range,range_analysis,range_calendar,time_bounds}.smt2` | rendered |
| `VarkaRangeRulesSuite.java` (catalyst tests) | the sweep of 3.3 |
| `sql/varka/proofs/README.md` | the four files, and the witness form |
| `sql/varka/skills/testing-and-debugging.md`, `SKILLS.md` | the lessons of 2.2 and 2.3 |
| `m7/PLAN.md` | row 281 |

## 5. Tests, and what each is for

* `VarkaProofFilesSuite`: the committed files are what the code renders, so a constant that moves
  in `VarkaRangeAnalysis`, `VarkaChrono` or `DateTimeConstants` moves its obligation or fails.
* `dev/varka_prove.sh --solver both`: every check of the four files meets its expectation under
  each solver, run whole and each check alone (VARKA-242's lesson).
* The pin checks inside `value_range.smt2` and `range_analysis.smt2`: a rule restated differently
  from the code fails without a proof being wrong about it.
* `range_calendar.smt2`'s `toEpochDay` pins: the transcription of `java.time` is held to the JVM
  at the sign, century and era edges.
* `VarkaRangeRulesSuite`: the calendar rules in the JVM over the whole admitted range.
* Planted faults, each in a copy, each expected to fail a check: `last_day`'s shift as `[0, 29]`
  (the sweep and the proof); `next_day`'s as `[2, 7]` (the proof's `sat` neighbour becomes the
  claim); `mul`'s claim with a corner dropped; the two-layer congruence with `q1` dropped from the
  identity; `quotientBound` with the floor; a `toEpochDay` with the leap-day correction off by
  one (the pins); `add_months` with `29k` as the lower bound.

## 6. The measurement

No benchmark moves. What is measured: the four files' solve times under both solvers on the
laptop and the "Varka proofs" step on the runner, the sweep's time, and the two solvers'
agreement.

### 6.1 Predictions, registered before the run

1. **The "Varka proofs" step stays under 45 s on the runner**, from 26 s today: the four files
   take about 7 s under Z3 on the laptop (2.3), doubled for the runner.
2. **Z3 and cvc5 meet every expectation on every check of the four files**, whole and alone.
3. **`VarkaRangeRulesSuite` runs in under 20 s in the catalyst test JVM**: about 15 rules times 20
   million `LocalDate` decodes is 300 million decodes, about 30 ns each single-threaded, over the
   parallel stream's cores; stated at twice the arithmetic, per the calibration table.
4. **Every planted fault of section 5 fails a check**, and the `[0, 29]` fault fails both legs.
5. **No rule is unsound.** The admission check's spikes proved the rules they stated; the
   remaining ones are the same shape over the same constants.

## 7. Risks

1. **cvc5 leaves a check unknown in the whole file that holds alone** (VARKA-242 9.2). Each file
   is run whole and check by check under both solvers; the remedies are the README's: `ALL`, one
   wrapping operation per check, named quotients.
2. **The closed forms transcribe `java.time` wrongly.** The pins hold them to the JVM at the
   edges where the branches differ; the sweep holds the rules themselves to `java.time` over the
   whole range, independently of the transcription.
3. **The witness form proves less than a consumer needs.** Section 2.2 is the argument that it
   proves exactly what they use: a consumer reads an interval within int32, where the witness is
   the lane value. The two `sat` checks in `range_analysis.smt2` keep the edge visible.
4. **The budget.** If the runner's step passes 45 s, `range_calendar.smt2` is the file to move,
   and `dev/varka_prove.sh` would gain a nightly-only list; not built unless needed.

## 8. Sequencing

1. This plan, and row 281 marked Planned.
2. The named constants, byte-identical answers; the four renderers and files; the sweep suite;
   the planted faults; the README and the lessons; section 9 and the row.

## 9. Outcome, 14 October 2026

### 9.1 What was built

* **The rules' constants, named.** `VarkaRangeAnalysis` carries `NEXT_DAY_SHIFT`,
  `THURSDAY_SHIFT`, `LAST_DAY_SHIFT`, `TRUNC_SHIFT` (a `Shift` record applied with `over`),
  `MONTH_DAYS_MIN`/`MAX` and the seven field magnitudes beside `YEAR_FIELD_MAGNITUDE`; its arms,
  the renderer and the sweep read them. `VarkaRangeAnalysisSuite` passes unchanged, which is the
  check that no answer moved.
* **Four rendered files, 306 checks**, every one holding under Z3 5.1.0 and cvc5 1.4.1, as whole
  files and check by check:

  | file | checks | claims (`unsat`) | neighbours and vacuity (`sat`) | Z3 | cvc5 |
  | :--- | ---: | ---: | ---: | ---: | ---: |
  | `value_range.smt2` | 68 | 29 | 39 | 0.10 s | 0.77 s |
  | `range_analysis.smt2` | 146 | 53 | 93 | 0.39 s | 0.95 s |
  | `range_calendar.smt2` | 52 | 27 | 25 | 6.8 to 11 s | 12 to 16 s |
  | `time_bounds.smt2` | 40 | 24 | 16 | 0.04 s | 0.1 to 4.6 s |

  The times are the laptop's on the evening of 14 October with two other agents' builds running,
  so they are inflated and noisy: the same `dev/varka_prove.sh --lint` that took 9.2 s before this
  task took 27 s wall at the end of it, of which the four new files are 8.7 s under Z3 and
  `long_divide.smt2` under cvc5 had itself doubled from 8.1 s to 16 s. The nightly's form,
  `--solver both` over all ten files, took 85 s wall, every run holding. `dev/varka_prove.sh`, CI
  and the nightly are unchanged: no file needs a nightly-only list.
* **The pins.** `value_range.smt2` holds each stated lattice rule to `Bounded`'s own answers at
  nine sample intervals (1,377 equations in seven checks), `range_analysis.smt2` each stated arm
  to `VarkaRangeAnalysis.range`'s answers on sample trees, symmetric and asymmetric (57
  equations), and
  `range_calendar.smt2` its transcription of `LocalDate.toEpochDay` and `lengthOfMonth` to the
  JVM at 31 years times twelve months times three days (1,488 equations).
* **`VarkaRangeRulesSuite`**, eight JUnit 5 tests sweeping every calendar rule over the 20.2
  million admitted days against `java.time` and `DateTimeUtils`, 1.5 s in all.
* The README's new section, the three lessons in `skills/testing-and-debugging.md`, and this row.

### 9.2 The planted faults

Each in a copy of a rendered file, run under Z3, except the first, which is in the source and ran
through the suite and the renderer:

| fault | caught by |
| :--- | :--- |
| `LAST_DAY_SHIFT` as `[0, 29]` in `VarkaRangeAnalysis` | the sweep, at the first admitted day (1 March -12800, whose last day is 30 days on); the rendering moves to `[0, 29]` and the solver refutes the claim, `sat` |
| `toEpochDay`'s correction past February off by one, in the transcription | the 1,488 pins, and four claims that rest on the leap day (`add_months` for negative counts, both `make_date` checks) |
| `last_day`'s claim narrowed to `[0, 29]` in the file | the claim, `sat` |
| the ring identity's `Q` without its `q1 my` term | layer A, `sat` |
| `ra.nextday.hi` stated as `hi + 8` | the pin against the analysis's answer, `sat`, and the high-end neighbour, now `unsat`: a stated rule that drifted from the code fails without the claim being wrong |
| `sub`'s low end stated as `a - c` | the claim and the pin against `Bounded.sub` |
| the microsecond count guard one too wide | the `timeFromUnits` claim, `sat` |

### 9.3 The predictions scored

1. **To be scored from the pull request's CI run**: the "Varka proofs" step was 26 s on the runner
   before this task, and the four files add 8.7 s under Z3 on the loaded laptop, about 9 s less
   loaded, so about 18 s on the runner by VARKA-240's ratio - under the 45 s predicted and inside
   the minute, if the laptop's ratio holds. If the step passes the minute, `range_calendar.smt2`
   is the file to move and `dev/varka_prove.sh` gains a nightly-only list (7, risk 4).
2. **Holds.** Both solvers meet every expectation on every check of the four files, whole and
   alone; `range_calendar.smt2`'s checks were also each timed alone (2.3).
3. **Holds, and the estimate was pessimistic by a factor of twelve**: 1.5 s for the eight tests
   against under 20 s predicted. The line is in the calibration table: the estimate priced
   single-threaded decodes and doubled, and the parallel stream and the JIT left little of either.
4. **Holds.** Every planted fault fails a check, and the `[0, 29]` fault fails both legs (9.2).
5. **Missed, by the review rather than the solver.** `arith` answered the sum's interval for
   `SUB`, which is the difference's only over symmetric operands; a `BoundedDivide`'s `[0, q]`
   and an int-lane `GuardedRange`'s bounds are asymmetric, and over two such operands the
   answer missed every negative difference. No compiler path composes such operands today, so
   nothing emitted was wrong, but the rule was, and the plan's own premise - "under `INT` every
   answer is symmetric" - was what hid it from the solver: the obligation proved the symmetric
   theorem the compiler's trees satisfy, not the code's domain. The arm now takes the lattice's
   `sub`, the obligations are stated over two general intervals, a `sat` check shows the old
   answer failing over asymmetric operands and an `unsat` one that it equals the new one over
   symmetric ones, and `VarkaRangeRulesSuite` holds the input that shows it: `[0, 52] - [0, 3]`
   is `[-3, 52]`, not `[0, 55]`. Found by `/code-review high` on the branch (9.6). The other
   findings are in 9.4.

### 9.4 What the plan did not foresee

* **The `INT` answers are not all symmetric**, so the obligations' premise was narrower than
  the code (9.3, prediction 5): `GuardedRange` under `INT` answers its own bounds and
  `BoundedDivide` `[0, (bound - 1) / d]`, both added after VARKA-84 wrote the symmetric
  contract. The arithmetic arms are now proven over two general intervals; `ConstDivide` over a
  general interval needs the hypothesis that the interval lies within int32, where the lane
  value is the witness, and over a symmetric one needs none.

* **The `year` answer's low end is slack by 29,600 years.** The rule's symmetric interval
  `[-42400, 42400]` is exact at the top and the admitted years begin at -12800, so the
  neighbour the renderer writes for every other rule - the low end raised by one - is not
  refuted here; `range_calendar.smt2` records the slack as a holding check instead. It is
  `m10/SCOPE.md` item 86's debt, the symmetric `INT` answers, made visible by a proof.
* **A `sat` neighbour can be the hard check.** `add_months` with `29k` as the lower bound is
  refuted by cvc5 in a third of a second and left unknown by Z3 after 20 s when run alone, though
  Z3 answered it `sat` inside the file: a satisfiable search over the closed forms, the reverse of
  VARKA-242's lesson, where a check held alone and failed in company. The neighbours now name
  their witnesses (31 January 2001 plus one month is 28 February) and decide at once.
* **A file costs about twice the sum of its checks alone**: the eight `add_months` cases take
  4.4 s under Z3 one at a time and 8 to 9 s in one file, pins or no pins, reordered or not. The
  per-check limit is what the runner enforces, and the slowest case is a fifth of it.
* **cvc5 left one hull claim unknown alone and decided it in the file**: `Least`'s witness as an
  `ite` over the wrapped lanes, ten seconds alone, a third of a second in company, while the
  identically shaped `Greatest` decided both ways. Stated per branch taken - the left lane at
  most the right, the right below the left - with no `ite`, all six hull checks decide at once
  under both solvers. VARKA-242's rule stands: run a new file whole and check by check.
* **Z3 and cvc5 divided the nonlinear claims between them** exactly as the admission check found,
  and the reals and the two layers were needed for the files to hold under both; cvc5 proved
  every integer product claim the spike put to it.

### 9.5 What this leaves

* **The step's time on the runner**, scored by the pull request's CI (9.3).
* **The symmetric `INT` answers** (item 86), with the `year` slack now quantified.
* **The four guard promises** - `GuardedDay`, `GuardedRange`, the armed column offset,
  `make_date`'s year - rest on the emitter's guard tests (VARKA-279), as 3.4 said; the analysis
  and the emitter share the constants.
* **The weekday fields and `weekofyear`** are proven over the forms the lanes compute (the
  `floorMod` offsets, the Thursday's day of the year); that each form is `java.time`'s field is
  the sweep's, now through `DateTimeUtils.getDayOfWeek` and `getWeekDay` rather than the forms
  themselves, which the review found tautological.
* **`VarkaTimeCompiler`'s dividend bounds are restated**, as `long_divide.smt2` restates its
  divisors; `quotientBound` moved into `ConstDivide` at the review's suggestion, so that one the
  renderer reads.
* **`VarkaRangeAnalysisSuite`'s property test** still states containment, which is true over its
  grammar's literals and false in general; the lesson is recorded, and a test of the witness form
  over wide literals would be the stronger statement.

### 9.6 The review

`/code-review high` on the branch found seven things, every one verified and changed:

| finding | what changed |
| :--- | :--- |
| `remainderOfSixty`'s obligation asserted `r = x mod 60` as a premise, assuming half of its claim | the equality is in the negated conclusion; a wrong multiply or divide now fails it |
| `arith` answers the sum's interval for `SUB`, sound only over symmetric operands | the lattice's `sub`; the obligations over general intervals; the test and the two checks of 9.3 |
| two of the three weekday sweeps tested hand-rolled `floorMod` forms, in range by construction | `DateTimeUtils.getDayOfWeek` and `getWeekDay`, Spark's definitions through `java.time` |
| `month` had no neighbours, and the weekday fields and `weekofyear` no obligation, while the row said every rule | `month` through `rule`; four obligations over the lanes' forms, with the identity to `java.time` the sweep's |
| `quotientBound` was re-implemented in the renderer, so the pin could not see the compiler drift | `ConstDivide.quotientBound()` in the IR, read by the compiler and the renderer |
| `pinEnds` silently dropped a sample the lattice saturates at | it refuses the sample, so a lost pin is a failed rendering |
| `TIME_UNITS` carried a dead second column beside a parallel name array | a `TimeUnit` record |

The second is the task's one unsound rule, latent, and the review's premise check - "is the
obligation's domain the code's?" - is what the solver could not ask. After the changes the four
files hold whole and check by check under both solvers (9.1), and the three suites pass.

