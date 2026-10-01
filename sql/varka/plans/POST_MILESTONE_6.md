# Under 8000 bytes by construction

---

The first post in this pair,
[The 8000-byte cliff in Spark SQL](https://vecbricks.github.io/the-8000-byte-cliff/),
was about a step. A query gets several times slower when a projection, a
`CASE WHEN` or a filter grows past the point where one generated method passes
8000 bytes of bytecode, because HotSpot never JIT-compiles a method that long.
That post was for people who run Spark. This one is for people who read and
change its code generator: why the generator cannot keep a method under the
line, and how one that emits bytecode does.

[Varka](https://github.com/vecbricks/varka) is a research fork of Spark that
compiles a projection or a filter over Arrow batches into one vector loop,
written with the JDK's Vector API. It builds that class with the Class-File API
rather than as Java source, reads the length of every method from the class it
built, and holds each one to 8000 bytes before anything runs. A group of outputs
whose method is over the line is split and the class built again; an output that
cannot fit on its own is declined at plan time, with a reason `EXPLAIN` prints,
and stays on Spark's own path. So no method Varka emits is past the limit, and
there is no method-size fallback to take. That is the claim of this post: not a
speed-up, but a property Spark's generator cannot have.

Two facts bound how much it matters, and they come first. The cliff is rare in
Spark's own queries: of 350,384 methods Spark generates for its golden-file
suite and its TPC-DS, TPC-H and SSB queries, one is past 8000 bytes, in the one
TPC-DS query Spark's suite already exempts from its size check. And it is common
in the shapes people write: a projection of date arithmetic crosses between 52
and 54 entries, and a filter of date ranges somewhere between 49 ranges and a
hundred. A third fact bounds the comparison: tuned, Spark gets most of the way
off the cliff, and section 4 puts the tuned numbers beside Varka's. Every timing
before section 7 is steady state; section 7 prices the first query of a new
shape, which costs more.

![The same hundred expressions as one method of Spark's and as one class of Varka's](figures/svg/fig21-one-method-or-a-class.svg)

*Figure 1. A hundred entries of the ladder of section 4. Spark puts them in
one method of 17,132 bytes that every row runs through, past the line, and the
JIT never compiles it.
Varka emits one class: 25 groups of four entries, each with four methods of
3,128 to 3,856 bytes, and two drivers of 1,120 bytes that call them.*

## 1. Why a generator of source cannot know

The JVM puts four limits on a generated class, and Spark meets each in its own
way.

* **65535 bytes of code in one method**, the class-file format's cap. Spark
  learns of it when the compile fails, and runs the stage's operators without
  whole-stage codegen. At the thirty or so places that call a generator
  directly, such as the lazily generated ordering of a top-k sort, there is no
  fallback and the query fails.
* **8000 bytes**, HotSpot's `HugeMethodLimit`. Past it a method is never
  compiled at any tier. Spark logs it at INFO and runs it.
* **65535 constant-pool entries** a class. `EXPLAIN CODEGEN` prints the pool's
  size, and nothing compares it with anything; a class past it fails to compile
  like a long method.
* **255 parameter slots** a method, `this` included. Guarded where Spark splits
  a function out, and elsewhere an ordinary compile error.

Each is a limit on bytecode, and Spark writes Java. Its splitter counts
characters, `spark.sql.codegen.methodSplitThreshold`, 1024 by default, and the
setting's documentation is frank about why: "We cannot know how many bytecode
will be generated, so use the code length as metric." On a projection of forty
`x + k` outside a stage the ratio comes to about seven characters of source to
a byte: the default makes methods of 235 bytes, a threshold of 8000 characters
makes methods of 1,435, and the forty unsplit are one method of 3,208. The
heuristic errs far on the safe side, as its documentation says it means to.
What it never does is measure the result, and inside a stage it does not run at
all: `splitExpressionsWithCurrentInputs` concatenates every expression's code
into one method whenever `ctx.currentVars` is set, which is the whole-stage
case, at all 26 of its call sites, and the row writer of a projection stays
unsplit there for the same reason.

Two more findings from the same reading, each pinned by a test.

**The check that measures bytecode cannot fire.** After Janino compiles a
stage, Spark parses the class file and reads every method's length, and
`WholeStageCodegenExec` compares the largest with
`spark.sql.codegen.hugeMethodLimit`, running the stage's operators separately
if it is over. The setting's default is 65535, a size no method that compiled
can reach. It was 8000 for four months and in no release: SPARK-21871 set it in
October 2017, and SPARK-23267 raised it before 2.3.0 shipped, citing
regressions in internal workloads. So by default the measurement is taken,
logged at INFO for each method past 8000, and acted on never. The ladder's
stage at 56 entries logs "Found too long generated codes" and falls back only
with the setting at 8000.

**The test that checks the size had stopped looking.** The TPC-DS, TPC-H and
SSB query suites compile every stage, and since 3.0.0 assert that no method
passes 8000 bytes (SPARK-29084), with `modified-q3` exempt pending a fix,
SPARK-29128, that never landed. From 3.2.0 adaptive execution is on by
default, the executed plan is an `AdaptiveSparkPlanExec`, which is a leaf, and
the check's `foreach` walk finds no stage below it. For five years the check
in those suites passed over nothing. SPARK-59764 runs it with adaptive
execution off, in 4.4.0.

Each has its own history, and both come from the same place: a generator of
source learns the size of its methods from the compiler, after the fact, and
every limit above is checked, where it is checked at all, on the way back.

Each claim in this section is a test that provokes the behaviour on vanilla
Spark and asserts it, with the revision named:

```
build/sbt "sql/testOnly *VarkaCodegenGiveUpSuite *VarkaCodegenCliffLogSuite"
```

## 2. The census, and Varka's column

![350,384 generated methods by size](figures/svg/fig22-the-method-sizes.svg)

*Figure 2. Every method Spark generates for its golden-file suite and its
TPC-DS, TPC-H and SSB queries, counted by size, against the two limits.*

How close does real work come to the line? Spark keeps a histogram of
generated method sizes in `CodegenMetrics`, but it is a decaying sample of about
a thousand values. Counting every method instead, with Varka off: 350,384
methods, a median of 27 bytes, the 99th percentile at 425, five between 4000 and
8000 and one past it, `modified-q3`'s `hashAgg_doAggregateWithKeys_0` at 12,450.
The test queries are not where the cliff is. It is where wide expressions are,
which is why the evidence below is a ladder of widths and one realistic filter
rather than a sweep of suites.

The same reading of the source found every place Spark's code generation gives
up: 34 of them, in four groups. Eleven are at plan time, where an operator, a
schema or a setting keeps a stage from forming. Twelve are while generating,
where the splitter is off or refuses. Eight are at compile time, the four
limits above among them. Three are outside a stage, in the
projections and predicates generated alone. Twenty-three can be provoked by a
query or a class built by hand, and the suites above provoke each one, with
vanilla's side and Varka's asserted on the same shape. Three rows give the
table's shape:

| # | where Spark gives up | what happens | logged | Varka |
|--|--|--|--|--|
| G15 | the splitter counts 1024 characters of source | methods of about 230 bytes outside a stage; none inside one | no | solved: every method measured in bytes on the built class |
| G25 | `hugeMethodLimit`, default 65535 | the stage falls back only past a size Janino already refuses | INFO, when it fires | solved: the budget is 8000 |
| G26 | HotSpot's 8000-byte limit | the method runs interpreted for the life of the JVM | INFO, per method | solved: split, or declined at plan time |

Varka's column has four answers: immune, solved, declined with a reason, or not
applicable because the operator stays Spark's. The first draft of the column
was a reading, and asserting each answer against its reproducer corrected one.
The whole table, with every trigger, log line and reproducer, is
[the census](https://github.com/vecbricks/varka/blob/master/sql/varka/plans/PLAN_TASK_188.md).

## 3. What Varka does instead

Varka's emitter never sees Java. It walks an IR of vector operations and writes
bytecode through `java.lang.classfile`, the Class-File API that became final in
JDK 24, so the length of every method's code is a number it reads off the class
it just built, in the unit HotSpot compares with 8000.

A kernel is one class. The projection's outputs are partitioned into groups,
and each group gets a loop method, which runs the group over every full lane
group of a batch, and an epilogue, which runs it over the rows that remain
under a mask; a batch with nulls takes a second pair of the same shape. Outputs
that share work go in one group, so a prefix such as the civil-from-days
decomposition several date fields need is computed once a lane group. A driver
calls the groups in turn, once per batch. Every call is per batch, not per row,
because the loop over the rows is inside each method.

After the class is built, every method is measured against 8000 bytes and the
class against the class-file caps. A group with a method over the budget is
split at its middle output and the class built again, until it fits. An output
over the budget on its own is declined, and the reason names the method, its
bytes and the budget. Splitting inside one output is deliberately not done: an
output is fused so that its intermediates stay in registers, and a call inside
it would spill them. The compiler asks its question at plan time, through the
shape cache, which builds and measures the class without defining it. A
declined output never reaches an executor: it stays on Spark's path as a
residual entry of the same node, the rest of the projection still fuses, and
`EXPLAIN` says why. Here one output is a balanced `greatest` over thirty-two
`add_months`, too heavy for any method, and the other a `date_add`:

```
Varka [2]: [h: residual (over the emitter's method budget (loopDense0 is 23505
  bytes, over the method budget of 8000 (HugeMethodLimit): HotSpot never compiles
  it): greatest(greatest(greatest(greatest(greatest(add_months(d, 1), add_months(d,
  ...), a: fused]
```

**Varka had the cliff too.** Until 24 September 2026 the epilogue was one method
over every output, a decision from when kernels were narrow. On a projection of
`make_date(year(d), month(d), k)` entries the masked epilogue passed 8000 bytes
at 13 outputs and reached 41,338 bytes at sixty, while the grouped loop methods
stayed under 5,400. The JVM's compile log, read in a forked JVM, showed every
loop method reaching C2 and the single epilogue at no tier. A bytecode emitter
is not immune to the limit; it is only able to see it. An epilogue per group
fixed it, and a test reads the same compile log to keep it fixed.

**Bounding the methods moves the problem to their caller.** The driver used to
set up each output's validity and literals in code repeated per output, and
passed 8000 bytes at about 140 outputs. It now does that work in two calls that
read tables baked into the class, so it grows by 44 bytes a group and not with
the outputs, the columns or the literals, and holds about 180 groups. Past
that, its calls move into stage methods, each calling a run of consecutive
groups, sized from the driver's measured bytes so that one rebuild settles it.
Past 64 input columns or the class-file caps, the compiler serves the entries
one kernel sets aside with a further kernel, and the kernels run in turn over
each batch into one output batch. The IR fuzzer composes kernels of 250 outputs
and more under five configurations of these options and checks every row
against a reference evaluator, and a run that does not reach every one of the
emitter's seven reactions to size fails.

Spark met the same displacement outside a stage. Its splitter puts each branch
of a wide `CASE WHEN` into a method of its own, and the method holding the calls
grows with the branches: 2141 bytes at 300 branches, 8060 at 1000, where it stops
being compiled. SPARK-59783, in 4.4.0, groups the calls into methods of their
own, which is the fix Varka's stages make.

**Compiled is not the same as inlined.** Splitting has a cost no size check
sees. `VarkaSplitInliningSuite` runs a `CASE WHEN` of `WHEN v = k THEN v * k`
branches outside a stage in a forked JVM under `-XX:+PrintInlining`, and reads
what C2 did with the split methods. At 16 branches it inlines five of six into
their caller. At 300 it inlines 11 of 101, and refuses 90 with
`size > DesiredMethodLimit`: C2 inlines at most 8000 bytes of bytecode into one
compilation, a second budget with the same number, which no product JDK lets
you change. Each refused method is compiled on its own, so the code is compiled
and still pays a call per method for every row. The same refusals show inside a
stage on apache/spark#59069, SPARK-33301's split of `CASE WHEN` there, still in
review. Varka's calls are per batch, so a refused inline costs a call per
method per batch, and the next section's numbers include every one of them.

*A second limit sits below 8000 bytes. C1, HotSpot's first compiler, refuses a
method of about a hundred Vector API calls whatever its bytes, and such a method
waits seconds for C2. Varka's emitter keeps wide groups under that count too, at
no cost we can measure, and leaves groups of a few heavy expressions past it,
where splitting would cost more than the wait. The ladder's groups are of that
kind.*

See any kernel's methods, their bytes and their vector calls, with the limits
checked:

```
dev/varka_emit.sh "greatest(add_months(d,1),date_add(d,1),last_day(d))" \
  "greatest(add_months(d,2),date_add(d,2),last_day(d))"
```

## 4. The size ladder

![The size ladder, defaults and tuned](figures/svg/fig11-the-size-ladder.svg)

*Figure 3. Time per row against the number of entries, on a log scale: Spark
under its defaults and with `hugeMethodLimit=8000`, and Varka, on an AMD EPYC
9V45, one core.*

The ladder is the first post's shape: `n` entries of
`greatest(add_months(d, k), date_add(d, k), last_day(d))` over two million
Arrow-cached dates, here on the runner pool's full-width 512-bit machine, an AMD
EPYC 9V45, with JDK 25.

Spark's largest method is 7,868 bytes at 52 entries and 8,254 at 54, and the
time per row goes from 876.0 to 4,671.8 ns, 5.3 times, and stays up: 9,722.5 ns
at a hundred. Varka runs the same rungs at 55.9, 59.1 and 97.1 ns a row: 16
times faster than Spark at 52 entries, where both are compiled, 79 times at 54
and a hundred times at a hundred. Its class at a hundred entries is Figure 1's,
with no method over 3,856 bytes.

Tuned, Spark does much better. With `hugeMethodLimit=8000` a stage past the
limit runs its operators separately and the step becomes a slope, 1,122.7 ns a
row at 54 entries and 2,155.6 at a hundred, in a run on the same CPU model whose
defaults read within 7% of this run's at every rung. `wholeStage=false` reads
the same. Against tuned Spark, Varka is 19 times faster at 54 entries and 22
times at a hundred. Most of that is the gap Varka has below the line, where
nothing is interpreted: tuning removes the cliff, and what is left is a vector
loop against a row loop. What no setting does is bound a method in bytes.

The ladder runs on one core. On a four-vCPU runner, an EPYC 7763, the ratio
past the step is 30 to 34 times with every core busy against 53 to 59 on one:
Spark's interpreter divides its time by 2.9 over four threads and Varka's
kernels by 1.7, since a core's two threads share one vector unit. And the 9V45
is one runner in about nine: four of thirty-six dispatches drew it, and this
file is the run nearest the median of the four, whose Varka rows are within 5
to 21% of each other at each rung.

## 5. One realistic filter

![The filter of modified-q3, defaults and Varka](figures/svg/fig23-the-range-filter.svg)

*Figure 4. A filter of `n` date ranges joined by `or`, over two million cached
rows on the AMD EPYC 9V45: Spark and Varka's two designs.*

`modified-q3` is the one TPC-DS query past the line, and its predicate is two
hundred date ranges joined by `or`: the shape a BI tool writes for a set of
reporting periods. A benchmark runs that filter over a cached integer date
column of two million rows.

Spark's method is 7,048 bytes at 49 ranges and 14,299 at a hundred, and the time
per row goes from 28.1 to 4,490.3 ns: 160 times, where the ladder steps five.
The difference is where the long method sits. The ladder's is a consume method
that a compiled loop calls; the filter is generated into the scan's
`processNext`, so when that method is not compiled, the loop over the rows is
interpreted with it.

Varka has two designs here, both built, both measured and both on. A
disjunction of ranges over one column compiles to a range set: the bounds in a
static table and one loop that tests each lane against them. Any other
predicate too large for one method is split across several selection outputs
that the filter combines. At 200 ranges the range set runs at 34.3 ns a row and
the split predicate at 33.0, against Spark's 8,426.4: 246 and 255 times faster.
Below the crossing the gap is the ordinary one, 2.5 and 2.7 times at 49 ranges.
The range set stays the design for ranges because it is cheaper to start: on a
four-core runner it plans a new filter of 200 ranges in 28 ms against 61, and
the JIT has it compiled in about a tenth of a second against more than three.

## 6. The accelerators

Gluten, Comet and Photon avoid the method limits by leaving the JVM: for the
operators their native libraries implement there is no 64KB method and no
8000-byte compile refusal. What they pay is a coverage boundary, past which they
fall back to Spark and every limit above returns. Comet draws that boundary with
the contract Varka arrived at on its own: a support level per expression, the
fallback reason in `EXPLAIN`, and no conversion until every child that produces
data is native.

An engine that evaluates one expression node at a time, over a whole batch,
needs neither native code nor a size limit. Velox works this way in C++, and
vecruntime 0.0.3 does it on the JVM with the Vector API, as a plugin for stock
Spark 4.1: each node writes its result out as a column and the next node reads
it back. On the size ladder, on the same 9V45 runner as section 4's figure,
vecruntime has no step. Its time grows by about 58 ns a row for each entry, the
price of the four columns an entry's `add_months`, `date_add`, `last_day` and
`greatest` write and read. Below the cliff that makes it 2.9 times slower than
Spark 4.1.3, whose whole-stage code keeps an entry's values in locals; at a
hundred entries, past the cliff, it is 1.7 times faster, though with
`hugeMethodLimit=8000` vanilla Spark is 2.7 times faster than it. Varka runs the
same hundred entries at 97.1 ns a row, 59 times faster than vecruntime. Having
no cliff is all an engine of that kind gets; fusion is what keeps the
intermediate values out of memory, as whole-stage code does, without its limit.

Two bounds on those numbers. vecruntime leaves a cached table to Spark, so it
reads Parquet where Varka reads its Arrow cache; Spark itself runs the ladder as
fast from one as from the other, so the ratio is the engines'. And the ladder is
the one shape both engines run: vecruntime converts none of the date chains,
which add a year-month interval to a date.

## 7. What it costs, and how it is measured

![The first query of a new shape](figures/svg/fig24-the-first-query.svg)

*Figure 5. A new shape's first, second and later queries over a hundred thousand
cached rows, Spark against Varka with its warm-up, on a four-core Intel Xeon
Platinum 8370C runner.*

**The first query.** A new shape pays for its kernel once: Varka plans it,
emits the class, and the JIT has to compile it before it is fast. Planning
takes 25 ms against Spark's 12 at 54 entries. By default a background thread
warms the kernel while the shape's batches run on Spark's own projection,
outside a stage, and the kernel takes over when the JIT has compiled it. Outside
a stage Spark's splitter does work, so that path has no cliff either: on a
four-core runner, past the cliff, Varka's first query is already a little faster
than Spark's, 665 ms against 713 at 54 entries, and its second much faster, 255
against 686. Below the cliff the compiled stage wins until the kernel is ready:
294 ms against 224 at 16 entries for the first query, 146 against 104 for the
second. Warming takes three and a half to four and a half seconds at 16 entries
on that runner and 22 to 24 at a hundred. After it, 16 entries take 37 ms against
Spark's 104, and 54 take 44 against 686. The kernel is keyed by the expression's
tree and not its literals, so a dashboard that reruns one shape with new values
pays once.

**How it is measured.** Every timing here is from a results file committed
beside the post, with a provenance file naming the commit, the run, the
runner's CPU, the JDK and the kernel. The ladder and the filter ran on
GitHub-hosted runners through the fork's benchmark workflow, with the CPU named
in the dispatch so that a run landing on any other machine stops before the
build; the development laptop's 256-bit datapath would understate a 512-bit
kernel. A checker reads every measured number in the project's documents and
fails on one that no committed results file contains. Ratios are taken within
one run; where the text sets two runs side by side, tuned Spark beside Varka,
one core beside four, or vecruntime beside both, it says so. The prose rounds;
the figures and the [results
files](https://github.com/vecbricks/varka/tree/master/sql/core/benchmarks)
carry the exact values.

---

A generator of source can only guess how large its methods are, and finds out
from the compiler when the method already exists. Varka asks the same question
of the class it built, in the unit the JVM enforces, before the class runs
anywhere. Every limit the first post described is then a number to compare and
a reason to give, and what does not fit is left to Spark, which runs it the way
it always has. The code, the census, the benchmarks and every results file
quoted here are in [vecbricks/varka](https://github.com/vecbricks/varka).
