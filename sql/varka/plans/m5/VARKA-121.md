# VARKA-121: the AVX2 arm of the `TIME` surface

*Scoped 15 September 2026 (milestone 5 section 2.56, row 121); planned 21
September 2026, after VARKA-105's four arms.*

## 1. The question

Every committed `TIME` number is from a machine whose JVM runs at `UseAVX=3`:
this laptop, and the Zen 5 runner the full-width numbers wait for. On such a
machine the 64-bit constant division that `hour(t)`, `minute(t)`, `second(t)`,
`time_trunc` and `time_diff` are built on goes through double lanes, `L2D` and
`D2L`, and those two conversions are instructions. Under `UseAVX=2` they are
not: VARKA-153's census on an EPYC 7763 printed `not supported` for the two
casts on every shape carrying a 64-bit division and on no other shape, so on
that host class the conversion form is a per-lane Java loop inside a vector
kernel. The emitter has a second lowering for exactly this, the magic-number
identity `emitMagicDivide`, selected by `VarkaEmitOptions.useAVX`, and nothing
has measured it against the per-lane conversion on a machine that actually
runs at that level. `VARKA-88.md` 9.4 left the arm out on purpose: this
laptop reports `UseAVX=3` and emitting the magic form on it measures an
instruction mix no machine runs.

The pool's most common runner is that host class. Half the machines a reader
is likely to have are that host class. VARKA-118 quotes an AVX2 file beside
the full-width one so that the post does not quote a Zen 5 number for a path
that is a different lowering on the machines most readers have. So the
question is two numbers and one decision: what the `TIME` surface reads under
`UseAVX=2` with the shipped lowering, what it reads with the magic form, and
whether the emitter selects the form from the level.

What is known going in, from the committed 256-bit companion at `UseAVX=3`
(`VARKA-152.md` 6.6, four 64-bit lanes with the conversions
intrinsified): the magic form reads 3011.0 M rows/s against the conversion
form's 4692.5 on `hour` in L3, and 440.9 against 1455.6 on the three-field
shape. That is the magic form's cost where the conversion is an instruction;
under `UseAVX=2` the conversion is not, and the comparison inverts or it does
not. That is the measurement.

## 2. The change

Three small pieces and two runs.

1. **A session switch for the level.** Production emits with
   `VarkaEmitOptions.DEFAULTS` (`VarkaShapeKey`'s production constructor), so
   nothing a benchmark driver passes can reach `useAVX` today; the default is
   deliberately not the host's level (`VarkaEmitOptions`' javadoc says why: a
   host-dependent default makes the shape hashes and the committed bytes
   describe one machine). The switch is a SQL configuration,
   `spark.sql.codegen.varka.emit.useAVX`, an integer defaulting to
   `USE_AVX_UNKNOWN`, read where the projection's options are built and
   mapped onto `withUseAVX`. The user-facing string surface and the typed
   option are two surfaces with one mapping between them, per the project's
   configuration rule; the configuration gets a binding policy and
   `SparkConfigBindingPolicySuite` runs. The level stays in the shape hash, so
   a session that sets it gets its own class names, which is what the hash
   promises.
   *Done 22 September 2026.* `spark.sql.codegen.varka.emit.useAVX`, an int
   defaulting to -1, `NOT_APPLICABLE` for view binding since a lowering
   choice changes how a kernel computes and never what a view resolves to.
   It is read on the driver in the three exec nodes beside
   `classDumpDirectory` and carried through the evaluator factories to
   `VarkaEvaluatorBase.shapeKey`, where it is applied *over* the test hook's
   options rather than instead of them, and left alone at the default so the
   hook's own level survives. Two tests: the evaluator suite shows a level-2
   evaluator emitting a different class from the default one over the same
   IR, and the `TIME` arithmetic suite runs the extracts, a truncation and a
   difference under the switch against the row engine through both consumers.
   The workflow gained `extra-confs`, appended to every arm's conf field, so a
   dispatch can carry the JVM flag and the switch together (step 2 below).
2. **The JVM flag reaches the arms.** `dev/varka_bench_surface.sh` already
   accepts `key=value` entries in an arm's conf field and passes them as
   `--conf`; `spark.driver.extraJavaOptions=-XX:UseAVX=2` is such an entry and
   in `local[1]` the driver is the executor, so no script change is needed
   for the flag. The workflow gets one input, `extra-confs`, appended to every
   arm's conf field, so a dispatch can carry the flag and the switch to a
   runner.
   *Done 22 September 2026, with step 1.*
3. **The files.** `TimeSurface-<label>-avx2-results.txt` for four arms on the
   laptop under `-XX:UseAVX=2` - stock 4.2.0 on JDK 25, the fork with the
   engine off, the fork with Varka at the default level (the per-lane
   conversion), the fork with Varka at `useAVX=2` (the magic form) - at VARKA-105's setting, 500000000 rows in 48g, the fixed-share bound 6% as 105
   recorded it. Then the same four arms through `varka-surface-benchmark.yml`
   with `require-datapath=any` and `extra-confs` carrying the flag and the
   switch, which lands on a Zen 3 runner most dispatches, at the row count a
   runner keeps resident. Every file's provenance carries `UseAVX`,
   `MaxVectorSize`, the CPU flags and the datapath probe, so a reader can tell
   which lowering it measured without opening the plan.

