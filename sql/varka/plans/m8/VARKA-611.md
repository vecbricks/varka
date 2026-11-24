# VARKA-611: The checked BIGINT multiply's option B, the double-lane magnitude pre-check

Row 611 of `PLAN.md`, added on 14 November 2026 from the retro review of the skip commit
`cd12e2ba197` merged under #1484. `VARKA-104.md` 3.3 lists three forms for an ANSI or TRY
`BIGINT` multiply whose operands' bounds do not rule out overflow: A, decline (shipped); B, a
magnitude pre-check in double lanes that declines the batch where it trips; C, the 64 by 64 to
128-bit product from 32-bit limbs, which row 361 owns. No row owned B. This one does.

## 1. Where this came from

`VARKA-104.md` 9.1 records the checked multiply shipping as option A, "B and C are step 4's
remaining work", and 6.1 prediction 3 says "Option B loses to A at 128-bit and may win at
AVX-512". The runner reading of #1484 (`VARKA-104.md` 9.2) gives the size of what A leaves: on
the EPYC 7763 at 256 bits the three checked products over a column, `l * 3` and `l * m` under
ANSI and `try_multiply(l, m)`, decline and tie the row path at 1.0X, while their wrapping twins
fuse whole, `l * 3` LEGACY at 6.6X and `l * m` LEGACY at 8.0X
(`sql/core/benchmarks/VarkaBigintArithmeticBaselineBenchmark-jdk25-runner-epyc7763-results.txt`).

## 2. The admission check, done

Read on master at `e5d70012bdc`; paths under `sql/catalyst/src/main/java/.../codegen/`. Nothing
was run.

1. **A is still the only form.** `VarkaNodeCompiler.intArith` (`:1250` on) builds a checked
   `IntArith(MUL, ...)` on the long lane only where `cannotOverflow` proves it, and declines
   otherwise; the emitter throws for a checked non-narrow `MUL` (`varka/VarkaVectorWalk.java:1058`,
   `:1412`). No emit option names a multiply form. So the gap is the row's.
2. **The row's host restriction is stale, and B runs on the EPYC runners too.** The row says B is
   AVX512DQ-only and "the EPYC 7763 runners are AVX2 and cannot run it". That was the source
   reading of 16 October 2026 (`VARKA-104.md` 3.3, `PLAN.md` 10.1 item 4). Since #1424 and row
   343a4 the long-to-double conversion has exact AVX2 forms (`VarkaConversionLowering`,
   `wideConversionForm`, `XOR_SPLIT` by default), and `VARKA-104.md` 0 item 6 already notes that
   B "is buildable on the runners at the split's cost". They are emitted wherever the emit level
   is 2, and the session's default, `spark.sql.codegen.varka.emit.useAVX = -2`, resolves an AVX2
   host whose long species is 256 bits or more to level 2 (`VarkaSessionEmit.FALLBACK_LEVEL`,
   `SQLConf.VARKA_EMIT_USE_AVX`). So on the EPYC 7763 at 256 bits B is reachable in production at
   the conversion forms' cost, and on an AVX512DQ host (level 3) at one instruction a conversion.
   This corrects `VARKA-104.md` 0 item 6, which said B "cannot be the default" because the
   default, unknown level declines the conversion: the session's default is not that level on
   these hosts. That item carries a dated note saying so.
3. **At two long lanes on x86 B cannot help.** A checked `BIGINT` arm declines there under row
   503's setting (`VarkaNodeCompiler.twoLaneChecked`, `VARKA-104.md` 9.1), since C2 lowers no
   long-lane mask at two lanes, and B's trip test is a mask. So prediction 3's "at 128-bit" half
   is settled by construction: B declines with A there. The node must be named in
   `twoLaneChecked`, whose `default` case reads as `WRAP` (risk 1).
