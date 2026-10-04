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
"""Which operators of your queries ran outside whole-stage codegen, and why, from event logs.

  dev/varka_codegen_report.py EVENT_LOG...          # a file or a directory of them, per app
  dev/varka_codegen_report.py --operators EVENT_LOG # also list each operator left out

An operator that leaves its whole-stage codegen stage leaves no log line; the only sign is in the
plan, where it lacks the `*(n)` prefix. A Spark event log (`spark.eventLog.enabled=true`) records
every SQL execution's final plan, its node names and their strings, and the settings the session
changed, and that is enough to classify each operator the way `CollapseCodegenStages` did, offline,
over a whole application's history. The table is the one `VarkaCodegenGiveUpCensus` prints for
Spark's own query suites (VARKA-233.md 2 and 10.4):

* in a stage: under a `WholeStageCodegen (n)` node and not below an `InputAdapter`;
* whole-stage codegen switched off by a setting: the execution ran with
  `spark.sql.codegen.wholeStage=false`;
* a CodegenFallback expression: the operator's string calls a function Spark evaluates without
  generated code, such as `from_json` or `zip_with`; the string lists at most
  `spark.sql.debug.maxToStringFields` entries, so a fallback past them is missed;
* a columnar scan, read inside the stage: a scan under `ColumnarToRow`, which is Spark's design;
* not a CodegenSupport operator: a window, an object hash aggregate, a top-k sort and the like;
* a CodegenSupport operator left out: the rest, which the census tells apart as `supportCodegen`
  false and structural; the plan string carries neither reason, so here they are one line.

Exchanges are counted apart, since they bound stages by design, and the plumbing - adaptive
plans and their query stages, input adapters, columnar transitions, subquery wrappers, the
exchanges' read side, the write commands - is walked through. Executions that failed and plans
that are commands are skipped. The log must be uncompressed (`spark.eventLog.compress=false`) or
gzipped.
"""

import argparse
import collections
import gzip
import json
import os
import re
import sys

START = "org.apache.spark.sql.execution.ui.SparkListenerSQLExecutionStart"
UPDATE = "org.apache.spark.sql.execution.ui.SparkListenerSQLAdaptiveExecutionUpdate"
END = "org.apache.spark.sql.execution.ui.SparkListenerSQLExecutionEnd"

# Operators that implement CodegenSupport, read from the class hierarchy of Spark's sql/core
# (javap over the compiled classes, superclasses and traits included), by node name.
CODEGEN_SUPPORT = {
    "BaseLimit",
    "ColumnarToRow",
    "DeserializeToObject",
    "EmptyRelation",
    "Expand",
    "Filter",
    "Generate",
    "GlobalLimit",
    "LocalLimit",
    "LocalTableScan",
    "MapElements",
    "OneRowRelation",
    "Project",
    "RDDScan",
    "Range",
    "RowDataSourceScan",
    "Sample",
    "SerializeFromObject",
    "Sort",
    "Union",
    "HashAggregate",
    "SortAggregate",
    "MergeRows",
    "BroadcastHashJoin",
    "BroadcastNestedLoopJoin",
    "ShuffledHashJoin",
    "SortMergeAsOfJoin",
    "SortMergeJoin",
}
# Plans whose root is one of these are commands, not queries, and are skipped, as the census
# skips `CommandResultExec`, `ExecutedCommandExec` and every `V2CommandExec`.
V2_COMMANDS = set(
    "CloseCursor CreateVariable DeclareCursor DropVariable ExecuteImmediate FetchCursor OpenCursor "
    "SetVariable AddCheckConstraint AddPartition AlterNamespaceSetProperties AlterTable "
    "AlterV2View AlterV2ViewSchemaBinding AlterV2ViewSetProperties AlterV2ViewUnsetProperties "
    "AppendData AtomicCreateTableAsSelect AtomicReplaceTableAsSelect AtomicReplaceTable "
    "CacheTableAsSelect CacheTable CreateIndex CreateNamespace CreateTableAsSelect CreateTable "
    "CreateTableLike CreateV2MetricView CreateV2View DeleteFromTable DescribeColumn "
    "DescribeNamespace DescribeTable DescribeTablePartition DescribeV2ViewColumn DescribeV2View "
    "DropIndex DropNamespace DropPartition DropTable DropView InsertOnlyMerge "
    "OverwriteByExpression OverwritePartitionsDynamic RefreshTable RenamePartition RenameTable "
    "RenameV2View ReplaceData ReplaceTableAsSelect ReplaceTable SetCatalogAndNamespace "
    "ShowColumns ShowCreateTable ShowCreateV2View ShowFunctions ShowPartitions "
    "ShowTablePartition ShowTableProperties ShowTables ShowTablesExtended ShowV2ViewColumns "
    "ShowV2ViewProperties ShowViews TruncatePartition TruncateTable UncacheTable V2Command "
    "WriteDelta WriteToDataSourceV2 CommandResult".split()
)
# Functions whose expression is a non-leaf CodegenFallback, by the name the plan string prints.
FALLBACK_FUNCTIONS = re.compile(
    r"\b(from_json|zip_with|map_zip_with|map_filter|transform_keys|transform_values|"
    r"array_sort|json_value|json_exists|json_table|reflect|java_method|"
    r"hll_sketch_estimate|hll_union|theta_sketch_estimate|theta_union|theta_intersection|"
    r"theta_difference|approx_top_k_estimate|tuple_sketch_\w+|tuple_union\w*|"
    r"tuple_intersection\w*|tuple_difference\w*|kll_sketch_\w+)\("
)
WALK_THROUGH = {
    "AdaptiveSparkPlan",
    "ColumnarToRow",
    "RowToColumnar",
    "AQEShuffleRead",
    "Subquery",
    "ReusedSubquery",
    "WriteFiles",
}
EXCHANGES = {"Exchange", "BroadcastExchange", "ReusedExchange", "SubqueryBroadcast"}
# Their children are what the plan was built from, not operators of it: the cached plan of an
# in-memory scan, an RDD's lineage, the logical plan of an empty relation.
LEAVES = {"InMemoryTableScan", "RDDScan", "ExistingRDD", "EmptyRelation"}


