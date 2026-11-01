/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration

import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.Random

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute
import org.apache.spark.sql.catalyst.expressions.{Cast, Expression, Literal}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaCatalystGrammar,
  VarkaJitEvidence, VarkaKernelWarmth, VarkaKernelWarmup, VarkaShapeCache}
import org.apache.spark.sql.catalyst.plans.logical.Filter
import org.apache.spark.sql.execution.VarkaSparkFuzz._

/**
 * Random compositions of the coverage table's rows over random data, run with Varka off and on,
 * ANSI off and on, the answers compared (VARKA-262); half the compositions add nested trees of
 * neighbouring types from `VarkaCatalystGrammar` (VARKA-298, `-Dvarka.sparkfuzz.nested=false`
 * leaves them out). A disagreement is shrunk, written as a
 * reproducer under `target/varka-sparkfuzz/`, and fails the test with the reproducer in its
 * message; `sql/varka/fuzz/spark/` is where a reviewed one is kept.
 *
 * Budget: `-Dvarka.sparkfuzz.iterations` (default 200, about a minute) and
 * `-Dvarka.sparkfuzz.seed`; `-Dvarka.sparkfuzz.only=<iteration>` replays one, and
 * `-Dvarka.sparkfuzz.maxFailures` (default 3) is how many disagreements stop the run, which a
 * census of what a seed finds raises so that a known one does not end it early. Every
 * `partitionEvery`th composition with a filter also checks the
 * ternary partition on both engines. The planted-rollback test draws from a seed of its own,
 * `-Dvarka.sparkfuzz.plantedSeed`, so a run at another seed does not move it (VARKA-309).
 *
 * A composition in which a query raised is compared again where no task races another, so that
 * the verdict does not depend on which of two raising partitions failed first (VARKA-381): over
 * its rows as one partition by default, or over each row alone with
 * `-Dvarka.sparkfuzz.errorRerun=eachRow` (`VarkaErrorRerun`).
 *
 * The shared sessions turn the kernel warm-up off, so the main test's kernels serve a shape's
 * first batch, interpreted. The warmed arm (VARKA-327) turns it on, as production runs: each
 * composition's first query on the row path while its kernel compiles, the next ones on the
 * kernel, then the kernel over other data, every run against the row engine. Its budget is
 * `-Dvarka.sparkfuzz.warmupIterations` (default 8, about ten seconds).
 */
class VarkaSparkFuzzSuite extends VarkaSparkDifferential {

  private lazy val rows: Seq[CoverageRow] =
    coverageRows(getWorkspaceFilePath("sql", "varka", "coverage.json").toFile)

  private val seed = sys.props.get("varka.sparkfuzz.seed").map(_.toLong).getOrElse(20261008L)
  private val iterations = sys.props.get("varka.sparkfuzz.iterations").map(_.toInt).getOrElse(200)
  private val onlyIteration = sys.props.get("varka.sparkfuzz.only").map(_.toInt)
  private val maxFailures = sys.props.get("varka.sparkfuzz.maxFailures").map(_.toInt).getOrElse(3)
  // Every output of a composition forced in turn, where the default forces one (VARKA-296).
  private val forceAll = sys.props.get("varka.sparkfuzz.forceAll").contains("true")
  private val partitionEvery = 4
  /**
   * The planted-rollback test's own seed. A planted bug fails only a composition whose shape
   * reaches the code it breaks, so a run's seed decides whether the first three hundred
   * compositions hold one; at seed 1 none did (VARKA-309). A check that a planted bug is caught
   * needs one composition that reaches it, not the run's variety, so it keeps one seed.
   */
  private val plantedSeed =
    sys.props.get("varka.sparkfuzz.plantedSeed").map(_.toLong).getOrElse(20261008L)
  private var firstFailure: Option[Int] = None

  /**
   * The warmed arm's budget (VARKA-327): compositions run with the kernel warm-up on, each
   * waiting out at least one warm-up, so seconds apiece where the main test's take milliseconds.
   * `-Dvarka.sparkfuzz.warmupOnly=<iteration>` replays one,
   * `-Dvarka.sparkfuzz.warmupSet=<i,j,k>` replays those, in that order, in one JVM (a prefix
   * with compositions left out, to find which earlier ones a later release depends on),
   * `-Dvarka.sparkfuzz.warmupShow=true` reports each composition's outputs and filter, and
   * `-Dvarka.sparkfuzz.jitEvidence=false` runs without the JIT recording a release reports.
   */
  private val warmupIterations =
    sys.props.get("varka.sparkfuzz.warmupIterations").map(_.toInt).getOrElse(8)
  private val warmupOnly = sys.props.get("varka.sparkfuzz.warmupOnly").map(_.toInt)
  private val warmupShow = sys.props.get("varka.sparkfuzz.warmupShow").contains("true")
  private val warmupSet =
    sys.props.get("varka.sparkfuzz.warmupSet").map(_.split(',').map(_.trim.toInt).toSeq)
  private val warmupJitEvidence = !sys.props.get("varka.sparkfuzz.jitEvidence").contains("false")
  /**
   * The warm-up's deadline in the warmed arm, `-Dvarka.sparkfuzz.warmupDeadlineSeconds`: ten
   * seconds against production's sixty, because about one warm-up in fifteen waits it out
   * (VARKA-327.md 2), while a small kernel that does compile gets its verdict in under a second.
   */
  private val warmupDeadline =
    sys.props.get("varka.sparkfuzz.warmupDeadlineSeconds").map(_.toInt).getOrElse(10)

  private def kindOf(c: Case, partitions: Boolean, forces: Seq[Int]): Option[String] =
    disagreement(c.fixture, c.select, c.where, c.ansi, partitions, c.pivot.map(rowText), forces)

  /**
   * The outputs a composition forces: one, chosen by the iteration, or every one. A composition of
   * one output forces none: with its only output declined the plan keeps no Varka node, so the
   * plan cannot prove the force moved anything.
   */
  private def forcesOf(c: Case, it: Int): Seq[Int] =
    if (c.outputs.size < 2) Nil
    else if (forceAll) c.outputs.indices
    else Seq(it % c.outputs.size)

  /** Whether the run's options turn the int overflow check off, as a matrix configuration does. */
  private def overflowUnchecked: Boolean =
    !VarkaColumnarToRowExec.currentEmitOptions.checkIntOverflow

  /** Runs `body` with the int overflow check on and every other option as the run has it. */
  private def withOverflowChecked[T](body: => T): T = {
    val previous = VarkaColumnarToRowExec.currentEmitOptions
    VarkaColumnarToRowExec.setEmitOptionsForTesting(previous.withCheckIntOverflow(true))
    try body finally VarkaColumnarToRowExec.setEmitOptionsForTesting(previous)
  }

