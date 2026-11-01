# VARKA-540: The scalar form of a 128-bit decimal where the lane pair is slow

## 0. What exists now (admission check)

From the plans worktree at `130280562f3` (master `5c660201b35` plus plan commits), the branches
`varka-359a-plan`, `varka-359-laptop-reading` and `varka-503-two-lane-decline`, and the committed
EPYC 7763 results named below, read on 1 November 2026 with `git` and `gh api` GETs. Nothing was
dispatched and no JVM was run. Varka paths are under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/`.

1. **The verdict is in: build the pair P.** The laptop's reading (`git show
   varka-359-laptop-reading:sql/varka/plans/m8/VARKA-359.md`, section 10 part 1) applies the 359a
   plan's decision rule (`git show varka-359a-plan:sql/varka/plans/m8/VARKA-359.md`, section 11
   part 3) and finds neither A nor S ahead of P by 1.3x at 256 or 512 bits on either host. At 128
   bits on x86 both vector layouts are the Java fallback, and the 359a plan's section 11 part 4 left
   that case to "a row of its own" (its section 11 part 8 point 3). This is that row.
2. **What S costs at 128 bits, from the committed EPYC file.**
   `sql/catalyst/benchmarks/VarkaDecimalPairSpikeBenchmark-jdk25-runner-epyc7763-128bit-results.txt`
   (`MaxVectorSize=16`, run 41264904788) gives, in ns a row in L3, P / A / S: the load and its
   `10^38` check 137.5 / 67.0 / 1.8; `x < y` into a selection word 168.7 / 44.7 / 5.9; `-x`
   stored 143.4 / 67.4 / 0.8; `abs(x)` stored 165.8 / 119.1 / 4.3; and `CASE WHEN x < y THEN x
   ELSE y END` stored 235.8 / 89.9 / 5.5.

   Past L3 the S column reads 1.9, 6.3, 1.3, 4.4 and 6.2. The 359a plan's section 11 part 1 item 4
   reads the L3 and past-L3 rungs, not L2, because the L2 rung's 4096 rows train S's branches.
3. **What the row path costs there.** `VarkaDecimalBaselineBenchmark`'s 128-bit file
   (`sql/core/benchmarks/VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-128bit-results.txt`)
   at `DECIMAL(38, 10)`: the comparison filter 372.3 ns a row and the choice
   390.4, Varka on and off tied since nothing above precision 18 fuses. So S's loops sit 60 times
   or more under the row path on this host, while P's fallback is under it by less than 2 times
   for the choice.
4. **Row 503's rule refuses a decimal filter before any form is chosen.** On the branch
   `varka-503-two-lane-decline` (unmerged, not yet in the table),
   `VarkaKernelPlan.hostRefusal` refuses every condition root of a kernel driven at the long lane
   when the session's `declineTwoLaneConditions` is set, which defaults to "x86 and two lanes in
   the preferred long species" (`VARKA-503.md` 11 item 1 on that branch). A pair kernel is
   long-driven (`VARKA-359.md` 2.2), so a precision-38 filter declines whole on that host however
   its words are computed. S cannot reach a filter until the refusal learns that a kernel whose
   long-lane work is all scalar makes no long mask.
5. **No emission mode is scalar today.** `varka/VarkaLoopEmitter.java:124` documents "the
   epilogue, not a scalar tail": the rows past the vector bound run as one more masked vector
   iteration. Every IR node lowers to `Vector` calls. A per-row loop inside a kernel is new in the
   emitter.
6. **Row 538's host reading is merged** (#1255): `VarkaHostIsa.Reading`, read once on the driver,
   resolved by `VarkaSessionEmit.of(conf, host)`. Its 2.2 kept the host out of
   `VarkaEmitOptions`, so that one class name never holds two bodies; the resolved choice is an
   option value the shape key renders, the host is not.
7. **What 359a and 359b build, which S reuses**: the registry's `Read` and `Storage`
   (`VARKA-359.md` 2.3), the compile-time `Pair(lo, hi, type)`, the literal pair and the sign
   extension (the 359a plan's section 11 part 5), and the reference evaluator and proof file over
   values, which are layout-free. The word leaf `DecimalWord` and the word lowerings of 2.5 are P's.

### What differs from the row text

- The row says S is "an emission mode chosen per host on the driver, as the two-lane rule and row
  538 choose theirs". The two do it differently. 503 decides which conjuncts are admitted and moves
  no emitted byte; 538 picks a form, and a form changes the bytes. S changes the bytes, so it is
  538's pattern: an emit option whose default resolves on the driver from the host reading, and
  which the shape key renders, so P and S are two classes. It is not a session flag beside 503's.
- The row's done-when says "row 359's comparisons and values fuse through the scalar form". The
  comparisons are filter conditions, which 503 refuses on that host by drive lane (item 4). They
  can fuse only if the refusal exempts a kernel with no long mask (question 1).
- The row names "the operations row 359 fuses". That is 359a's and 359b's lists: the six
  comparisons, `<>`, `<=>`, `IN`, the null tests, the choices, negation and `abs`. The arithmetic
  rows of 2.8 (360 to 363) are not in it, though the spike timed S's product and rescale too.

## 1. Goal and non-goal

**Goal.** On an x86 host at two long lanes, every form row 359 fuses at precision 19 to 38
fuses through a scalar per-row loop inside the kernel, with answers equal to Spark's, and each is
faster than the row path on the EPYC 7763 at 128 bits, or stays declined with the measured
reason.

**Non-goal.** No change to P at 256 or 512 bits, or on aarch64 at any width (the Neoverse N2's
cells are all intrinsic, `VARKA-359.md` 2.6). No arithmetic (360 to 363): their own plans take
the spike's arithmetic verdict. No A layout. No scalar form for any other type: a long, date or
floating filter at two lanes stays 503's and 543's.

## 2. Design

### 2.1 The option and its resolution

`VarkaEmitOptions` gains `decimal128Form`, `PAIR` or `SCALAR`, rendered in the shape key only
when it is not the default, as `useAVX` is. Its session default is `AUTO`, resolved by
`VarkaSessionEmit.of(conf, host)` on the driver: `SCALAR` exactly where 503's
`VarkaKernelPlan.twoLaneMasksFallBack(host)` is true, `PAIR` otherwise, so the two rows can never
disagree on what the host is. An
internal conf `spark.sql.codegen.varka.decimal128Form` (`auto`, `pair`, `scalar`, binding policy
`NOT_APPLICABLE` as 503's) overrides it. Every Varka SQL test session pins `pair`, as row 538
pins `useAVX=-1`, so the committed oracles stay host-blind; the option matrix gains `scalar` as
an arm on every host, which keeps S tested at 256 bits too, as the rule on keeping losing arms
asks.

### 2.2 The IR: a scalar subtree, not a scalar kernel

The pair compiler (359a's `Pair` record) already decides, per decimal subtree, which long-lane
nodes stand for the words. Under `SCALAR` it emits instead one node,
`DecimalScalar(Op op, List<Operand> operands, Output out)`:

- `op` is one of 359's forms (compare, `<=>`, `IN`, null test, choice, negation, `abs`), its
  operands the decimal columns' ordinals and long-table slots of the literal pairs;
- `out` is either a selection word (a condition: one bit a row, the long kernel's mask word) or a
  pair of scratch long arrays (a value: low and high words a row, for the store);
- the loop runs over the lane group's rows, reading `lo` and `hi` at `16 * i` from the Arrow
  buffer as the spike's S loop does, the `10^p` check folded into the same loop (a bad slot sets
  the guard status bit, as 347's load does), and nulls from the validity word.

A condition's selection word meets the rest of the kernel where every condition's does: the
filter's selection is a word per lane group (`VarkaBodyEmitter.pushKnownTrue`). So a scalar
conjunct beside a date conjunct joins as a word `and`, and nothing in the kernel makes a
two-lane long mask. A value's scratch arrays feed the decimal store directly: S writes the
sixteen-byte slot from `lo` and `hi`, no zip.

The alternative is a whole-kernel scalar mode (every node of a kernel with a decimal pair lowered
to scalar code). It is rejected because it changes every node's lowering for one type, while the
subtree keeps every other node as it is (question 2).

### 2.3 503's refusal learns the scalar subtree

`hostRefusal` refuses a condition root of a long-driven kernel. A kernel whose only long-lane
content is `DecimalScalar` nodes reads no long vector and makes no long mask; its drive lane is
then not the long lane. `VarkaKernelPlan.emissionLane` treats `DecimalScalar` as lane-free, as a
validity-only use of a narrow column already is (`narrowRefusal`'s `needsNarrowSpecies` case), so
such a kernel drives at the int lane, and 503's rule, keyed on the drive lane, admits it
unchanged. A kernel that also holds a long column's compare stays long-driven and keeps 503's
refusal (question 1).

### 2.4 Reference, cost, warm-up, proofs

The reference evaluator evaluates `DecimalScalar` over `BigDecimal`, as it does the pair. The cost
model gets one feature (rows times operands) on row 352's awaiting-refit list; the budget a
weight. The warm-up gains a scalar block over a sixteen-byte buffer at each `op`. The proofs:
the ring layer of `decimal128.smt2` holds for S unchanged, since it is over values; the carry
layer is P's, and S's compare is `Long.compare` on the high words then `Long.compareUnsigned` on
the low, the same formula, so the file gains a section that renders S's loop body from the code's
constants and proves it equal to the integer comparison.

## 3. Tests and proofs

1. **The pair's tests run under `SCALAR`.** 359a's slot table and word edges are parameterised by
   `decimal128Form`, so each row of them runs both forms: at 2, 4 and 8 long lanes, precision 19,
   20, 30 and 38, every null pattern, the sign-bit low words and `lo = 0` borrows.
2. **The refusal.** `VarkaTwoLaneConditionSuite` gains a scalar decimal conjunct admitted on the
   two-lane x86 host, a long compare beside it still refused, and a pair decimal conjunct (the
   option pinned `pair`) still refused.
3. **The host resolution.** `VarkaEvaluatorComponentsSuite`: `AUTO` resolves `SCALAR` for a
   planted x86 reading at two long lanes, `PAIR` for aarch64 and for four lanes; the conf beats
   both. `VarkaTwoLaneHost128Suite` (own JVM under `-XX:UseAVX=2 -XX:MaxVectorSize=16`) holds the
   default on the real host.
4. **Coverage rows** under the option arm: 359's "DECIMAL lane pair" family runs under `scalar` in
   the coverage differential, every row naming `differential` and the SMT section.
5. **The bytes.** `emitted_bytes.json` gains the `scalar` rows; no `pair` row and no existing shape
   moves. `width_audit.json`'s 128-bit column: the decimal filter rows move from 503's decline to
   fused.
6. **Planted faults** for row 504: the low-word compare signed (the word edges' sign-bit row and
   the SMT section catch it), the check's bound at `10^p` (the slot table), the loop's stride 8
   instead of 16 (every row).

## 4. Measurement, predictions and costs, registered before the build

**The measurement.** `benchmark.yml` on the EPYC 7763 with `extra-java-options:
-XX:MaxVectorSize=16`, expected CPU pinned, `pair` input set to master:
`VarkaDecimalBaselineBenchmark` at `(38, 10)` and `(20, 2)`, every section 359a and 359b fuse, its
row-path arm the control. A second run at 256 bits with the conf forced `scalar` gives S's cost
where P wins, for the record. No laptop number is a verdict.

Predictions, scored in section 9:

1. At 128 bits on the EPYC, each comparison filter at `(38, 10)` is 20 to 50 times faster than
   the row path's 372.3 ns a row
   (`VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-128bit-results.txt`). The spike's S compare
   is 5.9 to 6.3 in L3 and past it
   (`VarkaDecimalPairSpikeBenchmark-jdk25-runner-epyc7763-128bit-results.txt`); the kernel adds the
   batch, the null word and the output.
2. The choice at `(38, 10)` is 20 to 50 times faster than the row path's 390.4 ns a row (the same
   baseline file).
3. With no nulls, the `IS NULL` filter at `(38, 10)` is within 1.3x of the row path, which
   reads 5.4 ns a row there because it builds no `BigDecimal` (the same baseline file; the 359a
   plan's section 11 part 1 item 5). If S is slower, that section stays declined on the host with
   the measured reason; with one row in ten null it wins, as the other sections do.
4. At 256 bits, S forced is slower than P by 1.3x or more on every comparison section, as the
   spike's 256-bit column shows (the 359-laptop reading's section 10 part 1).
5. No existing shape's bytes move; `option_arms` gains the `scalar` arm's digests.

**Costs.** The parameterised slot table and word edges double 359a's (that plan's section 11 part 7
priced them under 10 s); the doubled set is under 20 s, stated and measured. The proof section is a
handful of linear checks, under 2 s a solver at VARKA-245's observed ratio in the calibration
table. The option arm adds the decimal family's coverage rows once more to each matrix bin that
draws it.

## 5. Order, files and size

After 359a and 359b merge, and after 503 merges (S's refusal change edits 503's code).

1. The option, its conf, its resolution and the test sessions' pin; no node yet, no byte moves.
2. `DecimalScalar`: the IR record, canonical form, parser (row 502's round trip), reference,
   emitter, warm-up, budget and cost feature, behind the option.
3. The compiler arm under `SCALAR`, the drive-lane change of 2.3, the tests of 3.
4. The measurement and the verdict; forms that lose stay declined with their reason; the
   generated files in `ADDING_A_TYPE.md`'s order; the default `AUTO` live in the last commit.

| file | what |
|---|---|
| `varka/VarkaEmitOptions.java`, `SQLConf.scala`, `VarkaSessionEmit.java` | option, conf |
| `varka/VarkaVectorIR.java`, `VarkaCanonicalIr`, reference | `DecimalScalar` |
| `varka/VarkaBodyEmitter.java`, `varka/VarkaVectorWalk.java` | the per-row loop, word out, store |
| `varka/VarkaKernelPlan.java` | `emissionLane` for a scalar subtree |
| `VarkaDecimalCompiler.java` | the `SCALAR` arm |
| `varka/VarkaEmitBudget.java`, cost, `VarkaVectorTemplateWarmup.java` | weight, block |
| `sql/varka/proofs/decimal128.smt2`, `VarkaProofFiles.java` | the S section |
| tests | 3's list |
| generated | `emitted_bytes.json`, `width_audit.json`, `coverage.json`, `docs/sql-varka.md` |

Size: **medium**, one pull request, about three days: the node and its loop about one and a half,
the compiler arm and refusal half a day, tests and measurement one.

## 6. Out of this row

- S for 360 to 363's arithmetic: their plans read the spike's arithmetic verdict and may ask for
  it, as an arm of this option.
- A two-lane x86 host whose executors differ from the driver: 503's risk 5 and `m9/SCOPE.md`
  item 127 (choosing CPU-dependent forms on each executor), which applies to this option as to
  538's.
- Scalar forms for other types at two lanes.

## 7. Questions for the owner

1. **How S passes 503's refusal.** (a) A kernel whose long-lane content is all `DecimalScalar`
   drives at the int lane, so 503's drive-lane rule admits it unchanged (2.3). (b) Re-key 503's
   refusal on "the kernel makes a long mask", which 503's review tried and reverted (its 10 item
   8). *Recommended: (a)*: it changes the drive lane of a node that makes no vector at all, and
   leaves 503's measured rule as it is.
2. **The node's scope.** (a) A scalar subtree node feeding the kernel through a word or scratch
   arrays (2.2). (b) A whole-kernel scalar mode. *Recommended: (a)*: other nodes keep their
   lowerings, and a date conjunct beside a decimal one stays vectorised.

Not a question: `scalar` stays an option-matrix arm on every host, a losing arm where P wins, by
the standing rule to keep losing option arms; its matrix cost is one family's rows.

## 8. Done when

At two long lanes on x86 (the EPYC 7763 under `-XX:MaxVectorSize=16`), every form 359a and 359b
fuse fuses through `DecimalScalar` with answers equal to Spark's, held by the slot table, the word
edges and the coverage differential under both forms; the SMT section proves S's compare; the
EPYC measurement is committed with section 4's predictions scored, and each form either beats
the row path there or stays declined with its measured reason; `emitted_bytes.json` moves no
`pair` shape; the PR Build and an option-matrix dispatch pass.

## 9. Open points for the lead

1. Row 503 has no row in the table yet, and 540, 543 and 359a lean on it. Its merge comes first.
2. 359b's measurement at 128 bits (`VARKA-359.md` 2.6) would measure P's fallback for the
   projections, which this row replaces by S on that host. 359b may skip the 128-bit projection
   verdict and leave it to this row.
3. `m9/SCOPE.md` item 101 (two-lane long masks) and this row both answer "x86 at two lanes" for
   their types. The item's note could name this row as the decimal answer.

## 10. Outcome

(Written when the work lands.)
