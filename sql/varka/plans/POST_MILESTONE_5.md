# Eight rows per instruction: Spark's `TIME` type on Varka's 64-bit lane

*The long read that closes milestone 5, drafted 22 September 2026 and finished
with the milestone's last numbers (`PLAN_TASK_118.md` section 4 C). Every
figure is a script under `figures/`; every number is from a committed results
file under `sql/varka/bench/benchmarks/`. The LinkedIn post is its trailer and
sits at the end of this file.*

---

Spark 4.1 gave SQL a `TIME` type: a time of day with no date attached, stored as
nanoseconds since midnight in a 64-bit integer, with the usual functions over
it - `hour`, `minute`, `second`, `time_trunc`, arithmetic with intervals,
differences between two times. It arrived behind a flag and is on its way to
being on by default.

On the development laptop - an AMD Ryzen AI 9 HX PRO 370, a Zen 5 core that
runs 512-bit instructions on a 256-bit datapath, OpenJDK 25, one core - stock
Spark 4.2 computes `hour(t)` over a cached column at 61.3 million rows a
second. Varka, a research fork of Spark that compiles a projection into one
vector loop, computes the same `hour(t)` on the same core at 1041.4 million rows
a second: about seventeen times faster. The numbers that need a genuine 512-bit
datapath are measured on one, a GitHub-hosted runner with an AMD EPYC 9V45,
through a workflow anyone can dispatch; section 8 says which numbers those
are. Every figure in this post comes from a committed results file that names
its machine, JDK, row count and measured vector width, so each one can be
checked without rerunning anything.
This post is about where those seventeen come from. Not from a faster
algorithm - the algorithm is a division - but from what the loop around it
looks like, what it reads, and what it does not do per row.

The ideas come first and the numbers last, because the numbers only mean
something once you can see the loop. If you already know how Spark's codegen
works, section 3 is where Varka starts.

## 1. What Spark's codegen produces

Whole-stage code generation was the right idea in 2015 and it still is: instead
of interpreting an expression tree per row, Spark writes the Java for a
pipeline of operators, compiles it with Janino, and runs a tight loop that the
JIT can inline through. It removed a virtual call per operator per row and won
a lot.

What it produces is still a row loop. The generated code takes one row, applies
every expression to it, writes one output row, and goes round again. Every
column value is read out of an `UnsafeRow`, every intermediate is a local, and
anything that needs an object - a `LocalTime`, a `Decimal`, a `UTF8String` -
gets a new one per row.

![Stock Spark's row loop against Varka's one loop over the column](figures/svg/fig1-row-loop-vs-vector-loop.svg)

*Figure 1. Left: the generated Java takes rows one at a time and uses one lane
of a core that has eight 64-bit lanes to offer. Right: Varka's emitted loop
takes eight values of the column per instruction.*

The core underneath has moved on. A server core today does 512 bits of integer
work per instruction: eight 64-bit values, sixteen 32-bit ones. A row loop uses
one lane of that and leaves the other seven idle, and no amount of JIT
cleverness recovers them, because the loop's shape - one row, many columns,
then the next row - is the wrong way round for the hardware. The vector unit
wants one column, many rows.

## 2. What `hour(t)` costs

Take the smallest example. A `TIME` is a long: nanoseconds since midnight. The
hour is that long divided by 3 600 000 000 000. Here is how stock Spark gets
there, from `DateTimeUtils`:

```scala
def getHoursOfTime(nanos: Long): Int = LocalTime.ofNanoOfDay(nanos).getHour
```

![What hour(t) costs stock Spark against what it costs the lane](figures/svg/fig2-localtime-per-row.svg)

*Figure 2. Stock Spark builds a `LocalTime` - four fields, one allocation - to
read one of the fields back. The lane divides.*

That is a perfectly reasonable line of Scala, and it is what a row engine
almost has to write: `java.time` is the correct library and `getHour` is the
correct method. The cost is that `LocalTime.ofNanoOfDay` does three divisions
to fill four fields, allocates an object to hold them, and the caller reads one
field and drops the object. Escape analysis sometimes removes the allocation
and sometimes does not, and the three divisions stay either way. Measured over
five hundred million cached rows, `hour(t)` costs stock Spark 16.3 nanoseconds a
row on this machine, and `minute(t)` and `second(t)` 16.4 each, because they
are the same object built for a different field.

Varka's lane does one 64-bit division and stores the quotient. It costs
1.0 nanoseconds a row, and most of that is the memory traffic - an eight-byte
read and a four-byte write per row - rather than the division.

