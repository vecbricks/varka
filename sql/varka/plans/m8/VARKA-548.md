# VARKA-548: Two lint rules: no JVM-wide test fault, no second list of CPU architectures

Row 548 of `PLAN.md`. Added on 2 November 2026 by the owner's word, from the reviews of row 359
and of the two-lane decline in #1262; planned on 8 November 2026.

Everything below was read from the tree; the lint was run over master's catalyst Varka sources
(Python, no JVM). What was not read is marked **[I]**, an assumption **[A]**.

## 0. What exists now (admission check)

Against master at `9eefe2a2d13`.

1. **The lint and where it runs.** `dev/varka_lint.py` holds eleven rules (`RULES`, `:646`), each
   a generator over a `Change`. The pre-commit hook runs it scoped to the lines a commit writes
   (`dev/varka_precommit.sh:562-565`); the Build runs only `--selftest`
   (`.github/workflows/build_and_test.yml:1865`); no job runs it over a range or the whole tree.
   Run unscoped over every catalyst Varka source on master (`dev/varka_lint.py FILE...`), it
   reports one finding, an `import-root` at `VarkaMaskOfWordSuite.java:29` that the rule's own
   docstring names as its origin. So "the lint passes on master" can only mean: the two new rules
   report nothing in that unscoped form.
2. **`global-fault` hits one line on master.** The only type under `sql/catalyst/src/main` whose
   name holds `Fault` is `VarkaFloatingFault` (`git grep` over class, enum, record, interface and
   object declarations), and its one mutable static is `private static volatile
   VarkaFloatingFault planted` (`VarkaFloatingFault.java:46`), set by `plantForTesting` (`:54`).
   Other non-final statics in Varka's main code are infrastructure and JVM-wide on purpose
   (`VarkaKernelCompileDirective.java:93`, `VarkaKernelWarmup.java:245` and `:395`,
   `VarkaShapeCache.java:77-78`); the static test hooks `setFailForTesting` and
   `setFailInFamilyForTesting` (`VarkaExpressionCompiler.java:264-284`,
   `VarkaNodeCompiler.java:205`) make a compile fail, which the ghost fallback answers correctly. Only a fault produces a wrong
   class the cache could serve under a clean key, which is the hazard the row names.
3. **Two of the three floating faults are read at compile time, not at emission.**
   `NEGATION_AS_SUBTRACTION` and `DEGREES_ROUNDED` change the IR `VarkaFloatingCompiler` builds
   (`negate`, `:171-179`; `degreesPerRadian`, `:185-188`), and the family's `arm` (`:75`) takes
   no options. Only `DIVISOR_POSITIVE_ZERO_ONLY` is read at emission (`VarkaVectorWalk.java:1039`).
   The compile already carries one option to the family arms, `rangeSets`, on `DeclineSink`
   (`DeclineSink.java:68`, `:88`), built from the options at every site
   (`VarkaExpressionCompiler.java:456`, `:593`, `:729`, `:797`, `:913`).
4. **The planting test.** `VarkaEmitterFloatingArithSuite.failsUnder` (`:1312-1319`) plants a
   fault JVM-wide, runs a check and clears it; its three tests (`:1324-1360`) emit their kernels
   directly, not through the class cache, which is the only thing keeping a faulty class out of
   it. A concurrent test in the same JVM would compile under the planted fault.
5. **Row 359a's model is not on master.** `VarkaEmitOptions.DecimalFault`, an option read at emit
   time from the emission's own options, exists only on the unmerged branch `varka-359a-pair`
   (`git grep DecimalFault` on master finds only `PLAN.md`). Master's fault injectors in the
   options table are flags and counts (`Reason.FAULT_INJECTOR`, `VarkaEmitOption.java:85`;
   `dropDecimalBound`, `:561`), which the fuzzer and the matrix never draw (`drawn()`, `:104`).
   The options are a component of `VarkaShapeKey` (`VarkaShapeKey.java:59-64`), so a fault held
   there gets a key of its own.
