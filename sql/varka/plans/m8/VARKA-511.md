# VARKA-511: A post for Spark application developers on testing faster below the session

## 0. What exists now (admission check)

From master at `5c660201b35` and this branch's tree, read on 1 November 2026. Also read: the
committed results of rows 509 and 510, the milestone 7 posts, Spark's own test bases, and the
laptop's tools by `which` and `ls`. Nothing was run but those reads and the plan lint.

1. **The lesson's numbers are committed, all from the laptop.**
   - `m8/results/VARKA-509-profile-local.txt`: `VarkaCoverageDifferentialSuite`, 1066 tests in
     358 s, each checking one expression's answer through a local session. Of its samples,
     Varka's compile and emit was 2.9%, the optimizer and analyzer 26.4%, task serialization
     18.2%, Janino 3.9%, and the task run 6.6%. Tree nodes and other Spark internals took 34.0%,
     and GC paused for 14.2 s in all.
   - `m8/results/VARKA-509-step4.txt`: the three suites row 509 moved below the session took
     135.8 s where they took 472.2 s, "3.5 times faster", with 1211 tests against 1172. In the
     pull request's Build their seconds went from 1064.1 to 399.3.
   - `m8/results/VARKA-510-planning-local.txt`: with the session gone, the harness spent 45.1% of
     its samples in the analyzer and optimizer, 39.1% under one method that plans a query at a
     time. Varka's compile and emit was 11.7% and the kernels 0.1%. It made 11601 plans for 1067
     tests, 1192 of them repeats, "about eleven a test, each a tiny plan through every optimizer
     batch".
   - `m8/results/VARKA-510-together-local.txt` and `m8/results/VARKA-510-rest-local.txt`: planning
     a row's checks together took the suite from 38.9 s to 35.2, then to 31.4 by medians. The
     optimizer runs went from 9743 to 6619. One negative: a `UNION ALL` of four filters, planned
     in one run, cost 2.93 ms a row against 3.08 for the four planned alone.

   The row's step (2) quotes these. Step (1)'s application suite has no numbers yet.
2. **Spark's own test session** is the common settings' authority:
   - `TestSparkSession` runs on `local[2,1]` by default (`TestSQLContext.scala:61-64`), and its
     `overrideConfs` set `spark.sql.shuffle.partitions` to 5, "to speed up testing" (`:102-103`);
   - Spark's sbt test JVMs set `spark.ui.enabled=false` and `spark.ui.showConsoleProgress=false`
     (`project/SparkBuild.scala:2104-2105`);
   - one session is shared by all the tests of a suite (`SharedSparkSessionBase`,
     `SharedSparkSession.scala:63-70`).

   Those are the row's four settings: one shuffle partition (Spark uses 5), the UI off, a small
   local master and a shared session.
3. **The precedent for an application-level experiment** is the compiles post
   (`m7/POST_COMPILES.md`, 1956 words on master). It ran on stock Spark 4.2.0
   (`/home/max/proj/spark-4.2.0-bin-hadoop3` on the laptop) through `bin/spark-shell -i`, from
   scripts under `sql/varka/demo/compiles/`, with their outputs committed beside them as
   `*-output.txt`. `dev/varka_quote_check.py` reads `sql/varka/demo/*/*-output.txt` as results
   files. Its times were taken on a GitHub-hosted runner, with the CPU named.
4. **The laptop's tools.** There is no `sbt`, `scala`, `scalac` or `mvn` on the path. The
   repository's `build/sbt` and `build/mvn` wrappers exist. ScalaTest's 2.13 artifacts are in
   `~/.m2` from Spark's own build, and `python3` is installed.
5. **The house style for posts**, from the milestone 7 posts and the owner's reviews:
   - open on a measured surprise, with the "In short" box right after it;
   - plain `##` headings in story order;
   - one figure a section, drawn by `sql/varka/plans/figures/figN.py` on `rough.py` from the
     committed file, outline-only;
   - round numbers in prose, decimals only in figures and captions;
   - provenance in a short closing "How these were measured";
   - short: the milestone 7 posts run 1425 to 1956 words, the correctness post apart.

   `dev/varka_post_page.py` renders a post into a page. The highest figure on this branch is
   `fig61.py`.
6. **Milestone 8 has no post yet**, and the first draft of row 509's post, which the row says "read
   as a post about Varka", is not in the tree or on any local or fork branch.

