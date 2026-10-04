# VARKA-23: Java-first migration, and the task-18 debt

Milestone 3's last task, from its section 3 row: migrate the Varka Scala
components that nothing forces to stay Scala - `VarkaShapeCache` first, as the
natural vehicle for the debt register's emit-options rework
(`m3/PLAN.md` section 10) - then an assessment of
`VarkaExpressionCompiler`, and a recorded boundary naming what stays Scala and
why. The gate is behaviour-preserving: every Varka suite green at both vector
widths with committed numbers unchanged, scalastyle and `dev/lint-java` clean.

This file is written before the work rather than during it, because the
exploration behind it changed the task's shape twice and those findings are
worth recording whether or not the implementation follows immediately. The
outcome section is added when the work lands.

## 1. The boundary, measured rather than assumed

The Java-first rule (`sql/varka/AGENTS.md`) names three surfaces that force
Scala: `SparkPlan` subclasses, the Catalyst rule and expression matching, and
ScalaTest suites. Measuring the tree against that rule gives a smaller
migration than the charter's wording suggests, and the honest number is worth
stating first: **474 Scala lines in two files.**

A second fact sets the standard the migration has to hold. Every existing Java
file in `.../codegen/varka/` is pure JDK - not one imports a Spark Scala class,
and `VarkaLoopEmitter`'s only Spark import is its own sibling IR file. That is
what lets the Java side be read and tested without Spark, and the migration
should keep the line rather than blur it.

| File | Lines | Verdict |
|---|---|---|
| `VarkaGeneratedClassLoader.scala` | 70 | **Migrate, first.** Imports only `ConcurrentHashMap`, extends `ClassLoader`, no Scala surface. A Java twin already exists - `sql/varka/engine/.../execution/VarkaClassLoader.java` - and this file's own doc calls itself a deliberate duplicate whose changes "belong in the other". Porting turns a behavioural duplicate into a literal one. |
| `VarkaShapeCache.scala` | 404 | **Migrate the core, keep a thin Scala facade** (section 3). Its Spark dependencies are configuration and logging, not logic. |
| `VarkaExpressionCompiler.scala` | 660 | **Stays Scala.** 35 `case` arms pattern-matching Catalyst case classes across 12 `match` blocks, 95 uses of `Seq`/`Option`/`mutable`, 10 `private[sql]` declarations. In Java every arm becomes an `instanceof` cascade over Scala case classes with `CollectionConverters` at each boundary. The charter asks for an assessment here, not a migration; this is it. (An earlier revision also cited the 10 `private[sql]` declarations; that argument is withdrawn - see the visibility note below - and the 35 arms carry the verdict on their own.) |
| `VarkaFusionReport.scala` | 103 | **Assessed and declined** - the one genuine judgement call. Plain string rendering, so it looks migratable, but every signature takes `Seq[NamedExpression]` / `Seq[Attribute]` / `Expression` and it pattern-matches `VarkaOutputSpec`. A veneer over forced-Scala types; Java buys nothing and adds a conversion layer. |
| `VarkaKernelEvaluator.scala` | 1062 | **Stays Scala**, and the file already argues why at `:42-49`: the task-21 review recorded a deliberate decision that `VarkaExecMetrics` stay a case class rather than a Java record, because every construction site is forced-Scala code leaning on named arguments and defaults over seven same-typed fields, where a record's positional constructor is a silent-swap hazard. That precedent is cited, not re-derived. |
| `VarkaColumnarRule`, `VarkaProjectExec`, `VarkaColumnarToRowExec`, `VarkaFilterExec` | 1118 | **Stay Scala.** `ColumnarRule` and `SparkPlan` subclasses - the rule's named exceptions. |
| Every `*Suite.scala` | 5619 | **Stays Scala.** ScalaTest. |

**A note on visibility, which this record originally got wrong.** Java cannot
express `private[sql]`, so every migrated type - `VarkaGeneratedClassLoader`,
`VarkaShapeKey`, `VarkaShapeEntry`, `VarkaShapeLookup`, `VarkaShapeCacheImpl`,
and `VarkaEmitOptions` - is a plain public class. Three things make
that acceptable, and none of them is "it does not matter":

