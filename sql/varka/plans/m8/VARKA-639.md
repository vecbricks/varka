# VARKA-639: Varka's sbt jobs restore their dependency caches and fall back to a Maven Central mirror

Row 639 of `PLAN.md`. Added on 22 November 2026 on the owner's question "why don't we use mirrors
or caches?".

## 1. Where this came from

#1625's Build failed in the job "Varka assembly gate" (`varka-asm` in `build_and_test.yml`; fork
run 45068179064, attempt 1, job 137354292759). The rerun passed. The log of the failed attempt:

```
[info] [launcher] getting org.scala-sbt sbt 1.13.0  (this may take some time)...
[error] [launcher] ...CantDownloadModule: Error downloading org.scala-sbt:sbt:1.13.0
  not found: /home/runner/.ivy2/local/org.scala-sbt/sbt/1.13.0/ivys/ivy.xml
  download error: ... HTTP response code: 429 for URL: https://repo1.maven.org/maven2/org/scala-sbt/sbt/1.13.0/sbt-1.13.0.pom
  download error: ... HTTP response code: 429 for URL: https://repo1.maven.org/... while downloading https://repo.scala-sbt.org/scalasbt/maven-releases/...
  not found: https://repo.scala-sbt.org/scalasbt/maven-snapshots/...
  not found: https://repo.typesafe.com/typesafe/ivy-releases/...
  not found: https://repo.scala-sbt.org/scalasbt/ivy-snapshots/...
[error] [launcher] could not retrieve sbt 1.13.0
```

Two gaps made this possible:

1. **No cache in that job.** The assembly gate restored no `~/.sbt` and no `~/.cache/coursier`,
   so every run downloaded sbt, Scala and catalyst's dependencies.
2. **The launcher has no mirror.** `build/sbt-launch-lib.bash` already downloads
   `sbt-launch-1.13.0.jar` from Google's mirror of Maven Central, and `project/SparkBuild.scala`
   already puts that mirror first in the projects' resolvers. But the launcher then resolves sbt
   itself from the repositories in the jar's `sbt/sbt.boot.properties`: Maven Central and four
   sbt hosts, and the first of those redirects to Maven Central too. So one 429 from Maven Central
   fails the job.

The log also shows that the launcher tries every repository in turn after an error. So a mirror
added to the list would have been tried.

## 2. The admission check, done: what each job restores

Every job of a Varka workflow (`varka-*.yml`) and every Varka job of `build_and_test.yml`
(`varka-*`) that runs sbt or Maven, read at `b4cbee926d1`. The table uses these terms:

* **build-** is the entry with key `build-<hash>` and paths `build/apache-maven-*`, `build/*.jar`
  and `~/.sbt`. It also holds the launcher's boot directory, `~/.sbt/boot`.
* **cs** is a `~/.cache/coursier` entry.
* **m2** is `~/.m2/repository`.
* `~/.ivy2/cache` is not used: sbt 1.13 resolves through Coursier, and nothing in `project/` turns
  that off.

