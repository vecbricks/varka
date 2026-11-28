# VARKA-679: `vechrono`, a library of its own for the batch forms of `java.time`

*Planned on 27 November 2026. This plan stands alone: its reader needs only this file and the
`AGENTS.md` files of the two repositories it names. **Nothing of this row starts, and the
repository is not created, before milestone 8 closes and the owner says go** (his answer of
27 November 2026: "let's finish the milestone 8 first of all"; `m9/PLAN.md` decision 18);
the lead confirms with him before P0 item 1. Its facts about Varka were checked on master
`a0e3a62cf2b`, and its facts about the precedent on `vecbricks/vfdlibm` at `8f852d63b1b`
(release 0.3.1). Revised the same day after a builder's review and against row 678's plan,
`VARKA-678.md`, which by the lead's ruling is the reference for the zone data: row 678 is
built first, the library ports its merged files, 4.2 restates them, and 10.1 and 10.5 say
what stays the library's own. Revised again after a second review: the parts that the
row's done-when does not need are in 5.6. Revised a last time on the owner's word of the
same day, "Nanosecond should be prime units": every form has nanosecond precision from
0.1.0, in two shapes (4.1, 4.9).*

## 1. What this row is

**The row's text** (`sql/varka/plans/m9/PLAN.md`, section 3, row 679): "`vechrono`, a library of
its own for the batch forms of `java.time`. [...] Section 2.10: its repository; the zone
database, the offset forms and the three rules first, from the probes of 2.2; then the fields,
the sums and the nanosecond forms as their rows come. **Done when** the repository builds and
tests in its own CI; the every-zone sweep and the forms' benchmarks at each vector width are in
it; a release is on Maven Central; and a Varka suite checks a kernel against it in test scope".

**In one paragraph.** Spark answers every datetime expression by calling `java.time` once a row.
Varka's kernels answer a batch at a time, and each kernel must give `java.time`'s answer in every
lane. `vechrono` is a small Java library, in a repository of its own, that holds those batch forms
outside Spark: a function over `LongVector` lanes whose every lane equals what `java.time` returns
on the running JVM, at nanosecond precision. It is to the datetime kernels what `vfdlibm` is to
the math kernels. A form is written and tested there first, where a build takes seconds and needs
no Spark. Varka then emits the same operations as its own IR nodes, and Varka's tests hold the
emitted kernel to the library's form and to `java.time`. No kernel calls the library.

**This plan is mostly about a second repository.** Nineteen of its twenty code parts belong
to `vecbricks/vechrono` (its founding commit and eighteen parts, nineteen pull requests
since the release is two); one is a pull request of Varka. Five more parts are written out and
left to a later row (5.6). Part 0 is no pull request: it is what the owner must do or approve before
anything is created or published.

**What it gives the rows after it.**

| row | what it takes from this row | from which part |
| :--- | :--- | :--- |
| 678, the zone database in Varka | nothing: row 678 is built first, and `ZoneData` and `Zones` are ports of its merged `VarkaChronoZoneChanges` and `VarkaChronoZones` (10.1) | L3, L4, L5 |
| 682 and 657, `ToLocal` and the first kernels | `VChrono.toLocal(x, steps)`: the reference of the one-offset form and of the steps | L6 |
| 689, the steps at two lanes | the arm with no mask, `ToLocal.noMask`, and its domain | L6 |
| 686, the table form | `ZoneTable` and `VChrono.toLocal(x, table, scratch)` | L8 |
| 690, a local time to an instant | `VChrono.toInstant` and `VChrono.toInstantKept` under the three rules | L9, L10 |
| 684, the proof | the hard-case inputs and the planted-fault names, which Varka's sweep and fault suite mirror | L2, L6, L9, L10 |
| 691, the measurement | each form's cost a row at each width, measured outside Spark | L7, L14, L17 |
| 662, the nanosecond layouts | both of its layouts outside Spark, on the same data: the two words (a long of microseconds and the nanoseconds within it) and one long of nanoseconds, with their costs | L11 to L14, L17 |
| 668 to 671 and 673 to 675, the fan-out | one library form for each `java.time` method a row lowers, in both shapes, from the template of 5.3 | after 0.1.0 |

**What must exist before it starts.** Part 0, the owner's go-ahead, comes first. L1 and L2
need nothing of Varka. L3 needs row 678's parts a to c merged in Varka, since it ports their
files. The Varka part (V1) needs release 0.1.0 on Maven Central and row 682's kernel merged.

**The goals it serves** (`m9/PLAN.md` 1.1 and 1.2, quoted word for word).

- **G2**, "Every time zone, proven. A kernel's answer equals Spark's in every zone the session
  can name - every region in the time-zone data of the JDK that CI runs, Spark's short names
  and fixed offsets - at the gaps and overlaps of each change of a zone's offset too, checked
  by a sweep that runs in CI." The row moves item 2 of 1.2, "the every-zone sweep passes in CI
  under each rule by which Spark resolves a local time in a gap or an overlap (2.1)", in two
  ways. The library's own sweep is the first one that runs, months before a kernel exists. And
  the Varka part gives row 684's sweep a second reference. *A reader checks it* by the green
  `Nightly` run of `vecbricks/vechrono` and by `VarkaChronoLibrarySuite` in Varka's Build.
- **G5**, "After the foundation, each fan-out task is one task plan from a template, with a
  one-command oracle; most fan-out pull requests are built by the cheaper models". The row
  moves item 8 of 1.2, "the share of fan-out pull requests the cheaper models built, and their
  review finding counts, reported from row 658's ledger; no threshold". A library form with
  `java.time` as its oracle is the smallest task there is: one file, one test class, one
  command (`mvn -B verify`). Section 5.3 holds its template. *A reader checks it* by the
  library pull requests that row 658's ledger lists with a cheaper model as their builder.
- **G7**, "A fused `TIMESTAMP_LTZ` expression runs significantly faster than vanilla Spark".
  The row serves it only through later rows: a form's arms are timed in the library before a
  kernel is emitted (L7, L14, L17), and rows 657, 686, 689 and 690 emit the arm that won.
- **G1** only through rows 678 and 657, which build and read the zone data this row shares.
- **G4**, "Nanosecond `TIMESTAMP_NTZ` and `TIMESTAMP_LTZ`, precision 7 to 9 (phase 2)", and
  item 4 of 1.2, "at least three quarters of each nanosecond type's pairs fused under the
  flag", only through rows 662 and 673 to 675: the library holds Spark's two-word value and
  the one-long layout first, and measures the one against the other before row 662 builds
  them in kernels (`m9/PLAN.md` 2.5, "Two layouts (row 662), both built").

## 2. What an agent must know

### 2.1 The idea, and the terms

- **An instant** is a time since 1970-01-01T00:00:00Z, to the nanosecond. **A local time**
  is a time since 1970-01-01T00:00:00 on a wall clock, with no zone, to the nanosecond.
- **The two words** of either: a long of microseconds, and the nanoseconds within that
  microsecond, 0 to 999. It is Spark's `TimestampNanosVal` (`m9/PLAN.md` 2.5: "`epochMicros`,
  a long, and `nanosWithinMicro`, a short from 0 to 999. Its range is the microsecond types'
  whole range"). A microsecond `TIMESTAMP` or `TIMESTAMP_NTZ` is the same value with a second
  word of 0. For a `LocalDateTime` `ldt` the first word is `ldt.toEpochSecond(ZoneOffset.UTC)
  * 1_000_000 + ldt.getNano() / 1000` and the second `ldt.getNano() % 1000`.
- **One long of nanoseconds** is the other shape of the same value: `micros * 1000 + nanos`,
  which fits a long only from 1677-09-21T00:12:43.145224192 to 2262-04-11T23:47:16.854775807.
- **A shape** is one of those two. The two words are the contract over the whole range; the
  one long is a measured arm inside its range.