* Nothing changes binary-compatibility-wise. `private[sql]` is a
  scalac-enforced restriction that compiles to `public` in bytecode, so these
  classes were already public to anything reading the jar.
* MiMa cannot object. `catalyst` is in `SparkBuild.mimaProjects`, but the
  baseline artifact is `spark-catalyst_2.13` 4.0.0, which contains no Varka
  class at all - MiMa reports removals and incompatible changes against a
  baseline, never additions.
* What is genuinely lost is a compile-time fence, and these are the files that
  can afford to lose it: leaf infrastructure whose every caller is Varka's own
  code or Varka's own suites.

The reason `VarkaExpressionCompiler` stays Scala is therefore the 35 `case`
arms over Catalyst case classes, not its `private[sql]` declarations. The same
visibility argument would have to disqualify the shape cache, which has seven
of them, so it cannot be doing the work the table first gave it.

## 2. Decisions, and who made them

* **The shape cache splits rather than translates** - a pure-JDK Java core plus
  a thin Scala facade - decided by the project owner over the two
  alternatives put to him (one all-Java file calling Scala objects through
  `SQLConf$.MODULE$`, or dropping the migration and landing only the debt
  rework). The deciding evidence was that the file's Spark dependencies are
  all configuration and logging: `SparkEnv`, `Logging`, `SQLConf`,
  `StaticSQLConf`, `NonFateSharingCache`, `SparkStringUtils`. A facade absorbs
  every one of them, and it makes the "deterministic executor-side sizing"
  debt disappear by construction rather than by patch.
* **`VarkaGeneratedClassLoader` is in scope**, though the charter row does not
  name it - the owner's call, on the argument that it is the cleanest instance
  of the rule in the tree and that its Java twin already exists.
* **The register's `renderLineMap` item is swept too, with a new shallow
  rendering** rather than the substitution the register implies - the owner's
  call once exploration showed the obvious fix is wrong (section 4).

## 3. Design: the shape cache

**Java core** (pure JDK plus Guava, in the package's house style - records,
sealed interfaces where they fit, exhaustive switches, explanatory javadoc):
`VarkaShapeKey`, `VarkaLoaderShapeKey`, `VarkaShapeEntry`, `VarkaShapeLookup`,
the cache implementation with both Guava caches, the removal listener that
releases evicted loaders, the `LongAdder` counters, `shapeHash` /
`classNameFor` / `sourceFileFor`, and the execution side table. Logging through
`SparkLoggerFactory` - checkstyle bans `org.slf4j` directly, and this also
avoids the Scala `Logging` trait.

**Scala facade**: capacity resolution, and the `Seq`/`List` conversions that
keep every call site outside this file unchanged.

Two findings make the split cheap. `NonFateSharingCache` is usable from Java as
`new NonFateSharingCache<>(guavaCache)` - `private[spark]` is public in
bytecode and the primary constructor is public - so the loading discipline
survives untouched and the four-overload companion `apply` is bypassed. And
every callback in the file is already a Java functional interface
(`RemovalListener`, two `Callable`s), so no Scala-function to Java-function
adaptation is needed anywhere.

### The three non-mechanical points

Everything else is direct substitution. These are not:

1. **`VarkaShapeKey.outputs: Seq[VarkaVectorIR]` becomes
   `List<VarkaVectorIR>`.** The largest ripple, and it touches the correctness
   contract, since key equality is what makes a wrong hit a wrong answer. Use
   `List.copyOf` in a compact constructor so no caller can mutate a live key.
   Two construction sites gain `new`, records having no `apply`:
   `VarkaKernelEvaluator.scala:212` and the suite's `keyOf` helper at
   `VarkaShapeCacheSuite.scala:58-59`.
2. **`throw e.getCause` in the Guava unwrap** (`:220-226`) meets Java's checked
   exception rules. It must stay a rethrow, never a wrap: the file's own doc
   (`:145-148`) records that the cause has to reach the evaluator's
   `isCatchable` test as itself, which is what keeps a fatal error or an
   interrupt from being counted as a kernel failure.
3. **`var emitted` captured by the loading `Callable`** (`:212-219`) needs
   effectively-final capture.

