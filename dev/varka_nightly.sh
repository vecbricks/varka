#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# The checks that need volume or an idle machine, in one command that leaves a
# dated log: the machine canary, the IR fuzzer at ten thousand iterations with
# a fresh seed, the random differential against vanilla Spark (VARKA-262) and its arm
# with the kernel warm-up on (VARKA-327), the exhaustive calendar sweeps, the SMT proofs
# under both pinned solvers, every small IR tree to size 5 (VARKA-269) and to size 4 in
# each of its variants (VARKA-278), the deoptimization-cycle guard and its ratchet over the
# NULL-mode kernels (VARKA-378), the inlining-cliff guard, and Spark's golden-file suite with
# Varka on (VARKA-271). Optionally the whole gate.
#
#   dev/varka_nightly.sh                  # canary, fuzzer (10000, seed = today), Spark differential (50000, and 2000 warmed), sweeps, proofs, deopt, cliff
#   dev/varka_nightly.sh --iterations 500 --seed 42 --skip-sweep --skip-deopt --skip-deoptnull --skip-cliff   # a quick trial
#   dev/varka_nightly.sh --gate           # the standing gate as well
#   dev/varka_nightly.sh --series-size 6  # every small tree to size 6, hours over every core
#   dev/varka_nightly.sh --variants-size 0  # without the variants of the small trees
#   dev/varka_nightly.sh --sparkfuzz-iterations 5000 --skip-sweep --skip-deopt --skip-deoptnull --skip-cliff
#   dev/varka_nightly.sh --sparkwarm-iterations 0   # without the warmed arm of the differential
#   dev/varka_nightly.sh --skip-golden    # without the golden-file suite, about an hour
#   dev/varka_nightly.sh --skip-platform  # without the forked JVM arms (interpreter, C1, C2, -Xcomp, ...)
#   dev/varka_nightly.sh --skip-deoptnull # without the NULL-mode kernels' cycle ratchet
#   dev/varka_nightly.sh --skip-history   # without the kernel-after-kernels arm (VARKA-257)
#   dev/varka_nightly.sh --skip-compiled  # without the compiled fuzz histories at volume
#
# The deopt step forks ten fresh JVMs per vector width of the default emission's twelve-output
# make_date kernel and fails if any of them enters the C2 deoptimization cycle, reading the
# JVM's own compile and trap log (dev/varka_deopt_cycle.sh, VARKA-189.md 3.3). The deoptnull
# step covers the kernels that one never runs, the NULL-mode kernels with no dense loop, where
# the cycle lives today (VARKA-326.md 9): the IR fuzzer's compiled test over a fixed range of
# draws, one JVM per vector width, counting the kernels still trapping at profile_predicate at
# the warm-up's 10 s deadline, and fails when a width counts more than the ratchet committed in
# dev/varka_deopt_census_guard.tsv (dev/varka_deopt_census.sh --guard, VARKA-378.md 3.1). The cliff
# step forks ten fresh JVMs of the cheap-tail shape at 24 and 40 outputs under the production
# emitter and fails if any of them is slow at the end of twelve seconds - the loop method past
# C1's limit, which the call-site budget is there to prevent (dev/varka_inlining_cliff.sh,
# VARKA-209.md 11). The golden step runs Spark's SQLQueryTestSuite files through an Arrow cache
# with Varka on, Spark's committed answers the oracle, and reruns the tests it fails with Varka
# off, naming each failure Varka's or the harness's (dev/varka_golden_suite.sh, VARKA-271.md 3.2).
# The platform step, the last, forks one JVM per arm - the interpreter, C1, C2, the Vector API's
# intrinsics off, -Xcomp, C2's seeded stress flags on the shipped JDK, and the fastdebug JDK's
# when its build is beside the repository - over the IR fuzzer's corpus with the day's seed, each
# arm made to prove its tier through JFR and the code list, the answers compared across the arms,
# a crashed arm's hs_err, replay and JFR files kept under the log directory
# (dev/varka_platform.sh, VARKA-272.md 3).
#
# Every step runs under dev/varka_deadline.sh, VARKA_STEP_DEADLINE seconds (default 10800, the
# ten-thousand-iteration fuzzer's room with margin), so a hung step fails instead of holding
# the machine until morning (VARKA-283).
#
# Logs go under target/varka-nightly/<date>/; the summary names the fuzzer's
# seed so a failure replays with -Dvarka.fuzz.seed=<seed> -Dvarka.fuzz.only=<n>.
# Exit status is the number of failed steps.
set -uo pipefail
# Usage text is found rather than numbered: a hard-coded range silently truncates as the
# comment above it grows. Ends at the first line that is not a comment.
usage() { sed -n '17,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }

root="$(git rev-parse --show-toplevel)"; cd "$root"
iterations=10000; seed="$(date +%Y%m%d)"; sweep=1; deopt=1; cliff=1; gate=0; golden=1
sparkfuzz=50000
sparkwarm=2000
series=5
variants=4
platform=1
deoptnull=1
compiled=1
while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --iterations) iterations="$2"; shift 2 ;;
    --seed) seed="$2"; shift 2 ;;
    --sparkfuzz-iterations) sparkfuzz="$2"; shift 2 ;;
    --sparkwarm-iterations) sparkwarm="$2"; shift 2 ;;
    --series-size) series="$2"; shift 2 ;;
    --variants-size) variants="$2"; shift 2 ;;
    --skip-sweep) sweep=0; shift ;;
    --skip-deopt) deopt=0; shift ;;
    --skip-deoptnull) deoptnull=0; shift ;;
    --skip-history) history=0; shift ;;
    --skip-compiled) compiled=0; shift ;;
    --skip-cliff) cliff=0; shift ;;
    --skip-golden) golden=0; shift ;;
    --skip-platform) platform=0; shift ;;
    --gate) gate=1; shift ;;
    *) usage ;;
  esac
