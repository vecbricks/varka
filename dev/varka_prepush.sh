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
# The pre-push hook (VARKA-492; dev/varka_precommit.sh --install-hook installs it). Git runs it
# with the remote's name and URL as arguments and one line per pushed ref on stdin.
#
#   * A branch pushed to vecbricks/varka itself is refused: pull request branches go to the
#     committer's fork, and a branch on vecbricks/varka starts its CI there besides. Master may
#     go there, which is how a merge lands, and so may a tag (a milestone's close).
#   * Every commit pushed is checked again: its message by dev/varka_msg_check.py and the lines
#     it writes by dev/varka_precommit.sh --range, since `git commit --no-verify`, a rebase or a
#     cherry-pick skips the commit-time hooks.
#
#   * A push of the branch checked out also runs dev/varka_push_check.py's rules that need no
#     JVM (VARKA-563), seconds: VARKA_PUSH_CHECK=fast, the default; full adds the suites the
#     branch's changed files imply; off runs nothing more.
#
# Only the branch's own commits are checked: never one Varka's master (origin/master) or Apache
# Spark's (upstream/master) already has, which a merge of either brings in and which follow their
# own rules; and the lines are the branch's change since its merge base with Varka's master.
set -uo pipefail
case "${1:-}" in -h|--help) sed -n '18,/^[^#]/p' "$0" | sed '$d'; exit 0 ;; esac
remote="${1:-}"
url="${2:-}"
root="$(git rev-parse --show-toplevel)"
cd "$root" || exit 1

zero=0000000000000000000000000000000000000000
status=0
while read -r local_ref local_sha remote_ref remote_sha; do
  [ "$local_sha" = "$zero" ] && continue # a deletion pushes no commits
  if [[ "$url" =~ vecbricks/varka(\.git)?$ ]] && [[ "$remote_ref" == refs/heads/* ]] \
      && [ "$remote_ref" != "refs/heads/master" ]; then
    echo "varka pre-push: refusing to push $local_ref to $remote ($url), Varka's own repository:"
    echo "  push a pull request branch to your fork (CLAUDE.md, 'Pull Request Workflow')"
    status=1
    continue
  fi
  [[ "$local_ref" == refs/tags/* ]] && continue
  base="$(git merge-base "$local_sha" origin/master 2> /dev/null)" || base=""
  [ -n "$base" ] || continue
  range="$base..$local_sha"
  if [ "$(git rev-list --count "$range")" -eq 0 ]; then continue; fi
  # Commits the remote already has were checked when they were pushed.
  known=(origin/master upstream/master)
  if [ "$remote_sha" != "$zero" ] && git cat-file -e "$remote_sha" 2> /dev/null; then
    known+=("$remote_sha")
  fi
  if [ -x dev/varka_msg_check.py ]; then
    out="$(dev/varka_msg_check.py range "$range" --not "${known[@]}" 2>&1)" || {
      echo "$out" | sed 's/^/varka pre-push: /'
      status=1
    }
  fi
  # The files are read from the working tree, which is the pushed commit's in the usual push of
  # the branch checked out; a push of another ref is checked against this tree's files.
  out="$(dev/varka_precommit.sh --range "$range" 2>&1)" || {
    echo "$out" | sed 's/^/varka pre-push: /'
    status=1
  }
  case "${VARKA_PUSH_CHECK:-fast}" in
    fast|full)
      if [ "$local_sha" = "$(git rev-parse HEAD)" ]; then
        mode=(--no-precommit)
        [ "${VARKA_PUSH_CHECK:-fast}" = fast ] && mode+=(--no-jvm)
        # Its stdin is not the hook's: the refs still to read are there.
        dev/varka_push_check.py --base "$base" "${mode[@]}" < /dev/null || status=1
      else
        echo "varka pre-push: VARKA_PUSH_CHECK skipped for $local_ref, which is not checked out"
      fi ;;
  esac
done
if [ "$status" -ne 0 ]; then
  echo "varka pre-push: push refused; fix the findings above, or push with --no-verify to override"
fi
exit "$status"
