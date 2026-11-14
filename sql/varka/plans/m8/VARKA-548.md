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
   `VarkaNodeCompiler.java:205`) make a compile fail, which the ghost fallback answers
   correctly. Only a fault produces a wrong class the cache could serve under a clean key, which
   is the hazard the row names.
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

PR 1 (`arch-list`) is built on `varka-548-lint`: the rule, its self-test cases and the five test
lines of 0 item 6. PR 2 (`global-fault` and the floating fault's move) is separate, not started,
and still waits on questions 2 and 3. `VarkaHostIsa.java` is untouched (B1's out of scope): the
two loops keep their literals marked ok, and the other three call `isX86`.

Results: `--selftest` `ok (60 cases)` after the review's added cases; ruff `1 file already
formatted`, `All checks passed!`; unscoped `arch-list` over all 576 Varka sources `0`, the
broader form of B4 step 3 that does not select on the file name, and over the canary probes `0`
(the only unscoped finding is the known `import-root` at
`VarkaMaskOfWordSuite.java:29`); `catalyst/Test/compile` `[success]` in 110 s;
`dev/varka_matrix.sh` over the five suites exit 0, 50 succeeded, 0 failed, 14 canceled (all
`VarkaAssemblySuite`'s hsdis lookups); the three generated files diff clean against
`origin/master`.

Planted faults, reverted by hand: `>= 2` as `>= 3` printed `varka_lint selftest: arch-list True:
m/src/test/java/VarkaZSuite.java: expected True, got False` and exited 1; the old
`arch == "amd64" || arch == "x86_64"` in `VarkaShapeCacheSuite` printed one `arch-list` line at
`:317`, and the unscoped run was `0` after the revert.

Predictions: P1 holds. P2: five cases for PR 1, though not the plan's five: `Defaults` is
`global-fault`'s and so PR 2's, and `arch-list: ok` came in its place. P3: the push check counts
92 hand-written lines against the plan's 150 for PR 1. Went differently from the sheet: both
pull requests it expected open (#1461, #1478) had merged, so the branch was cut from
`fb661840a3e`; the seven grep lines are the same, `VarkaTwoLaneConditionSuite`'s two at `:74` and
`:89`, and the self-test still held 49 cases before the new ones. The branch is `varka-548-lint`
in `/home/max/proj/varka-548-lint`, not the sheet's `varka-548`, so BUILD_SHEETS.md section 1
item 2's exact-name check prints nothing; PR 2 cuts its branch from master after this one merges,
where the rule already is.

**The review.** One `/code-review high` pass found fourteen points. Fixed: the rule counted the
os.arch names inside block comments and text blocks, where `without_comment` leaves a Javadoc's
`* ...` lines as they are, so a comment or a message naming two architectures was reported as a
second list; the rule now skips comment lines and a text block's delimiters and content, which
the new self-test cases pin. The rule now also lints the `dev/varka_canary/` probes, as
`nan-compare` does. The finding's advice is "for an x86 check call `VarkaHostIsa.isX86(arch)`,
and mark a line that pins the names", since `isX86` answers false on aarch64. Six self-test
cases were added - a Varka `.scala` path, the `aarch64` alternative, one name twice, a Javadoc
line, a text block, and a canary probe - and each case's failure message now quotes its fixture.
Left, with the reason: the `VarkaHostIsa` prefix exemption is the plan's question 1 decision; the
rule's own copy of the three names is the sheet's design (distinct literals count, the docstring
is the only documentation); two identical pinned lines in `VarkaTwoLaneConditionSuite` are B1's;
matching the marker on the raw line lets a string literal contain it, which the marker's own
comment form keeps harmless; the file-scan preamble copies `guava`'s shape, as the other rules
do; and the per-line `without_comment` call costs about 15 ms a commit, measured by the review.

## Build sheet (11 November 2026)

For a coding agent building **the first pull request only** (`arch-list`, section 5 item 1),
stopping at a local commit. Follow `sql/varka/plans/m8/BUILD_SHEETS.md` for what every sheet
shares: the worktree, the heavy lock, the environment, the regeneration, the commit format, the
linters and the rule that the agent never pushes. Where this sheet and that file disagree, stop
and report. Everything below was read at `c62f492fc89` (origin/master on 11 November 2026).

Branch and worktree: `varka-548` in `../varka-548`, made as BUILD_SHEETS.md section 1 item 2
says. Commit subject: `[VARKA-548] Lint a second list of CPU architectures`.

**Stale facts in sections 0 to 9 of this plan, corrected here.** The plan was read at
`9eefe2a2d13`; on `c62f492fc89` `VarkaAssemblySuite.scala`'s list is at line 131, not 133; the Build runs
`dev/varka_lint.py --selftest` at `build_and_test.yml` line 1886, not 1865. The six test hits
of 0 item 6 are unchanged. Use the symbols and grep strings below, never the plan's line
numbers.

### B0. Premise re-check

Run each command from the worktree root. Any output that differs from the expected one: stop
and report (B7), do not improvise.

1. Open pull requests that touch this sheet's files:
   `gh pr view 1461 --repo vecbricks/varka --json state --jq .state` and the same for `1478`.
   Expected on 11 November: `OPEN` for both. #1461 adds a constant `AARCH64` right below the
   `X86` line of `VarkaHostIsa.java`; #1478 adds a test to `VarkaTwoLaneConditionSuite.java`
   away from `hostTest`. If either prints `MERGED`, run `git fetch -q origin master` and cut the
   branch from the new master, then repeat checks 2 and 3: they must still pass. If either is
   still `OPEN` when you commit, rebase if #1461 or #1478 merged first, which here means: merge
   master into the branch and continue as BUILD_SHEETS.md section 8 says.
2. `grep -c 'private static final List<String> X86 = List.of("amd64", "x86_64");' sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaHostIsa.java`
   prints `1`; `grep -c 'public static boolean isX86(String arch)'` on the same file prints `1`.
3. `git grep -n -E '"(amd64|x86_64|aarch64)".*"(amd64|x86_64|aarch64)"' -- '*Varka*.java' '*Varka*.scala'`
   prints exactly these seven lines (path prefix
   `sql/catalyst/src/{main,test}/.../codegen/varka/`):
   - `VarkaHostIsa.java:60` (`private static final List<String> X86 = ...`), exempt
   - `VarkaHostIsaSuite.java:53` (`assertTrue(VarkaHostIsa.isX86("amd64") && ...`), exempt
   - `VarkaAvx2SelectSuite.java:137` (`if (arch.equals("amd64") || arch.equals("x86_64")) {`)
   - `VarkaTwoLaneConditionSuite.java:69` and `:84` (`for (String arch : List.of("amd64", "x86_64")) {`)
   - `VarkaAssemblySuite.scala:131` (`private val supportedArches = Set("amd64", "x86_64")`)
   - `VarkaShapeCacheSuite.scala:317` (`val x86 = arch == "amd64" || arch == "x86_64"`)
4. `grep -n 'supportedArches' sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaAssemblySuite.scala`
   prints two lines: the `private val` at 131 and `assume(supportedArches.contains(arch),` at 503
   inside `private def requireSupportedArch(): Unit`.
5. `python3 dev/varka_lint.py --selftest` prints `varka_lint selftest: ok (49 cases)`.
6. `grep -n -E '^RULES = \{|"nan-compare": rule_nan_compare,|^def without_comment|^def is_varka' dev/varka_lint.py`
   prints four lines (the `RULES` dict, its last entry, and the two helpers the rule reuses).
7. `git branch --list 'varka-548*'` prints nothing, and this sheet is in the new worktree's
   `VARKA-548.md` (it reached master with its plan pull request; cut the worktree from an
   origin/master that holds it).

### B1. Scope

**`dev/varka_lint.py`** (the only Python file):

- In the module docstring, after the three-line `nan-compare` entry (it ends `(VARKA-556)`), add:

  ```
    arch-list        in Varka code, a line naming two or more of the os.arch literals "amd64",
                     "x86_64" and "aarch64" outside VarkaHostIsa and its suite: a second list of
                     architectures drifts from the compiler's host fact; call VarkaHostIsa.isX86,
                     or mark a line that pins the names on purpose `arch-list: ok` (VARKA-548)
  ```

- Directly above `RULES = {`, add the rule. It is the `guava` rule's file filter plus a line test
  on the comment-free line (`without_comment`), counting **distinct** literals:

  ```python
  ARCH_LITERAL = re.compile(r'"(amd64|x86_64|aarch64)"')


  def rule_arch_list(ch):
      for path in ch.files:
          if not re.search(r"\.(java|scala)$", path) or not is_varka(path):
              continue
          # VarkaHostIsa holds the list, and its suite pins the names HotSpot's os.arch uses
          # (m8/VARKA-548.md 8, question 1).
          if os.path.basename(path).startswith("VarkaHostIsa"):
              continue
          for n, line in enumerate(read(path).splitlines(), 1):
              # A line that pins the os.arch names on purpose says so, as nan-compare's do.
              if "arch-list: ok" in line:
                  continue
              if len(set(ARCH_LITERAL.findall(without_comment(line)))) >= 2 and ch.wrote(path, n):
                  yield (
                      f"{path}:{n}: arch-list: a second list of os.arch names drifts from the "
                      "compiler's host fact; call VarkaHostIsa.isX86(arch), or mark a line that "
                      "pins the names `arch-list: ok` (m8/VARKA-548.md)"
                  )
  ```

- In `RULES`, after `"nan-compare": rule_nan_compare,`, add `"arch-list": rule_arch_list,`.
- In `selftest()`, directly after the `nan-compare` loop's `case(...)` line and before
  `if fails == 0:`, add this loop at eight spaces of indentation (inside the `with` block, level
  with the `nan-compare` loop's `for`) (the five cases are B2's tests 1 to 5):

  ```python
          for path, text, flagged in (
              ("m/src/test/java/VarkaZSuite.java", 'for (String a : List.of("amd64", "x86_64")) {\n', True),
              ("m/src/test/java/VarkaZSuite.java", 'var r = new Reading("amd64", "", null, 256);\n', False),
              ("m/src/test/java/VarkaZSuite.java", 'List.of("amd64", "x86_64") // arch-list: ok\n', False),
              ("m/src/test/java/VarkaHostIsaSuite.java", 'ok = isX86("amd64") && isX86("x86_64");\n', False),
              ("m/src/main/scala/Native.scala", 'case "amd64" | "x86_64" => "x86_64"\n', False),
          ):
              case(f"arch-list {flagged}: {path}", {path: lic + text}, "arch-list", flagged)
  ```

  Then run `ruff format dev/varka_lint.py`, which splits the three long tuples over lines; keep
  its output as it is.

**Java and Scala** (all under `sql/catalyst/src/{main,test}/.../expressions/codegen/varka/`):

- `VarkaTwoLaneConditionSuite.java`, method `hostTest`: both
  `for (String arch : List.of("amd64", "x86_64")) {` keep their literals (the test pins the two
  names `os.arch` gives x86; a loop over `VarkaHostIsa`'s own list would test it against itself,
  question 1's reason) and gain the trailing comment `// arch-list: ok, the os.arch names pinned`.
- `VarkaAvx2SelectSuite.java`, method `runsUnderAvx2`:
  `if (arch.equals("amd64") || arch.equals("x86_64")) {` becomes
  `if (VarkaHostIsa.isX86(arch)) {`. The `arch` local and its `toLowerCase(Locale.ROOT)` stay.
- `VarkaAssemblySuite.scala`: delete the `private val supportedArches = ...` line, the scaladoc
  line above it (`/** x86-64 is the only table filled in; see ... */`) and the blank line after
  it; put that same scaladoc line directly above `private def requireSupportedArch(): Unit = {`,
  and in that method replace `assume(supportedArches.contains(arch),` with
  `assume(VarkaHostIsa.isX86(arch),`. The message argument on the next line stays.
- `VarkaShapeCacheSuite.scala`, the test "the AVX level is read from the JVM rather than silently
  defaulting to unknown": `val x86 = arch == "amd64" || arch == "x86_64"` becomes
  `val x86 = VarkaHostIsa.isX86(arch)`.

**The plan's record** (in this commit): replace the text of section 10, `Not started.`, with
`PR 1 (arch-list) built on varka-548: the rule, its five self-test cases and the five
test lines of 0 item 6; PR 2 (global-fault and the fault move) not started.`, followed by the
commands run and their results, as BUILD_SHEETS.md section 7 asks for the Outcome, with the
predictions of section 4 scored: P1 by B4 step 7; P2 as "five cases for PR 1, though not the
plan's five: `Defaults` is `global-fault`'s and so PR 2's, and `arch-list: ok` came in its place"; P3 by the push check's count
against 150; and in `PLAN.md`
row 548 replace the opening `**Planned** (` with
`**Started** (PR 1, the arch-list rule, built on varka-548; left: PR 2, global-fault and the floating fault's move; `
(the rest of the row unchanged).

**OUT OF SCOPE** (do not touch, even if a check suggests it):

- The `global-fault` rule, `VarkaFloatingFault`, `VarkaEmitOptions`, `DeclineSink` and every
  other part of PR 2 (questions 2 and 3).
- `VarkaHostIsaSuite.java` (exempt by question 1) and `VarkaHostIsa.java` (untouched, so #1461
  cannot conflict with this change).
- Single-literal lines (`new VarkaHostIsa.Reading("amd64", ...)` in sql/core suites), which pass.
- Non-Varka files with two literals on a line (`NativeDataSourcePackage.scala`); the rule's `is_varka` filter leaves them out on purpose.
- The shell scripts' `uname -m` mappings (section 7).
- The rule list in `sql/varka/AGENTS.md`: it does not list `nan-compare` or `ci-gates` either;
  the docstring is the rule's documentation.

### B2. Tests

Self-test cases (in `selftest()`, run by the Build's "house rules" step); the oracle is the
literal text of each fixture, which the rule never wrote:

1. `arch-list True: m/src/test/java/VarkaZSuite.java`, a `List.of("amd64", "x86_64")` loop:
   reported.
2. `arch-list False: m/src/test/java/VarkaZSuite.java`, one literal in a constructed reading:
   not reported.
3. `arch-list False: m/src/test/java/VarkaHostIsaSuite.java`, two literals in the exempt suite:
   not reported.
4. `arch-list False: m/src/main/scala/Native.scala`, two literals in a file that is not Varka's:
   not reported.
5. `arch-list False: m/src/test/java/VarkaZSuite.java`, two literals on a line marked
   `// arch-list: ok`: not reported.

The unscoped run over every Varka source (B4 step 3) is the check that master passes its own
rule; its oracle is B0 check 3's grep, which does not use the lint.

The four edited suites keep their assertions, and `VarkaHostIsaSuite` (unchanged) runs beside
them; together they prove the edits changed no behaviour:
`VarkaTwoLaneConditionSuite` ("the host test is x86 at two long lanes or fewer, and nowhere
else"), `VarkaAvx2SelectSuite`, `VarkaHostIsaSuite`, `VarkaAssemblySuite`, `VarkaShapeCacheSuite`.

### B3. Planted fault

1. In `rule_arch_list` change `>= 2` to `>= 3`. Run `python3 dev/varka_lint.py --selftest`.
   Expected: the line
   `varka_lint selftest: arch-list True: m/src/test/java/VarkaZSuite.java: expected True, got False`
   and a non-zero exit. Revert.
2. In `VarkaShapeCacheSuite.scala` put back `val x86 = arch == "amd64" || arch == "x86_64"`. Run
   B4 step 3. Expected: one `arch-list` line naming `VarkaShapeCacheSuite.scala:317`. Revert, run
   B4 step 3 again: no `arch-list` line.

### B4. Commands, in order

1. `python3 dev/varka_lint.py --selftest` -> `varka_lint selftest: ok (54 cases)`.
2. `ruff format --check dev/varka_lint.py && ruff check dev/varka_lint.py` -> `1 file already
   formatted` and `All checks passed!`.
3. `git ls-files -z '*.java' '*.scala' | grep -z Varka | xargs -0 python3 dev/varka_lint.py | grep -c arch-list`
   -> `0` (the pipeline's exit status is then `1`, `grep -c`'s for no match: expected). (The
   unscoped run also prints the known `import-root` finding at
   `VarkaMaskOfWordSuite.java:29` and exits non-zero because of it; that is expected, only the
   `arch-list` count matters.)
4. `flock "$VARKA_HEAVY_LOCK" build/sbt catalyst/Test/compile` -> `[success]`.
5. `flock "$VARKA_HEAVY_LOCK" dev/varka_matrix.sh --defaults --out target/varka-548 -j 2 --module catalyst --suites VarkaTwoLaneConditionSuite,VarkaAvx2SelectSuite,VarkaHostIsaSuite,VarkaAssemblySuite,VarkaShapeCacheSuite`
   -> exit status `0`. `VarkaAssemblySuite`'s tests may be reported as canceled: they `assume`
   an x86 host and an hsdis disassembler (`requireSupportedArch` and the disassembler lookup),
   which is not the sanitizer case of BUILD_SHEETS.md section 3, so a cancel there is expected
   and is not a failed step. Any failed test is a stop.
6. B3, both steps. Revert each by undoing its one edit by hand (never `git checkout` a file,
   which would drop B1's work).
7. `git diff --exit-code origin/master -- sql/varka/emitted_bytes.json sql/varka/coverage.json sql/varka/width_audit.json`
   exits `0` (prediction P1).
8. BUILD_SHEETS.md section 6, items 1 to 6 in its order (Error Prone and `dev/lint-java` for
   the test Java, `dev/lint-scala` for the Scala, ruff for the Python). Its last item,
   `python3 dev/varka_push_check.py`, must exit `0` with no R2 finding; every suite it chooses
   must pass (no Varka main file changes, so its rule R3 is expected to choose none). Run it after the plan record of B1 is written, so it checks the final tree; then
   the commit, as BUILD_SHEETS.md section 7 says.

### B5. Done when

`python3 dev/varka_lint.py --selftest` prints `ok (54 cases)`, B4 step 3 prints `0`, B4 step 5
and the push check exit `0`, and `git log -1 --format=%s` is the subject above.

### B6. Size

About 65 counted lines: the lint about 45 after `ruff format`, the five source edits about 10,
the plan record (`PLAN.md` row and section 10) about 10. Far under the 1000-line cap.

### B7. Stop rules

Stop and report, without guessing, when:

- any B0 check prints something other than its expected output;
- a sixth non-exempt `arch-list` line appears (a new file or a merged PR wrote one);
- the self-test count is not `54`, or any existing self-test case fails;
- a suite in B4 step 5 or the push check fails;
- anything asks you to touch an OUT OF SCOPE item.

### B8. Decisions already taken

- **Question 1 = (a)**: `VarkaHostIsaSuite` counts as `VarkaHostIsa`, so the rule skips files
  whose basename starts with `VarkaHostIsa`. The plan's recommendation, taken by the lead on 11
  November 2026.
- **Two pull requests, `arch-list` first** (section 5), so that row 551 builds on the shared list.
- Taken in this sheet: distinct literals count; `.java` and `.scala` Varka files only, comments
  removed first; `supportedArches` goes; the docstring is the only documentation.

### B9. What the owner must do afterwards

Nothing beyond the lead's usual review and push. No benchmark, no dispatch. PR 2 (the
`global-fault` rule and the fault move) waits for questions 2 and 3.
