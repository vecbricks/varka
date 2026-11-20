# VARKA-375: `VectorMask.fromLong` where the hardware refuses it

*Row 375 of `m8/PLAN.md`, wave 0, added by the review of 16 October 2026 (10.1 item 2). Planned
on 16 October 2026 at `8a50f61a368`. Half of the row's first question was answered the same day by
the canary run that the review asked for; section 2 records that, and the step that answers the
other half.*

## 0. Admission check against master (21 October 2026, `a429af749f4`)

This plan was written on 16 October 2026 at `8a50f61a368`, before rows 335a, 376, 397, 405 and 410
landed. Read against the code and the committed census, **its verdict stands and gets narrower:
`fromLong` is intrinsic on every host the milestone measures at the widths those hosts run by
default, with one cell the plan did not name (a long species at 128 bits on x86); nothing in the
build half is admitted yet, because the run that would admit it was never made; and 335a built none
of the mask construction of 3.1, only a shift it would reuse.** Where this section and the text
below differ, this section wins; the sections below are left as written. What was checked:

* **The census confirms section 2 item 1, per host and width.** `operator_census.json` (laptop),
  `results/VARKA-376-census-epyc-7763.json` and `results/VARKA-376-census-neoverse-n2.json` (run
  39400866095, 20 October 2026) hold `byte.fromLong`, `int.fromLong`, `long.fromLong` and the three
  `toLong` cells. Verdicts: laptop and EPYC 7763 `I` with 0.0 bytes a row for bytes and ints at
  512, 256 and 128 bits and for longs at 512 and 256; **Neoverse N2 `I` with 0.0 bytes a row in all
  six cells, at its one width, 128 bits** (`fromBitsCoerced` and `maskReductionCoerced` among the
  intrinsics read). That is the BitPerm reading of 16 October made a second way, by the allocation
  rate of a loop and not by a log line, on the same runner class.
* **The census refutes "intrinsic on every host" for one cell.** `long.fromLong` reads `F`, 116
  bytes a row, with `fromBitsCoerced` and `blend` failed, and `long.toLong` `F`, 68 bytes a row, on
  both x86 hosts at 128 bits (laptop and EPYC 7763). The cause is not a CPU feature: it is `m7`
  row 374's, that x86 C2 lowers no two-lane mask (`VARKA-376.md` 9 and 9.2). It does not change
  section 2's verdict for the hosts at their defaults (512 bits on the laptop, 256 on the EPYC
  runner, 128 on the N2), but it is real wherever `lanesOverride` or a 128-bit preferred width puts
  a long lane at two lanes, and the plan's prediction 3 of 6.1 ("the four-operator
  construction ties `fromLong` on x86 ... at both widths") needs reading with it. **The
  construction of 3.1 would not rescue that cell.** Its last operator is a compare that makes a
  long-lane mask, and 376 reads every compare that makes a mask at two long lanes on x86 as the
  fallback (`VARKA-376.md` 9, the thirteen 64-bit cells at 128 bits; the committed unsigned long
  compares `long.ULT`, `UGT`, `ULE` and `UGE` read `F` there). That is an inference from those
  cells, not a measurement of the construction, which does not exist; the census has no signed long
  compare cell, so a one-line cell would settle it if anyone wants the fact. Row 374 remains "Done,
  no fix" and 375 does not reopen it.
* **The census does not cover the construction's other operators.** Its shift cells are `byte` and
  `short` only (`byte.LSHL`, `byte.LSHR`, `byte.ASHR` and the short three); an int or long
  shift by a vector, an `AND` against one and a broadcast are not cells. So whether the plan's
  "operators every host intrinsifies" holds on the N2 and at 128 bits is unread for ints and
  longs. If the construction is ever built, those cells come first (376's catalog takes an entry
  per operator).
