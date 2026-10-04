# VARKA-76: one validity writer is right for one shape and wrong for another

## 1. Where this came from

`m4/PLAN.md` row 76 and section 2.38, out of VARKA-70's review. VARKA-46
gave the per-group validity write a width-specialised helper -
`orValidityBitsAt16` and its siblings, each with the general form's four-arm
switch already resolved and the `VectorSpecies` constant baked in - and made it
the default, argued from a measurement on the four-field shared method. It is a
global boolean, and the measurement splits by shape.

## 2. What is established, before this plan proposes anything

### 2.1 The pairing, which is easy to get wrong

The specialised arm is the row named **`words per group (VARKA-70 A/B)`**, not
the one named for VARKA-45. VARKA-70 turned the per-group write off for a served
root, which left VARKA-46's arms timing one kernel against itself, so both were
rebuilt on VARKA-70's per-group reference variant: `generalHelpers =
perGroupWrite.withValidityByWidth(false)` against `perGroupWrite` itself
(`VarkaEmitterParityBenchmark.scala:157, 652`). The two differ in exactly one
flag and the per-group call survives in both.

*Recorded because the first attempt at this plan paired the VARKA-45 row as the
specialised arm and briefly concluded 2.38's premise had reversed.* It has not.

### 2.2 The effect, from the committed files

| pair | width | specialised | general | effect |
|---|---|---|---|---|
| single-field `year`, mixed nulls | AVX-512 | 3092.5 | 3366.0 | general +8.8% |
| single-field `year`, mixed nulls | 128-bit | 1190.5 | 1281.6 | general +7.7% |
| four fields shared, mixed nulls | AVX-512 | 1069.8 | 922.5 | specialised +16.0% |
| four fields shared, mixed nulls | 128-bit | 417.3 | 335.2 | specialised +24.5% |

So the shipped default wins the shape it was argued from and loses the other.

### 2.3 One of those four numbers is inside its own noise, and it is the one the
task exists for

Against VARKA-77's band for these rows:

| pair | width | effect | band of the two rows | verdict |
|---|---|---|---|---|
| single-field `year` | AVX-512 | 8.8% | 10.30% / 6.02% | **inside the noise** |
| single-field `year` | 128-bit | 7.7% | 1.34% / 1.47% | resolvable |
| four fields | AVX-512 | 16.0% | 10.08% / 10.33% | resolvable |
| four fields | 128-bit | 24.5% | 1.72% / 2.96% | resolvable |

**The 128-bit rows carry this task's claim.** The wide rows corroborate and
cannot fail it on their own: at AVX-512 the single-field loss is smaller than
what one of its two rows moves on an unchanged file. That is the third time this
milestone has found the narrow width the trustworthy one, after VARKA-90's band
and VARKA-77's collapse.

### 2.4 Where the choice is made, and what is not there

`options.validityByWidth()` is read in exactly one place, `emitLanes`
(`VarkaLoopEmitter.java:656-662`), which sees only the options - never the IR.
It bakes a class-wide `Analysis.lanes` (`:1564`). The only per-site refinement is
`widthSpecialised(analysis, s)` (`:3206-3214`), which adds one fact: the epilogue
always uses the general form, because its group is a remainder rather than a
width and it runs once per batch.

**The quantity the rule wants is not available where the choice is made.** The
count of validity writes a loop body makes is derivable one frame up, in
`emitLaneGroup` (`:3245`), which has the group's `outputIdx` and already computes
the per-root verdict `validityWritten = fillsValidityOnce(...) ||
servedByPass(...)` (`:3327-3328`). Nothing computes the count, and `Slots` carries
no group. Two seams exist and 3.3 chooses between them.

## 3. The admission check, to do first

### 3.1 Confirm the arms still measure two kernels

This measurement has broken once already, silently: VARKA-70's pass removed the
per-group call for served roots and left both arms emitting the same bytes.
Before any number is read, assert that the two arms differ - method names and
`codeSize` - for every pair the task quotes. A number from two identical kernels
is the failure mode this task is downstream of.

The filter pair needs its own look: 884 is `DEFAULTS` and 885 is
`perGroupWrite.withValidityByWidth(false)`, which differ in **two** flags
nominally. `validityByBitmap` should be inert for a `Cond` root, since the pass
never serves one - assert that rather than assume it.

### 3.2 Is the write count the key, or is something else?

2.38 proposes "specialised above one write, general at one", reasoning that the
number of validity writes is what differs between the two shapes. It is also
what differs between one output and four, and those shapes differ in method
size, op count and live ranges too. A rule keyed on the wrong quantity is right
on the two shapes it was fitted to and wrong elsewhere.

So the check is a ladder in the write count - one, two, three, four unserved
value roots in one group - holding the shape otherwise as constant as the IR
allows, at 128-bit where 2.3 says the effect is readable. If the crossover sits
at a write count, the rule is the write count. If the ladder is flat and the two
known shapes still differ, it is not, and the task says so instead of shipping a
rule fitted to two points.

### 3.3 Where the rule lives

Not `emitLanes`, which cannot see a shape. Two candidates:

* **On `Slots`, written by `planSlots`**, which already receives `outputIdx`, so
  `widthSpecialised(analysis, s)` reads it with no new parameter. This keeps the
  decision where the one existing per-site refinement already lives.
* **Threaded from `emitLaneGroup`** through `emitValidityRead`/`emitValidityOr`/
  `emitRootValidityOr`, which is more parameters on three signatures.

The plan takes the first unless the check finds `planSlots` cannot compute the
count for the same group the emission uses.

### 3.4 The reads are on a different footing from the writes

`emitValidityRead` (`:3284`) is called per *referenced input*, not per output, so
a rule counting writes leaves the reads unkeyed. Three outcomes are possible and
the task must pick one explicitly: reads follow the writes' verdict, reads keep
the global default, or reads get their own count. Silence here would ship a rule
that half-applies.

### 3.5 What the check would have rejected

That the VARKA-45 row is the specialised arm (2.1). That the wide width can
settle the single-field loss (2.3). That the arms are known to differ (they were
identical once, silently). That the write count is the key merely because it
differs between the two shapes (3.2). And that the choice can be keyed from
`emitLanes`, where the shape is not visible (2.4).

## 4. The design, if the check passes

A rule at `widthSpecialised`: the width-named helpers above the threshold the
ladder finds, the general pair at or below it, the epilogue unchanged.
`VarkaEmitOptions.validityByWidth` stays exactly as it is - the reference arm the
A/B measures against, on `FloorMod7`'s precedent - and gains no second boolean;
a rule that needs a switch to express is not a rule.

**And a decline is a real outcome**, which 2.38 already allows. If 3.2's ladder
is flat, or if the single-field cost proves to be inside the band at both widths
once remeasured, the honest close is the finding recorded and the debt entry
swept in the past tense. 2.3 makes that verdict per width rather than global,
which is new: the wide width cannot resolve it and the narrow one can.

## 5. Files

| file | what |
|---|---|
| `VarkaLoopEmitter.java` | the count on `Slots`, and `widthSpecialised` reading it |
| `VarkaLoopEmitterSuite.scala` | 3.1's arms-differ assertion, and the rule's own guard |
| `VarkaEmitterParityBenchmark.scala` | 3.2's write-count ladder, on fresh case ids |
| benchmarks + bands | regenerated, gated; the rows 2.2 names move |
| `m4/PLAN.md`, this file | row 76, 2.38 corrected where 2.1 and 2.3 change it, section 10 |

## 6. Tests

* **The arms differ**, asserted rather than assumed, for every pair this task
  quotes - the failure that made VARKA-70's review necessary.
* **The rule's guard**: shapes on either side of the threshold emit the helper
  the rule names, and shapes the rule does not reach are byte-identical with it
  on and off. The second half is what stops a rule keyed on one quantity from
  moving shapes that differ in another.
* **The differential is untouched.** Which helper writes a validity bit cannot
  change a bit's value; if any differential moves, the change is wrong.

## 7. The measurement, and predictions registered before it

The ladder of 3.2 at both widths, read at 128-bit and corroborated at AVX-512,
with the band consulted per row rather than a flat threshold.

1. **The crossover is at two writes** - the general helper wins at one and loses
   at two and above - which is 2.38's guess and what the two known shapes imply.
2. **The wide width will not confirm the single-field half.** Its effect is
   8.8% against rows that move 10.30% and 6.02%, so the honest report there is
   "not distinguished", and the task must not be allowed to rest on it.
3. **The filter pair's second flag is inert**, since the bitmap pass never
   serves a `Cond` root, so 884 against 885 is a one-flag comparison in effect.
4. **The rule moves no shape with one write per body other than the ones the
   ladder names** - the `dayofweek` and filter rows are the check on that, since
   both keep a per-group write for a different reason.

## 8. Risks

1. **The motivating number is the least resolvable one.** Everything here rests
   on a 7.7% effect at one width. If a remeasurement moves it inside the band,
   the task closes with a decline, and that is a result rather than a failure.
2. **A rule fitted to two points.** 3.2's ladder exists so the threshold comes
   from a curve rather than from the two shapes that raised the question.
3. **Reads and writes drifting apart** (3.4), which would leave a shape reading
   through one helper family and writing through the other.
4. **The arms silently collapsing again.** Any future change that removes the
   per-group write for a shape makes its pair meaningless; 6's first test is what
   makes that loud.

## 9. Sequencing

1. This plan and row 76.
2. 3.1's arms-differ assertion, first, because everything downstream reads those
   rows.
3. 3.2's ladder, at 128-bit, and the crossover.
4. 3.3's seam and 3.4's decision about reads.
5. The rule and its guard; the regeneration, gated and banded.
6. 2.38 corrected, row 76, `SKILLS.md`, section 10.

## 10. Outcome

### 10.1 Step 3.1: the arms do still differ, and the filter's second flag is inert

`VarkaLoopEmitterSuite`, "VARKA-76: every arm of VARKA-46's A/B still emits two
different kernels", over all five shapes the parity file pairs. It also asserts
what 3.1 said to assert rather than assume: `validityByBitmap` is inert for a
`Cond` root, so the filter pair varies one thing despite naming two.

### 10.2 Step 3.2's ladder, and it refutes the rule the row proposed

Four rungs, `k` unserved `IfElse` blends over one date, holding the shape family
constant so only the write count moves - one rung adds one write, the same ops
and the same 145 bytes. Four runs per width, pinned, adjacent arms.
**Specialised advantage, negative where the general pair wins:**

| writes | 128-bit (4 runs) | AVX-512 (4 runs) |
|---|---|---|
| 1 | -3.8 to -6.8% | +29.6 to +32.0% |
| 2 | -0.5 to -1.5% | +16.8 to +21.4% |
| 3 | +11.1 to +12.1% | +10.9 to +15.2% |
| 4 | +11.9 to +19.9% | +7.7 to +12.6% |

**The two widths disagree in sign at low write counts, and in direction
throughout.** At 128 bits the specialised helper loses below three writes and
wins above, which is the shape 2.38 expected. At AVX-512 it wins at every rung
and wins *most* where the narrow width says it loses. Both are reproducible: the
run-to-run spread is 1 to 5 percentage points against effects of 6 to 32.

The narrow half corroborates the shapes that raised the question - `year` at one
write loses 7.7% in the committed file against the ladder's 3.8 to 6.8%, and the
four shared fields at four writes win 24.5% against the ladder's 11.9 to 19.9%.

**So prediction 1 is refuted, and with it 2.38's design.** "Specialised above one
write, general at one" is right at 128 bits and wrong at AVX-512, where the
general pair never wins anything. A single rule keyed on the write count alone
cannot be correct at both widths.

That this is a width interaction rather than noise is what the cost structure
suggests: at four lanes a body makes four times as many lane-group calls per row
as at sixteen, and the specialised helper's saving is per call while its cost -
one more method to compile and keep hot - is per body.

### 10.3 The design fork this opens, which the plan did not state a default for

Three options, and the choice is not the measurement's to make:

1. **A width-aware rule.** `widthSpecialised` already reads `analysis.lanes`, so
   "specialised always at 16 lanes, specialised above two writes at 4" costs no
   new plumbing. It is also the most complex thing this task could ship, and it
   is fitted to two widths on one machine.
2. **Keep the global default and record the narrow-width loss.** The specialised
   helper is right at AVX-512 everywhere and right at 128 bits above two writes;
   what it costs is 4 to 7% on a narrow-width body with one or two writes.
   2.38 already licenses this outcome, and 2.3 makes it a per-width verdict.
3. **Key on writes without regard to width**, which the data says is wrong at
   AVX-512 by 17 to 32 percentage points. Not defensible.

### 10.4 Root cause: it is not the thing VARKA-46's javadoc says it is

The loss was chased through the JVM's own output at 128-bit, on the same blend
shape written as SQL so `dev/varka_emit.sh` and `VarkaEmitDump` could reach it.
**Three candidate causes are excluded by measurement, and the third is the one
the design rests on.**

* **Not a refused inline.** VARKA-46's javadoc explains the specialised helpers by
  the general pair being 212 bytes and "C2 refuses to inline the writer inside a
  fused loop". In the compiled masked loop **both arms inline it**: neither
  disassembly contains a call to the helper, and the 24 call sites in each are
  deoptimisation stubs at one address. `-XX:+PrintInlining` agrees and is
  symmetric - each helper inlines at hot sites and is refused at cold ones,
  `orValidityBitsAt4` included, because 48 bytes is over `MaxInlineSize`.
* **Not instruction count.** The compiled loops are 642 instructions for the
  width-named arm and 654 for the general one. The arm that loses is the
  *smaller* one.
* **Not the helper's own arithmetic.** The specialised form is cheaper by
  construction and the assembly shows it: `row >>> 3` and `row & 7` against the
  general form's `row / 8` and `row % 8`, which is 5 more `shrq`/`andl` in the
  width-named arm against 5 more `sarq`/`subq` in the general one. The arm doing
  less address arithmetic is the one that loses.

**What does differ is register-file traffic.** All ten of the general arm's extra
"vector" instructions are moves rather than compute - `vmovq` +6, `vmovd` +3,
`vmovsd` +1 - and the width-named arm carries correspondingly more GPR ALU work
(`movl` +5, `andl` +5). The two arms are not doing different work; they are
holding it in different register files.

That is a register-allocation difference, which is the class of effect
`SKILLS.md`'s "A bimodal kernel is usually the register allocator" was written
for, **and it explains the width dependence**: a 4-lane body runs four times as
many lane groups per row as a 16-lane one, so whatever the allocator does around
the per-group write is paid four times as often at the narrow width and is
amortised at the wide one. Stated as the leading hypothesis rather than as proof:
the instruction mix supports it, and nothing here measures spill traffic
directly.

**So the premise the option was added on does not hold for this shape.** The
specialised helpers were justified by an inlining refusal that does not occur
here, and they nonetheless win at 16 lanes and lose at 4 - for a reason unrelated
to why they exist.

### 10.5 A tooling defect found on the way, and fixed

`VarkaEmitDump`'s `--options` parser used `java.lang.Boolean.valueOf`, which
answers false for every string that is not `"true"`. `--options
validityByWidth=on` therefore selected the arm it was meant to exclude, silently,
and two of this investigation's runs measured the same arm twice before the
identical byte counts gave it away. The enum branch beside it already refuses an
unknown constant; the boolean branch now refuses an unknown boolean the same way.

### 10.6 The loss exists at one width only, and that width is the one where a
group is half a byte

The ladder run at a third width settles it. Specialised advantage:

| writes | 4 lanes | 8 lanes | 16 lanes |
|---|---|---|---|
| 1 | -3.8 to -6.8% | +33.3 to +35.7% | +29.6 to +32.0% |
| 2 | -0.5 to -1.5% | +18.9 to +19.7% | +16.8 to +21.4% |
| 3 | +11.1 to +12.1% | +19.7 to +25.8% | +10.9 to +15.2% |
| 4 | +11.9 to +19.9% | +13.3 to +16.5% | +7.7 to +12.6% |

**At 8 and 16 lanes the specialised helper wins at every write count.** The loss
is unique to 4 lanes, and 4 lanes is the only width in this set where a validity
group does not own whole bytes: 4 bits, so *two consecutive groups
read-modify-write the same byte* and serialise on it. At 8 lanes a group is
exactly one byte, at 16 exactly two.

So 10.4's register-file finding is the mechanism and this is the regime that
makes it visible: where the loop is already carrying a serialised byte
read-modify-write chain, the arms' differing register placement decides the
result and the width-named arm happens to lose; where the chain is absent, its
cheaper address arithmetic wins as designed. Both arms do the same byte
read-modify-write at 4 lanes, which is why the *chain* is not itself the
difference between them - it is the condition under which the difference matters.

### 10.7 What can be influenced, and by what

**Not the helper choice.** A rule keyed on the write count is right at 4 lanes
and wrong at 8 and 16, where the general pair never wins; a rule keyed on lanes
and writes together is two fitted thresholds on one machine, for a 4-to-7% effect
on one width at one and two writes.

**The lever is the write itself, and it is already a task.** Milestone 4's row
47, "one validity write per word", is exactly the change that removes the
serialised chain: accumulate a 64-bit word's worth of groups and store once
instead of a read-modify-write per group. At 4 lanes that is sixteen groups per
store rather than sixteen partial stores over eight bytes - the regime this loss
lives in disappears rather than being tuned around. Row 47 is currently gated on
VARKA-46, which is this decision; that gating is the right way round and this
measurement is what it was waiting for.

**So the honest close for VARKA-76 is a recorded decline**, which 2.38 already
licenses. The shipped default is right at 8 and 16 lanes at every write count,
and right at 4 lanes above two writes; what it costs is 4 to 7% on a 4-lane body
with one or two per-group writes, in a regime VARKA-47 exists to remove. Adding a
rule now would tune a constant inside a loop shape that is scheduled for
replacement.

### 10.8 The decline, and section 7's predictions scored

**VARKA-76 closes with a recorded decline.** 2.38 licensed one if the single-field
cost proved too small to matter; the measurement chose it for a better reason
than size. The shipped default is right at 8 and 16 lanes at every write count
and right at 4 lanes above two writes, and what it costs elsewhere is 4 to 7% in
a loop shape row 47 replaces. A rule now would fit two thresholds on one machine
inside a regime scheduled for removal.

1. **Missed.** The crossover was predicted at two writes. There is a crossover at
   4 lanes, between two and three - but none at all at 8 or 16 lanes, where the
   width-named helpers win throughout. The prediction assumed the question had
   one answer.
2. **Held.** The wide width did not confirm the single-field half: its committed
   8.8% sits inside a band of 10.30% and 6.02%, and the purpose-built ladder puts
   the same shape at +29.6 to +32.0% the other way. Reporting AVX-512 as "not
   distinguished" was right, and the task would have shipped a wrong rule had it
   rested there.
3. **Held.** `validityByBitmap` is inert for a `Cond` root, asserted in the
   emitter suite, so the filter pair is a one-flag comparison.
4. **Not reached.** No rule was built, so nothing was checked for over-reach.

### 10.9 What carries to row 47

That the per-group read-modify-write is a **4-lane** problem specifically, with
the measurement to show it, and that removing it removes the regime in which the
helper choice matters at all. Row 47 is also **ungated on VARKA-46** now: VARKA-76
was that gate.

Two artefacts stay behind for it. The write-count ladder is committed with its
case ids, so row 47 can measure against the same rungs. And the emitter suite's
"every arm of VARKA-46's A/B still emits two different kernels" is the assertion
that stops this pair collapsing silently a second time.