  /**
   * Whether a disagreement `check` showed over `fixture` is the configuration's by design: with
   * the overflow check off an overflowing lane wraps where the row engine raises or nulls. Two
   * things must hold. The composition agrees once the check is on, everything else equal; and the
   * disagreement is shown to be the wrap itself ([[wrapEvidence]]), since a check-on rerun that
   * agrees proves only that the checked emission differs, not that a row overflowed. Asked only
   * after a disagreement, so a run at the defaults never pays for it, and not counted in the
   * census.
   */
  private def configurationWrap(c: Case, fixture: String)(check: => Option[String]): Boolean =
    overflowUnchecked && withoutCounting {
      withOverflowChecked(check).isEmpty && wrapEvidence(c, fixture)
    }

  /**
   * How one expression, evaluated alone over the whole fixture, compares between the row engine
   * and Varka with the run's options: the same, different only as an overflow wrap is, or
   * different otherwise.
   */
  private sealed trait Probe
  private case object ProbeAgrees extends Probe
  private case object ProbeWraps extends Probe
  private case object ProbeDiffers extends Probe

  /**
   * `text` as the one output of a query over `fz`, on both engines. An unchecked lane that
   * overflows shows in one of two ways, and only these are a wrap: the row engine raises an
   * overflow condition where Varka answers (a checked mode that fails), or the row engine's rows
   * differ from Varka's only by a NULL where Varka has a value (a mode that nulls). A value
   * against another value is not a wrap, whatever the check does.
   */
  private def probe(text: String): Probe = {
    val query = s"SELECT $text AS c0 FROM fz"
    (outcome(spark, query), outcome(varkaSpark, query)) match {
      case (off, on) if off == on => ProbeAgrees
      case (Right(off), Right(on)) if off.size == on.size && off.diff(on).forall(_ == "[null]") =>
        ProbeWraps
      case (Left(raised), Right(_)) if raised.contains("OVERFLOW") => ProbeWraps
      case _ => ProbeDiffers
    }
  }

  /**
   * The probe of `e` and the probes of the differences that start in it: each node that differs
   * while every child of it agrees, which is where a wrong value first appears. A column or a
   * literal agrees without a query; a node whose text does not run alone agrees too, so its
   * parent is probed in its place.
   */
  private def differencesIn(e: Expression): (Probe, Seq[Probe]) = {
    val children = e.children.map(differencesIn)
    val self = e match {
      case _: Literal | _: UnresolvedAttribute => ProbeAgrees
      case _ => scala.util.Try(e.sql).toOption.map(probe).getOrElse(ProbeAgrees)
    }
    val starts = if (self != ProbeAgrees && children.forall(_._1 == ProbeAgrees)) Seq(self)
      else Nil
    (self, children.flatMap(_._2) ++ starts)
  }

  /**
   * Whether the disagreement of `c` over `fixture` is an overflow wrap and nothing else: every
   * output and every conjunct (as drawn, before rectification) is taken apart and each node
   * probed alone over the whole fixture, and at least one difference starts somewhere, and every
   * one that does is a wrap ([[probe]]). A wrong value on a row that did not overflow, beside a
   * wrap in the same batch or not, starts a difference of its own that is not a wrap, and stays
   * a finding. The fixture is one partition, so a probe that raises names the first raising
   * row's condition on both engines rather than the first failing task's.
   */
  private def wrapEvidence(c: Case, fixture: String): Boolean = withAnsi(c.ansi) {
    cacheFixture(fixture, _.coalesce(1))
    try {
      val starts = (c.outputs ++ c.conjuncts.map(_.sql)).flatMap { text =>
        differencesIn(spark.sessionState.sqlParser.parseExpression(text))._2
      }
      starts.nonEmpty && starts.forall(_ == ProbeWraps)
    } finally {
      uncacheFixture()
    }
  }

  /**
   * The failure text of composition `it`, which showed `found`: the case shrunk, its reproducer
   * written under `target/varka-sparkfuzz/`, and the kind with the reproducer in one message. The
   * checks it runs are not counted in the census, and its note names the error rerun's arm when
   * it is not the default, which the replay then takes (`VarkaErrorRerun.ofNote`).
   *
   * `checked` shrinks with the int overflow check on, as a run that turns it off does for a
   * finding that is not a configuration wrap, so the shrinker cannot settle on an output that only
   * wraps; the reproducer then names `checkIntOverflow=true` as its options. A finding that does
   * not show with the check on at all is reported unshrunk, under the run's own options, rather
   * than shrunk against a kind it no longer has.
   */
  private def failureText(
      c: Case, it: Int, partitions: Boolean, found: String, checked: Boolean = false): String = {
    // A kind may carry a prefix ("at two partitions only, "), so a forced one is found anywhere.
    def isForced(kind: String): Boolean = kind.contains("with an output forced")
    def underOptions[T](body: => T): T = if (checked) withOverflowChecked(body) else body
    val shrunk: Option[(String, Case)] = withoutCounting(underOptions {
      // A forced disagreement is shrunk with the one output that shows it forced, so the
      // reproducer can name it; a forced kind that needs every force names none.
      val forced = if (!isForced(found)) c
        else c.copy(force = forcesOf(c, it).find(k => kindOf(c, partitions, Seq(k)).isDefined))
      val again = kindOf(forced, partitions, forced.force.toSeq)
      // Under the run's own options a kind that does not repeat keeps the first run's; under the
      // check it means the finding needs the check off, and it is not shrunk.
      (if (checked) again else again.orElse(Some(found))).map { kind =>
        // A kind that no longer needs the force (the first run's was not repeatable) keeps none,
        // so the reproducer does not name a force its kind does not depend on.
        val start = if (isForced(kind)) forced else forced.copy(force = None)
        (kind, shrink(start, kind, x => kindOf(x, partitions, x.force.toSeq)))
      }
    })
    val (kind, small, options, how) = shrunk match {
      case Some((k, x)) if checked => (k, x, Some("checkIntOverflow=true"),
        ", shrunk with checkIntOverflow on, where the composition disagrees too")
      case Some((k, x)) => (k, x, None, "")
      case None => (found, c, None,
        ", not shrunk: with checkIntOverflow on the composition agrees, yet the disagreement " +
          "is not an overflow wrap")
    }
    val text = render(Reproducer("regression", s"seed $seed iteration $it${errorRerun.noteSuffix}",
      small.ansi, kind, small.select, small.where, small.fixture, small.pivot.map(rowText),
      small.force, options))
    val dir = getWorkspaceFilePath("sql", "core", "target", "varka-sparkfuzz")
    Files.createDirectories(dir)
    val file = dir.resolve(s"$seed-$it.sql")
    Files.write(file, text.getBytes(StandardCharsets.UTF_8))
    s"$kind (seed $seed, iteration $it$how), reproducer written to $file:\n$text"
  }