`executionsFor` returns `Seq[String]` today and three Scala test sites use it
as a Scala collection, so the Java core returns `List<String>` and the facade
converts. Scala's auto-application rule for Java nullary methods means
`entry.className`, `entry.sourceFile`, `lookup.hit` and `hitCount` keep
compiling unchanged as long as the component names hold.

### The debt items the migration carries

* **Deterministic sizing.** Confirmed at `:350`: the lazy singleton runs
  `configuredMaxEntries()` once, on whichever thread first touches any entry
  point, so whether `SQLConf.get` returns a task's `ReadOnlySQLConf` or the
  driver fallback depends on who won the race - two identically configured
  executors can size differently. The split fixes it structurally: capacity
  becomes a constructor argument the facade resolves at one defined point. The
  boundary to document is sharper than the register states: a builder-set
  static reaches an executor only when the builder also created the
  `SparkContext`; on a session attached to an existing context,
  `SQLConf.mergeNonStaticSQLConfigs` drops static keys, so the value is
  silently lost on the driver and never propagates at all.
* **`recordExecution` onto `asMap().compute`** (`:302-329`): the `while` loop
  with its `getIfPresent` / `putIfAbsent` reinstate dance collapses into one
  atomic remapping. This is a pair of edits, not one - the per-set
  `synchronized` at `:311` also goes, since `compute` holds the bin lock, so
  `executionsFor` (`:249-250`), which relies on that same monitor today, must
  take its defensive copy inside a `computeIfPresent`.
* **The kernel constructor** is resolved reflectively per call in `newKernel()`
  (`:90`), once per task at `VarkaKernelEvaluator.scala:543`. Resolve once in
  `emit`, store it in the entry.
* **`className` and `sourceFile`** are pure functions of `shapeHash`
  (`:373-377`), so they become derived accessors and stop being stored. A
  record with a `byte[] classBytes` component gets identity equality for that
  component - harmless, since entries are only ever compared with `eq`/`ne`,
  but it deserves a comment, because a record looks like it has value
  equality.

## 4. Design: emit options

### What exists today

Five `private static volatile` hook fields in `VarkaLoopEmitter.java` -
`misdescribeAddForTesting` (196), `disableCseForTesting` (203),
`divFloorModForTesting` (210), `digitSumFloorModForTesting` (218),
`groupBudgetForTesting` (227) - each with a package-private setter (240-263)
that bumps an `AtomicLong testHookGeneration` (declared at 237); two
package-private queries, `currentTestHookGeneration` (266) and
`anyTestHookSet` (279); a public
re-export shim `VarkaEmitterTestSupport` in the catalyst test jar so `sql/core`
suites can reach the setters; and a reflection-based completeness test at
`VarkaShapeCacheSuite:314`.

The cache guards it with three reads: a gate that throws for every lookup while
any hook is set (`VarkaShapeCache.scala:204`), a generation snapshot before the
emit walk (`:275`), and a re-check after it (`:282`).

**Three races live in that protocol - the register names two.**

1. A hook set between the gate (`:204`) and the snapshot (`:275`) - an
   unbounded window, since the caller may block on another task's in-flight
   load of the same key - is already set when the snapshot is taken, so the
   re-check passes and the poisoned bytes are cached under the plain key.
2. The gate is JVM-wide: while any suite holds a hook, every unrelated
   concurrent query throws instead of simply emitting uncached.
   `VarkaShapeCacheSuite:216-220` asserts exactly that.
3. Every write bumps the counter, resets included, so one suite *clearing* its
   hook spuriously fails another thread's in-flight emit. This one is not in
   the register; the exploration for this task found it.

### The replacement

