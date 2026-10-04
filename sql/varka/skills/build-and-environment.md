# Build and environment

Getting a build, a JDK and a machine into the state the rest of these lessons assume.

One of Varka's lesson files; the index over all of them is
[`SKILLS.md`](../../../SKILLS.md) at the repository root, which is generated from
these files by `dev/varka_toc.py`.

## Classpath Shadowing (the stub trap)

- Spark modules declare `test->test` project dependencies (e.g. `sql/core` on
  `sql/catalyst`). That puts the *compiled test classes directory* of the dependency
  on the dependent module's test classpath, ahead of jars in `~/.m2`.
- A test-only source that redefines a class at the same FQCN as a dependency silently
  shadows the real jar: the code "runs", produces no exception, and reinstalling the
  jar changes nothing.
- Varka kept a no-op stub `DateVectorOps` in `sql/catalyst/src/test/java` (VARKA-5) so
  catalyst tests could resolve the kernel owner FQCN without the engine jar. It
  shadowed the real kernel on the sql/core test classpath and made the kernel path
  appear to write nothing.
- Fix used: delete the stub, add the engine jar as a test-scope dep in the module's
  `pom.xml` (mirroring sql/core), delete the stale compiled class under `target/`.
- Detection: instrument the layer you *think* is running. If the debug prints never
  appear, you are executing a different class than the source you edited. Look for
  stale copies: `find target -name '*.class'`, search every jar/dir on the classpath
  for the FQCN.

## Build Gotchas

- The engine module is a reactor module since the sbt wiring change: sbt builds it
  in-tree and puts its jar on the catalyst/sql test classpaths itself
  (`VarkaEngine`/`VarkaEngineDependency` in `project/SparkBuild.scala`), so no manual
  install step remains. Maven still builds it standalone
  (`./build/mvn -f sql/varka/engine/pom.xml test`; `mvn` is not on `PATH`, use
  `./build/mvn`), which is how the engine-only suites and JMH run.
  (Earlier revisions of this file described a `~/.m2` install cycle from before the
  reactor change.)