- **An offset** is what a zone adds to an instant to give the local time there. `java.time`
  holds it in whole seconds, within 18 hours of zero (`ZoneOffset`'s contract).
- **A change** is an instant `T` at which a zone's offset moves from `before` to `after`. A
  zone is a sorted list of changes, so its offset is a step function of the instant.
- **A gap** is a change with `after > before`: the wall clock jumps forward, and the local
  times in between never happen. **An overlap** is a change with `after < before`: the wall
  clock goes back, and the local times in between happen twice.
- **The window** of a change on the local time line runs from `lo = T + min(before, after)` to
  `hi = T + max(before, after)`. Inside it a local time is skipped (a gap) or doubled (an
  overlap).
- **The three rules** by which `java.time` turns a local time `L` into an instant, with
  `k = #{hi <= L}` counted over the zone's changes and `O[k]` the offset after `k` changes:
  - `EARLIER` (`LocalDateTime.atZone`): the instant is `L - O[k]`. In an overlap that is the
    earlier of the two instants; in a gap it moves the time later by the gap's length.
  - `KEPT` (`ZonedDateTime.ofLocal` with a preferred offset): as `EARLIER`, but inside an
    overlap the preferred offset wins if it is the later one.
  - `GAP_END` (`LocalDate.atStartOfDay`): as `EARLIER`, but a local time inside a gap gives
    the gap's end, the change's instant `T`.
- **A form** is one way to compute a conversion for a batch: one offset for the whole batch;
  **steps** (one compare and one masked add for each change the batch spans); the **table**
  (a bucket of 2^39 microseconds holds at most one change, so a lookup is two loads).
- **An arm** is one way to write a form's operations: the steps with a mask, or with a
  subtract, a shift and an AND. Every arm of a form gives the same lanes on its domain.
- **A lane** is one element of a vector. **A live lane** is one whose bit is set in the mask a
  masked form takes; a lane that is not live keeps the bits of the first argument.
- **The oracle** is the thing a test compares against. Here it is `java.time`, called once a
  lane on the same JVM.
- **A planted fault** is a known error switched on by a system property, in a JVM of its own,
  to show that a named test fails when the code is wrong.
- **tzdb** is the IANA time-zone database. A JDK ships one version of it (JDK 25.0.5 ships
  2026f, `sql/varka/spikes/zone-offset/results/facts.txt`), and a JDK update ships a newer one.

### 2.2 What the probes already showed

Five probes ran on 25 and 26 November 2026 (`sql/varka/spikes/zone-offset` in Varka, one Java
file each, `results/` beside them). They are the library's first content: its formulas, its
first test inputs and its first costs.

- The step formulas equal `java.time`. With the windows above, `EARLIER`, `KEPT` and
  `GAP_END` are each one formula over the count of `hi` at or before the local time. The probe
  `ZoneResolve` checked them in the 604 zone ids of JDK 25 to the year 2400: 18,399,068
  checks, no mismatch (`results/resolve.txt`).
- Changes are sparse. The closest two of one zone are 601,200 s apart, so a bucket of 2^39
  microseconds holds at most one. No zone has more than 5 in any 365 days
  (`results/facts.txt`).
- The 604 ids are 349 distinct sets of rules (`results/facts.txt`). These counts are of tzdb
  2026f: the library prints them and never asserts them.
- Steps written out are cheap, a table costs the same at any number of changes, and at two
  64-bit lanes on x86 the JVM compiles no mask, so a masked step runs as a Java loop there
  (`results/forms.txt`, on the laptop).

### 2.3 How Varka uses such a library: the precedent

`vfdlibm` (`https://github.com/vecbricks/vfdlibm`) is the model, and `m8/VARKA-345.md` 2.2 is
its rule in Varka: "The library is the reference and the test oracle of the emitted ports,
never a runtime dependency of a kernel". In full:

- The library is a test-scope dependency of `sql/catalyst`, from Maven Central, at a released
  version. Varka never pins a snapshot.
- A kernel never calls it. C2 does not inline a large callee, and a vector passed to a call
  that is not inlined is boxed (`m8/VARKA-345.md` 2.2). Varka emits the same operations as IR
  nodes, by its own measurement.
- A Varka suite runs the library's method and the emitted kernel on the same inputs and
  requires the same bits, and checks both against the JDK (`VarkaMathPortSuite` for the math).
- Each function comes as a pair: a pull request in the library, and the Varka pull request
  that emits it (`m8/VARKA-356.md` 2.5 and 2.6).

### 2.4 The names this row adds, and the names it meets

All new names are in the library, in the package and module `io.github.vecbricks.vechrono`.
The zone database is one design in two repositories: row 678 builds it in `sql/catalyst`
(`VARKA-678.md` of `sql/varka/plans/m9/`, sections 4.1 to 4.9), and the library holds the same
files under its own names. The second column is the name row 678 gives the same thing.

| library name | row 678's name | kind | meaning |
| :--- | :--- | :--- | :--- |
| `ZoneData` | `VarkaChronoZoneChanges` | public final class | one set of rules laid out for vector code: the entry (4.2) |
| `ZoneData.Check` | `VarkaChronoZoneChanges.Check` | public record | what the build-time check counted, and why it failed |
| `Resolve` | `VarkaChronoZoneChanges.Rule` | public enum, top level | `EARLIER`, `GAP_END`, `KEPT`, in that order |
| `Zones` | `VarkaChronoZones` | public final class | the cache: one entry for each distinct `ZoneRules` of this JVM |
| `ZoneFixtures` | `VarkaChronoZoneFixtures` | test class | the sets of rules of the JVM, the made-up rules, the scalar oracles |
| `ZoneDataTest`, `ZoneDataLocalTest`, `ZonesTest` | `VarkaChronoZoneChangesSuite`, `VarkaChronoZoneLocalSuite`, `VarkaChronoZonesSuite` | test classes | the same tests, by the same numbers |
| `ZoneNotServedException`, `Zones.of(ZoneId)` | none | public class, public method | 5.6's part X5, not in this row: a way from a `ZoneId` to a served entry, or an exception |
| `VChrono` | none | public final class | the vector forms and their scalar twins, static methods (4.4) |
| `ZoneSteps` | none | public final class | the scalars of the steps for one range of instants (4.3), in microseconds or, made by `ofNanos`, in nanoseconds (4.9) |
| `LocalSteps` | none | public final class | the scalars of the steps for one range of local times and one rule (4.3), in either unit as `ZoneSteps` |
| `NanoTable` | none | public final class | the table of one zone for one long of nanoseconds: three longs a bucket (4.9) |
| `PlusNanos` | none | package-private final class | the arms of `plusNanos`, the first form that reads and writes both words (4.9) |
| `ZoneTable` | `VarkaZoneTable` (row 686) | public final class | the packed bucket table of one zone, and its constants (4.5) |
| `ToLocal`, `ToInstant`, `TableLookup` | none | package-private final classes | the arms of each form |
| `Faults` | none | package-private final class | the planted-fault switch, `-Dvechrono.fault=<name>` |

**What stays the library's own, and why** (the lead's ruling of 27 November 2026: row 678's
plan is the reference for the algorithm, the checks, the layout and the edge rules; names,
the exception and the table's home may differ):

- *The names.* A library's public names carry no `Varka` prefix. The enum is `Resolve`, at
  the top level, because `VChrono`'s callers name it; Varka nests it as `Rule`, since
  `VarkaVectorIR` gets a `Resolve` of its own from row 690. The constants and their order are
  the same.
- *The exception.* `Zones.of(ZoneRules)` and `ZoneData.build` behave as Varka's do: they
  never throw, and the entry answers `served()`. Varka's provider returns null for an entry
  that is not served, which its compiler turns into a decline. The library's own way, `Zones
  .of(ZoneId)` throwing `ZoneNotServedException`, is part X5 of 5.6; in this row the `of`
  methods of `ZoneSteps`, `LocalSteps` and `ZoneTable` refuse such an entry (4.8).
- *The table's home.* Both entries give `changesAt`, `changeOffsets` and a `memo` slot. In
  Varka row 686's `VarkaZoneTable.pack` fills the slot from `VarkaChronoZone`; in the library
  `ZoneTable.of` does (4.5). The packed layout is the same.
- *The faults.* 4.2.10.

Names of Varka that this row meets and does not add:

| name | added by | what it is to this row |
| :--- | :--- | :--- |
| `VarkaZoneData` (`id`, `fixed`, `changes`, `localChanges`, `toLocalArgs`, `toInstantArgs`, `toLocalTable`) | rows 682 and 686 | the interface in `sql/catalyst` that a kernel's runner reads a zone through |
| `VarkaJavaTimeZoneData` | row 682, test scope | Varka's reference over `java.time`; what Varka's suites read while no release exists |
| `VarkaChronoZoneChanges`, `VarkaChronoZones`, `VarkaChronoZone`, `VarkaChronoZone.PROVIDER` | row 678 | the entry, the cache, and the production `VarkaZoneData` with its provider |
| `ToLocal`, `ToInstant`, `ToInstantKept` (IR records), `ZoneForm` (`ONE`, `STEPS_2`, `STEPS_8`, `TABLE`, `TABLE_SCALAR`) | rows 682, 686, 690 | the nodes and forms whose kernels V1 checks against the library |
| `VarkaZoneTable` (`SHIFT`, `ORIGIN`, `LIMIT`, `BUCKETS`, `LONGS`) | row 686 | the table's constants; `ZoneTable` has the same values |
| `VarkaEmitterZoneSuite` | row 682 | the catalyst suite whose kernel helper V1 reuses |

## 3. The admission check

Each fact was checked on 27 November 2026. A command is run from the directory named.

| # | fact | command | prints |
| ---: | :--- | :--- | :--- |
| 1 | The repository does not exist | `gh repo view vecbricks/vechrono --json name` | `Could not resolve to a Repository` |
| 2 | vfdlibm is on Maven Central and `sql/catalyst` reads it in test scope | in Varka: `grep -n -A3 '<groupId>io.github.vecbricks' sql/catalyst/pom.xml` | `<artifactId>vfdlibm</artifactId>`, `<version>0.2.0</version>`, `<scope>test</scope>` |
| 3 | No main source of Varka imports the library | in Varka: `git grep -n 'import io.github.vecbricks' -- 'sql/*/src/main'` | nothing |
| 4 | Adding that dependency took one file of the build | in Varka: `git show --stat --format=%s d06381265e3 -- sql/catalyst/pom.xml` | `sql/catalyst/pom.xml \| 11 +++++++++++` |
| 5 | Varka's test JVMs add the Vector API module already | in Varka: `grep -n 'add-modules=jdk.incubator.vector' pom.xml project/SparkBuild.scala` | one line in each file |
| 6 | No zone code is on Varka's master yet | in Varka: `git grep -l 'VarkaZoneData\|VarkaChronoZone' -- sql/catalyst/src sql/core/src` | nothing |
| 7 | The probes are five Java files with committed results | in Varka: `ls sql/varka/spikes/zone-offset/src` | `ZoneFacts.java`, `ZoneForms.java`, `ZoneKernels.java`, `ZoneResolve.java`, `ZoneTables.java` |
| 8 | vfdlibm's releases are tags, four so far | in vfdlibm: `git tag --list 'v*'` | `v0.1.0`, `v0.2.0`, `v0.3.0`, `v0.3.1` |
| 9 | vfdlibm's release publishes from the owner's machine; its workflow is a dry run | in vfdlibm: `sed -n 3,4p .github/workflows/release.yml` | `A DRY RUN OF A RELEASE, AND NOTHING MORE. This workflow never publishes` |
| 10 | vfdlibm's coordinates, licence and Central plugin | in vfdlibm: `grep -n 'io.github.vecbricks\|Apache-2.0\|central-publishing-maven-plugin' pom.xml` | the group, the licence name, the module name, the plugin (pinned `0.12.0`) |
| 11 | vfdlibm runs every workflow on Zulu 25 | in vfdlibm: `grep -h 'java-version' .github/workflows/*.yml \| sort -u` | `java-version: 25` |
| 12 | vfdlibm's founding commit was a direct commit of 9 files | in vfdlibm: `git show --stat --format=%s b9a681a \| tail -1` | `9 files changed, 570 insertions(+)` |
| 13 | A probe names the tzdb version by a public call | in Varka: `grep -n 'getVersions' sql/varka/spikes/zone-offset/src/ZoneFacts.java` | `ZoneRulesProvider.getVersions("UTC").lastKey()` |
| 14 | vfdlibm's scripts print these lines, which the sheets quote | in vfdlibm: `grep -n 'jar-check: passed\|jar-check: self-test passed\|release.sh: self-test passed\|self-test: passed' dev/jar-check.sh dev/release.sh dev/release-version.sh` | four lines, one for each string |
| 15 | vfdlibm's `release.yml` calls `ci.yml`, which declares `workflow_call` | in vfdlibm: `grep -n 'uses: ./.github/workflows/ci.yml' .github/workflows/release.yml; grep -n 'workflow_call' .github/workflows/ci.yml` | one line each |
| 16 | vfdlibm's `benchmark.yml` writes the results header itself, into `bench-results.txt` | in vfdlibm: `grep -n 'bench-results.txt\|echo "# Host:' .github/workflows/benchmark.yml` | the header's `# Host:` line and the artifact's file |

**What the design rests on and could not be checked here** (no JVM run is allowed for this
plan beyond Varka's emitter, which this row does not touch). Each is a test or a build-time
check that fails loudly, so a false premise stops the part:

- how `java.time` reads a rule year (4.2, "What `java.time` does"): the plan states it, and
  no builder opens a JDK source for it; checks C3, C4 and C7 hold it through the public
  API on every set of rules;
- that every set of rules of the running JDK passes the check: test 1 of L3;
- that C2 hoists the scalars of a `ZoneSteps` out of a caller's loop, so that the public form
  allocates nothing (prediction 3 of section 7);
- row 678's plan is under its own review and may change in detail; L3's B0 reads the merged
  file where one exists.

## 4. The design

### 4.1 Scope, in order

**The unit is the nanosecond, from the first release** (the owner, 27 November 2026: "we
should focus on the nanosecond precision from the begining I believe. Nanosecond should be
prime units."). Asked which shape a value takes, he chose both:

1. **The two words** are the contract and the reference over the whole range: a lane of
   microseconds and a lane of the nanoseconds within the microsecond, 0 to 999, Spark's
   `TimestampNanosVal`. A microsecond timestamp is the same value with a zero second word,
   so every form serves Spark's microsecond types and its nanosecond types alike.
2. **One long of nanoseconds** is an arm of each form over the years 1677 to 2262, a
   measured fast path.

These are the two layouts that Varka's row 662 builds and measures (`m9/PLAN.md` 2.5). 4.9
has the value's rules, the second word's lane type, and each form's signature in each shape.

**Release 0.1.0, which closes the row**, holds what rows 678, 657, 682, 686, 689 and 690 need
first, in this order:

1. `ZoneData` and `Zones`, the zone database, on the instant line and on the local line,
   ported from row 678's merged files (L3 to L5).
2. Instant to local, `VChrono.toLocal`, as one offset and as steps, masked and with no mask
   (L6). Its `java.time` definition: the instant plus `ZoneRules.getOffset` of it.
3. The table form of instant to local (L8).
4. Local to instant under the three rules, `VChrono.toInstant` (L9) and
   `VChrono.toInstantKept` (L10). Their `java.time` definitions: `LocalDateTime.atZone`,
   `LocalDate.atStartOfDay` and `ZonedDateTime.ofLocal`.
5. The one-long arm of each of those: `toLocalNanos`, `toInstantNanos` and
   `toInstantKeptNanos` over steps (L11), and `toLocalNanos` over a table (L12).
6. `plusNanos` in both shapes (L13): the smallest form that reads and writes both words, so
   that the release proves the two-word shape and not only carries it. Its `java.time`
   definition: `Instant.plusNanos`.

**After 0.1.0**, one form a pull request, each paired with the Varka row that lowers it
(section 5.3 has the list and the template). The order is the order of Varka's waves
(`m9/PLAN.md` section 4): the clock fields and the cast to a day (row 657), the calendar
fields (row 668), the casts (row 669), truncation and the constructors (row 670), the sums
and the difference (row 671), the local table (row 693), and the forms that read the second
word (rows 662 and 673 to 675). Each comes in both shapes.

**Never in the library** (`m9/PLAN.md` 2.10): "no parsing, no formatting and no `java.time`
objects" in a lane. It ships no time-zone data of its own.

### 4.2 `ZoneData` and `Zones`: the zone database

This section is the design of `VARKA-678.md` 4.1 to 4.9, restated with the library's names so
that this plan stands alone. L3, L4 and L5 port row 678's merged files and do not write them
from this text; where a merged file and this text differ, the merged file wins and the pull
request says where. `ZoneData` imports only `java.time`,
`java.time.zone`, `java.util` and `java.util.function`, each class by name.

**What `java.time` does**, which the layout must equal. A builder takes these as stated and opens
no JDK source for them. `ZoneRules.getOffset(Instant)` answers from its stored changes up to the
last one. Past it, where recurring rules exist, it reads a year and scans that year's rule changes
in the rules' order; it reads the year with the offset after the last stored change, not with the
offset in force. A rule's change for a year, `ZoneOffsetTransitionRule.createTransition(int
year)`, is a pure function of the year, so the Gregorian cycle of 146097 days, a whole number of
weeks, repeats it. The local lookups (`getValidOffsets`, `getTransition`) read the local year.
Checks C3, C4 and C7 below hold each of these through the public API, and so do tests 4 and 9 of
`ZoneDataTest` and tests 1 and 5 of `ZoneDataLocalTest`.

#### 4.2.1 The data layout: `ZoneData`

One final class, immutable after `build`. Its fields:

| field | type | what it holds |
| :--- | :--- | :--- |
| `fixed` | `boolean` | `rules.isFixedOffset()` |
| `at` | `long[n]` | the instants of the zone's changes in microseconds, strictly ascending: the stored changes, then the head, then the block (below) |
| `offsetSeconds` | `int[n + 1]` | `offsetSeconds[i]` is the offset in force after `i` changes, in whole seconds; `offsetSeconds[0]` holds before the first change |
| `block` | `int` | the count of changes in one cycle of the rule era; 0 for a zone with no rules |
| `blockStart`, `blockEnd` | `long` | the cycle's first instant and `blockStart + CYCLE_MICROS`; unused when `block` is 0 |
| `wholeMinutesFrom`, `wholeHoursFrom` | `long` | 4.2.7 |
| `check` | `Check` | 4.2.4 |
| `memo` | `volatile long[]` | 4.2.8; the one field written after `build` |

Constants, each `public static final`: `long MICROS = 1_000_000L`; `long CYCLE_MICROS =
146_097L * 86_400L * MICROS`; `long MAX_OFFSET_MICROS = 64_800L * MICROS` (18 hours,
`ZoneOffset`'s bound); `int MANY = 4096`.

**Visibility.** Every field is `private final` (`memo` is `private volatile`), and the arrays
never leave the class. The class, `build`, `Check`, `Resolve`, the five basic methods `fixed()`,
`served()`, `check()`, `size()` and `bytes()`, and the methods that 4.2.3 to 4.2.8 write as
`public` are public: Varka's benchmark reads them from another package, and so do the library's
callers, the jar check's consumer and V1. The view of 4.2.2 (`rank`, `atOf`, `secondsOf`, `loOf`,
`hiOf`) and `int block()` are package-private, with no modifier: the suites are in the same
package and read a change by its number through them. `localRank` and `localStarted` are private.
Where a test table says "the change `r`", it means `atOf(r)`, `secondsOf(r)` and `secondsOf(r +
1)`, for `r` from 0 to `size() - 1`; "a block change" is one with `r >= size() - block()`.

**The unit.** `at` is in microseconds, because a batch's least and greatest value and a
kernel's thresholds are microseconds: a fill compares and copies, and multiplies nothing. A
change is on a whole second, so the product by 1,000,000 is exact. Offsets stay whole seconds
in an `int`, which is what `ZoneTable.pack` takes; a fill multiplies the few it writes.
The local thresholds `lo` and `hi` are not stored: `hi(r) = at(r) + MICROS *
max(offsetSeconds[r], offsetSeconds[r + 1])` is two loads and an add, and a fill reads a
handful.

**The three runs of `at`.**

1. *Stored*: `rules.getTransitions()`, in its order, each `toEpochSecond() * MICROS`, with
   `getOffsetBefore()` of the first as `offsetSeconds[0]` and each `getOffsetAfter()` after it.
   A stored change whose two offsets are equal is kept as it is, a step of difference 0, so
   that the counts equal a walk of `ZoneRules.nextTransition`. With no stored change, `n` is 0
   and `offsetSeconds[0]` is `rules.getOffset(Instant.EPOCH)`; the rules are then not read, as
   `getOffset` does not read them .
2. *Head*, only if `rules.getTransitionRules()` is not empty and a stored change exists. Let
   `last` be the last stored change in seconds, `w` the offset after it, and `Y0` the year of
   `LocalDateTime.ofEpochSecond(last + 1, 0, w)`: the year `java.time` reads for the first
   instant of the rule era . The head is each `rule.createTransition(Y0)`, in the
   rules' order, whose epoch second is greater than `last`.
3. *Block*: each `rule.createTransition(y)` for `y` from `Y0 + 1` to `Y0 + 400`, in order of
   year and then of rule: 400 times the count of rules. `blockStart` is `LocalDateTime.of(Y0 +
   1, 1, 1, 0, 0).toEpochSecond(w) * MICROS`, the instant from which `java.time` reads the
   year `Y0 + 1`.

The array is not sorted by the builder. `java.time` scans a year's rule changes in the rules'
order , so a set of rules out of order has an answer that a sorted list does not give;
the check of 4.2.4 refuses it.

**To which year.** The rule era is written out for 400 years after its first, and never past that.
Every later instant is answered by the cycle (4.2.2), to `Long.MAX_VALUE`, which is in the year
294247. Row 678's line in `m9/PLAN.md` says "the rule years written out as they are needed"; both
plans write the whole cycle when the entry is built, because an entry that never changes needs no
lock on the path a batch takes.

**Memory.** An entry's arrays hold `12 * n + 4` bytes, which `bytes()` returns: 8 for each
instant and 4 for each offset. For a zone with two rule changes a year the block alone is 800
changes, 9600 bytes. The bucket table of 4.5, when a caller first asks for it, adds
`ZoneTable.LONGS` longs, 183,712 bytes (`m9/PLAN.md` 2.2). A fixed zone holds one `int`.

#### 4.2.2 The periodic view

Five package-private methods give the changes as one endless ascending sequence, numbered
from 0. With
`p = n - block`, the index of the block's first change:

- `long rank(long x)`: the count of changes with instant `<= x`. For `block == 0` or `x <
  blockEnd`: a binary search of `at`. Else `k = 1 + Long.divideUnsigned(x - blockEnd,
  CYCLE_MICROS)`, and the answer is `k * block` plus the binary search for `x - k *
  CYCLE_MICROS`. The true difference `x - blockEnd` is under 2^64, so the unsigned quotient is
  exact whatever the signs, and the reduced value is from `blockStart` to `blockEnd - 1`.
- `long atOf(long r)`: for `r < n`, `at[r]`; else, with `j = r - p`, `at[p + j % block] + (j /
  block) * CYCLE_MICROS`. Callers ask only for a change at or before a long they hold, so the
  sum is a long.
- `int secondsOf(long r)`: the offset in force after `r` changes: for `r <= n`,
  `offsetSeconds[r]`; else `offsetSeconds[p + (r - p) % block]`.
- `long loOf(long r)` and `long hiOf(long r)`: `atOf(r)` plus `MICROS` times the lesser, or
  the greater, of `secondsOf(r)` and `secondsOf(r + 1)`.

A change `r` *exists* when `r < n` or `block > 0`. `atOf`, `loOf` and `hiOf` are called only
for a change that exists; every loop below is bounded by a `rank`, which counts only such.
`int block()` returns the field.

`rank` is a `long`: a zone with rules has two or more changes in each of the 292,000 years
between today and `Long.MAX_VALUE`.

#### 4.2.3 The instant line

- `public int offsetSecondsAt(long instant)`: `secondsOf(rank(instant))`.
- `public int changes(long least, long greatest)`: 0 when `greatest <= least`; else
  `(int) Math.min(rank(greatest) - rank(least), MANY)`. A change at `least` is not counted and
  one at `greatest` is.
- `public void toLocalArgs(long least, long greatest, int steps, long[] args, int first)`: with
  `r0 = rank(least)` and `c = changes(least, greatest)`: throws `IllegalArgumentException("a
  range of " + c + " changes does not fit " + steps + " steps")` when `c > steps`. Else
  `args[first] = secondsOf(r0) * MICROS`; for `i` from 0 to `c - 1`, `args[first + 1 + 2 * i] =
  atOf(r0 + i)` and `args[first + 2 + 2 * i] = (secondsOf(r0 + i + 1) - secondsOf(r0 + i)) *
  MICROS`; for `i` from `c` to `steps - 1`, `Long.MAX_VALUE` and 0.

Each is exact for every pair of longs, the two ends of a long included, and none allocates.
The scalar meaning of the arguments, which the tests and row 682's lowering share: `local = x +
args[first] + sum over i of (x >= at[i] ? delta[i] : 0)`.

#### 4.2.4 The build-time check

`public static ZoneData build(ZoneRules rules)` never throws for a non-null argument. It builds
the arrays, runs the check, and returns an entry whose `served()` is `check.failure() == null`. An
`ArithmeticException` (a product or a sum computed with `Math.multiplyExact` and `Math.addExact`)
or a `DateTimeException` inside it becomes a failure whose text is C1's for an
`ArithmeticException` and `java.time threw <the exception's simple class name>` for a
`DateTimeException`. A fixed zone has nothing to check and is served: its `check()` is `new
Check(0, 0, 0, 0, Long.MAX_VALUE, 0, null)`, never null. An entry that is not served is asked
nothing but `served()`, `check()` and `fixed()`: the `of` methods of `ZoneSteps`, `LocalSteps` and
`ZoneTable` refuse it, and its other methods carry no guard.

    public record Check(int stored, int ruleChanges, int gaps, int overlaps,
        long closestWindowsSeconds, int compared, String failure) { }   // failure: null if passed

The build stops at the first failure, with these texts, in this order (an inexact product
or sum is found as the arrays are written). L3 holds C1 to C5; L4 adds C6 and C7.

| | what must hold | failure text |
| :--- | :--- | :--- |
| C1 | every product and sum of the build is exact | `a change outside a long of microseconds` |
| C2 | `at` rises strictly | `changes out of order at <instant>` |
| C3 | for each head and block change `i`, the rule's `getOffsetBefore()` is `offsetSeconds[i]` | `a rule's offset before is not the offset in force at <instant>` |
| C4 | on the instant line, at each point `s` (seconds) of the set below, and at `s - 1`: `rules.getOffset(Instant.ofEpochSecond(s)).getTotalSeconds()` equals `offsetSecondsAt(s * MICROS)` | `java.time differs at instant <s>` |
| C5 | each head change is before `blockStart`; each block change is at or after `blockStart` and before `blockEnd`; `offsetSeconds[n] == offsetSeconds[p]` | `a rule change outside its cycle at <instant>` |
| C6 | `hiOf(r) <= loOf(r + 1)` for each change `r` below `n` whose next change exists; with rules, the first change of the next cycle is change `n` | `local windows touch at <instant>` |
| C7 | on the local line, at each point `L` (seconds) of the set below, and at `L - 1`: the first and the last element of `rules.getValidOffsets(ldt)` equal the earlier and the later offset the data gives, or both the list and the data say that `L` is in a gap and `rules.getTransition(ldt).toEpochSecond()` is that change's instant | `java.time differs at local time <ldt>` |

- C4's points: every change `r` from 0 to `n - 1`, and with rules also change `n`, the first of
  the next cycle; and the start of each rule year, `LocalDateTime.of(y, 1, 1, 0,
  0).toEpochSecond(w)` for `y` from `Y0 + 1` to `Y0 + 401`.
- C7's points: `loOf(r)` and `hiOf(r)` of the same changes, and the local midnight that starts
  each year from `Y0` to `Y0 + 401`. `ldt` is `LocalDateTime.ofEpochSecond(L, 0,
  ZoneOffset.UTC)`. What the data gives at `L`: with `k = localRank(L * MICROS)` (4.2.5), the
  count of changes whose `hi` is at or before it. If change `k` does not exist (`k == n` in a
  zone with no rules), the one offset `secondsOf(k)`. Else change `k` is the next one: if
  `loOf(k) <= L * MICROS` the time is inside its window, a gap when `secondsOf(k + 1) >
  secondsOf(k)`, else an overlap of the two offsets `secondsOf(k)` (earlier) and `secondsOf(k
  + 1)`; if not inside, the one offset `secondsOf(k)`.

**Why the points are enough.** On each line both functions, `java.time`'s and the data's, are step
functions of whole seconds. `java.time`'s can step only at a change of the year it reads or where
the year it reads changes ; the data's only at a change. So between two neighbours of the union of
those points both are constant, and equality at each point and one second before it is equality
everywhere up to the end of the first cycle. Past it, both repeat by the cycle: the data by 4.2.2,
`java.time` by what `java.time` does (above). The far-future tests hold that second half, which
the check does not run.

C4 runs before C5 on purpose: `offsetSecondsAt` is a binary search and a division that throw
for no data, so it is safe on a block that C5 would refuse, and a set of rules that
`java.time` reads in another year then fails with the text that says so. C5 is what the
reduction by cycles of 4.2.2 rests on. No made-up rules known to this plan pass C4 and fail C5;
its failure arm is reached by no test, only by the fault `zone.skipC4`.

`stored` is the count of stored changes and `ruleChanges` the head plus the block.
`compared` counts the lookups of C4 and C7. `closestWindowsSeconds` is the least `loOf(r + 1) -
hiOf(r)` in seconds, `Long.MAX_VALUE` for fewer than two changes. `gaps` and `overlaps` count
the changes held with `after > before` and `after < before`.

#### 4.2.5 The local line

`M` is `MAX_OFFSET_MICROS`. A local range is *inside the clamp* when `least >= Long.MIN_VALUE
+ 2 * M` and `greatest <= Long.MAX_VALUE - 2 * M`: a threshold is within `M` of its change, and
a change that matters is within `M` of the range, so inside the clamp no sum leaves a long.

- `private long localRank(long L)`: the count of changes with `hi <= L`. Start at `r =
  rank(L - M)`, since each of those changes has `hi <= L`; then, while `r < rank(L + M)` and
  `hiOf(r) <= L`, add one. C6 makes `hi` rise, and the loop runs once for each change within
  36 hours: once or not at all on the JDK's data .
- `private long localStarted(long L)`: the count with `lo <= L`, the same way with `loOf`.
- `public int localChanges(long least, long greatest)`: `MANY` when the range is not inside
  the clamp (so no form covers it); 0 when `greatest <
  least`; else `(int) Math.min(localStarted(greatest) - localRank(least), MANY)`: the windows
  with `hi > least` and `lo <= greatest`, each change that can touch a local time of the range.
- `public void toInstantArgs(Resolve rule, long least, long greatest, int steps, long[] args, int
  first)`: throws `IllegalArgumentException` when the range is not inside the clamp, or when
  `c = localChanges(least, greatest)` exceeds `steps` (the text of 4.2.3). With `r0 =
  localRank(least)`: `args[first] = secondsOf(r0) * MICROS`, the *base*. Then, for each change
  `r = r0 + i`, with `delta = (secondsOf(r + 1) - secondsOf(r)) * MICROS`, `after =
  secondsOf(r + 1) * MICROS` and `gap = delta > 0`:

| rule | the arguments of step `i`, in order | count | padding |
| :--- | :--- | ---: | :--- |
| `EARLIER` | `hiOf(r)`, `delta` | `1 + 2 * steps` | `Long.MAX_VALUE`, 0 |
| `GAP_END` | `hiOf(r)`, `gap ? loOf(r) : hiOf(r)`, `delta` | `1 + 3 * steps` | `Long.MAX_VALUE`, `Long.MAX_VALUE`, 0 |
| `KEPT` | `hiOf(r)`, `gap ? hiOf(r) : loOf(r)`, `after`, `delta` | `1 + 4 * steps` | `Long.MAX_VALUE`, `Long.MAX_VALUE`, 0, 0 |

The scalar meaning, from `VARKA-657.md` 2.4 and 3.7, which the tests of part b evaluate and
row 690's lowering must equal. `offset(L)` is the base plus the `delta` of each step that
fires:

- `EARLIER`: step `i` fires when `L >= hi[i]`. The instant is `L - offset(L)`. It is
  `LocalDateTime.atZone`.
- `KEPT`, with `kept` the offset the value had, in microseconds: step `i` fires when `L >=
  hi[i] || (L >= lo[i] && kept == after[i])`. It is `ZonedDateTime.ofLocal(ldt, zone,
  ZoneOffset.ofTotalSeconds(kept / MICROS))`. For a gap `lo[i]` is `hi[i]`, so no local time
  is inside.
- `GAP_END`: `L'` is `hi[i]` where `lo[i] <= L < hi[i]` for some `i`, else `L`; then
  `EARLIER` on `L'`. For an overlap `lo[i]` is `hi[i]`. Inside a gap the instant is the
  change's own instant. It is `LocalDate.atStartOfDay` at a midnight.

A window is in at most one step's reach, since windows do not touch (C6).

#### 4.2.6 Fixed zones, and a zone with no change

`build` of a fixed `ZoneRules` gives `n = 0`, `offsetSeconds = {the offset}`, `block = 0`,
`fixed = true` and no check. Every count is 0, `toLocalArgs` writes the offset and padding.
The cache does not keep it (4.2.9): a fixed offset has 129,601 possible values and costs nothing
to build. A zone with a standard-time change and no change of the wall offset is not fixed for
`java.time` and has `n = 0`: it is served, with one offset.

#### 4.2.7 Whole minutes and whole hours

`public long wholeMinutesFrom()` is the least instant `m` such that every offset in force at
or after `m` is a multiple of 60 seconds. It is total, by these rules in order, with `g =
60`:

1. if `block > 0` and any of `offsetSeconds[p]` to `offsetSeconds[n]` is no multiple of `g`:
   `Long.MAX_VALUE`. The rule era then holds an odd offset in every year, for ever;
2. if `offsetSeconds[n]` is no multiple of `g`: `Long.MAX_VALUE`;
3. else let `i` be the least index such that `offsetSeconds[i]` to `offsetSeconds[n]` are
   all multiples of `g`. If `i == 0`: `Long.MIN_VALUE`, every offset is whole;
4. else `at[i - 1]`, the change that ended the last odd offset.

`public long wholeHoursFrom()` is the same with `g = 3600`. Both are computed in `build`. No
merged plan reads them yet: `VARKA-685.md` 4.10 reduces the filled offsets instead. Row 678's line
in `m9/PLAN.md` asks for them.

#### 4.2.8 What the table reads

- `public long[] changesAt(long from, long until)`: a new array of the changes with `from <=
  instant < until`, ascending. `public int[] changeOffsets(long from, long until)`: a new array
  one longer, the offset in seconds before the first of them and after each. Both start from
  two ranks: `first = from == Long.MIN_VALUE ? 0 : rank(from - 1)` and `end = until ==
  Long.MIN_VALUE ? 0 : rank(until - 1)`, so nothing overflows at the least long, and `count =
  Math.toIntExact(Math.max(0, end - first))`. `changesAt` returns `atOf(first + i)` for `i`
  from 0 to `count - 1`; `changeOffsets` returns `secondsOf(first + i)` for `i` from 0 to
  `count`. With no change in the range, or `until <= from`, `changesAt` is an empty array and
  `changeOffsets` is one element, the offset in force just before `from`. With `from =
  ZoneTable.ORIGIN` and `until = ZoneTable.LIMIT` they are `pack`'s first two
  arguments. They allocate, and run once for a zone.
- `public long[] memo(Function<ZoneData, long[]> build)`: returns the array the entry keeps,
  calling `build` under `synchronized (this)` the first time, after a read of the volatile field
  that finds it null. `build` must not return null. `ZoneTable.of` keeps its table there (4.5).

#### 4.2.9 The cache: `Zones`

    public final class Zones {
      private static final ConcurrentHashMap<ZoneRules, ZoneData> ENTRIES = ...;
      public static ZoneData of(ZoneRules rules);
      public static int cached();
      static void clearForTesting();
    }

- `of`: a fixed `rules` is built and returned, not kept. Else
  `ENTRIES.computeIfAbsent(rules, ZoneData::build)`.
- **The key is the rules, by value** . Ids with the same rules share an entry, as the
  row's line asks, and nothing is keyed by an id. A lookup hashes the rules' arrays, a few
  hundred elements; it runs when a caller asks for a zone, never for a lane.
- **Thread safety.** `computeIfAbsent` builds an entry at most once: a second thread that asks
  for the same rules waits for the first, and threads that ask for other rules do not. `build`
  calls only `java.time` and never the cache, so the mapping function cannot recurse. An entry
  is safely published: its fields are final and its arrays are never written after the
  constructor. The one later write is `memo`, a volatile field written once under the entry's
  monitor.
- **An entry that failed its check is kept too,** with `served()` false, so a zone that is not
  served is checked once.
- **No eviction.** The map is bounded by the JDK's data: one entry for each distinct set of
  rules that a caller in this JVM named, at most 349 on time-zone data 2026f .
- **It follows the JVM.** The key is the `ZoneRules` object that `ZoneId.getRules()` returns
  when the provider is asked. If a `ZoneRulesProvider` gives an id new rules, the next lookup
  finds or builds the entry of the new rules.

#### 4.2.10 Where the library's two files differ from Varka's

Four differences, and nothing else but the package and the names of 2.4:

- **The clamp's exception.** In Varka's file `toInstantArgs` has one test, the count against
  `steps`; outside the clamp `localChanges` is `MANY`, so the exception there reads `a range
  of 4096 changes does not fit <steps> steps` (`VARKA-678.md` 4.5, "the text of 4.3"). The
  library's `toInstantArgs` tests the clamp first and throws a text of its own, `a local
  range within 36 hours of an end of a long`, because a caller of a library reads the
  message and 4096 changes is not what happened. The arguments written are the same.
- **The planted faults.** The library's files read their faults (section 6) into `static
  final boolean` constants, which fold away when no fault is planted. Row 678 plants its
  faults by hand after a commit and reverts them. The library has every fault of row 678's
  parts a to c but one, and one of its own: `zone.changesCountsLeast` is the library's, and
  row 678's "implements `Serializable`" cannot be a switch (L5).
- **`Rule` is `Resolve`,** a top-level enum (2.4).
- **Test 7 of the cache's suite** reads allocation through the JDK's
  `com.sun.management.ThreadMXBean`, where Varka's reads its own sampler (L5).

**The order of a step's arguments is fixed here and in `VARKA-678.md` 4.5,** by the table
of 4.2.5. `VARKA-657.md` 3.7 names the arguments and not their order, and `VARKA-682.md` 4.7
declares Varka's `toInstantArgs` over a `Zoned` node with no layout; no plan of row 690
exists yet. Row 690's lowering and the arms it adds to `VarkaChronoZone` follow this order.

### 4.3 `ZoneSteps` and `LocalSteps`: the scalars of the steps

    public final class ZoneSteps {
      public static ZoneSteps of(ZoneData zone, long least, long greatest);
      public static ZoneSteps of(ZoneData zone, long least, long greatest, int steps);
      public int count();                // 0, 2 or 8
      public long least();
      public long greatest();
      public boolean noMaskExact();      // greatest >= least, and greatest - least fits a long
    }
    public final class LocalSteps {
      public static LocalSteps of(ZoneData zone, Resolve rule, long least, long greatest);
      public static LocalSteps of(ZoneData zone, Resolve rule, long least, long greatest,
          int steps);
      public Resolve rule();
      public int count();                // 0, 2 or 8
      public long least();
      public long greatest();
    }

- The written-out step counts are 0, 2 and 8, as Varka's `ZoneForm` has `ONE`, `STEPS_2` and
  `STEPS_8`. The form of `of` with no `steps` takes the least of the three that is not below
  `zone.changes(least, greatest)` (for `LocalSteps`: `zone.localChanges(least, greatest)`).
  Past 8 it throws the `IllegalArgumentException` of `toLocalArgs` or `toInstantArgs`, since
  it calls them with 8 steps. The form with `steps`: for 0, 2 or 8 it calls the fill with
  that count, which pads the unused steps and throws if the count is below the range's
  changes; for any other number it throws `IllegalArgumentException("steps must be 0, 2 or
  8, not " + steps)` and calls nothing.
- `of` allocates one `long[]` of the fill's size, calls `zone.toLocalArgs(least, greatest,
  count, args, 0)` or `zone.toInstantArgs(rule, least, greatest, count, args, 0)`, and reads
  the array into fields. A `ZoneData` that is not served throws
  `IllegalArgumentException("the zone is not served: " + zone.check().failure())`.
- `LocalSteps.of` throws for a range outside the clamp, as `toInstantArgs` does (4.2).
- **The scalars are `long` fields, not an array.** Each class keeps `base` and, for each of
  eight steps, its thresholds and its delta in `final long` fields: `at0` to `at7` and `d0`
  to `d7` in `ZoneSteps`; `hi0` to `hi7`, `lo0` to `lo7`, `after0` to `after7` and `d0` to
  `d7` in `LocalSteps`. A step past `count` holds the padding. A field that the rule does not
  write (`lo` under `EARLIER`, `after` under `EARLIER` and `GAP_END`) holds `Long.MAX_VALUE`
  for `lo` and 0 for `after`. A store into a caller's `long[]` cannot alias a field, so C2
  can hoist the loads and their broadcasts out of the caller's loop. Prediction 3 of section
  7 scores this.

### 4.4 `VChrono`: the vector forms and their scalar twins

    public final class VChrono {
      public static LongVector toLocal(LongVector instant, ZoneSteps steps);
      public static LongVector toLocal(LongVector instant, ZoneSteps steps,
          VectorMask<Long> live);
      public static LongVector toLocal(LongVector instant, ZoneTable table, int[] scratch);
      public static LongVector toLocal(LongVector instant, ZoneTable table, int[] scratch,
          VectorMask<Long> live);
      public static LongVector toInstant(LongVector local, LocalSteps steps);
      public static LongVector toInstant(LongVector local, LocalSteps steps,
          VectorMask<Long> live);
      public static LongVector toInstantKept(LongVector local, LongVector kept,
          LocalSteps steps);
      public static LongVector toInstantKept(LongVector local, LongVector kept,
          LocalSteps steps, VectorMask<Long> live);
      public static long toLocal(long instant, ZoneData zone);                 // a caller's tail
      public static long toInstant(long local, ZoneData zone, Resolve rule);   // EARLIER, GAP_END
      public static long toInstantKept(long local, long kept, ZoneData zone);
    }

- **Any species.** Each vector method takes a `LongVector` of any species, 128 bits or more,
  and returns one of the same species. It reads the species from its first argument.
- **The two forms of each function**, as vfdlibm's `f(x)` and `f(x, live)`. The form with no
  mask computes every lane. The masked form computes the lanes set in `live`.
- **A lane that is not live holds the first argument's bits**, the instant or the local time,
  unchanged. It never selects a slow path, never indexes outside the table and never throws.
- **Arguments.** A null argument throws `NullPointerException` through
  `Objects.requireNonNull(x, "<name>")`, with the parameter's name. `toInstant` with `KEPT`
  steps throws `IllegalArgumentException("toInstant takes EARLIER or GAP_END, not KEPT")`;
  `toInstantKept` with other steps throws `IllegalArgumentException("toInstantKept takes
  KEPT, not " + rule)`. A `scratch` shorter than twice the vector's lane count throws
  `IllegalArgumentException("scratch holds " + n + " ints, the species needs " + m)` (4.5
  says why twice). Each check is loop-invariant.
- **The operations.** With `x` the argument and the scalars broadcast to its species:
  - `toLocal`, masked arm: `offset = base + sum over i of (x >= at[i] ? delta[i] : 0)`, each
    term one `compare(GE)` and one masked `add`; the result is `x + offset`. With a count of 0
    it is `x + base`.
  - `toLocal`, the arm with no mask: each term is `((at[i] - 1 - x) >> 63) & delta[i]`, an
    arithmetic shift. It is exact where `at[i] - 1 - x` does not overflow, which holds for
    every lane of the range when `steps.noMaskExact()`; a padded step is exact for every long
    (`VARKA-657.md` section 5). The public form takes this arm only when the species has two
    lanes, the host is x86 (`os.arch` is `amd64` or `x86_64`, read once into a
    `static final`) and `noMaskExact()` is true. Otherwise it takes the masked arm.
  - `toInstant(EARLIER)`: `local - (base + sum of (local >= hi[i] ? delta[i] : 0))`.
  - `toInstant(GAP_END)`: first `l = (local >= lo[i] && local < hi[i]) ? hi[i] : local` for
    each step, then `EARLIER` on `l`.
  - `toInstantKept`: as `EARLIER` with the condition
    `local >= hi[i] || (local >= lo[i] && kept == after[i])`. A `kept` lane that is no offset
    of the zone equals no `after[i]`, so such a lane answers as `EARLIER` does.
  - The masked form of each is `first.blend(result, live)`.
- **The scalar twins.** `toLocal(instant, zone)` is `instant + zone.offsetSecondsAt(instant)
  * 1_000_000L`, wrapping. `toInstant(local, zone, rule)` allocates a `long[9]`, calls
  `zone.toInstantArgs(rule, local, local, 2, args, 0)` and evaluates 4.2's scalar meaning; a
  point touches at most one window, so 2 steps always hold it. `toInstantKept` does the same
  with `KEPT`. They are for a caller's tail loop and for tests; they
  allocate, and say so in their Javadoc.
- **`toLocal` is total.** Its sums are two's-complement, so a lane within 18 hours of an end
  of a long holds `java.time`'s answer reduced modulo 2^64. **The way back is not:** a local
  range outside the clamp of 4.2 has no steps, and `LocalSteps.of` throws there.

### 4.5 `ZoneTable`: the table form

The constants and the bucket are those of `VARKA-686.md` 4.2, restated here, so that row
686's kernel and the library read one layout:

    public final class ZoneTable {
      public static final int SHIFT = 39;                          // a bucket is 2^39 microseconds
      public static final long ORIGIN = -2_208_988_800_000_000L;   // 1900-01-01T00:00:00Z
      public static final long LIMIT = 4_102_444_800_000_000L;     // 2100-01-01T00:00:00Z
      public static final int BUCKETS = 11_482;
      public static final int LONGS = 2 * BUCKETS;                 // 22964
      public static final int UNITS_A_SECOND = 15_625;             // a unit is 64 microseconds
      public static boolean has(ZoneData zone);
      public static ZoneTable of(ZoneData zone);                   // throws IllegalStateException
      public static boolean covers(long least, long greatest);     // ORIGIN <= least, greatest < LIMIT
      public static int index(long instant);                       // clamped, 0 to BUCKETS - 1
      public long toLocal(long instant);                           // the scalar lookup
      public void copyTo(long[] args, int first);                  // LONGS longs
      static long word(int beforeSeconds, int afterSeconds);
      static long offsetMicros(long word, boolean after);
      static int pack(long[] at, int[] offsetSeconds, long[] longs);
    }

- **A bucket is two longs.** With `b` the bucket, word 0, `longs[2 * b]`, is the instant of
  the bucket's change, or `Long.MAX_VALUE` where the bucket holds none. Word 1, `longs[2 * b
  + 1]`, holds the offset before the change in its high 32 bits and the offset after it in
  its low 32 bits, each a signed int in units of 64 microseconds. A bucket with no change
  holds the offset in force in both halves.
- `word(before, after)` is `((long) (before * UNITS_A_SECOND) << 32) | ((after * UNITS_A_SECOND) &
  0xFFFFFFFFL)`; it throws `IllegalArgumentException` for a second count outside plus and minus
  64800, as `VARKA-686.md` 4.2 has it. `offsetMicros(word, after)` is `((after ? word : word >>>
  32) << 32) >> 26`: the chosen half moved to the top, then an arithmetic shift that extends its
  sign and multiplies by 64.
- `index(x)` is `Math.min((int) ((x - ORIGIN) >>> SHIFT), BUCKETS - 1)`. The subtraction
  wraps near an end of a long; the logical shift then gives 0 to 2^25 - 1 for every long, and
  the `min` brings it into the table. A live instant has a bucket from 0 to 11480; bucket
  11481 is spare.
- `toLocal(x)` is `x + offsetMicros(longs[2 * b + 1], x >= longs[2 * b])` with `b =
  index(x)`.
- `pack(at, offsetSeconds, longs)` writes the `LONGS` longs and returns -1, or returns the
  number of the first bucket that two changes fall in and leaves `longs` unspecified. `at`
  holds the zone's changes inside `[ORIGIN, LIMIT)`, ascending; `offsetSeconds` has one entry
  more. It walks the buckets in order with one cursor into `at`: the offset in force starts
  as `offsetSeconds[0]`; a bucket with no change gets `Long.MAX_VALUE` and `word(o, o)`; a
  bucket with one change `at[i]` gets `at[i]` and `word(offsetSeconds[i], offsetSeconds[i +
  1])`, and the offset in force becomes `offsetSeconds[i + 1]`. It throws
  `IllegalArgumentException` for lengths that do not match (`offsetSeconds.length !=
  at.length + 1`, `longs.length != LONGS`) and for a change outside `[ORIGIN, LIMIT)` or out
  of order, as `VARKA-686.md` 4.2 has it. Varka's `pack` returns a boolean; the library's
  returns the bucket, since 4.8's message names it. That is the one difference of the two.
- `of(zone)` is `zone.memo(z -> ...)`: the function calls `z.changesAt(ORIGIN, LIMIT)` and
  `z.changeOffsets(ORIGIN, LIMIT)` and `pack`; on -1 it returns the `LONGS` longs, else the
  one-element array `{bucket}`. `of` wraps an array of `LONGS` longs in a `ZoneTable`, and
  for the one-element array throws `IllegalStateException("the zone has no table: two changes
  in bucket " + bucket)`. `has(zone)` is true for the long array. A zone that is not served
  throws as `ZoneSteps.of` does.
- **The vector form, `TableLookup.gather`,** with `LS` the species of `x` and `IS` the `int`
  species of the same shape (`VectorSpecies.of(int.class, LS.vectorShape())`), which has
  twice the lanes; that is why `scratch` holds twice the lane count:

| # | Vector API call | gives |
| ---: | :--- | :--- |
| 1 | `x.sub(ORIGIN)` | `t`, wrapping |
| 2 | `t.lanewise(LSHR, 39L)` | `u`, from 0 to 2^25 - 1 |
| 3 | `u.convertShape(L2I, IS, 0)` | the same as ints, in the low half of `IS` |
| 4 | `.min(BUCKETS - 1)` | the clamped bucket |
| 5 | `.lanewise(LSHL, 1)` | twice the bucket: the index of word 0 |
| 6 | `.intoArray(scratch, 0)` | the indices in `scratch` |
| 7 | `LongVector.fromArray(LS, longs, 1, scratch, 0)` | `w`, word 1 of each lane's bucket |
| 8 | `w.lanewise(LSHR, 32L)` | `h`: the offset before, in the low half |
| 9 | `LongVector.fromArray(LS, longs, 0, scratch, 0)` | `at`, word 0 |
| 10 | `x.compare(GE, at)` | the lanes at or after their change |
| 11 | `h.blend(w, mask)` | the chosen half in the low 32 bits |
| 12 | `.lanewise(LSHL, 32L)` | the half at the top |
| 13 | `.lanewise(ASHR, 26L)` | the offset in microseconds |
| 14 | `.add(x)` | the local time |

  The masked public form computes all 14 calls for every lane and then blends with `live`:
  the clamp of call 4 keeps a lane that is not live inside the table.
- **The arm `TableLookup.scalarLoads(LongVector x, ZoneTable t, long[] scratch)`** stores `x` into
  `scratch`, which the caller owns and which holds at least the lane count (else
  `IllegalArgumentException("scratch holds " + n + " longs, the species needs " + m)`), computes
  `toLocal` of each lane by the scalar lookup in place, and loads the result. It allocates
  nothing. It is for a host whose gather is not compiled (`ZoneForm.TABLE_SCALAR` in
  `VARKA-686.md`). The public form always takes `gather`; `scalarLoads` is reached by tests and
  benchmarks.
- **Its domain** is `covers(least, greatest)`: live lanes from `ORIGIN` to `LIMIT - 1`.

### 4.6 The contract, and the time-zone data

**The contract, which no version may change:** for a `ZoneData` built from a zone's rules on
the running JVM, each live lane of each function equals the result of the function's
`java.time` definition on that lane's argument, with that JVM's rules, to the nanosecond,
for every lane inside the domain of the steps or the table it was given. The value is the
two words; the one-long arm gives the same value as `micros * 1000 + nanos`. A lane that is
not live holds the first argument's bits.

For a lane's two words `(m, n)`:

- `instantOf(m, n)` is `Instant.ofEpochSecond(floorDiv(m, 1_000_000), floorMod(m, 1_000_000)
  * 1000 + n)`; `localOf(m, n)` is `LocalDateTime.ofEpochSecond` of the same second and the
  same nanosecond of the second, at `ZoneOffset.UTC`;
- `pairOf(instant)` is the inverse: `(getEpochSecond() * 1_000_000 + getNano() / 1000,
  getNano() % 1000)`, and `pairOf(ldt)` the same over `toEpochSecond(ZoneOffset.UTC)`.

| function | its `java.time` definition, for a lane `(m, n)` | the second word |
| :--- | :--- | :--- |
| `toLocal` | `pairOf(LocalDateTime.ofInstant(instantOf(m, n), zone))` | untouched: an offset is whole seconds |
| `toInstant`, `EARLIER` | `pairOf(localOf(m, n).atZone(zone).toInstant())` | untouched |
| `toInstant`, `GAP_END` | `rules.getTransition(localOf(m, n))` is a gap: `pairOf` of its instant; else as `EARLIER`. At a midnight this is `localOf(m, n).toLocalDate().atStartOfDay(zone)` | inside a gap: 0, since the gap's end is a whole second; else untouched |
| `toInstantKept` | `pairOf(ZonedDateTime.ofLocal(localOf(m, n), zone, offsetOf(kept)).toInstant())`, with a `kept` that is no whole second within 18 hours read as no preference (a null offset) | untouched |
| `plusNanos` | `pairOf(instantOf(m, n).plusNanos(dm * 1000 + dn))`, for a delta of two words `(dm, dn)` | read and written (4.9) |

For `toLocal` the first word is reduced modulo 2^64 (4.4). The one-long arms are refused
near an end of a long (4.9), so they have no such reduction.

**The domain** is stated, because a finite list of steps cannot be right for every long: a
live lane must lie from `steps.least()` to `steps.greatest()`, or inside the table's range. A
live lane outside it has an unspecified value. The library does not check lanes; the caller
gives the range, as a Varka kernel's runner does.

**When the tzdb differs by JDK.** The library holds no zone data. It reads the rules of the
JVM it runs in, when `Zones.of` is first called for those rules. So:

- Its answers move with the JVM's tzdb, exactly as `java.time`'s do there. Two JVMs with two
  tzdb versions give two answers, and each equals its own `java.time`. That is the contract,
  not a defect, and it is what lets a kernel and Spark's row engine agree on one executor
  (`m9/PLAN.md` section 5, "Time-zone data differs").
- No committed file holds an expected result. A hard-case file holds inputs only: a zone id
  and an instant or a local time. The expected value is computed by `java.time` in the test.
- No test asserts a count of zones, of rules or of changes of the JDK's data. Tests iterate
  `ZoneId.getAvailableZoneIds()` and print the counts with the tzdb version,
  `ZoneRulesProvider.getVersions("UTC").lastKey()` (fact 13).
- Every test run, nightly run and results file names its JDK build and its tzdb version.
- A zone id of a hard-case file that the running JVM does not have is skipped and counted,
  and the test fails if more than a quarter of the file's lines were skipped.
- **Supported JDKs:** 0.1.x supports JDK 25 only, as vfdlibm 0.3.x does, because the Vector
  API is incubating. Every workflow pins Zulu 25. A JDK 25 update with a newer tzdb is
  covered by construction, and the nightly meets it the night the runner image moves.

### 4.7 Edge cases, each with the test that holds it

| case | what the library does | held by |
| :--- | :--- | :--- |
| a mask with no live lane | every lane is the first argument's; the table's `scratch` is written | `maskedLanes` of each form's test |
| two 64-bit lanes on x86 | the arm with no mask where it is exact, else the masked arm as a Java loop; same lanes | `armsAgree`, CI's 128-bit row |
| AVX2 against AVX-512 | the same lanes at 256 and 512 bits; 512 is skipped on a host without it | `VectorWidth`, CI's three x86 rows |
| `Long.MIN_VALUE`, `Long.MAX_VALUE` as an instant | exact counts and fills; `toLocal`'s sum wraps | `ZoneDataTest` 3 and 7, `ToLocalTest.endsOfTheRange` |
| a local range within 36 hours of an end of a long | `localChanges` is `MANY`; `toInstantArgs` and `LocalSteps.of` throw | `ZoneDataLocalTest` 5, `ToInstantTest.clamp` |
| `greatest < least` | 0 changes; `toLocalArgs` writes the base at `least` and padding | `ZoneDataTest` 7 |
| a second word outside 0 to 999 | unspecified in `plusNanos`; passed through unread elsewhere; no form checks it (4.9) | `HarnessSelfTest.pairsAndNanos` holds that no test feeds one |
| a one-long range within 36 hours of an end of a long | `ofNanos` throws | `OneLongTest.rangeEnds` |
| a second word across a gap's end under `GAP_END` | the caller zeroes it under `VChrono.insideGap`; the one-long arm does it by itself | `ToInstantTest.secondWordPassesThrough`, `OneLongTest.shapesAgree` |
| a carry out of the second word | `plusNanos`: the first word gains 1 | `PlusNanosTest.carryEdges` |
| the calendar's ends | years 0001 and 9999, 15 October 1582: instants like any other; the data has no calendar | `ZoneDataTest` 3, `ZoneDataLocalTest` 5 |
| before a zone's first change | `offsetSeconds[0]`, often a local mean time that is no whole minute | `ZoneDataTest` 3 |
| past the last stored change | the cycle, to `Long.MAX_VALUE` | `ZoneDataTest` 4, fault `zone.cycleDayShort` |
| a change that skips a whole day (`Pacific/Apia`, 30 December 2011) | one gap of 24 hours, as any gap | `ZoneDataLocalTest` 2 to 4 |
| midnight inside a gap (`America/Toronto`, 31 March 1919) | `GAP_END` gives the gap's end where `EARLIER` gives half an hour later | `ZoneDataLocalTest` 4 |
| a fixed zone | no change: a count of 0, `base` the offset; not cached | `ZoneDataTest` 10, `ZonesTest` 2 |
| a set of rules that fails the check | `served()` is false; the `of` methods of `ZoneSteps`, `LocalSteps` and `ZoneTable` refuse it; the every-zone test fails, since the contract names every zone | `ZoneDataTest` 1 and 9 |

### 4.8 What declines, by name

The library declines nothing silently. Its exceptions, each with the case that holds it:

| exception | text | thrown by | held by |
| :--- | :--- | :--- | :--- |
| `IllegalArgumentException` | `a range of <c> changes does not fit <steps> steps` | `toLocalArgs`, `toInstantArgs`, and the `of` methods that call them | `ZoneDataTest` 8, `ZoneDataLocalTest` 8, `ToLocalTest.tooManyChanges` |
| `IllegalArgumentException` | `a local range within 36 hours of an end of a long` | `toInstantArgs` (tested before the count), `LocalSteps.of` | `ZoneDataLocalTest` 5, `ToInstantTest.clamp`, `ToInstantKeptTest.clamp` |
| `IllegalArgumentException` | `steps must be 0, 2 or 8, not <n>` | `ZoneSteps.of`, `LocalSteps.of` with a count | `refusals` of `ToLocalTest` and `ToInstantTest` |
| `IllegalArgumentException` | `the zone is not served: <check failure>` | `ZoneSteps.of`, `LocalSteps.of`, `ZoneTable.of`, `ZoneTable.has` | `refusals` of `ToLocalTest`, `ToInstantTest` and `TableTest` |
| `IllegalArgumentException` | `toInstant takes EARLIER or GAP_END, not KEPT`; `toInstantKept takes KEPT, not <rule>` | `VChrono` | `wrongRule` of `ToInstantTest` and `ToInstantKeptTest` |
| `IllegalArgumentException` | `scratch holds <n> ints, the species needs <m>`; for `TableLookup.scalarLoads`, `scratch holds <n> longs, the species needs <m>` | `VChrono.toLocal` over a table, `TableLookup` | `TableTest.shortScratch` |
| `IllegalArgumentException` | no fixed text | `ZoneTable.word`, `ZoneTable.pack` | `TableTest.packWords`, `TableTest.packRefuses` |
| `IllegalStateException` | `the zone has no table: two changes in bucket <b>` | `ZoneTable.of` | `TableTest.noTable` |
| `NullPointerException` | the parameter's name | `ZoneData.build`, `Zones.of`, the `of` methods of `ZoneSteps`, `LocalSteps` and `ZoneTable`, and every method of `VChrono` | `refusals` of `ToLocalTest`, `TableTest`, `ToInstantTest` and `ToInstantKeptTest` |

`ZoneNotServedException` is not in this row: 5.6, part X5.

The one-long arms and `plusNanos` add these (4.9):

| exception | text | thrown by | held by |
| :--- | :--- | :--- | :--- |
| `IllegalArgumentException` | `a nanosecond range within 36 hours of an end of a long` | `ZoneSteps.ofNanos`, `LocalSteps.ofNanos` | `OneLongTest.rangeEnds` |
| `IllegalArgumentException` | `toLocal takes steps in microseconds, these are in nanoseconds`, and the same sentence for `toInstant`, `toInstantKept` and `insideGap`; `toLocalNanos takes steps in nanoseconds, these are in microseconds`, and the same for `toInstantNanos` and `toInstantKeptNanos` | `VChrono` | `OneLongTest.wrongUnit` |
| `IllegalArgumentException` | `scratch holds <n> ints, the species needs <m>` | `VChrono.toLocalNanos` over a `NanoTable` | `NanoTableTest.refusals` |
| `IllegalStateException` | `the zone has no table: two changes in bucket <b>` | `NanoTable.of` | `NanoTableTest.noTable` |
| `NullPointerException` | the parameter's name | every method of 4.9 | `refusals` of `OneLongTest`, `NanoTableTest` and `PlusNanosTest` |

### 4.9 Nanoseconds: the two shapes

**The value.** A value is the pair `(micros, nanos)` with `0 <= nanos <= 999`. Both words
are `LongVector`s of one species.

- **Why the second word is a `LongVector`.** Spark holds it as a short. A `ShortVector` with
  the lane count of a 128-bit `LongVector` would be 32 bits wide, and the Vector API has no
  such shape; an `IntVector` of half the shape exists at every width, but every form would
  then cross two shapes with a conversion on each side of its arithmetic, and the carry of
  `plusNanos` moves from the second word into the first. With both words in one species the
  carry is one compare and one masked add, and a reference stays readable. A caller widens
  Spark's short when it loads (`ShortVector.convertShape(S2L, ...)`, or a scalar loop) and
  narrows when it stores. Varka's registry already holds a value of two long words, the
  128-bit decimal of row 359 (`m9/PLAN.md` 2.5: "One value is two words, as a 128-bit decimal
  is in the registry"); whether a kernel keeps the second word as a long lane or narrower is
  row 662's measurement, not the library's.
- **A lane that breaks the invariant** (`nanos < 0` or `nanos > 999`) has an unspecified
  result in a form that reads the second word (`plusNanos`), and is passed through unread by
  a form that does not. No form checks it: the caller holds the invariant, as it holds the
  range of the steps. `Oracle.instantOf` throws for such a pair, so no test feeds one.
- **The one long** is `micros * 1000 + nanos`, from `Long.MIN_VALUE` to `Long.MAX_VALUE`
  nanoseconds: 1677-09-21T00:12:43.145224192Z to 2262-04-11T23:47:16.854775807Z. A form's
  one-long arm is given a range by its caller, as the steps are; `ofNanos` checks the range
  and refuses one within 36 hours of an end of a long, so no sum of the arm leaves a long:
  with `E = 129_600_000_000_000L` (36 hours in nanoseconds), a range is accepted exactly
  when `leastNanos >= Long.MIN_VALUE + E` and `greatestNanos <= Long.MAX_VALUE - E`, that
  is from -9223242436854775808 to 9223242436854775807, and is refused otherwise with 4.8's
  text, before any count is taken. A live lane outside the range it was
  given is unspecified, and no form checks lanes.

**Forms that leave the second word untouched take the first word alone.** `toLocal`,
`toInstant` under `EARLIER` and `toInstantKept` change a value by an offset, which is whole
seconds. Their public forms are the ones of 4.4, over the microsecond word; the caller
carries the second word across unchanged. Each such form has a test,
`secondWordPassesThrough`, which feeds pairs with second words of 0, 1, 500 and 999 to
`java.time` at nanosecond precision and requires the oracle's second word to equal the
input's and its first word to equal the form's lane. **`GAP_END` is the one exception:**
inside a gap the answer is the gap's end, a whole second, so the second word becomes 0
there. `VChrono` has for it

    public static VectorMask<Long> insideGap(LongVector local, LocalSteps steps);   // GAP_END steps

which is true in the lanes whose local time is inside a gap of the steps (`local >= lo[i]
&& local < hi[i]` for some step); a caller with nanosecond data zeroes its second word under
that mask. Spark reaches `GAP_END` only from a `DATE`, whose second word is 0 already.

**The one-long arm of the steps.** The arithmetic of a step has no unit: a compare and an
add. So the arm is the same code over scalars in nanoseconds.

    // in ZoneSteps
    public static ZoneSteps ofNanos(ZoneData zone, long leastNanos, long greatestNanos);
    public static ZoneSteps ofNanos(ZoneData zone, long leastNanos, long greatestNanos, int steps);
    public boolean nanos();                 // false for steps made by of, true for ofNanos
    // in LocalSteps
    public static LocalSteps ofNanos(ZoneData zone, Resolve rule, long leastNanos,
        long greatestNanos);
    public static LocalSteps ofNanos(ZoneData zone, Resolve rule, long leastNanos,
        long greatestNanos, int steps);
    public boolean nanos();
    // in VChrono
    public static LongVector toLocalNanos(LongVector instant, ZoneSteps steps);
    public static LongVector toLocalNanos(LongVector instant, ZoneSteps steps,
        VectorMask<Long> live);
    public static LongVector toInstantNanos(LongVector local, LocalSteps steps);
    public static LongVector toInstantNanos(LongVector local, LocalSteps steps,
        VectorMask<Long> live);
    public static LongVector toInstantKeptNanos(LongVector local, LongVector kept,
        LocalSteps steps);
    public static LongVector toInstantKeptNanos(LongVector local, LongVector kept,
        LocalSteps steps, VectorMask<Long> live);
    public static long toLocalNanos(long instant, ZoneData zone);              // scalar twins
    public static long toInstantNanos(long local, ZoneData zone, Resolve rule);
    public static long toInstantKeptNanos(long local, long kept, ZoneData zone);

- **Where the arguments come from.** `ofNanos` is in `ZoneSteps` and `LocalSteps`, not in
  `ZoneData`: the ported file is untouched, and its `diff` rule (L3, L4) holds. It works in
  three steps. (1) It refuses a range within 36 hours of an end of a long (4.8's text), and
  otherwise turns the range into microseconds by floor: `leastM = Math.floorDiv(leastNanos,
  1000)`, `greatestM = Math.floorDiv(greatestNanos, 1000)`. (2) It calls the ported fill,
  `zone.toLocalArgs(leastM, greatestM, steps, args, 0)` or `zone.toInstantArgs(rule, leastM,
  greatestM, steps, args, 0)`. (3) It multiplies by 1000, with `Math.multiplyExact`, every
  entry of the array but those that equal `Long.MAX_VALUE`: the base, each threshold (`at`,
  `hi`, `lo`), each `delta` and each `after`. An entry of `Long.MAX_VALUE` stays as it is:
  that is a threshold of a padded step, and also the `lo` field of a real step under a rule
  that does not write it (4.3), and neither may ever fire. A 0 times 1000 is 0, so the
  `delta` and `after` of padding stay 0. No real threshold is `Long.MAX_VALUE`: a range
  that is accepted ends 36 hours below it.
- **Why that is exact.** A change is on a whole second, so for a lane `x` in nanoseconds `x
  >= at * 1000` holds exactly when `floorDiv(x, 1000) >= at`. A change counted in `(leastM,
  greatestM]` lies inside the nanosecond range, so its product is a long. On the local line
  a threshold is within 36 hours of the range, which is why the range is refused that close
  to an end; inside it `multiplyExact` cannot throw, and `OneLongTest.rangeEnds` holds that
  at the last range that is accepted.
- **`kept`** in the one-long arm is the offset in nanoseconds.
- **The forms** check the unit once a call: `toLocal`, `toInstant` and `toInstantKept` take
  steps with `nanos()` false, the `*Nanos` forms take steps with `nanos()` true, and the
  other kind throws 4.8's text. Then they run the arms of `ToLocal` and `ToInstant` as they
  are; the arm with no mask is chosen by the same rule. Under `GAP_END` the one-long arm is
  complete by itself: a lane inside a gap becomes the gap's end in nanoseconds, with no
  fraction.
- **The scalar twins** go through the two words: `Math.floorDiv(x, 1000)` and
  `Math.floorMod(x, 1000)`, the microsecond twin of 4.4, and `m * 1000 + n` back, with the
  second word zeroed inside a gap under `GAP_END`.

**The one-long arm of the table.** The packed bucket of 4.5 cannot hold an offset in
nanoseconds: 18 hours are 64,800,000,000,000 ns, and a half of a long is 32 bits. So the arm
has a table of its own, three longs a bucket, the probe's "three gathers" form.

    public final class NanoTable {
      public static final int SHIFT = 49;                              // a bucket is 2^49 ns
      public static final long ORIGIN = -2_208_988_800_000_000_000L;   // 1900-01-01T00:00:00Z
      public static final long LIMIT = 4_102_444_800_000_000_000L;     // 2100-01-01T00:00:00Z
      public static final int BUCKETS = 11_213;
      public static final int LONGS = 3 * BUCKETS;                     // 33639
      public static boolean has(ZoneData zone);
      public static NanoTable of(ZoneData zone);                       // throws IllegalStateException
      public static boolean covers(long leastNanos, long greatestNanos);   // ORIGIN <= least, greatest < LIMIT
      public static int index(long instantNanos);                      // clamped, 0 to BUCKETS - 1
      public long toLocal(long instantNanos);                          // the scalar lookup
    }
    // in VChrono
    public static LongVector toLocalNanos(LongVector instant, NanoTable table, int[] scratch);
    public static LongVector toLocalNanos(LongVector instant, NanoTable table, int[] scratch,
        VectorMask<Long> live);

- A bucket is 2^49 ns, about 562,950 s, under the 601,200 s between the closest two changes of
  a zone (2.2), so it holds at most one change; `BUCKETS` is `((LIMIT - ORIGIN) >>> SHIFT) +
  2`, where the shift gives 11211. Bucket `b` is three longs at `3 * b`: the change's
  instant in nanoseconds, or `Long.MAX_VALUE`; the offset before it in nanoseconds; the
  offset after it. A bucket with no change holds the offset in force in both.
- `static int pack(long[] at, int[] offsetSeconds, long[] longs)`, package-private, in
  full. `at` holds changes in microseconds, ascending, each inside `[ZoneTable.ORIGIN,
  ZoneTable.LIMIT)`, the microsecond bounds; `offsetSeconds` has one entry more. It throws
  `IllegalArgumentException` when `offsetSeconds.length != at.length + 1`, when
  `longs.length != LONGS`, when a change is outside those bounds or not above the one
  before it, and when an offset is outside plus and minus 64800 seconds. Then, with `o =
  offsetSeconds[0]` the offset in force and `i = 0`, for each bucket `b` from 0 to `BUCKETS
  - 1`: if `i < at.length` and `index(at[i] * 1000) == b`, the bucket's three longs are
  `at[i] * 1000`, `offsetSeconds[i] * 1_000_000_000L` and `offsetSeconds[i + 1] *
  1_000_000_000L`, then `o = offsetSeconds[i + 1]` and `i` moves on; if the next change is
  in bucket `b` too (`i < at.length` and `index(at[i] * 1000) == b` again), `pack` returns
  `b`, the first bucket with two changes, and `longs` is unspecified. Otherwise the three
  longs are `Long.MAX_VALUE`, `o * 1_000_000_000L` and `o * 1_000_000_000L`. It returns
  -1 when every bucket is written. No product can leave a long: a change inside the bounds
  times 1000 is inside `[ORIGIN, LIMIT)` of this class.
- `of(zone)` packs from `zone.changesAt(ZoneTable.ORIGIN, ZoneTable.LIMIT)` and
  `zone.changeOffsets(ZoneTable.ORIGIN, ZoneTable.LIMIT)`. It keeps the table in a
  `ConcurrentHashMap<ZoneData, long[]>` of its own, since the entry's one `memo` slot holds
  `ZoneTable`'s; the table of a fixed zone is built and not kept, as `Zones` does not keep
  a fixed entry. Two changes in one bucket give no table, as in 4.5.
- `index(x)` is `Math.min((int) ((x - ORIGIN) >>> SHIFT), BUCKETS - 1)`. The vector form
  is calls 1 to 6 of 4.5's table with the shift 49 and, as call 5, `mul(3)` in place of the
  shift by 1; then three gathers at the offsets 0, 1 and 2, `before.blend(after, x >= at)`,
  and `add(x)`. `scratch` holds twice the lane count, as in 4.5. There is no scalar-load
  arm: `ZoneTable`'s measures that question.

**`plusNanos`, the first form over both words.**

    // two words: the result is two vectors, so it is two methods
    public static LongVector plusNanosMicros(LongVector micros, LongVector nanos,
        LongVector deltaMicros, LongVector deltaNanos);                // the first word
    public static LongVector plusNanosWithin(LongVector nanos, LongVector deltaNanos);   // the second
    public static LongVector plusNanosMicros(LongVector micros, LongVector nanos,
        LongVector deltaMicros, LongVector deltaNanos, VectorMask<Long> live);
    public static LongVector plusNanosWithin(LongVector nanos, LongVector deltaNanos,
        VectorMask<Long> live);
    // one long
    public static LongVector plusNanos(LongVector nanos, LongVector deltaNanos);
    public static LongVector plusNanos(LongVector nanos, LongVector deltaNanos,
        VectorMask<Long> live);

- **The delta is two words too:** `deltaMicros` any long and `deltaNanos` from 0 to 999, the
  floor form, so a negative delta of 1 ns is `(-1, 999)`. A caller with a delta in
  nanoseconds splits it once, in scalar code, by `Math.floorDiv(d, 1000)` and
  `Math.floorMod(d, 1000)`. The form has no vector division: no host compiles one for long
  lanes.
- **The operations.** `sum = nanos + deltaNanos`, from 0 to 1998; `carry = sum >= 1000`.
  `plusNanosWithin` is `sum.sub(1000, carry)`: the sum, less 1000 in the lanes that carry.
  `plusNanosMicros` is `micros + deltaMicros`, plus 1 in the lanes that carry. Each is
  compares and adds of one species. The one-long `plusNanos` is one `add`.
- **Wrapping.** The first word's sum and the one-long sum are two's-complement: a result
  that leaves a long is `java.time`'s reduced modulo 2^64, as `toLocal`'s is.
- **A lane that is not live** holds the first argument's bits: `micros` for
  `plusNanosMicros`, `nanos` for `plusNanosWithin` and for the one-long form.

## 5. The parts

One list, one founding commit, nineteen pull requests of the library (L2 to L19; the release
is two) and one pull request of Varka close the row. Five more parts (5.6) are written and
not needed for the row's done-when; they are left to a later row (the owner's decision 6 of
10.4). Each code part is under 1000 counted lines, and each part whose estimate is 750 or
more has its cut line in its sheet.

| part | what it is | needs | gives | est. lines |
| :--- | :--- | :--- | :--- | ---: |
| P0 | the owner's list: what he does or approves | nothing | the repository, the coordinates, the licence, the release rule | 0 |
| L1 | the repository: build, CI, licence, the rules (the founding commit) | P0 items 1 to 5 and 8 | a green empty library | 700 |
| L2 | the test harness and the fault switch, at nanosecond precision | L1, P0 items 2a and 10 | `Oracle`, `Inputs`, `VectorWidth`, `Faults`, `PlantedFaultTest` | 950 |
| L3 | `ZoneData` on the instant line, ported | L2; row 678's parts a to c merged in Varka | the entry, checks C1 to C5, `changes`, `toLocalArgs` | 850 |
| L4 | `ZoneData` on the local line, ported | L3 | C6 and C7, `localChanges`, `toInstantArgs`, the grains, the table's inputs | 750 |
| L5 | `Zones`, the cache, ported | L4 | one entry for each set of rules | 400 |
| L6 | `VChrono.toLocal` as steps | L5 | `ZoneSteps`, the masked arm and the arm with no mask | 850 |
| L7 | the benchmarks' frame | L6 | `ToLocalBenchmark`, the allocation check, its CI job | 500 |
| L8 | the table form | L6 | `ZoneTable`, the gather arm and the scalar-load arm | 800 |
| L9 | local to instant: the earlier offset and the gap's end | L6 | `LocalSteps`, `VChrono.toInstant`, `insideGap` | 850 |
| L10 | local to instant: the kept offset | L9 | `VChrono.toInstantKept` | 600 |
| L11 | the one-long arm of the steps | L10 | `ofNanos`, `toLocalNanos`, `toInstantNanos`, `toInstantKeptNanos` | 650 |
| L12 | the one-long arm of the table | L8, L11 | `NanoTable`, `toLocalNanos` over it | 500 |
| L13 | `plusNanos` in both shapes | L6 | the first form that reads and writes both words | 550 |
| L14 | the benchmarks of the table, the way back and the two shapes | L7, L12, L13 | `TableBenchmark`, `TableStepsBenchmark`, `ToInstantBenchmark`, `ShapesBenchmark` | 650 |
| L15 | the every-zone sweep, as a program | L12 | `Sweep`, `NightlyTest` | 550 |
| L16 | the nightly workflow and its alert | L15, P0 item 9 | `nightly.yml`, `dev/nightly.sh` | 300 |
| L17 | the host benchmarks and the first results files | L14, P0 item 7 | `benchmark.yml`, `bench/host.sh`, `bench/record.sh`, results at 256, 128 and 512 bits | 500 |
| L18 | the jar checked from outside, and the release profile | L12, L13 | `dev/jar-check.sh`, the module name, the `release` profile | 700 |
| L19 | release 0.1.0 | L1 to L18, P0 item 6 | the jar on Maven Central | 80 |
| V1 | Varka's test-scope dependency and its cross-check | L19; Varka's rows 678 and 682 merged | `VarkaChronoLibrarySuite` | 450 |

**What can be built side by side by different agents.** L1 to L6 are strictly in line: each edits
or reads the files of the one before. After L6: L7, L8, L9 and L13. After L10: L11. After L12:
L15. After L12 and L13: L14 and L18. L16 and L17 each follow one part and touch no file of the
others. Every part that adds a fault adds lines to `pom.xml` and to `PlantedFaultTest`; two such
parts in flight conflict there, and the second one merges `main` and keeps both lists.

**Parts that only one later part reads:** L15 (read by L16).

### 5.1 What every library sheet shares

- **The repository** is `vecbricks/vechrono`, cloned at `/home/max/proj/vechrono`. Its default
  branch is `main`. Work on the branch the sheet names, from `origin/main`. In every sheet
  `M` is `src/main/java/io/github/vecbricks/vechrono` and `T` is its `src/test/java` twin.
- **The environment.** Every shell starts with these three lines; `<slot>` is the lock slot
  the lead names, and `heavy` if he names none:

      export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
      export PATH=/home/max/proj/vecbricks-varka/build/apache-maven-3.9.16/bin:$PATH
      export VARKA_HEAVY_LOCK=/home/max/varka-ops/locks/<slot>.lock

  `mvn -version` must then print `Apache Maven 3.9.16` and a Java version that starts with
  25. On the laptop every `mvn` run goes under the lock: `flock "$VARKA_HEAVY_LOCK" mvn ...`.
  Never print the environment (`env`, `printenv`, `set`).
- **Stop at a local commit.** Review, the push, the pull request and CI stay with the lead
  (P0 item 10), and nothing reaches `vecbricks/vechrono` before part 0 says so.
- **The title** is `[VECHRONO-<issue>] <what the change achieves>`, under 80 characters. The
  lead files the issue from the part's first paragraph before the build starts; the body
  says `Part of vecbricks/varka row 679`.
- **Attribution.** A commit message and a pull request end with one line,
  `Generated-by: <tool> (<model and version>)`, and with no `Co-Authored-By`.
- **The size** of a part is what this prints, and it must be under 1000:

      git diff --numstat origin/main...HEAD -- '*.java' '*.py' '*.sh' '*.yml' '*.xml' \
          '*.md' '*.txt' ':!bench/results' | awk '{s+=$1+$2} END {print s}'

- **The oracle of every part** is `flock "$VARKA_HEAVY_LOCK" mvn -B --no-transfer-progress
  verify`, which must end with `BUILD SUCCESS`, every surefire summary line reading
  `Failures: 0, Errors: 0`.
- **The faults.** `F0=$(grep -c '<id>fault-' pom.xml)` before the first edit. A sheet that
  adds `k` faults ends with `grep -c '<id>fault-' pom.xml` printing `F0 + k`. Each fault has:
  - one line in the class that owns it,
    `static final boolean X = Faults.is("<name>");`, read where the fault goes wrong, so the
    body holds no call and an unplanted fault folds away;
  - one surefire execution in `pom.xml` with the id `fault-<name>`, the phase
    `${vechrono.fault.phase}`, `<groups>fault</groups>`, `<failIfNoTests>true</failIfNoTests>`
    and the argument line `${vechrono.test.argLine} -Dvechrono.fault=<name>`;
  - one catcher. Each test class has `static Map<String, Executable> faultCatchers()`, and
    `PlantedFaultTest.CATCHERS` is the union of them. A test case that catches a fault keeps
    its body in a static method, `static void <case>Body(VectorSpecies<Long> species)` (no
    argument for a scalar case), which the `@Test` calls at each width. The catcher is
    `() -> PlantedFaultTest.assertCaught("<name>", () -> <case>Body(LongVector.SPECIES_PREFERRED))`,
    where `assertCaught(name, check)` is `assertThrows(AssertionFailedError.class, check,
    "the check did not catch planted fault " + name)`. So a fault run passes only when the
    case's body fails with an assertion error. A body that would fail by another exception
    wraps the call in `assertDoesNotThrow`, and its sheet says so.
  A fault execution runs the tests tagged `fault`, which is the one test
  `PlantedFaultTest.plantedFaultIsCaught`: it prints `Tests run: 1, Failures: 0, Errors: 0,
  Skipped: 0`.
- **Tests are nanosecond-native.** Every test of a form feeds pairs through the checks of
  `Oracle` (L2), which compare both words with `java.time` at nanosecond precision. The
  second words are spread over the lanes, one a lane, cycling: element `i` of an input
  array takes `Inputs.secondWords()[i % 4]`. A case is not run four times; only a case
  that says "each with each" crosses its first words with the four second words. Where a
  test table says that a form "equals `Oracle.toLocal`" (or another definition), it means
  that check: the form's lane is the first word, and for a form that takes the first word
  alone the input's second word is what `java.time` must return. Where a table lists
  instants or local times as longs, they are first words. A case computes the oracle once
  for each input and compares every arm, count and mask with that one answer.
- **A premise that fails is a stop.** Report the command and its output; do not repair it.
  Stop also when the size passes 900 before the sheet's last step: take the sheet's cut line.
- **Files of vfdlibm.** Where a sheet says "vfdlibm's" it names the file and the version,
  `/home/max/proj/vfdlibm` at tag `v0.3.1` unless a commit is given (`git -C
  /home/max/proj/vfdlibm show v0.3.1:<file>`), and says what stays and what goes. Replace
  `vfdlibm` by `vechrono`, `VFDLIBM` by `VECHRONO`, `VFdlibm` by `VChrono`, `DoubleVector` by
  `LongVector`. Not in 0.1.0, and so in no sheet: the assembly check (`AsmTest`, hsdis), the
  SMT proofs (`proofs/`, `dev/prove.sh`), `bench/record.py`, `bench/pair.sh`,
  `bench/datapath.sh`, the README's speedup table and `readme-check.yml`. The parts of 5.6
  hold what vfdlibm has and this row leaves to a later one.
- **The licence rule** (P0 item 3): never copy or translate a line of OpenJDK's `java.time`,
  ThreeTen, Joda-Time or cctz, and never open those sources: no sheet names a JDK source
  file, and a builder reads `java.time` through its Javadoc and its behaviour alone. What
  `java.time` does is in 4.2, and each such fact is held through the public API by checks
  C3, C4 and C7 and by the tests of L3 and L4.
- **Not here, in every part:** no time-zone data file, no `java.time` object in a vector
  loop, no dependency but JUnit and JMH (both test scope), no Spark and no Varka class.

### 5.2 The sheets

#### P0. The owner's list

Nothing of this row is created or published before these. Each later part names the items it
needs. The owner answered most of them on 27 November 2026; the last column has his answer.

**The start is not given.** On starting the library beside milestone 8 the owner said:
"let's finish the milestone 8 first of all". So nothing of this row starts, and the
repository is not created, before milestone 8 closes and the owner says go. The lead asks
him again before item 1; the other answers below hold from then.

| # | what the owner does or approves | recommended | needed by | the owner, 27 November 2026 |
| ---: | :--- | :--- | :--- | :--- |
| 1 | The word that row 679 starts (`m9/PLAN.md` decision 18; section 4 puts "the library's start (row 679)" in wave 0) | start it beside milestone 8 | L1 | **decided: not yet.** After milestone 8 closes, on his word; the lead confirms first |
| 2 | Create the repository `vecbricks/vechrono`: public, default branch `main`, issues on, Actions on, the labels `nightly-failure` and `nightly-fault-test`, and no branch rule yet | as written | L1 | **decided: yes,** once item 1 is given |
| 2a | After L1's first `CI` run is green: on `main`, the rule that a change needs a pull request whose four checks are green: `build (x86 as drawn)`, `build (x86 as AVX2)`, `build (x86 128 bits)`, `build (Arm 128 bits)`. A check can be required only after it has run once, and the rule would block item 5's push | the owner sets it once L1 is green; L7 adds `alloc (x86 as drawn)` and `alloc (x86 as AVX2)` to the list | L2 | open |
| 3 | The licence: Apache-2.0, with `LICENSE` copied from vfdlibm, and a `NOTICE` of three lines (`vechrono`, `Copyright 2026 the vecbricks contributors`, `Licensed under the Apache License, Version 2.0 (see LICENSE).`). No third-party notice: the library ports no code and ships no zone data. And the licence rule of 5.1 | as written | L1 | the licence and the notice: open. The rule: **decided,** a builder does not open OpenJDK's sources |
| 4 | The coordinates `io.github.vecbricks:vechrono`, the Java package and the module name `io.github.vecbricks.vechrono`. The namespace is verified at the Central portal already (vfdlibm's `AGENTS.md`, "Releases": "The same setup serves jegg's releases") | as written | L1, L19 | **decided: yes** |
| 5 | The first commit goes straight to `main`, as vfdlibm's did (fact 12): an empty repository has no branch to open a pull request against. The lead prepares it locally and pushes it | the lead pushes | L1 | **decided: yes** |
| 6 | Signing and publishing: after L19's pull request has merged, the owner tags its merge commit `v0.1.0`, pushes the tag, runs `mvn -B -Prelease clean deploy` on the tag with his GPG key and his `central` token in `~/.m2/settings.xml`, and publishes the deployment in the portal. No secret is stored in the repository and no workflow uploads | the owner, on his machine | L19 | open; it is his own step |
| 7 | Dispatches of `benchmark.yml` in the new repository on the AMD EPYC 7763 and on the Intel Xeon Platinum 8573C | a standing approval for the lead | L17 | **decided: yes,** a standing approval |
| 8 | Dependabot for the workflows' actions and the Maven plugins | yes, with vfdlibm's `.github/dependabot.yml` | L1 | open |
| 9 | Dispatches of `nightly.yml`: the two that test it in L16, and a replay of a failed night | a standing approval for the lead | L16 | **decided: yes,** a standing approval |
| 10 | Pushes of part branches and the opening of pull requests in `vecbricks/vechrono` | a standing approval for the lead, each after its review; a merge stays the owner's | L2 to L19 | open |
| 11 | Whether the five parts of 5.6 are built in this row (the owner's decision 6 of 10.4) | no: a later row | 5.6 | **decided: a later row;** the every-zone sweep stays |

**Done when** the owner has given item 1 and answered items 2a, 3, 8 and 10. Item 6 waits
for L19.

#### L1. The repository

*Title:* `[VECHRONO] The repository: build, CI, licence, the rules and the plan`.
*Branch:* none; one commit on `main` (P0 item 5).

**B0.** `gh repo view vecbricks/vechrono --json isEmpty --jq .isEmpty` prints `true`. In
vfdlibm, `git show --stat --format= b9a681a | tail -1` prints `9 files changed, 570
insertions(+)`.

**Steps.**

1. `LICENSE`: vfdlibm's, byte for byte. `NOTICE`: the three lines of P0 item 3.
2. `.gitignore`: vfdlibm's at `v0.3.1`, 9 lines, unchanged.
3. `pom.xml`, from vfdlibm's at `v0.3.1`, keeping only: the project's coordinates (group
   `io.github.vecbricks`, artifact `vechrono`, version `0.1.0-SNAPSHOT`), name, description
   ("Batch forms of java.time on the Java Vector API"), URL, the `licenses`, `developers` and
   `scm` blocks with the new repository's name and `<tag>HEAD</tag>`; the properties
   `maven.compiler.release` 25, `project.build.sourceEncoding`,
   `project.build.outputTimestamp`, `junit.version`, `jmh.version`, `maven.surefire.version`,
   `vector.module`, `vechrono.test.jvmArgs` (empty), `vechrono.fault.phase` (`test`) and
   `vechrono.test.argLine` (`--add-modules ${vector.module} ${vechrono.test.jvmArgs}`); the
   `repositories` and `pluginRepositories` blocks; the three dependencies (JUnit, `jmh-core`,
   `jmh-generator-annprocess`, test scope); the `resources` block that puts `LICENSE` and
   `NOTICE` under `META-INF/`; `maven-compiler-plugin` with its three compiler arguments
   (`--add-modules`, `${vector.module}`, `-Xlint:all`) and its execution `default-testCompile`
   whose `annotationProcessorPaths` names `jmh-generator-annprocess` (from JDK 23 on no
   annotation processor runs unless one is named, and without it L7 finds no benchmark);
   `build-helper-maven-plugin` adding `src/jmh/java` as a test source root;
   `maven-surefire-plugin` with the argument line, its two `<excludes>`
   (`**/jmh_generated/**` and `**/*$*`, without which `verify` runs JMH's generated classes
   as tests) and no fault execution. Not kept: every `fault-*` execution, the manifest's
   module name and the profile `release` (both L18's), the profiles `one-test` (L2's) and
   `jmh` (L7's).
4. `src/main/java/io/github/vecbricks/vechrono/package-info.java`: the package's Javadoc,
   the contract of 4.6 in one paragraph. The jar holds the licence files and no class yet.
5. `.github/workflows/ci.yml`, written out:
   - `on:` is `push` to `main`, `pull_request` and `workflow_call` (the dry-run workflow of
     5.6 calls this workflow);
   - `concurrency`: the group `ci-${{ github.workflow }}-${{ github.event_name ==
     'pull_request' && github.ref || github.run_id }}`, with `cancel-in-progress: ${{
     github.event_name == 'pull_request' }}`;
   - one job `build`, named `build (${{ matrix.name }})`, `fail-fast: false`, with four
     matrix rows whose `name` is: `x86 as drawn`
     (`ubuntu-latest`, no JVM option), `x86 as AVX2` (`ubuntu-latest`, `-XX:UseAVX=2
     -XX:MaxVectorSize=32`), `x86 128 bits` (`ubuntu-latest`, `-XX:MaxVectorSize=16`) and `Arm
     128 bits` (`ubuntu-24.04-arm`, no option);
   - its steps: `actions/checkout`, `actions/setup-java` (Zulu 25), `actions/cache` of
     `~/.m2/repository` with vfdlibm's key, a step that runs `java -version`, and `mvn -B
     --no-transfer-progress verify "-Dvechrono.test.jvmArgs=${{ matrix.jvm-args }}"`. Each
     action is pinned by the commit that vfdlibm's `ci.yml` pins it by.
6. `.github/PULL_REQUEST_TEMPLATE`: vfdlibm's five sections, with `StrictMath` read as
   `java.time` and "fdlibm's C" as "the form's `java.time` definition".
   `.github/dependabot.yml`: vfdlibm's, with the prefix `[VECHRONO] `.
7. `README.md`: what the library is (section 1's paragraph), the status ("0.x, nothing
   released"), the contract (4.6, with its table), "Supported JDKs" (4.6), the licence.
8. `PLAN.md`: the goal; how it grows (a form at a time, with Varka, section 5.4 of this
   plan); the scope and its order (4.1 and the list of 5.3); the API shape (4.2 to 4.5); the
   testing method (section 6); the rules.
9. `AGENTS.md`, and `CLAUDE.md` as a symbolic link to it: the rules that matter most (the
   licence rule, the contract, the tzdb rule of 4.6), build and test, the faults' rule of
   5.1, measurements (section 7's rule), changes (titles, attribution), releases (section
   5.5). It names vfdlibm's `SKILLS.md` as the place to read about JMH, C2 and the Vector
   API, and copies none of it.
10. `CHANGELOG.md`: the first paragraph of vfdlibm's, with the contract of 4.6 as its last
    sentence, then `## [Unreleased]` and nothing under it. It has no link line yet; L19 adds
    them.

**Tests.** None yet: the part's proof is the build and the four CI rows.

**Commands.** `mvn -B --no-transfer-progress verify` prints `BUILD SUCCESS`.
`unzip -l target/vechrono-0.1.0-SNAPSHOT.jar | grep -c 'META-INF/\(LICENSE\|NOTICE\)$'` prints
`2`. `git ls-files | wc -l` prints `13`. The size, which has no base to diff against:
`git ls-files | grep -E '\.(java|py|sh|yml|xml|md|txt)$' | xargs wc -l | tail -1` prints a
total under 1000. After the push, the run of `CI` on `main` shows four green rows.

**Done when** `main` holds the commit and its CI is green. **Not here:** no class, no test.

#### L2. The harness

*Title:* `[VECHRONO-<issue>] Add the java.time oracle and the test harness for the forms`.
*Branch:* `vechrono-l2-harness`.

**B0.** `ls src/test 2>/dev/null | wc -l` prints `0`. `grep -c '<id>fault-' pom.xml` prints
`0`.

**Steps.**

1. `M/Faults.java`: vfdlibm's `Faults` (61 lines), with the property `vechrono.fault`.
2. `M/Resolve.java`: `public enum Resolve { EARLIER, GAP_END, KEPT }`, since `Oracle` names it.
3. `T/VectorWidth.java`: vfdlibm's (55 lines), over `LongVector`
   (`VectorSpecies.of(long.class, VectorShape.forBitSize(bits))`).
4. `T/Oracle.java`, at nanosecond precision from the start. A value in a test is a pair of
   longs, the two words of 2.1; `record Pair(long micros, long nanos)` holds one, and its
   compact constructor throws `IllegalArgumentException` for a `nanos` outside 0 to 999.
   - The definitions of 4.6, static, each computed by `java.time` on `Instant` and `LocalDateTime`
     values with their nanoseconds: `instantOf(Pair)`, `localOf(Pair)`, `pairOf(Instant)`,
     `pairOf(LocalDateTime)`; `Pair toLocal(ZoneId zone, Pair instant)`; `Pair toInstant(ZoneId
     zone, Pair local, Resolve rule)` (`EARLIER` or `GAP_END`); `Pair toInstantKept(ZoneId zone,
     Pair local, long keptMicros)`; `Pair plusNanos(Pair value, Pair delta)`. For the one-long
     shape: `static long nanosOf(Pair p)`, which is, for `p.micros() >= 0`,
     `Math.addExact(Math.multiplyExact(p.micros(), 1000), p.nanos())`, and for a negative first
     word `Math.addExact(Math.multiplyExact(p.micros() + 1, 1000), p.nanos() - 1000)` (the plain
     product of the first word of `Long.MIN_VALUE` nanoseconds, -9223372036854776, is below a
     long, though the value is one; the second form has the same sum and no such product); and
     `static Pair ofNanos(long n)`, by `Math.floorDiv` and `Math.floorMod` with 1000.
     `nanosOf(ofNanos(n))` is `n` for every long, both ends included, and `nanosOf` throws
     `ArithmeticException` for a pair outside the range. A one-long expectation is always
     `nanosOf` of the pair's: the oracle has one definition a form.
   - The checks, each failing with an `AssertionFailedError` at the first lane that differs.
     Inputs and results are parallel arrays, a first word and a second word:

         static void check(String what, ZoneId zone, long[] inMicros, long[] inNanos,
             long[] gotMicros, long[] gotNanos, UnaryOperator<Pair> expected);
         static void check(String what, ZoneId zone, long[] inMicros, long[] inNanos,
             long[] kept, long[] gotMicros, long[] gotNanos,
             BiFunction<Pair, Long, Pair> expected);          // toInstantKept
         static void checkMasked(String what, ZoneId zone, long[] inMicros, long[] inNanos,
             long[] gotMicros, long[] gotNanos, boolean[] live, UnaryOperator<Pair> expected);
         static void checkMasked(String what, ZoneId zone, long[] inMicros, long[] inNanos,
             long[] kept, long[] gotMicros, long[] gotNanos, boolean[] live,
             BiFunction<Pair, Long, Pair> expected);
         static void checkNanos(String what, ZoneId zone, long[] in, long[] got,
             UnaryOperator<Pair> expected);                    // one long in, one long out
         static void checkNanos(String what, ZoneId zone, long[] in, long[] keptNanos,
             long[] got, BiFunction<Pair, Long, Pair> expected);   // toInstantKeptNanos
         static void checkNanosMasked(String what, ZoneId zone, long[] in, long[] got,
             boolean[] live, UnaryOperator<Pair> expected);
         static void checkNanosMasked(String what, ZoneId zone, long[] in, long[] keptNanos,
             long[] got, boolean[] live, BiFunction<Pair, Long, Pair> expected);
         static void checkPlusNanos(String what, long[] inMicros, long[] inNanos,
             long[] deltaMicros, long[] deltaNanos, long[] gotMicros, long[] gotNanos);
         static void checkPlusNanosOneLong(String what, long[] in, long[] delta, long[] got);

     A test of a form that takes the first word alone passes the input's second words as
     `gotNanos`: the check then holds that `java.time`'s second word is the input's, which
     is the pass-through of 4.9. `checkNanos` turns each input into a pair by `ofNanos`,
     and requires `got` to be `nanosOf` of the expected pair; with `keptNanos` it gives the
     oracle `keptNanos / 1000` as the kept offset in microseconds where `keptNanos` is a
     multiple of 1000, and `Long.MIN_VALUE` (no offset of any zone) where it is not.
     `checkPlusNanos` requires both result words to equal `Oracle.plusNanos(new
     Pair(inMicros[i], inNanos[i]), new Pair(deltaMicros[i], deltaNanos[i]))`;
     `checkPlusNanosOneLong` requires `got[i]` to be `nanosOf` of `Oracle.plusNanos(ofNanos
     (in[i]), ofNanos(delta[i]))`. The masked checks also require each lane that is not
     live to equal its input, in both words. Every check calls the oracle once a lane.
   - The message: `<what> <zone id> lane <i>: input <micros> <nanos> expected <micros>
     <nanos> got <micros> <nanos> (tzdb <version>)`, and with a kept offset `input <micros>
     <nanos> kept <kept>`. `static String mismatchLine(ZoneId zone, Pair in)` is `<zone id>
     <micros> <nanos>`, and with a kept offset `<zone id> <micros> <nanos> <kept>`: the form
     a hard-case file and the nightly use. A one-long mismatch is written as its pair.
5. `T/Inputs.java`:
   - `record RuleSet(int index, String id, ZoneRules rules, List<String> ids)` and `static
     List<RuleSet> sets()`: `ZoneId.getAvailableZoneIds()`, sorted, grouped by
     `ZoneId.of(id).getRules()` in a `LinkedHashMap`; `id` is the group's first id and
     `index` the set's place in the list, from 0. Computed once. Row 678's fixtures have the
     same record, component for component.
   - `static String tzdb()`: `ZoneRulesProvider.getVersions("UTC").lastKey()`.
   - `static List<ZoneId> hardZones()`: the ids of `hard-cases/zones.txt`, a file under
     `src/test/resources/io/github/vecbricks/vechrono/`, that the JVM has, with the skip
     rule of 4.6. "Has" means that `ZoneId.of(id)` returns and does not throw
     `DateTimeException`, so a fixed offset such as `+01:00` counts, though it is no element
     of `ZoneId.getAvailableZoneIds()`. The file holds one id a line and, after a
     `#`, why: `America/Los_Angeles`, `Australia/Lord_Howe` (a change of half an hour),
     `Asia/Kathmandu` (45 minutes), `Pacific/Apia` (a skipped day), `Africa/Casablanca` (5
     changes in 365 days), `Africa/Monrovia` (an offset that is no whole minute),
     `America/Havana` (a midnight that repeats), `America/Toronto` (midnight in a gap, 1919),
     `Asia/Qostanay`, `America/Goose_Bay` (8 distinct offsets), `America/Boa_Vista` (the
     closest two changes), `Europe/Dublin` (negative daylight saving), `Antarctica/Troll`,
     `Pacific/Kiritimati`, `UTC`, `+01:00`.
   - `static long[] ends()`, first words: `Long.MIN_VALUE`, `Long.MIN_VALUE + 1`,
     `Long.MAX_VALUE`, `Long.MAX_VALUE - 64_800_000_000L`, 0, -1, -62135596800000000
     (0001-01-01), -12219292800000000 (1582-10-15), 253402300799999999 and
     253402300800000000 (the year 9999's end).
   - `static long[] secondWords()`: 0, 1, 500, 999. A test "over pairs" takes each first
     word with each of these.
   - `static long[] nanoEnds()`, one-long values: `Long.MIN_VALUE`, `Long.MAX_VALUE`, each
     of them `129_600_000_000_000L` in (36 hours, the last accepted end of a range), one
     nanosecond either side of that, 0, -1, 999, 1000.
   - `static long[] around(long center, long halfWidth, long step)`: `center - halfWidth`
     to `center + halfWidth` by `step`; and `static long[] random(SplittableRandom random,
     long from, long until, int n)`: `n` values uniform in `[from, until)`.
   - `static List<VectorMask<Long>> masks(VectorSpecies<Long> species, long seed)`: all
     true, all false, and 14 masks from `new SplittableRandom(seed)`.
6. `T/PlantedFaultTest.java`: vfdlibm's (114 lines), with `CATCHERS` filled from
   `HarnessSelfTest.faultCatchers()`: `assertCaught`, `plantedFaultIsCaught` (tagged
   `fault`), `noFaultInTheNormalRun`, `everyFaultHasAnExecution`,
   `assertCaughtFailsWhenTheCheckPasses`.
7. `T/HarnessSelfTest.java`. Two fake forms: `fake(x)` is `x + 3_600_000_000L` in every
   lane, plus one more microsecond in lane 0 when `Faults.is("selftest.oneMicro")`;
   `fakeMasked(x, live)` blends `fake(x)` under `live`, and returns `fake(x)` unblended when
   `Faults.is("selftest.notLiveLane")`. Its catchers:

       static Map<String, Executable> faultCatchers() {
         var catchers = new LinkedHashMap<String, Executable>();
         catchers.put("selftest.oneMicro", () -> PlantedFaultTest.assertCaught(
             "selftest.oneMicro", () -> fakePassesBody(LongVector.SPECIES_PREFERRED)));
         catchers.put("selftest.notLiveLane", () -> PlantedFaultTest.assertCaught(
             "selftest.notLiveLane", () -> fakeMaskedPassesBody(LongVector.SPECIES_PREFERRED)));
         return catchers;
       }

8. `pom.xml`: surefire's `default-test` excludes the group `fault`; the two executions; the
   profile `one-test` (active when `-Dtest` is set, it sets `vechrono.fault.phase` to
   `none`), as vfdlibm's.

**Tests.**

| suite | case | inputs | expected, and its oracle | the failure only it catches |
| :--- | :--- | :--- | :--- | :--- |
| `HarnessSelfTest` (JUnit 5, library) | `fakePasses` (body `fakePassesBody`) | 64 first words around 0 at each width, each with the second words of `Inputs.secondWords()` | `check` of `fake`, which adds 3,600,000,000 to the first word, against `p -> new Pair(p.micros() + 3_600_000_000L, p.nanos())` passes | under `selftest.oneMicro`: a check that compares nothing |
| | `fakeMaskedPasses` (body `fakeMaskedPassesBody`) | the same, under `Inputs.masks` | `checkMasked` passes | under `selftest.notLiveLane`: a masked check that reads live lanes only |
| | `checkFailsOnOneLane` | `fake`'s output with the first word of lane 0 changed by the test; then with only the second word of lane 1 changed; then the kept check with one result changed; then `checkNanos` with one result changed | each check throws `AssertionFailedError`, and its message holds the lane, both words of the input and, with a kept offset, `kept <value>` | a check that reads the first word only; a failure message that cannot be turned into a hard case |
| | `pairsAndNanos` | 1000 seeded pairs inside the one-long range, and `Inputs.nanoEnds()` | `ofNanos(nanosOf(p))` is `p`; `pairOf(instantOf(p))` is `p`; `new Pair(0, 1000)` and `new Pair(0, -1)` throw | an oracle whose two shapes disagree |
| | `checkMaskedFailsOnADeadLane` | a mask with one lane off, that lane changed | `checkMasked` throws | - |
| | `oracleAgreesWithItself` | `hardZones()` by `ends()`, with three of its values left out because `toLocal` wraps or the way back is outside the clamp there: `Long.MIN_VALUE`, `Long.MIN_VALUE + 1` and `Long.MAX_VALUE` | `toInstant(EARLIER)` of `toLocal(x)` is `x` wherever `rules.getValidOffsets(localOf(toLocal(x)))` has one element | an oracle whose two directions use two units |
| | `setsAreDistinctRules` | `sets()` | no two entries have equal `ZoneRules`; the ids of all sets are `ZoneId.getAvailableZoneIds()`; prints the counts and `tzdb()` | a sweep that reads one set of rules twice |
| | `hardZonesSkipRule` | a temporary file with four unknown ids of five | fails with "more than a quarter skipped" | a hard-case file that silently tests nothing |
| `PlantedFaultTest` | the four of step 6 | the planted fault | each fault fails its catcher's body | a fault with no execution |

**Commands.** The oracle of 5.1; `grep -c '<id>fault-' pom.xml` prints `2`.

**The cut line.** If the size passes 900: `Inputs.hardZones()` with its file, `ends()`, and
the cases `oracleAgreesWithItself` and `hardZonesSkipRule` leave this part and become
`[VECHRONO-<issue>] Add the hard zones and the ends of a long as test inputs`, branch
`vechrono-l2b-hard-inputs`, which L3 then needs.

**Done when** the oracle passes on the laptop and on the four CI rows. **Not here:** no
`ZoneData`, no form.

#### L3. `ZoneData` on the instant line, ported

*Title:* `[VECHRONO-<issue>] Lay a zone's changes out for vector code, from the JVM's rules`.
*Branch:* `vechrono-l3-zone-data`.

Row 678 is built first, in Varka (`VARKA-678.md` section 11). L3, L4 and L5 port its merged
files; they do not write them. 4.2 says what the files hold, so that a reader of this plan
knows the design; **where the merged file and 4.2 differ, the merged file wins,** and the
pull request lists each such difference.

**B0.** `ls M/` prints `Faults.java`, `Resolve.java` and `package-info.java`. Then, in every
shell of this part (the two paths are Varka's main and test packages; plain strings, since
zsh does not split a variable):

    export VM=sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka
    export VT=sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka
    git -C /home/max/proj/vecbricks-varka fetch -q origin master
    git -C /home/max/proj/vecbricks-varka ls-tree -r --name-only origin/master -- \
        $VM/VarkaChronoZoneChanges.java $VM/VarkaChronoZones.java \
        $VT/VarkaChronoZoneChangesSuite.java $VT/VarkaChronoZoneLocalSuite.java \
        $VT/VarkaChronoZonesSuite.java $VT/VarkaChronoZoneFixtures.java | wc -l

prints `6`: row 678's parts a to c have merged. A lower count is a stop: the library waits
for row 678. Then the commit that added the file, row 678's part a:

    export A=$(git -C /home/max/proj/vecbricks-varka log --diff-filter=A --format=%H \
        origin/master -- $VM/VarkaChronoZoneChanges.java)

`echo "$A"` prints one commit id.

**Steps.**

1. `M/ZoneData.java`: the file as part a added it, `git -C /home/max/proj/vecbricks-varka
   show $A:$VM/VarkaChronoZoneChanges.java`, which holds the instant line and C1 to C5
   (4.2.1 to 4.2.4, less `loOf`, `hiOf`, C6 and C7). The edits of a port, and no other:
   - the package, `io.github.vecbricks.vechrono`;
   - the class name, `ZoneData`, in the file and in its comments;
   - the licence header, the library's (the one `Faults.java` has);
   - `build` starts with `Objects.requireNonNull(rules, "rules");`, and the file imports
     `java.util.Objects`: Varka's `build` has no such line, and L6's `refusals` case expects
     the message `rules`;
   - the six fault constants and their uses: `zone.cycleDayShort` (`CYCLE_MICROS` built
     from `146_096L`), `zone.rankLtForLe` (`rank` counts `at < x`), `zone.changesCountsLeast`
     (`changes` takes `rank(least - 1)`), `zone.noPadding` (`toLocalArgs` writes no
     padding), `zone.deltaIsAfter` (`toLocalArgs` writes the offset after as `delta`),
     `zone.skipC4` (C4 not run). Row 678 plants five of them by hand (a short cycle, the
     rank, C4 removed, no padding, the offset after as `delta`); `zone.changesCountsLeast`
     is the library's own.
2. `T/ZoneFixtures.java` and `T/ZoneDataTest.java`: Varka's `VarkaChronoZoneFixtures.java`
   and `VarkaChronoZoneChangesSuite.java` as they were at the commit that added each (the
   same `git log --diff-filter=A` command over each path). If row 678 took its cut line,
   its part a2 added the made-up rules to the fixtures and tests 9 to 11 to the same suite:
   then both files are taken as of part a2's commit, the second commit of `git log
   --format=%H --reverse origin/master -- $VT/VarkaChronoZoneChangesSuite.java`, so that
   `ZoneDataTest` has its 11 tests, and the pull request says so. The edits: the package;
   the names of 2.4; the licence header; the annotation `@VarkaSuite` and its import
   removed; `ZoneFixtures` loses its own `RuleSet`, `sets()` and `tzdata()` and imports
   `Inputs.RuleSet`, `Inputs.sets()` and `Inputs.tzdb()` (one `RuleSet` type in the
   library), and every call of `tzdata()` in the two files becomes `tzdb()`; the seed's
   property becomes `vechrono.zone.seed`; each test that catches a
   fault gets its static body method and its catcher (5.1). What the two files hold is
   written out below, so that the port can be checked against it.
3. `pom.xml`: six executions.

**Words the test tables of L3 to L5 share** (row 678's, word for word). Every test class is in
the library's package, so it reads the package-private view of 4.2.2.

- *Each set*: the list the test names, walked by `eachSet`; its random source is `new
  SplittableRandom(seed() + set.index())`, made inside the body, so a parallel walk draws the
  same numbers as a serial one. *Uniform from `a` to
  `b`* is `random.nextLong(a, b)`.
- *The domain of a set* is from `D0` to `D1 = 16725225600000000` (2500-01-01T00:00:00Z),
  where `D0` is `-5364662400000000` (1800-01-01T00:00:00Z) for a set whose `size()` is 0, and
  else the lesser of that and `atOf(0) - 315360000000000` (ten years of 365 days before the
  first change).
- *A random change* is `r = random.nextInt(size())`. An input that names a change skips a set
  whose `size()` is 0.
- *The range of `k` changes at `r`*, a pair `(least, greatest)` that holds exactly `k`
  changes: for `k == 0`, `(atOf(r), atOf(r) + 1)`; for `k >= 1`, `(atOf(r) - 1, atOf(r + k -
  1))`. It is skipped when `block() == 0` and `r + k > size()`.
- *A walk of `nextTransition` from `x` to `y`* (microseconds): `t =
  rules.nextTransition(Instant.ofEpochSecond(Math.floorDiv(x, 1_000_000L)))`, then `t =
  rules.nextTransition(t.getInstant())`, while `t != null` and `t.toEpochSecond() <=
  Math.floorDiv(y, 1_000_000L)`. It yields the changes `T` with `x < T <= y`.

**What `T/ZoneFixtures.java` holds after this part** (a final class of static helpers):

- `RuleSet` and `sets()` are `Inputs`' (L2), imported; the class defines neither.
- `static List<RuleSet> withMadeUp()`: `sets()`, then `threeRules()` and `standardOnly()`
  under the ids `made-up/three-rules` and `made-up/standard-only`, with the next two
  indexes and an empty list of ids.
- The made-up rules, exactly:

      static ZoneRules threeRules() {      // served: three rules a year, by day of week
        ZoneOffset std = ZoneOffset.ofHoursMinutes(5, 45);
        ZoneOffset dst = ZoneOffset.ofHoursMinutes(6, 45);
        ZoneOffset dbl = ZoneOffset.ofHoursMinutes(7, 45);
        var stored = ZoneOffsetTransition.of(LocalDateTime.of(1990, 10, 28, 3, 0), dst, std);
        return ZoneRules.of(std, dst, List.of(), List.of(stored), List.of(
            rule(Month.MARCH, -1, DayOfWeek.SUNDAY, 2, std, std, dst),
            rule(Month.JUNE, 1, DayOfWeek.SUNDAY, 2, std, dst, dbl),
            rule(Month.OCTOBER, -1, DayOfWeek.SUNDAY, 3, std, dbl, std)));
      }
      static ZoneRules outOfYear() {       // not served: java.time reads the year otherwise
        ZoneOffset std = ZoneOffset.ofHours(-1);
        ZoneOffset dst = ZoneOffset.UTC;
        var stored = ZoneOffsetTransition.of(LocalDateTime.of(2000, 7, 1, 0, 0), dst, std);
        return ZoneRules.of(std, dst, List.of(), List.of(stored), List.of(
            ZoneOffsetTransitionRule.of(Month.JANUARY, 1, null, LocalTime.MIDNIGHT, false,
                TimeDefinition.UTC, std, std, dst),
            ZoneOffsetTransitionRule.of(Month.JULY, 1, null, LocalTime.MIDNIGHT, false,
                TimeDefinition.UTC, std, dst, std)));
      }
      static ZoneRules standardOnly() {    // served, not fixed, no change of the offset
        ZoneOffset two = ZoneOffset.ofHours(2);
        var standard = ZoneOffsetTransition.of(
            LocalDateTime.of(2000, 1, 1, 0, 0), two, ZoneOffset.ofHours(3));
        return ZoneRules.of(two, two, List.of(standard), List.of(), List.of());
      }

  `rulesOnly()`, served: `ZoneRules.of(std, std, List.of(), List.of(), <the three rules
  of threeRules()>)`, rules and no stored change, which `java.time` answers with the base
  offset alone;
  and three more that are not served, each by one edit of `threeRules()`: `outOfOrder()`,
  its three rules in the order October, June, March; `brokenChain()`, its March rule with
  `dbl` as the offset before; and `tooFar()`, no rule and the one stored change
  `ZoneOffsetTransition.of(LocalDateTime.of(900000, 1, 1, 0, 0), std, dst)` with `std` as
  both base offsets.
  `rule(month, day, dayOfWeek, hour, standard, before, after)` is
  `ZoneOffsetTransitionRule.of(month, day, dayOfWeek, LocalTime.of(hour, 0), false,
  TimeDefinition.WALL, standard, before, after)`.
- `static int javaOffset(ZoneRules rules, long micros)`:
  `rules.getOffset(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L)))
  .getTotalSeconds()`. `tzdb()` is `Inputs`' too. `static long seed()`:
  `Long.getLong("vechrono.zone.seed", 678L)`.
- `static void eachSet(List<RuleSet> sets, BiConsumer<RuleSet, List<String>> body)`: runs
  `body` over `sets.parallelStream()`, each with its own list of failure lines, and fails
  with the first ten lines and the seed if any list is not empty. A failure line names the
  set's id, the instant and both answers.

**Tests.** `ZoneDataTest`, JUnit 5, each `@Test` with its sentence as a `@DisplayName`. They are
row 678's `VarkaChronoZoneChangesSuite`, by number, with the same made-up rules and the same
expected numbers. The oracle of each is `java.time`. `sets()` is `Inputs.sets()`. "Each set" is
`withMadeUp()` through `eachSet`, with the entry from `ZoneData.build`.

| # | test (its display name in short) | inputs | expected, and its oracle | the failure only it catches |
| ---: | :--- | :--- | :--- | :--- |
| 1 | every set of rules of this JVM builds and passes its check | `sets()` | `served()` for each, else the message holds the id and `check().failure()`; `check()` is never null; the count of ids is `ZoneId.getAvailableZoneIds().size()`; prints `checked: <sets> sets of rules for <ids> zone ids, tzdb <tzdb()>, <changes> changes held, <equal> stored changes with equal offsets, <compared> lookups`, where `<changes>` sums `size()`, `<compared>` sums `check().compared()` and `<equal>` counts the elements of each `rules.getTransitions()` whose two offsets are equal | a zone of a new time-zone data that the layout cannot hold, found at the JDK's update and not in a query |
| 2 | the offset around each change the entry holds is java.time's | each set; each change `r` from 0 to `size() - 1`: `atOf(r) - 1000000`, `atOf(r) - 1`, `atOf(r)`, `atOf(r) + 1`, `atOf(r) + 1000000` | `offsetSecondsAt` equals `javaOffset`; for a set of `sets()` (not a made-up one) it also equals `Instant.ofEpochSecond(Math.floorDiv(x, 1000000L)).atZone(ZoneId.of(set.id())).getOffset().getTotalSeconds()`, the call Spark's row path makes. A stored change with equal offsets, if test 1 counted one, is among these changes: this test is its case | a rank that counts `<` for `<=`: every row at a change in the old offset |
| 3 | the offset in the far past and at both ends of a long is java.time's | each set; `Long.MIN_VALUE`, `Long.MIN_VALUE + 1`, -62135596800000000 (0001-01-01), -12219292800000000 (1582-10-15), `atOf(0) - 1` where `size() > 0`, 253402300799999999 and 253402300800000000 (the year 9999's end), `Long.MAX_VALUE - MAX_OFFSET_MICROS`, `Long.MAX_VALUE` | the same | an overflow in the reduction by cycles; an offset before the first change taken from the first change's other side |
| 4 | a rule change 1, 2, 10, 100 and 700 cycles on, and in the last cycle of a long, is java.time's | each set with `block() > 0`; each block change `r`; `x = atOf(r) + k * CYCLE_MICROS` for `k` of 1, 2, 10, 100, 700 and `(Long.MAX_VALUE - atOf(r)) / CYCLE_MICROS`: the instants `x - 1` and `x` | the same; and at least one set of `sets()` has `block() > 0` | a cycle that is a day short; an image indexed into the wrong year of the block |
| 5 | 25000 random instants a set, by seed, are java.time's | each set; 20000 uniform over the set's domain, then 5000 from `random.nextLong()`, every long | the same | a stretch between changes that the edges do not reach |
| 6 | every second for two hours either side of each change from 2026 to 2040 | each set; each change `r` below `size()` with `1767225600000000 <= atOf(r) < 2240611200000000` (2026-01-01 to 2041-01-01); `atOf(r) + s * 1000000` for `s` from -7200 to 7200 | the same; and among `sets()` at least one set with such a change has `block() > 0` and at least one has `block() == 0` (a stored change in the future) | a zone whose change in the future comes from a rule in one JDK and from the stored list in the next |
| 7 | `changes` counts a change at `greatest` and not one at `least` | each set; 200 pairs `(least, greatest)`, each end uniform over the set's domain, in the order drawn; for each of 20 random changes, with `t = atOf(r)`, the pairs `(t, t)`, `(t - 1, t)`, `(t, t + 1)`, `(t + 1, t)`; and `(Long.MIN_VALUE, Long.MAX_VALUE)` | 0 when `greatest <= least`; else the length of a walk of `nextTransition` from `least` to `greatest`, stopped at 4096 | an end of the range off by one, which is a step the kernel lacks or one too many |
| 8 | `toLocalArgs` writes the base, the steps in order and the padding, and a scalar step loop over them is java.time's local time | each set; for each of 20 random changes and each `k` of 0, 1, 2 and 8, the range of `k` changes at `r`, into a `new long[23]` filled with -1, with `first = 3` and `steps = 8` (the program is indexes 3 to 19); and, for each of those 20 changes, the inverted pair `(atOf(r) + 5, atOf(r) - 5)` with the same array, `first` and `steps` | `args[3]` is `javaOffset(least) * 1000000`; for `x` of `least`, `greatest` and, for each change of the range, `at - 1`, `at`, `at + 1`: `x + args[3] + sum over i of (x >= args[4 + 2 * i] ? args[5 + 2 * i] : 0)` equals `x + javaOffset(x) * 1000000`; steps `k` to 7 are `Long.MAX_VALUE`, 0; indexes 0 to 2 and 20 to 22 are still -1; for `k >= 1` the same call with `steps = k - 1` throws `IllegalArgumentException`; and for the inverted pair nothing throws, `args[3]` is `javaOffset(least) * 1000000`, all 8 steps are `Long.MAX_VALUE`, 0, and indexes 0 to 2 and 20 to 22 are still -1 | a difference written as the new offset; a step left from the batch before; a write outside the program |
| 9 | made-up rules that the layout cannot hold are not served, each with its reason | `outOfYear()`, `outOfOrder()`, `brokenChain()`, `tooFar()` | for `outOfYear()` first the premise: `rules.getOffset(Instant.parse("2001-01-01T00:00:00Z"))` is -01:00, though the rule's change at that instant goes to +00:00; then, for each, `build` throws nothing, `served()` is false and the failure starts with, in order: `java.time differs at instant`, `changes out of order`, `a rule's offset before is not the offset in force`, `a change outside a long of microseconds` | a check that passes everything; a build that throws inside a compile |
| 10 | a zone with no change is not fixed unless java.time says so | `standardOnly()`; `rulesOnly()`; `ZoneOffset.ofHoursMinutes(5, 30).getRules()`. The five instants: `Long.MIN_VALUE`, 0, 1751328000000000 (2025-07-01), 1893456000000000 (2030-01-01), `Long.MAX_VALUE` | `standardOnly()`: `fixed()` false, `size()` 0, served, `offsetSecondsAt` 7200 at the five instants. `rulesOnly()`: first the premise, `rules.getOffset(Instant.parse("2030-07-01T00:00:00Z")).getTotalSeconds()` is 20700, the base offset, though a rule would have changed it; then `fixed()` false, `size()` 0, `block()` 0, served, 20700 at the five instants. The offset: `fixed()` true, `size()` 0, `check().failure()` null, `changes(Long.MIN_VALUE, Long.MAX_VALUE)` 0, and `toLocalArgs(0, 1, 2, new long[5], 0)` writes 19800000000, `Long.MAX_VALUE`, 0, `Long.MAX_VALUE`, 0 | a zone with one offset that declines; rules read where `java.time` does not read them |
| 11 | 400 years are 146097 days, a whole number of weeks, and the made-up zone holds one cycle | - | `CYCLE_MICROS == 146097L * 86400 * 1000000`; `146097 % 7 == 0`; `LocalDate.of(2400, 1, 1).toEpochDay() - LocalDate.of(2000, 1, 1).toEpochDay() == 146097`; the entry of `threeRules()` has `size()` 1201 and `bytes()` 14416 | a block of 399 or 401 years |

