# VARKA-144: what the long lane costs end to end

*Milestone 5, section 2.80. Opened and measured the night of 17-18 September
2026, from VARKA-29's registered prediction 6.1.2, which nothing had scored.*

## 1. Where this came from

VARKA-29 made the long lane reachable from SQL and registered a prediction it
could not score: a `bigint` comparison filter over an Arrow-cached table should
run at **0.45x to 0.60x** of the same filter over an `int` column. VARKA-142 had
priced the lane at the *kernel* - 1.5x to 2.0x while both arms are in cache, a
flat 2.11x to 2.20x once they are not - and the prediction carried that number up
a layer by assuming the rest of the query scales with it.

Nothing measured it, and the milestone is about to build four more tasks on this
lane (88, 102, 103, 104), each to be judged by a ratio against the row engine.
A baseline lands as its own change before the work it judges, so this is it.

## 2. The admission check, done

**The measurement is possible today and was not before.** VARKA-29 admits
comparisons, `IS [NOT] NULL`, the connectives, `greatest`/`least` and `CASE WHEN`
over `bigint`, `TIME(p)` and day-time intervals; no arithmetic. That is enough
for a filter and a projection at both widths, which is what the prediction is
about.

**The int-lane twin of a long projection is a date column, not an int column.**
A bare `int` column is admitted as a comparison or arithmetic operand (VARKA-122)
and never as a *value*, so `greatest(i, i2)` declines where `greatest(l, l2)`
fuses. The date columns are the int lane's value leaves and the same 32 bits, so
the pairing is by lane width rather than by Spark type. Found by the benchmark's
own fusion assertion, which is why every case asserts before it times.

**Every case asserts it fused.** A benchmark whose subject silently fell back
would report the row engine on both arms and call the ratio 1.0. The check uses
the four node classes `VarkaSharedSessions.isVarkaNode` names - a filter feeding
a row consumer is a `VarkaFilterColumnarToRowExec`, and a check that looked only
for `VarkaFilterExec` would call a fused plan unfused, which is exactly what the
first version of this benchmark did.

## 3. The design

`VarkaLongLaneThroughputBenchmark` in `sql/core`, on
`VarkaThroughputBenchmark`'s pattern: two sessions on one context, one with Varka
and one without, an Arrow-cached table, `noop()` sinks, and the committed-run
methodology of five iterations over two-second windows.

Every case is a pair - one shape at two widths over identical values - so the
ratio of the two Varka rows is the lane's end-to-end price and the ratio of each
Varka row to its own baseline is what the engine is worth on that shape. `TIME`
and day-time interval cases run beside the `bigint` ones because all three share
the lane and none shares a Catalyst expression, so a lowering that was
accidentally type-specific shows as one row out of line.

**Two scales, because one cannot answer the question.** At two million rows the
per-batch fixed costs are a large share of the work and do not double with the
lane; at twenty million the kernel's share grows. VARKA-142's finding - that the
lane's cost depends on where the working set sits - is the reason to expect the
ratio to move, and the second scale is what shows whether it does.

**And a crossed experiment**, because the first two scales disagreed: the same
filter at two widths, with the compared column and the output column crossed,
plus a `count(*)` form that outputs no column at all.

## 4. Files

| file | what |
|---|---|
| `VarkaLongLaneThroughputBenchmark.scala` | the benchmark |
| `VarkaLongLaneThroughputBenchmark-jdk25-results.txt` | its committed results |
| `sql/varka/plans/m5/PLAN.md` | section 2.80, rows 144 and 145 |
| `sql/varka/plans/m5/VARKA-29.md` | 9.1's prediction 2, now scored |
| `sql/varka/skills/benchmarking.md` | the lesson of 9.2 |

## 5. Tests, and what each is for

None of its own: a measurement whose guard is the fusion assertion on every case.

## 6. The measurement

Committed in `sql/core/benchmarks/`. The numbers below are rates in millions of
rows per second, Varka arms only, from that file.

## 7. Risks

1. **One machine, one row count each.** The ratios below are this laptop's; the
   two scales are what keep the reading honest rather than a single number.
2. **The `noop` sink** means no consumer reads the output, so a projection's cost
   is the kernel and the write, not a downstream read.

## 8. Sequencing

After VARKA-29, before VARKA-102, VARKA-103 and VARKA-104, whose numbers this is the baseline
for.

## 9. Outcome

