# VARKA-77: reading a regeneration without running it twice

## 1. Where this came from

`m4/PLAN.md` row 77, and section 2.39 **as corrected on 10 September
2026**. The row and the section are both named after the per-group validity OR.
That name is now only a label: the correction's controlled pair says the OR is
not what this task is about.

An earlier plan for this row was written against 2.39 before the correction,
inherited its mechanism, and was closed unmerged. This one starts from what the
correction established.

**Established.** A recompilation storm is real. An instrumented run of the parity
benchmark at 128-bit shows 957 runtime deoptimisations, 394 of them
`profile_predicate`, against 1764 compile-time insertions of the same name -
and it is the insertions the superseded reading counted. The worst method takes
100 deoptimisations across 194 compiles where a healthy method compiles once or
twice, and it is `ChronoVectorOps.vectorFourFieldsNoValidity`, a **hand-written**
reference kernel. The shape 2.39 is entirely about, `fused, 64 ops`, no longer
appears at all: VARKA-70's pass serves its root, which is a bare leaf.

**Not the per-group OR.** Benches 930 and 945 are one expression emitted with the
bitmap pass and with `perGroupWrite` forcing the OR. They storm identically, at
fifteen deoptimisations and twenty-two compiles each. That is a control inside
one run, and it retires the lowering as a suspect.

**Not established.** The mechanism. The polluted-profile story rested entirely on
the miscounted traps. The 10609 `task_queued` records are unread, and
`SKILLS.md`'s task-43 correction - that VARKA-11's "ten second compile" is better
read as a compile *queueing* behind others, a scheduling property no per-method
budget can bound - is a live alternative nobody has tested.

## 2. What this task is now, and what it is not

The cause is interesting and the **operational** problem is separate from it, so
this task takes the operational problem and leaves the cause to its own row.

The operational problem is the debt register's, recorded since VARKA-52: every
regeneration shows a cluster of unrelated rows 10-30% below the committed file,
a different cluster each run, once a single row at -99%. A reader cannot tell a
task's own movement from the run's cluster, so **every task since 52 has had to
run the whole file twice**. That is the tax, it is paid by every measurement
task in the milestone, and it does not need a cause to lift.

Three things lift it, and none of them is an engine change:

1. Measure what an unchanged file does, per case and per width, and commit it.
2. Have the diff classify a move against that, so reading a regeneration is a
   computation instead of a judgement call.
3. Catch the collapses that are *not* noise, with an assertion rather than a
   number - which is what row 77's "a committed row that would have caught the
   collapse the day it landed" is really asking for.

**Correction, 10 September 2026: items 1 and 2 above are milestone 5's VARKA-90,
not this task's.** That row was scoped on 9 September for exactly them - the band
committed per file, the regeneration diff reported against it rather than a flat
3% - and this plan re-scoped onto it without checking milestone 5's table. The
work is done and is credited to row 90, which now records what landed and what it
still owes; this task keeps item 3, the gate, which row 90 does not scope, and the
two repairs section 1 turned up: 2.39's correction and the row key. The mistake is
left visible here rather than tidied away, because it is the same one 2.39 made -
a claim adopted without enumerating the state it depends on.

**Explicitly not in this task.** Forking a JVM per section, which the superseded
plan proposed: forking was there to keep a profile clean, and the profile story
is what came out. The emitter. `GROUP_BUDGET` (VARKA-71), the validity helper
choice (VARKA-76), VARKA-47's remainder. The cause hunt, which is row 90's.

## 3. The admission check, to do first

### 3.1 Does a band reproduce per case?

This decides what the artefact can be, and the answer is not obvious. If a case
that is noisy in one set of runs is noisy in the next, a per-case band is
meaningful and a quiet case can be held to a tighter threshold than a noisy one.
If the noisy set is a fresh random subset each time - which is what "a different
cluster each run" suggests - then only a single file-level threshold is
defensible and per-case precision would be invented.

Ten runs per width of the parity benchmark, pinned exactly as
`dev/varka_bench_regen.sh` pins, split in half, each half measured
independently. `dev/varka_bench_band.py --split-half` reports the correlation of
per-case spread between halves and the overlap of their worst quartiles, which
is the statistic a per-case band would actually rest on.

### 3.2 Is the control gate computable from the committed file alone?