**The catchers.** `zone.rankLtForLe`: test 2. `zone.changesCountsLeast`: test 7.
`zone.noPadding` and `zone.deltaIsAfter`: test 8. `zone.skipC4`: test 9.
`zone.cycleDayShort`: test 11. Each body fails by an assertion, through `eachSet`'s
`Assertions.fail` or an `assertEquals`. No scalar case takes a species, so each body method
has no argument.

**Commands.** The oracle of 5.1; `grep -c '<id>fault-' pom.xml` prints `F0 + 6`;
`mvn -q test -Dtest=ZoneDataTest` prints one line that starts `checked: `. In Varka's
checkout, this prints only lines that a port's edit explains (the package, the names, the
header, the `requireNonNull` line and its import, the fault constants); a line that no edit
of step 1 explains is a stop:

    git -C /home/max/proj/vecbricks-varka show $A:$VM/VarkaChronoZoneChanges.java | \
        sed 's/VarkaChronoZoneChanges/ZoneData/g' | diff - M/ZoneData.java

**The cut line.** If the size passes 900: the made-up rules that are not served
(`outOfYear()`, `outOfOrder()`, `brokenChain()`, `tooFar()`), test 9 and the fault
`zone.skipC4` leave this part and become `[VECHRONO-<issue>] Hold with made-up rules what
the zone layout refuses`, branch `vechrono-l3b-made-up-rules`, before L4. Row 678's part a
has the same cut line.

