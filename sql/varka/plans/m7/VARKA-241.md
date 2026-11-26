# VARKA-241: The long-lane division forms, proven in floating-point theory

## 1. Where this came from

Row 241 of `m7/PLAN.md`, item 58 step 2 of `m10/SCOPE.md`: the long lane's two constant-division
forms proven at row 166's regions and at 2^52 - 1, with the solver asked for the exact bound
rather than handed one, starting with a spike that records the solve time. VARKA-240 built the
tooling and proved the int lane's multiply-high; its 9.6 left this row Bitwuzla as the first
solver to try, since the forms divide in doubles and no integer encoding states a rounding.

`ConstDivide` at the long lane (`VarkaVectorIR.java`) carries a dividend bound its caller must
prove, at most `EXACT_DIVIDEND_BOUND` = 2^52, and the emitter picks one of two forms by the host
(`VarkaDivisionLowering.java`):

* **the conversion form**, `D2L(L2D(v) / d)` in `DoubleVector` lanes, the default;
* **the magic form**, taken where `L2D` and `D2L` do not intrinsify (`-XX:UseAVX=2`): the
  magnitude's bits ORed into 2^52's and read back as a double, divided, floored by the 2^52
  rounding trick and a masked step down, the bits masked back out, and the sign put back.

What stands behind them today is a model and samples. `verify_double_division.py` derives the
conversion form's 2^53 from the error bound; row 166's suite case drives both emitted kernels over
nine points of each of three regions per divisor; the long-lane fuzzer stops at 2^46. Every
production divisor is a `TIME` compiler's: `NANOS_PER_MICROS` (1000), `NANOS_PER_MILLIS` (10^6),
`NANOS_PER_SECOND` (10^9), a minute (6 * 10^10) and an hour (3.6 * 10^12) in nanoseconds, and 60
in `remainderOfSixty` (`VarkaTimeCompiler.java`). Their dividends are nanoseconds of day or a
quotient of them, under 2^47, but the record's contract is 2^52, and that is what is proven: a
later caller may state any bound up to it.

## 2. The admission check, done

Run on 10 October 2026 on the laptop, with VARKA-240's pinned solvers: Z3 5.1.0, cvc5 1.4.1 and
Bitwuzla 0.9.1 (its static release build, checksum as in `VARKA-240.md` 2).

### 2.1 Floating-point theory does not decide this proof

The statement for one divisor - for every `v` with `|v| < 2^52`, what the form's lanes compute is
Java's `v / d` - in SMT-LIB's floating-point theory over bit-vectors (`QF_BVFP`), each operation the
theory's own: `to_fp` for `L2D` and for the reinterpretation, `fp.div`, `fp.add`, `fp.sub`,
`fp.gt`, `fp.to_sbv` for `D2L`, the bits read back by a declared bit-vector equal to the double,
and Java's `/` as `bvsdiv`.

| form, divisor | Bitwuzla | cvc5 | Z3 |
| :--- | ---: | ---: | ---: |
| conversion, 3.6 * 10^12 | 7 s | 32 s | 162 s |
| conversion, 60 | 36 s | timeout | timeout |
| magic, 3.6 * 10^12 | 8 s | 60 s | 223 s |
| magic, 60 | 145 s | timeout | timeout |
| conversion, 1000, alone on the machine | 487 s | - | - |
| magic, 1000, alone on the machine | 736 s | - | - |
| conversion, 10^9, alone on the machine | past 25 min | - | - |

The first four rows ran four at a time with a five-minute cap. Bitwuzla is the only solver that
finishes everywhere it finishes at all, and its time does not fall with the divisor: the middle
divisors are the hard ones. Three ways of making it cheaper did not:

* **Slicing the dividend by binade**, 105 checks a form and divisor, both signs: for 60 the
  slowest check took 10.5 s and the sum was six minutes, but for 10^9 one slice alone took 139 s
  and the 96 that finished summed to 13 minutes. Abandoned part way.
* **Bitwuzla's options**: abstraction off made 60 slower (57 s and 193 s against 36 s and 145 s);
  Kissat is not compiled into the release build.
* **No divider circuit at all**: the quotient declared as a double and pinned by IEEE 754's
  definition of rounding, every shift a constant and every product by a constant, and Java's `/`
  replaced by `|v - r * d| < |d|` with the dividend's sign. Past ten minutes for 1000, as before.

The statement asks a CDCL solver to equate two circuits of multiplication by a constant and
comparison over 52-bit values, which is the wall VARKA-240 met with the multiply-high (its 2.1):
encoding the division differently does not remove it, because the arithmetic is still there.

