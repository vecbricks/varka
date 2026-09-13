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
# The task worktrees this repository accumulates, and which of them are done.
#
#   dev/varka_worktree.sh list        # every worktree: branch, merged into master?, dirty?
#   dev/varka_worktree.sh gc          # show the merged, clean ones it would remove
#   dev/varka_worktree.sh gc --yes    # remove them, and their branches
#
# "Merged" means a merged PR's head is exactly the worktree's HEAD (PRs are
# squash-merged, so the branch is never an ancestor of master; gh answers this
# in one call), or, without gh, that HEAD is an ancestor of origin/master. A
# dirty worktree, or one whose branch is not merged, is never touched, whatever
# the flags. The main worktree is listed and never removed. New task worktrees
# come from dev/varka_task_new.sh.
set -euo pipefail
# Usage text is found rather than numbered: a hard-coded range silently truncates as the
# comment above it grows, which had already happened to four of these scripts. Ends at the
# first line that is not a comment.
usage() { sed -n '17,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }
case "${1:-}" in -h|--help) usage 0 ;; esac

remote="${VARKA_BASE_REMOTE:-origin}"
cmd="${1:-list}"; yes=0
[ "${2:-}" = "--yes" ] && yes=1
root="$(git rev-parse --show-toplevel)"
git -C "$root" fetch -q "$remote" master
base="$(git -C "$root" rev-parse "$remote/master")"
main="$(git -C "$root" worktree list --porcelain | sed -n '1s/^worktree //p')"
# The head SHAs of merged PRs, one per line; empty when gh is unavailable.
merged_heads="$(gh pr list --state merged --limit 500 --json headRefOid --jq '.[].headRefOid' \
  2>/dev/null || true)"
[ -n "$merged_heads" ] || echo "note: gh gave no merged PR list; using the ancestor test only" >&2

printf '%-45s %-32s %-8s %s\n' path branch merged state
removable=()
while IFS= read -r line; do
  path="${line%% *}"
  [ -d "$path" ] || continue
  # `rev-parse --abbrev-ref HEAD` prints the literal "HEAD" for a detached worktree and
  # exits 0, so the `|| echo detached` fallback never fired and the guard below never
  # matched: gc then ran `git branch -D HEAD`, which fails, and under `set -e` that aborted
  # the whole removal loop - leaving every worktree after the detached one in place.
  # `symbolic-ref` fails cleanly when there is no branch, which is the question being asked.
  branch="$(git -C "$path" symbolic-ref --quiet --short HEAD 2>/dev/null || echo detached)"
  head="$(git -C "$path" rev-parse HEAD)"
  merged=no
  if git -C "$root" merge-base --is-ancestor "$head" "$base"; then merged=yes
  elif grep -qx "$head" <<< "$merged_heads"; then merged=yes
  fi
  if [ -n "$(git -C "$path" status --porcelain 2>/dev/null)" ]; then state=dirty; else state=clean; fi
  [ "$path" = "$main" ] && state="$state (main)"
  printf '%-45s %-32s %-8s %s\n' "$path" "$branch" "$merged" "$state"
  if [ "$path" != "$main" ] && [ "$merged" = yes ] && [ "$state" = clean ] && [ "$branch" != detached ]; then
    removable+=("$path:$branch")
  fi
done < <(git -C "$root" worktree list | awk '{ print $1 }')

[ "$cmd" = gc ] || exit 0
echo
if [ "${#removable[@]}" -eq 0 ]; then echo "nothing to remove"; exit 0; fi
failed=0
for entry in "${removable[@]}"; do
  path="${entry%%:*}"; branch="${entry#*:}"
  if [ "$yes" -eq 1 ]; then
    # One failure must not abort the rest: before this, a single unremovable entry took
    # every later worktree down with it, and the failure looked like a clean stop.
    if ! git -C "$root" worktree remove "$path"; then
      echo "could not remove $path; left in place" >&2
      failed=$((failed + 1))
      continue
    fi
    if ! git -C "$root" branch -D "$branch" -q; then
      echo "removed $path but could not delete branch $branch" >&2
      failed=$((failed + 1))
      continue
    fi
    echo "removed $path ($branch)"
  else
    echo "would remove $path ($branch); pass --yes"
  fi
done
[ "$failed" -eq 0 ] || { echo "$failed worktree(s) could not be removed" >&2; exit 1; }
