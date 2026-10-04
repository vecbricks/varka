# Spark's built-in functions, as Varka's port sees them

*Opened 19 September 2026, on the owner's statement that ideally every Spark SQL
function is ported to Varka. A catalogue, not a plan: it says what each family
would need, which needs are shared, and one measured fact about the math family
that decides how it can be planned. Nothing here is built until a task claims
it.*

## 1. The census

`FunctionRegistry` registers **511** built-in scalar, aggregate and generator
functions. Varka fuses **46 Catalyst classes** today (`sql/varka/coverage.json`,
57 rows), nearly all of them dates and int32 arithmetic. By the registry's own
section headers:

| family | count | what a Varka port needs | where it stands |
|---|---:|---|---|
| datetime | 64 | int32 and int64 lanes; the calendar algorithms | Varka's home ground; `TIME` is milestone 5's subject |
| math | 61 | **a double lane**; the Vector API's math operators | section 2 and 3 |
| aggregate | 76 | the aggregation operator, milestone 6's target | `m8/SCOPE.md` items 4 and 5 |
| string | 67 | a string representation | `m8/SCOPE.md` item 80's family |
| predicate, conditional | 13 + 10 | compares, blend, `IS_NAN` | largely fused; `LIKE` and the regexes are strings |
| bitwise | 6 | shifts and counts, all in the Vector API | `m8/SCOPE.md` item 32 |
| hash | 9 | xxhash64 and murmur over lanes | feasible for numeric inputs; nothing scoped |
| array, map, lambda, collection, struct | 54 | variable-length nested values per row | far; no representation |
| window | 9 | operator-level, not expression-level | after aggregation |
| JSON, XML, CSV, Avro, Protobuf, variant, URL | 42 | parsing a string into structure | outside the lane model |
| datasketch, vector, ST, misc | 78 | sketch state, geometry, seeded random, session facts | outside the lane model |

Read down the third column and "all of them" is five representation problems
and a tail. The five: dates and times, which are in hand; **doubles, which are
not started** - today a double lane exists only as a conversion target inside one
node's lowering; strings; nested types; and aggregate state. The tail is the
parsing, sketching and session families, which are not vector work whatever the
representation.

The counts are the registry's sections and not a claim about difficulty: one
`aggregate` row is `sum` and another is `percentile_approx`.

## 2. The math family, function by function

Sixty-one registered names. What each needs, given a double lane:

**One Vector API operator each - 23.** `sin cos tan asin acos atan sinh cosh
tanh exp expm1 log ln log10 log1p cbrt sqrt pow atan2 hypot abs negative
positive`, and `signum` as a compare and a blend. The operators exist in JDK 25's
`VectorOperators` (`SIN COS TAN ASIN ACOS ATAN EXP LOG LOG10 SQRT CBRT SINH COSH
TANH EXPM1 LOG1P`, and `ATAN2 POW HYPOT` as binaries), and C2 lowers them to a
vector math library rather than to Java: Intel's SVML on x86 (`libjsvml.so`,
symbols of the form `__jsvml_sin8_ha_z0`), a SLEEF derivative on aarch64. The
JDK's own `legal/jdk.incubator.vector/sleef.md` is the notice.

**Composites of those - about a dozen.** `log2` is `log(x) / log(2)`; `cot`,
`sec`, `csc` are reciprocals of `tan`, `cos`, `sin`; `degrees` and `radians` are a
multiply; `e` and `pi` are constants; `asinh`, `acosh`, `atanh` are Spark's own
log-and-sqrt formulas in `ExpressionImplUtils`, which a kernel reproduces
term by term rather than through an operator the Vector API does not have;
`factorial` is a table of 21 entries; `width_bucket` is a divide and a compare.

**The rounding gap.** `VectorOperators` has **no rounding operator** - no
`FLOOR`, `CEIL`, `RINT`, `ROUND`. So `floor`, `ceil`, `ceiling`, `rint`, `round`
and `bround` are built, not called: the 2^52 add-and-subtract that
`VarkaLoopEmitter.emitMagicDivide` already uses for its floor, or a `D2L`
conversion and back. Spark's `floor` and `ceil` return `LONG` for a double
argument, so for them the conversion *is* the result. `round` and `bround` on a
double go through `BigDecimal` in Spark, which a kernel has to match digit for
digit; `truncate` likewise.