## 3. A batch is columns, not rows

The first thing Varka changes is what the loop reads. Spark already carries
columns in the JVM - a cached table lives in `ColumnarBatch`es - and Varka adds
a cache serializer that writes those batches as Arrow. That is where the
kernels get their columns today, and it is how every benchmark in this post is
set up: the table is cached, so the measurement times the loop rather than a
scan. An Arrow column is two buffers, the values back to back and a validity
bitmap with one bit per row, and nothing else.

The cache is the first source, not the only one it can have. A columnar
datasource that hands Arrow batches straight out of a file - Parquet is the
obvious one, since its pages are already columns - would feed the same kernels
with no cache step in front, and that is the natural next reader for this
engine. Nothing below depends on where the batch came from; it depends only on
the batch being Arrow.

![One Arrow batch of six columns, and the row it is not](figures/svg/fig3-batch-is-columns.svg)

*Figure 3. The `varka_times` table the benchmarks use: two `TIME` columns, two
day-time intervals, two `bigint`s, eight bytes a row each, plus a bit per row
for nulls. The loop reads the buffers where Arrow allocated them.*

Varka maps each buffer to a Panama `MemorySegment` over the address Arrow
already owns - no copy, no `ByteBuffer`, no object per value - and the emitted
loop loads eight lanes from it at a time with the Vector API. The validity
bitmap is loaded as bits and becomes a `VectorMask`, so a null is a lane that
is masked out rather than a branch.

This is the point where a native engine - Velox, DataFusion, Comet, Gluten -
crosses into C++ over JNI, and gets a very good vector engine on the other
side. Varka stays in the JVM on purpose: one jar for every CPU, one memory and
crash domain, ordinary profilers and stack traces, and eventually a user's own
vectorised function fused into the same loop as the built-ins instead of rows
handed back across a boundary. Whether the JVM can do the vector part well
enough is the experiment, and it is what the numbers at the end are for.

## 4. One class per projection, and a trapdoor

The second change is what the loop *is*. Varka does not interpret a vector
expression tree, and it does not call a library of kernels either - a call per
operator per batch is cheap, but the calls are megamorphic and the JIT cannot
see across them. Instead the compiler turns the Catalyst expressions of a
projection into a small vector IR, and an emitter writes a Java class for that
IR with JDK 25's Class-File API: one class per projection shape.

Inside that class the loop is not always a single method. Outputs that share
work stay together, and outputs that share nothing are split into sibling
methods, because C2's compile time grows steeply with the number of vector
operations in a method and a wide method is both slower to compile and slower
to run. Sibling methods, not longer methods, is the rule the emitter budgets
against.

![From the plan node to an emitted class, and the trapdoor under the kernel](figures/svg/fig4-one-class-per-projection.svg)

*Figure 4. Above: a projection becomes an IR, the IR becomes bytecode, the JIT
sees one loop with monomorphic call sites. Below: batch by batch at run time,
with the trapdoor to the row engine under the kernel.*

Two consequences are worth knowing about. The class is named after its plan
node - its `SourceFile` attribute reads like `Project#12` - and carries a custom
attribute with the IR it computes, so a profiler, a flame graph or a heap dump
names the query's operator with no mapping table. And the class is loaded by
the task that needs it and unloaded with it, so a long-running executor does
not accumulate a codegen cache that only grows.

The trapdoor is the contract that makes the rest possible. Anything the
compiler cannot lower declines *per expression* at plan time, and the stock
row path computes that column while the fusable ones still fuse. Anything the
emitted kernel cannot compute for a *batch* - a `TIME` plus an interval that
would cross midnight, which is an error in Spark, or any failure at all in the
kernel - is refused, and the row engine computes that batch instead. A query on
Varka can be slower than it should be; it cannot be wrong or fail because of
Varka. That rule is what lets the engine grow one expression family at a time
in front of users rather than behind them.

## 5. The 64-bit lane, and why a division is the expensive thing

`TIME` is the third type on Varka's 64-bit lane, after `bigint` and day-time
intervals, and it is the one that made the lane interesting, because nearly
everything you do to a time of day is a division by a constant: the hour is a
division by 3.6e12, the minute by 6e10 and 60, `time_trunc('MINUTE', t)` a
division and a multiplication, `time_diff` a subtraction and a division. On the
32-bit lane a division by a constant is a multiply-high and a shift - a few
cheap instructions. On the 64-bit lane there is no multiply-high in the Vector
API, and a 64-bit product overflows anyway, so the lane goes through doubles.

