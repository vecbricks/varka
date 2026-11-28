# Index of the Varka tools under `dev/`

Every entry under `dev/` whose name starts with `varka`, one bullet each, grouped by what you are
doing. `dev/varka_tools_lint.py` (the Build's docs job and the pre-commit hook run it) fails when
an entry is missing here, when a bullet names an entry that is gone, or when a bullet has no tag.
A new tool gets its bullet in the same commit.

A bullet says what the tool is for and gives its usual command line. It is not the usage: for the
other options read the tool's header, the comment block or docstring at the top of the file.
Run every command from the root of the worktree.

## The tags

Each bullet carries one of these:

- `[sbt]`: runs `build/sbt`, so it compiles what changed and then starts one or more JVMs. Count
  on minutes and on several GB of memory. Do not start one where no build is allowed.
- `[java]`: starts a JVM without a build.
- `[no JVM]`: starts neither. Most of these are Python that reads files. `needs GitHub` marks
  the ones that call it, through `gh` or through the API with `GH_TOKEN`.
- `[data]`: a file or a directory another tool reads; not run.

A tool that calls an `[sbt]` tool is tagged `[sbt]` too. A mode that only prints (`--list`,
`--dry-run`, `--self-test`, `--selftest`) starts no JVM unless the bullet says so.

## The heavy lock

The heavy lock is one file, `VARKA_HEAVY_LOCK` or else `~/proj/.varka-locks/heavy.lock`. It keeps
two heavy sbt runs from starting at the same time on one machine. Two tools take it by themselves,
with `flock`, around their own sbt call: `dev/varka_regen_generated.sh` and
`dev/varka_push_check.py` (`VARKA_HEAVY_LOCK=none` runs the second without the lock).
`dev/varka_ops/pre-pr.sh` calls both. No other entry of this index takes the lock:
`dev/varka_gate.sh`, `dev/varka_matrix.sh`, `dev/varka_emit.sh` and the benchmark tools start
their JVMs without it.

The rule: never wrap one of the two tools, or a script that calls one, in `flock` on that same
file. The inner `flock` then waits for the lock its own parent holds, and the run hangs for ever.
To run another tool under the lock, wrap that tool yourself, as `dev/varka_push_check.py` does
for the matrix: `flock ~/proj/.varka-locks/heavy.lock dev/varka_matrix.sh --config cse=false`.

## Starting a row, worktrees and long jobs

- `dev/varka_task_new.sh`: start a new row: a worktree and a branch off `origin/master`, the
  plan file from the template, the hooks installed. It refuses a row that already has a plan.
  `[no JVM]` `dev/varka_task_new.sh 37 "weekofyear by the Thursday rule"`
- `dev/varka_worktree.sh`: list the task worktrees, and remove the merged, clean ones.
  `[no JVM]` (uses `gh` when present) `dev/varka_worktree.sh list`
- `dev/varka_issues.py`: mirror the milestone plan's task table as GitHub issues; a dry run
  unless `--apply`. `[no JVM]` needs GitHub. `dev/varka_issues.py`
- `dev/varka_good_first_tasks.txt`: the rows a newcomer can finish in a day, kept by hand, read
  by `dev/varka_issues.py`. `[data]`
- `dev/varka_pr_sweep.sh`: dry-merge every open pull request against master and against each
  other, without touching the working tree. `[no JVM]` needs GitHub. `dev/varka_pr_sweep.sh`
- `dev/varka_bg.sh`: run a long job in the background and wait on it or stop it by its recorded
  PID, never by a name pattern. `[no JVM]` by itself; the job may be anything.
  `dev/varka_bg.sh start NAME CMD [ARGS...]`, then `dev/varka_bg.sh wait NAME [SECONDS]`
- `dev/varka_deadline.sh`: run a command under a deadline and stop its whole process group when
  the deadline passes. `[no JVM]` by itself.
  `dev/varka_deadline.sh 3600 build/sbt -batch 'catalyst/testOnly *Varka*'`

## Asking what the emitter and the code do

- `dev/varka_emit.sh`: what the emitter makes of a projection: the IR, the shape hash and each
  emitted method's size and op counts; `--asm` adds C2's assembly of the dense loop; `--replay`
  replays a reproduction file of a kernel failure. `[sbt]`
  `dev/varka_emit.sh "date_add(d, 7)" --columns d:date`
- `dev/varka_hsdis_build.sh`: build hsdis, the disassembler plugin that `--asm` and the assembly
  suite need, without a JDK build. Needs gcc and libcapstone-dev; clones the JDK's hsdis source
  when no `--src` is given. `[java]` (one `java -version` to check the plugin loads)
  `dev/varka_hsdis_build.sh`
- `dev/varka_members.py`: the member map of Java and Scala files: each member, its lines, its
  doc comment's first sentence, what it calls. For the inventory of a refactor. `[no JVM]`
  `dev/varka_members.py FILE... --callees`
- `dev/varka_callgraph.py`: the calls that cross between named groups of members, to choose where
  to cut a file. `[no JVM]` `dev/varka_callgraph.py A.java B.java --by-file`
- `dev/varka_unused_imports.py`: remove the imports a build log or a `dev/lint-java` log reports
  unused; it reads the log and runs no build. `[no JVM]`
  `dev/varka_unused_imports.py lint.log --apply`

## Building and testing

One suite or one test goes through sbt as the root `AGENTS.md` says. These are the larger runs.

- `dev/varka_gate.sh`: the laptop's full check in one command: every step a plan's
  "Verification" section lists, each logged, one summary table. It is not the gate before a push;
  that is `dev/varka_ops/pre-pr.sh`. `[sbt]` `dev/varka_gate.sh --list` shows the steps and
  stops; `dev/varka_gate.sh --only wide,narrow` runs a subset.
- `dev/varka_scoped_suites.sh`: one module's Varka suites under the defaults, as CI's
  varka-scoped job runs them. `[sbt]` `dev/varka_scoped_suites.sh catalyst`
- `dev/varka_new_tests.py`: list, rerun and base-check the Varka tests a branch adds. `[sbt]`
  for `fails-on-base`, which builds the base; `rerun` starts JVMs on a build that exists; `list`
  reads the diff only. `dev/varka_new_tests.py list catalyst --base origin/master`
- `dev/varka_golden_suite.sh`: Spark's golden-file suite with its queries fed to Varka, in a JVM
  of its own. `[sbt]` `dev/varka_golden_suite.sh --file date.sql --file interval.sql`
- `dev/varka_series.sh`: every small IR tree to a size, over several JVMs. `[sbt]`
  `dev/varka_series.sh 4 --variants`
- `dev/varka_platform.sh`: the fuzzer's kernels under the interpreter, C1, C2, `-Xcomp` and C2's
  stress flags, each arm in its own JVM, the answers compared. `[sbt]`
  `dev/varka_platform.sh --arms int,c2 --draws 5`
- `dev/varka_platform_report.py`: read the arms of a `dev/varka_platform.sh` run back and give
  the verdict. `[no JVM]` `dev/varka_platform_report.py target/varka-platform/<run>`
- `dev/varka_compiled_fuzz.sh`: the IR fuzzer's compiled test at volume, split over pinned JVMs.
  `[sbt]` (`--skip-build` reuses the last build)
  `dev/varka_compiled_fuzz.sh --seed 42 --draws 80 --jvms 2 --cpu-base 18`
- `dev/varka_compiled_fuzz.py`: total the run directories `dev/varka_compiled_fuzz.sh` writes.
  `[no JVM]` `dev/varka_compiled_fuzz.py RUN_DIR...`
- `dev/varka_tier_split.py`: the sql module's Varka suites with a kernel's two bodies held at
  different JIT tiers. `[sbt]` for `run`, which calls `dev/varka_matrix.sh`.
  `dev/varka_tier_split.py list`, then `dev/varka_tier_split.py run CONFIG`
- `dev/varka_nightly.sh`: the checks that need volume or an idle machine, in one command with a
  dated log under `target/varka-nightly/`: canary, fuzzers, sweeps, proofs, JIT guards, golden
  suite. Hours. `[sbt]`
  `dev/varka_nightly.sh --skip-golden --skip-platform`
- `dev/varka_sweeps.py`: check that every exhaustive sweep is listed once in
  `sql/varka/ci/sweeps.tsv`; also prints the manifest and the nightly's sbt commands. `[no JVM]`
  `dev/varka_sweeps.py --check`
- `dev/varka_session_lint.py`: flag a Varka suite that checks expressions' answers row by row
  through a session where the expression harness would do. `[no JVM]`
  `dev/varka_session_lint.py --working-tree`
- `dev/varka_new_lane.py`: check that every lane and type is wired at the sites no compiler
  checks. `[no JVM]` `dev/varka_new_lane.py --check`
- `dev/varka_errorprone.sh`: Error Prone and NullAway over Varka's Java, in a javac pass of its
  own. `[sbt]` (sbt exports the classpath; `--classpath-file FILE` takes one already exported)
  `dev/varka_errorprone.sh`
- `dev/varka_nullaway_pending.txt`: the classes NullAway still treats as unannotated; the list
  may only shrink. Read by `dev/varka_errorprone.sh`. `[data]`
- `dev/varka_prove.sh`: the SMT proofs under `sql/varka/proofs/`, run by Z3 or cvc5 with every
  verdict checked. `--install` downloads the pinned solvers. `[no JVM]`
  `dev/varka_prove.sh` (every proof under Z3)
- `dev/varka_gen_coverage.sh`: the coverage of the classes Varka emits and of the emitter, by
  the Varka suites under the JaCoCo agent; writes `sql/varka/coverage/`. `[sbt]`
  `dev/varka_gen_coverage.sh` (`--report-only` writes the report again from the last run)
- `dev/varka_gen_coverage/`: the two Java programs `dev/varka_gen_coverage.sh` compiles and runs
  for its reports. `[data]`
- `dev/varka_pit.sh`: a bounded mutation run of the emitter's classes with PIT. Long. `[sbt]`
  `dev/varka_pit.sh --classes Analysis --suites VarkaEmitterValiditySuite --methods liveWords`
- `dev/varka_pit/`: the two Java classes `dev/varka_pit.sh` plugs into PIT. `[data]`
- `dev/varka_pit_verdicts.py`: the three verdicts of a mutation run, from the two reports
  `dev/varka_pit.sh` leaves. `[no JVM]`
  `dev/varka_pit_verdicts.py BEHAVIOURAL.xml BYTES.xml REPORT.md`

## The option matrix

The matrix reruns the Varka suites once per configuration, the emit defaults with one option
changed. `dev/varka_matrix.sh` is the tool a person runs; the others plan, cut and read the
fanned-out run in CI.

- `dev/varka_matrix.sh`: run the Varka suites under one or more configurations, each in its own
  runner JVM under `target/varka-matrix/`; the defaults always run beside them. `[sbt]`,
  `--list` included; `--skip-build` reuses the last build; `--defaults --list-suites` builds
  nothing. `dev/varka_matrix.sh --config cse=false`, or one suite of one module under one
  configuration: `dev/varka_matrix.sh --module sql --config cse=false --suites <SuiteName>`
- `dev/varka_matrix_ci.sh`: what a pull request's Build runs: one module's suites under the one
  configuration `dev/varka_matrix_pick.py` picks, and on the base when that fails. `[sbt]`
  `dev/varka_matrix_ci.sh catalyst`
- `dev/varka_matrix_pick.py`: pick that one configuration from the diff and the branch name.
  `[no JVM]` `dev/varka_matrix_pick.py CONFIGURATIONS BRANCH [BASE]`
- `dev/varka_matrix_report.py`: summarise a `dev/varka_matrix.sh` run: one line a configuration,
  then what failed. `[no JVM]`
  `dev/varka_matrix_report.py target/varka-matrix/runs sql/varka/matrix/skips.tsv`
- `dev/varka_matrix_bins.py`: pack every (configuration, module, suite) unit into CI jobs
  (bins) of about the same length; its docstring defines unit, bin and lane. `[no JVM]`
  Subcommands `plan`, `times`, `select`, `replay`; `dev/varka_matrix_bins.py --selftest`
- `dev/varka_matrix_scope.py`: the suites a branch adds or changes, which the matrix's first
  stage runs under every configuration. `[no JVM]` `dev/varka_matrix_scope.py BASE HEAD --suites`
- `dev/varka_matrix_shards.py`: split the weekly matrix's configurations over its jobs by
  measured time. `[no JVM]` `dev/varka_matrix_shards.py split I/N WEIGHTS < configurations`
- `dev/varka_suite_shards.py`: spread suites over N JVMs or N CI jobs, longest first, by a
  weights file. `[no JVM]` `dev/varka_suite_shards.py N WEIGHTS_FILE SUITE...`
- `dev/varka_matrix_archive.sh`: pack a build of the test classes for the fanned-out matrix, or
  unpack one. `[no JVM]` `dev/varka_matrix_archive.sh pack BUILD_DIR TAR`
- `dev/varka_matrix_reuse.py`: find the Build run whose archive of the test classes the matrix
  can start from. A CI step. `[no JVM]` needs GitHub.
  `dev/varka_matrix_reuse.py REPOSITORY SHA [--wait MINUTES]`
- `dev/varka_matrix_run.py`: what one fanned-out matrix run cost, job by job and suite by suite.
  `[no JVM]` needs GitHub. `dev/varka_matrix_run.py RUN_ID`
- `dev/varka_matrix_stragglers.py`: how long the bins' units ran in past runs, for a bin far over
  its plan. `[no JVM]` needs GitHub.
  `dev/varka_matrix_stragglers.py history --repo OWNER/NAME RUN_ID...`
- `dev/varka_bin_units_test.py`: tests of the workflow steps that run a bin's units and a sweep,
  against stub tools. `[no JVM]` `python3 dev/varka_bin_units_test.py --selftest`

## Regenerating the generated files

- `dev/varka_regen_generated.sh`: regenerate the generated files (`coverage.json`,
  `emitted_bytes.json`, `width_audit.json`, the type and cast tables, the census, the docs
  blocks) in the order they read each other. With no argument only what the last merge owes;
  after a rebase or a code change use `--all`. `[sbt]` **Takes the heavy lock itself.**
  `dev/varka_regen_generated.sh --all` (`--all --dry-run` prints the steps and runs nothing)
- `dev/varka_regen_inputs.py`: whether a regeneration can be skipped, because the branch changes
  nothing the generated files read. `[java]` (one `java -version` to read the JDK's version)
  `dev/varka_regen_inputs.py GENERATED_FILE...`
- `dev/varka_generated_merge.py`: the git merge driver of the generated files, which records
  what a merge owes; `--check` fails on a generated file that lacks a coverage row. It prints
  the sbt commands and runs none. `[no JVM]` `dev/varka_generated_merge.py --check`
- `dev/varka_bytes_moved.py`: which pinned digests of `emitted_bytes.json` moved against a base.
  `[no JVM]` `dev/varka_bytes_moved.py origin/master --ignore option_arms`
- `dev/varka_toc.py`: rewrite or check the generated contents block of a long Markdown file.
  `[no JVM]` `dev/varka_toc.py SKILLS.md --from sql/varka/skills`
- `dev/varka_catalogue_move.sh`: move the scope catalogue forward one milestone with every
  citation of it; `--check` fails while a file cites the old path. `[no JVM]`
  `dev/varka_catalogue_move.sh --check 9 10`

## Benchmarks

Read `sql/varka/skills/benchmarking.md` before you quote a number. The tools that time code want
an idle machine and refuse a busy one.

- `dev/varka_bench_regen.sh`: regenerate one benchmark class's committed results at both vector
  widths, with provenance, and show what moved. Runs the canary first. `[sbt]`
  `dev/varka_bench_regen.sh catalyst VarkaEmitterParityBenchmark`
- `dev/varka_bench_pair.sh`: run a class on a base and on the head in alternating rounds and
  compare medians: the tool for "did this change make a kernel slower". Makes a worktree for the
  base and builds there too. `[sbt]` `dev/varka_bench_pair.sh catalyst VarkaCompileBenchmark`
- `dev/varka_bench_pair.py`: the comparison `dev/varka_bench_pair.sh` hands its files to.
  `[no JVM]` `dev/varka_bench_pair.py compare --base B1 B2 ... --head H1 H2 ...`
- `dev/varka_bench_repeat.sh`: run a class N times with nothing changed, to learn its own
  spread; `--band` writes the committed band file. `[sbt]`
  `dev/varka_bench_repeat.sh catalyst VarkaEmitterParityBenchmark 3`
- `dev/varka_bench_band.py`: the per-case spread of repeated runs, as tiers. `[no JVM]`
  `dev/varka_bench_band.py run1.txt run2.txt ...`
- `dev/varka_bench_diff.py`: compare results files row by row; `--requote` lists every document
  line that quotes a moved number. `[no JVM]`
  `dev/varka_bench_diff.py --git HEAD FILE --requote`
- `dev/varka_bench_ids.sh`: which benchmark case ids are taken and the next free one; a dry run
  of the class that times nothing. `[sbt]` `dev/varka_bench_ids.sh VarkaArithmeticBenchmark`
- `dev/varka_bench_gate.py`: assert what a results file must satisfy whatever its numbers, for
  example that the fused kernel beats the separate passes. `[no JVM]`
  `dev/varka_bench_gate.py sql/catalyst/benchmarks/VarkaEmitterParityBenchmark-jdk25-results.txt`
- `dev/varka_bench_sections.py`: replace some sections of a results file and keep the others as
  committed. `[no JVM]` `dev/varka_bench_sections.py list FILE`
- `dev/varka_bench_canary.sh`: is the machine in the state the committed files were measured
  in? About 35 seconds. A host with no baseline needs `--record` once. `[java]`
  `dev/varka_bench_canary.sh`
- `dev/varka_canary/`: the Java probes and the per-host baselines that
  `dev/varka_bench_canary.sh` and `dev/varka_datapath.sh` run and read. `[data]`
- `dev/varka_datapath.sh`: describe this machine, measure its vector datapath, and fail if it
  is not the CPU asked for. The benchmark workflow's CPU check. `[java]`
  `dev/varka_datapath.sh --require any --expected-cpu MODEL`
- `dev/varka_bench_cpu_retry.py`: rerun a benchmark workflow run whose only failures are the
  CPU check, up to a cap. A workflow's job. `[no JVM]` needs GitHub.
  `dev/varka_bench_cpu_retry.py --selftest`
- `dev/varka_bench_surface.sh`: the date-surface benchmark on several Spark distributions, one
  after another, with the table that compares them. Long. `[sbt]`
  `dev/varka_bench_surface.sh [--benchmark surface|chains|...] LABEL=SPARK_HOME:JAVA_HOME ...`
- `dev/varka_surface_shards.py`: dispatch one sharded surface run across GitHub runners and
  dispatch again the shards that miss. `[no JVM]` needs GitHub.
  `dev/varka_surface_shards.py run --shards 8 --benchmark chains --rows 100000000`
- `dev/varka_bench_merge.py`: join the shard files of one sharded surface run into one file per
  distribution. `[no JVM]`
  `dev/varka_bench_merge.py SHARD_DIR... --out sql/varka/bench/benchmarks`
- `dev/varka_quote_check.py`: does every performance number the documents quote trace to a
  committed results file? `[no JVM]` `dev/varka_quote_check.py`
- `dev/varka_quote_allowlist.txt`: the quoted numbers accepted without a results file, each
  with its reason. Read by `dev/varka_quote_check.py`. `[data]`
- `dev/varka_calibration.py`: print the calibration table of scored cost predictions, which a
  plan that predicts a cost reads first; `--add-row` writes an entry. `[no JVM]`
  `dev/varka_calibration.py`

## Reading what the JIT did

Read `sql/varka/skills/the-jit.md` first. The `.sh` of a pair forks the JVMs and the `.py` reads
their logs.

- `dev/varka_c2_report.py`: what the JIT did with one class's methods, from a
  `-XX:+LogCompilation` log: inlining, intrinsics, eliminated allocations, refusals. `[no JVM]`
  `dev/varka_c2_report.py run.xml --holder SpecificUnsafeProjection`
- `dev/varka_trap_census.py`: split a compilation log's `uncommon_trap` elements into guards
  inserted and traps taken, with counts per method. `[no JVM]` `dev/varka_trap_census.py jit.log`
- `dev/varka_deopt_cycle.sh`: does a kernel's dense loop compile once or enter C2's
  deoptimization cycle? Forks probe JVMs. `[sbt]`
  `dev/varka_deopt_cycle.sh --outputs 13,16,60 --forms group --forks 10`
- `dev/varka_deopt_cycle.py`: the verdict per fork from the logs of `dev/varka_deopt_cycle.sh`.
  `[no JVM]` `dev/varka_deopt_cycle.py --fail-on-cycle <log>...`
- `dev/varka_deopt_census.sh`: how often a kernel the fuzz grammar draws enters the cycle, and
  which shapes. `[sbt]` `dev/varka_deopt_census.sh --label A`
- `dev/varka_deopt_census.py`: read the run directories of `dev/varka_deopt_census.sh`.
  `[no JVM]` `dev/varka_deopt_census.py --label NAME RUN_DIR...`
- `dev/varka_deopt_census_guard.tsv`: the nightly guard's rows and its ratchet, one line a
  vector width. Read by the two tools above. `[data]`
- `dev/varka_inlining_cliff.sh`: does a kernel's loop method keep its vector intrinsics or fall
  to the Vector API's scalar bodies? Forks probe JVMs. `[sbt]`
  `dev/varka_inlining_cliff.sh --outputs 64 --ceilings 400,100,50`
- `dev/varka_inlining_cliff.py`: the verdict per fork from the logs of
  `dev/varka_inlining_cliff.sh`. `[no JVM]` `dev/varka_inlining_cliff.py --fail-if-slow <log>...`
- `dev/varka_kernel_history.sh`: what a kernel meets when it is compiled after N other kernels
  in one JVM. `[sbt]` `dev/varka_kernel_history.sh --after 0,16 --forks 5 --prewarm off`
- `dev/varka_kernel_history.py`: read the fork logs of `dev/varka_kernel_history.sh`. `[no JVM]`
  `dev/varka_kernel_history.py --dir target/varka-kernel-history/<date-time>`
- `dev/varka_jfr_frames.py`: where the time goes inside one Varka code path, from a JFR
  recording's execution samples on stdin. `[no JVM]`
  `jfr print --events jdk.ExecutionSample --stack-depth 64 emit.jfr | dev/varka_jfr_frames.py`
- `dev/varka_jfr_ir_hash.py`: the share of JFR samples spent hashing or comparing IR nodes.
  `[no JVM]`
  `jfr print --events jdk.ExecutionSample --stack-depth 128 emit.jfr | dev/varka_jfr_ir_hash.py`

## Plans, documents and censuses

- `dev/varka_plan_lint.py`: do a milestone plan's rows hold against the tree: provenance, cited
  files, sections, row numbers, run heads. The default file is `sql/varka/plans/m8/PLAN.md`.
  `[no JVM]` (uses `gh` unless `--offline`) `dev/varka_plan_lint.py --offline`
- `dev/varka_tools_lint.py`: check this index against the entries under `dev/`. `[no JVM]`
  `dev/varka_tools_lint.py` (`--self-test` runs its cases)
- `dev/varka_census.py`: the census of Spark's scalar expressions over the milestone's types:
  rewrites the census, the cast matrix and the docs' support table; `--check` compares them.
  `[no JVM]` `dev/varka_census.py --check`
- `dev/varka_word_census.sh`: the validity-word census over the surface projections and the
  fuzzer's value grammar. `[sbt]` `dev/varka_word_census.sh`
- `dev/varka_tpc_census.sh`: the largest generated method of every whole-stage codegen stage of
  the TPC-DS and TPC-H queries; writes `sql/varka/census/`. `[sbt]` `dev/varka_tpc_census.sh`
- `dev/varka_codegen_report.py`: which operators of your queries ran outside whole-stage
  codegen and why, from Spark event logs. `[no JVM]` `dev/varka_codegen_report.py EVENT_LOG...`
- `dev/varka_post_page.py`: render a post's Markdown and figures into the page that is
  published. `[no JVM]` `dev/varka_post_page.py sql/varka/plans/m5/POST.md --out target/post`
- `dev/varka_traffic_snapshot.sh`: one capture of the repository's traffic into
  `sql/varka/traffic/`. `[no JVM]` needs GitHub. `dev/varka_traffic_snapshot.sh --print`

## CI helpers

Workflow steps call these; a person runs them to read a run or to test the step.

- `dev/varka_scope.py`: classify a change by its files as `spark`, `scoped`, `docs` or `none`,
  for the Build's precondition. `[no JVM]` `git diff --name-only BASE HEAD | dev/varka_scope.py`
- `dev/varka_ci_admit.py`: the Build's `admit` job: let a full-matrix Build start only while
  few others are ahead. `[no JVM]` needs GitHub. `dev/varka_ci_admit.py --self-test`
- `dev/varka_ci_ledger.py`: the ledger of CI failures and flakes, `sql/varka/matrix/ledger.tsv`.
  `[no JVM]` needs GitHub. `dev/varka_ci_ledger.py --summary --days N`
- `dev/varka_ci_ledger_fixtures/`: recorded API responses for the self-test of
  `dev/varka_ci_ledger.py`. `[data]`
- `dev/varka_ci_times.py`: what each CI and laptop step costs against the committed budget,
  `sql/varka/ci/step-budget.tsv`. `[no JVM]` needs GitHub. `dev/varka_ci_times.py compare RUN_ID`
- `dev/varka_compile_state.py`: find the Build run whose saved compile state a job starts
  from. `[no JVM]` needs GitHub. `dev/varka_compile_state.py REPOSITORY`
- `dev/varka_scheduled_failure.py`: report a failed scheduled workflow run as a GitHub issue;
  a dry run unless `--apply`. Called by a workflow action. `[no JVM]` needs GitHub.
- `dev/varka_scheduled_failure_test.py`: its tests. `[no JVM]`
  `python3 dev/varka_scheduled_failure_test.py`

## The gate before a push

The order for a pull request is in `dev/varka_ops/AGENTS.md`. The tools of this section are the
parts it is made of.

- `dev/varka_ops/`: the scripts that gate, push and watch a pull request: `pre-pr.sh` through
  `pre-pr-slot.sh` is the one gate before a push, `open-pr.sh` and `push-ci.sh` push. They have
  their own index, `dev/varka_ops/INDEX.md`, with inputs, outputs and exit codes, and their own
  rules, `dev/varka_ops/AGENTS.md`; read both before you use one. `[sbt]`
- `dev/varka_precommit.sh`: the house rules over the files about to be committed, in about a
  second; `--install-hook` installs the pre-commit, commit-msg and pre-push hooks. `[no JVM]`
  `dev/varka_precommit.sh` (the staged files) or `dev/varka_precommit.sh --working-tree`
- `dev/varka_lint.py`: the rules of the pre-commit hook that need more than a line's regex, for
  example a license header on a new file. `[no JVM]` `dev/varka_lint.py --working-tree`
- `dev/varka_msg_check.py`: the rules for commit messages and for a pull request's title, body
  and size. `[no JVM]` (`pr --number` and `pr --this-branch` need gh)
  `dev/varka_msg_check.py pr --title T --body-file F`
- `dev/varka_prepush.sh`: the pre-push hook: refuses a branch pushed to the shared repository
  and checks every pushed commit again. Git runs it; `VARKA_PUSH_CHECK=full` adds the suites.
  `[no JVM]` by default. `dev/varka_prepush.sh --selftest`
- `dev/varka_push_check.py`: run before a push what the branch's changed files imply: the
  suites, the regeneration check, the house checks. `[sbt]` **Takes the heavy lock itself**
  around its suites. `dev/varka_push_check.py --dry-run` prints the steps and starts no JVM;
  `dev/varka_push_check.py --no-jvm` runs the rules that need none.