  test(s"random compositions agree with the row engine (seed $seed, $iterations of them)") {
    val failures = scala.collection.mutable.ArrayBuffer.empty[String]
    val pool = scala.collection.mutable.TreeMap.empty[String, (Int, Int)]
    // The compositions with nested trees (VARKA-298), those of them that errored on both sides,
    // and those that disagreed.
    var nested = 0
    var nestedErrored = 0
    var nestedFailed = 0
    var wraps = 0
    val range = onlyIteration.map(k => k to k).getOrElse(0 until iterations)
    for (it <- range if failures.size < maxFailures) {
      val c = rectify(draw(rows, seed, it))
      val partitions = it % partitionEvery == 0
      val errorsBefore = bothErrored
      val result = kindOf(c, partitions, forcesOf(c, it)).filter { _ =>
        val wrap = configurationWrap(c, c.fixture)(kindOf(c, partitions, forcesOf(c, it)))
        if (wrap) wraps += 1
        !wrap
      }
      val key = (if (it % 2 == 0) "safe" else "full") + (if (c.ansi) " ANSI on" else " ANSI off")
      pool(key) = pool.getOrElse(key, (0, 0)) match {
        case (n, e) => (n + 1, e + (if (bothErrored > errorsBefore) 1 else 0))
      }
      if (c.trees.nonEmpty) {
        nested += 1
        if (bothErrored > errorsBefore) nestedErrored += 1
        if (result.isDefined) nestedFailed += 1
      }
      result.foreach { found =>
        if (firstFailure.isEmpty) firstFailure = Some(it)
        failures += failureText(c, it, partitions, found, checked = overflowUnchecked)
      }
    }
    info(s"compared $compared, fused $fused, both errored $bothErrored")
    if (overflowUnchecked) {
      info(s"configuration wraps: $wraps compositions disagreed with checkIntOverflow off and " +
        "agreed with it on, so each wrapped an overflow by design")
    }
    pool.foreach { case (k, (n, e)) => info(s"  $k: $n compositions, $e errored on both sides") }
    info(s"  with nested trees: $nested compositions, $nestedErrored errored on both sides, " +
      s"$nestedFailed disagreed")
    val again = errorRerun match {
      case VarkaErrorRerun.ONE_PARTITION => "over one partition"
      case VarkaErrorRerun.EACH_ROW => "over one partition, then row by row"
    }
    info(s"  compared again because a query raised: $comparedAgain, $again; of them, " +
      s"$twoPartitionsOnly differed at two partitions only")
    errorCensus.foreach(l => info(s"  raised: $l"))
    // A warning, not a failure: which conditions a bounded run meets moves with the coverage
    // table, and a failure here would blame a pull request that only added a row (VARKA-319.md 5).
    unusedExpectedErrors.foreach(e => info(s"  expected error never raised in this run: $e"))
    info(s"filters returning a row: $filteredNonEmpty of $filtered, with a pivot " +
      s"$pivotFilteredNonEmpty of $pivotFiltered")
    info(s"outputs forced to decline: $forcedRuns, of which $forcedApplied changed the plan")
    // A force that silently stopped moving the plan would compare nothing. One that lands on an
    // output already residual (the one-lane rule) or in a plan with no Varka project node moves
    // nothing, which leaves well over a quarter that do.
    if (forcedRuns >= 50) {
      assert(forcedApplied * 4 >= forcedRuns,
        s"only $forcedApplied of $forcedRuns forced outputs changed the plan")
    }
    info(s"first disagreement at iteration ${firstFailure.getOrElse("none")}")
    assert(failures.isEmpty, failures.mkString("\n"))
  }

  test(s"random compositions agree with the row engine on both sides of the warm-up handover " +
      s"(seed $seed, $warmupIterations of them)") {
    // The arm needs a JVM that warms kernels, as every JVM the suites run in does (VARKA-212).
    assume(VarkaKernelWarmup.canWarm(), "this JVM cannot warm kernels")
    val failures = scala.collection.mutable.ArrayBuffer.empty[String]
    val verdicts = scala.collection.mutable.TreeMap.empty[String, Int]
    var releasedEarly = 0
    var wraps = 0
    val counts = new WarmedCounts
    val range: Seq[Int] =
      warmupSet.orElse(warmupOnly.map(Seq(_))).getOrElse(0 until warmupIterations)
    var evidence = if (warmupJitEvidence) Some(VarkaJitEvidence.start()) else None
    try withWarmupDeadline(warmupDeadline) {
      for (it <- range if failures.size < 3) {
        // The coverage rows alone: the nested trees meet the bug of VARKA-298.md 9.2 (row 380).
        val c = rectify(drawRows(rows, seed, it))
        if (warmupShow) {
          info(s"iteration $it: outputs ${c.outputs}, filter ${c.conjuncts.map(_.sql)} ${c.op}")
        }
        val later = laterData(c, new Random(~seed * 31L + it), safe = it % 2 == 0,
          wide = (it / 2) % 2 == 1)
        // Every composition starts from a cold shape, so that each one takes the handover.
        VarkaShapeCache.invalidateAll()
        val before = VarkaKernelWarmup.recentOutcomes().asScala.toSet
        val firstResult = warmedDisagreement(c, later, counts)
        val outcomes = VarkaKernelWarmup.recentOutcomes().asScala.filterNot(before.contains)
        // The checked run is a shape of its own (the options ride the shape key), so it takes
        // the handover too; its counts are not the arm's, but a wait that timed out is.
        val result = firstResult.filter { kind =>
          // A disagreement over the other data is probed over that data.
          val over = if (kind.startsWith("over other data")) fixtureSql(later) else c.fixture
          val checkedCounts = new WarmedCounts
          val wrap = configurationWrap(c, over)(checkedRerun(c, later, checkedCounts))
          counts.notIdle += checkedCounts.notIdle
          // The checked run's warm-ups finish here, not inside the next composition's tally.
          awaitWarmupIdle(counts)
          if (wrap) wraps += 1
          !wrap
        }
        outcomes.foreach { o =>
          verdicts(o.state().toString) = verdicts.getOrElse(o.state().toString, 0) + 1
        }
        // A release at the deadline is not a failure here, only evidence for the open question
        // of why a kernel misses its verdict (VARKA-295): the JIT's state at the release and,
        // when the recording runs, its account of the kernel. The warm-up also releases a kernel
        // with nothing to wait for, or one that failed; those are counted, not reported.
        val (atDeadline, early) = outcomes.filter(_.state() == VarkaKernelWarmth.State.RELEASED)
          .partition(o => o.queuedNanos() + o.runNanos() >= warmupDeadline * 1000000000L)
        releasedEarly += early.size
        atDeadline.zipWithIndex.foreach { case (o, n) =>
          // The recording is read once, for the composition's first release: reading it stops it,
          // and a new one would have missed every later release's warm-up.
          val account = evidence match {
            case Some(e) if n == 0 =>
              val text = e.summary(VarkaShapeCache.classNameFor(o.shapeHash()),
                "varka-kernel-warmup", Duration.ofSeconds(20))
              e.close()
              evidence = Some(VarkaJitEvidence.start())
              text
            case Some(_) => "JIT evidence read for this composition's first release"
            case None => "JIT evidence not recorded"
          }
          // In the test's report rather than the log, whose lines the test logger cuts short.
          // The JIT state is the last deadline release's, which this one is unless two were.
          info(s"iteration $it: a kernel was released at the deadline without its verdict: $o; " +
            s"${VarkaKernelWarmup.lastReleaseJitState()}; $account")
        }
        result.foreach { kind =>
          // Not shrunk: each smaller case is a new shape and a new warm-up, seconds apiece. The
          // seed and iteration replay it exactly, the other data included.
          // A failure over the other data is stored with that data, and without the pivot,
          // which is a row of the first data and was not checked there.
          val overLater = kind.startsWith("over other data")
          // Status `warmed`: the replay suite replays with the warm-up off, where this case's
          // path is not taken, and fails on any status but its two, so a warmed reproducer
          // cannot be kept there and pass for the wrong reason.
          val text = render(Reproducer("warmed", s"seed $seed iteration $it, the warm-up on: " +
            s"-Dvarka.sparkfuzz.seed=$seed -Dvarka.sparkfuzz.warmupOnly=$it", c.ansi, kind,
            c.select, c.where, if (overLater) fixtureSql(later) else c.fixture,
            if (overLater) None else c.pivot.map(rowText)))
          val dir = getWorkspaceFilePath("sql", "core", "target", "varka-sparkfuzz")
          Files.createDirectories(dir)
          val file = dir.resolve(s"$seed-warmed-$it.sql")
          Files.write(file, text.getBytes(StandardCharsets.UTF_8))
          failures += s"$kind (seed $seed, iteration $it), reproducer written to $file:\n$text"
        }
      }
    } finally {
      evidence.foreach(_.close())
    }
    counts.summary.foreach(info(_))
    if (overflowUnchecked) {
      info(s"configuration wraps: $wraps compositions disagreed with checkIntOverflow off and " +
        "agreed with it on")
    }
    info(s"warm-up verdicts: ${verdicts.map { case (k, n) => s"$k $n" }.mkString(", ")}, " +
      s"of the releases $releasedEarly before the deadline")
    // An arm whose shapes stopped reaching the kernel would compare the row engine with itself.
    // Three in four compositions hand over at two seeds (VARKA-327.md 2); the rest have no
    // Varka node, raise on both sides, or have every batch pruned, so half is far from the edge.
    if (counts.compositions >= 50) {
      assert(counts.handedOver * 2 >= counts.compositions,
        s"only ${counts.handedOver} of ${counts.compositions} compositions were handed over")
    }
    assert(failures.isEmpty, failures.mkString("\n"))
  }