* **335a built a shift the construction would reuse, and nothing else of it.** `BitOp` (SHL, SHR,
  USHR and the logical three; `VarkaVectorIR.BitOp`, `VarkaVectorWalk.emitBitOp`) emits
  `lanewise(LSHL | ASHR | LSHR, Vector)` through `Lane.lanewiseBinaryV`, so a shift of a vector by
  a vector of counts is emitted today for int and long lanes, and the plan's "broadcast, shift
  right by the lane index, `AND` one" has its shift and its `AND` as existing emitter code
  (335 9 item 2: a literal count is emitted as a broadcast vector too). On the laptop C2 compiles
  those to `vpsllvd`, `vpsrlvq` and kin (335 9 item 2, from `--asm`); the N2 and AVX2-at-128 were
  not read. What does not exist: a helper that builds a mask from a word (`VarkaMaskFromWord`), the
  `maskFromWord` switch, the lane-index constant vector, and the split of a word per long half.
  335a's bitmap lowering (`bitmap_bit_position`, `bitmap_bucket_number`) is arithmetic on longs
  (compare, blend, mask, shift) and builds no mask from a word. Row 335 and 375 therefore share no
  code beyond the `lanewise` shift; 335b (PR #955, open) is not read as built.
* **The call sites moved; section 2 item 2's line numbers are stale.** On master the emitter's
  `invokestatic VectorMask.fromLong` is in `VarkaBodyEmitter` (the guard accumulators' empty mask,
  line 219, from `loadConstant(0L)`) and in `VarkaVectorWalk` at `emitValue` (398, a condition's
  word), `emitGuardCollect` (855), `emitArmContext` (936), `emitToDrivingLane` (953), `emitPick`
  (985 and 993, the two operand masks of the pick, each rebuilt from a complemented word) and
  `emitCond` (1205, the dense branch's all-true mask): eight sites, against the plan's seven. In the
  hand-written ops: `IntRangeOps` (87, 88, 107) and `SelectionVectorOps` (142, 178); in
  `VarkaVectorTemplateWarmup.masks` (711); in the engine `DateVectorOps` (129, 179, 180, 270 to
  274). `VarkaDescriptors` holds `FROM_LONG` (152 on master; the plan said 147). `emitToDrivingLane`
  and the output-validity write of `VarkaBodyEmitter` (1069) also call `toLong`, so a
  `fromLong`-free emission still meets `toLong`, whose long cell at 128 bits on x86 is `F` as well
  (above). The line numbers are what `grep '"fromLong"'` gives today and will move again; a build
  starts from the grep, not from this list.
* **The first step the plan owes was never made.** `varka-canary.yml` has no input for JVM options:
  its `args` input is program arguments (the workflow's `probe` function takes JVM flags as a
  fixed third word, `"-XX:+UnlockDiagnosticVMOptions -XX:+PrintIntrinsics"` for the `fromlong`
  probe), no `UseSVE` appears in the repo's code, and `dev/varka_canary/` holds no
  `mathlane-aarch64-neoverse-n2-usesve1.txt`. The census suite plants `fromLong` under
  `UseSVE=1` as a data case it does not run (`VARKA-376.md` 9, departure 3). So section 2's
  question, what a validity-reading kernel allocates where `fromLong` falls back, is exactly as
  open as on 16 October. The owner's decision of 16 October (the `UseSVE=1` simulation answers the
  allocation half) stands.
* **The warm-up side (397, 405) does not change the design and adds one rule.** The template
  warm-up already calls `VectorMask.fromLong` per lane (`VarkaVectorTemplateWarmup.masks`), so a
  kernel that keeps `fromLong` is warmed as before. Row 397's `VarkaWarmupReachSuite` fails when a
  Vector API method an emitted kernel calls gets fewer than 2,000 calls from the warm-up's rounds,
  so a construction emitted by option must have its methods (the broadcast, the shift by a vector,
  the compare, whatever the split adds) called by the rounds in the same change, or that suite
  fails on the option's configuration: this is a fifth test beside
  section 5's four. Row 405 found the kernel warm-up's
  verdict unchanged by a second kernel class and states the lanes cliff at
  `VarkaEmitOptions.lanesOverride`; row 410 made the catalyst suites hold under `lanesOverride=4`
  on a 512-bit host. Neither bears on `fromLong`; both matter to any option the plan adds, since
  `VarkaSpeciesGuard` keeps one size per vector class in a shared JVM and the option matrix
  reruns every suite at five lane counts.
* **Section 3.2 and row 331 hold.** 331's done-when ("a construction intrinsic on every host the
  milestone measures") is met by the census rows above at the hosts' defaults, with the
  long-lane-at-128-bits on x86 cell recorded as 374's; 331's row text already carries that
  reading. `emitted_bytes.json` is unaffected by this section; the plan changes no code.

**What remains of the row, and whether it is still needed as written.**

1. *Still open, small:* the `UseSVE=1` run on the Arm runner and its allocation reading
   (sections 2 and 8 step 2), the only thing that can admit the build half. The `fromlong` probe
   exists (`MathLaneProbe` `fromLongLoop`); what is missing is a way to give the job `UseSVE=1`.
   Two forms: an input for extra JVM flags as the plan says, or one more `probe` line with the flag
   fixed in the workflow, which is smaller and changes no other probe's run. The allocation
   reading wants the census's loop (it reads bytes a row), not only the `PrintIntrinsics` line, so
   the cheaper route is probably a `UseSVE=1` cell in `VarkaOperatorCensusSuite`'s fork, on the
   N2 runner through `varka-width-audit.yml`, with the verdict rule it already has. This plan does
   not choose; see the questions below.
2. *Not admitted, not to be built now:* the `maskFromWord` option, `VarkaMaskFromWord` and the
   tests of section 5 items 2 to 4. Nothing measured says a host the milestone runs on needs them,
   and on the one cell where `fromLong` is the fallback (long, 128 bits, x86) the construction is
   expected to end in the same refusal.
3. *Superseded wording:* "on every host the milestone measures today, `fromLong` is intrinsic"
   (section 2 item 1) is true for every cell the hosts run by default and for bytes and ints at
   every width; it is false for longs at 128 bits on x86, for the reason of 374.

*Predictions registered by this check, to be scored when the run is made:* (a) `UseSVE=1` on the N2
runner turns the N2's `fromLong` cells from `I` to `F`, as section 6.1 prediction 1 says, now read
by the census's rule; (b) the fallback's allocation there is at least the 116 bytes a row the x86
two-lane cell shows, since it is the same Java method; if it is well under that, C2 scalar-replaces
the array on Arm and the build half is closed on the record.

## 1. Where this came from

JDK 25 intrinsifies `VectorLongToMask`, the node behind `VectorMask.fromLong`, on aarch64 only
when the core has SVE2 BitPerm (`aarch64_vector.ad` 217 and 218), and every Varka kernel builds its
validity masks with that call. On a NEON-only core (Apple's, Graviton2) or an SVE1 core
(Graviton3), `fromLong` runs the Vector API's Java fallback, which
`skills/vector-api-and-width.md` describes as a second implementation. Row 331's boolean leaf
(`m8/PLAN.md` 2.5) is the same call over a data word, and 1.1 item 5 keys the math bounds by the
Arm library, so the milestone measures on Arm. The row asks: does the Arm runner have BitPerm,
what does a validity-reading kernel allocate where `fromLong` falls back, and, if it falls back
somewhere the milestone measures, a construction every host intrinsifies.

## 2. The admission check, done

What the record and the source settled:

1. **The Arm runner has BitPerm** (canary run 38884285755, 16 October 2026,
   `dev/varka_canary/mathlane-aarch64-neoverse-n2.txt` and the skill entry "The JDK's SLEEF sends
   `exp` and `pow` to infinity early, SVML does not, and the Arm runner has BitPerm"):
   `svebitperm` in `/proc/cpuinfo` and in the JVM's feature line, and `fromBitsCoerced` inside
   `fromLong` reads `(intrinsic) late inline succeeded` wherever the species is a constant, with no
   `** not supported` line. Emitted kernels load their species by `getstatic` of a `static final`,
   always a constant. So **on every host the milestone measures today, `fromLong` is intrinsic**:
   the Neoverse N2 runner by BitPerm, and x86, where `x86.ad` (1876 to 1882) admits
   `VectorLongToMask` under AVX with BMI2, which the EPYC 7763 runners and the laptop have.
2. **The emitter's call sites**, the surface an alternative would replace: `VarkaBodyEmitter`
   195 (an empty guard mask per accumulator), `VarkaVectorWalk` 355, 672, 738, 772, 780 and 974,
   `IntRangeOps` 81, 82 and 101, `SelectionVectorOps` 136 and 172, `VarkaVectorTemplateWarmup`
   214 and 248; in the engine module `ChronoVectorOps` and `DateVectorOps` (the reference kernels).
   The descriptor is `VarkaDescriptors` 147.
3. **Where a host without BitPerm comes from.** Neither GitHub runner class lacks it, and the skill
   entry ends on that. HotSpot gives one on the same runner: `vm_version_aarch64.cpp` (JDK 25 GA
   tree, lines 631 to 634) clears `CPU_SVEBITPERM` and `CPU_SVE2` from the feature set whenever
   `UseSVE < 2`, and the match rule reads `VM_Version::supports_svebitperm()`. So
   **`-XX:UseSVE=1` on the Neoverse N2 runner refuses `VectorLongToMask` as an SVE1 core does**,
   and `-XX:UseSVE=0` gives a NEON-only JVM (which also loses every other SVE lowering, as a
   NEON-only core would). Both are simulations, of the JVM's feature set and not of a Graviton3's
   or an M-series core's timing; the repo has never set `UseSVE` (only `PrintFlagsFinal` reads of
   it, `benchmark.yml` 340, `varka-width-audit.yml` 84, `dev/varka_datapath.sh` 84). A real
   NEON-only host - an Apple Silicon machine, which `m7` row 374 notes behaves differently from
   x86 at two lanes - would confirm the timing; none is in the runner pool.
4. **`m7` row 374 is closed "Done, no fix"** (`m7/PLAN.md` 684, `VARKA-374.md` 9): at a 128-bit
   x86 species, two-lane long masks run the Java fallback because x86 C2 lowers no two-lane mask,
   and the rate of releases cannot fall below about 10 in 100 without a mask-free two-lane kernel.
   That is an x86 fallback of the same family as this row's, for a different reason (the lane
   count, not a CPU feature). Every 128-bit number this row records therefore carries the release
   rate beside it, as `m8/PLAN.md` 10.3 item 4 asks; there is no fix to wait for.

**What still needs a run, and what admits the build half of the row.** On the Arm runner, through
the canary workflow (`varka-canary.yml`, dispatched with `canary=MathLaneProbe`, whose third pass
runs the `fromlong` mode under `-XX:+PrintIntrinsics`), once with the defaults and once with
`-XX:UseSVE=1`, plus a validity-reading kernel's allocation rate in each (the assembly suite's
measure, `VarkaAssemblyProbe` with `getThreadAllocatedBytes`, at most one byte per row,
`VARKA-55.md`). The workflow needs an input for extra JVM options to pass `UseSVE`; that is this
row's first change. Dispatching it is the owner's or the implementing agent's step, not this
plan's.

