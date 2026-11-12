# VARKA-599: A sections run writes its numbers into the committed runner file

Row 599 of `PLAN.md`, added on 11 November 2026 on the owner's word from #1478's benchmark run
42900850708. The row's text is the plan; this file holds its build sheet.

## Build sheet (11 November 2026)

For a coding agent building the row in one pull request, stopping at a local commit. Follow
`sql/varka/plans/m8/BUILD_SHEETS.md` for what every sheet shares: the worktree, the heavy lock,
the environment, the commit format, the linters and the rule that the agent never pushes. Where
this sheet and that file disagree, stop and report. Everything below was read at `c62f492fc89`
(origin/master on 11 November 2026).

Branch and worktree: `varka-599` in `../varka-599`, made as BUILD_SHEETS.md section 1 item 2
says. Commit subject: `[VARKA-599] Write a sections run into the committed runner-named file`.

**What failed** (run 42900850708, job 130222589875, from its log): inputs as in B9; the
benchmark wrote the untracked `VarkaDecimalBaselineBenchmark-jdk25-results.txt`, `restore` read
only `git diff --name-only` and exited with `no regenerated file holds the sections [...]`, so
the provenance, `tar` and upload never ran.

### B0. Premise re-check

Run each from the worktree root; any other output: stop and report (B7).

1. `gh pr list --repo vecbricks/varka --state open --json number,files --jq '.[] | select(any(.files[].path; test("varka_bench_sections|workflows/benchmark.yml|build_and_test.yml"))) | .number'`
   prints `1474` and `1425` on 11 November, in either order. Of this sheet's files they share
   `build_and_test.yml`, around its proofs step (near line 1200, far from the line this row adds),
   and `PLAN.md`, in other rows' lines: rebase if #1474 or #1425 merged first, which here means
   merging master as BUILD_SHEETS.md section 8 says (a `PLAN.md` conflict is resolved as master's
   text plus this row's own line). Any other number: stop and report.
2. `grep -c -E '^def (split_sections|parse_titles|splice|committed|changed_results|restore|selftest|main)\(' dev/varka_bench_sections.py`
   prints `8`.
3. `grep -n '"git", "diff", "--name-only", "--", "\*benchmarks/\*-results.txt"' dev/varka_bench_sections.py`
   prints one line inside `changed_results`, and `grep -c 'ls-files' dev/varka_bench_sections.py`
   prints `0` (untracked files are never listed: the defect).
4. `python3 dev/varka_bench_sections.py --selftest` prints
   `varka_bench_sections: selftest passed`.
5. In `.github/workflows/benchmark.yml` (`B=.github/workflows/benchmark.yml`), each command
   prints the count after it:
   `grep -c -F 'restore --sections "$BENCHMARK_SECTIONS"' $B` -> `1` (job `benchmark`, step
   `Run benchmarks`); `grep -c -F 'id: probe' $B` -> `2`;
   `grep -c -F 'continue-on-error: ${{ inputs.expected-cpu' $B` -> `2`;
   `grep -c -F 'PROBE_RATIO: ${{ steps.probe.outputs.ratio }}' $B` -> `2`. The second of each
   pair is in the pair job (`bench-pair`), which this row does not edit.
6. `grep -n -F 'grep -qF "$expected_cpu"' dev/varka_datapath.sh` prints one line (102): the CPU check is
   a fixed-string substring match of `expected-cpu` in the CPU model, and a mismatch exits
   non-zero; the probe step's `outcome` is `success` only when the check (and the probe's own
   control) passed.
7. Committed runner files: `git ls-files 'sql/core/benchmarks/VarkaDecimalBaselineBenchmark-jdk25*'`
   prints the four `-runner-epyc7763` files (results and provenance, wide and `-128bit`) and no
   plain `-jdk25-results.txt`.
