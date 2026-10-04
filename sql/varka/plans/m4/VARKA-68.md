# VARKA-68: year-month interval algebra

## 1. Where this came from

`m4/PLAN.md` row 68 and section 2.33, which scoped the expressions that
*produce or transform* an interval once VARKA-63's int32 arithmetic and VARKA-67's
type admission were in. Both now are: 63 merged with the arithmetic nodes and
their evaluation modes, and 67 admitted `YearMonthIntervalType` end to end as a
value leaf, a literal, an `add_months` month count and two relabel casts.

VARKA-67 also left two shapes on this task's doorstep and said why
(`VARKA-67.md` 2.1): `d - ym_col` and the `YEAR`-unit cast in month-count
position, neither blocked on arithmetic, both blocked on the emitter's
month-count check.

## 2. The admission check, done

Read against master (`3934a8764b8`), with the ranges computed rather than
asserted. The check divides section 2.33's list in three, and the division is
not the one that section drew.

### 2.1 What Spark actually computes

| expression | definition | mode |
|---|---|---|
| `make_ym_interval(y, m)` | `toIntExact(addExact(m, multiplyExact(y, 12)))` | always checked |
| `ym + ym`, `ym - ym` | `IntervalMathUtils.addExact` / `subtractExact` | always checked |
| `-ym` | `IntervalMathUtils.negateExact` | always checked |
| `abs(ym)` | `IntegerExactNumeric`'s abs, whatever `failOnError` says | always checked |
| `ym * num` | `Math.multiplyExact(months, num)` for an int-family `num` | always checked |
| `ym / num` | `IntMath.divide(months, num, HALF_UP)` | rounding, not truncation |
| `extract(YEAR FROM ym)` | `months / 12`, Java division, an `IntegerType` | truncating |
| `extract(MONTH FROM ym)` | `(months % 12).toByte`, a **`ByteType`** | sign follows dividend |

"Always checked" is the useful half: none of the first five has a `LEGACY`
wrapping form, so each is VARKA-63's `FAIL` mode unconditionally, and the
compile-time bound is the only thing that can take the check off.

Three details of that table decide shapes later in the plan:

* **`abs` is not an op the IR has.** VARKA-63 shipped `IntArith` and `IntNeg`;
  `abs(x)` lowers as `IfElse(Compare(LT, x, 0), IntNeg(x), x)`, a blend over the
  negate. The only input that overflows a negation is `Int.MinValue`, which is
  negative and so takes the `IntNeg` arm - the check fires exactly where Spark
  throws. It is a checked node under a `CASE` arm, which is VARKA-79's shape, and
  it is deliberate.
* **`ym * num` takes any `NumericType`**, not only a literal:
  `MultiplyYMInterval.inputTypes` is `Seq(YearMonthIntervalType, NumericType)`,
  with arms for Byte/Short/Int, Long, Decimal and Double. An int literal is
  bounded and the check comes off; an int *column* is an unbounded checked
  multiply and declines, as VARKA-63 declines every such multiply; Long, Decimal
  and Double are not int32 lanes and decline with a reason naming the type.
* **`extract(MONTH FROM ym)` returns a byte.**
`ExtractIntervalPart[Int](ByteType,
  getMonths, ...)`. Varka has no byte lane and `allocateVector` has no `ByteType`
  arm, so this expression is un-emittable regardless of how the division is
  done - a second blocker beside the one 2.2 finds, and one that a better
  division does not remove.

### 2.2 The two divisions need what VARKA-26 was built without

`extract` and `ym / num` need division by a constant. `VectorOperators` has no
multiply-high on any lane type - the absence `VarkaChrono`'s header names, that
`m5/PLAN.md` 2.7 re-checked during VARKA-32 and found is not temporary
(no `MUL_HIGH` in JDK 25 or openjdk master; JDK-8219881 Open at P4 since 2019) -
so a full-range Granlund-Montgomery magic is not expressible on int lanes. What
is expressible is a round-up magic whose product must stay inside a signed int
lane, and that bounds the dividend hard.