* Under `UseSVE=1` the `fromLong` call should read `failed to inline (intrinsic)` with
  `lambda$fromLong$0 ... inline (hot)` (the fallback inlined), as the skill entry separates the
  kinds. If the fallback's kernel stays at or under one byte per row and its time is within the
  canary's band of the default's, the fallback costs nothing that matters, the row closes on the
  record, and nothing is built.
* **The build half is admitted** if the fallback allocates per iteration (five bytes per row or
  more, the box signature) or the validity-reading kernel is slower by more than the band. Then
  the alternative of 3.1 is built behind an option.

*Owner's decision, 16 October 2026:* `-XX:UseSVE=1` on the Neoverse N2 runner is accepted for the
allocation half of this question. It simulates the JVM's feature set, which is what decides whether
the fallback runs, so it answers whether the fallback allocates per iteration. The timing half, on
a real NEON-only or SVE1 host, stays unbuilt until such a host exists; a timing read under
`UseSVE=1` is the N2's and is recorded as such (risk 1), not as a Graviton3's or an M-series core's.

## 3. The design

### 3.1 The mechanism, if admitted

A mask from a long word, built from operators every host intrinsifies: broadcast the word to a
long vector, shift each lane right by its lane index (an index vector, a per-kernel constant),
`AND` with one, and compare with zero. For an int species of more lanes than a long species
holds, the word is split per long half, which is the two-part shape `VARKA-28.md` already uses for
widening. One helper in the emitter, `VarkaMaskFromWord`, emitted at each site of section 2 item 2
in place of the `fromLong` invocation, behind a `VarkaEmitOptions` switch `maskFromWord` with its
`VarkaEmitOption.TABLE` entry, so that `VarkaEmitOptionSuite`, the option matrix and the IR fuzzer
take it the day it lands. The default stays `fromLong` on every host the milestone measures,
since section 2 item 1 finds it intrinsic on all of them; the switch is chosen per host at kernel
compile time only if a later host needs it (a NEON-only executor class), which this row records and
does not build.

