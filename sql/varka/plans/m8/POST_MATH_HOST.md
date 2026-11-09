# Your `sin()` depends on your JDK and your instruction set

You move a Spark job to Arm instances, or upgrade the cluster to JDK 25. The job runs, the row
counts match, and then the output diff lights up: a column computed with `cos` disagrees in the
last digit. Nothing in your code changed. Both answers are correct.

Here is the smallest case we have. On a laptop with an AMD Ryzen AI 9 HX PRO 370 (Zen 5, AVX-512)
and Ubuntu's OpenJDK builds, with nothing changed but the JDK, Spark's `SELECT cbrt(0.22)`
returns `0.6036810736797685` on JDK 21 and `0.6036810736797686` on JDK 25. And
`SELECT cos(0.1)` on x86 returns `0.9950041652780258`, where Java's reference math library
says `0.9950041652780257`. On an Arm server, a Neoverse N2 on JDK 25, Spark's `cos(0.1)`
returns the reference answer, `0.9950041652780257`, so the same query on x86 and on Arm
disagrees in the last digit.

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
defined to the bit: it gives the same number on every machine (though not always the same
bits for a NaN). `Math` only promises to be within
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
where `Math` and `StrictMath` differ on the Ryzen laptop (x86) at JDK 25. Left, what Spark
computes with `Math` (`sin`, `cos`, `tan`, `sec`, `csc`, `cot`, `tanh`, `cbrt`); right, with
`StrictMath`.*

**One query can carry two answers.** Spark folds a constant expression while it plans the query,
so `cos(0.1)` is computed once, on the driver. `cos(x)` over a column is computed on the
executors. Put an x86 driver in front of Arm executors and the same expression has one answer in
the filter Spark folded and another in the rows it computed.

![One query, two answers](figures/svg/fig66-one-query-two-answers.svg)

*Figure 3. `SELECT x FROM t WHERE cos(x) = cos(0.1)` with an x86 driver and Arm executors. The
driver folds `cos(0.1)` to 0.9950041652780258 (the Ryzen laptop, JDK 25); the executors compute
`cos(x)` at the row x = 0.1 as 0.9950041652780257 (the Neoverse N2 GitHub Arm runner, JDK 25),
which at this input is also fdlibm's answer, so the row is filtered out. A drawing of the
mechanism: each answer is from its own host, and we did not run a mixed cluster.*

## What does not change the answer

You might suspect the JIT: a method starts in the interpreter and is compiled later, so perhaps
the answer moves as the query warms up. It does not. HotSpot calls the same routine from the
interpreter, C1 and C2, precisely so that a function stays consistent while its caller is
compiled. Whole-stage codegen, Spark's generated projections and its interpreted expressions gave
the same bits on every row we ran. And the x86 chip does not matter: the Ryzen laptop (Zen 5,
AVX-512) and an EPYC 7763 GitHub runner (AVX2) differ from fdlibm on the same inputs, function
by function. **[owed: EPYC 7763 runner, the answers compared input by input]** An Intel Xeon
Platinum 8573C GitHub runner, on another vendor's JDK 25 build, gave the laptop's bits for all
five functions on every one of a million inputs.

What does move it is the architecture and the JDK. Arm replaces only `sin` and `cos`, so on
`tan`, `tanh` and `cbrt` Arm gives fdlibm's answer and x86 does not, and on `sin` and `cos` each
has its own. Input by input, the laptop and an Arm runner disagree on about 6% of inputs for
`sin` and 4% for `cos`, more than either disagrees with fdlibm, and for the other three on
exactly the inputs where x86 leaves fdlibm. On x86, the `tanh` routine arrived
in JDK 24 and the `cbrt` one in JDK 25: upgrading from 21 changes `tanh` on about one input in
eight and `cbrt` on about one in twelve.

![Which routine answers](figures/svg/fig67-which-routine-answers.svg)