**Done when** the oracle passes on the four CI rows and the pull request holds test 1's
line. A zone of the JDK that the check refuses is a stop and a finding for the owner, never
a check to relax. **Not here:** no local line, no cache, no vector, no `PROOFS.md` (5.6).

#### L4. `ZoneData` on the local line, ported

*Title:* `[VECHRONO-<issue>] Give each change its window on the local time line`.
*Branch:* `vechrono-l4-local-line`.

**B0.** `grep -c 'public static ZoneData build(ZoneRules rules)' M/ZoneData.java` prints `1`;
`grep -c 'localChanges\|toInstantArgs' M/ZoneData.java` prints `0`. Then, in every shell of
this part:

    export VM=sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka
    export VT=sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka
    git -C /home/max/proj/vecbricks-varka fetch -q origin master
    git -C /home/max/proj/vecbricks-varka show origin/master:$VM/VarkaChronoZoneChanges.java | grep -c 'enum Rule'
    git -C /home/max/proj/vecbricks-varka log --format=%s origin/master -- $VM/VarkaChronoZoneChanges.java | grep -vc '^\[VARKA-678\]'

The first `grep` prints `1`. The second prints `0`: every commit that touched the file is
row 678's. Another count is a stop: a later row has changed the file, and the lead decides
which commit the library ports.

**Steps.**

1. `M/ZoneData.java` becomes Varka's file as it is on `origin/master`, whole, with L3's
   edits of a port made again and these more:
   - the nested `enum Rule` is removed and every `Rule` becomes the library's top-level
     `Resolve`;
   - `toInstantArgs` tests the clamp first and throws 4.8's own text for it (4.2.10); the
     test of `c` against `steps` comes second;
   - L3's six fault constants stay, and six join them: `zone.hiMinForMax` (`hiOf` takes the
     lesser offset), `zone.keptLoForGap` (`KEPT` writes `loOf(r)` for a gap),
     `zone.gapEndLoForOverlap` (`GAP_END` writes `loOf(r)` for an overlap), `zone.skipC6` (C6
     not run), `zone.noClamp` (`localChanges` does not return `MANY` outside the clamp),
     `zone.offsetsStartAfter` (`changeOffsets` starts with the offset after the first
     change). Row 678 plants these six by hand.
2. `T/ZoneFixtures.java` and `T/ZoneDataTest.java` become Varka's files as on
   `origin/master`, with L3's edits made again; `T/ZoneDataLocalTest.java` is Varka's
   `VarkaChronoZoneLocalSuite.java`, ported the same way. In test 5 the clamp's exception is
   asserted by the library's text.
3. `pom.xml`: six executions.

**What `T/ZoneFixtures.java` gains in this part,** the helpers of the local oracle:

- `static LocalDateTime local(long micros)`: `LocalDateTime.ofEpochSecond(Math.floorDiv
  (micros, 1_000_000L), (int) Math.floorMod(micros, 1_000_000L) * 1000, ZoneOffset.UTC)`;
  `static long micros(Instant i)`: seconds times 1,000,000 plus `getNano() / 1000`, exact.
- `static long earlier(long[] a, int f, int steps, long L)`, `kept(a, f, steps, L, long
  keptMicros)` and `gapEnd(a, f, steps, L)`: the three scalar meanings of 4.2.5, over
  arguments at `f`. Every argument and result is in microseconds, the kept offset too; the
  oracle's `ZoneOffset` is `ZoneOffset.ofTotalSeconds((int) (keptMicros / 1_000_000L))`.
- `static ZoneRules touching()`: two stored changes whose windows overlap, not served:

      ZoneOffset one = ZoneOffset.ofHours(1);
      ZoneOffset two = ZoneOffset.ofHours(2);
      var gap = ZoneOffsetTransition.of(LocalDateTime.of(2000, 3, 26, 2, 0), one, two);
      var back = ZoneOffsetTransition.of(LocalDateTime.of(2000, 3, 26, 3, 30), two, one);
      return ZoneRules.of(one, one, List.of(), List.of(gap, back), List.of());

**Tests.** `ZoneDataLocalTest`, JUnit 5. They are row 678's
`VarkaChronoZoneLocalSuite`, by number. `sets()` is `Inputs.sets()`. "Each set" is
`sets()`, the JDK's own, with `zone = ZoneId.of(set.id())`, since the oracles are
`java.time`'s methods over a `ZoneId`. Unless a line says otherwise the arguments are filled
by `toInstantArgs(rule, L - 86400000000, L + 86400000000, 8, args, 0)` into a `new
long[33]`, a day either side of the local time `L`; a local time whose range of a day either
side touches more than 8 windows is skipped and counted, and the count is asserted to be 0
for `sets()`. "The edges of a window", for a change `r` with `lo = loOf(r)` and `hi =
hiOf(r)`, are `lo - 1000000`, `lo - 1`, `lo`, `lo + 1`, `lo + (hi - lo) / 2`, `hi - 1`, `hi`,
`hi + 1`, `hi + 1000000`; "every window" is each change `r` from 0 to `size() - 1`. The
three oracles, with `ldt = local(L)`: *atZone* is `micros(ldt.atZone(zone).toInstant())`;
*ofLocal* is `micros(ZonedDateTime.ofLocal(ldt, zone, ZoneOffset.ofTotalSeconds((int)
(keptMicros / 1000000))).toInstant())`; *gap's end* is, with `t = rules.getTransition(ldt)`,
`t.toEpochSecond() * 1000000` when `t != null && t.isGap()`, else atZone.

