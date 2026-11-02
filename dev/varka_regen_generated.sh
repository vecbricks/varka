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
# Regenerate the generated files a merge owes (VARKA-546), in the documented order.
#
#   dev/varka_regen_generated.sh            # the files the current or last merge owes
#   dev/varka_regen_generated.sh --all      # every generated file
#   dev/varka_regen_generated.sh [--all] --dry-run  # print the steps and run nothing
#
# dev/varka_generated_merge.py, the merge driver, appends each file whose merge it could not
# compute in full to $(git rev-parse --git-path varka-regen-owed), tagged with the merge; its
# --owed mode lists the entries of the merge in progress or committed, never an aborted one's
# (the comment above its owed_live says how). This script turns those files
# into steps and runs them in the order the files read each other: types.tsv and
# casts_fused.tsv, then coverage.json and the docs blocks, the width audit (it reads
# coverage.json), the bytes file (likewise), coverage again (the docs read the width audit), and
# last the census, which reads them all and costs a second. The JVM steps run in one sbt call,
# under the heavy slot's lock when VARKA_HEAVY_LOCK names one (or ~/proj/.varka-locks/heavy.lock
# exists), with SPARK_LOCAL_IP=127.0.0.1. On a CPU that is not the width audit's host the audit
# is skipped and left owed, since its census is that CPU's (VarkaWidthAuditSuite), and the rest
# runs, still owed if a merge owed it; VARKA_CPUINFO names another cpuinfo file for the
# self-test. The owed list is cleared only after every step passed.
set -euo pipefail

top="$(git rev-parse --show-toplevel)"
cd "$top"
all=0; dry=0
for arg in "$@"; do
  case "$arg" in
    --all) all=1 ;;
    --dry-run) dry=1 ;;
    *) echo "usage: dev/varka_regen_generated.sh [--all] [--dry-run]" >&2; exit 2 ;;
  esac
done

if [ $all = 1 ]; then
  files="sql/varka/types.tsv sql/varka/plans/m8/casts_fused.tsv sql/varka/coverage.json"
  files+=" sql/varka/width_audit.json sql/varka/emitted_bytes.json"
else
  files="$(python3 dev/varka_generated_merge.py --owed | tr '\n' ' ')"
fi

want() { case " $files " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

steps=()
envs=(SPARK_LOCAL_IP=127.0.0.1)
want sql/varka/types.tsv && {
  envs+=(VARKA_TYPES_REGEN=true); steps+=("catalyst/testOnly *VarkaTypesSuite"); }
want sql/varka/plans/m8/casts_fused.tsv && {
  envs+=(VARKA_CASTS_REGEN=true); steps+=("catalyst/testOnly *VarkaCastMatrixSuite"); }
audit=0; bytes=0; coverage=0
want sql/varka/width_audit.json && audit=1
want sql/varka/emitted_bytes.json && bytes=1
# The width audit is that host's census (VarkaWidthAuditSuite), so on any other CPU it is
# skipped and stays owed; the rest runs. The CPU's name is read as the suite reads it: the
# first "model name" line, after its first colon, trimmed.
audit_skipped=0
if [ $audit = 1 ]; then
  read_host='import json; print(json.load(open("sql/varka/width_audit.json"))["host"]["cpu"])'
  host="$(python3 -c "$read_host")"
  line="$(grep -m1 '^model name' "${VARKA_CPUINFO:-/proc/cpuinfo}" 2>/dev/null || true)"
  cpu="${line#*:}"
  cpu="${cpu#"${cpu%%[![:space:]]*}"}"
  cpu="${cpu%"${cpu##*[![:space:]]}"}"
  if [ -z "$line" ] || [ "$host" != "$cpu" ]; then
    echo "varka regen: skipping width_audit.json, owed on its host: it is $host's census" \
      "and this is ${cpu:-another CPU}; run dev/varka_regen_generated.sh there" >&2
    audit=0; audit_skipped=1
  fi
fi
# After the skip, so that a skipped width audit owes no coverage run of its own.
if want sql/varka/coverage.json || want docs/sql-varka.md || [ $audit = 1 ] || [ $bytes = 1 ]
then
  coverage=1
fi
if [ $coverage = 1 ]; then
  envs+=(VARKA_COVERAGE_REGEN=true); steps+=("catalyst/testOnly *VarkaCoverageSuite"); fi
if [ $audit = 1 ]; then
  envs+=(VARKA_AUDIT_REGEN=true); steps+=("catalyst/testOnly *VarkaWidthAuditSuite"); fi
if [ $bytes = 1 ]; then
  envs+=(VARKA_BYTES_REGEN=true); steps+=("catalyst/testOnly *VarkaEmittedBytesSuite"); fi
if [ $audit = 1 ] || [ $bytes = 1 ]; then steps+=("catalyst/testOnly *VarkaCoverageSuite"); fi

if [ ${#steps[@]} -eq 0 ]; then
  echo "varka regen: no JVM regeneration owed"
else
  echo "varka regen: env ${envs[*]} build/sbt$(printf " '%s'" "${steps[@]}")"
fi
echo "varka regen: python3 dev/varka_census.py"
[ $dry = 1 ] && exit 0

if [ ${#steps[@]} -gt 0 ]; then
  lock="${VARKA_HEAVY_LOCK:-$HOME/proj/.varka-locks/heavy.lock}"
  if [ -e "$lock" ]; then
    env "${envs[@]}" flock "$lock" build/sbt "${steps[@]}"
  else
    env "${envs[@]}" build/sbt "${steps[@]}"
  fi
fi
python3 dev/varka_census.py > /dev/null
python3 dev/varka_census.py --check
if [ $audit_skipped = 1 ]; then
  python3 dev/varka_generated_merge.py --check --no-audit
  # The list keeps the width audit only if a merge owed it: --all on another CPU skips an
  # audit that may never have been owed.
  if [ -n "$(python3 dev/varka_generated_merge.py --owed --keep sql/varka/width_audit.json)" ]
  then
    echo "varka regen: done but for width_audit.json, still owed on its host;" \
      "review git diff and commit the regenerated files"
  else
    echo "varka regen: done; the width audit, not owed, was skipped on this CPU;" \
      "review git diff and commit the regenerated files"
  fi
else
  python3 dev/varka_generated_merge.py --check
  rm -f "$(git rev-parse --git-path varka-regen-owed)"
  echo "varka regen: done; review git diff and commit the regenerated files"
fi
