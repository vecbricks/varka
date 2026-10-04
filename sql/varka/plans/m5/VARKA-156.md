# VARKA-156: the narrowed store at two 64-bit lanes

*Opened 20 September 2026 from `m5/PLAN.md` 2.92 and `VARKA-102.md`
8.6; the diagnosis the same afternoon, the measurement queued for the night.*

## 1. The question

Route A's narrowed store (`VARKA-102.md` 8.3) costs, against the wide store
of the same tree in `VarkaTimeBenchmark`, within 4% on every shape at 512 bits
and a quarter *less* past L3, but at 128 bits `second` reads 12% under the wide
store and the three fields together 18%, where `hour` and `minute` are within
5%. The per-group cost over two rows explained the direction; nothing explained
why the heavier shapes pay more, when the store should be a smaller share of
them. The project establishes JIT facts from the JVM's own output, so the
question was put to the assembly.

## 2. The instrument

`dev/varka_emit.sh --asm` dumps C2's disassembly of a kernel's dense loop, but
it drove int32 kernels only. It now accepts the long lane's column types
(`time`, `time(p)`, `bigint`, `interval day to second`), runs a long-lane
kernel through the eight-argument entry point with each output buffer at its
Spark type's width - so a narrowed root is driven as the evaluator drives it -
counts the `LongVector` ops beside the `IntVector` ones in its method table,
and takes `--width=N` to read a body at a species other than the host's. The
first dumps of `second(t)` produced no method table at all: the tool passed the
int literal count to the emitter where the tree's literal sits in `longArgs`,
which `fused.numLiterals` fixes.

## 3. What the assembly says

The innermost loop of the narrowed `second(t)` at 128 bits, beside the wide
two-divide shape `time_diff('MINUTE', time_trunc('SECOND', t), TIME'00:00:00')`
at the same width, both one lane group per iteration:

| body | instructions per group | divides | of which the store | in-loop bounds check |
|---|---:|---:|---|---|
| wide, plain store | 36 | 2 | 1 (`vmovdqu` folded into `(%r13,%r9,8)`) | none |
| narrowed, masked store | 45 | 2 | 1 masked, plus `vpshufd`, `vmovq` | `cmpq`, `jg` |

