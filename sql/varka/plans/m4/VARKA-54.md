# VARKA-54: the Julian map in the prefix

## 1. Where this came from

The review of `benjoffe/fast-date-benchmarks` (PR #102; `SKILLS.md`, "The
Julian map: one division stage fewer in civil-from-days"). Neri-Schneider, and
Varka's prefix after it, take the day of era to a century, then a year of
century, then a day of year, and pay for the leap day at the year step with an
underflow correction. Ben Joffe removes the middle stage: scale the day by
four, take the century by one division, add four back per century, and the
count now lives in a calendar where every fourth year is leap without
exception, so one division by 1461 gives the year of era and its remainder
the day of year with 29 February right by construction.

The milestone row registered the prediction: about six ops off `year`'s 41,
one correction stage off the dependent chain, and the `century == 4` fold
gone with the year-step underflow correction and its leap mask.

## 2. The admission check, done

Recorded in PR #102 before any code, and re-run here as a committed test
rather than left in a plan. In Varka's terms - 32-bit low products, round-down
magic, one carry per division - over all 146097 days of an era against
`java.time`:

| division | magic | largest product | carries needed |
|---|---|---|---|
| `century = (4 * doe + 3) / 146097` | `(quad * 1837) >>> 28` | 1073518919 | 46 of 146097, none twice |
| `yearOfEra = jul / 1461` | `(jul * 2870) >>> 22` | 1677225130 | 8627 of 146097, none twice |

Zero mismatches. The `+ 3` puts the era's last day (29 February of its 400th
year) in century 3, so the fold the old form needed for `century == 4` has
nothing to fold. `VarkaChronoSuite`'s "VARKA-54" test is that table, run on
every build; the opt-in exhaustive sweep holds both forms to `java.time` over
all 16,777,216 covered days.

## 3. The design

### 3.1 The prefix, from the day of era

Behind `VarkaEmitOptions.julianMap`, in `emitJulianYearOfEra`, replacing the
century and year stages of `emitChronoPrefix` (the era step in front and the
month numerator behind are untouched):

```
quad      = (doe << 2) + 3
century   = (quad * 1837) >>> 28 ; r = quad - century * 146097 ; carry once
jul       = quad + (century << 2)
yearOfEra = (jul * 2870) >>> 22  ; r = jul - yearOfEra * 1461  ; carry once
doy       = r >>> 2
```

`VarkaChrono.narrowedJulian` is the scalar twin, line for line; `narrowed`
delegates to whichever form `VarkaEmitOptions.DEFAULTS` names, and
`narrowedCenturyYear` keeps the other one callable so the tests hold both to
the same oracles.

### 3.2 The slots do not move

`t[3]` still receives the century (the map needs it; no tail reads it
afterwards) and `t[4]` receives the year of *era* where the other form left
the year of *century*. `emitChronoYear` therefore takes the form as an
argument and assembles `400 * era + t[4]` under the map, against `400 * era +
100 * t[3] + t[4]` without it. That is the whole of the change at the tails:
`Year`, `DayOfYear`, `AddMonths` and `LastDay` call `emitChronoYear`; `Month`,
`DayOfMonth` and `Quarter` never touch the two slots. `CHRONO_PREFIX_SLOTS`
stays 8, the carries' scratch stays `t[6]`/`t[7]`, and the fragment-sharing
contract (VARKA-32) is unchanged because the locals a sibling reads are the
same locals.

### 3.3 Registered op counts, by the suite's own metric

`VarkaLoopEmitterSuite` counts `IntVector` invocations in `loopDense0`. On
that metric the century stage costs 10 (magic 2, remainder 2, carry 3, the
`century == 4` fold 3) and the year stage 12 (magic 2, day of year 4, the
underflow mask 1, the leap mask 2, three masked fixes 3); the map costs 19
(scale 2, magic 2, remainder 2, carry 3, map 2, magic 2, remainder 2, carry
3, shift 1). The year assembly loses the `100 * century` multiply and its add.

| node | delta |
|---|---|
| `year`, `dayofyear`, `last_day`, `add_months` | -5 |
| `month`, `dayofmonth`, `quarter` | -3 |

Registered in the suite's "VARKA-54" test before it was first run, and it
passed first time. PR #102 said "about six": it counted the `+ 3` and the
`lanewise` shift as separate ops where the suite counts a `loadConstant` plus
`add` as one invocation, and it did not count the `century == 4` fold's
compare the same way. Five by this metric is the number to hold the code to.

### 3.4 What is deliberately unchanged