def name_of(node):
    return re.sub(r" \(\d+\)$", "", node["nodeName"])


def classify(node, parent, wholestage_off):
    """Why an operator outside a stage is outside it, and the name to tally it under."""
    name, text = name_of(node), node.get("simpleString", "")
    if wholestage_off:
        return "whole-stage codegen switched off by a setting", name
    fallback = FALLBACK_FUNCTIONS.search(text)
    if fallback and name in CODEGEN_SUPPORT:
        return "a CodegenFallback expression", fallback.group(1)
    if parent == "ColumnarToRow":
        return "a columnar scan, read inside the stage", name.split(" ")[0]
    if name in CODEGEN_SUPPORT:
        return "a CodegenSupport operator left out", name
    return "not a CodegenSupport operator", name.split(" ")[0]


def walk(node, in_stage, parent, off, out):
    name = name_of(node)
    children = node.get("children", [])
    if node["nodeName"].startswith("WholeStageCodegen ("):
        for c in children:
            walk(c, True, name, off, out)
        return
    if name == "InputAdapter":
        # The adapter is plumbing: what a scan below it is read through is the node above it.
        for c in children:
            walk(c, False, parent, off, out)
        return
    if (
        name in WALK_THROUGH
        or name.endswith("QueryStage")
        or (name.startswith("Execute ") and children)
    ):
        for c in children:
            walk(c, False, name, off, out)
        return
    if name in EXCHANGES:
        out.append(("exchange", None, None))
        if name != "ReusedExchange":
            for c in children:
                walk(c, False, name, off, out)
        return
    if in_stage:
        out.append(("in", name, None))
    else:
        why, who = classify(node, parent, off)
        out.append(("out", who, why))
    if name not in LEAVES:
        for c in children:
            walk(c, in_stage, name, off, out)


def is_command(plan):
    name = name_of(plan)
    return name in V2_COMMANDS or (name.startswith("Execute ") and not plan.get("children"))


def events(path):
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8", errors="replace") as f:
        for line in f:
            if "SparkListenerSQL" not in line:
                continue
            try:
                yield json.loads(line)
            except ValueError:
                continue


def report(paths, list_operators):
    plans, settings, failed = {}, {}, set()
    for path in paths:
        for e in events(path):
            kind, eid = e.get("Event"), (path, e.get("executionId"))
            if kind == START:
                plans[eid] = e.get("sparkPlanInfo")
                settings[eid] = e.get("modifiedConfigs") or {}
            elif kind == UPDATE:
                plans[eid] = e.get("sparkPlanInfo")
            elif kind == END and e.get("errorMessage"):
                failed.add(eid)
    t = collections.Counter()
    reasons = collections.OrderedDict()
    for eid, plan in plans.items():
        if eid in failed or not plan or is_command(plan):
            continue
        off = settings.get(eid, {}).get("spark.sql.codegen.wholeStage", "true") == "false"
        found = []
        walk(plan, False, None, off, found)
        if not found:
            continue
        t["plans"] += 1
        touched = set()
        for kind, who, why in found:
            if kind == "exchange":
                t["exchanges"] += 1
                continue
            t["operators"] += 1
            if kind == "in":
                t["in"] += 1
                continue
            n, nplans, whos = reasons.get(why, (0, 0, collections.Counter()))
            whos[who] += 1
            reasons[why] = (n + 1, nplans + (why not in touched), whos)
            touched.add(why)
    print(f"{t['plans']} plans, {t['operators']} operators, {t['exchanges']} exchanges")
    if not t["operators"]:
        return
    outside = t["operators"] - t["in"]
    print(
        f"in a stage: {t['in']} ({100.0 * t['in'] / t['operators']:.1f}%); "
        f"outside: {outside} ({100.0 * outside / t['operators']:.1f}%)"
    )
    print(f"{'reason':<46}{'operators':>10}{'share':>8}{'plans':>7}  most often")
    for why, (n, nplans, whos) in sorted(reasons.items(), key=lambda kv: -kv[1][0]):
        top = ", ".join(f"{w} {c}" for w, c in whos.most_common(5))
        print(f"{why:<46}{n:>10}{100.0 * n / t['operators']:>7.1f}%{nplans:>7}  {top}")
        if list_operators:
            for w, c in whos.most_common():
                print(f"    {w} {c}")


def natural(name):
    """Sort `events_10_*` after `events_9_*`."""
    return [int(x) if x.isdigit() else x for x in re.split(r"(\d+)", name)]


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("paths", nargs="+", help="event log files, or directories of them")
    p.add_argument("--operators", action="store_true", help="list every operator by reason")
    args = p.parse_args()
    files = []
    for path in args.paths:
        if os.path.isdir(path):
            # Rolling logs are a directory per application, `events_<n>_<app>` in order and an
            # `appstatus_` marker beside them.
            for d, _, names in sorted(os.walk(path)):
                logs = [n for n in names if not n.startswith("appstatus_")]
                files += [os.path.join(d, n) for n in sorted(logs, key=natural)]
        else:
            files.append(path)
    if not files:
        sys.exit("no event log found")
    report(files, args.operators)


if __name__ == "__main__":
    main()
