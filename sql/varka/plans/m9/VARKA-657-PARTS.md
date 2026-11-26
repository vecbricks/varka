# VARKA-657, the row's own parts: the first zoned kernels end to end

*Planned on 26 November 2026. This file stands alone: a builder needs it and the repository's
`AGENTS.md` files. The row is not started, since milestone 9 waits for the owner's word
(`m9/PLAN.md` decision 18). Its code facts were checked on master `176db41f369`. The design of
the whole family (row 657 and rows 682 to 692) is `sql/varka/plans/m9/VARKA-657.md`; this file
is the build plan of row 657's own parts, and it restates what it needs of that design.*

## 1. What this row is

**The row's text** (`m9/PLAN.md` section 3): "its plan and its probes first; then the first
kernels end to end over row 682's node: the zone's index and the fold of a fixed zone, `hour`,
`minute`, `second` and `CAST(ts AS DATE)`, the zone programs over row 678's data, the suite
whose rows straddle both changes of a year, the coverage rows and the benchmark sections.
**Done when** the four entries fuse under a zone with changes and equal Spark on rows that
straddle both changes of a year; and a batch that spans more changes than its kernel's form
declines by name." The probes merged as #1748. The plan is `VARKA-657.md`, a pull request of
its own. This file plans the rest.

**What the row builds** is the compiler side of the zone family:

- `VarkaZoneCompiler`, a new compiler class. It claims the nodes that read the session time
  zone, turns an instant into a local time, keeps the zone's index, and declines by name what
  no kernel answers yet.
- The family `ZONE` and its switch, `spark.sql.codegen.varka.family.zone.enabled`.
- Four fused entries over a `TIMESTAMP` column: `hour`, `minute`, `second` and
  `CAST(ts AS DATE)`. Each is the `TIMESTAMP_NTZ` lowering over the local time.
- The production zone data (row 678), read by the compiler and by the runner on the executor.
- `VarkaTimestampZoneSuite`, whose rows straddle both changes of a year.
- The coverage rows, the census cells, the documentation and the benchmark sections.

**What it gives the rows after it.** Rows 684, 685, 687, 688, 689 and 690 start from its
kernels. Row 668's calendar fields need one guard more, since `year(ts)` reaches the compiler as
`Year(Cast(ts AS DATE))` and this row fuses the cast (4.7). Rows 669 to 671 call
`VarkaZoneCompiler.toLocal` and add their claims to the family. Row 691 measures on the
benchmark sections that this row lists as fused.

**What must be merged before it starts.**

| row | what this row reads of it |
| :--- | :--- |
| 682 | the `ToLocal` record and its registries, `CompiledVarkaProjection.zones()`, `VarkaZoneData` and its `Provider`, `VarkaBatchArgs`, the choice `zoneForm`, the lowering, the runner's fill and `STATUS_BATCH_ARGS` |
| 683 | `LongRangeOps.leastAndGreatest`, through row 682's fill |
| 656 | `TIMESTAMP` on the long lane: its column leaf, its Arrow vector in and out, a `TIMESTAMP` column in the coverage table, its conformance suite |
| 678 | `VarkaChronoZone`, the production zone data behind `VarkaZoneData`, with its provider |
| 655 | the baseline benchmark classes over a `TIMESTAMP`, and both widths' committed results |
| 654 | the census and the cast table with the type `ltz`. Row 656 needs it too. Parts C and D edit its files |

**The goals it serves** (`m9/PLAN.md` 1.1), each with the item of 1.2 that it moves:

| goal | what the row moves | item of 1.2 |
| :--- | :--- | :--- |
| G1 | four `TIMESTAMP_LTZ` pairs become fused: `hour`, `minute`, `second` and the cast alias `date` | 1: three quarters of the type's pairs |
| G2 | each of the four kernels gets a test whose rows straddle both changes of a year. The sweep over every zone is row 684's. That ten years in no order decline no batch is served only through rows 685 and 686: here such a batch declines by name | 2 |
| G3 | one temporal cast pair, `ltz` to `date`, fused in the three modes | 3 |
| G7 | the first sections that read the zone are listed as fused and measured at both widths. The bar for every form is row 691's reading. The 128-bit bar is served only through row 689 | 10 |
| G5 | part D is written as the template of row 668's tasks (section 8) | 8 |
| G4, G6 | only through later rows: a nanosecond `TIMESTAMP_LTZ` takes this conversion on its microsecond word (row 673), and the post quotes the measurement (row 676) | 4 and 9 |

## 2. What an agent must know

### 2.1 The idea

A `TIMESTAMP` is a long: microseconds since the epoch in UTC. It is the same eight bytes as a
`TIMESTAMP_NTZ`. Spark reads a field of it in the session time zone. It adds the zone's offset
at that instant, and reads the field of the local time it gets.

A zone's offset is a step function of the instant. A zone is a sorted list of instants `T[i]`
and the offset `O[i]` that holds from `T[i]` on. So `local = instant + O[#{T <= instant}]`.

A zone with one offset for ever is a **fixed zone**. The compiler folds it. Under UTC the local
time is the instant itself, so the kernel of `hour(ts)` is the kernel of `hour(ntz)`. Under a
fixed offset the local time is the instant plus a literal.

For any other zone the compiler builds the IR node `ToLocal(instant, zone)` (row 682). Its
values for one batch are the offset at the batch's least instant and the changes inside the
batch's range. The runner computes them before each call and writes them into the kernel's
long arguments, after the literals. The runner finds the range by one pass over the column
(row 683). The zone's id is not in the IR: the node holds an index into the compiled
projection's list of ids, so two sessions in two zones share one kernel class.

A kernel is emitted for one **form**: how many changes a batch may span. A batch that spans
more is not served by that kernel. The runner declines it and Spark's row path answers it.

Above the local time nothing is new. `hour(ts)` is the `TIMESTAMP_NTZ` tree of `hour` over the
local time, and `CAST(ts AS DATE)` is the `TIMESTAMP_NTZ` cast over it.

### 2.2 Terms

- **Instant**: a `TIMESTAMP` value. **Local time**: the instant plus the zone's offset, in
  microseconds, what a `TIMESTAMP_NTZ` holds. **Change**: an instant at which a zone's offset
  moves, as at the start and at the end of daylight saving.
- **Kernel**: the emitted vector loop for a projection or a filter. **Row path** or **row
  engine**: Spark's own evaluation, row by row. **Entry**: one expression of a projection.
- **Fused**: an entry that a kernel computes. **Declines by name**: the compiler refuses an
  entry with a reason text; the entry runs on the row path and the verbose `EXPLAIN` shows the
  reason. **A batch declines**: at run time the kernel or the runner refuses one batch with a
  status, and the row path answers that batch alone.
- **Sink**: `DeclineSink`, the object every compiler arm receives. It holds the first decline
  reason of an entry and the side tables a compile appends to. A table has a mark and a
  truncate: a declining entry leaves nothing behind.
- **Literal slot**: `VarkaVectorIR.LiteralSlot(index, lane)`, a constant whose value is outside
  the IR. `DeclineSink.longSlot(value)` interns a long by value.
- **Batch arguments**: the entries of `longArgs` after the literals that the runner writes for
  each batch. **Program**: one zone node's run of batch arguments.
- **Form**: the value of the emit option `zoneForm`: `ONE` (no change in a batch), `STEPS_2`
  (two, the default until row 685) or `STEPS_8` (eight). Its number of steps is `K`.
- **Family**: a member of `VarkaFamily`, a set of nodes with one session switch. A family that
  is off declines every node it claims with `VarkaFamily.offReason()`.
- **Coverage row**: a row of `VarkaCoverageSuite`'s table, generated into
  `sql/varka/coverage.json` and `docs/sql-varka.md`. Without one an entry does not count as
  fused. **Census cell**: an (entry, type) pair of `dev/varka_census.py`.
- **Harness**: `VarkaExpressionHarness`, which runs an evaluator over Arrow batches with no
  session and compares each row with Spark's own projections.

### 2.3 The names this row reads, and the names it adds

Read, as the shared contract of the family has them (B0 of each part greps them):