![The two lowerings of a 64-bit division by a constant](figures/svg/fig5-two-division-forms.svg)

*Figure 5. With AVX-512 the machine converts eight longs to eight doubles in
one instruction, divides, and converts back: three vector operations. With AVX2
there is no such conversion, so the lane reads the bits of the long as a double
through an identity and back again: fourteen.*

A double holds every integer below 2^53 exactly, so this form's quotient is
exact for every dividend below 2^53 - and a day is 8.64e13 nanoseconds, under
2^47, with room to spare. So the
conversion form is exact, and on a machine with AVX-512 it is a convert, a
divide and a convert - three vector operations, because a 64-bit lane and a
double lane are the same width, so there are no halves to split and rejoin the
way the 32-bit lane has to.

Most laptops and a good share of cloud machines have AVX2 and not AVX-512, and
AVX2 has no instruction that turns a vector of longs into a vector of doubles.
HotSpot then compiles the conversion as eight scalar conversions, which is
worse than not vectorising. For those machines Varka emits a second form
built on a bit trick: for `0 <= u < 2^52`, the bit pattern of `u` OR'd with
`0x4330000000000000` *read as a double* is exactly `2^52 + u`, so a subtraction
recovers `u` as a double with no conversion instruction at all, and the same
identity run backwards turns the quotient into a long. The floor that Java's
truncating division needs is built by hand - the Vector API has no lanewise
floor either - and the sign is handled by dividing the magnitude and negating
the lanes that need it afterwards. Fourteen operations against three, all of
them vector operations, all of them instructions AVX2 has.

Both forms are exact for every dividend under 2^52, and the emitter carries
that bound as an obligation on every 64-bit division it compiles, checks it
against what it knows of the dividend, and a test drives both forms through the
JVM at the bound to confirm that each fails exactly the way its arithmetic says
it should above it. The lowering is chosen per machine, at emission, from what
the JVM reports it can do, and the shape of the compiled kernel is part of the
cache key, so a jar moved between machines recompiles rather than misbehaves.

## 6. One loop for the whole projection

The third change is the one that compounds. A projection usually has several
outputs, and they usually share work: `hour(t + dt)` and
`time_trunc('MINUTE', t + dt)` both need `t + dt`, and both need it guarded
against leaving the day. Spark's codegen already spots that: subexpression elimination is on by
default, and the generated row loop computes the sum once per row into a local.
A kernel-library engine has a harder time of it - each operator is a call over
a whole batch, so the shared value is computed once per batch but materialised
into memory and read back. Varka compiles the projection as one graph, finds
the shared subtrees, and emits a loop in which the shared value is computed
once per eight rows and stays in a register between its two uses. The saving
is not that the work happens once; it is that it happens once for eight rows
and never leaves the register file.

![Two outputs sharing a subtree, and the loop they become](figures/svg/fig6-fusion-shared-subtree.svg)

*Figure 6. Left: the expression graph of two outputs over the same guarded
sum. Right: the loop the emitter writes for it - one load per input column,
the shared sum once, one store per output.*

This is where the row engine's overhead stops being amortised and starts being
multiplied: every link of a chain costs stock Spark another virtual call, another
boxed intermediate or another object, while the fused loop pays one load per
input column and one store per output whatever the depth. It is also why a
single-expression benchmark understates the engine. The `TIME` surface below
measures one call per row and reads 13x to 38x; the chains, which compose the
calls, are where the fused loop shows what it is worth, and they are the number
the closing section is built around.

## 7. How it is measured

A "17x" is worth exactly as much as the method behind it, so briefly: what was
compared, on what, and what would have made a run invalid.

![The four arms every table compares](figures/svg/fig7-four-arms.svg)

*Figure 7. Two stock releases on two JDKs, the fork with the engine off, and
the fork with it on, over the same cached rows, timed by one benchmark jar that
depends on no part of the fork.*

**Four arms.** Every table compares stock Spark 4.2.0 on JDK 17 and on JDK 25,
this fork with the engine off, and this fork with Varka on. The engine-off arm
is the control that separates the engine from the fork: the fork tracks Spark
master, and on `t + dt` master's own row path already runs at 52.3 million
rows a second where 4.2.0 runs at 33.5. So every ratio is against stock, and
where the two baselines disagree by more than a fifth the ratio against the
fork's own row engine is printed beside it. Runs are one core over cached
Arrow rows, with at least five iterations over two-second windows, recording
wall time and executor time separately.

