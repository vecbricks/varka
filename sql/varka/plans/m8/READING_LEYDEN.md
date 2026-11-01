# Reading for the matrix's JVM start: Project Leyden's AOT cache in JDK 25

Read on 29 October 2026 at the owner's request: can the option matrix's roughly 1500 test JVMs a
run, and the PR Build's, start from an AOT cache, and what would that do to the suites whose
subject is the JIT? The three JEP pages were read on openjdk.org. HotSpot and the JDK's Java
sources were read in the local tree at `/home/max/proj/openjdk-build/jdk25`, commit `6c48f4ed7`
(tag `jdk-25-ga`); paths below are relative to it. Varka's side was read in this worktree:
`project/SparkBuild.scala`, `.github/workflows/varka-option-matrix.yml`, `dev/varka_matrix.sh`
and `VarkaClassLoader.java`. Nothing was built or run, so every claim about what a JVM does is a
reading of the code, not an observation. **[R]** means read; **[I]** inferred.

| what | source | licence of the copy |
| :--- | :--- | :--- |
| JEP 483, AOT class loading, linking (JDK 24) | `https://openjdk.org/jeps/483` | GPLv2 (footer) |
| JEP 514, AOT command-line ergonomics (JDK 25) | `https://openjdk.org/jeps/514` | GPLv2 (footer) |
| JEP 515, AOT method profiling (JDK 25) | `https://openjdk.org/jeps/515` | GPLv2 (footer) |
| HotSpot, `java.base`, jtreg `runtime/cds` | local tree above, `6c48f4ed7` | GPLv2 + CPE |

Nothing is transcribed into `sql/varka/papers/`; the JEPs are short and stay online.

## 0. The answer in two findings

1. **In JDK 25 a Varka test JVM can get only half of JEP 483, and none of JEP 515 [I, from the
   code chain in section 4.1].** Every Spark test JVM runs with
   `--add-modules=jdk.incubator.vector` (`project/SparkBuild.scala` line 349, for netlib's BLAS,
   and Varka needs the module anyway). The JDK does not archive the boot module layer when an
   incubator module is resolved; without it the cache has no full module graph, so no
   AOT-linked classes and no method profiles. What is left is a cache of parsed classes, the
   "AOT cache, no loading/linking" column of JEP 483's table (+13% and +33% there).
2. **The matrix's classpath cannot be cached as it stands [R].** It holds the build's class
   directories as well as jars (the comment above "Archive the classpath" in
   `varka-option-matrix.yml`), and HotSpot refuses to dump an AOT cache when a non-empty
   directory on the classpath supplied a class. The class directories have to become jars first.

Both have to be confirmed by a run (section 6) before any row builds on them.

## 1. JEP 483: what the cache holds and the rules for using it

**What it is [R].** A training run (`-XX:AOTMode=record -XX:AOTConfiguration=app.aotconf`)
records what the application loads and links; a create step (`-XX:AOTMode=create`, which does
not run the application) writes the cache; later runs pass `-XX:AOTCache=app.aot`. The cache
holds JDK classes and classes from the class path and module path, loaded by the built-in
loaders, in a loaded and linked state, plus archived heap objects (the CDS heap). File formats
are unspecified. Compiled code is not cached; the JEP lists it as future work.

**What it measured [R].**

| workload | JDK 23 | AOT cache, no loading/linking | with loading/linking (JDK 24) |
| :--- | ---: | ---: | ---: |
| HelloStream, about 600 JDK classes | 0.031 s | 0.027 s (+13%) | 0.018 s (+42%) |
| Spring PetClinic 3.2.0, about 21,000 classes | 4.486 s | 3.008 s (+33%) | 2.604 s (+42%) |

The caches were 11.4 MB and 130 MB. The middle column is what JDK 23's AppCDS already gave.