| name | where | meaning |
| :--- | :--- | :--- |
| `record ToLocal(VarkaVectorIR instant, int zone, int hash)` under `sealed interface Zoned` | `VarkaVectorIR`, row 682 | an instant to the local time. `zone` is an index into `zones()`. A null operand makes a null. It fails no row |
| `CompiledVarkaProjection.zones()` | row 682 adds the component | `List<String>`, the zone ids by index. This row fills it through the sink |
| `VarkaZoneData`, `VarkaZoneData.Provider { VarkaZoneData of(String zoneId); }` | `codegen/varka`, row 682 | a zone's data for vector code: `fixed()`, `changes(least, greatest)`, `toLocalArgs(...)`. Resolved on the executor |
| `VarkaChronoZone` | row 678 | the production `VarkaZoneData`, built once a zone in each JVM from that JVM's `ZoneRules`. A zone that fails its build-time check is not served |
| `VarkaJavaTimeZoneData` | catalyst test scope, row 682 | a reference built from `java.time`, for tests |
| `zoneForm`, `enum ZoneForm { ONE, STEPS_2, STEPS_8 }` | `VarkaEmitOptions`, row 682 | one form for every zone node of a kernel |
| `VarkaBatchArgs.layout(outputs, numLongLiterals, options)`, `Program`, `count(node, form)` | row 682 | the batch arguments of a kernel. `count` of a `ToLocal` is `1 + 2K` |
| `VarkaKernelRunner.STATUS_BATCH_ARGS` | row 682 | the batch's range is not covered by the kernel's form |
| `LongRangeOps.leastAndGreatest` | row 683 | the least and greatest live value of a long column; false when no row is live |

Added by this row:

| name | where | meaning |
| :--- | :--- | :--- |
| `VarkaZoneCompiler` | `codegen`, Java, package-private | the family's arm, `toLocal`, the declines by name |
| `VarkaFamily.ZONE`, key `zone` | `VarkaFamily` | the switch `spark.sql.codegen.varka.family.zone.enabled` |
| `DeclineSink.zone(String)`, `zoneMark()`, `truncateZones(int)`, `zoneIds()` | `DeclineSink` | the compile's zone table |
| `VarkaTimeCompiler.hourOfLocal`, `minuteOfLocal`, `secondOfLocal`; `VarkaNodeCompiler.dateOfLocal` | the two compilers | the `TIMESTAMP_NTZ` lowerings over a local value |
| `VarkaZoneCompilerSuite` | catalyst, Java, JUnit 5 | the compile: the fold, the index, the declines, the claims |
| `VarkaTimestampZoneSuite` | sql/core, Java, JUnit 5, no session | each kernel against Spark's row engine across each zone's changes |
| `VarkaTimestampZoneQuerySuite` | sql/core, ScalaTest | the paths only a session has |
| `zone_fold.smt2` | `sql/varka/proofs` | the fixed offset's sum cannot leave a long under its guard |
| `ltzy` | a column of the coverage table | a `TIMESTAMP` inside the year 2021 |
| `--zone` | `dev/varka_emit.sh` | the session zone the tool resolves under |

## 3. The admission check