Computed, not assumed - for each divisor, the best `M`/`k` and the largest
dividend the pair is exact over:

| divisor | M | k | exact for |
|---|---|---|---|
| `/12` | 43691 | 19 | 0..49,151 |
| `/3` | 43691 | 17 | 0..49,151 |
| `/5` | 26215 | 17 | 0..43,690 |
| `/7` | 18725 | 17 | 0..43,690 |
| `/100` | 5243 | 19 | 0..43,690 |
| `/2`, `/4` | 1 | 1, 2 | 0..2,147,483,647 |

The `/12` row is `MONTH_ARITH_M` exactly - the emitter already has this magic,
and VARKA-60 already carries the runtime guard that keeps its dividend inside the
range. So `extract(YEAR)` does not need a new magic; it needs the *same* magic
over a dividend the type does not bound. A year-month interval is a full int32
month count, and 49,151 months is about 4,096 years: the magic covers roughly
one forty-thousandth of the type's range.

Powers of two are the exception and are exact over all of int32, which matters
for `ym / num` with a literal power of two: no magic at all.

### 2.3 Truncation is not floor, and the difference is only on negatives

The magic computes a floor. Java's `/` truncates toward zero, and
`extract(YEAR)` is Java's `/`:

| months | Java `/12` | floor | Java `% 12` |
|---|---|---|---|
| -13 | -1 | -2 | -1 |
| -12 | -1 | -1 | 0 |
| -1 | 0 | -1 | -1 |
| 13 | 1 | 1 | 1 |

So an `extract` built on the existing magic needs a correction on the negative
side, and `extract(MONTH)` inherits it, being `months - 12 * q` for whichever
`q` the year extract produced. VARKA-60's use of this magic does not need the
correction, because `add_months` wants the floor and biases its dividend
non-negative to get it. That bias is why one constant serves two callers that
disagree about rounding, and it is worth saying where the correction goes rather
than discovering it from a differential.

### 2.4 `ym / num` rounds HALF_UP, which a magic does not give

`IntMath.divide(months, num, HALF_UP)` rounds to the nearest, ties away from
zero: `-5 / 2` is `-3`, not `-2`. A quotient from a magic is a floor; HALF_UP
needs the remainder as well - `q` adjusted by one where `2 * |r| >= |num|`, in
the direction of the quotient's sign. That is three more lanewise ops on top of
a magic that is already range-bounded, and the bound is per-divisor (2.2's
table), so a guard's range would have to be computed per literal rather than
being one constant.

### 2.5 What VARKA-67 left, and which half of it is about the emitter

`d - ym_col` resolves to `DateAddYMInterval(d, UnaryMinus(ym))`, so its month
count is an `IntNeg`. The `YEAR`-unit cast is a `12 *` over its operand, an
`IntArith`. Where either sits in `add_months`' **month-count position**, the
emitter's `requireOffsetShape` admits a literal slot or a column and nothing
else, so admitting them in the compiler alone would be a ghost fallback. That is
the blocker, and it is an emitter check, not arithmetic.

The `YEAR`-unit cast in **value position** - `SELECT CAST(i AS INTERVAL YEAR)` -
has no such blocker: it is an `IntArith` `MUL` with an interval output, the
same shape as `ym * num`, and belongs with group A. VARKA-67's 2.1 was written
about `compileMonths` and did not draw this line; this plan does.

`VARKA-67.md` 2.1 also recorded, and this task acts on it, that the stated
reason for the month-count strictness - "a weekday and a month count carry
runtime bounds a derived value cannot declare" - is true of `next_day`'s weekday
and false of the month count. A column-count `AddMonths` is in `selfGuarding`
and is guarded at run time by a lanewise check on the count's *value*, which
does
not care what produced it; a derived count is covered by that same guard. The
two positions share a check and do not share the reason, and splitting them is
this task's.