8. `git ls-files '*benchmarks/*-jdk25-runner-epyc7763-results.txt' | wc -l` prints `18`, and
   none of those 18 classes has a committed plain `-jdk25-results.txt` beside it. Check with:
   `for f in $(git ls-files '*benchmarks/*-jdk25-runner-epyc7763-results.txt'); do git ls-files --error-unmatch "${f%-runner-epyc7763-results.txt}-results.txt" 2>/dev/null; done | wc -l`
   -> `0`.
9. `grep -n 'python3 dev/varka_bench_cpu_retry.py --selftest' .github/workflows/build_and_test.yml`
   prints one line (step "The house rules' checks pass their own cases"), and
   `grep -c varka_bench_sections .github/workflows/build_and_test.yml` prints `0`: nothing in CI
   runs this script's self-test today.
10. `git branch --list 'varka-599*'` prints nothing; `test -f sql/varka/plans/m8/VARKA-599.md`
    and `test -f sql/varka/plans/m8/BUILD_SHEETS.md` succeed in the new worktree (this sheet reached master with its plan pull request; cut the
    worktree from an origin/master that holds it).

### B1. Scope

**Decision: the script works out the target file; the workflow passes only two raw facts** (the
model the CPU check passed against, and the extra JVM options). The choice is in a pure function
that `--selftest` asserts; the workflow cannot be run locally.

**`dev/varka_bench_sections.py`**:

- Imports: add `import os` after `import argparse` and `import tempfile` after `import sys`.
- Module docstring: the `restore` usage line stays, and directly below it add the line
  `        [--cpu-checked MODEL] [--java-options=OPTS]` (eight spaces, so both stay within 100
  columns); and add this paragraph after the paragraph that starts `` `splice` writes OLD``:

  ```
  A class whose committed file is named for the runner that measured it
  (`<Class>-jdk25-runner-epyc7763-results.txt`, and `-128bit` for a run at -XX:MaxVectorSize=16)
  gets its run's numbers under the plain name, `<Class>-jdk25-results.txt`. `restore` splices
  that file into the runner-named one the run stands for and removes it (or puts it back to HEAD
  where it is tracked), but only when the run's
  CPU check passed against that runner's model (--cpu-checked, which the benchmark workflow
  passes with the run's --java-options); any other run of such a class is refused (VARKA-599).
  ```

- Replace `changed_results` with a version that also lists untracked files:

  ```python
  def changed_results():
      """The results files a run wrote: those that differ from HEAD and the new, untracked ones (a
      class whose committed file is named for its runner gets its run's file under the plain name)."""
      out = []
      for cmd in (
          ["git", "diff", "--name-only", "--", "*benchmarks/*-results.txt"],
          ["git", "ls-files", "--others", "--exclude-standard", "--", "*benchmarks/*-results.txt"],
      ):
          r = subprocess.run(cmd, check=True, capture_output=True, text=True)
          out += [p for p in r.stdout.split() if p and p not in out]
      return out
  ```

- Add, after `changed_results` and before `restore`:

  ```python
  # The CPU models whose runs have committed files named for them, by the name's slug.
  RUNNER_SLUGS = {"AMD EPYC 7763": "epyc7763"}
  PLAIN = re.compile(r"^(?P<stem>.*-jdk\d+)-results\.txt$")
  VECTOR_SIZE = re.compile(r"-XX:MaxVectorSize=(\d+)")


  def runner_target(path, is_committed, cpu_checked, java_options):
      """The committed runner-named file a run's plain results file `path` stands for, as
      (target, None); (None, None) when the class has no runner-named file, so the plain file is
      restored as before; (None, why) when the run must not write into one. `is_committed` answers
      whether a path has a version at HEAD; `cpu_checked` is the expected-cpu the run's CPU check
      passed against ("" when no check passed); `java_options` is the run's extra JVM options."""
      m = PLAIN.match(path)
      if not m:
          return None, None
      stem = m.group("stem")
      held = [
          slug
          for slug in RUNNER_SLUGS.values()
          if is_committed(f"{stem}-runner-{slug}-results.txt")
          or is_committed(f"{stem}-runner-{slug}-128bit-results.txt")
      ]
      if not held:
          return None, None
      slug = RUNNER_SLUGS.get(cpu_checked)
      if slug not in held:
          return None, (
              f"{path}: the class's committed numbers are named for {held}, and this run's CPU "
              f"was checked against {cpu_checked or 'nothing'}; dispatch with expected-cpu set to "
              "that CPU"
          )
      sizes = VECTOR_SIZE.findall(java_options or "")
      width = sizes[-1] if sizes else "32"
      if width == "32":
          target = f"{stem}-runner-{slug}-results.txt"
      elif width == "16":
          target = f"{stem}-runner-{slug}-128bit-results.txt"
      else:
          return None, f"{path}: no runner-named file is kept at -XX:MaxVectorSize={width}"
      if not is_committed(target):
          return None, f"{path}: {target} has no committed version to splice into"
      return target, None
  ```

  The exact names matter: `-runner-epyc7763-results.txt` and `-runner-epyc7763-128bit-results.txt`
  only. Other committed runner names (`-runner-7763-`, `-runner-9v74-`, `-runner-arm-128bit-`,
  numbered `-2-` and `-m7-` variants) are not targets, and a class holding only those keeps
  today's behaviour.