**Yes, and this is already checked.** `Benchmark.run()` returns `Unit` and prints
its results, so an in-process assertion would mean editing Spark's own
`Benchmark.scala`. It does not need to. The `Relative` column is each case's rate
against the *first* case in its table, so where the first case is the arm under
test and a later case is the reference it must beat, that reference's `Relative`
above 1.0X means the arm lost. The committed 64-op section reads
`sequential kernels, 64 passes` at 0.0X; at 8.8 M rows/s it would have read
above 1.0X. So the gate is a check over the results file, touching no upstream
code and no benchmark class.

What is **not** established is which rows are references. Many A/B pairs have no
"must beat" direction at all - `budget 16` against `budget 24` may legitimately
go either way - so a blanket rule would fire constantly. The list has to be
enumerated from the file and committed, not pattern-matched from names.

### 3.3 The keying, which both of the above need

`dev/varka_bench_diff.py` keys a row as `(table, case)`. That is not unique in
this file: four sections share the table header `20000000 rows in 4096-row
chunks`, and two rows share the case name `weekofyear (VARKA-37), null-free` as
well. A colliding key silently merges rows, and under `--requote` a merged or
shifted key is reported as absent rather than moved - a false all-clear on the
gate `sql/varka/AGENTS.md` makes the closing step of every regeneration.

So `(table, case, occurrence)` is the key everywhere: the band file, the diff and
the gate. `dev/varka_bench_band.py` already uses it.

### 3.4 What the check would have rejected

That the per-group validity OR has anything to do with this (2.39's controlled
pair). That forking is the fix (it was proposed to clean a profile). That a
per-case band is obviously meaningful (3.1 asks). That the gate needs a change to
Spark's benchmark harness (3.2 says it does not). And that `(table, case)` is a
key (3.3 says it is not, and the file already holds a collision).

## 4. The design

### 4.1 The band, measured and committed

`dev/varka_bench_band.py` over N runs writes a band file beside the results it
describes - `<Class>-jdk25-band.txt` and `<Class>-jdk25-128bit-band.txt` - with
one line per key and the provenance the run carries. Whether the line holds a
per-case spread or the file's single threshold is 3.1's answer, and the file
format is the same either way.

`dev/varka_bench_repeat.sh` becomes the driver: it already runs N times pinned
and parses rows, and it gains the narrow width, which it does not have today,
and the band file as an output instead of a stdout summary.

### 4.2 The diff classifies against it

`dev/varka_bench_diff.py --band <file>` marks each moved row as inside or outside
the band. Inside is printed quietly; outside keeps today's `<--`. The requote
walk is unchanged except that it now walks the outside set, which is the set a
reader must actually look at.

The keying fix of 3.3 lands here, and it is the part with a correctness
consequence beyond this task: today a shifted key is silently dropped from the
requote.

### 4.3 The control gate

A committed table of `(table, case)` pairs that are references - the arm the
first case in that table must beat. `dev/varka_bench_gate.py` reads a results
file and fails when a listed reference reads above 1.0X.

It runs at the end of `dev/varka_bench_regen.sh`, and it can be run against any
committed file, including historical ones, which is how 6.1's prediction 4 is
scored.

## 5. Files

| file | what |
|---|---|
| `dev/varka_bench_band.py` | committed with this plan; the band, and 3.1's split-half check |
| `dev/varka_bench_repeat.sh` | the narrow width, and the band file as its output |
| `dev/varka_bench_diff.py` | `(table, case, occurrence)` keys, and `--band` |
| `dev/varka_bench_gate.py` | 4.3, plus its committed reference list |
| `dev/varka_bench_regen.sh` | runs the gate; records the band file in provenance |
| `sql/catalyst/benchmarks/*-band.txt`, `sql/core/benchmarks/*-band.txt` | the measured bands |
| `m4/PLAN.md` | row 77, and the three debt entries this closes or narrows |
| `SKILLS.md` | what the band turns out to be, and the gate's idea |

## 6. Tests, and what each is for

* **The keying, on a synthetic results file** holding a duplicated `(table,
  case)` pair: the parse must keep both rows, and the diff must report a change
  to the second one. Today it does not, and that is a live defect rather than a
  hypothetical.
* **The gate, on a synthetic file** where a listed reference reads above 1.0X:
  it must fail, and it must pass on the same file with the row at 0.0X.
* **The gate's reference list is complete**: the count of listed pairs is
  asserted against the file, so a section added later without a reference is
  visible rather than silently ungated.
* **The split-half statistic on synthetic input** with a known answer - a set of
  cases whose noise is by construction per-case, and one where it is uniform -
  so the check can tell the two apart before it is pointed at real data.

## 7. The measurement, and 6.1's predictions