**VARKA-29's prediction is wrong, and the way it is wrong is the result.** The
long lane's end-to-end cost is not a single band, and at no scale is it the
kernel's 2.1x:

| shape | int32 | int64 | ratio |
|---|---:|---:|---:|
| filter, column against column, 2e6 | 62.9 | 60.4 | 0.96x |
| filter, column against literal, 2e6 | 180.4 | 132.0 | 0.73x |
| filter, two conjuncts and a null check, 2e6 | 69.6 | 58.8 | 0.84x |
| projection, greatest, 2e6 | 227.1 | 207.7 | 0.91x |
| projection, CASE WHEN, 2e6 | 240.1 | 211.2 | 0.88x |
| filter, column against column, 2e7 | 96.4 | 93.9 | 0.97x |
| filter, column against literal, 2e7 | 1034.9 | 324.0 | **0.31x** |

At two million rows the lane costs between 0.73x and 0.96x - far less than the
kernel's 2.1x, because the Arrow cache read, the batch machinery and the filter's
plumbing are most of the work and none of them doubles with the lane. The
predicted 0.45x to 0.60x band is met by no case at that scale.

At twenty million rows the two filters part company: column against column stays
at 0.97x while column against literal falls to 0.31x, *below* the predicted band
and below the kernel ratio. So the answer to "what does the long lane cost end to
end" is: it depends on the shape by a factor of three, and the plan that
predicted one band for it had the wrong model.

**What the engine is worth on these shapes**, against the row engine: filters
1.4x to 2.1x, projections 6.6x to 8.1x, and at twenty million rows the int32
column-against-literal filter reaches 11.1x. The `TIME` filter is 1.8x and the
day-time interval filter 1.4x, the lowest of the family.

### 9.1 Where the cost is, which is not where the prediction looked

The crossed experiment at twenty million rows, Varka arms:

| compared | output | rate |
|---|---|---:|
| int32 | int32 | 1101.9 |
| int64 | int64 | 327.8 |
| int64 | int32 | 103.0 |
| int32 | int64 | 99.0 |
| int64 | none (`count(*)`) | 128.0 |
| int32 | none (`count(*)`) | 132.6 |

