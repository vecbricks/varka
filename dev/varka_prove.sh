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
# The SMT proofs under sql/varka/proofs/ (VARKA-240), run by a solver with every verdict checked.
#
#   dev/varka_prove.sh                          # every proof under Z3
#   dev/varka_prove.sh --lint                   # as the linters' job runs them: CVC5_IN_LINT's under
#                                               # cvc5, NIGHTLY_ONLY's skipped, every other under Z3
#   dev/varka_prove.sh --solver both            # under Z3 and cvc5, each held to every expectation
#   dev/varka_prove.sh --solver cvc5 int_mulhi_divide.smt2
#   dev/varka_prove.sh --nightly-only           # NIGHTLY_ONLY's under both (or --solver's), as
#                                               # the nightly does
#   dev/varka_prove.sh --install z3|cvc5|both   # the pinned solvers, into target/varka-solvers/
#   dev/varka_prove.sh --install-shared z3|cvc5|both  # the same, into the shared directory below
#   dev/varka_prove.sh --self-test              # one planted failure of each kind, every one caught
#   dev/varka_prove.sh --self-test-fetch        # the download's cache, checksum, retries, prune
#   dev/varka_prove.sh --cache-key              # the pins in one line, CI's key for the archives
#   dev/varka_prove.sh --prune-downloads        # drop the archives no pin names, before CI saves
#
# A proof opens with its (set-logic ...) line, and java.smt2, Java's operators, is inserted after
# it, since SMT-LIB has no include. Each check is an (echo "<what it shows>: expect sat|unsat")
# followed by its (check-sat), so every verdict is held to the expectation before it. A run fails
# on a verdict other than the expected one; on unknown, which is also what a check past its time
# limit answers; on any output line that is neither an expectation nor a verdict, since Z3 goes on
# answering after an error, and an assertion it dropped can turn a refutation into sat
# (VARKA-240.md 2.5); on fewer verdicts than the file has checks; and on a solver that is asked for
# and is missing or not at its pinned version. Under --solver both every check must meet its
# expectation under each solver, so the two agree.
#
# A header line "; prelude: <name>.smt2" before the set-logic line asks for a second prelude of
# this directory, inserted after java.smt2 (VARKA-506 2.5); a file named so is a prelude, which is
# not run on its own, and is refused when named to run, as java.smt2 is. The name is a plain file
# name of the proof's own directory, not a path and not java.smt2; a run with no prelude (the
# self-test's bit-vector files) inserts no second one either.
#
# A solver is taken from $VARKA_Z3 or $VARKA_CVC5, else from target/varka-solvers/, else from the
# shared directory every worktree of a machine can read ($VARKA_SOLVERS_DIR, by default
# ~/.cache/varka-solvers/, laid out as target/varka-solvers/ is), else from PATH.
# VARKA_PROVE_LIMIT_MS bounds each check (default 10000) and VARKA_PROVE_RUN_LIMIT each
# file's run under one solver, in seconds (default 300); a NIGHTLY_ONLY file's defaults are 30000
# and 600. Exit status 0 when every proof holds.
#
# An install downloads the archives into <solvers>/downloads/, which the linters' job caches
# under the --cache-key line; an archive already there is used when its SHA-256 matches the pin,
# and is downloaded again otherwise. A download that fails, or whose bytes fail the checksum, is
# tried four times, ten, thirty and sixty seconds apart. CI ($GITHUB_ACTIONS) and the shared
# directory keep the archives; a worktree's own install outside CI deletes them once installed.
set -uo pipefail
# Usage text is found rather than numbered (see dev/varka_nightly.sh): from the line after the
# license to the first line that is not a comment.
usage() { sed -n '18,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }

root="$(cd "$(dirname "$0")/.." && pwd)"
proofs="$root/sql/varka/proofs"
solvers="$root/target/varka-solvers"
shared="${VARKA_SOLVERS_DIR:-${HOME:-/nonexistent}/.cache/varka-solvers}"
# The pinned solvers (VARKA-240.md 2). Z3 from PyPI, whose wheel ships the z3 binary; cvc5 from
# its release archive, since its wheel has the Python bindings only.
Z3_VERSION=5.1.0
CVC5_VERSION=1.4.1
CVC5_ZIP=cvc5-Linux-x86_64-static.zip
CVC5_SHA256=2f8efe58fe27ba7bccbb504533f690b9312d69da14192712460e4a19231f02a1
# The name cvc5's archive is kept under, with its version, since the release's own name has none.
CVC5_KEPT=cvc5-$CVC5_VERSION-Linux-x86_64-static.zip
# The Linux x86_64 wheel of Z3, the platform the linters' job runs on, by its PyPI file URL; any
# other platform, and outside CI one where that wheel fails, installs from pip's index by version.
Z3_WHEEL=z3_solver-$Z3_VERSION.0-py3-none-manylinux_2_27_x86_64.whl
Z3_WHEEL_URL=https://files.pythonhosted.org/packages/34/de/\
30329041d9a2dda11308576a80b5db17060e4b03a7ba7f550437fb38dd6b/$Z3_WHEEL
Z3_WHEEL_SHA256=dfad9e309d7010b1ff6bdb33f21570a1603ef4727373221c7117a74448f0cfef
# The seconds between a failed download and the next try; the self-test sets them to zero.
fetch_delays=(10 30 60)
# The platform both pinned archives are built for.
linux_x86_64() { [ "$(uname -s)-$(uname -m)" = Linux-x86_64 ]; }
# Whether this runs in GitHub Actions, which bounds a download's time and keeps its archives.
in_ci() { [ -n "${GITHUB_ACTIONS:-}" ]; }
# The proofs the linters' job runs under cvc5 rather than Z3, to keep the step inside its minute:
# long_divide.smt2 takes Z3 59 s on the runner and cvc5 a third of that (VARKA-241.md 9.5).
CVC5_IN_LINT=(long_divide.smt2)
# The proofs --lint skips, and refuses when named, for the nightly's run under both solvers
# (varka-fuzz.yml's proofs job, --nightly-only):
# fp_half_up.smt2 takes Z3 51 to 128 s and cvc5 88 to 208 s on the laptop, by its load
# (VARKA-342.md 5.2).
NIGHTLY_ONLY=(fp_half_up.smt2)
limit_ms="${VARKA_PROVE_LIMIT_MS:-10000}"
run_limit="${VARKA_PROVE_RUN_LIMIT:-300}"
# A NIGHTLY_ONLY file's limits where the environment sets none: cvc5 left one check of
# fp_half_up.smt2 unknown at an 8 s limit and Z3 one past 10 s on a loaded laptop
# (VARKA-342.md 5.2), too near the defaults for a runner. Two runs at the run limit fit the
# nightly job's 30 minutes.
nightly_limit_ms="${VARKA_PROVE_LIMIT_MS:-30000}"
nightly_run_limit="${VARKA_PROVE_RUN_LIMIT:-600}"

# ---------------------------------------------------------------------------------------------
# The solvers.
# ---------------------------------------------------------------------------------------------

find_solver() {
  local name="$1" given rel dir
  case "$name" in
    z3) given="${VARKA_Z3:-}"; rel="z3-$Z3_VERSION/bin/z3" ;;
    cvc5) given="${VARKA_CVC5:-}"; rel="cvc5-$CVC5_VERSION/bin/cvc5" ;;
  esac
  if [ -n "$given" ]; then
    [ -x "$given" ] && echo "$given"
    return 0
  fi
  # The worktree's own install first, then the shared one, so a fresh worktree finds the
  # solvers another one installed without an install of its own.
  for dir in "$solvers" "$shared"; do
    if [ -x "$dir/$rel" ]; then
      echo "$dir/$rel"
      return 0
    fi
  done
  command -v "$name" || true
}