### 2.6 What the check would have rejected

That `extract` is "a literal-divisor magic multiply" and therefore small (it is
the existing magic over a dividend forty thousand times its exact range, and
`extract(MONTH)` is un-emittable regardless); that the magic's rounding matches
Java's (it is floor, `extract` truncates); that `ym / num` is the same shape as
`extract` (it rounds HALF_UP and its exact range depends on the divisor); that
`abs` is an op (it is a blend over `IntNeg`); that `ym * k` takes a literal (it
takes any numeric); and that the shapes VARKA-67 deferred were waiting on VARKA-63
(they are waiting on an emitter check this task owns, and only in one position).

## 3. The design

### 3.1 The split the check found, and what this task takes

Section 2.33 lists eight things as one task. 2.1 to 2.5 divide them by what they
need from the emitter, and the division is sharp:

**Group A - no new machinery.** `make_ym_interval`, `ym + ym`, `ym - ym`, `-ym`,
`abs(ym)`, `ym * num`, and the `YEAR`-unit cast in value position. Every one is
VARKA-63's `IntArith`/`IntNeg` - `abs` as a blend over `IntNeg` - in `FAIL` mode
with an interval-typed output, over operands VARKA-67 already admits. The
compile-time bound applies unchanged, so `make_ym_interval(year(d), 3)` needs no
check while `ym1 + ym2` over two columns does. No IR node, no emitted byte that
VARKA-63 did not already emit.

**Group B - a bounded division and a guard.** `extract(YEAR|MONTH FROM ym)` and
`ym / num`. Both need a magic whose exact range is a fraction of the type's,
both
need a rounding correction the existing magic does not carry, `ym / num` needs
its range computed per literal, and `extract(MONTH)` needs a byte output the
evaluator does not have.

**Group C - one emitter check.** `d - ym_col`, and the `YEAR`-unit cast in
month-count position, blocked on `requireOffsetShape` (2.5).

**This task takes A and C.** B is milestone 5's VARKA-89, and 3.4 says why that
is not merely a size argument.

### 3.2 Group A, as arms

The compiler's arithmetic arms are gated on `dataType == IntegerType`
(`case a: Add if a.dataType == IntegerType`), which an interval-typed `Add` does
not satisfy. Widening that gate is wrong - int arithmetic is an int-typed
concept, and VARKA-67 declined to widen `UnaryMinus`' for the same reason - so
each arm gains an interval sibling that builds the same node with the same mode
and an interval `outputTypes` entry. `IntervalMathUtils`' `addExact` /
`subtractExact` / `negateExact` are Spark's own definitions and are checked in
every mode, so the mode is `FAIL` unconditionally rather than read from
`evalMode`.

`abs(ym)` is the blend of 2.1: `IfElse(Compare(LT, x, 0), IntNeg(x), x)`, with
`intBound` of the result equal to `intBound` of the operand, so the check comes
off wherever it would for a negation.

`ym * num` takes the int-family `num` arms only. A literal is a slot; an int
column is `intOperand`'s column and declines as an unbounded checked multiply,
exactly as `i * 3` under ANSI does; Long, Decimal and Double `num` decline with
"interval multiplier of type <t> is not an int32 lane".

`make_ym_interval(y, m)` is `m + 12 * y` with both parts checked, which is
two of
VARKA-63's nodes composed; `intBound` proves the check away over bounded
operands,
which is what makes `make_ym_interval(year(d), month(d))` fuse with no check at
all.

The `YEAR`-unit cast in value position is `IntArith(MUL, FAIL, x, 12)` with an
interval output - the arm VARKA-67 wrote, reverted before it compiled, and
recorded in its 2.1 as a ghost fallback. It was a ghost fallback *in month-count
position*; in value position there is no emitter check to drift from, and it
lands here.

### 3.3 Group C, as one emitter check split in two