*Figure 4. The routine behind `Math.sin`, `cos`, `tan`, `tanh` and `cbrt` on JDK 25 on the Ryzen
laptop, an EPYC 7763 GitHub runner and a Neoverse N2 GitHub Arm runner, with the count of 262,144
positive inputs (an earlier probe's control pass, a different input set from Figure 6's) where
`Math` differs from fdlibm. The two x86 chips: 8,707 (`sin`) to 21,962 (`cbrt`), the same count
for every function. Arm: 19,081 for `sin`, 17,697 for `cos`, none for the other three. Below, the
Ryzen laptop against the Arm runner input by input over Figure 6's 1,048,576 inputs: 66,122 for
`sin` and 43,725 for `cos`, by 1 ulp (125 of each only in a NaN's bits), and for `tan`, `tanh` and
`cbrt` the laptop's own counts against fdlibm, 22,721, 130,493 and 86,791.*

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

![Where it bites](figures/svg/fig68-where-it-bites.svg)

*Figure 5. A two-row CSV export, diffed. Left, the Ryzen laptop on JDK 21 and on JDK 25: `cos(0.1)`
prints 0.9950041652780258 on both, `cbrt(0.22)` 0.6036810736797685 and then 0.6036810736797686.
Right, the Ryzen laptop against the Neoverse N2 Arm runner, both on JDK 25: Arm prints
0.9950041652780257 and 0.6036810736797685, fdlibm's answers to both.*

How often depends on the function and the host. On x86, about 2% of inputs for `sin`, `cos`
and `tan`, about 8% for `cbrt`, about 12% for `tanh`; on Arm, about 5% for `sin` and 3% for
`cos`. Almost always by one ulp; `tanh` reaches two on about one
difference in a hundred, and so do Spark's `sec`, `csc` and `cot`, which divide one by a cosine,
sine or tangent that was already one ulp off.

![How often, how far](figures/svg/fig64-how-often-how-far.svg)

*Figure 6. The share of 1,048,576 inputs where `Math` differs from fdlibm, by one ulp and by two,
on the Ryzen laptop at JDK 25 and 21 and on the Neoverse N2 Arm runner at JDK 25, where 54,794
differ for `sin`, 29,538 for `cos` and none for the other three. Values only: an answer that differs
only in a NaN's bits is not counted.*

![Spark's own query](figures/svg/fig69-sparks-own-query.svg)

*Figure 7. Spark's own query over 262,144 rows read from Parquet: the rows where each function
differs from the same function over `StrictMath`, on the Ryzen laptop and on the Neoverse N2 Arm
runner, both at JDK 25. On the laptop from 4,166 (`csc`) to 32,396 (`tanh`), by up to 2 ulps for
`sec`, `csc`, `cot` and `tanh`; on Arm 13,569 for `sin`, 7,389 for `cos`, 4,418 for `sec` and
7,954 for `csc`, by up to 2 ulps for `sec` and `csc`, and none for `tan`, `cot`, `tanh` and `cbrt`;
on both, none for `asin`, `atan2`, `exp`, `ln`, `log10` and `pow`. Whole-stage codegen, the
generated projection and the interpreted path gave one digest per column on each host. The EPYC
7763 run is owed.*

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

On a laptop with an AMD Ryzen AI 9 HX PRO 370 (Zen 5, AVX-512) running Linux, with Ubuntu's
OpenJDK builds 25.0.4.1, 21.0.12.1 and 17.0.20.1 (HotSpot): a probe compares `Math` and
`StrictMath` over 1,048,576 inputs per function, cold and hot and in each JIT tier, and a Spark
query over a 262,144-row Parquet table runs the same functions with and without codegen. The
same probe ran on a Neoverse N2 GitHub Arm runner and an Intel Xeon Platinum 8573C GitHub runner,
and the Spark query on the Neoverse N2, all with Azul's Zulu 25.0.5 build. Figure 4's counts come
from the control pass of an earlier probe on GitHub's EPYC 7763 and Neoverse N2 runners; the
HotSpot facts are from the JDK 25 source. **[owed: the Spark query on the EPYC 7763 runner]**
The record is [`VARKA-587.md`](https://github.com/vecbricks/varka/blob/master/sql/varka/plans/m8/VARKA-587.md),
with the Ryzen laptop's output in
[`VARKA-587-math-host-laptop.txt`](https://github.com/vecbricks/varka/blob/master/sql/varka/plans/m8/results/VARKA-587-math-host-laptop.txt)
and the Arm runner's in
[`VARKA-587-math-host-arm.txt`](https://github.com/vecbricks/varka/blob/master/sql/varka/plans/m8/results/VARKA-587-math-host-arm.txt) and
[`VARKA-587-spark-arm.txt`](https://github.com/vecbricks/varka/blob/master/sql/varka/plans/m8/results/VARKA-587-spark-arm.txt).