**The rules [R].** All runs must use the same JDK release, architecture and OS. The class path
of a later run must equal the training class path or extend it by appended entries, and must
contain only JAR files. `-m`, `-p`, `--add-modules` and `--enable-native-access` must be
identical; the JEP says `--add-exports`, `--add-opens`, `--add-reads`, `--limit-modules`,
`--patch-module`, `--upgrade-module-path` and `--illegal-native-access` must not be used. No
JVMTI agent may rewrite class files through `ClassFileLoadHook`. The garbage collector and the
main class may differ. ZGC is not supported yet. Classes from user-defined loaders are a stated
non-goal, and signed classes and classes needing the old verifier are not cached.

**What the code adds [R].**

- *Flags.* `src/hotspot/share/cds/cds_globals.hpp` lines 107-151: `AOTMode` takes `off`,
  `record`, `create`, `auto`, `on` (default `auto`); `AOTClassLinking` is a product flag;
  `AOTRecordTraining`, `AOTReplayTraining` and `AOTInvokeDynamicLinking` are diagnostic.
  `cdsConfig.cpp` 616-629 turns `AOTClassLinking` on whenever any `AOTMode` is given. The only
  code-caching flags, `AOTAdapterCaching` and `AOTStubCaching` (lines 154-158), are diagnostic
  and off; nothing caches compiled methods.
- *JAR identity.* `aotClassLocation.cpp` 224-242 records, for every entry but the runtime image,
  the size and the modification time; `check()` at 392-443 rejects the cache when either
  differs ("This file is not the one used while building the shared archive file").
- *Directories.* `check_nonempty_dirs()` at 684-704 ends the dump with "Cannot have non-empty
  directory in paths" when a non-empty directory sits at or before the last classpath index a
  class was loaded from. At run time a non-empty directory where the cache saw an empty one
  rejects the cache (line 408).
- *`--add-opens` and friends.* Here the code is looser than the JEP. `classfile/modules.cpp`
  578-626 archives `--add-exports`, `--add-modules`, `--enable-native-access`, `--add-opens` and
  `--add-reads` as sorted, de-duplicated strings and compares them at run time; a mismatch
  disables optimized module handling, which also turns off the archived module graph. Only
  `--patch-module`, `--limit-modules`, `--upgrade-module-path` and `--illegal-native-access`
  are refused outright (`runtime/arguments.cpp` 340-348, `cdsConfig.cpp` 309-375). The jtreg
  test `jigsaw/addopens/AddopensOption.java` dumps and runs with `--add-opens`. Spark's test
  JVMs pass fourteen `--add-opens` (`SparkBuild.scala` 2126-2139) [I]: the code accepts them if
  every run passes the same set, but the JEP is the contract and says no, so a later JDK may
  refuse them.
- *Agents.* `filemap.cpp` 1853-1866: a cache with AOT-linked classes is rejected when a
  `ClassFileLoadHook` is enabled at start. JaCoCo runs (`-javaagent`) cannot use the cache [I].
  Whether a later self-attach (Mockito's inline mock maker, allowed by Spark's
  `-XX:+EnableDynamicAgentLoading`) causes trouble after start was not traced.

**What happens on a mismatch [R].** With `auto` (the default once `-XX:AOTCache` is given,
`cdsConfig.cpp` 466-480) the JVM continues without the cache. It is not silent: when the new
flags are used, `MetaspaceShared::report_loading_error` (`metaspaceShared.cpp` 1277-1302) prints
"An error has occurred while processing the AOT cache. Run with -Xlog:aot for details." and the
reason at error level on the `aot` tag, which unified logging shows on stdout by default. A few
header checks (object alignment, compact strings, `filemap.cpp` 1925-1937) log at info level
only. With `on` (`required` from JDK 27) the JVM exits (`aotClassLocation.cpp` 1030-1037).

## 2. JEP 514: the one-command training run

**What it is [R].** `-XX:AOTCacheOutput=app.aot` on the training run replaces record plus create.
The JVM trains into a temporary configuration file, then runs the create step and deletes the
file. `JDK_AOT_VM_OPTIONS` passes options to the create step only. The create step gets a heap
the size of the training run's, so peak memory roughly doubles (the JEP's example: `-Xmx4g`, about
8 GB). The two-step form remains for split or constrained environments. No measurements.

