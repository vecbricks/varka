# Benchmarking method

How a number here earns the right to be quoted, and the ways a benchmark lies.

One of Varka's lesson files; the index over all of them is
[`SKILLS.md`](../../../SKILLS.md) at the repository root, which is generated from
these files by `dev/varka_toc.py`.

## A benchmark that reuses `repeats` for wall-clock scaling must also scale its declared row count

`Benchmark`'s Rate/Per-Row columns divide the measured time by the row count passed to its
constructor, not by what the timed closure actually does. A section whose closure loops
`repeats` times over `numRows` (the pattern this file's "year" and alignment-ladder sections
both use, to pay the per-call prologue at production rate rather than timing one giant call)
must construct `Benchmark` with `numRows * repeats`, not `numRows` - passing the smaller
number does not corrupt any *ratio* between cases in the same section (both are scaled by the
same missing factor), but every absolute Rate and Per-Row figure comes out wrong by exactly
that factor, silently, with no error and a plausible-looking table. Sanity-check a new
section's absolute numbers against a neighboring section of comparable op count before
trusting them, not just its ratios.

## The benchmark controls are necessary and not sufficient

- `VarkaEmitterParityBenchmark` carries nine controls - per-row `LocalDate`, the scalar
  `year` variants, the row-engine parsers - and the rule has been "if these moved, the
  machine moved". In September 2026 a regeneration held every one of them within -0.2%
  to +0.8% and was still unusable: its AVX-512 sub-microsecond dense rows had fallen
  33.5% and 20.8% against the file before it while the 128-bit file's held. The controls
  are all long-running cases at tens of nanoseconds per row. They are simply insensitive
  to whatever perturbs a kernel that does a million rows in 0.08 us, so they can be flat
  while the fastest third of the file is not.
- Two checks catch what they miss, both free and both readable off the file itself
  rather than off a second run. **A masked row may not beat its dense twin** - same
  kernel, strictly less work on the dense side. **On a saturated dense shape a wide row
  may not lose to its own 128-bit companion** - same code, more lanes. The disturbed
  file broke both: `arithmetic depth 4, mixed nulls` led its own null-free row by 23.7%,
  and the wide `greatest(d, d2), null-free` sat behind the narrow one. Either check
  would have caught it the day it was written; instead it was committed, quoted into two
  plans and a milestone section, and found in review.
- When they fire, `dev/varka_bench_regen.sh`'s standing instruction applies - re-run the
  base commit the same day - and it is worth the wall time. The re-run put `date_add
  emitted loop, null-free` back to 19227.8 from the disturbed 12754.1, against the
  19180.2 it had read before, so it returned to where it was rather than to somewhere
  new. It also moved one figure a whole task was reasoning from by a factor of five: the
  OR root's AVX-512 gap read 5.5% on the bad file and 27.7% on the good one.

## A `--rounds` probe with no nulls compiles only half the kernel

- `dev/varka_emit.sh --rounds N` ran the emitted kernel hot with `Array.fill(numInputs)(0)`
  as the null counts, and the emitted `run` dispatches a null-free batch to the *dense*
  driver. So every `-XX:+PrintCompilation` and `-XX:CompileCommand=print` probe taken
  through that tool had been looking at `loopDense0` and had no way to see the masked
  body at all - which is the half that task 70 changed, that carries every validity
  word, and that the parity file's mixed-null rows measure. The first probe of the
  105x row came back "both arms compile identically" for exactly that reason, and the
  reason was invisible: the tool prints method sizes for both bodies whether or not it
  runs them. `--nulls N` now drives the masked path, and a probe that means to reason
  about a mixed-null row has to pass it.
- The general form: a diagnostic that *emits* everything but *executes* one path will
  answer questions about the path it did not run, confidently and wrongly. When a probe
  agrees with neither hypothesis, check what it actually executed before believing it.

## Flipping a default silently retires every A/B built on `DEFAULTS`