  /**
   * The warmed arm's run of `c` again, for [[configurationWrap]] to make under the check: an
   * agreement counts only if the run reached its kernel, since `warmedDisagreement` finds nothing
   * in a shape that never left the row path. A run that did not is made once more, the shape now
   * warmed or warming, and one that still did not is a disagreement of its own, never a wrap.
   */
  private def checkedRerun(
      c: Case, later: Vector[Vector[String]], counts: WarmedCounts): Option[String] = {
    val first = warmedDisagreement(c, later, counts)
    val found = if (first.isDefined || counts.kernelRuns > 0) first
      else warmedDisagreement(c, later, counts)
    found.orElse {
      if (counts.kernelRuns > 0) None
      else Some("with checkIntOverflow on, no run reached the kernel")
    }
  }

  // ---- the expected-errors list (VARKA-319) -------------------------------------------------

  private def listOf(lines: String*): java.util.List[VarkaExpectedErrors.Entry] =
    VarkaExpectedErrors.parse(lines.mkString("\n"))

  /** A one-row case whose only output is `output`, over a non-null `i`. */
  private def oneRowCase(output: String, i: Int, ansi: Boolean): Case = {
    val row = draw(rows, seed, 1).data.head.updated(2, i.toString)
    Case(Vector(output), Vector.empty, "AND", Vector(row), ansi)
  }

  test("the committed expected-errors list has a reason per entry and names real things") {
    val list = VarkaExpectedErrors.entries().asScala.toSeq
    assert(list.nonEmpty)
    val families = coverageFamilies(getWorkspaceFilePath("sql", "varka", "coverage.json").toFile) +
      "fixture"
    list.foreach { e =>
      assert(e.reason().length > 20, s"${e.condition()}: the reason is missing or a word")
      assert(e.families().asScala.forall(families.contains),
        s"${e.condition()}: a family that is not in coverage.json: ${e.families()}")
      assert(org.apache.spark.SparkThrowableHelper.isValidErrorClass(e.condition()) ||
        scala.util.Try(org.apache.spark.util.Utils.classForName(e.condition())).isSuccess,
        s"${e.condition()} is neither a Spark error condition nor an exception class")
    }
    val keys = list.flatMap(e => e.modes().asScala.map(m => (e.condition(), m)))
      .groupBy(identity).filter(_._2.size > 1).keys
    assert(keys.isEmpty, s"entries that overlap in condition and mode: ${keys.mkString(", ")}")
  }

  test("a raise is expected only from the side and under the mode its entry lists") {
    val list = listOf(
      "X\trow\tANSI\tfixture\ta reason",
      "Y\tboth\tLEGACY,ANSI\tfixture\ta reason")
    def raise(o: Outcome, engine: VarkaExpectedErrors.Side, ansi: Boolean): Option[String] =
      unexpectedRaise(o, engine, ansi, list)
    import VarkaExpectedErrors.Side._
    assert(raise(Left("X"), ROW, ansi = true).isEmpty)
    assert(raise(Left("X"), VARKA, ansi = true).exists(_.contains("X from Varka under ANSI")))
    assert(raise(Left("X"), ROW, ansi = false).exists(_.contains("X from the row engine")))
    assert(raise(Left("Y"), VARKA, ansi = false).isEmpty)
    assert(raise(Left("java.lang.ArithmeticException"), ROW, ansi = true).isDefined)
    assert(raise(Right(Seq("1")), ROW, ansi = true).isEmpty)
    assert(unexpectedError(Left("Y"), Left("Y"), ansi = true, list).isEmpty)
    assert(unexpectedError(Left("Y"), Left("Z"), ansi = true, list)
      .exists(_.contains("Z from Varka")))
    // A listed condition on one side only is no finding of the list's, and still a disagreement.
    assert(unexpectedError(Left("Y"), Right(Seq("1")), ansi = true, list).isEmpty)
    assert(difference(Left("Y"), Right(Seq("1"))).isDefined)
  }

  test("the partition check reports a raise the list does not allow, and ignores a listed one") {
    val list = listOf("Y\tboth\tANSI\tfixture\ta reason")
    def run(raised: String): String => Outcome =
      q => if (q.contains("IS NULL")) Left(raised) else Right(Seq("a"))
    def check(raised: String): Option[String] = partitionBroken("c", "p", run(raised),
      unexpectedRaise(_, VarkaExpectedErrors.Side.ROW, ansi = true, list))
    assert(check("Z").exists(_.contains("unexpected error condition Z")))
    assert(check("Y").isEmpty)
  }

  test("a condition on no entry fails the fuzzer's check, naming the condition and the case") {
    // `i / 0` raises DIVIDE_BY_ZERO under ANSI on both engines, which the list does not hold.
    val planted = oneRowCase("i / 0", 5, ansi = true)
      .copy(outputs = Vector("year(d)", "i / 0", "month(d)"))
    val found = kindOf(planted, partitions = false, Nil)
    assert(found.exists(k => k.contains("DIVIDE_BY_ZERO") && k.contains("the row engine")), found)
    val text = failureText(planted, 999999, partitions = false, found.get)
    assert(text.contains("unexpected error condition DIVIDE_BY_ZERO"), text)
    assert(text.contains("iteration 999999") && text.contains("-- select: i / 0 AS c0"), text)
  }

