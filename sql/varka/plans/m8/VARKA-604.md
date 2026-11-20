# VARKA-604: The decimal remainder at two long lanes on x86, as split F behind an option

Row 604 of `PLAN.md`, added on 11 November 2026 on the owner's word, his answer (c) to question
7.4 of `VARKA-577.md`: by default row 577's 128-bit part (577b) declines `%`, `mod`, `pmod` and
`try_mod` over the 128-bit pair at two long lanes on x86 (`VARKA-359.md` 2.6), and this row adds
the form "The test of 7.4" measured (`VARKA-577.md` 10.4) as an opt-in arm: split F, E's
arithmetic on 0/1 lanes with no mask, fed by a transpose of the slots into split low and high
blocks in a method of its own and copied back in another.

## 1. Where this came from

At two long lanes on x86 C2 lowers no rearrange, compare, blend or mask op, so E and D, the
remainder forms 577b builds at four lanes and up, run as the Vector API's Java fallback
(`VARKA-577.md` 10.4). F has none of those ops and compiles. On the EPYC 7763 at 128 bits split F
read 42.4 to 43.8 ns a row for `x % y` against `BigInteger`'s 276.1 to 280.9, allocating nothing
in that run's JVM (`VARKA-577.md` 10.5; the spike's `-runner-epyc7763-128bit-results.txt`),
and the row path costs 771.3 ns a row at `DECIMAL(38, 10)` (the decimal baseline's
`-runner-epyc7763-128bit-results.txt`). But after unrelated
vector code in the same JVM it kept 16 bytes a row at two lanes and 12 at four in every JVM of
the laptop's test, one `long[L]` a lane group (10.4, "The test of 7.4"), 16 times the per-row
term of the warm-up's allocation allowance (`VarkaAllocationSampler.BYTES_PER_ROW_ALLOWANCE`, 1
byte, beside the fixed 4,096). That is why the owner made it opt-in, not the default.

## 2. The admission check, done

