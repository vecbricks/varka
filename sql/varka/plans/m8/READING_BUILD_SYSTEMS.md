# Reading for row 520: what makes a restored incremental compile sound

Read on 29 October 2026 at the owner's request, for row 520 (each Varka job restores the newest
sbt compile state any branch saved and lets Zinc compile what differs), with lessons for rows 525
(a shared dependency cache) and 528 (a plan cache keyed by SQL text, columns and settings). The
extended paper (JFP 2020) was read in full from Cambridge's open-access PDF; the ICFP 2018 paper
from Microsoft Research's copy, for its section structure only, since the JFP version contains it.
Zinc was read from its source at tag `v1.12.1`, the version sbt 1.13.0 boots (the jars under
`~/.sbt/boot/scala-2.12.21/org.scala-sbt/sbt/1.13.0/`), and sbt from its source at tag `v1.13.0`,
both through the GitHub API; the issues from sbt/zinc's tracker. Both papers are CC BY 4.0, which
allows a transcription into `sql/varka/papers/`; this reading does not write one. **[R]** means
read; **[I]** inferred.

| what | source | licence of the copy |
| :--- | :--- | :--- |
| Mokhov, Mitchell, Peyton Jones, JFP 30 e11 (2020) [1] | Cambridge open access | CC BY 4.0 |
| Mokhov, Mitchell, Peyton Jones, ICFP 2018 (PACMPL 2, 79) [2] | Microsoft Research | CC BY 4.0 |
| sbt docs, "Understanding Incremental Recompilation" (for 0.13) [3] | scala-sbt.org | not kept |
| Zinc source at tag `v1.12.1` [4] | github.com/sbt/zinc | Apache-2.0 |
| sbt source at tag `v1.13.0` [5] | github.com/sbt/sbt | Apache-2.0 |
| Zinc issues 145, 147, 226, 227, 249, 1171, 1553, 1811; PR 1721 [6] | sbt/zinc | quoted |

1. "Build systems a la carte: Theory and practice", DOI 10.1017/S0956796820000088;
   `https://www.cambridge.org/core/services/aop-cambridge-core/content/view/`
   `097CE52C750E69BD16B78C318754C7A4/S0956796820000088a.pdf`
2. "Build Systems a la Carte",
   `https://www.microsoft.com/en-us/research/uploads/prod/2018/03/build-systems.pdf`
3. `https://www.scala-sbt.org/1.x/docs/Understanding-Recompilation.html`
4. `Stamp.scala`, `IncrementalCommon.scala`, `IncrementalCompilerImpl.scala`,
   `MiniSetupUtil.scala`, `LookupImpl.scala`, `caching/ClasspathCache.scala`,
   `MemberRefInvalidator.scala`, `NameHashing.scala`, `incremental.contra`
5. `main/src/main/scala/sbt/Defaults.scala`, `main/src/main/scala/sbt/internal/ClasspathImpl.scala`,
   `main-actions/src/main/scala/sbt/Sync.scala`
6. `https://github.com/sbt/zinc/issues/<number>`

## 1. Build systems a la carte: the vocabulary for row 520's argument

**What it is [R].** A build system is split into a *scheduler* (the order keys are brought up to
date: topological, restarting or suspending) and a *rebuilder* (whether a key needs work at all).
Four rebuilders (section 5): a *dirty bit* (Make's timestamps, Excel's dirty cells); *verifying
traces*, which record for a key the hashes of the dependencies it used and of its result, and skip
the key when every recorded hash still matches (Ninja, Shake); *constructive traces*, which also
store the result, so a key can be produced from the cache without running its task, which is what
makes cloud builds possible (CloudBuild, Bazel); *deep constructive traces*, keyed by the terminal
inputs only (Buck, Nix). Correctness (section 3.6): after a build, every non-input key equals what
its task computes from the final store. *Early cutoff* (section 2.3): a rebuilt key whose value
did not change stops its dependents from rebuilding; every trace kind supports it except the deep
one, which never looks at intermediate values. Section 8.3 names three impurities: untracked
dependencies (the example is a C compile that does not record the version of gcc),
non-determinism and volatility. Section 8.4 shows the "Frankenbuild": a deep constructive trace
plus a non-deterministic task yields a target inconsistent with its own dependency. Section 7.4,
on moving Shake builds to a shared cache, lists what broke: irrelevant differences (two users with
gcc on different paths share nothing), undeclared products, and missing dependencies, which matter
more in a cloud build because "with a cloud build they might only be built once ever". Section 8.8
(self-tracking): a system whose tasks are code in a full language cannot compare tasks for
equality, so the only safe assumption is that a change to the build changes every task.

