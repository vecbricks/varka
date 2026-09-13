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
# a fresh seed, and the exhaustive calendar sweeps. Optionally the whole gate.
#
#   dev/varka_nightly.sh                  # canary, fuzzer (10000, seed = today), sweeps
#   dev/varka_nightly.sh --iterations 500 --seed 42 --skip-sweep   # a quick trial
#   dev/varka_nightly.sh --gate           # the standing gate as well
#
# Logs go under target/varka-nightly/<date>/; the summary names the fuzzer's
# seed so a failure replays with -Dvarka.fuzz.seed=<seed> -Dvarka.fuzz.only=<n>.
# Exit status is the number of failed steps.
set -uo pipefail
# Usage text is found rather than numbered: a hard-coded range silently truncates as the
# comment above it grows, which had already happened to four of these scripts. Ends at the
# first line that is not a comment.
usage() { sed -n '17,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }

root="$(git rev-parse --show-toplevel)"; cd "$root"
iterations=10000; seed="$(date +%Y%m%d)"; sweep=1; gate=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --iterations) iterations="$2"; shift 2 ;;
    --seed) seed="$2"; shift 2 ;;
    --skip-sweep) sweep=0; shift ;;
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
  if "$@" > "$logdir/$name.log" 2>&1; then status[$name]=ok; else status[$name]=FAILED; fi
  secs[$name]=$((SECONDS - start))
  grep -h -E "Tests: succeeded|FAILED \*\*\*|verdict|[[:space:]]OFF$|seed=|^\[info\] - " "$logdir/$name.log" \
    | sed 's/^\[info\] //' | head -6 | sed 's/^/   /'
}
fuzz_opts="set Test/javaOptions ++= Seq(\"-Dvarka.fuzz.iterations=$iterations\", \"-Dvarka.fuzz.seed=$seed\")"

run_step canary dev/varka_bench_canary.sh
run_step fuzz build/sbt -batch "project catalyst" "$fuzz_opts" 'testOnly *VarkaIrFuzzSuite'
if [ "$sweep" -eq 1 ]; then
  run_step sweep build/sbt -batch "project catalyst" \
    'set Test/javaOptions += "-Dvarka.sweep=true"' \
    'testOnly *VarkaChronoSuite *VarkaLoopEmitterSuite -- -z opt-in'
fi
[ "$gate" -eq 1 ] && run_step gate dev/varka_gate.sh

echo
echo "fuzzer seed $seed, $iterations iterations"
printf '%-8s %-7s %6s  %s\n' step status secs log
bad=0
for s in canary fuzz sweep gate; do
  [ -n "${status[$s]:-}" ] || continue
  printf '%-8s %-7s %6d  %s\n' "$s" "${status[$s]}" "${secs[$s]}" "$logdir/$s.log"
  [ "${status[$s]}" = ok ] || bad=$((bad + 1))
done
exit "$bad"
