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
# pre-pr.sh <worktree> [--no-merge] [-j N]
# The one local gate before any push, fix push or PR open (22 Nov 2026: "prevent all the failures").
# Steps, in order, stopping at the first failure:
#   1. merge origin/master (stops on a conflict: resolve by hand, commit, rerun), then the push
#      check's R1 text check alone (seconds, no JVM): a test registry or match that lacks a new IR
#      node fails here, not after the regen's compile (23 Nov 2026)
#  1a. with PRE_PR_TREE_EQUAL=<sha>, between the merge and the R1 check: stamp HEAD and stop,
#      running nothing, when HEAD's tree equals that of a commit this worktree's gate stamped and
#      HEAD contains origin/master (a stacked branch after its parent squash-merged, when a
#      rehearsed merge of the two passed the full gate); otherwise log why and go on
#   2. dev/varka_regen_generated.sh --all, and commit what it changed (a clean text merge can leave
#      generated files stale: 348's push check, 22 Nov); skipped for a branch that changes no
#      input of the generated files (below). The width audit's probes get
#      PRE_PR_WIDTH_AUDIT_TIMEOUT seconds each (2400; the suite's own default is 600)
#   3. extra-gate.sh: lint-java, Error Prone, lint-scala, invariant and proof suites
#   4. dev/varka_push_check.py (its suites and the size cap)
#  4b. the skip-line recheck: every line of sql/varka/matrix/skips.tsv added or deleted since the
#      last stamp (or the merge base) reruns its suite under its configuration, and a change to
#      sql/varka/coverage.json reruns the suites drawn from it (below)
#   5. the arm sweep: every test suite the branch adds or changes, under the defaults, useAVX=2,
#      useAVX=3 and each configuration with an option a changed line of VarkaEmitOption.java,
#      VarkaEmitOptions.java or VarkaMatrixRuns.java names (23 Nov 2026: the sweep of every
#      configuration took 611 about 47 min and caught one failure in 34 sweeps since 22 Nov).
#      PRE_PR_SWEEP=all sweeps every configuration. A change to the options also sweeps
#      VarkaMatrixSuite. Three of 22 Nov's red matrices (#1617, #1619, #1625) were arms nobody
#      had run locally.
# A merge-only re-gate skips step 5 (23 Nov 2026). It is one where every first-parent commit since
# the branch's last stamp is either a merge of origin/master whose code files are exactly git's own
# merge, or a commit touching only plans, prose and generated files (NONCODE below). It runs
# steps 1-4 and sql/Test/compile for a branch touching catalyst or sql, since a clean merge can
# still break compilation (#1258, #1261), and step 4b. PRE_PR_FULL=1 or PRE_PR_SWEEP=all runs the
# sweep anyway.
# PRE_PR_DRY=1 prints the steps and the sweep configurations a run would choose and changes
# nothing: no fetch, merge, commit, log or stamp. PRE_PR_DRY_SINCE=<rev> makes a dry run decide
# from that commit instead of the last stamp, to check the decision on older history.
# On success it writes $VARKA_OPS_HOME/stamps/<dir> with HEAD, and appends "<sha> <time> <mode>" to
# stamps/<dir>.history, where the merge-only check and step 1a find the earlier stamps; open-pr.sh,
# push-ci.sh and push-no-ci.sh refuse a head without the stamp. Log: logs/pre-pr-<dir>.log
set -u
. "$(dirname "$0")/common.sh"
[ $# -ge 1 ] || { echo "usage: pre-pr.sh <worktree> [--no-merge] [-j N]"; exit 2; }
w=$(ops_worktree "$1"); shift
merge=1; J=4
while [ $# -gt 0 ]; do
  case $1 in
    --no-merge) merge=0;;
    -j) [ $# -ge 2 ] || { echo "pre-pr: -j needs a number"; exit 2; }; J=$2; shift;;
  esac
  shift
done
DRY=${PRE_PR_DRY:-0}
n=$(basename "$w"); L=$OPS/logs/pre-pr-$n.log
# a dry run leaves the log alone: a live gate for the same worktree may be writing it
[ "$DRY" = 1 ] || : > "$L"
export SPARK_LOCAL_IP=127.0.0.1 PATH=$VENV/bin:$PATH VARKA_HEAVY_LOCK=none
cd "$w" || { echo "pre-pr: no worktree $w"; exit 2; }
# regen and the push check leave a stray untracked file `none` (VARKA_HEAVY_LOCK=none); remove it
# on any exit
[ "$DRY" = 1 ] || trap 'rm -f "$w/none"' EXIT
say() { echo "pre-pr: $*"; [ "$DRY" = 1 ] || echo "pre-pr: $*" >> "$L"; }
die() { say "FAILED at $*"; exit 1; }
would() { echo "pre-pr (dry): would $*"; }

# The files a merge-only re-gate may carry: plans, prose and the generated files. The matrix's
# skip list is hand-written and decides which arm failures pass, so it counts as code.
NONCODE='^sql/varka/plans/|\.md$|^sql/varka/(coverage|emitted_bytes|width_audit)\.json$'
NONCODE+='|^sql/varka/[^/]*\.tsv$|^sql/varka/matrix/[^/]*\.tsv$|^sql/varka/fuzz/types\.tsv$'
code_files() { grep -vE "$NONCODE" | grep -vE '^$|^sql/varka/matrix/skips\.tsv$'; }
SWEEP=${PRE_PR_SWEEP:-}
case $SWEEP in ''|all) ;; *) echo "pre-pr: PRE_PR_SWEEP is empty or all, not $SWEEP"; exit 2;; esac
# the width audit's probe budget in the regen (step 2), checked before anything runs
audit_s=${PRE_PR_WIDTH_AUDIT_TIMEOUT:-2400}
[[ $audit_s =~ ^[1-9][0-9]*$ ]] \
  || { echo "pre-pr: PRE_PR_WIDTH_AUDIT_TIMEOUT is whole seconds, not $audit_s"; exit 2; }
