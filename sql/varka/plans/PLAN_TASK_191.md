# Task 191: Emission time linear in the kernel's width

*Scoped 24 September 2026 (milestone 6 row 191, from `PLAN_TASK_190.md` 9.1);
opened 27 September 2026.*

## 1. Where this came from

Task 190 lifted the op cap under the byte budget and priced what a wide
emission then costs. `VarkaEmissionBenchmark`'s wide section
(`VarkaEmissionBenchmark-jdk25-results.txt`) emits four-op outputs -
`greatest(greatest(add_months(d, k), date_add(d, k)), last_day(d))` - once
per iteration: 25 outputs in 1641141 ns, 50 in 3876786, 100 in 10122976, 200
in 31042842 and 400 in 103358023. Each doubling of the outputs costs 2.4 to
3.3 times as much, and the ratio grows with the width. Row 191 read that
as quadratic and guessed at the term: every group's four methods plan their
slots over the whole kernel's topological order (`Slots.plan`), so planning is
groups times nodes.

Task 219 made the row the next thing to do. Its bisection of class-wide
declines (`PLAN_TASK_219.md` 10) cut the compiler's asks on a wide projection
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
this task's first commit, the way task 189's census tool was.

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
  size is what task 169's class-wide decline is about, not this task's.
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
* The byte budget and its regroup (tasks 87, 168), the bisection of
  class-wide declines (task 219) and the driver's plan.
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
| `sql/varka/plans/PLAN_TASK_191.md` | this plan |
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
   8000-byte budget may regroup into fewer groups. Task 169's sixty-`make_date`
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

To be written when the measurement lands.
