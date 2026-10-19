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
# The standing Varka gate, in one command: everything a task plan's
# "Verification" section lists, in the order it lists it, each step logged
# to its own file, one summary table at the end, non-zero exit if any step
# failed. Run it from the repository root of the worktree under test.
#
#   dev/varka_gate.sh                      # the whole gate
#   dev/varka_gate.sh --list               # show the steps and stop
#   dev/varka_gate.sh --only wide,narrow   # a subset, by step name
#   dev/varka_gate.sh --skip sweep,doc     # everything but these
#   dev/varka_gate.sh --engine             # also run the engine module's tests
#
# Steps, by name:
#   compile   build/sbt catalyst/Test/compile sql/Test/compile, and the test classpath exported
#   wide      catalyst and sql/core Varka suites at the host's vector width, split over several
#             JVMs per module and one for each suite that runs alone (dev/varka_matrix.sh
#             --defaults --split)
#   narrow    the same under -XX:MaxVectorSize=16 (128-bit lanes), on each test JVM's own
#             command line
#             Every suite step runs with the memory sanitizer on (VARKA-263); quiet does not.
#   quiet     the suites that measure the JIT rather than answers, VarkaAssemblySuite,
#             VarkaWarmupEndToEndSuite and VarkaTierWitnessSuite, at both widths, after the rest
#   sweep     the opt-in exhaustive calendar sweeps (-Dvarka.sweep=true), both
#             the scalar model's and the emitted kernel's, at the wide width
#   doc       build/sbt catalyst/doc, the javadoc gate CI runs
#   engine    ./build/mvn -f sql/varka/engine/pom.xml test (off by default)
#   bench     ./build/mvn -f sql/varka/bench/pom.xml test - the benchmark drivers'
#             own suites, which nothing else runs: CI builds that module only with
#             -DskipTests, so the invariants ChainsTest and SurfaceTest hold (every
#             entry fuses, carries all three types and clears MIN_OPS) are enforced
#             here or nowhere. About ten seconds.
#   lint      dev/lint-java, dev/scalastyle and dev/varka_errorprone.sh (Error Prone and
#             NullAway over Varka's Java, on the compile step's classpath when there is one)
#   quotes    dev/varka_quote_check.py: every number the documents quote traces to a
#             committed results file (or the allowlist)
#
# After compile, three lanes run at once (VARKA-286): the suites, wide and narrow together;
# sbt's steps - doc, lint, sweep - one after another, since two sbt invocations in one worktree
# contend for its lock; and bench and quotes. quiet runs last, on a machine the lanes have left.
# VARKA_GATE_SPLIT (default 3) sets the JVMs per module and width.
#
# The assembly suite (VARKA-31) is part of `quiet`; it needs a
# disassembler and cancels without one. If VARKA_HSDIS_DIR is unset this
# script looks for hsdis-<arch>.so in the usual local places and exports it
# when found, and says so either way, because a gate that silently skipped
# its instruction assertions is a gate that lies.
#
# Every step runs under dev/varka_deadline.sh, VARKA_STEP_DEADLINE seconds (default 3600): a
# step past it is stopped with everything it started and reported FAILED, so a hang - sbt
# waiting on a test JVM the watchdog halted (VARKA-283) - fails
# the gate within the hour instead of holding the machine.
set -uo pipefail

# Usage text is found rather than numbered: a hard-coded range silently truncates as the
# comment above it grows. Ends at the first line that is not a comment.
usage() { sed -n '17,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }

steps_all=(compile wide narrow quiet sweep doc bench lint quotes)
only=""; skip=""; engine=0; list=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --only) only="$2"; shift 2 ;;
    --skip) skip="$2"; shift 2 ;;
    --engine) engine=1; shift ;;
    --list) list=1; shift ;;
    *) usage ;;
  esac
done
[ "$engine" -eq 1 ] && steps_all+=(engine)

selected=()
for s in "${steps_all[@]}"; do
  if [ -n "$only" ] && ! [[ ",$only," == *",$s,"* ]]; then continue; fi
  if [ -n "$skip" ] && [[ ",$skip," == *",$s,"* ]]; then continue; fi
  selected+=("$s")
done
if [ "$list" -eq 1 ]; then printf '%s\n' "${selected[@]}"; exit 0; fi

root="$(git rev-parse --show-toplevel)"
cd "$root"
logdir="${VARKA_GATE_LOGDIR:-$root/target/varka-gate}"
mkdir -p "$logdir"

# The disassembler, for the assembly suite.
arch="$(uname -m | sed 's/x86_64/amd64/')"
if [ -z "${VARKA_HSDIS_DIR:-}" ]; then
  for d in "$HOME"/proj/openjdk-build/*/build/*/support/hsdis "$HOME"/hsdis \
      "${JAVA_HOME:-/nonexistent}"/lib/server; do
    if [ -f "$d/hsdis-$arch.so" ]; then export VARKA_HSDIS_DIR="$d"; break; fi
  done