**What the code adds [R].** The create step is a separate process:
`MetaspaceShared::fork_and_dump_final_static_archive` (`metaspaceShared.cpp` 1162-1184) prints
"Launching child process", execs the launcher and leaves the configuration file behind if the
child fails. The training data is written by `preload_and_dump`, called from `before_exit`
(`runtime/java.cpp` 449-451): a training JVM that is killed, halted or timed out writes nothing.

**What Varka takes from it [I].** On a 16 GB runner a `-Xmx4g` test JVM fits the doubled peak,
but the two-step form is clearer in a workflow: the record step is an ordinary bin run, the
create step a separate command whose failure the step reports.

## 3. JEP 515: method profiles in the cache

**What it is [R].** The cache also stores, for methods that were hot in training, their profiles
(execution counts, the types seen), so that "the JIT runs earlier and with more accuracy".
Profiling continues in production. No workflow change; JEP 514's commands are used. No flag to
turn it off is named in the JEP.

**What it measured [R].** One program, HelloStreamWarmup (about 900 JDK classes, about 30 methods
reaching the top tier): 90 ms without profiles, 73 ms with (19%), for 250 KB more cache (2.5%).
Longer programs are said to be "likely" to gain; no numbers.

**What the code adds [R].**

- *Who switches it on.* `CDSConfig::setup_compiler_args` (`cdsConfig.cpp` 695-715) sets
  `AOTRecordTraining` in the training run and `AOTReplayTraining` in the create step and in every
  production run that uses the new flags. `TrainingData::have_data()` is simply
  `AOTReplayTraining` (`oops/trainingData.hpp` 283). It is set from the command line, before the
  cache is opened, so it is true even when the cache holds no profiles or is rejected. The off
  switch is `-XX:+UnlockDiagnosticVMOptions -XX:-AOTReplayTraining` (diagnostic).
- *What is stored.* Per class a `KlassTrainingData` (its initialization dependencies), per method
  a `MethodTrainingData` (the highest tier it reached, whether it was only inlined, and its final
  `MethodData`), per top-level compile a `CompileTrainingData` (including the inlining facts the
  compile read). `archiveBuilder.cpp` 560-566 copies `MethodData`, `MethodCounters` and the three
  training records only when training data is being written or assembled.
- *What changes for a method with a record.* `Method::install_training_method_data`
  (`oops/method.cpp` 626-633) makes the archived `MethodData` the method's live profile.
  `should_create_mdo` (`compilationPolicy.cpp` 1062-1067) starts profiling at once for a method
  that reached tier 4 in training. `trained_transition_from_none` (1119-1170) sends it to tier 2
  and then straight to tier 4 once its class dependencies are initialized, skipping tier 3's
  profiling (to tier 4 directly with the diagnostic `SkipTier2IfPossible`).
  `ciMethod::inline_instructions_size` (`ci/ciMethod.cpp` 1152-1170) gives C2 the callee sizes
  recorded in training for its `InlineSmallCode` decision. `AOTCompileEagerly` (experimental,
  off) would compile such methods as their classes initialize (`compilationPolicy.cpp` 141-163).
- *What changes for every method.* `call_event` (`compilationPolicy.cpp` 1420) passes
  `!have_data() && is_old(method)` as `disable_feedback`: without a cache an old method skips the
  C2-queue feedback and goes to tier 3; with `have_data()` it can be sent to tier 2 while C2's
  queue is long. In `common()` (1298-1310) a method with a record whose trained transition does
  not apply takes the standard transition with delayed profiling. A "Training replay thread" is
  started at init (`compileBroker.cpp` 1033-1040).
- *Flags that must match.* With `have_data()`, `TypeProfileLevel`, `TypeProfileWidth`,
  `ProfileTraps` and five more profile-shape flags must equal the training run's, or the cache is
  rejected (`filemap.cpp` 1938-1992).