Each fact was checked on `176db41f369` by the command beside it, run from the repository's
root. `<spark>` is `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst`, `<gen>` is
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen`, `<ir>` is
`<gen>/varka`, `<exec>` is `sql/core/src/main/java/org/apache/spark/sql/execution`. `<gent>`
and `<gens>` are `<gen>` under `src/test/java` and `src/test/scala`; `<exect>` and `<execs>`
are `<exec>` under `src/test/java` and `src/test/scala`.

**Where Spark carries the zone, and what its row path calls.**

| fact | command | prints |
| :--- | :--- | :--- |
| `TimeZoneAwareExpression.zoneId` is resolved from a string in the JVM that evaluates: it is transient and lazy | `grep -n "lazy val zoneId" <spark>/expressions/datetimeExpressions.scala` | `@transient lazy val zoneId: ZoneId = DateTimeUtils.getZoneId(timeZoneId.get)` |
| `Hour`, `Minute` and `Second` are `GetTimeField`s: the row path calls `func(micros, zoneIdInEval)`, and `zoneIdInEval` is `zoneId` for a `TIMESTAMP` child | `grep -n "func(timestamp.asInstanceOf" <spark>/expressions/datetimeExpressions.scala` | one line, in `GetTimeField.nullSafeEval` |
| `DateTimeUtils.getHours`, `getMinutes` and `getSeconds` read a field of `getLocalDateTime(micros, zoneId)` | `grep -n -A1 "def getHours\|def getMinutes\|def getSeconds(" <spark>/util/DateTimeUtils.scala` | three bodies, each `getLocalDateTime(micros, zoneId).get...` |
| `getLocalDateTime` is `Instant.atZone` | `grep -n -A1 "def getLocalDateTime" sql/api/src/main/scala/org/apache/spark/sql/catalyst/util/SparkDateTimeUtils.scala` | `microsToInstant(micros).atZone(zoneId).toLocalDateTime` |
| `Cast` of a `TIMESTAMP` to `DATE` is `microsToDays(t, zoneId)` in every mode, and it cannot fail | `grep -n "microsToDays(t, zoneId)" <spark>/expressions/Cast.scala` | one line, in `Cast.castToDate` |
| `microsToDays` is the local date of `getLocalDateTime` | `grep -n -A1 "def microsToDays" sql/api/src/main/scala/org/apache/spark/sql/catalyst/util/SparkDateTimeUtils.scala` | `localDateToDays(getLocalDateTime(micros, zoneId).toLocalDate)` |
| The session accepts regions, offsets and `ZoneId.SHORT_IDS` | `grep -n "ZoneId.SHORT_IDS" sql/api/src/main/scala/org/apache/spark/sql/catalyst/util/SparkDateTimeUtils.scala` | one line, in `SparkDateTimeUtils.getZoneId` |
| `ZoneId.normalized` returns the rules' offset exactly when `ZoneRules.isFixedOffset`; an offset is within 18 hours | read in JDK 25's source, `java/time/ZoneId.java`, `java/time/zone/ZoneRules.java` and `java/time/ZoneOffset.java` (`MAX_SECONDS = 18 * SECONDS_PER_HOUR`) | on the laptop the source is under `/home/max/proj/openjdk-build/jdk25/src/java.base/share/classes` |

**Where Varka's compilers would read it.**

| fact | command | prints |
| :--- | :--- | :--- |
| The `TIMESTAMP_NTZ` arms of `hour`, `minute` and `second` are in `VarkaTimeCompiler.arm`, each guarded by `VarkaTypes.isNtz` | `grep -c "when VarkaTypes.isNtz(h.child().dataType())\|when VarkaTypes.isNtz(m.child().dataType())\|when VarkaTypes.isNtz(s.child().dataType())" <gen>/VarkaTimeCompiler.java` | `3` |
| `hourOf`, `minuteOf` and `secondOf` are private and take the count of the day, not an expression; `ntzMicros` is private and takes an expression | `grep -n "private static VarkaVectorIR hourOf\|private static VarkaVectorIR minuteOf\|private static VarkaVectorIR secondOf\|private static Optional<VarkaVectorIR> ntzMicros" <gen>/VarkaTimeCompiler.java` | four lines |
| `VarkaNodeCompiler.microsOfDay`, `floorDivLong`, `ntzDay` and `longOperand` are package-private and take an IR value; `dateOfNtz` is private and takes a `Cast` | `grep -n "static VarkaVectorIR microsOfDay\|static VarkaVectorIR floorDivLong\|static VarkaVectorIR ntzDay\|private static Optional<VarkaVectorIR> dateOfNtz\|static Optional<VarkaVectorIR> longOperand" <gen>/VarkaNodeCompiler.java` | five lines |
| `GuardedRange(child, lo, hi)` declines the batch where a live lane is outside; its bounds are part of the node | `grep -n "public GuardedRange(VarkaVectorIR child, long lo, long hi)" <ir>/VarkaVectorIR.java` | one line, the `FAIL` form |
| The compiler builds a long-lane guard near a long's ends already | `grep -n "Long.MIN_VALUE + 1, Long.MAX_VALUE" <gen>/VarkaTimeCompiler.java` | one line, in `ntzPlusInterval` |
| A declined guard reports `STATUS_VALUE_RANGE` | `grep -n "int STATUS_VALUE_RANGE" <ir>/VarkaFusedKernel.java` | `int STATUS_VALUE_RANGE = 1 << 5;` |
| The compile runs in the task, on the executor | `grep -n "compilePartial(projectList, childAttributes()" <exec>/VarkaKernelEvaluator.java; grep -n "compilePredicate(condition, childAttributes()" <exec>/VarkaFilterEvaluator.java` | one line each: `VarkaKernelEvaluator.compiled` and the filter evaluator's compile |
| It also runs on the driver, for the plan and `EXPLAIN` | `grep -n "VarkaExpressionCompiler.compilePartial" sql/core/src/main/scala/org/apache/spark/sql/execution/VarkaColumnarRule.scala` | one line, in `VarkaColumnarRule.compilePartial` |
| What a task runs follows its own compile, not the driver's | `grep -n "kernels.partialPlan" sql/core/src/main/scala/org/apache/spark/sql/execution/VarkaColumnarToRowExec.scala` | one line, in the row node's `mergeProjection` |
| `DeclineSink.longSlot` interns by value, and the classifier's `Marks` hold five table sizes | `grep -n "private record Marks" <gen>/VarkaExpressionCompiler.java` | `Marks(int inputs, int literals, int longs, int bounds, int used)` |

**How a family is declared and switched.**

| fact | command | prints |
| :--- | :--- | :--- |
| A family's `SQLConf` entry comes from the enum: no edit of `SQLConf.scala` | `grep -n "VarkaFamily.values().toSeq.map" sql/catalyst/src/main/scala/org/apache/spark/sql/internal/SQLConf.scala` | one line, `VARKA_FAMILY_SWITCHES` |
| The entry declares a binding policy, so the audit suite `SparkConfigBindingPolicySuite` (sql/hive) passes with no edit | `grep -n -A12 "private def varkaFamilySwitch" sql/catalyst/src/main/scala/org/apache/spark/sql/internal/SQLConf.scala` | `.withBindingPolicy(ConfigBindingPolicy.NOT_APPLICABLE)` |
| A node is claimed by at most one link, and every link must claim something | `grep -n "val idle = counts.collect" <gens>/VarkaFamilyChainSuite.scala` | one line |
| The compiler's record of families must equal the families that claim the row's nodes, and each family needs a coverage row or an `UNBUILT` probe | `grep -n "UNBUILT = Map.of()" <gent>/VarkaFamilySwitchSuite.java` | one line |
| A cast of a column is asked its switch after its lowering ran | `grep -n "boolean relabel = expr instanceof Cast" <gen>/VarkaNodeCompiler.java` | one line, in `inFamily` |
| The coverage suite reads the compiler's arms from a list of files | `grep -n -A1 "val javaFiles" <gens>/varka/VarkaCoverageSuite.scala` | seven file names, `VarkaTimeCompiler` among them |

**Declines.** A reason is a string at its `sink.decline(reason, e)` site, a `static final`
where two sites share it. A test asserts the exact text.

| fact | command | prints |
| :--- | :--- | :--- |
| The compiler suite reads a decline's reason and compares it with `===` | `grep -n "private def declineReason" <gens>/VarkaExpressionCompilerSuite.scala` | one line |
| A Java suite does the same (the model of this row's) | `grep -n "private static String reason" <gent>/VarkaMathCompilerSuite.java` | one line |
| No test asserts a lane-mismatch text, so a new one of that kind collides with none | `git grep -n "not on the long lane" -- sql/catalyst/src/test sql/core/src/test` | nothing |
| After the cast fuses, `year(ts)` declines by the calendar's own reason | `grep -n "day producer the calendar range analysis does not bound" <gen>/VarkaChronoCompiler.java` | one line, in `admitCalendar`; `VarkaRangeAnalysis.range` answers unknown for a `NarrowLane` as a day |

**Tests, generated files and tools.**

| fact | command | prints |
| :--- | :--- | :--- |
| The harness reports each batch's status | `grep -n "public record Batch(int start, int rows, int status)" <exect>/VarkaExpressionHarness.java` | one line |
| Its batches come from the Arrow cache's own iterators, under the conf's zone | `grep -n "conf.sessionLocalTimeZone()" <exect>/columnar/VarkaCacheBatches.java` | two lines, in `each` and `written` |
| The `TIMESTAMP_NTZ` session suite caches a table and reads the node's metrics | `grep -n "numFallbackBatchesDeclined" <execs>/VarkaTimestampNtzSuite.scala` | several lines; the first is in `checkServed` |
| A session suite may make 40 answer checks | `grep -n "^LIMIT = " dev/varka_session_lint.py` | `LIMIT = 40` |
| The census refuses a fused cast that no coverage row casts | `grep -n "no coverage row casts it" dev/varka_census.py` | two lines, in `cast_errors` |
| The session differential runs the first row of each coverage family and asserts that kernels ran | `grep -n "rows.find(_.family == r.family).contains(r)" <execs>/VarkaCoverageDifferentialSuite.scala` | one line, `sampleRows` |
| A baseline's queries suite fails a section that fuses unlisted | `grep -n "a section fuses exactly when its class" <execs>/benchmark/VarkaTimestampNtzFieldsBaselineQueriesSuite.scala` | one line |
| The emit tool knows no timestamp column and no zone | `grep -c -i "timestamp" <gent>/varka/VarkaEmitDump.java` | `0` |
| The width audit's probe sizes the long table by the literals alone | `grep -n "val longLits = Array.fill" <gens>/varka/VarkaWidthAuditProbe.scala` | one line, in `runHot` |
| A ScalaTest suite fixes the JVM's zone; a JUnit suite does not | `grep -n "TimeZone.setDefault" core/src/test/scala/org/apache/spark/SparkTestSuite.scala; grep -c "TimeZone" <gent>/varka/VarkaSuiteExtension.java` | `America/Los_Angeles`; then `0` |

**Numbers.** On the EPYC 7763 the row path of `hour(ntz)` over an Arrow-cached table costs
64.1 ns a row (`VarkaTimestampNtzFieldsBaselineBenchmark-jdk25-runner-epyc7763-results.txt`).
On the laptop the probe's `hour` kernel with two steps costs 0.715 ns a row at four lanes and
0.479 with one offset, where `java.time` costs 25.409; at two lanes the masked steps cost
15.679 (`sql/varka/spikes/zone-offset/results/kernels.txt`). The pass for a batch's range
costs 0.232 ns a row at four lanes (`sql/varka/spikes/zone-offset/results/forms.txt`).

**What I could not check.** Rows 682, 683, 656, 678, 655 and 654 are not built, so every name
of theirs beyond the contract is a premise: B0 of each part greps it. No JVM was run: the emit
tool has no timestamp column on the base, so the IR and the op counts of the
`TIMESTAMP_NTZ` twins were not printed. The dates of the changes of the zones in section 6
were not printed either; the suite computes them from `ZoneRules`. No timing was taken.

## 4. The design

### 4.1 The arms live in `VarkaZoneCompiler`, the family's link of the chain

The family design put the arms in `VarkaTimeCompiler` and `VarkaNodeCompiler`. The code does not
allow that. A node is under exactly one switch, and the suites read the switch from the link
that claims the node (section 3, "How a family is declared"). So the family `ZONE` must claim
its nodes itself:

- `VarkaZoneCompiler.arm` is a link of `VarkaNodeCompiler.FAMILIES`, placed after `TIME`.
- It claims by class and type alone, as every arm does: `Hour`, `Minute` and `Second` whose
  child is a `TimestampType`, and a `Cast` to `DateType` whose child is a `TimestampType`.
- The lowering is the `TIMESTAMP_NTZ` one: the same helpers over the local value (4.4).

The switch therefore declines these nodes under every zone, UTC included. A claim cannot look
at the zone's rules: it must be free of side effects.

### 4.2 `toLocal`

    static Optional<VarkaVectorIR> toLocal(
        Expression whole, Option<String> timeZoneId, VarkaVectorIR instant, DeclineSink sink) {
      if (timeZoneId.isEmpty()) {
        return sink.decline(NO_ZONE_REASON, whole);
      }
      ZoneId zone;
      try {
        zone = DateTimeUtils$.MODULE$.getZoneId(timeZoneId.get()).normalized();
      } catch (DateTimeException e) {
        return sink.decline(unresolvedReason(timeZoneId.get()), whole);
      }
      if (zone instanceof ZoneOffset offset) {
        long micros = offset.getTotalSeconds() * DateTimeConstants.MICROS_PER_SECOND;
        if (micros == 0L) {
          return Optional.of(instant);
        }
        return Optional.of(new IntArith(IntOp.ADD, Overflow.WRAP,
            new GuardedRange(instant, Long.MIN_VALUE + MAX_OFFSET_MICROS,
                Long.MAX_VALUE - MAX_OFFSET_MICROS),
            sink.longSlot(micros)));
      }
      if (!(instant instanceof ColumnRef)) {
        return sink.decline(COMPUTED_OPERAND_REASON, whole);
      }
      if (served(zone.getId()) == null) {
        return sink.decline(unservedReason(zone.getId()), whole);
      }
      return Optional.of(new ToLocal(instant, sink.zone(zone.getId())));
    }

- **How "fixed" is decided.** By `java.time` alone, in the JVM that compiles:
  `ZoneId.normalized()` returns a `ZoneOffset` exactly when `ZoneRules.isFixedOffset()`. So
  `UTC`, `Z`, `+01:00`, `GMT+1` and `Etc/GMT+5` fold, and `Asia/Kathmandu` does not: it has
  changes in its past. The compile runs on the executor, so the kernel that runs follows the
  executor's rules. It also runs on the driver; a driver that disagrees moves `EXPLAIN` and
  speed, never an answer (section 3).
- **UTC** (an offset of zero, in any spelling): the operand itself. No node, no literal.
- **A fixed offset**: a wrapping sum with a literal slot under a `GuardedRange`. The guard's
  bounds are `Long.MIN_VALUE + M` and `Long.MAX_VALUE - M`, with `M = MAX_OFFSET_MICROS =
  ZoneOffset.MAX.getTotalSeconds() * MICROS_PER_SECOND`, 64,800,000,000. They do not depend on
  the offset, so every fixed offset shares one kernel class. A batch with a live instant
  within 18 hours of a long's end declines with `STATUS_VALUE_RANGE`, and the row path answers
  it: `java.time` does not wrap there. `zone_fold.smt2` proves that the sum cannot leave a long
  inside the guard and first leaves it one microsecond outside.
- **Any other zone**: `ToLocal` over the column, with the zone's index. The id kept is
  `ZoneId.getId()` after Spark's own resolution, so `PST` and `America/Los_Angeles` are one
  index. The table holds no fixed zone.
- **`served(id)`** asks the provider: `provider.of(id)`, null where the zone is not served. The
  provider is row 678's, held in one `private static volatile` field with a package-private
  `setProviderForTesting`, as `VarkaNodeCompiler.setFailInFamilyForTesting` is.

### 4.3 The zone's index through the sink

`DeclineSink` gains a table of zone ids, in first-use order: `zone(String id)` returns the id's
index and appends it on first use; `zoneMark()` and `truncateZones(int)` follow the tables'
rollback discipline; `zoneIds()` is the list for the compiled plan. `Marks` in
`VarkaExpressionCompiler` gains a sixth size, so a declining entry or conjunct leaves no zone
behind. `Tables` passes `sink.zoneIds()` to `CompiledVarkaProjection`. In a query every
zone-aware expression carries the session zone, so the table holds one id, at index 0. A
hand-built tree with two zones gets two indexes. The local rollbacks of `VarkaIntervalCompiler`,
`VarkaDecimalCompiler` and `VarkaNodeCompiler`, which call `truncateLong`, stay as they are: a
retried form asks for the same id and gets the same index.

### 4.4 Which methods take a local value as they are, and which change

| method | as it is | change |
| :--- | :--- | :--- |
| `VarkaNodeCompiler.microsOfDay(t, sink)` | yes: any long | none |
| `VarkaNodeCompiler.floorDivLong(x, divisor, sink)` | yes | none |
| `VarkaNodeCompiler.ntzDay(day)` | yes; row 668 calls it, this row does not | none |
| `VarkaNodeCompiler.longOperand` | not used: its reason speaks of a bigint | none |
| `VarkaTimeCompiler.hourOf`, `minuteOf`, `secondOf` | they take the count of the day and stay private | three package-private wrappers beside them: `hourOfLocal(local, sink)` is `hourOf(VarkaNodeCompiler.microsOfDay(local, sink), MICROS_PER_SECOND, ONE_DAY_MICROS)`, and `minuteOfLocal`, `secondOfLocal` alike. The `TIMESTAMP_NTZ` arms call them |
| `VarkaTimeCompiler.ntzMicros` | no: it compiles an expression | none; the cast to `TIME` keeps it |
| `VarkaNodeCompiler.dateOfNtz` | no: it takes the `Cast` | `dateOfLocal(local, sink)`, package-private, is `new NarrowLane(floorDivLong(local, MICROS_PER_DAY, sink))`; `dateOfNtz` calls it |

The refactoring moves no emitted byte: the literal slots are interned in the same order, and
`git diff --exit-code -- sql/varka/emitted_bytes.json` is its oracle.

### 4.5 What declines, and with which text

Compile-time declines, each a constant or a method of `VarkaZoneCompiler`:

| case | reason text |
| :--- | :--- |
| the node carries no zone (a hand-built tree) | `a zone-aware expression with no time zone, which the analyzer sets` |
| the id does not resolve on this JVM | `the time zone <id> does not resolve on this JVM` |
| a zone with changes over an operand that is no column (until row 688) | `the session time zone over a computed timestamp: a kernel finds a batch's offsets from the range of a column, and the range of a computed value is row 688` |
| a zone the data does not serve | `the time zone <id>: its rules fail the zone data's check on this JVM, so no kernel reads it` |
| the operand compiled onto another lane | `a timestamp operand that is not on the long lane` |
| the family is off | `VarkaFamily.ZONE.offReason()` |
| an arm not built yet (parts A and B only) | `hour of a TIMESTAMP is row 657's: not fused yet`, and the same for `minute`, `second` and `CAST(TIMESTAMP AS DATE)` |