| # | test | inputs | expected, and its oracle | the failure only it catches |
| ---: | :--- | :--- | :--- | :--- |
| 1 | each window is a gap or an overlap as java.time has it | each set, each change `r` whose `secondsOf(r)` and `secondsOf(r + 1)` differ: the local times `local(lo)`, `local(hi - 1)` (inside) and `local(lo - 1)`, `local(hi)` (outside) | inside: `rules.getValidOffsets` has 0 elements where `secondsOf(r + 1) > secondsOf(r)` and 2 where it is less, and `rules.getTransition(...).toEpochSecond() * 1000000` is `atOf(r)`; outside: 1 element | a window on the wrong side of its change |
| 2 | the earlier-offset rule at the edges of every window is `LocalDateTime.atZone` | each set, the edges of every window, `Resolve.EARLIER` | `earlier(args, 0, 8, L)` equals atZone | `min` for `max` in a threshold |
| 3 | the kept-offset rule is `ZonedDateTime.ofLocal` for the offset before, the offset after and a third | each set, the edges of every window, `Resolve.KEPT`, with `keptMicros` each of `secondsOf(r) * 1000000`, `secondsOf(r + 1) * 1000000` and `(secondsOf(r) + 3600) * 1000000`; the third is skipped where `secondsOf(r) + 3600 > 64800` | `kept(args, 0, 8, L, keptMicros)` equals ofLocal | a kept offset honoured inside a gap; a kept offset ignored inside an overlap |
| 4 | the gap's-end rule is the change's instant inside a gap, `atZone` elsewhere, and `LocalDate.atStartOfDay` at a midnight | each set, `Resolve.GAP_END`: the edges of every window, and the two midnights `Math.floorDiv(lo, 86400000000) * 86400000000` and `Math.floorDiv(hi, 86400000000) * 86400000000` | `gapEnd(args, 0, 8, L)` equals gap's end; at each of the two midnights it also equals `micros(local(L).toLocalDate().atStartOfDay(zone).toInstant())`; prints `midnights strictly inside a gap: <count>`, the midnights `L` with `lo < L < hi` in a gap | an overlap treated as a gap; the one rule Spark's cast from `DATE` reads |
| 5 | the three rules in the far past, the far future and at the clamp | (i) each set with `block() > 0`: the edges of every block change's window, each plus `k * CYCLE_MICROS` for `k` of 1, 10 and 700. (ii) each set: the local times -62135596800000000, -12219292800000000 and 253402300799999999. (iii) each set, with `E = Long.MAX_VALUE - 2 * MAX_OFFSET_MICROS` and `S = Long.MIN_VALUE + 2 * MAX_OFFSET_MICROS`: the four ranges `(0, E + 1)`, `(S - 1, 0)`, `(E, E)` and `(S, S)` | (i) and (ii): the three rules against their oracles as in tests 2 to 4, with `keptMicros = secondsOf(r) * 1000000` in (i) and 0 in (ii). (iii), first two ranges: `localChanges(least, greatest)` is 4096, and `toInstantArgs(Resolve.EARLIER, least, greatest, 8, new long[17], 0)` throws `IllegalArgumentException`. (iii), last two, with `L` the one value: `localChanges(L, L)` is 0 or 1, `toInstantArgs(Resolve.EARLIER, L, L, 8, new long[17], 0)` throws nothing, and `earlier(args, 0, 8, L)` equals atZone | a threshold that wraps near an end of a long and fires for every row |
| 6 | 20000 random local times a set, by seed, under the three rules | each set; 10000 times `L` uniform from `loOf(r) - 10800000000` to `hiOf(r) + 10800000000` of a random change `r`, with `keptMicros` one of `secondsOf(r) * 1000000` and `secondsOf(r + 1) * 1000000` by `random.nextBoolean()`; then 10000 times `L` uniform over the set's domain with `keptMicros = offsetSecondsAt(L) * 1000000`; a set of `size()` 0 takes 20000 of the second kind | the three rules against their three oracles | a local time between windows that the edges do not reach |
| 7 | `localChanges` counts the windows a range touches | each set; 200 pairs with each end uniform over the set's domain, in the order drawn; and, for each of 20 random changes with `lo = loOf(r)`, `hi = hiOf(r)`: `(lo, lo)`, `(lo - 1, lo - 1)`, `(hi, hi)`, `(hi - 1, hi - 1)`, `(hi, lo)` | 0 when `greatest < least`; else the count, stopped at 4096, of the transitions `t` of a walk of `nextTransition` from `least - 2 * MAX_OFFSET_MICROS - 1000000` to `greatest + 2 * MAX_OFFSET_MICROS` for which, with `T = t.toEpochSecond() * 1000000` and `b`, `a` its two offsets in microseconds, `T + Math.max(b, a) > least` and `T + Math.min(b, a) <= greatest` | a window that starts inside the range and ends outside it left out, so that a kept offset there is missed |
| 8 | `toInstantArgs` keeps its order, pads, and throws past its steps | the entry of `ZoneId.of("America/Los_Angeles").getRules()`; `least = 1735689600000000` (2025-01-01T00:00 as a local long), `greatest = 1767139200000000` (2025-12-31T00:00); each rule into a `new long[24]` filled with -1, `first = 2`, `steps = 4` | `localChanges` is 2. `args[2]` is -28800000000 for each rule. `EARLIER` (indexes 2 to 10): step 0 is 1741489200000000 (03:00 on 9 March), 3600000000; step 1 is 1762048800000000 (02:00 on 2 November), -3600000000; steps 2 and 3 are `Long.MAX_VALUE`, 0; indexes 11 to 23 are -1. `GAP_END` (2 to 14): step 0 is 1741489200000000, 1741485600000000 (02:00), 3600000000; step 1 is 1762048800000000, 1762048800000000, -3600000000; padding `Long.MAX_VALUE`, `Long.MAX_VALUE`, 0. `KEPT` (2 to 18): step 0 is 1741489200000000, 1741489200000000, -25200000000, 3600000000; step 1 is 1762048800000000, 1762045200000000 (01:00), -28800000000, -3600000000; padding `Long.MAX_VALUE`, `Long.MAX_VALUE`, 0, 0. Indexes 0, 1 and those past each program are -1. With `steps = 1` each rule throws `IllegalArgumentException` | two arguments of a step swapped, which every rule's sweep could miss only by luck |
| 9 | `wholeMinutesFrom` and `wholeHoursFrom` are the change that ends the last odd offset | each set, with `g` of 60 and of 3600 | from `java.time` alone: `Long.MAX_VALUE` if any rule of `rules.getTransitionRules()` has a `getOffsetBefore()` or `getOffsetAfter()` that is no multiple of `g` and `rules.getTransitions()` is not empty; else, walking `rules.getTransitions()` from its last element to its first: `Long.MAX_VALUE` if the last element's `getOffsetAfter()` is no multiple; the instant in microseconds of the first element met whose `getOffsetBefore()` is no multiple; `Long.MIN_VALUE` if none is met. With no stored change: `Long.MIN_VALUE` if `rules.getOffset(Instant.EPOCH)` is a multiple, else `Long.MAX_VALUE`. Prints `whole minutes from: <the greatest value under Long.MAX_VALUE over sets()> (<id>)` | an instant from which `second` would be read with no offset, one change early |
| 10 | `changesAt` and `changeOffsets` give the changes of 1900 to 2100 as a walk of `nextTransition` does, and `memo` builds once | (i) each set, `from = -2208988800000000`, `until = 4102444800000000`. (ii) the entry of `standardOnly()`, the same range; and each set's `changeOffsets(Long.MIN_VALUE, Long.MIN_VALUE + 1)`. (iii) a new entry `build(ZoneId.of("Europe/Podgorica").getRules())`: 8 threads, started by one latch, each call `memo` with one builder that adds 1 to an `AtomicInteger` and returns `new long[] {7}`. (iv) another new entry of the same rules: `memo` twice, with a builder that counts and returns `new long[0]` | (i): `changesAt` holds, element for element, `T * 1000000` of a walk of `nextTransition` from `from - 1` to `until - 1`; `changeOffsets` is one longer and holds `javaOffset(from - 1)`, then each walked transition's `getOffsetAfter().getTotalSeconds()`. (ii): `changesAt` is empty and `changeOffsets` is `{7200}`; `changeOffsets(Long.MIN_VALUE, Long.MIN_VALUE + 1)` has one element, `javaOffset(Long.MIN_VALUE)`, and throws nothing. (iii): the 8 results are one array object and the counter reads 1. (iv): both results are one empty array object and the counter reads 1 | a table packed from changes that the steps do not hold; an overflow at the least long |
| 11 | made-up rules whose two windows touch are not served | `touching()` | `served()` false, and the failure starts with `local windows touch` | a zone for which one local time is in two windows |

Test 8's values for `America/Los_Angeles` in 2025 are of today's tzdb; they are the one
place a value of the JDK's data is written down, as row 678's test does.

**The catchers.** `zone.hiMinForMax`: test 2. `zone.keptLoForGap`: test 3.
`zone.gapEndLoForOverlap`: test 4. `zone.noClamp`: test 5, whose body asserts the 4096
first. `zone.offsetsStartAfter`: test 10. `zone.skipC6`: test 11.

**Commands.** The oracle of 5.1; `grep -c '<id>fault-' pom.xml` prints `F0 + 6`. The `diff`
of L3's commands, with `origin/master` for `$A` and a second `sed` expression
`s/\bRule\b/Resolve/g`, prints only lines that a port's edit explains (L3's, the removed
enum, the clamp's test and text, the twelve fault constants); another line is a stop.

**The cut line.** If the size passes 900: `wholeMinutesFrom()`, `wholeHoursFrom()` and test
9 leave this part and become `[VECHRONO-<issue>] Say from when a zone's offsets are whole
minutes and whole hours`, branch `vechrono-l4b-grains`, which no part of this row needs.

**Done when** the oracle passes on the four CI rows; `ZoneDataTest` 1 still passes with C6
and C7 in the build (a zone they refuse is a stop). **Not here:** no cache, no vector form,
no `ZoneTable`.

#### L5. `Zones`, the cache, ported

*Title:* `[VECHRONO-<issue>] Keep one entry for each set of zone rules in a JVM`.
*Branch:* `vechrono-l5-zones`.

**B0.** `ls M/Zones.java` fails. `grep -c 'public long\[\] memo(' M/ZoneData.java` prints `1`.
Then, in every shell of this part:

    export VM=sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka
    export VT=sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka
    git -C /home/max/proj/vecbricks-varka fetch -q origin master
    git -C /home/max/proj/vecbricks-varka log --format=%s origin/master -- $VM/VarkaChronoZones.java | grep -vc '^\[VARKA-678\]'

The last command prints `0`; another count is a stop, as in L4.

**Steps.**

1. `M/Zones.java`: Varka's `VarkaChronoZones.java` as on `origin/master`, with the edits of
   a port (the package, the names, the header), one line more, `of` starts with
   `Objects.requireNonNull(rules, "rules");` (with the import of `java.util.Objects`; L6's
   `refusals` expects the message `rules`), and two fault constants: `zones.noMap` (`of`
   returns `ZoneData.build(rules)` and reads no map) and `zones.keepsFixed` (`of` keeps a
   fixed zone). It has `of(ZoneRules)`, `cached()` and `clearForTesting()`, and no
   `of(ZoneId)`: that is 5.6's.
2. `M/ZoneData.java`: one fault constant more, `zone.fillAllocates`: under it `toLocalArgs`
   first stores `new long[steps + 1]` into a `static long[] sink` field, so that the
   allocation cannot be optimised away.
3. `T/ZonesTest.java`: Varka's `VarkaChronoZonesSuite.java`, ported as L3's tests are, all
   seven tests. In test 7 Varka's sampler becomes the JDK's own counter: `var bean =
   (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()`; the test is
   skipped by `assumeTrue(bean.isThreadAllocatedMemorySupported())`; a reading is
   `bean.getThreadAllocatedBytes(Thread.currentThread().threadId())`; the allowance stays
   4096 bytes over the 1000 rounds.
4. `pom.xml`: three executions.

**Tests.** `ZonesTest`, JUnit 5; they are row 678's `VarkaChronoZonesSuite`'s seven, by number. It
clears a static cache: surefire runs the library's test classes one after another in one JVM, and
no other class reads `cached()`. JUnit fixes no order of tests, so tests 1, 2, 3 and 4 each start
with `Zones.clearForTesting()`, and no test reads what another left. `F` is the count of sets of
`sets()` whose `rules.isFixedOffset()` is false.

| # | test | inputs | expected | the failure only it catches |
| ---: | :--- | :--- | :--- | :--- |
| 1 | ids with the same rules share one entry | after the clear: `sets()`, every id of each, `e(id) = Zones.of(ZoneId.of(id).getRules())` | for a set that is not fixed, `e(id)` is the same object for every id of the set, and no two such sets share an object; for a fixed set, every `e(id)` is `fixed()` (each call may give a new object); then `cached()` is `F` | an entry for each id: 604 builds for 349 sets of rules |
| 2 | a fixed offset is built and not kept | after the clear: `ZoneOffset.ofTotalSeconds(s).getRules()` for `s` from 0 to 999 | each entry is `fixed()` with `offsetSecondsAt(0) == s`; `cached()` is 0 after them | a cache that a query with many offsets grows without bound |
| 3 | sixteen threads asking for every zone get one entry a set of rules | after the clear: 16 threads, started by one `CountDownLatch`, each asking `of(ZoneId.of(id).getRules())` for every id of `ZoneId.getAvailableZoneIds()` in an order shuffled by `new Random(seed() + thread)`, each keeping its results in a map by id | for every id whose rules are not fixed, the 16 threads hold the same object; `cached()` is `F`; no thread threw | two entries of one zone, each with a table of its own |
| 4 | an entry that failed its check is kept and not built again | after the clear: `rules = outOfYear()`, one object, passed to `of` twice | the two results are the same object, `served()` is false, `cached()` is 1 | a zone checked again on every compile |
| 5 | an entry does not serialize | `ZoneData.class`, `Zones.class` | neither is assignable to `java.io.Serializable` | an entry that a task's closure carries from the driver |
| 6 | the size of every entry, printed | `sets()`, each built by `ZoneData.build` (the cache is not read) | `bytes() == 12L * size() + 4` for each; prints `sizes: <sets> entries, <changes> changes, <bytes> bytes; the largest <id>, <n> changes, <bytes> bytes; America/Los_Angeles <n> changes, <bytes> bytes` | - (it is the record of the row's size) |
| 7 | the methods a batch calls allocate nothing | the entry of `America/Los_Angeles`, two `long[33]`; one round is `changes(1735689600000000, 1767139200000000)`, `toLocalArgs` of that range with 8 steps, `localChanges` of that range, and `toInstantArgs(Resolve.KEPT, ...)` of it with 8 steps; and the same four over the year 2825 (both ends plus `2 * CYCLE_MICROS`). The test is skipped by `assumeTrue(bean.isThreadAllocatedMemorySupported())`, with `bean` the JDK's `com.sun.management.ThreadMXBean`. 20000 rounds to warm; then `before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId())`, 1000 rounds, `after` | `after - before <= 4096`, the allowance row 678's test has, over all 1000 rounds | a fill that boxes or allocates for each batch, which is right and slow |

**The catchers.** `zones.noMap`: test 4. `zones.keepsFixed`: test 2. `zone.fillAllocates`:
test 7; under the fault its body is not skipped where the counter is supported, and where
it is not supported the catcher itself fails, saying so, since the fault would go unseen.
Row 678 has a fourth cache fault, `VarkaChronoZoneChanges implements java.io.Serializable`.
The library cannot hold it as a switch: a class's interfaces are fixed when it is compiled,
and no system property changes them. Test 5 holds the same thing with no fault run.

**Commands.** The oracle of 5.1; `grep -c '<id>fault-' pom.xml` prints `F0 + 3`;
`mvn -q test -Dtest=ZonesTest` prints one line that starts `sizes: `. In Varka's checkout,
this prints only lines that a port's edit explains (the package, the header, the
`requireNonNull` line and its import, the two fault constants); another line is a stop:

    git -C /home/max/proj/vecbricks-varka show origin/master:$VM/VarkaChronoZones.java | \
        sed 's/VarkaChronoZones/Zones/g;s/VarkaChronoZoneChanges/ZoneData/g' | diff - M/Zones.java

**Done when** the oracle passes on the four CI rows. **Not here:** no `of(ZoneId)`, no
exception class, no vector.

#### L6. `VChrono.toLocal` as steps

*Title:* `[VECHRONO-<issue>] Add toLocal: an instant to its local time, in every lane`.
*Branch:* `vechrono-l6-to-local`.

**B0.** `ls M/VChrono.java` fails. `grep -c 'public void toLocalArgs(' M/ZoneData.java`
prints `1`. `ls M/Zones.java` prints the path. `grep -c 'public' M/ZoneData.java` is above
10: the entry's members that 4.2.1 calls public are public.

**Steps.**

1. `M/ZoneSteps.java` (4.3).
2. `M/ToLocal.java`, package-private: `static LongVector masked(LongVector x, ZoneSteps s)`
   and `static LongVector noMask(LongVector x, ZoneSteps s)`, each a `switch` over
   `s.count()` with the 0, 2 and 8 steps written out, never a loop over the steps; and
   `static LongVector dispatch(LongVector x, ZoneSteps s)`, the choice of 4.4. Four faults:
   `toLocal.gtForGe` (`GT` for `GE` in `masked`), `toLocal.deltaSign` (the delta subtracted
   in `masked`), `toLocal.noMaskOffByOne` (`at - x` for `at - 1 - x` in `noMask`),
   `toLocal.maskedLaneTouched` (the masked public form returns the result unblended).
3. `M/VChrono.java`: the two `toLocal` forms over `ZoneSteps` and the scalar `toLocal(long,
   ZoneData)`, with the Javadoc of 4.4 and 4.6.
4. `T/ToLocalTest.java`, its catchers, four executions.
5. `README.md`: the section "Use": a client's `pom.xml` with the dependency, and a
   `Main.java` that gets `Zones.of(ZoneId.of("America/Los_Angeles").getRules())`, finds the
   least and
   greatest of an array, builds `ZoneSteps.of(zone, least, greatest)`, converts the array
   through `VChrono.toLocal` in a loop of the preferred species with a scalar tail through
   `VChrono.toLocal(long, ZoneData)`, and prints whether every element equals `java.time`'s.
   `CHANGELOG.md`: one line under `Unreleased`, in a `### Added` list.

**Tests.** Every case runs at each `VectorWidth`; a width the host lacks is skipped. A
helper of the test converts an array through a form in vectors of the species, the tail
padded with the last element.

| case of `ToLocalTest` | inputs | expected, and its oracle | the failure only it catches |
| :--- | :--- | :--- | :--- |
| `everyZoneAtEveryChange` | each set of `Inputs.sets()`; for each stored change a range from the change before it to the change after it; lanes at each `T`, one microsecond and one second either side, and from two hours before to two hours after at a step of 60 s | `VChrono.toLocal` equals `Oracle.toLocal` in every lane | `>` for `>=` (`toLocal.gtForGe`) |
| `everyCount` | `hardZones()`; a week, a year and a range of 5 changes; forced counts of 0, 2 and 8, each only where it is not below the range's changes | the same lanes from each count, and the oracle's | a delta's sign (`toLocal.deltaSign`), padding that fires |
| `armsAgree` | the inputs of the first case | `ToLocal.noMask` equals `ToLocal.masked`, called directly, on every host; the body asserts `noMaskExact()` of each range first | the shift form one off (`toLocal.noMaskOffByOne`) |
| `noMaskIsRefusedOnAWideRange` | `ZoneSteps.of(zone of +01:00, Long.MIN_VALUE, Long.MAX_VALUE)` | `noMaskExact()` is false, and `dispatch` returns what `masked` returns | the arm with no mask outside its domain |
| `secondWordPassesThrough` | `hardZones()`; 4096 seeded first words in the year 2025, each with each of `Inputs.secondWords()` | `Oracle.check` with the input's second words as `gotNanos`: `java.time`'s local time to the nanosecond has the form's first word and the input's second word | a form that would need the second word and does not take it |
| `maskedLanes` | `Inputs.masks`, on a year of `America/Los_Angeles` | `checkMasked`: live lanes equal the oracle, the others their input | a lane that is not live computed (`toLocal.maskedLaneTouched`) |
| `ruleEra` | each set with `block > 0`: the year 2437 and the year 9999, 4096 seeded instants each | the oracle | steps built from the block wrongly |
| `endsOfTheRange` | `Inputs.ends()`, each in a range of itself, in `hardZones()` | the oracle, wrapping | a broadcast of a wrapped threshold |
| `tooManyChanges` | ten years of `America/Los_Angeles` | `ZoneSteps.of` throws 4.8's first text, with the count that `changes` returns for the range | a silent truncation to 8 steps |
| `scalarTwin` | 1000 seeded instants in `hardZones()` | `VChrono.toLocal(long, ZoneData)` equals `Oracle.toLocal` | - |
| `refusals` | `ZoneSteps.of` with a count of 3; with the entry of `ZoneFixtures.outOfYear()`; `ZoneData.build(null)`, `Zones.of((ZoneRules) null)`, `ZoneSteps.of(null, 0, 1)`; `VChrono.toLocal` with a null vector, null steps and a null mask | 4.8's texts for the first two; `NullPointerException` whose message is the parameter's name (`rules`, `zone`, `instant`, `steps`, `live`) for the rest | a refusal that 4.8 promises and no test holds |

**Commands.** The oracle of 5.1; `grep -c '<id>fault-' pom.xml` prints `F0 + 4`.

**The cut line.** If the size passes 900: the arm with no mask (`ToLocal.noMask`,
`noMaskExact()`'s use in `dispatch`, `armsAgree`, `noMaskIsRefusedOnAWideRange` and the fault
`toLocal.noMaskOffByOne`) leaves this part and becomes a pull request of its
own, `[VECHRONO-<issue>] Add toLocal's steps with no mask, for two lanes on x86`, branch
`vechrono-l6b-no-mask`; `dispatch` then always takes `masked` until it merges, and L7 needs
it.

**Done when** the oracle passes on the four CI rows. **Not here:** no table, no benchmark.

#### L7. The benchmarks' frame

*Title:* `[VECHRONO-<issue>] Time toLocal against java.time, and fail CI on an allocation`.
*Branch:* `vechrono-l7-benchmarks`.

**B0.** `ls src/jmh 2>/dev/null | wc -l` prints `0`; `ls bench 2>/dev/null | wc -l` prints `0`.

**Steps.**

1. `pom.xml`: the profile `jmh` of vfdlibm's `pom.xml` (the `exec-maven-plugin` block that
   runs `org.openjdk.jmh.Main ${jmh.args}` on the test class path with `--add-modules`).
2. `src/jmh/java/io/github/vecbricks/vechrono/ToLocalBenchmark.java`.
   `@Param({"week", "year", "casablanca"}) inputs`: 4096 instants in no order, from
   `new SplittableRandom(679)`: a week of July 2025 and the year 2025 of
   `America/Los_Angeles` (0 and 2 changes), the year 2025 of `Africa/Casablanca` (8 steps).
   `setUp` throws `IllegalArgumentException` on any other value, builds the steps from the
   array's least and greatest, and prints `# tzdb <Inputs' call>` once. Rows, each a loop
   over the array at its species with a scalar tail: `javaTimeOffset` (the oracle's call once
   a row), `scalarZoneData` (`VChrono.toLocal(long, ZoneData)`), the public form as
   `steps256`, `stepsPreferred` and `stepsMax`; `kernelShaped256` and `kernelShapedPreferred`
   (the masked arm written out in the benchmark: `base`, each `at` and each `delta` broadcast
   before the loop, then compares and masked adds, as a Varka kernel holds them);
   `noMaskPreferred` (`ToLocal.noMask` called directly); `stepsOf8Preferred` (a forced count
   of 8); `maskedPreferred` (the masked public form under a mask with one lane in ten off).
3. `bench/alloc-check.py`: vfdlibm's first version (`git -C /home/max/proj/vfdlibm show
   9965b6c:bench/alloc-check.py`, 100 lines), with these edits and no other:
   - `INCLUDE = r".*Benchmark\.\w+(256|Preferred|Max)$"`; the rows `javaTimeOffset` and
     `scalarZoneData` end in none of the three and are left out by it;
   - `INLINE = "-XX:CompileCommand=inline,io.github.vecbricks.vechrono.*::*"`;
   - `JMH_OPTIONS = "-f 1 -wi 3 -w 1s -i 3 -r 1s -prof gc"`, with no `-p`: every `inputs`
     value of every included row runs, and the script's label prints the value;
   - the docstring says which rows it runs and that a 128-bit run on x86 is left to the
     128-bit CI row's tests, since a masked body boxes there.
   It ends with `No vector body allocates 1.0 B/op or more.`, or exits 1 after a `FAIL` line
   for each row at or over 1 B/op.
4. `ci.yml`: a job `alloc`, named `alloc (${{ matrix.name }})`, with two rows, `x86 as
   drawn` (`bench/alloc-check.py`) and
   `x86 as AVX2` (`bench/alloc-check.py --jvm-args "-XX:UseAVX=2 -XX:MaxVectorSize=32"`),
   with the checkout, Java and cache steps of `build`.
5. `AGENTS.md`: "Measurements, not adjectives", section 7's rule.

**Tests.** `T/ToLocalBenchmarkTest.java` (JUnit 5): `everyRowAnswersTheOracle` runs each row's
method once for each `inputs` value and compares the output array with `java.time` through
`Oracle.check`, with second words of 0 in and out; `unknownInputsThrow`. The failure only it
catches: a benchmark row that times a wrong answer.

**Commands.** The oracle of 5.1. `mvn -B -Pjmh test-compile exec:exec
-Djmh.args="ToLocalBenchmark.steps256 -p inputs=year -f 1 -wi 1 -i 1"` prints one JMH result
line that starts `ToLocalBenchmark.steps256` (a smoke run; its number is not recorded). In
CI, the job `alloc` prints `No vector body allocates 1.0 B/op or more.` in both rows.

**The cut line.** If the size passes 900: `bench/alloc-check.py` and the job `alloc` leave
this part and become `[VECHRONO-<issue>] Fail CI when a form's vector body allocates`,
branch `vechrono-l7b-alloc-check`, which L14 then needs.

**Done when** CI is green with the job `alloc`, and the owner has added its two checks to
`main`'s rule (P0 item 2a). If `alloc` fails on a `steps*` row and passes on the
`kernelShaped*` rows, stop and report: prediction 3 failed, and the owner decides the API
(risk 2). **Not here:** no results file; the host runs are L17.

#### L8. The table form

*Title:* `[VECHRONO-<issue>] Add the table form of toLocal: a batch that spans many years`.
*Branch:* `vechrono-l8-table`.

**B0.** `ls M/ZoneTable.java` fails. `grep -c 'static LongVector toLocal(' M/VChrono.java`
prints `2`. `grep -c 'public long\[\] memo(' M/ZoneData.java` prints `1`.

**Steps.**

1. `M/ZoneTable.java`, all of 4.5 but the vector form.
2. `M/TableLookup.java`: `static LongVector gather(LongVector x, ZoneTable t, int[] scratch)`, the
   14 calls of 4.5, and `static LongVector scalarLoads(LongVector x, ZoneTable t, long[]
   scratch)`. Faults: `table.unclampedIndex` (call 4 left out), `table.halvesSwapped` (calls 10
   and 11 pick the other half).
3. `M/VChrono.java`: the two `toLocal` forms over a `ZoneTable`.
4. `T/TableTest.java`, its catchers, two executions; `CHANGELOG.md`.

**Tests.** Every vector case at each `VectorWidth`.

