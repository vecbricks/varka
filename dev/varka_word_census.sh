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
# The validity-word census (PLAN_MILESTONE_5.md 2.9): what every value root's
# word is today and under the coalesce and absorption extensions, over the
# Surface projections, a list of composites and the fuzzer's value grammar,
# each single-root shape cross-checked against the emitter's own verdict.
#
#   dev/varka_word_census.sh                 # 20000 grammar shapes, seed 7
#   dev/varka_word_census.sh --fuzz 100000 --seed 11
#
# Runs VarkaWordCensus (catalyst test scope) through sbt and prints its report.
set -euo pipefail
# Usage text is found rather than numbered: a hard-coded range silently truncates as the
# comment above it grows, which had already happened to four of these scripts. Ends at the
# first line that is not a comment.
usage() { sed -n '17,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }
case "${1:-}" in -h|--help) usage 0 ;; esac

root="$(git rev-parse --show-toplevel)"; cd "$root"
main=org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaWordCensus
quoted=""
for a in "$@"; do quoted+=" \"$a\""; done
build/sbt -batch "catalyst/Test/runMain $main$quoted" 2>&1 | sed -E 's/^\[(info|error)\] ?//' \
  | sed -n '/^shape /,$p' | grep -v -E '^\[(success|warn)\]|^WARNING: '