Stock's arm does not change under the flag in any way that matters to Varka:
the row engine does not vectorise, so its rate is the same and the ratios move
with Varka's rate alone. It is run anyway so the ratio is a ratio between two
files from one machine state, not one from today and one from yesterday.

## 3. Predictions, registered before the run

1. **The shipped lowering collapses under `UseAVX=2`.** With the conversions
   per-lane, `hour(t)` on the laptop reads under a third of its `UseAVX=3`
   rate of 1041.4 M rows/s (the four arms, `VARKA-105.md` section 6), and
   the three-field shape under a quarter of its rate, since a per-lane loop
   costs per division and the three-field shape has three.
2. **The magic form wins there by more than 2x on every division shape.** At
   `UseAVX=3` and four lanes it read 0.64 of the conversion form on `hour`
   and 0.30 on three fields (section 1); against a per-lane conversion it
   reads above 2x on `hour` and above 3x on three fields. The `TIME` rows
   without a division - `t - t2`, the comparisons, `greatest`, the interval
   selections - do not move between the two Varka arms by more than the band,
   since they have no lowering to select.
3. **The decision is two lowerings.** Because of 1 and 2 the emitter selects
   the magic form when the level is 2, through the switch and, once
   `HOST_USE_AVX` is trusted on a number, as the production default on such
   hosts; `VARKA-88.md` 9.3's shape-hash argument stands, since the level
   is in the hash and two executors at different levels get two classes.
4. **The runner agrees with the laptop within the band.** The Zen 3 runner's
   file, at four lanes natively rather than by flag, ranks the two Varka arms
   the same way as the laptop's, with ratios within VARKA-101's band of the
   laptop's; if it does not, the laptop's `UseAVX=2` is not a model of an
   AVX2 machine and the plan says so.
5. **Against stock, the AVX2 magic form still reads above 5x** on every
   division projection and above 10x on the truncations: the magic form is a
   handful of multiplies and shifts per lane, and stock's per-row `LocalTime`
   is unchanged.

## 4. Verification

The switch has a unit test that the option reaches the shape key and the
class name, and the compiler suite pins that a session at level 2 emits the
magic form for a `TIME` division and level unknown does not. The bytes
oracle is unchanged at the defaults. The four laptop files pass the residency
and fixed-share rules; the runner files pass them at their row count. The
quote check holds every number section 5 will quote to the new files.

## 5. Outcome

### 5.1 Prediction 1, 22 September 2026: the shipped lowering under the flag

The first arm ran before any of section 2's pieces were built, because it
needed none of them: the fork's Varka arm of the `TIME` surface at VARKA-105's
setting (500000000 rows, 48g, one partition) with
`spark.driver.extraJavaOptions=-XX:UseAVX=2` in the arm's conf field, which the
surface script already passes through. The JVM read the flag - the file's
`MaxVectorSize` line says 32, four 64-bit lanes - and the file is
`TimeSurface-varka-jdk25-avx2-results.txt`. Wall-time rates in M rows/s
against the committed `UseAVX=3` arm:

| entry | `UseAVX=3` | `UseAVX=2`, shipped lowering |
|---|---|---|
| `hour(t)` | 1041.4 | 132.4 |
| `minute(t)` | 821.3 | 72.3 |
| `second(t)` | 835.4 | 73.0 |
| `time_trunc('MINUTE', t)` | 978.0 | 125.2 |
| `time_trunc('MILLISECOND', t2)` | 973.0 | 126.4 |
| `t - t2` | 796.9 | 123.8 |
| `time_diff('HOUR', t, t2)` | 791.9 | 123.4 |
| `t + dt` | 689.1 | 690.8 |
| `greatest(t, t2)` | 876.6 | 798.6 |
| `least(l, 5000000000)` | 1238.2 | 1234.6 |

**Prediction 1 held, by more than it said.** It predicted the extracts under a
third of their `UseAVX=3` rate; they read under a seventh, `hour(t)` at 0.13x
and `minute(t)` and `second(t)` at 0.09x, and every row with a 64-bit constant
division in it - the truncations, `t - t2`, both `time_diff` units - sits
between 0.13x and 0.16x. The rows without a division do not move by more than
their tier: `t + dt` and `least(l, ...)` are unchanged, the selections within
a fifth, which is the 256-bit species against the 512-bit one and nothing
else. So on an AVX2 host today, with the lowering that ships, every `TIME`
division row runs at a hundred M rows/s: still above stock's 61 (the four
arms, `VARKA-105.md` section 6), and a tenth of what the same kernel does
on this machine's own level.

Two things the run adds to the plan. The provenance block does not record the
`UseAVX` level itself, only `MaxVectorSize` and the CPU flags, so a reader has
to infer the flag from the file name and the 32; the surface driver should
print the level beside `MaxVectorSize`, which is a line in `Provenance`. And
`t - t2` collapsing with the divisions says its lowering carries a division
too, which section 2's reading of the magic form should confirm before the
second arm is run.

The second arm, the magic lowering under the same flag, waits on the session
switch of section 2 step 1, as planned.

## 6. Explicitly out of this task

The production default on AVX2 hosts follows from prediction 3 but ships
separately if it ships at all, since it changes what every executor on such a
host emits; this task produces the number the decision needs and the switch
that makes the choice explicit. The Intel server class, whose 512:256 ratio
sits between the two AMD classes (`sql/varka/HARDWARE.md`), is a third file
for another day. The `date` surface under `UseAVX=2` is VARKA-92's twin and not
this task's.
