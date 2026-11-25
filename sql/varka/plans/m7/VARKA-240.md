# VARKA-240: The int multiply-high division, proven by a solver

## 1. Where this came from

Row 240 of `m7/PLAN.md`, item 58 step 1 of `m10/SCOPE.md` as `m7/READING.md` 11 refined it: the proof
tooling - SMT-LIB files under `sql/varka/proofs/`, run by `dev/varka_prove.sh` in the linters' job -
and its first proof, the int-lane multiply-high division of VARKA-149. That lowering is checked
today by exhaustion: `VarkaEmitterDivisionSuite`'s opt-in sweep runs the form over all 2^32
dividends for nine divisors, thirteen and a half minutes of every nightly (13 minutes 26 seconds in
the nightly of 26 September). The row also asks that the prover's verdicts be checked in turn
(unknown and timeouts fail, sanity queries must be `sat`, Z3 and cvc5 agree in the nightly), that
Java's operators be stated once in a prelude, that the proof's constants be rendered from the code,
and that `signedMagic` assert Granlund and Montgomery's Theorem 5.1 inequality for every divisor.
Open question 1 of `m7/PLAN.md` - which solver gates the lint job - is this task's to answer.

## 2. The admission check, done

Run on 9 October 2026 on the laptop with three solvers, none of which was installed before:

| solver | version | from | licence | sha256 of the download |
| :--- | :--- | :--- | :--- | :--- |
| Z3 | 5.1.0 | PyPI `z3-solver==5.1.0.0` (16 August 2026), which ships the `z3` binary | MIT | - |
| cvc5 | 1.4.1 | GitHub release `cvc5-Linux-x86_64-static.zip` (25 September 2026) | BSD-3-Clause | `2f8efe58fe27ba7bccbb504533f690b9312d69da14192712460e4a19231f02a1` |
| Bitwuzla | 0.9.1 | GitHub release `Bitwuzla-Linux-x86_64-static.zip` (21 May 2026) | MIT | `057f1546ae2068df57beb178f3eeab1678f0e5f0c378787a05b7bb294617c9c6` |

The cvc5 and Bitwuzla wheels on PyPI carry only the Python bindings, no command-line binary, so the
release archives are the install path for both.

### 2.1 Bit-vectors do not decide this proof; integers do

The divisor-12 statement - for every int32 `n`, what the lanes compute equals Java's `n / 12` - in
four encodings, each solver under a five-minute cap:

| encoding | Z3 | cvc5 | Bitwuzla |
| :--- | :--- | :--- | :--- |
| bit-vectors, Java's `/` as `bvsdiv` | timeout | timeout | timeout |
| bit-vectors, Java's `/` by the JLS's definition, 15.17.2 and 15.17.3 (no divider circuit) | timeout | timeout | timeout |
| the same under Z3's `intblast` and `polysat` engines (two-minute cap) | timeout | - | - |
| the same under cvc5's `--solve-bv-as-int=sum` | - | `unsat`, 0.02 s | - |
| integers, Java's wrapping stated with `mod` (section 3.1) | `unsat`, under 0.2 s | `unsat`, under 0.2 s | no integer theory |
| any of them with the multiplier lowered by one | `sat`, 0.05 s | `sat`, 0.05 s | `sat`, 0.01 s |

Bit-blasting turns the claim into the equivalence of two multiplier circuits, which a CDCL solver
does not prove - the wall Alive hit with wide multiplies and divides, worked around there by
narrowing the widths (Alive p. 9), which would prove a different lowering here. In integers every
product has a constant factor, so the statement is linear in the dividend and its quotient, and both
solvers close it at once. Item 58's "bit-vector SMT decides these in seconds" is refuted for this
form; a counterexample, when there is one, every encoding finds in milliseconds.

### 2.2 The integer model, both solvers, every divisor

