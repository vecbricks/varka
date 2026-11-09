# Your `sin()` depends on your JDK and your instruction set

Run `SELECT cos(0.1)` in Spark on our x86 laptop with JDK 25 and you get `0.9950041652780258`.
fdlibm, the math library Java ships as `StrictMath`, says `0.9950041652780257`. On an Arm server
Spark gives **[owed: Arm runner]**. And on the same laptop, Spark's `SELECT cbrt(0.22)` gives
`0.6036810736797686` on JDK 25, while on JDK 21 the `Math.cbrt` it calls gives
`0.6036810736797685`. No setting changed, and no
answer is wrong.

![SELECT cos(0.1), two neighbouring doubles](figures/svg/fig65-one-ulp.svg)

*Figure 1. The two answers to `cos(0.1)` are neighbouring doubles, 2^-53 apart. The exact cosine
of the double 0.1 lies between them, a hair nearer the x86 answer: 0.496 ulp from it and 0.504
from fdlibm's.*

> **In short**
>
> 1. Eight of Spark's math functions, `sin`, `cos`, `tan`, `sec`, `csc`, `cot`, `tanh` and
>    `cbrt`, can return different last bits on different machines. Spark computes them with
>    `java.lang.Math`, which the JVM replaces with its own machine code. The rest, `exp`, `ln`,
>    `log10`, `pow`, `sqrt`, `asin`, `atan2` and the others, answer the same on every HotSpot
>    JVM.
> 2. What changes the answer is the instruction set and the JDK: x86 and Arm differ, and on x86
>    JDK 25 changed `tanh` and `cbrt`. Not the CPU model, not the JIT tier, not whole-stage
>    codegen.
> 3. The difference is one unit in the last place (two for `tanh`, `sec`, `csc`, `cot`), on 2 to
>    12% of inputs. Compare such results with a tolerance; if you need the bits, `reflect` into
>    `StrictMath` gives fdlibm's answer on any host.

## Two libraries behind one function list

Spark's documentation says `sin` returns the sine "as if computed by `java.lang.Math.sin`", and it
means it: both the generated code and the interpreted path call `Math.sin`. Java has two math
classes. `StrictMath` is fdlibm, defined to the bit, the same on every machine. `Math` only has to
be within one ulp (a unit in the last place) of the exact result, and the JVM may use a different
routine if it has one.

HotSpot does. On x86 it replaces `Math.sin`, `cos`, `tan`, `tanh`, `cbrt`, `exp`, `log`, `log10`
and `pow` with routines Intel contributed, hand-written machine code that is faster than fdlibm
and rounds differently. On Arm it replaces only `sin` and `cos`, with its own port of fdlibm that
uses fused multiply-adds. Every other `Math` method just calls `StrictMath`.

Spark calls `Math` for the trigonometric functions, `tanh` and
`cbrt`, and `StrictMath` for `exp`, `ln`, `log2`, `log10`, `log1p` and `pow`. So the x86 routines
for `exp`, `log` and `pow`, which differ from fdlibm just as often, never reach a query.

