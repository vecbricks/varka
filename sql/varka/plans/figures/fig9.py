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

"""Figure 9: where the full-width numbers come from - a GitHub-hosted runner that the measuring
job itself proves has a 512-bit datapath, found by dispatching until the pool hands one over."""

from rough import Rough, finish

r = Rough(900, 780, seed=71)
r.text(40, 40, "the runner pool, as eighteen dispatches saw it", size=24)
kinds = ["zen3"] * 8 + ["zen4"] * 3 + ["xeon"] * 6 + ["zen5"]
fills = {"zen3": "grey", "zen4": "grey", "xeon": "yellow", "zen5": "green"}
for i, k in enumerate(kinds):
    x, y = 40 + (i % 9) * 92, 70 + (i // 9) * 60
    r.rect(
        x,
        y,
        80,
        48,
        fill=fills[k],
        # The census's own readings (VARKA-62.md 11.9), not the later measuring run's:
        # Zen 3 read 1.00, the three Zen 4s 0.91 to 1.00, the Xeons 1.33 to 1.36, and the one
        # Zen 5 1.99. The committed results file below carries 2.01 because that is a
        # different run on a different day.
        label={"zen3": "1.00", "zen4": "1.00", "xeon": "1.35", "zen5": "1.99"}[k],
        size=18,
    )
r.text(
    40,
    210,
    "the number in each box is the datapath probe's 512:256 ratio; only one machine reads 2",
    size=17,
    color="#5c5f66",
)
r.note(560, 250, "AMD EPYC 9V45 (Zen 5):\none dispatch in about eighteen", size=19)

# The workflow.
r.text(40, 320, "one dispatch of varka-surface-benchmark.yml", size=24)
r.rect(40, 350, 240, 140, fill="grey")
r.text(160, 378, "build", size=22, anchor="middle")
r.text(160, 430, "any runner; the jars are\ncached by commit", size=16, anchor="middle")
r.arrow(285, 420, 325, 420)
r.rect(330, 350, 530, 140, fill="blue")
r.text(595, 378, "measure, on a fresh VM", size=22, anchor="middle")
r.text(
    595,
    412,
    "first, the probe on this VM: below 1.5, stop in a minute;\n"
    "at 2, the four arms, about 74 minutes, and the file\n"
    "records the reading the same VM produced",
    size=15,
    anchor="middle",
)
r.arrow(595, 495, 595, 525)
r.rect(430, 530, 330, 90, fill="green")
r.text(595, 555, "DateChain-varka-jdk25-results.txt", size=18, anchor="middle")
r.text(595, 585, "cpu: AMD EPYC 9V45   datapath: ratio 2.01", size=16, anchor="middle")
r.arrow(400, 495, 340, 560, color="#e03131")
r.text(330, 570, "refused:\nwrong machine", size=17, anchor="end", color="#e03131")
r.note(
    40,
    650,
    "a miss costs a minute, a hit costs an hour - so dispatch until the pool\n"
    "hands over the machine, and let the file prove which one it was",
    size=18,
)

finish(r, "fig9-the-full-width-runner")