**Division by a column.** `mod`, `pmod` and `div` are the integral family. Where
the divisor is a constant they are VARKA-88's magic multiply; where it is a
column there is no magic, and the double route serves them as
`a - trunc(a / b) * b`, exact below 2^53 under the bound VARKA-88 states. That is
a new lowering, not a variant of an existing one.

**Not lane work.** `bin`, `hex`, `unhex`, `conv` produce strings. `rand`,
`randn`, `random`, `uniform` are seeded per partition and Spark's answers depend
on the row order the generator sees. `try_add`, `try_subtract`,
`try_multiply`, `try_divide`, `try_mod` are the overflow family and belong with
VARKA-63 and VARKA-104, not here.

## 3. The measured fact: none of these is bit-exact through the Vector API

Varka's contract is that a fused result equals the row engine's. For the math
family the row engine is a scalar library call, and Spark uses **two** scalar
libraries:

* `java.lang.Math` for `sin cos tan asin acos atan sinh cosh tanh cbrt sqrt
  atan2 hypot rint signum`;
* `StrictMath` - fdlibm, the portable reference - for `exp expm1 log ln log10
  log1p pow`, and `log2` through `StrictMath.log`.

Whether a lane agrees with the scalar call is a property of the host's
libraries, so it was measured rather than reasoned about, on the three machine
classes Varka runs on. `dev/varka_canary/MathLaneProbe.java` runs each operator
over 262144 inputs after C2, with the operator a compile-time constant in its
own loop and every operator bound before any is warm - the two conditions
without which C2 compiles the per-lane scalar fallback instead, silently and
with correct answers, and the probe measures `Math` against itself. That is
what the first reading of this section did, on 19 September 2026, and reported
as bit identity; the skills entry on it in
`sql/varka/skills/vector-api-and-width.md` says how. The probe now carries a
second pass with the fallback forced
(`-Djdk.incubator.vector.VectorMathLibrary=java`) as the control, prints
nanoseconds per element so a fallback shows in the numbers, and runs under
`-Djdk.incubator.vector.DEBUG=true` so the JDK names the symbol each operator
was bound to. The outputs are committed beside the probe as
`dev/varka_canary/mathlane-*.txt`; the two runner readings are the CI workflow
`varka-canary.yml`'s, the same day, JDK 25.0.4 on all three hosts.