### What differs from the row text

- The row lists four common settings. Spark's own value for shuffle partitions is 5, not 1
  (item 0.2), so the settings arm measures both.
- Step (1)'s "logic as plain functions" can be done only for logic that does not need a session.
  An application has no catalyst-level harness of the kind Varka built, since that is Spark's
  internal API. So the post has to say where the line falls (2.3).
- Two arms the row does not list are owner questions: a table of cases checked in one query,
  which is row 510's lesson at the application's level, and whole-stage codegen off (question 3).

## 1. Goal and non-goal

**Goal.** A short post for developers of Spark applications on making their own test suites
faster. It opens on the measured surprise that a test of a little logic pays mostly for the
session around it, and it shows with a small application suite what each common setting buys
alone. It shows what writing the logic as plain functions behind a thin session layer buys,
what to keep end to end, and Varka's own suites as the real-world case. Every number traces to
a committed file.

**Non-goal.** Not a post about Varka: its kernels, its harness's internals and the option matrix
stay out, as the row says. No change to Varka's suites. Not a benchmark of Spark itself.

## 2. Design

### 2.1 The application and its suite

A small invented application, about 200 lines, in the shape app developers write:

- an orders feed parsed from strings, with a cleaning rule;
- a pricing rule (discount tiers, a currency rounding);
- a per-customer aggregate;
- the latest record a key, by a window;
- a date bucket.

Some of its logic is naturally row-level Scala (parsing, the pricing rule over a case class) and
some is naturally Column expressions (the aggregate, the window). That is the line the post
draws. The checks are about 40 small hand-written cases, a few rows each with the expected
answer, spread over four suite classes, as an application's tests are.

A check over a local `Seq` may run no task at all. `createDataFrame` of a `Seq` is a
`LocalRelation`, the optimizer's `ConvertToLocalRelation` folds a projection or a filter over it
into a new one, and the scan's `executeCollect` returns the rows without a job **[I]**: read
from Spark's rules, not measured here. The aggregate and the window do run tasks. So the
row-level checks pay the analyzer and optimizer but not task serialization, unlike Varka's
suite, which read Arrow-cached tables. The suite keeps both kinds and says which is which, and
the post tells the reader that the kind of input decides what a check pays.

### 2.2 The arms

Every arm runs the same checks, and every check must pass in every arm.

- **A, every check through a session.** Each suite class builds its own session with the
  builder's defaults and `.master("local[*]")`, which a builder must be given (the UI on, 200
  shuffle partitions) in `beforeAll` and stops it
  in `afterAll`. Every check builds a DataFrame from a `Seq`, runs the transformation and
  collects. This is the baseline.
- **B, the common settings, each alone on top of A:**
  - B1, one session shared by the four classes;
  - B2, `spark.sql.shuffle.partitions` at 1, and B2' at 5, Spark's own;
  - B3, `spark.ui.enabled=false`;
  - B4, `local[1]`, and B4' `local[2]`, Spark's own.

  Then all together, B-all.
- **C, the logic as plain functions with a thin session layer.** The row-level logic is plain
  Scala, checked by plain assertions with no session. The Column logic, and the wiring of each
  transformation, keep one end-to-end check each through one shared session with B-all's
  settings.
- **If the owner agrees (question 3)**:
  - D, the Column logic's cases as one table, checked in one query, not one query a case;
  - B5, `spark.sql.codegen.wholeStage=false`, whose effect on a check that compiles a new class
    the compiles post measured.

**Measured** with `run.sh` beside the suite. Each arm runs in a fresh JVM, five times, on an idle
machine, under a heavy slot and with `SPARK_LOCAL_IP=127.0.0.1`, as every local JVM run is. The
script reads the suite's wall from the JVM's start to its exit, and the tests' own durations
from ScalaTest's report. It writes minimums and medians, and any ratio under 1.3 is run again
and compared by minimums (`VARKA-14.md` 2.1, as `sql/varka/AGENTS.md` asks). The session's
start is timed on its own. Arm A is profiled once with JFR, its samples sorted into phases:
session start, analyzer and optimizer, task serialization and scheduling, Janino, the
application's code, and the rest. A sample under `ConvertToLocalRelation` or the other
folding rules is a phase of its own, evaluated by the optimizer. The phases are otherwise
the categories of the 509 profile, so the figure can put the application beside Varka's
suite. The outputs go to
`sql/varka/demo/app-tests/<arm>-jdk25-output.txt` and `profile-jdk25-output.txt`.