6. **`arch-list` hits six lines in five files on master**, every one in a test:

   - `VarkaTwoLaneConditionSuite.java:69` and `:84`: `for (String arch : List.of("amd64",
     "x86_64"))`, a loop that builds a reading for each name; fix: loop over `VarkaHostIsa`'s list.
   - `VarkaAvx2SelectSuite.java:137` (`arch.equals("amd64") || arch.equals("x86_64")`),
     `VarkaAssemblySuite.scala:133` (`Set("amd64", "x86_64")`) and `VarkaShapeCacheSuite.scala:317`
     (`arch == "amd64" || arch == "x86_64"`); fix: `VarkaHostIsa.isX86(arch)`.
   - `VarkaHostIsaSuite.java:53` (`isX86("amd64") && isX86("x86_64")`): the test of the list
     itself, left as it is (question 1).

   `VarkaHostIsa.java:60` holds the list (`private static final List<String> X86`). Main code has
   none. Plans quote the literals too (`PLAN.md:1252`, `VARKA-551.md:41`), so the rule reads
   source files only. The shell scripts that map `uname -m` to a JDK directory name
   (`dev/varka_emit.sh:124`, `dev/varka_gate.sh:103`, `dev/varka_hsdis_build.sh:58-59`) use no
   quoted pair and are another fact: what a directory is called, not what C2 lowers.
7. **No branch of this row exists** (`git branch --list 'varka-548*'`: none).

### What differs from the row text

- **The fault move is not 359a's move.** 359a's faults are all read at emission; two of the
  floating ones are read by the compiler (item 3), so the option reaches them through
  `DeclineSink`, as `rangeSets` does, and not through `Analysis.options`. And 359a's form is not
  on master to copy (item 5), so whichever of 548 and 359a lands second adds its component beside
  the other's.
- **"The lint passes on master"** is the unscoped run of the two new rules (item 1); the hook
  scopes findings to written lines, so it would pass on master today without any move.
- **`arch-list` has six hits, not the four `VARKA-551.md` 0 item 4 counts.** That item says four
  and lists five (`:69`, `:84`, `:137`, `:133`, `:317`); `VarkaHostIsaSuite.java:53` is the sixth.
- **"Outside `VarkaHostIsa`"** is read as outside `VarkaHostIsa.java` and its suite (question 1).

## 1. Goal and non-goal

**Goal.** Two rules in `dev/varka_lint.py`, each with a self-test case it reports and one it must
not; master clean under both in the unscoped form; the floating faults held in the emission's
options, so a planted fault compiles under a shape key of its own and no other compile sees it.

**Non-goal.** A rule for every mutable static in Varka (item 2's infrastructure stays); the shell
scripts' `uname -m` mappings; any change to what `VarkaHostIsa` reads (row 551's).

## 2. Design

### 2.1 `arch-list`

A line of a `.java` or `.scala` file, with its comment removed (`without_comment`), holding two or
more of the quoted literals `"amd64"`, `"x86_64"`, `"aarch64"`, in a file whose basename does not
start with `VarkaHostIsa`; scoped to written lines like the other line rules. The message names
`VarkaHostIsa.isX86` and its list. The per-line form is easy to defeat by splitting a list over
two lines; the rule catches the mistake it was found from, and the fix it points at, one shared
accessor, is what removes the drift. `VarkaHostIsa.X86` becomes package-private (the suites are in
its package), so the two loops at item 6 read it; the other three call `isX86`.

### 2.2 `global-fault`

In a `.java` file under `sql/catalyst/src/main/`, a field declaration holding `static` and not
`final`, inside a type whose name holds `Fault` (case-sensitive, so `Default` does not match):
the type found by its declaration, with brace depth tracked over the comment-free text, so a
nested type counts by its own name. Java only: Varka's main code under catalyst is Java, and a
Scala `object` holding a `var` is the one form it would miss **[A]**.

### 2.3 The floating fault as an option

- `VarkaEmitOptions` gains a component `floatingFault`, an enum (question 2 for where it lives),
  `NONE` in `DEFAULTS`; the table gains a `Choice` of `Reason.FAULT_INJECTOR`, so the fuzzer and
  matrix never draw it, with a tag that renders nothing at `NONE`, so no committed class name or
  hash moves.
- `DeclineSink` carries it beside `rangeSets`, from the options at the five sites; `negate` and
  `degreesPerRadian` read it from the sink; `VarkaVectorWalk.java:1039` reads
  `analysis.options.floatingFault()`.
- `VarkaFloatingFault` keeps its constants and loses `planted` and `plantForTesting`.
- `failsUnder` takes the options with the fault set and passes them to each check, which must
  compile and emit under them **[I]** how many of the check helpers take options today.

## 3. Tests