A computed operand under a fixed zone does not decline: the fold is plain arithmetic.

Run-time declines of a batch:

| case | status | who raises it |
| :--- | :--- | :--- |
| the batch spans more changes than the form's `K` | `VarkaKernelRunner.STATUS_BATCH_ARGS` | the runner's fill (row 682) |
| a zone with changes, a live instant within 18 hours of a long's end | `STATUS_BATCH_ARGS` | the program's fill (row 682) |
| a fixed offset, a live instant within 18 hours of a long's end | `VarkaFusedKernel.STATUS_VALUE_RANGE` | the kernel's guard |

### 4.6 Edge cases

- **Null rows.** `ToLocal` and the sum are null where the operand is. The guard and the range
  pass read live lanes only. A null lane computes on whatever its storage holds, and the
  output's validity hides it.
- **The ends of a long.** 4.5: declined, and answered by the row path. The suite holds both.
- **A batch with no live row.** `LongRangeOps.leastAndGreatest` answers false. The runner must
  then fill a padding run and run the kernel: every output is null. The suite asserts that such
  a batch is answered. This is a premise on row 682, tested here.
- **Two lanes of 64 bits** (128 bits on x86). This JVM compiles no vector mask there. The
  node's masked steps and the fold's guard run as Java loops: right answers, low speed. A
  filter driven at the long lane is refused there by the rule that exists
  (`VarkaKernelPlan.TWO_LANE_CONDITION_REASON`). The rest is row 689's.
- **AVX2 against AVX-512.** The compiler builds the same IR at every width. The benchmark runs
  at 256 and 128 bits on the runner; the laptop adds 512.
- **Driver and executor disagree** on a zone's rules. Each compiles for itself (4.2).
- **The zone of a JUnit JVM.** A ScalaTest suite runs under `America/Los_Angeles`. A JUnit
  suite, a forked probe and a tool run under the machine's zone, which is UTC on a runner. So
  part A makes catalyst's test resolver, `VarkaSqlResolve`, analyse under
  `America/Los_Angeles` unless the caller's conf names a zone: one row is then one tree
  everywhere. The sql/core harness keeps the JVM's default, because `VarkaHarnessParitySuite`
  holds its plans equal to a session's. So every new sql/core test sets the zone itself, and
  the coverage column (4.8) passes under a fixed zone and under a zone with at most two
  changes in 2021.

### 4.7 What fuses by composition once the cast is fused

`year(ts)` is `Year(Cast(ts AS DATE))`. After part D the cast fuses, and the calendar family
then declines the field with its own reason, "day producer the calendar range analysis does not
bound", under every zone: the plain cast carries no day range. Row 668 adds the guard. A
`DATE` operator that takes any day (a comparison, `datediff`, `date_add` by a literal,
`dayofweek`) may fuse over the cast with no new code. Its tree above the cast is the
`TIMESTAMP_NTZ` one and the day is exact, so it is right. Part D holds such forms to Spark's
answers without claiming them; their coverage rows are row 668's.

### 4.8 What a fused entry adds beside its code