| case of `TableTest` | inputs | expected, and its oracle | the failure only it catches |
| :--- | :--- | :--- | :--- |
| `everyZoneAtEveryChange` | each set with a table; each change of `changesAt(ORIGIN, LIMIT)`: `T`, one microsecond either side; and 10,000 seeded instants a set over the range | `gather`, `scalarLoads` and the scalar `toLocal` each equal `Oracle.toLocal` | the two halves swapped (`table.halvesSwapped`) |
| `everyZoneHasATable` | each set | `ZoneTable.has`; prints the sets without one, with the bucket | a tzdb update with two changes in a bucket, seen the night it comes |
| `edgesOfTheRange` | `ORIGIN`, `LIMIT - 1`, the first and last instant of buckets 0 and 1, and the first instant of bucket 11480 (its last is past `LIMIT - 1`, outside the table's range, and is not tried), in `hardZones()` | the oracle | an index one bucket off |
| `notLiveLanesOutsideTheTable` | the masked form; lanes that are not live hold `Long.MIN_VALUE`, `Long.MAX_VALUE`, `ORIGIN - 1`, `LIMIT`; the body wraps the call in `assertDoesNotThrow` | no exception, and those lanes keep their input | an index that is not clamped (`table.unclampedIndex`), which would throw `IndexOutOfBoundsException` |
| `tableEqualsSteps` | `hardZones()`, the year 2025 | the table's lanes equal the steps' lanes | a second definition that drifts |
| `packWords` | `word(-28800, -25200)`, `word(20700, 20700)`, `word(64800, -64800)`; then `word(64801, 0)` and `word(0, -64801)` | `offsetMicros` gives back each half times 1000000; `index(Long.MIN_VALUE)` and `index(Long.MAX_VALUE)` are from 0 to `BUCKETS - 1`; the last two throw `IllegalArgumentException` | a sign lost in the low half |
| `packRefuses` | `pack` with `offsetSeconds` as long as `at`; with a change at `ORIGIN - 1`; with a change at `LIMIT`; with two changes out of order; with `longs` of `LONGS - 1` | `IllegalArgumentException` for each | a table packed from arrays that do not belong together |
| `refusals` | `ZoneTable.of` and `ZoneTable.has` with the entry of `ZoneFixtures.outOfYear()`; `ZoneTable.of(null)`; `VChrono.toLocal` over a table with a null vector, table, `scratch` and mask | 4.8's text for the first two; `NullPointerException` with the parameter's name for the rest | a refusal that 4.8 promises and no test holds |
| `noTable` | `ZoneRules.of` with two stored changes one hour apart on 1 January 2000, each one hour forward | `has` is false, and `of` throws 4.8's text with the bucket `ZoneTable.index` gives the first change | - |
| `copyToLayout` | `America/Los_Angeles`, into an array filled with -1 at `first = 5` | `LONGS` longs written and nothing else; bucket `b`'s words at `5 + 2 * b` | a layout that Varka's row 686 cannot read |
| `shortScratch` | the table form with an `int[2 * lanes - 1]`; `TableLookup.scalarLoads` with a `long[lanes - 1]` | 4.8's two texts: `scratch holds <n> ints, the species needs <m>` and `scratch holds <n> longs, the species needs <m>`, with the numbers | - |

**Commands.** The oracle of 5.1; `grep -c '<id>fault-' pom.xml` prints `F0 + 2`.

**The cut line.** If the size passes 900: the arm `TableLookup.scalarLoads` and its lines
of `everyZoneAtEveryChange` leave this part and become `[VECHRONO-<issue>] Add the table
lookup by scalar loads, for a host with no gather`, branch `vechrono-l8b-scalar-loads`,
which L14 then needs.

**Done when** the oracle passes on the four CI rows. **Not here:** no table of the local
line (row 693's, after 0.1.0).

#### L9. Local to instant: the earlier offset and the gap's end

*Title:* `[VECHRONO-<issue>] Add toInstant: a local time to an instant, as java.time does`.
*Branch:* `vechrono-l9-to-instant`.

**B0.** `grep -c 'public void toInstantArgs(' M/ZoneData.java` prints `1`;
`grep -c 'static LongVector toInstant' M/VChrono.java` prints `0`; `ls M/LocalSteps.java`
fails.

**Steps.**

1. `M/LocalSteps.java` (4.3), for all three rules: its fields and both `of` forms.
2. `M/ToInstant.java`, package-private: `static LongVector earlier(LongVector l, LocalSteps
   s)` and `static LongVector gapEnd(LongVector l, LocalSteps s)`, each a `switch` over the
   count with 0, 2 and 8 steps written out, with masks (4.4). One fault:
   `toInstant.gapEndAsEarlier` (the move to `hi` dropped in `gapEnd`).
3. `M/VChrono.java`: the two `toInstant` vector forms, the scalar `toInstant(long,
   ZoneData, Resolve)`, and `insideGap(LongVector local, LocalSteps steps)` of 4.9, which
   throws `IllegalArgumentException("insideGap takes GAP_END steps, not " + rule)` for other
   steps. (L11 adds its unit check.)
4. `T/ToInstantTest.java`, its catcher, one execution; `README.md` (one sentence under
   "Use") and `CHANGELOG.md`.

**Tests.** Every vector case at each `VectorWidth`. A range is the day either side of the
window it is about, so that it is inside the clamp.

| case of `ToInstantTest` | inputs | expected, and its oracle | the failure only it catches |
| :--- | :--- | :--- | :--- |
| `everyZoneAtEveryWindow` | each set, each stored change with two different offsets: the nine edges of L4 and two hours either side at 60 s; `EARLIER` and `GAP_END` | `Oracle.toInstant` in every lane | a threshold of the wrong side |
| `gapInteriors` | each set, each stored gap: `lo + 1`, the middle, `hi - 1` | `toInstant` under `GAP_END` is the gap's instant | the gap's end taken as the earlier offset (`toInstant.gapEndAsEarlier`) |
| `everyCount` | `hardZones()`, a year; forced counts of 0, 2 and 8, each only where it is not below `localChanges` | the same lanes | a padded step that fires |
| `secondWordPassesThrough` | `hardZones()`; 4096 seeded local first words in 2025, half within three hours of a change, each with each of `Inputs.secondWords()`; `EARLIER`, and `GAP_END` with the second word zeroed under `VChrono.insideGap` | the oracle's pair: under `EARLIER` the input's second word; under `GAP_END` 0 inside a gap and the input's elsewhere | a second word carried across a gap's end, where `java.time` has none |
| `insideGap` | each set, each stored gap: `lo - 1`, `lo`, the middle, `hi - 1`, `hi`; each stored overlap: the same five points | `VChrono.insideGap` is true at `lo`, the middle and `hi - 1` of a gap, and false at every other point | the mask a caller zeroes its second word under, off by one |
| `maskedLanes` | `Inputs.masks`, both rules, a year of `America/Los_Angeles` | live lanes the oracle's, the others `local`'s | a lane that is not live computed |
| `ruleEra` | each set with `block > 0`: 4096 seeded local times in the year 2437 and in 9999 | the oracle | steps built from the block wrongly |
| `clamp` | a range that ends at `Long.MAX_VALUE - 2 * MAX_OFFSET_MICROS + 1`; one that ends a microsecond lower | the first: `LocalSteps.of` throws 4.8's second text; the second: no exception, and the lanes are the oracle's | a threshold that wraps |
| `wrongRule` | `VChrono.toInstant` with `KEPT` steps | 4.8's text | - |
| `scalarTwin` | 1000 seeded local times in `hardZones()`, both rules | the scalar `toInstant` equals the oracle | - |
| `refusals` | `LocalSteps.of` with a count of 3; with the entry of `ZoneFixtures.outOfYear()`; with a null zone and a null rule; `VChrono.toInstant` with a null vector, null steps and a null mask | 4.8's texts for the first two; `NullPointerException` with the parameter's name (`zone`, `rule`, `local`, `steps`, `live`) for the rest | a refusal that 4.8 promises and no test holds |

**Commands.** The oracle of 5.1; `grep -c '<id>fault-' pom.xml` prints `F0 + 1`.

**The cut line.** If the size passes 900: the arm `ToInstant.gapEnd`, `GAP_END` in
`VChrono.toInstant`, `gapInteriors` and the fault `toInstant.gapEndAsEarlier` leave this
part and become `[VECHRONO-<issue>] Add toInstant's rule of the gap's end`, branch
`vechrono-l9b-gap-end`, which L10 then needs; until it merges `VChrono.toInstant` throws
`IllegalArgumentException("toInstant takes EARLIER until the gap's end is built")` for
`GAP_END` steps.

**Done when** the oracle passes on the four CI rows. **Not here:** no kept offset (L10), no
sum, no field.

#### L10. Local to instant: the kept offset

*Title:* `[VECHRONO-<issue>] Add toInstantKept: the offset a value had wins in an overlap`.
*Branch:* `vechrono-l10-to-instant-kept`.

**B0.** `grep -c 'static LongVector toInstant(' M/VChrono.java` prints `2`;
`grep -c 'toInstantKept' M/VChrono.java` prints `0`.

**Steps.**

1. `M/ToInstant.java`: `static LongVector kept(LongVector l, LongVector k, LocalSteps s)`,
   0, 2 and 8 steps written out; a step is two compares, an AND and an OR of masks, and one
   masked add. One fault: `toInstant.keptIgnored` (the `kept == after[i]` term dropped).
2. `M/VChrono.java`: the two `toInstantKept` vector forms and the scalar one.
3. `T/ToInstantKeptTest.java`, its catcher, one execution; `README.md`, `CHANGELOG.md`.

**Tests.** Every vector case at each `VectorWidth`.

| case of `ToInstantKeptTest` | inputs | expected, and its oracle | the failure only it catches |
| :--- | :--- | :--- | :--- |
| `everyZoneAtEveryWindow` | each set, each stored change with two different offsets: the nine edges; `kept` the offset before, the offset after, `before + 3600` seconds, 1 and `Long.MIN_VALUE` | `Oracle.toInstantKept` in every lane, through the two-input `check` | a kept offset honoured inside a gap |
| `keptInsideEveryOverlap` | each set, each stored overlap: `lo`, the middle, `hi - 1`, with `kept` the offset after | the later of the two instants, and `Oracle.toInstantKept` | the kept offset ignored (`toInstant.keptIgnored`) |
| `thereAndBack` | each set, a year around each of five seeded changes, 4096 instants `x` in no order | `toInstantKept(toLocal(x), toLocal(x) - x)` is `x` in every lane | an error the oracle's use would share |
| `maskedLanes` | `Inputs.masks` | live lanes the oracle's, the others `local`'s | a lane that is not live computed |
| `secondWordPassesThrough` | `hardZones()`; 4096 seeded local first words in 2025, each with each of `Inputs.secondWords()` and `kept` one of the nearest change's two offsets | the oracle's pair has the form's first word and the input's second word | as L6's |
| `everyCount` | `hardZones()`, the local year 2025, `kept` the offset after the nearest change; forced counts of 0, 2 and 8, each only where it is not below `localChanges`; and a year of `Africa/Casablanca`, which needs the count of 8 | the same lanes from each count, and the oracle's | the 8-step arm of `kept`, which no other case reaches |
| `ruleEra` | each set with `block() > 0`: 4096 seeded local times in the year 2437 and in 9999, `kept` one of the two offsets of the nearest change | the oracle | steps built from the block wrongly |
| `clamp` | a range that ends at `Long.MAX_VALUE - 2 * MAX_OFFSET_MICROS + 1`; one that ends a microsecond lower | the first: `LocalSteps.of(zone, KEPT, ...)` throws 4.8's clamp text; the second: no exception, and the lanes are the oracle's | a threshold that wraps |
| `wrongRule` | `VChrono.toInstantKept` with `EARLIER` steps | 4.8's text | - |
| `scalarTwin` | 1000 seeded pairs in `hardZones()` | the scalar form equals the oracle | - |
| `refusals` | `VChrono.toInstantKept` with a null `local`, `kept`, steps and mask | `NullPointerException` with the parameter's name | - |

**Commands.** The oracle of 5.1; `grep -c '<id>fault-' pom.xml` prints `F0 + 1`.

**Done when** the oracle passes on the four CI rows. **Not here:** no sum, no field.

#### L11. The one-long arm of the steps

*Title:* `[VECHRONO-<issue>] Add the forms over one long of nanoseconds, by steps`.
*Branch:* `vechrono-l11-one-long-steps`.

**B0.** `grep -c 'ofNanos' M/ZoneSteps.java M/LocalSteps.java` prints `0` for each file.
`grep -c 'toInstantKept' M/VChrono.java` is above 0: L10 has merged.

**Steps.**

1. `M/ZoneSteps.java` and `M/LocalSteps.java`: the two `ofNanos` forms and `nanos()` of
   4.9. `least()` and `greatest()` of steps made by `ofNanos` are in nanoseconds, and
   `noMaskExact()` is computed from them. Three faults: `nanos.atNotScaled` (the thresholds
   are not multiplied), `nanos.deltaNotScaled` (the base and the differences are not
   multiplied), `nanos.noRangeCheck` (a range near an end of a long is not refused).
2. `M/VChrono.java`: the six `*Nanos` vector forms and the three scalar twins of 4.9; the
   six microsecond forms and `insideGap` gain the unit check (`insideGap` compares a
   microsecond lane, so with `ofNanos` steps it throws `IllegalArgumentException("insideGap
   takes steps in microseconds, these are in nanoseconds")`; the one-long arm needs no such
   mask, since it writes the gap's end itself). No new arm: they call `ToLocal` and
   `ToInstant`.
3. `T/OneLongTest.java`, its catchers, three executions; `README.md` (one paragraph under
   "Use": the two shapes, and when the one long applies) and `CHANGELOG.md`.

**Tests.** Every vector case at each `VectorWidth`. Inputs are pairs; a one-long input is
`Oracle.nanosOf` of the pair, and `Oracle.checkNanos` compares.

| case of `OneLongTest` | inputs | expected, and its oracle | the failure only it catches |
| :--- | :--- | :--- | :--- |
| `toLocalAtEveryChange` | each set; each change `r` with `-9_000_000_000_000_000L <= atOf(r) <= 9_000_000_000_000_000L` (inside the one-long range, and far from its ends): the range from `(atOf(r) - 86_400_000_000L) * 1000` to `(atOf(r) + 86_400_000_000L) * 1000`, a day either side, which holds at most 2 changes of any zone of the JDK (a set where it holds more than 8 is skipped and counted, and the count is asserted to be 0); lanes at `at * 1000`, one nanosecond, 999 ns, 1000 ns and one second either side | `toLocalNanos` equals `nanosOf(Oracle.toLocal(...))` | thresholds left in microseconds (`nanos.atNotScaled`) |
| `shapesAgree` | `hardZones()`; 4096 seeded pairs of the year 2025 (first words from 1735689600000000 to 1767225599999999), with the range their least and greatest; each of `toLocal`, `toInstant` under both rules, and `toInstantKept` with `kept` the offset after the change nearest to each value (`zone.offsetSecondsAt` of that change, in microseconds for the one shape and times 1000 for the other) | the one-long result is `micros * 1000 + nanos` of the two-word result (the first word from the microsecond form, the second passed through, zeroed under `insideGap` for `GAP_END`) | the base or a difference left in microseconds (`nanos.deltaNotScaled`) |
| `wayBackAtEveryWindow` | each set, each stored change inside the range with two different offsets: the nine edges of L4 times 1000, and 1 ns either side of `lo * 1000` and `hi * 1000`; each rule; `kept` the two offsets in nanoseconds | `nanosOf` of the oracle's pair | a window's edge that moves by the scaling |
| `rangeEnds` | four ranges, each one day long (86,400,000,000,000 ns), in `hardZones()`, for `ZoneSteps.ofNanos` and for `LocalSteps.ofNanos` under each rule, with `E = 129_600_000_000_000L`: (a) `greatest = Long.MAX_VALUE - E`, `least = greatest - 86_400_000_000_000L`; (b) the same shifted by +1 ns; (c) `least = Long.MIN_VALUE + E`, `greatest = least + 86_400_000_000_000L`; (d) the same shifted by -1 ns | (b) and (d): 4.8's clamp text; (a) and (c): no exception, and lanes at `least` and at `greatest` are `nanosOf` of the oracle's pair | a product that leaves a long (`nanos.noRangeCheck`: the body asserts the two exceptions first) |
| `everyCount` | `hardZones()`; three ranges in nanoseconds: the week from 2025-07-01T00:00:00Z (1751328000000000000, 604,800,000,000,000 ns long), the year 2025 (from 1735689600000000000 to 1767225599999999999) and, for `Africa/Casablanca`, the same year; 4096 seeded values each; forced counts of 0, 2 and 8, each only where it is not below `changes` of the range in microseconds | the same lanes from each count, and `nanosOf` of the oracle's pair | padding multiplied by 1000 |
| `maskedLanes` | `Inputs.masks`; the year 2025 of `America/Los_Angeles` in nanoseconds, 4096 seeded values; `toLocalNanos`, `toInstantNanos` under both rules, `toInstantKeptNanos` | `Oracle.checkNanosMasked`: live lanes the oracle's, the others their input | a lane that is not live computed in the one-long forms |
| `armsAgree` | the ranges and lanes of `toLocalAtEveryChange` | `ToLocal.noMask` equals `ToLocal.masked` over `ofNanos` steps, called directly, on every host; the body asserts `noMaskExact()` first | the arm with no mask chosen from a span in the wrong unit |
| `wrongUnit` | each microsecond form and `insideGap` with `ofNanos` steps; each `*Nanos` form with `of` steps | 4.8's seven texts | a silent answer a thousand times off |
| `scalarTwins` | 1000 seeded one-long values in `hardZones()`, each rule | the three twins equal `nanosOf` of the oracle's pair | - |
| `refusals` | null vectors, steps, masks and zones | `NullPointerException` with the parameter's name | - |

**Commands.** The oracle of 5.1; `grep -c '<id>fault-' pom.xml` prints `F0 + 3`.

**Done when** the oracle passes on the four CI rows. **Not here:** no table, no
`plusNanos`.

#### L12. The one-long arm of the table

*Title:* `[VECHRONO-<issue>] Add the table form over one long of nanoseconds`.
*Branch:* `vechrono-l12-nano-table`.

**B0.** `ls M/NanoTable.java` fails. `ls M/ZoneTable.java` prints the path.
`grep -c 'toLocalNanos' M/VChrono.java` is above 0.

**Steps.**

1. `M/NanoTable.java`, all of 4.9's class, with `pack` as 4.9 writes it out.
2. `M/TableLookup.java`: `static LongVector gatherNanos(LongVector x, NanoTable t, int[]
   scratch)`, 4.9's operations. One fault: `nanoTable.beforeForAfter` (the blend picks the
   offset before where the lane is at or after the change).
3. `M/VChrono.java`: the two `toLocalNanos` forms over a `NanoTable`.
4. `T/NanoTableTest.java`, its catcher, one execution; `CHANGELOG.md`.

**Tests.** Every vector case at each `VectorWidth`.

| case of `NanoTableTest` | inputs | expected, and its oracle | the failure only it catches |
| :--- | :--- | :--- | :--- |
| `everyZoneAtEveryChange` | each set with a table; each change inside `[ORIGIN, LIMIT)`: `T`, 1 ns and 1000 ns either side; 10,000 seeded values a set over the range | the vector form and the scalar `toLocal` equal `nanosOf` of the oracle's pair | the two offsets swapped (`nanoTable.beforeForAfter`) |
| `equalsZoneTable` | `hardZones()`; 4096 seeded pairs of the years 1900 to 2099 | `toLocalNanos(m * 1000 + n, nanoTable)` is `VChrono.toLocal(m, zoneTable) * 1000 + n` | two tables of one zone that disagree |
| `everyZoneHasATable` | each set | `NanoTable.has` equals `ZoneTable.has`, or the difference is printed with the bucket and the case fails | a bucket of 2^49 ns that holds two changes where 2^39 microseconds held one |
| `edgesOfTheRange` | `ORIGIN`, `LIMIT - 1`, the first and last value of buckets 0 and 1, and the first value of bucket 11211 (its last is past `LIMIT - 1` and is not tried), in `hardZones()` | the oracle | an index one bucket off |
| `packRefuses` | `pack` with `offsetSeconds` as long as `at`; with a change at `ZoneTable.ORIGIN - 1`; at `ZoneTable.LIMIT`; with two changes out of order; with an offset of 64801; with `longs` of `LONGS - 1` | `IllegalArgumentException` for each | a table packed from arrays that do not belong together |
| `notLiveLanesOutsideTheTable` | the masked form; lanes that are not live hold `Long.MIN_VALUE`, `Long.MAX_VALUE`, `ORIGIN - 1`, `LIMIT`; the body wraps the call in `assertDoesNotThrow` | no exception, and those lanes keep their input | - |
| `noTable` | the made-up rules of `TableTest.noTable` | `has` is false, and `of` throws 4.8's text | - |
| `refusals` | a scratch of `2 * lanes - 1` ints; a null vector, table, scratch and mask; the entry of `ZoneFixtures.outOfYear()` | 4.8's texts | - |

**Commands.** The oracle of 5.1; `grep -c '<id>fault-' pom.xml` prints `F0 + 1`.

**Done when** the oracle passes on the four CI rows. **Not here:** no table of the local
line.

#### L13. `plusNanos` in both shapes

*Title:* `[VECHRONO-<issue>] Add plusNanos: the first form that reads and writes both words`.
*Branch:* `vechrono-l13-plus-nanos`.

**B0.** `ls M/PlusNanos.java` fails. `ls M/VChrono.java` prints the path.

**Steps.**

1. `M/PlusNanos.java`, package-private: `static LongVector micros(LongVector m, LongVector n,
   LongVector dm, LongVector dn)`, `static LongVector within(LongVector n, LongVector dn)` and
   `static LongVector oneLong(LongVector x, LongVector d)`, 4.9's operations. Three faults:
   `plusNanos.noCarry` (the first word gets no carry), `plusNanos.carryAbove1000` (the carry
   compares `sum > 1000`, so a sum of exactly 1000 does not carry), `plusNanos.maskedLaneTouched`
   (the masked forms return the result unblended).
2. `M/VChrono.java`: the six `plusNanos` forms of 4.9.
3. `T/PlusNanosTest.java`, its catchers, three executions; `README.md`, `CHANGELOG.md`.

**Tests.** Every vector case at each `VectorWidth`. The oracle is
`Oracle.plusNanos(value, delta)`, `pairOf(instantOf(value).plusNanos(delta.micros() * 1000 +
delta.nanos()))`. Inputs are kept where `java.time` and the pair both hold the result:
first words from -9,000,000,000,000,000,000 / 1000 to 9,000,000,000,000,000,000 / 1000 and
`deltaMicros` within plus and minus 10^15.

| case of `PlusNanosTest` | inputs | expected, and its oracle | the failure only it catches |
| :--- | :--- | :--- | :--- |
| `carryEdges` | second words 0, 1, 499, 500, 998, 999 by `deltaNanos` 0, 1, 500, 501, 999, with first words 0, -1 and 1767225599999999 and `deltaMicros` 0, 1 and -1 | both words equal the oracle's pair; the second word is from 0 to 999 | a carry lost (`plusNanos.noCarry`); a carry at the wrong sum (`plusNanos.carryAbove1000`, at a sum of exactly 1000) |
| `seeded` | 100,000 seeded pairs and deltas | the oracle | a sum that the edges do not reach |
| `negativeDelta` | a delta of -1 ns, -999 ns, -1000 ns and -1001 ns, each split by `floorDiv` and `floorMod`, on second words 0 and 999 | the oracle, `Instant.plusNanos` of the negative count | a borrow, which the floor form turns into a carry |
| `shapesAgree` | 100,000 seeded pairs and deltas: first words uniform from -9,000,000,000,000,000 to 9,000,000,000,000,000, `deltaMicros` uniform from -1,000,000,000,000,000 to 1,000,000,000,000,000, second words and `deltaNanos` from 0 to 999; the one long is formed with wrapping arithmetic, `m * 1000 + n`, so a sum that leaves a long is kept: both shapes wrap modulo 2^64 and must still agree | the one-long `plusNanos(m * 1000 + n, dm * 1000 + dn)` is `micros * 1000 + within` of the two-word result | two shapes of one sum that disagree |
| `wraps` | two words: `(Long.MAX_VALUE, 0)` plus `(1, 0)`; `(Long.MAX_VALUE, 999)` plus `(0, 1)`, a carry out of the last first word; `(Long.MIN_VALUE, 0)` plus `(-1, 999)`. One long: `Long.MAX_VALUE` plus 1 | `(Long.MIN_VALUE, 0)`; `(Long.MIN_VALUE, 0)`; `(Long.MAX_VALUE, 999)`; `Long.MIN_VALUE`. The expected values are written down, since `java.time` has no wrap; no exception | an overflow check that a kernel does not have |
| `maskedLanes` | `Inputs.masks`, each of the three masked forms | live lanes the oracle's; the others their first argument's | a lane that is not live computed (`plusNanos.maskedLaneTouched`) |
| `refusals` | each form with each argument null | `NullPointerException` with the parameter's name | - |

**Commands.** The oracle of 5.1; `grep -c '<id>fault-' pom.xml` prints `F0 + 3`.

**Done when** the oracle passes on the four CI rows. **Not here:** no other form over the
second word (5.3).

#### L14. The benchmarks of the table, the way back and the two shapes

*Title:* `[VECHRONO-<issue>] Time the table and the three rules against java.time`.
*Branch:* `vechrono-l14-benchmarks`.

**B0.** `ls src/jmh/java/io/github/vecbricks/vechrono/` prints `ToLocalBenchmark.java` only.
After `mvn -q -DskipTests compile`, `javap -p -cp target/classes
io.github.vecbricks.vechrono.TableLookup | grep -c 'scalarLoads(.*, long\[\])'` prints `1`.

**Steps.** Four classes, each built as `ToLocalBenchmark` is (4096 values from
`new SplittableRandom(679)`, a `setUp` that throws on an unknown `inputs` and prints the
tzdb, no scalar tail since 4096 is a multiple of every lane count), each with a test as
L7's. No row of any of them throws for a value its `@Param` lists: `benchmark.yml` runs with
`-foe true`, and one failing row fails the whole run.

- `TableBenchmark`, `@Param({"decade", "year"}) inputs` (the years 2016 to 2025, and the
  year 2025, of `America/Los_Angeles`, in no order). Rows: `javaTimeOffset`, `gather256`,
  `gatherPreferred`, `gatherMax`, `scalarLoadsPreferred`. The benchmark owns the two
  scratch arrays, an `int[]` of twice the lane count and a `long[]` of the lane count, made
  in `setUp`, so that no row allocates.
- `TableStepsBenchmark`, `@Param({"year"}) inputs`, the same year: the one row
  `stepsPreferred`, the public step form on the data of `TableBenchmark`'s `year`. It is a
  class of its own because ten years have 20 changes and no steps.
- `ToInstantBenchmark`, `@Param({"year"}) inputs` (the local year 2025 of
  `America/Los_Angeles`, half of the values within three hours of a change). `kept` for a
  local time `L`: with `c = zone.changesAt(<the year's first instant>, <the next year's>)`,
  find the change nearest to `L` by `Arrays.binarySearch(c, L)` (for a miss, the nearer of
  the two neighbours of the insertion point), and take `zone.offsetSecondsAt(change) *
  1_000_000L`, the offset after it. Rows: `javaTimeAtZone`, `javaTimeOfLocal`, `earlier256`,
  `earlierPreferred`, `earlierMax`, `gapEndPreferred`, `kept256`, `keptPreferred`.

- `ShapesBenchmark`, `@Param({"year"}) inputs`: 4096 values of the year 2025 of
  `America/Los_Angeles` in no order, from `new SplittableRandom(679)`, so that both shapes
  run on the same values inside the one-long range. Its arrays, all `long[4096]`, made in
  `setUp`: the inputs `m` (first words) and `n` (second words, 0 to 999), and `x`, with
  `x[i] = m[i] * 1000 + n[i]`; the deltas `dm` (uniform within plus and minus 1,000,000,000
  microseconds) and `dn` (0 to 999), and `d`, with `d[i] = dm[i] * 1000 + dn[i]`; the
  outputs `outM`, `outN` and `out`. Its scratch for the two table rows: one `int[]` of
  `2 * LongVector.SPECIES_MAX.length()`, made in `setUp`. Its steps and tables: `ZoneSteps
  .of` and `.ofNanos`, `LocalSteps.of` and `.ofNanos` under `EARLIER`, `ZoneTable.of` and
  `NanoTable.of`, each for the least and greatest of its array. The rows, each at the
  preferred species and at 256 bits, seventeen in all:
  - `toLocalTwoWordsPreferred`, `toLocalTwoWords256`: `VChrono.toLocal` from `m` into
    `outM`; `n` is not touched, since the form leaves the second word;
  - `toLocalOneLongPreferred`, `toLocalOneLong256`: `toLocalNanos` from `x` into `out`;
  - `toLocalTableTwoWordsPreferred`, `toLocalTableTwoWords256`: over the `ZoneTable`;
  - `toLocalTableOneLongPreferred`, `toLocalTableOneLong256`: over the `NanoTable`;
  - `toInstantTwoWordsPreferred`, `toInstantTwoWords256`, and `toInstantOneLongPreferred`,
    `toInstantOneLong256`: `EARLIER`, reading `m` or `x` as local times;
  - `plusNanosTwoWordsPreferred`, `plusNanosTwoWords256`: `plusNanosMicros` from `m`, `n`,
    `dm`, `dn` into `outM`, and `plusNanosWithin` from `n`, `dn` into `outN`, in one loop:
    four arrays read, two written;
  - `plusNanosOneLongPreferred`, `plusNanosOneLong256`: `plusNanos` from `x`, `d` into
    `out`: two arrays read, one written;
  - `javaTimePlusNanos`: `Instant.plusNanos` once a row, over `Instant`s made in `setUp`.
  This is the measurement row 662 asks of its two layouts, outside Spark.

`bench/alloc-check.py` is not edited: its regex takes every row above that ends in `256`,
`Preferred` or `Max`, and each of them must read under 1 B/op.

**Commands.** The oracle of 5.1; CI's job `alloc` prints its last line in both rows.

**The cut line.** If the size passes 900: `ShapesBenchmark` and its test leave this part and
become `[VECHRONO-<issue>] Time the two shapes of a nanosecond value on the same data`,
branch `vechrono-l14b-shapes-benchmark`, which L17 then needs.

**Done when** CI is green. **Not here:** no results file.

#### L15. The every-zone sweep, as a program

*Title:* `[VECHRONO-<issue>] Sweep every change of every zone, as a main program`.
*Branch:* `vechrono-l15-sweep`.

**B0.** `ls T/Sweep.java` fails. `ls M/ZoneTable.java M/NanoTable.java M/ToInstant.java`
prints the three, and `grep -c 'toInstantKeptNanos' M/VChrono.java` is above 0: L8, L11 and
L12 have merged.

**Steps.**

1. `T/Sweep.java`. Its seam for the form under test, so that a test can plant a wrong one:

       interface Subject {      // one form in one shape, over arrays; results into outFirst, outSecond
         void apply(String form, String shape, ZoneData zone, VectorSpecies<Long> species,
             boolean[] live, long least, long greatest, long[] first, long[] second,
             long[] kept, long[] outFirst, long[] outSecond);
       }
       static final Subject LIBRARY = ...;        // the library's own forms
       static int run(String[] args);             // run(args, LIBRARY)
       static int run(String[] args, Subject subject);

   `live` is null for the unmasked form. Under `oneLong`, `first` holds the one long and `second`
and `outSecond` are not read. Under `twoWords`, `LIBRARY` copies `second` to `outSecond`, and for
`gapEnd` it then zeroes `outSecond` in the lanes of `VChrono.insideGap` (4.9). `main` calls
`run(args)` and exits with its result. The options are `--form` (`toLocal`, `table`, `earlier`,
`gapEnd`, `kept`), `--shape` (`twoWords`, the default, or `oneLong`), `--species` (`128`, `256`,
`512`, `max`), `--shard i/n` (the sets of `Inputs.sets()` whose index is `i` modulo `n`), `--step
<seconds>` (default 1) and `--out <file>`. For each set, each change `r` below `size()` (so the
stored changes, the head and the block), it checks every `--step` seconds from two hours before to
two hours after the change (on the local line: from two hours before `lo` to two hours after
`hi`), plus one microsecond either side of each threshold, in ranges of one change each; both arms
where a form has two; the unmasked form and the masked form under a mask with every third lane
off. Under `kept` each local time runs with `kept` the offset before and the offset after. Under
`table` it takes the changes inside the table's range, and a set with no table (`ZoneTable.has` is
false) is skipped and counted. It prints the JDK build (`Runtime.version()`), `tzdb <version>`,
the sets, the changes, the lanes checked and, under `table`, `no table: <n> sets`; it writes each
mismatch to `--out` as `Oracle.mismatchLine` has it; its last line is `<n> mismatches`; `run`
returns 0 with none and 1 otherwise. A species the host lacks prints `<bits>: not on this host`
and returns 0. Under `twoWords` the second words are spread one a lane, cycling, as 5.1 says, and
the check holds both words; no point runs four times. The oracle is called once for each input,
and each arm and the masked form are compared with that one answer. Under `oneLong` it takes the
changes inside the one-long range, the same points times 1000 and one nanosecond either side of
each threshold, through the `*Nanos` forms and, for `table`, the `NanoTable`. `plusNanos` has no
zone and is not swept: its test is exhaustive at its edges.
2. `T/NightlyTest.java` (JUnit 5): `Sweep.run` with `--shard 0/64 --step 900` for each of
   the five forms in each of the two shapes at `max`, each required to return 0. With a
   step of 900 s a change has 17 points and its thresholds' neighbours, so the ten runs
   make under 2 million oracle calls (section 7, prediction 8).
3. `-Dvechrono.fault` reaches the program, since the forms read `Faults`.

**Tests.** `NightlyTest.sweepFindsAPlantedMismatch`: `Sweep.run(args, subject)` with `--form
toLocal --shard 0/64 --step 900` and a `subject` that calls `Sweep.LIBRARY` and then adds 1
to `outFirst[0]` of its first call only. It returns 1, its last line is `1 mismatches`, and
its `--out` file holds one line, that input's, as `Oracle.mismatchLine` has it. The failure
only it catches: a sweep that returns 0 whatever it reads.

**Commands.** The oracle of 5.1. Then this prints a last line `0 mismatches` and exits 0:

    java --add-modules jdk.incubator.vector -cp "target/classes:target/test-classes" \
        io.github.vecbricks.vechrono.Sweep --form toLocal --species max --shard 0/64

**Done when** CI is green. **Not here:** no workflow, no `Fuzz` (5.6).

#### L16. The nightly workflow

*Title:* `[VECHRONO-<issue>] Run the every-zone sweep every night, with an issue on failure`.
*Branch:* `vechrono-l16-nightly`. Needs P0 item 9 for its two dispatches.

**B0.** `ls .github/workflows/nightly.yml` fails; `ls dev/nightly.sh` fails.

**Steps.**

1. `dev/nightly.sh <form> <shape> <species>`: it requires
   `target/test-classes/io/github/vecbricks/vechrono/Sweep.class` (else it prints
   `dev/nightly.sh: build the classes first: mvn -B -q test-compile` and exits 2); it runs
   `Sweep --form <form> --shape <shape> --species <species> --shard 0/1 --out
   target/nightly/sweep-<form>-<shape>-<species>.txt` with the JVM options of `NIGHTLY_JVM_ARGS`,
   and exits with its status.
2. `.github/workflows/nightly.yml`, written new (vfdlibm's has a `plan` job, stripes, slices
   and a fuzz that this one does not have):
   - `on:` a `schedule` at `7 0 * * *` and a `workflow_dispatch` with the inputs `forms`
     (default `toLocal table earlier gapEnd kept`) and `fault` (default empty);
   - `permissions: contents: read` for the workflow;
   - the job `sweep`: `ubuntu-latest`, a matrix of the five forms by the two shapes by
     three settings (`256`
     with no option, `max` with no option, `128` with `NIGHTLY_JVM_ARGS=-XX:MaxVectorSize=16`
     and the species `128`); steps: checkout, Java, cache, `java -version`, `mvn -B -q
     test-compile`, then `dev/nightly.sh` with, when `inputs.fault` is set,
     `-Dvechrono.fault=${{ inputs.fault }}` appended to `NIGHTLY_JVM_ARGS`. On a schedule
     `inputs.forms` is empty and every form runs; on a dispatch a matrix entry whose form is
     not a word of `inputs.forms` skips its run step. On failure it uploads
     `target/nightly/` as the artifact `mismatches-<form>-<setting>`;
   - the job `alert`: `needs: [sweep]`, `if: ${{ always() && needs.sweep.result ==
     'failure' }}`, `permissions: issues: write`; it downloads the `mismatches-*` artifacts
     and, with `gh` and `GH_TOKEN: ${{ github.token }}`, comments on the open issue that has
     the label, or opens one. The label is `nightly-fault-test` when `inputs.fault` is set,
     else `nightly-failure`. The body holds the run's link, the dispatch command that
     replays it, and the first 50 mismatch lines.
3. `AGENTS.md`: how to run one form by hand, and how a mismatch line becomes a line of
   `hard-cases/<form>.txt`.

**Tests.** After the merge the lead dispatches once with
`gh workflow run nightly.yml -f fault=toLocal.gtForGe -f forms=toLocal`: the run is red and
an issue labelled `nightly-fault-test` appears, which the lead closes. Then once with no
input: green, and no issue.

**Done when** both dispatches behaved so, and the first scheduled night is green. **Not
here:** no new test program; no `fuzz-avx2` and no `arm` job (5.6).

#### L17. The host benchmarks and the first results

*Title:* `[VECHRONO-<issue>] Record each form's cost on the EPYC 7763 at 256 and 128 bits`.
*Branch:* `vechrono-l17-host-benchmarks`. Needs P0 item 7.

**B0.** `ls bench` prints `alloc-check.py` only.

**Steps.**

1. `bench/host.sh`, cut from vfdlibm's (548 lines) to about 90. It keeps: the table

       HOSTS=(
         "epyc7763|end|AMD EPYC 7763 64-Core Processor|"
         "xeon8573c|any|INTEL(R) XEON(R) PLATINUM 8573C|"
       )

   the functions `keys`, `row_of`, `string_of`, `name_matches` (ignoring case; `end`: the
   model name ends with the string; `any`: it holds it), `trim`, `expect` and
   `expect_and_record`; and the options `--keys`, `--cpu` (the `model name` of
   `/proc/cpuinfo`), `--expect <key>` and `--self-test`. It drops the Arm row and its
   fields, the required flags, `--classify` and the census cases. `--expect` prints `Host:
   <key>, CPU: <cpu>` and exits 0, or prints `::error::CPU mismatch on host <key>! Expected
   '<string>' but got '<cpu>'` and exits 1; on success it appends `EXPECTED_CPU=<string>` to
   `$GITHUB_ENV` when that is set. `--self-test` checks that each row's own string matches
   its row and not the other, and that `"AMD EPYC 7763 64-Core Processor X"` matches no row;
   it prints `host.sh: self-test passed`.
2. `.github/workflows/benchmark.yml`, cut from vfdlibm's. It keeps: the `workflow_dispatch`
   inputs `host` (a choice of `epyc7763`, the default, and `xeon8573c`), `benchmarks` (a JMH
   regex, required) and `jvm-args`; `permissions: contents: read`; the one job on
   `ubuntu-latest` with `JMH_OPTIONS: -f 2 -wi 3 -w 2s -i 5 -r 2s -prof gc -foe true` and
   `INLINE: -XX:CompileCommand=inline,io.github.vecbricks.vechrono.*::*`; its steps "Fetch
   the probe's script", "Check the probe's script", "Probe the machine's CPU", the checkout,
   Java and cache steps, "Run JMH" and the upload of `bench-results.txt` as the artifact
   `bench-results`. It drops: the inputs `base`, `rounds`, `threshold` and `run-tag`, the
   steps "Check the pair inputs", "Measure the vector datapath" and "Run the pair", and the
   header's two `Datapath` lines. "Run JMH" writes the header itself, as vfdlibm's does: the
   lines `# <benchmarks> on a GitHub runner, <date>`, `# Host:`, `# Commit:`, `# Run:`, `#
   JDK:`, `# CPU:`, `# AVX-512:`, `# CPU flags:`, `# Vector:`, `# Kernel:`, `# Load:` (at
   start), `# JMH:`, `# Command:`, then JMH's whole output through `tee -a`, then `# Load:`
   at the end. The tzdb version is in JMH's output, printed by each benchmark's `setUp`.
3. `bench/record.sh <run id> <name> "<what and why, one sentence>"`, about 30 lines: `gh run
   download <run id> --repo vecbricks/vechrono -n bench-results -D <temporary directory>`;
   it reads the host from the artifact's `# Host:` line and the date (`YYYY-MM-DD`) from the
   artifact's first line, `# <benchmarks> on a GitHub runner, <date>`; it refuses an artifact
   with no `# tzdb` line; it writes `bench/results/<name>-<host>-<date>.txt` (a second run of
   one name, host and date gets `-2`): first the line `# What and why: <the sentence>`, then
   the artifact's file byte for byte; and it prints `wrote <path>`. Nothing is typed by hand
   into a results file.
4. `ci.yml`: a job `bench-scripts` that runs `bench/host.sh --self-test`.
5. The lead dispatches, the builder records. Eight runs:

| name | host | benchmarks | jvm-args |
| :--- | :--- | :--- | :--- |
| `to-local` | `epyc7763` | `ToLocalBenchmark` | none (256 bits) |
| `to-local-128` | `epyc7763` | `ToLocalBenchmark\.(javaTimeOffset\|scalarZoneData\|\w+Preferred)` | `-XX:MaxVectorSize=16` |
| `table` | `epyc7763` | `Table(Steps)?Benchmark` | none |
| `to-instant` | `epyc7763` | `ToInstantBenchmark` | none |
| `to-instant-128` | `epyc7763` | `ToInstantBenchmark\.(javaTime\w+\|\w+Preferred)` | `-XX:MaxVectorSize=16` |
| `shapes` | `epyc7763` | `ShapesBenchmark` | none |
| `shapes-128` | `epyc7763` | `ShapesBenchmark\.(javaTime\w+\|\w+Preferred)` | `-XX:MaxVectorSize=16` |
| `all-512` | `xeon8573c` | `(ToLocal\|Table\|TableSteps\|ToInstant\|Shapes)Benchmark` | none |

   The 128-bit runs name the `javaTime*`, scalar and `*Preferred` rows only: under
   `-XX:MaxVectorSize=16` a `*256` row would run a 256-bit species in software, which is no
   reading of anything.

   A run that fails its CPU check is dispatched again, at most ten times an hour apart, and
   the check is never relaxed. A ratio under 1.3 that a prediction rests on is run again,
   and both files are committed.
6. `README.md`: one table of the forms' costs, each cell with the results file it is from.
   `PLAN.md`: each prediction of section 7 with its score and its file.

**Done when** the files are committed and each prediction of section 7 has its score. **Not
here:** no change to a form; a form that loses is an issue, not a fix in this part.

#### L18. The jar checked from outside, and the release profile

*Title:* `[VECHRONO-<issue>] Check the jar as a consumer has it, and build a release's jars`.
*Branch:* `vechrono-l18-jar-check`.

**B0.** `ls dev/jar-check.sh` fails. `grep -c 'Automatic-Module-Name' pom.xml` prints `0`.
`grep -c '<id>release</id>' pom.xml` prints `0`. For each file of `ls M/*.java` but
`package-info.java`, `grep -c '^public \|^final \|^enum \|^record ' <file>` prints `1`: one
top-level type a file, which check 3 below assumes.

**Steps.**

1. `pom.xml`: `maven-jar-plugin` with the manifest entry `Automatic-Module-Name:
   io.github.vecbricks.vechrono`, as vfdlibm's; and the profile `release` of vfdlibm's
   `pom.xml`, whole: `maven-source-plugin` (`jar-no-fork`), the `maven-resources-plugin`
   execution that copies `LICENSE` and `NOTICE` into the Javadoc's `META-INF`,
   `maven-javadoc-plugin` with `--add-modules`, `maven-gpg-plugin` (signing at `verify`),
   and `central-publishing-maven-plugin` at vfdlibm's pinned version with
   `publishingServerId` `central` and no `autoPublish`. The property `vfdlibm.apidocs`
   becomes `vechrono.apidocs`. The row's done-when needs this profile and no script.
2. `dev/jar-check.sh`, vfdlibm's (652 lines) with its four checks and its option `--release`
   kept, its option `--self-test` and the function `mutant` left out (5.6), and its consumer
   rewritten:
   - check 1, the licence files: `META-INF/LICENSE` and `META-INF/NOTICE` in the jar, byte
     for byte the checkout's; vfdlibm's test that `NOTICE` names Sun Microsystems goes. A
     sources jar and a Javadoc jar beside the jar must hold the same two files; one that is
     not there prints `skip  no <kind> jar beside the jar (the release profile builds one)`,
     and under `--release` fails;
   - check 2, the module name in the manifest, and no `module-info.class`;
   - check 3, the classes are those of `src/main/java`, each source file's class there, every
     class a source file's or nested in one (`ZoneData$Check`), and nothing else in the jar
     but the manifest, the two licence files and `META-INF/maven/`;
   - check 4, the consumer, in a package of its own, compiled against the jar alone with
     `--add-modules jdk.incubator.vector`, on the class path and then as a module that
     `requires io.github.vecbricks.vechrono`. For each of `America/Los_Angeles`,
     `Asia/Kathmandu` and `+01:00` it takes `Zones.of(ZoneId.of(id).getRules())` and 64
     pairs around the first change of 2025 (for the fixed zone, around 0) with second words
     of 0 and 999, builds `ZoneSteps`, `LocalSteps`, `ZoneTable` and `NanoTable` by their
     public `of` and `ofNanos` methods, and calls every
     public static method of `VChrono` by name, vector and scalar, plain and masked, at the
     preferred species. **It passes** when every lane equals `java.time`'s answer by the
     definitions of 4.6, which the consumer computes itself; then it prints `consumer: <n>
     lanes equal java.time`. It fails if reflection finds a public method of `VChrono`, by
     name and parameter types, that it did not call.
   - the output lines are vfdlibm's: `ok    <what>` and `FAIL  <what>` for each check,
     `jar-check: <n> failed` with exit 1, or `jar-check: passed`.
3. `ci.yml`, in the job `build`, after `verify`: `mvn -B --no-transfer-progress -Prelease
   -Dgpg.skip=true -DskipTests package`, then `JAVA_TOOL_OPTIONS="${{ matrix.jvm-args }}"
   dev/jar-check.sh --release`.
4. `AGENTS.md`: a new public method of `VChrono` goes into the consumer, which fails until
   it is there; and "Releases", section 5.5 of this plan with L19's steps.

**Commands.** After `mvn -B -Prelease -Dgpg.skip=true -DskipTests package`, `ls target/*.jar
| wc -l` prints `3` (the jar, the sources jar, the Javadoc jar), and `dev/jar-check.sh
--release` prints `consumer: <n> lanes equal java.time` twice (the class path, then the
module path), no `skip` line, and `jar-check: passed` as its last line.

**The cut line.** If the size passes 900: the consumer's run as a module (the second half
of check 4) leaves this part and becomes `[VECHRONO-<issue>] Check the jar on the module
path too`, branch `vechrono-l18b-module-path`, which L19 does not need.

