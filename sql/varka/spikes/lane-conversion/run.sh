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
#
# run.sh: every form of src/Hand28.java at 4096 and 16384 rows, at the host's vector width and
# under -XX:MaxVectorSize=16, with the host, the JDK and the load before the run in a header. Run
# it on an idle machine; results/hand-loop.txt is its output.
set -u
D=$(cd "$(dirname "$0")" && pwd)
echo "# $(date -u +%FT%TZ), commit $(git -C "$D" rev-parse --short HEAD)"
echo "# $(java -version 2>&1 | sed -n 2p)"
echo "# $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | sed 's/^ //')"
echo "# load before: $(cat /proc/loadavg)"
for width in "" "-XX:MaxVectorSize=16"; do
  for n in 4096 16384; do
    for form in longs masked unmasked pair bounded; do
      printf '%-22s n=%-6s ' "${width:-host width}" "$n"
      java --add-modules jdk.incubator.vector $width -Dn="$n" "$D/src/Hand28.java" "$form" \
        2>/dev/null
    done
  done
done
echo "# load after: $(cat /proc/loadavg)"