Two things follow, and the plans say why. The fast pair are the two queries whose
executed plan is `VarkaFilterColumnarToRow (predicate)` with no forwarded column
- one column, filtered and output. Between them the only difference is the width,
and the long arm is **0.30x** the int one: more than the kernel's 2.1x can
explain, and consistent with the compaction the filter does on its surviving
column, where the vectorised `compress` path serves four-byte vectors only and an
eight-byte column takes a per-row copy (`VarkaKernelEvaluator`, the finding
`VARKA-29.md` 2 recorded as VARKA-128's). That is the leading explanation and
this measurement does not prove it; what would is the same pair with
`compactInt64` in place, which is VARKA-128.

The four slow cases all carry a second column. Their plans read
`VarkaFilterColumnarToRow (predicate), List(<the other column>)` - a forwarded
column to compact - and they collapse to about 100 M rows/s whichever width was
compared, a **tenfold** drop from the one-column int32 case. `count(*)`, which
outputs no column at all but adds an aggregate above the filter, sits at 130.
That cost is not the long lane's: it is paid by an int32 forwarded column too,
and it is much larger than the lane's. It is recorded as row 145.

### 9.2 What this changes for the milestone

* **A kernel ratio is not an end-to-end ratio**, in either direction: 2.1x at the
  kernel became 0.73x-0.96x at two million rows and 0.31x on one shape at twenty
  million. VARKA-102, VARKA-103 and VARKA-104 should quote this file rather than VARKA-142's
  when they speak about queries.
* **VARKA-128 has its motivating number**: 0.30x on the shape where the compaction
  is the only difference.
* **Row 145** is new and larger than the lane question that found it - and its
  first reading was wrong, which section 9.3 records.

### 9.3 The forwarded column, separated and then refuted the same night

9.1 read the four slow crossed cases as "forwarding a column costs ten times".
Two corrections followed within the hour, and both belong in the record.

**First**, a five-case separation said it is not the forwarding: `SELECT i, i2
FROM t WHERE i > 50000` returns two columns at 901.3 M rows/s while `SELECT i2
FROM t WHERE i > 50000` returns one at 120.3, and the plans differ only by
`VarkaFilterColumnarToRowExec.narrowing`. So the reading became "narrowing costs
eight times".

**Second**, that reading is wrong too, and two things refute it. VARKA-78 had
already measured this exact shape - `VarkaNarrowingBenchmark`, three
selectivities, both widths - and found the narrowed form slightly *faster* than
the two-column control, the opposite ordering. And forcing the row read-back with
`toRdd` puts the two within 1% of each other: 144.4 against 142.8.

What is actually there: under a columnar sink the un-narrowed shapes stay
columnar end to end and run seven times faster than they do through the row path,
while the narrowed one runs at its row-path rate either way. The gap is a cost
the un-narrowed shapes *avoid*, not one the narrowing pays - VARKA-19's read-back
floor, reached through a plan difference. Row 145 is rescoped to that question:
why the narrowed node does not take the columnar path when `columnarSibling`
already builds one for it.

**What this cost, and the lesson.** Three readings in ninety minutes, two of them
written down before the third arrived, because each experiment separated one
variable and stopped there. The check that would have caught it first is one this
project already has a rule for - VARKA-78's benchmark existed, measured this shape
and disagreed, and nobody looked for it until after the second reading. The
`toRdd` control is committed beside the `noop` arms in
`VarkaFilterNarrowingBenchmark` so the next reader gets both in one file, and
`SKILLS.md` carries the rule.

### 9.4 The lane needs four lanes, and the cliff is between 256 and 128 bits

The 128-bit companion, written the same night, says something the wide file
cannot: **the long lane's end-to-end advantage is a function of the vector
width, and the int lane's is not.** At 128 bits a long lane is two lanes where
the int lane still has four, and the numbers follow that arithmetic rather than
anything about the types:

| shape, Varka against the row engine | 512-bit | 256-bit | 128-bit |
|---|---:|---:|---:|
| projection, greatest - int32 | 7.72x | 6.72x | 6.63x |
| projection, greatest - int64 | 7.31x | 7.19x | **2.06x** |
| projection, CASE WHEN - int32 | 8.89x | 7.96x | 7.52x |
| projection, CASE WHEN - int64 | 7.28x | 7.31x | **2.08x** |
| projection, greatest over TIME | 8.12x | 7.73x | **1.97x** |
| projection, least over day-time intervals | 7.84x | 7.47x | **1.91x** |
| filter, column against literal - int64 | 1.87x | 1.69x | 1.37x |
| filter, TIME comparison | 1.83x | 1.50x | 1.10x |
| filter, day-time interval comparison | 1.40x | 1.12x | **0.89x** |
| filter, column against literal, 2e7 - int32 | 11.47x | 9.30x | 7.18x |

**The cliff is between 256 and 128 bits, not between 512 and 256.** At 256 bits a
long lane is four lanes and it keeps almost everything - the projections lose
about two per cent (7.31x to 7.19x, 7.28x to 7.31x within the noise) - while at
128 bits it is two lanes and they fall to about 2x. Four lanes is enough; two is
not. The int32 arms, which still have four lanes at 128 bits, lose comparatively
little across the whole range.

That matters more than the raw numbers. The machines where the long lane's
advantage largely goes away are exactly the ones VARKA-92's row names - "four int
lanes is `SPECIES_PREFERRED` on every NEON-only aarch64 and on x86 without AVX2"
- and on anything with AVX2 or better the milestone's claims hold. The weakest
shape still crosses below the row engine at 128 bits: a day-time interval
comparison filter is slower with Varka than without it there, 0.89x.

**What this costs the gate, and what caught it.** The pairs for this file were
derived from the wide run alone, which is the mistake `dev/varka_bench_gate.py`'s
own header warns against - a pair must hold in every committed revision of *both*
widths. The narrow companion failed the gate on the interval filter and the
regeneration refused to let the file be committed, which is the guard working
exactly as VARKA-77 designed it. That pair is now listed as ungated with its
measured reason, and the rest of the file's pairs hold at both widths.

**What it means for the milestone.** Milestone section 6's "half the lanes" risk
is about the kernel; this is its end-to-end form and it is sharper. The public
message's numbers come from a full-width runner, and the 256-bit column says that
is not the only machine they hold on: VARKA-121's AVX2 arm should expect the
long-lane projections within a few per cent of the full-width figures rather than
halfway to the 128-bit ones. What 118 owes the reader is therefore narrow - name
the width, and say that below 256 bits the long lane's projections fall to about
2x and its weakest filter below 1x.

`dev/varka_bench_regen.sh` gained `--width=N` for this, since it produced only the
128-bit companion before; 16 stays the default, so every other benchmark's files
and names are unchanged.