The measurement is the admission check: ten runs per width of the parity
benchmark, four per width of the throughput benchmark, all pinned and serial on
an idle machine.

Registered before the data:

1. **The per-case band does not reproduce.** Worst-quartile overlap between
   halves near chance, correlation under 0.3. The debt register's "a different
   cluster each run" is the reason to expect it, and if it is wrong - if noisy
   cases are stably noisy - that is a better outcome and a per-case band ships.
2. **The file-level median spread is under 3% and the p90 is over 10%**, which
   is the shape `dev/varka_bench_repeat.sh`'s header already reports from three
   runs (median 1.6%, 73 of 211 cases over 3%, worst near 26%). Ten runs should
   widen the tail, not the median.
3. **The narrow width is the noisier of the two.** Every collapse this milestone
   has recorded, this task's included, was found at 128-bit.
4. **The control gate fires on the known collapse and nowhere else.** Run over
   every committed parity file in git history, it flags the range from
   `aef0b82260e` to VARKA-70's commit and no other revision. This is the
   retrodiction that turns "a row that would have caught it" from an intention
   into a checked claim.

## 8. Risks

1. **A band is a property of this machine and this JDK.** It is committed with
   provenance and regenerated when the file is, and the diff says which band it
   compared against. It is not a constant to be quoted elsewhere.
2. **A gate with a missing reference is a silent gap**, which is why 6 asserts
   the list's completeness against the file rather than trusting it.
3. **Prediction 1 going the other way changes the artefact, not the task.** A
   per-case band is more useful; the plan ships whichever the data supports.
4. **The keying fix changes what `--requote` reports** on the next regeneration,
   possibly surfacing quotes that have been silently missed. That is the fix
   working, and it belongs in the outcome rather than in a surprise.
5. **This task does not explain the storm.** It makes the file readable while the
   storm continues. That is stated so nobody reads a green gate as a cure.

## 9. Sequencing

1. This plan, `dev/varka_bench_band.py`, and row 77 re-scoped.
2. The runs of section 7, serial on an idle machine.
3. 3.1's split-half, which decides the artefact.
4. The keying fix and its test, which the band and the gate both need.
5. The band files, the diff's `--band`, the repeat script's narrow width.
6. The gate, its reference list, its test, and prediction 4's walk through
   history.
7. The debt entries, row 77, `SKILLS.md`, section 10.

## 10. Outcome

**Prediction 4 was scored early, before this plan was reviewed, because it is the
falsifier for section 4.3 and it needs no machine - only committed files out of
git history.** A probe gate was walked over every committed revision of both
parity results files, 47 revisions in all, flagging any row that exists to lose
to the first row of its table and reads above 1.0X.

| file | revisions | flagged |
|---|---|---|
| `-jdk25-128bit-results.txt` | 15 | 3 |
| `-jdk25-results.txt` | 32 | 0 |

The three are `aef0b82260e`, the commit that caused the collapse, and the two
regenerations committed while it was still broken. In each the row
`sequential kernels, 64 passes` reads 2.0X, 2.1X and 2.0X - the sixty-four
separate passes beating the fused kernel that exists to beat them. Nothing after
VARKA-70's fix is flagged, and the wide file is never flagged, which is right
because the collapse was 128-bit only. So the gate fires on the known collapse
and nowhere else, with no false positive in 47 revisions.

Two caveats that belong with the result rather than after it. The probe selected
references by name (`sequential kernel`, `hand-written kernel`, `LocalDate`, and
two more), which is exactly what 3.2 says the shipped gate must *not* do; the
enumeration is still owed. And the coverage that heuristic reaches is 18 rows in
7 of the file's 12 tables, so five tables have no reference at all and are
ungated - which is what test 6's completeness assertion is for, and a number the
enumeration should try to improve.

### 10.1 The band, measured

Twenty-eight runs on an idle machine overnight on 10 September 2026, serial and
pinned to the fast CCX exactly as `dev/varka_bench_regen.sh` pins: ten of the
parity benchmark per width, four of the throughput benchmark per width. Every run
verified for row count as it finished.

| file | runs | cases | median | p90 | max | over 3% | over 10% | over 20% |
|---|---|---|---|---|---|---|---|---|
| parity, AVX-512 | 10 | 211 | 5.34% | 22.30% | 227.15% | 151 | 66 | 27 |
| parity, 128-bit | 10 | 211 | 1.72% | 11.88% | 39.06% | 71 | 25 | 12 |
| throughput, AVX-512 | 4 | 84 | 5.31% | 20.83% | 24.26% | 57 | 26 | 9 |
| throughput, 128-bit | 4 | 84 | 3.67% | 21.90% | 24.26% | 51 | 14 | 10 |