| what | where on the base commit | regenerated or checked by |
| :--- | :--- | :--- |
| coverage rows | the `families` list of `<gens>/varka/VarkaCoverageSuite.scala`; a column in its `columns` and in `fixtureView` of `<execs>/VarkaCoverageDifferentialSuite.scala` | `VARKA_COVERAGE_REGEN=true build/sbt 'catalyst/testOnly *VarkaCoverageSuite'` writes `sql/varka/coverage.json` and the table of `docs/sql-varka.md`. `VarkaCoverageHarnessSuite` then runs every row with no session, and `VarkaCoverageDifferentialSuite` the first row of each family through two sessions |
| the width audit and the emitted bytes | `sql/varka/width_audit.json`, `sql/varka/emitted_bytes.json` | `VARKA_AUDIT_REGEN=true ... *VarkaWidthAuditSuite`, `VARKA_BYTES_REGEN=true ... *VarkaEmittedBytesSuite`, then the coverage suite again |
| census cells | the curated file of the milestone (`sql/varka/plans/m8/census_curated.txt` on the base; row 654 adds milestone 9's) | `python3 dev/varka_census.py` rewrites the census, the cast table and the docs' support table; `--check` verifies |
| a cast pair | `casts_fused.tsv`, written by `VarkaCastMatrixSuite`; `casts_suites.tsv`, by Spark's three cast suites under Varka | `VARKA_CASTS_REGEN=true`, `VARKA_SUITES_REGEN=true`. The census fails on a fused pair that no coverage row casts |
| skip lines | `sql/varka/matrix/skips.tsv`: configuration, suite, test, `fails` or `declines`, reason | the option matrix; a stale line fails |
| benchmark sections | row 655's classes: a section says whether it fuses, and its queries suite checks that in every Build | the class's queries suite; the timed run is a dispatch |

`dev/varka_regen_generated.sh --all` runs every regeneration above in the order the files read
each other. It takes the heavy lock itself.

**The coverage column.** The coverage fixtures hold dates from 1969 to 9999. A zoned kernel in
its default form serves a batch that spans at most two changes, so over such a column every
batch would decline and the session differential would fail. By the table's own convention a
fixture's values lie inside every guard of the rows that read them. So the zoned rows read a
column of their own, `ltzy`, derived in `fixtureView`:

    timestamp_micros(1609459200000000 + pmod(i, 12) * 2629800000000 + time_to_micros(t)) AS ltzy

It is an instant of 2021: 1 January 2021 at 00:00 UTC, plus `pmod(i, 12)` twelfths of a year,
plus the time `t`. It is null where `i` or `t` is. It lies on both sides of both changes of
2021 in `America/Los_Angeles`. No value of `i` overflows it.

**The fuzzers.** The zoned rows stay out of the fuzzers' corpora until row 684 teaches them a
zone: `VarkaCoverageRows.readsZoned` and a filter that names `VARKA-684` as its owner.

**Where `sql/varka/ADDING_AN_EXPRESSION.md` is stale** (row 658 rewrites it; do not follow it
here): it says every node computes over int32 lanes, and the IR has six lanes; it names
`VarkaLoopEmitter.emitValue`, and the dispatch is in `VarkaVectorWalk` and the `Varka*Lowering`
classes; it names `m4/PLAN.md` as the task table; its check `dev/varka_emit.sh` has no
timestamp column and no zone; its step 7 says nothing of the session differential's first row,
of the width audit, the emitted bytes, the census, the cast matrix, the skip list or the
families; its benchmarks are `VarkaEmitterParityBenchmark` and `VarkaThroughputBenchmark`, and
today a feature has a baseline class with a fuse check; its gate is `dev/varka_gate.sh`, and
today the checks are those of 5.0.

### 4.9 Every file a new family touches

Checked on the commits that added the math family (`5c49b359f6f`) and the decimal cast family
(`56a61bf3242`):

| file | what |
| :--- | :--- |
| `<ir>/VarkaFamily.java` | the member: label, key, what it covers |
| `<gen>/VarkaNodeCompiler.java` | the link in `FAMILIES`, and the comment on the chain |
| `<gen>/VarkaZoneCompiler.java` | the family's compiler, new |
| `<gent>/VarkaFamilySwitchSuite.java` | an `UNBUILT` probe while no coverage row holds the family |
| `<gens>/VarkaFamilyChainSuite.scala` | a seam while no coverage row reaches the link |
| `<execs>/VarkaFamilySwitchQuerySuite.scala` | a case once an entry fuses |
| `<gens>/varka/VarkaCoverageSuite.scala` | the compiler's file in `admittedByCompiler`'s list |
| `docs/sql-varka.md` | the generated table of switches |
| not touched | `SQLConf.scala`, `VarkaSessionEmit.java`, `VarkaAdmission.java` and the binding-policy audit: each reads `VarkaFamily.values()` |

## 5. The parts

Five pull requests. The owner's sketch had four. Two code facts set the order: the census
refuses a fused cast pair that no coverage row casts, so the coverage column comes before the
cast; and a baseline's queries suite fails a section that fuses unlisted, so each part lists
the sections it fuses, and the timed run is a results pull request of its own.

| part | title | branch | counted lines, estimate |
| :--- | :--- | :--- | ---: |
| A | `[VARKA-657] Add the zone family, its switch and the fold of a fixed time zone` | `varka-657a` | 520 |
| B | `[VARKA-657] Fuse hour, minute and second of a TIMESTAMP in the session zone` | `varka-657b` | 680 |
| C | `[VARKA-657] Cover the zoned TIMESTAMP fields: coverage rows, census and docs` | `varka-657c` | 330 |
| D | `[VARKA-657] Fuse CAST(TIMESTAMP AS DATE) in the session time zone` | `varka-657d` | 190 |
| E | `[VARKA-657] Results: the first zoned kernels against the row path at two widths` | `varka-657e` | 40 |

### 5.0 What every sheet shares

- Work in the worktree and on the branch the lead names. Each part starts from master after
  the part before it has merged.
- Every shell that builds or tests starts with:

      export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
      export SPARK_LOCAL_IP=127.0.0.1
      export PATH=$HOME/.venv-ruff/bin:$PATH
      export VARKA_HEAVY_LOCK=<the lock file the lead names>

- Wrap every `build/sbt`, `dev/varka_emit.sh`, `dev/varka_matrix.sh`, `dev/lint-java`,
  `dev/lint-scala` and `dev/varka_errorprone.sh` call in `flock "$VARKA_HEAVY_LOCK"`. Call
  `dev/varka_regen_generated.sh` and `dev/varka_push_check.py` bare: they take the lock.
- Run B0 before any edit. A grep that does not print what the sheet says is a stop: report the
  command and its output. Rows 653, 646 and 654 may have moved a list to one file an entry or
  to milestone 9's folder; where B0 shows that, the content of a step is the same and its home
  is the one B0 prints.
- A test suite passes when sbt exits 0 and its summary reads `Failed 0, Errors 0`.
- Before the commit: `dev/varka_precommit.sh`; `dev/varka_errorprone.sh --master "$(git
  rev-parse origin/master)"` and `dev/lint-java` for Java; `dev/lint-scala` for Scala;
  `python3 dev/varka_plan_lint.py --check --offline`. After it: `python3
  dev/varka_push_check.py`, which prints the size and must exit 0.
- The commit's subject is the part's title. Its body ends with one attribution line,
  `Generated-by: <tool> (<model>)`, and no other trailer.
- Edit the row's line in milestone 9's task table: `**Planned**` becomes `**Started**` in part
  A and `**Done**` in part E. Add what was built to section 11 of this file.
- Stop at the local commit. No push, no pull request, no dispatch. Report the branch, the
  commit, the commands with their results and the size.
- Stop also on: a test that should pass and fails; a generated file that moves where the sheet
  says it does not; a size over the estimate by half or over 900; anything the sheet does not
  decide.

### 5.1 Part A: the family, the compiler class, the fold

**B0, the premises.**

| command | must print |
| :--- | :--- |
| `grep -n "record ToLocal" <ir>/VarkaVectorIR.java` | `record ToLocal(VarkaVectorIR instant, int zone, int hash)` |
| `grep -n "List<String> zones" <gen>/CompiledVarkaProjection.java` | the record's component |
| `grep -n "new CompiledVarkaProjection(" <gen>/VarkaExpressionCompiler.java` | one line, in `Tables.projection`; note what it passes for `zones` |
| `grep -n "interface Provider" -A3 <ir>/VarkaZoneData.java` | `VarkaZoneData of(String zoneId);` Note how a zone that is not served is told: a null, an `Optional`, or a method |
| `grep -rn "VarkaZoneData.Provider" sql/catalyst/src/main/java` | row 678's provider: a public constant or method. `PROVIDER` below stands for it |
| `grep -n -i "zone" <gen>/DeclineSink.java` | nothing |
| `grep -P "^TIMESTAMP\t" sql/varka/types.tsv` | a line with `ltz`, `on_lane` and `LONG` |
| `grep -n '"timestamp"' sql/varka/coverage.json` | the `TIMESTAMP` column of the coverage table; `ltz` below stands for its name |
| `grep -n "ZONE" <ir>/VarkaFamily.java` | nothing |

**The steps.**

1. `<ir>/VarkaFamily.java`: after `MATH`, add `ZONE("zone", "zone", ...)`, javadoc
   `{@code VarkaZoneCompiler}`. Its `covers`: "hour, minute and second of a TIMESTAMP and the
   cast of a TIMESTAMP to DATE, each read in the session time zone, under every zone; none
   fuses yet, and each declines naming row 657".
2. `<gen>/DeclineSink.java`: the zone table of 4.3. The field goes after `longLiterals`; the
   three methods after `truncateLong`; `zoneIds()` after `longLiteralValues()`; one paragraph
   in the class comment.
3. `<gen>/VarkaExpressionCompiler.java`: `Marks` gains `zones` (`of`, `restore`); `classify`
   calls `sink.truncateZones(marks.zones())` after `sink.truncateUsed(marks.used())`; `Tables`
   gains `List<String> zones`, passed by `projection` and set from `sink.zoneIds()` in
   `tables`.
4. `<gen>/VarkaZoneCompiler.java`, new, `final class`, package-private, with the license
   header. It holds `MAX_OFFSET_MICROS`; the reasons of 4.5; the provider field, initialised
   to `PROVIDER`, with `setProviderForTesting` (null restores `PROVIDER`); `served`; `toLocal`
   as in 4.2; and `arm`:

       static VarkaFamilyArm arm(
           Expression e, VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
         return switch (e) {
           case Hour h when isZoned(h.child()) -> () -> sink.decline(notYet("hour"), h);
           case Minute m when isZoned(m.child()) -> () -> sink.decline(notYet("minute"), m);
           case Second s when isZoned(s.child()) -> () -> sink.decline(notYet("second"), s);
           case Cast c when DataTypes.DateType.equals(c.dataType()) && isZoned(c.child()) ->
               () -> sink.decline(CAST_NOT_YET, c);
           default -> null;
         };
       }

   `isZoned(e)` is `DataTypes.TimestampType.equals(e.dataType())`. `served` returns the
   provider's data or null, by the form B0 printed: a null is returned as it is; an `Optional`
   by `orElse(null)`; a method `served()` by `data.served() ? data : null`.
5. `<gen>/VarkaNodeCompiler.java`: `new Family(VarkaFamily.ZONE, VarkaZoneCompiler::arm)` after
   the `TIME` link; one clause in the comment above `FAMILIES`.
6. `<gent>/varka/VarkaEmitDump.java`: column types `timestamp` (`DataTypes.TimestampType`) and
   `timestamp_ntz` (`DataTypes.TimestampNTZType`), unless row 656 added them; an argument
   `--zone <id>`, which sets `spark.sql.session.timeZone` on a clone of `SQLConf.get()` around
   the resolution and the compile (`SQLConf.withExistingConf`); a report line `zones: ` with
   the fused plan's `zones()` after the literals line; the usage text and the header of
   `dev/varka_emit.sh`.
7. `<gens>/varka/VarkaSqlResolve.scala`: `resolve` and `resolveAsWritten` run their analysis
   under `spark.sql.session.timeZone = America/Los_Angeles` when `SQLConf.get` does not
   contain that key (`SQLConf.contains`, `SQLConf.withExistingConf` over a clone). A comment
   says why (4.6).
   `<gent>/VarkaFamilySwitchSuite.java`: `UNBUILT = Map.of(VarkaFamily.ZONE, "hour(ltz)")`.
   `<gens>/VarkaFamilyChainSuite.scala`: add `"hour(ltz)"` to `seams`.
   `<gens>/varka/VarkaCoverageSuite.scala`: add `"VarkaZoneCompiler"` to `javaFiles`.
8. `<gent>/VarkaZoneCompilerSuite.java`, new, modelled on `VarkaMathCompilerSuite`.
9. Regenerate the docs: `VARKA_COVERAGE_REGEN=true build/sbt 'catalyst/testOnly
   *VarkaCoverageSuite'`.

**The tests.** `VarkaZoneCompilerSuite` builds `Hour(column, Option(zone))` by hand and calls
`toLocal` with a fresh `DeclineSink`, or compiles SQL resolved under a conf that sets the zone.

| test (`@DisplayName`) | what it holds | the failure only it catches |
| :--- | :--- | :--- |
| under UTC a zone conversion is its operand, in every spelling | `UTC`, `Z`, `Etc/UTC`, `GMT`, `+00:00`: the same object back, no literal, no zone | a fold that adds a zero, which is a second kernel class |
| a fixed offset is a sum with a literal under the 18-hour guard | `+01:00`, `-08:00`, `+05:45`, `+18:00`, `-18:00`: the tree of 4.2, the literal's value, the guard's two bounds; two offsets give equal trees | an offset's sign or unit; bounds that depend on the offset |
| a region whose rules are fixed folds as its offset does | `Etc/GMT+5`, `GMT+1` | "fixed" read from the id's spelling |
| a zone with changes is a node over the column, and its id enters the table once | `America/Los_Angeles`, `PST`, `Australia/Lord_Howe`, `Asia/Kathmandu`, `Pacific/Apia`, `Africa/Casablanca`; `PST` shares an index; a second zone is index 1 | an index that is always 0 |
| a computed operand declines in a zone with changes and folds in a fixed one | a `Greatest` of two columns | a node the runner cannot fill |
| a missing zone, an id that does not resolve and a zone not served each decline by name | `Mars/Olympus`; a planted provider | a throw out of the compile, which fails a query |
| the zone table rolls back to its mark | mark, two ids, truncate, the next id's index | a zone left by a declining entry |
| every claim declines naming row 657, and names the switch when the family is off | the four SQL forms over a `TIMESTAMP` column. Off, the three fields name the switch; the cast names it over `coalesce(ts, ts)`, since a cast of a bare column is asked its switch after its lowering | a claim the family forgot |
| the zone data calls a zone fixed exactly where `java.time` does | every id of `ZoneId.getAvailableZoneIds()` and of `ZoneId.SHORT_IDS`: `fixed()` equals `ZoneRules.isFixedOffset()`; the ids not served are listed | a fold that the data would not make |

**The commands.**

| command | must print |
| :--- | :--- |
| `build/sbt 'catalyst/testOnly *VarkaZoneCompilerSuite *VarkaFamilySwitchSuite *VarkaFamilyChainSuite *VarkaCoverageSuite'` | `Failed 0, Errors 0` |
| `git diff --stat -- docs/sql-varka.md sql/varka/coverage.json sql/varka/emitted_bytes.json` | one inserted line in `docs/sql-varka.md`, the switch's row; nothing else |
| `dev/varka_emit.sh "hour(ts)" --columns ts:timestamp --zone America/Los_Angeles` | `nothing fused`, then `entry  0` with `hour of a TIMESTAMP is row 657's: not fused yet` |
| `build/sbt 'hive/testOnly *SparkConfigBindingPolicySuite'` | `All tests passed.`: the new switch declares its binding policy |

**Planted faults** (commit first; plant one, rerun the first command, revert it):

| fault | the test that must fail |
| :--- | :--- |
| `toLocal` drops `.normalized()` | a region whose rules are fixed folds as its offset does |
| the guard's upper bound is `Long.MAX_VALUE` | a fixed offset is a sum with a literal under the 18-hour guard |
| `DeclineSink.zone` returns 0 for every id | a zone with changes is a node over the column |

**Done when** the four claims decline by name, the switch is a session setting, `toLocal`
answers every case of 4.2, and the three suites of the family pass. **Not here:** no arm that
builds a node; no coverage row; no edit of `SQLConf.scala`, of the runner or of the emitter.

### 5.2 Part B: `hour`, `minute` and `second`, with the suite

**B0, the premises.**

| command | must print |
| :--- | :--- |
| `grep -n "notYet(\"hour\")" <gen>/VarkaZoneCompiler.java` | one line (part A merged) |
| `grep -n "enum ZoneForm" -A2 <ir>/VarkaEmitOptions.java; grep -n "withZoneForm" <ir>/VarkaEmitOptions.java` | `ONE`, `STEPS_2`, `STEPS_8`; the with-method |
| `grep -n "static int count" <ir>/VarkaBatchArgs.java` | `count(VarkaVectorIR.Zoned node, ZoneForm form)` |
| `grep -n "STATUS_BATCH_ARGS" <exec>/varka/VarkaKernelRunner.java <exec>/varka/VarkaFallbackAccounting.java` | the constant, and its name among `STATUS_NAMES` |
| `grep -rn "VarkaZoneData" <exec>` | where the runner's programs get their zone data: the seam of step 4 |
| `grep -ln "hour(" sql/core/src/test/java/org/apache/spark/sql/execution/benchmark/Varka*BaselineBenchmark.java` | row 655's fields class over a `TIMESTAMP`, beside the `TIMESTAMP_NTZ` one |
| `dev/varka_emit.sh "hour(ts)" --columns ts:timestamp_ntz` | `entry  0` with `FusedOutput(0)`; keep the output |

**The steps.**

1. `<gen>/VarkaTimeCompiler.java`: the three wrappers of 4.4, after `secondOf`. The three
   `TIMESTAMP_NTZ` arms become `longOperand(h.child(), inputs, literals, sink).map(t ->
   hourOfLocal(t, sink))`, and alike.
2. `<gen>/VarkaZoneCompiler.java`: the three field arms build, and `notYet` goes. A private
   helper `local(whole, timeZoneId, operand, inputs, literals, sink)` compiles `operand` by
   `VarkaNodeCompiler.compileNode`, declines one off the long lane, and calls
   `toLocal(whole, timeZoneId, instant, sink)`:

       case Hour h when isZoned(h.child()) -> () ->
           local(h, h.timeZoneId(), h.child(), inputs, literals, sink)
               .map(t -> VarkaTimeCompiler.hourOfLocal(t, sink));

   The cast's arm still declines. `VarkaFamily.ZONE`'s `covers` now ends: "the cast declines
   naming row 657".
3. `<gent>/varka/VarkaProofFiles.java`: `zoneFold()`, registered in `render()` as
   `zone_fold.smt2`, modelled on `timeBounds()`. With `M` from `ZoneOffset.MAX`: for `x` in
   `[Long.MIN_VALUE + M, Long.MAX_VALUE - M]` and `o` in `[-M, M]`, `(jlong.add x o)` equals
   `(+ x o)`: expect unsat of its negation; at `x = Long.MAX_VALUE - M + 1`, `o = M` it does
   not: expect sat; and the same at the lower end. One row in `sql/varka/proofs/README.md`.
4. The runner's provider: at the seam B0 printed in `<exec>`, the provider is `PROVIDER`, the
   one `VarkaZoneCompiler` holds. If the line already names it, skip the step.
5. `<gent>/VarkaFamilySwitchSuite.java`: the probe becomes
   `"CAST(coalesce(ltz, ltz) AS DATE)"`. A cast of a bare column would not do: its switch is
   asked after its lowering.
6. Row 655's fields class: each section of `hour`, `minute` or `second` over the `TIMESTAMP`
   column is listed as fused, the way the class's `Section` says it (`Fusion.WHOLE` and a
   `BatchCheck` of `VarkaSqlBenchmarkBase`), by this table:

   | zone of the section | data | batch check |
   | :--- | :--- | :--- |
   | UTC, a fixed offset | any | `KERNEL` |
   | `America/Los_Angeles` | a week; a year in time order; a year in no order | `KERNEL` |
   | `America/Los_Angeles` | ten years in no order | `ALL_FALL_BACK` |

   If the class cannot say `ALL_FALL_BACK` for one section, stop.