  test("a condition on the list passes the fuzzer's check") {
    // `make_date` with month 13 raises DATETIME_FIELD_OUT_OF_BOUNDS... under ANSI on both engines.
    val listed = oneRowCase("make_date(2021, i, 1)", 13, ansi = true)
    def seenCount: Int = errorsSeen.filter(_._1._1.startsWith("DATETIME_FIELD_OUT_OF_BOUNDS"))
      .values.sum
    val before = seenCount
    assert(kindOf(listed, partitions = true, Nil).isEmpty)
    assert(seenCount > before, "the case raised nothing, so it did not test a listed raise")
  }

  test("the draw includes every row of the table in every pass of the table's length") {
    val seen = scala.collection.mutable.Set.empty[String]
    for (it <- 0 until rows.size) {
      val c = draw(rows, seed, it)
      seen ++= c.outputs
      seen ++= c.conjuncts.map(_.sql)
    }
    assert(rows.map(_.executable).toSet.subsetOf(seen), "a row of the table was not drawn")
  }

  test("the fixture builds for every drawn case, so no composition compares fixture errors") {
    // A view that raises while it is built (the cached one computes every column, the row engine
    // prunes the unused ones) makes the two sides raise whichever error their plan meets first.
    // The draw's hostile years must not overflow the year-month columns of the view (VARKA-372).
    val ansiKey = org.apache.spark.sql.internal.SQLConf.ANSI_ENABLED.key
    for (it <- 0 until 600) {
      val c = draw(rows, seed, it)
      withSQLConf(ansiKey -> c.ansi.toString) {
        try {
          spark.sql(c.fixture).collect()
        } catch {
          case e: Throwable =>
            fail(s"the fixture of composition $it (ANSI ${c.ansi}) raised: ${e.getMessage}\n" +
              c.fixture, e)
        }
      }
    }
  }

  test("the shrinker reduces a planted failure to the output that causes it and one row") {
    val c = draw(rows, seed, 5).copy(
      outputs = Vector("date_add(d, 3)", "datediff(d2, d)", "year(d)", "month(d)"))
    val planted = (x: Case) =>
      if (x.outputs.contains("datediff(d2, d)") && x.data.nonEmpty) Some("values differ") else None
    val small = shrink(c, "values differ", planted)
    assert(small.outputs == Vector("datediff(d2, d)"), small.outputs)
    assert(small.conjuncts.isEmpty && small.data.size == 1, small)
  }

  test("a reproducer parses back to what was rendered") {
    val r = Reproducer("known VARKA-999", "seed 1 iteration 2", ansi = true, "values differ",
      "datediff(d2, d) AS c0", Some("d IS NOT NULL"), fixtureSql(Seq(Seq("1", "2"))))
    assert(parse(render(r)) == r)
    val noFilter = r.copy(where = None)
    assert(parse(render(noFilter)) == noFilter)
  }

  test("a pivot case's rectified filter selects its pivot row on the row engine") {
    // Sixty compositions with a pivot: the filter, rectified, must return the pivot's outputs
    // from the whole table. A conjunct that raised on the pivot is dropped, so a case may
    // end without a filter; that case is not a PQS case and is skipped.
    var checked = 0
    for (it <- 0 until 300 if checked < 60) {
      // The coverage rows alone: a nested conjunct can meet the bug of VARKA-298.md 9.2, which is
      // the main test's to find, not this one's.
      val drawn = drawRows(rows, seed, it)
      if (drawn.pivot.isDefined) {
        val c = rectify(drawn)
        if (c.conjuncts.nonEmpty) {
          val kind = kindOf(c, partitions = false, Nil)
          assert(kind.isEmpty ||
            configurationWrap(c, c.fixture)(kindOf(c, partitions = false, Nil)),
            s"iteration $it: $kind: ${c.query}")
          checked += 1
        }
      }
    }
    assert(checked == 60, s"only $checked pivot cases with a filter in 300 compositions")
  }

  test("the draw reaches pivot cases and values outside the fixtures' lists") {
    val cases = (0 until 120).map(draw(rows, seed, _))
    val pivots = cases.count(_.pivot.isDefined)
    assert(pivots >= 10, s"$pivots pivot cases in 120")
    assert(cases.filter(_.pivot.isDefined).forall(c => c.data.contains(c.pivot.get)))
    val cells = cases.flatMap(_.data.flatten).toSet
    // A date outside the fixtures' lists, and a time with a non-listed fraction.
    val listed = Set("2024-01-31", "2024-02-29", "2023-12-27", "2021-01-01", "2021-06-01",
      "2021-03-15", "1969-12-31", "1970-01-01", "2000-02-29", "1999-12-31", "2021-11-01",
      "9999-12-01", "0001-01-15")
    val dates = cells.filter(_.startsWith("DATE'")).map(_.stripPrefix("DATE'").stripSuffix("'"))
    assert((dates -- listed).size >= 50, s"${(dates -- listed).size} dates outside the lists")
  }

  test("the wider pool leaves the compositions of the main stream as they were") {
    // Iterations 0 and 1 draw no wide data and no pivot: their data is the main stream's.
    val c = draw(rows, seed, 0)
    assert(c.pivot.isEmpty)
    val again = draw(rows, seed, 0)
    assert(c == again)
    val wideIteration = draw(rows, seed, 2)
    assert(wideIteration.outputs == draw(rows, seed, 2).outputs)
  }

  test("a case that lacks its pivot is reported, and the shrinker keeps the pivot row") {
    assert(pivotMissing(Right(Seq("a", "b")), Right(Seq("a")), Right(Seq("b")))
      .contains("pivot row not selected by Varka"))
    assert(pivotMissing(Right(Seq("a")), Right(Seq("a", "b")), Right(Seq("b")))
      .contains("pivot row not selected by the row engine"))
    assert(pivotMissing(Right(Seq("b")), Right(Seq("b")), Right(Seq("b"))).isEmpty)
    // An error on another row says nothing about the pivot.
    assert(pivotMissing(Left("X"), Right(Seq("b")), Right(Seq("b"))).isEmpty)
    assert(pivotMissing(Right(Seq("a")), Left("X"), Right(Seq("b")))
      .contains("pivot row not selected by the row engine"))
    assert(pivotMissing(Left("X"), Left("Y"), Right(Seq("b"))).isEmpty)
    val c = rectify(draw(rows, seed, 1)).copy(pivot = None)
    val data = Vector.tabulate(8)(k => Vector.fill(12)(k.toString))
    val withPivot = c.copy(data = data, pivot = Some(data(5)))
    val kept = shrink(withPivot, "values differ", _ => Some("values differ"))
    assert(kept.data.contains(data(5)) && kept.data.size == 1, kept.data)
  }