### 2.2 Integers decide it, as they did for VARKA-240

The same division step in linear integer arithmetic, one check per binade of the quotient: `q =
M * 2^-k` with `2^52 <= M < 2^53`, `2^-k` a constant in each check, rounding to nearest stated by
the neighbours' midpoints, `D2L` as `r * 2^k <= M < (r + 1) * 2^k`. For 1000, 60, 3.6 * 10^12 and
10^9 at the 2^52 bound, 99 checks each: 0.02 s under Z3 and 0.12 s under cvc5, every one `unsat`.
It is not vacuous: at `v = 1234567891` exactly one binade has a model, whose quotient is
`1234567`; past the bound, at `2^55`, two binades find a counterexample.

Both forms over the integer prelude (section 3.1), five divisors, 614 checks: 19 s under Z3 and
6 s under cvc5, all `unsat`. Planted faults, each run over all 614: the floor's step down removed,
156 counterexamples; the sign flip inverted, 47; `D2L` rounding a whole quotient down by one, 53.
The magic form's mask narrowed to 51 bits found none, and should not: every quotient is under
`2^52 / 2 = 2^51`, so a 51-bit mask computes the same thing, and section 5 plants a narrower one.

### 2.3 The conversion form's exact bound

Asked for the first wrong dividend above 2^53 by bisection over Bitwuzla, both signs, each
divisor - which is cheap, being a satisfiable search over a window of a few multiples - the answer
was one less than the first multiple of `|d|` above 2^53, at every divisor and both signs:
`2^53 + 27` for 60, `2^53 + 7` for 1000, `2^53 + 259007` for 10^6, and `9007199999999999` for 10^9
and for a minute and an hour of nanoseconds, all three of which divide `9007200000000000`. The reason: every
divisor in use is a multiple of four, so its multiples are multiples of four, and the odd dividend
just below one lies halfway between two doubles (they are two apart above 2^53) and rounds to the
one whose significand is even, the multiple, whose quotient is one too large. Below that dividend
no rounding of `L2D` crosses a multiple.

## 3. The design

### 3.1 Doubles in the integer prelude

`java.smt2` gains a section of doubles, written by hand like the rest and held to the JVM by
`java_check.smt2`. A positive normal double is `M * 2^E`; since `2^E` for an unknown `E` is not
linear, nothing returns a double: a proof names the binade, passing `2^E` as two constants.

* `jdouble.rne a b M sn sd`: `M * sn / sd` is the exact positive `a / b` rounded to nearest, ties
  to even (JLS 4.2.4, IEEE 754 4.3.1), by the midpoints to its neighbours, the lower one a quarter
  step nearer at the bottom of a binade. It serves `L2D` (`b = 1`), the division (`b = |d|`) and the
  magic form's sum (`b = 2^k`).
* `jdouble.d2l.mag`: `D2L`'s magnitude, the floor (JLS 5.1.3), below 2^63.
* `jdouble.bits`, `jdouble.fromBits.M`, `jdouble.fromBits.E`: the binary64 layout.
* `jlong.and.low`, `x & (2^k - 1)` as `x mod 2^k`, and `jlong.or.disjoint`, `x | c` as `x + c`
  where the bits are disjoint, which the check using it asserts.

Signs are outside the definitions: IEEE 754 6.3 makes a quotient's sign the exclusive or of the
operands', and rounding to nearest is symmetric, so a form is stated on magnitudes with the sign put
back as Java puts it back.

### 3.2 `long_divide.smt2`

Rendered by `VarkaProofFiles` from the code, for the seven divisors of section 1 (the six in use
and -60, which takes the sign branch the others do not), the constants `0x4330000000000000` and
`2^52 - 1` read from `VarkaDivisionLowering`, and the bound from `ConstDivide`:

1. **Two lemmas**, checked once: an integer `0 < n <= 2^53` rounds to itself, binade by binade;
   and the magic form's OR is disjoint below 2^52, so the bits read back are `2^52 + a`. The checks
   after them write such values without their rounding, citing the lemma.
2. **The conversion form exact for `0 < |v| < 2^53`**: one check per binade of the quotient, and
   one that the binades listed cover every quotient of that range, without which a check could pass
   by leaving a quotient out.
3. **Its exact bound**: exact for `2^53 <= |v| < B`, where `L2D` rounds, and wrong at `v = B` and
   `v = -B` (`sat`), B being one less than the first multiple of `|d|` above 2^53 (section 2.3). The
   rendering computes B by that rule; the solver confirms it both ways.
