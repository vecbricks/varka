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
# Row 604's admission probe runner (VARKA-604.md 2): three JVMs a mode, side by side.
# W is MaxVectorSize in bytes, X extra JVM flags (-Dp604.block=<rows>), MODES the modes.
cd "$(dirname "$0")" || exit 1
export SPARK_LOCAL_IP=127.0.0.1
C=org.apache.spark.sql.VarkaDecimalRemainderSpikeBenchmark; P=org.apache.spark.sql.Probe604
run() {
  java --add-modules jdk.incubator.vector -Xmx1g -XX:UseAVX=2 -XX:MaxVectorSize="$2" $3 \
    -XX:CompileCommand=quiet "-XX:CompileCommand=dontinline,$C::loop*" \
    "-XX:CompileCommand=inline,$C::body*" "-XX:CompileCommand=dontinline,$P::tIn" \
    "-XX:CompileCommand=dontinline,$P::tOut" "-XX:CompileCommand=dontinline,$P::add*" \
    -cp out $P "$1" 2>&1 | grep -v -i 'warning\|incubat'
}
W=${W:-16}; X=${X:-}
for m in ${MODES:-alone adds warm warm-adds}; do
  for i in 1 2 3; do run "$m" "$W" "$X" & done; wait
done