  test("a reproducer with a forced output parses back to what was rendered") {
    val r = Reproducer("regression", "seed 1 iteration 4", ansi = false,
      "with an output forced to decline, values differ", "year(d) AS c0, i + 1 AS c1", None,
      fixtureSql(Seq(Seq("1", "2"))), None, Some(1))
    assert(parse(render(r)) == r)
    val withOptions = r.copy(options = Some("checkIntOverflow=true"))
    assert(render(withOptions).contains("\n-- options: checkIntOverflow=true\n"))
    assert(parse(render(withOptions)) == withOptions)
  }

  /**
   * Runs `body` with `misdescribeRollback` 3 planted, which makes a declining entry's rollback drop
   * the input before its own and so changes the kernel's answers, with `force` (an output, from 0)
   * forced to decline as well. Some compositions fail to emit under the planted rollback instead,
   * and the row path answers them: a failure fallback the caller causes, so it declares one
   * (VARKA-275).
   */
  private def withPlantedRollback[T](force: Option[Int] = None)(body: => T): T = {
    val previous = VarkaColumnarToRowExec.currentEmitOptions
    val planted = previous.withMisdescribeRollback(3)
    VarkaColumnarToRowExec.setEmitOptionsForTesting(
      force.fold(planted)(k => planted.withForceResidualAt(k + 1)))
    VarkaColumnarToRowExec.setFailureFallbackExpectedForTesting(true)
    try body finally {
      VarkaColumnarToRowExec.setFailureFallbackExpectedForTesting(false)
      VarkaColumnarToRowExec.setEmitOptionsForTesting(previous)
    }
  }

  /** Runs `body` with output `k` (from 0) forced to decline and nothing planted. */
  private def withForcedOutput[T](k: Int)(body: => T): T = {
    val previous = VarkaColumnarToRowExec.currentEmitOptions
    VarkaColumnarToRowExec.setEmitOptionsForTesting(previous.withForceResidualAt(k + 1))
    try body finally VarkaColumnarToRowExec.setEmitOptionsForTesting(previous)
  }

  /**
   * Runs `body` under the options the planted tests select and replay with: the run's, with the
   * int overflow check on where the run turns it off, so that a drawn overflow raises or nulls as
   * the row engine does instead of wrapping, and every disagreement left is the plant's to show.
   * Selection and replay both take it, so the composition and output chosen are the ones replayed.
   */
  private def underPlantedOptions[T](body: => T): T =
    if (overflowUnchecked) withOverflowChecked(body) else body

  /**
   * A disagreement's kind without what may change between two runs of one composition: the
   * prefix a raise's rerun adds and the conditions a race may choose
   * (`VarkaErrorRerun.withoutConditions`), and an unexpected condition's name. What stays says
   * which comparison failed and how, the forced output's included.
   */
  private def kindClass(kind: String): String = {
    val core = kind.stripPrefix("at two partitions only, ")
    val forced = "with an output forced to decline, "
    val (prefix, rest) =
      if (core.startsWith(forced)) (forced, core.stripPrefix(forced)) else ("", core)
    val unexpected = "unexpected error condition"
    prefix + (if (rest.startsWith(unexpected)) unexpected
      else VarkaErrorRerun.withoutConditions(rest).toScala.getOrElse("error classes differ"))
  }

  /**
   * The first composition of the planted seed whose answer the planted rollback changes, the
   * output whose force shows the change, and the kind of the change; shared by the two tests
   * below, and found under [[underPlantedOptions]], as they replay it. The compositions are the
   * coverage rows' alone (`drawRows`): a nested tree meets the planted rollback's branch without a
   * force (VARKA-298.md 9), and these tests are about the force. What is counted is the plant's
   * own change: per forced output, a planted kind whose class ([[kindClass]]) the same run
   * without the plant does not show.
   */
  private lazy val plantedFirst: Option[(Int, Int, String)] = underPlantedOptions {
    (0 until 300).iterator.flatMap { it =>
      val c = rectify(drawRows(rows, plantedSeed, it))
      def planted(forces: Seq[Int]): Option[String] =
        withPlantedRollback()(kindOf(c, partitions = false, forces))
      // Every output forced at once first, the one run most compositions need.
      if (planted(c.outputs.indices).isEmpty) {
        None
      } else {
        c.outputs.indices.iterator.flatMap { k =>
          planted(Seq(k)).filter { p =>
            !kindOf(c, partitions = false, Seq(k)).exists(kindClass(_) == kindClass(p))
          }.map((it, k, _))
        }.nextOption()
      }
    }.nextOption()
  }

  test("a forced output finds a rollback that truncates one input too far") {
    // The unforced comparison meets the planted rollback's branch too rarely to see it
    // (VARKA-296.md 2), and forcing every output of each composition does.
    val found = plantedFirst
    info(s"seed $plantedSeed: the planted rollback first changes an answer at composition " +
      found.map { case (it, k, _) => s"$it, output $k forced" }.getOrElse("none of 300"))
    assert(found.exists(_._3.startsWith("with an output forced to decline")), found)
  }

  test("the warmed arm finds a planted kernel fault after the handover, not before it") {
    // The planted rollback changes what the kernel computes and nothing the row path does, so
    // with the warm-up on the runs on the row path must pass and the first run with a kernel
    // fail: the arm compares both sides of the switch, and keeps them apart.
    assume(VarkaKernelWarmup.canWarm(), "this JVM cannot warm kernels")
    val (it, k, _) = plantedFirst
      .getOrElse(fail(s"seed $plantedSeed: the planted rollback changed no answer in 300"))
    val c = rectify(drawRows(rows, plantedSeed, it))
    val later = laterData(c, new Random(plantedSeed), safe = true, wide = false)
    // Two seconds of deadline: the planted kernel's warm-up can wait one out, and a release hands
    // the shape to the kernel as a verdict does.
    def replay(planted: Boolean, counts: WarmedCounts): Option[String] = {
      VarkaShapeCache.invalidateAll()
      def run: Option[String] = withWarmupDeadline(2)(warmedDisagreement(c, later, counts))
      if (planted) withPlantedRollback(Some(k))(run) else withForcedOutput(k)(run)
    }
    // The same replay without the plant must agree, or what the planted one finds is not the
    // plant's. Both run under the options the composition was selected with.
    val baseline = underPlantedOptions(replay(planted = false, new WarmedCounts))
    assert(baseline.isEmpty, s"composition $it, output $k forced, without the plant: $baseline")
    val counts = new WarmedCounts
    val found = underPlantedOptions(replay(planted = true, counts))
    info(s"composition $it, output $k forced" +
      (if (overflowUnchecked) ", with checkIntOverflow on" else "") +
      s": ${found.getOrElse("no disagreement")}")
    // A shape that went to its kernel without a warm-up would pass the check below untested.
    assert(counts.rowPathRuns > 0, "no run took the row path while the kernel warmed")
    assert(found.exists(kind => kind.startsWith("after the handover to the kernel") ||
      kind.startsWith("with one node on its kernel while another warms")), found)
  }

  test("a reproducer with a pivot parses back to what was rendered") {
    val r = Reproducer("regression", "seed 1 iteration 4", ansi = false,
      "pivot row not selected by Varka", "d AS c0", Some("(d) IS NULL"),
      fixtureSql(Seq(Seq("1", "2"))), Some("(1, 2)"))
    assert(parse(render(r)) == r)
  }

