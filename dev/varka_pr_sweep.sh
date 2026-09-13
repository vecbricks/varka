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
# Dry-merge every open PR of the `origin` repository against its master, and
# every pair of open PRs against each other, without touching the working tree.
#
#   dev/varka_pr_sweep.sh            # all open PRs
#   dev/varka_pr_sweep.sh 105 107    # only these
#
# Run it after every merge to master: a PR that merged cleanly yesterday can
# conflict today because of a sibling that merged in between. PR heads are
# fetched through GitHub's `refs/pull/<n>/head`, so the author's remote does
# not need to be configured. Needs `gh` authenticated. Exit status is the
# number of conflicting merges, so it can gate a script.
set -euo pipefail

# Usage text is found rather than numbered: a hard-coded range silently truncates as the
# comment above it grows, which had already happened to four of these scripts. Ends at the
# first line that is not a comment.
usage() { sed -n '17,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }
case "${1:-}" in -h|--help) usage 0 ;; esac

remote="${VARKA_BASE_REMOTE:-origin}"
base_branch="${VARKA_BASE_BRANCH:-master}"
repo="$(git remote get-url "$remote" | sed -E 's#\.git$##; s#.*[:/]([^/]+/[^/]+)$#\1#')"

git fetch -q "$remote" "$base_branch"
base="$(git rev-parse "$remote/$base_branch")"

if [ "$#" -gt 0 ]; then
  numbers=("$@")
else
  mapfile -t numbers < <(gh pr list --repo "$repo" --state open --json number --jq '.[].number')
fi
if [ "${#numbers[@]}" -eq 0 ]; then
  echo "no open PRs on $repo"
  exit 0
fi

declare -A head title
for n in "${numbers[@]}"; do
  title[$n]="$(gh pr view "$n" --repo "$repo" --json title --jq '.title')"
  git fetch -q "$remote" "pull/$n/head:refs/varka-sweep/$n"
  head[$n]="$(git rev-parse "refs/varka-sweep/$n")"
done

conflicts() {
  # Number of files a merge of $2 into $1 would leave conflicted, from
  # `git merge-tree --write-tree` (git 2.38+): exit 1 on a conflict, the tree id
  # on the first line, then the conflicted paths up to a blank line. Not the
  # legacy three-argument form: its output is a diff, so every conflict marker
  # arrives as `+<<<<<<< .our` and a grep for `^<<<<<<<` counts zero, always -
  # which is how the first version of this script called #107 clean against a
  # master it conflicted with.
  local out
  if out="$(git merge-tree --write-tree --name-only "$1" "$2" 2>/dev/null)"; then
    echo 0
  else
    printf '%s\n' "$out" | awk 'NR > 1 { if ($0 == "") exit; c++ } END { print c + 0 }'
  fi
}

bad=0
printf '%-8s %-8s %s\n' "PR" "vs" "result"
for n in "${numbers[@]}"; do
  c="$(conflicts "$base" "${head[$n]}")"
  if [ "$c" -eq 0 ]; then r="clean"; else r="CONFLICT ($c files)"; bad=$((bad + 1)); fi
  printf '#%-7s %-8s %s  %s\n' "$n" "$base_branch" "$r" "${title[$n]}"
done
for ((i = 0; i < ${#numbers[@]}; i++)); do
  for ((j = i + 1; j < ${#numbers[@]}; j++)); do
    a="${numbers[$i]}"; b="${numbers[$j]}"
    c="$(conflicts "${head[$a]}" "${head[$b]}")"
    if [ "$c" -eq 0 ]; then r="clean"; else r="CONFLICT ($c files)"; bad=$((bad + 1)); fi
    printf '#%-7s #%-7s %s\n' "$a" "$b" "$r"
  done
done
exit "$bad"