- scalastyle requires a trailing newline at EOF ("File must end with newline
  character") and rejects `throw new XxxError` via the `throwerror` rule. For a
  deliberate `NoClassDefFoundError` test hook, wrap the throw in
  `// scalastyle:off throwerror` / `// scalastyle:on throwerror`.
- **Java in a non-core module must not pass a Guava type to a `core` API - and only
  Maven can tell you.** `core/pom.xml` relocates `com.google.common` to
  `org.sparkproject.guava` when it shades, so in the shaded jar the signature reads
  `NonFateSharingCache(org.sparkproject.guava.cache.Cache)`. Scala never notices,
  because scalac resolves the symbol from the Scala pickle, which the shade plugin
  does not rewrite; javac reads the relocated descriptor and fails with
  `cannot infer type arguments`. So the same call compiles from Scala and not from
  Java, and SBT - which does not shade - hides it from both: the only gate that sees
  it is the "Java 25 build with Maven" CI job. Upstream hit this too (SPARK-44064
  added a Guava-free `NonFateSharingCache.apply` overload precisely "to avoid non-core
  modules Maven test failures caused by using shaded core module"), and the two other
  non-core users, `CodeGenerator` and `ProtobufUtils`, both take Guava-free overloads.
  Note what this means for the Scala side as well: a Scala call site that passes a
  Guava type compiles against a method that does not exist in the shaded artifact, so
  it is a latent runtime failure rather than a safe alternative. Keep Guava types
  inside the module that owns them; when a `core` utility cannot be reached without
  one, reimplement the few lines locally (VARKA-23 did this for the shape cache's
  single-flight gate). Verifiable in seconds without a Maven run: `javac` the file
  against `~/.m2/.../spark-core_2.13-*.jar` and see it fail.
- **A Java class with an incubator-module type in a field needs `--add-modules` twice
  under Maven, and again only Maven can tell you.** VARKA-24 put `SelectionVectorOps` -
  a `jdk.incubator.vector` kernel - in catalyst's main sources. Adding
  `--add-modules jdk.incubator.vector` to `scala-maven-plugin`'s `javacArgs` compiles
  it, and the build then fails *after* a successful compile with
  `NoClassDefFoundError: jdk/incubator/vector/VectorSpecies`. The reason is zinc's
  API extraction: `sbt.internal.inc.ClassToAPI.structure` calls
  `Class.getDeclaredFields()` on the class file it just wrote, which loads the field
  types **reflectively, in the compiler's own JVM**. So the flag has to go in that
  plugin's `jvmArgs` as well - two blocks, both with `combine.children="append"` so
  the parent pom's own arguments survive. SBT never reaches this because the sbt
  launcher already runs with `--add-modules=jdk.incubator.vector`, which is exactly
  the shape of the Guava trap above: an SBT-green, Maven-red failure on the Varka Java
  surface. Reproducible in seconds without a Maven run - `java -cp
  sql/catalyst/target/scala-2.13/classes` a one-liner that calls
  `getDeclaredFields()` on the class, with and without the flag.

## Build Performance (measured, Aug 2026)

Benchmarked on a ThinkPad P16s Gen 4 (Ryzen AI 9 HX PRO 370, 12c/24t, 96 GB, NVMe).
Numbers are wall-clock for a cold `sql/compile` chain unless noted.

- The ceiling is Scala 2's single-threaded compiler frontend plus Spark's serial module
  graph (core -> catalyst -> sql/core). CPU sits at 350-570% out of a possible 2400%,
  so most of a build is one core running scalac.
- **Background CPU contention was the single largest effect.** The same cold build took
  103.9 s while three runaway browser tabs ate ~1.3 cores, and 89 s once they were gone
  (-14%). It also widened run-to-run spread from +/-1.7% to +/-15%.
- **One-shot `build/sbt` invocations cost ~9.3 s each** in JVM startup and build-definition
  loading. A no-op `sql/compile` is 11.0 s standalone but ~1.7 s as another command in a
  live session.
- **sbt beats Maven** for the same chain: 103.9 s vs 139.5 s, plus real incremental
  compilation. When Maven is required, `MAVEN_ARGS="-T 1C"` builds independent modules in
  parallel (CPU 551% -> 817%); `build/mvn` also ships a 4 GB heap and a 128 MB code cache,
  which `MAVEN_OPTS` can raise.

Tuning knobs that were tested and made **no measurable difference** -- do not re-litigate
these without new evidence:

- `-Ybackend-parallelism` (verified it reached scalac via `show sql/scalacOptions`)
- raising the sbt heap above the 8 GB set in `.sbtopts`
- Zinc's `recompileAllFraction` (0.2 / 0.5 / 1.0 all identical)
- scalac warning analysis: `-Wunused:imports` and the whole `-Wconf` list cost nothing
- JVM transparent huge pages, CPU governor / power profile, AC vs battery
- I/O, swap and filesystem tuning -- a cold build takes 62 major page faults and writes
  ~2 MB/s, so the kernel is not in the path at all
- genjavadoc is already gated to the unidoc config and does not run on normal compiles

Benchmarking method: compare interleaved A/B runs by their minimums. Single-run
comparisons on a contended machine carried a +/-15% noise band, large enough that several
apparent small wins turned out to be noise.

## Environment Facts (verified in this repo)

- `TaskContext.get()` is non-null on the driver in local-mode tests, so kernel
  execution also happens in driver-side eval.
- Test JVMs pass `--enable-native-access=ALL-UNNAMED` (`project/SparkBuild.scala`),
  so `MemorySegment.ofAddress(...).reinterpret(n)` works in tests.
- `ColumnarBatch` and Arrow vectors are not serializable. To feed a batch into a node
  under test, rebuild the batch inside the task from a serializable spec. Nested case
  classes capture the non-serializable test suite via `$outer`; keep spec classes
  top-level.
- `SparkPlan.executeCollect()` goes through `getByteArrayRdd()` and casts rows to
  `UnsafeRow`; row-producing evaluators must emit `UnsafeRow`.

## Extra Sessions on the Shared Context

- For side-by-side engine comparison, build extra sessions on the same context:
  `SparkSession.builder().sparkContext(spark.sparkContext)`. Clear the active and
  default sessions between creations (`SparkSession.clearActiveSession()` /
  `clearDefaultSession()`).
- Sessions have separate catalogs: a temp view cached in one is not visible in
  another; register and cache the data in every session.
- `InMemoryRelation` holds the cache serializer in a process-wide static
  initialized on first use; call `InMemoryRelation.clearSerializer()` in
  `beforeAll` and again in `afterAll` so the choice does not leak to later suites.
- AQE is on by default in the test framework's shared session; disable it
  explicitly on custom sessions, or plans change shape and QueryStage threads leak.

## Building a fastdebug JDK for HotSpot diagnostics

Three questions in milestone 4 could not be answered from a product JVM - why SuperWord
declined a loop, what C2 actually emitted, and which of two compilations a bimodal kernel had
landed on. The flags that answer them (`TraceSuperWord`, `PrintOptoAssembly`, and a
`PrintAssembly` that can actually disassemble) are `develop` flags or need `hsdis`, and neither
ships in a product build. No prebuilt debug JDK exists on `jdk.java.net` for any version, so
building one is the only route. It takes about three and a half minutes on 24 cores.

```
git clone --depth 1 --branch jdk-25-ga https://github.com/openjdk/jdk.git
bash configure --with-debug-level=fastdebug --enable-headless-only \
    --with-hsdis=capstone --with-boot-jdk=$JAVA_HOME \
    --with-jobs=24 --disable-warnings-as-errors
make images build-hsdis
cp build/*/support/hsdis/libhsdis.so build/*/images/jdk/lib/server/
```

Four things that cost time and are not obvious:

* **`--enable-headless-only` does not remove the X11 or cups build dependencies.** It affects
  the runtime, not what configure demands. The full Ubuntu list is still needed: `autoconf`,
  `libx11-dev`, `libxext-dev`, `libxrender-dev`, `libxrandr-dev`, `libxtst-dev`, `libxt-dev`,
  `libcups2-dev`, `libfontconfig1-dev`, `libfreetype-dev`, `libasound2-dev`, and
  `libcapstone-dev` for hsdis.
* **Ubuntu 26.04 ships `uutils coreutils` as `date`, and it breaks the build.** OpenJDK's
  configure decides GNU-ness with `date --version | grep "GNU\|BusyBox"`
  (`make/autoconf/basic_tools.m4`). uutils names itself differently while behaving
  GNU-compatibly, so configure falls back to the BSD `date -u -j -f` form, produces an empty
  `SOURCE_DATE_ISO_8601`, and the build later fails packaging `jrt-fs.jar` with
  `option --date requires an argument`. Passing `--with-source-date=<ISO string>` does not help
  - it is rejected as unparseable by the same broken path. The fix is a shim earlier in `PATH`
  that answers `--version` with a string containing "GNU" and `exec`s `/usr/bin/date` for
  everything else.
* **`make images` does not build hsdis.** It needs the separate `build-hsdis` target, and the
  result is left in `support/hsdis/libhsdis.so`. HotSpot looks for it beside `libjvm.so`, so it
  has to be copied to `images/jdk/lib/server/` - putting it in `images/jdk/lib/` is not enough
  and yields `Loading hsdis library failed` with no further explanation.
* **The flag is `TraceSuperWord`, not `TraceAutoVectorization`**, in JDK 25 - check
  `src/hotspot/share/opto/c2_globals.hpp` rather than trusting a flag name from a newer
  release.

**Never take a number from this JVM.** fastdebug keeps the assertions, so absolute throughput
is not comparable with the product build and nothing measured on it belongs in a committed
results file. It is for reading what C2 did, not how fast it did it.

## `dev/scalastyle` cannot parse an underscore-separated numeral before `->`

`16_384 -> "both arms in L2"` compiles and fails `dev/scalastyle` with
`Expected token RPAREN but got Token(STRING_LITERAL, ...)`, pointing at the
string rather than at the number. Scalastyle parses with scalariform, which is
older than the compiler and mis-lexes the literal here; the same literal alone
on the right of a `=` is accepted, which is why `1_000_000` appears in several
committed benchmarks without trouble.

The fix is to write the digits without separators in that position. The reason
it is worth knowing is the error message: it names a token several characters
away from the cause and says nothing about numerals, so the natural first guess
is an unbalanced paren in the surrounding expression, which sends you rewriting
correct code. `dev/varka_gate.sh --only lint` is a minute, and it is the only
thing that reports this at all - the compiler is happy.

## Python's 100-column rule is a convention here, not a check

Spark's `pyproject.toml` sets `line-length = 100`, and it is natural to read that
as a linter rule. It is not one. `[tool.ruff.lint]` only carries
`extend-select = ["I", "G010", "RUF001", "RUF002", "RUF003", "RUF100"]`, which
extends ruff's default `E4, E7, E9, F`; `line-too-long` is `E501`, in the `E5`
family, and is therefore never selected. `ruff check --show-settings` lists the
66 enabled rules and it is not among them. `line-length` still matters, because
it is what `ruff format` wraps to - so the formatter, not the checker, is what
holds Python lines near 100, and the formatter cannot split a string literal or a
comment.

Two consequences worth knowing before editing Python here. A long *code* line is
caught, because `ruff format --check` would rewrite it, which is why
`dev/lint-python` runs both halves. A long string or a long comment is caught by
nothing, and no edit can shorten a single long token anyway.

Even with `E501` forced on, ruff exempts a line of fewer than two
whitespace-separated chunks. Measured on the pinned ruff 0.14.8 over a six-shape
probe: a 112-character line holding one quoted token and a 110-character one-word
comment pass; two-chunk, three-chunk and many-chunk lines are reported. That is
why `dev/varka_precommit.sh` grants the same exemption for `.py` and no other
language - scalastyle and checkstyle have no equivalent, so a long Scala string
literal is a real finding.

The practical upshot: when the column scan fires on a Python line you cannot wrap,
check what ruff says before rewriting anything. It probably says nothing.

## A git hook's nested git commands act on the repository being committed to

`git commit` runs a hook with `GIT_DIR` and `GIT_INDEX_FILE` exported, both
absolute. Any git command the hook runs - or that a script the hook invokes runs,
however deep, and whatever directory it has changed to - inherits them and
therefore operates on the repository whose commit is in progress. `cd` does not
help, and neither does creating a repository of your own: `git init` in a fresh
directory succeeds while `git add` and `git commit` after it write to the outer
repository's index and refs.

This was found by writing a self-test for `dev/varka_precommit.sh` that builds a
throwaway repository to check the hook's diff scoping. Run as a hook, the fixture
committed itself onto the task branch - the commit carried the staged changes and
the fixture file - and the real commit then failed with `cannot lock ref 'HEAD':
is at <fixture commit> but expected <branch tip>`. `git reset --mixed <tip>`
restores it, and nothing is lost as long as it has not been pushed.

The fix is to strip the environment for every nested command:

    bare() { env -u GIT_DIR -u GIT_WORK_TREE -u GIT_INDEX_FILE -u GIT_PREFIX \
      -u GIT_COMMON_DIR -u GIT_OBJECT_DIRECTORY \
      -u GIT_ALTERNATE_OBJECT_DIRECTORIES "$@"; }

Two things make this worth remembering rather than rediscovering. The failure is
silent until the outer commit fails, and it fails with a message about ref
locking that says nothing about where the other commit came from. And it does not
reproduce when `GIT_DIR` is set by hand to a relative path such as `.git`, which
re-resolves against whatever directory the fixture is in - so a test of the fix
must export the absolute path a real hook receives.

## The Maven build CI runs takes about fifteen minutes here, and it is the only place three failures appear

Measured on 22 September 2026 on the development laptop, 24 cores, because the
project had quoted this build's cost for months without ever timing it:
`./build/mvn -DskipTests` with the full profile set CI uses (`yarn`,
`kubernetes`, `volcano`, `hive`, `hive-thriftserver`, `hadoop-cloud`,
`jvm-profiler`, `spark-ganglia-lgpl`, `kinesis-asl`, `credential-aws`) and
`clean install` took **13 minutes**, and `build/sbt catalyst/doc` one more.

Fifteen minutes is cheap enough that it should be run before a push that
touches module boundaries, because it is the only local check for three
failures sbt cannot produce, each of which otherwise costs a CI round trip:

- a Guava type crossing a module boundary, which Maven's shading relocates and
  sbt does not;
- the `--add-modules` that zinc needs *reflectively*, after a successful
  compile, for a class holding a `jdk.incubator.vector` type in a field - the
  one that fails with `NoClassDefFoundError` when the compile said nothing;
- scaladoc's cyclic reference on `java.lang.classfile`, which `catalyst/doc`
  reproduces in about a minute on its own and is worth running alone when the
  change is Scala touching emitter-adjacent code.

The full Maven *test* run is a different matter and is not worth an idle night:
CI shards it across about ten parallel jobs of one to two hours each, so a
single machine is looking at six to twelve hours, nearly all of it exercising
code Varka never touches.

## A committed file outside every module runs the whole CI matrix

CI decides which jobs a pull request needs from `dev/sparktestsupport/modules.py`: each changed
file is mapped to a module, and a file that matches no module counts as every module changed.
So one unmapped file in a pull request switches on every module-gated job, including ones that
have nothing to do with Varka - the Kubernetes integration tests and the OIDC end-to-end job
built Minikube clusters for #520 and #525 because `sql/varka/emit_cost_audit.json` was new and
in no module's list. The benchmark results, the bytes oracle and the coverage table had been
mapped one by one as each fell through the same hole.

The rule is that every committed file Varka adds gets a module, or a place in the ignored
patterns when no check reads it, in the same change that adds it. The check is one command:

    git ls-files sql/varka dev docs SKILLS.md | python3 -c "
    import sys; sys.path.insert(0, 'dev'); from sparktestsupport import modules
    for f in (l.strip() for l in sys.stdin):
        if f and not modules.is_ignored_file(f) and not any(
            m.contains_file(f) for m in modules.all_modules if m is not modules.root): print(f)"

A file this prints is one the next pull request touching it will pay the full matrix for. The
jobs that can never be Varka's are also gated on their own source directories in
`build_and_test.yml`, as SparkR's, buf's and the UI job's are, so the fallback cannot reach
them however the map drifts.

## An sbt-forked JVM hides its main class in an arg file

- A benchmark or a test JVM that sbt forks shows in `ps` as `java @/tmp/sbt-args<digits>.tmp`:
  the whole command line, main class included, is inside that file. On 3 October 2026 a
  `pkill` by class name killed the queue scripts around a running `VarkaWideKernelBenchmark`
  and left the JVM itself running for twenty minutes at full load, so the regeneration queued
  behind it never started (it refuses a busy machine). A `pgrep`-based wait for the same class
  name would have returned at once for the same reason.
- To find or stop such a JVM, match the process's working directory
  (`readlink /proc/<pid>/cwd`, the worktree's module) or the arg file's content
  (`grep -l <Class> /tmp/sbt-args*.tmp`), and stop the sbt launcher too; then read the load
  average before trusting that the machine is idle.