### 3.2 What is deliberately unchanged

* The default emission everywhere: `fromLong` stays, and `emitted_bytes.json` does not move under
  the x86 defaults.
* The hand-written ops (`IntRangeOps`, `SelectionVectorOps`) and the engine's reference kernels
  keep `fromLong`; if the emitter's switch wins on a host, porting them is a follow-up row, named
  in section 9, not this one.
* Row 331's boolean leaf is 331's; it uses whatever this row leaves as the default, and 331's
  done-when ("the mask built from the data word by a construction intrinsic on every host the
  milestone measures") is met by section 2 item 1 on today's hosts.
* `m7` row 374's two-lane x86 mask fallback: closed there, not reopened.

### 3.3 Registered op counts

Per mask, against `fromLong`'s one `VectorMask` invocation: one broadcast, one shift, one `AND`,
one compare - four `LongVector` invocations and no `VectorMask` one at a long species, twice that
for an int species split in halves. Read from `dev/varka_emit.sh "year(d)" --options
maskFromWord=true` and the defaults' table once the switch exists; asserted by an op-count test.

## 4. Files

* `.github/workflows/varka-canary.yml`: an input for extra JVM options, for the `UseSVE` runs
* `dev/varka_canary/MathLaneProbe.java`: the `fromlong` mode's allocation reading (or a sibling
  probe)