- An A/B pair in the parity benchmark is two kernels emitted from
  `VarkaEmitOptions.DEFAULTS` and `DEFAULTS.with<Option>(false)`. That is exactly right
  until some *other* option's default flips underneath it and removes the work the first
  option governs. Task 70 turned `validityByBitmap` on, so a served root makes no
  per-group validity call at all; task 46's three pairs - the width-named helpers and the
  OR's position, both of which only change how that call is made - were left comparing two
  byte-identical kernels, and the regenerated file committed three rows that priced
  nothing while `VarkaEmitOptions`' javadoc still cited them as the evidence for those
  options. The 128-bit numbers say it plainly in hindsight: the pair that had read -21%
  against its comparand read -1.6% after the flip.
- **The tests caught their half and the benchmark could not catch its own.** Task 46's
  three *naming* tests assert that `orValidityBitsAt16` appears in the class, so they
  failed the moment the call disappeared and were re-pinned on the reference arm in the
  same commit as the flip. Its two *behavioural* tests compare results across the option
  and passed, because two identical kernels do agree - and so did the benchmark, which
  only prints numbers. An assertion fails when its subject vanishes; an A/B measurement
  reports a tie.
- The rule that follows: **build an A/B's arms from the variant they belong to, never
  from `DEFAULTS`**, so the pair says which arm it rides and survives the next flip -
  `perGroupWrite.withValidityByWidth(false)` rather than
  `DEFAULTS.withValidityByWidth(false)`. And when a default does flip, walk every other
  pair in the file and ask what work each one's option still governs; three of them here
  had none left. The same is true one layer up: a *test* that compares two settings for
  equality is not evidence that either setting does anything, so pair it with an
  assertion on the emitted names, which is what fails when the work is gone.

## Write the Prediction Down, Then Measure

- Three perf predictions in this repo's plans were reversed by measurement: "the
  dense path won't beat masked-with-all-true" (it won 2.3x-2.9x), "the masked body
  needs masked ops" (unmasked + validity words doubled mixed-null throughput), and
  "no cliff at the op cap" (there was one, and it moved). JIT-adjacent performance
  intuition loses often enough that the plan should record the expectation, the A/B,
  and ship whichever wins - the written-down prediction is what makes the reversal
  visible and the numbers re-checkable.
- **Check what a benchmark never executes before believing what it says about a
  change there** (task 24). Every committed harness in this repo happened to be
  lane-aligned - this file's parity benchmark ran one call over 1,000,000 rows, the
  engine JMH's sizes are 32 / 10000 / 1000000, and Spark's default
  `COLUMN_BATCH_SIZE` is 4096, all multiples of 4, 8 and 16 - so `loopBound ==
  length` everywhere and the emitter's scalar remainder path had never executed a
  row under measurement. Any remainder-handling change was invisible to every
  committed number. When the code under test has an aligned fast path and a
  remainder path, the size ladder needs sizes like 4095 and 63 on it deliberately;
  a pair one row apart isolates the remainder (equal call counts), and a magnified
  pair (64/63) makes a per-row cost measurable that a 4096-row batch hides in
  noise. Two more measurement lessons from the same task: a cost quoted at one
  rung of a ladder is not a bound on the whole ladder (task 21's "~1-3 ns/row"
  copy cost, read as a ceiling, under-predicted the compress win threefold - the
  scalar copy grew with selectivity and the ceiling was one point on that curve);
  and an in-run control (cases the change cannot affect, measured in the same
  process) is what turns "the numbers moved" into "the noise floor is 15% and the
  effect is inside it".
- **A task that shrinks emitted bytecode must regenerate the committed benchmark
  file, or the next task to regenerate inherits its win** (task 48, measured).
  Task 51 removed the per-extraction range guard - two compares plus mask work on
  every calendar node's tail - and shipped without regenerating
  `VarkaEmitterParityBenchmark-jdk25-results.txt`. Task 48's regeneration
  therefore showed `year, null-free` moving 1823.4 to 2166.5 M rows/s, a fifth,
  for a change whose own A/B measures 1.01x. Three things separated the two, and
  all three are worth reproducing: an **in-run control** (`per-row LocalDate
  year`, which no Varka change touches, read 481.1-481.6 against a committed
  479.4, proving the machine had not drifted); an **in-run A/B** (both sides of
  the change as adjacent cases in one `Benchmark`, which is what actually
  isolates the task); and `git log` on the results file itself against `git log`
  on the emitter directory, which named the two commits that had landed in
  between and left exactly one candidate. Without the first two, a plausible and
  entirely false 21% could have been written down.