# Prints the solver's path, or says why there is none and fails.
require_solver() {
  local name="$1" path version want
  path="$(find_solver "$name")"
  if [ -z "$path" ]; then
    echo "varka_prove: $name is not installed; run dev/varka_prove.sh --install $name" \
      "(or --install-shared $name for every worktree), or point VARKA_${name^^} at one" >&2
    return 1
  fi
  case "$name" in
    z3) version="$("$path" --version | awk '{print $3}')"; want="$Z3_VERSION" ;;
    cvc5) version="$("$path" --version | awk 'NR == 1 {print $2}')"; want="$CVC5_VERSION" ;;
  esac
  if [ "$version" != "$want" ]; then
    echo "varka_prove: $path is $name $version, and the proofs are pinned to $want;" \
      "run dev/varka_prove.sh --install $name (or --install-shared $name)" >&2
    return 1
  fi
  echo "$path"
}

# Waits $1 seconds before a retry of a download; the self-test replaces it.
fetch_wait() { sleep "$1"; }

# Puts the file at $1 into $2, unless $2 is there already with the SHA-256 $3. A failed transfer,
# or one whose bytes fail the checksum (a truncated or proxied response), is tried again, four
# tries in all. In CI a transfer is cut off after five minutes or a minute under 10 KB/s, so a
# stalled one is retried; elsewhere only a transfer stalled for two minutes is, so a slow link
# still installs. Each download goes to a file of its own beside $2, so installs running at once
# into the shared directory do not write into each other's, and only a checked file is renamed
# to $2.
fetch() {
  local url="$1" dest="$2" sha="$3" part delay limits
  if in_ci; then
    limits=(--max-time 300 --speed-limit 10240 --speed-time 60)
  else
    limits=(--speed-limit 1 --speed-time 120)
  fi
  if [ -f "$dest" ]; then
    echo "$sha  $dest" | sha256sum -c --quiet > /dev/null 2>&1 && return 0
    echo "varka_prove: $dest does not match its SHA-256; downloading it again" >&2
    rm -f "$dest"
  fi
  mkdir -p "$(dirname "$dest")" && part="$(mktemp "$dest.XXXXXX")" || return 1
  for delay in first "${fetch_delays[@]}"; do
    if [ "$delay" != first ]; then
      echo "varka_prove: retrying $url in $delay s" >&2
      fetch_wait "$delay"
    fi
    curl -sSfL --connect-timeout 30 "${limits[@]}" -o "$part" "$url" || continue
    if ! echo "$sha  $part" | sha256sum -c --quiet >&2; then
      echo "varka_prove: $url does not match its pinned SHA-256" >&2
      continue
    fi
    mv "$part" "$dest" && return 0
    break
  done
  rm -f "$part"
  echo "varka_prove: could not download $url to $dest" >&2
  return 1
}

