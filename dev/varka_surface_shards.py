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
"""Run one sharded date surface across GitHub runners, re-dispatching the shards that miss.

    dev/varka_surface_shards.py run     --shards 8 --benchmark chains --rows 100000000
    dev/varka_surface_shards.py collect --shards 8 --out sql/varka/bench/benchmarks

The measurement wants a runner whose 512-bit datapath is real, and about one runner in
eighteen is (VARKA-62.md 11.9), so a shard cannot simply be dispatched and awaited: most
dispatches stop at the gate in about a minute. This dispatches the shards that are still
outstanding, watches which of them cleared the gate, and dispatches the rest again, until
every shard has a `measure` job that ran. GitHub allows twenty jobs at once, which is what
makes the arithmetic work: a round costs a minute or two and returns roughly one shard.

`run` leaves its state in .git/varka-surface-shards.json, so it is interruptible and can be
re-run; `collect` downloads each shard's artifact and hands the pieces to
dev/varka_bench_merge.py, which refuses to join shards that disagree.

**A shard whose `measure` job failed still counts as run.** The surface fails the job by
design when a Varka row's fixed share is over the ceiling, and it uploads its results first,
so the file is there and the violation is the finding. What does *not* count is a job that
never reached `measure`, which is the gate saying this runner was the wrong machine.
"""

import argparse
import json
import os
import subprocess
import sys
import time

WORKFLOW = "varka-surface-benchmark.yml"
REPO = "vecbricks/varka"
STATE = ".git/varka-surface-shards.json"


def gh(*args, check=True):
    p = subprocess.run(["gh", *args], capture_output=True, text=True)
    if check and p.returncode != 0:
        raise SystemExit(f"gh {' '.join(args)}\n{p.stderr.strip()}")
    return p.stdout.strip()


def runs_since(after):
    """Recent runs of this workflow, newest first, as (id, createdAt, status, conclusion)."""
    raw = gh(
        "run",
        "list",
        "--repo",
        REPO,
        "--workflow",
        WORKFLOW,
        "--limit",
        "60",
        "--json",
        "databaseId,createdAt,status,conclusion",
        check=False,
    )
    if not raw:
        return []
    return [r for r in json.loads(raw) if r["createdAt"] >= after]


def measure_ran(run_id):
    """Did this run get past the gate? None while still undecided."""
    raw = gh("run", "view", str(run_id), "--repo", REPO, "--json", "jobs", check=False)
    if not raw:
        return None
    jobs = json.loads(raw)["jobs"]
    by = {j["name"].split(" on ")[0]: j for j in jobs}
    measure = next((j for n, j in by.items() if n.startswith("Date surface")), None)
    if measure is None:
        # Skipped jobs are absent until the run settles; a settled run with no measure job
        # is a gate rejection, which is the common case and not an error.
        gate = by.get("Gate")
        if gate and gate["conclusion"] in ("failure", "cancelled"):
            return False
        return None
    if measure["status"] != "completed":
        return None
    # Failure here is a fixed-share violation, not a miss: the artifact is uploaded first.
    return measure["conclusion"] in ("success", "failure")


def load_state(shards):
    if os.path.exists(STATE):
        st = json.load(open(STATE))
        if st.get("shards") == shards:
            return st
    return {"shards": shards, "done": {}, "attempts": {}}


def save_state(st):
    os.makedirs(os.path.dirname(STATE), exist_ok=True)
    json.dump(st, open(STATE, "w"), indent=2)