- Debugging corollary from the same stretch: before concluding files changed or
  vanished, verify the working directory. A shell whose cwd resets between commands
  plus relative paths fabricates convincing evidence of disaster; absolute paths in
  forensics, always.

## A benchmark guard that fails in one direction gets satisfied by the failure in the other

Task 62's surface driver fails a run whose *fixed share* - `(wall - executor) / wall`, the
part of wall time that is not executor time - is over 5% on a Varka row. It exists to catch a
job too small to amortise its planning and scheduling. On 12 September 2026 it passed a run
that measured nothing at all, and passed it with the best number it had ever produced.

| rows | `date_add(d, 3)`, Varka arm | worst fixed share | verdict |
|---|---|---|---|
| 1e8 | **854 M rows/s** | 15.4% | failed the rule |
| 2e8 | **72.6 M rows/s** | **1.9%** | **passed** |

An eleven-fold throughput collapse, reported as an improvement, against 1811.6 M/s in the
committed laptop file. The cause was in the log 392 times and nowhere else:
`WARN MemoryStore: Not enough space to cache rdd_4_0 in memory!`, followed by
`Persisting block rdd_4_0 to disk instead`. The table did not fit, so the benchmark timed the
runner's SSD - served out of something that was still, to everything downstream, a cache.

**The rule was not merely blind to this - it was fooled by it, and the worse the failure the
better it looked.** A job whose cache does not fit has an enormous executor time, so the
constant driver cost becomes a vanishing fraction of it. The metric that catches "too small"
is *maximised* by "catastrophically too big".

So the lesson generalises past this benchmark: **ask of every guard what its own violation
looks like from the far side.** Here the question is "what does a run that is far too big look
like to a rule that catches runs that are too small?", and the answer - "healthy, and getting
healthier" - is the bug. A one-sided rule needs a partner before it can be trusted, and the
partner here is a residency check that refuses to write a file whose cached table is not
entirely in memory. The two now bracket the row count from opposite sides.

**And the storage level is worth setting explicitly in any benchmark that caches.** Spark's
default is `MEMORY_AND_DISK`, which is right for a workload - finish rather than fail - and
wrong for a measurement, which should fail rather than quietly measure something else. It is
what turned "does not fit" into "works, at storage bandwidth". `MEMORY_ONLY` removes that
path and makes *cached* mean one thing: under the default, a table wholly on disk still
reports every partition cached, so a residency check has to test bytes-on-disk separately.

Three things that were **not** the cause, each checked rather than assumed, because each was
the obvious guess:

* **Not the heap.** 6g, 8g and 11g gave 392, 389 and 389 warnings - identical. The limit is
  per *block*: with one partition the whole table is one block, and Spark will not cache a
  single block bigger than its unrolling memory however large the heap. More partitions is the
  lever, not more memory.
* **Not the stock arms.** Every warning in all three runs was in the Arrow-cached `varka-jdk25`
  arm; the arms using Spark's own compressed cached-batch serializer were fine.
* **Not a scaling law.** A "2.51x wall time per doubling of rows" measured across the runs
  looked like superlinear cache-residency behaviour and was used to project an eight-hour
  surface. It was the onset of this cliff. A smooth-looking exponent fitted across a
  discontinuity will mislead confidently.

## A benchmark number is reproducible within a run and not between runs

Two regenerations of `VarkaEmitterParityBenchmark` with no code change between
them disagree on rows nothing touched: pinned, the median case moves 1.6% but 73
of 211 move more than 3% and 22 move more than 10%, the worst near 26%; unpinned
the worst is 75%. This was found by refusing to commit a regeneration whose diff
looked like a regression, and then asking what an unchanged file does.

Each candidate was measured out rather than argued out. Within-run noise: the
average iteration over the best iteration has a median of 1.007 across 207 cases
and never exceeds 1.5, so every case is tight inside its own run. The clock:
sampling `scaling_cur_freq` through six runs of one case gives 5.08 to 5.14 GHz
while that case's throughput moves 31%. Address layout: `setarch -R` does not
narrow the spread. Contention: the machine is idle.

