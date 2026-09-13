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
# Is the machine in the state the committed benchmark files were measured in?
# Runs dev/varka_canary/Canary.java (about 35 seconds) and compares its three
# rates - a compute-bound control, a cache-resident vector add, and a
# memory-bound one - against dev/varka_canary/baseline-<host>.txt.
#
#   dev/varka_bench_canary.sh            # compare; exit 1 if the machine is off
#   dev/varka_bench_canary.sh --record   # write this host's baseline (commit it)
#
# The compute control is allowed 3% either way and the two vector loops 10%;
# outside that the exit status is 1 and dev/varka_bench_regen.sh, which runs
# this first, stops unless told --force. The thresholds are where today's
# known-good runs sit against each other; a memory-bound gap of 20-27% with a
# flat compute control is the shape that has been seen between days on this
# machine, and it is exactly what this refuses to let into a results file
# unnoticed.
set -euo pipefail
# Usage text is found rather than numbered: a hard-coded range silently truncates as the
# comment above it grows, which had already happened to four of these scripts. Ends at the
# first line that is not a comment.
usage() { sed -n '17,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }
case "${1:-}" in -h|--help) usage 0 ;; esac

here="$(cd "$(dirname "$0")" && pwd)"
host="$(hostname -s)"
baseline="$here/varka_canary/baseline-$host.txt"
record=0
[ "${1:-}" = "--record" ] && record=1

out="$(java --add-modules jdk.incubator.vector "$here/varka_canary/Canary.java" 2>/dev/null \
  | grep -E '^(preferred_bits|compute|cache|memory)=')"
if [ "$record" -eq 1 ]; then
  {
    echo "# Canary baseline for $host, $(date -u +%Y-%m-%dT%H:%M:%SZ), $(uname -r),"
    echo "# $(grep -m1 'model name' /proc/cpuinfo | sed 's/.*: //'),"
    echo "# governor=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo n/a)."
    echo "# Rates in M elements/s, best of five 2 s windows. Re-record only on a day whose"
    echo "# benchmark regenerations you are prepared to call the new normal."
    echo "$out"
  } > "$baseline"
  echo "recorded $baseline:"; cat "$baseline"
  exit 0
fi
[ -f "$baseline" ] || { echo "no baseline for $host: run $0 --record on a known-good day" >&2; exit 2; }

bad=0
printf '%-10s %10s %10s %8s  %s\n' loop baseline today change verdict
for key in compute cache memory; do
  base="$(grep "^$key=" "$baseline" | cut -d= -f2)"
  now="$(echo "$out" | grep "^$key=" | cut -d= -f2)"
  limit=10; [ "$key" = compute ] && limit=3
  verdict="$(awk -v b="$base" -v n="$now" -v l="$limit" 'BEGIN {
    c = (n / b - 1) * 100; v = (c > l || c < -l) ? "OFF" : "ok";
    printf "%+.1f%% %s", c, v }')"
  printf '%-10s %10s %10s %s\n' "$key" "$base" "$now" "$verdict"
  case "$verdict" in *OFF) bad=1 ;; esac
done
if [ "$bad" -eq 1 ]; then
  echo "the machine is not in the baseline's state; a regeneration now measures the day, not the code" >&2
fi
exit "$bad"