- In `restore`, the signature becomes `def restore(titles, files, cpu_checked="", java_options=""):`,
  its docstring gains, as its last sentence before the closing `"""`, `A plain file of a class
  whose committed file is named for its runner is spliced into that file instead
  (`runner_target`) and then removed, so only the runner-named file leaves the run.`, and its
  body from `held = 0` to the end becomes (a refusal binds only a file holding the sections
  asked for, so another runner-named class under a wildcard is not refused; a tracked plain file
  goes back to HEAD rather than being deleted, so `tar` meets no deleted path):

  ```python
      held = 0
      for path in files or changed_results():
          target, refusal = runner_target(
              path, lambda p: committed(p) is not None, cpu_checked, java_options
          )
          with open(path, encoding="utf-8") as f:
              new = f.read()
          have = {t for t, _ in split_sections(new) if t}
          present = [t for t in titles if t in have]
          if refusal or target:
              # A runner-named class: the plain file never leaves the run, and a refusal only
              # binds a file that holds sections the run was asked for.
              if refusal and present:
                  raise SystemExit(refusal)
              plain = committed(path)
              if plain is None:
                  os.remove(path)
              else:
                  with open(path, "w", encoding="utf-8") as f:
                      f.write(plain)
              if not target or not present:
                  print(f"{path}: holds none of the sections; removed")
                  continue
          dest = target or path
          old = committed(dest)
          if old is None:
              print(f"{path}: no committed version, left as it is")
              continue
          if not present:
              print(f"{path}: holds none of the sections; restored to HEAD")
              result = old
          elif len(present) < len(titles):
              lacking = [t for t in titles if t not in have]
              raise SystemExit(
                  f"{path}: lacks the sections {lacking}; it has {sorted(have)}"
              )
          else:
              result = splice(old, new, titles)
              held += 1
              print(f"{dest}: sections {titles} taken from {path}, the rest as committed")
          with open(dest, "w", encoding="utf-8") as f:
              f.write(result)
      if held == 0:
          raise SystemExit(f"no regenerated file holds the sections {titles}")
  ```

