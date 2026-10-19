# VARKA-400: `VarkaTierWitnessSuite` under the memory sanitizer

*Row 400 of `m8/PLAN.md`, filed with rows 397 to 401 on 19 October 2026. Read, fixed and measured
the same day at master `e6aa78e3cd5`.*

## 1. Where this came from

Row 28's run of the defaults gate (`dev/varka_matrix.sh --defaults`, which turns the memory
sanitizer on in every test JVM with `-Dvarka.sanitizeMemory=true`) passed 1,560 tests and failed
one: `VarkaTierWitnessSuite`'s "the witness sees each tier: a cold call interpreted, a warmed one
C2, and a trap", whose warm history was reported as trapping in `VarkaMemorySanitizer.check`. The
same test failed the same way on master without row 28's change. The gate is the check every lane
row runs before its pull request, so a standing failure there hides the next one.

`dev/varka_gate.sh` leaves the suite out of its sanitized steps and runs it in its quiet step with
`--no-sanitizer`, which is why that gate never saw it; `dev/varka_matrix.sh --defaults` on its own
runs every Varka suite sanitized, this one included.

## 2. The trap, read

Reproduced at `e6aa78e3cd5` by running the suite alone the way the gate does
(`dev/varka_matrix.sh --defaults --module catalyst --suites VarkaTierWitnessSuite`): 6 passed, 1
failed, with

    warm-same: [Trapped[method=loopDense0, reason=speculate_null_assert, action=make_not_entrant,
      at=...VarkaMemorySanitizer.check], Trapped[method=loopMasked0, ...same...]]

The "trap" is a JIT deoptimization, not a memory violation: no `VarkaMemoryViolation` was thrown,
and no mapping left a registered buffer. It is the witness's verdict that C2's warmed code for both
drivers was thrown away at its first compared call, at a site inlined from the sanitizer's check.

The cause is in the test harness, `VarkaCompiledCheck`. Its warm-up (`warm`, on
`VarkaKernelWarmup`'s call shape) called the kernel directly, outside any sanitizer window, while
every compared call goes through `VarkaKernelChecks.sanitized`, which opens a window and registers
the call's buffers. `VarkaMemorySanitizer.check` reads `ACTIVE.get()` and tests it against null.
Through the whole warm-up that value was always null, so C2 compiled the inlined check with the
speculation that it stays null: HotSpot's parser (`opto/parse2.cpp` 2658 to 2662, jdk-25-ga) puts a
`null_assert` on an `ifnull` whose operand's speculative type is always null, guarded by an
uncommon trap with reason `speculate_null_assert`. The first compared call opens a window, the
value is not null, and both drivers deoptimize (`make_not_entrant`). So under the sanitizer no
warmed history could ever be served by C2, and the test correctly said so.

Of the row's two candidate readings, it is the second, in a form the row did not name: not memory
the sanitizer fails to track, but a warm-up the sanitizer did not see, whose profile then
disagreed with the calls that it did see. There is no out-of-bounds access in the witness's kernel.

## 3. The fix

The warm-up's calls now run in the sanitizer's window like the compared calls: `Args`, one
warm-up call's prebuilt arguments, carries its kernel and lane and implements
`VarkaKernelChecks.Run`, and `Args.call()` goes through `VarkaKernelChecks.sanitized` (now
package-private, with its `Run` interface) with the same buffers registered as a compared call of
that shape. The warm-up's profile of the check is then the compared calls' profile, and the
warm-up's own mappings are checked as well, which they were not before. With the sanitizer off,
`begin`, `end` and the registration return on a `static final`, and the warm-up's call gains only
the thread's scratch lookup that `sanitized` makes for every compared call; `Args` gains no
per-call allocation.

The test is unchanged, and the suite's doc comment now says that it holds under the sanitizer too.

## 4. Tests

| run | before | after |
| :-- | :-- | :-- |
| `VarkaTierWitnessSuite`, sanitizer on (`--defaults --module catalyst --suites ...`) | 6 passed, 1 failed, 32 s | 7 passed, 33 s |
| the same, `--no-sanitizer` | | 7 passed, 31 s |
| the whole defaults gate, `--split 3 -j 6`, sanitizer on | row 28's run: 1,560 passed, 1 failed | 1,528 passed, 0 failed, 48 cancelled, 487 s |

No regression test was added beyond the suite itself: the failure was a test-harness profile, not
a kernel bug, and the existing test is the one that fails without the fix whenever the sanitizer
is on, as the gate runs it.

## 5. Outcome

The defaults gate passes whole on the fix: `dev/varka_matrix.sh --defaults --split 3 -j 6`, 111
suites in six JVMs with the sanitizer on, exit 0, 1,528 passed, none failed, 48 cancelled. The 48
are the opt-in tests that cancel themselves without their switch (the census lines, the sweeps,
the size-trace dump and the like); the sanitizer's own end-to-end suite cancelled none, the
script's proof that the flag reached the JVMs. Row 28's run had 1,560 passing because it carried
that row's own tests.

No predictions were registered: the row was a diagnosis, and the fix's only cost, the window around
each warm-up call, applies only with the sanitizer on.

## 6. The record

The same failure was an open debt in milestone 7's register (`m7/PLAN.md` 10, "In code and
tests"), which proposed running the suite without the sanitizer or making the witness tolerate the
trap. Neither was taken, since the trap was the harness's and not the sanitizer's; the entry is
rewritten in the past tense with this finding. The lesson is in `skills/the-jit.md`, "A warm-up
outside the sanitizer's window makes C2 trap at its first checked call".

## 7. Found beside the row

1. **Production's warm-up is outside the sanitizer's window too.** `VarkaKernelWarmup`'s calls
   (`probe` and `call`, lines 725 to 746) run on a thread with no window, so the warm-up's mappings
   are unchecked, and by the mechanism of section 2 a production kernel's warmed code is expected
   to be compiled with the same speculation and to deoptimize at its first sanitized batch; no run
   here has shown it. It changes no answer and the sanitizer is test-only,
   but sanitized end-to-end runs serve their first batches from code the warm-up did not produce.
2. **Sanitized compiled-history fuzz runs likely did not reach their warmed histories.**
   `VarkaIrFuzzSuite`'s and `VarkaCoverageCompositionFuzzSuite`'s compiled checks warm through the
   same harness, so by the mechanism of section 2 a sanitized run's warmed histories are expected
   to have met the same trap at their first compared call; no before-fix count was taken. A history not reached fails the compiled test only under
   `-Dvarka.fuzz.requireTiers=true`, the quiet machine's switch, so the defaults gate counted them
   and passed. On the fix the gate's run prints "0 history(ies) not reached" for both of the fuzz
   suite's compiled tests. The runner nightly (`varka-fuzz.yml`) runs without the sanitizer, so its
   compiled-history signal was never affected.
