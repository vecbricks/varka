# Task 203: a demo a reader can run

## 1. Where this came from

Row 203 of `PLAN_MILESTONE_6.md`, added on 24 September 2026 while listing
what the milestone's post needs beyond the measurements. The post's claim is
that stock Spark runs a wide projection's generated code interpreted, and
silently, once the method passes a size nobody shows the reader. A reader who has to take that on
trust will not; a reader who pastes a short script into `spark-shell` and
watches the time per row jump on their own laptop will. The row asks for that
script: stock Spark, a few lines, the method's size and the time per row either
side of the limit, and the JVM's own word that the method is never compiled -
checked on a GitHub runner and published with the post, which links to it.

## 2. The admission check, done

**The script exists in draft.** The JDK checks of `PLAN_TASK_192.md` 9.2 ran
the ladder's vanilla query through `spark-shell` on the stock Spark 4.2.0
distribution: `n` entries of `greatest(add_months(d, k), date_add(d, k),
last_day(d))` over two million generated dates, the largest generated method
read from Spark's own compile, five timed writes to the `noop` sink. It needs
no data, no fork and no Varka.

**The crossing on stock 4.2.0 is known and JDK-independent.** Its consume
method is 7945 bytes at 48 entries and 8677 at 52, the same bytes on JDK 17, 21
and 25, since Janino writes them; at 48 the method is compiled, at 52 it never
appears in `-XX:+PrintCompilation`'s log (9.2's table). So a demo at 48 and 52
entries straddles the limit on every JDK Spark 4.2 supports. The fork crosses
between 52 and 54, which is why the demo must use stock Spark, not the fork's
ladder rungs.

**What the draft does not do yet.** It reads an environment variable per run,
prints one rung at a time, reaches into `WholeStageCodegenExec` and
`CodeGenerator` directly, and was never run on a runner.

## 3. The design

### 3.1 The script

`sql/varka/demo/method_size_cliff.scala`, run as

    bin/spark-shell --master local[1] -i method_size_cliff.scala

In one run it walks 44, 48, 52 and 56 entries and prints one table:

| entries | largest method | time per row, defaults | time per row, `hugeMethodLimit=8000` |
|---:|---:|---:|---:|