| workflow | job | runs | `~/.sbt` | coursier | `~/.m2` | before this change |
|---|---|---|---|---|---|---|
| build_and_test | varka-asm | sbt | - | - | n/a | **nothing restored** |
| build_and_test | varka-matrix-classes | sbt | build- (restore) | cs sql (restore) | n/a | ok |
| build_and_test | varka-scoped | sbt | build- (restore, save by rule) | cs per module (same) | n/a | ok |
| build_and_test | varka-bench | Maven | n/a | n/a | varka-bench-m2 (cache) | ok |
| build_and_test | varka-engine and the install action | Maven | n/a | n/a | varka-engine-m2-v1 (restore, save) | ok |
| varka-fuzz | fuzz, series | sbt | build- (cache) | varka-fuzz-coursier (cache) | engine action | ok |
| varka-mutation | shard | sbt | build- (cache) | varka-mutation-coursier (cache) | engine action | ok |
| varka-option-matrix | build | sbt | build- (cache) | varka-option-matrix-coursier (cache) | engine action | ok |
| varka-option-matrix | bins | java only | - | - | - | needs none |
| varka-sweeps | build | sbt | build- (cache) | varka-sweeps-coursier (cache) | engine action | ok |
| varka-sweeps | sweep, avx2-128 | java only | - | - | - | needs none |
| varka-tier-split | build | sbt | build- (cache) | varka-option-matrix-coursier (cache) | engine action | ok |
| varka-tier-split | configurations | java only | - | - | - | needs none |
| varka-width-audit | audit | sbt | own entry with `~/.ivy2` | same entry | n/a | ok |
| varka-surface-benchmark | build | sbt and Maven | varka-surface (cache) | same entry | - | **no `~/.m2`** for its two Maven builds |
| varka-surface-benchmark | measure | jars only | - | - | - | needs none |
| varka-master, varka-weekly-matrix | run-build | `build_and_test.yml` | as above | as above | as above | - |
| benchmark (Spark's file) | tpcds-1g-gen, benchmark, pair | sbt | `setup-build-env` (cache) | `setup-build-env` (restore) | - | ok; not Varka-named, not changed |

The other Varka workflows (canary, demo, silent-giveups, fork-sync, issues, pr-update,
bench-cpu-retry) run no sbt and no Maven.

Two jobs miss a cache:

* the assembly gate misses both of its caches;
* the surface benchmark's build misses the Maven one.

No job gives the launcher a mirror.

Checked for the claim in 3.2:

* `project/plugins.sbt` pins every plugin and library by an exact version;
* `project/build.properties` pins `sbt.version=1.13.0`.

Cache keys:

* Upstream Spark's jobs use the keys `build-<os>-<hash>` with the restore prefix `build-<os>-`.
  They never restore a Varka `build-<hash>` entry.
* No other workflow uses Varka's keys.

## 3. The design

### 3.1 The caches

* **The assembly gate restores, never saves**, the same entries as `varka-matrix-classes`:
  * `build-<hash>` with the restore prefix `build-`;
  * `varka-scoped-coursier-catalyst-<hash>` with its prefix, since the gate runs a catalyst
    suite.

  The fork's master Build and the Varka suites' jobs save both entries under the rule
  `varka-scoped` already has (VARKA-525). A miss downloads, as before.
* **The surface benchmark's build restores two `~/.m2` entries, never saving.** Both use the path
  as the jobs that save them write it, so the cache versions match:
  * `varka-bench-m2-<os>-<hash>` comes from the Build's bench-driver job;
  * `varka-engine-m2-v1-<os>-build-<hash>` comes from the engine install.

### 3.2 The mirror

`sql/varka/ci/sbt-repositories` holds the launcher's default list, copied from
`sbt-launch-1.13.0.jar`, with one line added second, after `maven-central`:

```
gcs-maven-central-mirror: https://maven-central.storage-download.googleapis.com/maven2/
```

Each of the 11 Varka jobs that run sbt copies it to `~/.sbt/repositories` after its cache
restores and before its first sbt call. The copy comes after the restores, so a copy inside a
restored `~/.sbt` is replaced. `~/.sbt/repositories` is the launcher's default
`sbt.repository.config`, so every sbt call in the job reads it, including the calls inside
`dev/varka_*.sh`, with no argument changed.

What the file reaches:

* The launcher's download of sbt and Scala.
* The meta-build's plugins: sbt prepends the launcher's repositories to a build's default
  resolvers.
* Not Spark's projects. `SparkBuild.scala` sets their `externalResolvers` itself, mirror first.

**What resolves cannot change.**

* The mirror serves the same files as Maven Central. It is consulted only after Maven Central has
  failed for a file.
* Every version that is resolved is pinned (section 2).
* `sbt.override.build.repos` stays off, so no build's own resolver list is replaced.
* The checks are unchanged. The launcher checks `sha1,md5` and Coursier checks SHA-1, each
  against the checksum file of the repository that served the artifact.

**Mechanisms not taken, and why:**

* `MAVEN_MIRROR_URL`, the hook in `build/sbt-launch-lib.bash`, writes a list of `local` and the
  mirror only. That drops Maven Central. It also adds a resolver in `SparkBuild.scala` and turns
  on a profile in `pom.xml`.
* Editing `build/sbt` or `build/sbt-launch-lib.bash` would change Spark's files. Both are also
  hashed into the `build-` key, so the edit would start every job's sbt cache cold.
* `SBT_OPTS` replaces `build/sbt`'s default JVM options, `-Xss64m` among them.
* `JAVA_TOOL_OPTIONS` prints a line into the output of every JVM, and Varka's scripts parse that
  output.
* `sbt.override.build.repos` would replace `SparkBuild.scala`'s resolver list.
* `COURSIER_REPOSITORIES` is read by the Coursier command line, not by the launcher.

### 3.3 What is deliberately unchanged

* **Spark's jobs and `benchmark.yml`.** They are not Varka's. `benchmark.yml`'s jobs restore both
  caches through `setup-build-env`.
* **A Maven mirror for Varka's Maven builds.**
  * The Varka poms have no parent and no repositories, so they resolve from Maven Central alone.
    But each such job restores `~/.m2`, and the engine install retries a failed resolution.
  * A repository in a pom would change every `**/pom.xml` hash and start every cache cold.
  * A `settings.xml` profile puts its repositories before Maven Central, not after.

  This is left for a row of its own, if a 429 ever fails one of these jobs.

## 4. Files

| file | what |
|---|---|
| `sql/varka/ci/sbt-repositories` | the launcher's repositories, with the mirror second |
| `.github/workflows/build_and_test.yml` | the assembly gate's two restores; the copy step in `varka-asm`, `varka-matrix-classes`, `varka-scoped` |
| `.github/workflows/varka-{fuzz,mutation,option-matrix,sweeps,tier-split,width-audit}.yml` | the copy step in each sbt job |
| `.github/workflows/varka-surface-benchmark.yml` | the two `~/.m2` restores and the copy step in the build |

## 5. Tests, and what each is for

* **YAML and actionlint.** Every changed workflow parses with PyYAML. `actionlint` reports the
  same 62 findings before and after the change, all of them in lines this change does not touch.
* **The file as written.** `build/sbt -Dsbt.repository.config=sql/varka/ci/sbt-repositories about`
  runs with a scratch boot directory, Ivy home and Coursier cache. It shows that the launcher
  parses the file and boots sbt from it.
* **The fallback.** The same run with `maven-central` replaced by a dead URL
  (`http://127.0.0.1:9/maven2/`) has to boot sbt 1.13.0 and load the build from the mirror.
* **After the merge.** The first Build's assembly gate shows both restores hitting.

The results of these runs are in section 9.

## 6. The measurement

None beyond section 5: the change moves no kernel or suite time. The assembly gate gains two
cache restores and drops the downloads.

## 7. Risks

1. **A cache entry can grow.** A job that saves `~/.sbt` now also saves the 2 KB file. Only Varka
   jobs restore those entries, and each one writes the file again.
2. **The mirror can lag behind Maven Central.** A version published minutes ago could be missing
   from it. It is consulted only after Maven Central has failed, and every version is pinned.

## 8. Sequencing

One commit: the file, the steps, and this record.

## 9. Outcome

Filled in below as the checks land.