- In `selftest()`, call `restore_cases(sec)` on the line before
  `print("varka_bench_sections: selftest passed")`, and add `restore_cases` right after
  `selftest` (B2 lists what it asserts):

  ```python
  def restore_cases(sec):
      """`restore` in a scratch repository: a class whose committed file is named for its runner."""
      for key in [k for k in os.environ if k.startswith("GIT_")]:
          del os.environ[key]
      d = "sql/core/benchmarks/"
      wide = d + "XBenchmark-jdk25-runner-epyc7763-results.txt"
      narrow = d + "XBenchmark-jdk25-runner-epyc7763-128bit-results.txt"
      plain = d + "XBenchmark-jdk25-results.txt"
      other = d + "YBenchmark-jdk25-results.txt"
      committed_text = "preamble\n" + sec("a", "old a\n") + sec("b", "old b\n")
      run_text = sec("a", "new a\n") + sec("b", "new b\n")
      spliced = "preamble\n" + sec("a", "old a\n") + sec("b", "new b\n")
      epyc = "AMD EPYC 7763"
      here = os.getcwd()

      def git(*args):
          subprocess.run(["git", *args], check=True, capture_output=True)

      def write(path, text):
          with open(path, "w", encoding="utf-8") as f:
              f.write(text)

      def read(path):
          with open(path, encoding="utf-8") as f:
              return f.read()

      with tempfile.TemporaryDirectory() as tmp:
          os.chdir(tmp)
          try:
              os.makedirs(d)
              for path in (wide, narrow, other):
                  write(path, committed_text)
              git("init", "-q", ".")
              git("add", "-A")
              git("-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-qm", "base")
              for cpu, options, written in (
                  (epyc, "", wide),
                  (epyc, "--add-modules jdk.incubator.vector -XX:MaxVectorSize=16", narrow),
                  (epyc, "-XX:MaxVectorSize=32", wide),
              ):
                  write(plain, run_text)
                  restore(["b"], [], cpu, options)
                  assert read(written) == spliced, (options, read(written))
                  assert not os.path.exists(plain), options
                  for path in (wide, narrow):
                      if path != written:
                          assert read(path) == committed_text, (options, path)
                  git("checkout", "-q", "--", ".")
              for cpu, options in (("", ""), ("AMD EPYC 9V74", ""), (epyc, "-XX:MaxVectorSize=64")):
                  write(plain, run_text)
                  try:
                      restore(["b"], [], cpu, options)
                      raise AssertionError(f"expected a refusal at {cpu!r} {options!r}")
                  except SystemExit as e:
                      assert "XBenchmark-jdk25-results.txt" in str(e), e
                  assert read(wide) == committed_text and read(narrow) == committed_text
                  os.remove(plain)
              # A class with no runner-named file: its own committed file, spliced as before; an
              # unchecked run's plain file of a runner-named class without the sections goes.
              write(other, run_text)
              write(plain, sec("z", "z\n"))
              restore(["b"], [], "", "")
              assert read(other) == spliced and not os.path.exists(plain)
          finally:
              os.chdir(here)
  ```

- In `main()`, the `restore` parser gains `p.add_argument("--cpu-checked", default="")` and
  `p.add_argument("--java-options", default="")` after its `--sections` line, and the last
  branch calls `restore(titles, args.files, args.cpu_checked, args.java_options)`.
- Then run `ruff format dev/varka_bench_sections.py` and keep the file as it reformats it.

**`.github/workflows/benchmark.yml`**, job `benchmark`, step `Run benchmarks` only:

- In its `env:` block, after `PROBE_RATIO: ${{ steps.probe.outputs.ratio }}`, add:

  ```yaml
          # The expected-cpu the probe's CPU check passed against, empty when none passed: with
          # expected-cpu blank the probe may fail and the job go on (continue-on-error above).
          CPU_CHECKED: ${{ steps.probe.outcome == 'success' && inputs.expected-cpu || '' }}
  ```

  (`outcome`, not `conclusion`: under `continue-on-error` the conclusion is always `success`.)
- Replace the line
  `python3 "$TOOLS/dev/varka_bench_sections.py" restore --sections "$BENCHMARK_SECTIONS"` with
  the two lines:

  ```bash
            python3 "$TOOLS/dev/varka_bench_sections.py" restore --sections "$BENCHMARK_SECTIONS" \
              --cpu-checked "$CPU_CHECKED" --java-options="$EXTRA_JAVA_OPTIONS"
  ```

  `--java-options=` takes the `=` form because the value starts with `-`, which argparse would
  read as another option.
- Nothing else in the workflow changes: the provenance loop already reads `git diff --name-only`
  and so finds the modified runner-named file and writes
  `<Class>-jdk25-runner-epyc7763[-128bit]-provenance.txt` beside it, and the `tar` line no longer
  sees the removed plain file.

