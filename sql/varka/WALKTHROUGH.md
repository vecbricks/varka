# One expression, end to end

`hour(t)` over a cached `TIME` column, from the SQL text to the instructions
the CPU runs, with the tool that shows each step. The README's "Reading the
source" lists the nine files a query passes through; this page walks one
expression through them so each file is seen doing its one job.

    SELECT hour(t) FROM times

`t` is a `TIME(6)` column in Spark's Arrow cache: a 64-bit integer per row,
nanoseconds since midnight. Stock Spark evaluates `hour` one row at a time,
through `DateTimeUtils.getHoursOfTime`, which builds a `java.time.LocalTime`
per row and reads its hour. Varka turns the same expression into one loop
that divides eight rows at once.

## 1. Plan time: the rule decides

`VarkaColumnarRule` (`sql/core`) rewrites the physical plan. Above a columnar
source it finds this projection and asks the compiler whether the entry
`hour(t)` can be fused. If it can, the projection becomes a `Varka*Exec` node
whose `numVarkaBatches` metric later counts the batches the kernel served;
anything the compiler declines stays with stock Spark, row by row, and stays
correct. `EXPLAIN` shows the node, and `docs/sql-varka.md` documents which
expressions fuse and why the others decline.

## 2. Compile time: from Catalyst's tree to Varka's IR

`VarkaExpressionCompiler` (`sql/catalyst`) is the only file that knows
Spark's expression classes. By the time it sees `hour(t)` the analyzer has
turned it into a `StaticInvoke` of `DateTimeUtils.getHoursOfTime` over the
column, so the compiler's arm is keyed on that method name:

```scala
case ("getHoursOfTime", Seq(time)) =>
  narrowed(time)(t => new ConstDivide(t, nanosPerTimeUnit("HOUR")))
```

Two decisions are in that line. The hour of a time is its nanoseconds
divided by 3600000000000, so the value is a `ConstDivide` over the column in
the 64-bit lane. And Spark types the result `IntegerType`, so the kernel must
deliver a four-byte column from an eight-byte computation: `narrowed` wraps
the division in a `NarrowLane`, a node the emitter admits only at an output
root, where the loop keeps running at the long species and narrows once, at
the store. Nothing is guarded: every dividend is under a day of nanoseconds
and every quotient under 24, so the division cannot overflow and the
narrowing cannot truncate. The compiler's comment above the arm says so, and
a reader who wants the reasoning finds it in `PLAN_TASK_102.md` 8.3.

`dev/varka_emit.sh` prints what the compiler produced:

    $ dev/varka_emit.sh "hour(t)" --columns "t:time(6)"
    entry  0  hour(t)                                   FusedOutput(0)
    inputs (child ordinals, in kernel order): 0
    output 0 IR: (narrow (divc:3600000000000 col:0:long))
    shape hash: 77c00bd3f4c58b17  options: (defaults)

That one line of IR is the whole expression. `VarkaVectorIR` is a sealed
interface of small records - a column reference, a constant division, a
narrowing - and it carries no literal *values*: a folded literal becomes a
slot index, so the emitted class depends on the shape alone. The shape hash
is the key of the shape cache, and it is why the second query with this shape
in a JVM emits nothing and runs the class the first one built.

## 3. Emit time: the IR becomes a class

`VarkaLoopEmitter` walks the IR and assembles a JVM class with the JDK 25
Class-File API: no Java source, no Janino. The same dump lists the methods
the class got, with their bytecode size and how many Vector API lane
operations each one runs:

    method              bytes IntVector LongVector DoubleVector convert VectorMask validity lines
    epilogueDense0        209         1          1            1       3          0        0     3
    epilogueMasked0       209         1          1            1       3          0        0     3
    loopDense0            194         1          1            1       3          0        0     3
    loopMasked0           194         1          1            1       3          0        0     3
    run                    42         0          0            0       0          0        0     0
    runDense              137         0          0            0       0          0        1     0
    runMasked             204         0          0            0       0          0        1     0

`run` reads the batch's validity and picks a path: `runDense` when no input
has nulls in this batch, `runMasked` when one does, so a batch without nulls
never pays for a mask. Each path is a loop over full lane groups
(`loopDense0`) and an epilogue for the rows left at the end. The three
`convert` operations per method are the division: the lane has no 64-bit
integer divide, so a constant division goes through double lanes - long to
double, divide, double back to long - which is exact for every dividend
under 2^53, and a day of nanoseconds is far under it. The intermediate values
stay on the JVM operand stack, which is how they stay in vector registers.

The emitted class implements `VarkaFusedKernel`, the seam between the
generated bytecode and ordinary Java, and `sql/varka/emitted_bytes.json` pins
the bytes of this class for every documented shape, so a refactor that should
not change the generated code is checked by that file not moving.

## 4. Run time: what C2 makes of the loop

The class is bytecode; HotSpot's C2 compiles it. With `--asm` the dump runs
the kernel hot and prints the standard C2 compilation of `loopDense0`. On
this laptop (AVX-512, `MaxVectorSize=64`) the hot loop is eight rows an
iteration:

    vmovdqu32   (%rdi), %zmm0          ; load eight longs: nanoseconds of day
    vcvtqq2pd   %zmm0, %zmm0           ; long -> double
    vdivpd      %zmm1, %zmm0, %zmm0    ; / 3600000000000 (broadcast once, before the loop)
    vcvttpd2qq  %zmm0, %zmm2           ; double -> long, truncating
    vpcmpeqq    %zmm2, %zmm4, %k5      ; the JVM's (long) cast contract: NaN and
    kortestw    %k5, %k5               ;   out-of-range lanes fixed up only if any
    je          <no fix-up needed>     ;   lane hit them, which none does here
    vpmovqd     %zmm2, %ymm0           ; eight longs -> eight ints: the NarrowLane
    vmovdqu32   %zmm0, (%rsi) {%k7}    ; store eight ints under the low-half mask
    testl       %eax, (%r13)           ; safepoint poll
    cmpl        $8, %edx
    jle         <next group>

Every instruction is a packed one on a `zmm` register, no lane is boxed, and
the divide is the double-lane route the emitter chose. `VarkaAssemblySuite`
asserts exactly this in CI: that the emitted loops and the hand-written
kernels compile to packed instructions on vector registers of the width the
host reports, and that the shapes which must not allocate do not. The masked
store at the end is the narrowing store as shipped; task 156 measured a
cheaper half-species form for it and task 162 ships it.

Run the same dump with `--width=16` and the loop is two rows an iteration on
`xmm` registers, which is how a 128-bit machine's kernel is read on a 512-bit
one; `sql/varka/width_audit.json` is that census for every documented shape.

## 5. Where it is measured

`VarkaTimeBenchmark` (`sql/catalyst`) times this kernel against the
hand-written kernels and against alternative lowerings, at three vector
widths, and its results are committed under `sql/catalyst/benchmarks/`. The
`TIME` surface benchmark (`sql/varka/bench`) times the whole query against
stock Spark, through both consumers. The README's benchmark section says how
those numbers are produced and reproduced, and `dev/varka_quote_check.py`
holds every number a document quotes to one of those committed files.

## What to read next

The nine files in the README's "Reading the source", in order. Then
`PLAN_TASK_102.md`, the plan that built the `TIME` extracts, for how a
lowering is chosen, predicted and measured; and `SKILLS.md` for the lessons
the measurements left behind, the long-lane division above among them.