A `VarkaEmitOptions` record (Java, pure JDK, in the emitter's package) passed
explicitly into `VarkaLoopEmitter.emit` and folded into the cache key:

* `int groupBudget` - the one genuine tuning knob, default `GROUP_BUDGET = 16`.
* `boolean cse` - default true.
* `FloorMod7 floorMod7` - an enum `MAGIC | DIV | DIGIT_SUM`, replacing two
  independent booleans that can both be set today, an illegal state the record
  removes by construction.
* `boolean misdescribeAdd` - the one pure fault injector.

Every hook field, every setter, the generation counter, both queries, the gate,
the snapshot, the re-check, the four re-export methods and the reflection
enforcement test then delete. None of the three races can be expressed: options
travel as a value on the call, so there is nothing to write concurrently and
nothing to snapshot.

Two design points that come from the code rather than the idea:

* **The side table must not collide.** `executions` is keyed on `shapeHash`
  alone (`:190-194`) while the map is keyed on the full key, so options joining
  the key but not the hash would merge two variants' execution identities.
  Options reach `shapeHash` too.
* **Default options must hash to what they hash today.** The committed hashes
  (`586434f9b9739c40`, `612c94d132690dc2`) name the emitted classes, which are
  baked into the bytes and quoted in telemetry. So `shapeHash` renders the
  options only when they differ from the defaults: production hashes, class
  names and telemetry stay identical bit for bit, and only the variants a suite
  asks for get their own identity. That is what keeps "behaviour-preserving"
  literally true rather than approximately.

Two consequences to handle rather than discover. `fitsBudgets` (`:641-654`),
the compiler's eligibility mirror, does not consult `groupBudgetForTesting`
today, so a budget override already changes emission without changing
eligibility; once the budget is a record field, `fitsBudgets` takes the record
and the asymmetry closes. And the `emit` overload pair (`:421-424`, `:441-443`)
with nullable `sourceFile` and `planFragment` collapses into one entry point;
both strings stay outside the key, each already being a function of the hash.

### The line map

The register's last item is not in the shape cache at all: `renderLineMap`
lives in `VarkaLoopEmitter.java:1458-1472`, already Java. It renders IR nodes
through `Record.toString`, whose format no JDK promises - and so does the line
above it, `"outputs=" + outputs` at `:469`.

`canonical` is not a drop-in replacement: it recurses, so each line would carry
a whole subtree instead of one node, lengthening every entry and changing the
line map's decoding contract. The fix is a shallow rendering,
`canonicalShallow(node)`, added beside `canonical` in `VarkaVectorIR.java` - an
exhaustive switch over the same sealed hierarchy, rendering node kind plus
scalar fields with children referenced by index - pinned by its own committed
value the way `canonical` is. Both emitter call sites move onto it.

What that changes: the emitted `VarkaDebugInfo` content, so those bytes differ.
What it does not change: the shape hash, which is computed from `canonical`
over the IR - which is why the two committed hashes stay a valid oracle for
everything else. The task-16 debug-surface tests need checking for anything
that pins the old rendering.

### The one open question

Two `sql/core` suites use a hook as a fault injector and depend on the JVM-wide
gate throwing to manufacture an emission failure: `VarkaProjectExecSuite:401`
and `VarkaFilterExecSuite:183`, both calling
`VarkaEmitterTestSupport.setDisableCse(true)` and relying on the refusal, as
their own comments say. Once options ride the key that emission succeeds and
both tests lose their injection. Settle the replacement before deleting the
gate: inject at the evaluator seam, where those suites already build plans
through test doubles, rather than adding an internal config entry that only
tests would read. It is the only part of the rework that is not mechanical.

## 5. Documentation sweeps

### 5.1 The four-lane record: split it, do not delete it

`m3/PLAN.md:224-226` records four-lane coverage as "local only, via
`-XX:MaxVectorSize=16`", with a real aarch64 runner "dependent on runner
availability". The runner now exists - `build_and_test.yml:1088`, a matrix over
`ubuntu-latest` and `ubuntu-24.04-arm` - and ran green on PR #51. But the
record should not simply be struck, because the job runs `./build/mvn -f
sql/varka/engine/pom.xml install`: **the engine module alone**, five JUnit
classes, each twice (host width, then `-XX:MaxVectorSize=16` with
`-Dvarka.expected.int.lanes=4`). The catalyst and sql/core jobs that run
`VarkaLoopEmitterSuite`, `VarkaShapeCacheSuite` and `VarkaDifferentialSuite`
are `runs-on: ubuntu-latest`, x86_64 only, and build the engine with
`-Dmaven.test.skip=true`.

So the accurate statement is that the **hand-written kernels are covered on
real ARM hardware, and the emitted loops are not** - they still rely on
capped-width simulation on x86. Three sites say otherwise today:

* `m3/PLAN.md:224-226` - wrong for the kernels, right for the emitter.
* `ISSUES.md:600-605` - the "Suggested order" item 9 still lists the aarch64
  runner as outstanding, contradicting items 1 and 3 of its own list and the
  finding's own `Status: FIXED`.
* `ISSUES.md:83-85` - finding 1's caveat, "no `IntVector` path has actually
  executed at 4 lanes", is now false for the engine kernels.

One more found in passing: `sql/varka/engine/pom.xml:159-160` still asserts
"Every CI runner is x86_64 with 8 or 16 int lanes", now false for the job it
configures.

### 5.2 The debt register

`AGENTS.md` requires a swept entry be rewritten in the past tense with what the
sweep found, not deleted. The bullet spans two files and six items; this task
sweeps all six, and the rewrite should say what the sweep found that the
register did not know: the third race, the sharper builder-set-static
boundary, and that the line-map item needed a new rendering rather than a
substitution.

## 6. Verification

* `build/sbt catalyst/Test/compile sql/Test/compile`, then
  `catalyst/testOnly *Varka*` at the preferred width **and** under
  `-XX:MaxVectorSize=16`, and `sql/testOnly *Varka*`.
* `build/sbt catalyst/doc` - not optional. `AGENTS.md` records that Scala
  2.13's typechecker reports "illegal cyclic reference" on
  `java.lang.classfile` symbols and that the Maven scaladoc pass fails on it;
  this is the local reproduction, and it is the gate a Java file called from
  Scala can trip.
* `dev/lint-java`: checkstyle at severity `error`, nothing suppressing this
  package - 100 columns, mandatory ASF header, newline at end of file, no
  trailing whitespace, no tabs, no unused imports. `org.slf4j` is banned in
  favour of `SparkLogger`/`SparkLoggerFactory`; `com.google.common.cache` is
  not on the ban list, so the existing cache imports port over untouched.
* scalastyle on catalyst and sql, main and test; the 100-character and ASCII
  scans.
* **The behaviour-preservation proof is the committed hashes.**
  `586434f9b9739c40` (`VarkaShapeCacheSuite:293`) and `612c94d132690dc2`
  (`:311`) must come out byte-identical. They are SHA-256 over
  `VarkaVectorIR.canonical`, they name every emitted class, and those names are
  baked into the bytes and quoted in telemetry. If they hold, no committed
  number can have moved - which is why no benchmark is run and no performance
  claim is written anywhere.
* `VarkaGeneratedClassLoaderSuite`'s weak-reference Metaspace proof green
  against the Java loader.
* `./build/mvn -f sql/varka/engine/pom.xml install`, since the migrated
  loader's twin lives there and the two are meant to stay identical.

## 7. Sequencing

Four commits, each independently reviewable and green on its own:

1. `VarkaGeneratedClassLoader` to Java. Smallest, no design content, and it
   proves the Java-first mechanics - header, checkstyle, the scaladoc gate -
   before anything subtle depends on them.
2. The shape-cache split, carrying the sizing, `asMap().compute`,
   cached-constructor and name-from-hash items with it.
3. Emit options, deleting the hook stack, the gate, the generation counter, the
   re-export shim and the enforcement suite, and settling the two fault
   injection suites.
4. `canonicalShallow` and its pinning test, with both emitter call sites moved
   onto it - kept separate because it is the only commit that changes emitted
   bytes.

Then the records: this file's outcome section, the debt-register rewrite, the
four-lane sweep, and `m3/PLAN.md`'s task-23 row marked DONE, which
closes milestone 3.

## 8. Risks, ranked

1. **A wrong cache hit is a wrong answer.** The key's equality is the contract
   and the migration changes its representation. The oracle is the two
   committed hashes; `List.copyOf` in a compact constructor closes the aliasing
   hole a mutable component would open.
2. **The Guava unwrap must rethrow the cause as itself**, or a fatal error or
   an interrupt starts being counted as a kernel failure - a regression in the
   fallback contract that no existing test would catch, because the suites
   inject ordinary exceptions.
3. **Two suites lose their fault injector** when the gate goes. Settle the
   replacement before deleting it, not after.
4. **The line map's `Record.toString`** looks like a one-line fix and is not.
5. **Scaladoc, not scalac, is the gate** that fails on class-file types, so
   `catalyst/doc` runs before the PR rather than after CI says so.

## 9. Explicitly out of VARKA-23

`VarkaExpressionCompiler`, `VarkaFusionReport`, `VarkaKernelEvaluator` and the
four exec nodes stay Scala, each with its reason recorded in section 1 - that
record is a deliverable of this task, not a deferral of it. No new config
entry. No new expression, type, lane width or IR node beyond the shallow
rendering section 4 names. No benchmark run and no performance claim: the task
moves no numbers, and section 6's committed hashes are how that is proven
rather than asserted.

## 10. Outcome

The four commits section 7 planned, plus one for the review's corrections and
one for the records. A fifth kind of change - a Java configuration surface -
was built during the task and then scoped out of it by the owner; what it
taught is recorded under "Deferred to a dedicated task" below.

**1. `VarkaGeneratedClassLoader` to Java** (70 lines). Behaviour identical, the
`IllegalStateException` message included. The one declaration that could not
carry over is `private[sql]`, which Java cannot express, so the class is plain
public - which widens nothing, since `private[sql]` is already public in
bytecode and the only callers are the shape cache and Varka's own suites. The
duplication with the engine's `VarkaClassLoader` is now literal rather than
behavioural: the two bodies differ only in class name and package, and both
javadocs say so.

**2. The shape-cache split.** 404 Scala lines became ~370 Java lines in four
files (`VarkaShapeKey`, `VarkaShapeEntry`, `VarkaShapeLookup`,
`VarkaShapeCacheImpl`) plus a 95-line Scala facade. The split line is "what
reads Spark's configuration or environment", and exactly two things cross it:
the capacity and the parent class loader. Section 3 said "capacity and identity
as plain values"; in the event, the identity turned out to be the parent
loader, which the core takes as an argument to `getOrEmit`. That is why
`VarkaShapeCacheSuite`'s loader test now names two loaders directly instead of
swapping the thread's context loader - it tests the same thing more plainly,
and the facade is where `Utils.getContextOrSparkClassLoader` lives.

The sizing fix landed as: read the JVM's own `SparkConf` when a `SparkEnv`
exists, the entry's default otherwise. That is one source per JVM, identical
for every thread and fixed for the JVM's lifetime, which is what makes the lazy
singleton deterministic rather than a race with whichever thread touched it
first. The three non-mechanical points of section 3 were all three of them:
`List.copyOf` in the compact constructor, a sneaky-throw for the Guava unwrap,
and a `boolean[]` for the loading callable's flag. The unwrap was slightly
worse than expected - `ExecutionException` is checked, and `NonFateSharingCache`
is Scala and so declares no checked exceptions at all, which means the compiler
will not let it be named in a `catch` clause; it is reached by catching
`Exception` and testing, with precise rethrow keeping the method free of a
`throws`.

`NonFateSharingCache` then turned out not to be usable from Java at all, and
finding out cost a CI round trip: the PR's first push failed only the "Java 25
build with Maven" job, on `cannot infer type arguments for
NonFateSharingCache<>`. Maven shades `core` and relocates `com.google.common`
to `org.sparkproject.guava`, so the constructor arrives at javac as
`NonFateSharingCache(org.sparkproject.guava.cache.Cache)` while the argument is
a catalyst-side `com.google.common` cache. Scala never saw this - scalac
resolves the symbol from the Scala pickle, which the shade plugin does not
rewrite - and SBT does not shade, so every local gate this task ran was
structurally blind to it. The finding is bigger than the fix, and is worth
stating plainly: **the Scala original was not correct either.** Its call site
compiled against a method the shaded artifact does not contain, so it was a
latent runtime failure rather than a working alternative, unnoticed because the
Maven job builds without running tests. Upstream records the same trap
(SPARK-44064 added a Guava-free `apply` overload "to avoid non-core modules
Maven test failures caused by using shaded core module"), and the only two
other non-core callers, `CodeGenerator` and `ProtobufUtils`, both use
Guava-free overloads. Varka's was the sole exception in the tree; the port did
not create the hazard, it made it visible.

Neither Guava-free overload admits a removal listener, which this cache needs
to release evicted loaders, so the fix is Varka's own single-flight gate: a
`ConcurrentHashMap` of in-flight `CompletableFuture`s, where the winner is
whoever wins `putIfAbsent` and a loser whose winner failed retries rather than
inheriting the failure - the SPARK-43300 property, expressed directly instead
of emulated with a per-key monitor. It waits with `get()` rather than `join()`
because `join` is uninterruptible and this class's contract is that an
interrupt cancels the task. `VarkaShapeCacheSuite` gained the assertion that
names the property: racing callers of a failing shape must each receive their
own `Throwable` instance, never a co-waiter's. The owner considered and
rejected two alternatives - reusing Spark's `KeyLock` (verified to work across
the shaded boundary, but a `private[spark]` dependency carrying the same
`wait`/`notify` design) and a Scala shim in catalyst (smallest diff, but it
would have restored the compile while leaving the latent runtime hazard in
place). `VarHandle` was considered and declined on the project's own rule: the
gate is contended once per task around an ~80 us emit, `ConcurrentHashMap`
already does the atomics, and there is no measurement arguing for hand-rolled
fences. JDK 25's `StableValue` (JEP 502) would fit the lazy singleton exactly
and is preview-gated, so it is unavailable until it goes final.

The lesson is recorded in `SKILLS.md` and as a house rule in
`sql/varka/AGENTS.md`, because it constrains the whole migration rather than
this one file: Java in a non-core module must not pass a Guava type to a `core`
API, and only the Maven job can tell you that you did.

**3. Emit options.** `VarkaEmitOptions(groupBudget, cse, floorMod7,
misdescribeAdd)` replaced five static hook fields, an `AtomicLong` generation,
five setters, two queries, four re-export methods, a reflection suite, and the
cache's gate, snapshot and re-check. `Analysis` carries the record, since it
already reaches every body method. The hashing rule of section 4 held exactly:
options render into `shapeHash` only when non-default, so both committed hashes
are unchanged and only a variant gets its own name.

Two things came out differently from section 4. `fitsBudgets` needed no change
at all: the group budget is not an eligibility limit - `MAX_FUSED_NODES`,
`MAX_CHAIN_DEPTH` and `MAX_INPUTS` are - so there was no asymmetry to close,
and the plan was wrong to predict one. And the `emit` overloads did not collapse
to one: the four-argument telemetry-defaulted form has too many callers in
tests and benchmarks to be worth removing, so there are three, each delegating
to the seven-argument form.

The open question of section 4 is settled the way it recommended.
`VarkaColumnarToRowExec.setFailEmissionForTesting` injects at the evaluator's
class lookup - the seam that actually produces an emission failure - beside the
`failKernel` injector that file already owns. No new config entry, so nothing
is added to the production configuration surface.

**4. `canonicalShallow`.** The register's framing was that `renderLineMap` rode
an unspecified format. That is true, but the sharper problem is the one section
4 predicted: rendering a node inlines its whole subtree, so a shared node was
repeated once per parent and the key grew quadratically in exactly the sharing
the emitter exists to exploit. With children written as their line numbers the
key reconstructs the DAG and each node appears once. Over the every-node-type
IR that is 18 lines with `col:0` written once and pointed at eleven times; the
whole map is a committed literal in `VarkaLoopEmitterSuite`, with the same
update rule as the pinned hashes.

Two more `Record.toString` renderings went with it: `VarkaDebugInfo`'s `ir`
summary field, and `VarkaKernelEvaluator.kernelIdentity`, which the register did
not name and which every fallback warning and the JFR fallback event quote. It
renders the canonical form now, so a log line and the bytes it names describe
the shape identically.

### Deferred to a dedicated task, by the owner

A Java configuration surface for Varka was built during this task, on the
standing direction that the long-term goal is a Java Catalyst and that
duplicating a facility in the fork beats reaching into Spark's Scala machinery
from Java. The owner then scoped it out of this task and out of its PR, so it
is not in this branch. What it taught is recorded here rather than lost with
the conversation, because the next task should start from it rather than
rediscover it.

**The design.** A SQL configuration has two surfaces - the user-facing string
key and value, which is what `--conf`, `spark-defaults.conf` and `SET` need,
and the code-facing typed value an internal component reads - and binding one
to the other is a separate action rather than something every read redoes.
Spark's `ConfigEntry` is both surfaces and the binding in one object, and
re-runs the binding on every read: a string hash lookup, a regex substitution
pass, an alternatives fold and a conversion.

That framing is worth more than the speed it buys, because the binding's
*moment* is a correctness property. The deterministic-sizing debt this task
swept was exactly an unnamed binding moment - a lazy singleton that froze
whatever configuration the first thread to touch it happened to see.

**Two things the discarded attempt already proved**, and the next task should
keep both:

* Spark's converters are not the obvious ones. `ConfigHelpers.toBoolean` is
  `s.trim.toBoolean`, `toNumber` is `converter(s.trim)`, and `stringConf` is
  the bare identity that does *not* trim - so a Java parser must trim for
  numbers and booleans and must not for strings. `Boolean.parseBoolean` is also
  wrong twice over: it does not trim and it reads anything that is not `"true"`
  as `false`, so a misspelled value would silently disable the engine where
  Spark throws.
* `SparkConf.get(entry)` resolves `${...}` references through `ConfigReader`
  before converting, and `getOption` does not. Any hand-rolled read must go
  through `getWithSubstitution` or a substituted value silently stops working.

Both were found by a parity test that reads the same raw value through both
paths, and both were found *late* - the first two versions of that test missed
them, because it compared `valueConverter` against the Java parser while the
differences lived a layer up (substitution) and inside the converter's own
first statement (the trim). The lesson for the next task is that the parity
sample set is the guard, and it has to include padded, substituted and
malformed values, not just well-formed ones.

**Three increments, in the order they should be taken:**

1. The typed surface as a record of resolved fields, so a caller on a hot path
   holds values rather than a lookup. Nothing reads a Varka config per batch or
   per row today, so this is preparation, not a fix.
2. Declaration discovery by reflection over the Java class's own static entry
   fields, rather than a hand-maintained list.
3. Generate the `SQLConf` / `StaticSQLConf` declarations from the Java ones
   through a thin Scala registration shim. That turns a deliberate duplication
   into generation with one source of truth, at which point a drift test has
   nothing left to guard and can go.

### What the boundary looks like now

The 474 unforced Scala lines section 1 measured are gone, and the facade that
replaced part of them is 95 lines whose whole job is Spark interop. Everything
still Scala is Scala for a reason recorded in section 1:
`VarkaExpressionCompiler` (35 `case` arms over Catalyst case classes),
`VarkaKernelEvaluator` (the task-21 `VarkaExecMetrics` decision, Arrow and
`SQLMetric`), `VarkaFusionReport` (assessed and declined), the four exec nodes
and the columnar rule (`SparkPlan`, `ColumnarRule`), and the suites
(ScalaTest). The one lever that would move `VarkaExpressionCompiler` is an
adapter pass that converts Catalyst expressions into a Varka-owned Java model
once, so the compiler can be written against that instead of against Catalyst's
case classes - a design, not a port, and its own task.

### Verification

Everything section 6 asked for, all green:

* 81 catalyst Varka tests and 122 sql/core Varka tests, at the host's preferred
  vector width and again under `-XX:MaxVectorSize=16`.
* The two committed hashes, `586434f9b9739c40` and `612c94d132690dc2`,
  unchanged - which is the whole behaviour-preservation proof, and why no
  benchmark was run and no performance claim is made.
* `build/sbt catalyst/doc` clean, so no Class-File type reached a signature
  scaladoc has to complete.
* `dev/lint-java` clean. It caught one thing worth recording: an interface
  member is implicitly public, so `public static String canonical` is a
  `RedundantModifier` error - `canonical` had been reachable from `sql/core`
  all along.
* scalastyle clean over catalyst and sql/core, main and test.
* `./build/mvn -f sql/varka/engine/pom.xml install` green, both executions.

### The documentation sweeps

Section 5.1's four-lane record was split rather than struck, at all four sites
it lives at (`m3/PLAN.md` section 5, `ISSUES.md` finding 1's caveat
and the suggested-order list, and the engine `pom.xml`'s surefire comment): the
aarch64 job runs the engine module alone, so the hand-written kernels are
covered on real ARM and the emitted loops are not. Section 5.2's debt-register
entry is rewritten in the past tense with what the sweep found, per
`AGENTS.md`, rather than deleted.