4. **B is sound with the margin `VARKA-104.md` 3.3 argues.** Each conversion of a long to double
   rounds to nearest, a relative error of at most `2^-53`, and so does the product: the computed
   `|(double) a * (double) b|` lies between `|a * b| (1 - 2^-53)^3` and `|a * b| (1 + 2^-53)^3`.
   Testing it against `2^62`, where the overflow edge is `2^63`, leaves a factor of two. So
   every product of `2^62 / (1 - 2^-53)^3` or more trips, every overflow among them, and none below
   `2^62 / (1 + 2^-53)^3` does; in the band between, a lane may go either way (a product just
   below `2^62` can round up and trip, one just above with an operand past `2^53` can round
   down and pass). B therefore never admits an overflow, and declines products from about
   `2^62` up. No SMT file proves floating arithmetic in this repository; the argument is the
   written one above, and 5's edge tests hold its two sides.
5. **Where B's cost lands.** A tripped lane declines its whole batch, which then runs on the row
   path after the kernel's pass: on data whose products reach `2^62`, B costs a kernel pass more
   than A. The baseline's data never reaches it (`l` below `2^49`, `m` in `[-3000, 3000]`, so
   `|l * m|` is below `2^61`), so its sections price B's win and not its decline; 6 adds the
   section that prices the decline, which `VARKA-104.md` 7 risk 3 asks for.
6. **The domain rule.** Since row 610 (#1537) a coverage row's domain must equal its guards'
   condition. `VarkaCoverageSuite` compiles every row under `VarkaEmitOptions.DEFAULTS`, at the
   unknown level, and its `Row` carries no options or level, so its rows never build B: they keep
   recording A's decline, with no guard and no domain. B's guard condition,
   `|(double) a * (double) b| < 2^62`, is asserted where B is built, in 5.4's long-lane tests.

The check would have rejected the row had a form already fused the three sections, or had the
conversions no lowering below AVX512DQ (then B would be laptop-only, as the row says). Neither.

### What differs from the row text

- B is built and measured on the EPYC 7763 at 256 bits too (item 2), not only on an AVX512DQ host.
- The AVX512DQ reading: the row names the laptop at 256 bits. Question 1 asks whether the Xeon
  8573C runner, an AVX-512 host the benchmark workflow can draw, takes it instead.

## 3. The design

### 3.1 The node and its lowering

A new value node on the long lane, `GuardedProduct(left, right)`: its value is the wrapping
product `a * b`, and its batch is declined (`Overflow.FAIL`'s action, as `GuardedRange`'s) where
a live lane has `|(double) a * (double) b| >= 2^62`. It is FAIL-only in every mode. Under ANSI the
row engine then raises on the row that overflows, or answers a batch that held none; under TRY
(`try_multiply`) it nulls the overflowing rows. A lane B is unsure of is never nulled in the
kernel, since it may not overflow.

The emitter's arm converts both operands with the existing conversion lowering (one instruction
at level 3; at level 2 the exact `XOR_SPLIT` form, whatever `wideConversionForm` says),
multiplies, takes `ABS`, and
compares with the broadcast `0x1p62` (`GE`), the mask feeding the batch guard's existing decline
path; the value is the plain long `MUL`. Not `MAGIC` at level 2: that form is exact only for
`|v| <= 2^51`, and its window is a `VALUE_RANGE` refusal that `Analysis` registers on a real
`ConvertLane` node, with guard scratch `Slots` plans for it; a conversion inside `GuardedProduct`
has neither, so the node fixes the exact form and the option matrix's `MAGIC` configuration
changes nothing in it. The reference evaluator computes the same double test
per lane in Java, which gives the same bits as the vector lanes (IEEE round to nearest in both),
so the reference and the kernel decline the same batches.

`VarkaKernelPlan.conversionRefusal` refuses a `BIGINT` conversion at the unknown level by finding
a `ConvertLane` in the tree, and `GuardedProduct` holds its two `L2D` inside its own lowering. So
that walk learns the node too: a `GuardedProduct` at the unknown level is refused with the same
`CONVERSION` reason, and the compiler's level gate (3.2) is not the only protection.

A distinct node, not a mode of `IntArith`: an `IntArith(MUL, FAIL)` lowered this way would
decline batches its reference evaluator (`Math.multiplyExact`) answers, and every differential
test would then disagree with it.

### 3.2 The compiler and the option

