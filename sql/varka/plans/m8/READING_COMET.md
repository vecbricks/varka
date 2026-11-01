# Reading for milestone 8: what DataFusion Comet offers for the rest of m8's types

Read on 1 November 2026 at the owner's request: Apache DataFusion Comet runs Spark's plans in
native code and must match Spark's answers, so what does it know about Spark's decimal and
floating semantics, its own divergences, its parity testing and its fallback rule that rows 342
to 366, 352 and 541 of `m8/PLAN.md` can use? (541 is the Spark-fuzzer row the lead named,
described in `READING_SQLANCER.md`; it is not yet a row of PLAN.md on this branch.) Paths are
relative to `/home/max/proj/datafusion-comet` at commit `383ec5627` (1 November 2026). `SERDE` is
`spark/src/main/scala/org/apache/comet/serde/`, `EXPR` is `native/spark-expr/src/`, `COMPAT` is
`docs/source/user-guide/latest/compatibility/`, `AUDIT` is
`docs/source/contributor-guide/expression-audits/`, `CG` is `docs/source/contributor-guide/`,
`TEST` is `spark/src/test/scala/org/apache/comet/`. Spark's side was read in this fork.
**[V]** means the author read the code or doc; **[I]** means inferred; **[C]** means computed in
Python's `decimal` module. Nothing was built or run on a JVM. `READING_ARROW_RS.md` (Comet's
decimal kernels at `8c229a703`, 16 September 2026), `READING_TRINO_DECIMAL.md`,
`READING_DECIMAL_ENGINES.md` and `READING_RAPIDS.md` are not repeated; they are cited where Comet
agrees or differs. `READING_SQLANCER.md` holds the three-way run for 386 and 541.

## 1. What Comet is at this commit

* **Three paths per expression, chosen at plan time.** A serde returns `Compatible`,
  `Incompatible` or `Unsupported` from the expression's types and literals, never its values
  (`SERDE/SupportLevel.scala:27-48`) **[V]**. Compatible runs the native kernel. Since the
  September reading a fourth answer exists: a serde that mixes in `CodegenDispatchFallback`
  sends its Incompatible and Unsupported cases to the **codegen dispatcher**, which compiles
  Spark's own `doGenCode` against Arrow vectors with Janino, one method per expression and
  schema, one JNI round trip per batch (`SERDE/CometExpressionSerde.scala:108-125`,
  `spark/src/main/scala/org/apache/comet/codegen/CometBatchKernelCodegen.scala:44-62`) **[V]**.
  Only what the dispatcher cannot take falls back to Spark, and then the **whole projection**
  goes, unrelated expressions included (`COMPAT/floating-point.md:130-134`) **[V]**. The native
  path of a dispatched expression is opt-in per expression,
  `spark.comet.expression.<Name>.allowIncompatible` (`COMPAT/index.md:36-76`) **[V]**.
* **One decimal representation.** Every decimal is an Arrow `Decimal128` (`i128`); the narrow
  case is a per-value fast path inside a kernel, as `decimal_ceil_pow10` tries `i64` first with
  a constant divisor and keeps the 128-bit division out of line (`EXPR/math_funcs/ceil.rs:84-104`)
  **[V]**. Nothing like Varka's long lane for `p <= 18`.
* **The decimal findings of `READING_ARROW_RS.md` 2 still hold.** The wide-path thresholds are
  unchanged (`native/core/src/execution/planner.rs:1301-1303`) **[V]**. Two updates: the ANSI
  overflow of the wide path now raises Spark's `NUMERIC_VALUE_OUT_OF_RANGE` with Spark's value
  text, a product's value cut to 39 digits as Spark's `MathContext(39, DOWN)` does (#5276,
  `EXPR/math_funcs/wide_decimal_binary_expr.rs:376-450`) **[V]**, which settles that reading's
  **[I]**; and a decimal-to-decimal cast is one fused `DecimalRescaleCheckOverflow` that hands
  rescale, `HALF_UP` and the precision check to Arrow's cast kernel, with a scale gap past 38
  answered as zero or overflow by hand (`EXPR/math_funcs/internal/decimal_rescale_check.rs:
  108-153`, `:170-202`) **[V]**. It is arrow-rs's `make_downscaler`, so 364 has nothing new in it.
