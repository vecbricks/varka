#!/usr/bin/env python3
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
"""
The verdict of VARKA-347's measurement (VARKA-347.md 11.8), computed from the two results files
of VarkaDecimalLoadBenchmark on the AMD EPYC 7763, 256 bits and 128 bits (MaxVectorSize=16).

    python3 load_verdict.py <256-bit results> <128-bit results> [<epyc census> <arm census>]
    python3 load_verdict.py --selftest

Every number is a rate, Rate(M/s), the column dev/varka_bench_diff.py reads; a ratio above 1 means
the numerator is faster. The rules, in the plan's words:

  DEFAULT   a form F other than REARRANGE becomes decimalLoad's default (and the shuffle-free
            flag's) only if, at 256 bits, F's load-only rate is at least 1.3 times REARRANGE's
            at the L2 and the L3 rungs, and F's 4-add rate is at least REARRANGE's at all three
            rungs, and no census cell of F reads F(allback) on a runner where REARRANGE's reads
            I(ntrinsic). Of several such forms the one with the highest L3 load-only rate wins.
  CACHE     the cache layout (question 1 of section 8) is due as a row of its own if, at both
            widths, the BIGINT load-only rate is at least 1.5 times the best sixteen-byte form's
            past L3, and at least 1.0 times it in L2.
  INT       the int arm's ratio to the best long form, per rung and chain, is printed; it decides
            nothing in this row (VARKA-347.md 11.3 decision 4).

The last line is "VERDICT: default=<form> cache=<due|not due> ..." and is what the plan quotes.
"""

import json
import re
import sys

ROW = re.compile(r"^(.*?)\s+(\d+)\s+(\d+)\s+(\d+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)X\s*$")
HEADER = re.compile(r"^the decimal load over (\d+) rows - (.*?):\s+Best Time\(ms\)")
RUNGS = {"every arm in L2": "L2", "every arm in L3": "L3", "every arm past L3": "past L3"}
FORMS = [
    "REARRANGE",
    "COMPRESS",
    "SELECT_FROM",
    "REARRANGE with the shuffle-free check",
    "COMPRESS with the shuffle-free check",
    "SELECT_FROM with the shuffle-free check",
]
CHAINS = {"load only": 0, "1 add": 1, "4 adds": 4, "16 adds": 16}
BIGINT = "BIGINT column (the cache layout's kernel)"
INT_ARM = "DECIMAL slots on the int lane (the int arm)"

# The census cells each form's kernel calls (VARKA-347.md 10.1), the check cells first.
CELLS = {
    "REARRANGE": ["long.dec_check_rearrange", "long.dec_check_rearrange_masked"],
    "COMPRESS": ["long.dec_check_compress", "long.dec_compress"],
    "SELECT_FROM": ["long.dec_selectFrom2"],
    "SHUFFLE_FREE": ["long.dec_check_offset", "long.dec_check_offset_masked"],
}


def parse(text):
    """{(rung, arm): rate}. The arm is the label without its chain; the chain is its key too."""
    out, rung = {}, None
    for line in text.splitlines():
        line = re.sub(r"^\[info\] ?", "", line)
        h = HEADER.match(line)
        if h:
            rung = RUNGS[h.group(2).strip()]
            continue
        m = ROW.match(line)
        if m and rung:
            label = m.group(1).strip()
            for chain, k in CHAINS.items():
                if label.endswith(", " + chain):
                    out[(rung, label[: -len(chain) - 2], k)] = float(m.group(5))
    return out


def form_arm(form):
    return "DECIMAL slots, " + form


def cells_of(form):
    base = form.replace(" with the shuffle-free check", "")
    cells = list(CELLS[base])
    if form.endswith("shuffle-free check"):
        cells += CELLS["SHUFFLE_FREE"]
    return cells


def census_ok(form, censuses):
    """False when a cell of the form reads F where REARRANGE's check cell reads I."""
    for census in censuses:
        for width, cells in census["widths"].items():
            if cells.get("long.dec_check_rearrange", {}).get("verdict") != "I":
                continue
            for cell in cells_of(form):
                if cells.get(cell, {}).get("verdict") == "F":
                    return False
    return True