# Deletes every file in the folder $1 but the pinned archives. CI restores an older key's entry
# when the pins change and saves the folder whole, so this keeps a superseded archive out of the
# new entry. Run on the worktree's folder only, never the shared one, where another worktree's
# install may be writing an archive of other pins.
prune_downloads() {
  [ -d "$1" ] || return 0
  find "$1" -maxdepth 1 -type f ! -name "$Z3_WHEEL" ! -name "$CVC5_KEPT" -print -delete
}

install_z3() {
  local dir="$solvers/z3-$Z3_VERSION" wheel="$solvers/downloads/$Z3_WHEEL"
  [ -x "$dir/bin/z3" ] && return 0
  # The wheel's one dependency is for Pythons before 3.9, which the proofs do not run under.
  if linux_x86_64; then
    fetch "$Z3_WHEEL_URL" "$wheel" "$Z3_WHEEL_SHA256" \
      && python3 -m venv "$dir" \
      && "$dir/bin/pip" install --quiet --disable-pip-version-check --no-index --no-deps "$wheel" \
      && return 0
    # CI holds to the pinned wheel. A machine that cannot reach its URL (a pip mirror only) or
    # cannot run it (glibc before 2.27) gets pip's own choice of the version from its index.
    in_ci && return 1
    echo "varka_prove: the pinned Z3 wheel did not install; installing from pip's index" >&2
    rm -rf "$dir"
  fi
  python3 -m venv "$dir" \
    && "$dir/bin/pip" install --quiet --disable-pip-version-check --retries 10 \
      "z3-solver==$Z3_VERSION.0"
}

install_cvc5() {
  local dir="$solvers/cvc5-$CVC5_VERSION" tmp status
  [ -x "$dir/bin/cvc5" ] && return 0
  if ! linux_x86_64; then
    echo "varka_prove: the pinned cvc5 build is Linux x86_64's; install cvc5 $CVC5_VERSION" \
      "and point VARKA_CVC5 at it" >&2
    return 1
  fi
  fetch "https://github.com/cvc5/cvc5/releases/download/cvc5-$CVC5_VERSION/$CVC5_ZIP" \
    "$solvers/downloads/$CVC5_KEPT" "$CVC5_SHA256" || return 1
  tmp="$(mktemp -d)"
  unzip -q "$solvers/downloads/$CVC5_KEPT" -d "$tmp" \
    && mkdir -p "$dir/bin" && mv "$tmp/${CVC5_ZIP%.zip}/bin/cvc5" "$dir/bin/cvc5"
  status=$?
  rm -rf "$tmp"
  return "$status"
}