done
logdir="target/varka-nightly/$(date +%Y-%m-%d)"
mkdir -p "$logdir"
declare -A status secs
run_step() {
  local name="$1"; shift
  local start=$SECONDS
  echo "== $name (log: $logdir/$name.log)"
  if "$root/dev/varka_deadline.sh" "${VARKA_STEP_DEADLINE:-10800}" "$@" > "$logdir/$name.log" 2>&1
  then
    status[$name]=ok
  else
    status[$name]=FAILED
  fi
  secs[$name]=$((SECONDS - start))
  # Beside the log, as dev/varka_gate.sh writes them, so `dev/varka_ci_times.py gate` reads a
  # nightly's step times against the budget (VARKA-255.md 3.6).
  echo "${secs[$name]}" > "$logdir/$name.secs"
  echo "${status[$name]}" > "$logdir/$name.status"
  grep -h -E "Tests: succeeded|FAILED \*\*\*|^failed \(|verdict|[[:space:]]OFF$|seed=|^\[info\] - " "$logdir/$name.log" \
    | sed 's/^\[info\] //' | head -6 | sed 's/^/   /'
}
# The fuzz step's test JVM runs VarkaIrFuzzSuite alone, so the suite runs in it directly rather
# than in the child VarkaOwnJvm forks, whose budget is one test's cap for the whole suite
# (VARKA-392).
fuzz_opts="set Test/javaOptions ++= Seq(\"-Dvarka.fuzz.iterations=$iterations\", \"-Dvarka.fuzz.seed=$seed\", \"-Dvarka.ownJvm=true\")"

run_step canary dev/varka_bench_canary.sh
run_step fuzz build/sbt -batch "project catalyst" "$fuzz_opts" 'testOnly *VarkaIrFuzzSuite'
# One fused entry of each random projection forced to decline: the projection must equal the one
# without the entry (VARKA-296). A hundred thousand compositions take a few minutes.
run_step forced build/sbt -batch "project catalyst" \
  "set Test/javaOptions ++= Seq(\"-Dvarka.fuzz.forcedCompositions=100000\", \"-Dvarka.fuzz.seed=$seed\")" \
  'testOnly *VarkaCoverageCompositionFuzzSuite -- -z "forced decline leaves"'