**Two tables, two machines, and the reason is arithmetic.** The *chains* -
composed expressions, several operations deep - are measured on GitHub Actions,
on a runner whose vector datapath the measuring job itself proves is 512 bits
wide. They are the numbers this post leads with, and anyone can reproduce them
by dispatching the same workflow. The *surface* - one row per expression, the
coverage document - stays on the development laptop and is labelled as such,
because it cannot be measured honestly on a runner at all: its lightest entries
run at 0.8 nanoseconds a row, a cloud runner's per-iteration constant is about
36 milliseconds, and keeping that constant under 5% means the work has to last
at least 720 milliseconds - some nine hundred million rows, which at this
table's 49 bytes a row is about 41 GiB of cache on a machine with 15 GiB of
memory. A surface row therefore says *what* fuses and roughly what it
is worth on a developer's machine; a chain row is the claim.

![The gates a run passes before it is quoted](figures/svg/fig8-the-gates.svg)

*Figure 8. Six guards between a run and a committed file, each one the memory
of a number that was wrong once.*

**Six gates.** On a development machine a run is refused unless the *canary* -
three fixed loops - reads within a few percent of that host's committed
baseline, which is how a run knows the machine is in its measured state. It
cannot run on a cloud runner, whose hostname is new every dispatch and which
therefore has no baseline to compare against, so the runner files record
`canary: OFF` and a reader can see exactly which of these guards was in force.
The rest apply everywhere. The *datapath probe* records how wide the vector unit
really is (the 512:256 ratio reads 1.14 on this laptop, 1.35 on the Intel Xeons
of the CI pool and 2.01 on the EPYC 9V45, and only the last is full width);
and the table stayed *resident*, because one that spills recomputes every
iteration and passes every other rule with a better number. A run fails if
any entry expected to fuse planned without a Varka node or if a single batch
fell through the trapdoor, since a blend of kernel and row-engine batches
looks like a kernel rate; and it fails the *fixed-share rule* if the job's
constant - about 15 ms on the laptop, 36 ms on a runner - exceeds 5% of any
Varka row's wall time (one row sits at 5.9%, and the bound was lifted to 6% for
it, in writing). Finally the *band*: twelve repeated runs give each case a
tier, and no row is quoted without one. After all that, a check that every
number with decimals in the documents traces to a committed file, or the build
fails.

![Where the full-width numbers come from](figures/svg/fig9-the-full-width-runner.svg)

*Figure 9. The GitHub-hosted pool as eighteen dispatches saw it, and the
workflow that measures on the one machine in it whose datapath is genuinely
512 bits wide - proven by the measuring job itself, and written into the file.*

**Where the 512-bit numbers come from.** Not from this laptop, whose Zen 5
core double-pumps. The chains are measured by a GitHub Actions workflow,
`varka-surface-benchmark.yml`, that anyone can dispatch: a `build` job caches
the jars for the commit on whatever runner it gets, and a `measure` job starts
by running the datapath probe *on its own VM* - a probe in any other job would
be describing a different machine, which is a mistake the previous milestone
made once - and stops within a minute unless the ratio is near 2. Across the
pool that is one dispatch in about eighteen, an AMD EPYC 9V45; the Intel Xeons
carry every AVX-512 flag and still read 1.35, because they issue 512-bit
integer operations on fewer ports. A hit runs the four arms in about an hour
and commits a file whose header carries the CPU and the probe's reading from
the same VM, so the claim and its evidence cannot come apart. Milestone 4's
date chains were measured this way - **9.4x to 14.2x against stock, median
10.2x** - and the `TIME` chains of section 8 will be too.

## 8. The numbers

**The headline is the chains, on the machine that earns the word full-width.**
Twelve chained `TIME` expressions, three to five operations deep, over a
hundred million cached rows on one core of an AMD EPYC 9V45 whose measuring job
proved its own datapath at 1.97. Varka runs them at **3.7 to 5.5 nanoseconds a
row** - 182 to 273 million rows a second - against stock Spark 4.2.0's 88 to
197 nanoseconds a row on the same machine and the same rows.

![What a chained TIME expression costs per row](figures/svg/fig10-the-chains.svg)

*Figure 10. The median of the twelve, on the full-width machine and on one
without AVX-512. The scale differs between the panels; the machines are 1.8x
apart and Varka is 14.6x.*

| | against stock 4.2.0 (JDK 25) | against the fork, engine off |
|---|--:|--:|
| twelve `TIME` chains, wall time | 19.6x - 46.9x, median **31.8x** | 17.5x - 36.4x, median 25.9x |
| the same, executor time | 21.0x - 53.2x, median **34.9x** | - |