# ---------------------------------------------------------------------------------------------
# One file under one solver.
# ---------------------------------------------------------------------------------------------

# The verdicts against the expectations. Reads the solver's output; prints one line per failure
# and then "checks <n>"; fails when anything failed.
read_verdicts() {
  awk -v expected="$1" -v status="$2" '
    BEGIN { checks = 0; bad = 0; pending = "" }
    {
      line = $0
      if (line == "") next
      # cvc5 prints an echo in quotes, Z3 bare.
      if (line ~ /^".*"$/) line = substr(line, 2, length(line) - 2)
      if (line ~ /: expect (sat|unsat)$/) {
        if (pending != "") { print "    no verdict for \"" pending "\""; bad++ }
        pending = line
        next
      }
      if (line == "sat" || line == "unsat") {
        if (pending == "") {
          print "    a verdict with no expectation before it: " line
          bad++
          next
        }
        want = pending
        sub(/.*: expect /, "", want)
        checks++
        if (line != want) {
          print "    " line " where " want " was expected: \"" pending "\""
          bad++
        }
        pending = ""
        next
      }
      # unknown, a timeout, an error, a warning: none of them is a verdict.
      print "    " (pending != "" ? "at \"" pending "\": " : "") $0
      bad++
      if (line == "unknown" || line == "timeout") { checks++; pending = "" }
    }
    END {
      if (pending != "") { print "    no verdict for \"" pending "\""; bad++ }
      if (checks != expected) { print "    " checks " verdicts for " expected " checks"; bad++ }
      if (status != 0) {
        print "    the solver exited with status " status \
          (status == 124 ? ", past the run limit" : "")
        bad++
      }
      print "checks " checks
      exit (bad > 0)
    }'
}

# The second preludes FILE... ask for, one per line, each as its "; prelude: <name>" line before
# set-logic writes it; any such line counts, so a malformed one is refused, not skipped.
asked_preludes() {
  awk 'FNR == 1 { look = 1 } /^\(set-logic / { look = 0 }
    look && /^; prelude:/ { sub(/^; prelude: */, ""); print ($0 == "" ? "(none)" : $0); look = 0 }
  ' "$@"
}