fi
if [ -n "${VARKA_HSDIS_DIR:-}" ]; then
  echo "hsdis: $VARKA_HSDIS_DIR (the assembly suite will run)"
else
  echo "hsdis: not found - the assembly suite will cancel, not fail (see SKILLS.md on building it)"
fi

# A step's verdict and seconds go to files beside its log, so a step run in a lane - a background
# subshell - reports to the summary as one run in the foreground does.
run_step() {
  local name="$1"; shift
  local log="$logdir/$name.log"
  local start=$SECONDS
  echo "== $name: $* (log: $log)"
  if "$root/dev/varka_deadline.sh" "${VARKA_STEP_DEADLINE:-3600}" "$@" > "$log" 2>&1; then
    echo ok > "$logdir/$name.status"
  else
    echo FAILED > "$logdir/$name.status"
  fi
  echo $((SECONDS - start)) > "$logdir/$name.secs"
  if [ "$(cat "$logdir/$name.status")" = FAILED ]; then
    echo "-- $name FAILED; last lines of $log:"
    grep -E "FAILED \*\*\*|ABORTED|\[error\]|error:|Tests: succeeded|^configuration|^defaults" "$log" | tail -12
  fi
}
selected_has() { [[ " ${selected[*]} " == *" $1 "* ]]; }
rm -f "$logdir"/*.status "$logdir"/*.secs

# The suites run outside sbt, on the classpath the compile step exports (dev/varka_matrix.sh).
suites_build="$logdir/suites-build"
split="${VARKA_GATE_SPLIT:-3}"
timing_suites=VarkaAssemblySuite,VarkaWarmupEndToEndSuite,VarkaTierWitnessSuite
suites=(dev/varka_matrix.sh --defaults --skip-build --build-dir "$suites_build")

if selected_has compile; then
  run_step compile dev/varka_matrix.sh --defaults --out "$suites_build" --list
fi

lane_suites() {
  if selected_has wide; then
    run_step wide "${suites[@]}" --out "$logdir/wide-runs" --split "$split" \
      -j $((2 * split)) --skip-suites "$timing_suites" &
  fi
  if selected_has narrow; then
    echo "narrow: the preferred vector is $(java --add-modules jdk.incubator.vector \
      -XX:MaxVectorSize=16 dev/varka_canary/VectorBits.java) bits under the narrow step's flag"
    run_step narrow "${suites[@]}" --out "$logdir/narrow-runs" --split "$split" \
      -j $((2 * split)) --jvm-arg -XX:MaxVectorSize=16 --skip-suites "$timing_suites" &
  fi
  wait
}
lane_sbt() {
  if selected_has doc; then run_step doc build/sbt -batch catalyst/doc; fi
  if selected_has lint; then
    # The compile step's classpath only when that step passed in this run: the file outlives a
    # run, and a failed build leaves the previous run's classpath in place.
    run_step lint bash -c 'dev/lint-java && dev/scalastyle && dev/varka_errorprone.sh '"$(
      [ "$(cat "$logdir/compile.status" 2>/dev/null)" = ok ] && [ -s "$suites_build/classpath" ] \
        && echo "--classpath-file $suites_build/classpath")"
  fi
  if selected_has sweep; then
    run_step sweep build/sbt -batch "project catalyst" \
      'set Test/javaOptions += "-Dvarka.sweep=true"' \
      'testOnly *VarkaChronoSuite *VarkaEmitter*Suite -- -z opt-in'
  fi
}
lane_rest() {
  if selected_has bench; then run_step bench ./build/mvn -q -f sql/varka/bench/pom.xml test; fi
  if selected_has engine; then run_step engine ./build/mvn -q -f sql/varka/engine/pom.xml test; fi
  if selected_has quotes; then run_step quotes dev/varka_quote_check.py; fi
}
lane_suites &
lane_sbt &
lane_rest &
wait

# The quiet phase: each width's JIT-measuring suites, the narrow after the wide. The deadline
# wrapper runs a program, so the two runs are one quoted bash command.
if selected_has quiet; then
  quiet_wide=$(printf '%q ' "${suites[@]}" --out "$logdir/quiet-wide-runs" \
    --suites "$timing_suites" -j 2 --no-sanitizer)
  quiet_narrow=$(printf '%q ' "${suites[@]}" --out "$logdir/quiet-narrow-runs" \
    --suites "$timing_suites" -j 2 --no-sanitizer --jvm-arg -XX:MaxVectorSize=16)
  run_step quiet bash -c "$quiet_wide && $quiet_narrow"
fi

echo
printf '%-8s %-7s %6s  %s\n' step status secs log
bad=0
for s in "${selected[@]}"; do
  st=$(cat "$logdir/$s.status" 2>/dev/null || echo "not run")
  printf '%-8s %-7s %6d  %s\n' "$s" "$st" "$(cat "$logdir/$s.secs" 2>/dev/null || echo 0)" \
    "$logdir/$s.log"
  [ "$st" = ok ] || bad=$((bad + 1))
done
echo "gate: $SECONDS s"
exit "$bad"