`requireOffsetShape` serves two positions with different runtime-guard
situations, and this task separates them: `add_months`' month count keeps a
check that admits a literal, a column, *or* VARKA-63's arithmetic - because VARKA-60's lanewise guard covers a derived value - while `next_day`'s weekday keeps
the
strict pair, because its bound is a compile-time fold with no runtime guard
behind it. The comment that currently conflates them is corrected with the
split.

With that, `d - ym_col` is a `compileMonths` arm over `IntNeg`, and the
`YEAR`-unit cast in month-count position is the same `IntArith` as its
value-position twin, admitted by the widened check and guarded on its value by
VARKA-60.

### 3.4 Why group B is its own task and not this one's tail

Two reasons, and the second is the one that matters. It is a different kind of
work - a guard, a range table, a rounding correction and a byte output, against
group A's arms - and this project's rule is that a new node type or a new guard
earns its own admission check and its own A/B.

But also: **group B is the task that milestone 5 may delete, by either of two
routes.** `m5/PLAN.md` 2.7 (VARKA-65) establishes that widening the
dividend to int64 lanes makes the magic exact with a single 64-bit low product,
no range restriction and no correction carries. And this plan's own admission
check found a second route, now `m5/PLAN.md` 2.19 (VARKA-88): an exact
division through *double* lanes, `trunc((double) v * (1.0 / d))`, exact for
every int32 dividend and any divisor below about 2^21, with no magic, no
correction, no range restriction and no int64 lane. Whether it is *fast* is a
three-arm A/B, which is VARKA-88's admission check.

Building the int-lane version of group B now means building the thing either
route exists to remove, and then owning both. So group B is
`m5/PLAN.md` 2.20 (VARKA-89), with VARKA-65 and VARKA-88 named as the routes
it waits on and the `ByteType` output named as the blocker neither removes.

## 4. Files

| file | what |
|---|---|
| `VarkaExpressionCompiler.scala` (+ suite) | the interval siblings of the `Add`/`Subtract`/`Multiply`/`UnaryMinus` arms, `Abs` as a blend, `make_ym_interval`, the value-position `YEAR` cast; `compileMonths` taking `IntNeg` and the month-count `YEAR` cast; the reasons that change and the new ones for non-int multipliers |
| `VarkaLoopEmitter.java` (+ suite) | `requireOffsetShape` split into the month-count and weekday positions, with the comment corrected |
| `VarkaDifferentialSuite.scala`, `VarkaSharedSessions.scala` | the algebra over `varka_dates_intervals`, both ANSI modes, with the overflow rows raising the row engine's own error |
| `VarkaThroughputBenchmark.scala` + results | section 6's pairs on VARKA-67's `varka_date_interval_counts` fixture |
| `Surface.java`, `DateSurfaceBenchmark.java` | the new shapes in VARKA-62's surface |
| `docs/sql-varka.md` | the interval surface, which VARKA-67 wrote and this widens |
| `m4/PLAN.md`, `m5/PLAN.md`, this file | row 68, section 2.33 amended for the split, VARKA-89 opened, section 9 |

## 5. Tests, and what each is for

* **The compiler**, per expression and per mode: the node and its `outputTypes`,
  the bound removing the check where the operands are bounded, the unbounded
  multiply declining as VARKA-63's does, and the Long/Decimal/Double multipliers
  declining with their reason.
* **`abs` as a blend**: the IR pinned as `IfElse` over `IntNeg`, and the value
  matrix including `Int.MinValue`, which must condemn the batch and not answer.
* **`d - ym_col` and the month-count `YEAR` cast fusing**, which are the two
  shapes VARKA-67 pinned as declining - so those tests inverting is the evidence
  group C landed, and their decline reasons disappear from the suite.
* **The position split**: `next_day`'s weekday still refuses arithmetic, with
  its own reason, while `add_months`' count now takes it. The test that fails if
  the split is made in one direction only.