From master at `e5d70012bdc`. The question that decides whether the row can meet its done-when is
whether split F can pass "a shared-JVM allocation check, run after other vector code", given
10.4's residual. 10.4 left the deciding case untested: "whether the 295 warm-up's template
pre-warming (#827, the preferred species only) protects a two-lane kernel is untested". Every
Varka JVM runs that warm-up (`VarkaVectorTemplateWarmup.ensure`, from `VarkaKernelWarmup`'s
worker) before its first kernel, so a check that skips it tests a JVM production never is.

**The probe.** `results/VARKA-604/Probe604.java`, built by `results/VARKA-604/build.sh` from this
checkout and run by `results/VARKA-604/run.sh`, three JVMs a mode on the laptop at `-XX:UseAVX=2
-XX:MaxVectorSize=16` (and 32), the readings, the stubs it compiles against and the commands in
`results/VARKA-604-admission.txt`. It runs the spike's own `loopRemainderFS` over 4,096 Arrow-layout
`DECIMAL(38)` slots a block at a time, through a transpose into two reused split blocks and back,
each in a method of its own, after a chosen polluter, with the template warm-up before it, after it
or not at all, and reads the thread's allocation over 256 drives, checking every row against
`BigInteger`. The laptop was at a load of 17 to 20; the bytes a row do not depend on it.

**What it found** (JVMs that allocated, of those run; every answer exact):

| before split F | block | without the warm-up | with it |
| --- | --- | --- | --- |
| nothing, or three plain add loops | 64 | 0 / 6 | 0 / 6 |
| the spike's boxing arms (inserted F and E, transposed F) | 64 | 0 / 3 | 0 / 3 |
| a lanewise loop over seven species (the polluter) | 64 | 8 / 9, 16 bytes a row | 0 / 15 |
| the polluter, then the warm-up | 64 | | 0 / 6 |
| the polluter | 256 | 2 / 3, 16 and 32 | 0 / 3 |
| the polluter | 1,024 | 3 / 3, 32 | 3 / 3, 16 |
| the polluter, the loop called a 64-row sub-block | 1,024 | 3 / 3, 17.88 | 3 / 3, 1.88 * |
| the polluter, at four lanes (`MaxVectorSize=32`) | 64 | 2 / 3, 12 | 0 / 3 |

\* 1.88 bytes a row is the probe's own three `MemorySegment` slices a call, in every JVM of that
kind; the residual above them is 0 with the warm-up and 16 without.

1. **The residual reproduces**, at 10.4's exact sizes, but not from three plain add loops: the
   polluter that brings it back profiles the shared templates over seven species. 10.4's add
   loops were not committed, so this polluter stands in for them.
2. **The template warm-up removes it** when the loop runs 64 rows a call, at blocks of 64 and
   256 rows and in sub-blocks of 64 from a 1,024-row transpose, at two lanes and four, and
   whether it runs before the polluter or after it.
3. **It does not when the loop runs 1,024 rows a call**: 16 bytes a row remain with the warm-up.
   So the rows a call matter, not the transpose's size. Why is not read (no compile log).

**The verdict.** The row holds: the arm is buildable to its done-when, on two conditions this
plan adopts as its design - the loop runs 64 rows a call (3.1), and the allocation check runs
in a JVM with the template warm-up, after the polluter of item 1 (5.3). Had the warm-up not
removed the residual at any block size, the plan would have recommended closing the row.

**Prerequisites, on master or not.** No decimal remainder fuses on master at any width (the
census's `%`, `mod`, `pmod`, `try_mod` lines). The 128-bit pair's lane and leaves (row 359a,
parts 2 to 4) are on local branches, row 360's rescale is being rebased, row 481's null and
raise modes are started, and 577b, which this row's option changes, is not built. So this row
is planned now and built after 577b (8; question 1).

### What differs from the row text

- The row says the check is "run after other vector code"; the admission adds that the JVM must
  run the template warm-up first, as every Varka JVM does, and names the polluter (5.3).
- The row's "reuses its block buffer instead of allocating one a call" holds, and the block is
  fixed at 64 rows by item 3, not left to tune.

## 3. The design

### 3.1 The mechanism

- **A runtime helper**, `VarkaDecimalRemainderOps` in the catalyst Varka package, holding F's
  arithmetic as `VarkaDecimalRemainderSpikeBenchmark.bodyF` has it with `Access.SPLIT` (its
  exactness argument is E's, `VARKA-577.md` 2.3 and 10.2; the SMT proof `VARKA-577.md` 3.1 owes
  covers both, F being E value for value), two out-of-line methods `remainder64` and `pmod64`
  that run 64 rows over split blocks, and the two transposes. Its driver walks a batch 64 rows at
  a time: transpose the block's x and y slots into split blocks, call the 64-row loop, transpose
  the result and the zero-divisor word back. The three blocks (3 KB) are one per thread, in a
  `ThreadLocal`, as `VarkaScratch` keeps its buffer, so a call allocates nothing.
- **The option**, `VarkaEmitOptions.decimalRemainderTwoLane`, a flag, reason `MACHINE`, default
  false, read by the compiler. Under it, at two long lanes on x86 (the setting 577b's decline
  reads), the pair remainder of 577b's node lowers to one call of the helper's driver over the
  batch, outside the kernel's lane-group loop, as the decimal store's widening pass is called
  (`VarkaDecimalStoreBenchmark`'s `Buffers.widen`); elsewhere nothing changes.
- **The shapes.** A remainder whose two operands are pair columns at one scale, as a root value:
  the baseline's `x % y` and `pmod(x, y)` at `DECIMAL(38, 10)` and `DECIMAL(20, 2)`. A remainder
  under another node, or over a computed operand, keeps 577b's two-lane decline with its reason,
  since the call writes a column, not a lane group (question 2).
- **The modes.** The helper sets the zero-divisor bits; 577b's own handling of them (null, or
  raise under ANSI through row 481's modes, `try_mod` null) reads the same word, unchanged.
- **The benchmark arm.** `VarkaDecimalRemainderSpikeBenchmark` gains "split F, transposed out of
  line" for both operations, calling the helper's driver, the committed arm the row asks for in
  place of 10.4's throw-away driver.

### 3.2 Alternatives considered

- **Blocks of 1,024 rows, or a whole-batch transpose**, to call the loop less often: item 3 of 2
  shows the residual comes back when the loop runs 1,024 rows a call. Rejected.
- **Lowering F into the IR, lane group by lane group**, inside the kernel's loop: F's `x % y`
  body is 199 Vector API call sites (`VARKA-577.md` 10.4, "Can emitted kernels reach this
  size?"), past the emitter's 93-site budget a group method, and the kernel's loop would run the
  batch's rows a call, which is item 3's failing case. Rejected for this row.
- **B, `BigInteger` a row, behind the option**: the owner declined it (7.4).

### 3.3 What is deliberately unchanged

577b's default, its four-lane-and-up forms and its two-lane decline with the option off; the
long representation's remainder (577a); the other pair operators at two lanes (row 596's).

### 3.4 Registered op counts

None in the kernel's loop: the remainder leaves it. The emitted change is one static call and
its arguments, read with `dev/varka_emit.sh --options decimalRemainderTwoLane=true` at
`lanesOverride=2` when built.

## 4. Files

| file | what |
|---|---|
| `varka/VarkaDecimalRemainderOps.java` (new) | F's 64-row loops, the transposes, the driver |
| `varka/VarkaEmitOptions.java`, `varka/VarkaEmitOption.java` | the option and its table entry |
| 577b's compiler arm and emitter arm | the call under the option at two lanes |
| `VarkaDecimalRemainderSpikeBenchmark.java` | the arm "split F, transposed out of line" |
| 577b's conformance suite, a new allocation suite | 5 |
| `results/VARKA-604/`, `results/VARKA-604-admission.txt` | the admission probe, this commit |

## 5. Tests, and what each is for

1. **The helper against `BigInteger`**, over the spike's edge rows (`row(i, r)`: one-word and
   two-word values, a low word with its sign bit set, divisors of 1, `10^k`, the dividend and
   zero, every sign pair), at batch lengths 64, 4,096 and one not a multiple of 64 (the driver's
   tail), for `x % y` and `pmod`. Catches a wrong word or a wrong tail.
2. **577b's conformance tests with the option on**, at both representations, in an `ownJvm` at
   `-XX:MaxVectorSize=16 -XX:UseAVX=2`: the pair's shapes go through the helper, the long's are
   unchanged. Catches a mode (null, raise, `try_mod`) the call path breaks.
3. **The shared-JVM allocation check** (the done-when's), `ownJvm` at the same flags: the
   template warm-up as `VarkaKernelWarmup`'s worker runs it, then the polluter of 2 item 1 (moved
   from the probe into the test tree), then the kernel warmed through `VarkaKernelWarmup`; it
   asserts the warm-up's verdict is compiled, not released, and that
   `VarkaAllocationSampler.suspect` is false over 4,096-row calls. The control that the polluter
   bites without the warm-up stays a probe reading (2), not a test, since it reproduces in 8 of 9
   JVMs and not 9.
4. **The fuzzer draws the option** once it is in `VarkaEmitOption.TABLE`; at the suites' widths
   it changes nothing, and the 128-bit matrix configuration exercises it.
5. **The spike's intrinsic check** lists no Java call for the new arm at 128 bits.

## 6. The measurement

On the EPYC 7763 at 128 bits (`-XX:MaxVectorSize=16`), owner-dispatched:
`VarkaDecimalRemainderSpikeBenchmark`, the new arm against B and split F alone; and
`VarkaDecimalBaselineBenchmark`'s eight remainder sections (`x % y` and `pmod` at
`DECIMAL(38, 10)` and `DECIMAL(20, 2)`, both null densities) with the option on, named in the
labels, against the row path. The control: B in the spike, which no width or option moves.

### 6.1 Predictions, registered before the run

1. The new arm reads within 1.15 times of split F alone (42.4 to 43.8 ns a row for `x % y` on the
   runner), since the transpose costs about 2 ns a row (10.4), and at least 5 times B's rate.
2. The baseline's pair sections with the option on are at least 5X the row path (771.3 and 973.4
   ns a row at `DECIMAL(38, 10)`); not the 18 to 20 times 10.5 indicated, since the query also
   reads Arrow and writes its output.
