# VARKA-<n>: <title>

<!-- The sections every task plan here has converged on, in the order a reader
     expects them (compare VARKA-53.md and VARKA-54.md). Keep the
     headings; replace the guidance under each. A plan is written as the work
     happens: sections 1-8 before the code, section 9 after the measurement,
     and nothing in 1-8 is rewritten to look prescient afterwards - a correction
     is added and says what it corrects (sql/varka/AGENTS.md, "Plans are
     records, not scratch space"). Delete these comments as you go. -->

## 1. Where this came from

<!-- The milestone row, scope item, review or measurement this task answers,
     with the number that motivated it and where that number is committed. One
     paragraph. If an owner directive started it, quote the directive. -->

## 2. The admission check, done

<!-- What has to be true for the design to be worth building, checked before
     any emitter work and recorded here with the check's exact domain: a magic
     constant swept over every value it will see, an identity checked over all
     of its cases, a probe measured. "Done" means the numbers are in this
     section, not that the check is planned. Say what the check would have
     rejected. -->

## 3. The design

### 3.1 <the mechanism>

<!-- What changes, in the emitter's own terms: which helper, which slots, which
     option switch. New behaviour goes behind a VarkaEmitOptions switch with the
     old form kept as a live reference variant under the same tests (the
     FloorMod7 precedent), and the default flips in the last commit per the rule
     in 6.1. -->

### 3.2 What is deliberately unchanged

<!-- The neighbours this task does not touch, and the task that owns each. -->

### 3.3 Registered op counts

<!-- Per node, before and after, on the emitter suite's metric (IntVector
     invocations in loopDense0 - print them with dev/varka_emit.sh rather than
     counting by hand). Asserted by a test in section 5. -->

## 4. Files

| file | what |
|---|---|
| | |

## 5. Tests, and what each is for

<!-- One line per test: the shape, the oracle, and the failure it would catch
     that no other test would. Both vector widths. Say which pinned fixtures
     move and why, or that none do. -->

## 6. The measurement

<!-- Which benchmark section, which A/B pairs, both forms named explicitly so
     the labels survive the default changing, both widths, regenerated with
     dev/varka_bench_regen.sh on an idle machine. Name the control row. -->

### 6.1 Predictions, registered before the run

1. <!-- a number, a direction and a reason; the rule that decides the default -->
2.
3.

## 7. Risks

1. <!-- what could be wrong, and which test or check in 5 would show it -->

## 8. Sequencing

<!-- Commits, each green on its own; the constants and the scalar twin before
     the emitter, the emitter behind the switch before the measurement, the
     default and the docs last. -->

## 9. Outcome

<!-- Filled in when the measurement lands: the numbers with the committed file
     they trace to (dev/varka_quote_check.py holds you to this), 6.1's
     predictions scored one by one, what moved that the plan did not list, and
     what the task leaves for later - which goes to the milestone's debt
     register or a scope document, never to a code comment. -->