`VarkaEmitOptions.checkedLongMultiply`, a choice `BOUND_ONLY` (A) or `MAGNITUDE` (B), reason
`MACHINE`, read by the compiler, default `BOUND_ONLY`. Under `MAGNITUDE`, `intArith` builds
`GuardedProduct` where `cannotOverflow` fails, for ANSI and TRY, in place of declining, at an
emit level that lowers `L2D` (2 or above; at the unnamed default level the conversion declines,
and so does B). A bounded product keeps today's unchecked `IntArith`.

How a session reaches it. A session's options are `DEFAULTS` with `VarkaSessionEmit`'s settings
(the level, the refusal status, the math contract), which names no multiply form, so no new
configuration is added for an arm. The benchmark's B arm sets the option through
`VarkaArrowSessions.withEmitOptions`, as the class's other option arms do. A per-host default
needs no configuration either, since the session already resolves the host to a level: if 6.1's
rule chooses B at one level and not the other, the choice gains a third value, `BY_HOST`, made
the default, which `VarkaNodeCompiler` resolves to B at the levels the rule chose and to A
elsewhere, so an explicit `BOUND_ONLY` arm stays A. It resolves to B only on x86: aarch64 reports
level 3 as an AVX512DQ host does (`VarkaSessionEmit.resolve`), and two-lane NEON hosts build B
under no two-lane refusal (`twoLaneChecked` is x86's), so an Arm host keeps A until B is measured
there. The default changes in the last commit by that rule, or not at all.

### 3.3 Alternatives considered, not built

- **A tighter threshold**, `2^63 (1 - 2^-50)`: it trips only within about `2^-50` of the edge,
  so it declines almost nothing B's `2^62` would, but its soundness rests on a tighter error
  bound. Built only if 6's decline section shows `[2^62, 2^63)` products are common enough to
  matter; no committed workload has them.
- **A bit-length test**, `64 - lzcnt|a| + 64 - lzcnt|b| <= 63`, integer ops only, which would
  avoid the conversions on AVX2. Coarser (it declines products down to `2^62` divided by up to
  four) and its `LEADING_ZEROS_COUNT` lowering at long lanes on AVX2 is unread **[I]**. Kept as
  the candidate if B loses on the EPYC runner.
- **C**, row 361's limb product: exact, no decline; it stays that row's.

### 3.4 What is deliberately unchanged

The bounded products (A's fusions), the int lane's checked multiply (rows 95 and 28), the
two-lane refusal (row 503), and row 361's limb helper.

### 3.5 Registered op counts

From `dev/varka_emit.sh --table --variant checkedLongMultiply=MAGNITUDE` at the build, for
`l * m` under ANSI at levels 2 and 3. Registered before the emitter is written, by the design:
level 3, two `L2D`, one double `MUL`, one `ABS`, one compare, the guard's mask test, one long
`MUL`, against A's decline; level 2, the same with each `L2D` replaced by `XOR_SPLIT`'s form.

## 4. Files

| file | what |
|---|---|
| `varka/VarkaVectorIR.java` | `GuardedProduct`, its canonical form and its rebuild |
| `varka/VarkaVectorWalk.java` | its lowering |
| the reference evaluator, the cost and width tables, the warm-up's reach | its other consumers |
| `varka/VarkaEmitOptions.java`, `varka/VarkaEmitOption.java` | `checkedLongMultiply` |
| `VarkaNodeCompiler.java` | the arm under the option; `twoLaneChecked` names the node |
| `VarkaKernelPlan.java` | `conversionRefusal` refuses the node at the unknown level |
| `VarkaIrGrammar.java` (`LongShapes`) | the fuzzer draws the node |
| `VarkaEmitterLongLaneSuite` | 5 |
| `VarkaBigintArithmeticBaselineBenchmark.java` and its queries suite | the decline section |
| generated: `coverage.json`, `emitted_bytes.json`, `width_audit.json`, census | regenerated |

## 5. Tests, and what each is for

1. **The edges**, at 2, 4 and 8 long lanes and both bodies, against `Math.multiplyExact`:
   `2^31 * (2^31 - 1)`, exact in doubles and below `2^62`, must pass; `2^31 * 2^31` trips (`GE`)
   and declines; `Long.MIN_VALUE * 1` passes only if below the bound (it is not, so it declines);
   `-1 * Long.MIN_VALUE` overflows and must decline; a pair above `2^53` whose doubles round
   across `2^62`; every sign pair. The failure it catches: a lane that overflows and is
   answered.
2. **The decline-rate assertion** (`VARKA-104.md` 7 risk 3), on the sound sides of 2.4 only:
   over fixed 4,096-row fixtures, every batch holding a product of at least `2^62 (1 + 2^-50)`
   declines, and no batch whose products all stay below `2^62 (1 - 2^-50)` does. The fixtures
   keep every product out of the rounding band, so the assertion is exact. The decline section
   of 6 draws its products from `[1.01 * 2^62, 0.99 * 2^63]`, inside the declining side.
3. **Kernel and reference agree** on which batches decline, through the fuzzer:
   `GuardedProduct` joins `VarkaIrGrammar.LongShapes`, so `VarkaIrFuzzSuite` draws the node
   itself and checks the kernel's declines against the reference evaluator's. The option alone
   would not do it: the fuzzer draws IR trees and never runs the compiler, the option's only
   reader.
4. **The compiled arms**, in `VarkaEmitterLongLaneSuite` (the coverage suite compiles at the
   defaults only, item 2.6): `l * m`, `l * 3` under ANSI and `try_multiply(l, m)` compiled under
   `MAGNITUDE` at levels 2 and 3 build `GuardedProduct`, and its guard condition is B's, held
   against Arrow batches of 1, 3 and 4,096 rows as the option matrix's `arrowBatchSize` cuts
   them; at the unknown level each declines with the `CONVERSION` reason, from the compiler and,
   for a hand-built node, from `conversionRefusal`. The coverage rows keep A's decline.
5. **The two-lane refusal holds**: under `declineTwoLaneConditions` the product declines at
   `lanesOverride=2`.
6. Every suite that lists the IR's node types runs before the push, since a part adding a node
   failed four of them on CI before (#1338).

## 6. The measurement

`VarkaBigintArithmeticBaselineBenchmark`, its three checked-product sections at both null densities
plus one new section, `l * m, ANSI, products near 2^62` (every batch holds one in `[1.01 * 2^62,
0.99 * 2^63]`, nothing overflows), each with three arms: the row path, A (`BOUND_ONLY`, which is the
row path for these sections) and B (`MAGNITUDE`), both forms named in the labels. Hosts: the EPYC
7763 at 256 bits (level 2; question 2), and an AVX512DQ host at 256 bits (level 3; question 1).
The control is the class's wrapping `l * m, LEGACY`, unchanged by the option. 128 bits is not
run: item 2.3 settles it.

### 6.1 Predictions, registered before the run

1. **Level 3 (AVX512DQ):** B is at least 3X the row path on `l * m` and `try_multiply(l, m)`
   ANSI at both null densities, against LEGACY's 8.0X, since it adds about six vector ops to a
   kernel the load dominates.
2. **Level 2 (EPYC 7763):** B is at least 1.5X the row path on the same sections at both null
   densities; the two `XOR_SPLIT` conversions cost more than the multiply, and less than the row
   path's 47 ns a row.
3. **The decline section:** every batch pays B's kernel pass and then the row path, so at a
   speedup `s` for B on the same host the section reads at most `1 + 1/s` times the row path:
   under 1.34 at level 3 (prediction 1's 3X) and under 1.67 at level 2 (prediction 2's 1.5X).
   It is scored against the `s` the run measures, so a miss of 1 or 2 does not fail it by
   construction.
4. **The rule:** B becomes the default at an x86 level where it reads at least 1.3X the row path
   on each of the three checked sections at both null densities, a bar below predictions 1 and 2
   so that missing a prediction alone does not drop B, and where the decline section stays within
   prediction 3's `1 + 1/s`, so a declined batch costs no more than one kernel pass; elsewhere A
   stays and B stays as the arm. That pass is a slowdown against A for a workload whose products
   reach `2^62`, which no committed workload holds (3.3); `VARKA-104.md` 9 records it beside the
   choice per host either way.

## 7. Risks

1. **`twoLaneChecked`'s default.** It reads any node but `IntArith` and `IntNeg` as `WRAP`, so a
   `GuardedProduct` would pass the two-lane refusal silently and run its mask as the Java
   fallback. Test 5.5 catches it.
2. **The decline cost hides.** B on data near `2^62` is slower than A and looks only "fused";
   test 5.2 and the section of 6 are the defence, as `VARKA-104.md` 7 risk 3 asks.
3. **The conversion at the unnamed level.** At emit level -1 the `L2D` declines; B must decline
   there with a reason, not throw, both where the compiler builds it and where a node reaches the
   kernel plan another way (a fuzzer draw, a hand-built test). 5.4's unknown-level case holds both.
4. **The domain check** (row 610): a domain wider than the guard fails the option matrix's
   `arrowBatchSize` configurations, as #1484's did. The coverage rows never build B (item 2.6),
   so the check does not reach it; 5.4 asserts B's condition at the matrix's batch sizes instead.

## 8. Sequencing

1. This plan (one commit).
2. One pull request: the node and its consumers, the option, the compiler arm, the tests and the
   decline section, default `BOUND_ONLY`; about 500 counted lines.
3. The measurement on the runners (owner-dispatched), its results committed, and the default per
   host with `VARKA-104.md` 9's record, in the same pull request if the cap allows, or a second.

## 9. Outcome

### 9.1 What step 2 built (20 November 2026, while the owner was away)

Built on `varka-611-guarded-product` against master `767dcddce50`, local only.

- **The node.** `VarkaVectorIR.GuardedProduct(left, right)`, long lane only, no mode; it renders
  as `(guardedMul a b)`, with `BOUND = 2^62` and `trips(a, b)`, the Java form of the kernel's
  test that the reference side reads. Every exhaustive switch over the IR names it (the
  compiler found them: `Analysis`, `Slots`, `VarkaKernelPlan`'s two long-narrow switches,
  `VarkaEmitCost`, `VarkaEmitBudget`, `VarkaRangeAnalysis`, `VarkaSameAnswer`,
  `VarkaOperandPosition`, `VarkaChronoCompiler`, and the test tree's reference, guard specs,
  canonical parser, word census, SMT refusals and grammar).
- **The lowering** (`VarkaVectorWalk.emitGuardedProduct`) parks nothing: the long `MUL` is taken
  off a `dup2` and sunk under the operands with `dup_x2`, the operands are converted where they
  lie, multiplied as doubles, and the magnitude is the product's bits with the sign cleared
  (`AND Long.MAX_VALUE`) compared as a long `GE` with `2^62`'s bits. That replaces 3.1's `ABS`
  and double compare by one `AND` and a long compare: the same lanes for every non-negative
  double up to the infinity, and the mask is on the driving long lane, so the guard collect
  needs no mask cast. The conversion is `VarkaConversionLowering.emitExactL2D`: one
  `convertShape` at level 3, `XOR_SPLIT` below it whatever `wideConversionForm` says.
- **The refusal.** The node registers `INT_OVERFLOW` unconditionally (not behind
  `checkIntOverflow`: the compiler admits the multiply on its strength, as a range guard's), so
  a tripped batch returns `STATUS_INT_OVERFLOW` and the row engine raises or nulls.
- **The option.** `VarkaEmitOptions.checkedLongMultiply`, `BOUND_ONLY` (default) | `MAGNITUDE` |
  `BY_HOST`, reason `MACHINE`, tag `checkedLongMultiply=`, subject level 2;
  `guardsLongProducts(x86)` resolves it. The projection compile's `DeclineSink` reads it
  (`VarkaNodeCompiler.guardsProduct`), and `twoLaneChecked` names the node as `FAIL`.
- **The kernel plan.** `conversionRefusal` refuses a `GuardedProduct` at the unknown level with
  `Kind.CONVERSION` and `GUARDED_PRODUCT_UNKNOWN_LEVEL`.
- **The fuzzer.** A draw of its own, `drawGuardedProductShape` on
  `GUARDED_PRODUCT_FUZZ_SEED`: there `LongShapes`' multiply arm builds the node half the time
  where the product's bound may leave the long. `VarkaIrFuzzSuite` runs it at levels 2 and 3 in
  turn, holding each body's status to `VarkaGuardSpecs`' verdict (its arm for the node is
  `trips`) and an answered batch to the reference. Where 5.3 had the node join the committed
  long corpus, the review found two reasons against: that corpus is emitted at the unknown
  level, where the kernel plan refuses the node, so the bytes oracle, the cost corpus and the
  budget suites would meet a refusal; and the extra draw would move every committed
  `fuzz_long` block. On its own seed, no committed shape moves.
- **The benchmark.** `VarkaBigintArithmeticBaselineBenchmark`'s three checked products take a
  third arm, `varka on, B (checkedLongMultiply=MAGNITUDE)`, checked to plan a kernel where a
  checked `BIGINT` operation fuses on the host, and one section prices B's decline,
  `l * m, ANSI, products near 2^62, no nulls`, over two new columns `nb` and `nm` whose
  products lie in `[1.01 * 2^62, 1.95 * 2^62]`. The queries suite runs the new section and
  checks the B arm's plan class. Not run here: the readings are the runners'.

Not run in this window, and owed (8 step 3): the benchmark on the Xeon 8573C (AVX512DQ, level
3, question 1) and on the EPYC 7763 (level 2, the B arm, question 2), owner-dispatched; then 6.1's
predictions are scored, the default per host is chosen by prediction 4's rule, and `VARKA-104.md`
9 records the choice.

**Tests in this window.** `VarkaEmitterLongLaneSuite` passed (13), before the review's fixes;
`VarkaIrFuzzSuite` passed after them (42, 2 canceled), its guarded draw over 300 iterations
included; catalyst and sql/core test sources compile. Owed under the owner's order of 07:30 CET:
the long-lane suite again, every suite that lists the node types, the regeneration with the
width audit under `UseAVX=2`, `VarkaBigintArithmeticBaselineQueriesSuite`, and a second
`/code-review high` over the fix round.

**Gate findings (22 November 2026).** After the rebase, `VarkaBigintArithmeticBaselineQueriesSuite`
failed on the B arm: `l * 3, ANSI, no nulls` planned `NONE` where the suite expected `WHOLE`. The
cause was a stale expectation, not a defect. The shared test sessions pin
`spark.sql.codegen.varka.emit.useAVX=-1`, the unnamed default level, at which
`guardsLongProducts` is false by design (3.2) and the kernel plan refuses the conversion, so B
declines there exactly as A does; the benchmark's own sessions resolve the host's level (-2), a
named one. The suite now holds both: under `MAGNITUDE` at the unnamed level the plan keeps the
section's own class, and at level 2 it is `WHOLE` (`NONE` where the host declines two-lane
checks). The suite passed (2 tests) on the laptop. The node, the benchmark class and its results
files needed no change.

### Decisions taken while the owner was away (20 November 2026)

1. **`BY_HOST` is built now, not after the readings, and is not the default.** The coordinator's
   brief named the choice; 3.2 makes it the default only once 6.1's rule chooses. It resolves to
   B on x86 at levels 2 and 3 (`GUARDED_PRODUCT_LEVELS`), the levels predictions 1 and 2 expect
   B to win at, and to A elsewhere; the default stays `BOUND_ONLY`, so no session changes. When
   the readings land, the set is narrowed to the levels the rule chose and the default flips in
   that commit, or `BY_HOST` is removed if B is dropped.
2. **At the unknown level the compiler keeps A's decline reason.** 5.4 asked for the
   `CONVERSION` reason from the compiler; the compiler never builds the node there
   (`guardsLongProducts` is false), so the multiply declines as A does, with A's reason, and the
   `CONVERSION` refusal comes from the kernel plan for a node that reaches it another way. One
   reason per cause: the compiler's says the multiply was not built, the plan's says the node
   cannot run.
3. **Projections only.** The filter compile (`compilePredicate`) builds its sink without the
   options and keeps A: a checked product in a filter's condition is not one of 6's sections and
   would add a conversion to the two-lane condition refusal's ground.
4. **The magnitude as bits, not `ABS` and a double compare** (9.1), and **`INT_OVERFLOW`, not a new
   refusal reason**: a new status bit would touch the evaluator's reason table for no reader
   that distinguishes the two.
5. **Checks in `VarkaEmitterLongLaneSuite` for 5.1, 5.2, 5.4 and 5.5,** including the compiled
   arms, which compile Catalyst expressions there rather than in `VarkaExpressionCompilerSuite`,
   so the arms' IR and their batches of 1, 3 and 4,096 rows sit in one test.

### 9.2 Reverted (23 November 2026)

#1626 (squash commit `c4b0475d86e`) was reverted on 23 November 2026, by the pull request that
adds this section, at the owner's word, so that master is green before row 489. Row 611 must be
redesigned before it lands again. The code that 9.1 and the decisions above describe is no
longer on master; they stay as the record of what was built. The code that merged is branch
`varka-611-guarded-product-r`, not 9.1's first build `varka-611-guarded-product`.

- **The pin covered none of its code.** Its three option arms,
  `checkedLongMultiply=BOUND_ONLY`, `MAGNITUDE` and `BY_HOST` over `useAVX=2`, emit the same
  bytes as `useAVX=2` on every pinned shape. On `c4b0475d86e`, `sql/varka/emitted_bytes.json`
  has 27 option arms and 11 distinct digests, and 14 arms carry `useAVX=2`'s digests, including
  `useAVX=2` itself. So the pin paid for three full emission passes and caught no change to the
  guarded product. The redesign must add a pinned shape whose bytes the guarded product
  changes. The other 10 arms that share `useAVX=2`'s digests have the same flaw; that is for
  the split below.
- **The pin test had no headroom on AVX2 runners.** `VarkaEmittedBytesSuite`'s pin test took
  511 to 551 s on AVX2-only runners before #1626, against a 600 s cap. After #1626 it is about
  615 to 650 s, an extrapolation since the red runs stop at the cap. Master run 45256702183
  failed 3 of 3 attempts, all on EPYC 7763s. A new machine option with a non-default subject
  must not land until the pin test is split or cached. The split is branch
  `varka-emitted-bytes-budget`, not yet a pull request.
- **The default per host belongs to the per-host fuse-or-decline row** (fuse where faster, else
  decline), row 648 on branch `varka-rows-fuzz-confs-faster`, not yet on master.
- **Keep two parts in the redesign.** The conformance register's fix
  (`VarkaTypeConformance.guardsProduct`: a form a non-default option fuses is held to the
  reference, not reported as drift) and the check that holds each batch's status to the guards'
  verdict (`guardVerdict`), not to where Spark raises, while still requiring a decline wherever
  Spark raises.

## 10. Questions for the owner

1. **The AVX512DQ host.** The row names the laptop at 256 bits. A committed reading is a
   runner's by the house rule, and the Xeon 8573C runner is an AVX-512 host the benchmark
   workflow can request (`expected-cpu`), though row 577 drew the wrong CPU six times running.
   Recommended: the Xeon runner, with the laptop at 256 bits as the fallback reading if two
   retry chains miss, said so in 9. Not decided here: the dispatch is the owner's.
2. **B on the EPYC runner** (2.2 and "What differs"). Recommended: yes, measured and chosen per
   host there too, since production resolves that host to level 2, where B compiles; the row
   was written before that reading. The owner set the row's scope, so the widening is his.

## 11. Done when

- B is built over the two double lanes behind `checkedLongMultiply`, and measured against A on
  an AVX512DQ host, and on the EPYC 7763 at 256 bits if the owner answers question 2 yes (the
  row's own Done-when says the EPYC runners cannot run B, the reading 2.2 corrects);
- its decline rate against the row path is recorded (test 5.2 and the decline section), as
  `VARKA-104.md` 7 risk 3 requires;
- `VARKA-104.md` 9 records the choice per host, or B is dated as dropped.
