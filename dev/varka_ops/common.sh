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
# Sourced by every script in dev/varka_ops: the configuration they share (README.md lists it).
# With VARKA_OPS_HOME=~/varka-ops and the fork's remote named `fork`, the defaults reproduce the
# paths the scripts had in the owner's ~/varka-ops, where they grew (22-23 November 2026).
# shellcheck disable=SC2034  # the scripts that source this file read these variables
OPS_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
OPS=${VARKA_OPS_HOME:-$HOME/.varka-ops}
mkdir -p "$OPS"/logs "$OPS"/stamps "$OPS"/locks "$OPS"/pids "$OPS"/matrix-comments
# The worktrees sit beside the main checkout unless VARKA_WORKTREES says otherwise.
if [ -z "${VARKA_WORKTREES:-}" ]; then
  _common=$(git -C "$OPS_DIR" rev-parse --path-format=absolute --git-common-dir 2>/dev/null \
    || git rev-parse --path-format=absolute --git-common-dir 2>/dev/null)
  VARKA_WORKTREES=$(dirname "$(dirname "${_common:-.}")")
fi
REPO=${VARKA_REPO:-vecbricks/varka}
FORK_REMOTE=${VARKA_FORK_REMOTE:-fork}
VENV=${VARKA_VENV:-$HOME/.venv-ruff}
TRAILER=${VARKA_TRAILER:-Generated-by: Claude Code (Claude Opus 5.5)}

# ops_worktree <dir|name>: an existing directory as an absolute path, else <name> under
# VARKA_WORKTREES. Prints the path; the caller checks it exists.
ops_worktree() {
  local w=$1
  [ -d "$w" ] || w=$VARKA_WORKTREES/$1
  (cd "$w" 2>/dev/null && pwd) || echo "$w"
}

# ops_fork: set FORK (owner/repo) and FORK_OWNER from VARKA_FORK or the fork remote's URL. Only
# the scripts that talk to GitHub call it, so the local ones work without a fork remote.
ops_fork() {
  FORK=${VARKA_FORK:-}
  if [ -z "$FORK" ]; then
    local url
    url=$(git -C "$OPS_DIR" remote get-url "$FORK_REMOTE" 2>/dev/null \
      || git remote get-url "$FORK_REMOTE" 2>/dev/null)
    FORK=$(echo "$url" | sed -E 's#^(ssh://)?(git@|https://)github\.com[:/]##; s#\.git$##')
  fi
  [ -n "$FORK" ] || { echo "varka_ops: set VARKA_FORK=owner/repo or add a '$FORK_REMOTE' remote"
    exit 2; }
  FORK_OWNER=${FORK%%/*}
}

# ops_running <script>: how many bash processes run dev/varka_ops/<script> or an older copy of
# it, whichever way bash was started (#!/bin/bash or #!/usr/bin/env bash, any directory).
ops_running() {
  local s=${1//./\\.}
  ps -eo args= | grep -cE "^([^ ]*/)?bash ([^ ]*/)?$s( |$)"
}
