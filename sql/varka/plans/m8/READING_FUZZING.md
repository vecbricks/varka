# Reading: what compiler fuzzing and test-case reduction offer Varka's fuzzers

Read on 3 November 2026 for row 352 (the fuzzers across types), VARKA-559 (IR planning whose cost
doubles per level of a shared node, started on its own branch) and the shrinkers. Six papers,
read as PDF text from the authors' copies, kept outside the repository: Yang, Chen, Eide and
Regehr, PLDI 2011 (Csmith); Regehr et al., PLDI 2012 (C-Reduce); Livinskii, Babokin and Regehr,
OOPSLA 2020 (YARPGen) and PLDI 2023 (YARPGen v2, loops); Sun, Li, Zhang, Gu and Su, ICSE 2018
(Perses); Groce et al., ISSTA 2012 (swarm testing). **No source file of these tools was opened.**
Their licenses, checked from the repositories' LICENSE files: Csmith BSD-2-Clause, YARPGen
Apache-2.0, C-Reduce University of Illinois/NCSA, **Perses GPL-3.0, so paper only**. The papers
carry ACM copyright or the authors', so none goes into `sql/varka/papers/` without asking.
Varka's side is `origin/master` `f72e4556750`. **[P]** means read in the paper, **[V]** in Varka's
code or plans, **[I]** inferred. Nothing was built or run. Not repeated: `m7/READING.md` 254-299
(PQS, NoREC, TLP, ddmin, EMI), `READING_SQLANCER.md`, `READING_CONFIG_SAMPLING.md`.

## 1. Varka's fuzzers, the baseline [V]

* **The IR grammar** (`VarkaIrGrammar.java`) draws a depth of one to four, a node budget of 20
  (line 935) and an arm uniformly from 26 (line 997). Overflow is avoided statically, YARPGen's
  way: every subtree carries a saturating bound, and a checked mode (`FAIL` or `NULL`) is drawn
  only where the bounds rule overflow out (`mode(boolean safe)`, lines 978-990; the long grammar's
  add and subtract, 1470-1488, "the kernel has to answer, not decline"; `mode(long bound, long
  limit)`, 1758-1767); `IntNeg` draws `FAIL` only under an int bound (1103-1114).
* **The IR fuzzer's data** (`VarkaIrFuzzSuite.scala`): batch lengths around the lane boundaries
  (line 135), five null patterns, literals uniform over plus or minus 4000 (line 361) and columns
  uniform over plus or minus `COLUMN_BOUND`, 2,500,000 days (`VarkaIrGrammar.java` line 122).
* **The Catalyst grammar** (`VarkaCatalystGrammar.java`): `MAX_DEPTH = 3` (line 290), a child a
  leaf with probability `0.2 + 0.2 * depth` (line 308).
* **The shrinker** (`VarkaShrinker.java`): ddmin over roots, options and rows; a tree move tries a
  leaf, then each direct child of the same kind and lane (`replacements`, lines 293-322); literal
  values are only read, never reduced; a run must keep its failure signature.

## 2. Sorted

### (a) Worth a plan note or a row

1. **Checked arithmetic that overflows is never drawn [V/P].** Csmith wraps C's undefined overflow
   at run time; YARPGen rules it out statically, and Varka took the second. In C an overflow means
   nothing, in Spark it means a raise or a null, so the import went too far: no random tree's
   checked `IntArith` or `IntNeg` ever overflows. The `NULL` arm of `VarkaReference.intArith`
   (lines 331-335, empty on overflow) and the "kernel must decline" path for `FAIL` (its javadoc,
   299-306) are reached only by hand suites, and a nulled lane under an `IfElse`, a calendar node
   or a second output is never fuzzed. Row 481's nulling draw did exactly this for `GuardedRange`.
   YARPGen v2 (section 3.5) splits its iterations into two subsets so a mask is not always all on;
   the same split serves here: a side sequence that draws a checked mode with its bound past the
   limit, over data in which some rows overflow and some do not. Small.
2. **Values almost never tie [V/P].** Two columns are equal on a row about once in 5 million (two
   uniform draws over 5,000,001 values), and a column equals a literal almost never, so `EQ`,
   `NE`, `LE`, `GE` and `NullSafeEqual` over two wide operands are decided one way, an `IfElse` on
   one takes one arm, and `greatest` and `least` never tie: a lowering that emits `<` for `<=`
   survives the fuzzer on non-null rows. Calendar fields have small ranges and do tie. YARPGen's
   constant policies (OOPSLA 2020 section 3.3) reuse a buffer of earlier constants; for Varka, a
   share of cells drawn from a small per-batch pool that holds the literal table, on a value stream
   of its own (`VARKA-352.md` 2.2 rule 5), proven on a planted `<` against `<=` mutant. Small.
