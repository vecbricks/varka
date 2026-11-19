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
   `|(double) a * (double) b|` is within a factor `(1 + 2^-53)^3` of `|a * b|`. Testing it
   against `2^62`, where the overflow edge is `2^63`, leaves a factor of two. So every product
   of `2^62 (1 + 2^-53)^3` or more trips, every overflow among them, and none below
   `2^62 / (1 + 2^-53)^3` does; in the band between, a lane may go either way (a product just
   below `2^62` can round up and trip, one just above with an operand past `2^53` can round
   down and pass). B therefore never admits an overflow, and declines products from about
   `2^62` up. No SMT file proves floating arithmetic in this repository; the argument is the
   written one above, and 5's edge tests hold its two sides.
5. **Where B's cost lands.** A tripped lane declines its whole batch, which then runs on the row
   path after the kernel's pass: on data whose products reach `2^62`, B costs a kernel pass more
   than A. The baseline's data never reaches it (`l` below `2^49`, `m` in `[-3000, 3000]`, so
   `|l * m|` is below `2^61`), so its sections price B's win and not its decline; 5 adds the
   section that prices the decline, which `VARKA-104.md` 7 risk 3 asks for.
6. **The domain rule.** Since row 610 (#1537) a coverage row's domain must equal its guards'
   condition. B's coverage rows take `|(double) a * (double) b| < 2^62` as their domain.

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

The emitter's arm converts both operands with the existing `ConvertLane` lowering (one
instruction at level 3, `wideConversionForm`'s form at level 2), multiplies, takes `ABS`, and
compares with the broadcast `0x1p62` (`GE`), the mask feeding the batch guard's existing decline
path; the value is the plain long `MUL`. The reference evaluator computes the same double test
per lane in Java, which gives the same bits as the vector lanes (IEEE round to nearest in both),
so the reference and the kernel decline the same batches.

A distinct node, not a mode of `IntArith`: an `IntArith(MUL, FAIL)` lowered this way would
decline batches its reference evaluator (`Math.multiplyExact`) answers, and every differential
test would then disagree with it.

### 3.2 The compiler and the option

`VarkaEmitOptions.checkedLongMultiply`, a choice `BOUND_ONLY` (A) or `MAGNITUDE` (B), reason
`MACHINE`, read by the compiler, default `BOUND_ONLY`. Under `MAGNITUDE`, `intArith` builds
`GuardedProduct` where `cannotOverflow` fails, for ANSI and TRY, in place of declining, at an
emit level that lowers `L2D` (2 or above; at the unnamed default level the conversion declines,
and so does B). A bounded product keeps today's unchecked `IntArith`. The default is flipped per
host in the last commit by 6.1's rule, or not at all.

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
| `VarkaEmitterLongLaneSuite`, `VarkaCoverageSuite` rows | 5 |
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
3. **Kernel and reference agree** on which batches decline, through the fuzzer: the option enters
   `VarkaEmitOption.TABLE`, so `VarkaIrFuzzSuite` draws it.
4. **Coverage rows** for `l * m`, `l * 3` under ANSI and `try_multiply(l, m)` under
   `MAGNITUDE`, their domain B's condition (item 2.6), at levels 2 and 3; at the unnamed level
   the row records the decline.
5. **The two-lane refusal holds**: under `declineTwoLaneConditions` the product declines at
   `lanesOverride=2`.
6. Every suite that lists the IR's node types runs before the push, since a part adding a node
   failed four of them on CI before (#1338).

## 6. The measurement

`VarkaBigintArithmeticBaselineBenchmark`, its three checked-product sections at both null densities
plus one new section, `l * m, ANSI, products near 2^62` (every batch holds one in `[1.01 * 2^62,
0.99 * 2^63]`, nothing overflows), each with three arms: the row path, A (`BOUND_ONLY`, which is the
row path for these sections) and B (`MAGNITUDE`), both forms named in the labels. Hosts: the EPYC
7763 at 256 bits (level 2), and an AVX512DQ host at 256 bits (level 3; question 1). The control is
the class's wrapping `l * m, LEGACY`, unchanged by the option. 128 bits is not run: item 2.3 settles
it.

### 6.1 Predictions, registered before the run

1. **Level 3 (AVX512DQ):** B is at least 3X the row path on `l * m` and `try_multiply(l, m)`
   ANSI without nulls, against LEGACY's 8.0X, since it adds about six vector ops to a kernel the
   load dominates.
2. **Level 2 (EPYC 7763):** B is at least 1.5X the row path on the same sections; the two
   `XOR_SPLIT` conversions cost more than the multiply, and less than the row path's 47 ns a
   row.
3. **The decline section:** B is slower than the row path by under 1.25 times at both levels,
   the kernel's pass before every declined batch.
4. **The rule:** B becomes the default on a host where predictions 1 or 2 hold at both null
   densities by at least 1.3X; elsewhere A stays and B stays as the arm. `VARKA-104.md` 9 records
   the choice per host either way.

## 7. Risks

1. **`twoLaneChecked`'s default.** It reads any node but `IntArith` and `IntNeg` as `WRAP`, so a
   `GuardedProduct` would pass the two-lane refusal silently and run its mask as the Java
   fallback. Test 5.5 catches it.
2. **The decline cost hides.** B on data near `2^62` is slower than A and looks only "fused";
   test 5.2 and the section of 6 are the defence, as `VARKA-104.md` 7 risk 3 asks.
3. **The conversion at the unnamed level.** At emit level -1 the `L2D` declines; B must decline
   there with a reason, not throw. Coverage row 5.4's unnamed-level case holds it.
4. **The domain check** (row 610): a domain wider than the guard fails the option matrix's
   `arrowBatchSize` configurations, as #1484's did. 5.4's rows take the guard's condition.

## 8. Sequencing

1. This plan (one commit).
2. One pull request: the node and its consumers, the option, the compiler arm, the tests and the
   decline section, default `BOUND_ONLY`; about 500 counted lines.
3. The measurement on the runners (owner-dispatched), its results committed, and the default per
   host with `VARKA-104.md` 9's record, in the same pull request if the cap allows, or a second.

## 9. Outcome

*To be written when the work lands.*

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
  an AVX512DQ host and on the EPYC 7763 at 256 bits;
- its decline rate against the row path is recorded (test 5.2 and the decline section), as
  `VARKA-104.md` 7 risk 3 requires;
- `VARKA-104.md` 9 records the choice per host, or B is dated as dropped.
