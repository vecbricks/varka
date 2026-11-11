# VARKA-506: The decimal kernels' proofs, rendered from the IR the compiler lowers

## 0. What exists now (admission check)

From master at `fa698652fd2` (30 October 2026), the plans of rows 347 to 351 and 359, 362, 363 and
364 on master, and rows 360 and 361's plans on branch `varka-360-361-plan` at `ada77eb513a`, which
is not merged (the branch, master since merged into it at `ee7be2ce9aa`, is being opened as a pull
request; its citations here resolve once that merges). Varka paths are under
`sql/catalyst/src/{main,test}/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/`
unless named. The row's text is its line in the milestone
plan's table, with 2.8's "The proofs (359 to 364)" and risk 5.

1. **`refinement.smt2` is a transcription, and it holds no decimal.** `VarkaRefinementFile` (test
   side, 1016 lines) says so in its javadoc: "A kernel here is the arithmetic the emitter builds
   for a coverage row, written with `java.smt2`'s operators" (`:35`). Each kernel is a Java string,
   `integerArithmetic`'s `"(jint.add a b)"` (`:600-630`) or the calendar's `k.*` definitions
   (`kernel()`, from `:195`), with only the constants (`VarkaChrono`, `ChronoDivide`) read from
   the code. Nothing in the file reads a `VarkaVectorIR`. No proof file names a decimal (a search
   of every `.smt2` under `sql/varka/proofs/` finds none).
2. **How a rendered file is kept and run.** `VarkaProofFiles.render()` (`:77`) maps each file name
   to its text, `refinement.smt2` among them (`:95`); `VarkaProofFilesSuite` fails when a
   committed file differs and rewrites it under `VARKA_PROOFS_REGEN=true`. `dev/varka_prove.sh`
   inserts `java.smt2` after each file's `set-logic`, holds each verdict to its `expect`, and fails
   on `unknown`, a timeout or stray output; each check has 10 s (`VARKA_PROVE_LIMIT_MS`, `:58`) and
   each file 300 s (`:59`). The linters' job installs both solvers, runs the self-test under both
   and `--lint`, which runs every file under Z3 except `CVC5_IN_LINT`'s (`:57`, today only
   `long_divide.smt2`); the README allows "a minute for all of them". Both solvers over every
   file run in `dev/varka_nightly.sh` (`:179`), a laptop script, and in no workflow, which row
   490 names. The JVM renders, the linters' job (no JVM) solves.
3. **`java.smt2` lacks two operators the decimal lowerings use.** It has the wraps, `+ - *`,
   negation, `~`, the divisions, the shifts by `pow2`, `x & (2^k - 1)` as `jlong.and.low`
   (`:212`) and the disjoint `or` (`:213`), each held to the JVM by `java_check.smt2`. It has no
   unsigned compare (359's `ULT` to `UGE`) and no `AND` with a sign word (361's `y & sign(x)`).
4. **The IR has no decimal node yet, and the long lane's primitives are what the pair is built
   from.** `VarkaVectorIR` is a sealed interface with exhaustive switches (`childrenOf`, `:1678`):
   `IntArith` (`:672`, `ADD SUB MUL`, each `WRAP FAIL NULL`), `IntNeg`, `BitOp` (`:953`, `AND OR
   XOR SHL SHR USHR`, its right operand a vector), `Compare` (`:976`, `LT LE GT GE EQ`), `IfElse`
   (`:1209`), `CondValue` (`:1224`), `GuardedRange` (`:536`), `IsNotNull`, `ConstDivide` (`:764`).
   No `VarkaDecimalCompiler` is on master; 347's leaf and reader are on its branches.
5. **The row's premise holds for some decimal nodes and not for the multiplies.** The row cites
   `VARKA-359.md` 2.2 for "a decimal's words, limb products, carries and guards are ordinary IR
   nodes". The plans since:
   - 359: the word leaf `DecimalWord` and four unsigned `CompareOp` members, everything else
     composed of the primitives above (its 2.2, 2.5). The `10^p` check of the load, `gt128`, is
     emitted by the walk under `emitGuardCollect` (its 2.4), not built as nodes.
   - 360: the carry and borrow from primitives in two forms, `COMPARE` and `BITS` (its 2.3), the
     class K guard a `GuardedRange` (2.4); `WideMul` lands in its 360b with constant multipliers.
   - 361: "A node, not a composition": `WideMul(left, right, word, rightOneLimb)`, because a
     `BitOp` with a `LiteralSlot` mask loses `vpmuludq` (its item 0.7) and the two words share the
     limb products. Its network (2.3) is emitter code, the masks and shift counts emitted
     constants.
   - 362: `PairMul(...)`, sixteen products, calling the emitter's partial-product helper that 361a
     writes (its 2.2). 363: `ScaleDown(...)`, its constants from a table "which the proof renderer
     reads as well" (its 2.2). 364 and 350: no new node (350's limb-magic arm is an option arm).
   So the limb products and their carries sit inside the emitter's lowering of `WideMul`,
   `PairMul` and `ScaleDown`, which is where the planted faults of 361, 362 and 363 live. A printer
   that reads the IR as the plans now build it sees `wmul:hi(x,y)` and nothing to render a carry
   from (2.1 settles it; section 8, point 1).
6. **The per-node semantics today.** `VarkaReference` (test side, 830 lines) answers every node in
   one exhaustive switch (`value`, `:82-188`, and its long and condition twins), and the IR
   fuzzer, `VarkaReferenceEvaluator` and the differential suites read it. Row 489's descriptor,
   which the row says the SMT meaning sits beside, is built but not merged: on 27 October 2026, on
   the local, unpushed branch `varka-489-descriptor` (`81f2553ea4e`, read with `git show`), every
   IR record answers `VarkaNodeProps props()` with no default, a main-side record of six
   components (`ring`, `validity`, `weight`, `costKind`, `fills`, `restricted`), and ten switches
   read it (`VARKA-489.md` 1 on that branch). It holds no per-node meaning; its section 0 lists
   every switch over the IR's node types, 43 of them, which is the list a new node reaches. The
   row in the milestone plan has no status yet.
7. **The decimal rows' proof files**, each "written by `VarkaProofFiles` from the code's
   constants": `decimal128.smt2` for 359, 360, 361 and 362 (their 3 items 3, 5, 6, 7),
   `decimal_scale.smt2` for 363 (3 item 6), `decimal_casts.smt2` for 350 (3 item 4),
   `decimal128_casts.smt2` for 364 (3 item 4), and `decimal64.smt2` for 348 and 349's lemmas
   (`VARKA-348.md` 3 item 3, `VARKA-349.md` 3 item 5). Rows 348 and 349 also give each 64-bit
   decimal coverage row its five checks in `refinement.smt2` through `VarkaRefinementFile`, by
   hand, and say "Row 506's printer is not this row's prerequisite" (`VARKA-349.md` 3 item 5).
8. **The planted faults the row names**: 350, the rescale up multiplied before its check
   (Trino's `shortToShortCast` order, its 3 item 3); 360, the same order at class K's guard (its
   2.4); 361, the middle column's two products summed in one word (QuestDB's `a0 * m1 + a1 * m0`,
   its 3.1); 362, column 1 summed the same way (its 3 item 3); 363, two products of one column of
   `N * c` summed in one word (its 2.7). Row 364 plans three more, each caught by a suite (its 3
   item 3), which the row's done-when does not list.
9. **A coverage row's IR is reachable from a test.** `VarkaCoverageSuite` compiles every row
   through `VarkaExpressionCompiler.compilePredicate` or `compilePartial` and walks the fused
   outputs' trees (`VarkaCoverageSuite.scala:909-935`), the same trees the bytes oracle hashes.
   Row 490 has each coverage row name its verification (a refinement section, a proof file, an
   exhaustive suite, `differential` or an owing row), and `VarkaCoverageSuite` fails one that
   does not.
10. **m9 item 90** (`m9/SCOPE.md` 4906) is translation validation: the emitted class read back,
    one lane group executed symbolically, its outputs checked against the IR's reference
    semantics, over the bytes oracle's corpus; a spike first, not a row in this milestone.
11. **The solvers.** Neither `z3` nor `cvc5` is on the laptop's `PATH` and this worktree has no
    `target/varka-solvers/`. The spike ran the pinned Z3 5.1.0 and cvc5 1.4.1 from another
    worktree's `target/varka-solvers/`, the download `dev/varka_prove.sh --install both` makes and
    checks, not from a laptop install.