**What Varka takes from it.**

1. *Zinc is a verifying-trace rebuilder with early cutoff [I, from the sources in section 2].*
   Its analysis keeps, per source, one record: the source's hash, the hashes of the products it
   wrote, the hashes of the libraries its classes used, the API hash and name hashes of each class,
   and the dependency relations between classes. The products themselves live in the class
   directories, not in a store addressed by hash, and Zinc keeps one record per source rather than
   several. That is the paper's verifying trace, not a constructive one. The early cutoff is at the
   level of a class's API: a recompiled source whose API hash (or, with name hashing, whose names'
   hashes) did not change invalidates nothing downstream. The scheduler matches none of the three
   exactly: Zinc compiles an invalidated set, diffs the new APIs, invalidates further, and repeats,
   taking the whole transitive closure after `transitiveStep` (3) cycles; closest to restarting.
2. *A trace from another branch is sound under that model, on three premises [I].* A verifying
   trace is checked against the store as it is now; where it came from does not enter the check.
   Restoring branch B's analysis and class directories on branch A is, to Zinc, the same as a
   local build of B followed by a checkout of A. It is sound when (a) the trace and the products
   come from one run, which they do, since one `tar` of `target/scala-2.13` takes both after the
   job's compile; (b) every input that moves the output is in the trace or in the setup Zinc
   compares (section 2 lists what is); and (c) Zinc's own invalidation does not miss a dependency.
   (c) is not specific to row 520: a local incremental compile has the same holes. What row 520
   changes is the distance between the trace and the tree, which makes more of the holes reachable
   in one step, and the persistence of the result.
3. *A stale class travels [I].* Row 520 saves each run's incrementally compiled state for the next
   run of any branch. A class left stale by an under-invalidation is therefore not rebuilt by the
   next clean checkout, as it would be on a laptop after `clean`: it is copied into every later
   state until a change to its own source recompiles it. That is section 7.4's "built once ever",
   and the reason row 520 needs a check against a clean compile (section "For the rows").
4. *Non-determinism is harmless here [R, section 8.3 and 8.4].* Only deep constructive traces are
   made wrong by a non-deterministic task. Zinc verifies against the products actually on disk, so
   a scalac that emits slightly different bytes on two runs costs at most a recompile.

**What does not transfer [I].** The paper's tasks have declared or recorded dependencies; Zinc's
dependencies are extracted from the compiler (scalac's trees, javac's class files) and are only as
complete as that extraction, which is where its known holes are (section 2).

## 2. Zinc: what the restored state must match

**What it is [R].** sbt's incremental compiler. Since Zinc 1.0, dependencies are tracked between
classes; name hashing (on by default since sbt 0.13.6, per the docs page) records, per class, a
hash for each group of public definitions sharing a simple name (`NameHashing.scala`), and the
names each class uses, so a change to one member invalidates only the dependents that use that
name. Inheritance dependencies bypass name hashing and are followed transitively. The docs page
names the limits of the approach: implicits, sealed hierarchies in pattern matches, inferred
return types.

