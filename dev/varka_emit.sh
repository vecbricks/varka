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
# What does the emitter make of a projection? One command, from SQL text to the
# per-method op counts - and, with --asm, to C2's assembly for the dense loop.
#
#   dev/varka_emit.sh "year(d)"
#   dev/varka_emit.sh "year(d)" "month(d)" --options shareChronoPrefix=false
#   dev/varka_emit.sh "date_add(d, 7)" --columns d:date --asm
#   dev/varka_emit.sh "year(d)" --rounds 20000 --nulls 64   # drive the masked path
#   dev/varka_emit.sh "year(d)" "month(d)" "add_months(d, 1)" --table \
#     --variant neriSchneiderMonth=false
#
# Everything but --asm is passed through to VarkaEmitDump (catalyst test scope);
# --table prints the markdown op-count table a plan registers (one row per
# expression, one column per --variant, deltas against the defaults).
# Otherwise VarkaEmitDump
# which prints the IR, the shape hash, and per emitted method its bytecode size,
# IntVector and VectorMask invocation counts and line-map entries. --asm runs the
# kernel hot under -XX:CompileCommand=print for loopDense0, saves the full
# disassembly under target/varka-emit/, and prints the standard C2 compilation's
# mnemonic frequencies - which is what an op-count prediction should be checked
# against, and what a boxed vector (task 55) shows up in as prefetchnta and
# mark-word stores.
set -euo pipefail
# Usage text is found rather than numbered: a hard-coded range silently truncates as the
# comment above it grows, which had already happened to four of these scripts. Ends at the
# first line that is not a comment.
usage() { sed -n '17,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }
case "${1:-}" in -h|--help) usage 0 ;; esac

root="$(git rev-parse --show-toplevel)"; cd "$root"
asm=0; pass=()
for a in "$@"; do
  case "$a" in
    --asm) asm=1 ;;
    *) pass+=("$a") ;;
  esac
done
[ "${#pass[@]}" -gt 0 ] || { usage; }
main=org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitDump
# sbt splits runMain's argument string on spaces; keep each expression one token.
quoted=""
for a in "${pass[@]}"; do quoted+=" \"$a\""; done

if [ "$asm" -eq 0 ]; then
  # The run's output is kept, not piped straight into the filter, so that a run which threw
  # can be told from a run that simply matched nothing. It could not be before: the filter
  # printed nothing either way and the script exited 0, so an unsupported --columns type or an
  # expression the analyzer rewrites read exactly like a negative result. That cost three
  # round trips on 12 September 2026 before the runner was invoked by hand, and it is the
  # debt register entry this closes.
  log="$(mktemp -t varka-emit.XXXXXX)"
  trap 'rm -f "$log"' EXIT
  # `|| true` so `set -e` does not abort before the diagnosis below: a non-zero run is
  # exactly the case this block exists to explain.
  build/sbt -batch "catalyst/Test/runMain $main$quoted" > "$log" 2>&1 || true
  # `|| true` again: grep exits 1 when it filters everything out, which under `set -e` would
  # abort here - the same silence, one line further down.
  report="$(sed -E 's/^\[(info|error)\] ?//' "$log" \
    | sed -n '/^entry \|^| expression/,$p' | grep -v -E '^\[(success|warn)\]|^WARNING: ' || true)"
  if [ -z "$report" ]; then
    echo "the dump produced no report; the run's own output follows" >&2
    sed -E 's/^\[(info|error)\] ?//' "$log" | grep -E 'Exception|Error|error:' | head -5 >&2
    exit 1
  fi
  printf '%s\n' "$report"
  exit 0
fi

arch="$(uname -m | sed 's/x86_64/amd64/')"
if [ -z "${VARKA_HSDIS_DIR:-}" ]; then
  for d in "$HOME"/proj/openjdk-build/*/build/*/support/hsdis "$HOME"/hsdis; do
    if [ -f "$d/hsdis-$arch.so" ]; then export VARKA_HSDIS_DIR="$d"; break; fi
  done
fi
[ -n "${VARKA_HSDIS_DIR:-}" ] \
  || echo "no hsdis found; HotSpot will print a hex dump instead of instructions" >&2
mkdir -p target/varka-emit
log="target/varka-emit/asm-$(date +%H%M%S).log"
print_cmd="-XX:CompileCommand=print,org.apache.spark.sql.varka.execution.VarkaFusedDump::loopDense0"
flags='set Test/javaOptions ++= Seq("-XX:+UnlockDiagnosticVMOptions",'
flags+=" \"-XX:CompileCommand=quiet\", \"$print_cmd\")"
LD_LIBRARY_PATH="${VARKA_HSDIS_DIR:-}${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
build/sbt -batch "project catalyst" "$flags" \
  "Test/runMain $main$quoted --rounds 50000" > "$log" 2>&1 || true
# The tool's own report lines only; the disassembly HotSpot printed in between stays in the log.
sed -E 's/^\[(info|error)\] ?//' "$log" \
  | grep -E '^(entry |inputs |literals |output [0-9]+ IR|shape hash|method +bytes|[A-Za-z0-9_]+ +[0-9]+ +[0-9]+ +[0-9]+ +[0-9]+$|line map|  [0-9]+=|ran [0-9]+ rounds|\(--rounds)'
echo
echo "== mnemonics in the standard C2 compilation of loopDense0 (full disassembly: $log) =="
sed -E 's/^\[info\] ?//' "$log" | awk '
  /^Compiled method \(c2\)/ { on = ($0 !~ /%/) }
  /^Compiled method \(c1\)/ { on = 0 }
  on && /^ *0x[0-9a-f]+: +[a-z]/ {
    split($0, f, /[ \t]+/)
    for (i = 1; i <= length(f); i++) if (f[i] ~ /^[a-z][a-z0-9.]*$/) { m[f[i]]++; break }
  }
  END { for (k in m) printf "%6d %s\n", m[k], k }' | sort -rn | head -25