**`.github/workflows/build_and_test.yml`**: in step "The house rules' checks pass their own
cases", after `python3 dev/varka_bench_cpu_retry.py --selftest`, add
`python3 dev/varka_bench_sections.py --selftest` (same indentation), so the oracle runs on
every Build.

**The plan's record** (in this commit): in `PLAN.md` row 599 replace the opening
`**Planned** (` with `**Done** (built on varka-599: restore splices a plain
results file into the EPYC 7763 runner-named file when the CPU check passed, refuses otherwise,
self-test in the Build; the dispatch that proves it on a runner is the owner's; ` and leave the
rest of the row as it is.

What the refusal covers: a run whose CPU check did not pass or was not asked for (blank
`expected-cpu`, so `CPU_CHECKED` is empty) and a run checked against another model (say
`expected-cpu` = `AMD EPYC 9V74` on a 9V74). A run whose CPU differs from a set `expected-cpu`
already fails at the probe, before the build; that is unchanged and is the intended outcome.
`RUNNER_SLUGS` keys on the exact string `AMD EPYC 7763`, which run 42900850708 used: a shorter
`expected-cpu` such as `EPYC 7763` passes the probe's substring check but is refused here, on
purpose.

**OUT OF SCOPE**:

- Runs without `sections`: `restore` is not called and a class with runner files still gets a
  plain file in its artifact, as today.
- Runner names other than `epyc7763` (`RUNNER_SLUGS` has one entry; adding a CPU is a later row).
- Keeping a refused run's numbers in the artifact: a refusal fails the step before `tar`, as the
  failure of run 42900850708 did.
- The `bench-pair` job, `dev/varka_bench_regen.sh` (the laptop's splice), `varka_datapath.sh`.
- Renaming or moving any committed results or provenance file.

### B2. Tests

All in `--selftest`'s `restore_cases`, whose oracle is fixture text it writes itself (`spliced`
is built by hand, not by `splice`): (1) a wide run on a checked EPYC 7763 splices the wide runner
file, removes the plain file and leaves `-128bit` alone; (2) `-XX:MaxVectorSize=16` does the same
to `-128bit`; (3) `=32` is (1) again; (4) to (6) an unchecked CPU, `AMD EPYC 9V74` and `=64`
are refused naming the plain file, both runner files unchanged; (7) a class with no runner file
is spliced as today, and in the same call an unchecked run's plain file of a runner-named class
that holds none of the sections is removed, not refused. The existing asserts of `selftest()` stay and must pass.

### B3. Planted fault

1. In `runner_target`, change `elif width == "16":` to `elif width == "8":`. Run
   `python3 dev/varka_bench_sections.py --selftest`. Expected last line (no traceback, since
   `SystemExit` prints only its message):
   `sql/core/benchmarks/XBenchmark-jdk25-results.txt: no runner-named file is kept at -XX:MaxVectorSize=16`,
   exit status `1`, and no `selftest passed` line. Revert.