Read the executor column first. The job's fixed cost - scheduling a task,
collecting its result - is about 36 milliseconds on a cloud runner, and Varka
does only 0.42 seconds of work per iteration at this row count, so 7.2% to
10.5% of the wall time is the harness. That is over this project's own 5% ceiling, and
the bound was lifted to 10% for this benchmark deliberately, because no row
count both fits a runner's 15 GiB and satisfies 5%: this table is 49 bytes a
row against the date table's 25, and the benchmark has simply been outrun by
the engine it exists to measure.
The direction matters and is the reason it is publishable: the constant
inflates *every* arm alike, so it drags the ratio down. The wall figures above
are the conservative ones.

**What the width is worth, and what the lowering is worth.** The same twelve
entries ran on an AMD EPYC 7763 - Zen 3, `avx avx2` only - at the same row
count and commit. Between the two machines the three scalar arms gain 1.64x,
1.76x and 1.80x: that is the machine generation, and it agrees with what
milestone 4 measured on the date chains. Varka gains **14.6x**. Divide the
machine out and **8.3x is the kernel's**, which decomposes into two parts that
multiply: the lane count doubles, four 64-bit lanes to eight, and the division
lowering changes, because a machine without AVX-512 falls back to the
fourteen-operation magic form of section 5 where one with it emits the
three-operation conversion form. Two times four-point-seven is 9.4 against 8.3
measured.

So on this workload **the lowering matters more than the width** - and that is
the opposite of what the same experiment said about dates, where the width was
worth 1.14x once the machine was divided out. The two are consistent once you
look at what each kernel is made of. A date chain is a dependency chain of
cheap operations and waits on latency; a `TIME` chain *is* its divisions, and
what changes between the machines is how many instructions a division takes.
Neither number generalises to the other's shape, and a post that quoted one as
"what SIMD is worth" would be wrong twice.

**The single calls, which are the coverage table and not the headline.** On the
development laptop - 256-bit datapath, so not a full-width machine - the
fifteen `TIME` projections of the surface read 13.4x to 38.2x against stock,
with `hour(t)` at 1041.4 against 61.3 million rows a second. Those larger
ratios are mostly stock Spark's per-row machinery rather than Varka's
arithmetic, which is why the chains are the number to carry away: the deeper
the expression, the more of stock's overhead is amortised and the more of the
ratio is real work. And one entry is worth quoting against the fork's own row
engine rather than against 4.2.0: `t + dt` reads 52.3 million rows a second on
master's row path against 4.2.0's 33.5, because upstream Spark improved it
between the two releases. That difference is upstream's, not this engine's.

**What still declines**, read from `coverage.json` rather than from memory.
`bigint` arithmetic is not built, so `l + l2` falls back and a `bigint` column
enters a fused chain only through a comparison, `greatest`, `least` or a
conditional (task 104). Interval arithmetic is not built either, so
`time_diff(..) + time_diff(..)` declines and `t - dt` declines with it, since
Spark resolves that to `t + (-dt)` and the negation is not lowered (task 103).
An extract narrows to an int only at an output root, so `hour(t)` may end a
chain but `hour(t) + 1` does not fuse (task 28). And two decimal-valued
functions decline by type: `second` with a fraction, and `time_to_seconds`.
Everything else in the table fuses, each row proved by a differential test
against the row engine, and anything that declines runs on stock Spark's path
and returns the same answer.

**Run it yourself.** None of this is worth much if you have to take it on
trust, so every part of the measurement is in the repository and the README's
[reproducing it](https://github.com/vecbricks/varka#reproducing-it) section is
the recipe. The benchmark is a standalone jar that runs on any Spark 4.x
distribution and depends on no part of the fork, so the same jar measures the
fork and the releases it is compared against; the expression lists are data
you can read in a minute
([`TimeChains.java`](https://github.com/vecbricks/varka/blob/master/sql/varka/bench/src/main/java/org/apache/spark/sql/varka/bench/TimeChains.java)
is the twelve chains above, each with the emitter op count that earns it its
place); and every number quoted here is a line in a
[committed results file](https://github.com/vecbricks/varka/tree/master/sql/varka/bench/benchmarks)
that carries the CPU, the JDK, the row count, the cache residency and the
datapath probe's own reading.

The 512-bit figures need a machine most people do not have, so the workflow
that produced them runs on GitHub-hosted runners and anyone with a fork can
dispatch it - the README gives the command and says what to expect, including
that about one runner in eighteen qualifies and that a dispatch landing on
anything narrower aborts in a minute rather than quietly measuring the wrong
machine.