One file of 41 checks - for each of thirteen divisors (the nine of section 1, 196611, 2^30, 2^31 - 1
and -2^31) the form exact (`unsat`), the multiplier lowered by one refuted (`sat`) and the shift
raised by one refuted (`sat`), and the dividend's domain admitting both of its extremes (`sat`) -
takes 0.33 s under each solver, three runs each, and the two solvers' verdicts are identical line
for line.

### 2.3 Theorem 5.1 does not hold for every multiplier the book derives

Granlund and Montgomery, "Division by Invariant Integers using Multiplication", PLDI 1994, p. 5,
read from the PDF:

> **Theorem 5.1** Suppose m, d, l are integers such that d != 0 and 0 < m * |d| - 2^(N+l-1) <= 2^l.
> Let n be an arbitrary integer such that -2^(N-1) <= n <= 2^(N-1) - 1. Define
> q0 = floor(m * n / 2^(N+l-1)). Then TRUNC(n/d) is q0 if n >= 0 and d > 0, 1 + q0 if n < 0 and
> d > 0, -q0 if n >= 0 and d < 0, and -1 - q0 if n < 0 and d < 0.

That is `emitMulHiDivide` exactly, with N = 32 and `l = s + 1`: the pair `(Mu, 32 + s)` that
`signedMagic` returns satisfies the hypothesis when `0 < Mu * d - 2^(32+s) <= 2^(s+1)`. Checked for
every divisor from 2 to 2^31 - 1 (a scratch program, 56 s on 24 threads): **it fails for 327,741,950
of them, the first 196611**, whose book pair `(0x55550001, 48)` exceeds 2^48 by 131075 against a
bound of 131072. `READING.md`'s "holds for every divisor up to 2^17" is right; the row's "for every
divisor" assumed that the smallest shift Hacker's Delight 10-6 derives always meets the theorem, and
it does not. The book's pair is exact all the same - the integer model proves the 196611 book pair
exact - because the book's loop stops on `2^p > anc * (d - 2^p mod d)`, with `anc` the largest
dividend congruent to `d - 1`, where the theorem bounds the dividend by 2^31. The theorem's
inequality implies the book's, not the reverse: it is sufficient, not necessary.

So the shift is raised until the theorem's inequality holds. That terminates for every divisor,
since the paper's own choice `l = ceil(log2 d)` meets it; checked over every magnitude from 2 to
2^31, the multiplier stays below 2^32 and the shift at most 62. No divisor in use moves - the book's
pairs for 2, 3, 7, 12, 60, 100 and 3600 already meet the theorem - and the first that does is
196611, to `(0xAAAA0001, 49)`. The raise is by one shift for 163,878,613 divisors and by more for
the rest.

### 2.4 An int-lane divisor of `Integer.MIN_VALUE` throws at emit time

