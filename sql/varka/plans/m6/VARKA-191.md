# VARKA-191: Emission time linear in the kernel's width

*Scoped 24 September 2026 (milestone 6 row 191, from `VARKA-190.md` 9.1);
opened 27 September 2026.*

## 1. Where this came from

VARKA-190 lifted the op cap under the byte budget and priced what a wide
emission then costs. `VarkaEmissionBenchmark`'s wide section
(`VarkaEmissionBenchmark-jdk25-results.txt`) emits four-op outputs -
`greatest(greatest(add_months(d, k), date_add(d, k)), last_day(d))` - once
per iteration: 25 outputs in 1641141 ns, 50 in 3876786, 100 in 10122976, 200
in 31042842 and 400 in 103358023. Each doubling of the outputs costs 2.4 to
3.3 times as much, and the ratio grows with the width. Row 191 read that
as quadratic and guessed at the term: every group's four methods plan their
slots over the whole kernel's topological order (`Slots.plan`), so planning is
groups times nodes.

VARKA-219 made the row the next thing to do. Its bisection of class-wide
declines (`VARKA-219.md` 10) cut the compiler's asks on a wide projection
from one per output to a logarithmic handful, and each ask is one of these
emissions: `VarkaCodegenGiveUpSuite`'s G14, three thousand `date_add` outputs,
now takes nine seconds, almost all of it emission. What bounded planning time
was the number of asks; what bounds it now is the cost of one.

## 2. The admission check, done

Two things had to be true for the design in section 3 to be the right one:
that planning is where the time goes, and that the term is the one the row
guessed, or a better-named one. Checked on master `ad7a546a18b`, 27 September
2026, on the laptop; the readings are committed in
`VarkaEmissionProfile-jdk25-probe.txt`, and this section quotes them.

**2.1 The instrument.** `VarkaEmitDump` gained `--repeat N`, which emits the
compiled shape N more times and reports the median, so an emission loop can be
sampled by JFR; `dev/varka_jfr_frames.py` reads the recording's execution
samples and attributes each to the first emitter phase whose frame the stack
holds - a sample under `Slots.plan` is planning although the planner is called
from a body emitter - and to the nearest Varka frame to the top, so the JDK's
work on Varka's behalf is charged to the method that asked for it. Both are in
this task's first commit, the way VARKA-189's census tool was.

**2.2 Where the time goes.** The benchmark's shape at three widths, the byte
budget out of reach at all three so that every rung is one build with no
regroup, on a laptop at a load of three to five (a probe's reading; the
benchmark's number is the one above):

| outputs | ms per emission | slot planning | body emission | class assembly | elsewhere in Varka | the rest |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 12.4 | 35.1% | 25.2% | 22.0% | 8.6% | 9.1% |
| 200 | 43.2 | 56.5% | 15.8% | 13.6% | 9.7% | 4.4% |
| 400 | 205.8 | 71.8% | 9.2% | 8.2% | 7.3% | 3.5% |

Planning is the term, and it is worse than quadratic: its share times the
time is about 4 ms at 100 outputs, 24 at 200 and 148 at 400, six-fold per
doubling. The analysis passes, the grouping, the class measurement and the
debug attribute are each under three percent at every width and grow no
faster than the kernel. By the nearest Varka frame at 400 outputs,
`Slots.plan` itself holds half of all samples and `Slots.reachesGuardedDay`
another 4.5%, with `VarkaVectorIR.childrenOf` and the IR records' `hashCode`
under them; `VarkaLoopEmitter.build` - the Class-File API assembling the class
- holds 8.1%.

**2.3 Why: every method's frame is the kernel's.** The same shape emitted
once per width and parsed back, `max_locals` per method:

| outputs | methods | largest method | `max_locals`, largest | `max_locals`, mean |
| ---: | ---: | ---: | ---: | ---: |
| 25 | 32 | 4409 bytes | 726 | 399 |
| 100 | 104 | 5828 | 2766 | 1315 |
| 200 | 204 | 12412 | 5466 | 2566 |
| 400 | 404 | 25812 | 10866 | 5066 |

A method serves four outputs (the weight grouping puts about four of these in
a group: 404 methods are a hundred groups' four methods and the four the class
always has), and its frame grows with the whole kernel. `Slots.plan` walks
`analysis.topoOrder`, every distinct node of the kernel, and gives each a
word slot, a shared slot under CSE, a pair or scratch slot by node type - per
method, whether or not the method emits the node. Before that loop it scans
every output's tree four times (`producersGuarding`, `selfGuarding`,
`checkedArith`, `rearmed`, each an `anyMatch` over all outputs) to decide one
accumulator the body may need, and `reachesGuardedDay` walks with a fresh set
per output. So one method's planning is O(kernel) with a large constant, and
a kernel of g groups plans g times: the quadratic term, with the per-node
constant growing as the maps grow, which is the six-fold.