**How each kind of file is stamped [R].** In sbt 1.13.0, sources are stamped by content hash:
`inputFileStamper :== sbt.nio.FileStamper.Hash` (`Defaults.scala:166`), and `reusableStamper`
(`Defaults.scala:459-484`) reads source stamps from those hash caches. Libraries and products are
stamped by `Stamps.uncachedStamps`, which uses `Stamper.forHashInRootPaths`, a FarmHash of the
file or of the jar holding it (`Stamp.scala:222-236`, `287-292`). `timeWrapBinaryStamps` wraps
library and product stamps with a modification-time check (`Stamp.scala:240-261`, `425-447`), but
its cache is an in-memory `ConcurrentHashMap`: the time decides only whether to hash the file again
within one sbt process; the stamp stored in the analysis and compared is the hash. The classpath
hash in the compile setup is the same (`ClasspathCache.scala`): FarmHash of each jar, memoised by
modification time and size in memory; a directory hashes to a constant. File paths are written
relative to `rootPaths`: `BASE`, `SBT_BOOT`, `CSR_CACHE`, `IVY_HOME`, `JAVA_HOME`
(`Defaults.scala:414-428`), so the Coursier cache's location does not enter the analysis.

So the Coursier cache's jar times, which differ on every runner's restore, cost one hashing pass
over the classpath's jars and nothing else; a jar with the same bytes has the same stamp [R, from
the code above; the pass's duration on CI is not measured].

**What forces a full recompile [R].** `IncrementalCompilerImpl.prevAnalysis` compares the previous
setup with the current one (`MiniSetupUtil.equivCompileSetup`):

- different scalac or javac options (less those matched by `incOptions.ignoredScalacOptions`), a
  different compiler version or a different compile order: every source is pruned, so every
  product is deleted and compiled again;
- a different `extra` pair: the products are deleted and the analysis replaced by an empty one.
  sbt's default `extraIncOptions` is `JAVA_CLASS_VERSION -> java.class.version`
  (`Defaults.scala:188`), so a state compiled under another major JDK compiles clean;
- switching to compiling into a jar;
- after invalidation, more than `recompileAllFraction` (default 0.5) of the sources invalidated:
  everything is compiled in one step (`IncrementalCommon.scala:276-286`).

A changed library is not a full recompile: when the classpath hash differs, each library class the
analysis recorded is looked up on the new classpath and its jar's hash compared
(`IncrementalCommon.isLibraryModified`); the classes that used a changed one are invalidated. A
product whose hash differs from the analysis, or that is missing, invalidates its source
(`detectInitialChanges`). Sources the analysis knows and the tree lacks are removed with their
products.

**Spark's build against those rules.**

- `Compile / scalacOptions` carries `-sourcepath` with the absolute `ThisBuild / baseDirectory`
  (`project/SparkBuild.scala:371-374`) [R]. Options are compared as stored, so the state is tied to
  the checkout path, `/home/runner/work/<repo>/<repo>` on a hosted runner, which is the same for
  every run of one repository [I]. Whether Zinc's analysis store maps paths inside options through
  `rootPaths` was not checked.
- `javacOptions` carry `--release` from the pom's `java.version` [R]; the Varka jobs' JDK comes
  from the calling workflow, and `dev/varka_compile_state.py` reads runs of `build_main.yml` only,
  which pins `java: 25` [R]. That restriction is what keeps `JAVA_CLASS_VERSION` equal; a state
  found in a Java 21 workflow's run would compile clean [I].
- `Compile / exportJars := true` (`SparkBuild.scala:308`) puts sibling modules on the classpath as
  jars; sbt attaches each jar's analysis (`ClasspathImpl.analyzed`, `ClasspathImpl.scala:317`), so
  a change in catalyst reaches sql as API changes of catalyst's classes, with name hashing, not as
  a changed jar invalidating every user of it [R].
- Resources: `copyResources` keeps its sync cache in `crossTarget/<config>sync`, inside
  `target/scala-2.13`, so it is restored, and `Sync` deletes the targets the previous mapping had
  and the current one lacks (`Sync.scala:66-82`): a resource removed on this branch is removed from
  the class directory [R]. `previousCompile` reads the analysis file in `crossTarget/zinc/`, not
  anything under `target/streams`, which the archive leaves out [R].
- Generated sources (ANTLR, protobuf) are stamped by hash, so a regenerated identical file is
  unchanged to Zinc: early cutoff for generators [I, from the managed-source hash cache].

**Known under-invalidation, as it bears on Spark's mixed Scala and Java [R, issues].**

