# Reading for row 508: Ekstazi, from its own paper

Read on 29 October 2026 at the owner's request, for row 508 step 1 (a pull request's option
matrix that reruns a (configuration, test) pair only when something it depends on changed). It
completes `READING_TEST_SELECTION.md`, which read uRTS in full but knew Ekstazi only from its
abstract and from uRTS's account; uRTS is not repeated here. The Ekstazi paper was read in full
from the author's copy at UT Austin; the static-RTS study by Legunsen et al. was read for its
safety comparison only. Both copies print ACM's notice (personal or classroom use, no
redistribution), so `sql/varka/papers/` holds no transcription (its `README.md`, Licensing).
**[R]** means read in the paper; **[I]** inferred.

| paper | source | licence of the copy |
| :--- | :--- | :--- |
| Gligoric, Eloussi, Marinov: "Practical Regression Test Selection with Dynamic File Dependencies" (ISSTA 2015, DOI 10.1145/2771783.2771784) | `https://users.ece.utexas.edu/~gligoric/papers/GligoricETAL15Ekstazi.pdf`; tool at `http://www.ekstazi.org` | ACM; personal or classroom copies only |
| Legunsen, Hariri, Shi, Lu, Zhang, Marinov: "An Extensive Study of Static Regression Test Selection in Modern Software Evolution" (FSE 2016, DOI 10.1145/2950290.2950361) | `https://mir.cs.illinois.edu/~marinov/publications/LegunsenETAL16StaticRTS.pdf` | ACM; personal or classroom copies only |

Two things the earlier note took from uRTS are settled here from the primary source (the
instrumented code points, section 1, and which files are tracked), and one is corrected: the
earlier note says Ekstazi's default tracks only class files. The paper says the opposite (section
3.3): it collects every file read or written, and even files a test tried to open that did not
exist. uRTS's "Ekstazi-minus" may describe a later default of the tool; the paper does not say,
and this reading could not check it.

## What it is

**The idea [R].** Regression test selection as memoization, borrowed from build tools (Memoize,
Fabricate): for each test entity, record the files it used while it ran, with a checksum of each;
on the next revision, rerun the entity only when one of those checksums differs (section 3.1).
The old revision is never needed and nothing is diffed: the analysis reads the dependency files
and checksums the current files. A test with no dependency file is selected, so the first run
selects everything and a new test always runs. Safety is argued, not proven, from two proven
results: RTS on class dependencies (Skoglund and Runeson) and partial builds on file
dependencies (Christakis et al., who assume deterministic code with no network access).

**Two granularities [R].** The paper separates the *selection granularity* (what is selected: a
test method or a test class) from the *dependency granularity* (what is checksummed: a file,
which for code is one `.class` file). Ekstazi always uses files for the second and offers either
for the first. A dependency on class D includes all of D's superclasses, and every entity depends
on its own test class (section 2). The example of section 2 is why methods are not used as
dependencies: adding an override `D.m` changes what an old test calls without touching any
method it ran, and a changed final field read from an unchanged method needs field tracking;
class files catch both without diffing.

**How it records (section 3.3) [R].**

- A pure Java library called from the test framework, not an OS-level tracer: `strace`-style
  tools cannot split one JVM's file accesses by test entity and cannot see a class inside a jar.
- `entityStarted(name)` clears the collected set; `entityEnded(name)` writes it to one dependency
  file named after the entity (section 3.4: one file per entity, so parallel JVMs need no lock).
- Under method granularity, what the test class's constructor and `setUp` used is appended to
  each method's set, since JUnit runs those once or before each method.
- Explicit file use: every standard library method that may open a file (the paper's example is
  `FileInputStream`) is monitored. Reads and writes both count, and so do failed opens.
- Implicit use (class files): the bytecode is instrumented, by a class loader or a Java agent, to
  record the `java.lang.Class` objects a test uses; Ekstazi then finds where each was loaded from,
  including a `.class` inside a `.jar`. The seven instrumented points are: (1) the start of a
  constructor, (2) the start of a static initializer, (3) the start of a static method, (4) an
  access to a static field, (5) a class literal, (6) a reflective invocation, (7) an
  `invokeinterface`. Instance method entries are not instrumented: an instance of C exists, so
  C's constructor already recorded it. The test class itself is caught by its constructor.