7. The suites of the next paragraph. Regenerate the docs and the proof file.

**The tests.** `<exect>/VarkaTimestampZoneSuite.java`, new, `@VarkaSuite`, modelled on
`VarkaTimeHarnessSuite`. It calls `VarkaExpressionHarness.projection` and `filter` with a conf
of its own: `VarkaCoverageHarnessSuite.conf(ansi)`, then `spark.sql.session.timeZone` and
`spark.sql.execution.arrow.maxRecordsPerBatch` set; the plan comes from
`VarkaHarnessPlans.projection` inside `VarkaCoverageHarnessSuite.withConf`. Each check sets
the form with `VarkaMatrixRuns.BASE.withZoneForm(form)` through
`VarkaColumnarToRowExec.setEmitOptionsForTesting` and restores `VarkaMatrixRuns.BASE`.

- **The fixture** of a zone and a window: a table `(rid INT, ts TIMESTAMP)` built by
  `VarkaHarnessCases.source` from longs. Its changes are read from `ZoneRules.nextTransition`,
  never typed. Rows: for each change `T` in the window, `T` plus -2 h, -1 h, -30 min, -1 s,
  -1 us, 0, +1 us, +1 s, +30 min, +1 h, +2 h; and one row every six hours across the window.
  Every seventh row is null. Two orders: time order, and the permutation `(i * 7919) % n`.
