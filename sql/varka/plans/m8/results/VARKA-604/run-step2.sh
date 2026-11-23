#!/bin/bash
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
# Row 604's step-2 probe (VARKA-604.md 9.2): three JVMs a mode at two long lanes, no compile
# commands. CP is a file holding catalyst's test classpath (build/sbt 'export
# catalyst/Test/fullClasspath', its last line); OFF=1 switches the template warm-up off.
# BOTH=1 runs the modes that serve both operations in one JVM.
here="$(cd "$(dirname "$0")" && pwd)" || exit 1
out="${P604_OUT:-$HOME/p604-step2}"
mkdir -p "$out" || exit 1
javac --add-modules jdk.incubator.vector -implicit:none -cp "$(cat "$CP")" -d "$out" \
  "$here/Probe604b.java" || exit 1
export SPARK_LOCAL_IP=127.0.0.1
off=""; [ "${OFF:-0}" = 1 ] && off="-Dvarka.warmup.vectorTemplates=false"
run() {
  java --add-modules jdk.incubator.vector -Xmx1g $off -XX:UseAVX=2 -XX:MaxVectorSize=16 \
    -cp "$out:$(cat "$CP")" org.apache.spark.sql.catalyst.expressions.codegen.varka.Probe604b \
    "$1" "$2" 2>&1 | grep -v -i 'warning\|incubat\|SLF4J\|log4j'
}
specs=("alone rem" "mixed rem" "warm rem" "warm-mixed rem" "mixed-late rem" "mixed pmod"
  "warm-mixed pmod")
[ -n "$off" ] && specs=("alone rem" "mixed rem" "mixed pmod")
[ "${BOTH:-0}" = 1 ] && specs=("mixed both" "warm-mixed both")
for spec in "${specs[@]}"; do
  read -r mode op <<< "$spec"
  for i in 1 2 3; do run "$mode" "$op" & done; wait
done
