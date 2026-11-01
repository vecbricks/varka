# Reading for row 508: regression test selection across code and configurations

Read on 28 October 2026 at the owner's request, for row 508 (a pull request's option matrix that
reruns only what the change moves). The uRTS paper was read in full from the accepted manuscript in
the NSF Public Access Repository; Ekstazi is known here from its abstract and from uRTS's account of
it (section II-B and section V), not from its own paper. Neither copy prints a licence that allows
one to be kept, so `sql/varka/papers/` holds no transcription (its `README.md`, Licensing). **[R]**
means read; **[I]** inferred.

| paper | source | licence of the copy |
| :--- | :--- | :--- |
| Wang, Lian, Marinov, Xu: "Test Selection for Unified Regression Testing" (ICSE 2023) | NSF PAR, `https://par.nsf.gov/servlets/purl/10421176`; artifact `https://github.com/xlab-uiuc/uRTS-ae` | IEEE; the deposit prints no licence |
| Gligoric, Eloussi, Marinov: "Practical Regression Test Selection with Dynamic File Dependencies" (ISSTA 2015, DOI 10.1145/2771783.2771784), the Ekstazi paper | abstract at `https://users.ece.utexas.edu/~gligoric/papers/abstracts/GligoricETAL15Ekstazi.txt` | ACM; not read |

## 1. uRTS: the problem the option matrix has, solved for Hadoop's configurations

**What it is [R].** Regression testing where every test runs under a default configuration and
several production configurations, and a change can touch code, configurations or both. The
matrix's shape exactly: the defaults run is the paper's default configuration, each emit-option
configuration one of its production configurations. uRTS keeps, for each pair of a test and a
configuration, a dependency file with two parts: the code the test ran, as `<URI, checksum>` pairs
of the `.class` files it loaded (Ekstazi's form), and the configuration it read, as `<parameter,
value>` pairs recorded by instrumenting the project's one low-level configuration getter (Ctest's
technique; Hadoop's `Configuration.get`). Every parameter read is assumed to matter: the
over-approximation is what makes it safe. A test is skipped under a configuration when either
comparison finds an equivalent run:

- *horizontal*, within one revision: every parameter the test read has the same value in this
  configuration as in one already run (the default, or an earlier production one). Code is not
  compared, since it is the same revision.
- *vertical*, against the last round: the parameters it read have the values they had then under
  the same configuration, and every class file it loaded has the checksum it had.

A skipped test's dependency file is copied from the run it was found equal to, so the next round
compares against it. Selection is per test class, not per method, and the paper keeps Ekstazi's
reason: class-level analysis is cheaper and safer, and wins end to end although it runs more tests.

**What it measured [R].** Five projects (Hadoop common, HDFS, HBase, Alluxio, ZooKeeper), 50
commits each, two production configurations: uRTS took 27.5% of the time of running everything
(3.64 times faster) and 53.4% of the time of Ekstazi extended to track whole configuration files,
selecting 11.2% of the test classes. Time saved is less than tests skipped because the build is
paid either way and the tests selected are the long ones. One HDFS parameter was read by 584 test
classes, so a change to it reran all of them: a widely read option erases the gain.

**What Varka takes from it [I].**

1. *A cheaper recorder for row 507 step 5.* `VarkaTestReach` re-emits each compile under every
   covered configuration, which is what costs the Build's sql jobs 14%. The horizontal comparison
   suggests a first, cheap filter: record which `VarkaEmitOptions` accessors a test's compiles and
   emissions call, and re-emit only under the configurations that set one of them. A configuration
   that changes none of the options a test read cannot move it. Step 5's byte comparison then stays
   as the precise second filter, where uRTS stops at the over-approximation. The trap: a test that
   takes a kernel from the shape cache or the compile memo reads no options itself, so the options
   read when the entry was built must travel with the entry, as `producedBy` already carries
   moved configurations from a compile to its kernels.
2. *Row 508's key, recorded rather than listed.* The row's open question is which runtime classes
   key the cache. uRTS's answer, from Ekstazi, is to record per test the class files it loaded,
   with checksums, and compare them; no list is written by hand. A JVM loads a class once, so a
   class first loaded by an earlier test would be missing from a later test's set; Ekstazi tracks
   uses beyond loading (static field dereferences, per uRTS section V-C) for this reason, and a
   Varka recorder has to solve the same problem, for instance by taking the classes a whole suite
   loaded as each of its tests' set.
3. *Dependency files per (test, configuration), copied on a skip.* Row 508's record of master's
   last green run is the paper's dependency directory; a pair skipped under a pull request carries
   its master record forward unchanged.
4. *Class-level granularity.* Both papers select test classes; row 508 should start from suites
   (the matrix's unit) and go finer only where a suite's skips leave time on the table.

**What does not transfer [I].** uRTS's configurations are values read through one API, so
"equal values read" means "same behaviour". An emit option acts through the emitter, and two
configurations can read the same options and emit different bytes; that is why step 5 compares
emissions and why the option-read filter can only remove work, never replace the byte check.

## 2. Ekstazi: file dependencies, recorded dynamically

**What it is [R, from the abstract and uRTS].** Regression test selection for Java that records,
while each test runs, the files it depends on (`.class` files, optionally others) with their
checksums, and reruns a test only when one of them changed. No version-control integration is
needed. Over 615 revisions of 32 open-source projects it cut testing time by 32% on average and 54%
on the longer suites, with lower end-to-end time than the techniques it was compared with though it
selected more tests. The default mode tracks only class files and so misses a change to a
configuration file (uRTS calls it Ekstazi-minus, unsafe for configurations).

**What Varka takes from it [I].** The checksum-of-what-was-loaded key for row 508 step 1, and the
measurement discipline of both papers: report end-to-end time, the analysis and the collection
included, not the count of tests skipped.
