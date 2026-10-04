# VARKA-97: A bandwidth-bound row lost 22.6% and nothing in its lowering changed

## 1. Where this came from

`m5/PLAN.md` row 97 and section 2.32, opened 14 September 2026 out of
VARKA-78's surface regeneration. `date_add(d, 3)` reads **1811.6 M rows/s** in the
surface committed on 7 September (`DateSurface-varka-jdk25-results.txt` at
`4d6ea4ca3aa`) and **1401.4 M/s** in the one committed on 13 September - a loss of
**22.6%** - with the machine canary clean on both runs, the same host, the same
governor and the same billion rows.

The question is not whether the number moved. It is whether the *engine* moved,
or the *fixture* did, and the answer decides whether roughly a third of the
surface - every memory-bandwidth-bound entry - is currently understated.

## 2. The admission check, done

**The lowering did not change.** `date_add(d, 3)` emits four `IntVector`
operations today, from `dev/varka_emit.sh --table`; `date_add(d, i)` emits four
as well. Of the fifty-five commits between the two runs, exactly one touches the
`AddDays` neighbourhood of the emitter - `2972c9a229e`, VARKA-93's re-arm - and
every hunk of it is a `case GuardedDay` arm, an import, or a slot allocation
behind `reachesGuardedDay`. A literal-offset `AddDays` tree contains no
`GuardedDay` node and reaches none of them. The column-offset support this shape
might otherwise be blamed on landed long before the first of the two runs.

**The fixture did change, and the check pins when.** The 7 September file carries
35 entries and none over an interval column. Today's carries 52, of which 8 read
`ymm`, `ymy` or `ym`. `git log -S "AS ymm,"` on the driver names `ef1a7dbdea7`
(9 September, "Year-month interval columns in the date lane") as the commit that
added them to `buildTable`, and that commit is an ancestor of the second run and
not of the first. So the cached table went from three int32 columns to six
between the runs, and its `cache:` line from about 12 GiB to **23.2 GiB**.

**What this check would have rejected.** If the op count had moved, or if a
commit between the runs had touched the emitted `AddDays` path for a literal
offset, the task would be a regression hunt over fifty-five commits rather than a
fixture question, and the design below would be the wrong instrument.

## 3. The design

### 3.1 A table-shape switch on the benchmark driver

`DateSurfaceBenchmark` gains `--table-columns all|dates`. `all` is today's six
columns and stays the default, so every committed file and every dispatch is
unchanged. `dates` builds only `d`, `d2` and `i` - the three the table carried
before `ef1a7dbdea7` - which is the fixture the 1811.6 M/s number was measured
over.

The switch is on the *table*, not on the entry list: the same entries run either
way, and an entry that reads a column the narrow table does not have is refused
by the driver rather than silently skipped. That refusal is the point - it makes
the pairing explicit instead of leaving two runs with different entry sets to be
compared by eye.

The shape is recorded in the provenance as `table columns:`, so a results file
says which fixture produced it and the comparison is checkable from the files
alone.

### 3.2 What is deliberately unchanged

The entry lists, the row count, the guards, and the default. This task measures;
it does not decide the fixture. If the hypothesis holds, *what the surface should
build* is a decision for its own task, because it trades reproducibility of the
committed history against measuring each row over a table shaped like the query
it represents.

### 3.3 Registered op counts

None. No expression's lowering changes, which is section 2's whole finding.

## 4. Files

* `sql/varka/bench/src/main/java/.../DateSurfaceBenchmark.java` - the switch, the
  narrow table, the provenance key, and the refusal when an entry needs a column
  the shape does not carry.
* `dev/varka_bench_surface.sh` - pass it through.
* `sql/varka/bench/src/test/java/.../SurfaceTest.java` - the refusal is asserted.
* `sql/varka/plans/m5/PLAN.md` - the row, marked planned.

## 5. Tests, and what each is for

* **The narrow table has exactly three columns, and the wide one six.** A test
  that reads the built schema, so a future column added to one shape and not the
  other cannot pass unnoticed - which is precisely how this task's subject
  arrived.
* **An entry reading an absent column is refused, with its name in the message.**
  The alternative is a silently shorter run whose file looks comparable and is
  not.
* **The provenance carries the shape.** Without it two files are
  indistinguishable, and the whole comparison rests on remembering which was
  which.

## 6. The measurement