* **Per-expression audits** record, per Spark version, which Java method Spark calls and where
  the native kernel differs (`AUDIT/*.md`, 19 files) **[V]**; the user guide's per-expression
  notes and cast tables are generated from the serdes' reason methods by
  `spark/src/main/scala/org/apache/comet/GenerateDocs.scala` **[V]**.

## 2. Decimal semantics against Spark (349, 350, 351, 360 to 364)

* **Decimal to `INT`, `SMALLINT`, `TINYINT`, `BIGINT`: the exact rule, at every precision.** The
  ANSI arm truncates the `i128` by `10^s` and raises when `truncated.abs() > MAX`
  (`EXPR/conversion_funcs/numeric.rs:547-571`, `:602-615`); the narrow types go through `i32`
  first; LEGACY wraps through `as i32 as i8` (`:572-574`) **[V]**. Two consequences, both already
  in Varka's plans and confirmed at this commit: (a) `abs() > MAX` raises at `Int.MinValue` and
  `Long.MinValue`, where Spark's long-backed `roundToNumeric` returns the value
  (fork `sql/api/.../types/Decimal.scala:298-307`) **[V]**, PLAN.md 2.8 and `VARKA-350.md` 360;
  (b) above precision 18 Spark decides by the double (`Decimal.scala:308-317`) **[V]**, so a
  value such as `127.99999999999999999` at `(20, 17)` raises in Spark (the double is `128.0`)
  and is 127 in Comet **[I]**; `VARKA-364.md` 85-87 names the same class. TRY leaves that arm
  (`EXPR/conversion_funcs/cast.rs:397-401`) **[V]**, for Arrow's safe cast, the exact rule
  again **[I]**.
* **Why Comet's tests miss (a).** `TEST/CometNativeCastSuite.scala:1000-1004` casts
  `-2147483648.123123123` at `(38, 18)` to `INT`, which is case (a), but in one column with
  `-99999999999999999999.999999999999` (`:3070-3093`); under ANSI both engines raise on some row
  and the test compares only a 40-character message prefix (`:3362-3387`) **[V]**. A per-row
  disagreement behind an earlier raise is invisible. Section 5 takes the lesson.
* **Rounding of decimals is `HALF_UP` by `(x + sign(x) * half) / div`** for `round`
  (`EXPR/math_funcs/round.rs:216-233`), `div_ceil` and its floor twin for one-argument `ceil` and
  `floor` (`ceil.rs:79-82`) **[V]**. The two-argument `ceil` and `floor` (`RoundCeil`,
  `RoundFloor`) and `bround` are not native (`AUDIT/math_funcs.md:92`, `:147`, `:222`;
  `SERDE/math.scala:315`) **[V]**: Comet has no kernel for three of 351's five functions.
  Negative-scale decimals are Unsupported in every rounding, cast and division serde (#5050,
  `SERDE/arithmetic.scala:92-114`, `:404-418`) **[V]**, as Varka declines them
  (`census_curated.txt` 18).
* **`round` on an integral type with a negative scale wraps under LEGACY and raises under
  ANSI**, `round(5e18, -19)` giving the low 64 bits of `1e19` (`round.rs:286-316`) **[V]**. Row
  351 covers decimals; the `TINYINT`/`SMALLINT` form is not a row (section 10, action 7).
