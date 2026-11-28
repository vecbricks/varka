# VARKA-342: Floating arithmetic and the exact functions


## A. What is left of this row, and how it is built (28 November 2026)

*NEW SECTION, written on 28 November 2026 for builders that have only this file and the
repository's `AGENTS.md` files; revised the same day (the fix round): each part has a build
sheet in the house form. Its code facts were checked on master `6dafb8bd2f6` (origin/master
`04bfdd3673d` differs by plan files only). No JVM was started for it; A.13 lists what only a
JVM settles. Sections 0 to 10 below are the row's record on master, unchanged; 5.6, 5.7 and
decisions 8 to 10 are added at the end from the branch that built them, marked. Every build
sheet says "Follow `BUILD_SHEETS.md`": its rules bind each part in full. A.5.0 of
`VARKA-343.md` (the shared names `CG`, `T`, `S`, the output lines of a run, the bytes check)
applies to these sheets too. Revised again in fix round 2 (28 November 2026, after
review 1 and the owner's answers of that day): each changed place says "Changed in fix
round 2" or "fix round 2". Reworked the same day after the owner's decision E2 (c) for
`VARKA-343.md`'s part 343L: each changed place says "after E2 (c)". No part of this row is
full strength.*

### A.1 What this row is, and which done-when clause holds it

Row 342 of `m8/PLAN.md` is floating arithmetic and the exact functions. The close path
(`m8/PLAN.md` 12.5, 27 November 2026) keeps it as a gate "under item 2 alone": item 2 of 1.1,
"Most of the census is fused". So the close holds this row to `m8/census.tsv`.

**Merged:** the baseline (#1228); `+`, `-`, `*`, `/`, `try_divide`, negation, `abs`, `sqrt`,
`signum`, `degrees`, `radians` (#1248); the forms over a cast to double and `signum` over an
interval (#1425); the `Fma` node, the IR fuzz sequences and the twelve operator census cells
(#1631, 22 November 2026). The row's line in `m8/PLAN.md` still calls the last three "built
locally": it is stale.

**Left:** the four interval forms by a floating factor, and the measurement.

| census entry | over | fused today | after | part |
| :--- | :--- | :--- | :--- | :--- |
| `ym_times_num` | `float`, `double` | no | yes | 342d |
| `ym_div_num` | `float`, `double` | no | yes | 342q |
| `dt_times_num` | `float`, `double` | no | yes, credited at emit level 2 (343L of `VARKA-343.md` merges first) | 342e |
| `dt_div_num` | `float`, `double` | no | yes, credited at emit level 2 (343L merges first) | 342q |

A census claim of a plain type needs a coverage row whose node of the entry's class takes an
operand of that type (`dev/varka_census.py`, `claim`), and the coverage suite compiles under the
defaults, whose emit level is unknown; there the kernel plan declines every conversion between
the long and the double lane. So the two day-time entries fuse in a session (which resolves a
named level) and could not be credited until 343L makes the unknown level take the exact
software forms. *Changed in fix round 2:* the owner answered `VARKA-343.md` A.12 question 1
on 28 November 2026 with (b), build 343L, and 343L is first in that row's order; so 342e and
342q are written for a master with 343L (and 343b2b) merged, with one text, and add the
day-time coverage rows and census claims. *After E2 (c), the same day:* 343L does not make the
unknown level take the forms; it makes the coverage suite (and every suite that holds its rows)
compile at emit level 2, an x86 session's. So the day-time forms fuse at every named level and
still decline at the unknown one, the defaults' and the shared test sessions'; their coverage
rows are credited at level 2, and each session test of them runs at
`withBothConf(SQLConf.VARKA_EMIT_USE_AVX.key, "2")`.

**Not this row's, though the row's line lists it as left:** "the cost model's `DIV` and
`SQRT` prices". The floating lanes are unpriced until row 352's refit
(`VarkaEmitCostFit.PRICED_LANES`; `VarkaEmitCostSuite`'s `awaitingRefit` set names `MathUnary`,
`Fma`, `ConvertLane` and `CrossLane`). Nothing is built for it here.

**Built and not merged:** on the local branch `varka-342de-r` of
`/home/max/proj/vecbricks-varka` (tip `688414d4e10`, 23 November 2026, merge-base with master
`241125b2f37`, 474 commits behind `6dafb8bd2f6`), stacked on row 343's 343b2a and 343b2b:

| commit | what | counted lines |
| :--- | :--- | ---: |
| `44dcedceb07` | `ym * x`: `scaledHalfUp`, `floatingProduct`, the suite, 3 coverage rows | 645 |
| `952426cb850` | the plan's record (5.6) | plan only |
| `7c79e9ab581` | `dt * x` through `dayTimeMultiply` | about 60 |
| `d250fd3eb78` | a count off its lane declines; the top tie `2^52 - 0.5` replaces a duplicate cell | about 20 |
| `688414d4e10` | the session test's literal factors kept inside the int (`ym * -0.5F` for `ym * 2.5F`, which raises at the fixture's ends) | about 5 |

The four `varka-342-floating-arith`, `-342b-floating-fuzz`, `-342c-census` and
`-342c-census-r` branches hold only what #1631 merged. `varka-342d-interval-scaling` and
`varka-342e-daytime-scaling` are older copies. No branch adds an IR node.

**How the branch meets master** (fix round: `scratch-343/merge3.py` of the planning folder,
`git merge-file` per file, stacked on the results of 343b2a and 343b2b): from `163aac5ecd6` to
`44dcedceb07` one conflict, the imports of `VarkaIntervalCompiler` (master added `Supplier` and
`javax.annotation.Nullable`); from `44dcedceb07` to `688414d4e10` two more, in
`dayTimeMultiply` (master's takes a decimal literal factor, row 103). The handover's earlier
list ("imports and `dayTimeMultiply`") was right; it did not say which part each belongs to.

**What changed since the branch was built:** SPARK-61352 is on master (the sync #1773; `git
merge-base --is-ancestor 8621489fe80 6dafb8bd2f6` exits 0). Decision 8 of the branch kept
`ym / x` and `dt / x` declined only until that sync. So the quotients fuse now (342q).

### A.2 What a builder must know

- A year-month interval is a count of months on the int lane; a day-time interval a count of
  microseconds on the long lane. `x` is a `DOUBLE` factor; a `FLOAT` one is widened exactly.
- Spark computes `DoubleMath.roundToInt(m * (double) x, HALF_UP)` for months and
  `roundToLong` for microseconds, and the same over `m / x` after raising
  `INTERVAL_DIVIDED_BY_ZERO` on `x == 0` (both zeros). Guava raises on NaN, on an infinity and
  where the rounded value leaves the type. None of the four forms has an evaluation mode: the
  answer and the errors are the same under ANSI and LEGACY.
- HALF_UP rounds a tie away from zero: 2.5 to 3, -2.5 to -3.
- A kernel never raises: a batch with a lane Spark raises on declines, and Spark's row code
  raises on that row. So every raise becomes a guard.
- `ConvertLane(v, lane)` is Java's cast (row 343). `GuardedRange.floating(v, lo, hi, FAIL)`
  declines a batch with a live lane outside `[lo, hi]` or NaN (row 343, part 343b2a1).
  `CrossLane(cond, lane)` reads a condition at another lane (on master).
- A census line of `sql/varka/plans/m8/census_curated.txt` for an implicit operator is `name |
  class | family | types | result | fused today | need | rows | note`. A plain type in "fused
  today" needs a coverage row over a column of that type; a type marked `~` is fused over a
  derived operand and the note quotes that row.

### A.3 The admission check

The directories, as shell names every command of this file uses (paste them first):

    CG=sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen
    T=sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka
    S=sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka
    CS=sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen
    BJ=sql/core/src/test/java/org/apache/spark/sql/execution/benchmark
    BS=sql/core/src/test/scala/org/apache/spark/sql/execution/benchmark

Each command was run on `6dafb8bd2f6` and printed what is written after it, except item 1,
which holds only after row 343's parts merge (on `6dafb8bd2f6` it prints `0`, `0`, `0`).

1. Row 343's parts: `grep -c 'static GuardedRange floating' $CG/varka/VarkaVectorIR.java`
   prints `1` after 343b2a1; `grep -c 'narrowsAnIntAtRoot' $CG/varka/VarkaKernelPlan.java` at
   least `2` after 343b2a2; `grep -c 'emitAssembly' $CG/varka/VarkaConversionLowering.java` at
   least `2` after 343b2b.
2. `grep -c 'case MultiplyYMInterval _, DivideYMInterval _, MultiplyDTInterval _,
   DivideDTInterval _ ->' $CG/VarkaNodeCompiler.java`: `1`. The four classes decline with one
   reason.
3. `grep -c 'public static final double HALF_UP_' $CG/VarkaIntervalCompiler.java`: `4`.
4. `ls sql/varka/proofs/fp_half_up.smt2` lists the file: the rounding and the guards are
   proved against Guava under both solvers (merged with #1425).
5. `grep -A1 '"year-month interval ym \* x" + density'
   $BJ/VarkaFloatingArithmeticBaselineBenchmark.java`
   prints the section with `Fusion.NONE`.
6. `grep -c 'static LiteralSlot doubleSlot(double v, DeclineSink sink)\|static LiteralSlot
   bitsSlot(long bits, LaneType lane, VarkaSlotTable literals,' $CG/VarkaNodeCompiler.java`:
   `2`. The literal helpers the lowering takes are on master under the branch's names.
7. `grep -c 'scaledHalfUp\|floatingProduct' $CG/VarkaIntervalCompiler.java`: `0`.

### A.4 The design: one builder for the four forms

`VarkaIntervalCompiler.scaledHalfUp(op, m, x, doubleLit, countLit)`, public and static, `op`
`IntOp.MUL` or `IntOp.DIV`, `m` the count on the int or long lane, `x` on a floating lane:

    factor = x on DOUBLE ? x : ConvertLane(x, DOUBLE)                    F2D, exact
    p    = IntArith(op, op == DIV ? FAIL : WRAP, ConvertLane(m, DOUBLE), factor)
    g    = GuardedRange.floating(p, LO, HI, FAIL)
    t    = ConvertLane(g, lane of m)                                     D2I or D2L: truncation
    f    = IntArith(SUB, WRAP, g, ConvertLane(t, DOUBLE))                the fraction, exact
    up   = CrossLane(Compare(IEEE_LE, doubleLit(0.5), f), lane of m)     f >= 0.5
    down = CrossLane(Compare(IEEE_LE, f, doubleLit(-0.5)), lane of m)    f <= -0.5
    step = IfElse(up, countLit(1), IfElse(down, countLit(-1), countLit(0)))
    r    = IntArith(ADD, WRAP, t, step)

`LO`, `HI` are `HALF_UP_INT_LO` = `nextUp(-2^31 - 0.5)` and `HALF_UP_INT_HI` =
`nextDown(2^31 - 0.5)` for months, `HALF_UP_LONG_LO` = `-2^63` and `HALF_UP_LONG_HI` =
`nextDown(2^63)` for microseconds. A `DIV` in `FAIL` declines a batch with a live zero divisor
of either sign, which is Spark's `INTERVAL_DIVIDED_BY_ZERO`. The two tests of `f` are crossed
to the count's lane because an `IfElse` takes its condition and its values on one lane. `r`
cannot overflow: it is Guava's answer, which the guard keeps inside the type.

| form | Catalyst class | lane of `m` | `op` | conversions | row Spark raises on (the batch declines) | test cell |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `ym * x`, `x * ym`, `ym * f` | `MultiplyYMInterval` | `INT` | `MUL` | `I2D`, `D2I` | product past the int, NaN, infinity | `ym * 0.5D` at an odd count: a tie |
| `ym / x`, `ym / f` | `DivideYMInterval` | `INT` | `DIV` | `I2D`, `D2I` | the same, and `x` of `0.0` or `-0.0` | `ym / 0.4D` at 1: 2.5 to 3 |
| `dt * x`, `x * dt`, `dt * f` | `MultiplyDTInterval` | `LONG` | `MUL` | `L2D`, `D2L` | product at or past `2^63`, NaN, infinity | `(2^53 + 1) * 0.5` gives `2^52` |
| `dt / x`, `dt / f` | `DivideDTInterval` | `LONG` | `DIV` | `L2D`, `D2L` | the same, and a zero `x` | `MIN / -1.0` raises |

`(double) m` of a microsecond count rounds to nearest, as Java's cast does; that is Spark's
own first step, so `(2^53 + 1) * 0.5` is `2^52`, not `2^52 + 1`.

**Checked in Python** (`scratch-342/check_half_up.py` of the planning folder: Guava's rule on
exact rationals against the lowering above): `interval HALF_UP: 1905500 (count, factor,
operation) cells, months and microseconds, products and quotients, equal Guava's roundToInt
or roundToLong; 695894 of them raise in Spark and decline here; both guards tight; the ties
and row 103's long cells hold; 0 mismatches`. Python's `float` is the JVM's `double`, and
`float(int)`, `*` and `/` round as `l2d`, `dmul` and `ddiv` do, so the script stands in for
the JVM for these values. It does not stand in for: a NaN's payload (no NaN is answered here,
every NaN declines); `-0.0` as an answer (the answer is an integer, so no signed zero
exists); a `FLOAT` factor's arithmetic (there is none: the float is widened first, exactly);
and the emitted kernel itself, which `VarkaEmitterIntervalScaleSuite` holds to Spark's `eval`.
`fp_half_up.smt2` proves the same claim for every double inside each guard.

### A.5 The parts

Order: 342d, 342e, 342q, 342r. 342d needs row 343's 343b2a2 (`D2I`, the floating guard, `D2L`
at level 3); 342e and the day-time half of 342q need 343b2b (`D2L` below level 3, the
assembly) as well. Row 343's order puts 343L before 343b2a2 (`VARKA-343.md` A.5), so
every part here runs on a master with 343L merged; 342e and 342q check it in B0 and stop if
it is not (fix round 2, review 1 finding 3). Where the code comes from, and what a "branch's
lines N to M" is: `VARKA-343.md` A.5.0, "Where the code comes from" (this row reads the
fork's or the local `varka-342de-r`, `688414d4e10`).

| part | title | size, counted | builder | starts from | waits on |
| :--- | :--- | ---: | :--- | :--- | :--- |
| 342d | `[VARKA-342] Fuse a year-month interval times a floating factor` | 620 | Sonnet-class | master; `44dcedceb07`, `d250fd3eb78`, `688414d4e10` | 343b2a2 |
| 342e | `[VARKA-342] Fuse a day-time interval times a floating factor` | 110 | Sonnet-class | master; `7c79e9ab581` | 342d, 343b2b |
| 342q | `[VARKA-342] Fuse an interval divided by a floating factor` | 250 | Sonnet-class | master | 342e |
| 342r | `[VARKA-342] Results: floating arithmetic and the interval forms on kernels` | 40 | Sonnet-class | master | 342q |

#### Part 342d: the year-month product (build sheet, fix round)

- **Title:** `[VARKA-342] Fuse a year-month interval times a floating factor`. **Branch:**
  `varka-342d-ym-product` from master after 343b2a2 merged (the name
  `varka-342d-interval-scaling` is taken). **Builder:** Sonnet-class. **Size:** about 620
  counted lines (the suite is 336 of them). Follow `BUILD_SHEETS.md`.
- **What it adds:** `ym * x`, `x * ym` and `ym * f` fuse at every emit level; the HALF_UP
  builder `scaledHalfUp` that 342e and 342q reuse; a suite that holds the builder to Spark's
  own `eval` for all four forms (the day-time ones at level 3, where `D2L` is one call); three
  coverage rows; the census claim.

**B0, the premises** (each must print what is written, or stop):

1. A.3 items 2, 3, 6 and 7 print `1`, `4`, `2`, `0`.
2. `grep -c 'static GuardedRange floating' $CG/varka/VarkaVectorIR.java`: `1`;
   `grep -c 'narrowsAnIntAtRoot' $CG/varka/VarkaKernelPlan.java`: at least `2`;
   `grep -c '"a conversion from "' $CG/varka/VarkaKernelPlan.java`: `0` (master prints `1`:
   the old refusal of every conversion to an integral lane is gone after 343b2a2). *Changed in
   fix round 2 (review 1, finding 2):* the earlier premise, `grep -c 'is not built yet (row
   343)'` printing `0`, was wrong: 343b2a2's own refusal of `D2L` below level 3 ends "its AVX2
   form is not built yet (row 343)", so that count is `1` until 343b2b
   (`git show 96047835ca4:$CG/varka/VarkaKernelPlan.java | grep -c 'is not built yet (row
   343)'` prints `1`; at `163aac5ecd6` it prints `0`).
3. `ls $T/VarkaEmitterIntervalScaleSuite.java` fails.
4. `grep -n '^ym_times_num|' sql/varka/plans/m8/census_curated.txt` prints the line whose
   sixth field (`awk -F'|' '$1=="ym_times_num"{print $6}'`, fused today) is `ym~,int~`; its
   fifth field is the result, `ym` (fix round 2, review 1 finding 5: the earlier text said
   "fifth").
5. `git cat-file -t 688414d4e10` prints `commit`. If not, `VARKA-343.md` A.5.0 (the fork's
   `varka-342de-r`); if still not, stop: the suite is quoted from it.

**Steps.**

1. Take the code of these files from the branch, one file at a time, with
   `git diff 163aac5ecd6 688414d4e10 -- <file> | git apply --3way` for the files that merge
   clean, and by hand for `VarkaIntervalCompiler.java`:
   - `$CG/VarkaNodeCompiler.java`: take only the hunks of `44dcedceb07` (`git diff
     163aac5ecd6 44dcedceb07 -- <file> | git apply --3way`): `floatingDecline`'s javadoc names
     the year-month product as served; the pass list gains `|| e instanceof MultiplyYMInterval`
     after the `Signum` test, with the comment `// A year-month interval times a floating
     factor, rounded to the months by HALF_UP.`; and the switch's interval case splits in two.
     Then, by hand, the quotient's text (its blocker merged, A.1) becomes:

         // The quotients by a floating factor are part 342q's and the day-time product part
         // 342e's (`VARKA-342.md` A.5). UnaryPositive is RuntimeReplaceable, so it never
         // reaches this switch: it passes above and is asked again as its child.
         case DivideYMInterval _, DivideDTInterval _ ->
             "an interval quotient by a floating factor (row 342, part 342q)";
         // The day-time product rounds to the long, whose conversion below emit level 3 is the
         // AVX2 form of part 343b2b.
         case MultiplyDTInterval _ -> "a day-time interval product by a floating factor, which "
             + "waits for the conversion to the long lane below emit level 3 (part 343b2b)";

   - `$CG/VarkaIntervalCompiler.java`, by hand:
     - imports: after `import java.util.Optional;` the block becomes
       `import java.util.function.DoubleFunction;`, `import java.util.function.LongFunction;`,
       `import java.util.function.Supplier;`, a blank line, `import javax.annotation.Nullable;`
       (master's two kept, the branch's two added in order); and after the import of
       `VarkaVectorIR.ConstDivide` add the imports of `VarkaVectorIR.ConvertLane` and
       `VarkaVectorIR.CrossLane`.
     - `multiply(MultiplyYMInterval ...)`: its javadoc's last sentences and its first statement
       as the branch's lines 414 to 426 at `688414d4e10` (`git show
       688414d4e10:$CG/VarkaIntervalCompiler.java`): the statement is

           if (VarkaFloatingCompiler.floatingLane(m.num().dataType()).isPresent()) {
             return floatingProduct(m.interval(), m.num(), LaneType.INT, m, inputs, literals,
                 sink);
           }

     - the javadoc of the four `HALF_UP_*` constants: the branch's lines 526 to 527 ("The
       lowering is {@link #scaledHalfUp}; ...").
     - after `HALF_UP_LONG_HI`, the two methods of the branch's lines 534 to 631 at
       `688414d4e10`, word for word: `floatingProduct(Expression interval, Expression factor,
       LaneType lane, Expression whole, VarkaSlotTable inputs, VarkaSlotTable literals,
       DeclineSink sink)` (private) and `public static VarkaVectorIR scaledHalfUp(IntOp op,
       VarkaVectorIR m, VarkaVectorIR x, DoubleFunction<VarkaVectorIR> doubleLit,
       LongFunction<VarkaVectorIR> countLit)`. The body of `scaledHalfUp` is A.4's, as the
       branch has it (`git show 688414d4e10:$CG/VarkaIntervalCompiler.java | sed -n
       '534,631p'` prints both methods with their javadocs; `scaledHalfUp`'s signature is at
       line 601). `floatingProduct`'s body, with `d250fd3eb78`'s lane check in it:

           int mark = literals.size();
           int longMark = sink.longMark();
           Optional<VarkaVectorIR> count = lane == LaneType.INT
               ? intervalOperand(interval, "the multiplied interval", inputs, literals, sink)
               : dayTimeOperand(interval, "the multiplied interval", inputs, literals, sink);
           if (count.isPresent() && count.get().laneType() != lane) {
             count = sink.decline("an interval count computed on the " + count.get().laneType()
                 + " lane, not the " + lane, whole);
           }
           if (count.isEmpty()) {
             return rolledBack(count, literals, mark, sink, longMark);
           }
           Optional<VarkaVectorIR> x = VarkaNodeCompiler.compileNode(factor, inputs, literals,
               sink);
           if (x.isPresent() && !x.get().laneType().isFloating()) {
             x = sink.decline("an interval factor computed on the " + x.get().laneType()
                 + " lane", factor);
           }
           if (x.isEmpty()) {
             return rolledBack(x, literals, mark, sink, longMark);
           }
           return Optional.of(scaledHalfUp(IntOp.MUL, count.get(), x.get(),
               v -> VarkaNodeCompiler.doubleSlot(v, sink),
               v -> VarkaNodeCompiler.bitsSlot(v, lane, literals, sink)));

       Take `floatingProduct`'s javadoc from `44dcedceb07` (the year-month one: `git show
       44dcedceb07:$CG/VarkaIntervalCompiler.java | sed -n '533,539p'`); 342e widens it. Do not
       take `7c79e9ab581`'s `dayTimeMultiply` hunks: they are 342e's.
   - `$T/VarkaEmitterIntervalScaleSuite.java`, new: `git show
     688414d4e10:$T/VarkaEmitterIntervalScaleSuite.java` with the test `dayTimeAtAvx2` (its
     last 9 lines before the closing brace) removed; 342e adds it back. Four tests stay:
     `yearMonthProduct`, `yearMonthQuotient`, `refusals`, `dayTimeAtLevelThree`.
   - `$CS/VarkaExpressionCompilerSuite.scala`: the hunks of `44dcedceb07` (`git diff 163aac5ecd6
     44dcedceb07 -- <file> | git apply --3way`): the import of `ConvertLane`, the year-month
     divisor's assertion, the deleted year-month product assertion, the new test "`ym * x` by a
     floating factor rounds the double product to the months by HALF_UP", the day-time test "each
     with its own reason" and the value `quotientByFloatingReason`. Then by hand the value becomes
     `"an interval quotient by a floating factor (row 342, part 342q)"` and the comment above the
     year-month divisor's assertion `// A floating divisor is part 342q's.`
   - `$T/VarkaProofFiles.java` (`d250fd3eb78`, one comment): above the guards' bounds, "from
     the class that will build it" becomes "from the class that builds it (scaledHalfUp)".
   - `$S/VarkaCoverageSuite.scala` (`git diff 163aac5ecd6 44dcedceb07`, clean): the family
     "Floating arithmetic over other types" gains, after its row `signum(dt)`, the three rows
     of the table below, and its comment the clause "and a year-month interval times a
     floating factor the HALF_UP of the double product (2.4)".
   - `$BJ/VarkaFloatingArithmeticBaselineBenchmark.java`
     (`44dcedceb07`, clean): the section `year-month interval ym * x` becomes `Fusion.WHOLE`,
     and the javadoc sentence names it. `dt / x` stays `Fusion.NONE` until 342q.
   - `sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaFloatingLaneSuite.scala`
     (`git diff 163aac5ecd6 688414d4e10`, clean): the fixture `varka_float_scaled_intervals`
     (18 rows of 3.7's cells), the tests "ym * x and ym * f fuse and round to the months as the
     row engine does, a tie away from zero" (with `688414d4e10`'s `ym * -0.5F AS e`) and "ym *
     x past the int, or by a NaN or an infinite factor, declines its batch and raises the row
     engine's error", `rootCause`, and the decline test renamed "an interval quotient or a
     day-time product by a floating factor plans no Varka node" over `ym / x`, `dt * x`,
     `dt / x`, `ym / 4.0D`.
2. `sql/varka/plans/m8/census_curated.txt`, the line `ym_times_num|...`: its sixth field
   (fused today) `ym~,int~` becomes `ym~,int~,float,double`. Nothing else of the line moves:
   the line then begins

       ym_times_num|MultiplyYMInterval|interval|ym,NUM|ym|ym~,int~,float,double|semantics|
3. `sql/varka/plans/m8/PLAN.md`, row 342: after `**Started** (` insert `*342d built <date>,
   \`VARKA-342.md\` A.5: a year-month interval times a FLOAT or DOUBLE factor fuses, HALF_UP by
   scaledHalfUp;* `.
4. `VARKA-342.md` section 10 (or the end of 5.6 if section 10 has no subsection for it): `###
   342d as built (<date>)`, at most 15 lines.

**The coverage rows** (family "Floating arithmetic over other types", `44dcedceb07`):

| row | note | domain |
| :--- | :--- | :--- |
| `ym * x` | the double product, guarded to the doubles whose HALF_UP fits the int, truncated by D2I and stepped away from zero at a half; outside the guard, NaN and the infinities among it, the batch declines, as Spark raises | `x BETWEEN -0.5D AND 0.5D` |
| `ym * f` | the float factor widened exactly, then the same rounding | `f BETWEEN -0.5F AND 0.5F` |
| `ym * 0.5D` | a literal factor, every odd month count a tie | none |

**The tests.**

| suite | what it holds | the failure only it catches |
| :--- | :--- | :--- |
| `VarkaEmitterIntervalScaleSuite` (new, JUnit, own JVM) | for each form, every (count, factor) pair of 16 month counts or 20 microsecond counts by 29 factors, plus the pinned products at counts 1 and -1 (the ties `+-0.5`, `+-2.5`, `2^31 - 1.5`, `2^52 - 0.5`, each guard's ends and outer neighbours): the reference equals Spark's `eval` at each cell Spark answers (`referenceIsSpark`); the kernel equals the reference at 3 widths, 7 lengths, 3 null patterns; each cell Spark raises on, put at row 3 of 17 answered ones, declines both bodies, and declines no masked body when its factor is null. `yearMonthProduct`, `yearMonthQuotient` (the quotient at the IR: the compiler arm is 342q's), `refusals`, `dayTimeAtLevelThree` | a rounding off by one at a tie or a guard's end, a guard that lets a raising cell answer |
| `VarkaExpressionCompilerSuite` (Scala) | the compiled tree's shape for `ym * 2.5D` and `ym * 2.5F` (a `WRAP` add over a `D2I` truncation of a guard); the three decline texts | a builder change that keeps the answers but drops the guard |
| `VarkaFloatingLaneSuite` (Scala) | five spellings through a session, `checkAnswer` against Spark; four raising products raise the row engine's error with the same root cause and message | the session path, the fallback's error |

**Commands, in order.**

1. `flock "$VARKA_HEAVY_LOCK" build/sbt catalyst/Test/compile sql/Test/compile`: `[success]`.
2. `flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaEmitterIntervalScaleSuite'`:
   `Passed: Total 4, Failed 0, Errors 0, Passed 4` (on the branch: 4 of 4, 44 s).
3. `flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaExpressionCompilerSuite'`:
   `All tests passed.`
4. `dev/varka_regen_generated.sh --all` (bare): its last line begins `varka regen: done`. Then
   `grep -c '"sql" : "ym \* x"\|"sql" : "ym \* f"\|"sql" : "ym \* 0.5D"' sql/varka/coverage.json`
   prints `3`; the bytes check of `VARKA-343.md` A.5.0 prints `bytes: 0 committed shapes
   moved, 6 shapes added` (another added count is reported, not a stop).
5. `python3 dev/varka_census.py --check`: prints nothing; then `awk -F'\t'
   '$1=="ym_times_num"{print $8}' sql/varka/plans/m8/census.tsv` prints
   `int,float,double,ym`.
6. `flock "$VARKA_HEAVY_LOCK" build/sbt 'sql/testOnly *VarkaFloatingLaneSuite
   *VarkaFloatingArithmeticBaselineQueriesSuite *VarkaCoverageHarnessSuite'`: no test fails.
7. `BUILD_SHEETS.md` 6, the commit, `python3 dev/varka_push_check.py` (size under 900).

The order of the types in command 5 is the census's own (`ORDER` in `dev/varka_census.py`:
bool byte short int bigint float double dec date ntz time ym dt); a `~` type is listed without
its mark.

**Planted faults.** (a) In `scaledHalfUp`, `doubleLit.apply(0.5)` becomes `doubleLit.apply(
0.5000000000000001)`: `yearMonthProduct` fails at the tie `1 * 0.5`. (b) The guard's high end
`HALF_UP_INT_HI` becomes `0x1p31 - 0.5` in the call (not the constant: the proof file reads the
constant): `yearMonthProduct` fails at the pinned `2^31 - 0.5`, which Spark raises on and the
kernel then answers. (c) Drop the lane check `count.get().laneType() != lane`: no test of this
suite fails (it is the compiler's guard against a count the operand compiler built on another
lane); record that in the outcome.

**Done when** commands 1 to 7 print what they say, the census line of command 5 holds
`float,double`, and faults (a) and (b) fail as said.

**Not here:** the quotients' compiler arms; `dt * x`'s compiler arm; any change to
`GuardedRange`, `ConvertLane` or `fp_half_up.smt2`; `dt_times_num`'s census line.

#### Part 342e: the day-time product (build sheet, fix round)

- **Title:** `[VARKA-342] Fuse a day-time interval times a floating factor`. **Branch:**
  `varka-342e-dt-product` from master after 342d and 343b2b merged. **Builder:**
  Sonnet-class. **Size:** about 150 counted lines. Follow `BUILD_SHEETS.md`. *Changed in fix
  round 2 (review 1, findings 3 and 5): one text, for a master with 343L and 343b2b merged;
  the old variant A is gone. After E2 (c): the form fuses at every named level and declines at
  the unknown one, so 342d's session decline test keeps `dt * x` and the fused test runs at
  level 2.*
- **What it adds:** `dt * x`, `x * dt`, `dt * f` fuse at every named emit level by 342d's
  `floatingProduct` over the long lane (`L2D`, `D2L`; below level 3 their AVX2 forms, 343b2b);
  at the unknown level the kernel plan declines both conversions, and the form with them
  (343L's known limit). Two coverage rows, credited at level 2; the census claim.

**B0, the premises.**

1. 342d is merged: `grep -c 'static VarkaVectorIR scaledHalfUp' $CG/VarkaIntervalCompiler.java`:
   `1`. 343b2b is merged: `grep -c 'emitAssembly' $CG/varka/VarkaConversionLowering.java` at
   least `2`.
2. `grep -c 'a floating factor is row 342.s lowering' $CG/VarkaIntervalCompiler.java`: `1`
   (the type decline of `dayTimeMultiply`).
3. `grep -c 'case MultiplyDTInterval _ -> "a day-time interval product by a floating factor'
   $CG/VarkaNodeCompiler.java`: `1`.
4. 343L is merged: `grep -c 'public static final VarkaEmitOptions CREDIT ='
   $T/VarkaMatrixRuns.java`: `1`; and 343b2b: `grep -c 'conversion to BIGINT at an unknown emit
   level' $CG/varka/VarkaKernelPlan.java`: `1`. If not, stop (`VARKA-343.md` A.5).
5. `git cat-file -t 7c79e9ab581`: `commit` (if not, `VARKA-343.md` A.5.0: the fork's
   `varka-342de-r`).
6. `grep -n '^dt_times_num|' sql/varka/plans/m8/census_curated.txt` prints the line whose
   sixth field (`awk -F'|' '$1=="dt_times_num"{print $6}'`, fused today) is `dt,int~,dec~` and
   whose note ends "; a floating factor is 342's lowering".

**Steps.**

1. `$CG/VarkaIntervalCompiler.java`, by hand (master's `dayTimeMultiply` takes a decimal
   literal factor since row 103, so the branch's hunks conflict):
   - in `dayTimeMultiply`, directly after `DataType type = m.num().dataType();`:

         if (VarkaFloatingCompiler.floatingLane(type).isPresent()) {
           return floatingProduct(m.interval(), m.num(), LaneType.LONG, m, inputs, literals,
               sink);
         }

   - its type decline's text loses ": a floating factor is row 342's lowering" and reads
     `"day-time interval factor of type " + type.simpleString() + " is not an integral or
     decimal literal"` (no test pins it: `grep -rn 'is not an integral or decimal literal'
     sql/catalyst/src/test sql/core/src/test` prints nothing on master);
   - its javadoc's last sentence, "A column factor needs the checked long multiply of row 104
     and a floating one is row 342's lowering; each declines.", becomes "A column factor needs
     the checked long multiply of row 104 and declines. A {@code FLOAT} or {@code DOUBLE}
     factor is {@link #floatingProduct}'s.";
   - `floatingProduct`'s javadoc becomes the branch's (`git show
     7c79e9ab581:$CG/VarkaIntervalCompiler.java`, the javadoc above `floatingProduct`, which
     names `dt * x`, `roundToLong` and the unknown level).
2. `$CG/VarkaNodeCompiler.java` (`git diff 952426cb850 7c79e9ab581 -- <file> | git apply
   --3way`, clean): the pass list's clause becomes `|| e instanceof MultiplyYMInterval || e
   instanceof MultiplyDTInterval` with the comment "An interval times a floating factor,
   rounded to the months or microseconds by HALF_UP."; the case `MultiplyDTInterval _` of the
   switch goes; the javadoc names "an interval times a floating factor".
3. `$T/VarkaEmitterIntervalScaleSuite.java`: add back the test `dayTimeAtAvx2` (`git show
   688414d4e10:$T/VarkaEmitterIntervalScaleSuite.java`, its last test): `checkDayTime` at
   `useAVX=2` under `XOR_SPLIT` and `SPLIT`.
4. `$CS/VarkaExpressionCompilerSuite.scala`, by hand (342d took this test from `44dcedceb07`;
   the branch's next commit only fused it at the named levels): the test "`dt * x` and `dt / x`
   by a double decline, each with its own reason" (342d's, whole) becomes

         test("`dt * x` by a double fuses at the named emit levels and declines at the " +
             "unknown one, and `dt / x` declines") {
           // D2L and L2D are one call at level 3 and exact AVX2 forms below it; the defaults'
           // unknown level refuses both (VARKA-343, parts 343L and 343b2b).
           for (options <- Seq(VarkaMatrix.base.withUseAVX(2),
               VarkaMatrix.base.withUseAVX(VarkaEmitOptions.AVX512_LEVEL))) {
             assert(VarkaExpressionCompiler.compile(Seq(out(MultiplyDTInterval(dt, Literal(2.5d)))),
               withLong, options).isPresent, s"level ${options.useAVX}")
           }
           val unknown = declineReason(MultiplyDTInterval(dt, Literal(2.5d)), withLong)
           assert(VarkaMatrixRuns.isUnknownLevelDecline(unknown), unknown)
           assert(declineReason(DivideDTInterval(dt, Literal(2.5d)), withLong) ===
             quotientByFloatingReason)
         }

   (`VarkaEmitOptions` and `VarkaMatrix` are imported by the suite on master; import
   `VarkaMatrixRuns` from `codegen.varka` beside them if it is not. `declineReason` compiles at
   the defaults, the unknown level, whatever the matrix's configuration.)
5. Coverage rows in "Floating arithmetic over other types" (`$S/VarkaCoverageSuite.scala`),
   after `ym * 0.5D`: `Row("dt * x", "the long count's double product, guarded to the doubles
   whose HALF_UP fits the long, truncated by D2L and stepped away from zero at a half", domain
   = Some("x BETWEEN -0.5D AND 0.5D"))` (so every product fits the long) and `Row("dt * f",
   "the float factor widened exactly, then the same", domain = Some("f BETWEEN -0.5F AND
   0.5F"))`. `sql/varka/plans/m8/census_curated.txt`, the line of B0 item 6: its sixth field
   `dt,int~,dec~` becomes `dt,int~,dec~,float,double`, and its note loses its last clause, "; a
   floating factor is 342's lowering". Nothing else of the line moves.
5a. `sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaFloatingLaneSuite.scala`
   (342d's text), by hand (*after E2 (c)*): the decline test "an interval quotient or a day-time
   product by a floating factor plans no Varka node" stays as 342d wrote it, `dt * x` in its
   list included: the shared sessions keep the unknown level, where `dt * x` declines. After
   that test add the fused one, at level 2:

         // varka-session-lint: allow the day-time product's one path test, through the cached scan
         test("dt * x and dt * f fuse at emit level 2 and round to the microseconds as the row " +
             "engine does") {
           // The factors at most one in magnitude, since the fixture holds the long's ends, past
           // which Spark raises; the cells themselves are VarkaEmitterIntervalScaleSuite's. An
           // x86 session's level: these sessions keep the unknown one (VARKA-343, part 343L).
           withBothConf(SQLConf.VARKA_EMIT_USE_AVX.key, "2") {
             val q = "SELECT dt * x AS a, x * dt AS b, dt * CAST(x AS FLOAT) AS c, " +
               s"dt * 0.5D AS d FROM $intervals WHERE x BETWEEN -1.0D AND 1.0D"
             val actual = varkaSpark.sql(q)
             assertFused(actual.queryExecution.executedPlan)
             checkAnswer(actual, spark.sql(q))
             assertKernelsRan(actual.queryExecution.executedPlan)
           }
         }

   The rows the filter keeps (`$intervals`, master's fixture) are `(Long.MinValue, 0.5)`, `(-1,
   -1.0)`, `(Long.MaxValue, 0.25)` and `(0, 1.0)`: every product fits the long (`(double)
   Long.MaxValue` is `2^63`, so the third is `2^61`).
6. `PLAN.md` row 342: after `**Started** (` insert `*342e built <date>, \`VARKA-342.md\` A.5:
   a day-time interval times a FLOAT or DOUBLE factor fuses at every named emit level;* `.
   `VARKA-342.md` section 10: `### 342e as built (<date>)`, at most 10 lines.

**The two-lane host.** The arm is reached through `twoLaneMeasured(.., IntOp.MUL, ..)`, whose
`ownLongCheck` matches an `IntArith` of `MUL` on the long lane; this form's root is the
`IntArith` of `ADD` of A.4, so the form keeps fusing at two 64-bit lanes, unmeasured: the house
rule declines a family there only where a measurement shows it slower (342r).

**Commands.** The compile; `flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly
*VarkaEmitterIntervalScaleSuite'`: `Passed: Total 5, Failed 0, Errors 0, Passed 5`;
`'catalyst/testOnly *VarkaExpressionCompilerSuite'`: `All tests passed.`; the regeneration
(last line begins `varka regen: done`); `grep -c '"sql" : "dt \* x"\|"sql" : "dt \* f"'
sql/varka/coverage.json` prints `2`; the bytes check: `bytes: 0 committed shapes moved, 4
shapes added`; `python3 dev/varka_census.py --check` prints nothing, and `awk -F'\t'
'$1=="dt_times_num"{print $8}' sql/varka/plans/m8/census.tsv` prints `int,float,double,dec,dt`;
`'sql/testOnly *VarkaFloatingLaneSuite *VarkaCoverageHarnessSuite'`: no test fails;
`BUILD_SHEETS.md` 6. **Planted fault:** in step 1 pass `LaneType.INT` for the lane:
`VarkaExpressionCompilerSuite`'s new test fails (the count is on the long lane, and the
check of `d250fd3eb78` declines it). **Not here:** the quotients.

#### Part 342q: the two quotients (build sheet, fix round)

- **Title:** `[VARKA-342] Fuse an interval divided by a floating factor`. **Branch:**
  `varka-342q-quotients` from master after 342e merged. **Builder:** Sonnet-class. **Size:**
  about 290 counted lines. Nothing of it is on a branch but the lowering (`scaledHalfUp`'s
  `DIV`, which 342d's suite already holds to Spark at the IR). *Changed in fix round 2 (review
  1, findings 3, 5 and 6): one text, for a master with 343L and 343b2b merged; the session
  test's fixture and names are written out. After E2 (c): `dt / x` fuses at the named levels
  and declines at the unknown one, so its tests run at level 2 and its benchmark sections name
  that level.*

**B0.** 342e is merged (`grep -c 'e instanceof MultiplyDTInterval' $CG/VarkaNodeCompiler.java`
at least `1`); `grep -c 'case DivideYMInterval _, DivideDTInterval _ ->'
$CG/VarkaNodeCompiler.java`: `1`; `grep -c 'a floating divisor is row 342.s lowering'
$CG/VarkaIntervalCompiler.java`: `1`; SPARK-61352 is in the tree (`git merge-base
--is-ancestor 8621489fe80 HEAD` exits 0); 342e's B0 item 4 (343L and 343b2b merged) prints
what it says; `grep -n '^ym_div_num|\|^dt_div_num|' sql/varka/plans/m8/census_curated.txt`
prints the two lines, `ym_div_num`'s sixth field (fused today) empty and `dt_div_num`'s
`dt,int~,dec~`, its note ending "; a floating factor is 342's lowering".

**Steps.**

1. `$CG/VarkaIntervalCompiler.java`: rename the body of `floatingProduct` to a private
   `floatingScaled(IntOp op, Expression interval, Expression factor, LaneType lane, Expression
   whole, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink)`, in which the
   operand's description is `op == IntOp.MUL ? "the multiplied interval" : "the divided
   interval"`, the factor's decline `"an interval " + (op == IntOp.MUL ? "factor" : "divisor")
   + " computed on the " + ... + " lane"`, and the call `scaledHalfUp(op, ...)`. Then
   `floatingProduct(...)` is `return floatingScaled(IntOp.MUL, interval, factor, lane, whole,
   inputs, literals, sink);` and a new `floatingQuotient` with the same parameters returns
   `floatingScaled(IntOp.DIV, ...)`, its javadoc naming Spark's `INTERVAL_DIVIDED_BY_ZERO` on
   a zero divisor of either sign, which the `DIV`'s `FAIL` mode declines.
2. Same file: `divide(DivideYMInterval m, ...)`, first statement:

         if (VarkaFloatingCompiler.floatingLane(m.num().dataType()).isPresent()) {
           return floatingQuotient(m.interval(), m.num(), LaneType.INT, m, inputs, literals,
               sink);
         }

   and `dayTimeDivide`, directly after `DataType type = m.num().dataType();`, the same with
   `LaneType.LONG`; its type decline loses ": a floating divisor is row 342's lowering".
3. `$CG/VarkaNodeCompiler.java`, `floatingDecline`: the pass list gains `|| e instanceof
   DivideYMInterval || e instanceof DivideDTInterval` beside the products' clause, and the
   switch's case `DivideYMInterval _, DivideDTInterval _` with its comment goes.
4. `$CS/VarkaExpressionCompilerSuite.scala`: the two assertions `=== quotientByFloatingReason`
   become, for `DivideYMInterval(ymm, Literal(2.5d))`, an assertion that it compiles at the
   defaults (`VarkaExpressionCompiler.compile(Seq(out(DivideYMInterval(ymm, Literal(2.5d)))),
   withIntervals).isPresent`: `I2D` and `D2I` need no level), and for `DivideDTInterval(dt,
   Literal(2.5d))` what 342e's test holds of the product: inside its loop over levels 2 and 3
   also `compile(Seq(out(DivideDTInterval(dt, Literal(2.5d)))), withLong, options).isPresent`,
   and after it `VarkaMatrixRuns.isUnknownLevelDecline(declineReason(DivideDTInterval(dt,
   Literal(2.5d)), withLong))`. 342e's test is renamed "`dt * x` and `dt / x` by a double fuse
   at the named emit levels and decline at the unknown one"; the value
   `quotientByFloatingReason` goes.
5. `sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaFloatingLaneSuite.scala`:
   - after the value `scaledIntervals` (342d's), the fixture, written out (review 1 finding 6;
     modelled on master's `cacheIntervals`):

         private val quotients = "varka_float_quotients"

         /** The quotients' cells: both zero divisors, a divisor that takes each count past its
          *  type, one that fits, and nulls (VARKA-342, part 342q). */
         private def cacheQuotients(session: SparkSession): Unit = {
           def ym(months: Int): java.time.Period = java.time.Period.ofMonths(months)
           def dt(micros: Long): java.time.Duration =
             java.time.Duration.of(micros, java.time.temporal.ChronoUnit.MICROS)
           val rows = Seq(Row(ym(1), dt(1L), 0.0), Row(ym(1), dt(1L), -0.0),
             Row(ym(Int.MaxValue), dt(Long.MaxValue), 1.0E-30), Row(ym(7), dt(7L), 2.0),
             Row(null, null, 0.0), Row(ym(5), dt(5L), null))
           val schema = StructType(Seq(StructField("ym", YearMonthIntervalType()),
             StructField("dt", DayTimeIntervalType()), StructField("x", DoubleType)))
           session.createDataFrame(spark.sparkContext.parallelize(rows, 1), schema)
             .createOrReplaceTempView(quotients)
           session.catalog.cacheTable(quotients)
         }

         /** The two quotient forms: both fuse at emit level 2, at which the test runs (343b2b,
          *  342q); `dt / x` declines at the unknown one these sessions keep (343L). */
         private def quotientForms: Seq[String] = Seq("ym / x", "dt / x")

   - in `beforeAll`, after `cacheIntervals(varkaSpark)`, the two lines
     `cacheQuotients(spark)` and `cacheQuotients(varkaSpark)`;
   - the test, with the comment `// varka-session-lint: allow the errors a declined quotient's
     batch raises, as the user sees them` above it:

         test("ym / x and dt / x by a zero divisor raise INTERVAL_DIVIDED_BY_ZERO with the " +
             "query's context, and past the type Guava's error, as the row engine does") {
           // An x86 session's level, at which both forms fuse (VARKA-343, part 343L).
           withBothConf(SQLConf.VARKA_EMIT_USE_AVX.key, "2") {
             for (ansi <- Seq(false, true)) withAnsi(ansi) {
               for (e <- quotientForms) {
                 val zero = s"SELECT $e AS v FROM $quotients WHERE x = 0.0D"
                 withClue(s"$zero, ansi $ansi") {
                   val actual = varkaSpark.sql(zero)
                   assertFused(actual.queryExecution.executedPlan)
                   val got = intercept[org.apache.spark.SparkArithmeticException](actual.collect())
                   val want =
                     intercept[org.apache.spark.SparkArithmeticException](spark.sql(zero).collect())
                   assert(got.getCondition === "INTERVAL_DIVIDED_BY_ZERO", got.getMessage)
                   assert(got.getCondition === want.getCondition)
                   assert(got.getMessage === want.getMessage)
                   assert(got.getMessage.contains("== SQL"), got.getMessage)
                 }
                 val past = s"SELECT $e AS v FROM $quotients WHERE x = 1.0E-30D"
                 withClue(s"$past, ansi $ansi") {
                   val actual = varkaSpark.sql(past)
                   assertFused(actual.queryExecution.executedPlan)
                   val got = intercept[Exception](actual.collect())
                   val want = intercept[Exception](spark.sql(past).collect())
                   assert(rootCause(got).getClass === rootCause(want).getClass)
                   assert(rootCause(got).getMessage === rootCause(want).getMessage)
                 }
                 val fits = s"SELECT $e AS v FROM $quotients WHERE x IS NULL OR x = 2.0D"
                 val actual = varkaSpark.sql(fits)
                 assertFused(actual.queryExecution.executedPlan)
                 checkAnswer(actual, spark.sql(fits))
               }
             }
           }
         }

     `withAnsi`, `assertFused` (`VarkaSharedSessions.scala` on master),
     `varkaSpark` and `rootCause` (342d) exist; `Row`, `SparkSession`, `StructType`,
     `StructField`, `DoubleType`, `DayTimeIntervalType` and `YearMonthIntervalType` are
     imported by the suite on master. The rows each query keeps: `zero`, the two zero divisors
     (the null count beside a zero answers null in Spark, raising nothing); `past`, the counts'
     maxima over `1e-30`; `fits`, `7 / 2.0`, a tie that rounds to 4, and the null divisor;
   - the decline test "an interval quotient or a day-time product by a floating factor plans
     no Varka node" (342d's, unchanged by 342e) is renamed "a day-time product or quotient by a
     floating factor plans no Varka node at the sessions' unknown emit level", and its list
     `Seq("ym / x", "dt * x", "dt / x", "ym / 4.0D")` becomes `Seq("dt * x", "dt / x")`: the two
     year-month forms fuse now at every level (the test above holds `ym / x` through a session,
     the compiler suite the literal `ym / 4.0D`'s arm, step 4), and the two day-time ones still
     decline at -1. It is narrowed to what still holds, not dropped to make a run pass.
   **If the context assertions fail, stop and report**: before SPARK-61352 the Varka side lost
   the `== SQL ==` block (`VARKA-414.md` 9). Do not weaken them.
6. Coverage rows in "Floating arithmetic over other types" (`$S/VarkaCoverageSuite.scala`,
   after 342e's `dt * f`), each `Row(sql, note, domain = Some(domain))`: `ym / x` (domain `x >=
   1.5D OR x <= -1.5D`: every quotient then fits the int, the least month over `-1.0` being the
   one that does not), `ym / f` (domain `f >= 1.5F OR f <= -1.5F`), `dt / x` and `dt / f` with
   the same domains (the greatest long over `1.0` rounds to `2^63`, past the long); the notes
   "the double quotient, rounded to the months by HALF_UP; a zero divisor of either sign
   declines its batch", "the float divisor widened exactly, then the same", "the same to the
   microseconds", "the float twin". `sql/varka/plans/m8/census_curated.txt`: `ym_div_num`'s
   sixth field (fused today), empty, becomes `float,double`; `dt_div_num`'s sixth field
   `dt,int~,dec~` becomes `dt,int~,dec~,float,double` and its note loses its last clause, "; a
   floating factor is 342's lowering".
7. `$BJ/VarkaFloatingArithmeticBaselineBenchmark.java`: the `day-time interval dt / x`
   sections become `Fusion.WHOLE` with the settings `Map.of(SQLConf.VARKA_EMIT_USE_AVX().key(),
   "2")` in place of `Map.of()` (import `org.apache.spark.sql.internal.SQLConf` if the file
   lacks it), and the class javadoc says so: an x86 session's level, at which `dt / x` fuses
   (*after E2 (c)*: at the emitter's default, the unknown level, it declines). Each section is
   planned under its own settings by the queries suite too (its comment: "Each section is
   planned under its own settings, as the benchmark plans it"), so the fuse check agrees on
   every host.
8. `sql/varka/fuzz/expected_errors.tsv`: an `INTERVAL_DIVIDED_BY_ZERO` line, `both`,
   `LEGACY,ANSI`, family `Floating arithmetic over other types`, reason "ym / x with a zero x
   (either sign) raises in every mode; the kernel's checked division declines the batch and
   the row engine raises it (VARKA-342)", unless `grep -c '^INTERVAL_DIVIDED_BY_ZERO'
   sql/varka/fuzz/expected_errors.tsv` already prints `1`, in which case the family is added
   to that line's list.
9. `PLAN.md` row 342 and `VARKA-342.md` section 10, as the other parts.

**Commands.** The compile; `'catalyst/testOnly *VarkaExpressionCompilerSuite
*VarkaEmitterIntervalScaleSuite'`: no test fails; the regeneration; `grep -c '"sql" : "ym /
x"\|"sql" : "ym / f"\|"sql" : "dt / x"\|"sql" : "dt / f"' sql/varka/coverage.json` prints
`4`; the bytes check: `bytes: 0 committed shapes moved, 8 shapes added`; `python3
dev/varka_census.py --check` prints nothing, `awk -F'\t' '$1=="ym_div_num"{print $8}'
sql/varka/plans/m8/census.tsv` prints `float,double` and `awk -F'\t'
'$1=="dt_div_num"{print $8}' sql/varka/plans/m8/census.tsv` prints `int,float,double,dec,dt`;
`'sql/testOnly *VarkaFloatingLaneSuite
*VarkaFloatingArithmeticBaselineQueriesSuite *VarkaCoverageHarnessSuite
*VarkaCoverageDifferentialSuite'`: no test fails; `BUILD_SHEETS.md` 6. **Planted fault:** in
`floatingScaled` pass `IntOp.MUL` for every `op`: the session test's `fits` query answers the
product, and `checkAnswer` fails.

**Not here:** the three integral declines that name VARKA-414 in `VarkaIntervalCompiler`
(`ym / -1`, `dt / -1`, a decimal divisor under one): rows 414 and 103 lift those.

#### Part 342r: the measurement (build sheet, fix round)

- **Title:** `[VARKA-342] Results: floating arithmetic and the interval forms on kernels`.
  **Branch:** `varka-342r-results` from master after 342q merged. **Builder:** Sonnet-class;
  the dispatches are the lead's unless the builder has `gh` rights.
- **Steps:** the results procedure of `VARKA-343.md` A.5.0 for
  `VarkaFloatingArithmeticBaselineBenchmark` and `VarkaFloatingBaselineBenchmark`
  (`sql/core/benchmarks`), at both widths.
- **Score** section 4's predictions 3 and 4 and A.7's (`dt / x` is a kernel section since
  342q, at emit level 2 by its settings, `VARKA-343.md` A.5).
- **The two-lane rule, a number:** if, at 128 bits with no nulls, "varka on" of `year-month
  interval ym * x` is under its "spark row path" rate, record it and file a row whose change
  is, in `floatingScaled`, `if (sink.declinesTwoLaneChecks()) return
  sink.decline(VarkaKernelPlan.TWO_LANE_CHECK_REASON, whole);`. Otherwise nothing. *After E2
  (c):* `day-time interval dt / x` at 128 bits runs at level 2 by its settings, a level a
  session on that host does not resolve to (it keeps the unknown level, where `dt / x`
  declines: `VARKA-343.md` A.4.5), so its 128-bit number is recorded, not ruled on.
- **The lead's JVM command, owed since step 3:** `dev/varka_emit.sh "(x * y) + z" --columns
  x:double,y:double,z:double --asm` shows no `vfmadd` in the dense loop.

### A.6 Tests, proofs, fuzzing and coverage

| suite or file | part | what it holds |
| :--- | :--- | :--- |
| `VarkaEmitterIntervalScaleSuite` (new, JUnit, own JVM) | 342d, 342e | the four forms against Spark's `eval` at the IR, every width, length, null pattern and body |
| `VarkaExpressionCompilerSuite` | 342d, 342e, 342q | the compiled shape and the decline texts |
| `VarkaFloatingLaneSuite` | 342d, 342q | the session path and the raised errors (342q: with their SQL context) |
| `fp_half_up.smt2` (merged, nightly) | all | the rounding and both guards against Guava for every double inside them |

Fuzzing: the Spark fuzzer draws fused coverage rows, so it draws the new rows;
`expected_errors.tsv` lists what they raise. The IR fuzzer needs nothing: no node is new.

### A.7 Predictions registered before any run

1. No committed shape's bytes move in any part (the bytes check).
2. `ym * x` at least twice the row path at 256 bits with no nulls; `dt / x` at least 1.5
   times (the `D2L` assembly is 38 lane calls; the section runs at emit level 2, which takes
   the assembly since 343b2b).
3. At 128 bits both are within 0.7 to 1.5 times the row path: masks at two 64-bit lanes fall
   back on x86.
4. `VarkaEmitterIntervalScaleSuite` under 60 s in its own JVM (44 s on the branch with 4 tests).

### A.8 Who builds it

| part | builder | why |
| :--- | :--- | :--- |
| 342d | Sonnet-class | the code is on a branch and written out where it does not merge; the lowering is checked in Python and proved |
| 342e | Sonnet-class | one branch statement and one test |
| 342q | Sonnet-class | the arms repeat 342d's; the context test is stated exactly, with a stop rule |
| 342r | Sonnet-class | dispatches, files, scores, one rule with a number |

### A.9 Risks

1. The quotient's error context: settled only by 342q's test on a session.
2. The merged code may not compile on master. Held by each part's first command.
3. The branch lived on one laptop. Held: it is on the fork since 28 November 2026 (A.12
   question 2).

### A.10 The house rules that bind this row

From `sql/varka/AGENTS.md`: "Never remove, skip or weaken a failing test" (342q's context
test has a stop rule, not a fallback); generated files are regenerated, never edited; "A pull
request stays under 1000 changed lines"; the census claims are hand-curated in
`census_curated.txt` and checked by `dev/varka_census.py --check`. Do not: copy the branch's
`PLAN.md` or `VARKA-342.md` hunks; edit `census.tsv` or `coverage.json` by hand; lift a
`VARKA-414` integral decline (rows 414 and 103).

### A.11 Outcome

To be written when the row closes.

### A.12 Questions for the owner

*Changed in fix round 2 (review 1, findings 4 and 10): both answered on 28 November 2026.*

1. **The day-time forms in the census**, which hang on question 1 of `VARKA-343.md` A.12,
   the unknown emit level. Its options: (a) leave the unknown level declining every
   conversion between the long and the double lanes: then 342e and 342q fuse the day-time
   forms only in sessions at a named level, add no day-time coverage row, and the four
   `dt_times_num` and `dt_div_num` pairs over `float` and `double` stay uncredited, with the
   reason "fuses at a named emit level; the coverage table compiles at the unknown one" in the
   census note; (b) take the exact software forms there (part 343L of row 343), so the
   coverage table fuses them and the four pairs are credited. *Recommended: (b).* **Answered
   on 28 November 2026: (b), build 343L.** 343L is first in row 343's order, so 342e and 342q
   are written for a master with it merged (one text, A.5): they add the day-time coverage rows
   and claims, and `dt / x`'s benchmark sections fuse. **Answered on 28 November 2026, later:
   (c)** (decision E2 of `VARKA-343.md` A.12): the coverage table compiles at emit level 2, an
   x86 session's, so the four pairs are credited while the unknown level keeps declining; (b)
   was replaced because it changed production and needed a full-strength builder before 1
   December. 342e and 342q add the same rows and claims; their session tests and `dt / x`'s
   benchmark sections name level 2.
2. **Push `varka-342de-r` to the fork.** *Recommended: yes.* **Answered on 28 November 2026:
   done.** It is on the fork `MaxGekk/spark` as `varka-342de-r` (`688414d4e10`); the sheets
   take the code from the local branch, or from the fork branch when the local one is gone
   (`VARKA-343.md` A.5.0, "Where the code comes from").

### A.13 What only a JVM settles

The emitted kernels against Spark (`VarkaEmitterIntervalScaleSuite`), the regeneration, the
width audit under AVX2, the option matrix, the runners' results, the `--asm` reading of 342r,
and that the error of a declined quotient keeps its `== SQL ==` block (342q).

## 0. What exists now (admission check)

From the code at `7ddd8f6715e` (27 October 2026; Varka paths under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` unless named, Spark
paths under `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/expressions/`), and from
open PR #1065 (branch `varka-341-semantics`, the comparisons in Spark's order), on which this row
builds. The row's text is the milestone plan's 2.7, bullets 2 and 3, and item 35 of `m10/SCOPE.md`.

1. **The lanes are read; the ordering is #1065's.** `FLOAT` and `DOUBLE` are `OnLane`
   (`varka/VarkaTypes.java:320-325`), `Lane` has both members (`varka/Lane.java:82-90`), and a
   floating literal is its bits in the integral tables (`:348-357`). #1065 lowers `Compare` at a
   floating lane in Spark's order, NaN greatest and `-0.0 = 0.0` (its `varka/VarkaVectorWalk.java:
   1370-1483`), and `isnan` as `x = NaN` (its `VARKA-341.md` 5.1). Still owed by 341 there: the IR
   fuzzer's floating sequence, the warm-up blocks and the mask crossing between a double and a long
   mask (5.1, "Still owed").
2. **`floatingDecline` is an allowlist, and every form of this row is off it.**
   `VarkaNodeCompiler.java:331-352` declines `BinaryArithmetic`, `UnaryMinus`, `Abs` and the four
   interval classes as "floating arithmetic (row 342)", and `Signum`, `Sqrt`, `ToDegrees` and
   `ToRadians` by its default; #1065 admits the comparisons by class name (its `:338-349`). A
   `RuntimeReplaceable` passes first, so `UnaryPositive` and `try_divide` are asked again as their
   replacements.
3. **The IR has integral arithmetic only.** `IntOp` is `ADD, SUB, MUL` (`varka/VarkaVectorIR.java:
   395`), `Overflow` is `WRAP, FAIL, NULL` (`:415`), `IntArith` needs its operands on one lane
   (`:672`) and `IntNeg` carries an `abs` flag (`:701`). `emitIntArith` emits `WRAP` as the bare
   lanewise op through `lane.lanewiseVV` (`varka/VarkaVectorWalk.java:639-670`), which a
   `DoubleVector` has; its `FAIL` and `NULL` checks are sign tests over `lane.integral()`, and
   they exist only while `checkIntOverflow` is on (`varka/Slots.java:806`). The range analysis
   answers `UNKNOWN` for a node off the `INT` lane (`varka/VarkaRangeAnalysis.java:234`), before
   its switch reads the kind (`:305`). No node divides
   by a column at any lane (`ConstDivide`, `:764`, is a constant integer divide), and none calls a
   math operator.
4. **The interval arms are integral.** `ym * k` takes an int32 `k` (`VarkaIntervalCompiler.java:
   345-352`), `ym / k` an integral literal (`:385-398`); no arm claims `MultiplyDTInterval` or
   `DivideDTInterval`, and a floating factor never reaches either, `floatingDecline` asking first.
5. **What Spark computes.** No form below has a mode-dependent answer except the division.
   - `+`, `-`, `*` over `DOUBLE` and `FLOAT`: the Java operator in every mode
     (`arithmetic.scala:345-353`; ANSI checks only the integral types), so infinity and NaN are
     answers, never errors. `try_add`, `try_subtract` and `try_multiply` over numbers are the same
     classes in TRY (`TryEval.scala:94`, `:233`, `:272`), with the same answers.
   - `/`: `Divide` declares `DOUBLE` or `DECIMAL` only (`arithmetic.scala:880`), and the
     analyzer's `DivisionTypeCoercion` puts a `FLOAT`, `INT` or `BIGINT` operand under a cast to
     double (`../analysis/DivisionTypeCoercion.scala:32-33`, registered at `TypeCoercion.scala:67`
     and `AnsiTypeCoercion.scala:94` in the same directory). It is `DivModLike`: the divisor is
     zero by `x == 0` (`arithmetic.scala:689`), true for `-0.0` and false for NaN; under LEGACY
     and TRY a zero divisor gives null, under ANSI it raises `DIVIDE_BY_ZERO` unless the dividend
     is null, which gives null (`:701-728`); a divisor null gives null in every mode.
     `failOnError` is ANSI alone (`:878`), so `try_divide`, which is `Divide(..., TRY)` over
     numbers (`TryEval.scala:135-143`), is LEGACY's answer. A non-zero literal divisor drops the
     check (`arithmetic.scala:696`).
   - Negation is `-(x)` (`arithmetic.scala:76-83`) and `abs` is `Math.abs` (`:198`) in every mode.
   - `signum`, `sqrt`, `degrees`, `radians` are `UnaryMathExpression`s over `DOUBLE` only
     (`mathExpressions.scala:66-86`; `ImplicitCastInputTypes` at `:67` and `inputTypes` at `:70`
     put another numeric operand under a cast to double), generated as `java.lang.Math.<fn>`; the
     interpreted path's `scala.math` functions call the same `java.lang.Math` methods
     (scala-library 2.13.18, `pom.xml:182`; its sources' `scala/math/package.scala:126`, `:135`,
     `:208`, `:239`, `:293` and `Numeric.scala:173` delegate to `java.lang.Math`). `signum` also
     takes both interval types and answers a double (`mathExpressions.scala:893-899`).
   - JDK 25's `Math` (`src/java.base/share/classes/java/lang/Math.java` in the local source):
     `toRadians` and `toDegrees` are one multiply by a private constant (`:166`, `:172`,
     `:295-313`), `abs` clears the sign bit (`:2006-2008`), `signum` returns its operand for a zero
     or a NaN and `copySign(1.0, d)` otherwise (`:2624-2626`), `sqrt` is IEEE's (`:413`).
   - The interval forms: `ym * x` is `DoubleMath.roundToInt(m * (double) x, HALF_UP)` and `dt * x`
     `roundToLong` (`intervalExpressions.scala:649-650`, `:703-704`, the codegen the same);
     `ym / x` and `dt / x` divide instead, after raising `INTERVAL_DIVIDED_BY_ZERO` on `x == 0` in
     every mode (`:747-756`, `:794`, `:873`). None of the four has an evaluation mode. Guava
     33.6.0's `roundIntermediate` raises on NaN and the infinities and takes HALF_UP as `rint`
     plus a tie test (`DoubleMath.java:54-98`); `roundToInt` and `roundToLong` then raise an
     `ArithmeticException` when the rounded value leaves the int or the long (`:130-135`,
     `:157-162`).
6. **What the JIT does with them** (HotSpot source of `jdk-25-ga`, `src/hotspot/`):
   - **No contraction to FMA.** `FmaVD` and `FmaVF` are made only from `FmaD` and `FmaF`, that is
     from `Math.fma` or the Vector API's `FMA` (`share/opto/vectornode.cpp:83-86`); aarch64's
     multiply-accumulate rules match the integral vectors only (`cpu/aarch64/aarch64_vector.ad:
     2529-2533`), its `vfmla` matches `FmaV*` only (`:2579-2582`), and no x86 rule matches an
     `AddVD` over a `MulVD`.
   - **Negation and `abs` are bit operations on both engines.** `NegVD` and `AbsVD` are an XOR
     and an AND with the sign mask (`cpu/x86/x86.ad:8297-8298`,
     `cpu/x86/c2_MacroAssembler_x86.cpp:910-923`), as `Math.abs` and `dneg` are (`NegD` at
     `cpu/x86/x86.ad:3521`, `:3533` and `cpu/aarch64/aarch64.ad:14103`, `AbsD` at `:14164`;
     `AbsVD` and `NegVD` at `cpu/aarch64/aarch64_vector.ad:1862`, `:2035`).
   - **The row engine's NaN payload is not fixed.** C2 commutes a scalar floating add
     (`share/opto/addnode.cpp:555-558`, `:604-607`), so which payload `NaN1 + NaN2` returns in
     Spark depends on C2's ordering: 2.7's NaN rule, NaN-ness for a computed NaN, is the only
     comparison Spark itself would pass.
   - C2 replaces a scalar division by a power of two with a multiply by its reciprocal
     (`share/opto/divnode.cpp:995-1007`); that reciprocal is exact, so the row engine's answer is
     still the correctly rounded quotient.
7. **The census lacks the operators.** `operator_census.json` has no `ADD`, `SUB`, `MUL`, `DIV`,
   `NEG` or `ABS` cell for either floating species. `double.SQRT` is intrinsic at every width on
   the laptop and on the EPYC 7763 (`results/VARKA-376-census-epyc-7763.json`); the double
   compares are fallbacks at 128 bits on both, so a zero test there is too; `double.D2I` is
   intrinsic and `double.D2L` a fallback at 128 and 256 bits on the EPYC.
8. **Of this row's forms, the baseline covers only the chain.** Row 377's
   `VarkaFloatingBaselineBenchmark` times a comparison, `(x + y) * z - x`, a cast and a choice
   (`VarkaFloatingBaselineBenchmark.java:93-102`); the chain is 57.9 ns a row for `DOUBLE` and
   57.3 for `FLOAT` on Spark's row path at 256 bits with no nulls
   (`VarkaFloatingBaselineBenchmark-jdk25-runner-epyc7763-results.txt` in
   `sql/core/benchmarks/`). Nothing times `/`, the unary forms or the interval forms, and the class
   throws when a section fuses (`VarkaFloatingBaselineBenchmark.java:131-137`), which its
   comparison and choice sections do once #1065 merges.
9. **The conformance suites cancel the arithmetic naming this row.** #1065's
   `VarkaDoubleConformanceSuite` and `VarkaFloatConformanceSuite` cancel the null-handling
   `x + y` and the overflow check (`VarkaTypeConformance.java:172-187`, `:236-261` on its branch)
   naming the census rows, 342 among them.

### What differs from the row text and the milestone plan

- **Most of the division and the functions over a non-`DOUBLE` column are 343's casts.** `i / j`,
  `f / g`, `sqrt(f)`, `signum(i)` and their kin are this row's nodes over casts to double (item
  0.5), and the casts cross the kinds or the lane count, which 341's 2.4 left to 343. This adds
  an edge the milestone plan lacked: the foundations table (4.1, F2) lists 343 before 342 but
  sets 343 beside 341 and 342 under 356 to 358 only, and the older wave table (4, wave 3) lists
  342 before 343. F2's text now carries it, in its own "under" sense ("343's conversions under
  342's cast and interval forms"). This row fuses them the day 343's conversions land (question 1).
- **The interval forms need 343's conversions, not 344's `rint`.** HALF_UP is built from a
  truncation and a half-step test (2.4), which 343's `D2I` and `D2L` give.
- **Three modes, two lowerings.** `try_divide` is LEGACY's division, and no other form has a mode.
- **"Bit-identical" is read through 2.7's NaN rule**: bit for bit everywhere, a computed NaN by
  NaN-ness (item 0.6). Negation, `abs` and `signum` keep their NaN's bits on both engines and are
  compared bit for bit.

## 1. Goal and non-goal

`+`, `-`, `*`, negation and `abs` over `DOUBLE` and `FLOAT`, and `/`, `try_divide`, `sqrt`,
`signum`, `degrees` and `radians` over `DOUBLE`, fuse with the row engine's bits in every mode; a
zero divisor gives null under LEGACY and TRY, and under ANSI the batch goes to the row engine,
which raises `DIVIDE_BY_ZERO`. After 343: the same forms over casts to double, and the interval
products and quotients by a floating factor. Not this row: `%`, `pmod` (346), the rounding
functions (344), the math family (345 to 358), the casts themselves (343).

## 2. Design

### 2.1 Operators, not nodes

By `VARKA-475.md` 4 rule 1, the forms take existing nodes at a floating lane:

- `IntArith(ADD | SUB | MUL, WRAP)` at `FLOAT` and `DOUBLE`, the bare lanewise op. `FAIL` and
  `NULL` are refused there: no floating `+`, `-` or `*` has a check.
- **`IntOp.DIV`**, admitted at a floating lane only (the constructor refuses it at an integral
  one, which row 30 may lift), with the mode meaning the zero divisor's disposal (2.2).
- `IntNeg(WRAP, x)` and `IntNeg(WRAP, x, abs)` at a floating lane: the lanewise `NEG` and `ABS`.
- **One new node, `MathUnary(MathFn fn, child)`**, with `MathFn.SQRT` its first member: a call of
  a lanewise math operator is a new kind of computation, and 356 to 358 add their functions to it.
- `GuardedRange` at a floating lane (2.4) holds its bounds as the lane's bits and compares by
  IEEE, a NaN lane being outside.
- *Added 20 November 2026 (5.3), the lead's settlement of 30 October 2026 in `VARKA-346.md` 8:*
  **a second new node, `Fma(a, b, c)`**, `a * b + c` rounded once at a floating lane, evaluated by
  `Math.fma`, which row 346's remainder and `FLOAT` round-to-odd use. No Spark expression builds
  it, and it is never made from a multiply and an add: the no-contraction rule below stands.

Two rules hold at a floating lane and are written into `IntArith`'s javadoc: **no algebraic
rewrite** (`x + 0.0` is not `x` at `-0.0`, which is what 337's canonicalisation node relies on;
`x * 0.0` is not `0.0` at NaN or infinity; `x - x` is not `0.0`), and **operands keep Spark's
order**. `checkIntOverflow` reaches no floating node: its switch is about integer overflow, and
the zero test, like a `GuardedRange`, is unconditional.

### 2.2 The division and its zero divisor

| divisor | LEGACY, TRY | ANSI |
|---|---|---|
| a column | `DIV NULL`: a lane with `y == 0.0` null | `DIV FAIL`: a live one condemns the batch |
| non-zero literal | `DIV WRAP` | `DIV WRAP` |
| literal zero, either sign | declines by name | declines by name |

The test is the IEEE `compare(EQ, 0.0)`, which is Spark's `x == 0`: both zeros, never NaN. The
`FAIL` mask is ANDed with the node's word, the operands' validity, so a null dividend over a zero
divisor is null under ANSI as in `DivModLike.eval`. Only the divisor is parked for the check,
since the test reads neither the dividend nor the quotient. A division under a `CASE` or `IF` arm
has its guard qualified by the arm (`guardUnderArm`, on by default,
`varka/VarkaEmitOption.java:400`), so `IF(y = 0, 0.0D, x / y)` fuses under ANSI over batches
holding zeros. The commonest spelling, with a `NULL` arm or a `CASE` without `ELSE`, needs the
typed null leaf of row 333 and fuses when it lands. A division on the right of an `AND` still
condemns from the left's rejected rows, which is `m10/SCOPE.md` item 96.

**Never a reciprocal.** `x / c` is a lanewise `DIV` by the broadcast `c`. A multiply by `1 / c`
is exact when `c` is a power of two, as C2's own rewrite is (item 0.6); it is not taken, the
division being cheap beside the row path and the rule kept without an exception.

### 2.3 The exact functions

- **`sqrt(x)`**: `MathUnary(SQRT, x)`, the lanewise `SQRT`, correctly rounded as `Math.sqrt` is;
  `sqrt(-0.0)` is `-0.0` and a negative `x` NaN on both.
- **`degrees(x)`, `radians(x)`**: `IntArith(MUL, WRAP, x, k)`, `k` the bits of
  `Math.toDegrees(1.0)` or `Math.toRadians(1.0)` read at compile time, never retyped digits: one
  multiply by the JDK's own constant (`Math.java:172`, `:166`). Exact while the JDK's function is
  one multiply, which a test pins (3.3).
- **`signum(x)`**: `IfElse(x = 0.0 OR isnan(x), x, IfElse(x < 0.0, -1.0, 1.0))`, in #1065's
  order: `x = 0.0` holds at both zeros, `x < 0.0` never at NaN. The naive `x > 0.0` first would
  answer `1.0` for NaN, which Spark's order puts above every number. The picks move bits, so a
  zero keeps its sign and a NaN its payload, as `Math.signum` returns its operand.
  *Corrected 30 October 2026 (5.1):* this form answers `1.0` for a null `x`, since an unknown
  condition takes the else arm and the inner else is a valid literal; the built form ends in `x`
  itself, so a null answers null.
- **Negation and `abs`**: `IntNeg`, the sign bit flipped or cleared, NaN included.

### 2.4 The interval products and quotients (after 343)

With `m` the interval's month or microsecond count and `x` the factor (a `FLOAT` widened by
343's exact `F2D`):

    p = IntArith(MUL, WRAP, toDouble(m), x)        or  IntArith(DIV, FAIL, toDouble(m), x)
    g = GuardedRange(p, lo, hi)                    every mode: Spark raises outside
    t = toIntegral(g)                              truncation, exact inside the guard
    f = IntArith(SUB, WRAP, g, toDouble(t))        exact: a double's fractional part is one
    r = t + IfElse(f >= 0.5, 1, IfElse(f <= -0.5, -1, 0))

`toDouble` and `toIntegral` are 343's `I2D` and `D2I` for a year-month interval and `L2D` and
`D2L` for a day-time one. The division's `FAIL` is unconditional here, since Spark raises on a
zero factor in every mode. The bounds are the doubles whose HALF_UP lands in range: for the int,
`(-2^31 - 0.5, 2^31 - 0.5)` exclusive, both ends exact doubles; for the long, `[-2^63, 2^63)`,
both ends integers, so no tie sits at either. NaN and the infinities fall outside, so every lane
Guava raises on condemns the batch, and the row engine raises its `ArithmeticException`. The tie,
`|f| = 0.5`, moves away from zero as Guava's `x + copySign(0.5, x)` does, and `f = 0` for every
`|p| >= 2^52`.
On a host where 343 builds `D2L` and `L2D` by the magic number below `2^51` (AVX2), a day-time
lane past that window, about 71 years, also declines; with AVX512DQ the form is whole.
`signum` over an interval is `IfElse` over the interval lane's compares with a double result,
built in the same step, since it needs the same crossing.

### 2.5 Compiler, reference, cost, census

- A family `FLOATING_ARITHMETIC` in `varka/VarkaFamily.java`, its arm in a new
  `VarkaFloatingCompiler` claiming by class and type; `floatingDecline` admits `Add`, `Subtract`,
  `Multiply`, `Divide`, `UnaryMinus`, `Abs`, `Signum`, `Sqrt`, `ToDegrees` and `ToRadians` by
  name, and the interval classes in 2.4's step. `Remainder` and `Pmod` keep declining, naming 346.
- `VarkaReference` evaluates the floating `IntArith`, `IntNeg`, `MathUnary` and the floating
  `GuardedRange` over bits with Java's operators, and the DIV modes as 2.2's table.
- `Analysis` and `Slots`: a `DIV` in `FAIL` takes the refusals of a checked node and its parked
  divisor; `NULL` marks the node as one that nulls valid inputs.
- The range analysis answers `UNKNOWN` for any node off the `INT` lane before its kind switch
  (`varka/VarkaRangeAnalysis.java:234`, item 0.3), so a node on a floating lane is `UNKNOWN`
  whatever the kind; and the family builds its nodes directly,
  never through `arithOver`, whose `cannotOverflow` would read a double's bits as an int range.
- Cost: `DIV` and `SQRT` at a floating lane are features of `VarkaEmitCostRegister`, fitted as
  every feature is; `VarkaEmitBudget` weighs them as a checked node until the fit.
  *Corrected 30 October 2026 (5.1, the review):* a weight is not a placeholder for the cost
  model. `VarkaEmitterBudgetSuite`'s register (VARKA-254) holds every node's weight equal to the
  lane calls it emits alone, and a checked integral node weighs 5 because it emits its op and the
  four of its sign test. The floating division emits its divide, and under `FAIL` and `NULL` the
  zero test's compare, so it weighs 1 and 2; `MathUnary` emits one call and weighs 1.
- The operator census gains the six missing cells per floating species (item 0.7); the warm-up
  blocks of 341's owed list gain the operators this row calls.

## 3. Tests and oracles

The oracle is Spark's own evaluation, through the reference evaluator and the differential
suites, compared bit for bit except a computed NaN, compared by NaN-ness (2.7's rule).

1. **The special cells**, a table test in each mode and at both kinds where the form exists,
   against Spark's `eval`, the reference and the kernel: 2.7's cells (`0.0 * -1.0` is `-0.0`,
   `-0.0 + 0.0` is `+0.0`, `-0.0 - 0.0` is `-0.0`, `sqrt(-0.0)` is `-0.0`, `inf - inf`, `0 * inf`
   and `inf / inf` NaN); `1.0 / -0.0`, `0.0 / 0.0` and `NaN / 0.0` null or raised, never an
   infinity or a NaN; `x / NaN` NaN; `-1.0 / inf` `-0.0`; `MAX * 2` infinity in ANSI too;
   `MIN_NORMAL / 2` subnormal and `MIN_VALUE / 2` zero (no flush to zero); `degrees(MAX)`
   infinity; `radians(MIN_VALUE)` zero; `signum` of both zeros, both infinities and three NaNs
   (bits kept); negation and `abs` of three NaNs (bits).
2. **No contraction**: `(x * y) + z` at `x = 1 + 2^-27`, `y = 1 - 2^-27`, `z = -1` is `0.0`, where
   an FMA gives `-2^-54`; and `dev/varka_emit.sh --asm` over it shows no `vfmadd` in the loop.
3. **The constants**: `Math.toDegrees(x)` and `Math.toRadians(x)` equal `x * k` bit for bit over
   the sampled regions of 3.5, so a JDK that changes either formula fails a test, not an answer.
4. **Exhaustion at 32 bits**: negation and `abs` over all 2^32 `FLOAT` patterns against Spark's
   `UnaryMinus` and `Abs`, in the gate; `+`, `-` and `*` over every `FLOAT` pattern against each
   of 16 operands - three NaNs (quiet, negative, a payload), both zeros, both infinities, the
   extremes, the smallest subnormal and the smallest normal in both signs, three normals -
   nightly.
5. **Sampled `DOUBLE` regions** for every form: each special value, the subnormal range and its
   edges, `MIN_NORMAL` and its neighbours, the overflow thresholds (`MAX / 2`, `sqrt(MAX)`,
   `degrees`' own), every widened `FLOAT` pattern for the unary forms, and a seeded draw of
   random bit patterns per exponent band; pairs over the special set's cross product and a draw
   per exponent difference, where the subtraction cancels.
6. **The division**: the four null patterns against zero, `-0.0`, NaN and normal divisors in the
   three modes; ANSI's raise through the declined batch with `DIVIDE_BY_ZERO`; `IF(y = 0, 0.0D, x
   / y)` and `CASE WHEN y <> 0 THEN x / y ELSE 0.0D END` fused under ANSI over a batch holding
   zeros, and their `NULL`-arm spellings once row 333 lands; a
   literal zero declined; a non-zero literal with no zero test (`dev/varka_emit.sh` op counts).
7. **The interval forms** (2.4's step): the rounding stated as an SMT claim in the floating-point
   theory, `sql/varka/proofs/fp_half_up.smt2`, that `t + bump` equals `roundIntermediate`'s
   HALF_UP for every double inside each guard, under Z3 and cvc5; and pinned cells: ties at
   `+-0.5`, `+-2.5` and `2^31 - 1.5`, the guard's ends and their neighbours, a NaN and an infinite
   factor, a `-0.0` divisor raising, `2^51` either side on AVX2.
8. **Conformance**: the census marking the forms fused un-cancels the two suites' null-handling
   `x + y` and overflow check (item 0.9); a floating "overflow" reaches infinity, which Spark
   answers in every mode, so each form fuses and no batch declines there.
9. **Coverage rows**, a family "floating arithmetic": each form over `x`, `x2` and over `f`, `f2`
   where it exists, a literal on either side, `try_divide`, `try_add` and the chain of the
   baseline, through the differential suite at every position; after 343, `i / j`, `f / g`,
   `sqrt(i)` and the four interval forms.
10. **The fuzzers**: the floating IR sequence (341's, built here if it has not landed) draws the
    new nodes; the Spark fuzzer's grammar gains them over its floating columns.
11. **Generated files** in `ADDING_AN_EXPRESSION.md`'s order (`coverage.json`, the docs tables,
    `width_audit.json`, `emitted_bytes.json`, `census.tsv`'s fused column), then the PR build and
    the dispatched option matrix.

## 4. Predictions and costs

The measurement reruns the baseline classes (5, step 1) with the fuse check lifted, on the
runners' EPYC 7763 at 256 and 128 bits, diffed against the committed files.

1. **No existing shape's bytes move**; `option_arms` digests move with the new shapes.
2. **The census**: the six new cells intrinsic at every width on the laptop and the EPYC; the
   column-divisor zero test at 128 bits is the double compare's fallback, recorded in
   `width_audit.json` as a refusal.
3. **The chain** at least four times faster than the row path at 256 bits, against 57.9 and 57.3
   ns a row, and at least twice at 128 bits: three loads, three ops and a store.
4. **A column division and `sqrt`** at least three times faster at 256 bits; at 128 bits the
   division within 20% either way, the fallback zero test weighing on it.
5. **Test time**: the two `FLOAT` exhaustions under 30 s together in the gate, at `VARKA-335.md`'s
   measured 7 s for a unary 2^32 sweep (the calibration table's row, ratio taken as 1); the
   nightly binary ones about 48 such sweeps, under 10 minutes; the coverage rows under 30 s, at
   `VARKA-336.md`'s ratio; each SMT claim under 60 s (no multiplication in it).

Cost: medium, as the row says. About two days for the same-lane forms after the baseline, and
two for the cast and interval forms after 343.

## 5. Order and files

1. **The baseline**, its own pull request on the unchanged code:
   `VarkaFloatingArithmeticBaselineBenchmark`, over `x / y`, `x / 3.0D`, `try_divide(x, y)`,
   `sqrt(abs(x))`, `-x`, `signum(x)`, `degrees(x)`, `ym * x` and `dt / x`, with and without nulls,
   `/` in LEGACY and ANSI; its fuse check per section, against each class's list of the sections
   a row has fused, which 377's class gains too, and lifted by a property for the measurement;
   its results files at both widths from the EPYC runners, committed before it merges.
2. **The census cells** (2.5), on the laptop and through the census workflow on both runners.
3. **The same-lane forms**: the IR and emitter (2.1), the division (2.2), the functions (2.3), the
   reference, the compiler family, tests 3.1 to 3.6 and 3.8 to 3.11.
4. **After 343**: the cast forms' rows, the interval forms and interval `signum` (2.4), test 3.7.
5. **The measurement** and its verdict.

| file | what |
|---|---|
| `varka/VarkaVectorIR.java` | `IntOp.DIV`, the floating rules of 2.1, `MathUnary` |
| `varka/VarkaVectorWalk.java`, `Slots.java`, `Analysis.java` | the floating arms, the zero test |
| `VarkaFloatingCompiler`, `varka/VarkaFamily.java` | the family and its arm |
| `VarkaNodeCompiler.java` | `floatingDecline`'s names |
| `VarkaIntervalCompiler.java` | the floating factor (step 4) |
| `varka/VarkaEmitBudget.java`, `VarkaEmitCostRegister.java` | the `DIV` and `SQRT` features |
| `sql/varka/proofs/fp_half_up.smt2` | 3.7's claim |
| tests | the cells, the sweeps, the division, the coverage rows, the fuzz grammar, the reference |
| benchmark | the baseline class, its results files at both widths from the EPYC runners |
| generated | `coverage.json`, `docs/sql-varka.md`, `width_audit.json`, `emitted_bytes.json` |
| generated | `operator_census.json`, `census.tsv` |

### 5.1 Step 3 as built (30 October 2026)

Where the code departs from 2.1 to 2.5, and what is still owed:

- **`signum` is ordered so that a null answers null.** 2.3's form, `IfElse(x = 0.0 OR isnan(x),
  x, IfElse(x < 0.0, -1.0, 1.0))`, answers `1.0` for a null `x`: an unknown condition takes the
  else arm, and the inner else is a valid literal. The built form is `IfElse(x < 0.0, -1.0,
  IfElse(x > 0.0 AND NOT (x = NaN), 1.0, x))`: its last arm is `x`, so a null, both zeros and
  every NaN answer themselves, bits kept, and `x > 0.0` is excluded at NaN, which Spark's order
  puts above every number.
- **The zero test parks nothing.** 2.5 has a `FAIL` division park its divisor; the emitter keeps
  it under the quotient instead (`dup_x1`, the divide, `swap`), and the compare consumes the
  copy. A `FAIL` division declines with a refusal of its own, `DIVIDE_BY_ZERO`, and the kernel
  status bit `STATUS_DIVIDE_BY_ZERO`, never behind `checkIntOverflow`.
- **`DIV WRAP` takes any divisor**, a column or a computed value as well as a non-zero literal,
  with the IEEE quotient on a zero divisor (an infinity or a NaN): the form rows 356 to 358's
  compositions need (`1 / sin(x)`, `log(b) / log(x)`), which divide by a computed value with no
  test. Only Spark's `Divide` over a computed divisor takes `NULL` or `FAIL`.
- **Cost**: `VarkaEmitBudget` weighs a floating division 1 under `WRAP` and 2 under `FAIL` and
  `NULL`, and `MathUnary` 1: the lane calls each emits, the rule `VarkaEmitterBudgetSuite`'s
  register holds every node to (2.5's correction; this bullet said 5, as a checked node, which the
  code never weighed). `VarkaEmitCostTable` has no price for the new features, so a group holding
  one falls back to the budget and the byte budget's measurement of the built class, as an
  unpriced feature does.
- **Tests**: `VarkaEmitterFloatingArithSuite` holds the IR at both lanes and widths to the
  reference, the zero divisor in each mode, the compiled forms against Spark's `eval` in LEGACY,
  ANSI and TRY over 3.1's cells and the special values' cross product (a NaN computed from two
  NaN operands by NaN-ness), the guarded divisions under ANSI, the literal zero's decline, the
  contraction case of 3.2, the two constants of 3.3, the negation and `abs` of every `FLOAT`
  pattern, and the binary `FLOAT` sweeps of 3.4 behind `-Dvarka.sweep=true`.
  `VarkaFloatingLaneSuite` runs the forms end to end over its fixtures in both modes, and ANSI's
  `DIVIDE_BY_ZERO` through the declined batch. The coverage table gains a family, "Floating
  arithmetic", of 26 rows.
- **The NaN rule reaches the shared harness, at the nodes Java leaves open.** Java fixes no NaN
  payload for an operation over two NaN operands of different bits, and HotSpot's answer depends on
  the tier and the operator: the x86 interpreter's `dadd` and `dmul` return the right operand's
  payload (`addsd xmm0, [rsp]`, with the right operand in `xmm0`) and its `dsub` and `ddiv` the
  left's (`cpu/x86/templateTable_x86.cpp`, `TemplateTable::dop2` in the local `jdk-25-ga`
  source), and C2 commutes a scalar add or multiply (0.6). In `VarkaDoubleConformanceSuite`,
  `NaN + (-NaN)` gave the first operand's payload in the kernel and Spark and the second's in the
  reference evaluator, which ran interpreted. So the reference keeps Java's operators, and
  `VarkaKernelChecks.sameAnswer` compares two NaN answers by NaN-ness only where the row's answer
  descends from such a node (`unfixedNaN`): a floating `IntArith` over two unlike NaNs, or a
  negation, square root or pick that passes its payload on. An operation over one NaN operand
  returns it, quieted, on every tier, and is compared bit for bit, as are the picks, negation and
  `abs` everywhere else. `VarkaExpressionHarness` holds a kernel to Spark's projection by the
  same rule, per output and on the Catalyst expression; it once excused every output of a row
  with two unlike NaN inputs, a wrong pick among them. `VarkaEmitterFloatingArithSuite` and
  `VarkaFloatingLaneSuite` hold their own comparisons to the same line: a row or column of two
  unlike NaN inputs alone.
- **A shared guarded node keeps every arm it sits under.** Under ANSI, `IF(x2 = 0.0D, 0.0D, x1 /
  x2)` and `CASE WHEN x2 <> 0.0D THEN x1 / x2 ELSE x1 END` each fuse over batches holding zero
  divisors, but the two in one projection declined every batch of `VarkaFloatingLaneSuite`'s pairs
  fixture. The cause: `Analysis.assignArmChain` kept one arm chain per node and demoted a node
  whose uses sat under two different chains to none, so the shared division's guard condemned
  every live zero divisor. The integral `FAIL` guards (the checked add and negation, row 481's)
  went through the same walk and met the same collapse. The walk now keeps a set of chains,
  ORed at the guard (`Analysis.armChains`, at most four, a chain whose steps include another's
  dropped), so the guard fires only where some arm reading the node is live. A node shared by
  outputs in two groups is guarded in each by the chains that group emits, and the conditions of
  a guard's chains are emitted before the body's first output, since a shared node is computed at
  its first use: in `VarkaGuardEdgeSuite`'s two-output shape the shared sum is reached inside the
  first output, before the second output's condition, whose word the guard reads. A single chain emits as before, and a column's stored-value guard keeps a single
  chain only, so its lane-wise min and max stay. A node inside the condition of an arm one of
  its chains runs through is computed while that condition is, before its word exists, so it is
  unqualified: the long-lane fuzzer's untaken-arm variant built that shape, a class that failed
  to verify, and `VarkaGuardEdgeSuite` pins it. `VarkaGuardEdgeSuite` holds a shared checked
  add to the verdict and the kernel, and `VarkaFloatingLaneSuite` runs the two outputs in one
  projection with no batch declined. In `emitted_bytes.json`, against master, no coverage row moved but
  this row's own new ones; at both widths 4 blocks of the int fuzz sequence, 18 of the long and 3
  of the mixed moved, and the option arms' digests over them, and those are exactly the blocks holding a shape with a guard whose
  qualification the walk changed, found by running the walk as it was beside the new one over
  the three sequences: no shape without a shared guarded node moved.
- **The review's fixes (30 October 2026).** `VarkaGuardSpecs` gives the zero divisor its own
  verdict (both operands valid and the divisor's bits `0` or the sign bit), so the coverage
  harness's domains, the composition fuzzer and the compiled check's guard edges run `FAIL`
  divisions instead of throwing, and the guard-edge search sets a column divisor to `-0.0`
  directly. The fallback accounting names `STATUS_DIVIDE_BY_ZERO` `divide-by-zero`, with its
  reason metric. The conformance base asserts for a floating type that each overflow form
  reaches an infinity, that Spark neither raises nor nulls there, and that no batch declines.
  `VarkaEmitterFloatingArithSuite` is a JUnit 5 suite, as `sql/varka/AGENTS.md` asks of a new
  catalyst suite with Java helpers.
- **Planted faults (row 504).** `VarkaFloatingFault` plants three wrong lowerings, each failed by
  the suite's checks: a checked division that never tests a `-0.0` divisor (the zero-divisor
  cells and the guard verdict), negation built as `0.0 - x`, `+0.0` where Spark answers `-0.0`
  (the bit-for-bit check against Spark, at both kinds), and `degrees` multiplying by the
  constant rounded to nine digits (the check against Spark).
- **The baselines' fuse lists (31 October 2026, on merging master).** Step 1's check refuses a
  baseline run in which an unlisted section fuses, so this step lists what it fuses, read from
  each section's plan on the Varka session (`VarkaSqlBenchmarkBase.fusedPlan`) rather than from
  the coverage table: in `VarkaFloatingArithmeticBaselineBenchmark` the sixteen `DOUBLE`
  sections, the division in both modes, `x / 3.0D`, `try_divide`, `sqrt(abs(x))`, `-x`,
  `signum` and `degrees` at both densities, the four interval sections staying the row path
  until step 4; in `VarkaFloatingBaselineBenchmark` the four arithmetic chains, beside the
  comparisons and choices already listed, the casts staying the row path until 343.
- **The cost model's fitted table, refitted (31 October 2026).** `VarkaEmitCostSuite` had not
  run on this step: its feature list built an integral `IntArith` `DIV`, which the IR refuses,
  and `MathUnary` was on neither the list nor the awaiting-refit set. With both fixed, the
  fitted `VarkaEmitCostTable` differed in 104 rows, by at most 0.57: the fit reads the
  emitted classes of the fuzz sequences, whose guarded blocks the arm-chain walk above moved.
  It is regenerated with `VARKA_COST_REGEN=true`; the register is unchanged, and
  `emitted_bytes.json` holds under the new table, so no grouping moved. `emit_cost_audit.json`
  is regenerated with it: its corpus counts two methods fewer in the 500 to 1,999 byte bin and two
  more in the 2,000 to 7,999 byte one, its percentages move by at most 0.9 points, and the
  methods under-predicted past the margin fall from 1,083 to 1,076 in its smallest bin.
- **The integration fixes (1 November 2026).** The Spark fuzzer draws only fused coverage rows,
  so it can now draw `x / x2`, and its fixture's `x2` is zero in three of ten picks: both
  engines raise `DIVIDE_BY_ZERO` under ANSI, and `sql/varka/fuzz/expected_errors.tsv` gains that
  entry for floating arithmetic. Its planted unlisted-error test raises an out-of-range
  `element_at` instead of `i / 0`. With that entry, most ANSI compositions that draw the row
  compare only the raised error; keeping the fuzzer's safe rows off a zero `x2`, as the IR
  fuzzer redraws a zero divisor, is later work. The square root is rebuilt directly in
  `VarkaWithChildrenSuite`, which no grammar reaches, and the proof renderer skips `DIV` over
  integral operands, which the IR refuses.
- **The option matrix's fixes (1 November 2026).** Under `heavyGroupOutputs=0` and
  `shareWholeNodes=false`, two outputs sharing a guarded day under arms of different conditions
  split into two groups over a materialized date prefix: the loading group computes neither the
  day nor its guard, and the producing group kept only its own arm's chain, so its dense body
  answered a row only the other arm takes with an unchecked day. `Analysis.armChainsIn` now
  leaves such a guard unqualified in the group that computes it, and a `VarkaGuardEdgeSuite` test
  pins the split at defaults. That declines more batches of the split shape; keeping the
  qualification by refusing to materialize such a prefix is the alternative, not taken. Under
  `guardUnderArm=false` the three tests whose subject is the division's arm qualification fail
  by design, and `sql/varka/matrix/skips.tsv` lists them.
- **Still owed by this step:** the IR fuzz grammar's floating sequence (341's, which the grammar
  lacks: it draws no floating tree) and the Spark fuzzer's floating productions (3.10); the
  `--asm` check of 3.2 (no hsdis on the run that built this); the census cells (step 2).
- **Later work, a measured option: a cheaper `signum`.** The built form is three compares in
  Spark's order and two blends, correct by construction, and stays. The candidate ORs `x`'s sign
  bit into the bits of `1.0` and blends that where `x` is neither zero nor NaN, keeping `x`
  elsewhere. `dev/varka_emit.sh` counts the built form at 15 calls on the emitter suite's
  scale, and the candidate is predicted at 10 (`results/VARKA-342-signum-ops.txt`, a prediction
  for its pair to score). It changes speed only, so it lands as an option arm with its pair
  benchmark, never as a replacement on a reading of the op counts.

### 5.2 Step 4 as built, its first piece (8 November 2026)

Step 4 was to follow 343's conversions, and only half of them have landed. `I2D`, `F2D` and the
narrow loads are on master (343a3, 343a5), `L2D` only where the emit level assures AVX512DQ
(343a4), and nothing converts a floating lane to an integral one: `VarkaKernelPlan.
conversionRefusal` refuses every such `ConvertLane` and names part 343b2, which also owns the
floating `GuardedRange` bounds (`VARKA-343.md` 4). 2.4's `t = toIntegral(g)` is that conversion
and `g` that guard, so the four interval products and quotients cannot be built in this row
without building 343's code here, which 8 rules out. By 8's rule of landing what is ready, the
step lands in two pieces, cut between the forms that need no conversion to an integral lane and
the ones that do:

- **The cast forms fuse as they are.** `dev/varka_emit.sh` and the coverage suite on master:
  `y1 / i`, `s1 / i`, `x / i`, `f / f2`, `try_divide(i, y1)`, `sqrt(i)`, `signum(i)`,
  `degrees(i)`, `sqrt(f)`, `signum(f)` and `radians(s1)` each compile to one fused output, the
  division and the functions over 343's exact conversions with no code of this step. The coverage
  table gains the family "Floating arithmetic over other types" with the eleven, the divisions
  under a domain that keeps the divisor non-zero, as `x / x2`'s. A `BIGINT` operand (`l / l2`,
  `sqrt(l)`) is not a row: the coverage suite compiles at the defaults' unknown emit level, where
  `L2D` declines. Each row is verified differentially (`differential` in `coverage.json`): the
  conversions are exhausted by 343a5's sweeps and the double forms by this row's step 3, but no
  test sweeps the two composed.
- **`signum` over an interval** is built here, since it needs no conversion to an integral lane.
  2.4's `IfElse` over the interval lane's compares answers `0.0` for a null count, as 2.3's form
  did (5.1): a null's conditions are unknown and take the last arm, a valid literal. The built
  form is `(double) ((m > 0) - (m < 0))`: each compare a `CondValue` on the count's own lane, null
  where the compare is unknown, their difference there, narrowed from the long lane to the int
  one for a day-time interval, then `I2D`. So a null count answers null, a zero `+0.0`, and no
  `L2D` is needed: the day-time form fuses on AVX2 hosts too, where 2.4 expected the crossing.
  Spark's generated code calls `Math.signum(float)` on the int or long count (the overload Java
  picks) and its interpreted path `Math.signum(double)`; both give `-1`, `+0.0` or `1`, and
  `VarkaFloatingLaneSuite` holds the kernel and both paths to the same bits at each type's ends,
  zero, one, minus one and nulls. Two coverage rows, `signum(ym)` and `signum(dt)`, and the
  census's `signum` fused over all three of its types, so done.
- **The rounding's proof is built ahead of its lowering** (3.7), as 343b1 built its proofs ahead
  of `VarkaConversionLowering`: `sql/varka/proofs/fp_half_up.smt2`, rendered by
  `VarkaProofFiles.fpHalfUp` from the guard's bounds, which `VarkaIntervalCompiler`, the class
  2.4's lowering is to land in (5's table), holds as public constants (`HALF_UP_INT_LO` and its
  three kin) ahead of the lowering. It states 2.4 against Guava 33.6.0's code read from its
  sources jar: for each of the int and the long, the inclusive guard holds exactly the doubles
  `roundToInt` or `roundToLong` answers (so a NaN, an infinity and every double past the type
  decline), inside it `t + bump` is Guava's answer, and for the long `f = 0` at every
  `|p| >= 2^52`; each guard one double wider at either end and the tie taken by `f > 0.5` are
  refuted. 2.4 holds as written: Guava tests the rounded `z`, and the plan's exclusive
  `(-2^31 - 0.5, 2^31 - 0.5)` and `[-2^63, 2^63)` on `p` are the same test. The pinned cells are
  the ties of 3.7 and row 103's products (PLAN.md's 30 October note,
  `results/VARKA-103-admission.txt` item 7), each against the answer Spark gave there. Stated
  whole, the in-guard claim ran past the 10 s per-check limit under both solvers, so it is checked
  per binade of `|p|`: 129 checks, 80 s under Z3 and 118 s under cvc5 on the laptop
  (`dev/varka_prove.sh --solver both`, one run), each check within the limit. Prediction 4.5
  ("each SMT claim under 60 s") holds per check; the file as a whole is past it.
- **The decline names its wait.** `floatingDecline` gives the four interval classes their own
  reason, the conversions to an integral lane (row 342 after part 343b2), in place of "floating
  arithmetic (row 342)".
- **The width audit under AVX2.** The 13 new shapes are in `width_audit.json`'s census, taken on
  the laptop at `UseAVX` 3. Rerun at the preferred width under `JDK_JAVA_OPTIONS=-XX:UseAVX=2`
  (9 November 2026), the audit's invariant held: every shape is emitted, and C2 refused no Vector
  API call in any of them beyond the 64-bit converts without AVX512DQ, which the audit admits by
  design.

**Where the proof runs, decided by the owner on 9 November 2026: the nightly only.** It runs in
the nightly CI workflow `varka-fuzz.yml` under both solvers; PR Builds render and drift-check it
only, `dev/varka_prove.sh`'s `NIGHTLY_ONLY` list keeping it out of `--lint`, so that the PR Build
stays short. `dev/varka_prove.sh --lint`, the linters' job's step, which its workflow comment
keeps "inside its minute", took 142 s on the laptop with the file and about 49 s without it:
`fp_half_up.smt2` took Z3 62 to 115 s over four runs, each of its 129 checks within the 10 s
limit, its slowest 3.7 s on an idle laptop core and one past the limit on a loaded one. Fixing
the sign or grouping binades made no check cheaper.

Correction, from the review of 9 November 2026: the 10 s limit holds Z3's checks, not cvc5's.
cvc5 took 137 to 208 s over the file with one or two other solver runs beside it; at a 4 s
per-check limit six of the `dt` HALF_UP checks answered unknown, at 6 s seven and at 8 s
one, so its slowest check is past 8 s on a shared laptop core, too near the default limit for a
runner. `dev/varka_prove.sh` therefore runs a `NIGHTLY_ONLY` file at 30 s a check and 600 s a
run where the environment sets no limit, which the nightly job's 30 minutes fit twice over.

Correction, from the review of 11 November 2026, to 5.2's "each check within the limit": it
held in that run, on an idle laptop, and not under load. Over every run recorded here and in the
pull request, the file took Z3 51 to 128 s and cvc5 88 to 208 s on the laptop, the spread being
the laptop's load. Z3's slowest check took 3.7 s on an idle core and one ran past 10 s on a
loaded one; cvc5's slowest ran past 8 s on a shared core. On a loaded laptop neither solver
fits every check within 10 s, which is why the file runs in the nightly at 30 s a check.

**Still owed by step 4**, after 343b2: `ym * x`, `ym / x`, `dt * x` and `dt / x` by 2.4's
construction over `D2I` and `D2L` and the floating guard, with 3.7's pinned cells through the
kernel, the division's `FAIL` unconditional; the `2^51` decline on AVX2 if 343b1's `D2L` is the
bounded magic there (`VARKA-343.md` 8 makes the whole-range arms the default, so it may not
arise); the lowering built over `VarkaIntervalCompiler`'s guard constants, which the proof
reads; the coverage rows of the four; and the baselines' fuse lists for `ym * x` and `dt / x`.

### 5.3 The FMA node, for row 346 (20 November 2026)

Row 346's plan (`VARKA-346.md` 2.6, 8) gives this row the FMA node its remainder and its `FLOAT`
output's round-to-odd need, the earliest wave owning a shared node (`VARKA-350.md` 8, question 4,
names 342 its owner), and flags that this plan had none. It is built ahead of step 4's second
piece, which still waits for 343b2, as its own part, `varka-342-floating-arith`, so that 346a can
stack on it.

- **The node.** `Fma(a, b, c)` in `VarkaVectorIR`, three operands of one floating lane (the
  constructor refuses an integral lane and a mix), null-intolerant: its validity is the three
  operands' AND, folded as a two-operand node's is (`Slots.planWordRef`, `Analysis.ownerOf`),
  its pure word the AND of theirs. The emitter pushes the operands in order and calls
  `a.fma(b, c)`, the call `VarkaOperatorCensusProbe` measures; the warm-up's double and float
  blocks make it. The reference evaluates `Math.fma` at `DOUBLE` and `Math.fma`'s float form at
  `FLOAT`, never the double form narrowed. Range analysis answers `UNKNOWN`, the budget weighs it
  1 (one lane call, the register's rule), the cost model lists it as awaiting the refit with the
  other floating nodes, and the SMT renderer refuses it as it refuses `MathUnary`.
- **Its NaN is open everywhere.** `Math.fma` run in Java answers `Double.NaN`, the canonical
  NaN, for any NaN (`Math.java:2393-2394` in the local `jdk-25-ga` source) and for `inf * 0`
  (`:2404`); the instruction answers by the hardware's own rule over its own operand order, an
  operand's NaN or the host's default NaN for `inf * 0`: HotSpot emits x86's `vfmadd231`, `c = a *
  b + c` (`cpu/x86/x86.ad:8965-8966`, `macroAssembler_x86.cpp:1665`). So which bits a NaN answer
  has depends on the tier even over operands that are not NaNs, and `VarkaSameAnswer` gives the node its own
  step, `Unfixed`, open wherever it answers a NaN. A non-NaN answer is compared bit for bit:
  IEEE's fused operation has one correctly rounded result.
- **No compiler arm.** Nothing in Spark is an FMA, so no coverage row, family or census cell
  moves; the node reaches a kernel only through a lowering that builds it (346a).
- **Tests.** `VarkaEmitterFmaSuite` (JUnit 5): the single rounding, `(1 + 2^-27)(1 - 2^-27) - 1`
  `-2^-54` through the node where the multiply and the add give `0.0`, and its float twin
  `(1 + 2^-13)(1 - 2^-13) - 1` `-2^-26` against `0.0`; the node at both lanes and two widths each
  against the reference over every ordered triple of seventeen operands (zeros, infinities, a
  NaN, the extremes, a subnormal, inexact products), every null pattern of the three columns and
  both validity fills, in four operand arrangements, one under a negation; the constructor's
  refusals and the rendering's round trip through `VarkaCanonicalIr`; and the NaN rule, an
  `inf * 0 + 1` row open for the FMA and closed for the multiply. `VarkaWithChildrenSuite`
  rebuilds it, `VarkaLaneTypeSuite` emits it at `DOUBLE` and refuses it at the integral lanes,
  `VarkaEmitterBudgetSuite` weighs it, and the registry lists (`VarkaIrFuzzSuite`,
  `VarkaEmitCostSuite`, `VarkaRangeAnalysisSuite`) name it.
- **Predictions**, to score when the tests run: no existing shape's bytes move
  (`emitted_bytes.json` unchanged, the node being reachable from no compiler arm and no fuzz
  sequence, 5.4's sequences being new ones); `VarkaEmitterFmaSuite` runs under 20 s; under `-XX:UseAVX=2` the width audit has
  nothing to say, since no audited shape holds the node.
- **What ran (20 November 2026, the laptop).** `build/sbt catalyst/Test/compile` passed on the
  first try. `VarkaEmitterFmaSuite` ran twice, one run past the brief's one targeted run, since
  the first failed on the harness: the second passed, 5 tests in 9.1 s in its own JVM, scoring the time
  prediction (under 20 s): held. Its first run in the shared JVM failed three tests on
  `VarkaSpeciesGuard`, the two widths of one lane in one JVM, so the class runs in a JVM of its
  own (`@VarkaSuite(ownJvm = true)`), as `VarkaEmitterFloatingArithSuite` does. The registry
  suites, the emitted bytes and the width audit are owed (the draft pull request lists them).

### 5.4 The floating arithmetic fuzz sequences (20 November 2026)

3.10's IR fuzz half, owed by step 3 (5.1, "Still owed"), on part `varka-342b-floating-fuzz`,
stacked on 5.3's. 341's floating sequences (`fuzz_float`, `fuzz_double`) draw the leaves, the
picks and the conditions only; their grammar, `VarkaIrGrammar.FloatingShapes`, gains this row's
nodes behind a flag, drawn from two seeds of their own, `FLOAT_ARITH_FUZZ_SEED` and
`DOUBLE_ARITH_FUZZ_SEED`, so that the committed sequences and their pinned bytes do not move: the
plain draw keeps its exact random calls. With the flag a value is one of row 342's nodes four
times in nine: `+`, `-` and `*` unchecked, the division unchecked or nulling a zero divisor,
negation and `abs`, the square root and 5.3's FMA, at both floating lanes (the square root and
the division at `FLOAT` too, which the IR and the reference take though the compiler builds them
at `DOUBLE`). `VarkaIrFuzzSuite` runs each sequence against the reference, 300 draws as the
floating sequences do, and a reach test holds each to every form over its first 2,000 draws.

- **The `FAIL` division is not drawn** (decision 5); the emitter suite holds the declined batch,
  as it does the guards' firing for the other corpora.
- **Not yet in the bytes oracle.** `VarkaEmittedBytesSuite` pins the floating sequences' first
  two thousand shapes; pinning these is a regeneration (`emitted_bytes.json`), owed with the
  pull request's other generated files.
- **The Spark fuzzer's floating productions** (3.10's other half): its grammar draws only fused
  coverage rows, so it draws the family's 26 rows and their 11 cast forms already, `x / x2`
  among them (5.1, the integration fixes); nothing of it is built here.
- **What ran (20 November 2026, the laptop).** `build/sbt catalyst/Test/compile`, then three runs
  of `VarkaIrFuzzSuite`, past the brief's one targeted run: two by accident ran the whole suite,
  the `-z` filter reaching the parent only, and the third ran the three
  new tests directly in one JVM (`-Dvarka.ownJvm=true` set in the catalyst project, `-z
  arithmetic`): each sequence answered 300 of 300 draws against the reference, in 3.2 s at
  `FLOAT` and 2.4 s at `DOUBLE`, and the reach test found every form over 2,000 draws a lane. The first whole-suite run hit the
  child JVM's nine-minute cap, a harness limit, not a failure (`testing-and-debugging.md` on
  `VarkaOwnJvm`); the second, given the property at the root project, was stopped once seen.
  In the first, the child's own report shows the registry tests passing with `Fma` listed: "the
  generator reaches every IR node type" and the long-lane one.

### 5.5 Step 2's code, the census cells (20 November 2026)

Step 2 (2.5, item 0.7) on part `varka-342c-census`, stacked on 5.4's: `VarkaOperatorCensusCatalog`
gains a family, "floating arithmetic", of twelve cells, `ADD`, `SUB`, `MUL`, `DIV`, `NEG` and
`ABS` at each floating lane, over the probe's binary loops (`bin.float`, `bin.double`) and its
double unary loop, and a new `un.float` loop in `VarkaOperatorCensusProbe` for the float unary
two. The laptop's census was regenerated the same day (`VARKA_CENSUS_REGEN=true build/sbt
'catalyst/testOnly *VarkaOperatorCensusSuite'`, 200 s, its 8 tests passing): the diff adds the
twelve cells at 128, 256 and 512 bits, all 36 intrinsic with no allocation, and moves nothing
else but the host's JDK build string (25.0.5.0.3 to 25.0.5.1). Prediction 4.2, laptop half:
held. The census workflow on the EPYC 7763 and the Neoverse N2 is owed, its half of 4.2 with it.

### Decisions taken while the owner was away (20 November 2026)

1. **The FMA node is built now, as its own part, ahead of the rest of step 4.** The brief's
   priority note puts row 346's 346a behind it, and `VARKA-346.md` 8 records the lead's
   settlement that the node is this row's; the interval forms still wait for 343b2
   (`VarkaKernelPlan.conversionRefusal` refuses every conversion to an integral lane).
2. **The node's NaN is open wherever it answers one**, a `Step` of its own in `VarkaSameAnswer`,
   rather than `Other`'s rule, which opens a node only where an operand's NaN is open and would
   hold `inf * 0 + 1` bit for bit across tiers that disagree (5.3). Listing the causes instead (a
   NaN operand, `inf * 0`, an infinite product meeting the opposite infinity) names the same rows,
   since those are the only ways the operation answers a NaN.
3. **A host without an FMA instruction is not refused.** There the Vector API computes each lane
   by `Math.fma`, exact and slow (a `BigDecimal` product per lane); x86 hosts with AVX2 have FMA
   in practice and aarch64 always does, and the census records `double.FMA` and `float.FMA`
   intrinsic at every width on the laptop and the EPYC runners. Whether 346a's lowering declines
   on such a host is 346a's decision, made with its own measurement.
4. **The node is in no fuzz grammar.** The grammars draw no floating tree; the floating sequence
   (3.10, still owed) draws it when it is built, on a seed of its own, so that no committed
   sequence moves. *Corrected the same day:* 5.4's sequences, on part `varka-342b-floating-fuzz`,
   draw it; no committed sequence draws it, which is what this decision protects.
5. **The `FAIL` division is not fuzzed** (5.4): a draw has no reference answer for the batch a
   live zero divisor declines, and the floating edge values draw zeros often enough that most
   draws holding one would decline.
6. **The square root and the division are drawn at `FLOAT` too** (5.4), though the compiler
   builds them at `DOUBLE` only, where the narrow grammar draws only what the compiler builds at
   its lane. The IR, the emitter and the reference take both at `FLOAT`, and a later row (the
   float forms of 345 and 356 to 358) may build them there; a fuzzed form the compiler never
   reaches costs draws, not correctness.
7. **The fuzz sequences are a stacked part although both parts fit the cap together** (750 and
   183 counted lines, and 33 for 5.5's part): the brief asks for the FMA node as its own small part, so that row 346a can
   stack on it without the fuzz change.

## 6. Out of this row

`%`, `pmod` and `try_mod` (346); `try_divide` and `try_multiply` over an interval, which are
`TryEval` (`TryEval.scala:141`, `:274`; no arm claims it); decimal division (out of milestone
8); the casts (343); a multiply by an exact reciprocal (2.2, not taken).

## 7. Declines that stay

A literal zero divisor; a batch with a live lane Spark raises on (a zero divisor under ANSI, an
interval result past its type or from a NaN or infinite factor, an interval divided by zero),
which the row engine answers; `try_divide` and `try_multiply` over an interval, each a
`TryEval`; a day-time interval form past `2^51` on AVX2 hosts; until 343, every form over a cast
to double.

## 8. Questions for the owner

None open. The order relative to 343 is settled by the house pattern of landing what is ready
while the rest waits, as rows 341, 347 and 27 land in steps: the same-lane forms - `+`, `-`, `*`
and `/` over two `DOUBLE` or two `FLOAT` operands (`DivisionTypeCoercion` leaves a `DOUBLE`
division uncast, `DivisionTypeCoercion.scala:30`), the zero-divisor guard with them, negation,
`abs`, and the functions over a `DOUBLE` column - land first and the row is **Done in part**; the
forms over a cast to double and the interval forms land after 343 by its conversions. Building
343's conversions here would make two rows own one piece of code, and holding the row would keep
the commonest floating shapes on the row engine for nothing.

The baseline comes first, as its own pull request (5.1): not a question, since the house rule
settles it, as it did for rows 377, 27 and 339.

## 9. Done when

Row 342's text holds: each form fuses, bit-identical to the row engine over 3.1 to 3.7 and the
fuzzer, a computed NaN compared by NaN-ness; a zero divisor null under LEGACY and TRY and raised
through the row engine under ANSI; the interval rounding proven by 3.7's claim under both solvers;
no existing shape's bytes move; the PR build and the option matrix pass; the measurement is
committed with its predictions scored.

## 10. Outcome

(Written when the work lands.)

*Added 28 November 2026 from branch `varka-342de-r` (`688414d4e10`), unchanged: the
record of the interval forms as built there (5.6, 5.7, decisions 8 to 10), not merged.
Decision 8 is overtaken: SPARK-61352 is on master since #1773 (A.1).*

### 5.6 Step 4's second piece, the interval forms (20 November 2026)

On part `varka-342d-interval-scaling`, cut from `varka-342c-census` (`c7b2d46faaa`) with
`varka-343-conversions` (343b2a, `3ad1738e41a`) merged in at `4a1b8091ff5`: the conversions from a
floating lane to an integral one and the floating guard, which 2.4 waits for.

- **The lowering** is `VarkaIntervalCompiler.scaledHalfUp(op, m, x, ...)`, one builder for the four
  forms, `MUL` or `DIV` over a count on the int or long lane: 2.4's construction, with `p` a
  `DIV` in `FAIL` for the quotients (a live zero divisor of either sign declines, in every mode),
  the guard `GuardedRange.floating` over `HALF_UP_INT_LO` to `HALF_UP_INT_HI` or the long's pair in
  `FAIL`, `t` a `ConvertLane` to the count's lane, `f = g - (double) t`, and the step added on the
  count's lane. `fp_half_up.smt2` states this form, so the proof is the lowering's.
- **Where 2.4 was wrong: the step's condition cannot be on the double lane in a choice over the
  count's values.** `IfElse` takes its condition and its arms on one lane, and `f` is a double.
  The two tests of `f` are crossed to the count's lane by `CrossLane` (343a3b's node, which the
  compiler's value choice uses for the same meeting), by the word from the double lane to the int
  one and by `VectorMask.cast` to the long one. The alternative, the step as a double added before
  one conversion of `t + step`, would convert twice, which on AVX2 is a second 38-call `D2L`.
- **The tests of `f` are IEEE compares** (`IEEE_LE` against `0.5` and `-0.5`): `f` is never NaN
  on a lane the guard admits, so Spark's order, which places NaN, buys nothing there.
- **The compiler.** `ym * x` and `ym * f` (and `x * ym`, which the analyzer builds as the same
  node) fuse: `floatingDecline` admits `MultiplyYMInterval`, and `VarkaIntervalCompiler.multiply`
  hands a floating factor to the builder. `dt * x` keeps declining on this part, naming 343b2b's
  conversion below level 3 (5.7 builds it). The two quotients keep declining, with their own
  reason (decision 8). Three coverage rows join "Floating arithmetic over other types": `ym * x`
  and `ym * f` over a domain that keeps the product inside the int, and `ym * 0.5D`, where every
  odd count is a tie. `VarkaFloatingArithmeticBaselineBenchmark`'s `ym * x` sections are `WHOLE`.
- **Tests.** `VarkaEmitterIntervalScaleSuite` (JUnit, own JVM) holds the builder's trees to
  Spark's own `eval` of the four expressions through the reference, over the cross product of
  edge counts and factors and 3.7's pinned cells at a count of one (the ties, `2^31 - 1.5`, each
  guard's ends and their outer neighbours, `2^52 - 0.5` (the largest double with a half
  fraction), row 103's long cells), and the kernels to
  the reference at three widths, seven lengths, three null patterns and both bodies; every cell
  Spark raises on declines both bodies among answered cells, and declines neither the masked body
  nor the answer when null. The quotients and the day-time forms run there at the IR, the day-time
  ones at level 3. The compiler suite pins the built shape and the reasons; `VarkaFloatingLaneSuite`
  runs `ym * x`, `ym * f`, `x * ym` and two literal forms end to end against the row engine, and
  four products Spark raises on (an overflow each way, NaN, an infinity) with the same error.
  Its two new tests are headed by the session lint's marker, as path tests, since the suite
  stood at the lint's limit of 40 answer checks.
- **What ran (20 November 2026, the laptop).** `build/sbt catalyst/Test/compile` passed (its
  first run died in the 11:57 CET out-of-memory kill of the laptop's session and was rerun under
  the shared lock), then one run of `VarkaEmitterIntervalScaleSuite`: 4 of 4 passed, the sbt run
  44 s in all, every one of the four forms answering Spark's `eval` at each cell and the kernel
  the reference at every width and body. So the plan held where it was doubted: the kernel plan
  admits the conversion's int value read by inner nodes of a double-driven kernel.
- **Predictions, to score when the owed runs come in:** `emitted_bytes.json` gains the three
  coverage rows' shapes and no committed shape moves (4.1); under `-XX:UseAVX=2` the width audit
  refuses nothing in the new shapes, `D2I`, `I2D` and the crossings being intrinsic on AVX2.

### 5.7 The day-time product (20 November 2026)

On part `varka-342e-daytime-scaling`, stacked on 5.6's (`b7945875bb0`) with `varka-343b2b-avx2`
(343b2b, `d37962551b3`) merged in at `ba13356becb`: `D2L` and `F2L` at every named emit level, by
one call at level 3 and by 343b2b's exact AVX2 forms below it.

- **`dt * x` and `dt * f` fuse at every named level.** `floatingDecline` admits
  `MultiplyDTInterval`, and `VarkaIntervalCompiler.dayTimeMultiply` hands a floating factor to
  5.6's builder over the long lane: `L2D` of the microseconds (rounded to nearest, as Java's
  `(double)` is), the product, the long guard `[-2^63, nextDown(2^63)]`, `D2L`, `f`, and the step
  crossed to the long lane by `VectorMask.cast`. The unknown level declines it in the kernel plan,
  as it declines every conversion between the long and double lanes, so there is no coverage row,
  as there is none for `CAST(x AS BIGINT)`; the Spark sessions run at the unknown level, so the
  end-to-end suite still plans no Varka node for it.
- **2.4's `2^51` decline does not arise at the defaults.** 343b2b's default AVX2 forms
  (`XOR_SPLIT`, and `SPLIT`) are whole-range; only the `MAGIC` option arm bounds `D2L` to
  `|x| <= 2^51`, and there a day-time product past it declines its batch, as 2.4 says. The
  emitter suite holds the forms at level 3 and under both whole-range AVX2 arms.
- **`dt / x` stays declined**, by decision 8.

8. **The two quotients by a floating factor stay declined** (5.6). `IntervalMathUtils.divide`
   raises `INTERVAL_DIVIDED_BY_ZERO` and the overflow with `origin.context`, which a declined
   batch's fallback, compiled on the executor, reads empty until the sync that brings SPARK-61352
   (`VARKA-414.md` 9 and 10). The owner kept `ym / -1` declined on 10 November 2026 for exactly
   this, so that a query whose error is right today does not lose its `== SQL ==` block; the
   floating quotients raise on a zero divisor and on an overflow, so the same rule holds them.
   Their lowering is built and tested at the IR, and fusing them after the sync is
   `floatingDecline`'s two classes and their two arms, nothing else. 5.2's "still owed" predates
   414's outcome, which is where the plan was wrong.
9. **The step is crossed to the count's lane rather than converted** (5.6): one conversion of the
   truncation instead of two, which is the cheaper form on AVX2 for the day-time forms; the
   arithmetic is 2.4's and the proof's.
10. **The day-time product is its own stacked part**, `varka-342e-daytime-scaling`, with
   `varka-343b2b-avx2` merged in, as the brief orders: on this part `D2L` is one call at level 3
   alone, so `dt * x` would fuse at level 3 and decline below it, and the forms land once, whole.