## 4. Varka against the code

### 4.1 Why profiles and AOT-linking are off for Spark's test JVMs [I]

The chain, each link read:

1. `java.base/.../jdk/internal/module/ModuleBootstrap.java` 462-479: when dumping, the boot layer
   is archived only if no resolved module is incubating (`containsIncubatorModule`, 982-986).
   `jdk.incubator.vector` is.
2. `metaspaceShared.cpp` 946-951: no archived boot layer, so "archivedBootLayer not available,
   disabling full module graph" and `stop_dumping_full_module_graph()`.
3. `cdsConfig.cpp` `is_dumping_aot_linked_classes()`: for a static archive it requires
   `is_dumping_full_module_graph() && AOTClassLinking`, now false.
4. `trainingData.hpp` 285: `assembling_data()` requires `is_dumping_aot_linked_classes()`, so the
   create step builds no training dictionary (`trainingData.cpp` 457-482) and nulls every
   profile record (`archiveBuilder.cpp` 566).

The jtreg test `jigsaw/addmods/AddmodsOption.java` uses `jdk.incubator.vector` and expects
"subgraph jdk.internal.module.ArchivedBootLayer cannot be used because full module graph is
disabled", which agrees. The source of the constraint is the JDK's module bootstrap, not Varka;
whether a JDK after 25 lifts it was not read.

### 4.2 Varka's kernels are not in the cache [I]

`VarkaClassLoader` (`sql/varka/engine/.../execution/VarkaClassLoader.java`) is a user-defined
loader that calls `defineClass(name, bytes, ...)`; kernels are not hidden classes. JDK 25 can
cache some user-loader classes (jtreg `aotCache/AOTCacheSupportForCustomLoaders.java`), but the
recording path keeps only those with a `file:` source (`cds/classListWriter.cpp` 110-121), and a
cached one is served at run time only for a class of the same name, size and CRC32
(`systemDictionaryShared.cpp` 119-154). I did not find the line that drops a bytes-defined class
in the AOT record mode itself; the first run's `-Xlog:aot` will show it. Spark's Janino classes
are in the same position. It does not matter much: a kernel is defined after the session is up,
its cost is emission and compilation, and the cache saves neither. Training records need a
cached class (`replay_training_at_init`, `compilationPolicy.cpp` 166-170) and a cached method
(`Key::can_compute_cds_hash`, `trainingData.cpp` 692), so a kernel never gets one.

### 4.3 Which part is safe for which suite [I]

With classes only (JEP 483) and `-XX:-AOTReplayTraining`, no compile policy reads the cache,
so all five suites measure what they measure today. Without that flag even a profile-free cache
moves tiers (the `disable_feedback` change of section 3). With profiles (JEP 515, dormant in
JDK 25 by 4.1), each suite would move:

- `VarkaTierWitnessSuite`: old kernel methods can go to tier 2 instead of 3 under C2 load, and
  trained harness and runtime methods skip tier 3.
- `VarkaKernelWarmupSuite`, `VarkaWarmupReachSuite`: trained Varka runtime and JDK vector
  methods reach tier 4 earlier, so a warm-up count measured from the first call shrinks.
- `VarkaInliningCliffSuite`: trained callee sizes feed `InlineSmallCode`, so inlining of a
  cached callee into a cached caller can differ from an untrained run.
- `VarkaCoverageCompositionFuzzSuite`'s C2 history test: an archived `MethodData` carries the
  training run's trap history, so C2 may decline a speculation the test expects to see fail.

`AOTReplayTraining` is set in every production run that uses the new flags (section 3, first
bullet), so the JIT suites should pass `-XX:-AOTReplayTraining` until profiles are wanted.

### 4.4 How the matrix would use it [I]

- *Where.* The build job already exports one classpath and one `jvm.opts` per module and archives
  every entry at its absolute path. A step after "Archive the classpath" would jar each class
  directory (or the bins would run from jars), run one representative bin per module with
  `-XX:AOTMode=record`, run the create step, and put `sql.aot` and `catalyst.aot` into the same
  tar. One cache per module: sql's and catalyst's classpaths differ, and only appending is allowed.