2. In `changed_results`, delete the `ls-files` command from the tuple. Run the self-test.
   Expected last line: `no regenerated file holds the sections ['b']`, exit status `1`.
   Revert. (This is run 42900850708's failure, reproduced.)

### B4. Commands, in order

1. `python3 dev/varka_bench_sections.py --selftest` -> exactly these five lines (B2 tests 1, 2,
   3 and 7; the refused cases print nothing), with `D=sql/core/benchmarks/`:
   `${D}XBenchmark-jdk25-runner-epyc7763-results.txt: sections ['b'] taken from ${D}XBenchmark-jdk25-results.txt, the rest as committed`,
   the same with `-runner-epyc7763-128bit-results.txt` first, the first line again, and
   `${D}YBenchmark-jdk25-results.txt: sections ['b'] taken from ${D}YBenchmark-jdk25-results.txt, the rest as committed`
   and `${D}XBenchmark-jdk25-results.txt: holds none of the sections; removed`; then
   `varka_bench_sections: selftest passed`, exit `0`.
2. `ruff check dev && ruff format --check dev` (BUILD_SHEETS.md section 6 item 4, with its
   section 2 `PATH`) -> `All checks passed!` and `... files already formatted`, no file named.
3. `python3 dev/varka_bench_sections.py restore --sections b --cpu-checked "" --java-options=`
   in the clean worktree -> `no regenerated file holds the sections ['b']`, exit `1` (the CLI
   parses both new flags).
4. `actionlint -oneline .github/workflows/benchmark.yml | wc -l` -> `20` and
   `actionlint -oneline .github/workflows/build_and_test.yml | wc -l` -> `60`, the counts on
   `c62f492fc89` (pre-existing shellcheck notes; the edit adds none). If master has moved, run
   both on origin/master first and expect those numbers instead.
5. `python3 -c 'import yaml; yaml.safe_load(open(".github/workflows/benchmark.yml"))'` -> no
   output, exit `0`.
6. B3, both steps. Revert each by undoing its one edit by hand (never `git checkout` the file,
   which would drop B1's work); after each revert, B4 step 1 passes again.
7. The plan record of B1 and the Outcome section BUILD_SHEETS.md section 7 asks for.
8. BUILD_SHEETS.md section 6, items 1 to 6 in its order, on that final tree (only ruff applies of
   its language linters). Its last item, `python3 dev/varka_push_check.py`, is expected to choose
   no suite (no Varka source changed) and pass its R4 checks. Then the commit.

### B5. Done when

B4 steps 1 to 5 print what they say, the push check exits `0`, and `git diff origin/master
--stat` lists exactly `dev/varka_bench_sections.py`, `.github/workflows/benchmark.yml`,
`.github/workflows/build_and_test.yml`, `sql/varka/plans/m8/PLAN.md` and
`sql/varka/plans/m8/VARKA-599.md` (its Outcome section).

### B6. Size

About 170 counted lines: the script about 150 after `ruff format`, the workflows 7, the plan
record and the Outcome section about 10. Far under the 1000-line cap.

### B7. Stop rules

Stop and report, without guessing, when:

- any B0 check prints something other than its expected output;
- a class has both a plain `-jdk25-results.txt` and a runner-named file committed (B0 check 8
  no longer prints `0`);
- `benchmark.yml` has a second `restore` call, or the probe step lost `id: probe` or its
  `continue-on-error`;
- the self-test fails in a case B2 does not list, or an existing assert fails;
- `actionlint` reports more lines than on origin/master;
- you would need to dispatch a workflow to check something: that is the owner's (B9).

### B8. Decisions already taken

Taken in this sheet on 11 November 2026: the script computes the target and the workflow passes
only `--cpu-checked` (from `steps.probe.outcome == 'success' && inputs.expected-cpu`) and
`--java-options`, because only the script can be tested before a dispatch; the width is the last
`-XX:MaxVectorSize` (`16` -> `-128bit`, none or `32` -> wide, else refused); a refusal is an
exit with a message (the row: "refuses rather than writing"); the self-test joins the Build.

### B9. What the owner must do afterwards

After the lead pushes and the pull request merges: one dispatch of `benchmark.yml` on
vecbricks/varka from a ref that holds this change (master after the merge; the workflow file and,
with `pr` set, the scripts in `.varka-tooling` both come from the dispatched ref), with run
42900850708's inputs: `class` = `*.VarkaDecimalBaselineBenchmark`, `jdk` = `25`, `pr` = the
pull request whose numbers are wanted (1478 for the original run), `expected-cpu` =
`AMD EPYC 7763`, `extra-java-options` = `-XX:MaxVectorSize=16`, and the same eight `sections`.
Expected: the step prints
`sql/core/benchmarks/VarkaDecimalBaselineBenchmark-jdk25-runner-epyc7763-128bit-results.txt: sections [...] taken from sql/core/benchmarks/VarkaDecimalBaselineBenchmark-jdk25-results.txt, the rest as committed`,
writes the `-128bit-provenance.txt` beside it, and the artifact holds those two files and no
plain `-jdk25-results.txt`. The agent does not dispatch.
