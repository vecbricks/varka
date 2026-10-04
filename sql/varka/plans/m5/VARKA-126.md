# VARKA-126: the fifth arm, the Arrow cache with the engine off

*Milestone 5, section 2.61. Opened 16 September 2026 from the September surveys;
measured 17 September 2026 in an idle machine window.*

## 1. Where this came from

`m8/SCOPE.md` item 27 found that `dev/varka_bench_surface.sh`'s one
`varka` token sets two things - `spark.sql.codegen.varka.enabled` and the Arrow
cache serializer - while the README said the third and fourth arms "differ only
by that flag". They differ by two. The engine cannot run without the columnar
cache, so the missing control is the cache with the engine off, and without it
every published ratio attributes the cache format's share to the kernels.

## 2. The admission check, done

**What each arm sets.** `varka` is the engine flag, the cache serializer and the
engine jar on the driver's class path, with `--expect-fused` guarding that every
row fused. `arrow-cache`, new, is the serializer alone: no engine flag, no
`--expect-fused` because nothing fuses, and no engine jar because
`ArrowCachedBatchSerializer` is `sql/core`'s and does not need one. The bare
label with no token is the fork with neither.

**Cache build time is outside all three numbers.** The driver caches and
materializes before it measures, so every figure here is a hot-cache one; the
cache's build cost is a separate question and is not what this arm attributes.

## 3. The design

### 3.1 The mechanism

One session, three arms at twelve cores, 1e9 rows, a 56g driver:

    dev/varka_bench_surface.sh --rows 1000000000 --partitions 12 --cores 12 \
      --driver-memory 56g \
      fifth-varka=$PWD:$J25:varka \
      fifth-cache=$PWD:$J25:arrow-cache \
      fifth-off=$PWD:$J25

**Why twelve cores and not one.** The row-engine arm costs ninety minutes at
one core and thirteen at twelve, and this task needs two such arms. The question
is a decomposition of one ratio into two factors, and all three arms share a
rung, so the factors are measured against each other rather than against the
committed one-core files. VARKA-134's ladder has the same three parameters at the
same rung, which is the cross-check.

### 3.2 What is deliberately unchanged

The `varka` token, so every existing command line means what it meant. The
driver, the inventory and the fixture.

## 4. Files

* `dev/varka_bench_surface.sh` - the `arrow-cache` token and the header's
  description of what each token sets.
* `README.md` - the corrected sentence and a fifth arm in the example.
* `sql/varka/bench/benchmarks/DateSurface-fifth-*-results.txt` - three new.
* `sql/varka/plans/m5/PLAN.md` - row 126.

## 5. Tests, and what each is for

None: a measurement. The script's guards run per arm, and `--expect-fused` runs
on the `varka` arm only, which is the point of the new token.

## 6. The measurement

Three files, and the decomposition they give.

### 6.1 Predictions, registered before the run

1. **The cache alone is worth little on this surface.** The row engine reads a
   cached batch through `ColumnarToRow` either way, so the format should move
   the light shapes by a few per cent and nothing else - call it under 1.2x
   median.
2. **Almost all of the published ratio is the kernels.** If the cache were worth
   much, the engine-off arm would already show it, and the ladder's 16.2x at
   this rung would be mostly cache rather than kernel.
3. The `arrow-cache` arm fuses nothing, so its plans carry no Varka node.

## 7. Risks

* **The cache format may lose.** An Arrow batch read row-wise costs a
  conversion the on-heap cache does not pay; a ratio under 1.0 is a real answer
  and is reported as one. It did, and section 9 reports it.
* **Two hours, two tasks.** This one runs first because it is the long pole.

## 8. Sequencing

1. The token, the README, this plan. 2. The run. 3. Section 9, the row.

## 9. Outcome

Done, 17 September 2026, on `aqua`: three arms in one session, twelve cores,
1e9 rows, 52 shapes each.

**The decomposition.** Median over the 52 shapes, with the two factors either
side of the published ratio:

| factor | median | min | max |
| :--- | ---: | ---: | ---: |
| the Arrow cache alone, `off / cache` | 0.78x | 0.52 | 1.03 |
| the kernels on top of it, `cache / varka` | 21.8x | 1.67 | 38.0 |
| the published ratio, `off / varka` | 16.9x | 1.09 | 35.0 |

**The cache format is a tax on the row engine, not a bonus to the engine's
number.** Read row-wise, an Arrow batch costs the row engine 22 per cent at the
median against the on-heap cache it would otherwise use, because every batch
pays a columnar-to-row conversion. Predicates lose most - `d IN (...)` 0.52x, `d
IS NULL` 0.53x, `d = d2` 0.59x - and only the heaviest calendar shapes break
even, `weekofyear(d)` at 1.03x.

**So the published ratio is conservative, which is the opposite of what item 27
feared.** The worry was that the engine's number was partly the cache format's
doing, since the one token switched on both. It is not: measured like for like,
with both arms on the same storage format, the kernels are worth 21.8x, and the
published 16.9x is *smaller* because the engine-off arm is allowed the format
that suits it. Both numbers are honest and they answer different questions -
16.9x is what the whole stack buys against Spark as it would actually run, 21.8x
is what the kernels buy at equal footing - and the write-up now has to say which
one it is quoting.

### 9.1 The predictions, scored

**1. Wrong in sign, right in magnitude.** The cache alone was predicted to be
worth a little, under 1.2x. It is worth 0.78x: a loss, not a gain. The reasoning
behind the prediction was that the row engine reads a cached batch through a
conversion either way, which is false - the on-heap cache is already row-shaped
for it, and the Arrow one is not.

**2. Held, and then some.** Almost all of the published ratio is the kernels; in
fact more than all of it, since the cache format subtracts from the number
rather than adding to it.

**3. Held.** The `arrow-cache` arm fused nothing: no `--expect-fused` was passed
to it, and its plans carry no Varka node, which is what makes it a control
rather than a third engine arm.

### 9.2 What this changes elsewhere

VARKA-118 quotes a ratio, and it must now say which of the two it is. The README
sentence that started this - the third and fourth arms "differ only by that
flag" - is corrected in the same commit as the token, and the example grows the
fifth arm so the decomposition is reproducible from the README alone.