* **The differential**, both ANSI modes over the interval fixture: every shape
  against the row engine, the overflow rows raising `ARITHMETIC_OVERFLOW` from
  the row engine after the batch declines, and `try_*` where Spark spells it.
* **The emitter**, a value matrix over the int32 extremes for the new arms - the
  same shape VARKA-63's matrices take, since these are its nodes.

## 6. The measurement

The type exists only above the kernel - in `outputTypes` and `allocateVector` -
and not in the IR, so an emitter-level parity row cannot see it: an "interval
arm" and its int twin are the same IR and the same bytes. The instrument is the
one VARKA-67 used, `VarkaThroughputBenchmark` end to end through Spark, on VARKA-67's `varka_date_interval_counts` fixture, which already holds the same count as
an int column and as an interval.

Two pairs, each the same arithmetic spelled over the two types:

| pair | int form | interval form |
|---|---|---|
| addition | `m + m2` (VARKA-63's checked add) | `ym + ym2` |
| the composite | `year(d) * 12 + month(d)` | `make_ym_interval(year(d), month(d))` |

The fixture gains `m2`/`ym2` as a second count of the same generator with a
different shift - built once and reused for both spellings, and asserted
different from `m`/`ym` before anything is timed, per this project's fixture
rule.

`dev/varka_bench_ids.sh` is not needed here; the throughput file has no case
ids.

### 6.1 Predictions, registered before the run

1. Each interval form lands within 3% of its int form at both widths, since the
   kernel is the same and only the output vector's class differs; a larger gap
   is a finding about the Arrow write path, not the lane.
2. `make_ym_interval(year(d), month(d))` emits no overflow check, `intBound`
   proving it away, and the emitter suite's op-count register says so.
3. No shape that exists today changes a byte: every pinned oracle and every
   `codeSize` assertion holds, and the byte-identity form of that claim is what
   the suite checks. The committed *numbers* in the regenerated file will move
   by the run-to-run band this file has shown before, and that is not a
   prediction about this task.

## 7. Risks

1. **Widening an arm's type gate instead of adding a sibling**, which is how an
   interval would reach an int-typed position. The arms stay gated; the siblings
   are separate.
2. **The position split done on one side** (2.5), which is a ghost fallback in
   whichever direction is left behind. Its test is written first.
3. **`abs(Int.MinValue)`**, which throws in Spark and must condemn rather than
   answer - the blend's `IntNeg` arm is taken for it, and the matrix pins it.
4. **A `LEGACY`-mode expectation.** None of group A has a wrapping form; a test
   that sets `EvalMode.LEGACY` and expects `WRAP` would be asserting something
   Spark does not do.
5. **A non-int multiplier admitted by accident** - `ym * 2.5` is legal Spark and
   must decline by type, not compile as if `2.5` were an int.

## 8. Sequencing

1. This plan, the milestone row, section 2.33 amended for the split, and VARKA-89 opened with VARKA-65 and VARKA-88 as its routes.
2. The emitter's position split and its test (group C's blocker), first because
   both of C's shapes wait on it.
3. Group A's arms with the compiler suite, then C's two arms.
4. The differential and the fixture.
5. The throughput pairs, the docs, section 9, row 68.

## 9. Outcome

### 9.1 The two pairs, and the predictions scored

`VarkaThroughputBenchmark`, on `varka_interval_pairs` - one fixture holding two
month counts, each spelled once as an int column and once as a `MONTH`-unit
interval - so each pair's two rows differ in the operands' Spark type and in
nothing else. Both files are the first pinned regeneration of this benchmark,
the pinning having landed in the commit this branch sits on.

| row (varka side, M rows/s) | AVX-512 | 128-bit |
|---|---|---|
| `m + m2`, int counts, the control | 280.9 | 248.3 |
| `ym + ym2`, interval columns | 288.8 | 261.0 |
| `year(d) * 12 + month(d)`, the control | 262.0 | 223.0 |
| `make_ym_interval(year(d), month(d))` | 263.6 | 222.1 |