12. **The spike** (`results/VARKA-506-admission.txt`, laptop): row 361's network written by hand in
    the printer's two candidate forms, the products of two limbs free in `[0, (2^32 - 1)^2]`.
    Form A, each step a definition over `java.smt2`'s wrapping operators: Z3 did not decide the
    carry layer within its two-minute limit (120.01 s), cvc5 decided the whole file in 0.27 s.
    Form B, the word form of 2.3: every check as expected under both solvers, the whole file in
    0.06 s under Z3 and 0.09 s under cvc5, the planted order refuted. Both solvers' free-product
    witnesses for the planted order were not realizable (cvc5's at `x = y = 0`), so a refutation
    also needs a check pinned at a real witness with the products the limbs' own, which holds.
    The witness is `results/VARKA-361-planted-carry.txt`'s, on branch `varka-360-361-plan`; the
    citation resolves once that branch's pull request merges.
13. **Row 504** (every check proves it can fail) is not started; its rule is what the planted
    faults serve.

### What differs from the row text and the milestone plan

- **The printer renders the lowered graph, not the IR as the multiplies' plans build it** (item
  0.5). A composite decimal node lowers to a graph of primitive nodes by a function the emitter
  emits from and the printer renders from, so a planted flag changes both (2.1). This keeps
  rows 360 to 363's nodes in the shape key, the budget and the evaluator, and changes where their
  network is written: in that function, not in `VarkaVectorWalk`. Settled by the lead, point 1 of
  section 8, which lists the row notes 359 to 363 owe.
- **The meaning of each node sits beside `VarkaReference` until row 489's descriptor lands**
  (item 0.6), in a test-side exhaustive switch held to the evaluator on sampled inputs, and moves
  into the descriptor when it does (2.2). Settled, point 2.
- **The printer's form is the spike's form B** (item 0.12): unsigned word readings, the no-wrap
  claims as obligations, named quotients. Form A is what the row's "SMT over `java.smt2`'s
  operators" reads as, and Z3 does not decide it.
- **A planted refutation is three checks, not one** (item 0.12): the free-product `sat`, the same
  claim pinned at a witness of the results file with the products the limbs' own (`sat`), and the
  default lowering at that witness (`unsat`).
- **The 64-bit decimal shapes are in scope** (item 0.7): the row's done-when is "every decimal
  shape that fuses", and 350's planted fault is a 64-bit cast, while its opening names "the
  decimal shapes of rows 359 to 364". This plan renders 347 to 351's shapes too and retires the
  hand sections 348 and 349 add to `refinement.smt2`, later: those rows land with their hand
  sections, 506 not being their prerequisite. Settled, point 3.
- **Built after 359a and 360b, not before 361 and 362 alone** (5): 506a and 506b after 359a, 506c
  after 360b. Settled, point 6; the row's line carries the dated correction.
- **The load's `10^p` check is in the lowered graph** (item 0.5): 359 has the walk emit it; here
  it becomes a condition graph the lowering function builds, or the printer could not see the
  decline it renders check 2 against.

## 1. Goal and non-goal

For every decimal shape that fuses - a coverage row of a decimal family, in each mode it fuses
in - the kernel side of its refinement proof is rendered by a printer from the lowered graph the
emitter emits, against a specification of Spark's `Decimal` rule written once by hand and never
from Varka's IR; the committed rendering is held to the code by `VarkaProofFilesSuite`; each
planted fault of rows 350, 360, 361, 362 and 363 is refuted by a rendered proof; and a shape the
printer cannot render is listed with its reason. Not this row: the bytecode against its IR (m9
item 90), the lemmas over constants and the specification-side lemmas (they stay in their rows'
files, 2.5), the integer and calendar rows of `refinement.smt2`, which stay hand-written in this
milestone, and a bit-vector rendering (2.9).

## 2. Design

### 2.1 What the printer reads: the lowered graph

A node is **primitive** when the emitter emits it as one Vector API call or a fixed few with no
network inside - `ColumnRef`, `LiteralSlot`, `IntArith`, `IntNeg`, `BitOp`, `Compare` (with 359's
unsigned members), `IfElse`, `CondValue`, `GuardedRange`, `IsNotNull`, `And`, `Or`, `Not`,
`NullSafeEqual`, `WidenLane`, `NarrowLane`, 359's `DecimalWord` - and **composite** when the
emitter builds a network for it: 361's `WideMul`, 362's `PairMul`, 363's `ScaleDown`, and the
load's `10^p` check. A primitive gets a template (2.2). A composite gets none: it lowers.

**The lowering function.** Each composite has one pure function from the node to a graph of
primitive nodes, `VarkaDecimalLowering.lower(node, faults)`, over one new leaf, `WordConst(long
value, LaneType lane)`, a constant the emitter materialises with `loadConstant` as
`emitBitCount` does, so a mask or a shift count stays a constant in C2's graph and `vpmuludq` is
kept (361's item 0.7). The
emitter emits a composite by lowering it and walking the result with its ordinary per-node
emission, the shared limb products parked once by the walk's shared-node rule; the printer
renders the same result. A `BitOp` shift whose right operand is a `WordConst` is emitted as
`lanewise(op, int)` with the count a constant, and an `AND` with one as `and(long)`, the forms
361's network is planned in; and the two words' lowerings share their limb products only if the
walk's shared-node rule matches the lowered nodes by value, which records give. The node keeps
its place in the shape key, the budget and the evaluator, since the lowering runs inside the
emitter, after the key. `faults` is a set of planted faults, empty everywhere but the planted
tests (2.7). Three options were weighed:

- **(a) lower to primitive nodes**, as above. One source, the primitives' templates reused, and
  `VarkaReference` can evaluate the lowered graph against the composite's own answer.
- **(b) one lowering over a sink interface** with a bytecode sink and an SMT sink. One source too,
  but the sink's operations need their own meaning, a second copy of the templates.
- **(c) the printer re-states the network.** The transcription the row exists to remove, and a
  planted carry in the emitter would leave the proof holding. Rejected.

The lead settled (a) on 30 October 2026 (section 8, point 1): it changes where 359's load check
and 360b to 363's networks are written, and the row notes those rows owe are listed there. The
printer refuses a composite with no lowering, naming it.

**What a shape is.** A coverage row of a decimal family at its fixture types in one mode, compiled
as `VarkaCoverageSuite` compiles it (item 0.9), each output lowered. The compiler's choices -
which form, which guard, the arm the defaults select - are in that graph, so the rendering is of
the kernel the defaults build; an option arm renders when a shape is compiled under it (2.6).

### 2.2 The templates: each node's meaning stated once

A lane's outcome is `(decline, valid, data)`, as `refinement.smt2`'s header has it. Each
primitive's template gives its three parts from its children's: `data` a term over the children's
data, `valid` the conjunction of the children's for a null-intolerant node (and the node's own
rule for `IsNotNull`, `IfElse`, the null-safe forms and `GuardedRange` in `NULL` mode), `decline`
the children's disjunction plus the node's own (`GuardedRange` in `FAIL`, an `IntArith` in
`FAIL`). `DecimalWord`'s data is the column's unscaled `u` read as its low word, `u mod 2^64`
signed, or its high word, `floor(u / 2^64)`, over the specification's `u`.

