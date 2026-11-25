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
#                results/forms.txt is its output. Run it on an idle machine: about nine minutes.
# JAVA_HOME names the JDK; the plan's numbers are JDK 25's.
set -u
D=$(cd "$(dirname "$0")" && pwd)
java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
echo "# $(date -u +%FT%TZ), commit $(git -C "$D" rev-parse --short HEAD)"
echo "# $("$java" -version 2>&1 | sed -n 2p)"
echo "# $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | sed 's/^ //')"
echo "# load before: $(cat /proc/loadavg)"
case "${1:-}" in
  facts)
    "$java" "$D/src/ZoneFacts.java"
    ;;
  forms)
    for flags in "" "-XX:UseAVX=2" "-XX:UseAVX=2 -XX:MaxVectorSize=16"; do
      echo "# flags: ${flags:-none}"
      # shellcheck disable=SC2086
      "$java" --add-modules jdk.incubator.vector $flags "$D/src/ZoneForms.java" 2>/dev/null
    done
    ;;
  *)
    echo "usage: run.sh facts|forms" >&2
    exit 2
    ;;
esac
echo "# load after: $(cat /proc/loadavg)"
