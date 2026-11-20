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
# Builds row 604's admission probe (../VARKA-604-admission.txt) into $P604_OUT/out (default
# ~/p604-build): the spike benchmark and the template warm-up copied from this checkout, the
# spike's runBenchmarkSuite body removed, beside the stubs here. run.sh then runs it.
here="$(cd "$(dirname "$0")" && pwd)" || exit 1
root="$(cd "$here/../../../../../.." && pwd)" || exit 1
out="${P604_OUT:-$HOME/p604-build}"
src="$out/src"
mkdir -p "$src/org/apache/spark/sql/catalyst/expressions/codegen/varka" "$out/out" || exit 1
cp -r "$here/stubs/." "$src/" || exit 1
cp "$here/Probe604.java" "$src/org/apache/spark/sql/" || exit 1
pkg=org/apache/spark/sql/catalyst/expressions/codegen/varka
cp "$root/sql/catalyst/src/main/java/$pkg/VarkaVectorTemplateWarmup.java" "$src/$pkg/" || exit 1
spike="$src/org/apache/spark/sql/VarkaDecimalRemainderSpikeBenchmark.java"
tests="$root/sql/catalyst/src/test/java"
cp "$tests/org/apache/spark/sql/VarkaDecimalRemainderSpikeBenchmark.java" "$spike" || exit 1
python3 - "$spike" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
i = s.index("  @Override\n  public void runBenchmarkSuite")
j = s.index("  /** The bytes this thread allocates a row")
s = s[:i] + "  @Override\n  public void runBenchmarkSuite(String[] a) {}\n\n" + s[j:]
open(p, "w").write(s)
PY
cd "$src" || exit 1
javac --add-modules jdk.incubator.vector -d "$out/out" -sourcepath . \
  org/apache/spark/sql/Probe604.java org/apache/spark/sql/VarkaDecimalRemainderSpikeBenchmark.java
