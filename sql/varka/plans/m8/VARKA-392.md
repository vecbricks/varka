# VARKA-392: The nightly fuzz runs again

## 1. Where this came from

Milestone 7's close review (`m7/PLAN.md` 12, 18 October 2026) read the scheduled workflows'
history and found `varka-fuzz.yml` red on vecbricks/varka every night from 13 to 18 October.
Nobody had read it: not the rows that merged in those six days, and not the close, which read the
laptop nightly's silence since 26 September but not the runner nightly's failures.

## 2. What failed, and why

**The IR fuzzer.** In every run, all 16 tests of `VarkaIrFuzzSuite` in all four JVMs failed with
the same message: "did not finish in 9 minutes in its own JVM". The suite mixes in `VarkaOwnJvm`
(VARKA-246), so the workflow's JVM did not run it: it forked a child that runs the whole suite,
with a budget of one test's cap less a minute, `min(varka.test.watchdog.minutes = 10,
spark.test.timeout = 20) - 1`, nine minutes. The budget has been the same since VARKA-246 (#685,
9 October) and survived the Java port of the harness unchanged (#778, #785). The suite grew past
it. On 12 October, the last green night, the child ran 10 tests in 6 min 34 s to 6 min 42 s
across the four JVMs. On 13 October the suite had 16, among them VARKA-274a's compiled test over
eight batch histories and VARKA-323's op-cap tests, and no JVM finished in nine minutes.

**The two steps after it.** A failed step skips the steps after it unless they say otherwise.
The coverage-composition fuzzer and the size-4 variants step were therefore skipped all six nights
(`skipped` in the job's steps on 18 October). They had nothing wrong with them.

**Why a larger budget alone does not help.** In the parent JVM, the first test of the suite waits
for the child inside its own body. `SparkFunSuite` caps that body with
`failAfter(spark.test.timeout)`, so a child given more than the parent's cap only moves the
failure to the parent. A child run outside any timed test body would fix it in the harness. That
is a redesign of `VarkaOwnJvm` and `VarkaSuiteExtension` together, and the workflow does not
need it: each of its JVMs already runs this one suite alone.

## 3. The change

* `varka-fuzz.yml`: the IR fuzzer's JVMs run the suite directly (`-Dvarka.ownJvm=true`), as the
  variants and series steps already do, with the per-test caps at 45 minutes
  (`spark.test.timeout`, `varka.test.watchdog.minutes`). The composition fuzzer runs directly
  too. The composition and variants steps run whether or not the step before them failed
  (`if: ${{ !cancelled() }}`), so one fuzzer's failure no longer hides the others.
* `dev/varka_nightly.sh`: the laptop nightly's fuzz step passes `-Dvarka.ownJvm=true` as well. Its
  sbt test JVM runs only `VarkaIrFuzzSuite`, at 10,000 iterations, the same trap with more room.
* `VarkaChildJvm`: the timeout message says the budget is one test's cap for the whole suite, and
  names `-Dvarka.ownJvm=true` as the way out for a JVM that runs only that suite.
* `VarkaIrFuzzSuite`: "a planted liveness bug shrinks to one small root" asserted that the shrunk
  case has fewer nodes than the first failing draw. At some seeds that draw is already within the
  test's own bound of five nodes (three at 202610133, two at 202610181), and the shrinker has
  nothing to remove. The strict reduction is now asserted only for a draw above the bound. The
  nightly's own seeds found it: it would have failed on 13 October had the suite finished.

## 9. Outcome

Run on the laptop on 18 October 2026, from this branch's build, the step exactly as the workflow
now runs it (`-Dvarka.ownJvm=true`, caps at 30 minutes then, 40,000 iterations a lane, variants
every tenth draw):

* **Today's four seeds, 202610180 to 202610183, four JVMs held to four cores** (`taskset -c 0-3`)
  beside the replay below: 26 min 19 s to 30 min 2 s a JVM, 1,803 s for the step. The compiled
  test took 12 min 50 s to 18 min 8 s of it, each 40,000-iteration lane about 8 minutes. On 12
  October a runner ran a lane in about 3 minutes, so the step should take 15 to 25 minutes there.
  That is why the caps are 45 minutes and not 30, and the job's 90 minutes still hold it with the
  other two steps (21 minutes in all on 12 October). Seed 202610181 failed the shrinker test
  above, and nothing else.
* **The replay of the five nights the fuzzer missed**: the 20 seeds of 13 to 17 October,
  202610130 to 202610173, on the other 20 cores, 32 to 34 minutes a JVM. Nineteen passed every
  test; 202610133 failed only the shrinker test. **The six dark nights hid no wrong answer** in
  the IR fuzzer: every random tree, compiled tree, long-lane tree and wide composition agreed
  with the reference evaluator.
* **The fix of the shrinker test**, run alone at 202610133, 202610181 and 202610180: passes at
  each.
* What only the next scheduled run shows: the step's time on a runner, and the composition and
  variants steps after six skipped nights. The workflow runs from the default branch, so this
  branch cannot dispatch it before the merge.