- Smart checksums (section 3.5): the class file's checksum can skip debug information and
  compile-time annotations, so an added blank line in a source file selects nothing.

**Generated classes [R].** Quoted in substance from section 3.3: a class not loaded from disk but
created during execution need not be tracked, because it cannot change unless the code that
generates it changes. The generator's class files are in the record; the generated bytes are not.

**Integration [R].** Tight (section 3.2): `ekstazi.jar` replaces `junit.jar`, the build runs every
test class, and Ekstazi skips the unaffected ones inside JUnit. Loose: an analysis pass writes an
excludes list of test classes for the build, which saves loading classes or forking a JVM for a
test that will not run. Collection can run in the same pass (AEC) or in a second, later pass
(AE now, C offline). Where Ekstazi cannot integrate (JUnit 3 with class granularity, JUnit 4
custom runners with method granularity) it runs every test.

**Tests sharing a JVM [R].** Because the record is taken on *use* (constructor, static access,
interface call), not on class loading, a class that an earlier test in the same JVM loaded is
still recorded for a later test that uses it. This is the answer to the trap the earlier note
raised. Two behaviours of the shared record are reported in section 6: a test class listed twice
in one suite is skipped on its second appearance (its dependency file already matches), and a
method run several times in a row has its sets unioned, which is how parameterized tests are
handled (one entity, all inputs' dependencies).

**What the record misses [R, I].** The paper names its limits: a flaky test's record covers the
one run observed; databases and web services are not tracked (section 7, "no special support for
dependencies other than files"). The residual gap in the shared JVM [I]: a test that reaches an
object built by an earlier test only through instance methods (an object handed over in a
non-static holder, or a cached value returned through a virtual call) records no event for the
object's class. Static fields, interface calls and constructors close most of these paths, not
all. Whether jar resource streams (`getResourceAsStream`, `ServiceLoader`'s
`META-INF/services` files) count as "methods that may open a file" is not stated in the paper.

## What it measured

**Setup [R].** 32 open-source Java projects, 615 buildable revisions (the last 20 of each),
4,937,189 lines, 7,479 test classes; RetestAll time from 8 to 2,565 seconds a revision. Time is
the end-to-end time of the developers' own build command (`mvn test`, `ant junit-tests`),
unchanged, including its use of several cores or forked JVMs. Default configuration: class
selection granularity, smart checksums on. One 4-core 1.6 GHz i7 with 4 GB.

**Main results, Figure 4 [R].** Averages of per-revision ratios to RetestAll:

| | entities selected | AEC time | AE time |
| :--- | ---: | ---: | ---: |
| all 32 projects | 14% | 68% | 53% |
| 16 short-running (under a minute) | 14% | 90% | 72% |
| 16 long-running | 15% | 46% | 34% |

The abstract's 32% and 54% savings are 1 - 0.68 and 1 - 0.46. Selection ranged from 5% to 38%,
AEC time from 9% (Camel core) to 138% (BVal, a slowdown); every slowdown was a short-running
suite. A revision that selects almost everything is slower than RetestAll, since analysis and
collection are paid on top. The first run's collection is the dearest: about 8 times RetestAll
for CommonsMath.

**What moved the time [R].**

- Smart checksums (Figure 7, nine projects): 17% to 13% of methods selected, AEC 60% to 54%, AE
  45% to 41%. Where no revision changed only debug information, they cost a little.
- Selection granularity (Figure 8, 19 projects): method 13% selected, AEC 87%, AE 61%; class 14%
  selected, AEC 77%, AE 59%. Classes select slightly more and finish sooner; the paper makes them
  the default. Guava's 641,534 test methods could not even be tried by method, as one dependency
  file per method would exceed file system limits.
- Dependency granularity (Figure 9, seven projects that FaultTracer could handle): FaultTracer's
  control-flow-edge dependencies selected 5% of methods at 279% of RetestAll's time; Ekstazi
  selected 14% at 76%. A finer record selects less and costs more than it saves, in both
  collection and analysis.
- Apache CXF, after it adopted Ekstazi: 189 revisions took about 54 hours with Ekstazi against
  about 148 for RetestAll, 2.74 times faster.

**Sparse collection (section 6) [R].** Collecting dependencies every n-th revision is safe as long
as the analysis compares the current files against the revision whose dependencies were
collected, not against the previous revision. It trades precision for collection cost.

**The static comparison (Legunsen et al., FSE 2016) [R].** 985 revisions of 22 projects. Ekstazi
selected 20.6% of tests, a static class-level technique (ClassSRTS, over a static graph of the uses
and inheritance between types, Orso et al.'s intertype relation graph) 29.4%, a static method-level
one 43.8%. End-to-end, ClassSRTS and Ekstazi took 62.5% to 68.2% of RetestAll; the method-level one
was slower than RetestAll. Taking Ekstazi's selection as the reference, ClassSRTS missed a test in
0.2% of revisions (2 projects) and the method-level technique in 10.6% (14 projects). The misses
came from reflection (a class name built from a string, which Ekstazi sees because it records the
use at run time) and from leaving third-party libraries out of the graph. A list of classes worked
out ahead of a run is a static technique, with that blind spot.

## What Varka takes from it

1. **Key by use, recorded, not by a written list [R, I].** The row's question is which runtime
   classes key the cache. Ekstazi's answer is not a list: record per test the classes it used at
   the seven code points, resolve each to its file, keep the checksum. Legunsen et al. show what a
   list costs: it is a static technique and misses what reflection reaches. Spark loads
   `ServiceLoader` providers and data sources by name, so the gap is real here.
2. **The shared-JVM answer is "on use, not on load" [R].** Many Varka suites share a JVM, and a
   class loaded by an earlier suite would be missing from a later test's set under a load-time
   record. Ekstazi's instrumented points close most of that; the earlier note's fallback (give
   each test the classes its whole suite loaded) is coarser and still unsafe across suites in one
   JVM, since a class loaded by the previous suite is in neither set.
3. **Generated kernels are keyed through their generator, and Varka can do better [R, I].**
   Ekstazi leaves runtime-generated classes out because the generator's class files are in the
   record. Applied literally to Varka, every test depends on the compiler and the emitter (every
   test compiles), so any emitter change reruns everything: exactly what row 508 exists to avoid.
   Varka has what Ekstazi lacks, the generated bytes themselves: `VarkaTestReach` already
   compares, per test and configuration, each compile's result, each decline, and each kernel's
   bytes (its `Emission` record). A digest of those is a dependency file on the generated output,
   finer than Ekstazi's dependency on the generator.
4. **Invert the hand-written list [I].** So the key is: (a) the digest of what the test compiled
   and emitted under the configuration, plus (b) the checksums of every class the test used,
   minus an *exclusion list* of compiler and emitter classes whose whole effect (a) captures. A
   class wrongly left off the exclusion list only costs reruns; a class wrongly put on it is a
   skip without a check, so that list is the one place where safety is argued, and it needs a
   check (item 6). This replaces the row's "Varka's runtime classes outside the compiler and the
   emitter", which is a positive list and fails unsafe.
5. **Compare against the recorded revision, not the PR's base [R].** Row 508's record is master's
   last green matrix, refreshed weekly: that is sparse collection, safe only because the analysis
   checksums the pull request's classes and digests against the record, never a diff against the
   base branch.
6. **Class selection first [R].** Ekstazi's measured default is the test class; row 508's unit
   is the suite under one configuration, which is the same choice. Go to single tests only where
   the matrix's time stays in a few suites.
7. **End-to-end time [R].** Both papers judge by the time of the whole job, analysis and
   collection included; a short job gains nothing (90% for suites under a minute). The matrix's
   bins are long, which is the case where Ekstazi halved the time.

## What does not transfer

- **The exclusion is Varka's, not Ekstazi's [I].** Ekstazi's safety rests on recording every
  class used. Removing compiler classes from the record is sound only if nothing in them is
  reached while a kernel runs, and only if the digest covers every effect they have on the
  answer (compiled form, decline or kernel, bytes). A static helper in the emitter's package that
  a kernel calls at run time, a refusal message built lazily, or an option the evaluator reads as
  it runs (`VarkaTestReach.FULL` already names `exactRefusalStatus` for this reason) breaks it.
- **Configurations are not files [R, I].** Ekstazi has one record per test, not per (test,
  configuration); uRTS adds the second axis. Under an Ekstazi-style record each pair needs its own
  class set, since a configuration can make a test use other runtime classes (a different
  validity layout's helpers). Recording that set means running the pair, which the defaults run
  does not do: it re-emits under each configuration but executes only at the defaults.
- **Generated classes are visible, but should not be keyed by file [I, JDK source].** HotSpot
  skips the JVMTI ClassFileLoadHook only for hidden classes (`klassFactory.cpp`, "Skip this
  processing for VM hidden classes", OpenJDK 25). Varka's kernels go through
  `VarkaGeneratedClassLoader.defineGeneratedClass`, a plain `defineClass`, so an agent sees and
  could instrument them, but they have no file to resolve and checksum; their key is the digest.
- **Non-class files [R, I].** The paper tracks `java.io` opens. Spark's tests also read jar
  resources (service files, `error-conditions.json`, golden files) and write to temporary
  directories; whether a jar-resource read is recorded is not stated, and temporary files that
  change name every run would need excluding or they select every test.
- **Nondeterminism [R].** Both papers note a dynamic record covers one run. A Varka test whose
  plan depends on timing (adaptive execution, a cache filled by an earlier test in the JVM) can
  use classes in one run it did not in the recorded one; recording at the defaults and running
  in a matrix bin with other neighbours is a different JVM history.
- **Concurrency inside one JVM [I].** `entityStarted`/`entityEnded` assume one test at a time per
  JVM; a background thread left by an earlier test (a Spark executor pool) records into whichever
  entity is open. Spark's executor threads are shared across tests, so their uses must be
  attributed to the open test, which is the conservative choice.

## For the rows

**Row 508 step 1, a design sketch [I].**

1. *What is recorded per (test, configuration).* In the defaults run, extended from
   `VarkaTestReach`'s record: for each covered configuration, a digest (SHA-256, say) over the
   test's ordered compile outcomes and kernels' `Emission` bytes, which the recorder already
   computes and compares. Once per test (not per configuration): the set of main classes used,
   recorded Ekstazi's way, on use, by a test-only Java agent; checksums are taken when the record
   is written. Spark and Arrow classes outside Varka stay in the set: the row's "Varka's runtime
   classes" leaves out `ColumnarBatch`, the Arrow readers and Spark's own expressions, which the
   answer depends on as much as on the kernels.
2. *What is excluded.* A written list of compiler and emitter classes (the IR, the lowering, the
   emitter, the cost model), each kept only while the check in step 4 passes for it.
3. *The decision in a pull request's matrix.* Rerun a (configuration, test) pair when the test
   has no record, its digest under that configuration differs from master's (the pull request's
   defaults run recomputes it), or a class in its used set, minus the exclusions, has another
   checksum. A configuration in `VarkaTestReach.FULL` and a suite in `UNRECORDED` run in full, as
   today. A pair skipped carries master's record forward, as uRTS copies a dependency file.
4. *The safety check of the exclusion list.* Bracket the class-use record into the compile phase
   and the execution phase (`VarkaCompileObserver` already brackets compiles and emissions for the
   reach record). An excluded class that appears in any test's execution phase fails the build.
5. *The planted check in the row's done-when.* A change to a class on neither list (a vector
   helper) must rerun every test that used it; a change to an excluded emitter class that moves
   one kernel's bytes must rerun only the pairs whose digest moved.

**The first measurement, before building any of it [I].** Not run here. From one existing
defaults run of the sql module's Varka suites, log class loading per suite JVM (`-Xlog:class+load`
or the log a matrix job already keeps) and count, for each suite, how many of Varka's main classes
outside the compiler and the emitter it loads. If every suite loads nearly all of them (the
vector helpers, the evaluator, the Arrow readers are used by any query), a per-test class record
selects nothing beyond a single checksum over all of Varka's runtime classes, and all the
selectivity comes from the digest; then step 1 needs no agent, only the global checksum, the
exclusion list and its execution-phase check. If the sets differ, measure next what the agent
costs on the defaults run's time, against the 14% the reach record already costs. Report it as
Ekstazi did: end-to-end time of the dispatch against running every pair.

**Not worth taking [I].** Method-level dependencies (FaultTracer's cost, 279% of RetestAll) and a
static class graph as the runtime key (Legunsen's 0.2% of revisions missed, through reflection).