- Name hashing applies to Scala dependents only: `MemberRefInvalidator` logs "Name hashing
  optimization doesn't apply to non-Scala dependency" and invalidates every Java dependent of a
  changed class. Java over-invalidates; it does not under-invalidate on that path.
- #145: a Java class that depends on a separately compiled Java class only through a constant used
  as an annotation value or a `switch` case label keeps no reference in its class file, so the
  dependency is not recorded. Fixed by #1721, merged on 25 June 2026 into `develop` (Zinc 2.x); the
  merge commit and `v1.12.1` have diverged and a commit search found no 1.x backport, so sbt 1.13.0
  has the hole. In Spark this is a Java constant in one module used that way in another.
- #147 (open): a class used by Java only in a generic position can be erased from the class file
  and its dependency lost.
- #226 (open): a name shadowed between two wildcard imports; adding the shadowing class does not
  recompile the importer.
- #227: Scala `final val` constants inlined before Zinc saw them; fixed by Zinc PR 985, the
  2.12 bridges patched in 997 and 1003, so Spark's 2.13 build has the fix [I].
- #1553 (open): a Java and a Scala class whose names differ only in case break analysis.
- #1811 (open): a Java interface changed to an abstract class, implemented in Scala in a cycle,
  fails the compile instead of recompiling the implementor: loud, not stale.
- Macros: a change to a macro definition invalidates its users unconditionally
  (`recompileOnMacroDef`, `MemberRefInvalidator.scala:66`); #1171 (closed) was a user of a macro
  library not recompiled when the macro's input changed; #249 (open) is the meta issue. Spark
  defines few macros [I].

**What Varka takes from it [I].** The row's correctness argument holds as written for sources,
products and libraries, because all three are compared by content hash and the path roots are
mapped. Its premises should be stated: one repository (the checkout path is in the options), one
JDK major (in `extra`), the same Scala version and options. What can go wrong is the known holes,
reached more often across a branch distance and kept by the re-save.

**What does not transfer.** Zinc's settings for speed (pipelining, `useOptimizedSealed`) are not
row 520's concern; nothing here asks to change them.

## For the rows

- **Row 520.** The done-when's planted dependency change covers the common path. Add:
  (a) an *equivalence check*: one commit compiled twice, from a state another branch saved and
  clean, with the two class directories compared by a list of per-file hashes; any file present in
  one only or differing is a stale product, and the run fails. Once on the row's pull request, then
  on a schedule (on the fork's master Build once row 525 builds it). (b) A *visible fallback*: the
  job prints how many sources sbt compiled and, when it compiled all of them, the reason (a setup
  mismatch or `recompileAllFraction`), so a state that silently stopped helping shows in the log.
  (c) The known holes planted as documented expected misses, so the boundary is measured rather
  than assumed: a Java constant in an annotation value across modules (#145), a wildcard-import
  shadowing (#226). (d) A *hop bound*: the state carries the number of incremental compiles since
  its last clean one, and a job compiles clean past a fixed bound, which bounds how far a stale
  class can travel.
- **Row 525.** The Coursier cache is a store addressed by coordinates and checked by Coursier, so
  a wrong key costs downloads, never correctness, and its jars' times do not matter to Zinc. The
  key `hashFiles('**/pom.xml', '**/plugins.sbt')` leaves out `project/SparkBuild.scala` and
  `project/build.properties`, which also move what is resolved; adding them costs only misses.
- **Row 528.** The plan file is a constructive trace: a plan is used without being recomputed, so
  every input that moves it must be in the key or recorded as read. Settings should be recorded as
  the planner reads them, as `READING_TEST_SELECTION.md` describes for uRTS, not listed by hand.
  The code version stays out of the key only if the file never outlives the run's commit (section
  8.8: the task itself is an input), and that scope should be written down. Expression ids are
  fresh per planning, so step 3's equality is modulo ids (section 8.3's non-determinism). Keying by
  SQL text is a deep key with no early cutoff; a key on the canonical analyzed plan would share
  plans between texts that analyze alike. Open question: whether any emit option reaches the
  planner, which would put it in every key.
