# Your `sin()` depends on your JDK and your instruction set

You move a Spark job to Arm instances, or upgrade the cluster to JDK 25. The job runs, the row
counts match, and then the output diff lights up: a column computed with `cos` disagrees in the
last digit. Nothing in your code changed. Both answers are correct.

Here is the smallest case we have. On one laptop, with nothing changed but the JDK, Spark's
`SELECT cbrt(0.22)` returns `0.6036810736797685` on JDK 21 and `0.6036810736797686` on JDK 25.
And `SELECT cos(0.1)` on x86 returns `0.9950041652780258`, where Java's reference math library
says `0.9950041652780257`. On an Arm server Spark gives **[owed: Arm runner]**.

> **In short**
>
> Eight of Spark's math functions can return a different last bit on a different machine. What
> decides it is the instruction set and the JDK version: not the chip model, not the JIT, not
> whole-stage codegen. If you compare results across machines, compare those columns with a
> tolerance; if you need the same bits everywhere, there is a way to get them.

![SELECT cos(0.1), two neighbouring doubles](figures/svg/fig65-one-ulp.svg)

*Figure 1. The two answers to `cos(0.1)` are neighbouring doubles, 2^-53 apart. The exact cosine
of the double 0.1 lies between them, a hair nearer the x86 answer: 0.496 ulp from it and 0.504
from fdlibm's.*

## Two libraries behind one function list

Java has two math classes. `StrictMath` is fdlibm, a C library from the 1990s ported to Java and
defined to the bit: it gives the same answer on every machine. `Math` only promises to be within
one unit in the last place (an ulp) of the true result, which leaves the JVM free to use a
different routine when it has one.

HotSpot has several. On x86 it swaps `Math.sin`, `cos`, `tan`, `tanh`, `cbrt`, `exp`, `log`,
`log10` and `pow` for hand-written machine code Intel contributed: faster than fdlibm, and
rounding differently on a few percent of inputs. On Arm it swaps only `sin` and `cos`, for its own
port of fdlibm that uses fused multiply-adds. Everything else falls through to `StrictMath`.

Which of these a query meets depends on a choice Spark made function by function. Spark computes
the trigonometric functions, `tanh` and `cbrt` with `Math`, and its documentation says so: `sin`
returns the sine "as if computed by `java.lang.Math.sin`". It computes `exp`, `ln`, `log10`,
`log1p` and `pow` with `StrictMath`. So the x86 routines for `exp`, `log` and `pow`, which differ
from fdlibm just as often, never reach a query, while the ones for the trigonometric functions,
`tanh` and `cbrt` do.