**Done when** CI is green. **Not here:** no `--self-test`, no `dev/release.sh`, no
`release.yml` (5.6), no tag.

#### L19. Release 0.1.0

*Title:* `[VECHRONO-<issue>] Release 0.1.0: the zone database, toLocal and the three rules`.
*Branch:* `vechrono-l19-release-0.1.0`. Needs P0 item 6.

**B0.** The last scheduled `Nightly` run on `main` is green (`gh run list --workflow
nightly.yml --event schedule --limit 1 --json conclusion --jq '.[0].conclusion'` prints
`success`). `git tag --list 'v*'` prints nothing. `ls bench/results/*.txt | wc -l` prints at
least `8`. `grep -c '<version>0.1.0-SNAPSHOT</version>' pom.xml` prints `1`.

**Who does what.** The builder does steps 1 to 4 and stops at a local commit. The lead
pushes the branch and opens the pull request (P0 item 10). Step 5 is the owner's alone: only
he has the signing key and the `central` token. Step 6 is a second pull request that the
lead pushes after the owner has confirmed the deployment.

**Steps.** There is no release script in this row (5.6), so the pull request makes by hand
the five edits that vfdlibm's `dev/release.sh` makes: the pom's version, its `<scm><tag>`,
its `project.build.outputTimestamp` (step 1), the changelog (step 2) and the README (step
3).

1. `pom.xml`: the project's `<version>` becomes `0.1.0`; `<scm><tag>` becomes `v0.1.0`;
   `project.build.outputTimestamp` becomes the current time in UTC, as
   `2026-MM-DDTHH:MM:SSZ`, so that the jars are reproducible.
2. `CHANGELOG.md`: the lines under `## [Unreleased]` move under a new heading `## [0.1.0] -
   YYYY-MM-DD` with the day of the release in UTC, `## [Unreleased]` stays empty above it, and the
   file ends with two link lines:

       [Unreleased]: https://github.com/vecbricks/vechrono/compare/v0.1.0...HEAD
       [0.1.0]: https://github.com/vecbricks/vechrono/releases/tag/v0.1.0

3. `README.md`: the status paragraph becomes `**Status: 0.x.** Released as
   io.github.vecbricks:vechrono 0.1.0: the zone data (ZoneData, Zones); toLocal as steps and
   as a table, toInstant, toInstantKept and plusNanos, each to the nanosecond in two shapes,
   two words and one long.` (with the names in backticks), and "Use" has
   the dependency at `0.1.0`.
4. The checks of the pull request, locally and in CI: `mvn -B -Prelease -Dgpg.skip=true clean
   verify` ends with `BUILD SUCCESS`, with every test and every fault run; then
   `dev/jar-check.sh --release` prints `jar-check: passed`.
5. After the merge, the owner (P0 item 6): `git checkout main && git pull`; `git tag v0.1.0
   <the merge commit's sha>`, with the sha written out, since a bare `git tag` tags whatever
   `HEAD` is; `git push origin v0.1.0`; `git checkout v0.1.0 && mvn -B -Prelease
   clean deploy`, which signs with his key and uploads; he publishes the deployment in the
   Central portal; `git checkout main`. If `deploy` fails after the upload, he looks at the
   deployment in the portal before a second run: a second upload of one version can be
   refused.
6. A second pull request of two edits, `[VECHRONO-<issue>] Start 0.1.1-SNAPSHOT`, branch
   `vechrono-l19b-next-snapshot`: the pom's version becomes `0.1.1-SNAPSHOT` and `<scm><tag>`
   becomes `HEAD` again.

**Done when** this prints `HTTP/2 200` on its first line, and step 6 has merged:

    curl -sI https://repo.maven.apache.org/maven2/io/github/vecbricks/vechrono/0.1.0/vechrono-0.1.0.pom

#### V1. Varka's test-scope dependency and its cross-check

*Title:* `[VARKA-679] Check the zone kernels against vechrono, a test-scope reference`.
*Branch:* `varka-679a-vechrono-dependency` (in Varka; `varka-679` held this plan).

This part follows `sql/varka/plans/m8/BUILD_SHEETS.md` in full. `V` is
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka` and `TV`
its `src/test/java` twin.

**B0, the premises.**

| command, in Varka's worktree | prints |
| :--- | :--- |
| `grep -c '<artifactId>vechrono</artifactId>' sql/catalyst/pom.xml` | `0` |
| `grep -c '<artifactId>vfdlibm</artifactId>' sql/catalyst/pom.xml` | `1` |
| `curl -sI https://repo.maven.apache.org/maven2/io/github/vecbricks/vechrono/0.1.0/vechrono-0.1.0.pom \| head -1` | `HTTP/2 200` |
| `ls $V/VarkaZoneLowering.java $TV/VarkaJavaTimeZoneData.java $TV/VarkaEmitterZoneSuite.java` | the three paths (row 682 has merged); else stop |
| `grep -n 'static .*(' $TV/VarkaEmitterZoneSuite.java` | the suite's static helpers; step 2 reads them |
| `ls $V/VarkaChronoZoneChanges.java $TV/VarkaChronoZoneFixtures.java` | both paths (row 678 has merged; the library was ported from it); else stop |
| `git grep -c 'record ToInstant(' -- $V` | a count, or nothing: it decides case 4 |
| `git grep -c 'TABLE_SCALAR' -- $V` | files with their counts, or nothing: it decides case 5 |

**Steps.**

1. `sql/catalyst/pom.xml`: after vfdlibm's dependency, the same block for `vechrono` at
   `0.1.0`, test scope, with a comment of four lines: the reference and the test oracle of
   the zone kernels (this plan, 2.3), never a kernel's run-time call, on Maven Central.
