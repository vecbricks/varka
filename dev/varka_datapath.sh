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
# Describe the machine this is running on, measure its vector datapath, and exit
# non-zero if it is not the machine the caller asked for.
#
#   dev/varka_datapath.sh [--require 512|any] [--expected-cpu MODEL] [--github]
#
# This exists as a script rather than as steps in a workflow because it has to run in
# *two* jobs, and PLAN_TASK_62.md 11.15 is the reason. A gate in one GitHub job says
# nothing about the machine another job gets: every job is a fresh ephemeral VM. The
# 12 September 2026 chains run proved it the expensive way - the gate passed on an
# EPYC 9V45 reading 2.00 while the measurement ran on an EPYC 9V74 reading 1.00, and
# GitHub displayed the job under the 9V45's name because the name interpolated the
# gate's output. So the job that measures has to probe its own machine, and the only
# way to keep one gate rather than two divergent ones is to keep it in one file.
#
# --require 512 fails unless the 512:256 ratio is at least 1.50; --require any records
# the numbers and never fails on them. The probe's own 256:128 positive control is
# enforced either way, because a reading it cannot explain is not evidence for or
# against anything.
#
# --github appends cpu/lanes/control/ratio to $GITHUB_OUTPUT. Without it the script
# just prints, which is what makes it runnable on a laptop to see what this machine is.

set -uo pipefail

# Usage text is found rather than numbered: a hard-coded range silently truncates as the
# comment above it grows, which had already happened to four of these scripts. Ends at the
# first line that is not a comment.
usage() { sed -n '17,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }

require="any"
expected_cpu=""
github=0

while [ $# -gt 0 ]; do
  case "$1" in
    --require) require="$2"; shift 2 ;;
    --expected-cpu) expected_cpu="$2"; shift 2 ;;
    --github) github=1; shift ;;
    -h|--help) usage 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

case "$require" in
  512|any) ;;
  *) echo "--require takes 512 or any, not '$require'" >&2; exit 2 ;;
esac

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# GitHub's ::error:: and ::notice:: annotations are inert outside Actions, where they
# print as plain text, so the same output serves both callers.
note() { echo "::notice::$*"; }
fail() { echo "::error::$*"; exit 1; }

cpu_model=$(grep "model name" /proc/cpuinfo | head -1 | sed 's/model name\s*:\s*//')
note "Runner CPU: $cpu_model"
echo "cores: $(nproc) | memory: $(free -g | awk '/^Mem:/{print $2}') GiB"
echo "AVX/SVE flags: $(lscpu | tr ' ' '\n' | grep -E '^avx|^sve' | sort -u | tr '\n' ' ')"
# Are the cores the same? The development laptop is not - four Zen 5 at 5158 MHz and
# eight Zen 5c at 3289 MHz, in two L3 clusters - which is why the regen scripts pin to
# the fast CCX. A measurement spread over two core types is two measurements averaged,
# so a runner that is heterogeneous has to be pinned too, and this is how we find out.
lscpu --extended || true
echo "distinct core max frequencies: $(lscpu --extended=MAXMHZ 2>/dev/null | tail -n +2 | sort -u | tr '\n' ' ')"
echo "distinct core types: $(lscpu --extended=CORE,MAXMHZ 2>/dev/null | tail -n +2 | awk '{print $2}' | sort -u | wc -l)"
java -XX:+PrintFlagsFinal -version 2>/dev/null | grep -E ' (MaxVectorSize|UseAVX|UseSVE) ' || true

if [ -n "$expected_cpu" ] && ! echo "$cpu_model" | grep -qF "$expected_cpu"; then
  fail "CPU mismatch! Expected '$expected_cpu' but got '$cpu_model'"
fi

# The datapath probe: dev/varka_canary/Datapath.java, the same one
# dev/varka_bench_surface.sh runs and records in every results file's provenance. It
# reports *lanes* per nanosecond from an ALU-bound register-resident loop, so the ratio
# between MaxVectorSize=32 and =64 is about 2 on a full-width unit and about 1 on a
# double-pumped one. A third reading, 16 against 32, is taken as the probe's own
# positive control: it must show a real doubling on any machine with a 256-bit
# datapath, and if it does not then the probe is not measuring width here and no
# verdict from it should be believed.
probe() {
  java --add-modules jdk.incubator.vector -XX:+IgnoreUnrecognizedVMOptions \
    "-XX:MaxVectorSize=$1" "$here/dev/varka_canary/Datapath.java" 2>/dev/null \
    | sed -n 's/^lane_ops_per_ns=//p' | cut -d' ' -f1
}
c16="$(probe 16)"; c32="$(probe 32)"; c64="$(probe 64)"
if [ -z "$c32" ] || [ -z "$c64" ] || ! awk -v a="$c32" 'BEGIN { exit !(a > 0) }'; then
  fail "the datapath probe produced no rate (c32='$c32' c64='$c64')"
fi
ratio="$(awk -v a="$c32" -v b="$c64" 'BEGIN { printf "%.2f", b / a }')"
control="$(awk -v a="$c16" -v b="$c32" 'BEGIN { printf "%.2f", (a > 0) ? b / a : 0 }')"
note "datapath probe: $c16 / $c32 / $c64 lane-ops/ns at 128 / 256 / 512 bits; 256:128 = $control (control), 512:256 = $ratio"

if ! awk -v c="$control" 'BEGIN { exit !(c >= 1.50) }'; then
  fail "the probe's own control is $control - it cannot see a width doubling on this machine, so its 512-bit verdict means nothing"
fi
# 1.50 sits clear of both outcomes the probe distinguishes: a double-pumped unit reads
# 1.00 on the laptop this project measures on, a full-width one is nominally 2x. The
# eighteen-runner survey of 11 September put Intel's server Xeons at 1.33 to 1.36 -
# port-limited rather than double-pumped - and Zen 5 at 1.99, so the threshold has room
# on both sides by measurement. A value between them is a machine this gate should not
# guess about, so it fails rather than proceeding under either reading.
if [ "$require" = "512" ] && ! awk -v r="$ratio" 'BEGIN { exit !(r >= 1.50) }'; then
  fail "datapath ratio $ratio is not a full-width 512-bit unit; re-dispatch"
fi

if [ "$github" = 1 ] && [ -n "${GITHUB_OUTPUT:-}" ]; then
  {
    echo "cpu=$cpu_model"
    echo "lanes=$c16 / $c32 / $c64"
    echo "control=$control"
    echo "ratio=$ratio"
  } >> "$GITHUB_OUTPUT"
fi