`ConstDivide` admits `Integer.MIN_VALUE` at the int lane - it refuses zero, `Long.MIN_VALUE` and, at
that lane, a divisor wider than an int, and the analysis refuses -1 - but `emitMulHiDivide` passes
`(int) Math.abs(-2147483648L)`, which is `Integer.MIN_VALUE` again, to `signedMagic`, which throws.
No compiler path builds that divisor today (the int-lane sites are `VarkaIntervalCompiler`'s 12 and
`VarkaTimeCompiler`'s 60, and the fuzz grammar draws seven small ones), so nothing has met it. The
theorem covers `|d| <= 2^(N-1)`, the book's loop at 2^31 gives `(0x80000001, 62)`, which meets the
theorem with equality at the top, and the integer model proves the form exact with it. So
`signedMagic` takes the magnitude as a long, up to 2^31: every divisor the node admits then has a
derivation and a proof, which is the row's "every divisor".

### 2.5 Solver behaviour the prover has to guard against

* **An error, then a verdict.** Declared `QF_LIA`, Z3 rejected the prelude's multiplying definition
  with an error, dropped the assertion that used it, and answered `sat` - which a sanity check
  expecting `sat` would have taken as a pass. The script fails on any output line that is neither an
  expectation nor a verdict.
* **Incremental mode.** cvc5 refuses `push` without incremental mode, and Z3 answers `(set-option
  :incremental true)` with an error, so the files cannot carry the option and the script passes cvc5
  `--incremental`.
* **Echo.** Z3 prints an `(echo ...)` string bare, cvc5 in quotes.

### 2.6 Which solver gates the lint job (open question 1)

Both fit the minute by two orders of magnitude: 0.33 s for the proof file either way. Z3 installs in
4 s with `pip`, which the lint image's own virtual environment already uses; cvc5 is a 44 MB archive
from GitHub with a checksum to verify. **Z3 gates the lint job, and cvc5 is the nightly's second
opinion**, where the two must agree. Bitwuzla is not a candidate here, having no integer theory; its
strength is floating point, which is row 241's - the long lane's two forms divide in doubles - so
its archive and checksum are recorded above for that spike.

The check would have rejected the row's design if the integer model had timed out too: the proofs
would then have rested on one solver's int-blasting, with nothing to agree with.

## 3. The design

### 3.1 The proofs directory

* **`sql/varka/proofs/java.smt2`, the prelude, written by hand.** Java's `int` and `long` over
  SMT-LIB's integers, each operator defined the way the Java Language Specification defines it and
  citing the section: the two ranges; the wrap, "the low-order bits of the mathematical result"
  (4.2.2, 15.17.1, 15.18.2); `I2L` and `L2I` (5.1.2, 5.1.3); `+`, `-`, `*` and unary `-`; `/` and
  `%` (15.17.2, 15.17.3), `Integer.MIN_VALUE / -1` included; `Math.floorDiv` and `Math.floorMod`;
  `<<`, `>>` and `>>>` with the count masked to five or six bits, as the section on shift operators
  says. The saturating `D2I` and `D2L` need floating-point theory and stay for row 241.
* **`sql/varka/proofs/java_check.smt2`, rendered.** The prelude against the JVM: for each operator,
  one check that its definition gives what Java gives over a set of boundary operands, the expected
  results computed by the JVM while rendering. A definition with the right name and the wrong
  meaning - Alive2's warning (p. 4) - fails here before any proof can rest on it.
* **`sql/varka/proofs/int_mulhi_divide.smt2`, rendered from `signedMagic`.** Its header names the
  Java it encodes (`VarkaDivisionLowering.emitMulHiDivide`, `signedMagic`); a function
  `mulhi.divide` states the lanes' arithmetic once; then, per divisor, the form exact (`unsat`), the
  multiplier lowered by one refuted (`sat`) and the shift raised by one refuted (`sat`), and once,
  the domain admitting `Integer.MIN_VALUE` and `Integer.MAX_VALUE` (`sat`).
* **`sql/varka/proofs/README.md`.** How to run them, what a check looks like, how to add a proof,
  and why integers.

Every check is an `(echo "<what it shows>: expect sat|unsat")` followed by its `(check-sat)` between
`push` and `pop`, so a verdict carries its own expectation and its name. Each proof file opens with
`(set-logic QF_NIA)`, after which the script inserts the prelude, since SMT-LIB has no include.
`QF_NIA` because the prelude's definitions multiply and divide their parameters; every proof applies
them to constants, so what the solvers reason about is linear.

### 3.2 The constants, rendered from the code

`VarkaProofFiles` (Java, catalyst test sources) renders both files: the divisors, `signedMagic`'s
pair for each, and the JVM's results for the prelude's checks. `VarkaProofFilesSuite`
(`SparkFunSuite`) fails when a committed file differs from its rendering and rewrites it under
`VARKA_PROOFS_REGEN=true`, the pattern `VarkaEmitCostSuite` set. The divisor list moves from
`VarkaEmitterDivisionSuite.intDivisors` into `VarkaProofFiles`, and the sweep reads it from there,
so sweep and proof cover the same divisors: the nine in use, 196611 (the first the theorem raises)
and `Integer.MIN_VALUE` (the magnitude 2^31).

### 3.3 `dev/varka_prove.sh`

* `dev/varka_prove.sh` runs every proof under Z3, the linters' gate; `--solver cvc5` or `--solver
  both`, the nightly's, where both must meet every expectation and so agree; file arguments narrow
  the run.
* It fails on a verdict other than the expected one, on `unknown`, on a check that runs past its
  ten-second limit or a run past its overall one, on any output line that is neither an expectation
  nor a verdict, on fewer verdicts than checks, and on a solver it was asked for and cannot find or
  finds at another version than the pinned one.
* `--install z3|cvc5|both` fetches the pinned versions into `target/varka-solvers/` - Z3 by `pip`
  into a virtual environment, cvc5 from its release archive with the checksum of section 2 verified
  - and does nothing when they are already there.
* `--self-test` plants one failure of each kind - a wrong expectation, a solver error followed by a
  verdict (section 2.5), a check that cannot finish (the bit-vector encoding of section 2.1 under a
  one-second limit) and a missing solver - and fails unless the script rejects every one. The
  checker of verdicts is checked in turn.

### 3.4 `signedMagic` asserts Theorem 5.1

The book's loop is unchanged. After it, the theorem's inequality is checked and the shift raised
until it holds; then the two conditions the lanes need are checked - the multiplier below 2^32, so
that `n * Mu` fits a long, and the shift at most 62, inside the long shift's count - and an
`IllegalStateException` is thrown if either fails, which section 2.3 shows it cannot. The argument
becomes a long magnitude, 2 to 2^31, and `emitMulHiDivide` passes `Math.abs(n.divisor())`. The `&
0xFFFFFFFFL` mask goes: the 64-bit `q2 + 1` is the unsigned multiplier already, and the bound check
states what the mask assumed. The Javadoc is rewritten: exactness is now argued from the theorem,
whose hypothesis is checked for every divisor, proven per divisor in use by the solver, and swept by
the opt-in test; "the smallest shift" and "what C2 emits" become true below 196611 only.

### 3.5 CI and the nightly

* **The linters' job** gains a step after the Java linter: install Z3, the self-test, the proofs. It
  is gated on `lint-code` like the code linters, which is true for a `scoped` change (row 225), and
  a change to `sql/varka/proofs/` or `dev/varka_prove.sh` classifies as `scoped`. The `.smt2` files
  carry the ASF header as `;` comments, since `dev/check-license` runs in the same job and they are
  the project's own files, unlike `papers/`.
* **The nightly** gains a `prove` step, `--install both` then `--solver both`, and its sweep step a
  census: an opt-in test that runs `signedMagic` on every magnitude from 2 to 2^31, checks the
  theorem's inequality and the lanes' two conditions independently of the code, and logs how many
  shifts were raised and the first - section 2.3's numbers from a committed test rather than a
  scratch program.

### 3.6 The comparison with VARKA-149's sweep

One nightly cycle on this branch: the sweep step (with the two new divisors and the census) and the
`prove` step on the same commit, with both outcomes and times in section 9. Then one planted fault -
d = 12's multiplier lowered by one in `signedMagic` - must fail the sweep's d = 12 test, fail the
rendering test, and, rendered, fail the proof's exactness check for 12. Then the sweep's opt-in test
cites the proof in its title and comment. The sweep stays in the nightly: it checks Java's own
arithmetic where the proof checks a model of it, and its fifteen minutes are what the agreement
costs.

### 3.7 What is deliberately unchanged

* **Every emitted byte.** No divisor in use changes its pair, so `emitted_bytes.json` is
  byte-identical and VARKA-149's eleven operations stay registered as they are.
* **The long lane's division forms**, row 241, and the other bounded lowerings - `floorMod7`, the
  decomposition's constants, the unsigned compare - row 242, which also adds to
  `sql/varka/AGENTS.md` that a new bounded lowering comes with its proof file.
* **The fuzz grammar's divisor list.** The new divisors join the matrix test, the sweep and the
  proof, not the grammar, whose list feeds the random stream every recorded seed replays.
* **The specification of item 59**, rows 243 to 245; the proofs here are stated against Java's `/`.
* **Granlund and Montgomery in `papers/`.** The paper becomes load-bearing here, but the only copy
  is Granlund's self-archived PDF of an ACM paper, whose terms grant no redistribution; per
  `papers/README.md` the reading notes (`READING.md` 11) and the citation stay, and section 2.3
  quotes the theorem itself.

### 3.8 Registered op counts

None move: the form is the same eleven lane operations for every divisor (VARKA-149 9.2), and a
raised shift changes a constant, not an operation.

## 4. Files

| file | what |
|---|---|
| `VarkaDivisionLowering.java` | `signedMagic` over a long magnitude, Theorem 5.1 asserted, the Javadoc; `emitMulHiDivide`'s call |
| `sql/varka/proofs/java.smt2` | the prelude |
| `sql/varka/proofs/java_check.smt2`, `int_mulhi_divide.smt2` | rendered |
| `sql/varka/proofs/README.md` | the directory's how and why |
| `VarkaProofFiles.java`, `VarkaProofFilesSuite.scala` (catalyst tests) | the renderer and its check |
| `VarkaEmitterDivisionSuite.scala` | the constants, the theorem, the matrix and the census; the sweep reads the shared list, then cites the proof |
| `dev/varka_prove.sh` | the prover |
| `.github/workflows/build_and_test.yml` | the linters' step |
| `dev/varka_nightly.sh` | the `prove` step |
| `sql/varka/skills/testing-and-debugging.md`, `SKILLS.md` | the lesson of sections 2.1 and 2.5 |
| `m7/PLAN.md`, `m10/SCOPE.md` | row 240, open question 1's answer, item 58's correction |

## 5. Tests, and what each is for

* **The constants are the book's below 196611** (`VarkaEmitterDivisionSuite`, extended): 2, 3, 7, 12
  and 100 pinned to Hacker's Delight's pairs as now, 196611 pinned to its raised pair and the book's
  shift beside it, 2^31 to `(0x80000001, 62)`, and 1 and 2^31 + 1 refused. A derivation that drifted
  from the book where the book meets the theorem fails here.
* **Every pair meets the theorem** over 2 to 2^17, the edges and a seeded sample above: the
  inequality restated in `BigInteger` so the test shares no arithmetic with the code.
* **The census** (opt-in, nightly): the same over every magnitude to 2^31, with the counts logged.
* **The kernel over the extremes for 196611 and `Integer.MIN_VALUE`**, both forms, at the five lane
  counts the existing matrix test uses: the raised shift and the 2^31 magnitude emitted and run, not
  only derived.
* **The sweep** over the shared list, 196611 and `Integer.MIN_VALUE` added: the second
  implementation the proof is compared with.
* **The rendering** (`VarkaProofFilesSuite`): both committed files are what the code renders.
* **The prover's self-test**: each failure kind of section 3.3 rejected.
* **The bytes oracle**: `emitted_bytes.json` unchanged, which is the evidence for 3.7's first point.

## 6. The measurement

No benchmark: nothing emitted changes. What is measured is the prover's time against the lint job's
minute, the two solvers' agreement, and the proof against the sweep.

### 6.1 Predictions, registered before the run

1. **The linters' step finishes in under a minute on the CI runner**, install included, with under
   five seconds of it spent solving: the laptop solves the proof file in a third of a second.
2. **Z3 and cvc5 meet every expectation in the nightly's `prove` step**, and so agree, on every
   check of both files.
3. **The sweep and the proof agree on all eleven divisors**, and the planted fault fails the sweep,
   the rendering test and the rendered proof alike.
4. **The census counts 327,741,950 raised shifts below 2^31, the first at 196611, none among the
   divisors in use**, and finds no pair outside the lanes' two conditions.
5. **`emitted_bytes.json` is byte-identical.**

## 7. Risks

1. **The integer model is a second implementation of Java's arithmetic,** and a wrong definition
   would make a proof vacuous or wrong. `java_check.smt2` holds every definition to the JVM's own
   results, and the sweep is the independent check of the one proof built on it.
2. **A solver release changes a verdict or a time.** The versions are pinned and checked; a new one
   is adopted by changing the pin, with the proofs rerun under it.
3. **A later proof with a genuinely nonlinear term** may get `unknown` or run out of time under
   `QF_NIA`. The script fails it, which is the intended behaviour: such a proof needs its own
   design.
4. **`pip` fails in the lint container.** The image has a virtual environment on its path, and a
   failure is a red step, never a skipped proof.
5. **The raise moves a divisor in use.** The constants test and the bytes oracle both fail.

## 8. Sequencing

1. This plan, and row 240 marked Planned.
2. `signedMagic`: the long magnitude, the theorem's raise and the lanes' checks, the Javadoc; the
   constants, theorem, matrix and census tests; the bytes oracle unchanged.
3. The prelude, the renderer, the two rendered files and their suite.
4. `dev/varka_prove.sh` with `--install` and `--self-test`; the README.
5. The linters' step and the nightly's.
6. The nightly cycle - the sweep step and the `prove` step on one commit - and the planted fault,
   recorded in section 9.
7. The sweep cites the proof; the lesson; row 240, open question 1 and item 58 recorded.

## 9. Outcome, 9 October 2026

### 9.1 What was built

* **`signedMagic` asserts Theorem 5.1.** It returns a `MulHiMagic` record whose constructor refuses
  a pair that misses the theorem's inequality, a multiplier of 2^32 or more, or a shift over 62.
  Where the book's smallest shift misses the theorem the shift is raised until it holds, and the
  magnitude is a long up to 2^31, so `Integer.MIN_VALUE` derives `(0x80000001, 62)`.
  `VarkaEmittedBytesSuite` passes unchanged: no emitted byte moved.
* **`sql/varka/proofs/`.** The prelude `java.smt2`; `java_check.smt2`, 24 checks over 1,570
  equations whose right-hand sides the JVM computed; `int_mulhi_divide.smt2`, 35 checks - the
  domain's two extremes, and for each of the eleven divisors the form exact, the multiplier lowered
  by one refuted and the shift raised by one refuted. Both checked files are rendered by
  `VarkaProofFiles`, and `VarkaProofFilesSuite` holds them to the code.
* **`dev/varka_prove.sh`**, its six-case self-test, the linters' step under Z3, and the nightly's
  `prove` step under both solvers. On the laptop `--install both` takes under five seconds and is a
  no-op once done, the self-test two and a half, and every proof under both solvers under one.

### 9.2 The nightly cycle

`dev/varka_nightly.sh --iterations 100 --sparkfuzz-iterations 0 --skip-deopt --skip-cliff` on
commit `4151585adf1`, which carries the sweep step and the new `prove` step; the volume steps it
skips have nothing to compare.

| step | status | seconds | what it said |
| :--- | :--- | ---: | :--- |
| canary | failed | 31 | the machine off its baseline, straight after a cold build: the step that refuses benchmarks, not a check of this change |
| fuzz | ok | 47 | 100 iterations, seed 20261009 |
| forced | ok | 427 | VARKA-296's structural check, 100,000 projections |
| sweep | ok | 219 | the eleven multiply-high sweeps exact; the census over all 2,147,483,647 magnitudes, 327,741,950 shifts raised, the first for 196611 |
| prove | ok | 3 | the self-test under both solvers; both files hold under each, 59 checks apiece |

So the sweep and the proof agree on every divisor, on one commit: the JVM running the form over all
2^32 dividends, and two solvers proving the same statement over a model of Java's operators.

### 9.3 The planted faults

1. **In the code**: 12's multiplier lowered by one inside `signedMagic`. Nine tests fail, the
   sweeps for 12 and -12, the rendering test, the constants test, the census and the kernel
   matrix among them, and every one on the same line: "(715827882, 33) is not a multiply-high pair
   for 12: it fails Theorem 5.1's inequality". The record refuses the pair before the sweep's
   arithmetic or the renderer runs.
2. **Past the record**: the same pair, stated in the proof - its "multiplier lowered by one" check.
   Both solvers refute it, Z3 with n = -12 (the form gives 0, Java -1) and cvc5 with n = 2147483641
   (178956969 against 178956970), and both counterexamples fail in the JVM under the sweep's own
   arithmetic, whose first failing dividend is -2147483641.
3. **In the prelude**: five definitions made wrong - `<<` masking its count to six bits, `/`
   flooring, `Integer.MIN_VALUE / -1` left unwrapped, `>>>` as `>>`, and an `L2I` that saturates.
   `java_check.smt2` fails each under both solvers. The multiply-high proof fails three and goes on
   holding under the other two, whose difference its quotients never reach.

### 9.4 The predictions scored

1. **Holds** (scored after the merge, by VARKA-302). The "Varka proofs" step of #696's linters job
   took 7 seconds on the runner, installing Z3 included, and 0.8 of them solving: 0.78 s for
   `int_mulhi_divide.smt2` and 0.02 s for `java_check.smt2` (run 37990897708, attempt 2).
2. **Holds.** Z3 and cvc5 meet every expectation, 59 checks each.
3. **Holds, with a correction to how.** The sweep and the proof agree on all eleven divisors. The
   planted fault fails the sweep and the rendering test, but not through the arithmetic: the record
   refuses the pair first, so a wrong constant in the code never reaches either. Past the record,
   the proof refutes it with counterexamples the sweep's arithmetic confirms.
4. **Holds.** 327,741,950 shifts raised below 2^31 (2^31 itself is not), the first for 196611,
   none among the divisors in use, and no pair outside the lanes' two conditions.
5. **Holds.** `emitted_bytes.json` is byte-identical.

### 9.5 Corrections

* **The sweep's cost.** Sections 1 and 3.6 quoted item 58's thirteen and a half minutes for the
  sweep. Row 283 had already made it five seconds a divisor on 4 October (`VARKA-283.md` 9).
  Timed one test at a time with `-Dvarka.ownJvm=true`, each of the eleven sweeps takes five and a
  half seconds and the census 29 seconds on 24 threads, so the multiply-high's share of the sweep
  step is a minute and a half. (The nightly's log puts each sweep at a millisecond: the suite runs
  in a JVM of its own, whose results the parent replays, charging the child's whole run to its
  first test.) So the proof saves the nightly little; what it adds is that every pull request runs
  it, in the linters' job, that the theorem's assertion covers every divisor rather than eleven,
  and a second implementation for the sweep to agree with.
* **Prediction 3** assumed a wrong constant would reach the sweep; the assertion stops it first,
  which is the stronger result.

### 9.6 What this leaves

* **Row 241**, the long lane's two double-dividing forms, in floating-point theory. Int-blasting does
  not apply there; Bitwuzla's static build (section 2) is the first solver to try.
* **The emitted bytecode.** The proof states the arithmetic `emitMulHiDivide` is written to emit,
  restated by hand as `mulhi.divide`; what the emitter actually emits is checked by the kernel's
  parity over the extremes and by the fuzzers, not by the proof. That is the emitter layer item 58
  judged out of a solver's reach.
* **`D2I` and `D2L` in the prelude**, with row 241.