![Spark's math functions by the Java class they call](figures/svg/fig62-two-libraries-behind-one-function-list.svg)

*Figure 2. Spark's math functions by the class they call, with the count of inputs, of 1,048,576,
where `Math` and `StrictMath` differ on our x86 laptop at JDK 25. Left, what Spark computes with
`Math`; right, with `StrictMath`.*

## What changes the answer

Five things could plausibly change the bits. We tested each.

- **The JIT tier: no.** The interpreter, C1 and C2 all call the same routine. HotSpot insists on
  it, so that a function's answers stay consistent while a method moves from the interpreter to
  compiled code. A query does not change its answer as it warms up.
- **Spark's code path: no.** Whole-stage codegen, generated projections without it and interpreted
  expressions gave the same bits in every row.
- **The x86 CPU model: no.** The x86 routines have no branch that changes a value with the CPU's
  features, and an AMD EPYC 7763 cloud runner with AVX2 and our AVX-512 laptop differ from fdlibm
  on exactly the same number of inputs, function by function. **[owed: EPYC 7763 runner, the
  answers compared digest by digest]**
- **The JDK: yes.** The `tanh` routine arrived in JDK 24 and the `cbrt` one in JDK 25. On our
  laptop, JDK 25 answers `tanh` differently from JDK 21 on 12% of inputs and `cbrt` on 8%.
- **The architecture: yes.** Arm replaces only `sin` and `cos`, with code of its own, so x86 and
  Arm can disagree on all five: on `tan`, `tanh` and `cbrt` Arm gives fdlibm's answer and x86 does
  not, and on `sin` and `cos` each has its own. **[owed: Arm runner, the count input by input]**

![What changes the answer](figures/svg/fig63-what-changes-the-answer.svg)

*Figure 3. Each cause with its verdict and the count behind it. The x86 CPU row compares two
machines on the same 262,144 inputs; the JDK row compares JDK 25 and JDK 21 on one laptop.*

A mixed cluster adds a twist: Spark computes `cos(0.1)` once while it plans the query, on the
driver, and `cos(x)` of a column on the executors, so an x86 driver with Arm executors can give one
expression two answers.

## How often, how far

On our laptop, about 2% of inputs differ for `sin`, `cos` and `tan`, 8% for `cbrt` and 12% for
`tanh`, over a million inputs drawn from bit patterns, wide and narrow ranges, and values near
zero. Almost always by one ulp: only `tanh` reaches two, on about one difference in a hundred. In
Spark, `sec`, `csc` and `cot` reach two ulps as well, because Spark computes them as `1 / cos(x)`
and so on, and dividing by an answer one ulp off can land two ulps off.

![How often, how far](figures/svg/fig64-how-often-how-far.svg)

*Figure 4. The share of 1,048,576 inputs where `Math` differs from fdlibm, by one ulp and by two,
on our x86 laptop at JDK 25 and 21 and on an Arm runner (owed). In Spark's own query over 262,144 rows,
the eight functions differed in 4,166 (`csc`) to 32,396 (`tanh`) rows.*

One ulp of a double near 1 is about 1e-16, the 16th significant digit. Your dashboards will not
show it. It shows wherever a double is compared exactly: a row-by-row diff of a job's output
before and after a move to Arm instances or a JDK upgrade, a checksum or hash of a result, a join
or `GROUP BY` on a computed value, a test with expected answers written on another machine. And
it shows in text: Java prints a double as the shortest decimal that tells it from its neighbours,
so the two answers to `cos(0.1)` print with different last digits.

## What to do

- **Comparing results across machines**: compare these columns with a tolerance, a relative
  error of a few times 1e-16, not with `=`. Rounding first helps less than it seems: two answers
  one ulp apart can still round to different values.
- **Needing the same bits everywhere**: call fdlibm yourself.
  `CAST(reflect('java.lang.StrictMath', 'cos', x) AS DOUBLE)` gave fdlibm's answer in every row of
  our test but the NaNs, whose payload the string drops. It goes through reflection and a string, so it is much slower than `cos(x)`.
- **Changing the JVM instead**: the diagnostic option
  `-XX:+UnlockDiagnosticVMOptions -XX:DisableIntrinsic=_dsin,_dcos,_dtan,_dtanh,_dcbrt`, in
  `spark.driver.extraJavaOptions` and `spark.executor.extraJavaOptions`, makes `Math` return
  fdlibm's bits in every tier. It slows every `Math.sin` in the JVM, not only Spark's.
- **Or keep the machine fixed** where bits matter: the same architecture and the same JDK for the
  runs you compare.

We build Varka, a vectorized engine for Spark SQL, and it follows Spark bit for bit on the same
host, so it calls the same `Math` methods and shares the same dependence.

## How these were measured

On HotSpot JDK 25.0.4.1, 21 and 17, on an AMD Zen 5 laptop. A probe compares `Math` and `StrictMath` over
1,048,576 inputs per function, drawn four ways with a fixed seed, cold and hot, and in JVMs
restricted to the interpreter, C1 or C2; a Spark query over a 262,144-row Parquet table runs the
same functions with whole-stage codegen, without it and interpreted, against `StrictMath`. The
x86 CPU and Arm comparisons of Figure 3 come from an earlier probe of the vector math library,
whose control pass calls `Math` on GitHub's AMD EPYC 7763 and Arm Neoverse N2 runners. The HotSpot
facts are from the JDK 25 source.
**[owed: the probe and the Spark query on the Neoverse N2 and EPYC 7763 runners]**

The record is
[`VARKA-587.md`](https://github.com/vecbricks/varka/blob/master/sql/varka/plans/m8/VARKA-587.md),
with the laptop's output in
[`VARKA-587-math-host-laptop.txt`](https://github.com/vecbricks/varka/blob/master/sql/varka/plans/m8/results/VARKA-587-math-host-laptop.txt)
and the probes in
[`dev/varka_canary/`](https://github.com/vecbricks/varka/tree/master/dev/varka_canary).
