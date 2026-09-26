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
"""Figure 19: is my query on the cliff? A decision chart from the symptom - a query that got
several times slower when it grew - through the three checks the post describes, to what each
answer means and which section says what to do. It carries no measurement."""

from rough import Rough, finish

GREY = "#5c5f66"

r = Rough(960, 860, seed=191)
r.text(40, 44, "Is my query on the cliff?", size=34)

QX, QW = 40, 520
AX, AW = 640, 290


def question(y, text):
    r.rect(QX, y, QW, 96, fill="blue")
    r.text(QX + QW / 2, y + 48, text, size=22, anchor="middle")


def answer(y, text, fill):
    r.rect(AX, y - 6, AW, 108, fill=fill)
    r.text(AX + AW / 2, y + 48, text, size=20, anchor="middle")
    r.arrow(QX + QW, y + 48, AX - 6, y + 48, width=2.0)
    r.text((QX + QW + AX) / 2, y + 30, "yes", size=20, anchor="middle")


def down(y1, y2):
    r.arrow(QX + QW / 2, y1, QX + QW / 2, y2 - 6, width=2.0)
    r.text(QX + QW / 2 + 16, (y1 + y2) / 2, "no", size=20)


r.rect(QX + 60, 90, QW - 120, 76, fill="yellow")
r.text(QX + QW / 2, 128, "a query got several times\nslower when it grew", size=22, anchor="middle")
r.arrow(QX + QW / 2, 166, QX + QW / 2, 204, width=2.0)

question(210, 'after a run, explain("codegen"): is\nmaxMethodCodeSize above 8000?')
answer(210, "the 8000-byte cliff:\nset hugeMethodLimit\nto 8000 (section 4)", "green")
down(306, 366)
question(370, 'does the log say "Whole-stage codegen\ndisabled" or "Expr codegen error"?')
answer(370, "past 64 KB, most\nlikely a large\nCASE WHEN (section 5)", "orange")
down(466, 526)
question(530, "is it reading a cached table\nof more than 100 columns?")
answer(530, "read row by row:\nraise maxFields,\nwith care (section 5)", "orange")
down(626, 686)
r.rect(QX + 60, 690, QW - 120, 76, fill="grey")
r.text(QX + QW / 2, 728, "not the cliff: something\nelse made it slower", size=22, anchor="middle")

r.text(
    40,
    818,
    "each check is a command in section 3 or 5; the settings' full names are in sections 4 and 5",
    size=18,
    color=GREY,
)

finish(r, "fig19-is-my-query-on-the-cliff")