def dispatch(shard, shards, a):
    before = (
        gh(
            "run",
            "list",
            "--repo",
            REPO,
            "--workflow",
            WORKFLOW,
            "--limit",
            "1",
            "--json",
            "createdAt",
            "--jq",
            ".[0].createdAt",
            check=False,
        )
        or "1970-01-01"
    )
    gh(
        "workflow",
        "run",
        WORKFLOW,
        "--repo",
        REPO,
        "--ref",
        a.ref,
        "-f",
        "stop-after=run",
        "-f",
        f"benchmark={a.benchmark}",
        "-f",
        f"require-datapath={a.require_datapath}",
        "-f",
        f"rows={a.rows}",
        "-f",
        f"partitions={a.partitions}",
        "-f",
        f"driver-memory={a.driver_memory}",
        "-f",
        f"shard={shard}/{shards}",
        "-f",
        "create-commit=false",
    )
    # The run id is not returned by `workflow run`, so it is claimed by being the one that
    # appeared after the dispatch. Sequential dispatches keep that unambiguous.
    for _ in range(30):
        time.sleep(2)
        fresh = [r for r in runs_since(before) if r["createdAt"] > before]
        if fresh:
            return fresh[-1]["databaseId"]
    raise SystemExit(f"shard {shard}: dispatched but no run appeared")


def cmd_run(a):
    st = load_state(a.shards)
    while len(st["done"]) < a.shards:
        pending = [i for i in range(a.shards) if str(i) not in st["done"]]
        batch = pending[: a.max_parallel]
        print(f"== round: {len(st['done'])}/{a.shards} done, dispatching {batch}", flush=True)
        live = {}
        for i in batch:
            live[i] = dispatch(i, a.shards, a)
            st["attempts"][str(i)] = st["attempts"].get(str(i), 0) + 1
        save_state(st)
        while live:
            time.sleep(a.poll)
            for i, rid in list(live.items()):
                verdict = measure_ran(rid)
                if verdict is None:
                    continue
                del live[i]
                if verdict:
                    st["done"][str(i)] = rid
                    print(f"   shard {i}: ran on run {rid}", flush=True)
                else:
                    print(f"   shard {i}: gate rejected run {rid}, will retry", flush=True)
            save_state(st)
    print(
        f"all {a.shards} shards ran; now: dev/varka_surface_shards.py collect --shards {a.shards}"
    )
    return 0


def cmd_collect(a):
    st = load_state(a.shards)
    if len(st["done"]) < a.shards:
        raise SystemExit(
            f"only {len(st['done'])} of {a.shards} shards have run; finish `run` first"
        )
    stage = os.path.abspath(a.stage)
    os.makedirs(stage, exist_ok=True)
    for i in range(a.shards):
        rid = st["done"][str(i)]
        dest = os.path.join(stage, f"shard{i}")
        os.makedirs(dest, exist_ok=True)
        gh("run", "download", str(rid), "--repo", REPO, "--dir", dest)
        for root, _d, names in os.walk(dest):
            for n in names:
                if n.endswith(".tar"):
                    subprocess.run(["tar", "-xf", os.path.join(root, n), "-C", root], check=True)
        print(f"   shard {i}: artifact of run {rid} -> {dest}")
    subprocess.run([sys.executable, "dev/varka_bench_merge.py", stage, "--out", a.out], check=True)
    return 0


def main():
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("run", "collect"):
        p = sub.add_parser(name)
        p.add_argument("--shards", type=int, required=True)
        if name == "run":
            p.add_argument("--rows", default="500000000")
            p.add_argument("--partitions", default="1")
            p.add_argument("--driver-memory", default="6g")
            p.add_argument("--require-datapath", default="512", choices=["512", "any"])
            # Without this the dispatcher silently ran whatever the workflow defaults to, so a
            # sharded chains run had no orchestrator and `collect` merged surface shards.
            p.add_argument("--benchmark", default="surface", choices=["surface", "chains"])
            p.add_argument("--ref", default="master")
            p.add_argument("--max-parallel", type=int, default=20)
            p.add_argument("--poll", type=int, default=30)
        else:
            p.add_argument("--stage", default="target/varka-surface-shards")
            p.add_argument("--out", default="sql/varka/bench/benchmarks")
    a = ap.parse_args()
    return cmd_run(a) if a.cmd == "run" else cmd_collect(a)


if __name__ == "__main__":
    sys.exit(main())