3. Every arm and section allocates under the sampler's allowance on the runner, in the shared
   JVM of the benchmark, after its other arms.

## 7. Risks

1. **The protection is the warm-up's, and the warm-up is tuned for the preferred species.** At
   128 bits the preferred species is the two-lane one, which the probe ran; a JVM whose warm-up
   is switched off (`-Dvarka.warmup.vectorTemplates=false`) loses it. Test 5.3 pins it on.
2. **A polluter the probe did not try.** The check passes against one polluter; another set of
   shared-template profiles may bring the residual back, as row 373's per-bytecode traps did at a
   4-lane override. The sampler's verdict then releases the kernel to the row path, which is
   correct and slower, and the option stays opt-in. The order is covered: other vector code
   that runs before Varka's first kernel, and so before the warm-up, was probed too (0 of 6).
3. **577b changes shape.** If 577b's node or its two-lane decline differs from what 3.1 assumes,
   this plan's 3.1 is re-read against it before the build (question 1).

## 8. Sequencing

1. This plan and the admission probe (one commit).
2. After 577b merges: rebuild and re-run the probe on that master (`build.sh`, then `run.sh`
   with its default modes) and record it in 9; its result says whether 2's verdict still holds.
3. One pull request, about 650 counted lines: the helper with test 5.1 and the spike's arm; the
   option and the compiler and emitter call, with tests 5.2 to 5.5. Split in two at the helper if
   the count passes the cap.
4. The runner readings of 6, committed in the same pull request.

## 9. Outcome

*To be written when the work lands.*

## 10. Questions for the owner

1. **Order.** Build after 577b, which defines the node and the decline this option changes?
   Recommended: yes. The helper and the spike's arm could land before it, but nothing would call
   them, and the row's done-when needs 577b's conformance tests. Undecided here because it
   orders the owner's queue.
2. **The shapes the arm serves.** Only a remainder of two pair columns at one scale as a root
   value (3.1), the rest keeping the decline? Recommended: yes for this row, since those are the
   baseline's shapes and the only ones measured; a remainder under a comparison or over a
   rescaled operand needs a column in between, which is a row of its own if a workload asks.
3. **The option's surface.** An emit option only (tests, benchmarks, the matrix), or also an
   internal SQL configuration so a session can opt in, as
   `spark.sql.codegen.varka.declineTwoLaneConditions` is? Recommended: also the internal
   configuration, default off; "opt-in" means a user can opt in. A new configuration is the
   owner's call (the two-surface model of Varka's configuration).

## 11. Done when

- the arm passes 577b's remainder conformance tests at both representations with the option on;
- the shared-JVM allocation check (5.3), run after other vector code in a JVM that has run the
  template warm-up, finds it within the warm-up's allowance;
- an EPYC 7763 reading at 128 bits against B and the row path is committed;
- the out-of-line transpose is a committed benchmark arm that reuses its block buffer.