* The era step, `emitEra`. VARKA-49 owns the question of the whole prefix in
  long lanes, and its admission check now names the two-division form as a
  candidate (PR #102).
* The month numerator (VARKA-53) and every tail below it.
* `ChronoVectorOps`, the hand-written reference kernel, stays on the
  century-then-year form. It is reference code, not the production path.
* `DateVectorOps` and the engine module generally.

## 4. Files

| file | what |
|---|---|
| `VarkaChrono.java` | `QUAD_DAY_ADD`, `JULIAN_CENTURY_M/K`, `JULIAN_CYCLE_DAYS`, `JULIAN_YEAR_M/K`; `fromEraJulian`; `fromEra` and it share `fields`; `narrowedJulian`, `narrowedCenturyYear`, `narrowed` delegating by the default |
| `VarkaEmitOptions.java` | `julianMap`, `withJulianMap`, the canonical rendering |
| `VarkaLoopEmitter.java` | `emitJulianYearOfEra`; the switch in `emitChronoPrefix`; `emitChronoYear` and `emitChronoLastDay` take the form |
| `VarkaChronoSuite.scala` | the era-wide test of section 2; both forms in the exhaustive sweep |
| `VarkaLoopEmitterSuite.scala` | both forms over the calendar boundaries with `last_day` (the boundary set lifted to a shared value), both forms under every `add_months` offset, the Julian axis on the whole-range calendar and `last_day` sweeps, the op-count test of 3.3 |
| `VarkaEmitterParityBenchmark.scala` + results | section 6 |
| `m4/PLAN.md` | row 54; `SKILLS.md` if the measurement teaches anything the review did not |

## 5. Tests, and what each is for

1. **The era-wide model test** (section 2): the new arithmetic over its whole
   input domain, against `java.time` and against the old form, on every build.
2. **Both forms over the calendar boundaries** with every calendar tail and
   `last_day`, and under every `add_months` offset: the emitted bytecode, not
   the model, on the days a slot transposition or an off-by-one year of era
   would show first.
3. **The op counts** of 3.3, off the class file.
4. **The exhaustive sweeps**, opt-in, now on both forms and every existing
   axis: the emitted form held to `java.time` over every covered day.
5. **Nothing pinned moves.** The shape hash renders options only when they
   differ from the defaults, and the line map is per node, so neither fixture
   moves in either commit - a moved fixture here would mean the change reached
   something it should not have.

## 6. The measurement

The parity benchmark's `year` section gains adjacent A/B pairs, both forms
named explicitly so the labels survive the default changing: `year` null-free
and mixed-null (the shape that pays the prefix and nothing else), the
four-field shared shape null-free (prefix once, tails thrice), and
`add_months(d, 13)` null-free (the widest node, whose year assembly runs
inside a recomposition). One regeneration at the default width on an idle
machine, then the narrow width, compared by minimums against the shipped
cases in the same run; the `per-row LocalDate year` control says whether the
machine moved.

### 6.1 Predictions, registered before the run

1. `year`, null-free, gains 8-12% at the default width: five ops off a body of
   about forty on a latency-bound chain, with one carry stage removed from
   the dependent path - the same ratio VARKA-53's four ops off `dayofmonth`
   bought (2542.5 to 2863.2). Mixed-null gains a similar fraction; the masked
   body runs the same prefix.
2. The four-field shared shape gains 3-6%: the prefix is a smaller share of
   its body.
3. `add_months` gains 2-5%: five ops off about 113.
4. At 128-bit lanes every ratio is at least as large as at the default width,
   because the prefix's serial masked ops are the same count over a quarter
   of the rows.
5. The default flips to the map if `year` gains at least 3% by minimum at the
   default width and nothing regresses beyond noise at either width. If it
   comes back flat, the default still flips on the op count and the shorter
   chain, VARKA-53's precedent, and the plan says so.
6. Neither pinned fixture moves in either commit.

## 7. Risks

1. **A carry bound that holds over an era but not over the range.** The two
   divisions take the day of era, whose domain is exactly the 146097 values
   the committed test walks, so the era-wide test is the whole proof; the
   exhaustive sweep is belt and braces.
2. **The slot reuse.** `t[4]` means two different things under the two forms.
   `emitChronoYear` is the only reader that cares and takes the form
   explicitly; a caller that forgot would fail the boundary tests on every
   day past year 400 of any era, not quietly.
3. **The reference variant rotting.** Mitigated the way `FloorMod7` and VARKA-53 mitigate it: the non-default form is under the same tests, boundary and
   exhaustive, on every axis.
4. **Numbers moving under the task's own feet.** The parity file is
   regenerated once, by the commit that changes the default, not before.

## 8. Sequencing

1. **Constants, twin, emitter behind the switch, tests** - one commit, green
   at both widths, default unchanged, no benchmark number moved.
2. **The measurement**: the A/B cases, one regeneration at each width, the
   numbers in section 9.
3. **The default**, per 6.1's rule, with `VarkaChrono.narrowed` following it,
   the parity file regenerated with the flipped default, the milestone row
   marked DONE.

## 9. Outcome

Shipped, default flipped to the map. Three regenerations of the parity file:
the A/B under the old default, a first run under the new one, and the
committed run under the new one on a cold, idle machine through
`dev/varka_bench_regen.sh` (the pairs below are from the committed run and its
128-bit companion, `VarkaEmitterParityBenchmark-jdk25-128bit-results.txt`;
the two earlier runs agreed with them within 2%). The `per-row LocalDate year`
control read 481.4 against the committed 481.7.

### 9.1 The A/B, Julian map against century-then-year, same run

| shape | 256-bit | 128-bit |
|---|---|---|
| `year`, null-free | 3444.2 vs 2746.4, **+25%** | 1333.0 vs 1054.5, **+26%** |
| `year`, mixed nulls | 2318.3 vs 2031.7, +14% | 810.7 vs 747.3, +8% |
| four fields, unshared, null-free | 821.5 vs 692.7, +19% | 316.0 vs 262.8, +20% |
| `add_months(d, 13)`, null-free | 733.4 vs 712.8, +3% | 255.6 vs 245.1, +4% |

### 9.2 The shipped rows, previous commit against the regenerated file (256-bit)

| row | before | after |
|---|---|---|
| `year`, null-free | 2769.1 | 3452.2 (+25%) |
| `year`, mixed nulls | 2206.0 | 2316.4 (+5%) |
| `month`, null-free | 3010.8 | 3501.3 (+16%) |
| `dayofmonth`, null-free | 2863.2 | 3379.9 (+18%) |
| four fields, unshared, null-free | 694.3 | 809.2 (+17%) |
| four fields, shared, null-free | 1531.0 | 1694.3 (+11%) |
| four fields, shared, mixed nulls | 841.6 | 844.5 (0%) |
| `add_months(d, 13)`, null-free | 713.0 | 733.6 (+3%) |

Every calendar tail sits on the prefix, so VARKA-53's rows moved with it. The
shared four-field kernel gains 11% null-free and nothing mixed-null: its one
prefix is a small share of a body that then runs three tails, and the masked
path is bound elsewhere - VARKA-46 and VARKA-47's territory, not this task's.

**Read the previous-commit column with care.** The same file's fastest
*non-calendar* rows - the emitted `date_add` loop, the hand-written `datediff`
kernel, the chain kernels - are 20-27% under the previous commit's, and VARKA-54 does not touch them. A same-day run of master's own code reproduced those
drops exactly (`date_add` mixed-null 17091 to 12527, `datediff` hand-written
4724 to 3421, controls within 0.1%), and master against this branch on the
same day puts them within about 7%. The machine's memory-bound throughput
differs between the day master's file was generated and this one, under the
same `performance` governor; the compute-bound rows and every control do not.
The provenance sidecar the regen script now writes records the power state so
the next such gap can be traced; `dev/varka_bench_regen.sh`'s header says to
re-run master the same day before reading a cross-day drop as a regression.

### 9.3 Predictions, scored

1. *`year` gains 8-12%.* **Under by half**: 25% and 26% at the two widths. Five ops off
   about forty bought a quarter of the time because what went was a serial
   stage - a compare, a leap-flag mask, and three masked fixes, each waiting
   on the last - not five ops from anywhere. Op count predicts throughput on
   a throughput-bound body; this body is latency-bound on its chain, as VARKA-48's outcome already said, and a stage off the chain is worth its depth.
2. *Four fields unshared gains 3-6%.* **Under**: 19%. Unshared, the shape is
   four loop methods each running its own prefix, so it is four `year`-like
   bodies, not one prefix amortised over four tails. The shared shape, which
   is that, gained 11%.
3. *`add_months` gains 2-5%.* Right: 3% and 4%.
4. *128-bit ratios at least as large.* Right for null-free (26%, 20%), wrong
   for mixed nulls (8% against 14%): the masked body's cost at four lanes is
   in the validity handling, not the prefix.
5. *The default flips at 3%.* Flipped at 25%.
6. *No pinned fixture moves.* Right for the shape hash and the line map.
   **The epilogue ladder moved**, which the plan did not list as a fixture
   and should have, since VARKA-51's outcome (its section 4.1) had already
   shown the prefix's size reaching it: unshared now fits 20 outputs (7675
   bytes) and crosses at 21 (8336); shared still fits 40 (7087) and crosses
   at 44 (8063, down from 8630). The suite's test title and `VARKA-32.md`
   7.1's pointer carry the new numbers.

### 9.4 The ladder, re-measured

| outputs | unshared | shared (dates x 4) |
|---|---|---|
| 16 | 6133 | |
| 19 | 7279 | |
| 20 | 7675 | |
| 21 | **8336** | |
| 40 | | 7087 |
| 44 | | **8063** |
| 48 | | 9113 |

Fourth move of the same boundary for a fourth unrelated reason (sharing, the
guard, the month elision, the map). VARKA-44 should measure its own baseline
when it starts, as `VARKA-32.md` 7.1 already says.

### 9.5 What the map leaves for later

* **VARKA-49** now has a measured reason to prefer the two-division form: the
  gain here came from removing a correction stage, and the long-lane form
  removes both.
* **`ChronoVectorOps`**, the hand-written reference kernel, is still on the
  century-then-year form. Its parity against the emitted four-field kernel
  (the "hand-written ceiling" row) is now a comparison across two algorithms;
  whoever next reads that row should know why it moved the other way.
* **The masked path did not move.** Every gain above is null-free or small;
  VARKA-46 and VARKA-47 are where the mixed-null rows go.