* `dev/varka_canary/mathlane-aarch64-neoverse-n2-usesve1.txt`: new: the `UseSVE=1` run's record
* `sql/varka/skills/vector-api-and-width.md`: the BitPerm entry extended with the `UseSVE` finding
* *if admitted:* `VarkaEmitOptions.java`, `VarkaEmitOption` table: the `maskFromWord` switch
* *if admitted:* `VarkaBodyEmitter.java`, `VarkaVectorWalk.java`, new `VarkaMaskFromWord.java`: the
  alternative at each site
* *if admitted:* `sql/varka/emitted_bytes.json`: variant rows only

## 5. Tests, and what each is for

1. **The canary record under `UseSVE=1`**: the inlining lines show the fallback, so the simulation
   is known to reach the refusal it claims; without it the allocation reading means nothing.
2. **If admitted, the switch's answers**: the coverage differential and the IR fuzzer under
   `maskFromWord=true` at both widths, every validity pattern of `VARKA-138.md`; catches a lane
   index off by one in the shift, which an all-valid batch would not show.
3. **If admitted, the bytes oracle under the defaults**: unchanged; catches the switch leaking
   into the default emission.
4. **If admitted, the allocation rate**: the assembly suite's one byte per row, for the switch's
   kernels at both widths.

## 6. The measurement

The canary's `fromlong` mode and a validity-reading kernel (`year(d)` over a nullable date column,
the masked path driven by `--nulls`) on the Arm runner, default and `UseSVE=1`, and if admitted the
switch's kernel against the default on x86 (EPYC 7763 runner and the laptop) and on Arm, both
widths, with the 128-bit release rate beside each 128-bit number (section 2 item 4).

### 6.1 Predictions, registered before the run

1. **`UseSVE=1` makes `fromLong` the fallback** on the N2 runner, by section 2 item 3's source
   reading; a run that shows the intrinsic means the feature strip happens later than the matcher
   reads it, and the simulation is not valid.
2. **The fallback allocates** in a validity-reading loop, at least the five bytes per row of the
   box signature. The fallback (`VectorMask.fromLong`, `VectorMask.java` 240 to 259 in the JDK
   25 GA tree) allocates a `boolean[laneCount]` and calls `fromValues` for every word except
   zero (an all-valid word is not special: the bits are masked to the lane count first, so it is
   `0xFFFF` at sixteen lanes and not -1), and whether C2 scalar-replaces that array inside an
   emitted loop is what the run measures. This is the prediction that would admit the build half.