![Spark's math functions by the Java class they call](figures/svg/fig62-two-libraries-behind-one-function-list.svg)

*Figure 2. Spark's math functions by the class they call, with the count of inputs, of 1,048,576,
where `Math` and `StrictMath` differ on our x86 laptop at JDK 25. Left, what Spark computes with
`Math` (`sin`, `cos`, `tan`, `sec`, `csc`, `cot`, `tanh`, `cbrt`); right, with `StrictMath`.*

**One query can carry two answers.** Spark folds a constant expression while it plans the query,
so `cos(0.1)` is computed once, on the driver. `cos(x)` over a column is computed on the
executors. Put an x86 driver in front of Arm executors and the same expression has one answer in
the filter Spark folded and another in the rows it computed.

## What does not change the answer

You might suspect the JIT: a method starts in the interpreter and is compiled later, so perhaps
the answer moves as the query warms up. It does not. HotSpot calls the same routine from the
interpreter, C1 and C2, precisely so that a function stays consistent while its caller is
compiled. Whole-stage codegen, Spark's generated projections and its interpreted expressions gave
the same bits on every row we ran. And the x86 chip does not matter: a Zen 5 laptop with
AVX-512 and an EPYC 7763 cloud runner with AVX2 differ from fdlibm on the same inputs, function
by function. **[owed: EPYC 7763 runner, the answers compared input by input]**

What does move it is the architecture and the JDK. Arm replaces only `sin` and `cos`, so on
`tan`, `tanh` and `cbrt` Arm gives fdlibm's answer and x86 does not, and on `sin` and `cos` each
has its own. **[owed: Arm runner, the count input by input]** On x86, the `tanh` routine arrived
in JDK 24 and the `cbrt` one in JDK 25: upgrading from 21 changes `tanh` on about one input in
eight and `cbrt` on about one in twelve.

## Where it bites

One ulp of a double near 1 is about 1e-16, the sixteenth significant digit. No dashboard shows
it. It shows wherever doubles are compared exactly:

- a row-by-row diff of a job's output before and after a move to Arm or a JDK upgrade;
- a checksum or hash of a result table;
- a join or `GROUP BY` on a computed double;
- a test whose expected answers were written on another machine.

It also shows in text. Java prints a double as the shortest decimal that tells it from its
neighbours, so the two answers to `cos(0.1)` print with different last digits, and a CSV export
diffs.

How often depends on the function. About 2% of inputs for `sin`, `cos` and `tan`, about 8% for
`cbrt`, about 12% for `tanh`. Almost always by one ulp; `tanh` reaches two on about one
difference in a hundred, and so do Spark's `sec`, `csc` and `cot`, which divide one by a cosine,
sine or tangent that was already one ulp off.

![How often, how far](figures/svg/fig64-how-often-how-far.svg)

*Figure 3. The share of 1,048,576 inputs where `Math` differs from fdlibm, by one ulp and by two,
on our x86 laptop at JDK 25 and 21 and on an Arm runner (owed). In Spark's own query over 262,144
rows, the eight functions differed in 4,166 (`csc`) to 32,396 (`tanh`) rows.*

## What to do

- **Comparing results across machines:** compare these columns with a relative tolerance of a
  few times 1e-16, not with `=`. Rounding to fewer digits first helps less than it seems: two
  answers one ulp apart can still round to different values.
- **Needing the same bits everywhere:** call fdlibm yourself.
  `CAST(reflect('java.lang.StrictMath', 'cos', x) AS DOUBLE)` gave fdlibm's answer on every row
  of our test except NaNs, whose payload the string drops. It goes through reflection and a
  string, so it is much slower than `cos(x)`.
- **Changing the JVM instead:** `-XX:+UnlockDiagnosticVMOptions
  -XX:DisableIntrinsic=_dsin,_dcos,_dtan,_dtanh,_dcbrt` in `spark.driver.extraJavaOptions` and
  `spark.executor.extraJavaOptions` makes `Math` return fdlibm's bits. It slows every `Math.sin`
  in the JVM, not only Spark's.
- **Or keep the machine fixed** for the runs you compare: same architecture, same JDK.

We build Varka, a vectorized engine for Spark SQL. It matches Spark bit for bit on the same host,
so it calls the same `Math` methods and inherits the same dependence.

## How these were measured

On HotSpot JDK 25.0.4.1, 21 and 17, on an AMD Zen 5 laptop: a probe compares `Math` and
`StrictMath` over 1,048,576 inputs per function, cold and hot and in each JIT tier, and a Spark
query over a 262,144-row Parquet table runs the same functions with and without codegen. The
x86-chip and Arm comparisons come from the control pass of an earlier probe on GitHub's EPYC 7763
and Neoverse N2 runners; the HotSpot facts are from the JDK 25 source.
**[owed: the probe and the Spark query on the Neoverse N2 and EPYC 7763 runners]**
The record is [`VARKA-587.md`](https://github.com/vecbricks/varka/blob/master/sql/varka/plans/m8/VARKA-587.md),
with the laptop's output in
[`VARKA-587-math-host-laptop.txt`](https://github.com/vecbricks/varka/blob/master/sql/varka/plans/m8/results/VARKA-587-math-host-laptop.txt).