| host | library | lanes | every operator bound? | per element, against the fallback |
|---|---|---:|---|---|
| Zen 5, `UseAVX=3` (this repository's laptop) | SVML `__jsvml_*8_ha_z0` | 8 | yes | 5x to 14x faster |
| EPYC 7763 (Zen 3), `UseAVX=2` (`ubuntu-latest`) | SVML `__jsvml_*4_ha_l9` | 4 | no: `POW` has no AVX2 symbol | 2.5x to 9x faster; `TANH` 2x **slower** |
| Neoverse N2, NEON (`ubuntu-24.04-arm`) | SLEEF `*d2_u10advsimd` (`HYPOT` `_u05`) | 2 | no: `TANH` has no symbol | 1.1x to 3.9x faster |

Lanes differing from the library Spark calls, out of 262144; the largest
difference is one unit in the last place except where marked (2):

| operator | Spark calls | Zen 5, `_z0` | EPYC 7763, `_l9` | Neoverse N2, SLEEF |
|---|---|---:|---:|---:|
| `SIN` | `Math` | 484 | 284 | 24466 |
| `COS` | `Math` | 530 | 347 | 23212 |
| `TAN` | `Math` | 1354 | 898 | 10121 |
| `ATAN` | `Math` | 9019 | 4426 | 9611 |
| `TANH` | `Math` | 1535 | 455 | unbound: 0 |
| `CBRT` | `Math` | 1637 | 188 | 21981 |
| `ATAN2` | `Math` | 15984 | 15983 | 21507 |
| `HYPOT` | `Math` | 34258 | 34237 | 34155 |
| `EXP` | `StrictMath` | 27425 | 24969 | 27347 |
| `EXPM1` | `StrictMath` | 25691 | 25694 | 25701 |
| `LOG` | `StrictMath` | 12938 | 12924 | 13198 |
| `LOG10` | `StrictMath` | 24210 (2) | 24199 (2) | 24250 (2) |
| `LOG1P` | `StrictMath` | 12224 | 12211 | 12377 |
| `POW` | `StrictMath` | 28701 | unbound: 25672 | 25775 |

An unbound operator runs the scalar `Math` call per lane, at scalar speed: that
is why `TANH` on NEON is exact, and why `POW` at AVX2 still differs from
`StrictMath` - `Math.pow` on x86 is HotSpot's intrinsic, not fdlibm.

Four things follow.

1. **No operator reproduces the row engine's bits on any host.** Against the
   library Spark calls, every bound operator differs on anything from a single
   lane (`log10` at AVX2) to thirteen percent of them (`hypot`), by one unit in
   the last place, two for `log10` everywhere and `tanh` on x86. The only exact
   lanes are the ones with no library symbol, and those are exact because they
   *are* the scalar call.
2. **The bits are per library build, not per architecture.** SVML's AVX-512 and
   AVX2 builds disagree with each other - `exp` differs from `Math` on 12193
   lanes at `_z0` and on 1373 at `_l9` - and SLEEF is a third answer. A ULP
   bound can be stated once for every host; a bit pattern cannot, even within
   x86.
3. **`java.lang.Math` is itself per host.** On aarch64 HotSpot intrinsifies only
   `sin` and `cos` and every other `Math` call *is* fdlibm, so there `Math` and
   `StrictMath` agree on twelve of the fourteen; x86 carries Intel's scalar
   intrinsics for `sin cos tan exp log log10 pow tanh cbrt`. The row engine's
   own answer for `sin(x)` already differs between the two architectures a
   cluster may mix. The six `StrictMath` functions are the only ones whose
   row-engine bits are the same everywhere.
4. **The speed-up is width.** Eight lanes buy 5x to 14x, four buy 2.5x to 9x
   (except `tanh`, where SVML's AVX2 build loses to HotSpot's scalar intrinsic
   by 2x), two buy 1.1x to 3.9x. On NEON the operators that clear 2x are `exp`,
   `log10`, `expm1`, `log1p`, `pow` and `atan2`; `cbrt` gains nothing.

So the choice is one choice for the whole family, not for six functions, and
none of the three options is free. A **ULP contract**: each ported function
stated in the coverage table as "within 1 ulp of `java.lang.Math`" or "within
2 ulp of `StrictMath`" - the tier SLEEF names `_u10`, the place
`SCOPE_STANDARD_MODE.md` keeps for a deliberate deviation - with an answer for
what the ghost fallback means when one query is served partly by each library,
which is the mix a cluster of x86 and aarch64 executors already produces for
the `Math` functions today. A **Varka-emitted fdlibm** for the six `StrictMath`
functions, whose row-engine bits are the same on every host: `exp` and `log`
are a table and a short polynomial, and a lane that reproduces fdlibm's
arithmetic reproduces its bits - bit-exact, slower than SVML by a factor to be
measured, and a kernel to maintain. Or a **decline** of the family, since no
function in it is exact. `m8/SCOPE.md` item 36 holds the decision.

## 4. What is shared across the families

The families are separate representations, but three needs recur and are worth
building once:

* **A double lane** unlocks the whole math family, `nanvl`, `isnan`, and every
  numeric function whose argument is a double. It is the single largest
  unblocker in the table and is not scoped.
* **Rounding without a rounding operator** is needed by the math family, by
  `round`-style datetime functions, and by any decimal work later. One helper,
  measured once.
* **A per-function exactness statement** - bit-exact, or a ULP bound, or
  declined - is what section 3 forces on the math family and what the standard
  mode register already asks of intervals. It generalises: every ported
  function should say which it is, and the coverage table should carry the
  answer.

## 5. Sources read for this catalogue

`FunctionRegistry.scala` (the census), `mathExpressions.scala` and
`ExpressionImplUtils.java` (which library each function calls, and the
composites), JDK 25's `VectorOperators` (the operator set, enumerated from the
running JVM rather than from documentation), and the SLEEF and OpenVML
libraries, whose reading is recorded in
`sql/varka/skills/vector-api-and-width.md` under the SLEEF heading. SLEEF has no
integer division and never converts a 64-bit integer; what it contributed is
listed there.
