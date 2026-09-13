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
# How far apart do two runs of the same benchmark land, with nothing changed?
#
#   dev/varka_bench_repeat.sh catalyst VarkaEmitterParityBenchmark 3
#   dev/varka_bench_repeat.sh catalyst VarkaEmitterParityBenchmark 10 --narrow
#   dev/varka_bench_repeat.sh core VarkaThroughputBenchmark 4 --band <file>
#
# Runs the benchmark N times, pinned exactly as dev/varka_bench_regen.sh pins it,
# writes nothing to the committed results files, and hands the runs to
# dev/varka_bench_band.py, which reports the per-case spread: the median, the
# p90, how many cases exceed 3, 10 and 20 percent, and the worst few. With
# --band it also writes the committed band file the diff reads.
#
# --narrow runs under -XX:MaxVectorSize=16, the width the committed 128-bit
# companion files are measured at. The two widths need separate bands and are
# not interchangeable: over ten runs each, the narrow file's median spread is
# 1.72% against the wide file's 5.34%, so one band across both would be far too
# loose for the narrow file - which is where every collapse this milestone has
# recorded actually showed up.
#
# It exists because a diff between two regenerations is not evidence of a change
# until you know what an unchanged file does. On this machine it does more than
# anyone expected: pinned, the parity file's median case moves 1.6% between two
# runs, 73 of 211 cases move more than 3%, and 22 move more than 10%, with the
# worst near 26%. Unpinned the worst reaches 75%. That is not thermal drift and
# not the clock - the frequency is constant to 1.2% across runs while throughput
# moves 31% - it is a per-fork JIT and code-layout lottery, which `-Xbatch`
# narrows from 49% to 14% and does not remove.
#
# What follows from it, and the reason this script is committed rather than run
# once: an A/B whose two arms sit in the same run is sound, because they share a
# JVM, a layout and a clock, and that is how every A/B in this project is built.
# A number compared against a *previous run* is not sound below the band this
# script measures. Before reading a regeneration's diff as a regression, run
# this and compare the diff against the band.
set -uo pipefail
# Usage text is found rather than numbered: a hard-coded range silently truncates as the
# comment above it grows, which had already happened to four of these scripts. Ends at the
# first line that is not a comment.
usage() { sed -n '17,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }

root="$(git rev-parse --show-toplevel)"; cd "$root"
case "${1:-}" in -h|--help) usage 0 ;; esac
[ "$#" -ge 2 ] || { usage; }
module="$1"; klass="$2"; runs="${3:-3}"; shift 3 2>/dev/null || shift $#
narrow=0; band=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --narrow) narrow=1; shift ;;
    --band) band="$2"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

case "$klass" in
  *.*) fqcn="$klass"; klass="${klass##*.}" ;;
  *)
    case "$module" in catalyst) src="sql/catalyst/src/test" ;; *) src="sql/core/src/test" ;; esac
    file="$(find "$src" -name "$klass.scala" | head -1)"
    [ -n "$file" ] || { echo "no $klass.scala under $src" >&2; exit 2; }
    fqcn="$(sed -n 's/^package \(.*\)$/\1/p' "$file" | head -1).$klass" ;;
esac

# The same fast-CCX pin dev/varka_bench_regen.sh uses, for the same reasons.
maxf=0
for d in /sys/devices/system/cpu/cpu[0-9]*/cpufreq; do
  f=$(cat "$d/cpuinfo_max_freq" 2>/dev/null || echo 0)
  [ "$f" -gt "$maxf" ] && maxf=$f
done
fast=""
for d in /sys/devices/system/cpu/cpu[0-9]*/cpufreq; do
  c=$(basename "$(dirname "$d")"); c=${c#cpu}
  f=$(cat "$d/cpuinfo_max_freq" 2>/dev/null || echo 0)
  [ "$f" = "$maxf" ] && fast="${fast:+$fast,}$c"
done
if [ -n "$fast" ] && command -v taskset > /dev/null; then
  runner=(taskset -c "$fast"); echo "pinned to cpus $fast"
else
  runner=(); echo "not pinned"
fi

out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
for i in $(seq 1 "$runs"); do
  echo "== run $i of $runs${narrow:+ }$([ "$narrow" -eq 1 ] && echo '(128-bit)')"
  if [ "$narrow" -eq 1 ]; then
    "${runner[@]}" build/sbt -batch "project $module" \
      'set Test/javaOptions += "-XX:MaxVectorSize=16"' "Test/runMain $fqcn" \
      > "$out/run$i" 2>&1 || { echo "run $i failed:"; tail -5 "$out/run$i"; exit 1; }
  else
    "${runner[@]}" build/sbt -batch "$module/Test/runMain $fqcn" > "$out/run$i" 2>&1 \
      || { echo "run $i failed:"; tail -5 "$out/run$i"; exit 1; }
  fi
  # A run with no result rows is a broken invocation, not a quiet one; catching it
  # here keeps a long series from being analysed as if it had data.
  rows=$(grep -cE '^(\[info\] )?\S.*[0-9]+\s+[0-9.]+\s+[0-9.]+\s+[0-9.]+X\s*$' "$out/run$i")
  [ "$rows" -gt 0 ] || { echo "run $i produced no result rows:"; tail -5 "$out/run$i"; exit 1; }
  echo "   $rows rows"
done

files=""
for i in $(seq 1 "$runs"); do files="$files $out/run$i"; done
# shellcheck disable=SC2086
dev/varka_bench_band.py ${band:+--write "$band"} $files
