#!/usr/bin/env bash
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
# The TPC codegen census: compiles every whole-stage codegen stage of every TPC-DS and TPC-H
# query under six configurations and writes each stage's largest generated method against
# HotSpot's 8000-byte HugeMethodLimit (VarkaTpcCodegenCensusSuite, PLAN_TASK_193.md).
#
#   dev/varka_tpc_census.sh [sources-dir]
#
# Writes sql/varka/census/tpc_codegen-<configuration>.txt, one file per configuration, each
# naming the commit it was taken at. With a sources directory, the generated source of every
# stage past 8000 bytes is written under it. Nothing is timed, so the machine may be busy.
set -euo pipefail

cd "$(dirname "$0")/.."
out="$PWD/sql/varka/census"
sources="${1:-}"
commit="$(git rev-parse --short=11 HEAD)"
changes="$(git status --porcelain -- . ':!sql/varka/census')"
[ -z "$changes" ] || commit="$commit (working tree dirty)"
opts="\"-Dvarka.tpc.census.dir=$out\", \"-Dvarka.tpc.census.commit=$commit\""
[ -n "$sources" ] && opts="$opts, \"-Dvarka.tpc.census.sources=$(realpath -m "$sources")\""
build/sbt -batch "project sql" "set Test/javaOptions ++= Seq($opts)" \
  "testOnly *VarkaTpc*CodegenCensusSuite *VarkaTpch*CodegenCensusSuite"
ls -l "$out"