What remains is the per-fork C2 lottery `PLAN_TASK_32.md` 11 had already traced
to JDK-8380195, "Vector API produces bimodal performance - nondeterministic C2
intrinsification across JVM forks", closed Not an Issue. Read that section
before re-deriving any of it: it also refuted buffer alignment, OSR, unroll
limits and forced inlining as levers.

**Two effects are this machine's own, and those are fixable.** The Ryzen AI 9 HX
370 is heterogeneous: four Zen5 cores at 5.16 GHz and eight Zen5c at 3.29 GHz,
on two separate 16 MB L3 slices, and an unpinned thread is rescheduled between
them mid-run. The clock is worth 1.57x, and the datapath is not the difference -
the measured ratio 1.5632 matches the clock ratio 1.5680 to 0.3%, so a Zen5c
core is a slower clock and not a narrower machine. Migration also costs L3
residency where the working set fits one slice: 154 GB/s becomes 37.7 GB/s.
`dev/varka_bench_regen.sh` now pins to the fast complex and records the pin.

**The rule.** An A/B whose arms sit in the same run is sound - one JVM, one
layout, one clock - and that is how every A/B here is built, which is why task
79's arm-context pair read 0.5% and 1.4% across two runs whose absolute rates
disagreed by 75%. A number compared against a *previous* run is not sound below
the band, and the regeneration diff's 3% threshold is below the band for every
memory-bound row. Run `dev/varka_bench_repeat.sh` to measure the band before
reading a diff as a regression.

## A count of calls on an owner is not a count of the work you mean

- `VarkaEmitterTestSupport.invocationCount(bytes, method, owner)` was the natural tool
  for "how much validity work is in this method", and it cannot answer that question:
  `loadSegment` emits `VarkaVectorSupport.ofAddress` for every segment a body touches,
  in every body mode, so the count never reaches zero however much validity work is
  removed. Task 70's plan had registered a table of targets of "0" against it - numbers
  no run could have produced, which would have been discovered by whoever tried to
  assert them and quietly replaced with a different metric than the milestone accepted.
- The fix is an exclusion list, exact-matched (the helpers carry a lane-count suffix
  since task 46, and `orValidityBitsAt` is a prefix of `orValidityBitsAt16`). The
  general lesson: when a plan registers an op count, name the owner *and* what is
  excluded, and check the tool can produce the target before the number is registered.
  `dev/varka_emit.sh` now prints the validity count beside `IntVector` and `VectorMask`,
  so the check is a command rather than an argument.

## An inventory made by reading is not an inventory made by counting

- Task 70's plan listed the consumers of a validity word in the masked body by reading the
  emitter: the root's per-group write, `IfElse`'s blend, and - the correction the review
  added - the range guards' AND with their condemning mask. Three, and the plan's op-count
  table in 3.3 was derived from the three. There is a fourth: `emitPick`'s null substitution
  reads *both operand words for the value* (`a.blend(b, ~validA)` and its mirror), whether
  or not the pick's own word is wanted afterwards. The liveness pass had it, because it was
  written from the emission sites; the registered count did not, because it was written
  from the list; and the test that asserts the table failed on `greatest(d, d2)` the first
  time it ran - 2 calls left, not 0.
- The fix that matters is not the corrected row, it is the mechanism that found it: since
  step 1 every word load goes through one call that counts, every store through another,
  and each body asserts at its end that the two sets agree. A list of consumers made by
  reading is a design aid; the count is the record. Whenever a plan registers a number
  derived from an inventory of emission sites, arrange for the emission to count itself and
  assert the number, and expect the first run to correct the plan.
- The same session had the mirror lesson from the other direction. A first version of the
  algebra-agreement check also held a word expression's *operator* to the class of the node
  owning it - an AND must be owned by a node that ANDs, an OR by a pick. Twenty fuzz jobs
  out of twenty refused it: `least(month(dayOfWeek(date_add(d, off))), weekday(datediff(
  thursdayOf(d), dayofyear(off))))` has two operands whose words are both `w_d & w_off`, so
  the pick's OR of two equal ANDs folds to that AND, and a `Least` legitimately owns an AND.
  Structural folding makes the top operator a property of the *expression*, not of the node
  that computes it. A check that reads plausibly from the emitter's own rules can still be
  wrong about what those rules produce; the fuzzer at a million iterations is the tool that
  says so before a plan does.