def verdict(r256, r128, censuses):
    lines = []

    def rate(r, rung, arm, k):
        return r[(rung, arm, k)]

    # DEFAULT
    base = form_arm("REARRANGE")
    winners = []
    for form in FORMS[1:]:
        arm = form_arm(form)
        l2 = rate(r256, "L2", arm, 0) / rate(r256, "L2", base, 0)
        l3 = rate(r256, "L3", arm, 0) / rate(r256, "L3", base, 0)
        four = min(rate(r256, g, arm, 4) / rate(r256, g, base, 4) for g in RUNGS.values())
        ok = census_ok(form, censuses)
        wins = l2 >= 1.3 and l3 >= 1.3 and four >= 1.0 and ok
        lines.append(
            f"DEFAULT {form}: L2 {l2:.2f}, L3 {l3:.2f}, 4-add min {four:.2f}, "
            f"census {'ok' if ok else 'falls back'}: {'WINS' if wins else 'stays'}"
        )
        if wins:
            winners.append((rate(r256, "L3", arm, 0), form))
    default = max(winners)[1] if winners else "REARRANGE"
    # CACHE
    due = True
    for name, r in (("256", r256), ("128", r128)):
        best = {g: max(rate(r, g, form_arm(f), 0) for f in FORMS) for g in ("L2", "past L3")}
        past = rate(r, "past L3", BIGINT, 0) / best["past L3"]
        l2 = rate(r, "L2", BIGINT, 0) / best["L2"]
        meets = past >= 1.5 and l2 >= 1.0
        due = due and meets
        lines.append(
            f"CACHE {name}: BIGINT over the best form, past L3 {past:.2f}, "
            f"L2 {l2:.2f}: {'meets' if meets else 'misses'}"
        )
    # INT
    for name, r in (("256", r256), ("128", r128)):
        parts = []
        for g in RUNGS.values():
            for k in CHAINS.values():
                best = max(rate(r, g, form_arm(f), k) for f in FORMS)
                parts.append(f"{g}/{k}:{rate(r, g, INT_ARM, k) / best:.2f}")
        lines.append(f"INT {name}: " + " ".join(parts))
    lines.append(f"VERDICT: default={default} cache={'due' if due else 'not due'}")
    return lines


def selftest():
    def table(rung_rows, level, rates):
        head = (
            f"the decimal load over {rung_rows} rows - {level}:"
            + " " * 40
            + "Best Time(ms)   Avg Time(ms)   Stdev(ms)    Rate(M/s)   Per Row(ns)   Relative"
        )
        rows = [head, "-" * 80]
        for label, r in rates.items():
            rows.append(f"{label}    10   11   0   {r:.1f}   {1000 / r:.1f}   1.0X")
        return "\n".join(rows)

    def file(scale):
        out = []
        for n, level in (
            (8192, "every arm in L2"),
            (262144, "every arm in L3"),
            (8388608, "every arm past L3"),
        ):
            rates = {}
            for f in FORMS:
                for chain in CHAINS:
                    rates[f"DECIMAL slots, {f}, {chain}"] = scale(f, level, chain)
            for chain in CHAINS:
                rates[f"{BIGINT}, {chain}"] = scale("BIGINT", level, chain)
                rates[f"{INT_ARM}, {chain}"] = scale("INT", level, chain)
            out.append(table(n, level, rates))
        return "\n".join(out)

    def flat(f, level, chain):
        return {"BIGINT": 2000, "INT": 500}.get(f, 1000)

    r = verdict(parse(file(flat)), parse(file(flat)), [])
    assert r[-1] == "VERDICT: default=REARRANGE cache=due", r[-1]

    def compress_wins(f, level, chain):
        if f == "COMPRESS":
            return 1400
        return flat(f, level, chain)

    r = verdict(parse(file(compress_wins)), parse(file(flat)), [])
    # BIGINT over the best form past L3 is 2000 / 1400 = 1.43 at 256 bits: under 1.5.
    assert r[-1] == "VERDICT: default=COMPRESS cache=not due", r[-1]

    def select_wins(f, level, chain):
        return 1400 if f == "SELECT_FROM" else flat(f, level, chain)

    arm = {
        "widths": {
            "128": {
                "long.dec_check_rearrange": {"verdict": "I"},
                "long.dec_selectFrom2": {"verdict": "F"},
            }
        }
    }
    r = verdict(parse(file(select_wins)), parse(file(flat)), [arm])
    # SELECT_FROM is 1.4 times faster but falls back on the Arm census: it is not the default.
    assert r[-1] == "VERDICT: default=REARRANGE cache=not due", r[-1]
    r = verdict(parse(file(select_wins)), parse(file(flat)), [])
    assert r[-1] == "VERDICT: default=SELECT_FROM cache=not due", r[-1]

    def close(f, level, chain):
        return 1400 if f == "BIGINT" else 1000

    r = verdict(parse(file(close)), parse(file(flat)), [])
    assert r[-1] == "VERDICT: default=REARRANGE cache=not due", r[-1]
    print("selftest: 5 cases passed")


def main(argv):
    if argv[:1] == ["--selftest"]:
        selftest()
        return 0
    if len(argv) not in (2, 4):
        print(__doc__)
        return 2
    texts = [open(p, encoding="utf-8").read() for p in argv[:2]]
    censuses = [json.load(open(p, encoding="utf-8")) for p in argv[2:]]
    for line in verdict(parse(texts[0]), parse(texts[1]), censuses):
        print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
