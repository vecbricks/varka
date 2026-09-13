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
# Start a Varka task the way every task here starts: a worktree and branch off
# the base repository's master, and a plan file from the template with the
# sections the plans have converged on.
#
#   dev/varka_task_new.sh 37 "weekofyear by the Thursday rule"
#   dev/varka_task_new.sh 37 "..." --dir /somewhere/else
#
# Creates ../varka-task-<n> on branch varka-task-<n> at origin/master, writes
# sql/varka/plans/PLAN_TASK_<n>.md there from sql/varka/plans/TEMPLATE_TASK.md,
# and installs the pre-commit check in that worktree. It does not commit: the
# first commit is the plan, once its admission check is done, per the template.
# Refuses to overwrite an existing plan file or reuse an existing branch.
set -euo pipefail
usage() { sed -n '17,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }
case "${1:-}" in -h|--help) usage 0 ;; esac
[ "$#" -ge 2 ] || usage
n="$1"; title="$2"; shift 2
dir=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --dir) dir="$2"; shift 2 ;;
    *) usage ;;
  esac
done
[[ "$n" =~ ^[0-9]+$ ]] || { echo "task number must be an integer, got '$n'" >&2; exit 2; }
root="$(git rev-parse --show-toplevel)"
remote="${VARKA_BASE_REMOTE:-origin}"
branch="varka-task-$n"
[ -n "$dir" ] || dir="$(dirname "$root")/varka-task-$n"
plan="sql/varka/plans/PLAN_TASK_$n.md"

if git -C "$root" show-ref --quiet "refs/heads/$branch"; then
  echo "branch $branch already exists; pick up the existing worktree instead" >&2; exit 1
fi
[ -e "$dir" ] && { echo "$dir already exists" >&2; exit 1; }
git -C "$root" fetch -q "$remote" master
if git -C "$root" cat-file -e "$remote/master:$plan" 2>/dev/null; then
  echo "$plan already exists on $remote/master; this task has a plan" >&2; exit 1
fi

git -C "$root" worktree add -q -b "$branch" "$dir" "$remote/master"
sed -e "s/<n>/$n/g" -e "s/<title>/$title/" "$root/sql/varka/plans/TEMPLATE_TASK.md" > "$dir/$plan"
if [ -x "$dir/dev/varka_precommit.sh" ]; then
  (cd "$dir" && dev/varka_precommit.sh --install-hook > /dev/null)
fi
cat <<MSG
worktree: $dir (branch $branch at $(git -C "$dir" rev-parse --short=11 HEAD))
plan:     $plan, from the template - fill sections 1-8 before the code
next:     the milestone row for task $n, marked Planned with the plan file linked;
          the admission check into section 2; dev/varka_emit.sh for section 3.3's counts;
          then the first commit is the plan.
MSG