### 2.3 The post

`sql/varka/plans/m8/POST_APP_TESTS.md`, about 1,500 words and not over 1,800. A working title,
the owner's to change: *Most of a Spark test is Spark*. Its sections:

1. **The opening.** In a suite of about a thousand checks that ran for about six minutes,
   compiling the kernels the checks were about took about 3% of the time, and planning and
   shipping each tiny query took nearly half (`VARKA-509-profile-local.txt`). Then the
   "In short" box: the four or five things to do. *Figure 1*: that suite's samples by phase.
2. **Where a check's time goes.** The application suite in arm A: per check, how much is
   session, planning, scheduling and the application's code. *Figure 2*: arm A's phases, drawn
   beside Figure 1's.
3. **What the usual settings buy.** Each setting alone (arm B), the ones that help and the ones
   that do not, with Spark's own values named as Spark's. *Figure 3*: a bar a setting against
   A.
4. **Test the logic, not the session.** Arm C, and D if it is built. The line: logic that is
   plain code is tested as plain code. A Column expression is not turned into a UDF for
   testability, since that costs at run time what it saves in tests, and it keeps one
   end-to-end check. *Figure 4*: A, B-all and C, and D if built, as whole-suite times.
5. **What to keep end to end.** Folded into section 4 if it runs short: that the transformation
   is wired to the right columns, a join's and an aggregate's behaviour on duplicates and nulls,
   and the configuration the job runs with.
6. **Our suites.** Varka checked its kernels' answers through a session for every expression;
   moved below it, the three suites ran about 3.5 times faster on the laptop, and the pull
   request's Build spent about 400 s on them where it spent over 1,000. The next fixed cost was
   planning, about eleven plans a check. Planning a check's queries together took the suite from
   about 39 s to about 31, while one bigger query was no cheaper than four small ones. The
   option matrix is left out. *Figure 5*: before and after, from `VARKA-509-step4.txt` and the
   510 files.
7. **How these were measured.** Short: the machine, the Spark build, the scripts' directory, a
   link to the compiles post.

**The claims and their sources:**

| claim | source |
|---|---|
| emit 2.9% of a 358 s suite; analysis 26.4%; serialization 18.2% | the 509 profile |
| below the session, planning 45.1%; about eleven plans a test | `VARKA-510-planning-local.txt` |
| planning together: 38.9 s to 35.2, then 31.4 | the two 510 files above |
| one `UNION ALL` no cheaper: 2.93 ms against 3.08 | `VARKA-510-rest-local.txt` |
| three suites 472.2 s to 135.8; the Build 1064.1 s to 399.3 | `VARKA-509-step4.txt` |
| Spark's own settings | `TestSQLContext.scala`, `SparkBuild.scala` |
| every application number | `sql/varka/demo/app-tests/*-output.txt` |

The figures are new `figN.py` scripts, numbered from the next free number at build time, since
other rows draw figures too. Each reads its committed file. The post is rendered with
`dev/varka_post_page.py`, and published as a private review artifact for the owner. Before that,
the advisor reviews its structure and figures, as the posts' house rule asks.

## 3. Tests and proofs

1. **Every arm passes every check**, so no arm is fast by checking less. `run.sh` fails a run
   whose test count differs from arm A's.
2. **The quote check** (`dev/varka_quote_check.py`) passes on the post, with no new allowlist line.
3. **The figures regenerate**: each `figN.py` run from the committed files reproduces its
   committed SVG.

## 4. Predictions, registered before the run

All are directions **[I]**, since no application number exists yet. The post reports what is
measured, including a prediction that fails.

1. **Arm A's checks are mostly session**: under a fifth of arm A's samples in the application's
   own code, as in Varka's suite. The row-level checks over folded inputs spend it in the
   analyzer and optimizer, with no task; the aggregate and window checks add task serialization
   and scheduling.
2. **The shared session (B1) is the largest single setting** for a suite of several classes: it
   saves a session start for each class but the first.
3. **One shuffle partition (B2) buys little on Spark 4.2.0**, since adaptive execution already
   coalesces a small shuffle's reducers. It helps only the checks with a shuffle.
4. **The UI off (B3) buys little**, at the session's start only.
5. **A small master (B4) helps the checks over a few rows**, since a local `Seq` is split into up
   to as many tasks as the master has cores, and the laptop has 24.
