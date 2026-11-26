# VARKA-688: The range of a computed operand for each batch

*Planned on 26 November 2026. This plan stands alone: a builder needs this file and the
repository's `AGENTS.md` files, nothing else. Not started: milestone 9 waits for the owner's
word (`m9/PLAN.md` decision 18). The code facts below were checked on master `325511d01a3`.*

## 1. What this row is

Row 688 of `sql/varka/plans/m9/PLAN.md`: "The range of a computed operand for each batch.
`VARKA-657.md` 3.3: interval arithmetic over the ranges of the batch's columns, with the
transfer functions of `VarkaRangeAnalysis` and one for each zone node. **Done when**
`hour(ts + INTERVAL '1' HOUR)` fuses under a zone with changes and declines no batch that its
form covers."

Before this row a zone node (`ToLocal`, later `ToInstant` and `ToInstantKept`) compiles only
over a plain column. The runner knows the least and the greatest value of a column in a batch,
and from them the offsets the batch needs. It does not know the range of a computed value, so
`hour(ts + INTERVAL '1' HOUR)` and `hour(greatest(ts, ts2))` decline by name at compile time.
After this row the runner works out the operand's range for each batch from the ranges of the
columns the operand reads, and such an expression runs in a kernel.

What it gives the rows after it:

- row 690 builds `CAST(d AS TIMESTAMP)` and `ts + INTERVAL DAY`, whose zone nodes read a
  computed local time: a date times the microseconds of a day (part d gives the range of an
  int column), and `ToLocal(ts)` plus whole days (part a gives a zone node's rule);
- rows 668 to 671 and 664 compose: a field, a cast or a zone function over any timestamp
  expression whose nodes have a rule fuses with no code of its own, and a row whose tree holds
  a node with no rule adds that rule by the recipe of 4.3;
- row 693 (the table of a local time) takes part e's read of a table over a computed operand;
- row 687 hands the cache's statistics to the per-column source of 4.6, so a computed operand
  over cached columns runs no pass either.

What must be merged before it starts:

- row 657's parts A to D (and so rows 682, 683, 656 and 678), as the row's line says;
- row 685's parts a to c, before part b of this row: `VarkaBatchRanges` is where this row
  puts the ranges, as `VARKA-685.md` 1 says of rows 687 and 688, and part b's last test
  reads the ladder. The row's line in `m9/PLAN.md` names row 657 alone; section 9, risk 1
  has what follows from that;
- row 686's parts b and c, before part e only.

The goals it serves (`m9/PLAN.md` 1.1), each with the item of 1.2 that it moves:

- G1, item 1 (at least three quarters of `TIMESTAMP_LTZ`'s pairs fused): only through rows
  668 to 671 and 690. This row fuses one form of one pair, `ts + dt` with a literal under a
  day, which the census counts as fused in part.
- G2, item 2 (over ten years of instants in no order the kernels of the fields, of the casts
  and of the sum with an interval decline no batch): part e, for a field over a computed
  timestamp; for the casts and the sum only through rows 690 and 693.
- G7, item 10 (every `TIMESTAMP_LTZ` section whose kernel reads the zone at least five times
  the row path): only through row 691 and the sections of rows 668 to 671. This row registers
  its own sections (section 7).
- G3, G4, G5, G6: not served. Every part is built at full strength (section 8).

## 2. What an agent must know

**The idea.** A `TIMESTAMP` is a long: microseconds since the epoch in UTC, an *instant*. Local
time is the instant plus the zone's offset, and the offset changes at a few instants (a
daylight-saving step, a law). A zoned kernel does not look a zone up for each row. Before each
call the runner finds the least and the greatest value of the zone node's operand in the
batch, asks the zone's data for the changes inside that range, and writes them into the
kernel's `long[] longArgs`, after the literals. The kernel adds the first offset to every row
and, for each change, a difference to the rows at or after it.

For an operand that is a column, one pass over the column gives the two values exactly. For a
computed operand there is no column to pass over: the value exists only inside the kernel. So
this row computes an interval that is certain to hold every live value of the operand, from
the intervals of the columns it reads, by one rule for each node of the operand's tree. For
`ts + INTERVAL '1' HOUR` over a batch whose `ts` lies in `[a, b]`, the operand lies in
`[a + 3600000000, b + 3600000000]`.

The interval may be wider than the values. Then the runner fills more changes than the batch
needs, or takes a dearer form, or declines the batch: slower, never wrong. The interval must
never be narrower: a change outside it is not filled, and the rows past that change get the
wrong offset with no fallback to catch it. So the row's whole risk is one property, called
**soundness** below: every live value of the operand is inside the interval.

**Terms.**

- *Zone node*: an IR record under the sealed interface `Zoned`: `ToLocal(instant, zone)` on
  master after row 682; row 690 adds `ToInstant` and `ToInstantKept`. *Operand*: the node's
  input value, `Zoned.operand()`.
- *Batch argument*: an entry of `longArgs` that the runner writes before each call. *Program*:
  the run of batch arguments of one zone node.
- *Form*: the value of the emit option `zoneForm`: `ONE` (no change in a batch), `STEPS_2`,
  `STEPS_8`, and after row 686 `TABLE` and `TABLE_SCALAR`. *Ladder*: the default since row
  685: the evaluator takes for each batch the cheapest form that covers it.
- *Live row*: a row at which a value is not null. A null row's storage holds any bits.
- *Lattice*: `VarkaValueRange`, the compiler's intervals: `Bounded(lo, hi)` or `UNKNOWN`. Each
  of its operations answers `UNKNOWN` where an end would leave a long.
- *Rule* (the code calls it a transfer function): how a node's interval follows from its
  operands' intervals.
- *The pass*: `LongRangeOps.leastAndGreatest` (row 683), one vector loop over a column.
- *Decline*: the runner gives the batch to Spark's row path and counts the reason.
  *Declines by name*: the compiler builds no kernel for the entry and records a reason text.
- *Kernel input slot*: the index of a column among a kernel's inputs. `ColumnRef.ordinal()`
  is a slot; the runner's `inputOrdinals[slot]` is the batch's column.

**The names this row reads.** "master" is on `325511d01a3`; the others come from the merged
plans of the rows named, and each sheet's B0 greps them.

| Name | From | Meaning |
|---|---|---|
| `VarkaRangeAnalysis.range`, `Literals`, `Shift` | master | the compile-time range analysis, its two literal tables as functions, a rule's constant |
| `VarkaValueRange.Bounded` and its `add`, `sub`, `mul`, `neg`, `divideBy`, `hull`, `shift` | master | the lattice's operations |
| `VarkaVectorIR.Zoned` (`zone()`, `operand()`), `ToLocal(instant, zone)` | 682 | the zone nodes |
| `VarkaRangeAnalysis.ZONE_SHIFT` | 682 | `Shift(-MAX_OFFSET_MICROS, MAX_OFFSET_MICROS)`: 18 hours each way |
| `VarkaBatchArgs.layout`, `Program(node, first, count)`, `operandColumn(node)`, `MAX_OFFSET_MICROS` | 682 | the programs of a kernel; the operand's slot, or -1 where it is no column |
| `VarkaKernelPlan.Kind.ZONE_OPERAND`, `ZONE_OPERAND_REASON` | 682 | the plan's refusal of a zone node over a computed value |
| `VarkaZoneData.changes(least, greatest)`, `toLocalArgs(...)` | 682 | the zone's data for a range |
| `VarkaKernelRunner.STATUS_BATCH_ARGS`, reason `batch-args`; `BatchProgram(node, input, zone, steps, first)` | 682 | the decline of a batch whose arguments cannot be filled; a program as the runner holds it |
| `LongRangeOps.leastAndGreatest(srcData, srcValidity, srcNullCount, length, out)` | 683 | the least live value to `out[0]`, the greatest to `out[1]`; false when no row is live |
| `VarkaBatchRanges` (`measure`, `live`, `least`, `greatest`, `NOT_COVERED`) | 685, part b | the range of each program's operand for the current batch, measured once a batch |
| `VarkaZoneLadder` (`RUNGS`, `emitted`, `widest`) | 685, part c | the ladder's forms and its last form on a host |
| `VarkaZoneCompiler.toLocal(whole, timeZoneId, instant, sink)`, `COMPUTED_OPERAND_REASON` | 657, part A | the compiler's zone arm and its decline of a computed operand |
| `VarkaZoneTable` (`INDEX_SLACK`, `indexMapInts`), `VarkaZoneLowering.emitToLocalIndex`, `emitToLocalTable` | 686, part b | the table form: its index map and its lowering |
| `VarkaBatchArgsSuite`, `VarkaBatchArgRunnerSuite`, `VarkaZoneCompilerSuite`, `VarkaTimestampZoneSuite`, `VarkaTimestampZoneQuerySuite`, `VarkaZoneTableSuite` | 682, 657, 686 | the suites this row extends |

**The names this row adds.**

| Name | Where | Meaning |
|---|---|---|
| `VarkaOperandRange` | catalyst main, `codegen/varka` | the range of one operand: compiled once from the operand's tree, evaluated for each batch with no allocation |
| `VarkaOperandRange.unsupported(operand)`, `supports(operand)` | the same | the name of the first node with no rule, or null; shape only, no literal value read |
| `VarkaOperandRange.compile(operand, literals)` | the same | the range program of an operand |
| `VarkaOperandRange.range(live, least, greatest, out)`, `State { LIVE, EMPTY, UNKNOWN }` | the same | the batch's interval from the columns' intervals |
| `VarkaOperandRange.columns()`, `column(k)`, `lane(k)` | the same | the kernel input slots the operand reads, and each one's lane |
| `IntRangeOps.leastAndGreatest(srcData, srcValidity, srcNullCount, length, out)` | catalyst main, part d | the pass over an int column, with `LongRangeOps`' contract |
| `VarkaZoneCompiler.unrangedReason(node)`, `ZONED_INTERVAL_REASON` | catalyst main, part c | the two declines by name of 4.11 |
| `VarkaKernelPlan.ZONE_TABLE_OPERAND_REASON` | catalyst main, parts b to e | the refusal of a table form over a computed operand, until part e |
| `VarkaOperandRangeSuite`, `VarkaIntRangePassSuite` | catalyst tests, Java, JUnit 5 | the rules and their soundness; the int pass |
| `VarkaZoneOperandBenchmark` | sql/core tests, Java | the measurement (section 7) |

## 3. The admission check

Paths: `V` is `sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka`,
`C` is its parent folder, `E` is `sql/core/src/main/java/org/apache/spark/sql/execution`, `TJ`
and `TS` are the `src/test/java` and `src/test/scala` twins of `V`. Every fact was read in
`/home/max/proj/varka-m9-read-w`, a worktree of master at `325511d01a3`, and each command runs
from the repository's root.

**What `VarkaRangeAnalysis` offers today.**

1. **One traversal, compile time only.** `VarkaRangeAnalysis.range(node, kind, policy,
   literals, columns)` is a recursive switch over the sealed IR with no `default`. Its callers
   are the compilers; nothing in `sql/core` reads it.
   `grep -c 'public static Range range(VarkaVectorIR node, Kind kind, GuardPolicy policy,'
   $V/VarkaRangeAnalysis.java` prints 3 (its overloads);
   `grep -rl 'VarkaRangeAnalysis\|VarkaValueRange' sql/core/src/main | wc -l` prints 0.
2. **It reads no batch value.** A long column is `UNKNOWN` (the `ColumnRef` arm: `if (kind !=
   Kind.DAY) { yield VarkaValueRange.UNKNOWN; }`), and under `Kind.DAY` a date column is the
   constant `CONTRACT`, the years 0001 to 9999. `greatest`, `least` and `IF` are `UNKNOWN`
   under `Kind.INT` (the private `hull`). A `BitOp` is `UNKNOWN`.
   `grep -n -A6 'case ColumnRef c -> {' $V/VarkaRangeAnalysis.java` and
   `grep -n -A4 'private static Range hull' $V/VarkaRangeAnalysis.java` print both tests.
3. **It allocates, boxes and takes lambdas.** Every answer but `UNKNOWN` is a new `Bounded`
   record, and the literal tables cross in as `IntUnaryOperator` and
   `IntFunction<OptionalLong>`. `grep -c 'new Bounded(' $V/VarkaValueRange.java` prints 9;
   `grep -c 'public record Literals(IntUnaryOperator ints, IntFunction<OptionalLong> longs)'
   $V/VarkaRangeAnalysis.java` prints 1. The house rule forbids all three on a path that runs
   for each batch (section 10). So the analysis cannot run in the evaluator as it is.
4. **Its lattice saturates and never wraps.** `Bounded.shift`, `sub`, `mul` and `neg` use
   `Math.addExact` and its kin and answer `UNKNOWN` on `ArithmeticException`.
   `grep -c 'catch (ArithmeticException overflow)' $V/VarkaValueRange.java` prints 5.
5. **What proves it.** `sql/varka/proofs/range_analysis.smt2` (233 checks) proves the
   arithmetic arms over two general intervals. Its long-lane section says that with every
   interval inside a long the lane's value is the exact value and lies in the interval, for
   `ADD`, `SUB`, `MUL` and `IntNeg`, and that past a long the sum wraps out of it.
   `grep -c 'check-sat' sql/varka/proofs/range_analysis.smt2` prints 233;
   `grep -c '^(echo "Long' sql/varka/proofs/range_analysis.smt2` prints 13. For the int lane
   the file proves a weaker form: the interval holds a value congruent to the lane's modulo
   2^32, and "the compiler reads an interval only when it lies within int32" (its header).
   `VarkaRangeAnalysisSuite` (22 tests) holds each arm's answer; `VarkaRangeRulesSuite` (9
   tests) sweeps the calendar rules. `range_calendar.smt2` proves those rules.
