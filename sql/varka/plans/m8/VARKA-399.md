# VARKA-399: the option matrix and the weekly matrix name their runner

*Row 399 of `m8/PLAN.md`, found by row 394 (`VARKA-394.md` 7). Planned and built on 19 October
2026 from master `d49b71a9daf`.*

## 1. The problem

GitHub's runner pool mixes 256-bit and 512-bit x86 machines, and C2 emits different vector code
for each. On 18 October the option matrix's `lanesOverride=16` shard failed on a 256-bit runner and
passed on a 512-bit one, and the logs did not say which machine was which; only a species-guard
message in an unrelated test gave it away. #929 (row 396) made the fuzz workflow record the CPU and
the vector width before any kernel runs. The option matrix and the weekly full matrix did not.

## 2. The change

**One mechanism, in one place.** #929's commands - the CPU model and core count from `lscpu`, its
AVX and SVE flags, and `MaxVectorSize`, `UseAVX` and `UseSVE` from `java -XX:+PrintFlagsFinal` -
were inlined twice in `varka-fuzz.yml`. This row needs them in four more jobs, so they move into a
composite action, `.github/actions/record-runner-platform`, which takes the directory to write
`platform.txt` to and appends the same block to the job's step summary. The fuzz workflow's two
copies become calls of it, so there is still one implementation, not a second. Two small widenings
for the aarch64 engine job: the flag filter also keeps `asimd`, and when `lscpu` gives no model
name (common on Arm) the vendor ID stands in, and `unknown` if neither is there. Unlike the
inline copies, the action runs under the composite default of `bash -eo pipefail`, so the `lscpu`
calls end in `|| true`: a missing `lscpu` gives an `unknown` line, never a failed job, since the
step now runs in every pull request's test shards. The
directory reaches the script through `env`, not interpolated into it.

**The option matrix.** Its one job (twelve shards) calls the action right after the engine install,
before any suite, with `dir: target`; `target/platform.txt` joins the shard's uploaded files beside
`varka-matrix-report.txt`. The shard's step summary then carries the platform block above the
existing report block, so a failure ties to its runner from the run page without opening a log.

**The weekly matrix has no jobs of its own.** `varka-weekly-matrix.yml` calls
`build_and_test.yml`, the workflow every pull request runs, so the weekly matrix's jobs can only
gain the step there, and every pull request's run of those jobs gains it too: three lines in the
summary and well under a second (0.05 s measured on the laptop, `java -version` included). The
row's "every job" is read as every job that runs Varka's kernels or the SQL suites over them:

| Job | Step placed | `platform.txt` uploaded |
|---|---|---|
| `build` (every module shard) | after the precompiled artifact is extracted, before the tests | yes, in the always-run `test-results-*` artifact |
| `varka-engine` (x86_64 and aarch64) | after the JDK, before the engine's build and tests | no artifact; the step summary is the record |
| `varka-scoped` (catalyst, sql) | after the JDK, before the suites | no artifact; the step summary is the record |

Left out, with the reason: `varka-asm` already runs `dev/varka_datapath.sh` (the fuller report with
the datapath probe); `varka-bench` runs no Vector API kernel (its own comment); `varka-docs`,
`lint`, `docs`, `buf`, `ui`, `java25` (a Maven build with tests skipped), `precondition`,
`infra-image`, `admit` and `precompile` run no kernel; `k8s-integration-tests` and `oidc-e2e`
are off in the weekly matrix. `tpcds-1g`, `pyspark`, `sparkr` and
`docker-integration-tests` run SQL with Varka's engine off (`spark.sql.codegen.varka.enabled`
defaults to false, and `modules.py` notes Spark's own tests run with it off), so no kernel runs
there; adding one later is one `uses:` line. `build` gets the step in every shard, not only the
catalyst and sql ones that run Varka's suites, because it is one matrix job and the step costs
nothing; `yarn` is one of its shards.

`varka-engine` and `varka-scoped` upload nothing today, and adding artifacts only to carry a
three-line file was not worth two new uploads per run: their step summary is their record.

## 3. Predictions

None worth registering: the change is a fixed set of commands whose output on the laptop is quoted
below, and what a runner prints is what the row exists to find out.

## 4. Done when

Every job named in section 2's table, and the option matrix's twelve shards, show the platform
block in their step summary, and the jobs with an upload carry `platform.txt` in it. The
`build_and_test.yml` jobs run on this row's own pull request, which changes a Spark-scoped file and
so runs the full matrix: its run is the first evidence for them. The option matrix runs only on its
schedule or a dispatch from master, so its part is shown by its first run on master after the
merge, not by anything this branch can run.

## 5. Outcome

Built on 19 October 2026. The action's script, extracted from `action.yml`, passes `bash -n` and
`shellcheck`, and run locally with `GITHUB_STEP_SUMMARY` pointed at a scratch file it wrote the
same block to both files:

```
## Runner CPU and vector width
CPU: AMD Ryzen AI 9 HX PRO 370 w/ Radeon 890M, 24 cores
AVX/SVE flags: avx avx2 avx512_bf16 avx512_bitalg avx512bw avx512cd avx512dq avx512f avx512ifma ...
JVM: MaxVectorSize=64 UseAVX=3
```

With a stand-in `lscpu` and `java` that print an Arm runner's lines (no model name, `asimd sve
sve2` flags, `UseSVE=2`) it printed `CPU: ARM, 24 cores`, the three flags and `MaxVectorSize=16
UseSVE=2`. With no `lscpu` on the path, under the composite's `bash -eo pipefail`, it exited 0
and printed `CPU: unknown`. The four workflow files and the action parse as YAML, and
`actionlint` reports the same 61 findings on the changed workflows as on master's copies, none
new. An independent read-only review found no correctness bug; its hardening point (the step must
never fail a job) and its notes on the left-out list are folded in above. `dev/varka_ci_admit.py
--self-test`, which reads `build_and_test.yml`, passes (17 tests).

The runner evidence is owed to the pull request's run and to the option matrix's first run on master
after the merge (section 4).
