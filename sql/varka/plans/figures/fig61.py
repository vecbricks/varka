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

"""Figure 61: an integer passed to the class - figure 39's bars for an integer written into the
source against one passed to the class as a reference, the change Spark already makes for
strings. It reads the reference block of this repository's benchmark results
(sql/core/benchmarks/CodegenRecompileBenchmark-jdk25-results.txt), since the stand-in expression
it needs is test code and exists only there; its build and CPU differ from figure 39's."""

from fig39 import FORK, cpu, draw, means, short

fork = means(FORK)
assert len(fork) == 2, len(fork)
# The first block is the 4.2.0 run's arms on this build; the figure shows the second.
draw(
    "fig61-an-integer-passed-to-the-class",
    6101,
    "An integer passed to the class",
    "SELECT count(*) FROM t WHERE i % 1000000 = <value> over 100,000 cached rows, mean of 40 "
    "queries, ms; local mode, JDK 25, GitHub runner",
    "vecbricks/varka's Spark (master-based, Varka off), %s" % short(cpu(FORK)),
    fork[1],
)