# Whether FILE is a prelude: java.smt2, or a file a proof beside it names as its second.
is_prelude() {
  local base
  base="$(basename "$1")"
  [ "$base" = java.smt2 ] || asked_preludes "$(dirname "$1")"/*.smt2 | grep -qxF -- "$base"
}

# run_file SOLVER PATH FILE [PRELUDE]: runs FILE, with PRELUDE (java.smt2 unless given, none when
# empty) and the second prelude it asks for inserted after its set-logic line, prints a line for
# it, and fails if anything failed.
run_file() {
  local name="$1" solver="$2" file="$3" prelude="${4-$proofs/java.smt2}"
  local input expected start out status report rc ms checks second
  if is_prelude "$file"; then
    printf '%-5s %-28s refused: a prelude, which is not run on its own\n' \
      "$name" "$(basename "$file")"
    return 1
  fi
  second=""
  [ -n "$prelude" ] && second="$(asked_preludes "$file")"
  if [ -n "$second" ]; then
    if [[ ! "$second" =~ ^[A-Za-z0-9_]+\.smt2$ ]] || [ "$second" = java.smt2 ]; then
      printf '%-5s %-28s refused: asks for the prelude %s, not a file name beside java.smt2\n' \
        "$name" "$(basename "$file")" "$second"
      return 1
    fi
    if [ ! -f "$(dirname "$file")/$second" ]; then
      printf '%-5s %-28s asks for the prelude %s, which is not beside it\n' \
        "$name" "$(basename "$file")" "$second"
      return 1
    fi
    second="$(dirname "$file")/$second"
  fi
  input="$(mktemp --suffix=.smt2)"
  if ! awk -v prelude="$prelude" -v second="$second" '
      { print }
      /^\(set-logic / && !done {
        if (prelude != "") { while ((getline l < prelude) > 0) print l }
        if (second != "") { while ((getline l < second) > 0) print l }
        done = 1
      }
      END { exit !done }' "$file" > "$input"; then
    printf '%-5s %-28s no (set-logic ...) line to insert the prelude after\n' \
      "$name" "$(basename "$file")"
    rm -f "$input"
    return 1
  fi
  expected="$(grep -c -E '^\(echo ".*: expect (sat|unsat)"\)$' "$file")"
  local -a args
  case "$name" in
    z3) args=(-smt2 "-t:$limit_ms" "$input") ;;
    cvc5) args=(--lang=smt2 --incremental "--tlimit-per=$limit_ms" "$input") ;;
  esac
  start="$(date +%s%N)"
  out="$(timeout "$run_limit" "$solver" "${args[@]}" 2>&1)"
  status=$?
  ms=$((($(date +%s%N) - start) / 1000000))
  rm -f "$input"
  report="$(printf '%s\n' "$out" | read_verdicts "$expected" "$status")"
  rc=$?
  checks="$(printf '%s\n' "$report" | tail -1 | awk '{print $2}')"
  printf '%-5s %-28s %4s checks %4d.%02d s  %s\n' "$name" "$(basename "$file")" "$checks" \
    $((ms / 1000)) $((ms % 1000 / 10)) "$([ "$rc" -eq 0 ] && echo holds || echo FAILED)"
  printf '%s\n' "$report" | sed '$d'
  return "$rc"
}

# Whether FILE is one NIGHTLY_ONLY names.
is_nightly_only() {
  local c
  for c in "${NIGHTLY_ONLY[@]}"; do [ "$(basename "$1")" = "$c" ] && return 0; done
  return 1
}

# ---------------------------------------------------------------------------------------------
# The self-test: the checker of verdicts, checked.
# ---------------------------------------------------------------------------------------------

self_test() {
  local name="$1" solver="$2" dir bad=0
  dir="$(mktemp -d)"
  cat > "$dir/control.smt2" <<'EOF'
(set-logic QF_NIA)
(echo "int addition wraps at the top of the range: expect unsat")
(push 1)
(assert (not (= (jint.add jint.MAX 1) jint.MIN)))
(check-sat)
(pop 1)
(echo "an int can be zero: expect sat")
(push 1)
(declare-const x Int)
(assert (and (jint.in x) (= x 0)))
(check-sat)
(pop 1)
EOF
  cat > "$dir/wrong.smt2" <<'EOF'
(set-logic QF_NIA)
(declare-const x Int)
(echo "a satisfiable check, expected to be refuted: expect unsat")
(push 1)
(assert (= x 1))
(check-sat)
(pop 1)
EOF
  # An undeclared symbol: Z3 reports the error, drops the assertion and answers sat - which
  # meets the expectation, so only the error line can fail this run.
  cat > "$dir/error.smt2" <<'EOF'
(set-logic QF_NIA)
(declare-const x Int)
(echo "an assertion the solver rejects: expect sat")
(push 1)
(assert (= y 1))
(check-sat)
(pop 1)
EOF
  # The bit-vector form of the multiply-high proof for 12, which no solver tried finishes in five
  # minutes (VARKA-240.md 2.1), under a one-second limit: the solver answers unknown.
  cat > "$dir/limit.smt2" <<'EOF'
(set-logic QF_BV)
(declare-const n (_ BitVec 32))
(echo "a check no solver finishes inside the limit: expect unsat")
(push 1)
(assert (not (= (bvadd ((_ extract 31 0) (bvashr (bvmul ((_ sign_extend 32) n)
  #x000000002aaaaaab) #x0000000000000021)) (bvlshr n #x0000001f)) (bvsdiv n #x0000000c))))
(check-sat)
(pop 1)
EOF
  # The same check after a (reset), as round_to_odd.smt2 runs every check: the limit is given
  # on the solver's command line, which a (reset) does not undo under either pinned solver.
  cat > "$dir/limit_reset.smt2" <<'EOF'
(set-logic QF_BV)
(echo "a check before the reset: expect sat")
(declare-const a (_ BitVec 8))
(check-sat)
(reset)
(set-logic QF_BV)
(declare-const n (_ BitVec 32))
(echo "a check no solver finishes inside the limit, after a reset: expect unsat")
(assert (not (= (bvadd ((_ extract 31 0) (bvashr (bvmul ((_ sign_extend 32) n)
  #x000000002aaaaaab) #x0000000000000021)) (bvlshr n #x0000001f)) (bvsdiv n #x0000000c))))
(check-sat)
EOF
  cat > "$dir/missing.smt2" <<'EOF'
(set-logic QF_NIA)
(echo "a check whose verdict never comes: expect sat")
EOF
  # A file asking for a second prelude, which defines what its check reads, and one asking for a
  # prelude that is not there.
  cat > "$dir/second.smt2" <<'EOF'
(define-fun spec.twice ((x Int)) Int (* 2 x))
EOF
  cat > "$dir/asks.smt2" <<'EOF'
; prelude: second.smt2
(set-logic QF_NIA)
(echo "the second prelude's definition is there, after java.smt2's: expect unsat")
(push 1)
(assert (not (= (spec.twice jint.MAX) (+ jint.MAX jint.MAX))))
(check-sat)
(pop 1)
EOF
  sed 's/second.smt2/absent.smt2/' "$dir/asks.smt2" > "$dir/asks_absent.smt2"
  sed 's/second.smt2/java.smt2/' "$dir/asks.smt2" > "$dir/asks_java.smt2"
  sed 's#second.smt2#../second.smt2#' "$dir/asks.smt2" > "$dir/asks_path.smt2"
  # A check that reads only the second prelude, so it holds with the prelude and, run with none,
  # fails only if the second is left out too.
  cat > "$dir/bare.smt2" <<'EOF'
; prelude: second.smt2
(set-logic QF_NIA)
(echo "the second prelude's definition is there: expect unsat")
(push 1)
(assert (not (= (spec.twice 3) 6)))
(check-sat)
(pop 1)
EOF
  expect_run() {
    local what="$1" want="$2"
    shift 2
    if "$@" > "$dir/out" 2>&1; then got=holds; else got=fails; fi
    if [ "$got" = "$want" ]; then
      printf '%-5s ok    %s\n' "$name" "$what"
    else
      printf '%-5s WRONG %s: the run %s\n' "$name" "$what" "$got"
      sed 's/^/      /' "$dir/out"
      bad=$((bad + 1))
    fi
  }
  expect_run "a file whose checks hold, holds" holds run_file "$name" "$solver" "$dir/control.smt2"
  expect_run "a verdict against its expectation fails" fails \
    run_file "$name" "$solver" "$dir/wrong.smt2"
  expect_run "a solver error fails, whatever verdict follows it" fails \
    run_file "$name" "$solver" "$dir/error.smt2"
  # Without the prelude, whose integers a QF_BV file cannot declare.
  past_limit() {
    local limit_ms=1000
    run_file "$name" "$solver" "$dir/limit.smt2" ""
  }
  expect_run "a check past its time limit fails" fails past_limit
  # Fails as unknown at the per-check limit, not by running on to the run limit.
  past_limit_after_reset() {
    local limit_ms=1000 run_limit=20
    run_file "$name" "$solver" "$dir/limit_reset.smt2" "" > "$dir/reset.out" 2>&1
    cat "$dir/reset.out"
    if grep -q -E 'unknown$' "$dir/reset.out" && ! grep -q 'past the run limit' "$dir/reset.out"
    then
      return 1
    fi
    return 0
  }
  expect_run "a check past its time limit after a (reset) fails as unknown" fails \
    past_limit_after_reset
  expect_run "an expectation with no verdict fails" fails \
    run_file "$name" "$solver" "$dir/missing.smt2"
  expect_run "a file asking for a second prelude has its definitions" holds \
    run_file "$name" "$solver" "$dir/asks.smt2"
  expect_run "a file asking for a prelude that is not there fails" fails \
    run_file "$name" "$solver" "$dir/asks_absent.smt2"
  # Fails only when the run is refused, by the line that says so.
  # shellcheck disable=SC2329
  refused() {
    run_file "$@" > "$dir/refused.out" 2>&1
    cat "$dir/refused.out"
    ! grep -q ' refused: ' "$dir/refused.out"
  }
  expect_run "a second prelude named to run is refused" fails \
    refused "$name" "$solver" "$dir/second.smt2"
  expect_run "java.smt2 named to run is refused" fails \
    refused "$name" "$solver" "$proofs/java.smt2"
  expect_run "a file asking for java.smt2 as its second prelude is refused" fails \
    refused "$name" "$solver" "$dir/asks_java.smt2"
  expect_run "a file asking for a prelude by a path is refused" fails \
    refused "$name" "$solver" "$dir/asks_path.smt2"
  expect_run "a file asking for a second prelude, run with the prelude, holds" holds \
    run_file "$name" "$solver" "$dir/bare.smt2"
  expect_run "a run with no prelude inserts no second prelude" fails \
    run_file "$name" "$solver" "$dir/bare.smt2" ""
  expect_run "a missing solver fails" fails \
    env "VARKA_${name^^}=$dir/no-such-solver" "$root/dev/varka_prove.sh" --solver "$name" \
    "$dir/control.smt2"
  rm -rf "$dir"
  return "$bad"
}

# The download, checked against file:// URLs with no waits between tries: an archive that matches
# is used with no download, one that does not is fetched again, a source that fails or serves
# other bytes is tried four times, one that fails once is kept on its second try; nothing but a
# checked file is left behind.
self_test_fetch() {
  local dir sha bad=0 src tries want
  dir="$(mktemp -d)"
  fetch_delays=(0 0 0)
  printf 'pinned\n' > "$dir/good"
  sha="$(sha256sum "$dir/good" | cut -d' ' -f1)"
  printf 'pinned\n' > "$dir/cached"
  if ! fetch "file://$dir/missing" "$dir/cached" "$sha" 2> /dev/null; then
    echo "varka_prove: an archive that matches its pin was downloaded again" >&2; bad=1
  fi
  printf 'corrupt\n' > "$dir/cached"
  if ! fetch "file://$dir/good" "$dir/cached" "$sha" 2> /dev/null \
      || ! cmp -s "$dir/good" "$dir/cached"; then
    echo "varka_prove: an archive that fails its pin was not replaced" >&2; bad=1
  fi
  printf 'other\n' > "$dir/other"
  # The flaky source appears during the wait after its first failed try.
  fetch_wait() { cp "$dir/good" "$dir/flaky"; }
  for src in missing other flaky; do
    rm -f "$dir/flaky"
    tries="$(fetch "file://$dir/$src" "$dir/out-$src" "$sha" 2>&1 >/dev/null | grep -c retrying)"
    case "$src" in missing) want=3 ;; other) want=3 ;; flaky) want=1 ;; esac
    if [ "$tries" != "$want" ] || ls "$dir/out-$src".?* > /dev/null 2>&1 \
        || { [ "$src" = flaky ] && ! cmp -s "$dir/good" "$dir/out-$src"; } \
        || { [ "$src" != flaky ] && [ -e "$dir/out-$src" ]; }; then
      echo "varka_prove: the $src source made $tries retries, not $want, or left a wrong file" >&2
      bad=1
    fi
  done
  mkdir "$dir/downloads"
  touch "$dir/downloads/$Z3_WHEEL" "$dir/downloads/$CVC5_KEPT" "$dir/downloads/$CVC5_ZIP"
  prune_downloads "$dir/downloads" > /dev/null
  if [ "$(ls "$dir/downloads" | sort | tr '\n' ' ')" != "$CVC5_KEPT $Z3_WHEEL " ]; then
    echo "varka_prove: the prune did not keep exactly the pinned archives" >&2; bad=1
  fi
  rm -rf "$dir"
  [ "$bad" -eq 0 ] && echo "varka_prove: the download's cache, checksum, retries and prune hold"
  return "$bad"
}

# ---------------------------------------------------------------------------------------------
# The command line.
# ---------------------------------------------------------------------------------------------

mode=prove
# The solver set, and whether --solver or --install gave one: a given empty value goes to usage
# below rather than taking the default.
which=
which_given=0
nightly=0
files=()
named=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --solver) which="${2:-}"; which_given=1; shift 2 || usage ;;
    --install) mode=install; which="${2:-}"; which_given=1; solvers="$root/target/varka-solvers"
      shift 2 || usage ;;
    --install-shared) mode=install; solvers="$shared"; which="${2:-}"; which_given=1
      shift 2 || usage ;;
    --self-test) mode=self-test; shift ;;
    --self-test-fetch) self_test_fetch; exit ;;
    --cache-key)
      echo "z3-$Z3_VERSION-${Z3_WHEEL_SHA256:0:16}-cvc5-$CVC5_VERSION-${CVC5_SHA256:0:16}"
      exit 0 ;;
    --prune-downloads) prune_downloads "$root/target/varka-solvers/downloads"; exit 0 ;;
    --lint) mode=lint; shift ;;
    --nightly-only) nightly=1; files+=("${NIGHTLY_ONLY[@]}"); shift ;;
    -*) usage ;;
    *) files+=("$1"); named+=("$1"); shift ;;
  esac
done
# --lint picks each file's solver itself; --nightly-only runs both unless --solver names one,
# in either order.
if [ "$mode" = lint ]; then
  which=both
elif [ "$which_given" = 0 ]; then
  if [ "$nightly" = 1 ]; then which=both; else which=z3; fi
fi
# --lint skips every file --nightly-only names, so the two together prove nothing and would
# still report success.
if [ "$mode" = lint ] && [ "$nightly" = 1 ]; then
  echo "varka_prove: --lint skips the NIGHTLY_ONLY files --nightly-only names;" \
    "use one or the other" >&2
  exit 2
fi
# A NIGHTLY_ONLY file named to --lint would only be skipped, proving nothing.
if [ "$mode" = lint ]; then
  for f in "${named[@]}"; do
    if is_nightly_only "$f"; then
      echo "varka_prove: --lint skips $(basename "$f"), a NIGHTLY_ONLY file;" \
        "run it without --lint" >&2
      exit 2
    fi
  done
fi
case "$which" in
  z3|cvc5) names=("$which") ;;
  both) names=(z3 cvc5) ;;
  *) usage ;;
esac

if [ "$mode" = install ]; then
  for name in "${names[@]}"; do
    "install_$name" || { echo "varka_prove: installing $name failed" >&2; exit 1; }
    path="$(require_solver "$name")" || exit 1
    echo "$name $("$path" --version | head -1 | tr -s ' ') at $path"
  done
  # The cache needs the archives in CI, and the shared directory keeps them since another
  # worktree's install of the same pins may be between its download and its unzip there.
  in_ci || [ "$solvers" = "$shared" ] || rm -rf "$solvers/downloads"
  exit 0
fi

declare -A paths
for name in "${names[@]}"; do
  paths[$name]="$(require_solver "$name")" || exit 1
done

if [ "$mode" = self-test ]; then
  failed=0
  for name in "${names[@]}"; do
    self_test "$name" "${paths[$name]}" || failed=$((failed + 1))
  done
  [ "$failed" -eq 0 ] && echo "varka_prove: the self-test caught every planted failure"
  exit "$failed"
fi

if [ "${#files[@]}" -eq 0 ]; then
  # Every proof but the preludes: java.smt2 and each file a proof names as its second.
  for f in "$proofs"/*.smt2; do
    is_prelude "$f" || files+=("$f")
  done
fi
failed=0
if [ "$mode" = lint ]; then
  for f in "${files[@]}"; do
    [ -f "$f" ] || f="$proofs/$f"
    if is_nightly_only "$f"; then
      printf '%-5s %-28s skipped: proven in the nightly\n' - "$(basename "$f")"
      continue
    fi
    name=z3
    for c in "${CVC5_IN_LINT[@]}"; do [ "$(basename "$f")" = "$c" ] && name=cvc5; done
    run_file "$name" "${paths[$name]}" "$f" || failed=$((failed + 1))
  done
else
  for name in "${names[@]}"; do
    for f in "${files[@]}"; do
      [ -f "$f" ] || f="$proofs/$f"
      if is_nightly_only "$f"; then
        limit_ms="$nightly_limit_ms" run_limit="$nightly_run_limit" \
          run_file "$name" "${paths[$name]}" "$f" || failed=$((failed + 1))
      else
        run_file "$name" "${paths[$name]}" "$f" || failed=$((failed + 1))
      fi
    done
  done
fi
if [ "$failed" -eq 0 ]; then
  echo "varka_prove: every proof holds under ${names[*]}"
else
  echo "varka_prove: $failed run(s) failed"
fi
exit "$((failed > 0))"