4. **The magic form exact for `0 < |v| < 2^52`**, per binade with its coverage check: the sum
   `q + 2^52` rounded into 2^52's binade or onto 2^53, the rest exact by the lemmas, and the claim
   including that every intermediate stays in the range the lemmas cover.

Rendering refuses if `EXACT_DIVIDEND_BOUND` or either constant moves, since the statement is
written for them.

### 3.3 What is deliberately unchanged

`dev/varka_prove.sh`, the solvers and CI: the new file is integers like the others, so it runs
under Z3 in the linters' job and under both in the nightly, with no third solver. Bitwuzla goes on
being pinned only in `VARKA-240.md`. `EXACT_DIVIDEND_BOUND` stays 2^52: the magic form binds it,
and which form emits is the host's choice, made after the tree is built. The conversion form's
wider bound is a finding for `SCOPE_STANDARD_MODE.md` and `BoundedDivide`, not a code change. The
calendar's double forms (`ChronoDivide`, int lane, a range per site) stay with
`verify_double_division.py`; they are bounded lowerings per site, row 242's kind of statement. The
emitted bytecode stays covered by row 166's suite case: the proof is of the instructions the emitter
is written to emit, restated, as VARKA-240's was.

### 3.4 Registered op counts

None; no emitted code changes.

## 4. Files

| file | what |
|---|---|
| `sql/varka/proofs/java.smt2` | the doubles section |
| `sql/varka/proofs/java_check.smt2` | six more checks, rendered |
| `sql/varka/proofs/long_divide.smt2` | the two forms, rendered |
| `sql/varka/proofs/README.md` | the new file and section |
| `VarkaProofFiles.java` | `longDivide()`, the doubles' JVM checks, the long divisors from `DateTimeConstants` |
| `VarkaDivisionLowering.java` | `TWO_52_BITS` and `MANTISSA_52` package-private, for the rendering |
| `VarkaEmitterDivisionSuite.scala` | row 166's case cites the proof |
| `m7/PLAN.md`, `m10/SCOPE.md`, `skills/testing-and-debugging.md` | row 241, item 58's revisit, the lesson |

## 5. Tests, and what each is for

* `VarkaProofFilesSuite`: the committed files are the rendering, so a divisor or a constant that
  moves in the code moves the proof, or the suite fails.
* `dev/varka_prove.sh --solver both`: every check of the three files meets its expectation under
  each solver.
* `java_check.smt2`'s new checks hold each double definition to the JVM in both directions: Java's
  double satisfies it and its two neighbours do not. The rounding points include the ties of `L2D`
  above 2^53 and of the magic form's sum, where the tie rule decides.
* Planted faults, each confirmed caught by one of the files: the tie rule inverted; the floor's
  step down removed; the sign flip inverted for a negative divisor; `D2L` rounding the wrong way; the
  mask narrowed below the quotient's width; the bound rule off by one.

## 6. The measurement

`dev/varka_prove.sh` timed under each solver, on the laptop and in the linters' job. No benchmark
moves.

### 6.1 Predictions, registered before the run

1. **The linters' step stays under a minute on the CI runner**: the laptop solved 614 checks in
   19 s under Z3 (section 2.2), and the file adds two divisors and the bound's checks.
2. **Z3 and cvc5 meet every expectation**, and so agree, on every check of the three files.
3. **Every planted fault in section 5 fails a check**, and the tie rule's fault fails only
   `java_check.smt2`: below the bounds no tie moves a truncated quotient, so the exactness checks
   cannot see it, which is why the prelude has its own check.

## 7. Risks

1. **The prelude restates IEEE rounding, and a restatement can be wrong.** That is what
   `java_check.smt2` is for, in both directions and at the ties; the planted tie fault tests it.
2. **A binade list that misses a quotient makes its checks pass vacuously.** Each list has a
   coverage check, and the rendering widens each by one binade for a quotient that rounds up onto
   the next power of two.
3. **The lemmas carry the exact steps.** Each use is inside the range its lemma covers, and the
   magic form's claim includes that range, so a step outside it fails the check rather than
   passing on a wrong substitution.

## 8. Sequencing

One commit: the plan, the prelude's doubles, their JVM checks, `long_divide.smt2`, the citations,
section 9, item 58 and the skill, and row 241. The admission check changed the design under the
plan's feet (section 2), so the plan was rewritten before any of it was committed.

## 9. Outcome, 10 October 2026

### 9.1 What was built

The doubles in `java.smt2`, six more checks in `java_check.smt2` (30 in all), and
`long_divide.smt2`, 867 checks over the seven divisors: the two lemmas, and per divisor the
conversion form per binade with its coverage check, its exact bound both ways at both signs, and
the magic form per binade with its coverage check. `dev/varka_prove.sh --solver both` holds:

