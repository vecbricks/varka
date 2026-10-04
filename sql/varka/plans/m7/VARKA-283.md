# VARKA-283: The division sweep outlives the watchdog, and sbt hangs on the halted fork

*Found 3 October 2026 by VARKA-236's gate run; row 283 of `m7/PLAN.md`, wave 0. Opened 4 October
2026, the first task of milestone 7.*

## 1. Where this came from

`dev/varka_gate.sh`'s sweep step ran VARKA-149's opt-in test "the multiply-high form is exact over
every int32 dividend for every divisor in use", which checks nine divisors over all 2^32 dividends
in one test. `VarkaTestWatchdog` (VARKA-226) gives a test ten minutes and then halts its JVM, and
it did: the thread dump it wrote showed the test at its inner loop. sbt then did not report the
suite aborted, as VARKA-226 intended, but waited on the halted forked JVM indefinitely, printing
"Unable to read from client", until the gate was stopped by hand an hour later. Every log on record
shows this test only ever cancelled by its opt-in or halted, so the gate's and the nightly's sweep
steps have not passed since the watchdog landed on 28 September 2026, and VARKA-240's proofs are to
be compared against a sweep that never completed.

## 2. The admission check, done

**Why the sweep is slow.** The inner loop compared with ScalaTest's `assert(q === n / d, s"d=$d
n=$n")`. The assert macro evaluates its clue on every call, so each of the 9 x 2^32 iterations
built a string. The arithmetic itself is a multiply, a shift and an add.

**Why sbt hangs.** Spark's build forks its test JVMs (`fork := true` in `SparkBuild.scala`), and
sbt talks to a forked test JVM over a socket. `Runtime.halt` ends that JVM without the test
framework's goodbye, and the sbt server kept waiting on the socket. Nothing in the gate bounded a
step's time.

**What the row allows.** Either split the test or give it a cap of its own, and either make the
halt reach sbt or give the steps a timeout of their own.

## 3. The design

### 3.1 One test per divisor, a plain comparison, and a deadline on every step

* **The sweep.** One generated test per divisor in `intDivisors`, each the same loop with the
  comparison as a branch and `fail` with the divisor, the dividend and both quotients on a
  mismatch. Splitting names the failing divisor in the test's title and keeps each test far inside
  the watchdog; the branch removes the per-iteration string. No cap of its own is needed, so the
  watchdog stays one number for every Varka test.
* **The hang.** `dev/varka_deadline.sh <seconds> <command...>` runs the command in a session of
  its own (`setsid`), and at the deadline sends SIGTERM to the whole process group, SIGKILL a
  minute later, and exits 124. The group matters: `timeout(1)` would signal sbt alone and leave
  its forked JVMs running. Its own sleeps are waited on so that a command that finishes in time
  leaves nothing behind. Both `dev/varka_gate.sh` and `dev/varka_nightly.sh` run every step under
  it, `VARKA_STEP_DEADLINE` seconds, defaults 3600 for the gate and 10800 for the nightly (the
  ten-thousand-iteration fuzzer with margin). The gate's narrow step called a shell function, which
  an external wrapper cannot run, so it now sets `JAVA_OPTS` through `env`.
* **Considered and set aside.** Making the watchdog exit through a path sbt's test agent reports
  (`System.exit` instead of `Runtime.halt`): `exit` runs shutdown hooks and can itself hang on the
  very thread that is stuck, which is why VARKA-226 chose `halt`; and the deadline is needed anyway
  for every other way a step can hang.

### 3.2 What is deliberately unchanged

The arithmetic the sweep checks, the divisors, the opt-in property, the watchdog's ten minutes and
its halt, and every other sweep test.

### 3.3 Registered op counts

None: no emitted byte moves.

## 4. Files

* `VarkaEmitterDivisionSuite.scala`: the sweep, one test per divisor.
* `dev/varka_deadline.sh`: new.
* `dev/varka_gate.sh`, `dev/varka_nightly.sh`: every step under the deadline; the narrow step's
  `env`.

## 5. Tests, and what each is for

* The nine sweep tests, run under `-Dvarka.sweep=true`: the proof they carry, now completing.
* The deadline script on a command that exits in time (its status passes through, no process left)
  and on one that overruns with a child of its own (status 124, the child gone).
* The real failure reproduced: master's single-test sweep with `-Dvarka.test.watchdog.minutes=1`
  under a ten-minute deadline, which must halt, hang, and be stopped by the deadline with no
  process left.
* The gate end to end, including its sweep step.

## 6. The measurement

The sweep's time per divisor and in total, from the test report; the gate's sweep step's time.

### 6.1 Predictions, registered before the run

1. Each divisor's sweep takes under 30 seconds on the laptop, the whole sweep under five minutes.
2. The gate's sweep step passes and takes under fifteen minutes.
3. The reproduction exits 124 near the ten-minute mark, after the watchdog's halt at about a minute
   and a half into the test, with no sbt or test JVM left running.

## 7. Risks

1. **A deadline too short for a slow machine** fails a healthy step. The defaults are several
   times the steps' measured times, and `VARKA_STEP_DEADLINE` raises them.
2. **`setsid` absent** on a non-Linux host: it is util-linux, present on every machine the project
   uses; the script fails loudly if it is missing.

## 8. Sequencing

One pull request: the plan, the sweep, the deadline, the reproduction's result and the gate run
in section 9.

## 9. Outcome

Built and measured on 4 October 2026, on the laptop.

1. **Held.** Each divisor's sweep takes 5.0 to 5.3 seconds, the nine together 47 seconds in their
   own run, where the single test ran past the watchdog's ten minutes every time it was allowed to
   run. The whole cost was the assert's clue string: the arithmetic per dividend is a multiply, a
   shift and an add.
2. **Held.** `dev/varka_gate.sh` passed every step, and its sweep step passed for the first time
   since the watchdog landed: 18 tests, the nine divisors among them, in 141 seconds.
3. **Held, and it found a bug in the first version of the wrapper.** Master's single-test sweep
   with a one-minute watchdog, under a 600-second deadline: the watchdog halted the test at its
   cap, sbt then hung printing "Unable to read from client", and the deadline stopped the step at
   600 seconds with exit 124. But the first version of `dev/varka_deadline.sh` cancelled its
   pending SIGKILL as soon as the leader exited, so a JVM that ignored SIGTERM would have outlived
   it; the sbt launcher's JVM was still shutting down when the check ran and exited a moment later.
   The wrapper now waits up to the watcher's minute for the whole group after a deadline and then
   kills what is left, which a command ignoring SIGTERM confirms leaves nothing behind.

**Also found:** the gate's narrow step called a shell function, which an external wrapper cannot
run; it now sets `JAVA_OPTS` through `env`. And twice in this task a wait loop built on `pgrep -f`
matched its own command line, a trap the project has met before; the wrapper waits on the
process it started, never on a name.