Four runs on an idle development machine, the Varka arm only, at the committed
1e9 rows and the 56g driver the resident table needs, `--only` over three
entries chosen to span the regime:

* `date_add(d, 3)` - the subject, and the most bandwidth-bound entry in the file.
* `year(d)` - a mid-weight calendar extraction.
* `weekofyear(d)` - the heaviest single call in the surface, and the control: if
  the table's width moves *this* row materially, the effect is not what section
  2 says it is.

Each entry is run over the `dates` table and the `all` table, in one session per
shape so the pairing shares a JVM and a page cache state.

### 6.1 Predictions, registered before the run

1. **`date_add(d, 3)` over the three-column table reads within 5% of 1811.6 M/s,
   and over the six-column table within 5% of 1401.4.** This is the prediction
   that matters: it says the fixture reproduces both historical numbers on one
   machine on one day, which is what would make the working set the explanation
   rather than a coincidence of two runs a week apart.
2. **`weekofyear(d)`, the control, moves less than 5% between the two shapes.**
   It does about 64 emitter ops against `date_add`'s four, so it is bound by
   arithmetic and should not care what else is in the table. If it moves as much
   as the subject, the cause is not bandwidth and section 2's reasoning is wrong.
3. **The effect is sub-linear in table size.** The table doubles, 12 to 24 bytes
   per row, and the observed loss is 22.6%, not 50%: the kernel still reads only
   its own four bytes per row, and what degrades is locality across the batch
   sequence rather than the volume it must fetch. So I expect `year(d)` -
   mid-weight - to lose something between zero and the subject's 22.6%, and
   nearer zero than to it.

## 7. Risks

1. **The two historical numbers may not reproduce at all**, if something other
   than the table changed the machine between 7 and 13 September. The canary was
   clean on both, which is the evidence against, but the canary controls for
   thermal and load state rather than for kernel or firmware changes. Prediction
   1 is what detects this: if neither number reproduces, the task's answer is
   "not the fixture, and not attributable from here", and that is worth
   recording as a limit on what these files can settle.
2. **`--only` changes the run's shape**, because a full surface run builds the
   table once and amortises it over 52 entries. The measurement uses the same
   `--only` set for both arms, so the pairing is sound even if the absolute
   numbers sit slightly off a full run's.
3. **Page cache and allocation state** differ between a 12 GiB and a 23.2 GiB
   table in ways beyond the benchmark's control. Running the two shapes in one
   session each, rather than interleaved, means a shape's numbers share their
   allocation history - which is the honest arrangement, not an ideal one.

## 8. Sequencing

1. The plan, and the milestone row marked planned.
2. The switch, the narrow table and the provenance key, with the three tests -
   green before any measurement.
3. The four runs, and section 9 written from them.
4. If the hypothesis holds, a follow-up row for the fixture decision; if it does
   not, a follow-up row for the regression hunt. Either way this task ends with a
   number and a named next step, not with a fix.

## 9. Outcome

*Measured 14 September 2026 on the development laptop, commit `cf35b55217e`,
canary clean on every run, 1e9 rows and a 56g driver throughout.*

### 9.1 What the four runs say

| condition | `date_add(d, 3)` | `year(d)` | `weekofyear(d)` |
|---|---|---|---|
| three columns, Varka alone | 1891.4 | 1265.5 | 850.5 |
| six columns, Varka alone | 1780.6 | 1208.6 | 817.1 |
| six columns, Varka fourth of four | 1797.9 | 1204.8 | 817.9 |
| **13 September, six columns, fourth of four, 52 entries** | **1401.4** | - | - |
| 7 September, three columns, fourth of four, 35 entries | 1811.6 | - | - |

**The fixture costs about 6%, and not the way section 2 expected.** Widening the
table from three columns to six costs 5.9% on `date_add(d, 3)`, 4.5% on
`year(d)` and 3.9% on `weekofyear(d)`. That is close to uniform, which is the
wrong shape for a memory-bandwidth explanation: a bandwidth cause would have hit
the four-op entry and left the sixty-four-op one alone. Whatever the extra
columns cost, they cost it to arithmetic-bound kernels almost as much as to
bandwidth-bound ones, so it is general pressure rather than locality on the one
column the kernel reads.

**Ordinal arm position costs nothing.** Running the Varka arm fourth, after
three row-engine arms, reads 1797.9 against 1780.6 alone - a 1% difference in
the *favourable* direction, which is noise.