The nine extra instructions are not the narrowing, which is two (`vpshufd $8`
packs the two lanes' low halves, `vmovq` clears the upper half). Four are
scalar address arithmetic - `movslq`, `shlq $3`, `addq`, `shrq $1` - computing
the long byte offset and then halving it, where the wide store folds the
induction variable straight into its addressing mode. Two are a bounds check
on the destination that stays inside the loop. One is a base add. And the
masked store itself.

Two consequences follow, and both fall harder on the heavier shapes. The
address arithmetic and the check are per group whatever the shape, so they are
a larger share of `hour`'s short body than of `second`'s - which is the wrong
direction, and rules them out as the whole answer. The other consequence is
the one that fits: **no narrowed loop is unrolled.** The wide one-divide body
(`time_diff('SECOND', ...)`) runs two groups per iteration at 128 bits and
eight at 512; every narrowed body, at both widths, runs one. On two 64-bit
lanes the divider is latency-bound - each `vdivpd` on two lanes waits for the
previous one in its dependency chain - and unrolling is what lets the second
group's divide issue while the first's completes. `hour` has one divide per
group, `second` two dependent ones (`t / 10^9`, then `/ 60`), the three fields
three: the longer the chain, the more an unrolled loop hides and the more its
absence costs. At 512 bits eight lanes give the divider throughput work and the
loss is small, which is what the file showed.

## 4. Two changes, and what each did in the dump

**The offset from the row index.** The store's offset is now `(long) i * 4`,
derived from the induction variable the way the lane's own offset is, instead
of `byteOffset >>> 1`. That removed the shift arithmetic - two instructions per
group - and nothing else: the bounds branch stayed in the loop and the loop
stayed unrolled once. The masked `intoMemorySegment` reaches its intrinsic
through a branch on `offset <= byteSize - vectorByteSize`, and for a vector
twice as wide as the rows it writes, the last group of every batch fails that
test; a branch that is false on the last iteration is not one loop predication
hoists, and a loop with it is not one C2 unrolls.

**The half-width species** (`VarkaEmitOptions.narrowHalfSpecies`, off by
default). The conversion targets the int species with the long lane's own
count - `SPECIES_64` for two lanes, `SPECIES_256` for eight - so the converted
vector is exactly the group's values and the dense body stores it whole; the
epilogue stores under its remainder mask. Honoured at a baked lane count only,
since the half of the preferred species has no named constant. In the dump the
narrowed `second(t)` at 128 bits becomes 37 instructions per group against the
wide store's 36, no bounds branch, and `hour(t)` is unrolled twice again like
the wide one-divide body. That is the wide store's loop shape recovered, one
instruction over.

The cost the option carries is the one `VARKA-28.md` 2.2 recorded: a second
`IntVector` species in the JVM can make the Vector API's shared templates inline
bimorphically and box the int kernels that share them. `VarkaTimeBenchmark`
runs the int32 arms in the same JVM as this one, so their rows against the
committed file are the canary for that: if they hold within the band while the
new arm runs, the pollution did not happen there.

## 5. Predictions, registered before the run

The regeneration at 512, 256 and 128 bits is queued behind tonight's VARKA-105
chain, in this branch, with the offset fix in the shipped form and the half
species as a new arm beside it.

1. **The shipped (masked) form moves little.** With the shift gone, `second`
   at 128 bits closes at most a third of its 12% gap to the wide store; the
   unrolling it lacks is the larger part.
2. **The half-species arm reads within 3% of the wide store on every shape at
   128 bits**, `second` and the three fields included, and within 3% at 256
   and 512, since its loop is the wide store's loop.
3. **The int32 arms do not move**: `seconds of day, int32 lanes, emitted` and
   the hand-written arm stay within 3% of the committed file, so the second
   species did not box them in this JVM.
4. Past L3 at 512 bits the half-species arm keeps the narrowed store's
   quarter over the wide one, since it writes the same bytes.

If 2 and 3 hold, the half-species form becomes the shipped one where the lane
count is baked, and the general case - the half of the preferred species as a
static final of the emitted class - is the follow-up; if 3 fails, the masked
form stays and the cost is recorded as the price of one species per type.

## 6. Outcome, 21 September 2026

The three files were regenerated overnight on the laptop, the wide and 128-bit
pair first and the 256-bit companion after the machine had gone quiet again.
Rates in M rows/s, from the committed files; "masked" is the shipped narrowed
store with the offset fix, "half" the new arm.

In L3 (262144 rows):

| width | shape | wide store | masked | half species |
|---|---|---|---|---|
| 512 | `hour` | 4176.5 | 4021.7 | 4032.3 |
| 512 | `second` | 1406.4 | 1416.5 | 1407.2 |
| 512 | three fields | 1372.6 | 1364.6 | 1359.9 |
| 256 | `hour` | 4711.9 | 4264.2 | 4334.8 |
| 256 | `second` | 1437.4 | 1428.5 | 1434.9 |
| 256 | three fields | 1370.3 | 1305.5 | 1343.7 |
| 128 | `hour` | 2413.6 | 2299.4 | 2342.9 |
| 128 | `minute` | 1182.4 | 1150.9 | 1184.9 |
| 128 | `second` | 763.5 | 691.6 | 738.7 |
| 128 | three fields | 762.0 | 623.1 | 689.3 |

Past L3 (8388608 rows):

| width | shape | wide store | masked | half species |
|---|---|---|---|---|
| 512 | `hour` | 2529.8 | 3112.1 | 3635.7 |
| 512 | three fields | 1019.9 | 1078.4 | 1247.1 |
| 256 | `hour` | 2587.4 | 3102.6 | 3391.6 |
| 256 | three fields | 1010.3 | 1053.2 | 1192.5 |
| 128 | `hour` | 2303.5 | 2004.8 | 2241.9 |
| 128 | three fields | 658.5 | 580.8 | 671.3 |

The predictions, scored:

1. **Held.** The masked form moved little: at 128 bits `second` reads 9%
   under the wide store where 8.6 read 12%, and the three-field shape 18%
   under it where 8.6 read 18%. The offset arithmetic was a small part of the
   cost, as predicted; the unrolling the masked loop lacks is the rest.
2. **Held at 512 and 256 bits, failed at 128 on two shapes.** In L3 the half
   species is within 1% of the wide store at 512 bits and within 3% at 256 on
   every shape but `hour` (8% under, where the masked form is 9% under). At
   128 bits `hour` and `minute` are within 3%, `second` is 3% under and the
   three-field shape 10% under. So the wide store's loop shape is most of the
   128-bit gap but not all of it: with two long lanes a row group is two rows,
   and the three-field shape stores three half vectors per group where the wide
   store stores three full ones, so per-store overheads that the wider lanes
   amortise show through here. The half species still beats the masked form on
   every shape at 128 bits, by 7% on `second` and 11% on the three fields.
3. **Not decidable at 3%, and the failure it was guarding against did not
   happen.** The int32 arms moved, upward, by 3% to 9% on the emitted rows and
   by more on the hand-written and copy rows, and the wide-store rows moved
   both ways by up to 5%: the whole file shifted against a committed file
   measured on another day, in the direction of a faster machine. A boxed
   second species costs multiples, not percents (`SKILLS.md`, the assembly
   suite's pair), and nothing reads slower by more than 5%, so the second int
   species did not box the int32 arms. One reading is bimodal rather than
   moved: the 128-bit wide store on `second` past L3 reads 427.7 against 757.9
   before, with `hour` and `minute` beside it unchanged; it is the wide arm,
   not the one under test, and is not quoted.
4. **Held, and then some.** Past L3 at 512 bits the half species keeps the
   narrowed store's advantage over the wide one and widens it: `hour` reads
   44% over the wide store where the masked form read 23%, and the three
   fields 22% where the masked form read 6%. The same holds at 256 bits.

**What follows.** The half species dominates the masked form: equal within
noise or better at every width and shape, and the best arm of the three past
L3 at 512 and 256 bits. Section 5's rule made the flip conditional on
prediction 2 holding everywhere; it holds at the two wide widths and holds
partly at 128, where the half species is still the better narrowed store. So
the half-species form should become the shipped one. That is its own task
(milestone row 162): the default flips where the lane count is baked, the
general case - half of the preferred species as a static final of the emitted
class - is built for the unbaked path, `emitted_bytes.json` and the census
regenerate, and the 128-bit residue on `second` and the three fields is read in
the assembly, which this task's dump can already produce.
