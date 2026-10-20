# VARKA-401: the kernel warm-up inside the memory sanitizer's window

*Row 401 of `m8/PLAN.md`, filed with row 400 on 19 October 2026 and read, fixed and measured on
20 October 2026 at master `a350674efe4`.*

## 1. Where this came from

Row 400 (`VARKA-400.md`) found that a warm-up outside the memory sanitizer's window makes C2
compile the inlined `VarkaMemorySanitizer.check` with the window always null, and the first call
in a window then throws the warmed code away. It fixed the test harness's warm-up
(`VarkaCompiledCheck`). Its section 7 named two things beside the row: production's own warm-up,
`VarkaKernelWarmup`, runs on a thread with no window too, and the compiled-history fuzz suites
might never have reached their warmed histories in sanitized runs. Both were reasoned, not run.
The premise is the first thing this row settles.

## 2. The premise, run

`VarkaSanitizedWarmupProbe` (test code) warms the `VarkaKernelWarmupProbe` shape through
`VarkaKernelWarmup` in a JVM with `-Dvarka.sanitizeMemory=true -Xlog:deoptimization=debug`, waits
for the verdict, then makes 200 calls through `VarkaKernelChecks.sanitized`, the evaluator's
form of a call. Before the fix the warm-up reached `COMPILED` and the log between the two markers
held ten lines like

    level=4 ...VarkaMemorySanitizer.check(JJ)V trap_bci=12 null_assert_or_unreached0 make_not_entrant

ten level-4 compiles with the check inlined, each made not entrant at bci 12, the `ifnull` on the
window. So the premise holds for the engine's warm-up. Two details differ from row 400's wording:
HotSpot's deoptimization log names the reason `null_assert_or_unreached0`, not
`speculate_null_assert` (the tier witness spells it that way), and the trapping nmethods are
named by the method the trap sits in, the check, not by the kernel that inlined it.

The fuzz suites are a separate case. `VarkaIrFuzzSuite` and `VarkaCoverageCompositionFuzzSuite`
warm through `VarkaCompiledCheck`'s own warm-up (a copy of the engine's call shape), which row 400
already put in the window. They never ran `VarkaKernelWarmup`, so this row changes nothing for
them; the count of unreached histories in a sanitized gate is in section 5.

## 3. The fix

`VarkaKernelWarmup.Job.run` opens the sanitizer's window once for the whole warm-up and registers
every segment the job allocated (the copied batch, the destinations, the scratch) before the
calls start; the calls themselves are unchanged. The job's allocations go through one helper that
remembers the segments only when the sanitizer is on. The warm-up's profile of the check is then
the profile of the calls it warms for, and its own mappings are checked as well, which they were
not before. With the sanitizer off `begin`, `end` and the registration return on a
`static final` and nothing is remembered, so the added cost is two returns per warm-up, none per
call. `VarkaMemorySanitizer`'s class comment, which listed the warm-up's thread as unchecked, says
this now.

## 4. Tests

`VarkaSanitizedWarmupSuite` forks the probe with the sanitizer forced on, so it fails under the
plain `testOnly` as under the gate, and asserts that the probe's sanitizer checked mappings and
that the log between the markers holds no `make_not_entrant` at `VarkaMemorySanitizer.check`. It
cancels when the warm-up did not reach `COMPILED` (a loaded machine) or the lanes override is a
second species, as `VarkaKernelWarmupSuite` does.

| run | before the fix | after |
| :-- | :-- | :-- |
| probe, sanitizer on: made-not-entrant at the check | 10 | 0 |
| `VarkaSanitizedWarmupSuite` | failed, 3 sampled lines of traps | 1 passed |
| warm-up suites (`VarkaKernelWarmupSuite`, `VarkaWarmupReachSuite`, `VarkaVectorTemplateWarmupSuite`, the new suite; catalyst) | | sanitizer on: 30 passed in 62 s; off: 30 passed in 61 s |
| `VarkaWarmupEndToEndSuite` (sql) | | sanitizer on: 5 passed in 19 s; off: 5 passed in 20 s |
| sanitized defaults gate, `--split 3 -j 4` | | 1,736 passed, 0 failed, 51 opt-in cancels, 608 s (`-j 4`) |

## 5. Outcome

The defaults gate passes whole with the sanitizer on: `dev/varka_matrix.sh --defaults --split 3 -j 4`,
116 suites, exit 0, 1,736 passed, none failed, 51 cancelled (the opt-in tests that cancel without
their switch; the sanitizer's own suite cancelled none, the script's proof that the flag reached
the JVMs). The two fuzz suites' compiled-history tests report "0 history(ies) not reached":
`VarkaIrFuzzSuite` ran 13 int-lane and 17 long-lane compiled cases, `VarkaCoverageCompositionFuzzSuite`
4 and 4, each over its histories (for example the IR fuzz's int lane: 13 runs of warm-same,
warm-other, quiet-remainder, quiet-null and recompiled, all settled and confirmed). The suites
already printed the count, so nothing was added to them. No before-fix count was taken, and none
was needed: the premise about those suites did not hold, since they never ran the engine's
warm-up. The gate's parallelism is the shared laptop's modest four JVMs at a time.
No predictions were registered.

## 6. Found beside the row

Nothing outside the row's scope. `VarkaKernelWarmupSuite` still runs its verdict probes without
the sanitizer, which is right: they measure what C2 made of a kernel, and the new suite is the
sanitized case.