**And the control settles what remains.** Stock Spark 4.2 on JDK 25 measured
**82.9 M rows/s** for this entry on 13 September and **82.7** today: -0.2%. The
machine was not slower that day. Yet the Varka arm on the same entry, same
fixture and same ordinal position reads 28.3% higher today than it did then.

### 9.2 The predictions, scored

**1 half held, half failed.** `date_add(d, 3)` over the three-column table reads
1891.4 against the historical 1811.6, within 4.4% - the old number reproduces
over the old fixture. Over the six-column table it reads 1780.6, not the 1401.4
the prediction expected, and 27% away from it. So the fixture does not reproduce
the second number, which is the half that mattered.

**2 held, and for the wrong reason.** The control moved 3.9%, inside the 5% the
prediction allowed. But the prediction's purpose was to distinguish a
bandwidth-bound effect from a general one, and it cannot: the *subject* also
moved only 5.9%. Passing this test told us nothing, because nothing moved much.
A test that only discriminates when the effect is large is not a control, and
the right form would have been a *ratio* - the subject's loss against the
control's - with a threshold on the ratio rather than on each.

**3 held.** The effect is sub-linear in table size and `year(d)`, at 4.5%, sits
between the control and the subject as predicted. It is the one prediction that
survives intact, and it is also the least consequential.

### 9.3 So what is the 22.6%?

Not the code: the lowering is unchanged and section 2 establishes that from the
op count and the commit range. Not the machine: stock measured within 0.2% on
both days. Not ordinal position: measured, and worth 1%. The fixture accounts
for about 6 of the 22.6 points.

**The remaining 22% or so is real, is confined to the Varka arm, and is not
explained here.** The one variable this task did not control is *elapsed session
time and cumulative allocation before the arm starts*: on 13 September the Varka
arm began about five hours and twenty minutes into the session, after three arms
had each built and cached a billion-row table; today it began about forty
minutes in. Ordinal position was held fixed and duration was not, and those are
different things.

The mechanism that would fit is memory: the Varka arm allocates a 56g heap and
holds 23.2 GiB of Arrow-cached data, where the stock arms hold 1.2 GiB. Hours of
allocation churn degrade huge-page availability and fragment the address space,
and a kernel reading four bytes per row is the one most exposed to the TLB cost
of that - which would also explain why the stock arm, holding a twentieth of the
data, shows nothing. **This is a hypothesis with a clean control and no
measurement, and it is written here as that and not as a finding.**

### 9.4 What this means for the committed numbers

If the hypothesis holds, the committed surface understates Varka: its numbers are
measured in the most degraded memory state of the session, while the baselines
they are divided by are measured in the least. The published ratios would then be
**pessimistic**, by up to the 28% seen here on the most exposed row.

That is the opposite of the usual worry about a benchmark, and it is still a
reason to fix it before the numbers are quoted publicly. A ratio that is wrong in
the flattering direction is a lie; one that is wrong in the unflattering
direction is a waste, and either way the number is not the engine's.

### 9.5 What this task leaves

The switch, its tests and this record. The measurement is done and its answer is
mostly negative, which is the useful kind: three candidate causes are eliminated
with numbers, and the surviving one is named with the experiment that would
settle it - two full-length surface runs differing only in how long the machine
has been working when the Varka arm starts.

That experiment costs about eleven hours of a quiet machine and belongs in its own
row rather than here.

### 9.6 A hazard found while running this, and it is not this task's

**An `--only` run overwrites the whole results file for its label.** It does not
merge into it and it does not refuse. So running three entries of the surface in
a checkout replaces that label's committed file with a three-entry one, and
`git status` shows an ordinary modification that a distracted commit would carry
into master - silently truncating the coverage table from fifty-two rows to
three.

This nearly happened here. The Varka arms of the measurement used custom labels
(`varka-dates`, `varka-all`, `varka-4th`) and so wrote new files, but the two
stock arms were passed under their canonical `spark-4.2.0-jdk17` and
`spark-4.2.0-jdk25` names and truncated both committed files. They were restored
before the commit, and only because the working tree was read before staging.

The fix is small and belongs to whoever owns the driver: with `--only` or
`--shard` set, either refuse a label whose file already exists with more entries
than the run will write, or write to a name that says the file is partial. VARKA-62 already added the shard suffix for exactly this reason on the sharded path;
the `--only` path did not get it.