# Random compositions of the coverage rows over random data, Varka off against on (VARKA-262).
# A disagreement leaves its shrunk reproducer under sql/core/target/varka-sparkfuzz/ and its text in the log.
# One output of each composition is forced to decline as well (VARKA-296); forcing every one
# (-Dvarka.sparkfuzz.forceAll=true) cost two hours for 5,000 compositions, so it is for idle windows.
if [ "$sparkfuzz" -gt 0 ]; then
  # Without the nested trees (VARKA-298) until row 386 is fixed: they meet it most nights.
  run_step sparkfuzz build/sbt -batch "project sql" \
    "set Test/javaOptions ++= Seq(\"-Dvarka.sparkfuzz.iterations=$sparkfuzz\", \"-Dvarka.sparkfuzz.nested=false\", \"-Dvarka.sparkfuzz.seed=$seed\", \"-Dspark.test.timeout=170\", \"-Dvarka.test.watchdog.minutes=170\")" \
    'testOnly *VarkaSparkFuzzSuite *VarkaSparkReproducerSuite'
fi
# The same compositions with the kernel warm-up on, as production runs (VARKA-327): each shape's
# first query on the row path while its kernel compiles, the next on the kernel, then the kernel
# over other data, every run against the row engine. A kernel released without its verdict logs
# the JIT's account of it, the evidence VARKA-295 asks for; only answers fail the step.
if [ "$sparkwarm" -gt 0 ]; then
  run_step sparkwarm build/sbt -batch "project sql" \
    "set Test/javaOptions ++= Seq(\"-Dvarka.sparkfuzz.warmupIterations=$sparkwarm\", \"-Dvarka.sparkfuzz.seed=$seed\", \"-Dspark.test.timeout=170\", \"-Dvarka.test.watchdog.minutes=170\")" \
    "testOnly *VarkaSparkFuzzSuite -- -z \"both sides of the warm-up handover\""
fi
# Spark's golden files through the Arrow source with Varka on, in a JVM of its own; a failure not
# on sql/varka/spark_suites/excluded.tsv is rerun with Varka off and named Varka's or the harness's.
if [ "$golden" -eq 1 ]; then
  # Each arm's own deadline keeps both arms and the build inside the step's.
  run_step golden dev/varka_golden_suite.sh --attribute --deadline 4800 --out "$logdir/golden"
fi
if [ "$sweep" -eq 1 ]; then
  run_step sweep build/sbt -batch "project catalyst" \
    'set Test/javaOptions += "-Dvarka.sweep=true"' \
    'testOnly *VarkaChronoSuite *VarkaEmitter*Suite -- -z opt-in'
fi
# The proofs under Z3 and cvc5, each held to every expectation, so the two agree (VARKA-240); the
# lint job runs Z3 alone. Beside the sweep step, whose multiply-high sweep checks the statement of
# int_mulhi_divide.smt2 by running Java's own arithmetic.
run_step prove bash -c 'dev/varka_prove.sh --install both && dev/varka_prove.sh --self-test \
  --solver both && dev/varka_prove.sh --solver both'
# Every IR tree of the grammar to the size, one JVM per core (VARKA-269); size 5 is about twelve
# minutes on the laptop, size 6 hours. 0 skips it.
if [ "$series" -gt 0 ]; then
  run_step series dev/varka_series.sh "$series"
fi
# Every tree to the size in each of the eight variants (VARKA-278), one JVM per core; size 4 is a
# few minutes. 0 skips it. It reuses the series step's build when that step ran.
if [ "$variants" -gt 0 ]; then
  reuse=()
  [ "$series" -gt 0 ] && [ "${status[series]:-}" = ok ] && reuse=(--skip-build)
  run_step variants dev/varka_series.sh "$variants" --variants "${reuse[@]}"
fi
if [ "$deopt" -eq 1 ]; then
  run_step deopt dev/varka_deopt_cycle.sh --forms group --forks 10 --fail-on-cycle
fi
# The NULL-mode kernels' cycle, ratcheted on a committed count (VARKA-378): it fails when a width
# counts more than dev/varka_deopt_census_guard.tsv allows, and a fix lowers the count there.
if [ "$deoptnull" -eq 1 ]; then
  run_step deoptnull dev/varka_deopt_census.sh --guard --out "$logdir/deoptnull"
