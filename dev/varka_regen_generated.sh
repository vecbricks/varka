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
# --owed mode lists the entries of the merge in progress or committed, and of a squash merge,
# cherry-pick, revert or rebase uncommitted or stopped, never an aborted one's (the comment
# above its owed_live says how, and what it cannot see). This script turns those files
# into steps and runs them in the order the files read each other: types.tsv and
# casts_fused.tsv, then coverage.json and the docs blocks where owed, the width audit (it reads
# coverage.json), the bytes file (likewise), coverage again after an audit that ran (the docs
# read the width audit, and nothing else of coverage's reads the other two), and last the
# census, which reads them all and costs a second. The JVM steps run in one sbt call,
# under the heavy slot's lock, VARKA_HEAVY_LOCK or else ~/proj/.varka-locks/heavy.lock (made if
# missing), with SPARK_LOCAL_IP=127.0.0.1. On a CPU that is not the width audit's host the audit
# is skipped and left owed, since its census is that CPU's (VarkaWidthAuditSuite), and the rest
# runs, still owed if a merge owed it; VARKA_CPUINFO names another cpuinfo file for the
# self-test. The owed list is cleared only after every step passed.
set -euo pipefail

top="$(git rev-parse --show-toplevel)"
cd "$top"
# The commit the files are regenerated on, which the --all marker names at the end.
start="$(git rev-parse HEAD)"
all=0; dry=0
for arg in "$@"; do
  case "$arg" in
    --all) all=1 ;;
    --dry-run) dry=1 ;;
    *) echo "usage: dev/varka_regen_generated.sh [--all] [--dry-run]" >&2; exit 2 ;;
  esac
done

if [ $all = 1 ]; then
  # The driver's list of generated files, shared with the pull-request size check (VARKA-554).
  files="$(python3 dev/varka_generated_merge.py --list | tr '\n' ' ')"
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
audit=0; bytes=0
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
# Coverage runs first where it is owed, since the width audit and the bytes suite read
# coverage.json, and again after a width audit that ran, since the docs blocks it writes read
# the audit. Nothing the coverage suite writes reads emitted_bytes.json, so the bytes file alone
# owes no coverage run, and a skipped audit owes none either.
coverage=0
if want sql/varka/coverage.json || want docs/sql-varka.md; then coverage=1; fi
if [ $coverage = 1 ] || [ $audit = 1 ]; then envs+=(VARKA_COVERAGE_REGEN=true); fi
if [ $coverage = 1 ]; then steps+=("catalyst/testOnly *VarkaCoverageSuite"); fi
if [ $audit = 1 ]; then
  envs+=(VARKA_AUDIT_REGEN=true); steps+=("catalyst/testOnly *VarkaWidthAuditSuite"); fi
if [ $bytes = 1 ]; then
  envs+=(VARKA_BYTES_REGEN=true); steps+=("catalyst/testOnly *VarkaEmittedBytesSuite"); fi
if [ $audit = 1 ]; then steps+=("catalyst/testOnly *VarkaCoverageSuite"); fi

if [ ${#steps[@]} -eq 0 ]; then
  echo "varka regen: no JVM regeneration owed"
else
  echo "varka regen: env ${envs[*]} build/sbt$(printf " '%s'" "${steps[@]}")"
fi
echo "varka regen: python3 dev/varka_census.py"
[ $dry = 1 ] && exit 0

if [ ${#steps[@]} -gt 0 ]; then
  # flock creates the lock file; its directory is created here, so the JVM steps never run
  # outside the heavy slot.
  lock="${VARKA_HEAVY_LOCK:-$HOME/proj/.varka-locks/heavy.lock}"
  mkdir -p "$(dirname "$lock")"
  env "${envs[@]}" flock "$lock" build/sbt "${steps[@]}"
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
# Last, so that it marks only a run whose every step passed: the commit the generated files were
# regenerated on and those the regeneration left as that commit has them, which pays a rebase's
# text merge of them (dev/varka_push_check.py's rebase_debt). A file it rewrote is not listed,
# so it stays owed until --all runs again on the commit that holds it, nor is a width audit
# skipped on this CPU. No marker when HEAD moved during the run; it is written whole, through a
# temporary file.
if [ $all = 1 ]; then
  marker="$(git rev-parse --git-path varka-regen-all)"
  if [ "$(git rev-parse HEAD)" != "$start" ]; then
    echo "varka regen: HEAD moved during the run; no --all marker written" >&2
  else
    {
      echo "$start"
      for f in $files; do
        if [ $audit_skipped = 1 ] && [ "$f" = sql/varka/width_audit.json ]; then continue; fi
        if git diff --quiet HEAD -- "$f"; then echo "$f"; fi
      done
    } > "$marker.tmp"
    mv "$marker.tmp" "$marker"
    if ! git diff --quiet HEAD -- $files; then
      echo "varka regen: the marker leaves the rewritten files owed; commit them, then run" \
        "--all again so that its marker names the commit that holds them"
    fi
  fi
fi
