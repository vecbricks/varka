# VARKA-160: run only the CI a change can reach

*Opened 20 September 2026 on the owner's question whether the build's ninety
minutes were necessary for every Varka pull request. Measured from a full run,
implemented as one change to the precondition and one job, and proved by the
runs the change itself produces.*

## 1. What a Varka pull request paid, measured

Pull request #267's Build on the fork, a change to the emitter and the
compiler: 35 jobs, 1283 job-minutes, about ninety minutes of wall time. The
long poles, in minutes: the three `sql` shards at 94, 94 and 80; the streaming,
Kafka, connect and cloud shard at 93; the Docker integration tests at 84; the
TPC-DS run at 54; `hive - other` at 54; eight PySpark shards between 37 and 61;
the catalyst shard at 51; the Java 25 Maven build at 45; the linters at 24.

The precondition already selects by module - the fork had tuned it further,
turning SparkR, the protobuf and UI jobs off unless their own files change -
and still ran all of that, for one reason: any change under `sql/catalyst`
sets `build=true`, and `build=true` runs the whole module matrix. Spark's rule
is right for Spark, where catalyst is upstream of everything. It is wrong for
Varka, whose code lives almost entirely in files named for it or in `varka`
directories - 30 main and 35 test files in catalyst, 6 and 21 in sql/core, and
`sql/varka` - and whose hooks into Spark are six shared files (`SQLConf`,
`StaticSQLConf`, `BaseSessionStateBuilder`, `datetimeExpressions`,
`CodeGenerator`, the Arrow cache serializer). Spark's own suites run with the
engine off; a change confined to Varka's files cannot change what they see.

## 2. The change

**A scope per change** (`sparktestsupport.modules.varka_change_scope`): from the
changed files, after the ignored ones are dropped, a change is `spark` if any
file is neither Varka's own nor a Varka document, `scoped` if every file is
Varka's and at least one is code, `docs` if the only files are the documents
the docs job already checks, `none` otherwise. "Varka's own" is
`is_varka_scoped`: a basename containing `varka`, a `varka/` path component,
`sql/varka/`, `dev/varka_*`, the `varka-*` workflows. The six shared hooks and
the build's own workflow fail that test by name, which is the point: touching
them is a Spark change. Both functions carry their doctests.

**The precondition** asks the scope once, and for `scoped` sets `build`,
`build-core-utils`, the PySpark flags, TPC-DS, Docker and the Java 25 build to
false and a new `varka-scoped` flag to true. Everything else - the engine,
bench, assembly and docs jobs, the linters - keeps its own gate.

**The job** `varka-scoped` runs two entries in parallel: `catalyst/testOnly
*Varka*` and `sql/testOnly *Varka* *ArrowCachedBatchSerializerSuite`, each on
the same checkout and caches the module matrix uses. These are the suites the
matrix would have run for the change plus the thousands it would have run
around them.

## 3. What this does not do

It does not shorten the matrix for a `spark` change: a Varka pull request that
touches a shared hook, or `build_and_test.yml` itself, runs what it ran. It does
not touch the linters, which take 24 minutes for a documents-only change and
are a question of their own. It does not classify by content, only by path;
a Varka-named file that reaches into Spark would defeat it, and the review is
what catches that, as it does today.

## 4. Predictions, before the runs

1. A change confined to Varka files runs, besides the linters and the four
   Varka jobs, exactly the two `varka-scoped` entries, and finishes in about a
   third of the matrix's wall time: the catalyst entry near the matrix's
   catalyst shard less the non-Varka suites, the sql entry within the hour.
2. A documents-only change runs the docs checks and the linters and nothing
   else.
3. This pull request's own run, which changes the workflow, runs the full
   matrix - `spark` - as it should.

## 5. Outcome

**The first attempt found a defect of its own.** The verdict was inline Python
inside the workflow's `run` block; YAML strips the block's indentation and
Python read the remainder as an unexpected indent, so the precondition failed
before selecting anything, on the pull request and on both scratch branches.
The rule now runs as `dev/varka_scope.py`, a command line over the same
function, which is also easier to try by hand.

**Prediction 3 held on the second attempt.** The pull request's own run
reported `varka change scope: spark` for its five changed files and turned the
full matrix on.

**Predictions 1 and 2 cannot be taken from a branch.** The two scratch branches
carried the workflow change themselves, so their diff against master was the
workflow and `modules.py` plus the one file under test - six and five files -
and both were classified `spark`, correctly. A branch cannot exercise the
`scoped` and `docs` verdicts until the precondition that computes them is on
master; the first Varka-only and documents-only pushes after this merges are
the acceptance runs, and their job lists and times go here then. Until then the
evidence for those two verdicts is the script on the three file lists and the
doctests.

**The acceptance runs, 21 September 2026, on the merged master.** Two scratch
branches, each one file on top of master: a blank line in
`VarkaLoopEmitter.java` (classified `scoped`) and a blank line in this plan
(classified `docs`).

| change | wall | job-minutes | jobs that ran |
|---|---|---|---|
| full matrix (section 1) | about 90 min | 1283 | 35 |
| Varka-only | 35 min | 94 | the two `varka-scoped` entries (16 and 7 min), the assembly gate (6), documentation generation (25), the linters (31), and four one-minute checks |
| documents-only | 46 min | 96 | the docs checks, the Java 25 Maven build (44), documentation generation (14), the linters (29), and four one-minute checks |

Prediction 1 held in substance and missed one job: the matrix is gone and
the wall time is a little over a third of the full run's, but documentation
generation ran too, because the workflow turns it on for every pull request
and the scoped branch of the precondition did not turn it off. It stays on for
a `scoped` change on purpose: it builds catalyst's javadoc, which a Varka Java
file can break, and `dev/varka_gate.sh`'s `doc` step exists for the same
reason.

Prediction 2 failed by two jobs: besides the docs checks and the linters, a
documents-only change ran the Java 25 Maven build for 44 minutes and
documentation generation for 14, since the precondition's `docs` branch set
nothing at all. The same change now sets the matrix flags off for `docs` as
for `scoped`, and documentation generation off for `docs` alone, so a
documents-only push runs the docs checks, the linters and the one-minute
checks, about 30 minutes of wall time, all of it the linters.

One more defect found reading the block: the scoped branch set the core-utils
flag off and the next line recomputed it from the changed modules, which
happened to give the same answer for a Varka change. The recompute now comes
first and the scope's override last.

The linters at about 30 minutes are now the floor for any change; what they
spend it on is a separate question, not this task's.