fi
# A kernel compiled after other kernels (VARKA-257): forks warm sixteen kernels of the IR fuzz
# grammar through production's warm-up, then the cliff probe's cheap tails at 24 outputs, read its
# rate and its allocation, and compare every kernel's answers; a wrong answer, an unfinished fork
# or a slow one fails (dev/varka_kernel_history.sh, VARKA-257.md 3.2). It reuses deoptnull's build.
if [ "${history:-1}" -eq 1 ]; then
  history_build=(--build)
  [ "${status[deoptnull]:-}" = ok ] && history_build=()
  run_step history dev/varka_kernel_history.sh --after 16 --forks 6 --guard \
    "${history_build[@]}" --out "$logdir/history"
fi
# The IR fuzzer's compiled test at volume (VARKA-274): 1,000 draws a lane with the day's seed, each
# kernel over the eight batch histories - interpreted, warmed to C2, trapped after a quiet warm-up,
# recompiled, released through the OSRs with C1 on - every batch compared and its tier read by
# JFR, eight JVMs of three cores; then every coverage row's kernel over three of the histories. A
# wrong answer fails the step; the verdicts per history, the unsettled kernels and the histories
# not reached are counted (dev/varka_compiled_fuzz.sh).
if [ "$compiled" -eq 1 ]; then
  reuse=()
  [ "$deoptnull" -eq 1 ] && [ "${status[deoptnull]:-}" = ok ] && reuse=(--skip-build)
  run_step compiled dev/varka_compiled_fuzz.sh --seed "$seed" --out "$logdir/compiled" \
    "${reuse[@]}"
fi
if [ "$cliff" -eq 1 ]; then
  run_step cliff dev/varka_inlining_cliff.sh --outputs 24,40 --forks 10 --seconds 12 --fail-if-slow
fi
[ "$gate" -eq 1 ] && run_step gate dev/varka_gate.sh
# The kernels under the interpreter, C1, C2, the intrinsics off, -Xcomp and the stress flags, each
# in a JVM of its own, the answers compared across the arms and each arm's tier proven (VARKA-272);
# last, after the gate has had the machine to itself.
if [ "$platform" -eq 1 ]; then
  # Each arm's own deadline, 30 minutes, keeps the seven arms two at a time inside the step's.
  run_step platform dev/varka_platform.sh --seed "$seed" --arm-deadline 1800 \
    --out "$logdir/platform"
fi

echo
echo "fuzzer seed $seed, $iterations iterations; Spark differential $sparkfuzz compositions," \
  "$sparkwarm with the warm-up on"
printf '%-9s %-7s %6s  %s\n' step status secs log
bad=0
for s in canary fuzz forced sparkfuzz sparkwarm sweep prove series variants deopt deoptnull \
  cliff gate; do
  [ -n "${status[$s]:-}" ] || continue
  printf '%-9s %-7s %6d  %s\n' "$s" "${status[$s]}" "${secs[$s]}" "$logdir/$s.log"
  [ "${status[$s]}" = ok ] || bad=$((bad + 1))
done
# The history step is listed on its own lines, so that the step list above stays as it was.
if [ -n "${status[history]:-}" ]; then
  printf '%-9s %-7s %6d  %s\n' history "${status[history]}" "${secs[history]}" \
    "$logdir/history.log"
  [ "${status[history]}" = ok ] || bad=$((bad + 1))
fi
if [ -n "${status[compiled]:-}" ]; then
  printf '%-9s %-7s %6d  %s\n' compiled "${status[compiled]}" "${secs[compiled]}" \
    "$logdir/compiled.log"
  [ "${status[compiled]}" = ok ] || bad=$((bad + 1))
fi
# The platform step, the last to run, is listed last.
if [ -n "${status[platform]:-}" ]; then
  printf '%-9s %-7s %6d  %s\n' platform "${status[platform]}" "${secs[platform]}" \
    "$logdir/platform.log"
  [ "${status[platform]}" = ok ] || bad=$((bad + 1))
fi
exit "$bad"