The templates live in one test-side class, `VarkaSmtNodes`, an exhaustive switch over the sealed
interface beside `VarkaReference`, so a new node does not compile until it has a template, a
lowering, or a refusal with its reason. The printer builds a small term tree (records: constant,
variable, the prelude's operators, `ite`) that renders to SMT-LIB and also evaluates over
`BigInteger` in the JVM; `VarkaSmtNodesSuite` holds every template to `VarkaReference` on the
IR fuzzer's decimal sequence and on boundary words, and each lowering to its composite's
reference answer, so the meaning stated once agrees with the evaluator the differential and item
90 use.

**When row 489 lands, the meanings move into its descriptor** (the lead's settlement, point 2):
each record's template moves beside its `props()`, which already has no default, so the
completeness stays the compiler's, and the test-side switch is removed; `VarkaSmtNodesSuite`
holds the moved templates as it held the switch. The descriptor lives in the main source set, so
the term tree's records move there with the templates; the printer, the files and the suites stay
test-side. Until then the switch is the one place a node's meaning is stated.

### 2.3 The word form, and the operators added to the prelude

The spike's form B (item 0.12). Each long value is rendered by its unsigned reading, a value in
`[0, 2^64)`, with an interval the printer computes from its operands'. A wrapping step - an add, a
subtract, a shift left - whose interval stays inside `[0, 2^64)` is rendered exact, and its no-wrap
claim becomes an obligation check (`unsat` of the step leaving the range); a step that can wrap
keeps one reduction, a named quotient `w` with `0 <= v - 2^64 w < 2^64`. A shift right or a mask
by `2^32` is a named quotient pinned by its remainder's range, the README's advice for cvc5 and
the refinement file's own form (`quotients()`). An arithmetic shift by 63 is the sign of the
signed reading; a signed compare reads the words signed, an unsigned one unsigned. The prelude
gains two definitions in the cases the lowerings use, each with its `java_check.smt2` rows:
`jlong.ult` (and its three siblings), the compare of the two readings modulo `2^64`, and
`jlong.and.sign`, `m & y` for a sign word `m` in `{0, -1}`, with that range an obligation.

Form A is not offered as a fallback: Z3 left its carry layer undecided at the two-minute limit
(120.01 s) where form B took 0.06 s for the whole file (item 0.12). That is a finding for the
README beside `dev/varka_prove.sh`'s files (`sql/varka/proofs/README.md`), whose "Adding a proof"
step 4 lists what to try when cvc5 answers `unknown` and says nothing of Z3 timing out on a
network of wrapping operators; the advice to add there is the word form above. This plan does not
edit the README (section 7's findings).

### 2.4 A product of two unknowns: the two layers

A `MUL` with a `WordConst` operand renders exactly; it is linear. A `MUL` of two non-constant
operands is rendered only when each is recognised as a 32-bit limb of a word the shape has - a
`BitOp(AND, w, WordConst(2^32 - 1))` or a `USHR` of `w` by 32, `w` a word of an operand of the
composite - and then becomes a free variable `P_ij`, named by its operands' limbs, in
`[0, (2^32 - 1)^2]`; any other product of two unknowns is a refusal (2.9). This is C2's
`vpmuludq` predicate stated as a rendering rule (361's item 0.7), so a lowering that matches C2
also matches the printer.

Check 4 then takes 2.8's two layers. **The ring layer** is a lemma, written by hand once per
network width (four products at 64 by 64, eight at 128 by 64, sixteen at 128 by 128): the
weighted sum of every limb pair's product is `Xu * Yu`, with the sign correction `X * Y = Xu * Yu -
2^w ([X < 0] Yu + [Y < 0] Xu) + 2^(2w) [X < 0][Y < 0]` and the product's range, nonlinear and
proven alone (the spike's 4a checks, under both solvers at 64 bits). **The carry layer** is the
rendered kernel against the specification's product, where that product is written as the ring
lemma's instance over the same free `P_ij`, so check 4 is linear. The instance is the full set of
limb pairs, fixed by the widths and not by the lowering, so a lowering that drops a product, sums
two in one word or multiplies the wrong limbs is refuted. Row 362's ring identity at sixteen
products is stated row by row if the whole is slow, as its plan says.

### 2.5 What is rendered and what is written by hand

The specification side is written by hand, once, and never from `VarkaVectorIR`:

- **`spec/`'s prose** gets the decimal definitions as row 348 begins them (`VARKA-348.md` 3: a
  `DECIMAL(p, s)` is `u / 10^s` with `|u| <= 10^p - 1`, in `STANDARD.md` 4.1 and 4.2; `abs`,
  `greatest` and `least` in `EXTENSIONS.md`), and this row adds the rest Spark's code fixes: the
  result-type rules, `changePrecision`'s `HALF_UP` and its overflow, `MathContext(39, DOWN)`
  before a capped product's rounding, LEGACY's null and ANSI's raise, the casts' rules of 350 and
  364, each with its Spark source line.
- **`decimal_spec.smt2`**, a hand-written prelude of `spec.dec.*` definitions over exact integers
  stating that prose, inserted by `dev/varka_prove.sh` after `java.smt2` into a file whose header
  asks for it, and left out of the script's run list as `java.smt2` is (`dev/varka_prove.sh:358`),
  since a prelude has no checks; and **`decimal_spec_check.smt2`**, rendered by `VarkaProofFiles`
  from Spark's own `Decimal` over boundary operands, as `java_check.smt2` holds `java.smt2` to the
  JVM. So the
  specification is held to Spark and to nothing of Varka's.
- **The lemmas that are not about the kernel** stay in their rows' files: the ring identities and
  ranges of 2.4 (hand), 363's magic constants per `(k, bound)` and 350's `5^k` divisions
  (rendered from constants, as `long_divide.smt2` is), 363's precision-loss lemma, 362's
  shortcut bounds and 359's per-precision constant pairs. A rendered kernel uses a constant
  lemma as `refinement.smt2` uses `long_divide.smt2`: the operator stands for the form, and an
  obligation check shows every dividend fed to it lies in the bound its proof covers.

**Rendered** by the printer: every kernel term, its validity and its declines, each obligation,
and the planted renderings. **Rendered from constants**, as today: the constants in both.

| row | its file | stays there | moves to the rendered refinement |
|---|---|---|---|
| 348, 349 | `decimal64.smt2`; `refinement.smt2`'s hand rows | the rescale bounds, the comparison lemma | the five checks of each 64-bit row |
| 350 | `decimal_casts.smt2` | `5^k` division, limb-magic constants, the pre-shift identity | each cast's refinement, the guards against the precision check, the planted order |
| 359 | `decimal128.smt2` | the constant pair per `p` | the compares, the check, the borrow, negation and `abs` |
| 360 | `decimal128.smt2` | item 0.5's lemma, the per-`P` bounds | carry, borrow, `COMPARE` against `BITS`, sign extension, the guard, the planted order |
| 361, 362 | `decimal128.smt2` | the ring identities, the shortcut bounds, the 16-bit cross-check | the carry layer, the sign addends, the full-width check, the planted carries |
| 363 | `decimal_scale.smt2` | the constants, identities (1) and (2), the lemma | the network of `N * c`, the check, the planted carry |
| 364 | `decimal128_casts.smt2` | the normalisation and `MIDPOINT` lemmas | each cast's refinement over the lowered graph |

Those plans' wording, "written by `VarkaProofFiles` from the code's constants", is flagged and not
edited: for the right-hand column it becomes "rendered by row 506's printer" (point 4).

### 2.6 The refinement over a shape

Per shape and mode the five checks of `refinement.smt2`'s header, in Spark mode: (1) `sat`, a
valid lane the specification defines and the kernel answers; (2) `unsat`, the specification
raises and the kernel does not decline; (3) `unsat`, validity differs; (4) `unsat`, data differs on
a valid lane, in two layers where a product of two unknowns occurs (2.4); (5) `unsat`, a decline
inside the bound where the specification does not raise. The inputs are the specification's
operands, each column's `u` any 128-bit pattern under a validity bit (every pattern is a value
above 18, `VARKA-359.md` 0 item 1), so check 2 covers the load's decline. Spark mode only,
settled by the lead (point 5): row answers follow vanilla Spark, and the SQL standard is only a
register of speedups, of which a decimal's rendered proof has none to record.

**The shape set** is every coverage row of the decimal families, in each mode it fuses in, at its
fixture types; and, per form, the precisions where the lowered graph changes shape rather than a
constant - 18 and 19 across the boundary, 38, and the shortcut's and the capped forms' edges each
plan names - listed in the file's header with the form they reach. A constant that varies with
`p` inside one form is the constant lemma's (2.5). An option arm is rendered when its shape is
compiled under it: the option table's decimal options (`decimalWideSign`, `decimalWideGate`,
`decimalWideMulLow`, `decimalScaleDownAdd`, 350's division arms, 360's carry form) each get one
compiled variant per arm for the coverage rows they reach, so a losing arm stays proven.

**The files**: `decimal_refinement.smt2`, the shapes at the defaults and their option variants,
and `decimal_planted.smt2`, the planted renderings (2.7), both rendered by `VarkaProofFiles` and
held by `VarkaProofFilesSuite`. `refinement.smt2` keeps its integer and calendar rows.

### 2.7 The planted faults

A fault is an argument, not an option: `VarkaDecimalLowering.lower(node, faults)` and the decimal
compiler's test entry point take a set of `PlantedFault` values, empty in every production call,
so no option table, matrix configuration or fuzzer can reach a planted kernel. The renderer calls
the same functions with one fault set and renders `decimal_planted.smt2`; the rows' own planted
tests (their differential suites) pass the same value, so the two catch the one fault.

Each planted entry is three checks: the refutation over free products, `sat`; the same claim pinned
at a witness, the products the limbs' own, `sat` (the spike found free-product models that no input
gives, item 0.12); and the default lowering at that witness, `unsat`. Where the shape has no
product of two unknowns, the refutation is linear and exact and its model is the witness, which
the file prints.

| row | the fault | refuted by |
|---|---|---|
| 350 | the rescale up multiplied before its `10^p2` check (Trino) | check 2 under ANSI, check 3 under LEGACY and TRY, at `184467440737095517` from `(18, 0)` to `(18, 2)` |
| 360 | the same order at class K's guard | check 2 under ANSI, check 3 under LEGACY and TRY |
| 361 | the middle column summed in one word (QuestDB) | check 4's carry layer, witnesses from `results/VARKA-361-planted-carry.txt` (branch `varka-360-361-plan` until it merges) |
| 362 | column 1 summed in one word | check 4's carry layer, a witness of each sign class from `results/VARKA-362-planted-carry.txt` |
| 363 | two products of one column of `N * c` in one word | check 4's carry layer at a `(k, bound)` where the sum can pass `2^64` |

Row 364's three faults stay its suites' (item 0.8). Each planted entry is also a completeness
fact: `VarkaProofFilesSuite` fails if a `PlantedFault` value has no entry, so a fault added later
is rendered or the build says why not.

### 2.8 Solvers, time, and how it runs

Both pinned solvers, Z3 5.1.0 and cvc5 1.4.1, through `dev/varka_prove.sh`, which refuses other
versions. Every check is held to the existing 10 s limit and each file to 300 s. The two new files
run in the linters' job under `--lint`, under the solver that decides each faster, named in
`CVC5_IN_LINT` if it is cvc5, and under both in the nightly (`dev/varka_nightly.sh` today, row
490's workflow once it exists). Their times are stated before and after in each pull request that
adds shapes, against risk 5. If the files push `--lint` past its minute, the shapes run in the
linters' job are one per form and mode, named in the header, and the rest run in the nightly; the
split is a line in the file, not a reduced width.

By hand, on the laptop: `dev/varka_prove.sh --install both` once (none is on `PATH`, item 0.11),
then `dev/varka_prove.sh --solver both decimal_refinement.smt2 decimal_planted.smt2`; the
rendering is `VARKA_PROOFS_REGEN=true build/sbt 'catalyst/testOnly *VarkaProofFilesSuite'` under
`flock /home/max/proj/.varka-locks/heavy.lock` with `SPARK_LOCAL_IP=127.0.0.1`. The gate runs the
same two steps: the suite in the catalyst tests, the solvers in the proof step.

### 2.9 What the printer refuses, and how other shapes opt in

The printer refuses, naming the node and the reason, and the file's header lists each refused
shape: a node with no template and no lowering; a product of two unknowns whose operands are not
limbs; a lowering not built yet (a shape whose row has not landed its lowering); a check that
returns `unknown` at the limit, which is reported with its solver time, not dropped. A refused
shape's coverage row names `differential` or its owing row as its verification (row 490), never
the rendered file.

**Opting in later.** A shape outside the decimals joins by the same three steps: a template or a
lowering for each of its nodes in `VarkaSmtNodes`, its specification by hand in a prelude of its
own, and its coverage rows naming the rendered file. Row 337's `HashStep` is the first candidate
(`VARKA-337.md` 3 item 4 already says its implementation side moves to this printer "once 506's
printer exists and is extended to `HashStep`"); its proof is over bit-vectors where it masks and
rotates, so it needs a bit-vector rendering of the same term tree, which this row does not build.
Row 345's fdlibm ports, emitted as IR nodes, are open to the same rendering over its doubles'
binades; no proof of a math kernel is planned (`VARKA-345.md` 2.2).

### 2.10 Against m9 item 90

The seam is the lowered graph. This row proves that the lowered graph refines Spark's
specification; item 90 would prove that the emitted bytecode computes the lowered graph. This row
reads no bytecode and models no load, store, mask, epilogue or validity buffer: validity is one
boolean per operand. Item 90 states no specification: its reference is the IR's semantics. A carry
the emitter drops while the lowered graph keeps it is item 90's to find (and the differential's);
a carry the lowered graph drops is this row's. Item 90's model of the lane-wise operations is the
same `java.smt2` operators and may take `VarkaSmtNodes`' templates for its IR side; nothing here
waits on it.

## 3. Tests and proofs

1. **`VarkaSmtNodesSuite`** (catalyst, plain JUnit): every template and every lowering evaluated by
   the term tree over `BigInteger` against `VarkaReference` on the IR fuzzer's decimal sequence
   and on boundary words (`0`, `1`, `2^63 - 1`, `2^63`, `2^64 - 1`, limbs of `0` and `2^32 - 1`),
   and a completeness assertion: every record class of `VarkaVectorIR` has a template, a lowering
   or a refusal (row 504's rule for a check that iterates a set).
2. **`VarkaProofFilesSuite`** holds `decimal_refinement.smt2`, `decimal_planted.smt2` and
   `decimal_spec_check.smt2` to what the code renders, and fails when a decimal coverage row is
   neither rendered nor listed as refused, or a `PlantedFault` has no entry.
3. **The validation of the printer on existing shapes** (506a): `integerArithmetic`'s `a + b`, `a -
   b`, `a * 2` and `-a` rendered from their coverage rows' IR in the three kernels, compared term
   by term with the hand-written kernel strings; recorded in section 10, and the hand rows stay.
4. **The planted files fail where they should**: `decimal_planted.smt2`'s refutations expect `sat`
   under both solvers; `dev/varka_prove.sh --self-test` gains a case where a planted entry
   written `expect unsat` fails the run, so a planted file that stopped refuting is caught.
5. **The diff against the hand-written sections** each rendered row replaces (359's and 360's
   `decimal128.smt2` sections, 348 and 349's `refinement.smt2` rows): the claims compared before
   the hand sections are removed, any difference a finding in section 10.
6. **The solver times**, each new file whole and each of its checks alone, under both solvers,
   before and after each pull request, as the README's step 4 asks of a new file.

## 4. The baseline, and predictions

No benchmark: the row moves no emitted byte. The baseline is the hand-written proof each rendered
section replaces, and the bytes oracle: `emitted_bytes.json` must not move in this row's pull
requests, and where a row writes its network as a lowering function (2.1, point 1) its own bytes
are what later changes to the lowering are held to, which is the check that the lowered graph is
the code the emitter builds.

Predictions, registered before the build, priced from the spiked form at VARKA-245's observed
ratio of about 0.8 in the calibration table, as its note says to:

1. **A 64-by-64 shape's carry layer** decides in under a second under each solver in form B, the
   spike's whole file taking 0.06 s and 0.09 s.
2. **A sixteen-product shape** (362) decides its carry layer in form B within the 10 s limit under
   at least one solver; its ring identity is the risk, and is stated row by row if the whole is
   slow.
3. **The two files** fit the linters' minute with every shape at the defaults; the option variants
   may not, and go to the nightly if so (2.8).
4. **The validation of 3 item 3** finds the rendered and the hand-written int kernels equal term by
   term up to naming.
5. **No emitted byte moves** in 506c: 360b writes `WideMul`'s constant-multiplier network as its
   lowering function from the first (point 1's note), and the bytes oracle holds 506c's printer,
   free-product rule and planted entries to the bytes 360b's kernels already emit.

## 5. Order, files and size

The order is the lead's (point 6): 506a and 506b after 359a, 506c after 360b.

1. **506a, the printer**, after 359a: the term tree, `VarkaSmtNodes` over the existing primitives
   and 359's word leaf and unsigned compares, the word form with its intervals, obligations and
   named quotients, the two prelude definitions with their `java_check.smt2` rows,
   `dev/varka_prove.sh`'s prelude rule and self-test case, `VarkaSmtNodesSuite`, and 3 item 3's
   validation. About three days.
2. **`WordConst`, the leaf the lowerings build on**, in 506b, the first pull request that writes a
   lowering function (*settled, the lead, 30 October 2026*: 359a emits its load check from the
   walk as its plan says, and 506b moves the check into a lowering, the emitted-bytes oracle
   holding the move, so a planned row grows no new step). A new IR record reaches every switch
   over the node types (row 489's section 0 lists 43), and rows 431 to 433 found nodes that
   reached the compiler but not the fuzzer or the cost model; so the step carries all of these,
   not the record alone:
   - **the record** in `VarkaVectorIR`, `WordConst(long value, LaneType lane)`, its arms in
     `childrenOf`, `withChildren`, `canonical` and `laneType`, and its `props()` if 489 has landed
     (validity all-true, ring `Never`, weight that of `LiteralSlot`);
   - **the emission**: `loadConstant` as `emitBitCount` does, and the walk's two forms with a
     `WordConst` operand, `lanewise(op, int)` for a shift and `and(long)` for an `AND`;
   - **`VarkaReference`'s arm** and `VarkaRangeAnalysis.over`'s (the point interval);
   - **a production in the IR fuzz grammar**: `VarkaIrGrammar` draws `WordConst` in the long
     shapes as the right operand of a `BitOp` shift (counts 0, 1, 31, 32, 63), of an `AND` (masks
     `2^32 - 1`, `0`, `-1`) and of a `MUL`, and as a `Compare` operand, with its `boundsOf` arm. It
     draws in a side sequence of its row's seed behind a flag the committed draws leave off, as
     `VARKA-352.md` 2.2 item 3 has a new node on an existing lane enter, with a test that the
     committed draws reach none of it, and joins the committed sequences at 352's refit, so no
     committed digest moves;
   - **the cost model**: `VarkaEmitCost.kindOf` names it `WordConst/<lane>` (the switch is
     exhaustive, so the record does not compile without an arm), and it joins
     `VarkaEmitCostSuite`'s awaiting-refit list (`VarkaEmitCostSuite.scala:83`, `ADDING_A_TYPE.md`
     step 13) until 352's refit prices it from the grammar's draws. In production it is never
     counted: grouping prices the IR tree before the walk emits it (the `Tally` built in
     `VarkaLoopEmitter`, `:1330` and `:1486`), and the walk lowers a composite only when it emits
     it, so a `WordConst` exists only after pricing and the composite is priced under its own
     feature. The entry exists for the trees the fuzz grammar draws, which hold the leaf directly.
   Under a day.
3. **506b, after 359a**: `DecimalWord`'s template, the load check's lowering (moved out of the
   walk if 359a emitted it there, the bytes oracle its check), the specification prelude and its
   check file, the prose of 2.5, `decimal_refinement.smt2` for 347's to 349's and 359's shapes,
   the hand sections they replace removed after 3 item 5's diff. About three days.
4. **506c, after 350a and 360b**: `VarkaDecimalLowering`'s `WideMul` lowering as 360b wrote it, the
   free-product rule and the ring lemmas, `PlantedFault`, `decimal_planted.smt2` with 350's and
   360's entries, and 350's and 360's shapes. About three days.
5. **In rows 361, 362, 363 and 364's own pull requests**: each lands its network as a lowering
   function, its shapes rendered and its planted entry, the printer then being its proof's
   kernel side. Their size is theirs; each adds its file's times.

The row is done when the last decimal row's shapes are rendered.

| file | what |
|---|---|
| `varka/VarkaVectorIR.java` | `WordConst`: the record, its structural arms, `props()` after 489 |
| `varka/VarkaDecimalLowering.java` (new) | the composites' lowerings, `PlantedFault` |
| `varka/VarkaVectorWalk.java` | a composite emitted from its lowering; `WordConst`'s constant |
| `varka/VarkaEmitCost.java` | `kindOf`'s `WordConst/<lane>` |
| `varka/VarkaRangeAnalysis.java` | `WordConst`'s point interval |
| `VarkaSmtNodes.java`, `VarkaSmtTerm.java` (test, new) | templates, term tree, printer |
| `VarkaProofFiles.java`, `VarkaRefinementFile.java` (test) | the new files; 348, 349's rows out |
| `VarkaReference.java` (test) | `WordConst` |
| `VarkaIrGrammar.java` (test) | `WordConst`'s production in a side sequence, `boundsOf` |
| `VarkaEmitCostSuite.scala` (test) | `WordConst` on the awaiting-refit list until 352's refit |
| `sql/varka/proofs/java.smt2`, `java_check.smt2` | `jlong.ult` and siblings, `jlong.and.sign` |
| `sql/varka/proofs/decimal_spec.smt2` (hand), `decimal_spec_check.smt2` | the specification |
| `sql/varka/proofs/decimal_refinement.smt2`, `decimal_planted.smt2` | rendered |
| `sql/varka/proofs/spec/STANDARD.md`, `DELTAS.md`, `EXTENSIONS.md`, `README.md` | the prose |
| `dev/varka_prove.sh` | the second prelude, the self-test case, `CVC5_IN_LINT` |
| tests | `VarkaSmtNodesSuite`, `VarkaProofFilesSuite`'s new checks |

**Size: large**, as the row says: about nine days in three pull requests of this row's,
`WordConst`'s step under a day more wherever it lands, and a share in each of rows 361 to 364's.
Test time: `VarkaSmtNodesSuite` is pure evaluation over a few thousand nodes, seconds; the solver
time is the prediction of section 4. It rests on 359a (the word leaf and the unsigned compares),
360b (`WideMul`), 350a and 348's and 349's shapes.

## 6. What it unlocks in the census

No pair moves. It changes how a decimal pair's fused share is known: item 7's post can say each
decimal kernel's proof was rendered from the code that runs, and that the proofs were each shown
to fail on a planted fault.

## 7. Out of this row, and declines that stay

Out: the bytecode against the lowered graph (m9 item 90); a bit-vector rendering, and with it
337's hash (2.9); the integer, interval and calendar rows of `refinement.smt2`, which stay
hand-written in this milestone and could be rendered by the same printer later; standard mode for
decimals (point 5); row 364's suite-caught faults.

Declines that stay, in the rendering: a shape whose node has no template or lowering, a product of
two unknowns that are not limbs, and a check a solver leaves `unknown`, each listed in the file's
header with its reason and its coverage row naming another verification.

**Findings outside the row**, for the lead:

1. **The proofs' README misses Z3's failure mode** (2.3, item 0.12). `sql/varka/proofs/README.md`,
   "Adding a proof" step 4, covers cvc5's `unknown` only. The spike's form A, a network over
   `java.smt2`'s wrapping operators, held Z3 to its two-minute limit on one check while cvc5
   decided the file; a step for that case would say to render each step's unsigned reading, the
   no-wrap claims as obligations and each shift or mask by `2^32` a named quotient (form B).
   Not edited here.

## 8. Open points for the lead, settled

The lead settled points 1, 2, 3, 5 and 6 on 30 October 2026, each recorded under its point with
its reason; point 4 stays for the lead's batch list. The options as they were put are kept.

1. **Where a composite's network is written** (item 0.5, 2.1). (a) A lowering function to
   primitive nodes plus a `WordConst` leaf, which the emitter emits and the printer renders; (b) a
   lowering over a sink interface with a bytecode and an SMT sink; (c) the printer re-states the
   network. *Recommended: (a)*: one source with no second set of meanings, `VarkaReference` able to
   evaluate the lowered graph, the nodes unchanged in the key, the budget and the evaluator. It
   changes how 360b, 361a, 362 and 363 write their networks (in a function, not in the walk) and
   359's load check (a lowered condition, not a walk emission); those plans are settled, so this
   needs the lead's word and probably the owner's. (c) is ruled out by the row itself.

   **Settled: (a), one lowering function per composite node; the emitter emits from it and the
   printer renders from it.** The reason is the row's own premise, "a decimal's words, limb
   products, carries and guards are ordinary IR nodes" (`VARKA-359.md` 2.2), which holds for the
   multiplies only if their networks are nodes; and rows 361, 362 and 363's done-whens already
   commit to a planted fault "refuted by the row's proof as row 506 renders it" (their lines in
   the milestone plan). (c) is excluded by the row. The consequence for the rows that write a
   network: where each writes it moves from emitter code into its lowering function in
   `VarkaDecimalLowering`; the nodes `WideMul`, `PairMul` and `ScaleDown` stay unchanged in the
   shape key, the budget and the evaluator, since the lowering runs inside the emitter, after the
   key. The dated notes those rows need, for the lead to apply after this plan merges (this plan
   does not edit them):
   - **359**: "Note (30 October 2026, `VARKA-506.md` 8 point 1): the load's `10^p` check, `gt128`
     against the constant pair (2.4), is written as a condition graph in
     `VarkaDecimalLowering` over `DecimalWord`, the unsigned compares and `WordConst`, and emitted
     by walking that graph under `emitGuardCollect`, not by the walk's own emission; the first
     pull request with a lowering brings `WordConst` (`VARKA-506.md` 5 item 2)."
   - **360, for 360b**: "Note (30 October 2026, `VARKA-506.md` 8 point 1): `WideMul` with constant
     multipliers is written as its lowering function in `VarkaDecimalLowering`, primitive nodes
     over `WordConst` masks and counts, not as emitter code; the node is unchanged in the shape
     key, the budget and the evaluator, and the class K planted order is a `PlantedFault`
     argument (`VARKA-506.md` 2.7)."
   - **361, for 361a**: "Note (30 October 2026, `VARKA-506.md` 8 point 1): the network of 2.3 and
     the partial-product helper are written as `WideMul`'s lowering function, the masks and shift
     counts `WordConst` leaves so C2 still selects `vpmuludq`; the planted middle-column order is
     a `PlantedFault` argument of that function, which both row 506's rendering and this row's
     differential suite pass."
   - **362**: "Note (30 October 2026, `VARKA-506.md` 8 point 1): `PairMul`'s sixteen products are
     written as its lowering function, calling 361a's partial-product helper there; column 1's
     planted order is a `PlantedFault` argument of it."
   - **363**: "Note (30 October 2026, `VARKA-506.md` 8 point 1): `ScaleDown`'s network of
     `N * c` is written as its lowering function, the constants read from the table as
     `WordConst` leaves; the printer renders the lowered graph, the constants' lemmas stay in
     `decimal_scale.smt2`, and the planted column sum is a `PlantedFault` argument."
2. **Where the node meanings live** (item 0.6, 2.2). (a) A test-side exhaustive switch beside
   `VarkaReference`, held to it on samples; (b) a property of row 489's descriptor, once it
   lands.
   *Recommended: (a)*: 489 is not started, the switch gives the same completeness at compile time,
   and (b) puts proof text in the main source set. The row's text says "beside the per-node
   descriptor of row 489".

   **Settled: the test-side switch now, the meanings moving into 489's descriptor when it
   lands** (2.2). The premise above was wrong: 489's descriptor is not unstarted but built, on 27
   October 2026, on the local, unpushed branch `varka-489-descriptor` (`81f2553ea4e`, item 0.6),
   and not merged. Until it merges the switch is the one statement of each meaning; when it does,
   the templates move beside each record's `props()`, as the row's text asks.
3. **The 64-bit decimal shapes** (item 0.7). The row's opening says "the decimal shapes of rows 359
   to 364" and its done-when "every decimal shape that fuses", and lists 350's planted fault. (a)
   Render 347 to 351's shapes too and retire 348 and 349's hand rows; (b) render 350's alone, for
   its planted fault, and leave 348, 349 and 351 hand-written. *Recommended: (a)*: the 64-bit
   shapes are small, and (b) leaves two ways of proving one family.

   **Settled: (a)**, since the done-when says "every decimal shape that fuses". Rows 348 and 349
   keep their statement that 506 is not their prerequisite: they land with their hand-written
   `refinement.smt2` sections, and 506b retires those sections later, after 3 item 5's diff. No
   gap opens between them and no row waits on another.
4. **The decimal plans' proof wording** (2.5's table): rows 350 and 359 to 364 say their files are
   "written by `VarkaProofFiles` from the code's constants". This plan moves their kernel-side
   claims into the rendered refinement and leaves their lemmas. The plans are not edited here;
   each row's own pull request would say which of its claims the printer renders.

   **Stays for the lead's batch list**; nothing in this plan changes for it.
5. **Standard mode for decimals** (2.6). `refinement.smt2` proves each row in Spark mode and
   standard mode. *Recommended: Spark mode only*: the standard leaves the result's precision to
   the implementation, and answers follow Spark; a standard-mode statement would be a register of
   departures with no speedup to register.

   **Settled: Spark mode only.** Row answers follow vanilla Spark, and the SQL standard is only a
   register of speedups (2.6).
6. **The order against the milestone plan**: the row says "Built before 361 and 362". This plan's
   506a and 506b need 359a, and 506c needs 360b; 361 and 362 then land their lowerings as the
   printer's users.

   **Settled: the real order is recorded**: 506a and 506b after 359a, 506c after 360b (5). Row
   506's line in the milestone plan carries a dated correction of "Built before 361 and 362".

## 9. Done when

Row 506's text holds, with the corrections above: the printer renders the lowered graph of every
decimal shape that fuses into `decimal_refinement.smt2`, over a specification written by hand and
held to Spark's `Decimal` by `decimal_spec_check.smt2`; `VarkaProofFilesSuite` fails when a
rendered file differs from the committed one, when a decimal coverage row is neither rendered nor
listed, and when a planted fault has no entry; the planted faults of rows 350, 360, 361, 362 and
363 are each refuted in `decimal_planted.smt2`, pinned at a real witness; every node has a
template, a lowering or a refusal, held to `VarkaReference`; a shape the printer cannot render is
listed with its reason; the files' solver times are stated before and after under both solvers
against risk 5; no emitted byte moved; and the PR build passes.

## 10. Outcome

### 10.1 506a: the printer's terms, its word form and the IR's meanings (8 November 2026)

*Branches `varka-506a0-prelude`, from master at `99675606f2b`, and `varka-506a-printer` on it;
local commits only. Built on master
alone: 359a's word leaf and unsigned compares are there (#1392, #1400); #1413's `DecimalWords`
comparisons add no IR record, so they render through the templates below when they land.*

**Cut under the size cap, at the seam between printing and meaning.** 5 item 1 came to about
1700 counted lines, so 506a lands in three pull requests, each built and validated:

- **506a0, branch `varka-506a0-prelude`:** `dev/varka_prove.sh`'s second-prelude rule and its
  self-test cases (item 4 below), which nothing else in 506a reads; split off when the review
  round took 506a1 past the cap.
- **506a1, branch `varka-506a-printer`, on 506a0:** the term tree and the word form
  (`VarkaSmtTerm`), the two prelude definitions with their `java_check.smt2` rows, the word
  form's laws (`smt_print_check.smt2`), and `VarkaSmtTermSuite`.
- **506a2, local branch `varka-506a2-words`:** `VarkaSmtNodes` over the primitives, 359's word
  leaf and unsigned compares, its samples and `VarkaSmtNodesSuite` with 3 item 3's validation,
  and the per-tree checks of the printed text against `VarkaReference` under both solvers. It
  lands on 506a1; 506b waits for both.

**What 506a0 and 506a1 build.**

1. **The term tree**: constants, variables of four sorts (an exact integer, an int or long lane
   value, a truth value), applications of SMT-LIB's and the prelude's operators, and a named
   quotient `floor(v / k)`, which renders as a constant pinned by `0 <= v - k q < k` and
   evaluates as the floor. Each operator's evaluation over `BigInteger` is written from the JLS,
   apart from `java.smt2`'s definition of the same name, so the suite and `java_check.smt2` hold
   the two to the JVM separately.
2. **The word form** (2.3): a long rendered by its unsigned reading with an interval; an add,
   subtract, negation, shift left or multiply by a constant exact where the interval stays in
   `[0, 2^64)`, with that claim an obligation, and reduced by a quotient by `2^64` where it does
   not; a logical shift and a low mask one shared quotient by `2^k` and its remainder; `>> 63`
   the sign word, any other arithmetic shift the floor of the signed reading; a signed compare
   over the signed readings and an unsigned one over the readings.
3. **The prelude**: `jlong.ult`, `jlong.ule`, `jlong.ugt`, `jlong.uge`, the compares of the two
   readings modulo `2^64`, and `jlong.and.sign`, `m & y` for a sign word, its range an
   obligation where the word form uses it; `java_check.smt2` gains a check of each against
   `Long.compareUnsigned` and Java's `&` over the boundary longs (34 checks, from 32).
4. **The second-prelude rule** (2.5, 506a0): `; prelude: <name>.smt2` before `set-logic`
   inserts that file after `java.smt2`; the file is then a prelude, left out of the run list and
   refused when named to run, and the name must be a plain file name beside `java.smt2`. The
   self-test holds each case under both solvers; 506b's `decimal_spec.smt2` is the first user.
5. **The term tree's invariants, enforced where a term is built**: a quotient's divisor is a
   positive constant (its pin has no solution otherwise, which would make every check over it
   hold vacuously), a low mask is a `2^k` in the word form as in the evaluation, and a quotient
   is named `q.<n>`, a prefix no variable may take. `div` and `mod` evaluate as SMT-LIB's,
   Euclidean, so a negative divisor renders and evaluates alike.

**Settled from the plan's own rules, not the owner's.**

- **The int lane prints the prelude's operators, the long lane the word form.** 3 item 3
  compares the int kernels term by term with `refinement.smt2`'s `(jint.add a b)`, which a word
  form would not match, and 2.3 states the word form for longs only.
- **An operator the word form has no rule for is read as `(mod t 2^64)` of its prelude form**,
  which is exact, so the word form changes no meaning and refuses nothing; it only keeps that
  wrap for the solver. The plan names the rules and is silent on the rest.
- **`smt_print_check.smt2` declares `ALL`**: under `QF_NIA` cvc5 left the law for a mask plus a
  shift, `(jlong.add (jlong.and.low x 2^32) (jlong.ushr y 32))`, unknown at the 10 s limit, and
  under `ALL` both solvers decide the file; the README's step 4 gives that remedy first.

**Validation.** `smt_print_check.smt2`, 49 checks: for each long operator the word form has a
rule for, at the shift counts (0, 1, 32, 62, 63, 64, 65) and constants its rules branch on, and
nested where one rule feeds another (a mask under a shift, an add, a subtract and a multiply, a
sign word under an AND), so each branch of the intervals is taken (exact, reduced, and a shift
right of a word below `2^63`), the word form's reading equal to `(mod t 2^64)` of the prelude
form for every pair of longs, a compare's word form equal to the compare, every obligation for
every input, and `div` and `mod` equal to the term tree's evaluation on 110 operand pairs with
negative divisors and operands past the long range. Laptop times, whole file: Z3 0.42 s, cvc5
0.20 s; each check alone holds under both, the slowest Z3's 0.24 s for the subtract of a mask
and a shift; `java_check.smt2` Z3 0.03 s, cvc5 0.15 s. `VarkaSmtTermSuite` evaluates every
operator against Java's over 13 boundary longs and shift counts past the mask, the range tests
and the wrap also past the long range, `div` and `mod` against the Euclidean identity, the
invariants above as refusals, and every law term's word form against its prelude form on the
169 pairs of boundary words and random longs, 16,800 rows, obligations included.

**Not vacuous, by planted faults run and reverted:** the word form's unsigned compare printed
over the signed readings fails `VarkaSmtTermSuite` (486 rows, the first `(jlong.ult (jlong.add x
y) y)` at `x = -1, y = 0`) and five `smt_print_check.smt2` checks under each solver (`sat` where
`unsat` was expected, the first "the word form of `(jlong.ult (jlong.add x y) y)` is its prelude
form"); after those `sat` answers both solvers also left the subtract of a mask and a shift
`unknown` at the limit, a check that holds alone in a quarter of a second. `jlong.ult` defined
as the signed `<` fails `java_check.smt2`'s compare check and two `smt_print_check.smt2` checks
(the unsigned compare's law and the `ite` over it) under each solver.

**506a2, as built on its branch.** `VarkaSmtNodes` states `(decline, valid, data)` for the columns,
`DecimalColumn` with its load's decline, `DecimalWord` over a free 128-bit `u` (the low word
`jlong.wrap u`, the high `floor(u / 2^64)`, declining past `10^p - 1`; tying `u` to the
specification is 506b's), literals as the table's constants, `IntArith` and `IntNeg` in their three
modes, the shifts, `AND` with a low mask or a sign word, the nine compares, `IfElse`, the Kleene
`And`, `Or` and `Not`, `IsNotNull`, `<=>`, `Greatest`, `Least`, `GuardedRange`, `CondValue`,
`CondIf`, the constants, `WidenLane` and `NarrowLane` between int and long, and `WidenDecimal`. It
refuses, with the reason, the calendar nodes, the divisions (2.5's lemma-backed operators, 506b's),
a product of two unknowns (506c's free products), `OR` and `XOR`, the floating and narrow lanes and
the condition forms no decimal shape builds. Held to `VarkaReference` on 493 trees, the hand set and
the fuzzer's unsigned and decimal sequences, at 5916 rows, and every record class of the IR rendered
or refused; `smt_nodes_check.smt2` holds the printed text to the same answers in 95 checks, Z3 0.07
s and cvc5 0.21 s; the template planted as a signed compare for `ULT` fails its suite (24 rows) and
its per-tree check under both solvers.

**Findings.**

1. **`year(d) * 2` under ANSI is not the kernel its proof states.** The compiler builds
   `MUL:WRAP` under ANSI, the year's range bounding the product, while `refinement.smt2`'s
   `a * 2` row, which `VarkaCoverageSuite` names as the row's verification, refines the `FAIL`
   kernel. Both answer alike over that range, but the proof is of a kernel the code does not
   build: the transcription gap this row exists for. 506a2's validation states it rather than
   hiding it; the printer cannot close it, the `Year` below being a refused calendar node.
2. **The decline rule is a premise of the templates, not a result.** A guard under an `IfElse`
   arm declines only where the arm is taken, and the samples' oracle states the same rule, so
   the suite cannot see the emitter differ: `Analysis` unqualifies a guard past
   `MAX_ARM_CHAINS` arm chains and on a cycle, declining more. That is the safe direction, and
   506b's check 5 is where it matters.

### 10.2 506b1: the specification, held to Spark (11 November 2026)

*Branch `varka-506b`, from master at `62e1ba84593`, after 506a2 merged as #1477; local commits
only.*

**Cut under the size cap and the order.** 5 item 3 with 5 item 2's `WordConst` is about four
days and well past 1000 counted lines, so 506b lands in three pull requests, each built and
validated, in the order their inputs exist:

- **506b1, this branch:** the arm-loads fix below, the specification prelude
  `decimal_spec.smt2` (hand), its check file `decimal_spec_check.smt2` (rendered from Spark's
  `Decimal`), and the prose of 2.5 that the 64-bit shapes read. Test-side and prose only: no
  emitted byte moves.
- **506b2, left:** `decimal_refinement.smt2` for the 64-bit shapes of 347 to 349, rendered by the
  printer from each coverage row's lowered graph against `spec.dec.*`, 3 item 5's diff against
  `refinement.smt2`'s hand rows, and those rows removed. It needs no `WordConst`: the 64-bit
  column's load check is `DecimalColumn`'s template (10.1).
- **506b3, left:** `WordConst` with every site 5 item 2 lists, the 128-bit load check moved into
  a lowering with the bytes oracle holding the move, and 359's shapes rendered, retiring
  `decimal128.smt2`'s kernel sections.

**The arm-loads fix, a correction to 506a2's templates and to 10.1's finding 2.** The emitter
never qualifies a `DecimalColumn` or `DecimalWord` slot check by an arm (`Analysis.armChainsOf`):
a bad slot under the arm a lane does not take still declines the batch. The `IfElse` and `CondIf`
templates now OR both arms' slot checks into the decline, the samples' oracle states the same
rule, and a hand tree puts a decimal column under an arm; with the arms' checks dropped the suite
fails on it and on drawn decimal trees. Finding 2's "a guard under an arm declines only where the
arm is taken" held for the range guards and not for a slot check; check 5 of 506b2 reads the rule
as corrected.

**What 506b1 builds.**

1. **`decimal_spec.smt2`**, by hand, a prelude asked for by `; prelude: decimal_spec.smt2`
   (506a0's rule): `spec.dec.pow10` for `0 <= k <= 38`; `spec.dec.fits u p`, `|u| <= 10^p - 1`;
   `spec.dec.rescale`; `spec.dec.add` and `spec.dec.sub` at `max(s1, s2)`; `spec.dec.mul`, the
   exact product at `s1 + s2`; `spec.dec.neg` and `spec.dec.abs`; `spec.dec.cmp`, the comparison
   of the rationals as `-1, 0, 1`; and `spec.dec.halfup u k`, a cut by `k` digits rounding half
   away from zero. Each takes the precisions and scales as integers, applied to the shape's
   constants, so a refinement over them stays linear but for a product of two operands.
2. **`decimal_spec_check.smt2`**, rendered by `VarkaDecimalSpecFile`, which reads Spark's
   `Decimal` and `DecimalType` and nothing of Varka's: one check per definition over boundary
   operands (zero, the ties 5, 15 and 150 and their neighbours, the ends of 18 and 38 digits and
   of a long, `2^64`, at scales 0, 2 and 18, each built compact over a long within 18 digits as
   Spark's readers build it, so the long arms of `+`, `-`, `compare` and negation are the ones
   checked, and over a `BigDecimal` past it; a result at another scale than the definition
   states fails the render), `fits` against `Decimal.toPrecision`, the path
   `checkDecimalOverflow` takes, at every precision from 1 to 38, and `halfup` against
   `toPrecision` to a smaller scale.
3. **The prose.** `STANDARD.md` 4.1 states Spark's choices over `DECIMAL` with their sources:
   the result types (`resultDecimalType`), the cut to 38 digits (`adjustPrecisionScale`,
   `bounded`), the product's 39 digits toward zero (`Decimal.MATH_CONTEXT`) and `HALF_UP` at the
   result type (`checkDecimalOverflow`). `DELTAS.md` 1.2 is corrected: VARKA-244's check
   predates the decimal rows, and a decimal result past its type's precision is null outside
   ANSI; `VarkaStandardDeltasSuite`'s test of the delta gains a `DECIMAL(38, 0)` product of
   `10^38`, null and, under ANSI, `NUMERIC_VALUE_OUT_OF_RANGE`, 22003.

**Settled from the plan's own rules, not the owner's.**

- **No result type in the prelude.** 2.5 asks for the result-type rules in the prose; a shape's
  types are Spark's analyzer's, read from its plan, so an SMT definition of them would state a
  constant twice. The prose states the rule, and the rendered refinement takes the plan's types.
- **The product is defined exactly, below `10^39`.** Spark's `*` rounds to 39 digits toward zero
  first; every product of two operands of 19 digits or fewer is below `10^39`, so the exact
  product is Spark's for every shape before the capped forms, and the check file leaves out the
  pairs past it. The capped product's rule is 506c's, with 361 and 362.
- **The casts' rules of 350 and 364 are left** to the pull requests that render those shapes
  (506c, 364): no shape of 506b2 casts.
- **The delta is a correction to 1.2**, whose name states it, rather than a new name: one more
  place where overflow yields null, recorded as a correction since 1.2 said nothing else did.

**Validation.** `decimal_spec_check.smt2`, nine checks over 1874 distinct operand sets: `pow10`
at 40 exponents, `fits` at 264 (every precision, both signs, at scales 0 and `p / 2`), `neg` and
`abs` at 22 each, `add`, `sub` and `cmp` at 432 each, `mul` at 120 (the pairs below `10^39`) and
`halfup` at 110. Laptop times, whole file: Z3 0.09 s, cvc5 0.42 s; the regenerated
`smt_nodes_check.smt2` is unchanged by the cherry-pick onto merged 506a2 and holds, Z3 0.06 s and
cvc5 0.24 s. Each of the nine checks alone holds under both solvers, the slowest cvc5's 0.09 s
for `fits`. Every proof holds under both solvers, `--lint` takes 56 s on the laptop, all files,
and the self-test catches every planted failure. Before the file was written, a scratch rendering of two shapes against the prelude
(`d9 < d9s4`, and `d9 + d9b < d18` under LEGACY, the five checks each) held under both solvers,
and the first with its kernel's factor planted as 10 for 100 was refuted by check 4, so the
definitions serve the five checks as 506b2 will state them.

**Not vacuous, by planted faults run and reverted**, each failing its own check and only it
under both solvers: `halfup` rounding a tie toward zero (`4` for `5` in the half), `fits`
admitting `10^p`, `cmp` comparing the unscaled values with the scales ignored, and `abs`
wrapping as a long.

**Findings.**

1. **`DELTAS.md` 1.2 was false from 349 on** (corrected above): it said no operation of the
   fragment returns null where the standard raises, and 349's LEGACY rows in `refinement.smt2`
   refine exactly that null. No suite caught it, since the deltas' tests check what each delta
   says and not that the list is complete.
2. **506b2 cannot retire every 349 hand row.** `d8 * d9 <= d9b` and `try_multiply(d8, d9) IS
   NULL` multiply two column words, a product of two unknowns that are not limbs, which 2.4's rule
   refuses; so the printer lists those two shapes as refused, and their hand rows in
   `refinement.smt2` and the ring bound in `decimal64.smt2` stay, the conservative reading of 2.5's
   table against 2.4.
3. **The decline rule the templates state is a hand copy of the emitter's** (the review's,
   triaged for 506b2). `Analysis.armChainsOf` leaves a guard unqualified, declining under either
   arm, not only for a decimal slot check but also with `guardUnderArm` off, past
   `MAX_ARM_CHAINS`, on an arm cycle (`unqualifyArmCycles`), for a stored-value guard with
   several chains, and inside a materialized date (`armChainsIn`); the
   templates and the samples' oracle state only the slot case, so a printed decline can be
   smaller than the emitted one. 506b2's check 5 reads it, so 506b2 derives the rule from the
   emitter's own `Analysis` rather than restating it.
4. **The templates' slot-check walk is unshared** (the review's): `loads` re-walks each choice's
   subtree with no visited set, so a CASE chain of `N` arms prints `O(N^2)` terms. The hand and
   fuzzed trees are small; 506b2's shapes are the first where it would show, and a set-based walk
   is that pull request's if they do.

**The review** (`/code-review high`, one triaged round) found ten points; fixed here: the check
file now builds compact operands too and fails on a result at another scale than stated, repeated
equations are dropped (the counts above are distinct), `decimal_spec.smt2` states the exponent
range `halfup` is defined over (a capped product's cut by 39 is 506c's), `DELTAS.md` 1.2's
definition moves the result `HALF_UP` to its scale before the fit and its table row keeps
"outside the fragment", the README lists the two files, and the samples' slot test is a pattern
test rather than a switch with a `default`. Left, as findings 3 and 4 above: the decline rule
derived from the emitter's, and the walk's sharing.

### 10.3 506b2: the 64-bit shapes rendered, their hand rows retired (11 November 2026)

*Branch `varka-506b2`, on 506b1's `varka-506b` (#1493, not merged); local commits only.*

**What 506b2 builds.**

1. **`decimal_refinement.smt2`**, rendered by `VarkaDecimalRefinementFile`: the 43 rows of
   `VarkaCoverageSuite`'s families "DECIMAL columns" (347, 348) and "DECIMAL arithmetic" (349),
   each compiled as that suite compiles a predicate, under an ANSI and a LEGACY session, with
   `refinement.smt2`'s five checks. The kernel is `VarkaSmtNodes`' outcome of the fused root in
   the word form; the specification is read from Spark's analyzed expression, one rule per
   Catalyst node into `spec.dec.*` (a column raising on a valid slot past its precision wherever
   it is read, a cast rescaled up or cut `HALF_UP`, `+`, `-` and `*` exact, each fitted to its
   type, raising under ANSI and null otherwise; the comparisons, `<=>`, null tests, `greatest`,
   `coalesce`, `CASE`, `IN` and `InSet`, each raising only where Spark evaluates it); any other
   node is refused. 42 rows render, 20 alike in both
   sessions and 22 once per session: 320 checks, no word-form obligation arising. The header lists
   the one refused row.
2. **The coverage rows name it**: `rendered: decimal_refinement.smt2`, a new kind with its own
   column in the documentation's counts, failing a row of either family the file does not echo.
   347's equal-scale rows, differential until now, are refined for the first time.
3. **The arm rule read from the emitter (10.2 finding 3, fixed).** `VarkaSmtNodes.kernel`
   declines on each node `VarkaLoopEmitter.guardsForTest` reports, its own check on the lanes
   some `Analysis.armChainsOf` chain selects, or everywhere where it has none, and refuses a
   template check the emitter does not make; a stored-value guard (VARKA-379) reads null rows.
   `armChainsIn`'s materialized date sits only under calendar nodes, which are refused. The
   samples' oracle reads the same guards, and a template is back to its own rule.
4. **The slot-check walk is gone (10.2 finding 4, fixed)**: `loads` is removed, and `kernel`
   visits each node once.

**The diff of 3 item 5.** A scratch file paired each of the 31 retired hand rows (348's 15,
349's seven in both modes, `try_add`, `try_multiply`) with its rendered row and asked Z3 whether
any claim differs where it is read: all 155 queries `unsat`, so the hand rows were right. Not
committed: it read the text it retires.

**Hand-written still**: `d8 * d9 <= d9b`, a product of two columns (2.4, 506c's);
`try_multiply(d8, d9) IS NULL` renders, its kernel reading only validity (against 10.2 finding 2).

**Validation.** Laptop: `decimal_refinement.smt2` Z3 0.47 s, cvc5 0.55 s; `refinement.smt2` 381
checks to 226, Z3 2.96 s to 2.94 s, cvc5 4.22 s to 4.13 s; every proof holds under both, `--lint`
45 s; the four catalyst suites pass. Planted on the rendered text, each failing its own check under
both: `d9 < d9s4`'s factor 10 for 100 (check 4), a slot check of `d9 + d9b < d18` dropped (check
2), the specification's rescale one digit short (check 4).

**The review** (`/code-review high`, one triaged round): fixed `IN`'s raise to the items Spark
reads, refusals in place of aborts, the converse refusal in `kernel`, sorts from the terms, the
docs and row 506; left the copied fixtures (the coverage suite's are private Scala), a widening
cast's ANSI fit printing 22 rows twice, and the last hand row's dead `try_` branch.
