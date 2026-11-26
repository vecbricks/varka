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
# run.sh facts   src/ZoneFacts.java over every zone of the JDK; results/facts.txt is its output.
# run.sh forms   src/ZoneForms.java at the host's vector width, with AVX2 at 256 bits (the
#                runners' AMD EPYC 7763 has no AVX-512) and with AVX2 at 128 bits;
#                results/forms.txt is its output. Run it on an idle x86 machine: about eleven
#                minutes.
# run.sh kernels src/ZoneKernels.java at the same three widths: a whole field over a zoned
#                instant and a local time back to an instant; results/kernels.txt is its
#                output, and results/kernels-2.txt a second run, since one of its comparisons
#                is a ratio near 1. About six minutes on an idle x86 machine.
# run.sh tables  src/ZoneTables.java at the same three widths: the packed bucket table and the
#                ways to hand a gather its indices; results/tables.txt is its output. About
#                sixteen minutes on an idle x86 machine.
# run.sh resolve src/ZoneResolve.java over every zone of the JDK: one pair of local thresholds a
#                change against java.time's three rules for a local time; results/resolve.txt
#                is its output. Under a minute.
# JAVA_HOME names the JDK; the plan's numbers are JDK 25's. The script fails when a run fails
# or a probe's check does: a results file from a failed run is not a result.
set -u
D=$(cd "$(dirname "$0")" && pwd)
java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
echo "# $(date -u +%FT%TZ), commit $(git -C "$D" rev-parse --short HEAD)"
echo "# $("$java" -version 2>&1 | sed -n 2p)"
echo "# $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | sed 's/^ //')"
echo "# load before: $(cat /proc/loadavg)"
# Runs a probe; of its stderr only the incubator module's warning is dropped.
probe() {
  "$java" "$@" 2> >(grep -v 'incubator' >&2) || { echo "FAILED: java $*" >&2; exit 1; }
}
case "${1:-}" in
  facts)
    probe "$D/src/ZoneFacts.java"
    ;;
  forms)
    for flags in "" "-XX:UseAVX=2" "-XX:UseAVX=2 -XX:MaxVectorSize=16"; do
      echo "# flags: ${flags:-none}"
      # shellcheck disable=SC2086
      probe --add-modules jdk.incubator.vector $flags "$D/src/ZoneForms.java"
    done
    ;;
  kernels)
    for flags in "" "-XX:UseAVX=2" "-XX:UseAVX=2 -XX:MaxVectorSize=16"; do
      echo "# flags: ${flags:-none}"
      # shellcheck disable=SC2086
      probe --add-modules jdk.incubator.vector $flags "$D/src/ZoneKernels.java"
    done
    ;;
  tables)
    for flags in "" "-XX:UseAVX=2" "-XX:UseAVX=2 -XX:MaxVectorSize=16"; do
      echo "# flags: ${flags:-none}"
      # shellcheck disable=SC2086
      probe --add-modules jdk.incubator.vector $flags "$D/src/ZoneTables.java"
    done
    ;;
  resolve)
    probe "$D/src/ZoneResolve.java"
    ;;
  *)
    echo "usage: run.sh facts|forms|kernels|tables|resolve" >&2
    exit 2
    ;;
esac
echo "# load after: $(cat /proc/loadavg)"