3. **Deep, shared trees are drawn by nothing [V/P].** VARKA-559's class needs depth and sharing,
   `IfElse(cond, IfElse(cond, a, c), b)` over one compiled `cond` at each level. The IR grammar
   stops at four levels and 20 nodes, the Catalyst grammar at three, and sharing comes only from
   the variants at the roots (`VarkaVariants.duplicated` and `copied`), so `MAX_CHAIN_DEPTH` (16)
   is never approached by a draw. YARPGen's common-subexpression buffer (OOPSLA 2020 3.3) produces
   the same subtree under several parents; Csmith's section 3.3 finds bug yield falling at the
   largest sizes from "algorithms ... that have superlinear running time". Neither treats a
   compile-time blowup as a finding (YARPGen "did not pursue" hangs, section 2); Varka must,
   because one shows as a watchdog halt, not a named shape. Proposal: a side sequence of the
   Catalyst grammar nesting `if` and `coalesce` 8 to 32 deep, each level reusing an earlier subtree,
   bounded by a work counter (as #1324's `nestedConditionsCompileOnce` counts condition compiles
   instead of timing them), so a breach fails with its shape and shrinks by depth; an IR twin draws
   chains to `MAX_CHAIN_DEPTH` and one past it, which `Analysis` refuses. Medium: row 561.
4. **The shrinker, two small moves [P].** Perses (section 4.4) replaces a node by a compatible
   descendant, the first on each path within a bounded search of path length 4; Varka tries direct
   children only, so the operands of an `IfElse`'s `Compare` condition, or `x` in
   `NarrowLane(IntArith(WidenLane(x)))`, are out of one move's reach. C-Reduce (section 5) turns
   integer constants to 0 or 1; Varka never reduces a literal. Readability only; no bug was missed
   for them. Beside `READING_SQLANCER.md` 7 action 7, as `m9/SCOPE.md` item 133.
5. **Generation policies and swarm, as a measured A/B only [P].** YARPGen's contexts and its 23
   redrawn parameters (OOPSLA 2020 3.3; four of nine LLVM crashes found only with policies, Table
   4) and swarm's omission of each feature with probability one half (ISSTA 2012: 42% more distinct
   crashes in a week) map to arm weights redrawn per case and half of the 26 arms off. The prior
   is weak: VARKA-280's steering did not pay at 300 draws, and YARPGen v2 found one counter of 72
   reached only with policies. An A/B in VARKA-280's harness by rare-mechanism frequency, adopted
   only on a positive result; not a row.

### (b) Not applicable to a bytecode-over-C2 target

Csmith's run-time wrappers and effect analysis (Varka's IR has no side effects or evaluation
order); Seq-Reduce, which mutates the generator's choice sequence (`m7/VARKA-320.md` already
records that a change in what draws "shifts every draw" after it); C-Reduce's source-to-source
passes and a SQL-text reducer (the Spark fuzzer shrinks the grammar tree before it renders
text); YARPGen v2's stencils, reductions and loop interchange (Varka's kernels are
row-independent maps and filters).

### (c) Varka already does it as well or better

* **Static UB avoidance**: Varka's symbolic bounds, with SMT proofs of the arithmetic lowerings
  (`skills/testing-and-debugging.md`), beside YARPGen's 4-bit exhaustive and Z3 checks.
* **Unspecified results**: Csmith's checksum must not depend on them; Varka's register of NaN
  payload and sign (`SCOPE_STANDARD_MODE.md` 3.5) and row 555's two-NaN rule are that discipline.
* **Optimisation counters**: YARPGen measured its policies on compiler counters; Varka's
  `VarkaEmitMechanism` reach record is the same instrument, with an A/B harness (VARKA-280).
* **Loop-shape coverage** (YARPGen v2): lengths at the lane boundaries, the masked and dense
  bodies, `forceMasked`, `lanesOverride` and the budgets drawn per case, wide shapes to the byte
  and call-site budgets, and two oracles, the reference and the Spark differential.
* **Swarm over options**: each emit option is a coin per case (`randomOptions`).
* **The reducer's validity test**: a reduced case keeps its failure signature, stricter than
  C-Reduce's interestingness test, and candidates are memoised.

Not read: the tools' source, Csmith's bug tables, Perses's per-benchmark results and YARPGen v2's
bug list.
