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
Finds the Build run whose saved compile state a Varka job should start from, so that sbt
compiles only what differs (VARKA-520).

A Varka-scoped Build's first sql job archives every module's `target/scala-2.13` - the class
directories and Zinc's analysis of what each source produced - as `varka-compile-state` once its
suites have passed. Zinc decides what to recompile by the sources' hashes against that analysis,
so the state need not come from this branch or from master, and whatever differs is compiled
again. The newest state a Build of master saved (varka-master.yml, which the fork runs on each
sync of its master, VARKA-525) comes first: a pull request is cut from master, so master's state
differs from it by the pull request's own change. Where master has none, the newest state any
branch's Build saved (build_main.yml) is the closest to most pull requests. A run with no usable
state compiles clean.

This prints `run=<id>` of that Build run, or `run=` when no recent run has an unexpired state.
A run's state is looked up by its exact name (`artifacts?name=`): a Build that ran Spark's module
matrix beside the Varka jobs holds more artifacts than one page of the plain listing shows.

  dev/varka_compile_state.py REPOSITORY      # in a step, with GH_TOKEN set
  dev/varka_compile_state.py --selftest
"""

import json
import subprocess
import sys
import unittest

ARTIFACT = "varka-compile-state"
# The workflows whose runs are searched, in order of preference, each with the query that
# selects its runs: master's Builds first, then any branch's.
WORKFLOWS = (
    ("varka-master.yml", "&branch=master"),
    ("build_main.yml", ""),
)
# How many of the newest completed Build runs to look through: a state is kept three days, and
# the fork completes a few dozen Builds on a busy day.
RUNS = 40


def gh(path):
    out = subprocess.run(["gh", "api", path], check=True, capture_output=True, text=True)
    return json.loads(out.stdout)


def has_state(artifacts):
    """Whether a run's artifacts hold an unexpired compile state."""
    return any(a["name"] == ARTIFACT and not a.get("expired") for a in artifacts)


def newest(runs, artifacts_of):
    """The id of the newest run whose artifacts hold a state, or None. `runs` are the API's
    workflow runs; `artifacts_of` gives a run's artifacts by its id."""
    for run in sorted(runs, key=lambda r: r["id"], reverse=True):
        if run.get("status") == "completed" and has_state(artifacts_of(run["id"])):
            return run["id"]
    return None


def first_found(workflows, runs_of, artifacts_of):
    """The run `newest` picks from the first of `workflows` that has one, or None. `runs_of`
    gives a workflow's runs, or None when the repository has no such workflow."""
    for workflow in workflows:
        runs = runs_of(workflow)
        run = newest(runs, artifacts_of) if runs is not None else None
        if run is not None:
            return run
    return None


def main(argv):
    if argv == ["--selftest"]:
        result = unittest.main(argv=["selftest"], exit=False).result
        print("varka_compile_state selftest: " + ("ok" if result.wasSuccessful() else "FAILED"))
        return 0 if result.wasSuccessful() else 1
    if len(argv) != 1:
        print(__doc__, file=sys.stderr)
        return 2
    repository = argv[0]

    def runs_of(workflow):
        name, query = workflow
        path = f"repos/{repository}/actions/workflows/{name}/runs"
        try:
            return gh(f"{path}?status=completed&per_page={RUNS}{query}")["workflow_runs"]
        except subprocess.CalledProcessError:
            # A repository without this workflow on its default branch answers 404.
            return None

    run = first_found(
        WORKFLOWS,
        runs_of,
        lambda i: gh(f"repos/{repository}/actions/runs/{i}/artifacts?name={ARTIFACT}")["artifacts"],
    )
    print(f"run={run if run is not None else ''}")
    return 0


class SelfTest(unittest.TestCase):
    def test_the_newest_completed_run_with_a_state_wins(self):
        runs = [
            {"id": 3, "status": "in_progress"},
            {"id": 2, "status": "completed"},
            {"id": 1, "status": "completed"},
        ]
        arts = {
            1: [{"name": ARTIFACT}],
            2: [{"name": ARTIFACT}, {"name": "other"}],
            3: [{"name": ARTIFACT}],
        }
        self.assertEqual(newest(runs, arts.get), 2)

    def test_an_expired_state_or_none_gives_none(self):
        runs = [{"id": 2, "status": "completed"}, {"id": 1, "status": "completed"}]
        arts = {1: [{"name": ARTIFACT, "expired": True}], 2: [{"name": "other"}]}
        self.assertIsNone(newest(runs, arts.get))

    def test_master_state_comes_before_a_newer_one_of_another_branch(self):
        runs = {
            "master": [{"id": 5, "status": "completed"}],
            "any": [{"id": 9, "status": "completed"}],
        }
        arts = {5: [{"name": ARTIFACT}], 9: [{"name": ARTIFACT}]}
        self.assertEqual(first_found(("master", "any"), runs.get, arts.get), 5)

    def test_no_master_state_or_no_master_workflow_falls_back(self):
        runs = {
            "master": [{"id": 5, "status": "completed"}],
            "any": [{"id": 9, "status": "completed"}],
        }
        arts = {5: [{"name": "other"}], 9: [{"name": ARTIFACT}]}
        self.assertEqual(first_found(("master", "any"), runs.get, arts.get), 9)
        self.assertEqual(first_found(("absent", "any"), runs.get, arts.get), 9)
        self.assertIsNone(first_found(("absent",), runs.get, arts.get))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