6. **C is several times faster than A** for the checks it moves. The end-to-end checks it keeps
   are most of its remaining time.

## 5. Order, files and size

1. The application, its four suite classes, the arms and `run.sh`.
2. The laptop runs and the profile, committed (question 1).
3. The figures, then the post.
4. The advisor's review of the draft, the rendered page as a private artifact, and the owner's
   review.

| file | what |
|---|---|
| `sql/varka/demo/app-tests/` (new) | the application, suites, build file, `run.sh`, outputs |
| `sql/varka/plans/figures/figN.py`, `figures/svg/` | four or five figures |
| `sql/varka/plans/m8/POST_APP_TESTS.md` (new) | the post |

**Size: medium, as the row says.** The application, suites and arms take a day and a half. The
runs and the profile take half a day to a day, on the laptop. The figures take a day, the post a
day, and one review round half a day. About four and a half days, plus the owner's review.

## 6. Out of this row

- Row 355, the milestone's own post for JVM and database engineers.
- Varka's suites themselves: rows 509 and 510, and row 510's remaining step.
- The option matrix's times (row 509's step 4), left out by the row's word.

## 7. Questions for the owner

1. **Laptop or runner for the application's numbers.** The row says the three arms are measured
   on the laptop (29 October). The rule of 26 October keeps PR verification on CI. The
   compiles post timed its numbers on a GitHub runner and named the CPU. Options: (a) the
   laptop, as the row says; (b) a GitHub runner by a dispatched job, the CPU named; (c) both, the
   laptop's numbers in the post and a runner run as a check. Recommended: (a). The row's word is
   the later and the specific one, the whole measurement takes under an hour on an idle laptop,
   and Varka's own numbers in section 6 are laptop numbers too. The post names the CPU.

   *1 November 2026, by the owner's word:* the EPYC runner, with the CPU named in the results.
2. **Language and build.** (a) Scala, a Maven project built with the repository's `build/mvn`
   against the released `spark-sql_2.13` 4.2.0 and ScalaTest, run on the stock distribution's
   jars. (b) Scala, scripts through `bin/spark-shell -i` as the compiles post did. That needs no
   build file, but it is not a test runner, and the shell's own session stands in the way of
   arm A's session a class. (c) PySpark, `unittest` modules on the stock distribution's
   `python/`. That reaches the PySpark audience, but its time adds Py4J round trips the Varka
   case does not have. Recommended: (a). Its costs are the JVM's that the post explains, and
   ScalaTest and Spark's artifacts are what the build already resolves.
3. **Two more arms.** (a) Both D (a table of cases in one query) and B5 (whole-stage codegen
   off); (b) D only; (c) neither, the row's arms alone. Recommended: (a). D is row 510's lesson
   in an application's terms, and it keeps Column logic as Column logic. B5 is the setting the
   compiles post makes a reader ask about. Each adds about a fifth to the runs' time, and a bar
   to an existing figure.
4. **The opening.** (a) Varka's suite, its compile and emit at about 3% of a six-minute suite,
   committed now and the real-world case. (b) The application's own arm-A share, once measured,
   which speaks to the reader's own code but is invented. Recommended: (a), with the application
   as "and yours too" in section 2.

## 8. Done when

- Every number in the post traces to a committed results file, and the quote check passes.
- The suite's arms are measured on the laptop, or where question 1 settles, and committed under
  `sql/varka/demo/app-tests/`.
- Each figure is drawn from those files.
- The owner has reviewed the post.

## 9. Open points for the lead

1. **The demo's place and name**: `sql/varka/demo/app-tests/`, Varka-scoped by `modules.py`'s
   `^sql/varka/` rule, so no CI module needs to claim it, and no CI job runs it. The post's file
   name, `m8/POST_APP_TESTS.md`.
2. **The build tools.** `build/mvn` downloads Apache Maven from Apache's mirrors, as Spark's build
   does, and the project resolves only Apache Spark's and ScalaTest's artifacts from Maven
   Central. Nothing new needs the owner's yes under the tools rule, but the plan names it.
3. **The figure numbers** are taken at build time. Other rows draw figures concurrently.
4. **Row 509's first draft** is not in the tree. If it exists elsewhere, its owner-reviewed parts
   are worth reading before the post is written.

## 10. Outcome

(Written when the work lands.)