**2.4 The frames cost twice.** Of the 95 samples in class assembly at 400
outputs, 90 hold a `StackMapGenerator` frame, and the top frames are
`Arrays.fill`, `StackMapGenerator$Frame.checkLocal` and `processBlock`: the
Class-File API's stack maps are computed per method over its locals, so a
frame of ten thousand locals makes assembly superlinear too (its absolute time
2.7, 5.9 and 16.9 ms at the three widths, against a class that grows
linearly). And a local past 255 takes the four-byte `wide` forms of `iload`
and `istore`, so the frames inflate the methods' bytes, which is what the
byte budget measures.

**What the check would have rejected.** The row's second route first -
predicting bytes before emission (row 199) so that a wide kernel emits once -
would leave that one emission quadratic. And the row's own guess as stated -
a per-group *order* for planning - would remove the walk and keep the frames,
so assembly would stay superlinear and the bytes would keep their `wide`
forms; the frames are the thing to shrink, and the walk shrinks with them.

## 3. The design

### 3.1 Group-local frames

A group's loop and epilogue methods plan slots for the nodes they emit and no
others. Behind `VarkaEmitOptions.groupLocalSlots`, off until section 6's
measurement and flipped in the last commit, with the kernel-wide form kept as
the reference variant (the `FloorMod7` precedent):

* `Slots.plan` with `perGroup` first collects the body's node set - the union
  of its outputs' subtrees, one walk over the group - and the loop over
  `analysis.topoOrder` skips every node outside it. The order stays the
  kernel's, so children still precede parents and word aliasing still sees
  concrete child references; only the membership changes. A node outside the
  set has no entry in `wordRef`, `sharedSlot`, `pairTmp` or the scratch maps,
  so a body that reached for one would fail to build rather than read a stale
  local - the discipline the per-group output and literal slots already keep
  with their `-1`.
* The four accumulator scans and `reachesGuardedDay` run over the body's
  outputs (`outputIdx`) when `perGroup`: they decide the body's own
  accumulator, and a producer another group guards is that group's business.
* The driver keeps the kernel-wide plan. It zeroes every output's validity
  and runs the bitmap pass, so its frame is the kernel's by construction; its
  size is what VARKA-169's class-wide decline is about, not this task's.
* `liveWords` already takes `outputIdx` and stays as it is.

What this does and does not change. For a kernel of one group the body's node
set is the whole kernel, the walk visits every node in the same order, and
the slot numbering is unchanged: the bytes are identical, which is what the
tests pin across the switch. For several groups the slot numbers change and
shrink - a group's method holds its own few hundred locals rather than the
kernel's thousands, and the ones past 255 lose their `wide` forms - while the
operations emitted are the same, node for node.

### 3.2 What is deliberately unchanged

* The analysis passes, the grouping and the class measurement: each under
  three percent and linear (2.2).
* The byte budget and its regroup (VARKA-87, VARKA-168), the bisection of
  class-wide declines (VARKA-219) and the driver's plan.
* Predicting bytes before emission: row 199, which would remove the rebuilds
  the regroup pays; this task makes each build cheap.
* CSE's shared slots are still decided by the kernel-wide `useCount`, so a
  node shared across groups but used once in a body keeps its store and load;
  unchanged from today, and noted for row 199's reading.
* The Class-File API's stack maps: nothing to switch; they shrink with the
  frames.

### 3.3 Registered op counts

None move: a slot number is not an operation. `coverage.json` is unchanged.
`emitted_bytes.json`'s entries with one group are byte-identical; an entry
with several changes in local indices only, and the review of its
regeneration reads every method's invocation counts equal and its code size
equal or smaller.

## 4. Files

| file | what |
|---|---|
| `sql/varka/plans/m6/VARKA-191.md` | this plan |
| `sql/catalyst/benchmarks/VarkaEmissionProfile-jdk25-probe.txt` | the admission check's readings |
| `.../codegen/varka/VarkaEmitDump.scala` | `--repeat N` |
| `dev/varka_jfr_frames.py` | JFR samples by emitter phase and nearest Varka frame |
| `.../codegen/varka/Slots.java` | the body's node set, the loop and the scans over it |
| `.../codegen/varka/VarkaEmitOptions.java` | `groupLocalSlots` |
| `.../codegen/varka/VarkaEmitterBudgetSuite.scala` or a suite beside it | tests 1 and 2 |
| `sql/catalyst/src/test/scala/org/apache/spark/sql/VarkaEmissionBenchmark.scala` | the wide section's second arm |
| `sql/catalyst/benchmarks/VarkaEmissionBenchmark-jdk25-*.txt` | regenerated, both widths |
| `docs/sql-varka.md` | the two tools |