**Prediction 1's premise is false, which is a better answer than the measurement
it asked for.** It asked each interval form to land within 3% of its int form at
both widths, and said a larger gap would be a finding about the Arrow write
path. The composite pair agrees to 0.6% and -0.4%; the addition pair to 2.8% at
AVX-512 and 5.1% at 128 bits. The 5.1% cannot be a write-path finding, because
there is no per-type write path for it to be a finding about.

The kernel never touches an Arrow vector object. `VarkaKernelEvaluator.project`
reads `getDataBuffer().memoryAddress()` and `getValidityBuffer().memoryAddress()`
off each output and hands those addresses to the runner; the emitted loop writes
four-byte lanes into them. `IntVector`, `DateDayVector` and `IntervalYearVector`
are all `BaseFixedWidthVector`s of `TYPE_WIDTH` 4, so `allocateNew(len)` reserves
identical data and validity buffers, and one shared `setValueCount(len)` closes
the batch with no branch on type. The accessors do differ, but the benchmark's
cases write to a columnar sink and no accessor runs on either side. What is left
is a single constructor call per output per batch, choosing which class holds the
buffer - per batch, not per row, and a few hundred allocations against two
million lane writes.

So the whole of "admitting the type costs the kernel nothing" is a statement
about code, and `VarkaKernelEvaluatorSuite`'s "an interval output takes the int
output's write path, byte for byte" pins it: the same arithmetic under the two
Spark types, run through the evaluator, must produce byte-identical data buffers
of identical capacity from demonstrably different vector classes. That test
fails the day Arrow changes a width or someone adds a per-type write, which a
benchmark never would - a benchmark can only fail to find a difference that is
not there.

The residual 2.8% and 5.1% are therefore the per-fork JIT and code-layout
lottery, drawn once for each of a pair's two separately compiled kernels.
`dev/varka_bench_repeat.sh` measures that at 73 of 211 parity cases past 3%
between runs even pinned, which is the right order of magnitude for what is
seen here. Note that the thirty-two rows this regeneration moved are *not* that
evidence: that is a between-run diff carrying a systematic change, most of it
the pinning, and it bounds nothing about two cases inside one run.

**Prediction 2 is confirmed, and by identity rather than by a new register
row.** `make_ym_interval(year(d), month(d))` compiles to
`IntArith(ADD, WRAP, Month(d), IntArith(MUL, WRAP, Year(d), lit))` - both nodes
wrapping, `intBound` having proved the multiply and the add safe from the
calendar fields' own ranges. That is the node tree VARKA-63 already registered as
`year * 100 + month`, at 42 dense `IntVector` calls with the claim that the key
costs the two fields plus its own two ops. Registering it again would pin the
same bytes under a second name, so the register gained a comment pointing at it
instead, and `VarkaExpressionCompilerSuite` asserts the tree.

**Prediction 3 is confirmed on bytes and does not apply to numbers.** No pinned
oracle moved, no `codeSize` assertion moved, and the whole gate is green at both
widths. The committed numbers did move, on thirty-two rows, and none of that is
this task's: it is the first regeneration since the runner was pinned to the
fast CCX, which is exactly the movement that change was made to cause.

### 9.2 What moved that the plan did not list

**Section 6's fixture instruction contradicted the fixture it named, and the
fixture won.** The plan said to add `m2`/`ym2` to `varka_date_interval_counts`.
That table's own comment forbids it, in as many words: a cached table with one
more column is not the same cached table, which is why VARKA-67 copied VARKA-60's
generator into a new table rather than widening it. VARKA-68 did the same thing
one step further along, into `varka_interval_pairs`, and the run says the
precaution was real - VARKA-67's two rows moved 1.1% and 0.6% in a run where
thirty-two others moved more than 3%.

