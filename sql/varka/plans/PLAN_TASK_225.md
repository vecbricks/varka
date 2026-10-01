# Task 225: the lint and documentation jobs run only the steps a change can reach

## 1. Where this came from

Milestone 6 row 225, from the fork CI of #456 on 27 September 2026 (run
36295726808). For a change confined to Varka's files the module matrix is
already off (task 160), so the run is four jobs: the sql Varka suites at 17
minutes, the catalyst suites at 11, and beside them Spark's "Linters, licenses,
and dependencies" at 30 minutes and "Documentation generation" at 16. Every
Varka pull request therefore waited on the lint job about a quarter of an hour
past its own tests, and both jobs held two of the fork's twenty job slots for
that long.

The lint job by step, from that run: MiMa 8m08s, the Python linter 7m33s, the
Scala linter 2m55s, the R linter 2m51s (plus 9s installing SparkR), the
dependency test 2m20s, the Connect client's MiMa 1m48s, the Java linter 23s,
the license test 30s, the JS linter 5s, the config-policy and structured-logging
checks under a second; about three minutes of container start, checkout and
caches before the first step. The documentation job: 11m11s building the site
twice (once with `SKIP_API`, once whole) and 1m48s tarring it.

## 2. The change

The precondition already classifies a change by its paths
(`dev/varka_scope.py`: `spark`, `scoped`, `docs`, `none`). It now derives three
flags from the verdict and the changed files, and the lint job's steps read
them; a Spark change and the weekly full-matrix run keep every step, and the
job reads a missing flag as true, so a caller that passes its own `jobs` gets
the whole job.

| step | flag | runs for a Varka-only change? | why |
| :-- | :-- | :-- | :-- |
| MiMa | `lint-spark` | no | `MimaExcludes` excludes `org.apache.spark.sql.catalyst.*` and `org.apache.spark.sql.execution.*`, where Varka's Spark-side code lives; the engine module has no released artifact to compare against |
| Dependencies test | `lint-spark` | no | it reads the poms, and a pom is a Spark file, so a change to one is `spark` |
| Connect client MiMa | `lint-spark` | no | it compares the Connect client against the SQL API; neither has a Varka file |
| Install SparkR, R linter | `lint-spark` | no | they read `R/`, which no Varka file is under |
| Scala linter, Java linter | `lint-code` | yes for `scoped`, no for `docs` | Varka's sources are Scala and Java |
| Python linter (and the package listing) | `lint-python` | when a `.py` or `.pyi` file changed | `dev/lint-python` runs ruff over `dev/`, where Varka's tools are; its mypy pass over `python/pyspark` is what makes it seven minutes |
| License, JS linter, config-policy check, structured-logging check | none | yes | under a minute together; a new Varka file needs its header |

The documentation job is turned off for a `scoped` or `none` change, as it
already was for `docs`: unidoc drops every source under `sql/catalyst`,
`sql/execution` and `sql/internal` (`SparkBuild.ignoreUndocumentedPackages`),
leaves the engine project out of its filter, and `gen-sql-config-docs.py`
lists no internal config, so nothing Varka's code could change reaches the
site. A changed file under `docs/` keeps the job on whatever the verdict,
which also closes a gap task 160 left: `docs/sql-varka.md` is one of the
documents the `docs` verdict is made of, and it is a Jekyll page, so a change
to it alone skipped the job that renders it.

## 3. What this does not do

* Change what runs for a Spark change, for the weekly run, or for a change that
  touches both Varka's and Spark's files: every such change is `spark`.
* Shorten the steps themselves; the Python linter's mypy pass is upstream's.
* Trust the classifier beyond its rule: it reads paths, not content, and a
  Varka-named file that reaches into Spark's public API would pass MiMa here
  unchecked. The review is the check for that, as task 160 said of the matrix.

## 4. Predictions, before the runs

1. This pull request's own run is `spark` (it changes the workflow), and every
   step of both jobs runs in it.
2. The first `scoped` change without a Python file after the merge runs the
   lint job in about 7 minutes (three of setup, three of the Scala linter,
   the rest under a minute) and no documentation job, against 30 and 16 in
   run 36295726808; the run's wall time is then the sql Varka suites', about
   17 minutes.
3. The first `docs` change after the merge runs the lint job in about 4
   minutes: setup and the four cheap steps. #496, a plans-only pull request in
   the queue as this is written, gives the number before the change.
4. A `scoped` change with a `dev/varka_*.py` file runs the Python linter and
   the lint job takes about 15 minutes.

## 5. Outcome

*Written 1 October 2026 from the fork's runs since the merge, against section
1's baseline, run 36295726808: the lint job 30m03s, documentation generation
16m11s.*

* **`scoped` without a Python file** (prediction 2): #533's run 36817976605, the
  lint job 7m21s - checkout and setup 3m, the Scala linter 2m53s, the Java linter
  24s - and no documentation job; #531's run 36812401605, 7m49s. The wall time was
  21m00s and 27m23s, not the 17 predicted: the sql Varka suites took 18m32s, and
  in #531's run the base image build took 17m27s with the lint job queued behind
  it. #529's run 36776906174 ran a documentation job of 21m31s because the change
  touched `docs/sql-varka.md`, as the rule says.
* **`docs` only** (prediction 3): #532's run 36774538874, the lint job 3m56s,
  setup and the cheap steps, no documentation job. Held; but the wall time was
  22m17s, since the lint job starts after the base image build, which took 16m27s
  on a cache miss. On a documents-only change the critical path is now that
  build, not the linters.
* **`scoped` with a Python file** (prediction 4): #534's run 36876306977, with
  the figure scripts in the change, the lint job 11m16s, of which the Python
  linter 6m56s; wall 14m50s. Held in direction, under the 15 predicted.
* **Prediction 1** is unscored: the pull request's own run id was not kept.

So the job that cost every Varka pull request a quarter of an hour past its own
tests costs a scoped change 7 to 8 minutes, inside the suites' time, and a
documents-only change 4; row 225 is done. What the readings surface instead is
the base image build in front of the lint job on a documents-only run, a note
under `SCOPE_MILESTONE_7.md` item 76.
