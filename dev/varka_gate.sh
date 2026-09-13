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
#   compile   build/sbt catalyst/Test/compile sql/Test/compile
#   wide      catalyst and sql/core Varka suites at the host's vector width
#   narrow    the same under -XX:MaxVectorSize=16 (128-bit lanes)
#   sweep     the opt-in exhaustive calendar sweeps (-Dvarka.sweep=true), both
#             the scalar model's and the emitted kernel's, at the wide width
#   doc       build/sbt catalyst/doc, the javadoc gate CI runs
#   engine    ./build/mvn -f sql/varka/engine/pom.xml test (off by default)
#   bench     ./build/mvn -f sql/varka/bench/pom.xml test - the benchmark drivers'
#             own suites, which nothing else runs: CI builds that module only with
#             -DskipTests, so the invariants ChainsTest and SurfaceTest hold (every
#             entry fuses, carries all three types and clears MIN_OPS) are enforced
#             here or nowhere. About ten seconds.
#   lint      dev/lint-java and dev/scalastyle
#   quotes    dev/varka_quote_check.py: every number the documents quote traces to a
#             committed results file (or the allowlist)
#
# The assembly suite (task 31) is part of `wide` and `narrow`; it needs a
# disassembler and cancels without one. If VARKA_HSDIS_DIR is unset this
# script looks for hsdis-<arch>.so in the usual local places and exports it
# when found, and says so either way, because a gate that silently skipped
# its instruction assertions is a gate that lies.
set -uo pipefail

# Usage text is found rather than numbered: a hard-coded range silently truncates as the
# comment above it grows, which had already happened to four of these scripts. Ends at the
# first line that is not a comment.
usage() { sed -n '17,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }

steps_all=(compile wide narrow sweep doc bench lint quotes)
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

declare -A status secs
run_step() {
  local name="$1"; shift
  local log="$logdir/$name.log"
  local start=$SECONDS
  echo "== $name: $* (log: $log)"
  if "$@" > "$log" 2>&1; then status[$name]=ok; else status[$name]=FAILED; fi
  secs[$name]=$((SECONDS - start))
  if [ "${status[$name]}" = FAILED ]; then
    echo "-- $name FAILED; last lines of $log:"
    grep -E "FAILED \*\*\*|\[error\]|error:|Tests: succeeded" "$log" | tail -12
  else
    grep -h -E "Tests: succeeded" "$log" | sed 's/^\[info\] //' | sed "s/^/   /"
  fi
}

narrow_env() { JAVA_OPTS="${JAVA_OPTS:-} -XX:MaxVectorSize=16" "$@"; }

for s in "${selected[@]}"; do
  case "$s" in
    compile) run_step compile build/sbt -batch catalyst/Test/compile sql/Test/compile ;;
    wide) run_step wide build/sbt -batch 'catalyst/testOnly *Varka*' 'sql/testOnly *Varka*' ;;
    narrow) run_step narrow narrow_env build/sbt -batch \
              'catalyst/testOnly *Varka*' 'sql/testOnly *Varka*' ;;
    sweep) run_step sweep build/sbt -batch "project catalyst" \
             'set Test/javaOptions += "-Dvarka.sweep=true"' \
             'testOnly *VarkaChronoSuite *VarkaLoopEmitterSuite -- -z opt-in' ;;
    doc) run_step doc build/sbt -batch catalyst/doc ;;
    engine) run_step engine ./build/mvn -q -f sql/varka/engine/pom.xml test ;;
    bench) run_step bench ./build/mvn -q -f sql/varka/bench/pom.xml test ;;
    lint) run_step lint bash -c 'dev/lint-java && dev/scalastyle' ;;
    quotes) run_step quotes dev/varka_quote_check.py ;;
  esac
done

echo
printf '%-8s %-7s %6s  %s\n' step status secs log
bad=0
for s in "${selected[@]}"; do
  printf '%-8s %-7s %6d  %s\n' "$s" "${status[$s]}" "${secs[$s]}" "$logdir/$s.log"
  [ "${status[$s]}" = ok ] || bad=$((bad + 1))
done
exit "$bad"
