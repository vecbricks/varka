# VARKA-576: the Arm rule for the NaN payload of floating arithmetic

Row 576 of `PLAN.md`. Found on 5 November 2026 by the first dispatch of row 505's axis.

## 0. What exists now (admission check)

1. **One answer from Arm.** Run 42018067671 of the option matrix, under `isa=arm`, failed
   `VarkaCoverageCompositionFuzzSuite`'s two tests on `try_subtract(f, f2)` over the `FLOAT`
   patterns `0x7FC00000` and `0x7F800001`: Spark's own `eval` answers `2143289345` (`0x7FC00001`)
   and the reference evaluator `2143289344` (`0x7FC00000`). The pair is a quiet NaN and a
   signalling NaN with payload 1; Spark's answer is the signalling one quieted, the reference's the
   first operand. On x86 the same pair gives the first operand, which is the rule rows 568 and 556
   built (`VARKA-556.md` 11.1): `VarkaSameAnswer`'s row level opens only the add and the multiply of
   two unlike NaNs, and the subtract and the divide are fixed to the left payload.
2. **What is not known.** Every other operator and operand class on Arm: `+`, `-`, `*` and `/`; both
   widths; quiet against quiet, signalling against quiet in both orders, signalling against
   signalling; payloads and signs that differ. The rule for an x86 host is a table in the
   reference evaluator and `VarkaSameAnswer`; nothing says what Arm's table is except the one cell
   above and the architecture manual, which the row does not rely on.
3. **Two engines are compared, not one.** Spark's `eval` is scalar code the JIT compiles for the
   host, and a kernel's lanes are the Vector API's. Whether the two agree with each other on Arm is
   a second question: if they differ, the fix is in the kernel, not in the reference.

## 1. Goal and non-goal

Goal: the reference evaluator and `VarkaSameAnswer` give Arm its own NaN-payload answers for `+`,
`-`, `*` and `/` on `FLOAT` and `DOUBLE`, read from the Arm runner's own results, so that the two
tests of `isa=arm` pass and their lines leave `sql/varka/matrix/skips.tsv`.

Not in this row: x86's rule, which rows 568 and 556 own; SVE hosts, which the pool does not offer;
the kernel itself, unless step 1's table shows its lanes differ from Spark's scalar code.

## 2. Design

1. **A probe, not a derivation.** A canary class `NaNPayloadProbe` under `dev/varka_canary`, run by
   `varka-canary.yml` on both architectures as `MathLaneProbe` is. For each operator, each width
   and each pair of a fixed pattern set (quiet and signalling NaNs of both signs and several
   payloads, and the infinities and zeros that make a NaN), it prints the result bits of the scalar
   expression after C2 has compiled it and of the same operator through the Vector API's lanes.
   The output is a table per architecture.
2. **The table is committed** as `results/VARKA-576-nan-payload.txt` with the host's CPU and the
   JVM's flags, for the Arm runner and for an x86 one, so that the x86 column checks the probe
   against the rule that is already built.
3. **The rule follows the table.** If Arm's scalar and vector answers agree, the reference
   evaluator and `VarkaSameAnswer` take a host-dependent rule keyed on the architecture, as
   `VarkaHostIsa` keys other host facts; if they differ, the row stops and reports the kernel
   difference first.

## 3. Tests

1. The probe's x86 column equals the x86 rule on every cell.
2. `VarkaSameAnswerContractSuite` gains a case per Arm cell.
3. The two `isa=arm` tests pass and their skip lines are deleted, which the stale-line check
   enforces.

## 4. Order

1. The probe (small); one dispatch of `varka-canary.yml` for `NaNPayloadProbe`, which needs the
   owner's word.
2. The table, committed.
3. The rule and its tests, under a plan of its own if the table shows more than the one cell.

## 5. Done when

The two tests pass under `isa=arm` with their skip lines deleted, the table is committed for Arm and
x86, and a dispatch of the option matrix shows `isa=arm` green.

## 6. Questions for the owner

1. **Is a host-dependent rule acceptable, or should Varka make the answer the same on every
   host?** A kernel can force one payload where the two hardware rules differ, at a cost in every
   floating `+`, `-`, `*` and `/`. Recommended: the host-dependent rule, since vanilla Spark's own
   answer differs by host and the project's rule is to follow it (the owner's decision of
   1 November 2026 on row 365).

   *5 November 2026, by the owner's word:* the host-dependent rule (option A). Varka's answer must
   equal vanilla Spark's on the same host; the reference evaluator and `VarkaSameAnswer` take a
   table per architecture, and the kernel is changed only where the Arm table shows Spark's scalar
   code and the kernel's lanes disagree. The alternatives were one payload forced on every host, at
   a kernel cost on every floating `+`, `-`, `*` and `/` and a departure from vanilla on one host,
   and no payload check, which the owner's decision of 4 November 2026 on `nanByNess` rules out.

## 10. Outcome

### 10.1 The probe and the cause (6 November 2026)

`dev/varka_canary/NaNPayloadProbe.java` ran on `ubuntu-latest` (an AMD EPYC 9V45) and
`ubuntu-24.04-arm` (a Neoverse N2) in run 42193042419, 840 cells each, and the table is
`results/VARKA-576-nan-payload.txt`. Section 0's unknowns, answered:

- **Arm's rule, all four operators at both widths, scalar:** a signalling NaN operand's payload,
  quieted, the first such operand when both are, else the first quiet operand's: 105 of 105 pairs
  with a NaN, in each of the eight cells of operator and width. x86's, in a subtract, a multiply
  and a divide: the first operand's, quieted, 105 of 105; its add follows neither rule (65 of
  105), since a tier may swap its operands.
- **Scalar against lanes:** on Arm they agree in every pair but a DOUBLE add and multiply of two
  NaNs (18 pairs each), where the lane returns the other operand's payload; on x86 the lanes of a
  multiply and an add differ in pairs of two NaNs only (float 40 for the multiply, double 40 and
  16). Both are the case the row rule of `VarkaSameAnswer` leaves open, so no kernel change and no
  new rule table are needed.
- **Why the two tests failed.** `VarkaReference.floatArith` evaluated a `FLOAT` operation through
  doubles, as the double lane does. Widening a signalling NaN to a double quiets it, so on Arm the
  reference saw two quiet NaNs and returned the first, `0x7FC00000`, where Spark's float operation
  returns the signalling operand quieted, `0x7FC00001`. On x86 both give the first operand, which
  is why only Arm failed. The reference now evaluates a `FLOAT` operation in float
  (`floatArithmetic`), so it follows the host as Spark does, which the owner's answer of
  6 November 2026 (option A) asks for; `VarkaSameAnswerContractSuite` pins it over every pair of
  NaN patterns for the subtract and the divide against Java's float operators.
- **Locally**, `VarkaSameAnswerContractSuite` and `VarkaEmitterFloatingArithSuite` pass (49
  tests). Not run: `isa=arm`, which the option matrix runs; the two skip lines of this row are
  deleted, and a dispatch that shows them passing there is what is left.