2. New `$TV/VarkaChronoLibrarySuite.java`: JUnit 5, `@VarkaSuite(ownJvm = true)`, `public
   final class`, with the Apache header of a neighbouring file. It needs one thing of
   `VarkaEmitterZoneSuite`: a static method that emits the kernel of an IR tree under given
   emit options, fills its batch arguments from a given `VarkaZoneData`, runs it over a long
   column with a validity pattern, and **returns** the output values. `VARKA-682.md` (part
   c, "Tests") promises a helper that "fills a kernel's arguments from
   `VarkaJavaTimeZoneData` and compares each row" with the reference. So:
   - if B0's grep shows a static helper that returns the output (a `long[]` or the suite's
     output type), call it, and name it in the description;
   - if the only helper compares and returns nothing, split it in this part: its emitting,
     filling and running lines move into a new package-private static method `static long[]
     runZoned(VarkaVectorIR tree, VarkaEmitOptions options, VarkaZoneData zone, long[]
     values, boolean[] nulls)`, and the old helper calls it and then compares as before.
     That is the one edit of `VarkaEmitterZoneSuite.java`, and the description says so.
   The library's enum is written by its full name,
   `io.github.vecbricks.vechrono.Resolve`, wherever the file also names Varka's
   `VarkaVectorIR.Resolve` (row 690). The suite reads only public members of the library:
   `ZoneData.build` and the members that 4.2.1 calls public, `Zones.of(ZoneRules)`, the `of`
   methods of `ZoneSteps`, `LocalSteps` and `ZoneTable`, `ZoneTable.copyTo`, and `VChrono`.
   Zones: `UTC`, `+05:30`, `America/Los_Angeles`, `Australia/Lord_Howe`, `Asia/Kathmandu`,
   `Pacific/Apia`, `Africa/Casablanca`. Inputs for each zone: the year 2025 as 4096 seeded
   instants in no order with half of them within three hours of a change, one null in ten;
   and a batch whose every row is null.
   *The range of `k` changes at `r`* is row 678's: for `k == 0`, `(atOf(r), atOf(r) + 1)`;
   for `k >= 1`, `(atOf(r) - 1, atOf(r + k - 1))`; here `r` is the number of the first change
   at or after 2025-01-01T00:00:00Z, `r = (int) data.rank(1735689600000000L - 1)` on Varka's
   entry (the suite is in Varka's package, so it reads the package-private view), and a zone
   with `size() == 0` is skipped. For the library's side the same pair of longs is passed.
   *The IR and the options* of cases 2, 4 and 5: copy the first test of
   `VarkaEmitterZoneSuite` that emits a `ToLocal` under `STEPS_2`, and change only the zone
   and the form (`STEPS_8`, `TABLE`); for case 4 copy row 690's first test of each record
   in that suite the same way.
   *What is compared.* Varka's kernels of rows 682, 686 and 690 are microsecond kernels:
   each is held to the library's form over the first word, `VChrono.toLocal`, `toInstant`
   and `toInstantKept`, which is exact since those forms leave the second word. The
   nanosecond kernels of rows 662 and 673 to 675 do not exist when V1 is built; each of
   those rows adds its own case to this suite against the two-word forms, the `*Nanos`
   forms and `plusNanos`, all of which 0.1.0 already holds, so no new pin is needed for them.
3. The cases. Cases 1 to 3 are always built. Each of cases 4 and 5 is built when its line of
   B0 printed a count; a case that is not built is named in the description with
   the row it waits for, and joins by 5.4's rule 4.

| # | case | compares | the failure only it catches |
| ---: | :--- | :--- | :--- |
| 1 | `referenceArgsEqualTheLibrarys` | for each zone id, `VarkaJavaTimeZoneData.PROVIDER.of(id)` (row 682's reference, made through its provider) and `Zones.of(ZoneId.of(id).getRules())`: `toLocalArgs` of both, array with array, for the ranges of 0, 2 and 8 changes at `r` (below) at `steps` of 2 and of 8, and `changes` of both. In the one cell where the changes exceed the steps (8 changes, 2 steps) both must throw `IllegalArgumentException`, and the case asserts that and compares no text | Varka's own reference and the library reading the range's ends two ways |
| 2 | `toLocalKernelEqualsTheLibrary` | an emitted `ToLocal` kernel over a microsecond `TIMESTAMP` column under `STEPS_2` and under `STEPS_8`, row for row (the kernel's value is the library's first word; a microsecond column has a zero second word), against `VChrono.toLocal` over the same column at the preferred species (the tail through the scalar `VChrono.toLocal`), and both against `java.time`; a null row is compared by its nullness only | a lowering and a library form that differ, seen before `java.time` is consulted |
| 3 | `productionDataEqualsTheLibrarys` | for every set of `VarkaChronoZoneFixtures.sets()` (row 678's fixtures: one `RuleSet` for each distinct `ZoneRules` of the JVM), row 678's `VarkaChronoZoneChanges.build(rules)` against `ZoneData.build(rules)`: `served()`, `size()`, `changes` and `localChanges` over 200 seeded ranges, and, for the ranges of 0, 2 and 8 changes at `r` (below), `toLocalArgs` and `toInstantArgs` under each of the three rules with 8 steps, long for long. Varka's side takes its `VarkaChronoZoneChanges.Rule rule`, the library's side `io.github.vecbricks.vechrono.Resolve.valueOf(rule.name())`. Two arrays are compared when neither side throws; when either throws, both must throw `IllegalArgumentException`, and no text is compared, since the clamp's texts differ (4.2.10) | a port of the zone data that drifted from its twin |
| 4 | `toInstantKernelsEqualTheLibrary` | the emitted `ToInstant` (`EARLIER`, `GAP_END`) and `ToInstantKept` kernels against `VChrono.toInstant` and `VChrono.toInstantKept` | the same, on the way back |
| 5 | `tableKernelEqualsTheLibrary` | the emitted kernel under `TABLE` against `VChrono.toLocal(x, table, scratch)`, and `VarkaZoneData.toLocalTable`'s longs against `ZoneTable.copyTo`'s | two layouts of one bucket |

**What does not change:** no census cell, no emitted byte (`VarkaEmittedBytesSuite` passes
untouched), no benchmark, no main source, no `dev/deps` manifest (the scope is test).

**Commands, in order.**

    build/sbt -batch 'catalyst/Test/compile'
    build/sbt -batch 'catalyst/testOnly *VarkaChronoLibrarySuite *VarkaEmitterZoneSuite'
    git grep -n 'import io.github.vecbricks' -- 'sql/*/src/main'
    python3 dev/varka_msg_check.py size

each sbt command under the lock, as `BUILD_SHEETS.md` says. Expected: the compile succeeds,
which shows that sbt resolved the jar from Central; both suites are JUnit, so sbt prints
`Passed: Total N, Failed 0, Errors 0, Passed N`, with `N` the cases built here (3 to 5) plus
the tests `VarkaEmitterZoneSuite` had before, which the builder reads from a run on the base
commit first; the grep prints nothing; the size is under 1000.

**Registered before the run:** the suite adds under 15 s to its bin (the calibration
table's nearest row is VARKA-243, a new suite at about twice its estimate; this suite
compiles at most seven kernels); no kernel differs from the library. Scored in the pull
request.

**Done when** the Build is green and the suite ran in it. Row 679 is then done: section 1's
done-when is met. **Not here:** no kernel, no change to `VarkaZoneData`, no second
dependency, no snapshot version.

### 5.3 The forms after 0.1.0: the list, and the template of one form

Row 679 closes with V1 (question 7 of 10.4). Each later form is one library pull request, built
with the Varka row that lowers it, from this template. A form's task is small on purpose
(G5): one function file, one test file, one benchmark file, in both shapes.

Every form of the list comes in both shapes, the two words and one long of nanoseconds,
and names what it does with the second word.

| form | its `java.time` definition | the second word | Varka row |
| :--- | :--- | :--- | :--- |
| `hourOfDay`, `minuteOfHour`, `epochDay` | `LocalDateTime.getHour`, `getMinute`, `toLocalDate().toEpochDay()` | not read | 657 |
| `secondOfMinute` with its fraction, `nanoOfSecond` | `LocalDateTime.getSecond`, `getNano` | read: the fraction is `micros mod 1,000,000` times 1000 plus `nanos` | 657, 662, 673 |
| `year`, `monthOfYear`, `dayOfMonth`, `dayOfYear`, `dayOfWeek`, `quarter`, `weekOfYear`, `weekBasedYear` | `LocalDate.getYear` and its kin, `IsoFields` | not read | 668, 673 |
| `localOfDay`, `localOfFields` | `LocalDate.atStartOfDay()` with no zone, `LocalDateTime.of` with a nanosecond of the second | written: 0, or the field's last three digits | 669, 670 |
| `truncatedTo` a unit of a day down to a microsecond; `withDayOfMonth(1)` and its kin | `LocalDateTime.truncatedTo`, `LocalDate.with...` | written: 0 | 670, 675 |
| `truncatedTo` a unit below a microsecond, and the casts between precisions 6 to 9 | `LocalDateTime.withNano(n - n % 10^(9 - p))`: floor, never round (`m9/PLAN.md` 2.5: "a cast to a lower precision floors the nanoseconds to a power of ten") | read and written: `nanos - nanos % 10^(9 - p)` for `p` of 7 and 8, 0 for 6 | 662, 674 |
| `plusDays`, `plusMonths` on a local time | `LocalDateTime.plusDays`, `plusMonths` | untouched | 671 |
| `nanosBetween` two values | `ChronoUnit.NANOS.between`; and `ChronoUnit.MICROS.between` for Spark's microsecond difference, which floors toward zero | read: the difference of the two second words, with a borrow | 671, 675 |
| the pair to one long and back | `micros * 1000 + nanos`; `floorDiv` and `floorMod` by 1000 | written. The way back needs a division of long lanes by 1000, which no host compiles: its issue chooses between a multiply-high form and a scalar loop, by measurement | 662 |
| the table of the local line, in both shapes | as `toInstant`, by buckets | untouched | 693 |

**The template.** For a form `f`, the issue fixes every name below before the build starts:

1. *The issue* names `f`, its `java.time` definition as one Java expression, its domain, the
   Varka row, the sibling to copy (the merged form nearest to it), and its three faults by
   name with the line each changes.
   The issue also fixes the two shapes: the two-word signature (which words go in, which
   come out; a form with two result words is two methods, as `plusNanos` is; a form that
   leaves the second word takes the first alone and has the `secondWordPassesThrough` test)
   and the one-long signature `<f>Nanos` with its range and its refusal's text.
2. *Files to add:* `M/<F>.java` (package-private, the arms); `T/<F>Test.java`;
   `src/jmh/java/.../<F>Benchmark.java` and `T/<F>BenchmarkTest.java`.
   *Files to edit:* `VChrono.java` (the two public forms, `f(x)` and `f(x, live)`),
   `Oracle.java` (the definition), `PlantedFaultTest.java` and `pom.xml` (the three faults: a
   constant one off, a branch threshold moved, a lane that is not live touched),
   `Sweep.java` (the form's name; and `Fuzz.java` once 5.6's part X3 exists), the consumer
   of `dev/jar-check.sh`, `CHANGELOG.md`, and, once 5.6's part X4 exists, `PROOFS.md` when
   the form computes other than its definition reads.
3. *The test's cases:* `shapesAgree` (the one-long result is `micros * 1000 + nanos` of the
   two-word result inside the one-long range); every second word of `Inputs.secondWords()`;
   every lane against the oracle over seeded inputs of the whole long;
   the thresholds the form branches on, each with its 64 neighbours either way; `ends()`;
   `maskedLanes`; each `VectorWidth`.
4. *The commands:* 5.1's oracle, with the fault count three higher; CI's job `alloc`;
   `dev/jar-check.sh` prints `jar-check: passed`.
5. *Not to do:* no new public class, no change to a merged form, no change to a default arm
   without a results file.

### 5.4 How a Varka row pairs a library pull request with a Varka pull request

The precedent is the math: each port was a pair of pull requests (`m8/VARKA-356.md` 2.5 and
2.6), and what each library release fixed and measured is written into the Varka row's plan
as constraints on its ports (`m8/VARKA-356.md` section 11, "What `vfdlibm` 0.2.0 to 0.3.1
fixes and measures, as constraints on the ports", on Varka's master from `137600970a6`; an
older checkout does not have it). That section's shape, so that this plan does not depend on
it: one opening paragraph that names the library's releases with their dates and the tag
whose file names the items use; then a numbered list, each item one fix or one measurement
of the library, with a bold first sentence that states it, the library's pull requests and
the Varka row in brackets, and what the Varka port must therefore do or test; and each
measurement with the results file it is from.

1. **An issue each side, linked both ways.** The library issue names the Varka row; the
   Varka row's plan names the library issue.
2. **The library pull request comes first** where it can: the form, its test against
   `java.time`, its faults and its benchmark. Its description says `Paired with
   vecbricks/varka row <n>`.
3. **The Varka pull request** lowers the form as IR nodes, by Varka's own measurement. Its
   description links the library pull request. Its suites check the kernel against
   `java.time`, through `VarkaReference` and `VarkaJavaTimeZoneData`.
4. **The cross-check against the library joins with the pin.** A Varka test can name only a
   class of the pinned release, or it does not compile. So when a release holds new forms,
   one Varka pull request raises the version in `sql/catalyst/pom.xml`, adds the cross-check
   cases of those forms to `VarkaChronoLibrarySuite`, and adds to the Varka row's plan a
   section of the shape above: what that release fixes and measures, each item with the
   kernel it constrains. Its title is `[VARKA-<row>] Check <forms> against
   vechrono <version>`.
5. **Neither side waits.** If the Varka row is ready first, it merges with its `java.time`
   checks alone, and the library form follows from the same issue.
6. **A difference between the two is a finding of both.** It becomes a hard-case line in the
   library and a fixture row in Varka before the fix of the one or of the other merges.
7. **The zone database is Varka's first.** A change to it is made in Varka's
   `VarkaChronoZoneChanges` or `VarkaChronoZones`, and the library then ports the merged
   change; V1's case 3 fails from the next pin until the library has it.

### 5.5 Releases and versions

- **Coordinates:** `io.github.vecbricks:vechrono`. **The contract** of 4.6 is the one thing
  no version changes; a lane that differs from `java.time`'s is a bug and a fix.
- **While the version is 0.x:** a minor may break the public API and a patch may not. Adding
  a form is a patch. Changing which arm a form takes is a patch, since every arm gives the
  same lanes. The property `vechrono.fault` and the fault names are no part of the API.
- **0.1.0** holds 4.1's first scope and is cut when L1 to L18 have merged, the last night is
  green and the results files of L17 are committed. **0.1.x** adds forms as their Varka rows
  come, one release for a wave of forms, not one a form. **No later release changes a
  signature for nanoseconds:** the two words and the one long are both in 0.1.0, and a form
  added later is born in both shapes. 0.2.0 is not planned; it is the number a break of the
  API would take, if one were ever needed.
- **The release is owner-run, from a tag,** on his machine, with his key (P0 item 6; L19
  has the steps). No workflow publishes and the repository holds no secret. In this row a
  release is a pull request with five hand edits (L19); the script and the dry run that
  vfdlibm has are part X1 of 5.6.
- **The changelog** is in the form of Keep a Changelog: a pull request that changes what a
  client sees adds one line under `## [Unreleased]`, in `### Added`, `### Changed` or `###
  Fixed`; a release pull request moves them under `## [<version>]` and adds the version's
  link line.
- **Varka pins a released version and never a snapshot.**

### 5.6 Parts the milestone's done-when does not need

The row's done-when asks for a repository that builds and tests in its own CI, the every-zone
sweep, the forms' benchmarks at each vector width, a release on Maven Central, and a Varka suite
that checks a kernel against it. L1 to L19 and V1 give all of that. The five parts below give none
of it. Each is a part of its own, after the release, and gates nothing. **The plan is written on
leaving them to a later row** (the owner's decision 6 of 10.4, and P0 item 11): the row closes at
L19 and V1 without them. If the owner wants one in this row, it is built from its sheet here,
after L19, with no other sheet changed.

| part | what it is | needs | gives | est. lines |
| :--- | :--- | :--- | :--- | ---: |
| X1 | the release automation | L19 | `dev/release.sh`, `dev/release-version.sh`, `release.yml` (a dry run) | 650 |
| X2 | the jar check's self-test | L18 | `dev/jar-check.sh --self-test` | 250 |
| X3 | the fuzz, and the nightly's two other jobs | L16 | `Fuzz`, the jobs `fuzz-avx2` and `arm` | 550 |
| X4 | the written proofs | L6, L8, L10 | `PROOFS.md`, five lemmas | 200 |
| X5 | a zone by its id, or an exception | L5 | `Zones.of(ZoneId)`, `ZoneNotServedException` | 120 |

Two more things the review named are not parts, and stay in the row: `wholeMinutesFrom()`
and `wholeHoursFrom()` are lines of the file that L4 ports, and leaving them out would make
the port differ from Varka's file (L4's cut line moves them if the size asks); the nightly
`Sweep` is the done-when's "every-zone sweep".

#### X1. The release automation

*Title:* `[VECHRONO-<issue>] Prepare releases by a script, and try them on GitHub's runners`.
*Branch:* `vechrono-x1-release-tooling`.

**B0.** `ls dev/release.sh dev/release-version.sh .github/workflows/release.yml` fails for
all three. `grep -c 'workflow_call' .github/workflows/ci.yml` prints `1`. `grep -c
'<id>release</id>' pom.xml` prints `1`. `ls dev/jar-check.sh` prints the path.

**Steps.**

1. `dev/release-version.sh` (vfdlibm's, 123 lines) and `dev/release.sh` (318 lines), with
   the names changed and nothing else. What `release.sh` requires of the tree: a clean `main`
   at `origin/main`; no tag `v<version>`; a `## [<version>]` section and a `[<version>]: `
   link line in `CHANGELOG.md`; a pom at `<version>-SNAPSHOT`. It sets the pom's version, its
   `<scm><tag>` and `project.build.outputTimestamp`, dates the changelog section, runs `mvn
   -B -Prelease -Dgpg.skip=true clean verify` and `dev/jar-check.sh --release`, commits
   "Release <version>", tags, and commits the next snapshot: L19's hand edits, by a script.
2. `.github/workflows/release.yml`, vfdlibm's with three of its four jobs: `version` (the
   two scripts' self-tests, and on a tag `dev/release-version.sh "$GITHUB_REF_NAME"` and the
   test that the tagged commit is on `main`), `ci` (`uses: ./.github/workflows/ci.yml`,
   skipped on a pull request) and `bundle` (the release profile signed with a key made for
   the run, each of the four files and its signature checked, `dev/jar-check.sh --release`,
   the files kept seven days as `dry-run-bundle`). The job `proofs` goes. Its `on:` keeps the
   tag push, the dispatch and the pull-request paths, less `dev/prove.sh` and `proofs/**`.
   Its first comment lines stay: a dry run, which never publishes and holds no secret.
3. `AGENTS.md`: "Releases" names the script in place of L19's hand edits.

**Commands.** `dev/release-version.sh --self-test` prints `self-test: passed`.
`dev/release.sh --self-test` prints `release.sh: self-test passed` as its last line.
`dev/release-version.sh` with no argument prints `release-version: pom.xml holds version
<the pom's version>, <scm><tag> HEAD`. `grep -c 'secrets\.' .github/workflows/release.yml`
prints `0`.

**The cut line.** If the size passes 900: `release.yml` becomes a pull request of its own,
branch `vechrono-x1b-dry-run`.

**Done when** the dry run's jobs `version` and `bundle` are green on the pull request.
**Not here:** no tag, no upload.

#### X2. The jar check's self-test

*Title:* `[VECHRONO-<issue>] Show that the jar check can fail`.
*Branch:* `vechrono-x2-jar-self-test`.

**B0.** `grep -c 'self-test' dev/jar-check.sh` prints `0`.

**Steps.** `dev/jar-check.sh` gains vfdlibm's option `--self-test` and its function
`mutant`: it makes eight copies of the jar with one defect each (`no-notice`, `no-licence`,
`missing-class`, `no-module-name`, `stray-file`, `stranger-class`, `nested-stranger`,
`module-info`; the class the copies are made from is `VChrono.class`) and requires the
script to exit 1 on each with the line that names the defect. `ci.yml`: the first matrix row
of `build` runs it after the jar check.

**Commands.** `dev/jar-check.sh --self-test` prints eight lines `ok    <defect>: exit 1,
with "<line>"` and then `jar-check: self-test passed`.

**Done when** CI is green.

#### X3. The fuzz, and the nightly's two other jobs

*Title:* `[VECHRONO-<issue>] Fuzz the forms from a fresh seed every night, as AVX2 and on Arm`.
*Branch:* `vechrono-x3-fuzz`.

**B0.** `ls T/Fuzz.java` fails. `grep -c 'fuzz-avx2\|ubuntu-24.04-arm'
.github/workflows/nightly.yml` prints `0`.

**Steps.**

1. `T/Fuzz.java`, a `main` over `static int run(String[] args)`, with `--seed`, `--seconds`,
   `--species`, `--out`. It prints `seed <seed>` first. Each round draws: a set of
   `Inputs.sets()`; a function (`toLocal`, `earlier`, `gapEnd`, `kept`); a shape (the two
   words, with drawn second words, or the one long, with the range then drawn inside the
   one-long range); a range (a week, a
   year, ten years, 1000 years, the whole long; three rounds in ten are moved to start or
   end exactly on a change); 4096 inputs inside the range, half within three hours of a
   change of the range; a mask; and whether the count is forced. Then, by the count `c` of
   the range (`changes`, or `localChanges`):
   - `c <= 8`: the steps, at the least count or a forced one that is not below `c`;
   - `c > 8`, the function is `toLocal`, the zone has a table and the range is inside it:
     the table form, both arms;
   - otherwise: the round asserts that `ZoneSteps.of` or `LocalSteps.of` throws
     `IllegalArgumentException`, and counts it under `refused`.
   Its last lines are `<rounds> rounds, <refused> refused` and `<n> mismatches`; `run`
   returns 0 with no mismatch and 1 otherwise.
2. `T/NightlyTest.java`: one more test, `Fuzz.run` for 2 seconds from the seed 679, required
   to return 0.
3. `dev/nightly.sh` takes two more arguments, `<seed> <fuzz-seconds>`, and runs `Fuzz` after
   `Sweep` when `<form>` is not `table`. `nightly.yml` gains the inputs `seed` (default
   empty; the jobs use `${{ inputs.seed || github.run_id }}`) and `fuzz-seconds` (default
   `120`), and two jobs that `alert` then also needs: `fuzz-avx2` (`ubuntu-latest`, the five
   forms with `NIGHTLY_JVM_ARGS="-XX:UseAVX=2 -XX:MaxVectorSize=32"` at `max`) and `arm`
   (`ubuntu-24.04-arm`, the five forms at `max`). Both honour `inputs.forms` and
   `inputs.fault` as `sweep` does.

**Commands.** The oracle of 5.1. After the merge the lead dispatches `nightly.yml` once with
no input (P0 item 9): green.

**Done when** the first scheduled night with the three jobs is green.

#### X4. The written proofs

*Title:* `[VECHRONO-<issue>] Write down why each form equals its definition`.
*Branch:* `vechrono-x4-proofs`.

**Steps.** `PROOFS.md`: a header (a lemma holds its statement, its hypotheses, its proof,
and the test that checks its hinges and fails if a hinge were false) and five informal
lemmas, each with a Javadoc pointer added at the code it is about:

1. The offset at an instant is the offset after the last change at or before it, given `at`
   sorted (C2). Hinge: `ZoneDataTest` 2.
2. Past the block the data repeats by `CYCLE_MICROS`, since a rule's change is a function of
   the year and 400 Gregorian years are 146097 days, a whole number of weeks. Hinges: C4, C5
   and `ZoneDataTest` 4.
3. With the windows apart (C6), the three rules are the three scalar meanings of 4.2.5.
   Hinges: C6, C7 and `ZoneDataLocalTest` 2 to 4.
4. The step with no mask equals the masked step where `at - 1 - x` is a long. Hinge:
   `noMaskExact()` and `ToLocalTest.armsAgree`. It names Varka's
   `sql/varka/proofs/zone_step.smt2` (row 684) as the machine-checked form.
5. The index of every long is inside the table, and an offset in units of 64 microseconds
   unpacks to the microseconds that were packed. Hinge: `TableTest.packWords` and
   `notLiveLanesOutsideTheTable`. It names Varka's `zone_bucket.smt2` (row 686).

**Commands.** The oracle of 5.1 (the Javadoc pointers compile). **Done when** CI is green.

#### X5. A zone by its id, or an exception

*Title:* `[VECHRONO-<issue>] Give a zone's entry by its id, or say why the zone is not served`.
*Branch:* `vechrono-x5-zone-by-id`.

**B0.** `ls M/ZoneNotServedException.java` fails. `grep -c 'ZoneId' M/Zones.java` prints `0`.

**Steps.** `M/ZoneNotServedException.java`: a public `RuntimeException` with one constructor
that takes the message. `M/Zones.java`: `public static ZoneData of(ZoneId zone)` is
`served(zone.getId(), of(zone.getRules()))`, and the package-private `static ZoneData
served(String id, ZoneData data)` returns `data` if `data.served()` and else throws `new
ZoneNotServedException(id + " is not served: " + data.check().failure())`. The exception is
built outside the map, from the id the caller gave; the entry holds no id, and the failed
entry stays in the map. `README.md`'s example takes `Zones.of(ZoneId)`. `CHANGELOG.md`: one
line, "Added". The consumer of `dev/jar-check.sh` calls it.

**Tests.** `ZonesTest` gains test 8: `Zones.of(ZoneId.of("America/Los_Angeles"))` is the
entry of `of(zone.getRules())`; `Zones.served("made-up/out-of-year",
Zones.of(ZoneFixtures.outOfYear()))` throws `ZoneNotServedException` whose message starts
`made-up/out-of-year is not served: java.time differs at instant`; `Zones.of((ZoneId) null)`
throws `NullPointerException` with the message `zone`. The failure only it catches: a
refusal that names no zone.

**Commands.** The oracle of 5.1. **Done when** CI is green.

## 6. Tests, proofs, fuzzing and coverage

**The method** is differential: every form is compared with `java.time`, called once a lane
on the same JVM, with that JVM's rules. No expected value is committed (4.6).

| suite | what it holds | the failure only it catches |
| :--- | :--- | :--- |
| `HarnessSelfTest` (library, JUnit 5) | the oracle's two directions agree; the checks fail when a lane is wrong | a harness that passes everything |
| `ZoneDataTest` (11), `ZoneDataLocalTest` (11), `ZonesTest` (7) | row 678's tests of the zone data, ported, by number: every set of rules against `java.time` at every change, in the rule era, at the ends; the counts; the layouts; made-up rules; the cache | a zone whose data differs from `java.time` |
| `OneLongTest`, `NanoTableTest`, `PlusNanosTest` | the one-long arm of every form against `nanosOf` of the oracle's pair, and against the two-word form on the same values; `plusNanos`' carry in both shapes | a shape that differs from the other; a second word that is wrong |
| `ToLocalTest`, `TableTest`, `ToInstantTest`, `ToInstantKeptTest` | every form, arm, count and width against the oracle; masks; the there-and-back check | a form whose lanes differ from its definition |
| `PlantedFaultTest` | 32 faults, each failing one named case in a JVM of its own | a test that passes for another reason |
| `*BenchmarkTest` | each benchmark row's output against the oracle | a number that times a wrong answer |
| `NightlyTest`, then `Sweep` every night | every second around every change of every set of rules, under each rule, at each width | a change or a zone no written case holds; a tzdb update |
| `VarkaChronoLibrarySuite` (Varka, catalyst, JUnit 5) | Varka's reference, production data and emitted kernels against the library | a kernel and a library form that differ |

**The hard cases** are inputs only, and nanosecond-native: `hard-cases/zones.txt` (L2), and the
mismatch lines a failed night prints, which go into `hard-cases/<form>.txt` (`<zone> <micros>
<nanos>`, and for the kept offset `<zone> <micros> <nanos> <kept>`) with the fix; a line is tried
in both shapes where it is inside the one-long range; each form's test reads its file when the
file exists. `Inputs.ends()` holds the calendar's ends and the long's.

**The planted faults,** each with the case whose body must fail by an assertion:

| fault | part | caught by |
| :--- | :--- | :--- |
| `selftest.oneMicro` | L2 | `HarnessSelfTest.fakePasses` |
| `selftest.notLiveLane` | L2 | `HarnessSelfTest.fakeMaskedPasses` |
| `zone.rankLtForLe` | L3 | `ZoneDataTest` 2 |
| `zone.changesCountsLeast` | L3 | `ZoneDataTest` 7 |
| `zone.noPadding`, `zone.deltaIsAfter` | L3 | `ZoneDataTest` 8 |
| `zone.skipC4` | L3 | `ZoneDataTest` 9 |
| `zone.cycleDayShort` | L3 | `ZoneDataTest` 11 |
| `zone.hiMinForMax` | L4 | `ZoneDataLocalTest` 2 |
| `zone.keptLoForGap` | L4 | `ZoneDataLocalTest` 3 |
| `zone.gapEndLoForOverlap` | L4 | `ZoneDataLocalTest` 4 |
| `zone.noClamp` | L4 | `ZoneDataLocalTest` 5 |
| `zone.offsetsStartAfter` | L4 | `ZoneDataLocalTest` 10 |
| `zone.skipC6` | L4 | `ZoneDataLocalTest` 11 |
| `zones.keepsFixed` | L5 | `ZonesTest` 2 |
| `zones.noMap` | L5 | `ZonesTest` 4 |
| `zone.fillAllocates` | L5 | `ZonesTest` 7 |
| `toLocal.gtForGe` | L6 | `ToLocalTest.everyZoneAtEveryChange` |
| `toLocal.deltaSign` | L6 | `ToLocalTest.everyCount` |
| `toLocal.noMaskOffByOne` | L6 | `ToLocalTest.armsAgree` |
| `toLocal.maskedLaneTouched` | L6 | `ToLocalTest.maskedLanes` |
| `table.unclampedIndex` | L8 | `TableTest.notLiveLanesOutsideTheTable` |
| `table.halvesSwapped` | L8 | `TableTest.everyZoneAtEveryChange` |
| `toInstant.gapEndAsEarlier` | L9 | `ToInstantTest.gapInteriors` |
| `toInstant.keptIgnored` | L10 | `ToInstantKeptTest.keptInsideEveryOverlap` |
| `nanos.atNotScaled` | L11 | `OneLongTest.toLocalAtEveryChange` |
| `nanos.deltaNotScaled` | L11 | `OneLongTest.shapesAgree` |
| `nanos.noRangeCheck` | L11 | `OneLongTest.rangeEnds` |
| `nanoTable.beforeForAfter` | L12 | `NanoTableTest.everyZoneAtEveryChange` |
| `plusNanos.noCarry`, `plusNanos.carryAbove1000` | L13 | `PlusNanosTest.carryEdges` |
| `plusNanos.maskedLaneTouched` | L13 | `PlusNanosTest.maskedLanes` |

Why each can fail by an assertion and not by another exception:

- A fault inside `ZoneData` can make the build-time check refuse every zone. The tests of L3
  and L4 take their entries from `ZoneData.build`, which never throws, and compare values,
  so such a fault still ends in `eachSet`'s `Assertions.fail`. No catcher of a `zone.*`
  fault goes through a method that refuses an entry which is not served.
- `zone.cycleDayShort` is caught by test 11's `assertEquals` on the constant, before any
  entry is built.
- `zone.noClamp`: test 5's body asserts `localChanges == 4096` before it calls
  `toInstantArgs`.
- `table.unclampedIndex` would throw `IndexOutOfBoundsException` from the gather; the case
  wraps the call in `assertDoesNotThrow`.
- `zone.skipC4` and `zone.skipC6`: the cases assert the failure's text with `assertTrue`.

**Proofs.** This row writes none. The equality with `java.time` is no theorem: it is a
property of the JVM's data, held by the build-time check and the sweep, as `m9/PLAN.md` 2.2
says of Varka's. The machine-checked files stay Varka's (`sql/varka/proofs/zone_step.smt2`
of row 684, `zone_bucket.smt2` of row 686). Five informal lemmas are part X4 of 5.6.

**Fuzzing.** This row has none in the library: the nightly is the exhaustive `Sweep`. A
random fuzz is part X3 of 5.6. In Varka, V1 adds no fuzz production.

**Coverage.** The library has no coverage tool, in this row or in 5.6. What holds each
branch instead:

- *The two ported files.* Row 678 runs JaCoCo over the same code in Varka and names each
  missed branch in its pull requests (`VARKA-678.md` 6.3); the port changes no branch. In
  the library every failure arm of C1 to C4, C6 and C7 is reached by a made-up set of rules
  (tests 9 and 11), and C5's only by the fault `zone.skipC4`, as in Varka.
- *The forms.* Each arm and each written-out count is named by a case: `everyCount` runs
  0, 2 and 8 steps of every form; `armsAgree` runs both arms of `toLocal`;
  `TableTest.everyZoneAtEveryChange` runs both table arms; `maskedLanes` runs each masked
  form; `refusals`, `wrongRule`, `clamp`, `shortScratch`, `noTable` and `packRefuses` reach
  each `throw` of 4.8.
- *The 32 planted faults* are the hand-written mutants: each shows that one of those cases
  fails when its branch is wrong.

In Varka, V1 adds no IR record and no coverage row, so no registry suite is touched.

**What runs only in CI:** the four rows of `ci.yml` (three x86 settings and Arm), the job
`alloc`, the nightly, the host benchmarks, and Varka's option matrix over V1. **What the
builder runs locally first:** 5.1's oracle at the laptop's own width, then once more with
`-Dvechrono.test.jvmArgs="-XX:UseAVX=2 -XX:MaxVectorSize=32"` and once with
`-Dvechrono.test.jvmArgs=-XX:MaxVectorSize=16`.

## 7. Measurement

**The rule.** A claim about speed traces to a file under `bench/results/`. The method is
fixed: JMH, `-f 2 -wi 3 -w 2s -i 5 -r 2s -prof gc`, the body inlined with
`-XX:CompileCommand=inline,io.github.vecbricks.vechrono.*::*`, the arms of a comparison in
one run. A results file is what `benchmark.yml` wrote (L17): the host, the commit, the run,
the JDK build, the CPU and its flags, the vector flags, the kernel, the load at start and
end, the JMH options and the command, then JMH's output with the tzdb version in it. A ratio
under 1.3 is run again and compared by minimums, and both files are kept. The laptop's
numbers are never quoted.

**Where.** The AMD EPYC 7763 runners at 256 bits and, with `-XX:MaxVectorSize=16`, at 128
bits: the widths of `m9/PLAN.md` 1.2, item 10. One AVX-512 host, the Intel Xeon Platinum
8573C, adds 512 bits. Every run is a dispatch of `benchmark.yml` (L17, P0 item 7).

**The sections and the arms** are L7's and L14's rows: for each form the `java.time` loop,
the public form at 256 bits, at the preferred species and at `SPECIES_MAX`, and the A/B
arms: `kernelShapedPreferred` (the masked arm) against `noMaskPreferred`; `stepsPreferred`
(the public form) against `kernelShapedPreferred`; `stepsOf8Preferred` against
`stepsPreferred`; `gatherPreferred` against `scalarLoadsPreferred`; `gatherPreferred`
against `stepsPreferred` on `year` data; and, in `ShapesBenchmark`, the two words against
the one long for `toLocal` by steps and by table, for `toInstant` and for `plusNanos`, on
the same values.

### 7.1 Predictions, registered on 27 November 2026, before any run

The laptop's numbers behind them are the probes' (`sql/varka/spikes/zone-offset/results/`
in Varka); each prediction is scored from the library's results file of L17, or as named.

1. `ToLocalBenchmark.stepsPreferred` on `year` data at 256 bits on the EPYC 7763: under 0.3
   ns a row (the probe's two steps cost 0.148 at four lanes on the laptop,
   `results/forms.txt`).
2. `javaTimeOffset` costs at least 20 times `stepsPreferred` on the same data at 256 bits.
3. The public form allocates under 1 B/op in every row, and `stepsPreferred` is within 1.3
   times `kernelShapedPreferred`: C2 hoists the scalars. Scored by CI's job `alloc` (L7) and
   by the file `to-local`.
4. At 128 bits on the EPYC 7763 (the file `to-local-128`), `noMaskPreferred` is at least
   five times as fast as `kernelShapedPreferred`, the row that writes the masked arm out,
   on `year` data (the probe: 0.685 against 13 ns a row, `results/forms.txt`).
5. `TableBenchmark.gatherPreferred` on `decade` data at 256 bits: under 1.0 ns a row (the
   probe's three gathers cost 0.88 to 0.95, `results/forms.txt`).
6. `ToInstantBenchmark.earlierPreferred` at 256 bits: under 0.3 ns a row; `keptPreferred`:
   under 0.6 (the probe: 0.138 and 0.351, `results/kernels.txt`).
7. At 128 bits (the file `to-instant-128`) `keptPreferred`, which has no arm without a mask
   in 0.1.0, is not faster than `javaTimeOfLocal` (`VARKA-657.md` 2.3). Yes or no.
8. Test time, from counts. The sets of the JDK hold at most 65,794 stored changes before
   2100 (`results/facts.txt`, over all ids; fewer over distinct sets). A case over every
   change with 246 lanes a change (five points and every 60 s for two hours either side) is
   at most 16 million lanes; with second words cycling it is run once, at up to three
   widths with the oracle computed once: 16 million oracle calls. L6, L9 (two rules), L10,
   L11 (three cases) and L12 have ten such cases, L3's test 6 and L4's test 6 add under 80
   million between them, and `NightlyTest` under 2 million: under 250 million oracle calls
   in all. The probes price `java.time` at 10 ns for an offset and under 45 ns for a
   local time resolved (`results/forms.txt`, `results/kernels.txt`): under 12 s of one
   core. Registered: the test classes of L3 to L13 add under 120 s to `mvn verify` on a CI
   row, the 32 fault runs under 5 minutes more, and a night's slowest `sweep` job, whose
   one-second step makes about 58 times those lanes, takes under 15 minutes on four cores.
   Scored from the workflow runs' own times.
9. The row takes one founding commit, 19 library pull requests (L2 to L19, and L19's second, the
   next snapshot) and one Varka pull request, none over 1000 counted lines. At most four cut lines
   are taken, among L2, L6, L9 and L14, the parts nearest the cap. No part of 5.6 is built in the
   row. Scored at the close.
10. No tzdb update between L3's merge and the row's close fails `ZoneDataTest` 1 or
    `TableTest.everyZoneHasATable`. Yes or no, from the nightly's issues.
11. The two shapes of a form that leaves the second word cost the same: in the file
    `shapes`, `toLocalOneLongPreferred` and `toLocalTwoWordsPreferred` are within 1.3 times
    each other at 256 bits, and so are the two `toInstant` rows. The code is the same and
    the two-word form does not touch its second array.
12. The table differs: `toLocalTableOneLongPreferred`, three gathers of 24 bytes a bucket,
    costs between 1.2 and 2 times `toLocalTableTwoWordsPreferred`, two gathers of 16 bytes
    (`VARKA-657.md` 6.1 predicted the packed table under 0.7 ns a row where three gathers
    cost 0.9).
13. `plusNanos` is where the one long wins: `plusNanosOneLongPreferred` is at least 1.5
    times as fast as `plusNanosTwoWordsPreferred` at 256 bits, since one shape loads two
    arrays and stores one, and the other loads four and stores two with a compare between.
14. At 128 bits (the file `shapes-128`) the two-word `plusNanos`, whose carry is a mask, is
    not faster than `javaTimePlusNanos` by more than 3 times, and the one-long `plusNanos`,
    a plain add, is at least 10 times as fast as it. Row 689's question, asked of a carry.

## 8. Who builds it

- **P0:** the owner.
- **L1 to L19 and V1: at full strength.** They fix the contract, the harness that every later
  form is judged by, and the release. A fault in the harness passes wrong forms, and a
  review cannot see it from a form's pull request. L3, L4 and L5 are ports: their work is
  the fault constants, the catchers and the proof that nothing else changed.
- **The parts of 5.6,** if they are built: X1 and X3 at full strength; X2, X4 and X5 are
  small and closed, and a cheaper model can take each from its sheet.
- **The forms of 5.3: by the cheaper models,** each from the template with its issue, with
  `mvn -B verify` as the one-command oracle (`m9/PLAN.md` 2.10: "a function with `java.time`
  as its oracle is the smallest task a cheaper model can take"; goal G5). A build there
  takes no Spark and no laptop lock beyond one Maven run. The exceptions are the table of
  the local line and the two-word forms, which change a layout: full strength.

## 9. Risks

1. **A second repository to keep** (`m9/PLAN.md` section 5: "vfdlibm's rule is the control: a
   port here is paired with the pull request there, and nothing here waits on a release").
   Held by 5.4, and by leaving out of 0.1.0 every tool vfdlibm grew later (5.1).
2. **The public form boxes or allocates,** because C2 does not hoist the scalars or does not
   inline the form. Prediction 3 and CI's job `alloc` find it in L7, before any other form is
   built on the shape. Then the part stops, and the owner chooses between a species-bound
   object that holds broadcast vectors and static methods that take the vectors.
3. **One design in two repositories drifts.** The library's two files are ports of Varka's
   merged files, each checked by a `diff` in its sheet; V1's case 3 compares the two classes
   long for long; rule 7 of 5.4 says where a later change is made first.
4. **Time-zone data differs** between the JDK of CI and of an executor (`m9/PLAN.md` section
   5). The library holds none, commits no expected value but test 8 of L4, and names its
   tzdb version in every run (4.6). A newer tzdb that breaks a structural premise (a check
   of C1 to C7, one change a bucket) fails the night it arrives, by name.
5. **A set of rules of the JDK fails the check.** Test 1 of L3 fails with the id and the
   reason, and the part stops: it is a finding for the owner, in both repositories.
6. **The licence.** `java.time` in OpenJDK is GPL with the Classpath exception. Neither
   plan's builder opens its source (`VARKA-678.md` section 3 and its open question 5; 5.1
   here). The library copies no line of it, and each fact of its behaviour that the design
   rests on is held through the public API by C3, C4 and C7 (decision 4 of 10.4).
7. **The release depends on one person and one machine,** and in this row on five hand
   edits. Nothing in Varka waits on it (10.1); L19's pull request runs the release profile
   and the jar check in CI before the tag; the script and the dry run are part X1.
8. **The plan ages.** vfdlibm moves daily. Each sheet names the vfdlibm version it cuts
   from (`v0.3.1`, or a commit), not its `main`.
9. **Two parts add faults at once** and conflict in `pom.xml`. Section 5 says which parts
   run side by side; the second to merge takes `main` first.
10. **A GitHub runner without the expected CPU.** The CPU check fails the run, and it is
    dispatched again; the check is never relaxed.
11. **The library waits for row 678's parts a to c.** L1 and L2 do not; L3 stops at its B0
    until they have merged. The row's length follows row 678's.
12. **The second word doubles the arguments of every later form.** A form with two result
    words is two methods, and a caller that wants both pays the shared work twice unless C2
    merges it. `ShapesBenchmark`'s `plusNanosTwoWordsPreferred` row measures exactly that,
    and prediction 13 is its price; if it is worse than 3 times, the template of 5.3 gains
    a form that writes both words into two arrays.

## 10. Seams, exclusions and house rules

### 10.1 Seams with the plans written beside this one

**Row 678** (`VARKA-678.md` of `sql/varka/plans/m9/`, merged on 27 November 2026 as
vecbricks/varka#1782). Its section 11 states the seam, and this plan follows it.

| what | row 678 has | this row has |
| :--- | :--- | :--- |
| the entry | `VarkaChronoZoneChanges`, in `sql/catalyst`'s main sources, importing only the JDK | `ZoneData`: a port of the merged file (L3, L4) |
| the rules | `VarkaChronoZoneChanges.Rule { EARLIER, GAP_END, KEPT }` | `Resolve`, top level, the same constants in the same order |
| the cache | `VarkaChronoZones` | `Zones`: a port of the merged file (L5) |
| the `VarkaZoneData` record | `VarkaChronoZone`, with `PROVIDER`; it calls Spark's `DateTimeUtils.getZoneId` and stays in Varka | nothing |
| the tests | `VarkaChronoZoneChangesSuite` (11), `VarkaChronoZoneLocalSuite` (11), `VarkaChronoZonesSuite` (7), the fixtures | `ZoneDataTest` (11), `ZoneDataLocalTest` (11), `ZonesTest` (7): ports of the merged suites, with the same made-up rules and expected numbers |
| the faults | planted by hand after the commit, and reverted | constants behind `Faults` (4.2.10) |
| the table | `changesAt`, `changeOffsets` and `memo` for row 686's `VarkaZoneTable.pack` | the same three methods, read by `ZoneTable` |

- **The order has no condition.** Row 678's section 11: "Row 678 is built first, in
  `sql/catalyst`: the library's repository does not exist. A builder of this row never
  looks for the library, imports nothing of it, adds no dependency and links no pull request
  of it. After part c has merged, row 679 ports this row's two merged files into the
  library, `VarkaChronoZoneChanges` first and then `VarkaChronoZones`". So L3's B0 stops
  until part c has merged, L3 and L4 port the first file and L5 the second, and no pull
  request of row 678 links one of the library.
- **Which text binds.** Row 678's section 11: "Where `VARKA-679.md` words the algorithm, the
  check or a method otherwise, this plan binds the Varka classes, and the port follows the
  merged files." 4.2 here is a restatement for the reader; 4.2.10 lists the four places
  where the library's files differ on purpose.
- **The cross-check** is this row's: V1's case 3, in `VarkaChronoLibrarySuite`, compares the
  two classes long for long for every set of rules. No suite of row 678 reads the library.
- **One meaning of `localChanges`** in both: the windows with `hi > least` and `lo <=
  greatest`, capped at `MANY`, and `MANY` outside the clamp. Only Varka's test reference
  `VarkaJavaTimeZoneData` (row 682) counts an upper bound, and row 678's plan edits row
  682's text to say so.
- **A name to mind in V1.** Varka's `VarkaVectorIR.Resolve` (row 690) and the library's
  `Resolve` meet in one test file; V1 writes the library's by its full name.

**Rows 654 and 656** (row 654's plan is merged too, as vecbricks/varka#1783). None. The library
reads no census file and no lane, and V1 adds no census cell.

**Rows 660 and 662** (filed, with no plan yet; `m9/PLAN.md` 2.5). The seam this plan
assumes:

- Row 660 decides how precisions 7 to 9 enter the census. The library does not depend on
  the answer: its value has nanosecond precision, and a precision of 7 or 8 is the same two
  words with a second word that is a multiple of 100 or of 10.
- Row 662 builds two layouts in kernels, and the library has each first: layout (a), "Two
  words, the cache's own form", is the library's two words; layout (b), "One long at the
  column's precision", is the library's one long at precision 9. One long in units of 100
  ns or of 10 ns (precisions 7 and 8) is not in 0.1.0 (question 13 of 10.4).
- What row 662 takes: the forms of 4.9 as its test reference, in the suite V1 adds, with no
  new pin; and the file `shapes` of L17 as the first measurement of its two layouts.
  *28 November 2026:* row 662 now measures four representations in Varka's own harness
  (`VARKA-662.md` 4.1), with divisions that neither divide nor convert to a double (its 4.2) and
  guards with no minimum or maximum (its 4.3), which the library's kernels can take. The
  library's contract stands: a caller hands it Spark's two parts, and one long is its arm. Where
  this plan says that row 662 builds a layout or a kernel, read row 699. Row 699 builds seconds
  and days too, by the owner's answer of the same day (`VARKA-662.md` section 19), so the forms
  of 5.3 gain those two shapes as arms, by an issue each; the file `shapes` stays the library's
  own measurement.
- What row 662 adds, and the library does not: the lane type of the second word in a kernel
  (the library's is a `LongVector` for readability, 4.9), the load from the cache's struct
  of a long and a short, and the range check that sends a batch outside the one-long range
  to the two-word kernel or to the row path.
- Rows 673 to 675 take each later form from 5.3 in both shapes, paired as 5.4 says.

**What this row hands to row 657 and to rows 682 to 690,** by name: the classes and methods
of 4.2 to 4.5 in `io.github.vecbricks:vechrono:0.1.0`, read in Varka's test scope only; the
hard-zone list of L2, which is the list `VARKA-657.md` section 5 gives the fuzzers plus
nine more; and the fault names of section 6, which mirror `VarkaZonePlantedFaultSuite`'s
(`VARKA-657.md` section 5: "a threshold one microsecond off, `>` for `>=`, a delta's sign").
None of those rows waits for it: each tests against `VarkaJavaTimeZoneData` until V1.

### 10.2 What is out of the row

| excluded | where it is |
| :--- | :--- |
| the zone data the evaluator reads, its provider, its build time and size in Varka | row 678 |
| the IR records, the batch arguments, the lowerings, the runner | rows 682, 686, 689, 690 |
| Varka's every-zone sweep, its fault suite, the fuzzers' zone | row 684 |
| the cost of a form inside a kernel, the default form by width, G7's bars | row 691 |
| the forms after 0.1.0 | the Varka row of 5.3's table, each with a library issue |
| the table of the local line | row 693 |
| the forms over the second word beyond `plusNanos` (5.3), in both shapes | rows 662 and 673 to 675, each with a library issue |
| one long at precisions 7 and 8 | row 662, if its measurement asks (question 13 of 10.4) |
| parsing, formatting, a zone read from a column | out of the milestone (`m9/PLAN.md` 1.3) |
| the ledger line of a library pull request | row 658 |
| the tzdb entry of the register of upstream behaviours | row 681, with row 657 |
| the release script and its dry run, the jar check's self-test, the fuzz with its two nightly jobs, `PROOFS.md`, `Zones.of(ZoneId)` | a later row, by the owner's decision 6 of 10.4; their sheets are 5.6 |

### 10.3 The house rules that bind this row

From Varka's `sql/varka/AGENTS.md` and `CLAUDE.md`, for V1:

- "Java code: modern Java 25": the new suite is Java, a `final class`, JUnit 5.
- "Every new text file [...] needs the Apache License header, copied from a neighboring file
  of the same type".
- The only attribution is one `Generated-by: <tool> (<model>)` line.
- A pull request is under 1000 changed lines, and its title names the goal in at most 80
  characters.
- "Never remove, skip or weaken a failing test": a difference between a kernel and the
  library is a finding, not a case to drop.
- New zone classes in Varka take the `Chrono` name (`m9/PLAN.md` 2.2, "Names").

For the library, from vfdlibm's `AGENTS.md`, which L1 carries over:

- "Changes go through pull requests on branches, never straight to `main`", but for the
  founding commit and the owner's two release commits.
- "Any performance claim, in a comment, a document or a pull request, traces to a committed
  results file under `bench/results/`."
- "Varka pins a released version and never a snapshot."

**Do not:** create the repository, push to it, open a pull request in it, tag, deploy or dispatch
a workflow without the item of P0 that covers it; commit a time-zone data file or an expected
value; assert a count of the JDK's zones; open OpenJDK's `java.time` sources; add a run-time
dependency; call the library from a kernel or from any main source of Varka; put a snapshot or a
local build of the library in `sql/catalyst/pom.xml`; time anything on the shared laptop for a
headline.

### 10.4 Questions for the owner: what he decided, and what is open

**Decided on 27 November 2026:**

1. *Start beside milestone 8?* **Decided: no.** "let's finish the milestone 8 first of all".
   Nothing of this row starts, and the repository is not created, before milestone 8 closes
   and the owner says go; the lead confirms with him before P0 item 1. (The plan had
   recommended starting L1 to L19 beside milestone 8.)
2. *Create `vecbricks/vechrono`, with the coordinates `io.github.vecbricks:vechrono`?*
   **Decided: yes** (P0 items 2 and 4), once the start is given.
3. *The founding commit straight to `main`?* **Decided: yes** (P0 item 5).
4. *The licence rule.* **Decided: a builder does not open OpenJDK's `java.time` sources.**
   No line of them is copied or translated, and each fact of behaviour the design rests on
   is stated in the plans and held through the public API (C3, C4, C7 and the tests). Row
   678's plan has the same rule.
5. *A standing approval of the library's benchmark and nightly dispatches?* **Decided: yes**
   (P0 items 7 and 9).
6. *Build the five parts of 5.6 in row 679, or move them to a later row?* **Decided: a later
   row,** keeping the every-zone sweep. The row closes at L19 and V1. (P0 item 11.)
6a. *Lanes are microseconds only in 0.1.x?* **Decided: no.** "we should focus on the
   nanosecond precision from the begining I believe. Nanosecond should be prime units."
   Asked which shape a value takes, he chose both: the two words as the contract over the
   whole range, and one long of nanoseconds as a measured arm (4.1, 4.9). (The plan had
   recommended microseconds only.)

**Still open,** each with the recommendation first; the plan is written on the
recommendation:

*27 November 2026, later:* the owner read the plan as revised for nanoseconds and approved
it as written, so the recommendation of each question below stands as his answer.

7. **Does row 679 close at its done-when, with the later forms riding with their Varka
   rows?** Recommended: yes, as vfdlibm's ports rode with rows 356 to 358.
8. **One release, 0.1.0, after L18, or an early 0.1.0 after L7?** Recommended: one. A release
   is owner-run, and no Varka row waits on it.
10. **The step counts 0, 2 and 8 only,** as Varka's forms. Recommended: yes; a count of 4 is
    an arm to add if L17's files show a gain on `casablanca` data.
11. **The licence text and the `NOTICE`** (P0 item 3): Apache-2.0, a `NOTICE` of three lines,
    no third-party notice. Recommended: as written.
12. **The branch rule on `main` after L1** (P0 item 2a), **Dependabot** (item 8) and **a
    standing approval for pushes and pull requests in the library** (item 10). Recommended:
    yes to each.
13. **One long at precisions 7 and 8 too?** Row 662's layout (b) is "one long at the
    column's precision". Recommended: nanoseconds only in 0.1.0. The owner named the
    nanosecond as the prime unit; the steps of 4.9 need only another factor (100 or 10 in
    place of 1000) to serve the other two, which is a small pull request once row 662's
    measurement says the one long is worth keeping at all.
14. **The second word as a `LongVector`** (4.9), where Spark holds a short. Recommended:
    yes, for a reference: one species, no conversion, a carry of one compare. The lane type
    in a kernel stays row 662's to measure.
15. **`plusNanos` in 0.1.0, as the one form that reads and writes both words.**
    Recommended: yes; without it the release carries the second word and never computes
    with it. The other forms over the second word wait for their rows (5.3).
16. **The one-long table as three longs a bucket** (`NanoTable`), not the packed bucket of
    row 686. Recommended: yes; an offset in nanoseconds does not fit half a long, and the
    measurement of prediction 12 says what that costs.

### 10.5 Where this plan and `VARKA-678.md` differed, and how each was settled

The first draft of this plan had its own design of the zone data. Row 678's planner listed
thirteen differences, and the lead ruled on each on 27 November 2026: row 678's plan is the
reference, because it follows the merged `VARKA-682.md` and what Spark's row path reads,
`ZoneRules.getOffset`. This plan applied every ruling; none was found wrong on the facts.

| # | the first draft here | `VARKA-678.md` | settled |
| ---: | :--- | :--- | :--- |
| 1 | the rule era by a walk of `ZoneRules.nextTransition` | `createTransition(year)` for `Y0` and 400 years, checked against `getOffset` | row 678's (4.2.1). The oracle is `getOffset`, which reads the year its own way; a walk of `nextTransition` can differ from it near a year's start |
| 2 | three checks, the cycle sampled at three changes | C1 to C7 | row 678's, restated in full (4.2.4) |
| 3 | three arrays of microseconds a change | microseconds for the instants, seconds in an `int` for the offsets | row 678's (4.2.1) |
| 4 | an exception for `least > greatest` | the base and padding; an exception only past `steps` | row 678's (4.2.3) |
| 5 | `ZoneNotServedException` | `served()` false, a null from the provider | each keeps its own (2.4): the library's entry behaves as Varka's, and its exception is part X5 of 5.6 |
| 6 | a local range near an end of a long: wrapping sums | `MANY` within 36 hours of an end, and an exception from the fill | row 678's (4.2.5). The draft was wrong: a threshold that wraps fires for every lane |
| 7 | rules with no stored change read as changes every year | ignored, as `getOffset` ignores them | row 678's (4.2.1, and the made-up `rulesOnly()` of L3) |
| 8 | fixed zones cached | built and not kept | row 678's (4.2.9) |
| 9 | `Resolve` | `Rule` | names differ (2.4) |
| 10 | the table inside `ZoneData` | `changesAt`, `changeOffsets`, `memo`; row 686 packs | each keeps its own home for the packing; the entry's three methods are the same (2.4, 4.5) |
| 11 | 10.1 said `localChanges` may differ | one exact count | fixed (10.1) |
| 12 | one class ported as `VarkaChronoZone` | three classes | the port maps onto `VarkaChronoZoneChanges` and `VarkaChronoZones`; `VarkaChronoZone` stays Varka's (2.4, 10.1) |
| 13 | row 685's last part reads the grain methods | no merged plan reads them | corrected: `VARKA-685.md` 4.10 reduces the filled offsets and calls neither method (4.2.7) |

After the ruling row 678's plan became final, and three more points were settled by it: row
678 is built first and the library ports its merged files (10.1); no builder opens the
JDK's sources (decision 4 of 10.4); and the clamp's exception, whose text in Varka is the
count's, has a text of its own in the library (4.2.10). The planted faults stay
system-property constants in the library's main source, which its nightly's fault dispatch
needs and Varka's main sources do not take.

### 10.6 Edits to other plans, made by this plan's own pull request

Six edits of `sql/varka/plans/m9/PLAN.md` and one of `sql/varka/plans/m9/VARKA-678.md`.
Each old text was read at `a0e3a62cf2b`; the merges up to `73fad30ead4` (#1782 and #1783)
changed `m9/PLAN.md` in the lines of rows 654 and 678 and in section 2.4 only, so each old
text below is still there. Each is given with where it sits; a line break may fall
elsewhere in the file.

1. **`m9/PLAN.md`, section 2.10, the bullet "What it holds".** Old: "the calendar and clock
   fields, the sums with days and months, the difference of two local date-times,
   truncation, and the two-word nanosecond forms. It holds no parsing, no formatting and no
   `java.time` objects." New: "the calendar and clock fields, the sums with days and months,
   the difference of two local date-times, and truncation. Every form has nanosecond
   precision from the library's first release, in two shapes (the owner, 27 November 2026):
   the two words of 2.5, a long of microseconds and the nanoseconds within it, which is the
   contract over the whole range; and one long of nanoseconds, a measured arm over the years
   1677 to 2262. It holds no parsing, no formatting and no `java.time` objects."
2. **`m9/PLAN.md`, section 2.5, the bullet that starts "Two layouts (row 662), both
   built."** One sentence is added at the bullet's end, after "The measurement is on the
   baselines of row 655.": "The library (row 679) holds both layouts first, outside Spark,
   and its benchmark of the two shapes on the same values (`VARKA-679.md` 5.2, L14 and L17)
   is their first measurement."
3. **`m9/PLAN.md`, section 2.10, the bullet "Its contract".** Old: "is `java.time`'s
   answer, bit for bit, in every zone of the running JVM." New: "is `java.time`'s answer to
   the nanosecond, bit for bit, in every zone of the running JVM."
4. **`m9/PLAN.md`, section 3, the line of row 679,** two places. Old: "**Filed** (25
   November 2026, the owner's decisions 13 and 16;" New: "**Planned** (`VARKA-679.md`, 27
   November 2026: a founding commit, eighteen parts of the library and one of Varka;
   nanoseconds are its unit from the first release; it starts after milestone 8 closes, on
   the owner's word; filed 25 November 2026, the owner's decisions 13 and 16;". And old:
   "then the fields, the sums and the nanosecond forms as their rows come." New: "then
   `plusNanos`, the first form over both words of a nanosecond value; then the fields and
   the sums as their rows come, each in both shapes."
5. **`m9/PLAN.md`, section 4, the paragraph "Wave 0, now, while milestone 8 runs."** Old:
   "the library's start (row 679) and row 678, which are new classes and their tests;" New:
   "row 678, which is new classes and their tests (the library, row 679, starts after
   milestone 8 closes, on the owner's word of 27 November 2026, and ports row 678's merged
   files);".
6. **`m9/PLAN.md`, section 2.2, the paragraph "The zone database (row 678)".** Old: "Its
   first implementation is the library's (2.10); row 678 is Varka's own, which the
   evaluator reads." New: "Row 678 is Varka's own, which the evaluator reads, and is built
   first; the library (2.10) ports its merged files."
7. **`VARKA-678.md`, section 1, the paragraph that starts "The row adds three classes".** It
   quotes the sentence that edit 5 replaces. Old: "That is why the milestone plan's wave 0
   allows it while milestone 8 runs (`m9/PLAN.md` section 4: "the library's start (row 679)
   and row 678, which are new classes and their tests")." New: "That is why the milestone
   plan's wave 0 allows it while milestone 8 runs (`m9/PLAN.md` section 4: "row 678, which
   is new classes and their tests")."

No other text of `VARKA-678.md` changes: the library's nanosecond arms are in classes of
their own (4.9), and the port's `diff` rule holds.

## 11. Outcome

*To be written when the row closes.*