- **The zones and their windows**: `UTC`, `+01:00` and `Etc/GMT+5` over 2021;
  `America/Los_Angeles` over 2021 (the suite asserts exactly two changes) and over January
  2021 to June 2022 (three); `Australia/Lord_Howe` over 2021; `Asia/Kathmandu` from July 1985
  for a year and over 2021; `Pacific/Apia` over 2011; `Africa/Casablanca` over 2017.
- **The expectation** of a batch is computed by the suite, not typed: with `c` the changes
  `T` with `least < T <= greatest` over the batch's live rows, by `ZoneRules`, and `K = (count
  - 1) / 2` from `VarkaBatchArgs.count`, the batch is answered when the zone is fixed or `c <=
  K`, and declined with `STATUS_BATCH_ARGS` otherwise. Every row of an answered batch equals
  Spark's interpreted and generated projections: the harness compares them.

| test | what it holds | the failure only it catches |
| :--- | :--- | :--- |
| `<zone>`: the fields equal the row engine across every change of a year, in time order and in no order, in each form (one dynamic test a zone) | `hour`, `minute`, `second`; both consumers; the three forms; the whole fixture as one batch and as batches of 64 rows | a kernel that agrees with Spark only on rows that cross no change |
| a batch is served exactly when it spans no more changes than its form has steps | per zone with changes and per form: windows of `K` and of `K + 1` changes, found from `nextTransition` | a form that serves a batch it does not cover, which is a wrong answer |
| a range that ends on a change counts it, and one that starts on it does not | a batch whose greatest instant is `T`; one whose least is `T` | a count that is off by one at a change |
| one row, all rows equal and a batch with no live row are served | three small batches | a fill that declines a batch it need not |
| the ends of a long decline and the row engine answers them | batches of two values near one end, so that none spans a change. Under `+01:00`: the guard's bound and its neighbour inside are answered; one microsecond outside declines with `STATUS_VALUE_RANGE`. Under `America/Los_Angeles`: a batch with `Long.MIN_VALUE` or `Long.MAX_VALUE` declines with `STATUS_BATCH_ARGS`; one and two microseconds inside the fold's bounds are answered | a sum that wraps |
| both ends of Spark's range are answered | 0001-01-01 and 9999-12-31, each alone in a batch, under `America/Los_Angeles`: an offset that is no whole minute | a kernel that takes `second` from the instant |
| three fields of one timestamp are one kernel | `hour(ts)`, `minute(ts)`, `second(ts)` in one projection | a conversion computed once and read wrongly twice |
| a filter on a field is served by the filter evaluator | `hour(ts) = 2` under UTC, `+01:00`, `America/Los_Angeles` | batch arguments that only the projection's runner fills |

`VarkaZoneCompilerSuite` gains: "hour, minute and second of a TIMESTAMP are the
TIMESTAMP_NTZ trees over the local value" (the tree of `hour(ntz)` with its column replaced by
`ToLocal(column, 0)`, by `VarkaVectorIR.childrenOf` and `withChildren`); "under UTC the three
compile to the TIMESTAMP_NTZ trees"; "an entry forced residual leaves no zone behind" (two
hand-built entries in two zones under `forceResidualAt=1`: the plan's `zones()` holds the
second zone alone, at index 0).

**The commands.**

| command | must print |
| :--- | :--- |
| `dev/varka_emit.sh "hour(ts)" --columns ts:timestamp --zone America/Los_Angeles` | `FusedOutput(0)`; `zones: [America/Los_Angeles]`; the IR line holds a `ToLocal` |
| `dev/varka_emit.sh "hour(ts)" --columns ts:timestamp --zone UTC` | the `output 0 IR` and `shape hash` lines of B0's kept output, letter for letter |
| `dev/varka_emit.sh "hour(ts)" --columns ts:timestamp --zone +01:00` and again with `--zone -08:00` | one `shape hash` for both; a `guardedRange` in the IR; `zones: []` |
| `build/sbt 'catalyst/testOnly *VarkaZoneCompilerSuite *VarkaFamilySwitchSuite *VarkaFamilyChainSuite *VarkaEmittedBytesSuite *VarkaProofFilesSuite'` after `VARKA_PROOFS_REGEN=true` once | `Failed 0, Errors 0` |
| `git diff --exit-code -- sql/varka/emitted_bytes.json` | exits 0: the refactoring moved no byte |
| `dev/varka_prove.sh --lint` | every check of `zone_fold.smt2` gives its expected verdict |
| `build/sbt 'sql/testOnly *VarkaTimestampZoneSuite *VarkaTimestampNtzSuite *VarkaTimeHarnessSuite'` and row 655's fields queries suite | `Failed 0, Errors 0` |

**Planted faults:**

| fault | the test that must fail |
| :--- | :--- |
| the fold subtracts the offset | `+01:00`: the fields equal the row engine |
| the `Hour` arm calls `minuteOfLocal` | `America/Los_Angeles`: the fields equal the row engine |
| `Marks.restore` skips `truncateZones` | an entry forced residual leaves no zone behind |
| the runner's seam passes the id `UTC` to the provider whatever the node's zone | `America/Los_Angeles`: the fields equal the row engine |

**Done when** the three entries fuse under every zone of the suite and equal Spark across each
zone's changes; a batch of `K + 1` changes declines with `STATUS_BATCH_ARGS`; the emitted bytes
did not move. **Not here:** the cast; coverage rows; the emitter, the lowering, `VarkaBatchArgs`.

### 5.3 to 5.5 Parts C, D and E

The sheets of part C (the coverage rows, the census cells, the documentation), part D
(`CAST(ts AS DATE)`) and part E (the measurement) come in the pull request after this one.

## 6. Tests, proofs, fuzzing and coverage

Every suite runs at both vector widths through the gate, under each option configuration
through the matrix, and with the memory sanitizer on.

| suite | what it holds | the failure only it catches |
| :--- | :--- | :--- |
| `VarkaZoneCompilerSuite` (catalyst, Java, JUnit 5) | the fold, the index, the declines by name, the claims, the trees against the `TIMESTAMP_NTZ` twins | a compile that builds the wrong tree for a zone class, which every answer test of one zone would miss |
| `VarkaTimestampZoneSuite` (sql/core, Java, JUnit 5, no session) | each kernel against Spark's two projections under UTC, a fixed offset, a region with fixed rules and five zones with changes; rows across every change of a year, in time order and in no order, with nulls; batches of 0, 1, `K` and `K + 1` changes; the edges of 5.2 | a kernel that agrees with Spark on rows that cross no change and on no others |
| `VarkaTimestampZoneQuerySuite` (sql/core, ScalaTest) | the plan, the cache, the metrics of a declined batch, `EXPLAIN`, two sessions | a path that only a session has |
| `VarkaFamilySwitchSuite`, `VarkaFamilyChainSuite`, `VarkaFamilySwitchQuerySuite` (existing) | one switch a node; the switch declines exactly the family's rows | a node under two switches or under none |
| the coverage suites, the cast matrix, the conformance suite, Spark's cast suites (existing) | the registers agree with the compiler | a register that drifts |
| `VarkaEmittedBytesSuite` (existing) | no byte of an existing shape moves; the new rows' bytes are pinned in each form | a refactoring that changed a `TIMESTAMP_NTZ` kernel |

**Proofs.** `zone_fold.smt2`, new in part B, rendered by `VarkaProofFiles` from
`ZoneOffset.MAX`: the fixed offset's sum under its guard. `long_divide.smt2` holds the day's
floor division and its remainder for every long, so it holds them for a local value. The
step's proof, `zone_step.smt2`, is row 684's. That a kernel equals `java.time` in every zone
is no SMT claim: row 684's sweep holds it.

**Fuzzing.** None here. The zoned coverage rows are left out of the composition fuzzer and of
the Spark fuzzer by a filter that names `VARKA-684`, which draws the zone.

**Coverage and mutation.** The lead runs `dev/varka_gen_coverage.sh` after parts A, B and D
and reports the branches of `VarkaZoneCompiler` from `sql/varka/coverage/emitter.md` in each
pull request: every branch reached, or the test that reaches it added. The mutation run
(`dev/varka_pit.sh`) does not reach this row: it mutates classes of `codegen/varka` against
ScalaTest suites, and the row's class is in `codegen` with JUnit suites. The planted faults of
each sheet stand in for it.

## 7. Measurement

**The sections** are row 655's: `hour(ts)`, `minute(ts)`, `second(ts)` and `CAST(ts AS DATE)`;
under UTC, `+01:00` and `America/Los_Angeles`; over a week, a year in time order, a year in no
order and ten years in no order; with no nulls and with one row in ten null. The control of
each is its `TIMESTAMP_NTZ` twin. The numbers are the AMD EPYC 7763's at 256 and at 128 bits;
the laptop adds 512 where the owner asks. The A/B arms of the forms are row 691's.

**Predictions, registered on 26 November 2026, before any run.** Each is scored by part E. A
ratio under 1.3 is run again and read by minimums.

1. `hour(ts)` under `America/Los_Angeles`, a year in no order, at 256 bits on the EPYC 7763:
   at least eight times the row path's rate. Goal G7 asks five.
2. The zone's price over the `TIMESTAMP_NTZ` twin, the same section's time less its twin's, at
   256 bits: under 0.5 ns a row, with the pass for the range.
3. Under UTC `hour(ts)` costs what `hour(ntz)` costs: the two `varka on` times are within 1.1
   of each other. It is one kernel class.
4. Under `+01:00` `hour(ts)` costs under 0.3 ns a row more than under UTC: two compares and a
   sum.
5. `CAST(ts AS DATE)` under `America/Los_Angeles`, a year in no order, at 256 bits: at least
   eight times the row path.
6. Ten years in no order under `America/Los_Angeles`: every batch declines, and the `varka on`
   time is within 1.05 of the row path's. A declined batch costs the row path and the pass.
7. At 128 bits no section of the four is slower than the row path: yes. The masked steps cost
   15.679 ns a row there in the probe (`sql/varka/spikes/zone-offset/results/kernels.txt`),
   and the row path of the twin costs 64.1 on the runner
   (`VarkaTimestampNtzFieldsBaselineBenchmark-jdk25-runner-epyc7763-results.txt`).
8. Test time, read against `python3 dev/varka_calibration.py`. `VarkaTimestampZoneSuite` adds
   under 20 s to the sql/core Varka tests: about 350 harness checks, estimated at 8 s and
   doubled at VARKA-243's ratio for a new sql/core suite; VARKA-333's rows ran at 0.14 of
   such an estimate. `VarkaZoneCompilerSuite` adds under 5 s: VARKA-318's compile-only suite
   took about 2 s. `VarkaTimestampZoneQuerySuite` adds under 15 s: VARKA-365's nine tests
   over one-row cached views took 5 s.
9. Five pull requests, none over 1000 counted lines, within half of the estimates of
   section 5.

## 8. Who builds it

`m9/PLAN.md` 1.4 has rows 656, 657 and 682 to 692 built at full strength. Within that:

| part | builder | why |
| :--- | :--- | :--- |
| A | full strength | it sets the family's claim, the fold and the sink's table; a wrong fold is a wrong answer under every fixed zone |
| B | full strength | the suite's fixtures and its expectation are the row's proof; the runner's seam depends on what row 682 built |
| C | full strength, or a cheaper model after the pilot (row 659) with a lead at hand | the steps are mechanical, but the readers of a coverage row are many and a stop is likely |
| D | a cheaper model, from this sheet | it is the shape of row 668's tasks: one arm over `toLocal`, its rows, its tests, its sections. What it teaches goes into row 658's template |
| E | the lead | dispatches and results |

## 9. Risks

1. **A premise on rows 682, 656, 678, 655 or 654 fails.** The contract does not name the
   production provider, the runner's seam, or how a zone that is not served is told. B0 of
   each part greps what it needs and stops.
2. **A wrong fold is a wrong answer that no fallback catches.** `VarkaZoneCompilerSuite` pins
   the tree of each zone class, `VarkaTimestampZoneSuite` runs three fixed zones, and the
   planted faults flip the sign and drop the normalisation.
3. **A zone index that points at another zone.** The rollback test, the two-zone test and the
   two-session test hold it.
4. **The driver and an executor read different zone rules.** Each compiles for itself, and a
   task follows its own compile (section 3). Only `EXPLAIN` and speed move.
5. **A batch with no live row.** If row 682's runner declines it, the coverage rows lose their
   last answered batch under `zoneForm=ONE`. The suite of part B asserts that it is answered.
6. **Entries fuse by composition over the cast** before row 668 claims them (4.7). Part D
   holds their answers and lists their benchmark sections; row 668 owes their coverage rows.
7. **The coverage rows meet many readers.** The width audit's probe and the conformance
   harness run a kernel outside the runner; the fuzzers draw rows. Part C sizes the probe's
   table and filters the fuzzers; part D resolves the conformance casts under UTC. A reader
   this plan missed fails a suite of part C's commands, and that is a stop.
8. **128 bits.** The fold's guard and the node's steps are masks. Prediction 7 says they still
   beat the row path; row 689 owns the bar.
9. **A JUnit JVM's zone** is the machine's (4.6). Part A pins catalyst's resolver, and no new
   sql/core test reads the default.
10. **The plan ages.** Rows 653, 646 and 654 move shared files before this row starts. B0
    prints each list's home.
11. **Part B's size** is the largest, at 680. Over 900 it stops; the split is then the field
    arms with the compile tests and the proof, and the harness suite as its own pull request
    on top, merged together.

## 10. The house rules that bind this row

From `sql/varka/AGENTS.md`, the rules a builder of this row is most likely to break:

- "**The Varka code carries no `TODO` or `FIXME` comments, and should not start.**" Part A's
  arms decline by name; they carry no marker.
- "**A comment describes the code as it is.**" No "used to", "the old" or "since VARKA-657".
- "A new catalyst suite whose helpers are Java is a JUnit 5 class under `@VarkaSuite`", each
  `@Test` with its sentence as a `@DisplayName`. The sql/core suite with no session is one too;
  the session suite stays ScalaTest.
- "A test starts from `VarkaMatrix.base`, not `VarkaEmitOptions.DEFAULTS`".
- "**A new bounded lowering comes with its proof file**": `zone_fold.smt2` in part B.
- "The ghost fallback is a correctness contract ... Anything that can return a *wrong* answer
  rather than fail ... gets its own differential coverage."
- "**A pull request stays under 1000 changed lines** of hand-written files."
- "**Review your own diff before opening the pull request**" with `/code-review high`.
- "No allocation, streams, lambdas or boxing on a path that runs per batch or per row". The
  runner's seam of part B resolves a zone once a runner, never a batch.
- "**Never kill by name pattern**", "**Never `git stash`**", "**Write only inside your own
  worktree and scratch area.**"

Do not: edit a generated file by hand; add an IR node, an emit option or a default; touch the
emitter, the lowering or `VarkaBatchArgs`; edit `SQLConf.scala`; change a value of an existing
fixture; add a coverage row for an entry this row does not name; print the environment; open
`/home/max/proj/runtime-master`.

## 11. Outcome

*To be written when the row closes.*