| file | checks | Z3 | cvc5 |
| :--- | ---: | ---: | ---: |
| `int_mulhi_divide.smt2` | 35 | 0.37 s | 0.23 s |
| `java_check.smt2` | 30 | 0.02 s | 0.09 s |
| `long_divide.smt2` | 867 | 28 s | 8.06 s |

The runner, the solvers and CI did not change: the new file is integers like the others. Every
check finishes inside a one-second limit under Z3 on the laptop, a tenth of the runner's ten
seconds, so a runner twice as slow does not turn one into `unknown`; what such a runner does to
the job's total is prediction 1's question.

### 9.2 The planted faults

Each planted in a copy and run under cvc5, then the prelude's three again under both solvers after
the corrections below:

| fault | `java_check.smt2` | `long_divide.smt2` |
| :--- | :--- | :--- |
| the tie rule inverted | fails | fails |
| `D2L` admitting the quotient one below at an exact quotient | fails (after the correction) | fails |
| the midpoint at the bottom of a binade taken as elsewhere | fails (after the correction) | holds |
| the floor's step down removed | - | fails |
| the sign flip inverted for -60 | - | fails |
| the mask narrowed to 40 bits | - | fails |
| the bound one too far for 60 | - | fails |

Two of them first passed `java_check.smt2`, and each pointed at a gap in it, now closed. `D2L`'s
check refused only the integer above Java's answer, so a definition admitting the one below went
through; it refuses both now. And the rounding checks refused a double's neighbours only inside
its binade, so a wrong midpoint at a binade's bottom - which only a value just below a power of two
reaches, and there it admits the power as well as its true predecessor - went through every file;
`rounded` now refuses `Math.nextUp` and `Math.nextDown` each in its own binade, and two `L2D` points
sit at that edge, `2^54 - 2` and the tie `2^54 - 1`.

### 9.3 The predictions scored

1. **Missed** (scored from #699's CI, run 38025914635). The "Varka proofs" step took 66 seconds:
   `long_divide.smt2` 59 s under Z3 on the runner against 28 s on the laptop, 2.1 times, the
   ratio VARKA-240 measured, and the other two files and the install the rest. Item 58's minute
   for the whole step is past by six seconds, and the step has no margin left for row 242.
   Section 9.5 answers it.
2. **Holds.** Z3 and cvc5 meet every expectation on every check of the three files.
3. **Holds in part.** Every fault fails a check, but the tie rule's fails `long_divide.smt2` as
   well, at exactly the fourteen bound witnesses: section 2.3's reason for the bound is a tie,
   the odd dividend `B` halfway between two doubles going to the even one, so with ties inverted
   `L2D` rounds `B` down and the form is right there. Below the bounds no tie moves a quotient, as
   predicted; the bound is where one does. The midpoint fault, which the prediction did not name,
   fails only `java_check.smt2`, which is the case the prediction was about.

### 9.4 What this leaves

* **The calendar's double forms** (`ChronoDivide`'s `DOUBLE_RECIP` and `DOUBLE_DIV`, a range per
  site), with row 242: the same prelude states them.
* **The emitted bytecode**, as for VARKA-240: row 166's suite case holds the kernels to the
  arithmetic this proves; item 90 is the model that would prove the bytecode itself.

### 9.5 The miss answered: `long_divide.smt2` under cvc5 in the linters' job

Prediction 1's miss leaves two ways back inside the minute: raise the budget, which row 242 would
then push past again, or run the file under the solver that proves it fastest. cvc5 takes 8 s on
the laptop against Z3's 28 s. On the other two files the solvers are within a fraction of a second
of each other, so those stay with Z3, and the job still runs both solvers. Decided 10 October
2026: `dev/varka_prove.sh --lint` runs each file under Z3 unless the script's `CVC5_IN_LINT` names
it, and that list holds `long_divide.smt2`. A new proof file runs under Z3 with no change to the
workflow, and goes on the list only if Z3 is the slow one. The step installs both solvers and runs
the self-test under both, since the job now depends on cvc5's verdicts, whose quoted echoes the
reader of verdicts strips. The nightly still runs every file under both. On the laptop the lint run
takes 8.5 s and installing both solvers from nothing 4 s.

**Prediction, registered before the run:** the "Varka proofs" step takes under 30 seconds on the
runner. The estimate is cvc5's 8 s doubled, as VARKA-240 measured the runner, plus the cvc5 download
and its self-test.

**Holds** (scored from #701's CI, run 38032625580): the step took 26 seconds, installing both
solvers, self-testing both and running `--lint`, against 66 under Z3 alone.