1. **`--selftest`**, four cases: `arch-list` reports `List.of("amd64", "x86_64")` in a test file
   and passes `new Reading("amd64", ...)`; `global-fault` reports `private static volatile E
   planted` in `enum XFault` under `src/main` and passes `private static final` in the same enum.
   A fifth: a static non-final field in a class `Defaults` is not reported.
2. **Unscoped over master's catalyst Varka sources**: neither rule reports anything after the
   moves.
3. **The fault has a key of its own**: compiling `-x` over a `DOUBLE` column with
   `floatingFault=NEGATION_AS_SUBTRACTION` through the shape cache, then without, yields two
   entries and the clean compile's class answers `-0.0` for `0.0`.
4. **The three planting tests** pass under the options form, and still fail their checks under
   each fault.
5. **The registry suites that enumerate the options table** run with the new option:
   `VarkaEmitOptionSuite`, `VarkaMatrixSuite`, `VarkaIrFuzzSuite`, `VarkaEmittedBytesSuite`,
   `VarkaReproductionSuite` (the readers of `VarkaEmitOption.TABLE`), and
   `VarkaEmitterFloatingArithSuite`.

## 4. Predictions, registered before any build

- **P1.** `emitted_bytes.json`, `coverage.json` and `width_audit.json` do not change: the default
  options emit what they emit today, and `FAULT_INJECTOR` options are not pinned.
- **P2.** The lint's self-test gains five cases and stays under a second **[A]**.
- **P3.** Size: PR 1 under 150 counted lines, PR 2 under 250.

## 5. Order, files and size

1. **PR 1, `arch-list`** (small): the rule, its two self-test cases, the docstring line;
   `VarkaHostIsa.java:60` (visibility), the five test lines of item 6. No dependency; best before
   row 551's first pull request, which rewrites `VarkaTwoLaneConditionSuite.hostTest` (551 moves
   the two-lane boolean into its record), so that 551 builds on the shared list.
2. **PR 2, `global-fault` and the move** (small): the rule and its three cases;
   `VarkaEmitOptions`, `VarkaEmitOption`, `VarkaFloatingFault`, `DeclineSink`,
   `VarkaExpressionCompiler`, `VarkaFloatingCompiler`, `VarkaVectorWalk`, the floating suite, the
   key test. Its order against 359a's `DecimalFault` is question 3.

Each pull request carries its own rule and the moves that keep master clean under it.

## 6. What it unlocks

Every later fault is an option by rule, not by a reviewer's memory; every later host test reads
`VarkaHostIsa`, which row 551 extends.

## 7. Out of this row

- **Row 551**: no conflict. 551 moves none of item 6's lines (`VARKA-551.md` 7) and reads no
  literal; both touch `VarkaHostIsa.java` in different places (line 60 here, `Reading` there),
  and `VarkaTwoLaneConditionSuite` only if 551 rewrites `hostTest`, which PR 1's order avoids.
- **The `uname -m` mappings** in the shell scripts (item 6): not a C2 fact.
- **The infrastructure statics and failure hooks** of item 2: not faults.

## 8. Questions for the owner

1. **Does `VarkaHostIsaSuite` count as `VarkaHostIsa`?** (a) Yes: the rule skips files whose name
   starts with `VarkaHostIsa`. (b) No: its line loops over the list it is testing. **Recommended
   (a)**: the suite pins the names HotSpot's `os.arch` uses, which a loop over the list would test
   against itself.
2. **Where does the floating fault enum live?** (a) Nested in `VarkaEmitOptions` as
   `FloatingFault`, as 359a nests `DecimalFault`. (b) The top-level `VarkaFloatingFault` kept, its
   static removed. **Recommended (a)**: the row asks for 359a's form, and one place for every
   fault option; the type name keeps `Fault`, so the rule still covers it.
3. **PR 2 before or after 359a's part that adds `DecimalFault`?** Both add a component to the
   `VarkaEmitOptions` record, its builder and a `Choice` to the table, side by side. (a) After,
   if that part is close to merging. (b) First, with 359a merging master. **Recommended (a)** while
   359a is the milestone's critical path; the rebase is a few adjacent lines either way.

## 9. Done when

Both rules report their planted cases in `--selftest` and not their clean ones;
`VarkaFloatingFault` holds no mutable static and a compile under a planted fault gets a shape key
of its own (test 3.3); and the two rules report nothing on master's catalyst Varka sources run
unscoped.

## 10. Outcome

Not started.