3. **The four-operator construction ties `fromLong` on x86** within the band at both widths,
   since `VectorLongToMask` itself lowers to a short sequence there; the switch exists for hosts
   without the intrinsic, not for x86.

## 7. Risks

1. **`UseSVE=1` is not a Graviton3.** It strips the features the matcher reads; the timing is the
   N2's. Test 1 proves the refusal, and the record says it is a simulation.
2. **The construction's index vector is a second constant per kernel**, a new literal-like slot;
   if it moves the shape key it must be in the key, which the option's table entry ensures.
3. **The canary workflow's extra-options input** is used by other probes; it defaults to empty so
   their runs do not change.

## 8. Sequencing

1. This plan.
2. The canary input and the `UseSVE=1` run; its record and the skill entry committed, section 2's
   verdict recorded here.
3. If admitted: the switch behind the option, the tests of section 5, then the measurement of 6
   and 6.1 scored. The default does not flip unless a measured host needs it.

Explicitly out of this row: porting the hand-written ops and the engine's kernels, any per-host
switch selection at run time, `m7` row 374's two-lane x86 fallback, and 331's boolean leaf.

## 9. Outcome

*20 November 2026, built in the owner's absence on branch `varka-375-mask-from-long-arm` off
`342ed6851f8`; local only, nothing pushed or dispatched.*

### 9.0 Decisions taken while the owner was away (20 November 2026)

1. **The build half is built before the admission run, behind its option, default off.** Section 0
   item 2 says not to build it until the `UseSVE=1` run admits it; the brief for this window asked
   for the alternative so that x86 tests can force it, and the run cannot be made in the window
   (no dispatches). Building it changes nothing at the defaults (section 3.2 holds: the bytes
   oracle is unchanged, 9.2), so the order costs nothing if the run closes the row on the record:
   the option then stays an arm, as `fromLongMasks` does. The run still decides whether any host
   gets it; this section does not admit it.
2. **The `UseSVE=1` run goes through the canary, as one more fixed `probe` line on aarch64**, the
   smaller of section 0 item 1's two forms, not an input for JVM flags and not a census cell. The
   allocation half the owner's decision asks for is read by the probe itself (`maskcost` lines:
   bytes and nanoseconds a word, for `fromLong` and for the construction, each feeding a blend),
   so no census regeneration is needed. The reading is the probe loop's, not a Varka kernel's
   (`year(d)` through `VarkaAssemblyProbe`, which a single-file canary cannot run); if the probe
   shows the fallback allocating, the kernel reading is the next step, under the option matrix's
   `maskFromWord=true` configuration on the Arm runner.
3. **The construction ANDs with a constant of each lane's bit instead of shifting by the lane
   index** (section 3.1 had broadcast, shift by index, AND one, compare): one operator fewer, and
   no variable shift, whose int and long census cells do not exist (section 0 item 3). It is the
   form `VARKA-491.md` 6 measured as its first candidate, there as a default.
4. **No split per long half for ints.** An int lane holds 32 bits and an int species has at most
   16 lanes at 512 bits, so the low half of the word broadcast as ints holds every lane's bit.
   Bytes and shorts are where a lane cannot hold the lane count's bits: there the word is
   broadcast as longs, viewed at the lane's width, and each lane picks the element holding its
   bit with `selectFrom` by a constant index vector (a broadcast where the lane count fits one
   element: shorts up to 16 lanes, bytes up to 8). Floating lanes cast the mask of the integral
   species of their width.
5. **One call per site, to a hand-written helper**, `VarkaMaskFromWord.of<Lane>`, with
   `fromLong`'s descriptor, not the operators inlined into the kernel: `VARKA-491.md` 6 found that
   about seven calls a site moved the emit cost model's call-site fit and split kernels; one
   `invokestatic` keeps each site's call count and the kernel's method structure.