  // ---- nested trees of neighbouring types (VARKA-298) -----------------------------------------

  /** `n` trees from the grammar, outputs or predicates, from a stream of their own. */
  private def grammarTrees(n: Int, predicates: Boolean): Seq[VarkaCatalystGrammar.Node] = {
    val rnd = new java.util.Random(if (predicates) 2980002L else 2980001L)
    (0 until n).map { _ =>
      if (predicates) VarkaCatalystGrammar.drawPredicate(rnd)
      else VarkaCatalystGrammar.drawOutput(rnd)
    }
  }

  /** The query a tree is checked in: an output alone, or a predicate alone in a `WHERE`. */
  private def queryOf(tree: VarkaCatalystGrammar.Node, predicate: Boolean): String =
    if (predicate) querySql("d AS c0", Some(tree.sql)) else querySql(s"${tree.sql} AS c0", None)

  /** The casts in the tree's own operator of `sql`'s analyzed plan, not the view's. */
  private def castsOf(sql: String, predicate: Boolean): Int = {
    val plan = spark.sql(sql).queryExecution.analyzed
    val exprs = if (predicate) plan.collectFirst { case f: Filter => f.condition }.toSeq
      else plan.expressions
    exprs.map(_.collect { case c: Cast => c }.size).sum
  }

  test("the grammar's trees analyze under ANSI on and off, and the analyzer inserts casts") {
    // A tree that failed to analyze would compare one analysis error with another and test
    // nothing; the grammar is well typed by construction, and this holds it to that.
    spark.sql(draw(rows, seed, 0).fixture).createOrReplaceTempView("fz")
    val failures = scala.collection.mutable.ArrayBuffer.empty[String]
    for (predicate <- Seq(false, true)) {
      val trees = grammarTrees(if (predicate) 150 else 300, predicate)
      var implicitCasts = 0
      for (tree <- trees; ansi <- Seq(false, true)) withAnsi(ansi) {
        val sql = queryOf(tree, predicate)
        scala.util.Try(castsOf(sql, predicate)) match {
          case scala.util.Success(casts) =>
            // A cast the analyzer inserted is one the text does not write.
            if (ansi && casts > tree.sql.split("CAST\\(", -1).length - 1) implicitCasts += 1
          case scala.util.Failure(e) =>
            failures += s"ANSI $ansi: $sql: ${String.valueOf(e.getMessage).takeWhile(_ != '\n')}"
        }
      }
      val what = if (predicate) "predicates" else "outputs"
      info(s"$what: $implicitCasts of ${trees.size} with a cast the text does not write")
      assert(implicitCasts * 4 >= trees.size,
        s"only $implicitCasts of ${trees.size} $what had a cast inserted by the analyzer")
    }
    assert(failures.isEmpty, failures.take(10).mkString("\n"))
  }

  test("a share of the grammar's trees fuse on their own") {
    // A grammar whose trees all fell back would test the row path against itself.
    val c = draw(rows, seed, 0)
    cacheFixture(c.fixture)
    try {
      for (predicate <- Seq(false, true)) {
        val trees = grammarTrees(100, predicate)
        val fusedAlone = trees.count { tree =>
          val df = varkaSpark.sql(queryOf(tree, predicate))
          scala.util.Try(df.queryExecution.executedPlan).toOption
            .exists(p => find(p)(isVarkaNode).isDefined)
        }
        val what = if (predicate) "predicates" else "outputs"
        info(s"$what: $fusedAlone of ${trees.size} fuse alone")
        assert(fusedAlone * 5 >= trees.size, s"only $fusedAlone of ${trees.size} $what fuse")
      }
    } finally uncacheFixture()
  }

  test("half the compositions add nested trees and keep the coverage rows they drew") {
    assume(nestedIteration(5), "-Dvarka.sparkfuzz.nested=false draws no nested trees")
    val nestedOnes = (0 until 60).filter(nestedIteration)
    assert(nestedOnes.size == 30)
    var withTrees = 0
    for (it <- 0 until 60) {
      val c = draw(rows, seed, it)
      val plain = drawRows(rows, seed, it)
      // A nested composition whose every tree rendered as one of its coverage rows adds none.
      if (c.trees.nonEmpty) withTrees += 1
      assert(c.trees.isEmpty || nestedIteration(it), s"iteration $it")
      assert(c.outputs.filterNot(c.trees.contains) == plain.outputs, s"iteration $it")
      assert(c.conjuncts.filterNot(cj => c.trees.contains(cj.sql)).map(_.sql) ==
        plain.conjuncts.map(_.sql), s"iteration $it")
      assert(c.data == plain.data && c.ansi == plain.ansi && c.pivot == plain.pivot)
    }
    assert(withTrees >= 28, s"only $withTrees of 30 nested compositions added a tree")
  }

  private def node(t: VarkaCatalystGrammar.Type, template: String,
      children: VarkaCatalystGrammar.Node*): VarkaCatalystGrammar.Node =
    new VarkaCatalystGrammar.Node(t, template, children.asJava)

  test("the shrinker reduces inside a nested output to the subtree that causes it") {
    import VarkaCatalystGrammar.Type._
    // (year(date_add(d, datediff(d2, d))) + l), a BIGINT whose cause is an INT three levels down:
    // as an output it may become that INT.
    val inner = node(INT, "datediff($0, $1)", node(DATE, "d2"), node(DATE, "d"))
    val tree = node(BIGINT, "($0 + $1)",
      node(INT, "year($0)", node(DATE, "date_add($0, $1)", node(DATE, "d"), inner)),
      node(BIGINT, "l"))
    val c = draw(rows, seed, 5).copy(outputs = Vector("year(d)", tree.sql, "month(d)"),
      conjuncts = Vector.empty, trees = Map(tree.sql -> tree))
    val planted = (x: Case) =>
      if (x.outputs.exists(_.contains("datediff(d2, d)"))) Some("values differ") else None
    val small = shrink(c, "values differ", planted)
    assert(small.outputs == Vector("datediff(d2, d)"), small.outputs)
    assert(small.data.size == 1, small.data)
  }

  test("the shrinker keeps a nested conjunct boolean and each node inside it of its type") {
    import VarkaCatalystGrammar.Type._
    // ((i > 3) AND (year(date_add(date_add(d, 3), i)) > l)), failing whenever a date_add is left:
    // the AND gives way to its boolean child and the outer date_add to its inner one, while the
    // comparison cannot become its INT operand, which is not a boolean.
    val dateAdd = node(DATE, "date_add($0, $1)",
      node(DATE, "date_add($0, $1)", node(DATE, "d"), node(INT, "3")), node(INT, "i"))
    val compare = node(BOOLEAN, "($0 > $1)", node(INT, "year($0)", dateAdd),
      node(BIGINT, "l"))
    val tree = node(BOOLEAN, "($0 AND $1)",
      node(BOOLEAN, "($0 > $1)", node(INT, "i"), node(INT, "3")), compare)
    val c = draw(rows, seed, 5).copy(outputs = Vector("year(d)"),
      conjuncts = Vector(Conjunct(tree.sql, negated = false)), trees = Map(tree.sql -> tree))
    val planted = (x: Case) =>
      if (x.conjuncts.exists(_.sql.contains("date_add("))) Some("values differ") else None
    val small = shrink(c, "values differ", planted)
    assert(small.conjuncts.map(_.sql) == Vector("(year(date_add(d, 3)) > l)"), small.conjuncts)
    assert(small.trees(small.conjuncts.head.sql).`type` == BOOLEAN)
  }