The worst single case is `12 outputs over one date, one method (1 loop methods),
null-free`, which ranges 43.1 to 141.0 M rows/s across ten runs of an unchanged
file - a 3.3x swing that any task reading a diff would have to interpret.

### 10.2 The predictions, scored - three of four missed, and the misses are the result

**1 missed, and the artefact is better for it.** It predicted a per-case band
would *not* reproduce, with worst-quartile overlap near chance and correlation
under 0.3. The overlap is 37 of 52 at AVX-512 and 39 of 52 at 128-bit, against a
chance of about 13 - roughly three times chance at both widths. The correlation
of the exact spread is 0.325 and 0.728.

So the two statistics disagree, and the disagreement is the finding: *which*
cases are noisy reproduces strongly, while *how* noisy a given case is reproduces
only at the narrow width. That is exactly the shape a band needs. A band is a
threshold, not a point estimate, and a threshold only has to know which cases
deserve a loose one. The plan pre-committed to shipping whichever the data
supported, so the per-case band ships and the file-level threshold is dropped.

**2 half missed.** It predicted a file-level median under 3% and a p90 over 10%,
reasoning that ten runs would widen the tail and not the median. The p90 holds at
every width. The median does not at AVX-512: 5.34%, against the 1.6% that
`dev/varka_bench_repeat.sh`'s header reports from three runs. More runs widened
the median too, so three runs understate the band and the header's number should
not be quoted as the band.

**3 missed, and backwards.** It predicted the narrow width would be the noisier,
on the grounds that every collapse this milestone has recorded was found at
128-bit. The narrow width is markedly *quieter* on every statistic: median 1.72%
against 5.34%, p90 11.88% against 22.30%, worst case 39% against 227%. The
inference was wrong in an instructive way - collapses were found at 128-bit
because the narrow file is the quiet one, where a collapse stands out, not
because it is the unstable one.

**4 confirmed**, scored early in 10 above.

### 10.3 What this changes in the design

Section 4.1's open question is closed in favour of the per-case band, so the band
file carries a threshold per key rather than one number per file. Section 4.2's
classification is per case accordingly.

One thing the plan did not anticipate: the two widths need different thresholds,
and by a factor of three at the median. A single band applied to both would be
far too loose for the narrow file, which is where collapses have historically
shown up - the opposite of a safe default.

### 10.4 What moved that the plan did not list

**The scope belonged to a row that already existed.** Section 2's re-scope -
measure the band, commit it, classify against it - is milestone 5's VARKA-90,
scoped a day earlier for those exact deliverables. This plan did not look at
milestone 5's table before claiming the work. Both rows now say so, and the band
half is credited to 90 with its remaining items listed. The lesson is the one
2.39 already taught and this plan then repeated: a claim that depends on existing
state is checked against that state, not adopted because it reads well.

**The row key was not unique, and the requote had been paying for it.** Four
sections of the parity file share a table header and two rows share a case name,
so `(table, case)` merged two rows into one: 207 rows parsed to 206, and
`--requote` reported the survivor's shifted key as absent rather than moved, which
is a false all-clear on the closing step `sql/varka/AGENTS.md` makes mandatory.
Fixed with a self-test built on the collision the file actually holds.

**A control row is itself unreadable.** `probe: trivial body, int[] (control)`
spreads 64.6% across ten runs of an unchanged file. The diff prints controls under
the heading "if these moved, the machine moved", and that heading is not true of
that row. It is left as found, in the band's unreadable tier, rather than quietly
dropped from the control set - deciding what the control set should be is row
90's, alongside the cause.

**The gate's completeness check earned its place on first use.** Walked back
through the record it found a table present in August and absent now, which is
recorded as retired. A section appearing or vanishing should be a decision.

### 10.5 What this leaves for later

The cause, entirely: milestone 5's VARKA-90. The census leaves it sharper than it
found it - `ChronoVectorOps.vectorFourFieldsNoValidity` takes 100 runtime
deoptimisations across 194 compiles and is hand-written, the 10609 `task_queued`
records are unread, and `SKILLS.md`'s task-43 correction offers compile queueing
as an alternative nobody has tested. Row 90 also still owes the arithmetic
benchmark's band, VARKA-63's 9.7 figure re-taken pinned, and the N-fork decision.
