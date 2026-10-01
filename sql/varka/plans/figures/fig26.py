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

"""Figure 26: one query's journey through Varka - plan time on the left, once per shape: the
columnar rule, the compiler that translates each entry to the IR or declines it with a reason,
the IR whose literals are slots, the emitter that builds and measures the class, and the shape
cache every task shares. Run time on the right, once per batch: Arrow columns mapped as memory
segments, the kernel's dense or masked body and its epilogue, the warm-up thread that compiles a
new kernel while batches take Spark's row path, and the trapdoor under a refused batch. The
drawing of docs/sql-varka.md's "one query's journey". No measured value is drawn."""

from rough import Rough, finish

GREY = "#5c5f66"
RED = "#e03131"

r = Rough(1000, 860, seed=2601, roughness=1.3, sketchy=True, fill_style="outline")
r.text(40, 44, "One query's journey through Varka", size=32)

LW, LX = 290, 40  # plan time, left column
RW, RX = 250, 540  # run time, right column
H = 84


def column(x, w, top, boxes, step=110):
    """Boxes down a column, each with a head and a body, arrows between them."""
    ys = []
    for i, (head, body, fill) in enumerate(boxes):
        y = top + i * step
        r.rect(x, y, w, H, fill=fill)
        r.text(x + w / 2, y + 22, head, size=20, anchor="middle")
        r.text(x + w / 2, y + 54, body, size=15, anchor="middle")
        if i:
            r.arrow(x + w / 2, y - step + H + 4, x + w / 2, y - 4, width=2.0)
        ys.append(y)
    return ys


r.text(LX, 100, "plan time, once per shape", size=22)
left = column(
    LX,
    LW,
    120,
    [
        ("the physical plan", "a projection or a filter\nover Arrow batches", "grey"),
        ("VarkaColumnarRule", "is this node eligible?", "violet"),
        ("the compiler", "each entry to the IR,\nor declined with a reason", "violet"),
        (
            "the IR",
            "trees over lanes; a literal is a slot,\nso one shape serves many queries",
            "blue",
        ),
        ("the emitter", "bytecode, measured;\nsplit or decline", "green"),
        ("the shape cache", "one class per shape,\nshared by every task", "green"),
    ],
)
# A declined entry leaves at the compiler and stays Spark's.
y = left[2] + H / 2
r.arrow(LX + LW + 4, y, LX + LW + 44, y, color=RED, width=1.8)
r.text(LX + LW + 50, y, "declined: stays Spark's,\nthe reason in EXPLAIN", size=14, color=RED)

r.text(RX, 100, "run time, once per batch", size=22)
right = column(
    RX,
    RW,
    120,
    [
        ("an Arrow batch", "columns, not rows", "blue"),
        ("morsels", "data and validity buffers as\nmemory segments, zero-copy", "blue"),
        ("the kernel", "the dense or the masked body,\nthen the epilogue for the tail", "green"),
        ("output columns", "back to Spark, columnar or\nconverted at the boundary", "green"),
    ],
)
# The warm-up thread, beside the kernel.
ky = right[2]
SX, SW = RX + RW + 24, 170
r.rect(SX, ky - 44, SW, H + 72, fill="orange")
r.text(SX + SW / 2, ky - 22, "the warm-up", size=18, anchor="middle")
r.text(
    SX + SW / 2,
    ky + 44,
    "a new shape's kernel\nis compiled in the\nbackground; its batches\n"
    "take Spark's row path\nmeanwhile",
    size=13,
    anchor="middle",
)
r.arrow(SX - 4, ky + H / 2, RX + RW + 4, ky + H / 2, color="#e8590c", width=1.8)
# The trapdoor under the kernel, and where it leads.
r.line(RX + 40, ky + H + 2, RX + RW - 40, ky + H + 2, width=3.5, color=RED)
ty = right[3]
r.rect(SX, ty, SW, H, fill="red")
r.text(SX + SW / 2, ty + 22, "the trapdoor", size=18, anchor="middle")
r.text(
    SX + SW / 2,
    ty + 58,
    "a refused batch is\nrecomputed by\nthe row engine",
    size=13,
    anchor="middle",
)
r.arrow(RX + RW - 30, ky + H + 8, SX - 4, ty + H / 2, color=RED, width=1.8)

# Plan time hands run time its class.
r.arrow(LX + LW + 4, left[5] + H / 2, RX - 4, right[2] + H / 2, width=2.0, dash="7 6")
r.text(430, left[5] + H / 2 - 14, "the class", size=16, color=GREY)

r.note(
    40,
    790,
    "every arrow marked declined or refused leads somewhere correct: "
    "the compiler can decline an entry,\n"
    "the emitter a shape, the kernel a batch, and each falls back to Spark at that granularity",
    size=17,
)

finish(r, "fig26-one-querys-journey")