  // ---- errors raised in two partitions (VARKA-381) ---------------------------------------------

  /** The partitions of the view `fz` as `varkaSpark` has cached it, and whether `sql` fuses. */
  private def viewShape(sql: String): (Int, Boolean) = (
    varkaSpark.table("fz").queryExecution.executedPlan.execute().getNumPartitions,
    find(varkaSpark.sql(sql).queryExecution.executedPlan)(isVarkaNode).isDefined)

  test("the view is two partitions, one coalesced or a row alone, and fuses in each") {
    val c = draw(rows, seed, 0)
    for ((view, parts) <- Seq[(DataFrame => DataFrame, Int)](
        (identity, 2), (_.coalesce(1), 1), (_.offset(5).limit(1), 1))) {
      cacheFixture(c.fixture, view)
      try assert(viewShape("SELECT year(d) AS c0 FROM fz") == ((parts, true))) finally {
        uncacheFixture()
      }
    }
  }

  /**
   * Runs `body` with `fz_pause(x)` registered in both sessions, and drops it afterwards. It returns
   * `x`; for a marked row (`x` of 13 or 7, the first row of each partition of the test below) in
   * a task of a query over two partitions, in partition `rowWaits` of the row engine's queries
   * and `varkaWaits` of Varka's, it first waits until the task is killed, as a task is once
   * another task of its job has failed, at most five seconds and once per task. So the test
   * decides which partition of a two-partition query fails first; over one partition nothing
   * waits.
   */
  private def withPause[T](rowWaits: Int, varkaWaits: Int)(body: => T): T = {
    def pause(waits: Int): Int => Int = { x =>
      val task = org.apache.spark.TaskContext.get()
      if ((x == 13 || x == 7) && task.numPartitions() == 2 && task.partitionId() == waits) {
        val until = System.nanoTime() + 5000000000L
        while (!task.isInterrupted() && System.nanoTime() < until) Thread.sleep(5)
      }
      x
    }
    spark.udf.register("fz_pause", pause(rowWaits))
    varkaSpark.udf.register("fz_pause", pause(varkaWaits))
    try body finally {
      for (session <- Seq(spark, varkaSpark)) {
        session.sql("DROP TEMPORARY FUNCTION IF EXISTS fz_pause")
      }
    }
  }

  test("two partitions that raise different listed conditions compare the same on every run") {
    // Under ANSI, row 0 (partition 0) raises in `make_date` with month 13 and row 6 (partition 1)
    // overflows `l + 1`: two listed conditions on rows in different partitions, as VARKA-298.md
    // 9.3 met them. `fz_pause` decides which partition fails first on each engine.
    val data = draw(rows, seed, 0).data.zipWithIndex.map { case (row, k) =>
      row.updated(2, k match { case 0 => "13"; case 6 => "7"; case _ => "3" })
        .updated(6, s"CAST('${if (k == 6) Long.MaxValue else 0L}' AS BIGINT)")
    }
    val fixture = fixtureSql(data)
    val select = "make_date(2021, i, 1) AS c0, (l + 1) AS c1"
    val where = Some("fz_pause(i) > 0")
    val month = "DATETIME_FIELD_OUT_OF_BOUNDS.WITHOUT_SUGGESTION"
    val overflow = "ARITHMETIC_OVERFLOW"
    // The comparison before VARKA-381: one run on each engine over the two partitions.
    def firstRun(): Option[String] = {
      cacheFixture(fixture)
      try withAnsi(true) {
        assert(viewShape("SELECT year(d) AS c0 FROM fz")._1 == 2)
        val query = querySql(select, where)
        difference(outcome(spark, query), outcome(varkaSpark, query))
      } finally uncacheFixture()
    }
    // Which partition waits on the row engine and on Varka.
    val timings = Seq((0, 0), (0, 1), (1, 0), (1, 1))
    val before = timings.map { case (r, v) => withPause(r, v)(firstRun()) }
    assert(before == Seq(None, Some(s"error classes differ: $overflow, $month"),
      Some(s"error classes differ: $month, $overflow"), None), before)
    // The condition of the partition that does not wait, which the first run raises.
    def firstRaised(waits: Int): String = if (waits == 0) overflow else month
    for (rerun <- VarkaErrorRerun.values().toSeq; (r, v) <- timings; _ <- 0 until 2) {
      val seen = errorsSeen.toMap
      val kind = withPause(r, v) {
        disagreement(fixture, select, where, ansi = true, partitions = false, rerun = rerun)
      }
      assert(kind.isEmpty, s"$rerun, waits ($r, $v): $kind")
      val raised = errorsSeen.collect { case (key, n) if n > seen.getOrElse(key, 0) => key }.toSet
      // Over one partition the first raising row's condition; row by row, each row's.
      // Counted once each: the first run's condition, the first raising row's over one
      // partition, and row by row each row's.
      val conditions = if (rerun == VarkaErrorRerun.EACH_ROW) Set(month, overflow) else Set(month)
      val expected = conditions.flatMap(c => Set((c, "row", "ANSI"), (c, "varka", "ANSI"))) +
        ((firstRaised(r), "row", "ANSI")) + ((firstRaised(v), "varka", "ANSI"))
      assert(raised == expected,
        s"$rerun, waits ($r, $v): $raised")
    }
  }

  test("a difference only the two partitions show keeps which side raised and not the condition") {
    def withoutConditions(kind: String): Option[String] =
      VarkaErrorRerun.withoutConditions(kind).toScala
    assert(withoutConditions("only the row engine throws: ARITHMETIC_OVERFLOW")
      .contains("only the row engine throws"))
    assert(withoutConditions("with an output forced to decline, only Varka throws: X")
      .contains("with an output forced to decline, only Varka throws"))
    assert(withoutConditions("values differ").contains("values differ"))
    assert(withoutConditions("error classes differ: X, Y").isEmpty)
    // An unlisted condition is a finding whichever task raised it.
    val unlisted = "unexpected error condition X from Varka under ANSI"
    assert(withoutConditions(unlisted).contains(unlisted))
    import VarkaErrorRerun._
    assert(fromProperty(null) == ONE_PARTITION && fromProperty("eachRow") == EACH_ROW)
    intercept[IllegalArgumentException](fromProperty("twice"))
    assert(ofNote(s"seed 1 iteration 2${EACH_ROW.noteSuffix}") == EACH_ROW)
    assert(ofNote(s"seed 1 iteration 2${ONE_PARTITION.noteSuffix}") == ONE_PARTITION)
  }
}