- *What must hold.* Same JDK build on build and run jobs (`setup-java` with `zulu`/`25` resolves
  per job, so a release between two jobs of one run rejects the cache); same jar sizes and
  mtimes (`tar -xP` keeps mtimes); the same `--add-opens`, `--add-modules` and
  `--enable-native-access` on every JVM; no `-javaagent`; same profile-shape flags. Per-run `-D`
  properties and `--jvm-arg -XX:MaxVectorSize=16` are not among the header checks read.
- *What a mismatch looks like.* The bin runs, slower, with the error line of section 1 in its
  `run.log`. A health check greps for it; `-XX:AOTMode=on` would fail the bin instead, which is
  the wrong failure for a test matrix.
- *What it cannot do.* The cache covers the classes the training bin loaded; other bins load the
  rest as now. It does nothing for emission, Janino, kernel compiles or Spark session start beyond
  class loading.

## 5. What does not transfer [I]

- The JEPs' gains are for applications started thousands of times with one main path. A test JVM
  loads a different suite set per bin; the share of its classes a training bin covers is unknown.
- PetClinic's 33% is of a 4.5 s start that is mostly class loading. A matrix JVM's fixed cost
  also includes the Spark session, the first queries' Janino and Varka compiles and JIT warm-up,
  which a class cache does not touch. No committed number for that fixed cost was found in the
  repository.
- JEP 515's 19% is on a 90 ms program with 30 hot methods; it says nothing about a test JVM.

## 6. A first experiment

One `workflow_dispatch` job on an `ubuntu-latest` runner (PR verification belongs on CI), sql
module only, no product change:

1. Jar every class directory on the exported classpath and rewrite the classpath; run one bin
   without a cache to show the jarred classpath passes (resource lookups from jars, not dirs).
2. Training: that bin with `-XX:AOTMode=record -XX:AOTConfiguration=sql.aotconf -Xlog:aot`, then
   `-XX:AOTMode=create -XX:AOTCache=sql.aot`. Record the create log's lines on the full module
   graph and the `.aot` size, and the count of `-Xlog:class+load` lines of the training run.
3. Control for 4.1: the same on one catalyst suite with `--add-modules=jdk.incubator.vector`
   removed (a suite that does not touch the vector API), to show what the module costs.
4. Production: five other sql bins, each three times in three arms: no cache; cache with
   `-XX:+UnlockDiagnosticVMOptions -XX:-AOTReplayTraining`; cache as is. Report per JVM the time
   to the first test's start (ScalaTest's first event in `run.log`) and the bin's wall time, the
   line "Using AOT-linked classes" from `-Xlog:aot`, and any cache error line.
5. Run the five JIT suites of 4.3 in the first two arms and compare their pass/fail and
   `-XX:+PrintTieredEvents` tier counts.

Decision: worth a row only if (time saved per JVM) x 1500 clearly exceeds the training, create and
upload time added to the build job; otherwise park the idea until a JDK archives the module graph
with an incubator module, or until the Vector API leaves incubation.

## For the rows

- **No row now; one experiment to approve (section 6).** The expected outcome is a parsed-class
  cache only, so the ceiling is AppCDS-level, and the run decides whether that is worth jarring
  the classpath.
- **If it pays:** a row for the build job (jar the class dirs, train and create per module, ship
  the caches in the classpath tar) and a row for the bins (`-XX:AOTCache`, a `run.log` check for
  the cache error line, `-XX:-AOTReplayTraining` on all bins so the JIT suites keep today's
  policy).
- **Not for the JIT suites:** profiles (JEP 515) stay off for them even when a later JDK allows
  them; their subject is the untrained JIT.
- **Scope item, not a row:** recheck section 4.1 on each JDK upgrade (the module bootstrap and the
  JEP's `--add-opens` rule are the two things that could change the answer).