with each byte count marked when it is past 8000, and a closing line naming
the crossing ("at 52 entries the method is 8677 bytes: HotSpot will not compile
it"). The fourth column is task 192's finding in the reader's hands: the one
setting that removes most of the step, internal and off by default. Each time
is the best of five writes to the `noop` sink after two warm-up writes, so one
slow first iteration does not make the table.

It reads the method's size through `org.apache.spark.sql.execution.debug`'s
`codegenStringSeq`, the helper behind `Dataset.debugCodegen`, rather than
through `WholeStageCodegenExec` and `CodeGenerator` directly, so it uses a
facility Spark documents for this purpose. The query has no exchange, so
adaptive execution does not wrap it and the stage is there to measure.

The header of the script says in three sentences what the table shows and why,
and gives the second command, which shows the JVM's side:

    bin/spark-shell --master local[1] \
      --driver-java-options "-XX:+PrintCompilation" -i method_size_cliff.scala \
      | grep 'project_doConsume'

in which the 48-entry method appears as compiled and the 52-entry one does not.

**Why these four rungs.** 44 and 48 show the line below the limit, 52 and 56
the step and the line after it; four rungs keep the run under a minute on a
laptop. They are stock 4.2.0's crossing; a later Spark generating more or less
code moves it, and the closing line names the crossing it measured rather than
assuming it.

### 3.2 The runner check

The published sample output comes from a GitHub runner, as every number the
post quotes does. `varka-surface-benchmark.yml` already downloads, verifies and
caches the stock distribution; the demo gets a small workflow of its own,
`varka-demo.yml`, dispatched by hand, which reuses that download step, runs the
script under JDK 17, 21 and 25 on stock 4.2.0, and uploads the three outputs
and the compile logs as an artifact. The committed files,
`sql/varka/demo/method_size_cliff-<jdk>-output.txt`, are those outputs with the
runner's CPU named, as the benchmark files carry theirs.

A new workflow rather than a mode of the surface workflow: that workflow gates
on the vector datapath and builds the fork, neither of which the demo needs,
and a reader who wants to rerun the demo should find a workflow that does only
that.

### 3.3 Where it lives

In the repository, beside the plans' record, so the post links to a file
at a commit and the file cannot change under the link. A gist can mirror it
for readers who want one click; the repository copy is the one that is kept.

### 3.4 What is deliberately not in it

* **Varka.** The demo shows stock Spark's cliff, which is the claim a reader
  can check without building anything. Varka's side is the size ladder, whose
  reproduction is the benchmark workflow (task 171).
* **The realistic query.** `modified-q3`'s filter (task 172) is the second
  demo, once Varka takes it; the ladder shows the step more cleanly, and it is
  the one the post's figure is drawn from.

## 4. Files

| file | what |
|---|---|
| `sql/varka/demo/method_size_cliff.scala` | the script |
| `.github/workflows/varka-demo.yml` | the runner check |
| `sql/varka/demo/method_size_cliff-jdk{17,21,25}-output.txt` | the runner's outputs |
| `PLAN_MILESTONE_6.md` | row 203 |

## 5. Tests, and what each is for

* **The script checks itself.** It fails, rather than printing a table, if the
  48-entry method is not within 8000 bytes or the 52-entry one is not past it,
  naming the Spark version it ran on, so a Spark release that moves the
  crossing produces a clear message instead of a table with no step in it.
* **The workflow runs it on three JDKs.** That is the check that it works as
  a stranger will run it: a clean distribution, no repository build.

## 6. Predictions, registered before the run

1. **The runner reproduces the bytes exactly**: 7945 at 48 entries and 8677 at
   52, on all three JDKs.
2. **The step is at least three times** the time per row between 48 and 52
   entries under the defaults, on every JDK; the fork's step measured 3.7 to
   5.3 times on the laptop and the two runners (tasks 171 and 192).
3. **`hugeMethodLimit=8000` keeps the step under 1.5 times** at the same rungs.
4. **The compile log shows the 48-entry method compiled and the 52-entry one
   absent**, on every JDK.

## 7. Risks

1. **A reader's laptop is noisy.** Times vary run to run; the step is several
   times, so it survives noise that would swamp a 10% effect. The header says
   the ratio is the point, not the absolute times.
2. **`codegenStringSeq` is a debug facility, not a stable API.** If a future
   Spark changes it, the script's self-check fails with a message; the runner
   check pins the version the post is about.
3. **Spark 4.2.0 disappears from the download mirror** when a later release
   replaces it. The workflow falls back to `archive.apache.org` when the
   mirror has dropped it, and the post names the version.

## 8. Sequencing

1. The script, run on the laptop under the three JDKs; its self-check and
   table reviewed.
2. The workflow and one dispatch; the outputs committed with the runner named.
3. The predictions scored in 9, and the file linked from the post's draft.

## 9. Outcome

### 9.1 The script and the workflow, 24 September 2026

**Two departures from 3.1 and 3.2, and why.**

* **Half a million rows, and the best of three runs after one warm-up**, not two
  million rows and five runs after two. The interpreted rungs cost about four
  microseconds a row, so the plan's figures made the demo take minutes; at
  these it takes about 35 seconds end to end on the laptop, and the ratio is
  the same.
* **The workflow also runs on a push that changes the demo.** A hand dispatch
  needs the workflow file on the repository's default branch, which a new
  workflow is not on until it merges, so the first runner check comes from the
  push that adds it. After the merge, the dispatch works as planned.

**The laptop, stock Spark 4.2.0, one run per JDK** (the demo's own output,
reproduced by running it; the runner's outputs are the committed files):

| JDK | 48 entries, defaults | 52 entries, defaults | 48, `hugeMethodLimit=8000` | 52, `hugeMethodLimit=8000` |
|---|---:|---:|---:|---:|
| 17 | 836.4 | 4144.2 | 836.6 | 1080.3 |
| 21 | 708.1 | 4030.1 | 707.7 | 901.5 |
| 25 | 801.3 | 4100.4 | 807.8 | 1071.4 |

nanoseconds per row. The bytes are 7285, 7945, 8677 and 9513 at 44, 48, 52 and
56 entries on every JDK. Under `-XX:+PrintCompilation` on JDK 25, the compile
log names the 7285- and 7945-byte methods and never the 8677- or 9513-byte ones.

<!-- The runner's outputs and the predictions' scores follow when the workflow has run. -->