**`checkMatrix` cannot drive a shape that is meant to decline.** It asserts the
kernel returned status 0 on every case, which is the right assertion and the
reason `abs`'s `Int.MinValue` could not go through it. That case is a bespoke
status test in the shape of VARKA-63's condemn test - the overflowing lane in a
loop lane, in an epilogue lane, and under a null - beside a `checkMatrix` over
values inside the range. Section 5's "a value matrix including `Int.MinValue`"
was one test in the plan and is two in the code.

**One shape stayed residual and changed its reason, which is not the same as
staying put.** VARKA-67 pinned `d + CAST(m AS INTERVAL YEAR)` as declining and
recorded the emitter's month-count position as the cause. It still declines, and
the cause is now the checked multiply by twelve over an unbounded int column,
which has no int-lane overflow test. The differential's comment was rewritten
rather than its assertion left alone: a decline whose recorded reason has gone
stale is worse than an unpinned one, because the next reader trusts it.

**Group A's plainest spelling needed a cast arm the plan did not list, and the
surface table is what found it.** Two year-month intervals of different units
never meet directly: `TypeCoercion` widens both to
`YearMonthIntervalType(min(start), max(end))`, so `ymm + ymy` arrives as an add
over two casts and declined, while the same-unit `ymm + ymm2` fused. Every
compiler test had built its trees by hand, where no cast exists, so the suite was
green on a shape the analyzer never produces. What caught it was writing
`ymm + ymy` as a `Surface` entry marked fused - `varka_dates` has one column per
unit and no two of the same one, so the surface could not express the shape the
tests had been checking. The arm is a relabel: `castToYearMonthInterval` splits
the count into whole years and a remainder and reassembles it, which returns the
same int for a `MONTH` end field at every value including `Int.MinValue`. The
narrowing direction drops the remainder, so it is a division and declines with
its own reason, pointing at VARKA-89. Both directions and the coerced add are
pinned in `VarkaExpressionCompilerSuite`, and the coerced add in the
differential.

**`try_add(ym, ym)` needed pinning and was not in the plan.** Spark spells it as
a `TryEval` around the add, and the compiler has no arm for `TryEval` at all, so
the whole entry is residual and the row engine returns the null. That is the
answer this task must not change while it has no null-on-overflow lowering, so
it is asserted rather than left to be discovered.

**`docs/sql-varka.md` was stale from VARKA-67, not merely incomplete.** Its
`ADD_MONTHS` bullet still said a stored year-month interval column declines
because the Arrow cache holds it as a type no kernel reads - false since VARKA-67, and in the paragraph this task rewrites. Corrected here, along with the
`YEAR`-cast sentence in the type paragraph, both decline-reason entries, and a
new `SKILLS.md` section on the lesson underneath the whole task: a refusal
shared by two positions carries one reason, and here it was true of only one.

### 9.3 What this leaves for later

Group B, the two divisions, is milestone 5's VARKA-89: `extract(YEAR|MONTH FROM
ym)` and `ym / k` need a division exact over the full int32 month range, which
the emitter's `MONTH_ARITH_M` magic is not - it covers 0..49,151, about a
forty-thousandth of the type. VARKA-65 and VARKA-88 are its two routes, and
`extract(MONTH)`'s `ByteType` output is a blocker neither route removes.

The 3% question is closed, and not by measuring it. VARKA-67 left it to "the
pinned runner"; the pinned runner ran here and could not have answered it,
because 9.1's write path does not exist. Nothing is owed on it and no follow-up
row is opened - the claim it was standing in for is now a test.

What is genuinely unmeasured is this benchmark's own band: how far its cases
move between two runs with nothing changed. `dev/varka_bench_repeat.sh` has that
number for `VarkaEmitterParityBenchmark` and not for this file, and every future
task that reads a regeneration diff wants it. That is a property of the
instrument rather than anything about intervals, so it belongs in its own row if
it is wanted, not in this task's tail.