6. **Under the option the masked body's words skip the `VarkaMaskGuard` AND**: the guard exists
   only to keep `VectorLongToMask` from folding a `toLong` (VARKA-396), and the construction emits
   none.
7. **Every word-to-mask site goes through the option, the constant ones included** (the guard
   accumulators' empty mask, a constant condition, the dense body's all-true predicate), so a
   kernel under it calls no `fromLong` at all, which a test asserts; section 2 item 2's sites
   are now one emitter method, `VarkaVectorWalk.emitFromLong`.
8. **The x86 long-at-128-bits cell is settled by the probe, not by a census cell.** Section 0
   predicted the construction would not rescue it; the laptop's probe at `MaxVectorSize=16`
   shows both forms allocating there (9.1), so no census cell is added.
9. **The template warm-up makes each lane's mask both ways** (`VarkaVectorTemplateWarmup.masks`),
   by section 0's warm-up rule, though `VarkaWarmupReachSuite` would not catch the omission: it
   reads the Vector API calls a kernel's bytes make, and the kernel calls the helper.

### 9.1 What was built and measured

* `VarkaMaskFromWord` (catalyst main): `ofByte`, `ofShort`, `ofInt`, `ofLong`, `ofFloat`,
  `ofDouble`, and `of` by element type for the warm-up and the tests.
* `VarkaEmitOptions.maskFromWord` with its `VarkaEmitOption.TABLE` entry (reason `MACHINE`,
  default false), so the fuzzer draws it and the option matrix runs `maskFromWord=true`.
* `VarkaVectorWalk.emitFromLong`, the one place a word becomes a mask; `emitMaskOfWord` takes the
  lane whose species is on the stack.
* `varka-canary.yml`: the `fromlong` pass shows the `maskcost` lines, and on aarch64 a second pass
  runs under `-XX:UseSVE=1`. `MathLaneProbe`'s `fromlong` mode prints the two `maskcost` lines.
* **Laptop reading** (`results/VARKA-375-maskcost-laptop.txt`, a loaded machine, so the bytes
  only): at 512 bits neither form allocates (0.00 bytes a word each); at 128 bits, where the
  double species has two lanes, `fromLong` allocates 162.18 bytes a word and the construction
  296.01. Prediction (b) of section 0 is about the N2 and stays unscored; the inference of
  section 0's second bullet, that the construction ends in the same refusal at two long lanes on
  x86, is **confirmed**, and worse than the inference: the construction allocates more, since its
  compare and its cast both make two-lane masks.

### 9.2 Tests

`VarkaMaskFromWordSuite` (own JVM): the construction equals `fromLong` for all six lane types at
64, 128, 256 and 512 bits over 4,196 words; under `maskFromWord` no coverage row's kernel calls
`fromLong` and most call the construction; at the defaults none calls it. The answers under the
option are the coverage differential's and the fuzzer's, run under
`-Dvarka.matrix.config=maskFromWord=true` and at `lanesOverride=4` as well (section 9.3).

### 9.3 What is owed

* **The canary run on the Arm runner** (`varka-canary.yml`, `canary=MathLaneProbe`): its
  `fromlong-usesve1` pass scores predictions 1 and 2 of 6.1 and (a) and (b) of section 0, and its
  record is committed as `dev/varka_canary/mathlane-aarch64-neoverse-n2-usesve1.txt` with the skill
  entry extended. If the fallback allocates and the construction does not, the next step is the
  option matrix's `maskFromWord=true` configuration on the Arm runner and a validity kernel's
  allocation there; if the fallback does not allocate, the row closes on the record with the
  option kept as an arm.
* **The option matrix's `maskFromWord=true` configuration on CI**, which runs every suite under it.
* Prediction 3 of 6.1 (ties on x86) is not scored: the laptop was loaded, and 491's runner pairs
  are the measurement that would score it.

### 9.1 The owner's decision on the simulation, 16 October 2026

`-XX:UseSVE=1` on the Neoverse N2 runner answers the allocation half of section 2's question:
whether `fromLong`'s fallback allocates per iteration in a validity-reading kernel. The timing
half - whether the fallback is slower on a real NEON-only or SVE1 core - waits for such a host and
is not built until one exists. Section 8's step 2 is the run this decision allows.