## A default decided from a regeneration costs a second regeneration

- Task 70's plan said "one regeneration, section 9 with the predictions scored; the default set
  by 6.1's rule". Those two clauses cannot both be true of one run. The committed results
  file's plain rows are, by the repo's rule, the shipped bytes; the run that decides the default
  is taken with the old default, so its plain rows are the old bytes and its variant rows the
  new; and the moment the default moves, that file's labelling is wrong - the row called
  "shipped" is the reference arm and the row called "(task N A/B)" is what ships. There is no
  way to relabel it honestly, because the two arms were not measured under the names they
  would now carry. So the sequence is: measure with the variant, decide, flip, rename the
  variant to the reference arm (task 45's "validity OR-ed per group" is the model), and
  regenerate again. Forty minutes of idle machine, and the plan should budget it.
- Keep the first run's numbers out of the plan except for the few the decision rests on, and
  allowlist those with the reason: the commit that carried the first file is squashed away on
  merge, which is the provenance trap from earlier the same day. Score every prediction from
  the second file, since that is the one a reader can open.

## A number superseded inside its own PR loses its provenance when the PR squash-merges

- `dev/varka_quote_check.py` proves every number a document quotes traces to a committed
  results file, and it searches those files' git history as well as their current contents.
  That history is the branch's history only until the PR merges: vecbricks/varka
  squash-merges, so a results file that was committed and then regenerated *within one PR*
  reaches master in its final form alone, and the intermediate commit that carried the old
  number is gone. A document sentence quoting the old number was traceable on the branch and
  is an orphan on master.
- This is not a corner case, it is what the benchmark discipline produces. Re-running a base
  commit when the controls look flat but the fast rows moved (see "The benchmark controls are
  necessary and not sufficient") means committing a run and then superseding it, and the
  honest way to record the correction is to quote both numbers. Task 70 did exactly that for
  `date_add emitted loop, null-free` - 19227.8 against the disturbed 12754.1 - and master
  came out of the merge failing its own quote gate with exit code 2, in a PR whose own gate
  had been green on every run.
- So the check to make is on the *merge result*, not the branch: after a PR that regenerated
  a results file more than once, run `dev/varka_quote_check.py` against master. Where the
  orphan is a number the text quotes because it was wrong, the allowlist is the right home
  for it - that is what a ratchet with reasons is for - and the reason should say the
  provenance was squashed away, so nobody later hunts for a file that cannot exist.
- **And the mirror failure, which is quieter: a quote that traces perfectly and is stale.**
  The same history search that rescues a superseded number also means a document can keep
  quoting figures no current file carries, indefinitely, while the gate stays green. Task 64's
  plan was found this way on 11 September 2026: every one of the twelve numbers its admission
  check rested on - the guard's price from the parity files, the pre-pass's from the
  throughput files - had been regenerated away by tasks 70, 71, 76 and 77, and the quote check
  had passed over all of them because the git history still had them. The plan read as
  current and its summary sentence, "10-15% of the one shape that pays it", had become 4.4%
  to 15.9%.
- The tool is not wrong - proving provenance and proving currency are different questions,
  and it answers the first by design. What follows is a habit rather than a fix: **before
  acting on a plan that quotes benchmark numbers, grep the files it cites for the figures it
  attributes to them.** It costs one command, it is the only way to tell a requoted plan from
  a stale one, and a plan whose *decision* rests on a number - an admission check, a
  registered prediction, a default chosen from a committed rate - is exactly where a stale
  quote does damage. A `--current-only` mode that reported quotes matching history alone
  would turn the habit into a gate, and is worth a tooling row if this recurs.
- The related trap when checking by hand: a number can appear in *some* results file by
  coincidence, so grep the file the sentence names rather than the whole benchmark directory.
  A corpus-wide search said four of task 64's twelve numbers were fine; per-file, none was.

## A band says which moves to read; an invariant says which to stop the line for

Task 77 was opened because a kernel fell from 273 to 8.8 M rows/s at 128-bit and
the number went into three committed files with nobody remarking on it. The
instinct is to make the diff louder. That does not work, and measuring the file
says why: over ten runs with nothing changed, the parity file's median case moves
5.34% at AVX-512, its p90 22.3%, and its worst 227%. A threshold low enough to
catch a collapse flags a third of the file every time, and a reader who has
learned to discount large moves discounts the real one too.

Two different instruments, and conflating them is what left the collapse
uncaught for three regenerations:

- **The band is for reading.** It records, per case, what an unchanged file does,
  so a move can be compared against that case's own noise instead of a flat
  number. Held out, that cuts false alarms from 32.9% of rows to 5.6%.
- **The gate is for invariants.** What made the collapse different was not its
  size but that it broke something that must be true: the fused kernel became
  slower than the sixty-four separate passes it exists to beat. That is an
  assertion, it is independent of every number in the file, and walked back over
  76 committed revisions it fires three times - the collapse and the two
  regenerations after it - and never otherwise.

Two rules fell out of building them.

**A band is a threshold, so it only has to know which cases are noisy.** The
split-half check found that *which* cases are noisy reproduces strongly (worst
quartile 37 of 52 the same, against a chance of 13) while *how* noisy a given
case is reproduces weakly at the wide width (correlation 0.325). So the band
records a tier, not a spread, and does not publish precision the measurement
cannot support.

**Derive an invariant, do not assert one.** Every pair in the gate was checked
against every committed revision of its file before being written down. For the
end-to-end benchmark the direction was genuinely unknown - the debt register
records shapes where Varka loses - and measuring found 32 of 42 tables where it
wins in every revision and ten where it does not, which is a fact about the
read-back floor rather than a judgement call. A hand-written list would have got
that wrong in both directions.

## A benchmark that changes bytes per row must be a ladder, not a row count

Task 142 priced the 64-bit lane against the 32-bit one for eight shapes at a
million rows and read 2.74x to 3.74x, against an arithmetic prediction of about
2x. The invited conclusion - that a long lane costs three to four times an int
one - is false, and the same benchmark says so once the row count moves.

The cause is that doubling the lane doubles the working set, so the two arms are
not necessarily measured in the same place. At a million rows a two-column shape
holds 12 MB at the int lane and 24 MB at the long one, against a 24 MB L3: the
int arm fits and the long arm does not, and the ratio prices that boundary on
top of the lane. Run as a ladder - 16 384 rows (both arms in L2), 262 144 (both
in L3), 1 000 000 (the split), 8 388 608 (both past L3) - the in-cache rungs read
1.5x to 2.0x and the DRAM rung a flat 2.11x to 2.20x, which is the width and
nothing else. The 1M rung is the outlier in its own file.

So when a change alters bytes per row - a wider lane, a second column, a
different encoding - pick the rungs from the machine's cache sizes and the arms'
working sets, and make sure at least one rung has both arms on the same side of
every boundary. One row count in the middle is the one arrangement that cannot
be read, because it names the lane and measures the cache. The in-cache figures
below 2 are worth keeping for a second reason: they say a long lane group does
the work of two int groups under one set of loop overheads, so the wider lane is
cheaper than its width wherever there is issue slack to absorb it.

The same discipline one level up is `PLAN_TASK_134.md`'s partitions ladder,
which exists because one core and twelve cores are also not the same place.

## Two benchmarks of one project can need different kinds of band, not different thresholds

`dev/varka_bench_band.py` reports a per-case tier because that is what the parity
benchmark needed: over ten runs of an unchanged file, 73 of its 211 cases moved
more than 3% and 22 moved more than 10%, with a worst near 26%, so a flat
threshold would either miss real moves or cry wolf on the noisy cases, and
*which* cases are noisy reproduces well enough to act on.

The chains benchmark, measured the same way in September 2026, behaves nothing
like that: twelve runs, 24 cases, median spread 0.77%, p90 1.53%, worst 2.55%,
and not one case above 3%. The whole benchmark is one tier. Its split-half
correlation is 0.366, weaker than the parity benchmark's - and that number means
nothing here, because when every case sits inside 2.55% the ranking within the
band is noise about noise. A split-half check discriminates only where there is a
spread to discriminate.

The mechanism is the shape of the measurement, not the code under it. A chains
case is a whole Spark job over 2e8 rows, seconds long, where the per-fork JIT and
code-layout lottery averages out. A parity case is a short in-JVM loop where that
lottery is most of the variance. So before reading one benchmark's band practice
into another, measure what the other one does when nothing changes: the answer
decides whether you need tiers at all.

The second half of the same lesson is cost. Read off the committed files' own
timestamps, a chains arm takes about three minutes on this machine and a surface
arm at the committed 1e9 rows takes about 1h40m. A ten-run band is therefore half
an hour for one and seventeen hours for the other, which is why the surface's
band has to be sharded, moved to a runner, or done at a scale nobody commits -
and why "run it N times" is a plan for one benchmark and a project for another.

**The census, once every family had a band** (23 September 2026, seven files
across five families), settles what to do about the lottery in general, and the
answer is: nothing more. The instability is concentrated in the microbenchmarks
and is nearly absent from the files that carry public claims.

| file | cases | tier 0 | tier 1 | tier 2 | tier 3 |
|---|---:|---:|---:|---:|---:|
| `VarkaEmitterParityBenchmark` 512-bit | 211 | 60 | 85 | 49 | 17 |
| `VarkaEmitterParityBenchmark` 128-bit | 211 | 140 | 46 | 20 | 5 |
| `VarkaThroughputBenchmark` 512-bit | 84 | 27 | 31 | 26 | 0 |
| `VarkaThroughputBenchmark` 128-bit | 84 | 33 | 37 | 14 | 0 |
| `DateSurface` | 126 | 107 | 9 | 8 | 2 |
| `DateChain` | 24 | 24 | 0 | 0 | 0 |
| `TimeSurface` | 86 | 38 | 38 | 10 | 0 |

So **N forks per case with a median - what JMH does - is declined for this
project.** It costs N times every regeneration, and it would buy cross-run
comparability for the families that already have it while the family that needs
it most, the parity benchmark at 512 bits, is a microbenchmark whose every claim
is a within-run A/B anyway. The rule that replaces it, which is what the plans
already did in practice: absolute rates from different runs are not compared, a
number quoted across runs carries its band, a tier-3 case is not quoted at all,
and the regeneration diff is a reading aid while `dev/varka_bench_gate.py`'s
invariants are the gate.

One number in that table is worth carrying separately: the parity benchmark is
**quieter at 128 bits than at 512** - 140 cases in tier 0 against 60. Collapses
have historically been found at the narrow width not because it is less stable
but because it is stable enough for one to stand out, which is also why the two
widths need separate band files rather than a shared one.

## A kernel ratio is not an end-to-end ratio, in either direction

Task 142 measured what a 64-bit lane costs against a 32-bit one over memory
segments with no Spark above them: 1.5x to 2.0x while both arms are in cache, a
flat 2.11x to 2.20x once they are not. Task 29's plan then predicted what that
would be worth in a query - 0.45x to 0.60x of the int lane for a comparison
filter - by carrying the kernel ratio up a layer.

Measured (task 144), no case meets that band, and the misses go both ways. At two
million Arrow-cached rows the long lane costs 0.73x to 0.96x of the int lane,
because the cache read, the batch machinery and the filter's plumbing are most of
the work and none of them doubles with the lane. At twenty million rows one
filter stays at 0.97x while another falls to 0.31x - below the predicted band and
below the kernel ratio - because at that scale the fast shape is fast enough for
the surviving column's compaction to dominate, and the eight-byte compaction path
is a per-row copy where the four-byte one vectorises.

Two habits follow. When predicting an end-to-end number from a kernel number,
predict the *direction* and name what else is in the query, because the fixed
costs decide the magnitude and they do not scale with the change under test. And
when an end-to-end ratio surprises you, read the executed plans of the two arms
before explaining it: here the fast and slow cases differed by one clause -
`VarkaFilterColumnarToRow (pred)` against the same with `List(<column>)` - and
that clause, not the lane, was worth a factor of ten.

## Before pricing a shape, look for the benchmark that already priced it

In one night this project measured the same shape three times and read it three
ways. A crossed experiment said "forwarding a column costs ten times"; a
five-case separation said "no - narrowing the output costs eight times"; and a
control that forced the row read-back said neither, because with `toRdd` the
narrowed and un-narrowed forms are within 1% of each other. What is actually
there is that the un-narrowed shapes stay columnar under a columnar sink and the
narrowed one does not, which is the read-back floor arriving through a plan
difference.

The first two readings were each produced by an experiment that varied one thing
and stopped. The thing that would have caught both immediately was not a better
experiment: `VarkaNarrowingBenchmark` already existed, had measured this exact
shape at three selectivities and both widths for task 78, and its committed
numbers order the *opposite* way - the narrowed form slightly faster than the
two-column control. Nobody looked until after the second reading was written
down.

So: before building a benchmark for a shape, grep `sql/*/benchmarks/` and the
plans for that shape, and read the committed numbers first. If yours disagree
with a committed file, that disagreement is the finding and must be resolved
before either number is quoted - one of them is measuring something else, and
finding out which is cheaper than publishing both.

The second half of the rule is what to do once you have an anomaly: add the
control that distinguishes the candidate causes *before* writing the cause down.
Here it was one line - the same queries through `toRdd` - and it changed the
conclusion completely.

## A harness that checks how a row was produced, and never what it produced

The date-surface driver asserted a great deal per measured shape: that the plan
carried a Varka node, that no row-engine operator sat above it, that no batch
fell back, that some executor work ran, that the fixed share was under its
ceiling. It compared nothing the arms computed. A kernel that was fast and wrong
would therefore publish a rate like any other, and the run publishing it looked
exactly like a run that had not.

The differential suites do not close that gap. They cover correctness over a
thousand rows in a local session; the surface measures a different distribution,
row count, partitioning, cache format and Spark version, and it is the surface's
numbers that reach the README.

Two things worth carrying to the next harness:

- **The quantity you need is often already there and thrown away.** The driver
  had written `# selectivity:` per filter entry for months, and the table builder
  read it from every arm into one dict with `update()`, keeping the last. It was
  reading the same number from every arm and discarding all but one. Comparing
  them cost one line and was live immediately, with no regeneration.
- **Fold with a sum, not an xor, and reduce before summing.** An xor cancels, and
  benchmark data is repetitive by design - `year(d)` over a year of dates is one
  value - so equal hashes vanish in pairs and the checksum agrees for the wrong
  reason. A sum does not cancel, and reducing each hash modulo a value near 2^30
  first keeps the total inside an int64 without a check: a fold that silently
  overflows still compares equal between two arms that overflowed identically,
  so it keeps passing while meaning less than it claims.

And a rule about the check's own silence: when it has nothing to compare - an
old results file predating the line, say - it must say so. A check that silently
compares nothing is worse than no check, because the run looks the same either
way.

## Spark's benchmarks run with `spark.testing` set; a benchmark of a fallback clears it

`BenchmarkBase.main` sets the `spark.testing` system property "so the behavior between
running benchmark via spark-submit or SBT will be consistent". Under that flag several of
Spark's fallbacks are errors instead: a whole-stage codegen stage that fails to compile is
rethrown rather than run through its row-by-row operators (`WholeStageCodegenExec` guards the
fallback with `!Utils.isTesting`), and a refused subexpression or aggregate split is an
internal error rather than an INFO line. So Spark's own benchmarks never measure the
production fallback past 64 KB, and a benchmark written to measure it dies at the first
rung that crosses: `CaseWhenCodegenBenchmark`'s first runner dispatch did (`PLAN_TASK_210.md`
8). The class now clears the property at the start of its suite, since the fallback is what it
measures. Under sbt the same flag is also the `SPARK_TESTING` environment variable, which a
JVM cannot clear, so such a rung still throws there; the runners' `spark-submit` path is where
the full ladder runs. The quickest local check that a benchmark's fallback arm behaves as in
production is `bin/spark-submit` from a stock distribution, not `build/sbt Test/runMain`.