## 5. Tests, and what each is for

1. **A group's method frame holds its group's slots.** The benchmark's shape
   at 400 outputs with the switch on: every loop and epilogue method's
   `max_locals`, read from the class, under 400 (today 10866 at the widest),
   and the driver's unchanged. The test that catches a planner walking the
   kernel again.
2. **Byte identity where nothing should move, op identity where bytes must.**
   Over the emitted-bytes corpus, the switch off against on: a one-group shape
   byte-identical; a several-group shape equal per method in invocation
   counts and equal or smaller in code size.
3. **The answers.** The IR fuzzer at its default with the switch on, whose
   draws of up to three roots make several groups, against the reference
   evaluator; and the emitter contract suites, which drive kernels over
   batches with nulls and tails.
4. **The oracles**, `VarkaEmittedBytesSuite` and `VarkaCoverageSuite`,
   regenerated only where 3.3 says an entry moves, with that diff in the
   pull request.

Both widths: a frame does not depend on the species, so test 1 runs at the
host's width; the fuzzer and the contract suites run at both in CI as they
do today.

## 6. The measurement

`VarkaEmissionBenchmark`'s wide section, with a second arm per rung so both
forms are named whatever the default becomes - "n outputs, kernel-wide
slots" and "n outputs, group-local slots" - regenerated with
`dev/varka_bench_regen.sh` on an idle machine, both width files (emission does
not depend on width; the 128-bit file exists and is kept in step). The control
row is the narrow section's "four calendar outputs over one date", one group,
which must not move beyond its noise. The probe of 2.2 is rerun after, and its
shares recorded in the probe file's next section.

### 6.1 Predictions, registered before the run

1. At 400 outputs every loop and epilogue method's `max_locals` is under 400
   (from 10866), and does not grow with the width.
2. The wide section's per-doubling ratio is at most 2.3 at every rung (from
   2.36 to 3.33); 200 outputs under 17 ms (from 31.0) and 400 under 35 ms
   (from 103.4); 25 and 50 within their noise of today, having one to seven
   groups.
3. Planning's share of the 400-output emission is under 25% (from 71.8%),
   and assembly's absolute time at least halves (from about 17 ms), the stack
   maps shrinking with the frames.
4. No `coverage.json` entry moves; every one-group entry of
   `emitted_bytes.json` is byte-identical; every several-group entry changes
   in local indices only.
5. The one behavioural change beyond speed: a several-group method loses the
   `wide` forms of locals past 255 and is smaller, so a shape near the
   8000-byte budget may regroup into fewer groups. VARKA-169's sixty-`make_date`
   test still fuses a prefix and demotes a suffix.

## 7. Risks

1. **A body emits a node outside its planned set** - a lowering that reaches
   across outputs, or a shared prefix computed in one group and read in
   another. It fails to build rather than misreading a local, and the corpus
   and the fuzzer (tests 2 and 3) are where it shows. If one is found, the
   set widens to what the emission actually reads, and the plan records which
   node crossed.
2. **The stack maps do not shrink with the frames**, if the API's cost is per
   instruction rather than per local. Prediction 3 refutes it; the walk's
   saving stands either way.
3. **Noise at the narrow rungs.** 25 and 50 outputs are a millisecond or
   two; prediction 2 asks nothing of them beyond their band.

## 8. Sequencing

1. This plan, the probe file and the two instruments, committed first.
2. The switch and the planner change, tests 1 to 3.
3. The benchmark's second arm, the regeneration on an idle machine at both
   widths, the probe rerun.
4. The default flipped, the oracles regenerated where 3.3 allows, the docs,
   the row.

## 9. Outcome

*Written 27 September 2026, when the measurement had landed on an idle
machine and the default was flipped.*

Group-local frames are the default. A group's loop and epilogue methods
carry 174 locals at every width where they carried 726 to 10866, and one
emission of the wide section's four-hundred-output kernel takes 30535216 ns
where the kernel-wide form takes 107055659 - 3.5 times faster - with every
rung faster, down to 25 outputs at 1373370 ns against 1642955
(`VarkaEmissionBenchmark-jdk25-results.txt`; the 128-bit file agrees rung
for rung, 29736552 ns at 400 outputs). The narrow section, one group and
unchanged code, moved about one percent, the control reading.