TE=${PRE_PR_TREE_EQUAL:-}
[ -z "$TE" ] || git rev-parse --verify -q "$TE^{commit}" > /dev/null \
  || { echo "pre-pr: PRE_PR_TREE_EQUAL names no commit: $TE"; exit 2; }
short() { echo "$*" | tr '\n' ' ' | cut -c1-200; }

# why_full <commit>: why the commit needs the full gate; nothing when it may be merge-only. A merge
# qualifies when its second parent is on origin/master and its code files are exactly what git's
# own merge of its parents writes, which catches a hand-resolved code conflict and an evil merge
# alike; any other commit qualifies when it touches no code file.
why_full() {
  local c=$1 p auto files
  read -r -a p <<< "$(git rev-list --parents -n1 "$c")"
  if [ ${#p[@]} -gt 2 ]; then
    [ ${#p[@]} = 3 ] || { echo "${c:0:11} merges more than two parents"; return; }
    git merge-base --is-ancestor "${p[2]}" origin/master \
      || { echo "${c:0:11} merges ${p[2]:0:11}, which is not on origin/master"; return; }
    auto=$(git merge-tree --write-tree "${p[1]}" "${p[2]}" 2>/dev/null | head -1)
    [ -n "$auto" ] || { echo "${c:0:11}: git merge-tree failed"; return; }
    files=$(git diff --name-only --no-renames "$auto" "$c" | code_files)
    [ -z "$files" ] || echo "${c:0:11} is a merge whose code differs from git's: $(short "$files")"
  else
    files=$(git diff-tree --no-commit-id -r --name-only --no-renames "$c" | code_files)
    [ -z "$files" ] || echo "${c:0:11} changes code: $(short "$files")"
  fi
}

# prev_stamp: the newest stamp this worktree earned that lies on HEAD's first-parent line
prev_stamp() {
  local s fp
  fp=$(git rev-list --first-parent HEAD)
  for s in $(cat "$OPS/stamps/$n" 2>/dev/null
             tac "$OPS/stamps/$n.history" 2>/dev/null | cut -d' ' -f1); do
    echo "$fp" | grep -qx "$s" && { echo "$s"; return; }
  done
}

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  [ "$DRY" = 1 ] || die "start: the worktree has uncommitted changes"
  echo "pre-pr (dry): the worktree has uncommitted changes; a real run stops here"
fi

# 1. merge. A dry run predicts it with git merge-tree; pending holds why that merge, made by hand,
# would need the full gate.
pending=""; clean_merge=0
if [ $merge = 1 ] && [ "$DRY" = 1 ]; then
  m=$(git rev-parse --short origin/master)
  mt=$(git merge-tree --write-tree --name-only HEAD origin/master 2>/dev/null); rc=$?
  if git merge-base --is-ancestor origin/master HEAD; then
    echo "pre-pr (dry): master $m already merged (origin/master as last fetched)"
  elif [ $rc = 0 ]; then
    would "merge master $m: clean"; clean_merge=1
  elif [ $rc != 1 ]; then
    would "merge master $m, which git merge-tree could not predict"
    pending="the merge of master could not be predicted"
  else
    cf=$(echo "$mt" | sed -n '2,/^$/p' | grep -v '^$')
    would "stop at the merge of master $m: conflicts in $(short "$cf")"
    if [ -n "$(echo "$cf" | code_files)" ]; then
      pending="the merge of master conflicts in code: $(short "$(echo "$cf" | code_files)")"
    else
      echo "pre-pr (dry): every conflict is a plan, prose or generated file; resolved by hand," \
        "the merge stays merge-only"
    fi
  fi
elif [ $merge = 1 ]; then
  git fetch -q origin master || die "fetch"
  if git merge-base --is-ancestor origin/master HEAD; then
    say "master $(git rev-parse --short origin/master) already merged"
  else
    git merge -q --no-edit -m "Merge origin/master into $(git branch --show-current)

$TRAILER" origin/master >> "$L" 2>&1 \
      || { git diff --name-only --diff-filter=U; die "merge: conflict; resolve, commit, rerun"; }
    say "merged master $(git rev-parse --short origin/master)"
  fi
fi

# 1a. the tree-equal path (24 Nov 2026). PRE_PR_TREE_EQUAL=<sha> names a commit this worktree's
# gate already passed, in any mode (a line of stamps/<dir>.history). It serves a stacked branch
# whose parent squash-merged after a rehearsed merge of the two had passed the full gate: when
# HEAD, after the merge above, holds exactly that commit's tree and contains origin/master, there
# is nothing new to check, so the gate logs the match and stamps HEAD with the mode
# "tree-equal:<sha>". In any other case it says why and runs the gate. A dry run ignores it, and
# PRE_PR_FULL=1 or PRE_PR_SWEEP=all overrides it.
if [ -n "$TE" ] && [ "$DRY" != 1 ]; then
  te=$(git rev-parse "$TE^{commit}")
  if [ "${PRE_PR_FULL:-0}" = 1 ] || [ "$SWEEP" = all ]; then
    say "tree-equal: ignored, PRE_PR_FULL=1 or PRE_PR_SWEEP=all asks for the gate; running it"
  elif ! grep -q "^$te " "$OPS/stamps/$n.history" 2>/dev/null; then
    say "tree-equal: ${te:0:11} was never stamped here; running the gate"
  elif [ "$(git rev-parse "HEAD^{tree}")" != "$(git rev-parse "$te^{tree}")" ]; then
    say "tree-equal: HEAD's tree differs from ${te:0:11}'s" \
      "($(git diff --name-only "$te" HEAD | head -5 | paste -sd' ' -)); running the gate"
  elif ! git merge-base --is-ancestor origin/master HEAD; then
    say "tree-equal: HEAD lacks origin/master; running the gate"
  else
    git rev-parse HEAD > "$OPS/stamps/$n"
    echo "$(git rev-parse HEAD) $(date +%F_%H:%M) tree-equal:${te:0:11}" >> "$OPS/stamps/$n.history"
    say "tree-equal to stamped ${te:0:11}: no step run; passed on $(git rev-parse --short HEAD)"
    exit 0
  fi
fi

# 1b. the registries. R1 reads the test registries and matches as text; R2-R4 wait for step 4,
# since the regen fixes what R3 would report now. A caller's VARKA_PUSH_CHECK_SKIP=R1 holds
# here too.
if [ "$DRY" = 1 ]; then would "run dev/varka_push_check.py's R1 text check"
else
  VARKA_PUSH_CHECK_SKIP="R2,R3,R4,${VARKA_PUSH_CHECK_SKIP:-}" \
    python3 dev/varka_push_check.py --no-jvm --dry-run >> "$L" 2>&1 \
    || die "the push check's R1 text check (see $L)"
  say "R1 text check passed"
fi

# 2. regen. A branch whose own changes touch no input of the generated files cannot leave them
# stale (master's are kept current at each merge), so its regen, whose width audit alone can take
# an hour on a laptop, is skipped (23 Nov 2026); ops_regen_inputs in common.sh says what an input
# is.
# The width audit's probes may take childTimeoutSeconds each, 600 s unless set. Beside a second
# gate they ran past it (23 Nov 2026: the 30-standins, 611 and 343b2a gates); 2400 s is what
# 103a2's regen used, and passed. JDK_JAVA_OPTIONS reaches the forked test JVM, and is set for the
# regen alone, so extra-gate and the sweep do not inherit it.
audit_opt=-Dvarka.widthAudit.childTimeoutSeconds=$audit_s
regen_jopts=${JDK_JAVA_OPTIONS:+$JDK_JAVA_OPTIONS }$audit_opt
inputs=$(ops_regen_inputs "$w")
if [ "$DRY" = 1 ]; then
  if [ -z "$inputs" ] && [ "${PRE_PR_FORCE_REGEN:-0}" != 1 ]; then
    would "skip the regen (no input of the generated files changed) and run the census check"
  else
    would "run dev/varka_regen_generated.sh --all with $audit_opt added to JDK_JAVA_OPTIONS" \
      "and commit what it changes"
  fi
else
  if [ -z "$inputs" ] && [ "${PRE_PR_FORCE_REGEN:-0}" != 1 ]; then
    say "regen skipped: the branch changes no input of the generated files" \
      "(PRE_PR_FORCE_REGEN=1 forces it)"
    python3 dev/varka_census.py --check >> "$L" 2>&1 || die "census check (see $L)"
  else
    JDK_JAVA_OPTIONS=$regen_jopts dev/varka_regen_generated.sh --all >> "$L" 2>&1 \
      || die "regen (see $L)"
  fi
  rm -f none
  if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    git add -u && git commit -q -m "Regenerate the generated files after merging master

$TRAILER" >> "$L" 2>&1 || die "regen commit"
    say "regen changed files; committed $(git rev-parse --short HEAD)"
  else say "regen: nothing changed"; fi
fi

# The merge-only decision, made after this run's own merge and regen commit.
mode=full; prev=$(prev_stamp)
if [ "$DRY" = 1 ] && [ -n "${PRE_PR_DRY_SINCE:-}" ]; then
  prev=$(git rev-parse "$PRE_PR_DRY_SINCE") || exit 2
fi
if [ "${PRE_PR_FULL:-0}" = 1 ] || [ "$SWEEP" = all ]; then why="PRE_PR_FULL=1 or PRE_PR_SWEEP=all"
elif [ -z "$prev" ]; then why="no earlier stamp on HEAD's first-parent line"; prev=none
else
  why=$(for c in $(git rev-list --first-parent "$prev..HEAD"); do why_full "$c"; done
        [ -z "$pending" ] || echo "$pending")
  [ -n "$why" ] || mode="merge-only"
fi
if [ $mode = full ]; then
  say "full gate (last stamp ${prev:0:11}): $(echo "$why" | head -3 | paste -sd';' -)"
else
  say "merge-only since ${prev:0:11}: $(git rev-list --first-parent --count "$prev..HEAD")" \
    "commit(s) of merges, plans, prose or generated files$([ $clean_merge = 0 ] \
    || echo ", and the merge this run would make")"
fi

base=$(git merge-base HEAD origin/master)
own=$(git diff --name-only --no-renames "$base" HEAD)
# A merge-only re-gate compiles the tests the skipped sweep's build would have: sql's, which hold
# catalyst's too, so master's sql code that calls a catalyst API the branch changed compiles.
mods=""
echo "$own" | grep -qE '^sql/(catalyst|core)/' && mods=sql
if [ $mode = merge-only ] && [ -n "$mods" ]; then
  if [ "$DRY" = 1 ]; then would "run build/sbt $mods/Test/compile"
  else
    build/sbt -batch "$mods/Test/compile" >> "$L" 2>&1 || die "$mods/Test/compile (see $L)"
    say "$mods/Test/compile passed"
  fi
fi

# 3, 4. the extra gate and the push check
if [ "$DRY" = 1 ]; then would "run extra-gate.sh, then dev/varka_push_check.py"
else
  "$OPS_DIR/extra-gate.sh" "$w" | tee -a "$L" | grep -q "extra-gate: passed" \
    || die "extra-gate (see $OPS/logs/extra-gate-$n.log)"
  say "extra-gate passed"
  python3 dev/varka_push_check.py >> "$L" 2>&1 || die "push check (see $L)"
  rm -f none
  say "push check passed ($(grep -o 'pull request size: [0-9]* counted lines' "$L" | tail -1))"
fi

# 4b. the skip-line recheck (24 Nov 2026), in a merge-only re-gate too. A clean merge of master
# keeps a branch's old green matrix, but master's skip-list edits, or its coverage rows, can make
# a line stale that no matrix has run with the branch: #1644 re-added a line #1643 had deleted,
# both merged clean, and #1693's option matrix failed it. So every sql/varka/matrix/skips.tsv
# line added or deleted since the last stamp (with none, since the merge base) reruns its suite
# under its configuration, and so does every line on which HEAD differs from origin/master: master
# never ran its matrix with that line, and the branch's matrix may predate the merge (b135: #1644's
# head carried a line master had deleted). A line both sides carry is master's to keep true. A dry
# run reads the merge it predicts. A deleted line whose configuration names an option that
# VarkaEmitOption.java and VarkaMatrixRuns.java no longer declare went with that option and is not
# rerun. When sql/varka/coverage.json changed, the suites of DRAWN_ANYWAY also rerun under
# driverOutputTable=false with or without a skip line, since a new draw can make a passing test
# fail there (row 644; removable once its verdict stops depending on the draw). A stale or failing
# line fails the gate here.
DRAWN_ANYWAY='VarkaGroupingBoundSuite'
tab=$'\t'
from=$base
[ -z "$prev" ] || [ "$prev" = none ] || from=$prev
to=HEAD
[ "$DRY" = 1 ] && [ $clean_merge = 1 ] && to=$(echo "$mt" | head -1)
skips=sql/varka/matrix/skips.tsv
declared() {
  local k
  for k in $(echo "$1" | tr ',' ' ' | sed 's/=[^ ]*//g'); do
    git grep -q "\"$k\"" -- 'sql/*/src/*/VarkaEmitOption.java' 'sql/*/src/*/VarkaMatrixRuns.java' \
      || return 1
  done
}
sk=$(for f in "$from" origin/master; do git diff -U0 --no-renames "$f" "$to" -- "$skips"; done \
  | grep -E '^[-+][^-+#]' | cut -f1,2 | sort -u)
gone=$(echo "$sk" | grep '^-' | cut -c2- | while IFS=$tab read -r cfg suite; do
  declared "$cfg" || printf '%s\t%s\n' "$cfg" "$suite"; done)
[ -z "$gone" ] || say "skip recheck: not rerun, their options are gone: $(short "$gone")"
sk=$(echo "$sk" | cut -c2- | grep -vxF -f <(echo "$gone" | grep . || echo $'\x01'))
if ! git diff --quiet "$from" "$to" -- sql/varka/coverage.json; then
  sk=$(echo "$sk"
       for s in ${DRAWN_ANYWAY//|/ }; do printf 'driverOutputTable=false\t%s\n' "$s"; done)
fi
sk=$(echo "$sk" | grep -v '^$' | sort -u)
if [ -n "$sk" ]; then
  while IFS=$tab read -r cfg suite; do
    [ -n "$suite" ] || continue
    f=$(git ls-files "sql/*/src/test/*/$suite.scala" "sql/*/src/test/*/$suite.java" | head -1)
    case $f in
      sql/catalyst/*) m=catalyst;;
      sql/core/*) m=sql;;
      *) say "skip recheck: $suite has no catalyst or sql source; not rerun"; continue;;
    esac
    if [ "$DRY" = 1 ]; then would "recheck the skip line: $suite under $cfg ($m)"; continue; fi
    say "skip recheck: $suite under $cfg ($m)"
    dev/varka_matrix.sh --module "$m" --config "$cfg" --suites "$suite" \
      --out target/pre-pr-skip-recheck >> "$L" 2>&1 \
      || { grep -E "FAILED|stale entry" "$L" | tail -6
           die "skip recheck: $suite under $cfg (see $L)"; }
  done <<< "$sk"
  [ "$DRY" = 1 ] || say "skip recheck passed ($(echo "$sk" | grep -c .) line(s))"
fi

stamp() {
  if [ "$DRY" = 1 ]; then echo "pre-pr (dry): would stamp HEAD ($mode)"; exit 0; fi
  git rev-parse HEAD > "$OPS/stamps/$n"
  echo "$(git rev-parse HEAD) $(date +%F_%H:%M) $mode" >> "$OPS/stamps/$n.history"
  say "passed on $(git rev-parse --short HEAD)"
  exit 0
}
if [ $mode = merge-only ]; then say "arm sweep skipped: merge-only since $prev"; stamp; fi

# 5. the arm sweep
ch=$(git diff --name-only --no-renames --diff-filter=AM "$base" HEAD)
suites() {
  echo "$ch" | grep -E "^$1/.*Suite\.(scala|java)$" | sed -E 's#.*/##; s#\.(scala|java)$##' \
    | sort -u | paste -sd, -
}
cat_s=$(suites sql/catalyst/src/test); sql_s=$(suites sql/core/src/test)
if echo "$ch" | grep -qE 'codegen/varka/VarkaEmitOption(s)?\.java$|codegen/varka/VarkaMatrix'; then
  cat_s=$(echo "$cat_s,VarkaMatrixSuite" | tr ',' '\n' | grep -v '^$' | sort -u | paste -sd, -)
  say "options changed: VarkaMatrixSuite joins the sweep"
fi
# The options the branch adds or changes: every identifier on a changed line of the option table,
# the options record or the matrix's configuration list. A configuration is chosen when its own
# option, or another name in it (denseValidityOnce=false,validityByBitmap=false,...), is one of
# them.
optf=$(echo "$own" | grep -E 'codegen/varka/(VarkaEmitOptions?|VarkaMatrixRuns)\.java$')
words=""
if [ -n "$optf" ]; then
  # shellcheck disable=SC2086  # one path a word
  words=$(git diff -U0 --no-renames "$base" HEAD -- $optf | grep -E '^[-+]' \
    | grep -vE '^(\+\+\+|---) ' | grep -oE '[A-Za-z_][A-Za-z0-9_]*' | sort -u)
fi
# choose <configurations-file>: sets cfgs, one a line, and how, why they were chosen
choose() {
  local c pair always="" named=""
  if [ "$SWEEP" = all ]; then
    cfgs=$(cat "$1"); how="every configuration (PRE_PR_SWEEP=all)"; return
  fi
  while read -r c; do
    [ -n "$c" ] || continue
    if [ "$c" = useAVX=2 ] || [ "$c" = useAVX=3 ]; then always="$always $c"; continue; fi
    for pair in ${c//,/ }; do
      echo "$words" | grep -qx "${pair%%=*}" && { named="$named $c"; break; }
    done
  done < "$1"
  if [ -n "$optf" ] && [ -z "$named" ]; then
    cfgs=$(cat "$1"); how="every configuration: the option files changed but name no option"
    return
  fi
  # shellcheck disable=SC2086  # split the space-separated lists
  cfgs=$(printf '%s\n' $always $named)
  if [ -z "$cfgs" ]; then cfgs=$(cat "$1"); how="every configuration: none chosen"; return; fi
  how="the defaults; useAVX=2, useAVX=3 always"
  [ -z "$named" ] \
    || how="$how; named on changed lines of $(echo "$optf" | sed 's#.*/##' | paste -sd, -):$named"
}
swept=0
for m in catalyst sql; do
  s=$cat_s; [ $m = sql ] && s=$sql_s
  [ -n "$s" ] || continue
  out=target/pre-pr-sweep-$m
  if [ "$DRY" = 1 ]; then
    # this worktree's last list, else the newest another worktree holds (the real run lists its
    # own after the build, and a branch that adds an option lists one more)
    list=""
    for f in "$out/configurations" $(ls -t "$VARKA_WORKTREES"/*/$out/configurations 2>/dev/null)
    do [ -s "$f" ] && { list=$f; break; }; done
    if [ -z "$list" ]; then would "sweep $m: $s (no configuration list cached)"; continue; fi
    choose "$list"
    would "sweep $m: $s; $(echo "$cfgs" | grep -c .) of $(grep -c . "$list") configurations" \
      "($how; list from $list):"
    echo "$cfgs" | paste -sd' ' - | fold -s -w 96 | sed 's/^/    /'
    continue
  fi
  if [ "$SWEEP" = all ]; then
    say "arm sweep, $m: $s (every configuration, PRE_PR_SWEEP=all, -j $J)"
    run=(--all)
  else
    # --list builds and writes $out/configurations; the sweep then reuses that build
    dev/varka_matrix.sh --list --module $m --out $out >> "$L" 2>&1 \
      || die "listing the configurations, $m (see $L)"
    choose "$out/configurations"
    say "arm sweep, $m: $s; $(echo "$cfgs" | grep -c .) of" \
      "$(grep -c . "$out/configurations") configurations ($how), -j $J"
    say "  configurations: $(echo "$cfgs" | paste -sd' ' -)"
    run=(--skip-build)
    while read -r c; do [ -z "$c" ] || run+=(--config "$c"); done <<< "$cfgs"
  fi
  dev/varka_matrix.sh "${run[@]}" -j "$J" --module $m --suites "$s" --out $out >> "$L" 2>&1 \
    || { sed -n '/^configuration/,$p' "$L" \
           | grep -E "FAILED|stale|^ *[a-zA-Z].* [1-9][0-9]* +[0-9]+ +[0-9]+ +[1-9]" | head -20
         die "arm sweep $m (see $L)"; }
  swept=1
done
if [ "$DRY" != 1 ]; then
  [ $swept = 1 ] || say "arm sweep: the branch adds or changes no suite; nothing swept" \
    "(a main-code-only change: name its suites and run varka_matrix.sh --all --suites by hand)"
  rm -f none
elif [ -z "$cat_s$sql_s" ]; then
  echo "pre-pr (dry): no suite added or changed; nothing to sweep"
fi
stamp