* **Aggregates.** Comet's three decimal `AVG` defects (#7017: `i128` rescale of a large sum, a
  skipped overflowing row at precision 28 and up) and the `SUM` note below are outside Varka's
  fused operators (349's owner decision of 1 November) **[V]**.

## 3. Floating semantics (342 to 346, 356 to 358)

* **The math functions run on Rust's `f64` methods and are marked Compatible.** The audit says
  so per function: "ULP-level differences vs DataFusion `exp` are possible but unflagged"
  (`AUDIT/math_funcs.md:132`), "Possible ULP differences from `StrictMath`" for `log10` (`:173`)
  and `pow` (`:198`) **[V]**; nothing guards `sin`, `cos`, `tan`, where Spark's `Math` is the JIT
  intrinsic on x86 **[V for the serde, I for the bits]**. Its SQL tests compare 27 of the math
  files with `tolerance=1e-6` (101 `tolerance` queries in all, `spark/src/test/resources/
  sql-tests/`), and that mode ignores the sign of zero and of infinity (`CG/sql-file-tests.md:
  255-270`) **[V]**. This is the ULP model PLAN.md 2.7.1 put behind an opt-in on 30 October;
  Comet is the evidence that a parity suite with a tolerance cannot see the difference.
* **The special-value fixes Comet had to make are Varka's existing cells.** `signum(-0.0)` is
  `-0.0` (#6522, `SERDE/math.scala:242-263`); `atan2` adds `+0.0` to both operands as Spark does
  (`:27-40`); `sqrt` of a negative is NaN, not an error (`:225-240`); the log family is null at
  `<= 0` by an `If(x <= 0, null, x)` rewrite (`:265-270`), so `-0.0` is null; `x % -0.0` is null
  under LEGACY only through a comparison in Spark's order (#6896, `a45445c6e`) **[V]**. Varka has
  `signum` and `sqrt` in `VarkaFloatingLaneSuite` over every pair of specials, `-0.0` included
  (`sql/core/.../VarkaFloatingLaneSuite.scala:76-81`, `:318-319`), and 356, 346 and 358 name the
  rest (row 356's `+ 0.0`; `VARKA-346.md` 58, 411; row 358) **[V]**.
* **`round` and `bround` of a `DOUBLE` or `FLOAT` decline in Comet.** Spark rounds the value of
  `Double.toString`, not the binary value, and Comet gives two vectors
  (`SERDE/arithmetic.scala:419-434`) **[V]**:
  `round(-5.81855622136895E8, 5)` is `-5.818556221369E8` (the shortest digits end in `...895`, the
  binary value in `...894941`); `round(6.1317116247283497E18, -5)` is, per the comment,
  `6.1317116247282995E18`. The second is a JDK fact: before JDK 19 `Double.toString` printed
  `6.1317116247283497E18`; the shortest form is `6.13171162472835E18`, so on JDK 19 and later
  Spark returns `6.1317116247284E18` **[C, I for Spark]**, and the exact binary value gives
  `6.1317116247283E18` **[C]**. Comet notes the JDK 19 boundary on its float-to-decimal cast too
  (`AUDIT/conversion_funcs.md:37`) **[V]**. Neither vector is in `VARKA-346.md` or a suite
  (grep, this branch) **[V]**: three answers from one input, the case 346's oracle header names.
* **Floating `ceil` and `floor` to `BIGINT`** use Rust's saturating `as i64` (`ceil.rs:38-45`),
  NaN to 0 and the extremes clamped, Java's `(long)` exactly **[V]**; nothing for 344.
* **NaN bits are a platform fact, not a corner.** A NaN computed at run time is
  `0xfff8000000000000` on x86-64 and `0x7ff8000000000000` on aarch64, Spark's Parquet writer
  canonicalises a stored NaN, and a sign-bit NaN is made in the query by negation
  (`CG/floating_point.md`, sections "How Arrow and DataFusion differ" and "Testing") **[V]**.
  Varka's lane suite builds its payload rows from bits through `createDataFrame` for this reason
  (`VarkaFloatingLaneSuite.scala:101-122`) **[V]**; the Spark-level fuzzer writes its fixture as
  `VALUES` text (`VarkaSparkFuzz.scala:102-124`) **[V]**, which can carry only the canonical NaN
  and its negation **[I]**. Section 10, action 4.

## 4. Comet's documented divergences on m8's types, as Varka cases

Each item: the case, Comet's source, and whether Varka covers it (coverage row, suite or named
plan test, read on this branch).

1. `CAST(DECIMAL(10,0) -2147483648 AS INT)` and `Long.MinValue` at `(19, 0)` to `BIGINT`, ANSI
   (`numeric.rs:603-604`). Named in `VARKA-350.md` 360 and `VARKA-364.md` 376; no suite yet.
2. `127.99999999999999999` at `(20, 17)` to `TINYINT`, ANSI, where Spark raises
   (`numeric.rs:547-571`). Named in `VARKA-364.md` 85-87.
3. `round(-5.81855622136895E8, 5)` and `round(6.1317116247283497E18, -5)`
   (`arithmetic.scala:424-433`). **Not covered.**
4. `greatest(x, x2), greatest(x2, x)` in **one** projection over `(-0.0, 0.0)`
   (`COMPAT/floating-point.md:109-112`). **Not covered** (below).
5. `signum(-0.0)`, `sqrt(-1.0)`, `atan2(-0.0, -1.0)`, `log(-0.0)` (`math.scala:27-270`).
   `signum` and `sqrt` covered by the lane suite's pairs; `atan2` named in row 356 (not built);
   `log` is 358's named cell.
6. `x % -0.0` and `pmod(-0.0, 3.0)`, LEGACY and ANSI (#6896). Named in `VARKA-346.md` 58, 411.
7. `CAST(d AS DECIMAL)` of NaN and the infinities under ANSI, null
   (`AUDIT/conversion_funcs.md:37`). Named in PLAN.md 2.7 (346).
8. Decimal to `DOUBLE` with the unscaled value past `2^53`, `12345.6789` at scale 18
   (`numeric.rs:945-1022`). Named for 366 (`READING_ARROW_RS.md` 2).
9. An ANSI error from a row past `LIMIT` (`COMPAT/index.md`, "ANSI errors from rows skipped by
   LIMIT", #7147). Fixed in Varka in m7 (`m7/VARKA-387.md`).
10. `round` of a `TINYINT` past its range at a negative scale: LEGACY wraps, ANSI raises
    (`round.rs:28-80`). No row (action 7).

Case 4 in detail. Spark canonicalises `Greatest` and `Least` with their children ordered
(fork `arithmetic.scala:1408`, `:1503`) **[V]**, and whole-stage `ProjectExec` eliminates common
subexpressions by that canonical form (fork `basicPhysicalOperators.scala:77-79`) **[V]**, so with
codegen on both columns are the value of whichever was evaluated, `-0.0` for both **[I]**, and
with codegen off the second is `0.0` **[I]**. Spark's two engines disagree, the 386 and 541 class
(`READING_SQLANCER.md` 2). `VarkaFloatingLaneSuite.scala:296-304` runs `greatest(a, b)` and
`greatest(b, a)` in separate queries only **[V]**.

## 5. How Comet tests parity, and what 352 and 541 should take

* **Shapes.** `checkSparkAnswerAndOperator` (same rows and the plan stayed native), "both throw
  or both agree" for ANSI with a 40-character message prefix (`TEST/CometNativeCastSuite.scala:
  3362-3387`), and SQL files whose modes assert the path (`expect_native`, `expect_dispatch`,
  `expect_fallback`) or the error: `expect_error` matches a substring and permits fallback,
  `expect_error_class` requires the same exception class, error class and SQLSTATE and no native
  exception in either cause chain (`CG/sql-file-tests.md:283-370`) **[V]**. Varka's differential
  compares the condition and reruns on one partition so the first raising row decides
  (`sql/core/.../VarkaSparkDifferential.scala:227-282`, `:478-480`, VARKA-381) **[V]**, which is
  already stronger than the prefix test.
* **Constant folding is off by default in Comet's SQL files**, and `NullPropagation` can be
  excluded per file, so a literal-only query reaches the engine and `-0.0D` stays a literal with
  its sign (`CG/sql-file-tests.md:130-160`; `CG/floating_point.md` "Testing") **[V]**.
* **The fuzzer is weak where m8 needs it.** Decimals are `new BigDecimal(r.nextDouble())` set to
  the scale: in `[0, 1)`, never negative, never near `10^p`, never null
  (`spark/src/main/scala/org/apache/comet/testing/FuzzDataGenerator.scala:266-268`) **[V]**; the
  default types hold two shapes, `(10, 2)` and `(36, 18)` (`:325-339`); doubles are specials plus
  `nextDouble()` in `[0, 1)`, with no subnormal and no NaN payload (`:251-264`); the suites use
  seed 42, 1000 rows, and turn `-0.0` off (`TEST/CometFuzzTestBase.scala:58-82`); the math fuzz
  pins ANSI off (`TEST/CometFuzzMathSuite.scala:29-32`) **[V]**. 352's sweep of seven decimal
  shapes at four null densities, `-0.0` and payloads on (PLAN.md 2.10 (c)), is the right answer
  to this, and nothing in Comet's generator is worth adopting.
* **Worth adopting** (section 10): per-row ANSI isolation as the rule, never a column that holds
  two raising rows; the condition and SQLSTATE compared, not a message; literal operands kept
  unfolded in a slice of 352's draws; a fixture path that keeps NaN bits.

## 6. Comet's fallback against Varka's declines

* **Granularity.** Comet decides per expression at plan time from types alone, and a decline it
  cannot dispatch takes the whole projection to Spark (`COMPAT/floating-point.md:130-139`, where
  the doc quotes a 15x slowdown for one `array_distinct`) **[V]**. Varka admits per expression at
  compile time and declines per batch at run time from a value guard, the batch going to Spark's
  own expression in the node's per-row fallback (`m7/VARKA-387.md` 2) **[V]**. Comet's codegen
  dispatcher is the same idea as that fallback (Spark's code, in the pipeline), reached by a
  static choice rather than a batch guard.
* **Value guards.** Comet has none in the planner; the closest are kernels that test a batch and
  take an exact path for the rest, as the float-to-decimal cast does (`READING_ARROW_RS.md` 2)
  and the `CheckOverflow` scan does (`AUDIT/math_funcs.md:46-48`) **[V]**. Varka's guarded
  declines (2.2, 2.6, 2.8) are the finer tool and lose nothing to Comet here.
* **Errors.** Comet raises from native code and must format Spark's message itself (section 1,
  #5276); Varka never raises from a kernel, so Spark's own text is raised. 360 to 364's ANSI
  designs should keep that rule, which is also what makes 387's deferred raise correct.
* **The documented divergence list is generated** from the serdes' reasons, but the ANSI cast
  table still marks its N/A cells by `Cast.canCast`, the LEGACY legality, and shows one decimal
  shape (`GenerateDocs.scala:457-490`) **[V]**: row 353's two cautionary cases, confirmed.
* **Spark's two engines.** Comet's notes name two places where Spark's codegen and interpreted
  paths differ on m8 types: the commutative `greatest`/`least` CSE (section 4, case 4) and
  ungrouped decimal `SUM`, which latches on the first out-of-precision running sum without
  codegen and checks only at the end with it (`COMPAT/index.md`, "Known result-value
  divergences") **[V]**. Both are entries for 541's three-way classification.

## 7. Worth taking

1. The two `round` vectors of section 3 and the JDK-25 answer **[C]**, for 346.
2. The one-projection `greatest`/`least` swap over signed zeros and two NaN payloads, for 342's
   lane suite and 541's classification.
3. The rule that an ANSI comparison holds one raising row per query; never a column with two.
4. `expect_error_class`'s check: condition and SQLSTATE, and no internal exception in the cause.
5. Constant folding off for a slice of fuzz draws, so literal operands reach the kernels.
6. The NaN-bits facts of `CG/floating_point.md`: computed NaNs carry the sign bit on x86-64, the
   Parquet writer canonicalises, negation makes a sign-bit NaN on every host.

## 8. Not worth taking

* The tolerance comparison and the "unflagged" ULP stance on `exp`, `log10`, `pow` (2.7.1 rejects
  it; Varka compares bits).
* The exact rule for decimal to integral casts above precision 18 (Spark's is the double rule).
* The fuzz generator's value distributions (section 5).
* Plan-time, type-only fallback of a whole projection.
* `DecimalRescaleCheckOverflow`: Arrow's single-pass cast, already read in `READING_ARROW_RS.md`.

## 9. What this sharpens or contradicts

* **Contradicts nothing in m8's plans.** PLAN.md 2.8's "Comet raises, Spark returns" at
  `Int.MinValue` and 2.10 (c)'s "Comet turns `-0.0` off" are both still true at `383ec5627`.
* **Sharpens 2.7.1**: Comet is a production engine that shipped the ULP model and needed a
  tolerance to pass its own suite; a sentence for the post (355) if the owner wants one.
* **Sharpens 352**: the fixture route matters for NaN payloads (section 3), and per-row ANSI
  isolation should be stated as a rule of the cross-mode properties (2.10 (d)).
* **Sharpens 351**: Comet has no `bround`, two-argument `ceil`/`floor` or float `round` kernel,
  so 351 and 346 have no second implementation to compare against beyond Spark itself.
* **Updates `READING_ARROW_RS.md` 2**: the wide path's ANSI error is now Spark's condition and
  value text (#5276).

## 10. Proposed actions

1. **Test, row 346.** Add `round(-5.81855622136895E8, 5) = -5.818556221369E8` and
   `round(6.1317116247283497E18, -5) = 6.1317116247284E18` (JDK 19 and later;
   `6.1317116247282995E18` before) to 346's named tests and its oracle header's JDK note. Small.
2. **Test, row 342 (lane suite) and 541.** `SELECT greatest(x1, x2), greatest(x2, x1), least(x1,
   x2), least(x2, x1)` in one projection over the pairs fixture, codegen on and off; expect
   Varka to equal whole-stage Spark, and record the codegen-off answer as a 541-class Spark
   disagreement if it differs. Small.
3. **Test, rows 350 and 364.** Put case 1 of section 4 in a suite now (it is named, not built),
   each value alone in its query, LEGACY, TRY and ANSI. Small.
4. **Row edit, 352.** State in 2.10 (c) that payload and sign-bit NaN columns come from a
   `createDataFrame` of bits (as `VarkaFloatingLaneSuite.scala:101-122`), since `VALUES` text and
   Parquet carry only canonical NaN; add a slice of draws with `ConstantFolding` excluded. Small.
5. **Row edit, 352 and 541.** Add to 2.10 (d): an ANSI check holds at most one raising row per
   query, and an error agreement compares condition and SQLSTATE (and that no Varka class sits
   in the cause chain), never a message prefix. Small.
6. **541 entries.** Add ungrouped decimal `SUM` with an intermediate overflow that cancels
   (codegen on against off) and case 4 above to 541's list of known Spark-engine disagreements,
   classified by the three-way run of `READING_SQLANCER.md`. Small.
7. **m9 scope item, served by 351's family.** `round`/`bround` of `TINYINT`, `SMALLINT`, `INT` and
   `BIGINT` at a negative scale (LEGACY wraps, ANSI raises), unclaimed by any m8 row; one line in
   `m9/SCOPE.md` unless the owner puts it in 351.

Not read: Comet's aggregates and window code beyond the compatibility notes, its JSON, string,
regex and datetime casts, `modulo_expr.rs` past the commit message, and the dispatcher's input
and output emitters.