6. **The lattice's rule for a division is the true quotient's,** and a long-lane `ConstDivide`
   is exact only for a dividend whose magnitude is under its `dividendBound`, which "the
   caller owns". `grep -c "The exclusive bound on a 64-bit dividend's magnitude"
   $V/VarkaVectorIR.java` prints 1.

**The shapes the compiler builds.**

7. **A day-time interval added to a `TIMESTAMP_NTZ`** is `new IntArith(IntOp.ADD,
   Overflow.FAIL, t.get(), dt)` (`VarkaTimeCompiler.ntzPlusInterval`); the arm is claimed for
   `TIMESTAMP_NTZ` alone. `grep -c 'case TimestampAddInterval a when
   VarkaTypes.isNtz(a.start().dataType())' $C/VarkaTimeCompiler.java` prints 1.
8. **Spark adds a day-time interval to a `TIMESTAMP` in two steps:**
   `DateTimeUtils.timestampAddDayTime` takes `days = dayTime / MICROS_PER_DAY`, then
   `atZone(zoneId).plusDays(days).plus(microseconds, ChronoUnit.MICROS)`. With `days == 0`
   the sum is a sum on the instant in every zone (`ZonedDateTime.plus` of a time-based unit,
   read in JDK 25's source), and `SparkDateTimeUtils.instantToMicros` raises
   `ArithmeticException` exactly where the result leaves a long (`Math.multiplyExact`,
   `Math.addExact`). `grep -c 'val days = dayTime / MICROS_PER_DAY'
   sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/util/DateTimeUtils.scala` prints 1.
9. **The floor division by a day** (`VarkaNodeCompiler.floorDivLong`) is three node kinds: an
   arithmetic shift right by a literal count (`new BitOp(BitOpKind.SHR, x,
   sink.longSlot(k))`), a sign mask (`BitOp AND` of a shift by 63 and a literal), and a
   `ConstDivide` of their wrapping sum by the divisor's odd part. `CAST(ntz AS DATE)` is its
   `NarrowLane`. `grep -c 'return new ConstDivide(new IntArith(IntOp.ADD, Overflow.WRAP, y,
   down), odd, bound);' $C/VarkaNodeCompiler.java` prints 1.
10. **The midnight of a date** is `IntArith(MUL, WRAP, GuardedRange(WidenLane(day)), literal)`
    (`VarkaNodeCompiler.midnightOf` over `ntzMidnightDay`).
    `grep -n -A2 'static VarkaVectorIR ntzMidnightDay' $C/VarkaNodeCompiler.java` prints it.
11. **A hull node skips a null operand.** The reference evaluator's `pick` answers the other
    operand where one is null (`VarkaReference.longValue`, arms `Greatest` and `Least`), and
    an `IfElse` takes its else branch where the condition is unknown. So the interval of such
    a node is the hull of its value operands, and an operand with no live row adds nothing.

**The runner and the reference.**

12. **No pass over an int column exists.** `IntRangeOps` has one public method, `allWithin`,
    which tests an interval fixed at compile time.
    `grep -c 'public static' $V/IntRangeOps.java` prints 1.
13. **A kernel input can be derived,** computed by the runner into scratch (a weekday, a
    truncation level): `VarkaKernelRunner.fill` reads `derived[i]`. Such an input is no column
    of the batch. `grep -c 'VarkaDerivedKind kind = derived\[i\];'
    $E/varka/VarkaKernelRunner.java` prints 1.
14. **The compile result holds both literal tables as values:** `List<Integer> literals` and
    `List<Long> longLiterals` (`CompiledVarkaProjection`), which `VarkaEvaluatorBase.newRunner`
    copies into the runner's arrays. A range program built there reads the same values the
    kernel receives.
15. **The reference answers a row of both lanes:** `VarkaReference.mixedValue(node, row,
    isNull, intLits, longLits)` returns an `OptionalLong`, empty for a null row and for a lane
    that a checked operation fails. It has no checked long multiply (`longArith` throws on
    `MUL` under `FAIL` or `NULL`).
    `grep -c 'public static OptionalLong mixedValue(VarkaVectorIR node, long\[\] row,
    boolean\[\] isNull,' $TJ/VarkaReference.java` prints 1.
16. **No name of this row exists.** `ls $V/VarkaOperandRange.java` fails;
    `git branch --list 'varka-688*'` prints nothing.

**Not checked.**

- The code of rows 682, 683, 685, 686, 657, 684, 655 and 654 is not built. Their names are
  from `VARKA-682.md`, `VARKA-683.md`, `VARKA-685.md`, `VARKA-686.md`, `VARKA-657-PARTS.md`
  and `VARKA-684.md`. Rows 687 and 690 have no plan: what this row leaves for them is in
  4.3, 4.6 and 4.9.
- No JVM was run. The IR of `ts + INTERVAL '1' HOUR` over a `TIMESTAMP` was not printed: the
  emit tool of the base has no timestamp column. Fact 8 was read in source and not run; part
  c's suite holds it against the row engine.
- No cost was measured: what a call of the range program costs, what the int pass costs, and
  whether C2 lowers `IntVector.min`, `max` and the int masks of part d at each width. Section
  7 registers each as a prediction with its measurement.
- The cost of the table's indices beside the gather in a kernel. The probe measured that form
  in its own loop (`sql/varka/spikes/zone-offset/results/tables.txt`).

## 4. The design

### 4.1 Why a class of its own, and what it shares with `VarkaRangeAnalysis`

The row's text asks for the transfer functions of `VarkaRangeAnalysis`. The analysis itself
cannot run for each batch: it allocates an object for every node, it boxes its literals, and
its column and hull arms answer `UNKNOWN` (facts 1 to 3). Changing those arms would change
which shapes the compiler admits today.

So the rules get a second form, `VarkaOperandRange`, built for one operand once a task and
evaluated for each batch over primitive arrays. It shares with the analysis what can be
shared without an object:

- the constants: `VarkaRangeAnalysis.ZONE_SHIFT` for a zone node;
- the arithmetic: each rule is the lattice's operation of the same name, and
  `VarkaOperandRangeSuite` holds every rule equal to `VarkaValueRange`'s operation over a
  table of intervals that includes the ends of a long (section 6). So the rules inherit what
  `range_analysis.smt2` proves of the lattice (fact 5);
- the saturation: where the lattice answers `UNKNOWN`, the batch form answers `UNKNOWN`.

It differs in four places, each on purpose:

| Where | The analysis | The batch form | Why |
|---|---|---|---|
| a long or int column | `UNKNOWN`, or the constant `CONTRACT` | the column's live range in this batch | the pass gives it |
| `greatest`, `least`, `IF` | `UNKNOWN` under `Kind.INT` | the hull | fact 11; the analysis's answer is a registered debt of its own |
| a `GuardedRange` | the guard's bounds | the child's interval | the child's interval holds the value whatever the guard does; no answer rests on a guard |
| an interval past the int lane | kept, and read only where it fits | `UNKNOWN` | a lane past int32 has wrapped (fact 5), and a later node could bring the interval back inside |

### 4.2 `VarkaOperandRange`: the program

A new final class, `V/VarkaOperandRange.java`:

    public enum State { LIVE, EMPTY, UNKNOWN }
    public static final int MAX_OPS = 256;
    public static @Nullable String unsupported(VarkaVectorIR operand)
    public static boolean supports(VarkaVectorIR operand)        // unsupported(operand) == null
    public static VarkaOperandRange compile(VarkaVectorIR operand,
        VarkaRangeAnalysis.Literals literals)
    public int columns()
    public int column(int k)                                     // a kernel input slot
    public VarkaVectorIR.LaneType lane(int k)                    // LONG, or INT from part d
    public State range(boolean[] live, long[] least, long[] greatest, long[] out)

- **The program** is the operand's tree in postfix order: `Op[] ops`, `int[] arg` (one a op)
  and `long[] consts`. `Op` is a private enum: `COLUMN`, `CONST`, `NONE`, `ADD`, `SUB`,
  `MUL`, `NEG`, `DIV`, `SHR`, `SIGN_AND`, `HULL`, `SHIFT`, `FIT_INT`. The evaluation runs the
  ops over two stacks, `long[] lo` and `long[] hi`, sized at compile time to the program's
  greatest depth. The instance owns the stacks, so it belongs to one evaluator, which is one
  task's: it is not shared between threads.
- **One walk serves both questions.** A private recursive method holds the one switch over the
  sealed IR, with no `default`. With a builder it appends ops; with none it only answers
  whether every node has a rule. `unsupported` is the walk with no builder: it reads no
  literal value, so the compiler on the driver and on the executor, and the plan, agree.
- **A shared subtree is walked once for each path to it.** A walk that passes `MAX_OPS` ops
  answers `more than 256 range operations`, as an unsupported node does.
- **`compile`** throws `IllegalArgumentException` for an unsupported operand and for a literal
  slot whose value the tables do not hold. It runs once a task, where `newRunner` builds the
  ranges: records, lambdas and lists are welcome in it.
- **`range`** reads `live[s]`, `least[s]` and `greatest[s]` for each kernel input slot `s`
  that the program names, and writes `out[0]` and `out[1]` when it answers `LIVE`. It holds no
  allocation, lambda, boxing or exception: the overflow tests are bit tests, never
  `Math.addExact`, whose exception allocates.

**The three answers.**

- `LIVE`: every live value of the operand in this batch is in `[out[0], out[1]]`.
- `EMPTY`: the operand has no live row in this batch. The stacks hold it as `lo =
  Long.MAX_VALUE`, `hi = Long.MIN_VALUE`, the identity of a hull.
- `UNKNOWN`: an interval left its lane on the way, or a rule could not answer. One boolean
  field, set by any op and cleared at the start of `range`. It absorbs everything, as the
  lattice's `UNKNOWN` does: a program that set it answers `UNKNOWN`, even where a later
  operand has no live row. That costs a decline of a batch whose rows are all null there,
  never an answer.

### 4.3 The rules

Each arm of the walk, with the ops it appends. "Strict" means: if either operand is `EMPTY`
the result is `EMPTY`, before any arithmetic. A node on a lane other than `LONG` or `INT` has
no rule.

| Node | Ops | The rule | Shares with |
|---|---|---|---|
| `ColumnRef`, `LONG` (and `INT` from part d) | `COLUMN s` | `[least[s], greatest[s]]` if `live[s]`, else `EMPTY` | the pass |
| `LiteralSlot`, `LONG` or `INT` | `CONST` | the point | the analysis's long literal arm |
| `NullValue` | `NONE` | `EMPTY` | - |
| `WidenLane` from `INT` to `LONG` | the child's | the child's interval | the analysis's arm |
| `NarrowLane` to `INT` | the child's, `FIT_INT` | the child's interval, `UNKNOWN` where it leaves int32 | 4.1 |
| `GuardedRange`, either mode | the child's | the child's interval | 4.1 |
| `IntArith` `ADD`, `SUB`, `MUL`, any overflow mode | both operands', the op, and `FIT_INT` on the int lane | strict; `[a + c, b + d]`, `[a - d, b - c]`, the least and greatest of the four corner products; `UNKNOWN` where an end leaves a long | `Bounded.add`, `sub`, `mul` |
| `IntNeg` with `abs` false | the child's, `NEG`, and `FIT_INT` on the int lane | `[-b, -a]`; `UNKNOWN` where `a` is `Long.MIN_VALUE` | `Bounded.neg` |
| `ConstDivide`, `LONG` | the child's, `DIV` | `UNKNOWN` unless `-dividendBound < a` and `b < dividendBound`; else the ends divided, truncating, in order | `Bounded.divideBy`; fact 6 |
| `BitOp SHR`, `LONG`, the count a `LiteralSlot` | the left's, `SHR` | `[a >> k, b >> k]` with `k` the literal's low six bits | new: the shift keeps order |
| `BitOp AND`, `LONG`, whose left is a `BitOp SHR` | the left's, the right's, `SIGN_AND` | strict; with the left in `[x, y]` and the right in `[c, d]`: `[0, 0]` if `x == y == 0`; `[c, d]` if `x == y == -1`; `[min(0, c), max(0, d)]` if `-1 <= x` and `y <= 0`; else `UNKNOWN` | new: `-1 & v` is `v`, `0 & v` is 0 |
| `Greatest`, `Least` | both operands', `HULL` | `[min(a, c), max(b, d)]`; `EMPTY` only if both are | fact 11; `Bounded.hull` |
| `IfElse` | the two branches', `HULL` | the same; the condition is not read | the same |
| `ToLocal` | the operand's, `SHIFT` | strict; `[a - M, b + M]`, `M = MAX_OFFSET_MICROS`; `UNKNOWN` where an end leaves a long | `ZONE_SHIFT` |
| `ToInstant`, `ToInstantKept` (row 690) | the local operand's, `SHIFT` | the same; the kept offset is not read | `ZONE_SHIFT` |
| every other record | none | no rule: its name is `unsupported`'s answer | - |

- **The overflow tests.** For a sum `r = a + b`: overflow iff `((a ^ r) & (b ^ r)) < 0`. For a
  difference `r = a - b`: iff `((a ^ b) & (a ^ r)) < 0`. For a product: `Math.multiplyHigh(a,
  b) != ((a * b) >> 63)`. `FIT_INT` sets `UNKNOWN` where a non-empty interval has `lo <
  Integer.MIN_VALUE` or `hi > Integer.MAX_VALUE`.
- **Why 18 hours serves every zone node.** An offset is within 18 hours
  (`java.time.ZoneOffset`). `ToLocal` is the instant plus an offset. Each of the three rules
  of a local time to an instant answers the local time less some offset of the zone: the
  earlier one, the kept one, or, for a local time in a gap, the change's instant, which is
  the local time less an offset between the two (`VARKA-657.md` 2.4).
- **The names `unsupported` answers:** the record's simple name, a slash and the node's lane:
  `BitOp/LONG`, `Year/INT`, `ColumnRef/INT` (until part d), `IntArith/DOUBLE`.
- **Which records have no rule in this row:** `DecimalColumn`, `DecimalWord`, `GuardedDay`,
  `ConvertLane`, `ReinterpretLane`, `WidenDecimal`, `InterleaveDecimal`, `AddDays`, `SubDays`,
  `DateDiff`, `BoundedDivide`, `BitCount`, `MathUnary`, `Fma`, `RoundToIntegral`,
  `CanonicalFloat`, `CanonicalBits`, `HashStep`, `CondValue`, `BoolOutput`, `DayOfWeek`,
  `WeekDay`, `DayOfWeekIso`, `NextDay`, `ThursdayOf`, `AddMonths`, `MakeDate`, every `Chrono`
  and every `Cond`; and, of the records above, an `IntNeg` with `abs`, a `BitOp` of any other
  kind or shape, an `IntArith` `DIV`, a `ConstDivide` on the int lane, a widening or a
  narrowing between other lanes, and a column of a narrow or floating lane.
- **The recipe for a later row's node.** A row whose tree puts a node with no rule under a
  zone node adds: the arm in the walk, with a new `Op` if the lattice has no operation for it;
  the node in the property test's grammar (section 6); a planted fault that narrows the rule
  by one and fails that test; and the node's line in the table above. A calendar node's rule
  is its `Shift` of `VarkaRangeAnalysis`, over a day.

### 4.4 Soundness, stated

For every batch and every row at which the operand is live: `range` answers `LIVE` and the
operand's lane value at that row is in `[out[0], out[1]]`, or `range` answers `UNKNOWN`. It
answers `EMPTY` only if the operand is live at no row.

It rests on three premises, each held by a named test (section 6):

1. the inputs: `live[s]` is false only if column `s` has no live row, and every live value of
   the column is in `[least[s], greatest[s]]`: the pass's contract (row 683; part d);
2. each rule: if every operand's live values are inside its interval and no interval left its
   lane, the node's live values are inside the rule's answer;
3. no wrap goes unseen: an interval that leaves its lane sets `UNKNOWN`, so a `LIVE` answer
   was reached by lanes that never wrapped. This holds for the three overflow modes alike: a
   lane that `FAIL` or `NULL` stops is not live, and a lane that `WRAP` would wrap has an
   interval that left the lane.

### 4.5 The operand shapes milestone 9 needs

Read from `m9/PLAN.md` section 3 and `VARKA-657.md` 2.4 and 3.7. "Rule" says whether the
operand's nodes have rules after this row.

| Where | The zone node's operand | Nodes | Rule |
|---|---|---|---|
| row 688's done-when, `hour(ts + INTERVAL '1' HOUR)` | `ts + literal` | `IntArith ADD`, column, literal | yes, exact |
| a field or a cast over `greatest`, `least`, `coalesce`, `IF`, `CASE` of timestamps (row 656's entries) | a hull of columns | `Greatest`, `Least`, `IfElse` | yes |
| row 690, `CAST(d AS TIMESTAMP)` | the date's midnight (fact 10) | `IntArith MUL`, `GuardedRange`, `WidenLane`, an int column | yes, with part d |
| row 690, `ts + INTERVAL DAY` | `ToLocal(ts)` plus whole days | `IntArith ADD`, `ToLocal`, literal | yes: the column's range, 18 hours wider each way, moved by the days |
| rows 669 and 690, `CAST(CAST(ts AS DATE) AS TIMESTAMP)` | the local day's midnight | the nodes of fact 9, `NarrowLane`, `WidenLane`, `ToLocal` | yes |
| rows 669 and 670, a field of `CAST(ntz AS TIMESTAMP)`, `convert_timezone` | `ToInstant` of a column | a zone node over a zone node | yes, once row 690's records have their arm |
| row 667, a field of `timestamp_seconds(l)` and its kin | a column times a literal | `IntArith MUL` | yes, where the row builds it so |
| row 670, `date_trunc` to a day or coarser | a truncated local day's midnight | fact 9's nodes; above a day `TruncDate`, a `Chrono` | to a day yes; above it row 670 adds `TruncDate`'s rule |
| row 671, `ts + INTERVAL MONTH`, `timestampadd` by months | `AddMonths` of the local day, plus the local time of day | `AddMonths`; the remainder `t - floorDiv(t, D) * D` | no: row 671 adds `AddMonths`' rule and a rule for the remainder, whose interval by these rules is as wide as the batch's span |
| row 670, `make_timestamp` | a local time made of six int columns | `MakeDate` and products | no: row 670's |

### 4.6 The runner: one range a column, one evaluation a program

`VarkaBatchRanges` (row 685) measures each program's column and keeps a least and a greatest
value a program. This row splits it in two steps, in the same class:

1. **A column's range, once a batch.** The class holds, for each kernel input slot that some
   program reads, `inLive[s]`, `inLeast[s]` and `inGreatest[s]`. `measure` fills them with one
   pass a slot: `LongRangeOps.leastAndGreatest` for a long column, and from part d
   `IntRangeOps.leastAndGreatest` for an int column. Two programs over one column share its
   pass. This is the step row 687 replaces with the cache's statistics.
2. **A program's range.** For each program, `operand[p].range(inLive, inLeast, inGreatest,
   pair)`. `EMPTY` makes the program not live, and the fill writes the neutral arguments, as
   for a column with no live row. `UNKNOWN` makes `measure` return `NOT_COVERED`, and the
   evaluator declines the batch with `STATUS_BATCH_ARGS`. `LIVE` gives `least(p)` and
   `greatest(p)`, to which row 685's two tests apply unchanged: within 18 hours of an end of
   a long is `NOT_COVERED`, and the need is the greatest `zone[p].changes(least, greatest)`.

A column operand is the program of one op, `COLUMN s`, and takes the same path: `sql/core`
has no branch on "column or computed". The constructor becomes `VarkaBatchRanges(
VarkaOperandRange[] operand, VarkaZoneData[] zone, int inputs)`; `measure`, `live`, `least`,
`greatest`, `programs` and `NOT_COVERED` keep their signatures and meanings. The evaluator,
the fill, the ladder's choice, the warm-up and the reproduction read them as before.

`VarkaEvaluatorBase.newRunner` builds each program's `VarkaOperandRange` where row 685 took
`VarkaBatchArgs.operandColumn`, with the literal tables of the compiled projection (fact 14).
An operand that reads a derived input (fact 13) makes it throw `IllegalStateException`, which
lands in the emission-failure fallback: no node with a rule takes such an input today.

### 4.7 The compiler

- `VarkaZoneCompiler.toLocal` (row 657) declines where `!(instant instanceof ColumnRef)`. Part
  c makes the test `VarkaOperandRange.unsupported(instant) != null`, and the reason names the
  node (4.11). The fold of a fixed zone, which comes first, is unchanged.
- `VarkaKernelPlan.of` (row 682) refuses a zone node where `operandColumn(node) < 0`. Part b
  makes the test `VarkaOperandRange.unsupported(node.operand()) != null`. The compiler and
  the plan ask one method, so a tree the compiler admits is a tree the plan emits.
- **The row's own entry.** `ts + dt` over a `TIMESTAMP` is `TimestampAddInterval`, a
  zone-aware expression whose whole days are calendar days in the session zone: row 671's.
  For a literal interval `v` with `v / MICROS_PER_DAY == 0` no day is added, and the sum is a
  checked sum on the instant in every zone (fact 8). `VarkaZoneCompiler.arm` claims
  `TimestampAddInterval` over a `TimestampType` start and a `DayTimeIntervalType` interval,
  and lowers that one form as the `TIMESTAMP_NTZ` arm lowers its sum (fact 7): `new
  IntArith(IntOp.ADD, Overflow.FAIL, instant, sink.longSlot(v))`. A lane that overflows
  declines the batch, and the row engine raises Spark's error. Every other form declines
  with `ZONED_INTERVAL_REASON` until row 671. `ts - INTERVAL '1' HOUR` reaches the arm as a
  sum with a negated literal: the optimizer folds the negation.

### 4.8 The range of an int column (part d)

`IntRangeOps.leastAndGreatest(long srcData, long srcValidity, int srcNullCount, int length,
long[] out)`, beside `allWithin`, with `LongRangeOps`' contract: `out[0] = Long.MAX_VALUE`
and `out[1] = Long.MIN_VALUE` first; false at once for `length == 0` or `srcNullCount ==
length`; else the least live int to `out[0]` and the greatest to `out[1]`, widened, and
`out[0] <= out[1]` returned.

- Two private loops, as row 683's: `dense` (no null row: a load, `min` and `max` into two
  `IntVector` accumulators) and `nulls` (a mask from the group's validity bits by the class's
  `bitsAt`; a null lane takes `Integer.MAX_VALUE` in one blend and `Integer.MIN_VALUE` in
  another, both on the loaded vector).
- The fold reads `lane(j)` of each accumulator; the tail is scalar, one row at a time with
  its validity bit. No `reduceLanes` and no masked epilogue, as `VARKA-683.md` 4.5 has it.
- An int mask has four lanes at 128 bits, so no loop with no mask is built: `allWithin` runs
  the same masks on every host today.

### 4.9 The forms, the ladder and the table

- **The step forms** (`ONE`, `STEPS_2`, `STEPS_8`, rows 682 and 685) need no change in the
  emitter: `VarkaZoneLowering.emitToLocal` emits its operand by `emitValue` and compares the
  operand's vector with the filled changes. The fill writes the offset at `least(p)` and the
  changes in `(least(p), greatest(p)]` of the operand's interval. The ladder takes its rung
  from the need, which is counted over that interval.
- **A wider interval and the ladder.** An interval wider than the values can hold a change the
  values do not span. Under the ladder the batch then runs a dearer rung, with a step that
  never fires. Only at the ladder's top, or under a forced form, does it decline a batch
  that its values' own range would have let through. For `ts + literal` and for the hulls
  the interval is exact, so the row's done-when holds with no such batch. For a tree with a
  zone node inside, each side is 18 hours wider.
- **The table form** (row 686) computes each row's bucket index in a pass of the kernel's
  driver over the operand's column, into an index map, before the loops. A computed operand
  has no column to pass over. Part e computes the indices in the body instead, where the
  operand's vector is at hand, into a scratch of one lane group: the first ints of the map's
  slack (`VarkaZoneTable.INDEX_SLACK`, 16 ints after the last program's run). The body stores
  the lane group's indices there and gathers from there, in one run of code with no other
  node's code between. It is the form `VARKA-686.md` 4.1 measured as "the indices beside each
  gather" and left for this row: 0.673 ns a row at eight lanes and 0.871 at four, where the
  whole-batch pass costs 0.621 and 0.829 (`results/tables.txt`, `tables-2.txt`).
  `TABLE_SCALAR` reads the operand's lanes one by one and needs no change.
- **Until part e** a table form over a computed operand is refused by the plan
  (`ZONE_TABLE_OPERAND_REASON`), and the ladder of a kernel that holds such a node stops at
  `STEPS_8`: a batch of more than eight changes declines with `STATUS_BATCH_ARGS`.
- **`ToInstant` under a table form** is row 693's. Its operand is computed in two of its
  three first kernels, so row 693 takes part e's scratch as it stands.
- **Two lanes, the form with no mask** (row 689): its decline of a batch whose span does not
  fit a long reads `greatest(p) - least(p)`, which is the operand's interval here too.

### 4.10 Edge cases

| Case | What happens | What holds it |
|---|---|---|
| a null row | no pass reads its storage; the kernel computes the operand over whatever the storage holds, and the output's validity hides the row | the property test's null patterns; the runner's test of nulls over poison values |
| a column with no live row under a strict node | `EMPTY`: the program is not live, the fill is neutral, every output row is null | part a's test 5; part b's test of an all-null column |
| the same under `greatest`, `least`, `coalesce` | the other operand's interval | part a's test 4; part b's test |
| a batch with no live row at all | every program `EMPTY`; need 0; the form `ONE` | part b's test |
| the ends of a long in a column | the column's range holds them; a sum past the end is `UNKNOWN`, and a range within 18 hours of an end is `NOT_COVERED`: the row path | part a's test 6; part c's test of both ends |
| a `WRAP` operand that wraps | its interval leaves the lane: `UNKNOWN`, the batch declines | part b's test |
| an int-lane value past int32 | `UNKNOWN` | part a's test 6 |
| two lanes of 64 bits | the range program is scalar code and does not depend on the width; the passes are rows 683's and part d's | the suites run at both widths through the matrix |
| AVX2 against AVX-512 | the same; part e's store writes one int vector, at most 16 ints | part e's kernel tests at lane overrides |
| a shared subtree, a deep tree | walked for each path; past 256 ops the entry declines by name | part a's test 9 |
| a derived input under an operand | the runner is not built; the emission-failure fallback | 4.6 |

### 4.11 What declines, and with which text

Compile time, by name:

| Case | Text |
|---|---|
| a zone with changes over an operand that has a node with no rule (`VarkaZoneCompiler.unrangedReason(name)`, part c) | `the session time zone over a computed timestamp: a kernel finds a batch's offsets from the least and greatest value of the operand, and no range rule covers its node <name>` |
| a `TIMESTAMP` plus a day-time interval in any form but a literal under a day (`VarkaZoneCompiler.ZONED_INTERVAL_REASON`, part c) | `a TIMESTAMP plus a day-time interval that is no literal of under a day: its whole days are calendar days in the session time zone, which is row 671's` |
| the plan's refusal, for a hand-built tree (`VarkaKernelPlan.ZONE_OPERAND_REASON`, part b) | `a time zone conversion over a value with no range rule: a batch's offsets follow the least and greatest value of the operand, which VarkaOperandRange cannot work out for it` |
| a table form over a computed operand, parts b to e (`VarkaKernelPlan.ZONE_TABLE_OPERAND_REASON`) | `the table form of a time zone conversion over a computed value: its index pass reads a column` |

Run time, a batch: `STATUS_BATCH_ARGS` where the operand's range is `UNKNOWN`, beside the
status's other causes. No new status and no new metric. `docs/sql-varka.md` gains the cause
in the sentence on `batch-args`: "or a computed timestamp's range in the batch cannot be
worked out, because a value on the way to it would leave a long".

## 5. The parts

Six pull requests. Each sheet follows `sql/varka/plans/m8/BUILD_SHEETS.md`: the exports, the
lock, the linters, one local commit, no push. No branch is named `varka-688`, which held this
plan. Part a's last step makes row 688's line in `sql/varka/plans/m9/PLAN.md` open with
`**Started**`, part f's with `**Done**`. Every shell starts:

    export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
    V=sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka
    C=sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen
    E=sql/core/src/main/java/org/apache/spark/sql/execution
    TJ=sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka
    CJ=sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen
    TS=sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka
    XS=sql/core/src/test/scala/org/apache/spark/sql/execution
    XJ=sql/core/src/test/java/org/apache/spark/sql/execution

| Part | Title | Branch | Needs | Counted lines, estimate |
|---|---|---|---|---:|
| a | `[VARKA-688] Work out an operand's range for a batch from its columns' ranges` | `varka-688a-range` | row 657 | 640 |
| b | `[VARKA-688] Fill a zone node's arguments from the range of a computed operand` | `varka-688b-runner` | part a; row 685, parts a to c | 520 |
| c | `[VARKA-688] Fuse a zoned field over a computed timestamp` | `varka-688c-entry` | part b | 650 |
| d | `[VARKA-688] Find the range of an int column, for a zone node over a date` | `varka-688d-int` | part b | 480 |
| e | `[VARKA-688] Read the zone table over a computed operand` | `varka-688e-table` | part c; row 686, parts b and c | 380 |
| f | `[VARKA-688] Results: a zoned field over a computed timestamp at two widths` | `varka-688f-results` | parts c, d and e | 60 |

A JUnit class run alone prints sbt's `Passed: Total N, Failed 0, Errors 0, Passed N`. A
ScalaTest run passes with `failed 0`, `canceled 0` and no `*** FAILED ***`. A planted fault is
planted after the part's commit, the named command is rerun, and the fault is reverted.

### 5.1 Part a: the range program and its rules

**B0, the premises.** Each prints what its comment says, or the builder stops.

    git branch --list 'varka-688a-range' | wc -l                                              # 0
    ls $V/VarkaOperandRange.java $TJ/VarkaOperandRangeSuite.java 2>/dev/null | wc -l          # 0
    grep -c 'record ToLocal(' $V/VarkaVectorIR.java                                           # 1 (row 682)
    grep -c 'ZONE_SHIFT' $V/VarkaRangeAnalysis.java                                           # at least 2 (row 682)
    grep -c 'record ToInstant' $V/VarkaVectorIR.java                                          # 0, or step 2's last sentence
    grep -c 'public record Literals(IntUnaryOperator ints, IntFunction<OptionalLong> longs)' $V/VarkaRangeAnalysis.java   # 1
    grep -c 'public record Bounded(long lo, long hi) implements Range' $V/VarkaValueRange.java   # 1
    grep -c 'record ConstDivide(VarkaVectorIR child, long divisor, long dividendBound, int hash)' $V/VarkaVectorIR.java   # 1
    grep -c 'record BitOp(BitOpKind op, VarkaVectorIR left, VarkaVectorIR right, int hash)' $V/VarkaVectorIR.java   # 1
    grep -c 'List<String> zones' $TJ/VarkaReference.java                                      # at least 1 (row 682's overloads)

**Steps.**

1. `V/VarkaOperandRange.java`, new: the license header of `VarkaValueRange.java`; the members
   of 4.2 in that order; a class comment that says what the class answers, the three states,
   the soundness statement of 4.4 and that an instance belongs to one evaluator. Fields:
   `private final Op[] ops; private final int[] arg; private final long[] consts; private
   final int[] slots; private final VarkaVectorIR.LaneType[] lanes; private final long[] lo;
   private final long[] hi; private boolean unknown;`.
2. The walk, `private static @Nullable String walk(VarkaVectorIR node, @Nullable Builder
   out)`: one switch over `node` with no `default`, an arm for each line of 4.3's table in
   the table's order, then one arm that lists every record of 4.3's "no rule" list by name.
   It returns null where the node and its operands have rules, else the name. `Builder` is a
   private static class of three growing lists, a sorted set of slots and the literals; its
   `op(Op, int)` counts ops and depth and fails the walk past `MAX_OPS`. An arm checks its
   node's lane and shape first and answers the name where 4.3 gives no rule for it. If B0
   found `ToInstant`: an arm for it and one for `ToInstantKept`, each the local operand's ops
   and `SHIFT`; else none, and row 690's records fail to compile here until that row adds
   the arm 4.3 gives.
3. `range`: clear `unknown`; one loop over `ops` with a switch over the enum, no `default`;
   each case is its rule of 4.3 on the top of the stacks, with the overflow tests of 4.3.
   After the loop: `unknown` answers `UNKNOWN`; `lo[0] > hi[0]` answers `EMPTY`; else write
   `out` and answer `LIVE`. No call of `Math.addExact`, `subtractExact`, `multiplyExact` or
   `negateExact` in the file.
4. `TJ/VarkaOperandRangeSuite.java`, new: the ten tests below.
5. `sql/varka/plans/m8/VARKA-489.md`, "Adding a node, after 489c", step 3: after
   "`VarkaRangeAnalysis`," add "`VarkaOperandRange`'s walk,".
6. `sql/varka/plans/m9/PLAN.md`, row 688: `**Filed**` becomes `**Started**`, with this plan's
   file name and date as the other rows have them.

**Tests.** `VarkaOperandRangeSuite`, JUnit 5, `@VarkaSuite`, each test with a `@DisplayName`.
It emits no kernel. A helper builds column ranges by hand, compiles a tree and returns the
state and the two ends. "The edge set" is `Long.MIN_VALUE`, `Long.MIN_VALUE + 1`, `-(1L <<
62)`, `Integer.MIN_VALUE`, -1, 0, 1, `Integer.MAX_VALUE`, `1L << 62`, `Long.MAX_VALUE - 1`
and `Long.MAX_VALUE`; "the intervals" are every ordered pair of it.

| # | Test | The failure only it catches |
|---:|---|---|
| 1 | each arithmetic rule equals the lattice's operation, and is unknown where the lattice is: `ADD`, `SUB`, `MUL` over two columns and `NEG` over one, for every pair of the intervals, against `VarkaValueRange.of(...).add`, `sub`, `mul`, `neg`; `DIV` by 3 and by 10546875 against `divideBy`, with a `dividendBound` of `1L << 52`, and `UNKNOWN` where the interval is not inside the bound | a rule that differs from the proven lattice: `SUB` as the sum's interval, a corner product left out, an overflow that wraps |
| 2 | a shift right by each count from 0 to 63 answers the two ends shifted, for every interval, and a count of 64 + k answers as k | a shift rule that takes the count unmasked, or that swaps the ends |
| 3 | the floor division by a day is exact at both ends: the hand-built tree of fact 9 (shift by 13, sign mask, sum, `ConstDivide` by 10546875 with the bound `(1L << 50) + 10546875`) over intervals below zero, across zero and above it answers `[Math.floorDiv(a, D), Math.floorDiv(b, D)]` | a sign mask rule that loses a day at one end, which costs a form; or one that gains a day, which is unsound |
| 4 | `Greatest`, `Least` and `IfElse` take both operands: two columns of disjoint ranges answer the hull; with one column not live, the other's range; with neither, `EMPTY` | a hull that reads one operand, the missed-change fault of section 2 |
| 5 | a strict node over a column with no live row is `EMPTY`, and so are a `NullValue` and a `ToLocal` over one: `col0 + col1`, `col0 * col1`, `-col1`, with `col1` not live and `col0` at the ends of a long | an empty operand that reaches the arithmetic and comes back `LIVE` or `UNKNOWN` |
| 6 | an interval that leaves its lane is unknown: a long sum past `Long.MAX_VALUE`, a long product, a negation of `Long.MIN_VALUE`, an int-lane sum of two literals past int32 that a later subtraction brings back, a `NarrowLane` of a long column past int32 | a wrapped lane whose interval looks inside again |
| 7 | a zone node widens its operand by 18 hours each way, and every zone's local time is inside: `ToLocal` over `[a, b]` answers `[a - M, b + M]`; and for `Pacific/Kiritimati`, `Pacific/Apia`, `America/Los_Angeles`, `Asia/Kathmandu` and `Etc/GMT+12`, 10000 random instants a zone, each `VarkaReference.mixedValue(..., zones)` is inside | a shift narrower than an offset the JVM's data holds |
| 8 | every live row's value is inside its range, over random operands and random columns: 2000 draws by a printed seed (`-Dvarka.fuzz.seed`); a tree of depth at most 4 from the nodes with a rule over two long columns and long literals (`MUL` only under `WRAP`, fact 15), 64 rows, the columns' values from three regimes (small, around a change of `America/Los_Angeles`, near the ends of a long), nulls in none, some or all rows of a column; the columns' ranges by a scalar loop; each row's `mixedValue`: a present value needs `LIVE` and the value inside, or `UNKNOWN`; `EMPTY` needs no present value. At least a tenth of the draws answer `LIVE` | a rule that is right alone and unsound under a parent no written test has |
| 9 | a node with no rule is named, and a large tree is refused: `BitOp XOR` answers `BitOp/LONG`, a `Year` its name, an `IntNeg` with `abs`, an int column `ColumnRef/INT`; `supports` is false and `compile` throws with the name; a chain of 300 sums answers `more than 256 range operations` | an operand admitted with a node the program cannot range, which declines every batch for ever |
| 10 | a call allocates nothing: after 20000 calls of `range` on test 3's tree, 100000 more allocate under one byte a call (`com.sun.management.ThreadMXBean.getThreadAllocatedBytes`) | a boxed value, a lambda or an exception on the path that runs for each batch |

**Commands**, in order:

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/Test/compile'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaOperandRangeSuite'
    git diff --stat $(git merge-base origin/master HEAD) -- sql/core/src/main $V/VarkaRangeAnalysis.java $V/VarkaValueRange.java | wc -l
    dev/varka_regen_generated.sh
    git diff --exit-code -- sql/varka/emitted_bytes.json sql/varka/coverage.json sql/varka/width_audit.json
    python3 dev/varka_push_check.py

Expected: the compile succeeds; the second prints `Passed: Total 10, Failed 0, Errors 0,
Passed 10`; the third prints 0; the `git diff` and the push check exit 0.

**Planted faults.**

| Fault | The test that must fail |
|---|---|
| `SUB` answers `[a - c, b - d]` | 1 |
| `MUL` without its overflow test | 1, at the ends of a long |
| `ADD` without the test for an empty operand | 5 |
| `HULL` keeps its first operand | 4 |
| `FIT_INT` does nothing | 6 |
| `SHIFT` by 9 hours | 7, under `Pacific/Kiritimati` |

**Done when** the commands print the above, the six faults fail as named, and the size count
is under 900. **Not here:** a caller in main code; an edit of `VarkaRangeAnalysis` or
`VarkaValueRange`; a rule for an int column; a file under `sql/core`; a generated file.

### 5.2 Part b: the runner

**B0.**

    git branch --list 'varka-688b-runner' | wc -l                                             # 0
    grep -c 'public static @Nullable String unsupported(VarkaVectorIR operand)' $V/VarkaOperandRange.java   # 1 (part a)
    grep -n 'operandColumn(' $V/*.java $E/*.java $E/varka/*.java                               # its readers: note each
    grep -c 'ZONE_OPERAND_REASON' $V/VarkaKernelPlan.java                                     # at least 2 (row 682)
    grep -c 'public VarkaBatchRanges(int\[\] input, VarkaZoneData\[\] zone)' $E/varka/VarkaBatchRanges.java   # 1 (row 685)
    grep -c 'public int measure(ColumnarBatch batch, int len, int\[\] inputOrdinals)' $E/varka/VarkaBatchRanges.java   # 1
    grep -c 'LongRangeOps.leastAndGreatest' $E/varka/VarkaBatchRanges.java                    # 1; 0 means row 687 has merged: stop and report
    grep -n 'new VarkaBatchRanges(' $E/VarkaEvaluatorBase.java                                # one line, in newRunner
    grep -n '\.input()' $E/varka/VarkaKernelRunner.java $E/VarkaEvaluatorBase.java $E/varka/VarkaBatchRanges.java   # the readers of BatchProgram.input: note each
    grep -c 'LADDER' $V/VarkaZoneLadder.java                                                  # at least 1 (row 685, part c)
    grep -c 'TABLE(-1)' $V/VarkaEmitOptions.java                                              # 0, or steps 2 and 5
    grep -n 'ZONE_OPERAND_REASON' $TJ/VarkaBatchArgsSuite.java                                # row 682's refusal test
    grep -c '@Test' $TJ/VarkaBatchArgsSuite.java; grep -c '  test("' $XS/VarkaBatchArgRunnerSuite.scala   # n1 and n2: note them
    ls $TJ/VarkaZoneKernels.java 2>/dev/null | wc -l                                           # 1 if row 684 has merged: step 7

A reader of `BatchProgram.input` that indexes an array with it is a stop: after row 685 the
fill reads the ranges, and a computed operand's `input` is -1.

**Steps.**

1. `V/VarkaKernelPlan.java`, in `of`: the test that refuses a zone node,
   `VarkaBatchArgs.operandColumn(node) < 0`, becomes `VarkaOperandRange.unsupported(
   node.operand()) != null`. `ZONE_OPERAND_REASON` takes 4.11's text.
2. Only if B0 found `TABLE(-1)`: the same place also refuses, under `Kind.ZONE_OPERAND` with
   the new constant `ZONE_TABLE_OPERAND_REASON` (4.11), a zone node with `operandColumn(node)
   < 0` where the kernel's form is `TABLE` or `TABLE_SCALAR`. Read the form as the first
   statement of `VarkaZoneLowering.emitToLocal` reads it.
3. `E/varka/VarkaBatchRanges.java`, as 4.6. The constructor is `VarkaBatchRanges(
   VarkaOperandRange[] operand, VarkaZoneData[] zone, int inputs)`. It collects the distinct
   slots of every operand, ascending, into `int[] slots`, and throws
   `IllegalArgumentException` for a slot whose `lane(k)` is not `LONG`. Fields: `boolean[]
   inLive`, `long[] inLeast`, `long[] inGreatest` of length `inputs`, and `long[] pair = new
   long[2]`. `measure`: under the memory sanitizer, the window and the registrations it has
   today, for each slot of `slots`; then one pass a slot; then for each program the
   evaluation and the three outcomes of 4.6. No stream, lambda or boxing.
4. `E/VarkaEvaluatorBase.java`, `newRunner`, at the line B0 printed: `var literals = new
   VarkaRangeAnalysis.Literals(i -> compiled.literals().get(i), i ->
   OptionalLong.of(compiled.longLiterals().get(i)));`; for each program of the layout,
   `VarkaOperandRange.compile(program.node().operand(), literals)`; for each slot of each
   operand, `compiled.derivedAt(slot).isPresent()` throws `new IllegalStateException("a zone
   node's operand reads the derived input " + slot)`; the ranges are built with
   `inputOrdinals.length`. `BatchProgram.input` stays `VarkaBatchArgs.operandColumn(node)`.
5. Only if B0 found `TABLE(-1)`: in `plan()`, after row 685's `widest` is set: if some
   program of the layout has `operandColumn(node) < 0`, `widest = Math.min(widest,
   VarkaZoneLadder.RUNGS.indexOf(ZoneForm.STEPS_8))`.
6. The tests below. Row 682's refusal test in `VarkaBatchArgsSuite` keeps its name and takes
   the operand `(bit:XOR col:0:long col:1:long)`; it also asserts that
   `(toLocal:0 (int:ADD:WRAP col:0:long col:1:long))` plans with no refusal.
7. Only if B0 found `VarkaZoneKernels`: row 684's draw of a zone node in `TJ/VarkaIrGrammar
   .java` takes, one time in two, the operand `new IntArith(IntOp.ADD, Overflow.WRAP, <the
   column it drew>, <a long literal slot, drawn as its other long literals are>)`. The
   harness's filler, `VarkaZoneKernels.production`, takes a program's least and greatest
   from `VarkaOperandRange.compile(...).range(...)` over the scalar least and greatest of
   each column, and declines the batch on `UNKNOWN`. If the draw or the filler has no place
   for either, stop and report.
8. `docs/sql-varka.md`: the sentence on `batch-args` gains 4.11's words.

**Tests.** One added to `VarkaBatchArgsSuite` (JUnit):

| Test | The failure only it catches |
|---|---|
| kernel: a zone node over `col + literal` compares the sum with each change, under `STEPS_2` and `STEPS_8` with hand-set steps: a row whose column is before `at` and whose sum is at or after it takes the step | a lowering or a slot plan that reads the column in the operand's place |

Seven added to `VarkaBatchArgRunnerSuite` (ScalaTest), over hand-built projections, the zone
`Europe/Podgorica` and the suite's reference provider, as row 682's tests are:

| Test | The failure only it catches |
|---|---|
| a sum's range, not its column's, chooses the offsets: `(toLocal:0 (int:ADD:WRAP col:0:long lit:0:long))` with one hour, a batch inside the hour before the October change and one inside the hour before the March change: every row equals the reference, no decline | the column's range filled for the operand: the offset of the wrong side on every row, with no fallback |
| greatest of two columns is filled from both ranges: one column in July, one in January, nulls in each | a hull that reads one column |
| a column with no live row adds nothing under greatest and empties a sum: after a batch that spans a change, `greatest(col0, col1)` with `col1` all null is served and right, and `col0 + col1` answers all null with no decline | an empty operand that declines, or that runs on the last batch's arguments |
| a sum that would leave a long declines the batch: a literal of `Long.MAX_VALUE / 2` over a live value above it: `batch-args` counts 1 and the rows are the fallback path's | an `UNKNOWN` range filled as if it were known |
| two zone nodes over one column, `toLocal(col)` and `toLocal(col + one hour)`, are each filled from their own range, on a batch inside the hour before a change | two programs that share one range |
| nulls over poison values (`VarkaEmitterFixtures.makeLongInput`) do not reach a computed operand's range; run with the memory sanitizer too | a null row's storage in a range |
| under the ladder a computed operand runs the least form that covers its range: a week is served by `ONE`, a year by `STEPS_2`, by row 685's `served` helper | a need counted over the column's range |

**Commands.**

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaBatchArgsSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaOperandRangeSuite *VarkaEmitterZoneSuite *VarkaZoneSweepSuite *VarkaZoneLadderSuite *VarkaIrFuzzSuite *VarkaWithChildrenSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'sql/testOnly *VarkaBatchArgRunnerSuite *VarkaTimestampZoneSuite *VarkaTimestampZoneQuerySuite *VarkaKernelEvaluatorSuite *VarkaReproductionEndToEndSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'set LocalProject("sql") / Test / javaOptions += "-Dvarka.sanitizeMemory=true"' 'sql/testOnly *VarkaBatchArgRunnerSuite'
    dev/varka_regen_generated.sh
    git diff --exit-code -- sql/varka/emitted_bytes.json sql/varka/coverage.json sql/varka/width_audit.json
    python3 dev/varka_push_check.py

Expected: the first prints `Passed: Total n1 + 1, Failed 0, Errors 0`; the second and third
print `Failed 0, Errors 0` and `failed 0`; `VarkaBatchArgRunnerSuite` reports n2 + 7 tests
in both of its runs, none canceled; the `git diff` and the push check exit 0. If the push
check's rule R2 names a test or a document that still holds the old reason text, give it
the new text.

**Planted faults.**

| Fault | The test that must fail |
|---|---|
| `measure` gives a program its first column's range and skips the evaluation | a sum's range, not its column's |
| `measure` treats `UNKNOWN` as `EMPTY` | a sum that would leave a long |
| `VarkaKernelPlan.of` keeps `operandColumn(node) < 0` | the kernel test of `VarkaBatchArgsSuite`, by a refusal |
| `newRunner` builds one `VarkaOperandRange` and gives it to every program | two zone nodes over one column |

**Done when** the commands print the above and the four faults fail as named. **Not here:**
`VarkaZoneCompiler` (the SQL path still declines a computed operand); an int column; a table
form over a computed operand; a new status, metric or option; `VarkaWarmupGate`.

### 5.3 Part c: the compiler, and the row's entry

**B0.**

    git branch --list 'varka-688c-entry' | wc -l                                              # 0
    grep -c 'if (!(instant instanceof ColumnRef)) {' $C/VarkaZoneCompiler.java                # 1 (row 657, part A)
    grep -c 'COMPUTED_OPERAND_REASON' $C/VarkaZoneCompiler.java                               # at least 2
    grep -rn 'COMPUTED_OPERAND_REASON\|over a computed timestamp' sql/catalyst/src/test sql/core/src/test docs   # every holder of the old decline: note each
    grep -c 'TimestampAddInterval' $C/VarkaZoneCompiler.java                                  # 0; more means row 671 has started: stop and report
    grep -n 'case Hour' $C/VarkaZoneCompiler.java                                             # the arm: the new case goes beside it
    grep -c 'VarkaOperandRange.unsupported(' $V/VarkaKernelPlan.java                          # 1 (part b)
    grep -n 'hour(ltzy)' $TS/VarkaCoverageSuite.scala                                         # row 657's coverage rows
    grep -n 'TimestampAddInterval' sql/varka/plans/m9/census_curated.txt                      # row 654's entry of ts + dt, or nothing
    grep -c '@Test' $CJ/VarkaZoneCompilerSuite.java; grep -c '@Test' $XJ/VarkaTimestampZoneSuite.java   # n3 and n4: note them
    grep -n 'greatest(ts, ts2)' $XS/VarkaTimestampZoneQuerySuite.scala                        # row 657's test of the decline
    ls $XJ/benchmark/VarkaZoneLadderBenchmark.java | wc -l                                     # 1 (row 685, part d: the model)

**Steps.**

1. `C/VarkaZoneCompiler.java`, `toLocal`: the statement B0 printed becomes

       String unranged = VarkaOperandRange.unsupported(instant);
       if (unranged != null) {
         return sink.decline(unrangedReason(unranged), whole);
       }

   `COMPUTED_OPERAND_REASON` goes; `static String unrangedReason(String node)` returns 4.11's
   first text with the name in place of `<name>`.
2. The same file, the arm: a case beside `Hour`'s, `TimestampAddInterval a when
   a.start().dataType() instanceof TimestampType && a.interval().dataType() instanceof
   DayTimeIntervalType`, which calls a new private method `zonedPlusInterval(a, inputs,
   literals, sink)`:
   - unless `a.interval()` is a `Literal` whose value is a non-null `Long v` with `v /
     DateTimeConstants.MICROS_PER_DAY == 0`: `return sink.decline(ZONED_INTERVAL_REASON, a)`;
   - compile `a.start()` as the arm compiles `Hour`'s child, with the same lane test and
     text ("a timestamp operand that is not on the long lane");
   - answer `new IntArith(IntOp.ADD, Overflow.FAIL, instant, sink.longSlot(v))`.
   It reads no zone. Its comment says why (fact 8) and names `ntzPlusInterval` as its twin.
3. `CJ/VarkaZoneCompilerSuite.java`: row 657's test of the computed operand's decline keeps
   its name; its declining operand becomes the IR `new BitOp(BitOpKind.XOR, col0, col1)`
   handed to `toLocal`, with the reason `unrangedReason("BitOp/LONG")`. Four tests more,
   below.
4. `XJ/VarkaTimestampZoneSuite.java`: five tests, on the suite's own zones and fixture.
5. `XS/VarkaTimestampZoneQuerySuite.scala`: the test B0 printed turns over: `hour(greatest(
   ts, ts2))` is now `fused` in the verbose `EXPLAIN`, declines no batch and equals Spark.
   One test more, below. No SQL of rows 656 and 657 reaches `unrangedReason`: every
   timestamp producer they fuse has a rule. The row that first fuses one without adds the
   query test of that decline.
6. `TS/VarkaCoverageSuite.scala`, after row 657's rows and in their family: `hour(ltzy +
   INTERVAL '1' HOUR)` and `minute(greatest(ltzy, ltzy - INTERVAL '90' MINUTE))`, each with
   the note "a zoned field over a computed timestamp: the batch's offsets follow the
   operand's range, worked out from the column's range (VARKA-688)".
7. If B0 printed a census entry: its `ltz` among the fused types takes the mark `^` (fused in
   part), and its note quotes the first coverage row, as the file's header describes; then
   `python3 dev/varka_census.py`. If it printed nothing, say so in the pull request: the
   cell is row 654's or row 671's to add.
8. `docs/sql-varka.md`, "Timestamps in the session time zone": two sentences. A field or a
   cast over a computed timestamp fuses where each node of the computation has a range rule,
   and declines by name otherwise. A `TIMESTAMP` plus a literal interval of under a day
   fuses; a longer or a column interval is not fused yet.
9. `XJ/benchmark/VarkaZoneOperandBenchmark.java`, new, Java, modelled on
   `VarkaZoneLadderBenchmark`: section 7's groups, but the int pass's.
10. `dev/varka_regen_generated.sh --all`. Then row 655's queries suites (the fifth command):
    a section that now fuses unlisted is listed as that suite's failure message says.

**Tests.** `VarkaZoneCompilerSuite`, four more:

| Test | The failure only it catches |
|---|---|
| a zoned field over greatest of two timestamps holds one `ToLocal` over the `Greatest` | a decline that outlives the rule |
| ts plus a literal under a day is a checked sum on the instant, with no zone node: `TimestampAddInterval(ts, Literal(3600000000L, DayTimeIntervalType()), Some("America/Los_Angeles"))` is `(int:ADD:FAIL col:0:long lit:0:long)`; minus 23 hours and UTC give the same shape | a zone read where Spark reads none |
| hour of ts plus an hour is the `TIMESTAMP_NTZ` hour over `(toLocal:0 (int:ADD:FAIL col:0:long lit:0:long))` | a `ToLocal` over the column with the sum outside it, which is a different instant near a change |
| a literal of a day, of minus two days, a null literal and an interval column decline with `ZONED_INTERVAL_REASON` | a calendar day added as 86400 seconds across a change |

`VarkaTimestampZoneSuite`, five more, each against Spark's row engine:

| Test | The failure only it catches |
|---|---|
| hour, minute, second and the cast to DATE over `ts + INTERVAL '1' HOUR` equal Spark across each zone's changes; the fixture gains rows inside the hour before each change | a kernel right only where the sum crosses no change |
| hour over `ts - INTERVAL '23' HOUR`, and over greatest, least, coalesce and CASE of two timestamps, equals Spark across the changes | a rule that holds for one node and not for its siblings |
| a year of instants in no order under `America/Los_Angeles` declines no batch for `hour(ts + INTERVAL '1' HOUR)`, as one batch and as batches of 64 rows | the row's done-when |
| ten years of instants in no order, as one batch, decline with `STATUS_BATCH_ARGS` | a form that serves a batch past its steps; part e turns this test over |
| a batch with a live instant five microseconds under `Long.MAX_VALUE`: the harness's `Batch` holds the status `STATUS_BATCH_ARGS`, read before any row is compared (the row engine raises on that row, which is not this test's) | a sum that wraps into a wrong offset |

`VarkaTimestampZoneQuerySuite`, one more: `hour(ts + INTERVAL '1' HOUR)` over a cached table
under `America/Los_Angeles`: `numVarkaBatches` is above 0, `numFallbackBatchesDeclined` is
0, and the rows are Spark's. Only it catches a path that fuses in the harness and not in a
session.

**Commands.**

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaZoneCompilerSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'sql/testOnly *VarkaTimestampZoneSuite'
    dev/varka_regen_generated.sh --all
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaCoverageSuite *VarkaFamilySwitchSuite *VarkaFamilyChainSuite *VarkaExpressionCompilerSuite *VarkaEmittedBytesSuite *VarkaWidthAuditSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'sql/testOnly *VarkaTimestampZoneQuerySuite *VarkaCoverageHarnessSuite *VarkaCoverageDifferentialSuite *VarkaFamilySwitchQuerySuite *BaselineQueriesSuite'
    dev/varka_emit.sh "hour(ts + INTERVAL '1' HOUR)" --columns ts:timestamp --zone America/Los_Angeles
    python3 dev/varka_census.py --check
    python3 dev/varka_bytes_moved.py origin/master --ignore option_arms | grep -vc 'ltzy'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch "project sql" 'set Test/javaOptions += "-Dvarka.bench.dryRun=true"' "Test/runMain org.apache.spark.sql.execution.benchmark.VarkaZoneOperandBenchmark" > target/dry-688.log 2>&1; echo $?
    python3 dev/varka_push_check.py

Expected: `Passed: Total n3 + 4, Failed 0, Errors 0`; `Passed: Total n4 + 5, Failed 0, Errors
0`; the suites of the fourth and fifth commands pass; the emit tool prints `FusedOutput` and
an IR that holds `(toLocal:0 (int:ADD:FAIL col:0:long lit:0:long))`; the census check exits
0; the eighth prints 0 (every digest that moved is a row over `ltzy`); the dry run prints 0
and `grep -c 'Exception' target/dry-688.log` prints 0; the push check exits 0.

**Planted faults.**

| Fault | The test that must fail |
|---|---|
| `zonedPlusInterval` without its test of the whole days | a literal of a day ... decline |
| `zonedPlusInterval` builds `IntOp.SUB` | ts plus a literal under a day; and the Spark test of the fields |
| `toLocal` without the range test | row 657's test of the decline, on the `BitOp` |

**Done when** the commands print the above, the three faults fail as named, and the size
count is under 900. Over it, the benchmark class (step 9) moves to part f. **Not here:** an
interval of a day or more, an interval column, `ts - ts`, `timestampadd` (row 671); a new IR
node; a change to a default; a timed run.

### 5.4 Part d: the range of an int column

**B0.**

    git branch --list 'varka-688d-int' | wc -l                                                # 0
    grep -c 'public static' $V/IntRangeOps.java                                               # 1
    grep -c 'private static long bitsAt(MemorySegment bitmap, long row, int rows)' $V/IntRangeOps.java   # 1
    grep -c 'static boolean leastAndGreatest(long srcData, long srcValidity, int srcNullCount, int length,' $V/LongRangeOps.java   # 1 (row 683)
    grep -c 'IntRangeOps' dev/varka_nullaway_pending.txt                                      # 1: the list may not grow
    grep -c 'LongRangeOps.leastAndGreatest' $E/varka/VarkaBatchRanges.java                    # 1
    grep -c 'ColumnRef/INT' $TJ/VarkaOperandRangeSuite.java                                   # at least 1 (part a's test 9)
    ls $TJ/VarkaLongRangeOpsSuite.java | wc -l                                                 # 1 (row 683's suite: the model)
    grep -c '@Test' $TJ/VarkaOperandRangeSuite.java; grep -c '  test("' $XS/VarkaBatchArgRunnerSuite.scala   # 10 and n5: note n5

**Steps.**

1. `V/IntRangeOps.java`: `leastAndGreatest` as 4.8, after `allWithin`, with the private
   loops `dense` and `nulls` and the private `fold` and `tail`. No segment is ever null. The
   class comment gains one sentence: the second method returns the range of a batch's int
   column, which the evaluator reads for a zone node over a date.
2. `V/VarkaOperandRange.java`: the walk's `ColumnRef` arm admits the `INT` lane; `lane(k)`
   answers it.
3. `E/varka/VarkaBatchRanges.java`: the constructor accepts an `INT` slot and keeps `boolean[]
   wide` beside `slots`; `measure` calls `IntRangeOps.leastAndGreatest` for a slot that is
   not wide.
4. `TJ/VarkaIntRangePassSuite.java`, new, JUnit 5, `@VarkaSuite`, laid out as
   `VarkaLongRangeOpsSuite` lays its columns out: the five tests below.
5. `TJ/VarkaOperandRangeSuite.java`: test 9 drops the int column; test 8's grammar gains an
   int column under `WidenLane` and under `GuardedRange`; two tests more, below.
6. `XS/VarkaBatchArgRunnerSuite.scala`: two tests more, below.
7. `XJ/benchmark/VarkaZoneOperandBenchmark.java`: the group "the pass over 4096 rows", with
   the cases "a long column", "an int column" and "an int column, one row in ten null".

**Tests.** `VarkaIntRangePassSuite`:

| # | Test | The failure only it catches |
|---:|---|---|
| 1 | the pass equals a scalar loop over random int columns at every length from 0 to 67 and at 4096, with no nulls | a tail row left out, a lane read past the batch |
| 2 | the same with nulls in one row in three, in runs, and in all rows but one, the null rows over `Integer.MIN_VALUE` and `Integer.MAX_VALUE` | a null row's storage in the range |
| 3 | a column with no live row and a column of no rows answer false and leave `out` as `Long.MAX_VALUE` and `Long.MIN_VALUE` | a range an earlier call left |
| 4 | `Integer.MIN_VALUE` and `Integer.MAX_VALUE` as live values come back widened, not wrapped | an accumulator that starts from the wrong identity |
| 5 | one null row at each position from 0 to 70 of a 71-row column, one at a time, the null row over the column's least value: the range leaves out exactly that row | a group's validity bits read one row off |

`VarkaOperandRangeSuite`, two more: "the midnight of a date column is exact at both ends":
fact 10's tree over days `[a, b]` answers `[a * D, b * D]`, and a day whose product leaves a
long is `UNKNOWN`; and "an int column's ends are widened, not wrapped", at both ends of an
int. `VarkaBatchArgRunnerSuite`, two more: "a date's midnight is filled from the int column's
range": `(toLocal:0 (int:MUL:WRAP (widen col:0:int) lit:0:long))` over the days around the
October change, with nulls over poison values, equals the reference; and "an all-null date
column runs and answers all null". Only they catch an int slot that the ranges leave out.

**Commands.**

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaIntRangePassSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaOperandRangeSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *IntRangeOpsSuite *VarkaLongRangeOpsSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'set LocalProject("catalyst") / Test / javaOptions ++= Seq("-XX:UseAVX=2", "-XX:MaxVectorSize=16")' 'catalyst/testOnly *VarkaIntRangePassSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'sql/testOnly *VarkaBatchArgRunnerSuite *VarkaTimestampZoneSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'set LocalProject("sql") / Test / javaOptions += "-Dvarka.sanitizeMemory=true"' 'sql/testOnly *VarkaBatchArgRunnerSuite'
    dev/varka_regen_generated.sh
    git diff --exit-code -- sql/varka/emitted_bytes.json sql/varka/coverage.json sql/varka/width_audit.json
    python3 dev/varka_push_check.py

Expected: `Passed: Total 5, Failed 0, Errors 0, Passed 5`; `Passed: Total 12, Failed 0, Errors
0, Passed 12`; the third passes with no edit to those suites; the fourth prints `Passed:
Total 5` again; `VarkaBatchArgRunnerSuite` reports n5 + 2 tests in both runs, none canceled;
the `git diff` and the push check exit 0.

**Planted faults.**

| Fault | The test that must fail |
|---|---|
| the `nulls` loop blends `Integer.MAX_VALUE` into `greatest` too | `VarkaIntRangePassSuite` 2 |
| the tail takes a row without its validity bit | 2, at the lengths that are no multiple of a lane group |
| `measure` runs no pass for a slot that is not wide, and leaves it not live | a date's midnight is filled from the int column's range |

**Done when** the commands print the above and the three faults fail as named. **Not here:**
an edit of `allWithin` or of `IntRangeOpsSuite`; a narrow column; a rule for a calendar
node; a kernel of row 690.

### 5.5 Part e: the table form over a computed operand

**B0.**

    git branch --list 'varka-688e-table' | wc -l                                              # 0
    grep -c 'static void emitToLocalIndex(' $V/VarkaZoneLowering.java; grep -c 'static void emitToLocalTable(' $V/VarkaZoneLowering.java   # 1 and 1 (row 686, part b)
    grep -c 'INDEX_SLACK = 16' $V/VarkaZoneTable.java                                         # 1
    grep -c 'ZONE_TABLE_OPERAND_REASON' $V/VarkaKernelPlan.java                               # at least 2 (part b, step 2); 0 means row 686 merged after part b: the lead adds steps 2 and 5 of 5.2 first
    grep -n 'RUNGS.indexOf(ZoneForm.STEPS_8)' $E/VarkaEvaluatorBase.java                      # one line (part b, step 5)
    grep -n 'emitToLocalIndex(' $V/VarkaBodyEmitter.java                                      # the driver's pass, one call a program
    grep -n 'intoArray\|fromArray' $V/VarkaZoneLowering.java                                  # the store of the pass, the two gathers of the body
    grep -c 'record ToInstant' $V/VarkaVectorIR.java                                          # 0 or 1: either is right; this part changes ToLocal's lowering alone
    grep -c '@Test' $TJ/VarkaZoneTableSuite.java; grep -c '  test("' $XS/VarkaBatchArgRunnerSuite.scala   # n6 and n7: note them
    grep -n 'ten years' $XJ/VarkaTimestampZoneSuite.java                                      # part c's test of the decline, and row 686's tests

If the driver's pass or the body's gathers are not laid out as `VARKA-686.md` 4.3 has them
(a store of one int vector at `j * length + i`, two gathers from the same place), stop and
report the shape: this sheet was written beside that plan, not beside its code.

**Steps.**

1. `V/VarkaZoneLowering.java`, `emitToLocalTable`: for a node with
   `VarkaBatchArgs.operandColumn(n) < 0`, after the operand's vector is in the node's
   `zoneTmp`, emit the six calls of the index pass on that vector (the subtraction of
   `ORIGIN`, the shift by 39, the conversion to ints, the `min` with `BUCKETS - 1`, the shift
   left by 1, the `intoArray`), with the store's offset `scratch = perRow * length`: `perRow`
   is `analysis.indexMapIntsPerRow()`, a constant of the class, and `length` the kernel's
   parameter. Then the body's eight calls as they are, with `scratch` as both gathers' map
   offset. A method-local guard throws `IllegalStateException` where the int species holds
   more than `VarkaZoneTable.INDEX_SLACK` lanes. For a column operand nothing changes.
2. `V/VarkaBodyEmitter.java`, the driver: the index pass runs for the programs whose operand
   is a column, and for no other.
3. `V/VarkaZoneTable.java`: the comment of `INDEX_SLACK` says that the slack is also the
   scratch of a program over a computed operand, written and read inside one lane group.
4. `V/VarkaKernelPlan.java`: the refusal of part b's step 2 and its constant go.
   `E/VarkaEvaluatorBase.java`: the cap of part b's step 5 goes.
5. The tests below; part c's test "ten years of instants in no order, as one batch,
   decline" turns over: where `VarkaZoneTable.gatherFallsBack(VarkaHostIsa.host())` is false
   no batch declines and the rows are Spark's; where it is true the batch declines as before.
6. `docs/sql-varka.md`: nothing, unless row 686 wrote that a computed timestamp takes no
   table; then that sentence goes.

**Tests.** `VarkaZoneTableSuite`, four more, over a hand table as that suite's tests build it:

| Test | The failure only it catches |
|---|---|
| a table kernel over `col + literal` answers `VarkaZoneTable.toLocal` of the sum, before, at and after each change, at every length of `VarkaEmitterFixtures.LENGTHS`, dense and with nulls, under `TABLE` and `TABLE_SCALAR` | gathers that read the column's unwritten run of the map |
| a computed program and a column program in one kernel, in both orders, each answer their own rows, at lengths under one lane group too | the scratch written over indices another program still needs |
| null rows over `Long.MIN_VALUE`, `Long.MAX_VALUE` and -1 under a computed operand: the call throws nothing and the live rows are right | an index stored with no clamp |
| under `TABLE`, the dense loop of a computed program holds one `intoArray`, and the driver holds no index pass for it; tagged `PinsDefaults` | a pass that runs twice, in the driver and in the body |

`VarkaBatchArgRunnerSuite`, one more: ten years of instants in no order through `(toLocal:0
(int:ADD:WRAP col:0:long lit:0:long))` under a forced `TABLE`: no batch declines and every
row equals the reference. Only it catches a table rung that the plan still refuses.

**Commands.**

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaZoneTableSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'catalyst/testOnly *VarkaZoneTableLanesSuite *VarkaBatchArgsSuite *VarkaZoneSweepSuite *VarkaEmitterBudgetSuite *VarkaIrFuzzSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'sql/testOnly *VarkaBatchArgRunnerSuite *VarkaTimestampZoneSuite *VarkaTimestampZoneQuerySuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'set LocalProject("sql") / Test / javaOptions += "-Dvarka.sanitizeMemory=true"' 'sql/testOnly *VarkaBatchArgRunnerSuite *VarkaTimestampZoneSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'set LocalProject("sql") / Test / javaOptions ++= Seq("-XX:UseAVX=2", "-XX:MaxVectorSize=16")' 'sql/testOnly *VarkaTimestampZoneSuite'
    dev/varka_emit.sh "hour(ts + INTERVAL '1' HOUR)" --columns ts:timestamp --zone America/Los_Angeles --options zoneForm=TABLE
    dev/varka_regen_generated.sh
    git diff --exit-code -- sql/varka/emitted_bytes.json sql/varka/coverage.json sql/varka/width_audit.json
    python3 dev/varka_push_check.py

Expected: `Passed: Total n6 + 4, Failed 0, Errors 0`; the second passes; `VarkaBatchArgRunner
Suite` reports n7 + 1 tests, none canceled, in both runs; the two-lane run passes with the
ten-year test on its declining side; the emit tool prints `FusedOutput` where part b made it
print a refusal; the `git diff` and the push check exit 0.

**Planted faults.**

| Fault | The test that must fail |
|---|---|
| the gathers keep the map offset `j * length + i` | a table kernel over `col + literal`, on every row |
| the store before the `min` | null rows ... under a computed operand, by an exception |
| the evaluator's cap stays | ten years of instants in no order, by a decline |

**Done when** the commands print the above and the three faults fail as named. **Not here:**
`ToInstant`'s table (row 693); `tableFromChanges`; a second map or a new parameter of a
kernel; a change to a column program's bytes.

### 5.6 Part f: the results and the close

**B0.** `ls $XJ/benchmark/VarkaZoneOperandBenchmark.java | wc -l` prints 1;
`ls sql/core/benchmarks/VarkaZoneOperandBenchmark-jdk25-results.txt 2>/dev/null | wc -l`
prints 0.

**Steps.**

1. The lead dispatches the timed run of `VarkaZoneOperandBenchmark` on the AMD EPYC 7763
   runners at 256 and at 128 bits, and commits
   `sql/core/benchmarks/VarkaZoneOperandBenchmark-jdk25-results.txt` and its
   `-jdk25-128bit-results.txt` companion from the run's artifact. A ratio under 1.3 is run
   again and compared by minimums.
2. `sql/varka/skills/emitter-and-ir.md`, one `##` section: a value the runner must know
   before a kernel runs is ranged by rules over the columns' ranges; the property is
   soundness and a wide interval only costs speed; arithmetic that wraps on purpose (a
   remainder as a difference of a product) has an interval as wide as its inputs' span.
   Then `dev/varka_toc.py SKILLS.md --from sql/varka/skills`.
3. This plan's section 11 and the row's line in `PLAN.md`, which opens with `**Done**`: each
   prediction of section 7 scored, each scored time prediction added with
   `dev/varka_calibration.py --add-row`.

**Commands.** `python3 dev/varka_plan_lint.py --check --offline sql/varka/plans/m9/PLAN.md`,
`python3 dev/varka_quote_check.py sql/varka/plans/m9/VARKA-688.md` and `python3
dev/varka_push_check.py`: each exits 0. **Done when** both results files are committed and
every prediction has a score. **Not here:** a timed run on the shared laptop; a change to
main code; a number in a document that is not in a results file.

## 6. Tests, proofs, fuzzing and coverage

| Suite | Language, module | Holds | The failure only it catches |
|---|---|---|---|
| `VarkaOperandRangeSuite` (new) | Java, catalyst | each rule against the lattice; the shift and sign-mask rules over every count; the hulls and the empty state; the lane tests; the zone shift against `java.time`; the soundness property over random trees; the names of the nodes with no rule; no allocation | a range narrower than a live value, which is a wrong answer no fallback catches |
| `VarkaIntRangePassSuite` (new) | Java, catalyst | the int pass against a scalar loop, null patterns, lengths, the int extremes | a null row's storage read as a value |
| `VarkaBatchArgsSuite` (row 682, extended) | Java, catalyst | a step kernel over a computed operand; the plan's refusal | a lowering that compares the column |
| `VarkaBatchArgRunnerSuite` (row 682, extended) | Scala, sql/core | the operand's range reaches the fill: a sum, a hull, an empty column, an unknown range, two programs, poison nulls, the ladder, a date, the table | the column's range filled for the operand |
| `VarkaZoneCompilerSuite` (row 657, extended) | Java, catalyst | the compiler builds `ToLocal` over a tree with rules and declines by the node's name; the sum with a literal under a day; its declines | an entry that fuses and then declines every batch |
| `VarkaTimestampZoneSuite` (row 657, extended) | Java, sql/core | each kernel against Spark's row engine across each zone's changes, with rows whose sum crosses a change; a year declines no batch; ten years; the ends of a long | a kernel that agrees with Spark only where the operand crosses no change |
| `VarkaTimestampZoneQuerySuite` (row 657, extended) | Scala, sql/core | the session's path, its metrics and its `EXPLAIN` | a path only a session has |
| `VarkaZoneTableSuite` (row 686, extended) | Java, catalyst | the table over a computed operand: its scratch, two programs, null rows, its bytes | a gather from indices nobody wrote |
| `VarkaCoverageSuite`, `VarkaCoverageHarnessSuite`, `VarkaCoverageDifferentialSuite` | existing | the two new coverage rows, with and without a session | an entry that is fused and documented nowhere |

- **Proofs.** The row adds no lowering, so the house rule asks for no proof file. The rules
  for `ADD`, `SUB`, `MUL` and `IntNeg` are the lattice's, which `range_analysis.smt2` proves
  at the long lane (fact 5); test 1 ties each rule to the lattice at every pair of the edge
  set. Two rules are new, the shift right and the sign mask. Both are over small closed
  sets: test 2 runs every count against every interval, and test 3 the three cases of the
  mask through the one tree the compiler builds. Whether they also get claims in
  `range_analysis.smt2` is the owner's to decide (risk 9). That a zone's offset is within
  18 hours is `ZoneOffset`'s contract, held against the JVM's data by test 7 and, for each
  whole kernel, by `VarkaZoneSweepSuite` (row 684).
- **Fuzzing.** Test 8 is the row's own fuzzer, by seed, in every Build. `VarkaIrFuzzSuite`
  draws a zone node over a sum once part b's step 7 lands, against `VarkaReference`.
  `VarkaSparkFuzzSuite` composes coverage rows: from part c it draws the two new rows under
  each session zone row 684 gave it. A failure of any of them prints its seed.
- **Planted faults.** Nineteen across the parts, each with the test that must fail. They stand
  in for a mutation run where the suites are JUnit: `dev/varka_pit.sh` runs ScalaTest suites
  only (`VARKA-682.md` 3).
- **Mutation.** `dev/varka_pit.sh` reaches `VarkaBatchRanges.measure` and, through it,
  `VarkaOperandRange.range` from `VarkaBatchArgRunnerSuite`: part b's pull request lists or
  kills each surviving mutant of those two methods.
- **Coverage.** `dev/varka_gen_coverage.sh` after parts a, b and d: every line of
  `VarkaOperandRange` and of `IntRangeOps.leastAndGreatest` covered; an uncovered line is a
  missing test.
- **The sanitizer.** Parts b, d and e run the runner's suite with `-Dvarka.sanitizeMemory=
  true`; the matrix runs every suite so.
- **Unchanged suites.** After parts a, b and d no existing test changes but row 682's
  refusal test. Part c turns two of row 657's tests over (its steps 3 and 5), and part e
  one of part c's.

## 7. Measurement

`VarkaZoneOperandBenchmark` (parts c and d) runs on the AMD EPYC 7763 runners at 256 and at
128 bits; the laptop adds 512 when idle. Its groups, each over a cached `TIMESTAMP` column
under `America/Los_Angeles`, 4096 rows a batch:

- "a year, no order" and "a week": the cases "row path" (Varka off, the control),
  `hour(ts)` (the kernel's control: a column operand), `hour(ts + INTERVAL '1' HOUR)` and
  `hour(greatest(ts, ts2))`, each under the default options;
- "ten years, no order" (after part e): the same cases; A/B arms by name, `zoneForm=TABLE`
  against `zoneForm=TABLE_SCALAR`;
- "the range of an operand, a call": `VarkaOperandRange.range` alone for `ts + literal`, for
  `greatest(ts, ts2)` and for the floor division's tree;
- "the pass over 4096 rows" (part d): a long column, an int column, an int column with one
  row in ten null.

**Predictions, registered on 26 November 2026, before any run.**

| # | Prediction | Scored by |
|---|---|---|
| P1 | A call of `range` for `ts + literal` costs under 50 ns on the runner at either width: too little for a section of 4096-row batches to show | the group "the range of an operand, a call" |
| P2 | "a year, no order" at 256 bits: `hour(ts + INTERVAL '1' HOUR)` runs within a quarter of `hour(ts)`'s rate: one checked sum more, the same pass | part f, by minimums of two runs |
| P3 | The same section runs at least eight times the row path at 256 bits, the bar `VARKA-657.md` 6.1 registered for `hour(ts)`: the row path pays `java.time` twice here | part f |
| P4 | Over a year in no order no batch of `hour(ts + INTERVAL '1' HOUR)` declines, and over a week every batch runs `ONE`: yes | part c's suite; the section's `numZoneFormBatches` |
| P5 | `hour(greatest(ts, ts2))` costs under one nanosecond a row more than `hour(ts)` at 256 bits: a second pass and a blend | part f |
| P6 | The int pass costs at most six tenths of the long pass a row at 256 bits, dense: twice the lanes a vector | the group "the pass over 4096 rows" |
| P7 | "ten years, no order" under `TABLE` at 256 bits: the computed operand costs at most 15 percent more a row than `hour(ts)`. The probe's form beside the gather cost 0.871 ns a row against 0.829 at four lanes (`results/tables.txt`, `tables-2.txt`), on the laptop | part f |
| P8 | No existing shape's bytes move: after parts a, b, d and e no generated file differs; after part c only rows over `ltzy`: yes | each part's `git diff --exit-code` and `dev/varka_bytes_moved.py` |
| P9 | Six parts, none over 700 counted lines | `dev/varka_msg_check.py size` |
| P10 | Test time: `VarkaOperandRangeSuite` under 3 s; `VarkaIntRangePassSuite` under 2 s; the tests added to `VarkaBatchArgRunnerSuite` under 10 s together | the suites' reported times |
| P11 | Test 8 and the two fuzzers find no row outside its range between part a's merge and the row's close: yes | the Builds and the nightly's logs |

P10 was set after reading `python3 dev/varka_calibration.py`. Its nearest rows: a JUnit class
with no session took 0.15 s (VARKA-328); the calendar sweep took 1.5 s for eight tests over
20 million days each (VARKA-281); a `sql/core` suite of one-row cached views took 8.4 s
(VARKA-365). P10 is not doubled. P1 to P7 are not from a measurement of this code: none
exists. The pass's price they lean on is the probe's, 0.232 ns a row at four lanes on the
laptop (`sql/varka/spikes/zone-offset/results/forms.txt`).

## 8. Who builds it

Every part at full strength: `m9/PLAN.md` 1.4 has rows 656, 657 and 682 to 692 so.

| Part | Why |
|---|---|
| a | the rules are the row's soundness; a narrow rule is a wrong answer in every later row |
| b | the evaluator's per-batch path, where a stale or crossed range is a wrong answer |
| c | a new claim of the zone family, its declines and its generated files |
| d | vector code on the query path, beside row 683's |
| e | the emitter's table lowering, beside row 686's |
| f | the lead's: the dispatch, the scores, the close |

The row moves nothing of G5.

## 9. Risks

1. **The row's line names row 657 alone, and part b needs row 685's parts a to c.** If the
   owner wants this row before row 685, part b does not start: the two steps of 4.6 would
   have to go into row 682's fill and move again with row 685. The lead asks first.
2. **An unsound rule is a wrong answer** that no fallback catches. Held by test 8 over random
   trees, by test 1's tie to the proven lattice, by the planted faults that narrow each rule,
   and by the Spark-level tests whose rows cross a change only after the operand is computed.
3. **An interval loses what two operands share.** `t - floorDiv(t, D) * D` is in `[0, D)`,
   and these rules answer an interval as wide as the batch's span. Nothing this row fuses
   holds that shape under a zone node; row 671 adds a rule for it (4.5). A wide interval
   costs a dearer form or a decline, never an answer.
4. **Eighteen hours each side of a nested zone node** can count a change the values do not
   reach: one rung more, or at the ladder's top a decline. Row 691 reads how often; a rule
   that asks the zone's data for the offsets in the range would be exact, and is not built.
5. **The plan ages before it is built.** Rows 682, 683, 685, 686, 657 and 684 are plans, and
   rows 687 and 690 have none. Each B0 greps their names; a premise that fails stops the
   part. If row 687 merges first, the pass is no longer `measure`'s one call (5.2's B0); if
   row 690's records merge first, part a adds their arm (5.1, step 2).
6. **The int pass's cost is not known.** P6 scores it. If "an int column" costs more than
   "a long column" a row at 256 bits, part d's pull request says so and the lead decides
   before row 690 leans on it.
7. **Part e's scratch is the map's slack.** It is right only while an int vector is at most
   `INDEX_SLACK` ints, while no other node's code runs between the store and the two
   gathers, and while no live lane of another program reads the slack. The guard of 5.5's
   step 1 holds the first, the test of two programs in both orders the other two.
8. **Row 671 claims the same expression.** Part c's arm is the smallest form the done-when
   needs. Row 671 widens `zonedPlusInterval` and retires `ZONED_INTERVAL_REASON`; part c's
   B0 stops if row 671 started first.
9. **Two new rules have no SMT claim.** The shift and the sign mask are held by tests over
   every count and every case, not by `range_analysis.smt2`. The row adds no lowering, so
   the house rule does not ask for a file; the owner may ask for the claims all the same.
10. **Six pull requests where the family plan had two.** The int pass, the table and the
    row's own SQL entry were not in the family's count; each part is under the size rule.
11. **The census mark of part c leans on row 654's file,** which is not built: B0 prints the
    entry, and with none the cell stays with rows 654 and 671.

## 10. The house rules that bind this row

From `sql/varka/AGENTS.md` and `BUILD_SHEETS.md`, the rules a builder here is likeliest to
break:

- "No allocation, streams, lambdas or boxing on a path that runs per batch or per row".
  `VarkaOperandRange.range` and `VarkaBatchRanges.measure` run for every batch. A `Range`
  object, `Math.addExact` and an `OptionalLong` are each a breach. `compile` runs once a task
  and may use them.
- "A switch over a sealed type or an enum has no `default`". The walk names every record,
  and the op loop names every `Op`.
- "Anything that can return a *wrong* answer rather than fail ... gets its own differential
  coverage, because the fallback cannot catch it." A range is such a thing.
- "A new catalyst suite whose helpers are Java is a JUnit 5 class under `@VarkaSuite`", each
  `@Test` with its sentence as a `@DisplayName`. A `sql/core` session suite stays ScalaTest.
- "A test starts from `VarkaMatrix.base`, not `VarkaEmitOptions.DEFAULTS`"; a test whose
  subject is a form sets that form.
- "A comment describes the code as it is." No `TODO`, no history, no "until part e" in a
  comment: the plan holds that.
- "A pull request stays under 1000 changed lines". Generated files are never edited by hand.
- Source lines within 100 columns; ASCII outside string literals; a new file has the license
  header.

Do not: change an arm of `VarkaRangeAnalysis` or an operation of `VarkaValueRange`; widen a
rule's answer to make a test pass without saying why it is still sound; answer `EMPTY` for a
case the rules do not prove empty; add a status, a metric or an emit option; read a zone in
`zonedPlusInterval`; build `ToInstant`; time anything on the shared laptop; push, open a
pull request or dispatch a workflow.

## 11. Outcome

To be written when the row closes.