**A correction to section 2, and a fix.** The admission check's profile was
taken through `VarkaEmitDump`, which handed the emitter its outputs as a
Scala `List` seen through `asJava`, whose `get(i)` walks from the head. The
planner indexes the outputs per method, so every emission in 2.2 also paid a
walk per lookup: the probe's times (12.4, 43.2, 205.8 ms) were inflated, and
planning's share with them (35.1%, 56.5%, 71.8%); the frames of 2.3, read
from the class, are unaffected. Production never paid it - the shape cache
copies the outputs into a random-access list - and the benchmark builds a
`java.util.List`, which is why the two disagreed about the form under the
switch (107.5 ms in the probe, 30.5 in the benchmark) until the reason was
found. `VarkaLoopEmitter.emit` now copies its outputs on entry, so no caller
can pay it again, and the probe rerun with the copy agrees with the
benchmark: kernel-wide 10.7, 32.2 and 113.3 ms at 100, 200 and 400 outputs
with planning 30.7%, 44.2% and 56.3%; group-local 7.5, 16.3 and 38.9 ms with
planning 6.8%, 9.4% and 11.3% (`VarkaEmissionProfile-jdk25-probe.txt` 4).
The conclusion of section 2 stands on the corrected numbers: planning was
the growing term, and it is what the switch removed.

Prediction by prediction (6.1):

1. **Held.** 174 locals in every loop and epilogue method at 25, 100, 200
   and 400 outputs, against under 400 predicted; it does not grow with the
   width.
2. **Held on the times, refuted on the last ratio.** 200 outputs in 12470134
   ns and 400 in 30535216, under the 17 and 35 ms predicted, and 25 and 50
   outputs faster rather than level. The per-doubling ratio is 2.03, 2.06
   and 2.17 through 200 outputs and 2.45 from 200 to 400, over the 2.3
   predicted. What is left superlinear is the driver: it keeps the kernel's
   frame by design (329, 629 and 1229 locals at 100, 200 and 400 outputs,
   section 10), and its planning and stack maps grow with the kernel.
3. **Held on planning, near on assembly.** Planning is 11.3% of the
   four-hundred-output emission, under the 25% predicted. Assembly's time in
   the corrected probe fell from about 15.6 ms to 8.8 ms, a factor of 0.56
   against the half predicted.
4. **Held.** `coverage.json` is unchanged; in `emitted_bytes.json` no
   coverage row's method hash moved - the coverage rows are single-output
   kernels, one group each, so their bytes are identical - and what moved is
   the fuzz sequences' block digests and the option arms' digests at both
   widths, fourteen entries, the several-group kernels renumbering. That the
   several-group ones keep their lane arithmetic is what
   `VarkaEmitterFramesSuite` asserts, over the same grammar.
5. **Not observed.** No shape in the suites regrouped under the switch; the
   frames suite counts a regrouping as allowed, and the budget suite's
   task-169 test still fuses a prefix and demotes a suffix.

What moved that the plan did not list: the dead guard accumulator (section
10), the list-access trap above, and the inputs a group's method sets up
(section 11).

What the task leaves: the driver's frame, the one method still planned over
the kernel, which is what keeps the last doubling above 2.3 - a driver per
group of outputs, or a leaner prologue, is the next step if a wider kernel
needs it; rows 222 and 223, the IR's structural hashing and CSE's kernel-wide
use counts; and row 199, bytes predicted before emission, which would remove
the regroup's rebuilds that each emission now makes cheap.

## 10. Step 2, built: the frames, 27 September 2026

*Written when the switch, the planner and the tests were in and every suite
had passed; the measurement of section 6 is the next step.*

**What is built.** `VarkaEmitOptions.groupLocalSlots`, off. Under it,
`Slots.plan` for a group's loop or epilogue method collects the body's node
set once - its outputs' subtrees - filters the kernel's topological order by
it, and runs the four accumulator scans and the guarded-day scan over the
body's own outputs; the driver keeps the kernel's plan. `VarkaEmitterTestSupport`
reads a method's `max_locals`, `VarkaEmitterFramesSuite` holds tests 1 and 2,
and `VarkaEmissionBenchmark`'s wide section has its second arm at every rung,
"kernel-wide frames" beside "group-local frames".

**The frames, read from the class** (the shape of 2.3, `Frames.java` after
`Locals.java`, the byte budget as the benchmark sets it):

| outputs | loop/epilogue `max_locals`, kernel-wide | group-local | driver's, group-local | class bytes, kernel-wide | group-local |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 25 | 726 | 174 | 174 | 119895 | 108435 |
| 100 | 2766 | 174 | 329 | 480275 | 419015 |
| 200 | 5466 | 174 | 629 | 969790 | 842130 |
| 400 | 10866 | 174 | 1229 | 1947790 | 1687330 |

Prediction 1 holds with room: 174 locals at every width, not under 400 at
one; the frame is the group's and the group is four outputs whatever the
kernel. The driver's frame grows with the kernel as 3.1 says it must. The
class is 13% smaller at 400 outputs, the `wide` forms gone from the bodies;
where the largest method is the driver (200 and 400 outputs) it is unchanged,
and at 25 outputs the largest method fell from 4409 to 3689 bytes.

**A finding, and prediction 4 refined.** Test 2 found a several-group shape
of the shared grammar - fuzz shape 4, roots `addDays(lit0, lit1)` and
`addMonths(col0, col2)`, a constant output alone in one group and a
self-guarding node in the other - whose first loop method emits two fewer
mask operations with group-local frames. Planned over the kernel, every body
allocated the batch-condemning guard accumulator whenever any output of the
kernel guarded, and a body that guards nothing still initialised it
(`VectorMask.fromLong`) and tested it on exit (`anyTrue`): two operations that
could never fire, returned as "no batch condemned" every time. Planned over
the group, such a body has no accumulator. So 3.3's "the same operations" is
"the same lane arithmetic, node for node, and a body that guards nothing
loses the accumulator's two mask operations"; the suite's criterion says
exactly that, and no method grew. The bytes of a one-group kernel are
identical, as 3.3 said: over the grammar's first three hundred shapes and the
wide shape at 25 and 100 outputs, every one-group kernel matched byte for
byte and every several-group one kept its arithmetic.

**Tests.** The frames suite's two tests; the IR fuzzer, whose reflective draw
now covers the switch both ways, at its default; the emitted-bytes and
coverage oracles unchanged, the default being off; the full Varka suites of
`sql/catalyst`. Checkstyle, scalastyle, the javadoc build, the scans.

**Next.** Section 6's measurement on an idle machine, both widths, the probe
of 2.2 rerun, and the default flipped on the numbers, with the oracles
regenerated where 3.3 allows.

## 11. After review, 27 September 2026

*Written after a code review of the pull request, before the measurement.*

The review found the planner still doing kernel-sized work under the switch,
in three places, and the frames suite weaker than it read. What changed:

* **The body's nodes are its own list.** Filtering `analysis.topoOrder` still
  visited every node of the kernel per method, a membership test each: the
  groups-times-nodes term, cheaper per step. The body's node set is now
  sorted by the nodes' line numbers - their positions in the kernel's
  topological order - and walked alone, so the order the word aliasing needs
  is kept and a one-group kernel's slots are numbered as before.
* **The inputs are the group's.** Every method still set up every column the
  kernel read: a segment, null state and word, six locals a column, and the
  prologue's loads. `Slots.inputs` is now the columns the body's outputs read
  - the kernel's referenced columns without the switch - and the prologue and
  `wordKnownBeforeCompute` read it. For the benchmark's one-column shape this
  changes nothing; on a kernel whose groups read different columns it is the
  larger part of a frame.
* **The guard scans are passes over the node set** rather than walks of the
  group's trees, which revisited a shared subtree once per path.
* **The switch does nothing with the byte budget off**, where no group's
  method has a frame of its own; the option's javadoc says so.
* **The frames suite** compares operations only where both forms group a
  kernel the same way - grouping is decided by measuring bytes, and smaller
  methods can split differently near the budget, as prediction 5 says - and
  otherwise only that both build; a shape the group-local form declines that
  the kernel-wide form builds fails it, the other way round is allowed. It
  parses each class once (`VarkaEmitterTestSupport.methodProfile`), where it
  had parsed a two-megabyte class more than a thousand times.

Left as they were: CSE's shared slots by kernel-wide use counts, which is row
223; and the emission benchmark's committed results still carry the wide
section's old labels until step 3 regenerates them with both arms.

The frames are as section 10 read them - 174 locals in every group's method
at 25 to 400 outputs - and the IR fuzzer at twenty thousand trees per lane,
three seeds, with the switch drawn both ways, agrees with the reference
evaluator.

## 12. Step 3, measured, 27 September 2026

The measurement and the flip are recorded in section 9, where the plan asks
for them. The default is `groupLocalSlots` on; off is the reference variant,
and the option's canonical string names the off arm (`|kernelWideSlots`), so
every shape hash taken under the default is the one it was before the task.

